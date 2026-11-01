// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 RemoteFM1 contributors
// A0's stand-in for RFMCore.cpp's engine hooks (RFMInternal.h): there is no link yet, so an engine always runs
// headless. A1 replaces this with the copied RFMCore.cpp.
#include "RFMInternal.h"

RemoteLink *rfmc_link(rfmc_t *) { return nullptr; }
void rfmc_set_external(rfmc_t *, int) {}
void rfmc_external_event(rfmc_t *, const rfm_event_t &) {}
void rfmc_external_panel(rfmc_t *, uint32_t, uint32_t, int32_t) {}
