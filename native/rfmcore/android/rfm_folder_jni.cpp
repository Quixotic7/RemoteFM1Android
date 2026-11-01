// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 RemoteFM1 contributors
// The RemoteFM1 folder for a Fantasy CFW's file API on Android: RFMFileApi.h's FsFolder over Kotlin's
// RemoteFolderHost (app/.../folder/RemoteFolder.kt: the picked SAF folder's RemoteFM1/), and the JNI face of
// app/.../folder/RemoteFolderNative.kt.
//   the calls      made on a FileApi's IO thread only. That thread is attached to the JVM on its first call and
//                  detached when it ends (a pthread key's destructor). The class and its methods are looked up once,
//                  from a Java thread (nativeInit: the app's class loader; the IO thread would only see the system's)
//   the buffers    a READ's and a WRITE's bytes go across as direct ByteBuffers over the C++ buffers (no copy)
//   LIST           Kotlin answers every entry, packed {u8 dir, u8 len, name} in out[0]; read back into FsEntry here
//   fs_changed     pushed from Kotlin: RemoteFolderHost tells RemoteFolderNative when the folder comes or goes
//                  (nativeSetFolder: fsSetFolder, the JNI folder or none) and when its files changed (nativeChanged:
//                  fsBumpChanged). RemoteFolderHost.changed() is not polled
#include <jni.h>
#include <pthread.h>

#include <android/log.h>

#include <memory>
#include <mutex>
#include <string>
#include <vector>

#include "RFMFileApi.h"

namespace {

constexpr const char *LOG_TAG = "RFMFolder";

JavaVM *gVm = nullptr;
jclass gHost = nullptr;     // RemoteFolderHost (a global ref)
jclass gBytes = nullptr;    // byte[]
jmethodID mStat, mList, mRead, mWrite, mDelete, mMkdir;
pthread_key_t gKey;
pthread_once_t gOnce = PTHREAD_ONCE_INIT;

void detach(void *)
{
    if (gVm)
        gVm->DetachCurrentThread();
}

// this thread's env, attached on its first call (and detached at its end) if it wasn't already
JNIEnv *env()
{
    if (!gVm)
        return nullptr;
    JNIEnv *e = nullptr;
    if (gVm->GetEnv(reinterpret_cast<void **>(&e), JNI_VERSION_1_6) == JNI_OK)
        return e;
    JavaVMAttachArgs a{JNI_VERSION_1_6, "RFMFolderIO", nullptr};
    if (gVm->AttachCurrentThread(&e, &a) != JNI_OK)
        return nullptr;
    pthread_once(&gOnce, [] { pthread_key_create(&gKey, detach); });
    pthread_setspecific(gKey, reinterpret_cast<void *>(1));
    return e;
}

// a Kotlin result -> FS_*: counts as they are, the codes -2..-8, anything else (an exception, -1) FS_EIO
int32_t code(JNIEnv *e, jint r)
{
    if (e->ExceptionCheck()) {
        e->ExceptionClear();
        return rfm::FS_EIO;
    }
    if (r >= 0 || (r >= rfm::FS_ENOFOLDER && r <= rfm::FS_ENOENT))
        return r;
    return rfm::FS_EIO;
}

jbyteArray bytes(JNIEnv *e, const std::string &s)
{
    jbyteArray b = e->NewByteArray((jsize)s.size());
    if (b)
        e->SetByteArrayRegion(b, 0, (jsize)s.size(), reinterpret_cast<const jbyte *>(s.data()));
    return b;
}

// one call in its own local frame (the IO thread never returns to Java, so its local refs must be let go here)
template <typename F> int32_t call(F f)
{
    JNIEnv *e = env();
    if (!e || !gHost)
        return rfm::FS_EIO;
    if (e->PushLocalFrame(8) != 0) {
        e->ExceptionClear();
        return rfm::FS_EIO;
    }
    const int32_t r = f(e);
    e->PopLocalFrame(nullptr);
    return r;
}

class JniFolder final : public rfm::FsFolder {
public:
    int32_t stat(const std::string &rel, rfm::FsStat &out) override
    {
        return call([&](JNIEnv *e) -> int32_t {
            jlongArray o = e->NewLongArray(3);
            jbyteArray p = bytes(e, rel);
            if (!o || !p)
                return code(e, rfm::FS_EIO);
            const int32_t r = code(e, e->CallStaticIntMethod(gHost, mStat, p, o));
            if (r < 0)
                return r;
            jlong v[3] = {};
            e->GetLongArrayRegion(o, 0, 3, v);
            out.size = v[0] > 0 ? (uint64_t)v[0] : 0;
            out.mtime = (uint32_t)(v[1] > 0 ? v[1] : 0);
            out.dir = v[2] != 0;
            return 0;
        });
    }
    int32_t list(const std::string &rel, std::vector<rfm::FsEntry> &out) override
    {
        return call([&](JNIEnv *e) -> int32_t {
            jobjectArray o = e->NewObjectArray(1, gBytes, nullptr);
            jbyteArray p = bytes(e, rel);
            if (!o || !p)
                return code(e, rfm::FS_EIO);
            const int32_t r = code(e, e->CallStaticIntMethod(gHost, mList, p, o));
            if (r < 0)
                return r;
            auto packed = (jbyteArray)e->GetObjectArrayElement(o, 0);
            if (!packed)
                return 0;
            const jsize n = e->GetArrayLength(packed);
            std::vector<uint8_t> b((size_t)n);
            if (n)
                e->GetByteArrayRegion(packed, 0, n, reinterpret_cast<jbyte *>(b.data()));
            for (size_t at = 0; at + 2 <= b.size();) {
                const size_t len = b[at + 1];
                if (at + 2 + len > b.size())
                    break;
                rfm::FsEntry x;
                x.dir = b[at] != 0;
                x.name.assign(reinterpret_cast<const char *>(b.data() + at + 2), len);
                out.push_back(std::move(x));
                at += 2 + len;
            }
            return 0;
        });
    }
    int32_t read(const std::string &rel, uint64_t offset, uint8_t *buf, uint32_t len) override
    {
        if (!len)
            return 0;
        return call([&](JNIEnv *e) -> int32_t {
            jobject bb = e->NewDirectByteBuffer(buf, (jlong)len);
            jbyteArray p = bytes(e, rel);
            if (!bb || !p)
                return code(e, rfm::FS_EIO);
            const int32_t r = code(e, e->CallStaticIntMethod(gHost, mRead, p, (jlong)offset, bb, (jint)len));
            return r > (int32_t)len ? (int32_t)len : r;
        });
    }
    int32_t write(const std::string &rel, int64_t offset, const uint8_t *buf, uint32_t len) override
    {
        static uint8_t none[1];
        return call([&](JNIEnv *e) -> int32_t {
            jobject bb = e->NewDirectByteBuffer(len ? const_cast<uint8_t *>(buf) : none, (jlong)len);
            jbyteArray p = bytes(e, rel);
            if (!bb || !p)
                return code(e, rfm::FS_EIO);
            return code(e, e->CallStaticIntMethod(gHost, mWrite, p, (jlong)offset, bb, (jint)len));
        });
    }
    int32_t remove(const std::string &rel) override
    {
        return call([&](JNIEnv *e) -> int32_t {
            jbyteArray p = bytes(e, rel);
            return p ? code(e, e->CallStaticIntMethod(gHost, mDelete, p)) : code(e, rfm::FS_EIO);
        });
    }
    int32_t mkdir(const std::string &rel) override
    {
        return call([&](JNIEnv *e) -> int32_t {
            jbyteArray p = bytes(e, rel);
            return p ? code(e, e->CallStaticIntMethod(gHost, mMkdir, p)) : code(e, rfm::FS_EIO);
        });
    }
};

std::mutex gMu;
std::shared_ptr<rfm::FsFolder> gFolder;    // the one JNI folder (made at nativeInit)

} // namespace

