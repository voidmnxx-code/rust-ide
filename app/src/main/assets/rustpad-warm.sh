#!@PREFIX@/bin/bash
# rustpad-warm : يبني مكتبات شائعة مرة واحدة عبر sccache لتسريع المشاريع اللاحقة (وهو أيضاً اختبار لـ sccache).
set -euo pipefail
command -v sccache >/dev/null || { echo "ERROR: sccache not installed (Bootstrap -> offline-crates stage)"; exit 1; }
export SCCACHE_DIR="$HOME/.cache/sccache" SCCACHE_CACHE_SIZE=4G
mkdir -p "$SCCACHE_DIR"
echo "== sccache self-test"
sccache --start-server || { echo "ERROR: sccache server failed to start - keep sccache OFF in Settings"; exit 2; }
sccache --version
export RUSTC_WRAPPER=sccache

W="$HOME/.cache/rustpad-warm"
mkdir -p "$W/src"
cat > "$W/Cargo.toml" <<'TOML'
[package]
name = "warm"
version = "0.1.0"
edition = "2021"

[dependencies]
serde = { version = "1", features = ["derive"] }
serde_json = "1"
tokio = { version = "1", features = ["full"] }
rand = "0.9"
anyhow = "1"
regex = "1"
clap = { version = "4", features = ["derive"] }
chrono = { version = "0.4", features = ["serde"] }

[profile.dev]
opt-level = 1
debug = 0
incremental = true
TOML
echo 'fn main() {}' > "$W/src/main.rs"
JOBS=$(( $(nproc) / 2 )); [ "$JOBS" -ge 1 ] || JOBS=1
echo "== cargo build (this takes a while, -j$JOBS)"
cd "$W" && cargo build -j "$JOBS"
echo "== sccache stats"
sccache --show-stats | head -12
echo "DONE. If it worked, enable 'Use sccache' in Settings."
