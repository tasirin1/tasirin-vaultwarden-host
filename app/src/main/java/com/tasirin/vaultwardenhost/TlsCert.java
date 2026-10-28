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

public final class TlsCert {

    private TlsCert() {
    }

    private static final int CERT_VERSION = 6;

    public static final String CA_CERT_FILE = "ca.pem";

    public static final String CA_KEY_FILE = "ca-key.pem";

    public static final String LEAF_CERT_FILE = "cert.pem";

    public static final String LEAF_KEY_FILE = "key.pem";

    static final String CA_CN = "Vaultwarden Android CA";
    static final String LEAF_CN = "Vaultwarden Android";

    static final long BATAS_REGEN_MS = 30L * 24 * 3600 * 1000;

    static final long LEAF_MAX_MS = 397L * 24 * 3600 * 1000;

    static boolean diUiThread() {
        try {
            android.os.Looper ui = android.os.Looper.getMainLooper();
            if (ui == null) {
                return false;
            }
            return android.os.Looper.myLooper() == ui;
        } catch (Exception e) {
            return false;
        }
    }

    public static long sisaMs(File certFile) {
        long hasil = sisaMsSekali(certFile);
        if (hasil == -1) {

            if (diUiThread()) {
                return -1;
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return -1;
            }
            hasil = sisaMsSekali(certFile);
        }
        return hasil;
    }

    private static final java.util.Map<String, long[]> MEMO_MASA =
            new java.util.LinkedHashMap<String, long[]>(64, 0.75f, true) {
                @Override protected boolean removeEldestEntry(
                        java.util.Map.Entry<String, long[]> tertua) {
                    return size() > 64;
                }
            };

    static final long MEMO_MASA_TTL_MS = 60_000;

    static long elapsedAman() {
        try {
            return android.os.SystemClock.elapsedRealtime();
        } catch (Exception e) {
            return 0;
        }
    }

    private static long sisaMsSekali(File certFile) {
        String kunci = kunciStat(certFile);
        synchronized (MEMO_MASA) {
            long[] memo = kunci == null ? null : MEMO_MASA.get(kunci);
            if (memo != null) {

                long kini = elapsedAman();
                if (memo.length < 3 || memo[2] <= 0 || kini <= 0
                        || kini - memo[2] < MEMO_MASA_TTL_MS) {
                    return sisaDariMemo(memo);
                }
            }
        }
        long[] masa = bacaMasa(certFile);
        if (kunci != null) {
            long catat = elapsedAman();
            synchronized (MEMO_MASA) {
                MEMO_MASA.put(kunci, new long[]{masa[0], masa[1], catat});
            }
        }
        return sisaDariMemo(masa);
    }

    private static String kunciStat(File f) {
        try {
            if (f == null) {
                return null;
            }

            return f.getAbsolutePath() + '|' + f.length() + '|' + f.lastModified();
        } catch (Exception e) {
            return null;
        }
    }

    private static long[] bacaMasa(File certFile) {
        try (FileInputStream in = new FileInputStream(certFile)) {
            X509Certificate cert = (X509Certificate) CertificateFactory
                    .getInstance("X.509").generateCertificate(in);
            return new long[]{cert.getNotBefore().getTime(), cert.getNotAfter().getTime()};
        } catch (Exception e) {
            return new long[]{Long.MIN_VALUE, Long.MIN_VALUE};
        }
    }

    static long sisaDariMemo(long[] masa) {
        if (masa == null || masa[0] == Long.MIN_VALUE) {
            return -1;
        }
        long kini = System.currentTimeMillis();
        if (kini < masa[0]) {
            return -2;
        }
        if (kini >= masa[1]) {
            return 0;
        }
        return Math.max(0, masa[1] - kini);
    }

    public static long daysLeft(File certFile) {
        long ms = sisaMs(certFile);
        if (ms < 0) {
            return ms;
        }
        return ms / (24L * 3600 * 1000);
    }

