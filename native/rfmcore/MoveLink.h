// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 RemoteFM1 contributors
// The Ableton Move as the emulated FM-1's controller, on rfm1cs-abletonmove's session (vendor/rfm1cs-abletonmove,
// include/rfm1cs/session.h: Move Remote's protocol, HELLO, pings, silence, retries 1/2/4 s, LEDs on change, the screen
// at most every 33 ms; it owns the rfm_move core). This class is the host around it:
//   network thread  the socket (rfm1cs_tcp): opens and closes it when the session says, reads into a queue (each
//                   event with the attempt id of the EV_OPEN that opened it: the session ignores an older try's),
//                   writes what the session sent; SIGPIPE blocked
//   core thread     the engine's (RFMEngine's between hook, once a device millisecond) drives the session: the queued
//                   bytes into rfm1cs_session_rx, _tick, _advance by the device clock (the sequencer's notes into the
//                   firmware at that millisecond), the FM-1's LEDs (_panel), its screen (_frame), the firmware's MIDI
//                   clock (_midi_in); the core's actions into the panel, the firmware, the metronome and Sample
//   (no firmware)   while the engine's clock does not run, the network thread drives the session instead (the link
//                   stays up, the screen and LEDs follow input); the panel's and MIDI actions wait for the core thread
// One mutex (smu_) holds the session; only the driving thread takes it. The app's calls (start, stop, the projects)
// are queued for the driver. Android's own parts: the metronome mixed into the engine's ring and Sample's take cut from
// it (coreAudio), the recordings' lead, the clock and tempo in the status, the tests' fake Move (test*).
// The firmware browser (0.5.10's single-firmware host, rfm_move_fw_caps(0): one slot, no FX): the app's catalog as an
// array the driver owns (fwList: kept alive until the next list), the load / download answers (fwProgress, fwInstalled,
// fwSlot); the library blob given at start (the constructor). The core's FW_* actions come out as Requests.
// (0.5.11, the host guide's sections 20..24) the firmware's live labels from the engine (MoveTarget::moveLabels, with
// the button names), the readouts forgotten when slot 0 changes or a Move load ends (the engines give no readout
// record yet: none are sent, as on the web page), and the app's volume as slot 0's level (fwSetLevel).
#pragma once

#include <array>
#include <atomic>
#include <condition_variable>
#include <cstdint>
#include <deque>
#include <memory>
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
    // (0.5.6) the running firmware's own button names (FM1P_B_* order, "" none; all "" when the app's "Firmware's
    // button names" setting is off) if they changed since [gen] (then gen updated); false: unchanged
    virtual bool moveButtonNames(uint32_t &gen, std::array<std::string, RFM_MOVE_NBTN> &names) { (void)gen, (void)names; return false; }
    // (0.5.11) the running firmware's live labels (DYNAMIC-LABELS order; only with the "Firmware's button names"
    // setting on, as on the web page) if they changed since [seen] (then seen updated): given[i] 0 = not given, all 0 =
    // none (cleared); false: unchanged. The core's thread
    virtual bool moveLabels(uint64_t &seen, std::array<std::string, RFM_MOVE_LBL_N> &lbl,
                            std::array<uint8_t, RFM_MOVE_LBL_N> &given) { (void)seen, (void)lbl, (void)given; return false; }
    virtual ~MoveTarget() = default;
};

class MoveLink {
public:
    struct Status {
        int state = RFM1CS_OFF;     // RFM1CS_* (OFF, CONNECTING, CONNECTED, RETRYING, FAILED)
        std::string text, name;     // what to show (rfm1cs_status_text); the Move's name (its HELLO)
        int tone = 0;               // the text's RFM1CS_TONE_* (OFF, BUSY, ON, BAD)
        std::string toast;          // the last live link's loss (rfm1cs_lost_text), "" none
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
        uint32_t attempt = 0;       // the session's try (EV_OPEN's id; tests)
    };
    // a request for the app (projects, settings): kind is an RFM_MOVE_ACT_PROJECT_* or RFM_MOVE_ACT_SETTINGS
    struct Request {
        int kind = 0, slot = -1, value = 0;
        std::vector<uint8_t> data;  // PROJECT_SAVE's blob; SETTINGS: the settings image; FW_LIBRARY: the library;
                                    // FW_LOAD / _UNLOAD / _DOWNLOAD: the firmware's id
        uint32_t total = 0;         // kSampleStop: the take's frames (all in takeAudio's queue by then)
    };
    // Sample's take: kSampleStart (value: the rate), kSampleStop (slot: the bars, value: BPM x 10, total)
    enum { kSampleStart = 12, kSampleStop = 112 };

    // a catalog entry (rfm_move_fw_t's fields, owned): installed / desc empty with has* false = NULL; thumb 512 bytes
    // or empty (NULL: initials)
    struct FwEntry {
        std::string id, name, version, installed, desc;
        bool hasInstalled = false, hasDesc = false;
        std::vector<uint8_t> thumb;
        uint8_t cats = 0, cat = 255, virt = 0;
    };

