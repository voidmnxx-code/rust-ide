cmake_minimum_required(VERSION 3.18)
project(rustpad_ts C)
set(CMAKE_C_STANDARD 11)
set(TS ${CMAKE_CURRENT_SOURCE_DIR}/ts)

add_library(rustpad_ts SHARED
    ts_highlight.c
    ts_jni.c
    ${TS}/runtime/lib/src/lib.c
    ${TS}/rust/src/parser.c
    ${TS}/rust/src/scanner.c)
target_include_directories(rustpad_ts PRIVATE
    ${TS}/runtime/lib/include ${TS}/runtime/lib/src ${TS}/rust/src)
target_compile_options(rustpad_ts PRIVATE -O2 -fvisibility=hidden -Wno-unused-parameter)
target_link_options(rustpad_ts PRIVATE -Wl,-z,max-page-size=16384)
target_link_libraries(rustpad_ts log)
