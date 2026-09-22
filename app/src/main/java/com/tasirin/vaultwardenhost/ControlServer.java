package com.tasirin.vaultwardenhost;

import android.content.Context;
import android.os.Environment;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/** Status web ringan di port terpisah dari vaultwarden: JSON status + log realtime
 *  via SSE. Di-port (versi read-only & minimal) dari HttpControlServer download manager. */
public final class ControlServer {

    public static volatile boolean running = false;
    public static volatile int listeningPort = 0;

    private static final int MAX_SSE_CLIENTS = 3;
    private static final AtomicInteger sseClients = new AtomicInteger();
    private static final int LOG_TAIL_CHARS = 20_000;
    // Batas DoS LAN: koneksi konkuren + ukuran header HTTP.
    private static final int MAX_CONNS = 12;
    private static final AtomicInteger conns = new AtomicInteger();
    private static final int MAX_HEADER_LINES = 64;
    private static final int MAX_HEADER_LINE = 8192;
    private static final int MAX_HEADER_TOTAL = 65536;
    // Cache JSON status 10 dtk: halaman polling tiap 2 dtk, isinya mahal
    // (jalan rekursif folder web-vault + baca /proc).
    private static volatile String jsonCache = null;
    private static volatile long jsonCacheAt = 0;
    private static final long JSON_CACHE_MS = 10_000;

    // Pool tetap (bukan thread baru per koneksi): halaman status polling tiap
    // 2 dtk sehingga thread churn boros. Statis agar tak bocor tiap restart.
    private static final ExecutorService POOL = Executors.newFixedThreadPool(6, r -> {
        Thread t = new Thread(r, "vw-status-conn");
        t.setDaemon(true);
        return t;
    });

    private final Context context;
    private ServerSocket serverSocket;
    private Thread acceptThread;
    private volatile boolean stop;

    public ControlServer(Context context) {
        this.context = context.getApplicationContext();
    }

    public boolean start(int port) {
        try {
            serverSocket = new ServerSocket(port);
            listeningPort = serverSocket.getLocalPort();
            jsonCache = null;
            jsonCacheAt = 0;
            stop = false;
            acceptThread = new Thread(this::acceptLoop, "vw-status-web");
            acceptThread.setDaemon(true);
            acceptThread.start();
            running = true;
            return true;
        } catch (Exception e) {
            running = false;
            listeningPort = 0;
            return false;
        }
    }

    public void stop() {
        stop = true;
        jsonCache = null;
        jsonCacheAt = 0;
        try {
            if (serverSocket != null) {
                serverSocket.close();
            }
        } catch (Exception ignored) {
        }
        running = false;
    }

    private void acceptLoop() {
        while (!stop) {
            try {
                Socket s = serverSocket.accept();
                try {
                    POOL.execute(() -> handle(s));
                } catch (Exception e) {
                    try {
                        s.close();
                    } catch (Exception ignored) {
                    }
                }
            } catch (Exception e) {
                if (stop) {
                    break;
                }
            }
        }
    }

