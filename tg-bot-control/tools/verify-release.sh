#!/bin/bash
# Единая локальная проверка релиза BotControl (Stage 5):
#   1. Проверка отсутствия токенов (check-no-tokens.py)
#   2. Проверка изоляции ботов (check-bot-isolation.py + isolation.sh)
#   3. Офлайн JVM-тесты Android-логики (RoundTrip, V175Test, Phase1ATest, Stage2..Stage5)
#   4. Тесты desktop-web (Python unittest + Node JS generated.js)
#   5. Синхронность версий и наличие сетевой конфигурации безопасности (A10)
#
# usage: ./tools/verify-release.sh   (из tg-bot-control/ или любого места)
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

echo "== 1/5 Security & Isolation Checks =="
python3 tools/check-no-tokens.py
python3 tools/check-bot-isolation.py
tools/kotlin-harness/isolation.sh

echo "== 2/5 Offline Kotlin JVM Harness =="
HARNESS="$ROOT/tools/kotlin-harness"
SRC="$ROOT/android/app/src/main/java/com/botcontrol/admin"
OUT="/tmp/kverify"
rm -rf "$OUT" && mkdir -p "$OUT"

"$HARNESS/kc.sh" "$OUT" \
  "$SRC/data/PySource.kt" \
  "$SRC/data/ScriptImporter.kt" \
  "$SRC/data/BotExtras.kt" \
  "$SRC/data/Anim.kt" \
  "$SRC/data/SettingsExporter.kt" \
  "$SRC/data/telegram/TelegramUpdates.kt" \
  "$SRC/data/telegram/TelegramRateLimit.kt" \
  "$SRC/service/WebhookQueue.kt" \
  "$HARNESS/stubs/"*.kt \
  "$HARNESS/stubs2/"*.kt \
  "$HARNESS/src/RoundTrip.kt" \
  "$HARNESS/src/V175Test.kt" \
  "$HARNESS/src/Phase1ATest.kt" \
  "$HARNESS/src/Stage2Test.kt" \
  "$HARNESS/src/Stage3Test.kt" \
  "$HARNESS/src/Stage4Test.kt" \
  "$HARNESS/src/Stage5Test.kt" \
  "$HARNESS/src/RateLimitTest.kt"

TMP_FIX=$(mktemp /tmp/coffee_XXXXXX.py)
trap 'rm -f "$TMP_FIX"' EXIT
cp "$ROOT/tools/import-fixtures/demo-bot-coffee.py" "$TMP_FIX"

(
  cd "$HARNESS"
  ./run.sh "$OUT" RoundTripKt "$TMP_FIX" "$TMP_FIX" >/dev/null
  ./run.sh "$OUT" V175TestKt
  ./run.sh "$OUT" Phase1ATestKt
  ./run.sh "$OUT" Stage2TestKt
  ./run.sh "$OUT" Stage3TestKt
  ./run.sh "$OUT" Stage4TestKt
  ./run.sh "$OUT" Stage5TestKt "$ROOT"
  ./run.sh "$OUT" RateLimitTestKt
)

echo "== 3/5 Desktop-Web Runtime & Generator Tests =="
(
  cd "$ROOT/desktop-web"
  python3 -m unittest -v
  node test-generated.js
)

echo "== 4/5 Shared Telegram Mock Sanity =="
python3 - <<'PY'
import json
from pathlib import Path
import sys
sys.path.insert(0, 'tools/shared-telegram-mock')
from mock_telegram_server import MockTelegramState, start_mock_server
data = json.loads(Path('tools/shared-telegram-mock/contract_updates.json').read_text(encoding='utf-8'))
st = MockTelegramState()
st.enqueue_updates(data['getUpdatesResponse']['result'])
srv, _, _ = start_mock_server(st)
srv.shutdown()
srv.server_close()
print('✓ Shared Telegram Mock server OK')
PY

echo "== 5/5 Version & Security Config Check =="
GRADLE="$ROOT/android/app/build.gradle.kts"
grep -q 'versionCode = 43' "$GRADLE"
grep -q 'versionName = "2.2.0"' "$GRADLE"
grep -q "APP_VERSION = '0.7.0'" "$ROOT/desktop-web/server.py"
test -f "$ROOT/android/app/src/main/res/xml/network_security_config.xml"
echo "✓ Version 2.2.0 (code 43) & Desktop v0.7.0 & networkSecurityConfig OK"

echo ""
echo "✅ ALL RELEASE CHECKS PASSED"
