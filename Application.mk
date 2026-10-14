# Multi-ABI build: 32-bit + 64-bit ARM, 32-bit + 64-bit x86.
# NOTE: the 64-bit ABIs require at least android-21.
APP_ABI := armeabi-v7a arm64-v8a x86 x86_64
APP_PLATFORM := android-21
APP_MODULES := samp
# gnustl was removed from the NDK years ago; use libc++.
APP_STL := c++_static
