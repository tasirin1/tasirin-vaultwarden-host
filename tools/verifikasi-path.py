#!/usr/bin/env python3
"""Verifikasi otomatis path yang baru dikerjakan.

Memastikan penghapusan kode tak merusak acuan dan aturan repo tetap
ditaati, tanpa toolchain Android (hanya baca kode + git diff + parse XML).

Pakai:
    python3 tools/verifikasi-path.py                 # kerja lokal + belum push
    python3 tools/verifikasi-path.py --semua         # seluruh repo
    python3 tools/verifikasi-path.py --rentang A...B # rentang commit (dipakai CI)
    python3 tools/verifikasi-path.py app/... Main.java

Keluar 0 bila lolos (peringatan tak menggagalkan), 1 bila ada yang gagal.
"""
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
# Metode repo yang sengaja dihapus (audit agresif): bila baris baru masih
# memanggilnya, itu regresi hapusan. Tambahkan nama ke sini tiap menghapus
# metode agar pen penyebutnya tertangkap otomatis.
BEKAS_HAPUSAN = {"batalTunda"}
# Callback siklus hidup: menghapusnya aman dari sisi acuan (turunkan jadi peringatan).
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


def daftar_berkas_berubah(rentang):
    """Nama file berubah pada rentang diff (None bila revisi tak valid)."""
    if rentang:
        for pemisah in ("...", ".."):
            if pemisah in rentang:
                a, b = rentang.split(pemisah, 1)
                if set(a) == {"0"} or not a:
                    return None  # push pertama: tak ada pembanding
                out = jalan("git", "diff", "--name-only", a + pemisah + b)
                if out is not None:
                    return [x for x in out.splitlines() if x]
                return None
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
    for akar, _, berkas in os.walk(os.path.join(AKAR, rel)):
        for nama in berkas:
            if nama.endswith(ext):
                yield os.path.relpath(os.path.join(akar, nama), AKAR)


def himpun_definisi():
    """Kembalikan (definisi, acuan): nama sumber daya terdefinisi vs terpakai."""
    definisi = {}
    acuan = {}
    # Titik pada nama style (Theme.A.B) menjadi garis bawah di R (aturan aapt).
    def rapikan(jenis, nama):
        return nama.replace(".", "_") if jenis == "style" else nama

    pola_def = re.compile(r'<(style|string|color|dimen)\s+name="([^"]+)"')
    pola_array = re.compile(r'<(string-array|integer-array|array)\s+name="([^"]+)"')

    def tambah(kunci, nama):
        definisi.setdefault(kunci, set()).add(rapikan(kunci, nama))

    for path in jalan_repo(os.path.join("app", "src", "main", "res"), ext=(".xml",)):
        isi = baca(path)
        for jenis, nama in pola_def.findall(isi):
            tambah(jenis, nama)
        for jenis, nama in pola_array.findall(isi):
            tambah("array", nama)
        for nama in re.findall(r'@\+id/([A-Za-z0-9_]+)', isi):
            tambah("id", nama)
    for folder in ("drawable", "mipmap"):
        wadah = os.path.join(MAIN, "res", folder)
        if os.path.isdir(wadah):
            for akar, _, berkas in os.walk(wadah):
                for nama in berkas:
                    tambah("drawable", os.path.splitext(nama)[0])
    for path in jalan_repo(os.path.join("app", "src", "main", "res", "layout"), ext=(".xml",)):
        tambah("layout", os.path.splitext(os.path.basename(path))[0])

    # (?<!android\.) mengecualikan kerangka (android.R.*, @android:*) yang
    # bukan milik repo; style membolehkan titik (Theme.A.B).
    pola_pakai = [
        ("style", r"@style/([A-Za-z0-9_.]+)", r"(?<!android\.)R\.style\.([A-Za-z0-9_]+)"),
        ("string", r"@string/([A-Za-z0-9_]+)", r"(?<!android\.)R\.string\.([A-Za-z0-9_]+)"),
        ("color", r"@color/([A-Za-z0-9_]+)", r"(?<!android\.)R\.color\.([A-Za-z0-9_]+)"),
        ("dimen", r"@dimen/([A-Za-z0-9_]+)", r"(?<!android\.)R\.dimen\.([A-Za-z0-9_]+)"),
        ("array", r"@array/([A-Za-z0-9_]+)", r"(?<!android\.)R\.array\.([A-Za-z0-9_]+)"),
        ("drawable", r"@drawable/([A-Za-z0-9_]+)", r"(?<!android\.)R\.drawable\.([A-Za-z0-9_]+)"),
        ("layout", None, r"(?<!android\.)R\.layout\.([A-Za-z0-9_]+)"),
        ("id", r"@id/([A-Za-z0-9_]+)", r"(?<!android\.)R\.id\.([A-Za-z0-9_]+)"),
    ]
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
    return definisi, acuan


