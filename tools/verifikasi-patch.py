#!/usr/bin/env python3
import argparse
import os
import re
import subprocess
import sys
import xml.etree.ElementTree as ET

AKAR = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
MAIN = os.path.join(AKAR, "app", "src", "main")
UJI = os.path.join(AKAR, "app", "src", "test")
KATA_KUNCI_JAVA = {
    "if", "for", "while", "switch", "catch", "return", "new", "throw",
    "else", "do", "try", "synchronized", "assert", "case",
}

def muat_bekas_hapusan():
    nama = set()
    try:
        with open(os.path.join(AKAR, "tools", "bekas-hapusan.txt"),
                  encoding="utf-8") as f:
            for baris in f:
                baris = baris.strip()
                if baris and not baris.startswith("#"):
                    nama.add(baris.split()[0])
    except OSError:
        return None
    return nama

BEKAS_HAPUSAN = muat_bekas_hapusan()

def cek_daftar_hapusan(gagal):
    if BEKAS_HAPUSAN is None:
        gagal.append("tools/bekas-hapusan.txt tak terbaca — penjagaan nama "
                     "metode terhapus tidak bisa dijalankan")
    elif not BEKAS_HAPUSAN:
        gagal.append("tools/bekas-hapusan.txt kosong — daftar nama metode "
                     "terhapus wajib berisi")

def di_github_actions():
    return os.environ.get("GITHUB_ACTIONS") == "true"

def _lolos_properti(s):
    s = str(s)
    for lama, baru in (("%", "%25"), ("\r", "%0D"), ("\n", "%0A"),
                       (":", "%3A"), (",", "%2C")):
        s = s.replace(lama, baru)
    return s

def _lolos_pesan(s):
    s = str(s)
    for lama, baru in (("%", "%25"), ("\r", "%0D"), ("\n", "%0A")):
        s = s.replace(lama, baru)
    return s

def anotasi(jenis, pesan):
    if not di_github_actions():
        return
    m = re.search(r"([A-Za-z0-9_./-]+\.(?:java|xml|kts|yml|sh|py|txt)):(\d+)",
                  str(pesan))
    if m:
        print("::%s file=%s,line=%s::%s"
              % (jenis, _lolos_properti(m.group(1)), m.group(2),
                 _lolos_pesan(pesan)))
    else:
        print("::%s::%s" % (jenis, _lolos_pesan(pesan)))

OVERRIDE_AMAN = {
    "onCreate", "onStart", "onResume", "onPause", "onStop", "onDestroy",
    "onReceive", "onHandleIntent", "onBind", "onUnbind", "onUpgrade",
    "onAlarm", "onBoot", "onClick", "onFocusChange",
}

def jalan(*args):
    try:
        hasil = subprocess.run(
            list(args), cwd=AKAR, capture_output=True, text=True, timeout=60)
    except Exception:
        return None
    return hasil.stdout if hasil.returncode == 0 else None

def rentang_cadangan():
    if jalan("git", "rev-parse", "--verify", "HEAD~1") is None:
        return None
    return ("HEAD~1", "...", "HEAD")

def rentang_normal(rentang):
    if not rentang:
        return None
    for pemisah in ("...", ".."):
        if pemisah in rentang:
            a, b = rentang.split(pemisah, 1)
            if not a or set(a) == {"0"}:
                if not b:
                    return None
                a = b + "~1"
            return a, pemisah, b
    return None

def daftar_berkas_berubah(rentang):
    r = rentang_normal(rentang)
    if r is None:
        return None
    out = jalan("git", "diff", "--name-only", r[0] + r[1] + r[2])
    if out is not None:
        return [x for x in out.splitlines() if x]
    return None

def nama_tak_lacak():
    out = jalan("git", "ls-files", "--others", "--exclude-standard")
    return out.splitlines() if out else []

def baca(path):
    try:
        with open(os.path.join(AKAR, path), encoding="utf-8", errors="replace") as f:
            return f.read()
    except OSError:
        return ""

def jalan_repo(rel, ext=(".java",)):
    for akar, dirs, berkas in os.walk(os.path.join(AKAR, rel)):

        dirs[:] = [d for d in dirs if d not in ("build", ".gradle", ".git")]
        for nama in berkas:
            if ext is None or nama.endswith(ext):
                yield os.path.relpath(os.path.join(akar, nama), AKAR)

