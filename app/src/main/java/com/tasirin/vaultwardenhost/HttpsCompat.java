package com.tasirin.vaultwardenhost;

import android.content.Context;
import android.os.SystemClock;

import java.io.File;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.Socket;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.util.Enumeration;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManagerFactory;

public final class HttpsCompat {

    private static volatile SSLSocketFactory cached;

    private static volatile long cachedCap = -1L;

    private static final Object KUNCI_CAP = new Object();

    private static volatile String[] protokolCache = null;
    private static volatile String[] cipherCache = null;

    static String[] pilihProtokol(String[] didukung) {
        if (didukung == null || didukung.length == 0) {
            return new String[0];
        }
        int n = 0;
        for (String p : didukung) {
            if ("TLSv1.3".equals(p) || "TLSv1.2".equals(p)) {
                n++;
            }
        }
        String[] keluar = new String[n];
        int i = 0;
        for (String p : didukung) {
            if ("TLSv1.3".equals(p) || "TLSv1.2".equals(p)) {
                keluar[i++] = p;
            }
        }
        return keluar;
    }

    static boolean cipherKuat(String c) {
        if (c == null) {
            return false;
        }
        if (c.contains("RC4") || c.contains("DES") || c.contains("MD5")
                || c.contains("NULL") || c.contains("anon") || c.contains("ANON")
                || c.contains("EXPORT") || c.contains("EMPTY")) {
            return false;
        }
        return c.contains("ECDHE") || c.contains("AES_GCM") || c.contains("AES_256")
                || c.contains("CHACHA20");
    }

    static String[] saringCipher(String[] didukung) {
        if (didukung == null || didukung.length == 0) {
            return new String[0];
        }
        int n = 0;
        for (String c : didukung) {
            if (cipherKuat(c)) {
                n++;
            }
        }
        String[] keluar = new String[n];
        int i = 0;
        for (String c : didukung) {
            if (cipherKuat(c)) {
                keluar[i++] = c;
            }
        }
        return keluar;
    }

    static javax.net.ssl.SSLSocketFactory bungkusTls12(
            javax.net.ssl.SSLSocketFactory bawaan) {
        if (bawaan == null) {
            return null;
        }
        return new PabrikTls12(bawaan);
    }

    private HttpsCompat() {
    }

    public static void apply(HttpURLConnection c, Context ctx) {
        if (c instanceof HttpsURLConnection) {
            try {
                ((HttpsURLConnection) c).setSSLSocketFactory(socketFactory(ctx));
            } catch (Exception e) {

                try {
                    android.util.Log.w("HttpsCompat", "Gagal pasang trust tambahan: " + e);
                } catch (Exception ignored) {
                }
            }
        }
    }

    private static volatile long capStat = Long.MIN_VALUE;
    private static volatile long capNilai = 0L;

    private static volatile long capWaktu = 0;

    static long capOverride(Context ctx) {

        long kini = SystemClock.elapsedRealtime();

        if (capWaktu != 0 && kini - capWaktu < 60_000) {
            return capNilai;
        }

        synchronized (KUNCI_CAP) {

            long kini2 = SystemClock.elapsedRealtime();
            if (capWaktu != 0 && kini2 - capWaktu < 60_000) {
                return capNilai;
            }
            try {
                File ov = new File(ctx.getFilesDir(), "certs/" + Updater.TRUST_CHAIN_ASSET);
                if (ov.isFile()) {

                    long stat = ov.lastModified() * 31 + ov.length();
                    if (stat == capStat && capNilai != 0L) {
                        capWaktu = kini2;
                        return capNilai;
                    }

                    long cap = stat;
                    try (InputStream in = new java.io.FileInputStream(ov)) {
                        byte[] buf = new byte[8192];
                        int n;
                        while ((n = in.read(buf)) != -1) {

                            for (int i = 0; i < n; i++) {
                                cap = cap * 31 + (buf[i] & 0xff);
                            }
                        }
                    } catch (Exception ignored) {
                    }

                    capStat = stat;
                    capNilai = cap;
                    capWaktu = kini2;
                    return cap;
                }
            } catch (Exception ignored) {
            }

            capWaktu = kini2;
            return 0L;
        }
    }

    static boolean aliasCaSistem(String alias) {
        return alias != null && !alias.startsWith("user:");
    }

    private static final java.security.SecureRandom ACAK_SSL = new java.security.SecureRandom();

    static java.security.SecureRandom acakBersama() {
        return ACAK_SSL;
    }

