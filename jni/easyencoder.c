#include "easyencoder.h"
#include "anddefs.h"
#include "andtunables.h"
#include "andparseconf.h"
#include "andsysutil.h"
#include "andlog.h"
#include "andstr.h"
#include "andfifobuffer.h"
#include "andqueue.h"
#include "codecdef.h" // for frame type
#include "andtiming_c.h"
#include <pthread.h> // for sync
#include <stdio.h> // for vsprintf

#include "andc2java_c.h"

//libx264
#include "stdint.h"
#include "x264.h"

#ifdef ENC_USE_FFMPEG
//ffmpeg
#include "libavformat/avformat.h"
#include "libswscale/swscale.h"
#include "libavutil/avutil.h"
#include "libavutil/imgutils.h"
#endif

#define ENCODER_FIFO_SIZE		1048576
#define ENCODER_FRAMESIZE_LEN	4

#define TEST_IN_WIDTH			640
#define TEST_IN_HEIGHT			480
#define TEST_IN_FMT				2	//0-BRG565, 1-RGB24, 2-NV21, 3-YUV420p

#define SMART_QUALITY_WIDTH	640
#define SMART_QUALITY_HEIGHT	480
#define SMART_NOLATENCY_QUALITY	50

#define DEFAULT_HIGH_BITRATE	400
#define DEFAULT_LOW_BITRATE		200
#define LOW_WIDTH				320

int g_abort_test = 0;

struct easy_encoder_handle
{
	int				id;
	x264_t* 		encoder;		// x264 encoder handle
	x264_param_t 	param;			// x264 encoder param struct
	x264_picture_t 	pic_in;			// x264 encode picture inner buffer
	int				in_frames;		// input frame count
	int				out_frames;		// output frame count
	int				width;
	int				height;
	int				in_fmt;
	FifoBuffer		fifo;			// store encoded stream buffer
	SimpleQueue		queue;			// store opaque data
	long			start_sec, end_sec;
	long			start_usec, end_usec;
	//sws
	int				pic_trans;		// is need transform input picture pixel format
	uint8_t*		video_src_data[4];
	int				video_src_linesize[4];
	uint8_t*		video_dst_data[4];
	int				video_dst_linesize[4];
#ifdef ENC_USE_FFMPEG
	struct SwsContext*	img_convert_ctx;
#endif
	pthread_mutex_t	mutex;		// sync add() and get()
	//...
};

#ifdef ANDROID_APP
static int convert_jstring(JNIEnv* env, char *des_str, int* len, jstring str);
#endif

static int encode_pic(easy_encoder_handle* handle, unsigned char* pData, int len, 
	unsigned char* pOpaque, int opaque_len);
static void x264_log(void *user, int level, const char *fmt, va_list vl);

static int calc_bitrate_crf_by_quality(int width, int quality, int *bitrate, int *crf);
static int calc_bitrate_by_resolution(int w, int quality);
static int calc_bitrate_static_by_resolution(int w, int quality);

char * AndCodec_EasyEncoderVersion()
{
	return AND_ENCODER_VERSION;
}

