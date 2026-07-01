package com.pov.tiktok;

import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.content.res.AssetManager;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.util.Log;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Reads assets/eeg_map.csv (rows of "eegValue,word,fileIndex") and plays
 * assets/audio/&lt;fileIndex&gt;.mp3 on demand via a single MediaPlayer.
 * Files are opened lazily — preloading 6000 clips into a SoundPool would
 * ANR the UI thread and overflow SoundPool's limits.
 */
public class WordBank {

    private static final String TAG = "WordBank";

    private final List<String> words = new ArrayList<>();
    private final Map<String, String> wordToFile = new HashMap<>();
    private final AssetManager assets;
    private MediaPlayer player;

    public WordBank(Context ctx) {
        this.assets = ctx.getAssets();
    }

    public synchronized List<String> words() { return words; }

    public synchronized void loadFromCsv(String csv) {
        words.clear();
        wordToFile.clear();
        if (csv == null || csv.isEmpty()) return;
        for (String line : csv.split("\\r?\\n")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) continue;
            String[] parts = trimmed.split(",");
            if (parts.length < 3) continue;
            String word = parts[1].trim();
            String index = parts[2].trim();
            if (word.isEmpty() || index.isEmpty()) continue;
            words.add(word);
            wordToFile.put(word, index + ".mp3");
        }
        Log.i(TAG, "Loaded " + words.size() + " words from remote map");
    }

    public synchronized String wordAt(int rawValue) {
        if (words.isEmpty()) return "";
        return words.get(Math.floorMod(rawValue, words.size()));
    }

    public void play(String word) {
        String file = wordToFile.get(word);
        if (file == null) return;
        stopPlayer();
        try {
            AssetFileDescriptor afd = assets.openFd("audio/" + file);
            MediaPlayer mp = new MediaPlayer();
            mp.setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build());
            mp.setDataSource(afd.getFileDescriptor(), afd.getStartOffset(), afd.getLength());
            afd.close();
            mp.setOnCompletionListener(MediaPlayer::release);
            mp.setOnErrorListener((m, what, extra) -> { m.release(); return true; });
            mp.prepare();
            mp.start();
            player = mp;
        } catch (IOException e) {
            Log.d(TAG, "no sound for " + word + " (" + file + ")");
        }
    }

    public void release() {
        stopPlayer();
    }

    private void stopPlayer() {
        if (player == null) return;
        try { player.stop(); } catch (IllegalStateException ignored) {}
        player.release();
        player = null;
    }
}
