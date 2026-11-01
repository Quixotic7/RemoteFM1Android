// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 RemoteFM1 contributors
// libremotefm1's Android back end over AMidi (rfm_android.h). Modelled on RemoteFM1's host/src/macos/host.c (links
// in slots, tagged by generation; candidates added to the session, gone ones removed), with the device list kept
// here for Kotlin instead of CoreMIDI's.
#include "rfm_android.h"

#include <amidi/AMidi.h>
#include <android/log.h>
#include <unistd.h>

#include <atomic>
#include <chrono>
#include <condition_variable>
#include <cstdarg>
#include <cstdio>
#include <cstring>
#include <mutex>
#include <string>
#include <vector>

namespace {

const char *transportName(int t)
{
    return t == RFMA_USB ? "USB" : t == RFMA_BLUETOOTH ? "Bluetooth" : t == RFMA_VIRTUAL ? "virtual" : "?";
}

// ---------------------------------------------------------------- the device list ----
struct Dev {
    int id = 0;
    AMidiDevice *dev = nullptr;
    std::string name;
    int transport = 0, inPort = 0, outPort = 0;
    bool gone = false;
    int users = 0;                                      // port pairs open on it (by session threads)
};

struct Registry {
    std::mutex mu;
    std::condition_variable cv;                         // users dropped
    std::vector<Dev> devs;
    int nextId = 1;
    std::atomic<uint32_t> gen{0};
};

JavaVM *gVm = nullptr;

Registry &reg()
{
    static Registry r;
    return r;
}

Dev *findLocked(int id)
{
    for (Dev &d : reg().devs)
        if (d.id == id)
            return &d;
    return nullptr;
}

std::string jsonEsc(const std::string &s)
{
    std::string r;
    for (const unsigned char c : s) {
        if (c == '"' || c == '\\') {
            r += '\\';
            r += (char)c;
        } else if (c < 0x20) {
            char b[8];
            std::snprintf(b, sizeof b, "\\u%04x", c);
            r += b;
        } else {
            r += (char)c;
        }
    }
    return r;
}

} // namespace

extern "C" void rfma_set_vm(JavaVM *vm) { gVm = vm; }

extern "C" int rfma_add_device(JNIEnv *env, jobject midi_device, const char *name, int transport, int in_port,
                               int out_port, const char *virtual_name)
{
    const std::string nm = name ? name : "";
    if (transport == RFMA_VIRTUAL && (!virtual_name || !*virtual_name || nm != virtual_name))
        return -1;                                      // (a virtual device is only ever the tests' loopback)
    if (transport != RFMA_USB && transport != RFMA_BLUETOOTH && transport != RFMA_VIRTUAL)
        return -1;
    AMidiDevice *dev = nullptr;
    if (AMidiDevice_fromJava(env, midi_device, &dev) != AMEDIA_OK || !dev)
        return -1;
    if (in_port < 0 || out_port < 0 || in_port >= AMidiDevice_getNumInputPorts(dev) ||
        out_port >= AMidiDevice_getNumOutputPorts(dev)) {
        AMidiDevice_release(dev);
        return -1;
    }
    Registry &r = reg();
    std::lock_guard<std::mutex> g(r.mu);
    Dev d;
    d.id = r.nextId++;
    d.dev = dev;
    d.name = nm;
    d.transport = transport;
    d.inPort = in_port;
    d.outPort = out_port;
    r.devs.push_back(d);
    r.gen.fetch_add(1, std::memory_order_acq_rel);
    __android_log_print(ANDROID_LOG_INFO, "RFMMidi", "device %d added: \"%s\" (%s), in %d out %d", d.id, nm.c_str(),
                        transportName(transport), in_port, out_port);
    return d.id;
}

extern "C" void rfma_remove_device(int id)
{
    Registry &r = reg();
    std::unique_lock<std::mutex> l(r.mu);
    Dev *d = findLocked(id);
    if (!d)
        return;
    d->gone = true;
    r.gen.fetch_add(1, std::memory_order_acq_rel);
    // the session threads drop their links on it at their next pump (every ~1 ms); a stopped link holds none
    r.cv.wait_for(l, std::chrono::milliseconds(1500), [&] {
        Dev *x = findLocked(id);
        return !x || x->users == 0;
    });
    d = findLocked(id);
    if (!d)
        return;
    if (d->users)
        __android_log_print(ANDROID_LOG_WARN, "RFMMidi", "device %d removed with %d links still open", id, d->users);
    AMidiDevice *dev = d->dev;
    const std::string nm = d->name;
    for (auto it = r.devs.begin(); it != r.devs.end(); ++it)
        if (it->id == id) {
            r.devs.erase(it);
            break;
        }
    l.unlock();
    AMidiDevice_release(dev);
    __android_log_print(ANDROID_LOG_INFO, "RFMMidi", "device %d removed: \"%s\"", id, nm.c_str());
}

