// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 RemoteFM1 contributors
// Copied from RemoteFM1IOS 0d1a98b RFMKit/Sources/RFMCore/RemoteLink.cpp; changes: Android. rfm_android (AMidi) in
// place of rfm_mac (CoreMIDI); no unit lock, no gate or drop, no shared fake; the fake has no bulk side (SysEx only,
// as the unit is over Android MIDI); Status also carries the back end's numbers and the frame link's name.
// Remote FM-1: the session thread and the back ends (see RemoteLink.h).
#include "RemoteLink.h"

#include <cstdio>
#include <cstring>

#include "android/rfm_android.h"
#include "rfm/fake.h"
#include "rfm/screen.h"
#include "rfm/session.h"

const char *RemoteLink::stateName(State s)
{
    switch (s) {
    case kOff: return "off";
    case kInUse: return "in use";
    case kSearching: return "searching";
    case kConnected: return "connected";
    case kLost: return "unit lost";
    case kError: return "error";
    case kStopping: return "stopping";
    }
    return "?";
}

RemoteLink::RemoteLink(const Options &o) : opt_(o), t0_(std::chrono::steady_clock::now())
{
    if (opt_.fps < 1 || opt_.fps > 60)
        opt_.fps = 20;
    if (opt_.backend == Backend::Fake) {
        rfm_fake_cfg_t fc;
        rfm_fake_cfg_default(&fc);
        fc.sx_rate_bps = 300000;                 // the M0 numbers (RemoteFM1 PLAN.md section 10), as fm1remote's
        fc.bk_rate_bps = 830000;
        fc.has_bulk = 0;                         // (SysEx only, as the unit is over Android MIDI)
        if (opt_.fakeBluetooth) {                // (a 0.2.2 unit without RFM_CAP_BLE_FAST: ~6 KB/s, no bulk)
            fc.ble = 1;
            fc.sx_rate_bps = 6000;
        }
        if (opt_.fakeNoTouch)
            fc.touch = 0;
        fake_ = rfm_fake_new(&fc);
    }
    loopBuf_.resize(1u << 18);
    st_.text = "off";
}

RemoteLink::~RemoteLink()
{
    {
        std::lock_guard<std::mutex> g(mu_);
        quit_ = true;
        want_ = false;
    }
    cv_.notify_all();
    if (th_.joinable())
        th_.join();
    rfm_fake_free(fake_);
}

void RemoteLink::start()
{
    {
        std::lock_guard<std::mutex> g(mu_);
        if (quit_)
            return;
        want_ = true;
        if (!th_.joinable())
            th_ = std::thread([this] { threadMain(); });
    }
    cv_.notify_all();
}

void RemoteLink::stop()
{
    {
        std::lock_guard<std::mutex> g(mu_);
        want_ = false;
    }
    cv_.notify_all();
}

void RemoteLink::invalidateScreen() { invalidate_.store(true, std::memory_order_release); }

RemoteLink::Status RemoteLink::status() const
{
    Status s;
    {
        std::lock_guard<std::mutex> g(stMu_);
        s = st_;
    }
    s.eventsIn = eventsIn_.load();
    s.trsMidiIn = trsMidiIn_.load();
    s.trsMidiDropped = trsMidiDropped_.load();
    s.eventsDropped = eventsDropped_.load();
    s.snapshotsTaken = snapshotsTaken_.load();
    s.fullResends = fullResends_.load();
    s.runs = runs_.load();
    return s;
}

uint32_t RemoteLink::now() const
{
    return (uint32_t)std::chrono::duration_cast<std::chrono::milliseconds>(std::chrono::steady_clock::now() - t0_)
        .count();
}

void RemoteLink::setStatus(State s, const std::string &text)
{
    std::lock_guard<std::mutex> g(stMu_);
    st_.state = s;
    st_.text = text;
    if (s != kConnected) {
        st_.bulk = false;
        st_.fps = 0.0f;
    }
}