int AndCodec_EasyEncoderOpen(int w, int h, int in_fmt, 
							 const char* profile, const char* str_enc)
{
	and_log_writeline_simple(0, LOG_INFO, "AndCodec_EasyEncoderOpen()");

	//int baseline = 0; // since v1.03 set to 0(false)

	const char *profile_disp[]	= {"low", "high", "smartquality", "nolatency", "static"};// not x264 option
	const char enc_default[]		= "";
	const char* str_profile			= profile;
	const char* str_enc_settings	= str_enc;

#ifdef ANDROID_APP
	and_log_init("/mnt/sdcard/easy_encoder.log", LOG_INFO);
#else
	and_log_init("easy_encoder.log", LOG_INFO);
#endif

	if (!str_profile) {
		and_log_writeline_simple(0, LOG_INFO, "profile is null, use default profile");
		str_profile = profile_disp[1]; // high
	}
	else if (0 == and_sysutil_strlen(str_profile)) {
		and_log_writeline_simple(0, LOG_INFO, "profile is empty, use default preset");
		str_profile = profile_disp[1]; // high
	}

	int found_profile = 0;
	int i = 0;
	
	int profile_num = sizeof( profile_disp ) / sizeof( profile_disp[0] );
	while ( i < profile_num ){
		if (and_sysutil_strcmp(str_profile, profile_disp[i]) == 0) {
			found_profile = 1;		
			break;
		}
		i++;
	}

	if(!found_profile) {
		and_log_writeline_easy(0, LOG_ERROR, "input profile is invalid: %s", str_profile);
		return INVALID_HANDLE;
	}

	if (!str_enc_settings) {
		and_log_writeline_simple(0, LOG_INFO, "encode settings is null, use default encode settings: none");
		str_enc_settings = enc_default;
	}
	else if (0 == and_sysutil_strlen(str_enc_settings)) {
		and_log_writeline_simple(0, LOG_INFO, "encode settings is empty, use default encode settings: none");
		str_enc_settings = enc_default;
	}

	and_log_writeline_easy(0, LOG_INFO, "profile %s, enc_settings %s, width %d, height %d, in_fmt %d", 
		str_profile, str_enc_settings, w, h, in_fmt);
	
	easy_encoder_handle* handle = (easy_encoder_handle*)and_sysutil_malloc(sizeof(easy_encoder_handle));
	and_log_writeline_easy(0, LOG_INFO, "handle allocated :%d param %d", sizeof(easy_encoder_handle), sizeof(handle->param));
	handle->width		= w;
	handle->height		= h;
	handle->in_fmt		= in_fmt;
	handle->in_frames	= 0;
	handle->out_frames	= 0;

	int ret;

#ifdef ENC_USE_FFMPEG
	enum AVPixelFormat fmt;
	switch(handle->in_fmt) {
	case AND_PIXEL_FMT_YUV420P:
		fmt = AV_PIX_FMT_YUV420P;
		break;
	case AND_PIXEL_FMT_NV21:
		fmt = AV_PIX_FMT_NV21;
		break;
	case AND_PIXEL_FMT_BGR24:
		fmt = AV_PIX_FMT_BGR24;
		break;
	default:
		and_log_writeline_easy(0, LOG_ERROR, "wrong format %d.", handle->in_fmt);
		return INVALID_HANDLE;
	};

	if(AV_PIX_FMT_YUV420P != fmt) {
		handle->pic_trans = 1;

		ret = av_image_alloc(handle->video_src_data, handle->video_src_linesize, 
			handle->width, handle->height, fmt, 1);
		if(!ret) {
			and_log_writeline_simple(0, LOG_ERROR, "failed to alloc src image.");
			return INVALID_HANDLE;
		}

		ret = av_image_alloc(handle->video_dst_data, handle->video_dst_linesize, 
			handle->width, handle->height, AV_PIX_FMT_YUV420P, 1);
		if(!ret) {
			and_log_writeline_simple(0, LOG_ERROR, "failed to alloc des image.");
			return INVALID_HANDLE;
		}

		handle->img_convert_ctx = sws_getContext(
			handle->width, handle->height, fmt, 
			handle->width, handle->height, AV_PIX_FMT_YUV420P,
			SWS_FAST_BILINEAR, NULL, NULL, NULL);
		if(!handle->img_convert_ctx) {
			and_log_writeline_simple(0, LOG_ERROR, "failed to alloc sws context.");
			return INVALID_HANDLE;
		}
		and_log_writeline_simple(0, LOG_INFO, "transfer = yes");
	}
	else {
		handle->pic_trans = 0;
	}
#else
	if (AND_PIXEL_FMT_YUV420P != handle->in_fmt) {
		and_log_writeline_simple(0, LOG_ERROR, "ffmpeg sws was not build-in");
		return INVALID_HANDLE;
	}

	handle->pic_trans = 0;
#endif

	tunables_load_defaults();

	ret = and_parseconf_parse(str_enc_settings);
	if(ret < 0) {
		and_log_writeline_simple(0, LOG_ERROR, "failed to parse config");
		return INVALID_HANDLE;
	}

	int bitrate = 0;
	int crf = 0;
	int w_h = handle->width;
	if (handle->height > handle->width)
		w_h = handle->height;

	if (and_sysutil_strcmp(str_profile, profile_disp[0]) == 0 ||
		and_sysutil_strcmp(str_profile, profile_disp[1]) == 0 )
	{
		// calculate bitrate and crf for "low" and "high" profile
		if ( calc_bitrate_crf_by_quality(w_h, tunable_quality, &bitrate, &crf) < 0)
			return INVALID_HANDLE;

		and_log_writeline_easy(0, LOG_INFO, "width %d, quality %d: bitrate %d, crf %d",
			handle->width, tunable_quality, bitrate, crf);
		if (UINT_UNSET == tunable_crf_constant) {
			tunable_crf_constant = crf;
		}
		else {
			and_log_writeline_easy(0, LOG_INFO, "use crf set value: %d", tunable_crf_constant);
		}

		if (UINT_UNSET == tunable_crf_constant_max) {
			tunable_crf_constant_max = crf + 5;
		}
		else {
			and_log_writeline_easy(0, LOG_INFO, "use crf_max set value: %d", tunable_crf_constant_max);
		}

		if (0 == tunable_bitrate) {
			tunable_bitrate = bitrate;
		}
		else {
			and_log_writeline_easy(0, LOG_INFO, "use bitrate set value: %d", tunable_bitrate);
		}
	}

	if (and_sysutil_strcmp(str_profile, profile_disp[0]) == 0) { // low
		and_log_writeline_simple(0, LOG_INFO, "use \"low\" profile");

		x264_param_default_preset(&handle->param, "ultrafast", "zerolatency");
		x264_param_apply_profile(&handle->param, "baseline");

		handle->param.i_threads				= tunable_threads;
		handle->param.i_width				= handle->width;
		handle->param.i_height				= handle->height;
		handle->param.i_fps_num				= tunable_fps_num;
		handle->param.i_fps_den				= tunable_fps_den; //default 1
		//Intra refres:
		handle->param.i_keyint_max			= tunable_keyint_max;
		handle->param.b_intra_refresh		= 1;
		//Rate control:
		handle->param.rc.i_rc_method		= X264_RC_CRF;
		handle->param.rc.f_rf_constant		= (float)tunable_crf_constant;//22
		handle->param.rc.f_rf_constant_max	= (float)tunable_crf_constant_max;//24
		handle->param.rc.i_bitrate			= tunable_bitrate; // kbps
		handle->param.rc.i_vbv_buffer_size	= handle->param.rc.i_bitrate / 2;
		handle->param.rc.i_vbv_max_bitrate	= handle->param.rc.i_bitrate;
		//For streaming:
		handle->param.b_repeat_headers		= 1;
		handle->param.b_annexb				= 1;
	}
	else if(and_sysutil_strcmp(str_profile, profile_disp[1]) == 0) { // high
		and_log_writeline_simple(0, LOG_INFO, "use \"high\" profile");

		//install_str_setting("veryfast", &tunable_preset);
		//install_str_setting("high", &tunable_profile);

		x264_param_default_preset(&handle->param, "veryfast", "zerolatency");

		handle->param.i_threads						= tunable_threads;
		handle->param.i_width						= handle->width;
		handle->param.i_height						= handle->height;
		handle->param.i_fps_num						= tunable_fps_num;
		handle->param.i_fps_den						= tunable_fps_den;

		handle->param.i_keyint_max					= tunable_keyint_max;
		handle->param.b_intra_refresh				= 0; // 1 cause long p frame seq warn: "ref > 1 + intra-refresh is not supported"
		handle->param.i_frame_reference				= 3; // 4
		handle->param.i_bframe						= 0;
		handle->param.b_cabac						= 0; // 1
		handle->param.b_open_gop					= 0; // 1

		//Rate control:
		handle->param.rc.i_rc_method				= X264_RC_CRF;
		handle->param.rc.f_rf_constant				= (float)tunable_crf_constant; // 22
		handle->param.rc.f_rf_constant_max			= (float)tunable_crf_constant_max; // 24
		handle->param.rc.i_bitrate					= bitrate; // kbps
		handle->param.rc.i_vbv_buffer_size			= handle->param.rc.i_bitrate / 2;
		handle->param.rc.i_vbv_max_bitrate			= handle->param.rc.i_bitrate;
		//For streaming:
		handle->param.b_repeat_headers				= 1;
		handle->param.b_annexb						= 1;

	}
	else if(and_sysutil_strcmp(str_profile, profile_disp[2]) == 0) { // smartquality
		and_log_writeline_simple(0, LOG_INFO, "use \"smartquality\" profile");

		x264_param_default_preset(&handle->param, "veryfast", "zerolatency"); 	

		handle->param.i_threads						= tunable_threads;
		handle->param.i_width						= handle->width;
		handle->param.i_height						= handle->height;
		handle->param.i_fps_num						= tunable_fps_num;
		handle->param.i_fps_den						= tunable_fps_den;

		int correct_res = 0;
		if ( (handle->width == SMART_QUALITY_WIDTH && handle->height == SMART_QUALITY_HEIGHT) || 
			(handle->width == SMART_QUALITY_HEIGHT && handle->height == SMART_QUALITY_WIDTH) )
			correct_res = 1;
			
		if (!correct_res) {
			and_log_writeline_easy(0, LOG_ERROR, 
				"\"smartquality\" profile only support 640x480 resolution");
			return INVALID_HANDLE;
		}

		bitrate	= calc_bitrate_by_resolution(SMART_QUALITY_WIDTH, tunable_quality);
		if (bitrate < 0) {
			and_log_writeline_simple(0, LOG_ERROR, "failed to get bitrate");
			return INVALID_HANDLE;
		}

		if (0 == tunable_bitrate) {
			tunable_bitrate = bitrate;
		}
		else {
			and_log_writeline_easy(0, LOG_INFO, "use bitrate set value: %d", tunable_bitrate);
		}

		handle->param.i_keyint_min					= tunable_fps_num / tunable_fps_den;
		handle->param.i_keyint_max					= tunable_fps_num * 2 / tunable_fps_den;

		handle->param.i_bframe						= 16;
		handle->param.i_bframe_adaptive				= 1;
		handle->param.i_bframe_pyramid				= 1;

		handle->param.i_frame_reference				= 3; // 4
		handle->param.b_cabac						= 1;

		//bitrate
		handle->param.rc.i_rc_method				= X264_RC_ABR;
		handle->param.rc.i_bitrate					= tunable_bitrate;
		handle->param.rc.f_ip_factor				= 4.0f;
		handle->param.rc.f_pb_factor				= 1.9f;

		//anaylse
		handle->param.analyse.b_mixed_references    = 1;
		handle->param.analyse.b_transform_8x8       = 1;
		handle->param.analyse.i_me_method           = X264_ME_HEX;
		handle->param.analyse.i_me_range            = 16;
		handle->param.analyse.i_subpel_refine       = 1;
		handle->param.analyse.b_psy                 = 0;
		handle->param.analyse.i_trellis             = 1;
	}
	else if(and_sysutil_strcmp(str_profile, profile_disp[3]) == 0) { // nolatency
		and_log_writeline_simple(0, LOG_INFO, "use \"nolatency\" profile");

		x264_param_default_preset(&handle->param, "ultrafast", "zerolatency"); 	

		bitrate	= calc_bitrate_static_by_resolution(w_h, tunable_quality);
		if (bitrate < 0) {
			and_log_writeline_simple(0, LOG_ERROR, "failed to get bitrate");
			return INVALID_HANDLE;
		}

		if (0 == tunable_bitrate) {
			tunable_bitrate = bitrate;
		}
		else {
			and_log_writeline_easy(0, LOG_INFO, "use bitrate set value: %d", tunable_bitrate);
		}

		handle->param.i_threads				= tunable_threads;
		handle->param.i_width				= handle->width;
		handle->param.i_height				= handle->height;
		handle->param.i_fps_num				= tunable_fps_num;
		handle->param.i_fps_den				= tunable_fps_den;
		handle->param.i_keyint_max			= 1;
		handle->param.i_keyint_min			= 1;

		handle->param.rc.i_vbv_max_bitrate	= tunable_bitrate;
		handle->param.rc.i_vbv_buffer_size	= handle->param.rc.i_vbv_max_bitrate *
			handle->param.i_fps_num / handle->param.i_fps_den;
		handle->param.b_intra_refresh		= 1;
	}
	else if (and_sysutil_strcmp(str_profile, profile_disp[4]) == 0) { // static
		and_log_writeline_simple(0, LOG_INFO, "use \"static\" profile");

		x264_param_default_preset(&handle->param, "ultrafast", "zerolatency"); 	

		bitrate	= calc_bitrate_static_by_resolution(w_h, tunable_quality);
		if (bitrate < 0) {
			and_log_writeline_simple(0, LOG_ERROR, "failed to get bitrate");
			return INVALID_HANDLE;
		}

		if (0 == tunable_bitrate) {
			tunable_bitrate = bitrate;
		}
		else {
			and_log_writeline_easy(0, LOG_INFO, "use bitrate set value: %d", tunable_bitrate);
		}

		handle->param.i_threads				= tunable_threads;
		handle->param.i_width				= handle->width;
		handle->param.i_height				= handle->height;
		handle->param.i_fps_num				= tunable_fps_num;
		handle->param.i_fps_den				= tunable_fps_den;
		handle->param.i_keyint_max			= 1;
		handle->param.i_keyint_min			= 1;

		handle->param.rc.i_rc_method		= X264_RC_ABR;
		handle->param.rc.i_bitrate			= tunable_bitrate;
		handle->param.rc.f_rate_tolerance	= 0.1f;
		handle->param.b_intra_refresh		= 1;
	}
	else {
		and_log_writeline_easy(0, LOG_ERROR, "unknown profile: %s", str_profile);
		return INVALID_HANDLE;
	}

	//log
	handle->param.pf_log				= x264_log;
	handle->param.i_log_level 			= X264_LOG_DEBUG;

	//After this you can initialize the encoder as follows
	handle->encoder = x264_encoder_open(&handle->param);
	if (!handle->encoder) {
		and_log_writeline_simple(0, LOG_ERROR, "failed to open encoder");
		return INVALID_HANDLE;
	}
	
	ret = x264_picture_alloc(&handle->pic_in, X264_CSP_I420, w, h);
	if (0 != ret) {
		and_log_writeline_simple(0, LOG_ERROR, "failed to alloc picture");
		return INVALID_HANDLE;
	}
	handle->pic_in.opaque = (filesize_t *)and_sysutil_malloc(sizeof(filesize_t));
	
	ret = and_fifo_create(&handle->fifo, ENCODER_FIFO_SIZE);
	if (ret < 0) {
		and_log_writeline_simple(0, LOG_ERROR, "failed to create fifo");
		return INVALID_HANDLE;
	}
	
	ret = and_queue_init(&handle->queue, OPAQUE_DATA_LEN, QUEUE_SIZE);
	if (ret < 0) {
		and_log_writeline_simple(0, LOG_ERROR, "failed to create queue");
		return INVALID_HANDLE;
	}

	ret = pthread_mutex_init(&handle->mutex, 0);
	if (ret < 0) {
		and_log_writeline_simple(0, LOG_ERROR, "failed to create mutex");
		return INVALID_HANDLE;
	}
	
	// start to calculate encode time
	handle->start_sec	= and_sysutil_get_time_sec();
	handle->start_usec	= and_sysutil_get_time_usec(); 

	and_log_writeline_easy(0, LOG_INFO, "open encoder handle %p, encoder %p", 
		handle, handle->encoder);
	
	return (int)handle;
}