extern "C" uint32_t rfma_generation(void) { return reg().gen.load(std::memory_order_acquire); }

extern "C" uint32_t rfma_devices_json(char *buf, uint32_t n)
{
    std::string s = "[";
    {
        Registry &r = reg();
        std::lock_guard<std::mutex> g(r.mu);
        for (const Dev &d : r.devs) {
            char b[96];
            std::snprintf(b, sizeof b, "\"transport\":%d,\"in\":%d,\"out\":%d,\"linked\":%d,\"gone\":%d}", d.transport,
                          d.inPort, d.outPort, d.users, d.gone ? 1 : 0);
            s += (s.size() > 1 ? ",{\"id\":" : "{\"id\":") + std::to_string(d.id) + ",\"name\":\"" + jsonEsc(d.name) +
                 "\"," + b;
        }
    }
    s += "]";
    if (buf && n) {
        std::snprintf(buf, n, "%s", s.c_str());
    }
    return (uint32_t)s.size();
}

// ---------------------------------------------------------------- one run's links ----
namespace {

struct Link {
    bool used = false;
    int devId = 0, sid = -1, transport = 0;
    uint16_t gen = 0;
    AMidiInputPort *in = nullptr;                       // we send to the device's input port
    AMidiOutputPort *out = nullptr;                     // and read its output port
    struct rfm_android *m = nullptr;
    char name[96] = {};
};

} // namespace

struct rfm_android {
    rfm_android_cfg_t cfg{};
    std::string vname;
    rfm_session_t *s = nullptr;
    Link l[RFM_MAX_LINKS];
    uint32_t now = 0, seenGen = 0;
    rfm_android_stats_t st{};
    bool attached = false;                              // this run attached the session thread to the JVM
    uint8_t buf[1024];                                  // one AMidi message (AMIDI_PACKET_SIZE)
};

namespace {

void mlog(rfm_android_t *m, const char *fmt, ...)
{
    char b[200];
    va_list ap;
    va_start(ap, fmt);
    std::vsnprintf(b, sizeof b, fmt, ap);
    va_end(ap);
    __android_log_print(ANDROID_LOG_INFO, "RFMMidi", "%s", b);
    if (m->cfg.log)
        m->cfg.log(m->cfg.log_ctx, b);
}

// the session's write: the whole message, or the link failed. AMidi cuts a long one into its 1015-byte packets
// (AMidiInputPort_send loops); a short count means the MIDI service's socket took less, retried for up to ~20 ms
int wLink(void *ctx, const uint8_t *p, uint32_t n)
{
    Link *l = static_cast<Link *>(ctx);
    rfm_android_t *m = l->m;
    uint32_t done = 0;
    for (int tries = 0; done < n && tries < 20; tries++) {
        const ssize_t k = AMidiInputPort_send(l->in, p + done, n - done);
        if (k < 0)
            return -1;
        done += (uint32_t)k;
        if (done < n) {
            m->st.tx_short++;
            usleep(1000);
        }
    }
    m->st.tx_bytes += done;
    m->st.tx_msgs++;
    if (n > m->st.tx_max)
        m->st.tx_max = n;
    return done == n ? 0 : -1;
}

void closePorts(Link &l)
{
    if (l.out)
        AMidiOutputPort_close(l.out);
    if (l.in)
        AMidiInputPort_close(l.in);
    l.in = nullptr;
    l.out = nullptr;
}

// (registry locked) one link let go: out of the session, its ports closed, the device's count down
void dropLocked(rfm_android_t *m, int i)
{
    Link &l = m->l[i];
    if (!l.used)
        return;
    mlog(m, "link gone: %s", l.name);
    if (m->s && l.sid >= 0)
        rfm_session_remove_link(m->s, l.sid, m->now);
    closePorts(l);
    if (Dev *d = findLocked(l.devId))
        d->users--;
    m->st.midi_links--;
    l.used = false;
    l.sid = -1;
    l.gen++;
    reg().cv.notify_all();
}

int freeSlot(const rfm_android_t *m)
{
    for (int i = 0; i < RFM_MAX_LINKS; i++)
        if (!m->l[i].used)
            return i;
    return -1;
}

} // namespace