// ------------------------------------------------------------------ the thread ---
void RemoteLink::threadMain()
{
    for (;;) {
        {
            std::unique_lock<std::mutex> l(mu_);
            cv_.wait(l, [this] { return quit_.load() || want_.load(); });
            if (quit_)
                break;
        }
        runOnce();
    }
    setStatus(kOff, "off");
}

void RemoteLink::runOnce()
{
    runs_.fetch_add(1);

    // ---- 1. the session and its links
    rfm_session_cfg_t sc;
    rfm_session_cfg_default(&sc);
    sc.fps = opt_.fps;
    sc.host_name = opt_.hostName.c_str();
    sc.transport = RFM_T_AUTO;                   // (only SysEx links on Android: no vendor bulk)
    sc.key_touch = touchApplied_ = touchWant_.load();
    const rfm_session_cb_t cb{this, cbEvent, cbState, cbRescan, cbLog, cbMidiIn};
    lastState_ = -1;
    wasConnected_ = false;
    statFrames_ = 0;
    statT_ = now();
    {
        std::lock_guard<std::mutex> g(fakeMu_);
        s_ = rfm_session_new(&sc, &cb);
        if (s_ && fake_) {
            loops_[0] = {this, RFM_LINK_SX, rfm_session_add_link(s_, RFM_LINK_SX, loopWrite, &loops_[0], "fake midi")};
            loops_[1] = {this, RFM_LINK_BK, -1};
        }
    }
    const bool fakePath = fake_ != nullptr;
    bool ok = s_ != nullptr;
    if (!ok)
        setStatus(kError, "out of memory");
    if (ok && !fakePath) {
        rfm_android_cfg_t ac{};
        char err[200] = "";
        ac.virtual_name = opt_.virtualName.c_str();
        ac.log = [](void *ctx, const char *m) { cbLog(ctx, m); };
        ac.log_ctx = this;
        and_ = rfm_android_new(&ac, err, sizeof err);
        if (!and_) {
            setStatus(kError, err[0] ? err : "cannot start Android MIDI");
            ok = false;
        } else {
            rfm_android_attach(and_, s_);
        }
    }
    if (!ok) {                                   // (nothing to run: wait for the toggle)
        std::unique_lock<std::mutex> l(mu_);
        cv_.wait(l, [this] { return !want_.load() || quit_.load(); });
    } else {
        fullReq_.store(true, std::memory_order_release);
        engaged_.store(true, std::memory_order_release);
        setStatus(kSearching, "searching for the FM-1 (RemoteFM1 firmware)");
    }

    // ---- 2. the loop (fm1remote's session_thread)
    bool stopping = false;
    uint32_t tStop = 0;
    while (ok) {
        const uint32_t t = now();
        std::unique_lock<std::mutex> fg(fakeMu_, std::defer_lock);
        if (fakePath)
            fg.lock();
        if (!want_ && !stopping) {
            rfm_session_stop(s_, t);             // SESSION start=0: the unit goes back to its waiting screen
            stopping = true;
            tStop = t;
            setStatus(kStopping, "stopping");
        }
        if (stopping && (rfm_session_state(s_) == RFM_S_STOPPED || t - tStop > kStopMs))
            break;
        if (!fakePath && rescan_.exchange(false, std::memory_order_acq_rel))
            rfm_android_rescan(and_);
        if (fakePath)
            pumpFake(t);
        else
            rfm_android_pump(and_, t);
        if (const RemoteSnapshot *sn = tb_.take()) {
            rfm_session_set_frame(s_, sn->lcd);
            rfm_session_set_leds(s_, &sn->leds);
            snapshotsTaken_.fetch_add(1);
            // a power cycle / core switch: the unit's screen is resent whole (after the new core's first frame)
            if (invalidate_.exchange(false, std::memory_order_acq_rel)) {
                rfm_screen_invalidate(const_cast<rfm_screen_t *>(rfm_session_screen(s_)));
                fullResends_.fetch_add(1);
            }
        }
        if (const uint32_t tw = touchWant_.load(); tw != touchApplied_) {
            rfm_session_set_key_touch(s_, tw);   // (in a session with a TOUCH unit: sent at once)
            touchApplied_ = tw;
        }
        rfm_session_poll(s_, t);
        if (fakePath)
            pumpFake(t);
        if (!stopping)
            updateStatus(t);
        if (fakePath) {
            fg.unlock();
            std::this_thread::sleep_for(std::chrono::microseconds(700));
        } else {
            rfm_android_wait(and_, 2);
        }
    }

    // ---- 3. the end: everything the unit held let go, the back end closed
    engaged_.store(false, std::memory_order_release);
    {
        std::lock_guard<std::mutex> g(fakeMu_);
        if (s_)
            releasePanel();
        rfm_android_free(and_);                  // (after the session stopped: closes the ports)
        and_ = nullptr;
        rfm_session_free(s_);
        s_ = nullptr;
        loops_[0].id = loops_[1].id = -1;
    }
    {
        std::lock_guard<std::mutex> g(stMu_);
        st_.midiLinks = 0;
    }
    setStatus(kOff, "off");
}

