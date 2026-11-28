package com.botcontrol.admin.llm

import com.botcontrol.admin.data.BotMatcher
import com.botcontrol.admin.data.MessageCtx
import org.mozilla.javascript.BaseFunction
import org.mozilla.javascript.ClassShutter
import org.mozilla.javascript.Context
import org.mozilla.javascript.ContextFactory
import org.mozilla.javascript.Function
import org.mozilla.javascript.Scriptable
import org.mozilla.javascript.ScriptableObject

/**
 * Сессия персистентного KV-хранилища для JS-скрипта (объект `db`).
 * Загружается из Room до запуска `handle(e)` и сохраняет изменения после.
 */
class ScriptDbSession(
    initialUser: Map<String, String> = emptyMap(),
    initialGlobal: Map<String, String> = emptyMap(),
) {
    private val userMap = initialUser.toMutableMap()
    private val globalMap = initialGlobal.toMutableMap()

    val userWrites = linkedMapOf<String, String>()
    val userDeletes = linkedSetOf<String>()
    val globalWrites = linkedMapOf<String, String>()
    val globalDeletes = linkedSetOf<String>()

    fun get(key: String): String? = userMap[key]

    fun set(key: String, value: String) {
        val k = key.take(128)
        if (k.isBlank()) return
        val v = value.take(8000)
        userMap[k] = v
        userWrites[k] = v
        userDeletes.remove(k)
    }

    fun del(key: String) {
        userMap.remove(key)
        userWrites.remove(key)
        userDeletes.add(key)
    }

    fun getGlobal(key: String): String? = globalMap[key]

    fun setGlobal(key: String, value: String) {
        val k = key.take(128)
        if (k.isBlank()) return
        val v = value.take(8000)
        globalMap[k] = v
        globalWrites[k] = v
        globalDeletes.remove(k)
    }

    fun delGlobal(key: String) {
        globalMap.remove(key)
        globalWrites.remove(key)
        globalDeletes.add(key)
    }

    fun userSnapshot(): Map<String, String> = userMap.toMap()
    fun globalSnapshot(): Map<String, String> = globalMap.toMap()
}

/** Ответ внешнего HTTP-запроса для JS-скрипта (`http.get` / `http.post`). */
data class ScriptHttpResponse(
    val ok: Boolean = false,
    val status: Int = 0,
    val body: String = "",
    val error: String = "",
)

/** Мост исходящих HTTP-запросов из JS-песочницы (разрешены только домены белого списка). */
interface ScriptHttpBridge {
    fun request(
        method: String,
        url: String,
        body: String? = null,
        headers: Map<String, String> = emptyMap(),
    ): ScriptHttpResponse
}

/**
 * Local script engine for bot logic («выполнение скриптов по кнопке»).
 * JavaScript (Mozilla Rhino, interpreter mode) in a sandbox:
 *  - no Java access from scripts (safe standard objects + ClassShutter);
 *  - pure interpreter (optimizationLevel=-1) — safe on Android.
 *
 * Script contract:
 *   function handle(e) { ... return "text reply" }
 * where:
 *   e    = { user, text, chatId, username, userId, chatType, args, messageId,
 *            replyToText, kind, fileId, caption, mimeType, fileName, fileSize, localPath }
 *   db   = { get(k, def?), set(k, v), del(k), incr(k, d?), keys(),
 *            getGlobal(k, def?), setGlobal(k, v), delGlobal(k), incrGlobal(k, d?), globalKeys() }
 *   http = { get(url, headers?), post(url, bodyOrObj?, headers?) }
 */
object BotScript {

    const val EXAMPLE = """function handle(e) {
  var t = e.text.toLowerCase();
  var visits = db.incr("visits", 1);
  if (t.indexOf("привет") >= 0)
    return "Привет, " + e.user + "! Заходов: " + visits;
  if (t.indexOf("время") >= 0)
    return "Сейчас " + new Date().toLocaleTimeString();
  return "Ты написал: " + e.text + " (всего: " + visits + ")";
}"""

    private fun jsFn(block: (Context, Scriptable, Array<out Any?>) -> Any?): BaseFunction =
        object : BaseFunction() {
            override fun call(
                cx: Context?,
                scope: Scriptable?,
                thisObj: Scriptable?,
                args: Array<out Any?>?,
            ): Any? {
                val c = cx ?: Context.getCurrentContext()
                val s = scope ?: thisObj ?: return null
                return block(c, s, args ?: emptyArray())
            }
        }

