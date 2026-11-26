cmake_minimum_required(VERSION 3.22.1)
project(chuizi_usb C CXX)

# [1.4.19·S03] 新 USB 独占地基：TrueSample 开源 usbaudio 库（Apache-2.0），
# 与旧 chuizi_usb 并存；运行时由开关选择用哪套引擎，旧引擎保留可回退。
add_subdirectory(truesample)

# USB 独占的原生传输层：usbfs isochronous URB 直接推 PCM 给 USB DAC。
add_library(chuizi_usb SHARED usbdac.cpp)

target_link_libraries(chuizi_usb
        log)

# Android 15 起存在 16KB 页设备，NDK r26 默认按 4KB 对齐，必须显式对齐，
# 否则这些机器上 dlopen 会失败（表现为 USB 独占整体不可用）。
target_link_options(chuizi_usb PRIVATE
        -Wl,-z,max-page-size=16384
        -Wl,-z,common-page-size=16384)

target_compile_options(chuizi_usb PRIVATE
        $<$<CONFIG:RelWithDebInfo>:-Oz>
        -ffunction-sections
        -fdata-sections
        -fno-exceptions
        -fno-rtti)
target_link_options(chuizi_usb PRIVATE
        -Wl,--gc-sections
        -Wl,--exclude-libs,ALL)
