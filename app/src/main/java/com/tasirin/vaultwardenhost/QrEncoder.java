package com.tasirin.vaultwardenhost;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** Encoder QR mode byte (ECC L/M) yang mandiri — tanpa dependensi zxing di APK
 *  agar ukuran tetap kecil. Algoritma mengikuti ISO/IEC 18004; kebenaran
 *  diverifikasi dengan decode zxing di unit test (testImplementation, tidak
 *  ikut ke APK). Mask pattern 0 selalu dipakai (format info mencatatnya). */
public final class QrEncoder {

    public enum Ecc {
        L(0b01), M(0b00);

        final int bits;

        Ecc(int bits) {
            this.bits = bits;
        }
    }

    private static final int MAX_VERSION = 40;

    // Total codeword (data + ECC) per versi 1..40 — Tabel dari ISO/IEC 18004.
    private static final int[] TOTAL_CODEWORDS = {
        26, 44, 70, 100, 134, 172, 196, 242, 292, 346, 404, 466, 532, 581, 655,
        733, 815, 901, 991, 1085, 1156, 1258, 1364, 1474, 1588, 1706, 1828, 1921,
        2051, 2185, 2323, 2465, 2611, 2761, 2876, 3034, 3196, 3362, 3532, 3706
    };

    // ECC codeword per blok untuk level L.
    private static final int[] ECC_PER_BLOCK_L = {
        7, 10, 15, 20, 26, 18, 20, 24, 30, 18, 20, 24, 26, 30, 22, 24, 28, 30,
        28, 28, 28, 28, 30, 30, 26, 28, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30,
        30, 30, 30, 30
    };

    // Jumlah blok RS untuk level L.
    private static final int[] BLOCKS_L = {
        1, 1, 1, 1, 1, 2, 2, 2, 2, 4, 4, 4, 4, 4, 6, 6, 6, 6, 7, 8, 8, 9, 9,
        10, 12, 12, 12, 13, 14, 15, 16, 17, 18, 19, 19, 20, 21, 22, 24, 25
    };

    // ECC codeword per blok untuk level M.
    private static final int[] ECC_PER_BLOCK_M = {
        10, 16, 26, 18, 24, 16, 18, 22, 22, 26, 30, 22, 22, 24, 24, 28, 28, 26,
        26, 26, 26, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28,
        28, 28, 28, 28
    };

    // Jumlah blok RS untuk level M.
    private static final int[] BLOCKS_M = {
        1, 1, 1, 2, 2, 4, 4, 4, 5, 5, 5, 8, 9, 9, 10, 10, 11, 13, 14, 16, 17,
        17, 18, 20, 21, 23, 25, 26, 28, 29, 31, 33, 35, 37, 38, 40, 43, 45, 47, 49
    };

    // Pusat pola alignment per versi (indeks 0 = versi 1).
    private static final int[][] ALIGNMENT = {
        {},
        {6, 18}, {6, 22}, {6, 26}, {6, 30},
        {6, 34}, {6, 22, 38}, {6, 24, 42},
        {6, 26, 46}, {6, 28, 50}, {6, 30, 54},
        {6, 32, 58}, {6, 34, 62}, {6, 26, 46, 66},
        {6, 26, 48, 70}, {6, 26, 50, 74}, {6, 30, 54, 78},
        {6, 30, 56, 82}, {6, 30, 58, 86}, {6, 34, 62, 90},
        {6, 28, 50, 72, 94}, {6, 26, 50, 74, 98},
        {6, 30, 54, 78, 102}, {6, 28, 54, 80, 106},
        {6, 32, 58, 84, 110}, {6, 30, 58, 86, 114},
        {6, 34, 62, 90, 118}, {6, 26, 50, 74, 98, 122},
        {6, 30, 54, 78, 102, 126}, {6, 26, 52, 78, 104, 130},
        {6, 30, 56, 82, 108, 134}, {6, 34, 60, 86, 112, 138},
        {6, 30, 58, 86, 114, 142}, {6, 34, 62, 90, 118, 146},
        {6, 30, 54, 78, 102, 126, 150}, {6, 24, 50, 76, 102, 128, 154},
        {6, 28, 54, 80, 106, 132, 158}, {6, 32, 58, 84, 110, 136, 162},
        {6, 26, 54, 82, 110, 138, 166}, {6, 30, 58, 86, 114, 142, 170}
    };

