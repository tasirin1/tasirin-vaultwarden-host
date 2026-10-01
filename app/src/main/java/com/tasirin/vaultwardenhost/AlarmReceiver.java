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
            SharedPreferences cek = context.getSharedPreferences(
                    ServerService.PREFS, Context.MODE_PRIVATE);
            if (TgBackup.amanBoolean(cek, TgBackup.KEY_TG_AUTO, false)) {
                TgBackup.schedule(context, true);
            } else {
                // Auto dimatikan: alarm yang telanjur terjadwal tak boleh mengunggah.
                return;
            }
        } catch (Exception ignored) {
            // Prefs tak terbaca: gagal tertutup, jangan backup buta.
            return;
        }
        // mulaiService menelan penolakan di dalam (return false), jadi
        // try/catch di sini tak cukup: cek status kembalian agar susulan
        // benar-benar terjangkau saat Android 12+ menolak start background.
        boolean terkirim = false;
        try {
            terkirim = ServerService.backupNow(context);
        } catch (Exception ignored) {
        }
        if (!terkirim) {
            // Android 12+ menolak start dari background: tandai agar
            // MainActivity menjalankan susulan saat dibuka berikutnya.
            try {
                context.getSharedPreferences(ServerService.PREFS, Context.MODE_PRIVATE)
                        .edit().putBoolean(TgBackup.KEY_BACKUP_TERTUNDA, true).apply();
            } catch (Exception ignored2) {
            }
        }
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        // Proses receiver bisa hidup tanpa Activity/Service: sembuhkan prefs
        // bertipe salah dulu agar baca mentah di bawah tak ClassCastException.
        TgBackup.healkanStringPrefs(context);
        // STB baru hidup (mis. alarm tengah malam menyala sesaat setelah boot):
        // tunda sampai 5 menit agar sistem stabil, lalu nilai ulang.
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
        // Alarm susulan boot: putuskan SEKARANG dengan jam yang sudah stabil —
        // jalan hanya bila sudah ganti hari; tolak jam reset/mundur (1999).
        if (intent != null && intent.getBooleanExtra(TgBackup.EXTRA_TUNDA_BOOT, false)) {
            SharedPreferences sp = context.getSharedPreferences(
                    ServerService.PREFS, Context.MODE_PRIVATE);
            long last = TgBackup.amanLong(sp, TgBackup.KEY_TG_LAST, 0);
            long kini = System.currentTimeMillis();
            if (TgBackup.bolehBackupSusulanBoot(last, kini)) {
                mulaiBackup(context);
            } else {
                // Jaga jadwal harian tetap ada walau susulan dilewati.
                try {
                    if (TgBackup.amanBoolean(sp, TgBackup.KEY_TG_AUTO, false)) {
                        TgBackup.schedule(context, true);
                    }
                } catch (Exception ignored) {
                }
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
        String action = intent != null ? intent.getAction() : null;
        // Manifest mendaftar TIME_SET; TIME_CHANGED tak pernah dikirim sistem
        // sehingga utak-atik jam dulu jatuh ke mulaiBackup tanpa batas 1 jam.
        // TIME_SET tak punya konstanta Intent (literal saja), samakan dengan manifest.
        if (Intent.ACTION_DATE_CHANGED.equals(action)
                || Intent.ACTION_TIME_CHANGED.equals(action)
                || "android.intent.action.TIME_SET".equals(action)
                || Intent.ACTION_TIMEZONE_CHANGED.equals(action)) {
            SharedPreferences sp = context.getSharedPreferences(
                    ServerService.PREFS, Context.MODE_PRIVATE);
            if (!TgBackup.amanBoolean(sp, TgBackup.KEY_TG_AUTO, false)) {
                return;
            }
            // Hitung ulang tengah malam berikutnya setelah jam berubah.
            TgBackup.schedule(context, true);
            String token = Util.amanTrim(TgBackup.amanString(sp, TgBackup.KEY_TG_TOKEN, ""));
            String chat = Util.amanTrim(TgBackup.amanString(sp, TgBackup.KEY_TG_CHAT, ""));
            if (token.isEmpty() || chat.isEmpty()) {
                return;
            }
            long last = TgBackup.amanLong(sp, TgBackup.KEY_TG_LAST, 0);
            long kini = System.currentTimeMillis();
            // Abaikan utak-atik jam/zona manual yang memicu siaran beruntun:
            // backup <1 jam lalu tak perlu diulang hanya karena jam diutak-atik.
            // Jam mundur (kini < last) tak boleh melewatkan backup: biarkan
            // sudahGantiHari + dedup harian yang memutuskan.
            if (last > 0 && kini >= last && kini - last < 3600_000L) {
                return;
            }
            if (TgBackup.sudahGantiHari(last, kini)) {
                mulaiBackup(context);
            }
            return;
        }
        mulaiBackup(context);
    }
}
