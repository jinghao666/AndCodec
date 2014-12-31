#ifndef EASY_ENCODER_H
#define EASY_ENCODER_H

#ifdef ANDROID_APP
#include <jni.h>
#endif

typedef struct easy_encoder_handle easy_encoder_handle;

char * AndCodec_EasyEncoderVersion();

// @param profile: available option: "low", "high", "smartquality", "nolatency"
//		can be NULL or empty string "", will use default "high"
// @param enc_str: some x264 tunable settings(separated by comma ',')
//		can be NUll or empty string ""
// enc_str option list:
//		1) quality(1-100)
//		2) gop_size(integer[15] or factor[2x] to fps)
// quick profile and enc_str settings:
//		1) "low", ""
//		2) "high", ""
//		3) "smartquality", "quality=35"
//		4) "", ""
// @return INVALID_HANDLE: error, return others: encoder handle
//		encoder handle use as Add() Get() Close() GetFPS() input param
int AndCodec_EasyEncoderOpen(int w, int h, int in_fmt, 
							 const char *profile, const char *enc_str);

// @param enc: encoder handle
// @param picdata: picture data to encode
// @param picdata_size: picture data size in byte
// @param opaque: opaque data
// @param opaque: opaque data len(should be 16 byte)
// @return <0: error
// @return =0: no encoded data out(opaque data will be discarded)
// @return >0: encoded data size
int AndCodec_EasyEncoderAdd(int enc, unsigned char *picdata, int picdata_size, 
							unsigned char *opaque, int opaque_len);

// @param dec: encoder handle
// @param encdata: receive encoded h264 data
// @param opaque: opaque data
// @param opaque: receive opaque data len(could be null)
// @return <0: error
// @return =0: no h264 data got
// @return >0: out h264 data size
int AndCodec_EasyEncoderGet(int enc, unsigned char *encdata, 
							unsigned char *opaque, int *opaque_len);

void AndCodec_EasyEncoderClose(int enc);

void AndCodec_EasyEncoderTest(const char *input_file, const char *output_file, 
							  const char *preset, int frames, int is_writefile);

int AndCodec_EasyEncoderTest2(const char *input_file, const char *output_file, 
							  int w, int h, const char *profile, const char *enc_str, 
							  int frames, int is_writefile, int is_writesize);

double AndCodec_EasyEncoderGetFPS(int enc);

#ifdef ANDROID_APP
//@return <0 error, return >0 handle
JNIEXPORT int 
Java_tv_xormedia_AndCodec_CodecLib_EasyEncoderOpen(JNIEnv* env, jobject thiz,
	int w, int h, int in_fmt, 
	jstring profile, jstring enc_str);

//@return <0 error, return 0 ok
JNIEXPORT int 
Java_tv_xormedia_AndCodec_CodecLib_EasyEncoderAdd(JNIEnv* env, jobject thiz,
	int enc, jobject picdata, int picdata_size, jobject opaque);

//encdata alloc by user. it should be not less picdata_size. actual encdata size is returned by encdata_size.
//@return <0 error, return 0 no data, return >0 data size
JNIEXPORT int 
Java_tv_xormedia_AndCodec_CodecLib_EasyEncoderGet(JNIEnv* env, jobject thiz,
	int enc, jobject encdata, jobject opaque);

JNIEXPORT void 
Java_tv_xormedia_AndCodec_CodecLib_EasyEncoderClose(JNIEnv* env, jobject thiz, int enc);

JNIEXPORT int
Java_tv_xormedia_AndCodec_CodecLib_EasyEncoderTest(JNIEnv* env, jclass clazz, // jobject thiz,
	jstring input_file, jstring output_file, 
	int w, int h, jstring preset, jstring enc_str, 
	int frames, int is_writefile, int is_writesize);

JNIEXPORT int
Java_tv_xormedia_AndCodec_CodecLib_EasyEncoderTestAbort();

JNIEXPORT double 
Java_tv_xormedia_AndCodec_CodecLib_EasyEncoderGetFPS(JNIEnv* env, jobject thiz, int enc);
#endif

#endif //EASY_ENCODER_H