    private static final int[][] FINDER = {
        {1, 1, 1, 1, 1, 1, 1},
        {1, 0, 0, 0, 0, 0, 1},
        {1, 0, 1, 1, 1, 0, 1},
        {1, 0, 1, 1, 1, 0, 1},
        {1, 0, 1, 1, 1, 0, 1},
        {1, 0, 0, 0, 0, 0, 1},
        {1, 1, 1, 1, 1, 1, 1}
    };

    private static final int[] EXP = new int[512];
    private static final int[] LOG = new int[256];

    static {
        int x = 1;
        for (int i = 0; i < 255; i++) {
            EXP[i] = x;
            LOG[x] = i;
            x <<= 1;
            if ((x & 0x100) != 0) {
                x ^= 0x11D;
            }
        }
        for (int i = 255; i < 512; i++) {
            EXP[i] = EXP[i - 255];
        }
    }

    private QrEncoder() {
    }

    /** Matriks QR: true = modul gelap. [get] memakai koordinat (x, y). */
    public static final class Matrix {
        public final int size;
        private final int[] modules;

        private Matrix(int size) {
            this.size = size;
            this.modules = new int[size * size];
            for (int i = 0; i < modules.length; i++) {
                modules[i] = -1;
            }
        }

        public boolean get(int x, int y) {
            return modules[y * size + x] == 1;
        }

        private void set(int x, int y, boolean value) {
            modules[y * size + x] = value ? 1 : 0;
        }

        private boolean isEmpty(int x, int y) {
            return modules[y * size + x] == -1;
        }
    }

    /** Encode teks (UTF-8, mode byte). Mengembalikan null bila melebihi
     *  kapasitas versi 40. */
    public static Matrix encode(String text, Ecc ecc) {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        int version = 0;
        for (int v = 1; v <= MAX_VERSION; v++) {
            int dataCodewords = dataCodewordsFor(v, ecc);
            int countBits = v <= 9 ? 8 : 16;
            int bitsNeeded = 4 + countBits + bytes.length * 8;
            if ((bitsNeeded + 7) / 8 <= dataCodewords) {
                version = v;
                break;
            }
        }
        if (version == 0) {
            return null;
        }
        int dataCodewords = dataCodewordsFor(version, ecc);
        int[] data = buildDataBits(bytes, version, dataCodewords);
        int[] interleaved = interleave(data, version, ecc);
        return buildMatrix(interleaved, version, ecc);
    }

    public static Matrix encode(String text) {
        return encode(text, Ecc.L);
    }

    private static int dataCodewordsFor(int version, Ecc ecc) {
        int eccPer;
        int blocks;
        if (ecc == Ecc.L) {
            eccPer = ECC_PER_BLOCK_L[version - 1];
            blocks = BLOCKS_L[version - 1];
        } else {
            eccPer = ECC_PER_BLOCK_M[version - 1];
            blocks = BLOCKS_M[version - 1];
        }
        return TOTAL_CODEWORDS[version - 1] - eccPer * blocks;
    }

    private static int[] buildDataBits(byte[] bytes, int version, int dataCodewords) {
        List<Boolean> bits = new ArrayList<>(dataCodewords * 8);
        appendBits(bits, 0b0100, 4); // mode byte
        appendBits(bits, bytes.length, version <= 9 ? 8 : 16);
        for (byte b : bytes) {
            appendBits(bits, b & 0xFF, 8);
        }
        int terminator = 0;
        while (terminator < 4 && bits.size() < dataCodewords * 8) {
            bits.add(false);
            terminator++;
        }
        while (bits.size() % 8 != 0) {
            bits.add(false);
        }
        int pad = 0;
        while (bits.size() < dataCodewords * 8) {
            appendBits(bits, pad % 2 == 0 ? 0xEC : 0x11, 8);
            pad++;
        }
        int[] out = new int[dataCodewords];
        for (int i = 0; i < out.length; i++) {
            int v = 0;
            for (int j = 0; j < 8; j++) {
                v = (v << 1) | (bits.get(i * 8 + j) ? 1 : 0);
            }
            out[i] = v;
        }
        return out;
    }

    private static void appendBits(List<Boolean> bits, int value, int count) {
        for (int i = count - 1; i >= 0; i--) {
            bits.add(((value >> i) & 1) == 1);
        }
    }

