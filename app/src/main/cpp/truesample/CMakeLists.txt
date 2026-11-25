cmake_minimum_required(VERSION 3.22.1)
# TrueSample usbaudio（Apache-2.0）—— 开源 USB Host 音频驱动，S03 推倒重建的新地基。
# 上游：https://github.com/devang-altf4/truesample-player  usbaudio/ 模块
# 本目录把库源码 vendored 进来，独立编译为 libusbaudio.so + libusb-1.0.so。
project(ts_usbaudio C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

set(TS_ROOT ${CMAKE_CURRENT_SOURCE_DIR})
set(LIBUSB_ROOT ${TS_ROOT}/third_party/libusb)
set(SRC_ROOT ${TS_ROOT}/third_party/libsamplerate)

# ---- 16KB 页对齐（Android 15+ 设备要求，否则 dlopen 失败）----
set(TS_PAGE_FLAGS -Wl,-z,max-page-size=16384 -Wl,-z,common-page-size=16384)

# libusb 独立共享库，保留其 LGPL-2.1 许可边界。
add_library(usb-1.0 SHARED
        ${LIBUSB_ROOT}/libusb/core.c
        ${LIBUSB_ROOT}/libusb/descriptor.c
        ${LIBUSB_ROOT}/libusb/hotplug.c
        ${LIBUSB_ROOT}/libusb/io.c
        ${LIBUSB_ROOT}/libusb/sync.c
        ${LIBUSB_ROOT}/libusb/strerror.c
        ${LIBUSB_ROOT}/libusb/os/linux_usbfs.c
        ${LIBUSB_ROOT}/libusb/os/events_posix.c
        ${LIBUSB_ROOT}/libusb/os/threads_posix.c
        ${LIBUSB_ROOT}/libusb/os/linux_netlink.c)
target_include_directories(usb-1.0
        PRIVATE ${LIBUSB_ROOT}/android ${LIBUSB_ROOT}/libusb/os
        PUBLIC ${LIBUSB_ROOT}/libusb)
target_compile_options(usb-1.0 PRIVATE -fvisibility=hidden -pthread -Wno-everything)
target_link_libraries(usb-1.0 log)
target_link_options(usb-1.0 PRIVATE ${TS_PAGE_FLAGS})

# libsamplerate（DAC 不支持源采样率时重采样用）。
add_library(samplerate STATIC
        ${SRC_ROOT}/src/samplerate.c
        ${SRC_ROOT}/src/src_sinc.c
        ${SRC_ROOT}/src/src_linear.c
        ${SRC_ROOT}/src/src_zoh.c)
target_include_directories(samplerate PUBLIC ${SRC_ROOT}/include)
target_compile_definitions(samplerate PRIVATE
        ENABLE_SINC_BEST_CONVERTER ENABLE_SINC_MEDIUM_CONVERTER ENABLE_SINC_FAST_CONVERTER
        HAVE_STDBOOL_H CPU_CLIPS_POSITIVE=0 CPU_CLIPS_NEGATIVE=0
        PACKAGE="libsamplerate" VERSION="0.2.2")
target_compile_options(samplerate PRIVATE -O3 -Wno-everything)
set_target_properties(samplerate PROPERTIES POSITION_INDEPENDENT_CODE ON)

# 主库：USB 流传输 + UAC 描述符解析 + JNI 桥。
add_library(usbaudio SHARED
        ${TS_ROOT}/jni_bridge.cpp
        ${TS_ROOT}/UsbStreamer.cpp
        ${TS_ROOT}/Log.cpp
        ${TS_ROOT}/uac/UacDescriptors.cpp)
target_compile_options(usbaudio PRIVATE -Wall -Wextra $<$<CONFIG:RelWithDebInfo>:-Oz>)
target_link_libraries(usbaudio usb-1.0 samplerate log)
target_link_options(usbaudio PRIVATE ${TS_PAGE_FLAGS}
        -Wl,--exclude-libs,ALL)
