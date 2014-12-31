#include <jni.h>
#include <stddef.h>
extern "C" {
#include "easyencoder.h"
#include "easydecoder.h"
#include "andlog.h"
};


static void initClassHelper(JNIEnv *env, const char *path, jobject *objptr);

static JavaVM *g_JavaVM = NULL;
static jobject gInterfaceObject;
//static int g_registered = 0;

JNINativeMethod aJNINativeMethod[] = {
	{ "EasyEncoderAdd",
		"(I[BI[B)I",
		(void*)Java_tv_xormedia_AndCodec_CodecLib_EasyEncoderAdd },
	{ "EasyEncoderGet",
	"(I[B[B)I",
	(void*)Java_tv_xormedia_AndCodec_CodecLib_EasyEncoderGet },

	{ "EasyDecoderAdd",
	"(I[BI[B)I",
	(void*)Java_tv_xormedia_AndCodec_CodecLib_EasyDecoderAdd },
	{ "EasyDecoderGet",
	"(I[B[B)I",
	(void*)Java_tv_xormedia_AndCodec_CodecLib_EasyDecoderGet },
	
};

JNIEXPORT jint 
JNICALL JNI_OnLoad(JavaVM* vm, void* reserved)
{
	g_JavaVM = vm;
	JNIEnv *env = NULL;

	int status = g_JavaVM->GetEnv((void **) &env, JNI_VERSION_1_6);
	if (status < 0) {
		and_log_writeline_easy(0, LOG_ERROR, "get env failure");
		return JNI_VERSION_1_6;
	}

	const char *classname = "tv/xormedia/AndCodec/CodecLib";
	initClassHelper(env, classname, &gInterfaceObject);

	and_log_writeline_simple(0, LOG_INFO, "JNI_OnLoad()");
	return JNI_VERSION_1_6;
}

void initClassHelper(JNIEnv *env, const char *path, jobject *objptr)
{
	jclass cls = env->FindClass(path);
	if(!cls) {
		and_log_writeline_easy(0, LOG_ERROR, "initClassHelper: failed to get %s class reference", path);
		return;
	}
	jmethodID constr = env->GetMethodID(cls, "<init>", "()V");
	if(!constr) {
		and_log_writeline_easy(0, LOG_ERROR, "initClassHelper: failed to get %s constructor", path);
		return;
	}
	jobject obj = env->NewObject(cls, constr);
	if(!obj) {
		and_log_writeline_easy(0, LOG_ERROR, "initClassHelper: failed to create a %s object", path);
		return;
	}
	(*objptr) = env->NewGlobalRef(obj);

	if (env->RegisterNatives(cls, aJNINativeMethod,	
		sizeof( aJNINativeMethod ) / sizeof( aJNINativeMethod[0] ) ) )
	{
		and_log_writeline_simple(0, LOG_ERROR, "initClassHelper: failed to register natives");
		return;
	}
}