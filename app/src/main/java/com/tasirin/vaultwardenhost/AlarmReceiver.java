package com.tasirin.vaultwardenhost;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

/** Pemicu backup terjadwal — jalan walau app tidak dibuka.
 *  Dua sumber pemicu:
 *  (a) alarm tengah malam dari TgBackup.schedule (saat tanggal berganti);
 *  (b) siaran ganti tanggal/jam/zona (mis. jam diubah manual) — jadwal
 *  dihitung ulang lalu backup susulan bila hari sudah berganti. */
public class AlarmReceiver extends BroadcastReceiver {

    private static void mulaiBackup(Context context) {
        try {
            ServerService.backupNow(context);
        } catch (Exception ignored) {
            // Batasan start service versi Android baru - jadwal berikutnya coba lagi.
        }
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent != null ? intent.getAction() : null;
        if (Intent.ACTION_DATE_CHANGED.equals(action)
                || Intent.ACTION_TIME_CHANGED.equals(action)
                || Intent.ACTION_TIMEZONE_CHANGED.equals(action)) {
            SharedPreferences sp = context.getSharedPreferences(
                    ServerService.PREFS, Context.MODE_PRIVATE);
            if (!sp.getBoolean(TgBackup.KEY_TG_AUTO, false)) {
                return;
            }
            // Hitung ulang tengah malam berikutnya setelah jam berubah.
            TgBackup.schedule(context, true);
            String token = sp.getString(TgBackup.KEY_TG_TOKEN, "").trim();
            String chat = sp.getString(TgBackup.KEY_TG_CHAT, "").trim();
            if (token.isEmpty() || chat.isEmpty()) {
                return;
            }
            long last = sp.getLong(TgBackup.KEY_TG_LAST, 0);
            if (TgBackup.sudahGantiHari(last, System.currentTimeMillis())) {
                mulaiBackup(context);
            }
            return;
        }
        mulaiBackup(context);
    }
}
