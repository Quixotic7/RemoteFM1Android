// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 RemoteFM1 contributors
// The engine's audio output on Android (iOS: RFMEngineNode.mm's AVAudioSourceNode): an Oboe output stream whose
// data callback only copies from the engine's render-ahead ring (rfme_render) and wakes its core thread, as the iOS
// render block does. Low latency, exclusive when the device gives it, float stereo at the device's own rate (the
// engine's Device resamples 44.1 kHz to it). The control calls are for one thread (the app's main thread).
#pragma once
#include <stdint.h>

#include "RFMEngine.h"

#ifdef __cplusplus
extern "C" {
#endif

typedef struct rfmo rfmo_t;
rfmo_t *rfmo_new(rfme_t *e);        // e must outlive it
void rfmo_free(rfmo_t *o);          // stops first

// opens and starts the stream on device_id (0: the default output) and gives the engine's clock to it
// (RFME_CLOCK_AUDIO). 1: playing; 0: failed (err)
int rfmo_start(rfmo_t *o, int32_t device_id, char *err, uint32_t n);
// stops and closes the stream; the engine's clock goes to `clock_after` (RFME_CLOCK_TIMER keeps the core running)
void rfmo_stop(rfmo_t *o, int clock_after);

typedef struct {
    int open, running;
    int32_t rate, channels, frames_per_burst, buffer_size, buffer_capacity;
    int32_t api;                    // oboe::AudioApi: 1 OpenSL ES, 2 AAudio
    int32_t sharing;                // oboe::SharingMode: 0 exclusive, 1 shared
    int32_t performance;            // oboe::PerformanceMode: 10 none, 11 power saving, 12 low latency
    int32_t xruns;                  // the stream's own count (-1: unknown)
    int32_t last_error;             // oboe::Result of the last failure, 0 none
    uint32_t callbacks, max_frames, disconnects;
    uint64_t frames;
    float rms, peak;                // the output's over the last ~100 ms, full scale 1.0
    char error[96];
} rfmo_status_t;
void rfmo_status(rfmo_t *o, rfmo_status_t *st);

#ifdef __cplusplus
}
#endif
