package tv.xormedia.AndCodec;

import android.app.Activity;
import android.os.Bundle;
import android.widget.TextView;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.EditText;
import android.view.View;
import android.view.WindowManager;
import android.content.Intent;
import android.content.DialogInterface;

import android.os.Environment;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import	java.io.FileOutputStream;
import	java.io.FileInputStream;

import android.content.pm.ActivityInfo;
import android.util.Log;

import android.view.SurfaceHolder;
import android.view.SurfaceView;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.LinearGradient;
import android.content.Context;
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

import android.util.DisplayMetrics; // for display width and height

public class Manual extends Activity implements SurfaceHolder.Callback {
	private static final int MSG_DEC_REFRESH = 1;
	
	private EditText et_filename= null;
	private String m_in_filename;
	
	private EditText et_width	= null;
	private EditText et_height	= null;
	private EditText et_out_fmt	= null;
	private int m_width			= 320;
	private int m_height		= 240;
	private int m_out_fmt;
	
	private Button btn_open_enc	= null;
	private Button btn_add_enc	= null;
	private Button btn_get_enc	= null;
	
	private Button btn_run_dec	= null;
	private Button btn_open_dec	= null;
	private Button btn_add_dec	= null;
	private Button btn_get_dec	= null;
	
	private TextView tv_info	= null;
	private String str_info;
	
	private SurfaceView sv_disp	= null;
	private SurfaceHolder holder= null;
	private Canvas canvas		= null;
	private Bitmap bmp 			= null;
	
	private Button btn_2enc		= null;
	private ImageButton btn_res	= null;
	private ImageButton btn_disp= null;
	
	private int handle			= CodecLib.INVALID_HANDLE;
	private int in_frames		= 0;
	private int out_frames		= 0;
	FileInputStream fin 		= null;
	private byte[] decdata		= null;
	private byte[] pic_data		= null;
	private ByteBuffer decdata_direct 	= null;
	private ByteBuffer pic_data_direct	= null;
	private Long opaque_in 		= 0L;
	
	private int screen_width;
	private boolean bFullDisp	= false;
	private RectF disp_dst		= null;
	
	private boolean running		= false;
	
	private File sdCardDir		= null;
	
    /** Called when the activity is first created. */
    @Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		setContentView(R.layout.activity_test_manual);
		
		super.getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
		
		DisplayMetrics dm = new DisplayMetrics(); 
		getWindowManager().getDefaultDisplay().getMetrics(dm); 
		screen_width = dm.widthPixels;
		
		disp_dst = new RectF();
		
		sdCardDir = Environment.getExternalStorageDirectory();
		
		this.et_width	= (EditText)findViewById(R.id.edit_man_width);
		this.et_height	= (EditText)findViewById(R.id.edit_man_height);
		this.et_out_fmt	= (EditText)findViewById(R.id.edit_man_out_fmt);
		
		/*decdata = new byte[1048576];
		pic_data= new byte[1280 * 720 * 2];
		decdata_direct = ByteBuffer.allocateDirect(1048576);
		pic_data_direct = ByteBuffer.allocateDirect(1280 * 720 * 2);*/
		
		//this.bmp = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.RGB_565);
		
		this.et_filename = (EditText)findViewById(R.id.et_man_filename);
		
		this.tv_info = (TextView)findViewById(R.id.tv_man_info);
		
		this.btn_res = (ImageButton)findViewById(R.id.btn_man_res);
		this.btn_res.setOnClickListener(new View.OnClickListener(){
			@Override
			public void onClick(View v){
				SelectCamRes();
			}
		});
		
		this.btn_disp = (ImageButton)findViewById(R.id.btn_man_disp);
		this.btn_disp.setOnClickListener(new View.OnClickListener(){
			@Override
			public void onClick(View v){
				if(!bFullDisp) {
					parseCfg();
					
					btn_disp.setImageResource(R.drawable.ic_fulldisp);
					disp_dst.left	= 0;
					disp_dst.top	= 0;
					disp_dst.right	= screen_width;
					disp_dst.bottom	= disp_dst.top + screen_width * m_height / m_width;
				}
				else {
					btn_disp.setImageResource(R.drawable.ic_origindisp);
				}
				bFullDisp = !bFullDisp;
			}
		});
		