    private static final Object KUNCI_ENSURE = new Object();

    public static File ensure(File dir, List<String> ips,
            boolean paksaLeaf) {

        if (diUiThread()) {
            try {
                android.util.Log.w("TlsCert", "[tls] ensure dipanggil dari UI thread, batal agar tak ANR");
            } catch (Exception ignored) {
            }
            return null;
        }
        synchronized (KUNCI_ENSURE) {
        try {
            if (!dir.exists() && !dir.mkdirs()) {
                return null;
            }
            File caCert = new File(dir, CA_CERT_FILE);
            File caKey = new File(dir, CA_KEY_FILE);
            File certFile = new File(dir, LEAF_CERT_FILE);
            File keyFile = new File(dir, LEAF_KEY_FILE);
            pulihkanTukarTersisa(dir, CA_CERT_FILE, CA_KEY_FILE);
            pulihkanTukarTersisa(dir, LEAF_CERT_FILE, LEAF_KEY_FILE);

            boolean caBaru = false;
            if (!caOk(caCert, caKey, dir)) {

                File caCertBaru = new File(dir, CA_CERT_FILE + ".baru");
                File caKeyBaru = new File(dir, CA_KEY_FILE + ".baru");
                caCertBaru.delete();
                caKeyBaru.delete();
                if (!buatCa(caCertBaru, caKeyBaru)) {
                    caCertBaru.delete();
                    caKeyBaru.delete();
                    return null;
                }
                if (!tukarPasanganAtomik(dir, CA_CERT_FILE, CA_KEY_FILE,
                        caCertBaru, caKeyBaru)) {
                    caCertBaru.delete();
                    caKeyBaru.delete();
                    return null;
                }

                certFile.delete();
                keyFile.delete();
                caBaru = true;
                writeVersion(dir);
            }
            if (sisaMs(caCert) == -2) {

                try {
                    ServerService.catatLog("[tls] jam STB mundur - TLS tidak diubah,"
                            + " betulkan tanggal & jam lalu Start lagi");
                } catch (Exception ignored) {
                }
                return dir;
            }
            long sisaLeaf = sisaMs(certFile);
            if (sisaLeaf == -2) {

                try {
                    ServerService.catatLog("[tls] leaf belum valid (jam mundur) - dibuat ulang");
                } catch (Exception ignored) {
                }
            }
            if (!paksaLeaf && !caBaru && certFile.exists() && keyFile.exists()
                    && certFile.length() > 100 && keyFile.length() > 100
                    && leafCukup(sisaLeaf) && !leafTerlaluLama(sisaLeaf)) {

                if (pasanganCertKeyCocok(certFile, keyFile)
                        && ditandatanganiCa(certFile, caCert)) {
                    return dir;
                }
                try {
                    ServerService.catatLog(
                            "[tls] cert.pem/key.pem/CA tak berpasangan - leaf dibuat ulang");
                } catch (Exception ignored) {
                }
            }

            File certBaru = new File(dir, LEAF_CERT_FILE + ".baru");
            File keyBaru = new File(dir, LEAF_KEY_FILE + ".baru");
            certBaru.delete();
            keyBaru.delete();
            if (!buatLeaf(caKey, certBaru, keyBaru, ips)) {
                certBaru.delete();
                keyBaru.delete();
                return null;
            }
            if (!tukarPasanganAtomik(dir, LEAF_CERT_FILE, LEAF_KEY_FILE,
                    certBaru, keyBaru)) {
                certBaru.delete();
                keyBaru.delete();
                return null;
            }
            writeVersion(dir);
            return dir;
        } catch (Exception e) {
            return null;
        }
        }
    }

