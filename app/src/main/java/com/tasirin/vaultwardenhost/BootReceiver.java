package com.tasirin.vaultwardenhost;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.annotation.SuppressLint;
import android.content.SharedPreferences;

public class BootReceiver extends BroadcastReceiver {

    static final long THROTTLE_BOOT_MS = 60_000;
    private static volatile long terakhirBootElapsed = 0;

    static final String KEY_THROTTLE_BOOT_ELAPSED = "boot_throttle_elapsed";

    private static final Object KUNCI_BOOT = new Object();

    static final String AKSI_GANTI_PAKET = "android.intent.action.MY_PACKAGE_REPLACED";

    static boolean perluThrottleBoot(String action) {
        return !AKSI_GANTI_PAKET.equals(action);
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent != null ? intent.getAction() : null;

        if (!Intent.ACTION_BOOT_COMPLETED.equals(action)
                && !Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) {
            return;
        }
        final Context ctx = context.getApplicationContext();

        final boolean gantiPaket = Intent.ACTION_MY_PACKAGE_REPLACED.equals(action);

        final PendingResult pending = goAsync();
        Runnable kerja = () -> {
            try {
                if (perluThrottleBoot(action)) {
                    long kiniElapsed = android.os.SystemClock.elapsedRealtime();
                    synchronized (KUNCI_BOOT) {
                        long terakhirBoot = Math.max(terakhirBootElapsed,
                                bacaThrottleBoot(ctx));
                        if (!AlarmReceiver.bolehAlarmJalan(kiniElapsed, terakhirBoot,
                                THROTTLE_BOOT_MS)) {

                            terakhirBootElapsed = kiniElapsed;
                            simpanThrottleBoot(ctx, kiniElapsed);
                            return;
                        }
                        terakhirBootElapsed = kiniElapsed;
                        simpanThrottleBoot(ctx, kiniElapsed);
                    }
                }
                SharedPreferences sp = ctx.getSharedPreferences(
                        ServerService.PREFS, Context.MODE_PRIVATE);
                TgBackup.healkanStringPrefs(ctx);
                TgBackup.migrateAutoPref(ctx);
                if (!gantiPaket && TgBackup.amanBoolean(sp, ServerService.KEY_AUTO_START, false)) {

                    boolean jalan = false;
                    try {
                        jalan = ServerService.start(ctx);
                    } catch (Exception ignored) {
                        jalan = false;
                    }
                    if (!jalan) {
                        ServerService.catatLog("[app] Auto-start boot ditolak sistem"
                                + " - dijalankan susulan saat app dibuka.");
                    }
                }

                if (TgBackup.amanBoolean(sp, TgBackup.KEY_TG_AUTO, false)) {
                    TgBackup.schedule(ctx, true);
                    try {
                        String token = Util.amanTrim(TgBackup.amanString(sp, TgBackup.KEY_TG_TOKEN, ""));
                        String chat = Util.amanTrim(TgBackup.amanString(sp, TgBackup.KEY_TG_CHAT, ""));
                        String dataDir = TgBackup.amanString(sp, ServerService.KEY_DATA_DIR,
                                ServerService.dataDirBawaanSegar());

                        String dRapi = dataDir == null ? "" : dataDir.trim();
                        boolean dbAda = !dRapi.isEmpty()
                                && new java.io.File(dRapi, "db.sqlite3").exists();

                        if (!token.isEmpty() && !chat.isEmpty() && dbAda) {
                            if (!TgBackup.jadwalTundaBoot(ctx)) {
                                tandaiBackupTertunda(sp);
                            }
                        }
                    } catch (Exception ignored) {

                        try {
                            tandaiBackupTertunda(sp);
                        } catch (Exception ignored2) {
                        }
                    }
                }

                TgBot.schedule(ctx);
            } catch (Exception e) {

                ServerService.catatLog("[app] Penanganan boot gagal: "
                        + e.getClass().getSimpleName());
            } finally {
                pending.finish();
            }
        };
        if (!Util.jalankanBg(kerja)) {

            kerja.run();
        }
    }

    static long bacaThrottleBoot(Context context) {
        return TgBackup.bacaStempelThrottle(context, KEY_THROTTLE_BOOT_ELAPSED);
    }

    static void simpanThrottleBoot(Context context, long kini) {
        TgBackup.simpanStempelThrottle(context, KEY_THROTTLE_BOOT_ELAPSED, kini);
    }

    @SuppressLint("ApplySharedPref")
    private static void tandaiBackupTertunda(SharedPreferences sp) {
        try {
            sp.edit().putBoolean(TgBackup.KEY_BACKUP_TERTUNDA, true).commit();
        } catch (Exception ignored) {
        }
    }
}
