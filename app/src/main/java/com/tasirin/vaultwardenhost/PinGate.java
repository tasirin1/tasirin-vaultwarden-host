package com.tasirin.vaultwardenhost;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.SystemClock;

public final class PinGate {

    static final String KEY_GAGAL = "pin_gagal";
    static final String KEY_KUNCI_SAMPAI = "pin_kunci_sampai";

    static final String KEY_KUNCI_ELAPSED = "pin_kunci_elapsed";

    public static final String KEY_PIN_HASH = "pin_hash";
    public static final String KEY_PIN_ON = "pin_on";

    private PinGate() {
    }

    public static final long PIN_GRACE_MS = 30_000;
    private static volatile boolean bukaBersama = false;
    private static volatile long bukaBersamaAt = 0;

    private static final Object KUNCI_BERSAMA = new Object();

    public static final Object KUNCI_PERCOBAAN = new Object();

    public static boolean dalamGraceBersama() {
        synchronized (KUNCI_BERSAMA) {
            long delta = SystemClock.elapsedRealtime() - bukaBersamaAt;
            return bukaBersama && delta >= 0 && delta < PIN_GRACE_MS;
        }
    }

    public static void bukaKunciBersama() {
        synchronized (KUNCI_BERSAMA) {
            bukaBersama = true;
            bukaBersamaAt = SystemClock.elapsedRealtime();
        }
    }

    public static void kunciBersama() {
        synchronized (KUNCI_BERSAMA) {
            bukaBersama = false;
            bukaBersamaAt = 0;
        }
    }

    static final int KUNCI_MATI = 0;
    static final int KUNCI_GRACE = 1;
    static final int KUNCI_RUSAK = 2;
    static final int KUNCI_BUKA = 3;

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

    static boolean wajibLayarPin(int putus) {
        return putus == KUNCI_BUKA || putus == KUNCI_RUSAK;
    }

    public static boolean perlukanLayarPin(Context ctx) {
        SharedPreferences sp = ctx.getSharedPreferences(
                ServerService.PREFS, Context.MODE_PRIVATE);
        int putus = putusKunci(TgBackup.amanBoolean(sp, KEY_PIN_ON, false),
                dalamGraceBersama(), TgBackup.amanString(sp, KEY_PIN_HASH, ""));
        if (putus == KUNCI_MATI) {

            kunciBersama();
        } else if (putus == KUNCI_RUSAK) {

            ServerService.catatLog("[app] PIN rusak (hash hilang/rusak): akses"
                    + " ditolak - siapkan ulang PIN.");
        }
        return wajibLayarPin(putus);
    }

    public static long sisaKunciMs(Context ctx, long sekarang) {

        if (ctx == null) {
            return PinCrypto.KUNCI_MS;
        }
        SharedPreferences sp = ctx.getSharedPreferences(
                ServerService.PREFS, Context.MODE_PRIVATE);
        int gagal = TgBackup.amanInt(sp, KEY_GAGAL, 0);
        long sisaWall = PinCrypto.sisaKunciMs(gagal,
                TgBackup.amanLong(sp, KEY_KUNCI_SAMPAI, 0), sekarang);
        long elapsedKini = SystemClock.elapsedRealtime();
        long sampaiElapsed = TgBackup.amanLong(sp, KEY_KUNCI_ELAPSED, 0);
        if (gagal >= PinCrypto.MAX_GAGAL
                && PinCrypto.stempelElapsedBasi(sampaiElapsed, elapsedKini)) {
            long sisaBaru = PinCrypto.sisaKunciSesudahReboot(sisaWall);
            try {
                sp.edit().putLong(KEY_KUNCI_SAMPAI, sekarang + sisaBaru)
                        .putLong(KEY_KUNCI_ELAPSED, elapsedKini + sisaBaru)
                        .apply();
            } catch (Exception ignored) {
            }
            return sisaBaru;
        }
        long sisaElapsed = PinCrypto.sisaKunciElapsed(sampaiElapsed, elapsedKini);
        return Math.max(sisaWall, sisaElapsed);
    }

    @SuppressLint("ApplySharedPref")
    public static synchronized void catatHasil(Context ctx, boolean cocok, long sekarang) {
        if (ctx == null) {
            return;
        }
        // Audit 1362: pencatatan SENGAJA selalu sinkron di utas pemanggil.
        // Jalur async lama (executor antrean terbatas) bisa menolak atau
        // menunda tugas, sehingga percobaan gagal dari UI bisa hilang
        // sebelum tercatat dan hitungan kunci terlewat; commit kecil ini
        // cukup murah untuk utas utama layar PIN.
        SharedPreferences sp = ctx.getSharedPreferences(
                ServerService.PREFS, Context.MODE_PRIVATE);
        if (cocok) {

            sp.edit().remove(KEY_GAGAL).remove(KEY_KUNCI_SAMPAI)
                    .remove(KEY_KUNCI_ELAPSED).commit();
            return;
        }

        int gagal = TgBackup.amanInt(sp, KEY_GAGAL, 0) + 1;
        sp.edit().putInt(KEY_GAGAL, gagal)
                .putLong(KEY_KUNCI_SAMPAI,
                        PinCrypto.kunciBerikutnyaMs(gagal, sekarang))
                .putLong(KEY_KUNCI_ELAPSED, PinCrypto.kunciElapsedBerikutnyaMs(
                        gagal, SystemClock.elapsedRealtime()))
                .commit();
    }

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

            synchronized (KUNCI_PERCOBAAN) {
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
}
