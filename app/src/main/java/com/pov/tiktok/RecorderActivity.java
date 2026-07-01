package com.pov.tiktok;

import android.Manifest;
import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.util.Log;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.google.android.material.button.MaterialButton;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Streams raw EEG samples from a NeuroSky-style headset, then writes the full
 * CSV to Downloads/ as plain text when the user stops.
 */
public class RecorderActivity extends AppCompatActivity {

    private static final String TAG = "RecorderActivity";
    private static final int REQ_BT = 71;

    private static final int TARGET_ROWS = 1_000_000;
    private static final int SAMPLING_RATE = 512;

    private TextView statusView;
    private TextView rawValueView;
    private TextView rowCountView;
    private ProgressBar progressBar;
    private MaterialButton connectBtn;
    private MaterialButton captureBtn;

    private BtEegClient eeg;
    private volatile boolean eegConnected;
    private volatile boolean recording;
    private final List<Integer> samples = new ArrayList<>(TARGET_ROWS / 8);

    private final Handler ui = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_recorder);

        statusView    = findViewById(R.id.recStatus);
        rawValueView  = findViewById(R.id.rawValue);
        rowCountView  = findViewById(R.id.rowCount);
        progressBar   = findViewById(R.id.progressBar);
        connectBtn    = findViewById(R.id.recConnectBtn);
        captureBtn    = findViewById(R.id.recCaptureBtn);

        connectBtn.setOnClickListener(v -> toggleEeg());
        captureBtn.setOnClickListener(v -> {
            if (!eegConnected) {
                Toast.makeText(this, "Connect EEG first", Toast.LENGTH_SHORT).show();
                return;
            }
            if (recording) saveCapture();
            else           startCapture();
        });
    }

    @Override
    protected void onDestroy() {
        if (eeg != null) { eeg.stop(); eeg = null; }
        super.onDestroy();
    }

    // ---------- EEG ----------

    private void toggleEeg() {
        if (eeg != null) {
            eeg.stop();
            eeg = null;
            return;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                && ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)
                        != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.BLUETOOTH_CONNECT}, REQ_BT);
            return;
        }
        showDevicePicker();
    }

    @SuppressLint("MissingPermission")
    private void showDevicePicker() {
        BluetoothManager bm = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
        BluetoothAdapter adapter = bm != null ? bm.getAdapter() : null;
        if (adapter == null || !adapter.isEnabled()) {
            Toast.makeText(this, "Enable Bluetooth first", Toast.LENGTH_LONG).show();
            return;
        }
        Set<BluetoothDevice> bonded = adapter.getBondedDevices();
        if (bonded == null || bonded.isEmpty()) {
            new AlertDialog.Builder(this)
                    .setTitle(R.string.pick_device)
                    .setMessage(R.string.no_paired)
                    .setPositiveButton("OK", null).show();
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
                .setItems(labels, (d, w) -> connect(devices[w]))
                .setNegativeButton("Cancel", null).show();
    }

    private void connect(BluetoothDevice device) {
        statusView.setText("EEG: connecting…");
        eeg = new BtEegClient(device, new BtEegClient.Listener() {
            @Override public void onConnected() {
                runOnUiThread(() -> {
                    eegConnected = true;
                    statusView.setText("EEG: connected");
                    connectBtn.setText(R.string.disconnect);
                    captureBtn.setEnabled(true);
                });
            }
            @Override public void onDisconnected(String reason) {
                runOnUiThread(() -> {
                    eegConnected = false;
                    statusView.setText(R.string.status_idle);
                    connectBtn.setText(R.string.connect_eeg);
                    if (recording) cancelCapture();
                    captureBtn.setEnabled(false);
                });
            }
            @Override public void onRaw(int rawValue) { onSample(rawValue); }
            @Override public void onPoorSignal(int level) {
                runOnUiThread(() -> statusView.setText("signal " + level));
            }
        });
        eeg.start();
    }

    // ---------- Capture ----------

    private void onSample(int v) {
        ui.post(() -> rawValueView.setText(String.format(Locale.US, "%04d", Math.abs(v))));
        if (!recording) return;
        synchronized (samples) { samples.add(v); }
        int count = samples.size();
        if ((count & 0xFF) == 0) {           // refresh UI every 256 samples
            int prog = (int) ((long) count * 100 / TARGET_ROWS);
            int remSec = Math.max(0, (TARGET_ROWS - count) / SAMPLING_RATE);
            ui.post(() -> {
                rowCountView.setText(String.format(Locale.US, "%,d samples", count));
                progressBar.setProgress(Math.min(prog, 100));
                statusView.setText(String.format(Locale.US,
                        "Recording — %d:%02d remaining", remSec / 60, remSec % 60));
            });
        }
        if (count >= TARGET_ROWS) ui.post(this::saveCapture);
    }

    private void startCapture() {
        synchronized (samples) { samples.clear(); }
        recording = true;
        progressBar.setProgress(0);
        rowCountView.setText("0 samples");
        captureBtn.setText(R.string.stop_capture);
        statusView.setText("Recording…");
    }

    private void cancelCapture() {
        recording = false;
        synchronized (samples) { samples.clear(); }
        captureBtn.setText(R.string.start_capture);
        progressBar.setProgress(0);
        rowCountView.setText("0 samples");
    }

    private void saveCapture() {
        if (!recording) return;
        recording = false;
        captureBtn.setEnabled(false);
        captureBtn.setText("SAVING…");
        statusView.setText("Saving…");

        byte[] csv = buildCsv().getBytes(StandardCharsets.UTF_8);
        Uri saved = saveToDownloads(csv);
        captureBtn.setEnabled(true);
        captureBtn.setText(R.string.start_capture);
        if (saved != null) {
            statusView.setText("Saved to Downloads/");
            Toast.makeText(this, "CSV saved", Toast.LENGTH_LONG).show();
        } else {
            statusView.setText("Save failed");
            Toast.makeText(this, "Save failed", Toast.LENGTH_LONG).show();
        }
        progressBar.setProgress(0);
    }

    private String buildCsv() {
        StringBuilder sb = new StringBuilder(samples.size() * 6 + 16);
        sb.append("raw_eeg\n");
        synchronized (samples) {
            for (Integer v : samples) sb.append(v).append('\n');
        }
        return sb.toString();
    }

    // ---------- Save to Downloads ----------

    private Uri saveToDownloads(byte[] data) {
        String name = "eeg_raw_" + System.currentTimeMillis() + ".csv";
        try {
            ContentValues cv = new ContentValues();
            cv.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
            cv.put(MediaStore.MediaColumns.MIME_TYPE, "text/csv");
            Uri collection;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                cv.put(MediaStore.MediaColumns.RELATIVE_PATH,
                        Environment.DIRECTORY_DOWNLOADS);
                collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI;
            } else {
                collection = MediaStore.Files.getContentUri("external");
            }
            ContentResolver cr = getContentResolver();
            Uri uri = cr.insert(collection, cv);
            if (uri == null) return null;
            try (OutputStream out = cr.openOutputStream(uri)) {
                if (out == null) return null;
                out.write(data);
            }
            return uri;
        } catch (Exception e) {
            Log.e(TAG, "saveToDownloads failed", e);
            return null;
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_BT && grantResults.length > 0
                && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            showDevicePicker();
        }
    }
}
