package com.tasirin.vaultwardenhost;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.PowerManager;

public class TgBotReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || !TgBot.ACTION_POLL.equals(intent.getAction())) {
            return;
        }

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

        if (!adaKonfig(context)) {
            return;
        }

        final PendingResult pr;
        try {
            pr = goAsync();
        } catch (Exception e) {
            return;
        }
        final PowerManager.WakeLock wl = acquire(context);
        Runnable tugas = () -> {
            try {
                TgBot.pollOnce(context);
            } finally {
                selesaikan(pr, wl);
            }
        };

        if (!TgBot.cobaJalankanBg(tugas)) {
            boolean jalan = false;
            try {
                Thread t = new Thread(tugas, "vw-tgbot");
                t.setDaemon(true);
                t.start();
                jalan = true;
            } catch (Exception ignored) {
            }
            if (!jalan) {

                selesaikan(pr, wl);
                ServerService.catatLog("[tg] Poll dilewati: thread bot gagal dibuat.");
            }
        }
    }

    private static void selesaikan(PendingResult pr, PowerManager.WakeLock wl) {
        try {
            pr.finish();
        } finally {
            if (wl != null && wl.isHeld()) {
                wl.release();
            }
        }
    }

    static boolean konfigBotSiap(String token, String chat) {
        String t = Util.amanTrim(token);
        String c = Util.amanTrim(chat);
        return t != null && !t.isEmpty() && c != null && !c.isEmpty();
    }

    private static boolean adaKonfig(Context context) {
        try {
            android.content.SharedPreferences sp = context.getSharedPreferences(
                    ServerService.PREFS, Context.MODE_PRIVATE);
            String token = TgBackup.amanString(sp, TgBackup.KEY_TG_TOKEN, "");
            String chat = TgBackup.amanString(sp, TgBackup.KEY_TG_CHAT, "");
            return konfigBotSiap(token, chat);
        } catch (Exception e) {
            return false;
        }
    }

    private static PowerManager.WakeLock acquire(Context context) {
        try {
            PowerManager pm = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
            PowerManager.WakeLock wl = pm.newWakeLock(
                    PowerManager.PARTIAL_WAKE_LOCK, "vaultwarden:tgbot");

            wl.acquire(10L * 60L * 1000L);
            return wl;
        } catch (Exception e) {
            return null;
        }
    }
}
