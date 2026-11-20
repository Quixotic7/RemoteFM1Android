// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 RemoteFM1 contributors
// Copied from RemoteFM1IOS 0d1a98b RFMKit/Sources/RFMCore/RFMNativeCore.cpp; changes: rfm::nowTicks instead of mach_absolute_time; the slot is the Android loader's (RFMSlots.h).
// NativeCore: the core backend (RFMCoreBackend.h) over a slot framework's fm1core_t, and the tests' stalling
// decorator.
#include "RFMCoreBackend.h"

#include "RFMRealtime.h"

#include <cstdio>
#include <cstring>

#include "RFMSlots.h"

namespace rfm {

int CoreBackend::param_find(const char *name) const
{
    if (!name)
        return -1;
    for (uint32_t i = 0; i < param_count(); i++)
        if (const CoreParam *p = param(i); p && p->name == name)
            return (int)i;
    return -1;
}

bool CoreBackend::cleanLabel(const char *s, size_t max, std::string *out)
{
    if (!s)
        return false;
    std::string o;
    for (size_t i = 0; i < max && s[i]; i++)
        if ((unsigned char)s[i] >= 0x20 && (unsigned char)s[i] < 0x7f)
            o += s[i];
    auto trim = [](std::string &t) {
        const size_t a = t.find_first_not_of(' ');
        t = a == std::string::npos ? std::string() : t.substr(a, t.find_last_not_of(' ') - a + 1);
    };
    trim(o);
    if (o.size() > 10)
        o.resize(10);
    trim(o);
    if (out)
        *out = o;
    return true;
}

namespace {

std::string str(const char *s) { return s ? s : ""; }

class NativeCore final : public CoreBackend {
public:
    explicit NativeCore(SlotLoader::Slot *s) : slot_(s), c_(s->core)
    {
        lblEpoch_ = reinterpret_cast<uint32_t (*)(void)>(s->symbol("fm1core_labels_epoch"));
        lblText_ = reinterpret_cast<const char *(*)(int32_t)>(s->symbol("fm1core_label"));
        d_.id = str(c_->id);
        d_.name = str(c_->name);
        d_.version = str(c_->version);
        d_.source_url = str(c_->source_url);
        d_.family = str(c_->family);
        d_.license = str(c_->license);
        d_.gated = str(c_->gated);
        d_.credits = str(fm1core_credits(c_));
        d_.abi = c_->abi_version;
        d_.flash_size = c_->flash ? c_->flash_size : 0;
        for (int i = 0; i < FM1P_NB; i++)
            d_.button_names[(size_t)i] = str(c_->button_names[i]);
        if (c_->keymap) {
            d_.has_keymap = true;
            std::memcpy(d_.keymap.data(), c_->keymap, d_.keymap.size());
        } else {
            d_.keymap.fill(-1);
        }
        for (uint32_t i = 0; c_->params && i < c_->nparams; i++) {
            const fm1param_t &p = c_->params[i];
            CoreParam q;
            q.name = str(p.name);
            q.unit = str(p.unit);
            q.min = p.min;
            q.max = p.max;
            q.def = p.def;
            q.flags = p.flags;
            q.readable = p.get != nullptr;
            q.writable = p.set != nullptr;
            params_.push_back(std::move(q));
        }
    }
    ~NativeCore() override { SlotLoader::shared().release(slot_); }   // (shutdown(), dlclose)

    const CoreDesc &info() const override { return d_; }
    int slot() const override { return slot_->n; }
    const char *kind() const override { return "native"; }

    fm1panel_t *panel() override { return c_->panel; }
    uint8_t *flash() override { return c_->flash ? c_->flash() : nullptr; }

