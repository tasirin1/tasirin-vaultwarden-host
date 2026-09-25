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

/** Simpan log ke .txt di Download (satu implementasi untuk Main & Log).
 *  Return nama file bila sukses, null bila gagal. */
public final class LogExport {

    private LogExport() {
    }

    // API lawas sengaja: Downloads publik pra-29 + getPackageInfo satu jalur API 21-32.
    @SuppressWarnings("deprecation")
    public static String simpanKeDownload(Activity act, String logMentah) {
        String log = LogActivity.samarkanLog(logMentah);
        StringBuilder header = new StringBuilder();
        header.append("=== Tasirin Vaultwarden Host - Log Server (realtime) ===\n");
        header.append("Waktu: ")
                .append(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date()))
                .append('\n');
        try {
            android.content.pm.PackageInfo info =
                    act.getPackageManager().getPackageInfo(act.getPackageName(), 0);
            header.append("Versi app: ").append(info.versionName)
                    .append(" (build ").append(info.versionCode).append(")\n");
        } catch (Exception ignored) {
            header.append("Versi app: ?\n");
        }
        header.append("Android: ").append(Build.VERSION.RELEASE)
                .append(" (API ").append(Build.VERSION.SDK_INT).append(")\n");
        header.append("Perangkat: ").append(Build.MANUFACTURER).append(' ')
                .append(Build.MODEL).append("\n\n");
        header.append(log.isEmpty() ? "(Belum ada aktivitas server)\n" : log);
        header.append('\n');

        // Milidetik: dua export dalam sedetik tak saling timpa/hapus.
        String stamp = new SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.US).format(new Date());
        String name = "tasirin-vaultwarden-host-log-" + stamp + ".txt";
        boolean ok = false;
        if (Build.VERSION.SDK_INT >= 29) {
            try {
                ContentResolver resolver = act.getContentResolver();
                ContentValues values = new ContentValues();
                values.put(MediaStore.Downloads.DISPLAY_NAME, name);
                values.put(MediaStore.Downloads.MIME_TYPE, "text/plain");
                values.put(MediaStore.Downloads.RELATIVE_PATH, "Download/");
                values.put(MediaStore.Downloads.IS_PENDING, 1);
                Uri uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
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
            }
        } else {
            File tujuan = null;
            try {
                File dir = Environment.getExternalStoragePublicDirectory(
                        Environment.DIRECTORY_DOWNLOADS);
                if (dir != null && (dir.isDirectory() || dir.mkdirs())) {
                    tujuan = new File(dir, name);
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
}
