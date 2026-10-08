package com.tasirin.vaultwardenhost;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.PowerManager;

/** Pemicu polling perintah Telegram bot (AlarmManager tiap 20 detik). */
public class TgBotReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || !TgBot.ACTION_POLL.equals(intent.getAction())) {
            return;
        }
        // Anti-spoof selaras AlarmReceiver: action milik app wajib membawa
        // rahasia alarm yang cocok. Alarm lawas pra-rahasia tak lagi
        // diizinkan sekali jalan (lubang kompat: satu spoof tiap
        // fresh-install/update lolos): langsung dijadwalkan ulang
        // ber-rahasia agar tick berikut valid, lalu intent ini ditolak.
        // Galat baca prefs pun fail-closed (tolak) agar polling buta
        // tanpa verifikasi tak pernah jalan.
        try {
            if (!TgBackup.rahasiaAlarmCocok(context, intent)) {
                try {
                    TgBot.schedule(context);
                } catch (Exception ignored) {
                }
                return;
            }
        } catch (Exception ignored) {
            return;
        }
        TgBackup.healkanStringPrefs(context);
        // Tanpa token/chat polling pasti nir-op: keluar sebelum pegang
        // wakelock agar tak membangunkan perangkat sia-sia tiap 20 detik.
        if (!adaKonfig(context)) {
            return;
        }
        // goAsync dulu, wakelock kemudian: bila goAsync melempar, belum ada
        // wakelock yang bocor.
        final PendingResult pr;
        try {
            pr = goAsync();
        } catch (Exception e) {
            return;
        }
        final PowerManager.WakeLock wl = acquire(context);
        new Thread(() -> {
            try {
                TgBot.pollOnce(context);
            } finally {
                try {
                    pr.finish();
                } finally {
                    if (wl != null && wl.isHeld()) {
                        wl.release();
                    }
                }
            }
        }, "vw-tgbot").start();
    }

    private static boolean adaKonfig(Context context) {
        try {
            android.content.SharedPreferences sp = context.getSharedPreferences(
                    ServerService.PREFS, Context.MODE_PRIVATE);
            String token = Util.amanTrim(TgBackup.amanString(sp, TgBackup.KEY_TG_TOKEN, ""));
            String chat = Util.amanTrim(TgBackup.amanString(sp, TgBackup.KEY_TG_CHAT, ""));
            return !token.isEmpty() && !chat.isEmpty();
        } catch (Exception e) {
            return false;
        }
    }

    private static PowerManager.WakeLock acquire(Context context) {
        try {
            PowerManager pm = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
            PowerManager.WakeLock wl = pm.newWakeLock(
                    PowerManager.PARTIAL_WAKE_LOCK, "vaultwarden:tgbot");
            // Harus melampaui read timeout HTTP (35 dtk) agar polling tak
            // kehilangan wakelock sebelum respons long-poll tiba.
            wl.acquire(60_000);
            return wl;
        } catch (Exception e) {
            return null;
        }
    }
}
