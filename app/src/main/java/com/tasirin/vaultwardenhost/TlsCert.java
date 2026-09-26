package com.tasirin.vaultwardenhost;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.SecureRandom;
import java.security.Signature;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * Membuat CA lokal + sertifikat server (RSA 2048, SHA256withRSA) tanpa library eksternal.
 * Digunakan untuk HTTPS lokal (ROCKET_TLS_*).
 *
 * <p>Skema: CA self-signed (ca.pem, CA:TRUE, 10 tahun) menandatangani sertifikat
 * server (cert.pem, CA:FALSE + SAN IP/DNS, 5 tahun). Yang dipasang di HP lain cukup
 * ca.pem sebagai "CA certificate" (tanpa private key); CA stabil saat IP/domain berubah
 * sehingga tidak perlu install ulang, hanya cert.pem yang dibuat ulang.</p>
 */
public final class TlsCert {

    private TlsCert() {
    }

    /** Bump kalau struktur cert diubah; memaksa regenerasi cert lama. */
    private static final int CERT_VERSION = 6;

    /** Nama file CA (boleh disebar ke HP lain untuk dipasang sebagai CA). */
    public static final String CA_CERT_FILE = "ca.pem";

    /** Nama file kunci privat CA (milik server, jangan disebar). */
    public static final String CA_KEY_FILE = "ca-key.pem";

    /** Nama file sertifikat server (dipakai Rocket, bukan untuk dipasang). */
    public static final String LEAF_CERT_FILE = "cert.pem";

    /** Nama file kunci privat server (milik server, jangan disebar). */
    public static final String LEAF_KEY_FILE = "key.pem";

    static final String CA_CN = "Vaultwarden Android CA";
    static final String LEAF_CN = "Vaultwarden Android";

    /** Sisa hari masa berlaku cert.pem; 0 bila kedaluwarsa, -2 bila belum
     *  valid (jam STB miring ke masa lalu), -1 bila tidak bisa dibaca. */
    public static long daysLeft(File certFile) {
        try (FileInputStream in = new FileInputStream(certFile)) {
            X509Certificate cert = (X509Certificate) CertificateFactory
                    .getInstance("X.509").generateCertificate(in);
            try {
                cert.checkValidity();
            } catch (java.security.cert.CertificateNotYetValidException belum) {
                return -2;
            } catch (Exception tidakValid) {
                return 0;
            }
            long ms = cert.getNotAfter().getTime() - System.currentTimeMillis();
            return ms > 0 ? ms / (24L * 3600 * 1000) : 0;
        } catch (Exception e) {
            return -1;
        }
    }

    /** True bila sertifikat belum valid karena jam perangkat miring. Murni. */
    static boolean masaBelumTiba(File certFile) {
        return daysLeft(certFile) == -2;
    }

    /** Pastikan CA + cert server ada di {@code dir}; buat bila belum. Return null bila gagal. */
    public static File ensure(File dir, List<String> ips) {
        return ensure(dir, ips, java.util.Collections.<String>emptyList());
    }

    /** Varian dengan SAN DNS tambahan (domain lokal); {@code dns} boleh kosong. */
    public static File ensure(File dir, List<String> ips, List<String> dns) {
        try {
            if (!dir.exists() && !dir.mkdirs()) {
                return null;
            }
            File caCert = new File(dir, CA_CERT_FILE);
            File caKey = new File(dir, CA_KEY_FILE);
            File certFile = new File(dir, LEAF_CERT_FILE);
            File keyFile = new File(dir, LEAF_KEY_FILE);
            if (!caOk(caCert, caKey, dir)) {
                // CA hilang / rusak / versi lama / kedaluwarsa: buat ulang semuanya.
                caCert.delete();
                caKey.delete();
                certFile.delete();
                keyFile.delete();
                if (!buatCa(caCert, caKey)) {
                    return null;
                }
                writeVersion(dir);
            }
            if (certFile.exists() && keyFile.exists()
                    && certFile.length() > 100 && keyFile.length() > 100
                    && daysLeft(certFile) > 0) {
                return dir;
            }
            // Leaf hilang / rusak / kedaluwarsa / IP atau domain berubah: buat ulang, CA tetap.
            certFile.delete();
            keyFile.delete();
            if (!buatLeaf(caKey, certFile, keyFile, ips, dns)) {
                return null;
            }
            writeVersion(dir);
            return dir;
        } catch (Exception e) {
            return null;
        }
    }

