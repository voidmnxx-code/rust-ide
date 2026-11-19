#include "ts_highlight.h"
#include <stdlib.h>
#include <string.h>
#include <stdbool.h>
#include <tree_sitter/api.h>

const TSLanguage *tree_sitter_rust(void);

typedef struct { int *v; int n, cap; } Buf;

static bool push(Buf *b, uint32_t sbyte, uint32_t ebyte, int cls) {
    if (ebyte <= sbyte) return true;
    if (b->n + 3 > b->cap) {
        int ncap = b->cap ? b->cap * 2 : 2048;
        int *nv = realloc(b->v, (size_t)ncap * sizeof(int));
        if (!nv) return false;
        b->v = nv; b->cap = ncap;
    }
    b->v[b->n++] = (int)(sbyte / 2);   /* UTF-16: 2 بايت لكل char */
    b->v[b->n++] = (int)(ebyte / 2);
    b->v[b->n++] = cls;
    return true;
}

static bool push_node(Buf *b, TSNode n, int cls) {
    if (ts_node_is_null(n)) return true;
    return push(b, ts_node_start_byte(n), ts_node_end_byte(n), cls);
}

static bool is_keyword_token(const char *t) {
    if (!t[0]) return false;
    for (const char *p = t; *p; p++) {
        if (!((*p >= 'a' && *p <= 'z') || *p == '_')) return false;
    }
    return t[0] != '_';
}

static TSNode field(TSNode n, const char *name) {
    return ts_node_child_by_field_name(n, name, (uint32_t)strlen(name));
}

/* يعيد 1 للنزول إلى الأبناء، 0 للتخطي، -1 عند نفاد الذاكرة */
static int visit(TSNode n, Buf *b) {
    const char *t = ts_node_type(n);
    bool ok = true;
    if (!ts_node_is_named(n)) {
        if (is_keyword_token(t)) ok = push_node(b, n, HL_KEYWORD);
        return ok ? 0 : -1;
    }
    if (!strcmp(t, "line_comment") || !strcmp(t, "block_comment")) ok = push_node(b, n, HL_COMMENT);
    else if (!strcmp(t, "string_literal") || !strcmp(t, "raw_string_literal") || !strcmp(t, "char_literal")) ok = push_node(b, n, HL_STRING);
    else if (!strcmp(t, "integer_literal") || !strcmp(t, "float_literal")) ok = push_node(b, n, HL_NUMBER);
    else if (!strcmp(t, "boolean_literal") || !strcmp(t, "self") || !strcmp(t, "super") ||
             !strcmp(t, "crate") || !strcmp(t, "mutable_specifier")) ok = push_node(b, n, HL_KEYWORD);
    else if (!strcmp(t, "type_identifier") || !strcmp(t, "primitive_type")) ok = push_node(b, n, HL_TYPE);
    else if (!strcmp(t, "attribute_item") || !strcmp(t, "inner_attribute_item")) ok = push_node(b, n, HL_ATTR);
    else if (!strcmp(t, "lifetime")) ok = push_node(b, n, HL_LIFETIME);
    else {
        if (!strcmp(t, "macro_invocation")) {
            ok = push_node(b, field(n, "macro"), HL_MACRO);
        } else if (!strcmp(t, "function_item") || !strcmp(t, "function_signature_item")) {
            ok = push_node(b, field(n, "name"), HL_FUNC);
        } else if (!strcmp(t, "call_expression")) {
            TSNode f = field(n, "function");
            if (!ts_node_is_null(f)) {
                const char *ft = ts_node_type(f);
                if (!strcmp(ft, "identifier")) ok = push_node(b, f, HL_FUNC);
                else if (!strcmp(ft, "field_expression")) ok = push_node(b, field(f, "field"), HL_FUNC);
                else if (!strcmp(ft, "scoped_identifier")) ok = push_node(b, field(f, "name"), HL_FUNC);
            }
        }
        return ok ? 1 : -1;
    }
    return ok ? 0 : -1;
}

int ts_hl_run(const uint16_t *text, uint32_t nchars, int **out, int *nints) {
    *out = NULL; *nints = 0;
    TSParser *parser = ts_parser_new();
    if (!parser) return 1;
    if (!ts_parser_set_language(parser, tree_sitter_rust())) { ts_parser_delete(parser); return 2; }
    TSTree *tree = ts_parser_parse_string_encoding(parser, NULL, (const char *)text, nchars * 2, TSInputEncodingUTF16LE);
    if (!tree) { ts_parser_delete(parser); return 3; }

    Buf b = {0};
    int rc = 0;
    TSTreeCursor cur = ts_tree_cursor_new(ts_tree_root_node(tree));
    for (;;) {
        int d = visit(ts_tree_cursor_current_node(&cur), &b);
        if (d < 0) { rc = 4; break; }
        if (d == 1 && ts_tree_cursor_goto_first_child(&cur)) continue;
        bool more = true;
        while (!ts_tree_cursor_goto_next_sibling(&cur)) {
            if (!ts_tree_cursor_goto_parent(&cur)) { more = false; break; }
        }
        if (!more) break;
    }
    ts_tree_cursor_delete(&cur);
    ts_tree_delete(tree);
    ts_parser_delete(parser);
    if (rc) { free(b.v); return rc; }
    *out = b.v; *nints = b.n;
    return 0;
}
