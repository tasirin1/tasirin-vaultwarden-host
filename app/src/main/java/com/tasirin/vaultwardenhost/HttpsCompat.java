package com.tasirin.vaultwardenhost;

import android.content.Context;

import java.io.File;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.Socket;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.util.Enumeration;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
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

    /** Kunci stat murah (mtime+ukuran): cap penuh dihitung ulang hanya bila stat
     *  berubah, agar tiap koneksi HTTPS (polling bot) tak membaca seluruh file. */
    private static volatile long capStat = Long.MIN_VALUE;
    private static volatile long capNilai = 0L;

    /** Cap file override anchor (0 bila tak ada): kunci invalidasi cache.
     *  Memakai mtime+ukuran+hash isi seperti ServerService.capCaAktif agar
     *  refresh se-detik berukuran sama tak memakai factory basi. */
    static long capOverride(Context ctx) {
        // Sinkron agar dua thread polling tak berlomba baca-tulis capStat/capNilai
        // (satu bisa menimpa hasil segar dengan nilai basi). I/O kecil (chain KB).
        synchronized (HttpsCompat.class) {
            try {
                File ov = new File(ctx.getFilesDir(), "certs/" + Updater.TRUST_CHAIN_ASSET);
                if (ov.isFile()) {
                    long stat = ov.lastModified() * 31 + ov.length();
                    if (stat == capStat) {
                        return capNilai;
                    }
                    // Tanpa perkalian raksasa (rawan overflow): gabung mtime + panjang
                    // lalu campur hash SELURUH isi agar perubahan ekor file tak lolos.
                    long cap = stat;
                    try (InputStream in = new java.io.FileInputStream(ov)) {
                        byte[] buf = new byte[8192];
                        int n;
                        while ((n = in.read(buf)) != -1) {
                            cap = cap * 31 + (java.util.Arrays.hashCode(
                                    java.util.Arrays.copyOf(buf, n)) & 0xffffffffL);
                        }
                    } catch (Exception ignored) {
                    }
                    capStat = stat;
                    capNilai = cap;
                    return cap;
                }
            } catch (Exception ignored) {
            }
            return 0L;
        }
    }

    private static SSLSocketFactory socketFactory(Context ctx) throws Exception {
        long cap = capOverride(ctx);
        SSLSocketFactory f = cached;
        if (f != null && cap == cachedCap) {
            return f;
        }
        synchronized (HttpsCompat.class) {
            // Hitung ulang di dalam kunci (reentrant): cap di luar bisa basi bila
            // berkas disegarkan antar baca-cap dan masuk-kunci, lalu factory basi
            // menimpa factory segar milik thread lain.
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
            // Union (bukan ganti): bawaan selalu dimuat, berkas segar hasil
            // segarkanTrustAnchor ditumpuk di atasnya. Override valid tapi tak
            // lengkap tak boleh memutus rantai root lama sampai refresh berikut.
            // Validasi dasar: chain kosong berarti aset rusak; tolak agar tak dikira trust OK.
            // Union (bukan ganti) agar kompatibel Android 5/6: bawaan tetap dipakai.
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
                        // Validasi dasar seperti aset bawaan: chain kosong = berkas rusak, tolak.
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
            // Paksa TLSv1.2 di API 21/22: konteks "TLS" bawaan hanya
            // mengaktifkan TLSv1 di sana sehingga HTTPS GitHub (wajib
            // >=1.2) gagal; di HP baru 1.3 tetap dirundingkan bila ada.
            SSLContext sc;
            try {
                sc = SSLContext.getInstance("TLSv1.2");
            } catch (Exception e12) {
                sc = SSLContext.getInstance("TLS");
            }
            sc.init(null, tmf.getTrustManagers(), new SecureRandom());
            cached = new PabrikTls12(sc.getSocketFactory());
            cachedCap = cap;
        }
        return cached;
    }

    /** Pembungkus factory yang menyalakan TLSv1.2+ di tiap soket.
     *  API 21/22 mendukung TLSv1.2 tapi tak mengaktifkannya by default;
     *  tanpa ini handshake ke GitHub (wajib >=1.2) gagal di Android 5.0/5.1.
     *  Murni delegasi + setEnabledProtocols (API 1, aman untuk minSdk 21). */
    private static final class PabrikTls12 extends SSLSocketFactory {
        private final SSLSocketFactory bawaan;

        PabrikTls12(SSLSocketFactory bawaan) {
            this.bawaan = bawaan;
        }

        private Socket nyalakan(Socket s) {
            if (s instanceof SSLSocket) {
                SSLSocket ssl = (SSLSocket) s;
                java.util.ArrayList<String> mau = new java.util.ArrayList<String>();
                // Hanya TLS modern (1.2/1.3): 1.0/1.1 usang dan GitHub wajib 1.2+.
                try {
                    for (String p : ssl.getSupportedProtocols()) {
                        if ("TLSv1.3".equals(p) || "TLSv1.2".equals(p)) {
                            mau.add(p);
                        }
                    }
                } catch (Exception ignored) {
                }
                if (!mau.isEmpty()) {
                    // Jangan fail-open diam: bila set protokol gagal, catat lalu lempar
                    // agar pemanggil tahu handshake tak aman.
                    try {
                        ssl.setEnabledProtocols(mau.toArray(new String[0]));
                    } catch (Exception e) {
                        try {
                            android.util.Log.w("HttpsCompat", "Gagal set protokol TLS: " + e);
                        } catch (Exception ignored2) {
                        }
                        throw new RuntimeException(e);
                    }
                }
                // Filter cipher lemah: hanya cipher modern agar tak negosiasi RC4/3DES.
                // Bila hasil filter kosong (Android 5 tua), jangan set sama sekali
                // dan pakai bawaan agar tetap kompatibel API 21.
                try {
                    String[] didukung = ssl.getSupportedCipherSuites();
                    java.util.ArrayList<String> kuat = new java.util.ArrayList<String>();
                    if (didukung != null) {
                        for (String c : didukung) {
                            if (c != null && (c.contains("ECDHE")
                                    || c.contains("AES_GCM") || c.contains("AES_256"))) {
                                kuat.add(c);
                            }
                        }
                    }
                    if (!kuat.isEmpty()) {
                        ssl.setEnabledCipherSuites(kuat.toArray(new String[0]));
                    }
                    // Bila kosong: pakai bawaan (jangan set) agar API 21 tetap bisa handshake.
                } catch (Exception e) {
                    try {
                        android.util.Log.w("HttpsCompat", "Gagal filter cipher: " + e);
                    } catch (Exception ignored) {
                    }
                    // Sengaja tidak throw untuk cipher agar kompatibel Android 5/6.
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