int AndCodec_EasyEncoderAdd(int enc, unsigned char* picdata, int picdata_size, unsigned char* opaque, int opaque_len)
{
	and_log_writeline_easy(0, LOG_DEBUG, "AndCodec_EasyEncoderAdd opaque_len:%d", opaque_len);

	if(!enc) {
		and_log_writeline_simple(0, LOG_ERROR, "encoder handle is null");
		return -1;
	}
	if(!picdata) {
		and_log_writeline_simple(0, LOG_ERROR, "picture data is null");
		return -1;
	}

	easy_encoder_handle* handle = (easy_encoder_handle *)enc;
	return encode_pic(handle, picdata, picdata_size, opaque, opaque_len);
}


int AndCodec_EasyEncoderGet(int enc, unsigned char* encdata, 
							unsigned char* opaque, int *opaque_len)
{
	and_log_writeline_easy(0, LOG_DEBUG, "AndCodec_EasyEncoderGet.");

	if(!enc) {
		and_log_writeline_simple(0, LOG_ERROR, "encoder handle is null");
		return -1;
	}
	if(!encdata) {
		and_log_writeline_simple(0, LOG_ERROR, "encoded data is null");
		return -1;
	}

	easy_encoder_handle* handle = (easy_encoder_handle *)enc;	

	pthread_mutex_lock(&handle->mutex);

	int readed = -1;
	int frame_size;
	int ret;
	OpaqueData opaque_data;
	
	if (and_fifo_used(&handle->fifo) < ENCODER_FRAMESIZE_LEN) {
		readed = 0;
		goto exit;
	}

	readed = and_fifo_read(&handle->fifo, (char *)&frame_size, ENCODER_FRAMESIZE_LEN);
	and_log_writeline_easy(0, LOG_DEBUG, "frame size %d", frame_size);
	readed = and_fifo_read(&handle->fifo, (char *)encdata, frame_size);
	if (readed < frame_size) {
		and_log_writeline_easy(0, LOG_ERROR, "frame data is corrupt %d.%d", frame_size, readed);
		readed = -1;
		goto exit;
	}
	
	ret = and_queue_get(&handle->queue, (void *)&opaque_data);
	if(ret < 0) {
		readed = -1;
		goto exit;
	}

	// add frame info
	int nType = encdata[4] & 0x1F;
	if ( nType <= H264NT_PPS ) {
		switch(nType) {
		case H264NT_SLICE_IDR:
		case H264NT_SPS:
			opaque_data.uchar_d0 = 1; //I frames
			and_log_writeline_simple(0, LOG_DEBUG, "I frame");
			break;
		case H264NT_SLICE:
			opaque_data.uchar_d0 = 2; //P frames
			and_log_writeline_simple(0, LOG_DEBUG, "P frame");
			break;
		case H264NT_SLICE_DPA:
		case H264NT_SLICE_DPB:
		case H264NT_SLICE_DPC:
			opaque_data.uchar_d0 = 3; //B frames
			and_log_writeline_simple(0, LOG_DEBUG, "B frame");
			break;
		default:
			opaque_data.uchar_d0 = 0; //unknown frames
			and_log_writeline_simple(0, LOG_INFO, "unknown frame");
			break;
		}
	}
	else {
		and_log_writeline_easy(0, LOG_ERROR, "h264 encoded data was corrupted %d", nType);
		readed = -1;
		goto exit;
	}
		
	and_sysutil_memcpy(opaque, (void *)&opaque_data, OPAQUE_DATA_LEN);
	if(opaque_len) {
		*opaque_len = OPAQUE_DATA_LEN;
	}

exit:
	pthread_mutex_unlock(&handle->mutex);
	return readed;
}

