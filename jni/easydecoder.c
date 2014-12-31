#include "easydecoder.h"
#include "anddefs.h"
#include "andsysutil.h"
#include "andlog.h"
#include "andstr.h"
#include "andfifobuffer.h"
#include "andqueue.h"
#include "codecdef.h"
#include <pthread.h> // for sync

#include "libavcodec/avcodec.h"
#include "libavformat/avformat.h"
#include "libswscale/swscale.h"
#include "libavutil/avutil.h"
#include "libavutil/imgutils.h"

#define DECODER_FIFO_SIZE		(1048576 * 4) // 4MB
#define DECODER_FRAMESIZE_LEN	4

struct easy_decoder_handle
{
	int					id;
	int					width;
	int					height;
	int					out_framesize;
	//ffmpeg
	AVCodecContext*		dec_ctx;			// ffmpeg decoder context
	AVCodec*			dec;				// ffmpeg decoder
	AVFrame*			dec_frame;			// decoded yuv420p picture
	AVFrame*			out_frame;			// outout pixel format picture
	struct SwsContext*	img_convert_ctx;	// do swscale context
	//data
	FifoBuffer			fifo;				// store decoded picture buffer
	SimpleQueue			queue;				// store opaque data
	//info
	int					pic_trans;			// is need transform input picture pixel format
	int					dec_frames_num;		// decoded frame count
	int					dec_pic_num;		// output picture count
	long				start_sec, end_sec;
	long				start_usec, end_usec;

	pthread_mutex_t		mutex;		// sync add() and get()
	//...
};

static int convert_jstring(JNIEnv* env, char *des_str, int* len, jstring str);
static AVFrame * alloc_picture(enum AVPixelFormat pix_fmt, int width, int height);
static int decode_data(easy_decoder_handle* handle, unsigned char* pData, int datalen,
					   unsigned char* pOpaque, int opaque_len);
static int decode_flush(easy_decoder_handle* handle);

static void ff_log(void* user, int level, const char* fmt, va_list vl)
{
	char szLog[2048] = {0};
	vsprintf(szLog, fmt, vl);
	and_log_writeline_easy(0, LOG_DEBUG, "ffmpeg:%s", szLog);
}

int 
Java_tv_xormedia_AndCodec_CodecLib_EasyDecoderOpen(JNIEnv* env, jobject thiz, 
	int w, int h, int out_fmt)
{
	and_log_writeline_simple(0, LOG_INFO, "EasyDecoderOpen()");

	return AndCodec_EasyDecoderOpen(w, h, out_fmt);
}

int 
Java_tv_xormedia_AndCodec_CodecLib_EasyDecoderAdd(JNIEnv* env, jobject thiz,
	int dec, jobject decdata, int decdata_size, jobject opaque)
{
	and_log_writeline_simple(0, LOG_DEBUG, "EasyEncoderAdd()");
#ifdef USE_NATIVE_IO
	unsigned char *p_decdata = (*env)->GetDirectBufferAddress(env, decdata);
	if(!p_decdata) {
		and_log_writeline_easy(0, LOG_ERROR, "failed to get direct addr");
		return -1;
	}
	and_log_writeline_easy(0, LOG_INFO, "add native io addr %p", p_decdata);
	jlong data_size = (*env)->GetDirectBufferCapacity(env, decdata);
#else
	jbyte* p_decdata	= (*env)->GetByteArrayElements(env, decdata, NULL);
	jsize data_size		= (*env)->GetArrayLength(env, decdata);
#endif
	and_log_writeline_easy(0, LOG_DEBUG, "add size %d", data_size);

	jbyte* p_opaque		= (*env)->GetByteArrayElements(env, opaque, NULL);
	int size			= (*env)->GetArrayLength(env, opaque);

	int n = AndCodec_EasyDecoderAdd(dec, (unsigned char *)p_decdata, decdata_size, 
		 (unsigned char *)p_opaque, size);

#ifndef USE_NATIVE_IO
	(*env)->ReleaseByteArrayElements(env, decdata, p_decdata, 0);
#endif
    (*env)->ReleaseByteArrayElements(env, opaque,  p_opaque, 0);
	return n;
}

