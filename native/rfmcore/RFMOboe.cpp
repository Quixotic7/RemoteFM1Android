// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 RemoteFM1 contributors
// The Oboe output (RFMOboe.h). The callback: rfme_render into two scratch buffers (sized at open, so nothing is
// allocated there), interleaved into Oboe's buffer, the level measured. No locks, no waits: a stream that is not the
// active one, or finds another callback rendering, plays silence.
#include "RFMOboe.h"

#include <android/log.h>
#include <oboe/Oboe.h>
#include <oboe/OboeExtensions.h>

#include <atomic>
#include <chrono>
#include <cmath>
#include <cstdio>
#include <cstring>
#include <memory>
#include <mutex>
#include <thread>
#include <vector>

#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, "RFMAudio", __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, "RFMAudio", __VA_ARGS__)

namespace {

constexpr uint32_t kScratch = 4096;     // frames rendered per rfme_render at most (a bigger callback: in pieces)

// what the streams share with the rfmo_t (kept alive by each stream, so a stream outliving the engine is safe)
struct Shared {
    rfme_t *e = nullptr;
    std::atomic<uint32_t> active{0};    // the generation of the stream that renders (0: none)
    std::atomic<bool> busy{false};      // a callback is in rfme_render
    std::atomic<bool> gone{false};      // rfmo_free ran: nothing renders again
    std::atomic<uint32_t> gens{0};
    std::atomic<uint32_t> streams{0};
    std::atomic<int> closeHangMs{0}, openHangMs{0};
    std::mutex m;                       // the fields below
    std::shared_ptr<rfmo_stream> current;   // the active stream (for rfmo_status)
    uint32_t disconnectsDone = 0;       // of the streams closed (the count is the session's)
    int32_t lastError = 0;
    int32_t requested = 0;              // the last open's
    int usbSafe = 0;
    char error[96] = {};

    void fail(int32_t r, const char *what)
    {
        std::lock_guard<std::mutex> l(m);
        lastError = r;
        std::snprintf(error, sizeof error, "%s", what);
    }
};

class Output final : public oboe::AudioStreamDataCallback, public oboe::AudioStreamErrorCallback {
public:
    Output(std::shared_ptr<Shared> sh, uint32_t gen) : sh_(std::move(sh)), gen_(gen), L_(kScratch), R_(kScratch) {}

    oboe::DataCallbackResult onAudioReady(oboe::AudioStream *s, void *data, int32_t numFrames) override
    {
        float *out = static_cast<float *>(data);
        const int ch = s->getChannelCount();
        const double rate = (double)s->getSampleRate();
        double sum = 0;
        float peak = 0;
        bool rendered = false;
        // only the active stream renders, and one callback at a time (an old stream still running while its close is
        // stuck must never take the ring's frames or touch an engine that has moved on)
        if (sh_->active.load(std::memory_order_seq_cst) == gen_ && !sh_->busy.exchange(true, std::memory_order_seq_cst)) {
            if (sh_->active.load(std::memory_order_seq_cst) == gen_) {
                rendered = true;
                for (int32_t done = 0; done < numFrames;) {
                    const uint32_t k = (uint32_t)(numFrames - done) < kScratch ? (uint32_t)(numFrames - done) : kScratch;
                    rfme_render(sh_->e, L_.data(), R_.data(), k, rate);
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
            }
            sh_->busy.store(false, std::memory_order_seq_cst);
        }
        if (!rendered)
            std::memset(data, 0, (size_t)numFrames * (size_t)ch * sizeof(float));
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

    // a disconnect (the device gone, the default route changed): Oboe has closed the stream on its own thread; the
    // app's audio-control thread sees `dead` and opens a new one (AudioControl.kt). Nothing is closed here.
    void onErrorAfterClose(oboe::AudioStream *, oboe::Result r) override
    {
        disconnects_.fetch_add(1, std::memory_order_relaxed);
        lastError_.store((int32_t)r, std::memory_order_relaxed);
        dead_.store(true, std::memory_order_release);
    }

    std::shared_ptr<Shared> sh_;
    const uint32_t gen_;
    std::vector<float> L_, R_;
    std::atomic<uint32_t> callbacks_{0}, maxFrames_{0}, disconnects_{0};
    std::atomic<uint64_t> frames_{0};
    std::atomic<float> rms_{0}, peak_{0};
    std::atomic<int32_t> lastError_{0};
    std::atomic<bool> dead_{false};
    double winSum_ = 0;                 // (the callback's)
    uint32_t winFrames_ = 0;
    float winPeak_ = 0;
};

} // namespace

struct rfmo {
    std::shared_ptr<Shared> sh;
};

struct rfmo_stream {
    std::shared_ptr<Shared> sh;
    std::shared_ptr<Output> cb;
    std::shared_ptr<oboe::AudioStream> s;
    std::shared_ptr<rfmo_stream> self;  // the owner's reference, let go by rfmo_close
    std::mutex op;                      // held through start and close (rfmo_stream_info's getters skip then)
    bool closed = false;
    uint32_t gen = 0;
    std::atomic<bool> started{false};
    // what open found (read without Oboe)
    int32_t requested = 0, device = 0, rate = 0, channels = 0, burst = 0, api = 0, sharing = 0, performance = 0;
    int usbSafe = 0, mmap = -1;
    // refreshed by rfmo_stream_info (the owner's thread)
    std::atomic<int32_t> bufferSize{0}, bufferCapacity{0}, xruns{-1};
    std::atomic<double> latencyMs{-1};

