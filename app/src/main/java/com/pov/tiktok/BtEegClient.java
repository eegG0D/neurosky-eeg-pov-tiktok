package com.pov.tiktok;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.util.Log;

import java.io.IOException;
import java.io.InputStream;
import java.util.UUID;

/**
 * Connects to a NeuroSky-style EEG headset over Bluetooth SPP and parses the
 * ThinkGear binary stream.
 *
 * Packet: 0xAA 0xAA PLENGTH PAYLOAD CHKSUM
 * CHKSUM = ~(sum of payload bytes) & 0xFF
 *
 * Payload tuples: (0x55*)? CODE [VLENGTH] VALUE...
 * - CODE  < 0x80: VLENGTH implicit = 1
 * - CODE >= 0x80: next byte is VLENGTH
 * Codes used here: 0x02 POOR_SIGNAL (1B), 0x80 RAW (2B signed BE).
 */
public class BtEegClient {

    public interface Listener {
        void onConnected();
        void onDisconnected(String reason);
        void onRaw(int rawValue);
        void onPoorSignal(int level);
        default void onAttention(int level) {}
        default void onEegBands(int delta, int theta,
                                int alphaLow, int alphaHigh,
                                int betaLow,  int betaHigh,
                                int gammaLow, int gammaMid) {}
    }

    private static final String TAG = "BtEegClient";
    private static final UUID SPP_UUID =
            UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");

    private final BluetoothDevice device;
    private final Listener listener;

    private BluetoothSocket socket;
    private Thread reader;
    private volatile boolean running;

    public BtEegClient(BluetoothDevice device, Listener listener) {
        this.device = device;
        this.listener = listener;
    }

    public void start() {
        if (running) return;
        running = true;
        reader = new Thread(this::run, "bt-eeg-reader");
        reader.start();
    }

    public void stop() {
        running = false;
        try { if (socket != null) socket.close(); } catch (IOException ignored) {}
        socket = null;
    }

    @SuppressLint("MissingPermission")
    private void run() {
        try {
            Log.d(TAG, "creating RFCOMM socket to " + device.getAddress());
            socket = device.createRfcommSocketToServiceRecord(SPP_UUID);
            Log.d(TAG, "connecting socket…");
            socket.connect();
            Log.d(TAG, "socket connected, entering parse loop");
            listener.onConnected();
            parseStream(socket.getInputStream());
            listener.onDisconnected("eof");
        } catch (Throwable t) {
            // Catch SecurityException etc. too — anything thrown here would otherwise
            // silently kill the reader thread and leave the UI stuck at "connecting…".
            Log.w(TAG, "bt error: " + t.getClass().getSimpleName() + ": " + t.getMessage(), t);
            listener.onDisconnected(t.getMessage() != null ? t.getMessage() : t.getClass().getSimpleName());
        } finally {
            running = false;
            try { if (socket != null) socket.close(); } catch (IOException ignored) {}
            socket = null;
        }
    }

    private void parseStream(InputStream in) throws IOException {
        while (running) {
            if (readByte(in) != 0xAA) continue;
            if (readByte(in) != 0xAA) continue;
            int plen = readByte(in);
            if (plen < 0 || plen > 169) continue;
            byte[] payload = new byte[plen];
            int read = 0;
            while (read < plen) {
                int n = in.read(payload, read, plen - read);
                if (n < 0) throw new IOException("eof");
                read += n;
            }
            int checksum = readByte(in);
            int sum = 0;
            for (byte b : payload) sum += b & 0xFF;
            int expected = ~sum & 0xFF;
            if (expected != checksum) continue;
            parsePayload(payload);
        }
    }

    private static int readByte(InputStream in) throws IOException {
        int b = in.read();
        if (b < 0) throw new IOException("eof");
        return b & 0xFF;
    }

    private void parsePayload(byte[] payload) {
        int i = 0;
        while (i < payload.length) {
            while (i < payload.length && (payload[i] & 0xFF) == 0x55) i++;
            if (i >= payload.length) break;
            int code = payload[i++] & 0xFF;
            int vlen;
            if (code < 0x80) {
                vlen = 1;
            } else {
                if (i >= payload.length) break;
                vlen = payload[i++] & 0xFF;
            }
            if (i + vlen > payload.length) break;
            switch (code) {
                case 0x02: // POOR_SIGNAL
                    listener.onPoorSignal(payload[i] & 0xFF);
                    break;
                case 0x04: // ATTENTION eSense (0..100)
                    listener.onAttention(payload[i] & 0xFF);
                    break;
                case 0x80: // RAW (2 bytes signed big-endian)
                    if (vlen == 2) {
                        int raw = ((payload[i] & 0xFF) << 8) | (payload[i + 1] & 0xFF);
                        if (raw >= 0x8000) raw -= 0x10000;
                        listener.onRaw(raw);
                    }
                    break;
                case 0x83: // ASIC_EEG_POWER (8 × 3-byte big-endian unsigned ints)
                    if (vlen >= 24) {
                        listener.onEegBands(
                                u24(payload, i),       u24(payload, i + 3),
                                u24(payload, i + 6),   u24(payload, i + 9),
                                u24(payload, i + 12),  u24(payload, i + 15),
                                u24(payload, i + 18),  u24(payload, i + 21));
                    }
                    break;
                default:
                    break;
            }
            i += vlen;
        }
    }

    private static int u24(byte[] p, int o) {
        return ((p[o] & 0xFF) << 16) | ((p[o + 1] & 0xFF) << 8) | (p[o + 2] & 0xFF);
    }
}