int 
Java_tv_xormedia_AndCodec_CodecLib_EasyDecoderGet(JNIEnv* env, jobject thiz,
	int dec, jobject picdata, jobject opaque)
{
	and_log_writeline_simple(0, LOG_DEBUG, "EasyEncoderGet()");
#ifdef USE_NATIVE_IO
	unsigned char *p_pic = (*env)->GetDirectBufferAddress(env, picdata);
	if(!p_pic) {
		and_log_writeline_easy(0, LOG_ERROR, "failed to get direct addr");
		return -1;
	}
	and_log_writeline_easy(0, LOG_INFO, "get addr %p", p_pic);
	jlong pic_size	= (*env)->GetDirectBufferCapacity(env, picdata);
#else
	jbyte* p_pic	= (*env)->GetByteArrayElements(env, picdata, NULL);
	jsize pic_size	= (*env)->GetArrayLength(env, opaque);
#endif
	and_log_writeline_easy(0, LOG_DEBUG, "get size %d", pic_size);

	jbyte* p_opaque =  (*env)->GetByteArrayElements(env, opaque, NULL);
	int opaque_len	= (*env)->GetArrayLength(env, opaque);

	int n = AndCodec_EasyDecoderGet(dec, (unsigned char *)p_pic, 
		 (unsigned char *)p_opaque, &opaque_len);

#ifndef USE_NATIVE_IO
	(*env)->ReleaseByteArrayElements(env, picdata, p_pic, 0);
#endif
    (*env)->ReleaseByteArrayElements(env, opaque,  p_opaque, 0);
	return n;
}

void 
Java_tv_xormedia_AndCodec_CodecLib_EasyDecoderClose(JNIEnv* env, jobject thiz,
												   int dec)
{
	and_log_writeline_simple(0, LOG_INFO, "EasyEncoderClose()");

	AndCodec_EasyDecoderClose(dec);
}

double 
Java_tv_xormedia_AndCodec_CodecLib_EasyDecoderGetFPS(JNIEnv* env, jobject thiz, int enc)
{
	//and_log_writeline_simple(0, LOG_DEBUG, "EasyEncoderGetFPS()");

	return AndCodec_EasyDecoderGetFPS(enc);
}

