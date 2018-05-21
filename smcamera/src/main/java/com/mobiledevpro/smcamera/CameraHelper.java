package com.mobiledevpro.smcamera;

import android.annotation.TargetApi;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.graphics.Matrix;
import android.graphics.RectF;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.media.MediaRecorder;
import android.os.Handler;
import android.os.HandlerThread;
import android.support.annotation.NonNull;
import android.util.Log;
import android.util.Range;
import android.util.Size;
import android.view.Surface;
import android.view.TextureView;

import com.samsung.android.sdk.SsdkUnsupportedException;
import com.samsung.android.sdk.camera.SCamera;
import com.samsung.android.sdk.camera.SCameraCaptureSession;
import com.samsung.android.sdk.camera.SCameraCharacteristics;
import com.samsung.android.sdk.camera.SCameraDevice;
import com.samsung.android.sdk.camera.SCameraManager;
import com.samsung.android.sdk.camera.SCaptureRequest;
import com.samsung.android.sdk.camera.STotalCaptureResult;

import java.io.File;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * Camera helper
 * <p>
 * Created by Dmitriy V. Chernysh on 30.04.18.
 * <p>
 * https://instagr.am/mobiledevpro
 * https://github.com/dmitriy-chernysh
 * #MobileDevPro
 */
@TargetApi(21)
class CameraHelper implements ICameraHelper {

    private static final int CAMERA_STATE_IDLE = 0;
    private static final int CAMERA_STATE_START_PREVIEW = 1;
    private static final int CAMERA_STATE_PREVIEW = 2;
    private static final int CAMERA_STATE_RECORD_VIDEO = 3;
    private static final int CAMERA_STATE_CLOSING = 4;

    private static CameraHelper sHelper;

    //A {@link Semaphore} to prevent the app from exiting before closing the camera.
    private Semaphore mCameraOpenCloseLock = new Semaphore(1);

    private SCamera mSCamera;
    private SCameraManager mSCameraManager;
    private SCameraDevice mSCameraDevice;
    private SCameraCaptureSession mSCameraSession;
    private SCameraCharacteristics mCharacteristics;
    private SCaptureRequest.Builder mPreviewBuilder;
    private List<SCaptureRequest> mRepeatingList;

    private String mCameraId;
    private int mCameraState = CAMERA_STATE_IDLE;

    private int mLastOrientation = 270;
    private Size mPreviewSize;
    private VideoParameter mVideoParameter;
    private TextureView mTextureView;

    private HandlerThread mBackgroundHandlerThread;
    private Handler mBackgroundHandler;

    private MediaRecorder mMediaRecorder;
    private File mVideoFilesDir;

    private SCameraCaptureSession.CaptureCallback mSessionCaptureCallback = new SCameraCaptureSession.CaptureCallback() {
        @Override
        public void onCaptureCompleted(SCameraCaptureSession session,
                                       SCaptureRequest request,
                                       STotalCaptureResult result) {
            // Depends on the current state and capture result, app will take next action.
            switch (getCameraState()) {

                case CAMERA_STATE_IDLE:
                case CAMERA_STATE_PREVIEW:
                case CAMERA_STATE_CLOSING:
                    // do nothing
                    break;
            }
        }
    };

    private CameraHelper(@NonNull Context context, @NonNull File videoFilesDir) {
        mVideoFilesDir = videoFilesDir;
        Log.d(Constants.LOG_TAG_DEBUG, "CameraHelper.CameraHelper(): Video files dir: " + mVideoFilesDir);
        initSCamera(context);
    }

    public static CameraHelper init(@NonNull Context context, @NonNull File videoFilesDir) {
        if (sHelper == null) sHelper = new CameraHelper(context, videoFilesDir);
        return sHelper;
    }

    @Override
    public boolean isThisSamsungDevice() {
        if (mSCamera.isFeatureEnabled(SCamera.SCAMERA_PROCESSOR)) {
            return true;
        }

        return false;
    }

