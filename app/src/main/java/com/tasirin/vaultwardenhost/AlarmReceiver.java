package com.tasirin.vaultwardenhost;

import android.annotation.SuppressLint;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

public class AlarmReceiver extends BroadcastReceiver {

    public static final String ACTION_HARIAN =
            "com.tasirin.vaultwardenhost.ALARM_HARIAN";

    public static final String ACTION_TUNDA =
            "com.tasirin.vaultwardenhost.ALARM_TUNDA_BOOT";

    public static final String EXTRA_RAHASIA =
            "com.tasirin.vaultwardenhost.ALARM_SECRET";

    static final long THROTTLE_ALARM_MS = 60_000;
    private static volatile long terakhirAlarmElapsed = 0;
    private static volatile long terakhirWaktuElapsed = 0;

    static final String KEY_THROTTLE_ALARM_ELAPSED = "alarm_throttle_elapsed";
    static final String KEY_THROTTLE_WAKTU_ELAPSED = "alarm_throttle_waktu_elapsed";
    static final String KEY_THROTTLE_ALARM_WALL = "alarm_throttle_wall";
    static final String KEY_THROTTLE_WAKTU_WALL = "alarm_throttle_waktu_wall";

    // Audit 2879: stempel elapsedRealtime
    // mentah tak punya identitas boot -
    // peninggalan boot PENDEK sebelumnya
    // bisa menelan alarm harian di boot
    // baru. Stempel kini berpasangan cap
    # jam dinding dan hanya dipercaya
    // bila kedua jam maju konsisten
    # (satu boot); selain itu diabaikan
    // (gagal-terbuka: alarm tetap jalan).
    static final long TOLERANSI_STEMPEL_THROTTLE_MS = 120_000;

    static boolean stempelThrottleSatuBoot(long capElapsed,
            long capWall, long kiniElapsed, long kiniWall) {
        if (capElapsed <= 0 || capWall <= 0) {
            return false;
        }
        long selisihElapsed = kiniElapsed - capElapsed;
        long selisihWall = kiniWall - capWall;
        return selisihElapsed >= 0
                && Math.abs(selisihWall - selisihElapsed)
                        <= TOLERANSI_STEMPEL_THROTTLE_MS;
    }

    static long throttleTervalidasi(Context context,
            String kunciElapsed, String kunciWall) {
        long capElapsed = TgBackup.bacaStempelThrottle(
                context, kunciElapsed);
        long capWall = TgBackup.bacaStempelThrottle(
                context, kunciWall);
        if (!stempelThrottleSatuBoot(capElapsed, capWall,
                android.os.SystemClock.elapsedRealtime(),
                System.currentTimeMillis())) {
            return 0;
        }
        return capElapsed;
    }

    static boolean bolehAlarmJalan(long kini, long terakhir, long jeda) {
        return terakhir <= 0 || kini < terakhir || kini - terakhir >= jeda;
    }

    static long throttleEfektif(long statik, long tersimpan) {
        return Math.max(statik, tersimpan);
    }

    static long throttleTersimpan(Context context) {
        return throttleTervalidasi(context,
                KEY_THROTTLE_ALARM_ELAPSED, KEY_THROTTLE_ALARM_WALL);
    }

    static void simpanThrottle(Context context, long kini) {
        terakhirAlarmElapsed = kini;
        TgBackup.simpanStempelThrottle(context, KEY_THROTTLE_ALARM_ELAPSED, kini);
        TgBackup.simpanStempelThrottle(context, KEY_THROTTLE_ALARM_WALL,
                System.currentTimeMillis());
    }

    static long throttleWaktuTersimpan(Context context) {
        return throttleTervalidasi(context,
                KEY_THROTTLE_WAKTU_ELAPSED, KEY_THROTTLE_WAKTU_WALL);
    }

    static void simpanThrottleWaktu(Context context, long kini) {
        terakhirWaktuElapsed = kini;
        TgBackup.simpanStempelThrottle(context, KEY_THROTTLE_WAKTU_ELAPSED, kini);
        TgBackup.simpanStempelThrottle(context, KEY_THROTTLE_WAKTU_WALL,
                System.currentTimeMillis());
    }