void
Java_tv_xormedia_AndCodec_CodecLib_EasyDecoderTest(JNIEnv* env, jobject thiz, jstring input_file, jstring output_file, int width, int height, int out_fmt, int frames, int is_writefile)
{	
	and_log_writeline_simple(0, LOG_INFO, "EasyDecoderTest()");
	and_log_init("/mnt/sdcard/easy_decoder.log", LOG_INFO);
	
	//parse input and output filename
	char str_in_file[256]	= {0};
	char str_out_file[256]	= {0};
	char str_tmp[256]	= {0};
	int str_len = 256;
	
	convert_jstring(env, str_tmp, &str_len, input_file);
	sprintf(str_in_file, "/data/data/tv.xormedia.AndCodec/files/%s", str_tmp);
	convert_jstring(env, str_tmp, &str_len, output_file);
	sprintf(str_out_file, "/data/data/tv.xormedia.AndCodec/files/%s", str_tmp);
	
	and_log_writeline_easy(0, LOG_INFO, "in: %s, out %s, frames %d, write_file %d", 
		str_in_file, str_out_file,frames, is_writefile);
	
	avcodec_register_all();
	
	int PIC_W = width;
	int PIC_H = height;
	int ret = 0;
	enum AVPixelFormat out_pix_fmt = AV_PIX_FMT_RGB565LE;

	uint8_t* video_dst_data[4] = {NULL};
	int video_dst_linesize[4];

	AVCodecContext*			dec_ctx		= NULL;
	AVCodec*				dec			= NULL;
	unsigned char*			pbuf		= NULL;
	AVFrame*				frame 		= NULL;
	AVPacket pkt;

	int to_read, readed;
	int fd_in;
	fd_in = and_sysutil_open_file(str_in_file, kANDSysUtilOpenReadOnly);
	if(fd_in < 0) {
		and_log_writeline_easy(0, LOG_ERROR, "failed to open h264(with size) file: %s", str_in_file);
		return;
	}
	
	int written;
	int fd_out;
	fd_out = and_sysutil_create_or_open_file(str_out_file, 0644);
	if(fd_out < 0) {
		and_log_writeline_easy(0, LOG_ERROR, "failed to open rgb565 file: %s", str_out_file);
		return;
	}

	do {
		dec = avcodec_find_decoder(AV_CODEC_ID_H264);
	
		dec_ctx = avcodec_alloc_context3(dec);
		dec_ctx->width	 = PIC_W;
		dec_ctx->height	 = PIC_H;
		dec_ctx->pix_fmt = AV_PIX_FMT_YUV420P;

		ret = avcodec_open2(dec_ctx, dec, NULL);
		if(ret < 0) {
			and_log_writeline_easy(0, LOG_ERROR, "failed to open video decoder .");
			break;
		}
	
        ret = av_image_alloc(video_dst_data, video_dst_linesize, dec_ctx->width, dec_ctx->height, dec_ctx->pix_fmt, 1);
		struct SwsContext* img_convert_ctx = sws_getContext(dec_ctx->width, dec_ctx->height, dec_ctx->pix_fmt, PIC_W, PIC_H, out_pix_fmt, SWS_BICUBIC, NULL, NULL, NULL);

		AVFrame* rgb_pic = alloc_picture(out_pix_fmt, PIC_W, PIC_H);
		frame = avcodec_alloc_frame();

		av_init_packet(&pkt);
		pkt.data = NULL;
		pkt.size = 0;

		const int frame_len = 65536;
		pbuf = (unsigned char*)malloc(frame_len);

		int got_frame = 0;
		int dec_frames = 0; 
		while(1)
		{ 
			to_read = 4;
			readed = and_sysutil_read(fd_in, pbuf, to_read);
			if (readed != to_read) {
				and_log_writeline_easy(0, LOG_INFO, "eof. decoder done! total %d frames", dec_frames);
				break;
			}

			to_read = *((int*)pbuf);
			and_log_writeline_easy(0, LOG_DEBUG, "read h264 frame %d. %c%c%c%c", 
				to_read, pbuf[0], pbuf[1], pbuf[2], pbuf[3]);
			readed = and_sysutil_read(fd_in, pbuf, to_read);
			if (readed != to_read) {
				and_log_writeline_easy(0, LOG_INFO, "eof.");
				break;
			}

			pkt.data = pbuf;
			pkt.size = readed;
			and_log_writeline_easy(0, LOG_DEBUG, "encoder frame size:[%d] %d", dec_frames, readed);

			dec_frames++;
			while(1)
			{
				if( pkt.size == 0)
					break;

				ret = avcodec_decode_video2(dec_ctx, frame, &got_frame, &pkt);
				if (ret < 0) 
					break;

				if(pkt.data) {
					pkt.data += ret;
					pkt.size -= ret;
				}

				if(got_frame)		{
					and_log_writeline_easy(0, LOG_DEBUG, "got pic.");
					av_image_copy(video_dst_data, video_dst_linesize,(const uint8_t **)(frame->data), frame->linesize,
						dec_ctx->pix_fmt, dec_ctx->width, dec_ctx->height);

					//to out_pix_fmt 
					sws_scale(img_convert_ctx, (uint8_t const**)video_dst_data, video_dst_linesize, 0, dec_ctx->height, rgb_pic->data, rgb_pic->linesize);

					int len = rgb_pic->linesize[0] * PIC_H;
					and_log_writeline_easy(0, LOG_DEBUG, "pic len:%d.", len);
					written = and_sysutil_write(fd_out, (void *)rgb_pic->data[0], len);
					if (written != len) {
						and_log_writeline_easy(0, LOG_ERROR, "failed to write %d - %d", len, written);
						break;
					}

					av_free_packet(&pkt);

				}
			}
		}

		pkt.data = NULL;
		pkt.size = 0;
		break;
	}while(0);

	if(pbuf)
		free(pbuf);

	if(dec_ctx)
		avcodec_close(dec_ctx);

	if(frame)
		av_free(frame);

	if(video_dst_data[0])
		av_free(video_dst_data[0]);

	and_sysutil_close(fd_in);
	and_sysutil_close(fd_out);
}

