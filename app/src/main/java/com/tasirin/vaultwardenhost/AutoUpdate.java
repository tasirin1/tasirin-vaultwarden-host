package com.tasirin.vaultwardenhost;

import android.annotation.SuppressLint;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.Build;

public final class AutoUpdate {

    private AutoUpdate() {
    }

    public interface Aksi {
        void toast(String pesan);
        void catat(String baris);
        void kabariTersedia(String versi);
        void tawarkanWebVault();
        boolean webVaultSiap(String dataDir);
        void restartServer();
    }

    public interface AturPending {
        void atur(String versi);
    }

    static final String KEY_TAWARAN_UPDATE = "update_ditawarkan_untuk";

    private static final java.util.concurrent.locks.ReentrantLock KUNCI_CEK =
            new java.util.concurrent.locks.ReentrantLock();

    static boolean tawarkanBaru(String sudah, String versi) {
        return !Updater.versiCocok(versi, sudah);
    }

    static boolean catatPenandaPengumuman(String tercatat, String versi,
            boolean pengumumanDicoba) {
        return pengumumanDicoba && tawarkanBaru(tercatat, versi);
    }

    static boolean bolehLewatiCobaLagi(boolean adaCache, long gagalAt, long sekarang) {
        return adaCache && !ServerService.bolehCobaUnduhLagi(gagalAt, sekarang);
    }

    public static void cek(Context ctx, Aksi aksi, AturPending pending, boolean lengkap) {
        KUNCI_CEK.lock();
        try {

            String latest = Updater.versiTargetBinary(ctx);
            if (latest == null) {

                aksi.catat("[app] Cek update dilewati: versi terbaru tak terbaca"
                        + " (offline/rate-limit).");
                return;
            }

            SharedPreferences sp = ctx.getSharedPreferences(ServerService.PREFS,
                    Context.MODE_PRIVATE);
            String real = ServerService.running
                    ? Updater.parseBinaryVersion(ServerService.binaryVersion)
                    : null;
            String updated = TgBackup.amanString(sp, ServerService.KEY_UPDATE_VERSION, "");
            String current = real != null ? real : Updater.normVersion(
                    updated != null && !updated.isEmpty()
                            ? updated : Updater.readBundledVersionRaw(ctx));
            boolean adaTerpasang = false;

            int bandingReal = Updater.bandingVersi(real, latest);
            if (bandingReal == 0) {
                sp.edit().putString(ServerService.KEY_UPDATE_VERSION, latest).apply();
                pending.atur(null);
                batalkanNotifikasiUpdate(ctx);
            } else if (bandingReal > 0) {

                pending.atur(null);
                batalkanNotifikasiUpdate(ctx);
            } else if (Updater.bandingVersi(current, latest) < 0) {
                if (TgBackup.amanBoolean(sp, ServerService.KEY_AUTO_UPDATE, false) && tanpaKuota(ctx)) {

                    long gagalAt = TgBackup.amanLong(sp, ServerService.KEY_BIN_DL_GAGAL_AT, 0);
                    java.io.File berkasBin = new java.io.File(ctx.getFilesDir(),
                            "bin/vaultwarden-" + ServerService.ABI);
                    boolean adaCache = berkasBin.isFile() && berkasBin.length() >= 1000000;
                    if (bolehLewatiCobaLagi(adaCache, gagalAt, System.currentTimeMillis())) {

                        pending.atur(latest);
                    } else {
                        try {
                            String msg = Updater.tryUpdate(ctx);
                            pending.atur(null);
                            batalkanNotifikasiUpdate(ctx);
                            if (Updater.binaryBerubah(msg)) {
                                adaTerpasang = true;
                            }
                            aksi.toast(msg);
                            aksi.catat("[app] " + msg);
                            try {
                                sp.edit().remove(ServerService.KEY_BIN_DL_GAGAL_AT).apply();
                            } catch (Exception ignored) {
                            }
                        } catch (Exception e) {
                            aksi.catat("[app] Auto-update gagal: " + e.getMessage());
                            try {
                                sp.edit().putLong(ServerService.KEY_BIN_DL_GAGAL_AT,
                                        System.currentTimeMillis()).apply();
                            } catch (Exception ignored) {
                            }
                            pending.atur(latest);
                        }
                    }
                } else {
                    pending.atur(latest);
                    aksi.kabariTersedia(latest);

                    String tercatat = TgBackup.amanString(sp, "tg_notified_version", "");
                    if (tawarkanBaru(tercatat, latest)) {
                        tampilkanNotifikasi(ctx, latest);
                        TgBackup.sendMessage(ctx, "Update Vaultwarden v" + latest
                                + " tersedia. Kirim /update ke bot untuk memasang dari jauh.");
                        // Penanda dicatat sesudah pengumuman DICOBA di
                        // kedua kanal, bukan digerbang keberhasilan
                        // notifikasi sistem: izin notifikasi mati dulu
                        // membuat pesan Telegram terkirim ulang pada
                        // setiap pembukaan aplikasi (Audit 599).
                        // Keputusan itu predikat murni catatPenandaPengumuman
                        // — tanda tangannya sengaja tak menerima hasil
                        // notifikasi sistem (Audit 609).
                        if (catatPenandaPengumuman(tercatat, latest, true)) {
                            sp.edit().putString("tg_notified_version", latest).apply();
                        }
                    }
                }
            }
            if (lengkap) {

                if (TgBackup.amanBoolean(sp, ServerService.KEY_AUTO_UPDATE_WV, false)
                        && tanpaKuota(ctx)) {
                    try {
                        String dataDir = TgBackup.amanString(sp, ServerService.KEY_DATA_DIR,
                                ServerService.dataDirBawaanSegar());
                        if (aksi.webVaultSiap(dataDir)) {
                            String targetWv = Updater.versiTargetWebVault(ctx);
                            String marker = Updater.webVaultFromVersion(ctx);
                            if (targetWv != null
                                    && (marker == null || !Updater.versiCocok(marker, targetWv))) {
                                long gagalWvAt = TgBackup.amanLong(sp,
                                        ServerService.KEY_WV_DL_GAGAL_AT, 0);
                                if (ServerService.bolehCobaUnduhLagi(gagalWvAt,
                                        System.currentTimeMillis())) {
                                    String msg = Updater.updateWebVault(ctx);
                                    if (TgBot.webVaultBerubah(msg)) {
                                        adaTerpasang = true;
                                    }
                                    aksi.toast(msg);
                                    aksi.catat("[app] " + msg);
                                    try {
                                        sp.edit().remove(ServerService.KEY_WV_DL_GAGAL_AT).apply();
                                    } catch (Exception ignored) {
                                    }
                                }
                            }
                        }
                    } catch (Exception e) {
                        try {
                            sp.edit().putLong(ServerService.KEY_WV_DL_GAGAL_AT,
                                    System.currentTimeMillis()).apply();
                        } catch (Exception ignored) {
                        }
                        aksi.catat("[app] Auto-update web-vault gagal: " + e.getMessage());
                    }
                }

            }
            if (adaTerpasang && ServerService.running
                    && TgBackup.amanBoolean(sp, ServerService.KEY_AUTO_RESTART_UPDATE, false)) {
                aksi.catat("[app] Auto-restart setelah update...");
                aksi.restartServer();
            }
            TgBackup.notifyLowStorage(ctx);
            if (lengkap) {
                aksi.tawarkanWebVault();
            }
        } catch (Exception e) {

            try {
                String m = e.getMessage();
                aksi.catat("[app] Cek update gagal: "
                        + (m != null && !m.isEmpty() ? m : e.getClass().getSimpleName()));
            } catch (Exception ignored) {
            }
        } finally {
            KUNCI_CEK.unlock();
        }
    }

