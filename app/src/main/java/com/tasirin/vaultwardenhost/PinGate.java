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

    /** Grace buka PIN antar-activity (satu sumber agar Main/Settings tak drift):
     *  30 dtk cukup untuk pindah Main <-> Settings tanpa PIN ulang, tapi sempit
     *  untuk penyalahgunaan akses fisik. Jangkar elapsed (bukan wall-clock) agar
     *  utak-atik tanggal tak memperpanjang. */
    public static final long PIN_GRACE_MS = 30_000;
    private static volatile boolean bukaBersama = false;
    private static volatile long bukaBersamaAt = 0;
    /** Kunci khusus status grace: terpisah dari monitor kelas agar commit() disk
     *  di catatHasil (synchronized, bisa ratusan ms di storage STB lambat) tak
     *  memblokir cek grace UI. Nama sengaja beda dari konstanta hasil
     *  {@link #KUNCI_GRACE} (int) — dulu keduanya bernama sama sehingga javac
     *  menolak kelas ini ("variable KUNCI_GRACE is already defined"). */
    private static final Object KUNCI_BERSAMA = new Object();
    /** Executor tunggal berbatas untuk pencatatan gagal: antrean 32; tugas baru
     *  ditolak saat penuh (pencatatan best-effort, penolakan di-catch pemanggil)
     *  agar spam PIN tak menumpuk OOM di STB 1 GB.
     *  Daemon agar thread idle tak menahan proses saat uji/unit selesai.
     *  Penolakan dicatat lewat satu pintu Util. */
    // Satu pintu Util (tolak tugas baru saat antrean penuh + jejak log).
    private static final java.util.concurrent.RejectedExecutionHandler TOLAK_BERLOG =
            Util.penanganAntreanPenuh("PinGate");
    private static final java.util.concurrent.ExecutorService CATAT_EXEC =
            new java.util.concurrent.ThreadPoolExecutor(0, 1, 60L,
                    java.util.concurrent.TimeUnit.SECONDS,
                    new java.util.concurrent.LinkedBlockingQueue<Runnable>(32), r -> {
                Thread t = new Thread(r, "vw-pin-catat");
                t.setDaemon(true);
                return t;
            }, TOLAK_BERLOG);

    /** True bila PIN dibuka dalam grace (tanpa peka activity). Murni waktu. */
    public static boolean dalamGraceBersama() {
        synchronized (KUNCI_BERSAMA) {
            long delta = SystemClock.elapsedRealtime() - bukaBersamaAt;
            return bukaBersama && delta >= 0 && delta < PIN_GRACE_MS;
        }
    }

    /** Catat PIN cocok sebagai milik bersama (tanpa panggil balik activity). */
    public static void bukaKunciBersama() {
        synchronized (KUNCI_BERSAMA) {
            bukaBersama = true;
            bukaBersamaAt = SystemClock.elapsedRealtime();
        }
    }

    /** Buang status buka bersama (PIN dimatikan / grace habis). */
    public static void kunciBersama() {
        synchronized (KUNCI_BERSAMA) {
            bukaBersama = false;
            bukaBersamaAt = 0;
        }
    }

    /** Hasil keputusan layar kunci (lihat putusKunci). */
    static final int KUNCI_MATI = 0;   // PIN nonaktif
    static final int KUNCI_GRACE = 1;  // masih dalam grace buka bersama
    static final int KUNCI_RUSAK = 2;  // PIN aktif tapi hash hilang/rusak
    static final int KUNCI_BUKA = 3;   // wajib minta PIN

    /** Keputusan murni apakah layar PIN perlu dibuka. Aturan yang dulu
     *  disalin di MainActivity & SettingsActivity dipusatkan di sini agar
     *  tak bisa menyimpang (satu layar fail-open). Murni agar bisa diuji. */
    static int putusKunci(boolean pinOn, boolean dalamGrace, String pinHash) {
        if (!pinOn) {
            return KUNCI_MATI;
        }
        if (dalamGrace) {
            return KUNCI_GRACE;
        }
        if (pinHash == null || pinHash.isEmpty()) {
            return KUNCI_RUSAK;
        }
        return KUNCI_BUKA;
    }

    /** True bila keputusan {@link #putusKunci} mewajibkan layar PIN.
     *  KUNCI_RUSAK ikut wajib: hash hilang/rusak bukan alasan membuka aplikasi
     *  tanpa kunci (PinActivity juga fail-closed). Murni agar bisa unit test. */
    static boolean wajibLayarPin(int putus) {
        return putus == KUNCI_BUKA || putus == KUNCI_RUSAK;
    }

    /** Jalankan keputusan + efek sampingnya; true = activity wajib membuka
     *  PinActivity. Satu pintu aturan PIN mati / grace / hash rusak untuk
     *  MainActivity & SettingsActivity. Fail-closed: PIN aktif tanpa hash
     *  tetap menutup aplikasi, bukan mematikan proteksi diam-diam. */
    public static boolean perlukanLayarPin(Context ctx) {
        SharedPreferences sp = ctx.getSharedPreferences(
                ServerService.PREFS, Context.MODE_PRIVATE);
        int putus = putusKunci(TgBackup.amanBoolean(sp, KEY_PIN_ON, false),
                dalamGraceBersama(), TgBackup.amanString(sp, KEY_PIN_HASH, ""));
        if (putus == KUNCI_MATI) {
            // PIN mati: buang status buka basi agar PIN yang diaktifkan lagi
            // dalam grace lama tak dianggap sudah dibuka tanpa entri baru.
            kunciBersama();
        } else if (putus == KUNCI_RUSAK) {
            // PIN aktif tanpa hash (prefs rusak/terpotong): JANGAN dimatikan
            // dan JANGAN dibuka. Prefs tak disentuh; PinActivity menolak
            // (RESULT_CANCELED) sehingga pemanggil finish — Settings, admin
            // token, log, dan backup tetap terkunci sampai PIN disiapkan ulang.
            ServerService.catatLog("[app] PIN rusak (hash hilang/rusak): akses"
                    + " ditolak - siapkan ulang PIN.");
        }
        return wajibLayarPin(putus);
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