def himpun_definisi():
    definisi = {}
    acuan = {}

    def rapikan(jenis, nama):
        return nama.replace(".", "_") if jenis == "style" else nama

    pola_def = re.compile(r'<(style|string|color|dimen|plurals)\s+name="([^"]+)"')
    pola_array = re.compile(r'<(string-array|integer-array|array)\s+name="([^"]+)"')

    def tambah(kunci, nama):
        definisi.setdefault(kunci, set()).add(rapikan(kunci, nama))

    akar_res = os.path.join(MAIN, "res")
    for path in jalan_repo(os.path.join("app", "src", "main", "res"), ext=(".xml",)):
        isi = baca(path)
        for jenis, nama in pola_def.findall(isi):
            tambah(jenis, nama)
        for jenis, nama in pola_array.findall(isi):
            tambah("array", nama)
        for nama in re.findall(r'@\+id/([A-Za-z0-9_]+)', isi):
            tambah("id", nama)

    subdir_res = sorted(os.listdir(akar_res)) if os.path.isdir(akar_res) else []
    for nama_dir in subdir_res:
        if nama_dir.startswith("values"):
            continue
        tipe = nama_dir.split("-")[0]
        wadah = os.path.join(akar_res, nama_dir)
        if not os.path.isdir(wadah):
            continue
        for akar, _, berkas in os.walk(wadah):
            for nama in berkas:
                tambah(tipe, os.path.splitext(nama)[0])

    pola_pakai = [
        ("style", r"@style/([A-Za-z0-9_.]+)", r"(?<!android\.)R\.style\.([A-Za-z0-9_]+)"),
        ("string", r"@string/([A-Za-z0-9_]+)", r"(?<!android\.)R\.string\.([A-Za-z0-9_]+)"),
        ("color", r"@color/([A-Za-z0-9_]+)", r"(?<!android\.)R\.color\.([A-Za-z0-9_]+)"),
        ("dimen", r"@dimen/([A-Za-z0-9_]+)", r"(?<!android\.)R\.dimen\.([A-Za-z0-9_]+)"),
        ("array", r"@array/([A-Za-z0-9_]+)", r"(?<!android\.)R\.array\.([A-Za-z0-9_]+)"),
        ("drawable", r"@drawable/([A-Za-z0-9_]+)", r"(?<!android\.)R\.drawable\.([A-Za-z0-9_]+)"),
        ("layout", None, r"(?<!android\.)R\.layout\.([A-Za-z0-9_]+)"),
        ("id", r"@id/([A-Za-z0-9_]+)", r"(?<!android\.)R\.id\.([A-Za-z0-9_]+)"),
        ("plurals", r"@plurals/([A-Za-z0-9_]+)", r"(?<!android\.)R\.plurals\.([A-Za-z0-9_]+)"),
    ]
    pola_generik_xml = re.compile(r"@([a-z]+)/([A-Za-z0-9_.]+)")
    pola_generik_r = re.compile(r"(?<!android\.)R\.([a-z]+)\.([A-Za-z0-9_]+)")
    pola_android = re.compile(r"@android:[a-z]+/[A-Za-z0-9_.]+")
    pemindai = [p for p in jalan_repo("app", ext=(".java", ".xml",))]
    pemindai.append("app/src/main/AndroidManifest.xml")
    for path in pemindai:
        isi = baca(path)
        if not isi:
            continue
        isi = pola_android.sub("", isi)
        for jenis, pola_xml, pola_r in pola_pakai:
            for pola in (pola_xml, pola_r):
                if pola:
                    for nama in re.findall(pola, isi):
                        acuan.setdefault(jenis, set()).add(rapikan(jenis, nama))
        for tipe, nama in pola_generik_xml.findall(isi):
            acuan.setdefault(tipe, set()).add(rapikan(tipe, nama))
        for tipe, nama in pola_generik_r.findall(isi):
            acuan.setdefault(tipe, set()).add(rapikan(tipe, nama))
    return definisi, acuan

def parse_xml_aman(path):
    try:
        ET.parse(os.path.join(AKAR, path))
        return None
    except ET.ParseError as e:
        return ("rusak", "%s (%s)" % (path, e))
    except OSError as e:
        return ("tak terbaca", "%s (%s)" % (path, e))

def cek_xml(gagal):
    akar_res = os.path.join(MAIN, "res")

    if not os.path.isdir(akar_res):
        gagal.append("Folder resource hilang: app/src/main/res")
    temuan = {"rusak": [], "tak terbaca": []}
    for path in jalan_repo(os.path.join("app", "src", "main", "res"), ext=(".xml",)):
        galat = parse_xml_aman(path)
        if galat:
            temuan[galat[0]].append(galat[1])
    galat = parse_xml_aman("app/src/main/AndroidManifest.xml")
    if galat:
        temuan[galat[0]].append(galat[1])
    for jenis in ("rusak", "tak terbaca"):
        if temuan[jenis]:
            gagal.append("XML %s: %s" % (jenis, ", ".join(temuan[jenis])))

def cek_acuan_yatim(gagal):
    if not os.path.isdir(os.path.join(MAIN, "res")):
        return
    definisi, acuan = himpun_definisi()
    for jenis, dipakai in sorted(acuan.items()):
        ada = definisi.get(jenis, set())
        yatim = sorted(n for n in dipakai if n not in ada)
        if yatim:
            gagal.append("Acuan %s yatim (dipakai tapi tak terdefinisi): %s"
                         % (jenis, ", ".join(yatim[:10])))

def namespace_aplikasi():
    tetap = baca("app/build.gradle.kts")
    cocok = re.search(r'namespace\s*=\s*"([^"]+)"', tetap)
    return cocok.group(1) if cocok else ""

