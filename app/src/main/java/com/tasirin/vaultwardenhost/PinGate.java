package com.tasirin.vaultwardenhost;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.SystemClock;

/** Anti brute-force PIN: kunci 5 menit setelah 5 gagal beruntun.
 *  Dipakai dialog PIN (Main/Settings) & PIN bot Telegram. */
public final class PinGate {

    static final String KEY_GAGAL = "pin_gagal";
    static final String KEY_KUNCI_SAMPAI = "pin_kunci_sampai";
    /** Batas elapsed pendamping wall-clock: tahan reset jam & jam STB rusak.
     *  Wall-clock tahan reboot; elapsed tahan reset jam. Dipakai nilai terbesar. */
    static final String KEY_KUNCI_ELAPSED = "pin_kunci_elapsed";

    /** Kunci hash PIN & status aktif: satu sumber untuk activity/bot/backup
     *  (dulu literal "pin_hash"/"pin_on" tersebar + konstanta privat ganda). */
    public static final String KEY_PIN_HASH = "pin_hash";
    public static final String KEY_PIN_ON = "pin_on";

    private PinGate() {
    }

    /** Sisa kunci (ms); 0 bila boleh coba. Nilai terbesar wall-clock vs elapsed.
     *  Baca via TgBackup.amanInt/amanLong agar prefs korup bertipe String tak
     *  ClassCastException berulang (disembuhkan sekali, lalu default). */
    public static long sisaKunciMs(Context ctx, long sekarang) {
        SharedPreferences sp = ctx.getSharedPreferences(
                ServerService.PREFS, Context.MODE_PRIVATE);
        long sisaWall = PinCrypto.sisaKunciMs(TgBackup.amanInt(sp, KEY_GAGAL, 0),
                TgBackup.amanLong(sp, KEY_KUNCI_SAMPAI, 0), sekarang);
        long sisaElapsed = PinCrypto.sisaKunciElapsed(
                TgBackup.amanLong(sp, KEY_KUNCI_ELAPSED, 0),
                SystemClock.elapsedRealtime());
        return Math.max(sisaWall, sisaElapsed);
    }

    /** Catat hasil satu percobaan (true = cocok, gagal di-reset).
     *  Wajib dari worker thread: commit() sinkron memblokir hingga awet di disk
     *  (pemanggil UI/bot kini sudah di worker). Bila tanpa sengaja dipanggil
     *  dari UI thread, otomatis dialihkan ke worker agar tak ANR. */
    // commit() di bawah disengaja (sinkron, lihat komentar) — bukan apply().
    @SuppressLint("ApplySharedPref")
    public static synchronized void catatHasil(Context ctx, boolean cocok, long sekarang) {
        if (ctx != null && diMainThread()) {
            catatHasilAsync(ctx, cocok, sekarang);
            return;
        }
        SharedPreferences sp = ctx.getSharedPreferences(
                ServerService.PREFS, Context.MODE_PRIVATE);
        if (cocok) {
            // commit() sinkron seperti jalur gagal: reset counter wajib awet di
            // disk sebelum kill, bila tidak user terkunci walau PIN benar.
            sp.edit().remove(KEY_GAGAL).remove(KEY_KUNCI_SAMPAI)
                    .remove(KEY_KUNCI_ELAPSED).commit();
            return;
        }
        // commit() sinkron (bukan apply()): hitungan gagal wajib awet di disk
        // sebelum penyerang sempat membunuh app (metode sudah synchronized).
        int gagal = TgBackup.amanInt(sp, KEY_GAGAL, 0) + 1;
        sp.edit().putInt(KEY_GAGAL, gagal)
                .putLong(KEY_KUNCI_SAMPAI,
                        PinCrypto.kunciBerikutnyaMs(gagal, sekarang))
                .putLong(KEY_KUNCI_ELAPSED, PinCrypto.kunciElapsedBerikutnyaMs(
                        gagal, SystemClock.elapsedRealtime()))
                .commit();
    }

    /** Varian aman-UI: catat di worker agar commit() tak memblokir UI thread. */
    public static void catatHasilAsync(final Context ctx, final boolean cocok, final long sekarang) {
        final Context app;
        try {
            app = ctx == null ? null : ctx.getApplicationContext();
        } catch (Exception ignored) {
            return;
        }
        final Context pakai = app != null ? app : ctx;
        new Thread(() -> {
            try {
                catatHasil(pakai, cocok, sekarang);
            } catch (Exception ignored) {
            }
        }, "vw-pin-cat").start();
    }

    /** True bila dipanggil dari UI thread (uji aman di JVM: tanpa Looper = false). */
    private static boolean diMainThread() {
        try {
            android.os.Looper utama = android.os.Looper.getMainLooper();
            return utama != null && android.os.Looper.myLooper() == utama;
        } catch (Exception ignored) {
            return false;
        }
    }
}
