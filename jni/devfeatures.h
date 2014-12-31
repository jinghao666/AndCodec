#ifndef DEVICE_FEATURES_H
#define DEVICE_FEATURES_H
#include <jni.h>

int AndCodec_IsSupportNeon();

// return >0 support Neon, =0 not support
JNIEXPORT int 
Java_tv_xormedia_AndCodec_CodecLib_IsSupportNeon();

#endif //DEVICE_FEATURES_H

