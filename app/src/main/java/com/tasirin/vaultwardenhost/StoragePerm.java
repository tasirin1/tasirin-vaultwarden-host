package com.tasirin.vaultwardenhost;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.Settings;
import android.widget.Toast;

public final class StoragePerm {

    private StoragePerm() {
    }

    public static boolean butuhKelolaSemuaFile(int sdk) {
        return sdk >= Build.VERSION_CODES.R;
    }

    private static final String[] IZIN_KOSONG = new String[0];
    // Audit 1483: cukup WRITE — READ satu grup izin (STORAGE)
    // dengan WRITE, jadi WRITE yang diberikan ikut memberi READ
    // pada model izin runtime targetSdk ini; READ tetap ada di
    // manifest untuk FileShareProvider. Meminta keduanya hanya
    // menambah gesekan dialog.
    private static final String[] IZIN_RW = new String[]{
            Manifest.permission.WRITE_EXTERNAL_STORAGE,
    };

    public static String[] izinRuntime(int sdk) {
        if (sdk < Build.VERSION_CODES.M) {
            return IZIN_KOSONG;
        }
        // Audit 2968: sejak API 30 izin tulis runtime tak lagi
        // membuka jalur berkas sembarang (scoped storage) dan
        // sejak target 33 tak bisa diberikan sama sekali -
        // folder kustom jalurnya izin akses semua file.
        if (butuhKelolaSemuaFile(sdk)) {
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
        if (folderPribadiAplikasi(d)) {
            return false;
        }
        return d.equals("/sdcard") || d.startsWith("/sdcard/")
                || d.equals("/storage") || d.startsWith("/storage/")
                || d.equals("/mnt") || d.startsWith("/mnt/");
    }

    static boolean folderPribadiAplikasi(String d) {
        String segmen = "Android/data/com.tasirin.vaultwardenhost";
        int potong = -1;
        if (d.startsWith("/sdcard/")) {
            potong = "/sdcard/".length();
        } else if (d.startsWith("/storage/") || d.startsWith("/mnt/")) {
            int awal = d.startsWith("/storage/")
                    ? "/storage/".length() : "/mnt/".length();
            int garis = d.indexOf('/', awal);
            if (garis > awal) {
                potong = garis + 1;
                // Audit 2778: media_rw juga akar dua
                // segmen (segmen kedua UUID volume),
                // sejajar emulated/<id-pengguna>.
                // Audit 2811: expand juga - volume
                // adoptable terpasang di
                // /mnt/expand/<uuid>, dua segmen
                // sebelum Android/data.
                if (d.startsWith("emulated/", awal)
                        || d.startsWith("media_rw/", awal)
                        || d.startsWith("expand/", awal)) {
                    int garis2 = d.indexOf('/', potong);
                    if (garis2 > potong) {
                        potong = garis2 + 1;
                    }
                }
            }
        }
        if (potong < 0) {
            return false;
        }
        String sisa = d.substring(potong);
        return sisa.equals(segmen) || sisa.startsWith(segmen + "/");
    }

    static boolean cukupAkses(int sdk, boolean kelolaSemuaFile, boolean tulisDiberikan) {
        if (butuhKelolaSemuaFile(sdk)) {
            // Audit 2968: grant WRITE lama bukan akses nyata ke
            // folder kustom sejak scoped storage (API 30+) -
            // hanya akses semua file yang berlaku.
            return kelolaSemuaFile;
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

    public static boolean mintaIzinBilaPerlu(Activity act, String dataDir,
            int reqTulis) {
        if (!butuhIzinEksternal(dataDir)) {
            return false;
        }
        return mintaIzinBilaPerlu(act, reqTulis);
    }

    public static boolean mintaIzinBilaPerlu(Activity act, int reqTulis) {
        // Audit 2968: di API 30+ tak ada izin runtime yang bisa
        // diminta untuk folder kustom; memintanya hanya memicu
        // penolakan semu. Jalur grant-nya layar akses semua
        // file, dibuka siapStart atas aksi pengguna.
        if (butuhKelolaSemuaFile(Build.VERSION.SDK_INT)) {
            return false;
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

    public static boolean siapStart(Activity act, String dataDir, int reqTulis) {
        if (!butuhIzinEksternal(dataDir)) {
            return true;
        }
        if (sudahPunyaAkses(act)) {
            return true;
        }

        // Audit 2968: API 30+ - satu-satunya jalan memberi akses
        // folder kustom adalah layar akses semua file sistem;
        // dibuka dari aksi Start yang disengaja pengguna.
        if (butuhKelolaSemuaFile(Build.VERSION.SDK_INT)) {
            boolean dibuka = bukaPengaturanSemuaFile(act);
            Toast.makeText(act, act.getString(R.string.izin_semua_file_panduan),
                    Toast.LENGTH_LONG).show();
            ServerService.catatLog("[app] Start dibatalkan: folder kustom butuh izin"
                    + " akses semua file (Android 11+) untuk " + dataDir
                    + (dibuka ? "." : " - layar pengaturan gagal dibuka, aktifkan"
                    + " manual lewat Pengaturan HP."));
            return false;
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

    // Audit 2968: layar sistem akses semua file untuk aplikasi
    // ini; fallback ke daftar semua aplikasi bila layar langsung
    // tak tersedia. Hanya dipanggil pada API 30+.
    static boolean bukaPengaturanSemuaFile(Activity act) {
        try {
            Intent langsung = new Intent(
                    Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                    Uri.parse("package:" + act.getPackageName()));
            act.startActivity(langsung);
            return true;
        } catch (Exception e) {
            try {
                act.startActivity(new Intent(
                        Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
                return true;
            } catch (Exception e2) {
                return false;
            }
        }
    }

    static boolean penolakanPermanen(boolean rationaleTampil, boolean sudahDiizinkan) {
        return !rationaleTampil && !sudahDiizinkan;
    }

    // Audit 1619: badan onRequestPermissionsResult MainActivity
    // dan SettingsActivity tadinya salinan identik — kini satu
    // di sini. `lanjut` dibaca+ dibersihkan pemanggil di dalam
    // gerbang kode permintaannya sendiri sebelum memanggil ini.
    static void tanganiHasilIzinTulis(Activity act, String[] permissions,
            int[] grantResults, boolean lanjut, Runnable aksiLanjut) {
        boolean diberi = tulisDiizinkan(permissions, grantResults);
        if (!diberi) {
            tanganiPenolakan(act);
        }
        if (diberi && lanjut && !act.isFinishing() && !act.isDestroyed()) {
            aksiLanjut.run();
        }
    }

    public static void tanganiPenolakan(Activity act) {
        boolean permanen = false;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                permanen = penolakanPermanen(
                        act.shouldShowRequestPermissionRationale(
                                Manifest.permission.WRITE_EXTERNAL_STORAGE),
                        act.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                                == PackageManager.PERMISSION_GRANTED);
            } catch (Exception ignored) {
            }
        }
        Toast.makeText(act, act.getString(permanen
                        ? R.string.izin_storage_ditolak_permanen
                        : R.string.izin_storage_ditolak),
                Toast.LENGTH_LONG).show();
        ServerService.catatLog(permanen
                ? "[app] Izin penyimpanan ditolak PERMANEN (dialog tidak akan muncul lagi);"
                        + " satu-satunya jalan lewat Pengaturan HP."
                : "[app] Izin penyimpanan ditolak user.");
    }
}
