package com.pov.tiktok;

import android.Manifest;
import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Matrix;
import android.graphics.RectF;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.MediaRecorder;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.util.Log;
import android.util.Size;
import android.view.Surface;
import android.view.TextureView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.google.android.material.button.MaterialButton;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "POVTikTok";
    private static final int REQ_CAMERA_MIC = 42;
    private static final int REQ_BT = 43;

    private TextureView previewView;
    private TextView subtitle;
    private TextView statusView;
    private MaterialButton connectBtn;
    private MaterialButton trainBtn;
    private MaterialButton recordBtn;

    private CameraManager cameraManager;
    private String cameraId;
    private CameraDevice cameraDevice;
    private CameraCaptureSession captureSession;
    private CaptureRequest.Builder requestBuilder;
    private Size videoSize;
    private Size previewSize;
    private Integer sensorOrientation = 0;
    private HandlerThread cameraThread;
    private Handler cameraHandler;

    private EegPlaybackService playback;
    private boolean playbackBound;
    private BluetoothDevice pendingDevice;
    private boolean eegConnected;
    private boolean mapReady;
    private boolean isRecording;
    private boolean isTraining;
    private String currentSubtitle = "";

    private MediaProjectionManager projectionManager;
    private ActivityResultLauncher<Intent> projectionLauncher;

    private final Handler uiHandler = new Handler(Looper.getMainLooper());

    private final ServiceConnection playbackConn = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            playback = ((EegPlaybackService.LocalBinder) service).getService();
            playback.setListener(playbackListener);
            playbackBound = true;
            mapReady      = playback.isMapReady();
            eegConnected  = playback.isConnected();
            currentSubtitle = playback.currentSubtitle();
            connectBtn.setText(eegConnected ? R.string.disconnect : R.string.connect_eeg);
            if (eegConnected) {
                statusView.setText("EEG: connected");
            } else if (pendingDevice == null) {
                statusView.setText(R.string.status_idle);
            }
            if (!currentSubtitle.isEmpty()) subtitle.setText(currentSubtitle);
            playback.setPlaybackActive(isTraining || isRecording);
            if (pendingDevice != null) {
                playback.connect(pendingDevice);
                pendingDevice = null;
            }
        }
        @Override
        public void onServiceDisconnected(ComponentName name) {
            playback = null;
            playbackBound = false;
        }
    };

    private final EegPlaybackService.StateListener playbackListener =
            new EegPlaybackService.StateListener() {
                @Override
                public void onConnectionChanged(boolean connected, String reason) {
                    uiHandler.post(() -> {
                        eegConnected = connected;
                        if (connected) {
                            statusView.setText("EEG: connected");
                            connectBtn.setText(R.string.disconnect);
                        } else {
                            statusView.setText(R.string.status_idle);
                            connectBtn.setText(R.string.connect_eeg);
                            subtitle.setText("");
                            currentSubtitle = "";
                            if (isTraining) stopTraining();
                            if (isRecording) stopRecording();
                        }
                    });
                }
                @Override
                public void onSignal(int level) {
                    uiHandler.post(() -> statusView.setText("signal " + level));
                }
                @Override
                public void onSubtitle(String word) {
                    uiHandler.post(() -> {
                        currentSubtitle = word;
                        subtitle.setText(word);
                    });
                }
                @Override
                public void onMapReady(boolean ok) {
                    uiHandler.post(() -> {
                        mapReady = ok;
                        if (!ok) Toast.makeText(MainActivity.this,
                                "EEG map empty", Toast.LENGTH_LONG).show();
                    });
                }
            };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        previewView = findViewById(R.id.cameraPreview);
        subtitle    = findViewById(R.id.subtitle);
        statusView  = findViewById(R.id.statusView);
        connectBtn  = findViewById(R.id.connectBtn);
        trainBtn    = findViewById(R.id.trainBtn);
        recordBtn   = findViewById(R.id.recordBtn);

        MaterialButton recorderBtn = findViewById(R.id.recorderBtn);
        MaterialButton terminalBtn = findViewById(R.id.terminalBtn);
        recorderBtn.setOnClickListener(v ->
                startActivity(new Intent(this, RecorderActivity.class)));
        terminalBtn.setOnClickListener(v ->
                startActivity(new Intent(this, TranslatorActivity.class)));

        cameraManager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
        previewView.setSurfaceTextureListener(textureListener);

        projectionManager = (MediaProjectionManager)
                getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        projectionLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                        beginRecording(result.getResultCode(), result.getData());
                    } else {
                        Toast.makeText(this, "Screen capture not granted",
                                Toast.LENGTH_SHORT).show();
                    }
                });

        connectBtn.setOnClickListener(v -> toggleEeg());
        trainBtn.setOnClickListener(v -> {
            if (isTraining) {
                stopTraining();
            } else if (!mapReady) {
                Toast.makeText(this, "EEG map still loading…",
                        Toast.LENGTH_SHORT).show();
            } else if (!eegConnected) {
                Toast.makeText(this, "Connect EEG headset first",
                        Toast.LENGTH_SHORT).show();
                toggleEeg();
            } else {
                startTraining();
            }
        });
        recordBtn.setOnClickListener(v -> {
            if (isRecording) {
                stopRecording();
            } else if (!mapReady) {
                Toast.makeText(this, "EEG map still loading…",
                        Toast.LENGTH_SHORT).show();
            } else if (!eegConnected) {
                Toast.makeText(this, "Connect EEG headset first",
                        Toast.LENGTH_SHORT).show();
                toggleEeg();
            } else {
                requestRecording();
            }
        });
    }

    @Override
    protected void onStart() {
        super.onStart();
        bindService(new Intent(this, EegPlaybackService.class),
                playbackConn, Context.BIND_AUTO_CREATE);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (cameraThread == null) startCameraThread();
        if (cameraDevice == null) {
            if (previewView.isAvailable()) {
                openCamera(previewView.getWidth(), previewView.getHeight());
            } else {
                previewView.setSurfaceTextureListener(textureListener);
            }
        }
    }

    @Override
    protected void onStop() {
        // Camera goes with the activity, EEG + audio do not.
        closeCamera();
        stopCameraThread();
        if (playbackBound) {
            if (playback != null) playback.clearListener();
            unbindService(playbackConn);
            playbackBound = false;
            playback = null;
        }
        super.onStop();
    }

    @Override
    public void onConfigurationChanged(@NonNull Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        configureTransform(previewView.getWidth(), previewView.getHeight());
    }

    // ---------- EEG ----------

    private void toggleEeg() {
        if (eegConnected) {
            if (playback != null) playback.disconnect();
            return;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)
                    != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this,
                        new String[]{Manifest.permission.BLUETOOTH_CONNECT}, REQ_BT);
                return;
            }
        }
        showDevicePicker();
    }

    @SuppressLint("MissingPermission")
    private void showDevicePicker() {
        BluetoothManager bm = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
        BluetoothAdapter adapter = bm != null ? bm.getAdapter() : null;
        if (adapter == null) {
            Toast.makeText(this, "Bluetooth not supported", Toast.LENGTH_LONG).show();
            return;
        }
        if (!adapter.isEnabled()) {
            Toast.makeText(this, "Enable Bluetooth first", Toast.LENGTH_LONG).show();
            return;
        }
        Set<BluetoothDevice> bonded = adapter.getBondedDevices();
        if (bonded == null || bonded.isEmpty()) {
            new AlertDialog.Builder(this)
                    .setTitle(R.string.pick_device)
                    .setMessage(R.string.no_paired)
                    .setPositiveButton("OK", null)
                    .show();
            return;
        }
        final BluetoothDevice[] devices = bonded.toArray(new BluetoothDevice[0]);
        String[] labels = new String[devices.length];
        for (int i = 0; i < devices.length; i++) {
            String name = devices[i].getName();
            labels[i] = (name == null ? "(unnamed)" : name) + "\n" + devices[i].getAddress();
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.pick_device)
                .setItems(labels, (d, which) -> connectToDevice(devices[which]))
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void connectToDevice(BluetoothDevice device) {
        statusView.setText("EEG: connecting…");
        // Promote the service to a foreground process so it survives onStop —
        // the activity may be torn down (screen off / backgrounded) but the
        // EEG link + MP3 playback must keep running.
        ContextCompat.startForegroundService(this,
                new Intent(this, EegPlaybackService.class));
        if (playback != null) {
            playback.connect(device);
        } else {
            // Binder hasn't arrived yet — onServiceConnected will pick this up.
            pendingDevice = device;
        }
    }

    private void startTraining() {
        isTraining = true;
        trainBtn.setText(R.string.stop_train);
        if (playback != null) playback.setPlaybackActive(true);
    }

    private void stopTraining() {
        isTraining = false;
        trainBtn.setText(R.string.train);
        if (playback != null && !isRecording) playback.setPlaybackActive(false);
    }

    // ---------- Camera ----------

    private final TextureView.SurfaceTextureListener textureListener =
            new TextureView.SurfaceTextureListener() {
        @Override
        public void onSurfaceTextureAvailable(@NonNull SurfaceTexture s, int w, int h) {
            openCamera(w, h);
        }
        @Override
        public void onSurfaceTextureSizeChanged(@NonNull SurfaceTexture s, int w, int h) {
            configureTransform(w, h);
        }
        @Override
        public boolean onSurfaceTextureDestroyed(@NonNull SurfaceTexture s) { return true; }
        @Override
        public void onSurfaceTextureUpdated(@NonNull SurfaceTexture s) {}
    };

    private void startCameraThread() {
        cameraThread = new HandlerThread("Camera");
        cameraThread.start();
        cameraHandler = new Handler(cameraThread.getLooper());
    }

    private void stopCameraThread() {
        if (cameraThread == null) return;
        cameraThread.quitSafely();
        try { cameraThread.join(); } catch (InterruptedException ignored) {}
        cameraThread = null;
        cameraHandler = null;
    }

    private void openCamera(int width, int height) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED
            || ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO},
                    REQ_CAMERA_MIC);
            return;
        }
        try {
            for (String id : cameraManager.getCameraIdList()) {
                CameraCharacteristics c = cameraManager.getCameraCharacteristics(id);
                Integer facing = c.get(CameraCharacteristics.LENS_FACING);
                if (facing != null && facing == CameraCharacteristics.LENS_FACING_BACK) {
                    cameraId = id;
                    sensorOrientation = c.get(CameraCharacteristics.SENSOR_ORIENTATION);
                    if (sensorOrientation == null) sensorOrientation = 90;
                    StreamConfigurationMap map = c.get(
                            CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
                    if (map == null) continue;
                    videoSize = chooseVideoSize(map.getOutputSizes(MediaRecorder.class));
                    previewSize = chooseOptimalSize(
                            map.getOutputSizes(SurfaceTexture.class), width, height, videoSize);
                    break;
                }
            }
            if (cameraId == null) {
                Toast.makeText(this, "No back camera", Toast.LENGTH_SHORT).show();
                return;
            }
            configureTransform(width, height);
            cameraManager.openCamera(cameraId, cameraStateCallback, cameraHandler);
        } catch (CameraAccessException | SecurityException e) {
            Log.e(TAG, "openCamera failed", e);
        }
    }

    private final CameraDevice.StateCallback cameraStateCallback =
            new CameraDevice.StateCallback() {
        @Override public void onOpened(@NonNull CameraDevice device) {
            cameraDevice = device; startPreview();
        }
        @Override public void onDisconnected(@NonNull CameraDevice device) {
            device.close(); cameraDevice = null;
        }
        @Override public void onError(@NonNull CameraDevice device, int error) {
            device.close(); cameraDevice = null;
            Log.e(TAG, "camera error " + error);
        }
    };

    private void startPreview() {
        if (cameraDevice == null || !previewView.isAvailable() || previewSize == null) return;
        try {
            SurfaceTexture texture = previewView.getSurfaceTexture();
            texture.setDefaultBufferSize(previewSize.getWidth(), previewSize.getHeight());
            Surface previewSurface = new Surface(texture);
            requestBuilder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            requestBuilder.addTarget(previewSurface);
            cameraDevice.createCaptureSession(
                    Collections.singletonList(previewSurface),
                    new CameraCaptureSession.StateCallback() {
                        @Override public void onConfigured(@NonNull CameraCaptureSession s) {
                            captureSession = s; updateRepeating();
                        }
                        @Override public void onConfigureFailed(@NonNull CameraCaptureSession s) {
                            Log.e(TAG, "preview config failed");
                        }
                    }, cameraHandler);
        } catch (CameraAccessException e) {
            Log.e(TAG, "startPreview failed", e);
        }
    }

    private void updateRepeating() {
        if (captureSession == null || requestBuilder == null) return;
        requestBuilder.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO);
        try {
            captureSession.setRepeatingRequest(requestBuilder.build(), null, cameraHandler);
        } catch (CameraAccessException e) {
            Log.e(TAG, "setRepeatingRequest failed", e);
        }
    }

    private void closeCamera() {
        if (captureSession != null) { captureSession.close(); captureSession = null; }
        if (cameraDevice != null) { cameraDevice.close(); cameraDevice = null; }
    }

    private void configureTransform(int viewWidth, int viewHeight) {
        if (previewView == null || previewSize == null
                || viewWidth == 0 || viewHeight == 0) return;
        int rotation = getWindowManager().getDefaultDisplay().getRotation();
        Matrix matrix = new Matrix();
        RectF viewRect = new RectF(0, 0, viewWidth, viewHeight);
        RectF bufferRect = new RectF(0, 0, previewSize.getHeight(), previewSize.getWidth());
        float centerX = viewRect.centerX();
        float centerY = viewRect.centerY();
        if (Surface.ROTATION_90 == rotation || Surface.ROTATION_270 == rotation) {
            bufferRect.offset(centerX - bufferRect.centerX(), centerY - bufferRect.centerY());
            matrix.setRectToRect(viewRect, bufferRect, Matrix.ScaleToFit.FILL);
            float scale = Math.max(
                    (float) viewHeight / previewSize.getHeight(),
                    (float) viewWidth / previewSize.getWidth());
            matrix.postScale(scale, scale, centerX, centerY);
            matrix.postRotate(90 * (rotation - 2), centerX, centerY);
        } else if (Surface.ROTATION_180 == rotation) {
            matrix.postRotate(180, centerX, centerY);
        }
        previewView.setTransform(matrix);
    }

    // ---------- Recording (MediaProjection) ----------

    private void requestRecording() {
        if (cameraDevice == null && previewView.isAvailable()) {
            openCamera(previewView.getWidth(), previewView.getHeight());
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this,
                    Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this,
                        new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_CAMERA_MIC);
            }
        }
        projectionLauncher.launch(projectionManager.createScreenCaptureIntent());
    }

    private void beginRecording(int resultCode, Intent resultData) {
        DisplayMetrics m = new DisplayMetrics();
        getWindowManager().getDefaultDisplay().getRealMetrics(m);
        // Round dimensions to even numbers — H264 encoder requirement.
        int w = m.widthPixels & ~1;
        int h = m.heightPixels & ~1;

        Intent svc = new Intent(this, ScreenRecordService.class);
        svc.setAction(ScreenRecordService.ACTION_START);
        svc.putExtra(ScreenRecordService.EXTRA_RESULT_CODE, resultCode);
        svc.putExtra(ScreenRecordService.EXTRA_RESULT_DATA, resultData);
        svc.putExtra(ScreenRecordService.EXTRA_WIDTH, w);
        svc.putExtra(ScreenRecordService.EXTRA_HEIGHT, h);
        svc.putExtra(ScreenRecordService.EXTRA_DPI, m.densityDpi);
        ContextCompat.startForegroundService(this, svc);

        isRecording = true;
        recordBtn.setText(R.string.stop);
        recordBtn.setBackgroundTintList(
                getResources().getColorStateList(R.color.recording, getTheme()));
        if (playback != null) playback.setPlaybackActive(true);
    }

    private void stopRecording() {
        if (!isRecording) return;
        isRecording = false;

        Intent svc = new Intent(this, ScreenRecordService.class);
        svc.setAction(ScreenRecordService.ACTION_STOP);
        startService(svc);

        recordBtn.setText(R.string.start);
        recordBtn.setBackgroundTintList(
                getResources().getColorStateList(R.color.accent, getTheme()));
        if (playback != null && !isTraining) playback.setPlaybackActive(false);
        Toast.makeText(this, "Saved to Movies/", Toast.LENGTH_SHORT).show();
    }

    // ---------- Size helpers ----------

    private static Size chooseVideoSize(Size[] choices) {
        for (Size s : choices) {
            if (s.getWidth() == s.getHeight() * 16 / 9 && s.getWidth() <= 1920) return s;
        }
        return choices[choices.length - 1];
    }

    private static Size chooseOptimalSize(Size[] choices, int viewW, int viewH, Size aspect) {
        List<Size> big = new ArrayList<>();
        int aw = aspect.getWidth();
        int ah = aspect.getHeight();
        for (Size s : choices) {
            if (s.getHeight() == s.getWidth() * ah / aw
                    && s.getWidth() >= viewW && s.getHeight() >= viewH) {
                big.add(s);
            }
        }
        if (!big.isEmpty()) {
            return Collections.min(big,
                    Comparator.comparingLong(a -> (long) a.getWidth() * a.getHeight()));
        }
        return choices[0];
    }

    // ---------- Permissions ----------

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_CAMERA_MIC) {
            // Only camera+mic are mandatory; notification permission is optional.
            for (int i = 0; i < permissions.length; i++) {
                if ((Manifest.permission.CAMERA.equals(permissions[i])
                        || Manifest.permission.RECORD_AUDIO.equals(permissions[i]))
                        && grantResults[i] != PackageManager.PERMISSION_GRANTED) {
                    Toast.makeText(this, "Camera and microphone are required",
                            Toast.LENGTH_LONG).show();
                    finish();
                    return;
                }
            }
            if (previewView.isAvailable()) {
                openCamera(previewView.getWidth(), previewView.getHeight());
            }
        } else if (requestCode == REQ_BT) {
            if (grantResults.length > 0
                    && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                showDevicePicker();
            } else {
                Toast.makeText(this, "Bluetooth permission required to pick a device",
                        Toast.LENGTH_LONG).show();
            }
        }
    }
}
