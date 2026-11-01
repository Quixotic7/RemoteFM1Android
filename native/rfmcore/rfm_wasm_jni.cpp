// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 RemoteFM1 contributors
// The JNI face of the .rfm1 loader (include/RFMWasm.h) for Kotlin (app/.../firmware/Rfm1Native.kt): a file's check,
// its texts and its SHA-256. Loading one into an engine is rfm_jni.cpp's nativeLoadWasm.
#include <jni.h>

#include <cstdio>
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

std::string esc(const char *s)
{
    std::string r = "\"";
    for (; s && *s; s++) {
        const unsigned char c = (unsigned char)*s;
        if (c == '"' || c == '\\') {
            r += '\\';
            r += (char)c;
        } else if (c < 0x20) {
            char b[8];
            std::snprintf(b, sizeof b, "\\u%04x", c);
            r += b;
        } else {
            r += (char)c;
        }
    }
    return r + "\"";
}

// NewStringUTF wants modified UTF-8: a text with bytes it might refuse goes through a byte array instead
jstring utf8(JNIEnv *env, const std::string &s)
{
    jbyteArray b = env->NewByteArray((jsize)s.size());
    if (!b)
        return nullptr;
    env->SetByteArrayRegion(b, 0, (jsize)s.size(), reinterpret_cast<const jbyte *>(s.data()));
    jclass str = env->FindClass("java/lang/String");
    jmethodID ctor = env->GetMethodID(str, "<init>", "([BLjava/lang/String;)V");
    jstring cs = env->NewStringUTF("UTF-8");
    auto *r = (jstring)env->NewObject(str, ctor, b, cs);
    env->DeleteLocalRef(b);
    env->DeleteLocalRef(cs);
    env->DeleteLocalRef(str);
    return r;
}

} // namespace

extern "C" {

// {"ok":1, id, name, version, family, license, sha256, folders, abi, flash_size, memory_bytes, file_size, load_us,
// virtual, uses_folder} or
// {"ok":0, "error": the one-line reason}
JNIEXPORT jstring JNICALL Java_com_quixotic7_remotefm1_firmware_Rfm1Native_nativeCheck(JNIEnv *env, jclass, jstring path)
{
    rfmw_info_t i{};
    char err[512] = {};
    const std::string p = jstr(env, path);
    std::string j;
    if (rfmw_check(p.c_str(), &i, err, sizeof err)) {
        char nums[256];
        std::snprintf(nums, sizeof nums, "\"abi\":%u,\"flash_size\":%u,\"memory_bytes\":%u,\"file_size\":%u,\"load_us\":%u,"
                      "\"virtual\":%u,\"uses_folder\":%u",
                      i.abi, i.flash_size, i.memory_bytes, i.file_size, i.load_us, i.is_virtual, i.uses_folder);
        j = "{\"ok\":1,\"id\":" + esc(i.id) + ",\"name\":" + esc(i.name) + ",\"version\":" + esc(i.version) +
            ",\"family\":" + esc(i.family) + ",\"license\":" + esc(i.license) + ",\"sha256\":" + esc(i.sha256) + ",\"folders\":" + esc(i.folders) + "," +
            nums + "}";
    } else {
        j = "{\"ok\":0,\"error\":" + esc(err[0] ? err : "not a valid .rfm1 file") + "}";
    }
    return utf8(env, j);
}

// "manifest", "credits", "license.<n>": the text, "" none
JNIEXPORT jstring JNICALL Java_com_quixotic7_remotefm1_firmware_Rfm1Native_nativeText(JNIEnv *env, jclass, jstring path,
                                                                                      jstring what)
{
    const std::string p = jstr(env, path), w = jstr(env, what);
    const uint32_t n = rfmw_text(p.c_str(), w.c_str(), nullptr, 0);
    if (!n)
        return utf8(env, "");
    std::vector<char> b(n + 1);
    rfmw_text(p.c_str(), w.c_str(), b.data(), n + 1);
    return utf8(env, std::string(b.data(), n));
}

// the file's SHA-256 (lowercase hex), "" when it can't be read
JNIEXPORT jstring JNICALL Java_com_quixotic7_remotefm1_firmware_Rfm1Native_nativeSha256(JNIEnv *env, jclass, jstring path)
{
    char out[65] = {};
    const std::string p = jstr(env, path);
    return utf8(env, rfmw_sha256(p.c_str(), out) ? out : "");
}

// the memory an instance may have (the app's MemoryBudget); 0: no check
JNIEXPORT void JNICALL Java_com_quixotic7_remotefm1_firmware_Rfm1Native_nativeSetMemoryBudget(JNIEnv *, jclass, jlong bytes)
{
    rfmw_set_memory_budget(bytes > 0 ? (uint64_t)bytes : 0);
}

} // extern "C"
