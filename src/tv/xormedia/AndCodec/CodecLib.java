package tv.xormedia.AndCodec;

import android.util.Log;
import java.nio.ByteBuffer;

// for audio track
import android.media.AudioTrack;
import android.media.AudioManager;
import android.media.AudioFormat;

// for audio recorder
import android.media.AudioRecord;
import android.media.MediaRecorder;

// for file io
import java.io.IOException;
import	java.io.FileOutputStream;

// for thread
import android.os.Handler;  
import android.os.Message;

// for file
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import	java.io.FileInputStream;

public class CodecLib {
	public static final String LOG_TAG 		= "AndCodec";
	public static final boolean FORCE_NONEON= false;
	public static final boolean USE_NATIVEIO= false;
	
	public static final int MSG_REFRESH		= 0x000011;
	public static final int MSG_FINISH 		= 0x000012;
	public static final int MSG_ABORT		= 0x000013;
	public static final int MSG_ERROR		= 0x000014;
	public static final int MSG_EOF			= 0x000015;
	public static final int MSG_INFO		= 0x000016;
	
	public static final int CLIP_FPS		= 10;
	
	public static final int INVALID_HANDLE 	= -1;
	
	private static TestEnc ins 				= null;
	
	public static void Register(TestEnc thiz) {
		ins = thiz;
	}
	
	// This function will be called in C++
    public static int OnError(int code, String message) {
        Log.e(CodecLib.LOG_TAG, String.format("Java: Java testEnc: OnError code %d, message %s", 
			code, message));
		ins.str_enc_info = message;
		Message msg = new Message();  
        msg.what = MSG_ERROR;
        ins.handler.sendMessage(msg);
		return 0;
    }
    
    public static int OnFinish(String filename, 
			int total_frames, int in_frames, int out_frames, double fps) {
        Log.i(CodecLib.LOG_TAG, 
			String.format("Java: Java testEnc: OnFinish %s: in/out %d/%d(all %d), fps %.2f", 
			filename, in_frames, out_frames, total_frames, fps));
		
		try {
			FileInputStream outfile = new FileInputStream(filename);
			
			double bitrate;
			bitrate = (double)(outfile.available() * 8 * CLIP_FPS) / (double)(out_frames * 1000);
			ins.str_enc_info = String.format("i/o: %d/%d(all %d) frm, fps: %.2f, %.0f kbps", 
				in_frames, out_frames, total_frames, 
				fps, bitrate);
			Message msg = new Message();  
			msg.what = MSG_FINISH;
			ins.handler.sendMessage(msg);
		
			outfile.close();
		}
		catch (FileNotFoundException e) {
			Log.e(CodecLib.LOG_TAG, "file not found");
			ins.str_enc_info = "file not found";
			Message msg = new Message();  
			msg.what = CodecLib.MSG_ERROR;  
			ins.handler.sendMessage(msg);
		}
		catch(Exception e){   
			e.printStackTrace();
			Log.e(CodecLib.LOG_TAG, "an error occured while finishing...", e);
			Message msg = new Message();  
			msg.what = CodecLib.MSG_ERROR;  
			ins.handler.sendMessage(msg);
		}		
			
		
		return 0;
    }
    
    public static int OnInfo(int code, String message) {
        Log.d(CodecLib.LOG_TAG, String.format("Java: Java testEnc: OnInfo code %d, message %s",
			code, message));
		ins.str_enc_info = message;
		Message msg = new Message();  
        msg.what = MSG_INFO;
        ins.handler.sendMessage(msg);
		return 0;
    }
	
	public static int OnFPS(int frames, double fps) {
        Log.d(CodecLib.LOG_TAG, String.format("Java: Java testEnc: OnFPS frames %d, fps %.2f",
			frames, fps));
		String message	= (String) ins.getResources().getString(R.string.enc_prog_dlg_msg);
		ins.str_enc_info = message + String.format(" (fps: %.2f)", fps);
		ins.progDlg.setProgress(frames);
		Message msg = new Message();  
		msg.what = MSG_REFRESH;  
		ins.handler.sendMessage(msg); 
		return 0;
    }
	
	// audio record
	private static AudioRecord audioRecord	= null;
	private static boolean isRecording		= false;
	private static short[] buffer			= null;
	private static int bufferSize;
	
