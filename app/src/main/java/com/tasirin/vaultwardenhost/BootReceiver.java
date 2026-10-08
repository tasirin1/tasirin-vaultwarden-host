package com.tasirin.vaultwardenhost;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.annotation.SuppressLint;
import android.content.SharedPreferences;

public class BootReceiver extends BroadcastReceiver {

    /** Throttle spoof BOOT_COMPLETED explicit-intent (boot legit sekali jalan).
     *  Siaran sistem tak ber-rahasia, jadi tanpa ini app lain bisa memicu
     *  auto-start + jadwal backup berulang-ulang. */
    static final long THROTTLE_BOOT_MS = 60_000;
    private static volatile long terakhirBootElapsed = 0;
    /** Penanda throttle di prefs (tahan mati proses): statik saja reset tiap
     *  proses mati sehingga spoof BOOT_COMPLETED lolos lagi. */
    static final String KEY_THROTTLE_BOOT_ELAPSED = "boot_throttle_elapsed";

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent != null ? intent.getAction() : null;
        if (Intent.ACTION_BOOT_COMPLETED.equals(action)
                || "android.intent.action.QUICKBOOT_POWERON".equals(action)
                || Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) {
            long kiniElapsed = android.os.SystemClock.elapsedRealtime();
            long terakhirBoot = Math.max(terakhirBootElapsed,
                    bacaThrottleBoot(context));
            if (!AlarmReceiver.bolehAlarmJalan(kiniElapsed, terakhirBoot, THROTTLE_BOOT_MS)) {
                // Jendela geser agar spoof BOOT_COMPLETED beruntun tak lolos
                // tiap 60 dtk; boot legit berjarak menit sehingga tak terdampak.
                terakhirBootElapsed = kiniElapsed;
                simpanThrottleBoot(context, kiniElapsed);
                return;
            }
            terakhirBootElapsed = kiniElapsed;
            simpanThrottleBoot(context, kiniElapsed);
            SharedPreferences sp = context.getSharedPreferences(ServerService.PREFS, Context.MODE_PRIVATE);
            TgBackup.healkanStringPrefs(context);
            TgBackup.migrateAutoPref(context);
            // MY_PACKAGE_REPLACED bukan perintah start: update APK tak boleh
            // menyalakan server yang sengaja di-Stop user. Hanya BOOT yang
            // boleh auto-start; selepas update cukup jadwalkan ulang alarm/bot.
            boolean gantiPaket = Intent.ACTION_MY_PACKAGE_REPLACED.equals(action);
            if (!gantiPaket && TgBackup.amanBoolean(sp, ServerService.KEY_AUTO_START, false)) {
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

    /** Baca penanda throttle boot tersimpan (0 bila tak ada/rusak). */
    static long bacaThrottleBoot(Context context) {
        try {
            android.content.SharedPreferences sp = context.getSharedPreferences(
                    ServerService.PREFS, Context.MODE_PRIVATE);
            return TgBackup.amanLong(sp, KEY_THROTTLE_BOOT_ELAPSED, 0);
        } catch (Exception e) {
            return 0;
        }
    }

    /** Simpan penanda throttle boot (best-effort, tahan mati proses). */
    static void simpanThrottleBoot(Context context, long kini) {
        try {
            context.getSharedPreferences(ServerService.PREFS, Context.MODE_PRIVATE)
                    .edit().putLong(KEY_THROTTLE_BOOT_ELAPSED, kini).apply();
        } catch (Exception ignored) {
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