extern "C" {

rfm_android_t *rfm_android_new(const rfm_android_cfg_t *cfg, char *err, size_t errn)
{
    auto *m = new (std::nothrow) rfm_android;
    if (!m) {
        std::snprintf(err, errn, "out of memory");
        return nullptr;
    }
    m->cfg = *cfg;
    m->vname = cfg->virtual_name ? cfg->virtual_name : "";
    m->cfg.virtual_name = m->vname.c_str();
    // (the session thread: attached for the run, detached in rfm_android_free on the same thread)
    JNIEnv *env = nullptr;
    if (gVm && gVm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6) == JNI_EDETACHED) {
        JavaVMAttachArgs a{JNI_VERSION_1_6, "RFMLink", nullptr};
        m->attached = gVm->AttachCurrentThread(&env, &a) == JNI_OK;
    }
    return m;
}

void rfm_android_rescan(rfm_android_t *m)
{
    if (!m->s)
        return;
    Registry &r = reg();
    std::lock_guard<std::mutex> g(r.mu);
    m->seenGen = r.gen.load(std::memory_order_acquire);
    // links whose device went away
    for (int i = 0; i < RFM_MAX_LINKS; i++) {
        if (!m->l[i].used)
            continue;
        const Dev *d = findLocked(m->l[i].devId);
        if (!d || d->gone)
            dropLocked(m, i);
    }
    // devices without a link of this run
    for (Dev &d : r.devs) {
        if (d.gone)
            continue;
        if (d.transport == RFMA_VIRTUAL && (m->vname.empty() || d.name != m->vname))
            continue;
        bool have = false;
        for (const Link &l : m->l)
            have = have || (l.used && l.devId == d.id);
        const int slot = have ? -1 : freeSlot(m);
        if (slot < 0)
            continue;
        Link &l = m->l[slot];
        l.m = m;
        l.devId = d.id;
        l.transport = d.transport;
        std::snprintf(l.name, sizeof l.name, "midi \"%s\" (%s)", d.name.c_str(), transportName(d.transport));
        if (AMidiInputPort_open(d.dev, d.inPort, &l.in) != AMEDIA_OK ||
            AMidiOutputPort_open(d.dev, d.outPort, &l.out) != AMEDIA_OK) {
            mlog(m, "cannot open the ports of %s", l.name);
            closePorts(l);
            continue;
        }
        l.sid = rfm_session_add_link(m->s, RFM_LINK_SX, wLink, &l, l.name);
        if (l.sid < 0) {
            closePorts(l);
            continue;
        }
        l.used = true;
        d.users++;
        m->st.midi_links++;
        mlog(m, "candidate %s", l.name);
    }
}

void rfm_android_attach(rfm_android_t *m, rfm_session_t *s)
{
    m->s = s;
    rfm_android_rescan(m);
}

void rfm_android_pump(rfm_android_t *m, uint32_t now)
{
    m->now = now;
    if (rfma_generation() != m->seenGen)
        rfm_android_rescan(m);
    for (int i = 0; i < RFM_MAX_LINKS; i++) {
        Link &l = m->l[i];
        if (!l.used)
            continue;
        for (int k = 0; k < 256; k++) {                 // (bounded: the session's poll gets its turn)
            int32_t op = 0;
            size_t got = 0;
            int64_t ts = 0;
            const ssize_t r = AMidiOutputPort_receive(l.out, &op, m->buf, sizeof m->buf, &got, &ts);
            if (r < 0) {
                m->st.rx_errors++;
                std::lock_guard<std::mutex> g(reg().mu);
                dropLocked(m, i);
                break;
            }
            if (r == 0)
                break;
            if (op != AMIDI_OPCODE_DATA || !got)
                continue;
            m->st.rx_bytes += (uint32_t)got;
            m->st.rx_msgs++;
            if (got > m->st.rx_max)
                m->st.rx_max = (uint32_t)got;
            if (m->s)
                rfm_session_rx(m->s, l.sid, m->buf, (uint32_t)got, now);
        }
    }
}

void rfm_android_wait(rfm_android_t *, uint32_t ms)
{
    if (ms)
        usleep(1000);
}

void rfm_android_stats(const rfm_android_t *m, rfm_android_stats_t *st) { *st = m->st; }

void rfm_android_link_name(const rfm_android_t *m, int sid, char *buf, size_t n)
{
    if (!n)
        return;
    buf[0] = 0;
    for (const Link &l : m->l)
        if (l.used && l.sid == sid && sid >= 0) {
            std::snprintf(buf, n, "%s", l.name);
            return;
        }
}

void rfm_android_free(rfm_android_t *m)
{
    if (!m)
        return;
    {
        std::lock_guard<std::mutex> g(reg().mu);
        m->s = nullptr;                                 // (the session may be gone already)
        for (int i = 0; i < RFM_MAX_LINKS; i++)
            dropLocked(m, i);
    }
    if (m->attached && gVm)
        gVm->DetachCurrentThread();
    delete m;
}

} // extern "C"
