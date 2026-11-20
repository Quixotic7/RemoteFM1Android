// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 RemoteFM1 contributors
// MoveLink (MoveLink.h): rfm1cs-abletonmove's session on the engine's core thread, its socket on a network thread.

#include "MoveLink.h"

#include <pthread.h>
#include <signal.h>

#include <chrono>
#include <cmath>
#include <cstdio>
#include <cstdlib>
#include <cstring>

#include "moveremote/proto.h"
#include "rfm1cs/posix_tcp.h"

namespace rfm {

namespace {
constexpr uint32_t kFrameMs = 33;           // the FM-1's screen into the session at most ~30 a second
constexpr uint32_t kDriveIdleMs = 100;      // the core thread this quiet: the network thread drives the session
constexpr size_t kMaxIn = 4096, kMaxPending = 1024, kMaxLeds = 8192;

uint32_t wallMs()
{
    using namespace std::chrono;
    return (uint32_t)duration_cast<milliseconds>(steady_clock::now().time_since_epoch()).count();
}

std::vector<uint8_t> framed(int type, const uint8_t *p, uint32_t n)
{
    std::vector<uint8_t> v(3 + n);
    rfm1cs_frame(v.data(), type, p, n);
    return v;
}
} // namespace

MoveLink::MoveLink(const uint8_t *settings, uint32_t n, const uint8_t *library, uint32_t libN, std::string hostName)
    : hostName_(std::move(hostName))
{
    s_ = static_cast<rfm1cs_session_t *>(std::calloc(1, sizeof(rfm1cs_session_t)));   // (not the stack)
    rfm1cs_session_init(s_, settings, n, hostName_.c_str(), onEvent, this);
    // (0.5.10) one firmware, no FX: the web page's and the phone apps' host (RemoteFM14Move on the Move keeps three
    // slots and FX). Before the catalog; then the stored library (favourites, colours, tags, slot 0 and its level)
    rfm_move_fw_caps(&s_->core, 0);
    if (library && libN && rfm_move_fw_library(&s_->core, library, libN) < 0)
        rfm_move_fw_library(&s_->core, nullptr, 0);   // (not a library: an empty one)
    rfm1cs_session_give_up(s_, -1);             // (tries for ever while wanted, as before)
    lcd_.resize(240 * 240);
    audio_.resize(2 * kAudioFrames);
    peaks_.reserve(64);
    net_ = std::thread([this] { netMain(); });
}

MoveLink::~MoveLink()
{
    {
        std::lock_guard<std::mutex> g(mu_);
        quit_ = true;
    }
    cv_.notify_all();
    if (net_.joinable())
        net_.join();
    std::free(s_);
}

// ------------------------------------------------------------------ the app ---

void MoveLink::post(In &&m)
{
    {
        std::lock_guard<std::mutex> g(mu_);
        if (in_.size() >= kMaxIn)
            in_.pop_front();
        in_.push_back(std::move(m));
    }
    cv_.notify_all();
}

void MoveLink::start(const std::string &host, const std::string &shown)
{
    In m(In::kStart);
    m.bytes.assign(host.begin(), host.end());
    m.shown = shown;
    post(std::move(m));
}

void MoveLink::stop() { post(In(In::kStop)); }

MoveLink::Status MoveLink::status() const
{
    std::lock_guard<std::mutex> g(mu_);
    Status s = st_;
    const uint32_t t = lastTickMs_.load(std::memory_order_relaxed);
    s.ticking = t && wallMs() - t < 500;
    s.seqMidi = seqMidi_.load(std::memory_order_relaxed);
    s.clicks = clickCount_.load(std::memory_order_relaxed);
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

void MoveLink::projectsInit(uint32_t dirBits, int openSlot, const std::vector<uint8_t> &openBlob)
{
    In m(In::kInit);
    m.bits = dirBits, m.slot = openSlot, m.bytes = openBlob;
    post(std::move(m));
}

void MoveLink::projectDir(uint32_t bits)
{
    In m(In::kDir);
    m.bits = bits;
    post(std::move(m));
}

void MoveLink::projectInfo(int slot, const std::vector<uint8_t> &blob)
{
    In m(In::kInfo);
    m.slot = slot, m.bytes = blob;
    post(std::move(m));
}

void MoveLink::projectAnswer(int slot, const std::vector<uint8_t> &blob)
{
    In m(In::kAnswer);
    m.slot = slot, m.bytes = blob;
    post(std::move(m));
}

void MoveLink::flushProject() { post(In(In::kFlush)); }

// ---- the firmware browser: the catalog built here (any thread), handed to the driver whole
void MoveLink::fwList(std::vector<FwEntry> &&entries)
{
    auto l = std::make_shared<FwList>();
    l->src = std::move(entries);
    l->arr.resize(l->src.size());
    for (size_t i = 0; i < l->src.size(); i++) {      // (pointers into l->src: it never moves again)
        const FwEntry &e = l->src[i];
        rfm_move_fw_t &f = l->arr[i];
        f.id = e.id.c_str();
        f.name = e.name.c_str();
        f.version = e.version.c_str();
        f.installed = e.hasInstalled ? e.installed.c_str() : nullptr;
        f.desc = e.hasDesc ? e.desc.c_str() : nullptr;
        f.thumb = e.thumb.size() == RFM_MOVE_FW_THUMB_BYTES ? e.thumb.data() : nullptr;
        f.cats = e.cats;
        f.cat = e.cat;
        f.virt = e.virt;
    }
    In m(In::kFwList);
    m.fw = std::move(l);
    post(std::move(m));
}

void MoveLink::fwInstalled(const std::string &id, const std::string *version)
{
    In m(In::kFwInstalled);
    m.shown = id;
    m.has = version != nullptr;
    if (version)
        m.bytes.assign(version->begin(), version->end());
    post(std::move(m));
}

void MoveLink::fwSlot(const std::string &id)
{
    In m(In::kFwSlot);
    m.shown = id;
    post(std::move(m));
}

void MoveLink::fwProgress(const std::string &id, int pct)
{
    In m(In::kFwProgress);
    m.shown = id;
    m.slot = pct;
    post(std::move(m));
}

// ------------------------------------------------------------------ the network thread ---
// Opens and closes the socket when the session says (publish: openReq_ / closeReq_), reads into in_ tagged with the
// attempt id that opened it, writes txq_. While no firmware runs the engine's clock it also drives the session (the
// guide's third shape: the core thread's lastTickMs_ older than kDriveIdleMs).

void MoveLink::netMain()
{
#ifdef __ANDROID__
    sigset_t pipe;                              // (a write to a closed link: EPIPE here, never the app's end)
    sigemptyset(&pipe);
    sigaddset(&pipe, SIGPIPE);
    pthread_sigmask(SIG_BLOCK, &pipe, nullptr);
    pthread_setname_np(pthread_self(), "RFM Move");
#endif
    int fd = -1;
    uint32_t attempt = 0;
    bool connected = false;
    std::vector<uint8_t> tx;
    std::vector<uint8_t> buf(16384);
    auto lost = [&](void) {
        rfm1cs_tcp_close(fd);
        fd = -1;
        connected = false;
        In m(In::kClosed);
        m.attempt = attempt;
        post(std::move(m));
    };
    for (;;) {
        bool doOpen, doClose, doDrop;
        std::string host;
        {
            std::unique_lock<std::mutex> l(mu_);
            if (fd < 0 && !openReq_ && !closeReq_ && !quit_ && in_.empty())   // (off: a slow beat for the driving)
                cv_.wait_for(l, std::chrono::milliseconds(
                                    st_.state == RFM1CS_OFF || st_.state == RFM1CS_FAILED ? 100 : 2));
            if (quit_)
                break;
            doClose = closeReq_, doOpen = openReq_, doDrop = dropReq_;
            closeReq_ = openReq_ = dropReq_ = false;
            if (doOpen)
                attempt = openAttempt_, host = openHost_;
            tx.swap(txq_);
        }
        if ((doClose || doOpen) && fd >= 0) {
            rfm1cs_tcp_close(fd);
            fd = -1;
            connected = false;
        }
        if (doOpen) {
            fd = rfm1cs_tcp_connect(host.c_str(), 0);   // ("address", "address:port", "[v6]:port"; no port: 7720)
            if (fd < 0)
                lost();                         // (no address: the session tries again)
        }
        if (doDrop && fd >= 0)
            lost();
        if (fd >= 0 && connected && !tx.empty() && rfm1cs_tcp_write(fd, tx.data(), (int)tx.size()) < 0)
            lost();
        tx.clear();
        if (fd >= 0 && !connected) {
            const int r = rfm1cs_tcp_wait(fd, 1);
            if (r > 0) {
                connected = true;
                In m(In::kOpened);
                m.attempt = attempt;
                post(std::move(m));
            } else if (r < 0) {
                lost();
            }
        } else if (fd >= 0) {
            const int n = rfm1cs_tcp_read(fd, buf.data(), (int)buf.size(), 1);
            if (n > 0) {
                In m(In::kRx);
                m.attempt = attempt;
                m.bytes.assign(buf.begin(), buf.begin() + n);
                post(std::move(m));
            } else if (n < 0) {
                lost();
            }
        }
        const uint32_t now = wallMs(), t = lastTickMs_.load(std::memory_order_relaxed);
        if (!t || now - t >= kDriveIdleMs) {    // (no firmware runs the core: this thread drives)
            std::lock_guard<std::mutex> g(smu_);
            drive(now, false);
            publish(true);
        }
    }
    rfm1cs_tcp_close(fd);
}

// ------------------------------------------------------------------ the driver (smu_ held) ---

void MoveLink::drive(uint32_t now, bool core)
{
    core_ = core;
    {
        std::unique_lock<std::mutex> l(mu_, std::try_to_lock);   // (busy: next time)
        if (l.owns_lock()) {
            for (In &m : in_)
                work_.push_back(std::move(m));
            in_.clear();
        }
    }
    for (In &m : work_) {
        // (an older try's socket: the session ignores it)
        const uint32_t att = m.attempt ? m.attempt : rfm1cs_session_attempt(s_);
        const uint8_t *b = m.bytes.empty() ? nullptr : m.bytes.data();
        const uint32_t n = (uint32_t)m.bytes.size();
        switch (m.kind) {
        case In::kRx:
            rfm1cs_session_rx(s_, now, att, b, n);
            break;
        case In::kOpened:
            rfm1cs_session_opened(s_, now, att);
            break;
        case In::kClosed:
            rfm1cs_session_closed(s_, now, att, 0);
            break;
        case In::kStart:
            fakeUp_ = false;
            host_d_.assign(m.bytes.begin(), m.bytes.end());
            shown_ = !m.shown.empty() ? m.shown : !host_d_.empty() ? host_d_ : "move.local";
            rfm1cs_session_start(s_, now);
            break;
        case In::kTestUp:
            fakeUp_ = true;
            shown_ = "the test Move";
            rfm1cs_session_start(s_, now);
            break;
        case In::kStop:
            rfm1cs_session_stop(s_);
            break;
        case In::kInit:
            rfm1cs_session_projects_init(s_, now, m.bits, m.slot, b, n);
            rfm1cs_session_redraw(s_, now);
            break;
        case In::kDir:
            rfm_move_project_dir(&s_->core, m.bits);
            rfm1cs_session_redraw(s_, now);
            break;
        case In::kInfo:
            rfm_move_project_info(&s_->core, m.slot, b, n);
            rfm1cs_session_redraw(s_, now);
            break;
        case In::kAnswer:                       // (not a valid project: an empty one there, as before)
            if (rfm1cs_session_project_answer(s_, now, m.slot, b, n) < 0)
                rfm1cs_session_project_answer(s_, now, m.slot, nullptr, 0);
            break;
        case In::kFlush:
            rfm1cs_session_flush(s_);
            break;
        case In::kFwList: {                     // (the old array freed after the core has the new one)
            std::shared_ptr<FwList> old = std::move(fwList_);
            fwList_ = std::move(m.fw);
            rfm_move_fw_list(&s_->core, fwList_->arr.empty() ? nullptr : fwList_->arr.data(), (int)fwList_->arr.size());
            old.reset();
            rfm1cs_session_redraw(s_, now);
            break;
        }
        case In::kFwInstalled:                  // (the string lives with the list: the core keeps the pointer)
            if (fwList_) {
                for (size_t i = 0; i < fwList_->src.size(); i++) {
                    if (fwList_->src[i].id != m.shown)
                        continue;
                    const char *v = nullptr;
                    if (m.has) {
                        fwList_->installed.emplace_back(m.bytes.begin(), m.bytes.end());
                        v = fwList_->installed.back().c_str();
                    }
                    rfm_move_fw_installed(&s_->core, (int)i, v);
                    rfm1cs_session_redraw(s_, now);
                    break;
                }
            }
            break;
        case In::kFwSlot: {                     // (one slot: ids past 0 are ignored)
            const char *ids[RFM_MOVE_FW_SLOTS] = {m.shown.empty() ? nullptr : m.shown.c_str(), nullptr, nullptr};
            rfm_move_fw_slots(&s_->core, ids);
            if (m.shown.empty()) {              // (nothing plays: no button names; a load gives them again)
                const char *none[RFM_MOVE_NBTN] = {};
                rfm_move_set_button_names(&s_->core, none);
                namesGen_ = 0;
            }
            rfm1cs_session_redraw(s_, now);
            break;
        }
        case In::kFwProgress:
            rfm_move_fw_progress(&s_->core, m.shown.c_str(), m.slot);
            rfm1cs_session_redraw(s_, now);
            break;
        }
    }
    work_.clear();
    rfm1cs_session_tick(s_, now);
    if (fakeUp_)
        testFake(now);
}

// the driver's results out: the socket's orders and bytes, the requests, the status
bool MoveLink::publish(bool wait)
{
    std::unique_lock<std::mutex> l(mu_, std::defer_lock);
    if (wait)
        l.lock();
    else if (!l.try_lock())
        return false;                           // (kept for the next pass)
    const bool wake = closeLocal_ || !txLocal_.empty();
    if (closeLocal_) {                          // (an open is always a close first; a later close cancels it)
        txq_.clear();
        closeReq_ = true;
        openReq_ = openLocal_;
        if (openLocal_)
            openAttempt_ = attemptLocal_, openHost_ = host_d_;
        closeLocal_ = openLocal_ = false;
    }
    txq_.insert(txq_.end(), txLocal_.begin(), txLocal_.end());
    txLocal_.clear();
    while (!reqLocal_.empty()) {
        requests_.push_back(std::move(reqLocal_.front()));
        reqLocal_.pop_front();
    }
    if (!ledsLocal_.empty() && ledsOut_.size() < kMaxLeds)
        ledsOut_.insert(ledsOut_.end(), ledsLocal_.begin(), ledsLocal_.end());
    ledsLocal_.clear();
    if (screenLocal_)
        screenOut_ = screen_, screenDue_ = true, screenLocal_ = false;
    const rfm1cs_status_t &s = *rfm1cs_session_status(s_);
    st_.state = s.state;
    st_.text = text_;
    st_.tone = tone_;
    st_.toast = toast_;
    st_.attempt = rfm1cs_session_attempt(s_);
    st_.name = s.name;
    st_.via = s.via;
    st_.battery = s.battery;
    st_.mode = s.mode;
    st_.project = s.project;
    st_.playing = s.playing != 0;
    st_.following = s.following != 0;
    st_.bpm10 = s.bpm10;
    st_.inputs = s.inputs;
    st_.leds = s.led_writes;
    st_.screens = s.screens;
    st_.connects = s.connects;
    l.unlock();
    if (wake)
        cv_.notify_all();
    return true;
}

void MoveLink::onEvent(void *ctx, const rfm1cs_event_t *ev) { static_cast<MoveLink *>(ctx)->event(*ev); }

void MoveLink::event(const rfm1cs_event_t &ev)
{
    const rfm1cs_status_t &st = *rfm1cs_session_status(s_);
    switch (ev.type) {
    case RFM1CS_EV_SEND:
        if (!fakeUp_) {
            txLocal_.insert(txLocal_.end(), ev.data, ev.data + ev.len);
            return;
        }
        // (tests) the fake Move: HELLO answered; the LEDs and the screen kept for the test to take
        if (ev.len >= 3) {
            const uint8_t type = ev.data[2], *p = ev.data + 3;
            const uint32_t n = ev.len - 3;
            if (type == MR_H_HELLO)
                fakeHello_ = true;
            else if (type == MR_H_LED_RGB)
                for (uint32_t i = 0; i + 5 <= n; i += 5)
                    ledsLocal_.push_back(rfm_move_led_t{1, (uint8_t)(p[i] != 0), p[i + 1], p[i + 2], p[i + 3], p[i + 4]});
            else if (type == MR_H_LED)
                for (uint32_t i = 0; i + 3 <= n; i += 3)
                    ledsLocal_.push_back(rfm_move_led_t{0, (uint8_t)((p[i] & 0xF0) == 0xB0), p[i + 1], p[i + 2], 0, 0});
            else if (type == MR_H_DISPLAY && n == MR_SCREEN_BYTES)
                std::memcpy(screen_.data(), p, MR_SCREEN_BYTES), screenLocal_ = true;
        }
        return;
    case RFM1CS_EV_OPEN:
        attemptLocal_ = ev.attempt;
        if (fakeUp_)
            fakeOpen_ = true;
        else
            closeLocal_ = openLocal_ = true;
        txLocal_.clear();
        return;
    case RFM1CS_EV_CLOSE:
        fakeOpen_ = fakeHello_ = false;
        if (!fakeUp_)
            closeLocal_ = true, openLocal_ = false;
        txLocal_.clear();
        return;
    case RFM1CS_EV_ACTION:
        action(ev);
        return;
    case RFM1CS_EV_LOST:                        // (the toast for a live link; the line follows with the STATUS)
        if (ev.was_live) {
            char t[200];
            const rfm1cs_words_t w = {shown_.c_str(), nullptr, "TCP", "app"};
            rfm1cs_lost_text(ev.reason, 1, &st, &w, t, sizeof t);
            toast_ = t;
        }
        return;
    case RFM1CS_EV_STATUS:                      // (the line names the mode: made again on every change)
        words();
        return;
    default:                                    // (RELEASED: no timed MIDI waits here, it went out at its ms)
        return;
    }
}

// the status line and its tone: rfm1cs-abletonmove's words (the web player's and iOS's), with the address as shown
void MoveLink::words()
{
    char t[256];
    const rfm1cs_words_t w = {shown_.c_str(), nullptr, "TCP", "app"};
    tone_ = rfm1cs_status_text(rfm1cs_session_status(s_), &w, t, sizeof t);
    text_ = t;
}

void MoveLink::action(const rfm1cs_event_t &ev)
{
    const rfm_move_act_t &a = *ev.act;
    Pending p{};
    rfm_event_t &e = p.e;
    switch (a.kind) {
    case RFM_MOVE_ACT_KEY: e.type = RFM_EV_KEY, e.index = a.index, e.value = a.down, e.vel = a.down ? a.vel : 0; break;
    case RFM_MOVE_ACT_PRESS: e.type = RFM_EV_PRESS, e.index = a.index, e.value = a.value; break;
    case RFM_MOVE_ACT_BTN: e.type = RFM_EV_BUTTON, e.index = a.index, e.value = a.down; break;
    case RFM_MOVE_ACT_TURN: e.type = RFM_EV_ENC, e.index = a.index, e.value = a.value; break;
    case RFM_MOVE_ACT_MASTER: e.type = RFM_EV_MASTER, e.value = a.value; break;
    case RFM_MOVE_ACT_MIDI:
        if (!a.n)
            return;
        if (core_ && target_) {
            target_->moveMidi(a.bytes, a.n);
            seqMidi_.fetch_add(1, std::memory_order_relaxed);
            return;
        }
        p.midi = true, p.n = a.n > 3 ? 3 : a.n;
        std::memcpy(p.b, a.bytes, p.n);
        break;
    case RFM_MOVE_ACT_SETTINGS: {
        Request r;
        r.kind = a.kind;
        r.data.assign(ev.data, ev.data + ev.len);
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
        r.kind = a.kind, r.slot = a.index, r.value = a.value;
        if (a.kind == RFM_MOVE_ACT_PROJECT_SAVE && ev.data)
            r.data.assign(ev.data, ev.data + ev.len);
        request(std::move(r));
        return;
    }
    case RFM_MOVE_ACT_FW_LOAD:                  // the firmware browser (one slot: FW_ACTIVE, FX_*, MIX, ROUTE never
    case RFM_MOVE_ACT_FW_UNLOAD:                // come): for the app, the id in data
    case RFM_MOVE_ACT_FW_DOWNLOAD:
    case RFM_MOVE_ACT_FW_LEVEL:
    case RFM_MOVE_ACT_FW_LIBRARY: {
        Request r;
        r.kind = a.kind, r.slot = a.slot, r.value = a.value;
        if (a.kind == RFM_MOVE_ACT_FW_LIBRARY) {
            if (ev.data && ev.len)
                r.data.assign(ev.data, ev.data + ev.len);
        } else if (a.data && a.len) {
            r.data.assign(a.data, a.data + a.len);
        }
        request(std::move(r));
        return;
    }
    case RFM_MOVE_ACT_CLICK:                    // the metronome and Sample's loop: at this millisecond's place in
    case RFM_MOVE_ACT_AUDIO_REC:                // the engine's ring (coreAudio); the take's bars and tempo with it
        if (core_)
            mark(a.kind, a.value, a.index, ev.bpm10);
        return;
    default:
        return;
    }
    if (core_ && target_) {
        target_->moveEvent(e);
        return;
    }
    if (pending_.size() >= kMaxPending)         // (the core thread away a long time: the oldest go)
        pending_.pop_front();
    pending_.push_back(p);
}

// (tests) the fake Move: the socket open at once, its HELLO when asked, a STATUS every 500 ms while connected
void MoveLink::testFake(uint32_t now)
{
    for (int guard = 0; guard < 4 && (fakeOpen_ || fakeHello_); guard++) {
        if (fakeOpen_) {
            fakeOpen_ = false;
            rfm1cs_session_opened(s_, now, rfm1cs_session_attempt(s_));
        }
        if (fakeHello_) {
            fakeHello_ = false;
            uint8_t h[46] = {'M', 'O', 'V', 'R', MR_PROTO, 0, MR_CAP_RGB, MR_CAP_SIMULATOR >> 8};   // (proto16 caps16 ..)
            h[16] = MR_VIA_LOCAL;
            std::memcpy(h + 22, "Test", 4);
            const std::vector<uint8_t> m = framed(MR_D_HELLO, h, sizeof h);
            rfm1cs_session_rx(s_, now, rfm1cs_session_attempt(s_), m.data(), (uint32_t)m.size());
            fakeStatusAt_ = now;
        }
    }
    if (rfm1cs_session_status(s_)->state == RFM1CS_CONNECTED && (int32_t)(now - fakeStatusAt_) >= 0) {
        fakeStatusAt_ = now + 500;
        uint8_t st[MR_STATUS_LEN] = {};
        st[4] = 80;                             // (battery)
        const std::vector<uint8_t> m = framed(MR_D_STATUS, st, sizeof st);
        rfm1cs_session_rx(s_, now, rfm1cs_session_attempt(s_), m.data(), (uint32_t)m.size());
    }
}

// ------------------------------------------------------------------ the core thread ---

void MoveLink::coreTick(uint32_t ms, MoveTarget &t, const uint8_t levels[RFM_MOVE_NLEVELS], uint8_t playGreen,
                        int master, const uint16_t *lcdBE, uint32_t lcdWrites)
{
    target_ = &t;
    const uint32_t now = wallMs();
    lastTickMs_.store(now | 1, std::memory_order_relaxed);
    std::lock_guard<std::mutex> g(smu_);       // (only the network thread's idle driving can hold it)
    core_ = true;
    for (const Pending &p : pending_) {         // what came while the network thread drove
        if (p.midi) {
            t.moveMidi(p.b, p.n);
            seqMidi_.fetch_add(1, std::memory_order_relaxed);
        } else {
            t.moveEvent(p.e);
        }
    }
    pending_.clear();
    if (const uint32_t lead = t.moveLeadUs(); lead != leadUs_) {   // (recordings where they were heard)
        leadUs_ = lead;
        rfm_move_seq_set_lead_us(&s_->core, lead);
    }
    if (master != lastMaster_) {
        lastMaster_ = master;
        rfm_move_set_master(&s_->core, master);
        rfm1cs_session_redraw(s_, now);
    }
    drive(now, true);                           // the Move's bytes, the app's calls, the timers
    // (0.5.6) the firmware's own button names for the Move's popups: on a load (a new core) and a setting change
    {
        std::array<std::string, RFM_MOVE_NBTN> nm;
        if (t.moveButtonNames(namesGen_, nm)) {
            const char *p[RFM_MOVE_NBTN];
            for (int b = 0; b < RFM_MOVE_NBTN; b++)
                p[b] = nm[(size_t)b].empty() ? nullptr : nm[(size_t)b].c_str();
            rfm_move_set_button_names(&s_->core, p);
            rfm1cs_session_redraw(s_, now);
        }
    }
    // the sequencer and the popups' timers by the device clock (us)
    const uint32_t dt = started_ ? ms - lastMs_ : 0;
    lastMs_ = ms, started_ = true;
    if (dt > 0 && dt < 1000)
        rfm1cs_session_advance(s_, now, dt * 1000u, 1000000u);
    // the FM-1's LEDs (PLAY's green as its level), only when they changed
    std::array<uint8_t, RFM_MOVE_NLEVELS + 1> pn;
    std::memcpy(pn.data(), levels, RFM_MOVE_NLEVELS);
    pn[RFM_MOVE_NLEVELS] = playGreen;
    if (!panelOk_ || pn != lastPanel_) {
        lastPanel_ = pn, panelOk_ = true;
        rfm1cs_session_panel(s_, levels, playGreen);
    }
    // the FM-1's screen: a new frame, byte-swapped (big-endian as the LCD is sent), at most every 33 ms
    if (lcdWrites != lcdSeen_)
        lcdSeen_ = lcdWrites, frameDue_ = true;
    if (frameDue_ && lcdBE && now - lastFrameMs_ >= kFrameMs) {
        frameDue_ = false;
        lastFrameMs_ = now;
        for (size_t i = 0; i < lcd_.size(); i++)
            lcd_[i] = (uint16_t)(lcdBE[i] >> 8 | lcdBE[i] << 8);
        rfm1cs_session_frame(s_, now, lcd_.data());
    }
    publish(false);
}

void MoveLink::coreMidiOut(const uint8_t *msg, int n)
{
    std::lock_guard<std::mutex> g(smu_);
    core_ = true;
    rfm1cs_session_midi_in(s_, wallMs(), msg, n);
}

// ------------------------------------------------------------------ Sample and the metronome ---
// The web player plays the click after its volume, never into a recording (js/audio.js click: 1760 Hz on a bar's first
// beat, else 1320 Hz, up in 2 ms, down over 40 ms), and cuts Sample's take at the bar lines' frames, into its
// recorder's Takes (js/rec-ui.js loopStart / loopStop), with the take's ~16 ms peaks for the Move's waveform. Here the
// marks carry the ring position of the millisecond they came in; coreAudio does the rest at the chunk that holds them.

void MoveLink::mark(int kind, int value, int index, int bpm10)
{
    const int64_t pos = target_ ? target_->moveAudioPos() : -1;
    if (pos < 0 || nmarks_ >= (int)marks_.size())
        return;                                 // (no audio clock: no click, no take)
    marks_[(size_t)nmarks_++] = Mark{pos, kind, value, index, bpm10};
}

void MoveLink::coreAudio(uint64_t pos, float *L, float *R, uint32_t n, uint32_t rate)
{
    if (!rate)
        return;
    std::lock_guard<std::mutex> g(smu_);       // (the requests and the core; as coreTick)
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
                sampleBpm10_ = mk.bpm10;        // counted, not taken from them)
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
    // the take's peaks onto the Move's screen (Sample's waveform); the requests out
    if (!peaks_.empty()) {
        if (rfm_move_sample_levels(&s_->core, peaks_.data(), (int)peaks_.size()) > 0)
            rfm1cs_session_redraw(s_, wallMs());
        peaks_.clear();
    }
    publish(false);
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

// ------------------------------------------------------------------ tests ---

void MoveLink::testInput(const uint8_t packet[4])
{
    const uint8_t rec[8] = {0, 0, 0, 0, packet[0], packet[1], packet[2], packet[3]};   // { frame32, usb_midi[4] }
    In m(In::kRx);
    m.bytes = framed(MR_D_INPUT, rec, sizeof rec);
    post(std::move(m));
}

void MoveLink::testConnected(bool up)
{
    if (up) {
        {
            std::lock_guard<std::mutex> g(mu_);
            ledsOut_.clear();
            screenDue_ = false;
        }
        post(In(In::kTestUp));
    } else {
        testClose(0);
    }
}

void MoveLink::testClose(uint32_t attempt)
{
    In m(In::kClosed);
    m.attempt = attempt;
    post(std::move(m));
}

void MoveLink::testDrop()
{
    {
        std::lock_guard<std::mutex> g(mu_);
        dropReq_ = true;
    }
    cv_.notify_all();
}

std::string MoveLink::testFw()
{
    std::lock_guard<std::mutex> g(smu_);
    const rfm_move_fw_state_t &f = s_->core.fw;
    char line[512];
    std::snprintf(line, sizeof line, "%d %d %d %s %d\n", rfm_move_fw_caps_get(&s_->core), f.active, f.track,
                  f.slot_id[0][0] ? f.slot_id[0] : "-", f.n);
    std::string out = line;
    for (int i = 0; i < f.n && f.list; i++) {
        const rfm_move_fw_t &e = f.list[i];
        std::snprintf(line, sizeof line, "%s|%s|%s|%d|%d|%d|%d\n", e.id, e.version,
                      e.installed && e.installed[0] ? e.installed : "-", e.cats, e.cat, e.virt, e.thumb ? 1 : 0);
        out += line;
    }
    return out;
}

int MoveLink::testTakeLeds(rfm_move_led_t *out, int max)
{
    std::lock_guard<std::mutex> g(mu_);
    const int n = (int)ledsOut_.size() < max ? (int)ledsOut_.size() : max;
    std::copy(ledsOut_.begin(), ledsOut_.begin() + n, out);
    ledsOut_.erase(ledsOut_.begin(), ledsOut_.begin() + n);
    return n;
}

bool MoveLink::testTakeScreen(uint8_t *out)
{
    std::lock_guard<std::mutex> g(mu_);
    if (!screenDue_)
        return false;
    std::memcpy(out, screenOut_.data(), MR_SCREEN_BYTES);
    screenDue_ = false;
    return true;
}

} // namespace rfm
