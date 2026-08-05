package com.vaultwarden.android;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.PowerManager;

/** Pemicu polling perintah Telegram bot (AlarmManager tiap 60 detik). */
public class TgBotReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || !TgBot.ACTION_POLL.equals(intent.getAction())) {
            return;
        }
        final PowerManager.WakeLock wl = acquire(context);
        final PendingResult pr = goAsync();
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

    private static PowerManager.WakeLock acquire(Context context) {
        try {
            PowerManager pm = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
            PowerManager.WakeLock wl = pm.newWakeLock(
                    PowerManager.PARTIAL_WAKE_LOCK, "vaultwarden:tgbot");
            wl.acquire(60_000);
            return wl;
        } catch (Exception e) {
            return null;
        }
    }
}
