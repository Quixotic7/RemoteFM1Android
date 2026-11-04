// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 RemoteFM1 contributors
// (ain1) The engine's audio input on Android (iOS c47ac4f: an AVAudioSinkNode on the input): one Oboe input stream
// (AAudio, low latency, float, the device's own rate and channels) whose data callback hands each buffer to the
// engine's input ring (rfme_ain_push_f32: resampled to 44.1 kHz there). Nothing else: the firmware monitors its input
// itself, the app plays none of it. rfmi_open and rfmi_close may block in Android: never on the main thread. One
// input per engine at a time (the resampler's state is the engine's): close the old one before opening another.
#pragma once
#include <stdint.h>

#include "RFMEngine.h"

#ifdef __cplusplus
extern "C" {
#endif

typedef struct rfmi rfmi_t;

// opens and starts an input on device_id (0: Android's default input, the built-in mic); e must outlive it. The
// RECORD_AUDIO permission must be granted. Null on failure (err)
rfmi_t *rfmi_open(rfme_t *e, int32_t device_id, char *err, uint32_t n);
// stopped, closed and freed (nothing reaches the engine after it returns)
void rfmi_close(rfmi_t *s);

typedef struct {
    int32_t device_id, rate, channels, frames_per_burst, sharing, performance, mmap;
    double latency_ms;          // Oboe's calculateLatencyMillis for the input (-1 unknown)
    uint64_t frames;
    uint32_t callbacks;
    int32_t xruns;
    float peak;                 // the input's over the last ~100 ms
} rfmi_info_t;
// refresh: Oboe's getters asked again (may ask Android: not on the main thread)
void rfmi_info(rfmi_t *s, int refresh, rfmi_info_t *out);

#ifdef __cplusplus
}
#endif
