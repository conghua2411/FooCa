package com.example.fooca.cameraFragment;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.app.assist.AssistStructure;
import android.content.Context;
import android.content.DialogInterface;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.ImageFormat;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.RectF;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.TotalCaptureResult;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.ImageReader;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.AsyncTask;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;
import android.util.Size;
import android.util.SparseIntArray;
import android.view.LayoutInflater;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.androidnetworking.AndroidNetworking;
import com.androidnetworking.common.Priority;
import com.androidnetworking.error.ANError;
import com.androidnetworking.interfaces.JSONObjectRequestListener;
import com.example.fooca.R;
import com.example.fooca.ViewPagerImageAdapter.ViewPagerImageAdapter;
import com.example.fooca.customView.AutoFixTextureView;
import com.example.fooca.customView.ViewfinderView;
import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.googlecode.leptonica.android.ReadFile;
import com.googlecode.tesseract.android.TessBaseAPI;

import org.json.JSONException;
import org.json.JSONObject;
import org.jsoup.Jsoup;
import org.jsoup.select.Elements;
import org.w3c.dom.Text;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.DialogFragment;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;
import androidx.viewpager.widget.ViewPager;

public class CameraFragment extends Fragment {
    private static final String TAG = "CameraFragment";

    private static final SparseIntArray ORIENTATIONS = new SparseIntArray();
    private static final int REQUEST_CAMERA_PERMISSION = 1;
    private static final String FRAGMENT_DIALOG = "dialog";
    private static final int STORAGE_PERMISSION = 1231;
    private static final Size DESIRED_PREVIEW_SIZE = new Size(1024, 768);
    //    private static final Size DESIRED_PREVIEW_SIZE = new Size(640, 480);
    private static final int FINDER_BOX_WIDTH = 400;
    private static final int FINDER_BOX_HEIGHT = 120;
    static final int kMaxChannelValue = 262143;
    private static final String LANGUAGE_DETECT = "vie";
    private static final String DATA_PATH = Environment.getExternalStorageDirectory().toString() + "/FooCa/";
    private static final String TESS_DATA = "tessdata";

    static {
        ORIENTATIONS.append(Surface.ROTATION_0, 90);
        ORIENTATIONS.append(Surface.ROTATION_90, 0);
        ORIENTATIONS.append(Surface.ROTATION_180, 270);
        ORIENTATIONS.append(Surface.ROTATION_270, 180);
    }

    private AutoFixTextureView mTextureView;
    private ImageView imgBitmap1, imgBitmap2;
    private ViewfinderView viewfinderView;
    private TextView txtDebug;
    private ViewPager viewPagerImage;
    private ViewPagerImageAdapter adapterImage;
    private TextView mTextDetail;
    private ImageView imgDrag;
    private LinearLayout mLlBottomSheet;
    private BottomSheetBehavior<LinearLayout> mSheetBehavior;
    private boolean isPaused = false;
    private String[] storagePermission = new String[]{
            Manifest.permission.WRITE_EXTERNAL_STORAGE,
            Manifest.permission.READ_EXTERNAL_STORAGE
    };
    private HandlerThread mBackgroundThread;
    private Handler mBackgroundHandler;
    private Integer mSensorOrientation;
    private Size mPreviewSize;
    private String mCameraId;
    private Semaphore cameraOpenCloseLock = new Semaphore(1);
    private CameraDevice.StateCallback stateCallback =
            new CameraDevice.StateCallback() {
                @Override
                public void onOpened(@NonNull CameraDevice camera) {
                    Log.d(TAG, "onOpened: ");
                    cameraOpenCloseLock.release();
                    mCameraDevice = camera;
                    createCameraPreviewSession();
                }

                @Override
                public void onDisconnected(@NonNull CameraDevice camera) {
                    Log.d(TAG, "onDisconnected: ");
                    cameraOpenCloseLock.release();
                    camera.close();
                    mCameraDevice = null;
                }

                @Override
                public void onError(@NonNull CameraDevice camera, int error) {
                    Log.d(TAG, "onError: ");
                    cameraOpenCloseLock.release();
                    camera.close();
                    mCameraDevice = null;
                    final Activity activity = getActivity();
                    if (null != activity) {
                        activity.finish();
                    }
                }
            };
    private CaptureRequest.Builder previewRequestBuilder;