    /** True bila CA bisa dipakai: file ada, versi cocok, belum kedaluwarsa. */
    static boolean caOk(File caCert, File caKey, File dir) {
        return caCert.exists() && caKey.exists()
                && caCert.length() > 100 && caKey.length() > 100
                && certVersionOk(dir) && daysLeft(caCert) > 0;
    }

    /** Buat CA self-signed baru (CA:TRUE). Return false bila gagal. */
    private static boolean buatCa(File caCert, File caKey) {
        try {
            KeyPair kp = buatRsa2048();
            byte[] tbs = buildTbs(kp.getPublic(), CA_CN, CA_CN,
                    caExtensionsBlock(), 365 * 10);
            writePem(caCert, "CERTIFICATE", tandatangani(tbs, kp.getPrivate()));
            writePem(caKey, "PRIVATE KEY", kp.getPrivate().getEncoded());
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** Validasi nama DNS (ASCII, label 1-63 char, boleh satu label lokal); false untuk IP. */
    static boolean namaDnsValid(String host) {
        if (host == null || host.isEmpty() || host.length() > 253) {
            return false;
        }
        if (ipv4(host) != null || ipv6(host) != null) {
            return false;
        }
        String[] label = host.split("\\.", -1);
        if (label.length == 0) {
            return false;
        }
        for (String l : label) {
            if (l.isEmpty() || l.length() > 63) {
                return false;
            }
            if (!l.matches("[a-z0-9]([a-z0-9-]*[a-z0-9])?")) {
                return false;
            }
        }
        return true;
    }

    /** Pecah input domain menjadi daftar DNS valid (kecil, unik, maks 5); kosong bila tak ada. */
    static List<String> daftarDns(String mentah) {
        List<String> hasil = new java.util.ArrayList<>();
        if (mentah == null) {
            return hasil;
        }
        for (String potong : mentah.trim().toLowerCase(java.util.Locale.US).split("[,\\s]+")) {
            String h = potong.trim();
            if (h.endsWith(".")) {
                h = h.substring(0, h.length() - 1);
            }
            if (!h.isEmpty() && namaDnsValid(h) && !hasil.contains(h) && hasil.size() < 5) {
                hasil.add(h);
            }
        }
        return hasil;
    }

    /** Buat sertifikat server baru ditandatangani CA (CA tetap). Return false bila gagal. */
    private static boolean buatLeaf(File caKeyFile, File certFile, File keyFile,
            List<String> ips) {
        return buatLeaf(caKeyFile, certFile, keyFile, ips,
                java.util.Collections.<String>emptyList());
    }

    /** Varian dengan SAN DNS tambahan (domain lokal). */
    private static boolean buatLeaf(File caKeyFile, File certFile, File keyFile,
            List<String> ips, List<String> dns) {
        try {
            PrivateKey caPriv = bacaPrivateKey(caKeyFile);
            if (caPriv == null) {
                return false;
            }
            KeyPair kp = buatRsa2048();
            byte[] tbs = buildTbs(kp.getPublic(), CA_CN, LEAF_CN,
                    extensionsBlock(ips, dns), 365 * 5);
            writePem(certFile, "CERTIFICATE", tandatangani(tbs, caPriv));
            writePem(keyFile, "PRIVATE KEY", kp.getPrivate().getEncoded());
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static KeyPair buatRsa2048() throws Exception {
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
        kpg.initialize(2048);
        return kpg.generateKeyPair();
    }

    /** Tandatangani TBS dengan kunci privat (SHA256withRSA), bungkus jadi sertifikat DER. */
    private static byte[] tandatangani(byte[] tbs, PrivateKey key) throws Exception {
        Signature sig = Signature.getInstance("SHA256withRSA");
        sig.initSign(key);
        sig.update(tbs);
        byte[] signature = sig.sign();
        return der(b -> {
            b.raw(tbs);
            b.oidSha256Rsa();
            b.bitString(signature);
        }, 0x30);
    }

    /** Baca kunci privat RSA PKCS#8 dari file PEM; null bila gagal. */
    private static PrivateKey bacaPrivateKey(File pem) {
        FileInputStream in = null;
        try {
            in = new FileInputStream(pem);
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            byte[] tmp = new byte[1024];
            int n;
            while ((n = in.read(tmp)) > 0) {
                buf.write(tmp, 0, n);
            }
            String s = new String(buf.toByteArray(), StandardCharsets.US_ASCII);
            s = s.replace("-----BEGIN PRIVATE KEY-----", "")
                    .replace("-----END PRIVATE KEY-----", "")
                    .replaceAll("\\s", "");
            byte[] pkcs8 = android.util.Base64.decode(s, android.util.Base64.DEFAULT);
            return KeyFactory.getInstance("RSA")
                    .generatePrivate(new PKCS8EncodedKeySpec(pkcs8));
        } catch (Exception e) {
            return null;
        } finally {
            try {
                if (in != null) {
                    in.close();
                }
            } catch (Exception ignored) {
            }
        }
    }

    /** Susun TBS sertifikat v3 dengan issuer/subject/ekstensi/masa berlaku pilihan. */
    private static byte[] buildTbs(PublicKey subjectPub, String issuerCn, String subjectCn,
            byte[] exts, long masaHari) throws Exception {
        SecureRandom rnd = new SecureRandom();
        byte[] serialBytes = new byte[16];
        rnd.nextBytes(serialBytes);
        serialBytes[0] &= 0x7F; // positif

        Date now = new Date();
        Date notBefore = new Date(now.getTime() - TimeUnit.DAYS.toMillis(1));
        Date notAfter = new Date(now.getTime() + TimeUnit.DAYS.toMillis(masaHari));

        // version [0] EXPLICIT INTEGER 2 (v3)
        byte[] version = der(b -> b.raw(integer(BigInteger.valueOf(2))), 0xA0);
        byte[] serial = integer(new BigInteger(serialBytes));
        byte[] sigAlg = sha256RsaAlgorithmId();
        byte[] issuer = nameCn(issuerCn);
        byte[] validity = der(b -> {
            b.waktu(notBefore);
            b.waktu(notAfter);
        }, 0x30);
        byte[] subject = nameCn(subjectCn);
        byte[] spki = subjectPub.getEncoded();

        return der(b -> {
            b.raw(version);
            b.raw(serial);
            b.raw(sigAlg);
            b.raw(issuer);
            b.raw(validity);
            b.raw(subject);
            b.raw(spki);
            b.raw(exts);
        }, 0x30);
    }

    /** Ekstensi CA: BasicConstraints CA:TRUE + KeyUsage keyCertSign agar diterima installer. */
    private static byte[] caExtensionsBlock() throws Exception {
        byte[] basicConstraints = extension(new byte[]{0x06, 0x03, 0x55, 0x1D, 0x13}, true,
                octetString(der(b -> b.raw(new byte[]{0x01, 0x01, (byte) 0xFF}), 0x30)));
        // KeyUsage: digitalSignature + keyCertSign + cRLSign.
        byte[] keyUsage = extension(new byte[]{0x06, 0x03, 0x55, 0x1D, 0x0F}, true,
                octetString(new byte[]{0x03, 0x02, 0x01, (byte) 0x86}));
        byte[] extensions = der(b -> {
            b.raw(basicConstraints);
            b.raw(keyUsage);
        }, 0x30);

        // extensions [3] EXPLICIT Extensions
        return der(b -> b.raw(extensions), 0xA3);
    }

    private static byte[] extensionsBlock(List<String> ips) throws Exception {
        return extensionsBlock(ips, java.util.Collections.<String>emptyList());
    }

    private static byte[] extensionsBlock(List<String> ips, List<String> dns) throws Exception {
        final List<String> bersihDns = new java.util.ArrayList<>();
        if (dns != null) {
            for (String mentah : dns) {
                String d = mentah == null ? "" : mentah.trim()
                        .toLowerCase(java.util.Locale.US);
                if (d.endsWith(".")) {
                    d = d.substring(0, d.length() - 1);
                }
                if (namaDnsValid(d) && !bersihDns.contains(d) && bersihDns.size() < 5
                        && d.getBytes(StandardCharsets.US_ASCII).length <= 127) {
                    bersihDns.add(d);
                }
            }
        }
        byte[] generalNames = der(b -> {
            for (String ip : ips) {
                byte[] octets = ipv4(ip);
                if (octets == null) {
                    octets = ipv6(ip);
                }
                if (octets != null) {
                    b.raw(new byte[]{(byte) 0x87, (byte) octets.length});
                    b.raw(octets);
                }
            }
            b.raw(new byte[]{(byte) 0x82, 9});
            b.raw("localhost".getBytes(StandardCharsets.US_ASCII));
            for (String d : bersihDns) {
                byte[] encoded = d.getBytes(StandardCharsets.US_ASCII);
                b.raw(new byte[]{(byte) 0x82, (byte) encoded.length});
                b.raw(encoded);
            }
        }, 0x30);

        // subjectAltName: IP + localhost + DNS lokal
        byte[] san = extension(new byte[]{0x06, 0x03, 0x55, 0x1D, 0x11}, false,
                octetString(generalNames));
        // BasicConstraints CA:FALSE - cert server biasa, BUKAN CA: bila key bocor,
        // penyerang tidak bisa menerbitkan cert untuk host lain atas nama perangkat.
        byte[] basicConstraints = extension(new byte[]{0x06, 0x03, 0x55, 0x1D, 0x13}, true,
                octetString(der(b -> b.raw(new byte[]{0x01, 0x01, 0x00}), 0x30)));
        // KeyUsage: digitalSignature + keyEncipherment saja (tanpa keyCertSign).
        byte[] keyUsage = extension(new byte[]{0x06, 0x03, 0x55, 0x1D, 0x0F}, true,
                octetString(new byte[]{0x03, 0x02, 0x01, (byte) 0xA0}));
        // ExtendedKeyUsage: serverAuth saja (1.3.6.1.5.5.7.3.1).
        byte[] eku = extension(new byte[]{0x06, 0x03, 0x55, 0x1D, 0x25}, false,
                octetString(der(b -> b.raw(new byte[]{0x06, 0x08, 0x2B, 0x06,
                        0x01, 0x05, 0x05, 0x07, 0x03, 0x01}), 0x30)));

        byte[] extensions = der(b -> {
            b.raw(san);
            b.raw(basicConstraints);
            b.raw(keyUsage);
            b.raw(eku);
        }, 0x30);

        // extensions [3] EXPLICIT Extensions
        return der(b -> b.raw(extensions), 0xA3);
    }

    private static byte[] extension(byte[] oid, boolean critical, byte[] extValue) throws Exception {
        byte[] ext = der(b -> {
            b.raw(oid);
            if (critical) {
                b.raw(new byte[]{0x01, 0x01, (byte) 0xFF}); // BOOLEAN TRUE
            }
            b.raw(extValue);
        }, 0x30);
        return ext;
    }

    private static byte[] octetString(byte[] content) throws Exception {
        return der(b -> b.raw(content), 0x04);
    }

    static byte[] ipv4(String ip) {
        if (ip == null) {
            return null;
        }
        ip = ip.trim();
        if (ip.isEmpty()) {
            return null;
        }
        String[] parts = ip.split("\\.");
        if (parts.length != 4) {
            return null;
        }
        try {
            byte[] out = new byte[4];
            for (int i = 0; i < 4; i++) {
                int v = Integer.parseInt(parts[i]);
                if (v < 0 || v > 255) {
                    return null;
                }
                out[i] = (byte) v;
            }
            return out;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** Urai literal IPv6 (termasuk kompresi ::) jadi 16 byte; null bila bukan IPv6. Murni. */
    static byte[] ipv6(String ip) {
        if (ip == null) {
            return null;
        }
        String t = ip.trim();
        if (t.isEmpty() || !t.contains(":")) {
            return null;
        }
        // Kupas zona (%wlan0) dan kurung ([::1]) bila ada.
        int persen = t.indexOf('%');
        if (persen >= 0) {
            t = t.substring(0, persen);
        }
        if (t.startsWith("[") && t.endsWith("]") && t.length() > 2) {
            t = t.substring(1, t.length() - 1);
        }
        if (t.isEmpty() || t.contains(" ") || t.contains("/")) {
            return null;
        }
        // Bentuk embedded-IPv4 (::ffff:192.168.1.1): ubah ekor desimal
        // jadi dua grup heksa agar parser di bawah tetap murni heksa.
        if (t.contains(".")) {
            int titikDua = t.lastIndexOf(':');
            if (titikDua < 0) {
                return null;
            }
            byte[] oktet = ipv4(t.substring(titikDua + 1));
            if (oktet == null) {
                return null;
            }
            int tinggi = ((oktet[0] & 0xFF) << 8) | (oktet[1] & 0xFF);
            int rendah = ((oktet[2] & 0xFF) << 8) | (oktet[3] & 0xFF);
            t = t.substring(0, titikDua + 1)
                    + Integer.toHexString(tinggi) + ":" + Integer.toHexString(rendah);
        }
        try {
            int pecah = t.indexOf("::");
            String[] kiri;
            String[] kanan;
            if (pecah >= 0) {
                if (t.indexOf("::", pecah + 2) >= 0) {
                    return null;
                }
                kiri = t.substring(0, pecah).isEmpty()
                        ? new String[0] : t.substring(0, pecah).split(":", -1);
                kanan = t.substring(pecah + 2).isEmpty()
                        ? new String[0] : t.substring(pecah + 2).split(":", -1);
                if (kiri.length + kanan.length > 7) {
                    return null;
                }
            } else {
                String[] semua = t.split(":", -1);
                if (semua.length != 8) {
                    return null;
                }
                kiri = semua;
                kanan = new String[0];
            }
            int[] grup = new int[8];
            int pos = 0;
            for (String g : kiri) {
                if (g.isEmpty()) {
                    return null;
                }
                grup[pos++] = Integer.parseInt(g, 16);
            }
            int ekor = kanan.length;
            int awalKanan = 8 - ekor;
            if (pecah < 0 && pos != 8) {
                return null;
            }
            if (pecah >= 0 && pos > awalKanan) {
                return null;
            }
            int q = awalKanan;
            for (String g : kanan) {
                if (g.isEmpty()) {
                    return null;
                }
                grup[q++] = Integer.parseInt(g, 16);
            }
            for (int v : grup) {
                if (v < 0 || v > 0xFFFF) {
                    return null;
                }
            }
            byte[] out = new byte[16];
            for (int i = 0; i < 8; i++) {
                out[i * 2] = (byte) (grup[i] >> 8);
                out[i * 2 + 1] = (byte) grup[i];
            }
            return out;
        } catch (Exception e) {
            return null;
        }
    }

    private static byte[] nameCn(String cn) throws Exception {
        // AttributeTypeAndValue: SEQUENCE { OID 2.5.4.3, UTF8String }
        byte[] attr = der(b -> {
            b.oidCn();
            b.utf8(cn);
        }, 0x30);
        // RDN: SET OF AttributeTypeAndValue
        byte[] rdn = der(b -> b.raw(attr), 0x31);
        // Name: SEQUENCE OF RDN
        return der(b -> b.raw(rdn), 0x30);
    }

    private static byte[] sha256RsaAlgorithmId() {
        // SEQUENCE { OID 1.2.840.113549.1.1.11, NULL }
        return new byte[]{0x30, 0x0D, 0x06, 0x09, 0x2A, (byte) 0x86, 0x48,
                (byte) 0x86, (byte) 0xF7, 0x0D, 0x01, 0x01, 0x0B, 0x05, 0x00};
    }

    private static byte[] integer(BigInteger v) throws IOException {
        byte[] body = v.toByteArray();
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        o.write(0x02);
        len(body.length, o);
        o.write(body, 0, body.length);
        return o.toByteArray();
    }

    static boolean certVersionOk(File dir) {
        try (java.io.FileInputStream in = new java.io.FileInputStream(new File(dir, "version.txt"))) {
            byte[] buf = new byte[16];
            int n = in.read(buf);
            if (n <= 0) {
                return false;
            }
            String v = new String(buf, 0, n, StandardCharsets.US_ASCII).trim();
            return Integer.parseInt(v) >= CERT_VERSION;
        } catch (Exception e) {
            return false;
        }
    }

    private static void writeVersion(File dir) throws IOException {
        try (FileOutputStream w = new FileOutputStream(new File(dir, "version.txt"))) {
            w.write((CERT_VERSION + "\n").getBytes(StandardCharsets.US_ASCII));
        }
    }

    private static void writePem(File f, String type, byte[] der) throws IOException {
        try (OutputStreamWriter w = new OutputStreamWriter(
                new FileOutputStream(f), StandardCharsets.US_ASCII)) {
            w.write("-----BEGIN " + type + "-----\n");
            String b64 = android.util.Base64.encodeToString(der, android.util.Base64.NO_WRAP);
            for (int i = 0; i < b64.length(); i += 64) {
                w.write(b64, i, Math.min(64, b64.length() - i));
                w.write('\n');
            }
            w.write("-----END " + type + "-----\n");
        }
        if (type.contains("PRIVATE")) {
            // Best-effort: batasi ke pemilik saja bila FS mendukung chmod
            // (di /sdcard FAT tidak berpengaruh; lihat catatan tls internal).
            try {
                f.setReadable(false, false);
                f.setWritable(false, false);
                f.setReadable(true, true);
                f.setWritable(true, true);
            } catch (Exception ignored) {
            }
        }
    }

    /** Builder ASN.1 DER sederhana. */
    private interface DerFn {
        void build(Der b) throws Exception;
    }

    private static byte[] der(DerFn fn, int tag) throws Exception {
        ByteArrayOutputStream content = new ByteArrayOutputStream();
        Der b = new Der(content);
        fn.build(b);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(tag);
        len(content.size(), out);
        out.write(content.toByteArray(), 0, content.size());
        return out.toByteArray();
    }

    private static void len(int n, OutputStream o) throws IOException {
        if (n < 0x80) {
            o.write(n);
        } else if (n <= 0xFF) {
            o.write(0x81);
            o.write(n);
        } else if (n <= 0xFFFF) {
            o.write(0x82);
            o.write(n >> 8);
            o.write(n);
        } else {
            o.write(0x83);
            o.write(n >> 16);
            o.write(n >> 8);
            o.write(n);
        }
    }

    private static final class Der {
        private final OutputStream out;

        Der(OutputStream out) {
            this.out = out;
        }

        void raw(byte[] data) throws IOException {
            out.write(data, 0, data.length);
        }

        void bitString(byte[] data) throws IOException {
            out.write(0x03);
            len(data.length + 1, out);
            out.write(0); // unused bits
            out.write(data, 0, data.length);
        }

        void utf8(String s) throws IOException {
            byte[] data = s.getBytes(StandardCharsets.UTF_8);
            out.write(0x0C);
            len(data.length, out);
            out.write(data, 0, data.length);
        }

        /** UTCTime (<2050) atau GeneralizedTime (>=2050) agar CA 10 tahun tetap valid. */
        void waktu(Date d) throws IOException {
            java.util.Calendar c = java.util.Calendar.getInstance(
                    java.util.TimeZone.getTimeZone("UTC"), Locale.US);
            c.setTime(d);
            if (c.get(java.util.Calendar.YEAR) >= 2050) {
                String s = String.format(Locale.US, "%04d%02d%02d%02d%02d%02dZ",
                        c.get(java.util.Calendar.YEAR),
                        c.get(java.util.Calendar.MONTH) + 1,
                        c.get(java.util.Calendar.DAY_OF_MONTH),
                        c.get(java.util.Calendar.HOUR_OF_DAY),
                        c.get(java.util.Calendar.MINUTE),
                        c.get(java.util.Calendar.SECOND));
                byte[] data = s.getBytes(StandardCharsets.US_ASCII);
                out.write(0x18);
                len(data.length, out);
                out.write(data, 0, data.length);
                return;
            }
            utcTime(d);
        }

        void utcTime(Date d) throws IOException {
            // Wajib UTC: format lama memakai zona perangkat tapi berlabel Z.
            java.util.Calendar c = java.util.Calendar.getInstance(
                    java.util.TimeZone.getTimeZone("UTC"), Locale.US);
            c.setTime(d);
            String s = String.format(Locale.US, "%02d%02d%02d%02d%02d%02dZ",
                    c.get(java.util.Calendar.YEAR) % 100,
                    c.get(java.util.Calendar.MONTH) + 1,
                    c.get(java.util.Calendar.DAY_OF_MONTH),
                    c.get(java.util.Calendar.HOUR_OF_DAY),
                    c.get(java.util.Calendar.MINUTE),
                    c.get(java.util.Calendar.SECOND));
            byte[] data = s.getBytes(StandardCharsets.US_ASCII);
            out.write(0x17);
            len(data.length, out);
            out.write(data, 0, data.length);
        }

        void oidSha256Rsa() throws IOException {
            // SHA256withRSA: 1.2.840.113549.1.1.11 + NULL params.
            // 30 0D: SEQUENCE panjang 13 (OID 11 byte + NULL 2 byte)
            raw(new byte[]{0x30, 0x0D, 0x06, 0x09, 0x2A, (byte) 0x86, 0x48,
                    (byte) 0x86, (byte) 0xF7, 0x0D, 0x01, 0x01, 0x0B, 0x05, 0x00});
        }

        void oidCn() throws IOException {
            // commonName: 2.5.4.3
            raw(new byte[]{0x06, 0x03, 0x55, 0x04, 0x03});
        }

    }
}