    void options(const char *path, int no_flash, int save, int headless) override
    {
        c_->options(path, no_flash, save, headless);
    }
    bool has_boot_options() const override { return c_->boot_options != nullptr; }
    int boot_options(int fail, const char *reason, int stage) override
    {
        return c_->boot_options ? c_->boot_options(fail, reason, stage) : 1;
    }
    void init(int demo) override { c_->init(demo); }
    void tick(uint32_t ms) override { c_->tick(ms); }
    void frame() override { c_->frame(); }
    void idle() override { c_->idle(); }
    void audio(int16_t *stereo, uint32_t frames) override { c_->audio(stereo, frames); }
    int midi_in(uint32_t pkt) override { return c_->midi_in ? c_->midi_in(pkt) : 1; }
    int midi_out_take(uint32_t *pkt) override { return c_->midi_out_take ? c_->midi_out_take(pkt) : 0; }
    bool stats(uint32_t *shed, uint32_t *cpu) override
    {
        if (!c_->stats)
            return false;
        c_->stats(shed, cpu);
        return true;
    }
    void ui_info(char *buf, uint32_t n) override
    {
        if (c_->ui_info)
            c_->ui_info(buf, n);
        else if (buf && n)
            buf[0] = 0;
    }
    void dump() override
    {
        if (c_->dump)
            c_->dump();
    }
    void shutdown() override
    {
        if (c_->shutdown)
            c_->shutdown();
    }
    int halted() override { return c_->halted ? c_->halted() : 0; }
    int flash_stage(const uint8_t *b, uint32_t n) override { return c_->flash_stage ? c_->flash_stage(b, n) : 0; }
    uint32_t flash_dirty() override { return c_->flash_dirty ? c_->flash_dirty() : 0; }
    int flash_sync() override { return c_->flash_sync ? c_->flash_sync() : 1; }
    // (Android) live labels: the core's optional symbols in its own copy (the slot's handle), as wasm_glue.c's weak ones
    uint32_t labels_epoch() override { return lblEpoch_ && lblText_ ? lblEpoch_() : 0u; }
    bool label(int which, std::string *out) override
    {
        if (!lblEpoch_ || !lblText_ || which < 0 || which >= kLabels)
            return false;
        return cleanLabel(lblText_((int32_t)which), 64, out);
    }
    uint32_t param_epoch() override { return c_->param_epoch ? c_->param_epoch() : 0; }
    int32_t knob_target(int role) override { return c_->knob_target ? c_->knob_target(role) : -1; }

