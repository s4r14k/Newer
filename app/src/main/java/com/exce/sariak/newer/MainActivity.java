package com.exce.sariak.newer;

import androidx.appcompat.app.AppCompatActivity;

import android.os.Bundle;

public class MainActivity extends AppCompatActivity implements OnClickListener {

    private DjiGLSurfaceView mDjiGLSurfaceView;
    private TextView commonMessageTextView;
    private LinearLayout centerLinearLayout;
    private Button startButton;
    private Button stitchingButton;
    private ProgressDialog mDownloadDialog;

    private final String STITCHING_SOURCE_IMAGES_DIRECTORY = Environment.getExternalStorageDirectory().getPath()+"/app_name/";
    private final String STITCHING_RESULT_IMAGES_DIRECTORY = Environment.getExternalStorageDirectory().getPath()+"/app_name/result/";

    private static final String TAG = "APPNAMEMainActivity";  //debug TAG. Edit to suit the name of your own app

    private BaseLoaderCallback mLoaderCallback = new BaseLoaderCallback(this) {
        @Override
        public void onManagerConnected(int status) {
            switch (status) {
                case LoaderCallbackInterface.SUCCESS:
                {
                    showLOG("OpenCV Manager loaded successfully");
                    break;
                }
                default:
                {
                    super.onManagerConnected(status);
                    break;
                }
            }
        }
    };

    private static boolean isDJIAoaStarted = false;  //DJIAoa

    private Timer checkCameraConnectionTimer = new Timer();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        setContentView(R.layout.activity_main);
        initUIControls();
        initStitchingImageDirectory();
        initOpenCVLoader();
        initDJISDK();
        initDJICamera();
    }

    private void showLOG(String str) {
        Log.e(TAG, str);
    }

    private void initUIControls() {
        //Assign variables to their corresponding views
        mDjiGLSurfaceView=(DjiGLSurfaceView)findViewById(R.id.mDjiSurfaceView);
        commonMessageTextView=(TextView)findViewById(R.id.commonMessageTextView);
        centerLinearLayout=(LinearLayout)findViewById(R.id.centerLinearLayout);
        startButton=(Button)findViewById(R.id.startButton);
        stitchingButton=(Button)findViewById(R.id.stitchingButton);

        //Add Listeners for buttons
        startButton.setOnClickListener(this);
        stitchingButton.setOnClickListener(this);

        //Customize controls
        commonMessageTextView.setText("");
        startButton.setClickable(false);
        stitchingButton.setEnabled(false);
        stitchingButton.setText(getString(R.string.one_key_panorama));

        initDownloadProgressDialog();
    }

    private void initDownloadProgressDialog() {
        mDownloadDialog = new ProgressDialog(MainActivity.this);
        mDownloadDialog.setTitle(R.string.downloading);
        mDownloadDialog.setIcon(android.R.drawable.ic_dialog_info);
        mDownloadDialog.setProgressStyle(ProgressDialog.STYLE_HORIZONTAL);
        mDownloadDialog.setCanceledOnTouchOutside(false);
        mDownloadDialog.setCancelable(false);
    }

    private void initStitchingImageDirectory() {
        //check if directories already exist. If not, create
        File sourceDirectory = new File(STITCHING_SOURCE_IMAGES_DIRECTORY);
        if(!sourceDirectory.exists())
        {
            sourceDirectory.mkdirs();
        }
        File resultDirectory = new File(STITCHING_RESULT_IMAGES_DIRECTORY);
        if(!resultDirectory.exists())
        {
            resultDirectory.mkdirs();
        }
    }

    private boolean initOpenCVLoader() {
        if (!OpenCVLoader.initDebug())
        {
            // Handle initialization error
            showLOG("init buildin OpenCVLoader error,going to use OpenCV Manager");
            OpenCVLoader.initAsync(OpenCVLoader.OPENCV_VERSION_2_4_3, this, mLoaderCallback);
            return false;
        }
        else
        {
            showLOG("init buildin OpenCVLoader success");
            return true;
        }
    }

    private void initDJISDK() {
        startDJIAoa();
        activateDJISDK();

        // The SDK initiation for Inspire 1
        DJIDrone.initWithType(this.getApplicationContext(), DJIDroneType.DJIDrone_Inspire1);
        DJIDrone.connectToDrone(); // Connect to the drone
    }

    private void startDJIAoa() {
        if(isDJIAoaStarted)
        {
            //Do nothing
            showLOG("DJIAoa aready started");
        }
        else
        {
            ServiceManager.getInstance();
            UsbAccessoryService.registerAoaReceiver(this);
            isDJIAoaStarted = true;
            showLOG("DJIAoa start success");
        }
        Intent aoaIntent = getIntent();
        if(aoaIntent != null)
        {
            String action = aoaIntent.getAction();
            if(action==UsbManager.ACTION_USB_ACCESSORY_ATTACHED || action == Intent.ACTION_MAIN)
            {
                Intent attachedIntent = new Intent();
                attachedIntent.setAction(DJIUsbAccessoryReceiver.ACTION_USB_ACCESSORY_ATTACHED);
                sendBroadcast(attachedIntent);
            }
        }
    }

    private void activateDJISDK() {
        new Thread()
        {
            public void run()
            {
                try
                {
                    DJIDrone.checkPermission(getApplicationContext(), new DJIGerneralListener()
                    {
                        @Override
                        public void onGetPermissionResult(int result)
                        {
                            //result=0 is success
                            showLOG("DJI SDK onGetPermissionResult = "+result);
                            showLOG("DJI SDK onGetPermissionResultDescription = "+DJIError.getCheckPermissionErrorDescription(result));
                            if(result!=0)
                            {
                                showToast(getString(R.string.dji_sdk_activate_error)+":"+DJIError.getCheckPermissionErrorDescription(result));
                            }
                        }
                    });
                }
                catch(Exception e)
                {
                    showLOG("activateDJISDK() Exception");
                    showToast("activateDJISDK() Exception");
                    e.printStackTrace();
                }
            }
        }.start();
    }

    private void showToast(String str) {
        Toast.makeText(this, str, Toast.LENGTH_SHORT).show();
    }

    private void initDJICamera()
    {
        //check camera status every 3 seconds
        checkCameraConnectionTimer.schedule(new CheckCameraConnectionTask(), 1000, 3000);
    }
}

class CheckCameraConnectionTask extends TimerTask {
    @Override
    public void run()
    {
        if(checkCameraConnectState()==true)
        {
            runOnUiThread(new Runnable() {
                public void run() {
                    startButton.setBackgroundResource(R.drawable.start_green);
                    startButton.setClickable(true);
                }
            });
        }
        else
        {
            runOnUiThread(new Runnable() {
                public void run() {
                    startButton.setBackgroundResource(R.drawable.start_gray);
                    startButton.setClickable(false);
                    stitchingButton.setEnabled(false);
                }
            });
        }
    }

    private boolean checkCameraConnectState(){
        //check connection
        boolean cameraConnectState = DJIDrone.getDjiCamera().getCameraConnectIsOk();
        if(cameraConnectState)
        {
            //showLOG("DJI Camera connect ok");
            return true;
        }
        else
        {
            //showLOG("DJI Camera connect failed");
            return false;
        }
    }
}
