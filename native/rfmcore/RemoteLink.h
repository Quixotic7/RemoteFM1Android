// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 RemoteFM1 contributors
// Copied from RemoteFM1IOS 0d1a98b RFMKit/Sources/RFMCore/RemoteLink.h; changes: Android. The device back end is
// rfm_android (android/rfm_android.h: AMidi ports of the MidiDevices Kotlin opened) instead of rfm_mac; no unit lock
// (one app, one link), no gate / drop / lease and no shared fake (those were the AU's); Options.virtualName (tests:
// a virtual MIDI device of that exact name is a candidate too). Everything else as there.
// Copied there from FM1VST v0.6.2 plugin/RemoteLink.h (RemoteFM1 iOS: Status carries the unit's HELLO_R, the session
// state and more of rfm_session_stats_t; requestRescan(); the unit's TRS MIDI in, popMidi; TOUCH).
//
// THREADS
//   session thread   one per RemoteLink, started by the first start() and joined by the destructor. While the link
//                    is wanted it runs libremotefm1 (rfm_session over the Android MIDI back end, or over the
//                    in-process fake unit), and polls it every 1-2 ms; when not wanted it sleeps on a condition
//                    variable. start() / stop() only set a flag and return at once.
//   audio thread     (the engine's core thread, or RFMCore's pattern producer) pops panel events (popEvent) and
//                    publishes the LCD + LEDs (back() .. publish()). Both are lock-free, allocation-free and bounded.
//   control thread   start / stop / status / options (the app).
// Session -> audio: a single-producer single-consumer queue of rfm_event_t (matrix ids, as the unit sends them; a key's
// velocity in rfm_event_t.vel and RFM_EV_PRESS its pressure with TOUCH). Audio -> session: a triple buffer of
// RemoteSnapshot (the newest wins; the session thread gives it to rfm_session_set_frame / set_leds, which diff it
// against the unit's shadow and send what differs).
//
// STOPPING (stop(), the destructor): SESSION start=0 (the unit goes back to its waiting screen), up to kStopMs for its
// ACK, then the back end closed. The destructor joins the thread (bounded by kStopMs).
//
// THE FAKE UNIT (tests, the app's fake launch extra): Backend::Fake runs libremotefm1's fake (host/src/fake.c) in this
// process behind a loopback at the measured M0 rates; no MIDI device is touched. The fake belongs to the link (it
// outlives each run, as a unit stays plugged in); withFake() reaches it under the session thread's lock.
#pragma once
#include <array>
#include <atomic>
#include <chrono>
#include <condition_variable>
#include <cstdint>
#include <memory>
#include <mutex>
#include <string>
#include <thread>
#include <vector>

#include "rfm/codec.h"
#include "rfm/panel.h"
#include "rfm1.h"

struct rfm_session;
struct rfm_android;
struct rfm_fake;

// what the unit shows: the LCD exactly as emu_hal_t.lcd holds it (RGB565, each slot big-endian in memory) and the LEDs
struct RemoteSnapshot {
    uint16_t lcd[RFM_W * RFM_H];
    rfm_leds_t leds;
    uint32_t seq = 0;
};

class RemoteLink {
public:
    enum class Backend { Device, Fake };
    struct Options {
        Backend backend = Backend::Device;
        uint32_t fps = 20;                          // frames per second to the unit (the session's cap)
        std::string hostName = "RFM1 Android";      // HELLO's body (at most 16 bytes)
        // (tests) the fake unit as a RemoteFM1 0.2.2 unit over Bluetooth MIDI: SysEx only, its HELLO_R says
        // Bluetooth, HOLD in its caps, the wire at Bluetooth's rate (the session paces and reduces tiles for it)
        bool fakeBluetooth = false;
        // (tests) the fake unit as one before RemoteFM1 0.3.1: no RFM_CAP_TOUCH
        bool fakeNoTouch = false;
        // (tests, Backend::Device) a virtual MIDI device with exactly this name is a candidate too
        std::string virtualName;
    };
    explicit RemoteLink(const Options &o);
    ~RemoteLink();                                  // stop(), then the thread joined (<= kStopMs + a poll)
    RemoteLink(const RemoteLink &) = delete;
    RemoteLink &operator=(const RemoteLink &) = delete;

    // ---- control (any thread; they return at once)
    void start();
    void stop();
    bool wanted() const { return want_.load(); }
    Backend backend() const { return opt_.backend; }