void RemoteLink::updateStatus(uint32_t t)
{
    rfm_session_stats_t ss;
    rfm_session_stats(s_, &ss);
    const int state = rfm_session_state(s_);
    rfm_android_stats_t as{};
    if (and_)
        rfm_android_stats(and_, &as);
    char linkName[96] = "";
    if (and_)
        rfm_android_link_name(and_, rfm_session_frame_link(s_), linkName, sizeof linkName);
    else if (fake_ && rfm_session_frame_link(s_) >= 0)
        std::snprintf(linkName, sizeof linkName, "fake midi%s", opt_.fakeBluetooth ? " (Bluetooth)" : "");
    std::lock_guard<std::mutex> g(stMu_);
    if (t - statT_ >= 1000u) {
        st_.fps = (float)((double)(ss.frames - statFrames_) * 1000.0 / (double)(t - statT_));
        statFrames_ = ss.frames;
        statT_ = t;
    }
    st_.sessions = ss.sessions;
    st_.frames = ss.frames;
    st_.acks = ss.acks;
    st_.pending = ss.pending;
    st_.inFlight = ss.in_flight;
    st_.colors = ss.lossy;
    st_.reduced = ss.reduced;
    st_.refined = ss.refined;
    st_.held = ss.held;
    st_.sessionState = state;
    st_.budget = rfm_session_budget(s_);
    st_.wireBytes = ss.wire_bytes;
    st_.bodyBytes = ss.body_bytes;
    st_.ackMsLast = ss.last_ack_ms;
    st_.ackMsAvg = ss.acks ? ss.ack_ms_sum / ss.acks : 0;
    st_.ackMsMax = ss.ack_ms_max;
    st_.ackTimeouts = ss.ack_timeouts;
    st_.discoveries = ss.discoveries;
    st_.hold = ss.hold;
    st_.wireCap = ss.wire_cap;
    st_.frameMs = ss.frame_ms;
    st_.touchWanted = touchApplied_;
    st_.touch = ss.touch;
    st_.touchSent = ss.touch_sent;
    st_.midiLinks = fake_ ? 1 : as.midi_links;
    st_.rxBytes = as.rx_bytes;
    st_.txBytes = as.tx_bytes;
    st_.txShort = as.tx_short;
    st_.frameLinkName = linkName;
    if (const rfm_hello_t *h = rfm_session_hello(s_); h && h->name[0]) {
        st_.hello = *h;
        st_.haveHello = true;
    }
    char b[160];
    switch (state) {
    case RFM_S_ACTIVE:
        st_.state = kConnected;
        st_.bulk = false;
        st_.ble = ss.ble != 0;
        std::snprintf(b, sizeof b, "connected (%s, %.0f fps)", st_.ble ? "Bluetooth" : "MIDI", (double)st_.fps);
        break;
    case RFM_S_STARTING:
        st_.state = wasConnected_ ? kLost : kSearching;
        std::snprintf(b, sizeof b, "found the FM-1: starting the session");
        break;
    case RFM_S_DISCOVER:
        st_.state = wasConnected_ ? kLost : kSearching;
        std::snprintf(b, sizeof b, "%s", wasConnected_ ? "unit lost: searching" : "searching for the FM-1 (RemoteFM1 firmware)");
        break;
    default:
        st_.state = kStopping;
        std::snprintf(b, sizeof b, "stopping");
        break;
    }
    if (st_.state != kConnected) {
        st_.bulk = false;
        st_.ble = false;
        st_.fps = 0.0f;
    }
    st_.text = b;
}

