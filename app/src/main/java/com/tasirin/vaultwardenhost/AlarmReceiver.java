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

    static final String KEY_THROTTLE_ALARM_ELAPSED = "alarm_throttle_elapsed";

    static boolean bolehAlarmJalan(long kini, long terakhir, long jeda) {
        return terakhir <= 0 || kini < terakhir || kini - terakhir >= jeda;
    }

    static long throttleEfektif(long statik, long tersimpan) {
        return Math.max(statik, tersimpan);
    }

    static long throttleTersimpan(Context context) {
        return TgBackup.bacaStempelThrottle(context, KEY_THROTTLE_ALARM_ELAPSED);
    }

    static void simpanThrottle(Context context, long kini) {
        terakhirAlarmElapsed = kini;
        TgBackup.simpanStempelThrottle(context, KEY_THROTTLE_ALARM_ELAPSED, kini);
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

            try {
                context.getSharedPreferences(ServerService.PREFS, Context.MODE_PRIVATE)
                        .edit().putBoolean(TgBackup.KEY_BACKUP_TERTUNDA, true).commit();
            } catch (Exception ignored2) {
            }
        }
    }

    @Override
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
            }
            return;
        }

        if (TgBackup.sisaTungguBootMs(
                android.os.SystemClock.elapsedRealtime()) > 0) {
            try {
                SharedPreferences cekAwal = context.getSharedPreferences(
                        ServerService.PREFS, Context.MODE_PRIVATE);
                if (TgBackup.amanBoolean(cekAwal, TgBackup.KEY_TG_AUTO, false)) {
                    TgBackup.jadwalTundaBoot(context);
                }
            } catch (Exception ignored) {
            }
            return;
        }

        if (intent.getBooleanExtra(TgBackup.EXTRA_TUNDA_BOOT, false)) {

            SharedPreferences sp = context.getSharedPreferences(
                    ServerService.PREFS, Context.MODE_PRIVATE);
            long last = TgBackup.amanLong(sp, TgBackup.KEY_TG_LAST, 0);
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
                            + " otomatis di Pengaturan STB, lalu backup manual.");
                } else {
                    ServerService.catatLog("[tg] Backup susulan boot dilewati:"
                            + " belum ganti hari.");
                }
            }
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

            if (!lolosThrottleAlarm(context)) {
                return;
            }

            TgBackup.schedule(context, true);
            String token = Util.amanTrim(TgBackup.amanString(sp, TgBackup.KEY_TG_TOKEN, ""));
            String chat = Util.amanTrim(TgBackup.amanString(sp, TgBackup.KEY_TG_CHAT, ""));
            if (token.isEmpty() || chat.isEmpty()) {
                return;
            }
            long last = TgBackup.amanLong(sp, TgBackup.KEY_TG_LAST, 0);
            long kini = System.currentTimeMillis();

            if (last > 0 && kini >= last && kini - last < 3600_000L) {
                return;
            }
            if (TgBackup.sudahGantiHari(last, kini)) {
                mulaiBackup(context);
            }
            return;
        }

        if (!lolosThrottleAlarm(context)) {
            if (ACTION_HARIAN.equals(action)) {
                jadwalUlangBilaOtomatis(context);
            }
            return;
        }
        mulaiBackup(context);
    }

    private boolean lolosThrottleAlarm(Context context) {
        long kiniElapsed = android.os.SystemClock.elapsedRealtime();
        long terakhir = throttleEfektif(terakhirAlarmElapsed, throttleTersimpan(context));
        boolean boleh = bolehAlarmJalan(kiniElapsed, terakhir, THROTTLE_ALARM_MS);
        simpanThrottle(context, kiniElapsed);
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
