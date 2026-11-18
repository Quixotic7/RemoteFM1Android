// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 RemoteFM1 contributors
// JNI for the Ableton Move (rfmm_*, include/RFMMove.h, the API RemoteFM1IOS ad33e95 has; MoveLink.cpp and
// rfm1cs-abletonmove's session under it), and its project file (store.h rfm1cs_file_*):
// move/MoveNative.kt. A handle is an rfmm_t pointer; requests and audio are copied out per call.
#include <jni.h>

#include <algorithm>
#include <cstdio>
#include <cstring>
#include <string>
#include <vector>

#include "RFMMove.h"
#include "rfm1cs/store.h"

namespace {
rfmm_t *mv(jlong h) { return reinterpret_cast<rfmm_t *>(h); }

std::vector<uint8_t> bytes(JNIEnv *env, jbyteArray a)
{
    std::vector<uint8_t> v;
    if (!a)
        return v;
    v.resize((size_t)env->GetArrayLength(a));
    if (!v.empty())
        env->GetByteArrayRegion(a, 0, (jsize)v.size(), reinterpret_cast<jbyte *>(v.data()));
    return v;
}
const uint8_t *ptr(const std::vector<uint8_t> &v) { return v.empty() ? nullptr : v.data(); }
} // namespace

#define F(name) Java_com_quixotic7_remotefm1_move_MoveNative_##name

