// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 RemoteFM1 contributors
// librfmlzfse.so: Apple's open-source lzfse (src/, lzfse-1.0 from github.com/lzfse/lzfse, BSD-3: LICENSE) for
// com.quixotic7.remotefm1.data.Lzfse. The same LZFSE stream Apple's Compression framework writes and reads
// (COMPRESSION_LZFSE, NSData.compressed(using: .lzfse)), so the iOS app's state files and backups open here and back.
// Also built for the Mac by the JVM tests (LzfseHost in app/src/test).
#include <jni.h>
#include <stdlib.h>
#include "src/lzfse.h"

// the bytes compressed; null when they don't fit (never for a flash: an incompressible block is stored as it is)
JNIEXPORT jbyteArray JNICALL Java_com_quixotic7_remotefm1_data_Lzfse_nEncode(JNIEnv *env, jclass cls, jbyteArray src) {
    (void)cls;
    jsize n = (*env)->GetArrayLength(env, src);
    size_t cap = (size_t)n + (size_t)n / 16 + 4096;
    uint8_t *dst = malloc(cap);
    void *scratch = malloc(lzfse_encode_scratch_size());
    jbyte *in = (*env)->GetByteArrayElements(env, src, NULL);
    jbyteArray out = NULL;
    if (dst && scratch && in) {
        size_t z = lzfse_encode_buffer(dst, cap, (const uint8_t *)in, (size_t)n, scratch);
        if (z > 0) {
            out = (*env)->NewByteArray(env, (jsize)z);
            if (out) (*env)->SetByteArrayRegion(env, out, 0, (jsize)z, (const jbyte *)dst);
        }
    }
    if (in) (*env)->ReleaseByteArrayElements(env, src, in, JNI_ABORT);
    free(scratch);
    free(dst);
    return out;
}

// the stream decompressed into at most [cap] bytes (the caller passes one more than it expects, so a longer one
// shows); null when it can't be read
JNIEXPORT jbyteArray JNICALL Java_com_quixotic7_remotefm1_data_Lzfse_nDecode(JNIEnv *env, jclass cls, jbyteArray src,
                                                                            jint cap) {
    (void)cls;
    if (cap <= 0) return NULL;
    jsize n = (*env)->GetArrayLength(env, src);
    uint8_t *dst = malloc((size_t)cap);
    void *scratch = malloc(lzfse_decode_scratch_size());
    jbyte *in = (*env)->GetByteArrayElements(env, src, NULL);
    jbyteArray out = NULL;
    if (dst && scratch && in && n > 0) {
        size_t r = lzfse_decode_buffer(dst, (size_t)cap, (const uint8_t *)in, (size_t)n, scratch);
        if (r > 0) {
            out = (*env)->NewByteArray(env, (jsize)r);
            if (out) (*env)->SetByteArrayRegion(env, out, 0, (jsize)r, (const jbyte *)dst);
        }
    }
    if (in) (*env)->ReleaseByteArrayElements(env, src, in, JNI_ABORT);
    free(scratch);
    free(dst);
    return out;
}
