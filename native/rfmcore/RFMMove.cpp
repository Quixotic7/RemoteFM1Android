// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 RemoteFM1 contributors
// Ported from RemoteFM1IOS ad33e95 RFMKit/Sources/RFMCore/RFMMove.cpp; changes: the "(Android)" parts.
// rfmm_* (RFMMove.h) over MoveLink.

#include "RFMMove.h"

#include <algorithm>
#include <cstdio>
#include <cstdlib>
#include <cstring>

#include "MoveLink.h"

using rfm::MoveLink;

static MoveLink *L(rfmm_t *m) { return reinterpret_cast<MoveLink *>(m); }

static std::vector<uint8_t> bytes(const uint8_t *b, uint32_t n) { return b && n ? std::vector<uint8_t>(b, b + n) : std::vector<uint8_t>{}; }

rfmm_t *rfmm_new(const uint8_t *settings, uint32_t n, const char *host_name)
{
    return reinterpret_cast<rfmm_t *>(new MoveLink(settings, n, host_name ? host_name : "RemoteFM1"));
}

void rfmm_free(rfmm_t *m) { delete L(m); }
void rfmm_start(rfmm_t *m, const char *host) { L(m)->start(host ? host : ""); }
void rfmm_stop(rfmm_t *m) { L(m)->stop(); }

void rfmm_status(rfmm_t *m, rfmm_status_t *st)
{
    const MoveLink::Status s = L(m)->status();
    std::memset(st, 0, sizeof *st);
    st->state = s.state;
    std::snprintf(st->text, sizeof st->text, "%s", s.text.c_str());
    std::snprintf(st->name, sizeof st->name, "%s", s.name.c_str());
    st->via = s.via;
    st->battery = s.battery;
    st->mode = s.mode;
    st->project = s.project;
    st->playing = s.playing;
    st->ticking = s.ticking;
    st->inputs = s.inputs;
    st->leds = s.leds;
    st->screens = s.screens;
    st->connects = s.connects;
    st->seq_midi = s.seqMidi;
    st->following = s.following;                // (Android)
    st->bpm10 = s.bpm10;
    st->clicks = s.clicks;
}

int rfmm_take_request(rfmm_t *m, rfmm_request_t *r, uint8_t *data, uint32_t cap)
{
    MoveLink::Request q;
    if (!L(m)->takeRequest(q))
        return 0;
    r->kind = q.kind;
    r->slot = q.slot;
    r->value = q.value;
    r->total = q.total;                         // (Android)
    r->len = (uint32_t)std::min<size_t>(q.data.size(), data ? cap : 0);
    if (r->len)
        std::memcpy(data, q.data.data(), r->len);
    return 1;
}

void rfmm_projects_init(rfmm_t *m, uint32_t dir, int slot, const uint8_t *b, uint32_t n) { L(m)->projectsInit(dir, slot, bytes(b, n)); }
void rfmm_project_dir(rfmm_t *m, uint32_t bits) { L(m)->projectDir(bits); }
void rfmm_project_info(rfmm_t *m, int slot, const uint8_t *b, uint32_t n) { L(m)->projectInfo(slot, bytes(b, n)); }
void rfmm_project_answer(rfmm_t *m, int slot, const uint8_t *b, uint32_t n) { L(m)->projectAnswer(slot, bytes(b, n)); }
void rfmm_project_flush(rfmm_t *m) { L(m)->flushProject(); }
int rfmm_project_check(const uint8_t *b, uint32_t n) { return rfm_move_project_check(b, n); }

// the web's withColor (js/move-projects.js): byte 18, then the FNV-1a check over all but the last 4 bytes
void rfmm_project_with_color(uint8_t *b, uint32_t n, int color)
{
    if (!b || n < 23)
        return;
    b[18] = (uint8_t)color;
    uint32_t h = 2166136261u;
    for (uint32_t i = 0; i < n - 4; i++)
        h = (h ^ b[i]) * 16777619u;
    b[n - 4] = (uint8_t)h, b[n - 3] = (uint8_t)(h >> 8), b[n - 2] = (uint8_t)(h >> 16), b[n - 1] = (uint8_t)(h >> 24);
}

uint32_t rfmm_project_max(void) { return RFM_MOVE_PROJECT_MAX; }
uint32_t rfmm_settings_bytes(void) { return RFM_MOVE_SETTINGS_BYTES; }

int rfmm_test_empty_project(uint8_t *out, uint32_t cap)
{
    rfm_move_t *m = static_cast<rfm_move_t *>(std::calloc(1, sizeof(rfm_move_t)));
    rfm_move_settings_t st;
    rfm_move_settings_default(&st);
    rfm_move_init(m, &st, [](void *, const rfm_move_act_t *) {}, nullptr);
    rfm_move_project_load(m, 0, nullptr, 0);
    const int n = rfm_move_project_save(m, out, cap);
    std::free(m);
    return n;
}

// ---- (Android)
uint32_t rfmm_take_audio(rfmm_t *m, int16_t *stereo, uint32_t max) { return L(m)->takeAudio(stereo, max); }

void rfmm_test_input(rfmm_t *m, uint32_t packet)
{
    const uint8_t p[4] = {(uint8_t)packet, (uint8_t)(packet >> 8), (uint8_t)(packet >> 16), (uint8_t)(packet >> 24)};
    mr_event_t ev;
    mr_decode(p, 0, &ev);
    L(m)->testInput(ev);
}

void rfmm_test_connected(rfmm_t *m, int up) { L(m)->testConnected(up != 0); }

int rfmm_test_take_leds(rfmm_t *m, uint8_t *out6, int max)
{
    rfm_move_led_t b[256];
    const int n = L(m)->testTakeLeds(b, max < 256 ? max : 256);
    for (int i = 0; i < n; i++) {
        uint8_t *o = out6 + 6 * i;
        o[0] = b[i].rgb, o[1] = b[i].cc, o[2] = b[i].number, o[3] = b[i].r, o[4] = b[i].g, o[5] = b[i].b;
    }
    return n;
}

int rfmm_test_take_screen(rfmm_t *m, uint8_t *out1024) { return L(m)->testTakeScreen(out1024) ? 1 : 0; }
