package tv.xormedia.AndCodec;

import android.os.Bundle;
import android.app.Activity;
import android.view.Menu;
import android.view.View;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.TextView;
import android.widget.EditText;
import android.widget.CheckBox;
import android.widget.Toast;
import android.app.ProgressDialog;
import android.view.WindowManager;
import android.content.DialogInterface;
import android.content.pm.ActivityInfo;
import android.content.DialogInterface;
import android.util.Log;
import android.content.Intent;
import android.os.Environment;
import android.app.AlertDialog;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import	java.io.FileOutputStream;
import	java.io.FileInputStream;
import java.nio.ByteBuffer;

// for thread
import android.os.Handler;  
import android.os.Message; 

import android.app.Dialog;
import android.app.AlertDialog;

import android.hardware.Camera;
import android.hardware.Camera.CameraInfo;
import android.hardware.Camera.Size;
import java.util.List;

public class TestDec extends Activity {
	private final int SKIP_FRAMES		= 0;

	private int m_width, m_height;
	private String m_in_filename, m_out_filename;
	private int m_out_fmt;
	private int m_frames;
	private boolean m_bWritefile;
	private int m_pic_size;
	private int m_stop_dec = 0;
	
	private EditText et_in_filename 	= null;
	private EditText et_out_filename	= null;
	private EditText et_width 			= null;
	private EditText et_height 			= null;
	private EditText et_out_fmt 		= null;
	private EditText et_frames			= null;
	
	private Button btn_decode			= null;
	private ImageButton btn_run_resolution	= null;
	private Button btn_jump2manual 		= null;
	private CheckBox cb_write 			= null;
	private TextView tv_dec_info 		= null;
	private String str_dec_info;
	private ProgressDialog progDlg 		= null;
	private TextView tv_home_folder		= null;
	
	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		setContentView(R.layout.activity_test_dec);
		
		super.getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
		
		//in out file
		this.et_in_filename = (EditText) findViewById(R.id.edit_dec_in_file); 
		this.et_out_filename = (EditText) findViewById(R.id.edit_dec_out_file);
		this.et_width = (EditText) findViewById(R.id.edit_width);
		this.et_height = (EditText) findViewById(R.id.edit_height);
		this.et_out_fmt = (EditText) findViewById(R.id.edit_out_fmt);
		
		this.tv_home_folder = (TextView) findViewById(R.id.tv_dec_home_folder);
		this.tv_home_folder.setText(getResources().getString(R.string.tv_home_folder_text) + 
			Environment.getExternalStorageDirectory().getAbsolutePath());
		
		this.tv_dec_info = (TextView) findViewById(R.id.tv_dec_result);
						
		this.cb_write = (CheckBox) findViewById(R.id.cb_write_file);
		this.cb_write.setChecked(true);
		
		this.et_frames = (EditText) findViewById(R.id.edit_frames);
		
		this.btn_jump2manual = (Button)findViewById(R.id.btn_jump2manual);
		this.btn_jump2manual.setOnClickListener(new View.OnClickListener(){
			@Override
			public void onClick(View v){
				Intent intent = new Intent();
				intent.setClass(TestDec.this, Manual.class);
				startActivity(intent);
				finish();
			}
		});
		
		this.btn_run_resolution = (ImageButton)findViewById(R.id.btn_dec_res);
		this.btn_run_resolution.setOnClickListener(new View.OnClickListener(){
			@Override
			public void onClick(View v){
				SelectCamRes();
			}
		});
		
		this.btn_decode = (Button)findViewById(R.id.btn_decode);
		this.btn_decode.setOnClickListener(new View.OnClickListener(){
			@Override
			public void onClick(View v){
				Log.i(CodecLib.LOG_TAG, "Java: TestDec decode");
				
				GetParam();
				
				String title 	= (String) getResources().getString(R.string.app_name);
				String message	= (String) getResources().getString(R.string.dec_prog_dlg_msg);
				
				//display ProgressDialog
				progDlg = new ProgressDialog(TestDec.this);
				progDlg.setProgressStyle(ProgressDialog.STYLE_HORIZONTAL);
				progDlg.setTitle(title);
				progDlg.setMessage(message);
				progDlg.setMax(m_frames);
				progDlg.setIndeterminate(false);
				progDlg.setCancelable(true);
				progDlg.setOnCancelListener(new DialogInterface.OnCancelListener() {
					@Override
					public void onCancel(DialogInterface dialog) {
						// TODO Auto-generated method stub
						m_stop_dec = 1;
						progDlg.dismiss();
				   }
				});
				progDlg.show(); 
                   
                new Thread(){  
  
                    @Override  
                    public void run() {  
                        // todo job
                        int ret;
						
						// block
						ret = do_decode_job();
						
						//handler.sendEmptyMessage(0);  
                    }
				}.start();		
			}
		});
	
