LOCAL_PATH := $(call my-dir)

FFMPEG_LIB_PATH	:= $(LOCAL_PATH)/ffmpeg/lib/$(TARGET_ARCH_ABI)
X264_LIB_PATH 	:= $(LOCAL_PATH)/x264/lib/$(TARGET_ARCH_ABI)

include $(CLEAR_VARS)
LOCAL_MODULE 	:= avformat
LOCAL_SRC_FILES := ffmpeg/lib/$(TARGET_ARCH_ABI)/libavformat.a
include $(PREBUILT_STATIC_LIBRARY)

include $(CLEAR_VARS)
LOCAL_MODULE 	:= avcodec
LOCAL_SRC_FILES := ffmpeg/lib/$(TARGET_ARCH_ABI)/libavcodec.a
include $(PREBUILT_STATIC_LIBRARY)

include $(CLEAR_VARS)
LOCAL_MODULE 	:= avutil
LOCAL_SRC_FILES := ffmpeg/lib/$(TARGET_ARCH_ABI)/libavutil.a
include $(PREBUILT_STATIC_LIBRARY)

include $(CLEAR_VARS)
LOCAL_MODULE 	:= swscale
LOCAL_SRC_FILES := ffmpeg/lib/$(TARGET_ARCH_ABI)/libswscale.a
include $(PREBUILT_STATIC_LIBRARY)

include $(CLEAR_VARS)
LOCAL_MODULE 	:= swresample
LOCAL_SRC_FILES := ffmpeg/lib/$(TARGET_ARCH_ABI)/libswresample.a
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
LOCAL_SRC_FILES 		:= andsysutil.c andutility.c andstr.c andlog.c \
	andfifobuffer.c andqueue.c andtunables.c andparseconf.c easyencoder.c easydecoder.c 
LOCAL_CFLAGS    		:= -Wall -DANDROID_APP=1
LOCAL_STATIC_LIBRARIES 	:= avformat avcodec avutil swscale swresample x264 fdk-aac
LOCAL_LDLIBS 			:= -llog -L$(X264_LIB_PATH) -L$(FFMPEG_LIB_PATH)
include $(BUILD_SHARED_LIBRARY)

ifeq ($(TARGET_ARCH_ABI),armeabi)
include $(CLEAR_VARS)
LOCAL_MODULE 	:= x264_neon
LOCAL_SRC_FILES := x264/lib/$(TARGET_ARCH_ABI)/libx264_neon.a
include $(PREBUILT_STATIC_LIBRARY)

include $(CLEAR_VARS)
LOCAL_MODULE			:= andcodec_neon
LOCAL_C_INCLUDES		:= $(LOCAL_PATH)/x264/include $(LOCAL_PATH)/ffmpeg/include
LOCAL_SRC_FILES 		:= andsysutil.c andutility.c andstr.c andlog.c \
	andfifobuffer.c andqueue.c andtunables.c andparseconf.c easyencoder.c easydecoder.c 
LOCAL_CFLAGS    		:= -Wall -DANDROID_APP=1 -DHAVE_NEON=1
LOCAL_STATIC_LIBRARIES 	:= avformat avcodec avutil swscale swresample x264_neon fdk-aac
LOCAL_LDLIBS 			:= -llog -L$(X264_LIB_PATH) -L$(FFMPEG_LIB_PATH)
include $(BUILD_SHARED_LIBRARY)
endif

include $(CLEAR_VARS)
LOCAL_MODULE			:= anddevft
LOCAL_STATIC_LIBRARIES	:= cpufeatures
LOCAL_SRC_FILES 		:= andsysutil.c andutility.c andstr.c andlog.c devfeatures.c 
LOCAL_CFLAGS    		:= -Wall -DANDROID_APP
LOCAL_LDLIBS 			:= -llog
include $(BUILD_SHARED_LIBRARY)
$(call import-module,cpufeatures)