char * AndCodec_EasyDecoderVersion()
{
	return AND_DECODER_VERSION;
}

int AndCodec_EasyDecoderOpen(int w, int h, int out_fmt)
{
	and_log_writeline_simple(0, LOG_INFO, "AndCodec_EasyDecoderOpen()");
	and_log_init("/mnt/sdcard/easy_decoder.log", LOG_INFO);
	//and_log_init("/data/data/tv.xormedia.AndCodec/files/easy_decoder.log", LOG_INFO);

	av_log_set_callback(ff_log);

	easy_decoder_handle* handle = (easy_decoder_handle*)and_sysutil_malloc(sizeof(easy_decoder_handle));
	and_log_writeline_easy(0, LOG_INFO, "handle allocated :%d", sizeof(easy_decoder_handle));

	//parse input
	handle->width			= w;
	handle->height			= h;
	handle->dec_frames_num	= 0;
	handle->dec_pic_num		= 0;
	handle->start_sec	= and_sysutil_get_time_sec();
	handle->start_usec	= and_sysutil_get_time_usec(); 

	enum AVPixelFormat out_pix_fmt;

	switch(out_fmt) {
	case AND_PIXEL_FMT_RGB565:
		out_pix_fmt = AV_PIX_FMT_RGB565LE;
		break;
	case AND_PIXEL_FMT_YUV420P:
		out_pix_fmt = AV_PIX_FMT_YUV420P;
		break;
	default:
		out_pix_fmt = -1;
		break;
	}

	and_log_writeline_easy(0, LOG_INFO, "input: %d x %d, out_fmt:%d  ffmpeg_pix_fmt:%d", 
		handle->width, handle->height, out_fmt, (int)out_pix_fmt);

	if(out_pix_fmt == -1) {
		and_log_writeline_easy(0, LOG_INFO, "unsupport output pixel format:%d", out_fmt);
		return INVALID_HANDLE;
	}

	if(out_pix_fmt == AV_PIX_FMT_YUV420P) {
		handle->pic_trans = 0;
		handle->out_framesize = handle->width * handle->height * 3 / 2;
	}
	else {
		handle->pic_trans = 1;
		handle->out_framesize = handle->width * handle->height * 2;
	}

	avcodec_register_all();

	int ret;
	int succeed = 0;
	do {

		and_log_writeline_easy(0, LOG_INFO, "find decoder.");
		handle->dec = avcodec_find_decoder(AV_CODEC_ID_H264);

		and_log_writeline_easy(0, LOG_INFO, "alloc context.");
		handle->dec_ctx = avcodec_alloc_context3(handle->dec);
		handle->dec_ctx->width	 = handle->width;
		handle->dec_ctx->height	 = handle->height;
		handle->dec_ctx->pix_fmt = AV_PIX_FMT_YUV420P;

		and_log_writeline_easy(0, LOG_INFO, "open codec.");
		ret = avcodec_open2(handle->dec_ctx, handle->dec, NULL);
		if(ret < 0) {
			and_log_writeline_easy(0, LOG_ERROR, "failed to open video decoder.");
			break;
		}

		if(handle->pic_trans > 0)
		{
			and_log_writeline_easy(0, LOG_INFO, "alloc sws context.");
			handle->img_convert_ctx = sws_getContext(
					handle->dec_ctx->width, handle->dec_ctx->height,
					handle->dec_ctx->pix_fmt, 
					handle->width, handle->height, 
					out_pix_fmt,
					SWS_FAST_BILINEAR, NULL, NULL, NULL);
			if(!handle->img_convert_ctx) {
				and_log_writeline_easy(0, LOG_ERROR, "failed to alloc sws context: %dx%d@%d, %dx%d@%d",
					handle->dec_ctx->width, handle->dec_ctx->height,
					handle->dec_ctx->pix_fmt, 
					handle->width, handle->height, 
					out_pix_fmt);
				break;
			}

			and_log_writeline_easy(0, LOG_INFO, "alloc out picture.");
			handle->out_frame = alloc_picture(out_pix_fmt, handle->width, handle->height);
			if(!handle->out_frame){
				and_log_writeline_easy(0, LOG_ERROR, "failed to alloc out frame.");
				break;
			}
		}

		handle->dec_frame = avcodec_alloc_frame();
		if(!handle->dec_frame){
			and_log_writeline_easy(0, LOG_ERROR, "failed to alloc dec frame.");
			break;
		}

		//all done!
		succeed = 1;
	}while(0);

	if(!succeed)
		return INVALID_HANDLE;

	ret = pthread_mutex_init(&handle->mutex, 0);
	if (ret < 0) {
		and_log_writeline_simple(0, LOG_ERROR, "failed to create mutex");
		return INVALID_HANDLE;
	}

	and_log_writeline_easy(0, LOG_INFO, "create fifo.");
	ret = and_fifo_create(&handle->fifo, DECODER_FIFO_SIZE);
	if (ret < 0) {
		and_log_writeline_simple(0, LOG_ERROR, "failed to create fifo");
		return INVALID_HANDLE;
	}

	and_log_writeline_simple(0, LOG_INFO, "create queue.");
	ret = and_queue_init(&handle->queue, OPAQUE_DATA_LEN, QUEUE_SIZE);
	if (ret < 0) {
		and_log_writeline_simple(0, LOG_ERROR, "failed to create queue");
		return INVALID_HANDLE;
	}

	and_log_writeline_easy(0, LOG_INFO, "open decoder handle %p", handle);
	return (int)handle;
}