		this.btn_run_dec = (Button)findViewById(R.id.btn_man_run_dec);
		this.btn_run_dec.setOnClickListener(new View.OnClickListener(){
			@Override
			public void onClick(View v){
				if(!running) {
					MyThread mt1 = new MyThread();
					new Thread(mt1).start();
					btn_run_dec.setText(getResources().getString(R.string.btn_man_run_dec_stop));
				}
				else {
					btn_run_dec.setText(getResources().getString(R.string.btn_man_run_dec));
				}
				running = !running;
			}
		});
		
		this.btn_open_dec = (Button)findViewById(R.id.btn_man_open_dec);
		this.btn_open_dec.setOnClickListener(new View.OnClickListener(){
			@Override
			public void onClick(View v){
				if(handle == CodecLib.INVALID_HANDLE) {	
					parseCfg();
					
					try{
						File sdInFile = new File(sdCardDir, m_in_filename);
						fin = new FileInputStream(sdInFile);
					}
					catch (FileNotFoundException e) {
						Log.e(CodecLib.LOG_TAG, "file not found");
						return;
					}
					catch(Exception e){   
						e.printStackTrace();
						Log.e(CodecLib.LOG_TAG, "an error occured while opening...", e);
						return;
					}	
					
					handle = CodecLib.EasyDecoderOpen(m_width, m_height, m_out_fmt);
					if(handle == CodecLib.INVALID_HANDLE) {
						Log.e(CodecLib.LOG_TAG, "failed to open decoder");
					}
					in_frames = out_frames = 0;
					opaque_in = 1L;
					bmp = Bitmap.createBitmap(m_width, m_height, Bitmap.Config.RGB_565);
					
					decdata = new byte[1048576];
					pic_data= new byte[1280 * 720 * 2];
					decdata_direct = ByteBuffer.allocateDirect(1048576);
					pic_data_direct = ByteBuffer.allocateDirect(1280 * 720 * 2);
					
					btn_open_dec.setText(getResources().getString(R.string.btn_man_close));
				}
				else {
					try {
						if (fin != null)
							fin.close();
					}
					catch(Exception e){   
						e.printStackTrace();
						Log.e(CodecLib.LOG_TAG, "an error occured while opening...", e);
					}
					
					CodecLib.EasyDecoderClose(handle);
					handle = CodecLib.INVALID_HANDLE;
					btn_open_dec.setText(getResources().getString(R.string.btn_man_open));
				}
			}
		});
		
		this.btn_add_dec = (Button)findViewById(R.id.btn_man_add_dec);
		this.btn_add_dec.setOnClickListener(new View.OnClickListener(){
			@Override
			public void onClick(View v){
				if(handle == CodecLib.INVALID_HANDLE) {
					Log.e(CodecLib.LOG_TAG, "decoder was not opened");
					return;
				}
				
				int ret;
				byte[] size_buf = new byte[4];
				byte[] opa_in;
				int decdata_size = 0;
				
				try {
					ret = fin.read(size_buf, 0, 4);
					if(ret != 4) {
						Log.i(CodecLib.LOG_TAG, "Java: Manual in_file eof");
						fin.close();
						File sdInFile = new File(sdCardDir, m_in_filename);
						fin = new FileInputStream(sdInFile);
						ret = fin.read(size_buf, 0, 4);
						if(ret != 4) {
							Log.i(CodecLib.LOG_TAG, "Java: Manual in_file failed to reset");
							return;
						}
					}
					
					decdata_size = CodecLib.ByteToInt(size_buf);
					if(decdata_size < 0 || decdata_size > 1048576) {
						Log.e(CodecLib.LOG_TAG, "Java: Manual h264 data is corrupt: " + decdata_size);
						return;
					}
					
					ret = fin.read(decdata, 0, decdata_size);
					if(ret != decdata_size) {
						Log.i(CodecLib.LOG_TAG, "Java: Manual infile eof(data)");
						return;
					}
					
					if (CodecLib.USE_NATIVEIO) {
						decdata_direct.position(0);
						decdata_direct.put(decdata, 0, ret);
					}
				}
				catch(Exception e){   
					e.printStackTrace();
					Log.e(CodecLib.LOG_TAG, "an error occured while decoding...", e);
				}
						
				opa_in = CodecLib.LongToOpaque(opaque_in);
				
				if (CodecLib.USE_NATIVEIO) {
					ret = CodecLib.EasyDecoderAdd(handle, decdata_direct, decdata_size, opa_in);
				}
				else {
					ret = CodecLib.EasyDecoderAdd(handle, decdata, decdata_size, opa_in);
				}
				if(ret < 0) {
					Log.e(CodecLib.LOG_TAG, "Java: Manual failed to Add");
					return;
				}
				
				tv_info.setText(String.format("in %d, Add result: %d", in_frames, ret));
				Log.i(CodecLib.LOG_TAG, String.format("Java: in %d, Add result: %d", in_frames, ret));
				in_frames++;
				opaque_in++;
			}
		});
		
