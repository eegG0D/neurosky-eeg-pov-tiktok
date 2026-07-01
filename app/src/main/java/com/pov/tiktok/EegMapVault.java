package com.pov.tiktok;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Arrays;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Decrypts eeg_map.csv.enc.
 *
 * The 256-bit AES-GCM key is SHA-256 of a 50-character passphrase. The
 * passphrase is not stored directly — its 25 two-character tiles are
 * scattered across a 5x5 board. At runtime the tiles are collected in the
 * order produced by an open knight's tour starting at the top-left corner,
 * choosing each next square by Warnsdorff's rule (move to the square with
 * the fewest onward knight moves; ties broken in the order knight moves
 * are listed in {@link #MOVES}). The same rule produces the same tour
 * deterministically, so the tiles always rejoin in the same order.
 *
 * The .enc file format is: IV (12 bytes) || ciphertext || GCM tag (16 bytes).
 */
final class EegMapVault {

    // 5x5 board of two-character tiles. Empty look-up — meaning lives in the tour.
    private static final String[][] BOARD = {
            {"K7", "Ml", "H3", "sX", "pQ"},
            {"wF", "a5", "m9", "Vo", "wP"},
            {"A8", "8j", "4Q", "2v", "cY"},
            {"dE", "6g", "bR", "7z", "Ui"},
            {"zL", "rB", "0n", "T1", "N4"},
    };

    // Knight moves. Order matters: Warnsdorff ties are broken by first-found.
    private static final int[][] MOVES = {
            {-2, -1}, {-2, 1}, {-1, -2}, {-1, 2},
            { 1, -2}, { 1, 2}, { 2, -1}, { 2, 1},
    };

    private EegMapVault() {}

    static byte[] decrypt(byte[] sealed) throws GeneralSecurityException {
        if (sealed.length < 12 + 16) throw new GeneralSecurityException("blob too short");
        byte[] iv   = Arrays.copyOfRange(sealed, 0, 12);
        byte[] body = Arrays.copyOfRange(sealed, 12, sealed.length);
        SecretKey key = new SecretKeySpec(deriveKey(), "AES");
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, iv));
        return cipher.doFinal(body);
    }

    private static byte[] deriveKey() throws GeneralSecurityException {
        StringBuilder pass = new StringBuilder(50);
        boolean[][] seen = new boolean[5][5];
        int r = 0, c = 0;
        for (int step = 0; step < 25; step++) {
            seen[r][c] = true;
            pass.append(BOARD[r][c]);
            if (step == 24) break;
            int bestR = -1, bestC = -1, bestDeg = Integer.MAX_VALUE;
            for (int[] m : MOVES) {
                int nr = r + m[0], nc = c + m[1];
                if (nr < 0 || nr > 4 || nc < 0 || nc > 4 || seen[nr][nc]) continue;
                int deg = onwardCount(nr, nc, seen);
                if (deg < bestDeg) { bestDeg = deg; bestR = nr; bestC = nc; }
            }
            if (bestR < 0) throw new GeneralSecurityException("tour broke at step " + step);
            r = bestR; c = bestC;
        }
        return MessageDigest.getInstance("SHA-256")
                .digest(pass.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static int onwardCount(int r, int c, boolean[][] seen) {
        int n = 0;
        for (int[] m : MOVES) {
            int nr = r + m[0], nc = c + m[1];
            if (nr >= 0 && nr <= 4 && nc >= 0 && nc <= 4 && !seen[nr][nc]) n++;
        }
        return n;
    }
}