int AndCodec_EasyDecoderAdd(int dec, unsigned char* decdata, int decdata_size, 
							unsigned char* opaque, int opaque_len)
{
	and_log_writeline_simple(0, LOG_DEBUG, "AndCodec_EasyDecoderAdd()");

	if(!dec) {
		and_log_writeline_simple(0, LOG_ERROR, "decoder handle is null");
		return -1;
	}
	if(!decdata) {
		and_log_writeline_simple(0, LOG_ERROR, "decode data is null");
		return -1;
	}

	easy_decoder_handle* handle = (easy_decoder_handle *)dec;
	return decode_data(handle, decdata, decdata_size, opaque, opaque_len);
}

int AndCodec_EasyDecoderFlush(int dec)
{
	and_log_writeline_simple(0, LOG_DEBUG, "AndCodec_EasyDecoderFlush()");

	if(!dec) {
		and_log_writeline_simple(0, LOG_ERROR, "decoder handle is null");
		return -1;
	}

	easy_decoder_handle* handle = (easy_decoder_handle *)dec;
	return decode_flush(handle);
}

int AndCodec_EasyDecoderGet(int dec, unsigned char* picdata, 
							unsigned char* opaque, int *opaque_len)
{
	and_log_writeline_simple(0, LOG_DEBUG, "AndCodec_EasyDecoderGet()");

	if(!dec) {
		and_log_writeline_simple(0, LOG_ERROR, "decoder handle is null");
		return -1;
	}
	if(!picdata) {
		and_log_writeline_simple(0, LOG_ERROR, "picture data is null");
		return -1;
	}

	easy_decoder_handle* handle = (easy_decoder_handle *)dec;

	pthread_mutex_lock(&handle->mutex);

	int readed;
	int frame_size;
	int ret;

	if (and_fifo_used(&handle->fifo) < DECODER_FRAMESIZE_LEN) {
		readed = 0;
		goto exit;
	}

	readed = and_fifo_read(&handle->fifo, (char *)&frame_size, DECODER_FRAMESIZE_LEN);
	and_log_writeline_easy(0, LOG_DEBUG, "frame size %d", frame_size);
	readed = and_fifo_read(&handle->fifo, (char *)picdata, frame_size);
	if (readed < frame_size) {
		and_log_writeline_easy(0, LOG_ERROR, "frame data is corrupt %d.%d", frame_size, readed);
		readed = -1;
		goto exit;
	}

	ret = and_queue_get(&handle->queue, (void *)opaque);
	if(ret < 0) {
		and_log_writeline_easy(0, LOG_ERROR, "failed to get opaque data.");
		readed = -1;
		goto exit;
	}

	//and_sysutil_memcpy(opaque, (void *)&opaque_data, opa_size);
	if(opaque_len)
		*opaque_len = OPAQUE_DATA_LEN;
	
exit:
	pthread_mutex_unlock(&handle->mutex);
	return readed;
}