    @SuppressLint("ApplySharedPref")
    private static void mulaiBackup(Context context) {

        TgBackup.healkanStringPrefs(context);
        try {
            SharedPreferences cek = context.getSharedPreferences(
                    ServerService.PREFS, Context.MODE_PRIVATE);
            if (TgBackup.amanBoolean(cek, TgBackup.KEY_TG_AUTO, false)) {
                TgBackup.schedule(context, true);
            } else {

                return;
            }
        } catch (Exception ignored) {
            jadwalUlangBilaOtomatis(context);
            return;
        }

        boolean terkirim = false;
        try {
            terkirim = ServerService.backupNow(context);
        } catch (Exception ignored) {
        }
        if (!terkirim) {
            TgBackup.tandaiBackupTertunda(context);
        }
    }

    @Override
    @SuppressLint("ApplySharedPref")
    public void onReceive(Context context, Intent intent) {
        if (intent == null) {
            return;
        }
        String action = intent.getAction();
        boolean alarmApp = ACTION_HARIAN.equals(action) || ACTION_TUNDA.equals(action);

        boolean siaranWaktu = Intent.ACTION_DATE_CHANGED.equals(action)
                || Intent.ACTION_TIME_CHANGED.equals(action)
                || Intent.ACTION_TIMEZONE_CHANGED.equals(action);

        if (!alarmApp && !siaranWaktu) {
            return;
        }

        if (alarmApp && !TgBackup.rahasiaAlarmCocok(context, intent)) {
            if (ACTION_HARIAN.equals(action)) {
                jadwalUlangBilaOtomatis(context);
            } else if (ACTION_TUNDA.equals(action)) {
                TgBackup.tandaiBackupTertunda(context);
                ServerService.catatLog("[tg] Alarm tunda boot gugur: rahasia alarm"
                        + " tak cocok - backup ditandai tertunda.");
            }
            return;
        }

        if (TgBackup.sisaTungguBootMs(
                android.os.SystemClock.elapsedRealtime()) > 0) {
            boolean terjadwal = false;
            try {
                SharedPreferences cekAwal = context.getSharedPreferences(
                        ServerService.PREFS, Context.MODE_PRIVATE);
                if (TgBackup.amanBoolean(cekAwal, TgBackup.KEY_TG_AUTO, false)) {
                    terjadwal = TgBackup.jadwalTundaBoot(context);
                } else {
                    terjadwal = true;
                }
            } catch (Exception ignored) {
            }
            if (!terjadwal) {
                jadwalUlangBilaOtomatis(context);
                TgBackup.tandaiBackupTertunda(context);
                ServerService.catatLog("[tg] Alarm jatuh pada masa tunggu boot dan"
                        + " penjadwalan tunda gagal - rantai harian dipasang ulang"
                        + " dan backup ditandai tertunda.");
            }
            return;
        }

        if (intent.getBooleanExtra(TgBackup.EXTRA_TUNDA_BOOT, false)) {

            SharedPreferences sp = context.getSharedPreferences(
                    ServerService.PREFS, Context.MODE_PRIVATE);
            if (!TgBackup.amanBoolean(sp, TgBackup.KEY_TG_AUTO, false)) {
                return;
            }
            long last = TgBackup.stempelEfektif(
                    TgBackup.amanLong(sp, TgBackup.KEY_TG_LAST, 0),
                    TgBackup.amanString(sp, TgBackup.KEY_TG_LAST_BOT_ID, null),
                    TgBot.idBotDariToken(TgBackup.amanString(
                            sp, TgBackup.KEY_TG_TOKEN, "")));
            long kini = System.currentTimeMillis();
            if (TgBackup.bolehBackupSusulanBoot(last, kini)) {
                mulaiBackup(context);
            } else {

                jadwalUlangBilaOtomatis(context);
                if (!TgBackup.jamStbWajar(kini)
                        || (last > 0 && kini < last)) {
                    ServerService.catatLog("[tg] Backup susulan boot dilewati:"
                            + " tanggal & jam STB salah. Betulkan agar backup jalan.");
                    TgBackup.sendMessage(context, "Backup otomatis dilewati: tanggal & jam STB"
                            + " salah (terbaca "
                            + Updater.tanggalStbTerbaca() + "). Aktifkan Tanggal & waktu"
                            + " otomatis di Pengaturan STB - backup dicoba lagi otomatis.");
                    TgBackup.tandaiBackupTertunda(context);
                    int hitungTunda = TgBackup.amanInt(sp,
                            TgBackup.KEY_TUNDA_JAM_HITUNG, 0);
                    if (TgBackup.bolehJadwalUlangJamBackup(hitungTunda)) {
                        sp.edit().putInt(TgBackup.KEY_TUNDA_JAM_HITUNG,
                                hitungTunda + 1).apply();
                        TgBackup.jadwalTundaBackupMs(context,
                                TgBackup.TUNDA_ULANG_BACKUP_JAM_MS);
                    } else {
                        ServerService.catatLog("[tg] Alarm ulang backup karena jam"
                                + " salah dihentikan sesudah "
                                + TgBackup.BATAS_TUNDA_JAM_BACKUP
                                + "x - backup menunggu jam benar atau aplikasi dibuka.");
                    }
                } else {
                    ServerService.catatLog("[tg] Backup susulan boot dilewati:"
                            + " belum ganti hari.");
                }
            }
            return;
        }

        if (ACTION_TUNDA.equals(action)) {
            TgBackup.tandaiBackupTertunda(context);
            ServerService.catatLog("[tg] Alarm tunda tanpa penanda boot"
                    + " - backup ditandai tertunda, tak dijalankan.");
            return;
        }

        if (Intent.ACTION_DATE_CHANGED.equals(action)
                || Intent.ACTION_TIME_CHANGED.equals(action)
                || Intent.ACTION_TIMEZONE_CHANGED.equals(action)) {
            SharedPreferences sp = context.getSharedPreferences(
                    ServerService.PREFS, Context.MODE_PRIVATE);
            if (!TgBackup.amanBoolean(sp, TgBackup.KEY_TG_AUTO, false)) {
                return;
            }

            if (!lolosThrottleWaktu(context)) {
                return;
            }

            TgBackup.schedule(context, true);
            String token = Util.amanTrim(TgBackup.amanString(sp, TgBackup.KEY_TG_TOKEN, ""));
            String chat = Util.amanTrim(TgBackup.amanString(sp, TgBackup.KEY_TG_CHAT, ""));
            if (token.isEmpty() || chat.isEmpty()) {
                return;
            }
            long last = TgBackup.stempelEfektif(
                    TgBackup.amanLong(sp, TgBackup.KEY_TG_LAST, 0),
                    TgBackup.amanString(sp, TgBackup.KEY_TG_LAST_BOT_ID, null),
                    TgBot.idBotDariToken(token));
            long kini = System.currentTimeMillis();

            // Audit 1073: keputusan diekstrak ke fungsi murni
            // TgBackup.bolehKejarBackupWaktu (pagar Audit 1027
            // kini teruji JVM).
            if (TgBackup.bolehKejarBackupWaktu(last, kini)) {
                mulaiBackup(context);
            }
            return;
        }

        if (!lolosThrottleAlarm(context)) {
            if (ACTION_HARIAN.equals(action)) {
                jadwalUlangBilaOtomatis(context);
                TgBackup.tandaiBackupTertunda(context);
                ServerService.catatLog("[tg] Alarm harian tertelan throttle"
                        + " - rantai dipasang ulang dan backup ditandai tertunda.");
            }
            return;
        }
        mulaiBackup(context);
    }

