/* SPDX-License-Identifier: GPL-3.0-only */
/* Copyright (C) 2026 RemoteFM1 contributors */
/* Android has no <os/lock.h>. ChoralRoot's host build (tools/emu/emu_hal_fw.h, emu_fw.c) takes Apple's
 * os_unfair_lock for its CPU lock (the audio ISR, the timer ISR and the UI's interrupt-off sections) and makes it
 * recursive itself; a plain pthread mutex does the same job here. On the include path of ChoralRoot's Android build
 * only (cores/CMakeLists.txt). */
#pragma once
#include <pthread.h>
#include <stdbool.h>

typedef pthread_mutex_t os_unfair_lock;
#define OS_UNFAIR_LOCK_INIT PTHREAD_MUTEX_INITIALIZER
static inline void os_unfair_lock_lock(os_unfair_lock *l) { pthread_mutex_lock(l); }
static inline void os_unfair_lock_unlock(os_unfair_lock *l) { pthread_mutex_unlock(l); }
static inline bool os_unfair_lock_trylock(os_unfair_lock *l) { return pthread_mutex_trylock(l) == 0; }
