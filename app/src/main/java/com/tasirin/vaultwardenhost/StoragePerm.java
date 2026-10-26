package com.tasirin.vaultwardenhost;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Environment;
import android.widget.Toast;

public final class StoragePerm {

    private StoragePerm() {
    }

    public static boolean butuhKelolaSemuaFile(int sdk) {
        return sdk >= Build.VERSION_CODES.R;
    }

    private static final String[] IZIN_KOSONG = new String[0];
    private static final String[] IZIN_RW = new String[]{
            Manifest.permission.WRITE_EXTERNAL_STORAGE,
            Manifest.permission.READ_EXTERNAL_STORAGE,
    };

    public static String[] izinRuntime(int sdk) {
        if (sdk < Build.VERSION_CODES.M) {
            return IZIN_KOSONG;
        }
        return IZIN_RW.clone();
    }

    public static boolean butuhIzinEksternal(String dataDir) {
        if (dataDir == null) {
            return true;
        }
        String d = dataDir.trim();
        if (d.isEmpty()) {
            return true;
        }
        return d.equals("/sdcard") || d.startsWith("/sdcard/")
                || d.equals("/storage") || d.startsWith("/storage/")
                || d.equals("/mnt") || d.startsWith("/mnt/");
    }

    static boolean cukupAkses(int sdk, boolean kelolaSemuaFile, boolean tulisDiberikan) {
        if (butuhKelolaSemuaFile(sdk)) {
            return kelolaSemuaFile || tulisDiberikan;
        }
        if (sdk >= 23) {
            return tulisDiberikan;
        }
        return true;
    }

    public static boolean sudahPunyaAkses(Context ctx) {
        boolean kelola = false;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                kelola = Environment.isExternalStorageManager();
            } catch (Exception ignored) {
            }
        }
        boolean tulis = false;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                tulis = ctx.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                        == PackageManager.PERMISSION_GRANTED;
            } catch (Exception ignored) {
            }
        }
        return cukupAkses(Build.VERSION.SDK_INT, kelola, tulis);
    }

    static boolean tulisDiizinkan(String[] permissions, int[] grantResults) {
        if (permissions == null || grantResults == null) {
            return false;
        }
        int n = Math.min(permissions.length, grantResults.length);
        for (int i = 0; i < n; i++) {
            if (Manifest.permission.WRITE_EXTERNAL_STORAGE.equals(permissions[i])) {
                return grantResults[i] == PackageManager.PERMISSION_GRANTED;
            }
        }
        return false;
    }

    public static boolean mintaIzinBilaPerlu(Activity act, int reqTulis) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                if (act.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                        != PackageManager.PERMISSION_GRANTED) {
                    act.requestPermissions(izinRuntime(Build.VERSION.SDK_INT), reqTulis);
                    return true;
                }
            } catch (Exception ignored) {
            }
        }
        return false;
    }

    public static boolean siapStart(Activity act, String dataDir, int reqTulis) {
        if (!butuhIzinEksternal(dataDir)) {
            return true;
        }
        if (sudahPunyaAkses(act)) {
            return true;
        }

        boolean diminta = mintaIzinBilaPerlu(act, reqTulis);
        if (!diminta) {
            Toast.makeText(act, act.getString(R.string.izin_storage_belum),
                    Toast.LENGTH_LONG).show();
        }
        ServerService.catatLog("[app] Start dibatalkan: izin penyimpanan belum diberikan "
                + "untuk " + dataDir + ".");
        return false;
    }

    public static void tanganiPenolakan(Activity act) {
        Toast.makeText(act, act.getString(R.string.izin_storage_ditolak),
                Toast.LENGTH_LONG).show();
        ServerService.catatLog("[app] Izin penyimpanan ditolak user.");
    }
}
