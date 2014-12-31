package tv.xormedia.AndCodec;

import android.os.Bundle;
import android.os.Build.*;
import android.app.Activity;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.TextView;
import android.widget.AutoCompleteTextView;
import android.widget.MultiAutoCompleteTextView;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.Toast;
import android.app.ProgressDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.view.WindowManager;
import android.util.Log;
import android.os.Environment;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import	java.io.FileOutputStream;
import	java.io.FileInputStream;
//import java.io.RandomAccessFile;
import java.nio.ByteBuffer;

// for thread
import android.os.Handler;  
import android.os.Message;

import android.hardware.Camera;
import android.hardware.Camera.CameraInfo;
import android.hardware.Camera.Size;
import java.util.List;

import android.app.Dialog;
import android.app.AlertDialog;
import android.app.AlertDialog.Builder;

// for external storage path
import java.lang.Runtime;
import java.io.InputStream;
import java.io.BufferedReader;
import java.io.InputStreamReader;

public class TestEnc extends Activity {

	private int m_width, m_height;
	private String m_in_filename, m_out_filename;
	private int m_in_fmt;
	private int m_frames;
	private String m_profile, m_enc_str;
	private boolean m_bWritefile					= true;
	private boolean m_bWriteSize					= false;
	private boolean m_bUseJavaTest					= true;
	private int m_pic_size;
	private int m_stop_enc = 0;
	private int m_lasttype = 0; // 0 raw->h264, 1 raw->ts, 2 cam->h264, 3 cam->ts, 4 cam->nv21
	
	private AutoCompleteTextView et_in_filename		= null;
	private EditText et_out_filename				= null;
	private EditText et_width 						= null;
	private EditText et_height 						= null;
	private EditText et_in_fmt 						= null;
	private AutoCompleteTextView et_preset 			= null;
	private AutoCompleteTextView et_enc_settings	= null;
	private CheckBox cb_write 						= null;
	private CheckBox cb_write_size					= null;
	private EditText et_frames						= null;
	private TextView tv_enc_info 					= null;
	private Button	 btn_mode						= null;
	private ImageButton	 btn_run_resolution			= null;
	private Button	 btn_run						= null;
	private Button	 btn_jump2dec					= null;
	public ProgressDialog progDlg 					= null;
	private TextView tv_home_folder					= null;
	public String str_enc_info;
	
	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		setContentView(R.layout.activity_test_enc);
		
		CodecLib.Register(this);
		
		super.getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
		
		this.et_in_filename = (AutoCompleteTextView) findViewById(R.id.edit_enc_in_file);
		this.et_out_filename = (EditText) findViewById(R.id.edit_enc_out_file);
		this.et_width = (EditText) findViewById(R.id.edit_width);
		this.et_height = (EditText) findViewById(R.id.edit_height);
		this.et_in_fmt = (EditText) findViewById(R.id.edit_in_fmt);
		this.et_preset = (AutoCompleteTextView) findViewById(R.id.edit_preset);
		this.et_enc_settings = (AutoCompleteTextView) findViewById(R.id.edit_enc_settings);
		this.tv_enc_info = (TextView) findViewById(R.id.tv_enc_result);
		this.et_frames = (EditText) findViewById(R.id.edit_frames);
		
		this.tv_home_folder = (TextView) findViewById(R.id.tv_home_folder);
		this.tv_home_folder.setText(getResources().getString(R.string.tv_home_folder_text) + 
			Environment.getExternalStorageDirectory().getAbsolutePath());
		
		this.cb_write = (CheckBox) findViewById(R.id.cb_write_file);
		//this.cb_write.setChecked(true);
		
		String[] preset_tpl = getResources().getStringArray(R.array.preset_disp);
		ArrayAdapter<String> adapt_preset = new ArrayAdapter<String>(getApplicationContext(),   
			android.R.layout.simple_dropdown_item_1line,   
			preset_tpl);   
		this.et_preset.setAdapter(adapt_preset);
		
		String[] enc_settings_tpl = getResources().getStringArray(R.array.enc_settings_disp);   
		ArrayAdapter<String> adapt_enc_settings = new ArrayAdapter<String>(getApplicationContext(), 
			android.R.layout.simple_dropdown_item_1line, 
			enc_settings_tpl);
		//this.et_enc_settings.setTokenizer(new MultiAutoCompleteTextView.CommaTokenizer()); // ',' seperator
		this.et_enc_settings.setAdapter(adapt_enc_settings);
		