void AndCodec_EasyEncoderClose(int enc)
{
	and_log_writeline_simple(0, LOG_INFO, "EasyEncoderClose enter");

	easy_encoder_handle* handle = (easy_encoder_handle *)enc;	
	and_log_writeline_easy(0, LOG_INFO, "encoder handle %x, encoder %x", handle, handle->encoder);

	if(handle->encoder) {
		and_log_writeline_simple(0, LOG_INFO, "EasyEncoderClose before encoder close.");
		x264_encoder_close(handle->encoder);

		and_log_writeline_simple(0, LOG_INFO, "EasyEncoderClose. before pic free.");
		if(handle->pic_in.opaque)
			and_sysutil_free(handle->pic_in.opaque);
		x264_picture_clean(&handle->pic_in);

		and_log_writeline_simple(0, LOG_INFO, "EasyEncoderClose. before fifo & queue free.");
		and_fifo_close(&handle->fifo);
		and_queue_close(&handle->queue);
		and_log_writeline_simple(0, LOG_INFO, "EasyEncoderClose. after fifo & queue free.");

#ifdef ENC_USE_FFMPEG
		if(handle->img_convert_ctx)
			sws_freeContext(handle->img_convert_ctx);
		if(handle->video_src_data[0])
			av_free(handle->video_src_data[0]);
		if(handle->video_dst_data[0])
			av_free(handle->video_dst_data[0]);
#endif
		pthread_mutex_destroy(&handle->mutex);
	}
	and_sysutil_free(handle);

	and_log_writeline_simple(0, LOG_INFO, "EasyEncoder Closed");
	and_log_close();
}

double AndCodec_EasyEncoderGetFPS(int enc)
{
	and_log_writeline_simple(0, LOG_DEBUG, "AndCodec_EasyEncoderGetFPS()");

	easy_encoder_handle* handle = (easy_encoder_handle *)enc;

	double elapsed;
	double fps;

	handle->end_sec		= and_sysutil_get_time_sec();
	handle->end_usec	= and_sysutil_get_time_usec();
	elapsed = (double) (handle->end_sec - handle->start_sec);
	elapsed += (double) (handle->end_usec - handle->start_usec) /
		(double) 1000000;

	if (elapsed <= 0.01)
		elapsed = 0.01f;

	fps = (double)handle->out_frames / elapsed;
	and_log_writeline_easy(0, LOG_DEBUG, "fps: %.2f(%d frames/%.3f sec)",
		fps, handle->out_frames, elapsed);

	return fps;
}

