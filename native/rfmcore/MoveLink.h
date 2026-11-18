// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 RemoteFM1 contributors
// The Ableton Move as the emulated FM-1's controller, on rfm1cs-abletonmove's session (vendor/rfm1cs-abletonmove,
// include/rfm1cs/session.h: Move Remote's protocol, HELLO, pings, silence, retries 1/2/4 s, LEDs on change, the screen
// at most every 33 ms; it owns the rfm_move core). This class is the host around it:
//   network thread  the socket (rfm1cs_tcp): opens and closes it when the session says, reads into a queue, writes
//                   what the session sent; SIGPIPE blocked
//   core thread     the engine's (RFMEngine's between hook, once a device millisecond) drives the session: the queued
//                   bytes into rfm1cs_session_rx, _tick, _advance by the device clock (the sequencer's notes into the
//                   firmware at that millisecond), the FM-1's LEDs (_panel), its screen (_frame), the firmware's MIDI
//                   clock (_midi_in); the core's actions into the panel, the firmware, the metronome and Sample
//   (no firmware)   while the engine's clock does not run, the network thread drives the session instead (the link
//                   stays up, the screen and LEDs follow input); the panel's and MIDI actions wait for the core thread
// One mutex (smu_) holds the session; only the driving thread takes it. The app's calls (start, stop, the projects)
// are queued for the driver. Android's own parts: the metronome mixed into the engine's ring and Sample's take cut from
// it (coreAudio), the recordings' lead, the clock and tempo in the status, the tests' fake Move (test*).
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

#include "rfm/panel.h"              // rfm_event_t
#include "rfm1cs/session.h"

namespace rfm {

// what the core thread does with the Move's actions (the engine)
struct MoveTarget {
    virtual void moveEvent(const rfm_event_t &e) = 0;                 // a key, button, encoder or MASTER, matrix ids
    virtual void moveMidi(const uint8_t *d, uint32_t n) = 0;          // one message into the firmware's MIDI in
    // the engine's ring position the device clock's millisecond plays at (-1: no audio clock), and how far the core
    // thread runs ahead of what is heard (rfm_move_seq_set_lead_us)
    virtual int64_t moveAudioPos() { return -1; }
    virtual uint32_t moveLeadUs() { return 0; }
    virtual ~MoveTarget() = default;
};

class MoveLink {
public:
    struct Status {
        int state = RFM1CS_OFF;     // RFM1CS_* (OFF, CONNECTING, CONNECTED, RETRYING, FAILED)
        std::string text, name;     // what to show; the Move's name (its HELLO)
        int via = -1;               // MR_VIA_* as the Move said it (0 Wi-Fi, 1 USB, 2 this computer), -1 none
        int battery = -1;           // as the Move reports it, -1 unknown
        int mode = 0;               // 0..3: FM-1, MIDI, Control, Sequencer
        int project = -1;           // the open project's slot
        bool playing = false;       // the sequencer
        bool ticking = false;       // the engine runs the core (a firmware playing)
        uint32_t inputs = 0, leds = 0, screens = 0, connects = 0, seqMidi = 0;
        bool following = false;     // the sequencer follows the firmware's MIDI clock
        int bpm10 = 0;              // its tempo, BPM x 10
        uint32_t clicks = 0;        // metronome clicks played
    };
    // a request for the app (projects, settings): kind is an RFM_MOVE_ACT_PROJECT_* or RFM_MOVE_ACT_SETTINGS
    struct Request {
        int kind = 0, slot = -1, value = 0;
        std::vector<uint8_t> data;  // PROJECT_SAVE's blob; SETTINGS: the settings image
        uint32_t total = 0;         // kSampleStop: the take's frames (all in takeAudio's queue by then)
    };
    // Sample's take: kSampleStart (value: the rate), kSampleStop (slot: the bars, value: BPM x 10, total)
    enum { kSampleStart = 12, kSampleStop = 112 };

    MoveLink(const uint8_t *settings, uint32_t n, std::string hostName);
    ~MoveLink();

    // ---- any thread (the app)
    void start(const std::string &host);     // "address" or "address:port" ("": move.local)
    void stop();
    Status status() const;
    bool takeRequest(Request &r);
    // at start: which slots hold a project and the project to open (blob empty: a new one there)
    void projectsInit(uint32_t dirBits, int openSlot, const std::vector<uint8_t> &openBlob);
    void projectDir(uint32_t bits);
    void projectInfo(int slot, const std::vector<uint8_t> &blob);
    void projectAnswer(int slot, const std::vector<uint8_t> &blob);    // PROJECT_LOAD's answer (empty: none there)
    void flushProject();                                               // the autosave now (the app leaves)

    // ---- the core thread (RFMEngine)
    void coreTick(uint32_t ms, MoveTarget &t, const uint8_t levels[RFM_MOVE_NLEVELS], uint8_t playGreen,
                  int master, const uint16_t *lcdBE, uint32_t lcdWrites);
    void coreMidiOut(const uint8_t *msg, int n);                       // the firmware's MIDI out (realtime, F2)
    // the end of a render chunk: n frames at ring position pos (L, R: the ring's), Sample's take cut out of it and
    // the clicks mixed into it at their positions
    void coreAudio(uint64_t pos, float *L, float *R, uint32_t n, uint32_t rate);