    private fun attachDb(cx: Context, scope: Scriptable, session: ScriptDbSession) {
        val dbObj = cx.newObject(scope)

        fun argStr(args: Array<out Any?>, idx: Int): String? {
            val v = args.getOrNull(idx)
            if (v == null || v === org.mozilla.javascript.Undefined.instance) return null
            return Context.toString(v)
        }

        ScriptableObject.putProperty(dbObj, "get", jsFn { _, _, args ->
            val k = argStr(args, 0) ?: return@jsFn null
            val v = session.get(k)
            if (v != null) v else argStr(args, 1)
        })
        ScriptableObject.putProperty(dbObj, "set", jsFn { _, _, args ->
            val k = argStr(args, 0) ?: return@jsFn false
            val v = argStr(args, 1) ?: ""
            session.set(k, v)
            true
        })
        ScriptableObject.putProperty(dbObj, "del", jsFn { _, _, args ->
            val k = argStr(args, 0) ?: return@jsFn false
            session.del(k)
            true
        })
        ScriptableObject.putProperty(dbObj, "incr", jsFn { _, _, args ->
            val k = argStr(args, 0) ?: return@jsFn 0
            val delta = argStr(args, 1)?.toLongOrNull() ?: 1L
            val cur = session.get(k)?.toLongOrNull() ?: 0L
            val next = cur + delta
            session.set(k, next.toString())
            next.toDouble()
        })
        ScriptableObject.putProperty(dbObj, "keys", jsFn { c, s, _ ->
            val keys = session.userSnapshot().keys.toTypedArray()
            c.newArray(s, keys)
        })

        ScriptableObject.putProperty(dbObj, "getGlobal", jsFn { _, _, args ->
            val k = argStr(args, 0) ?: return@jsFn null
            val v = session.getGlobal(k)
            if (v != null) v else argStr(args, 1)
        })
        ScriptableObject.putProperty(dbObj, "setGlobal", jsFn { _, _, args ->
            val k = argStr(args, 0) ?: return@jsFn false
            val v = argStr(args, 1) ?: ""
            session.setGlobal(k, v)
            true
        })
        ScriptableObject.putProperty(dbObj, "delGlobal", jsFn { _, _, args ->
            val k = argStr(args, 0) ?: return@jsFn false
            session.delGlobal(k)
            true
        })
        ScriptableObject.putProperty(dbObj, "incrGlobal", jsFn { _, _, args ->
            val k = argStr(args, 0) ?: return@jsFn 0
            val delta = argStr(args, 1)?.toLongOrNull() ?: 1L
            val cur = session.getGlobal(k)?.toLongOrNull() ?: 0L
            val next = cur + delta
            session.setGlobal(k, next.toString())
            next.toDouble()
        })
        ScriptableObject.putProperty(dbObj, "globalKeys", jsFn { c, s, _ ->
            val keys = session.globalSnapshot().keys.toTypedArray()
            c.newArray(s, keys)
        })

        ScriptableObject.putProperty(scope, "db", dbObj)
    }

    private fun attachHttp(cx: Context, scope: Scriptable, bridge: ScriptHttpBridge?) {
        val httpObj = cx.newObject(scope)

        fun argStr(args: Array<out Any?>, idx: Int): String? {
            val v = args.getOrNull(idx)
            if (v == null || v === org.mozilla.javascript.Undefined.instance) return null
            return if (v is Scriptable) {
                val jsonMod = ScriptableObject.getProperty(scope, "JSON") as? Scriptable
                val stringifyFn = jsonMod?.let { ScriptableObject.getProperty(it, "stringify") as? Function }
                if (stringifyFn != null) {
                    Context.toString(stringifyFn.call(cx, scope, jsonMod, arrayOf(v)))
                } else Context.toString(v)
            } else Context.toString(v)
        }

        fun argHeaders(args: Array<out Any?>, idx: Int): Map<String, String> {
            val v = args.getOrNull(idx) as? Scriptable ?: return emptyMap()
            val out = linkedMapOf<String, String>()
            for (id in v.ids) {
                val k = id?.toString() ?: continue
                val raw = v.get(k, v)
                if (raw != null && raw !== org.mozilla.javascript.Undefined.instance) {
                    out[k] = Context.toString(raw)
                }
            }
            return out
        }

        fun toJsResp(c: Context, s: Scriptable, r: ScriptHttpResponse): Scriptable {
            val obj = c.newObject(s)
            ScriptableObject.putProperty(obj, "ok", r.ok)
            ScriptableObject.putProperty(obj, "status", r.status)
            ScriptableObject.putProperty(obj, "body", r.body)
            ScriptableObject.putProperty(obj, "error", r.error)
            val jsonVal: Any? = if (r.body.trimStart().let { it.startsWith("{") || it.startsWith("[") }) {
                runCatching {
                    val jsonMod = ScriptableObject.getProperty(s, "JSON") as? Scriptable
                    val parseFn = jsonMod?.let { ScriptableObject.getProperty(it, "parse") as? Function }
                    parseFn?.call(c, s, jsonMod, arrayOf(r.body))
                }.getOrNull()
            } else null
            ScriptableObject.putProperty(obj, "json", jsonVal)
            return obj
        }

        fun perform(c: Context, s: Scriptable, method: String, url: String?, body: String?, headers: Map<String, String>): Scriptable {
            if (url.isNullOrBlank()) {
                return toJsResp(c, s, ScriptHttpResponse(ok = false, error = "не указан URL"))
            }
            if (bridge == null) {
                return toJsResp(c, s, ScriptHttpResponse(ok = false, error = "HTTP-мост не настроен для этого контекста"))
            }
            val resp = bridge.request(method, url, body, headers)
            return toJsResp(c, s, resp)
        }

        ScriptableObject.putProperty(httpObj, "get", jsFn { c, s, args ->
            perform(c, s, "GET", argStr(args, 0), null, argHeaders(args, 1))
        })
        ScriptableObject.putProperty(httpObj, "post", jsFn { c, s, args ->
            perform(c, s, "POST", argStr(args, 0), argStr(args, 1), argHeaders(args, 2))
        })
        ScriptableObject.putProperty(scope, "http", httpObj)
    }

