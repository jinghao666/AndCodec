package tv.xormedia.AndCodec;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.hardware.Camera;
import android.hardware.Camera.CameraInfo;
import android.hardware.Camera.Size;
import android.os.Bundle;
import android.os.Build.*;
import android.util.Log;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;

import android.graphics.ImageFormat;
import android.graphics.YuvImage;
import android.graphics.Rect;
import java.io.ByteArrayOutputStream;

import java.io.IOException;
import java.io.FileNotFoundException;
import java.util.List;
import java.util.Iterator;

import android.content.Intent;
import android.content.pm.ActivityInfo;

import android.os.Environment;
import java.io.File;
import	java.io.FileOutputStream;

import android.widget.Toast;

import android.app.Dialog;
import android.app.AlertDialog;
import android.content.DialogInterface;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ArrayBlockingQueue;
import java.lang.Thread;
import java.lang.Runnable;
import android.os.Looper;

import android.widget.TextView;
import android.widget.Button;
import android.text.TextPaint;
import android.widget.LinearLayout;
import android.view.ViewGroup.LayoutParams;

// for thread
import android.os.Handler;  
import android.os.Message; 

import java.util.Date;
import java.util.TimeZone;
import java.text.SimpleDateFormat;

// Need the following import to get access to the app resources, since this
// class is in a sub-package.

// ----------------------------------------------------------------------

public class CameraPreview extends Activity implements Camera.PreviewCallback {
	
	private static final String OUT_NV21_FILENAME = "/mnt/sdcard/test/pic/out.nv21";
	private static final int MSG_REFRESH 		= 1;
	private static final int MSG_FINISH	 		= 2;
	private static final int MSG_ERROR			= -1;
	private static final int MSG_FILE_NOT_FOUND	= -2; 
	
    private Preview mPreview;
    Camera mCamera;
    int numberOfCameras;
    int cameraCurrentlyLocked;
	
	private LinearLayout	m_info_layout 	= null;
	private TextView		m_tv_info		= null;
	private Button			m_btn_start		= null;

	public int currentVersion;
	public int m_width 			= 352;
	public int m_height 		= 288;
	private int m_pic_size		= 0;
	private int m_in_fmt		= 2;
	private String m_profile	= "";
	private String m_enc_str	= "";
	private int cam_frames		= 0;
	private int cam_drop_frames = 0;
	private int frames			= 0;
	private int cap_frames	= 0;
	private int m_frames		= 100;
	private String m_out_filename= "";
	private int m_is_writefile	= 1;
	private int m_is_writesize	= 0;
	private int	m_type			= 2; // type: 2 cam->h264, 3 cam->ts, 4 cam->nv21
	
	private int MAX_FRAME_NUM;
	private BlockingQueue<Object> mFrameList = new ArrayBlockingQueue<Object>(16);
	private Thread mFrameThread 	= null;
	private boolean m_bRecording 	= false;
	private boolean m_bQuit			= false;
	
	public Handler mHandler = null;
	private double m_enc_fps;
	private long start_msec;
	private String str_msg;
	
    // The first rear facing camera
    int defaultCameraId;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
		
		Intent intent 	= getIntent();
		m_width			= intent.getIntExtra("width", 352);
		m_height		= intent.getIntExtra("height", 288);
		m_in_fmt		= intent.getIntExtra("in_fmt", 2);
		m_profile		= intent.getStringExtra("profile");
		m_enc_str		= intent.getStringExtra("settings");
		m_frames		= intent.getIntExtra("frames", 100);
		
		m_out_filename	= intent.getStringExtra("out_filename");
		m_is_writefile 	= intent.getIntExtra("writefile", 1);
		m_is_writesize 	= intent.getIntExtra("writesize", 0);
		m_type			= intent.getIntExtra("type", 2);
		
		if (4 == m_type) // save picture
			MAX_FRAME_NUM = 12;
		else
			MAX_FRAME_NUM = 4;

		Log.i(CodecLib.LOG_TAG, String.format("input %dx%d, fmt %d, profile %s, settings %s, %d frames, out_filename: %s, type: %d, write file: %s, write size: %s", 
			m_width, m_height, m_in_fmt, m_profile, m_enc_str, m_frames, 
			m_out_filename, m_type, 
			m_is_writefile > 0 ? "yes" : "no", 
			m_is_writesize > 0 ? "yes" : "no"));
		
