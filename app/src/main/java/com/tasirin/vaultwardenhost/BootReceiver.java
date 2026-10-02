package com.tasirin.vaultwardenhost;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.annotation.SuppressLint;
import android.content.SharedPreferences;

public class BootReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent != null ? intent.getAction() : null;
        if (Intent.ACTION_BOOT_COMPLETED.equals(action)
                || "android.intent.action.QUICKBOOT_POWERON".equals(action)
                || Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) {
            SharedPreferences sp = context.getSharedPreferences(ServerService.PREFS, Context.MODE_PRIVATE);
            TgBackup.healkanStringPrefs(context);
            TgBackup.migrateAutoPref(context);
            if (TgBackup.amanBoolean(sp, ServerService.KEY_AUTO_START, false)) {
                // mulaiService tak melempar: penolakan background dicatat di
                // dalam + ditandai KEY_START_TERTUNDA untuk susulan MainActivity.
                // Cek boolean agar boot yang ditolak tercatat di log.
                boolean jalan = false;
                try {
                    jalan = ServerService.start(context);
                } catch (Exception ignored) {
                    jalan = false;
                }
                if (!jalan) {
                    ServerService.catatLog("[app] Auto-start boot ditolak sistem"
                            + " - dijalankan susulan saat app dibuka.");
                }
            }
            // Pertahankan jadwal backup tengah malam setelah reboot;
            // kejar backup bila tanggal sudah berganti saat device mati.
            if (TgBackup.amanBoolean(sp, TgBackup.KEY_TG_AUTO, false)) {
                TgBackup.schedule(context, true);
                try {
                    String token = Util.amanTrim(TgBackup.amanString(sp, TgBackup.KEY_TG_TOKEN, ""));
                    String chat = Util.amanTrim(TgBackup.amanString(sp, TgBackup.KEY_TG_CHAT, ""));
                    String dataDir = TgBackup.amanString(sp, ServerService.KEY_DATA_DIR,
                            ServerService.dataDirBawaanSegar());
                    boolean dbAda = dataDir != null && !dataDir.trim().isEmpty()
                            && new java.io.File(dataDir.trim(), "db.sqlite3").exists();
                    // Jam saat boot belum tepercaya (bisa 1999/NTP belum sinkron):
                    // hanya jadwalkan tembakan 5 menit setelah hidup; keputusan
                    // backup (ganti hari + jam wajar) diambil AlarmReceiver
                    // saat menyala. Jadwal harian tak tersentuh.
                    if (!token.isEmpty() && !chat.isEmpty() && dbAda) {
                        if (!TgBackup.jadwalTundaBoot(context)) {
                            tandaiBackupTertunda(sp);
                        }
                    }
                } catch (Exception ignored) {
                    // Android 12+ bisa menolak start dari background: tandai agar
                    // MainActivity menjalankan susulan saat dibuka berikutnya.
                    try {
                        tandaiBackupTertunda(sp);
                    } catch (Exception ignored2) {
                    }
                }
            }
            // Remote kontrol bot tetap aktif setelah reboot
            TgBot.schedule(context);
        }
    }

    /** Flag daya-tahan boot: commit() sinkron agar tak hilang bila
     *  perangkat mati/kill tepat sesudah tulis (apply() async bisa lenyap). */
    @SuppressLint("ApplySharedPref")
    private static void tandaiBackupTertunda(SharedPreferences sp) {
        try {
            sp.edit().putBoolean(TgBackup.KEY_BACKUP_TERTUNDA, true).commit();
        } catch (Exception ignored) {
        }
    }
}
