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

    /** Grace buka PIN antar-activity (satu sumber agar Main/Settings tak drift).
     *  Jangkar elapsed (bukan wall-clock) agar utak-atik tanggal tak memperpanjang. */
    /** Grace 30 dtk: cukup untuk pindah Main <-> Settings tanpa PIN ulang,
     *  tapi sempit untuk penyalahgunaan akses fisik. */
    public static final long PIN_GRACE_MS = 30_000;
    private static volatile boolean bukaBersama = false;
    private static volatile long bukaBersamaAt = 0;
    /** Kunci khusus status grace: terpisah dari monitor kelas agar commit() disk
     *  di catatHasil (synchronized, bisa ratusan ms di storage STB lambat) tak
     *  memblokir cek grace UI. */
    private static final Object KUNCI_GRACE = new Object();
    /** Executor tunggal untuk pencatatan gagal: cegah ledakan thread tiap upaya. */
    private static final java.util.concurrent.ExecutorService CATAT_EXEC =
            java.util.concurrent.Executors.newSingleThreadExecutor();

    /** True bila PIN dibuka dalam grace (tanpa peka activity). Murni waktu. */
    public static boolean dalamGraceBersama() {
        synchronized (KUNCI_GRACE) {
            long delta = SystemClock.elapsedRealtime() - bukaBersamaAt;
            return bukaBersama && delta >= 0 && delta < PIN_GRACE_MS;
        }
    }

    /** Kapan PIN bersama dibuka (jangkar grace, tanpa perpanjangan). */
    public static long kapanBukaBersama() {
        synchronized (KUNCI_GRACE) {
            return bukaBersamaAt;
        }
    }

    /** Catat PIN cocok sebagai milik bersama (tanpa panggil balik activity). */
    public static void bukaKunciBersama() {
        synchronized (KUNCI_GRACE) {
            bukaBersama = true;
            bukaBersamaAt = SystemClock.elapsedRealtime();
        }
    }

    /** Buang status buka bersama (PIN dimatikan / grace habis). */
    public static void kunciBersama() {
        synchronized (KUNCI_GRACE) {
            bukaBersama = false;
            bukaBersamaAt = 0;
        }
    }

    /** Sisa kunci (ms); 0 bila boleh coba. Nilai terbesar wall-clock vs elapsed.
     *  Baca via TgBackup.amanInt/amanLong agar prefs korup bertipe String tak
     *  ClassCastException berulang (disembuhkan sekali, lalu default). */
    public static long sisaKunciMs(Context ctx, long sekarang) {
        // Fail-closed: ctx null jangan beri celah coba (samakan dengan catatHasil).
        if (ctx == null) {
            return PinCrypto.KUNCI_MS;
        }
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
        if (ctx == null) {
            return;
        }
        if (diMainThread()) {
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

    /** Keraskan hash PIN legasi di rest tanpa menunggu login sukses.
     *  Tanpa ini SHA-256 tanpa salt bertahan selamanya bagi user yang jarang
     *  login dan retak offline dalam detik bila prefs bocor. Wajib dari worker
     *  thread (PBKDF2 30k); dipanggil sekali tiap app dibuka. Tulis best-effort
     *  via apply (gagal tertunda dicoba lagi saat buka berikut); cek-ulang
     *  sebelum tulis agar tak menimpa hash standar hasil login di thread lain. */
    public static void kuatkanHashDini(Context ctx) {
        try {
            if (ctx == null) {
                return;
            }
            SharedPreferences sp = ctx.getSharedPreferences(
                    ServerService.PREFS, Context.MODE_PRIVATE);
            String simpan = TgBackup.amanString(sp, KEY_PIN_HASH, "");
            if (!PinCrypto.perluMigrasi(simpan)) {
                return;
            }
            String bungkus = PinCrypto.bungkusLegasi(simpan);
            if (bungkus == null) {
                return;
            }
            // Kunci kelas + baca ulang di dalam blok: cegah TOCTOU menimpa
            // hash standar hasil login thread lain; tulis hanya bila masih legasi.
            synchronized (PinGate.class) {
                String kini = TgBackup.amanString(sp, KEY_PIN_HASH, "");
                if (!simpan.equals(kini)) {
                    return;
                }
                if (!PinCrypto.perluMigrasi(kini)) {
                    return;
                }
                sp.edit().putString(KEY_PIN_HASH, bungkus).apply();
            }
        } catch (Exception ignored) {
        }
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
        // Executor tunggal agar upaya beruntun tak membuat thread tak terbatas.
        try {
            CATAT_EXEC.execute(() -> {
                try {
                    catatHasil(pakai, cocok, sekarang);
                } catch (Exception ignored) {
                }
            });
        } catch (Exception ignored) {
        }
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