def cek_manifest(gagal):
    try:
        pohon = ET.parse(os.path.join(AKAR, "app/src/main/AndroidManifest.xml"))
    except (ET.ParseError, OSError):
        return
    ns = namespace_aplikasi()
    for el in pohon.getroot().iter():
        nama = el.get("{http://schemas.android.com/apk/res/android}name", "")
        if not nama or el.tag not in (
                "activity", "service", "receiver", "provider"):
            continue
        if not nama.startswith(".") and not nama.startswith(ns):
            continue
        rel = nama[1:] if nama.startswith(".") else nama[len(ns) + 1:]
        berkas = os.path.join(
            MAIN, "java", *ns.split("."), *rel.split(".")) + ".java"
        if not os.path.isfile(berkas):
            gagal.append("Komponen manifest tanpa kelas: %s (hapus %s.java?)"
                         % (nama, rel.replace(".", "/")))

def cek_duplikat_definisi(gagal):
    pola = re.compile(r'<(style|string|color|dimen|plurals|string-array|integer-array|array)\s+name="([^"]+)"')
    for path in jalan_repo(os.path.join("app", "src", "main", "res"), ext=(".xml",)):
        if "/values" not in path.replace(os.sep, "/"):
            continue
        hitung = {}
        for jenis, nama in pola.findall(baca(path)):
            kunci = jenis + "/" + nama
            hitung[kunci] = hitung.get(kunci, 0) + 1
        ganda = sorted(k for k, n in hitung.items() if n > 1)
        if ganda:
            gagal.append("Definisi ganda di %s: %s" % (path, ", ".join(ganda[:5])))

def berkas_dihapus(rentang, diff_biasa):
    r = rentang_normal(rentang)
    if r is not None:
        out = jalan("git", "diff", "--name-status", r[0] + r[1] + r[2])
        if out is None:
            return []
        return [x.split(None, 1)[1] for x in out.splitlines()
                if x.startswith("D")]
    out = jalan("git", "diff", "--name-status")
    return [x.split(None, 1)[1] for x in (out or "").splitlines() if x.startswith("D")]

def disebut_kata(nama, lewat=()):
    pola = re.compile(r"\b%s\b" % re.escape(nama))
    temu = []
    for path in jalan_repo("app", ext=(".java", ".xml")):
        if path in lewat:
            continue
        for i, baris in enumerate(baca(path).splitlines(), 1):
            if pola.search(baris):
                temu.append("%s:%d" % (path, i))
                if len(temu) >= 6:
                    return temu
    return temu

def cek_berkas_java_dihapus(gagal, hapus):
    for path in hapus:
        if not (path.startswith("app/src/main/java/") and path.endswith(".java")):
            continue
        kelas = os.path.splitext(os.path.basename(path))[0]
        siapa = disebut_kata(kelas, lewat=[path])
        if siapa:
            gagal.append("Berkas %s dihapus tapi %s masih disebut: %s"
                         % (path, kelas, ", ".join(siapa)))

def tambah_java(diff):
    path = ""
    for baris in diff.splitlines():
        if baris.startswith("diff --git "):
            bagian = baris.split()
            path = bagian[-1][2:] if len(bagian) >= 4 else ""
        elif baris.startswith("+") and not baris.startswith("+++"):
            if path.endswith(".java"):
                yield path, baris[1:]

def hapus_java(diff):
    path = ""
    for baris in diff.splitlines():
        if baris.startswith("diff --git "):
            bagian = baris.split()
            path = bagian[-1][2:] if len(bagian) >= 4 else ""
        elif baris.startswith("-") and not baris.startswith("---"):
            if path.endswith(".java"):
                yield path, baris[1:]

def kupas_java(isi):
    petik_ganda = chr(34)
    petik_satu = chr(39)
    garis_miring_balik = chr(92)
    kutip_tiga = petik_ganda * 3
    keluar = []
    i, n = 0, len(isi)
    while i < n:
        c = isi[i]
        dua = isi[i:i + 2]
        if dua == '//':
            while i < n and isi[i] != chr(10):
                i += 1
        elif dua == '/*':
            i += 2
            while i < n and isi[i:i + 2] != '*/':
                i += 1
            i += 2
        elif isi[i:i + 3] == kutip_tiga:
            i += 3
            while i < n:
                if isi[i] == garis_miring_balik:
                    i += 2
                    continue
                if isi[i:i + 3] == kutip_tiga:
                    i += 3
                    break
                i += 1
        elif c == petik_ganda or c == petik_satu:
            kutip = c
            i += 1
            while i < n:
                if isi[i] == garis_miring_balik:
                    i += 2
                    continue
                if isi[i] == kutip:
                    i += 1
                    break
                if kutip == petik_ganda and isi[i] == chr(10):
                    break
                i += 1
        else:
            keluar.append(c)
            i += 1
    return "".join(keluar)

def cek_kurung_java(gagal, berubah):
    for path in berubah:
        if not path.endswith(".java"):
            continue
        penuh = os.path.join(AKAR, path)
        if not os.path.isfile(penuh):
            continue
        isi = kupas_java(baca(path))
        for buka, tutup, nama in (("{", "}", "kurawal"), ("(", ")", "lengkung"), ("[", "]", "siku")):
            selisih = isi.count(buka) - isi.count(tutup)
            if selisih:
                gagal.append("Kurung %s tak seimbang di %s (%+d) — kompilasi pasti gagal"
                             % (nama, path, selisih))
                break