    @Override
    public void startCamera(Context context,
                            boolean useBackCamera,
                            TextureView textureView,
                            int textureWidth,
                            int textureHeight,
                            int rotation) {
        mTextureView = textureView;
        Log.d(Constants.LOG_TAG_DEBUG, "CameraHelper.startCamera(): Texture W " + mTextureView.getWidth() + " H " + mTextureView.getHeight());
        startBackgroundThread(context);
        try {
            configureCameraParameters(useBackCamera);
            configureTextureViewTransform(textureWidth, textureHeight, rotation);
            openCamera();
        } catch (RuntimeException e) {
            showAlertDialog(context, e.getLocalizedMessage(), true);
        }
    }

    @Override
    public void stopCamera(Context context) {
        stopBackgroundThread();
        try {
            closeCamera();
        } catch (RuntimeException e) {
            showAlertDialog(context, e.getLocalizedMessage(), true);
        }
        mTextureView = null;
    }

    @Override
    public synchronized void startStopVideoRecording() {
        if (getCameraState() == CAMERA_STATE_PREVIEW) {
            setCameraState(CAMERA_STATE_RECORD_VIDEO);
            //start video recording
            mMediaRecorder.start();
            Log.d(Constants.LOG_TAG_DEBUG, "CameraHelper.startStopVideoRecording(): START RECORD");
        } else if (getCameraState() == CAMERA_STATE_RECORD_VIDEO) {
            //stop video recording
            mMediaRecorder.stop();
            mMediaRecorder.reset();
            Log.d(Constants.LOG_TAG_DEBUG, "CameraHelper.startStopVideoRecording(): STOP RECORD");

            mBackgroundHandler.post(() -> {
                prepareMediaRecorder();
                //start preview again
                createPreviewSession();
            });

        }
    }

    /**
     * Starts background thread that callback from camera will posted.
     * NOTE: calls in onStart or onResume
     */
    private void startBackgroundThread(Context context) throws RuntimeException {
        mBackgroundHandlerThread = new HandlerThread("CameraThread");
        mBackgroundHandlerThread.setUncaughtExceptionHandler((thread, throwable) -> {
            Log.d(Constants.LOG_TAG_DEBUG, "CameraHelper.startBackgroundThread(): " + throwable.getLocalizedMessage());
            showAlertDialog(context, throwable.getLocalizedMessage(), true);
        });
        mBackgroundHandlerThread.start();
        mBackgroundHandler = new Handler(mBackgroundHandlerThread.getLooper());
    }

    /**
     * Stops background thread.
     * NOTE: calls in onStop or onPause
     */
    private void stopBackgroundThread() {
        if (mBackgroundHandlerThread != null) {
            mBackgroundHandlerThread.quitSafely();
            try {
                mBackgroundHandlerThread.join();
                mBackgroundHandlerThread = null;
                mBackgroundHandler = null;
            } catch (InterruptedException e) {
                Log.e(Constants.LOG_TAG_ERROR, "CameraHelper.stopBackgroundThread: InterruptedException: " + e.getLocalizedMessage(), e);
            }
        }
    }


    /**
     * Init camera
     *
     * @param context Activity context
     * @return True - camera has been initialized
     */
    private boolean initSCamera(Context context) {
        mSCamera = new SCamera();
        String message;
        try {
            mSCamera.initialize(context);
            return true;
        } catch (SsdkUnsupportedException e) {
            int eType = e.getType();
            message = e.getLocalizedMessage();
            if (eType == SsdkUnsupportedException.VENDOR_NOT_SUPPORTED) {
                message = "It's not a Samsung camera.\n" + message;
            } else if (eType == SsdkUnsupportedException.DEVICE_NOT_SUPPORTED) {
                message = "This device is not supported.\n" + message;
            } else {
                message = "Fail to initialize SCamera.\n" + message;
            }
            showAlertDialog(context, message, true);
        }

        return false;
    }

