package com.tasirin.vaultwardenhost;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.annotation.SuppressLint;
import android.content.SharedPreferences;

public class BootReceiver extends BroadcastReceiver {

    static final long THROTTLE_BOOT_MS = 60_000;
    private static volatile long terakhirBootElapsed = 0;
    private static volatile long terakhirBootWall = 0;

    static final String KEY_THROTTLE_BOOT_ELAPSED = "boot_throttle_elapsed";
    static final String KEY_THROTTLE_BOOT_WALL = "boot_throttle_wall";
    static final String KEY_THROTTLE_BOOT_COUNT = "boot_throttle_count";
    private static volatile long terakhirBootCount = 0;

    static boolean throttleBootMenahan(long kiniElapsed, long terakhirElapsed,
                                       long kiniWall, long terakhirWall, long jeda) {
        return throttleBootMenahan(kiniElapsed, terakhirElapsed,
                kiniWall, terakhirWall, jeda, 0, 0);
    }

    static boolean throttleBootMenahan(long kiniElapsed, long terakhirElapsed,
                                       long kiniWall, long terakhirWall, long jeda,
                                       long kiniCount, long terakhirCount) {
        if (kiniCount > 0 && terakhirCount > 0) {
            if (kiniCount != terakhirCount) {
                return false;
            }
            if (terakhirElapsed <= 0) {
                return false;
            }
            if (kiniElapsed < terakhirElapsed) {
                return false;
            }
            return kiniElapsed - terakhirElapsed < jeda;
        }
        if (terakhirElapsed <= 0) {
            return false;
        }
        if (kiniElapsed < terakhirElapsed) {
            return false;
        }
        if (kiniElapsed - terakhirElapsed >= jeda) {
            return false;
        }
        if (terakhirWall > 0 && kiniWall > 0) {
            long majuDinding = kiniWall - terakhirWall;
            if (majuDinding < 0) {
                return false;
            }
            if (majuDinding - (kiniElapsed - terakhirElapsed) > jeda) {
                return false;
            }
        }
        return true;
    }

    private static final Object KUNCI_BOOT = new Object();

    static final String AKSI_GANTI_PAKET = "android.intent.action.MY_PACKAGE_REPLACED";