    private void handle(Socket s) {
        if (conns.incrementAndGet() > MAX_CONNS) {
            try {
                respond(s, 503, "text/plain; charset=utf-8", "Terlalu banyak koneksi");
            } catch (Exception ignored) {
            }
            conns.decrementAndGet();
            return;
        }
        try {
            s.setSoTimeout(8000);
            BufferedReader in = new BufferedReader(
                    new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
            String line = in.readLine();
            if (line == null || line.length() > MAX_HEADER_LINE) {
                return;
            }
            // Tanpa regex: baris request hanya "METHOD TARGET ...".
            int sp1 = line.indexOf(' ');
            if (sp1 <= 0) {
                return;
            }
            int sp2 = line.indexOf(' ', sp1 + 1);
            String method = line.substring(0, sp1);
            String target = sp2 < 0 ? line.substring(sp1 + 1) : line.substring(sp1 + 1, sp2);
            if (target.isEmpty()) {
                return;
            }
            // Baca header dengan batas (cegah slowloris / header raksasa).
            int headerTotal = line.length();
            String authHeader = "";
            for (int i = 0; i < MAX_HEADER_LINES; i++) {
                String h = in.readLine();
                if (h == null || h.isEmpty()) {
                    break;
                }
                headerTotal += h.length();
                if (h.length() > MAX_HEADER_LINE || headerTotal > MAX_HEADER_TOTAL) {
                    respond(s, 431, "text/plain; charset=utf-8", "Header terlalu besar");
                    return;
                }
                if (h.regionMatches(true, 0, "Authorization:", 0, 14)) {
                    authHeader = h.substring(14).trim();
                }
            }
            if (!"GET".equals(method)) {
                respond(s, 405, "text/plain; charset=utf-8", "Method not allowed");
                return;
            }
            String path = target;
            String query = "";
            int q = path.indexOf('?');
            if (q >= 0) {
                query = path.substring(q + 1);
                path = path.substring(0, q);
            }
            if (path.startsWith("/api/") && !checkToken(query, authHeader)) {
                respond(s, 403, "text/plain; charset=utf-8",
                        "Akses ditolak: admin token dibutuhkan (?token= / Authorization: Bearer).");
                return;
            }
            switch (path) {
                case "/api/status":
                    respond(s, 200, "application/json; charset=utf-8", statusJson());
                    break;
                case "/api/log":
                    respond(s, 200, "text/plain; charset=utf-8", logTail());
                    break;
                case "/api/events":
                    sse(s);
                    break;
                default:
                    respond(s, 200, "text/html; charset=utf-8", PAGE);
            }
        } catch (Exception ignored) {
        } finally {
            conns.decrementAndGet();
            try {
                s.close();
            } catch (Exception ignored) {
            }
        }
    }

    /** Banding token constant-time; kedua sisi di-trim agar salinan token
     *  ber-spasi tak ditolak. Murni agar bisa unit test. */
    static boolean tokenCocok(String perlu, String dapat) {
        if (perlu == null || dapat == null) {
            return false;
        }
        String a = perlu.trim();
        if (a.isEmpty()) {
            return false;
        }
        return MessageDigest.isEqual(
                a.getBytes(StandardCharsets.UTF_8),
                dapat.trim().getBytes(StandardCharsets.UTF_8));
    }

    /** True bila query/header membawa admin token yang benar (atau tidak ada token
     *  yang dikonfigurasi - status web tetap terbuka + tercatat di log & JSON). */
    boolean checkToken(String query) {
        return checkToken(query, "");
    }

    private boolean checkToken(String query, String authHeader) {
        String need;
        try {
            need = context.getSharedPreferences(ServerService.PREFS, Context.MODE_PRIVATE)
                    .getString(ServerService.KEY_ADMIN_TOKEN, "");
        } catch (Exception e) {
            return true;
        }
        if (need == null || need.trim().isEmpty()) {
            return true;
        }
        if (authHeader != null && authHeader.regionMatches(true, 0, "Bearer ", 0, 7)) {
            if (tokenCocok(need, authHeader.substring(7).trim())) {
                return true;
            }
        }
        // Tanpa regex: pindai pasangan kunci=nilai satu per satu.
        int start = 0;
        while (start <= query.length()) {
            int amp = query.indexOf('&', start);
            int end = amp < 0 ? query.length() : amp;
            int eq = query.indexOf('=', start);
            if (eq > start && eq < end && query.regionMatches(start, "token", 0, eq - start)
                    && eq - start == 5) {
                try {
                    String got = java.net.URLDecoder.decode(
                            query.substring(eq + 1, end), "UTF-8");
                    // Banding constant-time: lawan timing attack di LAN.
                    if (tokenCocok(need, got)) {
                        return true;
                    }
                } catch (Exception ignored) {
                }
            }
            if (amp < 0) {
                break;
            }
            start = amp + 1;
        }
        return false;
    }

    private void respond(Socket s, int code, String type, String body) throws Exception {
        byte[] data = body.getBytes(StandardCharsets.UTF_8);
        OutputStream out = s.getOutputStream();
        String status;
        if (code == 200) {
            status = "OK";
        } else if (code == 403) {
            status = "Forbidden";
        } else if (code == 405) {
            status = "Method Not Allowed";
        } else if (code == 431) {
            status = "Request Header Fields Too Large";
        } else if (code == 503) {
            status = "Service Unavailable";
        } else {
            status = "Error";
        }
        out.write(("HTTP/1.1 " + code + " " + status + "\r\n").getBytes(StandardCharsets.UTF_8));
        out.write(("Content-Type: " + type + "\r\n").getBytes(StandardCharsets.UTF_8));
        out.write(("Content-Length: " + data.length + "\r\n").getBytes(StandardCharsets.UTF_8));
        out.write("Connection: close\r\n\r\n".getBytes(StandardCharsets.UTF_8));
        out.write(data);
        out.flush();
    }

    private boolean adaAdminToken() {
        try {
            String at = context.getSharedPreferences(ServerService.PREFS, Context.MODE_PRIVATE)
                    .getString(ServerService.KEY_ADMIN_TOKEN, "");
            return at != null && !at.trim().isEmpty();
        } catch (Exception e) {
            return false;
        }
    }

    // getPackageInfo lama sengaja agar satu jalur kode untuk API 21-32.
    @SuppressWarnings("deprecation")
    private String statusJson() {
        long now = System.currentTimeMillis();
        String cached = jsonCache;
        if (cached != null && now - jsonCacheAt < JSON_CACHE_MS) {
            return cached;
        }
        try {
            JSONObject o = new JSONObject();
            o.put("running", ServerService.running);
            o.put("status", ServerService.statusLine == null ? "" : ServerService.statusLine);
            try {
                android.content.pm.PackageInfo info =
                        context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
                o.put("appVersion", info.versionName);
                o.put("build", info.versionCode);
            } catch (Exception ignored) {
                o.put("appVersion", "?");
                o.put("build", 0);
            }
            o.put("binaryVersion", ServerService.binaryVersion == null ? "" : ServerService.binaryVersion);
            String wv = Updater.webVaultFromVersion(context);
            o.put("wvVersion", wv == null ? "" : wv);
            o.put("port", ServerService.runningPort == null ? "" : ServerService.runningPort);
            o.put("https", ServerService.runningHttps);
            o.put("protected", adaAdminToken());
            o.put("dataDir", ServerService.runningDataDir == null ? "" : ServerService.runningDataDir);
            long up = ServerService.uptimeMs();
            o.put("uptimeMs", up);
            o.put("uptimeHuman", up > 0 ? TgBot.durationText(up) : "");
            long rss = ServerService.processRssKb();
            o.put("ramKb", rss > 0 ? rss : -1);
            o.put("ramHuman", rss > 0 ? TgBackup.humanBytes(rss * 1024) : "");
            o.put("ctrlPort", listeningPort);
            String dir = ServerService.runningDataDir == null || ServerService.runningDataDir.isEmpty()
                    ? Environment.getDataDirectory().getAbsolutePath()
                    : ServerService.runningDataDir;
            long free = TgBackup.freeBytes(dir);
            o.put("freeBytes", free);
            o.put("freeHuman", free < 0 ? "" : TgBackup.humanBytes(free));

            File dbFile = new File(dir, "db.sqlite3");
            long dbBytes = dbFile.exists() ? dbFile.length() : 0;
            o.put("dbBytes", dbBytes);
            o.put("dbHuman", dbBytes > 0 ? TgBackup.humanBytes(dbBytes) : "");
            File wvDir = new File(dir, "web-vault");
            long wvBytes = TgBackup.folderBytesCached(wvDir);
            o.put("wvBytes", wvBytes);
            o.put("wvHuman", wvBytes > 0 ? TgBackup.humanBytes(wvBytes) : "");
            File binFile = new File(context.getFilesDir(),
                    "bin/vaultwarden-" + ServerService.ABI);
            long binBytes = binFile.exists() ? binFile.length() : 0;
            o.put("binaryBytes", binBytes);
            o.put("binaryHuman", binBytes > 0 ? TgBackup.humanBytes(binBytes) : "");
            File backupDir = new File(dir, "backups");
            File[] backups = backupDir.listFiles();
            o.put("backupCount", backups == null ? 0 : backups.length);
            String restarts = ServerService.restartSummary();
            o.put("restartHistory", restarts == null ? "" : restarts);
            String json = o.toString();
            jsonCache = json;
            jsonCacheAt = System.currentTimeMillis();
            return json;
        } catch (Exception e) {
            return "{\"error\":\"" + escJson(e.getMessage()) + "\"}";
        }
    }

    private static final char[] HEX = "0123456789abcdef".toCharArray();

    /** Escape string untuk sisipan JSON manual (tanpa library tambahan). */
    private static String escJson(String v) {
        if (v == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(v.length());
        for (int i = 0; i < v.length(); i++) {
            char c = v.charAt(i);
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:
                    if (c < 0x20) {
                        sb.append("\\u");
                        sb.append(HEX[(c >> 12) & 0xF]);
                        sb.append(HEX[(c >> 8) & 0xF]);
                        sb.append(HEX[(c >> 4) & 0xF]);
                        sb.append(HEX[c & 0xF]);
                    } else {
                        sb.append(c);
                    }
            }
        }
        return sb.toString();
    }

    private static String logTail() {
        return ServerService.logTailChars(LOG_TAIL_CHARS);
    }

    /** SSE: kirim seluruh log lalu delta tiap detik + heartbeat tiap 15 dtk. */
    private void sse(Socket s) throws Exception {
        if (sseClients.incrementAndGet() > MAX_SSE_CLIENTS) {
            sseClients.decrementAndGet();
            respond(s, 503, "text/plain; charset=utf-8", "Terlalu banyak client log");
            return;
        }
        try {
            OutputStream out = new java.io.BufferedOutputStream(
                    s.getOutputStream(), 8192);
            out.write("HTTP/1.1 200 OK\r\n".getBytes(StandardCharsets.UTF_8));
            out.write("Content-Type: text/event-stream; charset=utf-8\r\n".getBytes(StandardCharsets.UTF_8));
            out.write("Cache-Control: no-cache\r\n".getBytes(StandardCharsets.UTF_8));
            out.write("Connection: close\r\n\r\n".getBytes(StandardCharsets.UTF_8));
            out.write("retry: 1000\n\n".getBytes(StandardCharsets.UTF_8));
            out.flush();

            int sent = -1;
            long lastWrite = System.currentTimeMillis();
            while (!stop && !s.isClosed()) {
                String text = null;
                int len;
                synchronized (ServerService.logBuffer) {
                    len = ServerService.logBuffer.length();
                    if (sent < 0 || len < sent) {
                        // Klien baru cukup menerima ekor log (20 KB), bukan
                        // salinan seluruh buffer (≤300 KB) per koneksi.
                        text = ServerService.logTailChars(LOG_TAIL_CHARS);
                    } else if (len > sent) {
                        text = ServerService.logBuffer.substring(sent, len);
                    }
                }
                if (text != null) {
                    sent = len;
                    kirimSse(out, text);
                    lastWrite = System.currentTimeMillis();
                    out.flush();
                } else {
                    long now = System.currentTimeMillis();
                    if (now - lastWrite >= 15_000) {
                        out.write(": ping\n\n".getBytes(StandardCharsets.UTF_8));
                        lastWrite = now;
                        out.flush();
                    }
                }
                Thread.sleep(1000);
            }
        } finally {
            sseClients.decrementAndGet();
        }
    }

    private static final byte[] SSE_AWAL =
            "data: ".getBytes(StandardCharsets.UTF_8);
    private static final byte[] SSE_AKHIR = "\n\n".getBytes(StandardCharsets.UTF_8);

    /** Tulis blok SSE tanpa split/concat per baris (hemat alokasi per detik per klien). */
    private static void kirimSse(java.io.OutputStream out, String text) throws Exception {
        int start = 0;
        int n = text.length();
        for (int i = 0; i <= n; i++) {
            if (i == n || text.charAt(i) == '\n') {
                int end = i;
                while (end > start && text.charAt(end - 1) == '\r') {
                    end--;
                }
                if (end > start && !ServerService.rentangKosong(text, start, end)) {
                    out.write(SSE_AWAL);
                    out.write(text.substring(start, end).getBytes(StandardCharsets.UTF_8));
                    out.write(SSE_AKHIR);
                }
                start = i + 1;
            }
        }
    }

    // ─── Halaman status web (HTML+JS ringan, tema gelap) ────────────────

    private static final String PAGE = """
            <!DOCTYPE html>
            <html lang="id">
            <head>
            <meta charset="utf-8">
            <meta name="viewport" content="width=device-width, initial-scale=1">
            <title>Tasirin Vaultwarden Host - Status Web</title>
            <style>
              :root { --bg:#0f172a; --card:#1e293b; --line:#334155; --text:#e2e8f0; --muted:#94a3b8; --ok:#4ade80; --bad:#f87171; --acc:#ffb74d; }
              * { box-sizing:border-box; }
              body { margin:0; background:var(--bg); color:var(--text); font-family:system-ui,Roboto,sans-serif; }
              .wrap { max-width:960px; margin:0 auto; padding:16px; }
              h1 { font-size:18px; margin:0 0 4px; }
              .sub { color:var(--muted); font-size:12px; margin-bottom:14px; }
              .grid { display:grid; grid-template-columns:repeat(auto-fit,minmax(150px,1fr)); gap:8px; margin-bottom:14px; }
              .card { background:var(--card); border:1px solid var(--line); border-radius:12px; padding:10px 12px; }
              .card .k { color:var(--muted); font-size:11px; text-transform:uppercase; letter-spacing:.04em; }
              .card .v { font-size:14px; margin-top:2px; word-break:break-word; }
              .pill { display:inline-block; padding:2px 10px; border-radius:999px; font-size:12px; font-weight:600; }
              .on { background:rgba(74,222,128,.15); color:var(--ok); }
              .off { background:rgba(248,113,113,.15); color:var(--bad); }
              .bar { display:flex; gap:8px; align-items:center; margin-bottom:8px; flex-wrap:wrap; }
              .bar input[type=text] { flex:1; min-width:160px; background:var(--card); color:var(--text); border:1px solid var(--line); border-radius:8px; padding:8px 10px; }
              label { color:var(--muted); font-size:12px; display:flex; align-items:center; gap:6px; }
              .count { color:var(--muted); font-size:12px; }
              pre { background:var(--card); border:1px solid var(--line); border-radius:12px; padding:10px; height:46vh; overflow:auto; font-size:12px; line-height:1.45; margin:0; white-space:pre-wrap; word-break:break-all; }
            </style>
            </head>
            <body>
            <div class="wrap">
              <h1>Tasirin Vaultwarden Host &middot; Status Web</h1>
              <div class="sub">Log realtime server &mdash; buka dari HP/PC di jaringan yang sama</div>
              <div class="grid">
                <div class="card"><div class="k">Status</div><div class="v"><span id="st" class="pill off">Memuat&hellip;</span></div></div>
                <div class="card"><div class="k">Versi App</div><div class="v" id="appVer">-</div></div>
                <div class="card"><div class="k">Binary</div><div class="v" id="binVer">-</div></div>
                <div class="card"><div class="k">Port Server</div><div class="v" id="port">-</div></div>
                <div class="card"><div class="k">HTTPS</div><div class="v" id="https">-</div></div>
                <div class="card"><div class="k">Uptime</div><div class="v" id="uptime">-</div></div>
                <div class="card"><div class="k">Storage</div><div class="v" id="storage">-</div></div>
                <div class="card"><div class="k">Web Vault</div><div class="v" id="wvVer">-</div></div>
                <div class="card"><div class="k">DB</div><div class="v" id="dbSize">-</div></div>
                <div class="card"><div class="k">Restart</div><div class="v" id="restartInfo">-</div></div>
                <div class="card"><div class="k">Folder Data</div><div class="v" id="dataDir">-</div></div>
              </div>
              <div class="bar">
                <input id="q" type="text" placeholder="Cari di log...">
                <label><input id="auto" type="checkbox"> Auto-scroll</label>
                <span class="count" id="count">Baris: 0</span>
              </div>
              <pre id="log"></pre>
            </div>
            <script>
            var QS = location.search || "";
            var buf = ""; var lastKey = null;
            function esc(s){ return s.replace(/[&<>]/g, function(c){ return {"&":"&amp;","<":"&lt;",">":"&gt;"}[c]; }); }
            function render(){
              var q = document.getElementById("q").value.toLowerCase();
              var auto = document.getElementById("auto").checked;
              var pre = document.getElementById("log");
              var lines = buf.split("\\n");
              var shown = q ? lines.filter(function(l){ return l.toLowerCase().indexOf(q) >= 0; }) : lines;
              var text = shown.join("\\n");
              var key = text + "\\u0000" + q;
              if (key === lastKey) return;
              lastKey = key;
              var scroll = pre.scrollTop;
              pre.innerHTML = esc(text).split("\\n").map(function(l){
                if (/GAGAL|ERROR|FAILED/i.test(l)) return "<span style='color:#f87171'>"+esc(l)+"</span>";
                return esc(l);
              }).join("\\n");
              document.getElementById("count").textContent = "Baris: " + shown.length;
              if (auto) { pre.scrollTop = pre.scrollHeight; }
              else { pre.scrollTop = scroll; }
            }
            function pollLog(){
              fetch("/api/log" + QS).then(function(r){ return r.text(); }).then(function(t){
                buf = t; render();
              }).catch(function(){});
              setTimeout(pollLog, 1000);
            }
            function fmtUptime(ms){
              if (!ms) return "-";
              var s = Math.floor(ms/1000), h = Math.floor(s/3600), m = Math.floor((s%3600)/60), sec = s%60;
              return h+"j "+m+"m "+sec+"d";
            }
            function pollStatus(){
              fetch("/api/status" + QS).then(function(r){ return r.json(); }).then(function(j){
                var st = document.getElementById("st");
                st.textContent = j.running ? "Berjalan" : "Berhenti";
                st.className = "pill " + (j.running ? "on" : "off");
                document.getElementById("appVer").textContent = j.appVersion + " (build " + j.build + ")";
                document.getElementById("binVer").textContent = j.binaryVersion || "-";
                document.getElementById("port").textContent = j.port || "-";
                document.getElementById("https").textContent = j.https ? "Ya" : "Tidak";
                document.getElementById("uptime").textContent = fmtUptime(j.uptimeMs);
                document.getElementById("storage").textContent = j.freeHuman + " bebas";
                document.getElementById("wvVer").textContent = (j.wvVersion || "-")
                        + (j.wvHuman ? " \u00B7 " + j.wvHuman : "");
                document.getElementById("dbSize").textContent = j.dbHuman
                        || (j.dbBytes > 0 ? "-" : "belum ada");
                document.getElementById("restartInfo").textContent = j.restartHistory || "-";
                document.getElementById("dataDir").textContent = j.dataDir || "-";
              }).catch(function(){});
              setTimeout(pollStatus, 2000);
            }
            if (window.EventSource) {
              var es = new EventSource("/api/events" + QS);
              es.onmessage = function(e){ buf += e.data + "\\n"; if (buf.length > 60000) buf = buf.slice(-60000); render(); };
              es.onerror = function(){ es.close(); pollLog(); };
            } else {
              pollLog();
            }
            pollStatus();
            document.getElementById("q").addEventListener("input", render);
            document.getElementById("auto").addEventListener("change", render);
            </script>
            </body>
            </html>
            """;
}
