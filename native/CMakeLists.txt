# SPDX-License-Identifier: GPL-3.0-only
# Copyright (C) 2026 RemoteFM1 contributors
# The app's native library, librfmcore.so (built by the app's Gradle build through externalNativeBuild):
#   - libremotefm1 (vendor/RemoteFM1 host/src, portable C99, compiled in place; the FM-1 link uses it from A1 on)
#   - the engine copied from RemoteFM1IOS (rfmcore/: RFMEngine, the device clock, the core loader, the MIDI map)
#   - FM1VST's engine/resampler.h and core-api/fm1core.h, in place
#   - the Oboe output (rfmcore/RFMOboe.cpp; Oboe from its prefab package) and the JNI face (rfmcore/rfm_jni.cpp)
# The cores are not built here: scripts/build_cores_android.sh makes them (libfm1core_<id>.so, jniLibs).
cmake_minimum_required(VERSION 3.22)
project(rfmcore LANGUAGES C CXX)

set(CMAKE_C_STANDARD 99)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

get_filename_component(RFM_ROOT ${CMAKE_CURRENT_SOURCE_DIR}/.. ABSOLUTE)
set(RFM ${RFM_ROOT}/vendor/RemoteFM1)
set(FM1VST ${RFM_ROOT}/vendor/FM1VST)

add_library(remotefm1 STATIC
    ${RFM}/host/src/codec.c ${RFM}/host/src/screen.c ${RFM}/host/src/tiles_ref.c ${RFM}/host/src/panel.c
    ${RFM}/host/src/session.c ${RFM}/host/src/fake.c)
target_include_directories(remotefm1 PUBLIC ${RFM}/host/include ${RFM}/proto PRIVATE ${RFM}/firmware/src)
target_compile_options(remotefm1 PRIVATE -Wall -Wextra -Wno-unused-parameter)

find_package(oboe REQUIRED CONFIG)

set(R ${CMAKE_CURRENT_SOURCE_DIR}/rfmcore)
add_library(rfmcore SHARED
    ${R}/RFMEngine.cpp ${R}/RFMDevice.cpp ${R}/RFMNativeCore.cpp ${R}/RFMSlots.cpp ${R}/RFMRealtime.cpp
    ${R}/RFMMidiMap.cpp ${R}/RFMEchoCore.cpp ${R}/RFMLinkStub.cpp ${R}/RFMOboe.cpp ${R}/rfm_jni.cpp)
target_include_directories(rfmcore PRIVATE ${R} ${R}/include ${FM1VST}/engine ${FM1VST}/core-api ${FM1VST}/plugin)
target_compile_options(rfmcore PRIVATE -Wall -Wextra -Wno-unused-parameter)
target_link_libraries(rfmcore PRIVATE remotefm1 oboe::oboe log dl)
target_link_options(rfmcore PRIVATE -Wl,-z,max-page-size=16384)
