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

/** Satu-satunya implementasi cek auto-update (dipakai MainActivity & SettingsActivity
 *  agar logika tak drift bila diubah di satu tempat). Berjalan di worker thread. */
public final class AutoUpdate {

    private AutoUpdate() {
    }

    /** Callback UI milik activity pemanggil. toast/catat tiap activity sudah
     *  post ke UI thread sendiri sehingga aman dipanggil dari worker. */
    public interface Aksi {
        void toast(String pesan);
        void catat(String baris);
        void kabariTersedia(String versi);
        void tawarkanWebVault();
        boolean webVaultSiap(String dataDir);
        void restartServer();
    }

    /** Penampung tulis pendingVersion milik activity. */
    public interface AturPending {
        void atur(String versi);
    }

    /** Cek & pasang update binary; bila lengkap=true sekalian web-vault,
     *  auto-restart, dan tawaran web-vault (jalur Settings). */
    public static void cek(Context ctx, Aksi aksi, AturPending pending, boolean lengkap) {
        try {
            // Versi target menghormati kuncian user (pin) agar tak terpaku terbaru.
            String latest = Updater.versiTargetBinary(ctx);
            if (latest == null) {
                return;
            }
            // Segarkan anchor TLS maksimal 1x sehari (best-effort, gagal diam).
            try {
                Updater.segarkanTrustAnchor(ctx);
            } catch (Exception ignored) {
            }
            SharedPreferences sp = ctx.getSharedPreferences(ServerService.PREFS,
                    Context.MODE_PRIVATE);
            String real = Updater.parseBinaryVersion(ServerService.binaryVersion);
            String updated = TgBackup.amanString(sp, ServerService.KEY_UPDATE_VERSION, "");
            String current = real != null ? real : Updater.normVersion(
                    updated != null && !updated.isEmpty()
                            ? updated : Updater.readBundledVersionRaw(ctx));
            boolean adaTerpasang = false;
            if (Updater.bandingVersi(real, latest) == 0) {
                sp.edit().putString(ServerService.KEY_UPDATE_VERSION, latest).apply();
                pending.atur(null);
            } else if (Updater.bandingVersi(real, latest) > 0) {
                // Binary lokal lebih baru dari rilis (mis. build dev): jangan
                // tulis marker mundur; cukup bersihkan banner update.
                pending.atur(null);
            } else if (Updater.bandingVersi(current, latest) < 0) {
                if (TgBackup.amanBoolean(sp, ServerService.KEY_AUTO_UPDATE, false) && tanpaKuota(ctx)) {
                    try {
                        String msg = Updater.tryUpdate(ctx);
                        pending.atur(null);
                        if (Updater.binaryBerubah(msg)) {
                            adaTerpasang = true;
                        }
                        aksi.toast(msg);
                        aksi.catat("[app] " + msg);
                    } catch (Exception e) {
                        aksi.catat("[app] Auto-update gagal: " + e.getMessage());
                        pending.atur(latest);
                        tampilkanNotifikasi(ctx, latest);
                    }
                } else {
                    pending.atur(latest);
                    aksi.kabariTersedia(latest);
                    // Notifikasi sistem + Telegram cukup sekali per versi.
                    if (!Updater.versiCocok(latest, TgBackup.amanString(sp, "tg_notified_version", ""))) {
                        sp.edit().putString("tg_notified_version", latest).apply();
                        tampilkanNotifikasi(ctx, latest);
                        TgBackup.sendMessage(ctx, "Update Vaultwarden v" + latest
                                + " tersedia. Kirim /update ke bot untuk memasang dari jauh.");
                    }
                }
            }
            if (lengkap) {
                // Web-vault mengikuti versi target (kuncian user atau terbaru);
                // lewati bila versi target tak terbaca (offline/rate-limit).
                if (TgBackup.amanBoolean(sp, ServerService.KEY_AUTO_UPDATE_WV, false)
                        && tanpaKuota(ctx)) {
                    try {
                        String dataDir = TgBackup.amanString(sp, ServerService.KEY_DATA_DIR,
                                ServerService.DEFAULT_DATA_DIR);
                        if (aksi.webVaultSiap(dataDir)) {
                            String targetWv = Updater.versiTargetWebVault(ctx);
                            String marker = Updater.webVaultFromVersion(ctx);
                            if (targetWv != null
                                    && (marker == null || !Updater.versiCocok(marker, targetWv))) {
                                String msg = Updater.updateWebVault(ctx);
                                if (TgBot.webVaultBerubah(msg)) {
                                    adaTerpasang = true;
                                }
                                aksi.toast(msg);
                                aksi.catat("[app] " + msg);
                            }
                        }
                    } catch (Exception e) {
                        aksi.catat("[app] Auto-update web-vault gagal: " + e.getMessage());
                    }
                }
                // Opsi: restart sekali bila ada update terpasang & server sedang jalan.
                if (adaTerpasang && ServerService.running
                        && TgBackup.amanBoolean(sp, ServerService.KEY_AUTO_RESTART_UPDATE, false)) {
                    aksi.catat("[app] Auto-restart setelah update...");
                    aksi.restartServer();
                }
            }
            TgBackup.notifyLowStorage(ctx);
            if (lengkap) {
                aksi.tawarkanWebVault();
            }
        } catch (Exception ignored) {
        }
    }

    /** Auto-update binary hanya di jaringan non-kuota (WiFi/ethernet). */
    // API lawas sengaja untuk Android 5.0/5.1 (API 21/22); jalur modern dipakai bila API >= 23.
    @SuppressWarnings("deprecation")
    public static boolean tanpaKuota(Context ctx) {
        try {
            ConnectivityManager cm = (ConnectivityManager)
                    ctx.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) {
                return false;
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                // Tanpa network aktif isActiveNetworkMetered() false sehingga
                // offline dulu dianggap "tanpa kuota": pastikan ada network dulu.
                // VPN juga ditolak: underlying-nya bisa seluler berkuota.
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
            // Fail-closed saat VPN: underlying-nya bisa seluler berkuota dan
            // API 21/22 tak bisa tahu, jadi tolak auto-unduh 15-35 MB.
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

    // Konstruktor Builder tanpa channel sengaja untuk pra-Oreo (API 21-25).
    // POST_NOTIFICATIONS tak dicek runtime: targetSdk 28 exempt (izin runtime
    // notifikasi hanya untuk targetSdk 33+); notify dibungkus try/catch untuk
    // forward-compat bila targetSdk naik.
    @SuppressLint("MissingPermission")
    @SuppressWarnings("deprecation")
    public static void tampilkanNotifikasi(Context ctx, String version) {
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
                .setContentText("v" + version + " tersedia")
                .setSmallIcon(android.R.drawable.stat_notify_sync)
                .setContentIntent(pi)
                .setVisibility(android.app.Notification.VISIBILITY_PRIVATE)
                .setAutoCancel(true)
                .build();
        // getSystemService(Class) baru di API 23; pakai string agar API 21/22 aman.
        NotificationManager nm = (NotificationManager)
                ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) {
            try {
                nm.notify(2, n);
            } catch (SecurityException ignored) {
            }
        }
    }
}
