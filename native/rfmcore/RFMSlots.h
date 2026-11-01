// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 RemoteFM1 contributors
// Copied from RemoteFM1IOS 0d1a98b RFMKit/Sources/RFMCore/RFMSlots.h; changes: Android. One library per core
// (libfm1core_<id>.so) instead of four slot frameworks; every acquire loads a fresh copy of it (Slot::copy), so the
// held / retired lists are of copies, not paths. scan("") is not used (the app passes its nativeLibraryDir).
// The core loader (the C face is rfms_* in include/RFMEngine.h). Replaces FM1VST's engine/cores.cpp: each instance
// loads its own copy of the core's image (android_dlopen_ext, ANDROID_DLEXT_FORCE_LOAD), so its globals are its own.
// The descriptor probe and the ABI check are cores.cpp's (open_core).
#pragma once
#include <map>
#include <mutex>
#include <string>
#include <vector>

#include "fm1core.h"

namespace rfm {

struct CoreSlotInfo {
    std::string id, name, version, source_url, family, license, gated, credits;
    uint32_t abi = 0, flash_size = 0;
    std::vector<std::string> paths;     // the core's library (one entry on Android)
};

class SlotLoader {
public:
    static SlotLoader &shared();

    // one held copy: its image loaded (RTLD_NOW | RTLD_LOCAL, a copy of its own), its descriptor checked
    struct Slot {
        std::string id, path;
        int n = 0;                      // 1 (the engine's status shows it)
        uint32_t copy = 0;              // this copy's number, 1.. in the process
        void *handle = nullptr;
        const fm1core_t *core = nullptr;
        void *symbol(const char *name) const;
    };

    int scan(const std::string &dir);   // the libfm1core_<id>.so files in dir (rfms_scan)
    int scanList(const std::string &dir, const std::string &list);   // the cores `list` names, in dir (rfms_scan_list)
    std::vector<CoreSlotInfo> cores() const;
    bool find(const std::string &id, CoreSlotInfo &out) const;
    int inUse(const std::string &id) const;
    int retired(const std::string &id) const;
    std::string dirs() const;
    std::string errors() const;
    std::string source() const;         // how the last scan found its slots (rfms_source)

    // a fresh copy of the core, loaded; nullptr with a message otherwise
    Slot *acquire(const std::string &id, std::string *err);
    // shutdown(), dlclose; the copy is counted as retired if its image did not leave memory
    void release(Slot *s);

private:
    mutable std::mutex mu_;
    std::vector<CoreSlotInfo> cores_;
    std::vector<std::string> held_, retired_;   // copies, as "<path>#<copy>"
    uint32_t copies_ = 0;
    std::vector<std::string> dirs_;
    std::string errors_, source_;

    int probeLocked(const std::map<std::string, std::vector<std::string>> &found);
};

} // namespace rfm
