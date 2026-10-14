# The SAMP client C++ sources live at the repository root. Reuse that
# build file so there is a single source of truth for the source list.
# $(call my-dir) inside the included file re-evaluates to the repo root.
LOCAL_PATH := $(call my-dir)
include $(LOCAL_PATH)/../../../../Android.mk
