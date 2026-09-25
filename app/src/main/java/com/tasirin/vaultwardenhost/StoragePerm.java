package com.tasirin.vaultwardenhost;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.Settings;
import android.widget.Toast;

/** Izin penyimpanan untuk semua versi Android (Bahasa Indonesia).
 *  Satu pintu agar MainActivity dan SettingsActivity berperilaku sama:
 *  Android 6-10 cukup izin runtime biasa, Android 11+ butuh
 *  All files access agar /storage/emulated/0/vaultwarden writable. */
public final class StoragePerm {

    private StoragePerm() {
    }

    /** True bila SDK butuh All files access (Android 11+, API 30+). */
    public static boolean butuhKelolaSemuaFile(int sdk) {
        return sdk >= Build.VERSION_CODES.R;
    }

    /** Izin runtime yang diminta (dipakai di bawah API 30 dan pelengkap di 30+). */
    public static String[] izinRuntime(int sdk) {
        if (sdk < Build.VERSION_CODES.M) {
            return new String[0];
        }
        return new String[]{
                Manifest.permission.WRITE_EXTERNAL_STORAGE,
                Manifest.permission.READ_EXTERNAL_STORAGE,
        };
    }

    /** True bila folder data berada di storage eksternal sehingga butuh izin. */
    public static boolean butuhIzinEksternal(String dataDir) {
        if (dataDir == null) {
            return true;
        }
        String d = dataDir.trim();
        if (d.isEmpty()) {
            return true;
        }
        return d.startsWith("/sdcard")
                || d.startsWith("/storage")
                || d.startsWith("/mnt");
    }

    /** Keputusan akses murni (mudah diuji): di API 30+ hanya All files access
     *  yang cukup untuk /storage/emulated/0 — izin runtime biasa tidak berlaku. */
    static boolean cukupAkses(int sdk, boolean kelolaSemuaFile, boolean tulisDiberikan) {
        if (sdk >= 30) {
            return kelolaSemuaFile;
        }
        if (sdk >= 23) {
            return tulisDiberikan;
        }
        return true;
    }

    /** True bila app sudah boleh tulis ke folder eksternal pada SDK ini. */
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

    /** True bila hasil request izin mengandung persetujuan tulis (murni, mudah diuji).
     *  Jangan cek grantResults[0] buta: urutan izin yang diminta bisa berubah. */
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

    /** Minta izin bila belum ada. Kembalikan true bila ada aksi diminta/dibuka. */
    public static boolean mintaIzinBilaPerlu(Activity act, int reqTulis) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            boolean kelola = false;
            try {
                kelola = Environment.isExternalStorageManager();
            } catch (Exception ignored) {
            }
            if (!kelola) {
                tampilDialogKelola(act);
                return true;
            }
        }
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

    /** Cegah Start buta: folder eksternal tanpa izin langsung ditolak dengan pesan jelas. */
    public static boolean siapStart(Activity act, String dataDir, int reqTulis) {
        if (!butuhIzinEksternal(dataDir)) {
            return true;
        }
        if (sudahPunyaAkses(act)) {
            return true;
        }
        mintaIzinBilaPerlu(act, reqTulis);
        Toast.makeText(act, act.getString(R.string.izin_storage_belum),
                Toast.LENGTH_LONG).show();
        ServerService.catatLog("[app] Start dibatalkan: izin penyimpanan belum diberikan "
                + "untuk " + dataDir + ".");
        return false;
    }

    /** Dialog penjelasan All files access (ramah D-pad, tombol besar). */
    private static void tampilDialogKelola(final Activity act) {
        try {
            new AlertDialog.Builder(act)
                    .setTitle(act.getString(R.string.izin_storage_judul))
                    .setMessage(act.getString(R.string.izin_storage_pesan))
                    .setPositiveButton(act.getString(R.string.izin_storage_buka),
                            (d, w) -> bukaPengaturanKelola(act))
                    .setNegativeButton(act.getString(R.string.izin_storage_nanti), null)
                    .show();
        } catch (Exception ignored) {
            bukaPengaturanKelola(act);
        }
    }

    /** Buka layar All files access untuk app ini (dengan fallback). */
    public static void bukaPengaturanKelola(Activity act) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return;
        }
        try {
            Intent i = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                    Uri.parse("package:" + act.getPackageName()));
            act.startActivity(i);
            return;
        } catch (Exception ignored) {
        }
        try {
            act.startActivity(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
        } catch (Exception e) {
            Toast.makeText(act, act.getString(R.string.izin_storage_belum),
                    Toast.LENGTH_LONG).show();
        }
    }

    /** Pesan penolakan izin runtime biasa (dipakai onRequestPermissionsResult). */
    public static void tanganiPenolakan(Activity act) {
        Toast.makeText(act, act.getString(R.string.izin_storage_ditolak),
                Toast.LENGTH_LONG).show();
        ServerService.catatLog("[app] Izin penyimpanan ditolak user.");
    }
}
