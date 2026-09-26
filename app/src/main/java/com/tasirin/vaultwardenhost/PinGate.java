package com.tasirin.vaultwardenhost;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.SharedPreferences;

/** Anti brute-force PIN: kunci 5 menit setelah 5 gagal beruntun.
 *  Dipakai dialog PIN (Main/Settings) & PIN bot Telegram. */
public final class PinGate {

    static final String KEY_GAGAL = "pin_gagal";
    static final String KEY_KUNCI_SAMPAI = "pin_kunci_sampai";

    private PinGate() {
    }

    /** Sisa kunci (ms); 0 bila boleh coba. */
    public static long sisaKunciMs(Context ctx, long sekarang) {
        SharedPreferences sp = ctx.getSharedPreferences(
                ServerService.PREFS, Context.MODE_PRIVATE);
        return PinCrypto.sisaKunciMs(sp.getInt(KEY_GAGAL, 0),
                sp.getLong(KEY_KUNCI_SAMPAI, 0), sekarang);
    }

    /** Catat hasil satu percobaan (true = cocok, gagal di-reset). */
    // commit() di bawah disengaja (sinkron, lihat komentar) — bukan apply().
    @SuppressLint("ApplySharedPref")
    public static synchronized void catatHasil(Context ctx, boolean cocok, long sekarang) {
        SharedPreferences sp = ctx.getSharedPreferences(
                ServerService.PREFS, Context.MODE_PRIVATE);
        if (cocok) {
            sp.edit().remove(KEY_GAGAL).remove(KEY_KUNCI_SAMPAI).apply();
            return;
        }
        // commit() sinkron (bukan apply()): hitungan gagal wajib awet di disk
        // sebelum penyerang sempat membunuh app (metode sudah synchronized).
        int gagal = sp.getInt(KEY_GAGAL, 0) + 1;
        sp.edit().putInt(KEY_GAGAL, gagal)
                .putLong(KEY_KUNCI_SAMPAI,
                        PinCrypto.kunciBerikutnyaMs(gagal, sekarang))
                .commit();
    }
}
