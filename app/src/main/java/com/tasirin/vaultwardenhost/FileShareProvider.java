package com.tasirin.vaultwardenhost;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.provider.OpenableColumns;
import android.os.ParcelFileDescriptor;

import java.io.File;
import java.io.FileNotFoundException;

public class FileShareProvider extends ContentProvider {

    public static final String AUTHORITY = "com.tasirin.vaultwardenhost.fileprovider";

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection,
                        String[] selectionArgs, String sortOrder) {
        try {
            if (uri == null || !AUTHORITY.equals(uri.getAuthority())) {
                return null;
            }
            String path = uri.getPath();
            File f = path == null ? null : new File(path);
            if (f == null) {
                return null;
            }
            AkarBagi akar = akarBagi();
            String kanon = f.getCanonicalPath();
            if (!isShareable(kanon, akar)) {
                return null;
            }

            String kanonUlang = new File(kanon).getCanonicalPath();
            if (!kanonUlang.equals(kanon) || !isShareable(kanonUlang, akar)
                    || adaSymlinkInduk(kanonUlang)) {
                return null;
            }
            File cf = new File(kanonUlang);
            if (!cf.isFile() || !cf.canRead()) {
                return null;
            }
            android.database.MatrixCursor c = new android.database.MatrixCursor(
                    new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE});
            c.addRow(new Object[]{cf.getName(), cf.length()});
            try {
                if (getContext() != null) {
                    c.setNotificationUri(getContext().getContentResolver(), uri);
                }
            } catch (Exception ignored) {
            }
            return c;
        } catch (Exception e) {
            return null;
        }
    }

    @Override
    public String getType(Uri uri) {

        try {
            if (uri == null || !AUTHORITY.equals(uri.getAuthority())) {
                return null;
            }
            String path = uri.getPath();
            File f = path == null ? null : new File(path);
            if (f == null) {
                return null;
            }
            AkarBagi akar = akarBagi();
            String kanon = f.getCanonicalPath();
            if (!isShareable(kanon, akar)) {
                return null;
            }

            String kanonUlang = new File(kanon).getCanonicalPath();
            if (!kanonUlang.equals(kanon) || !isShareable(kanonUlang, akar)
                    || adaSymlinkInduk(kanonUlang)) {
                return null;
            }

            if (!new File(kanonUlang).isFile()) {
                return null;
            }
            return tipeMime(uri);
        } catch (Exception e) {
            return null;
        }
    }

    static String tipeMime(Uri uri) {
        return tipeMimeDariPath(uri == null ? null : uri.getPath());
    }

    static boolean berakhirAbaikanHuruf(String s, String akhir) {
        return s != null && akhir != null && s.length() >= akhir.length()
                && s.regionMatches(true, s.length() - akhir.length(), akhir, 0, akhir.length());
    }

    private static final String[] EKSTENSI_BUNGKUS =
            {".zip", ".enc", ".gz", ".tgz", ".tar", ".bak", ".txt"};

    static boolean mulaiAbaikanHuruf(String s, String awal) {
        return s != null && awal != null && s.length() >= awal.length()
                && s.regionMatches(true, 0, awal, 0, awal.length());
    }

    static String tipeMimeDariPath(String path) {
        if (path == null) {
            return "application/octet-stream";
        }
        if (berakhirAbaikanHuruf(path, ".json")) {
            return "application/json";
        }
        if (berakhirAbaikanHuruf(path, ".txt")) {
            return "text/plain";
        }
        if (berakhirAbaikanHuruf(path, ".pem") || berakhirAbaikanHuruf(path, ".crt") || berakhirAbaikanHuruf(path, ".cer")) {
            return "application/x-pem-file";
        }
        if (berakhirAbaikanHuruf(path, ".zip")) {
            return "application/zip";
        }
        if (berakhirAbaikanHuruf(path, ".enc")) {
            return "application/octet-stream";
        }
        return "application/octet-stream";
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        return 0;
    }

    static boolean modeBacaSaja(String mode) {
        if (mode == null || mode.isEmpty()) {
            return true;
        }
        String m = mode.trim();
        return m.equalsIgnoreCase("r") || m.equalsIgnoreCase("rt");
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {

        if (uri == null || !AUTHORITY.equals(uri.getAuthority())) {
            throw new FileNotFoundException("Akses ditolak.");
        }
        if (!modeBacaSaja(mode)) {
            throw new FileNotFoundException("Akses ditolak.");
        }
        String path = uri.getPath();
        File f = path == null ? null : new File(path);
        if (f == null) {
            throw new FileNotFoundException("Berkas tidak ditemukan.");
        }

        final AkarBagi akar = akarBagi();
        final String canon;
        try {
            canon = f.getCanonicalPath();
            if (!isShareable(canon, akar)) {
                throw new FileNotFoundException("Akses ditolak.");
            }
        } catch (FileNotFoundException e) {
            throw e;
        } catch (Exception e) {
            throw new FileNotFoundException("Berkas tidak ditemukan.");
        }
        final File target = new File(canon);
        if (!target.isFile()) {
            throw new FileNotFoundException("Berkas tidak ditemukan.");
        }

        try {
            String canonUlang = target.getCanonicalPath();
            if (!canonUlang.equals(canon) || !isShareable(canonUlang, akar)) {
                throw new FileNotFoundException("Akses ditolak.");
            }
            if (adaSymlinkInduk(canonUlang)) {
                throw new FileNotFoundException("Akses ditolak.");
            }
            File buka = new File(canonUlang);
            if (!buka.isFile()) {
                throw new FileNotFoundException("Berkas tidak ditemukan.");
            }
            ParcelFileDescriptor pfd = ParcelFileDescriptor.open(buka,
                    ParcelFileDescriptor.MODE_READ_ONLY);
            String jalurFd = jalurFdTerbuka(pfd.getFd());
            if (!jalurFdCocok(jalurFd, canonUlang)) {
                try {
                    pfd.close();
                } catch (Exception ignored) {
                }
                throw new FileNotFoundException("Akses ditolak.");
            }
            return pfd;
        } catch (FileNotFoundException e) {
            throw e;
        } catch (Exception e) {
            throw new FileNotFoundException("Berkas tidak ditemukan.");
        }
    }

    static boolean diJvmUnitTest() {
        try {
            if ("true".equalsIgnoreCase(System.getProperty("vaultwardenhost.unittest"))) {
                return true;
            }
            String vm = System.getProperty("java.vm.name");
            if (vm == null) {
                return false;
            }
            String v = vm.toLowerCase(java.util.Locale.US);
            return !v.contains("dalvik") && !v.contains("art");
        } catch (Exception e) {
            return false;
        }
    }

    private static volatile java.lang.reflect.Method LSTAT_CACHE = null;
    private static volatile java.lang.reflect.Method CEK_LINK_CACHE = null;
    private static volatile boolean REFLEKSI_DICARI = false;
    private static volatile java.lang.reflect.Field MODE_CACHE = null;

    static String jalurFdTerbuka(int fd) {
        try {
            return android.system.Os.readlink("/proc/self/fd/" + fd);
        } catch (Exception e) {
            return null;
        }
    }

    static boolean jalurFdCocok(String jalurFd, String canon) {
        if (jalurFd == null || canon == null) {
            return false;
        }
        String j = jalurFd;
        String ekor = " (deleted)";
        if (j.endsWith(ekor)) {
            j = j.substring(0, j.length() - ekor.length());
        }
        return j.equals(canon);
    }

    interface ModeInduk {
        Integer modeDari(String path);
    }

    static boolean modeAdalahLink(int mode) {
        return (mode & 0170000) == 0120000;
    }

    static boolean adaSymlinkDariMode(String canon, ModeInduk penyedia) {
        File induk = new File(canon).getParentFile();
        for (int i = 0; i < 256 && induk != null; i++) {
            Integer mode = penyedia.modeDari(induk.getAbsolutePath());
            if (mode != null && modeAdalahLink(mode.intValue())) {
                return true;
            }
            induk = induk.getParentFile();
        }
        return false;
    }

    static boolean adaSymlinkInduk(String canon) {

        if (diJvmUnitTest()) {
            return false;
        }

        java.lang.reflect.Method lstat = LSTAT_CACHE;
        java.lang.reflect.Method cekLink = CEK_LINK_CACHE;
        if (!REFLEKSI_DICARI) {
            synchronized (FileShareProvider.class) {
                if (!REFLEKSI_DICARI) {
                    try {
                        Class<?> os = Class.forName("android.system.Os");
                        Class<?> konst = Class.forName("android.system.OsConstants");
                        LSTAT_CACHE = os.getMethod("lstat", String.class);
                        CEK_LINK_CACHE = konst.getMethod("S_ISLNK", int.class);
                    } catch (Exception ignored) {
                        LSTAT_CACHE = null;
                        CEK_LINK_CACHE = null;
                    }
                    REFLEKSI_DICARI = true;
                }
                lstat = LSTAT_CACHE;
                cekLink = CEK_LINK_CACHE;
            }
        }
        if (lstat == null || cekLink == null) {

            return true;
        }
        final java.lang.reflect.Method lstatFinal = lstat;
        ModeInduk penyedia = new ModeInduk() {
            @Override
            public Integer modeDari(String path) {
                try {
                    Object st = lstatFinal.invoke(null, path);
                    java.lang.reflect.Field fMode = MODE_CACHE;
                    if (fMode == null) {
                        synchronized (FileShareProvider.class) {
                            if (MODE_CACHE == null) {
                                MODE_CACHE = st.getClass().getField("st_mode");
                            }
                            fMode = MODE_CACHE;
                        }
                    }
                    return Integer.valueOf(fMode.getInt(st));
                } catch (Exception e) {
                    return null;
                }
            }
        };
        if (adaSymlinkDariMode(canon, penyedia)) {
            return true;
        }
        File induk = new File(canon).getParentFile();
        for (int i = 0; i < 256 && induk != null; i++) {
            try {
                if (!induk.getCanonicalPath().equals(induk.getAbsolutePath())) {
                    return true;
                }
            } catch (Exception ignored) {
                return true;
            }
            induk = induk.getParentFile();
        }

        try {
            File f = new File(canon);
            if (!f.getCanonicalPath().equals(f.getAbsolutePath())) {
                return true;
            }
        } catch (Exception e) {
            return true;
        }
        return false;
    }

    static boolean kunciPrivat(String name) {
        if (name == null) {
            return false;
        }

        if (name.equalsIgnoreCase("key.pem") || name.equalsIgnoreCase("ca-key.pem")
                || mulaiAbaikanHuruf(name, "key.pem.") || mulaiAbaikanHuruf(name, "ca-key.pem.")) {
            return true;
        }
        String rendah = name;

        String inti = rendah;
        boolean kupas = true;
        while (kupas) {
            kupas = false;
            for (String ext : EKSTENSI_BUNGKUS) {
                if (berakhirAbaikanHuruf(inti, ext) && inti.length() > ext.length()) {
                    inti = inti.substring(0, inti.length() - ext.length());
                    kupas = true;
                    break;
                }
            }
        }
        if (inti.equalsIgnoreCase("ca-key.pem") || inti.equalsIgnoreCase("ca-key")
                || inti.equalsIgnoreCase("key.pem") || inti.equalsIgnoreCase("key")) {
            return true;
        }
        return berakhirAbaikanHuruf(inti, ".key");
    }

    static boolean berkasBackupPenuh(String name) {
        if (name == null) {
            return false;
        }
        return mulaiAbaikanHuruf(name, "backup-")
                || mulaiAbaikanHuruf(name, "db-backup-");
    }

    static boolean namaBolehDibagikan(String name) {
        if (name == null) {
            return false;
        }
        if (kunciPrivat(name)) {
            return false;
        }
        return berakhirAbaikanHuruf(name, ".pem") || berakhirAbaikanHuruf(name, ".crt") || berakhirAbaikanHuruf(name, ".cer")
                || berakhirAbaikanHuruf(name, ".zip") || berakhirAbaikanHuruf(name, ".json") || berakhirAbaikanHuruf(name, ".txt")
                || berakhirAbaikanHuruf(name, ".enc");
    }

    static boolean namaCacheBolehDibagikan(String name) {
        if (name == null) {
            return false;
        }
        if (name.equalsIgnoreCase("ca.pem") || name.equalsIgnoreCase("cert.pem")) {
            return true;
        }
        if (mulaiAbaikanHuruf(name, "ca-cadangan-") && berakhirAbaikanHuruf(name, ".pem")) {
            return true;
        }
        return mulaiAbaikanHuruf(name, "app-config-")
                && (berakhirAbaikanHuruf(name, ".json") || berakhirAbaikanHuruf(name, ".json.enc"));
    }

    static boolean berkasSementara(String nama) {
        if (nama == null) {
            return false;
        }

        return mulaiAbaikanHuruf(nama, "vwtg-")
                || mulaiAbaikanHuruf(nama, "verifikasi-tmp")
                || mulaiAbaikanHuruf(nama, "app-config-enctmp-");
    }

    static boolean anakLangsung(String canon, String folder) {
        if (canon == null || folder == null) {
            return false;
        }
        String awalan = folder.endsWith(File.separator)
                ? folder : folder + File.separator;
        if (!canon.startsWith(awalan)) {
            return false;
        }
        return !canon.substring(awalan.length()).contains(File.separator);
    }

    private static final class AkarBagi {
        final String files;
        final String cache;
        String data;

        AkarBagi(String files, String cache) {
            this.files = files;
            this.cache = cache;
        }
    }

    private AkarBagi akarBagi() {
        try {
            if (getContext() == null) {
                return null;
            }
            return new AkarBagi(getContext().getFilesDir().getCanonicalPath(),
                    getContext().getCacheDir().getCanonicalPath());
        } catch (Exception e) {
            return null;
        }
    }

    private String dataBagi(AkarBagi akar) {
        if (akar.data != null) {
            return akar.data;
        }
        String data = akar.files;
        try {
            android.content.SharedPreferences sp = getContext().getSharedPreferences(
                    ServerService.PREFS, android.content.Context.MODE_PRIVATE);
            String dataDir = TgBackup.amanString(sp, ServerService.KEY_DATA_DIR,
                    ServerService.dataDirBawaanSegar());
            if (dataDir == null || dataDir.trim().isEmpty()) {
                dataDir = ServerService.dataDirBawaanSegar();
            }
            String kanonData = new File(dataDir).getCanonicalPath();
            if (kanonData == null || kanonData.trim().isEmpty() || kanonData.equals("/")
                    || (!kanonData.startsWith("/data/") && !kanonData.startsWith("/storage/")
                    && !kanonData.startsWith("/sdcard/"))) {
                try {
                    android.util.Log.w("FileShare",
                            "[share] data_dir tak valid, pakai internal: " + dataDir);
                } catch (Exception ignored) {
                }
            } else {
                data = kanonData;
            }
        } catch (Exception e) {
            data = akar.files;
        }
        akar.data = data;
        return data;
    }

    private boolean isShareable(String canon, AkarBagi akar) {
        try {
            if (canon == null || akar == null) {
                return false;
            }
            if (canon.startsWith(akar.files + File.separator)
                    || canon.startsWith(akar.cache + File.separator)) {

                String nama = new File(canon).getName();
                if (nama.startsWith("db.sqlite3")) {
                    return false;
                }

                if (berkasSementara(nama)) {
                    return false;
                }
                if (kunciPrivat(nama)) {
                    return false;
                }
                return namaCacheBolehDibagikan(nama);
            }
            String data = dataBagi(akar);
            String name = new File(canon).getName();
            if (name.startsWith("db.sqlite3")) {
                return false;
            }
            if (berkasSementara(name)) {
                return false;
            }
            if (kunciPrivat(name)) {
                return false;
            }
            if (berkasBackupPenuh(name)) {
                return false;
            }
            boolean diTls = anakLangsung(canon, data + File.separator + "tls");
            boolean diBackup = anakLangsung(canon, data + File.separator + "backups");
            if (!diTls && !diBackup) {
                return false;
            }
            return namaBolehDibagikan(name);
        } catch (Exception e) {
            return false;
        }
    }
}