int AndCodec_EasyEncoderTest2(const char *input_file, const char *output_file, 
							  int w, int h, const char *preset, const char* enc_str, 
							  int frames, int is_writefile, int is_writesize)
{
	and_log_writeline_simple(0, LOG_INFO, "AndCodec_EasyEncoderTest2()");

	and_log_writeline_easy(0, LOG_INFO, "in %s, out %s, preset %s, settings %s, "
		"frame %d, writefile %c, writesize %c", 
		input_file, output_file, 
		preset, enc_str, 
		frames, 
		is_writefile ? 'y' : 'n', 
		is_writesize ? 'y' : 'n');

	int64_t handle;
	int in_fmt;
	int id = 100;
	int in_fd, out_fd;
	int ret;
	unsigned int out_x264_size;
	int frame_size;
	filesize_t	opqaue;
	int opq_size = OPAQUE_DATA_LEN;

	in_fmt		= TEST_IN_FMT;
	char *pic	= NULL;
	char *h264	= NULL;

#ifdef C_SIDE_TEST
	// attach java thread
	JNIEnv *env = NULL;
	env = c2java_Attach();
	if(!env) {
		and_log_writeline_simple(0, LOG_ERROR, "failed to attach thread");
		return -1;
	}
#endif

	handle  = AndCodec_EasyEncoderOpen(w, h, in_fmt, preset, enc_str);
	if (-1 == handle) {
		and_log_writeline_simple(id, LOG_ERROR, "failed to open encoder handle");
#ifdef C_SIDE_TEST	
		c2java_OnError(env, -1, "failed to open encoder");
#endif
		return -1;
	}

	in_fd = out_fd = -1;
	in_fd = and_sysutil_open_file(input_file, kANDSysUtilOpenReadOnly);
	if(in_fd < 0) {
		and_log_writeline_easy(id, LOG_ERROR, "failed to open input: %s", input_file);	
		return -1;
	}

	if(is_writefile) {
		out_fd = and_sysutil_create_or_open_file(output_file, 0644);
		if(out_fd < 0) {
			and_log_writeline_easy(id, LOG_ERROR, "failed to open output: %s", output_file);	
			return -1;
		}
		and_sysutil_ftruncate(out_fd);
		and_sysutil_lseek_to(out_fd, 0);
	}

	switch(in_fmt) {
	case 2://nv21
	case 3://yuv420p
		frame_size = w * h * 3 / 2;
		break;
	default:
		and_log_writeline_easy(id, LOG_ERROR, "wrong in_fmt: %d", in_fmt);
		return -1;
	}

	pic = (char *)and_sysutil_malloc(frame_size);
	if(!pic) {
		and_log_writeline_simple(id, LOG_ERROR, "failed to malloc picture");
		return -1;
	}

	h264 = (char *)and_sysutil_malloc(ENCODER_FIFO_SIZE / 4);
	if(!h264) {
		and_log_writeline_simple(id, LOG_ERROR, "failed to malloc enc data");
		return -1;
	}

	int i;
	int in_frames, out_frames;

	//0-total, 1-read, 2-add, 3-write
	AndTicker tick[4];
	double total_msec[4];
	double max_msec[4];
	double elapsed;
	double fps;

	for(i=0;i<4;i++) {
		and_sysutil_memclr((void *)&tick[i], sizeof(tick[0]));
		total_msec[i]	= 0.0f;
		max_msec[i]		= 0.0f;
	}

	and_log_writeline_simple(id, LOG_INFO, "start encoding...");
	in_frames = out_frames = 0;
	g_abort_test = 0;
	and_ticker_reset(&tick[0]);

	for (i=0 ; i<frames ; i++) {
		if (g_abort_test) {
			and_log_writeline_simple(0, LOG_INFO, "encode test was aborted");		
			break;
		}

		and_ticker_reset(&tick[0]);

		and_ticker_reset(&tick[1]);
		ret = and_sysutil_read(in_fd, pic, frame_size);
		if (ret < 0) {
			and_log_writeline_simple(0, LOG_ERROR, "failed to read in file");
			break;
		}

		elapsed = and_ticker_msec(&tick[1]);
		if (elapsed > max_msec[1])
			max_msec[1] = elapsed;
		total_msec[1] += elapsed;

		if(ret < frame_size) {
			and_log_writeline_simple(0, LOG_INFO, "in file re-seek to begin");
			and_sysutil_lseek_to(in_fd, 0);
		}

		opqaue = i;
		and_ticker_reset(&tick[2]);
		ret = AndCodec_EasyEncoderAdd(handle, (unsigned char *)pic, frame_size, 
			(unsigned char *)&opqaue, opq_size);
		if(ret < 0) {
			and_log_writeline_easy(id, LOG_ERROR, "failed to add in #%d", i);
			break;
		}

		elapsed = and_ticker_msec(&tick[2]);
		if (elapsed > max_msec[2])
			max_msec[2] = elapsed;
		total_msec[2] += elapsed;

		and_log_writeline_easy(id, LOG_INFO, "add pic #%d", i);
		in_frames++;

		//get
		ret = AndCodec_EasyEncoderGet(handle, (unsigned char *)h264, 
			(unsigned char *)&opqaue, &opq_size);
		if(ret < 0) {
			and_log_writeline_easy(id, LOG_ERROR, "failed to get in #%d", i);
			break;
		}
		if (ret > 0) {
			out_frames++;
			if(out_frames % 5 == 0) {
				//update java ui
				fps = AndCodec_EasyEncoderGetFPS(handle);
#ifdef C_SIDE_TEST
				c2java_OnFPS(env, out_frames, fps);
#else
				(void)fps;
#endif
			}

			if (is_writefile) {
				out_x264_size = (unsigned int)ret;
				and_ticker_reset(&tick[3]);
				if(is_writesize) {
					ret = and_sysutil_write(out_fd, &out_x264_size, ENCODER_FRAMESIZE_LEN);
					if(ret != ENCODER_FRAMESIZE_LEN) {
						and_log_writeline_easy(id, LOG_ERROR, "failed to write size in #%d %d.%d", 
							out_frames, ret, ENCODER_FRAMESIZE_LEN);
						break;
					}
				}
				ret = and_sysutil_write(out_fd, h264, out_x264_size);
				if(ret != out_x264_size) {
					and_log_writeline_easy(id, LOG_ERROR, "failed to write file in #%d %d.%d", 
						out_frames, ret, out_x264_size);
					break;
				}
				elapsed = and_ticker_msec(&tick[3]);
				if (elapsed > max_msec[3])
					max_msec[3] = elapsed;
				total_msec[3] += elapsed;
				and_log_writeline_easy(id, LOG_INFO, "write dump %d in #%d", 
					out_x264_size, out_frames);
			}

			elapsed = and_ticker_msec(&tick[0]);
			if (elapsed > max_msec[0])
				max_msec[0] = elapsed;
			total_msec[0] += elapsed;
		}
	}

	fps = AndCodec_EasyEncoderGetFPS(handle);

	and_log_writeline_easy(0, LOG_INFO, "avg/max(msec): all %.f/%.f, r %.1f/%.1f, "
		"add %.f/%.f, w %.1f/%.1f",
		total_msec[0] / (double)out_frames, max_msec[0],
		total_msec[1] / (double)out_frames, max_msec[1],
		total_msec[2] / (double)out_frames, max_msec[2],
		total_msec[3] / (double)out_frames, max_msec[3]);

#ifdef C_SIDE_TEST
	c2java_OnFinish(env, output_file, i, in_frames, out_frames, fps);
#endif

	if (in_fd > 0)
		and_sysutil_close(in_fd);
	if (out_fd > 0)
		and_sysutil_close(out_fd);
	if (pic)
		and_sysutil_free(pic);
	if (h264)
		and_sysutil_free(h264);

	AndCodec_EasyEncoderClose(handle);
	return 0;
}

JNIEXPORT int
Java_tv_xormedia_AndCodec_CodecLib_EasyEncoderTestAbort()
{
	g_abort_test = 1;
	return 0;
}