    // ---- the app: Sample's audio, interleaved stereo int16 (one reader)
    uint32_t takeAudio(int16_t *stereo, uint32_t max);
    // ---- tests: a fake Move in place of the socket (it answers HELLO and sends a STATUS every 500 ms), its input,
    // the link cut, and the LEDs and screen the session sent it
    void testInput(const uint8_t packet[4]);
    void testConnected(bool up);             // up: the fake Move connected (a fresh start); down: the link cut (lost)
    int testTakeLeds(rfm_move_led_t *out, int max);
    bool testTakeScreen(uint8_t *out);
    void testDrop();                         // the real socket cut under the session (lost: it tries again)

private:
    // from the app and the network thread to the driver; gen: the socket try it belongs to (-1: any)
    struct In {
        enum Kind { kRx, kOpened, kClosed, kStart, kStop, kInit, kDir, kInfo, kAnswer, kFlush, kTestUp } kind;
        explicit In(Kind k) : kind(k) {}
        int gen = -1, slot = -1, a = 0;
        uint32_t bits = 0;
        std::vector<uint8_t> bytes;
    };
    struct Pending {                         // a panel or MIDI action taken while the core thread was not driving
        bool midi;
        rfm_event_t e;
        uint8_t b[3];
        uint8_t n;
    };
    void post(In &&m);
    void netMain();
    static void onEvent(void *ctx, const rfm1cs_event_t *ev);
    void event(const rfm1cs_event_t &ev);
    void action(const rfm1cs_event_t &ev);
    void lostText(int why, bool wasLive);
    // the driver's pass (smu_ held): the queue into the session, the timers, the fake Move, then publish()
    void drive(uint32_t now, bool core);
    void testFake(uint32_t now);
    bool publish(bool wait);
    void request(Request &&r) { reqLocal_.push_back(std::move(r)); }
    void mark(int kind, int value, int index, int bpm10);

    // ---- shared (mu_)
    mutable std::mutex mu_;
    std::condition_variable cv_;
    Status st_;                              // the last published status
    std::string host_, hostName_;
    bool quit_ = false;
    std::deque<In> in_;
    std::deque<Request> requests_;           // core -> app
    // driver -> network: open (tryGen_) / close the socket, bytes to write
    bool openReq_ = false, closeReq_ = false, dropReq_ = false;
    int tryGen_ = 0;
    std::string openHost_;
    std::vector<uint8_t> txq_;
    std::vector<rfm_move_led_t> ledsOut_;    // (tests) what the session sent the fake Move
    std::array<uint8_t, MR_SCREEN_BYTES> screenOut_{};
    bool screenDue_ = false;
    std::thread net_;

    // ---- the session and the driver's (smu_)
    std::mutex smu_;
    rfm1cs_session_t *s_ = nullptr;
    bool core_ = false;                      // the core thread drives this pass
    int gen_ = 0;                            // the socket try the session is on
    std::string host_d_ = "move.local";      // (the driver's copy, for the texts)
    std::string text_;
    bool openLocal_ = false, closeLocal_ = false;
    std::vector<uint8_t> txLocal_;
    std::deque<Request> reqLocal_;
    std::vector<rfm_move_led_t> ledsLocal_;
    bool screenLocal_ = false;
    std::array<uint8_t, MR_SCREEN_BYTES> screen_{};
    std::deque<Pending> pending_;            // panel / MIDI actions for the core thread
    std::vector<In> work_;
    // the test fake Move
    bool fakeUp_ = false, fakeOpen_ = false, fakeHello_ = false;
    uint32_t fakeStatusAt_ = 0;

    // ---- the core thread's
    MoveTarget *target_ = nullptr;           // the engine (set by its first coreTick)
    std::atomic<uint32_t> lastTickMs_{0};    // (the wall clock's: the engine runs the core; 0 never)
    std::atomic<uint32_t> seqMidi_{0};
    uint32_t lastMs_ = 0, lcdSeen_ = ~0u, lastFrameMs_ = 0;
    bool started_ = false, frameDue_ = false;
    std::array<uint8_t, RFM_MOVE_NLEVELS + 1> lastPanel_{};
    bool panelOk_ = false;
    int lastMaster_ = -1;
    uint32_t leadUs_ = ~0u;
    std::vector<uint16_t> lcd_;              // the FM-1's screen in this CPU's byte order

    // ---- Sample and the metronome (the core thread's): marks at ring positions, the take's queue
    struct Mark { int64_t pos; int kind, value, index, bpm10; };
    std::array<Mark, 16> marks_{};
    int nmarks_ = 0;
    struct Click { uint64_t pos; uint32_t at; bool accent; };
    std::array<Click, 4> clicks_{};
    int nclicks_ = 0;
    bool sampling_ = false;
    int64_t sampleEnd_ = -1;
    int sampleBars_ = 0, sampleBpm10_ = 0;
    uint32_t sampleFrames_ = 0, winFrames_ = 1, winN_ = 0, winPeak_ = 0;
    std::vector<uint8_t> peaks_;
    static constexpr uint32_t kAudioFrames = 1u << 18;   // ~5.5 s of stereo at 48 kHz
    std::vector<int16_t> audio_;
    std::atomic<uint64_t> audioW_{0}, audioR_{0};
    std::atomic<uint32_t> clickCount_{0};
};

} // namespace rfm
