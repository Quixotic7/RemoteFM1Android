// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 RemoteFM1 contributors
// Copied from RemoteFM1IOS 0d1a98b RFMKit/Sources/RFMCore/RFMRealtime.cpp; changes: rewritten for Android (a futex
// semaphore, SCHED_FIFO or nice -19 instead of the Mach time-constraint policy, no workgroup).
#include "RFMRealtime.h"

#include <pthread.h>
#include <sched.h>
#include <sys/resource.h>
#include <unistd.h>

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

} // namespace rfm
