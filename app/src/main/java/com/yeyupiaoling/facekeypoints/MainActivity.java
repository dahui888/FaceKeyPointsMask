package com.yeyupiaoling.facekeypoints;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.ImageFormat;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureFailure;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.ImageReader;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;
import android.util.Size;
import android.view.Surface;
import android.view.TextureView;
import android.widget.ImageView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;

import com.yeyupiaoling.ai.Face;
import com.yeyupiaoling.ai.FaceDetectionUtil;
import com.yeyupiaoling.ai.Utils;
import com.yeyupiaoling.facekeypoints.view.AutoFitTextureView;

import java.util.Arrays;

public class MainActivity extends AppCompatActivity {
    private static final String TAG = MainActivity.class.getName();
    private CameraCaptureSession mCaptureSession;
    private CameraDevice mCameraDevice;

    private HandlerThread mCaptureThread;
    private Handler mCaptureHandler;
    private HandlerThread mInferThread;
    private Handler mInferHandler;

    private ImageReader mImageReader;
    private boolean isFont = true;
    private Size mPreviewSize;
    private boolean mCapturing;

    private ImageView imageView;
    private AutoFitTextureView mTextureView;

    private final Object lock = new Object();
    private boolean runClassifier = false;
    private boolean isInfer = true;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        initView();
        if (!hasPermission()) {
            requestPermission();
        }

    }

    // 初始化控件
    private void initView() {
        mTextureView = findViewById(R.id.texture_view);
        imageView = findViewById(R.id.image_view);
    }

    // 预测图片线程
    private Runnable periodicClassify =
            new Runnable() {
                @Override
                public void run() {
                    synchronized (lock) {
                        if (runClassifier) {
                            // 开始预测前要判断相机是否已经准备好
                            if (getApplicationContext() != null && mCameraDevice != null && isInfer) {
                                detection();
                            }
                        }
                    }
                    if (mInferThread != null && mInferHandler != null && mCaptureHandler != null && mCaptureThread != null) {
                        mInferHandler.post(periodicClassify);
                    }
                }
            };


    // detection
    private void detection() {
        Bitmap bitmap = mTextureView.getBitmap();
        try {
            long start = System.currentTimeMillis();
            final Face[] result = FaceDetectionUtil.getInstance(MainActivity.this).predictImage(bitmap);
            long end = System.currentTimeMillis();
            Log.d(TAG, "预测时间：" + (end - start) + "ms");

            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    try {
                        if (result != null && result.length > 0) {
                            Bitmap b = Utils.drawBitmap(FaceDetectionUtil.getInstance(MainActivity.this).getBitmap(), result);
                            imageView.setImageBitmap(b);
                        }else {
                            imageView.setImageBitmap(FaceDetectionUtil.getInstance(MainActivity.this).getBitmap());
                        }
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                }
            });
        } catch (Exception e) {
            e.printStackTrace();
        }
    }


    // 初始化以下变量和状态
    private void initStatus() {
        // 启动线程
        startCaptureThread();
        startInferThread();

        // 判断SurfaceTexture是否可用，可用就直接启动捕获图片
        if (mTextureView.isAvailable()) {
            startCapture();
        } else {
            mTextureView.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
                @Override
                public void onSurfaceTextureAvailable(SurfaceTexture surface, int width, int height) {
                    startCapture();
                }

                @Override
                public void onSurfaceTextureSizeChanged(SurfaceTexture surface, int width, int height) {
                }

                @Override
                public boolean onSurfaceTextureDestroyed(SurfaceTexture surface) {
                    return true;
                }

                @Override
                public void onSurfaceTextureUpdated(SurfaceTexture surface) {
                }
            });
        }
    }

    // 启动捕获图片
    private void startCapture() {
        // 判断是否正处于捕获图片的状态
        if (mCapturing) return;
        mCapturing = true;

        final CameraManager manager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);

        String cameraIdAvailable = null;
        try {
            assert manager != null;
            for (final String cameraId : manager.getCameraIdList()) {
                final CameraCharacteristics characteristics = manager.getCameraCharacteristics(cameraId);

                final Integer facing = characteristics.get(CameraCharacteristics.LENS_FACING);
                // 设置相机前摄像头或者后摄像头
                if (isFont) {
                    if (facing != null && facing == CameraCharacteristics.LENS_FACING_FRONT) {
                        cameraIdAvailable = cameraId;
                        break;
                    }
                } else {
                    if (facing != null && facing == CameraCharacteristics.LENS_FACING_BACK) {
                        cameraIdAvailable = cameraId;
                        break;
                    }
                }
            }
        } catch (CameraAccessException e) {
            Log.e(TAG, "启动图片捕获异常 ", e);
        }

        try {
            assert cameraIdAvailable != null;
            final CameraCharacteristics characteristics =
                    manager.getCameraCharacteristics(cameraIdAvailable);

            final StreamConfigurationMap map =
                    characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            assert map != null;
            mPreviewSize = Utils.chooseOptimalSize(map.getOutputSizes(SurfaceTexture.class),
                    mTextureView.getWidth(),
                    mTextureView.getHeight());
            Log.d("mPreviewSize", String.valueOf(mPreviewSize));
            mTextureView.setAspectRatio(mPreviewSize.getHeight(), mPreviewSize.getWidth());

            // float aspectRatio = mPreviewSize.getWidth() * 1.0f / mPreviewSize.getHeight();
            // imageView.setTextureViewDimen(mTextureView.getWidth(),
            //        (int) (mTextureView.getWidth() * aspectRatio));

            manager.openCamera(cameraIdAvailable, new CameraDevice.StateCallback() {
                @Override
                public void onOpened(@NonNull CameraDevice camera) {
                    mCameraDevice = camera;
                    createCaptureSession();
                }

                @Override
                public void onDisconnected(@NonNull CameraDevice camera) {
                    camera.close();
                    mCameraDevice = null;
                    mCapturing = false;
                }

                @Override
                public void onError(@NonNull CameraDevice camera, final int error) {
                    Log.e(TAG, "打开相机错误 =  " + error);
                    camera.close();
                    mCameraDevice = null;
                    mCapturing = false;
                }
            }, mCaptureHandler);
        } catch (CameraAccessException | SecurityException e) {
            mCapturing = false;
            Log.e(TAG, "启动图片捕获异常 ", e);
        }
    }

    // 创建捕获图片session
    private void createCaptureSession() {
        try {
            final SurfaceTexture texture = mTextureView.getSurfaceTexture();
            texture.setDefaultBufferSize(mPreviewSize.getWidth(), mPreviewSize.getHeight());

            final Surface surface = new Surface(texture);
            final CaptureRequest.Builder captureRequestBuilder = mCameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            captureRequestBuilder.addTarget(surface);

            mImageReader = ImageReader.newInstance(
                    mPreviewSize.getWidth(), mPreviewSize.getHeight(), ImageFormat.JPEG, 10);

            mCameraDevice.createCaptureSession(
                    Arrays.asList(surface, mImageReader.getSurface()),
                    new CameraCaptureSession.StateCallback() {

                        @Override
                        public void onConfigured(@NonNull CameraCaptureSession cameraCaptureSession) {
                            if (null == mCameraDevice) {
                                return;
                            }

                            mCaptureSession = cameraCaptureSession;
                            try {
                                captureRequestBuilder.set(
                                        CaptureRequest.CONTROL_AF_MODE,
                                        CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
                                captureRequestBuilder.set(
                                        CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON_AUTO_FLASH);

                                CaptureRequest previewRequest = captureRequestBuilder.build();
                                mCaptureSession.setRepeatingRequest(
                                        previewRequest, new CameraCaptureSession.CaptureCallback() {
                                            @Override
                                            public void onCaptureProgressed(@NonNull CameraCaptureSession session, @NonNull CaptureRequest request, @NonNull CaptureResult partialResult) {
                                                super.onCaptureProgressed(session, request, partialResult);
                                            }

                                            @Override
                                            public void onCaptureFailed(@NonNull CameraCaptureSession session, @NonNull CaptureRequest request, @NonNull CaptureFailure failure) {
                                                super.onCaptureFailed(session, request, failure);
                                                Log.d(TAG, "onCaptureFailed = " + failure.getReason());
                                            }

                                            @Override
                                            public void onCaptureSequenceCompleted(@NonNull CameraCaptureSession session, int sequenceId, long frameNumber) {
                                                super.onCaptureSequenceCompleted(session, sequenceId, frameNumber);
                                                Log.d(TAG, "onCaptureSequenceCompleted");
                                            }
                                        }, mCaptureHandler);
                            } catch (final CameraAccessException e) {
                                Log.e(TAG, "onConfigured exception ", e);
                            }
                        }

                        @Override
                        public void onConfigureFailed(@NonNull final CameraCaptureSession cameraCaptureSession) {
                            Log.e(TAG, "onConfigureFailed ");
                        }
                    },
                    null);
        } catch (final CameraAccessException e) {
            Log.e(TAG, "创建捕获图片session异常 ", e);
        }
    }

    // 关闭相机
    private void closeCamera() {
        if (mCaptureSession != null) {
            mCaptureSession.close();
            mCaptureSession = null;
        }
        if (mCameraDevice != null) {
            mCameraDevice.close();
            mCameraDevice = null;
        }
        if (mImageReader != null) {
            mImageReader.close();
            mImageReader = null;
        }
        mCapturing = false;
    }

    // 关闭捕获图片线程
    private void stopCaptureThread() {
        try {
            if (mCaptureThread != null) {
                mCaptureThread.quitSafely();
                mCaptureThread.join();
            }
            mCaptureThread = null;
            mCaptureHandler = null;
        } catch (final Exception e) {
            e.printStackTrace();
        }
    }

    // 关闭预测线程
    private void stopInferThread() {
        try {
            if (mInferThread != null) {
                mInferThread.quitSafely();
                mInferThread.join();
            }
            mInferThread = null;
            mInferHandler = null;
            synchronized (lock) {
                runClassifier = false;
            }
        } catch (final Exception e) {
            e.printStackTrace();
        }
    }

    @Override
    protected void onPause() {
        stopInfer();
        super.onPause();
    }

    @Override
    protected void onStop() {
        stopInfer();
        super.onStop();
    }

    // 停止预测操作
    private void stopInfer() {
        // 关闭相机和线程
        closeCamera();
        stopCaptureThread();
        stopInferThread();
    }

    // 启动捕获图片线程
    private void startCaptureThread() {
        mCaptureThread = new HandlerThread("capture");
        mCaptureThread.start();
        mCaptureHandler = new Handler(mCaptureThread.getLooper());
    }

    // 启动预测线程
    private void startInferThread() {
        mInferThread = new HandlerThread("inference");
        mInferThread.start();
        mInferHandler = new Handler(mInferThread.getLooper());
        synchronized (lock) {
            runClassifier = true;
        }
        mInferHandler.post(periodicClassify);
    }

    @Override
    protected void onResume() {
        initStatus();
        super.onResume();
    }

    // check had permission
    private boolean hasPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            return checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED &&
                    checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED &&
                    checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED;
        } else {
            return true;
        }
    }

    // request permission
    private void requestPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            requestPermissions(new String[]{Manifest.permission.CAMERA,
                    Manifest.permission.READ_EXTERNAL_STORAGE,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE}, 1);
        }
    }
}