double AndCodec_EasyDecoderGetFPS(int enc)
{
	and_log_writeline_simple(0, LOG_DEBUG, "AndCodec_EasyEncoderGetFPS()");

	easy_decoder_handle* handle = (easy_decoder_handle *)enc;

	double elapsed;
	double fps;

	handle->end_sec		= and_sysutil_get_time_sec();
	handle->end_usec	= and_sysutil_get_time_usec();
	elapsed = (double) (handle->end_sec - handle->start_sec);
	elapsed += (double) (handle->end_usec - handle->start_usec) /
		(double) 1000000;

	if (elapsed <= 0.01)
		elapsed = 0.01f;

	fps = (double)handle->dec_frames_num / elapsed;
	and_log_writeline_easy(0, LOG_DEBUG, "fps: %.2f(%d frames/%.3f sec)", 
		fps, handle->dec_frames_num, elapsed);

	return fps;
}

void AndCodec_EasyDecoderClose(dec)
{
	and_log_writeline_simple(0, LOG_INFO, "AndCodec_EasyDecoderClose()");

	easy_decoder_handle* handle = (easy_decoder_handle *)dec;
	and_log_writeline_easy(0, LOG_INFO, "decoder handle %x", handle);

	if(handle->dec_frame)
		avcodec_free_frame(&handle->dec_frame);

	if(handle->pic_trans && handle->out_frame) 
		avcodec_free_frame(&handle->out_frame);

	if(handle->dec_ctx) {
		avcodec_close(handle->dec_ctx);
	    av_free(handle->dec_ctx);
	}
	
	pthread_mutex_destroy(&handle->mutex);

	and_fifo_close(&handle->fifo);
	and_queue_close(&handle->queue);

	and_sysutil_free(handle);
	and_log_close();
}

static int convert_jstring(JNIEnv* env, char *des_str, int* len, jstring str)
{
	const char *nativeString = (*env)->GetStringUTFChars(env, str, 0);     
	and_sysutil_strcpy(des_str, nativeString, *len);
	(*env)->ReleaseStringUTFChars(env, str, nativeString);
	
	return 0;
}

