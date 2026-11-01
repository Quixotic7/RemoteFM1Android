// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 RemoteFM1 contributors
// libremotefm1's Android back end (in place of RemoteFM1's host/include/rfm/macos.h, rfm_mac_*): SysEx links over the
// NDK's AMidi. Kotlin finds the MIDI devices (MidiManager: USB, Bluetooth LE, and in tests one virtual device), applies
// the FM-1 candidate rule, opens the candidates and hands each MidiDevice down (rfma_add_device). A RemoteLink run
// (rfm_android_t) opens an input and an output port on every device in that list, adds each pair to its session as an
// RFM_LINK_SX link, and the session finds the unit by HELLO. Nothing else on Android ever gets a byte from us.
//
// THREADS: rfma_* from Kotlin's threads (JNI); rfm_android_* from the session thread only. AMidiOutputPort_receive
// is non-blocking and has no file descriptor to wait on, so pump() reads every port on the session thread and
// wait() sleeps up to a millisecond (no reader thread: it could only poll too). Received bytes go to the session as
// they come; codec.c's stream parser puts split SysEx back together.
//
// SAFETY: a virtual device is a candidate only when its name is exactly cfg.virtual_name (the tests' loopback); USB
// and Bluetooth devices only as Kotlin's rule says (Fm1Candidate.kt: 1209:0001 on USB, an "FM-1" name otherwise).
#pragma once
#include <jni.h>
#include <stddef.h>
#include <stdint.h>

#include "rfm/session.h"

#ifdef __cplusplus
extern "C" {
#endif

// MidiDeviceInfo.TYPE_* (= AMIDI_DEVICE_TYPE_*)
enum { RFMA_USB = 1, RFMA_VIRTUAL = 2, RFMA_BLUETOOTH = 3 };

// the JVM (JNI_OnLoad): a session thread is attached to it while its run lasts, since AMidi's port open and close
// are binder calls that run Java code on the calling thread when the device's server is in this process (the debug
// build's virtual FM-1)
void rfma_set_vm(JavaVM *vm);

// ---- the process's device list (Kotlin, via JNI)
// a candidate MidiDevice (an open android.media.midi.MidiDevice), the input port we send to and the output port we
// read from; returns its id (> 0), or -1 (AMidiDevice_fromJava failed, a virtual device of another name, no ports)
int rfma_add_device(JNIEnv *env, jobject midi_device, const char *name, int transport, int in_port, int out_port,
                    const char *virtual_name);
// the device went away (or the app closes it): links on it are dropped by their session threads (waited for, at
// most ~1.5 s), then the AMidiDevice is released; Kotlin closes the MidiDevice after this returns
void rfma_remove_device(int id);
// the list as JSON for Diagnostics: [{"id":1,"name":"FM-1","transport":1,"in":0,"out":0,"linked":1}, ...]
uint32_t rfma_devices_json(char *buf, uint32_t n);
// tests: the next n session writes (on any link) fail, as a MIDI service that stopped taking bytes would
void rfma_test_fail_writes(int n);
// changes so far (an add or a remove): a session thread rescans when this moves
uint32_t rfma_generation(void);

// ---- one RemoteLink run (the session thread)
typedef struct {
    const char *virtual_name;                           // tests: also virtual devices with exactly this name
    void (*log)(void *ctx, const char *msg);
    void *log_ctx;
} rfm_android_cfg_t;

typedef struct {
    uint32_t midi_links;                                // port pairs open now
    uint32_t rx_bytes, rx_msgs, rx_max;                 // bytes in, AMidi messages (chunks) in, the longest chunk
    uint32_t tx_bytes, tx_msgs, tx_max, tx_short;       // bytes out, session writes, the longest, writes cut short
    uint32_t rx_errors;                                 // receive errors (the link dropped)
    uint32_t write_fails;                               // session writes that failed (the link dropped, reopened)
    uint32_t silent_reopens;                            // links dropped and reopened: nothing came for a while
    uint32_t link_drops, port_opens;                    // links let go (any reason), port pairs opened
} rfm_android_stats_t;

typedef struct rfm_android rfm_android_t;
rfm_android_t *rfm_android_new(const rfm_android_cfg_t *cfg, char *err, size_t errn);
void rfm_android_attach(rfm_android_t *m, rfm_session_t *s);   // the session the links go to; scans once
void rfm_android_rescan(rfm_android_t *m);                      // the session's rescan callback; device list changes
void rfm_android_pump(rfm_android_t *m, uint32_t now_ms);       // every port read into the session
void rfm_android_wait(rfm_android_t *m, uint32_t timeout_ms);   // sleeps up to min(timeout, 1 ms)
void rfm_android_stats(const rfm_android_t *m, rfm_android_stats_t *st);
// the next bytes received are logged (RFMMidi), with `why` ("after the session started")
void rfm_android_log_next_rx(rfm_android_t *m, const char *why);
// the name of the session link `sid` ("midi \"FM-1\" (USB)"), "" none
void rfm_android_link_name(const rfm_android_t *m, int sid, char *buf, size_t n);
void rfm_android_free(rfm_android_t *m);                        // closes every port (call after the session stopped)

#ifdef __cplusplus
}
#endif