		if(CodecLib.IsSupportNeon() > 0) {
			Toast.makeText(TestDec.this, "load neon lib", 
				Toast.LENGTH_LONG).show();
		}
		else {
			Toast.makeText(TestDec.this, "load generic lib", 
				Toast.LENGTH_LONG).show();
		}
		
	}
	
	void SelectCamRes() {
		int defaultCameraId = 0;
		int cameraCurrentlyLocked;
		int numberOfCameras;
		
		numberOfCameras = Camera.getNumberOfCameras();
		CameraInfo cameraInfo = new CameraInfo();
		for (int i = 0; i < numberOfCameras; i++) {
			Camera.getCameraInfo(i, cameraInfo);
			if (cameraInfo.facing == CameraInfo.CAMERA_FACING_BACK) {
				defaultCameraId = i;
			}
		}
		Camera mCamera = Camera.open();
        cameraCurrentlyLocked = defaultCameraId;
		
		Camera.Parameters parameters = mCamera.getParameters();
		
		final List<Size> list_preview_size = parameters.getSupportedPreviewSizes();
		int res_number = list_preview_size.size();
		final String 	str_resolution[] 	= new String[res_number];
		int res_cnt = 0;
		
		for (Size size : list_preview_size) {
			str_resolution[res_cnt]	= String.format("%d x %d", size.width, size.height);
			res_cnt++;
		}
		
		Dialog choose_cam_res_dlg = new AlertDialog.Builder(TestDec.this)
			.setTitle(getResources().getString(R.string.select_cam_resolution))
			./*setItems*/setSingleChoiceItems(str_resolution, -1, /*default selection item number*/
				new DialogInterface.OnClickListener(){
				public void onClick(DialogInterface dialog, int whichButton){
					et_width.setText(String.valueOf(list_preview_size.get(whichButton).width));
					et_height.setText(String.valueOf(list_preview_size.get(whichButton).height));
					dialog.cancel();
					Log.i(CodecLib.LOG_TAG, String.format("Java TestDec: choose %d %s", 
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
	
	@Override
	public boolean onCreateOptionsMenu(Menu menu) {
		// Inflate the menu; this adds items to the action bar if it is present.
		getMenuInflater().inflate(R.menu.activity_test_dec, menu);
		return true;
	}
	
	private Handler handler = new Handler(){  
  
        @Override  
        public void handleMessage(Message msg) {  
            switch(msg.what) {
			case CodecLib.MSG_REFRESH: 
				progDlg.setMessage(str_dec_info);
				break;
			case CodecLib.MSG_FINISH: 
			case CodecLib.MSG_ERROR:
				progDlg.dismiss();
				tv_dec_info.setText(str_dec_info);
				break;
			default:
				Log.w(CodecLib.LOG_TAG, "unknown msg.what " + msg.what);
				break;
			}			 
        }
	};  
	
	private int do_decode_job() {
	
		int in_frames, out_frames;
		
		byte[] decdata = new byte[1048576];
		byte[] pic_data = new byte[m_pic_size];
		ByteBuffer decdata_direct = ByteBuffer.allocateDirect(1048576);
		ByteBuffer pic_data_direct = ByteBuffer.allocateDirect(m_pic_size);
		
		byte[] size_buf = new byte[4];
		Long opaque_in = 1L;
		Long opaque_out = 0L;
		byte[] opa_in;
		byte[] opa_out = new byte[16];
		int decdata_size;
		int frame_size;
		int ret;
		
		int handle;
		handle = CodecLib.EasyDecoderOpen(m_width, m_height, m_out_fmt);
		if (CodecLib.INVALID_HANDLE == handle) {
			Log.e(CodecLib.LOG_TAG, "Java: TestDec failed to open decoder");
			str_dec_info = "failed to open decoder";
			Message msg = new Message();  
			msg.what = CodecLib.MSG_ERROR;  
			handler.sendMessage(msg); 
			return -1;
		}
		
		FileInputStream fin = null;
		FileOutputStream fout = null;
		File sdCardDir = Environment.getExternalStorageDirectory();
		
		String info_msg;
				
		Log.i(CodecLib.LOG_TAG, "Java: TestDec start to decode...");
		
		m_stop_dec = 0;
		
		try{
			if(-1 == m_in_filename.indexOf('/')) {
				Log.i(CodecLib.LOG_TAG, "Java: TestDec input file is in package: " + m_in_filename);
				fin = openFileInput(m_in_filename);
			}
			else {
				Log.d(CodecLib.LOG_TAG, "Java: TestDec input file is in sdcard: " + m_in_filename);
				File sdInFile = new File(sdCardDir, m_in_filename);
				fin = new FileInputStream(sdInFile);
			}
			
			int length = fin.available();
			if(length < 1024) {
				Log.e(CodecLib.LOG_TAG, "Java: TestDec input file is too small");
				fin.close();
				CodecLib.EasyDecoderClose(handle);
				str_dec_info = "input file is too small";
				Message msg = new Message();  
				msg.what = CodecLib.MSG_ERROR;  
				handler.sendMessage(msg); 
				return -1;
			}

			if(-1 == m_out_filename.indexOf("/")) {
				Log.i(CodecLib.LOG_TAG, "Java: TestDec output file is in package: " + m_out_filename);
				fout = openFileOutput(m_out_filename, MODE_PRIVATE);
			}
			else {
				Log.d(CodecLib.LOG_TAG, "Java: TestDec output file is in sdcard: " + m_out_filename);
				File sdFile = new File(sdCardDir, m_out_filename);
				fout = new FileOutputStream(sdFile);
			}
			
			in_frames = out_frames = 0;
			int i;
			for (i =0 ; i < m_frames ; i++) {
				if (m_stop_dec > 0) {
					Log.w(CodecLib.LOG_TAG, "Java: TestDec aborted");
					break;
				}
				//in
				ret = fin.read(size_buf, 0, 4);
				if(ret != 4) {
					Log.i(CodecLib.LOG_TAG, "Java: TestDec in_file eof");
					fin.close();
					File sdInFile = new File(sdCardDir, m_in_filename);
					fin = new FileInputStream(sdInFile);
					ret = fin.read(size_buf, 0, 4);
					if(ret != 4) {
						Log.i(CodecLib.LOG_TAG, "Java: TestDec in_file failed to reset");
						break;
					}
				}
				
				decdata_size = CodecLib.ByteToInt(size_buf);
				if(decdata_size < 0 || decdata_size > 1048576) {
					info_msg = "Java: TestDec h264 data is corrupt: " + Long.toString(decdata_size);
					Log.e(CodecLib.LOG_TAG, info_msg);
					break;
				}
				
				ret = fin.read(decdata, 0, decdata_size);
				if (ret != decdata_size) {
					Log.i(CodecLib.LOG_TAG, "Java: TestDec infile eof(data)");
					break;
				}
				
				if (CodecLib.USE_NATIVEIO) {
					decdata_direct.position(0);
					decdata_direct.put(decdata, 0, ret);
				}
				
				opa_in = CodecLib.LongToOpaque(opaque_in);
				
				if (i < SKIP_FRAMES) {
					Log.w(CodecLib.LOG_TAG, "skip frame: #" + i);
					continue;
				}
					
				if (CodecLib.USE_NATIVEIO) {
					ret = CodecLib.EasyDecoderAdd(handle, decdata_direct, decdata_size, opa_in);
				}
				else {
					ret = CodecLib.EasyDecoderAdd(handle, decdata, decdata_size, opa_in);
				}
				if(ret < 0) {
					info_msg = "Java: TestDec failed to Add in #" + Long.toString(i);
					Log.e(CodecLib.LOG_TAG, info_msg);
					break;
				}
				
				in_frames++;
				opaque_in++;
				
				//out
				if (CodecLib.USE_NATIVEIO) {
					ret = CodecLib.EasyDecoderGet(handle, pic_data_direct, opa_out);
				}
				else {
					ret = CodecLib.EasyDecoderGet(handle, pic_data, opa_out);
				}
				if(ret < 0) {
					info_msg = "Java: TestDec failed to Get in #" + Long.toString(i);
					Log.e(CodecLib.LOG_TAG, info_msg);
					break;
				}
				
				if (ret > 0) {
					frame_size = ret;
					if(m_bWritefile) {
						if (CodecLib.USE_NATIVEIO) {
							pic_data_direct.position(0);
							pic_data_direct.get(pic_data, 0, frame_size);
						}
						
						info_msg = "Java: TestDec write file " + Long.toString(frame_size);
						Log.d(CodecLib.LOG_TAG, info_msg);
						fout.write(pic_data, 0, frame_size);
					}
					out_frames++;
					opaque_out = CodecLib.ByteToLong(opa_out);
					Log.d(CodecLib.LOG_TAG, "Java: TestDec opaque: " + 
						Long.toString(opaque_out));
					progDlg.setProgress(i);
				}
				
				//calc instant fps
				if(i % 10 == 0) {
					double fps = CodecLib.EasyDecoderGetFPS(handle);
					String message	= (String) getResources().getString(R.string.dec_prog_dlg_msg);
					str_dec_info = message + String.format(" (fps: %.2f)", fps);
					Message msg = new Message();  
					msg.what = CodecLib.MSG_REFRESH;  
					handler.sendMessage(msg); 
				}
			}
			
			double fps = CodecLib.EasyDecoderGetFPS(handle);
			if(fps > 0.01f) {
				str_dec_info = String.format("i/o: %d/%d(all %d) frm, fps: %.2f", 
					in_frames, out_frames,
					m_frames, fps);
				Message msg = new Message();  
				msg.what = CodecLib.MSG_FINISH;  
				handler.sendMessage(msg); 
			}
			else {
				str_dec_info = "failed to get fps";
				Message msg = new Message();  
				msg.what = CodecLib.MSG_ERROR;  
				handler.sendMessage(msg); 
			}
		
			fin.close();
			fout.close();
			
		}
		catch (FileNotFoundException e) {
			Log.e(CodecLib.LOG_TAG, "file not found");
			str_dec_info = "file not found";
			Message msg = new Message();  
			msg.what = CodecLib.MSG_ERROR;  
			handler.sendMessage(msg);
		}
		catch(Exception e){   
			e.printStackTrace();
			Log.e(CodecLib.LOG_TAG, "an error occured while decoding...", e);
			Message msg = new Message();  
			msg.what = CodecLib.MSG_ERROR;  
			handler.sendMessage(msg);
		}
		
		CodecLib.EasyDecoderClose(handle);
		Log.i(CodecLib.LOG_TAG, "Java: TestDec Decode all done");
		return 0;
	}
	
	@Override
    protected void onResume() {
        super.onResume();
		
		if(getRequestedOrientation()!=ActivityInfo.SCREEN_ORIENTATION_PORTRAIT){
			setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
		}
    }
	
	private void GetParam() {
		String tmp;
		
		//in out file
		m_in_filename = et_in_filename.getText().toString();
		m_out_filename = et_out_filename.getText().toString();
		//input info
		tmp = et_width.getText().toString();
		m_width = Integer.parseInt(tmp);
		tmp = et_height.getText().toString();
		m_height = Integer.parseInt(tmp);
		tmp = et_out_fmt.getText().toString();
		m_out_fmt = Integer.parseInt(tmp);
		switch(m_out_fmt) {
		case 0://bgr565be
			m_pic_size = m_width * m_height * 2;
			break;
		case 3://yuv420p
			m_pic_size = m_width * m_height * 2;
			break;
		default:
			m_pic_size = m_width * m_height * 3;
			Log.e(CodecLib.LOG_TAG, "Java: TestDec Decode format not supported");
			break;
		}
		
		if(cb_write.isChecked())
			m_bWritefile = true;
		else
			m_bWritefile = false;
		tmp = et_frames.getText().toString();
		m_frames = Integer.parseInt(tmp);
	}
	
}