    private boolean isDetecting = false;
    private boolean isProcessingImage = false;
    private TessBaseAPI baseAPI;
    private CameraCaptureSession mCaptureSession;
    private CaptureRequest previewRequest;
    private CameraCaptureSession.CaptureCallback mCaptureCallback =
            new CameraCaptureSession.CaptureCallback() {
                @Override
                public void onCaptureProgressed(
                        @NonNull CameraCaptureSession session,
                        @NonNull CaptureRequest request,
                        @NonNull CaptureResult partialResult) {
                    Log.d(TAG, "onCaptureProgressed: ");
                }

                @Override
                public void onCaptureCompleted(
                        @NonNull CameraCaptureSession session,
                        @NonNull CaptureRequest request,
                        @NonNull TotalCaptureResult result) {
                    Log.d(TAG, "onCaptureCompleted: ");
                }
            };
    private TextureView.SurfaceTextureListener mSurfaceTextureListener =
            new TextureView.SurfaceTextureListener() {
                @Override
                public void onSurfaceTextureAvailable(SurfaceTexture surface, int width, int height) {
                    Log.d(TAG, "onSurfaceTextureAvailable: ");
                    openCamera(width, height);
                }

                @Override
                public void onSurfaceTextureSizeChanged(SurfaceTexture surface, int width, int height) {
                    Log.d(TAG, "onSurfaceTextureSizeChanged: " + width + " -- " + height);
                    configureTransform(width, height);
                }

                @Override
                public boolean onSurfaceTextureDestroyed(SurfaceTexture surface) {
                    Log.d(TAG, "onSurfaceTextureDestroyed: ");
                    return false;
                }

                @Override
                public void onSurfaceTextureUpdated(SurfaceTexture surface) {
                    Log.d(TAG, "onSurfaceTextureUpdated: ");

                }
            };

    public static CameraFragment newInstance() {
        return new CameraFragment();
    }

    private void convertYUV420ToARGB8888(
            byte[] yData,
            byte[] uData,
            byte[] vData,
            int width,
            int height,
            int yRowStride,
            int uvRowStride,
            int uvPixelStride,
            int[] out) {
        int yp = 0;
        for (int j = 0; j < height; j++) {
            int pY = yRowStride * j;
            int pUV = uvRowStride * (j >> 1);

            for (int i = 0; i < width; i++) {
                int uv_offset = pUV + (i >> 1) * uvPixelStride;

                out[yp++] = YUV2RGB(
                        0xff & yData[pY + i],
                        0xff & uData[uv_offset],
                        0xff & vData[uv_offset]);
            }
        }
    }

    private int YUV2RGB(int y, int u, int v) {
        // Adjust and check YUV values
        y = (y - 16) < 0 ? 0 : (y - 16);
        u -= 128;
        v -= 128;

        // This is the floating point equivalent. We do the conversion in integer
        // because some Android devices do not have floating point in hardware.
        // nR = (int)(1.164 * nY + 2.018 * nU);
        // nG = (int)(1.164 * nY - 0.813 * nV - 0.391 * nU);
        // nB = (int)(1.164 * nY + 1.596 * nV);
        int y1192 = 1192 * y;
        int r = (y1192 + 1634 * v);
        int g = (y1192 - 833 * v - 400 * u);
        int b = (y1192 + 2066 * u);

        // Clipping RGB values to be inside boundaries [ 0 , kMaxChannelValue ]
        r = r > kMaxChannelValue ? kMaxChannelValue : (r < 0 ? 0 : r);
        g = g > kMaxChannelValue ? kMaxChannelValue : (g < 0 ? 0 : g);
        b = b > kMaxChannelValue ? kMaxChannelValue : (b < 0 ? 0 : b);

        // grayScale
        int grayScale = (int) (0.3 * r + 0.59 * g + 0.11 * b);

        r = grayScale;
        g = grayScale;
        b = grayScale;

        return 0xff000000 | ((r << 6) & 0xff0000) | ((g >> 2) & 0xff00) | ((b >> 10) & 0xff);
    }

    private void fillBytes(Image.Plane[] planes, byte[][] yuvBytes) {
        for (int i = 0; i < planes.length; ++i) {
            final ByteBuffer buffer = planes[i].getBuffer();
            if (yuvBytes[i] == null) {
                yuvBytes[i] = new byte[buffer.capacity()];
            }
            buffer.get(yuvBytes[i]);
        }
    }

