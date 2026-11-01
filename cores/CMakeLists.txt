# SPDX-License-Identifier: GPL-3.0-only
# Copyright (C) 2026 RemoteFM1 contributors
# Copied from RemoteFM1IOS 0d1a98b cores/CMakeLists.txt; changes: Android instead of iOS (one ELF shared library per
# core, libfm1core_<id>.so, exporting only fm1core_get through a version script; the arm64 coroutine on Android,
# where bionic has no ucontext switch; cores/android/os/lock.h for ChoralRoot instead of iOS's libproc.h stand-in,
# which Android doesn't need: ChoralRoot includes <libproc.h> only under __APPLE__); the macOS conformance test kept.
#
# The FM-1 cores for RemoteFM1 Android: ChoralRoot, Felucca, FIZZ, Saadet, Boardwalk and LOOM-8 through FM1VST's
# fm1_add_core() (cmake/FM1Cores.cmake) and their glue (core-glue/), without FM1VST's top CMakeLists (it fetches JUCE).
#
# Not configured from here directly: scripts/build_cores_android.sh copies this file, the parts of vendor/FM1VST it
# needs and the firmware trees into build/cores/src, laid out as in FM1VST, and configures that: the generate steps
# write into <tree>/build/gen, which must not land in vendor/, and the glue reaches the firmware by relative paths and
# as ${CMAKE_SOURCE_DIR}/cores/<name>. RFM_FM1VST is that copy (the source dir).
cmake_minimum_required(VERSION 3.22)
project(RemoteFM1Cores LANGUAGES C CXX)

set(RFM_FM1VST ${CMAKE_SOURCE_DIR} CACHE PATH "a copy of vendor/FM1VST's cmake, core-api, core-glue, engine, tests")
if(NOT EXISTS ${RFM_FM1VST}/cmake/FM1Cores.cmake)
    message(FATAL_ERROR "RFM_FM1VST (${RFM_FM1VST}) is not FM1VST: run scripts/build_cores_android.sh")
endif()
set(CR_PYTHON "/Library/Frameworks/Python.framework/Versions/3.11/bin/python3" CACHE FILEPATH
    "python3 with Pillow and fontTools, for the cores' generate steps")
set(RFM_CORES "choralroot;felucca;fizz;saadet;boardwalk;loom" CACHE STRING "the cores to build")
# FIZZ's build file is cores/fizz/core.cmake, Saadet's cores/saadet/core.cmake (two cores: saadet and its store
# variant saadet_store, the same core id), Boardwalk's cores/boardwalk/core.cmake, LOOM-8's cores/loom/core.cmake
# (RFM_OWN: this repo's cores/ folder)
set(RFM_OWN "" CACHE PATH "the repo's cores/ folder (cores/android: the coroutine; cores/fizz, ...)")

include(${RFM_FM1VST}/cmake/FM1Cores.cmake)
# FM1VST's coroutine (core-glue/common/coro.c) switches stacks with ucontext, which bionic lacks:
# cores/android/coro_arm64.c is the same API with an arm64 switch of its own (iOS's)
if(ANDROID)
    list(REMOVE_ITEM FM1_COMMON_SOURCES ${FM1_COMMON_DIR}/coro.c)
    list(APPEND FM1_COMMON_SOURCES ${RFM_OWN}/android/coro_arm64.c)
endif()
set(_glue ${RFM_FM1VST}/core-glue)
# (crf.cmake's golden tests are deferred to the end of this directory; they need FM1VST's reference emulator target,
# which is not here, so they skip themselves)
if(choralroot IN_LIST RFM_CORES)
    include(${_glue}/choralroot/core_choralroot.cmake)
endif()
if(felucca IN_LIST RFM_CORES)
    include(${_glue}/felucca_web/core_felucca.cmake)
endif()
if(fizz IN_LIST RFM_CORES)
    include(${RFM_OWN}/fizz/core.cmake)
endif()
if(saadet IN_LIST RFM_CORES)
    include(${RFM_OWN}/saadet/core.cmake)
endif()
if(boardwalk IN_LIST RFM_CORES)
    include(${RFM_OWN}/boardwalk/core.cmake)
endif()
if(loom IN_LIST RFM_CORES)
    include(${RFM_OWN}/loom/core.cmake)
endif()
# ChoralRoot's host build takes Apple's os_unfair_lock for its CPU lock: cores/android/os/lock.h stands in for it
if(ANDROID AND TARGET core_choralroot_obj)
    target_include_directories(core_choralroot_obj BEFORE PRIVATE ${RFM_OWN}/android)