    enum State { kOff = 0, kInUse, kSearching, kConnected, kLost, kError, kStopping };
    struct Status {
        State state = kOff;
        bool bulk = false;                          // kConnected: frames on the vendor bulk pipe (never on Android)
        bool ble = false;                           // kConnected: frames over Bluetooth MIDI (paced for it)
        float fps = 0.0f;                           // frames sent in the last second
        uint32_t sessions = 0, frames = 0, acks = 0, pending = 0, inFlight = 0, fullResends = 0, eventsIn = 0;
        uint32_t eventsDropped = 0, snapshotsTaken = 0, runs = 0;
        // Bluetooth (libremotefm1's rfm_session_stats_t): tiles sent reduced to `colors` colours, reduced tiles
        // sent again exactly once the screen was still, frames sent with HOLD (a screen change shown whole)
        uint32_t colors = 0, reduced = 0, refined = 0, held = 0;
        std::string text;                           // "connected (MIDI, 20 fps)" ..
        std::string lastLog;                        // the session's last log line
        // the session's own view: RFM_S_*, -1 none; the unit's HELLO_R once found (kept after)
        int sessionState = -1;
        bool haveHello = false;
        rfm_hello_t hello{};
        uint32_t budget = 0, wireBytes = 0, bodyBytes = 0, ackMsLast = 0, ackMsAvg = 0, ackMsMax = 0;
        uint32_t ackTimeouts = 0, discoveries = 0, hold = 0, wireCap = 0, frameMs = 0;
        // (RemoteFM1 0.3, RFM_CAP_MIDI_IN) the unit's TRS MIDI in: messages queued for the core, lost (queue full)
        uint32_t trsMidiIn = 0, trsMidiDropped = 0;
        // (RemoteFM1 0.3.1, RFM_CAP_TOUCH) TOUCH: what this link asks (RFM_TOUCH_F_*), what the unit's PANEL_R says
        // is in force, TOUCH messages sent
        uint32_t touchWanted = 0, touch = 0, touchSent = 0;
        // (Android) the back end: MIDI links open now, bytes in, bytes lost (nobody pumping), short writes
        uint32_t midiLinks = 0, rxBytes = 0, rxDropped = 0, txBytes = 0, txShort = 0;
        std::string frameLinkName;                  // the session's frame link ("midi \"FM-1\" (usb)")
    };
    Status status() const;
    static const char *stateName(State s);

    // ---- the audio thread
    // this link holds the unit (the back end running): panel events may come, frames are wanted
    bool engaged() const { return engaged_.load(std::memory_order_acquire); }
    // one panel event (rfm_event_t: RFM_EV_KEY / BUTTON / ENC / MASTER / PRESS, matrix ids), or kEvRelease: everything
    // the unit held is up (the session ended or the unit was lost)
    static constexpr uint8_t kEvRelease = 0x80;
    bool popEvent(rfm_event_t &e) { return q_.pop(e); }
    // (RFM_CAP_MIDI_IN) one MIDI message from the unit's TRS jack (MIDI_IN, split by status: a channel message or
    // real time F8 FA FB FC), in order, for the core's MIDI in. Popped where the panel events are
    struct MidiMsg {
        uint8_t len = 0;
        uint8_t data[3] = {0, 0, 0};
    };
    bool popMidi(MidiMsg &m) { return midiQ_.pop(m); }
    // true once after a run started or invalidateScreen(): publish now, whatever changed
    bool takeFullRequest() { return fullReq_.exchange(false, std::memory_order_acq_rel); }
    void requestFull() { fullReq_.store(true, std::memory_order_release); }   // (any thread: the next block publishes)
    RemoteSnapshot &back() { return tb_.back(); }
    void publish() { tb_.publish(); }
    static constexpr uint32_t kPeriodMs = 50;       // the audio thread publishes at most every kPeriodMs device ms

    // ---- any thread: TOUCH (velocity and pressure from the unit's keys, RFM_TOUCH_F_*; the default both). Sent after
    // each SESSION start's ACK to a unit with RFM_CAP_TOUCH, and at once in a session when it changes; 0: not asked
    // (the keys at velocity 100, no pressure, as before)
    void setKeyTouch(uint32_t flags) { touchWant_.store(flags & (RFM_TOUCH_F_VEL | RFM_TOUCH_F_PRESS)); }
    uint32_t keyTouch() const { return touchWant_.load(); }

    // ---- any thread: the MIDI devices changed (Kotlin added or removed one): the session thread rescans
    void requestRescan() { rescan_.store(true, std::memory_order_release); }

    // ---- any thread: the device's screen is unknown (a power cycle, a core switch): the next frame resends it all
    void invalidateScreen();

