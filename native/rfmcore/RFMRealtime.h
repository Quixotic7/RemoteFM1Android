// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 RemoteFM1 contributors
// Copied from RemoteFM1IOS 0d1a98b RFMKit/Sources/RFMCore/RFMRealtime.h; changes: Android. The Mach semaphore is a
// futex counter (a condition variable off Linux, for host builds), the Mach real-time policy an audio-priority
// thread (SCHED_FIFO when the system allows it, else nice -19, Android's THREAD_PRIORITY_URGENT_AUDIO), no audio
// workgroup (Android has none: Oboe's callback thread is the host's), and the clock is CLOCK_MONOTONIC in ns.
//
// What the engine's core thread needs to run beside the audio callback (RFMEngine.cpp): a semaphore (signalled from
// the audio thread: no locks, no allocation; waited on with a timeout) and the audio priority.
#pragma once
#include <atomic>
#include <cstdint>
#include <ctime>

#if !defined(__linux__)
#include <chrono>
#include <condition_variable>
#include <mutex>
#endif

namespace rfm {

// the time in ticks (ns) and how many microseconds one tick is
inline uint64_t nowTicks()
{
    timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (uint64_t)ts.tv_sec * 1000000000u + (uint64_t)ts.tv_nsec;
}
constexpr double kUsPerTick = 0.001;

#if defined(__linux__)
class Semaphore {
public:
    Semaphore() = default;
    Semaphore(const Semaphore &) = delete;
    Semaphore &operator=(const Semaphore &) = delete;
    void signal();                                      // real-time safe (an atomic, a futex wake if someone waits)
    // false: timed out
    bool wait_us(uint32_t us);

private:
    std::atomic<int32_t> count_{0};
    std::atomic<int32_t> waiters_{0};
};
#else
class Semaphore {                                       // (host builds: not real-time safe, for tests only)
public:
    void signal()
    {
        std::lock_guard<std::mutex> g(mu_);
        count_++;
        cv_.notify_one();
    }
    bool wait_us(uint32_t us)
    {
        std::unique_lock<std::mutex> l(mu_);
        if (!cv_.wait_for(l, std::chrono::microseconds(us), [&] { return count_ > 0; }))
            return false;
        count_--;
        return true;
    }

private:
    std::mutex mu_;
    std::condition_variable cv_;
    int count_ = 0;
};
#endif

// the calling thread at audio priority; `period_s` (the host buffer's duration) is kept for the iOS API's sake.
// Returns 0 refused, 1 SCHED_FIFO, 2 nice -19 (the usual result for an app on Android)
int setRealtime(double period_s);
// (iOS 14c9b96's setHighPriority) the calling thread an ordinary one (SCHED_OTHER) at nice -19: for work too lumpy
// for a real-time budget (an interpreted core's screen redraw can take many host buffers). Returns 3, or 0 refused
int setHighPriority();

// (Android 13+, ADPF) a performance hint session for the calling thread: the work it does each wake against a
// target (the host buffer's duration), so the system raises the CPU's clock (or moves the thread to a big core) when
// the work runs long instead of finding out late. Nothing where the device has no hint support (the emulator, Android
// 12 and older). One thread's: start, report and stop on the thread it is for
class PerfHint {
public:
    ~PerfHint() { stop(); }
    bool start(int64_t target_ns);          // (again: the target updated) false: no hint support here
    void report(int64_t actual_ns);
    void stop();
    bool on() const { return session_ != nullptr; }

private:
    void *session_ = nullptr;
    int64_t target_ = 0;
};

} // namespace rfm
