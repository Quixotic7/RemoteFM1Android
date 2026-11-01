// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 RemoteFM1 contributors
// Copied from RemoteFM1IOS 0d1a98b RFMKit/Sources/RFMCore/include/RFMCore.h; changes: Android. No lease (rfml_*,
// rfmc_set_lease / claim / drop), no unit lock and no shared fake (the AU's); rfmc_cfg_t.virtual_name (tests: the
// loopback MIDI device); rfmc_status_t carries the Android back end's numbers (midi_links, rx/tx bytes, short writes)
// and the frame link's name; the umbrella includes only RFMEngine.h.
// RFMCore: the C face of RemoteLink for the app (rfm_jni.cpp). One rfmc_t is one RemoteLink plus a producer thread
// that takes the panel's events and publishes the LCD and LEDs. Without an attached engine (Pattern mode) the producer
// draws fm1remote's test pattern (--pattern), or a still screen, and shows the panel's held keys and buttons on the
// unit (their LEDs lit, a strip at the bottom of the screen). Every call returns at once and may come from any thread.
#ifndef RFMCORE_H
#define RFMCORE_H
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

enum { RFMC_DEVICE = 0, RFMC_FAKE = 1 };
// RemoteLink::State
enum { RFMC_OFF = 0, RFMC_IN_USE, RFMC_SEARCHING, RFMC_CONNECTED, RFMC_LOST, RFMC_ERROR, RFMC_STOPPING };
// rfm_session_state (RFM_S_*), -1 no session
enum { RFMC_S_DISCOVER = 0, RFMC_S_STARTING, RFMC_S_ACTIVE, RFMC_S_STOPPING, RFMC_S_STOPPED };
// rfm_event_t types, and RemoteLink::kEvRelease (everything the unit held is up)
enum { RFMC_EV_KEY = 1, RFMC_EV_BUTTON = 2, RFMC_EV_ENC = 3, RFMC_EV_MASTER = 4, RFMC_EV_PRESS = 5, RFMC_EV_RELEASE = 0x80 };
// TOUCH (RemoteFM1 0.3.1): HELLO_R caps bit, rfmc_set_key_touch flags, PANEL_R in force (rfmc_status_t.touch)
enum { RFMC_CAP_TOUCH = 1u << 10, RFMC_TOUCH_VEL = 1u << 0, RFMC_TOUCH_PRESS = 1u << 1 };
#define RFMC_W 240
#define RFMC_H 240

typedef struct {
    int backend;                // RFMC_DEVICE (Android MIDI, FM-1 candidates only) or RFMC_FAKE (in-process fake unit)
    int fake_ble;               // the fake as a 0.2.2 unit over Bluetooth MIDI
    uint32_t fps;               // the session's cap (20)
    const char *host_name;      // HELLO's body, at most 16 bytes
    int fake_no_touch;          // (tests, RFMC_FAKE) the fake as a unit before 0.3.1 (no RFMC_CAP_TOUCH)
    const char *virtual_name;   // (tests, RFMC_DEVICE) a virtual MIDI device of exactly this name is a candidate too
} rfmc_cfg_t;

typedef struct rfmc rfmc_t;
rfmc_t *rfmc_new(const rfmc_cfg_t *cfg);
void rfmc_free(rfmc_t *c);      // stops (SESSION 0, at most ~600 ms) and joins the threads

void rfmc_start(rfmc_t *c);
void rfmc_stop(rfmc_t *c);
void rfmc_set_pattern(rfmc_t *c, int on);
void rfmc_rescan(rfmc_t *c);    // the MIDI devices changed
// TOUCH: velocity and pressure from the FM-1's keys (RFMC_TOUCH_*; default both). Asked after each SESSION start's
// ACK from a unit with RFMC_CAP_TOUCH, and at once when changed; 0: not asked (velocity 100, no pressure, as before)
void rfmc_set_key_touch(rfmc_t *c, uint32_t flags);