void AndCodec_EasyEncoderTest(const char* input_file, const char* output_file, const char* preset, int frames, int is_writefile)
{
	and_log_writeline_easy(0, LOG_INFO, "in: %s, out %s, preset %s, write_file %d", 
		input_file, output_file, preset, is_writefile);
	
	//x264 sample
	int width = 320;
	int height = 240;
	int fps = 25;
	int ret;
	
	x264_param_t param;
	x264_param_default_preset(&param, preset, "zerolatency");
	param.i_threads = 1;
	param.i_width	= width;
	param.i_height	= height;
	param.i_fps_num = fps;
	param.i_fps_den	= 1;
	//Intra refres:
	param.i_keyint_max = 25;
	param.b_intra_refresh = 1;
	//Rate control:
	param.rc.i_rc_method = X264_RC_CRF;//X264_RC_ABR
	param.rc.f_rf_constant =25;
	param.rc.f_rf_constant_max =20;
	param.rc.i_bitrate			= 200;//kbps
	param.rc.i_vbv_buffer_size	= param.rc.i_bitrate / 2;
	param.rc.i_vbv_max_bitrate	= param.rc.i_bitrate;
	//For streaming:
	param.b_repeat_headers =1;
	param.b_annexb =1;
	x264_param_apply_profile(&param, "baseline");
	
	//After this you can initialize the encoder as follows
	x264_t* encoder = x264_encoder_open(&param);
	if(!encoder) {
		and_log_writeline_simple(0, LOG_ERROR, "failed to open encoder");
		return;
	}
	x264_picture_t pic_in, pic_out;
	ret = x264_picture_alloc(&pic_in, X264_CSP_I420, width, height);
	if(0 != ret) {
		and_log_writeline_simple(0, LOG_ERROR, "failed to alloc picture");
		return;
	}

	x264_nal_t* nals;
	int i_nals;
	int frame_size;
	int in_frame, out_frame;
	
	int to_read, readed;
	int yuv_fd;
	yuv_fd = and_sysutil_open_file(input_file, kANDSysUtilOpenReadOnly);
	if(yuv_fd < 0) {
		and_log_writeline_easy(0, LOG_ERROR, "failed to open yuv file: %s", input_file);
		return;
	}
	
	int written;
	int x264_fd;
	x264_fd = and_sysutil_create_or_open_file(output_file, 0644);
	if(x264_fd < 0) {
		and_log_writeline_easy(0, LOG_ERROR, "failed to open h264 file: %s", output_file);
		return;
	}

	in_frame = out_frame = 0;
	while (in_frame < frames) {
		//fill pic data
		int j=0;
		int factor;
		while (j<3) {
			if (0 == j)
				factor = 1;
			else
				factor = 2;
			to_read = pic_in.img.i_stride[j] * height / factor;
			readed = and_sysutil_read(yuv_fd, pic_in.img.plane[j], to_read);
			if (readed != to_read) {
				and_log_writeline_easy(0, LOG_ERROR, "failed to read YUV #%d, %d.%d",
					in_frame, to_read, readed);
				break;
			}
			j++;
		}
			
			
		frame_size = x264_encoder_encode(encoder, &nals, &i_nals, &pic_in, &pic_out);
		if (frame_size < 0) {
			and_log_writeline_easy(0, LOG_ERROR, "failed to encode in #%d", in_frame);
			break;
		}
		else if (frame_size > 0)
		{
			written = and_sysutil_write(x264_fd, (void *)nals[0].p_payload, frame_size);
			if (written != frame_size) {
				and_log_writeline_easy(0, LOG_ERROR, "failed to write H264 #%d, %d.%d",
					in_frame, frame_size, written);
				break;
			}
			and_log_writeline_easy(0, LOG_DEBUG, "dump out[%] size %d ,nal %d", 
				out_frame++, frame_size, i_nals);
		}
		in_frame++;
	}

	while(1) {
		frame_size = x264_encoder_encode(encoder, &nals, &i_nals, NULL, &pic_out);
		if (frame_size < 0) {
			and_log_writeline_easy(0, LOG_ERROR, "failed to encode(flush) in #%d", in_frame);
			break;
		}
		else if (0 == frame_size) {
			and_log_writeline_simple(0, LOG_INFO, "flush finished");
			break;
		}
		else
		{
			if(is_writefile) {
				written = and_sysutil_write(x264_fd, (void *)nals[0].p_payload, frame_size);
				if(written != frame_size) {
					and_log_writeline_easy(0, LOG_ERROR, "failed to write file(flush) #%d, ret=%d",
						in_frame, written);
					break;
				}
			}
			and_log_writeline_easy(0, LOG_DEBUG, "dump out(flush)[%d] size %d, nal %d", 
				out_frame++, frame_size, i_nals);
		}
	}

	x264_picture_clean(&pic_in);
	x264_encoder_close(encoder);
	encoder = NULL;
	
	and_sysutil_close(yuv_fd);
	and_sysutil_close(x264_fd);
	and_log_writeline_easy(0, LOG_INFO, "encoded done! in %d, out %d", in_frame, out_frame);
	and_log_close();
}

#ifdef ANDROID_APP
int 
Java_tv_xormedia_AndCodec_CodecLib_EasyEncoderOpen(JNIEnv* env, jobject thiz,
													int w, int h, int in_fmt, 
													jstring profile, jstring enc_str)
{
	and_log_writeline_simple(0, LOG_INFO, "EasyEncoderOpen()");
	
	//parse input and output filename
	char str_profile[256]	= {0};//preset
	char str_enc[256]		= {0};
	int str_len 			= 256;
	
	convert_jstring(env, str_profile, &str_len, profile);
	convert_jstring(env, str_enc, &str_len, enc_str);
	
	return AndCodec_EasyEncoderOpen(w, h, in_fmt, str_profile, str_enc);
}

int 
Java_tv_xormedia_AndCodec_CodecLib_EasyEncoderAdd(JNIEnv* env, jobject thiz,
												int enc, jobject picdata, int picdata_size, jobject opaque)
{
	and_log_writeline_simple(0, LOG_DEBUG, "EasyEncoderAdd()");
#ifdef USE_NATIVE_IO
	unsigned char *p_pic = (*env)->GetDirectBufferAddress(env, picdata);
	if(!p_pic) {
		and_log_writeline_easy(0, LOG_ERROR, "failed to get direct addr");
		return -1;
	}
	and_log_writeline_easy(0, LOG_INFO, "add native io addr %p", p_pic);
	jlong pic_size = (*env)->GetDirectBufferCapacity(env, picdata);
#else
	jbyte* p_pic = (*env)->GetByteArrayElements(env, picdata, NULL);
	jsize pic_size = (*env)->GetArrayLength(env, opaque);
#endif
	and_log_writeline_easy(0, LOG_DEBUG, "add size %d", pic_size);

	jbyte* p_opaque =  (*env)->GetByteArrayElements(env, opaque, NULL);
	
	jsize opaque_len = (*env)->GetArrayLength(env, opaque);
	int n =	AndCodec_EasyEncoderAdd(enc, (unsigned char *)p_pic, picdata_size,
		(unsigned char *)p_opaque, (int)opaque_len);

#ifndef USE_NATIVE_IO
	(*env)->ReleaseByteArrayElements(env, picdata, p_pic, 0);
#endif
    (*env)->ReleaseByteArrayElements(env, opaque,  p_opaque, 0);
	return n;
}

