// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 RemoteFM1 contributors
// Copied from RemoteFM1IOS 0d1a98b RFMKit/Sources/RFMCore/RFMSlots.cpp; changes: Android. The bundle's slot
// frameworks (CoreFoundation, FM1CoreSlots.txt) are replaced by libfm1core_<id>.so files in one folder; each acquire
// loads its own copy (android_dlopen_ext with ANDROID_DLEXT_FORCE_LOAD), and whether a copy left memory on release
// is told by dladdr on its descriptor function instead of RTLD_NOLOAD (which would find another copy). open_core and
// the C face are as there.
// The core loader (RFMSlots.h) and its C face (rfms_*, include/RFMEngine.h). open_core is FM1VST v0.6.2's
// engine/cores.cpp open_core (the descriptor probe and the ABI check), with its messages shortened.
#include "RFMSlots.h"

#include <dirent.h>
#include <dlfcn.h>
#include <errno.h>
#include <sys/stat.h>
#if defined(__ANDROID__)
#include <android/dlext.h>
#endif

#include <algorithm>
#include <cstdio>
#include <cstring>
#include <map>

#include "RFMEngine.h"

namespace rfm {

static bool isFile(const std::string &p)
{
    struct stat st;
    return stat(p.c_str(), &st) == 0 && S_ISREG(st.st_mode);
}

// libfm1core_<id>.so -> id
static bool coreName(const std::string &entry, std::string &id)
{
    static const std::string pre = "libfm1core_", suf = ".so";
    if (entry.size() <= pre.size() + suf.size() || entry.compare(0, pre.size(), pre) != 0 ||
        entry.compare(entry.size() - suf.size(), suf.size(), suf) != 0)
        return false;
    id = entry.substr(pre.size(), entry.size() - pre.size() - suf.size());
    return !id.empty();
}

// a fresh copy of the image at path (its own globals), even when one is loaded already
static void *loadCopy(const std::string &path)
{
#if defined(__ANDROID__)
    android_dlextinfo x{};
    x.flags = ANDROID_DLEXT_FORCE_LOAD;
    return android_dlopen_ext(path.c_str(), RTLD_NOW | RTLD_LOCAL, &x);
#else
    return dlopen(path.c_str(), RTLD_NOW | RTLD_LOCAL);
#endif
}

static const fm1core_t *open_core(const std::string &path, void **handle, std::string *error)
{
    void *h = loadCopy(path);
    if (!h) {
        if (error) {
            const char *e = dlerror();
            *error = path + ": " + (e ? e : "dlopen failed");
        }
        return nullptr;
    }
    auto get = reinterpret_cast<fm1core_get_fn>(dlsym(h, FM1CORE_SYMBOL));
    if (!get) {
        if (error)
            *error = path + ": no " FM1CORE_SYMBOL " (not a core)";
        dlclose(h);
        return nullptr;
    }
    const fm1core_t *c = nullptr;   // this host's ABI, else an older one it still loads (ABI 5: no credits)
    for (uint32_t v = FM1CORE_ABI; v >= FM1CORE_ABI_MIN && !c; v--) {
        c = get(v);
        if (c && c->abi_version != v)
            c = nullptr;
    }
    if (!c) {
        uint32_t built = 0;
        for (uint32_t v = 1; v < 64 && !built; v++)
            if ((v < FM1CORE_ABI_MIN || v > FM1CORE_ABI) && get(v))
                built = v;
        if (error)
            *error = path + ": " + (built ? "a core for ABI " + std::to_string(built) : std::string("another ABI")) +
                     "; this host loads ABI " + std::to_string(FM1CORE_ABI_MIN) + ".." + std::to_string(FM1CORE_ABI);
        dlclose(h);
        return nullptr;
    }
    *handle = h;
    return c;
}

static std::string str(const char *s) { return s ? s : ""; }

// dlclose, and whether this copy's image really left (its descriptor function no longer mapped)
static bool unload(void *h)
{
    void *probe = dlsym(h, FM1CORE_SYMBOL);
    dlclose(h);
    Dl_info info{};
    return !probe || dladdr(probe, &info) == 0;
}

void *SlotLoader::Slot::symbol(const char *name) const { return handle && name ? dlsym(handle, name) : nullptr; }

SlotLoader &SlotLoader::shared()
{
    static SlotLoader l;
    return l;
}

using Found = std::map<std::string, std::vector<std::string>>;   // id -> its library

// the cores in d's listing; errno when d can't be listed (else 0)
static int listDir(const std::string &d, Found &found)
{
    DIR *dp = opendir(d.c_str());
    if (!dp)
        return errno ? errno : ENOENT;
    while (struct dirent *de = readdir(dp)) {
        std::string id;
        const std::string entry = de->d_name;
        if (!coreName(entry, id))
            continue;
        const std::string p = d + "/" + entry;
        if (isFile(p) && found.find(id) == found.end())
            found[id] = {p};
    }
    closedir(dp);
    return 0;
}

// the cores a list names (one libfm1core_<id>.so a line; # comments), looked for in d by path
static void fromList(const std::string &d, const std::string &list, Found &found)
{
    size_t i = 0;
    while (i < list.size()) {
        size_t e = list.find('\n', i);
        if (e == std::string::npos)
            e = list.size();
        std::string line = list.substr(i, e - i);
        i = e + 1;
        const size_t hash = line.find('#');
        if (hash != std::string::npos)
            line.erase(hash);
        const size_t a = line.find_first_not_of(" \t\r"), b = line.find_last_not_of(" \t\r");
        if (a == std::string::npos)
            continue;
        line = line.substr(a, b - a + 1);
        std::string id;
        if (coreName(line, id) && found.find(id) == found.end())
            found[id] = {d + "/" + line};
    }
}

int SlotLoader::scan(const std::string &dir)
{
    std::lock_guard<std::mutex> g(mu_);
    dirs_ = {dir};
    errors_.clear();
    Found found;
    const int e = listDir(dir, found);
    if (e)
        errors_ = dir + ": " + std::strerror(e);
    source_ = found.empty() ? "" : "dir listing";
    return probeLocked(found);
}

int SlotLoader::scanList(const std::string &dir, const std::string &list)
{
    std::lock_guard<std::mutex> g(mu_);
    dirs_ = {dir};
    errors_.clear();
    Found found;
    fromList(dir, list, found);
    source_ = "list file";
    return probeLocked(found);
}

int SlotLoader::probeLocked(const std::map<std::string, std::vector<std::string>> &found)
{
    std::vector<CoreSlotInfo> next;
    for (auto &kv : found) {
        CoreSlotInfo info;
        info.id = kv.first;
        info.paths = kv.second;
        const std::string &probe = info.paths[0];
        void *h = nullptr;
        std::string err;
        const fm1core_t *c = open_core(probe, &h, &err);   // (a copy of its own: nothing is initialised)
        if (!c) {
            errors_ += (errors_.empty() ? "" : "; ") + err;
            continue;
        }
        info.name = str(c->name);
        info.version = str(c->version);
        info.source_url = str(c->source_url);
        info.family = str(c->family);
        info.license = str(c->license);
        info.gated = str(c->gated);
        info.credits = str(fm1core_credits(c));
        info.abi = c->abi_version;
        info.flash_size = c->flash_size;
        if (str(c->id) != info.id)
            errors_ += (errors_.empty() ? "" : "; ") + probe + ": its descriptor says id " + str(c->id);
        dlclose(h);                                  // (a probe: nothing to shut down)
        next.push_back(info);
    }
    cores_ = next;
    return (int)cores_.size();
}

std::vector<CoreSlotInfo> SlotLoader::cores() const
{
    std::lock_guard<std::mutex> g(mu_);
    return cores_;
}

bool SlotLoader::find(const std::string &id, CoreSlotInfo &out) const
{
    std::lock_guard<std::mutex> g(mu_);
    for (const CoreSlotInfo &c : cores_)
        if (c.id == id) {
            out = c;
            return true;
        }
    return false;
}

// copies of c's library in v ("<path>#<copy>")
static int countIn(const std::vector<std::string> &v, const CoreSlotInfo &c)
{
    int n = 0;
    for (const std::string &k : v)
        for (const std::string &p : c.paths)
            if (k.compare(0, p.size() + 1, p + "#") == 0)
                n++;
    return n;
}

int SlotLoader::inUse(const std::string &id) const
{
    CoreSlotInfo c;
    if (!find(id, c))
        return 0;
    std::lock_guard<std::mutex> g(mu_);
    return countIn(held_, c);
}

int SlotLoader::retired(const std::string &id) const
{
    CoreSlotInfo c;
    if (!find(id, c))
        return 0;
    std::lock_guard<std::mutex> g(mu_);
    return countIn(retired_, c);
}

std::string SlotLoader::dirs() const
{
    std::lock_guard<std::mutex> g(mu_);
    std::string s;
    for (const std::string &d : dirs_)
        s += (s.empty() ? "" : "; ") + d;
    return s;
}

std::string SlotLoader::errors() const
{
    std::lock_guard<std::mutex> g(mu_);
    return errors_;
}

std::string SlotLoader::source() const
{
    std::lock_guard<std::mutex> g(mu_);
    return source_;
}

SlotLoader::Slot *SlotLoader::acquire(const std::string &id, std::string *err)
{
    std::lock_guard<std::mutex> g(mu_);
    const CoreSlotInfo *info = nullptr;
    for (const CoreSlotInfo &c : cores_)
        if (c.id == id)
            info = &c;
    if (!info) {
        if (err)
            *err = "no core \"" + id + "\" in " + (dirs_.empty() ? std::string("the app") : dirs_[0]);
        return nullptr;
    }
    const std::string &p = info->paths[0];
    void *h = nullptr;
    std::string e;
    const fm1core_t *c = open_core(p, &h, &e);
    if (!c) {
        if (err)
            *err = e;
        return nullptr;
    }
    auto *s = new Slot;
    s->id = id;
    s->path = p;
    s->n = 1;
    s->copy = ++copies_;
    s->handle = h;
    s->core = c;
    held_.push_back(p + "#" + std::to_string(s->copy));
    return s;
}

void SlotLoader::release(Slot *s)
{
    if (!s)
        return;
    if (s->core && s->core->shutdown)
        s->core->shutdown();
    const bool gone = s->handle ? unload(s->handle) : true;
    {
        std::lock_guard<std::mutex> g(mu_);
        const std::string key = s->path + "#" + std::to_string(s->copy);
        held_.erase(std::remove(held_.begin(), held_.end(), key), held_.end());
        if (!gone)
            retired_.push_back(key);
    }
    delete s;
}

} // namespace rfm

