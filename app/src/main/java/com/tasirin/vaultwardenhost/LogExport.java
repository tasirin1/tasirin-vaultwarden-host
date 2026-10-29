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

public final class LogExport {

    private LogExport() {
    }

    private static final java.security.SecureRandom ACAK =
            new java.security.SecureRandom();

    @SuppressWarnings("deprecation")
    public static String simpanKeDownload(Activity act, String logMentah) {
        String log = LogActivity.butuhSamaran(logMentah)
                ? LogActivity.samarkanLog(logMentah) : (logMentah == null ? "" : logMentah);

        StringBuilder kepala = new StringBuilder(256);
        kepala.append("=== Tasirin Vaultwarden Host - Server Log (realtime) ===\n");
        kepala.append("Time: ")
                .append(Util.formatTanggalSekarang("yyyy-MM-dd HH:mm:ss"))
                .append('\n');
        try {
            android.content.pm.PackageInfo info =
                    act.getPackageManager().getPackageInfo(act.getPackageName(), 0);
            kepala.append("App version: ").append(info.versionName)
                    .append(" (build ").append(info.versionCode).append(")\n");
        } catch (Exception ignored) {
            kepala.append("App version: ?\n");
        }
        kepala.append("Android: ").append(Build.VERSION.RELEASE)
                .append(" (API ").append(Build.VERSION.SDK_INT).append(")\n");

        kepala.append("Device: [perangkat disamarkan]\n\n");
        String kepalaTeks = kepala.toString();
        String badan = log.isEmpty() ? "(No server activity yet)\n" : log;

        String stamp = Util.formatTanggalSekarang("yyyyMMdd-HHmmss-SSS");
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
                    try (OutputStream mentah = resolver.openOutputStream(uri)) {
                        if (mentah != null) {
                            try (java.io.OutputStreamWriter w =
                                    new java.io.OutputStreamWriter(mentah, StandardCharsets.UTF_8)) {
                                w.write(kepalaTeks);
                                w.write(badan);
                                w.write('\n');
                            }
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

                try {
                    if (!ok && pendingUri != null) {
                        act.getContentResolver().delete(pendingUri, null, null);
                    }
                } catch (Exception ignored2) {
                }
            }
        } else {

            boolean punyaAkses;
            try {
                punyaAkses = StoragePerm.sudahPunyaAkses(act);
            } catch (Exception e) {
                punyaAkses = false;
            }
            if (!punyaAkses) {
                return null;
            }
            File tujuan = null;
            try {
                File dir = Environment.getExternalStoragePublicDirectory(
                        Environment.DIRECTORY_DOWNLOADS);
                if (dir != null && (dir.isDirectory() || dir.mkdirs())) {

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

                            continue;
                        }
                    }
                    if (tujuan == null) {
                        return null;
                    }

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
                        w.write(kepalaTeks);
                        w.write(badan);
                        w.write('\n');
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

    static String namaLog(String stamp, java.security.SecureRandom rnd) {
        long acak = rnd.nextLong() & 0xFFFFFFFFFFFFL;
        return "tasirin-vaultwarden-host-log-" + stamp + "-"
                + Util.hex12(acak) + ".txt";
    }
}