static int decode_data(easy_decoder_handle* handle, unsigned char* pData, int datalen,
					   unsigned char* pOpaque, int opaque_len)
{
	pthread_mutex_lock(&handle->mutex);

	AVPacket pkt;
	int got_frame = 0;
	int ret;
	int written;
	int len;

	if(opaque_len != OPAQUE_DATA_LEN) {
		and_log_writeline_easy(0, LOG_ERROR, "opaque data size is wrong %d.%d", 
			opaque_len, OPAQUE_DATA_LEN);
		got_frame = -1;
		goto exit;
	}

	av_init_packet(&pkt);
	pkt.data = NULL;
	pkt.size = 0;

	pkt.data = pData;
	pkt.size = datalen;
	and_log_writeline_easy(0, LOG_DEBUG, "decode frame size:[%d] %d", handle->dec_frames_num, datalen);

	while (pkt.size > 0) {
		ret = avcodec_decode_video2(handle->dec_ctx, handle->dec_frame, &got_frame, &pkt);
		if (ret < 0) {
			and_log_writeline_easy(0, LOG_WARN, "failed to decode #%d %d, ret %d", 
				handle->dec_frames_num, datalen, ret);	
			break;
		}
		handle->dec_frames_num++;

		if (got_frame) {
			and_log_writeline_easy(0, LOG_DEBUG, "got pic. type %d", handle->dec_frame->pict_type);
			handle->dec_pic_num++;

			filesize_t timestamp;
			if (handle->dec_frame->opaque) {
				and_sysutil_memcpy(&timestamp, handle->dec_frame->opaque, sizeof(filesize_t));
				and_log_writeline_easy(0, LOG_INFO, "frame opaque %lld", timestamp);
			}

			if(handle->pic_trans)
			{
				//to out_pix_fmt rgb565le
				and_log_writeline_easy(0, LOG_DEBUG, "scale begin. linesize_in:%d linesize_out:%d h:%d", 
					handle->dec_frame->linesize[0], handle->out_frame->linesize[0], handle->dec_ctx->height);
				sws_scale(handle->img_convert_ctx, 
					(uint8_t const**)handle->dec_frame->data, handle->dec_frame->linesize,
					0, handle->dec_ctx->height,
					handle->out_frame->data, handle->out_frame->linesize);

				len = handle->out_frame->linesize[0]* handle->height;
				and_log_writeline_simple(0, LOG_DEBUG, "scale end.");
			}
			else  //420p only
			{
				handle->out_frame = handle->dec_frame;
				len = handle->out_frame->linesize[0]* handle->height * 3 / 2 ;
			}

			and_log_writeline_easy(0, LOG_DEBUG, "line size:%d; pic len:%d.", handle->out_frame->linesize[0], len);
			written = and_fifo_write(&handle->fifo, (void *)&len, DECODER_FRAMESIZE_LEN);
			if (written != DECODER_FRAMESIZE_LEN) {
				and_log_writeline_easy(0, LOG_ERROR, "failed to write data size %d - %d", len, written);
				got_frame = -1;
				goto exit;
			}

			written = 0;
			if(handle->pic_trans) // rgb565le
			{
				written = and_fifo_write(&handle->fifo, (void *)handle->out_frame->data[0], len);
			}
			else //420p only
			{
				written += and_fifo_write(&handle->fifo, (void *)handle->out_frame->data[0], handle->out_frame->linesize[0] * handle->height);
				written += and_fifo_write(&handle->fifo, (void *)handle->out_frame->data[1], handle->out_frame->linesize[1] * handle->height/2);
				written += and_fifo_write(&handle->fifo, (void *)handle->out_frame->data[2], handle->out_frame->linesize[2] * handle->height/2);
			}
			if (written != len) {
				and_log_writeline_easy(0, LOG_ERROR, "failed to write %d - %d", len, written);
				got_frame = -1;
				goto exit;
			}

			if ( and_queue_put(&handle->queue, (void *)pOpaque) < 0) {
				got_frame = -1;
				goto exit;
			}
			//all done!
			break;
		}

		if(pkt.data) {
			pkt.data += ret;
			pkt.size -= ret;
		}
	}

	av_free_packet(&pkt);
	pkt.data = NULL;
	pkt.size = 0;

	if(!got_frame) {
		and_log_writeline_easy(0, LOG_WARN, "no picture decoded out in #%d", handle->dec_frames_num);
		
		if ( and_queue_put(&handle->queue, (void *)pOpaque) < 0) {
			got_frame = -1;
			goto exit;
		}
	}

	// handle->dec_frame->key_frame
	if (got_frame /*&& handle->dec_frames_num > 1*/ && 
		AV_PICTURE_TYPE_I == handle->dec_frame->pict_type ) {
		while (decode_flush(handle) > 0) {
			and_log_writeline_easy(0, LOG_INFO, "flush picture out in #%d", handle->dec_frames_num);
		}

		int opaque_num		= and_queue_used(&handle->queue);
		int cache_pic_num	= and_fifo_used(&handle->fifo) / handle->out_framesize;
		int pop_num = opaque_num - cache_pic_num;
		if (pop_num > 0) {
			and_log_writeline_easy(0, LOG_INFO, "opaque list %d, cache picture %d, to drop %d", 
				opaque_num, cache_pic_num, pop_num);

			OpaqueData op;
			while (pop_num > 0) {
				and_queue_get(&handle->queue, (void *)&op);
				and_log_writeline_easy(0, LOG_INFO, "discard opaque data");
				pop_num--;
			}
		}
	}

exit:
	pthread_mutex_unlock(&handle->mutex);
	return got_frame;
}