    static boolean tukarPasanganAtomik(File dir, String namaCert, String namaKey,
            File certBaru, File keyBaru) {
        if (dir == null || namaCert == null || namaKey == null
                || certBaru == null || keyBaru == null) {
            return false;
        }
        File certFile = new File(dir, namaCert);
        File keyFile = new File(dir, namaKey);
        File certCad = new File(dir, namaCert + ".cad");
        File keyCad = new File(dir, namaKey + ".cad");
        try {
            certCad.delete();
        } catch (Exception ignored) {
        }
        try {
            keyCad.delete();
        } catch (Exception ignored) {
        }
        boolean adaCertLama = certFile.exists();
        boolean adaKeyLama = keyFile.exists();
        if (adaCertLama && !certFile.renameTo(certCad)) {
            return false;
        }
        if (adaKeyLama && !keyFile.renameTo(keyCad)) {
            if (adaCertLama) {
                certCad.renameTo(certFile);
            }
            return false;
        }
        if (!certBaru.renameTo(certFile) || !keyBaru.renameTo(keyFile)) {
            try {
                certFile.delete();
            } catch (Exception ignored) {
            }
            try {
                keyFile.delete();
            } catch (Exception ignored) {
            }
            if (adaCertLama) {
                certCad.renameTo(certFile);
            }
            if (adaKeyLama) {
                keyCad.renameTo(keyFile);
            }
            return false;
        }
        try {
            certCad.delete();
        } catch (Exception ignored) {
        }
        try {
            keyCad.delete();
        } catch (Exception ignored) {
        }
        return true;
    }

    static void pulihkanTukarTersisa(File dir, String namaCert, String namaKey) {
        if (dir == null || namaCert == null || namaKey == null) {
            return;
        }
        File certFile = new File(dir, namaCert);
        File keyFile = new File(dir, namaKey);
        File certCad = new File(dir, namaCert + ".cad");
        File keyCad = new File(dir, namaKey + ".cad");
        File certBaru = new File(dir, namaCert + ".baru");
        File keyBaru = new File(dir, namaKey + ".baru");
        try {
            if (!certFile.exists() && !keyFile.exists()
                    && certBaru.exists() && keyBaru.exists()
                    && pasanganCertKeyCocok(certBaru, keyBaru)) {
                if (certBaru.renameTo(certFile)) {
                    keyBaru.renameTo(keyFile);
                }
            }
            if (!certFile.exists() && certCad.exists()) {
                certCad.renameTo(certFile);
            }
            if (!certFile.exists() && certBaru.exists() && keyFile.exists()
                    && pasanganCertKeyCocok(certBaru, keyFile)) {
                certBaru.renameTo(certFile);
            }
            if (!keyFile.exists()) {
                if (keyBaru.exists() && certFile.exists()
                        && pasanganCertKeyCocok(certFile, keyBaru)) {
                    keyBaru.renameTo(keyFile);
                } else if (keyCad.exists()) {
                    keyCad.renameTo(keyFile);
                }
            }
            if (certFile.exists() && keyFile.exists()
                    && !pasanganCertKeyCocok(certFile, keyFile)
                    && certCad.exists() && keyCad.exists()
                    && pasanganCertKeyCocok(certCad, keyCad)) {
                File certRusak = new File(dir, namaCert + ".rusak");
                File keyRusak = new File(dir, namaKey + ".rusak");
                certRusak.delete();
                keyRusak.delete();
                boolean pindah1 = certFile.renameTo(certRusak);
                boolean pindah2 = keyFile.renameTo(keyRusak);
                boolean pasang1 = pindah1 && pindah2 && certCad.renameTo(certFile);
                boolean pasang2 = pasang1 && keyCad.renameTo(keyFile);
                if (pasang2 && pasanganCertKeyCocok(certFile, keyFile)) {
                    certRusak.delete();
                    keyRusak.delete();
                } else {
                    if (pasang2) {
                        keyFile.renameTo(keyCad);
                        certFile.renameTo(certCad);
                    } else if (pasang1) {
                        certFile.renameTo(certCad);
                    }
                    if (pindah1) {
                        certRusak.renameTo(certFile);
                    }
                    if (pindah2) {
                        keyRusak.renameTo(keyFile);
                    }
                }
            }
            if (certFile.exists() && keyFile.exists()
                    && pasanganCertKeyCocok(certFile, keyFile)) {
                certCad.delete();
                keyCad.delete();
                certBaru.delete();
                keyBaru.delete();
            }
        } catch (Exception ignored) {
        }
    }