        // Hide the window title.
        super.requestWindowFeature(Window.FEATURE_NO_TITLE);
        //super.getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
		super.getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, 
			WindowManager.LayoutParams.FLAG_FULLSCREEN);
		super.getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        // Create a RelativeLayout container that will hold a SurfaceView,
        // and set it as the content of our activity.
        mPreview = new Preview(this);
        setContentView(mPreview);
		mPreview.setInstance(this);

		currentVersion = VERSION.SDK_INT;
		Log.i(CodecLib.LOG_TAG, String.format("android version %d", currentVersion));
		if (currentVersion >= VERSION_CODES.GINGERBREAD) {
			// Find the total number of cameras available
			numberOfCameras = Camera.getNumberOfCameras();
			Log.i(CodecLib.LOG_TAG, "camera number: " + numberOfCameras);
			
			// Find the ID of the default camera
			CameraInfo cameraInfo = new CameraInfo();
			for (int i = 0; i < numberOfCameras; i++) {
				Camera.getCameraInfo(i, cameraInfo);
				if (cameraInfo.facing == CameraInfo.CAMERA_FACING_BACK) {
					defaultCameraId = i;
				}
			}
		}
		else {
			numberOfCameras = 2;
			defaultCameraId = 1;
		}

        
		
		this.m_tv_info = new TextView(this);
		this.m_tv_info.setText("camera info");
		this.m_tv_info.setTextColor(0xffff0000);// red
		TextPaint tp1 = this.m_tv_info.getPaint();
		tp1.setFakeBoldText(true);
		
		this.m_btn_start = new Button(this);
		this.m_btn_start.setText(getResources().getString(R.string.btn_cam_record_start));
		this.m_btn_start.setOnClickListener(new View.OnClickListener(){
			@Override
			public void onClick(View v){
				if (m_bRecording) {
					Log.i(CodecLib.LOG_TAG, "press stop record button");
					if (3 == m_type)		
						CodecLib.StopRecord();
					m_btn_start.setText(getResources().getString(R.string.btn_cam_record_start));
					m_bQuit = true;
				}
				else {
					Log.i(CodecLib.LOG_TAG, "press start record button");
					OnStartPreview();
					if (3 == m_type)
						CodecLib.StartRecord();
					m_btn_start.setText(getResources().getString(R.string.btn_cam_record_stop));
				}
				m_bRecording = !m_bRecording;
			}
		});
		
		this.m_info_layout = new LinearLayout(this);
		this.m_info_layout.setLayoutParams(new LayoutParams(LayoutParams.FILL_PARENT,
			LayoutParams.FILL_PARENT));
		this.m_info_layout.setOrientation(LinearLayout.HORIZONTAL);
		this.m_info_layout.addView(m_btn_start);
		this.m_info_layout.addView(m_tv_info);
		addContentView(m_info_layout, new LayoutParams(LayoutParams.FILL_PARENT,
			LayoutParams.WRAP_CONTENT));
			
		this.mHandler = new Handler() {  
            @Override
            public void handleMessage(Message msg) {  
				double cam_fps;
				long elapsedTime;
                switch(msg.what) {
				case MSG_FILE_NOT_FOUND:
					Toast.makeText(CameraPreview.this, str_msg, 
						Toast.LENGTH_SHORT).show();
					break;
				case MSG_ERROR:
					Toast.makeText(CameraPreview.this, str_msg, 
						Toast.LENGTH_SHORT).show();
					break;
                case MSG_REFRESH:
					cam_fps = (double)cam_frames / (double)((System.currentTimeMillis() - start_msec) / 1000);
					elapsedTime = System.currentTimeMillis() - start_msec;
					SimpleDateFormat dateFormat = new SimpleDateFormat("HH:mm:ss");
					dateFormat.setTimeZone(TimeZone.getTimeZone("UTC"));
					
					m_tv_info.setText(
						String.format("c/e %d(- %d %.2f%%)/%d, fps: %2.2f/%2.2f ",
						cam_frames, cam_drop_frames, 
						(double)cam_drop_frames * (double)100.0f / (double)cam_frames, 
						frames, cam_fps, m_enc_fps) + dateFormat.format(new Date(elapsedTime)));
					break;
				case MSG_FINISH:
					Toast.makeText(CameraPreview.this/*getApplicationContext()*/, "Encode all done", 
						Toast.LENGTH_SHORT).show();
					CameraPreview.this.getIntent().putExtra("result", "encode all done");
					CameraPreview.this.setResult(2, CameraPreview.this.getIntent());
					CameraPreview.this.finish();
					break;
				default:
					Log.w(CodecLib.LOG_TAG, String.format("unknown message: 0x%x", msg.what));
					break;
                }

                super.handleMessage(msg);  
            }  
        };  				
    }

    @Override
    protected void onResume() {
		Log.i(CodecLib.LOG_TAG, "onResume()");
		
        super.onResume();
		
		if(getRequestedOrientation()!=ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE){
			setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
		}

        // Open the default i.e. the first rear facing camera.
        mCamera = Camera.open();
        cameraCurrentlyLocked = defaultCameraId;
        mPreview.setCamera(mCamera);
    }

    @Override
    protected void onPause() {
		Log.i(CodecLib.LOG_TAG, "onPause()");
		
        super.onPause();
		
		m_bQuit = true; // signal working thread
		
        // Because the Camera object is a shared resource, it's very
        // important to release it when the activity is paused.
        if (mCamera != null) {
			// new added
			mCamera.setPreviewCallback(null);
            mCamera.stopPreview();
			
            mPreview.setCamera(null);
            mCamera.release();
            mCamera = null;
        }
		if(m_bRecording) {
			if (3 == m_type)
				CodecLib.StopRecord();
		}
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {

        // Inflate our menu which can gather user input for switching camera
        MenuInflater inflater = getMenuInflater();
        inflater.inflate(R.menu.camera_menu, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        // Handle item selection
        switch (item.getItemId()) {
        case R.id.switch_cam:
            // check for availability of multiple cameras
            if (numberOfCameras == 1) {
                AlertDialog.Builder builder = new AlertDialog.Builder(this);
                builder.setMessage(this.getString(R.string.camera_alert))
                       .setNeutralButton("Close", null);
                AlertDialog alert = builder.create();
                alert.show();
                return true;
            }
			
            // OK, we have multiple cameras.
            // Release this camera -> cameraCurrentlyLocked
            if (mCamera != null) {
				mCamera.setPreviewCallback(null);
                mCamera.stopPreview();
                mPreview.setCamera(null);
                mCamera.release();
                mCamera = null;
            }

            // Acquire the next camera and request Preview to reconfigure
            // parameters.
            mCamera = Camera.open((cameraCurrentlyLocked + 1) % numberOfCameras);
            cameraCurrentlyLocked = (cameraCurrentlyLocked + 1)
                    % numberOfCameras;
            mPreview.switchCamera(mCamera);

			mCamera.setPreviewCallback(this);
            // Start the preview
            mCamera.startPreview();
            return true;
        default:
            return super.onOptionsItemSelected(item);
        }
    }
	
	@Override
	public void onPreviewFrame(byte[] data, Camera camera) {
		//Log.d(CodecLib.LOG_TAG, "onPreviewFrame()");
		
		Camera.Parameters parameters = camera.getParameters();  
        final Camera.Size size=parameters.getPreviewSize();
		cap_frames++;
		if(data != null && cap_frames % 2 == 0) {
			final byte[] copyData = new byte[data.length];  
			System.arraycopy(data, 0, copyData, 0, data.length);
			putVideoFrame(copyData, size.width, size.height);	
		}
	}
	
	private void putVideoFrame(byte[] data,int width,int height){  
        if(!m_bRecording){  
            //Log.d(CodecLib.LOG_TAG, "putVideoFrame. no display frame");  
            return;  
        }
		cam_frames++;
        Log.d(CodecLib.LOG_TAG, "putVideoFrame.");  
		
		if( mFrameList.size() >= MAX_FRAME_NUM){
			Log.d(CodecLib.LOG_TAG, String.format("frame number is %d, too much, do drop", mFrameList.size()));  
			cam_drop_frames++;
			return;
			//Object[] obj=(Object[]) mFrameList.poll();  
			//mFrameList.clear();  
			//mFrameList.offer(obj);  
		} 
		
        ///synchronized(mFrameList){  
            Object[] obj=new Object[]{data, width, height};  
            mFrameList.offer(obj);  
        //}   
    }  

	public int dump_pic() {
		Log.i(CodecLib.LOG_TAG, "Java: TestCam dump_pic");
		
		long start_msec = System.currentTimeMillis();
		FileOutputStream fout_pic = null;
		
		try {
			File sdCardDir = Environment.getExternalStorageDirectory();
			File sdFile = new File(sdCardDir, m_out_filename);
			fout_pic = new FileOutputStream(sdFile);
		}
		catch (FileNotFoundException e) {
			e.printStackTrace();
			Message msg = new Message();  
			msg.what = MSG_FILE_NOT_FOUND;
			str_msg = "file not found: " + m_out_filename;
			mHandler.sendMessage(msg);
			return -1;
		}
		catch(Exception e){   
			e.printStackTrace();
			Message msg = new Message();
			msg.what = MSG_ERROR;
			mHandler.sendMessage(msg);
			Log.e(CodecLib.LOG_TAG, "an error occured while open file", e);
			return -1;
		}
			
		Log.i(CodecLib.LOG_TAG, "Java: TestCam ready to dump picture");

		frames = 0;
		while (!m_bQuit && frames < m_frames ) { 
			// get pic data
			Object[] obj = null;  
			obj = (Object[]) mFrameList.poll();
			if ( null == obj ) {
				Log.d(CodecLib.LOG_TAG, "Java: testCam picture list is empty");
				try {  
					Thread.sleep(1l);  
				} catch (InterruptedException e) {  
					e.printStackTrace();  
				}  
				continue;
			}
			Log.d(CodecLib.LOG_TAG, "Java: testCam obj:" + obj);

			try 
			{
				// save nv21 raw picture
				byte[] data = (byte[]) obj[0];
				fout_pic.write(data);
				Log.d(CodecLib.LOG_TAG, String.format("Java: testCam save raw picture #%d %d", 
					frames, data.length));
				frames++;
				if(frames % 5 == 0) {
					m_enc_fps = (double)(frames * 1000) / 
						(double)(System.currentTimeMillis() - start_msec);
					
					Message msg = new Message();  
					msg.what = MSG_REFRESH;
					mHandler.sendMessage(msg);
				}
			}
			catch (FileNotFoundException e) {
				e.printStackTrace();
			}
			catch(Exception e){   
				e.printStackTrace();
				Log.e(CodecLib.LOG_TAG, "an error occured while process picture", e);
			} 
		} 	
		
		m_enc_fps = (double)(frames * 1000) / (double)(System.currentTimeMillis() - start_msec);
		Log.i(CodecLib.LOG_TAG, 
			String.format("Java: TestCam dump_pic in/out %d/%d(total %d) frames, fps: %.2f", 
			frames, frames, m_frames, m_enc_fps));
			
		try {
			fout_pic.close();
		}
		catch (FileNotFoundException e) {
				e.printStackTrace();
		}
		catch(Exception e){   
			e.printStackTrace();
			Log.e(CodecLib.LOG_TAG, "an error occured while closing file", e);
		}
		
		return 0;
	}
	
	public int dump_h264() {
		Log.i(CodecLib.LOG_TAG, "Java: TestCam dump_h264");
		
		long start_msec = System.currentTimeMillis();
		FileOutputStream fout_h264 = null;
		
		try {
			File sdCardDir = Environment.getExternalStorageDirectory();
			File sdFile = new File(sdCardDir, m_out_filename);
			fout_h264 = new FileOutputStream(sdFile);
		}
		catch (FileNotFoundException e) {
			e.printStackTrace();
			Message msg = new Message();  
			msg.what = MSG_FILE_NOT_FOUND;
			str_msg = "file not found: " + m_out_filename;
			mHandler.sendMessage(msg);
			return -1;
		}
		catch(Exception e){   
			e.printStackTrace();
			Message msg = new Message();
			msg.what = MSG_ERROR;
			mHandler.sendMessage(msg);
			Log.e(CodecLib.LOG_TAG, "an error occured while open file", e);
			return -1;
		}
			
		Log.i(CodecLib.LOG_TAG, "Java: TestCam ready to dump h264");

		int ret;
		int handle = CodecLib.INVALID_HANDLE;
		Long opaque_in	= 1L;
		Long opaque_out = 0L;
		byte[] opa_in;
		byte[] opa_out 	= new byte[16];
		byte[] enc_data	= new byte[128 * 1024]; // 128k
		int frame_type;
		int frame_size;
		char cFrame;
		String info_msg;
		int in_frames, out_frames;
		double fps;
		
		handle = CodecLib.EasyEncoderOpen(m_width, m_height, 
			m_in_fmt, m_profile, m_enc_str);
		if(CodecLib.INVALID_HANDLE == handle) {
			Message msg = new Message();
			msg.what = MSG_ERROR;
			str_msg = "failed to open encoder";
			mHandler.sendMessage(msg);
			Log.e(CodecLib.LOG_TAG, "Java: TestCam failed to open encoder");
			return -1;
		}
		
		frames = 0;
		in_frames = out_frames = 0;
		
		while (!m_bQuit && frames < m_frames ) { 
			// get pic data
			Object[] obj = null;  
			obj = (Object[]) mFrameList.poll();
			if ( null == obj ) {
				Log.d(CodecLib.LOG_TAG, "Java: testCam picture list is empty");
				try {  
					Thread.sleep(10l);  
				} catch (InterruptedException e) {  
					e.printStackTrace();  
				}  
				continue;
			}
			Log.d(CodecLib.LOG_TAG, "Java: testCam obj:" + obj);

			
			// encode pic
			// add
			byte[] data = (byte[]) obj[0];
					
			Log.d(CodecLib.LOG_TAG, "Java: TestCam Add #" + frames);
			opa_in = CodecLib.LongToOpaque(opaque_in);
			ret = CodecLib.EasyEncoderAdd(handle, data, data.length, opa_in);
			if(ret < 0) {
				Log.e(CodecLib.LOG_TAG, "Java: TestCam failed to Add in #" + frames);
				break;
			}
			
			in_frames++;
			opaque_in++;
			
			// get
			ret = CodecLib.EasyEncoderGet(handle, enc_data, opa_out);		
			if(ret < 0) {
				Log.e(CodecLib.LOG_TAG, "Java: TestCam failed to Get in #" + frames);
				break;
			}
											
			if (ret > 0) {
				out_frames++;
				opaque_out = CodecLib.ByteToLong(opa_out);
				frame_type = CodecLib.OpaqueToType(opa_out);
				switch(frame_type) {
				case 0:
					cFrame = 'U';
					break;
				case 1:
					cFrame = 'I';
					break;
				case 2:
					cFrame = 'P';
					break;
				case 3:
					cFrame = 'B';
					break;
				default:
					cFrame = 'E';
					break;
				}
				info_msg = String.format("Java: TestCam opaque: %d(%c)", 
					opaque_out, cFrame);
				Log.d(CodecLib.LOG_TAG, info_msg);
				
				try 
				{
					frame_size = ret;
					if (m_is_writefile > 0) {
						Log.d(CodecLib.LOG_TAG, "Java: TestCam write file in #" + frames);
						if (m_is_writesize > 0) {
							byte []byte_size = CodecLib.IntToByte(frame_size);
							fout_h264.write(byte_size);
						}
						
						fout_h264.write(enc_data, 0, frame_size);
					}
				}
				catch (FileNotFoundException e) {
					e.printStackTrace();
					break;
				}
				catch(Exception e){   
					e.printStackTrace();
					Log.e(CodecLib.LOG_TAG, "an error occured while encoding h264", e);
					break;
				}
			}
					
			// calc sleep time
			long pause_time;
			long elapsed_msec = System.currentTimeMillis() - start_msec;
			pause_time = frames * 1000 / 15 - elapsed_msec; // msec @15fps
			if(pause_time > 5) {
				try {  
					Thread.sleep(pause_time);  
				} catch (InterruptedException e) {  
					e.printStackTrace();  
				}  
			}	
					
			frames++;
			if(frames % 5 == 0) {
				m_enc_fps = CodecLib.EasyEncoderGetFPS(handle);
				
				Message msg = new Message();  
				msg.what = MSG_REFRESH;
				mHandler.sendMessage(msg);
			}
		} 	
		
		m_enc_fps = (double)(frames * 1000) / (double)(System.currentTimeMillis() - start_msec);
		Log.i(CodecLib.LOG_TAG, 
			String.format("Java: TestCam dump_h264 in/out %d/%d(total %d) frames, fps: %.2f", 
			in_frames, out_frames, m_frames, m_enc_fps));
			
		CodecLib.EasyEncoderClose(handle);
		handle = CodecLib.INVALID_HANDLE;
		
		try {
			fout_h264.close();
		}
		catch (FileNotFoundException e) {
			e.printStackTrace();
			return -1;
		}
		catch(Exception e){   
			e.printStackTrace();
			Log.e(CodecLib.LOG_TAG, "an error occured while closing file", e);
			return -1;
		}
		
		return 0;
	}
	
	public int dump_ts() {
		Log.i(CodecLib.LOG_TAG, "Java: TestCam dump_ts");
		
		long start_msec = System.currentTimeMillis();
		FileOutputStream fout_ts = null;
		
		try {
			File sdCardDir = Environment.getExternalStorageDirectory();
			File sdFile = new File(sdCardDir, m_out_filename);
			fout_ts = new FileOutputStream(sdFile);
		}
		catch (FileNotFoundException e) {
			e.printStackTrace();
			Message msg = new Message();  
			msg.what = MSG_FILE_NOT_FOUND;
			str_msg = "file not found: " + m_out_filename;
			mHandler.sendMessage(msg);
			return -1;
		}
		catch(Exception e){   
			e.printStackTrace();
			Message msg = new Message();
			msg.what = MSG_ERROR;
			mHandler.sendMessage(msg);
			Log.e(CodecLib.LOG_TAG, "an error occured while open file", e);
			return -1;
		}
			
		Log.i(CodecLib.LOG_TAG, "Java: TestCam ready to dump ts");

		int ret;
		int handle = CodecLib.INVALID_HANDLE;
		Long opaque_in	= 1L;
		Long opaque_out = 0L;
		byte[] opa_in;
		byte[] opa_out 	= new byte[16];
		byte[] enc_data	= new byte[128 * 1024]; // 128k
		int frame_type;
		int frame_size;
		char cFrame;
		String info_msg;
		int in_frames, out_frames;
		double fps;
		
		handle = CodecLib.EasyffEncoderOpen(m_width, m_height, 
			m_in_fmt, m_profile, m_enc_str);
		if(CodecLib.INVALID_HANDLE == handle) {
			Message msg = new Message();
			msg.what = MSG_ERROR;
			str_msg = "failed to open ff encoder";
			mHandler.sendMessage(msg);
			Log.e(CodecLib.LOG_TAG, "Java: TestCam failed to open ff encoder");
			return -1;
		}
		
		frames = 0;
		in_frames = out_frames = 0;
		final int bufferSize = 8192;
		byte[] audio_data = new byte[bufferSize * 4]; 
		int audio_offset;
		long total_audio_len = 0;
				
		in_frames = out_frames = 0;
		
		while (!m_bQuit && frames < m_frames ) { 
			byte[] pic_data = null;
			int pic_len = 0;
			
			// get pic data
			Object[] obj = null;  
			obj = (Object[]) mFrameList.poll();
			if ( null == obj ) {
				Log.d(CodecLib.LOG_TAG, "Java: testCam picture list is empty");
				try {  
					Thread.sleep(10l);  
				} catch (InterruptedException e) {  
					e.printStackTrace();  
				}  
				continue;
			}
			Log.d(CodecLib.LOG_TAG, "Java: testCam obj:" + obj);

			// get audio buffer data
			int audio_len;
			audio_offset = 0;
			for (int i=0;i<2;i++) {
				// would block
				audio_len = CodecLib.GetAudioData(audio_data, audio_offset);
				audio_offset += audio_len;
				if(audio_len < 2048) {
					Log.d(CodecLib.LOG_TAG, "Java: testCam audio data is not enough: " + audio_len);
					break;
				}
			}
			
			total_audio_len += audio_offset;
			float v_t = (float)in_frames / (float)10.0f;
			float a_t = (float)total_audio_len / (float)(44100 * 2);
			float elapsed = v_t - a_t;
			Log.i(CodecLib.LOG_TAG, String.format("v.a.e %.3f %.3f %.3f", v_t, a_t, elapsed));
			if (v_t < a_t) {
				// need add picture
				byte[] data = (byte[]) obj[0];
				pic_data	= data;
				pic_len 	= data.length;
				Log.d(CodecLib.LOG_TAG, "add pic and audio");
			}
			else {
				// only audio
				pic_data	= null;
				pic_len 	= 0;
				Log.d(CodecLib.LOG_TAG, "add audio");
			}
			
			Log.d(CodecLib.LOG_TAG, "Java: TestCam Add #" + frames);
			opa_in = CodecLib.LongToOpaque(opaque_in);
			ret = CodecLib.EasyffEncoderAdd(handle, pic_data, pic_len, 
				audio_data, audio_offset, opa_in);
			if(ret < 0) {
				Log.e(CodecLib.LOG_TAG, "Java: TestCam failed to Add in #" + frames);
				break;
			}
			
			if ( pic_data != null)
				in_frames++;
			opaque_in++;		
			
			// get
			ret = CodecLib.EasyffEncoderGet(handle, enc_data, opa_out);		
			if(ret < 0) {
				Log.e(CodecLib.LOG_TAG, "Java: TestCam failed to Get in #" + frames);
				break;
			}
											
			if (ret > 0) {
				Log.d(CodecLib.LOG_TAG, String.format("Java: TestCam Get #%d %d", out_frames, ret));
				out_frames++;
				
				try 
				{
					if (m_is_writefile > 0) {
						frame_size = ret;
						Log.d(CodecLib.LOG_TAG, "Java: TestCam write file in #" + frames);
						fout_ts.write(enc_data, 0, frame_size);
					}
				}
				catch (FileNotFoundException e) {
					e.printStackTrace();
					break;
				}
				catch(Exception e){   
					e.printStackTrace();
					Log.e(CodecLib.LOG_TAG, "an error occured while encoding ts", e);
					break;
				}
			}
					
			frames++;
			if(frames % 5 == 0) {
				m_enc_fps = CodecLib.EasyffEncoderGetFPS(handle);
				
				Message msg = new Message();  
				msg.what = MSG_REFRESH;
				mHandler.sendMessage(msg);
			}
		} 	
		
		m_enc_fps = (double)(frames * 1000) / (double)(System.currentTimeMillis() - start_msec);
		Log.i(CodecLib.LOG_TAG, 
			String.format("Java: TestCam dump_ts in/out %d/%d(total %d) frames, fps: %.2f", 
			in_frames, out_frames, m_frames, m_enc_fps));
			
		CodecLib.EasyffEncoderClose(handle);
		handle = CodecLib.INVALID_HANDLE;
				
		try {
			fout_ts.close();
		}
		catch (FileNotFoundException e) {
			e.printStackTrace();
			return -1;
		}
		catch(Exception e){   
			e.printStackTrace();
			Log.e(CodecLib.LOG_TAG, "an error occured while closing file", e);
			return -1;
		}
		
		return 0;
	}
	
	public int OnStartPreview() {
		cap_frames		= 0;
		cam_frames		= 0;
		cam_drop_frames	= 0;
		start_msec		= System.currentTimeMillis();
		
		mFrameThread = new Thread(new Runnable(){
			private static final boolean bFFencoder = true;
			
			@Override  
			public void run() {  
				Log.i(CodecLib.LOG_TAG, "Java: TestCam Encode thread started.");
				
				int ret = -1;
				
				switch(m_in_fmt) {
				case 2: // nv21
					m_pic_size = m_width * m_height * 3 / 2;
					break;
				default:
					Log.e(CodecLib.LOG_TAG, "Java: TestCam Encode fmt not supported");
					Toast.makeText(CameraPreview.this, "TestCam fmt not supported" + m_in_fmt, 
						Toast.LENGTH_SHORT).show();
					return;
				}
				switch (m_type) {
				case 2:
					ret = dump_h264();
					break;
				case 3:
					ret = dump_ts();
					break;
				case 4:
					ret = dump_pic();
					break;
				default:
					Log.e(CodecLib.LOG_TAG, "Java: TestCam Encode type not supported");
					break;
				}
				
				m_bRecording = false;
				
				mFrameList.clear();  
				if (0 == ret) {
					Message msg = new Message();  
					msg.what = MSG_FINISH;
					mHandler.sendMessage(msg);
				}
				Log.i(CodecLib.LOG_TAG, "Java: TestCam Encode thread exited.");
			}  
		});
		//mFrameThread.setPriority(4);
		mFrameThread.start();
		return 0;
	}

}

// ----------------------------------------------------------------------

/**
 * A simple wrapper around a Camera and a SurfaceView that renders a centered preview of the Camera
 * to the surface. We need to center the SurfaceView because not all devices have cameras that
 * support preview sizes at the same aspect ratio as the device's display.
 */
class Preview extends ViewGroup implements SurfaceHolder.Callback {

    SurfaceView mSurfaceView;
    SurfaceHolder mHolder;
    Size mPreviewSize;
    List<Size> mSupportedPreviewSizes;
    Camera mCamera;
	CameraPreview mIns;

    Preview(Context context) {
        super(context);

        mSurfaceView = new SurfaceView(context);
        addView(mSurfaceView);

        // Install a SurfaceHolder.Callback so we get notified when the
        // underlying surface is created and destroyed.
        mHolder = mSurfaceView.getHolder();
        mHolder.addCallback(this);
        mHolder.setType(SurfaceHolder.SURFACE_TYPE_PUSH_BUFFERS);
		//mHolder.setFixedSize(352, 288);
    }

    public void setCamera(Camera camera) {
        mCamera = camera;
        if (mCamera != null) {
            mSupportedPreviewSizes = mCamera.getParameters().getSupportedPreviewSizes();
			
            requestLayout();
        }
    }
	
	public void setInstance(CameraPreview ins) {
		mIns = ins;
	}

    public void switchCamera(Camera camera) {
		Log.i(CodecLib.LOG_TAG, "switchCamera()");
		setCamera(camera);
		try {
			camera.setPreviewDisplay(mHolder);
		}
		catch (IOException exception) {
			Log.e(CodecLib.LOG_TAG, "IOException caused by setPreviewDisplay()", exception);
		}
		Camera.Parameters parameters = camera.getParameters();

		boolean bCamSize = false;
		final List<Size> list_preview_size = parameters.getSupportedPreviewSizes();
		int res_number = list_preview_size.size();
		final String str_resolution[] = new String[res_number];
		int res_cnt = 0;
				
		for (Size size : list_preview_size) {
			str_resolution[res_cnt] = String.format("%d x %d", size.width, size.height);
			res_cnt++;
			
			Log.d(CodecLib.LOG_TAG, String.format("supported preview size: %dx%d", 
				size.width, size.height));
			if(size.width == mIns.m_width && size.height == mIns.m_height) {
				bCamSize = true;
				Log.i(CodecLib.LOG_TAG, 
					String.format("Java CameraPreview: new camera preview size is supported %dx%d",
					size.width, size.height));
			}
		}
		
		if(!bCamSize) {
			Log.i(CodecLib.LOG_TAG, "Java CameraPreview: need re-choose camera preview size");
			Toast.makeText(mIns, getResources().getString(R.string.switch_cam_resolution_bad), 
				Toast.LENGTH_SHORT).show();
			
			Dialog choose_cam_res_dlg = new AlertDialog.Builder(mIns)
				.setTitle(getResources().getString(R.string.select_cam_resolution))
				.setSingleChoiceItems(str_resolution, -1, /*default selection item number*/
					new DialogInterface.OnClickListener(){
					public void onClick(DialogInterface dialog, int whichButton){
						mIns.m_width = list_preview_size.get(whichButton).width;
						mIns.m_height = list_preview_size.get(whichButton).height;
						dialog.cancel();
						Log.i(CodecLib.LOG_TAG, String.format("Java CameraPreview: choose %d %s", 
								whichButton, str_resolution[whichButton]));
					}
				})
				.setNegativeButton(getResources().getString(R.string.select_cam_resolution_cancel), 
					new DialogInterface.OnClickListener(){
						public void onClick(DialogInterface dialog, int whichButton){
					}})
				.create();
			choose_cam_res_dlg.show();
		}

		if(bCamSize)
			parameters.setPreviewSize(/*mPreviewSize.width, mPreviewSize.height*/mIns.m_width, mIns.m_height);
		requestLayout();

		camera.setParameters(parameters);
	}

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        // We purposely disregard child measurements because act as a
        // wrapper to a SurfaceView that centers the camera preview instead
        // of stretching it.
        final int width = resolveSize(getSuggestedMinimumWidth(), widthMeasureSpec);
        final int height = resolveSize(getSuggestedMinimumHeight(), heightMeasureSpec);
        setMeasuredDimension(width, height);
		Log.i(CodecLib.LOG_TAG, String.format("setMeasuredDimension %dx%d", width, height));

        if (mSupportedPreviewSizes != null) {
            mPreviewSize = getOptimalPreviewSize(mSupportedPreviewSizes, width, height);
        }
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
		Log.i(CodecLib.LOG_TAG, String.format("onLayout() %d.%d.%d.%d", l, t, r, b));
		
        if (changed && getChildCount() > 0) {
            final View child = getChildAt(0);

            final int width = r - l;
            final int height = b - t;

            int previewWidth = width;
            int previewHeight = height;
            if (mPreviewSize != null) {
                previewWidth = mPreviewSize.width;
                previewHeight = mPreviewSize.height;
            }

            // Center the child SurfaceView within the parent.
            if (width * previewHeight > height * previewWidth) {
                final int scaledChildWidth = previewWidth * height / previewHeight;
                child.layout((width - scaledChildWidth) / 2, 0,
                        (width + scaledChildWidth) / 2, height);
            } else {
                final int scaledChildHeight = previewHeight * width / previewWidth;
                child.layout(0, (height - scaledChildHeight) / 2,
                        width, (height + scaledChildHeight) / 2);
            }
        }
    }

    public void surfaceCreated(SurfaceHolder holder) {
		Log.i(CodecLib.LOG_TAG, "surfaceCreated()");
        // The Surface has been created, acquire the camera and tell it where
        // to draw.
        try {
            if (mCamera != null) {
                mCamera.setPreviewDisplay(holder);
            }
        } catch (IOException exception) {
            Log.e(CodecLib.LOG_TAG, "IOException caused by setPreviewDisplay()", exception);
        }
    }

    public void surfaceDestroyed(SurfaceHolder holder) {
		Log.i(CodecLib.LOG_TAG, "surfaceDestroyed()");
        // Surface will be destroyed when we return, so stop the preview.
        if (mCamera != null) {
			mCamera.setPreviewCallback(null);
            mCamera.stopPreview();
        }
    }


    private Size getOptimalPreviewSize(List<Size> sizes, int w, int h) {
        final double ASPECT_TOLERANCE = 0.1;
        double targetRatio = (double) w / h;
        if (sizes == null) return null;

        Size optimalSize = null;
        double minDiff = Double.MAX_VALUE;

        int targetHeight = h;

        // Try to find an size match aspect ratio and size
        for (Size size : sizes) {
            double ratio = (double) size.width / size.height;
            if (Math.abs(ratio - targetRatio) > ASPECT_TOLERANCE) continue;
            if (Math.abs(size.height - targetHeight) < minDiff) {
                optimalSize = size;
                minDiff = Math.abs(size.height - targetHeight);
            }
        }

        // Cannot find the one match the aspect ratio, ignore the requirement
        if (optimalSize == null) {
            minDiff = Double.MAX_VALUE;
            for (Size size : sizes) {
                if (Math.abs(size.height - targetHeight) < minDiff) {
                    optimalSize = size;
                    minDiff = Math.abs(size.height - targetHeight);
                }
            }
        }
		optimalSize.width 	= mIns.m_width;
		optimalSize.height	= mIns.m_height;
        return optimalSize;
    }

    public void surfaceChanged(SurfaceHolder holder, int format, int w, int h) {
		Log.i(CodecLib.LOG_TAG, "surfaceChanged()");
		
        // Now that the size is known, set up the camera parameters and begin
        // the preview.
        Camera.Parameters parameters = mCamera.getParameters();
		
		// get some info
		int pic_fmt = parameters.getPictureFormat();
		Size pic_size = parameters.getPictureSize();
		Log.i(CodecLib.LOG_TAG, String.format("picture format %d, size %dx%d", 
			pic_fmt, pic_size.width, pic_size.height));
		
		int preview_fmt = parameters.getPreviewFormat();// nv21: 17 (0x00000011)
		Size preview_size = parameters.getPictureSize();
		Log.i(CodecLib.LOG_TAG, String.format("preview format %d, size %dx%d", 
			preview_fmt, preview_size.width, preview_size.height));
		
		List<Integer> list_fmt = parameters.getSupportedPictureFormats();
		Iterator<Integer> it_fmt = list_fmt.iterator();
		while (it_fmt.hasNext()) {
			Log.i(CodecLib.LOG_TAG, "supported picture format: " + it_fmt.next());
		}
		
		List<Size> list_pic_size = parameters.getSupportedPictureSizes();
		for (Size size : list_pic_size) {
			Log.d(CodecLib.LOG_TAG, String.format("supported picture size: %dx%d", 
				size.width, size.height));
		}
		
		List<Size> list_preview_size = parameters.getSupportedPreviewSizes();
		for (Size size : list_preview_size) {
			Log.d(CodecLib.LOG_TAG, String.format("supported preview size: %dx%d", 
				size.width, size.height));
		}
		
		parameters.setPreviewSize(/*mPreviewSize.width, mPreviewSize.height*/mIns.m_width, mIns.m_height);
		
		int currentVersion = VERSION.SDK_INT;
		if (currentVersion >= VERSION_CODES.GINGERBREAD) {
			List<int[]> list_rate = parameters.getSupportedPreviewFpsRange();
			for (int[] range : list_rate) {
				Log.i(CodecLib.LOG_TAG, String.format("supported preview fps range: %d - %d", 
					range[0], range[1]));
			}
			
			parameters.setPreviewFpsRange(30000, 30000);
		}
		
		//parameters.setRotation(90); 
		parameters.setFocusMode(Camera.Parameters.FOCUS_MODE_CONTINUOUS_VIDEO);
        requestLayout();
		Log.i(CodecLib.LOG_TAG, String.format("set preview size %dx%d", 
			mPreviewSize.width, mPreviewSize.height));
		
        mCamera.setParameters(parameters);
		//mCamera.setDisplayOrientation(90);
		mCamera.setPreviewCallback(mIns);
		
        mCamera.startPreview();
		//mIns.OnStartPreview();
    }
	
}


