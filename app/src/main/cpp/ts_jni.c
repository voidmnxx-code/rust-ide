#include <jni.h>
#include <stdlib.h>
#include "ts_highlight.h"

/* dev.rustpad.TreeSitter.nativeHighlight(String): int[]  (ثلاثيات start,end,class) */
JNIEXPORT jintArray JNICALL
Java_dev_rustpad_TreeSitter_nativeHighlight(JNIEnv *env, jclass cls, jstring s) {
    if (s == NULL) return NULL;
    jsize len = (*env)->GetStringLength(env, s);
    const jchar *chars = (*env)->GetStringChars(env, s, NULL);
    if (chars == NULL) return NULL;
    int *buf = NULL; int n = 0;
    int rc = ts_hl_run((const uint16_t *)chars, (uint32_t)len, &buf, &n);
    (*env)->ReleaseStringChars(env, s, chars);
    if (rc != 0) { free(buf); return NULL; }
    jintArray arr = (*env)->NewIntArray(env, n);
    if (arr != NULL && n > 0) (*env)->SetIntArrayRegion(env, arr, 0, n, buf);
    free(buf);
    return arr;
}