    private void createCameraPreviewSession() {
        Log.d(TAG, "createCameraPreviewSession: ");
        try {
            final SurfaceTexture texture = mTextureView.getSurfaceTexture();
            assert texture != null;

            texture.setDefaultBufferSize(mPreviewSize.getWidth(), mPreviewSize.getHeight());

            final Surface surface = new Surface(texture);

            previewRequestBuilder = mCameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            previewRequestBuilder.addTarget(surface);

            mCameraDevice.createCaptureSession(
                    Arrays.asList(surface),
                    new CameraCaptureSession.StateCallback() {
                        @Override
                        public void onConfigured(@NonNull CameraCaptureSession session) {
                            Log.d(TAG, "onConfigured: mCameraDevice");

                            if (null == mCameraDevice) {
                                return;
                            }

                            mCaptureSession = session;

                            try {
                                previewRequestBuilder.set(
                                        CaptureRequest.CONTROL_AF_MODE,
                                        CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE
                                );

                                previewRequest = previewRequestBuilder.build();
                                mCaptureSession.setRepeatingRequest(
                                        previewRequest,
                                        mCaptureCallback,
                                        mBackgroundHandler
                                );
                            } catch (CameraAccessException e) {
                                Log.d(TAG, "onConfigured: CameraAccessException");
                                e.printStackTrace();
                            }
                        }

                        @Override
                        public void onConfigureFailed(@NonNull CameraCaptureSession session) {
                            Log.d(TAG, "onConfigureFailed: mCameraDevice");
                        }
                    },
                    null
            );

        } catch (CameraAccessException e) {
            Log.d(TAG, "createCameraPreviewSession: CameraAccessException");
            e.printStackTrace();
        }
    }

    private CameraDevice mCameraDevice;

    private Object lock = new Object();

    private Runnable periodDetect = new Runnable() {
        @Override
        public void run() {
            Log.d("textureViewBitmap", "run: isDetecting : " + isDetecting + ", isProcessingImage : " + isProcessingImage + ", isPaused : " + isPaused);
            synchronized (lock) {
                if (isDetecting && !isProcessingImage && !isPaused) {
                    detectText();
                }
            }

            mBackgroundHandler2.postDelayed(periodDetect, 200);
        }
    };

