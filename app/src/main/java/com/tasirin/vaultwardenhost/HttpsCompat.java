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

    static boolean semuaProtokolDidukung(String[] protokol, String[] didukung) {
        if (protokol == null || didukung == null) {
            return false;
        }
        for (String p : protokol) {
            boolean ada = false;
            for (String d : didukung) {
                if (p.equals(d)) {
                    ada = true;
                    break;
                }
            }
            if (!ada) {
                return false;
            }
        }
        return true;
    }

    static String[] protokolCadangan(String[] dipilih, String[] didukung) {
        if (dipilih == null || didukung == null) {
            return new String[0];
        }
        java.util.List<String> hasil = new java.util.ArrayList<>();
        for (String p : dipilih) {
            for (String d : didukung) {
                if (p.equals(d)) {
                    hasil.add(p);
                    break;
                }
            }
        }
        return hasil.toArray(new String[0]);
    }

    static String[] cipherCadangan(String[] dipilih, String[] didukung) {
        if (dipilih == null || didukung == null) {
            return new String[0];
        }
        java.util.List<String> hasil = new java.util.ArrayList<>();
        for (String p : dipilih) {
            for (String d : didukung) {
                if (p.equals(d)) {
                    hasil.add(p);
                    break;
                }
            }
        }
        return hasil.toArray(new String[0]);
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
            try {
                return bangunFactory(ctx);
            } catch (Exception e) {
                bangunGagalAt = android.os.SystemClock.elapsedRealtime();
                throw e;
            }
        }
    }

    static boolean trustSistemCukup(int jumlahCaSistem) {
        return jumlahCaSistem > 0;
    }

    private static SSLSocketFactory bangunFactory(Context ctx) throws Exception {
        synchronized (HttpsCompat.class) {
            if (cached != null) {
                return cached;
            }
            KeyStore ks = KeyStore.getInstance(KeyStore.getDefaultType());
            ks.load(null, null);
            int jumlahCaSistem = 0;
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
                        jumlahCaSistem++;
                    } catch (Exception ignored) {
                    }
                }
            } catch (Exception ignored) {
            }
            if (!trustSistemCukup(jumlahCaSistem)) {
                throw new Exception("CA sistem kosong - trust tambahan tidak dipasang");
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

                String[] dukungIni;
                try {
                    dukungIni = ssl.getSupportedProtocols();
                } catch (Exception ignored) {
                    dukungIni = null;
                }
                String[] protokol = protokolCache;
                if (protokol == null) {
                    protokol = pilihProtokol(dukungIni);

                    if (dukungIni != null) {
                        protokolCache = protokol;
                    }
                } else if (dukungIni != null
                        && !semuaProtokolDidukung(protokol, dukungIni)) {
                    protokol = protokolCadangan(protokol, dukungIni);
                    if (protokol.length == 0) {
                        protokol = pilihProtokol(dukungIni);
                    }
                }

                if (protokol.length > 0) {

                    try {
                        ssl.setEnabledProtocols(protokol);
                    } catch (Exception e) {
                        String[] cadangan = dukungIni != null
                                ? protokolCadangan(protokol, dukungIni) : new String[0];
                        boolean ok = false;
                        if (cadangan.length > 0 && cadangan.length < protokol.length) {
                            try {
                                ssl.setEnabledProtocols(cadangan);
                                ok = true;
                            } catch (Exception ignored) {
                            }
                        }
                        if (!ok) {
                            try {
                                android.util.Log.w("HttpsCompat",
                                        "Gagal set protokol TLS - memakai bawaan soket: " + e);
                            } catch (Exception ignored2) {
                            }
                        }
                    }
                }

                String[] dukungCipher;
                try {
                    dukungCipher = ssl.getSupportedCipherSuites();
                } catch (Exception ignored) {
                    dukungCipher = null;
                }
                String[] kuat = cipherCache;
                if (kuat == null) {
                    kuat = saringCipher(dukungCipher);
                    if (dukungCipher != null) {
                        cipherCache = kuat;
                    }
                } else if (dukungCipher != null) {
                    String[] irisan = cipherCadangan(kuat, dukungCipher);
                    if (irisan.length < kuat.length) {
                        kuat = irisan.length > 0
                                ? irisan : saringCipher(dukungCipher);
                    }
                }
                try {
                    if (kuat.length > 0) {
                        ssl.setEnabledCipherSuites(kuat);
                    }

                } catch (Exception e) {
                    String[] cadangan = dukungCipher != null
                            ? cipherCadangan(kuat, dukungCipher) : new String[0];
                    boolean ok = false;
                    if (cadangan.length > 0 && cadangan.length < kuat.length) {
                        try {
                            ssl.setEnabledCipherSuites(cadangan);
                            ok = true;
                        } catch (Exception ignored) {
                        }
                    }
                    if (!ok) {
                        try {
                            android.util.Log.w("HttpsCompat", "Gagal filter cipher: " + e);
                        } catch (Exception ignored) {
                        }
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