    static boolean perluThrottleBoot(String action) {
        return !AKSI_GANTI_PAKET.equals(action);
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent != null ? intent.getAction() : null;

        if (!Intent.ACTION_BOOT_COMPLETED.equals(action)
                && !Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) {
            return;
        }
        final Context ctx = context.getApplicationContext();

        final boolean gantiPaket = Intent.ACTION_MY_PACKAGE_REPLACED.equals(action);

        final PendingResult pending = goAsync();
        Runnable kerja = () -> {
            try {
                if (perluThrottleBoot(action)) {
                    long kiniElapsed = android.os.SystemClock.elapsedRealtime();
                    long kiniWall = System.currentTimeMillis();
                    synchronized (KUNCI_BOOT) {
                        long simpanElapsed = bacaThrottleBoot(ctx);
                        long pakaiElapsed;
                        long pakaiWall;
                        long pakaiCount;
                        if (terakhirBootElapsed >= simpanElapsed) {
                            pakaiElapsed = terakhirBootElapsed;
                            pakaiWall = terakhirBootWall;
                            pakaiCount = terakhirBootCount;
                        } else {
                            pakaiElapsed = simpanElapsed;
                            pakaiWall = bacaThrottleBootWall(ctx);
                            pakaiCount = bacaThrottleBootCountTersimpan(ctx);
                        }
                        long kiniCount = bacaBootCount(ctx);
                        boolean menahan = throttleBootMenahan(kiniElapsed, pakaiElapsed,
                                kiniWall, pakaiWall, THROTTLE_BOOT_MS,
                                kiniCount, pakaiCount);
                        if (menahan) {
                            ServerService.catatLog("[app] Broadcast boot diabaikan"
                                    + " - masih dalam throttle 60 detik dari broadcast"
                                    + " boot sebelumnya pada boot yang sama.");
                            return;
                        }
                        terakhirBootElapsed = kiniElapsed;
                        terakhirBootWall = kiniWall;
                        terakhirBootCount = kiniCount;
                        simpanThrottleBoot(ctx, kiniElapsed);
                        simpanThrottleBootWall(ctx, kiniWall);
                        simpanThrottleBootCount(ctx, kiniCount);
                    }
                }
                SharedPreferences sp = ctx.getSharedPreferences(
                        ServerService.PREFS, Context.MODE_PRIVATE);
                try {
                    if (TgBackup.tandaRestoreTersisa(ctx) > 0) {
                        ServerService.catatLog("[app] PERINGATAN: restore terakhir"
                                + " tidak selesai (proses mati di tengah restore)"
                                + " - database mungkin sudah baru tetapi"
                                + " pengaturan masih lama. Periksa data, atau"
                                + " restore ulang backup yang sama.");
                        TgBackup.sendMessage(ctx, "Peringatan: restore terakhir"
                                + " terputus di tengah (kemungkinan listrik padam)."
                                + " Database dan pengaturan bisa tidak serasi"
                                + " - periksa, atau restore ulang backup yang sama.");
                        TgBackup.tandaiRestoreSelesai(ctx);
                    }
                    TgBackup.healkanStringPrefs(ctx);
                    TgBackup.migrateAutoPref(ctx);
                } catch (Exception ePersiapan) {
                    ServerService.catatLog("[app] Persiapan boot gagal: "
                            + ePersiapan.getClass().getSimpleName()
                            + " - langkah boot lain tetap dicoba.");
                }
                if (!gantiPaket && TgBackup.amanBoolean(sp, ServerService.KEY_AUTO_START, false)) {

                    try {
                        String dataDirBoot = TgBackup.amanString(sp,
                                ServerService.KEY_DATA_DIR,
                                ServerService.dataDirBawaanSegar());
                        if (StoragePerm.butuhIzinEksternal(dataDirBoot)
                                && !StoragePerm.sudahPunyaAkses(ctx)) {
                            ServerService.catatLog("[app] Auto-start boot: izin"
                                    + " penyimpanan tidak ada untuk " + dataDirBoot
                                    + " - server tidak akan bisa menulis folder data.");
                            TgBackup.sendMessage(ctx, "Peringatan: sesudah reboot,"
                                    + " server Vaultwarden tidak bisa jalan karena"
                                    + " izin penyimpanan untuk folder data tidak ada"
                                    + " (dicabut/di-reset sistem?). Beri izin Storage"
                                    + " di Pengaturan HP, lalu Start dari aplikasi.");
                        }
                    } catch (Exception ignoredIzin) {
                    }

                    boolean jalan = false;
                    try {
                        jalan = ServerService.start(ctx);
                    } catch (Exception ignored) {
                        jalan = false;
                    }
                    if (!jalan) {
                        ServerService.catatLog("[app] Auto-start boot ditolak sistem"
                                + " - dijalankan susulan saat app dibuka.");
                    }
                }

                if (TgBackup.amanBoolean(sp, TgBackup.KEY_TG_AUTO, false)) {
                    try {
                        TgBackup.schedule(ctx, true);
                    } catch (Exception eJadwal) {
                        ServerService.catatLog("[app] Jadwal backup boot gagal: "
                                + eJadwal.getClass().getSimpleName());
                    }
                    try {
                        String token = Util.amanTrim(TgBackup.amanString(sp, TgBackup.KEY_TG_TOKEN, ""));
                        String chat = Util.amanTrim(TgBackup.amanString(sp, TgBackup.KEY_TG_CHAT, ""));
                        String dataDir = TgBackup.amanString(sp, ServerService.KEY_DATA_DIR,
                                ServerService.dataDirBawaanSegar());

                        String dRapi = dataDir == null ? "" : dataDir.trim();
                        boolean dbAda = !dRapi.isEmpty()
                                && new java.io.File(dRapi, "db.sqlite3").exists();

                        if (!token.isEmpty() && !chat.isEmpty() && dbAda) {
                            if (!TgBackup.jadwalTundaBoot(ctx)) {
                                tandaiBackupTertunda(sp);
                            }
                        }
                    } catch (Exception ignored) {

                        try {
                            tandaiBackupTertunda(sp);
                        } catch (Exception ignored2) {
                        }
                    }
                }

                try {
                    TgBot.schedule(ctx);
                } catch (Exception eBot) {
                    ServerService.catatLog("[app] Jadwal bot boot gagal: "
                            + eBot.getClass().getSimpleName());
                }
            } catch (Exception e) {

                ServerService.catatLog("[app] Penanganan boot gagal: "
                        + e.getClass().getSimpleName());
            } finally {
                pending.finish();
            }
        };
        if (!Util.jalankanBg(kerja)) {
            try {
                new Thread(kerja, "vw-boot-cadangan").start();
            } catch (Throwable t) {
                ServerService.catatLog("[app] Penanganan boot dilewati:"
                        + " antrean latar penuh dan thread cadangan gagal"
                        + " dibuat.");
                pending.finish();
            }
        }
    }

    static long bacaThrottleBoot(Context context) {
        return TgBackup.bacaStempelThrottle(context, KEY_THROTTLE_BOOT_ELAPSED);
    }

    static void simpanThrottleBoot(Context context, long kini) {
        TgBackup.simpanStempelThrottle(context, KEY_THROTTLE_BOOT_ELAPSED, kini);
    }

    static int bacaBootCount(Context context) {
        if (android.os.Build.VERSION.SDK_INT < 24) {
            return 0;
        }
        try {
            return android.provider.Settings.Global.getInt(
                    context.getContentResolver(),
                    android.provider.Settings.Global.BOOT_COUNT, 0);
        } catch (Exception e) {
            return 0;
        }
    }

    static long bacaThrottleBootCountTersimpan(Context context) {
        return TgBackup.bacaStempelThrottle(context, KEY_THROTTLE_BOOT_COUNT);
    }

    static void simpanThrottleBootCount(Context context, long kini) {
        TgBackup.simpanStempelThrottle(context, KEY_THROTTLE_BOOT_COUNT, kini);
    }

    static long bacaThrottleBootWall(Context context) {
        return TgBackup.bacaStempelThrottle(context, KEY_THROTTLE_BOOT_WALL);
    }

    static void simpanThrottleBootWall(Context context, long kini) {
        TgBackup.simpanStempelThrottle(context, KEY_THROTTLE_BOOT_WALL, kini);
    }

    @SuppressLint("ApplySharedPref")
    private static void tandaiBackupTertunda(SharedPreferences sp) {
        try {
            sp.edit().putBoolean(TgBackup.KEY_BACKUP_TERTUNDA, true).commit();
        } catch (Exception ignored) {
        }
    }
}
