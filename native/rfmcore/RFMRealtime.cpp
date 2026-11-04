// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 RemoteFM1 contributors
// Copied from RemoteFM1IOS 0d1a98b RFMKit/Sources/RFMCore/RFMRealtime.cpp; changes: rewritten for Android (a futex
// semaphore, SCHED_FIFO or nice -19 instead of the Mach time-constraint policy, no workgroup). setHighPriority from 14c9b96.
#include "RFMRealtime.h"

#include <pthread.h>
#include <sched.h>
#include <sys/resource.h>
#include <unistd.h>
#include <dlfcn.h>

#if defined(__linux__)
#include <linux/futex.h>
#include <sys/syscall.h>
#endif

namespace rfm {

#if defined(__linux__)
static long futex(std::atomic<int32_t> *addr, int op, int32_t val, const timespec *ts)
{
    return syscall(SYS_futex, reinterpret_cast<int32_t *>(addr), op, val, ts, nullptr, 0);
}

void Semaphore::signal()
{
    count_.fetch_add(1, std::memory_order_release);
    if (waiters_.load(std::memory_order_seq_cst) > 0)
        futex(&count_, FUTEX_WAKE_PRIVATE, 1, nullptr);
}

bool Semaphore::wait_us(uint32_t us)
{
    const uint64_t end = nowTicks() + (uint64_t)us * 1000u;
    for (;;) {
        int32_t c = count_.load(std::memory_order_acquire);
        while (c > 0)
            if (count_.compare_exchange_weak(c, c - 1, std::memory_order_acq_rel))
                return true;
        const uint64_t now = nowTicks();
        if (now >= end)
            return false;
        const uint64_t left = end - now;
        const timespec ts{(time_t)(left / 1000000000u), (long)(left % 1000000000u)};
        waiters_.fetch_add(1, std::memory_order_seq_cst);
        futex(&count_, FUTEX_WAIT_PRIVATE, 0, &ts);     // (returns at once when the count is no longer 0)
        waiters_.fetch_sub(1, std::memory_order_seq_cst);
    }
}
#endif

int setRealtime(double period_s)
{
    (void)period_s;
    sched_param sp{};
    sp.sched_priority = 2;                              // (low among FIFO threads: AAudio's own use 2..3)
    if (pthread_setschedparam(pthread_self(), SCHED_FIFO, &sp) == 0)
        return 1;
#if defined(__linux__)
    if (setpriority(PRIO_PROCESS, (id_t)gettid(), -19) == 0)
        return 2;
#endif
    return 0;
}

namespace {
// libandroid's APerformanceHint_* (API 33), found at run time (minSdk 29)
struct HintApi {
    void *(*getManager)() = nullptr;
    void *(*create)(void *, const int32_t *, size_t, int64_t) = nullptr;
    int (*update)(void *, int64_t) = nullptr;
    int (*report)(void *, int64_t) = nullptr;
    void (*close)(void *) = nullptr;
    void *manager = nullptr;
    HintApi()
    {
        void *lib = dlopen("libandroid.so", RTLD_NOW | RTLD_NOLOAD);
        if (!lib)
            lib = dlopen("libandroid.so", RTLD_NOW);
        if (!lib)
            return;
        getManager = reinterpret_cast<void *(*)()>(dlsym(lib, "APerformanceHint_getManager"));
        create = reinterpret_cast<void *(*)(void *, const int32_t *, size_t, int64_t)>(dlsym(lib, "APerformanceHint_createSession"));
        update = reinterpret_cast<int (*)(void *, int64_t)>(dlsym(lib, "APerformanceHint_updateTargetWorkDuration"));
        report = reinterpret_cast<int (*)(void *, int64_t)>(dlsym(lib, "APerformanceHint_reportActualWorkDuration"));
        close = reinterpret_cast<void (*)(void *)>(dlsym(lib, "APerformanceHint_closeSession"));
        if (getManager && create && update && report && close)
            manager = getManager();
    }
};
HintApi &hintApi()
{
    static HintApi h;
    return h;
}
} // namespace

bool PerfHint::start(int64_t target_ns)
{
#if defined(__linux__)
    if (target_ns <= 0)
        target_ns = 4000000;
    HintApi &h = hintApi();
    if (!h.manager)
        return false;
    if (session_) {
        if (target_ns != target_)
            h.update(session_, target_ns);
        target_ = target_ns;
        return true;
    }
    const int32_t tid = (int32_t)gettid();
    session_ = h.create(h.manager, &tid, 1, target_ns);
    target_ = target_ns;
    return session_ != nullptr;
#else
    (void)target_ns;
    return false;
#endif
}

void PerfHint::report(int64_t actual_ns)
{
    if (session_ && actual_ns > 0)
        hintApi().report(session_, actual_ns);
}

void PerfHint::stop()
{
    if (session_)
        hintApi().close(session_);
    session_ = nullptr;
}

int setHighPriority()
{
    sched_param sp{};
    sp.sched_priority = 0;
    pthread_setschedparam(pthread_self(), SCHED_OTHER, &sp);    // (leaves SCHED_FIFO if it had it)
#if defined(__linux__)
    if (setpriority(PRIO_PROCESS, (id_t)gettid(), -19) == 0)
        return 3;
#endif
    return 0;
}

} // namespace rfm
