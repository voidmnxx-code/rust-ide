#ifndef TS_HIGHLIGHT_H
#define TS_HIGHLIGHT_H
#include <stdint.h>

enum { HL_KEYWORD = 1, HL_STRING, HL_COMMENT, HL_NUMBER, HL_TYPE, HL_MACRO, HL_ATTR, HL_FUNC, HL_LIFETIME };

/* text: UTF-16LE (كما في Java). يملأ *out بمصفوفة من ثلاثيات [start, end, class] بوحدات char.
 * يعيد 0 عند النجاح. على المستدعي استدعاء free(*out). */
int ts_hl_run(const uint16_t *text, uint32_t nchars, int **out, int *nints);
#endif