		this.btn_get_dec = (Button)findViewById(R.id.btn_man_get_dec);
		this.btn_get_dec.setOnClickListener(new View.OnClickListener(){
			@Override
			public void onClick(View v){
				int ret;
				byte[] opa_out = new byte[16];
				Long opaque_out = 0L;
				int frame_size;
				
				if (CodecLib.USE_NATIVEIO) {
					ret = CodecLib.EasyDecoderGet(handle, pic_data_direct, opa_out);	
				}
				else {
					ret = CodecLib.EasyDecoderGet(handle, pic_data, opa_out);
				}
				
				Log.i(CodecLib.LOG_TAG, "Java: Get result: " + ret);
				tv_info.setText("Get result: " + ret);
				
				if (ret > 0) {
					out_frames++;
					tv_info.setText("out " + out_frames);
					frame_size = ret;
					opaque_out = CodecLib.ByteToLong(opa_out);
					
					if (CodecLib.USE_NATIVEIO) {
						pic_data_direct.position(0);
						pic_data_direct.get(pic_data, 0, frame_size);
					}
					
					//render
					canvas = holder.lockCanvas();//获得画布
					canvas.drawColor(Color.WHITE);//设置画布背景为白色
					ByteBuffer buffer = ByteBuffer.wrap(pic_data);
					bmp.copyPixelsFromBuffer(buffer);
					
					if (bFullDisp)
						canvas.drawBitmap(bmp, null, disp_dst, null);
					else
						canvas.drawBitmap(bmp, 0, 0, null);
						
					canvas.save();
					canvas.restore();
					holder.unlockCanvasAndPost(canvas);//完成绘图后解锁递交画布视图
					
					Log.i(CodecLib.LOG_TAG, "Java: TestDec opaque: " + 
						Long.toString(opaque_out));
				}
			}
		});
		
		this.btn_2enc = (Button)findViewById(R.id.btn_jump2enc);
		this.btn_2enc.setOnClickListener(new View.OnClickListener(){
			@Override
			public void onClick(View v){
				Intent intent = new Intent();
				intent.setClass(Manual.this, TestEnc.class);
				startActivity(intent);
				finish();
			}
		});
		
		this.sv_disp = (SurfaceView)findViewById(R.id.sv_disp);
		this.holder = this.sv_disp.getHolder();
		this.holder.addCallback(this);
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
		
