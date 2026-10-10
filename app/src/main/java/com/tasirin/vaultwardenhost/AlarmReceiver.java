package com.tasirin.vaultwardenhost;

import android.annotation.SuppressLint;
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

    /** Action alarm harian milik app (lihat TgBackup.schedule). */
    public static final String ACTION_HARIAN =
            "com.tasirin.vaultwardenhost.ALARM_HARIAN";
    /** Action tunda-boot milik app (lihat TgBackup.jadwalTundaBoot). */
    public static final String ACTION_TUNDA =
            "com.tasirin.vaultwardenhost.ALARM_TUNDA_BOOT";
    /** Extra rahasia anti-spoof untuk action milik app (lihat TgBackup.rahasiaAlarm).
     *  Siaran sistem (DATE_CHANGED/TIME_SET/TIMEZONE_CHANGED) tak membawa ini. */
    public static final String EXTRA_RAHASIA =
            "com.tasirin.vaultwardenhost.ALARM_SECRET";
    /** Throttle spam explicit-intent: service sudah dedup harian, tapi tiap
     *  siaran palsu tetap membangunkan perangkat + start service. Berlaku
     *  untuk jalur alarm umum dan ganti tanggal (keduanya tanpa rahasia bila
     *  dari sistem); susulan boot punya rahasia sendiri sehingga tak ikut. */
    static final long THROTTLE_ALARM_MS = 60_000;
    private static volatile long terakhirAlarmElapsed = 0;
    /** Penanda throttle di prefs (tahan mati proses): statik saja reset tiap
     *  proses mati sehingga spam explicit-intent lolos lagi. */
    static final String KEY_THROTTLE_ALARM_ELAPSED = "alarm_throttle_elapsed";

    /** Murni waktu: true bila alarm umum boleh jalan (di luar jeda spam). */
    static boolean bolehAlarmJalan(long kini, long terakhir, long jeda) {
        return terakhir <= 0 || kini < terakhir || kini - terakhir >= jeda;
    }

    /** Gabung penanda statik + tersimpan (murni): yang terbesar dipakai agar
     *  throttle tetap berlaku walau proses mati (statik reset ke 0). */
    static long throttleEfektif(long statik, long tersimpan) {
        return Math.max(statik, tersimpan);
    }

    /** Baca penanda throttle tersimpan (0 bila tak ada/rusak). */
    static long throttleTersimpan(Context context) {
        try {
            SharedPreferences sp = context.getSharedPreferences(
                    ServerService.PREFS, Context.MODE_PRIVATE);
            return TgBackup.amanLong(sp, KEY_THROTTLE_ALARM_ELAPSED, 0);
        } catch (Exception e) {
            return 0;
        }
    }

    /** Simpan penanda throttle (best-effort, tahan mati proses).
     *  Sengaja apply() bukan commit(): dipanggil dari onReceive di main thread
     *  sehingga commit() sinkron berisiko ANR; throttle yang hilang sesaat hanya
     *  berarti satu bangun ekstra, bukan korupsi data. */
    static void simpanThrottle(Context context, long kini) {
        terakhirAlarmElapsed = kini;
        try {
            context.getSharedPreferences(ServerService.PREFS, Context.MODE_PRIVATE)
                    .edit().putLong(KEY_THROTTLE_ALARM_ELAPSED, kini).apply();
        } catch (Exception ignored) {
        }
    }

    /** commit() di bawah disengaja (sinkron anti-hilang, lihat komentar) — bukan apply(). */
    @SuppressLint("ApplySharedPref")
    private static void mulaiBackup(Context context) {
        // Heal di sini (bukan onReceive): hanya jalur yang benar-benar tulis
        // prefs yang membayar parse penuh; baca di atas pakai aman*.
        TgBackup.healkanStringPrefs(context);
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
            // commit() sinkron (bukan apply()): flag daya-tahan wajib awet di
            // disk sebelum reboot/kill, selaras BootReceiver/offset bot.
            try {
                context.getSharedPreferences(ServerService.PREFS, Context.MODE_PRIVATE)
                        .edit().putBoolean(TgBackup.KEY_BACKUP_TERTUNDA, true).commit();
            } catch (Exception ignored2) {
            }
        }
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        // Tanpa heal penuh di sini: getAll() mem-parse ulang seluruh XML prefs
        // tiap alarm (termasuk spoof/throttle yang kembali dini); baca aman*
        // sembuh-sendiri per kunci, heal jalan di mulaiBackup sebelum tulis.
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
            // Action milik app wajib membawa rahasia alarm: tanpa ini app lain
            // bisa memicu backup+upload via explicit-intent spoof.
            if (!TgBackup.rahasiaAlarmCocok(context, intent)) {
                return;
            }
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
        // Intent.ACTION_TIME_CHANGED nilainya "android.intent.action.TIME_SET"
        // (sama dengan literal di manifest) sehingga satu cek cukup.
        if (Intent.ACTION_DATE_CHANGED.equals(action)
                || Intent.ACTION_TIME_CHANGED.equals(action)
                || Intent.ACTION_TIMEZONE_CHANGED.equals(action)) {
            SharedPreferences sp = context.getSharedPreferences(
                    ServerService.PREFS, Context.MODE_PRIVATE);
            if (!TgBackup.amanBoolean(sp, TgBackup.KEY_TG_AUTO, false)) {
                return;
            }
            // Siaran sistem tak ber-rahasia sehingga bisa dipalsu via
            // explicit-intent: throttle 60 dtk seperti jalur alarm umum
            // (ganti tanggal legit jarang beruntun).
            long kiniElapsedTgl = android.os.SystemClock.elapsedRealtime();
            if (!bolehAlarmJalan(kiniElapsedTgl,
                    throttleEfektif(terakhirAlarmElapsed, throttleTersimpan(context)),
                    THROTTLE_ALARM_MS)) {
                // Jendela geser: spam beruntun tak lolos tiap 60 dtk.
                // Sekali-lolos pasca-reboot tetap utuh (hanya tolak yang geser).
                simpanThrottle(context, kiniElapsedTgl);
                return;
            }
            simpanThrottle(context, kiniElapsedTgl);
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
        // Jalur alarm umum: terima action milik app + bare intent lawas
        // (transisi satu siklus alarm). Action asing diabaikan. Throttle
        // 60 dtk menumpulkan spam explicit-intent: jadwal tetap disegar
        // oleh mulaiBackup, service sudah dedup harian di dalamnya.
        // Hanya action milik app yang diterima (bare intent lawas + action asing
        // ditolak: alarm baru selalu ber-action + ber-rahasia, dan pembatalan
        // alarm lawas tak butuh receiver ini). Rahasia menutup spoof explicit-intent
        // dari app lain yang sebelumnya bisa memicu backup+upload tiap 60 detik.
        if (!ACTION_HARIAN.equals(action) && !ACTION_TUNDA.equals(action)) {
            return;
        }
        if (!TgBackup.rahasiaAlarmCocok(context, intent)) {
            return;
        }
        long kiniElapsed = android.os.SystemClock.elapsedRealtime();
        long terakhir = throttleEfektif(terakhirAlarmElapsed, throttleTersimpan(context));
        if (!bolehAlarmJalan(kiniElapsed, terakhir, THROTTLE_ALARM_MS)) {
            // Jendela geser seperti jalur tanggal (hanya intent ber-rahasia
            // yang sampai sini, jadi penggeser pasti alarm sah yang beruntun).
            simpanThrottle(context, kiniElapsed);
            return;
        }
        simpanThrottle(context, kiniElapsed);
        mulaiBackup(context);
    }
}