    uint32_t param_count() const override { return (uint32_t)params_.size(); }
    const CoreParam *param(uint32_t i) const override { return i < params_.size() ? &params_[i] : nullptr; }
    bool param_get(uint32_t i, int32_t *v) override
    {
        if (i >= params_.size() || !c_->params[i].get)
            return false;
        if (v)
            *v = c_->params[i].get();
        return true;
    }
    bool param_set(uint32_t i, int32_t v) override
    {
        if (i >= params_.size() || !c_->params[i].set)
            return false;
        c_->params[i].set(v);
        return true;
    }
    bool param_desc(uint32_t i, ParamDesc *o) override
    {
        if (i >= params_.size() || !o)
            return false;
        const fm1param_t &p = c_->params[i];
        std::snprintf(o->name, sizeof o->name, "%s", p.name ? p.name : "");
        o->min = p.min;
        o->max = p.max;
        o->def = p.def;
        o->flags = p.flags;
        return true;
    }
    int32_t param_target(uint32_t i) override
    {
        return i < params_.size() && c_->params[i].target ? c_->params[i].target() : -1;
    }
    bool param_text(uint32_t i, int32_t v, char *b, uint32_t n) override
    {
        if (i >= params_.size() || !b || !n)
            return false;
        const fm1param_t &p = c_->params[i];
        b[0] = 0;
        if (p.text)
            p.text(v, b, n);
        else if (p.names && v >= p.min && v <= p.max && p.names[v - p.min])
            std::snprintf(b, n, "%s", p.names[v - p.min]);
        else
            std::snprintf(b, n, "%d", (int)v);
        b[n - 1] = 0;
        return true;
    }

private:
    SlotLoader::Slot *slot_;
    const fm1core_t *c_;
    uint32_t (*lblEpoch_)(void) = nullptr;
    const char *(*lblText_)(int32_t) = nullptr;
    CoreDesc d_;
    std::vector<CoreParam> params_;
};

// forwards everything; frame() spins `us` longer
class StallingCore final : public CoreBackend {
public:
    StallingCore(std::unique_ptr<CoreBackend> in, uint32_t us) : in_(std::move(in))
    {
        ticks_ = (uint64_t)us * 1000u;
    }
    const CoreDesc &info() const override { return in_->info(); }
    int slot() const override { return in_->slot(); }
    const char *kind() const override { return in_->kind(); }
    fm1panel_t *panel() override { return in_->panel(); }
    uint8_t *flash() override { return in_->flash(); }
    void options(const char *p, int a, int b, int c) override { in_->options(p, a, b, c); }
    bool has_boot_options() const override { return in_->has_boot_options(); }
    int boot_options(int f, const char *r, int s) override { return in_->boot_options(f, r, s); }
    void init(int demo) override { in_->init(demo); }
    void tick(uint32_t ms) override { in_->tick(ms); }
    void frame() override
    {
        in_->frame();
        const uint64_t end = nowTicks() + ticks_;
        while (nowTicks() < end) {
        }
    }
    void idle() override { in_->idle(); }
    void audio(int16_t *s, uint32_t n) override { in_->audio(s, n); }
    int midi_in(uint32_t p) override { return in_->midi_in(p); }
    int midi_out_take(uint32_t *p) override { return in_->midi_out_take(p); }
    bool stats(uint32_t *s, uint32_t *c) override { return in_->stats(s, c); }
    void ui_info(char *b, uint32_t n) override { in_->ui_info(b, n); }
    void dump() override { in_->dump(); }
    void shutdown() override { in_->shutdown(); }
    int halted() override { return in_->halted(); }
    int flash_stage(const uint8_t *b, uint32_t n) override { return in_->flash_stage(b, n); }
    uint32_t flash_dirty() override { return in_->flash_dirty(); }
    int flash_sync() override { return in_->flash_sync(); }
    uint32_t param_epoch() override { return in_->param_epoch(); }
    int32_t knob_target(int r) override { return in_->knob_target(r); }
    uint32_t labels_epoch() override { return in_->labels_epoch(); }
    bool label(int w, std::string *out) override { return in_->label(w, out); }
    uint32_t param_count() const override { return in_->param_count(); }
    const CoreParam *param(uint32_t i) const override { return in_->param(i); }
    bool param_get(uint32_t i, int32_t *v) override { return in_->param_get(i, v); }
    bool param_set(uint32_t i, int32_t v) override { return in_->param_set(i, v); }
    bool param_desc(uint32_t i, ParamDesc *o) override { return in_->param_desc(i, o); }
    int32_t param_target(uint32_t i) override { return in_->param_target(i); }
    bool param_text(uint32_t i, int32_t v, char *b, uint32_t n) override { return in_->param_text(i, v, b, n); }

private:
    std::unique_ptr<CoreBackend> in_;
    uint64_t ticks_ = 0;
};

} // namespace

CoreFactory nativeCoreFactory(const std::string &id)
{
    return [id](std::string *err) -> std::unique_ptr<CoreBackend> {
        SlotLoader::Slot *s = SlotLoader::shared().acquire(id, err);
        if (!s)
            return nullptr;
        return std::make_unique<NativeCore>(s);
    };
}

std::unique_ptr<CoreBackend> stallingCore(std::unique_ptr<CoreBackend> inner, uint32_t stall_us)
{
    if (!inner)
        return nullptr;
    return std::make_unique<StallingCore>(std::move(inner), stall_us);
}

} // namespace rfm