    private void detectText() {

        if (getActivity() == null || mCameraDevice == null || mPreviewSize == null || mTextureView == null) {
            return;
        }

        isProcessingImage = true;

        int resizeWidth = (mTextureView.getWidth() * 3) / 5;
        int resizeHeight = (mTextureView.getHeight() * 3) / 5;

        int finderWidth = resizeWidth*3/5;
        int finderHeight = resizeHeight/10;

        Bitmap bitmap = mTextureView.getBitmap(resizeWidth, resizeHeight);

        Bitmap bmResize = Bitmap.createBitmap(bitmap, (resizeWidth-finderWidth)/2, resizeHeight/4-finderHeight/2, finderWidth, finderHeight);

        Bitmap bmGrayScale = Bitmap.createBitmap(bmResize.getWidth(), bmResize.getHeight(), Bitmap.Config.ARGB_8888);

        Canvas canvas = new Canvas(bmGrayScale);

        Paint paint = new Paint();

        ColorMatrix cm = new ColorMatrix();

        cm.setSaturation(0);

        ColorMatrixColorFilter f = new ColorMatrixColorFilter(cm);

        paint.setColorFilter(f);

        canvas.drawBitmap(bmResize, 0, 0, paint);

        Log.d("textureViewBitmap", "detectText: mTextureView : " + resizeWidth + " mTextureView : " + resizeHeight);

        baseAPI.setImage(ReadFile.readBitmap(bmGrayScale));

        onImageSearch(baseAPI.getUTF8Text());

        getActivity().runOnUiThread(new Runnable() {
            @Override
            public void run() {
                txtDebug.setText(baseAPI.getUTF8Text());
            }
        });

        isProcessingImage = false;

        bitmap.recycle();
        bmResize.recycle();
        bmGrayScale.recycle();
    }


    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.camera_fragment, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {

        AndroidNetworking.initialize(getActivity().getApplicationContext());

        mTextureView = view.findViewById(R.id.texture);

        imgBitmap1 = view.findViewById(R.id.imgBitmap1);
        imgBitmap2 = view.findViewById(R.id.imgBitmap2);

        viewfinderView = view.findViewById(R.id.viewfinderView);

        //debug
        txtDebug = view.findViewById(R.id.txtDebug);

        viewPagerImage = view.findViewById(R.id.viewpager_image_search);
        adapterImage = new ViewPagerImageAdapter(this.getActivity(), new ArrayList<String>());
        viewPagerImage.setAdapter(adapterImage);

        mTextDetail = view.findViewById(R.id.textDetail);
        imgDrag = view.findViewById(R.id.imgDrag);

        //setup bottom sheet
        mLlBottomSheet = view.findViewById(R.id.llBottomSheet);
        mSheetBehavior = BottomSheetBehavior.from(mLlBottomSheet);

        mLlBottomSheet.setBackgroundColor(Color.parseColor("#00ffffff"));

        view.findViewById(R.id.llBottomContent).setBackgroundColor(Color.parseColor("#00ffffff"));

        view.findViewById(R.id.rlTextSession).setBackgroundColor(Color.WHITE);

        view.findViewById(R.id.viewpager_image_search).setBackgroundColor(Color.WHITE);

        mSheetBehavior.setBottomSheetCallback(new BottomSheetBehavior.BottomSheetCallback() {
            @Override
            public void onStateChanged(@NonNull View view, int newState) {
                switch (newState) {
                    case BottomSheetBehavior.STATE_HIDDEN:
                        Log.d(TAG, "onStateChanged: STATE_HIDDEN");
                        break;
                    case BottomSheetBehavior.STATE_COLLAPSED:
                        Log.d(TAG, "onStateChanged: STATE_COLLAPSED");
                        imgDrag.setRotation(90);
                        isPaused = false;
                        break;
                    case BottomSheetBehavior.STATE_EXPANDED:
                        imgDrag.setRotation(270);
                        imgDrag.setVisibility(View.GONE);
                        isPaused = true;
                        Log.d(TAG, "onStateChanged: STATE_EXPANDED");
                        break;
                    case BottomSheetBehavior.STATE_DRAGGING:
                        imgDrag.setVisibility(View.VISIBLE);
                        Log.d(TAG, "onStateChanged: STATE_DRAGGING");
                        break;
                    case BottomSheetBehavior.STATE_SETTLING:
                        Log.d(TAG, "onStateChanged: STATE_SETTLING");
                        break;
                }
            }

            @Override
            public void onSlide(@NonNull View view, float slideOffset) {

            }
        });

        if (checkPermisionStorage()) {
            initOcr();
        }
    }

    @Override
    public void onResume() {
        super.onResume();

        startBackgroundThread();

        if (mTextureView.isAvailable()) {
            Log.d(TAG, "onResume: openCamera");
            openCamera(mTextureView.getWidth(), mTextureView.getHeight());
        } else {
            Log.d(TAG, "onResume: setSurfaceTextureListener");
            mTextureView.setSurfaceTextureListener(mSurfaceTextureListener);
        }
    }

    @Override
    public void onPause() {
        closeCamera();
        stopBackgroundThread();
        super.onPause();
    }

    private void closeCamera() {
        try {
            cameraOpenCloseLock.acquire();
            if (null != mCaptureSession) {
                mCaptureSession.close();
                mCaptureSession = null;
            }
            if (null != mCameraDevice) {
                mCameraDevice.close();
                mCameraDevice = null;
            }
        } catch (InterruptedException e) {
            throw new RuntimeException("Interrupted while trying to lock camera closing.", e);
        } finally {
            cameraOpenCloseLock.release();
        }
    }

    private void stopBackgroundThread() {
        mBackgroundThread.quitSafely();
        mBackgroundThread2.quitSafely();

        try {
            mBackgroundThread.join();
            mBackgroundThread = null;
            mBackgroundHandler = null;

            mBackgroundThread2.join();
            mBackgroundThread2 = null;
            mBackgroundHandler2 = null;

        } catch (InterruptedException e) {
            Log.e(TAG, "Interrupted when stopping background thread", e);
        }
    }