    private static SSLSocketFactory socketFactory(Context ctx) throws Exception {
        long cap = capOverride(ctx);
        SSLSocketFactory f = cached;
        if (f != null && cap == cachedCap) {
            return f;
        }
        synchronized (HttpsCompat.class) {

            cap = capOverride(ctx);
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

                    if (!aliasCaSistem(a)) {
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

            try (InputStream in = ctx.getAssets().open("certs/github-chain.pem")) {
                java.util.Collection<? extends Certificate> chain = cf.generateCertificates(in);
                if (chain == null || chain.isEmpty()) {
                    throw new Exception("chain kosong");
                }
                int i = 0;
                for (Certificate cert : chain) {
                    ks.setCertificateEntry("extra-" + (i++), cert);
                }
            }
            try {
                File ov = new File(ctx.getFilesDir(), "certs/" + Updater.TRUST_CHAIN_ASSET);
                if (ov.isFile()) {
                    try (InputStream in = new java.io.FileInputStream(ov)) {

                        java.util.Collection<? extends Certificate> chain = cf.generateCertificates(in);
                        if (chain == null || chain.isEmpty()) {
                            throw new Exception("chain kosong");
                        }
                        int i = 0;
                        for (Certificate cert : chain) {
                            ks.setCertificateEntry("ov-" + (i++), cert);
                        }
                    } catch (Exception ignored) {
                    }
                }
            } catch (Exception ignored) {
            }
            TrustManagerFactory tmf = TrustManagerFactory.getInstance(
                    TrustManagerFactory.getDefaultAlgorithm());
            tmf.init(ks);

            SSLContext sc;
            try {
                sc = SSLContext.getInstance("TLSv1.2");
            } catch (Exception e12) {
                sc = SSLContext.getInstance("TLS");
            }
            sc.init(null, tmf.getTrustManagers(), acakBersama());
            cached = new PabrikTls12(sc.getSocketFactory());
            cachedCap = cap;

            protokolCache = null;
            cipherCache = null;
        }
        return cached;
    }

    private static final class PabrikTls12 extends SSLSocketFactory {
        private final SSLSocketFactory bawaan;

        PabrikTls12(SSLSocketFactory bawaan) {
            this.bawaan = bawaan;
        }

        private Socket nyalakan(Socket s) {
            if (s instanceof SSLSocket) {
                SSLSocket ssl = (SSLSocket) s;

                String[] protokol = protokolCache;
                if (protokol == null) {
                    String[] dukung;
                    try {
                        dukung = ssl.getSupportedProtocols();
                    } catch (Exception ignored) {
                        dukung = null;
                    }
                    protokol = pilihProtokol(dukung);

                    if (dukung != null) {
                        protokolCache = protokol;
                    }
                }

                if (protokol.length > 0) {

                    try {
                        ssl.setEnabledProtocols(protokol);
                    } catch (Exception e) {
                        try {
                            android.util.Log.w("HttpsCompat", "Gagal set protokol TLS: " + e);
                        } catch (Exception ignored2) {
                        }
                        throw new RuntimeException(e);
                    }
                }

                String[] kuat = cipherCache;
                if (kuat == null) {
                    String[] dukung;
                    try {
                        dukung = ssl.getSupportedCipherSuites();
                    } catch (Exception ignored) {
                        dukung = null;
                    }
                    kuat = saringCipher(dukung);
                    if (dukung != null) {
                        cipherCache = kuat;
                    }
                }
                try {
                    if (kuat.length > 0) {
                        ssl.setEnabledCipherSuites(kuat);
                    }

                } catch (Exception e) {
                    try {
                        android.util.Log.w("HttpsCompat", "Gagal filter cipher: " + e);
                    } catch (Exception ignored) {
                    }

                }
            }
            return s;
        }

        @Override
        public String[] getDefaultCipherSuites() {
            return bawaan.getDefaultCipherSuites();
        }

        @Override
        public String[] getSupportedCipherSuites() {
            return bawaan.getSupportedCipherSuites();
        }

        @Override
        public Socket createSocket() throws java.io.IOException {
            return nyalakan(bawaan.createSocket());
        }

        @Override
        public Socket createSocket(String host, int port)
                throws java.io.IOException {
            return nyalakan(bawaan.createSocket(host, port));
        }

        @Override
        public Socket createSocket(String host, int port,
                InetAddress localHost, int localPort)
                throws java.io.IOException {
            return nyalakan(bawaan.createSocket(host, port, localHost, localPort));
        }

        @Override
        public Socket createSocket(InetAddress host, int port)
                throws java.io.IOException {
            return nyalakan(bawaan.createSocket(host, port));
        }

        @Override
        public Socket createSocket(InetAddress address, int port,
                InetAddress localAddress, int localPort)
                throws java.io.IOException {
            return nyalakan(bawaan.createSocket(address, port, localAddress, localPort));
        }

        @Override
        public Socket createSocket(Socket s, String host,
                int port, boolean autoClose) throws java.io.IOException {
            return nyalakan(bawaan.createSocket(s, host, port, autoClose));
        }
    }
}
