package com.tasirin.vaultwardenhost;

import java.io.File;
import java.io.IOException;

public final class Util {

    public static final long BATAS_UNZIP_WEBVAULT = 300L * 1024 * 1024;

    public static final long BATAS_UNZIP_RESTORE = 200L * 1024 * 1024;

    public static final int BATAS_JUMLAH_ENTRI = 20000;

    private Util() {
    }

    private static final java.util.concurrent.RejectedExecutionHandler TOLAK_BERLOG =
            penanganAntreanPenuh("Util");

    public static java.util.concurrent.RejectedExecutionHandler penanganAntreanPenuh(
            final String tag) {
        final String t = tag == null ? "Util" : tag;
        return new java.util.concurrent.RejectedExecutionHandler() {
            @Override public void rejectedExecution(Runnable r,
                    java.util.concurrent.ThreadPoolExecutor e) {
                try {
                    android.util.Log.w(t, "Antrean penuh, tugas baru ditolak");
                } catch (Exception ignored) {
                }
                throw new java.util.concurrent.RejectedExecutionException(
                        t + ": antrean penuh");
            }
        };
    }

    private static java.util.concurrent.ExecutorService buatPool(
            int ukuran, int antrean, final String nama) {
        java.util.concurrent.ThreadPoolExecutor p =
                new java.util.concurrent.ThreadPoolExecutor(ukuran, ukuran, 60L,
                        java.util.concurrent.TimeUnit.SECONDS,
                        new java.util.concurrent.LinkedBlockingQueue<Runnable>(antrean),
                        r -> {
                            Thread t = new Thread(r, nama);
                            t.setDaemon(true);
                            return t;
                        }, TOLAK_BERLOG);
        p.allowCoreThreadTimeOut(true);
        return p;
    }

    private static final java.util.concurrent.ExecutorService BG_RINGAN =
            buatPool(3, 32, "vw-ui-bg");

    private static final java.util.concurrent.ExecutorService BG_LAMA =
            buatPool(2, 16, "vw-bg-lama");

    private static final java.util.concurrent.ExecutorService BG_PIN =
            buatPool(1, 8, "vw-pin");

