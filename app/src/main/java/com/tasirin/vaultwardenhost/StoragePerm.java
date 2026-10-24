package com.tasirin.vaultwardenhost;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Environment;
import android.widget.Toast;

/** Izin penyimpanan untuk semua versi Android (Bahasa Indonesia).
 *  Satu pintu agar MainActivity dan SettingsActivity berperilaku sama:
 *  Android 6+ cukup izin tulis runtime. targetSdk 28 tetap memakai model
 *  legacy di Android 11+ (scoped storage baru wajib untuk target 30+),
 *  jadi All files access hanya alternatif yang tetap diterima bila sudah
 *  diberikan — bukan syarat agar /sdcard/vaultwarden bisa ditulis. */
public final class StoragePerm {

    private StoragePerm() {
    }

    /** True bila SDK butuh All files access (Android 11+, API 30+). */
    public static boolean butuhKelolaSemuaFile(int sdk) {
        return sdk >= Build.VERSION_CODES.R;
    }

    /** Array statis (jangan diubah pemanggil): izinRuntime dipanggil tiap cek
     *  izin sehingga alokasi array baru tiap kali membuang sampah di ART lama. */
    private static final String[] IZIN_KOSONG = new String[0];
    private static final String[] IZIN_RW = new String[]{
            Manifest.permission.WRITE_EXTERNAL_STORAGE,
            Manifest.permission.READ_EXTERNAL_STORAGE,
    };

    /** Izin runtime yang diminta (dipakai di bawah API 30 dan pelengkap di 30+).
     *  Varian berisi diklon: array bersama yang diekspos langsung bisa diubah
     *  pemanggil dan meracuni panggilan berikutnya. Varian kosong memakai
     *  konstanta bersama tanpa klon — array nol-elemen tak punya indeks yang
     *  bisa diubah sehingga aman dibagi (hemat alokasi tiap cek izin di ART lama). */
    public static String[] izinRuntime(int sdk) {
        if (sdk < Build.VERSION_CODES.M) {
            return IZIN_KOSONG;
        }
        return IZIN_RW.clone();
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
        return d.equals("/sdcard") || d.startsWith("/sdcard/")
                || d.equals("/storage") || d.startsWith("/storage/")
                || d.equals("/mnt") || d.startsWith("/mnt/");
    }

    /** Keputusan akses murni (mudah diuji): di API 30+ terima All files access
     *  ATAU izin tulis runtime — targetSdk 28 masih model legacy sehingga
     *  /sdcard bisa ditulis dengan izin biasa. All files hanya alternatif. */
    static boolean cukupAkses(int sdk, boolean kelolaSemuaFile, boolean tulisDiberikan) {
        if (butuhKelolaSemuaFile(sdk)) {
            return kelolaSemuaFile || tulisDiberikan;
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

    /** Minta izin tulis bila belum ada. Kembalikan true bila ada aksi diminta.
     *  Android 11+ ikut jalur ini: targetSdk 28 masih model legacy, jadi izin
     *  tulis runtime cukup. All files access (bila sudah diberikan) tetap
     *  diterima `cukupAkses` tanpa dipaksa lewat dialog pengaturan. */
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

    /** Cegah Start buta: folder eksternal tanpa izin langsung ditolak dengan pesan jelas. */
    public static boolean siapStart(Activity act, String dataDir, int reqTulis) {
        if (!butuhIzinEksternal(dataDir)) {
            return true;
        }
        if (sudahPunyaAkses(act)) {
            return true;
        }
        // Bila dialog izin baru dimunculkan, jangan menumpuk toast "belum
        // diberikan" di atas dialog (dulu user melihat dua pesan sekaligus).
        // Penolakan tetap dilaporkan lewat onRequestPermissionsResult.
        boolean diminta = mintaIzinBilaPerlu(act, reqTulis);
        if (!diminta) {
            Toast.makeText(act, act.getString(R.string.izin_storage_belum),
                    Toast.LENGTH_LONG).show();
        }
        ServerService.catatLog("[app] Start dibatalkan: izin penyimpanan belum diberikan "
                + "untuk " + dataDir + ".");
        return false;
    }

    /** Pesan penolakan izin runtime biasa (dipakai onRequestPermissionsResult). */
    public static void tanganiPenolakan(Activity act) {
        Toast.makeText(act, act.getString(R.string.izin_storage_ditolak),
                Toast.LENGTH_LONG).show();
        ServerService.catatLog("[app] Izin penyimpanan ditolak user.");
    }
}