    @SuppressWarnings("deprecation")
    public static boolean tanpaKuota(Context ctx) {
        try {
            ConnectivityManager cm = (ConnectivityManager)
                    ctx.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) {
                return false;
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {

                try {
                    android.net.Network aktif = cm.getActiveNetwork();
                    if (aktif == null) {
                        return false;
                    }
                    try {
                        android.net.NetworkCapabilities cap = cm.getNetworkCapabilities(aktif);
                        if (cap != null && cap.hasTransport(
                                android.net.NetworkCapabilities.TRANSPORT_VPN)) {
                            return false;
                        }
                    } catch (Exception ignored) {
                    }
                } catch (Exception ignored) {
                }
                return !cm.isActiveNetworkMetered();
            }
            NetworkInfo ni = cm.getActiveNetworkInfo();
            if (ni == null) {
                return false;
            }
            int t = ni.getType();

            if (t == ConnectivityManager.TYPE_VPN) {
                return false;
            }
            return t == ConnectivityManager.TYPE_WIFI
                    || t == ConnectivityManager.TYPE_ETHERNET
                    || t == ConnectivityManager.TYPE_WIMAX;
        } catch (Exception e) {
            return false;
        }
    }

    @SuppressLint("MissingPermission")
    @SuppressWarnings("deprecation")
    /** Batalkan notifikasi "update tersedia" (ID 2): sesudah update
     *  terpasang lewat jalur mana pun, notifikasi itu menyesatkan bila
     *  tetap tergantung di laci (Audit 600). */
    static void batalkanNotifikasiUpdate(Context ctx) {
        try {
            NotificationManager nm = (NotificationManager)
                    ctx.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) {
                nm.cancel(2);
            }
        } catch (Exception ignored) {
        }
    }

    public static boolean tampilkanNotifikasi(Context ctx, String version) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager nm = (NotificationManager)
                    ctx.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) {
                NotificationChannel ch = new NotificationChannel("vw_updates",
                        "Vaultwarden Update", NotificationManager.IMPORTANCE_DEFAULT);
                nm.createNotificationChannel(ch);
            }
        }
        Intent intent = new Intent(ctx, MainActivity.class);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT
                | (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                        ? PendingIntent.FLAG_IMMUTABLE : 0);
        PendingIntent pi = PendingIntent.getActivity(ctx, 0, intent, flags);
        android.app.Notification.Builder b;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            b = new android.app.Notification.Builder(ctx, "vw_updates");
        } else {
            b = new android.app.Notification.Builder(ctx);
        }
        android.app.Notification n = b.setContentTitle("Vaultwarden Update")
                .setContentText("v" + version + " available")
                .setSmallIcon(android.R.drawable.stat_notify_sync)
                .setContentIntent(pi)
                .setVisibility(android.app.Notification.VISIBILITY_PRIVATE)
                .setAutoCancel(true)
                .build();

        NotificationManager nm = (NotificationManager)
                ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) {
            return false;
        }
        try {

            if (android.os.Build.VERSION.SDK_INT >= 33) {
                try {
                    if (ctx.checkSelfPermission("android.permission.POST_NOTIFICATIONS")
                            != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                        ServerService.catatLog("[app] Notifikasi update tertahan:"
                                + " izin notifikasi (POST_NOTIFICATIONS) belum diberikan.");
                        return false;
                    }
                } catch (Exception ignoredPerm) {
                    return false;
                }
            }
            nm.notify(2, n);
            return true;
        } catch (SecurityException ignored) {
            return false;
        }
    }
}
