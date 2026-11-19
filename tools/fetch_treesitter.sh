#!/usr/bin/env bash
# يجلب مصادر tree-sitter وقواعد Rust (إصدارات مثبّتة) إلى app/src/main/cpp/ts
set -euo pipefail
cd "$(dirname "$0")/../app/src/main/cpp"
rm -rf ts && mkdir ts
git clone -q --depth 1 --branch v0.27.2 https://github.com/tree-sitter/tree-sitter ts/runtime
git clone -q --depth 1 --branch v0.24.2 https://github.com/tree-sitter/tree-sitter-rust ts/rust
rm -rf ts/runtime/.git ts/rust/.git
echo "tree-sitter v0.27.2 + tree-sitter-rust v0.24.2 fetched"