int 
Java_tv_xormedia_AndCodec_CodecLib_EasyEncoderGet(JNIEnv* env, jobject thiz,
												int enc, jobject encdata, jobject opaque)
{
	and_log_writeline_simple(0, LOG_DEBUG, "EasyEncoderGet()");
#ifdef USE_NATIVE_IO
	unsigned char *p_data = (*env)->GetDirectBufferAddress(env, encdata);
	if(!p_data) {
		and_log_writeline_simple(0, LOG_ERROR, "failed to get direct addr");
		return -1;
	}
	and_log_writeline_easy(0, LOG_INFO, "get addr %p", p_data);
	jlong data_size = (*env)->GetDirectBufferCapacity(env, encdata);
#else
	jbyte* p_data = (*env)->GetByteArrayElements(env, encdata, NULL);
	jsize data_size = (*env)->GetArrayLength(env, encdata);
#endif
	and_log_writeline_easy(0, LOG_DEBUG, "get size %d", data_size);

	jbyte* p_opaque =  (*env)->GetByteArrayElements(env, opaque, NULL);
	
	int opaque_len = (*env)->GetArrayLength(env, opaque);

	int n = AndCodec_EasyEncoderGet(enc, (unsigned char *)p_data, 
		(unsigned char *)p_opaque, &opaque_len);
#ifndef USE_NATIVE_IO
	(*env)->ReleaseByteArrayElements(env, encdata, p_data, 0);
#endif
	(*env)->ReleaseByteArrayElements(env, opaque, p_opaque, 0);
	return n;
}

void 
Java_tv_xormedia_AndCodec_CodecLib_EasyEncoderClose(JNIEnv* env, jobject thiz, int enc)
{
	and_log_writeline_easy(0, LOG_INFO, "EasyEncoderClose() enc %d", enc);
	return AndCodec_EasyEncoderClose(enc);
}

double 
Java_tv_xormedia_AndCodec_CodecLib_EasyEncoderGetFPS(JNIEnv* env, jobject thiz, int enc)
{
	//and_log_writeline_easy(0, LOG_DEBUG, "EasyEncoderGetFPS() enc %d", enc);
	
	return AndCodec_EasyEncoderGetFPS(enc);
}

int 
Java_tv_xormedia_AndCodec_CodecLib_EasyEncoderTest(JNIEnv* env, jclass clazz, // jobject thiz,
												   jstring input_file, jstring output_file, 
												   int w, int h,
												   jstring preset, jstring enc_str, 
												   int frames, 
												   int is_writefile, int is_writesize)
{
	and_log_init("/mnt/sdcard/easy_encoder.log", LOG_INFO);
#ifdef C_SIDE_TEST	
	if ( c2java_Register(env, clazz) < 0) {
		and_log_writeline_simple(0, LOG_ERROR, "failed to register function");
		return -1;
	}
#endif

	and_log_writeline_simple(0, LOG_INFO, "EasyEncoderTest()");
	
	const char* prefix = "/mnt/sdcard";

	//parse input and output filename
	char str_in_file[STR_MAX_LEN * 2]	= {0};
	char str_out_file[STR_MAX_LEN * 2]	= {0};
	
	char str_preset[STR_MAX_LEN] 		= {0};
	char str_enc_str[STR_MAX_LEN]		= {0};

	char str_tmp[STR_MAX_LEN]			= {0};
	
	int str_len = STR_MAX_LEN;
	
	convert_jstring(env, str_tmp, &str_len, input_file);
	sprintf(str_in_file, "%s%s", prefix, str_tmp);
	convert_jstring(env, str_tmp, &str_len, output_file);
	sprintf(str_out_file, "%s%s", prefix, str_tmp);

	convert_jstring(env, str_preset, &str_len, preset);
	convert_jstring(env, str_enc_str, &str_len, enc_str);
	
	return AndCodec_EasyEncoderTest2(str_in_file, str_out_file, 
		w, h, str_preset, str_enc_str, frames, is_writefile, is_writesize);
}

static int convert_jstring(JNIEnv* env, char *des_str, int* len, jstring str)
{
	const char *nativeString = (*env)->GetStringUTFChars(env, str, 0);     
	and_sysutil_strcpy(des_str, nativeString, *len);
	(*env)->ReleaseStringUTFChars(env, str, nativeString);
	
	return 0;
}
#endif