// ---- the C face
using rfm::CoreSlotInfo;
using rfm::SlotLoader;

static void put(char *dst, size_t n, const std::string &s)
{
    std::strncpy(dst, s.c_str(), n - 1);
    dst[n - 1] = 0;
}

static uint32_t putAll(char *buf, uint32_t n, const std::string &s)
{
    if (buf && n) {
        const size_t k = std::min<size_t>(n - 1, s.size());
        std::memcpy(buf, s.data(), k);
        buf[k] = 0;
    }
    return (uint32_t)s.size();
}

static void fill(const CoreSlotInfo &c, rfms_info_t *o)
{
    std::memset(o, 0, sizeof *o);
    put(o->id, sizeof o->id, c.id);
    put(o->name, sizeof o->name, c.name);
    put(o->version, sizeof o->version, c.version);
    put(o->family, sizeof o->family, c.family);
    put(o->license, sizeof o->license, c.license);
    put(o->gated, sizeof o->gated, c.gated);
    put(o->source_url, sizeof o->source_url, c.source_url);
    o->abi = c.abi;
    o->flash_size = c.flash_size;
    o->slots = c.paths.empty() ? 0 : 1;
    o->in_use = SlotLoader::shared().inUse(c.id);
    o->retired = SlotLoader::shared().retired(c.id);
}