		String[] in_file_tpl = getResources().getStringArray(R.array.enc_in_file);
		ArrayAdapter<String> adapt_in_file = new ArrayAdapter<String>(getApplicationContext(),   
			android.R.layout.simple_dropdown_item_1line,   
			in_file_tpl);   
		this.et_in_filename.setAdapter(adapt_in_file);
		
		this.cb_write_size = (CheckBox) findViewById(R.id.cb_write_size);
		this.cb_write_size.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener(){  
            @Override  
            public void onCheckedChanged(CompoundButton buttonView,  
                    boolean isChecked) {  
                // TODO Auto-generated method stub 
				String et_text;
                if (isChecked) {
					et_text = (String)getResources().getString(R.string.et_enc_out_dump_text);
					cb_write.setChecked(true);
                }else{  
                    et_text = (String)getResources().getString(R.string.et_enc_out_file_text);
                }  
				et_out_filename.setText(et_text);
            }  
        });
		
		this.btn_run_resolution = (ImageButton)findViewById(R.id.btn_cam_size);
		this.btn_run_resolution.setOnClickListener(new View.OnClickListener(){
			@Override
			public void onClick(View v){
				SelectCamRes();
			}
		});
		
		this.btn_run = (Button)findViewById(R.id.btn_enc_run);
		this.btn_run.setOnClickListener(new View.OnClickListener(){
			@Override
			public void onClick(View v){
				GetParam();
				
				switch (m_lasttype) {
				case 0: // raw->h264
					if (m_bWriteSize) {
						if (-1 == m_out_filename.indexOf(".dump")) {
							Toast.makeText(TestEnc.this, "out put file name must be \".dump\"", 
								Toast.LENGTH_SHORT).show();
							return;
						}
					}
					
					String title 	= (String) getResources().getString(R.string.app_name);
					String message	= (String) getResources().getString(R.string.enc_prog_dlg_msg);
					
					//display ProgressDialog
					progDlg = new ProgressDialog(TestEnc.this);
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
							CodecLib.EasyEncoderTestAbort();
							m_stop_enc = 1;
							progDlg.dismiss();
					   }
					});
					progDlg.show(); 
					
					if(m_bUseJavaTest)
						Toast.makeText(TestEnc.this, "use java side encode", Toast.LENGTH_SHORT).show(); 
					else 
						Toast.makeText(TestEnc.this, "use c side encode", Toast.LENGTH_SHORT).show(); 
					
					new Thread(){  
	  
						@Override  
						public void run() {  
							// todo job
							int ret;
							
							// block
							if(m_bUseJavaTest) {
								Log.i(CodecLib.LOG_TAG, "Java TestEnc: use java side encode");
								ret = do_encode264_job(); 
							}
							else { 
								Log.i(CodecLib.LOG_TAG, "Java TestEnc: use c side encode");
								CodecLib.EasyEncoderTest(m_in_filename, m_out_filename, 
									m_width, m_height, m_profile, m_enc_str, m_frames, 
									m_bWritefile ? 1 : 0, m_bWriteSize ? 1 : 0);
							}
						}
					}.start();
					break;
				case 1: // raw->ts
					break;
				case 2: // cam->h264
					if (m_bWriteSize) {
						if (-1 == m_out_filename.indexOf(".dump")) {
							Toast.makeText(TestEnc.this, "out put file name must be \".dump\"", 
								Toast.LENGTH_SHORT).show();
							return;
						}
					}
					else {
						if (-1 == m_out_filename.indexOf(".h264")) {
							Toast.makeText(TestEnc.this, "out put file name must be \".h264\"", 
								Toast.LENGTH_SHORT).show();
							return;
						}
					}
					do_camera_job(m_lasttype); // gen h264 file
					break;
				case 3: // cam->ts
					if (-1 == m_out_filename.indexOf(".ts")) {
						Toast.makeText(TestEnc.this, "out put file name must be \".ts\"", 
							Toast.LENGTH_SHORT).show();
						return;
					}
					do_camera_job(m_lasttype); // gen ts file
					break;
				case 4: // cam->nv21
					if (-1 == m_out_filename.indexOf(".nv21")) {
						Toast.makeText(TestEnc.this, "out put file name must be \".nv21\"", 
								Toast.LENGTH_SHORT).show();
						return;
					}
					do_camera_job(m_lasttype); // gen nv21 picture file
					break;
				default:
					break;
				}
			}
		});
		
		this.btn_jump2dec = (Button)findViewById(R.id.btn_jump2dec);
		this.btn_jump2dec.setOnClickListener(new View.OnClickListener(){
			@Override
			public void onClick(View v){
				Intent intent = new Intent();
				intent.setClass(TestEnc.this, TestDec.class);
				startActivity(intent);
				finish();
			}
		});
		
		this.btn_mode = (Button)findViewById(R.id.btn_mode);
		this.btn_mode.setOnClickListener(new View.OnClickListener(){
			@Override
			public void onClick(View v){
				Log.i(CodecLib.LOG_TAG, "Java: TestEnc select mode");
				GetParam();
				
				final String[] OutputType = getResources().getStringArray(R.array.output_type);
				
				Dialog choose_outtype_dlg = new AlertDialog.Builder(TestEnc.this)
					.setTitle(getResources().getString(R.string.select_output_title))
					.setSingleChoiceItems(OutputType, m_lasttype, /*default selection item number*/
						new DialogInterface.OnClickListener(){
							public void onClick(DialogInterface dialog, int whichButton){
								Log.i(CodecLib.LOG_TAG, "java TestEnc: choose output type: " + 
									OutputType[whichButton]);
								btn_mode.setText(OutputType[whichButton]);
								String et_text;
								
								if (whichButton > 1) {
									et_in_filename.setEnabled(false);
																	
									switch (whichButton) {
									case 2: // cam->h264
										if(m_bWriteSize)
											et_text = getResources().getString(R.string.et_enc_out_dump_text);
										else
											et_text = getResources().getString(R.string.et_enc_out_file_text);
										break;
									case 3: // cam->ts
										et_text = getResources().getString(R.string.et_enc_out_ts_text);
										break;
									case 4: // cam->nv21
										et_text = getResources().getString(R.string.et_enc_in_file_text);
										break;					
									default:
										Log.e(CodecLib.LOG_TAG, "java TestEnc: wrong type input select");
										return;
									}
								}
								else {
									et_in_filename.setEnabled(true);
									
									switch (whichButton) {
									case 0: // pic->h264
										if(m_bWriteSize)
											et_text = getResources().getString(R.string.et_enc_out_dump_text);
										else
											et_text = getResources().getString(R.string.et_enc_out_file_text);
										break;
									case 1: // pic->ts
										et_text = getResources().getString(R.string.et_enc_out_ts_text);
										break;
									default:
										Log.e(CodecLib.LOG_TAG, "java TestEnc: wrong type input select");
										return;
									}
									
								}
								
								et_out_filename.setText(et_text);
								m_lasttype = whichButton;
								dialog.cancel();
							}
						})
					.setNegativeButton(getResources().getString(R.string.select_output_cancel), 
						new DialogInterface.OnClickListener(){
							public void onClick(DialogInterface dialog, int whichButton){
							}})
					.create();
				choose_outtype_dlg.show();	
			}
		});
		
		if(CodecLib.IsSupportNeon() > 0) {
			Toast.makeText(TestEnc.this, "load neon lib", 
				Toast.LENGTH_SHORT).show();
		}
		else {
			Toast.makeText(TestEnc.this, "load generic lib", 
				Toast.LENGTH_SHORT).show();
		}
		
		//getExternalMounts();
	}
	
	public void getExternalMounts() {
	
		String externalpath = new String();
		String internalpath = new String();

		Runtime runtime = Runtime.getRuntime();
		try
		{
			Process proc = runtime.exec("mount");
			InputStream is = proc.getInputStream();
			InputStreamReader isr = new InputStreamReader(is);
			String line;

			BufferedReader br = new BufferedReader(isr);
			while ((line = br.readLine()) != null) {
				Log.i(CodecLib.LOG_TAG, line);
				if (line.contains("secure")) continue;
				if (line.contains("asec")) continue;

				if (line.contains("fat")) {//external card
					String columns[] = line.split(" ");
					if (columns != null && columns.length > 1) {
						externalpath = externalpath.concat("*" + columns[1] + "\n");
					}
				} 
				else if (line.contains("fuse")) {//internal storage
					String columns[] = line.split(" ");
					if (columns != null && columns.length > 1) {
						internalpath = internalpath.concat(columns[1] + "\n");
					}
				}
			}
		}
		catch(Exception e)
		{
			e.printStackTrace();
		}
		
		Log.i(CodecLib.LOG_TAG, "Path  of sd card external............" + externalpath);
		Log.i(CodecLib.LOG_TAG, "Path  of internal memory............" + internalpath);
	}
	
	void do_camera_job(int type) { // type: 2 cam->h264, 3 cam->ts, 4 cam->nv21
		if (type < 2 || type > 4) {
			Toast.makeText(TestEnc.this, "not supported camera run mode: " + type, 
				Toast.LENGTH_SHORT).show();
			return;
		}
		
		int write_file;
		if(m_bWritefile)
			write_file = 1;
		else
			write_file = 0;
			
		int write_size;
		if(m_bWriteSize)
			write_size = 1;
		else
			write_size = 0;
			
		Intent intent = new Intent();
		intent.putExtra("width", m_width);
		intent.putExtra("height", m_height);
		intent.putExtra("in_fmt", 2); // hard code NV21
		intent.putExtra("profile", m_profile);
		intent.putExtra("settings", m_enc_str);
		intent.putExtra("frames", m_frames);
		intent.putExtra("out_filename", m_out_filename);
		intent.putExtra("writefile", write_file);
		intent.putExtra("writesize", write_size);
		intent.putExtra("type", type); // 0-save pic, 1-save h264
		intent.setClass(TestEnc.this, CameraPreview.class);
		TestEnc.this.startActivityForResult(intent, 1);
	}	
	
	@Override
	protected void onActivityResult(int requestCode, int resultCode, Intent intent) {  
        super.onActivityResult(requestCode, resultCode, intent);
		Log.i(CodecLib.LOG_TAG, String.format("Java: TestEnc onActivityResult requestCode %d, resultcode %d",
			requestCode, resultCode)); 
	}

	public Handler handler = new Handler(){  
  
        @Override  
        public void handleMessage(Message msg) {  
            switch(msg.what) {
			case CodecLib.MSG_REFRESH:
				progDlg.setMessage(str_enc_info);
				break;
			case CodecLib.MSG_FINISH: 
			case CodecLib.MSG_ERROR:
				progDlg.dismiss();
				tv_enc_info.setText(str_enc_info);
				break;
			default:
				Log.w(CodecLib.LOG_TAG, "unknown msg.what " + msg.what);
				break;
			}			 
        }
	}; 
	
	void SelectCamRes() {
		int defaultCameraId = 0;
		int cameraCurrentlyLocked;
		int numberOfCameras;
		
		int currentVersion = VERSION.SDK_INT;
		if (currentVersion >= VERSION_CODES.GINGERBREAD) {
			numberOfCameras = Camera.getNumberOfCameras();
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
		
		Dialog choose_cam_res_dlg = new AlertDialog.Builder(TestEnc.this)
			.setTitle(getResources().getString(R.string.select_cam_resolution))
			./*setItems*/setSingleChoiceItems(str_resolution, -1, /*default selection item number*/
				new DialogInterface.OnClickListener(){
				public void onClick(DialogInterface dialog, int whichButton){
					et_width.setText(String.valueOf(list_preview_size.get(whichButton).width));
					et_height.setText(String.valueOf(list_preview_size.get(whichButton).height));
					dialog.cancel();
					Log.i(CodecLib.LOG_TAG, String.format("Java TestEnc: choose %d %s", 
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
	
	int do_encode264_job()
	{
		int in_frames, out_frames;
		int handle;
		
		byte[] yuv = new byte[m_pic_size];
		byte[] h264= new byte[1048576];
		ByteBuffer yuv_direct = ByteBuffer.allocateDirect(m_pic_size);
		ByteBuffer h264_direct = ByteBuffer.allocateDirect(1048576);
		
		Long opaque_in = 1L;
		Long opaque_out = 0L;
		byte[] opa_in;
		byte[] opa_out = new byte[16];
		int frame_size;
		int ret;
		int frame_type;
		char cFrame;		
		String info_msg;
		
		FileInputStream fin = null;
		//RandomAccessFile fin = null;
		FileOutputStream fout = null;
		File sdCardDir = Environment.getExternalStorageDirectory();
		
		handle = CodecLib.EasyEncoderOpen(m_width, m_height, 
			m_in_fmt, m_profile, m_enc_str);
		if(CodecLib.INVALID_HANDLE == handle) {
			Log.e(CodecLib.LOG_TAG, "Java: TestDec failed to open encoder: ");
			str_enc_info = "failed to open encoder";
			Message msg = new Message();  
			msg.what = CodecLib.MSG_ERROR;  
			handler.sendMessage(msg); 
			return -1;
		}
		
		Log.i(CodecLib.LOG_TAG, "Java: TestEnc start to encode...");
		
		try {
			if(-1 == m_in_filename.indexOf('/')) {
				Log.i(CodecLib.LOG_TAG, "Java: TestEnc input file is in data: " + m_in_filename);
				fin = openFileInput(m_in_filename);
				//fin = new RandomAccessFile(m_in_filename, "r");  
			}
			else {
				info_msg = "Java: TestEnc input file is in sdcard: " + m_in_filename;
				Log.d(CodecLib.LOG_TAG, info_msg);
				File sdInFile = new File(sdCardDir, m_in_filename);
				fin = new FileInputStream(sdInFile);
			}
		
			int length = fin.available();
			if(length < 1024) {
				Log.e(CodecLib.LOG_TAG, "Java: TestEnc input file is too small");
				fin.close();
				CodecLib.EasyEncoderClose(handle);
				str_enc_info = "input file is too small";
				Message msg = new Message();  
				msg.what = CodecLib.MSG_ERROR;  
				handler.sendMessage(msg); 
				return -1;
			}
			
			if(-1 == m_out_filename.indexOf('/')) {
				Log.i(CodecLib.LOG_TAG, "Java: TestEnc output file is in package: " + m_in_filename);
				fout = openFileOutput(m_out_filename, MODE_PRIVATE);
			}
			else {
				Log.d(CodecLib.LOG_TAG, "Java: TestEnc output file is in sdcard: " + m_out_filename);
				File sdFile = new File(sdCardDir, m_out_filename);
				fout = new FileOutputStream(sdFile);
			}
			
			Log.i(CodecLib.LOG_TAG, "Java: TestEnc run for " + Integer.toString(m_frames));
			
			in_frames = out_frames = 0;
			m_stop_enc = 0;
			
			int i;
			long start_msec, elapsed_msec;
			long total_msec, max_msec;
			total_msec = max_msec = 0;
			for (i=0 ; i < m_frames ; i++) {
				if (m_stop_enc > 0) {
					Log.w(CodecLib.LOG_TAG, "Java: TestEnc aborted");
					break;
				}
				
				start_msec = System.currentTimeMillis();
				ret = fin.read(yuv, 0, m_pic_size);
				if(ret != m_pic_size) {
					Log.i(CodecLib.LOG_TAG, "Java: TestEnc in_file eof");
					fin.close();
					File sdInFile = new File(sdCardDir, m_in_filename);
					fin = new FileInputStream(sdInFile);
					ret = fin.read(yuv, 0, m_pic_size);
					if(ret != m_pic_size) {
						Log.i(CodecLib.LOG_TAG, "Java: TestEnc in_file failed to re-open");
						break;
					}
				}
				elapsed_msec = System.currentTimeMillis() - start_msec;
				if(elapsed_msec > max_msec)
					max_msec = elapsed_msec;
				total_msec += elapsed_msec;
				Log.d(CodecLib.LOG_TAG, String.format("Java: TestEnc read %d msec", elapsed_msec));
				
				if (CodecLib.USE_NATIVEIO) {
					yuv_direct.position(0);
					yuv_direct.put(yuv, 0, m_pic_size);
				}
				
				opa_in = CodecLib.LongToOpaque(opaque_in);
				Log.d(CodecLib.LOG_TAG, "Java: TestEnc Add #" + Integer.toString(i));
				if (CodecLib.USE_NATIVEIO) {
					ret = CodecLib.EasyEncoderAdd(handle, yuv_direct, m_pic_size, opa_in);
				}
				else {
					ret = CodecLib.EasyEncoderAdd(handle, yuv, m_pic_size, opa_in);
				}
				if(ret < 0) {
					Log.e(CodecLib.LOG_TAG, "Java: TestEnc failed to Add in #" + i);
					str_enc_info = "failed to add in #" + i;
					Message msg = new Message();  
					msg.what = CodecLib.MSG_ERROR;  
					handler.sendMessage(msg); 
					break;
				}
				
				opaque_in++;
				in_frames++;
				
				if (CodecLib.USE_NATIVEIO) {
					ret = CodecLib.EasyEncoderGet(handle, h264_direct, opa_out);
				}
				else {
					ret = CodecLib.EasyEncoderGet(handle, h264, opa_out);
				}
				if(ret < 0) {
					info_msg = "Java: TestEnc failed to Get in #" + Integer.toString(i);
					Log.e(CodecLib.LOG_TAG, info_msg);
					str_enc_info = "failed to get in #" + i;
					Message msg = new Message();  
					msg.what = CodecLib.MSG_ERROR;  
					handler.sendMessage(msg); 
					break;
				}
										
				if (ret > 0) {
					frame_size = ret;
					if(m_bWritefile) {
						if (CodecLib.USE_NATIVEIO) {
							h264_direct.position(0);
							h264_direct.get(h264, 0, frame_size);
						}
						
						Log.d(CodecLib.LOG_TAG, "Java: TestEnc write file in #" + i);
						if(m_bWriteSize) {
							byte []byte_size = CodecLib.IntToByte(frame_size);
							fout.write(byte_size);
						}
						fout.write(h264, 0, frame_size);
					}
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
					info_msg = String.format("Java: TestEnc opaque: %d(%c)", 
						opaque_out, cFrame);
					Log.d(CodecLib.LOG_TAG, info_msg/*Long.toString()*/);
					progDlg.setProgress(i);
				}
				
				//calc instant fps
				if(i % 5 == 0) {
					double fps = CodecLib.EasyEncoderGetFPS(handle);
					String message	= (String) getResources().getString(R.string.enc_prog_dlg_msg);
					str_enc_info = message + String.format(" (fps: %.2f)", fps);
					Message msg = new Message();  
					msg.what = CodecLib.MSG_REFRESH;  
					handler.sendMessage(msg); 
				}
			}
			
			
			Log.i(CodecLib.LOG_TAG, String.format("Java: TestEnc read file avg/max %.2f/%d msec", 
				(double)total_msec / (double)m_frames, max_msec));
			
			File sdOutputFile = new File(sdCardDir, m_out_filename);
			FileInputStream outfile = new FileInputStream(sdOutputFile);
			double fps = CodecLib.EasyEncoderGetFPS(handle);
			if(fps > 0.1f) {
				double bitrate;
				bitrate = (double)(outfile.available() * 8 * CodecLib.CLIP_FPS) / (double)(out_frames * 1000);
				str_enc_info = String.format("i/o: %d/%d(all %d) frm, fps: %.2f, %.0f kbps", 
					in_frames, out_frames, m_frames, 
					fps, bitrate);
				Message msg = new Message();  
				msg.what = CodecLib.MSG_FINISH;  
				handler.sendMessage(msg); 
			}
			
			outfile.close();
			fin.close();
			fout.close();
			
		}
		catch (FileNotFoundException e) {
			Log.e(CodecLib.LOG_TAG, "file not found");
			str_enc_info = "file not found";
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
		
		CodecLib.EasyEncoderClose(handle);
		Log.i(CodecLib.LOG_TAG, "Java: TestEnc Encode all done");
		return 0;
	}
	
	private void builderCheckBoxDialog() {  
						
        Builder builder = new AlertDialog.Builder(this);  
        builder.setTitle("编码器设置");  
        final String[] titles = new String[] { "写文件", "写大小", "C方式" };
        builder.setMultiChoiceItems(titles,  
                new boolean[] { m_bWritefile, m_bWriteSize, m_bUseJavaTest },  
                new DialogInterface.OnMultiChoiceClickListener() {  
  
                    @Override  
                    public void onClick(DialogInterface dialog, int which,  
                            boolean isChecked) {  
                        // TODO Auto-generated method stub  
						boolean bWritefile;
						boolean bWriteSize;
						boolean bUseJavaTest;
                        //当checkbox选中时响应  
                        if (isChecked) {  
                            Toast.makeText(TestEnc.this,  
                                    titles[which] + "被选中", Toast.LENGTH_SHORT)  
                                    .show();  
                        }  
  
                        //当checkbox未选中时响应  
                        if (!isChecked) {  
                            Toast.makeText(TestEnc.this,  
                                    titles[which] + "被取消", Toast.LENGTH_SHORT)  
                                    .show();								
                        }  
                    }  
                });  
  
        //确定按钮事件响应  
        builder.setPositiveButton("确定", new DialogInterface.OnClickListener() {  
  
            @Override  
            public void onClick(DialogInterface dialog, int which) {  
                // TODO Auto-generated method stub  
                Toast.makeText(TestEnc.this, "确定", Toast.LENGTH_SHORT)  
                        .show();  
            }  
        });  
  
        //取消按钮 事件响应  
        builder.setNegativeButton("取消", new DialogInterface.OnClickListener() {  
  
            @Override  
            public void onClick(DialogInterface dialog, int which) {  
                // TODO Auto-generated method stub  
                Toast.makeText(TestEnc.this, "取消", Toast.LENGTH_SHORT)  
                        .show();  
            }  
        });  
  
        builder.create().show();  
    }  
	
	@Override
	public boolean onCreateOptionsMenu(Menu menu) {
		// Inflate the menu; this adds items to the action bar if it is present. 
		
		getMenuInflater().inflate(R.menu.activity_test_enc, menu);
		return true;
	}
	
	@Override  
    public boolean onOptionsItemSelected(MenuItem item) {  
        // TODO Auto-generated method stub  
  
        switch (item.getItemId()) {  
        case R.id.menu_settings:
			builderCheckBoxDialog();
            return true;  
        case R.id.menu_usejava_test:  
			m_bUseJavaTest = !m_bUseJavaTest;
			item.setChecked(m_bUseJavaTest);
            return true;  
        case R.id.menu_write_file:  
            Toast.makeText(this, "write file", Toast.LENGTH_SHORT).show(); 
            return true;  
		case R.id.menu_write_size:  
            Toast.makeText(this, "write frame size", Toast.LENGTH_SHORT).show();  
			return true;
        default:  
            return super.onOptionsItemSelected(item);  
        }  
    } 
	
	@Override  
    public boolean onContextItemSelected(MenuItem item) {  
        // TODO Auto-generated method stub  
		switch (item.getItemId()) {  
        case R.id.menu_settings:
            builderCheckBoxDialog();  
            return true;  
        default:  
			if (!item.isChecked()) {  
				item.setChecked(true);  
				Toast.makeText(this, "you choose is: " + item.getTitle(),  
						Toast.LENGTH_SHORT).show();  
			}  
			return super.onContextItemSelected(item);  
        }  
	}
	
    @Override  
    public void onContextMenuClosed(Menu menu) {  
        // TODO Auto-generated method stub  
        Toast.makeText(this, "contextMenu was closed", Toast.LENGTH_SHORT).show();  
        super.onContextMenuClosed(menu);  
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
		tmp = et_in_fmt.getText().toString();
		m_in_fmt = Integer.parseInt(tmp);
		
		switch(m_in_fmt) {
		case 2://nv21
		case 3://yuv420p
			m_pic_size = m_width * m_height + m_width * m_height / 2;
			break;
		default:
			m_pic_size = m_width * m_height * 3;
			Log.e(CodecLib.LOG_TAG, "Java: TestEnc Encode fmt not supported");
			break;
		}
		//output info
		m_profile = et_preset.getText().toString();
		m_enc_str = et_enc_settings.getText().toString();
		
		if(cb_write.isChecked())
			m_bWritefile = true;
		else
			m_bWritefile = false;
			
		if(cb_write_size.isChecked())
			m_bWriteSize = true;
		else
			m_bWriteSize = false;
		
		tmp = et_frames.getText().toString();
		m_frames = Integer.parseInt(tmp);
	}

}
