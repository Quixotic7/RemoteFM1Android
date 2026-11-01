# SPDX-License-Identifier: GPL-3.0-only
# Copyright (C) 2026 RemoteFM1 contributors
# The app's native library, librfmcore.so (built by the app's Gradle build through externalNativeBuild):
#   - libremotefm1 (vendor/RemoteFM1 host/src, portable C99, compiled in place) and fm1remote's test pattern
#   - the engine and the FM-1 link copied from RemoteFM1IOS (rfmcore/: RFMEngine, the device clock, the core loader,
#     the MIDI map, RemoteLink, RFMCore) and the link's Android MIDI back end (rfmcore/android: AMidi)
#   - FM1VST's engine/resampler.h and core-api/fm1core.h, in place
#   - the Oboe output (rfmcore/RFMOboe.cpp; Oboe from its prefab package) and the JNI faces (rfmcore/rfm_jni.cpp: the
#     engine; rfmcore/rfm_link_jni.cpp: the link, the MIDI device list, a standalone fake unit)
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
    ${R}/RFMMidiMap.cpp ${R}/RFMEchoCore.cpp ${R}/RemoteLink.cpp ${R}/RFMCore.cpp ${R}/android/rfm_android.cpp
    ${RFM}/host/tools/fm1remote/patterns.cpp ${R}/RFMOboe.cpp ${R}/rfm_jni.cpp ${R}/rfm_link_jni.cpp)
target_include_directories(rfmcore PRIVATE ${R} ${R}/include ${FM1VST}/engine ${FM1VST}/core-api ${FM1VST}/plugin
    ${RFM}/host/tools/fm1remote)
target_compile_options(rfmcore PRIVATE -Wall -Wextra -Wno-unused-parameter)
target_link_libraries(rfmcore PRIVATE remotefm1 oboe::oboe amidi log dl)
target_link_options(rfmcore PRIVATE -Wl,-z,max-page-size=16384)

# (A5) librfmlzfse.so: Apple's lzfse (native/lzfse/src, BSD-3) and its JNI, for the states' LZFSE (data/Lzfse.kt)
file(GLOB RFM_LZFSE_SRC ${CMAKE_CURRENT_SOURCE_DIR}/lzfse/src/*.c)
add_library(rfmlzfse SHARED ${RFM_LZFSE_SRC} ${CMAKE_CURRENT_SOURCE_DIR}/lzfse/lzfse_jni.c)
target_compile_options(rfmlzfse PRIVATE -O2 -Wno-unused-parameter)
target_link_options(rfmlzfse PRIVATE -Wl,-z,max-page-size=16384)
