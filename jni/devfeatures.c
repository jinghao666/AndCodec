#include "devfeatures.h"
#include <cpu-features.h>
#include "andlog.h"

int AndCodec_IsSupportNeon()
{
	uint64_t features;
	
	if (android_getCpuFamily() != ANDROID_CPU_FAMILY_ARM) {
		and_log_writeline_simple(0, LOG_INFO, "Not an ARM CPU !");
		return 0;
	}

	features = android_getCpuFeatures();

	if ((features & ANDROID_CPU_ARM_FEATURE_ARMv7) == 0) {
		and_log_writeline_simple(0, LOG_INFO, "Not an ARMv7 CPU !");
		return 0;
	}

	if ((features & ANDROID_CPU_ARM_FEATURE_NEON) == 0) {
		and_log_writeline_simple(0, LOG_INFO, "CPU doesn't support NEON !");
		return 0;
	}

	return 1;
}

int 
Java_tv_xormedia_AndCodec_CodecLib_IsSupportNeon()
{
	return AndCodec_IsSupportNeon();
}