    // ---- tests: the fake unit, under the session thread's lock (Backend::Fake only; f is nullptr otherwise)
    template <typename F> void withFake(F &&fn)
    {
        std::lock_guard<std::mutex> g(fakeMu_);
        fn(fake_);
    }

    static constexpr uint32_t kStopMs = 600;        // the longest a stop waits for the unit's ACK

private:
    // single producer (the session thread), single consumer (the audio thread)
    template <uint32_t N, typename T = rfm_event_t> class EventQueue {
    public:
        bool push(const T &e)
        {
            const uint32_t w = w_.load(std::memory_order_relaxed);
            if (w - r_.load(std::memory_order_acquire) >= N)
                return false;
            buf_[w % N] = e;
            w_.store(w + 1, std::memory_order_release);
            return true;
        }
        bool pop(T &e)
        {
            const uint32_t r = r_.load(std::memory_order_relaxed);
            if (r == w_.load(std::memory_order_acquire))
                return false;
            e = buf_[r % N];
            r_.store(r + 1, std::memory_order_release);
            return true;
        }

    private:
        std::array<T, N> buf_{};
        std::atomic<uint32_t> w_{0}, r_{0};
    };
    // one writer (the audio thread), one reader (the session thread); the reader gets the newest published buffer
    class TripleBuffer {
    public:
        TripleBuffer()
        {
            for (auto &p : s_)
                p = std::make_unique<RemoteSnapshot>();
        }
        RemoteSnapshot &back() { return *s_[back_]; }
        void publish() { back_ = mid_.exchange(back_ | FRESH, std::memory_order_acq_rel) & 3; }
        const RemoteSnapshot *take()
        {
            if (!(mid_.load(std::memory_order_acquire) & FRESH))
                return nullptr;
            front_ = mid_.exchange(front_, std::memory_order_acq_rel) & 3;
            return s_[front_].get();
        }

    private:
        static constexpr int FRESH = 4;
        std::unique_ptr<RemoteSnapshot> s_[3];
        int back_ = 0, front_ = 2;
        std::atomic<int> mid_{1};
    };

    struct Loop {                                   // a loopback link to the fake unit
        RemoteLink *self;
        int kind, id;
    };

    void threadMain();
    void runOnce();                                 // one run: the back end, the session, the stop
    uint32_t now() const;
    void setStatus(State s, const std::string &text);
    void updateStatus(uint32_t now);
    void pumpFake(uint32_t now);
    void releasePanel();                            // kEvRelease, and the decoder forgets what it held

    static void cbEvent(void *ctx, const rfm_event_t *e);
    static void cbState(void *ctx, int st, const char *why);
    static void cbRescan(void *ctx);
    static void cbLog(void *ctx, const char *m);
    static void cbMidiIn(void *ctx, const uint8_t *msgs, uint32_t n);
    static int loopWrite(void *ctx, const uint8_t *p, uint32_t n);

    Options opt_;
    std::thread th_;
    std::mutex mu_;                                 // want_ / quit_ changes, the condition variable
    std::condition_variable cv_;
    std::atomic<bool> want_{false}, quit_{false}, engaged_{false}, fullReq_{false}, invalidate_{false}, rescan_{false};
    EventQueue<4096> q_;
    EventQueue<1024, MidiMsg> midiQ_;                // (TRS MIDI in) the session thread -> the panel's consumer
    TripleBuffer tb_;
    // the session thread's own
    rfm_session *s_ = nullptr;
    rfm_android *and_ = nullptr;
    rfm_fake *fake_ = nullptr;                      // Backend::Fake: for the link's lifetime
    std::mutex fakeMu_;
    Loop loops_[2] = {};
    std::atomic<uint32_t> touchWant_{RFM_TOUCH_F_VEL | RFM_TOUCH_F_PRESS};
    uint32_t touchApplied_ = 0;                     // (the session thread's) what the session was last given
    std::vector<uint8_t> loopBuf_;
    int lastState_ = -1;
    bool wasConnected_ = false;
    uint32_t statFrames_ = 0, statT_ = 0;
    std::chrono::steady_clock::time_point t0_;
    // the status (the session thread writes, anyone reads)
    mutable std::mutex stMu_;
    Status st_;
    std::atomic<uint32_t> trsMidiIn_{0}, trsMidiDropped_{0};
    std::atomic<uint32_t> eventsIn_{0}, eventsDropped_{0}, snapshotsTaken_{0}, fullResends_{0}, runs_{0};
};
