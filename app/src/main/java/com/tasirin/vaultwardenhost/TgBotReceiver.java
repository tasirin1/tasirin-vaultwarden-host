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
        // Tanpa heal penuh di sini: getAll() mem-parse ulang seluruh XML prefs
        // tiap 20 detik di STB lama; baca aman* di bawah sembuh-sendiri per
        // kunci bila korup, dan heal penuh tetap jalan di onCreate/start.
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
        Runnable tugas = () -> {
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
        };
        // Pool bot bersama dulu (hemat thread baru tiap 20 dtk di STB 1 GB);
        // fallback thread sendiri bila pool penuh agar goAsync/wakelock
        // tetap selesai dan polling berikut tak macet.
        if (!TgBot.cobaJalankanBg(tugas)) {
            try {
                Thread t = new Thread(tugas, "vw-tgbot");
                t.setDaemon(true);
                t.start();
            } catch (Exception e) {
                tugas.run();
            }
        }
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
            // Melampaui long-poll 8 dtk + margin jaring: cukup 25 dtk
            // (sebelumnya 60 dtk menahan CPU sia-sia tiap 20 dtk di STB).
            wl.acquire(25_000);
            return wl;
        } catch (Exception e) {
            return null;
        }
    }
}