	public static int StartRecord() {
		if(isRecording) {
			StopRecord();
		}
		
		int frequency = 44100;//44100 11025
		int channelConfiguration = AudioFormat.CHANNEL_CONFIGURATION_MONO;//CHANNEL_CONFIGURATION_STEREO
		int audioEncoding = AudioFormat.ENCODING_PCM_16BIT;
		
		/*File file = new File(Environment.getExternalStorageDirectory().getAbsolutePath() + "/test/reverseme.pcm");

		// Delete any previous recording.
		if (file.exists())
			file.delete();


		// Create the new file.
		try {
			file.createNewFile();
		}
		catch (IOException e) {
			throw new IllegalStateException("Failed to create " + file.toString());
		}*/

		try {
			// Create a DataOuputStream to write the audio data into the saved file.
			//OutputStream os = new FileOutputStream(file);
			//BufferedOutputStream bos = new BufferedOutputStream(os);
			//DataOutputStream dos = new DataOutputStream(bos);

			// Create a new AudioRecord object to record the audio.
			if (null == audioRecord) {
				bufferSize = AudioRecord.getMinBufferSize(frequency, channelConfiguration,  audioEncoding);
				audioRecord = new AudioRecord(MediaRecorder.AudioSource.MIC, 
					frequency, channelConfiguration, audioEncoding, bufferSize);
			}

			//buffer = new short[bufferSize];  
				
			audioRecord.startRecording();
			isRecording = true;
			Log.i(LOG_TAG, String.format("Java: audioRecord %d start recording...", bufferSize));
		}
		catch (Throwable t) {
			Log.e(LOG_TAG,"Recording Failed: " + t);
			return -1;
		}
		
		return 0;
	}
	
	public static int GetAudioData(byte data[], int offset) {
		if(!isRecording)
			return 0;
			
		int bufferReadResult = audioRecord.read(data, offset, bufferSize);
		//for (int i = 0; i < bufferReadResult; i++)
		//	dos.writeShort(buffer[i]);
		Log.d(LOG_TAG, "audioRecord read audio data: " + bufferReadResult);
		return bufferReadResult;
	}
	
	public static void StopRecord() {
		if(!isRecording) {
			Log.i(LOG_TAG, "Java: audioRecord not recording, cannot stop");
			return;
		}
		audioRecord.stop();
		//dos.close();
		isRecording = false;
		Log.i(LOG_TAG, "Java: audioRecord record finished");
	}
	
	private void WriteWaveFileHeader(FileOutputStream out, long totalAudioLen,  
            long totalDataLen, long longSampleRate, int channels, long byteRate)  
            throws IOException {  
        byte[] header = new byte[44];  
        header[0] = 'R'; // RIFF/WAVE header  
        header[1] = 'I';  
        header[2] = 'F';  
        header[3] = 'F';  
        header[4] = (byte) (totalDataLen & 0xff);  
        header[5] = (byte) ((totalDataLen >> 8) & 0xff);  
        header[6] = (byte) ((totalDataLen >> 16) & 0xff);  
        header[7] = (byte) ((totalDataLen >> 24) & 0xff);  
        header[8] = 'W';  
        header[9] = 'A';  
        header[10] = 'V';  
        header[11] = 'E';  
        header[12] = 'f'; // 'fmt ' chunk  
        header[13] = 'm';  
        header[14] = 't';  
        header[15] = ' ';  
        header[16] = 16; // 4 bytes: size of 'fmt ' chunk  
        header[17] = 0;  
        header[18] = 0;  
        header[19] = 0;  
        header[20] = 1; // format = 1  
        header[21] = 0;  
        header[22] = (byte) channels;  
        header[23] = 0;  
        header[24] = (byte) (longSampleRate & 0xff);  
        header[25] = (byte) ((longSampleRate >> 8) & 0xff);  
        header[26] = (byte) ((longSampleRate >> 16) & 0xff);  
        header[27] = (byte) ((longSampleRate >> 24) & 0xff);  
        header[28] = (byte) (byteRate & 0xff);  
        header[29] = (byte) ((byteRate >> 8) & 0xff);  
        header[30] = (byte) ((byteRate >> 16) & 0xff);  
        header[31] = (byte) ((byteRate >> 24) & 0xff);  
        header[32] = (byte) (2 * 16 / 8); // block align  
        header[33] = 0;  
        header[34] = 16; // bits per sample  
        header[35] = 0;  
        header[36] = 'd';  
        header[37] = 'a';  
        header[38] = 't';  
        header[39] = 'a';  
        header[40] = (byte) (totalAudioLen & 0xff);  
        header[41] = (byte) ((totalAudioLen >> 8) & 0xff);  
        header[42] = (byte) ((totalAudioLen >> 16) & 0xff);  
        header[43] = (byte) ((totalAudioLen >> 24) & 0xff);  
        out.write(header, 0, 44);  
    }  
	
	// byte to long 
    public static long ByteToLong(byte[] b) { 
        long s = 0; 
        long s0 = b[0] & 0xff;// MSB
        long s1 = b[1] & 0xff; 
        long s2 = b[2] & 0xff; 
        long s3 = b[3] & 0xff; 
        long s4 = b[4] & 0xff;// MSB 
        long s5 = b[5] & 0xff; 
        long s6 = b[6] & 0xff; 
        long s7 = b[7] & 0xff; 
 
        // s0 unchange
        s1 <<= 8; 
        s2 <<= 16; 
        s3 <<= 24; 
        s4 <<= 8 * 4; 
        s5 <<= 8 * 5; 
        s6 <<= 8 * 6; 
        s7 <<= 8 * 7; 
        s = s0 | s1 | s2 | s3 | s4 | s5 | s6 | s7; 
        return s; 
    } 
	
