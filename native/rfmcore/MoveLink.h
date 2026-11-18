// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 RemoteFM1 contributors
// Ported from RemoteFM1IOS ad33e95 RFMKit/Sources/RFMCore/MoveLink.h; changes (Android, each marked "(Android)"): Sample's
// audio loop and the metronome (coreAudio: the take cut and the clicks mixed at their place in the engine's ring), the
// recordings' lead (MoveTarget.moveLeadUs), the sequencer's clock and tempo in the status, SIGPIPE held off the network
// thread, and the tests' entries without a network (test*).
// The Ableton Move as the emulated FM-1's controller (RemoteFM1's rfm_move core, host/src/move/README.md; the web's
// js/move-link.js is the model). Two halves on two threads:
//   network thread  libmoveremote's mr_client: the Move's INPUT, STATUS and BYE in; LEDs and the screen out; a PING a
//                   second; lost after 3 s silent; connects again (1, 2, 4 s) while wanted
//   core thread     the engine's (RFMEngine's between hook, once a device millisecond): owns rfm_move_t. The input is
//                   applied there, the sequencer advances there (rfm_move_seq_advance by the device clock), and its
//                   notes go into the firmware at that millisecond; the firmware's MIDI clock comes back (coreMidiOut)
// Between them only queues, so nothing touches rfm_move_t from two threads. The app's side (projects, settings)
// goes through two more: requests out (takeRequest), answers in (projectsInit, projectAnswer, projectInfo).
// The Android app ports this file (agreed 2026-11-18).
#pragma once

#include <array>
#include <atomic>
#include <condition_variable>
#include <cstdint>
#include <deque>
#include <mutex>
#include <string>
#include <thread>
#include <vector>

#include "rfm/move.h"
#include "rfm/panel.h"              // rfm_event_t

namespace rfm {

// what the core thread does with the Move's actions (the engine)
struct MoveTarget {
    virtual void moveEvent(const rfm_event_t &e) = 0;                 // a key, button, encoder or MASTER, matrix ids
    virtual void moveMidi(const uint8_t *d, uint32_t n) = 0;          // one message into the firmware's MIDI in
    // (Android) the engine's ring position the device clock's millisecond plays at (-1: no audio clock), and how far
    // the core thread runs ahead of what is heard (rfm_move_seq_set_lead_us)
    virtual int64_t moveAudioPos() { return -1; }
    virtual uint32_t moveLeadUs() { return 0; }
    virtual ~MoveTarget() = default;
};

class MoveLink {
public:
    enum State { kOff = 0, kConnecting, kConnected, kRetrying, kError };
    struct Status {
        State state = kOff;
        std::string text, name;     // what to show; the Move's name (its HELLO)
        int via = -1;               // MR_VIA_*
        int battery = -1;           // 0..100, -1 unknown
        int mode = 0;               // 0..3: FM-1, MIDI, Control, Sequencer
        int project = -1;           // the open project's slot
        bool playing = false;       // the sequencer
        bool ticking = false;       // the engine runs the core (a firmware playing)
        uint32_t inputs = 0, leds = 0, screens = 0, actions = 0, connects = 0, seqMidi = 0;
        bool following = false;     // (Android) the sequencer follows the firmware's MIDI clock
        int bpm10 = 0;              // (Android) its tempo, BPM x 10
        uint32_t clicks = 0;        // (Android) metronome clicks played
    };
    // a request for the app (projects, settings): kind is an RFM_MOVE_ACT_PROJECT_* or RFM_MOVE_ACT_SETTINGS
    struct Request {
        int kind = 0, slot = -1, value = 0;
        std::vector<uint8_t> data;  // PROJECT_SAVE's blob; SETTINGS: the settings image
        uint32_t total = 0;         // (Android) kSampleStop: the take's frames (all in takeAudio's queue by then)
    };
    // (Android) Sample's take: kSampleStart (value: the rate), kSampleStop (slot: the bars, value: BPM x 10, total)
    enum { kSampleStart = 12, kSampleStop = 112 };

    MoveLink(const uint8_t *settings, uint32_t n, std::string hostName);
    ~MoveLink();

    // ---- any thread (the app)
    void start(const std::string &host);     // "" : move.local (libmoveremote: USB first, then move.local)
    void stop();
    Status status() const;
    bool takeRequest(Request &r);
    // at start (once the store is read) and after the store changed: which slots hold a project, each one's blob for
    // the grid; then the project to open (blob empty: a new one there)
    void projectsInit(uint32_t dirBits, int openSlot, const std::vector<uint8_t> &openBlob);
    void projectDir(uint32_t bits);
    void projectInfo(int slot, const std::vector<uint8_t> &blob);
    void projectAnswer(int slot, const std::vector<uint8_t> &blob);    // PROJECT_LOAD's answer (empty: none there)
    void flushProject();                                               // the autosave now (the app leaves)
    static bool projectValid(const uint8_t *b, uint32_t n) { return rfm_move_project_check(b, n) == 0; }