    private void openCamera(int width, int height) {
        Log.d(TAG, "openCamera: " + width + " -- " + height);
        if (ContextCompat.checkSelfPermission(getActivity(), Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED) {
            requestCameraPermission();
            return;
        }

        setupCameraOutput();
        configureTransform(width, height);
        final Activity activity = getActivity();
        final CameraManager cameraManager = (CameraManager) activity.getSystemService(Context.CAMERA_SERVICE);

        try {
            if (!cameraOpenCloseLock.tryAcquire(2500, TimeUnit.MILLISECONDS)) {
                throw new RuntimeException("Time out waiting to lock camera opening.");
            }

            Log.d(TAG, "openCamera: manager.openCamera");
            cameraManager.openCamera(mCameraId, stateCallback, mBackgroundHandler);
        } catch (InterruptedException e) {
            Log.d(TAG, "openCamera: ");
            e.printStackTrace();
        } catch (CameraAccessException e) {
            Log.d(TAG, "openCamera: 2");
            e.printStackTrace();
        }
    }

    private void requestCameraPermission() {
        Log.d(TAG, "requestCameraPermission: ");
        if (shouldShowRequestPermissionRationale(Manifest.permission.CAMERA)) {
            new ConfirmationDialog().show(getChildFragmentManager(), FRAGMENT_DIALOG);
        } else {
            requestPermissions(new String[]{Manifest.permission.CAMERA}, REQUEST_CAMERA_PERMISSION);
        }
    }

    private void configureTransform(int width, int height) {

        Log.d(TAG, "configureTransform: ");
        final Activity activity = getActivity();
        if (null == mTextureView || null == mPreviewSize || null == activity)
            return;

        final int rotation = activity.getWindowManager().getDefaultDisplay().getRotation();
        final Matrix matrix = new Matrix();
        final RectF viewRect = new RectF(0, 0, width, height);
        final RectF bufferRect = new RectF(0, 0, mPreviewSize.getWidth(), mPreviewSize.getHeight());
        final float centerX = viewRect.centerX();
        final float centerY = viewRect.centerY();
        if (Surface.ROTATION_90 == rotation || Surface.ROTATION_270 == rotation) {
            bufferRect.offset(centerX - bufferRect.centerX(), centerY - bufferRect.centerY());
            matrix.setRectToRect(viewRect, bufferRect, Matrix.ScaleToFit.FILL);
            final float scale =
                    Math.max(
                            (float) height / mPreviewSize.getHeight(),
                            (float) width / mPreviewSize.getWidth());
            matrix.postScale(scale, scale, centerX, centerY);
            matrix.postRotate(90 * (rotation - 2), centerX, centerY);
        } else if (Surface.ROTATION_180 == rotation) {
            matrix.postRotate(180, centerX, centerY);
        }
        mTextureView.setTransform(matrix);


        //nexus
        viewfinderView.setTextureRect(new RectF(0, 0, mTextureView.getHeight(), mTextureView.getWidth()));
        viewfinderView.requestLayout();
    }

    private void setupCameraOutput() {
        Log.d(TAG, "setupCameraOutput: ");
        final Activity activity = getActivity();
        final CameraManager cameraManager = (CameraManager) activity.getSystemService(Context.CAMERA_SERVICE);

        try {
            for (String cameraId : cameraManager.getCameraIdList()) {
                CameraCharacteristics cameraCharacteristics = cameraManager.getCameraCharacteristics(cameraId);

                Integer facing = cameraCharacteristics.get(CameraCharacteristics.LENS_FACING);

                // get back camera
                if (facing != null && facing == CameraCharacteristics.LENS_FACING_FRONT) {
                    continue;
                }

                StreamConfigurationMap map = cameraCharacteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);

                if (map == null) {
                    continue;
                }

                mSensorOrientation = cameraCharacteristics.get(CameraCharacteristics.SENSOR_ORIENTATION);

                mPreviewSize =
                        chooseOptimalSize(map.getOutputSizes(SurfaceTexture.class),
                                DESIRED_PREVIEW_SIZE.getWidth(),
                                DESIRED_PREVIEW_SIZE.getHeight());


                viewfinderView.setPreviewRect(
                        new RectF(
                                0,
                                0,
                                mPreviewSize.getWidth(),
                                mPreviewSize.getHeight()
                        )
                );
                viewfinderView.setFinderRect(
                        new RectF(
                                (mPreviewSize.getWidth() - FINDER_BOX_WIDTH) / 2,
                                mPreviewSize.getHeight() / 4 - FINDER_BOX_HEIGHT / 2,
                                (mPreviewSize.getWidth() - FINDER_BOX_WIDTH) / 2 + FINDER_BOX_WIDTH,
                                mPreviewSize.getHeight() / 4 - FINDER_BOX_HEIGHT / 2 + FINDER_BOX_HEIGHT
                        )
                );

                viewfinderView.requestLayout();

                final int orientation = getResources().getConfiguration().orientation;
                if (orientation == Configuration.ORIENTATION_LANDSCAPE) {
                    mTextureView.setAspectRatio(mPreviewSize.getWidth(), mPreviewSize.getHeight());
                } else {
                    mTextureView.setAspectRatio(mPreviewSize.getHeight(), mPreviewSize.getWidth());
                }

                mCameraId = cameraId;
            }
        } catch (CameraAccessException e) {
            Log.d(TAG, "setupCameraOutput: ");
            e.printStackTrace();
        } catch (NullPointerException e) {
            Log.d(TAG, "setupCameraOutput: 2");
            e.printStackTrace();
        }
    }