endif()
message(STATUS "cores: ${FM1_CORE_IDS} (${CMAKE_SYSTEM_NAME} ${ANDROID_ABI} ${ANDROID_PLATFORM})")

# per core: FM1Core_<id> (lib/libfm1core_<id>.so), the same objects as FM1VST's <id>.fm1core
file(WRITE ${CMAKE_BINARY_DIR}/fm1core.map "{ global: fm1core_get; local: *; };\n")
foreach(_id IN LISTS FM1_CORE_IDS)
    add_library(FM1Core_${_id} SHARED $<TARGET_OBJECTS:core_${_id}_obj>)
    set_target_properties(FM1Core_${_id} PROPERTIES
        OUTPUT_NAME fm1core_${_id}
        LIBRARY_OUTPUT_DIRECTORY ${CMAKE_BINARY_DIR}/lib)
    if(ANDROID)
        target_link_options(FM1Core_${_id} PRIVATE -Wl,--version-script=${CMAKE_BINARY_DIR}/fm1core.map
                            -Wl,--gc-sections -Wl,-z,max-page-size=16384)
        target_link_libraries(FM1Core_${_id} PRIVATE m log)
    elseif(APPLE)
        target_link_options(FM1Core_${_id} PRIVATE -Wl,-exported_symbol,_fm1core_get -Wl,-dead_strip)
    endif()
    get_property(_ver GLOBAL PROPERTY FM1_CORE_VERSION_${_id})
    get_property(_commit GLOBAL PROPERTY FM1_CORE_COMMIT_${_id})
    string(APPEND _manifest "${_id} ${_ver} ${_commit}\n")
endforeach()
file(WRITE ${CMAKE_BINARY_DIR}/cores.txt "${_manifest}")

# macOS only: FM1VST's conformance test (tests/core_conformance.cpp) over our libraries: ctest -R core_conformance
if(CMAKE_SYSTEM_NAME STREQUAL "Darwin")
    enable_testing()
    add_library(fm1engine STATIC ${RFM_FM1VST}/engine/cores.cpp ${RFM_FM1VST}/engine/device.cpp)
    target_include_directories(fm1engine PUBLIC ${RFM_FM1VST}/engine ${RFM_FM1VST}/core-api)
    target_compile_features(fm1engine PUBLIC cxx_std_17)
    add_library(fm1testlib STATIC ${RFM_FM1VST}/tests/script_runner.cpp)
    target_link_libraries(fm1testlib PUBLIC fm1engine)
    target_include_directories(fm1testlib PUBLIC ${RFM_FM1VST}/tests ${FM1_COMMON_DIR})
    target_compile_definitions(fm1testlib PUBLIC FM1_CR_DIR="" FM1_CORE_MODULE="" FM1_REF_EMU="" FM1_CORES_BUILD_DIR=""
        FM1_BUILT_CORES="" FM1_REF_OUT="" FM1_SOURCE_DIR="${RFM_FM1VST}")
    add_executable(core_conformance EXCLUDE_FROM_ALL ${RFM_FM1VST}/tests/core_conformance.cpp)
    target_link_libraries(core_conformance PRIVATE fm1testlib)
    file(MAKE_DIRECTORY ${CMAKE_BINARY_DIR}/conformance)
    foreach(_id IN LISTS FM1_CORE_IDS)
        get_property(_g GLOBAL PROPERTY FM1_CORE_GLUE_${_id})
        get_property(_fam GLOBAL PROPERTY FM1_CORE_FAMILY_${_id})
        get_property(_lic GLOBAL PROPERTY FM1_CORE_LICENSE_${_id})
        set(_conf ${_g}/conformance_${_id}.txt)
        if(NOT EXISTS ${_conf})
            set(_conf ${_g}/conformance.txt)
        endif()
        string(REGEX REPLACE "_store$" "" _cid ${_id})     # (a store variant has its core's id)
        add_test(NAME core_conformance_${_id}
                 COMMAND core_conformance $<TARGET_FILE:FM1Core_${_id}> ${_cid} ${_fam} ${_lic} ${_conf}
                         ${CMAKE_BINARY_DIR}/conformance)
        set_tests_properties(core_conformance_${_id} PROPERTIES TIMEOUT 300
                             ENVIRONMENT "FM1EMU_HOME=${CMAKE_BINARY_DIR}/conformance/home")
    endforeach()
endif()
