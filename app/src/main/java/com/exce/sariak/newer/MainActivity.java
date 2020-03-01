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

    private DJIDroneType mDroneType;

    //Callback functions to implement
    private DJIReceivedVideoDataCallBack mReceivedVideoDataCallBack;
    private DJIGimbalErrorCallBack mGimbalErrorCallBack;
    private DJICameraPlayBackStateCallBack mCameraPlayBackStateCallBack;  //to get currently selected pictures count

    private DJIGimbalCapacity mDjiGimbalCapacity;
    private int numbersOfSelected = 0;  //updated from mCameraPlayBackStateCallBack
    private final int COMMON_MESSAGE_DURATION_TIME = 2500;  //in milliseconds

    private Timer commonMessageTimer = new Timer();

    private final int HANDLER_SHOW_COMMON_MESSAGE = 1000;
    private final int HANDLER_SET_STITCHING_BUTTON_TEXT = 1001;
    private final int HANDLER_ENABLE_STITCHING_BUTTON = 1003;
    private final int HANDLER_SHOW_STITCHING_OR_NOT_DIALOG = 1005;
    private final int CAPTURE_IMAGE_GIMBAL_INIT_POSITION = -2300;  //-2300 for inspire1
    private final int HANDLER_INSPIRE1_CAPTURE_IMAGES = 2000;

    private final int CAPTURE_IMAGE_NUMBER = 8;  //number of images to take to form a panorama
    private int captureImageFailedCount = 0;
    private boolean isCheckCaptureImageFailure = false;  //check dji camera capture result

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

    private void showCommonMessage(final String message)
    {
        runOnUiThread(new Runnable()
        {
            @Override
            public void run()
            {
                if(message.equals(commonMessageTextView.getText()))
                {
                    //filter same message
                    return;
                }
                commonMessageTextView.setText(message);
                commonMessageTimer.schedule(new commonMessageCleanTask(), COMMON_MESSAGE_DURATION_TIME);
            }
        });
    }

    private void startDJICamera() {
        // check drone type
        mDroneType = DJIDrone.getDroneType();

        // start SurfaceView
        mDjiGLSurfaceView.start();

        // decode video data
        mReceivedVideoDataCallBack = new DJIReceivedVideoDataCallBack() {
            @Override
            public void onResult(byte[] videoBuffer, int size) {
                mDjiGLSurfaceView.setDataToDecoder(videoBuffer, size);
            }
        };

        mGimbalErrorCallBack = new DJIGimbalErrorCallBack() {
            @Override
            public void onError(final int error) {
                if (error != DJIError.RESULT_OK) {
                    runOnUiThread(new Runnable() {
                        public void run() {
                            showCommonMessage("Gimbal error code=" + error);
                        }
                    });
                }
            }
        };

        mCameraPlayBackStateCallBack = new DJICameraPlayBackStateCallBack() {
            @Override
            public void onResult(DJICameraPlaybackState mState) {
                numbersOfSelected = mState.numbersOfSelected;
            }
        };

        DJIDrone.getDjiCamera().setReceivedVideoDataCallBack(
                mReceivedVideoDataCallBack);
        DJIDrone.getDjiGimbal().setGimbalErrorCallBack(mGimbalErrorCallBack);
        DJIDrone.getDjiCamera().setDJICameraPlayBackStateCallBack(
                mCameraPlayBackStateCallBack);

        DJIDrone.getDjiGimbal().startUpdateTimer(1000);
    }

    @Override
    public void onClick(View v) {
        switch (v.getId()) {
            case R.id.startButton:

                // start dji camera
                startDJICamera();

                centerLinearLayout.setVisibility(View.INVISIBLE); // hide startButton
                stitchingButton.setEnabled(true);
                break;

            case R.id.stitchingButton:
                cleanSourceFolder();
                stitchingButton.setEnabled(false);
                stitchingButton.setText(getString(R.string.one_key_panorama));

                if(mDroneType==DJIDroneType.DJIDrone_Inspire1)
                {
                    handler.sendMessage(handler.obtainMessage(HANDLER_INSPIRE1_CAPTURE_IMAGES,""));
                }
                else
                {
                    showCommonMessage(getString(R.string.unsupported_drone));
                }
                break;

            default:
                break;
        }

    }

    private void cleanSourceFolder()
    {
        File sourceDirectory = new File(STITCHING_SOURCE_IMAGES_DIRECTORY);
        //clean source file, except folders
        for(File file : sourceDirectory.listFiles())
        {
            if(!file.isDirectory())
            {
                file.delete();
            }
        }

    }

    private Handler handler = new Handler(new Handler.Callback() {
        @Override
        public boolean handleMessage(Message msg)
        {
            // handleMessage code
            switch (msg.what)
            {
                case HANDLER_INSPIRE1_CAPTURE_IMAGES:
                    // capture images code
                {
                    new Thread()
                    {
                        public void run()
                        {
                            //rotate gimble to take photos
                            int imgIndex=0;
                            showCommonMessage(getString(R.string.init_gimabal_yaw));
                            //init the gimbal yaw to Clockwise Min
                            while(DJIDrone.getDjiGimbal().getYawAngle()>CAPTURE_IMAGE_GIMBAL_INIT_POSITION)
                            {
                                DJIGimbalRotation mYaw_relative = new DJIGimbalRotation(true,false,false, 1000);
                                DJIDrone.getDjiGimbal().updateGimbalAttitude(null,null,mYaw_relative);
                                try
                                {
                                    sleep(50);
                                }
                                catch(InterruptedException e)
                                {
                                    e.printStackTrace();
                                }
                            }
                            DJIGimbalRotation mYaw_init_stop = new DJIGimbalRotation(true,false,false, 0);
                            DJIDrone.getDjiGimbal().updateGimbalAttitude(null,null,mYaw_init_stop);
                            try
                            {
                                sleep(50);
                            }
                            catch(InterruptedException e)
                            {
                                e.printStackTrace();
                            }

                            // Take specified number of photos
                            for(int i=-180;i<180;i+=(360/CAPTURE_IMAGE_NUMBER))
                            {
                                imgIndex++;
                                showCommonMessage(getString(R.string.capturing_image)+" "+imgIndex+"/"+CAPTURE_IMAGE_NUMBER);
                                DJIGimbalRotation mYaw = new DJIGimbalRotation(true,true,true, i);
                                DJIDrone.getDjiGimbal().updateGimbalAttitude(null,null,mYaw);
                                try
                                {
                                    sleep(3000);
                                }
                                catch(InterruptedException e)
                                {
                                    e.printStackTrace();
                                }
                                DJICameraTakePhoto();
                                try
                                {
                                    sleep(3000);
                                }
                                catch(InterruptedException e)
                                {
                                    e.printStackTrace();
                                }
                            }

                            //gimbal yaw face front
                            showCommonMessage(getString(R.string.capture_image_complete));
                            DJIGimbalRotation mYaw_front = new DJIGimbalRotation(true,false,true, 0);
                            DJIDrone.getDjiGimbal().updateGimbalAttitude(null,null,mYaw_front);
                            try
                            {
                                Thread.sleep(3000);
                            }
                            catch(InterruptedException e)
                            {
                                e.printStackTrace();
                            }
                            if(captureImageFailedCount!=0)
                            {
                                showCommonMessage("Check "+captureImageFailedCount+" images capture failed,Task Abort!");
                                captureImageFailedCount=0;
                                handler.sendMessage(handler.obtainMessage(HANDLER_SET_STITCHING_BUTTON_TEXT,getString(R.string.one_key_panorama)));
                                handler.sendMessage(handler.obtainMessage(HANDLER_ENABLE_STITCHING_BUTTON,""));
                            }
                            else
                            {
                                showCommonMessage("Check "+CAPTURE_IMAGE_NUMBER+" images capture all success,continue....");
                                try
                                {
                                    Thread.sleep(3000);
                                }
                                catch (InterruptedException e)
                                {
                                    e.printStackTrace();
                                }
                                //show dialog
                                handler.sendMessage(handler.obtainMessage(HANDLER_SHOW_STITCHING_OR_NOT_DIALOG, ""));
                            }
                        }
                    }.start();
                    break;
                }
                case HANDLER_SHOW_COMMON_MESSAGE:
                {
                    showCommonMessage((String)msg.obj);
                    break;
                }
                case HANDLER_SET_STITCHING_BUTTON_TEXT:
                {
                    stitchingButton.setText((String)msg.obj);
                    break;
                }
                case HANDLER_ENABLE_STITCHING_BUTTON:
                {
                    stitchingButton.setEnabled(true);
                    break;
                }
                case HANDLER_SHOW_STITCHING_OR_NOT_DIALOG:
                {
                    //capture complete, show dialog, user determines to continue or cancel
                    DialogInterface.OnClickListener positiveButtonOnClickListener=new DialogInterface.OnClickListener()
                    {
                        @Override
                        public void onClick(DialogInterface dialog, int which)
                        {
                            //set dji camera playback mode
                            handler.sendMessage(handler.obtainMessage(HANDLER_SET_DJI_CAMERA_PALYBACK_MODE, ""));
                        }
                    };
                    DialogInterface.OnClickListener negativeButtonOnClickListener=new DialogInterface.OnClickListener()
                    {
                        @Override
                        public void onClick(DialogInterface dialog, int which)
                        {
                            handler.sendMessage(handler.obtainMessage(HANDLER_SET_STITCHING_BUTTON_TEXT,getString(R.string.one_key_panorama)));
                            handler.sendMessage(handler.obtainMessage(HANDLER_ENABLE_STITCHING_BUTTON,""));
                        }
                    };
                    break;
                    new AlertDialog.Builder(MainActivity.this).setTitle("Message").setMessage("Capture complete,stitching?").setPositiveButton("OK", positiveButtonOnClickListener).setNegativeButton("Cancel", negativeButtonOnClickListener).show();

                    break;
                }
                case HANDLER_SET_DJI_CAMERA_PALYBACK_MODE:
                {
                    //set camera playback mode to pull back images
                    showCommonMessage("Set camera playback mode");
                    CameraMode mode_playback = CameraMode.Camera_PlayBack_Mode;
                    DJIDrone.getDjiCamera().setCameraMode(mode_playback, new DJIExecuteResultCallback()
                    {
                        @Override
                        public void onResult(DJIError mErr)
                        {
                            if(mErr.errorCode==DJIError.RESULT_OK)
                            {
                                //enter multi preview mode
                                new Thread()
                                {
                                    public void run()
                                    {
                                        try
                                        {
                                            Thread.sleep(3000);
                                        }
                                        catch(InterruptedException e)
                                        {
                                            e.printStackTrace();
                                        }
                                        //enter multi preview mode
                                        handler.sendMessage(handler.obtainMessage(HANDLER_SET_DJI_CAMERA_MULTI_PREVIEW_MODE, ""));
                                    }
                                }.start();
                            }
                            else
                            {
                                handler.sendMessage(handler.obtainMessage(HANDLER_SHOW_COMMON_MESSAGE, "Set camera playback mode failed"));
                            }
                        }
                    });
                    break;
                }
                case HANDLER_SET_DJI_CAMERA_MULTI_PREVIEW_MODE:
                {

                    //enter multi preview mode
                    showCommonMessage("Enter multi preview mode");
                    DJIDrone.getDjiCamera().enterMultiplePreviewMode(new DJIExecuteResultCallback()
                    {
                        @Override
                        public void onResult(DJIError mErr)
                        {
                            if(mErr.errorCode==DJIError.RESULT_OK)
                            {
                                new Thread()
                                {
                                    public void run()
                                    {
                                        try
                                        {
                                            Thread.sleep(3000);
                                        }
                                        catch(InterruptedException e)
                                        {
                                            e.printStackTrace();
                                        }
                                        //enter multi edit mode
                                        handler.sendMessage(handler.obtainMessage(HANDLER_SET_DJI_CAMERA_MULTI_EDIT_MODE, ""));
                                    }
                                }.start();
                            }
                            else
                            {
                                handler.sendMessage(handler.obtainMessage(HANDLER_SHOW_COMMON_MESSAGE, "Enter multi preview mode failed"));
                            }
                        }
                    });
                    break;
                }
                case HANDLER_SET_DJI_CAMERA_MULTI_EDIT_MODE:
                {
                    //enter multi edit mode
                    showCommonMessage("Enter multi edit mode");
                    DJIDrone.getDjiCamera().enterMultipleEditMode(new DJIExecuteResultCallback()
                    {
                        @Override
                        public void onResult(DJIError mErr)
                        {
                            if(mErr.errorCode==DJIError.RESULT_OK)
                            {
                                new Thread()
                                {
                                    public void run()
                                    {
                                        try
                                        {
                                            Thread.sleep(3000);
                                        }
                                        catch(InterruptedException e)
                                        {
                                            e.printStackTrace();
                                        }
                                        //select page(max 8)
                                        handler.sendMessage(handler.obtainMessage(HANDLER_SET_DJI_CAMERA_SELECT_PAGE, ""));
                                    }
                                }.start();
                            }
                            else
                            {
                                handler.sendMessage(handler.obtainMessage(HANDLER_SHOW_COMMON_MESSAGE, "Enter multi edit mode failed"));
                            }
                        }
                    });
                    break;
                }

                case HANDLER_SET_DJI_CAMERA_SELECT_PAGE:
                {
                    //select page(max 8)
                    showCommonMessage("Select all file in page");
                    DJIDrone.getDjiCamera().selectAllFilesInPage(new DJIExecuteResultCallback()
                    {
                        @Override
                        public void onResult(DJIError mErr)
                        {
                            if(mErr.errorCode==DJIError.RESULT_OK)
                            {
                                new Thread()
                                {
                                    public void run()
                                    {
                                        try
                                        {
                                            Thread.sleep(3000);
                                        }
                                        catch(InterruptedException e)
                                        {
                                            e.printStackTrace();
                                        }
                                        if(numbersOfSelected<CAPTURE_IMAGE_NUMBER)
                                        {
                                            //enter previous page
                                            handler.sendMessage(handler.obtainMessage(HANDLER_SET_DJI_CAMERA_PREVIOUS_PAGE, ""));
                                        }
                                        else
                                        {
                                            //download selected
                                            handler.sendMessage(handler.obtainMessage(HANDLER_SET_DJI_CAMERA_DOWNLOAD_SELECTED, ""));
                                        }
                                    }
                                }.start();
                            }
                            else
                            {
                                handler.sendMessage(handler.obtainMessage(HANDLER_SHOW_COMMON_MESSAGE, "Select all file in page failed"));
                            }
                        }

                    });
                    break;
                }

                case HANDLER_SET_DJI_CAMERA_PREVIOUS_PAGE:
                {
                    //if no enough in this page,go back previous page
                    showCommonMessage("No enough images,go back previous page");
                    DJIDrone.getDjiCamera().multiplePreviewPreviousPage(new DJIExecuteResultCallback()
                    {
                        @Override
                        public void onResult(DJIError mErr)
                        {
                            if(mErr.errorCode==DJIError.RESULT_OK)
                            {
                                new Thread()
                                {
                                    public void run()
                                    {
                                        try
                                        {
                                            Thread.sleep(3000);
                                        }
                                        catch(InterruptedException e)
                                        {
                                            e.printStackTrace();
                                        }
                                        //go back previous page
                                        handler.sendMessage(handler.obtainMessage(HANDLER_SET_DJI_CAMERA_SELECT_FILE_AT_INDEX, ""));
                                    }
                                }.start();
                            }
                            else
                            {
                                handler.sendMessage(handler.obtainMessage(HANDLER_SHOW_COMMON_MESSAGE, "Go back previous page failed"));
                            }
                        }
                    });
                    break;
                }

                case HANDLER_SET_DJI_CAMERA_SELECT_FILE_AT_INDEX:
                {
                    new Thread()
                    {
                        public void run()
                        {
                            showCommonMessage("Select rest "+(CAPTURE_IMAGE_NUMBER-numbersOfSelected)+" images");
                            for(int i=numbersOfSelected;i<CAPTURE_IMAGE_NUMBER;i++)
                            {
                                //select single file
                                DJIDrone.getDjiCamera().selectFileAtIndex(i, new DJIExecuteResultCallback()
                                {
                                    @Override
                                    public void onResult(DJIError mErr)
                                    {
                                        if(mErr.errorCode==DJIError.RESULT_OK)
                                        {

                                        }
                                        else
                                        {

                                        }
                                    }
                                });
                                try
                                {
                                    Thread.sleep(1000);
                                }
                                catch (InterruptedException e)
                                {
                                    e.printStackTrace();
                                }
                            }
                            try
                            {
                                Thread.sleep(1000);
                            }
                            catch (InterruptedException e)
                            {
                                e.printStackTrace();
                            }
                            //download selected
                            handler.sendMessage(handler.obtainMessage(HANDLER_SET_DJI_CAMERA_DOWNLOAD_SELECTED, ""));
                        }
                    }.start();
                    break;
                }
                case HANDLER_SET_DJI_CAMERA_DOWNLOAD_SELECTED:
                {
                    //download file
                    File downloadPath = new File(STITCHING_SOURCE_IMAGES_DIRECTORY);
                    DJIDrone.getDjiCamera().downloadAllSelectedFiles(downloadPath,new DJIFileDownloadCallBack()
                    {
                        @Override
                        public void OnStart()
                        {
                            runOnUiThread(new Runnable()
                            {
                                @Override
                                public void run()
                                {
                                    showDownloadProgressDialog();
                                }
                            });
                        }

                        @Override
                        public void OnError(Exception exception)
                        {
                            if(isCheckDownloadImageFailure)
                            {
                                downloadImageFailedCount++;
                                handler.sendMessage(handler.obtainMessage(HANDLER_SHOW_COMMON_MESSAGE, "Downloading images on error"));
                            }
                        }

                        @Override
                        public void OnEnd()
                        {
                            new Thread()
                            {
                                public void run()
                                {
                                    handler.sendMessage(handler.obtainMessage(HANDLER_SET_DJI_CAMERA_FINISH_DOWNLOAD_FILES,""));
                                    handler.sendMessage(handler.obtainMessage(HANDLER_SHOW_COMMON_MESSAGE, "Download finished"));
                                    try
                                    {
                                        Thread.sleep(3000);
                                    }
                                    catch (InterruptedException e)
                                    {
                                        e.printStackTrace();
                                    }
                                    handler.sendMessage(handler.obtainMessage(HANDLER_SET_DJI_CAMERA_CAPTURE_MODE,""));
                                    try
                                    {
                                        Thread.sleep(3000);
                                    }
                                    catch (InterruptedException e)
                                    {
                                        e.printStackTrace();
                                    }
                                    //some images download failed
                                    if(downloadImageFailedCount!=0)
                                    {
                                        handler.sendMessage(handler.obtainMessage(HANDLER_SHOW_COMMON_MESSAGE, "Check "+downloadImageFailedCount+" images download failed,Task Abort!"));
                                        downloadImageFailedCount=0;
                                        handler.sendMessage(handler.obtainMessage(HANDLER_SET_STITCHING_BUTTON_TEXT,getString(R.string.one_key_panorama)));
                                        handler.sendMessage(handler.obtainMessage(HANDLER_ENABLE_STITCHING_BUTTON,""));
                                    }
                                    else
                                    {
                                        handler.sendMessage(handler.obtainMessage(HANDLER_SHOW_COMMON_MESSAGE, "Check "+CAPTURE_IMAGE_NUMBER+" images download all success,stitching...."));
                                        try
                                        {
                                            Thread.sleep(3000);
                                        }
                                        catch (InterruptedException e)
                                        {
                                            e.printStackTrace();
                                        }
                                        handler.sendMessage(handler.obtainMessage(HANDLER_START_STITCHING,""));
                                    }
                                }
                            }.start();
                        }

                        @Override
                        public void OnProgressUpdate(final int progress)
                        {
                            runOnUiThread(new Runnable()
                            {
                                @Override
                                public void run()
                                {
                                    if(mDownloadDialog!=null)
                                    {
                                        mDownloadDialog.setProgress(progress);
                                    }
                                    if(progress>=100)
                                    {
                                        hideDownloadProgressDialog();
                                    }
                                }
                            });
                        }
                    });
                    break;
                }
                case HANDLER_SET_DJI_CAMERA_FINISH_DOWNLOAD_FILES:
                {
                    //finish download
                    DJIDrone.getDjiCamera().finishDownloadAllSelectedFiles(new DJIExecuteResultCallback()
                    {
                        @Override
                        public void onResult(DJIError mErr)
                        {
                            if(mErr.errorCode==DJIError.RESULT_OK)
                            {
                                handler.sendMessage(handler.obtainMessage(HANDLER_SHOW_COMMON_MESSAGE, "Finished download"));
                            }
                            else
                            {
                                handler.sendMessage(handler.obtainMessage(HANDLER_SHOW_COMMON_MESSAGE, "Finished download failed"));
                            }
                        }
                    });
                    break;
                }
                case HANDLER_SET_DJI_CAMERA_CAPTURE_MODE:
                {
                    CameraMode mode=CameraMode.Camera_Capture_Mode;
                    DJIDrone.getDjiCamera().setCameraMode(mode, new DJIExecuteResultCallback()
                    {
                        @Override
                        public void onResult(DJIError mErr)
                        {
                            if(mErr.errorCode==DJIError.RESULT_OK)
                            {

                            }
                            else
                            {
                                handler.sendMessage(handler.obtainMessage(HANDLER_SHOW_COMMON_MESSAGE, "Set camera capture mode failed"));
                            }
                        }
                    });
                    break;
                }
                case HANDLER_START_STITCHING:
                {
                    // Start stitching
                    break;
                }
                case HANDLER_START_STITCHING:
                {
                    if(isDIsableDJIVideoPreviewDuringStitching)
                    {
                        new Thread()
                        {
                            public void run()
                            {
                                handler.sendMessage(handler.obtainMessage(HANDLER_DISABLE_DJI_VIDEO_PREVIEW,""));  //disable dji video preview
                                while(isStitchingCompleted==false)
                                {
                                    handler.sendMessage(handler.obtainMessage(HANDLER_SHOW_COMMON_MESSAGE, getString(R.string.video_preview_disabled_during_stitching)));
                                    try
                                    {
                                        sleep(4000);
                                    }
                                    catch(InterruptedException e)
                                    {
                                        e.printStackTrace();
                                    }
                                }
                            }
                        }.start();
                    }

                    handler.sendMessage(handler.obtainMessage(HANDLER_SET_STITCHING_BUTTON_TEXT,"Stitching...."));
                    new Thread()
                    {
                        public void run()
                        {
                            isStitchingCompleted=false;
                            String[] source=getDirectoryFilelist(STITCHING_SOURCE_IMAGES_DIRECTORY);
                            stitchingResultImagePath=STITCHING_RESULT_IMAGES_DIRECTORY+getCurrentDateTime()+"result.jpg";
                            if(jnistitching(source, stitchingResultImagePath, STITCH_IMAGE_SCALE)==0)
                            {
                                handler.sendMessage(handler.obtainMessage(HANDLER_SET_STITCHING_BUTTON_TEXT,"Stitching success"));
                            }
                            else
                            {
                                handler.sendMessage(handler.obtainMessage(HANDLER_SET_STITCHING_BUTTON_TEXT,"Stitching error"));
                            }
                            handler.sendMessage(handler.obtainMessage(HANDLER_SET_STITCHING_BUTTON_TEXT,getString(R.string.one_key_panorama)));
                            handler.sendMessage(handler.obtainMessage(HANDLER_ENABLE_STITCHING_BUTTON,""));
                            handler.sendMessage(handler.obtainMessage(HANDLER_ENABLE_DJI_VIDEO_PREVIEW,""));
                            isStitchingCompleted=true;
                        }
                    }.start();
                    break;
                }
                case HANDLER_DISABLE_DJI_VIDEO_PREVIEW:
                {
                    DJIDrone.getDjiCamera().setReceivedVideoDataCallBack(null);
                    break;
                }
                case HANDLER_ENABLE_DJI_VIDEO_PREVIEW:
                {
                    DJIDrone.getDjiCamera().setReceivedVideoDataCallBack(mReceivedVideoDataCallBack);
                    break;
                }

            }
        }
    });

    private String[] getDirectoryFilelist(String directory)
    {
        String[] filelist;
        File sourceDirectory = new File(STITCHING_SOURCE_IMAGES_DIRECTORY);
        int index=0;
        int folderCount=0;
        //except folders
        for(File file : sourceDirectory.listFiles())
        {
            if(file.isDirectory())
            {
                folderCount++;
            }
        }
        filelist=new String[sourceDirectory.listFiles().length-folderCount];
        for(File file : sourceDirectory.listFiles())
        {
            if(!file.isDirectory())
            {
                //showLOG("getFilelist file:"+file.getPath());
                filelist[index]=file.getPath();
                index++;
            }
        }
        return filelist;
    }

    private String getCurrentDateTime()
    {
        Calendar c = Calendar .getInstance();
        SimpleDateFormat df = new SimpleDateFormat("yyyyMMddHHmmss",Locale.getDefault());
        return df.format(c.getTime());
    }

    private void DJICameraTakePhoto()
    {
        CameraCaptureMode mode = CameraCaptureMode.Camera_Single_Capture;
        DJIDrone.getDjiCamera().startTakePhoto(mode, new DJIExecuteResultCallback()
        {
            @Override
            public void onResult(DJIError mErr)
            {
                if(mErr.errorCode==DJIError.RESULT_OK)
                {
                    showLOG("take photo success");
                }
                else
                {
                    if(isCheckCaptureImageFailure)
                    {
                        captureImageFailedCount++;
                        handler.sendMessage(handler.obtainMessage(HANDLER_SHOW_COMMON_MESSAGE, "Capture image on error"));
                    }
                    showLOG("take photo failed");
                }
            }
        });
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

class commonMessageCleanTask extends TimerTask
{
    @Override
    public void run()
    {
        runOnUiThread(new Runnable()
        {
            public void run()
            {
                commonMessageTextView.setText("");
            }
        });
    }
}