    private void configureCameraParameters(boolean useBackCamera) {
        try {
            if (!mCameraOpenCloseLock.tryAcquire(3000, TimeUnit.MILLISECONDS)) {
                throw new RuntimeException("Timeout (3 sec) waiting to lock camera opening.");
            }

            //Getting camera id
            mCameraId = getCameraId(useBackCamera);
            if (mCameraId == null) {
                throw new RuntimeException("Cannot get camera parameters. Error: camera id is null. Please, try again.");
            }

            Log.d(Constants.LOG_TAG_DEBUG, "CameraHelper.openCamera(): Camera ID " + mCameraId);

            // acquires camera characteristics
            mCharacteristics = mSCamera.getSCameraManager().getCameraCharacteristics(mCameraId);

           /* for (Size videoSize : mCharacteristics.get(SCameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP).getHighSpeedVideoSizes()) {
                for (Range<Integer> fpsRange : mCharacteristics.get(SCameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP).getHighSpeedVideoFpsRangesFor(videoSize)) {
                    //we will record constant fps video
                    if (fpsRange.getLower().equals(fpsRange.getUpper())) {
                        mVideoParameterList.add(new VideoParameter(videoSize, fpsRange));
                    }
                }
            }
            */

            // TODO: 18.05.18 need to add ability to change FPS from app settings (30 or 60)
            Range<Integer> fpsRange = new Range<>(30, 30);
            Size videoSize = new Size(1280, 720);
            mVideoParameter = new VideoParameter(videoSize, fpsRange);

            mPreviewSize = mVideoParameter.getVideoSize();
        } catch (InterruptedException e) {
            throw new RuntimeException("Interrupted while trying to lock camera opening. Error: " + e.getLocalizedMessage());
        } catch (CameraAccessException e) {
            throw new RuntimeException("Cannot get camera parameters. Error: " + e.getLocalizedMessage());
        }
    }

    /**
     * Open camera
     */
    private void openCamera() throws RuntimeException {
        try {

            mSCameraManager = mSCamera.getSCameraManager();

            // Opening the camera device
            mSCameraManager.openCamera(mCameraId, new SCameraDevice.StateCallback() {
                @Override
                public void onDisconnected(SCameraDevice sCameraDevice) {
                    mCameraOpenCloseLock.release();
                    if (getCameraState() == CAMERA_STATE_CLOSING) return;
                    throw new RuntimeException("Camera disconnected.");
                }

                @Override
                public void onError(SCameraDevice sCameraDevice, int i) {
                    mCameraOpenCloseLock.release();
                    if (getCameraState() == CAMERA_STATE_CLOSING) return;
                    throw new RuntimeException("Error while camera open.");
                }

                public void onOpened(SCameraDevice cameraDevice) {
                    Log.d(Constants.LOG_TAG_DEBUG, "CameraHelper.onOpened(): " + cameraDevice.toString());
                    mCameraOpenCloseLock.release();
                    if (getCameraState() == CAMERA_STATE_CLOSING) return;
                    mSCameraDevice = cameraDevice;

                    prepareMediaRecorder();
                    createPreviewSession();
                }
            }, mBackgroundHandler);

        } catch (CameraAccessException e) {
            throw new RuntimeException("Cannot open the camera. Error: " + e.getLocalizedMessage());
        }
    }

    /**
     * Close camera
     */
    private void closeCamera() throws RuntimeException {
        try {
            mCameraOpenCloseLock.acquire();

            stopPreview();

            if (mSCameraSession != null) {
                mSCameraSession.close();
                mSCameraSession = null;
            }

            if (mSCameraDevice != null) {
                mSCameraDevice.close();
                mSCameraDevice = null;
            }

            if (null != mMediaRecorder) {
                mMediaRecorder.release();
                mMediaRecorder = null;
            }

            mSCameraManager = null;
            mSCamera = null;
        } catch (InterruptedException e) {
            throw new RuntimeException("Interrupted while trying to lock camera closing. Error: " + e.getLocalizedMessage());
        } finally {
            mCameraOpenCloseLock.release();
        }
    }

