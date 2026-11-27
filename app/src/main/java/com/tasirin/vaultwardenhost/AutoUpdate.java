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

    // Audit 2862: tryUpdateVersi melaporkan tertunda
    // lewat STRING hasil, bukan lemparan - tiga bentuk
    // produksinya harus dikenali agar tak dinilai sukses.
    static boolean hasilUpdateTertunda(String msg) {
        if (msg == null) {
            return false;
        }
        return msg.startsWith("Cek pembaruan tertunda")
                || msg.startsWith("Cek pembaruan terganggu")
                || (msg.startsWith("Build Android")
                        && msg.contains("belum tersedia"));
    }

    // Audit 2863: syarat cache dihapus - backoff sesudah
    // gagal unduh berlaku juga untuk instalasi pertama,
    // kasus termahal yang dulu justru tanpa jeda.
    static boolean bolehLewatiCobaLagi(long gagalAt, long sekarang) {
        return !ServerService.bolehCobaUnduhLagi(gagalAt, sekarang);
    }

    public static void cek(Context ctx, Aksi aksi, AturPending pending, boolean lengkap) {
        // Audit 1473: tryLock — cek berkala yang menumpuk antre di
        // belakang cek lain yang sedang berjalan (unduh bisa
        // berjam-jam) hanya membuang utas; lewati dengan catatan.
        if (!KUNCI_CEK.tryLock()) {
            try {
                aksi.catat("[app] Cek update dilewati: cek lain sedang berjalan.");
            } catch (Exception ignored) {
            }
            return;
        }
        try {

            String latest = Updater.versiTargetBinary(ctx);
            if (latest == null) {
                // Audit 2860 arah (b): jangan return - blok
                // web-vault di bawah bisa tetap jalan dari pin
                // web-vault-nya sendiri. Rantai binary di bawah
                // aman terhadap latest null: versiSamaPersis
                // false, bandingVersi terhadap null >= 0, dan
                // cabang bandingReal > 0 digerbang eksplisit.
                aksi.catat("[app] Cek update binary dilewati: versi terbaru"
                        + " tak terbaca (offline/rate-limit).");
            }

            SharedPreferences sp = ctx.getSharedPreferences(ServerService.PREFS,
                    Context.MODE_PRIVATE);
            String real = ServerService.running
                    ? Updater.parseBinaryVersion(ServerService.binaryVersion)
                    : null;
            String current = Updater.versiSaatIni(ctx, real);
            boolean adaTerpasang = false;

            // Audit 2860 arah (a): target dari PIN belum tentu
            // ada rilisnya - pin salah ketik dulu tetap
            // diumumkan "tersedia" dan jalur auto memasang
            // stempel gagal 6 jam untuk upaya yang mustahil.
            // Verifikasi keberadaan asetnya lewat probe
            // checksum: tak terbukti -> lewati pengumuman dan
            // pemasangan putaran ini, tanpa stempel.
            boolean pinTakTerbukti = false;
            if (latest != null && Updater.adaKuncianBinary(ctx)
                    && Updater.bandingVersi(current, latest) < 0) {
                Updater.HasilChecksum probe = Updater.fetchChecksumRinci(ctx,
                        Updater.binaryAssetUrl(latest, ServerService.ABI)
                                + ".sha256", 20000, 20000);
                if (probe.hex == null) {
                    pinTakTerbukti = true;
                    aksi.catat("[app] Pin binary v" + latest
                            + (probe.sementara
                                    ? " belum bisa diverifikasi (jaringan"
                                            + " terganggu); pengumuman dan"
                                            + " pemasangan ditunda."
                                    : " tidak punya aset rilis; pengumuman"
                                            + " dan pemasangan dilewati."));
                }
            }

            int bandingReal = Updater.bandingVersi(real, latest);
            if (Updater.versiSamaPersis(real, latest)) {
                sp.edit().putString(ServerService.KEY_UPDATE_VERSION, latest).apply();
                pending.atur(null);
                batalkanNotifikasiUpdate(ctx);
            } else if (latest != null && bandingReal > 0) {

                // Audit 2861: samakan cabang sama-persis -
                // penanda versi ditulis dari versi NYATA
                // yang berjalan, agar tak basi dan
                // versiSaatIni tak salah menilai saat
                // server mati.
                sp.edit().putString(ServerService.KEY_UPDATE_VERSION, real).apply();
                pending.atur(null);
                batalkanNotifikasiUpdate(ctx);
            } else if (!pinTakTerbukti && Updater.bandingVersi(current, latest) < 0) {
                if (TgBackup.amanBoolean(sp, ServerService.KEY_AUTO_UPDATE, false) && tanpaKuota(ctx)) {

                    long gagalAt = TgBackup.amanLong(sp, ServerService.KEY_BIN_DL_GAGAL_AT, 0);
                    if (bolehLewatiCobaLagi(gagalAt, System.currentTimeMillis())) {

                        pending.atur(latest);
                    } else {
                        String msgBin = null;
                        try {
                            msgBin = Updater.tryUpdate(ctx);
                            if (hasilUpdateTertunda(msgBin)) {
                                // Audit 2862: tertunda bukan
                                // sukses - stempel gagal tetap
                                // berlaku dan pending disimpan,
                                // sama seperti jalur exception.
                                try {
                                    sp.edit().putLong(ServerService.KEY_BIN_DL_GAGAL_AT,
                                            System.currentTimeMillis()).apply();
                                } catch (Exception ignored) {
                                }
                                pending.atur(latest);
                            } else {
                                pending.atur(null);
                                batalkanNotifikasiUpdate(ctx);
                                if (Updater.binaryBerubah(msgBin)) {
                                    adaTerpasang = true;
                                }
                                try {
                                    sp.edit().remove(ServerService.KEY_BIN_DL_GAGAL_AT).apply();
                                } catch (Exception ignored) {
                                }
                            }
                        } catch (Exception e) {
                            aksi.catat("[app] Auto-update gagal: " + e.getMessage());
                            try {
                                sp.edit().putLong(ServerService.KEY_BIN_DL_GAGAL_AT,
                                        System.currentTimeMillis()).apply();
                            } catch (Exception ignored) {
                            }
                            pending.atur(latest);
                            msgBin = null;
                        }
                        // Audit 2617: callback UI/log berjalan di luar
                        // try pemasangan dan masing-masing dijaga -
                        // callback yang melempar sesudah update
                        // terpasang tidak lagi tercatat sebagai gagal.
                        if (msgBin != null) {
                            try {
                                aksi.toast(msgBin);
                            } catch (Exception ignored) {
                            }
                            try {
                                aksi.catat("[app] " + msgBin);
                            } catch (Exception ignored) {
                            }
                        }
                    }
                } else {
                    pending.atur(latest);
                    aksi.kabariTersedia(latest);

                    String tercatat = TgBackup.amanString(sp, "tg_notified_version", "");
                    if (tawarkanBaru(tercatat, latest)) {
                        boolean notifTampil = tampilkanNotifikasi(ctx, latest);
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
                        // Audit 2859: "dicoba" kini dihitung jujur -
                        // pengumuman hanya dianggap tersampaikan bila
                        // minimal satu kanal benar-benar bisa
                        // menyampaikan: notifikasi tampil, ATAU
                        // Telegram terkonfigurasi (token+chat terisi).
                        // Bila keduanya mustahil, penanda TIDAK
                        // ditulis agar versi itu diumumkan ulang
                        // sesudah ada kanal yang hidup; tanpa kanal
                        // yang hidup pun tak ada kirim ulang yang
                        // bisa mengganggu, jadi pelajaran Audit 599
                        // tetap utuh.
                        boolean tgSiap = !Util.amanTrim(TgBackup.amanString(
                                sp, TgBackup.KEY_TG_TOKEN, "")).isEmpty()
                                && !Util.amanTrim(TgBackup.amanString(
                                        sp, TgBackup.KEY_TG_CHAT, "")).isEmpty();
                        if (catatPenandaPengumuman(tercatat, latest,
                                notifTampil || tgSiap)) {
                            sp.edit().putString("tg_notified_version", latest).apply();
                        }
                    }
                }
            }
            if (lengkap) {

                if (TgBackup.amanBoolean(sp, ServerService.KEY_AUTO_UPDATE_WV, false)
                        && tanpaKuota(ctx)) {
                    String msgWv = null;
                    try {
                        String dataDir = TgBackup.amanString(sp, ServerService.KEY_DATA_DIR,
                                ServerService.dataDirBawaanSegar());
                        if (aksi.webVaultSiap(dataDir)) {
                            String targetWv = Updater.versiTargetWebVault(ctx);
                            String marker = Updater.webVaultFromVersion(ctx);
                            boolean terkunciWv = Updater.adaKuncianWebVault(ctx);
                            if (Updater.webVaultOtomatisPerluUpdate(marker, targetWv,
                                    terkunciWv)) {
                                long gagalWvAt = TgBackup.amanLong(sp,
                                        ServerService.KEY_WV_DL_GAGAL_AT, 0);
                                if (ServerService.bolehCobaUnduhLagi(gagalWvAt,
                                        System.currentTimeMillis())) {
                                    msgWv = Updater.updateWebVault(ctx);
                                    if (TgBot.webVaultBerubah(msgWv)) {
                                        adaTerpasang = true;
                                    }
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
                        msgWv = null;
                    }
                    // Audit 2617: callback di luar try pemasangan,
                    // masing-masing dijaga (lihat cabang binary).
                    if (msgWv != null) {
                        try {
                            aksi.toast(msgWv);
                        } catch (Exception ignored) {
                        }
                        try {
                            aksi.catat("[app] " + msgWv);
                        } catch (Exception ignored) {
                        }
                    }
                }

            }
            if (adaTerpasang && ServerService.running
                    && TgBackup.amanBoolean(sp, ServerService.KEY_AUTO_RESTART_UPDATE, false)) {
                aksi.catat("[app] Auto-restart setelah update...");
                // Audit 2792: kabari pemilik lewat Telegram
                // SEBELUM restart, bukan sesudah - vault akan
                // tak terjangkau sebentar dan ia berhak tahu
                // sebabnya lebih dulu.
                try {
                    TgBackup.sendMessage(ctx, "Update otomatis terpasang"
                            + " - server Vaultwarden restart otomatis"
                            + " sekarang agar versi baru aktif. Vault tak"
                            + " terjangkau sebentar; bila tak kembali"
                            + " dalam beberapa menit, kirim /start.");
                } catch (Exception ignoredKabar) {
                }
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

                android.net.Network aktif;
                try {
                    aktif = cm.getActiveNetwork();
                } catch (Exception e) {
                    return false;
                }
                if (aktif == null) {
                    return false;
                }
                // Audit 2864: kapabilitas yang tak bisa
                // dibaca (null/lempar) dulu ditelan dan cek
                // VPN terlewat diam-diam - kini gagal-
                // tertutup: tanpa bukti bukan-VPN, unduhan
                // otomatis tak jalan putaran ini.
                android.net.NetworkCapabilities cap;
                try {
                    cap = cm.getNetworkCapabilities(aktif);
                } catch (Exception e) {
                    return false;
                }
                if (cap == null) {
                    return false;
                }
                if (cap.hasTransport(
                        android.net.NetworkCapabilities.TRANSPORT_VPN)) {
                    return false;
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
                        ctx.getString(R.string.notif_update),
                        NotificationManager.IMPORTANCE_DEFAULT);
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
        android.app.Notification n = b.setContentTitle(
                        ctx.getString(R.string.notif_update))
                .setContentText(ctx.getString(
                        R.string.update_tersedia_singkat, version))
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
