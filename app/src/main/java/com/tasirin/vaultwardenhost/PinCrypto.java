package com.tasirin.vaultwardenhost;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

/** Hash & verifikasi PIN app (PBKDF2-HMAC-SHA256 + salt acak).
 *  Format simpan: {@code PBKDF2$iterasi$salthex$hashhex}.
 *  Menerima hash lama (SHA-256 tanpa salt, 64 hex) agar bisa diverifikasi
 *  sekali lalu dimigrasi ke format baru. Murni JVM — bisa unit test. */
public final class PinCrypto {

    public static final int MAX_GAGAL = 5;
    public static final long KUNCI_MS = 5 * 60_000;

    private static final String PREFIX = "PBKDF2$";
    /** Awalan hash "bungkus-dini": PBKDF2 atas hash legasi (bukan PIN langsung).
     *  Lihat bungkusLegasi. */
    static final String PREFIX_BUNGKUS = "PBKDF2W$";
    /** Iterasi hash BARU: 30rb (dulu 120rb) agar sekali set/buka PIN di CPU
     *  lemah STB rampung ~1 detik, bukan belasan detik yang bikin status
     *  "proses" dikira macet. PIN tetap aman: brute-force online dibatasi
     *  kunci 5 menit tiap 5 gagal (PinGate) dan salt acak tiap hash menutup
     *  rainbow table; biaya KDF hanya pertahanan lapis kedua. */
    private static final int ITERATIONS = 30_000;
    /** Iterasi untuk STB Android lama tanpa factory PBKDF2-SHA256: jalur manual
     *  via Mac ~2-3x lebih lambat per iterasi, jadi standar diturunkan agar
     *  set/buka PIN tetap ~1 detik. Tetap di atas ITERASI_MINIMAL sehingga
     *  diterima semua perangkat; PIN 4-6 digit memang mengandalkan kunci
     *  lockout + salt, bukan besar iterasi, melawan serangan offline. */
    static final int ITERASI_LEGASI = 12_000;
    /** SecureRandom bersama (thread-safe): hemat biaya seed tiap hash di STB. */
    private static final class Acak {
        static final java.security.SecureRandom ISI = new java.security.SecureRandom();
    }
    /** Hasil deteksi factory (null = belum dicek): getInstance yang gagal di
     *  STB tak diulang tiap buka PIN. */
    private static volatile Boolean pabrikAda = null;
    /** Iterasi minimum yang diterima saat verifikasi: hash beriterasi jauh
     *  lebih rendah (mis. hasil utak-atik prefs) ditolak fail-closed.
     *  Semua hash yang ditulis app ini memakai standar perangkat
     *  (lihat iterasiStandar), selalu di atas batas ini. */
    static final int ITERASI_MINIMAL = 10_000;
    /** Iterasi maksimum yang diterima saat verifikasi: hash lama 120rb yang
     *  ditulis versi app sebelumnya tetap diverifikasi sekali lalu
     *  dinormalisasi ke standar perangkat (lihat perluUpgradeHash), bukan ditolak
     *  dan mengunci user. Batas ini juga menutup DoS iterasi raksasa. */
    static final int MAKS_VERIFIKASI = 120_000;
    /** Panjang heks maksimum tiap bagian salt/hash saat verifikasi: format sah
     *  hanya 32 (salt 16 byte) dan 64 char (hash 32 byte). Heks raksasa dari
     *  prefs utak-atik/import jahat memaksa alokasi besar sebelum cek ukuran
     *  byte sempat menolak — tolak dini fail-closed agar STB 1 GB tak OOM. */
    static final int HEKS_MAKSIMAL = 128;
    private static final int SALT_BYTES = 16;
    private static final int HASH_BITS = 256;

    private PinCrypto() {
    }

