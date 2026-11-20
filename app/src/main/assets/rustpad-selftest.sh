#!@PREFIX@/bin/bash
# rustpad-selftest : فحص شامل على الجهاز. كل سطر PASS/FAIL/SKIP. الصق الناتج كاملاً.
export PATH="@PREFIX@/bin:$PATH"
P=0; F=0; S=0
ok()   { echo "PASS  $1"; P=$((P+1)); }
bad()  { echo "FAIL  $1"; [ -n "${2:-}" ] && echo "      -> $2" | head -c 600; echo; F=$((F+1)); }
skip() { echo "SKIP  $1"; S=$((S+1)); }
t()    { # t "name" cmd...
  local n="$1"; shift; local out
  if out=$("$@" 2>&1); then ok "$n"; else bad "$n" "$(echo "$out" | tail -4 | tr '\n' ' ')"; fi
}
W="$HOME/.cache/rustpad-selftest"; rm -rf "$W"; mkdir -p "$W"; cd "$W" || exit 1
echo "== RustPad selftest  ($(uname -m), $(getprop ro.product.model 2>/dev/null))"

echo "-- toolchain"
t "rustc"  rustc --version
t "cargo"  cargo --version
t "clang"  clang --version
t "git"    git --version
t "java"   java -version
t "aapt2"  aapt2 version
t "d8"     d8 --version
t "apksigner" apksigner --version
t "rustfmt" rustfmt --version
t "clippy" cargo clippy --version
t "rust target android" bash -c 'rustc --print target-list | grep -q aarch64-linux-android && ls "$(rustc --print sysroot)"/lib/rustlib/aarch64-linux-android/lib | grep -q libstd'

echo "-- build + run (hello)"
cargo new --quiet hello >/dev/null 2>&1
( cd hello && t "cargo run hello" bash -c 'cargo run -q 2>&1 | grep -q "Hello, world"' )

echo "-- lld"
if command -v ld.lld >/dev/null; then
  ( cd hello && t "build with lld" env RUSTFLAGS="-C link-arg=-fuse-ld=lld" cargo build -q --target-dir target-lld \
      && t "run lld binary" ./target-lld/debug/hello )
else skip "lld not installed"; fi

echo "-- offline crates"
V="@PREFIX@/share/rustpad/vendor"
if [ -d "$V" ]; then
  ok "vendor dir ($(ls "$V" | wc -l) crates)"
  grep -q "managed by RustPad" "$HOME/.cargo/config.toml" 2>/dev/null && ok "cargo config managed" || bad "cargo config not managed" "Settings->Offline crates may be off or file customized"
  cargo new --quiet off >/dev/null 2>&1
  ( cd off && echo 'serde_json = "1"' >> Cargo.toml && echo 'fn main(){ println!("{}", serde_json::json!({"a":1})); }' > src/main.rs
    t "offline build serde_json" env CARGO_NET_OFFLINE=true cargo build -q )
else skip "offline-crates stage not installed"; fi

echo "-- sccache"
if command -v sccache >/dev/null; then
  export SCCACHE_DIR="$HOME/.cache/sccache"
  if out=$(sccache --start-server 2>&1); then
    ok "sccache server"
    cargo new --lib --quiet sc >/dev/null 2>&1
    ( cd sc && RUSTC_WRAPPER=sccache CARGO_INCREMENTAL=0 cargo build -q 2>&1 | tail -2
      cargo clean -q; RUSTC_WRAPPER=sccache CARGO_INCREMENTAL=0 cargo build -q 2>&1 | tail -2 )
    h=$(sccache --show-stats 2>/dev/null | grep -iE "cache (hits|misses)")
    echo "$h" | sed 's/^/      /'
    echo "$h" | grep -i "hits" | grep -Eq '[1-9][0-9]*\s*$' && ok "sccache cache hit" || bad "sccache no hits" "keep sccache OFF"
    sccache --stop-server >/dev/null 2>&1
  else bad "sccache server" "$(echo "$out" | tail -2)"; fi
else skip "sccache not installed"; fi

echo "-- git"
git config --global user.name >/dev/null && ok "git identity set" || skip "git identity not set (Git menu -> Identity)"
( cd hello && git init -q 2>&1 && git add -A && git -c user.name=t -c user.email=t@t commit -qm init && ok "git init+commit" ) || bad "git init+commit"

echo "-- rust-analyzer"
if command -v rust-analyzer >/dev/null; then
  t "rust-analyzer version" rust-analyzer --version
  # بروتوكول LSP: initialize ثم خروج
  body='{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"processId":null,"rootUri":null,"capabilities":{}}}'
  r=$( { printf 'Content-Length: %d\r\n\r\n%s' ${#body} "$body"; sleep 3; } | timeout 8 rust-analyzer 2>/dev/null | head -c 200)
  echo "$r" | grep -q '"result"' && ok "rust-analyzer LSP initialize" || bad "rust-analyzer LSP initialize" "$r"
else skip "rust-analyzer not installed"; fi

echo "-- export APK tools"
[ -f "@PREFIX@/share/rustpad/shell/classes.dex" ] && ok "shell classes.dex" || bad "shell classes.dex missing"
for j in android.jar d8.jar apksigner.jar; do
  f=$(find "@PREFIX@" -name "$j" 2>/dev/null | head -1); [ -n "$f" ] && ok "$j" || bad "$j missing"
done

echo "-- learn content"
L="@PREFIX@/share/rustpad/learn"
if [ -f "$L/VERSIONS.json" ]; then
  ok "learn content ($(ls "$L/exercism/practice" | wc -l) exercism, $(grep -c '^\[\[exercises' "$L/rustlings/info.toml") rustlings)"
  t "rustpad-ex check" bash "@PREFIX@/bin/rustpad-ex" "$L/rustlings/solutions/00_intro/intro1.rs" run
else skip "learn stage not installed"; fi

echo "-- resources"
echo "      RAM: $(awk '/MemTotal/{printf "%.1f GiB",$2/1048576}' /proc/meminfo)  free: $(awk '/MemAvailable/{printf "%.1f GiB",$2/1048576}' /proc/meminfo)"
echo "      disk free: $(df -h "$HOME" | awk 'NR==2{print $4}')"
echo "      cores: $(nproc)"
echo
echo "== RESULT: $P pass, $F fail, $S skip"
rm -rf "$W"