    protected static Size chooseOptimalSize(
            final Size[] choices,
            final int width,
            final int height) {
        Log.d(TAG, "chooseOptimalSize: ");
        final int minSize = Math.max(Math.min(width, height), 320);
        final Size desiredSize = new Size(width, height);

        // Collect the supported resolutions that are at least as big as the preview Surface
        boolean exactSizeFound = false;
        final List<Size> bigEnough = new ArrayList<Size>();
        final List<Size> tooSmall = new ArrayList<Size>();
        for (final Size option : choices) {
            if (option.equals(desiredSize)) {
                // Set the size but don't return yet so that remaining sizes will still be logged.
                exactSizeFound = true;
            }

            if (option.getHeight() >= minSize && option.getWidth() >= minSize) {
                bigEnough.add(option);
            } else {
                tooSmall.add(option);
            }
        }

        if (exactSizeFound) {
            return desiredSize;
        }

        // Pick the smallest of those, assuming we found any
        if (bigEnough.size() > 0) {
            final Size chosenSize = Collections.min(bigEnough, new CompareSizesByArea());
            return chosenSize;
        } else {
            return choices[0];
        }
    }

    private HandlerThread mBackgroundThread2;
    private Handler mBackgroundHandler2;

    private void startBackgroundThread() {
        Log.d(TAG, "startBackgroundThread: ");
        mBackgroundThread = new HandlerThread("Camera2Preview");
        mBackgroundThread.start();
        mBackgroundHandler = new Handler(mBackgroundThread.getLooper());

        //test
        mBackgroundThread2 = new HandlerThread("testbackgroundThread");
        mBackgroundThread2.start();
        mBackgroundHandler2 = new Handler(mBackgroundThread2.getLooper());
//        synchronized (lock) {
//
//        }
        mBackgroundHandler2.postDelayed(periodDetect, 200);

    }

