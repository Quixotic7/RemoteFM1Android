// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 RemoteFM1 contributors
// The JNI face of the engine for Kotlin (app/.../RfmEngine.kt): the core loader (rfms_*), one engine (rfme_*) with
// its Oboe output (rfmo_*), on an FM-1 link (RfmLink, rfm_link_jni.cpp) or headless, and offline renders for tests.
// Status comes back as JSON text.
#include <jni.h>

#include <algorithm>
#include <cmath>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <mutex>
#include <string>
#include <vector>

#include "RFMEngine.h"
#include "RFMCore.h"
#include "RFMOboe.h"
#include "RFMRealtime.h"
#include "RFMWasm.h"

namespace {

struct Host {
    rfme_t *e = nullptr;
    rfmo_t *out = nullptr;
    // the engine's clock has one owner at a time: the calls that set it, or stop it and give it back (load, boot,
    // unload, power cycle, the final flash, offline renders), come from the main thread and the audio-control thread
    // (AudioControl.kt's clock claims), so they are serialised here. Held only for the engine call, never through an
    // Oboe call (which may block in Android).
    std::mutex ctl;
    rfmo_stream_t *direct = nullptr;    // nativeStartAudio's stream (tests' synchronous path), under ctl
};

using Lock = std::lock_guard<std::mutex>;

Host *host(jlong h) { return reinterpret_cast<Host *>(h); }

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

// JSON string escaping for the descriptor's text
std::string esc(const char *s)
{
    std::string r;
    for (; s && *s; s++) {
        const unsigned char c = (unsigned char)*s;
        if (c == '"' || c == '\\') {
            r += '\\';
            r += (char)c;
        } else if (c == '\n') {
            r += "\\n";
        } else if (c < 0x20) {
            char b[8];
            std::snprintf(b, sizeof b, "\\u%04x", c);
            r += b;
        } else {
            r += (char)c;
        }
    }
    return r;
}

struct Json {
    std::string s = "{";
    void key(const char *k)
    {
        if (s.size() > 1)
            s += ',';
        s += '"';
        s += k;
        s += "\":";
    }
    void num(const char *k, double v)
    {
        key(k);
        char b[40];
        if (std::isfinite(v))
            std::snprintf(b, sizeof b, "%.6g", v);
        else
            std::snprintf(b, sizeof b, "null");
        s += b;
    }
    void str(const char *k, const char *v)
    {
        key(k);
        s += '"' + esc(v) + '"';
    }
    void raw(const char *k, const std::string &v)
    {
        key(k);
        s += v;
    }
    std::string done() { return s + "}"; }
};

jstring utf(JNIEnv *env, const std::string &s) { return env->NewStringUTF(s.c_str()); }

} // namespace

