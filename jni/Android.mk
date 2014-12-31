LOCAL_PATH := $(call my-dir)
#BUILD_FF_ENC	:= 1
#BUILD_JNI_LOAD	:= 1

FFMPEG_LIB_PATH	:= $(LOCAL_PATH)/ffmpeg/lib/$(TARGET_ARCH_ABI)
X264_LIB_PATH 	:= $(LOCAL_PATH)/x264/lib/$(TARGET_ARCH_ABI)

include $(CLEAR_VARS)
LOCAL_MODULE 	:= ffmpeg
LOCAL_SRC_FILES := ffmpeg/lib/$(TARGET_ARCH_ABI)/libffmpeg.a
include $(PREBUILT_STATIC_LIBRARY)

include $(CLEAR_VARS)
LOCAL_MODULE 	:= x264
LOCAL_SRC_FILES := x264/lib/$(TARGET_ARCH_ABI)/libx264.a
include $(PREBUILT_STATIC_LIBRARY)

include $(CLEAR_VARS)
LOCAL_MODULE 	:= fdk-aac
LOCAL_SRC_FILES := x264/lib/$(TARGET_ARCH_ABI)/libfdk-aac.a
include $(PREBUILT_STATIC_LIBRARY)

include $(CLEAR_VARS)
LOCAL_MODULE			:= andcodec
LOCAL_C_INCLUDES		:= $(LOCAL_PATH)/x264/include $(LOCAL_PATH)/ffmpeg/include
LOCAL_SRC_FILES 		:= andsysutil.c andutility.c andstr.c andlog.c andtiming_c.c \
	andfifobuffer.c andqueue.c andtunables.c andparseconf.c easyencoder.c easydecoder.c
LOCAL_CFLAGS    		:= -Wall -DANDROID_APP=1
ifdef BUILD_FF_ENC
LOCAL_SRC_FILES			+= andffencoder.c
endif
ifdef BUILD_JNI_LOAD
LOCAL_SRC_FILES			+= andc2java_c.c
LOCAL_CFLAGS    		+= -DC_SIDE_TEST=1
endif
LOCAL_STATIC_LIBRARIES 	:= ffmpeg x264 fdk-aac
LOCAL_LDLIBS 			:= -llog -lz -L$(X264_LIB_PATH) -L$(FFMPEG_LIB_PATH)
include $(BUILD_SHARED_LIBRARY)

include $(CLEAR_VARS)
LOCAL_MODULE			:= anddevft
LOCAL_STATIC_LIBRARIES	:= cpufeatures
LOCAL_SRC_FILES 		:= andsysutil.c andutility.c andstr.c andlog.c devfeatures.c 
LOCAL_CFLAGS    		:= -Wall -DANDROID_APP
LOCAL_LDLIBS 			:= -llog
include $(BUILD_SHARED_LIBRARY)
$(call import-module,cpufeatures)