    // settings: the stored image (NULL: defaults); library: the stored FW_LIBRARY blob (NULL: an empty library)
    MoveLink(const uint8_t *settings, uint32_t n, const uint8_t *library, uint32_t libN, std::string hostName);
    ~MoveLink();

    // ---- any thread (the app)
    // host: what rfm1cs_tcp_connect takes ("address", "address:port", "[v6]:port"; "": move.local); shown: the
    // address in the status texts ("": host)
    void start(const std::string &host, const std::string &shown = "");
    void stop();
    Status status() const;
    bool takeRequest(Request &r);
    // at start: which slots hold a project and the project to open (blob empty: a new one there)
    void projectsInit(uint32_t dirBits, int openSlot, const std::vector<uint8_t> &openBlob);
    void projectDir(uint32_t bits);
    void projectInfo(int slot, const std::vector<uint8_t> &blob);
    void projectAnswer(int slot, const std::vector<uint8_t> &blob);    // PROJECT_LOAD's answer (empty: none there)
    void flushProject();                                               // the autosave now (the app leaves)
    // the firmware browser (queued for the driver, applied on the core's thread)
    void fwList(std::vector<FwEntry> &&entries);                       // the whole catalog (rfm_move_fw_list)
    void fwInstalled(const std::string &id, const std::string *version);   // a download landed (NULL: removed)
    void fwSlot(const std::string &id);                                // what slot 0 holds now ("" empty)
    void fwProgress(const std::string &id, int pct);                   // 0..100, RFM_MOVE_FW_FAILED / _BUSY
    // (0.5.11) the app's volume as slot 0's level 0..127 (rfm_move_fw_set_level, as the web page's slider): the
    // library keeps it; its FW_LEVEL is not handed back to the app (no loop)
    void fwSetLevel(int slot, int level);

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
    void testClose(uint32_t attempt);        // a close from that try's socket (0: the current one; an older: ignored)
    // the core's firmware state: "caps active track slot0 n" then a line an entry "id|version|installed|cats|cat|virt|
    // thumb" (installed "-": NULL; thumb 1: one given)
    std::string testFw();
    // (0.5.11) the core's labels and readouts: "given readouts level0" then a line a label "1|text" (given) or "0|";
    // readouts: the knobs' kept ones and the one up on its own; testReadout puts one in (rfm_move_readout, the driver)
    std::string testLabels();
    void testReadout(int control, const std::string &title, const std::string &value);

private:
    // the catalog the core reads (rfm_move_fw_list's contract): the entries' strings, the array pointing into them,
    // and the strings fw_installed handed the core since; replaced whole by the next list
    struct FwList {
        std::vector<FwEntry> src;
        std::vector<rfm_move_fw_t> arr;
        std::deque<std::string> installed;
    };
    // from the app and the network thread to the driver; attempt: the socket's try (EV_OPEN's id), 0 the current one
    // (the tests' fake Move)
    struct In {
        enum Kind { kRx, kOpened, kClosed, kStart, kStop, kInit, kDir, kInfo, kAnswer, kFlush, kTestUp,
                    kFwList, kFwInstalled, kFwSlot, kFwProgress, kFwLevel, kTestReadout } kind;
        explicit In(Kind k) : kind(k) {}
        uint32_t attempt = 0, bits = 0;
        int slot = -1;
        std::vector<uint8_t> bytes;
        std::string shown;
        std::shared_ptr<FwList> fw;   // kFwList
        bool has = false;                    // kFwInstalled: a version (else NULL)
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
    void words();                            // text_ and tone_ from the session's status
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
    // driver -> network: open (attempt openAttempt_) / close the socket, bytes to write
    bool openReq_ = false, closeReq_ = false, dropReq_ = false;
    uint32_t openAttempt_ = 0;
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
    std::string host_d_ = "move.local", shown_ = "move.local";   // (the driver's copies: to open; for the texts)
    std::string text_ = "Not connected.", toast_;
    int tone_ = RFM1CS_TONE_OFF;
    bool openLocal_ = false, closeLocal_ = false;
    uint32_t attemptLocal_ = 0;              // the EV_OPEN's id the socket opens with
    std::vector<uint8_t> txLocal_;
    std::deque<Request> reqLocal_;
    std::vector<rfm_move_led_t> ledsLocal_;
    bool screenLocal_ = false;
    std::array<uint8_t, MR_SCREEN_BYTES> screen_{};
    std::deque<Pending> pending_;            // panel / MIDI actions for the core thread
    std::vector<In> work_;
    std::shared_ptr<FwList> fwList_;         // the catalog the core holds now
    std::string fwSlotId_;                   // what slot 0 was said to hold (a change: the readouts forgotten)
    bool hostLevel_ = false;                 // (fwSetLevel) its FW_LEVEL is the app's own: not handed back
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
    uint32_t namesGen_ = 0;                  // the firmware's button names last given (0: none yet / cleared)
    uint64_t lblSeen_ = 0;                   // (0.5.11) the live labels last given (moveLabels; 0: none yet / cleared)
    uint32_t lblAt_ = 0;                     // when they were last asked for
    std::array<std::string, RFM_MOVE_LBL_N> lbl_;
    std::array<uint8_t, RFM_MOVE_LBL_N> lblGiven_{};
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