	// byte to int 
    public static int ByteToInt(byte[] b) { 
        int s = 0; 
        int s0 = b[0] & 0xff;// MSB
        int s1 = b[1] & 0xff; 
        int s2 = b[2] & 0xff; 
        int s3 = b[3] & 0xff; 
 
        // s0 unchange
        s1 <<= 8; 
        s2 <<= 16; 
        s3 <<= 24;  
        s = s0 | s1 | s2 | s3;
        return s;
    }
	
	// int to byte 
    public static byte[] IntToByte(int number) { 
        int temp = number; 
        byte[] b = new byte[4]; 
        for (int i = 0; i < b.length; i++) { 
            b[i] = new Long(temp & 0xff).byteValue();
            temp = temp >> 8; // right shift 8
        } 
        return b; 
    }
	
	// opaque(16 byte) to frame type 
	public static int OpaqueToType(byte[] b) { 
        int type = b[8];
        return type; 
    }
	
	// long to byte 
	public static byte[] LongToByte(long number) { 
        long temp = number; 
        byte[] b = new byte[8]; 
        for (int i = 0; i < b.length; i++) { 
            b[i] = new Long(temp & 0xff).byteValue();
            temp = temp >> 8; // right shift 8
        } 
        return b; 
    }
	
	// long to opaque(16 byte)
	public static byte[] LongToOpaque(long number) { 
        long temp = number; 
        byte[] b = new byte[16]; //0-7 time stamp, 8-15 customized 
        for (int i = 0; i < 8; i++) { 
            b[i] = new Long(temp & 0xff).byteValue();
            temp = temp >> 8; // right shift 8
        } 
        return b; 
    }
	
	
	// for encoder
	public static native int EasyEncoderOpen(int w, int h, int in_fmt, 
		String profile, String enc_str);
	
	public static native int EasyEncoderAdd(int enc, byte[] picdata, int picdata_size, 
		byte[] opaque);
		
	public static native int EasyEncoderAdd(int enc, ByteBuffer picdata, int picdata_size, 
		byte[] opaque);
	
	public static native int EasyEncoderGet(int enc, byte[] encdata, byte[] opaque);
	
	public static native int EasyEncoderGet(int enc, ByteBuffer encdata, byte[] opaque);
	
	public static native void EasyEncoderClose(int enc);
	
	public static native double EasyEncoderGetFPS(int enc);
	
	public static native void EasyEncoderTest(String input_file, String output_file, 
		int w, int h, String preset, String enc_str, int frames, int is_writefile, int is_writesize);
		
	public static native int EasyEncoderTestAbort();
	
	// for ffmpeg encoder
	public static native int EasyffEncoderOpen(int w, int h, int in_fmt, 
		String profile, String enc_str);
	
	public static native int EasyffEncoderAdd(int ffenc, byte[] picdata, int picdata_size, 
		byte[] audiodata, int audiodata_size,
		byte[] opaque);
	
	public static native int EasyffEncoderGet(int ffenc, byte[] encdata, byte[] opaque);
	
	public static native void EasyffEncoderClose(int ffenc);
	
	public static native double EasyffEncoderGetFPS(int ffenc);
	
	public static native void EasyffEncoderTest(String input_file, String output_file, 
		String preset, int frames, int is_writefile);
	
	// for decoder
	public static native int EasyDecoderOpen(int w, int h, int in_fmt);
	
	public static native int EasyDecoderAdd(int dec, ByteBuffer decdata, int decdata_size, byte[] opaque);
	
	public static native int EasyDecoderAdd(int dec, byte[] decdata, int decdata_size, byte[] opaque);
		
	public static native int EasyDecoderGet(int dec, ByteBuffer picdata, byte[] opaque);
	
	public static native int EasyDecoderGet(int dec, byte[] picdata, byte[] opaque);
	
	public static native void EasyDecoderClose(int dec);
	
	public static native double EasyDecoderGetFPS(int dec);
	
	public static native void EasyDecoderTest(String input_file, String output_file, 
		int width, int height, int out_fmt, int frames, int is_writefile);
		
	// for device features
	public static native int IsSupportNeon();
		
	public static int loadAndCodec() {
		if (IsSupportNeon() > 0 && !FORCE_NONEON) {
			Log.i(LOG_TAG, "Java: Codeclib load neon lib");
			System.loadLibrary("andcodec_neon");
			//Toast.makeText(getApplicationContext(), "load neon lib", 
			//	Toast.LENGTH_SHORT).show();
			return 1;
		}
		else {
			Log.i(LOG_TAG, "Java: Codeclib load generic lib");
			System.loadLibrary("andcodec");
			//Toast.makeText(getApplicationContext(), "load generic lib", 
			//	Toast.LENGTH_SHORT).show();
			return 0;
		}
	}
	
    // Load the .so
    static {
		System.loadLibrary("anddevft");
		loadAndCodec();
    }
}