    private boolean checkPermisionStorage() {
        int result;
        List<String> listPermissionsNeeded = new ArrayList<>();
        for (String p : storagePermission) {
            result = ContextCompat.checkSelfPermission(getActivity(), p);
            if (result != PackageManager.PERMISSION_GRANTED) {
                listPermissionsNeeded.add(p);
            }
        }
        if (!listPermissionsNeeded.isEmpty()) {
            ActivityCompat.requestPermissions(
                    this.getActivity(),
                    listPermissionsNeeded.toArray(new String[listPermissionsNeeded.size()]),
                    STORAGE_PERMISSION);
            return false;
        }
        return true;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        Log.d(TAG, "onRequestPermissionsResult: ");
        if (requestCode == REQUEST_CAMERA_PERMISSION) {
            if (grantResults.length != 1 || grantResults[0] != PackageManager.PERMISSION_GRANTED) {
                ErrorDialog.newInstance(getString(R.string.request_permission))
                        .show(getChildFragmentManager(), FRAGMENT_DIALOG);
            }
        } else if (requestCode == STORAGE_PERMISSION) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                initOcr();
            } else {
                Log.e(TAG, "onRequestPermissionsResult: failed permission storage");
            }
        } else {
            super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        }
    }

    private void initOcr() {
        baseAPI = new TessBaseAPI();

        new OrcInitAsyncTask(getActivity(), baseAPI).execute(DATA_PATH);
    }

    static class CompareSizesByArea implements Comparator<Size> {
        @Override
        public int compare(final Size lhs, final Size rhs) {
            // We cast here to ensure the multiplications won't overflow
            return Long.signum(
                    (long) lhs.getWidth() * lhs.getHeight() - (long) rhs.getWidth() * rhs.getHeight());
        }
    }

    public static class ErrorDialog extends DialogFragment {

        private static final String ARG_MESSAGE = "message";

        public static CameraFragment.ErrorDialog newInstance(String message) {
            Log.d(TAG, "newInstance: ErrorDialog");
            CameraFragment.ErrorDialog dialog = new CameraFragment.ErrorDialog();
            Bundle args = new Bundle();
            args.putString(ARG_MESSAGE, message);
            dialog.setArguments(args);
            return dialog;
        }

        @NonNull
        @Override
        public Dialog onCreateDialog(Bundle savedInstanceState) {
            Log.d(TAG, "onCreateDialog: ErrorDialog");
            final Activity activity = getActivity();
            return new AlertDialog.Builder(activity)
                    .setMessage(getArguments().getString(ARG_MESSAGE))
                    .setPositiveButton(android.R.string.ok, new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialogInterface, int i) {
                            activity.finish();
                        }
                    })
                    .create();
        }

    }

    public static class ConfirmationDialog extends DialogFragment {
        @NonNull
        @Override
        public Dialog onCreateDialog(@Nullable Bundle savedInstanceState) {
            final Fragment parent = getParentFragment();
            return new AlertDialog.Builder(getActivity())
                    .setMessage(R.string.request_permission)
                    .setPositiveButton(
                            android.R.string.ok,
                            new DialogInterface.OnClickListener() {
                                @Override
                                public void onClick(DialogInterface dialog, int which) {
                                    parent.requestPermissions(
                                            new String[]{Manifest.permission.CAMERA},
                                            REQUEST_CAMERA_PERMISSION);
                                }
                            })
                    .setNegativeButton(
                            android.R.string.cancel,
                            new DialogInterface.OnClickListener() {
                                @Override
                                public void onClick(DialogInterface dialog, int which) {
                                    Activity activity = parent.getActivity();
                                    if (activity != null) {
                                        activity.finish();
                                    }
                                }
                            })
                    .create();
        }
    }

    private class OrcDetectAsyncTask extends AsyncTask<Bitmap, String, String> {
        @Override
        protected String doInBackground(Bitmap... bitmaps) {

            Log.d("OrcDetectAsyncTask", "doInBackground: OrcDetectAsyncTask");
            baseAPI.setImage(ReadFile.readBitmap(bitmaps[0]));
            return baseAPI.getUTF8Text();
        }

        @Override
        protected void onPostExecute(String result) {
            super.onPostExecute(result);
            onImageSearch(result);
            isProcessingImage = false;
            Log.d("OrcDetectAsyncTask", "onPostExecute: " + result);
            txtDebug.setText(result);
        }
    }

    private String previousText = "";

    private void onImageSearch(final String text) {
        if (text.isEmpty() || isContainSpecialCharacter(text) || previousText.equalsIgnoreCase(text)) {
            return;
        }

        previousText = text;

        final List<String> listImgBase64 = new ArrayList<>();

        if (isNetworkAvailable()) {
            new AsyncTask<Void, Void, Void>() {
                @Override
                protected Void doInBackground(Void... voids) {
                    try {
                        // url get wiki definition
                        String urlWiki = String.format("https://vi.wikipedia.org/w/api.php?action=query&format=json&titles=%s&prop=extracts&exintro&explaintext", text);

                        Log.d("getWiki", "onImageSearchURL: " + urlWiki);

                        // get wiki
                        AndroidNetworking.get(urlWiki)
                                .setTag("wikiget")
                                .setPriority(Priority.HIGH)
                                .build()
                                .getAsJSONObject(new JSONObjectRequestListener() {
                                    @Override
                                    public void onResponse(JSONObject response) {
                                        Log.d("getWiki", "onResponse: " + response.toString());

                                        try {
                                            if (!isPaused) {
                                                String content = response.getJSONObject("query").getString("pages");

                                                if (content.contains("extract")) {
                                                    mTextDetail.setText(String.format(getString(R.string.text_search_result), text, content.substring(content.indexOf("\"extract\":\"") + 11, content.indexOf("\"}}"))));
                                                } else {
                                                    mTextDetail.setText(String.format(getString(R.string.text_search_result), text, "Not found definition"));

                                                }
                                            }

                                        } catch (JSONException e) {
                                            e.printStackTrace();
                                        }

                                    }

                                    @Override
                                    public void onError(ANError anError) {
                                        Log.d("getWiki", "onError: " + anError.getErrorBody());
                                    }
                                });


                        org.jsoup.nodes.Document doc = Jsoup.connect("https://www.google.com/search?safe=active&tbm=isch&q=" + "món " + text).userAgent("Mozilla/5.0 (X11; Linux i686) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/35.0.1916.114 Safari/537.36").get();

                        Elements imgs = doc.select("#rg div.rg_di .rg_meta");

                        String imageString;

                        if (!isPaused) {
                            for (org.jsoup.nodes.Element img : imgs) {

                                imageString = img.toString();

                                Log.d("test_image", "doInBackground:" + imageString.substring(imageString.indexOf("\"ou\":\"") + 6, imageString.indexOf("\",\"ow\"")));

                                listImgBase64.add(imageString.substring(imageString.indexOf("\"ou\":\"") + 6, imageString.indexOf("\",\"ow\"")));
                            }
                        }

                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                    return null;
                }

                @Override
                protected void onPostExecute(Void aVoid) {
                    super.onPostExecute(aVoid);
                    if (!isPaused) {
                        adapterImage.addAll(listImgBase64);
                        viewPagerImage.setCurrentItem(0);

                        if (mLlBottomSheet.getVisibility() == View.GONE) {
                            mLlBottomSheet.setVisibility(View.VISIBLE);
                        }
                    }
                }
            }.execute();
        } else {
            Toast.makeText(getActivity(), "cannot connect to the internet", Toast.LENGTH_SHORT).show();
        }
    }

    private Boolean isNetworkAvailable() {
        ConnectivityManager connectivityManager = (ConnectivityManager) getActivity().getSystemService(Context.CONNECTIVITY_SERVICE);
        NetworkInfo activeNetworkInfo = connectivityManager.getActiveNetworkInfo();
        return activeNetworkInfo != null && activeNetworkInfo.isConnectedOrConnecting();
    }

    private boolean isContainSpecialCharacter(String text) {
        for (char c : text.toCharArray()) {
            if ((c > 32 && c < 48) || (c > 57 && c < 65) || (c > 90 && c < 97) || (c > 123 && c < 127)) {
                return true;
            }
        }
        return false;
    }

    private class OrcInitAsyncTask extends AsyncTask<String, String, Boolean> {

        private TessBaseAPI baseAPI;
        private AlertDialog dialog;
        private FragmentActivity activity;

        public OrcInitAsyncTask(FragmentActivity activity, TessBaseAPI baseAPI) {
            Log.d(TAG, "OrcInitAsyncTask:");

            this.baseAPI = baseAPI;
            this.activity = activity;
        }

        private void prepareDirection() {
            File dir = new File(DATA_PATH + TESS_DATA);

            if (!dir.exists()) {
                if (!dir.mkdirs()) {
                    Log.e("prepareDirection", "Cannot create folder");
                }
            } else {
                Log.i("prepareDirection", "Created folder: ");
            }
        }

        private void copyTessDataFile(String path) {
            try {
                String fileList[] = getActivity().getAssets().list(path);

                for (String fileName : fileList) {
                    String pathToDataFile = DATA_PATH + path + "/" + fileName;
                    if (!(new File(pathToDataFile).exists())) {
                        InputStream in = getActivity().getAssets().open(path + "/" + fileName);

                        OutputStream out = new FileOutputStream(pathToDataFile);

                        byte[] buf = new byte[1024];
                        int len;

                        while ((len = in.read(buf)) > 0) {
                            out.write(buf, 0, len);
                        }
                        in.close();
                        out.close();

                        Log.d(TAG, "copyTessDataFile: copied " + fileName);
                    }
                }

            } catch (IOException e) {
                e.printStackTrace();
                Log.e(TAG, "copyTessDataFile: unable to copy file!");
            }
        }

        @Override
        protected void onPreExecute() {
            Log.d(TAG, "onPreExecute:");
            super.onPreExecute();
            dialog = new AlertDialog.Builder(activity)
                    .setCancelable(false)
                    .setTitle("init orc")
                    .setMessage("check file tessdata")
                    .create();

            dialog.show();
        }

        @Override
        protected Boolean doInBackground(String... params) {
            try {
                prepareDirection();

                copyTessDataFile(TESS_DATA);

                baseAPI.init(DATA_PATH, LANGUAGE_DETECT);

                Log.d(TAG, "doInBackground: init tessdata");

                return true;
            } catch (Exception e) {
                return false;
            }
        }

        @Override
        protected void onPostExecute(Boolean result) {
            super.onPostExecute(result);

            dialog.dismiss();

            if (!result) {
                Log.d(TAG, "onPostExecute: error init ocr");
            } else {
                isDetecting = true;
            }
        }
    }
}
