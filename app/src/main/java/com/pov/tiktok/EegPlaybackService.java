package com.pov.tiktok;

import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.bluetooth.BluetoothDevice;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.PowerManager;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * Foreground service that owns the EEG link and the subtitle-driven MP3 playback so
 * both survive the activity going to onStop (screen off, app backgrounded). The
 * activity binds for UI updates and triggers connect / disconnect / playback state
 * through the binder.
 */
public class EegPlaybackService extends Service {

    public static final String ACTION_STOP = "stop";

    private static final String TAG = "EegPlaybackSvc";
    private static final String CHANNEL_ID = "pov_eeg_playback";
    private static final int NOTIF_ID = 9101;
    private static final long SUBTITLE_INTERVAL_MS = 2000L;

    public interface StateListener {
        void onConnectionChanged(boolean connected, @Nullable String reason);
        void onSignal(int level);
        void onSubtitle(String word);
        void onMapReady(boolean ok);
    }

    public class LocalBinder extends Binder {
        public EegPlaybackService getService() { return EegPlaybackService.this; }
    }

    private final IBinder binder = new LocalBinder();

    private WordBank wordBank;
    private volatile BtEegClient eeg;
    private volatile boolean connected;
    private volatile int latestRaw;
    private boolean playbackActive;
    private boolean mapReady;
    private String currentSubtitle = "";
    private PowerManager.WakeLock wakeLock;

    private HandlerThread tickThread;
    private Handler tick;
    private final Runnable subtitleTick = new Runnable() {
        @Override
        public void run() {
            if (!playbackActive || !connected) return;
            updateSubtitle();
            tick.postDelayed(this, SUBTITLE_INTERVAL_MS);
        }
    };

    @Nullable private StateListener listener;