extern "C" {

JNIEXPORT jlong JNICALL F(nativeNew)(JNIEnv *env, jclass, jbyteArray settings)
{
    const std::vector<uint8_t> s = bytes(env, settings);
    return (jlong)rfmm_new(ptr(s), (uint32_t)s.size(), "RemoteFM1 Android");
}

JNIEXPORT void JNICALL F(nativeFree)(JNIEnv *, jclass, jlong h) { rfmm_free(mv(h)); }

JNIEXPORT void JNICALL F(nativeStart)(JNIEnv *env, jclass, jlong h, jstring host, jstring shown)
{
    const char *s = env->GetStringUTFChars(host, nullptr), *w = env->GetStringUTFChars(shown, nullptr);
    rfmm_start_shown(mv(h), s, w);
    env->ReleaseStringUTFChars(shown, w);
    env->ReleaseStringUTFChars(host, s);
}

JNIEXPORT void JNICALL F(nativeStop)(JNIEnv *, jclass, jlong h) { rfmm_stop(mv(h)); }

// out (17 ints): state via battery mode project playing ticking inputs leds screens connects seq_midi following
// bpm10 clicks tone attempt; returns "name\ntext\ntoast"
JNIEXPORT jstring JNICALL F(nativeStatus)(JNIEnv *env, jclass, jlong h, jintArray out)
{
    rfmm_status_t s;
    rfmm_status(mv(h), &s);
    const jint v[17] = {s.state, s.via, s.battery, s.mode, s.project, s.playing, s.ticking, (jint)s.inputs,
                        (jint)s.leds, (jint)s.screens, (jint)s.connects, (jint)s.seq_midi, s.following, s.bpm10,
                        (jint)s.clicks, s.tone, (jint)s.attempt};
    env->SetIntArrayRegion(out, 0, 17, v);
    std::string t = std::string(s.name) + "\n" + s.text + "\n" + s.toast;
    return env->NewStringUTF(t.c_str());
}

// the next request: out = kind, slot, value, total; its data into `data`; returns the data's length (-1: none)
JNIEXPORT jint JNICALL F(nativeTakeRequest)(JNIEnv *env, jclass, jlong h, jintArray out, jbyteArray data)
{
    static thread_local std::vector<uint8_t> buf(rfmm_project_max());
    rfmm_request_t r{};
    if (!rfmm_take_request(mv(h), &r, buf.data(), (uint32_t)buf.size()))
        return -1;
    const jint v[4] = {r.kind, r.slot, r.value, (jint)r.total};
    env->SetIntArrayRegion(out, 0, 4, v);
    if (r.len && data && env->GetArrayLength(data) >= (jsize)r.len)
        env->SetByteArrayRegion(data, 0, (jsize)r.len, reinterpret_cast<const jbyte *>(buf.data()));
    return (jint)r.len;
}

JNIEXPORT void JNICALL F(nativeProjectsInit)(JNIEnv *env, jclass, jlong h, jint dir, jint slot, jbyteArray blob)
{
    const std::vector<uint8_t> b = bytes(env, blob);
    rfmm_projects_init(mv(h), (uint32_t)dir, slot, ptr(b), (uint32_t)b.size());
}
JNIEXPORT void JNICALL F(nativeProjectDir)(JNIEnv *, jclass, jlong h, jint bits) { rfmm_project_dir(mv(h), (uint32_t)bits); }
JNIEXPORT void JNICALL F(nativeProjectInfo)(JNIEnv *env, jclass, jlong h, jint slot, jbyteArray blob)
{
    const std::vector<uint8_t> b = bytes(env, blob);
    rfmm_project_info(mv(h), slot, ptr(b), (uint32_t)b.size());
}
JNIEXPORT void JNICALL F(nativeProjectAnswer)(JNIEnv *env, jclass, jlong h, jint slot, jbyteArray blob)
{
    const std::vector<uint8_t> b = bytes(env, blob);
    rfmm_project_answer(mv(h), slot, ptr(b), (uint32_t)b.size());
}
JNIEXPORT void JNICALL F(nativeProjectFlush)(JNIEnv *, jclass, jlong h) { rfmm_project_flush(mv(h)); }
JNIEXPORT jint JNICALL F(nativeProjectCheck)(JNIEnv *env, jclass, jbyteArray blob)
{
    const std::vector<uint8_t> b = bytes(env, blob);
    return rfmm_project_check(ptr(b), (uint32_t)b.size());
}
JNIEXPORT jbyteArray JNICALL F(nativeWithColor)(JNIEnv *env, jclass, jbyteArray blob, jint color)
{
    std::vector<uint8_t> b = bytes(env, blob);
    rfmm_project_with_color(b.data(), (uint32_t)b.size(), color);
    jbyteArray a = env->NewByteArray((jsize)b.size());
    env->SetByteArrayRegion(a, 0, (jsize)b.size(), reinterpret_cast<const jbyte *>(b.data()));
    return a;
}
JNIEXPORT jint JNICALL F(nativeProjectMax)(JNIEnv *, jclass) { return (jint)rfmm_project_max(); }

// ---- the project file (.rfmmove / .rfmmoveset, "RMVP"): rfm1cs-abletonmove's, as the web player and iOS write it
// entries: slots[i], saved[i] (Unix seconds, 0 unknown), blobs[i]; kind 0: one project for one entry, else a set. The
// file, or null (more than 32 entries, a slot out of range)
JNIEXPORT jbyteArray JNICALL F(nativeFilePack)(JNIEnv *env, jclass, jintArray slots, jintArray saved, jobjectArray blobs,
                                               jint kind)
{
    const jsize n = env->GetArrayLength(blobs);
    if (n > RFM1CS_SLOTS || env->GetArrayLength(slots) < n || env->GetArrayLength(saved) < n)
        return nullptr;
    std::vector<std::vector<uint8_t>> keep((size_t)n);
    rfm1cs_file_entry_t e[RFM1CS_SLOTS];
    jint sl[RFM1CS_SLOTS], sv[RFM1CS_SLOTS];
    env->GetIntArrayRegion(slots, 0, n, sl);
    env->GetIntArrayRegion(saved, 0, n, sv);
    for (jsize i = 0; i < n; i++) {
        jbyteArray b = (jbyteArray)env->GetObjectArrayElement(blobs, i);
        keep[(size_t)i] = bytes(env, b);
        env->DeleteLocalRef(b);
        if (sl[i] < 0 || sl[i] >= RFM1CS_SLOTS)
            return nullptr;
        e[i] = rfm1cs_file_entry_t{sl[i], (uint32_t)sv[i], ptr(keep[(size_t)i]), (uint32_t)keep[(size_t)i].size()};
    }
    std::vector<uint8_t> out(rfm1cs_file_size(e, n));
    const uint32_t len = rfm1cs_file_pack(e, n, kind, out.data(), (uint32_t)out.size());
    if (!len)
        return nullptr;
    jbyteArray a = env->NewByteArray((jsize)len);
    env->SetByteArrayRegion(a, 0, (jsize)len, reinterpret_cast<const jbyte *>(out.data()));
    return a;
}

// a file read, every blob checked (rfm_move_project_check): its blobs, with meta[0] the kind and meta[1 + 2i],
// meta[2 + 2i] entry i's slot and saved (Unix seconds); or null with why[0] what is wrong ("Not a Move project file:
// ...", in the web player's words, "this app")
JNIEXPORT jobjectArray JNICALL F(nativeFileUnpack)(JNIEnv *env, jclass, jbyteArray file, jintArray meta,
                                                   jobjectArray why)
{
    const std::vector<uint8_t> f = bytes(env, file);
    rfm1cs_file_entry_t e[RFM1CS_SLOTS];
    rfm1cs_file_error_t err;
    int kind = 0;
    const int n = file ? rfm1cs_file_unpack(ptr(f), (uint32_t)f.size(), &kind, e, &err)
                       : rfm1cs_file_unpack(nullptr, 0, &kind, e, &err);
    if (n < 0) {
        char t[200];
        rfm1cs_file_error_text(&err, "app", t, sizeof t);
        if (why && env->GetArrayLength(why) > 0)
            env->SetObjectArrayElement(why, 0, env->NewStringUTF(t));
        return nullptr;
    }
    jint m[1 + 2 * RFM1CS_SLOTS] = {kind};
    for (int i = 0; i < n; i++)
        m[1 + 2 * i] = e[i].slot, m[2 + 2 * i] = (jint)e[i].saved;
    env->SetIntArrayRegion(meta, 0, std::min<jsize>(1 + 2 * n, env->GetArrayLength(meta)), m);
    jobjectArray out = env->NewObjectArray(n, env->FindClass("[B"), nullptr);
    for (int i = 0; i < n; i++) {
        jbyteArray a = env->NewByteArray((jsize)e[i].len);
        env->SetByteArrayRegion(a, 0, (jsize)e[i].len, reinterpret_cast<const jbyte *>(e[i].bytes));
        env->SetObjectArrayElement(out, i, a);
        env->DeleteLocalRef(a);
    }
    return out;
}
JNIEXPORT jbyteArray JNICALL F(nativeTestEmptyProject)(JNIEnv *env, jclass)
{
    std::vector<uint8_t> b(rfmm_project_max());
    const int n = rfmm_test_empty_project(b.data(), (uint32_t)b.size());
    if (n <= 0)
        return nullptr;
    jbyteArray a = env->NewByteArray(n);
    env->SetByteArrayRegion(a, 0, n, reinterpret_cast<const jbyte *>(b.data()));
    return a;
}

JNIEXPORT jint JNICALL F(nativeTakeAudio)(JNIEnv *env, jclass, jlong h, jshortArray stereo)
{
    const jsize cap = env->GetArrayLength(stereo) / 2;
    std::vector<int16_t> b((size_t)cap * 2);
    const uint32_t n = rfmm_take_audio(mv(h), b.data(), (uint32_t)cap);
    if (n)
        env->SetShortArrayRegion(stereo, 0, (jsize)(2 * n), b.data());
    return (jint)n;
}

// ---- tests without a network
JNIEXPORT void JNICALL F(nativeTestInput)(JNIEnv *, jclass, jlong h, jint packet) { rfmm_test_input(mv(h), (uint32_t)packet); }
JNIEXPORT void JNICALL F(nativeTestConnected)(JNIEnv *, jclass, jlong h, jboolean up) { rfmm_test_connected(mv(h), up ? 1 : 0); }
JNIEXPORT void JNICALL F(nativeTestDrop)(JNIEnv *, jclass, jlong h) { rfmm_test_drop(mv(h)); }
JNIEXPORT void JNICALL F(nativeTestClose)(JNIEnv *, jclass, jlong h, jint attempt) { rfmm_test_close(mv(h), (uint32_t)attempt); }
JNIEXPORT jint JNICALL F(nativeTestTakeLeds)(JNIEnv *env, jclass, jlong h, jbyteArray out)
{
    const jsize max = env->GetArrayLength(out) / 6;
    std::vector<uint8_t> b((size_t)max * 6);
    const int n = rfmm_test_take_leds(mv(h), b.data(), max);
    if (n)
        env->SetByteArrayRegion(out, 0, n * 6, reinterpret_cast<const jbyte *>(b.data()));
    return n;
}
JNIEXPORT jboolean JNICALL F(nativeTestTakeScreen)(JNIEnv *env, jclass, jlong h, jbyteArray out)
{
    uint8_t b[1024];
    if (!rfmm_test_take_screen(mv(h), b))
        return JNI_FALSE;
    env->SetByteArrayRegion(out, 0, 1024, reinterpret_cast<const jbyte *>(b));
    return JNI_TRUE;
}

} // extern "C"
