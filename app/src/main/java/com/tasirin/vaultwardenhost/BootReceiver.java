package com.tasirin.vaultwardenhost;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

public class BootReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent != null ? intent.getAction() : null;
        if (Intent.ACTION_BOOT_COMPLETED.equals(action)
                || "android.intent.action.QUICKBOOT_POWERON".equals(action)
                || Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) {
            SharedPreferences sp = context.getSharedPreferences(ServerService.PREFS, Context.MODE_PRIVATE);
            TgBackup.migrateAutoPref(context);
            if (sp.getBoolean(ServerService.KEY_AUTO_START, false)) {
                try {
                    ServerService.start(context);
                } catch (Exception ignored) {
                    // Android 12+ bisa menolak start dari background - user tekan Start manual.
                }
            }
            // Pertahankan jadwal backup tengah malam setelah reboot;
            // kejar backup bila tanggal sudah berganti saat device mati.
            if (sp.getBoolean(TgBackup.KEY_TG_AUTO, false)) {
                TgBackup.schedule(context, true);
                try {
                    String token = sp.getString(TgBackup.KEY_TG_TOKEN, "").trim();
                    String chat = sp.getString(TgBackup.KEY_TG_CHAT, "").trim();
                    String dataDir = sp.getString(ServerService.KEY_DATA_DIR,
                            ServerService.DEFAULT_DATA_DIR);
                    long last = sp.getLong(TgBackup.KEY_TG_LAST, 0);
                    boolean dbAda = dataDir != null && !dataDir.trim().isEmpty()
                            && new java.io.File(dataDir.trim(), "db.sqlite3").exists();
                    if (!token.isEmpty() && !chat.isEmpty() && dbAda
                            && TgBackup.sudahGantiHari(last, System.currentTimeMillis())) {
                        ServerService.backupNow(context);
                    }
                } catch (Exception ignored) {
                    // Backup susulan gagal - alarm tengah malam yang urus berikutnya.
                }
            }
            // Remote kontrol bot tetap aktif setelah reboot
            TgBot.schedule(context);
        }
    }
}
