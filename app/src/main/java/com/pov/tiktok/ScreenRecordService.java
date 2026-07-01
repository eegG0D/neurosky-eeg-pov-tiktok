package com.pov.tiktok;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.MediaRecorder;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Environment;
import android.os.IBinder;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import java.io.File;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Foreground service that wraps MediaProjection + MediaRecorder to capture the screen
 * (camera preview + subtitle overlay) into an MP4 with subtitles burned in.
 */
public class ScreenRecordService extends Service {

    public static final String ACTION_START = "start";
    public static final String ACTION_STOP = "stop";
    public static final String EXTRA_RESULT_CODE = "resultCode";
    public static final String EXTRA_RESULT_DATA = "resultData";
    public static final String EXTRA_WIDTH = "width";
    public static final String EXTRA_HEIGHT = "height";
    public static final String EXTRA_DPI = "dpi";

    private static final String TAG = "ScreenRecordSvc";
    private static final String CHANNEL_ID = "pov_recording";
    private static final int NOTIF_ID = 9001;

    private MediaProjection projection;
    private VirtualDisplay virtualDisplay;
    private MediaRecorder recorder;
    private File outputFile;
    private boolean recording;

    @Nullable
    @Override
    public IBinder onBind(Intent intent) { return null; }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) return START_NOT_STICKY;
        String action = intent.getAction();
        if (ACTION_STOP.equals(action)) {
            stopRecording();
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf();
            return START_NOT_STICKY;
        }

        startInForeground();

        if (recording) return START_NOT_STICKY;

        int resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0);
        Intent resultData = intent.getParcelableExtra(EXTRA_RESULT_DATA);
        int width = intent.getIntExtra(EXTRA_WIDTH, 1080);
        int height = intent.getIntExtra(EXTRA_HEIGHT, 1920);
        int dpi = intent.getIntExtra(EXTRA_DPI, 420);

        try {
            startRecording(resultCode, resultData, width, height, dpi);
        } catch (Exception e) {
            Log.e(TAG, "startRecording failed", e);
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf();
        }
        return START_NOT_STICKY;
    }

    private void startInForeground() {
        NotificationManager nm =
                (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && nm != null
                && nm.getNotificationChannel(CHANNEL_ID) == null) {
            NotificationChannel ch = new NotificationChannel(CHANNEL_ID,
                    "POV Recording", NotificationManager.IMPORTANCE_LOW);
            nm.createNotificationChannel(ch);
        }
        Notification n = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("POV TikTok")
                .setContentText("Recording…")
                .setSmallIcon(android.R.drawable.presence_video_online)
                .setOngoing(true)
                .build();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, n,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
        } else {
            startForeground(NOTIF_ID, n);
        }
    }

    private void startRecording(int resultCode, Intent resultData,
                                int width, int height, int dpi) throws IOException {
        MediaProjectionManager mgr =
                (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
        projection = mgr.getMediaProjection(resultCode, resultData);
        if (projection == null) throw new IOException("MediaProjection unavailable");

        File dir = getExternalFilesDir(Environment.DIRECTORY_MOVIES);
        if (dir != null && !dir.exists()) dir.mkdirs();
        String stamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
        outputFile = new File(dir, "pov_" + stamp + ".mp4");

        recorder = new MediaRecorder();
        recorder.setAudioSource(MediaRecorder.AudioSource.MIC);
        recorder.setVideoSource(MediaRecorder.VideoSource.SURFACE);
        recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
        recorder.setVideoEncoder(MediaRecorder.VideoEncoder.H264);
        recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
        recorder.setVideoEncodingBitRate(10_000_000);
        recorder.setVideoFrameRate(30);
        recorder.setVideoSize(width, height);
        recorder.setOutputFile(outputFile.getAbsolutePath());
        recorder.prepare();

        // Required on Android 14+: register a callback before creating the VirtualDisplay.
        projection.registerCallback(new MediaProjection.Callback() {
            @Override public void onStop() { stopRecording(); }
        }, null);

        virtualDisplay = projection.createVirtualDisplay(
                "pov-tiktok",
                width, height, dpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                recorder.getSurface(),
                null, null);

        recorder.start();
        recording = true;
    }

    private void stopRecording() {
        if (!recording) return;
        recording = false;
        try {
            if (recorder != null) {
                try { recorder.stop(); } catch (RuntimeException ignored) {}
                recorder.reset();
                recorder.release();
            }
        } finally {
            recorder = null;
        }
        if (virtualDisplay != null) {
            virtualDisplay.release();
            virtualDisplay = null;
        }
        if (projection != null) {
            projection.stop();
            projection = null;
        }
        Log.i(TAG, "saved: " + (outputFile != null ? outputFile.getAbsolutePath() : "?"));
    }

    @Override
    public void onDestroy() {
        stopRecording();
        super.onDestroy();
    }
}