		Dialog choose_cam_res_dlg = new AlertDialog.Builder(Manual.this)
			.setTitle(getResources().getString(R.string.select_cam_resolution))
			./*setItems*/setSingleChoiceItems(str_resolution, -1, /*default selection item number*/
				new DialogInterface.OnClickListener(){
				public void onClick(DialogInterface dialog, int whichButton){
					et_width.setText(String.valueOf(list_preview_size.get(whichButton).width));
					et_height.setText(String.valueOf(list_preview_size.get(whichButton).height));
					dialog.cancel();
					Log.i(CodecLib.LOG_TAG, String.format("Java Manual: choose %d %s", 
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
	
	private Handler handler = new Handler(){  
  
        @Override  
        public void handleMessage(Message msg) {  
            switch(msg.what) {
			case MSG_DEC_REFRESH:
				tv_info.setText(str_info);
				break;
			default:
				Log.w(CodecLib.LOG_TAG, "unknown msg.what " + msg.what);
				break;
			}			 
        }
	}; 
	
	void parseCfg() {
		String tmp;
		tmp	= et_width.getText().toString();
		m_width = Integer.parseInt(tmp);
		tmp = et_height.getText().toString();
		m_height = Integer.parseInt(tmp);
		tmp = et_out_fmt.getText().toString();
		m_out_fmt = Integer.parseInt(tmp);
		m_in_filename = et_filename.getText().toString();
	}
	
	class MyThread implements Runnable {
		public void run() {
			parseCfg();
			
			if ( handle != CodecLib.INVALID_HANDLE) {
				CodecLib.EasyDecoderClose(handle);
			}
			
			handle = CodecLib.EasyDecoderOpen(m_width, m_height, m_out_fmt);// output-rgb565le
			if(handle == CodecLib.INVALID_HANDLE) {
				Log.e(CodecLib.LOG_TAG, "failed to open decoder");
			}
			
			FileInputStream fin_dec;
			try{
				File sdInFile = new File(sdCardDir, m_in_filename);
				fin_dec = new FileInputStream(sdInFile);
			}
			catch (FileNotFoundException e) {
				Log.e(CodecLib.LOG_TAG, "file not found");
				return;
			}
			catch(Exception e){   
				e.printStackTrace();
				Log.e(CodecLib.LOG_TAG, "an error occured while opening...", e);
				return;
			}	
			
			Log.i(CodecLib.LOG_TAG, "Java manual: decoder thread starting...");
			
			Bitmap dec_bmp = Bitmap.createBitmap(m_width, m_height, Bitmap.Config.RGB_565);
			
			int ret;
			byte[] size_buf = new byte[4];
			Long opaque_out = 0L;
			byte[] opa_in;
			int decdata_size = 0;
			byte[] opa_out = new byte[16];
			int frame_size;
			
			decdata = new byte[1048576];
			pic_data= new byte[1280 * 720 * 2];
			decdata_direct = ByteBuffer.allocateDirect(1048576);
			pic_data_direct = ByteBuffer.allocateDirect(1280 * 720 * 2);
			
			int frameNo = 0;
			while (running) {
				try {
					ret = fin_dec.read(size_buf, 0, 4);
					if(ret != 4) {
						Log.i(CodecLib.LOG_TAG, "Java: Manual in_file eof");
						fin_dec.close();
						File sdInFile = new File(sdCardDir, m_in_filename);
						fin_dec = new FileInputStream(sdInFile);
						frameNo = 0;
						ret = fin_dec.read(size_buf, 0, 4);
						if(ret != 4) {
							Log.i(CodecLib.LOG_TAG, "Java: Manual in_file failed to reset");
							break;
						}
					}
					
					decdata_size = CodecLib.ByteToInt(size_buf);
					if(decdata_size < 0 || decdata_size > 1048576) {
						Log.e(CodecLib.LOG_TAG, "Java: Manual h264 data is corrupt: " + decdata_size);
						return;
					}
					
					ret = fin_dec.read(decdata, 0, decdata_size);
					if(ret != decdata_size) {
						Log.i(CodecLib.LOG_TAG, "Java: Manual infile eof(data)");
						return;
					}
					if (CodecLib.USE_NATIVEIO) {
						decdata_direct.position(0);
						decdata_direct.put(decdata, 0, ret);
					}
				}
				catch(Exception e){   
					e.printStackTrace();
					Log.e(CodecLib.LOG_TAG, "an error occured while decoding...", e);
				}
				
				opa_in = CodecLib.LongToOpaque(opaque_in);
				if (CodecLib.USE_NATIVEIO) {
					ret = CodecLib.EasyDecoderAdd(handle, decdata_direct, decdata_size, opa_in);
				}
				else {
					ret = CodecLib.EasyDecoderAdd(handle, decdata, decdata_size, opa_in);
				}
				if(ret < 0) {
					Log.e(CodecLib.LOG_TAG, "Java: Manual failed to Add");
					break;
				}
				
				Log.i(CodecLib.LOG_TAG, "Java: Add result: " + ret);
				opaque_in++;
				
				// get
				if (CodecLib.USE_NATIVEIO) {
					ret = CodecLib.EasyDecoderGet(handle, pic_data_direct, opa_out);
				}
				else {
					ret = CodecLib.EasyDecoderGet(handle, pic_data, opa_out);
				}
				if(ret < 0) {
					Log.e(CodecLib.LOG_TAG, "Java: TestDec failed to Get");
					break;
				}
				
				Log.i(CodecLib.LOG_TAG, "Java: Get result: " + ret);
				if (ret > 0) {
					frameNo++;
					frame_size = ret;
					opaque_out = CodecLib.ByteToLong(opa_out);
					
					if (CodecLib.USE_NATIVEIO) {
						pic_data_direct.position(0);
						pic_data_direct.get(pic_data, 0, frame_size);
					}
					
					// render
					canvas = holder.lockCanvas();//获得画布
					canvas.drawColor(Color.WHITE);//设置画布背景为白色
					ByteBuffer buffer = ByteBuffer.wrap(pic_data);
					dec_bmp.copyPixelsFromBuffer(buffer);
					if (bFullDisp)
						canvas.drawBitmap(dec_bmp, null, disp_dst, null);
					else
						canvas.drawBitmap(dec_bmp, 0, 0, null);
						
					canvas.save();
					canvas.restore();
					holder.unlockCanvasAndPost(canvas);//完成绘图后解锁递交画布视图
					
					// message
					str_info = String.format("frame #%d", frameNo);
					Message msg = new Message();  
					msg.what = MSG_DEC_REFRESH;  
					handler.sendMessage(msg);
					
					Log.i(CodecLib.LOG_TAG, "Java: TestDec opaque: " + 
						Long.toString(opaque_out));
				}
				
				try {  
					Thread.sleep(100);  
				} catch (InterruptedException e) {  
					e.printStackTrace();  
				}  
			}
			
			CodecLib.EasyDecoderClose(handle);
			handle = CodecLib.INVALID_HANDLE;
			
			try{
				fin_dec.close();
			}
			catch (FileNotFoundException e) {
				Log.e(CodecLib.LOG_TAG, "file not found");
			}
			catch(Exception e){   
				e.printStackTrace();
				Log.e(CodecLib.LOG_TAG, "an error occured while closing...", e);
			}
			
			Log.i(CodecLib.LOG_TAG, "Java manual: decoder thread exited");
		}
	}
	
	@Override
	public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
		Log.i(CodecLib.LOG_TAG, String.format("surfaceChanged %d %dx%d", format, width, height));
		
		canvas = holder.lockCanvas();//获得画布
		
		canvas.drawColor(Color.WHITE);//设置画布背景为白色
		/*Paint mPaint = new Paint();
		
		//画矩形
		mPaint.setColor(Color.YELLOW);
		canvas.drawRect(100, 200, 200, 300, mPaint);
		canvas.save();//每画完一个图做备忘存储,以保证与其它图形互不影响
		//画旋转
		canvas.rotate(45);//旋转角度45度
		mPaint.setColor(Color.GREEN);
		canvas.drawRect(150, 10, 200, 60, mPaint);//画矩形
		canvas.save();
		 //画渐变色圆形
		Shader mShader=new LinearGradient(0,0,100,100, 
			new int[]{Color.RED,Color.GREEN,Color.BLUE,Color.YELLOW},
			null,Shader.TileMode.REPEAT);//使用着色器
		mPaint.setShader(mShader);
		canvas.drawCircle(500, 150, 80, mPaint);
		
		canvas.save();
		//画三角形
		mPaint.setStyle(Paint.Style.STROKE);//设置空心
		mPaint.setStrokeWidth(3);//设置p外框宽度
		Path path=new Path();
		path.moveTo(10,330);
		path.lineTo(70,330);
		path.lineTo(40,270);
		path.close();
		canvas.drawPath(path,mPaint);
		canvas.save();
		// 画梯形
		mPaint.setAntiAlias(true);//去掉边缘锯齿
		mPaint.setColor(Color.BLUE);
		mPaint.setStyle(Paint.Style.FILL);//设置实心
		Path path1=new Path();
		path1.moveTo(10,410);
		path1.lineTo(70,410);
		path1.lineTo(55,350);
		path1.lineTo(25,350);
		path1.close();
		canvas.drawPath(path1,mPaint);
		canvas.save();
		//画椭圆形
		mPaint.setAntiAlias(true);//去掉边缘锯齿
		mPaint.setColor(Color.RED);
		RectF re=new RectF(10,220,70,250);
		canvas.drawOval(re,mPaint);
		canvas.save();*/
		
		canvas.save();
		canvas.restore();
		holder.unlockCanvasAndPost(canvas);//完成绘图后解锁递交画布视图
		
	}
	
	@Override
	public void surfaceCreated(SurfaceHolder holder) {
		
	}
	
	@Override
	public void surfaceDestroyed(SurfaceHolder holder) {
		// TODO Auto-generated method stub
	}
	
	@Override
    protected void onResume() {
        super.onResume();
		
		if(getRequestedOrientation()!=ActivityInfo.SCREEN_ORIENTATION_PORTRAIT/*SCREEN_ORIENTATION_LANDSCAPE*/){
			setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
		}
    }
	
	@Override
	   protected void onPause() {
        super.onPause();
		running = false;
    }
	
}

