package com.tasirin.vaultwardenhost;

import android.content.Context;

import java.io.File;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.util.Enumeration;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManagerFactory;

/**
 * Bantu koneksi HTTPS tetap jalan di Android 5/6: trust store sistem sudah usang,
 * jadi kita tambahkan root CA GitHub (ISRG Root X1 / Let's Encrypt, USERTrust ECC)
 * dari assets ke trust manager.
 */
public final class HttpsCompat {

    private static volatile SSLSocketFactory cached;
    /** Cap override anchor (mtime+ukuran); gugur bila file disegarkan. */
    private static volatile long cachedCap = -1L;

    private HttpsCompat() {
    }

    /** Terapkan socket factory yang mempercayai root sistem + root tambahan. */
    public static void apply(HttpURLConnection c, Context ctx) {
        if (c instanceof HttpsURLConnection) {
            try {
                ((HttpsURLConnection) c).setSSLSocketFactory(socketFactory(ctx));
            } catch (Exception e) {
                // Jangan bungkam: tanpa root tambahan Android 5/6 gagal SSL
                // dengan pesan generik. Catat agar log jelas aset mana rusak.
                try {
                    android.util.Log.w("HttpsCompat", "Gagal pasang trust tambahan: " + e);
                } catch (Exception ignored) {
                }
            }
        }
    }

    /** Cap file override anchor (0 bila tak ada): kunci invalidasi cache. */
    private static long capOverride(Context ctx) {
        try {
            File ov = new File(ctx.getFilesDir(), "certs/" + Updater.TRUST_CHAIN_ASSET);
            if (ov.isFile()) {
                return ov.lastModified() * 1000000L + ov.length();
            }
        } catch (Exception ignored) {
        }
        return 0L;
    }

    private static SSLSocketFactory socketFactory(Context ctx) throws Exception {
        long cap = capOverride(ctx);
        SSLSocketFactory f = cached;
        if (f != null && cap == cachedCap) {
            return f;
        }
        synchronized (HttpsCompat.class) {
            if (cached != null && cap == cachedCap) {
                return cached;
            }
            KeyStore ks = KeyStore.getInstance(KeyStore.getDefaultType());
            ks.load(null, null);
            try {
                KeyStore sys = KeyStore.getInstance("AndroidCAStore");
                sys.load(null, null);
                Enumeration<String> aliases = sys.aliases();
                while (aliases.hasMoreElements()) {
                    String a;
                    try {
                        a = aliases.nextElement();
                    } catch (Exception ignored) {
                        continue;
                    }
                    try {
                        java.security.cert.Certificate c = sys.getCertificate(a);
                        if (c == null) {
                            continue;
                        }
                        ks.setCertificateEntry(a, c);
                    } catch (Exception ignored) {
                    }
                }
            } catch (Exception ignored) {
            }
            CertificateFactory cf = CertificateFactory.getInstance("X.509");
            // Override hasil segarkanTrustAnchor diutamakan bila valid; bila
            // rusak, jatuh ke bawaan (fail-safe, bukan gagal total).
            boolean pakaiBawaan = true;
            try {
                File ov = new File(ctx.getFilesDir(), "certs/" + Updater.TRUST_CHAIN_ASSET);
                if (ov.isFile()) {
                    try (InputStream in = new java.io.FileInputStream(ov)) {
                        int i = 0;
                        for (Certificate cert : cf.generateCertificates(in)) {
                            ks.setCertificateEntry("ov-" + (i++), cert);
                        }
                        if (i > 0) {
                            pakaiBawaan = false;
                        }
                    } catch (Exception ignored) {
                    }
                }
            } catch (Exception ignored) {
            }
            if (pakaiBawaan) {
            try (InputStream in = ctx.getAssets().open("certs/github-chain.pem")) {
                int i = 0;
                for (Certificate cert : cf.generateCertificates(in)) {
                    ks.setCertificateEntry("extra-" + (i++), cert);
                }
            }
            }
            TrustManagerFactory tmf = TrustManagerFactory.getInstance(
                    TrustManagerFactory.getDefaultAlgorithm());
            tmf.init(ks);
            // "TLS" umum agar negosiasi 1.2 di Android 5/6 dan 1.3 di HP baru.
            SSLContext sc = SSLContext.getInstance("TLS");
            sc.init(null, tmf.getTrustManagers(), new SecureRandom());
            cached = sc.getSocketFactory();
            cachedCap = cap;
        }
        return cached;
    }
}
