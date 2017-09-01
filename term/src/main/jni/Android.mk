LOCAL_PATH:= $(call my-dir)
include $(CLEAR_VARS)

# This is the target being built.
#LOCAL_MODULE:= libjackpal-androidterm5
LOCAL_MODULE:= libjackpal-androidterm5nhj1

# All of the source files that we will compile.
LOCAL_SRC_FILES:= \
  common.cpp \
  termExec.cpp \
  fileCompat.cpp

LOCAL_LDLIBS := -ldl -llog

include $(BUILD_SHARED_LIBRARY)
