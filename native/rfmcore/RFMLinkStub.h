// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 RemoteFM1 contributors
// A0's stand-in for the FM-1 link: what RFMEngine.cpp uses of RemoteLink (RemoteFM1IOS RFMKit/Sources/RFMCore/
// RemoteLink.h) and of RFMCore.h's C face (the event types, rfmc_t), with nothing behind it. An engine made with
// rfme_new(NULL) never touches it. A1 replaces this file with the copied RemoteLink.h / RemoteLink.cpp / RFMCore.cpp
// over the Android MIDI back end; the names and signatures here are theirs, so RFMEngine.cpp stays as it is.
#pragma once
#include <cstdint>

#include "rfm/codec.h"
#include "rfm/panel.h"
#include "rfm1.h"

extern "C" {
// rfm_event_t types, and RemoteLink::kEvRelease (RFMCore.h)
enum { RFMC_EV_KEY = 1, RFMC_EV_BUTTON = 2, RFMC_EV_ENC = 3, RFMC_EV_MASTER = 4, RFMC_EV_PRESS = 5, RFMC_EV_RELEASE = 0x80 };
enum { RFMC_CAP_TOUCH = 1u << 10, RFMC_TOUCH_VEL = 1u << 0, RFMC_TOUCH_PRESS = 1u << 1 };
typedef struct rfmc rfmc_t;
}

// what the unit shows (RemoteLink.h)
struct RemoteSnapshot {
    uint16_t lcd[RFM_W * RFM_H];
    rfm_leds_t leds;
    uint32_t seq = 0;
};

class RemoteLink {
public:
    static constexpr uint8_t kEvRelease = 0x80;
    static constexpr uint32_t kPeriodMs = 50;
    struct MidiMsg {
        uint8_t len = 0;
        uint8_t data[3] = {0, 0, 0};
    };
    bool engaged() const { return false; }
    bool popEvent(rfm_event_t &) { return false; }
    bool popMidi(MidiMsg &) { return false; }
    bool takeFullRequest() { return false; }
    RemoteSnapshot &back() { return snap_; }
    void publish() {}
    void invalidateScreen() {}

private:
    RemoteSnapshot snap_{};
};
