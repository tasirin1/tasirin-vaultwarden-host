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

/**
 * Bantu koneksi HTTPS tetap jalan di Android 5/6: trust store sistem sudah usang,
 * jadi kita tambahkan root CA GitHub (ISRG Root X1 / Let's Encrypt, USERTrust ECC)
 * dari assets ke trust manager.
 */
public final class HttpsCompat {

    private static volatile SSLSocketFactory cached;
    /** Cap override anchor (mtime+ukuran); gugur bila file disegarkan. */
    private static volatile long cachedCap = -1L;
    /** Kunci khusus cap stat: poll 20 dtk + health 30 dtk tak boleh antre
     *  di kunci kelas yang dipakai build factory (I/O keystore). */
    private static final Object KUNCI_CAP = new Object();
    /** Pilihan protokol/cipher terhitung (null = belum): dukungan soket
     *  konstan per perangkat + factory tunggal sehingga hitung sekali,
     *  bukan tiap handshake. Gugur bersama factory saat cap berubah. */
    private static volatile String[] protokolCache = null;
    private static volatile String[] cipherCache = null;
    /** Pilih protokol modern dari dukungan (murni agar bisa unit test).
     *  Kosong = lewatkan set agar kompatibel Android 5 tua. */
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
    /** True bila suite modern: ECDHE/AES seperti semula, kecuali yang
     *  mengandung primitif lemah (RC4/3DES/DES/MD5/NULL/anon/EXPORT) —
     *  mis. ECDHE+3DES lolos filter lama walau komentar mengklaim gugur.
     *  "DES" aman dipakai (AES tak mengandungnya). Murni. */
    static boolean cipherKuat(String c) {
        if (c == null) {
            return false;
        }
        if (c.contains("RC4") || c.contains("DES") || c.contains("MD5")
                || c.contains("NULL") || c.contains("anon") || c.contains("ANON")
                || c.contains("EXPORT") || c.contains("EMPTY")) {
            return false;
        }
        return c.contains("ECDHE") || c.contains("AES_GCM") || c.contains("AES_256");
    }

    /** Saring cipher lemah (RC4/3DES gugur); kosong = pakai bawaan (murni). */
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

    /** Bungkus factory agar soket menyalakan TLSv1.2+ (satu pintu socketFactory()).
     *  Dipakai health-check loopback ServerService: konteks "TLS" di API 21/22
     *  default hanya TLSv1 sehingga handshake ke Rocket (min 1.2) gagal
     *  selamanya tanpa pembungkus ini. Null bila bawaan null. */
    static javax.net.ssl.SSLSocketFactory bungkusTls12(
            javax.net.ssl.SSLSocketFactory bawaan) {
        if (bawaan == null) {
            return null;
        }
        return new PabrikTls12(bawaan);
    }

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

    /** Kunci stat+isi (mtime+ukuran+hash isi): cap penuh selalu hash seluruh
     *  isi file (rantai hanya hitungan KB) agar ganti isi se-detik berukuran
     *  sama tak lolos sebagai cache basi. */
    private static volatile long capStat = Long.MIN_VALUE;
    private static volatile long capNilai = 0L;
    /** Waktu stat override terakhir (elapsedRealtime): stat ulang maks 1x/menit. */
    private static volatile long capWaktu = 0;