    /**
     * Determine ID of the camera
     *
     * @param isBackCamera True - use back camera
     * @return Camera ID
     * @throws CameraAccessException
     */
    private String getCameraId(boolean isBackCamera) throws CameraAccessException {
        if (mSCamera == null) return null;
        // Find camera device that facing to given facing parameter.
        for (String id : mSCamera.getSCameraManager().getCameraIdList()) {
            SCameraCharacteristics cameraCharacteristics = mSCamera.getSCameraManager().getCameraCharacteristics(id);
            if (isBackCamera) {
                if (cameraCharacteristics.get(SCameraCharacteristics.LENS_FACING) == SCameraCharacteristics.LENS_FACING_BACK) {
                    return id;
                }
            } else {
                if (cameraCharacteristics.get(SCameraCharacteristics.LENS_FACING) == SCameraCharacteristics.LENS_FACING_FRONT) {
                    return id;
                }
            }
        }

        return null;
    }

    private synchronized int getCameraState() {
        return mCameraState;
    }

    private synchronized void setCameraState(int state) {
        mCameraState = state;
    }

    private void createPreviewSession() throws RuntimeException {
        if (null == mSCamera || null == mSCameraDevice || null == mSCameraManager /*|| !mTextureView.isAvailable()*/)
            return;

        try {
            setCameraState(CAMERA_STATE_START_PREVIEW);

            Log.d(Constants.LOG_TAG_DEBUG, "CameraHelper.createPreviewSession(): Preview size: " + mPreviewSize + " Video size: " + mVideoParameter.getVideoSize());

            // Set the aspect ratio to TextureView
        /*    int orientation = getResources().getConfiguration().orientation;
            if (orientation == Configuration.ORIENTATION_LANDSCAPE) {
                mTextureView.setAspectRatio(
                        mPreviewSize.getWidth(), mPreviewSize.getHeight());
            } else {
                mTextureView.setAspectRatio(
                        mPreviewSize.getHeight(), mPreviewSize.getWidth());
            }
            */

            SurfaceTexture texture = mTextureView.getSurfaceTexture();
            Surface previewSurface = new Surface(texture);
            Surface recorderSurface = mMediaRecorder.getSurface();

            // Creates SCaptureRequest.Builder for preview and recording with output target.
            mPreviewBuilder = mSCameraDevice.createCaptureRequest(SCameraDevice.TEMPLATE_RECORD);

            // {@link com.samsung.android.sdk.camera.processor.SCameraEffectProcessor} supports only 24fps.
            mPreviewBuilder.set(SCaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, mVideoParameter.getFpsRange());
            mPreviewBuilder.set(SCaptureRequest.CONTROL_AF_MODE, SCaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO);
            //mPreviewBuilder.set(SCaptureRequest.CONTROL_SCENE_MODE, SCaptureRequest.CONTROL_SCENE_MODE_HIGH_SPEED_VIDEO);
            mPreviewBuilder.addTarget(previewSurface);
            mPreviewBuilder.addTarget(recorderSurface);

            // limit preview fps up to 30.
            int requestListSize = mVideoParameter.mFpsRange.getUpper() > 30 ? mVideoParameter.mFpsRange.getUpper() / 30 : 1;

            mRepeatingList = new ArrayList<>();
            mRepeatingList.add(mPreviewBuilder.build());
            mPreviewBuilder.removeTarget(previewSurface);
            for (int i = 0; i < requestListSize - 1; i++) {
                mRepeatingList.add(mPreviewBuilder.build());
            }

            Log.d(Constants.LOG_TAG_DEBUG, "CameraHelper.createPreviewSession(): Request size: " + mRepeatingList.size());

            // Creates a SCameraCaptureSession here.
            List<Surface> outputSurface = Arrays.asList(previewSurface, recorderSurface);
            mSCameraDevice.createCaptureSession(outputSurface, new SCameraCaptureSession.StateCallback() {
                @Override
                public void onConfigureFailed(SCameraCaptureSession sCameraCaptureSession) {
                    if (getCameraState() == CAMERA_STATE_CLOSING) return;
                    throw new RuntimeException("Fail to create camera capture session.");
                }

                @Override
                public void onConfigured(SCameraCaptureSession sCameraCaptureSession) {
                    if (getCameraState() == CAMERA_STATE_CLOSING) return;
                    mSCameraSession = sCameraCaptureSession;

                    startPreview();
                }
            }, mBackgroundHandler);

        } catch (CameraAccessException e) {
            throw new RuntimeException("Failed to create camera capture session. Error: " + e.getLocalizedMessage());
        }
    }

