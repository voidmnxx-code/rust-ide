#!@PREFIX@/bin/bash
# rustpad-crates [pattern] : يعرض المكتبات المتاحة بلا اتصال (name version)
V="$PREFIX/share/rustpad/vendor"
[ -d "$V" ] || { echo "offline crates not installed (Terminal -> Bootstrap installs the offline-crates stage)"; exit 1; }
for d in "$V"/*/; do
  n=$(basename "$d")
  v=$(grep -m1 '^version' "$d/Cargo.toml" | cut -d'"' -f2)
  echo "$n $v"
done | { if [ -n "${1:-}" ]; then grep -i -- "$1"; else cat; fi; }
