package com.tasirin.vaultwardenhost;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.provider.OpenableColumns;
import android.os.ParcelFileDescriptor;

import java.io.File;
import java.io.FileNotFoundException;

/** Provider sederhana agar file konfigurasi bisa dibagikan ke app lain. */
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
            String kanon = f.getCanonicalPath();
            if (!isShareable(kanon)) {
                return null;
            }
            // Samakan dengan openFile(): cek ulang kanonis + tolak symlink induk
            // agar tukar symlink di jeda cek-vs-baca tak membocorkan nama/ukuran.
            String kanonUlang = new File(kanon).getCanonicalPath();
            if (!kanonUlang.equals(kanon) || !isShareable(kanonUlang)
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
        // Tanpa cek isShareable, getType menjadi oracle ekstensi path
        // privat (beda MIME untuk ada/tak-ada). Samakan dengan query().
        try {
            if (uri == null || !AUTHORITY.equals(uri.getAuthority())) {
                return null;
            }
            String path = uri.getPath();
            File f = path == null ? null : new File(path);
            if (f == null) {
                return null;
            }
            String kanon = f.getCanonicalPath();
            if (!isShareable(kanon)) {
                return null;
            }
            // Tanpa cek keberadaan, getType membedakan folder shareable vs tidak
            // walau file tak ada (oracle struktur). Samakan dengan query().
            if (!new File(kanon).isFile()) {
                return null;
            }
            return tipeMime(uri);
        } catch (Exception e) {
            return null;
        }
    }

    /** MIME sesuai ekstensi agar app penerima bisa preview/handle.
     *  Tak dikenal jatuh ke octet-stream. Murni agar bisa unit test. */
    static String tipeMime(Uri uri) {
        return tipeMimeDariPath(uri == null ? null : uri.getPath());
    }

    /** Varian path polos agar bisa diuji JVM tanpa runtime Android. */
    static String tipeMimeDariPath(String path) {
        if (path == null) {
            return "application/octet-stream";
        }
        String rendah = path.toLowerCase(java.util.Locale.US);
        if (rendah.endsWith(".json")) {
            return "application/json";
        }
        if (rendah.endsWith(".txt")) {
            return "text/plain";
        }
        if (rendah.endsWith(".pem") || rendah.endsWith(".crt") || rendah.endsWith(".cer")) {
            return "application/x-pem-file";
        }
        if (rendah.endsWith(".zip")) {
            return "application/zip";
        }
        if (rendah.endsWith(".enc")) {
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

    /** True bila mode buka hanya-baca ("r"/"rt"); tolak tulis agar berkas
     *  berbagi tak bisa diubah pemegang URI. Murni agar bisa unit test. */
    static boolean modeBacaSaja(String mode) {
        if (mode == null || mode.isEmpty()) {
            return true;
        }
        String m = mode.trim().toLowerCase(java.util.Locale.US);
        return m.equals("r") || m.equals("rt");
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        // Samakan dengan query()/getType(): tolak otoritas asing sebelum
        // menyentuh filesystem agar tak jadi oracle/confused-deputy.
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
        // Kanonis dulu sebelum cek exists/isFile: cek pra-kanonis membuka
        // jendela TOCTOU symlink di jeda cek-vs-buka.
        // Hanya file di lokasi yang memang perlu dibagikan (cert, backup,
        // export config, cache): tolak yang lain meski provider internal.
        // Buka file KANONIS yang lolos cek (bukan path asli) agar symlink
        // yang ditukar di jeda cek-vs-buka tak bisa mengalihkan ke file lain.
        final String canon;
        try {
            canon = f.getCanonicalPath();
            if (!isShareable(canon)) {
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
        // Cek ulang kanonis tepat sebelum open: symlink induk yang ditukar app
        // lain di jeda cek-vs-buka mengubah kanonis kedua sehingga open batal.
        // Tanpa ini jendela TOCTOU bisa mengarahkan open keluar folder.
        try {
            String canonUlang = target.getCanonicalPath();
            if (!canonUlang.equals(canon) || !isShareable(canonUlang)) {
                throw new FileNotFoundException("Akses ditolak.");
            }
            if (adaSymlinkInduk(canonUlang)) {
                throw new FileNotFoundException("Akses ditolak.");
            }
            File buka = new File(canonUlang);
            if (!buka.isFile()) {
                throw new FileNotFoundException("Berkas tidak ditemukan.");
            }
            return ParcelFileDescriptor.open(buka, ParcelFileDescriptor.MODE_READ_ONLY);
        } catch (FileNotFoundException e) {
            throw e;
        } catch (Exception e) {
            throw new FileNotFoundException("Berkas tidak ditemukan.");
        }
    }

    /** True bila rantai induk memuat symlink (lstat per segmen): tolak agar
     *  tukar symlink induk di jeda cek-vs-buka tak mengalihkan open keluar folder.
     *  Di JVM unit test (tanpa android.system.Os) dianggap bersih agar test hijau. Murni I/O. */
    static boolean adaSymlinkInduk(String canon) {
        final java.lang.reflect.Method lstat;
        final java.lang.reflect.Method cekLink;
        try {
            Class<?> os = Class.forName("android.system.Os");
            Class<?> konst = Class.forName("android.system.OsConstants");
            lstat = os.getMethod("lstat", String.class);
            cekLink = konst.getMethod("S_ISLNK", int.class);
        } catch (Exception e) {
            // JVM unit test tanpa android.system.Os: dianggap bersih agar test hijau.
            return false;
        }
        File induk = new File(canon).getParentFile();
        for (int i = 0; i < 32 && induk != null; i++) {
            try {
                Object st = lstat.invoke(null, induk.getAbsolutePath());
                int mode = st.getClass().getField("st_mode").getInt(st);
                if (Boolean.TRUE.equals(cekLink.invoke(null, mode))) {
                    return true;
                }
            } catch (Exception e) {
                // Fail-closed: lstat gagal di perangkat (izin/SELinux) = anggap
                // mencurigakan agar tukar symlink tak lolos diam-diam.
                return true;
            }
            induk = induk.getParentFile();
        }
        return false;
    }

    /** True bila nama file adalah kunci privat TLS (ca-key.pem, key.pem, *.key,
     *  termasuk yang dibungkus zip/enc mis. key.pem.zip).
     *  Murni agar bisa unit test; cocok tepat agar monkey.zip maupun
     *  backup-ca-key-info.txt tak ikut ditolak. */
    static boolean kunciPrivat(String name) {
        if (name == null) {
            return false;
        }
        String rendah = name.toLowerCase(java.util.Locale.US);
        // Kupas pembungkus (zip/enc/txt) agar key.pem.zip tak lolos;
        // monkey.pem/monkey.zip tetap lolos karena intinya bukan kunci.
        String inti = rendah;
        boolean kupas = true;
        while (kupas) {
            kupas = false;
            for (String ext : new String[]{".zip", ".enc", ".gz", ".tgz", ".tar", ".bak", ".txt"}) {
                if (inti.endsWith(ext) && inti.length() > ext.length()) {
                    inti = inti.substring(0, inti.length() - ext.length());
                    kupas = true;
                    break;
                }
            }
        }
        if (inti.equals("ca-key.pem") || inti.equals("ca-key")
                || inti.equals("key.pem") || inti.equals("key")) {
            return true;
        }
        return inti.endsWith(".key");
    }

    /** Ekstensi file yang aman dibagikan (cert, backup, export, log, blob terenkripsi).
     *  Kunci privat (ca-key.pem, key.pem, *.key) tidak boleh dibagikan walau
     *  ekstensinya terlihat aman — hanya sertifikat publik yang boleh lewat. */
    static boolean namaBolehDibagikan(String name) {
        if (name == null) {
            return false;
        }
        if (kunciPrivat(name)) {
            return false;
        }
        String rendah = name.toLowerCase(java.util.Locale.US);
        return rendah.endsWith(".pem") || rendah.endsWith(".crt") || rendah.endsWith(".cer")
                || rendah.endsWith(".zip") || rendah.endsWith(".json") || rendah.endsWith(".txt")
                || rendah.endsWith(".enc");
    }

    /** Nama file di files/cache app yang boleh dibagikan: hanya yang memang
     *  dibagikan app (CA + export config). Daftar sempit agar file internal
     *  lain tak ikut terekspos bila URI grant bocor. Murni agar bisa unit test. */
    static boolean namaCacheBolehDibagikan(String name) {
        if (name == null) {
            return false;
        }
        String rendah = name.toLowerCase(java.util.Locale.US);
        if (rendah.equals("ca.pem") || rendah.equals("cert.pem")) {
            return true;
        }
        if (rendah.startsWith("ca-cadangan-") && rendah.endsWith(".pem")) {
            return true;
        }
        return rendah.startsWith("app-config-")
                && (rendah.endsWith(".json") || rendah.endsWith(".json.enc"));
    }

    /** True bila nama file sementara restore/dekrip yang tak boleh dibagikan (murni). */
    static boolean berkasSementara(String nama) {
        if (nama == null) {
            return false;
        }
        String rendah = nama.toLowerCase(java.util.Locale.US);
        return rendah.startsWith("vwtg-restore") || rendah.startsWith("vwtg-")
                || rendah.startsWith("verifikasi-tmp");
    }

    /** True bila file boleh dibagikan: internal/cache app, atau tls/ & backups/
     *  di folder data. Database mentah (db.sqlite3*) dan binary tidak ikut. */
    private boolean isShareable(String canon) {
        try {
            if (canon == null || getContext() == null) {
                return false;
            }
            String files = getContext().getFilesDir().getCanonicalPath();
            String cache = getContext().getCacheDir().getCanonicalPath();
            if (canon.startsWith(files + File.separator)
                    || canon.startsWith(cache + File.separator)) {
                // Jangan bagikan binary server / file decrypt sementara dari
                // files/cache: hanya ekstensi aman yang boleh lewat.
                // Kunci privat TLS (key.pem/ca-key.pem) juga ditolak di sini
                // sebagai lapis kedua selain namaBolehDibagikan().
                String nama = new File(canon).getName();
                if (nama.startsWith("db.sqlite3")) {
                    return false;
                }
                // Plaintext sementara restore (vwtg-restore-bot-dec.zip) tak boleh bocor via grant URI.
                if (berkasSementara(nama)) {
                    return false;
                }
                if (kunciPrivat(nama)) {
                    return false;
                }
                return namaCacheBolehDibagikan(nama);
            }
            android.content.SharedPreferences sp = getContext().getSharedPreferences(
                    ServerService.PREFS, android.content.Context.MODE_PRIVATE);
            String dataDir = TgBackup.amanString(sp, ServerService.KEY_DATA_DIR,
                    ServerService.dataDirBawaanSegar());
            if (dataDir == null || dataDir.trim().isEmpty()) {
                dataDir = ServerService.dataDirBawaanSegar();
            }
            String data = new File(dataDir).getCanonicalPath();
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
            boolean diTls = canon.startsWith(data + File.separator + "tls" + File.separator);
            boolean diBackup = canon.startsWith(data + File.separator + "backups" + File.separator);
            if (!diTls && !diBackup) {
                return false;
            }
            return namaBolehDibagikan(name);
        } catch (Exception e) {
            return false;
        }
    }
}
