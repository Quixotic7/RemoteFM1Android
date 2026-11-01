// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 RemoteFM1 contributors
// The JNI face of the FM-1 link for Kotlin: RFMCore's rfmc_* (app/.../RfmLink.kt), the Android MIDI back end's device
// list (RfmMidi.kt: the MidiDevices Kotlin opened), and a standalone fake unit (FakeUnit.kt: the debug build's virtual
// MIDI device "RemoteFM1 Fake FM-1", the tests' loopback through Android's own MIDI service). Status comes back as
// JSON text.
#include <jni.h>

#include <chrono>
#include <cmath>
#include <cstdio>
#include <cstring>
#include <mutex>
#include <string>
#include <vector>

#include "RFMCore.h"
#include "android/rfm_android.h"
#include "rfm/fake.h"

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
    std::string r;
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
    std::string done() { return s + "}"; }
};

rfmc_t *link(jlong h) { return reinterpret_cast<rfmc_t *>(h); }

std::vector<uint8_t> bytes(JNIEnv *env, jbyteArray a)
{
    std::vector<uint8_t> v;
    if (a) {
        v.resize((size_t)env->GetArrayLength(a));
        env->GetByteArrayRegion(a, 0, (jsize)v.size(), reinterpret_cast<jbyte *>(v.data()));
    }
    return v;
}

// ---- a standalone fake unit (the virtual MIDI device's): its own lock and clock
struct Fake {
    std::mutex mu;
    rfm_fake_t *f = nullptr;
    std::chrono::steady_clock::time_point t0 = std::chrono::steady_clock::now();
    std::vector<uint8_t> out = std::vector<uint8_t>(1u << 16);
    uint32_t rxChunks = 0, rxBytes = 0, rxMax = 0, txChunks = 0, txBytes = 0;
    uint32_t now() const
    {
        return (uint32_t)std::chrono::duration_cast<std::chrono::milliseconds>(std::chrono::steady_clock::now() - t0)
            .count();
    }
};
Fake *fake(jlong h) { return reinterpret_cast<Fake *>(h); }

} // namespace