def cek_xml(gagal, basis_desc):
    buruk = []
    for path in jalan_repo(os.path.join("app", "src", "main", "res"), ext=(".xml",)):
        try:
            ET.parse(os.path.join(AKAR, path))
        except ET.ParseError as e:
            buruk.append("%s (%s)" % (path, e))
    for path in ("app/src/main/AndroidManifest.xml",):
        try:
            ET.parse(os.path.join(AKAR, path))
        except ET.ParseError as e:
            buruk.append("%s (%s)" % (path, e))
    if buruk:
        gagal.append("XML rusak: " + ", ".join(buruk))


def cek_acuan_yatim(gagal):
    definisi, acuan = himpun_definisi()
    for jenis, dipakai in sorted(acuan.items()):
        ada = definisi.get(jenis, set())
        yatim = sorted(n for n in dipakai if n not in ada)
        if yatim:
            gagal.append("Acuan %s yatim (dipakai tapi tak terdefinisi): %s"
                         % (jenis, ", ".join(yatim[:10])))


def cek_sinkron_terjemah(gagal):
    def nama_string(path):
        return set(re.findall(r'<string\s+name="([^"]+)"', baca(path)))

    inggris = nama_string("app/src/main/res/values/strings.xml")
    indonesia = nama_string("app/src/main/res/values-in/strings.xml")
    if inggris and indonesia:
        kurang = sorted(inggris - indonesia)
        lebih = sorted(indonesia - inggris)
        if kurang:
            gagal.append("values-in kurang string: " + ", ".join(kurang[:10]))
        if lebih:
            gagal.append("values-in berlebih string: " + ", ".join(lebih[:10]))


def cek_aturan_repo(gagal, berubah):
    tetap = baca("app/build.gradle.kts")
    target = re.search(r"targetSdk\s*=\s*(\d+)", tetap)
    if target and int(target.group(1)) >= 29:
        gagal.append("targetSdk %s >= 29 memblokir execve binary (aturan repo)" % target.group(1))
    abi = re.findall(r'"(arm64-v8a|x86|x86_64)"', tetap)
    if abi:
        gagal.append("ABI tambahan %s membengkakkan APK (aturan: armeabi-v7a saja)" % sorted(set(abi)))
    for path in berubah:
        rendah = path.lower()
        if rendah.endswith((".apk", ".aab", ".dex", ".jks", ".keystore")):
            gagal.append("Berkas terlarang masuk repo: " + path)
        elif path.startswith("app/src/main/assets/bin/"):
            gagal.append("Binary tak boleh dibundel ke APK: " + path)
        elif path.startswith("app/src/main/"):
            try:
                ukuran = os.path.getsize(os.path.join(AKAR, path))
            except OSError:
                ukuran = 0
            if ukuran > 1024 * 1024:
                gagal.append("Berkas >1 MB di app/src (binary/web-vault diunduh CI): " + path)


def nama_metode_hilang(diff):
    """Nama metode pada baris dihapus yang definisinya hilang dari pohon kerja."""
    hilang = set()
    for baris in diff.splitlines():
        if not baris.startswith("-") or baris.startswith("---"):
            continue
        cocok = re.match(
            r"-\s*(?:(?:public|protected|private)\s+)?(?:static\s+)?"
            r"[\w<>\[\].,? ]+\s+(\w+)\s*\(", baris[1:])
        if cocok and cocok.group(1) not in KATA_KUNCI_JAVA:
            hilang.append(cocok.group(1))
    return hilang


def masih_didefinisikan(nama):
    pola = re.compile(r"\b%s\s*\(" % re.escape(nama))
    for path in jalan_repo(os.path.join("app", "src", "main", "java")):
        if pola.search(baca(path)):
            return True
    return False


def pemanggil(nama):
    pola = re.compile(r"\b%s\s*\(" % re.escape(nama))
    temu = []
    for path in jalan_repo("app", ext=(".java",)):
        for i, baris in enumerate(baca(path).splitlines(), 1):
            if pola.search(baris):
                temu.append("%s:%d" % (path, i))
                if len(temu) >= 6:
                    return temu
    return temu


def cek_metode_hilang(gagal, peringatan, diff):
    for nama in sorted(nama_metode_hilang(diff)):
        if masih_didefinisikan(nama):
            continue  # overload/kembaran masih ada: aman
        siapa = pemanggil(nama)
        if not siapa:
            continue  # tak ada pemanggil sisa: hapus bersih
        pesan = "Metode %s() dihapus tapi masih dipanggil: %s" % (nama, ", ".join(siapa))
        if nama in OVERRIDE_AMAN:
            peringatan.append(pesan + " (callback siklus hidup?)")
        else:
            gagal.append(pesan)