static int decode_flush(easy_decoder_handle* handle)
{
	AVPacket pkt;
	int got_frame = 0;
	int ret;
	int written;
	int len;

	av_init_packet(&pkt);
	pkt.data = NULL;
	pkt.size = 0;

	and_log_writeline_easy(0, LOG_DEBUG, "decode flush: #%d", handle->dec_frames_num);

	ret = avcodec_decode_video2(handle->dec_ctx, handle->dec_frame, &got_frame, &pkt);
	if (ret < 0) {
		and_log_writeline_easy(0, LOG_WARN, "failed to decode flush #%d, ret %d", 
			handle->dec_frames_num, ret);	
		return -1;
	}

	if (got_frame) {
		and_log_writeline_easy(0, LOG_DEBUG, "got pic.");
		handle->dec_pic_num++;

		if(handle->pic_trans) {
			//to out_pix_fmt 
			and_log_writeline_easy(0, LOG_DEBUG, "scale begin. linesize_in:%d linesize_out:%d h:%d", 
				handle->dec_frame->linesize[0], handle->out_frame->linesize[0], handle->dec_ctx->height);
			sws_scale(handle->img_convert_ctx, 
				(uint8_t const**)handle->dec_frame->data, handle->dec_frame->linesize,
				0, handle->dec_ctx->height,
				handle->out_frame->data, handle->out_frame->linesize);

			len = handle->out_frame->linesize[0]* handle->height;
			and_log_writeline_simple(0, LOG_DEBUG, "scale end.");
		}
		else  //420p only
		{
			handle->out_frame = handle->dec_frame;
			len = handle->out_frame->linesize[0]* handle->height * 3 / 2 ;
		}

		and_log_writeline_easy(0, LOG_DEBUG, "line size:%d; pic len:%d.", handle->out_frame->linesize[0], len);
		written = and_fifo_write(&handle->fifo, (void *)&len, DECODER_FRAMESIZE_LEN);
		if (written != DECODER_FRAMESIZE_LEN) {
			and_log_writeline_easy(0, LOG_ERROR, "failed to write data size %d - %d", len, written);
			return -1;
		}

		written = 0;
		if(handle->pic_trans) {
			written = and_fifo_write(&handle->fifo, (void *)handle->out_frame->data[0], len);
		}
		else {
			written += and_fifo_write(&handle->fifo, (void *)handle->out_frame->data[0], handle->out_frame->linesize[0] * handle->height);
			written += and_fifo_write(&handle->fifo, (void *)handle->out_frame->data[1], handle->out_frame->linesize[1] * handle->height/2);
			written += and_fifo_write(&handle->fifo, (void *)handle->out_frame->data[2], handle->out_frame->linesize[2] * handle->height/2);
		}
		if (written != len) {
			and_log_writeline_easy(0, LOG_ERROR, "failed to write %d - %d", len, written);
			return -1;
		}
	}

	return got_frame;
}

static AVFrame * alloc_picture(enum AVPixelFormat pix_fmt, int width, int height)
{
	AVFrame *picture;
	uint8_t *picture_buf;
	int size;

	picture = avcodec_alloc_frame();
	if (!picture)
		return NULL;
	size = avpicture_get_size(pix_fmt, width, height);
	picture_buf = (uint8_t *)av_malloc(size);
	if (!picture_buf) {
		av_free(picture);
		return NULL;
	}
	avpicture_fill((AVPicture *)picture, picture_buf,
		pix_fmt, width, height);
	return picture;
}

