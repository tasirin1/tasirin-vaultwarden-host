#!/usr/bin/env python3
import argparse
import ast
import codecs
import os
import re
import subprocess
import sys
import xml.etree.ElementTree as ET

AKAR = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
MAIN = os.path.join(AKAR, "app", "src", "main")
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

# Audit 1634: daftar bekas hapusan dimuat MALAS pada pemakaian
# pertama, bukan saat import — modul yang disalin/diprofilkan di
# luar pohon tak lagi gagal di import, dan vonis di dalam pohon
# tetap persis (cek_daftar_hapusan tetap gagal bila tak terbaca).
_BACAS_HAPUSAN = []


def bekas_hapusan():
    if not _BACAS_HAPUSAN:
        _BACAS_HAPUSAN.append(muat_bekas_hapusan())
    return _BACAS_HAPUSAN[0]

def cek_daftar_hapusan(gagal):
    BEKAS_HAPUSAN = bekas_hapusan()
    if BEKAS_HAPUSAN is None:
        gagal.append("tools/bekas-hapusan.txt tak terbaca — penjagaan nama "
                     "metode terhapus tidak bisa dijalankan")
    elif not BEKAS_HAPUSAN:
        gagal.append("tools/bekas-hapusan.txt kosong — daftar nama metode "
                     "terhapus wajib berisi")
    else:
        for nama in sorted(BEKAS_HAPUSAN):
            if masih_didefinisikan(nama):
                gagal.append("Nama %s di tools/bekas-hapusan.txt masih "
                             "terdefinisi di pohon — keluarkan dari daftar "
                             "atau ganti nama metodenya" % nama)
        isi_java = []
        for akar, _dirs, berkas2 in os.walk(
                os.path.join(AKAR, "app", "src")):
            for b in berkas2:
                if b.endswith(".java"):
                    p = os.path.relpath(os.path.join(akar, b), AKAR)
                    isi_java.append((p, kupas_java(baca(p))))
        # Audit 953: kandidat nama pemanggilan diekstrak SEKALI per
        # berkas lalu disilangkan dengan daftar — pola lama menyusun
        # regex per nama dan memindai ulang seluruh berkas untuk
        # tiap nama (90 nama x 46 berkas).
        pola_kandidat = re.compile(r"(\w+)\s*\(|::\s*(\w+)")
        berkas_pertama = {}
        for p, isi in isi_java:
            for cocok in pola_kandidat.finditer(isi):
                nama = cocok.group(1) or cocok.group(2)
                if nama in BEKAS_HAPUSAN and nama not in berkas_pertama:
                    berkas_pertama[nama] = p
        for nama in sorted(BEKAS_HAPUSAN):
            if nama in berkas_pertama:
                gagal.append("Pohon masih memanggil %s() yang sudah "
                             "dihapus (di %s) — regresi hapusan di luar "
                             "rentang diff" % (nama, berkas_pertama[nama]))

def _impor_yaml(gagal):
    """PyYAML wajib ada: tanpa itu validasi YAML mati diam-diam sementara
    gerbang tetap LOLOS (Audit 758) — gagal keras adalah satu-satunya sikap
    yang jujur untuk sebuah gerbang verifikasi."""
    try:
        import yaml
        return yaml
    except Exception:
        pesan = ("PyYAML tak tersedia — validasi YAML tak bisa berjalan; "
                 "pasang dulu (pip install pyyaml) agar gerbang tak "
                 "lolos buta")
        # Pintu ini dipanggil beberapa fungsi cek untuk akar yang sama:
        # laporkan sekali saja (Audit 770).
        if pesan not in gagal:
            gagal.append(pesan)
        return None


WORKFLOW_WAJIB = ("auto-merge.yml", "build-apk.yml", "build-binary.yml",
                  "cek-wajib.yml", "codeql.yml", "gitleaks.yml",
                  "label-audit.yml", "labeler.yml", "stale.yml")

BERKAS_CI_WAJIB = (".github/actions/resolve-vw/action.yml",)


def cek_workflow_yaml(gagal, peringatan):
    folder = os.path.join(AKAR, ".github", "workflows")
    if not os.path.isdir(folder):
        gagal.append("Folder .github/workflows hilang — automasi repo "
                     "tak bisa dicek")
        return
    for nama in WORKFLOW_WAJIB:
        if not os.path.isfile(os.path.join(folder, nama)):
            gagal.append("Workflow wajib hilang: .github/workflows/%s "
                         "(menghapus workflow wajib harus mengubah daftar "
                         "WORKFLOW_WAJIB di gerbang ini secara sadar)" % nama)
    for rel in BERKAS_CI_WAJIB:
        if not os.path.isfile(os.path.join(AKAR, rel)):
            gagal.append("Berkas CI wajib hilang: %s" % rel)
    yaml = _impor_yaml(gagal)
    if yaml is None:
        return
    for nama in sorted(os.listdir(folder)):
        if not nama.endswith((".yml", ".yaml")):
            continue
        try:
            with open(os.path.join(folder, nama), encoding="utf-8") as f:
                data = yaml.safe_load(f)
        except Exception as e:
            gagal.append("YAML workflow %s tak bisa di-parse: %s" % (nama, e))
            continue
        if not isinstance(data, dict):
            gagal.append("YAML workflow %s kosong/bukan peta di level atas "
                         "— workflow tak akan jalan" % nama)
            continue
        if not (("on" in data) or (True in data)) or "jobs" not in data:
            gagal.append("YAML workflow %s tanpa kunci on/jobs — workflow "
                         "tak akan jalan" % nama)

def _kumpul_glob_labeler(node, kumpul):
    if isinstance(node, str):
        kumpul.append(node)
    elif isinstance(node, list):
        for item in node:
            _kumpul_glob_labeler(item, kumpul)
    elif isinstance(node, dict):
        for nilai in node.values():
            _kumpul_glob_labeler(nilai, kumpul)

_RENTANG_KURAWAL = None


def _opsi_rentang(isi):
    """Isi grup berupa rentang {x..y[..langkah]} -> daftar nilainya;
    None bila bukan rentang. Selaras brace-expansion: ujung berupa
    bilangan bulat atau satu huruf, langkah bilangan bulat tak nol.
    Jumlah nilai dibatasi 512 agar pola liar tak meledak
    (512 jauh di atas rentang wajar mana pun di labeler, tetapi tetap
    membatasi biaya glob; batas 64 sebelumnya menolak rentang sah
    seperti {1..100} — Audit 635)."""
    global _RENTANG_KURAWAL
    if _RENTANG_KURAWAL is None:
        import re
        _RENTANG_KURAWAL = re.compile(
            r"^(-?\d+|.)\.\.(-?\d+|.)(?:\.\.(-?\d+))?$", re.DOTALL)
    m = _RENTANG_KURAWAL.match(isi)
    if not m:
        return None
    a, b, langkah = m.group(1), m.group(2), m.group(3)
    if a.lstrip("-").isdigit() and b.lstrip("-").isdigit():
        awal, akhir = int(a), int(b)
        if langkah is None:
            langkah = 1 if akhir >= awal else -1
        else:
            langkah = int(langkah)
        if langkah == 0:
            return None
        lebar = 0
        for ujung in (a, b):
            t = ujung.lstrip("-")
            if len(t) > 1 and t.startswith("0"):
                lebar = max(lebar, len(t))
        nilai = []
        v = awal
        while (v <= akhir) if langkah > 0 else (v >= akhir):
            if lebar:
                nilai.append(("-" if v < 0 else "")
                             + str(abs(v)).rjust(lebar, "0"))
            else:
                nilai.append(str(v))
            if len(nilai) > 512:
                return None
            v += langkah
        return nilai
    if len(a) == 1 and len(b) == 1:
        awal, akhir = ord(a), ord(b)
        if langkah is None:
            step = 1 if akhir >= awal else -1
        else:
            step = int(langkah)
        if step == 0:
            return None
        nilai = []
        v = awal
        while (v <= akhir) if step > 0 else (v >= akhir):
            nilai.append(chr(v))
            if len(nilai) > 512:
                return None
            v += step
        return nilai
    return None


def _ekspansi_kurawal(pola):
    """Ekspansi grup kurawal ala minimatch (glob Python tak mendukungnya);
    rekursif untuk beberapa grup dan grup bersarang: tutup grup dicari
    dengan menghitung kedalaman, isi dipecah hanya pada koma terluar
    (Audit 602; sarang ditambah Audit 605). Selaras brace-expansion,
    grup hanya diekspansi bila berisi daftar koma atau rentang `..`:
    grup unsur tunggal tanpa koma ({md}) diperlakukan LITERAL, dan
    kurung tak ditutup dikembalikan apa adanya (Audit 623)."""
    i = pola.find("{")
    if i == -1:
        return [pola]
    dalam = 0
    j = -1
    for k in range(i, len(pola)):
        if pola[k] == "{":
            dalam += 1
        elif pola[k] == "}":
            dalam -= 1
            if dalam == 0:
                j = k
                break
    if j == -1:
        return [pola]
    isi = pola[i + 1:j]
    opsi = []
    dalam = 0
    potong = 0
    for k, c in enumerate(isi):
        if c == "{":
            dalam += 1
        elif c == "}":
            dalam -= 1
        elif c == "," and dalam == 0:
            opsi.append(isi[potong:k])
            potong = k + 1
    opsi.append(isi[potong:])
    if len(opsi) == 1:
        opsi = _opsi_rentang(isi)
        if opsi is None:
            # Bukan grup yang sah untuk diekspansi: biarkan literal,
            # lanjutkan ekspansi pada sisa pola sesudah grup ini.
            return [pola[:j + 1] + sisa
                    for sisa in _ekspansi_kurawal(pola[j + 1:])]
    hasil = []
    for alt in opsi:
        hasil.extend(_ekspansi_kurawal(pola[:i] + alt + pola[j + 1:]))
    return hasil

def cek_labeler_glob(data_labeler, gagal):
    # Pola labeler yang salah ketik menunjuk path tak ada: label diam-diam
    # berhenti menempel pada PR tanpa sinyal apa pun (Audit 585). Dulu
    # hanya pola ber-wildcard yang diperiksa dan hanya awalan statisnya;
    # pola literal tanpa wildcard dan pola yang cocok ke 0 berkas (folder
    # ada, ekstensinya tak pernah ada) lolos tanpa sinyal (Audit 594).
    # Kini SEMUA pola wajib sungguh mencocokkan minimal satu path, dengan
    # kurung kurawal minimatch diekspansi lebih dulu (Audit 602).
    import glob as globmod
    kumpul = []
    _kumpul_glob_labeler(data_labeler, kumpul)
    for pola in kumpul:
        cocok = []
        for varian in _ekspansi_kurawal(pola):
            cocok = globmod.glob(varian, root_dir=AKAR, recursive=True)
            if cocok:
                break
        if not cocok:
            gagal.append("labeler.yml: pola %r tak mencocokkan satu pun "
                         "berkas/folder — label tak akan pernah menempel "
                         "untuk pola itu" % pola)

EKOSISTEM_DEPENDABOT = {
    "bundler", "cargo", "composer", "docker", "elm", "github-actions",
    "gomod", "gradle", "maven", "mix", "npm", "nuget", "pip", "pnpm",
    "pub", "swift", "terraform", "yarn",
}

def _muat_yaml_github(rel, gagal):
    """Baca+parse satu berkas YAML .github; kembalikan datanya atau None."""
    path = os.path.join(AKAR, rel)
    if not os.path.isfile(path):
        gagal.append("Berkas %s hilang — automasi yang mengandalkannya "
                     "berhenti diam-diam" % rel)
        return None
    yaml = _impor_yaml(gagal)
    if yaml is None:
        return None
    try:
        with open(path, encoding="utf-8") as f:
            data = yaml.safe_load(f)
    except Exception as e:
        gagal.append("YAML %s tak bisa di-parse: %s" % (rel, e))
        return None
    if not isinstance(data, dict):
        gagal.append("YAML %s kosong/bukan peta di level atas" % rel)
        return None
    return data

def _potong_komentar_shell(baris):
    """Buang komentar shell dari satu baris: `#` di luar kutip yang
    mengawali kata (sesudah awal baris/spasi/`;`/`&`/`|`) membuka
    komentar — teks sesudahnya tak dieksekusi shell dan tak boleh
    dihitung sebagai perintah (Audit 881)."""
    kutip = None
    tikus = False
    # Keadaan di-dalam-kata yang EFEKTIF (Audit 923): ditentukan dari
    # pemrosesan, bukan karakter mentah sebelumnya — spasi ter-escape
    # adalah bagian kata, bukan pemisah.
    kata = False
    i = 0
    n = len(baris)
    while i < n:
        c = baris[i]
        if kutip:
            if c == "\\" and kutip == '"':
                i += 2
                continue
            if c == kutip:
                kutip = None
            i += 1
            continue
        if c in ("'", '"'):
            kutip = c
            kata = True
            i += 1
            continue
        if c == "\\" and i + 1 < n:
            # Karakter ter-escape adalah literal (Audit 918) —
            # selaras penilai yang sadar escape sejak Audit 908.
            kata = True
            i += 2
            continue
        if c == "`":
            # Di dalam substitusi gaya lama, `#` bukan komentar baris
            # ini (Audit 907).
            tikus = not tikus
            kata = True
            i += 1
            continue
        if c == "#" and not tikus and not kata:
            return baris[:i]
        if c in " \t;&|()":
            # Pemisah tak ter-escape mengakhiri kata; `(` juga: di
            # belakang kurung buka perintah, `#` mengawali kata baru
            # (padanan `)` pada Audit 884).
            kata = False
        else:
            kata = True
        i += 1
    return baris


def pecah_segmen_shell(baris):
    """Pecah satu baris shell menjadi segmen perintah pada `;`,
    `&&`, dan `||` yang berada DI LUAR kutip (Audit 874, 888):
    pemisah di dalam teks berkutip adalah data, bukan pemisah
    perintah — memecah buta di sana mengarang segmen semu dari isi
    string."""
    segmen = []
    kini = []
    i = 0
    n = len(baris)
    kutip = ""
    dalam = 0
    tikus = False
    while i < n:
        c = baris[i]
        if kutip:
            kini.append(c)
            if c == "\\" and kutip == '"' and i + 1 < n:
                kini.append(baris[i + 1])
                i += 2
                continue
            if c == kutip:
                kutip = ""
            i += 1
            continue
        if c in ("'", '"'):
            kutip = c
            kini.append(c)
            i += 1
            continue
        if c == "`":
            # Substitusi gaya lama: pemisah di dalamnya bukan pemisah
            # perintah luar (Audit 906).
            tikus = not tikus
            kini.append(c)
            i += 1
            continue
        if c == "$" and i + 1 < n and baris[i + 1] == "(":
            dalam += 1
            kini.append(c)
            kini.append(baris[i + 1])
            i += 2
            continue
        if c in "><" and i + 1 < n and baris[i + 1] == "(":
            dalam += 1
            kini.append(c)
            kini.append(baris[i + 1])
            i += 2
            continue
        if c == "(" and dalam > 0:
            dalam += 1
            kini.append(c)
            i += 1
            continue
        if c == ")" and dalam > 0:
            dalam -= 1
            kini.append(c)
            i += 1
            continue
        if c == ";" and dalam == 0 and not tikus:
            segmen.append("".join(kini))
            kini = []
            i += 1
            continue
        if (c == "&" and dalam == 0 and not tikus
                and i + 1 < n and baris[i + 1] == "&"):
            segmen.append("".join(kini))
            kini = []
            i += 2
            continue
        if (c == "|" and dalam == 0 and not tikus
                and i + 1 < n and baris[i + 1] == "|"):
            # `||` juga pemisah perintah (Audit 888): perintah
            # sesudahnya adalah perintah sungguhan, bukan sufiks.
            segmen.append("".join(kini))
            kini = []
            i += 2
            continue
        kini.append(c)
        i += 1
    segmen.append("".join(kini))
    return segmen


def _potong_pipa_pertama(bagian):
    """Audit 1022: potong segmen pada pipa tunggal PERTAMA di luar
    kutip/substitusi — dalam satu jaringan pipa, hanya elemen
    pertama yang membaca stdin bersama; elemen berikutnya membaca
    keluaran elemen sebelumnya. `||` bukan pipa (pemisah perintah,
    sudah dipecah pemecah segmen); `|&` adalah pipa juga."""
    i = 0
    n = len(bagian)
    kutip = ""
    dalam = 0
    tikus = False
    while i < n:
        c = bagian[i]
        if kutip:
            if c == "\\" and kutip == '"' and i + 1 < n:
                i += 2
                continue
            if c == kutip:
                kutip = ""
            i += 1
            continue
        if c in ("'", '"'):
            kutip = c
            i += 1
            continue
        if c == "`":
            tikus = not tikus
            i += 1
            continue
        if c == "$" and i + 1 < n and bagian[i + 1] == "(":
            dalam += 1
            i += 2
            continue
        if c in "><" and i + 1 < n and bagian[i + 1] == "(":
            dalam += 1
            i += 2
            continue
        if c == "(" and dalam > 0:
            dalam += 1
            i += 1
            continue
        if c == ")" and dalam > 0:
            dalam -= 1
            i += 1
            continue
        if c == "|" and dalam == 0 and not tikus:
            if i + 1 < n and bagian[i + 1] == "|":
                i += 2
                continue
            return bagian[:i]
        i += 1
    return bagian


def _substitusi_masukan_menguras(bagian):
    """Audit 1024/1036: True bila segmen memuat substitusi proses
    masukan <(...) ATAU substitusi perintah $(...) di luar kutip
    tunggal yang isinya menguras stdin bersama — proses di dalam
    keduanya mewarisi stdin shell, lepas dari redirect perintah
    luarnya. $((...)) adalah aritmetika, bukan substitusi. Di
    dalam kutip ganda substitusi tetap berjalan, jadi pemindaian
    tidak berhenti di sana. Audit 1045: substitusi backtick
    dinilai dengan cara yang sama — isinya perintah yang berjalan
    dengan stdin warisan."""
    i = 0
    n = len(bagian)
    kutip = ""
    tikus = False
    while i < n:
        c = bagian[i]
        if kutip == "'":
            if c == "'":
                kutip = ""
            i += 1
            continue
        if kutip == '"':
            if c == "\\" and i + 1 < n:
                i += 2
                continue
            if c == '"':
                kutip = ""
                i += 1
                continue
            if c == "`":
                # Audit 1045: backtick juga hidup di kutip ganda.
                j = i + 1
                while j < n:
                    if bagian[j] == "\\" and j + 1 < n:
                        j += 2
                        continue
                    if bagian[j] == "`":
                        break
                    j += 1
                isi = bagian[i + 1:j]
                if isi.strip() and any(
                        _menguras_stdin(s)
                        for s in pecah_segmen_shell(isi)):
                    return True
                i = j + 1 if j < n else n
                continue
            if not (c == "$" and i + 1 < n and bagian[i + 1] == "("):
                i += 1
                continue
        else:
            if c == "\\" and i + 1 < n:
                i += 2
                continue
            if c in ("'", '"'):
                kutip = c
                i += 1
                continue
            if c == "`" and not tikus:
                # Audit 1045: substitusi backtick — nilai isinya
                # sampai backtick penutup (yang ter-escape dengan
                # \ di dalam tidak menutup).
                j = i + 1
                while j < n:
                    if bagian[j] == "\\" and j + 1 < n:
                        j += 2
                        continue
                    if bagian[j] == "`":
                        break
                    j += 1
                isi = bagian[i + 1:j]
                if isi.strip() and any(
                        _menguras_stdin(s)
                        for s in pecah_segmen_shell(isi)):
                    return True
                i = j + 1 if j < n else n
                continue
            if c == "`":
                tikus = not tikus
                i += 1
                continue
        pembuka = False
        if not tikus and i + 1 < n and bagian[i + 1] == "(":
            if c == "<":
                pembuka = True
            elif c == "$" and not (i + 2 < n and bagian[i + 2] == "("):
                pembuka = True
        if pembuka:
            dalam = 1
            j = i + 2
            k2 = ""
            t2 = False
            while j < n and dalam > 0:
                cj = bagian[j]
                if k2:
                    if cj == "\\" and k2 == '"' and j + 1 < n:
                        j += 2
                        continue
                    if cj == k2:
                        k2 = ""
                    j += 1
                    continue
                if cj in ("'", '"'):
                    k2 = cj
                    j += 1
                    continue
                if cj == "`":
                    t2 = not t2
                    j += 1
                    continue
                if cj == "(":
                    dalam += 1
                elif cj == ")":
                    dalam -= 1
                    if dalam == 0:
                        break
                j += 1
            isi = bagian[i + 2:j]
            for seg in pecah_segmen_shell(isi):
                if _menguras_stdin(seg):
                    return True
            i = j + 1
            continue
        i += 1
    return False


def _potong_komentar_sisa(sisa):
    """Potong sisa segmen pada `#` yang mengawali kata saja (posisi
    awal atau sesudah spasi/tab) — `#` yang menempel pada kata
    adalah bagian kata itu bagi shell (Audit 882)."""
    for idx, ch in enumerate(sisa):
        if ch == "#" and (idx == 0 or sisa[idx - 1] in " \t"):
            return sisa[:idx]
    return sisa


# Perintah-perintah yang lazim mengonsumsi stdin sampai habis —
# dipakai menilai isi substitusi proses multi-perintah (Audit 931).
_PEMBACA_STDIN = {"cat", "tee", "head", "tail", "wc", "dd", "sort",
                  "uniq",
                  # Audit 1037: filter aliran murni — tanpa operand
                  # berkas membaca stdin sampai habis.
                  "cut", "tr", "paste", "fold", "nl", "tac", "rev",
                  # Audit 1359: alat akun shadow-utils — tanpa
                  # operand berkas membaca daftar dari stdin.
                  "chpasswd", "newusers",
                  # Audit 1397: chgpasswd — cermin chpasswd
                  # untuk grup; pola yang sama persis.
                  "chgpasswd",
                  # Audit 1376: shell CPAN tanpa argumen
                  # membaca perintah dari stdin.
                  "cpan",
                  # Audit 1398: pl2pm tanpa operand menerjemahkan
                  # stdin ke stdout (operand berkas menekan).
                  "pl2pm",
                  # Audit 1378: utmpdump tanpa operand
                  # membaca rekaman dari stdin.
                  # Audit 1425-1427/1429: keluarga server
                  # Assuan & pengurai NSS — tanpa operand
                  # membaca protokol/objek dari stdin.
                  "pinentry", "pinentry-curses", "dirmngr",
                  "gpg-connect-agent", "derdump",
                  # Audit 1639: gpgv — verifikator tanda tangan;
                  # tanpa operand data membaca dari stdin.
                  "gpgv",
                  "utmpdump",
                  "shuf", "base64", "od", "sha256sum", "md5sum",
                  "gzip", "gunzip",
                  # Audit 1055: sisa keluarga checksum GNU (aturan
                  # operand sama dengan sha256sum/md5sum).
                  "sha1sum", "sha224sum", "sha384sum", "sha512sum",
                  "b2sum", "cksum", "sum",
                  # Audit 1056: filter teks tingkat-3 (operand
                  # berkas menekan; factor/logger operandnya nilai/
                  # pesan sehingga vonis operand generik selaras).
                  "fmt", "pr", "expand", "unexpand", "tsort", "ptx",
                  "split", "factor", "strings", "base32",
                  "iconv", "logger", "numfmt",
                  # Audit 1057: kompresor saudara gzip + zcat.
                  "xz", "bzip2", "lzma", "zcat",
                  # Audit 1801: column util-linux -
                  # membaca stdin bila tanpa berkas.
                  "column", "gzcat",
                  # Audit 1638: kompresor generasi berikut —
                  # tanpa operand membaca stdin seperti gzip.
                  "zstd", "pigz",
                  # Audit 1642: varian multithread zstd.
                  "zstdmt",
                  # Audit 1098: varian nama kompresor yang sama
                  # perilakunya (dekompresi ke stdout).
                  "bunzip2", "bzcat", "xzcat", "unxz", "unlzma",
                  # Audit 1199: lzcat setara unlzma, tertinggal
                  # dari daftar.
                  "lzcat",
                  # Audit 1638: varian dekompresi zstd/pigz.
                  "unzstd", "zstdcat", "unpigz",
                  # Audit 1806: plzip - tanpa berkas
                  # atau berkas '-' dari stdin (manual).
                  "plzip",
                  # Audit 1807: keluarga lbzip2 - tanpa
                  # FILE bekerja sebagai filter stdin.
                  "lbzip2", "lbunzip2", "lbzcat",
                  # Audit 1808: pixz - bawaannya stdin/
                  # stdout tanpa argumen INPUT/OUTPUT.
                  "pixz",
                  # Audit 1809: compress - pasangan
                  # uncompress yang sudah anggota.
                  "compress",
                  # Audit 1810: keluarga brotli - tanpa
                  # berkas atau '-' membaca stdin.
                  "brotli", "unbrotli", "brcat",
                  # Audit 1811: lzop - tanpa berkas atau
                  # '-' mengompres stdin.
                  "lzop",
                  # Audit 1812: lziprecover - mode
                  # dekompresi/uji tanpa berkas stdin.
                  "lziprecover",
                  # Audit 1813: keluarga dos2unix - tanpa
                  # FILE adalah filter stdin.
                  "dos2unix", "unix2dos", "mac2unix",
                  "unix2mac",
                  # Audit 1112: pembaca interaktif/kueri — operand
                  # berkas/nama menekan seperti filter biasa.
                  "wall", "c++filt", "nslookup",
                  # Audit 1101: basenc TIDAK di sini — tanpa mode
                  # ia galat dan tak membaca; cabangnya khusus.
                  # Audit 1038: keluarga beroperand skrip/pola —
                  # operand pertamanya bukan berkas (penilaian
                  # posisinya di _menguras_keluarga_skrip).
                  "sed", "awk", "grep", "jq",
                  # Audit 1067: varian nama keluarga yang sama
                  # (dipetakan ke nama kanonis di penilainya).
                  "mawk", "nawk", "egrep", "fgrep", "rg",
                  # Audit 1638: varian GNU awk.
                  "gawk", "pgawk", "igawk",
                  # Audit 1652: debugger GNU awk.
                  "dgawk",
                  # Audit 1244: grep terkompresi — pola
                  # pertamanya bukan berkas (penilai keluarga).
                  "zgrep", "bzgrep", "xzgrep", "lzgrep",
                  # Audit 1642: grep terkompresi zstd.
                  "zstdgrep",
                  # Audit 1306: egrep/fgrep gzip.
                  "zegrep", "zfgrep",
                  # Audit 1307: egrep/fgrep bzip2.
                  "bzegrep", "bzfgrep",
                  # Audit 1308: keluarga xz.
                  "xzegrep", "xzfgrep",
                  # Audit 1309: keluarga lzma.
                  "lzegrep", "lzfgrep",
                  # Audit 1310: varian nama gzip.
                  "uncompress",
                  # Audit 1313: penulis zip aliran.
                  "streamzip",
                  # Audit 1314: pager terkompresi.
                  "zless", "zmore", "bzless", "bzmore",
                  "xzless", "xzmore", "lzless", "lzmore",
                  # Audit 1315: pager sistem.
                  "sensible-pager", "pager",
                  # Audit 1324: seleksi debconf.
                  "debconf-set-selections",
                  # Audit 1336: keluarga POD lain.
                  "pod2html", "pod2man", "pod2usage",
                  "podchecker",
                  # Audit 1337: penjelas galat Perl.
                  "splain",
                  # Audit 1338: shell modul Perl.
                  "instmodsh",
                  # Audit 1113: varian awk orisinal.
                  "original-awk",
                  # Audit 1196: pemulih tabel firewall membaca
                  # aturannya dari stdin secara bawaan.
                  "iptables-restore", "ip6tables-restore",
                  # Audit 1257: varian nama pemulih tabel.
                  "iptables-nft-restore",
                  "iptables-legacy-restore",
                  "ip6tables-nft-restore",
                  "ip6tables-legacy-restore", "arptables-restore",
                  "ebtables-restore",
                  # Audit 1245/1246/1247/1248/1260: alias
                  # checksum, konverter, POD, penebak encoding.
                  "shasum", "md5sum.textutils", "uconv",
                  "piconv", "pod2text", "chardetect",
                  # Audit 1191/1192: funzip mengekstrak anggota
                  # pertama arsip zip dari stdin; json_pp
                  # memformat JSON dari stdin.
                  "funzip", "json_pp"}


# Audit 1407: bendera info huruf tunggal per perintah —
# mencetak versi/bantuan lalu keluar tanpa membaca stdin
# (penjaga informasional Audit 1102 hanya mengenal bentuk
# panjang --help/--version).
_INFO_HURUF = {"cpan": {"-v", "-V", "-h"},
               "utmpdump": {"-h"},
               # Audit 1423: pl2pm (Audit 1398) ketinggalan
               # dari tabel ini.
               "pl2pm": {"-v", "-h"}}

_OPSI_BERNILAI = {
    "head": {"-n", "-c", "--lines", "--bytes"},
    "tail": {"-n", "-c", "--lines", "--bytes", "--pid", "--sleep",
             # Audit 1166: nama resmi --sleep-interval (--sleep
             # hanya singkatan getopt yang kebetulan lolos).
             "--sleep-interval", "--max-unchanged-stats"},
    "sort": {"-k", "-o", "-t", "-S", "-T", "--random-source",
             "--parallel", "--buffer-size", "--compress-program",
             "--files0-from", "--batch-size",
             # Audit 1164: bentuk panjang yang hilang.
             "--key", "--field-separator",
             "--temporary-directory", "--output"},
    "uniq": {"-f", "-s", "-w", "--skip-fields", "--check-chars",
             # Audit 1165: --skip-chars hilang.
             "--skip-chars"},
    # Audit 1141: --group uniq DIBUANG dari tabel — argumennya
    # opsional dan hanya lewat '='; memasukkannya membuat operand
    # berkas tertelan sebagai "nilai".
    # Audit 1100: entri yang dulu absen/sebagian — nilai opsinya
    # terbaca operand berkas (fail-open).
    # Audit 1264: --total wc bernilai OPSIONAL (hanya =).
    "wc": {"--files0-from"},
    "cksum": {"-a", "--algorithm",
              # Audit 1171: -l/--length (sah untuk blake2b).
              "-l", "--length"},
    "b2sum": {"-l", "--length"},
    "base32": {"-w", "--wrap"},
    "logger": {"-t", "--tag", "-p", "--priority", "-u",
               # Audit 1212: --id bernilai OPSIONAL (hanya lewat
               # '=') — diperlakukan bendera.
               "--socket", "-n", "--server", "-P", "--port", "-S",
               "--size", "--journald",
               # Audit 1170: opsi sd/journal bernilai.
               "--msgid", "--sd-id", "--sd-param"},
    "ptx": {"-M", "--macro-name",
            "-S", "--sentence-regexp", "-T", "--format",
            "-b", "--break-file", "-i", "--ignore-file",
            # Audit 1235: -F/--flag-truncation ptx bernilai.
            "-F", "--flag-truncation",
            # Audit 1174: -o/--only-file hilang.
            "-o", "--only-file",
            # Audit 1210: pasangan tertukar dibetulkan — gap =
            # -g, width = -w, word-regexp = -W (bernilai);
            # -G/--traditional dan -f/--ignore-case adalah
            # BENDERA, bukan bernilai.
            "-g", "--gap-size", "-w", "--width",
            "-W", "--word-regexp"},
    # Audit 1037: opsi bernilai filter aliran.
    "cut": {"-d", "-f", "-c", "--delimiter", "--fields",
            "--characters", "--bytes", "--output-delimiter"},
    "fold": {"-w", "--width"},
    "nl": {"-b", "-d", "-f", "-h", "-i", "-l", "-n", "-s", "-v",
           "-w", "--body-numbering",
           # Audit 1209: nama resmi pasangan -d adalah
           # --section-delimiter (--delimiter bukan nama resmi);
           # --join-blank-lines (pasangan -l) hilang.
           "--section-delimiter", "--join-blank-lines",
           "--footer-numbering", "--header-numbering",
           "--number-format", "--number-separator",
           "--starting-line-number", "--line-increment",
           "--number-width"},
    "paste": {"-d", "--delimiters"},
    "base64": {"-w", "--wrap", "-b", "--break"},
    "od": {"-A", "--address-radix", "-j", "--skip-bytes", "-N",
           "--read-bytes", "-t", "--format",
           # Audit 1173: --endian bernilai.
           "--endian",
           # Audit 1241: -S bernilai terpisah; -w/--width
           # bernilai OPSIONAL (hanya merekat/=) = bendera.
           "-S"},
    "gzip": {"--suffix", "-S"},
    "gunzip": {"--suffix", "-S"},
    # Audit 1048: tac tertinggal dari tabel saat ditambahkan
    # Audit 1037 — nilai -s dinilai operand berkas.
    "tac": {"-s", "--separator"},
    "shuf": {"-n", "--head-count", "-o", "--output",
             "--random-source", "-i", "--input-range"},
    # Audit 1056: opsi bernilai filter tingkat-3.
    # Audit 1100: fmt -p/--prefix tertinggal.
    "fmt": {"-w", "--width", "-g", "--goal", "-p", "--prefix"},
    # Audit 1167: -d pr adalah BENDERA --double-space; yang
    # bernilai adalah -D/--date-format (dulu tertukar).
    "pr": {"-w", "--width", "-l", "--length",
           "-o", "--indent", "-h", "--header",
           "-D", "--date-format",
           # Audit 1208: --columns/-N/-W hilang (fail-open);
           # -n/--sep-string bernilai OPSIONAL (hanya menempel/=)
           # sehingga diperlakukan bendera; --number-width bukan
           # opsi pr (milik nl); pasangan -s adalah --separator.
           "--columns", "-N", "--first-line-number",
           # Audit 1234: -s/--separator pr bernilai OPSIONAL
           # juga (hanya menempel/=) — diperlakukan bendera.
           "-W", "--page-width"},
    "expand": {"-t", "--tabs"},
    "unexpand": {"-t", "--tabs"},
    "split": {"-l", "--lines", "-b", "--bytes", "-C",
              "--line-bytes", "-n", "--number", "-a",
              "--suffix-length", "-t", "--separator",
              # Audit 1169: --filter (perintah per potongan!)
              # dan --additional-suffix bernilai.
              "--filter", "--additional-suffix"},
    "strings": {"-n", "--bytes", "-t", "--radix", "-e",
                "--encoding",
                # Audit 1168: -T/--target bernilai.
                "-T", "--target",
                # Audit 1242: -s/-U strings bernilai, hilang.
                "-s", "--output-separator", "-U", "--unicode"},
    "iconv": {"-f", "--from-code", "-t", "--to-code", "-o",
              "--output", "--byte-subst", "--widechar-subst",
              "--unicode-subst"},
    "numfmt": {"-d", "--delimiter", "--field", "--format",
               "--padding",
               # Audit 1265: --grouping numfmt adalah BENDERA.
               # Audit 1125: opsi konversi bernilai yang hilang.
               "--from", "--to", "--from-unit", "--to-unit",
               "--invalid", "--suffix",
               # Audit 1294: --round hilang.
               "--round"},
    "basenc": {"-w", "--wrap"},
    # Audit 1057: opsi bernilai kompresor.
    "xz": {"-T", "--threads", "-C", "--check", "--block-size",
           "--memlimit-compress", "--memlimit-decompress",
           # Audit 1172: -F/--format bernilai.
           "-F", "--format"},
    # Audit 1639: opsi bernilai gpgv.
    # Audit 1648: --weak-digest terprobe real menguras;
    # --output/-o & --log-file bernilai menurut man gpgv(1).
    "gpgv": {"--keyring", "--homedir", "--status-fd",
             "--logger-fd", "--weak-digest", "--output", "-o",
             "--log-file", "--debug", "--assert-pubkey-algo"},
    # Audit 1638: opsi bernilai kompresor zstd/pigz.
    "zstd": {"-o", "--output", "-D", "-T", "--threads",
             # Audit 1642: -M/--memory & --format bernilai.
             "-M", "--memory", "--format"},
    "unzstd": {"-o", "--output", "-D", "-T", "--threads",
               "-M", "--memory", "--format"},
    "zstdcat": {"-D", "-T", "--threads",
                "-M", "--memory", "--format"},
    # Audit 1642: zstdmt varian multithread zstd.
    "zstdmt": {"-o", "--output", "-D", "-T", "--threads",
               "-M", "--memory", "--format"},
    "pigz": {"-p", "--processes", "-b", "--blocksize", "-S",
             "--suffix"},
    "unpigz": {"-p", "--processes", "-b", "--blocksize", "-S",
               "--suffix"},
    # Audit 1211: wall selama ini tanpa entri — -t bernilai.
    "wall": {"-t", "--timeout",
            # Audit 1236: -g/--group wall bernilai.
            "-g", "--group"},
    # Audit 1213: kompresor tanpa entri untuk opsi bersamanya.
    "lzma": {"-F", "--format", "-S", "--suffix", "-T", "--threads",
             "--block-size", "-C", "--check"},
    "xzcat": {"-F", "--format", "-T", "--threads",
              # Audit 1237: -S/--suffix hilang dari entri ini.
              "-S", "--suffix",
              # Audit 1292: --block-size (entri xz memuatnya).
              "--block-size"},
    # Audit 1238: lzcat tanpa entri sama sekali (Audit 1199
    # hanya menambah namanya ke daftar pembaca).
    "lzcat": {"-F", "--format", "-S", "--suffix", "-T",
              "--threads", "--block-size", "-C", "--check"},
    "zcat": {"-S", "--suffix"},
    # Audit 1801: opsi bernilai column util-linux.
    "column": {"-s", "--separator", "-c", "--columns",
               "-o", "--output-separator"},
    # Audit 1802: gzcat adalah nama varian zcat.
    "gzcat": {"-S", "--suffix"},
    # Audit 1819: opsi bernilai plzip.
    "plzip": {"-n", "--threads", "--dictionary-size",
              "--match-length", "--format"},
    # Audit 1196: opsi bernilai pemulih tabel firewall.
    "iptables-restore": {"-T", "--table", "-M", "--modprobe",
                         "-w", "--wait",
                         # Audit 1293: -W/--wait-interval.
                         "-W", "--wait-interval"},
    "ip6tables-restore": {"-T", "--table", "-M", "--modprobe",
                          "-w", "--wait",
                         # Audit 1293: -W/--wait-interval.
                         "-W", "--wait-interval"},
    # Audit 1257: varian nama pemulih tabel lain.
    "iptables-nft-restore": {"-T", "--table", "-M", "--modprobe",
                             "-w", "--wait",
                         # Audit 1293: -W/--wait-interval.
                         "-W", "--wait-interval"},
    "iptables-legacy-restore": {"-T", "--table", "-M",
                                "--modprobe", "-w", "--wait",
                         # Audit 1293: -W/--wait-interval.
                         "-W", "--wait-interval"},
    "ip6tables-nft-restore": {"-T", "--table", "-M",
                              "--modprobe", "-w", "--wait",
                         # Audit 1293: -W/--wait-interval.
                         "-W", "--wait-interval"},
    "ip6tables-legacy-restore": {"-T", "--table", "-M",
                                 "--modprobe", "-w", "--wait",
                         # Audit 1293: -W/--wait-interval.
                         "-W", "--wait-interval"},
    "arptables-restore": {"-T", "--table", "-M", "--modprobe",
                          "-w", "--wait",
                         # Audit 1293: -W/--wait-interval.
                         "-W", "--wait-interval"},
    "ebtables-restore": {"-T", "--table", "-M", "--modprobe",
                         "-w", "--wait",
                         # Audit 1293: -W/--wait-interval.
                         "-W", "--wait-interval"},
    # Audit 1243: json_pp tanpa entri (Audit 1192 hanya
    # menambah namanya ke daftar pembaca).
    "json_pp": {"-f", "--format",
                # Audit 1287: -t/-json_opt bernilai.
                "-t", "-json_opt"},
    # Audit 1246/1247/1248: konverter & pembaca POD.
    "uconv": {"-f", "--from-code", "-t", "--to-code", "-o",
              "--output", "-x", "--translit",
              # Audit 1288: -b/--block-size & --callback.
              "-b", "--block-size", "--callback"},
    "piconv": {"-f", "--from", "-t", "--to", "-o", "--output",
               # Audit 1289: -S/--scheme (juga -C & -s).
               "-S", "--scheme", "-C", "-s", "--string"},
    "pod2text": {"-o", "--output", "-w", "--width", "-i",
                 "--indent", "-q", "--quotes", "--errors",
                 # Audit 1290: -e/--encoding, --guesswork,
                 # -m/--margin.
                 "-e", "--encoding", "--guesswork", "-m",
                 "--margin"},
    # Audit 1291: shasum selama ini tanpa entri — nilai -a
    # terbaca operand.
    "shasum": {"-a", "--algorithm"},
    # Audit 1359: opsi bernilai alat akun shadow-utils.
    "chpasswd": {"-c", "--crypt-method", "-s", "--sha-rounds",
                 "-R", "--root", "--prefix"},
    # Audit 1397: opsi bernilai chgpasswd sama dengan chpasswd.
    "chgpasswd": {"-c", "--crypt-method", "-s", "--sha-rounds",
                  "-R", "--root", "--prefix"},
    "newusers": {"-R", "--root", "--prefix"},
    # Audit 1336: keluarga POD lain.
    # Audit 1310: uncompress tanpa opsi bernilai.
    "uncompress": set(),
    # Audit 1313: streamzip tanpa opsi bernilai.
    "streamzip": set(),
    "zless": {"-p", "--pattern", "-P", "--prompt", "-o",
              "--log-file"},
    "zmore": set(),
    "bzless": {"-p", "--pattern", "-P", "--prompt", "-o",
               "--log-file"},
    "bzmore": set(),
    "xzless": {"-p", "--pattern", "-P", "--prompt", "-o",
               "--log-file"},
    "xzmore": set(),
    "lzless": {"-p", "--pattern", "-P", "--prompt", "-o",
               "--log-file"},
    "lzmore": set(),
    "sensible-pager": {"-p", "--pattern", "-P", "--prompt",
                       "-o", "--log-file"},
    "pager": {"-p", "--pattern", "-P", "--prompt", "-o",
              "--log-file"},
    # Audit 1310/1313/1337/1338/1324: entri kosong — vonis
    # mengikuti aturan operand generik.
    # Audit 1324: debconf-set-selections tanpa opsi bernilai.
    "debconf-set-selections": set(),
    "pod2man": {"-c", "--center", "-d", "--date", "-n",
                "--name", "-r", "--release", "-s", "--section",
                "--quotes"},
    "pod2html": {"--backlink", "--cachedir", "--css",
                 "--htmldir", "--htmlroot", "--infile",
                 "--outfile", "--podpath", "--podroot",
                 "--title"},
    "pod2usage": {"-exitval", "-output", "-verbose",
                  "-pathlist", "-formatter", "-msg"},
    "podchecker": {"--warnings", "-warnings"},
    # Audit 1314/1315: pager (opsi lazim less yang bernilai).
    # Audit 1337: splain tanpa opsi bernilai.
    "splain": set(),
    # Audit 1338: instmodsh tanpa opsi bernilai.
    "instmodsh": set(),
    # Audit 1098: saudara kompresor berbagi opsi sufiks.
    "bunzip2": {"--suffix", "-S"},
    "unxz": {"--suffix", "-S", "-T", "--threads",
             # Audit 1172: -F/--format bernilai.
             "-F", "--format"},
    "unlzma": {"--suffix", "-S"},
}


# Audit 957: opsi bernilai milik pembungkus perintah — nilai yang
# terpisah dari opsinya bukan perintah sungguhan.
_OPSI_PEMBUNGKUS = {
    "nice": {"-n", "--adjustment"},
    "stdbuf": {"-i", "--input", "-o", "--output", "-e", "--error"},
    "timeout": {"-s", "--signal", "-k", "--kill-after"},
    # Audit 1015: flock menjalankan perintah sesudah operand berkas
    # kunci; opsi bernilainya waktu tunggu dan kode keluar konflik.
    "flock": {"-w", "--timeout", "--wait", "-E",
              "--conflict-exit-code"},
    "xargs": {"-a", "--arg-file", "-E", "-I", "-L", "-n", "-P",
              "-s", "-d", "--eof", "--max-lines", "--max-args",
              "--max-procs", "--max-chars", "--delimiter"},
    # Audit 970: pembungkus transparan tambahan beserta opsi
    # bernilainya (setsid/eatmydata/nohup tanpa opsi bernilai).
    # Audit 989: -p/--pid ionice dan taskset BUKAN opsi bernilai —
    # keduanya bendera mode pid (operand sesudahnya pid, bukan
    # nilai opsi dan bukan perintah); lihat mode_pid di resolver.
    # Audit 1044: sudo — opsi bernilainya identitas/target/batas;
    # bendera polos (-n/-S/-b/-v/...) dilompati lingkar umum.
    "sudo": {"-u", "--user", "-g", "--group", "-h", "--host",
             "-p", "--prompt", "-t", "--command-timeout", "-C",
             "--close-from", "-T", "--command-timeout-fd", "-D",
             "--chdir", "-r", "--role", "-R", "--chroot"},
    # Audit 1065: unshare — opsi bernilai pemetaan id dan root.
    # Audit 1153: --propagation/--map-users/--map-groups hilang —
    # nilainya disangka perintah, blok habis 1121 tak tercapai.
    "unshare": {"--setgroups", "--map-user", "--map-group",
                "--setuid", "--setgid", "-R", "--root", "--wd",
                "--kill-child", "--propagation", "--map-users",
                "--map-groups"},
    # Audit 1155: setpriv — pembungkus hak istimewa (perintah
    # anak di operand pertama).
    "setpriv": {"--reuid", "--ruid", "--euid", "--regid",
                "--rgid", "--egid", "--groups", "--ambient-caps",
                "--bounding-set", "--inh-caps", "--securebits",
                "--pdeathsig", "--selinux-label",
                "--apparmor-profile"},
    # Audit 1156: prlimit — pembungkus batas sumber daya;
    # --pid mode pid (tanpa perintah hanya mencetak/mengubah
    # batas pid, tak membaca), perintah anak tetap dinilai.
    "prlimit": {"--pid", "--as", "--core", "--cpu", "--data",
                "--fsize", "--locks", "--memlock", "--msgqueue",
                "--nice", "--nofile", "--nproc", "--rss",
                "--rtprio", "--rttime", "--sigpending", "--stack"},
    # Audit 1070: systemd-cat — operandnya perintah (pembungkus);
    # tanpa operand ia meneruskan stdin-nya sendiri ke jurnal.
    "systemd-cat": {"-t", "--identifier", "-p", "--priority"},
    # Audit 1107: pembungkus putaran-2 — strace (perintah di
    # belakang opsi pelacak), dbus-run-session (sesi baru lalu
    # perintah operand), runuser (perintah operand sesudah -u).
    "strace": {"-o", "--output", "-p", "--attach", "-e", "--trace",
               "-s", "--string-limit", "-u", "--runas", "-E", "--env",
               "-a", "--align", "-I", "--interruptible", "-b",
               "--detach-on", "--trace-path", "--env"},
    "dbus-run-session": {"--config-file", "--dbus-daemon"},
    "runuser": {"-u", "--user", "-g", "--group", "-G",
                "--supp-group", "-s", "--shell"},
    # Audit 1107: su/sg ditangani blok khusus resolver (string -c
    # dinilai seperti flock); entri ini hanya untuk opsi lain.
    "su": {"-s", "--shell", "-g", "--group", "-G", "--supp-group"},
    "sg": set(),
    # Audit 1194: newgrp sekelas sg.
    "newgrp": set(),
    # Audit 1203: ssh-agent pembungkus perintah anak.
    "ssh-agent": {"-a", "-t", "-E", "-P"},
    # Audit 1254/1255/1256: pembungkus putaran-8.
    "uclampset": {"-m", "--min", "-M", "--max", "-p", "--pid"},
    "choom": {"-n", "--adjust", "-p", "--pid"},
    "xvfb-run": {"-n", "--server-num", "-s", "--server-args",
                 "-f", "--auth-file", "-e", "--error-file",
                 # Audit 1297: -p/--xauth-protocol.
                 "-p", "--xauth-protocol"},
    # Audit 1084: chroot dikenal resolver sejak 1065 tetapi tabel
    # opsinya kosong — --userspec/--groups bernilai tak dilompati.
    "chroot": {"--userspec", "--groups", "--skip-chdir"},
    "ionice": {"-c", "--class", "-n", "--classdata", "-u", "--which"},
    "taskset": {"-c", "--cpu-list"},
    "chrt": {"-T", "--sched-runtime", "-D", "--sched-deadline",
             "-P", "--sched-period"},
}


def _gt_di_luar_kutip(token):
    """True bila token memuat `>` di LUAR kutip dan tak ter-escape
    (Audit 952): `>` di dalam kutip — penuh maupun sebagian — adalah
    data nama berkas, bukan operator redirect."""
    kutip = ""
    i = 0
    n = len(token)
    while i < n:
        c = token[i]
        if kutip:
            if c == "\\" and kutip == '"' and i + 1 < n:
                i += 2
                continue
            if c == kutip:
                kutip = ""
            i += 1
            continue
        if c in ("'", '"'):
            kutip = c
            i += 1
            continue
        if c == "\\" and i + 1 < n:
            i += 2
            continue
        if c == ">":
            return True
        i += 1
    return False


def _awalan_sebelum_gt(token):
    """Awalan token sebelum `>` pertama di LUAR kutip dan tak
    ter-escape (Audit 959); None bila tak ada `>` semacam itu.
    Awalan itulah operand yang merekat ke redirect-nya."""
    kutip = ""
    i = 0
    n = len(token)
    while i < n:
        c = token[i]
        if kutip:
            if c == "\\" and kutip == '"' and i + 1 < n:
                i += 2
                continue
            if c == kutip:
                kutip = ""
            i += 1
            continue
        if c in ("'", '"'):
            kutip = c
            i += 1
            continue
        if c == "\\" and i + 1 < n:
            i += 2
            continue
        if c == ">":
            return token[:i]
        i += 1
    return None


def _fd_norm(fd):
    # Audit 1004: shell menilai nomor fd secara numerik — ejaan
    # "00"/"000" adalah fd 0 dan "01" adalah fd 1. Normalkan digit
    # murni dengan membuang nol di depan; nilai lain ("", "-",
    # nama berkas) dikembalikan apa adanya. Audit 1011: sufiks "-"
    # sesudah nomor fd adalah semantik pindah-fd bash — untuk
    # menilai SUMBER duplikasi ia setara nomornya saja ("0-" =
    # fd 0, sumber yang sama); "-" polos tetap penutup fd.
    if fd and len(fd) > 1 and fd.endswith("-") and fd[:-1].isdigit():
        fd = fd[:-1]
    if fd and fd.isdigit():
        return fd.lstrip("0") or "0"
    return fd


def _sumber_nol_pindah(target):
    # Audit 1016: target duplikasi "0-"/"00-" adalah pindah-fd
    # BERSUMBER fd 0 — bila fd tujuannya bukan 0, fd 0 perintah
    # dipindah ke sana lalu DITUTUP, jadi stdin perintah tertutup
    # dan tak menguras apa pun. Tujuan 0 = pindah ke diri sendiri
    # (no-op di bash), bukan penutupan.
    return (len(target) > 1 and target.endswith("-")
            and _fd_norm(target) == "0")


def _indeks_sesudah_redirect(toks, i):
    """Audit 965: indeks sesudah token redirect KELUARAN pada
    posisi i — bentuk murni ('>' dsb.) melompati targetnya juga,
    bentuk merekat ('>log', '2>err', '>&2') satu token. Mengembalikan
    i apa adanya bila toks[i] bukan redirect keluaran, dan -1 bila
    redirect MASUKAN ('< berkas' / '<berkas'): stdin perintah
    dialihkan dari berkas itu, bukan stdin bersama. Bentuk '<&'
    (duplikasi fd dari stdin itu sendiri) netral: dilompati.
    Audit 967: awalan fd arbitrer dikenali di kedua arah — redirect
    ke fd masukan selain 0 ('3< berkas') tak menyentuh stdin
    bersama sehingga netral; bentuk murni '>&'/'N>&' melompati
    target terpisahnya; penutup fd ('<&-', '>&-') berarti fd
    ditutup: masukan yang ditutup membuat perintah tak menguras
    (-1), keluaran yang ditutup netral."""
    t = toks[i]
    j = 0
    while j < len(t) and t[j].isdigit():
        j += 1
    fd = _fd_norm(t[:j])
    sisa = t[j:]
    if sisa.startswith("<"):
        if sisa.startswith("<&"):
            if fd not in ("", "0"):
                # Audit 1016: kecuali sumbernya pindah-fd dari 0 —
                # fd 0 perintah ikut tertutup, stdinnya hilang.
                sumber = (sisa[2:] if sisa != "<&"
                          else (toks[i + 1] if i + 1 < len(toks)
                                else ""))
                if _sumber_nol_pindah(sumber):
                    return -1
                # Duplikasi ke fd lain: stdin bersama tak tersentuh.
                return (min(i + 2, len(toks)) if sisa == "<&"
                        else i + 1)
            target = _fd_norm(sisa[2:])
            if target == "-":
                return -1  # stdin ditutup: perintah tak menguras
            if target == "":
                berikut = (toks[i + 1] if i + 1 < len(toks) else "")
                if _fd_norm(berikut) in ("", "0"):
                    return min(i + 2, len(toks))
                return -1  # '<& -' tutup, '<& N' alihkan dari fd lain
            if target == "0":
                return i + 1  # stdin diduplikasi dari dirinya: netral
            return -1  # '<&N': stdin berasal dari fd lain
        if fd in ("", "0"):
            return -1  # stdin perintah dialihkan dari berkas
        # Fd masukan lain: netral — bentuk murni melompati target.
        if sisa in ("<", "<>"):
            return min(i + 2, len(toks))
        return i + 1
    if sisa.startswith(">&"):
        target = sisa[2:]
        # Audit 1016: pindah-fd bersumber 0 ke fd selain 0 menutup
        # stdin perintah (tujuan "" = stdout fd 1); tujuan 0
        # ("0>&0-") adalah pindah ke diri sendiri yang netral.
        if fd != "0" and _sumber_nol_pindah(target):
            return -1
        if target == "-":
            # Audit 985: stdout ditutup TIDAK menghentikan mayoritas
            # pembaca — wc/sort/head/dd/tee membaca stdin sampai
            # habis dulu, galat tulis muncul belakangan; hanya cat
            # yang berhenti sebelum membaca. Perlakukan seperti
            # redirect keluaran biasa dan biarkan vonis ditentukan
            # perintahnya ('>&- cat' menjadi fail-closed, arah aman).
            return i + 1
        if target == "":
            # Audit 1016: target terpisah juga bisa pindah-fd dari 0.
            berikut = (toks[i + 1] if i + 1 < len(toks) else "")
            if fd != "0" and _sumber_nol_pindah(berikut):
                return -1
            return min(i + 2, len(toks))  # '>&' murni, target pisah
        return i + 1
    if (re.fullmatch(r"(\d*)(>|>>)", t)
            or re.fullmatch(r"&(>|>>)", t)):
        return min(i + 2, len(toks))
    if re.match(r"^(\d+|&)?>>?", t):
        return i + 1
    return i


def _sesudah_redirect_berantai(toks, i, batas):
    """Audit 968: indeks sesudah rangkaian redirect yang dimulai
    pada posisi i, selama posisi itu masih wilayah shell
    (i >= batas argv). -1 bila redirect masukan mengalihkan atau
    menutup stdin — vonis akhir pemanggil adalah tak menguras."""
    while i < len(toks) and i >= batas:
        sesudah = _indeks_sesudah_redirect(toks, i)
        if sesudah < 0:
            return -1
        if sesudah == i:
            break
        i = sesudah
    return i


def _token_shell(teks):
    """Audit 1047: tokenisasi sadar-kutip — spasi di dalam kutip
    TIDAK memecah token (tr '\\n' ' ', grep 'a b'). Tanda kutip
    dipertahankan di token karena logika hilir memakai sinyal
    berkutip (Audit 939); penggabungan fragmen bersebelahan
    ditangani _kata_shell di resolusi nama."""
    hasil = []
    kini = []
    kutip = ""
    i = 0
    n = len(teks)
    while i < n:
        c = teks[i]
        if kutip == "'":
            kini.append(c)
            if c == "'":
                kutip = ""
            i += 1
            continue
        if kutip == '"':
            kini.append(c)
            if c == "\\" and i + 1 < n:
                kini.append(teks[i + 1])
                i += 2
                continue
            if c == '"':
                kutip = ""
            i += 1
            continue
        if c in ("'", '"'):
            kutip = c
            kini.append(c)
            i += 1
            continue
        if c == "\\" and i + 1 < n:
            kini.append(c)
            kini.append(teks[i + 1])
            i += 2
            continue
        if c.isspace():
            if kini:
                hasil.append("".join(kini))
                kini = []
            i += 1
            continue
        kini.append(c)
        i += 1
    if kini:
        hasil.append("".join(kini))
    return hasil


def _kata_shell_utuh(token):
    """Audit 1046: kata shell dari satu token — fragmen berkutip
    dan telanjang yang bersebelahan digabungkan dan tanda
    kutipnya dibuang, persis pembentukan kata shell (c''at →
    cat). Dipakai untuk resolusi nama perintah; token operand
    tetap mentah (sinyal berkutip-penuh Audit 939 utuh)."""
    hasil = []
    i = 0
    n = len(token)
    while i < n:
        c = token[i]
        if c == "'":
            j = token.find("'", i + 1)
            if j < 0:
                hasil.append(token[i + 1:])
                break
            hasil.append(token[i + 1:j])
            i = j + 1
        elif c == '"':
            j = i + 1
            buf = []
            while j < n and token[j] != '"':
                if (token[j] == "\\" and j + 1 < n
                        and token[j + 1] in '$`"\\'):
                    buf.append(token[j + 1])
                    j += 2
                else:
                    buf.append(token[j])
                    j += 1
            hasil.append("".join(buf))
            i = j + 1
        elif c == "\\" and i + 1 < n:
            hasil.append(token[i + 1])
            i += 2
        else:
            hasil.append(c)
            i += 1
    # Audit 1396: fungsi ini hanya dipakai untuk resolusi
    # NAMA perintah — komponen direktorinya dibuang di sini
    # (nama berjalur /bin/cat, /usr/bin/openssl dinilai dari
    # nama dasarnya, sama seperti shell menjalankannya).
    return "".join(hasil)


def _kata_shell(token):
    """Audit 1396/1408: nama perintah dari bentuk utuh —
    komponen direktori dibuang (nama berjalur dinilai dari
    nama dasarnya). Pemanggil yang menilai token BUKAN nama
    perintah (penjaga penetapan) memakai _kata_shell_utuh:
    nilai penetapan boleh berjalur tanpa merusak polanya."""
    kata = _kata_shell_utuh(token)
    if "/" in kata:
        kata = kata.rsplit("/", 1)[-1]
    return kata


def _menguras_keluarga_skrip(perintah, toks, awal):
    """Audit 1038: bagi sed/awk/grep/jq operand pertama adalah
    skrip/pola/filter, BUKAN berkas masukan. Menguras bila tak ada
    operand berkas (operand kedua dst., atau semua operand bila
    skrip datang dari -e/-f). Operand "-" adalah stdin. Redirect
    masukan mengganti stdin (tak menguras); redirect keluaran
    dilompati beserta target terpisahnya."""
    # Audit 1067: varian nama → nama kanonis keluarga.
    # Audit 1113: original-awk varian awk orisinal.
    nama_asli = perintah
    perintah = {"mawk": "awk", "nawk": "awk", "egrep": "grep",
                "fgrep": "grep", "rg": "grep",
                # Audit 1244: grep terkompresi sekeluarga grep.
                "zgrep": "grep", "bzgrep": "grep",
                "xzgrep": "grep", "lzgrep": "grep",
                # Audit 1642: grep terkompresi zstd.
                "zstdgrep": "grep",
                # Audit 1306-1309: egrep/fgrep terkompresi.
                "zegrep": "grep", "zfgrep": "grep",
                "bzegrep": "grep", "bzfgrep": "grep",
                "xzegrep": "grep", "xzfgrep": "grep",
                "lzegrep": "grep", "lzfgrep": "grep",
                "original-awk": "awk",
                # Audit 1638: varian GNU awk.
                "gawk": "awk", "pgawk": "awk",
                "igawk": "awk"}.get(perintah, perintah)
    # Audit 1095: mode daftar rg tak membaca stdin sama sekali.
    if nama_asli == "rg" and any(
            t.strip("\"'") == "--files" for t in toks[awal + 1:]):
        return False
    if nama_asli == "dgawk":
        # Audit 1652: debugger GNU awk membaca PERINTAH
        # debug dari stdin apa pun operand berkasnya
        # (preseden debugger perl -d di penilai
        # interpreter) — selalu menguras.
        return True
    nilai = {
        "sed": {"-e", "-f", "--expression", "--file"},
        "awk": {"-F", "-v", "-f", "-W", "--field-separator",
                "--assign", "--file"},
        "grep": {"-e", "-f", "-m", "-A", "-B", "-C", "-d", "-D",
                 "--pattern", "--file", "--max-count",
                 "--after-context", "--before-context", "--context",
                 "--devices", "--directories",
                 # Audit 1127: opsi penyaring berkas bernilai.
                 "--exclude-from", "--exclude", "--include",
                 "--exclude-dir", "--label", "--binary-files",
                 # Audit 1176: --group-separator ketinggalan.
                 "--group-separator"},
        "jq": {"-L", "--library-path", "-f", "--from-file",
               "--arg", "--argjson", "--rawfile", "--slurpfile",
               # Audit 1126: --indent bernilai.
               "--indent"},
    }[perintah]
    if nama_asli in ("gawk", "pgawk", "igawk"):
        # Audit 1640: opsi bernilai khas GNU awk — tidak
        # ditambahkan ke himpunan awk bersama karena awk
        # POSIX yang tak mengenalnya akan galat dan tak
        # membaca stdin sama sekali.
        nilai = nilai | {"-i", "--include", "-l", "--load"}
    nilai2 = {"--arg", "--argjson", "--rawfile", "--slurpfile"}
    skrip_dari_opsi = False
    skrip_terisi = False
    # Audit 1138: sed -i (edit di tempat) tanpa operand berkas
    # adalah galat ("no input files"), bukan pembaca stdin.
    sed_i = False
    i = awal + 1
    n = len(toks)
    while i < n:
        t = toks[i]
        if t in (">", ">>", "1>", "2>", "1>>", "2>>"):
            i += 2
            continue
        if re.fullmatch(r"\d*>>?", t):
            i += 2
            continue
        if re.match(r"\d*>>?\S", t):
            i += 1
            continue
        if t.startswith("<"):
            if t.startswith("<&") and _fd_norm(t[2:]) == "0":
                i += 1
                continue
            return False
        if ">" in t:
            # operand yang merekat ke redirect keluaran: nilai
            # bagian operandnya saja.
            t = t.split(">", 1)[0]
            if not t:
                i += 1
                continue
        bersih = t.strip("\"'")
        if bersih == "--":
            i += 1
            continue
        if bersih.startswith("-") and bersih != "-":
            if perintah == "jq" and bersih in ("-n", "--null-input"):
                # jq -n tak membaca stdin sama sekali.
                return False
            dasar = bersih.split("=", 1)[0]
            if dasar in nilai:
                if perintah in ("sed", "awk", "grep") and (
                        dasar in ("-e", "-f", "--expression",
                                  "--file", "--pattern")):
                    skrip_dari_opsi = True
                    # Audit 1103: skrip/pola dari "-f -" dibaca dari
                    # stdin itu sendiri — menguras walau ada operand
                    # berkas data sesudahnya.
                    if dasar in ("-f", "--file"):
                        nilai_f = bersih.split("=", 1)[1] \
                            if "=" in bersih else (
                                toks[i + 1].strip("\"'")
                                if i + 1 < n else None)
                        if nilai_f == "-":
                            return True
                if perintah == "jq" and dasar in (
                        "-f", "--from-file"):
                    # Audit 1103 (cermin): jq menolak "-" sebagai
                    # berkas filter — tak membaca stdin.
                    nilai_f = bersih.split("=", 1)[1] \
                        if "=" in bersih else (
                            toks[i + 1].strip("\"'")
                            if i + 1 < n else None)
                    if nilai_f == "-":
                        return False
                    skrip_dari_opsi = True
                i += 1 if "=" in bersih else (
                    3 if dasar in nilai2 else 2)
                continue
            # Bentuk merekat: -e<skrip>, -f<berkas>, -F<pemisah>.
            if (perintah in ("sed", "grep")
                    and re.match(r"^-[ef]", bersih)
                    and len(bersih) > 2):
                # Audit 1103: "-f-" merekat = skrip dari stdin.
                if bersih == "-f-":
                    return True
                skrip_dari_opsi = True
                i += 1
                continue
            if perintah == "awk" and re.match(r"^-[Ffv]", bersih) \
                    and len(bersih) > 2:
                if bersih == "-f-":
                    return True
                if bersih.startswith("-f"):
                    skrip_dari_opsi = True
                i += 1
                continue
            if perintah == "sed" and (
                    bersih == "--in-place"
                    or bersih.startswith("--in-place=")
                    or re.fullmatch(r"-[a-z]*i[a-z]*(\..*)?",
                                    bersih)):
                sed_i = True
            i += 1  # bendera polos
            continue
        if bersih == "-":
            i += 1
            continue
        if (bersih in ("/dev/stdin", "/dev/fd/0", "/proc/self/fd/0")
                and (skrip_terisi or skrip_dari_opsi)):
            # Audit 1099: jalur fd-0 di posisi berkas adalah stdin.
            i += 1
            continue
        if (perintah == "awk" and (skrip_terisi or skrip_dari_opsi)
                and re.match(r"^[A-Za-z_][A-Za-z0-9_]*=", bersih)):
            # Audit 1052: penetapan awk hanya sah SESUDAH slot
            # program terisi — di posisi pertama ia adalah teks
            # program (dan program 'FS=:' tak sah, perintah batal).
            i += 1  # penetapan awk, bukan berkas
            continue
        # Operand polos: skrip/pola pertama, berkas sesudahnya.
        if not skrip_terisi and not skrip_dari_opsi:
            skrip_terisi = True
            i += 1
            continue
        return False
    # Audit 1138: lingkar habis tanpa operand berkas — sed -i
    # galat dan tak membaca (operand berkas akan return False
    # di dalam lingkar).
    if perintah == "sed" and sed_i:
        return False
    return True


def _menguras_interpreter(perintah, toks, awal):
    """Audit 1039: python3/node membaca SKRIP dari stdin bila
    operandnya "-" atau tak ada operand skrip sama sekali; perl
    dengan -n/-p memasang lingkar baca implisit atas stdin (atau
    atas berkas operand bila ada). Bentuk -c/-e berkode arbitrer
    adalah batas model (isi tak dinilai)."""
    i = awal + 1
    n = len(toks)
    if perintah == "perl":
        pakai_lingkar = False
        ada_berkas = False
        debugger = False
        # Audit 1119: tanpa operand skrip sama sekali, perl
        # membaca SKRIP-nya dari stdin (telanjang, -w, "-").
        skrip_terisi = False
        berkas_stdin = False
        while i < n:
            t = toks[i]
            if t == "-e":
                skrip_terisi = True
                i += 2
                continue
            if t.startswith("-") and t != "-":
                huruf = t[1:]
                # Audit 1392: huruf-huruf bundel dinilai
                # sebagai jalan huruf EFEKTIF yang berhenti
                # SEBELUM huruf bernilai pertama (I/m/M/e
                # menelan sisa bundel sebagai argumennya) —
                # huruf di wilayah argumen (mis. 'd'/'n' pada
                # -Id, -mdiagnostics) bukan bendera.
                # Audit 1411: saklar -0 menelan digit oktal
                # di dalam bundel yang sama (-0777pe idiom
                # slurp) — kelompok digit itu dibuang dari
                # huruf yang dinilai, bukan menggagalkan
                # pindaian.
                huruf_huruf = ""
                k_h = 0
                while k_h < len(huruf):
                    if huruf[k_h] == "0":
                        k_h += 1
                        while (k_h < len(huruf)
                               and huruf[k_h] in "01234567"):
                            k_h += 1
                        continue
                    huruf_huruf += huruf[k_h]
                    k_h += 1
                if re.fullmatch(r"[A-Za-z]+", huruf_huruf):
                    efektif = ""
                    for h_x in huruf_huruf:
                        if h_x in "IMme":
                            break
                        efektif += h_x
                    if "n" in efektif or "p" in efektif:
                        pakai_lingkar = True
                    if "d" in efektif:
                        debugger = True
                # Audit 1375: -d (termasuk bundel huruf dan
                # bentuk -d:Modul) menyalakan debugger, yang
                # membaca PERINTAH dari stdin.
                if t.startswith("-d:"):
                    debugger = True
                # Audit 1412: 'e' di bundel menelan SISA
                # bundel sebagai programnya — di ujung
                # bundel programnya adalah token berikutnya
                # (-ne/-pe), di tengah bundel programnya
                # sisa huruf itu sendiri dan token
                # berikutnya adalah operand berkas (-ned).
                posisi_e = -1
                for k_e, h_e in enumerate(huruf_huruf):
                    if h_e in "IMm":
                        break
                    if h_e == "e":
                        posisi_e = k_e
                        break
                if posisi_e >= 0:
                    skrip_terisi = True
                    i += 2 if posisi_e == len(huruf_huruf) - 1 else 1
                else:
                    i += 1
                continue
            if t == "--":
                i += 1
                continue
            if t == "-":
                if not skrip_terisi:
                    return True  # skrip dari stdin
                berkas_stdin = True
                i += 1
                continue
            if not skrip_terisi:
                skrip_terisi = True
            else:
                ada_berkas = True
            i += 1
        if not skrip_terisi:
            return True
        # Audit 1375: kanal perintah debugger membaca stdin
        # apa pun mode lingkar/operand skripnya.
        if debugger:
            return True
        return pakai_lingkar and (berkas_stdin or not ada_berkas)
    if perintah in ("expect", "expectk"):
        # Audit 1122: expect telanjang membaca skrip dari stdin;
        # -f - sama; berkas skrip/-c menekan (isi -c = kode
        # arbitrer, batas model seperti interpreter lain).
        # Audit 1145: -c hanyalah perintah AWALAN — telan
        # nilainya lalu lanjut memindai; -f - yang menyusul
        # tetap berlaku. Hanya -c tanpa skrip/-f: perintah
        # awalan lalu keluar (terprobe tak membaca stdin).
        ada_c = False
        while i < n:
            t = toks[i].strip("\"'")
            if t == "-f":
                return (i + 1 < n
                        and toks[i + 1].strip("\"'") == "-")
            if t.startswith("-f") and len(t) > 2:
                return t[2:] == "-"
            if t == "-c":
                ada_c = True
                i += 2
                continue
            if t.startswith("-c"):
                ada_c = True
                i += 1
                continue
            # Audit 1146: -D bernilai — nilainya bukan nama
            # skrip (dulu token angka sesudah -D mengakhiri
            # vonis sebagai operand skrip).
            if t == "-D":
                i += 2
                continue
            if t.startswith("-D") and len(t) > 2:
                i += 1
                continue
            # Audit 1182: operand "-" adalah skrip dari stdin,
            # juga sesudah -c.
            if t == "-":
                return True
            if t.startswith("-"):
                i += 1
                continue
            return False
        return not ada_c
    if perintah in ("tclsh", "wish"):
        # Audit 1159: tclsh tanpa berkas skrip membaca skrip
        # dari stdin; operand "-" juga stdin; token lain =
        # nama skrip (argumen sesudahnya argv skrip).
        while i < n:
            t = toks[i].strip("\"'")
            # Audit 1183: -encoding bernilai — nilainya bukan
            # nama skrip.
            if t == "-encoding":
                i += 2
                continue
            if t.startswith("-") and t != "-":
                i += 1
                continue
            return t == "-"
        return True
    if perintah in ("bash", "sh", "dash", "zsh", "rbash",
                    "rzsh",
                    # Audit 1638: shell lain sekeluarga —
                    # tanpa skrip membaca perintah dari stdin.
                    "ash", "ksh", "fish",
                    # Audit 1737: shell keluarga kedua.
                    "mksh", "tcsh", "csh", "yash"):
        # Audit 1058: shell tanpa skrip membaca perintah dari
        # stdin; -c menekan; operand "-" memintanya eksplisit.
        # Audit 1083: sesudah -s, operand pertama adalah $0 (nama
        # program), bukan berkas skrip — stdin tetap dibaca.
        dari_stdin = False
        while i < n:
            t = toks[i]
            bersih_t = t.strip("\"'")
            if bersih_t == "-c" or (
                    bersih_t.startswith("-")
                    and not bersih_t.startswith("--")
                    and len(bersih_t) > 2
                    and bersih_t[1] != "-"
                    and "c" in bersih_t[1:]
                    and bersih_t.index("c") == len(bersih_t) - 1):
                # Audit 1082: -c bisa huruf terakhir bundel (-ec).
                # String -c shell adalah baris perintah shell —
                # dinilai rekursif seperti env -S/flock -c (beda
                # dengan -c interpreter yang kode arbitrer).
                if i + 1 < n:
                    return any(
                        _menguras_stdin(s) for s in
                        pecah_segmen_shell(toks[i + 1].strip("\"'")))
                return False
            if bersih_t == "-o":
                i += 2
                continue
            if bersih_t == "-s":
                dari_stdin = True
                i += 1
                continue
            if bersih_t == "--":
                i += 1
                if i >= n:
                    return True
                if dari_stdin:
                    return True
                return toks[i] == "-"
            if bersih_t.startswith("-") and bersih_t != "-":
                # Audit 1082: bundel berisi s (-ns/-es) = mode -s.
                if (not bersih_t.startswith("--")
                        and "s" in bersih_t[1:]
                        and "c" not in bersih_t[1:]):
                    dari_stdin = True
                i += 1
                continue
            if dari_stdin:
                return True
            return t == "-"
        return True
    interaktif = False
    # Audit 1725/1726: penanda aksi lua (-e) dan -v —
    # dinilai di ekor lingkar.
    aksi_lua = False
    versi_lua = False
    while i < n:
        t = toks[i]
        bersih_t = t.strip("\"'")
        if perintah.startswith("python"):
            # Audit 1082: bundel python (-ic/-ci) — huruf i di
            # bundel tetap menyalakan interaktif; huruf c mengakhiri
            # penguraian opsi (sisanya string -c itu sendiri).
            if (bersih_t.startswith("-")
                    and not bersih_t.startswith("--")
                    and len(bersih_t) > 2):
                huruf_py = bersih_t[1:]
                # Audit 1692: bundel ditafsir per karakter
                # seperti CPython - opsi bernilai (c/m/W/X)
                # menelan SISA token sebagai nilainya di
                # posisi mana pun (bukan hanya huruf pertama
                # seperti Audit 1674); huruf 'i' sebelum opsi
                # bernilai pertama tetap menyalakan interaktif.
                pos_bernilai = -1
                # Audit 1694: huruf asing sebelum opsi
                # bernilai pertama = opsi tak dikenal -
                # CPython mencetak galat lalu keluar.
                asing_py = False
                for pos, h in enumerate(huruf_py):
                    if h in ("c", "m", "W", "X"):
                        pos_bernilai = pos
                        break
                    if h == "i":
                        interaktif = True
                        continue
                    if h not in "bBdEhIOPQRsSuUvVqx":
                        asing_py = True
                        break
                if asing_py:
                    return False
                # Audit 1705: -V di bundel mencetak versi lalu
                # keluar, mendahului REPL maupun skrip.
                if "V" in (huruf_py if pos_bernilai < 0
                           else huruf_py[:pos_bernilai]):
                    return False
                # Audit 1719: -h di bundel juga bantuan lalu
                # keluar (kasus sejajar -V yang tertinggal;
                # terprobe -hi/-ih/-hs model True / real False).
                if "h" in (huruf_py if pos_bernilai < 0
                           else huruf_py[:pos_bernilai]):
                    return False
                if (pos_bernilai >= 0
                        and huruf_py[pos_bernilai] == "c"):
                    # Audit 1081: -i berlaku juga sesudah -c.
                    return interaktif or any(
                        x.strip("\"'") == "-i"
                        or (x.strip("\"'").startswith("-")
                            and not x.strip("\"'").startswith("--")
                            and len(x.strip("\"'")) > 2
                            and "i" in x.strip("\"'")[1:])
                        for x in toks[i + 1:])
                if (pos_bernilai >= 0
                        and huruf_py[pos_bernilai] == "m"):
                    if pos_bernilai == len(huruf_py) - 1:
                        t = "-m"
                    else:
                        # -m bernilai menempel: sisa token
                        # adalah nama modulnya - dinilai
                        # seperti cabang -m terpisah atas
                        # modul itu (versi ringkas).
                        modul_m = huruf_py[pos_bernilai + 1:]
                        if modul_m in ("pdb", "asyncio", "code"):
                            return True
                        if modul_m == "sqlite3":
                            sisa_m = [x.strip("\"'")
                                      for x in toks[i + 1:]]
                            if ("-c" in sisa_m
                                    or "--version" in sisa_m
                                    or "--help" in sisa_m):
                                return False
                            return len(_operand_sesudah_opsi(
                                toks, i, {"-c"})) < 2
                        if modul_m in ("json.tool", "base64",
                                       "tokenize", "quopri",
                                       "gzip", "uu", "dis"):
                            ops_m = _operand_sesudah_opsi(
                                toks, i, {"--indent", "--tab"})
                            if modul_m == "tokenize":
                                return not ops_m
                            if not ops_m:
                                return True
                            if modul_m == "gzip":
                                return "-" in ops_m
                            return ops_m[0] == "-"
                        return interaktif
                elif (pos_bernilai >= 0
                        and huruf_py[pos_bernilai] in ("W", "X")):
                    # Audit 1708: W/X di ekor bundel menelan
                    # token BERIKUTNYA sebagai nilainya; tanpa
                    # token berikutnya = galat lalu keluar.
                    # Nilai menempel (sisa token) tuntas di
                    # token ini juga.
                    if pos_bernilai == len(huruf_py) - 1:
                        if i + 1 >= n:
                            return False
                        i += 2
                    else:
                        i += 1
                    continue
                else:
                    i += 1
                    continue
            if t == "-i":
                # Audit 1049: -i menjatuhkan python ke REPL sesudah
                # skrip jalan — REPL itu menguras stdin.
                interaktif = True
                i += 1
                continue
            if t == "-c":
                # Audit 1081: -c tidak membatalkan -i — REPL
                # sesudah kode -c tetap menguras.
                return interaktif or any(
                    x.strip("\"'") == "-i" for x in toks[i + 1:])
            if t == "-m":
                # Audit 1049: modul penyaring stdin yang terverifikasi
                # menguras; modul lain tak dinilai (bawaan: bukan).
                # Audit 1090: operand berkas SESUDAH nama modul
                # adalah masukan modul penyaring — menekan stdin.
                # Audit 1091: pdb membaca PERINTAH dari stdin walau
                # beroperand skrip; asyncio menjatuhkan REPL.
                if i + 1 >= n:
                    return False
                modul = toks[i + 1].strip("\"'")
                if modul in ("pdb", "asyncio",
                             # Audit 1374: konsol interaktif.
                             "code"):
                    return True
                if modul == "sqlite3":
                    # Audit 1372: shell SQL — operand pertama
                    # adalah nama basis data (TAK menekan);
                    # operand SQL kedua (atau perintah -c)
                    # yang menekan.
                    # Audit 1390: -c sungguh menekan (SQL-nya
                    # dijalankan lalu keluar) — kehadirannya
                    # diperiksa langsung pada token, bukan
                    # tersirat dari hitungan operand;
                    # --version/--help juga informasional
                    # (regresi dari cabang ini).
                    sisa_sq = [x.strip("\"'")
                               for x in toks[i + 2:]]
                    if ("-c" in sisa_sq
                            or "--version" in sisa_sq
                            or "--help" in sisa_sq):
                        return False
                    ops_sq = _operand_sesudah_opsi(
                        toks, i + 1, {"-c"})
                    return len(ops_sq) < 2
                if modul in ("json.tool", "base64",
                             # Audit 1373: modul penyaring lain
                             # — operand berkas menekan seperti
                             # json.tool/base64.
                             "tokenize", "quopri", "gzip", "uu",
                             "dis"):
                    ops_md = _operand_sesudah_opsi(
                        toks, i + 1, {"--indent", "--tab"})
                    if modul == "tokenize":
                        # tokenize: '-' nama berkas harfiah,
                        # bukan stdin (aturan lama utuh).
                        return not ops_md
                    # Audit 1391: bagi modul penyaring lain,
                    # operand PERTAMA '-' adalah stdin itu
                    # sendiri (masukan modulnya), bukan berkas
                    # yang menekan.
                    if not ops_md:
                        return True
                    # Audit 1422: gzip memproses banyak
                    # berkas — '-' di posisi mana pun
                    # adalah stdin.
                    if modul == "gzip":
                        return "-" in ops_md
                    return ops_md[0] == "-"
                # Audit 1693: modul biasa + -i = modul jalan
                # lalu REPL - REPL itu menguras stdin.
                return interaktif
        if perintah == "node" and t in (
                "-e", "--eval", "-p", "--print"):
            return False
        # Audit 1661: pasangan -W/-X hanya sah untuk python
        # (di python keduanya memang bernilai). Di ruby -W
        # berargumen opsional MENEMPEL (-W0/-W1/-W2; token
        # terpisah berikutnya adalah berkas skrip) dan di lua
        # -W bendera tanpa nilai — menelan token berikutnya
        # di sana menyembunyikan operand skrip (positif palsu).
        if perintah.startswith("python") and t in ("-W", "-X"):
            # Audit 1708: -W/-X menggantung (tak ada token
            # nilai) = galat "Argument expected" lalu keluar.
            if i + 1 >= n:
                return False
            i += 2
            continue
        # Audit 1299: opsi bernilai node — nilainya bukan
        # operand skrip.
        if perintah == "node" and t in (
                "-r", "--require", "--import", "--loader",
                "--experimental-loader", "--input-type",
                "--conditions", "--env-file"):
            # Audit 1722: menggantung = "requires an argument"
            # lalu keluar (sejajar Audit 1708/1713).
            if i + 1 >= n:
                return False
            i += 2
            continue
        # Audit 1641: opsi bernilai interpreter lain —
        # nilainya bukan operand skrip.
        if perintah == "ruby" and t in (
                "-I", "-r",
                # Audit 1650: opsi bernilai ruby lain dari
                # man ruby(1) — -C direktori, -E/--encoding
                # keluarga encoding, -F pemisah, -K koding.
                "-C", "-E", "--encoding",
                "--external-encoding", "--internal-encoding",
                "-F", "-K"):
            i += 2
            continue
        if perintah == "php" and t in (
                "-d", "-c",
                # Audit 1651: -z ekstensi bernilai + keluarga
                # pemroses baris (-B/-R/-E kode, -F berkas)
                # yang membaca baris masukan dari stdin bila
                # tanpa berkas (manual PHP).
                "-z", "-B", "-R", "-E", "-F",
                # Audit 1723: -t docroot server bawaan juga
                # bernilai (manual PHP) — nilai bukan operand
                # dan menggantung = galat lalu keluar
                # (DUGAAN kuat; biner tak terpasang).
                "-t"):
            if i + 1 >= n:
                return False
            i += 2
            continue
        if perintah in ("lua", "luajit") and t == "-l":
            # Audit 1713: -l menggantung = "needs argument"
            # lalu keluar (keluarga collectargs lua.c).
            if i + 1 >= n:
                return False
            i += 2
            continue
        # Audit 1672: -j luajit bernilai terpisah (perintah
        # JIT; luajit.c collectargs case 'j') — khusus luajit,
        # lua tidak punya -j.
        if perintah == "luajit" and t == "-j":
            i += 2
            continue
        if perintah == "luajit" and t == "-b":
            # Audit 1673: mode bytecode — semua token sisa
            # adalah argumen jit/bcsave.lua, bukan program
            # lua: -n/-t/-a/-o/-e bernilai (lompati
            # berpasangan), opsi lain bendera, operand adalah
            # berkas masukan; stdin hanya dibaca bila ada
            # operand "-" persis. Tanpa operand = usage lalu
            # keluar (tak membaca).
            j = i + 1
            while j < n:
                u = toks[j].strip("\"'")
                if u in ("-n", "-t", "-a", "-o", "-e"):
                    j += 2
                    continue
                if u.startswith("-") and u != "-":
                    j += 1
                    continue
                if u == "-":
                    return True
                j += 1
            return False
        if t == "--":
            i += 1
            return toks[i] == "-" if i < n else True
        # Audit 1705: -V mencetak versi lalu keluar.
        if perintah.startswith("python") and bersih_t == "-V":
            return False
        # Audit 1694: token python satu huruf tak dikenal
        # (atau -h: bantuan lalu keluar) - CPython galat/
        # bantuan lalu keluar tanpa membaca. Token -i/-c/-m/
        # -W/-X sudah tertangani cabang-cabangnya di atas.
        if (perintah.startswith("python") and len(bersih_t) == 2
                and bersih_t.startswith("-")
                and bersih_t[1] not in "bBcdEiImOPQRsSuUvVWxXq"):
            return False
        # Audit 1694: opsi panjang python tak dikenal -
        # CPython mencetak galat lalu keluar tanpa membaca.
        if perintah.startswith("python") and t.startswith("--"):
            # Audit 1705: opsi informasional mencetak lalu
            # keluar - bukan dilewati sebagai bendera biasa.
            if t.split("=")[0] in (
                    "--help", "--version", "--help-env",
                    "--help-xoptions", "--help-all"):
                return False
            # Audit 1707: --check-hash-based-pycs BERNILAI
            # (bentuk spasi saja; bentuk = ditolak CPython
            # 3.12.3 terprobe) - nilainya bukan operand.
            if t == "--check-hash-based-pycs":
                # Audit 1720: menggantung = "Argument expected"
                # lalu keluar (sejajar -W/-X Audit 1708).
                if i + 1 >= n:
                    return False
                i += 2
                continue
            if t.split("=")[0] not in ("--safe-path",):
                return False
            i += 1
            continue
        # Audit 1711: node - opsi tak dikenal membuat node
        # galat "bad option" lalu keluar (terprobe real).
        # Yang dikenal dilewati (bendera umum + keluarga
        # awalan); opsi bernilai node tertangani Audit 1299
        # dan -e/-p tertangani cabangnya sendiri di atas.
        if perintah == "node" and t.startswith("-") and t != "-":
            dasar_nd = t.split("=")[0]
            # Audit 1712: informasional node mencetak lalu
            # keluar di posisi mana pun.
            if dasar_nd in ("--version", "--help", "-v"):
                return False
            # Audit 1721: --title BERNILAI (mengambil argumen;
            # menggantung = "--title requires an argument"
            # lalu keluar) — nilainya bukan operand skrip.
            if dasar_nd == "--title":
                if "=" in t:
                    i += 1
                elif i + 1 >= n:
                    return False
                else:
                    i += 2
                continue
            dikenal_nd = (
                dasar_nd in ("--version", "--help", "-v",
                             "--eval", "--print", "--check",
                             "--interactive", "--require",
                             "--import", "--loader",
                             "--experimental-loader",
                             "--input-type", "--conditions",
                             "--env-file", "--enable-source-maps",
                             "--watch", "--test", "--inspect",
                             "--inspect-brk", "--cpu-prof",
                             "--heap-prof",
                             "--use-largepages",
                             "--zero-fill-buffers")
                or dasar_nd.startswith("--experimental-")
                or dasar_nd.startswith("--v8-")
                or dasar_nd.startswith("--trace-")
                or dasar_nd.startswith("--no-")
                or dasar_nd.startswith("--max-")
                or (len(dasar_nd) == 2
                    and dasar_nd[1] in "ceiprv"))
            if not dikenal_nd:
                return False
            i += 1
            continue
        # Audit 1714: ruby/php - informasional cetak lalu
        # keluar; opsi tak dikenal galat lalu keluar
        # (DUGAAN kuat dari dokumentasi CLI keduanya;
        # biner tak terpasang di mesin audit). Huruf sah
        # diambil longgar dari man ruby(1)/php --help agar
        # bendera sah tak ikut tertolak.
        if perintah in ("ruby", "php") and t.startswith("-") and t != "-":
            dasar_rp = t.split("=")[0]
            if dasar_rp in ("--version", "--help"):
                return False
            if dasar_rp.startswith("--"):
                if perintah == "ruby":
                    sah_rp = {"--copyright", "--dump",
                              "--verbose", "--debug", "--warn",
                              "--encoding",
                              "--external-encoding",
                              "--internal-encoding",
                              "--backtrace-limit", "--jit",
                              "--yjit",
                              "--frozen-string-literal",
                              "--debug-frozen-string-literal"}
                else:
                    sah_rp = {"--ini", "--info", "--modules"}
                if (dasar_rp not in sah_rp
                        and not dasar_rp.startswith("--enable-")
                        and not dasar_rp.startswith("--disable-")):
                    return False
                i += 1
                continue
            if len(dasar_rp) == 2:
                if perintah == "ruby":
                    sah1_rp = "abcdehilmnprsuvwxyCEFIKTUWX0"
                else:
                    sah1_rp = "abcdefhilmnqrstvzBCEFRST"
                if dasar_rp[1] not in sah1_rp:
                    return False
                # -v telanjang di ekor = versi lalu keluar.
                if dasar_rp == "-v" and i + 1 >= n:
                    return False
            i += 1
            continue
        # Audit 1697: opsi lua tak dikenal - lua.c usage
        # lalu keluar tanpa membaca (DUGAAN kuat; sejajar
        # Audit 1694). Yang sah: -e (bernilai), -i, -l
        # (tertangani di atas), -v, -E, -W.
        if perintah == "lua" and t.startswith("-") and t != "-":
            if t == "-e":
                # Audit 1713: -e menggantung sama seperti -l.
                if i + 1 >= n:
                    return False
                aksi_lua = True
                i += 2
                continue
            if t == "-i":
                interaktif = True
                i += 1
                continue
            if t == "-v":
                versi_lua = True
                i += 1
                continue
            if t in ("-E", "-W"):
                i += 1
                continue
            return False
        if t.startswith("-") and t != "-":
            i += 1
            continue
        if t == "-":
            return True
        # Operand berkas skrip: tak membaca stdin, KECUALI REPL
        # interaktif python menyusul (Audit 1049).
        return interaktif if perintah.startswith("python") else False
    # Audit 1725/1726: lua yang sudah menerima aksi -e
    # atau -v — TANPA skrip dan TANPA REPL (-i) — menjalankan
    # aksinya lalu keluar; stdin tidak dibaca sebagai program
    # (lua.c 5.4 pmain: syarat baca-stdin memakai masker
    # has_e|has_v saja). -l SENGAJA tidak menyetel penanda:
    # collectargs tidak memberi bit untuk -l, jadi
    # `lua -l lib` tanpa skrip tetap mengeksekusi stdin
    # sebagai program (terkunci Audit 1641/1653).
    if perintah == "lua" and (aksi_lua or versi_lua) \
            and not interaktif:
        return False
    return True


def _menguras_mapfile(toks, awal):
    """Audit 1040: mapfile/readarray tanpa batas membaca stdin
    sampai EOF. -n/-N berbatas hanya membaca sebagian (penulis
    berikutnya tak kelaparan); -u fd lain membaca fd itu."""
    i = awal + 1
    n = len(toks)
    while i < n:
        t = toks[i]
        # Audit 1053: redirect masukan SESUDAH nama builtin —
        # resolver hanya memproses redirect sebelum perintah.
        # "< berkas" / "<berkas" / "0<..." mengganti stdin;
        # "<&0" duplikasi-diri netral; "<&-" / "<&N" lain membaca
        # bukan-stdin. Semuanya membuat stdin bersama tak dibaca.
        if (t.startswith("<") and not t.startswith("<<")
                and _fd_norm(t[1:].lstrip("&")) != "0"):
            return False
        if (t.startswith("<&") and t[2:].strip("\"'")
                not in ("0", "0-")):
            return False
        if t in ("-n", "-N"):
            if i + 1 < n:
                try:
                    if int(toks[i + 1].strip("\"'")) > 0:
                        return False
                except ValueError:
                    pass
            i += 2
            continue
        if t == "-u":
            if i + 1 < n and toks[i + 1].strip("\"'") != "0":
                return False
            i += 2
            continue
        if t in ("-d", "-O", "-s", "-C", "-c"):
            i += 2
            continue
        i += 1
    return True


def _operand_sesudah_opsi(toks, awal, bernilai):
    """Daftar operand polos sesudah melompati opsi (yang bernilai
    menelan token berikutnya; bentuk --opsi=nilai dan -o<nilai>
    merekat ditangani). Dipakai penilai khusus Audit 1059-1072."""
    hasil = []
    i = awal + 1
    n = len(toks)
    while i < n:
        t = toks[i]
        bersih = t.strip("\"'")
        if bersih == "--":
            hasil.extend(x.strip("\"'") for x in toks[i + 1:])
            break
        if bersih.startswith("-") and bersih != "-":
            dasar = bersih.split("=", 1)[0]
            if dasar in bernilai:
                if "=" not in bersih and i + 1 >= n:
                    # Audit 1096: opsi bernilai menggantung di
                    # token terakhir — program galat ("requires an
                    # argument") dan tak membaca. Penanda operand
                    # membuat semua pemanggil condong ke False.
                    hasil.append("\x00gantung")
                i += 1 if "=" in bersih else 2
                continue
            i += 1
            continue
        hasil.append(bersih)
        i += 1
    return hasil


def _uri_store_ke_jalur(o_s):
    # Audit 1564: normalisasi operand storeutl —
    # pemuat file OSSL_STORE hanya menerima host
    # kosong atau localhost (tak peka huruf); host
    # lain (termasuk userinfo, port, kurung IPv6)
    # ditolak sebelum membaca, jadi BUKAN stdin.
    # Sebaliknya garis miring ganda di awal jalur
    # dilipat openssl (file:////dev/stdin tetap
    # membuka fd-nya) — lipat sebelum mencocokkan.
    m = re.match(r"(?i)^file:(.*)$", o_s)
    if not m:
        return o_s
    sisa = m.group(1)
    if sisa.startswith("//"):
        potong = sisa.find("/", 2)
        if potong < 0:
            return sisa
        host = sisa[2:potong]
        if host != "" and host.lower() != "localhost":
            return sisa
        sisa = sisa[potong:]
    if sisa.startswith("/"):
        sisa = "/" + sisa.lstrip("/")
    return sisa

def _menguras_khusus(perintah, toks, awal):
    """Audit 1059-1072: penilai perintah yang pengurasannya
    bergantung pada subcommand / bentuk opsi khas — tak bisa
    dinilai aturan operand generik."""
    sisa = toks[awal + 1:]
    mentah = [t.strip("\"'") for t in sisa]
    # Audit 1136: panggilan informasional juga tak membaca pada
    # penilai khusus (celah 1102; untuk vi ini regresi dari 1108).
    # Audit 1147: token informasional yang menjadi NILAI opsi
    # perintah itu sendiri (mis. label diff '--help') bukan
    # panggilan bantuan — lewati yang didahului opsi bernilai
    # khas penilai khusus.
    def _ada_informatif(tanda):
        # Audit 1180: himpunan pendahulu wajib sadar perintah —
        # '-t' bagi tar adalah MODE dan '-f' bagi more bendera
        # polos, bukan opsi bernilai.
        # Audit 1181: -init bernilai hanya untuk sqlite3.
        pendahulu = {"--label", "--output-delimiter", "-f",
                     "--prefix", "--suffix-format", "-o",
                     "--output", "--homedir", "-t",
                     "--separator", "-in"}
        if perintah == "tar":
            pendahulu.discard("-t")
        if perintah == "more":
            pendahulu.discard("-f")
        if perintah == "less":
            # Audit 1216: -f bagi less juga bendera polos.
            pendahulu.discard("-f")
        if perintah == "patch":
            # Audit 1217: -t bagi patch bendera mode batch.
            pendahulu.discard("-t")
            # Audit 1275: -f bagi patch juga bendera polos
            # (paksa) — bukan opsi bernilai.
            pendahulu.discard("-f")
        if perintah == "sqlite3":
            pendahulu.add("-init")
        for k_i, t_i in enumerate(mentah):
            if t_i in tanda:
                if k_i > 0 and mentah[k_i - 1] in pendahulu:
                    continue
                return True
        return False

    if _ada_informatif(("--help", "--version")):
        return False
    if perintah == "openssl" and _ada_informatif(
            ("-help", "-version")):
        return False
    # Audit 1088: redirect masukan di EKOR perintah mengganti atau
    # menutup stdin sebelum perintah berjalan — cabang khusus dulu
    # lolos dari pemeriksaannya (resolver hanya menangani redirect
    # sebelum nama perintah). "<&0"/"<&0-" netral seperti di 1053.
    for t_asli_r in sisa:
        # Audit 1133: token berawalan kutip adalah DATA berkutip,
        # bukan redirect (mis. label diff '<a>').
        # Audit 1154: tetapi segmen kutip yang DIIKUTI sisa token
        # tetap dipecah shell — ''<berkas adalah operand kosong
        # plus redirect, jadi sisa sesudah kutip penutup dinilai.
        if t_asli_r[:1] in ("\"", "'"):
            tutup_r = t_asli_r.find(t_asli_r[0], 1)
            if tutup_r < 0:
                continue
            ekor_r = t_asli_r[tutup_r + 1:]
            if not ekor_r:
                continue
            t_r = ekor_r.strip("\"'")
        else:
            t_r = t_asli_r.strip("\"'")
        s_r = t_r
        nomor_r = ""
        while s_r[:1].isdigit():
            nomor_r, s_r = nomor_r + s_r[0], s_r[1:]
        if s_r.startswith("<") and nomor_r in ("", "0"):
            if s_r.startswith("<<<") or s_r.startswith("<<"):
                return False
            badan_r = s_r[1:]
            if badan_r.startswith("&"):
                if badan_r[1:].strip("\"'") not in ("0", "0-"):
                    return False
                continue
            return False
    if perintah == "sqlite3":
        # Audit 1059: operand pertama = berkas db (bukan sumber
        # perintah); operand kedua = string SQL yang menekan.
        ops = _operand_sesudah_opsi(
            toks, awal,
            {"-cmd", "-init", "-separator", "-nullvalue",
             "-maxwidth", "-vfs", "-pagecache", "-lookaside",
             # Audit 1175: -newline/-nonce bernilai — vonis
             # sqlite3 menghitung jumlah operand, jadi nilai
             # yang tertelan langsung membalikkan vonis.
             "-newline", "-nonce"})
        # Audit 1096: opsi menggantung = program galat.
        if "\x00gantung" in ops:
            return False
        return len(ops) < 2
    if perintah == "openssl":
        # Audit 1060: hanya subcommand dgst (dan alias nama
        # digestnya) serta enc yang membaca stdin.
        # Audit 1075: operand "-" adalah stdin juga di sini
        # (konvensi Audit 1037 belum menjangkau penilai khusus).
        # Audit 1104: subcommand pembaca putaran-2 (base64 = alias
        # enc; smime/cms/pkeyutl tanpa -in; passwd -stdin;
        # s_client ber--connect).
        if not mentah:
            return False
        sub = mentah[0]
        # Audit 1583: pola nama dinamis dihoist dari
        # cabang enc/dgst agar penjaga bentuk daftar
        # memakai himpunan yang sama persis (dulu
        # penjaga hanya mengenal sub harfiah sehingga
        # alias seperti 'aes-256-cbc -ciphers' dan
        # 'sha256 -list' kena positif palsu).
        pola_cipher = (
            r"(aes-(128|192|256)-(cbc|cfb|cfb1|cfb8|ctr|ecb|ofb)"
            r"|aria-(128|192|256)-(cbc|cfb|cfb1|cfb8|ctr|ecb|ofb)"
            r"|camellia-(128|192|256)"
            r"-(cbc|cfb|cfb1|cfb8|ctr|ecb|ofb)"
            r"|des-ede3?(-(cbc|cfb|ofb))?|des3"
            r"|sm4-(cbc|cfb|ctr|ecb|ofb))")
        pola_digest = (
            r"(md5|sha1|sha224|sha256|sha384|sha512|rmd160"
            r"|blake2b512|blake2s256|sm3|whirlpool"
            r"|sha3-224|sha3-256|sha3-384|sha3-512"
            r"|sha512-224|sha512-256|shake128|shake256)")
        # Audit 1214: bentuk daftar openssl juga informasional
        # (mencetak daftar lalu keluar tanpa membaca).
        if (sub in ("dgst", "enc")
                or re.fullmatch(pola_cipher, sub,
                                re.IGNORECASE)
                or re.fullmatch(pola_digest, sub,
                                re.IGNORECASE)) and any(
                # Audit 1240: bentuk strip-ganda juga.
                t in ("-list", "-ciphers", "--list",
                      "--ciphers") for t in mentah):
            return False

        def _in_non_stdin():
            # Audit 1278: openssl memakai -in TERAKHIR — vonis
            # dari kemunculan terakhir, bukan pertama.
            hasil_in = None
            for k_in, t_in in enumerate(mentah):
                # Audit 1582: token -in yang menjadi NILAI
                # -out (mis. 'enc -out -in') bukan opsi
                # -in — openssl membacanya sebagai nama
                # berkas keluaran dan masukan tetap dari
                # stdin.
                if k_in > 0 and mentah[k_in - 1] == "-out" \
                        and (t_in in ("-in", "--in")
                             or t_in.startswith("-in=")
                             or t_in.startswith("--in=")):
                    continue
                if t_in in ("-in", "--in") or t_in.startswith("-in=") \
                        or t_in.startswith("--in="):
                    # Audit 1239: /dev/stdin & keluarga fd 0
                    # sebagai nilai -in ADALAH stdin.
                    if "=" in t_in:
                        hasil_in = t_in.split("=", 1)[1] not in (
                            "-", "/dev/stdin", "/dev/fd/0",
                            "/proc/self/fd/0")
                    else:
                        hasil_in = not (
                            k_in + 1 < len(mentah)
                            and mentah[k_in + 1] in (
                                "-", "/dev/stdin",
                                "/dev/fd/0",
                                "/proc/self/fd/0"))
            return bool(hasil_in)

        if sub == "enc" or sub == "base64" or re.fullmatch(
                pola_cipher, sub, re.IGNORECASE):
            # Audit 1561: regex nama cipher di atas dan
            # regex alias digest di cabang dgst sama-sama
            # tak peka huruf — openssl menyelesaikan nama
            # dinamis tanpa peka huruf (AES-256-CBC dan
            # SHA256 terprobe menguras), sedangkan nama
            # subcommand biasa tetap peka huruf.
            # Audit 1548: nama cipher (aes-256-cbc dkk., 44
            # nama dari openssl list -cipher-commands)
            # adalah alias enc — membaca stdin tanpa -in
            # sama seperti enc (terprobe).
            # Audit 1501: OpenSSL memakai -in TERAKHIR
            # juga di sini (seperti _in_non_stdin,
            # Audit 1278) — vonis lama menghitung
            # kemunculan "-" di posisi mana pun.
            return not _in_non_stdin()
        # Audit 1495: subcommand parameter yang tak
        # tertangani putaran-putaran sebelumnya —
        # pkeyparam/dhparam membaca parameter dari
        # stdin tanpa -in, dan "-" adalah stdin di
        # keduanya (terprobe; beda dari kelompok
        # pemuat objek di bawah).
        if sub in ("pkeyparam", "dhparam"):
            return not _in_non_stdin()
        if sub in ("smime", "cms", "pkeyutl", "rsautl"):
            # Audit 1161: rsautl (deprecated) membaca masukan
            # dari stdin tanpa -in, seperti pkeyutl.
            return not _in_non_stdin()
        # Audit 1402: sess_id/asn1parse pembaca objek juga —
        # tanpa -in DAN tanpa operand posisi membaca objek
        # sesi/DER dari stdin (operand posisi adalah berkas
        # masukannya, terprobe).
        if sub in ("sess_id", "asn1parse"):
            if _in_non_stdin():
                return False
            # Audit 1499: himpunan tak lagi dipakai
            # bersama mentah — -item hanya milik
            # asn1parse; di sess_id ia galat usage
            # sehingga nilainya bukan nilai opsi
            # (residu Audit 1443).
            bernilai_os = {"-in", "-inform", "-outform",
                           "-out", "-strparse", "-genconf",
                           "-config", "-nameopt", "-certopt",
                           # Audit 1417: opsi bernilai
                           # asn1parse yang hilang.
                           "-length", "-offset", "-depth",
                           "-dlimit", "-oid", "-genstr",
                           # Audit 1418: -context (sess_id).
                           "-context"}
            if sub == "asn1parse":
                # Audit 1443: -item (asn1parse).
                bernilai_os.add("-item")
            ops_os = _operand_sesudah_opsi(
                toks, awal + 1, bernilai_os)
            return not ops_os
        # Audit 1403: req -new tanpa -subj/-batch meminta
        # nama terbilang lewat prompt yang membaca stdin
        # (jawaban tak sah memicu prompt ulang).
        if sub == "req":
            # Audit 1419: tanpa -in, objek req (CSR/
            # sertifikat) dibaca dari stdin — bukan hanya
            # bentuk -new yang meminta prompt.
            if _in_non_stdin():
                return False
            if any(t == "-new" or t.startswith("-newkey")
                   for t in mentah):
                if any(t == "-subj" or t.startswith("-subj=")
                       for t in mentah):
                    return False
                return "-batch" not in mentah
            return True
        # Audit 1432: ts -query tanpa -data membaca
        # data yang di-hash dari stdin (keluarannya
        # permintaan stempel waktu DER yang sah).
        if sub == "ts":
            # Audit 1444: -data - menyerahkan data lewat
            # stdin — penjaga lama buta nilai.
            if "-query" not in mentah:
                return False
            # Audit 1497: OpenSSL memakai -data
            # TERAKHIR (seperti -in, Audit 1278) —
            # pindai 1444 berhenti di kemunculan
            # pertama sehingga "-data berkas -data -"
            # lolos dan arah sebaliknya vonis palsu.
            hasil_data = None
            for k_ts, t_ts in enumerate(mentah):
                if t_ts == "-data":
                    hasil_data = (k_ts + 1 < len(mentah)
                                  and mentah[k_ts + 1] == "-")
                elif t_ts.startswith("-data="):
                    hasil_data = t_ts == "-data=-"
            if hasil_data is None:
                return True
            return hasil_data
        # Audit 1433: ca dengan -in - membaca CSR dari
        # stdin (dengan konfigurasi CA lengkap).
        if sub == "ca":
            return "-in" in mentah and not _in_non_stdin()
        if sub == "passwd":
            return "-stdin" in mentah
        if sub == "s_client":
            return "-connect" in mentah
        # Audit 1496: mac membaca data yang di-MAC
        # dari stdin tanpa -in (dan -in -//dev/stdin),
        # sekelas dgst (terprobe).
        if sub == "mac":
            return not _in_non_stdin()
        # Audit 1495: ecparam/dsaparam membaca
        # parameter dari stdin tanpa -in, dengan
        # semantik pemuat objek yang sama seperti
        # kelompok di bawah ("-" nama berkas harfiah,
        # jalur fd adalah stdin — terprobe).
        if sub in ("x509", "rsa", "pkey", "dsa", "crl", "ec",
                   "ecparam", "dsaparam"):
            # Audit 1215: untuk keenamnya '-in -' BUKAN stdin di
            # OpenSSL 3.0.13 — pembuka OSSL_STORE menolak skema
            # '-' dan membaca 0 byte (terprobe satu per satu);
            # tanpa -in mereka tetap membaca objek dari stdin,
            # dan -in berkas tentu bukan stdin.
            # Audit 1239: -in /dev/stdin justru menguras —
            # jalur fd di pemuat objek adalah stdin itu
            # sendiri (terprobe dengan PEM sungguhan).
            # Audit 1278: -in terakhir yang dipakai openssl.
            ada_in = False
            in_terakhir_stdin = False
            for k_i2, t_i2 in enumerate(mentah):
                # Audit 1582: '-in' sebagai nilai -out
                # bukan opsi masukan (kelas yang sama).
                if k_i2 > 0 and mentah[k_i2 - 1] == "-out" \
                        and (t_i2 in ("-in", "--in")
                             or t_i2.startswith("-in=")
                             or t_i2.startswith("--in=")):
                    continue
                if t_i2 in ("-in", "--in"):
                    ada_in = True
                    in_terakhir_stdin = (
                        k_i2 + 1 < len(mentah)
                        and mentah[k_i2 + 1] in (
                            "/dev/stdin", "/dev/fd/0",
                            "/proc/self/fd/0"))
                elif (t_i2.startswith("-in=")
                      or t_i2.startswith("--in=")):
                    ada_in = True
                    in_terakhir_stdin = t_i2.split("=", 1)[1] in (
                        "/dev/stdin", "/dev/fd/0",
                        "/proc/self/fd/0")
            if ada_in:
                return in_terakhir_stdin
            return True
        if sub in ("pkcs7",
                   # Audit 1160: objek-tunggal putaran-3.
                   "pkcs8", "pkcs12", "crl2pkcs7", "nseq"):
            # Audit 1105: subcommand objek-tunggal tanpa -in
            # membaca satu objek dari stdin (pembaca sebagian —
            # kelas read-ahead yang diterima Audit 1057).
            # Audit 1215: kelompok ini terprobe justru MENGURAS
            # lewat -in - (jalur pemuat berbeda) — vonis lama
            # untuk mereka dipertahankan.
            return not _in_non_stdin()
        # Audit 1520: spkac tak punya cabang sama sekali.
        # Bentuk baca (tanpa -key) membaca SPKAC dari
        # stdin (bawaan tanpa -in, atau -in -/jalur fd —
        # menguras masukan SPKAC sah berulang, terprobe);
        # bentuk buat (-key ...) tidak menguras: kunci
        # dibaca dari berkas, dan "-key -" pun hanya
        # membaca satu kunci lalu berhenti (terprobe
        # dengan kunci sah berulang).
        if sub == "spkac":
            if "-key" in mentah or any(
                    t_sk.startswith("-key=")
                    for t_sk in mentah):
                return False
            return not _in_non_stdin()
        # Audit 1523: storeutl membaca objek dari operand
        # URI-nya — operand jalur fd (/dev/stdin &
        # keluarga) adalah stdin, sedangkan "-" nama
        # berkas harfiah seperti kelompok pemuat objek
        # (terprobe dengan PEM sungguhan).
        if sub == "storeutl":
            ops_s = _operand_sesudah_opsi(
                toks, awal + 1,
                {"-out", "-passin", "-engine", "-provider",
                 "-provider-path", "-propquery", "-alias",
                 "-subject", "-issuer", "-serial",
                 "-fingerprint"})
            # Audit 1546: bentuk URI file:// ke jalur fd
            # juga membaca stdin (openssl storeutl
            # file:///dev/stdin terprobe menguras) — kupas
            # skemanya sebelum mencocokkan jalur fd.
            # Audit 1551: varian file:/dev/stdin (satu
            # garis miring), file://localhost/... (ada
            # host), dan FILE: (huruf besar) juga URI
            # store yang sah (terprobe menguras) —
            # normalisasi: skema tak peka huruf + host
            # opsional sesudah '//'.
            # Audit 1564: normalisasi dipindah ke
            # _uri_store_ke_jalur — host asing BUKAN
            # stdin (fail-closed lama) dan garis miring
            # ganda dilipat (fail-open lama).
            return any(_uri_store_ke_jalur(o_s)
                       in ("/dev/stdin", "/dev/fd/0",
                           "/proc/self/fd/0")
                       for o_s in ops_s)
        if sub == "verify":
            # Audit 1128: verify tanpa operand sertifikat membaca
            # sertifikat dari stdin.
            ops_v = _operand_sesudah_opsi(
                toks, awal + 1,
                {"-CAfile", "-CApath", "-trusted", "-untrusted",
                 "-attime", "-policy", "-purpose", "-verify_depth",
                 "-auth_level", "-engine", "-provider", "-propquery",
                 "-nameopt",
                 # Audit 1149: opsi verifikasi bernilai lain —
                 # nilainya terhitung operand sertifikat.
                 "-verify_hostname", "-verify_email", "-verify_ip",
                 "-CRLfile", "-CAstore", "-policy_oids",
                 # Audit 1521: -vfyopt/-provider-path/
                 # -verify_name juga bernilai — nilainya
                 # terhitung operand penekan.
                 "-vfyopt", "-provider-path", "-verify_name"})
            return not ops_v
        # Audit 1580: regex alias digest kini juga
        # memuat 8 nama provider bawaan OpenSSL 3 yang
        # dulu absen (sha3-224/256/384/512,
        # sha512-224/256, shake128, shake256).
        if sub == "dgst" or re.fullmatch(
                pola_digest, sub, re.IGNORECASE):
            dasar = awal + 1 if sub == "dgst" else awal
            # Audit 1076: -rand bernilai — dulu hilang dari tabel
            # sehingga nilai berkasnya terhitung operand penekan.
            ops = _operand_sesudah_opsi(
                toks, dasar,
                {"-sign", "-verify", "-keyform", "-out", "-passin",
                 "-engine", "-fipskey", "-provider", "-propquery",
                 # Audit 1129: -mac/-macopt/-prverify bernilai —
                 # nilainya terbaca operand penekan.
                 # Audit 1148: -hmac/-sigopt ketinggalan.
                 "-rand", "-mac", "-macopt", "-prverify",
                 "-hmac", "-sigopt",
                 # Audit 1581: -signature/-writerand/
                 # -provider-path/-xoflen bernilai di dgst
                 # tetapi absen (-provider-path hanya
                 # ditambahkan ke verify pada Audit 1521).
                 "-signature", "-writerand",
                 "-provider-path", "-xoflen"})
            # Audit 1135: opsi menggantung = program galat.
            if "\x00gantung" in ops:
                return False
            if sub != "dgst":
                ops = ops[1:] if ops else []
            return not ops or "-" in ops
        return False
    if perintah == "tar":
        # Audit 1061: arsip '-' (nilai -f) atau daftar -T '-'.
        # Audit 1077: bundel gaya lama tanpa strip (tf) dan nilai
        # merekat pada bundel (-tf-, -f-) — regex lama mewajibkan
        # huruf sesudah f/T sehingga '-' terakhir tak cocok.
        mode_tar = None
        nilai_f = None
        nilai_T = None
        # Audit 1152: -f/-T di ujung tanpa nilai = galat usage.
        gantung_f = False
        gantung_T = False
        i = 0
        while i < len(mentah):
            t = mentah[i]
            if t in ("--create",):
                mode_tar = "c"
            elif t in ("--extract", "--get"):
                mode_tar = "x"
            elif t in ("--list",):
                mode_tar = "t"
            if t in ("-f", "--file"):
                if i + 1 < len(mentah):
                    nilai_f = mentah[i + 1]
                else:
                    gantung_f = True
                i += 2
                continue
            if t in ("-T", "--files-from"):
                if i + 1 < len(mentah):
                    nilai_T = mentah[i + 1]
                else:
                    gantung_T = True
                i += 2
                continue
            if (t.startswith("--file=")
                    or t.startswith("--files-from=")):
                if t.startswith("--file="):
                    nilai_f = t.split("=", 1)[1]
                else:
                    nilai_T = t.split("=", 1)[1]
                i += 1
                continue
            bundel = None
            if re.fullmatch(r"-[a-z]+-?", t):
                bundel = t[1:].rstrip("-")
                if t.endswith("-") and bundel and bundel[-1] in "fT":
                    # nilai merekat "-" pada huruf terakhir bundel
                    if bundel[-1] == "f":
                        nilai_f = "-"
                    else:
                        nilai_T = "-"
            elif i == 0 and re.fullmatch(r"[a-z]+", t):
                # bundel gaya lama: huruf-huruf tanpa strip.
                bundel = t
            if bundel:
                if mode_tar is None:
                    for huruf_t in bundel:
                        if huruf_t in "cxt":
                            mode_tar = huruf_t
                            break
                if bundel[-1] in "fT" and not (
                        t.endswith("-") and len(t) > 1
                        and t[0] == "-"):
                    # nilai f/T adalah token berikutnya
                    if i + 1 < len(mentah):
                        if bundel[-1] == "f":
                            nilai_f = mentah[i + 1]
                        else:
                            nilai_T = mentah[i + 1]
                        i += 1
                    elif bundel[-1] == "f":
                        gantung_f = True
                    else:
                        gantung_T = True
            i += 1
        # Audit 1187: cek menggantung WAJIB mendahului return
        # '-' — opsi menggantung adalah galat usage yang
        # menghentikan program sebelum '-' sempat dibaca.
        # Audit 1152: -f/-T menggantung = galat usage (rc 64).
        if gantung_f or gantung_T:
            return False
        # Audit 1188: daftar -T KOSONG membuat tar gagal membuka
        # daftar nama sebelum membaca arsip.
        if nilai_T == "":
            return False
        if nilai_T == "-":
            return True
        if nilai_f == "-":
            # Audit 1078: pada mode buat (-c), '-f -' adalah arsip
            # KELUARAN, bukan masukan — yang menguras hanya -T '-'.
            return mode_tar != "c"
        # Audit 1120: tanpa -f, arsip bawaan GNU tar untuk mode
        # baca (t/x) adalah stdin.
        # Audit 1151: -T hanya memasok NAMA anggota, bukan sumber
        # arsip — keberadaannya tak menggugurkan aturan ini.
        if nilai_f is None:
            return mode_tar in ("t", "x")
        return False
    if perintah == "git":
        # Audit 1062: subcommand penyaring stdin.
        # Audit 1079: pembaca putaran-2 — hash-object --stdin-paths,
        # credential fill, mktree, update-index --stdin, dan
        # berkas pesan '-' pada commit/tag (-F -).
        ops_git = _operand_sesudah_opsi(
            toks, awal, {"-C", "-c", "--git-dir", "--work-tree",
                         "--namespace"})
        if not ops_git:
            return False
        sub = ops_git[0]
        if sub == "apply":
            # Audit 1262: nilai opsi bernilai (mis.
            # --build-fake-ancestor) bukan berkas patch.
            ops_ap = _operand_sesudah_opsi(
                toks, awal + 1,
                {"--build-fake-ancestor", "--directory",
                 "--exclude", "--include", "-C", "--whitespace"})
            if "\x00gantung" in ops_ap:
                return False
            return not ops_ap or "-" in ops_ap
        if sub == "hash-object":
            return "--stdin" in mentah or "--stdin-paths" in mentah
        if sub == "cat-file":
            # Audit 1266: --batch-all-objects menghitung
            # objek repo sendiri — stdin tak dibaca.
            if "--batch-all-objects" in mentah:
                return False
            # Audit 1261: bentuk berformat '=' juga batch.
            return any(t in ("--batch", "--batch-check",
                             "--batch-command")
                       or t.startswith("--batch=")
                       or t.startswith("--batch-check=")
                       or t.startswith("--batch-command=")
                       for t in mentah)
        if sub == "fast-import":
            return True
        if sub == "credential":
            # Audit 1399: approve/reject membaca deskripsi
            # protokol persis seperti fill (konsumsi byte
            # stdin terprobe identik).
            return len(ops_git) > 1 and ops_git[1] in (
                "fill", "approve", "reject")
        # Audit 1400: format-patch --stdin membaca daftar
        # revisi dari stdin sampai EOF.
        if sub == "format-patch":
            # Audit 1416: --stdin hanya berarti bila ia
            # bukan NILAI opsi lain (mis. nilai
            # --output-directory — terbukti token itu
            # menjadi nama direktori keluaran).
            k_fp = 0
            while k_fp < len(mentah):
                t_fp = mentah[k_fp]
                if t_fp == "--":
                    break
                if t_fp == "--stdin":
                    return True
                if t_fp in ("-o", "--output-directory",
                            "--subject-prefix", "--base",
                            "--reroll-count", "--to", "--cc",
                            "--from", "--in-reply-to",
                            "--signature-file",
                            "--cover-from-description",
                            # Audit 1449: penelan nilai
                            # yang hilang — --stdin
                            # sempat menjadi NILAI
                            # --start-number.
                            "--start-number", "--thread-depth",
                            "--filename-max-length",
                            # Audit 1503: penelan nilai
                            # gelombang berikutnya —
                            # --stdin sempat menjadi
                            # NILAI --signature dkk.
                            "--signature", "--suffix",
                            "--add-header", "--interdiff",
                            "--range-diff",
                            "--creation-factor"):
                    k_fp += 2
                    continue
                k_fp += 1
            return False
        # Audit 1434: fmt-merge-msg tanpa berkas/-F
        # membaca log gabungan dari stdin seluruhnya
        # lebih dulu (terprobe di dalam repo).
        if sub == "fmt-merge-msg":
            # Audit 1441: nilai opsi subcommand (-m,
            # --message, --into-name) bukan operand
            # berkas — hitung operand dengan himpunan
            # bernilai yang memuatnya.
            ops_fm = _operand_sesudah_opsi(
                toks, awal,
                {"-C", "-c", "--git-dir", "--work-tree",
                 "--namespace", "-m", "--message",
                 "--into-name"})
            # Audit 1442: -F/--file bernilai "-" berarti
            # berkas log gabungan adalah stdin itu
            # sendiri. Pindai sadar nilai: nilai -m bisa
            # saja berbentuk "-F".
            k_fm = 0
            while k_fm < len(mentah):
                t_fm = mentah[k_fm]
                if t_fm == "--":
                    break
                if t_fm in ("-m", "--message", "--into-name"):
                    k_fm += 2
                    continue
                if t_fm in ("-F", "--file"):
                    return (k_fm + 1 < len(mentah)
                            and mentah[k_fm + 1] == "-")
                if t_fm in ("-F-", "--file=-"):
                    return True
                if t_fm.startswith("--file=") or (
                        t_fm.startswith("-F")
                        and len(t_fm) > 2):
                    return False
                k_fm += 1
            return len(ops_fm) < 2
        if sub == "mktree":
            return True
        # Audit 1124: pembaca putaran-3 — stripspace (filter
        # murni), patch-id, mktag, update-index --index-info,
        # index-pack --stdin.
        if sub in ("stripspace", "patch-id", "mktag"):
            return True
        if sub == "index-pack":
            return "--stdin" in mentah
        if sub == "update-index":
            return "--stdin" in mentah or "--index-info" in mentah
        # Audit 1207: daftar pathspec dari stdin.
        # Audit 1226: bentuk terpisah '--pathspec-from-file -'
        # juga sah.
        # Audit 1227: hanya subcommand ber-pathspec — cek
        # lintas-subcommand lama membuat rev-list (menolak
        # opsi ini) divonis menguras.
        # Audit 1268: cek ini dihoist SEBELUM cabang
        # commit/tag (di bawah gilirannya) — commit ada di
        # allowlist tetapi cabangnya mengembalikan vonis
        # lebih dulu sehingga tak pernah terjangkau.
        # Audit 1269: token yang menjadi NILAI opsi bernilai
        # (mis. nilai --chmod) bukan opsi pathspec.
        if sub in ("add", "checkout", "commit", "restore",
                   "rm", "reset", "stash"):
            bernilai_ps = {"--chmod", "-F", "--file", "-m",
                           "--message", "--template", "--author",
                           "--date", "--cleanup", "--trailer",
                           "--gpg-sign", "--key-id-format"}
            k_ps = 0
            while k_ps < len(mentah):
                t_ps = mentah[k_ps]
                if t_ps == "--pathspec-from-file=-":
                    return True
                if t_ps == "--pathspec-from-file" and (
                        k_ps + 1 < len(mentah)
                        and mentah[k_ps + 1] == "-"):
                    return True
                if t_ps in bernilai_ps:
                    k_ps += 2
                    continue
                k_ps += 1
        # Audit 1401: mode tambal/interaktif git membaca
        # jawaban prompt dari stdin sampai EOF (-p/--patch
        # pada add/checkout/reset/restore/stash; -i/
        # --interactive pada add/clean) — termasuk di dalam
        # bundel huruf pendek.
        # Audit 1415: pindaian -p/-i wajib sadar posisi —
        # token yang menjadi NILAI opsi lain (mis. -p
        # sebagai pesan -m) dan token sesudah -- (nama
        # berkas harfiah) bukan bendera.
        bernilai_in = {"-m", "--message", "-F", "--file",
                       "--pathspec-from-file", "--source",
                       "-b", "-B", "--orphan", "-t", "--track",
                       "--onto", "--strategy", "-s",
                       "--strategy-option", "-X"}
        token_in = []
        k_in = 0
        while k_in < len(mentah):
            t_k = mentah[k_in]
            if t_k == "--":
                break
            if t_k in bernilai_in:
                k_in += 2
                continue
            token_in.append(t_k)
            k_in += 1
        if sub in ("add", "checkout", "reset", "restore",
                   "stash"):
            for t_in in token_in:
                if t_in in ("-p", "--patch") or re.fullmatch(
                        r"-[a-zA-Z]*p[a-zA-Z]*", t_in):
                    return True
        if sub in ("add", "clean"):
            for t_in in token_in:
                if t_in in ("-i", "--interactive") or re.fullmatch(
                        r"-[a-zA-Z]*i[a-zA-Z]*", t_in):
                    return True
        if sub in ("commit", "tag"):
            pesan_stdin = False
            # Audit 1502: pindai -F kini sadar nilai
            # (meniru pola fmt-merge-msg Audit 1442) —
            # "-F" yang menjadi NILAI opsi lain
            # (mis. -m -F -) bukan bendera berkas.
            k_g = 0
            while k_g < len(mentah):
                t_g = mentah[k_g]
                if t_g == "--":
                    break
                if t_g in ("-F", "--file"):
                    if k_g + 1 < len(mentah) \
                            and mentah[k_g + 1] == "-":
                        pesan_stdin = True
                    k_g += 2
                    continue
                if t_g in ("-F-", "--file=-"):
                    pesan_stdin = True
                elif t_g.startswith("--file="):
                    if t_g.split("=", 1)[1] == "-":
                        pesan_stdin = True
                elif t_g.startswith("-F") \
                        and not t_g.startswith("--") \
                        and len(t_g) > 2:
                    if t_g[2:] == "-":
                        pesan_stdin = True
                elif (t_g.startswith("-")
                        and not t_g.startswith("--")
                        and len(t_g) > 2):
                    # Audit 1522: -F bisa bersembunyi di
                    # dalam bundel huruf pendek ("-aF -",
                    # "-aF-") — git mem-parse bundel huruf
                    # demi huruf: opsi bernilai wajib
                    # menelan sisa token (atau token
                    # berikut bila ia huruf terakhir);
                    # -u/-S bernilai opsional hanya
                    # menelan sisa token.
                    isi_b = t_g[1:]
                    j_b = 0
                    vonis_b = None
                    lompat_b = 1
                    while j_b < len(isi_b):
                        c_b = isi_b[j_b]
                        if c_b == "F":
                            sisa_b = isi_b[j_b + 1:]
                            if sisa_b:
                                vonis_b = sisa_b == "-"
                            else:
                                vonis_b = (
                                    k_g + 1 < len(mentah)
                                    and mentah[k_g + 1] == "-")
                                lompat_b = 2
                            break
                        if c_b in ("m", "C", "c", "t"):
                            if j_b + 1 >= len(isi_b):
                                lompat_b = 2
                            vonis_b = False
                            break
                        if c_b in ("u", "S"):
                            vonis_b = False
                            break
                        if not c_b.isalpha():
                            break
                        j_b += 1
                    if vonis_b is None:
                        k_g += 1
                    else:
                        if vonis_b:
                            pesan_stdin = True
                        k_g += lompat_b
                    continue
                # Audit 1524: --cleanup/--author/--template/
                # --trailer juga bernilai di commit/tag —
                # tak ada di bernilai_in (dipakai bersama
                # subcommand lain); nilai mereka (mis. -F)
                # bukan bendera berkas.
                # Audit 1535: "-s" DIKECUALIKAN di sini — di
                # bernilai_in ia untuk --strategy milik merge,
                # tetapi di commit -s = --signoff dan di tag
                # -s = --sign, keduanya bendera TANPA nilai;
                # memperlakukannya bernilai menelan -F
                # berikutnya ("git commit -s -F -" lolos).
                elif (t_g in bernilai_in and t_g != "-s") or t_g in (
                        "--cleanup", "--author", "--template",
                        "--trailer"):
                    k_g += 2
                    continue
                k_g += 1
            if not pesan_stdin:
                return False
            if sub == "commit":
                return True
            # git tag tanpa nama tag = galat usage SEBELUM membaca
            # pesan (terverifikasi) — nama tag adalah operand
            # selain '-' itu sendiri.
            return any(o != "-" for o in ops_git[1:])
        # Audit 1162: pembaca putaran-4 — pesan commit tanpa
        # -m/-F dari stdin; trailer & kolom & belah mbox tanpa
        # operand berkas; mailinfo/pack/unpack/imap selalu.
        if sub == "commit-tree":
            for t_ct in mentah:
                if t_ct in ("-F", "--file"):
                    k_ct = mentah.index(t_ct)
                    return (k_ct + 1 < len(mentah)
                            and mentah[k_ct + 1] == "-")
                if t_ct.startswith("--file="):
                    return t_ct.split("=", 1)[1] == "-"
                if (t_ct.startswith("-F")
                        and not t_ct.startswith("--")
                        and len(t_ct) > 2):
                    return t_ct[2:] == "-"
                if t_ct in ("-m", "--message") or (
                        t_ct.startswith("--message")) or (
                        t_ct.startswith("-m")
                        and not t_ct.startswith("--")
                        and len(t_ct) > 2):
                    return False
            return True
        if sub == "interpret-trailers":
            ops_it = _operand_sesudah_opsi(
                toks, awal + 1,
                {"--trailer", "--where", "--if-exists",
                 "--if-missing"})
            return not ops_it or "-" in ops_it
        if sub in ("mailinfo", "pack-objects", "unpack-objects",
                   "imap-send"):
            return True
        if sub == "mailsplit":
            # -o wajib di git modern: tanpanya galat usage
            # sebelum membaca (terverifikasi rc 129).
            ada_o = any(t == "-o" or t.startswith("-o")
                        for t in mentah)
            ops_ms = _operand_sesudah_opsi(
                toks, awal + 1, {"-o", "-d", "-f"})
            return ada_o and (not ops_ms or "-" in ops_ms)
        if sub == "column":
            ops_co = _operand_sesudah_opsi(
                toks, awal + 1,
                {"--mode", "--width", "--separator",
                 "--output-width", "--padding",
                 # Audit 1184: --indent/--nl bernilai, hilang.
                 "--indent", "--nl"})
            return not ops_co or "-" in ops_co
        # Audit 1205: pembaca langsung putaran-5.
        if sub == "am":
            ops_am = _operand_sesudah_opsi(
                toks, awal + 1, {"--patch-format",
                                 # Audit 1221: opsi bernilai am
                                 # lain yang hilang.
                                 "--exclude", "--include",
                                 "--directory", "--quoted-cr",
                                 # Audit 1273: --whitespace.
                                 "--whitespace",
                                 "-C"})
            return not ops_am or "-" in ops_am
        if sub == "shortlog":
            ops_sl = _operand_sesudah_opsi(
                toks, awal + 1, {"--format",
                                 # Audit 1223: --group bernilai.
                                 "--group",
                                 # Audit 1272: opsi revisi
                                 # bernilai lain — nilainya
                                 # terbaca operand revisi.
                                 "--since", "--until", "--before",
                                 "--after", "--author",
                                 "--committer"})
            # Audit 1222: -w shortlog bernilai OPSIONAL
            # (hanya merekat) — diperlakukan bendera.
            return not ops_sl
        # Audit 1206: keluarga --stdin putaran-5.
        if sub in ("update-ref", "check-attr", "check-ignore",
                   "diff-tree", "name-rev"):
            # Audit 1224: check-ignore --stdin menolak
            # operand jalur (fatal) — sub lain tak terprobe
            # menolak operandnya.
            if sub == "check-ignore" and "--stdin" in mentah:
                return not ops_git[1:]
            return "--stdin" in mentah
        # Audit 1207: catatan dari berkas pesan stdin.
        if sub == "notes":
            for k_nt, t_nt in enumerate(mentah):
                if t_nt in ("--file=-",
                            # Audit 1225: -F- merekat.
                            "-F-"):
                    return True
                # Audit 1274: -F- di dalam bundel pendek
                # (mis. -fF-).
                if re.fullmatch(r"-[A-Za-z]*F-", t_nt):
                    return True
                if t_nt in ("-F", "--file") and (
                        k_nt + 1 < len(mentah)
                        and mentah[k_nt + 1] == "-"):
                    return True
            return False
        # Audit 1163: --stdin plumbing & protokol credential.
        if sub in ("rev-list", "log"):
            return "--stdin" in mentah
        if sub == "check-mailmap":
            # Audit 1185: telanjang = fatal 'no contacts
            # specified' — tak membaca.
            # Audit 1218/1219: operand '-' BUKAN stdin
            # (fatal parse).
            # Audit 1270: --stdin bersama kontak argv yang SAH
            # tetap menguras — yang fatal hanya kontak tak
            # sah (kata polos/'-'/email polos tanpa kurung
            # sudut, semua terprobe fatal sebelum membaca).
            if "--stdin" in mentah:
                return all("<" in o and ">" in o
                           for o in ops_git[1:])
            return False
        if sub in ("credential-store", "credential-cache"):
            # protokol helper membaca deskripsi dari stdin
            # untuk semua aksinya (get/store/erase).
            # Audit 1186: TANPA aksi = galat usage, tak membaca.
            # Audit 1220: kata aksi yang menjadi NILAI opsi
            # (--file get) bukan aksi — lewati nilai opsi.
            # Audit 1271: mulai SESUDAH token subcommand —
            # opsi global git (-C <path> dsb.) sebelumnya
            # menggeser posisi; nilai opsi helper lain
            # (--timeout/--socket cache) juga ditelan.
            i_cr = (mentah.index(sub) + 1 if sub in mentah
                    else 1)
            while i_cr < len(mentah):
                t_cr = mentah[i_cr]
                if t_cr in ("--file", "--timeout", "--socket"):
                    i_cr += 2
                    continue
                if t_cr.startswith("--file=") or (
                        t_cr.startswith("-") and t_cr != "-"):
                    i_cr += 1
                    continue
                return t_cr in ("get", "store", "erase")
            return False
        return False
    if perintah == "comm":
        # Audit 1063: '-' di posisi operand mana pun = stdin.
        # Audit 1074: -1/-2/-3 pada comm adalah BENDERA penekan
        # kolom, bukan opsi bernilai — memasukkannya ke tabel
        # membuat nilai bendera menelan operand '-'.
        # Audit 1110: --output-delimiter bernilai baru ditambah.
        ops_c = _operand_sesudah_opsi(
            toks, awal, {"--output-delimiter"})
        # Audit 1135: opsi menggantung = program galat.
        if "\x00gantung" in ops_c:
            return False
        return "-" in ops_c
    if perintah == "join":
        # Audit 1063: '-' di posisi operand mana pun = stdin
        # (di join -1/-2 memang bernilai nomor field).
        ops_j = _operand_sesudah_opsi(
            toks, awal, {"-1", "-2", "-o", "-t", "-e", "-a", "-v",
                         "--output", "--separator"})
        # Audit 1135: opsi menggantung = program galat.
        if "\x00gantung" in ops_j:
            return False
        return "-" in ops_j
    if perintah in ("diff", "diff3"):
        ops_d = _operand_sesudah_opsi(
            toks, awal, {"-U", "-C", "-A", "--unified", "--context",
                         "--width", "-D", "--ifdef", "--label",
                         "--line-format", "--GTYPE-group-format",
                         "--LTYPE-line-format"})
        # Audit 1135: opsi menggantung = program galat.
        if "\x00gantung" in ops_d:
            return False
        return "-" in ops_d
    if perintah == "sdiff":
        # Audit 1249: operand '-' sdiff adalah stdin
        # (sekelas diff), tak dikenal khusus sebelumnya.
        # Audit 1282: -W adalah BENDERA abaikan-spasi; yang
        # bernilai -w/--width (tertulis terbalik sebelumnya).
        ops_sdf = _operand_sesudah_opsi(
            toks, awal, {"-w", "--width", "-o", "--output"})
        if "\x00gantung" in ops_sdf:
            return False
        return "-" in ops_sdf
    if perintah == "csplit":
        ops = _operand_sesudah_opsi(
            toks, awal, {"-f", "--prefix", "-n", "--digits", "-b",
                         "--suffix-format"})
        # Audit 1135: opsi menggantung = program galat.
        if "\x00gantung" in ops:
            return False
        return bool(ops) and ops[0] == "-"
    if perintah in ("pdfinfo", "pdffonts", "pdfimages",
                    "pdftops"):
        # Audit 1258: keluarga Poppler — operand masukan
        # pertama '-' = PDF dari stdin (pdftops beroperand
        # keluaran kedua; '-' pertama tetap masukan).
        ops_pp = _operand_sesudah_opsi(
            toks, awal, {"-f", "-l", "-opw", "-upw", "-r",
                         "-x", "-y", "-W", "-H", "-cfg",
                         "-subst", "-paperw", "-paperh",
                         # Audit 1283: opsi bernilai keluarga
                         # yang hilang (-enc pdfinfo; -paper/
                         # -aaRaster/-aa/-aaVector/-pass
                         # pdftops).
                         "-enc", "-paper", "-aaRaster", "-aa",
                         "-aaVector", "-pass"})
        if "\x00gantung" in ops_pp:
            return False
        return bool(ops_pp) and ops_pp[0] == "-"
    if perintah == "gm":
        # Audit 1259: gm identify '-' membaca gambar dari
        # stdin; subcommand lain tak dinilai di sini.
        # Audit 1284: '-' sebagai NILAI opsi (mis. -format -)
        # bukan operand — penilaian wajib sadar opsi.
        # Audit 1285: convert beroperand '-' juga menguras,
        # dan batch tanpa berkas membaca perintah dari stdin.
        # Audit 1348: -append/-average adalah operator daftar
        # gambar TANPA nilai (bukan opsi bernilai); entri
        # -stroke ganda dibersihkan.
        if mentah and mentah[0] in ("identify", "convert"):
            ops_gm = _operand_sesudah_opsi(
                toks, awal + 1,
                {"-format", "-define", "-density", "-label",
                 "-limit", "-sampling-factor", "-size",
                 "-geometry", "-frame", "-tile", "-bordercolor",
                 "-font", "-fill", "-stroke", "-draw",
                 "-background", "-gravity", "-channel",
                 "-colorspace", "-depth", "-quality", "-rotate",
                 "-scale", "-resize", "-crop", "-extent",
                 "-compose", "-delay", "-dispose", "-output",
                 "-type", "-encoding", "-pointsize",
                 "-strokewidth", "-box", "-comment", "-caption",
                 "-texture", "-affine"})
            return "-" in ops_gm or "/dev/stdin" in ops_gm
        if mentah and mentah[0] == "batch":
            return len(mentah) == 1
        return False
    if perintah == "zip":
        # Audit 1064: -@ membaca daftar nama dari stdin.
        # Audit 1094: tanpa operand arsip, zip galat sebelum
        # membaca daftar — -@ saja tidak menguras.
        # Audit 1763: operand berkas masukan "-" mengarsipkan
        # isi stdin sebagai entri "-" (terprobe biner nyata).
        polos = [t for t in mentah
                 if not t.startswith("-") or t == "-"]
        if len(polos) >= 2 and "-" in polos[1:]:
            return True
        if not any(t == "-@" or re.fullmatch(r"-[a-z]*@", t)
                   for t in mentah):
            return False
        # Audit 1130: arsip "-" (ditulis ke stdout) adalah operand
        # arsip yang sah — dulu tak terhitung karena berawalan '-'.
        return any(not t.startswith("-") or t == "-"
                   for t in mentah)
    if perintah == "rsync":
        # Audit 1064: --files-from=- membaca daftar dari stdin.
        for i, t in enumerate(mentah):
            if t == "--files-from=-":
                return True
            if t == "--files-from" and i + 1 < len(mentah) \
                    and mentah[i + 1] == "-":
                return True
        return False
    if perintah in ("socat",
                    # Audit 1250: nama binary socat sebenarnya.
                    "socat1"):
        # Audit 1066: alamat '-' / STDIO / STDIN membaca stdin.
        return any(t == "-" or t.startswith("STDIO")
                   or t.startswith("STDIN") for t in mentah
                   if not t.startswith("-") or t == "-")
    if perintah in ("gpg", "gpg2"):
        # Audit 1638: gpg2 adalah nama historis binary GnuPG 2 —
        # semantik opsi & operandnya sama persis dengan gpg.
        # Audit 1069: enkripsi/tanda tangan simetris batch tanpa
        # operand berkas membaca plaintext dari stdin.
        bernilai_gpg = {"-o", "--output", "--homedir", "-r", "--recipient",
             "-u", "--local-user", "--passphrase",
             "--passphrase-fd", "--passphrase-file", "--keyring",
             "--status-fd", "--logger-fd", "--attribute-fd",
             "--pinentry-mode", "--command-fd", "--keyserver",
             "--cipher-algo", "--digest-algo", "--compress-algo",
             "--cert-digest-algo", "--s2k-cipher-algo",
             "--s2k-digest-algo", "--agent-program",
             # Audit 1549: himpunan ini tak pernah disapu
             # dump-options — 10 opsi bernilai terprobe
             # fail-open (nilainya terhitung operand
             # penekan); gpg menolak token dash sebagai
             # nilai sehingga arah sebaliknya aman.
             "--s2k-mode", "--s2k-count", "--faked-system-time",
             "--debug", "--default-key", "--log-file",
             "--passphrase-repeat", "--charset",
             "--compress-level", "--comment",
             # Audit 1562: sapuan dump-options kedua —
             # himpunan 1549 ternyata jauh dari lengkap
             # (±92 nama bernilai terprobe fail-open,
             # termasuk yang lazim di skrip: --options,
             # --trust-model, --status-file, --keyid-format,
             # -z terpisah, dan keluarga penerima enkripsi
             # --encrypt-to/--hidden-recipient/
             # --recipient-file). Penahanan audit-91 atas
             # --encrypt-to dicabut: artefak kunci fixture
             # sign-only; terprobe SELISIH terisolasi.
             "-z", "--aead-algo", "--assert-pubkey-algo",
             "--assert-signer", "--attribute-file",
             "--auto-key-locate", "--bzip2-compress-level",
             "--cert-policy-url", "--chuid", "--chunk-size",
             "--command-file", "--compatibility-flags",
             "--completes-needed", "--compliance",
             "--ctapi-driver", "--debug-level",
             "--debug-set-iobuf-size",
             "--default-cert-check-level",
             "--default-cert-expire", "--default-cert-level",
             "--default-keyserver-url",
             "--default-new-key-algo",
             "--default-preference-list",
             "--default-recipient", "--default-sig-expire",
             "--dirmngr-program", "--disable-cipher-algo",
             "--disable-pubkey-algo", "--display",
             "--display-charset", "--encrypt-to",
             "--exec-path", "--export-filter",
             "--export-options", "--group",
             "--hidden-encrypt-to", "--hidden-recipient",
             "--hidden-recipient-file", "--import-filter",
             "--import-options", "--input-size-hint",
             "--kbx-buffer-size", "--key-origin",
             "--keyboxd-program", "--keyid-format",
             "--keyserver-options", "--known-notation",
             "--lc-ctype", "--lc-messages",
             "--limit-card-insert-tries", "--list-options",
             "--logger-file", "--marginals-needed",
             "--max-cert-depth", "--max-output",
             "--min-cert-level", "--min-rsa-length",
             "--options", "--override-session-key",
             "--override-session-key-fd", "--pcsc-driver",
             "--personal-aead-preferences",
             "--personal-cipher-preferences",
             "--personal-compress-preferences",
             "--personal-digest-preferences",
             "--photo-viewer", "--primary-keyring",
             "--reader-port", "--recipient-file",
             "--request-origin", "--secret-keyring",
             "--sender", "--set-filename", "--set-filesize",
             "--set-notation", "--set-policy-url",
             "--sig-keyserver-url", "--sig-policy-url",
             "--sign-with", "--status-file",
             "--temp-directory", "--tofu-db-format",
             "--tofu-default-policy", "--trust-model",
             "--trustdb-name", "--trusted-key",
             "--try-secret-key", "--ttyname", "--ttytype",
             "--user", "--verify-options", "--weak-digest",
             "--xauthority",
             # Audit 1587: --cert-notation/--sig-notation
             # bernilai (notasi nama@nilai) — tertahan
             # audit-92, kini terprobe fail-open.
             "--cert-notation", "--sig-notation",
             # Audit 1588: -N, opsi pendek notasi gpg —
             # selisih dua arah bila absen.
             "-N",
             # Audit 1589: alias bernilai berbagi opcode
             # dengan nama yang sudah ada di himpunan.
             "--compression-algo",
             "--personal-cipher-prefs",
             "--personal-compress-prefs", "--remote-user",
             # Audit 1590: lima nama bernilai lain dari
             # dump-options yang absen.
             "--ungroup", "--add-desig-revoker",
             "--force-ownertrust", "--gpg-agent-info",
             "--debug-allow-large-chunks"}
        ops = _operand_sesudah_opsi(
            toks, awal, bernilai_gpg)
        # Audit 1135: opsi menggantung = program galat.
        if "\x00gantung" in ops:
            return False
        # Audit 1106: operasi stdin di luar mode kripto — import/
        # dearmor/enarmor/list-packets tanpa operand berkas, dan
        # print-md yang operand tunggalnya nama algoritma.
        # Audit 1123: --verify tanpa operand membaca bahan
        # tanda tangan dari stdin.
        # Audit 1591: deteksi modus sadar nilai — token
        # modus yang menjadi NILAI opsi bernilai (mis.
        # --options --clearsign) bukan modus; gpgsm
        # sudah begini sejak Audit 1504. --tofu-policy
        # menelan nilai untuk pemindaian ini saja
        # (opcode perintah; nilainya tetap terhitung
        # operand di ops).
        modus = []
        k_md = 0
        while k_md < len(mentah):
            t_md = mentah[k_md]
            modus.append(t_md)
            if (t_md in bernilai_gpg
                    or t_md == "--tofu-policy") \
                    and "=" not in t_md \
                    and k_md + 1 < len(mentah):
                k_md += 1
            k_md += 1
        if any(t in ("--import", "--dearmor", "--enarmor",
                     "--list-packets", "--verify")
               for t in modus):
            # Audit 1150: operand "-" adalah stdin juga di sini
            # (gpg --verify - membaca tanda tangan dari stdin).
            return not ops or "-" in ops
        if "--print-md" in modus:
            return len(ops) <= 1
        kripto = any(t in ("-c", "--symmetric", "-e", "--encrypt",
                           "-s", "--sign", "-d", "--decrypt",
                           "--clearsign", "--detach-sign")
                     or t.startswith("--symmetric") for t in modus)
        bendera_saja = all(
            t == "-" or t in bernilai_gpg
            or t in ("--batch", "--yes", "--no", "--quiet",
                     "--verbose", "-v", "--no-verbose",
                     "--dry-run", "--no-tty")
            for t in modus)
        if ops:
            # Audit 1756: operand "-" adalah stdin juga pada
            # modus kripto dan gpg nyaris telanjang (hanya
            # bendera global; terprobe biner nyata) - sejajar
            # keluarga verify Audit 1150.
            return "-" in ops and (kripto or bendera_saja)
        return kripto
    # Audit 1428: gpgsm — --decrypt/--verify tanpa
    # operand berkas membaca pesan CMS dari stdin
    # (--sign/--encrypt terbukti gagal lebih dulu).
    if perintah == "gpgsm":
        # Audit 1504: token modus yang menjadi NILAI
        # opsi (mis. --options --decrypt) bukan modus —
        # deteksi kini sadar nilai, melompati nilai
        # opsi bernilai gpgsm yang sama seperti
        # himpunan operand di bawah.
        # Audit 1525: himpunan ini (dan salinannya di
        # himpunan operand di bawah — keduanya wajib
        # identik) tak lengkap: --log-file/--p12-charset/
        # --default-key/--policy-file/--encrypt-to/
        # --audit-log/--debug-level/--include-certs/
        # --keyring juga bernilai (dari gpgsm --help,
        # sebagian terprobe).
        # Audit 1545: dilengkapi lagi dari gpgsm
        # --dump-options + probe dua arah (27 opsi):
        # --pinentry-mode/--html-audit-log/
        # --extra-digest-algo/--validation-model/
        # --keyserver/--dirmngr-program/--compliance/
        # --keyboxd-program/--protect-tool-program/
        # --min-rsa-length/--ignore-cert-extension/
        # --ignore-cert-with-oid/--ldapserver/
        # --disable-cipher-algo/--disable-pubkey-algo/
        # --request-origin/--faked-system-time/
        # --kbx-buffer-size/--input-size-hint/
        # --compatibility-flags/--display/--ttyname/
        # --ttytype/--lc-ctype/--lc-messages/
        # --xauthority/--chuid.
        bernilai_gs = {
                       "--agent-program", "--attribute-fd", "--audit-log", "--chuid",
                       "--cipher-algo", "--command-fd", "--compatibility-flags", "--compliance",
                       "--compress-algo", "--debug-level", "--debug-wait", "--default-key", "--debug", "--attribute", "--digest-algo",
                       "--dirmngr-program", "--disable-cipher-algo", "--disable-pubkey-algo", "--display",
                       "--encrypt-to", "--extra-digest-algo", "--faked-system-time", "--homedir",
                       "--html-audit-log", "--ignore-cert-extension", "--ignore-cert-with-oid", "--include-certs",
                       "--input-size-hint", "--kbx-buffer-size", "--keyboxd-program", "--keyring",
                       "--keyserver", "--lc-ctype", "--lc-messages", "--ldapserver",
                       "--local-user", "--log-file", "--logger-fd", "--min-rsa-length",
                       "--options", "--output", "--p12-charset", "--passphrase",
                       "--passphrase-fd", "--pinentry-mode", "--policy-file", "--protect-tool-program",
                       "--recipient", "--request-origin", "--status-fd", "--ttyname",
                       "--ttytype", "--validation-model", "--xauthority", "-o",
                       "-p", "-r", "-u"}
        ada_modus = False
        k_gs = 0
        while k_gs < len(mentah):
            t_gs = mentah[k_gs]
            if t_gs == "--":
                break
            if t_gs in ("--decrypt", "--verify"):
                ada_modus = True
                break
            if t_gs in bernilai_gs:
                k_gs += 2
                continue
            k_gs += 1
        if not ada_modus:
            return False
        ops_gs = _operand_sesudah_opsi(
            toks, awal, {
                       "--agent-program", "--attribute-fd", "--audit-log", "--chuid",
                       "--cipher-algo", "--command-fd", "--compatibility-flags", "--compliance",
                       "--compress-algo", "--debug-level", "--debug-wait", "--default-key", "--debug", "--attribute", "--digest-algo",
                       "--dirmngr-program", "--disable-cipher-algo", "--disable-pubkey-algo", "--display",
                       "--encrypt-to", "--extra-digest-algo", "--faked-system-time", "--homedir",
                       "--html-audit-log", "--ignore-cert-extension", "--ignore-cert-with-oid", "--include-certs",
                       "--input-size-hint", "--kbx-buffer-size", "--keyboxd-program", "--keyring",
                       "--keyserver", "--lc-ctype", "--lc-messages", "--ldapserver",
                       "--local-user", "--log-file", "--logger-fd", "--min-rsa-length",
                       "--options", "--output", "--p12-charset", "--passphrase",
                       "--passphrase-fd", "--pinentry-mode", "--policy-file", "--protect-tool-program",
                       "--recipient", "--request-origin", "--status-fd", "--ttyname",
                       "--ttytype", "--validation-model", "--xauthority", "-o",
                       "-p", "-r", "-u"})
        return not ops_gs or "-" in ops_gs
    if perintah == "curl":
        # Audit 1068: data/unggah/konfigurasi dari stdin.
        # Audit 1085: bentuk merekat (-d@-, -T-, nilai = pada --form)
        # dan bagian-berkas -F/--data-urlencode (name@-).
        # Audit 1086: --data-raw TIDAK pernah membaca stdin — '@'
        # dan '-' di nilainya data harfiah.
        i = 0
        while i < len(mentah):
            t = mentah[i]
            if t in ("-d", "--data", "--data-ascii",
                     "--data-binary", "--data-urlencode", "-T",
                     "--upload-file", "-K", "--config", "-F",
                     "--form"):
                if i + 1 < len(mentah):
                    v = mentah[i + 1]
                    if t in ("-d", "--data", "--data-ascii",
                             "--data-binary"):
                        if v == "@-" or v.startswith("@-"):
                            return True
                    elif t == "--data-urlencode":
                        if v == "@-" or v.startswith("@-") or (
                                "@" in v
                                and v.split("@", 1)[1] == "-"):
                            return True
                    elif t in ("-F", "--form"):
                        seg_f = v.split("=", 1)[-1]
                        if seg_f.startswith("<@"):
                            seg_f = seg_f[1:]
                        if seg_f == "@-" or seg_f.startswith("@-;"):
                            return True
                    elif v == "-":
                        return True
                i += 2
                continue
            if t == "--data-raw" or t.startswith("--data-raw="):
                i += 2 if t == "--data-raw" else 1
                continue
            if (t.startswith("--data") and "@-" in t) or (
                    t.startswith("--form") and "@-" in t
                    and "--form-string" not in t) or (
                    t.startswith("-T") and len(t) > 2
                    and t[2:] == "-") or (
                    t.startswith("-d") and len(t) > 2
                    and t[2:].startswith("@-")) or (
                    t.startswith("-K") and len(t) > 2
                    and t[2:] == "-") or (
                    t.startswith("-F") and len(t) > 2
                    and "@-" in t) or (
                    t.startswith("--upload-file=")
                    and t.split("=", 1)[1] == "-") or (
                    t.startswith("--config=")
                    and t.split("=", 1)[1] == "-"):
                return True
            i += 1
        return False
    if perintah == "wget":
        # Audit 1068: daftar URL dari stdin.
        for i, t in enumerate(mentah):
            if t in ("-i", "--input-file"):
                if i + 1 < len(mentah) and mentah[i + 1] == "-":
                    return True
            if t in ("--input-file=-", "-i-"):
                return True
        return False
    if perintah in ("nc", "netcat", "nc.openbsd"):
        # Audit 1068: nc dengan target/port meneruskan stdin ke
        # koneksi (atau pendengar) — tanpa operand ia tak jalan.
        # Audit 1092: mode pindai -z hanya menguji port terbuka.
        if "-z" in mentah:
            return False
        ops_nc = _operand_sesudah_opsi(
            toks, awal, {"-p", "-s", "-X", "-x", "-w", "-i", "-q",
                         "--proxy", "--proxy-auth"})
        # Audit 1096: opsi menggantung = program galat.
        if "\x00gantung" in ops_nc:
            return False
        return bool(ops_nc)
    if perintah in ("make",
                    # Audit 1252: alias GNU make.
                    "gmake"):
        # Audit 1071: makefile dari stdin lewat -f -.
        # Audit 1300: nilai /dev/stdin juga stdin; bundel
        # berakhiran f (-nf) menelan operand berikutnya
        # sebagai makefile; -f<berkas> merekat.
        for i, t in enumerate(mentah):
            if t in ("-f", "--file", "--makefile"):
                if i + 1 < len(mentah) and mentah[i + 1] in (
                        "-", "/dev/stdin", "/dev/fd/0",
                        "/proc/self/fd/0"):
                    return True
            if t in ("-f-", "--file=-", "--makefile=-",
                     "-f/dev/stdin",
                     # Audit 1388: ejaan fd-0 merekat pendek,
                     # sejajar bentuk panjang Audit 1365.
                     "-f/dev/fd/0", "-f/proc/self/fd/0",
                     # Audit 1350: bentuk panjang merekat.
                     "--file=/dev/stdin",
                     "--makefile=/dev/stdin",
                     # Audit 1365: ejaan fd-0 lain untuk bentuk
                     # panjang merekat (nilai terpisah di cabang
                     # yang sama sudah menerimanya).
                     "--file=/dev/fd/0", "--file=/proc/self/fd/0",
                     "--makefile=/dev/fd/0",
                     "--makefile=/proc/self/fd/0"):
                return True
            if re.fullmatch(r"-[a-z]*f", t):
                # Audit 1389: himpunan nilai bundel sejajar
                # cabang terpisah — keluarga fd-0 lengkap.
                if i + 1 < len(mentah) and mentah[i + 1] in (
                        "-", "/dev/stdin", "/dev/fd/0",
                        "/proc/self/fd/0"):
                    return True
        return False
    if perintah == "visudo":
        # Audit 1071: berkas sudoers dari stdin lewat -f -.
        for i, t in enumerate(mentah):
            if t == "-f":
                if i + 1 < len(mentah) and mentah[i + 1] == "-":
                    return True
            if re.fullmatch(r"-[a-z]*f", t):
                if i + 1 < len(mentah) and mentah[i + 1] == "-":
                    return True
        return False
    if perintah == "patch":
        # Audit 1071: patch membaca patch dari stdin; operand
        # kedua (berkas patch) yang menekannya.
        # Audit 1093: -i/--input memasok berkas patch itu sendiri
        # (kecuali nilainya '-' = stdin).
        for k_p, t_p in enumerate(mentah):
            if t_p in ("-i", "--input"):
                return k_p + 1 < len(mentah) \
                    and mentah[k_p + 1] == "-"
            if t_p.startswith("--input="):
                return t_p.split("=", 1)[1] == "-"
            if re.fullmatch(r"-[a-z]*i", t_p):
                return k_p + 1 < len(mentah) \
                    and mentah[k_p + 1] == "-"
            if re.fullmatch(r"-i.+", t_p):
                # Audit 1131: nilai -i merekat — "-" = patch dari
                # stdin (hanya -i sebagai huruf pertama yang pasti;
                # huruf sebelumnya bisa opsi bernilai lain).
                return t_p[2:] == "-"
            if re.fullmatch(r"-[a-z]*i.+", t_p):
                return False
        ops = _operand_sesudah_opsi(
            toks, awal,
            {"-i", "--input", "-d", "--directory", "-o", "--output",
             "-r", "--reject-file", "-p", "--strip", "-F", "--fuzz",
             "-B", "--prefix", "-Y", "-Z", "--quoting-style",
             "--suffix", "--basename-prefix"})
        # Audit 1096: opsi menggantung = program galat.
        if "\x00gantung" in ops:
            return False
        return len(ops) < 2
    if perintah == "more":
        # Audit 1089: more util-linux menyangga stdin pipa sampai
        # EOF sebelum membuka berkas operandnya — selalu menguras
        # di konteks pipa (less tidak; lihat cabang di bawah).
        return True
    if perintah == "less":
        # Audit 1072: pager pada stdin pipa menyangga/menyaring
        # seluruh aliran bila tanpa operand berkas.
        # Audit 1109: -p/--pattern bernilai (pola lompatan).
        ops = _operand_sesudah_opsi(
            toks, awal, {"-n", "--lines", "-P", "--prompt", "-o",
                         "--log-file", "-O", "--logfile", "-#",
                         "-p", "--pattern"})
        if not ops:
            return True
        # Audit 1080: operand +N (posisi awal) bukan berkas.
        if re.fullmatch(r"\+\d+", ops[0]):
            # Audit 1764: +N hanya posisi awal - operand
            # berkas sesudahnya berarti berkas itu yang
            # dibuka, bukan stdin (terprobe biner nyata).
            return len(ops) == 1 or "-" in ops[1:]
        return ops[0] == "-"
    if perintah in ("vi", "vim", "nvim", "view", "ex",
                    # Audit 1316: alias vim/editor sistem.
                    "vim.tiny", "rview", "editor",
                    "sensible-editor"):
        # Audit 1072: operand '-' membaca stdin ke buffer.
        # Audit 1108: di konteks pipa vim menyangga stdin sampai
        # habis juga saat membuka berkas (dan tanpa operand) —
        # keluarga ini selalu menguras di konteks gerbang.
        return True
    if perintah == "basenc":
        # Audit 1101: tanpa bendera mode basenc galat dan tak
        # membaca; bermode ia filter stdin biasa.
        # Audit 1137: operand berkas menekan seperti filter lain;
        # operand "-" justru stdin itu sendiri.
        if any(t == "-" for t in mentah):
            return True
        if _operand_sesudah_opsi(toks, awal, {"-w", "--wrap"}):
            return False
        return any(t in ("--base64", "--base64url", "--base32",
                         "--base32hex", "--base16", "--base2msbf",
                         "--base2lsbf", "--z85")
                   or t.startswith("--base") for t in mentah)
    if perintah == "du":
        # Audit 1111: --files0-from membaca daftar nama dari stdin.
        for k_d, t_d in enumerate(mentah):
            if t_d == "--files0-from=-":
                return True
            if t_d == "--files0-from" and k_d + 1 < len(mentah) \
                    and mentah[k_d + 1] == "-":
                return True
        return False
    if perintah == "find":
        # Audit 1111: -files0-from membaca daftar jalur awal dari
        # stdin ('-' atau '=-').
        for k_f2, t_f2 in enumerate(mentah):
            if t_f2 == "-files0-from=-":
                return True
            if t_f2 == "-files0-from" and k_f2 + 1 < len(mentah) \
                    and mentah[k_f2 + 1] == "-":
                return True
        return False
    if perintah == "addr2line":
        # Audit 1112: tanpa -e tak ada berkas objek (a.out tak
        # diasumsikan ada) dan ia menolak jalan; ber--e ia membaca
        # alamat dari stdin kecuali alamat adalah operand.
        ops = _operand_sesudah_opsi(
            toks, awal, {"-e", "--exe", "-j", "--section",
                         # Audit 1132: --target nama panjang -b.
                         "-b", "--base", "--target"})
        if not any(t in ("-e", "--exe") or t.startswith("--exe=")
                   or t.startswith("-e") and len(t) > 2
                   for t in mentah):
            return False
        return not ops
    if perintah == "dig":
        # Audit 1112: berkas batch -f '-' dibaca dari stdin; kueri
        # biasa tak menyentuh stdin.
        for k_g2, t_g2 in enumerate(mentah):
            if t_g2 in ("-f", "--file") and k_g2 + 1 < len(mentah) \
                    and mentah[k_g2 + 1] == "-":
                return True
            if t_g2 in ("-f-", "--file=-"):
                return True
        return False
    # Audit 1193: pdftotext — operand masukan '-' = stdin.
    if perintah == "pdftotext":
        ops_pd = _operand_sesudah_opsi(
            toks, awal, {"-f", "-l", "-r", "-x", "-y", "-W", "-H",
                         "-opw", "-upw", "-enc", "-eol",
                         # Audit 1232: -cfg bernilai, hilang.
                         "-cfg"})
        return bool(ops_pd) and ops_pd[0] == "-"
    # Audit 1404: pdftoppm/pdftocairo — operand masukan
    # PERTAMA '-' adalah PDF dari stdin (poppler menampung
    # seluruh masukannya); operand berkas menekan.
    if perintah in ("pdftoppm", "pdftocairo"):
        # Audit 1420: himpunan dilengkapi per perintah —
        # -scale-to sekeluarga untuk keduanya; keluarga
        # kertas/ICC/JPEG hanya milik pdftocairo.
        if perintah == "pdftoppm":
            bernilai_pp = {"-f", "-l", "-r", "-rx", "-ry",
                           "-x", "-y", "-W", "-H", "-sz",
                           "-opw", "-upw", "-scale-to",
                           "-scale-to-x", "-scale-to-y",
                           # Audit 1448: opsi bernilai
                           # resmi yang hilang dari
                           # Audit 1420 (anti-alias,
                           # profil, kompresi TIFF).
                           "-aa", "-aaVector", "-sep",
                           "-freetype", "-thinlinemode",
                           "-displayprofile",
                           "-defaultgrayprofile",
                           "-defaultrgbprofile",
                           "-defaultcmykprofile",
                           "-tiffcompression", "-jpegopt"}
        else:
            bernilai_pp = {"-f", "-l", "-r", "-rx", "-ry",
                           "-x", "-y", "-W", "-H", "-sz",
                           "-opw", "-upw", "-scale-to",
                           "-scale-to-x", "-scale-to-y",
                           "-paperw", "-paperh", "-paper",
                           "-icc", "-jpegopt",
                           # Audit 1448: keluarga yang
                           # sama untuk pdftocairo.
                           "-aa", "-aaVector", "-sep",
                           "-freetype", "-thinlinemode",
                           "-displayprofile",
                           "-defaultgrayprofile",
                           "-defaultrgbprofile",
                           "-defaultcmykprofile",
                           "-tiffcompression",
                           # Audit 1498: -antialias
                           # bernilai, khusus
                           # pdftocairo (pdftoppm
                           # memakai -aa) — hilang
                           # dari Audit 1448.
                           "-antialias"}
        ops_pp = _operand_sesudah_opsi(toks, awal, bernilai_pp)
        return bool(ops_pp) and ops_pp[0] == "-"
    # Audit 1195: nft — hanya -f '-' yang membaca dari stdin.
    if perintah == "nft":
        for k_nf, t_nf in enumerate(mentah):
            if t_nf in ("--file=-",
                        # Audit 1233: -f- merekat.
                        "-f-"):
                return True
            # Audit 1279: nilai /dev/stdin juga stdin.
            if t_nf in ("--file=/dev/stdin",):
                return True
            # Audit 1280: bundel pendek berakhiran f-
            # (mis. -cf-).
            if re.fullmatch(r"-[A-Za-z]*f-", t_nf):
                return True
            if t_nf in ("-f", "--file"):
                return (k_nf + 1 < len(mentah)
                        and mentah[k_nf + 1] in (
                            "-", "/dev/stdin", "/dev/fd/0",
                            "/proc/self/fd/0"))
        return False
    # Audit 1197: mode batch iproute2 membaca daftar perintah
    # dari berkas batch; '-' = stdin.
    if perintah in ("ip", "tc", "bridge"):
        for k_bt, t_bt in enumerate(mentah):
            if t_bt == "-batch=-" or t_bt == "--batch=-":
                return True
            if t_bt in ("-batch", "-b", "--batch"):
                return (k_bt + 1 < len(mentah)
                        and mentah[k_bt + 1] == "-")
        return False
    # Audit 1198: dpkg --set-selections membaca pilihan paket.
    if perintah == "dpkg":
        return "--set-selections" in mentah
    # Audit 1200: file — operand '-' = periksa stdin; -f '-' =
    # daftar nama dari stdin.
    if perintah == "file":
        # Audit 1229: -f berkas tak mengakhiri penilaian —
        # operand '-' di tempat lain tetap stdin.
        for k_fl, t_fl in enumerate(mentah):
            if t_fl in ("-f", "--files-from"):
                # Audit 1279: nilai /dev/stdin juga stdin.
                if (k_fl + 1 < len(mentah)
                        and mentah[k_fl + 1] in (
                            "-", "/dev/stdin", "/dev/fd/0",
                            "/proc/self/fd/0")):
                    return True
            if t_fl in ("-f-", "--files-from=-"):
                return True
        ops_fl = _operand_sesudah_opsi(
            toks, awal, {"-f", "--files-from", "-F",
                         "--separator", "-m", "--magic-file",
                         "-e", "--exclude", "--exclude-quiet"})
        return "-" in ops_fl
    # Audit 1201: date -f '-' membaca daftar tanggal.
    if perintah == "date":
        for k_dt, t_dt in enumerate(mentah):
            if t_dt in ("--file=-",
                        # Audit 1230: -f- merekat.
                        "-f-"):
                return True
            # Audit 1279: nilai /dev/stdin juga stdin.
            if t_dt in ("--file=/dev/stdin",):
                return True
            # Audit 1280: bundel berakhiran f- (mis. -Rf-).
            if re.fullmatch(r"-[A-Za-z]*f-", t_dt):
                return True
            if t_dt in ("-f", "--file"):
                return (k_dt + 1 < len(mentah)
                        and mentah[k_dt + 1] in (
                            "-", "/dev/stdin", "/dev/fd/0",
                            "/proc/self/fd/0"))
        return False
    # Audit 1202: sysctl -p '-' membaca konfigurasi dari stdin.
    if perintah == "sysctl":
        for k_sy, t_sy in enumerate(mentah):
            # Audit 1281: -f/--file adalah alias -p pada
            # sysctl modern.
            if t_sy in ("-p-", "--preload-file=-", "-f-",
                        "--file=-"):
                return True
            if t_sy in ("-p", "--preload", "--preload-file",
                        "-f", "--file"):
                # Audit 1231: -p lain sesudahnya tetap
                # dinilai — jangan berhenti di yang pertama.
                # Audit 1279: nilai /dev/stdin juga stdin.
                if (k_sy + 1 < len(mentah)
                        and mentah[k_sy + 1] in (
                            "-", "/dev/stdin", "/dev/fd/0",
                            "/proc/self/fd/0")):
                    return True
        return False
    # Audit 1734: sftp membaca perintah batch dari stdin bila
    # -b bernilai "-" (man sftp(1): batchfile "-" = standard
    # input); tanpa -b, mode interaktif membaca perintah dari
    # stdin yang ter-redirect; -b berkas membacanya dari berkas.
    if perintah == "sftp":
        # Audit 1791: nilai -o berupa '-' adalah kata kunci
        # konfigurasi ssh yang tak sah - sftp gugur fatal
        # sebelum koneksi, mengalahkan jalur -b mana pun
        # (terprobe OpenSSH 9.6p1).
        for k_sf, t_sf in enumerate(mentah):
            if t_sf == "-o" and k_sf + 1 < len(mentah) \
                    and mentah[k_sf + 1] == "-":
                return False
            if t_sf.startswith("-o") and len(t_sf) > 2 \
                    and t_sf[2:] == "-":
                return False
        b_sf = 0
        nilai_b_sf = None
        for k_sf, t_sf in enumerate(mentah):
            if t_sf == "-b":
                if k_sf + 1 >= len(mentah):
                    return False
                b_sf += 1
                nilai_b_sf = mentah[k_sf + 1]
            elif t_sf.startswith("-b") and len(t_sf) > 2:
                b_sf += 1
                nilai_b_sf = t_sf[2:]
        # Audit 1792: -b kedua ditolak OpenSSH ('Batch file
        # already specified.') pada tahap parsing.
        if b_sf > 1:
            return False
        if b_sf == 1:
            return nilai_b_sf == "-"
        # Audit 1790: tanpa -b, operand '-' adalah nama
        # host yang tak sah - sftp keluar saat resolusi
        # host (sftp(1) hanya mendokumentasikan '-' untuk
        # nilai -b), jadi perintah tak pernah dibaca.
        if "-" in _operand_sesudah_opsi(
                toks, awal, {"-b", "-o", "-P", "-F", "-J",
                             "-B", "-D", "-S"}):
            return False
        return True
    # Audit 1735: pwsh/powershell (dokumentasi resmi
    # about_Pwsh) — stdin yang ter-redirect dieksekusi sebagai
    # perintah bila -File/-Command bernilai "-"; tanpa skrip
    # dan tanpa perintah, mode interaktif membaca perintah
    # dari stdin; -File berkas, -Command berisi string, dan
    # operand naskah menekan pembacaan itu.
    if perintah in ("pwsh", "powershell"):
        # Audit 1740: -NoExit membuat host tetap interaktif sesudah
        # -File/-Command selesai (dokumentasi about_Pwsh) - stdin
        # lalu dibaca sebagai perintah REPL, jadi pindaian tidak
        # berhenti di aksi pertama: nilai "-" tetap langsung True,
        # nilai berkas/string dicatat sebagai aksi dan pindaian
        # berlanjut mencari -NoExit (token nilai itu sendiri
        # dilompati, bukan dinilai sebagai saklar).
        i_pw = 0
        aksi_pw = False
        if perintah == "pwsh":
            # Audit 1759: -Version mengabaikan parameter lain
            # (about_Pwsh), jadi deteksinya mendahului putaran:
            # token penentu yang lebih awal (-NoExit, -File -)
            # tidak boleh menembak lebih dulu. Nilai aksi
            # dilompati agar "-Version" sebagai nilai string
            # tidak ikut terdeteksi.
            i_ps = 0
            while i_ps < len(mentah):
                t_ps = mentah[i_ps].lower()
                if t_ps in ("-version", "-v"):
                    return False
                if t_ps in ("-file", "-command", "-c",
                            "-encodedcommand"):
                    i_ps += 2
                    continue
                i_ps += 1
        while i_pw < len(mentah):
            t_pw = mentah[i_pw].lower()
            if perintah == "pwsh" and t_pw in ("-version", "-v"):
                # Audit 1752: -Version pada pwsh informasional -
                # cetak versi lalu keluar dan argumen lain
                # diabaikan (about_Pwsh); bukan mode interaktif.
                return False
            if perintah == "powershell" and t_pw == "-v":
                # Audit 1785: '-v' bukan alias -Version pada
                # powershell.exe (alias itu hanya milik pwsh
                # menurut about_PowerShell_exe) - parameter
                # tak dikenal atau nilai wajibnya hilang;
                # host keluar tanpa sesi interaktif.
                return False
            if t_pw == "-noexit":
                return True
            if t_pw in ("-file", "-command", "-c",
                        "-encodedcommand"):
                # Audit 1744: -File dan -Command tidak bisa
                # digabung dalam satu perintah (dokumentasi
                # about_Pwsh) - aksi kedua membuat host menolak
                # dan keluar, jadi vonisnya tidak menguras walau
                # aksi kedua itu bernilai "-".
                if aksi_pw:
                    return False
                if i_pw + 1 >= len(mentah):
                    return False
                if (mentah[i_pw + 1] == "-"
                        and t_pw != "-encodedcommand"):
                    return True
                aksi_pw = True
                i_pw += 2
                continue
            if (t_pw in ("-configurationname",
                        "-configurationfile", "-custompipename",
                        "-executionpolicy", "-inputformat",
                        "-outputformat", "-settingsfile",
                        "-windowstyle", "-workingdirectory")
                    or (perintah == "powershell"
                        and (t_pw == "-version"
                             or (len(t_pw) >= 3
                                 and "-version".startswith(
                                     t_pw))))):
                # Audit 1745: opsi bernilai lain - nilainya
                # bukan operand naskah; lompati beserta nilainya
                # agar bentuk tanpa -File/-Command tetap divonis
                # interaktif (membaca perintah dari stdin).
                if i_pw + 1 >= len(mentah):
                    return False
                # Audit 1760: nilai -Version pada powershell
                # harus versi yang terpasang - token bersaklar
                # bukan versi sah dan host keluar dengan galat.
                if (perintah == "powershell"
                        and (t_pw == "-version"
                             or (len(t_pw) >= 3
                                 and "-version".startswith(t_pw)))
                        and mentah[i_pw + 1].startswith("-")):
                    return False
                i_pw += 2
                continue
            if (perintah == "pwsh" and len(t_pw) >= 3
                    and "-version".startswith(t_pw)):
                # Audit 1762: awalan unik -Version (mis. -ver);
                # Audit 1773: ambang diturunkan ke 3 aksara -
                # -ve juga awalan unik ("Ve" hanya -Version).
                # terikat oleh pengikat parameter sebagai
                # -Version itu sendiri (informatif), bukan
                # sesi interaktif.
                return False
            if t_pw.startswith("-version"):
                # Audit 1761: perpanjangan nama -Version
                # (mis. -Versionx) bukan parameter dan bukan
                # singkatan sah - parser menggalalkan atau
                # berkasnya tak ada; host keluar tanpa sesi.
                return False
            if t_pw.startswith("-"):
                i_pw += 1
                continue
            return False
        return not aksi_pw
    # Audit 1748: bc membaca stdin sesudah semua berkas
    # operand dibaca (POSIX bc; manual GNU bc) - tak ada
    # bentuk operand yang menekan pembacaan itu kecuali
    # berkas yang memuat perintah halt, yang tidak bisa
    # diketahui gerbang.
    if perintah == "bc":
        return True
    # Audit 1749: dc membaca perintah dari stdin bila tanpa
    # berkas dan tanpa -e (manual GNU dc); berkas operand
    # atau -e/-f menggantikan stdin, dan "-" adalah nama
    # berkas aliran stdin.
    if perintah == "dc":
        i_dc = 0
        aksi_dc = False
        berkas_dc = False
        while i_dc < len(mentah):
            t_dc = mentah[i_dc].lower()
            if t_dc in ("-e", "--expression", "-f", "--file"):
                if i_dc + 1 >= len(mentah):
                    return False
                # Audit 1751: nilai "-" untuk -f/--file adalah
                # berkas aliran stdin (manual GNU dc; man dc(1):
                # dc hanya keluar sesudah -f bila "-" tidak
                # pernah diberikan ke -f/--file).
                if (t_dc in ("-f", "--file")
                        and mentah[i_dc + 1] == "-"):
                    return True
                aksi_dc = True
                i_dc += 2
                continue
            if (t_dc.startswith("--expression=")
                    or t_dc.startswith("--file=")):
                # Audit 1751: bentuk "--file=-" sama - stdin.
                if t_dc == "--file=-":
                    return True
                aksi_dc = True
                i_dc += 1
                continue
            if (t_dc.startswith("-e") and len(t_dc) > 2) or \
                    (t_dc.startswith("-f") and len(t_dc) > 2):
                # Audit 1794: nilai menempel getopt untuk
                # -e/-f (mis. -e2p, -fberkas) adalah aksi
                # juga; nilai menempel '-' untuk -f tetap
                # berkas stdin (lini Audit 1751).
                if t_dc.startswith("-f") and t_dc[2:] == "-":
                    return True
                aksi_dc = True
                i_dc += 1
                continue
            if mentah[i_dc] == "-":
                return True
            if t_dc.startswith("-"):
                i_dc += 1
                continue
            # Audit 1793: operand polos adalah berkas yang
            # dibaca berurutan; '-' di posisi operand
            # berikutnya tetap berarti stdin (manual GNU
            # dc: parameter tersisa adalah berkas-berkas
            # dan '-' adalah aliran standar), jadi
            # pindaian berlanjut, bukan berhenti.
            berkas_dc = True
            i_dc += 1
            continue
        return not aksi_dc and not berkas_dc
    # Audit 1750: perintah ed selalu dibaca dari stdin
    # (manual GNU ed) - operand berkas hanya mengisi buffer
    # suntingan, sumber perintahnya tetap stdin.
    if perintah == "ed":
        return True
    # Audit 1757: hexdump menampilkan berkas yang disebut,
    # atau standard input bila tak ada berkas (hexdump(1));
    # hd adalah nama panggil yang sama. Opsi bernilai -n/-s
    # (angka) dan -e/-f (string/berkas format) dilompati
    # beserta nilainya.
    if perintah in ("hexdump", "hd"):
        i_hx = 0
        while i_hx < len(mentah):
            t_hx = mentah[i_hx].lower()
            if t_hx in ("-n", "-s", "-e", "-f"):
                if i_hx + 1 >= len(mentah):
                    return False
                # Audit 1775: nilai -f adalah nama berkas
                # format harfiah - '-' di sana berkas bernama
                # '-' (galat bila tak ada), bukan stdin.
                if t_hx == "-f" and mentah[i_hx + 1] == "-":
                    return False
                i_hx += 2
                continue
            if mentah[i_hx] == "-":
                return True
            if t_hx.startswith("-"):
                i_hx += 1
                continue
            return False
        return True
    # Audit 1758: xxd membaca standard input bila tak ada
    # infile, dan infile "-" juga berarti stdin (xxd(1)).
    # Opsi bernilai dilompati beserta nilainya.
    if perintah == "xxd":
        i_xx = 0
        while i_xx < len(mentah):
            t_xx = mentah[i_xx].lower()
            if t_xx in ("-l", "-len", "-s", "-seek", "-c",
                        "-cols", "-g", "-groupsize", "-o",
                        "-offset"):
                if i_xx + 1 >= len(mentah):
                    return False
                i_xx += 2
                continue
            if mentah[i_xx] == "-":
                return True
            if t_xx.startswith("-"):
                i_xx += 1
                continue
            return False
        return True
    # Audit 1765: cpio membaca dari stdin pada modusnya
    # (GNU cpio): copy-in arsipnya dari stdin (kecuali -F
    # berkas), copy-out dan copy-pass daftar namanya dari
    # stdin. Operand posisi pada copy-in adalah pola nama,
    # bukan sumber arsip.
    if perintah == "cpio":
        modus_cp = ""
        berkas_cp = None
        i_cp = 0
        while i_cp < len(mentah):
            t_cp = mentah[i_cp]
            if t_cp in ("-F", "--file"):
                if i_cp + 1 >= len(mentah):
                    return False
                berkas_cp = mentah[i_cp + 1]
                i_cp += 2
                continue
            if t_cp.startswith("--file="):
                berkas_cp = t_cp.split("=", 1)[1]
                i_cp += 1
                continue
            if (t_cp.startswith("-F") and len(t_cp) > 2
                    and not t_cp.startswith("--")):
                # Audit 1786: nilai -F menempel (getopt:
                # argumen wajib boleh menempel) - berkas
                # yang disebut; huruf di dalam nilainya
                # bukan huruf modus.
                berkas_cp = t_cp[2:]
                i_cp += 1
                continue
            if t_cp in ("--create", "--pass"):
                modus_cp = "tulis"
            elif t_cp in ("--extract", "--list"):
                modus_cp = "baca"
            elif t_cp.startswith("-") and t_cp != "-":
                # Audit 1797: -F di tengah cluster juga
                # berargumen wajib (getopt) - huruf
                # sesudahnya nilai berkas, bukan huruf
                # modus; kepala cluster tetap dipindai.
                kepala_cp = t_cp
                if not t_cp.startswith("--") and "F" in t_cp:
                    pos_cp = t_cp.index("F")
                    berkas_cp = t_cp[pos_cp + 1:]
                    kepala_cp = t_cp[:pos_cp]
                if "o" in kepala_cp or "p" in kepala_cp:
                    modus_cp = "tulis"
                elif "i" in kepala_cp or "t" in kepala_cp:
                    modus_cp = "baca"
            i_cp += 1
        if modus_cp == "tulis":
            return True
        if modus_cp == "baca":
            # Audit 1780: nilai -F berupa '-' adalah nama
            # berkas harfiah - manual GNU cpio tidak
            # mendokumentasikan '-' sebagai stdin di sini
            # (beda dari tar/gzip yang eksplisit).
            return berkas_cp is None
        return False
    # Audit 1766: pax (POSIX) - mode list dan read membaca
    # arsip dari standard input kecuali -f menyebut berkas;
    # mode write membaca daftar pathname dari stdin bila
    # tanpa operand berkas.
    if perintah == "pax":
        tulis_px = False
        arsip_px = None
        ada_operand_px = False
        i_px = 0
        while i_px < len(mentah):
            t_px = mentah[i_px]
            if (t_px.startswith("-")
                    and not t_px.startswith("--")
                    and "f" in t_px):
                if "w" in t_px:
                    tulis_px = True
                sisa_px = t_px[t_px.index("f") + 1:]
                if sisa_px:
                    arsip_px = sisa_px
                    i_px += 1
                    continue
                if i_px + 1 >= len(mentah):
                    return False
                arsip_px = mentah[i_px + 1]
                i_px += 2
                continue
            if t_px.startswith("-"):
                if "w" in t_px:
                    tulis_px = True
                i_px += 1
                continue
            ada_operand_px = True
            i_px += 1
        if tulis_px:
            return not ada_operand_px
        return arsip_px is None or arsip_px == "-"
    # Audit 1767: pg (util-linux) membaca standard input
    # bila tak ada nama berkas (pg(1)); opsi -p bernilai
    # string prompt.
    if perintah == "pg":
        i_pg = 0
        while i_pg < len(mentah):
            t_pg = mentah[i_pg].lower()
            if t_pg == "-p":
                if i_pg + 1 >= len(mentah):
                    return False
                i_pg += 2
                continue
            if mentah[i_pg] == "-":
                return True
            if t_pg.startswith("-"):
                i_pg += 1
                continue
            return False
        return True
    # Audit 1768: bat/batcat membaca standard input bila
    # tak ada FILE atau FILE adalah '-' (bat(1)). Opsi
    # bernilai lazim dilompati beserta nilainya.
    if perintah in ("bat", "batcat"):
        i_bt = 0
        while i_bt < len(mentah):
            t_bt = mentah[i_bt].lower()
            if t_bt in ("-l", "--language", "--theme",
                        "--style", "--file-name", "--line-range",
                        "--map-syntax", "--wrap",
                        "--terminal-width", "--highlight-line",
                        "--diff-context"):
                if i_bt + 1 >= len(mentah):
                    return False
                i_bt += 2
                continue
            if mentah[i_bt] == "-":
                return True
            if t_bt.startswith("-"):
                i_bt += 1
                continue
            return False
        return True
    # Audit 1769: lzip tanpa nama berkas memampatkan /
    # membuka dari standard input, dan argumen '-' juga
    # stdin (manual GNU lzip) - keluarga gzip.
    if perintah == "lzip":
        i_lz = 0
        while i_lz < len(mentah):
            t_lz = mentah[i_lz].lower()
            if t_lz in ("-o", "--output", "-S", "--suffix",
                        "-s", "--dictionary-size", "-b",
                        "--member-size", "-m", "--match-length",
                        "-n", "--threads"):
                if i_lz + 1 >= len(mentah):
                    return False
                i_lz += 2
                continue
            if mentah[i_lz] == "-":
                return True
            if t_lz.startswith("-"):
                i_lz += 1
                continue
            return False
        return True
    # Audit 1770: lz4 membaca stdin bila tak ada berkas
    # masukan atau berkasnya '-' (lz4(1)); unlz4/lz4cat
    # bentuk kucingnya. Opsi bernilai lazim dilompati.
    if perintah in ("lz4", "unlz4", "lz4cat"):
        i_l4 = 0
        while i_l4 < len(mentah):
            t_l4 = mentah[i_l4].lower()
            if t_l4 in ("-D", "--dict", "-B", "--block-size",
                        "-T", "--threads", "--input-size"):
                if i_l4 + 1 >= len(mentah):
                    return False
                i_l4 += 2
                continue
            if mentah[i_l4] == "-":
                return True
            if t_l4.startswith("-"):
                i_l4 += 1
                continue
            return False
        return True
    # Audit 1803: uuencode [INPUT] NAME - satu-satunya
    # operand adalah NAME keluaran dan masukan dari
    # stdin; dua operand membaca berkas INPUT; tanpa
    # operand galat (manual GNU sharutils).
    if perintah in ("uuencode", "uudecode"):
        ops_uu = _operand_sesudah_opsi(toks, awal, {"-o"})
        if "\x00gantung" in ops_uu:
            return False
        if perintah == "uuencode":
            return len(ops_uu) == 1
        # Audit 1804: uudecode [FILE]... - tanpa FILE
        # membaca stdin (manual GNU sharutils).
        return not ops_uu or "-" in ops_uu
    # Audit 1814-1817: keluarga moreutils yang selalu
    # membaca stdin - operandnya bukan berkas masukan
    # (sponge: berkas keluaran; ts: pola format; vipe:
    # editor pipa; pee: perintah tujuan).
    if perintah in ("sponge", "ts", "vipe", "pee",):
        return True
    # Audit 1818: recode REQUEST [FILE]... - operand
    # pertama adalah request pengkodean, bukan berkas;
    # tanpa FILE ia filter stdin (manual GNU recode).
    if perintah == "recode":
        ops_rc = _operand_sesudah_opsi(toks, awal, set())
        if "\x00gantung" in ops_rc:
            return False
        return len(ops_rc) == 1
    # Audit 1771: rpm2cpio tanpa argumen membaca aliran
    # RPM dari standard input (rpm2cpio(1)).
    if perintah == "rpm2cpio":
        ops_rp = _operand_sesudah_opsi(toks, awal, set())
        return not ops_rp or "-" in ops_rp
    # Audit 1772: satu-satunya mekanisme stdin resmi 7z
    # adalah saklar -si pada perintah a (7z(1): -si membaca
    # data dari StdIn, termasuk bentuk menempel -sinama);
    # bentuk x -/l - tak didukung dokumentasi.
    if perintah in ("7z", "7za", "7zr", "7zz"):
        # Audit 1783: -si juga berlaku untuk perintah u
        # (update) menurut referensi saklar resmi 7-Zip.
        if mentah and mentah[0].lower() in ("a", "u"):
            # Audit 1781: nama arsip wajib - tanpa operand
            # polos perintah gugur sebelum membaca stdin
            # (sejajar zip Audit 1094).
            # Audit 1782: '--' menghentikan parsing saklar -
            # '-si' sesudahnya nama berkas, bukan saklar.
            si_7z = False
            arsip_7z = False
            dd_7z = False
            for t_7z in mentah[1:]:
                if t_7z == "--" and not dd_7z:
                    dd_7z = True
                    continue
                if dd_7z:
                    # Audit 1798: sesudah '--' token adalah
                    # nama berkas biasa - nama arsip tetap
                    # wajib ditemukan di sana.
                    arsip_7z = True
                elif t_7z.lower().startswith("-si"):
                    si_7z = True
                elif (not t_7z.startswith("-")
                        and not t_7z.startswith("@")):
                    # Audit 1787: token @listfile memasok
                    # daftar isi, bukan nama arsip.
                    arsip_7z = True
            return si_7z and arsip_7z
        return False
    # Audit 1204: bun repl mengevaluasi masukan pipa; bentuk
    # lain (skrip/bantuan) tak membaca stdin.
    if perintah == "bun":
        # Audit 1788: opsi global bernilai bun dilompati
        # beserta nilainya (--cwd/--config/--env-file/dll),
        # bukan hanya -e/--eval - nilai terpisahnya dulu
        # jatuh sebagai operand dan menyembunyikan '-'.
        ops_bn = _operand_sesudah_opsi(
            toks, awal, {"-e", "--eval", "--cwd", "--config",
                         "--env-file", "--port", "--host",
                         "--hostname"})
        # Audit 1789: sesudah -e/--eval, token '-' adalah
        # argumen posisi skrip eval (argv), bukan skrip dari
        # stdin (terprobe fungsional bun 1.4.4).
        if ops_bn and ops_bn[0] == "-" and any(
                t in ("-e", "--eval")
                or t.startswith("--eval=")
                or (t.startswith("-e") and len(t) > 2)
                for t in mentah):
            return False
        if not ops_bn:
            return False
        # Audit 1732: `bun -` dan `bun run -` menjalankan
        # skrip dari stdin, sama seperti repl.
        if ops_bn[0] in ("repl", "-"):
            return True
        return (ops_bn[0] == "run" and len(ops_bn) > 1
                and ops_bn[1] == "-")
    # Audit 1302/1303/1304: kompiler & assembler — '-' atau
    # tanpa berkas sumber (untuk -E) berarti stdin.
    if perintah == "cpp":
        ops_cp = _operand_sesudah_opsi(
            toks, awal, {"-I", "-D", "-U", "-o", "-MF", "-MT",
                         "-MQ", "-include", "-imacros"})
        return (not ops_cp or "-" in ops_cp
                or "/dev/stdin" in ops_cp)
    if perintah in ("gcc", "g++", "cc", "c++"):
        ops_gc = _operand_sesudah_opsi(
            toks, awal, {"-x", "-o", "-I", "-D", "-U", "-L",
                         "-include", "-imacros", "-MF", "-MT",
                         "-MQ", "-B", "-specs", "-specs="})
        return ("-" in ops_gc or "/dev/stdin" in ops_gc
                or ("-E" in mentah and not ops_gc))
    if perintah == "as":
        ops_as = _operand_sesudah_opsi(
            toks, awal, {"-o", "-I", "--defsym"})
        # Audit 1352: tanpa operand berkas, as membaca
        # sumber dari stdin (seperti cpp).
        return ("-" in ops_as or "/dev/stdin" in ops_as
                or not ops_as)
    # Audit 1311/1305/1308/1309: banding beroperand '-' —
    # cmp sendiri dan keluarga terkompresinya (bzcmp/bzdiff
    # SENGAJA tak ikut: '-' mereka nama berkas harfiah).
    if perintah in ("cmp", "zcmp", "zdiff", "xzcmp", "xzdiff",
                    "lzcmp", "lzdiff"):
        ops_cm = _operand_sesudah_opsi(
            toks, awal, {"-i", "--ignore-initial", "-n",
                         "--bytes"})
        return "-" in ops_cm or "/dev/stdin" in ops_cm
    # Audit 1312: ptar — berkas arsip dari -f.
    if perintah == "ptar":
        # Audit 1353: kemunculan -f TERAKHIR yang menang
        # (Getopt::Long), bukan yang pertama.
        nilai_pt = None
        for k_pt, t_pt in enumerate(mentah):
            if t_pt in ("-f", "--file"):
                if k_pt + 1 < len(mentah):
                    nilai_pt = mentah[k_pt + 1]
            elif re.fullmatch(r"-[a-z]*f", t_pt):
                if k_pt + 1 < len(mentah):
                    nilai_pt = mentah[k_pt + 1]
            elif t_pt.startswith("-f") and len(t_pt) > 2:
                nilai_pt = t_pt[2:]
        return nilai_pt in ("-", "/dev/stdin")
    # Audit 1317: script — perintah anak adalah nilai -c
    # (string shell); tanpa -c ia shell interaktif.
    if perintah == "script":
        for k_sc, t_sc in enumerate(mentah):
            if t_sc in ("-c", "--command") or re.fullmatch(
                    r"-[a-zA-Z]*c", t_sc):
                if k_sc + 1 < len(mentah):
                    segmen_sc = pecah_segmen_shell(
                        mentah[k_sc + 1])
                    if segmen_sc:
                        return _menguras_stdin(segmen_sc[0])
                return False
            if t_sc.startswith("-c") and len(t_sc) > 2:
                segmen_sc = pecah_segmen_shell(t_sc[2:])
                if segmen_sc:
                    return _menguras_stdin(segmen_sc[0])
                return False
        return True
    # Audit 1320: tmux load-buffer dari stdin.
    if perintah == "tmux":
        # Audit 1354: opsi global (-S/-L/-f/-T) lazim
        # mendahului nama subperintah — cari subperintah
        # sesudah opsi-opsi itu, bukan token pertama.
        k_tm = 0
        while k_tm < len(mentah):
            t_tm = mentah[k_tm]
            if t_tm in ("-S", "-L", "-f", "-T"):
                k_tm += 2
                continue
            if t_tm.startswith("-"):
                # Audit 1386: nilai MEREKAT opsi global bernilai
                # (-LV = -L bernilai 'V') bukan bundel bendera —
                # 'V' di sana nilai opsi, bukan -V; tokennya
                # dilompati sebagai opsi bernilai.
                if re.fullmatch(r"-[LSfT].+", t_tm):
                    k_tm += 1
                    continue
                # Audit 1366: -V (termasuk di dalam bundel
                # huruf) mencetak versi lalu KELUAR — subperintah
                # tak pernah berjalan.
                # Audit 1387: bundel boleh berisi angka
                # (-2V) — syarat huruf-murni lama
                # melewatkannya dari deteksi -V.
                if (not t_tm.startswith("--")
                        and re.fullmatch(r"[A-Za-z0-9]+",
                                          t_tm[1:])
                        and "V" in t_tm[1:]):
                    return False
                k_tm += 1
                continue
            break
        if (k_tm < len(mentah)
                and mentah[k_tm] == "load-buffer"):
            ops_tm = _operand_sesudah_opsi(
                toks, awal + k_tm + 1, {"-b", "-t"})
            return "-" in ops_tm or "/dev/stdin" in ops_tm
        return False
    # Audit 1321/1322: konfigurasi systemd dari operand '-'.
    if perintah in ("systemd-sysusers", "systemd-tmpfiles"):
        ops_sy2 = _operand_sesudah_opsi(
            toks, awal, {"--root", "--image", "--prefix",
                         "--exclude-prefix", "--replace"})
        return "-" in ops_sy2
    # Audit 1323: systemd-creds encrypt — masukan pertama.
    if perintah == "systemd-creds":
        if mentah and mentah[0] == "encrypt":
            ops_sc2 = _operand_sesudah_opsi(
                toks, awal + 1, {"--name", "--timestamp",
                                 "--with-key"})
            return bool(ops_sc2) and ops_sc2[0] in (
                "-", "/dev/stdin")
        return False
    # Audit 1325: debconf-communicate selalu membaca
    # protokol dari stdin.
    if perintah == "debconf-communicate":
        return True
    # Audit 1326: dpkg-deb — arsip dari operand '-'.
    if perintah == "dpkg-deb":
        ops_dd2 = _operand_sesudah_opsi(
            toks, awal, {"--root", "--admindir", "--instdir",
                         "--path-exclude", "--path-include"})
        return "-" in ops_dd2 or "/dev/stdin" in ops_dd2
    # Audit 1327: dpkg-parsechangelog — berkas dari -l.
    if perintah == "dpkg-parsechangelog":
        # Audit 1353: kemunculan -l TERAKHIR yang menang.
        nilai_dc = None
        for k_dc, t_dc in enumerate(mentah):
            if t_dc in ("-l", "--file"):
                if k_dc + 1 < len(mentah):
                    nilai_dc = mentah[k_dc + 1]
            elif t_dc.startswith("--file="):
                nilai_dc = t_dc.split("=", 1)[1]
            elif t_dc.startswith("-l") and len(t_dc) > 2:
                nilai_dc = t_dc[2:]
        return nilai_dc in ("-", "/dev/stdin")
    # Audit 1328: cvtsudoers — tanpa berkas membaca stdin.
    if perintah == "cvtsudoers":
        ops_cv = _operand_sesudah_opsi(
            toks, awal, {"-b", "--base", "-c", "--config",
                         "-d", "--defaults", "-f", "--format",
                         "-i", "--input-format", "-o",
                         "--output", "-O", "--output-format"})
        return (not ops_cv or "-" in ops_cv
                or "/dev/stdin" in ops_cv)
    # Audit 1329: tic — hanya operand '-' yang stdin.
    if perintah == "tic":
        ops_ti = _operand_sesudah_opsi(
            toks, awal, {"-o", "-e"})
        return "-" in ops_ti
    # Audit 1330: lesskey — sumber dari operand '-'.
    if perintah == "lesskey":
        ops_lk = _operand_sesudah_opsi(
            toks, awal, {"-o", "--output"})
        return "-" in ops_lk
    # Audit 1331: xkbcomp — sumber pertama '-'//dev/stdin.
    if perintah == "xkbcomp":
        ops_xk = _operand_sesudah_opsi(
            toks, awal, {"-C", "-o", "-w", "-R"})
        return bool(ops_xk) and ops_xk[0] in (
            "-", "/dev/stdin")
    # Audit 1332: wayland-scanner — hanya jalur fd-0
    # ('-' harfiah BUKAN stdin bagi perintah ini).
    if perintah == "wayland-scanner":
        ops_ws = _operand_sesudah_opsi(toks, awal + 1, set())
        return bool(ops_ws) and ops_ws[0] in (
            "/dev/stdin", "/dev/fd/0", "/proc/self/fd/0")
    # Audit 1333: localedef — definisi dari -i.
    if perintah == "localedef":
        # Audit 1353: kemunculan -i TERAKHIR yang menang.
        nilai_ld = None
        for k_ld, t_ld in enumerate(mentah):
            if t_ld in ("-i", "--inputfile"):
                if k_ld + 1 < len(mentah):
                    nilai_ld = mentah[k_ld + 1]
            elif t_ld.startswith("--inputfile="):
                nilai_ld = t_ld.split("=", 1)[1]
            # Audit 1355: bentuk merekat -i<berkas> sah pada
            # getopt ('-i-' = stdin).
            elif t_ld.startswith("-i") and len(t_ld) > 2:
                nilai_ld = t_ld[2:]
        return nilai_ld in ("-", "/dev/stdin")
    # Audit 1334: glib-genmarshal — hanya '-' yang membaca.
    if perintah == "glib-genmarshal":
        ops_gm2 = _operand_sesudah_opsi(
            toks, awal, {"--prefix", "--output", "--include"})
        return "-" in ops_gm2
    # Audit 1335: genrb — sumber dari operand '-'.
    if perintah == "genrb":
        ops_gr = _operand_sesudah_opsi(
            toks, awal, {"-d", "--destdir", "-s",
                         "--sourcedir", "-e", "--encoding"})
        return "-" in ops_gr
    # Audit 1339: pygettext3 — sumber dari operand '-'.
    if perintah == "pygettext3":
        ops_pg = _operand_sesudah_opsi(
            toks, awal, {"-o", "--output", "-d",
                         "--default-domain", "-p",
                         "--output-dir", "-k", "--keyword"})
        return "-" in ops_pg or "/dev/stdin" in ops_pg
    # Audit 1340: nsupdate — tanpa berkas membaca stdin.
    if perintah == "nsupdate":
        ops_ns = _operand_sesudah_opsi(
            toks, awal, {"-k", "-y", "-p", "-t", "-u", "-r"})
        return not ops_ns or "-" in ops_ns
    # Audit 1341: ssh-keygen -L membaca dari -f '-'.
    if perintah == "ssh-keygen":
        if "-L" in mentah:
            for k_sk, t_sk in enumerate(mentah):
                if t_sk == "-f":
                    return (k_sk + 1 < len(mentah)
                            and mentah[k_sk + 1] in (
                                "-", "/dev/stdin"))
                if t_sk.startswith("-f") and len(t_sk) > 2:
                    return t_sk[2:] in ("-", "/dev/stdin")
        return False
    # Audit 1342: tree --fromfile membaca daftar dari stdin.
    if perintah == "tree":
        # Audit 1356: --fromfile hanya berarti sebagai token
        # opsi sungguhan — sebagai NILAI opsi bernilai (-P
        # menelan token berikutnya walau berawalan --) ia
        # pola nama, bukan mode stdin.
        # Audit 1385: --gitfile (tree 2.x) juga opsi
        # bernilai — nilainya menelan token berikutnya.
        bernilai_tr2 = ("-P", "-I", "-L", "-o", "-T", "-H",
                        "--charset", "--filelimit", "--timefmt",
                        "--sort", "--gitfile")
        k_tr2 = 0
        while k_tr2 < len(mentah):
            t_tr2 = mentah[k_tr2]
            # Audit 1367: sesudah '--' semua token adalah
            # operand direktori — '--fromfile' di sana bukan
            # mode stdin. (Audit 1368: -T/-H kini ikut bernilai.)
            if t_tr2 == "--":
                return False
            if t_tr2 == "--fromfile":
                return True
            if t_tr2 in bernilai_tr2:
                k_tr2 += 2
                continue
            if ("=" in t_tr2 and t_tr2.split("=", 1)[0]
                    in bernilai_tr2):
                k_tr2 += 1
                continue
            # Audit 1368: opsi bernilai di UJUNG bundel pendek
            # (-aP) menelan token berikutnya sebagai nilainya,
            # sama seperti token persisnya.
            # Audit 1384: huruf bernilai di TENGAH bundel
            # (-Pa, -aPx) juga menelan token berikutnya —
            # pengurai tree mengambil nilai dari token
            # sesudahnya begitu huruf bernilai apa pun
            # ditemui di dalam bundel.
            if (t_tr2.startswith("-")
                    and not t_tr2.startswith("--")
                    and len(t_tr2) > 2
                    and any(h_tr2 in "PILOTH"
                            for h_tr2 in t_tr2[1:])):
                k_tr2 += 2
                continue
            k_tr2 += 1
        return False
    # Audit 1343: gio save menulis isi stdin ke tujuan.
    if perintah == "gio":
        return bool(mentah) and mentah[0] == "save"
    # Audit 1344: debugfs membaca perintah dari stdin,
    # kecuali daftar perintah datang dari -f/-R.
    if perintah == "debugfs":
        # Audit 1357: bentuk merekat -R<perintah>/-f<berkas>
        # juga menekan stdin.
        # Audit 1369: -f/-R juga bisa bersembunyi di dalam
        # bundel (-wf = bendera -w lalu -f menelan token
        # berikutnya) — pindai huruf per token; huruf bernilai
        # lain (-b/-s/-d) menghentikan pindaian token itu
        # karena sisanya adalah nilainya.
        # Audit 1394: -z (berkas undo) juga bernilai —
        # himpunan opsi bernilai kini lengkap sesuai getopt
        # debugfs 1.47.0: {b, d, f, R, s, z}. Huruf bernilai
        # di UJUNG token menelan TOKEN BERIKUTNYA sebagai
        # nilainya (bentuk terpisah -z -f: '-f' di sana nilai
        # -z, bukan opsi sumber perintah).
        k_df = 0
        while k_df < len(mentah):
            t_df = mentah[k_df]
            k_df += 1
            if not t_df.startswith("-") or t_df == "-":
                continue
            telan_df = False
            for pos_df, huruf_df in enumerate(t_df[1:]):
                if huruf_df == "f":
                    # Audit 1414: nilai -f berupa stdin
                    # (-f -) berarti berkas perintahnya
                    # adalah stdin itu sendiri (-R tidak:
                    # nilai '-' baginya string permintaan,
                    # terprobe tidak membaca).
                    sisa_df = t_df[pos_df + 2:]
                    if not sisa_df and k_df < len(mentah):
                        sisa_df = mentah[k_df]
                    return sisa_df in (
                        "-", "/dev/stdin", "/dev/fd/0",
                        "/proc/self/fd/0")
                if huruf_df == "R":
                    return False
                if huruf_df in ("b", "s", "d", "z"):
                    telan_df = pos_df == len(t_df) - 2
                    break
            if telan_df:
                # Audit 1413: opsi bernilai menggantung di
                # akhir argv = galat getopt; debugfs keluar
                # tanpa membaca apa pun.
                if k_df >= len(mentah):
                    return False
                k_df += 1
        return True
    # Audit 1345: xauth tanpa subcommand membaca perintah
    # dari stdin.
    if perintah == "xauth":
        ops_xa = _operand_sesudah_opsi(
            toks, awal, {"-f", "--file"})
        return not ops_xa
    # Audit 1347: systemd-run — stdin anak hanya diteruskan
    # pada mode --scope. Mode layanan menjalankan unit
    # dengan stdin /dev/null; opsi timer/jalur/soket dan
    # --remain-after-exit/--service-type DITOLAK systemd
    # pada mode --scope sehingga tak ada yang berjalan.
    if perintah == "systemd-run":
        bernilai_sr = {"--unit", "--description", "--property",
                       "-p", "--setenv", "-E",
                       "--working-directory", "--machine", "-M",
                       "--host", "-H", "--timer-property",
                       "--path-property", "--socket-property",
                       "--uid", "--gid", "--nice", "--limit",
                       "--slice", "--service-type"}
        timer_sr = {"--on-calendar", "--on-active", "--on-boot",
                    "--on-startup", "--on-unit-active",
                    "--on-unit-inactive", "--timer-property",
                    "--path-property", "--socket-property",
                    # Audit 1364: dua pemicu timer lain yang
                    # sama-sama ditolak pada mode --scope.
                    "--on-clock-change", "--on-timezone-change"}
        scope_sr = False
        ditolak_sr = False
        anak_sr = None
        k_sr = 0
        while k_sr < len(mentah):
            t_sr = mentah[k_sr]
            if t_sr == "--":
                anak_sr = k_sr + 1
                break
            if t_sr.startswith("--"):
                nama_sr = t_sr.split("=", 1)[0]
                if nama_sr == "--scope":
                    scope_sr = True
                if (nama_sr in timer_sr
                        or nama_sr in ("--remain-after-exit",
                                       "--service-type")):
                    ditolak_sr = True
                if nama_sr in bernilai_sr and "=" not in t_sr:
                    k_sr += 2
                    continue
                k_sr += 1
                continue
            if t_sr.startswith("-") and len(t_sr) > 1:
                tambah_sr = 1
                for j_sr, c_sr in enumerate(t_sr[1:]):
                    if c_sr == "r":
                        ditolak_sr = True
                    if c_sr in ("p", "E", "M", "H"):
                        if j_sr == len(t_sr) - 2:
                            tambah_sr = 2
                        break
                k_sr += tambah_sr
                continue
            anak_sr = k_sr
            break
        if not scope_sr or ditolak_sr or anak_sr is None:
            return False
        if anak_sr >= len(mentah):
            return False
        segmen_sr = pecah_segmen_shell(
            " ".join(mentah[anak_sr:]))
        return bool(segmen_sr) and _menguras_stdin(segmen_sr[0])
    # Audit 1358: keluarga ffmpeg — masukan '-'/'pipe:'
    # membaca media dari stdin sampai EOF.
    if perintah in ("ffmpeg", "ffprobe", "ffplay"):
        # Audit 1393: ffprobe/ffplay menerima TEPAT satu
        # masukan — hanya -i PERTAMA yang menentukan (masukan
        # kedua ditolak sebelum membaca); ffmpeg boleh banyak
        # masukan, jadi pindaiannya tetap "ada -i stdin".
        if perintah != "ffmpeg":
            for k_ff, t_ff in enumerate(mentah):
                if t_ff == "-i" and k_ff + 1 < len(mentah):
                    return mentah[k_ff + 1] in (
                        "-", "pipe:", "pipe:0", "/dev/stdin")
        else:
            for k_ff, t_ff in enumerate(mentah):
                if t_ff == "-i" and k_ff + 1 < len(mentah):
                    if mentah[k_ff + 1] in ("-", "pipe:",
                                            "pipe:0",
                                            "/dev/stdin"):
                        return True
            return False
        ops_ff = _operand_sesudah_opsi(
            toks, awal, {"-i", "-x", "-y", "-volume", "-seek",
                         "-ss", "-t", "-of", "-print_format",
                         "-select_streams", "-v", "-loglevel",
                         "-f", "-vf", "-af", "-left", "-top"})
        # Audit 1370: ffprobe/ffplay hanya menerima TEPAT
        # satu masukan — masukan kedua adalah galat sebelum
        # membaca, jadi hanya operand pertama yang menentukan.
        return bool(ops_ff) and ops_ff[0] in (
            "-", "pipe:", "pipe:0", "/dev/stdin")
    # Audit 1377: gh — hanya 'auth login --with-token' yang
    # membaca stdin (seluruh pipa dibaca untuk token).
    if perintah == "gh":
        # Audit 1395: bendera global gh (--hostname bernilai)
        # lazim mendahului subperintah — lompati dulu sebelum
        # memeriksa 'auth login'.
        k_gh = 0
        while k_gh < len(mentah):
            if mentah[k_gh] == "--hostname":
                k_gh += 2
                continue
            if mentah[k_gh].startswith("--hostname="):
                k_gh += 1
                continue
            break
        # Audit 1424: --with-token hanya dihitung
        # SESUDAH posisi subperintah — kemunculannya
        # sebagai nilai --hostname (sudah dilompati
        # gelung di atas) bukan bendera.
        return (len(mentah) - k_gh >= 2
                and mentah[k_gh] == "auth"
                and mentah[k_gh + 1] == "login"
                and "--with-token" in mentah[k_gh:])
    # Audit 1405: npm init tanpa -y/--yes adalah wizard
    # interaktif yang membaca jawaban prompt dari stdin.
    if perintah == "npm":
        ops_npm = _operand_sesudah_opsi(
            toks, awal, {"--prefix", "--userconfig",
                         "--globalconfig", "--cache",
                         "--registry", "--workspace", "-w",
                         # Audit 1421: bendera global
                         # bernilai yang hilang — nilainya
                         # sempat menggeser operand init.
                         "--loglevel", "--otp", "--proxy",
                         "--https-proxy", "--cafile",
                         # Audit 1445: gelombang kedua
                         # bendera global bernilai yang
                         # hilang dari Audit 1421.
                         "--init-module", "--tmp", "--shell",
                         "--node-options", "--ca", "--cert",
                         "--key"})
        if not ops_npm or ops_npm[0] != "init":
            return False
        return "-y" not in mentah and "--yes" not in mentah
    # Audit 1430: certutil -A tanpa -i membaca
    # sertifikat dari stdin (dengan basis data NSS sah).
    if perintah == "certutil":
        return "-A" in mentah and "-i" not in mentah
    # Audit 1431: cmsutil -D tanpa -i membaca pesan
    # dari stdin.
    if perintah == "cmsutil":
        # Audit 1447: -i bisa menjadi NILAI -p (kata
        # sandi), bukan berkas masukan — penjaga lama
        # buta nilai. Pindai dengan melompati nilai opsi
        # bernilai cmsutil.
        if "-D" not in mentah:
            return False
        k_cu = 0
        while k_cu < len(mentah):
            t_cu = mentah[k_cu]
            if t_cu == "--":
                break
            if t_cu in ("-i", "--input"):
                # Audit 1500: "-" di cmsutil adalah
                # nama berkas harfiah yang gagal
                # dibuka, BUKAN stdin (terprobe) —
                # asumsi 1447 terbalik; stdin hanya
                # dibaca bila -i absen sama sekali.
                return False
            if t_cu.startswith("--input="):
                return False
            if t_cu in ("-p", "--password", "-o", "--output",
                        "-d", "-u"):
                k_cu += 2
                continue
            k_cu += 1
        return True
    # Audit 1435: apt-key add - menyerahkan materi
    # kunci ke gpg yang membacanya dari stdin.
    if perintah == "apt-key":
        return (len(mentah) >= 2 and mentah[0] == "add"
                and "-" in mentah[1:])
    return False


def _menguras_stdin(bagian, _batas_argv=0, _posisi_awal=True):
    """True bila perintah pembaca ini sungguh menguras stdin bersama
    (Audit 934): sumber masukannya stdin itu sendiri. Pembaca yang
    membaca berkas argumennya atau redirect masukan tak menguras
    stdin; tee selalu membaca stdin walau berargumen berkas keluaran;
    dd membaca berkas bila ada if=."""
    bagian = _potong_pipa_pertama(bagian)
    if _substitusi_masukan_menguras(bagian):
        return True
    toks = _token_shell(bagian.strip().lstrip("({ "))
    # Audit 1021: kupas kurung tutup grup yang menempel di token
    # terakhir ("(cat)" menyisakan "cat)") atau berdiri sendiri
    # ("( cat )") — keduanya sisa grup subshell, bukan operand.
    while toks and toks[-1] in (")", "}"):
        toks.pop()
    if toks and toks[-1][-1:] in (")", "}"):
        # Tepat SATU penutup: "((cat))" adalah aritmetika shell,
        # bukan grup ganda — mengupas dua penutup mengarang
        # perintah yang tak pernah berjalan.
        inti = toks[-1][:-1]
        if inti:
            toks[-1] = inti
        else:
            toks.pop()
    if not toks:
        return False
    # Audit 937: perintah sungguhan bisa tersembunyi di balik
    # penetapan variabel lingkungan dan pembungkus umum.
    awal = 0
    xargs_dari_berkas = False
    xargs_sendiri = False
    lewat_pembungkus = False
    # Audit 1033/1034: keadaan posisi perintah untuk kata kunci.
    sesudah_exec = False
    lewat_command = False
    # Audit 1050: mode shell sudo (-s/-i) tanpa perintah = shell
    # membaca perintah dari stdin.
    sudo_shell = False
    # Audit 1070: pembungkus terakhir — systemd-cat tanpa operand
    # meneruskan stdin-nya sendiri.
    pembungkus_terakhir = ""
    # Audit 987: isi string env -S di-exec env sebagai program
    # eksternal — kata pertamanya tidak pernah builtin shell,
    # jadi rekursi -S berjalan dengan _posisi_awal=False.
    masih_awal = _posisi_awal
    while awal < len(toks):
        if awal >= _batas_argv:
            # Audit 965: redirect di wilayah awalan bukan perintah —
            # resolusi melompatinya beserta targetnya, di mana pun
            # ia muncul sebelum perintah sungguhan (di depan
            # perintah, di sela opsi pembungkus/env). Redirect
            # masukan berarti stdin perintah itu sendiri dialihkan.
            sesudah = _indeks_sesudah_redirect(toks, awal)
            if sesudah < 0:
                return False
            if sesudah != awal:
                awal = sesudah
                continue
        t0 = _kata_shell(toks[awal])
        # Audit 1408: pola penetapan dinilai pada token
        # UTUH — nama dasar (Audit 1396) membuang direktori
        # dari NILAI penetapan ('X=/a/b' menjadi 'b') dan
        # penjaga ini tak pernah cocok lagi (regresi).
        if (not lewat_pembungkus and not sesudah_exec
                and re.match(r"^[A-Za-z_][A-Za-z0-9_]*=",
                             _kata_shell_utuh(toks[awal]))):
            # Audit 966: penetapan hanya transparan di awal segmen
            # atau sesudah env — sesudah pembungkus lain, token
            # berbentuk penetapan adalah nama program yang
            # dijalankan pembungkus itu, bukan penetapan shell.
            awal += 1
            continue
        if t0 in ("busybox", "toybox"):
            # Audit 1738/1739: multi-biner - perintah sesungguhnya
            # adalah applet sesudahnya, jadi putaran resolusi ini
            # dilanjutkan pada applet itu: applet yang berupa
            # pembungkus (timeout/nice/env/xargs/sudo) ikut dikupas
            # tahap pembungkus yang sama, bukan dilompati.
            # busybox telanjang atau beropsi (--list/--install)
            # hanya mencetak teks bantuan, tidak membaca stdin.
            if awal + 1 >= len(toks):
                return False
            applet = _kata_shell(toks[awal + 1])
            if not applet or applet.startswith("-"):
                return False
            # Audit 1743: sesudah multi-biner, token berbentuk
            # penetapan adalah nama applet (Audit 966), bukan
            # penetapan shell - tandai sudah lewat pembungkus.
            lewat_pembungkus = True
            awal += 1
            continue
        if t0 == "env":
            # Audit 969: env yang dijalankan pembungkus memulihkan
            # wilayah penetapan — penetapan sesudah env ini adalah
            # penetapan env sungguhan, bukan nama program.
            lewat_pembungkus = False
            # Audit 972: sesudah env, kata berikutnya adalah program
            # eksternal yang di-exec env, bukan builtin shell.
            masih_awal = False
            awal += 1
            # Audit 951: opsi env sendiri bukan perintah — lompatan
            # wajib melewatinya beserta nilai opsi yang terpisah
            # (-u NAMA, -C DIR, -S STRING), bukan berhenti di sana.
            while awal < len(toks):
                if awal >= _batas_argv:
                    # Audit 965: redirect bisa menyela daftar opsi.
                    sesudah = _indeks_sesudah_redirect(toks, awal)
                    if sesudah < 0:
                        return False
                    if sesudah != awal:
                        awal = sesudah
                        continue
                if not toks[awal].startswith("-"):
                    break
                opsi = toks[awal]
                awal += 1
                if opsi == "--":
                    break
                if (opsi in ("-S", "--split-string")
                        or opsi.startswith("--split-string=")
                        or (opsi.startswith("-S") and len(opsi) > 2)):
                    # Audit 960: nilai -S adalah baris perintah yang
                    # dijalankan env — nilai itu, digabung sisa token
                    # sesudahnya, dinilai sebagai perintah itu sendiri.
                    # Audit 963: env tak menjalankan shell, jadi token
                    # di dalam rentang string -S adalah argv harfiah;
                    # rentangnya (mengikuti kutip pembuka) diteruskan
                    # sebagai _batas_argv agar operator redirect di
                    # dalamnya dinilai operand, bukan redirect.
                    if opsi in ("-S", "--split-string"):
                        # Audit 968: redirect bisa menyela sebelum
                        # nilai -S — lompati beserta targetnya dulu.
                        awal = _sesudah_redirect_berantai(
                            toks, awal, _batas_argv)
                        if awal < 0:
                            return False
                        if awal >= len(toks):
                            return False
                        daftar = toks[awal:]
                        geser = awal
                    elif opsi.startswith("--split-string="):
                        daftar = [opsi.split("=", 1)[1]] + toks[awal:]
                        geser = awal - 1
                    else:
                        daftar = [opsi[2:]] + toks[awal:]
                        geser = awal - 1
                    rentang = 1
                    pembuka = daftar[0][:1]
                    if pembuka in ("'", '"') and not (
                            len(daftar[0]) >= 2
                            and daftar[0].endswith(pembuka)):
                        j = 1
                        while j < len(daftar):
                            rentang += 1
                            if daftar[j].endswith(pembuka):
                                break
                            j += 1
                    # Audit 964: batas argv lapisan luar diwariskan —
                    # token yang berasal dari string -S lapisan luar
                    # tetap argv harfiah di rekursi ini, jadi batas
                    # yang berlaku adalah yang terluas dari keduanya.
                    warisan = max(0, _batas_argv - geser)
                    return _menguras_stdin(
                        " ".join(daftar),
                        _batas_argv=max(rentang, warisan),
                        _posisi_awal=False)
                if opsi in ("-u", "--unset", "-C", "--chdir"):
                    # Audit 968: redirect yang menyela bukan nilai
                    # opsi — lompati dulu, baru telan nilainya.
                    awal = _sesudah_redirect_berantai(
                        toks, awal, _batas_argv)
                    if awal < 0:
                        return False
                    if awal < len(toks):
                        awal += 1
            continue
        if t0 == "command" and not masih_awal:
            # Audit 972: 'command' adalah builtin shell — transparan
            # hanya sebagai kata perintah pertama segmen. Di balik
            # pembungkus/env ia di-exec sebagai program eksternal
            # yang tak ada, jadi resolusi berhenti di namanya.
            break
        if masih_awal and t0 in ("exec", "time", "eval", "!"):
            # Audit 1020: kata kunci/builtin awalan transparan —
            # perintah sesudahnyalah yang menentukan pengurasan
            # (exec menggantikan shell dengan perintah itu; time,
            # eval, dan ! hanya membungkusnya). Hanya di posisi
            # awal: di balik pembungkus/env nama-nama ini adalah
            # program eksternal yang tak ada (pola Audit 972).
            # Audit 1034: 'command' menjalankan builtin/program,
            # BUKAN kata kunci — time/! di baliknya tak transparan
            # (exec/eval adalah builtin, tetap transparan).
            if lewat_command and t0 in ("time", "!"):
                break
            # Audit 1051: kata kunci/builtin SESUDAH exec juga
            # nama program untuk execvp — posisi sesudah-exec
            # diperlakukan sama seperti sesudah command (1034)
            # dan penetapan sesudah exec (1033): bukan transparan,
            # karena binary bernama itu tidak ada.
            if sesudah_exec and t0 in (
                    "time", "!", "eval", "exec", "command"):
                break
            awal += 1
            if t0 == "exec":
                # Audit 1031: opsi milik exec sendiri (-c/-l
                # bendera, -a bernilai, -- akhir opsi) bukan
                # perintah — lompati juga.
                # Audit 1033: kata berbentuk penetapan SESUDAH exec
                # adalah nama program yang di-exec, bukan penetapan
                # shell — tandai agar cabang penetapan tak menelannya.
                sesudah_exec = True
                while awal < len(toks):
                    o = toks[awal]
                    if (o in ("-c", "-l")
                            or re.fullmatch(r"-[cl]+", o)):
                        awal += 1
                        continue
                    if o == "-a":
                        awal += 2
                        continue
                    if o.startswith("-a") and len(o) > 2:
                        awal += 1
                        continue
                    if o == "--":
                        awal += 1
                    break
            if t0 == "time":
                # Audit 1032: '--' juga akhir opsi time, seperti -p.
                while (awal < len(toks)
                        and toks[awal] in ("-p", "--")):
                    berhenti = toks[awal] == "--"
                    awal += 1
                    if berhenti:
                        break
            continue
        if t0 == "timeout" or t0 in ("xargs", "nice", "command",
                "stdbuf", "setsid", "ionice", "chrt", "taskset",
                "eatmydata", "nohup", "flock", "sudo",
                # Audit 1065/1070: pembungkus baru — unshare dan
                # chroot (operand direktori ditangani di bawah),
                # systemd-cat (operand perintah/penyalur stdin).
                # Audit 1107: pembungkus putaran-2 — strace,
                # dbus-run-session, runuser (perintah operand);
                # su/sg (string -c / shell interaktif, blok khusus).
                "unshare", "chroot", "systemd-cat", "strace",
                "dbus-run-session", "runuser", "su", "sg",
                # Audit 1155/1156: pembungkus putaran-6.
                "setpriv", "prlimit",
                # Audit 1253/1254/1255/1256: pembungkus
                # putaran-8 (setarch beroperand arsitektur).
                "setarch", "linux32", "linux64", "uclampset",
                "choom", "xvfb-run",
                # Audit 1318: alias personalitas langsung.
                "i386", "x86_64",
                # Audit 1194/1203: newgrp (sekelas sg) dan
                # ssh-agent.
                "newgrp", "ssh-agent"):
            # Audit 1051: 'command' SESUDAH exec adalah nama program
            # untuk execvp, bukan pembungkus — jangan transparan.
            if sesudah_exec and t0 == "command":
                break
            # Audit 972: sesudah pembungkus mana pun, kata perintah
            # berikutnya adalah program eksternal.
            # Audit 988: kecuali 'command' sendiri — ia builtin yang
            # menjalankan kata berikutnya lewat shell, jadi posisi
            # awal diteruskan (command kedua tetap transparan).
            if t0 != "command":
                masih_awal = False
            else:
                # Audit 1034: ingat lintasan 'command' — kata kunci
                # di baliknya bukan builtin (lihat cabang 1020).
                lewat_command = True
            if t0 == "xargs":
                # Audit 962: xargs terdalam yang menentukan sumber
                # item — xargs di balik xargs -a membaca stdin
                # sendiri, jadi bendera -a lapisan luar gugur.
                xargs_dari_berkas = False
            # Audit 966: sesudah pembungkus non-env, penetapan tak
            # lagi transparan.
            lewat_pembungkus = True
            pembungkus_terakhir = t0
            if t0 == "sudo":
                # Audit 1044: sudo menerima penetapan lingkungan
                # sebagai bagian sintaksnya sendiri (VAR=nilai
                # perintah), seperti env — wilayah penetapan pulih.
                lewat_pembungkus = False
            # Audit 970: taskset/chrt berbentuk daftar/pid tak
            # mempunyai operand masker/prio sebelum perintah.
            tanpa_masker = False
            # Audit 989: mode pid ionice/taskset — semua operand
            # sesudah bendera -p adalah pid, tak pernah perintah.
            mode_pid = False
            awal += 1
            # Audit 957: nilai opsi pembungkus yang terpisah bukan
            # perintah — lompatan wajib melewatinya juga, kalau
            # tidak nilai itu yang dinilai sebagai perintah.
            bernilai = _OPSI_PEMBUNGKUS.get(t0, ())
            # Audit 1189: prlimit --pid menargetkan proses lain —
            # perintah sesudahnya BUKAN anak (nyata: galat
            # 'mutually exclusive' bila keduanya ada), dan
            # stdin tak dibaca.
            if t0 == "prlimit":
                # Audit 1228: pindaian --pid berhenti di
                # perintah anak — --pid sesudahnya milik anak.
                j_pr = awal
                while j_pr < len(toks):
                    t_pr = toks[j_pr]
                    if not t_pr.startswith("-"):
                        break
                    if (t_pr == "--pid"
                            or t_pr.startswith("--pid=")
                            # Audit 1277: bentuk pendek -p.
                            or t_pr == "-p"
                            or t_pr.startswith("-p=")
                            or re.fullmatch(r"-p\d+", t_pr)):
                        return False
                    if ("=" not in t_pr and t_pr
                            in _OPSI_PEMBUNGKUS.get(
                                "prlimit", ())):
                        j_pr += 2
                    else:
                        j_pr += 1
            # Audit 1296: choom mode -p/--pid — perintah
            # sesudahnya bukan anak (saling meniadakan).
            if t0 == "choom":
                j_ch = awal
                while j_ch < len(toks):
                    t_ch = toks[j_ch]
                    if not t_ch.startswith("-"):
                        break
                    if (t_ch in ("-p", "--pid")
                            or t_ch.startswith("--pid=")
                            or t_ch.startswith("-p=")
                            or re.fullmatch(r"-p\d+", t_ch)):
                        return False
                    if ("=" not in t_ch and t_ch
                            in _OPSI_PEMBUNGKUS.get(
                                "choom", ())):
                        j_ch += 2
                    else:
                        j_ch += 1
            # Audit 1298: alias setarch --list mencetak lalu
            # keluar — perintah sesudahnya tak berjalan.
            if t0 in ("linux32", "linux64", "i386", "x86_64"):
                j_la = awal
                while (j_la < len(toks)
                        and toks[j_la].startswith("-")):
                    if toks[j_la] == "--list":
                        return False
                    j_la += 1
            if t0 in ("su", "sg", "newgrp"):
                # Audit 1107: su/sg — string -c dinilai seperti
                # flock -c; tanpa -c keduanya menjatuhkan shell
                # interaktif yang membaca perintah dari stdin.
                # Operand pertama adalah pengguna (su) / grup (sg).
                bernilai_su = {"-s", "--shell", "-g", "--group",
                               "-G", "--supp-group"}
                j_su = awal
                operand_su = False
                while j_su < len(toks):
                    t_su = toks[j_su]
                    # Audit 1140: -c juga sah berbentuk bundel
                    # berakhiran c (-mc) dan merekat (-c'...').
                    perintah_su = None
                    if t_su in ("-c", "--command"):
                        j_su += 1
                        j_su = _sesudah_redirect_berantai(
                            toks, j_su, _batas_argv)
                        if j_su < 0 or j_su >= len(toks):
                            return False
                        perintah_su = toks[j_su]
                    elif (t_su.startswith("-c") and len(t_su) > 2
                            and not t_su.startswith("--")):
                        perintah_su = t_su[2:]
                    elif re.fullmatch(r"-[a-z]*c", t_su):
                        j_su += 1
                        j_su = _sesudah_redirect_berantai(
                            toks, j_su, _batas_argv)
                        if j_su < 0 or j_su >= len(toks):
                            return False
                        perintah_su = toks[j_su]
                    if perintah_su is not None:
                        nilai_su = perintah_su
                        if (len(nilai_su) >= 1
                                and nilai_su[0] in "\"'"
                                and not (len(nilai_su) >= 2
                                         and nilai_su[-1]
                                         == nilai_su[0])):
                            kutip_su = nilai_su[0]
                            kumpul_su = [nilai_su]
                            k_su = j_su + 1
                            while k_su < len(toks):
                                kumpul_su.append(toks[k_su])
                                if toks[k_su].endswith(kutip_su):
                                    break
                                k_su += 1
                            nilai_su = " ".join(kumpul_su)
                        return _menguras_stdin(
                            nilai_su.strip("\"'"))
                    if t_su in bernilai_su:
                        j_su += 2
                        continue
                    if t_su == "--" or t_su.startswith("-"):
                        j_su += 1
                        continue
                    if not operand_su:
                        operand_su = True
                        j_su += 1
                        continue
                    break
                return True
            while awal < len(toks):
                if awal >= _batas_argv:
                    # Audit 965: redirect bisa menyela daftar opsi
                    # pembungkus (termasuk sebelum operand durasi
                    # timeout) — lompati dulu, jangan dinilai opsi
                    # atau operand.
                    sesudah = _indeks_sesudah_redirect(toks, awal)
                    if sesudah < 0:
                        return False
                    if sesudah != awal:
                        awal = sesudah
                        continue
                if not toks[awal].startswith("-"):
                    break
                opsi = toks[awal]
                awal += 1
                if opsi == "--":
                    break
                if t0 == "sudo" and opsi in (
                        "-s", "--shell", "-i", "--login"):
                    # Audit 1050: mode shell sudo.
                    sudo_shell = True
                if (t0 == "sudo" and len(opsi) > 2
                        and opsi[0] == "-" and opsi[1] != "-"):
                    # Audit 1082: bundel opsi pendek — huruf bendera
                    # di mana pun dalam bundel berlaku, sampai huruf
                    # opsi bernilai yang menelan sisa token sebagai
                    # nilainya. Audit 1097: bundel yang memuat -s
                    # DAN -i ditolak sudo — bukan mode shell.
                    bendera_sudo = set()
                    for huruf_sudo in opsi[1:]:
                        if huruf_sudo in "ughptCTDrR":
                            break
                        bendera_sudo.add(huruf_sudo)
                    if ("s" in bendera_sudo) != ("i" in bendera_sudo):
                        sudo_shell = True
                if (t0 == "command" and len(opsi) > 1
                        and all(c in "pvV" for c in opsi[1:])
                        and ("v" in opsi or "V" in opsi)):
                    # Audit 961: -v/-V pada command adalah bendera
                    # tanya — perintah di baliknya tidak dijalankan
                    # dan stdin tidak dibaca sama sekali.
                    # Audit 971: xargs lapisan luar tanpa -a tetap
                    # menguras stdin untuk itemnya sendiri.
                    return xargs_sendiri
                if (t0 == "xargs"
                        and (opsi in ("-a", "--arg-file")
                             or opsi.startswith("--arg-file=")
                             or (opsi.startswith("-a")
                                 and len(opsi) > 2))):
                    # Audit 962: item dari berkas argumen menjadi
                    # operand perintah anak, bukan stdin bersama.
                    # Audit 1134: nilai "-" berarti daftar item
                    # dibaca dari stdin itu sendiri.
                    nilai_a = None
                    if opsi in ("-a", "--arg-file"):
                        k_a = _sesudah_redirect_berantai(
                            toks, awal, _batas_argv)
                        if 0 <= k_a < len(toks):
                            nilai_a = toks[k_a].strip("\"'")
                    elif opsi.startswith("--arg-file="):
                        nilai_a = opsi.split("=", 1)[1]
                    else:
                        nilai_a = opsi[2:]
                    if nilai_a == "-":
                        xargs_sendiri = True
                    else:
                        xargs_dari_berkas = True
                if t0 == "taskset" and (opsi.startswith("--cpu-list=")
                        or opsi.startswith("--pid=")):
                    # Audit 989: getopt taskset menolak argumen =
                    # untuk opsi mode ini ("doesn't allow an
                    # argument") — perintah tak pernah berjalan.
                    return xargs_sendiri
                if ((t0 == "ionice" and (opsi in ("-p", "--pid")
                        or opsi.startswith("--pid=")
                        or (opsi.startswith("-p") and len(opsi) > 2)))
                        or (t0 == "taskset"
                            and opsi in ("-p", "--pid"))):
                    # Audit 989: bendera mode pid — operand
                    # sesudahnya adalah pid-pid, bukan perintah.
                    mode_pid = True
                if (t0 == "taskset" and len(opsi) > 2
                        and opsi.startswith("-")
                        and not opsi.startswith("--")
                        and "p" in opsi[1:]):
                    # Audit 998: bendera gabungan — util-linux
                    # memperlakukan "p" di dalam token gabungan
                    # (-pc, -cp, -ap, ...) sebagai bendera mode pid,
                    # jadi operand sesudahnya pid, bukan perintah
                    # ("invalid PID argument" di perangkat nyata).
                    mode_pid = True
                if ((t0 == "taskset"
                        and (opsi in ("-c", "--cpu-list", "-p", "--pid")
                             or opsi.startswith("--cpu-list=")
                             or opsi.startswith("--pid=")))
                        or (t0 == "chrt"
                            and opsi in ("-p", "--pid"))):
                    # Audit 970: bentuk daftar/pid — tak ada
                    # operand masker/prio yang perlu dilompati.
                    tanpa_masker = True
                if (t0 == "ionice" and len(opsi) > 2
                        and opsi.startswith("-")
                        and not opsi.startswith("--")):
                    # Audit 1006: bundel opsi — getopt menguraikan
                    # token gabungan huruf demi huruf; opsi bernilai
                    # (c/n/u) menelan sisa token sebagai nilainya,
                    # atau token berikutnya bila ia huruf terakhir
                    # ("-tc 2" = -t + -c 2). Bendera lain yang
                    # dikenal hanya -t; huruf tak dikenal menghentikan
                    # penguraian (perilaku lama dipertahankan).
                    for j_b in range(1, len(opsi)):
                        ch_b = opsi[j_b]
                        if ch_b in "cnu":
                            if j_b == len(opsi) - 1:
                                awal = _sesudah_redirect_berantai(
                                    toks, awal, _batas_argv)
                                if awal < 0:
                                    return False
                                if awal < len(toks):
                                    awal += 1
                            break
                        if ch_b != "t":
                            break
                if opsi in bernilai:
                    # Audit 968: redirect yang menyela bukan nilai
                    # opsi — lompati dulu, baru telan nilainya.
                    awal = _sesudah_redirect_berantai(
                        toks, awal, _batas_argv)
                    if awal < 0:
                        return False
                    if awal < len(toks):
                        awal += 1
            if mode_pid:
                # Audit 989: mode pid — sisa token adalah pid-pid,
                # resolusi selesai tanpa perintah.
                awal = len(toks)
            if (t0 == "timeout" or t0 == "flock" or t0 == "chroot"
                    # Audit 1253: operand pertama setarch adalah
                    # nama arsitektur, berposisi seperti dir
                    # chroot.
                    or t0 == "setarch"
                    or (t0 in ("taskset", "chrt")
                        and not tanpa_masker)) and awal < len(toks):
                # Audit 1065: operand pertama chroot adalah
                # DIREKTORI root baru — berposisi seperti operand
                # berkas flock.
                # Audit 1015: operand pertama flock (berkas kunci /
                # nomor fd) berposisi seperti durasi timeout.
                # Audit 968: redirect sebelum durasi (mis. sesudah
                # '--') dilompati dulu, jangan ditelan sebagai
                # operand durasi.
                # Audit 970: operand pertama taskset (masker) dan
                # chrt (prio) berposisi seperti durasi timeout.
                awal = _sesudah_redirect_berantai(
                    toks, awal, _batas_argv)
                if awal < 0:
                    return False
                if awal < len(toks):
                    awal += 1  # operand durasi/masker/prio
            if (t0 == "flock" and awal < len(toks)
                    and toks[awal] in ("-c", "--command")):
                # Audit 1023: bentuk string flock — nilai -c adalah
                # perintah yang dijalankan lewat /bin/sh -c sesudah
                # operand berkas kunci; nilai itulah yang dinilai.
                # (-c SEBELUM operand berkas bukan bentuk sah flock
                # dan sengaja tak dimodelkan.)
                awal += 1
                awal = _sesudah_redirect_berantai(
                    toks, awal, _batas_argv)
                if awal < 0:
                    return False
                if awal >= len(toks):
                    return False
                nilai = toks[awal]
                if (len(nilai) >= 1 and nilai[0] in "\"'"
                        and not (len(nilai) >= 2
                                 and nilai[-1] == nilai[0])):
                    # Audit 1035: string -c berkutip multi-kata
                    # terpecah tokenizer menjadi banyak token —
                    # kumpulkan sampai token yang menutup kutipnya,
                    # seperti rentang string env -S (Audit 963).
                    kutip_c = nilai[0]
                    kumpul = [nilai]
                    j = awal + 1
                    while j < len(toks):
                        kumpul.append(toks[j])
                        if toks[j].endswith(kutip_c):
                            break
                        j += 1
                    nilai = " ".join(kumpul)
                return _menguras_stdin(nilai.strip("\"'"))
            if t0 == "xargs" and awal < len(toks):
                awal = _sesudah_redirect_berantai(
                    toks, awal, _batas_argv)
                if awal < 0:
                    return False
                if awal < len(toks) and toks[awal].isdigit():
                    awal += 1  # nilai opsi xargs yang terpisah
            if t0 == "xargs" and not xargs_dari_berkas:
                # Audit 971: xargs tanpa -a membaca stdin bersama
                # untuk itemnya sendiri, apa pun perintah anaknya.
                xargs_sendiri = True
            continue
        break
    if xargs_sendiri:
        # Audit 986: redirect masukan yang tertulis sesudah nama
        # perintah anak adalah milik invokasi xargs itu sendiri —
        # shell menerapkannya ke proses xargs dan anak mewarisi
        # stdin dari berkas/penutup itu, jadi pipa bersama tak
        # tersentuh walau xargs sendiri tadinya akan menguras.
        for pos in range(awal + 1, len(toks)):
            if pos < _batas_argv:
                continue  # di dalam string env -S: argv harfiah
            tok = toks[pos]
            # '<' pertama di luar kutip pada token ini.
            idx_lt = -1
            q_lt = ""
            j_lt = 0
            while j_lt < len(tok):
                ch_lt = tok[j_lt]
                if q_lt:
                    if ch_lt == q_lt:
                        q_lt = ""
                elif ch_lt in "\"'":
                    q_lt = ch_lt
                elif ch_lt == "\\":
                    j_lt += 1
                elif ch_lt == "<":
                    idx_lt = j_lt
                    break
                j_lt += 1
            if idx_lt < 0:
                continue
            awalan_lt = tok[:idx_lt]
            if awalan_lt and not awalan_lt.isdigit():
                # Audit 996: redirect yang merekat di tengah kata —
                # shell memecah "a<berkas" menjadi operand "a" plus
                # redirect fd 0 milik xargs; awalan bukan fd.
                fd_lt = "0"
            else:
                fd_lt = _fd_norm(awalan_lt)
            # Audit 995: semantik fd yang sama dengan
            # _indeks_sesudah_redirect — hanya redirect yang
            # MENGGANTI stdin (fd 0 ke berkas/fd lain, atau
            # menutupnya) yang membatalkan vonis xargs. Redirect
            # fd lain (3<, 9<, ...) tak menyentuh stdin xargs,
            # dan <&0 adalah duplikasi-diri yang netral: xargs
            # tetap menguras pipa bersama.
            if fd_lt not in ("", "0"):
                continue
            sufiks_lt = tok[idx_lt + 1:]
            if sufiks_lt.startswith("&"):
                suf_t = sufiks_lt[1:]
                m_t = re.fullmatch(r"(\d+)(?:-)?|(-)", suf_t)
                if suf_t and m_t is None:
                    # Audit 1007: target duplikasi bersufiks huruf
                    # ("<&0x", "<&0abc") ditolak bash sebagai
                    # ambiguous redirect — perintah batal sebelum
                    # berjalan, pipa tak tersentuh.
                    return False
                target = (m_t.group(1) or m_t.group(2)) if m_t else None
                if target is None and pos + 1 < len(toks):
                    target = toks[pos + 1].strip("\"'")
                if _fd_norm(target or "") == "0":
                    continue
                return False
            return False
    if awal >= len(toks):
        # Audit 1050: sudo -s/-i tanpa perintah menjalankan shell
        # yang membaca perintah dari stdin sampai EOF.
        # Audit 1070: systemd-cat tanpa perintah meneruskan
        # stdin-nya sendiri ke jurnal.
        if sudo_shell or pembungkus_terakhir == "systemd-cat":
            return True
        # Audit 1121: unshare/chroot tanpa perintah menjalankan
        # shell interaktif yang membaca perintah dari stdin.
        if pembungkus_terakhir in ("unshare", "chroot"):
            return True
        return xargs_sendiri
    perintah = _kata_shell(toks[awal])
    if perintah not in _PEMBACA_STDIN:
        # Audit 1039/1040: perintah yang menguras hanya pada
        # bentuk tertentu (interpreter skrip-dari-stdin, builtin
        # penampung baris) dinilai penilai khususnya sendiri.
        # Audit 1159: nama interpreter berversi dipetakan ke
        # nama kanonisnya (python3.12 → python3; tclsh8.6 →
        # tclsh, keluarga yang baru dikenal putaran ini).
        nama_interp = perintah
        if re.fullmatch(r"python3(\.\d+)?", perintah):
            nama_interp = "python3"
        elif re.fullmatch(r"tclsh(\d+(\.\d+)?)?", perintah):
            nama_interp = "tclsh"
        elif re.fullmatch(r"wish(\d+(\.\d+)?)?", perintah):
            # Audit 1736: wish adalah tclsh+Tk — tanpa
            # fileName membaca perintah dari stdin (wish(1));
            # dinilai cabang tclsh.
            nama_interp = "wish"
        elif perintah == "expectk":
            # Audit 1736: expectk adalah expect+Tk; dinilai
            # cabang expect.
            nama_interp = "expectk"
        # Audit 1301: alias berversi perl/zsh.
        elif re.fullmatch(r"perl5(\.\d+)*(-\S+)?", perintah):
            nama_interp = "perl"
        elif re.fullmatch(r"zsh\d+", perintah):
            nama_interp = "zsh"
        elif perintah == "nodejs":
            # Audit 1251: alias Debian untuk node.
            nama_interp = "node"
        # Audit 1653: nama berversi interpreter putaran
        # 1638 — nama biner standar distro (ruby3.1,
        # php8.2, lua5.4) berperilaku identik nama polosnya.
        elif re.fullmatch(r"ruby\d+(\.\d+)?", perintah):
            nama_interp = "ruby"
        elif re.fullmatch(r"php\d+(\.\d+)?", perintah):
            nama_interp = "php"
        elif re.fullmatch(r"lua\d+(\.\d+)?", perintah):
            nama_interp = "lua"
        if nama_interp in ("python3", "python", "node", "perl",
                           # Audit 1058: shell membaca perintah
                           # dari stdin bila tanpa skrip berkas.
                           # Audit 1122: expect pembaca skrip.
                           # Audit 1158: shell terbatas rbash/rzsh.
                           "bash", "sh", "dash", "zsh", "expect",
                           "rbash", "rzsh", "tclsh", "wish", "expectk",
                           # Audit 1638: shell & interpreter lain
                           # — tanpa skrip membaca dari stdin
                           # seperti keluarganya.
                           "ash", "ksh", "fish",
                           # Audit 1737: shell keluarga kedua
                           # (mksh = sh bawaan Android).
                           "mksh", "tcsh", "csh", "yash",
                           "ruby", "php", "lua", "luajit"):
# Audit 1796: bendera xargs_sendiri (Audit 971) berlaku
            # di semua jalur return dispatch - xargs tanpa -a
            # menguras stdin untuk itemnya apa pun anaknya.
            return xargs_sendiri or _menguras_interpreter(
                nama_interp, toks, awal)
        if perintah in ("mapfile", "readarray"):
            return xargs_sendiri or _menguras_mapfile(toks, awal)
        # Audit 1302-1304: nama kompiler bertriple/bersufiks
        # versi dinormalkan ke nama kanonisnya.
        m_komp = re.fullmatch(
            # Audit 1351: triple mesin lain (aarch64-linux-gnu-,
            # arm-linux-gnueabihf-, riscv64-linux-gnu-) juga
            # dinormalkan — pola triple generik berdash.
            r"(?:[a-zA-Z0-9_]+(?:-[a-zA-Z0-9_]+)+-)?"
            r"(gcc|g\+\+|cc|c\+\+|cpp|as)"
            r"(?:-\d+(?:\.\d+)?)?", perintah)
        if m_komp:
            return xargs_sendiri or _menguras_khusus(
                m_komp.group(1), toks, awal)
        if perintah in ("sqlite3", "openssl", "tar", "git",
                        "comm", "join", "diff", "diff3", "csplit",
                        "zip", "rsync", "socat", "gpg", "gpg2",
                        "gpgsm", "curl",
                        # Audit 1157: nama varian nc.
                        "wget", "nc", "netcat", "nc.openbsd",
                        "make", "visudo", "patch",
                        "more", "less", "vi", "vim", "nvim",
                        "basenc", "view", "ex", "du", "find",
                        "addr2line", "dig",
                        # Audit 1193/1195/1197/1198/1200/1201/
                        # 1202/1204: penilai khusus putaran-7.
                        "pdftotext", "nft", "ip", "tc", "bridge",
                        "dpkg", "file", "date", "sysctl", "bun", "sftp",
                        "pwsh", "powershell", "bc", "dc", "ed",
                        "hexdump", "hd", "xxd", "cpio", "pax",
                        "pg", "bat", "batcat", "lzip", "lz4",
                        "unlz4", "lz4cat", "rpm2cpio", "7z",
                        "7za", "7zr", "7zz", "uuencode",
                        "uudecode", "sponge", "ts", "vipe", "pee",
                        "recode",
                        # Audit 1249/1250/1252/1258/1259:
                        # penilai khusus putaran-8.
                        "sdiff", "socat1", "gmake", "pdfinfo",
                        "pdffonts", "pdfimages", "pdftops",
                        # Audit 1404: perender poppler.
                        "pdftoppm", "pdftocairo",
                        "certutil", "cmsutil", "apt-key",
                        # Audit 1305-1345: penilai khusus putaran-9.
                        "zcmp", "zdiff",
                        "xzcmp", "xzdiff",
                        "lzcmp", "lzdiff",
                        "cmp",
                        "ptar",
                        "vim.tiny", "rview", "editor",
                        "sensible-editor",
                        "script",
                        "tmux",
                        "systemd-sysusers",
                        "systemd-tmpfiles",
                        "systemd-creds",
                        "debconf-communicate",
                        "dpkg-deb",
                        "dpkg-parsechangelog",
                        "cvtsudoers",
                        "tic",
                        "lesskey",
                        "xkbcomp",
                        "wayland-scanner",
                        "localedef",
                        "glib-genmarshal",
                        "genrb",
                        "pygettext3",
                        "nsupdate",
                        "ssh-keygen",
                        "tree",
                        "gio",
                        "debugfs",
                        "xauth",
                        # Audit 1347: systemd-run kini cabang
                        # khusus (semantik mode --scope).
                        # Audit 1358: keluarga ffmpeg.
                        "systemd-run", "ffmpeg", "ffprobe",
                        "ffplay",
                        # Audit 1377: gh (auth login
                        # --with-token membaca token).
                        "gh",
                        # Audit 1405: npm (init interaktif).
                        "npm",
                        "gm"):
            # Audit 1059-1072: penilai subcommand/bentuk khas.
            return xargs_sendiri or _menguras_khusus(
                perintah, toks, awal)
        return xargs_sendiri
    if xargs_dari_berkas and perintah != "tee":
        # Audit 962: perintah anak menerima operand item dari berkas
        # argumen xargs, jadi tak membaca stdin bersama (tee
        # dikecualikan: operandnya berkas keluaran, ia tetap
        # menguras). Isi berkas tak bisa diketahui gerbang; vonis
        # mengikuti pemakaian lazim -a, yaitu berkas berisi item.
        # Audit 971: lapisan xargs lain tanpa -a tetap menguras.
        return xargs_sendiri
    if xargs_sendiri:
        return True
    if perintah in ("sed", "awk", "grep", "jq",
                    "mawk", "nawk", "egrep", "fgrep", "rg",
                    # Audit 1638: varian GNU awk.
                    "gawk", "pgawk", "igawk", "dgawk",
                  # Audit 1244: grep terkompresi — pola
                  # pertamanya bukan berkas (penilai keluarga).
                  "zgrep", "bzgrep", "xzgrep", "lzgrep",
                  # Audit 1642: grep terkompresi zstd.
                  "zstdgrep",
                  # Audit 1306: egrep/fgrep gzip.
                  "zegrep", "zfgrep",
                  # Audit 1307: egrep/fgrep bzip2.
                  "bzegrep", "bzfgrep",
                  # Audit 1308: keluarga xz.
                  "xzegrep", "xzfgrep",
                  # Audit 1309: keluarga lzma.
                  "lzegrep", "lzfgrep",
                    "original-awk"):
        # Audit 1038: semantik operand keluarga skrip/pola.
        # Audit 1067: varian nama dipetakan ke kanonisnya.
        return _menguras_keluarga_skrip(perintah, toks, awal)
    if perintah == "shuf" and any(
            t in ("-e", "--echo") or t == "-i"
            or t.startswith("--input-range")
            for t in toks[awal + 1:]):
        # Audit 1037: shuf -e/-i mengambil masukan dari argumen/
        # rentang, bukan stdin.
        return False
    # Audit 1139: --files0-from pada sort/wc — sumber daftarnya
    # menentukan vonis: "-" stdin, selain itu berkas daftar
    # (du/find sudah benar lewat penilai khususnya, Audit 1111).
    if perintah in ("sort", "wc"):
        sisa_f0 = toks[awal + 1:]
        for k_f0, t_f0 in enumerate(sisa_f0):
            b_f0 = t_f0.strip("\"'")
            if b_f0 == "--files0-from":
                return (k_f0 + 1 < len(sisa_f0)
                        and sisa_f0[k_f0 + 1].strip("\"'") == "-")
            if b_f0.startswith("--files0-from="):
                return b_f0.split("=", 1)[1] == "-"
    # Audit 1102: panggilan informasional mencetak dan keluar
    # tanpa membaca stdin (--help/--version umum; iconv -l/--list).
    if perintah in _PEMBACA_STDIN and any(
            t.strip("\"'") in ("--help", "--version")
            for t in toks[awal + 1:]):
        return False
    # Audit 1407: bendera info huruf tunggal milik perintah
    # tertentu juga informasional (cpan -v/-V/-h, utmpdump
    # -h) — penjaga 1102 hanya mengenal bentuk panjang.
    if perintah in _PEMBACA_STDIN and any(
            t.strip("\"'") in _INFO_HURUF.get(perintah, ())
            for t in toks[awal + 1:]):
        return False
    if perintah == "gpgv":
        # Audit 1690: token bebas = token yang BUKAN nilai
        # opsi bernilai (penyusuran sadar nilai seperti
        # penjaga iconv Audit 1276) - "--warranty" sebagai
        # nilai -o/--log-file tak lagi memicu penjaga meta.
        bernilai_gv = _OPSI_BERNILAI.get("gpgv", set())
        bebas_gv = []
        k_gv = awal + 1
        while k_gv < len(toks):
            u_gv = toks[k_gv].strip("\"'")
            if u_gv == "--":
                break
            if u_gv in bernilai_gv:
                k_gv += 2
                continue
            if any(u_gv.startswith(o + "=")
                   for o in bernilai_gv if o.startswith("--")):
                k_gv += 1
                continue
            bebas_gv.append(u_gv)
            k_gv += 1
        # Audit 1675: opsi meta gpgv mencetak lalu keluar
        # tanpa membaca stdin (--warranty terprobe real:
        # payload utuh; --dump-options sekelas).
        # Audit 1691: --dump-option-table ikut meta
        # (enumerasi --dump-options GnuPG 2.4.4: satu-satunya
        # cetak-lalu-keluar yang kemarin absen).
        if any(u in ("--warranty", "--dump-options",
                     "--dump-option-table")
               for u in bebas_gv):
            return False
        # Audit 1710: opsi panjang gpgv tak dikenal = galat
        # "invalid option" lalu keluar (kelas Audit 1694
        # untuk gpgv); --help/--version ikut meta cetak-
        # lalu-keluar. Himpunan sah = enumerasi
        # --dump-options GnuPG 2.4.4.
        sah_gv = {"--assert-pubkey-algo", "--debug",
                  "--dump-option-table", "--dump-options",
                  "--enable-special-filenames", "--help",
                  "--homedir", "--ignore-time-conflict",
                  "--keyring", "--log-file", "--logger-fd",
                  "--output", "--quiet", "--status-fd",
                  "--verbose", "--version", "--warranty",
                  "--weak-digest"}
        if any(u.startswith("--")
               and u.split("=")[0] not in sah_gv
               for u in bebas_gv):
            return False
        if any(u.split("=")[0] in ("--help", "--version")
               for u in bebas_gv):
            return False
        # Audit 1649: bentuk terdokumentasi `gpgv sigfile -`
        # (man gpgv EXAMPLES) — operand kedua "-" berarti
        # data bertanda tangan dibaca dari stdin walau
        # operand pertamanya berkas sig.
        # Audit 1709: operand gpgv ditelusuri BERURUTAN -
        # gpgv berhenti memindai opsi di operand pertama;
        # "--" yang muncul sesudah operand pertama adalah
        # nama berkas harfiah, bukan pemisah.
        ops_gv = []
        k2_gv = awal + 1
        operand_mulai = False
        while k2_gv < len(toks):
            u2_gv = toks[k2_gv].strip("\"'")
            if operand_mulai:
                ops_gv.append(u2_gv)
                k2_gv += 1
                continue
            if u2_gv == "--":
                operand_mulai = True
                k2_gv += 1
                continue
            if u2_gv in bernilai_gv:
                if k2_gv + 1 >= len(toks):
                    ops_gv.append("\x00gantung")
                k2_gv += 2
                continue
            if any(u2_gv.startswith(o + "=")
                   for o in bernilai_gv if o.startswith("--")):
                k2_gv += 1
                continue
            if u2_gv.startswith("-") and u2_gv != "-":
                k2_gv += 1
                continue
            operand_mulai = True
            ops_gv.append(u2_gv)
            k2_gv += 1
        # Audit 1689: penanda mode nama-berkas khusus.
        khusus_gv = "--enable-special-filenames" in bebas_gv
        # Audit 1659: cerminnya — operand pertama "-"
        # berarti berkas tanda tangan dibaca dari stdin
        # (terprobe real: detached-sig sah terkuras penuh).
        # Audit 1688: gpgv mengonkatenasi SEMUA operand
        # data (terprobe fungsional: tanda tangan atas
        # dataA+dataB sah dari "gpgv gab.sig dataA -") -
        # "-" di posisi data mana pun menguras; premis
        # dua-operand Audit 1671 gugur oleh bukti itu.
        if len(ops_gv) >= 2 and ops_gv[0] == "-":
            return True
        # Audit 1709: operand data diproses berurutan -
        # "--" harfiah menghentikan dengan galat SEBELUM
        # "-" sesudahnya sempat dibaca.
        if len(ops_gv) >= 2:
            for o_gv in ops_gv[1:]:
                if o_gv == "--":
                    break
                if o_gv == "-":
                    return True
        # Audit 1689: --enable-special-filenames menjadikan
        # operand "-&0" pembaca fd 0 (terprobe real: Good
        # signature dari stdin). "-&0" tak pernah masuk
        # daftar operand (penyapu operand melewati semua
        # token berawalan "-"), jadi kehadirannya dicari di
        # token mentah sesudah perintah - di sana ia hanya
        # bisa berstatus operand.
        # Audit 1690: pencarian "-&0" ikut sadar nilai -
        # di token bebas ia hanya bisa berstatus operand
        # (nilai opsi bernilai sudah tersaring keluar).
        if khusus_gv and "-&0" in bebas_gv:
            return True
    if perintah == "iconv":
        # Audit 1263: --usage juga informasional.
        # Audit 1276: token yang menjadi NILAI opsi bernilai
        # iconv (mis. berkas keluaran -o bernama --usage)
        # bukan panggilan informasional.
        bernilai_ic = _OPSI_BERNILAI.get("iconv", set())
        k_ic = awal + 1
        informasional_ic = False
        while k_ic < len(toks):
            t_ic = toks[k_ic].strip("\"'")
            if t_ic in bernilai_ic:
                k_ic += 2
                continue
            if "=" in t_ic and t_ic.split("=", 1)[0] in bernilai_ic:
                k_ic += 1
                continue
            if t_ic in ("-l", "--list", "--usage"):
                informasional_ic = True
                break
            k_ic += 1
        if informasional_ic:
            return False
    lewati = False
    # Audit 1286: pr membaca SETIAP operand berurutan.
    ada_stdin_pr = False
    # Audit 1349: operand berkas pr tak memutuskan vonis.
    ada_operand_pr = False
    # Audit 1406: keadaan operand strings ('-' ditolaknya).
    strings_tolak_dash = False
    strings_ada_stdin = False
    operand_tr = 0  # Audit 1047: penghitung operand himpunan tr.
    # Audit 1087: pada mode hapus (-d tanpa -s) GNU tr menolak
    # himpunan KEDUA — batas operand sah menyusut jadi 1.
    batas_tr = 2
    if perintah == "tr":
        huruf_tr = ""
        for t_tr in toks[awal + 1:]:
            b_tr = t_tr.strip("\"'")
            if b_tr == "--":
                break
            if b_tr.startswith("-") and b_tr != "-":
                huruf_tr += b_tr.lstrip("-")
        if ("d" in huruf_tr or "--delete" in huruf_tr) and not (
                "s" in huruf_tr or "--squeeze-repeats" in huruf_tr):
            batas_tr = 1
    for pos in range(awal + 1, len(toks)):
        t = toks[pos]
        if lewati:
            lewati = False
            # Audit 1005: token redirect BUKAN nilai opsi — shell
            # memproses redirect sebelum program berjalan, jadi opsi
            # bernilai tetap tanpa nilai dan program keluar dengan
            # galat tanpa membaca stdin ("head -n 3<berkas",
            # "sort -t <berkas"). Menelannya sebagai nilai membuat
            # perintah dinilai menguras padahal tak berjalan.
            if re.match(r"\d*(<|>)", t) or t.startswith(("<", ">")):
                return False
            continue
        # Audit 963: token di dalam rentang string env -S adalah
        # argv harfiah — operator redirect di dalamnya operand,
        # bukan redirect shell.
        argv_saja = pos < _batas_argv
        m_fd_lt = (re.match(r"(\d+)<", t)
                   if not argv_saja else None)
        if m_fd_lt:
            # Audit 997: redirect masukan ber-fd di posisi operand —
            # posisi AWALAN sudah paham semantik fd sejak Audit 967,
            # posisi operand tidak. fd 0 sungguh mengganti stdin;
            # fd lain tak menyentuh stdin bersama, jadi token ini
            # (dan targetnya bila terpisah) dilompati saja.
            sisa_fd = t[m_fd_lt.end():]
            if _fd_norm(m_fd_lt.group(1)) == "0":
                # Audit 1014: bedakan "<" (redirect berkas — stdin
                # sungguh diganti) dari "<&" (duplikasi — target 0
                # adalah duplikasi-diri yang netral, sama seperti
                # cabang "<&" tanpa awalan fd di bawah).
                if sisa_fd.startswith("&"):
                    target = sisa_fd[1:]
                    if _fd_norm(target) == "0":
                        continue
                    if target == "":
                        if (pos + 1 < len(toks)
                                and _fd_norm(
                                    toks[pos + 1].strip("\"'")) == "0"):
                            lewati = True
                            continue
                    return False
                return False
            if sisa_fd.startswith("&"):
                # Audit 1016: pindah-fd bersumber 0 ke fd tujuan
                # bukan-0 (posisi ini) menutup stdin perintah.
                target_op = sisa_fd[1:]
                if _sumber_nol_pindah(target_op):
                    return False
                if (target_op == "" and pos + 1 < len(toks)
                        and _sumber_nol_pindah(
                            toks[pos + 1].strip("\"'"))):
                    return False
            if sisa_fd in ("", "&"):
                lewati = True
            continue
        if t.startswith("<") and not argv_saja:
            # Audit 997: <&0 adalah duplikasi-diri yang netral —
            # stdin perintah tetap pipa bersama. Target "0" yang
            # terpisah sesudah "<&" telanjang juga netral.
            if (t.startswith("<&") and len(t) > 2
                    and _fd_norm(t[2:]) == "0"):
                continue
            if t == "<&":
                if (pos + 1 < len(toks)
                        and _fd_norm(toks[pos + 1].strip("\"'")) == "0"):
                    lewati = True
                    continue
                return False
            return False
        if (len(t) >= 2 and t[0] in "\"'"
                and t[-1] == t[0]):
            # Audit 939: token berkutip penuh adalah operand data,
            # walau isinya memuat karakter redirect.
            # Audit 958: operand tee adalah berkas keluaran — tee
            # tetap menguras stdin walau operand-nya berkutip.
            if perintah == "tee":
                continue
            # Audit 1047: set tr yang berkutip adalah idiom lazim
            # (tr '\n' ' ') — pengecualian tr harus menjangkaunya
            # sebelum vonis operand-data.
            if perintah == "tr":
                operand_tr += 1
                if operand_tr > batas_tr:
                    return False
                continue
            return False
        if ">" in t and _gt_di_luar_kutip(t) and not argv_saja:
            # Audit 1016: "N>&0-" merekat di posisi operand —
            # pindah-fd bersumber 0 menutup stdin (tujuan 0 netral).
            m_pindah = re.fullmatch(r"(\d*|&)>&(\d+-)", t)
            if (m_pindah and m_pindah.group(1) != "0"
                    and _fd_norm(m_pindah.group(2)) == "0"):
                return False
            if t in (">", ">>", "1>", "2>", "1>>", "2>>"):
                lewati = True
                continue
            awalan = _awalan_sebelum_gt(t)
            if (awalan is None or awalan == "" or awalan == "&"
                    or awalan.isdigit() or awalan.startswith("-")):
                # Redirect merekat murni (target/fd/opsi yang
                # merekat) — tak ada operand yang dinilai.
                continue
            # Audit 959: awalan adalah operand berkas yang merekat
            # ke redirect — nilainya seperti operand biasa.
            if perintah == "tee":
                continue
            if perintah == "dd":
                if awalan.startswith("if="):
                    # Audit 1041: /dev/stdin & keluarga fd 0 adalah
                    # stdin itu sendiri, bukan berkas lain.
                    if awalan[3:].strip("\"'") in (
                            "/dev/stdin", "/dev/fd/0",
                            "/proc/self/fd/0"):
                        continue
                    return False
                if "=" in awalan:
                    continue
            return False
        if t == "-":
            # Audit 1037: operand "-" adalah stdin itu sendiri
            # bagi filter coreutils.
            # Audit 1286: penilaian ini wajib mendahului
            # cabang opsi di bawah — "-" berawalan "-" dan
            # tertelan sebagai token opsi sebelum sempat
            # tercatat.
            if perintah == "pr":
                ada_stdin_pr = True
            # Audit 1406: strings MENOLAK '-' (galat usage /
            # nama berkas harfiah) — bukan stdin baginya.
            if perintah == "strings":
                strings_tolak_dash = True
            continue
        if t.startswith("-"):
            # Audit 938: opsi bernilai tak hanya -n/-c dan -o;
            # nilai opsi yang terpisah bukan operand berkas.
            if t in _OPSI_BERNILAI.get(perintah, ()):
                lewati = True
            continue
        if (perintah in ("tail", "sort", "pr",
                         # Audit 1241: od juga ber-offset usang.
                         "od")
                and re.fullmatch(r"\+\d+", t.strip("\"'"))):
            # Audit 1080: operand usang +N (mulai dari baris N)
            # bukan berkas — hanya perintah yang masih
            # mendukungnya (head modern menolaknya: bukan temuan).
            continue
        if t.strip("\"'") in ("/dev/stdin", "/dev/fd/0",
                              "/proc/self/fd/0"):
            # Audit 1099: jalur fd-0 sebagai operand ADALAH stdin
            # itu sendiri (pengecualian 1041 diperluas dari dd).
            # Audit 1406: untuk strings ini operand stdin
            # SUNGGUHAN (berbeda dari '-' yang ditolaknya).
            if perintah == "strings":
                strings_ada_stdin = True
            continue
        if perintah == "tr":
            # Audit 1037: operand tr adalah himpunan karakter,
            # tak pernah berkas masukan.
            # Audit 1047: GNU tr menolak operand KETIGA dan tidak
            # membaca apa pun — model tanpa batas salah arah.
            operand_tr += 1
            if operand_tr > batas_tr:
                return False
            continue
        if perintah == "tee":
            continue
        if perintah == "dd":
            if t.startswith("if="):
                # Audit 1041: /dev/stdin & keluarga fd 0 adalah
                # stdin itu sendiri, bukan berkas lain.
                if t[3:].strip("\"'") in (
                        "/dev/stdin", "/dev/fd/0",
                        "/proc/self/fd/0"):
                    continue
                return False
            if "=" in t:
                continue
        # Audit 1371: chpasswd TAK punya operand berkas —
        # token asing diabaikan dan stdin tetap dibaca
        # (berbeda dari newusers yang sah beroperand berkas).
        # Audit 1397: chgpasswd sama persis — tak beroperand.
        if perintah in ("chpasswd", "chgpasswd"):
            continue
        # Audit 1349: pr membaca SETIAP operand berurutan —
        # operand berkas pertama tak boleh memutuskan; catat
        # dan lanjutkan ('-' bisa muncul sesudah berkas).
        if perintah == "pr":
            ada_operand_pr = True
            continue
        return False
    # Audit 1096: opsi bernilai (atau operator redirect) di token
    # terakhir menggantung — program/shell galat tanpa membaca.
    if lewati:
        return False
    # Audit 1349: pr beroperand berkas hanya menguras bila
    # '-' termasuk di antara operandnya.
    if perintah == "pr" and ada_operand_pr:
        return ada_stdin_pr
    # Audit 1406: strings yang menolak '-' tak membaca apa
    # pun dari pipa — kecuali ada operand stdin sungguhan.
    if perintah == "strings" and strings_tolak_dash:
        return strings_ada_stdin
    return True


def segmen_redirect_output(segmen):
    """True bila segmen memuat redirect `>>` ke PERSIS $GITHUB_OUTPUT
    yang berada DI LUAR kutip dan menjadi penutup segmen (Audit 874):
    redirect di dalam teks berkutip adalah data, bukan penulisan.
    Sufiks `|| ...` sesudah target tetap sah (Audit 875): penulisan
    sudah terjadi sebelum rantai ATAU dievaluasi shell."""
    hasil = False
    sesudah_op = False
    dalam = 0
    tikus = False
    i = 0
    n = len(segmen)
    kutip = ""
    while i < n:
        c = segmen[i]
        if c not in " \t><":
            # Kata/isi apa pun di antara dua operator membersihkan
            # keadaan; operator yang langsung mengikuti operator
            # adalah sintaks tak sah (Audit 895).
            sesudah_op = False
        if kutip:
            if c == "\\" and kutip == '"':
                i += 2
                continue
            if c == kutip:
                kutip = ""
            i += 1
            continue
        if c == "\\" and i + 1 < n:
            # Karakter ter-escape di luar kutip adalah literal —
            # bukan operator, pembuka kutip, atau awal substitusi
            # (Audit 908/909).
            i += 2
            continue
        if c in ("'", '"'):
            kutip = c
            i += 1
            continue
        if c == "`":
            # Substitusi gaya lama: isi di antara dua backtick milik
            # perintah-dalam seperti substitusi perintah
            # (Audit 903/904).
            tikus = not tikus
            i += 1
            continue
        if c in "><" and i + 1 < n and segmen[i + 1] == "(":
            # Substitusi proses `>(...)`/`<...)`: kurungnya membuka
            # kedalaman seperti substitusi perintah — isinya milik
            # perintah lain, bukan perintah ini (Audit 901/902).
            dalam += 1
            i += 2
            continue
        if c == "$" and i + 1 < n and segmen[i + 1] == "(":
            # Masuk substitusi perintah: redirect di dalamnya milik
            # perintah-dalam, bukan perintah ini (Audit 899/900).
            dalam += 1
            i += 2
            continue
        if c == "(" and dalam > 0:
            dalam += 1
            i += 1
            continue
        if c == ")" and dalam > 0:
            dalam -= 1
            i += 1
            continue
        if c == "<" and dalam == 0 and not tikus:
            # `<` adalah operator redirect masukan: ia ikut
            # memasang keadaan sesudah-operator (Audit 898), kecuali
            # `<` kedua dari heredoc `<<` yang memang berurutan.
            if sesudah_op and segmen[i - 1] != "<":
                return False
            sesudah_op = True
            i += 1
            continue
        if (c == ">" and dalam == 0 and not tikus and i > 0
                and segmen[i - 1] == "<"):
            # `>` pada operator `<>` adalah redirect baca-tulis di
            # stdin, bukan penulisan stdout (Audit 894).
            sesudah_op = True
            i += 1
            continue
        if (c == ">" and dalam == 0 and not tikus and sesudah_op):
            return False
        if (c == ">" and dalam == 0 and not tikus
                and not (i + 2 < n and segmen[i + 1] == "&"
                              and (segmen[i + 2].isdigit()
                                   or segmen[i + 2] == "-"))):
            # `>&` hanya duplikasi fd bila diikuti angka/`-`; diikuti
            # nama berkas ia penulisan kedua fd ke berkas (Audit 893).
            # Redirect `>` tunggal (truncate) juga penulisan
            # (Audit 889); `>=` bukan operator redirect.
            op = 2 if (i + 1 < n and segmen[i + 1] in ">&") else 1
            if op == 1 and i + 1 < n and segmen[i + 1] == "=":
                i += 1
                continue
            # Clobber `>|` adalah redirect truncate paksa (Audit 892):
            # pipa di sini bagian operator, bukan pemisah perintah.
            # Hanya untuk `>` tunggal — `>>|` bukan operator bash
            # (Audit 896), pipanya tidak dikonsumsi di sana.
            if op == 1 and i + op < n and segmen[i + op] == "|":
                op += 1
            # Redirect fd selain stdout (`2>>` dsb.) tidak menulis
            # keluaran perintah (Audit 890): digit fd hanya dihitung
            # bila berdiri sebagai kata sendiri (didahului batas kata);
            # `1>>` dan tanpa fd berarti stdout. Lewati tanpa menilai.
            j = i - 1
            while j >= 0 and segmen[j].isdigit():
                j -= 1
            if (j < i - 1 and segmen[j + 1:i] != "1"
                    and (j < 0 or segmen[j] in " \t;&|()")):
                sesudah_op = True
                i += op
                continue
            # Komentar akhir baris (`# ...` mengawali kata) sesudah
            # target juga sah (Audit 878); `#` yang menempel pada
            # kata BUKAN komentar bagi shell (Audit 882).
            # Target redirect adalah SATU kata shell (Audit 899):
            # ambil kata pertama sesudah operator, sadar kutip —
            # argumen yang mengikutinya bukan bagian target.
            mentah = segmen[i + op:].lstrip(" \t")
            kata = []
            kq = ""
            for ch in mentah:
                if kq:
                    kata.append(ch)
                    if ch == kq:
                        kq = ""
                    continue
                if ch in ("'", '"'):
                    kq = ch
                    kata.append(ch)
                    continue
                if ch in " \t":
                    break
                kata.append(ch)
            sisa = (_potong_komentar_sisa("".join(kata))
                    .split("||")[0].strip())
            # Penutup pembungkus subshell/grup yang menempel sesudah
            # target (Audit 887) bukan bagian nama berkas — tetapi
            # sisa yang SUDAH persis target sah tak boleh dikupas:
            # kurung tutup bentuk polos `${GITHUB_OUTPUT}` adalah
            # bagian targetnya (Audit 891).
            # Hanya `)` yang dibuang: kurung subshell memang
            # metakarakter penutup, sedangkan `}` yang menempel
            # pada kata adalah karakter nama berkas bagi shell
            # (Audit 897) — penutup grup selalu kata tersendiri
            # yang terpisah segmen oleh `;`.
            while (sisa and sisa[-1] == ")"
                   and sisa not in ('"$GITHUB_OUTPUT"', "$GITHUB_OUTPUT",
                                    '"${GITHUB_OUTPUT}"',
                                    "${GITHUB_OUTPUT}")):
                sisa = sisa[:-1].rstrip()
            if mentah[:2] in (">(", "<("):
                # Target substitusi proses (Audit 911): yang menulis
                # adalah perintah-dalamnya — nilai isi kurungnya
                # secara rekursif, lalu lewati seimbang.
                awal = i + op + (len(segmen[i + op:]) - len(mentah))
                kedalaman = 0
                k = awal + 1
                while k < n:
                    if segmen[k] == "\\" and k + 1 < n:
                        # Pasangan escape adalah literal (Audit 927):
                        # kurung ter-escape bukan kurung sungguhan.
                        k += 2
                        continue
                    if segmen[k] in ("'", '"'):
                        # Kurung di dalam kutip bukan kurung sungguhan
                        # (Audit 922) — lewati rentang berkutip utuh.
                        qk = segmen[k]
                        k += 1
                        while k < n and segmen[k] != qk:
                            if qk == '"' and segmen[k] == "\\":
                                k += 1
                            k += 1
                        k += 1
                        continue
                    if segmen[k] == "(":
                        kedalaman += 1
                    elif segmen[k] == ")":
                        kedalaman -= 1
                        if kedalaman == 0:
                            break
                    k += 1
                # Kurung tak seimbang = sintaks tak sah (Audit 916):
                # isi terpotong tak boleh dinilai sama sekali.
                if kedalaman == 0:
                    # Isi substitusi bisa berisi beberapa perintah
                    # (Audit 931): stdin bersama dikonsumsi berurutan,
                    # jadi penulis yang muncul SEBELUM pembaca stdin
                    # mana pun yang berlaku; sesudah pembaca, stdin
                    # sudah terkuras dan penulis berikutnya menerima
                    # EOF. Perintah yang tak membaca stdin (echo dan
                    # kawanannya) tak menguras apa pun. Aturan
                    # redirect-terakhir (Audit 885) berlaku di dalam
                    # satu perintah, bukan melintasi perintah.
                    hasil = False
                    stdin_habis = False
                    lingkar_baca = False
                    lingkar_putus = False
                    loop_lain = 0
                    badan_kuras = False
                    for bagian in pecah_segmen_shell(
                            segmen[awal + 2:k]):
                        kata = bagian.strip().lstrip("({ ").split(
                            None, 1)
                        # Audit 942: kata kunci dibandingkan pada
                        # token MENTAH — kata cadangan berkutip
                        # kehilangan statusnya di bash.
                        kata1 = kata[0] if kata else ""
                        if lingkar_baca:
                            # Audit 941: break di badan mengakhiri
                            # lingkar lebih awal — stdin belum tentu
                            # habis. break/done milik loop bersarang
                            # dihitung lewat kedalamannya; kata kunci
                            # bisa bersembunyi sesudah do/then/else.
                            kunci = kata1
                            if (kunci in ("do", "then", "else")
                                    and len(kata) > 1):
                                kunci = kata[1].split(
                                    None, 1)[0].strip("\"'")
                            if kunci in ("while", "until", "for"):
                                loop_lain += 1
                                continue
                            if kunci == "done":
                                if loop_lain > 0:
                                    loop_lain -= 1
                                    continue
                                lingkar_baca = False
                                stdin_habis = (badan_kuras
                                               or not lingkar_putus)
                                lingkar_putus = False
                                badan_kuras = False
                                continue
                            if kunci == "break" and loop_lain == 0:
                                lingkar_putus = True
                                continue
                            if loop_lain == 0:
                                # Audit 942: segmen badan dinilai
                                # dengan aturan urutan yang sama —
                                # penulis di badan berlaku pada
                                # iterasi pertama, penguras di badan
                                # menghabiskan stdin bagi segmen
                                # berikutnya.
                                efektif = (kata[1] if kata1 in (
                                    "do", "then", "else")
                                    and len(kata) > 1 else bagian)
                                if segmen_redirect_output(efektif):
                                    if (badan_kuras
                                            and _menguras_stdin(
                                                efektif)):
                                        continue
                                    hasil = True
                                    break
                                if _menguras_stdin(efektif):
                                    badan_kuras = True
                            continue
                        if (kata1 in ("while", "until")
                                and re.search(r"\bread\b", bagian)):
                            # Audit 935: lingkar baca menguras stdin
                            # sampai done; segmen badan dinilai di
                            # cabang lingkar_baca di atas.
                            lingkar_baca = True
                            continue
                        efektif = bagian
                        if (kata1 in ("while", "until", "do", "then",
                                      "else") and len(kata) > 1):
                            # Audit 940: perintah sungguhan bersembunyi
                            # di balik kata kunci — kondisi lingkar
                            # atau badan sesudah do/then/else.
                            efektif = kata[1]
                        if segmen_redirect_output(efektif):
                            if stdin_habis and _menguras_stdin(efektif):
                                break
                            hasil = True
                            break
                        if _menguras_stdin(efektif):
                            break
                else:
                    hasil = False
                # Target sudah dikonsumsi lompatan ini — keadaan
                # sesudah-operator harus mati, bukan menyala, agar
                # redirect sah berikutnya tak ditolak penjaga
                # Audit 895 (Audit 917).
                sesudah_op = False
                i = k + 1 if k < n else n
                continue
            if mentah.startswith("("):
                # Target tak boleh subshell (Audit 910): isi kurungnya
                # bukan target berkas — lewati seimbang agar redirect
                # di dalamnya tak bocor menjadi milik perintah ini.
                awal = i + op + (len(segmen[i + op:]) - len(mentah))
                kedalaman = 0
                k = awal
                while k < n:
                    if segmen[k] == "\\" and k + 1 < n:
                        # Pasangan escape adalah literal (Audit 927):
                        # kurung ter-escape bukan kurung sungguhan.
                        k += 2
                        continue
                    if segmen[k] in ("'", '"'):
                        # Kurung di dalam kutip bukan kurung sungguhan
                        # (Audit 922) — lewati rentang berkutip utuh.
                        qk = segmen[k]
                        k += 1
                        while k < n and segmen[k] != qk:
                            if qk == '"' and segmen[k] == "\\":
                                k += 1
                            k += 1
                        k += 1
                        continue
                    if segmen[k] == "(":
                        kedalaman += 1
                    elif segmen[k] == ")":
                        kedalaman -= 1
                        if kedalaman == 0:
                            break
                    k += 1
                hasil = False
                sesudah_op = False
                i = k + 1 if k < n else n
                continue
            # Redirect TERAKHIR yang berlaku bagi shell (Audit 885):
            # jangan kembali pada `>>` pertama — catat nilai yang ini
            # lalu lanjutkan memindai redirect berikutnya.
            hasil = sisa in ('"$GITHUB_OUTPUT"', "$GITHUB_OUTPUT",
                             '"${GITHUB_OUTPUT}"', "${GITHUB_OUTPUT}")
            sesudah_op = True
            i += op
            continue
        i += 1
    return hasil


def _punya_redirect_luar(segmen):
    """True bila segmen memuat operator redirect keluaran di tingkat
    luar (di luar kutip, substitusi, dan kurung). Echo seperti itu
    diatur redirect-nya sendiri — redirect pembungkus (grup atau
    subshell) tak berlaku baginya (Audit 914)."""
    kutip = ""
    dalam = 0
    param = 0
    tikus = False
    i = 0
    n = len(segmen)
    while i < n:
        c = segmen[i]
        if kutip:
            if c == "\\" and kutip == '"' and i + 1 < n:
                i += 2
                continue
            if c == kutip:
                kutip = ""
            i += 1
            continue
        if c == "\\" and i + 1 < n:
            i += 2
            continue
        if c in ("'", '"'):
            kutip = c
            i += 1
            continue
        if c == "`":
            tikus = not tikus
            i += 1
            continue
        if c == "$" and i + 1 < n and segmen[i + 1] == "(":
            dalam += 1
            i += 2
            continue
        if c == "$" and i + 1 < n and segmen[i + 1] == "{" and dalam == 0:
            # Ekspansi parameter juga sebuah kedalaman (Audit 919):
            # `>` di dalam nilai bawaannya bukan operator redirect.
            # Hanya yang di tingkat luar yang dihitung: pasangan
            # buka/tutup di dalam substitusi perintah sudah tertutup
            # kedalaman substitusi itu sendiri (Audit 925).
            param += 1
            i += 2
            continue
        if c == "}" and param > 0 and dalam == 0:
            param -= 1
            i += 1
            continue
        if c in "><" and i + 1 < n and segmen[i + 1] == "(":
            dalam += 1
            i += 2
            continue
        if c == "(" and dalam > 0:
            dalam += 1
            i += 1
            continue
        if c == ")" and dalam > 0:
            dalam -= 1
            i += 1
            continue
        if c == ">" and dalam == 0 and param == 0 and not tikus:
            return True
        i += 1
    return False


def _ada_kurung_argumen(segmen):
    """True bila segmen memuat kurung tutup APA PUN di tingkat luar
    (kata tersendiri atau menempel kata lain). Pada segmen yang
    membuka grup, kurung seperti itu selalu argumen perintah —
    penutup grup sungguhan hanya bisa muncul sebagai segmen sendiri
    sesudah pemisah (Audit 929). Pemindaian sadar konteks yang sama
    dengan Audit 928; kurung penutup ekspansi parameter bukan kurung
    argumen (pasangan Audit 919/925)."""
    kutip = ""
    dalam = 0
    param = 0
    kurung = 0
    tikus = False
    i = 0
    n = len(segmen)
    while i < n:
        c = segmen[i]
        if kutip:
            if c == "\\" and kutip == '"' and i + 1 < n:
                i += 2
                continue
            if c == kutip:
                kutip = ""
            i += 1
            continue
        if c == "\\" and i + 1 < n:
            i += 2
            continue
        if c in ("'", '"'):
            kutip = c
            i += 1
            continue
        if c == "`":
            tikus = not tikus
            i += 1
            continue
        if c in (" ", "\t") and dalam == 0 and not tikus:
            # Audit 936: pasangan ekspansi kurung hanya berlaku di
            # dalam kata yang sama; kurung buka yang tak tertutup
            # sampai batas kata adalah teks literal, bukan pembuka
            # yang bisa menyerap kurung tutup kata berikutnya.
            kurung = 0
            i += 1
            continue
        if c == "$" and i + 1 < n and segmen[i + 1] == "(":
            dalam += 1
            i += 2
            continue
        if c in "><" and i + 1 < n and segmen[i + 1] == "(":
            dalam += 1
            i += 2
            continue
        if c == "(" and dalam > 0:
            dalam += 1
            i += 1
            continue
        if c == ")" and dalam > 0:
            dalam -= 1
            i += 1
            continue
        if (c == "$" and i + 1 < n and segmen[i + 1] == "{"
                and dalam == 0):
            param += 1
            i += 2
            continue
        if c == "}" and param > 0 and dalam == 0:
            param -= 1
            i += 1
            continue
        if c == "{" and dalam == 0 and not tikus:
            # Audit 933: kurung buka argumen (mis. ekspansi kurung
            # {a,b}) berpasangan dengan kurung tutupnya sendiri —
            # tutupnya bukan kurung argumen yatim.
            kurung += 1
            i += 1
            continue
        if c == "}" and dalam == 0 and not tikus:
            if kurung > 0:
                kurung -= 1
                i += 1
                continue
            return True
        i += 1
    return False


def cek_yaml_github_lain(gagal, peringatan):
    # YAML .github di luar workflows/ dulu tak divalidasi gerbang sama
    # sekali (Audit 584): dependabot.yml rusak menghentikan Dependabot
    # tanpa satu pun CI merah.
    if _impor_yaml(gagal) is None:
        return
    data = _muat_yaml_github(os.path.join(".github", "dependabot.yml"),
                             gagal)
    if data is not None:
        updates = data.get("updates")
        if not isinstance(updates, list) or not updates:
            gagal.append("dependabot.yml tanpa daftar updates — "
                         "Dependabot tak akan jalan")
        else:
            for entri in updates:
                if not isinstance(entri, dict):
                    gagal.append("dependabot.yml: entri updates bukan peta")
                    continue
                eco = entri.get("package-ecosystem")
                if eco not in EKOSISTEM_DEPENDABOT:
                    gagal.append("dependabot.yml: package-ecosystem %r "
                                 "tak dikenal — Dependabot berhenti untuk "
                                 "entri itu" % (eco,))
                if not isinstance(entri.get("directory"), str):
                    gagal.append("dependabot.yml: entri %s tanpa directory"
                                 % (eco,))
    data_labeler = _muat_yaml_github(os.path.join(".github", "labeler.yml"),
                                     gagal)
    if data_labeler is not None:
        cek_labeler_glob(data_labeler, gagal)
    folder_actions = os.path.join(AKAR, ".github", "actions")
    if os.path.isdir(folder_actions):
        for nama in sorted(os.listdir(folder_actions)):
            rel = os.path.join(".github", "actions", nama, "action.yml")
            data = _muat_yaml_github(rel, gagal)
            if data is not None and "runs" not in data:
                gagal.append("%s tanpa kunci runs — action tak bisa "
                             "dipakai workflow" % rel)
    rel_action = os.path.join(".github", "actions", "resolve-vw",
                              "action.yml")
    data_action = _muat_yaml_github(rel_action, gagal)
    if data_action is not None:
        keluaran = data_action.get("outputs")
        nama_keluaran = set(keluaran) if isinstance(keluaran, dict) else set()
        if not nama_keluaran:
            gagal.append("%s tanpa outputs — kontrak resolve-vw kosong"
                         % rel_action)
        else:
            id_internal = set()
            runs = data_action.get("runs")
            if isinstance(runs, dict):
                for langkah in runs.get("steps") or []:
                    if isinstance(langkah, dict) and langkah.get("id"):
                        id_internal.add(str(langkah["id"]))
            mentah_action = baca(rel_action)
            # Hanya echo yang benar-benar mengarah ke $GITHUB_OUTPUT
            # yang dihitung (Audit 867): echo log polos bernama sama tak
            # boleh memenuhi kontrak. Redirect wajib menunjuk PERSIS
            # berkas keluaran di akhir baris (Audit 870): substring
            # GITHUB_OUTPUT di mana pun membuat umpan seperti
            # "$GITHUB_OUTPUT.bak" ikut dihitung. Pencocokan per SEGMEN
            # perintah (Audit 872): baris komentar shell dilewati, dan
            # nama hanya diambil dari segmen echo yang redirect-nya
            # persis — echo umpan pada baris majemuk yang sama tak ikut.
            # Pemecahan segmen sadar kutip (Audit 874).
            nama_ditulis = set()
            # Tumpukan pembungkus yang sedang terbuka (Audit 920):
            # tiap entri [jenis, {kandidat}]. Kandidat dicatat ke
            # pembungkus TERDALAM — redirect dialah yang pertama
            # mengatur stdout echo — dan pembuka dicatat dari loop
            # kupas di bawah, sehingga pembungkus yang dibuka di
            # tengah segmen (di belakang pembungkus lain pada segmen
            # yang sama) ikut terlacak; sebelumnya keadaan hanya
            # dinyalakan bila segmen diawali pembungkus terluar.
            tumpuk = []
            for baris_act in mentah_action.splitlines():
                if baris_act.lstrip().startswith("#"):
                    continue
                for segmen in pecah_segmen_shell(
                        _potong_komentar_shell(baris_act)):
                    if segmen.lstrip().startswith("(("):
                        # Segmen perintah aritmetika: isinya ekspresi,
                        # bukan perintah — teks mirip echo di dalamnya
                        # tak boleh dicocokkan sebagai penulisan
                        # (Audit 930).
                        continue
                    # Pembungkus grup `{ ...; }` / subshell `( ... )`
                    # dibuka lebih dulu (Audit 886): penulisan di
                    # dalamnya tetap penulisan sungguhan bagi shell.
                    segmen_cek = segmen.lstrip()
                    bukaan = []
                    while True:
                        if segmen_cek.startswith("(("):
                            # `((` di posisi perintah adalah operator
                            # aritmetika, bukan dua subshell bertumpuk
                            # (Audit 926): lewati tanpa mencatat
                            # pembungkus.
                            segmen_cek = segmen_cek[2:].lstrip()
                            continue
                        if not (segmen_cek[:1] == "("
                                or (segmen_cek[:1] == "{"
                                    and (len(segmen_cek) == 1
                                         or segmen_cek[1] in " \t"))):
                            break
                        # `{` hanya pembuka grup bila berdiri sebagai
                        # kata tersendiri — `{echo` adalah satu kata
                        # perintah, bukan grup (Audit 913); `(` memang
                        # metakarakter.
                        bukaan.append(
                            "kulit" if segmen_cek[:1] == "(" else "grup")
                        segmen_cek = segmen_cek[1:].lstrip()
                    if (segmen.lstrip().startswith("{")
                            and _ada_kurung_argumen(segmen_cek)
                            and segmen_redirect_output(segmen_cek)):
                        # Grup satu baris tanpa `;` sebelum `}` penutup
                        # adalah sintaks tak sah — `}` di sana argumen
                        # biasa, bukan penutup grup (Audit 905). Syarat
                        # posisi apa pun (akhiran, sebelum redirect)
                        # bisa digeser (Audit 924: pipa, kata menempel,
                        # nomor fd — Audit 929), jadi yang dinilai
                        # adalah KEBERADAAN kurung argumen di tingkat
                        # luar pada segmen pembuka itu sendiri.
                        continue
                    segmen_r = segmen.strip()
                    if (tumpuk and segmen_r[:1] in ("}", ")")
                            and ((segmen_r[:1] == "}"
                                  and tumpuk[-1][0] == "grup")
                                 or (segmen_r[:1] == ")"
                                     and tumpuk[-1][0] == "kulit"))):
                        # Redirect tingkat pembungkus (Audit 912/915):
                        # penutup yang membawa redirect ke berkas
                        # keluaran membuat echo kandidat pembungkus
                        # itu sungguh tertulis.
                        sisa = segmen_r[1:].strip()
                        if (sisa and segmen_redirect_output(
                                "echo z=1 " + sisa)):
                            nama_ditulis |= tumpuk[-1][1]
                        tumpuk.pop()
                        continue
                    for jenis in bukaan:
                        tumpuk.append([jenis, set()])
                    m_nama = re.match(
                        r"\s*echo\s+[\"']?([A-Za-z0-9_-]+)=", segmen_cek)
                    if m_nama and segmen_redirect_output(segmen_cek):
                        nama_ditulis.add(m_nama.group(1))
                    elif (m_nama and tumpuk
                          and not _punya_redirect_luar(segmen_cek)):
                        tumpuk[-1][1].add(m_nama.group(1))
            for nama_out, isi_out in keluaran.items():
                nilai = ""
                if isinstance(isi_out, dict):
                    nilai = str(isi_out.get("value", ""))
                for id_ref in re.findall(
                        r"steps\.([A-Za-z0-9_-]+)\.outputs\.", nilai):
                    if id_ref not in id_internal:
                        gagal.append("%s: outputs.%s merujuk steps.%s.* "
                                     "tetapi tak ada langkah ber-id itu di "
                                     "runs.steps action — keluaran akan "
                                     "kosong di runtime (Audit 840)"
                                     % (rel_action, nama_out, id_ref))
                for nama_ref in re.findall(
                        r"steps\.[A-Za-z0-9_-]+\.outputs\.([A-Za-z0-9_-]+)",
                        nilai):
                    if nama_ref not in nama_ditulis:
                        gagal.append("%s: outputs.%s merujuk keluaran "
                                     "langkah %s yang tak pernah ditulis "
                                     "ke $GITHUB_OUTPUT di skrip action — "
                                     "keluaran akan kosong di runtime "
                                     "(Audit 855)"
                                     % (rel_action, nama_out, nama_ref))
            for wf in ("build-apk.yml", "build-binary.yml"):
                rel_wf = os.path.join(".github", "workflows", wf)
                data_wf = _muat_yaml_github(rel_wf, gagal)
                if data_wf is None:
                    continue
                mentah = baca(rel_wf)
                id_pakai = set()
                jobs = data_wf.get("jobs")
                if isinstance(jobs, dict):
                    for job in jobs.values():
                        if not isinstance(job, dict):
                            continue
                        for langkah in job.get("steps") or []:
                            if isinstance(langkah, dict) and \
                                    "actions/resolve-vw" in str(
                                        langkah.get("uses", "")):
                                if langkah.get("id"):
                                    id_pakai.add(str(langkah["id"]))
                for idl in id_pakai:
                    for rujukan in re.findall(
                            r"steps\." + re.escape(idl)
                            + r"\.outputs\.([A-Za-z0-9_-]+)", mentah):
                        if rujukan not in nama_keluaran:
                            gagal.append("%s merujuk outputs.%s yang tak "
                                         "dideklarasikan %s"
                                         % (rel_wf, rujukan, rel_action))
                # Rujukan steps.* hanya sah di dalam JOB yang sama
                # (Audit 856): id dikumpulkan per-job, bukan se-berkas.
                if isinstance(jobs, dict):
                    for nama_job, job in jobs.items():
                        if not isinstance(job, dict):
                            continue
                        id_job = set()
                        for langkah in job.get("steps") or []:
                            if isinstance(langkah, dict) and langkah.get("id"):
                                id_job.add(str(langkah["id"]))
                        teks_job = str(job)
                        for id_rujuk in sorted(set(re.findall(
                                r"steps\.([A-Za-z0-9_-]+)\.outputs\.",
                                teks_job))):
                            if id_rujuk not in id_job:
                                gagal.append("%s job %s merujuk "
                                             "steps.%s.outputs.* tetapi tak "
                                             "ada langkah ber-id %s di job "
                                             "itu — konteks steps hanya "
                                             "berlaku se-job (Audit 836/856)"
                                             % (rel_wf, nama_job, id_rujuk,
                                                id_rujuk))

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
    args = list(args)
    if args and args[0] == "git":
        args = ["git", "-c", "core.quotepath=false"] + args[1:]
    try:
        hasil = subprocess.run(
            args, cwd=AKAR, capture_output=True, text=True,
            encoding="utf-8", errors="replace", timeout=60)
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

_CACHE_BACA = {}

def baca(path):
    # Audit 1450: cache per lari — berkas yang sama
    # sempat dibuka sampai 11x dalam satu lari --semua;
    # isi repo tak berubah di tengah satu proses
    # gerbang, jadi salinan pertama tetap sah.
    if path not in _CACHE_BACA:
        try:
            with open(os.path.join(AKAR, path),
                      encoding="utf-8", errors="replace") as f:
                _CACHE_BACA[path] = f.read()
        except OSError:
            _CACHE_BACA[path] = ""
    return _CACHE_BACA[path]

# __pycache__ dilewati selaras .gitignore dan klon() uji-diri: sisa
# bytecode lokal bukan isi repo dan tak boleh mengubah vonis (Audit 954).
def jalan_repo(rel, ext=(".java",)):
    for akar, dirs, berkas in os.walk(os.path.join(AKAR, rel)):

        dirs[:] = [d for d in dirs if d not in ("build", ".gradle", ".git",
                                                "__pycache__")]
        for nama in berkas:
            if ext is None or nama.endswith(ext):
                yield os.path.relpath(os.path.join(akar, nama), AKAR)

def kupas_komentar_xml(isi):
    return re.sub(r"<!--.*?-->", "", isi, flags=re.S)


_HIMPUN_CACHE = []


def himpun_definisi():
    # Audit 1596: hasil dibangun SEKALI per lari lalu dipakai ulang
    # — dulu cek_acuan_yatim dan cek_definisi_baru masing-masing
    # membangun ulang seluruh peta definisi+acuan resource (0,9 dtk
    # per bangunan; pohon tak berubah di tengah satu lari gerbang).
    if _HIMPUN_CACHE:
        return _HIMPUN_CACHE[0]
    hasil = _himpun_definisi_mentah()
    _HIMPUN_CACHE.append(hasil)
    return hasil


def _himpun_definisi_mentah():
    definisi = {}
    acuan = {}

    def rapikan(jenis, nama):
        return nama.replace(".", "_") if jenis == "style" else nama

    pola_def = re.compile(r'<(style|string|color|dimen|plurals)(?=[\s/>])[^>]*?\bname="([^"]+)"')
    pola_array = re.compile(r'<(string-array|integer-array|array)(?=[\s/>])[^>]*?\bname="([^"]+)"')

    def tambah(kunci, nama):
        definisi.setdefault(kunci, set()).add(rapikan(kunci, nama))

    akar_res = os.path.join(MAIN, "res")
    for path in jalan_repo(os.path.join("app", "src", "main", "res"), ext=(".xml",)):
        isi = kupas_komentar_xml(baca(path))
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
        if path.endswith(".xml"):
            isi = kupas_komentar_xml(isi)
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

IZIN_WAJIB = {
    "android.permission.INTERNET",
    "android.permission.READ_EXTERNAL_STORAGE",
    "android.permission.WRITE_EXTERNAL_STORAGE",
    "android.permission.ACCESS_NETWORK_STATE",
    "android.permission.WAKE_LOCK",
    "android.permission.RECEIVE_BOOT_COMPLETED",
    "android.permission.SCHEDULE_EXACT_ALARM",
    "android.permission.FOREGROUND_SERVICE",
    "android.permission.FOREGROUND_SERVICE_DATA_SYNC",
    "android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS",
    "android.permission.POST_NOTIFICATIONS",
}

def cek_izin_wajib(gagal):
    # Gerbang dulu asimetris (Audit 586): izin BARU diperingatkan, izin
    # yang DIHAPUS tak terlihat — penghapusan tak sengaja (mis. konflik
    # merge) mematikan fitur diam-diam dengan CI tetap hijau.
    try:
        pohon = ET.parse(os.path.join(AKAR,
                                      "app/src/main/AndroidManifest.xml"))
    except (ET.ParseError, OSError):
        return
    atribut = "{http://schemas.android.com/apk/res/android}name"
    ada = set()
    for el in pohon.getroot().iter():
        if el.tag == "uses-permission":
            nama = el.get(atribut)
            if nama:
                ada.add(nama)
    for nama in sorted(IZIN_WAJIB - ada):
        gagal.append("Izin wajib %s hilang dari AndroidManifest.xml — "
                     "fitur yang bergantung padanya mati diam-diam; "
                     "kembalikan, atau hapus dari IZIN_WAJIB di "
                     "verifikasi-patch.py bila memang disengaja" % nama)

def cek_manifest(gagal):
    try:
        pohon = ET.parse(os.path.join(AKAR, "app/src/main/AndroidManifest.xml"))
    except (ET.ParseError, OSError):
        return
    ns = namespace_aplikasi()
    if not ns:
        gagal.append("Namespace aplikasi tak terbaca dari app/build.gradle.kts"
                     " - cek komponen manifest dilewati")
        return
    atribut = "{http://schemas.android.com/apk/res/android}"
    for el in pohon.getroot().iter():
        if el.tag == "activity-alias":
            nama = el.get(atribut + "targetActivity", "")
        elif el.tag in ("activity", "service", "receiver", "provider"):
            nama = el.get(atribut + "name", "")
        else:
            continue
        if not nama:
            continue
        if nama.startswith("."):
            fqcn = ns + nama
        elif "." in nama:
            fqcn = nama
        else:
            fqcn = ns + "." + nama
        berkas = os.path.join(
            MAIN, "java", *fqcn.split(".")) + ".java"
        if not os.path.isfile(berkas):
            gagal.append("Komponen manifest tanpa kelas: %s (hapus %s.java?)"
                         % (nama, fqcn.replace(".", "/")))

def cek_duplikat_definisi(gagal):
    pola = re.compile(r'<(style|string|color|dimen|plurals|string-array|integer-array|array)(?=[\s/>])[^>]*?\bname="([^"]+)"')
    per_folder = {}
    for path in jalan_repo(os.path.join("app", "src", "main", "res"), ext=(".xml",)):
        rata = path.replace(os.sep, "/")
        if "/values" not in rata:
            continue
        folder = rata.rsplit("/", 1)[0]
        hitung = per_folder.setdefault(folder, {})
        for jenis, nama in pola.findall(kupas_komentar_xml(baca(path))):
            kunci = jenis + "/" + nama
            hitung[kunci] = hitung.get(kunci, 0) + 1
    for folder in sorted(per_folder):
        ganda = sorted(k for k, n in per_folder[folder].items() if n > 1)
        if ganda:
            gagal.append("Definisi ganda di folder %s: %s" % (folder, ", ".join(ganda[:5])))

def berkas_dihapus(rentang):
    r = rentang_normal(rentang)
    if r is not None:
        out = jalan("git", "diff", "--name-status", r[0] + r[1] + r[2])
        if out is None:
            return []
        return [x.split(None, 1)[1] for x in out.splitlines()
                if x.startswith("D")]
    hapus = set()
    for args in (("git", "diff", "--name-status"),
                 ("git", "diff", "--name-status", "--cached")):
        out = jalan(*args)
        for x in (out or "").splitlines():
            if x.startswith("D"):
                hapus.add(x.split(None, 1)[1])
    # Penghapusan yang sudah ter-commit lokal tetapi belum ter-push tak
    # terlihat diff working-tree/staged (Audit 763): ikut sertakan rentang
    # commit lokal terhadap upstream (atau origin/main sebagai cadangan).
    for acuan in ("@{upstream}", "origin/main"):
        out = jalan("git", "diff", "--name-status", acuan + "..HEAD")
        if out is not None:
            for x in out.splitlines():
                if x.startswith("D"):
                    hapus.add(x.split(None, 1)[1])
            break
    return sorted(hapus)

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

_KUPAS_CACHE = {}


def kupas_java(isi):
    try:
        return _KUPAS_CACHE[isi]
    except KeyError:
        pass
    hasil = _kupas_java_mentah(isi)
    if len(_KUPAS_CACHE) > 100000:
        _KUPAS_CACHE.clear()
    _KUPAS_CACHE[isi] = hasil
    return hasil


def _kupas_java_mentah(isi):
    petik_ganda = chr(34)
    petik_satu = chr(39)
    garis_miring_balik = chr(92)
    kutip_tiga = petik_ganda * 3
    keluar = []
    i, n = 0, len(isi)
    while i < n:
        # Audit 1597: rentang kode polos disalin sekaligus sampai
        # awal konstruk berikutnya (komentar/literal) — dulu tiap
        # aksara membayar irisan + append sendiri (±835 KB/s).
        # Penanganan konstruknya sendiri tak berubah dari
        # implementasi lama; keluaran dibuktikan byte-identik.
        j_petik2 = isi.find(petik_ganda, i)
        j_petik1 = isi.find(petik_satu, i)
        j_miring = isi.find('/', i)
        j = n
        if 0 <= j_petik2 < j:
            j = j_petik2
        if 0 <= j_petik1 < j:
            j = j_petik1
        if 0 <= j_miring < j:
            j = j_miring
        if j > i:
            if j == j_miring and isi[j:j + 2] not in ('//', '/*'):
                # Garis miring tunggal (bagi/kurung komentar tak
                # jadi): polos — ikut disalin, lanjut sesudahnya.
                keluar.append(isi[i:j + 1])
                i = j + 1
                continue
            keluar.append(isi[i:j])
            i = j
            if i >= n:
                break
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

def kupas_komentar_saja(isi):
    petik_ganda = chr(34)
    petik_satu = chr(39)
    garis_miring_balik = chr(92)
    kutip_tiga = petik_ganda * 3
    keluar = []
    i, n = 0, len(isi)
    while i < n:
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
            j = i + 3
            while j < n:
                if isi[j] == garis_miring_balik:
                    j += 2
                    continue
                if isi[j:j + 3] == kutip_tiga:
                    j += 3
                    break
                j += 1
            keluar.append(isi[i:j])
            i = j
        elif isi[i] in (petik_ganda, petik_satu):
            kutip = isi[i]
            j = i + 1
            while j < n:
                if isi[j] == garis_miring_balik:
                    j += 2
                    continue
                if isi[j] == kutip:
                    j += 1
                    break
                if kutip == petik_ganda and isi[j] == chr(10):
                    break
                j += 1
            keluar.append(isi[i:j])
            i = j
        else:
            keluar.append(isi[i])
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

def cek_literal_rahasia_utuh(gagal, diff, berubah=()):
    # Audit 1571: literal header kunci UTUH pada baris
    # baru adalah jebakan Gitleaks (insiden fixture
    # Audit 1547, run 42529695945 FAILURE): pemindai
    # rahasia CI menandainya sebagai private-key dan
    # menggagalkan rilis, sedangkan sebelumnya tak
    # ada gerbang lokal yang menangkapnya sebelum
    # push. Rakit header dari potongan literal.
    # Audit 1578: pola diselaraskan ke aturan
    # private-key Gitleaks v8 — tak peka huruf, label
    # boleh memuat '-'/'_', dan akhiran ' BLOCK'
    # diterima (blok PGP); bentuk-bentuk itu lolos
    # pola sempit 1571 tetapi kena di CI.
    pola = re.compile(
        r"-----BEGIN[ A-Z0-9_-]{0,100}PRIVATE KEY"
        r"( BLOCK)?-----", re.IGNORECASE)
    for baris in diff.splitlines():
        if baris.startswith("+") and not baris.startswith("+++"):
            if pola.search(baris):
                gagal.append(
                    "Baris baru memuat literal header kunci "
                    "utuh — rakit dari potongan literal "
                    "agar Gitleaks CI tidak menandainya "
                    "sebagai bocoran: "
                    + baris.strip()[:70])
                return
    # Audit 1579: berkas biner tak punya baris diff —
    # blok kunci utuh di dalam berkas biner berformat
    # yang diizinkan (mis. gambar pasif) lolos semua
    # cek diff, padahal Gitleaks memindai isi berkas.
    # Pindai byte mentah berkas berubah yang bukan
    # teks dengan pola biner yang sama.
    pola_b = re.compile(
        rb"-----BEGIN[ A-Z0-9_-]{0,100}PRIVATE KEY"
        rb"( BLOCK)?-----", re.IGNORECASE)
    for path in berubah:
        try:
            if berkas_teks(path):
                continue
            with open(os.path.join(AKAR, path), "rb") as f:
                isi_b = f.read(16 * 1024 * 1024)
        except OSError:
            continue
        if pola_b.search(isi_b):
            gagal.append(
                "Berkas biner memuat blok header kunci "
                "utuh di dalam isinya — Gitleaks CI "
                "memindai isi berkas biner juga: "
                + path)
            return

def cek_definisi_baru(peringatan, diff):
    pola = re.compile(r'\+\s*<(style|string|color|dimen|plurals|string-array|integer-array|array)(?=[\s/>])[^>]*?\bname="([^"]+)"')
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
    def himpun(folder, pola):
        hasil = set()
        relatif = os.path.join("app", "src", "main", "res", folder)
        wadah = os.path.join(AKAR, relatif)
        if os.path.isdir(wadah):
            for nama in sorted(os.listdir(wadah)):
                if nama.endswith(".xml"):
                    isi = kupas_komentar_xml(
                        baca(os.path.join(relatif, nama)))
                    hasil.update(re.findall(pola, isi))
        return hasil

    pola_string = r'<string(?=[\s/>])[^>]*?\bname="([^"]+)"'
    pola_plurals = r'<plurals(?=[\s/>])[^>]*?\bname="([^"]+)"'

    inggris = himpun("values", pola_string)
    indonesia = himpun("values-in", pola_string)
    if not inggris and not indonesia:
        gagal.append("Kedua strings.xml (values + values-in) kosong/hilang "
                     "— cek sinkron terjemahan tak bisa berjalan (Audit 765)")
    else:
        kurang = sorted(inggris - indonesia)
        lebih = sorted(indonesia - inggris)
        if kurang:
            gagal.append("values-in kurang string: " + ", ".join(kurang[:10]))
        if lebih:
            gagal.append("values-in berlebih string: " + ", ".join(lebih[:10]))

    pl_inggris = himpun("values", pola_plurals)
    pl_indonesia = himpun("values-in", pola_plurals)
    # Plurals TIDAK ikut aturan kedua-kosong Audit 765 (diluruskan Audit 772):
    # tak punya plurals sama sekali adalah keadaan sah yang tersinkron,
    # sedangkan berkas hilang sudah tertangkap cabang string di atas karena
    # berkas yang sama memuat string.
    if pl_inggris or pl_indonesia:
        kurang = sorted(pl_inggris - pl_indonesia)
        lebih = sorted(pl_indonesia - pl_inggris)
        if kurang:
            gagal.append("values-in kurang plurals: " + ", ".join(kurang[:10]))
        if lebih:
            gagal.append("values-in berlebih plurals: " + ", ".join(lebih[:10]))

_CACHE_BERKAS_TEKS = {}


def berkas_teks(path):
    # Audit 1633: hasil diperiksa sekali per keadaan berkas —
    # dulu tiap pemanggil (jenis_biner/jenis_pasif/cek literal
    # biner Audit 1579) membaca ulang berkas yang sama bongkahan
    # demi bongkahan di lari yang sama.
    try:
        st = os.stat(os.path.join(AKAR, path))
        kunci = (path, st.st_size, st.st_mtime_ns)
    except OSError:
        kunci = None
    if kunci is not None and kunci in _CACHE_BERKAS_TEKS:
        return _CACHE_BERKAS_TEKS[kunci]
    hasil = _berkas_teks_mentah(path)
    if kunci is not None:
        if len(_CACHE_BERKAS_TEKS) > 4096:
            _CACHE_BERKAS_TEKS.clear()
        _CACHE_BERKAS_TEKS[kunci] = hasil
    return hasil


def _berkas_teks_mentah(path):
    dek = codecs.getincrementaldecoder("utf-8")()
    try:
        with open(os.path.join(AKAR, path), "rb") as f:
            while True:
                contoh = f.read(65536)
                if not contoh:
                    break
                if b"\x00" in contoh:
                    return False
                try:
                    dek.decode(contoh)
                except UnicodeDecodeError:
                    return False
            try:
                dek.decode(b"", True)
            except UnicodeDecodeError:
                return False
    except OSError:
        return False
    return True


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
    if kepala[:2] == b"\x1f\x8b":
        return "gzip"
    return None

BINER_DIIZINKAN = {"gradle/wrapper/gradle-wrapper.jar"}
BATAS_UKURAN_BERKAS = 1024 * 1024

EKSTENSI_PASIF = {"PNG": {".png"}, "JPEG": {".jpg", ".jpeg"}, "GIF": {".gif"},
                  "WebP": {".webp"}, "PDF": {".pdf"}, "ICO": {".ico"}}
TANDA_BUNGKUS_BAHAYA = (b"\x7fELF", b"PK\x03\x04", b"dex\n",
                        b"\xcf\xfa\xed\xfe", b"\xce\xfa\xed\xfe",
                        b"\xca\xfe\xba\xbe")

def jenis_pasif(path):
    try:
        with open(os.path.join(AKAR, path), "rb") as f:
            kepala = f.read(12)
    except OSError:
        return None
    jenis = None
    if kepala.startswith(b"\x89PNG"):
        jenis = "PNG"
    elif kepala[:3] == b"\xff\xd8\xff":
        jenis = "JPEG"
    elif kepala[:4] == b"GIF8":
        jenis = "GIF"
    elif kepala[:4] == b"RIFF" and kepala[8:12] == b"WEBP":
        jenis = "WebP"
    elif kepala[:4] == b"%PDF":
        jenis = "PDF"
    elif kepala[:4] == b"\x00\x00\x01\x00":
        jenis = "ICO"
    if jenis is None:
        return None
    ekst = os.path.splitext(path)[1].lower()
    if ekst not in EKSTENSI_PASIF[jenis]:
        return None
    try:
        with open(os.path.join(AKAR, path), "rb") as f:
            isi = f.read()
    except OSError:
        return None
    for tanda in TANDA_BUNGKUS_BAHAYA:
        if tanda in isi:
            return None
    return jenis

def nilai_target_sdk(tetap):
    tetap = kupas_java(tetap)
    m = re.search(r"targetSdk\s*=\s*([^\n]+)", tetap)
    if not m:
        return None
    rhs = m.group(1).split("//", 1)[0].strip()
    if re.fullmatch(r"\d+", rhs):
        return int(rhs)
    if not re.fullmatch(r"[\d\s+\-*/()%]+", rhs):
        return -1
    try:
        pohon = ast.parse(rhs, mode="eval")
    except SyntaxError:
        return -1

    def hitung(node):
        if isinstance(node, ast.Expression):
            return hitung(node.body)
        if isinstance(node, ast.Constant) and isinstance(node.value, int):
            return node.value
        if isinstance(node, ast.UnaryOp) and isinstance(
                node.op, (ast.UAdd, ast.USub)):
            v = hitung(node.operand)
            return v if isinstance(node.op, ast.UAdd) else -v
        if isinstance(node, ast.BinOp) and isinstance(
                node.op, (ast.Add, ast.Sub, ast.Mult, ast.FloorDiv, ast.Mod)):
            kiri = hitung(node.left)
            kanan = hitung(node.right)
            if isinstance(node.op, ast.Add):
                return kiri + kanan
            if isinstance(node.op, ast.Sub):
                return kiri - kanan
            if isinstance(node.op, ast.Mult):
                return kiri * kanan
            if isinstance(node.op, ast.FloorDiv):
                return kiri // kanan
            return kiri % kanan
        raise ValueError("simpul tak didukung")

    try:
        return hitung(pohon)
    except Exception:
        return -1


def nilai_min_sdk(tetap):
    tetap = kupas_java(tetap)
    m = re.search(r"minSdk\s*=\s*([^\n]+)", tetap)
    if not m:
        return None
    rhs = m.group(1).split("//", 1)[0].strip()
    if re.fullmatch(r"\d+", rhs):
        return int(rhs)
    return -1


def himpun_abi(tetap):
    tetap = kupas_komentar_saja(tetap)
    abi = set()
    pengisi = {"listOf", "setOf", "arrayOf", "mutableListOf", "mutableSetOf",
               "arrayListOf", "hashSetOf", "linkedSetOf", "addAll", "add"}
    for m in re.finditer(r"abiFilters", tetap):
        i = m.end()
        while i < len(tetap):
            while i < len(tetap) and tetap[i] in " \t\r\n,":
                i += 1
            if i < len(tetap) and tetap[i] == '"':
                j = tetap.find('"', i + 1)
                if j < 0:
                    break
                abi.add(tetap[i + 1:j])
                i = j + 1
                continue
            if i < len(tetap) and tetap[i] in "+=.()":
                i += 1
                continue
            j = i
            while j < len(tetap) and (tetap[j].isalpha() or tetap[j] == "_"):
                j += 1
            if j > i and tetap[i:j] in pengisi:
                i = j
                continue
            break
    return abi


def cek_aturan_repo(gagal, berubah):
    tetap = baca("app/build.gradle.kts")
    target = nilai_target_sdk(tetap)
    if target is None:
        gagal.append("targetSdk tak ditemukan di app/build.gradle.kts — "
                     "tanpa baris itu AGP memakai compileSdk sebagai target "
                     "(aturan repo: targetSdk wajib tertulis dan <= 28)")
    elif target == -1:
        gagal.append("targetSdk bukan literal/ekspresi angka sederhana — "
                     "gerbang tak bisa memastikan nilainya <= 28 (aturan repo)")
    elif target is not None and target >= 29:
        gagal.append("targetSdk %s >= 29 memblokir execve binary (aturan repo)" % target)
    minimum = nilai_min_sdk(tetap)
    if minimum is None:
        gagal.append("minSdk tak ditemukan di app/build.gradle.kts "
                     "(aturan repo: minSdk = 21)")
    elif minimum == -1:
        gagal.append("minSdk bukan literal angka sederhana — "
                     "gerbang tak bisa memastikan nilainya 21 (aturan repo)")
    elif minimum != 21:
        gagal.append("minSdk %s != 21 memutus dukungan Android 5 (aturan repo)" % minimum)
    abi = himpun_abi(tetap)
    if "abiFilters" not in kupas_komentar_saja(tetap) or not abi:
        gagal.append("abiFilters tak ditemukan/tak terbaca di app/build.gradle.kts "
                     "(aturan repo: ABI wajib tertulis, armeabi-v7a saja)")
    asing = sorted(a for a in abi if a != "armeabi-v7a")
    if asing:
        gagal.append("ABI tambahan %s membengkakkan APK (aturan: armeabi-v7a saja)" % asing)
    for path in berubah:

        if not os.path.exists(os.path.join(AKAR, path)):
            continue
        rendah = path.lower()
        if rendah.endswith((".apk", ".aab", ".dex", ".jks", ".keystore",
                            ".zip", ".p12", ".pfx", ".so", ".a")):
            gagal.append("Berkas terlarang masuk repo: " + path)
            continue
        nama_dasar = rendah.rsplit("/", 1)[-1]
        if ("/vaultwarden-armeabi" in rendah or "/libgetrandom-shim-" in rendah
                or "keystore" in rendah.split("/")
                or nama_dasar == ".env" or nama_dasar.startswith(".env.")
                or nama_dasar == "vw_version.txt"):
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
        if ukuran > BATAS_UKURAN_BERKAS and not berkas_teks(path):
            gagal.append("Berkas >1 MB tak boleh masuk repo"
                         " (binary/web-vault diunduh CI): " + path)
            continue
        if ukuran > 0 and not berkas_teks(path) and not jenis_pasif(path):
            gagal.append("Berkas biner format tak dikenal tak boleh masuk repo: "
                         + path)

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

_CACHE_BARIS = {}


def _daftar_baris_tanpa_komentar(isi):
    # Audit 953: hasil per isi di-cache — banyak cek memindai berkas
    # yang sama berulang kali dalam satu lari gerbang.
    daftar = _CACHE_BARIS.get(isi)
    if daftar is None:
        daftar = []
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
            if "/" not in baris and '"' not in baris \
                    and "'" not in baris:
                # Jalur cepat: tanpa pemicu komentar/string, hasil
                # kupas_java atas baris ini adalah baris itu sendiri.
                daftar.append((i, baris))
            else:
                daftar.append((i, kupas_java(baris)))
        if len(_CACHE_BARIS) > 1000:
            _CACHE_BARIS.clear()
        _CACHE_BARIS[isi] = daftar
    return daftar


def baris_tanpa_komentar(isi):
    yield from _daftar_baris_tanpa_komentar(isi)

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
        daftar = [b for _, b in baris_tanpa_komentar(baca(path))]
        for i, baris in enumerate(daftar):
            metode = nama_metode_dideklarasikan(baris)
            if metode:
                nama.add(metode)
            if i + 1 < len(daftar):
                gabungan = baris.strip() + " " + daftar[i + 1].strip()
                metode = nama_metode_dideklarasikan(gabungan)
                if metode:
                    nama.add(metode)
                if i + 2 < len(daftar):
                    gabungan3 = gabungan + " " + daftar[i + 2].strip()
                    metode = nama_metode_dideklarasikan(gabungan3)
                    if metode:
                        nama.add(metode)
        _CACHE_DEKLARASI[path] = nama
    return _CACHE_DEKLARASI[path]

_CACHE_SEMUA_DEKLARASI = None

def _semua_nama_terdeklarasi():
    # Audit 1451: gabungan nama terdeklarasi dibangun
    # sekali per lari; sebelumnya tiap nama memicu
    # jalan pohon + pindai ulang (93 nama = 0,85 dtk).
    global _CACHE_SEMUA_DEKLARASI
    if _CACHE_SEMUA_DEKLARASI is None:
        semua = set()
        for path in jalan_repo(os.path.join("app", "src")):
            semua |= nama_terdeklarasi(path)
        _CACHE_SEMUA_DEKLARASI = semua
    return _CACHE_SEMUA_DEKLARASI

def masih_didefinisikan(nama):
    return nama in _semua_nama_terdeklarasi()

_POLA_KANDIDAT_PEMANGGIL = re.compile(r"(\w+)\s*\(|::\s*(\w+)")
_PETA_PEMANGGIL = []


def _peta_pemanggil():
    # Audit 1631: kandidat nama->berkas dibangun SEKALI per lari
    # dari teks ter-kupas seluruh pohon; dulu pemanggil() memindai
    # ulang seluruh pohon untuk tiap nama yang ditanyakan.
    if _PETA_PEMANGGIL:
        return _PETA_PEMANGGIL[0]
    peta = {}
    for path in jalan_repo("app", ext=(".java",)):
        for _no, baris in baris_tanpa_komentar(baca(path)):
            for cocok in _POLA_KANDIDAT_PEMANGGIL.finditer(baris):
                for nama in cocok.groups():
                    if nama:
                        peta.setdefault(nama, [])
                        if path not in peta[nama]:
                            peta[nama].append(path)
    _PETA_PEMANGGIL.append(peta)
    return peta


def pemanggil(nama):
    pola = re.compile(r"\b%s\s*\(|::\s*%s\b"
                      % (re.escape(nama), re.escape(nama)))
    temu = []
    for path in _peta_pemanggil().get(nama, []):
        for i, baris in baris_tanpa_komentar(baca(path)):
            if pola.search(baris):
                temu.append("%s:%d" % (path, i))
                if len(temu) >= 6:
                    return temu
    return temu

def _lompat_template(isi, i):
    """i menunjuk `{` dari `${`; kembalikan indeks tepat sesudah `}`
    pasangannya. String/char di dalam template dilompati sebagai satu
    satuan agar petiknya tak mengganggu hitungan kurung."""
    n = len(isi)
    j = i + 1
    dalam = 1
    while j < n and dalam:
        c = isi[j]
        if c == "/" and j + 1 < n and isi[j + 1] == "/":
            k = isi.find("\n", j)
            j = n if k < 0 else k + 1
            continue
        if c == "/" and j + 1 < n and isi[j + 1] == "*":
            # Komentar blok di dalam template (Audit 880): petik di
            # dalamnya teks komentar, bukan awal string. Komentar
            # blok Kotlin bisa bersarang.
            k = j + 2
            dalam_k = 1
            while k < n and dalam_k:
                if isi[k:k + 2] == "/*":
                    dalam_k += 1
                    k += 2
                    continue
                if isi[k:k + 2] == "*/":
                    dalam_k -= 1
                    k += 2
                    continue
                k += 1
            j = k
            continue
        if c == '"':
            j = _lompat_string(isi, j)
            continue
        if c == "'":
            k = j + 1
            if k < n and isi[k] == "\\":
                k += 6 if isi[k + 1:k + 2] == "u" else 2
            else:
                k += 1
            j = k + 1 if k < n and isi[k] == "'" else j + 1
            continue
        if c == "{":
            dalam += 1
        elif c == "}":
            dalam -= 1
        j += 1
    return j


def _lompat_string(isi, i):
    """Indeks tepat sesudah string "..." yang petik pembukanya di i.
    Sadar escape dan template (Audit 877): `${` membuka template yang
    dilompati utuh — petik di dalamnya (mis. char '"' di dalam
    template) tidak menutup string luar."""
    n = len(isi)
    if isi.startswith('"""', i):
        # Raw string (Audit 883): tanpa escape; ditutup petik tiga
        # berikutnya, dan template di dalamnya tetap berlaku.
        j = i + 3
        while j < n:
            if isi.startswith('"""', j):
                return j + 3
            if isi[j] == "$" and j + 1 < n and isi[j + 1] == "{":
                j = _lompat_template(isi, j + 1)
                continue
            j += 1
        return n
    j = i + 1
    while j < n:
        c = isi[j]
        if c == "\\":
            j += 2
            continue
        if c == '"':
            return j + 1
        if c == "$" and j + 1 < n and isi[j + 1] == "{":
            j = _lompat_template(isi, j + 1)
            continue
        j += 1
    return n


def kupas_komentar_kotlin(isi, kosongkan_string=False):
    """Buang komentar // dan /* */ dengan sadar string (Audit 854):
    cek mentah atas teks Gradle bisa dipenuhi teks komentar (baris mode
    yang dikomentari) atau dikelabui komentar di antara kata kunci dan
    kurung. String "..." (termasuk URL di dalamnya) dipertahankan,
    kecuali mode kosongkan_string (Audit 869): isi string adalah data,
    bukan kode, jadi dikosongkan agar cek substring membaca kode saja.
    Raw string triple-quote selalu dikosongkan (Audit 869). Char
    literal dikenali sebagai satu token (Audit 873) agar petik di
    dalamnya tidak dibaca sebagai awal string."""
    hasil = []
    i = 0
    n = len(isi)
    while i < n:
        c = isi[i]
        if isi.startswith('"""', i):
            j = isi.find('"""', i + 3)
            j = n if j < 0 else j + 3
            hasil.append('""')
            hasil.append("\n" * isi[i:j].count("\n"))
            i = j
        elif c == "'":
            j = i + 1
            if j < n and isi[j] == "\\":
                j += 6 if isi[j + 1:j + 2] == "u" else 2
            else:
                j += 1
            if j < n and isi[j] == "'" and "\n" not in isi[i:j]:
                hasil.append("''" if kosongkan_string else isi[i:j + 1])
                i = j + 1
            else:
                hasil.append(c)
                i += 1
        elif c == '"':
            j = _lompat_string(isi, i)
            hasil.append('""' if kosongkan_string else isi[i:j])
            i = j
        elif c == "/" and i + 1 < n and isi[i + 1] == "/":
            while i < n and isi[i] != "\n":
                i += 1
        elif c == "/" and i + 1 < n and isi[i + 1] == "*":
            # Komentar blok Kotlin RESMI bisa bersarang: hitung kedalaman
            # (Audit 866) — berhenti di */ pertama membuat teks di dalam
            # sarang terbaca sebagai kode oleh cek-cek sesudahnya.
            i += 2
            dalam = 1
            while i < n and dalam:
                if isi[i] == "/" and i + 1 < n and isi[i + 1] == "*":
                    dalam += 1
                    i += 2
                    continue
                if isi[i] == "*" and i + 1 < n and isi[i + 1] == "/":
                    dalam -= 1
                    i += 2
                    continue
                if isi[i] == "\n":
                    hasil.append("\n")
                i += 1
            hasil.append(" ")
        else:
            hasil.append(c)
            i += 1
    return "".join(hasil)


VERSI_AGP_TERPAKU = "8.13.2"
REPO_SETTINGS_BOLEH = ("google()", "mavenCentral()", "gradlePluginPortal()")


def blok_repositories(isi):
    """Semua isi blok `repositories { ... }` (kurung dihitung)."""
    hasil = []
    for m in re.finditer(r"repositories\s*\{", isi):
        i = m.end()
        dalam = 1
        j = i
        while j < len(isi) and dalam:
            if isi[j] == "{":
                dalam += 1
            elif isi[j] == "}":
                dalam -= 1
            j += 1
        hasil.append(isi[i:j - 1])
    return hasil


def _dekode_escape_properties(baris):
    """Dekode escape format Java properties pada satu baris logis:
    escape unicode (huruf u boleh berulang, empat digit heksa),
    escape kontrol tab/newline/CR/FF, dan escape karakter apa pun
    (pemisah, titik dua, spasi, backslash). Format properties
    mendekode ini SEBELUM parsing, jadi penilai harus menilai teks
    yang sama (Audit 879)."""
    hasil = []
    i = 0
    n = len(baris)
    while i < n:
        c = baris[i]
        if c == "\\" and i + 1 < n:
            d = baris[i + 1]
            if d == "u":
                j = i + 2
                while j < n and baris[j] == "u":
                    j += 1
                heks = baris[j:j + 4]
                if len(heks) == 4 and all(
                        ch in "0123456789abcdefABCDEF"
                        for ch in heks):
                    hasil.append(chr(int(heks, 16)))
                    i = j + 4
                    continue
                hasil.append("u")
                i = j
                continue
            hasil.append({"t": "\t", "n": "\n", "r": "\r",
                          "f": "\f"}.get(d, d))
            i += 2
            continue
        hasil.append(c)
        i += 1
    return "".join(hasil)


def _sambung_properties(baris):
    """True bila baris properties berakhir dengan backslash GANJIL
    (sambung-baris format Java properties)."""
    return (len(baris) - len(baris.rstrip("\\"))) % 2 == 1


def cek_gradle_akar(gagal):
    isi = kupas_komentar_kotlin(baca("build.gradle.kts"))
    if not os.path.isfile(os.path.join(AKAR, "build.gradle.kts")):
        gagal.append("build.gradle.kts (akar) hilang — versi plugin AGP "
                     "tak terpaku")
    elif not re.search(r'id\("com\.android\.application"\)\s+version\s+"'
                       + re.escape(VERSI_AGP_TERPAKU)
                       + r'"\s+apply\s+false', isi):
        gagal.append("build.gradle.kts (akar): versi plugin "
                     "com.android.application bukan versi terpaku "
                     + VERSI_AGP_TERPAKU + " + apply false — mengganti versi "
                     "AGP wajib menyelaraskan VERSI_AGP_TERPAKU di "
                     "verifikasi-patch pada commit yang sama (Audit 835)")
    isi = kupas_komentar_kotlin(baca("settings.gradle.kts"),
                                kosongkan_string=True)
    if not os.path.isfile(os.path.join(AKAR, "settings.gradle.kts")):
        gagal.append("settings.gradle.kts hilang — mode repositori tak "
                     "terpaku")
    else:
        if "RepositoriesMode.FAIL_ON_PROJECT_REPOS" not in isi:
            gagal.append("settings.gradle.kts: repositoriesMode bukan "
                         "FAIL_ON_PROJECT_REPOS — proyek bisa menimpa "
                         "repositori yang dipaku settings (rantai pasok)")
        for repo in ("google()", "mavenCentral()"):
            if repo not in isi:
                gagal.append("settings.gradle.kts: repositori %s hilang dari "
                             "daftar yang dipaku" % repo)
        for blok in blok_repositories(isi):
            for entri in re.findall(r"[A-Za-z_][A-Za-z0-9_.]*\s*\([^)]*\)"
                                    r"(?:\s*\{[^}]*\})?", blok):
                entri = entri.strip()
                if entri not in REPO_SETTINGS_BOLEH:
                    gagal.append("settings.gradle.kts: repositori tak "
                                 "diizinkan di blok repositories: %s — hanya "
                                 "google()/mavenCentral()/gradlePluginPortal() "
                                 "yang terpaku (Audit 835)" % entri)
    isi = baca("gradle.properties")
    if not os.path.isfile(os.path.join(AKAR, "gradle.properties")):
        gagal.append("gradle.properties hilang")
    else:
        # Nilai EFEKTIF last-wins (Audit 854): baris duplikat `=true`
        # sesudah `=false` dulu lolos karena regex menemukan baris false
        # di mana pun. Baris komentar (#/!) dilewati. Pemisah diakui
        # seluas format Java properties (Audit 871): `=`, `:`, atau
        # spasi — baris gaya `kunci:nilai` sah bagi Gradle dan ikut
        # menentukan nilai efektif.
        # Gabung baris logis lebih dulu (Audit 876): baris berakhiran
        # backslash ganjil disambung format properties dengan baris
        # berikutnya SEBELUM parsing — menilai baris fisik membuat
        # kunci yang terbelah sebelum pemisahnya tak terbaca.
        baris_logis = []
        for b_fisik in isi.splitlines():
            if baris_logis and _sambung_properties(baris_logis[-1]):
                baris_logis[-1] = (baris_logis[-1][:-1]
                                   + b_fisik.lstrip())
            else:
                baris_logis.append(b_fisik)
        baris_logis = [_dekode_escape_properties(b)
                       for b in baris_logis]
        nilai_ax = None
        for baris in baris_logis:
            b = baris.strip()
            if not b or b.startswith("#") or b.startswith("!"):
                continue
            m = re.match(r"android\.useAndroidX[\s=:]+(\S+)\s*$", b)
            if m:
                nilai_ax = m.group(1)
        if nilai_ax != "false":
            gagal.append("gradle.properties: nilai efektif "
                         "android.useAndroidX bukan false (terbaca %r) — "
                         "app ini Java murni tanpa AndroidX" % nilai_ax)


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
    if not bekas_hapusan():
        return
    baris_tambah = [(path, baris) for path, baris in tambah_java(diff)
                    if not baris.lstrip().startswith(("*", "//", "/*"))]
    for nama in sorted(bekas_hapusan()):
        pola = re.compile(r"\b%s\s*\(|::\s*%s\b"
                          % (re.escape(nama), re.escape(nama)))
        sebelumnya = {}
        for path, baris in baris_tambah:
            kode = kupas_java(baris)
            gabung = sebelumnya.get(path, "").rstrip() + kode.lstrip()
            if pola.search(kode) or pola.search(gabung):
                gagal.append(
                    "Baris baru memanggil %s() yang sudah dihapus: %s"
                    % (nama, baris.strip()[:80]))
                break
            if kode.strip():
                sebelumnya[path] = kode

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
    for path, nama in hunk_fungsi(diff):
        kunci = (path, nama)
        if not nama or kunci in sudah or nama in KATA_KUNCI_JAVA:
            continue
        if nama[:1].isupper():
            continue
        sudah.add(kunci)
        if not disebut_di_uji(nama):
            peringatan.append(
                "Metode %s() diubah tapi tanpa jejak di app/src/test — "
                "tambah/cek uji logika murninya agar salah-fix ketahuan" % nama)

def cek_hapusan_belum_tercatat(peringatan, diff):
    tercatat = bekas_hapusan()
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
        if re.search(r"\.to(Lower|Upper)Case\s*\(\s*\)", kode):
            gagal.append("Baris baru toLower/UpperCase() tanpa Locale — bug locale "
                         "Turki + alokasi sia-sia; pakai varian Locale.US: %s"
                         % kode.strip()[:80])

def teks_xml_bersih(path):
    return re.sub(r"<!--.*?-->", "", baca(path), flags=re.S)

def nama_warna(path):
    return set(re.findall(r'<color\b[^>]*?\bname="([^"]+)"', teks_xml_bersih(path)))

# Audit 1491: warna yang nilainya memang sama persis siang &
# malam — override malamnya sudah dihapus (tak ada beda yang
# dijaga), jadi absennya di values-night BUKAN pelanggaran
# paritas. Warna lain yang absen tetap gagal, dan warna
# malam-saja tetap selalu gagal.
WARNA_MALAM_BOLEH_ABSEN = {
    "primary_dark", "white", "status_on", "hero_start",
    "hero_end", "chip_stopped", "chip_busy", "btn_stop_pressed",
}

def quantities_plurals(path):
    hasil = {}
    for m in re.finditer(r'<plurals\b[^>]*?\bname="([^"]+)"(.*?)</plurals>', teks_xml_bersih(path), re.S):
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
            if siang - malam - WARNA_MALAM_BOLEH_ABSEN:
                gagal.append("colors.xml siang berlebih vs night: "
                             + ", ".join(sorted(
                                 siang - malam - WARNA_MALAM_BOLEH_ABSEN)[:10]))
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
            for nama in ("activity_pin.xml",):
                if not os.path.isfile(os.path.join(tata, nama)):
                    gagal.append("Berkas layout wajib hilang: layout/" + nama)
                if not os.path.isfile(os.path.join(darat_dir, nama)):
                    gagal.append("Berkas layout-land wajib hilang: layout-land/"
                                 + nama + " (varian landscape berkas ini wajib ada)")
            for nama in sorted(os.listdir(tata)):
                if not nama.endswith(".xml"):
                    continue
                darat = os.path.join(darat_dir, nama)
                if not os.path.isfile(darat):
                    continue
                port = set(re.findall(r'@\+id/([A-Za-z0-9_]+)',
                                      teks_xml_bersih("app/src/main/res/layout/" + nama)))
                land = set(re.findall(r'@\+id/([A-Za-z0-9_]+)',
                                      teks_xml_bersih("app/src/main/res/layout-land/" + nama)))
                if port != land:
                    gagal.append("ID layout/%s != layout-land/%s (kurang: %s; lebih: %s)"
                                 % (nama, nama,
                                    ", ".join(sorted(port - land)[:5]) or "-",
                                    ", ".join(sorted(land - port)[:5]) or "-"))
            for nama in sorted(os.listdir(darat_dir)):
                if not nama.endswith(".xml"):
                    continue
                if not os.path.isfile(os.path.join(tata, nama)):
                    gagal.append("Berkas layout-land/%s tanpa pasangan di "
                                 "layout/ — varian yatim tak pernah terbandingkan "
                                 "ID-nya (Audit 764)" % nama)

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
        for taut in re.findall(r"\{@(?:link(?:plain|code)?|see)\s+([\w.#$]+)", mentah):
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

_POLA_KANDIDAT_UJI = re.compile(r"(\w+)\s*\(|(\w+)\s*\.\s*(\w+)\s*\(")
_PETA_UJI = []


def _peta_uji():
    # Audit 1631: pasangan nama->berkas uji dibangun sekali per
    # lari dari isi mentah berkas uji (sumber yang sama dengan yang
    # dulu dipindai ulang per nama oleh disebut_di_uji()).
    if _PETA_UJI:
        return _PETA_UJI[0]
    peta = {}
    for path in jalan_repo(os.path.join("app", "src", "test")):
        for cocok in _POLA_KANDIDAT_UJI.finditer(baca(path)):
            for nama in (cocok.group(1), cocok.group(3)):
                if nama:
                    peta.setdefault(nama, [])
                    if path not in peta[nama]:
                        peta[nama].append(path)
    _PETA_UJI.append(peta)
    return peta


def disebut_di_uji(nama, kelas=None):
    pola = re.compile(r"\b%s\s*\(" % re.escape(nama))
    berkas = _peta_uji().get(nama, [])
    if not kelas:
        for path in berkas:
            if pola.search(baca(path)):
                return True
        return False
    pola_lengkap = re.compile(r"\b%s\s*\.\s*%s\s*\("
                              % (re.escape(kelas), re.escape(nama)))
    for path in berkas:
        isi = baca(path)
        if pola_lengkap.search(isi):
            return True
        if os.path.basename(path) == kelas + "Test.java" and pola.search(isi):
            return True
    return False

def cek_uji_baru(peringatan, diff):
    pola = re.compile(
        r"\s*(?:(?:public|private|protected|static|final|synchronized|"
        r"default)\s+)*[\w<>\[\].,?][\w<>\[\].,? ]*?\s+(\w+)\s*\(")

    def inti(baris):
        return re.sub(r"\s+", " ", kupas_java(baris)).strip()

    lama = set(inti(baris) for _, baris in hapus_java(diff))
    sudah = set()
    sebelumnya = {}
    kelas = {}
    pola_kelas = re.compile(r"\b(?:class|interface|enum)\s+(\w+)")
    for path, baris in tambah_java(diff):
        if "/src/test/" in "/" + path.replace("\\", "/"):
            sebelumnya[path] = baris
            continue
        if path not in kelas:
            dasar = os.path.basename(path)
            kelas[path] = {dasar[:-5]} if dasar.endswith(".java") else set()
        for cocok_kelas in pola_kelas.finditer(baris):
            kelas[path].add(cocok_kelas.group(1))
        cocok = pola.match(baris)
        if cocok and cocok.group(1) in kelas[path]:
            sebelumnya[path] = baris
            continue
        if cocok and cocok.group(1) not in KATA_KUNCI_JAVA:
            nama = cocok.group(1)
            kunci = (path, nama)
            if kunci not in sudah:
                sudah.add(kunci)
                if inti(baris) in lama:
                    sebelumnya[path] = baris
                    continue
                if "@Override" in baris or "@Override" in sebelumnya.get(path, ""):
                    sebelumnya[path] = baris
                    continue
                if inti(baris).endswith(";"):
                    sebelumnya[path] = baris
                    continue
                dasar = os.path.basename(path)
                kelas_utama = dasar[:-5] if dasar.endswith(".java") else None
                if not disebut_di_uji(nama, kelas_utama):
                    peringatan.append(
                        "Metode baru %s() tanpa jejak di app/src/test "
                        "(aturan: uji logika murni baru)" % nama)
        sebelumnya[path] = baris

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
        for j in args.jalur:
            if not os.path.exists(os.path.join(AKAR, j)):
                gagal.append("Jalur diminta tak ada: %s — ejaan/nama berkas "
                             "mungkin keliru atau berkas sudah dihapus "
                             "(cek penghapusan berjalan lewat mode "
                             "diff/rentang, bukan jalur eksplisit)" % j)
        berubah = [j for j in args.jalur if os.path.exists(os.path.join(AKAR, j))]
        diff, ok = jalan_diff("git", "diff", "HEAD", "--", *berubah)
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
        acuan = hulu.strip() if hulu else ""
        if not acuan and jalan("git", "rev-parse", "--verify", "origin/main") is not None:
            acuan = "origin/main"
        if acuan:
            diff, ok = jalan_diff("git", "diff", acuan + "...HEAD")
            diff_gagal = diff_gagal or not ok
            if not args.semua:
                daftar = jalan("git", "diff", "--name-only", acuan + "...HEAD") or ""
                berubah = daftar.splitlines()
        else:
            diff, ok = jalan_diff("git", "diff", "HEAD~1...HEAD")
            diff_gagal = diff_gagal or not ok
            if not args.semua:
                berubah = (jalan("git", "diff", "--name-only", "HEAD~1...HEAD") or "").splitlines()
        # Audit 1632: pada mode --semua daftar berubah kerja ini
        # ditimpa daftar seluruh pohon di bawah — jangan dibayar.
        if not args.semua:
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
    if not args.semua:
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
    cek_izin_wajib(gagal)
    cek_duplikat_definisi(gagal)
    cek_sinkron_terjemah(gagal)
    cek_import_mati(gagal)
    cek_acuan_agents(gagal)
    cek_paritas_resource(gagal)
    cek_daftar_hapusan(gagal)
    cek_workflow_yaml(gagal, peringatan)
    cek_gradle_akar(gagal)
    cek_yaml_github_lain(gagal, peringatan)
    cek_aturan_repo(gagal, berubah)
    cek_kurung_java(gagal, berubah)
    # Daftar hapus = union sumber rentang dan sumber lokal, ter-dedup lewat
    # set: mode lokal murni rentang_cek kosong (sumber lokal Audit 763 sudah
    # lengkap); mode --semua dengan rentang cadangan HEAD~1...HEAD dulu
    # kehilangan penghapusan ter-staged karena berkas_dihapus bercabang
    # rentang (Audit 775); tambahan staged mentah seperti dulu menggandakan
    # laporan (Audit 771) — union set menutup keduanya. Mode --rentang
    # eksplisit (CI) tetap murni rentang.
    if args.rentang:
        hapus = berkas_dihapus(rentang_cek)
    elif rentang_cek:
        hapus = sorted(set(berkas_dihapus(rentang_cek))
                       | set(berkas_dihapus("")))
    else:
        # rentang_cek kosong = sumber lokal itu sendiri; memanggilnya dua
        # kali (bentuk union lama) membuang separuh panggilan git (Audit 777).
        hapus = berkas_dihapus("")
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
        cek_literal_rahasia_utuh(gagal, diff, berubah)
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