    /** Buat hash baru untuk PIN (salt acak tiap panggilan). */
    public static String hash(String pin) {
        if (pin == null) {
            pin = "";
        }
        byte[] salt = new byte[SALT_BYTES];
        Acak.ISI.nextBytes(salt);
        int iter = iterasiStandar();
        byte[] dk = derive(pin, salt, iter);
        return PREFIX + iter + "$" + hex(salt) + "$" + hex(dk);
    }

    /** True bila tersimpan dalam format baru (bukan hash lama). */
    public static boolean isNewFormat(String stored) {
        return stored != null && stored.startsWith(PREFIX);
    }

    /** True bila hash lama wajib dibungkus/dimigrasi: bukan format standar
     *  maupun bungkusan dini. Murni. */
    public static boolean perluMigrasi(String stored) {
        return stored != null && !stored.isEmpty()
                && !isNewFormat(stored) && !isFormatBungkus(stored);
    }

    /** True bila hash legasi valid (64 hex) yang bisa dibungkus. Murni. */
    static boolean hashLegasiValid(String stored) {
        if (stored == null) {
            return false;
        }
        String l = stored.trim().toLowerCase(java.util.Locale.US);
        if (l.length() != 64) {
            return false;
        }
        for (int i = 0; i < l.length(); i++) {
            char c = l.charAt(i);
            if (!((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f'))) {
                return false;
            }
        }
        return true;
    }

    /** Bungkus hash legasi ke format ber-salt + stretch 30k tanpa perlu PIN.
     *  Tanpa ini SHA-256 tanpa salt bertahan di disk sampai login sukses
     *  berikutnya dan retak offline dalam detik bila prefs bocor (PIN 4-6
     *  digit). Tiap tebakan atas hasil bungkusan memaksa PBKDF2 30k penuh
     *  karena inputnya bukan PIN melainkan hash legasi. Null bila bukan
     *  hash legasi valid (tak ada yang boleh ditulis). Murni CPU — panggil
     *  dari worker thread (lihat PinGate.kuatkanHashDini). */
    static String bungkusLegasi(String stored) {
        if (!hashLegasiValid(stored)) {
            return null;
        }
        String dalam = stored.trim().toLowerCase(java.util.Locale.US);
        byte[] salt = new byte[SALT_BYTES];
        Acak.ISI.nextBytes(salt);
        int iter = iterasiStandar();
        byte[] dk = derive(dalam, salt, iter);
        return PREFIX_BUNGKUS + iter + "$" + hex(salt) + "$" + hex(dk);
    }

    /** True bila tersimpan dalam format bungkus-dini. Murni. */
    static boolean isFormatBungkus(String stored) {
        return stored != null && stored.startsWith(PREFIX_BUNGKUS);
    }

    /** True bila hash wajib di-upgrade sesudah verifikasi sukses: hash lama,
     *  bungkusan dini (normalisasi ke format standar), maupun PBKDF2 yang
     *  iterasinya bukan standar perangkat ini (prefs utak-atik 10k, hash lama
     *  120k yang berat di STB, atau hash HP 30k yang dibawa ke STB lama dan
     *  sebaliknya). Tanpa ini hash lemah lolos selamanya dan buka PIN di STB
     *  tetap lambat tiap kali.
     *  Murni agar bisa unit test. */
    public static boolean perluUpgradeHash(String stored) {
        if (stored == null || stored.isEmpty()) {
            return false;
        }
        if (isFormatBungkus(stored)) {
            return true;
        }
        if (!isNewFormat(stored)) {
            return true;
        }
        try {
            String[] parts = Util.pecahPin(stored);
            if (parts == null || parts.length != 4) {
                return true;
            }
            int iter = Integer.parseInt(parts[1]);
            // Minta upgrade bila salt/hash tak standar atau iterasi bukan standar
            // perangkat ini (lihat iterasiStandar).
            // Hash sah: salt 16 byte (32 hex) & hash 32 byte (64 hex).
            byte[] salt = unhex(parts[2]);
            byte[] want = unhex(parts[3]);
            if (salt == null || want == null || salt.length != 16 || want.length != 32) {
                return true;
            }
            return iter != iterasiStandar();
        } catch (Exception e) {
            return true;
        }
    }

    /** Verifikasi PIN terhadap hash lama maupun baru (perbandingan konstan). */
    public static boolean verify(String stored, String pin) {
        // Tolak dini input raksasa dari prefs utak-atik agar tak boros memori.
        if (stored == null || stored.length() > 512) {
            return false;
        }
        // Guard pertama di atas sudah menolak null, jadi cek null tak diulang di sini.
        if (stored.isEmpty() || pin == null || pin.isEmpty()) {
            return false;
        }
        try {
            if (isNewFormat(stored)) {
                String[] parts = Util.pecahPin(stored);
                if (parts == null || parts.length != 4) {
                    return false;
                }
                int iter = Integer.parseInt(parts[1]);
                // Batas atas = MAKS_VERIFIKASI (120k): hash utak-atik beriterasi
                // raksasa (mis. 1 jt) memaksa PBKDF2 puluhan kali dan stall
                // STB/polling tiap upaya verifikasi. Hash lama 10k-120k tetap
                // diverifikasi lalu di-upgrade (lihat perluUpgradeHash).
                if (iter < ITERASI_MINIMAL || iter > MAKS_VERIFIKASI) {
                    return false;
                }
                // Heks raksasa ditolak sebelum unhex mengalokasi
                // setengah panjang string di memori STB 1 GB.
                if (parts[2].length() > HEKS_MAKSIMAL || parts[3].length() > HEKS_MAKSIMAL) {
                    return false;
                }
                byte[] salt = unhex(parts[2]);
                byte[] want = unhex(parts[3]);
                // Tolak salt pendek/hash tak standar: sah hanya salt 16 byte & hash 32 byte.
                if (salt == null || want == null || salt.length != 16
                        || want.length != 32) {
                    return false;
                }
                byte[] got = derive(pin, salt, iter);
                return MessageDigest.isEqual(got, want);
            }
            if (isFormatBungkus(stored)) {
                String[] parts = Util.pecahPin(stored);
                if (parts == null || parts.length != 4) {
                    return false;
                }
                int iter;
                try {
                    iter = Integer.parseInt(parts[1]);
                } catch (Exception e) {
                    return false;
                }
                // Batas sama seperti format standar: iterasi raksasa = DoS,
                // iterasi mini = prefs utak-atik.
                if (iter < ITERASI_MINIMAL || iter > MAKS_VERIFIKASI) {
                    return false;
                }
                // Batas sama seperti format standar: heks raksasa = OOM.
                if (parts[2].length() > HEKS_MAKSIMAL || parts[3].length() > HEKS_MAKSIMAL) {
                    return false;
                }
                byte[] salt = unhex(parts[2]);
                byte[] want = unhex(parts[3]);
                // Tolak salt pendek/hash tak standar: sah hanya salt 16 byte & hash 32 byte.
                if (salt == null || want == null || salt.length != 16
                        || want.length != 32) {
                    return false;
                }
                byte[] got = derive(sha256(pin), salt, iter);
                return MessageDigest.isEqual(got, want);
            }
            // Legasi: SHA-256 tanpa salt — banding tak peka huruf agar hash
            // lama ber-huruf besar tak mengunci user (disimpan lowercase baru).
            return slowHexEquals(sha256(pin), stored.trim().toLowerCase(java.util.Locale.US));
        } catch (Exception e) {
            return false;
        }
    }

    /** Batas pisah wall-clock vs jam monoton: uptime tak pernah capai 1e11 ms
     *  (~3 tahun tanpa reboot), sedangkan wall-clock kini ~1,7e12. */
    static final long AMBANG_WALL_MS = 100_000_000_000L;

    /** Sisa kunci (ms) dari data mentah prefs; 0 bila boleh coba. Murni.
     *  Batas waktu wajib wall-clock (currentTimeMillis) agar reboot tak mereset
     *  lockout brute-force. Stempel hilang (prefs korup/kunci dihapus) fail-closed
     *  dengan kunci penuh agar lockout tak bisa di-bypass; nilai lama era
     *  elapsedRealtime (kecil tapi non-nol, di bawah ambang) tetap dianggap
     *  kedaluwarsa agar tak mengunci permanen. Jam perangkat yang mundur ke
     *  1970 (sekarang kecil) fail-closed dengan kunci penuh agar penyerang tak
     *  bisa bypass via utak-atik tanggal. */
    public static long sisaKunciMs(int gagal, long terkunciSampai, long sekarang) {
        if (gagal < MAX_GAGAL) {
            return 0;
        }
        if (terkunciSampai <= 0) {
            return KUNCI_MS;
        }
        if (sekarang >= terkunciSampai) {
            return 0;
        }
        if (terkunciSampai <= AMBANG_WALL_MS) {
            return 0;
        }
        if (sekarang <= AMBANG_WALL_MS) {
            return KUNCI_MS;
        }
        return terkunciSampai - sekarang;
    }

    /** Kunci baru sesudah satu kegagalan (perpanjangan bila sudah capai batas). Murni. */
    public static long kunciBerikutnyaMs(int gagalSesudah, long sekarang) {
        if (gagalSesudah < MAX_GAGAL) {
            return 0;
        }
        return sekarang + KUNCI_MS;
    }

    /** Sisa kunci monotonik (ms) dari jam elapsed; 0 bila boleh coba. Murni.
     *  Pendamping wall-clock untuk jam STB rusak (1970) dan reset jam oleh
     *  penyerang: elapsed tak bisa dimundurkan tanpa reboot, dan reboot
     *  ditutup wall-clock. Sisa di atas KUNCI_MS dianggap basi (reboot
     *  me-reset elapsed sehingga selisih meledak) agar tak mengunci permanen. */
    public static long sisaKunciElapsed(long terkunciElapsed, long sekarangElapsed) {
        long sisa = terkunciElapsed - sekarangElapsed;
        if (sisa <= 0 || sisa > KUNCI_MS) {
            return 0;
        }
        return sisa;
    }

    /** Kunci elapsed baru sesudah satu kegagalan. Murni. */
    public static long kunciElapsedBerikutnyaMs(int gagalSesudah, long sekarangElapsed) {
        if (gagalSesudah < MAX_GAGAL) {
            return 0;
        }
        return sekarangElapsed + KUNCI_MS;
    }

    /** True bila factory PBKDF2-SHA256 ada (sekali cek, hasilnya diingat). */
    static boolean pabrikTersedia() {
        Boolean c = pabrikAda;
        if (c != null) {
            return c.booleanValue();
        }
        boolean ada;
        try {
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
            ada = true;
        } catch (Exception e) {
            ada = false;
        }
        pabrikAda = ada ? Boolean.TRUE : Boolean.FALSE;
        return ada;
    }

    /** Iterasi hash standar perangkat ini: penuh di HP modern, diringankan di
     *  STB lama yang memakai jalur manual lambat. Murni (varian boolean) agar
     *  bisa unit test di JVM yang factory-nya selalu ada. */
    static int iterasiStandar(boolean pabrik) {
        return pabrik ? ITERATIONS : ITERASI_LEGASI;
    }

    /** Varian produksi: standar mengikuti hasil deteksi factory. */
    static int iterasiStandar() {
        return iterasiStandar(pabrikTersedia());
    }

    private static byte[] derive(String pin, byte[] salt, int iter) {
        // Salinan char dinolkan di finally: String PIN tak bisa dihapus, tapi
        // salinan kerja ini jangan mengendap di heap sampai GC.
        char[] chars = pin == null ? new char[0] : pin.toCharArray();
        PBEKeySpec spec = new PBEKeySpec(chars, salt, iter, HASH_BITS);
        try {
            if (pabrikTersedia()) {
                try {
                    SecretKeyFactory f = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
                    return f.generateSecret(spec).getEncoded();
                } catch (Exception e) {
                    // Factory sempat ada lalu gagal: ingat agar tak dicoba lagi.
                    pabrikAda = Boolean.FALSE;
                }
            }
            // STB Android lama (API 21-25) tak punya factory PBKDF2WithHmacSHA256
            // sehingga centang PIN selalu gagal di sana ("PIN gagal diproses")
            // sementara di HP bisa: hitung manual via Mac HmacSHA256 yang ada
            // di semua API.
            return pbkdf2Manual(pin, salt, iter);
        } finally {
            spec.clearPassword();
            java.util.Arrays.fill(chars, '\0');
        }
    }

    /** PBKDF2-HMAC-SHA256 manual (RFC 2898) untuk STB tanpa factory-nya.
     *  dkLen 256 bit = tepat 1 blok SHA-256: F = U1^U2^...^Uc dengan
     *  U1 = HMAC(PIN, salt || INT_32_BE(1)). Hasil bit-identik dengan factory
     *  untuk PIN ASCII (digit/huruf) sehingga hash HP baru terverifikasi di
     *  STB dan sebaliknya. Murni JVM — bisa unit test. */
    static byte[] pbkdf2Manual(String pin, byte[] salt, int iter) {
        if (iter < 1) {
            throw new IllegalStateException("PBKDF2 tidak tersedia");
        }
        byte[] sandi = pin == null
                ? new byte[0] : pin.getBytes(StandardCharsets.UTF_8);
        try {
            javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
            mac.init(new javax.crypto.spec.SecretKeySpec(sandi, "HmacSHA256"));
            byte[] blok = new byte[salt.length + 4];
            System.arraycopy(salt, 0, blok, 0, salt.length);
            blok[salt.length + 3] = 1;
            byte[] u = mac.doFinal(blok);
            byte[] hasil = u.clone();
            for (int i = 1; i < iter; i++) {
                u = mac.doFinal(u);
                for (int j = 0; j < hasil.length; j++) {
                    hasil[j] ^= u[j];
                }
            }
            return hasil;
        } catch (Exception e) {
            throw new IllegalStateException("PBKDF2 tidak tersedia", e);
        } finally {
            java.util.Arrays.fill(sandi, (byte) 0);
        }
    }

    static String sha256(String pin) {
        try {
            // Digest bersama per thread (bukan lookup provider tiap panggil).
            java.security.MessageDigest md = Util.mdSha256();
            return hex(md.digest(pin.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            return "";
        }
    }

    private static boolean slowHexEquals(String a, String b) {
        if (a == null || b == null || a.length() != b.length()) {
            return false;
        }
        return MessageDigest.isEqual(a.getBytes(StandardCharsets.US_ASCII),
                b.getBytes(StandardCharsets.US_ASCII));
    }

    static String hex(byte[] data) {
        // Tabel bersama (bukan Character.forDigit yang menelepon + cek radix
        // per digit): hemat di jalur verifikasi PIN STB lama. Hasil sama persis.
        char[] o = new char[data.length * 2];
        for (int i = 0; i < data.length; i++) {
            int v = data[i] & 0xFF;
            o[i * 2] = Util.HEKS[v >>> 4];
            o[i * 2 + 1] = Util.HEKS[v & 15];
        }
        return new String(o);
    }

    private static byte[] unhex(String s) {
        if (s == null || (s.length() & 1) != 0 || s.isEmpty()) {
            return null;
        }
        byte[] out = new byte[s.length() / 2];
        for (int i = 0; i < out.length; i++) {
            int hi = Character.digit(s.charAt(i * 2), 16);
            int lo = Character.digit(s.charAt(i * 2 + 1), 16);
            if (hi < 0 || lo < 0) {
                return null;
            }
            out[i] = (byte) ((hi << 4) | lo);
        }
        return out;
    }
}
