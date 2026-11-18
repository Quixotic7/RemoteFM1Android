// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 RemoteFM1 contributors
// Ported from RemoteFM1IOS ad33e95 RFMKit/Sources/RFMCore/MoveLink.cpp; changes: the "(Android)" parts (MoveLink.h).
// MoveLink (MoveLink.h): the Ableton Move on libmoveremote's client, the rfm_move core on the engine's core thread.

#include "MoveLink.h"

#include <pthread.h>
#include <signal.h>

#include <algorithm>

#include <chrono>
#include <cmath>
#include <cstdlib>
#include <cstring>

#include "moveremote/client.h"

namespace rfm {

namespace {
constexpr uint32_t kScreenMs = 33;          // the Move's screen at most ~30 a second (the web's SCREEN_MS)
constexpr uint32_t kLedMs = 5;              // the FM-1's LEDs onto the Move at most every 5 ms (and after input)
constexpr uint64_t kPingUs = 1000000, kSilentUs = 3000000;   // PING a second; nothing for 3 s: lost (STATUS: 500 ms)
constexpr int kConnectMs = 3000;
constexpr int kRetryMs[] = {1000, 2000, 4000};
constexpr size_t kMaxToCore = 4096;         // (inputs while no firmware runs the core: the oldest go)

uint64_t nowUs() { return mr_now_us(); }
} // namespace

MoveLink::MoveLink(const uint8_t *settings, uint32_t n, std::string hostName) : hostName_(std::move(hostName))
{
    m_ = static_cast<rfm_move_t *>(std::calloc(1, sizeof(rfm_move_t)));
    rfm_move_settings_t s;
    if (!settings || rfm_move_load(&s, settings, n) != 0)
        rfm_move_settings_default(&s);
    rfm_move_init(m_, &s, onAct, this);
    lcd_.resize(240 * 240);
    audio_.resize(2 * kAudioFrames);            // (Android: Sample's queue)
    peaks_.reserve(64);
    net_ =std::thread([this] { netMain(); });
}

MoveLink::~MoveLink()
{
    {
        std::lock_guard<std::mutex> g(mu_);
        quit_ = true;
        want_ = false;
    }
    cv_.notify_all();
    if (net_.joinable())
        net_.join();
    std::free(m_);
}

// ------------------------------------------------------------------ the app ---

void MoveLink::start(const std::string &host)
{
    {
        std::lock_guard<std::mutex> g(mu_);
        host_ = host;
        want_ = true;
    }
    cv_.notify_all();
}

void MoveLink::stop()
{
    {
        std::lock_guard<std::mutex> g(mu_);
        want_ = false;
    }
    cv_.notify_all();
}

MoveLink::Status MoveLink::status() const
{
    std::lock_guard<std::mutex> g(mu_);
    Status s = st_;
    s.ticking = nowUs() - lastTickUs_.load(std::memory_order_relaxed) < 500000;
    s.seqMidi = seqMidi_.load(std::memory_order_relaxed);
    return s;
}

bool MoveLink::takeRequest(Request &r)
{
    std::lock_guard<std::mutex> g(mu_);
    if (requests_.empty())
        return false;
    r = std::move(requests_.front());
    requests_.pop_front();
    return true;
}

void MoveLink::post(CoreMsg &&m)
{
    std::lock_guard<std::mutex> g(mu_);
    if (toCore_.size() >= kMaxToCore)
        toCore_.pop_front();
    toCore_.push_back(std::move(m));
}

void MoveLink::projectsInit(uint32_t dirBits, int openSlot, const std::vector<uint8_t> &openBlob)
{
    CoreMsg m{CoreMsg::kInit};
    m.bits = dirBits;
    m.slot = openSlot;
    m.blob = openBlob;
    post(std::move(m));
}

void MoveLink::projectDir(uint32_t bits)
{
    CoreMsg m{CoreMsg::kDir};
    m.bits = bits;
    post(std::move(m));
}

void MoveLink::projectInfo(int slot, const std::vector<uint8_t> &blob)
{
    CoreMsg m{CoreMsg::kInfo};
    m.slot = slot;
    m.blob = blob;
    post(std::move(m));
}

void MoveLink::projectAnswer(int slot, const std::vector<uint8_t> &blob)
{
    CoreMsg m{CoreMsg::kLoad};
    m.slot = slot;
    m.blob = blob;
    post(std::move(m));
}

void MoveLink::flushProject() { post(CoreMsg{CoreMsg::kFlush}); }

void MoveLink::setStatus(State s, const std::string &text)
{
    std::lock_guard<std::mutex> g(mu_);
    st_.state = s;
    st_.text = text;
}

// ------------------------------------------------------------------ the network thread ---

void MoveLink::netMain()
{
#ifdef __ANDROID__
    // (Android) mr_client's send() has no SO_NOSIGPIPE here: a write to a closed link is EPIPE on this thread, not a
    // signal that ends the app
    sigset_t pipe;
    sigemptyset(&pipe);
    sigaddset(&pipe, SIGPIPE);
    pthread_sigmask(SIG_BLOCK, &pipe, nullptr);
    pthread_setname_np(pthread_self(), "RFM Move");
#endif
    std::unique_lock<std::mutex> l(mu_);
    int retries = 0;
    while (!quit_) {
        cv_.wait(l, [this] { return quit_ || want_; });
        if (quit_)
            break;
        const std::string host = host_.empty() ? "move.local" : host_;
        st_.state = kConnecting;
        st_.text = "Connecting to " + host + "...";
        l.unlock();
        mr_callbacks_t cb{};
        cb.user = this;
        cb.on_input = [](void *u, const mr_event_t *ev) {
            auto *self = static_cast<MoveLink *>(u);
            self->lastRxUs_.store(nowUs(), std::memory_order_relaxed);
            CoreMsg m{CoreMsg::kInput};
            m.ev = *ev;
            self->post(std::move(m));
            std::lock_guard<std::mutex> g(self->mu_);
            self->st_.inputs++;
        };
        cb.on_status = [](void *u, const mr_status_t *st) {
            auto *self = static_cast<MoveLink *>(u);
            self->lastRxUs_.store(nowUs(), std::memory_order_relaxed);
            bool changed;
            {
                std::lock_guard<std::mutex> g(self->mu_);
                changed = self->st_.battery != st->battery;
                self->st_.battery = st->battery;
            }
            if (changed) {
                CoreMsg m{CoreMsg::kBattery};
                m.a = st->battery;
                m.b = st->power;
                self->post(std::move(m));
            }
        };
        cb.on_pong = [](void *u, uint32_t, uint64_t, uint32_t) {
            static_cast<MoveLink *>(u)->lastRxUs_.store(nowUs(), std::memory_order_relaxed);
        };
        cb.on_bye = [](void *u, int reason) { static_cast<MoveLink *>(u)->bye_.store(reason ? reason : -1); };
        std::string addr = host;                // "address" or "address:port" (port 0: MR_PORT)
        int port = 0;
        if (const size_t colon = addr.rfind(':'); colon != std::string::npos && addr.find(':') == colon &&
            colon + 1 < addr.size() && addr.find_first_not_of("0123456789", colon + 1) == std::string::npos) {
            port = std::atoi(addr.c_str() + colon + 1);
            addr.resize(colon);
        }
        mr_client_t *c = mr_connect(addr.c_str(), port, hostName_.c_str(), &cb, kConnectMs);
        if (!c) {
            const char *e = mr_last_error();
            const int ms = kRetryMs[retries < 2 ? retries : 2];
            retries++;
            l.lock();
            st_.state = kRetrying;
            st_.text = std::string(e && *e ? e : "no answer") + ". Is Move Remote running on the Move, and is " + host +
                       " its address? Trying again in " + std::to_string(ms / 1000) + " s.";
            cv_.wait_for(l, std::chrono::milliseconds(ms), [this] { return quit_ || !want_; });
            if (!want_ && !quit_) {
                st_.state = kOff;
                st_.text = "Off";
            }
            continue;
        }
        retries = 0;
        session(c);
        mr_close(c);
        post(CoreMsg{CoreMsg::kLost});
        l.lock();
        st_.via = -1;
        if (!want_ && st_.state != kError) {
            st_.state = kOff;
            if (st_.text.empty() || st_.text.rfind("Connected", 0) == 0)
                st_.text = "Off";
        }
    }
}

void MoveLink::session(mr_client_t *c)
{
    bye_.store(0);
    mr_config(c, MR_CFG_AUDIO_CLOCK, 0);        // (no AUDIO messages: the app plays its own sound)
    const mr_hello_t *h = mr_hello(c);
    {
        std::lock_guard<std::mutex> g(mu_);
        st_.state = kConnected;
        st_.name = h && h->name[0] ? h->name : "Move";
        st_.via = h ? h->via : -1;
        st_.text = "Connected to " + st_.name + (st_.via == MR_VIA_USB ? " over USB" : st_.via == MR_VIA_WIFI ? " over Wi-Fi" : "");
        st_.connects++;
        ledsOut_.clear();
        screenDue_ = false;
    }
    post(CoreMsg{CoreMsg::kConnected});
    lastRxUs_.store(nowUs());
    uint64_t lastPing = nowUs();
    uint32_t token = 0;
    std::vector<rfm_move_led_t> leds;
    std::array<uint8_t, MR_SCREEN_BYTES> screen{};
    for (;;) {
        {
            std::lock_guard<std::mutex> g(mu_);
            if (quit_ || !want_)
                return;
        }
        if (mr_poll(c, 2) < 0) {
            setStatus(kRetrying, "Link lost: connecting again...");
            return;
        }
        if (const int bye = bye_.load()) {
            std::lock_guard<std::mutex> g(mu_);
            if (bye == MR_BYE_SHUTDOWN || bye < 0) {
                st_.state = kRetrying;
                st_.text = "The Move shut down: connecting again when it's back...";
            } else {
                want_ = false;
                st_.state = kOff;
                st_.text = bye == MR_BYE_REPLACED ? "Another app took the Move." : "The Move left Move Remote.";
            }
            return;
        }
        bool sendScreen = false;
        {
            std::lock_guard<std::mutex> g(mu_);
            leds.swap(ledsOut_);
            if (screenDue_) {
                screen = screenOut_;
                screenDue_ = false;
                sendScreen = true;
            }
        }
        for (const rfm_move_led_t &d : leds) {
            if (d.rgb)
                mr_led_rgb(c, d.cc, d.number, d.r, d.g, d.b);
            else
                mr_led(c, d.cc, d.number, d.r, 0);
        }
        if (sendScreen)
            mr_display(c, screen.data());
        {
            std::lock_guard<std::mutex> g(mu_);
            st_.leds += (uint32_t)leds.size();
            st_.screens += sendScreen ? 1 : 0;
        }
        leds.clear();
        const uint64_t t = nowUs();
        if (t - lastPing >= kPingUs) {
            lastPing = t;
            mr_ping(c, ++token);
        }
        if (t - lastRxUs_.load(std::memory_order_relaxed) > kSilentUs) {
            setStatus(kRetrying, "The Move stopped answering: connecting again...");
            return;
        }
    }
}

// ------------------------------------------------------------------ the core thread ---

void MoveLink::onAct(void *ctx, const rfm_move_act_t *a) { static_cast<MoveLink *>(ctx)->act(*a); }

void MoveLink::act(const rfm_move_act_t &a)
{
    rfm_event_t e{};
    switch (a.kind) {
    case RFM_MOVE_ACT_KEY:
        e.type = RFM_EV_KEY, e.index = a.index, e.value = a.down, e.vel = a.down ? a.vel : 0;
        break;
    case RFM_MOVE_ACT_PRESS:
        e.type = RFM_EV_PRESS, e.index = a.index, e.value = a.value;
        break;
    case RFM_MOVE_ACT_BTN:
        e.type = RFM_EV_BUTTON, e.index = a.index, e.value = a.down;
        break;
    case RFM_MOVE_ACT_TURN:
        e.type = RFM_EV_ENC, e.index = a.index, e.value = a.value;
        break;
    case RFM_MOVE_ACT_MASTER:
        e.type = RFM_EV_MASTER, e.value = a.value;
        break;
    case RFM_MOVE_ACT_MIDI:
        if (target_ && a.n) {
            target_->moveMidi(a.bytes, a.n);
            seqMidi_.fetch_add(1, std::memory_order_relaxed);
        }
        return;
    case RFM_MOVE_ACT_SETTINGS: {
        Request r;
        r.kind = a.kind;
        r.data.resize(RFM_MOVE_SETTINGS_BYTES);
        rfm_move_save(&m_->s, r.data.data());
        request(std::move(r));
        return;
    }
    case RFM_MOVE_ACT_PROJECT_SAVE:
    case RFM_MOVE_ACT_PROJECT_LOAD:
    case RFM_MOVE_ACT_PROJECT_DELETE:
    case RFM_MOVE_ACT_PROJECT_CUT:
    case RFM_MOVE_ACT_PROJECT_COPY:
    case RFM_MOVE_ACT_PROJECT_PASTE:
    case RFM_MOVE_ACT_PROJECT_COLOR: {
        Request r;
        r.kind = a.kind;
        r.slot = a.index;
        r.value = a.value;
        if (a.kind == RFM_MOVE_ACT_PROJECT_SAVE && a.data)
            r.data.assign(a.data, a.data + a.len);
        request(std::move(r));
        return;
    }
    case RFM_MOVE_ACT_CLICK:                    // (Android) the metronome and Sample's audio loop: at this
    case RFM_MOVE_ACT_AUDIO_REC:                // millisecond's place in the engine's ring (coreAudio)
        mark(a.kind, a.value, a.index);
        return;
    default:
        return;
    }
    if (target_)
        target_->moveEvent(e);
}

void MoveLink::request(Request &&r) { reqPending_.push_back(std::move(r)); }

void MoveLink::coreMsg(CoreMsg &m, uint32_t ms)
{
    switch (m.kind) {
    case CoreMsg::kInput:
        if (live_) {
            rfm_move_input(m_, &m.ev, ms);
            screenDirty_ = true;
        }
        break;
    case CoreMsg::kConnected:
        live_ = true;
        lastMs_ = ms;
        rfm_move_leds_invalidate(m_);
        screenSent_.fill(0xA5);                 // (the first screen goes out whatever it is)
        screenDirty_ = true;
        lastLedMs_ = ms - kLedMs;
        break;
    case CoreMsg::kLost:
        if (live_) {
            rfm_move_release_all(m_);           // (every key, button and note let go; the sequencer stops)
            rfm_move_project_flush(m_);
        }
        live_ = false;
        break;
    case CoreMsg::kBattery:
        if (rfm_move_set_battery(m_, m.a, m.b))
            screenDirty_ = true;
        break;
    case CoreMsg::kInit:
        rfm_move_project_dir(m_, m.bits);
        if (rfm_move_project_load(m_, m.slot, m.blob.empty() ? nullptr : m.blob.data(), (uint32_t)m.blob.size()) < 0)
            rfm_move_project_load(m_, m.slot, nullptr, 0);   // (unreadable: it starts empty; the stored copy stays)
        screenDirty_ = true;
        break;
    case CoreMsg::kDir:
        rfm_move_project_dir(m_, m.bits);
        screenDirty_ = true;
        break;
    case CoreMsg::kInfo:
        rfm_move_project_info(m_, m.slot, m.blob.empty() ? nullptr : m.blob.data(), (uint32_t)m.blob.size());
        screenDirty_ = true;
        break;
    case CoreMsg::kLoad:
        if (rfm_move_project_load(m_, m.slot, m.blob.empty() ? nullptr : m.blob.data(), (uint32_t)m.blob.size()) < 0)
            rfm_move_project_load(m_, m.slot, nullptr, 0);
        screenDirty_ = true;
        break;
    case CoreMsg::kFlush:
        rfm_move_project_flush(m_);
        break;
    }
}

void MoveLink::coreTick(uint32_t ms, MoveTarget &t, const uint8_t levels[RFM_MOVE_NLEVELS], uint8_t playGreen,
                        int master, const uint16_t *lcdBE, uint32_t lcdWrites)
{
    target_ = &t;
    lastTickUs_.store(nowUs(), std::memory_order_relaxed);
    // what came in (never waiting: the lock busy, it waits for the next millisecond)
    if (mu_.try_lock()) {
        work_.insert(work_.end(), std::make_move_iterator(toCore_.begin()), std::make_move_iterator(toCore_.end()));
        toCore_.clear();
        if (!ledsPending_.empty()) {
            ledsOut_.insert(ledsOut_.end(), ledsPending_.begin(), ledsPending_.end());
            ledsPending_.clear();
        }
        if (screenPending_) {
            screenOut_ = screen_;
            screenDue_ = true;
            screenPending_ = false;
        }
        while (!reqPending_.empty()) {
            requests_.push_back(std::move(reqPending_.front()));
            reqPending_.pop_front();
        }
        st_.mode = m_->s.mode;
        st_.project = rfm_move_project_slot(m_);
        st_.playing = m_->seq.playing != 0;
        st_.following = rfm_move_seq_following(m_) != 0;     // (Android)
        st_.bpm10 = rfm_move_seq_bpm10(m_);
        st_.clicks = clickCount_.load(std::memory_order_relaxed);
        mu_.unlock();
    }
    if (const uint32_t lead = t.moveLeadUs(); lead != leadUs_) {   // (Android: recordings where they were heard)
        leadUs_ = lead;
        rfm_move_seq_set_lead_us(m_, lead);
    }
    const bool hadInput = !work_.empty();
    rfm_move_set_master(m_, master);
    for (CoreMsg &m : work_)
        coreMsg(m, ms);
    work_.clear();
    if (!live_)
        return;
    // the sequencer and the popups' timers, by the device clock (us)
    const uint32_t dt = ms - lastMs_;
    lastMs_ = ms;
    if (dt > 0 && dt < 1000)
        rfm_move_seq_advance(m_, dt * 1000u, 1000000u);
    // the FM-1's LEDs onto the Move
    if (hadInput || ms - lastLedMs_ >= kLedMs) {
        lastLedMs_ = ms;
        rfm_move_panel(m_, levels, playGreen);
        rfm_move_led_t buf[RFM_MOVE_NLEDS];
        const int n = rfm_move_leds(m_, buf, RFM_MOVE_NLEDS);
        if (n > 0)
            ledsPending_.insert(ledsPending_.end(), buf, buf + n);
    }
    // the screen: after input, a new FM-1 frame, or while it moves with the clock; only when it changed
    if (ms - lastScreenMs_ >= kScreenMs &&
        (screenDirty_ || hadInput || lcdWrites != lastLcdWrites_ || rfm_move_screen_timed(m_))) {
        lastScreenMs_ = ms;
        screenDirty_ = false;
        const uint16_t *px = nullptr;
        if (lcdBE && lcdWrites != lastLcdWrites_) {
            for (size_t i = 0; i < lcd_.size(); i++)
                lcd_[i] = (uint16_t)(lcdBE[i] >> 8 | lcdBE[i] << 8);   // (the FM-1's are big-endian, as sent)
            px = lcd_.data();
        }
        lastLcdWrites_ = lcdWrites;
        rfm_move_screen(m_, px, screen_.data());
        if (screen_ != screenSent_) {
            screenSent_ = screen_;
            screenPending_ = true;
        }
    }
}

void MoveLink::coreMidiOut(const uint8_t *msg, int n)
{
    if (live_ && n > 0)
        rfm_move_seq_midi_in(m_, msg, n);
}

// ------------------------------------------------------------------ (Android) Sample and the metronome ---
// The web player plays the click after its volume, never into a recording (js/audio.js click: 1760 Hz on a bar's first
// beat, else 1320 Hz, up in 2 ms, down over 40 ms), and cuts Sample's take at the bar lines' frames, into its
// recorder's Takes (js/rec-ui.js loopStart / loopStop), with the take's ~16 ms peaks for the Move's waveform. Here the
// marks carry the ring position of the millisecond they came in; coreAudio does the rest at the chunk that holds them.

void MoveLink::mark(int kind, int value, int index)
{
    const int64_t pos = target_ ? target_->moveAudioPos() : -1;
    if (pos < 0 || nmarks_ >= (int)marks_.size())
        return;                                 // (no audio clock: no click, no take)
    marks_[(size_t)nmarks_++] = Mark{pos, kind, value, index};
}

void MoveLink::coreAudio(uint64_t pos, float *L, float *R, uint32_t n, uint32_t rate)
{
    if (!rate)
        return;
    uint64_t w = audioW_.load(std::memory_order_relaxed);
    const uint64_t r = audioR_.load(std::memory_order_acquire);
    for (uint32_t f = 0; f < n; f++) {
        const uint64_t at = pos + f;
        while (nmarks_ > 0 && marks_[0].pos <= (int64_t)at) {
            const Mark mk = marks_[0];
            for (int i = 1; i < nmarks_; i++)
                marks_[(size_t)i - 1] = marks_[(size_t)i];
            nmarks_--;
            if (mk.kind == RFM_MOVE_ACT_CLICK) {
                if (nclicks_ < (int)clicks_.size())
                    clicks_[(size_t)nclicks_++] = Click{at, 0, mk.value != 0};
                clickCount_.fetch_add(1, std::memory_order_relaxed);
            } else if (mk.value) {              // Sample: from here
                sampling_ = true;
                sampleFrames_ = 0;
                sampleEnd_ = -1;
                winFrames_ = rate * 16 / 1000 ? rate * 16 / 1000 : 1;
                winN_ = winPeak_ = 0;
                Request q;
                q.kind = kSampleStart;
                q.value = (int)rate;
                request(std::move(q));
            } else if (sampling_) {             // .. to the bar line: exactly N bars of frames at the tempo (the
                sampleBars_ = mk.index;         // marks come on the device clock's millisecond, so the length is
                sampleBpm10_ = rfm_move_seq_bpm10(m_);   // counted, not taken from them)
                sampleEnd_ = sampleBpm10_ > 0 ? (int64_t)std::llround((double)sampleBars_ * 2400.0 * rate / sampleBpm10_)
                                               : (int64_t)sampleFrames_;
            }
        }
        if (sampling_ && sampleEnd_ >= 0 && (int64_t)sampleFrames_ >= sampleEnd_) {   // the take ends
            sampling_ = false;
            Request q;
            q.kind = kSampleStop;
            q.slot = sampleBars_;
            q.value = sampleBpm10_;
            q.total = (uint32_t)sampleEnd_;     // (frames past it, if any, are the app's to drop)
            audioW_.store(w, std::memory_order_release);
            request(std::move(q));
        }
        if (sampling_) {                        // (the firmware's sound only: the clicks come after)
            const float l = L[f] < -1.f ? -1.f : L[f] > 1.f ? 1.f : L[f];
            const float rr = R[f] < -1.f ? -1.f : R[f] > 1.f ? 1.f : R[f];
            if (w - r < kAudioFrames) {         // (the app far behind: those frames are lost)
                int16_t *o = audio_.data() + 2 * (w % kAudioFrames);
                o[0] = (int16_t)std::lrint(l * 32767.f);
                o[1] = (int16_t)std::lrint(rr * 32767.f);
                w++;
            }
            sampleFrames_++;
            const uint32_t pk = (uint32_t)(std::fmax(std::fabs(l), std::fabs(rr)) * 255.f + 0.5f);
            if (pk > winPeak_)
                winPeak_ = pk;
            if (++winN_ >= winFrames_) {
                if (peaks_.size() < 64)
                    peaks_.push_back((uint8_t)(winPeak_ > 255 ? 255 : winPeak_));
                winN_ = winPeak_ = 0;
            }
        }
        for (int i = 0; i < nclicks_;) {        // the metronome, mixed in
            Click &c = clicks_[(size_t)i];
            const uint32_t len = (uint32_t)(0.04 * rate), up = (uint32_t)(0.002 * rate) + 1;
            const float f0 = c.accent ? 1760.f : 1320.f, peak = c.accent ? 0.5f : 0.3f;
            const float env = c.at < up ? (float)c.at / (float)up : std::exp(-6.9f * (float)(c.at - up) / (float)(len - up));
            const float v = peak * env * std::sin(6.2831853f * f0 * (float)c.at / (float)rate);
            L[f] += v;
            R[f] += v;
            if (++c.at >= len)
                clicks_[(size_t)i] = clicks_[(size_t)--nclicks_];
            else
                i++;
        }
    }
    audioW_.store(w, std::memory_order_release);
    if (!peaks_.empty()) {
        if (rfm_move_sample_levels(m_, peaks_.data(), (int)peaks_.size()) > 0)
            screenDirty_ = true;
        peaks_.clear();
    }
}

uint32_t MoveLink::takeAudio(int16_t *stereo, uint32_t max)
{
    uint64_t r = audioR_.load(std::memory_order_relaxed);
    const uint64_t w = audioW_.load(std::memory_order_acquire);
    uint32_t n = 0;
    for (; r < w && n < max; r++, n++) {
        const int16_t *s = audio_.data() + 2 * (r % kAudioFrames);
        stereo[2 * n] = s[0];
        stereo[2 * n + 1] = s[1];
    }
    audioR_.store(r, std::memory_order_release);
    return n;
}

// ------------------------------------------------------------------ (Android) tests without a network ---

void MoveLink::testInput(const mr_event_t &ev)
{
    CoreMsg m{CoreMsg::kInput};
    m.ev = ev;
    post(std::move(m));
    std::lock_guard<std::mutex> g(mu_);
    st_.inputs++;
}

void MoveLink::testConnected(bool up)
{
    {
        std::lock_guard<std::mutex> g(mu_);
        st_.state = up ? kConnected : kOff;
        st_.name = up ? "Test" : "";
        ledsOut_.clear();
        screenDue_ = false;
    }
    post(CoreMsg{up ? CoreMsg::kConnected : CoreMsg::kLost});
}

int MoveLink::testTakeLeds(rfm_move_led_t *out, int max)
{
    std::lock_guard<std::mutex> g(mu_);
    const int n = (int)ledsOut_.size() < max ? (int)ledsOut_.size() : max;
    std::copy(ledsOut_.begin(), ledsOut_.begin() + n, out);
    ledsOut_.erase(ledsOut_.begin(), ledsOut_.begin() + n);
    st_.leds += (uint32_t)n;
    return n;
}

bool MoveLink::testTakeScreen(uint8_t *out)
{
    std::lock_guard<std::mutex> g(mu_);
    if (!screenDue_)
        return false;
    std::memcpy(out, screenOut_.data(), MR_SCREEN_BYTES);
    screenDue_ = false;
    st_.screens++;
    return true;
}

} // namespace rfm
