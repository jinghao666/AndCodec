#ifndef AND_C_2_JAVA_C_H
#define AND_C_2_JAVA_C_H

#include <jni.h>

JNIEXPORT jint 
JNICALL JNI_OnLoad(JavaVM* vm, void* reserved);

int 
c2java_Register(JNIEnv* env, jclass clazz);

JNIEnv* 
c2java_Attach();

int 
c2java_Detach(JNIEnv * env);

int 
c2java_OnError(JNIEnv* env, int code, const char *message);

int 
c2java_OnFinish(JNIEnv *env, const char *filename, 
			 int total_frames, int in_frames, int out_frames, double fps);

int 
c2java_OnInfo(JNIEnv* env, int code, const char *message);

int 
c2java_OnFPS(JNIEnv *env, int frames, double fps);

#endif //AND_C_2_JAVA_C_H