    // ---- the core thread (RFMEngine)
    void coreTick(uint32_t ms, MoveTarget &t, const uint8_t levels[RFM_MOVE_NLEVELS], uint8_t playGreen,
                  int master, const uint16_t *lcdBE, uint32_t lcdWrites);
    void coreMidiOut(const uint8_t *msg, int n);                       // the firmware's MIDI out (realtime, F2)
    // (Android) the end of a render chunk: n frames at ring position pos (L, R: the ring's), Sample's take cut out of
    // it and the clicks mixed into it at their positions
    void coreAudio(uint64_t pos, float *L, float *R, uint32_t n, uint32_t rate);

    // ---- (Android) the app: Sample's audio, interleaved stereo int16 (one reader)
    uint32_t takeAudio(int16_t *stereo, uint32_t max);
    // ---- (Android) tests without a network: the network thread's posts and takes
    void testInput(const mr_event_t &ev);
    void testConnected(bool up);
    int testTakeLeds(rfm_move_led_t *out, int max);
    bool testTakeScreen(uint8_t *out);

private:
    struct CoreMsg {
        enum Kind { kInput, kConnected, kLost, kBattery, kInit, kDir, kInfo, kLoad, kFlush } kind;
        mr_event_t ev{};
        int slot = -1, a = 0, b = 0;
        uint32_t bits = 0;
        std::vector<uint8_t> blob;
    };
    void post(CoreMsg &&m);
    void netMain();
    void session(mr_client_t *c);
    void setStatus(State s, const std::string &text);
    static void onAct(void *ctx, const rfm_move_act_t *a);
    void act(const rfm_move_act_t &a);
    void coreMsg(CoreMsg &m, uint32_t ms);
    void request(Request &&r);

    // ---- shared
    mutable std::mutex mu_;                 // status, the queues below, want/host
    std::condition_variable cv_;
    Status st_;
    bool want_ = false, quit_ = false;
    std::string host_, hostName_;
    std::deque<CoreMsg> toCore_;            // network / app -> core
    std::vector<rfm_move_led_t> ledsOut_;   // core -> network
    std::array<uint8_t, MR_SCREEN_BYTES> screenOut_{};
    bool screenDue_ = false;
    std::deque<Request> requests_;          // core -> app
    std::thread net_;

    // ---- the core thread's
    rfm_move_t *m_ = nullptr;
    MoveTarget *target_ = nullptr;          // the engine (set by its first coreTick)
    std::atomic<uint64_t> lastTickUs_{0};   // (status: the engine runs the core)
    std::atomic<uint64_t> lastRxUs_{0};     // (the network thread's: anything from the Move)
    std::atomic<int> bye_{0};
    std::atomic<uint32_t> seqMidi_{0};    // MIDI messages into the firmware
    bool live_ = false;                     // a Move connected (the core advances, LEDs and screen go out)
    uint32_t lastMs_ = 0, lastScreenMs_ = 0, lastLcdWrites_ = ~0u, lastLedMs_ = 0;
    bool started_ = false, screenDirty_ = true;
    std::vector<CoreMsg> work_;             // toCore_ taken in one go
    std::vector<rfm_move_led_t> ledsPending_;
    std::array<uint8_t, MR_SCREEN_BYTES> screen_{}, screenSent_{};
    bool screenPending_ = false;
    std::vector<uint16_t> lcd_;             // the FM-1's screen in this CPU's byte order
    std::deque<Request> reqPending_;        // core-local until the lock is free

    // ---- (Android) Sample and the metronome (the core thread's): marks at ring positions, the take's queue
    struct Mark { int64_t pos; int kind, value, index; };
    std::array<Mark, 16> marks_{};
    int nmarks_ = 0;
    struct Click { uint64_t pos; uint32_t at; bool accent; };
    std::array<Click, 4> clicks_{};
    int nclicks_ = 0;
    bool sampling_ = false;
    int64_t sampleEnd_ = -1;
    int sampleBars_ = 0, sampleBpm10_ = 0;
    uint32_t sampleFrames_ = 0, winFrames_ = 1, winN_ = 0, winPeak_ = 0, leadUs_ = ~0u;
    std::vector<uint8_t> peaks_;
    static constexpr uint32_t kAudioFrames = 1u << 18;   // ~5.5 s of stereo at 48 kHz
    std::vector<int16_t> audio_;
    std::atomic<uint64_t> audioW_{0}, audioR_{0};
    std::atomic<uint32_t> clickCount_{0};
    void mark(int kind, int value, int index);
};

} // namespace rfm
