#include "andc2java_c.h"
#include "easyencoder.h"
#include "easydecoder.h"
#include <stddef.h>
#include "andlog.h"

static void initClassHelper(JNIEnv *env, const char *path, jobject *objptr);

static JavaVM *g_JavaVM = NULL;
static jobject gInterfaceObject;
static int g_registered = 0;

// Main activity
jclass mActivityClass;

// method signatures
static jmethodID midOnError;
static jmethodID midOnFinish;
static jmethodID midOnInfo;
static jmethodID midOnFPS;

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

jint JNI_OnLoad(JavaVM* vm, void* reserved)
{
	g_JavaVM = vm;
	JNIEnv *env = NULL;

	int status = (*g_JavaVM)->GetEnv(g_JavaVM, (void **) &env, JNI_VERSION_1_6);
	if (status < 0) {
		and_log_writeline_easy(0, LOG_ERROR, "get env failure");
		return JNI_VERSION_1_6;
	}

	const char *classname = "tv/xormedia/AndCodec/CodecLib";
	initClassHelper(env, classname, &gInterfaceObject);
	
	and_log_writeline_simple(0, LOG_INFO, "JNI_OnLoad()");
	return JNI_VERSION_1_6;
}

int c2java_Register(JNIEnv* env, jclass clazz)
{
	if(g_registered)
		return 0;
	
	//register java callback
	mActivityClass = (jclass)(*env)->NewGlobalRef(env, clazz);
	midOnError = (*env)->GetStaticMethodID(env, mActivityClass, // GetStaticMethodID
		"OnError", "(ILjava/lang/String;)I");
	midOnFinish = (*env)->GetStaticMethodID(env, mActivityClass,
		"OnFinish", "(Ljava/lang/String;IIID)I");
	midOnInfo = (*env)->GetStaticMethodID(env, mActivityClass,
		"OnInfo", "(ILjava/lang/String;)I");
	midOnFPS = (*env)->GetStaticMethodID(env, mActivityClass,
		"OnFPS", "(ID)I");

	if (!midOnError || !midOnFinish || !midOnInfo) {
		and_log_writeline_simple(0, LOG_ERROR, 
			"Couldn't locate Java callbacks, check that they're named and typed correctly");
		return -1;
	}

	g_registered = 1;
	return 0;
}

JNIEnv * c2java_Attach()
{
	JNIEnv *envLocal = NULL;
	int status;
	status = (*g_JavaVM)->GetEnv(g_JavaVM, (void **) &envLocal, JNI_VERSION_1_6);
	if (status == JNI_EDETACHED)
	{
		and_log_writeline_simple(0, LOG_INFO, "AttachCurrentThread: JNI_EDETACHED");
		status = (*g_JavaVM)->AttachCurrentThread(g_JavaVM, &envLocal, NULL);
		if (status != JNI_OK) {
			and_log_writeline_easy(0, LOG_ERROR, "AttachCurrentThread failed %d", status);
			return NULL;
		}
	}

	and_log_writeline_simple(0, LOG_INFO, "CurrentThread Attached");
	return envLocal;
}

int c2java_Detach(JNIEnv * env)
{
	int status;

	if (!env) {
		and_log_writeline_simple(0, LOG_WARN, "No JNIEnv to Detach");
		return 0;
	}

	status = (*g_JavaVM)->DetachCurrentThread(g_JavaVM);
	if (status != JNI_OK) {
		and_log_writeline_easy(0, LOG_ERROR, "DetachCurrentThread failed %d", status);
		return -1;
	}
	
	and_log_writeline_simple(0, LOG_INFO, "CurrentThread Detached");
	return 0;
}

int c2java_OnError(JNIEnv *env, int code, const char *message)
{
	and_log_writeline_simple(0, LOG_INFO, "OnError()");
	return (*env)->CallStaticIntMethod(env, mActivityClass, midOnError, 
		-1, (*env)->NewStringUTF(env, message));
}

int c2java_OnFinish(JNIEnv *env, const char *filename, 
					int total_frames, int in_frames, int out_frames, double fps)
{
	and_log_writeline_simple(0, LOG_INFO, "OnFinish()");
	return (*env)->CallStaticIntMethod(env, mActivityClass, midOnFinish, 
		(*env)->NewStringUTF(env, filename), 
		total_frames, in_frames, out_frames, fps);
}

int c2java_OnInfo(JNIEnv *env, int code, const char *message)
{
	and_log_writeline_simple(0, LOG_DEBUG, "OnInfo()");
	return (*env)->CallStaticIntMethod(env, mActivityClass, midOnInfo, 
		-1, (*env)->NewStringUTF(env, message));
}

int c2java_OnFPS(JNIEnv *env, int frames, double fps)
{
	and_log_writeline_simple(0, LOG_INFO, "OnFPS");
	return (*env)->CallStaticIntMethod(env, mActivityClass, midOnFPS, 
		frames, fps);
}

void initClassHelper(JNIEnv *env, const char *path, jobject *objptr)
{
	jclass cls = (*env)->FindClass(env, path);
	if(!cls) {
		and_log_writeline_easy(0, LOG_ERROR, "initClassHelper: failed to get %s class reference", path);
		return;
	}
	jmethodID constr = (*env)->GetMethodID(env, cls, "<init>", "()V");
	if(!constr) {
		and_log_writeline_easy(0, LOG_ERROR, "initClassHelper: failed to get %s constructor", path);
		return;
	}
	jobject obj = (*env)->NewObject(env, cls, constr);
	if(!obj) {
		and_log_writeline_easy(0, LOG_ERROR, "initClassHelper: failed to create a %s object", path);
		return;
	}
	(*objptr) = (*env)->NewGlobalRef(env, obj);

	if ((*env)->RegisterNatives(env, cls, aJNINativeMethod,	
		sizeof( aJNINativeMethod ) / sizeof( aJNINativeMethod[0] ) ) )
	{
		and_log_writeline_simple(0, LOG_ERROR, "initClassHelper: failed to register natives");
		return;
	}
}