    @Override
    public void onCreate() {
        super.onCreate();
        wordBank = new WordBank(this);
        loadEegMap();
        tickThread = new HandlerThread("eeg-tick");
        tickThread.start();
        tick = new Handler(tickThread.getLooper());
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        if (pm != null) {
            // Partial wake lock keeps the CPU on so the BT reader thread isn't suspended
            // during doze; mediaPlayback foreground type alone doesn't guarantee that.
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,
                    "pov-tiktok:eeg-playback");
            wakeLock.setReferenceCounted(false);
        }
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) { return binder; }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            shutdown();
            return START_NOT_STICKY;
        }
        startInForeground();
        return START_STICKY;
    }

    // ---------- Activity-facing API ----------

    /** Just registers — callers read current state via the isXxx() getters. */
    public void setListener(@Nullable StateListener l) {
        this.listener = l;
    }

    public void clearListener() { this.listener = null; }

    public boolean isConnected()      { return connected; }
    public boolean isMapReady()       { return mapReady; }
    public boolean isPlaybackActive() { return playbackActive; }
    public String  currentSubtitle()  { return currentSubtitle; }

    /** Bound activity calls this after picking a paired device. */
    @SuppressLint("MissingPermission")
    public void connect(BluetoothDevice device) {
        // Replace any stale client. A previous failed/dropped connection leaves
        // eeg non-null, and silently returning here would freeze the UI on
        // "EEG: connecting…" forever.
        BtEegClient old = eeg;
        if (old != null) {
            Log.d(TAG, "connect(): cleaning up stale client");
            old.stop();
            eeg = null;
        }
        Log.d(TAG, "connect(): starting BT link to " + device.getAddress());
        startInForeground();
        if (wakeLock != null && !wakeLock.isHeld()) wakeLock.acquire();
        eeg = new BtEegClient(device, new BtEegClient.Listener() {
            @Override public void onConnected() {
                Log.d(TAG, "BT onConnected");
                connected = true;
                notifyConnection(true, null);
                refreshNotification();
            }
            @Override public void onDisconnected(String reason) {
                Log.d(TAG, "BT onDisconnected: " + reason);
                connected = false;
                eeg = null;
                notifyConnection(false, reason);
                refreshNotification();
            }
            @Override public void onRaw(int rawValue) { latestRaw = rawValue; }
            @Override public void onPoorSignal(int level) { notifySignal(level); }
        });
        eeg.start();
    }

    public void disconnect() { shutdown(); }

    public void setPlaybackActive(boolean active) {
        if (active == playbackActive) return;
        playbackActive = active;
        if (active) {
            tick.removeCallbacks(subtitleTick);
            tick.post(subtitleTick);
        } else {
            tick.removeCallbacks(subtitleTick);
            currentSubtitle = "";
            notifySubtitle("");
        }
    }

    // ---------- Internals ----------

    private void updateSubtitle() {
        if (!mapReady) return;
        String word = wordBank.wordAt(latestRaw);
        if (!word.equals(currentSubtitle)) {
            currentSubtitle = word;
            notifySubtitle(word);
            wordBank.play(word);
        }
    }

    private void loadEegMap() {
        try (InputStream in = getAssets().open("eeg_map.csv.enc");
             ByteArrayOutputStream out = new ByteArrayOutputStream(in.available())) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            byte[] csv = EegMapVault.decrypt(out.toByteArray());
            wordBank.loadFromCsv(new String(csv, StandardCharsets.UTF_8));
            mapReady = !wordBank.words().isEmpty();
            notifyMapReady(mapReady);
        } catch (Exception e) {
            Log.e(TAG, "loadEegMap failed", e);
        }
    }

    private void shutdown() {
        setPlaybackActive(false);
        if (eeg != null) { eeg.stop(); eeg = null; }
        connected = false;
        notifyConnection(false, "stopped");
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    @Override
    public void onDestroy() {
        if (eeg != null) { eeg.stop(); eeg = null; }
        if (wordBank != null) wordBank.release();
        if (tickThread != null) tickThread.quitSafely();
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        super.onDestroy();
    }

    // Listener fan-out — invoked from BT reader thread, tick thread, or main.
    // Each callback target should marshal to its own thread (the activity uses runOnUiThread).
    private void notifyConnection(boolean c, @Nullable String reason) {
        StateListener l = listener; if (l != null) l.onConnectionChanged(c, reason);
    }
    private void notifySignal(int level) {
        StateListener l = listener; if (l != null) l.onSignal(level);
    }
    private void notifySubtitle(String word) {
        StateListener l = listener; if (l != null) l.onSubtitle(word);
    }
    private void notifyMapReady(boolean ok) {
        StateListener l = listener; if (l != null) l.onMapReady(ok);
    }

    // ---------- Foreground notification ----------

    private void startInForeground() {
        NotificationManager nm =
                (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && nm != null
                && nm.getNotificationChannel(CHANNEL_ID) == null) {
            NotificationChannel ch = new NotificationChannel(CHANNEL_ID,
                    "POV EEG playback", NotificationManager.IMPORTANCE_LOW);
            nm.createNotificationChannel(ch);
        }
        Notification n = buildNotification();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIF_ID, n,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
                            | ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, n,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
        } else {
            startForeground(NOTIF_ID, n);
        }
    }

    private void refreshNotification() {
        NotificationManager nm =
                (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm != null) nm.notify(NOTIF_ID, buildNotification());
    }

    private Notification buildNotification() {
        Intent stopIntent = new Intent(this, EegPlaybackService.class)
                .setAction(ACTION_STOP);
        PendingIntent stopPi = PendingIntent.getService(this, 0, stopIntent,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        Intent openIntent = new Intent(this, MainActivity.class)
                .setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent openPi = PendingIntent.getActivity(this, 0, openIntent,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("POV TikTok")
                .setContentText(connected ? "EEG connected" : "EEG idle")
                .setSmallIcon(android.R.drawable.presence_audio_online)
                .setOngoing(true)
                .setContentIntent(openPi)
                .addAction(android.R.drawable.ic_menu_close_clear_cancel,
                        "Disconnect", stopPi)
                .build();
    }
}
