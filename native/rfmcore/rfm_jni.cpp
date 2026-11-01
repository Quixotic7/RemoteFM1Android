// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 RemoteFM1 contributors
// The JNI face of the engine for Kotlin (app/.../RfmEngine.kt): the core loader (rfms_*), one engine (rfme_*) with
// its Oboe output (rfmo_*), on an FM-1 link (RfmLink, rfm_link_jni.cpp) or headless, and offline renders for tests.
// Status comes back as JSON text.
#include <jni.h>

#include <cmath>
#include <cstdio>
#include <cstring>
#include <string>
#include <vector>

#include "RFMEngine.h"
#include "RFMCore.h"
#include "RFMOboe.h"
#include "RFMRealtime.h"

namespace {

struct Host {
    rfme_t *e = nullptr;
    rfmo_t *out = nullptr;
};

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
    rfmo_free(h->out);
    rfme_free(h->e);
    delete h;
}

// "" ok, else the error
JNIEXPORT jstring JNICALL Java_com_quixotic7_remotefm1_RfmEngine_nativeLoad(JNIEnv *env, jclass, jlong p, jstring id)
{
    char err[512] = {};
    const int ok = rfme_load(host(p)->e, jstr(env, id).c_str(), err, sizeof err);
    return utf(env, ok ? "" : (err[0] ? err : "load failed"));
}

JNIEXPORT jboolean JNICALL Java_com_quixotic7_remotefm1_RfmEngine_nativeBoot(JNIEnv *env, jclass, jlong p, jbyteArray flash)
{
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
    rfme_set_clock(host(p)->e, owner);
}

// a note key (0..26) down or up, as the FM-1 sends it
JNIEXPORT jboolean JNICALL Java_com_quixotic7_remotefm1_RfmEngine_nativeKey(JNIEnv *, jclass, jlong p, jint key, jboolean down,
                                                                             jint vel)
{
    return rfme_event_vel(host(p)->e, RFMC_EV_KEY, (uint8_t)key, down ? 1 : 0, (uint8_t)vel) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jstring JNICALL Java_com_quixotic7_remotefm1_RfmEngine_nativeStartAudio(JNIEnv *env, jclass, jlong p, jint device)
{
    char err[160] = {};
    const int ok = rfmo_start(host(p)->out, device, err, sizeof err);
    return utf(env, ok ? "" : (err[0] ? err : "start failed"));
}

JNIEXPORT void JNICALL Java_com_quixotic7_remotefm1_RfmEngine_nativeStopAudio(JNIEnv *, jclass, jlong p, jint clockAfter)
{
    rfmo_stop(host(p)->out, clockAfter);
}

JNIEXPORT jstring JNICALL Java_com_quixotic7_remotefm1_RfmEngine_nativeStatus(JNIEnv *env, jclass, jlong p)
{
    Host *h = host(p);
    rfme_status_t s;
    rfme_status(h->e, &s);
    rfmo_status_t o;
    rfmo_status(h->out, &o);
    Json j;
    j.num("loaded", s.loaded);
    j.num("booted", s.booted);
    j.num("halted", s.halted);
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
    Json a;
    a.num("open", o.open);
    a.num("running", o.running);
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

} // extern "C"
