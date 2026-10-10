package com.tasirin.vaultwardenhost;

import android.app.Activity;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** Simpan log ke .txt di Download (dipakai LogActivity).
 *  Return nama file bila sukses, null bila gagal. */
public final class LogExport {

    private LogExport() {
    }

    /** Cache tanggal per thread: SimpleDateFormat init berat di STB lama. */
    private static final ThreadLocal<SimpleDateFormat> FMT_KEPALA =
            new ThreadLocal<SimpleDateFormat>() {
                @Override protected SimpleDateFormat initialValue() {
                    return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US);
                }
            };
    private static final ThreadLocal<SimpleDateFormat> FMT_STAMP =
            new ThreadLocal<SimpleDateFormat>() {
                @Override protected SimpleDateFormat initialValue() {
                    return new SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.US);
                }
            };
    /** Acak bersama: new SecureRandom tiap export bisa blokir seed di kernel lama. */
    private static final java.security.SecureRandom ACAK =
            new java.security.SecureRandom();
    // API lawas sengaja: Downloads publik pra-29 + getPackageInfo satu jalur API 21-32.
    @SuppressWarnings("deprecation")
    public static String simpanKeDownload(Activity act, String logMentah) {
        String log = LogActivity.butuhSamaran(logMentah)
                ? LogActivity.samarkanLog(logMentah) : (logMentah == null ? "" : logMentah);
        StringBuilder header = new StringBuilder();
        header.append("=== Tasirin Vaultwarden Host - Server Log (realtime) ===\n");
        header.append("Time: ")
                .append(FMT_KEPALA.get().format(new Date()))
                .append('\n');
        try {
            android.content.pm.PackageInfo info =
                    act.getPackageManager().getPackageInfo(act.getPackageName(), 0);
            header.append("App version: ").append(info.versionName)
                    .append(" (build ").append(info.versionCode).append(")\n");
        } catch (Exception ignored) {
            header.append("App version: ?\n");
        }
        header.append("Android: ").append(Build.VERSION.RELEASE)
                .append(" (API ").append(Build.VERSION.SDK_INT).append(")\n");
        // Privasi: sidik perangkat (merek + model) sengaja tak diekspor agar
        // berkas log yang dibagikan tak memuat fingerprint perangkat.
        header.append("Device: [perangkat disamarkan]\n\n");
        header.append(log.isEmpty() ? "(No server activity yet)\n" : log);
        header.append('\n');

        // Milidetik + akhiran acak 48-bit: dua export dalam ms yang sama
        // (ketuk ganda) tak saling timpa; acak 16-bit lama tabrakan 1/65536
        // dan jalur legacy menimpa file yang sudah ada.
        String stamp = FMT_STAMP.get().format(new Date());
        String name = namaLog(stamp, ACAK);
        boolean ok = false;
        android.net.Uri pendingUri = null;
        if (Build.VERSION.SDK_INT >= 29) {
            try {
                ContentResolver resolver = act.getContentResolver();
                ContentValues values = new ContentValues();
                values.put(MediaStore.Downloads.DISPLAY_NAME, name);
                values.put(MediaStore.Downloads.MIME_TYPE, "text/plain");
                values.put(MediaStore.Downloads.RELATIVE_PATH, "Download/");
                values.put(MediaStore.Downloads.IS_PENDING, 1);
                Uri uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
                pendingUri = uri;
                if (uri != null) {
                    try (OutputStream out = resolver.openOutputStream(uri)) {
                        if (out != null) {
                            out.write(header.toString().getBytes(StandardCharsets.UTF_8));
                            ok = true;
                        } else {
                            resolver.delete(uri, null, null);
                        }
                    } catch (Exception e) {
                        resolver.delete(uri, null, null);
                    }
                    if (ok) {
                        try {
                            ContentValues done = new ContentValues();
                            done.put(MediaStore.Downloads.IS_PENDING, 0);
                            resolver.update(uri, done, null, null);
                        } catch (Exception e) {
                            try {
                                resolver.delete(uri, null, null);
                            } catch (Exception ignored) {
                            }
                            ok = false;
                        }
                    }
                }
            } catch (Exception ignored) {
                // Lempar di jeda insert-vs-tulis (mis. openOutputStream melempar
                // sebelum masuk try dalam): hapus pending agar tak jadi orphan
                // tak terlihat di Download.
                try {
                    if (!ok && pendingUri != null) {
                        act.getContentResolver().delete(pendingUri, null, null);
                    }
                } catch (Exception ignored2) {
                }
            }
        } else {
            // Lapis kedua (pemanggil LogActivity sudah cek dulu): tanpa izin
            // tulis, FileOutputStream pasti gagal — tolak eksplisit agar tak
            // dikira galat I/O misterius.
            try {
                if (!StoragePerm.sudahPunyaAkses(act)) {
                    return null;
                }
            } catch (Exception ignored) {
            }
            File tujuan = null;
            try {
                File dir = Environment.getExternalStoragePublicDirectory(
                        Environment.DIRECTORY_DOWNLOADS);
                if (dir != null && (dir.isDirectory() || dir.mkdirs())) {
                    // createNewFile atomik (bukan timpa): nama yang sudah ada
                    // (tabrakan acak / file lama) tak akan ditimpa atau dihapus.
                    for (int coba = 0; coba < 5 && tujuan == null; coba++) {
                        if (coba > 0) {
                            name = namaLog(stamp, ACAK);
                        }
                        try {
                            File cand = new File(dir, name);
                            if (cand.createNewFile()) {
                                tujuan = cand;
                            }
                        } catch (Exception ignored) {
                            // Galat I/O bisa transient (balap nama/ledakan sesaat):
                            // coba nama acak baru, terbatas 5x agar tak putar sia-sia.
                            continue;
                        }
                    }
                    if (tujuan == null) {
                        return null;
                    }
                    // Tutup TOCTOU cek-vs-buka: pastikan file baru masih di
                    // dalam Download dan bukan symlink yang ditukar app lain
                    // di jeda createNewFile-vs-tulis.
                    try {
                        String kanonDir = dir.getCanonicalPath();
                        String kanonTujuan = tujuan.getCanonicalPath();
                        if (!kanonTujuan.equals(kanonDir + File.separator + tujuan.getName())) {
                            try { tujuan.delete(); } catch (Exception ignored2) { }
                            return null;
                        }
                    } catch (Exception e) {
                        try { tujuan.delete(); } catch (Exception ignored2) { }
                        return null;
                    }
                    try (java.io.OutputStreamWriter w = new java.io.OutputStreamWriter(
                            new FileOutputStream(tujuan, false),
                            StandardCharsets.UTF_8)) {
                        w.write(header.toString());
                        ok = true;
                    }
                }
            } catch (Exception ignored) {
            }
            if (!ok && tujuan != null) {
                tujuan.delete();
            }
        }
        return ok ? name : null;
    }

    /** Nama file log unik (acak 48-bit agar ketuk ganda dalam ms sama tak tabrakan). */
    private static String namaLog(String stamp, java.security.SecureRandom rnd) {
        long acak = rnd.nextLong() & 0xFFFFFFFFFFFFL;
        return "tasirin-vaultwarden-host-log-" + stamp + "-"
                + Util.hex12(acak) + ".txt";
    }
}