    /** Bagi data ke blok RS, hitung ECC tiap blok, lalu interleave data lalu
     *  ECC (aturan 8.6 ISO/IEC 18004). */
    private static int[] interleave(int[] data, int version, Ecc ecc) {
        int eccPerBlock;
        int numBlocks;
        if (ecc == Ecc.L) {
            eccPerBlock = ECC_PER_BLOCK_L[version - 1];
            numBlocks = BLOCKS_L[version - 1];
        } else {
            eccPerBlock = ECC_PER_BLOCK_M[version - 1];
            numBlocks = BLOCKS_M[version - 1];
        }
        int numTotal = TOTAL_CODEWORDS[version - 1];
        int numData = data.length;
        // Kelompok 2 punya total codeword 1 lebih banyak (aturan Tabel 9).
        int group2 = numTotal % numBlocks;
        int group1 = numBlocks - group2;
        int dataPer1 = numData / numBlocks;
        int dataPer2 = dataPer1 + 1;

        List<int[]> blocksData = new ArrayList<>(numBlocks);
        List<int[]> blocksEcc = new ArrayList<>(numBlocks);
        int offset = 0;
        for (int i = 0; i < numBlocks; i++) {
            int dataLen = i < group1 ? dataPer1 : dataPer2;
            int[] blockData = new int[dataLen];
            System.arraycopy(data, offset, blockData, 0, dataLen);
            offset += dataLen;
            blocksData.add(blockData);
            blocksEcc.add(rsEncode(blockData, eccPerBlock));
        }
        List<Integer> result = new ArrayList<>(numTotal);
        int maxData = 0;
        for (int[] blockData : blocksData) {
            maxData = Math.max(maxData, blockData.length);
        }
        for (int i = 0; i < maxData; i++) {
            for (int[] blockData : blocksData) {
                if (i < blockData.length) {
                    result.add(blockData[i]);
                }
            }
        }
        for (int i = 0; i < eccPerBlock; i++) {
            for (int[] blockEcc : blocksEcc) {
                result.add(blockEcc[i]);
            }
        }
        int[] out = new int[result.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = result.get(i);
        }
        return out;
    }

    // ---------- Reed-Solomon over GF(256) ----------

    private static int mul(int a, int b) {
        if (a == 0 || b == 0) {
            return 0;
        }
        return EXP[LOG[a] + LOG[b]];
    }

    /** Polinomial generator RS: hasil kali (x - a^i) untuk i = 0..degree-1.
     *  gen[j] = koefisien x^j. */
    private static int[] rsGenerator(int degree) {
        int[] gen = {1};
        for (int i = 0; i < degree; i++) {
            int[] next = new int[gen.length + 1];
            for (int j = 0; j < gen.length; j++) {
                next[j] ^= mul(gen[j], EXP[i]);
                next[j + 1] ^= gen[j];
            }
            gen = next;
        }
        return gen;
    }

    private static int[] rsEncode(int[] data, int eccLen) {
        int[] gen = rsGenerator(eccLen);
        int[] res = new int[data.length + eccLen];
        System.arraycopy(data, 0, res, 0, data.length);
        for (int i = 0; i < data.length; i++) {
            int coef = res[i];
            if (coef != 0) {
                // Pembagian sintetis: koefisien x^j dikalikan coef lalu
                // dikurangi di posisi i + (eccLen - j) (suku leading x^k
                // meniadakan res[i] sendiri).
                for (int j = 0; j < eccLen; j++) {
                    res[i + eccLen - j] ^= mul(gen[j], coef);
                }
            }
        }
        int[] out = new int[eccLen];
        System.arraycopy(res, data.length, out, 0, eccLen);
        return out;
    }

    // ---------- Matriks ----------

    private static Matrix buildMatrix(int[] data, int version, Ecc ecc) {
        int size = version * 4 + 17;
        Matrix m = new Matrix(size);
        embedFinderAndSeparators(m);
        m.set(8, size - 8, true); // modul gelap (8.9)
        embedAlignment(m, version);
        embedTiming(m);
        embedFormat(m, ecc);
        if (version >= 7) {
            embedVersion(m, version);
        }
        embedData(m, data);
        return m;
    }

    private static void embedFinderAndSeparators(Matrix m) {
        int size = m.size;
        for (int y = 0; y < 7; y++) {
            for (int x = 0; x < 7; x++) {
                m.set(x, y, FINDER[y][x] == 1);
                m.set(size - 7 + x, y, FINDER[y][x] == 1);
                m.set(x, size - 7 + y, FINDER[y][x] == 1);
            }
        }
        // Separator putih di sekitar finder.
        for (int i = 0; i < 8; i++) {
            m.set(i, 7, false);
            m.set(size - 8 + i, 7, false);
            m.set(i, size - 8, false);
        }
        for (int i = 0; i < 7; i++) {
            m.set(7, i, false);
            m.set(size - 8, i, false);
            m.set(7, size - 7 + i, false);
        }
    }

