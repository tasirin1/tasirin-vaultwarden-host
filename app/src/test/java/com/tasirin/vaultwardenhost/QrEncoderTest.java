package com.tasirin.vaultwardenhost;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import com.google.zxing.BinaryBitmap;
import com.google.zxing.RGBLuminanceSource;
import com.google.zxing.common.HybridBinarizer;
import com.google.zxing.qrcode.QRCodeReader;

import org.junit.Test;

/** Verifikasi encoder QR mandiri: encode lalu decode dengan zxing (test scope,
 *  tidak ikut APK) — kalau matriks salah, decode pasti gagal. */
public class QrEncoderTest {

    private static String decode(QrEncoder.Matrix matrix) throws Exception {
        int w = matrix.size;
        int[] pixels = new int[w * w];
        for (int y = 0; y < w; y++) {
            for (int x = 0; x < w; x++) {
                pixels[y * w + x] = matrix.get(x, y) ? 0xFF000000 : 0xFFFFFFFF;
            }
        }
        RGBLuminanceSource source = new RGBLuminanceSource(w, w, pixels);
        return new QRCodeReader().decode(new BinaryBitmap(new HybridBinarizer(source))).getText();
    }

    @Test
    public void encodeDecodeUrlServer() throws Exception {
        String text = "http://192.168.1.10:8080/";
        QrEncoder.Matrix matrix = QrEncoder.encode(text);
        assertNotNull(matrix);
        assertEquals(text, decode(matrix));
    }

    @Test
    public void encodeDecodeBerbagaiPanjang() throws Exception {
        String[] samples = {
            "a",
            "hello world",
            "https://example.com/path?q=1&r=2&s=3",
            repeat("A", 50),
            repeat("B", 100),
            repeat("C", 200),
            repeat("D", 400),
            repeat("E", 700),
            repeat("F", 1200),
            repeat("G", 2000)
        };
        for (String s : samples) {
            for (QrEncoder.Ecc ecc : QrEncoder.Ecc.values()) {
                QrEncoder.Matrix matrix = QrEncoder.encode(s, ecc);
                assertNotNull(ecc + " len=" + s.length(), matrix);
                assertEquals(ecc + " len=" + s.length(), s, decode(matrix));
            }
        }
    }

    @Test
    public void encodeDecodeKapasitasMaksimumLevelL() throws Exception {
        String s = repeat("X", 2900);
        QrEncoder.Matrix matrix = QrEncoder.encode(s, QrEncoder.Ecc.L);
        assertNotNull(matrix);
        assertEquals(s, decode(matrix));
    }

    @Test
    public void teksUtf8Multibyte() throws Exception {
        String s = "kopi \u2615 dan nasi goreng \uD83C\uDF5B \u2014 indonesia";
        QrEncoder.Matrix matrix = QrEncoder.encode(s);
        assertNotNull(matrix);
        assertEquals(s, decode(matrix));
    }

    @Test
    public void teksKosongTetapMenghasilkanMatriks() {
        assertNotNull(QrEncoder.encode(""));
    }

    @Test
    public void melebihiKapasitasVersi40MengembalikanNull() {
        assertNull(QrEncoder.encode(repeat("X", 4000)));
        assertNull(QrEncoder.encode(repeat("Y", 2400), QrEncoder.Ecc.M));
    }

    private static String repeat(String s, int n) {
        return new String(new char[n]).replace("\0", s);
    }
}
