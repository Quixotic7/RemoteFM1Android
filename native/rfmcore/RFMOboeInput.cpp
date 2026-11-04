// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 RemoteFM1 contributors
// (ain1) The Oboe input (RFMOboeInput.h): its callback splits the device's interleaved float into left and right
// (scratch sized at open: nothing allocated there) and hands them to rfme_ain_push_f32. No locks, no waits.
#include "RFMOboeInput.h"

#include <android/log.h>
#include <oboe/Oboe.h>
#include <oboe/OboeExtensions.h>

#include <atomic>
#include <cmath>
#include <cstdio>
#include <memory>
#include <mutex>
#include <vector>

#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, "RFMAudioIn", __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, "RFMAudioIn", __VA_ARGS__)

namespace {

constexpr uint32_t kScratch = 2048;     // frames handed over at a time (a bigger callback: in pieces)

class Input final : public oboe::AudioStreamDataCallback {
public:
    explicit Input(rfme_t *e) : e_(e), L_(kScratch), R_(kScratch) {}

    oboe::DataCallbackResult onAudioReady(oboe::AudioStream *s, void *data, int32_t numFrames) override
    {
        if (!on_.load(std::memory_order_acquire))
            return oboe::DataCallbackResult::Continue;
        const float *in = static_cast<const float *>(data);
        const int ch = s->getChannelCount();
        const double rate = (double)s->getSampleRate();
        float peak = 0;
        for (int32_t done = 0; done < numFrames;) {
            const uint32_t k = (uint32_t)(numFrames - done) < kScratch ? (uint32_t)(numFrames - done) : kScratch;
            for (uint32_t i = 0; i < k; i++) {
                const float l = in[(done + i) * ch];
                const float r = ch > 1 ? in[(done + i) * ch + 1] : l;
                L_[i] = l;
                R_[i] = r;
                const float a = std::fabs(l) > std::fabs(r) ? std::fabs(l) : std::fabs(r);
                if (a > peak)
                    peak = a;
            }
            rfme_ain_push_f32(e_, L_.data(), ch > 1 ? R_.data() : nullptr, k, rate);
            done += (int32_t)k;
        }
        frames_.fetch_add((uint64_t)numFrames, std::memory_order_relaxed);
        callbacks_.fetch_add(1, std::memory_order_relaxed);
        if (peak > winPeak_)
            winPeak_ = peak;
        winFrames_ += (uint32_t)numFrames;
        if (winFrames_ >= (uint32_t)(rate / 10)) {
            peak_.store(winPeak_, std::memory_order_relaxed);
            winPeak_ = 0;
            winFrames_ = 0;
        }
        return oboe::DataCallbackResult::Continue;
    }

    void stop() { on_.store(false, std::memory_order_release); }

    rfme_t *e_;
    std::vector<float> L_, R_;
    std::atomic<bool> on_{true};
    std::atomic<uint64_t> frames_{0};
    std::atomic<uint32_t> callbacks_{0};
    std::atomic<float> peak_{0};
    float winPeak_ = 0;                 // (the callback's)
    uint32_t winFrames_ = 0;
};

} // namespace

struct rfmi {
    std::shared_ptr<Input> cb;
    std::shared_ptr<oboe::AudioStream> s;
    std::mutex op;
    int32_t device = 0, rate = 0, channels = 0, burst = 0, sharing = 0, performance = 0, mmap = -1;
    std::atomic<int32_t> xruns{-1};
    std::atomic<double> latencyMs{-1};
};

static void putErr(char *err, uint32_t n, const char *s)
{
    if (err && n)
        std::snprintf(err, n, "%s", s);
}

extern "C" {

rfmi_t *rfmi_open(rfme_t *e, int32_t device_id, char *err, uint32_t n)
{
    auto *st = new rfmi;
    st->cb = std::make_shared<Input>(e);
    oboe::AudioStreamBuilder b;
    b.setDirection(oboe::Direction::Input)
        ->setFormat(oboe::AudioFormat::Float)
        ->setChannelCount(oboe::ChannelCount::Stereo)
        ->setPerformanceMode(oboe::PerformanceMode::LowLatency)
        ->setSharingMode(oboe::SharingMode::Shared)
        ->setInputPreset(oboe::InputPreset::Unprocessed)      // (music: no voice processing, no gain control)
        ->setChannelConversionAllowed(true)
        ->setDataCallback(st->cb);
    if (device_id > 0)
        b.setDeviceId(device_id);
    oboe::Result r = b.openStream(st->s);
    if (r != oboe::Result::OK) {                                // (some devices have no unprocessed source)
        LOGW("input open (unprocessed) failed: %s; trying the generic preset", oboe::convertToText(r));
        b.setInputPreset(oboe::InputPreset::Generic);
        r = b.openStream(st->s);
    }
    if (r != oboe::Result::OK) {
        char m[96];
        std::snprintf(m, sizeof m, "input openStream: %s", oboe::convertToText(r));
        putErr(err, n, m);
        LOGW("%s", m);
        delete st;
        return nullptr;
    }
    oboe::AudioStream *s = st->s.get();
    st->device = s->getDeviceId();
    st->rate = s->getSampleRate();
    st->channels = s->getChannelCount();
    st->burst = s->getFramesPerBurst();
    st->sharing = (int32_t)s->getSharingMode();
    st->performance = (int32_t)s->getPerformanceMode();
    st->mmap = oboe::OboeExtensions::isMMapUsed(s) ? 1 : 0;
    r = s->requestStart();
    if (r != oboe::Result::OK) {
        char m[96];
        std::snprintf(m, sizeof m, "input requestStart: %s", oboe::convertToText(r));
        putErr(err, n, m);
        LOGW("%s", m);
        s->close();
        delete st;
        return nullptr;
    }
    LOGI("input open: asked device %d, got %d, %d Hz, %d ch, burst %d, %s, performance %d, MMAP %d", (int)device_id,
         (int)st->device, (int)st->rate, (int)st->channels, (int)st->burst,
         st->sharing == 0 ? "exclusive" : "shared", (int)st->performance, st->mmap);
    return st;
}

void rfmi_close(rfmi_t *st)
{
    if (!st)
        return;
    {
        std::lock_guard<std::mutex> l(st->op);
        st->cb->stop();                                         // (nothing reaches the engine from here)
        if (st->s) {
            st->s->stop();
            st->s->close();                                     // (waits for a callback in flight)
        }
    }
    delete st;
}

void rfmi_info(rfmi_t *st, int refresh, rfmi_info_t *o)
{
    *o = rfmi_info_t{};
    if (!st)
        return;
    if (refresh) {
        std::lock_guard<std::mutex> l(st->op);
        if (st->s) {
            auto lat = st->s->calculateLatencyMillis();
            st->latencyMs.store(lat ? lat.value() : -1.0);
            auto x = st->s->getXRunCount();
            st->xruns.store(x ? x.value() : -1);
        }
    }
    o->device_id = st->device;
    o->rate = st->rate;
    o->channels = st->channels;
    o->frames_per_burst = st->burst;
    o->sharing = st->sharing;
    o->performance = st->performance;
    o->mmap = st->mmap;
    o->latency_ms = st->latencyMs.load();
    o->xruns = st->xruns.load();
    o->frames = st->cb->frames_.load();
    o->callbacks = st->cb->callbacks_.load();
    o->peak = st->cb->peak_.load();
}

} // extern "C"
