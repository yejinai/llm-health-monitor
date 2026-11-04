#!/usr/bin/env bash
# ==============================================================================
# Zeli News Encrypted Module Reassembler & RAM (tmpfs) Runtime Launcher
# - Reassembles <=15MB encrypted chunks (No Git LFS)
# - Verifies SHA-256 integrity
# - Decrypts into RAM (/dev/shm/zeliai_news_runtime, chmod 700)
# - Shreds decrypted Node.js source files from RAM immediately after V8 load
# ==============================================================================
set -euo pipefail

REPO_DIR="$(cd "$(dirname "$0")" && pwd)"
KEY_FILE="${ZELI_ENC_KEY_FILE:-/home/ubuntu/.zeliai_enc_key}"
RUNTIME_ROOT="/dev/shm/zeliai_news_runtime"
CAST_ENV_SRC="/home/ubuntu/zeliai/zeliai_web_new_git/backend_cast/.env"
CAST_NM_SRC="/home/ubuntu/zeliai/zeliai_web_new_git/backend_cast/node_modules"
PRESS_ENV_SRC="/home/ubuntu/zeliai/zeliai_web_new_git/backend_press/secrets.press.env"

if [ ! -f "$KEY_FILE" ]; then
  echo "[ERROR] Master decryption key not found at $KEY_FILE" >&2
  exit 1
fi

cd "$REPO_DIR"
echo "[1/5] Verifying chunk integrity (SHA256SUMS)..."
sha256sum -c chunks/SHA256SUMS

echo "[2/5] Preparing isolated RAM runtime directory ($RUNTIME_ROOT)..."
rm -rf "$RUNTIME_ROOT"
mkdir -p "$RUNTIME_ROOT/cast" "$RUNTIME_ROOT/press"
chmod 700 "$RUNTIME_ROOT" "$RUNTIME_ROOT/cast" "$RUNTIME_ROOT/press"

echo "[3/5] Reassembling & decrypting zeli_cast_bundle into RAM..."
cat chunks/cast/zeli_cast_bundle.tar.gz.enc.part.* | \
  openssl enc -d -aes-256-cbc -pbkdf2 -iter 100000 -salt -pass "file:$KEY_FILE" | \
  tar -xzf - -C "$RUNTIME_ROOT/cast"

ln -s "$CAST_NM_SRC" "$RUNTIME_ROOT/cast/node_modules"
if [ -f "$CAST_ENV_SRC" ]; then
  cp "$CAST_ENV_SRC" "$RUNTIME_ROOT/cast/.env"
  chmod 600 "$RUNTIME_ROOT/cast/.env"
fi

echo "[4/5] Reassembling & decrypting zeli_press_jar into RAM..."
cat chunks/press/zeli_press_jar.enc.part.* | \
  openssl enc -d -aes-256-cbc -pbkdf2 -iter 100000 -salt -pass "file:$KEY_FILE" \
  > "$RUNTIME_ROOT/press/zeliai-press-ktor-all.jar"
chmod 600 "$RUNTIME_ROOT/press/zeliai-press-ktor-all.jar"

if [ "${1:-}" = "--verify-only" ]; then
  echo "[5/5] Verification mode complete. Decrypted artifacts verified in $RUNTIME_ROOT."
  ls -lh "$RUNTIME_ROOT/cast" "$RUNTIME_ROOT/press"
  exit 0
fi

echo "[5/5] Restarting backend_cast (:5171) and backend_press (:8091) from encrypted RAM runtime..."
# Restart backend_cast in screen
screen -S backend_cast -X quit || true
pkill -f "node index.js" || true
sleep 1
screen -dmS backend_cast bash -c "cd '$RUNTIME_ROOT/cast' && exec node index.js >> /tmp/backend_cast_enc.log 2>&1"

# Wait for backend_cast V8 engine to finish loading modules, then shred plaintext .js from RAM!
for i in {1..15}; do
  if curl -fsS http://127.0.0.1:5171/api/cast/health >/dev/null 2>&1; then
    rm -rf "$RUNTIME_ROOT/cast/index.js" "$RUNTIME_ROOT/cast/services"
    rmdir "$RUNTIME_ROOT/cast/services" 2>/dev/null || true
    echo "  -> backend_cast (:5171) UP & plaintext .js wiped from RAM (/dev/shm)!"
    break
  fi
  sleep 1
done

# Restart backend_press in screen
screen -S backend_press -X quit || true
pkill -f "zeliai-press-ktor-all.jar" || true
sleep 1
screen -dmS backend_press bash -c "cd '$RUNTIME_ROOT/press' && set -a && [ -f '$PRESS_ENV_SRC' ] && . '$PRESS_ENV_SRC' && set +a && exec java -jar '$RUNTIME_ROOT/press/zeliai-press-ktor-all.jar' >> /tmp/backend_press_enc.log 2>&1"

for i in {1..20}; do
  if curl -fsS http://127.0.0.1:8091/api/press/health >/dev/null 2>&1; then
    echo "  -> backend_press (:8091) UP from encrypted RAM runtime!"
    break
  fi
  sleep 1
done

echo "[DONE] Encrypted Zeli News modules are live."
