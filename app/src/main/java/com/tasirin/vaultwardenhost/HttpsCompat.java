package com.tasirin.vaultwardenhost;

import android.content.Context;

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

    private HttpsCompat() {
    }

    /** Terapkan socket factory yang mempercayai root sistem + root tambahan. */
    public static void apply(HttpURLConnection c, Context ctx) {
        if (c instanceof HttpsURLConnection) {
            try {
                ((HttpsURLConnection) c).setSSLSocketFactory(socketFactory(ctx));
            } catch (Exception ignored) {
            }
        }
    }

    private static SSLSocketFactory socketFactory(Context ctx) throws Exception {
        if (cached != null) {
            return cached;
        }
        synchronized (HttpsCompat.class) {
            if (cached != null) {
                return cached;
            }
            KeyStore ks = KeyStore.getInstance(KeyStore.getDefaultType());
            ks.load(null, null);
            try {
                KeyStore sys = KeyStore.getInstance("AndroidCAStore");
                sys.load(null, null);
                Enumeration<String> aliases = sys.aliases();
                while (aliases.hasMoreElements()) {
                    String a = aliases.nextElement();
                    ks.setCertificateEntry(a, sys.getCertificate(a));
                }
            } catch (Exception ignored) {
            }
            CertificateFactory cf = CertificateFactory.getInstance("X.509");
            try (InputStream in = ctx.getAssets().open("certs/github-chain.pem")) {
                int i = 0;
                for (Certificate cert : cf.generateCertificates(in)) {
                    ks.setCertificateEntry("extra-" + (i++), cert);
                }
            }
            TrustManagerFactory tmf = TrustManagerFactory.getInstance(
                    TrustManagerFactory.getDefaultAlgorithm());
            tmf.init(ks);
            SSLContext sc = SSLContext.getInstance("TLSv1.2");
            sc.init(null, tmf.getTrustManagers(), new SecureRandom());
            cached = sc.getSocketFactory();
        }
        return cached;
    }
}