POLA_BOROS = [
    ("String.format(", "Formatter+Locale mahal di ART lama; pakai concat/manual"),
    ("new SimpleDateFormat(", "buat sekali per thread (ThreadLocal bersama)"),
    ("Calendar.getInstance(", "hitung manual tanpa Calendar berat"),
    (".split(", "kompilasi Pattern tiap panggil; pecah manual/indexOf"),
    ("new Thread(", "pakai pool bersama (Util.jalankanBg/Lama) hemat stack 1 MB"),
    ("System.out.println", "pakai log/Toast, bukan stdout"),
    (".printStackTrace()", "telan/abaikan eksplisit + catat log"),
]

def cek_pola_boros(peringatan, diff):
    tampil = set()
    for _, isi in tambah_java(diff):
        for pola, saran in POLA_BOROS:
            if pola in isi and pola not in tampil:
                if pola == ".split(" and "matcher" in isi.lower():
                    continue
                tampil.add(pola)
                peringatan.append("Pola boros di baris baru %s — %s: %s"
                                  % (pola.strip(), saran, isi.strip()[:70]))
    for _, isi in tambah_java(diff):
        if ".matches(" in isi and "matcher" not in isi.lower() \
                and ".matches(" not in tampil:
            tampil.add(".matches(")
            peringatan.append("Pola boros di baris baru .matches( — "
                              "kompilasi regex tiap panggil; pakai Pattern statis: %s"
                              % isi.strip()[:70])

def cek_izin_baru(peringatan, diff):
    for baris in diff.splitlines():
        if baris.startswith("+") and "uses-permission" in baris:
            cocok = re.search(r'android:name="([^"]+)"', baris)
            if cocok:
                peringatan.append("Izin baru %s — pastikan perlu di TV/STB + tercatat README"
                                  % cocok.group(1))

def cek_definisi_baru(peringatan, diff):
    pola = re.compile(r'\+\s*<(style|string|color|dimen|plurals|string-array|integer-array|array)\s+name="([^"]+)"')
    _, acuan = himpun_definisi()
    sudah = set()
    for baris in diff.splitlines():
        cocok = pola.match(baris)
        if cocok and (cocok.group(1), cocok.group(2)) not in sudah:
            sudah.add((cocok.group(1), cocok.group(2)))
            jenis, nama = cocok.group(1), cocok.group(2)
            kunci = "array" if "array" in jenis else jenis
            if nama not in acuan.get(kunci, set()):
                peringatan.append("Definisi baru %s/%s tanpa acuan — hapus bila tak jadi dipakai"
                                  % (kunci, nama))

def cek_pengingat_changelog(peringatan, berubah):
    if not berubah or "CHANGELOG.md" in berubah:
        return
    if any(b.startswith(("app/src/main/java/", "app/src/main/res/",
                          "app/src/main/AndroidManifest.xml", "shim/",
                          "app/build.gradle.kts")) for b in berubah):
        peringatan.append("Kode aplikasi berubah tanpa CHANGELOG.md — catat per rilis (aturan repo)")

def cek_sinkron_terjemah(gagal):
    def nama_string(path):
        return set(re.findall(r'<string\s+name="([^"]+)"', baca(path)))

    def nama_plurals(path):
        return set(re.findall(r'<plurals\s+name="([^"]+)"', baca(path)))

    inggris = nama_string("app/src/main/res/values/strings.xml")
    indonesia = nama_string("app/src/main/res/values-in/strings.xml")
    if inggris and indonesia:
        kurang = sorted(inggris - indonesia)
        lebih = sorted(indonesia - inggris)
        if kurang:
            gagal.append("values-in kurang string: " + ", ".join(kurang[:10]))
        if lebih:
            gagal.append("values-in berlebih string: " + ", ".join(lebih[:10]))

    pl_inggris = nama_plurals("app/src/main/res/values/strings.xml")
    pl_indonesia = nama_plurals("app/src/main/res/values-in/strings.xml")
    if pl_inggris or pl_indonesia:
        kurang = sorted(pl_inggris - pl_indonesia)
        lebih = sorted(pl_indonesia - pl_inggris)
        if kurang:
            gagal.append("values-in kurang plurals: " + ", ".join(kurang[:10]))
        if lebih:
            gagal.append("values-in berlebih plurals: " + ", ".join(lebih[:10]))

def jenis_biner(path):
    try:
        with open(os.path.join(AKAR, path), "rb") as f:
            kepala = f.read(4)
    except OSError:
        return None
    if kepala.startswith(b"\x7fELF"):
        return "ELF"
    if kepala[:2] == b"MZ":
        return "PE/EXE"
    if kepala[:4] == b"PK\x03\x04":
        return "ZIP/JAR/APK"
    if kepala[:4] in (b"\xcf\xfa\xed\xfe", b"\xce\xfa\xed\xfe", b"\xca\xfe\xba\xbe"):
        return "Mach-O/class"
    if kepala[:4] == b"dex\n":
        return "DEX"
    return None