    ~rfmo_stream() { sh->streams.fetch_sub(1); }
};

static void putErr(char *err, uint32_t n, const char *s)
{
    if (err && n)
        std::snprintf(err, n, "%s", s);
}

static void sleepMs(int ms)
{
    if (ms > 0)
        std::this_thread::sleep_for(std::chrono::milliseconds(ms));
}

extern "C" {

rfmo_t *rfmo_new(rfme_t *e)
{
    auto *o = new rfmo;
    o->sh = std::make_shared<Shared>();
    o->sh->e = e;
    return o;
}

void rfmo_free(rfmo_t *o)
{
    if (!o)
        return;
    Shared &sh = *o->sh;
    sh.gone.store(true);
    sh.active.store(0, std::memory_order_seq_cst);
    while (sh.busy.load(std::memory_order_seq_cst))  // (a render in flight finishes; none starts after)
        std::this_thread::yield();
    {
        std::lock_guard<std::mutex> l(sh.m);
        sh.current.reset();
    }
    delete o;
}

rfmo_stream_t *rfmo_open(rfmo_t *o, int32_t device_id, int usb_safe, const int32_t *forbidden, int n_forbidden,
                         char *err, uint32_t n)
{
    std::shared_ptr<Shared> sh = o->sh;
    sleepMs(sh->openHangMs.load());
    {
        std::lock_guard<std::mutex> l(sh->m);
        sh->requested = device_id;
        sh->usbSafe = usb_safe;
    }
    auto st = std::make_shared<rfmo_stream>();
    sh->streams.fetch_add(1);
    st->sh = sh;
    st->gen = sh->gens.fetch_add(1) + 1;
    st->cb = std::make_shared<Output>(sh, st->gen);
    st->requested = device_id;
    st->usbSafe = usb_safe;
    oboe::AudioStreamBuilder b;
    b.setDirection(oboe::Direction::Output)
        ->setFormat(oboe::AudioFormat::Float)
        ->setChannelCount(oboe::ChannelCount::Stereo)
        ->setUsage(oboe::Usage::Media)
        ->setContentType(oboe::ContentType::Music)
        ->setDataCallback(st->cb)
        ->setErrorCallback(st->cb);
    if (usb_safe) {
        // a USB sound card (the FM-1's): Android's mixer, no MMAP, no exclusive stream (MMAP to the FM-1 through the
        // Qualcomm USB offload restarted a Galaxy Tab A9+; README "Known issues")
        b.setPerformanceMode(oboe::PerformanceMode::None)->setSharingMode(oboe::SharingMode::Shared);
    } else {
        b.setPerformanceMode(oboe::PerformanceMode::LowLatency)->setSharingMode(oboe::SharingMode::Exclusive);
    }
    if (device_id > 0)
        b.setDeviceId(device_id);
    const bool mmapWas = oboe::OboeExtensions::isMMapEnabled();
    if (usb_safe && mmapWas) {
        const int32_t r = oboe::OboeExtensions::setMMapEnabled(false);
        LOGI("MMAP off for this stream: %d", (int)r);
    }
    oboe::Result r = b.openStream(st->s);
    if (usb_safe && mmapWas)
        oboe::OboeExtensions::setMMapEnabled(true);
    if (r != oboe::Result::OK) {
        char e[96];
        std::snprintf(e, sizeof e, "openStream: %s", oboe::convertToText(r));
        sh->fail((int32_t)r, e);
        putErr(err, n, e);
        LOGW("open on device %d failed: %s", (int)device_id, oboe::convertToText(r));
        return nullptr;
    }
    oboe::AudioStream *s = st->s.get();
    st->device = s->getDeviceId();
    st->mmap = oboe::OboeExtensions::isMMapUsed(s) ? 1 : 0;
    st->rate = s->getSampleRate();
    st->channels = s->getChannelCount();
    st->burst = s->getFramesPerBurst();
    st->api = (int32_t)s->getAudioApi();
    st->sharing = (int32_t)s->getSharingMode();
    st->performance = (int32_t)s->getPerformanceMode();
    LOGI("stream %u open: asked device %d, got device %d, %s, %s, performance %d, MMAP %d, %d Hz, burst %d",
         (unsigned)st->gen, (int)device_id, (int)st->device, s->getAudioApi() == oboe::AudioApi::AAudio ? "AAudio" : "OpenSL ES",
         s->getSharingMode() == oboe::SharingMode::Exclusive ? "exclusive" : "shared", (int)st->performance, st->mmap,
         (int)st->rate, (int)st->burst);
    // where it really goes: never a USB output the user did not pick (closed before a sample is played)
    for (int i = 0; i < n_forbidden; i++) {
        if (forbidden[i] > 0 && forbidden[i] == st->device) {
            char e[96];
            std::snprintf(e, sizeof e, "the stream went to USB output %d, not picked: closed", (int)st->device);
            sh->fail((int32_t)oboe::Result::ErrorInvalidState, e);
            putErr(err, n, e);
            LOGW("%s", e);
            s->close();
            st->s.reset();
            return nullptr;
        }
    }
    // two bursts of buffering (Oboe's low-latency advice); the engine's ring sits in front of it. (The careful
    // stream keeps the size Android gave it.)
    if (!usb_safe)
        s->setBufferSizeInFrames(st->burst * 2);
    st->bufferSize.store(s->getBufferSizeInFrames());
    st->bufferCapacity.store(s->getBufferCapacityInFrames());
    st->self = st;
    return st.get();
}

int rfmo_start(rfmo_stream_t *st, char *err, uint32_t n)
{
    std::lock_guard<std::mutex> l(st->op);
    const oboe::Result r = st->s->requestStart();
    if (r != oboe::Result::OK) {
        char e[96];
        std::snprintf(e, sizeof e, "requestStart: %s", oboe::convertToText(r));
        st->sh->fail((int32_t)r, e);
        putErr(err, n, e);
        LOGW("stream %u: %s", (unsigned)st->gen, e);
        return 0;
    }
    st->started.store(true);
    std::lock_guard<std::mutex> m(st->sh->m);
    st->sh->error[0] = 0;
    return 1;
}

void rfmo_activate(rfmo_stream_t *st)
{
    Shared &sh = *st->sh;
    if (sh.gone.load())
        return;
    {
        std::lock_guard<std::mutex> l(sh.m);
        if (sh.current && sh.current.get() != st)
            sh.disconnectsDone += sh.current->cb->disconnects_.exchange(0);
        sh.current = st->self;
    }
    sh.active.store(st->gen, std::memory_order_seq_cst);
}

void rfmo_deactivate(rfmo_stream_t *st)
{
    uint32_t g = st->gen;
    st->sh->active.compare_exchange_strong(g, 0, std::memory_order_seq_cst);
}

void rfmo_close(rfmo_stream_t *st)
{
    std::shared_ptr<rfmo_stream> keep = std::move(st->self);    // (freed when this returns)
    std::shared_ptr<Shared> sh = st->sh;
    uint32_t g = st->gen;
    sh->active.compare_exchange_strong(g, 0, std::memory_order_seq_cst);   // silent from now on
    {
        std::lock_guard<std::mutex> l(sh->m);
        if (sh->current.get() == st) {
            sh->disconnectsDone += st->cb->disconnects_.exchange(0);
            sh->current.reset();
        }
    }
    std::lock_guard<std::mutex> l(st->op);
    sleepMs(sh->closeHangMs.load());                // (tests: Android's close stuck)
    if (st->s) {
        st->s->stop();
        st->s->close();
        st->s.reset();
    }
    st->closed = true;
    st->started.store(false);
    LOGI("stream %u closed", (unsigned)st->gen);
}

void rfmo_stream_info(rfmo_stream_t *st, int refresh, rfmo_stream_info_t *o)
{
    std::memset(o, 0, sizeof *o);
    o->frames = st->cb->frames_.load();
    o->callbacks = st->cb->callbacks_.load();
    o->dead = st->cb->dead_.load(std::memory_order_acquire) ? 1 : 0;
    o->device_id = st->device;
    o->mmap = st->mmap;
    o->usb_safe = st->usbSafe;
    o->api = st->api;
    o->sharing = st->sharing;
    o->performance = st->performance;
    o->rate = st->rate;
    o->frames_per_burst = st->burst;
    // the getters that may ask Android (the owner's thread only; never while a start or close is in progress)
    if (!refresh)
        return;
    std::unique_lock<std::mutex> l(st->op, std::try_to_lock);
    if (l.owns_lock() && !st->closed && st->s && !o->dead) {
        oboe::AudioStream *s = st->s.get();
        st->bufferSize.store(s->getBufferSizeInFrames());
        st->bufferCapacity.store(s->getBufferCapacityInFrames());
        const oboe::ResultWithValue<int32_t> x = s->getXRunCount();
        st->xruns.store(x ? x.value() : -1);
        const oboe::ResultWithValue<double> lat = s->calculateLatencyMillis();
        st->latencyMs.store(lat ? lat.value() : -1);
    }
}

void rfmo_test_disconnect(rfmo_t *o)
{
    std::shared_ptr<rfmo_stream> cur;
    {
        std::lock_guard<std::mutex> l(o->sh->m);
        cur = o->sh->current;
    }
    if (cur)
        cur->cb->onErrorAfterClose(cur->s.get(), oboe::Result::ErrorDisconnected);
}

void rfmo_test_hang(rfmo_t *o, int close_ms, int open_ms)
{
    o->sh->closeHangMs.store(close_ms);
    o->sh->openHangMs.store(open_ms);
}

void rfmo_status(rfmo_t *o, rfmo_status_t *st)
{
    std::memset(st, 0, sizeof *st);
    Shared &sh = *o->sh;
    std::shared_ptr<rfmo_stream> cur;
    {
        std::lock_guard<std::mutex> l(sh.m);
        cur = sh.current;
        st->last_error = sh.lastError;
        st->requested_device = sh.requested;
        st->usb_safe = sh.usbSafe;
        st->disconnects = sh.disconnectsDone;
        std::snprintf(st->error, sizeof st->error, "%s", sh.error);
    }
    st->streams = sh.streams.load();
    st->mmap = -1;
    st->latency_ms = -1;
    if (!cur)
        return;
    const Output &cb = *cur->cb;
    st->callbacks = cb.callbacks_.load();
    st->frames = cb.frames_.load();
    st->max_frames = cb.maxFrames_.load();
    st->disconnects += cb.disconnects_.load();
    st->dead = cb.dead_.load(std::memory_order_acquire) ? 1 : 0;
    st->rms = cb.rms_.load();
    st->peak = cb.peak_.load();
    if (const int32_t e = cb.lastError_.load())
        st->last_error = e;
    st->open = 1;
    st->device_id = cur->device;
    st->requested_device = cur->requested;
    st->usb_safe = cur->usbSafe;
    st->mmap = cur->mmap;
    st->running = cur->started.load() && !st->dead;
    st->rate = cur->rate;
    st->channels = cur->channels;
    st->frames_per_burst = cur->burst;
    st->buffer_size = cur->bufferSize.load();
    st->buffer_capacity = cur->bufferCapacity.load();
    st->api = cur->api;
    st->sharing = cur->sharing;
    st->performance = cur->performance;
    st->xruns = cur->xruns.load();
    st->latency_ms = cur->latencyMs.load();
}

} // extern "C"
