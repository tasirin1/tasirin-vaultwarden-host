package com.vaultwarden.android;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.Signature;
import java.util.Date;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Membuat sertifikat self-signed (RSA 2048, SHA256withRSA) tanpa library eksternal.
 * Digunakan untuk HTTPS lokal (ROCKET_TLS_*).
 */
public final class TlsCert {

    private TlsCert() {
    }

    /** Pastikan cert.pem & key.pem ada di {@code dir}; buat bila belum. Return null bila gagal. */
    public static File ensure(File dir, List<String> ips) {
        try {
            File certFile = new File(dir, "cert.pem");
            File keyFile = new File(dir, "key.pem");
            if (certFile.exists() && keyFile.exists()
                    && certFile.length() > 100 && keyFile.length() > 100) {
                return dir;
            }
            if (!dir.exists() && !dir.mkdirs()) {
                return null;
            }

            KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
            kpg.initialize(2048);
            KeyPair kp = kpg.generateKeyPair();

            byte[] tbs = buildTbsCertificate(kp.getPublic(), ips);
            Signature sig = Signature.getInstance("SHA256withRSA");
            sig.initSign(kp.getPrivate());
            sig.update(tbs);
            byte[] signature = sig.sign();

            byte[] cert = der(b -> {
                b.raw(tbs);
                b.oidSha256Rsa();
                b.bitString(signature);
            }, 0x30);

            writePem(certFile, "CERTIFICATE", cert);
            writePem(keyFile, "PRIVATE KEY", kp.getPrivate().getEncoded());
            return dir;
        } catch (Exception e) {
            return null;
        }
    }

    private static byte[] buildTbsCertificate(PublicKey pub, List<String> ips) throws Exception {
        SecureRandom rnd = new SecureRandom();
        byte[] serialBytes = new byte[16];
        rnd.nextBytes(serialBytes);
        serialBytes[0] &= 0x7F; // positif

        Date now = new Date();
        Date notBefore = new Date(now.getTime() - TimeUnit.DAYS.toMillis(1));
        Date notAfter = new Date(now.getTime() + TimeUnit.DAYS.toMillis(365 * 5));

        // version [0] EXPLICIT INTEGER 2 (v3)
        byte[] version = der(b -> b.raw(integer(BigInteger.valueOf(2))), 0xA0);
        byte[] serial = integer(new BigInteger(serialBytes));
        byte[] sigAlg = der(b -> b.oidSha256Rsa(), 0x30);
        byte[] issuer = nameCn("Vaultwarden Android");
        byte[] validity = der(b -> {
            b.utcTime(notBefore);
            b.utcTime(notAfter);
        }, 0x30);
        byte[] subject = nameCn("Vaultwarden Android");
        byte[] spki = pub.getEncoded();
        byte[] san = sanExtension(ips);

        return der(b -> {
            b.raw(version);
            b.raw(serial);
            b.raw(sigAlg);
            b.raw(issuer);
            b.raw(validity);
            b.raw(subject);
            b.raw(spki);
            b.raw(san);
        }, 0x30);
    }

    private static byte[] sanExtension(List<String> ips) throws Exception {
        byte[] generalNames = der(b -> {
            for (String ip : ips) {
                byte[] octets = ipv4(ip);
                if (octets != null) {
                    b.bytes(new byte[]{(byte) 0x87, (byte) octets.length});
                    b.raw(octets);
                }
            }
            b.bytes(new byte[]{(byte) 0x82, 9});
            b.raw("localhost".getBytes(StandardCharsets.US_ASCII));
        }, 0x30);

        byte[] extValue = der(b -> b.raw(generalNames), 0x04);
        // Extension ::= SEQUENCE { extnID, extnValue }
        byte[] ext = der(b -> {
            b.oidSan();
            b.raw(extValue);
        }, 0x30);

        // extensions [3] EXPLICIT SEQUENCE OF Extension
        return der(b -> b.raw(ext), 0xA3);
    }

    private static byte[] ipv4(String ip) {
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

    private static byte[] nameCn(String cn) throws Exception {
        // RDN: SET { SEQUENCE { OID 2.5.4.3, UTF8String } }
        byte[] attr = der(b -> {
            b.oidCn();
            b.utf8(cn);
        }, 0x30);
        return der(b -> b.raw(attr), 0x31);
    }

    private static byte[] integer(BigInteger v) throws IOException {
        byte[] body = v.toByteArray();
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        o.write(0x02);
        len(body.length, o);
        o.write(body, 0, body.length);
        return o.toByteArray();
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

        void bytes(byte[] data) throws IOException {
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

        void utcTime(Date d) throws IOException {
            String s = String.format("%1$ty%1$tm%1$td%1$tH%1$tM%1$tSZ", d);
            byte[] data = s.getBytes(StandardCharsets.US_ASCII);
            out.write(0x17);
            len(data.length, out);
            out.write(data, 0, data.length);
        }

        void oidSha256Rsa() throws IOException {
            // SHA256withRSA: 1.2.840.113549.1.1.11
            raw(new byte[]{0x30, 0x0B, 0x06, 0x09, 0x2A, (byte) 0x86, 0x48,
                    (byte) 0x86, (byte) 0xF7, 0x0D, 0x01, 0x01, 0x0B, 0x05, 0x00});
        }

        void oidCn() throws IOException {
            // commonName: 2.5.4.3
            raw(new byte[]{0x06, 0x03, 0x55, 0x04, 0x03});
        }

        void oidSan() throws IOException {
            // subjectAltName: 2.5.29.17
            raw(new byte[]{0x06, 0x03, 0x55, 0x1D, 0x11});
        }
    }
}
