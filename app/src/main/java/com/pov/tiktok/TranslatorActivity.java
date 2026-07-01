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
import android.text.method.ScrollingMovementMethod;
import android.util.Log;
import android.view.View;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.google.android.material.button.MaterialButton;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;

/**
 * Port of translator.php — TGAM hardware decoding terminal:
 *  - loads eeg_map.csv from assets/
 *  - shows live raw / signal / attention / 5 wave bands
 *  - logs throttled frames (every 400 ms) to a CSV the user can export
 */
public class TranslatorActivity extends AppCompatActivity {

    private static final String TAG = "Translator";
    private static final int REQ_BT = 81;
    private static final long RECORD_INTERVAL_MS = 400L;

    // ---------------- THRESHOLDS / DESCRIPTIONS (mirror translator.php) ---------------
    private static final long DELTA_LOW  = 100_000L, DELTA_HIGH = 5_000_000L;
    private static final long THETA_LOW  = 100_000L, THETA_HIGH = 5_000_000L;
    private static final long ALPHA_LOW  =  50_000L, ALPHA_HIGH = 2_000_000L;
    private static final long BETA_LOW   =  30_000L, BETA_HIGH  = 1_000_000L;
    private static final long GAMMA_LOW  =  10_000L, GAMMA_HIGH =   500_000L;

    private static String classify(long v, long low, long high, String l, String m, String h) {
        if (v <= low)  return l;
        if (v <= high) return m;
        return h;
    }

    // ---------------- UI ----------------
    private TextView statusText, mappedText, signalText, attText, rawText, logText;
    private ProgressBar signalBar, attBar;
    private ProgressBar deltaBar, thetaBar, alphaBar, betaBar, gammaBar;
    private MaterialButton connectBtn, recBtn, exportBtn;
    private ScrollView logScroll;

    // ---------------- State ----------------
    private BtEegClient eeg;
    private volatile boolean eegConnected;
    private boolean isRecording;
    private long lastRecordTime;

    /** Built once from assets/eeg_map.csv. Read from the BT reader thread (onRaw)
     *  and the UI thread, so we publish via a volatile reference. */
    private volatile Map<Integer, String> eegMap = new HashMap<>();
    private final List<String[]> csvRows = new ArrayList<>();

    private volatile boolean mapReady;

    // Latest band state for logging
    private volatile long deltaV, thetaV, alphaLowV, betaLowV;
    private volatile int signalPct, attentionV;