static int encode_pic(easy_encoder_handle* handle, unsigned char* pData, int len, 
	unsigned char* pOpaque, int opaque_len)
{
	and_log_writeline_simple(0, LOG_DEBUG, "encode_pic");
	
	pthread_mutex_lock(&handle->mutex);
	
	(void)opaque_len;
	int factor;
	int offset;
	int to_read;
	int written;
	x264_nal_t* nals;
	int i_nals;
	int out_size = -1;
	x264_picture_t* p_pic;
	int ret;
	x264_picture_t	picout;
	int j;

	if (pData && len > 0) {//fix me
		if(handle->pic_trans) { //nv21 or bgr24
#ifndef ENC_USE_FFMPEG
			and_log_writeline_simple(0, LOG_ERROR, "failed to do sws, ffmpeg was not build-in.");
			goto exit;
#else
			and_log_writeline_simple(0, LOG_DEBUG, "start to do sws.");
			switch (handle->in_fmt) {
			case AND_PIXEL_FMT_NV21:
				and_sysutil_memcpy(handle->video_src_data[0], pData, 
					handle->video_src_linesize[0] * handle->height);
				and_sysutil_memcpy(handle->video_src_data[1], 
					pData + handle->video_src_linesize[0] * handle->height, 
					handle->video_src_linesize[1] * handle->height / 2);
				break;
			case AND_PIXEL_FMT_BGR24:
				and_sysutil_memcpy(handle->video_src_data[0], pData, 
					handle->video_src_linesize[0] * handle->height);
				break;
			default:
				and_log_writeline_easy(0, LOG_ERROR, "wrong pixel fmt %d to do sws", handle->in_fmt);
				goto exit;
			}

			ret = sws_scale(handle->img_convert_ctx, 
				(uint8_t const**)handle->video_src_data, handle->video_src_linesize,
				0, handle->height,
				handle->video_dst_data, handle->video_dst_linesize);
			if(ret != handle->height) {
				and_log_writeline_easy(0, LOG_ERROR, "failed to do sws %d.%d",
					ret, handle->height, handle->video_src_linesize[0], 
					handle->video_dst_linesize[0]);
				goto exit;
			}
			and_log_writeline_simple(0, LOG_DEBUG, "scale end.");
			
			// alway copy to yuv420p x264 pic struct
			for(j=0; j<3 ;j++) {
				if(0 == j)
					factor = 1;
				else
					factor = 2;
				to_read = handle->video_dst_linesize[j] * handle->height / factor;
				and_sysutil_memcpy(handle->pic_in.img.plane[j], handle->video_dst_data[j], to_read);
			}
#endif
		}
		else { //yuv420p
			offset = 0;
			for(j=0;j<3;j++) {
				if(0 == j)
					factor = 1;
				else
					factor = 2;
				to_read = handle->pic_in.img.i_stride[j] * handle->height / factor;
				if(offset + to_read > len) {
					and_log_writeline_easy(0, LOG_ERROR, "image data length is invalid #%d %d.%d", 
						handle->in_frames, offset + to_read, len);
				}
				and_sysutil_memcpy(handle->pic_in.img.plane[j], pData + offset, to_read);
				offset += to_read;
			}
		}

		p_pic = &handle->pic_in;
	}
	else { // encode flush case
		p_pic = 0;
	}
	
	and_log_writeline_simple(0, LOG_DEBUG, "after copy data");	

	// mark frame timestamp
	/*if(p_pic->opaque) {
		and_sysutil_memcpy(p_pic->opaque, pOpaque, sizeof(filesize_t));
	}*/

	out_size = x264_encoder_encode(handle->encoder, &nals, &i_nals, p_pic, &picout);
	if (out_size < 0) {
		and_log_writeline_easy(0, LOG_ERROR, "failed to encode in #%d, out_size %d", 
			handle->in_frames, out_size);
		out_size = -1;
		goto exit;
	}

	and_log_writeline_easy(0, LOG_DEBUG, "encode #%d", handle->in_frames);
	handle->in_frames++;
	
	if (out_size > 0)
	{
		written = and_fifo_write(&handle->fifo, (char *)&out_size, ENCODER_FRAMESIZE_LEN);
		if (written != ENCODER_FRAMESIZE_LEN) {
			and_log_writeline_easy(0, LOG_ERROR, "fifo overflowed #%d %d.%d", 
				handle->out_frames, ENCODER_FRAMESIZE_LEN, written);
			out_size = -1;
			goto exit;
		}
		
		written = and_fifo_write(&handle->fifo, (char *)nals[0].p_payload, out_size);
		if (written < out_size) {
			and_log_writeline_easy(0, LOG_WARN, "fifo overflowed #%d %d.%d", 
				handle->out_frames, out_size, written);
		}
		if ( and_queue_put(&handle->queue, (void *)pOpaque) <0)
			goto exit;
		
		and_log_writeline_easy(0, LOG_DEBUG, "dump out[%d] size %d, nal %d", 
			handle->out_frames, written, i_nals);
		handle->out_frames++;
	}
	else {
		and_log_writeline_easy(0, LOG_WARN, "no data out in #%d", handle->in_frames);
	}

exit:
	pthread_mutex_unlock(&handle->mutex);
	return out_size;
}

static void x264_log(void *user, int level, const char *fmt, va_list vl)
{
	(void)user;
	static char szLog[STRING_BUF_LEN] = {0};

	vsprintf(szLog, fmt, vl);

	enum loglevel lvl;
	switch(level) {
		case X264_LOG_ERROR:
			lvl = LOG_ERROR;
			break;
		case X264_LOG_WARNING:
			lvl = LOG_DEBUG;
			break;
		case X264_LOG_INFO:
			lvl = LOG_DEBUG;
			break;
		case X264_LOG_DEBUG:
			lvl = LOG_DEBUG;
			break;
		default:
			lvl = LOG_DEBUG;
			break;
	}
	and_log_writeline_easy(0, lvl, "x264(%d): %s", level, szLog);
}

static int calc_bitrate_crf_by_quality(int width, int quality, int *bitrate, int *crf)
{
	if( quality <= 0 || quality > 100) {
		and_log_writeline_easy(0, LOG_ERROR, "invalid quality input: %d", quality);
		return -1;
	}

	//profile #24    320x240
	const int qvga_bitrate[]  = { 100, 200, 400, 800 };      //1600 upper limit
	const int qvga_crf_base[] = { 24, 20, 15, 11 };   

	//profile #25   640x480
	const int vga_bitrate[]   = { 200, 400, 800, 1600 };    //3200 upper limit
	//const int vga_crf_base[]  = { 26, 21, 18, 14 };
	const int vga_crf_base[]  = { 26, 21, 18, 14 };

	const int level_count     = 4;
	const int bitrate_level[] = { 25, 50, 75, 100 };

	int sub_quality = 0;
	int i;
	for (i = 0; i < level_count; i++) {
		if(quality <= bitrate_level[i])
		{
			//sub_quality = (bitrate_level[i] - quality) * 3 / 5 ;
			sub_quality = (bitrate_level[i] - quality) * 9 / 25 ;

			if(width == 320) //profile #24
			{
				*bitrate =  qvga_bitrate[i];
				*crf     = sub_quality + qvga_crf_base[i];
			}
			else if(width == 640) //profile #25
			{
				*bitrate = vga_bitrate[i];
				*crf     = sub_quality + vga_crf_base[i];
			}
			else   //720p
			{
				*bitrate = 4000;
				*crf     = 23;
			}

			break;
		}
	}

	return 0;
}

static int calc_bitrate_by_resolution(int w, int quality)
{
	int bitrate = 0;

	if (w != SMART_QUALITY_WIDTH) {
		and_log_writeline_easy(0, LOG_ERROR, "only support for 640x480: %d", w);
		return -1;
	}

	if (quality == 50)
		bitrate				= 650;
	else if (quality < 50)
		bitrate				= (int)((650 - 400) / 48 * (quality - 1)) + 400;
	else
		bitrate				= (int)((2000 - 650) / 49 * (quality - 51)) + 650;

	and_log_writeline_easy(0, LOG_INFO, "no latency: input width %d, quality %d, bitrate %d", 
		w, quality, bitrate);
	return bitrate;
}


static int calc_bitrate_static_by_resolution(int w, int quality)
{
	int bitrate = 0;

	if (w <= 320) { // 25-128k bps, 40-256k
		bitrate = 850  * quality / 100 - 85;
		if(bitrate < 100)
			bitrate = 100;
	}
	else if (w <= 640){ // 200k - 1.6M bps
		bitrate = 1400 * quality / 100 + 200;
	}
	else {	// 800k - 4M bps
		bitrate = 3200 * quality / 100 + 800;
	}

	and_log_writeline_easy(0, LOG_INFO, "static: input width %d, quality %d, bitrate %d", 
		w, quality, bitrate);
	return bitrate;
}


