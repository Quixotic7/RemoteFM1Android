// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 RemoteFM1 contributors
// The test C API of the .rfm1 loader (include/RFMWasm.h: rfmw_inst_*, rfmw_set_folder) for the instrumented tests,
// through app/src/debug/.../firmware/Rfm1TestNative.kt (debug builds only call it): one instance outside an engine,
// any export by name, its memory as a direct ByteBuffer, the file API's answers applied by hand; a POSIX folder.
#include <jni.h>

#include <climits>
#include <string>
#include <vector>

#include "RFMWasm.h"

namespace {

std::string jstr(JNIEnv *env, jstring s)
{
    if (!s)
        return "";
    const char *c = env->GetStringUTFChars(s, nullptr);
    std::string r = c ? c : "";
    if (c)
        env->ReleaseStringUTFChars(s, c);
    return r;
}

rfmw_inst *inst(jlong h) { return reinterpret_cast<rfmw_inst *>(h); }

} // namespace

extern "C" {

#define T(name) Java_com_quixotic7_remotefm1_firmware_Rfm1TestNative_##name

// the app's folder as a POSIX one (null: none)
JNIEXPORT void JNICALL T(nativeSetFolder)(JNIEnv *env, jclass, jstring path)
{
    const std::string p = jstr(env, path);
    rfmw_set_folder(path ? p.c_str() : nullptr);
}

// an instance (its handle), or 0 with the reason in err[0]
JNIEXPORT jlong JNICALL T(nativeOpen)(JNIEnv *env, jclass, jstring path, jstring folder, jobjectArray err)
{
    const std::string p = jstr(env, path), f = jstr(env, folder);
    char e[1024] = {};
    rfmw_inst *i = rfmw_inst_open(p.c_str(), folder ? f.c_str() : nullptr, e, sizeof e);
    if (!i && err && env->GetArrayLength(err) > 0) {
        jstring s = env->NewStringUTF(e);
        env->SetObjectArrayElement(err, 0, s);
        env->DeleteLocalRef(s);
    }
    return reinterpret_cast<jlong>(i);
}

JNIEXPORT void JNICALL T(nativeClose)(JNIEnv *, jclass, jlong h) { rfmw_inst_close(inst(h)); }

// an export by name; Long.MIN_VALUE: none, or it trapped
JNIEXPORT jlong JNICALL T(nativeCall)(JNIEnv *env, jclass, jlong h, jstring name, jlongArray args)
{
    const std::string n = jstr(env, name);
    const jsize na = args ? env->GetArrayLength(args) : 0;
    std::vector<uint64_t> a((size_t)na + 1);
    if (na)
        env->GetLongArrayRegion(args, 0, na, reinterpret_cast<jlong *>(a.data()));
    int64_t r = 0;
    return rfmw_inst_call(inst(h), n.c_str(), a.data(), (uint32_t)na, &r) ? (jlong)r : (jlong)LLONG_MIN;
}

JNIEXPORT jobject JNICALL T(nativeMemory)(JNIEnv *env, jclass, jlong h)
{
    uint32_t n = 0;
    uint8_t *m = rfmw_inst_memory(inst(h), &n);
    return m ? env->NewDirectByteBuffer(m, (jlong)n) : nullptr;
}

JNIEXPORT void JNICALL T(nativeApply)(JNIEnv *, jclass, jlong h) { rfmw_inst_apply(inst(h)); }
JNIEXPORT jboolean JNICALL T(nativeSettle)(JNIEnv *, jclass, jlong h, jint ms)
{
    return rfmw_inst_settle(inst(h), (uint32_t)ms) ? JNI_TRUE : JNI_FALSE;
}
JNIEXPORT jboolean JNICALL T(nativeWait)(JNIEnv *, jclass, jlong h, jint ms)
{
    return rfmw_inst_wait(inst(h), (uint32_t)ms) ? JNI_TRUE : JNI_FALSE;
}
JNIEXPORT void JNICALL T(nativeBump)(JNIEnv *, jclass, jlong h) { rfmw_inst_bump(inst(h)); }
JNIEXPORT void JNICALL T(nativeTick)(JNIEnv *, jclass, jlong h, jint ms) { rfmw_inst_tick(inst(h), (uint32_t)ms); }

#undef T

} // extern "C"