    private static void embedAlignment(Matrix m, int version) {
        if (version < 2) {
            return;
        }
        int[] coords = ALIGNMENT[version - 1];
        for (int y : coords) {
            for (int x : coords) {
                if (m.isEmpty(x, y)) {
                    for (int dy = 0; dy < 5; dy++) {
                        for (int dx = 0; dx < 5; dx++) {
                            boolean dark = dx == 0 || dx == 4 || dy == 0 || dy == 4
                                    || (dx == 2 && dy == 2);
                            m.set(x - 2 + dx, y - 2 + dy, dark);
                        }
                    }
                }
            }
        }
    }

    private static void embedTiming(Matrix m) {
        for (int i = 8; i < m.size - 8; i++) {
            boolean bit = (i + 1) % 2 == 1;
            if (m.isEmpty(i, 6)) {
                m.set(i, 6, bit);
            }
            if (m.isEmpty(6, i)) {
                m.set(6, i, bit);
            }
        }
    }

    private static void embedFormat(Matrix m, Ecc ecc) {
        int typeInfo = (ecc.bits << 3) | 0; // mask pattern 0
        int format = ((typeInfo << 10) | bch(typeInfo, 0x537)) ^ 0x5412;
        int size = m.size;
        int[][] coords = {
            {8, 0}, {8, 1}, {8, 2}, {8, 3},
            {8, 4}, {8, 5}, {8, 7}, {8, 8},
            {7, 8}, {5, 8}, {4, 8}, {3, 8},
            {2, 8}, {1, 8}, {0, 8}
        };
        for (int i = 0; i < 15; i++) {
            boolean bit = ((format >> i) & 1) == 1;
            m.set(coords[i][0], coords[i][1], bit);
            if (i < 8) {
                m.set(size - 1 - i, 8, bit);
            } else {
                m.set(8, size - 15 + i, bit);
            }
        }
        m.set(size - 8, 8, true);
    }

    private static void embedVersion(Matrix m, int version) {
        int bits = (version << 12) | bch(version, 0x1F25);
        // Bit LSB dulu (urutan penulisan BitArray zxing: bit 0 = bit pertama
        // yang ditambahkan = MSB version).
        int bitIndex = 0;
        for (int i = 0; i < 6; i++) {
            for (int j = 0; j < 3; j++) {
                boolean bit = ((bits >> bitIndex) & 1) == 1;
                bitIndex++;
                m.set(i, m.size - 11 + j, bit);
                m.set(m.size - 11 + j, i, bit);
            }
        }
    }

    /** Isi data zigzag dari kanan-bawah, lompati pola fungsi; mask 0. */
    private static void embedData(Matrix m, int[] data) {
        List<Boolean> bits = new ArrayList<>(data.length * 8);
        for (int b : data) {
            for (int i = 7; i >= 0; i--) {
                bits.add(((b >> i) & 1) == 1);
            }
        }
        int bitIndex = 0;
        int direction = -1;
        int x = m.size - 1;
        int y = m.size - 1;
        while (x > 0) {
            if (x == 6) {
                x--;
            }
            while (y >= 0 && y < m.size) {
                for (int i = 0; i < 2; i++) {
                    int xx = x - i;
                    if (m.isEmpty(xx, y)) {
                        boolean bit = bitIndex < bits.size() && bits.get(bitIndex);
                        if (bitIndex < bits.size()) {
                            bitIndex++;
                        }
                        if (((xx + y) & 1) == 0) {
                            bit = !bit; // mask 0
                        }
                        m.set(xx, y, bit);
                    }
                }
                y += direction;
            }
            direction = -direction;
            y += direction;
            x -= 2;
        }
    }

    /** BCH remainder (16-bit) — dipakai untuk format info & version info. */
    private static int bch(int value, int poly) {
        int v = value << (32 - Integer.numberOfLeadingZeros(poly) - 1);
        int polyMsb = 32 - Integer.numberOfLeadingZeros(poly);
        while (32 - Integer.numberOfLeadingZeros(v) >= polyMsb) {
            v ^= poly << (32 - Integer.numberOfLeadingZeros(v) - polyMsb);
        }
        return v;
    }
}
