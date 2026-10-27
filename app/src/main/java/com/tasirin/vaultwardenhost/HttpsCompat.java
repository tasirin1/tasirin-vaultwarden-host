package com.tasirin.vaultwardenhost;

import android.content.Context;

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

    private static volatile long bangunGagalAt = 0;

    static final long JEDA_BANGUN_GAGAL_MS = 60_000;

    private static final class JedaBangunTrust extends Exception {
    }

    static boolean bolehBangunTrust(long gagalAt, long kini) {
        return gagalAt <= 0 || kini < gagalAt || kini - gagalAt >= JEDA_BANGUN_GAGAL_MS;
    }

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
            } catch (JedaBangunTrust ignored) {
            } catch (Exception e) {
                bangunGagalAt = android.os.SystemClock.elapsedRealtime();
                catatGagalTrust(e);
            }
        }
    }

    private static volatile String gagalTrustTerakhir;

    static String gagalTrustTerakhir() {
        return gagalTrustTerakhir;
    }

    static boolean sinyalGagalTrust(String sebelumnya, String kini) {
        return kini != null && !kini.equals(sebelumnya);
    }

    static void catatGagalTrust(Exception e) {
        String kini = String.valueOf(e);
        try {
            android.util.Log.w("HttpsCompat", "Gagal pasang trust tambahan: " + e);
        } catch (Exception ignored) {
        }
        if (sinyalGagalTrust(gagalTrustTerakhir, kini)) {
            gagalTrustTerakhir = kini;
            try {
                ServerService.catatLog("[app] Trust tambahan GitHub gagal"
                        + " dipasang (" + kini + "); koneksi memakai trust"
                        + " bawaan Android.");
            } catch (Exception ignored) {
            }
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
        SSLSocketFactory f = cached;
        if (f != null) {
            return f;
        }
        synchronized (HttpsCompat.class) {

            if (cached != null) {
                return cached;
            }
            if (!bolehBangunTrust(bangunGagalAt,
                    android.os.SystemClock.elapsedRealtime())) {
                throw new JedaBangunTrust();
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
            bangunGagalAt = 0;

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
