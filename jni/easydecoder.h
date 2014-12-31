#ifndef EASY_DECODER_H
#define EASY_DECODER_H

#include <jni.h>

typedef struct easy_decoder_handle easy_decoder_handle;

char * AndCodec_EasyDecoderVersion();

// @return INVALID_HANDLE: error, return others: decoder handle
// decoder handle use as Add() Get() Close() GetFPS() input param
int AndCodec_EasyDecoderOpen(int w, int h, int out_fmt);

// @param dec: decoder handle
// @param decdata: h264 data to encode
// @param decdata_size: h264 data size in byte
// @param opaque: opaque data
// @param opaque: opaque data len(should be 16 byte)
// @return <0: error
// @return =0: no picture decoded out(opaque data will be always added to list, cached picture will available later)
// @return >0: new picture decoded out
int AndCodec_EasyDecoderAdd(int dec, unsigned char* decdata, int decdata_size, 
							unsigned char* opaque, int opaque_len);

// @param dec: decoder handle
// @param picdata: receive decoded picture data
// @param opaque: opaque data
// @param opaque: opaque data len(should be 16 byte)
// @return <0: error
// @return 0: no picture got
// @return >0: out picture size
int AndCodec_EasyDecoderGet(int dec, unsigned char* picdata, 
							unsigned char* opaque, int *opaque_len);

int AndCodec_EasyDecoderFlush(int dec);

void AndCodec_EasyDecoderClose(int dec);

double AndCodec_EasyDecoderGetFPS(int dec);

void AndCodec_EasyDecoderTest(const char* input_file, const char* output_file, 
							  int frames, int is_writefile);

//@return <0 error
//@return >0 handle
JNIEXPORT int 
Java_tv_xormedia_AndCodec_CodecLib_EasyDecoderOpen(JNIEnv* env, jobject thiz, 
	int w, int h, int out_fmt);

//@return <0 error
//@return 0 ok
JNIEXPORT int 
Java_tv_xormedia_AndCodec_CodecLib_EasyDecoderAdd(JNIEnv* env, jobject thiz, 
	int dec, jobject decdata, int decdata_size, jobject opaque);

//picdata alloc by user. Actual picdata size is returned by picdata_size.
//@return <0 error
//@return 0 no picture
//@return >0 pic size	
JNIEXPORT int 
Java_tv_xormedia_AndCodec_CodecLib_EasyDecoderGet(JNIEnv* env, jobject thiz, 
	int dec, jobject picdata, jobject opaque);

JNIEXPORT void 
Java_tv_xormedia_AndCodec_CodecLib_EasyDecoderClose(JNIEnv* env, jobject thiz, int dec);

JNIEXPORT double 
Java_tv_xormedia_AndCodec_CodecLib_EasyDecoderGetFPS(JNIEnv* env, jobject thiz, int enc);

JNIEXPORT void
Java_tv_xormedia_AndCodec_CodecLib_EasyDecoderTest(JNIEnv* env, jobject thiz, 
	jstring input_file, jstring output_file, int width, int height, int out_fmt,
	int frames, int is_writefile);

#endif //EASY_DECODER_H

