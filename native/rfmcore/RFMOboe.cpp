// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 RemoteFM1 contributors
// The Oboe output (RFMOboe.h). The callback: rfme_render into two scratch buffers (sized at open, so nothing is
// allocated there), interleaved into Oboe's buffer, the level measured. No locks, no waits.
#include "RFMOboe.h"

#include <oboe/Oboe.h>

#include <atomic>
#include <cmath>
#include <cstdio>
#include <cstring>
#include <memory>
#include <vector>

namespace {

constexpr uint32_t kScratch = 4096;     // frames rendered per rfme_render at most (a bigger callback: in pieces)

class Output final : public oboe::AudioStreamDataCallback, public oboe::AudioStreamErrorCallback {
public:
    explicit Output(rfme_t *e) : e_(e), L_(kScratch), R_(kScratch) {}

    oboe::DataCallbackResult onAudioReady(oboe::AudioStream *s, void *data, int32_t numFrames) override
    {
        float *out = static_cast<float *>(data);
        const int ch = s->getChannelCount();
        const double rate = (double)s->getSampleRate();
        double sum = 0;
        float peak = 0;
        for (int32_t done = 0; done < numFrames;) {
            const uint32_t k = (uint32_t)(numFrames - done) < kScratch ? (uint32_t)(numFrames - done) : kScratch;
            rfme_render(e_, L_.data(), R_.data(), k, rate);
            for (uint32_t i = 0; i < k; i++) {
                const float l = L_[i], r = R_[i];
                if (ch >= 2) {
                    out[0] = l;
                    out[1] = r;
                    for (int c = 2; c < ch; c++)
                        out[c] = 0;
                } else {
                    out[0] = 0.5f * (l + r);
                }
                out += ch;
                sum += (double)l * l + (double)r * r;
                const float m = std::fabs(l) > std::fabs(r) ? std::fabs(l) : std::fabs(r);
                if (m > peak)
                    peak = m;
            }
            done += (int32_t)k;
        }
        callbacks_.fetch_add(1, std::memory_order_relaxed);
        frames_.fetch_add((uint64_t)numFrames, std::memory_order_relaxed);
        if ((uint32_t)numFrames > maxFrames_.load(std::memory_order_relaxed))
            maxFrames_.store((uint32_t)numFrames, std::memory_order_relaxed);
        // the level over ~100 ms windows
        winSum_ += sum;
        winFrames_ += (uint32_t)numFrames;
        if (peak > winPeak_)
            winPeak_ = peak;
        if (winFrames_ >= (uint32_t)(rate / 10)) {
            rms_.store((float)std::sqrt(winSum_ / (2.0 * winFrames_)), std::memory_order_relaxed);
            peak_.store(winPeak_, std::memory_order_relaxed);
            winSum_ = 0;
            winFrames_ = 0;
            winPeak_ = 0;
        }
        return oboe::DataCallbackResult::Continue;
    }

    void onErrorAfterClose(oboe::AudioStream *, oboe::Result r) override
    {
        disconnects_.fetch_add(1, std::memory_order_relaxed);   // (A2: reopen on the new default device)
        lastError_.store((int32_t)r, std::memory_order_relaxed);
    }

    rfme_t *e_;
    std::vector<float> L_, R_;
    std::atomic<uint32_t> callbacks_{0}, maxFrames_{0}, disconnects_{0};
    std::atomic<uint64_t> frames_{0};
    std::atomic<float> rms_{0}, peak_{0};
    std::atomic<int32_t> lastError_{0};
    double winSum_ = 0;                 // (the callback's)
    uint32_t winFrames_ = 0;
    float winPeak_ = 0;
};

} // namespace

struct rfmo {
    rfme_t *e = nullptr;
    std::shared_ptr<Output> cb;
    std::shared_ptr<oboe::AudioStream> stream;
    int32_t lastError = 0;
    char error[96] = {};
};

static void putErr(char *err, uint32_t n, const char *s)
{
    if (err && n)
        std::snprintf(err, n, "%s", s);
}