BINER_DIIZINKAN = {"gradle/wrapper/gradle-wrapper.jar"}
BATAS_UKURAN_BERKAS = 1024 * 1024

def cek_aturan_repo(gagal, berubah):
    tetap = baca("app/build.gradle.kts")
    target = re.search(r"targetSdk\s*=\s*(\d+)", tetap)
    if target and int(target.group(1)) >= 29:
        gagal.append("targetSdk %s >= 29 memblokir execve binary (aturan repo)" % target.group(1))
    abi = set()
    for baris in tetap.splitlines():
        if "abiFilters" in baris:
            abi.update(re.findall(r'"([^"]+)"', baris))
    asing = sorted(a for a in abi if a != "armeabi-v7a")
    if asing:
        gagal.append("ABI tambahan %s membengkakkan APK (aturan: armeabi-v7a saja)" % asing)
    for path in berubah:

        if not os.path.exists(os.path.join(AKAR, path)):
            continue
        rendah = path.lower()
        if rendah.endswith((".apk", ".aab", ".dex", ".jks", ".keystore")):
            gagal.append("Berkas terlarang masuk repo: " + path)
            continue
        if path.startswith("app/src/main/assets/bin/"):
            gagal.append("Binary tak boleh dibundel ke APK: " + path)
            continue
        if path in BINER_DIIZINKAN:
            continue

        jenis = jenis_biner(path)
        if jenis:
            gagal.append("Berkas biner (%s) tak boleh masuk repo: %s" % (jenis, path))
            continue
        ukuran = os.path.getsize(os.path.join(AKAR, path))
        if ukuran > BATAS_UKURAN_BERKAS:
            gagal.append("Berkas >1 MB tak boleh masuk repo"
                         " (binary/web-vault diunduh CI): " + path)

def nama_metode_hilang(diff, lewati_berkas=()):
    hilang = []
    for path, baris in hapus_java(diff):

        rapi = baris.rstrip()
        if not re.search(r"\b(public|protected|private|static)\b", baris) \
                and not rapi.endswith("{") \
                and (rapi.endswith(";")
                     or not re.match(r"\s*\w[\w<>\[\].,? ]*\s+\w+\s*\(", baris)):
            continue

        if path in lewati_berkas or not os.path.isfile(os.path.join(AKAR, path)):
            continue
        cocok = re.match(
            r"\s*(?:(?:public|protected|private)\s+)?(?:static\s+)?"
            r"[\w<>\[\].,? ]+\s+(\w+)\s*\(", baris)
        if not cocok or cocok.group(1) in KATA_KUNCI_JAVA:
            continue

        if re.search(r"\bnew\s+" + re.escape(cocok.group(1)) + r"\b", baris):
            continue
        hilang.append(cocok.group(1))
    return hilang

def baris_tanpa_komentar(isi):
    dalam = False
    for i, baris in enumerate(isi.splitlines(), 1):
        s = baris.strip()
        if dalam:
            if "*/" in s:
                dalam = False
            continue
        if s.startswith("/*"):
            if "*/" not in s:
                dalam = True
            continue
        if s.startswith("//") or s.startswith("*"):
            continue
        yield i, kupas_java(baris)

BUKAN_TIPE = KATA_KUNCI_JAVA | {"break", "continue", "super", "this", "yield"}

POLA_DEKLARASI = re.compile(
    r"^(?:@[\w.]+(?:\([^)]*\))?\s+)*"
    r"(?:(?:public|protected|private|static|final|abstract|synchronized"
    r"|native|default|strictfp)\s+)*"
    r"(?:<[^<>]*>\s+)?"
    r"([\w$][\w$<>\[\].,?& ]*)\s+(\w+)\s*\(")

def nama_metode_dideklarasikan(baris):
    s = baris.strip()
    if not s:
        return None
    cocok = POLA_DEKLARASI.match(s)
    if not cocok or cocok.group(1) in BUKAN_TIPE:
        return None
    return cocok.group(2)

_CACHE_DEKLARASI = {}

def nama_terdeklarasi(path):
    if path not in _CACHE_DEKLARASI:
        nama = set()
        for _, baris in baris_tanpa_komentar(baca(path)):
            metode = nama_metode_dideklarasikan(baris)
            if metode:
                nama.add(metode)
        _CACHE_DEKLARASI[path] = nama
    return _CACHE_DEKLARASI[path]

def masih_didefinisikan(nama):
    for path in jalan_repo(os.path.join("app", "src")):
        if nama in nama_terdeklarasi(path):
            return True
    return False

def pemanggil(nama):
    pola = re.compile(r"\b%s\s*\(" % re.escape(nama))
    temu = []
    for path in jalan_repo("app", ext=(".java",)):
        for i, baris in baris_tanpa_komentar(baca(path)):
            if pola.search(baris):
                temu.append("%s:%d" % (path, i))
                if len(temu) >= 6:
                    return temu
    return temu

def cek_metode_hilang(gagal, peringatan, diff, hapus=()):
    lewati_berkas = set(hapus)
    for nama in sorted(nama_metode_hilang(diff, lewati_berkas)):
        if masih_didefinisikan(nama):
            continue
        siapa = pemanggil(nama)
        if not siapa:
            continue
        pesan = "Metode %s() dihapus tapi masih dipanggil: %s" % (nama, ", ".join(siapa))
        if nama in OVERRIDE_AMAN:
            peringatan.append(pesan + " (callback siklus hidup?)")
        else:
            gagal.append(pesan)