    static boolean caOk(File caCert, File caKey, File dir) {
        if (!caCert.exists() || !caKey.exists()
                || caCert.length() <= 100 || caKey.length() <= 100
                || !certVersionOk(dir)) {
            return false;
        }

        if (!pasanganCertKeyCocok(caCert, caKey)) {
            try {
                ServerService.catatLog(
                        "[tls] ca.pem dan ca-key.pem tak berpasangan - CA dibuat ulang");
            } catch (Exception ignored) {
            }
            return false;
        }
        long sisa = sisaMs(caCert);
        if (sisa == -2) {

            try {
                ServerService.catatLog("[tls] CA belum valid (jam mundur)"
                        + " - CA dipertahankan, betulkan jam STB");
            } catch (Exception ignored) {
            }
        }
        return caLayakDipakai(sisa);
    }

    static boolean caLayakDipakai(long sisa) {
        return sisa == -2 || caSisaCukup(sisa);
    }

    static boolean caSisaCukup(long sisa) {
        return sisa > BATAS_REGEN_MS;
    }

    static boolean leafCukup(long sisa) {
        return sisa > BATAS_REGEN_MS;
    }

    static boolean leafTerlaluLama(long sisa) {
        return sisa > LEAF_MAX_MS;
    }

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

    private static final java.util.regex.Pattern POLA_KOLON =
            java.util.regex.Pattern.compile(":");
    private static final java.util.regex.Pattern POLA_SPASI_APA =
            java.util.regex.Pattern.compile("\\s");