extern "C" {

int rfms_scan(const char *dir) { return SlotLoader::shared().scan(dir ? dir : ""); }

int rfms_scan_list(const char *dir, const char *list)
{
    return SlotLoader::shared().scanList(dir ? dir : "", list ? list : "");
}

int rfms_count(void) { return (int)SlotLoader::shared().cores().size(); }

int rfms_info(int i, rfms_info_t *out)
{
    const std::vector<CoreSlotInfo> c = SlotLoader::shared().cores();
    if (i < 0 || i >= (int)c.size())
        return 0;
    fill(c[(size_t)i], out);
    return 1;
}

int rfms_find(const char *id, rfms_info_t *out)
{
    CoreSlotInfo c;
    if (!id || !SlotLoader::shared().find(id, c))
        return 0;
    fill(c, out);
    return 1;
}

uint32_t rfms_credits(const char *id, char *buf, uint32_t n)
{
    CoreSlotInfo c;
    if (!id || !SlotLoader::shared().find(id, c))
        return putAll(buf, n, "");
    return putAll(buf, n, c.credits);
}

uint32_t rfms_dirs(char *buf, uint32_t n) { return putAll(buf, n, SlotLoader::shared().dirs()); }

uint32_t rfms_errors(char *buf, uint32_t n) { return putAll(buf, n, SlotLoader::shared().errors()); }

uint32_t rfms_source(char *buf, uint32_t n) { return putAll(buf, n, SlotLoader::shared().source()); }

} // extern "C"
