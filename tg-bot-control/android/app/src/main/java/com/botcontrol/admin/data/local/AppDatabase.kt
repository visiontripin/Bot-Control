package com.botcontrol.admin.data.local

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Entity(tableName = "drafts")
data class DraftEntity(
    @PrimaryKey val pluginId: String,
    val code: String,
    val manifest: String,
    val config: String,
    val updatedAt: Long = System.currentTimeMillis(),
)

@Dao
interface DraftDao {
    @Query("SELECT * FROM drafts WHERE pluginId = :pluginId")
    suspend fun get(pluginId: String): DraftEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(draft: DraftEntity)

    @Query("DELETE FROM drafts WHERE pluginId = :pluginId")
    suspend fun delete(pluginId: String)
}

@Entity(tableName = "bot_rules")
data class BotRuleEntity(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val type: String,          // "command" | "contains" | "button" | "regex"
    val pattern: String,
    val responseText: String,
    val enabled: Boolean = true,
    val actionType: String = "text", // "text" | "script" | "llm" | "pack" | "anim" | "dice" | "media" | "poll"
    val script: String = "",         // JS source when actionType == "script"
    val packId: String = "",         // набор ответов when actionType == "pack"
    val menu: String = "",           // JSON: inline-кнопки под ответом
    val botId: Long = 1L,            // какому боту принадлежит правило
    val options: String = "",        // JSON RuleOptions: parseMode, флаги, медиа, опрос
)

@Dao
interface BotRuleDao {
    @Query("SELECT * FROM bot_rules WHERE botId = :botId ORDER BY id")
    suspend fun byBot(botId: Long): List<BotRuleEntity>

    @Query("SELECT * FROM bot_rules ORDER BY id")
    suspend fun all(): List<BotRuleEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(rule: BotRuleEntity): Long

    @Query("DELETE FROM bot_rules WHERE id = :id")
    suspend fun delete(id: Int)
}

@Entity(tableName = "bot_users", primaryKeys = ["botId", "chatId"])
data class BotUserEntity(
    val botId: Long,
    val chatId: Long,
    val userId: Long = 0L,
    val username: String = "",
    val firstName: String = "",
    val chatType: String = "",       // "private" | "group" | "supergroup" | "channel"
    val role: String = "user",       // "user" | "vip" | "admin"
    val tags: String = "",           // через запятую
    val isBlocked: Boolean = false,  // 403 / бот заблокирован пользователем
    val isBanned: Boolean = false,   // ручной бан владельцем
    val varsJson: String = "{}",     // пользовательские переменные {var} из FSM
    val firstSeenAt: Long = 0L,
    val lastSeenAt: Long = 0L,
    val msgCount: Int = 0,
)

@Dao
interface BotUserDao {
    @Query("SELECT * FROM bot_users WHERE botId = :botId ORDER BY lastSeenAt DESC")
    suspend fun byBot(botId: Long): List<BotUserEntity>

    @Query("SELECT * FROM bot_users WHERE botId = :botId AND chatId = :chatId LIMIT 1")
    suspend fun get(botId: Long, chatId: Long): BotUserEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(user: BotUserEntity)

    @Query("DELETE FROM bot_users WHERE botId = :botId AND chatId = :chatId")
    suspend fun delete(botId: Long, chatId: Long)

    @Query("DELETE FROM bot_users WHERE botId = :botId")
    suspend fun deleteByBot(botId: Long)
}

@Entity(tableName = "bot_kv", primaryKeys = ["botId", "scopeKey", "kvKey"])
data class BotKvEntity(
    val botId: Long,
    val scopeKey: String,   // "u:<userId>" или "global"
    val kvKey: String,
    val kvValue: String = "",
    val updatedAt: Long = 0L,
)

@Dao
interface BotKvDao {
    @Query("SELECT * FROM bot_kv WHERE botId = :botId AND scopeKey = :scopeKey AND kvKey = :kvKey LIMIT 1")
    suspend fun get(botId: Long, scopeKey: String, kvKey: String): BotKvEntity?

    @Query("SELECT * FROM bot_kv WHERE botId = :botId AND scopeKey = :scopeKey ORDER BY kvKey")
    suspend fun listScope(botId: Long, scopeKey: String): List<BotKvEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entry: BotKvEntity)

    @Query("DELETE FROM bot_kv WHERE botId = :botId AND scopeKey = :scopeKey AND kvKey = :kvKey")
    suspend fun delete(botId: Long, scopeKey: String, kvKey: String)

    @Query("DELETE FROM bot_kv WHERE botId = :botId")
    suspend fun deleteByBot(botId: Long)
}

@Database(
    entities = [DraftEntity::class, BotRuleEntity::class, BotUserEntity::class, BotKvEntity::class],
    version = 7,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun draftDao(): DraftDao
    abstract fun botRuleDao(): BotRuleDao
    abstract fun botUserDao(): BotUserDao
    abstract fun botKvDao(): BotKvDao

    companion object {
        @Volatile
        private var instance: AppDatabase? = null

        private val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS bot_users (
                        botId INTEGER NOT NULL,
                        chatId INTEGER NOT NULL,
                        userId INTEGER NOT NULL DEFAULT 0,
                        username TEXT NOT NULL DEFAULT '',
                        firstName TEXT NOT NULL DEFAULT '',
                        chatType TEXT NOT NULL DEFAULT '',
                        role TEXT NOT NULL DEFAULT 'user',
                        tags TEXT NOT NULL DEFAULT '',
                        isBlocked INTEGER NOT NULL DEFAULT 0,
                        isBanned INTEGER NOT NULL DEFAULT 0,
                        varsJson TEXT NOT NULL DEFAULT '{}',
                        firstSeenAt INTEGER NOT NULL DEFAULT 0,
                        lastSeenAt INTEGER NOT NULL DEFAULT 0,
                        msgCount INTEGER NOT NULL DEFAULT 0,
                        PRIMARY KEY(botId, chatId)
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS bot_kv (
                        botId INTEGER NOT NULL,
                        scopeKey TEXT NOT NULL,
                        kvKey TEXT NOT NULL,
                        kvValue TEXT NOT NULL DEFAULT '',
                        updatedAt INTEGER NOT NULL DEFAULT 0,
                        PRIMARY KEY(botId, scopeKey, kvKey)
                    )
                    """.trimIndent()
                )
            }
        }

        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE bot_rules ADD COLUMN options TEXT NOT NULL DEFAULT ''")
            }
        }

        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE bot_rules ADD COLUMN botId INTEGER NOT NULL DEFAULT 1")
            }
        }

        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE bot_rules ADD COLUMN packId TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE bot_rules ADD COLUMN menu TEXT NOT NULL DEFAULT ''")
            }
        }

        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE bot_rules ADD COLUMN actionType TEXT NOT NULL DEFAULT 'text'")
                db.execSQL("ALTER TABLE bot_rules ADD COLUMN script TEXT NOT NULL DEFAULT ''")
            }
        }

        fun get(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "botcontrol.db",
                ).addMigrations(MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7)
                    .fallbackToDestructiveMigration()
                    .build().also { instance = it }
            }
    }
}