    /** Cap file override anchor (0 bila tak ada): kunci invalidasi cache.
     *  Memakai mtime+ukuran+hash isi seperti ServerService.capCaAktif agar
     *  refresh se-detik berukuran sama tak memakai factory basi. */
    static long capOverride(Context ctx) {
        // Gerbang TTL 60 dtk tanpa kunci/syscall (pola ServerService.capCaAktif):
        // tiap koneksi (poll bot 20 dtk + health 30 dtk + unduhan) membayar
        // kunci kelas + getFilesDir/isFile/mtime/length sia-sia di STB lama.
        // Berkas hanya berubah via segarkanTrustAnchor (maks 1x/hari) sehingga
        // basi 60 dtk aman (factory lama tetap valid, anchor bersifat aditif).
        long kini = SystemClock.elapsedRealtime();
        // Gerbang TTL memakai capWaktu, BUKAN capStat: capStat hanya diisi saat
        // berkas override ada, sehingga di kondisi normal (override tak ada)
        // gerbang lama (`capStat != Long.MIN_VALUE`) tak pernah lolos dan tiap
        // koneksi TLS tetap membayar kunci + getFilesDir + isFile.
        if (capWaktu != 0 && kini - capWaktu < 60_000) {
            return capNilai;
        }
        // Sinkron di kunci cap (bukan kunci kelas) agar polling tak antre
        // dengan build factory; cek ulang dalam kunci seperti semula.
        synchronized (KUNCI_CAP) {
            // Cek ulang dalam kunci: thread lain bisa menyegarkan saat antre.
            long kini2 = SystemClock.elapsedRealtime();
            if (capWaktu != 0 && kini2 - capWaktu < 60_000) {
                return capNilai;
            }
            try {
                File ov = new File(ctx.getFilesDir(), "certs/" + Updater.TRUST_CHAIN_ASSET);
                if (ov.isFile()) {
                    // Stat dulu (tanpa I/O isi): polling 20 dtk + health 30 dtk
                    // tak membayar buka-baca-hash tiap koneksi bila tak berubah.
                    long stat = ov.lastModified() * 31 + ov.length();
                    if (stat == capStat && capNilai != 0L) {
                        capWaktu = kini2;
                        return capNilai;
                    }
                    // Stat berubah: hash SELURUH isi (rantai hitungan KB) agar
                    // ganti isi se-detik berukuran sama tak lolos basi.
                    long cap = stat;
                    try (InputStream in = new java.io.FileInputStream(ov)) {
                        byte[] buf = new byte[8192];
                        int n;
                        while ((n = in.read(buf)) != -1) {
                            // Campur per byte tanpa Arrays.copyOf per chunk:
                            // hemat alokasi tiap segarkan cap di STB 1 GB.
                            for (int i = 0; i < n; i++) {
                                cap = cap * 31 + (buf[i] & 0xff);
                            }
                        }
                    } catch (Exception ignored) {
                    }
                    // Cek "stat == capStat && cap == capNilai" yang dulu ada di
                    // sini tak pernah bisa benar: stat yang cocok sudah pulang
                    // lewat gerbang cepat di atas (capNilai != 0) atau capNilai
                    // masih 0 sehingga hash nonzero tak mungkin sama.
                    capStat = stat;
                    capNilai = cap;
                    capWaktu = kini2;
                    return cap;
                }
            } catch (Exception ignored) {
            }
            // Override tak ada/rusak: stempel juga agar koneksi tak
            // menghujani stat tiap 20-30 dtk; refresh anchor mereset
            // via perubahan stat (basi maks 60 dtk, factory lama valid).
            capWaktu = kini2;
            return 0L;
        }
    }

    /** Acak bersama init SSL (thread-safe): seed kernel lama bisa blokir detik. */
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
            // Union (bukan ganti) agar kompatibel Android 5/6: bawaan selalu
            // dimuat, berkas segar hasil segarkanTrustAnchor ditumpuk di atasnya.
            // Override valid tapi tak lengkap tak boleh memutus rantai root lama
            // sampai refresh berikut.
            // Validasi dasar: chain kosong berarti aset rusak; tolak agar tak dikira trust OK.
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
            sc.init(null, tmf.getTrustManagers(), acakBersama());
            cached = new PabrikTls12(sc.getSocketFactory());
            cachedCap = cap;
            // Factory baru = dukungan bisa beda: hitung ulang pilihan di
            // handshake berikut (praktis hasilnya sama per perangkat).
            protokolCache = null;
            cipherCache = null;
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
                // Hitung sekali per proses (dukungan konstan per perangkat):
                // tanpa cache tiap handshake (poll 20 dtk, health 30 dtk)
                // membayar getSupported* + ArrayList + toArray di STB lama.
                // Array tak pernah diubah setelah terbit sehingga aman dipakai
                // ulang (setEnabled* menyalin isi di dalam).
                String[] protokol = protokolCache;
                if (protokol == null) {
                    String[] dukung;
                    try {
                        dukung = ssl.getSupportedProtocols();
                    } catch (Exception ignored) {
                        dukung = null;
                    }
                    protokol = pilihProtokol(dukung);
                    // Gagal baca (null) jangan di-cache agar handshake berikut
                    // mencoba lagi; hasil kosong valid tetap di-cache.
                    if (dukung != null) {
                        protokolCache = protokol;
                    }
                }
                // Hanya TLS modern (1.2/1.3): 1.0/1.1 usang dan GitHub wajib 1.2+.
                if (protokol.length > 0) {
                    // Jangan fail-open diam: bila set protokol gagal, catat lalu lempar
                    // agar pemanggil tahu handshake tak aman.
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
                // Filter cipher lemah: hanya cipher modern agar tak negosiasi RC4/3DES.
                // Bila hasil filter kosong (Android 5 tua), jangan set sama sekali
                // dan pakai bawaan agar tetap kompatibel API 21.
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
