package com.tasirin.vaultwardenhost;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
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
        return null;
    }

    @Override
    public String getType(Uri uri) {
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

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        String path = uri.getPath();
        File f = path == null ? null : new File(path);
        if (f == null || !f.exists() || !f.isFile()) {
            throw new FileNotFoundException(String.valueOf(uri));
        }
        // Hanya file di lokasi yang memang perlu dibagikan (cert, backup,
        // export config, cache): tolak yang lain meski provider internal.
        try {
            String canon = f.getCanonicalPath();
            if (!isShareable(canon)) {
                throw new FileNotFoundException("Lokasi tidak diizinkan: " + uri);
            }
        } catch (FileNotFoundException e) {
            throw e;
        } catch (Exception e) {
            throw new FileNotFoundException(String.valueOf(uri));
        }
        return ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY);
    }

    /** True bila file boleh dibagikan: internal/cache app, atau tls/ & backups/
     *  di folder data. Database mentah (db.sqlite3*) tidak ikut dibagikan. */
    private boolean isShareable(String canon) {
        try {
            String files = getContext().getFilesDir().getCanonicalPath();
            String cache = getContext().getCacheDir().getCanonicalPath();
            if (canon.startsWith(files + File.separator)
                    || canon.startsWith(cache + File.separator)) {
                return true;
            }
            android.content.SharedPreferences sp = getContext().getSharedPreferences(
                    ServerService.PREFS, android.content.Context.MODE_PRIVATE);
            String dataDir = sp.getString(ServerService.KEY_DATA_DIR,
                    ServerService.DEFAULT_DATA_DIR);
            if (dataDir == null || dataDir.trim().isEmpty()) {
                dataDir = ServerService.DEFAULT_DATA_DIR;
            }
            String data = new File(dataDir).getCanonicalPath();
            String name = new File(canon).getName();
            if (name.startsWith("db.sqlite3")) {
                return false;
            }
            return canon.startsWith(data + File.separator + "tls" + File.separator)
                    || canon.startsWith(data + File.separator + "backups" + File.separator);
        } catch (Exception e) {
            return false;
        }
    }
}