def cek_panggil_bekas_hapusan(gagal, diff):
    if not BEKAS_HAPUSAN:
        return
    for nama in sorted(BEKAS_HAPUSAN):
        pola = re.compile(r"\b%s\s*\(" % re.escape(nama))
        for _, baris in tambah_java(diff):

            if baris.lstrip().startswith(("*", "//", "/*")):
                continue
            if pola.search(kupas_java(baris)):
                gagal.append(
                    "Baris baru memanggil %s() yang sudah dihapus: %s"
                    % (nama, baris.strip()[:80]))
                break

def hunk_fungsi(diff):
    path = ""
    for baris in diff.splitlines():
        if baris.startswith("diff --git "):
            bagian = baris.split()
            path = bagian[-1][2:] if len(bagian) >= 4 else ""
        elif baris.startswith("@@ ") and path.endswith(".java"):
            ekor = baris.split("@@", 2)[-1]
            cocok = re.search(r"(\w+)\s*\(", ekor)
            if cocok:
                yield path, cocok.group(1)

def cek_uji_ubah(peringatan, diff):
    sudah = set()
    for _, nama in hunk_fungsi(diff):
        if not nama or nama in sudah or nama in KATA_KUNCI_JAVA:
            continue
        if nama[:1].isupper():
            continue
        sudah.add(nama)
        if not disebut_di_uji(nama):
            peringatan.append(
                "Metode %s() diubah tapi tanpa jejak di app/src/test — "
                "tambah/cek uji logika murninya agar salah-fix ketahuan" % nama)

def cek_hapusan_belum_tercatat(peringatan, diff):
    tercatat = BEKAS_HAPUSAN
    if not tercatat:
        return
    for nama in sorted(set(nama_metode_hilang(diff))):
        if nama in tercatat or masih_didefinisikan(nama):
            continue
        peringatan.append(
            "Metode %s() dihapus tapi belum di tools/bekas-hapusan.txt — "
            "tambahkan agar push berikut tetap terjaga" % nama)

def cek_locale_baru(gagal, diff):
    for _, isi in tambah_java(diff):

        rapih = isi.strip()
        if not rapih or rapih.startswith(("*", "//", "/*", "*/")):
            continue
        kode = kupas_java(isi)
        if re.search(r"\.to(Lower|Upper)Case\(\)", kode):
            gagal.append("Baris baru toLower/UpperCase() tanpa Locale — bug locale "
                         "Turki + alokasi sia-sia; pakai varian Locale.US: %s"
                         % kode.strip()[:80])

def nama_warna(path):
    return set(re.findall(r'<color\s+name="([^"]+)"', baca(path)))

def quantities_plurals(path):
    hasil = {}
    for m in re.finditer(r'<plurals\s+name="([^"]+)"(.*?)</plurals>', baca(path), re.S):
        hasil[m.group(1)] = set(re.findall(r'quantity="([^"]+)"', m.group(2)))
    return hasil

def cek_paritas_resource(gagal):
    try:

        warna_siang = "app/src/main/res/values/colors.xml"
        warna_malam = "app/src/main/res/values-night/colors.xml"
        warna_hilang = [p for p in (warna_siang, warna_malam)
                        if not os.path.isfile(os.path.join(AKAR, p))]
        if warna_hilang:
            gagal.append("Berkas warna wajib hilang: " + ", ".join(warna_hilang)
                         + " (mode siang & gelap butuh keduanya)")
        else:
            siang = nama_warna(warna_siang)
            malam = nama_warna(warna_malam)
            if not siang or not malam:
                gagal.append("colors.xml tanpa <color> — paritas siang/gelap"
                             " tak bisa dicek")
            if siang - malam:
                gagal.append("colors.xml siang berlebih vs night: "
                             + ", ".join(sorted(siang - malam)[:10]))
            if malam - siang:
                gagal.append("values-night/colors.xml berlebih vs siang: "
                             + ", ".join(sorted(malam - siang)[:10]))
        tata = os.path.join(AKAR, "app", "src", "main", "res", "layout")
        darat_dir = os.path.join(AKAR, "app", "src", "main", "res", "layout-land")
        folder_hilang = [p for p in ("layout", "layout-land")
                         if not os.path.isdir(os.path.join(AKAR, "app", "src",
                                                           "main", "res", p))]
        if folder_hilang:
            gagal.append("Folder layout wajib hilang: " + ", ".join(folder_hilang)
                         + " (portrait & landscape butuh keduanya)")
        else:
            for nama in sorted(os.listdir(tata)):
                if not nama.endswith(".xml"):
                    continue
                darat = os.path.join(darat_dir, nama)
                if not os.path.isfile(darat):
                    continue
                port = set(re.findall(r'@\+id/([A-Za-z0-9_]+)',
                                      baca("app/src/main/res/layout/" + nama)))
                land = set(re.findall(r'@\+id/([A-Za-z0-9_]+)',
                                      baca("app/src/main/res/layout-land/" + nama)))
                if port != land:
                    gagal.append("ID layout/%s != layout-land/%s (kurang: %s; lebih: %s)"
                                 % (nama, nama,
                                    ", ".join(sorted(port - land)[:5]) or "-",
                                    ", ".join(sorted(land - port)[:5]) or "-"))

        pl_in = quantities_plurals("app/src/main/res/values-in/strings.xml")
        for nama in sorted(pl_in):
            q = pl_in[nama]
            if "other" not in q:
                gagal.append("plurals %s (values-in) tanpa quantity \"other\""
                             " (lint MissingQuantity)" % nama)
            asing = q - {"other"}
            if asing:
                gagal.append("plurals %s (values-in) quantity tak relevan untuk"
                             " bahasa Indonesia: %s (lint UnusedQuantity)"
                             % (nama, ", ".join(sorted(asing))))
    except OSError as e:
        gagal.append("Paritas resource tak terbaca: %s" % e)