    private class ScriptTimeoutError(msg: String) : Error(msg)

    private object SandboxedFactory : ContextFactory() {
        private val DEADLINE_KEY = Any()

        override fun makeContext(): Context {
            val cx = super.makeContext()
            cx.optimizationLevel = -1 // interpreter: no bytecode generation on ART
            cx.instructionObserverThreshold = 10_000
            cx.setClassShutter(ClassShutter { false }) // no java.* from scripts
            return cx
        }

        override fun observeInstructionCount(cx: Context?, instructionCount: Int) {
            val deadline = cx?.getThreadLocal(DEADLINE_KEY) as? Long ?: return
            if (System.currentTimeMillis() > deadline) {
                throw ScriptTimeoutError("Превышен лимит времени выполнения скрипта (2 с)")
            }
        }

        fun setDeadline(cx: Context, timeoutMs: Long) {
            cx.putThreadLocal(DEADLINE_KEY, System.currentTimeMillis() + timeoutMs)
        }

        fun clearDeadline(cx: Context) {
            cx.putThreadLocal(DEADLINE_KEY, null)
        }
    }

    fun run(
        source: String,
        user: String,
        text: String,
        chatId: Long,
        ctx: MessageCtx? = null,
        dbSession: ScriptDbSession? = null,
        httpBridge: ScriptHttpBridge? = null,
    ): Result<String> {
        if (source.length > 64_000) {
            return Result.failure(IllegalArgumentException("Скрипт слишком большой (максимум 64 КБ)"))
        }
        val cx = SandboxedFactory.enterContext()
        SandboxedFactory.setDeadline(cx, 2_000L)
        try {
            val scope = cx.initSafeStandardObjects()
            attachDb(cx, scope, dbSession ?: ScriptDbSession())
            attachHttp(cx, scope, httpBridge)
            cx.evaluateString(scope, source, "bot_script", 1, null)
            val fn = scope.get("handle", scope) as? Function
                ?: return Result.failure(IllegalStateException("В скрипте нет функции handle(e)"))
            val arg = cx.newObject(scope)
            ScriptableObject.putProperty(arg, "user", user)
            ScriptableObject.putProperty(arg, "text", text)
            ScriptableObject.putProperty(arg, "chatId", chatId.toString())
            ScriptableObject.putProperty(arg, "username", ctx?.username.orEmpty())
            ScriptableObject.putProperty(arg, "userId", (ctx?.userId ?: chatId).toString())
            ScriptableObject.putProperty(arg, "chatType", ctx?.chatType.orEmpty())
            ScriptableObject.putProperty(arg, "args", BotMatcher.extractCommandArgsRaw(text))
            ScriptableObject.putProperty(arg, "messageId", ctx?.messageId ?: 0)
            ScriptableObject.putProperty(arg, "replyToText", ctx?.replyToText.orEmpty())
            ScriptableObject.putProperty(arg, "kind", ctx?.kind.orEmpty())
            ScriptableObject.putProperty(arg, "fileId", ctx?.fileId.orEmpty())
            ScriptableObject.putProperty(arg, "caption", ctx?.caption.orEmpty())
            ScriptableObject.putProperty(arg, "mimeType", ctx?.mimeType.orEmpty())
            ScriptableObject.putProperty(arg, "fileName", ctx?.fileName.orEmpty())
            ScriptableObject.putProperty(arg, "fileSize", (ctx?.fileSize ?: 0L).toDouble())
            ScriptableObject.putProperty(arg, "localPath", ctx?.localMediaPath.orEmpty())
            val result = fn.call(cx, scope, scope, arrayOf(arg))
            return Result.success(Context.toString(result).take(16_000))
        } catch (e: Throwable) {
            return Result.failure(
                IllegalStateException("${e.javaClass.simpleName}: ${e.message ?: "ошибка скрипта"}"))
        } finally {
            SandboxedFactory.clearDeadline(cx)
            Context.exit()
        }
    }
}