typedef struct {
    int state;                  // RFMC_*
    int session_state;          // RFMC_S_*, -1
    int wanted, bulk, ble;
    float fps;
    uint32_t runs, sessions, frames, acks, pending, in_flight, events_in, events_dropped, snapshots;
    uint32_t colors, reduced, refined, held, hold, budget, wire_bytes, body_bytes, wire_cap, frame_ms;
    uint32_t ack_ms_last, ack_ms_avg, ack_ms_max, ack_timeouts, discoveries;
    char text[160];
    char log[200];
    // the unit's HELLO_R (have_hello: one was seen; kept after the session ends)
    int have_hello, hello_ble;
    char name[17], ver[9], fw_ver[9], identity[9];
    uint8_t proto, transports, usb_mode, session, max_in_flight;
    uint16_t caps, sxq, sx_body_max, bk_body_max, screen_w, screen_h;
    // the unit's TRS MIDI in (RemoteFM1 0.3: caps RFM_CAP_MIDI_IN, MIDI_IN in a session): messages queued for the
    // attached engine's core (its MIDI in, as the website's), lost (queue full)
    uint32_t trs_midi_in, trs_midi_dropped;
    // TOUCH: asked (RFMC_TOUCH_*), in force as the unit's PANEL_R says, TOUCH messages sent
    uint32_t touch_wanted, touch, touch_sent;
    // (Android) the back end: MIDI port pairs open, bytes in and out, writes the MIDI service took short; the link
    // the session's frames go on
    uint32_t midi_links, rx_bytes, tx_bytes, tx_short;
    char frame_link[96];
} rfmc_status_t;
void rfmc_status(rfmc_t *c, rfmc_status_t *st);

typedef struct {
    uint32_t seq;               // 1, 2, .. in arrival order
    uint8_t type, index, source; // RFMC_EV_*; key 0..26 / button 0..13 / encoder 0..6; 0 MIDI, 1 PANEL_R correction
    int16_t value;              // key / button 1 down 0 up; encoder detents; MASTER 0..1023; PRESS 0..127
    uint8_t vel;                // a key down: its velocity 1..127 (TOUCH), 0 none given
} rfmc_event_t;
// the events after seq `after` (the newest `max` of them, oldest first); returns how many were copied
uint32_t rfmc_events(rfmc_t *c, uint32_t after, rfmc_event_t *out, uint32_t max);
// the panel as the producer sees it: held keys and buttons (bits), MASTER (-1 unknown)
void rfmc_panel(rfmc_t *c, uint32_t *keys, uint32_t *buttons, int32_t *master);

// the frame being sent: if its number differs from `have`, copies it as RGBA8888 (240 x 240 x 4) into rgba;
// returns its number (0: none yet)
uint32_t rfmc_preview(rfmc_t *c, uint32_t have, uint8_t *rgba);

// ---- the fake unit (RFMC_FAKE; no-ops otherwise)
void rfmc_fake_key(rfmc_t *c, uint32_t k, int down);
void rfmc_fake_button(rfmc_t *c, uint32_t b, int down);
void rfmc_fake_enc(rfmc_t *c, uint32_t e, int32_t detents);
void rfmc_fake_master(rfmc_t *c, uint32_t adc);
// TOUCH: a key pressed at vel (sent as vel when the session asked for velocity, else 100); a held key's pressure 0..127
void rfmc_fake_key_vel(rfmc_t *c, uint32_t k, int down, uint32_t vel);
void rfmc_fake_press(rfmc_t *c, uint32_t k, uint32_t p);
// MIDI into the fake's TRS jack (whole messages with their status): MIDI_IN to the host while a session runs
void rfmc_fake_trs(rfmc_t *c, const uint8_t *msgs, uint32_t n);
typedef struct {
    int session;                // 0 none, 1 SysEx, 2 bulk
    uint32_t frames, acks, held_frames, hellos;
    uint32_t touch, touch_msgs; // TOUCH in force on the fake (RFMC_TOUCH_*), TOUCH messages it took
    uint8_t lit[11];            // the LEDs it shows lit (LEDS' lit plane, packed row bits per column)
} rfmc_fake_t;
int rfmc_fake_state(rfmc_t *c, rfmc_fake_t *out);   // 0: not the fake backend
// the fake's LED (lit plane) for note key k / matrix button b: 1 lit
int rfmc_fake_key_lit(rfmc_t *c, uint32_t k);
int rfmc_fake_button_lit(rfmc_t *c, uint32_t b);
// (tests) the LED of matrix id `id` in a lit plane (11 columns of packed row bits): note key k is 14 + k, button b is b
int rfmc_led_is(const uint8_t *lit, int id);

#ifdef __cplusplus
}
#endif

#include "RFMEngine.h"
#endif