    public static boolean jalankanLama(Runnable r) {
        if (r == null) {
            return false;
        }
        try {
            BG_LAMA.execute(r);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    public static boolean jalankanPin(Runnable r) {
        if (r == null) {
            return false;
        }
        try {
            BG_PIN.execute(r);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static final ThreadLocal<java.security.MessageDigest> MD_SHA256 =
            new ThreadLocal<java.security.MessageDigest>() {
            };

    public static java.security.MessageDigest mdSha256() throws Exception {
        java.security.MessageDigest m = MD_SHA256.get();
        if (m == null) {
            m = java.security.MessageDigest.getInstance("SHA-256");
            MD_SHA256.set(m);
        } else {

            try {
                m.reset();
            } catch (Exception ignored) {
            }
        }
        return m;
    }

    static final char[] HEKS = "0123456789abcdef".toCharArray();

    static String hex12(long v) {
        char[] o = new char[12];
        for (int i = 11; i >= 0; i--) {
            o[i] = HEKS[(int) (v & 15)];
            v >>>= 4;
        }
        return new String(o);
    }

    static String heks(byte[] data) {
        if (data == null) {
            return "";
        }
        char[] o = new char[data.length * 2];
        for (int i = 0; i < data.length; i++) {
            int v = data[i] & 0xFF;
            o[i * 2] = HEKS[v >>> 4];
            o[i * 2 + 1] = HEKS[v & 15];
        }
        return new String(o);
    }

    public static String rendahAscii(String s) {
        if (s == null) {
            return "";
        }
        int n = s.length();
        for (int i = 0; i < n; i++) {
            char c = s.charAt(i);
            if (c >= 'A' && c <= 'Z') {
                char[] o = s.toCharArray();
                o[i] = (char) (c + 32);
                for (int j = i + 1; j < n; j++) {
                    char d = o[j];
                    if (d >= 'A' && d <= 'Z') {
                        o[j] = (char) (d + 32);
                    }
                }
                return new String(o);
            }
        }
        return s;
    }

    static boolean numerik4Plus(String s) {
        if (s == null || s.length() < 4) {
            return false;
        }
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < '0' || c > '9') {
                return false;
            }
        }
        return true;
    }

    static boolean pinKataValid(String s) {
        if (s == null || s.length() < 4) {
            return false;
        }
        boolean digit = false;
        boolean simbol = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c <= ' ') {
                return false;
            }
            if (c >= '0' && c <= '9') {
                digit = true;
            } else if (!((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z'))) {
                simbol = true;
            }
        }
        return digit || simbol;
    }

    private static final java.util.concurrent.ConcurrentHashMap<String, ThreadLocal<java.text.SimpleDateFormat>> FMT_CACHE =
            new java.util.concurrent.ConcurrentHashMap<String, ThreadLocal<java.text.SimpleDateFormat>>();

    public static String formatTanggal(String pola, java.util.Date t) {
        if (pola == null || t == null) {
            return "";
        }
        try {
            ThreadLocal<java.text.SimpleDateFormat> tl = FMT_CACHE.get(pola);
            if (tl == null) {
                final String pe = pola;
                ThreadLocal<java.text.SimpleDateFormat> buat =
                        new ThreadLocal<java.text.SimpleDateFormat>() {
                    @Override protected java.text.SimpleDateFormat initialValue() {
                        return new java.text.SimpleDateFormat(pe, java.util.Locale.US);
                    }
                };
                ThreadLocal<java.text.SimpleDateFormat> menang =
                        FMT_CACHE.putIfAbsent(pola, buat);
                tl = menang != null ? menang : buat;
            }
            return tl.get().format(t);
        } catch (Exception ignored) {
            return "";
        }
    }

    private static final ThreadLocal<java.text.SimpleDateFormat> FMT_STB_ID =
            new ThreadLocal<java.text.SimpleDateFormat>() {
                @Override protected java.text.SimpleDateFormat initialValue() {
                    return new java.text.SimpleDateFormat("d MMM yyyy HH:mm",
                            new java.util.Locale("id", "ID"));
                }
            };

    public static String tanggalStb(long kiniMs) {
        try {
            return FMT_STB_ID.get().format(new java.util.Date(kiniMs));
        } catch (Exception ignored) {
            return String.valueOf(kiniMs);
        }
    }

    public static String formatTanggalSekarang(String pola) {
        try {
            return formatTanggal(pola, new java.util.Date());
        } catch (Exception ignored) {
            return "";
        }
    }

    private static volatile android.os.Handler HANDLER_UTAMA = null;

    private static final Object KUNCI_HANDLER = new Object();

    private static android.os.Handler handlerUtama() {
        try {
            android.os.Looper looper = android.os.Looper.getMainLooper();
            if (looper == null) {
                return null;
            }
            android.os.Handler h = HANDLER_UTAMA;
            if (h == null || h.getLooper() != looper) {
                synchronized (KUNCI_HANDLER) {
                    h = HANDLER_UTAMA;
                    if (h == null || h.getLooper() != looper) {
                        h = new android.os.Handler(looper);
                        HANDLER_UTAMA = h;
                    }
                }
            }
            return h;
        } catch (Exception ignored) {
            return null;
        }
    }

    public static void postTunda(Runnable r, long tundaMs) {
        if (r == null) {
            return;
        }
        try {
            android.os.Handler h = handlerUtama();
            if (h == null) {
                return;
            }
            h.postDelayed(r, tundaMs);
        } catch (Exception ignored) {
        }
    }

    public static void postUtama(Runnable r) {
        if (r == null) {
            return;
        }
        try {
            android.os.Handler h = handlerUtama();
            if (h == null) {
                return;
            }
            h.post(r);
        } catch (Exception ignored) {
        }
    }

    public static void batalkanTunda(Runnable r) {
        if (r == null) {
            return;
        }
        try {

            android.os.Handler h = HANDLER_UTAMA;
            if (h == null) {
                return;
            }
            h.removeCallbacks(r);
        } catch (Exception ignored) {
        }
    }

    public static Thread pasangWatchdogProses(final Process proc,
            final java.util.concurrent.atomic.AtomicBoolean selesai,
            final long timeoutMs, final String nama) {
        Thread w = new Thread(new Runnable() {
            @Override public void run() {
                try {
                    Thread.sleep(timeoutMs);
                } catch (InterruptedException e) {
                    return;
                }
                try {
                    if (selesai != null && !selesai.get() && proc != null) {
                        proc.destroy();
                    }
                } catch (Exception ignored) {
                }
            }
        }, nama == null || nama.isEmpty() ? "vw-watchdog" : nama);
        try {
            w.setDaemon(true);
            w.start();
        } catch (Exception ignored) {
        }
        return w;
    }

    public static String[] pecahPin(String s) {
        if (s == null) {
            return null;
        }
        int a = s.indexOf('$');
        if (a < 0) {
            return null;
        }
        int b = s.indexOf('$', a + 1);
        if (b < 0) {
            return null;
        }
        int c = s.indexOf('$', b + 1);
        if (c < 0 || s.indexOf('$', c + 1) >= 0) {
            return null;
        }
        String[] out = new String[4];
        out[0] = s.substring(0, a);
        out[1] = s.substring(a + 1, b);
        out[2] = s.substring(b + 1, c);
        out[3] = s.substring(c + 1);
        return out;
    }

    public static boolean mengandungAbaikanHuruf(String hay, String needle) {
        if (hay == null || needle == null || needle.isEmpty() || hay.length() < needle.length()) {
            return false;
        }

        boolean ascii = true;
        for (int k = 0; k < needle.length(); k++) {
            if (needle.charAt(k) >= 128) {
                ascii = false;
                break;
            }
        }
        if (ascii) {
            return cocokAscii(hay, needle);
        }
        int n = hay.length() - needle.length();
        for (int i = 0; i <= n; i++) {
            if (hay.regionMatches(true, i, needle, 0, needle.length())) {
                return true;
            }
        }
        return false;
    }

    public static boolean cocokAscii(String hay, String needle) {
        int n = hay.length() - needle.length();
        for (int i = 0; i <= n; i++) {
            int j = 0;
            while (j < needle.length() && samaHurufAscii(hay.charAt(i + j), needle.charAt(j))) {
                j++;
            }
            if (j >= needle.length()) {
                return true;
            }
        }
        return false;
    }

    static boolean samaHurufAscii(char a, char b) {
        if (a == b) {
            return true;
        }
        if (a >= 'A' && a <= 'Z') {
            a = (char) (a + 32);
        }
        if (b >= 'A' && b <= 'Z') {
            b = (char) (b + 32);
        }
        return a == b;
    }

    public static boolean jalankanBg(Runnable r) {
        if (r == null) {
            return false;
        }
        try {
            BG_RINGAN.execute(r);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    static boolean angkaBulat(String s) {
        if (s == null || s.isEmpty()) {
            return false;
        }
        int i = 0;
        char depan = s.charAt(0);
        if (depan == '+' || depan == '-') {
            i = 1;
        }
        if (i >= s.length()) {
            return false;
        }
        for (; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < '0' || c > '9') {
                return false;
            }
        }
        return true;
    }

    static boolean semuaDigit(String s) {
        if (s == null || s.isEmpty()) {
            return false;
        }
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < '0' || c > '9') {
                return false;
            }
        }
        return true;
    }

    public static String amanTrim(String s) {
        return s == null ? "" : s.trim();
    }

    private static volatile boolean peringatanUsernameSudah = false;

    public static boolean cocokChat(String config, long id, String username) {
        if (config == null) {
            return false;
        }
        String c = config.trim();
        if (c.isEmpty()) {
            return false;
        }

        if (c.startsWith("+") && c.length() > 1) {
            c = c.substring(1).trim();
            if (c.isEmpty()) {
                return false;
            }
        }

        if (c.length() > 1 && c.startsWith("0")) {
            return false;
        }

        if (angkaBulat(c)) {
            try {
                return id == Long.parseLong(c);
            } catch (NumberFormatException ignored) {
            }
        }
        String norm = c.startsWith("@") ? c.substring(1) : c;
        if (norm.isEmpty() || username == null) {
            return false;
        }

        if (semuaDigit(norm)) {

            if (norm.length() > 19) {
                return false;
            }
            try {
                if (norm.length() > 1 && norm.startsWith("0")) {
                    return false;
                }
                return id == Long.parseLong(norm);
            } catch (NumberFormatException e) {
                return false;
            }
        }

        if (!peringatanUsernameSudah) {
            peringatanUsernameSudah = true;
            try {
                android.util.Log.w("Util", "[tg] Chat bot memakai username (@"
                        + norm + "), bukan ID numerik. Username bisa diklaim"
                        + " ulang orang lain — ganti ke ID numerik di pengaturan.");
            } catch (Throwable ignored) {
            }
        }

        String u = username.trim();
        if (u.startsWith("@") && u.length() > 1) {
            u = u.substring(1);
        }
        return norm.equalsIgnoreCase(u);
    }

    public static final int MAKS_CHAT_TUJUAN = 5;

    public static final int BATAS_ENTRI_CHAT = 32;

    public static java.util.List<String> pecahDaftarChat(String config) {
        java.util.List<String> hasil = new java.util.ArrayList<String>();
        if (config == null) {
            return hasil;
        }

        java.util.Set<String> terlihat = new java.util.HashSet<String>();
        int n = config.length();
        int awal = -1;
        for (int i = 0; i <= n; i++) {
            char c = i < n ? config.charAt(i) : ',';
            boolean pisah = c == ',' || c == ';' || c == '\n' || c == '\r'
                    || c == '\t' || c == ' ';
            if (pisah) {
                if (awal >= 0) {
                    String potong = config.substring(awal, i).trim();
                    if (!potong.isEmpty() && terlihat.add(potong)) {
                        hasil.add(potong);
                        if (hasil.size() >= BATAS_ENTRI_CHAT) {
                            break;
                        }
                    }
                    awal = -1;
                }
            } else if (awal < 0) {
                awal = i;
            }
        }
        return hasil;
    }

    public static boolean cocokChatBanyak(String config, long id, String username) {
        java.util.List<String> daftar = pecahDaftarChat(config);
        for (int i = 0; i < daftar.size(); i++) {
            if (cocokChat(daftar.get(i), id, username)) {
                return true;
            }
        }
        return false;
    }

    static int bandingDigit(String a, String b) {
        for (int i = 0; i < a.length() && i < b.length(); i++) {
            char x = a.charAt(i);
            char y = b.charAt(i);
            if (x != y) {
                return x < y ? -1 : 1;
            }
        }
        if (a.length() == b.length()) {
            return 0;
        }
        return a.length() < b.length() ? -1 : 1;
    }

    public static boolean chatTunggalValid(String entri) {
        if (entri == null) {
            return false;
        }
        String t = entri.trim();
        if (t.isEmpty() || t.length() > 64) {
            return false;
        }
        for (int i = 0; i < t.length(); i++) {
            char c = t.charAt(i);
            if (c <= ' ' || c == 127) {
                return false;
            }
        }
        String isi = t;
        if (isi.startsWith("+") && isi.length() > 1) {
            isi = isi.substring(1);
        }
        if (isi.isEmpty()) {
            return false;
        }

        boolean negatif = isi.startsWith("-");
        String angka = negatif ? isi.substring(1) : isi;
        if (!angka.isEmpty() && semuaDigit(angka)) {
            if (angka.length() > 19) {
                return false;
            }
            if (angka.length() > 1 && angka.startsWith("0")) {
                return false;
            }

            if (angka.length() == 19) {
                String batas = negatif ? "9223372036854775808"
                        : "9223372036854775807";
                if (bandingDigit(angka, batas) > 0) {
                    return false;
                }
            }
            return true;
        }
        String nama = isi.startsWith("@") ? isi.substring(1) : isi;
        if (nama.isEmpty() || semuaDigit(nama) || nama.length() < 5) {
            return false;
        }
        for (int i = 0; i < nama.length(); i++) {
            char c = nama.charAt(i);
            boolean ok = (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z')
                    || (c >= '0' && c <= '9') || c == '_';
            if (!ok) {
                return false;
            }
        }
        return true;
    }

    public static boolean daftarPerluAnggapGrup(String config) {
        java.util.List<String> daftar = pecahDaftarChat(config);
        for (int i = 0; i < daftar.size(); i++) {
            if (chatPerluAnggapGrup(daftar.get(i))) {
                return true;
            }
        }
        return false;
    }

    public static boolean chatAdalahGrup(String config) {
        if (config == null) {
            return false;
        }
        String c = config.trim();
        if (c.isEmpty() || c.startsWith("@")) {
            return false;
        }
        if (c.startsWith("+") && c.length() > 1) {
            c = c.substring(1).trim();
        }

        if (!angkaBulat(c)) {
            return false;
        }
        try {
            return Long.parseLong(c) < 0;
        } catch (NumberFormatException ignored) {
            return false;
        }
    }

    public static boolean chatPerluAnggapGrup(String config) {
        if (chatAdalahGrup(config)) {
            return true;
        }
        if (config == null) {
            return false;
        }
        String c = config.trim();
        if (c.isEmpty()) {
            return false;
        }
        if (c.startsWith("+") && c.length() > 1) {
            c = c.substring(1).trim();
            if (c.isEmpty()) {
                return false;
            }
        }
        String norm = c.startsWith("@") ? c.substring(1) : c;
        if (norm.isEmpty()) {
            return false;
        }

        if (!angkaBulat(norm)) {
            return true;
        }
        try {
            return Long.parseLong(norm) < 0;
        } catch (NumberFormatException ignored) {

            return true;
        }
    }

    public static boolean pesanSegar(long dateMs, long sekarang, long basiMs) {

        if (dateMs <= 0) {
            return false;
        }
        if (dateMs > sekarang + Math.max(0, basiMs)) {
            return false;
        }
        if (dateMs > sekarang) {
            return true;
        }
        return sekarang - dateMs <= basiMs;
    }

    public static boolean bolehIkutiRedirect(String asal, String lokasi) {
        if (lokasi == null || lokasi.isEmpty()) {
            return false;
        }
        String l = lokasi.trim();
        if (l.startsWith("//")) {

            return hostSama(asal, l.substring(2));
        }
        if (l.startsWith("/")) {
            return true;
        }
        int kol = l.indexOf(':');
        if (kol < 0) {
            return true;
        }
        return "https".equals(rendahAscii(l.substring(0, kol)));
    }

    public static boolean hostGithubAman(String host) {
        if (host == null) {
            return false;
        }

        String kupas = kupasHostPort(host.trim());
        if (kupas == null || kupas.isEmpty()) {
            return false;
        }
        String h = rendahAscii(normalisasiHost(kupas));
        if (h.isEmpty()) {
            return false;
        }
        if (h.equals("github.com") || h.equals("api.github.com")
                || h.equals("codeload.github.com")
                || h.equals("objects.githubusercontent.com")
                || h.equals("release-assets.githubusercontent.com")) {
            return true;
        }

        if (h.startsWith("github-production-release-asset-")
                && h.endsWith(".githubusercontent.com")) {
            return true;
        }
        return false;
    }

    public static boolean hostTelegramAman(String host) {
        if (host == null) {
            return false;
        }
        String kupas = kupasHostPort(host.trim());
        if (kupas == null || kupas.isEmpty()) {
            return false;
        }
        String h = rendahAscii(normalisasiHost(kupas));
        if (h.isEmpty()) {
            return false;
        }
        if (h.equals("telegram.org") || h.endsWith(".telegram.org")) {
            return true;
        }
        return h.equals("cdn-telegram.org") || h.endsWith(".cdn-telegram.org");
    }

    public static boolean bolehIkutiRedirectTelegram(String asal, String lokasi) {
        return bolehIkutiRedirectHost(asal, lokasi, true);
    }

    public static boolean bolehIkutiRedirectGithub(String asal, String lokasi) {
        return bolehIkutiRedirectHost(asal, lokasi, false);
    }

    private static boolean bolehIkutiRedirectHost(String asal, String lokasi, boolean telegram) {
        if (!bolehIkutiRedirect(asal, lokasi)) {
            return false;
        }
        if (lokasi == null) {
            return false;
        }
        String l = lokasi.trim();

        if (l.startsWith("/")) {
            return true;
        }
        int kol = l.indexOf(':');
        try {
            if (kol < 0) {
                String gabung = sambungRedirect(asal, l);
                String host = new java.net.URL(gabung).getHost();
                return telegram ? hostTelegramAman(host) : hostGithubAman(host);
            }
            if (!"https".equals(rendahAscii(l.substring(0, kol)))) {
                return false;
            }
            String host = new java.net.URL(l).getHost();
            return telegram ? hostTelegramAman(host) : hostGithubAman(host);
        } catch (Exception e) {
            return false;
        }
    }

    static boolean hostSama(String asal, String sisa) {
        if (asal == null || sisa == null || sisa.isEmpty()) {
            return false;
        }
        try {
            String hostAsal = new java.net.URL(asal.trim()).getHost();
            if (hostAsal == null || hostAsal.isEmpty()) {
                return false;
            }
            int ujung = sisa.length();
            for (int i = 0; i < sisa.length(); i++) {
                char c = sisa.charAt(i);
                if (c == '/' || c == '?' || c == '#') {
                    ujung = i;
                    break;
                }
            }
            String host = kupasHostPort(sisa.substring(0, ujung));
            return host != null && !host.isEmpty()
                    && host.equalsIgnoreCase(normalisasiHost(hostAsal));
        } catch (Exception e) {
            return false;
        }
    }

    static String kupasHostPort(String hostPort) {
        if (hostPort == null || hostPort.isEmpty()) {
            return null;
        }
        if (hostPort.charAt(0) == '[') {
            int tutup = hostPort.indexOf(']');
            if (tutup < 0) {
                return null;
            }
            String ekor = hostPort.substring(tutup + 1);
            if (!ekor.isEmpty() && !(ekor.charAt(0) == ':' && semuaDigit(ekor.substring(1)))) {
                return null;
            }
            String dalam = hostPort.substring(1, tutup);
            return dalam.isEmpty() ? null : dalam;
        }
        int titikDua = 0;
        for (int i = 0; i < hostPort.length(); i++) {
            if (hostPort.charAt(i) == ':') {
                titikDua++;
            }
        }
        if (titikDua > 1) {
            return hostPort;
        }
        if (titikDua == 1) {
            int i = hostPort.indexOf(':');
            String h = hostPort.substring(0, i);
            String port = hostPort.substring(i + 1);

            if (h.isEmpty() || port.isEmpty() || !semuaDigit(port)) {
                return null;
            }
            return h;
        }
        return hostPort;
    }

    static String normalisasiHost(String host) {
        if (host == null) {
            return "";
        }
        String h = host.trim();
        if (h.length() >= 2 && h.charAt(0) == '['
                && h.charAt(h.length() - 1) == ']') {
            return h.substring(1, h.length() - 1);
        }
        return h;
    }

    public static String sambungRedirect(String dasar, String lokasi) {
        if (lokasi == null) {
            return dasar;
        }
        String l = lokasi.trim();
        if (l.regionMatches(true, 0, "https://", 0, 8)) {
            return l;
        }

        if (l.regionMatches(true, 0, "http://", 0, 7)) {
            return dasar;
        }
        if (l.startsWith("//")) {

            try {
                java.net.URL u = new java.net.URL(dasar);
                return u.getProtocol() + ":" + l;
            } catch (Exception e) {
                return dasar;
            }
        }
        if (l.startsWith("/")) {
            try {
                java.net.URL u = new java.net.URL(dasar);
                return u.getProtocol() + "://" + u.getHost()
                        + (u.getPort() < 0 ? "" : ":" + u.getPort()) + l;
            } catch (Exception e) {

                return dasar;
            }
        }
        try {
            return new java.net.URL(new java.net.URL(dasar), l).toString();
        } catch (Exception e) {
            return dasar;
        }
    }

    public static boolean bolehHapusFile(File cacheDir, File filesDir, File target) {
        if (target == null) {
            return false;
        }
        try {
            String kanon = target.getCanonicalPath();
            if (cacheDir != null) {
                String c = cacheDir.getCanonicalPath();
                if (kanon.equals(c) || kanon.startsWith(c + File.separator)) {
                    return true;
                }
            }
            if (filesDir != null) {
                String f = filesDir.getCanonicalPath();
                if (kanon.equals(f) || kanon.startsWith(f + File.separator)) {
                    return true;
                }
            }
            return false;
        } catch (IOException e) {
            return false;
        }
    }

    public static long tambahUkuranUnzip(long total, long tambah, long batas,
            int jumlah, int batasJumlah) throws IOException {
        if (jumlah > batasJumlah) {
            throw new IOException("Terlalu banyak entri zip (" + jumlah + ").");
        }
        long hasil = total + Math.max(0, tambah);
        if (hasil > batas) {
            throw new IOException("Ukuran unzip melebihi batas " + batas + " byte.");
        }
        return hasil;
    }
}