void RemoteLink::pumpFake(uint32_t t)
{
    rfm_fake_poll(fake_, t);
    for (const Loop &l : loops_) {
        if (l.id < 0 || l.kind != RFM_LINK_SX)
            continue;
        if (const uint32_t n = rfm_fake_take_sx(fake_, loopBuf_.data(), (uint32_t)loopBuf_.size()))
            rfm_session_rx(s_, l.id, loopBuf_.data(), n, t);
    }
}

int RemoteLink::loopWrite(void *ctx, const uint8_t *p, uint32_t n)
{
    const Loop *l = static_cast<const Loop *>(ctx);
    rfm_fake_rx_sx(l->self->fake_, p, n, l->self->now());
    return 0;
}

// the unit's held keys and buttons are up for the core; the panel decoder forgets them too, so the next session's
// PANEL_R reports whatever is still held as a fresh press
void RemoteLink::releasePanel()
{
    rfm_panel_t *p = const_cast<rfm_panel_t *>(rfm_session_panel(s_));
    p->keys = 0;
    p->buttons = 0;
    rfm_event_t e{};
    e.type = kEvRelease;
    if (!q_.push(e))
        eventsDropped_.fetch_add(1);
}

// ------------------------------------------------------------------ callbacks (the session thread) ---
void RemoteLink::cbEvent(void *ctx, const rfm_event_t *e)
{
    RemoteLink *self = static_cast<RemoteLink *>(ctx);
    self->eventsIn_.fetch_add(1);
    if (!self->q_.push(*e))
        self->eventsDropped_.fetch_add(1);
}

// the unit's TRS MIDI in (MIDI_IN; libremotefm1 checked the body: whole messages, each with its status): one queue
// entry per message, for the core's MIDI in (the engine's toFirmware, as the website's site.midiIn)
void RemoteLink::cbMidiIn(void *ctx, const uint8_t *msgs, uint32_t n)
{
    RemoteLink *self = static_cast<RemoteLink *>(ctx);
    for (uint32_t k = 0; k < n;) {
        const uint32_t m = rfm_midi_msg_len(msgs[k]);
        if (!m || k + m > n)
            break;
        MidiMsg e;
        e.len = (uint8_t)m;
        std::memcpy(e.data, msgs + k, m);
        (self->midiQ_.push(e) ? self->trsMidiIn_ : self->trsMidiDropped_).fetch_add(1);
        k += m;
    }
}

void RemoteLink::cbState(void *ctx, int st, const char *why)
{
    RemoteLink *self = static_cast<RemoteLink *>(ctx);
    if (self->lastState_ == RFM_S_ACTIVE && st != RFM_S_ACTIVE)
        self->releasePanel();                    // (the session ended or the unit went away)
    if (st == RFM_S_ACTIVE)
        self->wasConnected_ = true;
    self->lastState_ = st;
    char b[200];
    std::snprintf(b, sizeof b, "session: %s (%s)", rfm_session_state_name(st), why ? why : "");
    cbLog(ctx, b);
}

void RemoteLink::cbRescan(void *ctx)
{
    RemoteLink *self = static_cast<RemoteLink *>(ctx);
    if (self->and_)
        rfm_android_rescan(self->and_);
}

void RemoteLink::cbLog(void *ctx, const char *m)
{
    RemoteLink *self = static_cast<RemoteLink *>(ctx);
    std::lock_guard<std::mutex> g(self->stMu_);
    self->st_.lastLog = m ? m : "";
}