def cek_panggil_bekas_hapusan(gagal, diff):
    for nama in sorted(BEKAS_HAPUSAN):
        pola = re.compile(r"\b%s\s*\(" % re.escape(nama))
        for baris in diff.splitlines():
            if baris.startswith("+") and not baris.startswith("+++"):
                if pola.search(baris[1:]):
                    gagal.append(
                        "Baris baru memanggil %s() yang sudah dihapus: %s"
                        % (nama, baris[1:].strip()[:80]))
                    break


def cek_uji_baru(peringatan, diff):
    pola = re.compile(
        r"\+\s*(?:public\s+)?static\s+[\w<>\[\].,? ]+\s+(\w+)\s*\(")
    for baris in diff.splitlines():
        if not baris.startswith("+") or baris.startswith("+++"):
            continue
        cocok = pola.match(baris[1:])
        if cocok and cocok.group(1) not in KATA_KUNCI_JAVA:
            nama = cocok.group(1)
            if not pemanggil(nama) or not any("src/test" in p for p in pemanggil(nama)):
                peringatan.append(
                    "Metode statis baru %s() tanpa jejak di app/src/test (aturan: uji logika murni baru)"
                    % nama)


def cek_pola_berisiko(peringatan, diff, berubah):
    for baris in diff.splitlines():
        if baris.startswith("+") and not baris.startswith("+++"):
            isi = baris[1:]
            if "System.out.println" in isi:
                peringatan.append("System.out.println di diff (pakai log/Toast): " + isi.strip()[:80])
            if ".printStackTrace()" in isi:
                peringatan.append("printStackTrace di diff (telan/abaikan eksplisit): " + isi.strip()[:80])
    cek = jalan("git", "diff", "--check")
    if cek:
        peringatan.append("Rapikan spasi diff --check: " + cek.strip().splitlines()[0][:100])


def utama():
    parser = argparse.ArgumentParser(description="Verifikasi path yang baru dikerjakan.")
    parser.add_argument("jalur", nargs="*", help="berkas spesifik (opsional)")
    parser.add_argument("--semua", action="store_true", help="cek seluruh repo")
    parser.add_argument("--rentang", default="", help="rentang diff A...B (dipakai CI)")
    args = parser.parse_args()

    gagal = []
    peringatan = []
    diff = ""
    if args.jalur:
        berubah = [j for j in args.jalur if os.path.exists(os.path.join(AKAR, j))]
        diff = jalan("git", "diff", "--", *berubah) or ""
    elif args.rentang:
        daftar = daftar_berkas_berubah(args.rentang)
        if daftar is None:
            print("info: rentang tak valid (push pertama?) — cek keadaan penuh.")
            berubah = [p for p in jalan_repo("app") if not p.endswith(".md")]
        else:
            berubah = daftar
            a, b = re.split(r"\.\.+", args.rentang, maxsplit=1)
            diff = jalan("git", "diff", a + "..." + b) or ""
    else:
        hulu = jalan("git", "rev-parse", "--abbrev-ref", "--symbolic-full-name", "@{u}")
        if hulu:
            diff = jalan("git", "diff", hulu.strip() + "...HEAD") or ""
            daftar = jalan("git", "diff", "--name-only", hulu.strip() + "...HEAD") or ""
            berubah = daftar.splitlines()
        else:
            diff = jalan("git", "diff", "HEAD~1...HEAD") or ""
            berubah = (jalan("git", "diff", "--name-only", "HEAD~1...HEAD") or "").splitlines()
        kotor = jalan("git", "status", "--porcelain") or ""
        for baris in kotor.splitlines():
            nama = baris[3:].strip().strip('"')
            if nama and nama not in berubah:
                berubah.append(nama)
        # Diff kerja yang belum commit ikut diperiksa agar hapusan salah
        # tertangkap sebelum commit, bukan sesudah push.
        diff += (jalan("git", "diff") or "")
        diff += jalan("git", "diff", "--cached") or ""
    berubah += [b for b in nama_tak_lacak() if b not in berubah]
    if args.semua:
        berubah = [p for p in jalan_repo("app")]

    cek_xml(gagal, "")
    cek_acuan_yatim(gagal)
    cek_sinkron_terjemah(gagal)
    cek_aturan_repo(gagal, berubah)
    if diff:
        cek_panggil_bekas_hapusan(gagal, diff)
        cek_metode_hilang(gagal, peringatan, diff)
        cek_uji_baru(peringatan, diff)
        cek_pola_berisiko(peringatan, diff, berubah)

    for w in peringatan:
        print("PERINGATAN: " + w)
    if gagal:
        for g in gagal:
            print("GAGAL: " + g)
        print("Hasil: GAGAL (%d) — perbaiki sebelum push." % len(gagal))
        return 1
    print("Hasil: LOLOS (%d berkas diperiksa%s)." % (
        len(berubah), (", %d peringatan" % len(peringatan)) if peringatan else ""))
    return 0


if __name__ == "__main__":
    sys.exit(utama())