extern "C" {

// once, from a Java thread: the VM, RemoteFolderHost and its methods kept. false: not found (the log says why)
JNIEXPORT jboolean JNICALL Java_com_quixotic7_remotefm1_folder_RemoteFolderNative_nativeInit(JNIEnv *e, jclass)
{
    std::lock_guard<std::mutex> g(gMu);
    if (gFolder)
        return JNI_TRUE;
    e->GetJavaVM(&gVm);
    jclass h = e->FindClass("com/quixotic7/remotefm1/folder/RemoteFolderHost");
    jclass b = h ? e->FindClass("[B") : nullptr;
    if (!h || !b) {
        e->ExceptionClear();
        __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, "RemoteFolderHost not found");
        return JNI_FALSE;
    }
    mStat = e->GetStaticMethodID(h, "stat", "([B[J)I");
    mList = e->GetStaticMethodID(h, "list", "([B[[B)I");
    mRead = e->GetStaticMethodID(h, "read", "([BJLjava/nio/ByteBuffer;I)I");
    mWrite = e->GetStaticMethodID(h, "write", "([BJLjava/nio/ByteBuffer;I)I");
    mDelete = e->GetStaticMethodID(h, "delete", "([B)I");
    mMkdir = e->GetStaticMethodID(h, "mkdir", "([B)I");
    if (!mStat || !mList || !mRead || !mWrite || !mDelete || !mMkdir) {
        e->ExceptionClear();
        __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, "RemoteFolderHost's methods not found");
        return JNI_FALSE;
    }
    gHost = (jclass)e->NewGlobalRef(h);
    gBytes = (jclass)e->NewGlobalRef(b);
    gFolder = std::make_shared<JniFolder>();
    return JNI_TRUE;
}

// the folder every Fantasy CFW's next request uses: RemoteFolderHost's (present) or none (FS_ENOFOLDER); fs_changed moves
JNIEXPORT void JNICALL Java_com_quixotic7_remotefm1_folder_RemoteFolderNative_nativeSetFolder(JNIEnv *, jclass,
                                                                                           jboolean present)
{
    std::shared_ptr<rfm::FsFolder> f;
    {
        std::lock_guard<std::mutex> g(gMu);
        f = present ? gFolder : nullptr;
    }
    rfm::fsSetFolder(std::move(f));
}

JNIEXPORT void JNICALL Java_com_quixotic7_remotefm1_folder_RemoteFolderNative_nativeChanged(JNIEnv *, jclass)
{
    rfm::fsBumpChanged();
}

JNIEXPORT jint JNICALL Java_com_quixotic7_remotefm1_folder_RemoteFolderNative_nativeChangedCount(JNIEnv *, jclass)
{
    return (jint)rfm::fsChangedCount();
}

JNIEXPORT jlong JNICALL Java_com_quixotic7_remotefm1_folder_RemoteFolderNative_nativeReads(JNIEnv *, jclass)
{
    return (jlong)rfm::fsReadCount();
}

} // extern "C"