def cek_acuan_agents(gagal):
    teks = baca("AGENTS.md")
    if not teks.strip():
        gagal.append("AGENTS.md tak terbaca — panduan agen wajib ada")
        return
    for nama in sorted(set(re.findall(r"`([A-Za-z_]\w*)\(\)`", teks))):
        if not masih_didefinisikan(nama):
            gagal.append("AGENTS.md menyebut metode %s() yang tak ada di app/src"
                         " — perbarui dokumen (atau kode)" % nama)

def cek_import_mati(gagal):
    for path in jalan_repo("app", ext=(".java",)):
        mentah = baca(path)
        bersih = kupas_java(mentah)
        tanpa = re.sub(r"^\s*import\s+.*$", "", bersih, flags=re.M)

        acuan_javadoc = set()
        for taut in re.findall(r"\{@link(?:plain|code)?\s+([\w.#$]+)", mentah):
            target = taut.split("#", 1)[0].split(".")[-1]
            if target:
                acuan_javadoc.add(target)
        for m in re.finditer(r"^\s*import\s+(static\s+)?([\w.]+)\s*;",
                             bersih, flags=re.M):
            jalan_imp = m.group(2)
            if jalan_imp.endswith(".*"):
                continue
            nama = jalan_imp.split(".")[-1]

            if (not re.search(r"(?<![\w.])%s\b" % re.escape(nama), tanpa)
                    and nama not in acuan_javadoc):
                gagal.append("Import mati di %s: %s" % (path, jalan_imp))

def disebut_di_uji(nama):
    pola = re.compile(r"\b%s\s*\(" % re.escape(nama))
    for path in jalan_repo(os.path.join("app", "src", "test")):
        if pola.search(baca(path)):
            return True
    return False

def cek_uji_baru(peringatan, diff):
    pola = re.compile(
        r"\s*(?:public\s+)?static\s+[\w<>\[\].,? ]+\s+(\w+)\s*\(")

    def inti(baris):
        return re.sub(r"\s+", " ", kupas_java(baris)).strip()

    lama = set(inti(baris) for _, baris in hapus_java(diff))
    sudah = set()
    for _, baris in tambah_java(diff):
        cocok = pola.match(baris)
        if cocok and cocok.group(1) not in KATA_KUNCI_JAVA:
            nama = cocok.group(1)
            if nama not in sudah:
                sudah.add(nama)
                if inti(baris) in lama:
                    continue
                if not disebut_di_uji(nama):
                    peringatan.append(
                        "Metode statis baru %s() tanpa jejak di app/src/test "
                        "(aturan: uji logika murni baru)" % nama)

def cek_pola_berisiko(peringatan, diff, berubah, rentang=""):
    for _, isi in tambah_java(diff):
        if "System.out.println" in isi:
            peringatan.append("System.out.println di diff (pakai log/Toast): " + isi.strip()[:80])
        if ".printStackTrace()" in isi:
            peringatan.append("printStackTrace di diff (telan/abaikan eksplisit): " + isi.strip()[:80])

    r = rentang_normal(rentang) if rentang else None
    if r is not None:
        cek = jalan("git", "diff", "--check", r[0] + "..." + r[2])
    else:
        cek = jalan("git", "diff", "--check")
    if cek:
        peringatan.append("Rapikan spasi diff --check: " + cek.strip().splitlines()[0][:100])

def jalan_diff(*args):
    hasil = jalan(*args)
    if hasil is None:
        return "", False
    return hasil, True

