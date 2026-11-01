// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 RemoteFM1 contributors
// The engine's audio output on Android (iOS: RFMEngineNode.mm's AVAudioSourceNode): Oboe output streams whose data
// callback only copies from the engine's render-ahead ring (rfme_render) and wakes its core thread, as the iOS
// render block does. Low latency, exclusive when the device gives it, float stereo at the device's own rate (the
// engine's Device resamples 44.1 kHz to it).
//
// THREADS (after the second FM-1 test, where a stream's close blocked the app's main thread until Android killed
// it): every call that can block in Android's audio server (rfmo_open, rfmo_start, rfmo_close) is made off the main
// thread, by the app's audio-control thread or a worker of its (AudioControl.kt), which may give up waiting for one
// and go on with a new stream. So:
//   - streams are separate objects (rfmo_stream_t); several may exist at once (a new one while an old one's close is
//     still stuck in Android);
//   - only the ACTIVE stream (rfmo_activate) renders the engine: every other stream's callback plays silence and
//     never touches the engine, and two callbacks never run rfme_render at once;
//   - rfmo_close never touches the rfmo_t (only the state the streams share, kept alive by them), so an abandoned
//     close that returns after the engine is gone is safe; it frees the stream when Android's close returns;
//   - rfmo_status (any thread, never blocks) reads what was recorded at open and the callback's atomics; Oboe's own
//     getters only when no call is in progress on that stream.
// None of these touches the engine's clock: the caller does (rfme_set_clock), serialised with the other clock users.
#pragma once
#include <stdint.h>

#include "RFMEngine.h"

#ifdef __cplusplus
extern "C" {
#endif

typedef struct rfmo rfmo_t;
typedef struct rfmo_stream rfmo_stream_t;

rfmo_t *rfmo_new(rfme_t *e);        // e must outlive it
// no stream renders after this returns (a stream still open keeps the shared state alive and plays silence); the
// streams are not closed: their owners close them
void rfmo_free(rfmo_t *o);

// (may block in Android) opens a stream on device_id (0: the default output, which the app never asks for). usb_safe:
// a USB output the user picked: shared, Android's mixer, MMAP off. forbidden: device ids the stream must not play on
// (the USB outputs not picked): if Android routed it to one, it is closed before it starts. The stream or null (err).
rfmo_stream_t *rfmo_open(rfmo_t *o, int32_t device_id, int usb_safe, const int32_t *forbidden, int n_forbidden,
                         char *err, uint32_t n);
// (may block) starts it (its callback plays silence until it is made active). 1 ok; 0 failed (err)
int rfmo_start(rfmo_stream_t *s, char *err, uint32_t n);
// the stream that renders the engine from now on (the one before it plays silence)
void rfmo_activate(rfmo_stream_t *s);
// silent from now on if it is the active stream (kept open, still rfmo_status's; rfmo_activate makes it render again)
void rfmo_deactivate(rfmo_stream_t *s);
// (may block) silenced at once, then stopped, closed and freed: s is gone when it returns (never use it again)
void rfmo_close(rfmo_stream_t *s);

// the owner's view of its stream: frames played, callbacks, closed by a disconnect; refresh: Oboe's getters asked
// again for rfmo_status (buffer size, xruns, latency: these may ask Android, so never on the main thread)
typedef struct {
    uint64_t frames;
    uint32_t callbacks;
    int dead;
    int32_t device_id;              // the device it plays on
    int mmap, usb_safe;
    int32_t api, sharing, performance, rate, frames_per_burst;
} rfmo_stream_info_t;
void rfmo_stream_info(rfmo_stream_t *s, int refresh, rfmo_stream_info_t *st);

// tests: what a device disconnect does (the error callback's count and `dead` flag on the active stream), so the
// app's reopen path can be tried where no device can be pulled (the emulator)
void rfmo_test_disconnect(rfmo_t *o);
// tests: every rfmo_close then blocks close_ms inside the stream's close, every rfmo_open open_ms before it opens
// (Android's audio server stuck, as on the Galaxy Tab A9+ in the second FM-1 test); 0 0 back to normal
void rfmo_test_hang(rfmo_t *o, int close_ms, int open_ms);

typedef struct {
    int open, running;
    int dead;                       // the stream was closed by a disconnect (onErrorAfterClose): reopen it
    int32_t device_id;              // the device the stream plays on (AudioDeviceInfo.getId)
    int32_t requested_device;       // the one asked for (0: the default)
    int usb_safe;                   // opened the careful way (a picked USB output)
    int mmap;                       // 1 MMAP, 0 not, -1 unknown
    double latency_ms;              // Oboe's calculateLatencyMillis (-1: unknown)
    int32_t rate, channels, frames_per_burst, buffer_size, buffer_capacity;
    int32_t api;                    // oboe::AudioApi: 1 OpenSL ES, 2 AAudio
    int32_t sharing;                // oboe::SharingMode: 0 exclusive, 1 shared
    int32_t performance;            // oboe::PerformanceMode: 10 none, 11 power saving, 12 low latency
    int32_t xruns;                  // the stream's own count (-1: unknown)
    int32_t last_error;             // oboe::Result of the last failure, 0 none
    uint32_t callbacks, max_frames, disconnects;
    uint64_t frames;
    float rms, peak;                // the output's over the last ~100 ms, full scale 1.0
    uint32_t streams;               // streams in existence (more than 1: an old one's close is stuck or slow)
    char error[96];
} rfmo_status_t;
// (any thread, never blocks) the active stream's
void rfmo_status(rfmo_t *o, rfmo_status_t *st);

#ifdef __cplusplus
}
#endif