extern "C" {

JNIEXPORT jint JNICALL Java_com_quixotic7_remotefm1_RfmEngine_nativeScan(JNIEnv *env, jclass, jstring dir)
{
    return rfms_scan(jstr(env, dir).c_str());
}

JNIEXPORT jstring JNICALL Java_com_quixotic7_remotefm1_RfmEngine_nativeCores(JNIEnv *env, jclass)
{
    std::string a = "[";
    for (int i = 0; i < rfms_count(); i++) {
        rfms_info_t c;
        if (!rfms_info(i, &c))
            continue;
        Json j;
        j.str("id", c.id);
        j.str("name", c.name);
        j.str("version", c.version);
        j.str("family", c.family);
        j.str("license", c.license);
        j.num("abi", c.abi);
        j.num("flash_size", c.flash_size);
        j.num("in_use", c.in_use);
        j.num("retired", c.retired);
        a += (a.size() > 1 ? "," : "") + j.done();
    }
    char err[1024];
    rfms_errors(err, sizeof err);
    Json r;
    r.raw("cores", a + "]");
    r.str("errors", err);
    return utf(env, r.done());
}

// link: an RfmLink's handle (its rfmc_t, which must outlive the engine), 0 headless
JNIEXPORT jlong JNICALL Java_com_quixotic7_remotefm1_RfmEngine_nativeCreate(JNIEnv *, jclass, jlong link)
{
    auto *h = new Host;
    h->e = rfme_new(reinterpret_cast<rfmc_t *>(link));
    h->out = rfmo_new(h->e);
    return reinterpret_cast<jlong>(h);
}

JNIEXPORT void JNICALL Java_com_quixotic7_remotefm1_RfmEngine_nativeDestroy(JNIEnv *, jclass, jlong p)
{
    Host *h = host(p);
    if (!h)
        return;
    {
        Lock l(h->ctl);
        if (h->direct) {
            rfmo_close(h->direct);
            h->direct = nullptr;
        }
    }
    rfmo_free(h->out);
    rfme_free(h->e);
    delete h;
}

// "" ok, else the error
// (A4) an .rfm1 file (wasm3: RFMWasmCore.cpp, rfme_load_wasm): checked, then its own instance for this engine;
// "" ok, else the one-line reason
JNIEXPORT jstring JNICALL Java_com_quixotic7_remotefm1_RfmEngine_nativeLoadWasm(JNIEnv *env, jclass, jlong p, jstring path)
{
    Lock l(host(p)->ctl);
    char err[512] = {};
    const int ok = rfme_load_wasm(host(p)->e, jstr(env, path).c_str(), err, sizeof err);
    return utf(env, ok ? "" : (err[0] ? err : "load failed"));
}

JNIEXPORT jstring JNICALL Java_com_quixotic7_remotefm1_RfmEngine_nativeLoad(JNIEnv *env, jclass, jlong p, jstring id)
{
    Lock l(host(p)->ctl);
    char err[512] = {};
    const int ok = rfme_load(host(p)->e, jstr(env, id).c_str(), err, sizeof err);
    return utf(env, ok ? "" : (err[0] ? err : "load failed"));
}

JNIEXPORT jboolean JNICALL Java_com_quixotic7_remotefm1_RfmEngine_nativeBoot(JNIEnv *env, jclass, jlong p, jbyteArray flash)
{
    Lock l(host(p)->ctl);
    std::vector<uint8_t> img;
    if (flash) {
        img.resize((size_t)env->GetArrayLength(flash));
        env->GetByteArrayRegion(flash, 0, (jsize)img.size(), reinterpret_cast<jbyte *>(img.data()));
    }
    return rfme_boot(host(p)->e, img.empty() ? nullptr : img.data(), (uint32_t)img.size()) ? JNI_TRUE : JNI_FALSE;
}

// attached: the engine plays the link's panel and shows on the unit; the link's test pattern stands aside
JNIEXPORT void JNICALL Java_com_quixotic7_remotefm1_RfmEngine_nativeAttach(JNIEnv *, jclass, jlong p, jboolean on)
{
    rfme_attach(host(p)->e, on ? 1 : 0);
}

// tests: the echo core (its MIDI in back out, silence)
JNIEXPORT jboolean JNICALL Java_com_quixotic7_remotefm1_RfmEngine_nativeTestLoadEcho(JNIEnv *, jclass, jlong p)
{
    Lock l(host(p)->ctl);
    return rfme_test_load_echo(host(p)->e) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL Java_com_quixotic7_remotefm1_RfmEngine_nativeSetMidi(JNIEnv *, jclass, jlong p, jint keyChannel,
                                                                             jint transpose, jboolean keyNotes,
                                                                             jboolean useMap)
{
    rfme_set_midi(host(p)->e, keyChannel, transpose, keyNotes ? 1 : 0, useMap ? 1 : 0);
}

JNIEXPORT void JNICALL Java_com_quixotic7_remotefm1_RfmEngine_nativeMidiOutEnable(JNIEnv *, jclass, jlong p, jint on)
{
    rfme_midi_out_enable(host(p)->e, on);
}

JNIEXPORT void JNICALL Java_com_quixotic7_remotefm1_RfmEngine_nativeSetOffline(JNIEnv *, jclass, jlong p, jboolean on)
{
    rfme_set_offline(host(p)->e, on ? 1 : 0);
}

// tests (the caller set RFME_CLOCK_AUDIO, offline): `count` renders of `frames` at `rate` (rfme_render_midi, no host
// MIDI); returns the core's MIDI out packets taken after each, and the audio's sum of squares and samples in `level`
// ([sum, n, peak])
JNIEXPORT jintArray JNICALL Java_com_quixotic7_remotefm1_RfmEngine_nativeRenderBlocks(JNIEnv *env, jclass, jlong p,
                                                                                      jint count, jint frames,
                                                                                      jdouble rate, jdoubleArray level)
{
    Host *h = host(p);
    std::vector<float> L((size_t)frames), R((size_t)frames);
    std::vector<jint> pk;
    double sum = 0, peak = 0, n = 0;
    for (int i = 0; i < count; i++) {
        rfme_render_midi(h->e, L.data(), R.data(), (uint32_t)frames, rate, nullptr, 0);
        for (int k = 0; k < frames; k++) {
            sum += (double)L[(size_t)k] * L[(size_t)k] + (double)R[(size_t)k] * R[(size_t)k];
            peak = std::fmax(peak, std::fmax(std::fabs((double)L[(size_t)k]), std::fabs((double)R[(size_t)k])));
        }
        n += 2.0 * frames;
        for (uint32_t pkt = 0; rfme_midi_out_take(h->e, &pkt);)
            pk.push_back((jint)pkt);
    }
    if (level && env->GetArrayLength(level) >= 3) {
        const jdouble v[3] = {sum, n, peak};
        env->SetDoubleArrayRegion(level, 0, 3, v);
    }
    jintArray a = env->NewIntArray((jsize)pk.size());
    if (a && !pk.empty())
        env->SetIntArrayRegion(a, 0, (jsize)pk.size(), pk.data());
    return a;
}

JNIEXPORT void JNICALL Java_com_quixotic7_remotefm1_RfmEngine_nativeSetClock(JNIEnv *, jclass, jlong p, jint owner)
{
    Lock l(host(p)->ctl);
    rfme_set_clock(host(p)->e, owner);
}

// a note key (0..26) down or up, as the FM-1 sends it
JNIEXPORT jboolean JNICALL Java_com_quixotic7_remotefm1_RfmEngine_nativeKey(JNIEnv *, jclass, jlong p, jint key, jboolean down,
                                                                             jint vel)
{
    return rfme_event_vel(host(p)->e, RFMC_EV_KEY, (uint8_t)key, down ? 1 : 0, (uint8_t)vel) ? JNI_TRUE : JNI_FALSE;
}

static int readIds(JNIEnv *env, jintArray a, int32_t *ids, int max)
{
    if (!a)
        return 0;
    int nf = env->GetArrayLength(a);
    if (nf > max)
        nf = max;
    env->GetIntArrayRegion(a, 0, nf, reinterpret_cast<jint *>(ids));
    return nf;
}

// (tests, one thread) the synchronous path: a stream opened, started, made active, the clock given to it
JNIEXPORT jstring JNICALL Java_com_quixotic7_remotefm1_RfmEngine_nativeStartAudio(JNIEnv *env, jclass, jlong p, jint device,
                                                                                  jboolean usbSafe, jintArray forbidden)
{
    Host *h = host(p);
    if (h->direct)
        return utf(env, "already playing");
    char err[160] = {};
    int32_t ids[16] = {};
    const int nf = readIds(env, forbidden, ids, 16);
    rfmo_stream_t *s = rfmo_open(h->out, device, usbSafe ? 1 : 0, ids, nf, err, sizeof err);
    if (!s)
        return utf(env, err[0] ? err : "open failed");
    if (!rfmo_start(s, err, sizeof err)) {
        rfmo_close(s);
        return utf(env, err[0] ? err : "start failed");
    }
    rfmo_activate(s);
    Lock l(h->ctl);
    h->direct = s;
    rfme_set_clock(h->e, RFME_CLOCK_AUDIO);
    return utf(env, "");
}

JNIEXPORT void JNICALL Java_com_quixotic7_remotefm1_RfmEngine_nativeStopAudio(JNIEnv *, jclass, jlong p, jint clockAfter)
{
    Host *h = host(p);
    rfmo_stream_t *s;
    {
        Lock l(h->ctl);
        s = h->direct;
        h->direct = nullptr;
        if (rfme_clock(h->e) == RFME_CLOCK_AUDIO)
            rfme_set_clock(h->e, clockAfter);
    }
    if (s)
        rfmo_close(s);
}

// ---- the audio-control thread's streams (AudioControl.kt; each call on a worker of its, any may block in Android)
// out[0] = the stream; "" or the error
JNIEXPORT jstring JNICALL Java_com_quixotic7_remotefm1_RfmEngine_nativeStreamOpen(JNIEnv *env, jclass, jlong p, jint device,
                                                                                  jboolean usbSafe, jintArray forbidden,
                                                                                  jlongArray out)
{
    char err[160] = {};
    int32_t ids[16] = {};
    const int nf = readIds(env, forbidden, ids, 16);
    rfmo_stream_t *s = rfmo_open(host(p)->out, device, usbSafe ? 1 : 0, ids, nf, err, sizeof err);
    const jlong v = reinterpret_cast<jlong>(s);
    env->SetLongArrayRegion(out, 0, 1, &v);
    return utf(env, s ? "" : (err[0] ? err : "open failed"));
}

JNIEXPORT jstring JNICALL Java_com_quixotic7_remotefm1_RfmEngine_nativeStreamStart(JNIEnv *env, jclass, jlong s)
{
    char err[160] = {};
    return utf(env, rfmo_start(reinterpret_cast<rfmo_stream_t *>(s), err, sizeof err) ? "" : (err[0] ? err : "start failed"));
}

JNIEXPORT void JNICALL Java_com_quixotic7_remotefm1_RfmEngine_nativeStreamActivate(JNIEnv *, jclass, jlong s)
{
    rfmo_activate(reinterpret_cast<rfmo_stream_t *>(s));
}

JNIEXPORT void JNICALL Java_com_quixotic7_remotefm1_RfmEngine_nativeStreamDeactivate(JNIEnv *, jclass, jlong s)
{
    rfmo_deactivate(reinterpret_cast<rfmo_stream_t *>(s));
}

// (static: never touches the engine, which may be gone when an abandoned close returns)
JNIEXPORT void JNICALL Java_com_quixotic7_remotefm1_RfmEngine_nativeStreamClose(JNIEnv *, jclass, jlong s)
{
    rfmo_close(reinterpret_cast<rfmo_stream_t *>(s));
}

// out = [frames, callbacks, dead, device, usb_safe, mmap, api, sharing, performance, rate, burst]
JNIEXPORT void JNICALL Java_com_quixotic7_remotefm1_RfmEngine_nativeStreamInfo(JNIEnv *env, jclass, jlong s, jboolean refresh,
                                                                               jlongArray out)
{
    rfmo_stream_info_t i;
    rfmo_stream_info(reinterpret_cast<rfmo_stream_t *>(s), refresh ? 1 : 0, &i);
    const jlong v[11] = {(jlong)i.frames, i.callbacks, i.dead, i.device_id, i.usb_safe, i.mmap, i.api, i.sharing,
                         i.performance, i.rate, i.frames_per_burst};
    env->SetLongArrayRegion(out, 0, 11, v);
}

// the clock moved from `from` to `to` only if it is `from` now (the audio thread's claims: TIMER -> AUDIO when its
// stream plays, AUDIO -> TIMER when it goes; a clock the main thread stopped meanwhile stays stopped)
JNIEXPORT jboolean JNICALL Java_com_quixotic7_remotefm1_RfmEngine_nativeClockSwap(JNIEnv *, jclass, jlong p, jint from, jint to)
{
    Host *h = host(p);
    Lock l(h->ctl);
    if (rfme_clock(h->e) != from)
        return JNI_FALSE;
    rfme_set_clock(h->e, to);
    return JNI_TRUE;
}

JNIEXPORT void JNICALL Java_com_quixotic7_remotefm1_RfmEngine_nativeTestAudioHang(JNIEnv *, jclass, jlong p, jint closeMs, jint openMs)
{
    rfmo_test_hang(host(p)->out, closeMs, openMs);
}

JNIEXPORT jstring JNICALL Java_com_quixotic7_remotefm1_RfmEngine_nativeStatus(JNIEnv *env, jclass, jlong p)
{
    Host *h = host(p);
    {
        Lock l(h->ctl);                 // (the tests' synchronous stream: its getters refreshed on their thread)
        if (h->direct) {
            rfmo_stream_info_t i;
            rfmo_stream_info(h->direct, 1, &i);
        }
    }
    rfme_status_t s;
    rfme_status(h->e, &s);
    rfmo_status_t o;
    rfmo_status(h->out, &o);
    Json j;
    j.num("loaded", s.loaded);
    j.num("booted", s.booted);
    j.num("halted", s.halted);
    j.num("halt_code", s.halt_code);
    j.num("slot", s.slot);
    j.num("attached", s.attached);
    j.num("clock", s.clock);
    j.str("core_id", s.core_id);
    j.str("kind", s.kind);
    j.num("dev_ms", s.dev_ms);
    j.num("lcd_writes", s.lcd_writes);
    j.num("events_applied", s.events_applied);
    j.num("keys_pressed", s.keys_pressed);
    j.num("events_dropped", s.events_dropped);
    j.num("key_vel_last", s.key_vel_last);
    j.num("key_lean_last", s.key_lean_last);
    j.num("midi_out", s.midi_out);
    j.num("midi_to_core", s.midi_to_core);
    j.num("midi_in", s.midi_in);
    j.num("midi_notes", s.midi_notes);
    j.num("midi_in_dropped", s.midi_in_dropped);
    j.num("midi_mapped", s.midi_mapped);
    j.num("midi_learned", s.midi_learned);
    j.num("snapshots", s.snapshots);
    j.num("renders", (double)s.renders);
    j.num("render_frames", (double)s.render_frames);
    j.num("render_avg_us", s.render_avg_us);
    j.num("render_max_us", s.render_max_us);
    j.num("rate", s.rate);
    j.num("latency_frames", s.latency_frames);
    j.num("ring_target", s.ring_target);
    j.num("ring_fill_min", s.ring_fill_min);
    j.num("underruns", s.underruns);
    j.num("underrun_frames", s.underrun_frames);
    j.num("core_wakes", (double)s.core_wakes);
    j.num("core_frames", (double)s.core_frames);
    j.num("core_avg_us", s.core_avg_us);
    j.num("core_max_us", s.core_max_us);
    j.num("core_load_pct", s.core_load_pct);
    j.num("frame_max_us", s.frame_max_us);
    j.num("realtime", s.realtime);
    j.num("cpu_pct", s.cpu_pct);
    j.num("flash_size", s.flash_size);
    j.num("flash_dirty", s.flash_dirty);
    j.num("flash_copies", s.flash_copies);
    Json a;
    a.num("open", o.open);
    a.num("running", o.running);
    a.num("dead", o.dead);
    a.num("device_id", o.device_id);
    a.num("requested_device", o.requested_device);
    a.num("usb_safe", o.usb_safe);
    a.num("mmap", o.mmap);
    a.num("latency_ms", o.latency_ms);
    a.num("rate", o.rate);
    a.num("channels", o.channels);
    a.num("frames_per_burst", o.frames_per_burst);
    a.num("buffer_size", o.buffer_size);
    a.num("buffer_capacity", o.buffer_capacity);
    a.str("api", o.api == 2 ? "AAudio" : o.api == 1 ? "OpenSLES" : "none");
    a.str("sharing", o.open ? (o.sharing == 0 ? "exclusive" : "shared") : "");
    a.num("performance", o.performance);
    a.num("xruns", o.xruns);
    a.num("callbacks", o.callbacks);
    a.num("max_frames", o.max_frames);
    a.num("frames", (double)o.frames);
    a.num("disconnects", o.disconnects);
    a.num("rms", o.rms);
    a.num("peak", o.peak);
    a.num("last_error", o.last_error);
    a.str("error", o.error);
    a.num("streams", o.streams);
    j.raw("audio", a.done());
    return utf(env, j.done());
}

JNIEXPORT void JNICALL Java_com_quixotic7_remotefm1_RfmEngine_nativeResetStats(JNIEnv *, jclass, jlong p)
{
    rfme_reset_render_stats(host(p)->e);
}

// tests: `frames` frames at `rate` through the render-ahead ring as the audio callback would take them, `buffer`
// frames at a time, the render waiting for the core thread (rfme_set_offline) instead of playing silence. Returns
// the samples interleaved L R (null: the Oboe stream is running). The clock is the audio's while it runs, then given
// back to what it was.
JNIEXPORT jfloatArray JNICALL Java_com_quixotic7_remotefm1_RfmEngine_nativeRenderOffline(JNIEnv *env, jclass, jlong p,
                                                                                          jint frames, jdouble rate,
                                                                                          jint buffer)
{
    Lock l(host(p)->ctl);
    Host *h = host(p);
    rfmo_status_t o;
    rfmo_status(h->out, &o);
    if (o.open || frames <= 0 || buffer <= 0)
        return nullptr;
    const int prev = rfme_clock(h->e);
    rfme_set_offline(h->e, 1);
    if (prev != RFME_CLOCK_AUDIO)
        rfme_set_clock(h->e, RFME_CLOCK_AUDIO);
    std::vector<float> L((size_t)buffer), R((size_t)buffer), out((size_t)frames * 2);
    for (int done = 0; done < frames;) {
        const int k = frames - done < buffer ? frames - done : buffer;
        rfme_render(h->e, L.data(), R.data(), (uint32_t)k, rate);
        for (int i = 0; i < k; i++) {
            out[(size_t)(done + i) * 2] = L[(size_t)i];
            out[(size_t)(done + i) * 2 + 1] = R[(size_t)i];
        }
        done += k;
    }
    rfme_set_offline(h->e, 0);
    if (prev != RFME_CLOCK_AUDIO)
        rfme_set_clock(h->e, prev);
    jfloatArray a = env->NewFloatArray((jsize)out.size());
    if (a)
        env->SetFloatArrayRegion(a, 0, (jsize)out.size(), out.data());
    return a;
}

// the LCD as RGBA8888 (240 x 240 x 4) when it changed since `have`; returns its number
JNIEXPORT jint JNICALL Java_com_quixotic7_remotefm1_RfmEngine_nativePreview(JNIEnv *env, jclass, jlong p, jint have,
                                                                             jbyteArray rgba)
{
    std::vector<uint8_t> buf(240 * 240 * 4);
    const uint32_t seq = rfme_preview(host(p)->e, (uint32_t)have, buf.data());
    if (seq && seq != (uint32_t)have && rgba && env->GetArrayLength(rgba) >= (jsize)buf.size())
        env->SetByteArrayRegion(rgba, 0, (jsize)buf.size(), reinterpret_cast<const jbyte *>(buf.data()));
    return (jint)seq;
}

// ---- A2: the flash image, the power cycle, and the caller's clock for tests

JNIEXPORT jint JNICALL Java_com_quixotic7_remotefm1_RfmEngine_nativeFlashSize(JNIEnv *, jclass, jlong p)
{
    return (jint)rfme_flash_size(host(p)->e);
}

// a copy the clock made (every 30 device s when dirty, after a request, a power cycle's) into out; true: there was one
JNIEXPORT jboolean JNICALL Java_com_quixotic7_remotefm1_RfmEngine_nativeTakeFlash(JNIEnv *env, jclass, jlong p, jbyteArray out)
{
    const jsize n = out ? env->GetArrayLength(out) : 0;
    std::vector<uint8_t> buf((size_t)n);
    if (!n || !rfme_take_flash(host(p)->e, buf.data(), (uint32_t)n))
        return JNI_FALSE;
    env->SetByteArrayRegion(out, 0, n, reinterpret_cast<const jbyte *>(buf.data()));
    return JNI_TRUE;
}

JNIEXPORT void JNICALL Java_com_quixotic7_remotefm1_RfmEngine_nativeRequestFlash(JNIEnv *, jclass, jlong p)
{
    rfme_request_flash(host(p)->e);
}

// the clock stopped, flash_sync, the image now into out, the clock given back
JNIEXPORT jboolean JNICALL Java_com_quixotic7_remotefm1_RfmEngine_nativeFinalFlash(JNIEnv *env, jclass, jlong p, jbyteArray out)
{
    Lock l(host(p)->ctl);
    const jsize n = out ? env->GetArrayLength(out) : 0;
    std::vector<uint8_t> buf((size_t)n);
    if (!n || !rfme_final_flash(host(p)->e, buf.data(), (uint32_t)n))
        return JNI_FALSE;
    env->SetByteArrayRegion(out, 0, n, reinterpret_cast<const jbyte *>(buf.data()));
    return JNI_TRUE;
}

// "" ok, else the error
JNIEXPORT jstring JNICALL Java_com_quixotic7_remotefm1_RfmEngine_nativePowerCycle(JNIEnv *env, jclass, jlong p)
{
    Lock l(host(p)->ctl);
    char err[256] = {};
    const int ok = rfme_power_cycle(host(p)->e, err, sizeof err);
    return utf(env, ok ? "" : (err[0] ? err : "power cycle failed"));
}

JNIEXPORT void JNICALL Java_com_quixotic7_remotefm1_RfmEngine_nativeUnload(JNIEnv *, jclass, jlong p)
{
    Lock l(host(p)->ctl);
    rfme_unload(host(p)->e);
}

// RFME_CLOCK_CALLER: `ms` device ms on this thread; out = [frames, rms, peak (int16), hash]
JNIEXPORT void JNICALL Java_com_quixotic7_remotefm1_RfmEngine_nativeRunMs(JNIEnv *env, jclass, jlong p, jint ms,
                                                                           jdoubleArray out)
{
    Lock l(host(p)->ctl);
    rfme_audio_t a{};
    rfme_run_ms(host(p)->e, (uint32_t)ms, &a);
    if (out && env->GetArrayLength(out) >= 4) {
        const jdouble v[4] = {(double)a.frames, a.rms, (double)a.peak, (double)a.hash};
        env->SetDoubleArrayRegion(out, 0, 4, v);
    }
}

// a panel event (RFMC_EV_*: matrix ids)
JNIEXPORT jboolean JNICALL Java_com_quixotic7_remotefm1_RfmEngine_nativeEvent(JNIEnv *, jclass, jlong p, jint type,
                                                                               jint index, jint value)
{
    return rfme_event(host(p)->e, (uint8_t)type, (uint8_t)index, (int16_t)value) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jint JNICALL Java_com_quixotic7_remotefm1_RfmEngine_nativeMatrixButton(JNIEnv *, jclass, jlong p, jint label)
{
    return rfme_matrix_button(host(p)->e, label);
}

JNIEXPORT jint JNICALL Java_com_quixotic7_remotefm1_RfmEngine_nativeMatrixEncoder(JNIEnv *, jclass, jlong p, jint role)
{
    return rfme_matrix_encoder(host(p)->e, role);
}

// a parameter's value (Long.MIN_VALUE: none); anyClock: rfme_param_read (the clock stopped for it), else
// rfme_param_get (CALLER or NONE only)
JNIEXPORT jlong JNICALL Java_com_quixotic7_remotefm1_RfmEngine_nativeParam(JNIEnv *env, jclass, jlong p, jstring name,
                                                                            jboolean anyClock)
{
    Lock l(host(p)->ctl);
    int32_t v = 0;
    const std::string n = jstr(env, name);
    const int ok = anyClock ? rfme_param_read(host(p)->e, n.c_str(), &v) : rfme_param_get(host(p)->e, n.c_str(), &v);
    return ok ? (jlong)v : (jlong)INT64_MIN;
}

JNIEXPORT jint JNICALL Java_com_quixotic7_remotefm1_RfmEngine_nativeLcdHash(JNIEnv *, jclass, jlong p)
{
    return (jint)rfme_lcd_hash(host(p)->e);
}

// the colours on the LCD last published
JNIEXPORT jint JNICALL Java_com_quixotic7_remotefm1_RfmEngine_nativeColours(JNIEnv *, jclass, jlong p)
{
    std::vector<uint8_t> buf(240 * 240 * 4);
    if (!rfme_preview(host(p)->e, 0, buf.data()))
        return 0;
    std::vector<uint32_t> px(240 * 240);
    for (size_t i = 0; i < px.size(); i++)
        px[i] = (uint32_t)buf[i * 4] << 16 | (uint32_t)buf[i * 4 + 1] << 8 | buf[i * 4 + 2];
    std::sort(px.begin(), px.end());
    return (jint)(std::unique(px.begin(), px.end()) - px.begin());
}

JNIEXPORT void JNICALL Java_com_quixotic7_remotefm1_RfmEngine_nativeTestDisconnect(JNIEnv *, jclass, jlong p)
{
    rfmo_test_disconnect(host(p)->out);
}

// ---- A3: the faceplate (ui/faceplate/PanelFeed.kt), polled every frame: no allocations

// the LCD as RGBA8888 straight into a direct buffer (240 x 240 x 4) when it changed since `have`; its number
JNIEXPORT jint JNICALL Java_com_quixotic7_remotefm1_RfmEngine_nativePreviewDirect(JNIEnv *env, jclass, jlong p, jint have,
                                                                                   jobject buf)
{
    void *dst = buf ? env->GetDirectBufferAddress(buf) : nullptr;
    if (!dst || env->GetDirectBufferCapacity(buf) < 240 * 240 * 4)
        return 0;
    return (jint)rfme_preview(host(p)->e, (uint32_t)have, static_cast<uint8_t *>(dst));
}

// rfme_panel into out (at least 56 ints): seq, keys, labels, enc[7], master, booted, play_green, master_pot,
// pickup_seq, key_led[27], btn_led[14]; returns seq
JNIEXPORT jint JNICALL Java_com_quixotic7_remotefm1_RfmEngine_nativePanel(JNIEnv *env, jclass, jlong p, jintArray out)
{
    rfme_panel_t s;
    const uint32_t seq = rfme_panel(host(p)->e, &s);
    if (!out || env->GetArrayLength(out) < 56)
        return (jint)seq;
    jint v[56];
    int n = 0;
    v[n++] = (jint)seq;
    v[n++] = (jint)s.keys;
    v[n++] = (jint)s.labels;
    for (int i = 0; i < 7; i++)
        v[n++] = s.enc[i];
    v[n++] = s.master;
    v[n++] = s.booted;
    v[n++] = s.play_green;
    v[n++] = s.master_pot;
    v[n++] = s.pickup_seq;
    for (int i = 0; i < 27; i++)
        v[n++] = s.key_led[i];
    for (int i = 0; i < 14; i++)
        v[n++] = s.btn_led[i];
    env->SetIntArrayRegion(out, 0, n, v);
    return (jint)seq;
}

JNIEXPORT jint JNICALL Java_com_quixotic7_remotefm1_RfmEngine_nativeButtonNamesGen(JNIEnv *, jclass, jlong p)
{
    return (jint)rfme_button_names_gen(host(p)->e);
}

// the firmware's button names: "<gen>\n<loaded>\n<use>\n" then the 14 names, one per line (newlines in a name become
// spaces)
JNIEXPORT jstring JNICALL Java_com_quixotic7_remotefm1_RfmEngine_nativeButtonNames(JNIEnv *env, jclass, jlong p)
{
    rfme_button_names_t b;
    const uint32_t g = rfme_button_names(host(p)->e, &b);
    std::string s = std::to_string(g) + "\n" + std::to_string(b.loaded) + "\n" + std::to_string(b.use) + "\n";
    for (int i = 0; i < 14; i++) {
        std::string n(b.names[i], strnlen(b.names[i], sizeof b.names[i]));
        std::replace(n.begin(), n.end(), '\n', ' ');
        s += n + "\n";
    }
    return env->NewStringUTF(s.c_str());
}

JNIEXPORT void JNICALL Java_com_quixotic7_remotefm1_RfmEngine_nativeSetButtonNames(JNIEnv *, jclass, jlong p, jboolean use)
{
    rfme_set_button_names(host(p)->e, use ? 1 : 0);
}

// ---- A6: MIDI from controllers, the learned map, learn, a web manager's SysEx and the core's MIDI out

// one channel message (1..3 bytes) as host MIDI (to_firmware 0: learn, the map, the keys, the rest to midi_in) or
// straight to the firmware's midi_in (1); any thread but the audio callback. 1: queued
JNIEXPORT jint JNICALL Java_com_quixotic7_remotefm1_RfmEngine_nativeMidiIn(JNIEnv *env, jclass, jlong p, jbyteArray data,
                                                                           jint off, jint len, jboolean toFirmware)
{
    if (!data || len <= 0 || len > 3 || off < 0 || off + len > env->GetArrayLength(data))
        return 0;
    jbyte b[3];
    env->GetByteArrayRegion(data, off, len, b);
    return rfme_midi_in(host(p)->e, reinterpret_cast<const uint8_t *>(b), (uint32_t)len, toFirmware ? 1 : 0);
}

// one whole SysEx (F0 .. F7) to the core's midi_in; 1 queued, 0 not one SysEx or no room now
JNIEXPORT jint JNICALL Java_com_quixotic7_remotefm1_RfmEngine_nativeSysexIn(JNIEnv *env, jclass, jlong p, jbyteArray data)
{
    if (!data)
        return 0;
    std::vector<uint8_t> v((size_t)env->GetArrayLength(data));
    env->GetByteArrayRegion(data, 0, (jsize)v.size(), reinterpret_cast<jbyte *>(v.data()));
    return rfme_sysex_in(host(p)->e, v.data(), (uint32_t)v.size());
}

// the learned map: 5 ints per entry (src, channel, number, target, mode), n entries, the knob speed
JNIEXPORT void JNICALL Java_com_quixotic7_remotefm1_RfmEngine_nativeSetMidiMap(JNIEnv *env, jclass, jlong p,
                                                                               jintArray entries, jint n, jfloat speed)
{
    std::vector<rfme_map_entry_t> m;
    if (entries && n > 0) {
        const jsize len = env->GetArrayLength(entries);
        std::vector<jint> v((size_t)len);
        env->GetIntArrayRegion(entries, 0, len, v.data());
        for (jint i = 0; i < n && 5 * i + 4 < len; i++)
            m.push_back(rfme_map_entry_t{(uint8_t)v[5 * i], (uint8_t)v[5 * i + 1], (uint8_t)v[5 * i + 2],
                                         (uint8_t)v[5 * i + 3], (uint8_t)v[5 * i + 4]});
    }
    rfme_set_midi_map(host(p)->e, m.empty() ? nullptr : m.data(), (uint32_t)m.size(), speed);
}

JNIEXPORT void JNICALL Java_com_quixotic7_remotefm1_RfmEngine_nativeLearn(JNIEnv *, jclass, jlong p, jboolean armed)
{
    rfme_learn(host(p)->e, armed ? 1 : 0);
}

// the next message in the learn queue into msg (3 bytes); false: none
JNIEXPORT jboolean JNICALL Java_com_quixotic7_remotefm1_RfmEngine_nativeLearnTake(JNIEnv *env, jclass, jlong p, jbyteArray msg)
{
    uint8_t m[3];
    if (!rfme_learn_take(host(p)->e, m))
        return JNI_FALSE;
    if (msg && env->GetArrayLength(msg) >= 3)
        env->SetByteArrayRegion(msg, 0, 3, reinterpret_cast<const jbyte *>(m));
    return JNI_TRUE;
}

// the core's MIDI out packets (USB-MIDI) into out, at most its length; how many
JNIEXPORT jint JNICALL Java_com_quixotic7_remotefm1_RfmEngine_nativeMidiOutTake(JNIEnv *env, jclass, jlong p, jintArray out)
{
    if (!out)
        return 0;
    const jsize cap = env->GetArrayLength(out);
    std::vector<jint> v;
    uint32_t pkt = 0;
    while ((jsize)v.size() < cap && rfme_midi_out_take(host(p)->e, &pkt))
        v.push_back((jint)pkt);
    if (!v.empty())
        env->SetIntArrayRegion(out, 0, (jsize)v.size(), v.data());
    return (jint)v.size();
}

} // extern "C"