    private static boolean tokenAman(String s) {
        if (s == null || s.isEmpty() || s.length() > 64) {
            return false;
        }
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            boolean ok = (c >= 'a' && c <= 'z')
                    || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9')
                    || c == '_' || c == '-';
            if (!ok) {
                return false;
            }
        }
        return true;
    }


    private static boolean buatLeaf(File caKeyFile, File certFile, File keyFile,
            List<String> ips) {
        try {
            PrivateKey caPriv = bacaPrivateKey(caKeyFile);
            if (caPriv == null) {
                return false;
            }
            KeyPair kp = buatRsa2048();
            byte[] tbs = buildTbs(kp.getPublic(), CA_CN, LEAF_CN,
                    extensionsBlock(ips), 397);
            writePem(certFile, "CERTIFICATE", tandatangani(tbs, caPriv));
            writePem(keyFile, "PRIVATE KEY", kp.getPrivate().getEncoded());
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static final SecureRandom ACAK_BERSAMA = new SecureRandom();

    private static final java.util.TimeZone ZONA_UTC =
            java.util.TimeZone.getTimeZone("UTC");

    private static KeyPair buatRsa2048() throws Exception {
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
        kpg.initialize(2048, ACAK_BERSAMA);
        return kpg.generateKeyPair();
    }

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

    private static PrivateKey bacaPrivateKey(File pem) {
        try {

            if (pem != null && pem.length() > 32768) {
                throw new java.io.IOException("kunci terlalu besar");
            }
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            try (FileInputStream in = new FileInputStream(pem)) {
                byte[] tmp = new byte[1024];
                int n;
                while ((n = in.read(tmp)) != -1) {
                    buf.write(tmp, 0, n);

                    if (buf.size() > 32768) {
                        throw new java.io.IOException("kunci terlalu besar");
                    }
                }
            }
            String s = new String(buf.toByteArray(), StandardCharsets.US_ASCII);
            s = s.replace("-----BEGIN PRIVATE KEY-----", "")
                    .replace("-----END PRIVATE KEY-----", "");
            s = POLA_SPASI_APA.matcher(s).replaceAll("");
            byte[] pkcs8 = android.util.Base64.decode(s, android.util.Base64.DEFAULT);
            return KeyFactory.getInstance("RSA")
                    .generatePrivate(new PKCS8EncodedKeySpec(pkcs8));
        } catch (Exception e) {
            return null;
        }
    }

    private static X509Certificate bacaCert(File pem) {
        try (FileInputStream in = new FileInputStream(pem)) {
            return (X509Certificate) CertificateFactory
                    .getInstance("X.509").generateCertificate(in);
        } catch (Exception e) {
            return null;
        }
    }

    static boolean kunciCocokCert(PublicKey publik, PrivateKey privat) {
        if (!(publik instanceof java.security.interfaces.RSAPublicKey)
                || !(privat instanceof java.security.interfaces.RSAPrivateKey)) {
            return false;
        }
        BigInteger modulusPub =
                ((java.security.interfaces.RSAPublicKey) publik).getModulus();
        BigInteger modulusPriv =
                ((java.security.interfaces.RSAPrivateKey) privat).getModulus();
        return modulusPub != null && modulusPub.equals(modulusPriv);
    }

    static boolean pasanganCertKeyCocok(File certFile, File keyFile) {
        try {
            X509Certificate cert = bacaCert(certFile);
            PrivateKey key = bacaPrivateKey(keyFile);
            return cert != null && key != null
                    && kunciCocokCert(cert.getPublicKey(), key);
        } catch (Exception e) {
            return false;
        }
    }

    static boolean ditandatanganiCa(File leafFile, File caFile) {
        try {
            X509Certificate leaf = bacaCert(leafFile);
            X509Certificate ca = bacaCert(caFile);
            if (leaf == null || ca == null) {
                return false;
            }
            leaf.verify(ca.getPublicKey());
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    public static boolean siapPakaiTanpaTulis(File dir) {
        if (dir == null) {
            return false;
        }
        File caCert = new File(dir, CA_CERT_FILE);
        File caKey = new File(dir, CA_KEY_FILE);
        File certFile = new File(dir, LEAF_CERT_FILE);
        File keyFile = new File(dir, LEAF_KEY_FILE);
        if (!caCert.isFile() || !caKey.isFile()
                || !certFile.isFile() || !keyFile.isFile()
                || caCert.length() <= 100 || caKey.length() <= 100
                || certFile.length() <= 100 || keyFile.length() <= 100
                || !certVersionOk(dir)) {
            return false;
        }
        long sisaCa = sisaMs(caCert);
        long sisaLeaf = sisaMs(certFile);
        return caLayakDipakai(sisaCa) && leafCukup(sisaLeaf)
                && !leafTerlaluLama(sisaLeaf)
                && pasanganCertKeyCocok(caCert, caKey)
                && pasanganCertKeyCocok(certFile, keyFile)
                && ditandatanganiCa(certFile, caCert);
    }

    private static byte[] buildTbs(PublicKey subjectPub, String issuerCn, String subjectCn,
            byte[] exts, long masaHari) throws Exception {
        SecureRandom rnd = ACAK_BERSAMA;
        byte[] serialBytes = new byte[16];
        rnd.nextBytes(serialBytes);
        serialBytes[0] &= 0x7F;

        boolean nolSemua = true;
        for (byte b : serialBytes) {
            if (b != 0) {
                nolSemua = false;
                break;
            }
        }
        if (nolSemua) {
            serialBytes[serialBytes.length - 1] = 1;
        }

        Date now = new Date();
        Date notBefore = new Date(now.getTime() - TimeUnit.DAYS.toMillis(1));
        Date notAfter = new Date(now.getTime() + TimeUnit.DAYS.toMillis(masaHari));

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

    private static byte[] caExtensionsBlock() throws Exception {
        byte[] basicConstraints = extension(new byte[]{0x06, 0x03, 0x55, 0x1D, 0x13}, true,
                octetString(der(b -> b.raw(new byte[]{0x01, 0x01, (byte) 0xFF}), 0x30)));

        byte[] keyUsage = extension(new byte[]{0x06, 0x03, 0x55, 0x1D, 0x0F}, true,
                octetString(new byte[]{0x03, 0x02, 0x01, (byte) 0x86}));
        byte[] extensions = der(b -> {
            b.raw(basicConstraints);
            b.raw(keyUsage);
        }, 0x30);

        return der(b -> b.raw(extensions), 0xA3);
    }

    private static byte[] extensionsBlock(List<String> ips) throws Exception {
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
        }, 0x30);

        byte[] san = extension(new byte[]{0x06, 0x03, 0x55, 0x1D, 0x11}, false,
                octetString(generalNames));

        byte[] basicConstraints = extension(new byte[]{0x06, 0x03, 0x55, 0x1D, 0x13}, true,
                octetString(der(b -> b.raw(new byte[]{0x01, 0x01, 0x00}), 0x30)));

        byte[] keyUsage = extension(new byte[]{0x06, 0x03, 0x55, 0x1D, 0x0F}, true,
                octetString(new byte[]{0x03, 0x02, 0x01, (byte) 0xA0}));

        byte[] eku = extension(new byte[]{0x06, 0x03, 0x55, 0x1D, 0x25}, false,
                octetString(der(b -> b.raw(new byte[]{0x06, 0x08, 0x2B, 0x06,
                        0x01, 0x05, 0x05, 0x07, 0x03, 0x01}), 0x30)));

        byte[] extensions = der(b -> {
            b.raw(san);
            b.raw(basicConstraints);
            b.raw(keyUsage);
            b.raw(eku);
        }, 0x30);

        return der(b -> b.raw(extensions), 0xA3);
    }

    private static byte[] extension(byte[] oid, boolean critical, byte[] extValue) throws Exception {
        byte[] ext = der(b -> {
            b.raw(oid);
            if (critical) {
                b.raw(new byte[]{0x01, 0x01, (byte) 0xFF});
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

        int[] nilai = new int[4];
        int bagian = 0;
        int angka = 0;
        int digit = 0;
        for (int i = 0; i < ip.length(); i++) {
            char c = ip.charAt(i);
            if (c >= '0' && c <= '9') {
                angka = angka * 10 + (c - '0');
                if (angka > 255) {
                    return null;
                }
                digit++;
                if (digit > 3) {
                    return null;
                }
            } else if (c == '.') {
                if (digit == 0 || bagian >= 3) {
                    return null;
                }
                nilai[bagian++] = angka;
                angka = 0;
                digit = 0;
            } else {
                return null;
            }
        }
        if (bagian != 3 || digit == 0) {
            return null;
        }
        nilai[3] = angka;
        return new byte[]{(byte) nilai[0], (byte) nilai[1], (byte) nilai[2], (byte) nilai[3]};
    }

    static byte[] ipv6(String ip) {
        if (ip == null) {
            return null;
        }
        String t = ip.trim();
        if (t.isEmpty() || !t.contains(":")) {
            return null;
        }

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
                        ? new String[0] : POLA_KOLON.split(t.substring(0, pecah), -1);
                kanan = t.substring(pecah + 2).isEmpty()
                        ? new String[0] : POLA_KOLON.split(t.substring(pecah + 2), -1);
                if (kiri.length + kanan.length > 7) {
                    return null;
                }
            } else {
                String[] semua = POLA_KOLON.split(t, -1);
                if (semua.length != 8) {
                    return null;
                }
                kiri = semua;
                kanan = new String[0];
            }
            int[] grup = new int[8];
            int pos = 0;
            for (String g : kiri) {
                if (!grupHeksaValid(g)) {
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
                if (!grupHeksaValid(g)) {
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

    static boolean grupHeksaValid(String g) {
        if (g == null || g.isEmpty() || g.length() > 4) {
            return false;
        }
        for (int i = 0; i < g.length(); i++) {
            char c = g.charAt(i);
            boolean ok = (c >= '0' && c <= '9')
                    || (c >= 'a' && c <= 'f')
                    || (c >= 'A' && c <= 'F');
            if (!ok) {
                return false;
            }
        }
        return true;
    }

    private static byte[] nameCn(String cn) throws Exception {

        byte[] attr = der(b -> {
            b.oidCn();
            b.utf8(cn);
        }, 0x30);

        byte[] rdn = der(b -> b.raw(attr), 0x31);

        return der(b -> b.raw(rdn), 0x30);
    }

    private static byte[] sha256RsaAlgorithmId() {

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

        File tujuan = new File(dir, "version.txt");
        File baru = new File(dir, "version.txt.baru");
        baru.delete();
        FileOutputStream w = new FileOutputStream(baru);
        try {
            w.write((CERT_VERSION + "\n").getBytes(StandardCharsets.US_ASCII));
            w.flush();
            try {
                w.getFD().sync();
            } catch (Exception ignored) {
            }
        } finally {
            try {
                w.close();
            } catch (Exception ignored) {
            }
        }

        if (!baru.renameTo(tujuan)) {
            if (!tujuan.delete() || !baru.renameTo(tujuan)) {
                baru.delete();
                throw new IOException("gagal menulis version.txt");
            }
        }
    }

    private static void writePem(File f, String type, byte[] der) throws IOException {
        FileOutputStream fos = new FileOutputStream(f);
        try {
            OutputStreamWriter w = new OutputStreamWriter(fos, StandardCharsets.US_ASCII);
            w.write("-----BEGIN " + type + "-----\n");
            String b64 = android.util.Base64.encodeToString(der, android.util.Base64.NO_WRAP);
            for (int i = 0; i < b64.length(); i += 64) {
                w.write(b64, i, Math.min(64, b64.length() - i));
                w.write('\n');
            }
            w.write("-----END " + type + "-----\n");
            w.flush();

            try {
                fos.getFD().sync();
            } catch (Exception ignored) {
            }
        } finally {
            try {
                fos.close();
            } catch (Exception ignored) {
            }
        }
        if (type.contains("PRIVATE") || f.getName().contains("key")) {

            try {
                f.setReadable(false, false);
                f.setReadable(true, true);
                f.setWritable(false, false);
                f.setWritable(true, true);

                try {
                    String p = f.getCanonicalPath();
                    if (!p.startsWith("/data/")) {
                        android.util.Log.w("TlsCert",
                                "[tls] kunci di storage bersama, pindahkan data_dir ke internal");
                    }
                } catch (Exception ignored2) {
                }
            } catch (Exception ignored) {
            }
        }
    }

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
        } else if (n <= 0xFFFFFF) {
            o.write(0x83);
            o.write(n >> 16);
            o.write(n >> 8);
            o.write(n);
        } else {
            o.write(0x84);
            o.write(n >> 24);
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
            out.write(0);
            out.write(data, 0, data.length);
        }

        void utf8(String s) throws IOException {
            byte[] data = s.getBytes(StandardCharsets.UTF_8);
            out.write(0x0C);
            len(data.length, out);
            out.write(data, 0, data.length);
        }

        static void dua(StringBuilder b, int v) {
            b.append((char) ('0' + (v / 10) % 10));
            b.append((char) ('0' + v % 10));
        }

        static void empat(StringBuilder b, int v) {
            dua(b, v / 100);
            dua(b, v % 100);
        }

        void waktu(Date d) throws IOException {

            java.util.Calendar c = java.util.Calendar.getInstance(ZONA_UTC, Locale.US);
            c.setTime(d);
            int tahun = c.get(java.util.Calendar.YEAR);
            int bulan = c.get(java.util.Calendar.MONTH) + 1;
            int hari = c.get(java.util.Calendar.DAY_OF_MONTH);
            int jam = c.get(java.util.Calendar.HOUR_OF_DAY);
            int menit = c.get(java.util.Calendar.MINUTE);
            int detik = c.get(java.util.Calendar.SECOND);
            if (tahun >= 2050) {
                StringBuilder b = new StringBuilder(15);
                empat(b, tahun);
                dua(b, bulan);
                dua(b, hari);
                dua(b, jam);
                dua(b, menit);
                dua(b, detik);
                b.append('Z');
                byte[] data = b.toString().getBytes(StandardCharsets.US_ASCII);
                out.write(0x18);
                len(data.length, out);
                out.write(data, 0, data.length);
                return;
            }
            tulisUtc(tahun % 100, bulan, hari, jam, menit, detik);
        }

        private void tulisUtc(int tahun2, int bulan, int hari,
                int jam, int menit, int detik) throws IOException {
            StringBuilder b = new StringBuilder(13);
            dua(b, tahun2);
            dua(b, bulan);
            dua(b, hari);
            dua(b, jam);
            dua(b, menit);
            dua(b, detik);
            b.append('Z');
            byte[] data = b.toString().getBytes(StandardCharsets.US_ASCII);
            out.write(0x17);
            len(data.length, out);
            out.write(data, 0, data.length);
        }

        void oidSha256Rsa() throws IOException {

            raw(new byte[]{0x30, 0x0D, 0x06, 0x09, 0x2A, (byte) 0x86, 0x48,
                    (byte) 0x86, (byte) 0xF7, 0x0D, 0x01, 0x01, 0x0B, 0x05, 0x00});
        }

        void oidCn() throws IOException {

            raw(new byte[]{0x06, 0x03, 0x55, 0x04, 0x03});
        }

    }

    private static String amanTs(String ts) {
        if (ts == null) {
            return "tanpa-waktu";
        }
        if (!tokenAman(ts)) {
            return "tanpa-waktu";
        }
        return ts;
    }

    static String namaBackupCa(String timestamp) {

        String ts = amanTs(timestamp == null ? null : timestamp.trim());
        return "ca-cadangan-" + ts + ".pem";
    }

    static File salinCaKeStorage(File caAktif, File dirBackup, String timestamp) throws IOException {
        if (caAktif == null || !caAktif.isFile()) {
            throw new IOException("CA belum ada. Tekan Start dulu agar CA dibuat.");
        }
        if (dirBackup == null) {
            throw new IOException("Folder tujuan tak valid.");
        }
        if (!dirBackup.exists() && !dirBackup.mkdirs()) {
            throw new IOException("Folder tujuan tak bisa dibuat: " + dirBackup.getAbsolutePath());
        }
        File tujuan = new File(dirBackup, namaBackupCa(timestamp));
        try (FileInputStream in = new FileInputStream(caAktif);
             FileOutputStream out = new FileOutputStream(tujuan)) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) {
                out.write(buf, 0, n);
            }
        }
        return tujuan;
    }

    static int resetTls(File tlsDir) {
        if (tlsDir == null || !tlsDir.isDirectory()) {
            return 0;
        }
        int hapus = 0;

        synchronized (KUNCI_ENSURE) {

            for (String nama : new String[]{CA_CERT_FILE, CA_KEY_FILE, LEAF_CERT_FILE,
                    LEAF_KEY_FILE, "ips.txt", "version.txt", "version.txt.baru",
                    CA_CERT_FILE + ".baru", CA_KEY_FILE + ".baru",
                    LEAF_CERT_FILE + ".baru", LEAF_KEY_FILE + ".baru",
                    CA_CERT_FILE + ".cad", CA_KEY_FILE + ".cad",
                    LEAF_CERT_FILE + ".cad", LEAF_KEY_FILE + ".cad"}) {
                try {
                    File f = new File(tlsDir, nama);
                    if (f.isFile() && f.delete()) {
                        hapus++;
                    }
                } catch (Exception ignored) {
                }
            }
        }
        return hapus;
    }
}