def utama():
    parser = argparse.ArgumentParser(description="Verifikasi patch yang baru dikerjakan.")
    parser.add_argument("jalur", nargs="*", help="berkas spesifik (opsional)")
    parser.add_argument("--semua", action="store_true", help="cek seluruh repo")
    parser.add_argument("--rentang", default="", help="rentang diff A...B (dipakai CI)")
    args = parser.parse_args()

    gagal = []
    peringatan = []
    diff = ""

    diff_gagal = False
    rentang_cek = ""
    if args.jalur:
        berubah = [j for j in args.jalur if os.path.exists(os.path.join(AKAR, j))]
        diff, ok = jalan_diff("git", "diff", "--", *berubah)
        diff_gagal = diff_gagal or not ok
    elif args.rentang:
        r = rentang_normal(args.rentang)
        if r is None:

            r = rentang_cadangan()
            if r is None:
                print("info: rentang kosong dan repo satu commit — cek keadaan penuh.")
            else:
                print("info: rentang kosong/tak valid — bandingkan HEAD~1...HEAD.")
        if r is None:
            berubah = [p for p in jalan_repo("app") if not p.endswith(".md")]
        else:
            rentang_cek = r[0] + r[1] + r[2]
            daftar = daftar_berkas_berubah(rentang_cek)
            if daftar is None:

                r = rentang_cadangan()
                if r is not None:
                    rentang_cek = r[0] + r[1] + r[2]
                    daftar = daftar_berkas_berubah(rentang_cek)
                    print("info: rentang tak valid — bandingkan HEAD~1...HEAD.")
            if daftar is None:
                berubah = [p for p in jalan_repo("app") if not p.endswith(".md")]
            else:
                berubah = daftar

            if r is not None:
                diff, ok = jalan_diff("git", "diff", r[0] + "..." + r[2])
                diff_gagal = diff_gagal or not ok
    else:
        hulu = jalan("git", "rev-parse", "--abbrev-ref", "--symbolic-full-name", "@{u}")
        if hulu:
            diff, ok = jalan_diff("git", "diff", hulu.strip() + "...HEAD")
            diff_gagal = diff_gagal or not ok
            daftar = jalan("git", "diff", "--name-only", hulu.strip() + "...HEAD") or ""
            berubah = daftar.splitlines()
        else:
            diff, ok = jalan_diff("git", "diff", "HEAD~1...HEAD")
            diff_gagal = diff_gagal or not ok
            berubah = (jalan("git", "diff", "--name-only", "HEAD~1...HEAD") or "").splitlines()
        kotor = jalan("git", "status", "--porcelain") or ""
        for baris in kotor.splitlines():
            nama = baris[3:].strip().strip('"')
            if nama and nama not in berubah:
                berubah.append(nama)

        teks, ok = jalan_diff("git", "diff")
        diff += teks
        diff_gagal = diff_gagal or not ok
        teks, ok = jalan_diff("git", "diff", "--cached")
        diff += teks
        diff_gagal = diff_gagal or not ok
    berubah += [b for b in nama_tak_lacak() if b not in berubah]
    if args.semua:

        berubah = list(jalan_repo(".", ext=None))
        if not diff:

            r = rentang_cadangan()
            if r is not None:
                rentang_cek = r[0] + r[1] + r[2]
                diff, ok = jalan_diff("git", "diff", r[0] + r[1] + r[2])
                diff_gagal = diff_gagal or not ok
                print("info: --semua tanpa diff kerja — cek diff memakai HEAD~1...HEAD.")
            else:
                print("info: --semua tanpa riwayat commit — cek diff dilewati.")

    cek_xml(gagal)
    cek_acuan_yatim(gagal)
    cek_manifest(gagal)
    cek_duplikat_definisi(gagal)
    cek_sinkron_terjemah(gagal)
    cek_import_mati(gagal)
    cek_acuan_agents(gagal)
    cek_paritas_resource(gagal)
    cek_daftar_hapusan(gagal)
    cek_aturan_repo(gagal, berubah)
    cek_kurung_java(gagal, berubah)
    hapus = berkas_dihapus(rentang_cek, diff)
    if not args.rentang:
        keluar = jalan("git", "diff", "--cached", "--name-status") or ""
        hapus += [x.split(None, 1)[1] for x in keluar.splitlines() if x.startswith("D")]
    cek_berkas_java_dihapus(gagal, hapus)
    if not args.semua:
        cek_pengingat_changelog(peringatan, berubah)
    if diff:
        cek_panggil_bekas_hapusan(gagal, diff)
        cek_locale_baru(gagal, diff)
        cek_metode_hilang(gagal, peringatan, diff, hapus)
        cek_hapusan_belum_tercatat(peringatan, diff)
        cek_uji_baru(peringatan, diff)
        cek_uji_ubah(peringatan, diff)
        cek_pola_berisiko(peringatan, diff, berubah, rentang_cek)
        cek_pola_boros(peringatan, diff)
        cek_izin_baru(peringatan, diff)
        cek_definisi_baru(peringatan, diff)
    if diff_gagal:

        gagal.append(
            "git diff gagal (riwayat tak berhubungan, safe.directory, atau "
            "timeout): cek berbasis diff tak bisa dijalankan — bukan alasan LOLOS.")

    for w in peringatan:
        print("PERINGATAN: " + w)
        anotasi("warning", w)
    if gagal:
        for g in gagal:
            print("GAGAL: " + g)
            anotasi("error", g)
        print("Hasil: GAGAL (%d) — perbaiki sebelum push." % len(gagal))
        return 1
    print("Hasil: LOLOS (%d berkas diperiksa%s)." % (
        len(berubah), (", %d peringatan" % len(peringatan)) if peringatan else ""))
    return 0

if __name__ == "__main__":
    sys.exit(utama())