    private final Handler ui = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_translator);

        statusText  = findViewById(R.id.tStatusText);
        mappedText  = findViewById(R.id.tMapped);
        signalText  = findViewById(R.id.tSignalText);
        attText     = findViewById(R.id.tAttText);
        rawText     = findViewById(R.id.tRaw);
        logText     = findViewById(R.id.tLog);
        logScroll   = findViewById(R.id.tLogScroll);
        signalBar   = findViewById(R.id.tSignalBar);
        attBar      = findViewById(R.id.tAttBar);
        connectBtn  = findViewById(R.id.tConnectBtn);
        recBtn      = findViewById(R.id.tRecBtn);
        exportBtn   = findViewById(R.id.tExportBtn);

        deltaBar = bindWave(R.id.tDeltaRow, "DELTA");
        thetaBar = bindWave(R.id.tThetaRow, "THETA");
        alphaBar = bindWave(R.id.tAlphaRow, "ALPHA");
        betaBar  = bindWave(R.id.tBetaRow,  "BETA");
        gammaBar = bindWave(R.id.tGammaRow, "GAMMA");

        logText.setMovementMethod(new ScrollingMovementMethod());

        connectBtn.setOnClickListener(v -> {
            if (eeg != null) stopLink(); else startLink();
        });
        recBtn.setOnClickListener(v -> toggleRecording());
        exportBtn.setOnClickListener(v -> exportCSV());

        log("INITIALIZING OMEGA-7 CORE...", LogLvl.ALERT);
        loadEegMap();
    }

    private ProgressBar bindWave(int includeId, String label) {
        View row = findViewById(includeId);
        ((TextView) row.findViewById(R.id.waveLabel)).setText(label);
        return row.findViewById(R.id.waveBar);
    }

    @Override
    protected void onDestroy() {
        if (eeg != null) { eeg.stop(); eeg = null; }
        super.onDestroy();
    }

    // ---------------- Neural map load ----------------

    private void loadEegMap() {
        try (InputStream in = getAssets().open("eeg_map.csv");
             ByteArrayOutputStream out = new ByteArrayOutputStream(in.available())) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            int nodes = parseNeuralMap(new String(out.toByteArray(), StandardCharsets.UTF_8));
            mapReady = nodes > 0;
            log("BOOT_SUCCESS: " + nodes + " NODES MAPPED.", LogLvl.INFO);
        } catch (IOException e) {
            Log.e(TAG, "loadEegMap failed", e);
            log("BOOT_ERROR: NEURAL MAP UNAVAILABLE.", LogLvl.ERROR);
        }
    }

    private int parseNeuralMap(String csv) {
        if (csv == null) return 0;
        Map<Integer, String> next = new HashMap<>();
        for (String line : csv.split("\\r?\\n")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) continue;
            String[] parts = trimmed.split(",");
            if (parts.length < 2) continue;
            try {
                next.put(Integer.parseInt(parts[0].trim()), parts[1].trim());
            } catch (NumberFormatException ignored) {
                // header row or junk line — skip
            }
        }
        eegMap = next;            // volatile publish — safe for concurrent readers
        return next.size();
    }

    // ---------------- EEG link ----------------

    private void startLink() {
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
        log("LINK: requesting TGAM bridge...", LogLvl.INFO);
        eeg = new BtEegClient(device, new BtEegClient.Listener() {
            @Override public void onConnected() {
                ui.post(() -> {
                    eegConnected = true;
                    statusText.setText("ONLINE");
                    statusText.setTextColor(getResources().getColor(R.color.connected, getTheme()));
                    connectBtn.setText("Disconnect Sensor");
                    recBtn.setEnabled(true);
                    log("LINK: TGAM HARDWARE BRIDGE SECURED.", LogLvl.INFO);
                });
            }
            @Override public void onDisconnected(String reason) {
                ui.post(() -> {
                    eegConnected = false;
                    statusText.setText("DISCONNECTED");
                    statusText.setTextColor(0xFF888888);
                    connectBtn.setText("Connect Hardware");
                    recBtn.setEnabled(false);
                    mappedText.setText("OFFLINE");
                    if (isRecording) toggleRecording();
                });
            }
            @Override public void onRaw(int rawValue) { onRawValue(rawValue); }
            @Override public void onPoorSignal(int level) { onSignal(level); }
            @Override public void onAttention(int level) { onAttn(level); }
            @Override public void onEegBands(int delta, int theta,
                                             int alphaLow, int alphaHigh,
                                             int betaLow, int betaHigh,
                                             int gammaLow, int gammaMid) {
                onBands(delta, theta, alphaLow, alphaHigh,
                        betaLow, betaHigh, gammaLow, gammaMid);
            }
        });
        eeg.start();
    }

    private void stopLink() {
        log("LINK: TERMINATING SENSOR STREAM...", LogLvl.INFO);
        if (eeg != null) { eeg.stop(); eeg = null; }
    }

    // ---------------- Live data handlers ----------------

    private void onSignal(int rawByte) {
        signalPct = Math.max(0, (200 - rawByte) / 2);
        ui.post(() -> {
            signalText.setText(signalPct + "%");
            signalBar.setProgress(signalPct);
        });
    }

    private void onAttn(int level) {
        attentionV = level;
        ui.post(() -> {
            attText.setText(String.valueOf(level));
            attBar.setProgress(level);
        });
    }

    private void onRawValue(int raw) {
        final int rawKey = raw;
        final String mapped = eegMap.get(rawKey);
        final String label = mapped != null
                ? mapped
                : (mapReady ? "ANALYZING..." : "OFFLINE");
        ui.post(() -> {
            rawText.setText(String.valueOf(rawKey));
            mappedText.setText(label);
            // Recording state + csvRows live on the UI thread to avoid races
            // with exportCSV() iterating the list.
            if (isRecording) {
                long now = System.currentTimeMillis();
                if (now - lastRecordTime >= RECORD_INTERVAL_MS) {
                    lastRecordTime = now;
                    recordFrame(rawKey, mapped);
                }
            }
        });
    }

    private void onBands(int delta, int theta,
                         int alphaLow, int alphaHigh,
                         int betaLow,  int betaHigh,
                         int gammaLow, int gammaMid) {
        deltaV    = delta & 0xFFFFFFFFL;
        thetaV    = theta & 0xFFFFFFFFL;
        alphaLowV = alphaLow & 0xFFFFFFFFL;
        betaLowV  = betaLow & 0xFFFFFFFFL;
        final int dPct = pct(delta),    tPct = pct(theta);
        final int aPct = pct(alphaLow), bPct = pct(betaLow), gPct = pct(gammaLow);
        ui.post(() -> {
            deltaBar.setProgress(dPct);
            thetaBar.setProgress(tPct);
            alphaBar.setProgress(aPct);
            betaBar.setProgress(bPct);
            gammaBar.setProgress(gPct);
        });
    }

    /** PHP uses (v / 2_000_000) * 100, capped at 100. */
    private static int pct(int v) {
        if (v <= 0) return 0;
        int p = (int) (((long) v * 100L) / 2_000_000L);
        return Math.min(100, p);
    }

    // ---------------- Logging / export ----------------

    private void toggleRecording() {
        isRecording = !isRecording;
        if (isRecording) {
            csvRows.clear();
            lastRecordTime = 0L;
            recBtn.setText("Stop Logging");
            exportBtn.setEnabled(false);
            log("LOGGER: ACTIVE (THROTTLE: " + RECORD_INTERVAL_MS + " ms).", LogLvl.INFO);
        } else {
            recBtn.setText("Start Logging");
            if (!csvRows.isEmpty()) {
                exportBtn.setEnabled(true);
                log("LOGGER: FINISHED. " + csvRows.size() + " FRAMES READY.", LogLvl.ALERT);
            }
        }
    }

    /** Must be called on the UI thread — csvRows is not thread-safe. */
    private void recordFrame(int raw, String label) {
        String neuralState = label != null ? label : ("ID_" + raw);
        csvRows.add(new String[] {
                isoNow(),
                neuralState,
                String.valueOf(attentionV),
                signalPct + "%",
                classify(deltaV,   DELTA_LOW, DELTA_HIGH, "Alert",      "Relaxed",    "Deep Sleep"),
                classify(thetaV,   THETA_LOW, THETA_HIGH, "Distracted", "Meditation", "Dreaming"),
                classify(betaLowV, BETA_LOW,  BETA_HIGH,  "Idle",       "Focused",    "Intense")
        });
        log("FRAME_CAPTURED: Node " + neuralState, LogLvl.INFO);
    }

    private void exportCSV() {
        if (csvRows.isEmpty()) return;
        StringBuilder sb = new StringBuilder();
        sb.append("timestamp,neural_state,attention,link,delta,theta,beta\n");
        for (String[] r : csvRows) {
            for (int i = 0; i < r.length; i++) {
                if (i > 0) sb.append(',');
                sb.append(r[i]);
            }
            sb.append('\n');
        }
        Uri saved = saveToDownloads(sb.toString().getBytes(),
                "neural_report_" + System.currentTimeMillis() + ".csv");
        if (saved != null) {
            log("EXPORT: SUCCESSFUL.", LogLvl.INFO);
            Toast.makeText(this, "Saved to Downloads/", Toast.LENGTH_LONG).show();
        } else {
            log("EXPORT: WRITE FAILED.", LogLvl.ERROR);
        }
    }

    private Uri saveToDownloads(byte[] data, String name) {
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

    // ---------------- Terminal log ----------------

    private enum LogLvl { INFO, ALERT, ERROR }

    private void log(String msg, LogLvl lvl) {
        String ts = new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date());
        CharSequence prev = logText.getText();
        StringBuilder sb = new StringBuilder(prev.length() + msg.length() + 32);
        sb.append(prev);
        if (prev.length() > 0) sb.append('\n');
        sb.append('[').append(ts).append("] > ").append(msg);
        // Cap to last 60 lines to avoid runaway memory.
        String full = sb.toString();
        int lines = countLines(full);
        if (lines > 60) full = trimToLast(full, 60);
        logText.setText(full);
        logScroll.post(() -> logScroll.fullScroll(View.FOCUS_DOWN));
        // Color hint only for errors / alerts in the global TextView color
        if (lvl == LogLvl.ERROR) logText.setTextColor(0xFFFF3366);
        else if (lvl == LogLvl.ALERT) logText.setTextColor(0xFFFFCC00);
        else logText.setTextColor(0xFF666666);
    }

    private static int countLines(String s) {
        int n = 1;
        for (int i = 0; i < s.length(); i++) if (s.charAt(i) == '\n') n++;
        return n;
    }

    private static String trimToLast(String s, int keep) {
        int newline = 0;
        int drop = 0;
        int total = countLines(s);
        int toDrop = total - keep;
        while (newline < s.length() && drop < toDrop) {
            if (s.charAt(newline) == '\n') drop++;
            newline++;
        }
        return s.substring(newline);
    }

    private static String isoNow() {
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US);
        f.setTimeZone(TimeZone.getTimeZone("UTC"));
        return f.format(new Date());
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