    /**
     * Prepares the media recorder to begin recording.
     */
    private void prepareMediaRecorder() throws RuntimeException {
        try {
            mMediaRecorder = new MediaRecorder();
            mMediaRecorder.setVideoSource(MediaRecorder.VideoSource.SURFACE);
            mMediaRecorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
            mMediaRecorder.setOutputFile(createNewVideoFile().getAbsolutePath());

            int bitrate = 384000;
            if (mVideoParameter.getVideoSize().getWidth() * mVideoParameter.getVideoSize().getHeight() >= 1920 * 1080) {
                bitrate = 14000000;
            } else if (mVideoParameter.getVideoSize().getWidth() * mVideoParameter.getVideoSize().getHeight() >= 1280 * 720) {
                bitrate = 9730000;
            } else if (mVideoParameter.getVideoSize().getWidth() * mVideoParameter.getVideoSize().getHeight() >= 640 * 480) {
                bitrate = 2500000;
            } else if (mVideoParameter.getVideoSize().getWidth() * mVideoParameter.getVideoSize().getHeight() >= 320 * 240) {
                bitrate = 622000;
            }
            mMediaRecorder.setVideoEncodingBitRate(bitrate);

            mMediaRecorder.setVideoFrameRate(mVideoParameter.getFpsRange().getUpper());
            mMediaRecorder.setVideoSize(mVideoParameter.getVideoSize().getWidth(), mVideoParameter.getVideoSize().getHeight());
            mMediaRecorder.setVideoEncoder(MediaRecorder.VideoEncoder.H264);
            mMediaRecorder.setOrientationHint(getJpegOrientation());
            mMediaRecorder.prepare();
        } catch (IOException e) {
            throw new RuntimeException("Prepare MediaRecorder error: " + e.getLocalizedMessage());
        }
    }

    /**
     * Starts a preview.
     */
    private void startPreview() throws RuntimeException {
        try {
            // Starts displaying the preview.
            mSCameraSession.setRepeatingBurst(mRepeatingList, mSessionCaptureCallback, mBackgroundHandler);
            setCameraState(CAMERA_STATE_PREVIEW);
        } catch (CameraAccessException e) {
            throw new RuntimeException("Fail to start preview. Error: " + e.getLocalizedMessage());
        }
    }


    /**
     * Stop a preview.
     */
    private void stopPreview() throws RuntimeException {
        try {
            if (mSCameraSession != null)
                mSCameraSession.stopRepeating();
        } catch (CameraAccessException e) {
            throw new RuntimeException("Fail to stop preview. Error: " + e.getLocalizedMessage());
        }
    }

    /**
     * Returns required orientation that the jpeg picture needs to be rotated to be displayed upright.
     */
    private int getJpegOrientation() {
        int degrees = mLastOrientation;

        if (mCharacteristics.get(SCameraCharacteristics.LENS_FACING) == SCameraCharacteristics.LENS_FACING_FRONT) {
            degrees = -degrees;
        }

        return (mCharacteristics.get(SCameraCharacteristics.SENSOR_ORIENTATION) + degrees + 360) % 360;
    }

