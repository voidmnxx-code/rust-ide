#!@PREFIX@/bin/bash
# rustpad-ex <file.rs> <check|run|test> : يترجم تمرين rustlings بملف واحد ويشغّله.
f="$1"; mode="${2:-run}"
[ -f "$f" ] || { echo "no such file: $f"; exit 2; }
minor=$(rustc --version | sed -E 's/^rustc 1\.([0-9]+).*/\1/')
ed=2021; [ "${minor:-0}" -ge 85 ] 2>/dev/null && ed=2024
out="${TMPDIR:-/tmp}/rustpad-ex-$$"
trap 'rm -f "$out" "$out".*' EXIT
case "$mode" in
  check) rustc --edition "$ed" --crate-type bin --emit=metadata -o "$out.rmeta" "$f"; exit $? ;;
  test)  rustc --edition "$ed" --test -o "$out" "$f" || exit $?; "$out"; exit $? ;;
  *)     rustc --edition "$ed" -o "$out" "$f" || exit $?; "$out"; exit $? ;;
esac