extern "C" {

rfmo_t *rfmo_new(rfme_t *e)
{
    auto *o = new rfmo;
    o->e = e;
    return o;
}

void rfmo_free(rfmo_t *o)
{
    if (!o)
        return;
    rfmo_stop(o, rfme_clock(o->e) == RFME_CLOCK_AUDIO ? RFME_CLOCK_NONE : rfme_clock(o->e));
    delete o;
}

int rfmo_start(rfmo_t *o, int32_t device_id, char *err, uint32_t n)
{
    if (o->stream) {
        putErr(err, n, "already playing");
        return 0;
    }
    o->cb = std::make_shared<Output>(o->e);
    oboe::AudioStreamBuilder b;
    b.setDirection(oboe::Direction::Output)
        ->setPerformanceMode(oboe::PerformanceMode::LowLatency)
        ->setSharingMode(oboe::SharingMode::Exclusive)
        ->setFormat(oboe::AudioFormat::Float)
        ->setChannelCount(oboe::ChannelCount::Stereo)
        ->setUsage(oboe::Usage::Media)
        ->setContentType(oboe::ContentType::Music)
        ->setDataCallback(o->cb)
        ->setErrorCallback(o->cb);
    if (device_id > 0)
        b.setDeviceId(device_id);
    oboe::Result r = b.openStream(o->stream);
    if (r != oboe::Result::OK) {
        o->lastError = (int32_t)r;
        std::snprintf(o->error, sizeof o->error, "openStream: %s", oboe::convertToText(r));
        putErr(err, n, o->error);
        o->stream.reset();
        o->cb.reset();
        return 0;
    }
    // two bursts of buffering (Oboe's low-latency advice); the engine's ring sits in front of it
    o->stream->setBufferSizeInFrames(o->stream->getFramesPerBurst() * 2);
    rfme_set_clock(o->e, RFME_CLOCK_AUDIO);
    r = o->stream->requestStart();
    if (r != oboe::Result::OK) {
        o->lastError = (int32_t)r;
        std::snprintf(o->error, sizeof o->error, "requestStart: %s", oboe::convertToText(r));
        putErr(err, n, o->error);
        o->stream->close();
        o->stream.reset();
        o->cb.reset();
        return 0;
    }
    o->error[0] = 0;
    return 1;
}

void rfmo_stop(rfmo_t *o, int clock_after)
{
    if (o->stream) {
        o->stream->stop();
        o->stream->close();
        o->stream.reset();
    }
    if (rfme_clock(o->e) == RFME_CLOCK_AUDIO)
        rfme_set_clock(o->e, clock_after);
}

void rfmo_status(rfmo_t *o, rfmo_status_t *st)
{
    std::memset(st, 0, sizeof *st);
    st->last_error = o->lastError;
    std::snprintf(st->error, sizeof st->error, "%s", o->error);
    if (o->cb) {
        st->callbacks = o->cb->callbacks_.load();
        st->frames = o->cb->frames_.load();
        st->max_frames = o->cb->maxFrames_.load();
        st->disconnects = o->cb->disconnects_.load();
        st->rms = o->cb->rms_.load();
        st->peak = o->cb->peak_.load();
        if (const int32_t e = o->cb->lastError_.load())
            st->last_error = e;
    }
    oboe::AudioStream *s = o->stream.get();
    if (!s)
        return;
    st->open = 1;
    st->running = s->getState() == oboe::StreamState::Started;
    st->rate = s->getSampleRate();
    st->channels = s->getChannelCount();
    st->frames_per_burst = s->getFramesPerBurst();
    st->buffer_size = s->getBufferSizeInFrames();
    st->buffer_capacity = s->getBufferCapacityInFrames();
    st->api = (int32_t)s->getAudioApi();
    st->sharing = (int32_t)s->getSharingMode();
    st->performance = (int32_t)s->getPerformanceMode();
    const oboe::ResultWithValue<int32_t> x = s->getXRunCount();
    st->xruns = x ? x.value() : -1;
}

} // extern "C"