extern "C" {

JNIEXPORT jint JNI_OnLoad(JavaVM *vm, void *)
{
    rfma_set_vm(vm);
    return JNI_VERSION_1_6;
}

// ------------------------------------------------------------------ RfmLink ---
JNIEXPORT jlong JNICALL Java_com_quixotic7_remotefm1_RfmLink_nativeNew(JNIEnv *env, jclass, jint backend, jboolean fakeBle,
                                                                        jboolean fakeNoTouch, jint fps, jstring hostName,
                                                                        jstring virtualName)
{
    const std::string hn = jstr(env, hostName), vn = jstr(env, virtualName);
    rfmc_cfg_t cfg{};
    cfg.backend = backend == RFMC_FAKE ? RFMC_FAKE : RFMC_DEVICE;
    cfg.fake_ble = fakeBle ? 1 : 0;
    cfg.fake_no_touch = fakeNoTouch ? 1 : 0;
    cfg.fps = (uint32_t)fps;
    cfg.host_name = hn.c_str();
    cfg.virtual_name = vn.c_str();
    return reinterpret_cast<jlong>(rfmc_new(&cfg));      // (copies both strings)
}

JNIEXPORT void JNICALL Java_com_quixotic7_remotefm1_RfmLink_nativeFree(JNIEnv *, jclass, jlong h) { rfmc_free(link(h)); }
JNIEXPORT void JNICALL Java_com_quixotic7_remotefm1_RfmLink_nativeStart(JNIEnv *, jclass, jlong h) { rfmc_start(link(h)); }
JNIEXPORT void JNICALL Java_com_quixotic7_remotefm1_RfmLink_nativeStop(JNIEnv *, jclass, jlong h) { rfmc_stop(link(h)); }
JNIEXPORT void JNICALL Java_com_quixotic7_remotefm1_RfmLink_nativeRescan(JNIEnv *, jclass, jlong h) { rfmc_rescan(link(h)); }

JNIEXPORT void JNICALL Java_com_quixotic7_remotefm1_RfmLink_nativeSetPattern(JNIEnv *, jclass, jlong h, jboolean on)
{
    rfmc_set_pattern(link(h), on ? 1 : 0);
}

JNIEXPORT void JNICALL Java_com_quixotic7_remotefm1_RfmLink_nativeSetKeyTouch(JNIEnv *, jclass, jlong h, jint flags)
{
    rfmc_set_key_touch(link(h), (uint32_t)flags);
}

JNIEXPORT jstring JNICALL Java_com_quixotic7_remotefm1_RfmLink_nativeStatus(JNIEnv *env, jclass, jlong h)
{
    rfmc_status_t st;
    rfmc_status(link(h), &st);
    Json j;
    j.num("state", st.state);
    j.num("session_state", st.session_state);
    j.num("wanted", st.wanted);
    j.num("ble", st.ble);
    j.num("fps", st.fps);
    j.num("runs", st.runs);
    j.num("sessions", st.sessions);
    j.num("frames", st.frames);
    j.num("acks", st.acks);
    j.num("pending", st.pending);
    j.num("in_flight", st.in_flight);
    j.num("events_in", st.events_in);
    j.num("events_dropped", st.events_dropped);
    j.num("snapshots", st.snapshots);
    j.num("colors", st.colors);
    j.num("reduced", st.reduced);
    j.num("refined", st.refined);
    j.num("held", st.held);
    j.num("hold", st.hold);
    j.num("budget", st.budget);
    j.num("wire_bytes", st.wire_bytes);
    j.num("body_bytes", st.body_bytes);
    j.num("wire_cap", st.wire_cap);
    j.num("frame_ms", st.frame_ms);
    j.num("ack_ms_last", st.ack_ms_last);
    j.num("ack_ms_avg", st.ack_ms_avg);
    j.num("ack_ms_max", st.ack_ms_max);
    j.num("ack_timeouts", st.ack_timeouts);
    j.num("discoveries", st.discoveries);
    j.str("text", st.text);
    j.str("log", st.log);
    j.num("have_hello", st.have_hello);
    j.num("hello_ble", st.hello_ble);
    j.str("name", st.name);
    j.str("ver", st.ver);
    j.str("fw_ver", st.fw_ver);
    j.str("identity", st.identity);
    j.num("proto", st.proto);
    j.num("transports", st.transports);
    j.num("usb_mode", st.usb_mode);
    j.num("session", st.session);
    j.num("max_in_flight", st.max_in_flight);
    j.num("caps", st.caps);
    j.num("sxq", st.sxq);
    j.num("sx_body_max", st.sx_body_max);
    j.num("bk_body_max", st.bk_body_max);
    j.num("screen_w", st.screen_w);
    j.num("screen_h", st.screen_h);
    j.num("trs_midi_in", st.trs_midi_in);
    j.num("trs_midi_dropped", st.trs_midi_dropped);
    j.num("touch_wanted", st.touch_wanted);
    j.num("touch", st.touch);
    j.num("touch_sent", st.touch_sent);
    j.num("midi_links", st.midi_links);
    j.num("rx_bytes", st.rx_bytes);
    j.num("tx_bytes", st.tx_bytes);
    j.num("tx_short", st.tx_short);
    j.str("frame_link", st.frame_link);
    return env->NewStringUTF(j.done().c_str());
}

// the panel's events after `after`: [[seq, type, index, source, value, vel], ...]
JNIEXPORT jstring JNICALL Java_com_quixotic7_remotefm1_RfmLink_nativeEvents(JNIEnv *env, jclass, jlong h, jint after)
{
    rfmc_event_t ev[64];
    const uint32_t n = rfmc_events(link(h), (uint32_t)after, ev, 64);
    std::string s = "[";
    for (uint32_t i = 0; i < n; i++) {
        char b[96];
        std::snprintf(b, sizeof b, "%s[%u,%u,%u,%u,%d,%u]", i ? "," : "", ev[i].seq, ev[i].type, ev[i].index,
                      ev[i].source, ev[i].value, ev[i].vel);
        s += b;
    }
    return env->NewStringUTF((s + "]").c_str());
}

// [keys, buttons, master]
JNIEXPORT jlongArray JNICALL Java_com_quixotic7_remotefm1_RfmLink_nativePanel(JNIEnv *env, jclass, jlong h)
{
    uint32_t keys = 0, buttons = 0;
    int32_t master = -1;
    rfmc_panel(link(h), &keys, &buttons, &master);
    const jlong v[3] = {(jlong)keys, (jlong)buttons, (jlong)master};
    jlongArray a = env->NewLongArray(3);
    if (a)
        env->SetLongArrayRegion(a, 0, 3, v);
    return a;
}

JNIEXPORT jint JNICALL Java_com_quixotic7_remotefm1_RfmLink_nativePreview(JNIEnv *env, jclass, jlong h, jint have,
                                                                           jbyteArray rgba)
{
    std::vector<uint8_t> buf(RFMC_W * RFMC_H * 4);
    const uint32_t seq = rfmc_preview(link(h), (uint32_t)have, buf.data());
    if (seq != (uint32_t)have && rgba && env->GetArrayLength(rgba) >= (jsize)buf.size())
        env->SetByteArrayRegion(rgba, 0, (jsize)buf.size(), reinterpret_cast<const jbyte *>(buf.data()));
    return (jint)seq;
}

JNIEXPORT void JNICALL Java_com_quixotic7_remotefm1_RfmLink_nativeFakeKey(JNIEnv *, jclass, jlong h, jint k, jboolean down,
                                                                           jint vel)
{
    if (vel > 0)
        rfmc_fake_key_vel(link(h), (uint32_t)k, down ? 1 : 0, (uint32_t)vel);
    else
        rfmc_fake_key(link(h), (uint32_t)k, down ? 1 : 0);
}

JNIEXPORT void JNICALL Java_com_quixotic7_remotefm1_RfmLink_nativeFakePress(JNIEnv *, jclass, jlong h, jint k, jint p)
{
    rfmc_fake_press(link(h), (uint32_t)k, (uint32_t)p);
}

JNIEXPORT void JNICALL Java_com_quixotic7_remotefm1_RfmLink_nativeFakeButton(JNIEnv *, jclass, jlong h, jint b, jboolean down)
{
    rfmc_fake_button(link(h), (uint32_t)b, down ? 1 : 0);
}

JNIEXPORT void JNICALL Java_com_quixotic7_remotefm1_RfmLink_nativeFakeEnc(JNIEnv *, jclass, jlong h, jint e, jint d)
{
    rfmc_fake_enc(link(h), (uint32_t)e, d);
}

JNIEXPORT void JNICALL Java_com_quixotic7_remotefm1_RfmLink_nativeFakeMaster(JNIEnv *, jclass, jlong h, jint adc)
{
    rfmc_fake_master(link(h), (uint32_t)adc);
}

JNIEXPORT void JNICALL Java_com_quixotic7_remotefm1_RfmLink_nativeFakeTrs(JNIEnv *env, jclass, jlong h, jbyteArray msgs)
{
    const std::vector<uint8_t> v = bytes(env, msgs);
    rfmc_fake_trs(link(h), v.data(), (uint32_t)v.size());
}

// the fake unit now: {"fake":1,"session":1,"frames":..,"lit_keys":bits,"lit_buttons":bits,...}; {"fake":0} otherwise
JNIEXPORT jstring JNICALL Java_com_quixotic7_remotefm1_RfmLink_nativeFakeState(JNIEnv *env, jclass, jlong h)
{
    rfmc_fake_t f;
    Json j;
    const int ok = rfmc_fake_state(link(h), &f);
    j.num("fake", ok);
    if (ok) {
        uint32_t keys = 0, buttons = 0;
        for (int k = 0; k < 27; k++)
            if (rfmc_led_is(f.lit, 14 + k))
                keys |= 1u << k;
        for (int b = 0; b < 14; b++)
            if (rfmc_led_is(f.lit, b))
                buttons |= 1u << b;
        j.num("session", f.session);
        j.num("frames", f.frames);
        j.num("acks", f.acks);
        j.num("held_frames", f.held_frames);
        j.num("hellos", f.hellos);
        j.num("touch", f.touch);
        j.num("touch_msgs", f.touch_msgs);
        j.num("lit_keys", keys);
        j.num("lit_buttons", buttons);
    }
    return env->NewStringUTF(j.done().c_str());
}

// ------------------------------------------------------------------ RfmMidi (the device list) ---
JNIEXPORT jint JNICALL Java_com_quixotic7_remotefm1_RfmMidi_nativeAddDevice(JNIEnv *env, jclass, jobject device, jstring name,
                                                                             jint transport, jint inPort, jint outPort,
                                                                             jstring virtualName)
{
    const std::string n = jstr(env, name), vn = jstr(env, virtualName);
    return rfma_add_device(env, device, n.c_str(), transport, inPort, outPort, vn.c_str());
}

JNIEXPORT void JNICALL Java_com_quixotic7_remotefm1_RfmMidi_nativeRemoveDevice(JNIEnv *, jclass, jint id)
{
    rfma_remove_device(id);
}

JNIEXPORT jstring JNICALL Java_com_quixotic7_remotefm1_RfmMidi_nativeDevices(JNIEnv *env, jclass)
{
    const uint32_t n = rfma_devices_json(nullptr, 0);
    std::vector<char> b(n + 1);
    rfma_devices_json(b.data(), n + 1);
    return env->NewStringUTF(b.data());
}

// ------------------------------------------------------------------ FakeUnit (the virtual device's) ---
JNIEXPORT jlong JNICALL Java_com_quixotic7_remotefm1_FakeUnit_nativeNew(JNIEnv *, jclass, jint sxRateBps)
{
    auto *x = new Fake;
    rfm_fake_cfg_t fc;
    rfm_fake_cfg_default(&fc);
    fc.has_bulk = 0;
    fc.sx_rate_bps = (uint32_t)sxRateBps;            // (0: as fast as Android's MIDI service carries it)
    x->f = rfm_fake_new(&fc);
    return reinterpret_cast<jlong>(x);
}

JNIEXPORT void JNICALL Java_com_quixotic7_remotefm1_FakeUnit_nativeFree(JNIEnv *, jclass, jlong h)
{
    Fake *x = fake(h);
    if (!x)
        return;
    rfm_fake_free(x->f);
    delete x;
}

// bytes the host sent (one MidiReceiver.onSend chunk)
JNIEXPORT void JNICALL Java_com_quixotic7_remotefm1_FakeUnit_nativeRx(JNIEnv *env, jclass, jlong h, jbyteArray data,
                                                                       jint off, jint len)
{
    Fake *x = fake(h);
    std::vector<uint8_t> v((size_t)len);
    env->GetByteArrayRegion(data, off, len, reinterpret_cast<jbyte *>(v.data()));
    std::lock_guard<std::mutex> g(x->mu);
    x->rxChunks++;
    x->rxBytes += (uint32_t)len;
    if ((uint32_t)len > x->rxMax)
        x->rxMax = (uint32_t)len;
    rfm_fake_rx_sx(x->f, v.data(), (uint32_t)len, x->now());
}

// its timers run, then what it sends the host (null: nothing)
JNIEXPORT jbyteArray JNICALL Java_com_quixotic7_remotefm1_FakeUnit_nativePoll(JNIEnv *env, jclass, jlong h)
{
    Fake *x = fake(h);
    uint32_t n;
    {
        std::lock_guard<std::mutex> g(x->mu);
        rfm_fake_poll(x->f, x->now());
        n = rfm_fake_take_sx(x->f, x->out.data(), (uint32_t)x->out.size());
        if (n) {
            x->txChunks++;
            x->txBytes += n;
        }
    }
    if (!n)
        return nullptr;
    jbyteArray a = env->NewByteArray((jsize)n);
    if (a)
        env->SetByteArrayRegion(a, 0, (jsize)n, reinterpret_cast<const jbyte *>(x->out.data()));
    return a;
}

JNIEXPORT void JNICALL Java_com_quixotic7_remotefm1_FakeUnit_nativeKey(JNIEnv *, jclass, jlong h, jint k, jboolean down)
{
    Fake *x = fake(h);
    std::lock_guard<std::mutex> g(x->mu);
    rfm_fake_key(x->f, (uint32_t)k, down ? 1 : 0);
}

JNIEXPORT void JNICALL Java_com_quixotic7_remotefm1_FakeUnit_nativeTrs(JNIEnv *env, jclass, jlong h, jbyteArray msgs)
{
    Fake *x = fake(h);
    const std::vector<uint8_t> v = bytes(env, msgs);
    std::lock_guard<std::mutex> g(x->mu);
    rfm_fake_trs(x->f, v.data(), (uint32_t)v.size());
}

JNIEXPORT jstring JNICALL Java_com_quixotic7_remotefm1_FakeUnit_nativeState(JNIEnv *env, jclass, jlong h)
{
    Fake *x = fake(h);
    std::lock_guard<std::mutex> g(x->mu);
    rfm_fake_stats_t fs;
    rfm_fake_stats(x->f, &fs);
    uint32_t keys = 0;
    for (int k = 0; k < 27; k++)
        if (rfmc_led_is(rfm_fake_leds(x->f)->lit, 14 + k))
            keys |= 1u << k;
    Json j;
    j.num("session", rfm_fake_session(x->f));
    j.num("hellos", fs.hellos);
    j.num("sessions_started", fs.sessions_started);
    j.num("frames", fs.frames);
    j.num("acks", fs.acks);
    j.num("tile_bytes", fs.tile_bytes);
    j.num("decode_errs", fs.decode_errs);
    j.num("sx_msgs", fs.sx_msgs);
    j.num("lit_keys", keys);
    j.num("rx_chunks", x->rxChunks);
    j.num("rx_bytes", x->rxBytes);
    j.num("rx_max", x->rxMax);
    j.num("tx_chunks", x->txChunks);
    j.num("tx_bytes", x->txBytes);
    return env->NewStringUTF(j.done().c_str());
}

} // extern "C"