    /**
     * Configures requires transform {@link android.graphics.Matrix} to TextureView.
     */
    private void configureTextureViewTransform(int viewWidth, int viewHeight, int rotation) {

        if (null == mTextureView || null == mPreviewSize) return;

        Log.d(Constants.LOG_TAG_DEBUG, "CameraHelper.configureTextureViewTransform(): W " + viewWidth + " H " + viewHeight);
        // int rotation = Surface.ROTATION_90;//mView.getActivity().getWindowManager().getDefaultDisplay().getRotation();
        Matrix matrix = new Matrix();
        RectF viewRect = new RectF(0, 0, viewWidth, viewHeight);
        RectF bufferRect = new RectF(0, 0, mPreviewSize.getHeight(), mPreviewSize.getWidth());
        float centerX = viewRect.centerX();
        float centerY = viewRect.centerY();
        if (Surface.ROTATION_90 == rotation || Surface.ROTATION_270 == rotation) {
            bufferRect.offset(centerX - bufferRect.centerX(), centerY - bufferRect.centerY());
            matrix.setRectToRect(viewRect, bufferRect, Matrix.ScaleToFit.FILL);
            float scale = Math.max(
                    (float) viewHeight / mPreviewSize.getHeight(),
                    (float) viewWidth / mPreviewSize.getWidth());
            matrix.postScale(scale, scale, centerX, centerY);
            matrix.postRotate(90 * (rotation - 2), centerX, centerY);
        } else {
            matrix.postRotate(90 * rotation, centerX, centerY);
        }

        mTextureView.setTransform(matrix);
        mTextureView.getSurfaceTexture().setDefaultBufferSize(mPreviewSize.getWidth(), mPreviewSize.getHeight());
    }

    /**
     * Shows alert dialog.
     */
    private void showAlertDialog(final Context context, String message, final boolean finishActivity) {
        final AlertDialog.Builder dialog = new AlertDialog.Builder(context, R.style.CommonAppTheme_AlertDialog);
        dialog.setMessage(message)
                .setTitle(R.string.dialog_title_error)
                .setPositiveButton(R.string.button_ok, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        dialog.dismiss();
                        if (context instanceof Activity) {
                            if (finishActivity) ((Activity) context).finish();
                        }
                    }
                }).setCancelable(false);


        ((Activity) context).runOnUiThread(() -> {
            try {
                dialog.show();
            } catch (Exception e) {
                Log.e(Constants.LOG_TAG_ERROR, "CameraHelper.showAlertDialog: " + e.getLocalizedMessage(), e);
            }
        });

    }

    /**
     * Get path for next Video file
     *
     * @return String File Name
     */
    private File createNewVideoFile() throws RuntimeException {
        if (!mVideoFilesDir.exists()) mVideoFilesDir.mkdirs();
        String timeStamp = new SimpleDateFormat("yyyyMMdd_HHmmssSSS", Locale.getDefault()).format(new Date());
        //return File.createTempFile(, mVideoFilesDir);
        return new File(mVideoFilesDir + File.separator + "temp_video_" + timeStamp + ".mp4");
    }


    /**
     * Model class for Video parameters
     */
    private static class VideoParameter {
        private Size mVideoSize;
        private Range<Integer> mFpsRange;

        VideoParameter(Size videoSize, Range<Integer> fpsRange) {
            mVideoSize = new Size(videoSize.getWidth(), videoSize.getHeight());
            mFpsRange = new Range<>(fpsRange.getLower(), fpsRange.getUpper());
            //mFpsRange = new Range<>(30, 30);
        }

        Size getVideoSize() {
            return mVideoSize;
        }

        Range<Integer> getFpsRange() {
            return mFpsRange;
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof VideoParameter &&
                    mVideoSize.equals(((VideoParameter) o).mVideoSize) &&
                    mFpsRange.equals(((VideoParameter) o).mFpsRange);
        }

        @Override
        public String toString() {
            return mVideoSize.toString() + " @ " + mFpsRange.getUpper() + "FPS";
        }
    }


}