    private boolean lolosThrottleAlarm(Context context) {
        long kiniElapsed = android.os.SystemClock.elapsedRealtime();
        long terakhir = throttleEfektif(terakhirAlarmElapsed, throttleTersimpan(context));
        boolean boleh = bolehAlarmJalan(kiniElapsed, terakhir, THROTTLE_ALARM_MS);
        if (boleh) {
            simpanThrottle(context, kiniElapsed);
        }
        return boleh;
    }

    private boolean lolosThrottleWaktu(Context context) {
        long kiniElapsed = android.os.SystemClock.elapsedRealtime();
        long terakhir = throttleEfektif(terakhirWaktuElapsed, throttleWaktuTersimpan(context));
        boolean boleh = bolehAlarmJalan(kiniElapsed, terakhir, THROTTLE_ALARM_MS);
        if (boleh) {
            simpanThrottleWaktu(context, kiniElapsed);
        }
        return boleh;
    }

    private static void jadwalUlangBilaOtomatis(Context context) {
        try {
            SharedPreferences sp = context.getSharedPreferences(
                    ServerService.PREFS, Context.MODE_PRIVATE);
            if (TgBackup.amanBoolean(sp, TgBackup.KEY_TG_AUTO, false)) {
                TgBackup.schedule(context, true);
            }
        } catch (Exception ignored) {
        }
    }
}
