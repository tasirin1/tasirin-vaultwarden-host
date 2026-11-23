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
        path_wf = os.path.join(folder, nama)
        if path_wf in _CACHE_YAML:
            data = _CACHE_YAML[path_wf]
        else:
            try:
                with open(path_wf, encoding="utf-8") as f:
                    data = yaml.safe_load(f)
            except Exception as e:
                gagal.append("YAML workflow %s tak bisa di-parse: %s" % (nama, e))
                continue
            if isinstance(data, dict):
                _CACHE_YAML[path_wf] = data
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

_CACHE_YAML = {}

def _muat_yaml_github(rel, gagal):
    """Baca+parse satu berkas YAML .github; kembalikan datanya atau None."""
    path = os.path.join(AKAR, rel)
    if not os.path.isfile(path):
        gagal.append("Berkas %s hilang — automasi yang mengandalkannya "
                     "berhenti diam-diam" % rel)
        return None
    if path in _CACHE_YAML:
        return _CACHE_YAML[path]
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
    _CACHE_YAML[path] = data
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
        if (c == "&" and dalam == 0 and not tikus
                and (i + 1 >= n or baris[i + 1] != ">")
                and (i == 0 or baris[i - 1] not in (">", "|", "<"))):
            # Audit 1875: '&' tunggal adalah pemisah latar
            # belakang - perintah sesudahnya segmen sendiri.
            # Bukan pemisah: '&>' (saluran ganda), '>&' dan
            # '<&' (duplikasi fd), '|&' (pipa plus stderr).
            segmen.append("".join(kini))
            kini = []
            i += 1
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
                  "cut", "tr", "paste", "fold", "nl", "tac",
                  # Audit 1359: alat akun shadow-utils — tanpa
                  # operand berkas membaca daftar dari stdin.
                  "chpasswd", "newusers",
                  # Audit 1397: chgpasswd — cermin chpasswd
                  # untuk grup; pola yang sama persis.
                  "chgpasswd",
                  # Audit 1376: shell CPAN tanpa argumen
                  # membaca perintah dari stdin.
                  # Audit 1865: cpan dinilai cabangnya
                  # sendiri (mode shell vs pasang).
                  # Audit 1398: pl2pm tanpa operand menerjemahkan
                  # stdin ke stdout (operand berkas menekan).
                  "pl2pm",
                  # Audit 1378: utmpdump tanpa operand
                  # membaca rekaman dari stdin.
                  # Audit 1425-1427/1429: keluarga server
                  # Assuan & pengurai NSS — tanpa operand
                  # membaca protokol/objek dari stdin.
                  "pinentry", "pinentry-curses", "dirmngr",
                  "gpg-connect-agent",
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
                  "split", "strings", "base32",
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
                  # Audit 1833: hck, klon cut - tanpa
                  # berkas atau '-' membaca stdin.
                  "hck",
                  # Audit 1834: hexyl - tanpa FILE
                  # membaca stdin (hexyl(1)).
                  "hexyl",
                  # Audit 1112: pembaca interaktif/kueri — operand
                  # berkas/nama menekan seperti filter biasa.
                  "wall", "c++filt", "nslookup",
                  # Audit 1101: basenc TIDAK di sini — tanpa mode
                  # ia galat dan tak membaca; cabangnya khusus.
                  # Audit 1038: keluarga beroperand skrip/pola —
                  # operand pertamanya bukan berkas (penilaian
                  # posisinya di _menguras_keluarga_skrip).
                  "sed", "awk", "grep", "jq", "yq", "fq",
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


# Audit 2455/2456: pendahulu bernilai PER
# SUBPERINTAH untuk penjaga informasional
# qsv/xsv - gabungan peta bernilai cabang
# dengan USAGE sumber tiap subperintah
# (docopt memakan nilai verbatim; token
# bool/asing subperintah lain tak melindungi).
# Audit 2462: subperintah handler-only
# (count/viz/fixlengths/prompt) kini punya
# entri; Audit 2463/2464: entri bool/asing
# hasil ekstraksi dibersihkan.
_PENDAHULU_QSV_PER_SUB = {
    'apply': frozenset({'--addl-props', '--api-key', '--base-url',
        '--batch', '--cache-dir', '--comparand', '--delimiter',
        '--formatstr', '--jobs', '--max-tokens', '--model',
        '--new-column', '--on-error', '--output', '--prompt',
        '--prompt-file', '--rate-limit', '--rename', '--replacement',
        '--timeout', '--user-agent', '-C', '-R', '-b', '-c', '-d', '-f',
        '-j', '-k', '-m', '-o', '-r', '-t', '-u',}),
    'behead': frozenset({'--output', '-o',}),
    'blake3': frozenset({'--derive-key', '--jobs', '--length',
        '--output', '-j', '-l', '-o',}),
    'cat': frozenset({'--delimiter', '--group', '--group-name',
        '--output', '-N', '-d', '-g', '-o',}),
    'color': frozenset({'--delimiter', '--output', '--title', '-d', '-o',
        '-t',}),
    'count': frozenset({'--delimiter', '-d',}),
    'datefmt': frozenset({'--batch', '--default-tz', '--delimiter',
        '--formatstr', '--input-tz', '--jobs', '--new-column',
        '--output', '--output-tz', '--rename', '--ts-resolution', '-R',
        '-b', '-c', '-d', '-j', '-o', '-r',}),
    'dedup': frozenset({'--delimiter', '--dupes-output', '--jobs',
        '--output', '--select', '-D', '-d', '-j', '-o', '-s',}),
    'denull': frozenset({'--add-vocab', '--delimiter', '--max-distinct',
        '--output', '--select', '--vocab', '-d', '-o', '-s',}),
    'describegpt': frozenset({'--addl-cols-list', '--addl-props',
        '--api-key', '--base-url', '--cache-dir', '--ckan-api',
        '--ckan-token', '--context-file', '--disk-cache-dir',
        '--ds-license', '--ds-source', '--ds-updated',
        '--enum-threshold', '--export-prompt', '--format',
        '--freq-options', '--language', '--markdown-template',
        '--max-tokens', '--model', '--num-examples', '--num-tags',
        '--okf-type', '--output', '--prompt', '--prompt-file',
        '--sample-size', '--score-max-retries', '--score-threshold',
        '--session', '--session-len', '--sql-results', '--stats-options',
        '--tag-vocab', '--timeout', '--tour-audience', '--truncate-str',
        '--user-agent', '-k', '-m', '-o', '-p', '-t', '-u',}),
    'edit': frozenset({'--output', '-o',}),
    'enum': frozenset({'--constant', '--copy', '--delimiter', '--hash',
        '--increment', '--new-column', '--output', '--start', '-c', '-d',
        '-o',}),
    'excel': frozenset({'--cell', '--date-format', '--delimiter',
        '--error-format', '--header-row', '--jobs', '--metadata',
        '--output', '--range', '--sheet', '--table', '-d', '-j', '-o',
        '-s',}),
    'exclude': frozenset({'--delimiter', '--output', '-d', '-o',}),
    'explode': frozenset({'--delimiter', '--output', '--rename', '-d',
        '-o', '-r',}),
    'extdedup': frozenset({'--delimiter', '--dupes-output',
        '--memory-limit', '--select', '--temp-dir', '-D', '-d', '-s',}),
    'extsort': frozenset({'--delimiter', '--jobs', '--memory-limit',
        '--select', '--tmp-dir', '-d', '-j', '-s',}),
    'fetch': frozenset({'--default-encoding', '--delimiter',
        '--disk-cache-dir', '--http-header', '--jaq', '--jaqfile',
        '--max-errors', '--max-retries', '--mem-cache-size',
        '--new-column', '--output', '--rate-limit', '--report',
        '--timeout', '--url-template', '--user-agent', '-H', '-c', '-d',
        '-o',}),
    'fetchpost': frozenset({'--content-type', '--default-encoding',
        '--delimiter', '--disk-cache-dir', '--globals-json',
        '--http-header', '--jaq', '--jaqfile', '--max-errors',
        '--max-retries', '--mem-cache-size', '--new-column', '--output',
        '--payload-tpl', '--rate-limit', '--report', '--timeout',
        '--user-agent', '-H', '-c', '-d', '-j', '-o', '-t',}),
    'fill': frozenset({'--default', '--delimiter', '--groupby',
        '--output', '-d', '-g', '-o', '-v',}),
    'fixedwidth': frozenset({'--output', '--positions', '--widths', '-o',}),
    'fixlengths': frozenset({'--delimiter', '--escape', '--insert',
        '--length', '--output', '--quote', '-d', '-i', '-l', '-o',}),
    'flatten': frozenset({'--condense', '--delimiter',
        '--field-separator', '--separator', '-c', '-d', '-f', '-s',}),
    'fmt': frozenset({'--delimiter', '--escape', '--out-delimiter',
        '--output', '--quote', '-d', '-o', '-t',}),
    'foreach': frozenset({'--delimiter', '--dry-run', '--new-column',
        '-c', '-d',}),
    'frequency': frozenset({'--all-unique-text', '--delimiter',
        '--high-card-pct', '--high-card-threshold', '--jobs', '--limit',
        '--lmt-threshold', '--no-float', '--null-text', '--other-text',
        '--output', '--pct-dec-places', '--rank-strategy', '--select',
        '--sketch-map-size', '--sketch-method', '--stats-filter',
        '--unq-limit', '--weight', '-d', '-j', '-l', '-o', '-r', '-s',
        '-u',}),
    'geocode': frozenset({'--admin1', '--api-key', '--batch',
        '--benchmark', '--cache-dir', '--cache-ttl', '--census-mode',
        '--cities-url', '--country', '--delimiter', '--endpoint',
        '--formatstr', '--invalid-result', '--jobs', '--k_weight',
        '--language', '--languages', '--min-score', '--new-column',
        '--older-than', '--output', '--params', '--rate-limit',
        '--rename', '--timeout', '--vintage', '-b', '-c', '-d', '-f',
        '-j', '-k', '-l', '-o', '-r',}),
    'geoconvert': frozenset({'--delimiter', '--geometry', '--latitude',
        '--longitude', '--max-length', '--output', '-d', '-g', '-l',
        '-o', '-x', '-y',}),
    'headers': frozenset({'--delimiter', '-d',}),
    'implode': frozenset({'--delimiter', '--keys', '--output',
        '--rename', '--value', '-d', '-k', '-o', '-r', '-v',}),
    'input': frozenset({'--comment', '--delimiter', '--encoding-errors',
        '--escape', '--output', '--quote', '--quote-style',
        '--skip-lastlines', '--skip-lines', '-d', '-o',}),
    'join': frozenset({'--delimiter', '--keys-output', '--output', '-d',
        '-o',}),
    'json': frozenset({'--jaq', '--output', '--select', '-o', '-s',}),
    'jsonl': frozenset({'--batch', '--delimiter', '--jobs', '--output',
        '-b', '-d', '-j', '-o',}),
    'lens': frozenset({'--columns', '--delimiter', '--echo-column',
        '--filter', '--find', '--freeze-columns', '--prompt',
        '--wrap-mode', '-P', '-W', '-d', '-f',}),
    'luau': frozenset({'--begin', '--cache-dir', '--ckan-api',
        '--ckan-token', '--delimiter', '--end', '--max-errors',
        '--output', '--timeout', '-B', '-E', '-d', '-o',}),
    'moarstats': frozenset({'--bivariate-batch', '--bivariate-stats',
        '--cardinality-threshold', '--epsilon', '--jobs',
        '--join-inputs', '--join-keys', '--join-type', '--output',
        '--pct-thresholds', '--round', '--stats-options',
        '--xsd-gdate-scan', '-C', '-J', '-K', '-S', '-T', '-e', '-j',
        '-o',}),
    'partition': frozenset({'--delimiter', '--filename', '--limit',
        '--prefix-length', '-d', '-p',}),
    'pragmastat': frozenset({'--compare1', '--compare2', '--delimiter',
        '--jobs', '--misrate', '--output', '--round', '--seed',
        '--select', '--stats-options', '--subsample', '-d', '-j', '-m',
        '-o', '-s',}),
    'profile': frozenset({'--dcat-discovery-timeout', '--delimiter',
        '--describegpt', '--initial-context', '--jobs', '--locate',
        '--output', '--profile', '--spec', '--tag-vocab', '-d', '-j',
        '-o',}),
    'prompt': frozenset({'--base-delay-ms', '--filters', '--msg',
        '--output', '--save-fname', '--workdir', '-F', '-d', '-m', '-o',}),
    'pseudo': frozenset({'--delimiter', '--formatstr', '--increment',
        '--output', '--start', '-d', '-o',}),
    'py': frozenset({'--batch', '--delimiter', '--helper', '--output',
        '-b', '-d', '-f', '-o',}),
    'rename': frozenset({'--delimiter', '--output', '-d', '-o',}),
    'replace': frozenset({'--delimiter', '--dfa-size-limit', '--jobs',
        '--output', '--select', '--size-limit', '-d', '-j', '-o', '-s',}),
    'reverse': frozenset({'--delimiter', '--output', '-d', '-o',}),
    'safenames': frozenset({'--delimiter', '--mode', '--output',
        '--prefix', '--reserved', '-d', '-o',}),
    'sample': frozenset({'--cluster', '--delimiter', '--max-size',
        '--output', '--rng', '--seed', '--sketch-in', '--sketch-out',
        '--stratified', '--systematic', '--timeout', '--timeseries',
        '--ts-adaptive', '--ts-aggregate', '--ts-input-tz',
        '--ts-interval', '--ts-start', '--user-agent', '--varopt',
        '--weighted', '-d', '-o',}),
    'schema': frozenset({'--dates-whitelist', '--delimiter',
        '--enum-threshold', '--jobs', '--output', '--pattern-columns',
        '-d', '-j', '-o',}),
    'scoresql': frozenset({'--delimiter', '--infer-len', '--output',
        '-d', '-o',}),
    'search': frozenset({'--delimiter', '--dfa-size-limit', '--flag',
        '--jobs', '--output', '--preview-match', '--select',
        '--size-limit', '-d', '-f', '-j', '-o', '-s',}),
    'searchset': frozenset({'--delimiter', '--dfa-size-limit', '--flag',
        '--jobs', '--output', '--select', '--size-limit',
        '--unmatched-output', '-d', '-f', '-o', '-s',}),
    'select': frozenset({'--delimiter', '--output', '--seed', '-d', '-o',}),
    'slice': frozenset({'--delimiter', '--end', '--index', '--len',
        '--output', '--start', '-d', '-e', '-i', '-l', '-o', '-s',}),
    'snappy': frozenset({'--jobs', '--output', '--timeout',
        '--user-agent', '-j', '-o',}),
    'sniff': frozenset({'--delimiter', '--quote', '--sample',
        '--save-urlsample', '--timeout', '--user-agent', '-d',}),
    'sort': frozenset({'--delimiter', '--jobs', '--output', '--rng',
        '--seed', '--select', '-d', '-j', '-o', '-s',}),
    'sortcheck': frozenset({'--delimiter', '--select', '-d', '-s',}),
    'split': frozenset({'--chunks', '--delimiter', '--filename',
        '--filter', '--jobs', '--kb-size', '--pad', '--size', '-c', '-d',
        '-j', '-k', '-s',}),
    'sqlp': frozenset({'--compress-level', '--compression',
        '--date-format', '--datetime-format', '--delimiter',
        '--float-precision', '--format', '--infer-len', '--output',
        '--rnull-values', '--time-format', '--wnull-value', '-d', '-o',}),
    'stats': frozenset({'--boolean-patterns', '--cache-threshold',
        '--cardinality-method', '--dates-whitelist', '--delimiter',
        '--jobs', '--mode-cardinality-cap', '--output',
        '--percentile-list', '--quantile-method', '--round', '--select',
        '--weight', '-c', '-d', '-j', '-o', '-s',}),
    'table': frozenset({'--align', '--condense', '--delimiter',
        '--output', '--pad', '--width', '-a', '-c', '-d', '-o', '-p',
        '-w',}),
    'template': frozenset({'--batch', '--cache-dir', '--ckan-api',
        '--ckan-token', '--customfilter-error', '--delimiter',
        '--globals-json', '--jobs', '--outfilename', '--output',
        '--outsubdir-size', '--template', '--template-file', '--timeout',
        '-J', '-b', '-j', '-o', '-t',}),
    'to': frozenset({'--compress-level', '--compression', '--delimiter',
        '--infer-len', '--jobs', '--schema', '--separator',
        '--stats-csv', '--table', '-c', '-d', '-j', '-p', '-s', '-t',}),
    'tojsonl': frozenset({'--batch', '--delimiter', '--jobs', '--output',
        '-b', '-d', '-j', '-o',}),
    'transpose': frozenset({'--delimiter', '--long', '--output',
        '--select', '-d', '-o', '-s',}),
    'validate': frozenset({'--backtrack-limit', '--batch', '--cache-dir',
        '--ckan-api', '--ckan-token', '--delimiter', '--dfa-size-limit',
        '--email-min-subdomains', '--invalid', '--jobs', '--sarif',
        '--sarif-max-results', '--size-limit', '--timeout', '--valid',
        '--valid-output', '-b', '-d', '-j',}),
    'viz': frozenset({'--agg', '--annotation', '--bins', '--box-points',
        '--close', '--cluster', '--color', '--color-scale', '--cols',
        '--dataset-pid', '--delimiter', '--denominator',
        '--denominator-key', '--denominator-unit', '--dictionary',
        '--dictionary-context', '--feature-id-key', '--feature-name-key',
        '--geocode-admin1', '--geocode-country', '--geojson',
        '--grid-cols', '--heatmap-density', '--height',
        '--hierarchy-style', '--high', '--language', '--lat', '--limit',
        '--location-mode', '--locations', '--log-scale', '--lon',
        '--low', '--max-charts', '--ohlc-open', '--output',
        '--preview-threshold', '--projection', '--region-state',
        '--scale', '--series', '--size', '--slider', '--slider-speed',
        '--snap-max-dist', '--source', '--style', '--target', '--text',
        '--theme', '--title', '--tour-audience', '--tour-steps',
        '--value', '--violin', '--width', '--x', '--x-title', '--y',
        '--y-range', '--y-title', '--z', '-d', '-o', '-x', '-y', '-z',}),
    'writestat': frozenset({'--delimiter', '--dictionary', '--format',
        '--output', '--table-name', '-d', '-o',}),
}
_PENDAHULU_XSV_PER_SUB = {
    'cat': frozenset({'--delimiter', '--output', '-d', '-o',}),
    'count': frozenset({'--delimiter', '-d',}),
    'fixlengths': frozenset({'--delimiter', '--length', '--output', '-d',
        '-l', '-o',}),
    'flatten': frozenset({'--condense', '--delimiter', '--separator',
        '-c', '-d', '-s',}),
    'fmt': frozenset({'--delimiter', '--escape', '--out-delimiter',
        '--output', '--quote', '-d', '-o', '-t',}),
    'frequency': frozenset({'--delimiter', '--jobs', '--limit',
        '--output', '--select', '-d', '-j', '-l', '-o', '-s',}),
    'headers': frozenset({'--delimiter', '-d',}),
    'input': frozenset({'--delimiter', '--escape', '--output', '--quote',
        '-d', '-o',}),
    'join': frozenset({'--delimiter', '--output', '-d', '-o',}),
    'partition': frozenset({'--delimiter', '--filename',
        '--prefix-length', '-d', '-p',}),
    'reverse': frozenset({'--delimiter', '--output', '-d', '-o',}),
    'sample': frozenset({'--delimiter', '--output', '--seed', '-d', '-o',}),
    'search': frozenset({'--delimiter', '--output', '--select', '-d',
        '-o', '-s',}),
    'select': frozenset({'--delimiter', '--output', '-d', '-o',}),
    'slice': frozenset({'--delimiter', '--end', '--index', '--len',
        '--output', '--start', '-d', '-e', '-i', '-l', '-o', '-s',}),
    'sort': frozenset({'--delimiter', '--output', '--select', '-d', '-o',
        '-s',}),
    'split': frozenset({'--delimiter', '--filename', '--jobs', '--size',
        '-d', '-j', '-s',}),
    'stats': frozenset({'--delimiter', '--jobs', '--output', '--select',
        '-d', '-j', '-o', '-s',}),
    'table': frozenset({'--condense', '--delimiter', '--output', '--pad',
        '--width', '-c', '-d', '-o', '-p', '-w',}),
}

# Audit 1407: bendera info huruf tunggal per perintah —
# mencetak versi/bantuan lalu keluar tanpa membaca stdin
# (penjaga informasional Audit 1102 hanya mengenal bentuk
# panjang --help/--version).
_INFO_HURUF = {"cpan": {"-v", "-V", "-h"},
               "utmpdump": {"-h"},
               # Audit 2451: pembaca coreutils -
               # -h/-V tak sah (berhenti galat)
               # atau informasional versi.
               "head": {"-h", "-V"},
               "tail": {"-h", "-V"},
               "cat": {"-h", "-V"},
               "wc": {"-h", "-V"},
               "uniq": {"-h", "-V"},
               "cut": {"-h", "-V"},
               "tr": {"-h", "-V"},
               # Audit 1423: pl2pm (Audit 1398) ketinggalan
               # dari tabel ini.
               "pl2pm": {"-v", "-h"}}

_OPSI_BERNILAI = {
    "head": {"-n", "-c", "--lines", "--bytes"},
    "tail": {"-n", "-c", "--lines", "--bytes", "--pid", "--sleep", "-s",
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
           "--memlimit", "--memlimit-compress",
           "--memlimit-decompress",
           # Audit 1172: -F/--format bernilai.
           "-F", "--format", "--block-list",
           "-S", "--suffix"},
    # Audit 1639: opsi bernilai gpgv.
    # Audit 1648: --weak-digest terprobe real menguras;
    # --output/-o & --log-file bernilai menurut man gpgv(1).
    "gpgv": {"--keyring", "--homedir", "--status-fd",
             "--logger-fd", "--weak-digest", "--output", "-o",
             "--log-file", "--debug", "--assert-pubkey-algo"},
    # Audit 1638: opsi bernilai kompresor zstd/pigz.
    "zstd": {"-o", "--output", "-D", "-T", "--threads",
             # Audit 1642: -M/--memory & --format bernilai.
             "-M", "--memory", "--format",
             # Audit 1847: opsi bernilai generasi baru.
             "--output-dir-flat", "--output-dir-mirror",
             "--patch-from"},
    "unzstd": {"-o", "--output", "-D", "-T", "--threads",
               "-M", "--memory", "--format",
               "--output-dir-flat",
               "--output-dir-mirror", "--patch-from"},
    "zstdcat": {"-D", "-T", "--threads",
                "-M", "--memory", "--format", "-o", "--output",
                "--output-dir-flat",
                "--output-dir-mirror", "--patch-from"},
    # Audit 1642: zstdmt varian multithread zstd.
    "zstdmt": {"-o", "--output", "-D", "-T", "--threads",
               "-M", "--memory", "--format",
               "--output-dir-flat",
               "--output-dir-mirror", "--patch-from"},
    "pigz": {"-p", "--processes", "-b", "--blocksize", "-S",
             "--suffix", "-I", "--iterations", "-X", "--index"},
    "unpigz": {"-p", "--processes", "-b", "--blocksize", "-S",
               "--suffix", "-I", "--iterations", "-X", "--index"},
    # Audit 1211: wall selama ini tanpa entri — -t bernilai.
    "wall": {"-t", "--timeout",
            # Audit 1236: -g/--group wall bernilai.
            "-g", "--group"},
    # Audit 1213: kompresor tanpa entri untuk opsi bersamanya.
    "lzma": {"-F", "--format", "-S", "--suffix", "-T", "--threads",
             "--block-size", "-C", "--check",
             "--block-list", "--memlimit",
             "--memlimit-compress",
             "--memlimit-decompress"},
    "xzcat": {"-F", "--format", "-T", "--threads",
              "-C", "--check",
              # Audit 1237: -S/--suffix hilang dari entri ini.
              "-S", "--suffix",
              # Audit 1292: --block-size (entri xz memuatnya).
              "--block-size", "--block-list", "--memlimit",
              "--memlimit-compress",
              "--memlimit-decompress"},
    # Audit 1238: lzcat tanpa entri sama sekali (Audit 1199
    # hanya menambah namanya ke daftar pembaca).
    "lzcat": {"-F", "--format", "-S", "--suffix", "-T",
              "--threads", "--block-size", "-C", "--check",
              "--block-list", "--memlimit",
              "--memlimit-compress",
              "--memlimit-decompress"},
    "zcat": {"-S", "--suffix"},
    # Audit 1801: opsi bernilai column util-linux.
    "column": {"-s", "--separator", "-c", "--columns",
               "-o", "--output-separator"},
    # Audit 1802: gzcat adalah nama varian zcat.
    "gzcat": {"-S", "--suffix"},
    # Audit 1819: opsi bernilai plzip.
    "plzip": {"-n", "--threads", "--dictionary-size", "-s",
              "--match-length", "-m", "-B", "--data-size",
              "-o", "--output"},
    # Audit 1820: opsi bernilai lbzip2.
    "lbzip2": {"-n"},
    # Audit 1821: opsi bernilai pixz.
    "pixz": {"-p", "-q", "-x", "-f", "-o"},
    # Audit 1822: opsi bernilai compress.
    "compress": {"-b"},
    # Audit 1823: opsi bernilai brotli.
    "brotli": {"-q", "--quality", "-o", "--output",
               "-w", "--window", "-S", "--suffix"},
    # Audit 1824: opsi bernilai lzop.
    "lzop": {"-o", "--output", "--suffix"},
    # Audit 1825: opsi bernilai keluarga dos2unix.
    "dos2unix": {"-c", "--convmode"},
    "unix2dos": {"-c", "--convmode"},
    "mac2unix": {"-c", "--convmode"},
    "unix2mac": {"-c", "--convmode"},
    # Audit 1833: opsi bernilai hck.
    "hck": {"-f", "--fields", "-d", "--delimiter"},
    # Audit 1834: opsi bernilai hexyl.
    "hexyl": {"-n", "--length", "-s", "--skip"},
    # Audit 1843: opsi bernilai derdump.
    "derdump": {"-o"},
    # Audit 1844: alias brotli berbagi entri brotli.
    "unbrotli": {"-q", "--quality", "-o", "--output",
                 "-w", "--window", "-S", "--suffix"},
    "brcat": {"-q", "--quality", "-o", "--output",
              "-w", "--window", "-S", "--suffix"},
    # Audit 1845: alias lbzip2 berbagi entri lbzip2.
    "lbzcat": {"-n"},
    "lbunzip2": {"-n"},
    # Audit 1846: opsi bernilai lziprecover.
    "lziprecover": {"-o", "--output"},
    # Audit 1859: opsi bernilai c++filt.
    "c++filt": {"-s", "--format"},
    # Audit 1860: opsi bernilai utmpdump.
    "utmpdump": {"-o", "--output"},
    # Audit 1861: opsi bernilai pinentry.
    "pinentry": {"-o", "--timeout", "-D", "--display",
                 "-T", "--ttyname", "-C", "--lc-ctype",
                 "-M", "--lc-messages"},
    # Audit 1862: pinentry-curses berbagi opsi pinentry.
    "pinentry-curses": {"-o", "--timeout", "-D",
                        "--display", "-T", "--ttyname",
                        "-C", "--lc-ctype", "-M",
                        "--lc-messages"},
    # Audit 1863: opsi bernilai gpg-connect-agent.
    "gpg-connect-agent": {"--homedir", "-S",
                          "--raw-socket", "-T",
                          "--tcp-socket", "-r", "--run"},
    # Audit 1864: opsi bernilai dirmngr.
    "dirmngr": {"--homedir", "--options", "--log-file"},
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
    "newusers": {"-R", "--root", "--prefix", "-c",
                 "--crypt-method", "-s", "--sha-rounds"},
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
    "bunzip2": set(),
    "unxz": {"--suffix", "-S", "-T", "--threads",
             "-C", "--check",
             # Audit 1172: -F/--format bernilai.
             "-F", "--format", "--block-list", "--memlimit",
             "--memlimit-compress",
             "--memlimit-decompress", "--block-size"},
    # Audit 1842: unlzma = xz mode lzma - entri
    # penuh menyamai lzma.
    "unlzma": {"--suffix", "-S", "--block-list",
               "--memlimit", "--memlimit-compress",
               "--memlimit-decompress",
               "-F", "--format", "-T", "--threads",
               "--block-size", "-C", "--check"},
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
    # Audit 1872: doas - pembungkus pewaris stdin sekelas
    # sudo; opsi bernilainya berkas konfigurasi dan pengguna.
    "doas": {"-C", "-u"},
    # Audit 1873: watch - menjalankan anak berulang dengan
    # stdin warisan; opsi bernilainya selang antarlari.
    "watch": {"-n", "--interval"},
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
    # perintah operand), runuser (resolver khusus Audit 2715).
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
                 # Audit 2639: "--pattern" dibuang -
                 # grep tak punya opsi itu (biner mati
                 # "unrecognized option"); pembawa pola
                 # yang asli --regexp/--regex di bawah.
                 "--file", "--max-count",
                 # Audit 2459: ejaan pola grep -
                 # --regexp penuh dan singkatan
                 # getopt --regex sama-sama pembawa
                 # pola di handler keluarga.
                 "--regexp", "--regex",
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
        # Audit 1827: opsi bernilai yq (keluarga skrip).
        "yq": {"-p", "--input-format",
               "-o", "--output-format", "--indent",
               # Audit 1851: -f dicatat agar penjaga
               # cerminnya terjangkau.
               "-f", "--from-file",
               # Audit 2480: dilengkapi ke yq --help
               # v4.44.5 penuh (10 opsi bernilai +
               # -I + --front-matter panjang).
               "-I", "--front-matter",
               "--csv-separator", "-s", "--split-exp",
               "--split-exp-file", "--lua-prefix",
               "--lua-suffix",
               "--properties-separator",
               "--xml-attribute-prefix",
               "--xml-content-name",
               "--xml-directive-name",
               "--xml-proc-inst-prefix"},
        # Audit 1828: opsi bernilai fq (meniru jq).
        # Audit 2472/2473: diselaraskan ke fq
        # --help 0.15.1 asli - hantu jq dibuang
        # (--rawfile/--slurpfile/--library-path),
        # opsi asli ditambah (--raw-file dan
        # --argdecode dua token; --include-path
        # bentuk panjang -L; -d/--decode dan
        # -o/--option satu token).
        "fq": {"-L", "--include-path", "-f", "--from-file",
               "--arg", "--argjson", "--argdecode",
               "--raw-file", "-d", "--decode", "-o",
               "--option"},
    }[perintah]
    if perintah == "awk":
        # Audit 2090: operand pertama awk adalah
        # TEKS PROGRAM - '-' di sana adalah teks
        # program, bukan stdin (terprobe mawk:
        # keluar tanpa menguras).
        ops_awk9 = _operand_sesudah_opsi(toks, awal, nilai)
        if ops_awk9 and ops_awk9[0] == "-":
            return False
    if nama_asli == "rg":
        # Audit 2654: pembawa nilai khas rg -
        # penelusuran utama dulu hanya memakai
        # himpunan grep, jadi nilai pembawa rg
        # terbaca sebagai pola dan pola asli
        # sebagai berkas (vonis mati palsu,
        # fail-open). Himpunan rg selama ini
        # hanya hidup di penelusuran kematian
        # 2619. Bentuk = dan pola dari -e/
        # --regexp tidak berubah.
        nilai = nilai | {
            "--glob", "--iglob", "--pre", "--pre-glob",
            "--encoding", "--type", "--type-add",
            "--engine", "--colors",
            "--context-separator",
            "--field-match-separator",
            "--field-context-separator",
            "--path-separator", "--replace",
            "--sort", "--sortr", "--max-columns",
            "--max-depth", "--threads",
            "--max-filesize",
            # Audit 2669: union 2654 ternyata
            # menghimpun daftar tangan, bukan
            # daftar tuntas - 3 pendek dan 8
            # panjang pembawa tertinggal
            # (terekstrak dari --help rg 14.1.0
            # dan dicocokkan programatis:
            # himpunan help minus yang sudah
            # ada = tepat daftar ini + --generate
            # yang informasional).
            "--type-not", "--type-clear",
            "--ignore-file", "--dfa-size-limit",
            "--regex-size-limit", "--color",
            "--hostname-bin", "--hyperlink-format",
            "-g", "-r", "-M", "-j",
            "-t", "-T", "-E"}
    if nama_asli in ("gawk", "pgawk", "igawk"):
        # Audit 1640: opsi bernilai khas GNU awk — tidak
        # ditambahkan ke himpunan awk bersama karena awk
        # POSIX yang tak mengenalnya akan galat dan tak
        # membaca stdin sama sekali.
        nilai = nilai | {"-i", "--include", "-l", "--load"}
    if perintah == "grep":
        # Audit 2619: penerima numerik keluarga grep/rg -
        # nilai yang bukan angka membunuh alat di parser
        # sebelum stdin dibaca (rg: "value is not a valid
        # number"; grep: "invalid max count"/"invalid
        # context length"; --max-filesize rg: "invalid
        # size" - digit + sufiks huruf besar tunggal
        # K/M/G). Terverifikasi biner rg 14.1.0 dan
        # grep 3.11. Cermin model kematian penerima
        # 2573/2574 di cabang tsv-summarize. Penyusuran
        # sadar nilai: pembawa string keluarga (himpunan
        # nilai + pembawa khas rg) menelan nilainya
        # tanpa validasi angka.
        if nama_asli == "rg":
            num_panjang_19 = {"after-context",
                              "before-context", "context",
                              "max-count", "max-columns",
                              "max-depth", "threads",
                              "max-filesize",
                              # Audit 2669: dua penerima
                              # ukuran lain dari rg -
                              # parser ukurannya sama
                              # dengan max-filesize
                              # (biner: "invalid size").
                              "dfa-size-limit",
                              "regex-size-limit"}
            num_pendek_19 = set("ABCmMdj")
            lewati_panjang_19 = {
                "--" + x for x in num_panjang_19}
            lewati_panjang_19 |= {
                "--regexp", "--replace", "--glob",
                "--iglob", "--pre", "--pre-glob",
                "--field-match-separator",
                "--field-context-separator",
                "--context-separator", "--colors",
                "--path-separator", "--type-add",
                "--type", "--engine", "--encoding",
                "--file", "--label", "--exclude",
                "--include", "--exclude-dir",
                "--exclude-from", "--group-separator",
                "--binary-files", "--devices",
                "--directories",
                # Audit 2669: pembawa string baru
                # dari union yang dilengkapi -
                # nilainya wajib dilewati di sini
                # juga agar tak terbaca sebagai
                # token perintah.
                "--type-not", "--type-clear",
                "--ignore-file", "--color",
                "--hostname-bin",
                "--hyperlink-format"}
            # Audit 2669: t/T/E pembawa string
            # baru (-t tipe, -T tipe-not, -E
            # encoding) ikut dilewati nilainya.
            lewati_pendek_19 = set("efrgABCdmDtTE")
            # Audit 2688: tipe bawaan rg 14.1.0
            # (ekstrak `rg --type-list`, 203
            # nama) - nama tipe asing membunuh
            # rg ("unrecognized file type").
            tipe_rg_88 = frozenset({
                "ada", "agda", "aidl", "alire", "amake", "asciidoc", "asm",
                "asp", "ats", "avro", "awk", "bat", "batch", "bazel",
                "bitbake", "brotli", "buildstream", "bzip2", "c", "cabal",
                "candid", "carp", "cbor", "ceylon", "clojure", "cmake",
                "cmd", "cml", "coffeescript", "config", "coq", "cpp",
                "creole", "crystal", "cs", "csharp", "cshtml", "csproj",
                "css", "csv", "cuda", "cython", "d", "dart", "devicetree",
                "dhall", "diff", "dita", "docker", "dockercompose", "dts",
                "dvc", "ebuild", "edn", "elisp", "elixir", "elm", "erb",
                "erlang", "fennel", "fidl", "fish", "flatbuffers",
                "fortran", "fsharp", "fut", "gap", "gn", "go", "gprbuild",
                "gradle", "graphql", "groovy", "gzip", "h", "haml", "hare",
                "haskell", "hbs", "hs", "html", "hy", "idris", "janet",
                "java", "jinja", "jl", "js", "json", "jsonl", "julia",
                "jupyter", "k", "kotlin", "lean", "less", "license",
                "lilypond", "lisp", "lock", "log", "lua", "lz4", "lzma",
                "m4", "make", "mako", "man", "markdown", "matlab", "md",
                "meson", "minified", "mint", "mk", "ml", "motoko",
                "msbuild", "nim", "nix", "objc", "objcpp", "ocaml", "org",
                "pants", "pascal", "pdf", "perl", "php", "po", "pod",
                "postscript", "prolog", "protobuf", "ps", "puppet", "purs",
                "py", "python", "qmake", "qml", "r", "racket", "raku",
                "rdoc", "readme", "reasonml", "red", "rescript", "robot",
                "rst", "ruby", "rust", "sass", "scala", "sh", "slim",
                "smarty", "sml", "solidity", "soy", "spark", "spec", "sql",
                "stylus", "sv", "svg", "swift", "swig", "systemd",
                "taskpaper", "tcl", "tex", "texinfo", "textile", "tf",
                "thrift", "toml", "ts", "twig", "txt", "typescript",
                "typoscript", "usd", "v", "vala", "vb", "vcl", "verilog",
                "vhdl", "vim", "vimscript", "webidl", "wiki", "xml", "xz",
                "yacc", "yaml", "yang", "z", "zig", "zsh", "zstd"})
            # Audit 2688: definisi --type-add
            # berlaku dari posisi mana pun di
            # perintah yang sama, jadi kumpulkan
            # dulu dari seluruh token.
            # Audit 2708: pengumpulan kini
            # sadar posisi seperti pindaian
            # 2689 - nilai pembawa dilompati,
            # jadi --type-add yang berposisi
            # sebagai nilai (-e/-r/dsb.) tak
            # lagi terbaca sebagai definisi.
            defs_88 = []
            pembawa_88 = (
                lewati_panjang_19
                | {"--" + x for x in num_panjang_19}
                | {"--sort", "--sortr"})
            j_88 = awal + 1
            while j_88 < len(toks):
                t_88 = toks[j_88].strip("\"'")
                if t_88 == "--":
                    break
                if (t_88 in (">", ">>", "1>", "2>", "1>>",
                             "2>>")
                        or re.fullmatch(r"\d*>>?", t_88)):
                    j_88 += 2
                    continue
                if re.match(r"\d*>>?\S", t_88):
                    j_88 += 1
                    continue
                if (t_88 == "--type-add"
                        and j_88 + 1 < len(toks)):
                    defs_88.append(
                        toks[j_88 + 1].strip("\"'"))
                    j_88 += 2
                    continue
                if t_88.startswith("--type-add="):
                    defs_88.append(t_88[11:])
                    j_88 += 1
                    continue
                if t_88.startswith("--"):
                    nama_88 = t_88[2:].partition("=")[0]
                    if (("--" + nama_88) in pembawa_88
                            and "=" not in t_88):
                        j_88 += 1
                    j_88 += 1
                    continue
                if t_88.startswith("-") and t_88 != "-":
                    huruf_88 = t_88[1:]
                    for pos_88, ch_88 in enumerate(
                            huruf_88):
                        if (ch_88 in lewati_pendek_19
                                or ch_88 in num_pendek_19):
                            if (pos_88
                                    == len(huruf_88) - 1):
                                j_88 += 1
                            break
                    j_88 += 1
                    continue
                j_88 += 1
            tipe_tambahan_88 = set()
            for d_88 in defs_88:
                if d_88.split(":", 1)[0]:
                    tipe_tambahan_88.add(
                        d_88.split(":", 1)[0])
            # Audit 2687: penerima enum rg -
            # nilai di luar himpunan membunuh
            # rg saat parse (rc=2 "choice is
            # unrecognized"); --colors tata
            # bahasanya sendiri (_colors_rg_sah).
            enum_panjang_19 = {
                "color": {"always", "auto", "never", "ansi"},
                "engine": {"default", "pcre2", "auto"},
                "sort": {"none", "path", "modified",
                         "accessed", "created"},
                "sortr": {"none", "path", "modified",
                          "accessed", "created"}}
        else:
            num_panjang_19 = {"after-context",
                              "before-context", "context",
                              "max-count"}
            num_pendek_19 = set("ABCm")
            lewati_panjang_19 = {
                "--" + x for x in (
                    "file", "max-count",
                    "regexp", "regex", "after-context",
                    "before-context", "context",
                    "devices", "directories",
                    "exclude-from", "exclude", "include",
                    "exclude-dir", "label", "binary-files",
                    "group-separator")}
            lewati_pendek_19 = set("efmABCdD")
            # Audit 2687: penerima enum grep -
            # nilai asing membunuh grep saat
            # parse ("unknown binary-files
            # type"/"unknown devices method"/
            # "invalid argument"); --color/
            # --colour nilainya hanya lewat =
            # (token terpisah adalah pola).
            enum_panjang_19 = {
                "color": {"always", "never", "auto"},
                "colour": {"always", "never", "auto"},
                "binary-files": {"binary", "text",
                                 "without-match"},
                "devices": {"read", "skip"},
                "directories": {"read", "skip", "recurse"}}
        # Audit 2625: kosakata panjang grep penuh
        # (ekstrak --help grep 3.11, sama seperti
        # _VOKAB_SK) untuk resolusi singkatan
        # getopt_long di penyusuran bawah.
        _VOCAB_GREP_25 = frozenset({
            "after-context", "basic-regexp",
            "before-context", "binary",
            "binary-files", "byte-offset", "color",
            "colour", "context", "count",
            "dereference-recursive", "devices",
            "directories", "exclude", "exclude-dir",
            "exclude-from", "extended-regexp",
            "file", "files-with-matches",
            "files-without-match", "fixed-strings",
            "group-separator", "help", "ignore-case",
            "include", "initial-tab", "invert-match",
            "label", "line-buffered", "line-number",
            "line-regexp", "max-count", "no-filename",
            "no-group-separator", "no-ignore-case",
            "no-messages", "null", "null-data",
            "only-matching", "perl-regexp", "quiet",
            "recursive", "regexp", "silent", "text",
            "version", "with-filename",
            "word-regexp"})

        def _angka19(nama19, nilai19):
            if nama19 in ("max-filesize",
                          # Audit 2669: parser ukuran
                          # rg yang sama.
                          "dfa-size-limit",
                          "regex-size-limit"):
                return re.fullmatch(
                    r"\d+[KMG]?", nilai19) is not None
            return _uint_getopt_sah(nilai19)

        def _colors_rg_sah(v19):
            # Audit 2687: tata bahasa --colors
            # rg dari pesan galat biner sendiri:
            # <tipe>:<spec>:<nilai>, tipe =
            # path/line/column/match; spec =
            # fg/bg/style/none; warna = 8 nama
            # atau angka ANSI 0-255; style =
            # 6 kata; spec none mengabaikan
            # segmen ketiga; bentuk dua segmen
            # hanya sah bila segmen kedua none.
            b19 = v19.split(":")
            tipe19 = {"path", "line", "column", "match"}
            if len(b19) == 2:
                return (b19[0] in tipe19
                        and b19[1] == "none")
            if len(b19) != 3 or b19[0] not in tipe19:
                return False
            if b19[1] == "none":
                return True
            if b19[1] in ("fg", "bg"):
                if b19[2] in {"black", "blue", "green",
                              "red", "cyan", "magenta",
                              "yellow", "white"}:
                    return True
                return (b19[2].isdigit()
                        and int(b19[2]) <= 255)
            if b19[1] == "style":
                return b19[2] in {"nobold", "bold",
                                  "nointense", "intense",
                                  "nounderline", "underline"}
            return False

        def _enum19(nama19, nilai19):
            if nama19 == "colors":
                return _colors_rg_sah(nilai19)
            return nilai19 in enum_panjang_19[nama19]

        if nama_asli == "rg":
            # Audit 2688: definisi --type-add
            # yang rusak membunuh rg juga -
            # tanpa titik dua / nama kosong /
            # sasaran include tak dikenal.
            # Audit 2709: sasaran include
            # peka urutan - biner hanya
            # menerima tipe bawaan atau yang
            # didefinisikan --type-add
            # SEBELUMNYA; nilai dengan
            # himpunan berjalan sesuai urutan
            # definisi (nama tipe untuk -t
            # tetap tak peka posisi).
            dikenal_88 = set(tipe_rg_88)
            for d_88 in defs_88:
                if (":" not in d_88
                        or not d_88.split(":", 1)[0]):
                    return False
                if ":include:" in d_88:
                    for t88 in d_88.split(
                            ":include:", 1)[1].split(","):
                        if t88 not in dikenal_88:
                            return False
                dikenal_88.add(
                    d_88.split(":", 1)[0])

        def _tipe_rg_sah(v88):
            return (v88 in tipe_rg_88
                    or v88 in tipe_tambahan_88)
        i_19 = awal + 1
        while i_19 < len(toks):
            t_19 = toks[i_19].strip("\"'")
            if t_19 == "--":
                break
            if (t_19 in (">", ">>", "1>", "2>", "1>>",
                         "2>>")
                    or re.fullmatch(r"\d*>>?", t_19)):
                i_19 += 2
                continue
            if re.match(r"\d*>>?\S", t_19):
                i_19 += 1
                continue
            if t_19.startswith("--"):
                nama_19, eq_19, isi_19 = (
                    t_19[2:].partition("="))
                if (nama_asli != "rg" and nama_19
                        and nama_19 not in num_panjang_19
                        and ("--" + nama_19)
                        not in lewati_panjang_19):
                    # Audit 2625: getopt_long GNU
                    # menerima singkatan unik opsi
                    # panjang (grep --max-c adalah
                    # --max-count); singkatan ambigu
                    # membunuh alat. rg (clap) tidak
                    # menyimpulkan awalan.
                    # Audit 2638: kecocokan EKSak
                    # selalu menang di getopt_long -
                    # nama yang sendiri ada di
                    # kosakata bukan singkatan ambigu
                    # (--null vs --null-data, --binary
                    # vs --binary-files, keduanya opsi
                    # eksak grep yang sah).
                    if nama_19 in _VOCAB_GREP_25:
                        calon_25 = [nama_19]
                    else:
                        calon_25 = [x for x in _VOCAB_GREP_25
                                    if x.startswith(nama_19)]
                    if len(calon_25) > 1:
                        return False
                    if len(calon_25) == 1:
                        nama_19 = calon_25[0]
                if nama_19 in num_panjang_19:
                    if eq_19:
                        if not _angka19(nama_19, isi_19):
                            return False
                    elif i_19 + 1 < len(toks):
                        if not _angka19(
                                nama_19,
                                toks[i_19 + 1].strip("\"'")):
                            return False
                        i_19 += 1
                elif (nama_19 in enum_panjang_19
                        or (nama_asli == "rg"
                            and nama_19 == "colors")):
                    # Audit 2687: validasi nilai
                    # enum; grep --color/--colour
                    # hanya divalidasi bentuk =
                    # (bentuk terpisah tak menelan
                    # token berikutnya).
                    if eq_19:
                        if not _enum19(nama_19, isi_19):
                            return False
                    elif (nama_asli == "rg"
                            or nama_19 not in ("color",
                                               "colour")):
                        if i_19 + 1 < len(toks):
                            if not _enum19(
                                    nama_19,
                                    toks[i_19 + 1].strip(
                                        "\"'")):
                                return False
                            i_19 += 1
                elif (nama_asli == "rg"
                        and nama_19 in ("type",
                                        "type-not")):
                    # Audit 2688: nama tipe
                    # divalidasi terhadap tipe
                    # bawaan + definisi --type-add
                    # perintah yang sama.
                    # --type-clear sengaja TIDAK
                    # divalidasi (biner menerima
                    # nama asing di sana).
                    if eq_19:
                        if not _tipe_rg_sah(isi_19):
                            return False
                    elif i_19 + 1 < len(toks):
                        if not _tipe_rg_sah(
                                toks[i_19 + 1].strip(
                                    "\"'")):
                            return False
                        i_19 += 1
                elif ("--" + nama_19) in lewati_panjang_19 \
                        and not eq_19:
                    i_19 += 1
                i_19 += 1
                continue
            if t_19.startswith("-") and t_19 != "-":
                huruf_19 = t_19[1:]
                for pos_19, ch_19 in enumerate(huruf_19):
                    if ch_19 in num_pendek_19:
                        ekor_19 = huruf_19[pos_19 + 1:]
                        # rg (clap) membuang "=" di depan
                        # nilai pendek (-m=5 sah); grep
                        # memperlakukan "=5" sebagai nilai
                        # (mati) - terverifikasi biner.
                        if (nama_asli == "rg"
                                and ekor_19.startswith("=")):
                            ekor_19 = ekor_19[1:]
                        if ekor_19:
                            if not _angka19("", ekor_19):
                                return False
                        elif i_19 + 1 < len(toks):
                            if not _angka19(
                                    "",
                                    toks[i_19 + 1].strip(
                                        "\"'")):
                                return False
                            i_19 += 1
                        break
                    if (nama_asli == "rg"
                            and ch_19 in "tT"):
                        # Audit 2688: nilai tipe
                        # pendek - nempel (sisa
                        # gugus; "=" dibuang ala
                        # clap) atau token berikut.
                        ekor_t88 = huruf_19[pos_19 + 1:]
                        if ekor_t88.startswith("="):
                            ekor_t88 = ekor_t88[1:]
                        if ekor_t88:
                            if not _tipe_rg_sah(ekor_t88):
                                return False
                        elif i_19 + 1 < len(toks):
                            if not _tipe_rg_sah(
                                    toks[i_19 + 1].strip(
                                        "\"'")):
                                return False
                            i_19 += 1
                        break
                    if ch_19 in lewati_pendek_19:
                        if (pos_19 == len(huruf_19) - 1
                                and i_19 + 1 < len(toks)):
                            i_19 += 1
                        break
                i_19 += 1
                continue
            i_19 += 1
    if nama_asli == "rg":
        # Audit 2689: mode informasional rg
        # (--files Audit 1095 / --generate
        # Audit 2669) hanya berlaku bila
        # tokennya berposisi sebagai OPSI.
        # Pindaian lama mencocokkan token di
        # posisi mana pun, sehingga nilai
        # pembawa dan pola sesudah -- yang
        # kebetulan bernama sama ikut
        # dinyatakan informasional dan rg
        # yang menguras lolos. Kini token
        # ditelusuri sadar posisi memakai
        # himpunan pembawa penyusuran 2619:
        # nilai pembawa dilompati, berhenti
        # di --.
        pembawa_panjang_89 = (
            lewati_panjang_19
            | {"--" + x for x in enum_panjang_19}
            | {"--" + x for x in num_panjang_19}
            | {"--type", "--type-not", "--colors"})
        i_89 = awal + 1
        while i_89 < len(toks):
            t_89 = toks[i_89].strip("\"'")
            if t_89 == "--":
                break
            if (t_89 in (">", ">>", "1>", "2>", "1>>",
                         "2>>")
                    or re.fullmatch(r"\d*>>?", t_89)):
                i_89 += 2
                continue
            if re.match(r"\d*>>?\S", t_89):
                i_89 += 1
                continue
            if (t_89 == "--files" or t_89 == "--generate"
                    or t_89.startswith("--generate=")):
                return False
            if t_89.startswith("--"):
                nama_89 = t_89[2:].partition("=")[0]
                if (("--" + nama_89) in pembawa_panjang_89
                        and "=" not in t_89):
                    i_89 += 1
                i_89 += 1
                continue
            if t_89.startswith("-") and t_89 != "-":
                huruf_89 = t_89[1:]
                for pos_89, ch_89 in enumerate(huruf_89):
                    if (ch_89 in lewati_pendek_19
                            or ch_89 in num_pendek_19):
                        if pos_89 == len(huruf_89) - 1:
                            i_89 += 1
                        break
                i_89 += 1
                continue
            i_89 += 1
    nilai2 = {"--arg", "--argjson", "--rawfile", "--slurpfile", "--argdecode", "--raw-file"}
    skrip_dari_opsi = False
    skrip_terisi = False
    lewat_dd = False
    # Audit 1138: sed -i (edit di tempat) tanpa operand berkas
    # adalah galat ("no input files"), bukan pembaca stdin.
    sed_i = False
    # Audit 2641: penanda awk sempat bertemu
    # token opsi apa pun - mawk dengan opsi
    # saja (tanpa program) tetap membaca stdin
    # sebagai rekaman berprogram kosong, jadi
    # hanya awk yang benar-benar telanjang
    # yang mati usage.
    ada_opsi_awk = False
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
            # Audit 2441: sesudah -- token
            # adalah operand, bukan bendera.
            lewat_dd = True
            i += 1
            continue
        if bersih.startswith("-") and bersih != "-":
            if lewat_dd and perintah == "grep":
                # Audit 2689: sesudah -- token
                # strip keluarga grep adalah
                # OPERAND (pola/berkas), bukan
                # bendera (Audit 2441) - walk
                # utama dulu melewatinya sebagai
                # bendera polos sehingga pola
                # tak pernah terisi dan vonis
                # akhir 2639 membalikkan
                # perintah yang menguras jadi
                # mati (rg -- --generate).
                if not skrip_terisi and not skrip_dari_opsi:
                    skrip_terisi = True
                    i += 1
                    continue
                return False
            if perintah == "awk":
                if (nama_asli in ("awk", "mawk", "nawk",
                                  "original-awk")
                        and not lewat_dd):
                    # Audit 2675: blok ini (dan cek
                    # -e 2482 di hilir) hanya berlaku
                    # SEBELUM --; sesudah -- token
                    # strip adalah teks program
                    # (ekspresi -x/-5 sah dan
                    # menguras), bukan opsi.
                    # Audit 2661: semesta opsi mawk
                    # kecil (F/f/v/W, semua pembawa;
                    # tanpa opsi panjang) - token
                    # strip lain membunuh alat
                    # ("not an option", rc=2) atau
                    # informasional (--help/--version
                    # dan -W version/help mencetak
                    # lalu keluar); penanda 2641
                    # dulu menyapu semuanya sehingga
                    # bentuk mati itu divonis
                    # menguras (fail-closed).
                    if bersih.startswith("--"):
                        return False
                    if bersih.startswith("-W"):
                        nilai_w = bersih[2:] or (
                            toks[i + 1].strip("\"'")
                            if i + 1 < n else "")
                        if nilai_w in ("version",
                                       "help"):
                            return False
                    elif not (
                            bersih.split("=", 1)[0]
                            in nilai
                            or re.match(r"^-[Ffv]",
                                        bersih)):
                        return False
                ada_opsi_awk = True
            if perintah in ("jq", "yq", "fq") and bersih in ("-n", "--null-input"):
                # jq -n tak membaca stdin sama sekali.
                return False
            if (perintah == "yq"
                    and not lewat_dd
                    and _angka_negatif(bersih)):
                # Audit 2435: yq menolak flag
                # angka (unknown shorthand) -
                # galat urai, stdin tak dibaca.
                return False
            if ((perintah in ("jq", "fq")
                    or (perintah == "yq"
                        and lewat_dd))
                    and _angka_negatif(bersih)):
                # Audit 2421: angka negatif =
                # program filter jq (atau nama
                # berkas sesudah skrip terisi),
                # bukan bendera polos.
                if skrip_terisi or skrip_dari_opsi:
                    return False
                skrip_terisi = True
                i += 1
                continue
            dasar = bersih.split("=", 1)[0]
            if (perintah == "grep" and nama_asli != "rg"
                    and dasar.startswith("--")
                    and dasar not in nilai):
                # Audit 2625: singkatan unik getopt_long
                # diselesaikan ke nama penuh sebelum
                # pemeriksaan pembawa - tanpa ini nilai
                # singkatan terbaca sebagai pola dan
                # operand sesudahnya sebagai berkas
                # (grep --max-c 1 pola: "1" adalah
                # nilai --max-count, "pola" polanya,
                # tak ada berkas - alat menguras).
                # Audit 2638: kecocokan eksak menang
                # juga di sini (lihat blok 2625).
                if dasar[2:] in _VOCAB_GREP_25:
                    calon_m = [dasar[2:]]
                else:
                    calon_m = [x for x in _VOCAB_GREP_25
                               if x.startswith(dasar[2:])]
                if len(calon_m) == 1:
                    dasar = "--" + calon_m[0]
            if (perintah == "jq"
                    and bersih.startswith("--")
                    and "=" in bersih):
                # Audit 2494: parser jq menolak
                # SEMUA --opsi=nilai ("Unknown
                # option", rc=2 - terprobe 1.7).
                return False
            if (perintah == "fq" and dasar in (
                    "--rawfile", "--slurpfile",
                    "--library-path", "--indent")):
                # Audit 2493: nama hantu fq -
                # biner selalu "no such argument"
                # (bentuk = maupun pisah).
                return False
            if (perintah == "fq" and "=" in bersih
                    and (dasar in nilai2
                         or dasar not in nilai)):
                # Audit 2493: bentuk = di fq hanya
                # sah untuk opsi bernilai SATU
                # token; dua-token "needs two
                # argument", bool "takes no
                # argument", tak dikenal ditolak.
                return False
            if (perintah == "yq" and dasar in (
                    "--arg", "--argjson",
                    "--rawfile", "--slurpfile")):
                # Audit 2495: nama hantu yq -
                # biner "unknown flag" untuk
                # bentuk = maupun pisah.
                return False
            if (perintah == "awk"
                    and nama_asli in ("mawk", "nawk",
                                      "original-awk")
                    and not lewat_dd
                    and dasar == "-e"):
                # Audit 2482: ketiga awk itu tak
                # punya -e sama sekali (galat
                # "not an option", stdin tak
                # dibaca); gawk memang punya -e.
                return False
            if dasar in nilai:
                # Audit 2481: opsi bernilai khusus
                # handler yang menggantung di akhir
                # segmen (token efektif sesudahnya
                # kurang dari kebutuhan) - biner
                # galat "needs an argument".
                if "=" not in bersih:
                    _perlu = (2 if dasar in nilai2
                              else 1)
                    _j = i + 1
                    _ada = 0
                    while _j < n and _ada < _perlu:
                        _tj = toks[_j]
                        if (_tj in (">", ">>", "1>",
                                    "2>", "1>>", "2>>")
                                or re.fullmatch(
                                    r"\d*>>?", _tj)):
                            _j += 2
                            continue
                        if re.match(r"\d*>>?\S", _tj):
                            _j += 1
                            continue
                        _ada += 1
                        _j += 1
                    if _ada < _perlu:
                        return False
                if perintah in ("sed", "awk", "grep") and (
                        dasar in ("-e", "-f", "--expression",
                                  "--file", "--pattern",
                                  "--regexp", "--regex")):
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
                if ((perintah in ("jq", "fq")
                        and dasar in ("-f",
                                      "--from-file"))
                        or (perintah == "yq"
                            and dasar
                            == "--from-file")):
                    # Audit 2500 (koreksi 2475): di
                    # yq, -f adalah --front-matter
                    # (string biasa), BUKAN from-file;
                    # hanya --from-file panjang yang
                    # memuat ekspresi dari berkas
                    # (biner v4.44.5). jq/fq: -f dan
                    # --from-file keduanya from-file.
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
            if (perintah == "grep"
                    and not skrip_terisi
                    and not skrip_dari_opsi):
                # Audit 2655: "-" di slot pola grep
                # adalah POLANYA sendiri (biner
                # mencetak hasil dari stdin), bukan
                # penanda berkas - tanpa ini vonis
                # akhir 2639 membaliknya jadi mati.
                skrip_terisi = True
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
    # Audit 2640: sed mewajibkan skrip (operand
    # pertama atau -e/-f) - lingkar habis tanpa
    # skrip terisi berarti alat mati usage rc=1
    # tanpa membaca stdin (sed, sed -n,
    # sed --posix).
    if perintah == "sed" and not skrip_terisi \
            and not skrip_dari_opsi:
        return False
    # Audit 2641: awk yang benar-benar telanjang
    # (tanpa program, tanpa opsi) mati usage di
    # mawk tanpa membaca stdin. Bentuk ber-opsi
    # tanpa program TIDAK divonis mati: mawk
    # menguras stdin pada awk -F:/awk -v x=1
    # (terbukti biner di verifikasi Audit-179).
    if perintah == "awk" and not skrip_terisi \
            and not skrip_dari_opsi and not ada_opsi_awk:
        return False
    # Audit 2639: keluarga grep mewajibkan operand
    # pola (atau pola dari opsi -e/-f) - lingkar
    # habis tanpa pola terisi berarti alat mati
    # usage rc=2 tanpa membaca stdin (grep -m 1,
    # grep --max-c 1, rg -c, egrep, fgrep).
    if perintah == "grep" and not skrip_terisi \
            and not skrip_dari_opsi:
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


def _menguras_read(toks, awal):
    """Audit 2717: builtin read menelan baris stdin
    (satu baris penuh secara bawaan). Aliran bersama
    gerbang berisi tepat satu baris keluaran, jadi
    konsumsi read berdampak utuh - sekelas head -1.
    -u fd lain dan redirect masukan mengganti
    sumber bacanya."""
    i = awal + 1
    n = len(toks)
    while i < n:
        t = toks[i]
        if (t.startswith("<") and not t.startswith("<<")
                and _fd_norm(t[1:].lstrip("&")) != "0"):
            return False
        if (t.startswith("<&") and t[2:].strip("\"'")
                not in ("0", "0-")):
            return False
        if t == "-u":
            if i + 1 < n and toks[i + 1].strip("\"'") != "0":
                return False
            i += 2
            continue
        if t.startswith("-u") and len(t) > 2:
            if t[2:].strip("\"'") != "0":
                return False
            i += 1
            continue
        if t in ("-p", "-t", "-d", "-n", "-N", "-i",
                 "-a"):
            i += 2
            continue
        i += 1
    return True


def _operand_sesudah_opsi(toks, awal, bernilai,
                          bernilai2=frozenset()):
    """Daftar operand polos sesudah melompati opsi (yang bernilai
    menelan token berikutnya; bentuk --opsi=nilai dan -o<nilai>
    merekat ditangani). Dipakai penilai khusus Audit 1059-1072.
    Audit 2658: bernilai2 = opsi yang menelan DUA token nilai
    (sqlite3 -lookaside/-pagecache) - dulu hanya satu yang
    dilompati sehingga nilai kedua terhitung operand."""
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
            if dasar in bernilai or dasar in bernilai2:
                _perlu = 2 if dasar in bernilai2 else 1
                if "=" not in bersih and i + _perlu >= n:
                    # Audit 1096: opsi bernilai menggantung di
                    # token terakhir — program galat ("requires an
                    # argument") dan tak membaca. Penanda operand
                    # membuat semua pemanggil condong ke False.
                    hasil.append("\x00gantung")
                i += 1 if "=" in bersih else 1 + _perlu
                continue
            i += 1
            continue
        hasil.append(bersih)
        i += 1
    return hasil



_CSVKIT_BOOL = {"--verbose", "--no-header-row",
                "--help", "--version",
                "--add-bom", "--blanks",
                "--linenumbers",
                "--no-leading-zeroes",
                "--skipinitialspace", "--tabs",
                "--zero"}


def _angka_negatif(tok):
    # argparse: token mirip angka negatif
    # adalah operand/nilai, bukan opsi.
    return (len(tok) > 1 and tok[0] == "-"
            and (tok[1].isdigit()
                 or (tok[1] == "." and len(tok) > 2
                     and tok[2].isdigit())))


def _hadir_unik(mentah, nama, semesta):
    # Audit 2405: kehadiran opsi panjang juga
    # lewat singkatan awalan UNIK terhadap
    # semesta opsi cabang.
    for t_hu in mentah:
        if t_hu == "--":
            break
        dasar_hu = t_hu.split("=", 1)[0]
        if dasar_hu == nama:
            return True
        if (dasar_hu.startswith("--")
                and nama.startswith(dasar_hu)
                and sum(1 for s_hu in semesta
                        if s_hu.startswith(dasar_hu)) == 1):
            return True
    return False


def _ada_token_angka(mentah, bernilai):
    # Audit 2424: token strip-angka di posisi
    # opsi = flag tak dikenal docopt (galat
    # parse sebelum stdin disentuh); token
    # sesudah opsi bernilai adalah nilainya.
    i_ta2 = 1
    while i_ta2 < len(mentah):
        t_ta2 = mentah[i_ta2]
        if t_ta2 == "--":
            return False
        if (t_ta2.split("=", 1)[0] in bernilai
                and "=" not in t_ta2):
            i_ta2 += 2
            continue
        if (len(t_ta2) > 1 and t_ta2[0] == "-"
                and t_ta2[1].isdigit()):
            return True
        i_ta2 += 1
    return False


def _operand_csvkit(toks, awal, bernilai,
                    bool_cabang=frozenset(),
                    kecuali=frozenset(),
                    pendek_bool=frozenset()):
    # Audit 2388: argparse mengembangkan bundel
    # huruf pendek - huruf bool menumpuk, huruf
    # bernilai menelan sisa token (atau token
    # berikutnya) sebagai nilainya. Bundel
    # dikembangkan dulu, lalu logika operand
    # yang sama dengan _operand_sesudah_opsi.
    mentah_ck = [t.strip("\"'") for t in toks[awal + 1:]]
    # Audit 2436: semesta huruf pendek bool =
    # umum csvkit (cli.py) + khas cabang.
    bool_pendek_ck = ({"-h", "-H", "-S", "-V",
                       "-b", "-l", "-t", "-v"}
                      | pendek_bool)
    kembang_ck = []
    i_mentah_ck = 0
    while i_mentah_ck < len(mentah_ck):
        t_ck = mentah_ck[i_mentah_ck]
        # Audit 2411: angka negatif = operand,
        # jangan dikembangkan sebagai bundel.
        if _angka_negatif(t_ck):
            kembang_ck.append(t_ck)
            i_mentah_ck += 1
            continue
        if t_ck == "--":
            # Audit 2396: sesudah -- semua token
            # operand harfiah, tak dikembangkan.
            kembang_ck.extend(mentah_ck[i_mentah_ck:])
            break
        if (t_ck.startswith("-")
                and not t_ck.startswith("--")
                and len(t_ck) > 2):
            # Audit 2437: kepala bundel = bagian
            # sebelum = pertama; nilai sesudah
            # huruf bernilai boleh merekat lewat
            # sisa kepala atau sesudah =.
            kepala_ck = t_ck.split("=", 1)[0]
            j_ck = 1
            while j_ck < len(kepala_ck):
                # Audit 2395: aksara bukan
                # alfanumerik di bundel = galat
                # argparse (bukan '--' sintetis).
                if not kepala_ck[j_ck].isalnum():
                    kembang_ck.append("\x00gantung")
                    break
                eja_ck = "-" + kepala_ck[j_ck]
                # Audit 2436: huruf yang tak
                # dikenal alat (bukan bernilai,
                # bukan bool umum/khas) = galat.
                if (eja_ck not in bernilai
                        and eja_ck not in bool_pendek_ck):
                    kembang_ck.append("\x00gantung")
                    break
                kembang_ck.append(eja_ck)
                if eja_ck in bernilai:
                    sisa_ck = t_ck[j_ck + 1:]
                    if sisa_ck.startswith("="):
                        # Audit 2440/2446: nilai
                        # kosong merekat hanya
                        # diteruskan biner untuk
                        # -d; opsi bernilai lain
                        # berhenti galat.
                        if sisa_ck[1:]:
                            kembang_ck.append(sisa_ck[1:])
                        elif eja_ck == "-d":
                            kembang_ck.append("")
                        else:
                            # jebakan, bukan
                            # gantung: token sesudah
                            # huruf bernilai termakan
                            # sebagai nilainya.
                            kembang_ck.append(
                                "\x00jebakan")
                    elif sisa_ck:
                        kembang_ck.append(sisa_ck)
                    elif ("=" not in t_ck
                            and i_mentah_ck + 1 < len(mentah_ck)
                            and mentah_ck[i_mentah_ck + 1].startswith("-")
                            and mentah_ck[i_mentah_ck + 1] != "-"
                            and not (len(mentah_ck[i_mentah_ck + 1]) > 1
                                     and mentah_ck[i_mentah_ck + 1][1].isdigit())):
                        # Audit 2392: nilai bundel
                        # mirip-opsi ditolak
                        # argparse.
                        kembang_ck.append("\x00jebakan")
                    break
                j_ck += 1
            else:
                # Audit 2437: semua huruf bool -
                # = merekat berarti argumen bagi
                # bool terakhir: ditolak.
                if "=" in t_ck:
                    kembang_ck.append("\x00gantung")
            i_mentah_ck += 1
            continue
        kembang_ck.append(t_ck)
        i_mentah_ck += 1
    hasil_ck = []
    i_ck = 0
    while i_ck < len(kembang_ck):
        t_ck = kembang_ck[i_ck]
        if _angka_negatif(t_ck):
            hasil_ck.append(t_ck)
            i_ck += 1
            continue
        if t_ck == "--":
            hasil_ck.extend(kembang_ck[i_ck + 1:])
            break
        if t_ck.startswith("-") and t_ck != "-":
            dasar_ck = t_ck.split("=", 1)[0]
            # Audit 2444: informasional pendek
            # -h/-V berhenti seperti bentuk
            # panjangnya, bukan bool biasa.
            if t_ck in ("-h", "-V"):
                hasil_ck.append("\x00gantung")
                i_ck += 1
                continue
            # Audit 2436: huruf pendek tunggal
            # yang tak dikenal alat = galat.
            if (len(t_ck) == 2
                    and not dasar_ck.startswith("--")
                    and dasar_ck not in bernilai
                    and dasar_ck not in bool_pendek_ck
                    and not _angka_negatif(t_ck)):
                hasil_ck.append("\x00gantung")
                i_ck += 1
                continue
            # Audit 2432: bentuk pendek ber-=
            # yang hurufnya bukan opsi bernilai
            # cabang ini = bool berargumen -
            # ditolak argparse (rc=2).
            if ("=" in t_ck
                    and not dasar_ck.startswith("--")
                    and len(dasar_ck) == 2
                    and dasar_ck not in bernilai):
                hasil_ck.append("\x00gantung")
                i_ck += 1
                continue
            # Audit 2394: singkatan awalan unik
            # opsi panjang diterima argparse.
            if (dasar_ck.startswith("--")
                    and dasar_ck not in bernilai):
                cocok_ck = [b_ck for b_ck in bernilai
                            if b_ck.startswith(dasar_ck)]
                # Audit 2403/2410/2416/2419:
                # semesta bool = umum csvkit +
                # khas cabang - yang dicabut.
                # Ambigu lintas himpunan, atau
                # informasional bool tunggal
                # (--help/--version), = berhenti.
                semesta_bool_ck = (
                    (_CSVKIT_BOOL | bool_cabang)
                    - kecuali)
                bool_ck = [b_ck
                           for b_ck in semesta_bool_ck
                           if b_ck.startswith(dasar_ck)]
                if (len(cocok_ck) > 1
                        or (len(cocok_ck) == 1
                            and bool_ck)
                        or len(bool_ck) > 1
                        or (not cocok_ck
                            and len(bool_ck) == 1
                            and bool_ck[0] in (
                                "--help", "--version"))
                        or ("=" in t_ck
                            and not cocok_ck
                            and bool_ck)):
                    hasil_ck.append("\x00gantung")
                    i_ck += 1
                    continue
                if len(cocok_ck) == 1:
                    dasar_ck = cocok_ck[0]
            if dasar_ck in bernilai:
                if ("=" not in t_ck
                        and i_ck + 1 >= len(kembang_ck)):
                    hasil_ck.append("\x00gantung")
                elif ("=" not in t_ck
                        and kembang_ck[i_ck + 1] == "\x00jebakan"):
                    hasil_ck.append("\x00gantung")
                    i_ck += 2
                    continue
                i_ck += 1 if "=" in t_ck else 2
                continue
            i_ck += 1
            continue
        hasil_ck.append(t_ck)
        i_ck += 1
    return hasil_ck


def _opsi_hadir(mentah, mulai, bernilai, dicari):
    # Audit 2320/2321: kehadiran opsi sadar
    # posisi - nilai opsi lain dilompati
    # verbatim; bentuk merekat '--x=v' dan
    # bundel pendek docopt dikenali.
    ditemukan = set()
    i_oh = mulai
    while i_oh < len(mentah):
        t_oh = mentah[i_oh]
        if t_oh == "--":
            break
        if t_oh in bernilai:
            if t_oh in dicari:
                ditemukan.add(t_oh)
            i_oh += 2
            continue
        if t_oh.startswith("--") and "=" in t_oh:
            nama_oh = t_oh.split("=", 1)[0]
            if nama_oh in dicari:
                ditemukan.add(nama_oh)
            i_oh += 1
            continue
        if (t_oh.startswith("-")
                and not t_oh.startswith("--")
                and len(t_oh) > 1):
            j_oh = 1
            while j_oh < len(t_oh):
                eja_oh = "-" + t_oh[j_oh]
                if eja_oh in bernilai:
                    if eja_oh in dicari:
                        ditemukan.add(eja_oh)
                    j_oh = len(t_oh)
                    break
                if eja_oh in dicari:
                    ditemukan.add(eja_oh)
                j_oh += 1
            i_oh += 1
            continue
        i_oh += 1
    return ditemukan

def _ada_duplikat(mentah, mulai, bernilai, pasangan,
                  boleh_ulang=frozenset()):
    # Audit 2326: docopt menolak opsi yang
    # muncul lebih dari sekali (maks 1 per
    # opsi; alias pendek/panjang disatukan
    # lewat dict pasangan).
    hitung_du = {}
    i_du = mulai
    while i_du < len(mentah):
        t_du = mentah[i_du]
        if t_du == "--":
            break
        if t_du in bernilai:
            k_du = pasangan.get(t_du, t_du)
            if k_du not in boleh_ulang:
                hitung_du[k_du] = hitung_du.get(k_du, 0) + 1
            i_du += 2
            continue
        if t_du.startswith("--"):
            nama_du = t_du.split("=", 1)[0]
            k_du = pasangan.get(nama_du, nama_du)
            if k_du not in boleh_ulang:
                hitung_du[k_du] = hitung_du.get(k_du, 0) + 1
            i_du += 1
            continue
        if t_du.startswith("-") and len(t_du) > 1:
            j_du = 1
            lompat_du = False
            while j_du < len(t_du):
                eja_du = "-" + t_du[j_du]
                k_du = pasangan.get(eja_du, eja_du)
                if k_du not in boleh_ulang:
                    hitung_du[k_du] = hitung_du.get(k_du, 0) + 1
                if eja_du in bernilai:
                    if j_du == len(t_du) - 1:
                        lompat_du = True
                    break
                j_du += 1
            i_du += 2 if lompat_du else 1
            continue
        i_du += 1
    return any(v_du > 1 for v_du in hitung_du.values())


def _saring_csvkit(toks, awal, perintah):
    # Audit 2335/2336/2337/2340/2341: csvkit
    # --null-value ber-nargs='+' menelan semua
    # token non-opsi sesudahnya (cli.py:222);
    # csvsql --engine-option ber-nargs=2.
    # Mengembalikan daftar toks tersaring,
    # atau None bila --null-value tanpa nilai
    # (argparse berhenti dengan galat).
    segmen_sc = list(toks[awal:])
    # Audit 2418: nama khas per alat -
    # csvsql hanya --engine-option; sql2csv
    # keduanya; alat lain hanya --null-value.
    if perintah == "csvsql":
        khas_sc = ("--null-value",
                   "--engine-option")
    elif perintah == "sql2csv":
        khas_sc = ("--null-value",
                   "--engine-option",
                   "--execution-option")
    else:
        khas_sc = ("--null-value",)
    hasil_sc = []
    i_sc = 0
    while i_sc < len(segmen_sc):
        t_sc = segmen_sc[i_sc]
        # Audit 2407: singkatan awalan unik
        # ketiga nama khas saringan.
        dasar_sc = t_sc.split("=", 1)[0]
        if (dasar_sc.startswith("--")
                and len(dasar_sc) >= 5
                and dasar_sc not in khas_sc):
            cocok_sc = [n_sc for n_sc in khas_sc
                        if n_sc.startswith(dasar_sc)]
            if len(cocok_sc) == 1:
                t_sc = cocok_sc[0] + t_sc[len(dasar_sc):]
        if t_sc == "--":
            hasil_sc.extend(segmen_sc[i_sc:])
            break
        if t_sc == "--null-value":
            j_sc = i_sc + 1
            while (j_sc < len(segmen_sc)
                   and (not segmen_sc[j_sc].startswith("-")
                        or segmen_sc[j_sc] == "-"
                        or (len(segmen_sc[j_sc]) > 1
                            and segmen_sc[j_sc][1].isdigit()))):
                j_sc += 1
            if j_sc == i_sc + 1:
                return None
            i_sc = j_sc
            continue
        if t_sc.startswith("--null-value="):
            i_sc += 1
            continue
        if (perintah in ("csvsql", "sql2csv")
                and t_sc in khas_sc[1:]):
            if i_sc + 2 >= len(segmen_sc):
                return None
            # Audit 2393: argparse menolak nilai
            # mirip-opsi untuk nargs=2 (angka
            # negatif tetap nilai).
            if any(segmen_sc[i_sc + k].startswith("-")
                   and segmen_sc[i_sc + k] != "-"
                   and not (len(segmen_sc[i_sc + k]) > 1
                            and segmen_sc[i_sc + k][1].isdigit())
                   for k in (1, 2)):
                return None
            i_sc += 3
            continue
        if (perintah in ("csvsql", "sql2csv")
                and any(t_sc.startswith(n_sc + "=")
                        for n_sc in khas_sc[1:])):
            return None
        hasil_sc.append(t_sc)
        i_sc += 1
    return list(toks[:awal]) + hasil_sc


def _token_terlarang(mentah, terlarang, bernilai):
    # Audit 2338/2339/2342: token yang tak
    # dikenal utilitas csvkit tertentu
    # (override_flags di sumbernya) membuat
    # argparse berhenti sebelum membaca stdin.
    # Audit 2348: bentuk merekat/
    # bundel huruf pendek (-Ly, -y100) ikut
    # dipindai; huruf bernilai menghentikan
    # pindaian bundel (sisanya nilai).
    for t_tt in mentah:
        if t_tt == "--":
            break
        if t_tt in terlarang:
            return True
        if any(t_tt.startswith(n_tt + "=")
               for n_tt in terlarang
               if n_tt.startswith("--")):
            return True
        # Audit 2409: singkatan awalan unik
        # nama terlarang ikut tertangkap.
        if t_tt.startswith("--"):
            dasar_tt = t_tt.split("=", 1)[0]
            cocok_tt = [n_tt for n_tt in terlarang
                        if n_tt.startswith("--")
                        and n_tt.startswith(dasar_tt)]
            if len(cocok_tt) == 1 and dasar_tt != cocok_tt[0]:
                return True
        if (t_tt.startswith("-")
                and not t_tt.startswith("--")
                and len(t_tt) > 1):
            for h_tt in t_tt[1:]:
                eja_tt = "-" + h_tt
                if eja_tt in terlarang:
                    return True
                if eja_tt in bernilai:
                    break
    return False


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

def _jebakan_argparse(mentah, bernilai):
    # Audit 2106: argparse menolak nilai yang
    # mirip opsi ('expected one argument') -
    # perintah berhenti sebelum membaca stdin.
    # Angka negatif dan '-' tunggal adalah nilai
    # sah bagi heuristik argparse.
    for k_jb, t_jb in enumerate(mentah):
        # Audit 2408: singkatan awalan unik
        # opsi bernilai ikut dikenali.
        nama_jb = t_jb
        if (t_jb.startswith("--") and "=" not in t_jb
                and t_jb not in bernilai):
            cocok_jb = [b_jb for b_jb in bernilai
                        if b_jb.startswith(t_jb)]
            if len(cocok_jb) == 1:
                nama_jb = cocok_jb[0]
        if nama_jb in bernilai and k_jb + 1 < len(mentah):
            b_jb = mentah[k_jb + 1]
            if (b_jb.startswith("-") and b_jb != "-"
                    and not (len(b_jb) > 1
                             and b_jb[1].isdigit())):
                return True
    return False


_CSVKIT = {"-d", "--delimiter", "-q", "--quotechar",
           "-u", "--quoting", "-p", "--escapechar",
           "-z", "--maxfieldsize", "-e", "--encoding",
           "-y", "--snifflimit", "-L", "--locale",
           "-K", "--skip-lines", "--null-value",
           "--date-format", "--datetime-format"}



_SAH_TSV = {
    # Audit 2498: nama sah per cabang klon tsv
    # dari sumber getopt masing-masing:
    # (panjang kanon, pendek kanon, pulau
    # panjang caseSensitive, pulau pendek,
    # lipat huruf, pulau lipat). Nama di luar
    # himpunan = GetOptException - alat
    # berhenti sebelum membaca. 'h'/'help' +
    # varian help-* = bantuan implisit
    # std.getopt. Audit 2529: pulau lipat =
    # nama yang tetap dilipat walau alat tak
    # melipat (help-verbose csv2tsv terdeklarasi
    # SEBELUM penanda caseSensitive pertama di
    # csv2tsv.d - zona tak-peka bawaan Phobos).
    # Audit 2532: elemen terakhir = huruf
    # pendek opsi BERNILAI per alat (sumber
    # yang sama); sisanya opsi bool. Partisinya
    # pas menutup himpunan pendek tiap entri.
    "tsv-sample": (
        frozenset({"delimiter", "key-fields",
                   "num", "prob", "seed-value",
                   "weight-field",
                   "random-value-header", "header",
                   "version", "inorder", "replace",
                   "static-seed",
                   "compatibility-mode",
                   "gen-random-inorder",
                   "print-random", "line-buffered",
                   "prefer-skip-sampling",
                   "prefer-algorithm-r", "help",
                   "help-verbose", "help-fields"}),
        frozenset({"d", "k", "n", "p", "v", "w",
                   "H", "V", "i", "r", "s", "h"}),
        frozenset({"header", "seed-value",
                   "version"}),
        frozenset({"H", "v", "V"}), True,
        frozenset(),
        frozenset({"d", "k", "n", "p", "v", "w"})),
    "tsv-uniq": (
        frozenset({"fields", "at-least", "max",
                   "equiv-header", "equiv-start",
                   "number-header", "delimiter",
                   "header", "version",
                   "ignore-case", "repeated",
                   "equiv", "number",
                   "line-buffered", "help",
                   "help-verbose", "help-fields"}),
        frozenset({"f", "a", "m", "d", "H", "V",
                   "i", "r", "e", "z", "h"}),
        frozenset({"header", "version"}),
        frozenset({"H", "V"}), True,
        frozenset(),
        frozenset({"a", "d", "f", "m"})),
    "tsv-pretty": (
        frozenset({"delimiter", "lookahead",
                   "repeat-header", "precision",
                   "empty-replacement",
                   "space-between-fields",
                   "max-text-width", "preamble",
                   "header", "no-header",
                   "underline-header",
                   "format-floats", "replace-empty",
                   "auto-preamble", "version",
                   "help", "help-verbose"}),
        frozenset({"d", "l", "r", "p", "E", "s",
                   "m", "b", "H", "x", "u", "f",
                   "e", "a", "V", "h"}),
        frozenset({"header", "replace-empty",
                   "empty-replacement", "version"}),
        frozenset({"H", "e", "E", "V"}), True,
        frozenset(),
        frozenset({"d", "l", "r", "p", "E", "s",
                   "m", "b"})),
    "tsv-split": (
        frozenset({"delimiter", "digit-width",
                   "dir", "key-fields",
                   "lines-per-file",
                   "max-open-files", "num-files",
                   "prefix", "seed-value", "suffix",
                   "header", "header-in-only",
                   "append", "static-seed",
                   "version", "help",
                   "help-verbose", "help-fields"}),
        frozenset({"d", "k", "l", "n", "v", "w",
                   "H", "I", "a", "s", "V", "h"}),
        frozenset({"header", "header-in-only",
                   "seed-value", "version"}),
        frozenset({"H", "I", "v", "V"}), True,
        frozenset(),
        frozenset({"d", "k", "l", "n", "v", "w"})),
    "csv2tsv": (
        frozenset({"quote", "csv-delim",
                   "tsv-delim", "tab-replacement",
                   "newline-replacement", "header",
                   "version", "help-verbose",
                   "help"}),
        frozenset({"q", "c", "t", "r", "n", "H",
                   "V", "h"}),
        frozenset(), frozenset(), False,
        frozenset({"help-verbose"}),
        frozenset({"q", "c", "t", "r", "n"})),
    "number-lines": (
        frozenset({"delimiter", "start-number",
                   "header-string", "header",
                   "line-buffered", "version",
                   "help"}),
        frozenset({"d", "n", "s", "H", "V", "h"}),
        frozenset({"header", "version"}),
        frozenset({"H", "V"}), True,
        frozenset(),
        frozenset({"d", "n", "s"})),
}


def _nama_sah_tsv(dasar, sah):
    """Audit 2498: True bila dasar adalah nama
    opsi yang dikenal getopt alat klon tsv
    (atau bukan token opsi sama sekali)."""
    pjg, pdk, pu_pjg, pu_pdk, lipat, pu_lipat, nilai_pdk = sah
    if (not dasar.startswith("-")
            or dasar in ("-", "--")):
        return True
    if dasar.startswith("--"):
        nama = dasar[2:]
        if nama in pu_pjg:
            return True
        if nama.lower() in pu_pjg:
            return False
        if lipat:
            return nama.lower() in pjg
        return nama in pjg or nama.lower() in pu_lipat
    huruf = dasar[1]
    if huruf in pu_pdk:
        kanon = huruf
        dikenal = huruf in pdk
    elif huruf in pdk:
        kanon = huruf
        dikenal = True
    else:
        kanon = huruf.lower()
        dikenal = lipat and kanon in pdk
    if not dikenal:
        return False
    # Audit 2553: -h bantuan implisit hanya
    # cocok sebagai token persis (sapuan akhir
    # getopt) - bentuk berekornya tak dikenal
    # parse utama, alat berhenti. -V version
    # adalah opsi bool sungguhan: ekor "false"
    # (dilipat) berarti alat tetap jalan,
    # ekor lain membuatnya berhenti sebelum
    # membaca (true = version tercetak,
    # sampah = ConvException).
    if len(dasar) > 2 and kanon == "h":
        return False
    if len(dasar) > 2 and kanon == "V":
        return dasar[2:].lower() == "false"
    if len(dasar) > 2:
        # Audit 2532: ekor token pendek adalah
        # nilai menempel opsi pertamanya. Opsi
        # bernilai: ekor apa pun sah. Opsi bool:
        # optMatch Phobos hanya menerima literal
        # "true"/"false" sebagai nilai - ekor
        # lain = ConvException, alat mati
        # sebelum membaca.
        if kanon in nilai_pdk:
            return True
        # Audit 2543: parse!bool Phobos melipat
        # huruf tiap aksara (sumber std/conv.d;
        # unittest resmi "falSE"/"faLse") - ekor
        # kapital campuran pun nilai sah, alat
        # jalan dan menguras.
        return dasar[2:].lower() in ("true", "false")
    return True


def _char_getopt_sah(nilai):
    # Penerima char Phobos (to!char dari string):
    # sah hanya tepat satu karakter satu-byte -
    # dok opsi tsv-summarize sendiri berbunyi
    # "Single byte UTF-8 characters only"; di luar
    # itu ConvException dan alat mati di getopt.
    return len(nilai) == 1 and ord(nilai) < 128


def _uint_getopt_sah(nilai):
    # Penerima bilangan bulat tak bertanda Phobos
    # (to!size_t dst.): literal digit ASCII dengan
    # tanda '+' tunggal opsional di depan; di luar
    # itu ConvException dan alat mati di getopt.
    inti = nilai[1:] if nilai.startswith("+") else nilai
    return inti != "" and inti.isascii() and inti.isdigit()


def _durasi_timeout_sah(nilai):
    # Audit 2690: durasi timeout diurai strtod
    # (xstrtod coreutils) + satu sufiks [smhd] -
    # bukan hanya desimal polos: bentuk "5."/
    # "5.s" (titik di akhir), eksponen ("5e3"),
    # heksadesimal ("0x10", "0x1p2"), "inf"/
    # "infinity" (huruf bebas), dan tanda "+"
    # semua diterima biner; "nan" dan nilai
    # negatif ditolak timeout sendiri.
    # Terverifikasi biner coreutils 9.4.
    return re.fullmatch(
        r"\+?("
        r"\d+(\.\d*)?([eE][+-]?\d+)?"
        r"|\.\d+([eE][+-]?\d+)?"
        r"|0[xX]([0-9a-fA-F]+(\.[0-9a-fA-F]*)?"
        r"|\.[0-9a-fA-F]+)([pP][+-]?\d+)?"
        r"|[iI][nN][fF]([iI][nN][fF][iI][nN]"
        r"[iI][tT][yY])?"
        r")[smhd]?", nilai) is not None


def _sinyal_timeout_sah(nilai):
    # Audit 2691: nilai -s/--signal timeout -
    # angka 0..64, atau nama sinyal (huruf
    # bebas, awalan SIG opsional) dari tabel
    # coreutils, atau RTMIN/RTMAX +- geseran
    # yang hasilnya tetap di rentang RT
    # (34..64). Terverifikasi biner 9.4:
    # UNUSED/LOST ditolak, CLD/POLL/IOT
    # diterima, "+9" ditolak.
    u = nilai.upper()
    if u.isdigit():
        return int(u) <= 64
    m = re.fullmatch(r"(SIG)?(RTMIN|RTMAX)([+-]\d+)?", u)
    if m:
        dasar = 34 if m.group(2) == "RTMIN" else 64
        if m.group(3):
            dasar += int(m.group(3))
        return 34 <= dasar <= 64
    if u.startswith("SIG"):
        u = u[3:]
    return u in {
        "HUP", "INT", "QUIT", "ILL", "TRAP", "ABRT",
        "IOT", "BUS", "FPE", "KILL", "USR1", "SEGV",
        "USR2", "PIPE", "ALRM", "TERM", "STKFLT",
        "CHLD", "CLD", "CONT", "STOP", "TSTP",
        "TTIN", "TTOU", "URG", "XCPU", "XFSZ",
        "VTALRM", "PROF", "WINCH", "IO", "POLL",
        "PWR", "SYS"}


def _mode_stdbuf_sah(nilai):
    # Audit 2695: mode penyangga stdbuf -
    # "L", angka polos, atau angka/huruf
    # sufiks tunggal [KMGTPEk] (huruf kecil
    # hanya k) dengan akhiran B/iB opsional;
    # sufiks telanjang tanpa angka pun sah.
    # Parser stdbuf sendiri, BUKAN xstrtol
    # (1m/1g/1Z mati di sini). Biner 9.4.
    return re.fullmatch(
        r"L|\d+([KMGTPEk](iB|B)?)?"
        r"|[KMGTPEk](iB|B)?", nilai) is not None


def _masker_taskset_sah(nilai):
    # Audit 2692: masker taskset boleh berbentuk
    # grup heksa dipisah koma (untuk >32 CPU) -
    # awalan 0x hanya di grup pertama, grup
    # kosong hanya ditoleransi di ujung akhir,
    # dan masker keseluruhan tak boleh nol.
    # Terverifikasi biner util-linux setempat.
    v = nilai
    if v.startswith(("0x", "0X")):
        v = v[2:]
    grup = v.split(",")
    if grup and grup[-1] == "":
        grup.pop()
    if not grup:
        return False
    bukan_nol = False
    for g in grup:
        if not re.fullmatch(r"[0-9a-fA-F]+", g):
            return False
        if int(g, 16) != 0:
            bukan_nol = True
    return bukan_nol


def _double_getopt_sah(nilai):
    # Penerima double Phobos (to!double): literal
    # desimal bertanda dengan pecahan/eksponen
    # opsional, atau inf/infinity/nan (huruf
    # dilipat); di luar itu ConvException dan
    # alat mati di getopt.
    t = nilai.strip().lower()
    if t in ("inf", "+inf", "-inf", "infinity",
             "+infinity", "-infinity", "nan",
             "+nan", "-nan"):
        return True
    return re.fullmatch(
        r"[+-]?(\d+(\.\d*)?|\.\d+)([eE][+-]?\d+)?",
        nilai.strip()) is not None


def _jumlah_head_sah(nilai):
    # Penerima jumlah coreutils head/tail
    # (xstrtol): tanda opsional, digit, lalu
    # sufiks pengali opsional (huruf tunggal
    # b/k/K/m/M/g/G/t/T/p/P/e/E/z/Z/y/Y, atau
    # huruf itu + B untuk basis 1000); di luar
    # itu alat mati "invalid number" sebelum
    # membaca (terprobe coreutils 9.4: "1B"
    # dan "1.5" mati, "1b"/"1k"/"1KB" jalan).
    return re.fullmatch(
        r"[+-]?\d+(?:[bkKmMgGtTpPeEzZyY]"
        r"|[kKmMgGtTpPeEzZyY]B)?",
        nilai) is not None


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
            # Audit 1181: -init. Audit 2592: opsi bernilai
            # lain dari --help resmi sqlite3 (-cmd terbukti
            # menelan --help lalu stdin tetap dieksekusi).
            pendahulu.update(("-init", "-cmd", "-separator",
                              "-nullvalue", "-newline", "-nonce",
                              "-vfs", "-mmap", "-maxsize",
                              "-lookaside", "-pagecache"))
        # Audit 2453: pendahulu khas docopt -
        # nilai opsi bernilai qsv/xsv dimakan
        # verbatim, termasuk -h/-V.
        # Audit 2455/2456: pendahulu PER
        # SUBPERINTAH (mentah[0]) - token bool
        # atau asing bagi subperintah yang
        # berjalan tidak lagi melindungi.
        # Audit 2467: untuk qsv/xsv himpunan per
        # subperintah MENGGANTIKAN pendahulu
        # dasar bersama (bukan digabung) - token
        # dasar yang asing bagi subperintah
        # (mis. -f di select) tak lagi melindungi.
        if perintah == "qsv":
            pendahulu = set(
                _PENDAHULU_QSV_PER_SUB.get(
                    mentah[0], frozenset()))
        if perintah == "xsv":
            pendahulu = set(
                _PENDAHULU_XSV_PER_SUB.get(
                    mentah[0], frozenset()))
        # Audit 2575: pendahulu khas tsv-summarize -
        # opsi bernilai dan operator (callback dua-
        # parameter) memakan token berikutnya
        # sebagai nilai, jadi --help/--version di
        # posisi itu bukan panggilan informasional.
        # Nama dilipat (zona caseInsensitive
        # sumber); perbandingan pendahulunya ikut
        # dilipat khusus perintah ini.
        lipat_pendahulu = perintah == "tsv-summarize"
        if lipat_pendahulu:
            pendahulu = pendahulu | {
                "--group-by", "-d", "--delimiter",
                "--sum", "--mean", "--median", "--min",
                "--max", "--mode", "--first", "--last",
                "--stdev", "--var", "--range",
                "--quantile", "--retain", "--values",
                "--unique-values", "--unique-count",
                "--mode-count", "--missing-count",
                "--not-missing-count", "--count-header",
                "--mad", "-g", "-v",
                "--values-delimiter", "-p",
                "--float-precision", "-r",
                "--replace-missing"}
        # Audit 2623: sqlite3 -lookaside/-pagecache
        # mengambil DUA nilai (N SZ) - tanda di
        # posisi nilai kedua pun tertelan, bukan
        # informasional (probe biner: stdin tetap
        # dieksekusi). Tandai kedua posisi nilainya.
        tertelan2 = set()
        if perintah == "sqlite3":
            lewati2 = 0
            for k_i2, t_i2 in enumerate(mentah):
                if lewati2 > 0:
                    tertelan2.add(k_i2)
                    lewati2 -= 1
                    continue
                if t_i2 in ("-lookaside", "-pagecache"):
                    lewati2 = 2
        for k_i, t_i in enumerate(mentah):
            if t_i in tanda:
                if k_i in tertelan2:
                    continue
                if k_i > 0:
                    sebelum = mentah[k_i - 1]
                    if lipat_pendahulu:
                        sebelum = sebelum.lower()
                    if sebelum in pendahulu:
                        continue
                return True
        return False

    if _ada_informatif(("--help", "--version")):
        return False
    if perintah == "openssl" and _ada_informatif(
            ("-help", "-version")):
        return False
    # Audit 2449/2450: qsv/xsv (docopt) juga
    # berhenti pada informasional pendek
    # -h/-V, bukan hanya bentuk panjang.
    if perintah in ("qsv", "xsv") and _ada_informatif(
            ("-h", "-V")):
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
        # Audit 1939: -version/-help strip-tunggal juga
        # informasional (terprobe sqlite3: keluar tanpa
        # membaca) - penjaga kepala hanya mengenal
        # strip-ganda.
        if "-version" in mentah or "-help" in mentah:
            return False
        # Audit 2644: opsi tak dikenal membunuh
        # shell sebelum membaca ("Error: unknown
        # option", rc=1) - cabang ini sebelumnya
        # tak punya aturan kematian opsi asing
        # (sqlite3 --bogus/-bogus dinilai
        # menguras). Kosakata = gabungan --help
        # sqlite3 3.45.1 + opsi yang sudah
        # dikenal cabang ini + opsi shell 3.x
        # umum; biner mencocokkan EKSak
        # (singkatan -sep/-vers mati) dan bentuk
        # "=" juga mati (-cmd=x rc=1). Nilai
        # pembawa yang dikenal dilompati agar
        # tak dinilai sebagai opsi.
        # Audit 2664: escape/excel/full/htm/
        # ifexists/ltrace/uri dibuang - biner
        # 3.45.1 menolak ketujuhnya ("unknown
        # option"); mereka dulu masuk dari
        # daftar opsi umum tanpa bukti biner
        # per opsi.
        _VOKAB_SQLITE3_44 = frozenset({
            "A", "append", "ascii", "bail", "batch",
            "box", "cmd", "column", "csv",
            "deserialize", "echo", "eqp",
            "header", "help",
            "html", "init",
            "interactive", "json", "line", "list",
            "lookaside", "markdown",
            "maxsize", "memtrace",
            "mmap", "newline", "nofollow",
            "noheader", "nonce", "nullvalue",
            "pagecache", "pcachetrace", "quote",
            "readonly", "safe", "separator",
            "stats", "table", "tabs",
            # Audit 2672: "unsafe" hantu disapu
            # (biner menolaknya); yang asli
            # justru -unsafe-testing, opsi sah
            # 3.45.1 yang mengeksekusi SQL
            # terpipa - dulu hilang dari
            # kosakata (fail-open). Ejaannya
            # bertanda hubung, persis seperti
            # yang dibandingkan pindaian.
            "unsafe-testing",
            "version", "vfs", "zip"})
        _pembawa1_44 = {"cmd", "init", "separator",
                        # Audit 2672: maxwidth hantu
                        # dibuang dari himpunan ini
                        # juga (duduk di 3 tempat).
                        "nullvalue", "vfs",
                        "newline", "nonce", "A",
                        # Audit 2657: -maxsize/-mmap
                        # bernilai angka tetapi dulu
                        # bukan pembawa - nilainya
                        # terhitung operand di kedua
                        # penghitung cabang ini.
                        "maxsize", "mmap"}
        _lewati_44 = 0
        _ops_44 = 0
        for t_44 in mentah:
            if _lewati_44 > 0:
                _lewati_44 -= 1
                continue
            if _ops_44 >= 2:
                break
            if t_44 == "--":
                continue
            if t_44.startswith("-") and t_44 != "-":
                nm_44 = t_44.lstrip("-")
                if "=" in nm_44 or \
                        nm_44 not in _VOKAB_SQLITE3_44:
                    return False
                if nm_44 in ("lookaside", "pagecache"):
                    _lewati_44 = 2
                elif nm_44 in _pembawa1_44:
                    _lewati_44 = 1
                continue
            _ops_44 += 1
        # Audit 2653: nilai -cmd dieksekusi
        # sebagai SQL sebelum stdin dibaca -
        # nilai yang diawali "-" (bukan "--",
        # yang adalah komentar SQL sah) pasti
        # syntax error di prepare dan alat
        # berhenti tanpa membaca (terbukti
        # biner: -cmd "-lookaside"/"-x" mati,
        # -cmd "-- komentar" jalan). Cacat
        # lama: nilai berbentuk opsi dua-nilai
        # juga membingungkan pindaian pendahulu
        # informasional.
        for k_53, t_53 in enumerate(mentah):
            if t_53 in ("-cmd", "--cmd") \
                    and k_53 + 1 < len(mentah):
                v_53 = mentah[k_53 + 1]
                if v_53.startswith("-") \
                        and not v_53.startswith("--"):
                    return False
        # Audit 1059: operand pertama = berkas db (bukan sumber
        # perintah); operand kedua = string SQL yang menekan.
        # Audit 2663: biner menerima bentuk panjang --semua
        # opsi ini juga (terbukti --cmd/--separator/--nullvalue/
        # --maxsize/--lookaside mengeksekusi SQL terpipa) -
        # himpunan lompati digandakan stripnya, dan aturan
        # nilai -cmd 2653 di atas kini mencocokkan --cmd juga.
        _bernilai_sq = {
            "-cmd", "-init", "-separator", "-nullvalue",
            # Audit 2672: "-maxwidth" hantu
            # dibuang dari himpunan lompati.
            "-vfs", "-pagecache", "-lookaside",
            # Audit 2657: -maxsize/-mmap bernilai
            # juga - nilainya bukan operand.
            "-maxsize", "-mmap",
            # Audit 1175: -newline/-nonce bernilai — vonis
            # sqlite3 menghitung jumlah operand, jadi nilai
            # yang tertelan langsung membalikkan vonis.
            "-newline", "-nonce"}
        ops = _operand_sesudah_opsi(
            toks, awal,
            _bernilai_sq | {"-" + x for x in _bernilai_sq},
            {"-lookaside", "-pagecache",
             "--lookaside", "--pagecache"})
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
        # Audit 1954: --exclude-from/--include-from
        # bernilai '-' juga membaca daftar dari stdin
        # (man rsync; terprobe rsync terpasang).
        for i, t in enumerate(mentah):
            if t in ("--files-from=-", "--exclude-from=-",
                     "--include-from=-"):
                return True
            if (t in ("--files-from", "--exclude-from",
                      "--include-from")
                    and i + 1 < len(mentah)
                    and mentah[i + 1] == "-"):
                return True
            if t == "--files-from" and i + 1 < len(mentah) \
                    and mentah[i + 1] == "-":
                return True
        return False
    if perintah in ("socat",
                    # Audit 1250: nama binary socat sebenarnya.
                    "socat1"):
        # Audit 1066: alamat '-' / STDIO / STDIN membaca stdin.
        # Audit 1906: -u aliran satu arah kiri->kanan dan
        # -U kanan->kiri: stdin hanya dikonsumsi bila
        # alamat di sisi SUMBER (pertama untuk -u, kedua
        # untuk -U) adalah alamat stdin.
        def _alamat_stdin(t_sc):
            return (t_sc == "-" or t_sc.startswith("STDIO")
                    or t_sc.startswith("STDIN"))
        if "-u" in mentah or "-U" in mentah:
            alamat_sc = []
            i_sc = 0
            while i_sc < len(mentah):
                t_sc = mentah[i_sc]
                if t_sc in ("-b", "-t", "-T", "-S"):
                    i_sc += 2
                    continue
                if not t_sc.startswith("-") or t_sc == "-":
                    alamat_sc.append(t_sc)
                i_sc += 1
            if "-u" in mentah:
                return bool(alamat_sc) and _alamat_stdin(alamat_sc[0])
            return len(alamat_sc) >= 2 and _alamat_stdin(alamat_sc[1])
        return any(_alamat_stdin(t) for t in mentah
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
                     "--data-binary", "--data-urlencode", "--json", "-T",
                     "--upload-file", "-K", "--config", "-F",
                     "--form"):
                if i + 1 < len(mentah):
                    v = mentah[i + 1]
                    if t in ("-d", "--data", "--data-ascii",
                             "--data-binary", "--json"):
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
                    t.startswith("--json") and "@-" in t) or (
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
    if perintah in ("nc", "netcat", "nc.openbsd", "ncat"):
        # Audit 1068: nc dengan target/port meneruskan stdin ke
        # koneksi (atau pendengar) — tanpa operand ia tak jalan.
        # Audit 1092: mode pindai -z hanya menguji port terbuka.
        if "-z" in mentah:
            return False
        # Audit 1910: --recv-only ncat hanya menerima -
        # stdin tak diteruskan (Ncat Reference Guide).
        if perintah == "ncat" and "--recv-only" in mentah:
            return False
        ops_nc = _operand_sesudah_opsi(
            toks, awal, {"-p", "-s", "-X", "-x", "-w", "-i", "-q",
                         "-I", "-M", "-m", "-O", "-P",
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
        # Audit 1907: nilai '-' pada opsi bernilai selain
        # -b (-P/-F/-J/-D/-B) gugur fatal saat parsing di
        # OpenSSH - perintah tak pernah berjalan walau
        # ada -b - di belakangnya.
        for k_sf, t_sf in enumerate(mentah):
            if t_sf in ("-P", "-F", "-J", "-D", "-B") \
                    and k_sf + 1 < len(mentah) \
                    and mentah[k_sf + 1] == "-":
                return False
            if (len(t_sf) > 2 and t_sf[:2]
                    in ("-P", "-F", "-J", "-D", "-B")
                    and t_sf[2:] == "-"):
                return False
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
        ops_sf = _operand_sesudah_opsi(
            toks, awal, {"-b", "-o", "-P", "-F", "-J",
                         "-B", "-D", "-S"})
        if "-" in ops_sf:
            return False
        # Audit 1908: operand pertama berbentuk
        # host:berkas = mode unduh otomatis
        # non-interaktif - tak membaca perintah stdin.
        if ops_sf:
            pertama_sf = ops_sf[0]
            if (":" in pertama_sf and (
                    "/" not in pertama_sf
                    or pertama_sf.index(":")
                    < pertama_sf.index("/"))):
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
            if t_cp in ("-F", "-I", "--file"):
                if i_cp + 1 >= len(mentah):
                    return False
                berkas_cp = mentah[i_cp + 1]
                i_cp += 2
                continue
            if t_cp.startswith("--file="):
                berkas_cp = t_cp.split("=", 1)[1]
                i_cp += 1
                continue
            if (t_cp[:2] in ("-F", "-I") and len(t_cp) > 2
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
                # Audit 1879: -I sekelas -F (berkas arsip).
                if not t_cp.startswith("--") and (
                        "F" in t_cp or "I" in t_cp):
                    pos_cp = min(
                        t_cp.index(h_cp)
                        for h_cp in "FI" if h_cp in t_cp)
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
        ops_rc = _operand_sesudah_opsi(
            toks, awal, {"-x", "--ignore", "--sequence"})
        if "\x00gantung" in ops_rc:
            return False
        return len(ops_rc) == 1
    # Audit 1865: cpan - mode shell membaca perintah
    # dari stdin; mode pasang (operand modul, atau
    # opsi bernilai -j/-M tanpa -s) tidak.
    if perintah == "cpan":
        if any(t_c2 in _INFO_HURUF.get("cpan", ())
               for t_c2 in mentah):
            return False
        if "-s" in mentah:
            return True
        ops_c = _operand_sesudah_opsi(
            toks, awal, {"-j", "-M"})
        if "\x00gantung" in ops_c:
            return False
        if ops_c:
            return False
        for t_c in mentah:
            if (t_c in ("-j", "-M")
                    or (t_c.startswith(("-j", "-M"))
                        and not t_c.startswith("--"))):
                return False
        return True
    if perintah == "ssh":
        # Audit 1881: ssh meneruskan stdin ke sesi jauh
        # secara bawaan; yang memutusnya hanya -n (alihkan
        # stdin dari /dev/null; -f mengimplikasikannya), -N
        # (tanpa perintah jauh), dan -G/-Q (cetak
        # konfigurasi lalu keluar). Tanpa tujuan = galat.
        bernilai_ssh = {"-B", "-b", "-c", "-D", "-E", "-e",
                        "-F", "-I", "-i", "-J", "-L", "-l",
                        "-m", "-O", "-o", "-p", "-R", "-S",
                        "-W", "-w"}
        i_sh = 0
        tujuan_sh = False
        while i_sh < len(mentah):
            t_sh = mentah[i_sh]
            # Audit 1888: '-' tunggal sebagai token
            # pertama bukan tujuan sah (ssh keluar:
            # nama host tak sah) - ia tak pernah
            # meneruskan stdin. Sesudah tujuan ia kata
            # perintah jauh biasa.
            if t_sh == "-" and not tujuan_sh:
                return False
            if t_sh == "--":
                # Audit 1912: token sesudah -- adalah
                # tujuan harfiah - yang berawalan '-'
                # bukan nama host sah (ssh menolaknya).
                tujuan_sh = (i_sh + 1 < len(mentah)
                             and not mentah[i_sh + 1].startswith("-"))
                break
            if t_sh in bernilai_ssh:
                # Audit 1911: opsi bernilai di token
                # terakhir menggantung - ssh galat
                # tanpa membaca stdin.
                if i_sh + 1 >= len(mentah):
                    return False
                i_sh += 2
                continue
            if t_sh.startswith("--"):
                i_sh += 1
                continue
            if (t_sh.startswith("-") and len(t_sh) > 1
                    and not t_sh.startswith("--")):
                huruf_sh = t_sh[1:]
                # Huruf bernilai pertama membatasi wilayah
                # bendera: sesudahnya semua nilai menempel.
                pos_sh = None
                for k_sh, h_sh in enumerate(huruf_sh):
                    if "-" + h_sh in bernilai_ssh:
                        pos_sh = k_sh
                        break
                bendera_sh = (huruf_sh if pos_sh is None
                              else huruf_sh[:pos_sh])
                if any(h_sh in bendera_sh for h_sh in "nNGQf"):
                    return False
                if pos_sh is not None:
                    # Audit 1911: huruf bernilai di ujung
                    # bundel menelan token berikutnya -
                    # yang menggantung juga galat.
                    if pos_sh < len(huruf_sh) - 1:
                        i_sh += 1
                    elif i_sh + 1 >= len(mentah):
                        return False
                    else:
                        i_sh += 2
                    continue
                i_sh += 1
                continue
            # Audit 1887: getopt OpenSSH mempermutasi
            # argv - opsi SESUDAH tujuan tetap diurai
            # sebagai opsi ssh, jadi pindaian berlanjut
            # (kata perintah jauh berikutnya bukan opsi
            # dan tak mengubah vonis, kecuali huruf
            # pemutus yang sudah diperiksa di atas).
            tujuan_sh = True
            i_sh += 1
            continue
        return tujuan_sh
    if perintah in ("rsh", "rlogin"):
        # Audit 1894: rsh menyalin stdin-nya ke perintah
        # jauh (rsh(1): "copies its standard input to
        # the remote command"); -n mengalihkan stdin
        # dari /dev/null.
        # Audit 2039: tanpa perintah jauh rsh masuk
        # mode rlogin (rsh(1) verbatim) - sesi
        # interaktif yang sama; rlogin sendiri
        # kini ikut dinilai.
        if "-n" in mentah:
            return False
        ops_rsh = _operand_sesudah_opsi(
            toks, awal, {"-l", "-e", "-t", "-p"})
        if "\x00gantung" in ops_rsh:
            return False
        return len(ops_rsh) >= 1
    if perintah == "mosquitto_pub":
        # Audit 1895: -s/--stdin-file dan -l/--stdin-line
        # membaca pesan dari stdin (man mosquitto_pub);
        # bentuk lain pesannya dari -m/berkas.
        # Audit 1922: bundel huruf getopt diurai -
        # huruf mode stdin (s/l) di dalam bundel tetap
        # berlaku; huruf bernilai menghentikan urai
        # (sisanya adalah nilai menempel).
        # Audit 1946: pindai menghormati opsi bernilai
        # terpisah lebih dulu - '-s' sebagai nilai -t
        # bukan mode stdin.
        i_mp = 0
        while i_mp < len(mentah):
            t_mp = mentah[i_mp]
            if t_mp in ("-s", "--stdin-file", "-l",
                        "--stdin-line"):
                return True
            # Audit 1964/1965: -m/-x/-I/-D bernilai
            # (hilang kemarin); -c bendera polos
            # (keluar dari himpunan bernilai).
            if t_mp in ("-h", "-p", "-q", "-t", "-u", "-P",
                        "-i", "-k", "-A", "-V", "-f", "-m",
                        "-x", "-I", "-D"):
                i_mp += 2
                continue
            if (t_mp.startswith("-")
                    and not t_mp.startswith("--")
                    and len(t_mp) > 2):
                lompat_mp = 1
                for pos_mp, h_mp in enumerate(t_mp[1:]):
                    if h_mp in ("s", "l"):
                        return True
                    if h_mp in ("h", "p", "q", "t", "u", "P",
                                "i", "k", "A", "V", "f", "m",
                                "x", "I", "D"):
                        if pos_mp == len(t_mp) - 2:
                            lompat_mp = 2
                        break
                i_mp += lompat_mp
                continue
            i_mp += 1
        return False
    if perintah == "grpcurl":
        # Audit 1896: -d @ membaca badan permintaan dari
        # stdin (README grpcurl); nilai @ lain berkas.
        for k_gr, t_gr in enumerate(mentah):
            if t_gr in ("-d", "--data"):
                if (k_gr + 1 < len(mentah)
                        and mentah[k_gr + 1] == "@"):
                    return True
            elif t_gr in ("-d@", "--data=@"):
                return True
        return False
    if perintah == "kubectl":
        # Audit 1897: sub-perintah exec/attach/run dengan
        # -i/--stdin meneruskan stdin ke kontainer
        # (help kubectl: "Pass stdin to the container").
        # Audit 1919/1920: wilayah pindai = sesudah
        # sub-perintah sampai '--' (pflag berhenti di
        # sana; '-i' sesudahnya milik perintah
        # kontainer); bundel diurai dengan -c bernilai
        # (sisa bundel/nilai berikutnya adalah nama
        # kontainer, bukan bendera).
        sub_kc = None
        for k_kc, t_kc in enumerate(mentah):
            if t_kc in ("exec", "attach", "run"):
                sub_kc = k_kc
                break
        if sub_kc is None:
            return False
        j_kc = sub_kc + 1
        while j_kc < len(mentah):
            t_kc = mentah[j_kc]
            if t_kc == "--":
                return False
            if t_kc in ("-i", "--stdin", "--stdin=true"):
                return True
            if t_kc in ("-c", "--container"):
                j_kc += 2
                continue
            if t_kc.startswith("--"):
                j_kc += 1
                continue
            if t_kc.startswith("-") and len(t_kc) > 1:
                lompat_kc = 1
                huruf_kc = t_kc[1:]
                for pos_kc, h_kc in enumerate(huruf_kc):
                    if h_kc == "i":
                        return True
                    if h_kc == "c":
                        if pos_kc == len(huruf_kc) - 1:
                            lompat_kc = 2
                        break
                j_kc += lompat_kc
                continue
            j_kc += 1
        return False
    if perintah == "docker":
        # Audit 1898: exec/run -i menjaga stdin tetap
        # terbuka dan meneruskannya ke kontainer
        # (docs.docker.com).
        # Audit 1916/1918/1921: wilayah opsi hanya
        # sesudah sub-perintah dan sebelum operand
        # pertama (nama kontainer/citra) - '-i' di
        # wilayah perintah milik perintah kontainer;
        # bentuk panjang --interactive berlaku; bundel
        # diurai setia getopt (huruf bernilai menelan
        # sisa bundel / token berikutnya).
        # Audit 2018: sub ditentukan sadar nilai
        # opsi global (docs: --config/--context/
        # -H/--host/--log-level bernilai) - token
        # di posisi nilai BUKAN sub-perintah.
        global_dk = {"--config", "--context", "-c",
                     "-H", "--host", "--log-level", "-l",
                     "--tlscacert", "--tlscert",
                     "--tlskey"}
        j0_dk = 0
        sub0_dk = None
        while j0_dk < len(mentah):
            t0_dk = mentah[j0_dk]
            if t0_dk in global_dk:
                j0_dk += 2
                continue
            if any(t0_dk.startswith(g + "=")
                   for g in global_dk):
                j0_dk += 1
                continue
            # Audit 2084: huruf informasional
            # pendek -h/-v sebelum sub mematikan
            # perintah seperti bentuk panjangnya.
            if t0_dk in ("-h", "-v"):
                return False
            # Audit 2092: bundel yang memuat h/v
            # di antara huruf bool murni juga
            # informasional ('-vD', '-Dv',
            # '-v=true') - pflag mengurai bundel
            # huruf demi huruf.
            badan_dk = t0_dk[1:].split("=")[0]
            if (len(t0_dk) > 2 and t0_dk[1] != "-"
                    and badan_dk
                    and all(ch in "Dhv"
                            for ch in badan_dk)
                    and any(ch in "hv"
                            for ch in badan_dk)):
                return False
            # Audit 2080: bundel huruf pendek
            # bool+nilai ('-Dc') diurai - huruf
            # terakhir bernilai menelan token
            # berikutnya (dok: opsi satu huruf
            # boleh digabung).
            if (len(t0_dk) > 2 and t0_dk[1] != "-"
                    and t0_dk[-1] in ("c", "H", "l")
                    and all(ch in "D"
                            for ch in t0_dk[1:-1])):
                j0_dk += 2
                continue
            if t0_dk.startswith("-"):
                j0_dk += 1
                continue
            sub0_dk = j0_dk
            break
        if sub0_dk is not None:
            nama_sub = mentah[sub0_dk]
            sisa_dk = mentah[sub0_dk + 1:]
            if nama_sub == "import":
                return "-" in sisa_dk
            if nama_sub == "load":
                return ("-i" not in sisa_dk
                        and "--input" not in sisa_dk
                        and not any(x.startswith("--input=")
                                    for x in sisa_dk))
            if nama_sub == "build":
                # Audit 2026: '-' konteks hanya di
                # posisi operand (nilai opsi dilewati);
                # Audit 2021: bentuk merekat '-f-'.
                nilai_bd = {"-f", "--file", "-t",
                            "--tag", "--build-arg",
                            "--label", "--network",
                            "--target", "--platform",
                            "--output", "-o", "--cache-from",
                            "--cache-to", "--secret",
                            "--ssh", "--add-host",
                            "--progress", "--iidfile",
                            "--metadata-file"}
                j_bd = 0
                while j_bd < len(sisa_dk):
                    t_bd = sisa_dk[j_bd]
                    if t_bd in ("-f", "--file"):
                        if (j_bd + 1 < len(sisa_dk)
                                and sisa_dk[j_bd + 1] == "-"):
                            return True
                        j_bd += 2
                        continue
                    if t_bd in nilai_bd:
                        j_bd += 2
                        continue
                    if any(t_bd.startswith(v + "=")
                           for v in nilai_bd):
                        j_bd += 1
                        continue
                    if t_bd == "-":
                        return True
                    if t_bd == "--file=-":
                        return True
                    if (t_bd.startswith("-f")
                            and len(t_bd) > 2
                            and t_bd[2:].lstrip("=") == "-"):
                        return True
                    if (t_bd in ("-f", "--file")
                            and j_bd + 1 < len(sisa_dk)
                            and sisa_dk[j_bd + 1] == "-"):
                        return True
                    j_bd += 1
                return False
            if nama_sub not in ("exec", "run"):
                return False
        sub_dk = None
        for k_dk, t_dk in enumerate(mentah):
            if t_dk in ("exec", "run"):
                sub_dk = k_dk
                break
        if sub_dk is None:
            return False
        nilai_dk = {"e", "u", "w", "v", "p", "m", "h"}
        panjang_dk = {"--env", "--user", "--workdir",
                      "--volume", "--publish", "--name",
                      "--network", "--memory", "--env-file",
                      "--entrypoint", "--hostname", "--mount",
                      "--add-host", "--dns", "--label",
                      "--label-file", "--ip", "--expose",
                      "--restart", "--pull", "--platform",
                      "--cidfile", "--detach-keys",
                      "--log-driver", "--health-cmd"}
        j_dk = sub_dk + 1
        while j_dk < len(mentah):
            t_dk = mentah[j_dk]
            if t_dk == "--":
                return False
            if t_dk in ("-i", "--interactive",
                        "--interactive=true"):
                return True
            if t_dk.startswith("--"):
                j_dk += 2 if t_dk in panjang_dk else 1
                continue
            if t_dk.startswith("-") and len(t_dk) > 1:
                lompat_dk = 1
                huruf_dk = t_dk[1:]
                for pos_dk, h_dk in enumerate(huruf_dk):
                    if h_dk == "i":
                        return True
                    if h_dk in nilai_dk:
                        if pos_dk == len(huruf_dk) - 1:
                            lompat_dk = 2
                        break
                j_dk += lompat_dk
                continue
            return False
        return False
    if perintah == "podman":
        # Audit 1899: exec/run -i mengonsumsi stdin
        # (docs.podman.io) - cermin docker 1898.
        # Audit 1917/1918/1921: cermin perbaikan docker
        # 1916 (wilayah opsi, --interactive, bundel
        # getopt) pada cabang podman.
        # Audit 2019: cermin 2018 pada podman
        # (opsi global bernilai: --connection/
        # --url/--config/--identity/--log-level/
        # --namespace/--runtime/--root/--runroot).
        global_pm = {"--connection", "--url",
                     "--config", "--identity",
                     "--log-level", "--namespace",
                     "--runtime", "--root", "--runroot",
                     "--storage-driver",
                     "--cgroup-manager",
                     "--events-backend", "--tmpdir",
                     "--storage-opt", "-c", "--conmon",
                     "--hooks-dir", "--imagestore",
                     "--module", "--network-cmd-path",
                     "--network-config-dir", "--out",
                     "--runtime-flag", "--ssh",
                     "--cdi-spec-dir", "--tls-ca",
                     "--tls-cert", "--tls-details",
                     "--tls-key", "--volumepath"}
        j0_pm = 0
        sub0_pm = None
        while j0_pm < len(mentah):
            t0_pm = mentah[j0_pm]
            if t0_pm in global_pm:
                j0_pm += 2
                continue
            if any(t0_pm.startswith(g + "=")
                   for g in global_pm):
                j0_pm += 1
                continue
            # Audit 2084: cermin docker - -h/-v
            # pendek sebelum sub.
            if t0_pm in ("-h", "-v"):
                return False
            # Audit 2101: bundel informasional
            # cermin docker (podman juga pflag).
            badan_pm = t0_pm[1:].split("=")[0]
            if (len(t0_pm) > 2 and t0_pm[1] != "-"
                    and badan_pm
                    and all(ch in "Dhv"
                            for ch in badan_pm)
                    and any(ch in "hv"
                            for ch in badan_pm)):
                return False
            if t0_pm.startswith("-"):
                j0_pm += 1
                continue
            sub0_pm = j0_pm
            break
        if sub0_pm is not None:
            nama_pm = mentah[sub0_pm]
            sisa_pm = mentah[sub0_pm + 1:]
            if nama_pm == "import":
                return "-" in sisa_pm
            if nama_pm == "load":
                return ("-i" not in sisa_pm
                        and "--input" not in sisa_pm
                        and not any(x.startswith("--input=")
                                    for x in sisa_pm))
            if nama_pm == "machine":
                # Audit 2020: machine ssh = sesi
                # interaktif ke mesin podman (man).
                return bool(sisa_pm) and sisa_pm[0] == "ssh"
            if nama_pm == "build":
                nilai_bp = {"-f", "--file", "-t",
                            "--tag", "--build-arg",
                            "--label", "--network",
                            "--target", "--platform",
                            "--output", "-o", "--cache-from",
                            "--cache-to", "--secret",
                            "--ssh", "--add-host",
                            "--progress", "--iidfile",
                            "--metadata-file"}
                j_bp = 0
                while j_bp < len(sisa_pm):
                    t_bp = sisa_pm[j_bp]
                    if t_bp in ("-f", "--file"):
                        if (j_bp + 1 < len(sisa_pm)
                                and sisa_pm[j_bp + 1] == "-"):
                            return True
                        j_bp += 2
                        continue
                    if t_bp in nilai_bp:
                        j_bp += 2
                        continue
                    if any(t_bp.startswith(v + "=")
                           for v in nilai_bp):
                        j_bp += 1
                        continue
                    if t_bp == "--file=-":
                        return True
                    if (t_bp.startswith("-f")
                            and len(t_bp) > 2
                            and t_bp[2:].lstrip("=") == "-"):
                        return True
                    if (t_bp in ("-f", "--file")
                            and j_bp + 1 < len(sisa_pm)
                            and sisa_pm[j_bp + 1] == "-"):
                        return True
                    j_bp += 1
                return False
            if nama_pm not in ("exec", "run"):
                return False
        sub_pm = None
        for k_pm, t_pm in enumerate(mentah):
            if t_pm in ("exec", "run"):
                sub_pm = k_pm
                break
        if sub_pm is None:
            return False
        nilai_pm = {"e", "u", "w", "v", "p", "m", "h"}
        panjang_pm = {"--env", "--user", "--workdir",
                      "--volume", "--publish", "--name",
                      "--network", "--memory", "--env-file",
                      "--entrypoint", "--hostname", "--mount",
                      "--add-host", "--dns", "--label",
                      "--label-file", "--ip", "--expose",
                      "--restart", "--pull", "--platform",
                      "--cidfile", "--detach-keys",
                      "--log-driver", "--health-cmd"}
        j_pm = sub_pm + 1
        while j_pm < len(mentah):
            t_pm = mentah[j_pm]
            if t_pm == "--":
                return False
            if t_pm in ("-i", "--interactive",
                        "--interactive=true"):
                return True
            if t_pm.startswith("--"):
                j_pm += 2 if t_pm in panjang_pm else 1
                continue
            if t_pm.startswith("-") and len(t_pm) > 1:
                lompat_pm = 1
                huruf_pm = t_pm[1:]
                for pos_pm, h_pm in enumerate(huruf_pm):
                    if h_pm == "i":
                        return True
                    if h_pm in nilai_pm:
                        if pos_pm == len(huruf_pm) - 1:
                            lompat_pm = 2
                        break
                j_pm += lompat_pm
                continue
            return False
        return False
    if perintah == "terraform":
        # Audit 1900: hanya sub-perintah console yang
        # membaca perintah dari stdin saat terpipa
        # (dokumentasi HashiCorp); sub-perintah lain
        # tak membaca stdin.
        ops_tf = _operand_sesudah_opsi(toks, awal, {"-chdir"})
        if "\x00gantung" in ops_tf:
            return False
        return bool(ops_tf) and ops_tf[0] == "console"
    if perintah == "websocat":
        # Audit 1901: alamat '-'/'stdio:' membaca stdin;
        # mode klien sederhana (satu alamat ws:// atau
        # wss:// tanpa mode dengar) juga meneruskan
        # baris stdin ke websocket (README websocat).
        if any(t_ws == "-" or t_ws.startswith("stdio:")
               for t_ws in mentah):
            return True
        if any(t_ws in ("-l", "--listen", "-s", "--server")
               for t_ws in mentah):
            return False
        ops_ws = _operand_sesudah_opsi(
            toks, awal, {"-H", "--header", "--origin",
                         "--protocol", "-k"})
        if "\x00gantung" in ops_ws:
            return False
        return (len(ops_ws) == 1
                and (ops_ws[0].startswith("ws://")
                     or ops_ws[0].startswith("wss://")))
    if perintah == "redis-cli":
        # Audit 1902: -x membaca argumen terakhir dari
        # stdin dan --pipe membaca protokol dari stdin;
        # tanpa kata perintah ia membaca perintah dari
        # stdin saat terpipa (redis.io). Dengan kata
        # perintah (tanpa -x) ia menjalankan lalu keluar.
        # Audit 1923: pindai menghormati nilai opsi
        # bernilai - '-x'/'--pipe' hanya berarti saklar
        # di posisi opsi, bukan sebagai nilai (mis.
        # kata sandi -a).
        bernilai_rc = {"-h", "-p", "-a", "-u", "--user",
                       "--pass", "-n", "--db", "-s",
                       "--cacert", "--cert", "--key",
                       "--tls-cacertdir"}
        # Audit 1993: mode khusus redis-cli
        # (--bigkeys/--scan/--latency/dll) berjalan
        # sendiri di main() dan tak membaca stdin;
        # pemeriksaan di posisi opsi (nilai opsi
        # bernilai sudah dilompati gelung).
        modus_rc = {"--bigkeys", "--scan", "--latency",
                    "--stat", "--memkeys", "--hotkeys",
                    "--replica"}
        i_rc = 0
        kata_rc = 0
        while i_rc < len(mentah):
            t_rc = mentah[i_rc]
            if t_rc in modus_rc:
                return False
            if t_rc in ("-x", "--pipe"):
                return True
            if t_rc in bernilai_rc:
                if i_rc + 1 >= len(mentah):
                    return False
                i_rc += 2
                continue
            if t_rc.startswith("-") and t_rc != "-":
                i_rc += 1
                continue
            kata_rc += 1
            i_rc += 1
        return kata_rc == 0
    if perintah == "psql":
        # Audit 1903: -f - membaca stdin; tanpa -c/-f
        # ia membaca perintah dari stdin saat terpipa
        # (dokumentasi app-psql). -c menjalankan string
        # lalu keluar; -f berkas membaca berkas.
        # Audit 1913: -c dan -f diproses BERURUTAN
        # sesuai urutan kemunculannya (dokumentasi
        # app-psql) - vonis ditentukan himpunan aksinya,
        # bukan penentu pertama: -f - di mana pun berarti
        # stdin tetap dibaca.
        ada_stdin_pq = False
        ada_tekan_pq = False
        for k_pq, t_pq in enumerate(mentah):
            if t_pq in ("-f", "--file"):
                if k_pq + 1 >= len(mentah):
                    return False
                if mentah[k_pq + 1] == "-":
                    ada_stdin_pq = True
                else:
                    ada_tekan_pq = True
            elif t_pq in ("-f-", "--file=-"):
                ada_stdin_pq = True
            elif t_pq in ("-c", "--command"):
                ada_tekan_pq = True
            elif t_pq in ("-l", "--list", "-V", "--version"):
                return False
        if ada_stdin_pq:
            return True
        if ada_tekan_pq:
            return False
        return True
    if perintah == "mysql":
        # Audit 1904: tanpa -e ia berjalan sebagai
        # filter membaca stdin saat terpipa (MySQL
        # Reference Manual); -e/--execute menjalankan
        # string lalu keluar.
        # Audit 1955: gelung indeks agar nilai opsi
        # terpisah bisa dilompati.
        i_my = 0
        while i_my < len(mentah):
            t_my = mentah[i_my]
            lompat_my = False
            if t_my in ("-V", "--version", "--help"):
                return False
            # Audit 1914: --execute=<..> dan huruf e di
            # bundel mana pun adalah -e (e bernilai -
            # sisa bundel adalah nilainya).
            if (t_my == "--execute"
                    or t_my.startswith("--execute=")):
                return False
            # Audit 1943 + 1955: urai setia getopt -
            # opsi bernilai TERPISAH menelan token
            # berikutnya utuh ('-e' sebagai nilai -h
            # bukan -e); di dalam bundel, huruf
            # bernilai pertama menelan sisa bundel.
            # Audit 1981: opsi bernilai WAJIB yang
            # menggantung di token terakhir adalah
            # galat; -p/--password bernilai opsional.
            if t_my in ("-h", "-u", "-D", "-S",
                        "--host", "--user",
                        "--database", "--socket", "--port"):
                if i_my + 1 >= len(mentah):
                    return False
                lompat_my = True
            elif t_my in ("-p", "--password"):
                lompat_my = True
            elif (t_my.startswith("-")
                    and not t_my.startswith("--")):
                for pos_my, h_my in enumerate(t_my[1:]):
                    if h_my == "e":
                        return False
                    if h_my in ("p", "D", "h", "u", "S", "P"):
                        # Audit 1981: huruf bernilai di
                        # UJUNG bundel menelan token
                        # berikut; menggantung = galat
                        # (kecuali p: prompt sandi).
                        if pos_my == len(t_my) - 2:
                            if h_my == "p":
                                pass
                            elif i_my + 1 >= len(mentah):
                                return False
                            else:
                                lompat_my = True
                        break
            i_my += 2 if lompat_my else 1
        return True
    if perintah == "mariadb":
        # Audit 1905: cermin mysql 1904 (klien MariaDB:
        # mode masukan terpipa membaca stdin;
        # dokumentasi mariadb.com).
        i_md = 0
        while i_md < len(mentah):
            t_md = mentah[i_md]
            lompat_md = False
            if t_md in ("-V", "--version", "--help"):
                return False
            # Audit 1915: cermin 1914 pada mariadb.
            if (t_md == "--execute"
                    or t_md.startswith("--execute=")):
                return False
            # Audit 1944 + 1956: cermin 1943/1955
            # pada mariadb.
            # Audit 1982: cermin 1981 pada mariadb.
            if t_md in ("-h", "-u", "-D", "-S",
                        "--host", "--user",
                        "--database", "--socket", "--port"):
                if i_md + 1 >= len(mentah):
                    return False
                lompat_md = True
            elif t_md in ("-p", "--password"):
                lompat_md = True
            elif (t_md.startswith("-")
                    and not t_md.startswith("--")):
                for pos_md, h_md in enumerate(t_md[1:]):
                    if h_md == "e":
                        return False
                    if h_md in ("p", "D", "h", "u", "S", "P"):
                        # Audit 1982: cermin 1981 -
                        # huruf bernilai di ujung bundel.
                        if pos_md == len(t_md) - 2:
                            if h_md == "p":
                                pass
                            elif i_md + 1 >= len(mentah):
                                return False
                            else:
                                lompat_md = True
                        break
            i_md += 2 if lompat_md else 1
        return True
    if perintah == "nerdctl":
        # Audit 1926: cermin docker untuk nerdctl -
        # exec/run -i/--interactive meneruskan stdin
        # dan load polos membaca arsip dari stdin
        # (dokumentasi containerd/nerdctl). Wilayah
        # opsi dan urai bundel sama dengan docker.
        sub_nc = None
        for k_nc, t_nc in enumerate(mentah):
            if t_nc in ("exec", "run"):
                sub_nc = k_nc
                break
            if t_nc == "load":
                return True
        if sub_nc is None:
            return False
        nilai_nc = {"e", "u", "w", "v", "p", "m", "h"}
        panjang_nc = {"--env", "--user", "--workdir",
                      "--volume", "--publish", "--name",
                      "--network", "--memory", "--env-file",
                      "--entrypoint", "--hostname", "--mount",
                      "--add-host", "--dns", "--label",
                      "--label-file", "--ip", "--expose",
                      "--restart", "--pull", "--platform",
                      "--cidfile", "--detach-keys",
                      "--log-driver", "--health-cmd"}
        j_nc = sub_nc + 1
        while j_nc < len(mentah):
            t_nc = mentah[j_nc]
            if t_nc == "--":
                return False
            if t_nc in ("-i", "--interactive",
                        "--interactive=true"):
                return True
            if t_nc.startswith("--"):
                j_nc += 2 if t_nc in panjang_nc else 1
                continue
            if t_nc.startswith("-") and len(t_nc) > 1:
                lompat_nc = 1
                huruf_nc = t_nc[1:]
                for pos_nc, h_nc in enumerate(huruf_nc):
                    if h_nc == "i":
                        return True
                    if h_nc in nilai_nc:
                        if pos_nc == len(huruf_nc) - 1:
                            lompat_nc = 2
                        break
                j_nc += lompat_nc
                continue
            return False
        return False
    if perintah == "oc":
        # Audit 1927: cermin kubectl untuk oc -
        # exec/attach -i/--stdin meneruskan stdin
        # (man page oc). Wilayah pindai dan urai
        # bundel sama dengan kubectl.
        sub_oc = None
        for k_oc, t_oc in enumerate(mentah):
            if t_oc in ("exec", "attach"):
                sub_oc = k_oc
                break
        if sub_oc is None:
            return False
        j_oc = sub_oc + 1
        while j_oc < len(mentah):
            t_oc = mentah[j_oc]
            if t_oc == "--":
                return False
            if t_oc in ("-i", "--stdin", "--stdin=true"):
                return True
            if t_oc in ("-c", "--container"):
                j_oc += 2
                continue
            if t_oc.startswith("--"):
                j_oc += 1
                continue
            if t_oc.startswith("-") and len(t_oc) > 1:
                lompat_oc = 1
                huruf_oc = t_oc[1:]
                for pos_oc, h_oc in enumerate(huruf_oc):
                    if h_oc == "i":
                        return True
                    if h_oc == "c":
                        if pos_oc == len(huruf_oc) - 1:
                            lompat_oc = 2
                        break
                j_oc += lompat_oc
                continue
            j_oc += 1
        return False
    if perintah == "consul":
        # Audit 1928: kv put/import dengan nilai '-'
        # membaca nilai dari stdin (dokumentasi
        # HashiCorp + contoh resmi).
        # Audit 1945: '-' hanya berarti stdin di
        # posisi NILAI (operand terakhir) - di posisi
        # kunci ia nama kunci harfiah dengan data
        # tersedia.
        if len(mentah) >= 2 and mentah[0] == "kv" \
                and mentah[1] in ("put", "import"):
            sisa_cs = mentah[2:]
            return bool(sisa_cs) and sisa_cs[-1] == "-"
        return False
    if perintah == "vault":
        # Audit 1929: bentuk tulis vault dengan '-'
        # atau 'kunci=-' membaca nilai dari stdin
        # (dokumentasi HashiCorp seksi stdin). Bentuk
        # baca (kv get) tak membaca stdin.
        if not any(t_vl in ("put", "write") for t_vl in mentah):
            return False
        # Audit 1947: nilai stdin hanya bila token
        # '-' sendiri atau nilai sesudah '=' pertama
        # PERSIS '-' (bukan berakhiran '=-').
        return any(
            t_vl == "-"
            or ("=" in t_vl
                and t_vl.split("=", 1)[1] == "-")
            for t_vl in mentah)
    if perintah == "etcdctl":
        # Audit 1930: put tanpa nilai membaca nilai
        # dari stdin; txn membaca interaksi dari
        # stdin (README resmi etcdctl).
        sub_et = None
        for k_et, t_et in enumerate(mentah):
            if t_et in ("put", "txn"):
                sub_et = k_et
                break
        if sub_et is None:
            return False
        if mentah[sub_et] == "txn":
            return True
        sisa_et = []
        j_et = sub_et + 1
        while j_et < len(mentah):
            t_et = mentah[j_et]
            if t_et == "--lease":
                j_et += 2
                continue
            if t_et.startswith("-"):
                j_et += 1
                continue
            sisa_et.append(t_et)
            j_et += 1
        return len(sisa_et) == 1
    if perintah == "glab":
        # Audit 1932: api --input - dan field @-
        # membaca badan dari stdin; auth login
        # --stdin membaca token dari stdin (man glab).
        for k_gl, t_gl in enumerate(mentah):
            if t_gl == "--stdin":
                return True
            if t_gl == "--input":
                if (k_gl + 1 < len(mentah)
                        and mentah[k_gl + 1] == "-"):
                    return True
            elif t_gl == "--input=-":
                return True
            elif (t_gl in ("-F", "--field")
                    and k_gl + 1 < len(mentah)
                    and "@-" in mentah[k_gl + 1]):
                return True
        return False
    if perintah == "clickhouse-client":
        # Audit 1933: mode polos terpipa = mode batch
        # membaca query dari stdin (dokumentasi
        # resmi ClickHouse).
        # Audit 1941: --query pun tetap membaca stdin
        # (dokumentasi: query, stdin, "or both" -
        # request = query + line feed + isi stdin).
        return True
    if perintah == "aws":
        # Audit 1934: hanya s3 cp dengan SUMBER '-'
        # yang mengunggah isi stdin (AWS CLI User
        # Guide); '-' di tujuan berarti stdout.
        # Audit 1942: opsi global bernilai aws bisa
        # mendahului sub-perintah - lompati dulu.
        k_aw = 0
        global_aw = {"--region", "--profile",
                     "--endpoint-url", "--output", "--query",
                     "--ca-bundle", "--cli-read-timeout",
                     "--cli-connect-timeout"}
        while k_aw < len(mentah) and mentah[k_aw].startswith("--"):
            if "=" in mentah[k_aw]:
                k_aw += 1
            elif mentah[k_aw] in global_aw:
                k_aw += 2
            else:
                k_aw += 1
        if len(mentah) - k_aw >= 2 and mentah[k_aw] == "s3" \
                and mentah[k_aw + 1] == "cp":
            ops_aw = _operand_sesudah_opsi(
                toks, awal + 2 + k_aw,
                {"--region", "--endpoint-url"})
            if "\x00gantung" in ops_aw:
                return False
            return len(ops_aw) >= 2 and ops_aw[0] == "-"
        return False
    if perintah == "gsutil":
        # Audit 1935: cp dengan sumber '-' mengunggah
        # stdin dan -I membaca daftar URL dari stdin
        # (help resmi gslib, streaming transfers).
        if "-I" in mentah:
            return True
        ops_gu = _operand_sesudah_opsi(
            toks, awal, {"-o", "-h", "-x"})
        if "\x00gantung" in ops_gu:
            return False
        if not ops_gu or ops_gu[0] != "cp":
            return False
        return "-" in ops_gu[1:-1]
    if perintah == "gcloud":
        # Audit 1936: hanya storage cp yang dinilai
        # (cermin gsutil 1935): sumber '-' = unggah
        # stdin, -I membaca daftar dari stdin
        # (dokumentasi cloud.google.com).
        if "-I" in mentah:
            sub_gc = _operand_sesudah_opsi(toks, awal, set())
            return len(sub_gc) >= 2 and sub_gc[:2] == ["storage", "cp"]
        ops_gc = _operand_sesudah_opsi(
            toks, awal, {"--project", "--account"})
        if "\x00gantung" in ops_gc:
            return False
        if len(ops_gc) < 3 or ops_gc[:2] != ["storage", "cp"]:
            return False
        return "-" in ops_gc[2:-1]
    if perintah == "buildah":
        # Audit 1937: build/bud --stdin meneruskan
        # stdin ke kontainer RUN (buildah-build(1)).
        # Audit 2062: opsi global buildah
        # (buildah(1) '[OPTIONS] COMMAND')
        # bernilai - nilainya jangan terhitung
        # operand penentu sub.
        # Audit 2076: lima opsi global bernilai
        # lagi (buildah(1)) ikut dilengkapi.
        ops_ba = _operand_sesudah_opsi(
            toks, awal, {"--root", "--runroot",
                         "--storage-driver",
                         "--registries-conf",
                         "--userns-gid-map",
                         "--userns-uid-map",
                         "--cgroup-manager",
                         "--imagestore", "--log-level",
                         "--registries-conf-dir",
                         "--short-name-alias-conf",
                         "--storage-opt"})
        global_ba = {"--root", "--runroot",
                     "--storage-driver",
                     "--registries-conf",
                     "--registries-conf-dir",
                     "--userns-gid-map",
                     "--userns-uid-map",
                     "--cgroup-manager",
                     "--imagestore", "--log-level",
                     "--short-name-alias-conf",
                     "--storage-opt"}
        if "\x00gantung" in ops_ba:
            return False
        # Audit 2010: '-f -' pada bud/build berarti
        # Containerfile dibaca dari stdin (man).
        # Audit 2021: bentuk merekat '-f-' ikut.
        # Audit 2027: pindai sadar nilai - token
        # yang duduk sebagai nilai opsi lain
        # (--label --stdin) BUKAN saklar.
        fstdin_ba = False
        stdin_ba = False
        nilai_ba = {"--label", "-l", "--tag", "-t",
                    "--build-arg", "--network",
                    "--target", "--platform", "--output",
                    "-o", "--iidfile", "--metadata-file",
                    "--volume", "-v", "--env", "-e",
                    "--add-host", "--cap-add",
                    "--cap-drop", "--isolation",
                    "--sign-by", "--timestamp",
                    "--cgroup-parent", "--runtime",
                    "--from", "--authfile", "--memory",
                    "-m", "--user",
                    "--annotation", "--arch",
                    "--build-arg-file", "--build-context",
                    "--cache-from", "--cache-to",
                    "--cache-ttl", "--cert-dir",
                    "--cgroupns", "--compression-format",
                    "--compression-level", "--cpp-flag",
                    "--cpu-period", "--cpu-quota",
                    "--cpu-shares", "-c", "--cpuset-cpus",
                    "--cpuset-mems", "--creds", "--cw",
                    "--decryption-key", "--device",
                    "--dns", "--dns-option", "--dns-search",
                    "--format", "--group-add",
                    "--hooks-dir", "--iidfile-raw",
                    "--raw-iidfile", "--ignorefile",
                    "--ipc", "--jobs", "--layer-label",
                    "--logfile", "--manifest",
                    "--memory-swap", "--mount", "--net",
                    "--no-cache-filter", "--os",
                    "--os-feature", "--os-version",
                    "--pid", "--retry", "--retry-delay",
                    "--runtime-flag", "--sbom",
                    "--sbom-image-output",
                    "--sbom-image-purl-output",
                    "--sbom-merge-strategy",
                    "--sbom-output", "--sbom-purl-output",
                    "--sbom-scanner-command",
                    "--sbom-scanner-image", "--secret",
                    "--security-opt", "--shm-size",
                    "--source-date-epoch",
                    "--source-policy-file", "--ssh",
                    "--ulimit", "--unsetannotation",
                    "--unsetenv", "--unsetlabel",
                    "--userns", "--userns-gid-map",
                    "--userns-gid-map-group",
                    "--userns-uid-map",
                    "--userns-uid-map-user", "--uts",
                    "--variant"}
        j_ba = 0
        while j_ba < len(mentah):
            t_ba = mentah[j_ba]
            # Audit 2078: nilai opsi GLOBAL juga
            # dilewatkan pindai bendera ini -
            # '--stdin' sebagai nilai --root bukan
            # saklar.
            if t_ba in global_ba:
                j_ba += 2
                continue
            if t_ba in nilai_ba:
                j_ba += 2
                continue
            if any(t_ba.startswith(v + "=")
                   for v in nilai_ba):
                j_ba += 1
                continue
            if t_ba == "--stdin":
                stdin_ba = True
            if t_ba == "--file=-":
                fstdin_ba = True
            if (t_ba.startswith("-f") and len(t_ba) > 2
                    and t_ba[2:].lstrip("=") == "-"):
                fstdin_ba = True
            if (t_ba in ("-f", "--file")
                    and j_ba + 1 < len(mentah)
                    and mentah[j_ba + 1] == "-"):
                fstdin_ba = True
                j_ba += 2
                continue
            j_ba += 1
        return (bool(ops_ba)
                and ops_ba[0] in ("build", "bud")
                and (stdin_ba or fstdin_ba))
    if perintah == "skopeo":
        # Audit 1938: copy dari docker-archive:/dev/stdin
        # membaca aliran stdin (containers-transports(5)).
        ops_sk = _operand_sesudah_opsi(toks, awal, set())
        if "\x00gantung" in ops_sk:
            return False
        return (len(ops_sk) >= 2 and ops_sk[0] == "copy"
                and ops_sk[1].startswith("docker-archive:/dev/stdin"))
    if perintah == "crictl":
        # Audit 1940: exec -i meneruskan stdin ke
        # kontainer (DUGAAN audit-121: blog resmi
        # Kubernetes soal CRI stdin + dokumen fork
        # ccictl; man cri-tools tak menyebut stdin).
        sub_ct = None
        for k_ct, t_ct in enumerate(mentah):
            if t_ct == "exec":
                sub_ct = k_ct
                break
        if sub_ct is None:
            return False
        j_ct = sub_ct + 1
        while j_ct < len(mentah):
            t_ct = mentah[j_ct]
            if t_ct in ("-i", "--stdin"):
                return True
            # Audit 1966: bundel huruf pendek diurai
            # (cri-tools UseShortOptionHandling) -
            # huruf i di bundel tetap -i; huruf asing
            # menggugurkan.
            if (t_ct.startswith("-")
                    and not t_ct.startswith("--")
                    and len(t_ct) > 1):
                asing_ct = False
                for h_ct in t_ct[1:]:
                    if h_ct == "i":
                        return True
                    if h_ct != "t":
                        asing_ct = True
                if asing_ct:
                    return False
                j_ct += 1
                continue
            if t_ct in ("-t", "--tty"):
                j_ct += 1
                continue
            if t_ct == "--timeout":
                j_ct += 2
                continue
            # Audit 1948: opsi tak dikenal = galat
            # parsing crictl sebelum berjalan.
            return False
        return False
    if perintah == "rclone":
        # Audit 1950: rcat menyalin stdin ke berkas
        # jauh; keluarga filter --files-from/
        # --files-from-raw/--filter-from bernilai '-'
        # membaca daftar dari stdin (docs rclone.org).
        # Audit 1958: opsi global rclone bernilai
        # dilompati agar posisi sub-perintah utuh.
        ops_rx = _operand_sesudah_opsi(
            toks, awal, {"--config", "--cache-dir",
                         "--log-file", "--files-from",
                         "--files-from-raw", "--filter-from",
                         "--temp-dir", "--metadata"})
        if "\x00gantung" in ops_rx:
            return False
        # Audit 1957: rcat menuntut tepat satu
        # berkas jauh - tanpa itu galat argumen.
        if ops_rx and ops_rx[0] == "rcat":
            return len(ops_rx) >= 2
        for k_rx, t_rx in enumerate(mentah):
            if t_rx in ("--files-from=-",
                        "--files-from-raw=-",
                        "--filter-from=-"):
                return True
            if (t_rx in ("--files-from", "--files-from-raw",
                         "--filter-from")
                    and k_rx + 1 < len(mentah)
                    and mentah[k_rx + 1] == "-"):
                return True
        return False
    if perintah == "restic":
        # Audit 1951: backup --stdin membaca data
        # dari stdin; --files-from/--files-from-raw
        # bernilai '-' membaca daftar dari stdin
        # (dokumentasi restic). --stdin-from-command
        # sengaja tak dinilai.
        # Audit 1959: opsi global restic bernilai
        # (-r/--repo dll) dilompati.
        ops_re = _operand_sesudah_opsi(
            toks, awal, {"-r", "--repo", "--repo-file",
                         "--password-file", "--cache-dir",
                         "-o", "--option", "--tls-client-cert"})
        if "\x00gantung" in ops_re:
            return False
        if not ops_re or ops_re[0] != "backup":
            return False
        # Audit 1960: bendera bool pflag menerima
        # bentuk =true.
        if "--stdin" in mentah or "--stdin=true" in mentah:
            return True
        for k_re, t_re in enumerate(mentah):
            if t_re in ("--files-from=-",
                        "--files-from-raw=-"):
                return True
            if (t_re in ("--files-from", "--files-from-raw")
                    and k_re + 1 < len(mentah)
                    and mentah[k_re + 1] == "-"):
                return True
        return False
    if perintah == "borg":
        # Audit 1952: create dengan path '-' membaca
        # data dari stdin; --paths-from-stdin membaca
        # daftar path dari stdin (dokumentasi borg).
        ops_bg = _operand_sesudah_opsi(toks, awal, set())
        if "\x00gantung" in ops_bg:
            return False
        if not ops_bg or ops_bg[0] != "create":
            return False
        if "--paths-from-stdin" in mentah:
            return True
        return "-" in ops_bg[1:]
    if perintah == "ipfs":
        # Audit 1953: dag put dan block put membaca
        # masukan dari stdin; add tanpa operand path
        # membaca data dari stdin (sumber Kubo).
        # Audit 1961: opsi global ipfs bernilai
        # (--api/--config) dilompati.
        ops_ip = _operand_sesudah_opsi(
            toks, awal, {"--stdin-name", "-f", "--file",
                         "--api", "--config", "-c",
                         "--mount", "-v", "--json"})
        if "\x00gantung" in ops_ip:
            return False
        # Audit 1962/1963: dag put/block put membaca
        # stdin hanya TANPA argumen berkas - dengan
        # berkas, masukan dari berkas itu.
        if ops_ip[:2] in (["dag", "put"], ["block", "put"]):
            return len(ops_ip) == 2
        if ops_ip and ops_ip[0] == "add":
            return len(ops_ip) == 1
        return False
    if perintah == "nano":
        # Audit 1968: nama berkas '-' berarti nano
        # membaca data dari stdin (manual GNU nano).
        return "-" in mentah
    if perintah == "gnuplot":
        # Audit 1969: tanpa berkas muatan gnuplot
        # membaca perintah dari stdin; nama berkas
        # '-' juga memaksa stdin (dokumentasi resmi).
        # Audit 1977: --persist bendera polos, bukan
        # bernilai - ia disaring dari operand.
        ops_gp = _operand_sesudah_opsi(
            toks, awal, {"-e", "-c"})
        ops_gp = [o_gp for o_gp in ops_gp
                  if o_gp not in ("--persist", "-p")]
        if "\x00gantung" in ops_gp:
            return False
        return not ops_gp or "-" in ops_gp
    if perintah == "kak":
        # Audit 1970: mode filter -f memproses stdin
        # dan -p mengirim perintah dari stdin ke
        # sesi (man kak(1)).
        return "-f" in mentah or "-p" in mentah
    if perintah == "micro":
        # Audit 1971: tanpa operand berkas micro
        # membuat buffer dari stdin saat terpipa
        # (README resmi micro).
        ops_mc = _operand_sesudah_opsi(toks, awal, set())
        if "\x00gantung" in ops_mc:
            return False
        return not ops_mc
    if perintah == "pandoc":
        # Audit 1972: tanpa berkas masukan pandoc
        # membaca dari stdin (Pandoc User's Guide).
        ops_pd = _operand_sesudah_opsi(
            toks, awal, {"-f", "--from", "-t", "--to",
                         "-o", "--output", "--template",
                         "--metadata-file", "-M",
                         "--metadata", "-V", "--variable",
                         "--defaults", "--reference-doc"})
        if "\x00gantung" in ops_pd:
            return False
        # Audit 1979: operand '-' adalah stdin
        # (sumber pandoc: readSource '-' = stdin).
        return not ops_pd or "-" in ops_pd
    if perintah in ("magick", "convert", "identify"):
        # Audit 1973: nama semu '-' / 'fmt:-' / 'fd:0'
        # di posisi MASUKAN berarti stdin
        # (dokumentasi ImageMagick); operand terakhir
        # adalah keluaran (stdout), bukan masukan.
        # Audit 1983: opsi bernilai ImageMagick
        # dilompati; sub-perintah magick (identify
        # dll) menempati slot pertama; identify tak
        # punya operand keluaran.
        ops_im = _operand_sesudah_opsi(
            toks, awal, {"-format", "-define", "-size",
                         "-quality", "-resize", "-crop",
                         "-rotate", "-density", "-depth",
                         "-background", "-fill", "-stroke",
                         "-font", "-pointsize", "-gravity",
                         "-channel", "-type", "-colorspace",
                         "-profile", "-sampling-factor",
                         "-extent", "-geometry", "-page",
                         "-delay", "-loop", "-label",
                         "-comment", "-set"})
        if "\x00gantung" in ops_im:
            return False
        sub_im = perintah == "identify"
        if (perintah == "magick" and ops_im
                and ops_im[0] in ("identify", "stream",
                                  "convert", "mogrify",
                                  "montage", "compare",
                                  "composite", "conjure",
                                  "import", "display",
                                  "animate")):
            sub_im = ops_im[0] == "identify"
            ops_im = ops_im[1:]
        masuk_im = (ops_im if sub_im
                    else (ops_im[:-1] if len(ops_im) >= 2
                          else ops_im))
        for t_im in masuk_im:
            if (t_im == "-" or t_im.endswith(":-")
                    or t_im == "fd:0"):
                return True
        return False
    if perintah == "sendmail":
        # Audit 1974: mode kirim (polos, -t, alamat)
        # membaca pesan dari stdin (man sendmail(8));
        # mode antrean/versi/verifikasi tidak.
        # Audit 1984: pemeriksaan mode sadar posisi
        # - nilai opsi bernilai (-F/-f/-o/dll) yang
        # kebetulan berbunyi mode bukan mode.
        i_sm = 0
        while i_sm < len(mentah):
            t_sm = mentah[i_sm]
            if t_sm in ("-F", "-f", "-o", "-O", "-N",
                        "-R", "-h"):
                i_sm += 2
                continue
            if t_sm in ("-bp", "-V", "-bv", "-bP"):
                return False
            i_sm += 1
        return True
    if perintah in ("mailx", "s-nail", "mail"):
        # Audit 1975: mode kirim (operand alamat)
        # membaca isi surat dari stdin; mode baca
        # (-f kotak) tidak (POSIX mailx + s-nail(1)).
        # Audit 1978: mode -t membaca seluruh pesan
        # dari stdin (penerima dari header).
        if "-t" in mentah:
            return True
        if "-f" in mentah:
            return False
        ops_mx = _operand_sesudah_opsi(
            toks, awal, {"-s", "-c", "-b", "-a", "-r",
                         "-S", "-A", "-m", "-T"})
        if "\x00gantung" in ops_mx:
            return False
        return len(ops_mx) >= 1
    if perintah == "R":
        # Audit 1980: R CMD menjalankan sub-perintah
        # perkakas (BATCH membaca berkas), bukan R
        # interaktif.
        # Audit 2014 (KOREKSI 2000): gelung opsi
        # skrip BATCH menelan '-' sebagai opsi -
        # 'R CMD BATCH -' berakhir galat, BUKAN
        # stdin (terprobe dua shell atas gelung
        # resmi). Audit 2015: sesudah '--' infile
        # '-' utuh -> perintahnya 'R -f -' (stdin).
        # Audit 2041: opsi BATCH boleh mendahului
        # '--' (skrip resmi) - yang menentukan
        # token pertama SESUDAH '--'.
        if mentah[:2] == ["CMD", "BATCH"] and "--" in mentah:
            sesudah_rb = mentah.index("--") + 1
            if (sesudah_rb < len(mentah)
                    and mentah[sesudah_rb] == "-"):
                return True
        if mentah[:1] == ["CMD"]:
            return False
        # Audit 1976: -f '-' berarti stdin; tanpa
        # -f/-e R membaca dari stdin saat terpipa
        # (manual resmi R, Lampiran B.1).
        for k_r, t_r in enumerate(mentah):
            if t_r in ("-e", "--expr"):
                return False
            if t_r in ("-f", "--file"):
                return (k_r + 1 < len(mentah)
                        and mentah[k_r + 1] == "-")
            if t_r == "--file=-":
                return True
        return True
    if perintah in ("kafka-console-producer",
                    "kafka-console-producer.sh"):
        # Audit 1985: producer membaca baris pesan
        # dari stdin untuk dikirim (bantuan resmi).
        return True
    if perintah == "duckdb":
        # Audit 1986 + 1994-1999: ejaan resmi DuckDB
        # strip TUNGGAL. Penjalan lalu keluar: -c/-s
        # (perintah), -f (skrip), -manual. -cmd
        # berjalan SEBELUM membaca stdin (tak
        # menekan). Opsi bernilai resmi: -init,
        # -cmd, -separator, -newline, -nullvalue,
        # -storage-version.
        # Audit 2016/2017: pindai penekan sadar
        # posisi - bendera info strip-tunggal
        # (-h/-help/-version), penekan -no-stdin,
        # dan penekan yang duduk sebagai NILAI
        # opsi (-cmd -c) tidak lagi tertukar.
        bernilai_dd = {"-init", "--init", "-cmd",
                       "-separator", "-newline",
                       "-nullvalue", "--nullvalue",
                       "-storage-version",
                       "-c", "-s", "-f"}
        j_dd = 0
        while j_dd < len(mentah):
            t_dd = mentah[j_dd]
            if t_dd in ("-c", "-s", "-f", "-manual",
                        "-h", "-help", "-version",
                        "-no-stdin"):
                return False
            if t_dd in bernilai_dd:
                j_dd += 2
                continue
            if ("=" in t_dd
                    and t_dd.split("=", 1)[0]
                    in bernilai_dd):
                j_dd += 1
                continue
            j_dd += 1
        ops_dd = _operand_sesudah_opsi(
            toks, awal, {"-init", "--init", "-cmd",
                         "-separator", "-newline",
                         "-nullvalue", "--nullvalue",
                         "-storage-version"})
        if "\x00gantung" in ops_dd:
            return False
        return len(ops_dd) <= 1
    if perintah == "pg_restore":
        # Audit 1987: tanpa nama berkas arsip,
        # pg_restore membaca dari stdin
        # (dokumentasi PostgreSQL).
        ops_pr = _operand_sesudah_opsi(
            toks, awal, {"-d", "--dbname", "-f",
                         "--file", "-h", "--host", "-p",
                         "--port", "-U", "--username",
                         "-j", "--jobs", "-L", "--use-list",
                         "-F", "--format", "-t", "--table",
                         "-n", "--schema", "-P",
                         "--trigger"})
        if "\x00gantung" in ops_pr:
            return False
        return not ops_pr
    if perintah == "nats":
        # Audit 1988: pub/request dengan subjek saja
        # membaca body pesan dari stdin (README
        # resmi natscli).
        ops_nt = _operand_sesudah_opsi(
            toks, awal, {"--server", "-s", "--user",
                         "--password", "--creds",
                         "--nkey", "--cert", "--key",
                         "--ca", "--context", "--timeout",
                         "--count", "--header", "-H"})
        if "\x00gantung" in ops_nt:
            return False
        return (len(ops_nt) == 2
                and ops_nt[0] in ("pub", "request", "req"))
    if perintah == "cypher-shell":
        # Audit 1989: polos terpipa membaca
        # pernyataan Cypher dari stdin (manual
        # Neo4j); argumen pernyataan / -f menekan.
        if "-f" in mentah or "--file" in mentah:
            return False
        ops_cs = _operand_sesudah_opsi(
            toks, awal, {"-a", "--address", "-u",
                         "--username", "-p", "--password",
                         "--database", "--format", "-P",
                         "--param", "--encryption"})
        if "\x00gantung" in ops_cs:
            return False
        return not ops_cs
    if perintah == "cqlsh":
        # Audit 1990: polos tanpa --execute/--file
        # masuk mode interaktif yang membaca stdin
        # saat terpipa (dokumentasi DataStax/Scylla).
        if any(t_cq in ("-e", "--execute", "-f",
                        "--file") for t_cq in mentah):
            return False
        return True
    if perintah in ("clickhouse", "clickhouse-local"):
        # Audit 1991: bentuk multicall - 'clickhouse
        # client' adalah clickhouse-client (semantik
        # 1941) dan 'clickhouse local'/'clickhouse-
        # local' membaca data dari stdin bawaan.
        if perintah == "clickhouse-local":
            return True
        ops_ch = _operand_sesudah_opsi(toks, awal, set())
        return bool(ops_ch) and ops_ch[0] in ("client", "local")
    if perintah == "snowsql":
        # Audit 1992: polos terpipa berjalan batch
        # lewat stdin (dokumentasi Snowflake); -q
        # dan -f menekan (-f - tak diklaim).
        if any(t_sq in ("-q", "--query", "-f",
                        "--filename", "--file")
               for t_sq in mentah):
            return False
        return True
    if perintah == "az":
        # Audit 2011: az rest --body @- mengambil
        # body dari pipa (mekanisme '@-' Azure CLI
        # menurut Microsoft Learn; DUGAAN kuat).
        ops_az = _operand_sesudah_opsi(
            toks, awal, {"--output", "-o", "--query",
                         "--subscription", "--resource-group",
                         "-g", "--name", "-n", "--body",
                         "--headers", "--uri", "--method",
                         "-m", "--uri-parameters"})
        if "\x00gantung" in ops_az:
            return False
        if not ops_az or ops_az[0] != "rest":
            return False
        for k_az, t_az in enumerate(mentah):
            if t_az == "--body=@-":
                return True
            if (t_az == "--body" and k_az + 1 < len(mentah)
                    and mentah[k_az + 1] == "@-"):
                return True
        return False
    if perintah == "vagrant":
        # Audit 2012: vagrant ssh adalah sesi SSH
        # interaktif (meneruskan stdin seperti ssh
        # polos; dokumentasi HashiCorp, DUGAAN).
        # Audit 2024: -h adalah bantuan (dok
        # HashiCorp). Audit 2025: argumen sesudah
        # '--' diteruskan ke ssh - '-n' di sana
        # mengalihkan stdin dari /dev/null.
        # Audit 2046: -h yang duduk sebagai NILAI
        # -c/--command bukan bantuan - pindai
        # sadar nilai.
        j_vg = 0
        while j_vg < len(mentah):
            if mentah[j_vg] in ("-c", "--command"):
                j_vg += 2
                continue
            if mentah[j_vg] == "-h":
                return False
            j_vg += 1
        ops_vg = _operand_sesudah_opsi(toks, awal, set())
        if not ops_vg or ops_vg[0] != "ssh":
            return False
        if "--" in mentah:
            sesudah_vg = mentah[mentah.index("--") + 1:]
            if "-n" in sesudah_vg:
                return False
        return True
    if perintah == "minikube":
        # Audit 2013: minikube ssh adalah sesi SSH
        # interaktif (kelas sama vagrant ssh/ssh;
        # DUGAAN kuat).
        # Audit 2022: opsi warisan minikube
        # -p/--profile bernilai (dok resmi) -
        # nilainya jangan terhitung operand.
        # Audit 2023: -h adalah bantuan.
        if "-h" in mentah:
            return False
        # Audit 2045: opsi warisan bernilai lain
        # dari halaman resmi minikube ssh
        # (-b/--bootstrapper, --user, --log_dir).
        # Audit 2063: opsi warisan glog bernilai
        # (-v/--v, --vmodule, ambang log) ikut
        # dilengkapi dari daftar resmi.
        ops_mk = _operand_sesudah_opsi(
            toks, awal, {"-p", "--profile", "-b",
                         "--bootstrapper", "--user",
                         "--log_dir", "--log_file",
                         "--alsologtostderrthreshold",
                         "--log_backtrace_at",
                         "--log_file_max_size",
                         "--stderrthreshold", "-v",
                         "--v", "--vmodule"})
        return bool(ops_mk) and ops_mk[0] == "ssh"
    if perintah == "mpv":
        # Audit 2034: operand '-' tunggal berarti
        # data media dibaca dari stdin (man mpv).
        ops_mv = _operand_sesudah_opsi(
            toks, awal, {"--playlist", "--audio-file",
                         "--sub-file", "--config-dir",
                         "--input-file", "--log-file",
                         "--o", "--of", "--oac", "--ovc",
                         "--alang", "--slang", "--start",
                         "--speed", "--title"})
        if "\x00gantung" in ops_mv:
            return False
        return "-" in ops_mv
    if perintah in ("sox", "play"):
        # Audit 2035: nama berkas khusus '-'
        # sebagai masukan PERTAMA = membaca data
        # audio dari stdin (man sox); '-' di
        # posisi keluaran adalah stdout.
        ops_sx = _operand_sesudah_opsi(
            toks, awal, {"-c", "-r", "-b", "-e", "-t",
                         "-v", "--buffer",
                         "--input-buffer", "-o"})
        if "\x00gantung" in ops_sx:
            return False
        return bool(ops_sx) and ops_sx[0] == "-"
    if perintah == "tesseract":
        # Audit 2036: masukan 'stdin' atau '-'
        # berarti gambar dibaca dari stdin
        # (man tesseract).
        ops_ts = _operand_sesudah_opsi(
            toks, awal, {"--psm", "--oem", "-l", "-c",
                         "--dpi", "--tessdata-dir",
                         "--user-words",
                         "--user-patterns"})
        if "\x00gantung" in ops_ts:
            return False
        return bool(ops_ts) and ops_ts[0] in ("stdin", "-")
    if perintah == "guix":
        # Audit 2037: 'guix repl' tanpa berkas
        # membaca dari stdin secara bawaan
        # (manual Guix); --listen menggantikannya
        # dengan server (tepat False).
        if any(t_gx.startswith("--listen")
               for t_gx in mentah):
            return False
        # Audit 2047: opsi repl bernilai
        # -t/--type dan -L/--load-path (man
        # guix-repl) jangan terhitung operand.
        ops_gx = _operand_sesudah_opsi(
            toks, awal, {"-t", "--type", "-L",
                         "--load-path"})
        return ops_gx == ["repl"]
    if perintah == "nix":
        # Audit 2038: flag stdin resmi Nix -
        # 'repl --stdin' (installables dari
        # stdin), '--arg-from-stdin' (isi stdin
        # sebagai argumen), '-f -' (ekspresi
        # dari stdin). 'nix repl' polos TERTAHAN.
        for k_nx, t_nx in enumerate(mentah):
            if t_nx in ("--stdin", "--arg-from-stdin"):
                return True
            if (t_nx in ("-f", "--file")
                    and k_nx + 1 < len(mentah)
                    and mentah[k_nx + 1] == "-"):
                return True
            if t_nx == "--file=-":
                return True
        return False
    if perintah == "gst-launch-1.0":
        # Audit 2040: elemen fdsrc dengan fd=0
        # membaca dari stdin (gst-inspect: fdsrc
        # "Read from a file descriptor"; fd 0 =
        # stdin POSIX). Tanpa fd TERTAHAN
        # (bawaan -1 tak terdokumentasi).
        return "fdsrc" in mentah and "fd=0" in mentah
    if perintah == "irb":
        # Audit 2052: IRB mengeksekusi ekspresi
        # Ruby yang dibaca dari stdin (RDoc
        # resmi); operand berkas = skrip.
        ops_ir = _operand_sesudah_opsi(
            toks, awal, {"-r", "-I", "-E",
                         "--prompt", "--echo-limit"})
        if "\x00gantung" in ops_ir:
            return False
        return not ops_ir
    if perintah == "litecli":
        # Audit 2053: bila stdin bukan TTY,
        # litecli membaca seluruh stdin lalu
        # menjalankannya sebagai kueri (sumber
        # resmi main.py); -e menekan.
        return "-e" not in mentah and "--execute" not in mentah
    if perintah == "mycli":
        # Audit 2054: mycli masuk modus batch
        # dari stdin bila stdin bukan TTY
        # (sumber resmi cli_runner.py);
        # -e/--execute menekan.
        return "-e" not in mentah and "--execute" not in mentah
    if perintah == "usql":
        # Audit 2055: masukan usql bisa berasal
        # dari pipa dan dikirim di EOF seperti
        # psql (sumber resmi handler.go);
        # -c/--command menekan.
        return "-c" not in mentah and "--command" not in mentah
    if perintah == "helm":
        # Audit 2056: values '-' pada -f/--values
        # dibaca dari stdin (sumber resmi helm:
        # readFile memuat stdin untuk '-').
        for k_hm, t_hm in enumerate(mentah):
            if (t_hm in ("-f", "--values")
                    and k_hm + 1 < len(mentah)
                    and mentah[k_hm + 1] == "-"):
                return True
            if t_hm in ("--values=-", "-f=-"):
                return True
        return False
    if perintah == "sbcl":
        # Audit 2057: REPL SBCL berjalan di atas
        # stdin (sbcl(1)); --script dan
        # --non-interactive menekan REPL.
        # Audit 2064: --quit juga menekan REPL
        # (sbcl(1): keluar sesudah pemrosesan
        # opsi toplevel).
        return ("--script" not in mentah
                and "--non-interactive" not in mentah
                and "--quit" not in mentah)
    if perintah == "racket":
        # Audit 2058: polos menambahkan REPL
        # secara efektif dan '-f -' membaca
        # ekspresi dari stdin (Racket
        # Reference); -e/operand berkas menekan.
        if any(t_rk in ("-e", "--eval", "-t",
                        "--require", "-u",
                        "--require-script")
               for t_rk in mentah):
            return False
        for k_rk, t_rk in enumerate(mentah):
            if (t_rk in ("-f", "--load")
                    and k_rk + 1 < len(mentah)
                    and mentah[k_rk + 1] == "-"):
                return True
        ops_rk = _operand_sesudah_opsi(
            toks, awal, {"-f", "--load", "-l",
                         "--lib", "-p", "--package"})
        if "\x00gantung" in ops_rk:
            return False
        return not ops_rk
    if perintah == "parallel":
        # Audit 2085: modus --semaphore menjalankan
        # perintah dari argv; stdin bukan sumber
        # masukan (parallel(1) Synopsis).
        # Audit 2094: --semaphore hanya modus bila
        # duduk SEBELUM token perintah pertama -
        # sesudah perintah ia argumen biasa bagi
        # perintah itu.
        # Audit 2104: himpunan dilengkapi dari
        # parallel(1) - 17 opsi bernilai lagi.
        nilai_pl = {"--jobs", "-j", "-a", "--arg-file",
                    "--joblog", "--semaphorename",
                    "--tagstring", "--tmpdir",
                    "--max-args", "-n", "--max-procs",
                    "-P", "--sshlogin", "--sshloginfile",
                    "--env", "--workdir", "--results",
                    "--timeout", "--delay", "--block",
                    "--header:", "--colsep",
                    "--delimiter", "--return",
                    "--transferfile", "--basefile",
                    "--semaphoretimeout"}
        k_sem = 0
        modus_sem = False
        while k_sem < len(mentah):
            t_sem = mentah[k_sem]
            if t_sem == "--semaphore":
                modus_sem = True
                break
            # Audit 2105: --semaphorename/--id
            # MENYIRATKAN semaphore (parallel(1)).
            if t_sem in ("--semaphorename", "--id"):
                modus_sem = True
                break
            if t_sem in nilai_pl:
                k_sem += 2
                continue
            if t_sem.startswith("-"):
                k_sem += 1
                continue
            break
        if modus_sem:
            return False
        # Audit 2069: GNU parallel membangun
        # baris perintah dari stdin (parallel(1));
        # argumen ':::' atau berkas -a/--arg-file
        # menggantikan stdin.
        if ":::" in mentah:
            return False
        for k_pl, t_pl in enumerate(mentah):
            if (t_pl in ("-a", "--arg-file")
                    and k_pl + 1 < len(mentah)
                    and mentah[k_pl + 1] != "-"):
                return False
        return True
    if perintah == "entr":
        # Audit 2070: daftar berkas yang diawasi
        # dibaca dari stdin (man entr) - selalu
        # menguras.
        return True
    if perintah == "pv":
        # Audit 2071: tanpa operand berkas (atau
        # operand '-') pv membaca stdin (pv(1)).
        # Audit 2081: enam opsi bernilai pv lagi
        # dari pv(1) ikut dilengkapi.
        # Audit 2102: -d/--watchfd, -R/--remote,
        # -Q/--query bernilai dan modusnya memantau
        # proses lain - tak pernah menyalin stdin;
        # bentuk menggantung pun ditolak getopt
        # (pv 1.12.7).
        for t_pv2 in mentah:
            if (t_pv2 in ("-d", "--watchfd", "-R",
                          "--remote", "-Q", "--query")
                    or t_pv2.startswith(("--watchfd=",
                                          "--remote=",
                                          "--query="))
                    or (len(t_pv2) > 2
                        and not t_pv2.startswith("--")
                        and t_pv2[1] in "dRQ")):
                return False
        ops_pv = _operand_sesudah_opsi(
            toks, awal, {"-L", "--rate-limit", "-B",
                         "--buffer-size", "-s", "--size",
                         "-i", "--interval", "-N", "--name",
                         "-A", "--last-written", "-F",
                         "--format", "-D", "--delay-start",
                         "-w", "--width", "-H", "--height",
                         "-P", "--pidfile",
                         "-o", "--output", "-u",
                         "--bar-style", "-x",
                         "--extra-display", "-J",
                         "--pipe-buffer-size", "-m",
                         "--average-rate-window", "-Z",
                         "--error-skip-block", "-U",
                         "--store-and-forward", "-M",
                         "--monitor"})
        if "\x00gantung" in ops_pv:
            return False
        # Audit 2093: modus -M/--monitor menjalankan
        # perintah sisa sebagai pipa - stdin luar
        # mengalir lewat pv apa pun operandnya
        # (contoh resmi pv(1)).
        if any(t_pv3 == "-M"
               or t_pv3.startswith("--monitor")
               or (len(t_pv3) > 2
                   and t_pv3.startswith("-M"))
               for t_pv3 in mentah):
            return True
        return all(o_pv == "-" for o_pv in ops_pv)
    if perintah == "rev":
        # Audit 2073: '-' bagi rev adalah NAMA
        # BERKAS harfiah (terprobe: 'cannot open
        # -'), bukan stdin - hanya bentuk tanpa
        # operand yang membaca stdin.
        ops_rv = _operand_sesudah_opsi(toks, awal, set())
        if "\x00gantung" in ops_rv:
            return False
        return not ops_rv
    if perintah == "xonsh":
        # Audit 2072: masukan terpipa masuk mode
        # script-from-stdin (dok resmi xon.sh);
        # -c dan operand skrip menekan.
        if "-c" in mentah or "--command" in mentah:
            return False
        # Audit 2082: --rc bernilai JAMAK - semua
        # token non-opsi sesudahnya berkas rc
        # sampai opsi/'--' berikutnya; operand
        # sesudah '--' barulah skrip.
        sisa_xn = []
        k_xn = 0
        while k_xn < len(mentah):
            t_xn = mentah[k_xn]
            # Audit 2095: -D terpisah juga bernilai
            # (xonsh(1) '[-D ITEM]') - definisinya
            # dilewatkan, bukan skrip.
            if t_xn == "-D":
                if k_xn + 1 >= len(mentah):
                    return False
                k_xn += 2
                continue
            # Audit 2083: -st/--shell-type bernilai
            # (xonsh(1)) - nilainya dilewatkan.
            if t_xn in ("-st", "--shell-type"):
                if k_xn + 1 >= len(mentah):
                    return False
                k_xn += 2
                continue
            if t_xn == "--rc":
                if k_xn + 1 >= len(mentah):
                    return False
                k_xn += 1
                while (k_xn < len(mentah)
                       and not mentah[k_xn].startswith("-")):
                    k_xn += 1
                continue
            if t_xn == "--":
                sisa_xn.extend(mentah[k_xn + 1:])
                break
            if not t_xn.startswith("-"):
                sisa_xn.append(t_xn)
            k_xn += 1
        return not sisa_xn
    if perintah == "exiftool":
        # Audit 2086: operand '-' membaca stdin
        # (dok resmi exiftool); tanpa operand ia
        # menunggu nama berkas, bukan stdin.
        ops_xf = _operand_sesudah_opsi(
            toks, awal, {"-config", "-o", "-w",
                         "-ext", "-if", "-fileOrder",
                         "-api"})
        if "\x00gantung" in ops_xf:
            return False
        return "-" in ops_xf
    if perintah == "factor":
        # Audit 2091: tanpa operand ia membaca
        # angka dari stdin (coreutils); operand
        # '-' BUKAN stdin - galat angka tak sah
        # (terprobe).
        ops_fc = _operand_sesudah_opsi(toks, awal, set())
        if "\x00gantung" in ops_fc:
            return False
        return not ops_fc
    if perintah == "groff":
        # Audit 2089: tanpa operand berkas (atau
        # operand '-') groff membaca stdin
        # (groff(1)).
        ops_gf = _operand_sesudah_opsi(
            toks, awal, {"-T", "-K", "-m", "-n",
                         "-r", "-D"})
        if "\x00gantung" in ops_gf:
            return False
        return not ops_gf or "-" in ops_gf
    if perintah == "in2csv":
        # Audit 2087: tanpa nama berkas ia
        # menerima data terpipa lewat STDIN
        # (dok csvkit).
        # Audit 2096: nilai format '-' ditolak
        # argparse csvkit (pilihan tertutup)
        # sebelum stdin dibaca.
        # Audit 2106: nilai mirip-opsi adalah
        # jebakan argparse (galat sebelum stdin).
        if _jebakan_argparse(
                mentah, {"-f", "--format", "-s",
                         "--schema", "-k", "--key",
                         "-y", "--snifflimit",
                         "-d", "--delimiter", "-e",
                         "--encoding", "--sheet",
                         "--write-sheets",
                         "--encoding-xls"}):
            return False
        # Audit 2410: awalan ambigu = galat
        # argparse (--enc ~ encoding/encoding-xls).
        semesta_i2 = {"--format", "--schema",
                      "--key", "--snifflimit",
                      "--delimiter", "--encoding",
                      "--sheet", "--write-sheets",
                      "--encoding-xls"}
        # Audit 2425: calon bool ikut
        # dihitung (in2csv tak memakai
        # _operand_csvkit) - ambigu lintas
        # himpunan atau informasional bool
        # tunggal = berhenti.
        bool_i2 = _CSVKIT_BOOL | {
            "--no-doublequote", "--no-inference",
            "--names", "--use-sheet-names",
            "--reset-dimensions"}
        # Audit 2430: token persis -- adalah
        # pemisah, bukan calon opsi.
        mentah_gi2 = (mentah[:mentah.index("--")]
                      if "--" in mentah else mentah)
        for t_i2 in mentah_gi2:
            dasar_i2 = t_i2.split("=", 1)[0]
            if (not t_i2.startswith("--")
                    or dasar_i2 in semesta_i2):
                continue
            cv_i2 = [b_i2 for b_i2 in semesta_i2
                     if b_i2.startswith(dasar_i2)]
            cb_i2 = [b_i2 for b_i2 in bool_i2
                     if b_i2.startswith(dasar_i2)]
            if (len(cv_i2) + len(cb_i2) > 1
                    or (not cv_i2 and len(cb_i2) == 1
                        and cb_i2[0] in ("--help",
                                         "--version"))
                    or ("=" in t_i2 and not cv_i2
                        and cb_i2)):
                return False
        for t_i2 in mentah:
            if (t_i2 == "-f-"
                    or t_i2.startswith("--format=-")):
                return False
        for k_i2, t_i2 in enumerate(mentah):
            if t_i2 in ("-f", "--format"):
                if (k_i2 + 1 < len(mentah)
                        and mentah[k_i2 + 1] == "-"):
                    return False
        # Audit 2377: operand dihitung lokal
        # sadar bundel - huruf bernilai di
        # tengah bundel menelan sisa token
        # atau token berikutnya.
        ops_i2 = []
        i_oi2 = 0
        while i_oi2 < len(mentah):
            t_oi2 = mentah[i_oi2]
            if t_oi2 == "--":
                ops_i2.extend(mentah[i_oi2 + 1:])
                break
            if t_oi2 in ("-f", "--format", "-s",
                         "--schema", "-k", "--key",
                         "-y", "--snifflimit",
                         "-d", "--delimiter", "-e",
                         "--encoding", "--sheet",
                         "--write-sheets",
                         "--encoding-xls"):
                if i_oi2 + 1 >= len(mentah):
                    ops_i2.append("\x00gantung")
                    break
                i_oi2 += 2
                continue
            if (t_oi2.startswith("--")
                    and "=" in t_oi2):
                i_oi2 += 1
                continue
            if (t_oi2.startswith("-")
                    and not t_oi2.startswith("--")
                    and len(t_oi2) > 1):
                j_oi2 = 1
                lompat_oi2 = False
                while j_oi2 < len(t_oi2):
                    if t_oi2[j_oi2] in "fskydequpzLK":
                        if j_oi2 == len(t_oi2) - 1:
                            lompat_oi2 = True
                        break
                    if t_oi2[j_oi2] not in "tnHISblv":
                        # Audit 2387: huruf tak
                        # dikenal = galat argparse.
                        ops_i2.append("\x00gantung")
                        break
                    j_oi2 += 1
                if lompat_oi2:
                    if i_oi2 + 1 >= len(mentah):
                        ops_i2.append("\x00gantung")
                        break
                    i_oi2 += 2
                else:
                    i_oi2 += 1
                continue
            if t_oi2.startswith("-") and t_oi2 != "-":
                i_oi2 += 1
                continue
            ops_i2.append(t_oi2)
            i_oi2 += 1
        if "\x00gantung" in ops_i2:
            return False
        # Audit 2431: bentuk tanpa operand
        # ikut divalidasi 2362/2363 di bawah
        # (biner menolak format tak dikenal /
        # names non-Excel sebelum membaca).
        if ops_i2 and ops_i2 != ["-"]:
            return False
        # Audit 2362: nilai -f wajib anggota
        # SUPPORTED_FORMATS in2csv.py - di luar
        # itu argparse menolak sebelum membaca.
        # Audit 2363: -n/--names hanya sah bagi
        # format Excel (xls/xlsx).
        format_i2 = None
        i_fi2 = 0
        while i_fi2 < len(mentah):
            t_fi2 = mentah[i_fi2]
            if t_fi2 == "--":
                break
            if t_fi2 in ("-f", "--format"):
                if i_fi2 + 1 < len(mentah):
                    format_i2 = mentah[i_fi2 + 1]
                i_fi2 += 2
                continue
            if t_fi2.startswith("--format="):
                format_i2 = t_fi2.split("=", 1)[1]
            elif (t_fi2.startswith("-f")
                    and len(t_fi2) > 2):
                format_i2 = t_fi2[2:]
            elif t_fi2 in ("-s", "--schema", "-k",
                           "--key"):
                format_i2 = "skema"
                i_fi2 += 2
                continue
            elif t_fi2.startswith(("--schema=",
                                   "--key=")):
                format_i2 = "skema"
            elif (t_fi2.startswith("-")
                    and not t_fi2.startswith("--")
                    and len(t_fi2) > 2):
                # Audit 2377: -f di dalam bundel
                # sesudah huruf bool ikut terbaca.
                for j_fi2 in range(1, len(t_fi2)):
                    h_fi2 = t_fi2[j_fi2]
                    if h_fi2 == "f":
                        sisa_fi2 = t_fi2[j_fi2 + 1:]
                        if sisa_fi2:
                            format_i2 = sisa_fi2
                        elif i_fi2 + 1 < len(mentah):
                            format_i2 = mentah[i_fi2 + 1]
                        break
                    if h_fi2 in "skydequpzLK":
                        break
            i_fi2 += 1
        if format_i2 is None:
            return False
        if (format_i2 != "skema"
                and format_i2 not in ("csv", "dbf",
                                      "fixed",
                                      "geojson",
                                      "json", "ndjson",
                                      "xls", "xlsx")):
            return False
        # Audit 2372: -n di dalam bundel huruf
        # pendek ikut dikembangkan argparse.
        # Audit 2373: --names=<x> ditolak
        # argparse (store_true berargumen).
        ada_ni2 = False
        for t_ni2 in mentah:
            if t_ni2 == "--":
                break
            if t_ni2.startswith("--names="):
                return False
            if t_ni2 == "--names":
                ada_ni2 = True
            elif (t_ni2.startswith("--")
                    and len(t_ni2) >= 4
                    and "--names".startswith(t_ni2)):
                # Audit 2438: singkatan awalan
                # unik --names ikut dikenali.
                ada_ni2 = True
            elif (t_ni2.startswith("-")
                    and not t_ni2.startswith("--")):
                for huruf_ni2 in t_ni2[1:]:
                    if huruf_ni2 == "n":
                        ada_ni2 = True
                        break
                    if huruf_ni2 in "fskydequpzLK":
                        break
        if ada_ni2 and format_i2 not in ("xls", "xlsx"):
            return False
        return True
    if perintah == "units":
        # Audit 2088: tanpa argumen non-opsi ia
        # membaca pasangan HAVE/WANT dari stdin
        # (GNU Units Manual).
        ops_un = _operand_sesudah_opsi(
            toks, awal, {"-f", "--file"})
        if "\x00gantung" in ops_un:
            return False
        return not ops_un
    if perintah == "qsv":
        # Audit 2107 (koreksi 2098): sub 'cat'
        # mewajibkan sub-subperintah rows/rowskey/
        # columns (USAGE cat.rs) - tanpa itu parse
        # galat dan stdin tak tersentuh. Dengan
        # sub-subperintah, masukan kosong/'-'
        # membaca stdin (config.rs).
        # Audit 2108: 'count' memakai Config yang
        # sama seperti cat (None/'-' -> stdin).
        # Audit 2424/2429: token
        # strip-angka di subperintah
        # lain juga flag tak dikenal
        # docopt (peta menyeluruh).
        bernilai_angka_qsv = {
            "apply": {"--addl-props", "--api-key", "--base-url", "--batch", "--cache-dir", "--comparand", "--delimiter", "--formatstr", "--jobs", "--max-tokens", "--model", "--new-column", "--on-error", "--output", "--prompt", "--prompt-file", "--rate-limit", "--rename", "--replacement", "--timeout", "--user-agent", "-C", "-R", "-b", "-c", "-d", "-f", "-j", "-k", "-m", "-o", "-r", "-t", "-u"},
            "behead": {"--output", "-o"},
            "blake3": {"--derive-key", "--jobs", "--length", "--output", "-j", "-l", "-o"},
            "cat": {"--delimiter", "--group", "--group-name", "--output", "-N", "-d", "-g", "-o"},
            "color": {"--delimiter", "--output", "--title", "-d", "-o", "-t"},
            "datefmt": {"--batch", "--default-tz", "--delimiter", "--formatstr", "--input-tz", "--jobs", "--new-column", "--output", "--output-tz", "--rename", "--ts-resolution", "-R", "-b", "-c", "-d", "-f", "-j", "-o", "-r"},
            "dedup": {"--delimiter", "--dupes-output", "--jobs", "--output", "--select", "-D", "-d", "-j", "-o", "-s"},
            "describegpt": {"--addl-cols-list", "--addl-props", "--api-key", "--base-url", "--cache-dir", "--ckan-api", "--ckan-token", "--context-file", "--disk-cache-dir", "--ds-license", "--ds-source", "--ds-updated", "--enum-threshold", "--export-prompt", "--format", "--freq-options", "--language", "--markdown-template", "--max-tokens", "--model", "--num-examples", "--num-tags", "--okf-type", "--output", "--prompt", "--prompt-file", "--sample-size", "--score-max-retries", "--score-threshold", "--session", "--session-len", "--sql-results", "--stats-options", "--tag-vocab", "--timeout", "--tour-audience", "--truncate-str", "--user-agent", "-k", "-m", "-o", "-p", "-t", "-u"},
            "denull": {"--add-vocab", "--delimiter", "--max-distinct", "--output", "--select", "--vocab", "-d", "-o", "-s"},
            "edit": {"--output", "-o"},
            "enum": {"--constant", "--copy", "--delimiter", "--hash", "--increment", "--new-column", "--output", "--start", "-c", "-d", "-o"},
            "excel": {"--cell", "--date-format", "--delimiter", "--error-format", "--header-row", "--jobs", "--metadata", "--output", "--range", "--sheet", "--table", "-d", "-j", "-o", "-s"},
            "exclude": {"--delimiter", "--output", "-d", "-o"},
            "explode": {"--delimiter", "--output", "--rename", "-d", "-o", "-r"},
            "extdedup": {"--delimiter", "--dupes-output", "--memory-limit", "--select", "--temp-dir", "-D", "-d", "-s"},
            "extsort": {"--delimiter", "--jobs", "--memory-limit", "--select", "--tmp-dir", "-d", "-j", "-s"},
            "fetch": {"--default-encoding", "--delimiter", "--disk-cache-dir", "--http-header", "--jaq", "--jaqfile", "--max-errors", "--max-retries", "--mem-cache-size", "--new-column", "--output", "--rate-limit", "--report", "--timeout", "--url-template", "--user-agent", "-H", "-c", "-d", "-o"},
            "fetchpost": {"--content-type", "--default-encoding", "--delimiter", "--disk-cache-dir", "--globals-json", "--http-header", "--jaq", "--jaqfile", "--max-errors", "--max-retries", "--mem-cache-size", "--new-column", "--output", "--payload-tpl", "--rate-limit", "--report", "--timeout", "--user-agent", "-H", "-c", "-d", "-j", "-o", "-t"},
            "fill": {"--default", "--delimiter", "--groupby", "--output", "-d", "-g", "-o", "-v"},
            "fixedwidth": {"--output", "--positions", "--widths", "-o"},
            "flatten": {"--condense", "--delimiter", "--field-separator", "--separator", "-c", "-d", "-f", "-s"},
            "fmt": {"--delimiter", "--escape", "--out-delimiter", "--output", "--quote", "-d", "-o", "-t"},
            "foreach": {"--delimiter", "--dry-run", "--new-column", "-c", "-d"},
            "geocode": {"--admin1", "--api-key", "--batch", "--benchmark", "--cache-dir", "--cache-ttl", "--census-mode", "--cities-url", "--country", "--delimiter", "--endpoint", "--formatstr", "--invalid-result", "--jobs", "--k_weight", "--language", "--languages", "--min-score", "--new-column", "--older-than", "--output", "--params", "--rate-limit", "--rename", "--timeout", "--vintage", "-b", "-c", "-d", "-f", "-j", "-k", "-l", "-o", "-r"},
            "frequency": {"--all-unique-text", "--delimiter", "--high-card-pct", "--high-card-threshold", "--jobs", "--limit", "--lmt-threshold", "--no-float", "--null-text", "--other-text", "--output", "--pct-dec-places", "--rank-strategy", "--select", "--sketch-map-size", "--sketch-method", "--stats-filter", "--unq-limit", "--weight", "-d", "-j", "-l", "-o", "-r", "-s", "-u"},
            "geoconvert": {"--delimiter", "--geometry", "--latitude", "--longitude", "--max-length", "--output", "-d", "-g", "-o", "-x", "-y"},
            "headers": {"--delimiter", "-d"},
            "implode": {"--delimiter", "--keys", "--output", "--rename", "--value", "-d", "-k", "-o", "-r", "-v"},
            "input": {"--comment", "--delimiter", "--encoding-errors", "--escape", "--output", "--quote", "--quote-style", "--skip-lastlines", "--skip-lines", "-d", "-o"},
            "join": {"--delimiter", "--keys-output", "--output", "-d", "-o"},
            "json": {"--jaq", "--output", "--select", "-o", "-s"},
            "jsonl": {"--batch", "--delimiter", "--jobs", "--output", "-b", "-d", "-j", "-o"},
            "lens": {"--columns", "--delimiter", "--echo-column", "--filter", "--find", "--freeze-columns", "--prompt", "--wrap-mode", "-P", "-W", "-d", "-f"},
            "luau": {"--begin", "--cache-dir", "--ckan-api", "--ckan-token", "--delimiter", "--end", "--max-errors", "--output", "--timeout", "-B", "-E", "-d", "-o"},
            "moarstats": {"--bivariate-batch", "--bivariate-stats", "--cardinality-threshold", "--epsilon", "--jobs", "--join-inputs", "--join-keys", "--join-type", "--output", "--pct-thresholds", "--round", "--stats-options", "--xsd-gdate-scan", "-C", "-J", "-K", "-S", "-T", "-e", "-j", "-o"},
            "partition": {"--delimiter", "--filename", "--limit", "--prefix-length", "-d", "-p"},
            "pragmastat": {"--compare1", "--compare2", "--delimiter", "--jobs", "--misrate", "--output", "--round", "--seed", "--select", "--stats-options", "--subsample", "-d", "-j", "-m", "-o", "-s"},
            "profile": {"--dcat-discovery-timeout", "--delimiter", "--describegpt", "--initial-context", "--jobs", "--locate", "--output", "--profile", "--spec", "--tag-vocab", "-d", "-j", "-o"},
            "pseudo": {"--delimiter", "--formatstr", "--increment", "--output", "--start", "-d", "-f", "-o"},
            "py": {"--batch", "--delimiter", "--helper", "--output", "-b", "-d", "-f", "-o"},
            "rename": {"--delimiter", "--output", "-d", "-o"},
            "replace": {"--delimiter", "--dfa-size-limit", "--jobs", "--output", "--select", "--size-limit", "-d", "-j", "-o", "-s"},
            "reverse": {"--delimiter", "--output", "-d", "-o"},
            "safenames": {"--delimiter", "--mode", "--output", "--prefix", "--reserved", "-d", "-o"},
            "sample": {"--cluster", "--delimiter", "--max-size", "--output", "--rng", "--seed", "--sketch-in", "--sketch-out", "--stratified", "--systematic", "--timeout", "--timeseries", "--ts-adaptive", "--ts-aggregate", "--ts-input-tz", "--ts-interval", "--ts-start", "--user-agent", "--varopt", "--weighted", "-d", "-o"},
            "schema": {"--dates-whitelist", "--delimiter", "--enum-threshold", "--jobs", "--output", "--pattern-columns", "-d", "-j", "-o"},
            "scoresql": {"--delimiter", "--infer-len", "--output", "-d", "-o"},
            "search": {"--delimiter", "--dfa-size-limit", "--flag", "--jobs", "--output", "--preview-match", "--select", "--size-limit", "-d", "-f", "-j", "-o", "-s"},
            "searchset": {"--delimiter", "--dfa-size-limit", "--flag", "--jobs", "--output", "--select", "--size-limit", "--unmatched-output", "-d", "-f", "-o", "-s"},
            "select": {"--delimiter", "--output", "--seed", "-d", "-o"},
            "slice": {"--delimiter", "--end", "--index", "--len", "--output", "--start", "-d", "-e", "-i", "-l", "-o", "-s"},
            "snappy": {"--jobs", "--output", "--timeout", "--user-agent", "-j", "-o"},
            "sniff": {"--delimiter", "--quote", "--sample", "--save-urlsample", "--timeout", "--user-agent", "-d"},
            "sort": {"--delimiter", "--jobs", "--output", "--rng", "--seed", "--select", "-d", "-j", "-o", "-s"},
            "sortcheck": {"--delimiter", "--select", "-d", "-s"},
            "split": {"--chunks", "--delimiter", "--filename", "--filter", "--jobs", "--kb-size", "--pad", "--size", "-c", "-d", "-j", "-k", "-s"},
            "sqlp": {"--compress-level", "--compression", "--date-format", "--datetime-format", "--delimiter", "--float-precision", "--format", "--infer-len", "--output", "--rnull-values", "--time-format", "--wnull-value", "-d", "-o"},
            "stats": {"--boolean-patterns", "--cache-threshold", "--cardinality-method", "--dates-whitelist", "--delimiter", "--jobs", "--mode-cardinality-cap", "--output", "--percentile-list", "--quantile-method", "--round", "--select", "--weight", "-c", "-d", "-j", "-o", "-s"},
            "table": {"--align", "--condense", "--delimiter", "--output", "--pad", "--width", "-a", "-c", "-d", "-o", "-p", "-w"},
            "template": {"--batch", "--cache-dir", "--ckan-api", "--ckan-token", "--customfilter-error", "--delimiter", "--globals-json", "--jobs", "--outfilename", "--output", "--outsubdir-size", "--template", "--template-file", "--timeout", "-J", "-b", "-j", "-o", "-t"},
            "to": {"--compress-level", "--compression", "--delimiter", "--infer-len", "--jobs", "--schema", "--separator", "--stats-csv", "--table", "-c", "-d", "-j", "-p", "-s", "-t"},
            "tojsonl": {"--batch", "--delimiter", "--jobs", "--output", "-b", "-d", "-j", "-o"},
            "transpose": {"--delimiter", "--long", "--output", "--select", "-d", "-o", "-s"},
            "validate": {"--backtrack-limit", "--batch", "--cache-dir", "--ckan-api", "--ckan-token", "--delimiter", "--dfa-size-limit", "--email-min-subdomains", "--invalid", "--jobs", "--sarif", "--sarif-max-results", "--size-limit", "--timeout", "--valid", "--valid-output", "-b", "-d", "-j"},
            "writestat": {"--delimiter", "--dictionary", "--format", "--output", "--table-name", "-d", "-o"},
            }
        if (mentah
                and mentah[0] in bernilai_angka_qsv
                and _ada_token_angka(
                    mentah,
                    bernilai_angka_qsv[mentah[0]])):
            return False
        if mentah and mentah[0] == "count":
            if _ada_duplikat(mentah, 1, {"-d", "--delimiter"}, {"-d": "--delimiter", "-H": "--human-readable", "-f": "--flexible", "-n": "--no-headers"}):
                return False
            # Audit 2420: token strip-angka =
            # flag tak dikenal docopt (galat
            # parse, stdin tak tersentuh).
            i_qc = 1
            while i_qc < len(mentah):
                t_qc = mentah[i_qc]
                if t_qc == "--":
                    break
                if t_qc in ("-d", "--delimiter"):
                    i_qc += 2
                    continue
                if (len(t_qc) > 1
                        and t_qc[0] == "-"
                        and t_qc[1].isdigit()):
                    return False
                i_qc += 1
            ops_qc = _operand_sesudah_opsi(
                toks, awal + 1, {"-d", "--delimiter"})
            if "\x00gantung" in ops_qc:
                return False
            return (not ops_qc
                    or (len(ops_qc) == 1
                        and ops_qc[0] == "-"))
        # Audit 2124: sub 'search' memakai
        # Config::new(&args.arg_input) yang sama
        # (search.rs) - pola operand pertama,
        # masukan opsional sesudahnya.
        if len(mentah) >= 2 and mentah[0] == "search":
            # Audit 2131: kosakata search resmi
            # (search.rs) - kolom -s/--select.
            if _ada_duplikat(mentah, 1, {"-s", "--select", "-f", "--flag", "--preview-match", "--size-limit", "--dfa-size-limit", "-j", "--jobs", "-o", "--output", "-d", "--delimiter"}, {"-s": "--select", "-f": "--flag", "-j": "--jobs", "-o": "--output", "-d": "--delimiter", "-i": "--ignore-case", "-v": "--invert-match", "-u": "--unicode", "-Q": "--quick", "-c": "--count", "-n": "--no-headers", "-p": "--progressbar", "-q": "--quiet"}):
                return False
            ops_qse = _operand_sesudah_opsi(
                toks, awal + 1,
                {"-s", "--select", "-f", "--flag",
                 "--preview-match", "--size-limit",
                 "--dfa-size-limit", "-j", "--jobs",
                 "-o", "--output", "-d", "--delimiter"})
            if "\x00gantung" in ops_qse:
                return False
            return (len(ops_qse) == 1
                    or (len(ops_qse) == 2
                        and ops_qse[1] == "-"))
        # Audit 2141: sub 'apply operations' -
        # operand operasi + kolom, masukan
        # [<input>] opsional (apply.rs).
        if (len(mentah) >= 2 and mentah[0] == "apply"
                and mentah[1] == "operations"):
            if _ada_duplikat(mentah, 2, {"-c", "--new-column", "-r", "--rename", "-C", "--comparand", "-R", "--replacement", "-f", "--formatstr", "-j", "--jobs", "-b", "--batch", "-o", "--output", "-d", "--delimiter", "-u", "--base-url", "-m", "--model", "-k", "--api-key", "-t", "--max-tokens", "--timeout", "--addl-props", "--prompt", "--prompt-file", "--rate-limit", "--on-error", "--user-agent", "--cache-dir"}, {"-c": "--new-column", "-r": "--rename", "-C": "--comparand", "-R": "--replacement", "-f": "--formatstr", "-j": "--jobs", "-b": "--batch", "-o": "--output", "-d": "--delimiter", "-u": "--base-url", "-m": "--model", "-k": "--api-key", "-t": "--max-tokens", "-n": "--no-headers", "-p": "--progressbar"}):
                return False
            ops_qap = _operand_sesudah_opsi(
                toks, awal + 2, {"-c", "--new-column", "-r", "--rename", "-C", "--comparand", "-R", "--replacement", "-f", "--formatstr", "-j", "--jobs", "-b", "--batch", "-o", "--output", "-d", "--delimiter", "-u", "--base-url", "-m", "--model", "-k", "--api-key", "-t", "--max-tokens", "--timeout", "--addl-props", "--prompt", "--prompt-file", "--rate-limit", "--on-error", "--user-agent", "--cache-dir"})
            if "\x00gantung" in ops_qap:
                return False
            return (len(ops_qap) == 2
                    or (len(ops_qap) == 3
                        and ops_qap[2] == "-"))
        # Audit 2148: sub-sub apply lain juga
        # ber-[<input>] opsional (apply.rs).
        if len(mentah) >= 2 and mentah[0] == "apply":
            if _ada_duplikat(mentah, 2, {"-c", "--new-column", "-r", "--rename", "-C", "--comparand", "-R", "--replacement", "-f", "--formatstr", "-j", "--jobs", "-b", "--batch", "-o", "--output", "-d", "--delimiter", "-u", "--base-url", "-m", "--model", "-k", "--api-key", "-t", "--max-tokens", "--timeout", "--addl-props", "--prompt", "--prompt-file", "--rate-limit", "--on-error", "--user-agent", "--cache-dir"}, {"-c": "--new-column", "-r": "--rename", "-C": "--comparand", "-R": "--replacement", "-f": "--formatstr", "-j": "--jobs", "-b": "--batch", "-o": "--output", "-d": "--delimiter", "-u": "--base-url", "-m": "--model", "-k": "--api-key", "-t": "--max-tokens", "-n": "--no-headers", "-p": "--progressbar"}):
                return False
            sub_ap = mentah[1]
            if sub_ap == "dynfmt":
                if not (_opsi_hadir(
                        mentah, 2, {"-c", "--new-column", "-r", "--rename", "-C", "--comparand", "-R", "--replacement", "-f", "--formatstr", "-j", "--jobs", "-b", "--batch", "-o", "--output", "-d", "--delimiter", "-u", "--base-url", "-m", "--model", "-k", "--api-key", "-t", "--max-tokens", "--timeout", "--addl-props", "--prompt", "--prompt-file", "--rate-limit", "--on-error", "--user-agent", "--cache-dir"},
                        {"-f", "--formatstr"})
                        and _opsi_hadir(
                        mentah, 2, {"-c", "--new-column", "-r", "--rename", "-C", "--comparand", "-R", "--replacement", "-f", "--formatstr", "-j", "--jobs", "-b", "--batch", "-o", "--output", "-d", "--delimiter", "-u", "--base-url", "-m", "--model", "-k", "--api-key", "-t", "--max-tokens", "--timeout", "--addl-props", "--prompt", "--prompt-file", "--rate-limit", "--on-error", "--user-agent", "--cache-dir"},
                        {"-c", "--new-column"})):
                    return False
                ops_ap = _operand_sesudah_opsi(
                    toks, awal + 2, {"-c", "--new-column", "-r", "--rename", "-C", "--comparand", "-R", "--replacement", "-f", "--formatstr", "-j", "--jobs", "-b", "--batch", "-o", "--output", "-d", "--delimiter", "-u", "--base-url", "-m", "--model", "-k", "--api-key", "-t", "--max-tokens", "--timeout", "--addl-props", "--prompt", "--prompt-file", "--rate-limit", "--on-error", "--user-agent", "--cache-dir"})
                if "\x00gantung" in ops_ap:
                    return False
                return not ops_ap or ops_ap == ["-"]
            if sub_ap == "calcconv":
                # Audit 2194: calcconv sejajar dynfmt
                # (wajib --formatstr + --new-column,
                # operand [<input>] tunggal).
                if not (_opsi_hadir(
                        mentah, 2, {"-c", "--new-column", "-r", "--rename", "-C", "--comparand", "-R", "--replacement", "-f", "--formatstr", "-j", "--jobs", "-b", "--batch", "-o", "--output", "-d", "--delimiter", "-u", "--base-url", "-m", "--model", "-k", "--api-key", "-t", "--max-tokens", "--timeout", "--addl-props", "--prompt", "--prompt-file", "--rate-limit", "--on-error", "--user-agent", "--cache-dir"},
                        {"-f", "--formatstr"})
                        and _opsi_hadir(
                        mentah, 2, {"-c", "--new-column", "-r", "--rename", "-C", "--comparand", "-R", "--replacement", "-f", "--formatstr", "-j", "--jobs", "-b", "--batch", "-o", "--output", "-d", "--delimiter", "-u", "--base-url", "-m", "--model", "-k", "--api-key", "-t", "--max-tokens", "--timeout", "--addl-props", "--prompt", "--prompt-file", "--rate-limit", "--on-error", "--user-agent", "--cache-dir"},
                        {"-c", "--new-column"})):
                    return False
                ops_ap = _operand_sesudah_opsi(
                    toks, awal + 2, {"-c", "--new-column", "-r", "--rename", "-C", "--comparand", "-R", "--replacement", "-f", "--formatstr", "-j", "--jobs", "-b", "--batch", "-o", "--output", "-d", "--delimiter", "-u", "--base-url", "-m", "--model", "-k", "--api-key", "-t", "--max-tokens", "--timeout", "--addl-props", "--prompt", "--prompt-file", "--rate-limit", "--on-error", "--user-agent", "--cache-dir"})
                if "\x00gantung" in ops_ap:
                    return False
                return not ops_ap or ops_ap == ["-"]
            if sub_ap == "emptyreplace":
                if not _opsi_hadir(
                        mentah, 2, {"-c", "--new-column", "-r", "--rename", "-C", "--comparand", "-R", "--replacement", "-f", "--formatstr", "-j", "--jobs", "-b", "--batch", "-o", "--output", "-d", "--delimiter", "-u", "--base-url", "-m", "--model", "-k", "--api-key", "-t", "--max-tokens", "--timeout", "--addl-props", "--prompt", "--prompt-file", "--rate-limit", "--on-error", "--user-agent", "--cache-dir"},
                        {"-R", "--replacement"}):
                    return False
                ops_ap = _operand_sesudah_opsi(
                    toks, awal + 2, {"-c", "--new-column", "-r", "--rename", "-C", "--comparand", "-R", "--replacement", "-f", "--formatstr", "-j", "--jobs", "-b", "--batch", "-o", "--output", "-d", "--delimiter", "-u", "--base-url", "-m", "--model", "-k", "--api-key", "-t", "--max-tokens", "--timeout", "--addl-props", "--prompt", "--prompt-file", "--rate-limit", "--on-error", "--user-agent", "--cache-dir"})
                if "\x00gantung" in ops_ap:
                    return False
                return (len(ops_ap) == 1
                        or (len(ops_ap) == 2
                            and ops_ap[1] == "-"))
            if sub_ap == "summarize":
                # Audit 2317: sub summarize -
                # <column> [<input>] dengan syarat
                # wajib -c/--new-column (apply.rs).
                if not _opsi_hadir(
                        mentah, 2, {"-c", "--new-column", "-r", "--rename", "-C", "--comparand", "-R", "--replacement", "-f", "--formatstr", "-j", "--jobs", "-b", "--batch", "-o", "--output", "-d", "--delimiter", "-u", "--base-url", "-m", "--model", "-k", "--api-key", "-t", "--max-tokens", "--timeout", "--addl-props", "--prompt", "--prompt-file", "--rate-limit", "--on-error", "--user-agent", "--cache-dir"},
                        {"-c", "--new-column"}):
                    return False
                ops_ap = _operand_sesudah_opsi(
                    toks, awal + 2, {"-c", "--new-column", "-r", "--rename", "-C", "--comparand", "-R", "--replacement", "-f", "--formatstr", "-j", "--jobs", "-b", "--batch", "-o", "--output", "-d", "--delimiter", "-u", "--base-url", "-m", "--model", "-k", "--api-key", "-t", "--max-tokens", "--timeout", "--addl-props", "--prompt", "--prompt-file", "--rate-limit", "--on-error", "--user-agent", "--cache-dir"})
                if "\x00gantung" in ops_ap:
                    return False
                return (len(ops_ap) == 1
                        or (len(ops_ap) == 2
                            and ops_ap[1] == "-"))
        # Audit 2142: sub 'slice' - tanpa masukan
        # membaca stdin (slice.rs).
        if mentah and mentah[0] == "slice":
            if _ada_duplikat(mentah, 1, {"-s", "--start", "-e", "--end", "-l", "--len", "-i", "--index", "-d", "--delimiter", "-o", "--output"}, {"-s": "--start", "-e": "--end", "-l": "--len", "-i": "--index", "-d": "--delimiter", "-o": "--output", "-n": "--no-headers"}):
                return False
            ops_qsl = _operand_sesudah_opsi(
                toks, awal + 1,
                {"-s", "--start", "-e", "--end",
                 "-l", "--len", "-i", "--index",
                 "-d", "--delimiter", "-o", "--output"})
            if "\x00gantung" in ops_qsl:
                return False
            return (not ops_qsl
                    or (len(ops_qsl) == 1
                        and ops_qsl[0] == "-"))
        # Audit 2151: sub 'stats' - masukan
        # [<input>] opsional (stats.rs).
        if mentah and mentah[0] == "stats":
            if _ada_duplikat(mentah, 1, {"-o", "--output", "-s", "--select", "--round", "--dates-whitelist", "-j", "--jobs", "-d", "--delimiter", "-c", "--cache-threshold", "--boolean-patterns", "--cardinality-method", "--mode-cardinality-cap", "--percentile-list", "--quantile-method", "--weight"}, {"-o": "--output", "-s": "--select", "-j": "--jobs", "-d": "--delimiter", "-c": "--cache-threshold", "-E": "--everything", "-n": "--no-headers"}):
                return False
            ops_qst = _operand_sesudah_opsi(
                toks, awal + 1,
                {"-o", "--output", "-s", "--select",
                 "--round", "--dates-whitelist", "-j",
                 "--jobs", "-d", "--delimiter", "-c",
                 "--cache-threshold",
                 "--boolean-patterns",
                 "--cardinality-method",
                 "--mode-cardinality-cap",
                 "--percentile-list",
                 "--quantile-method", "--weight"})
            if "\x00gantung" in ops_qst:
                return False
            return (not ops_qst
                    or (len(ops_qst) == 1
                        and ops_qst[0] == "-"))
        # Audit 2152: sub 'headers' - masukan
        # kosong/'-' berarti stdin (README qsv:
        # dukungan masukan diperluas).
        if mentah and mentah[0] == "headers":
            if _ada_duplikat(mentah, 1, {"-d", "--delimiter"}, {"-d": "--delimiter", "-j": "--just-names", "-J": "--just-count"}):
                return False
            ops_qh = _operand_sesudah_opsi(
                toks, awal + 1,
                {"-d", "--delimiter"})
            if "\x00gantung" in ops_qh:
                return False
            return not ops_qh or "-" in ops_qh
        # Audit 2178: sub 'sort' qsv - masukan
        # [<input>] opsional (sort.rs).
        if mentah and mentah[0] == "sort":
            if _ada_duplikat(mentah, 1, {"-s", "--select", "--seed", "--rng", "-j", "--jobs", "-o", "--output", "-d", "--delimiter"}, {"-s": "--select", "-j": "--jobs", "-o": "--output", "-d": "--delimiter", "-N": "--numeric", "-R": "--reverse", "-i": "--ignore-case", "-u": "--unique", "-n": "--no-headers"}):
                return False
            ops_qso = _operand_sesudah_opsi(
                toks, awal + 1,
                {"-s", "--select", "--seed", "--rng",
                 "-j", "--jobs", "-o", "--output", "-d",
                 "--delimiter"})
            if "\x00gantung" in ops_qso:
                return False
            return (not ops_qso
                    or (len(ops_qso) == 1
                        and ops_qso[0] == "-"))
        # Audit 2179: sub 'select' qsv - operand
        # pertama pilihan kolom, masukan kedua
        # (select.rs).
        if mentah and mentah[0] == "select":
            if _ada_duplikat(mentah, 1, {"-o", "--output", "-d", "--delimiter", "--seed"}, {"-o": "--output", "-d": "--delimiter", "-R": "--random", "-S": "--sort", "-n": "--no-headers"}):
                return False
            ops_qse = _operand_sesudah_opsi(
                toks, awal + 1,
                {"-o", "--output", "-d", "--delimiter",
                 "--seed"})
            if "\x00gantung" in ops_qse:
                return False
            return (len(ops_qse) == 1
                    or (len(ops_qse) == 2
                        and ops_qse[1] == "-"))
        # Audit 2180: sub 'sample' qsv - operand
        # pertama ukuran, masukan kedua
        # (sample.rs).
        if mentah and mentah[0] == "sample":
            if _ada_duplikat(mentah, 1, {"--seed", "--rng", "--user-agent", "--timeout", "-o", "--output", "-d", "--delimiter", "--max-size", "--systematic", "--stratified", "--weighted", "--varopt", "--sketch-in", "--sketch-out", "--cluster", "--timeseries", "--ts-interval", "--ts-start", "--ts-adaptive", "--ts-aggregate", "--ts-input-tz"}, {"-o": "--output", "-d": "--delimiter", "-n": "--no-headers"}):
                return False
            ops_qsa = _operand_sesudah_opsi(
                toks, awal + 1,
                {"--seed", "--rng", "--user-agent",
                 "--timeout", "-o", "--output", "-d",
                 "--delimiter", "--max-size",
                 "--systematic", "--stratified",
                 "--weighted", "--varopt",
                 "--sketch-in", "--sketch-out",
                 "--cluster", "--timeseries",
                 "--ts-interval", "--ts-start",
                 "--ts-adaptive", "--ts-aggregate",
                 "--ts-input-tz"})
            if "\x00gantung" in ops_qsa:
                return False
            # Audit 2184: ukuran qsv sample f64 -
            # bukan angka = galat parse.
            if ops_qsa:
                # Audit 2200: f64 Rust menolak spasi
                # tepi + underscore (dok std).
                if (ops_qsa[0] != ops_qsa[0].strip()
                        or "_" in ops_qsa[0]):
                    return False
                try:
                    float(ops_qsa[0])
                except ValueError:
                    return False
            return (len(ops_qsa) == 1
                    or (len(ops_qsa) == 2
                        and ops_qsa[1] == "-"))
        # Audit 2181: sub 'split' qsv - operand
        # pertama direktori keluaran, masukan
        # kedua (split.rs).
        if mentah and mentah[0] == "split":
            if _ada_duplikat(mentah, 1, {"-s", "--size", "-c", "--chunks", "-k", "--kb-size", "-j", "--jobs", "--filename", "--pad", "--filter", "-d", "--delimiter"}, {"-s": "--size", "-c": "--chunks", "-k": "--kb-size", "-j": "--jobs", "-d": "--delimiter", "-n": "--no-headers", "-q": "--quiet"}):
                return False
            ops_qsp = _operand_sesudah_opsi(
                toks, awal + 1,
                {"-s", "--size", "-c", "--chunks",
                 "-k", "--kb-size", "-j", "--jobs",
                 "--filename", "--pad", "--filter",
                 "-d", "--delimiter"})
            if "\x00gantung" in ops_qsp:
                return False
            # Audit 2185: template --filename qsv
            # split wajib tepat satu '{}'.
            for i_qp, t_qp in enumerate(mentah):
                nilai_qp = None
                if (t_qp == "--filename"
                        and i_qp + 1 < len(mentah)):
                    nilai_qp = mentah[i_qp + 1]
                elif t_qp.startswith("--filename="):
                    nilai_qp = t_qp.split("=", 1)[1]
                if (nilai_qp is not None
                        and nilai_qp.count("{}") != 1):
                    return False
            return (len(ops_qsp) == 1
                    or (len(ops_qsp) == 2
                        and ops_qsp[1] == "-"))
        # Audit 2182: sub 'join' qsv - empat
        # operand (kolom, masukan1, kolom,
        # masukan2); '-' pada masukan = stdin
        # (join.rs).
        if mentah and mentah[0] == "join":
            if _ada_duplikat(mentah, 1, {"--delimiter", "--keys-output", "--output", "-d", "-o"}, {"-d": "--delimiter", "-i": "--ignore-case", "-n": "--no-headers", "-o": "--output", "-z": "--ignore-leading-zeros"}):
                return False
            ops_qj = _operand_sesudah_opsi(
                toks, awal + 1,
                {"-o", "--output", "-d", "--delimiter",
                 "--keys-output"})
            if "\x00gantung" in ops_qj:
                return False
            return (len(ops_qj) == 4
                    and (ops_qj[1] == "-"
                         or ops_qj[3] == "-"))
        # Audit 2183: sub 'dedup' qsv - masukan
        # [<input>] opsional (dedup.rs).
        if mentah and mentah[0] == "dedup":
            if _ada_duplikat(mentah, 1, {"--delimiter", "--dupes-output", "--jobs", "--output", "--select", "-D", "-d", "-j", "-o", "-s"}, {"-D": "--dupes-output", "-H": "--human-readable", "-N": "--numeric", "-d": "--delimiter", "-i": "--ignore-case", "-j": "--jobs", "-n": "--no-headers", "-o": "--output", "-q": "--quiet", "-s": "--select"}):
                return False
            ops_qdd = _operand_sesudah_opsi(
                toks, awal + 1,
                {"-s", "--select", "-D", "--dupes-output",
                 "-j", "--jobs", "-o", "--output", "-d",
                 "--delimiter"})
            if "\x00gantung" in ops_qdd:
                return False
            return (not ops_qdd
                    or (len(ops_qdd) == 1
                        and ops_qdd[0] == "-"))
        # Audit 2203: sub 'transpose' - masukan
        # [<input>] opsional (transpose.rs).
        if mentah and mentah[0] == "transpose":
            if _ada_duplikat(mentah, 1, {"--delimiter", "--long", "--output", "--select", "-d", "-o", "-s"}, {"-d": "--delimiter", "-m": "--multipass", "-o": "--output", "-s": "--select"}):
                return False
            ops_qtr = _operand_sesudah_opsi(
                toks, awal + 1,
                {"-o", "--output", "-d", "--delimiter",
                 "-s", "--select", "--long"})
            if "\x00gantung" in ops_qtr:
                return False
            return (not ops_qtr
                    or (len(ops_qtr) == 1
                        and ops_qtr[0] == "-"))
        # Audit 2204: sub 'reverse' - masukan
        # [<input>] opsional (reverse.rs).
        if mentah and mentah[0] == "reverse":
            if _ada_duplikat(mentah, 1, {"--delimiter", "--output", "-d", "-o"}, {"-d": "--delimiter", "-n": "--no-headers", "-o": "--output"}):
                return False
            ops_qrv = _operand_sesudah_opsi(
                toks, awal + 1,
                {"-o", "--output", "-d", "--delimiter"})
            if "\x00gantung" in ops_qrv:
                return False
            return (not ops_qrv
                    or (len(ops_qrv) == 1
                        and ops_qrv[0] == "-"))
        # Audit 2205: sub 'rename' - operand
        # pertama nama kolom baru, masukan
        # kedua (rename.rs).
        if mentah and mentah[0] == "rename":
            if _ada_duplikat(mentah, 1, {"--delimiter", "--output", "-d", "-o"}, {"-d": "--delimiter", "-n": "--no-headers", "-o": "--output"}):
                return False
            ops_qrn = _operand_sesudah_opsi(
                toks, awal + 1,
                {"-o", "--output", "-d", "--delimiter"})
            if "\x00gantung" in ops_qrn:
                return False
            return (len(ops_qrn) == 1
                    or (len(ops_qrn) == 2
                        and ops_qrn[1] == "-"))
        # Audit 2206: sub 'replace' - operand
        # pola + pengganti, masukan ketiga
        # (replace.rs).
        if mentah and mentah[0] == "replace":
            if _ada_duplikat(mentah, 1, {"--delimiter", "--dfa-size-limit", "--jobs", "--output", "--select", "--size-limit", "-d", "-j", "-o", "-s"}, {"-d": "--delimiter", "-i": "--ignore-case", "-j": "--jobs", "-n": "--no-headers", "-o": "--output", "-p": "--progressbar", "-q": "--quiet", "-s": "--select", "-u": "--unicode"}):
                return False
            ops_qrp = _operand_sesudah_opsi(
                toks, awal + 1,
                {"-s", "--select", "--size-limit",
                 "--dfa-size-limit", "-j", "--jobs",
                 "-o", "--output", "-d",
                 "--delimiter"})
            if "\x00gantung" in ops_qrp:
                return False
            return (len(ops_qrp) == 2
                    or (len(ops_qrp) == 3
                        and ops_qrp[2] == "-"))
        # Audit 2207: sub 'to' - operand sesudah
        # sub-format adalah target keluaran,
        # daftar masukan boleh kosong (to.rs).
        if (len(mentah) >= 2 and mentah[0] == "to"
                and mentah[1] in ("postgres", "sqlite",
                                  "xlsx", "parquet",
                                  "datapackage", "ods")):
            if _ada_duplikat(mentah, 1, {"--compress-level", "--compression", "--delimiter", "--infer-len", "--jobs", "--schema", "--separator", "--stats-csv", "--table", "-c", "-d", "-j", "-p", "-s", "-t"}, {"-A": "--all-strings", "-a": "--stats", "-c": "--stats-csv", "-d": "--delimiter", "-e": "--evolve", "-i": "--pipe", "-j": "--jobs", "-k": "--print-package", "-p": "--separator", "-q": "--quiet", "-s": "--schema", "-t": "--table", "-u": "--dump"}):
                return False
            ops_qto = _operand_sesudah_opsi(
                toks, awal + 2,
                {"-c", "--stats-csv", "-s", "--schema",
                 "-p", "--separator", "-j", "--jobs",
                 "-d", "--delimiter", "-t", "--table",
                 "--compression", "--compress-level",
                 "--infer-len"})
            if "\x00gantung" in ops_qto:
                return False
            return (len(ops_qto) >= 2
                    and "-" in ops_qto[1:])
        # Audit 2208: sub 'sqlp' - daftar
        # masukan lalu SQL terakhir; '-' di
        # masukan berarti stdin (sqlp.rs).
        if mentah and mentah[0] == "sqlp":
            if _ada_duplikat(mentah, 1, {"--compress-level", "--compression", "--date-format", "--datetime-format", "--delimiter", "--float-precision", "--format", "--infer-len", "--output", "--rnull-values", "--time-format", "--wnull-value", "-d", "-o"}, {"-d": "--delimiter", "-o": "--output", "-q": "--quiet"}):
                return False
            ops_qsq = _operand_sesudah_opsi(
                toks, awal + 1,
                {"--format", "--infer-len",
                 "--rnull-values", "--datetime-format",
                 "--date-format", "--time-format",
                 "--float-precision", "--wnull-value",
                 "--compression", "--compress-level",
                 "-o", "--output", "-d",
                 "--delimiter"})
            if "\x00gantung" in ops_qsq:
                return False
            return (len(ops_qsq) >= 2
                    and "-" in ops_qsq[:-1])
        # Audit 2213: sub 'behead' - masukan
        # [<input>] opsional (behead.rs).
        if mentah and mentah[0] == "behead":
            if _ada_duplikat(mentah, 1, {"--output", "-o"}, {"-f": "--flexible", "-o": "--output"}):
                return False
            ops_qbh = _operand_sesudah_opsi(
                toks, awal + 1, {"-o", "--output"})
            if "\x00gantung" in ops_qbh:
                return False
            return (not ops_qbh
                    or (len(ops_qbh) == 1
                        and ops_qbh[0] == "-"))
        # Audit 2214: sub 'snappy' - sub
        # compress/decompress/check ber-[<input>]
        # opsional; validate menolak stdin
        # (snappy.rs).
        if (len(mentah) >= 2 and mentah[0] == "snappy"
                and mentah[1] in ("compress",
                                  "decompress", "check")):
            if _ada_duplikat(mentah, 1, {"--jobs", "--output", "--timeout", "--user-agent", "-j", "-o"}, {"-j": "--jobs", "-o": "--output", "-p": "--progressbar", "-q": "--quiet"}):
                return False
            ops_qsn = _operand_sesudah_opsi(
                toks, awal + 2,
                {"--user-agent", "--timeout", "-j",
                 "--jobs", "-o", "--output"})
            if "\x00gantung" in ops_qsn:
                return False
            return not ops_qsn
        # Audit 2215: sub 'fetch' - operand
        # pertama nama kolom URL, masukan kedua
        # (fetch.rs).
        if mentah and mentah[0] == "fetch":
            if _ada_duplikat(mentah, 1, {"--http-header", "--default-encoding", "--delimiter", "--disk-cache-dir", "--jaq", "--jaqfile", "--max-errors", "--max-retries", "--mem-cache-size", "--new-column", "--output", "--rate-limit", "--report", "--timeout", "--url-template", "--user-agent", "-H", "-c", "-d", "-o"}, {"-H": "--http-header", "-c": "--new-column", "-d": "--delimiter", "-n": "--no-headers", "-o": "--output", "-p": "--progressbar"}, {"--http-header"}):
                return False
            ops_qfe = _operand_sesudah_opsi(
                toks, awal + 1,
                {"--url-template",
                 "--jaq", "--jaqfile",
                 "--default-encoding",
                 "-c", "--new-column", "--rate-limit",
                 "--timeout", "-H", "--http-header",
                 "--max-retries", "--max-errors",
                 "--user-agent", "--report",
                 "--mem-cache-size", "--disk-cache-dir",
                 "-o", "--output", "-d", "--delimiter"})
            if "\x00gantung" in ops_qfe:
                return False
            ada_templat = any(
                t_qfe == "--url-template"
                or t_qfe.startswith("--url-template=")
                for t_qfe in mentah)
            if ada_templat:
                return (not ops_qfe
                        or ops_qfe == ["-"])
            return (len(ops_qfe) == 1
                    or (len(ops_qfe) == 2
                        and ops_qfe[1] == "-"))
        # Audit 2220: sub 'input' - masukan
        # [<input>] opsional (input.rs).
        if mentah and mentah[0] == "input":
            if _ada_duplikat(mentah, 1, {"--quote", "--escape", "--quote-style", "--skip-lines", "--skip-lastlines", "--comment", "--encoding-errors", "-o", "--output", "-d", "--delimiter"}, {"-o": "--output", "-d": "--delimiter"}):
                return False
            ops_qin = _operand_sesudah_opsi(
                toks, awal + 1,
                {"--quote", "--escape", "--quote-style",
                 "--skip-lines", "--skip-lastlines",
                 "--comment", "--encoding-errors", "-o",
                 "--output", "-d", "--delimiter"})
            if "\x00gantung" in ops_qin:
                return False
            return (not ops_qin
                    or (len(ops_qin) == 1
                        and ops_qin[0] == "-"))
        # Audit 2221: sub 'luau' - map/filter
        # sadar sub (luau.rs).
        if (len(mentah) >= 2 and mentah[0] == "luau"
                and mentah[1] in ("map", "filter")):
            if _ada_duplikat(mentah, 1, {"--begin", "--cache-dir", "--ckan-api", "--ckan-token", "--delimiter", "--end", "--max-errors", "--output", "--timeout", "-B", "-E", "-d", "-o"}, {"-B": "--begin", "-E": "--end", "-d": "--delimiter", "-g": "--no-globals", "-n": "--no-headers", "-o": "--output", "-p": "--progressbar", "-r": "--remap"}):
                return False
            ops_qlu = _operand_sesudah_opsi(
                toks, awal + 2,
                {"-B", "--begin", "-E", "--end",
                 "--max-errors",
                 "--timeout", "--ckan-api",
                 "--ckan-token", "--cache-dir", "-o",
                 "--output", "-d", "--delimiter"})
            if "\x00gantung" in ops_qlu:
                return False
            if mentah[1] == "map":
                ada_n = any(
                    t_qlu == "-n"
                    or t_qlu == "--no-headers"
                    for t_qlu in mentah)
                return ((ada_n and len(ops_qlu) == 1)
                        or len(ops_qlu) == 2
                        or (len(ops_qlu) == 3
                            and ops_qlu[2] == "-"))
            return (len(ops_qlu) == 1
                    or (len(ops_qlu) == 2
                        and ops_qlu[1] == "-"))
        # Audit 2222: sub 'py' - map/filter
        # sadar sub (python.rs).
        if (len(mentah) >= 2 and mentah[0] == "py"
                and mentah[1] in ("map", "filter")):
            if _ada_duplikat(mentah, 1, {"--batch", "--delimiter", "--helper", "--output", "-b", "-d", "-f", "-o"}, {"-b": "--batch", "-d": "--delimiter", "-f": "--helper", "-n": "--no-headers", "-o": "--output", "-p": "--progressbar"}):
                return False
            ops_qpy = _operand_sesudah_opsi(
                toks, awal + 2,
                {"-f", "--helper", "-b", "--batch",
                 "-o", "--output", "-d", "--delimiter"})
            if "\x00gantung" in ops_qpy:
                return False
            if mentah[1] == "map":
                ada_n = any(
                    t_qpy == "-n"
                    or t_qpy == "--no-headers"
                    for t_qpy in mentah)
                return ((ada_n and len(ops_qpy) == 1)
                        or len(ops_qpy) == 2
                        or (len(ops_qpy) == 3
                            and ops_qpy[2] == "-"))
            return (len(ops_qpy) == 1
                    or (len(ops_qpy) == 2
                        and ops_qpy[1] == "-"))
        # Audit 2223: sub 'foreach' - operand
        # kolom + perintah, masukan ketiga
        # (foreach.rs).
        if mentah and mentah[0] == "foreach":
            if _ada_duplikat(mentah, 1, {"--delimiter", "--dry-run", "--new-column", "-c", "-d"}, {"-c": "--new-column", "-d": "--delimiter", "-n": "--no-headers", "-p": "--progressbar", "-u": "--unify"}):
                return False
            ops_qfo = _operand_sesudah_opsi(
                toks, awal + 1,
                {"-c", "--new-column", "--dry-run", "-d",
                 "--delimiter"})
            if "\x00gantung" in ops_qfo:
                return False
            return (len(ops_qfo) == 2
                    or (len(ops_qfo) == 3
                        and ops_qfo[2] == "-"))
        # Audit 2227: sub 'extsort' - masukan
        # [<input>] [<output>] (extsort.rs).
        if mentah and mentah[0] == "extsort":
            if _ada_duplikat(mentah, 1, {"--delimiter", "--jobs", "--memory-limit", "--select", "--tmp-dir", "-d", "-j", "-s"}, {"-R": "--reverse", "-d": "--delimiter", "-j": "--jobs", "-n": "--no-headers", "-s": "--select"}):
                return False
            ops_qex = _operand_sesudah_opsi(
                toks, awal + 1,
                {"--memory-limit", "--tmp-dir", "-j",
                 "--jobs", "-s", "--select", "-d",
                 "--delimiter"})
            if "\x00gantung" in ops_qex:
                return False
            return (not ops_qex
                    or ops_qex[0] == "-")
        # Audit 2228: sub 'partition' qsv -
        # <column> <outdir> [<input>]
        # (partition.rs qsv).
        if mentah and mentah[0] == "partition":
            if _ada_duplikat(mentah, 1, {"--filename", "-p", "--prefix-length", "--limit", "-d", "--delimiter"}, {"-p": "--prefix-length", "-d": "--delimiter", "-n": "--no-headers"}):
                return False
            ops_qpa = _operand_sesudah_opsi(
                toks, awal + 1,
                {"--filename", "-p", "--prefix-length",
                 "--limit", "-d", "--delimiter"})
            if "\x00gantung" in ops_qpa:
                return False
            return (len(ops_qpa) == 2
                    or (len(ops_qpa) == 3
                        and ops_qpa[2] == "-"))
        # Audit 2229: sub 'sortcheck' - masukan
        # [<input>] opsional (sortcheck.rs).
        if mentah and mentah[0] == "sortcheck":
            if _ada_duplikat(mentah, 1, {"-s", "--select", "-d", "--delimiter"}, {"-s": "--select", "-d": "--delimiter", "-N": "--numeric", "-i": "--ignore-case", "-n": "--no-headers", "-p": "--progressbar"}):
                return False
            ops_qsc = _operand_sesudah_opsi(
                toks, awal + 1,
                {"-s", "--select", "-d", "--delimiter"})
            if "\x00gantung" in ops_qsc:
                return False
            return (not ops_qsc
                    or (len(ops_qsc) == 1
                        and ops_qsc[0] == "-"))
        # Audit 2230: sub 'frequency' - masukan
        # [<input>] opsional (frequency.rs).
        if mentah and mentah[0] == "frequency":
            if _ada_duplikat(mentah, 1, {"-s", "--select", "-l", "--limit", "-u", "--unq-limit", "--lmt-threshold", "--pct-dec-places", "--other-text", "-j", "--jobs", "-o", "--output", "-d", "--delimiter", "--weight", "--null-text", "--no-float", "--all-unique-text", "--high-card-pct", "--high-card-threshold", "--sketch-map-size", "--sketch-method", "--stats-filter", "-r", "--rank-strategy"}, {"-s": "--select", "-l": "--limit", "-u": "--unq-limit", "-j": "--jobs", "-o": "--output", "-d": "--delimiter", "-r": "--rank-strategy", "-a": "--asc", "-i": "--ignore-case", "-n": "--no-headers"}):
                return False
            ops_qfr = _operand_sesudah_opsi(
                toks, awal + 1,
                {"-s", "--select", "-l", "--limit", "-u",
                 "--unq-limit", "--lmt-threshold",
                 "--pct-dec-places", "--other-text", "-j",
                 "--jobs", "-o", "--output", "-d",
                 "--delimiter", "--weight",
                 "--null-text", "--no-float",
                 "--all-unique-text", "--high-card-pct",
                 "--high-card-threshold",
                 "--sketch-map-size", "--sketch-method",
                 "--stats-filter", "-r",
                 "--rank-strategy"})
            if "\x00gantung" in ops_qfr:
                return False
            return (not ops_qfr
                    or (len(ops_qfr) == 1
                        and ops_qfr[0] == "-"))
        # Audit 2237: sub 'geocode' - sub kolom
        # <column> [<input>] membaca stdin bila
        # masukan kosong/'-' (geocode.rs); sub
        # *now lokasi harfiah dan index-*/cache-*
        # tak membaca stdin.
        if (len(mentah) >= 2
                and mentah[0] == "geocode"):
            if _ada_duplikat(mentah, 1, {"--admin1", "--api-key", "--batch", "--benchmark", "--cache-dir", "--cache-ttl", "--census-mode", "--cities-url", "--country", "--delimiter", "--endpoint", "--formatstr", "--invalid-result", "--jobs", "--k_weight", "--language", "--languages", "--min-score", "--new-column", "--older-than", "--output", "--params", "--rate-limit", "--rename", "--timeout", "--vintage", "-b", "-c", "-d", "-f", "-j", "-k", "-l", "-o", "-r"}, {"-b": "--batch", "-c": "--new-column", "-d": "--delimiter", "-f": "--formatstr", "-j": "--jobs", "-k": "--k_weight", "-l": "--language", "-o": "--output", "-p": "--progressbar", "-r": "--rename"}):
                return False
            if mentah[1] in ("suggest", "reverse",
                             "countryinfo", "iplookup",
                             "opencage", "geoclient",
                             "census"):
                ops_qge = _operand_sesudah_opsi(
                    toks, awal + 2,
                    {"--formatstr", "-f", "-c",
                     "--new-column", "-r", "--rename",
                     "--country", "--min-score",
                     "--admin1", "-k", "--k_weight",
                     "--api-key", "--rate-limit",
                     "--cache-ttl", "--endpoint",
                     "--params", "--benchmark",
                     "--vintage", "--census-mode",
                     "-l", "--language",
                     "--invalid-result", "-j",
                     "--jobs", "-b", "--batch",
                     "--timeout", "--cache-dir",
                     "--older-than", "--languages",
                     "--cities-url", "-o", "--output",
                     "-d", "--delimiter"})
                if "\x00gantung" in ops_qge:
                    return False
                return (len(ops_qge) == 1
                        or (len(ops_qge) == 2
                            and ops_qge[1] == "-"))
            return False
        # Audit 2238: sub 'describegpt' -
        # masukan [<input>] kelompok Limited
        # Extended Input: kosong/'-' = stdin
        # (describegpt.rs).
        if mentah and mentah[0] == "describegpt":
            if _ada_duplikat(mentah, 1, {"--addl-cols-list", "--addl-props", "--api-key", "--base-url", "--cache-dir", "--ckan-api", "--ckan-token", "--context-file", "--disk-cache-dir", "--ds-license", "--ds-source", "--ds-updated", "--enum-threshold", "--export-prompt", "--format", "--freq-options", "--language", "--markdown-template", "--max-tokens", "--model", "--num-examples", "--num-tags", "--okf-type", "--output", "--prompt", "--prompt-file", "--sample-size", "--score-max-retries", "--score-threshold", "--session", "--session-len", "--sql-results", "--stats-options", "--tag-vocab", "--timeout", "--tour-audience", "--truncate-str", "--user-agent", "-k", "-m", "-o", "-p", "-t", "-u"}, {"-A": "--all", "-k": "--api-key", "-m": "--model", "-o": "--output", "-p": "--prompt", "-q": "--quiet", "-t": "--max-tokens", "-u": "--base-url"}):
                return False
            ops_qdg = _operand_sesudah_opsi(
                toks, awal + 1,
                {"-p", "--prompt", "-u", "--base-url",
                 "-m", "--model", "-k", "--api-key",
                 "-t", "--max-tokens", "-o",
                 "--output", "--num-examples",
                 "--truncate-str", "--addl-cols-list",
                 "--addl-props", "--cache-dir",
                 "--ckan-api", "--ckan-token",
                 "--context-file", "--disk-cache-dir",
                 "--ds-license", "--ds-source",
                 "--ds-updated", "--enum-threshold",
                 "--export-prompt", "--format",
                 "--freq-options", "--language",
                 "--markdown-template",
                 "--num-tags", "--okf-type",
                 "--prompt-file", "--sample-size",
                 "--score-max-retries",
                 "--score-threshold", "--session",
                 "--session-len", "--sql-results",
                 "--stats-options", "--tag-vocab",
                 "--timeout", "--tour-audience",
                 "--user-agent"})
            if "\x00gantung" in ops_qdg:
                return False
            return (not ops_qdg
                    or (len(ops_qdg) == 1
                        and ops_qdg[0] == "-"))
        # Audit 2241: sub 'searchset' -
        # (<regexset-file>) [<input>]
        # (searchset.rs).
        if mentah and mentah[0] == "searchset":
            if _ada_duplikat(mentah, 1, {"--delimiter", "--dfa-size-limit", "--flag", "--jobs", "--output", "--select", "--size-limit", "--unmatched-output", "-d", "-f", "-o", "-s"}, {"-Q": "--quick", "-c": "--count", "-d": "--delimiter", "-f": "--flag", "-i": "--ignore-case", "-j": "--json", "-n": "--no-headers", "-o": "--output", "-p": "--progressbar", "-q": "--quiet", "-s": "--select", "-u": "--unicode", "-v": "--invert-match"}):
                return False
            ops_qss = _operand_sesudah_opsi(
                toks, awal + 1,
                {"-s", "--select", "-f", "--flag",
                 "--unmatched-output", "--size-limit",
                 "--dfa-size-limit", "--jobs", "-o",
                 "--output", "-d", "--delimiter"})
            if "\x00gantung" in ops_qss:
                return False
            return (len(ops_qss) == 1
                    or (len(ops_qss) == 2
                        and ops_qss[1] == "-"))
        # Audit 2173: sub 'validate' - masukan
        # [<input>] opsional; tanpa masukan
        # membaca stdin (validate.rs).
        if mentah and mentah[0] == "validate":
            if _ada_duplikat(mentah, 1, {"--backtrack-limit", "--batch", "--cache-dir", "--ckan-api", "--ckan-token", "--delimiter", "--dfa-size-limit", "--email-min-subdomains", "--invalid", "--jobs", "--sarif", "--sarif-max-results", "--size-limit", "--timeout", "--valid", "--valid-output", "-b", "-d", "-j"}, {"-b": "--batch", "-d": "--delimiter", "-j": "--jobs", "-n": "--no-headers", "-p": "--progressbar", "-q": "--quiet"}):
                return False
            ops_qv = _operand_sesudah_opsi(
                toks, awal + 1,
                {"--valid", "--invalid", "--valid-output",
                 "-j", "--jobs", "-b", "--batch",
                 "--timeout", "-d", "--delimiter",
                 "--backtrack-limit", "--cache-dir",
                 "--ckan-api", "--ckan-token",
                 "--dfa-size-limit",
                 "--email-min-subdomains", "--sarif",
                 "--sarif-max-results", "--size-limit"})
            if "\x00gantung" in ops_qv:
                return False
            return not ops_qv or ops_qv[0] == "-"
        # Audit 2174: sub 'schema' - tanpa
        # masukan seluruh stdin disalin lebih
        # dulu (schema.rs).
        if mentah and mentah[0] == "schema":
            if _ada_duplikat(mentah, 1, {"--dates-whitelist", "--delimiter", "--enum-threshold", "--jobs", "--output", "--pattern-columns", "-d", "-j", "-o"}, {"-d": "--delimiter", "-i": "--ignore-case", "-j": "--jobs", "-n": "--no-headers", "-o": "--output"}):
                return False
            ops_qsc = _operand_sesudah_opsi(
                toks, awal + 1,
                {"--enum-threshold", "--pattern-columns",
                 "--dates-whitelist", "-j", "--jobs",
                 "-d", "--delimiter", "-o", "--output"})
            if "\x00gantung" in ops_qsc:
                return False
            return not ops_qsc or ops_qsc[0] == "-"
        if (len(mentah) >= 2 and mentah[0] == "cat"
                and mentah[1] in ("rows", "rowskey",
                                  "columns")):
            if _ada_duplikat(mentah, 1, {"--delimiter", "--group", "--group-name", "--output", "-N", "-d", "-g", "-o"}, {"-N": "--group-name", "-d": "--delimiter", "-g": "--group", "-n": "--no-headers", "-o": "--output", "-p": "--pad"}):
                return False
            ops_qs = _operand_sesudah_opsi(
                toks, awal + 2,
                {"-o", "--output", "-g", "--group",
                 "-N", "--group-name", "-d",
                 "--delimiter"})
            if "\x00gantung" in ops_qs:
                return False
            return (not ops_qs
                    or all(o == "-" for o in ops_qs))
        # Audit 2245: sub 'blake3' - [<input>...]
        # variadik; tanpa berkas atau ada '-'
        # membaca stdin; --keyed membaca kunci
        # dari stdin (blake3.rs:6,96).
        if mentah and mentah[0] == "blake3":
            if _ada_duplikat(mentah, 1, {"--derive-key", "--jobs", "--length", "--output", "-j", "-l", "-o"}, {"-c": "--check", "-j": "--jobs", "-l": "--length", "-o": "--output", "-q": "--quiet"}):
                return False
            ops_qb3 = _operand_sesudah_opsi(
                toks, awal + 1,
                {"-j", "--jobs", "-l", "--length",
                 "--derive-key", "-o", "--output"})
            if "\x00gantung" in ops_qb3:
                return False
            return (not ops_qb3 or "-" in ops_qb3
                    or "--keyed" in mentah)
        # Audit 2246: sub 'color' - masukan
        # [<input>] opsional; Config qsv memakai stdin bila
        # masukan kosong atau '-' (color.rs).
        if mentah and mentah[0] == "color":
            if _ada_duplikat(mentah, 1, {"--delimiter", "--output", "--title", "-d", "-o", "-t"}, {"-C": "--color", "-d": "--delimiter", "-n": "--row-numbers", "-o": "--output", "-t": "--title"}):
                return False
            ops_qco = _operand_sesudah_opsi(
                toks, awal + 1, {"-d", "--delimiter", "-o", "--output", "-t", "--title"})
            if "\x00gantung" in ops_qco:
                return False
            return (not ops_qco
                    or (len(ops_qco) == 1
                        and ops_qco[0] == "-"))
        # Audit 2247: sub 'datefmt' - <kolom>
        # [<input>]; Config qsv (datefmt.rs mandiri,
        # beda dari apply datefmt).
        if mentah and mentah[0] == "datefmt":
            if _ada_duplikat(mentah, 1, {"--batch", "--default-tz", "--delimiter", "--formatstr", "--input-tz", "--jobs", "--new-column", "--output", "--output-tz", "--rename", "--ts-resolution", "-R", "-b", "-c", "-d", "-j", "-o", "-r"}, {"-R": "--ts-resolution", "-b": "--batch", "-c": "--new-column", "-d": "--delimiter", "-j": "--jobs", "-n": "--no-headers", "-o": "--output", "-p": "--progressbar", "-r": "--rename"}):
                return False
            ops_qdf = _operand_sesudah_opsi(
                toks, awal + 1, {"-f", "--formatstr", "-c", "--new-column", "-r", "--rename", "--input-tz", "--output-tz", "--default-tz", "-R", "--ts-resolution", "-b", "--batch", "-j", "--jobs", "-d", "--delimiter", "-o", "--output"})
            if "\x00gantung" in ops_qdf:
                return False
            return (len(ops_qdf) == 1
                    or (len(ops_qdf) == 2
                        and ops_qdf[1] == "-"))
        # Audit 2248: sub 'denull' - masukan
        # [<input>] opsional; Config qsv memakai stdin bila
        # masukan kosong atau '-' (denull.rs).
        if mentah and mentah[0] == "denull":
            if _ada_duplikat(mentah, 1, {"--add-vocab", "--delimiter", "--max-distinct", "--output", "--select", "--vocab", "-d", "-o", "-s"}, {"-d": "--delimiter", "-n": "--no-headers", "-o": "--output", "-s": "--select"}):
                return False
            ops_qdn = _operand_sesudah_opsi(
                toks, awal + 1, {"-s", "--select", "-d", "--delimiter", "-o", "--output", "--add-vocab", "--vocab", "--max-distinct"})
            if "\x00gantung" in ops_qdn:
                return False
            return (not ops_qdn
                    or (len(ops_qdn) == 1
                        and ops_qdn[0] == "-"))
        # Audit 2249: sub 'enum' - masukan
        # [<input>] opsional; Config qsv memakai stdin bila
        # masukan kosong atau '-' (enumerate.rs).
        if mentah and mentah[0] == "enum":
            if _ada_duplikat(mentah, 1, {"--constant", "--copy", "--delimiter", "--hash", "--increment", "--new-column", "--output", "--start", "-c", "-d", "-o"}, {"-c": "--new-column", "-d": "--delimiter", "-n": "--no-headers", "-o": "--output"}):
                return False
            ops_qen = _operand_sesudah_opsi(
                toks, awal + 1, {"-c", "--new-column", "--copy", "--constant", "--start", "--increment", "--hash", "-d", "--delimiter", "-o", "--output"})
            if "\x00gantung" in ops_qen:
                return False
            return (not ops_qen
                    or (len(ops_qen) == 1
                        and ops_qen[0] == "-"))
        # Audit 2250: sub 'explode' - <column>
        # <separator> [<input>]; Config qsv
        # (explode.rs).
        if mentah and mentah[0] == "explode":
            if _ada_duplikat(mentah, 1, {"--delimiter", "--output", "--rename", "-d", "-o", "-r"}, {"-d": "--delimiter", "-n": "--no-headers", "-o": "--output", "-r": "--rename"}):
                return False
            ops_qex = _operand_sesudah_opsi(
                toks, awal + 1,
                {"-r", "--rename", "-d", "--delimiter",
                 "-o", "--output"})
            if "\x00gantung" in ops_qex:
                return False
            return (len(ops_qex) == 2
                    or (len(ops_qex) == 3
                        and ops_qex[2] == "-"))
        # Audit 2251: sub 'extdedup' - [<input>]
        # [<output>]; Config qsv - masukan pertama
        # kosong/'-' berarti stdin (extdedup.rs).
        if mentah and mentah[0] == "extdedup":
            if _ada_duplikat(mentah, 1, {"--delimiter", "--dupes-output", "--memory-limit", "--no-output", "--select", "--temp-dir", "-D", "-d", "-s"}, {"-D": "--dupes-output", "-H": "--human-readable", "-d": "--delimiter", "-n": "--no-headers", "-q": "--quiet", "-s": "--select"}):
                return False
            ops_qed = _operand_sesudah_opsi(
                toks, awal + 1,
                {"-s", "--select", "-D", "--dupes-output",
                 "--memory-limit", "--temp-dir",
                 "-d", "--delimiter"})
            if "\x00gantung" in ops_qed:
                return False
            return (not ops_qed
                    or ops_qed[0] == "-")
        # Audit 2252: sub 'fetchpost' -
        # (<url-column>) (<column-list> |
        # --payload-tpl <file>) [<input>]; Config
        # qsv (fetchpost.rs).
        if mentah and mentah[0] == "fetchpost":
            if _ada_duplikat(mentah, 1, {"--http-header", "--content-type", "--default-encoding", "--delimiter", "--disk-cache-dir", "--globals-json", "--jaq", "--jaqfile", "--max-errors", "--max-retries", "--mem-cache-size", "--new-column", "--output", "--payload-tpl", "--rate-limit", "--report", "--timeout", "--user-agent", "-H", "-c", "-d", "-j", "-o", "-t"}, {"-H": "--http-header", "-c": "--new-column", "-d": "--delimiter", "-j": "--globals-json", "-n": "--no-headers", "-o": "--output", "-p": "--progressbar", "-t": "--payload-tpl"}, {"--http-header"}):
                return False
            ops_qfp = _operand_sesudah_opsi(
                toks, awal + 1,
                {"-H", "--http-header", "-c",
                 "--new-column", "--jaq", "--jaqfile",
                 "--payload-tpl", "-t", "--timeout",
                 "--rate-limit", "--max-retries",
                 "--max-errors", "--report",
                 "--mem-cache-size", "--disk-cache-dir",
                 "--globals-json", "--content-type",
                 "--default-encoding", "--user-agent",
                 "-j", "--globals-json", "-d",
                 "--delimiter", "-o", "--output"})
            if "\x00gantung" in ops_qfp:
                return False
            # Audit 2300: kehadiran --payload-tpl
            # dinilai sadar posisi opsi (nilai opsi
            # lain yang berbunyi '-t' tak dihitung).
            ada_tpl_qfp = False
            lewat_qfp = False
            for t_qfp2 in mentah[1:]:
                if lewat_qfp:
                    lewat_qfp = False
                    continue
                if t_qfp2 == "--":
                    break
                if t_qfp2 in ("--payload-tpl", "-t"):
                    ada_tpl_qfp = True
                    lewat_qfp = True
                    continue
                if t_qfp2.startswith("--payload-tpl="):
                    ada_tpl_qfp = True
                    continue
                if (len(t_qfp2) > 2
                        and t_qfp2.startswith("-t")
                        and not t_qfp2.startswith("--")):
                    ada_tpl_qfp = True
                    continue
                if ("=" not in t_qfp2
                        and t_qfp2 in (
                            "-H", "--http-header", "-c",
                            "--new-column", "--jaq",
                            "--jaqfile", "--timeout",
                            "--rate-limit", "--max-retries",
                            "--max-errors", "--report",
                            "--mem-cache-size",
                            "--disk-cache-dir",
                            "--globals-json",
                            "--content-type",
                            "--default-encoding",
                            "--user-agent", "-j", "-d",
                            "--delimiter", "-o",
                            "--output")):
                    lewat_qfp = True
            dasar_qfp = 1 if ada_tpl_qfp else 2
            return (len(ops_qfp) == dasar_qfp
                    or (len(ops_qfp) == dasar_qfp + 1
                        and ops_qfp[dasar_qfp] == "-"))
        # Audit 2253: sub 'fill' - <selection>
        # [<input>]; Config qsv (fill.rs).
        if mentah and mentah[0] == "fill":
            if _ada_duplikat(mentah, 1, {"--default", "--delimiter", "--groupby", "--output", "-d", "-g", "-o", "-v"}, {"-b": "--backfill", "-d": "--delimiter", "-f": "--first", "-g": "--groupby", "-n": "--no-headers", "-o": "--output", "-v": "--default"}):
                return False
            ops_qfl = _operand_sesudah_opsi(
                toks, awal + 1,
                {"-g", "--groupby", "-v", "--default",
                 "-d", "--delimiter", "-o", "--output"})
            if "\x00gantung" in ops_qfl:
                return False
            return (len(ops_qfl) == 1
                    or (len(ops_qfl) == 2
                        and ops_qfl[1] == "-"))
        # Audit 2254: sub 'fixedwidth' - masukan
        # [<input>] opsional; Config::new di fixedwidth.rs:134 -
        # stdin bila kosong atau '-'.
        if mentah and mentah[0] == "fixedwidth":
            if _ada_duplikat(mentah, 1, {"--positions", "--widths", "-o", "--output"}, {"-o": "--output"}):
                return False
            ops_qfw = _operand_sesudah_opsi(
                toks, awal + 1, {"--positions", "--widths", "-o", "--output"})
            if "\x00gantung" in ops_qfw:
                return False
            return (not ops_qfw
                    or (len(ops_qfw) == 1
                        and ops_qfw[0] == "-"))
        # Audit 2255: sub 'flatten' - masukan
        # [<input>] opsional; Config qsv memakai stdin bila
        # masukan kosong atau '-' (flatten.rs).
        if mentah and mentah[0] == "flatten":
            if _ada_duplikat(mentah, 1, {"--condense", "--delimiter", "--field-separator", "--separator", "-c", "-d", "-f", "-s"}, {"-c": "--condense", "-d": "--delimiter", "-f": "--field-separator", "-n": "--no-headers", "-s": "--separator"}):
                return False
            ops_qft = _operand_sesudah_opsi(
                toks, awal + 1, {"-s", "--separator", "-f", "--field-separator", "-c", "--condense", "-d", "--delimiter"})
            if "\x00gantung" in ops_qft:
                return False
            return (not ops_qft
                    or (len(ops_qft) == 1
                        and ops_qft[0] == "-"))
        # Audit 2256: sub 'fmt' - masukan
        # [<input>] opsional; Config qsv memakai stdin bila
        # masukan kosong atau '-' (fmt.rs).
        if mentah and mentah[0] == "fmt":
            if _ada_duplikat(mentah, 1, {"--delimiter", "--escape", "--out-delimiter", "--output", "--quote", "-d", "-o", "-t"}, {"-d": "--delimiter", "-o": "--output", "-t": "--out-delimiter"}):
                return False
            ops_qfm = _operand_sesudah_opsi(
                toks, awal + 1, {"-t", "--out-delimiter", "-d", "--delimiter", "--escape", "--quote", "-o", "--output"})
            if "\x00gantung" in ops_qfm:
                return False
            return (not ops_qfm
                    or (len(ops_qfm) == 1
                        and ops_qfm[0] == "-"))
        # Audit 2257: sub 'implode' - <separator>
        # [<input>] (-k/-v wajib bernilai); Config
        # qsv (implode.rs).
        if mentah and mentah[0] == "implode":
            if _ada_duplikat(mentah, 1, {"--delimiter", "--keys", "--output", "--rename", "--value", "-d", "-k", "-o", "-r", "-v"}, {"-d": "--delimiter", "-k": "--keys", "-n": "--no-headers", "-o": "--output", "-r": "--rename", "-v": "--value"}):
                return False
            ops_qim = _operand_sesudah_opsi(
                toks, awal + 1,
                {"-k", "--keys", "-v", "--value",
                 "-r", "--rename", "-d", "--delimiter",
                 "-o", "--output"})
            if "\x00gantung" in ops_qim:
                return False
            return (len(ops_qim) == 1
                    or (len(ops_qim) == 2
                        and ops_qim[1] == "-"))
        # Audit 2258: sub 'json' - masukan
        # [<input>] opsional; json.rs:108,173 - stdin bila tanpa
        # path atau '-'.
        if mentah and mentah[0] == "json":
            if _ada_duplikat(mentah, 1, {"--jaq", "--output", "--select", "-o", "-s"}, {"-o": "--output", "-s": "--select"}):
                return False
            ops_qjs = _operand_sesudah_opsi(
                toks, awal + 1, {"-s", "--select", "--jaq", "-o", "--output"})
            if "\x00gantung" in ops_qjs:
                return False
            return (not ops_qjs
                    or (len(ops_qjs) == 1
                        and ops_qjs[0] == "-"))
        # Audit 2259: sub 'jsonl' - masukan
        # [<input>] opsional; jsonl.rs:165-168 - None/'-' =
        # io::stdin().lock().
        if mentah and mentah[0] == "jsonl":
            if _ada_duplikat(mentah, 1, {"--batch", "--delimiter", "--jobs", "--output", "-b", "-d", "-j", "-o"}, {"-b": "--batch", "-d": "--delimiter", "-j": "--jobs", "-o": "--output"}):
                return False
            ops_qjl = _operand_sesudah_opsi(
                toks, awal + 1, {"-b", "--batch", "-j", "--jobs", "-d", "--delimiter", "-o", "--output"})
            if "\x00gantung" in ops_qjl:
                return False
            return (not ops_qjl
                    or (len(ops_qjl) == 1
                        and ops_qjl[0] == "-"))
        # Audit 2260: sub 'lens' - masukan
        # [<input>] opsional; lens.rs:148 - tanpa berkas atau '-'
        # ditangani sebagai stdin.
        if mentah and mentah[0] == "lens":
            if _ada_duplikat(mentah, 1, {"--columns", "--delimiter", "--echo-column", "--filter", "--find", "--freeze-columns", "--prompt", "--wrap-mode", "-P", "-W", "-d", "-f"}, {"-A": "--auto-reload", "-P": "--prompt", "-S": "--streaming-stdin", "-W": "--wrap-mode", "-d": "--delimiter", "-f": "--freeze-columns", "-i": "--ignore-case", "-m": "--monochrome", "-t": "--tab-separated"}):
                return False
            ops_qln = _operand_sesudah_opsi(
                toks, awal + 1, {"-d", "--delimiter", "-f", "--filter", "--find", "--columns", "--echo-column", "--freeze-columns", "-P", "--prompt", "-W", "--wrap-mode"})
            if "\x00gantung" in ops_qln:
                return False
            return (not ops_qln
                    or (len(ops_qln) == 1
                        and ops_qln[0] == "-"))
        # Audit 2261: sub 'tojsonl' - masukan
        # [<input>] opsional; tojsonl.rs:106-107 - tanpa masukan
        # dibaca sebagai '-' (stdin).
        if mentah and mentah[0] == "tojsonl":
            if _ada_duplikat(mentah, 1, {"--batch", "--delimiter", "--jobs", "--output", "-b", "-d", "-j", "-o"}, {"-b": "--batch", "-d": "--delimiter", "-j": "--jobs", "-o": "--output", "-q": "--quiet"}):
                return False
            ops_qtj = _operand_sesudah_opsi(
                toks, awal + 1, {"-b", "--batch", "-j", "--jobs", "-d", "--delimiter", "-o", "--output"})
            if "\x00gantung" in ops_qtj:
                return False
            return (not ops_qtj
                    or (len(ops_qtj) == 1
                        and ops_qtj[0] == "-"))
        # Audit 2262: sub 'moarstats' - masukan
        # [<input>] opsional; moarstats.rs - is_stdin +
        # pass_stdin_spool; kosong/'-' stdin.
        if mentah and mentah[0] == "moarstats":
            if _ada_duplikat(mentah, 1, {"--bivariate-batch", "--bivariate-stats", "--cardinality-threshold", "--epsilon", "--jobs", "--join-inputs", "--join-keys", "--join-type", "--output", "--pct-thresholds", "--round", "--stats-options", "--xsd-gdate-scan", "-C", "-J", "-K", "-S", "-T", "-e", "-j", "-o"}, {"-B": "--bivariate", "-C": "--cardinality-threshold", "-J": "--join-inputs", "-K": "--join-keys", "-S": "--bivariate-stats", "-T": "--join-type", "-e": "--epsilon", "-j": "--jobs", "-o": "--output", "-p": "--progressbar"}):
                return False
            ops_qms = _operand_sesudah_opsi(
                toks, awal + 1, {"-j", "--jobs", "-o", "--output", "-e", "--epsilon", "-C", "--cardinality-threshold", "-T", "--pct-thresholds", "-S", "--stats-options", "-J", "--join-inputs", "-K", "--join-keys", "--join-type", "--bivariate-stats", "--bivariate-batch", "--round", "--xsd-gdate-scan"})
            if "\x00gantung" in ops_qms:
                return False
            return (not ops_qms
                    or (len(ops_qms) == 1
                        and ops_qms[0] == "-"))
        # Audit 2263: sub 'pragmastat' - masukan
        # [<input>] opsional; Config qsv memakai stdin bila
        # masukan kosong atau '-' (pragmastat.rs).
        if mentah and mentah[0] == "pragmastat":
            if _ada_duplikat(mentah, 1, {"--compare1", "--compare2", "--delimiter", "--jobs", "--misrate", "--output", "--round", "--seed", "--select", "--stats-options", "--subsample", "-d", "-j", "-m", "-o", "-s"}, {"-d": "--delimiter", "-j": "--jobs", "-m": "--misrate", "-n": "--no-headers", "-o": "--output", "-s": "--select", "-t": "--twosample"}):
                return False
            ops_qpm = _operand_sesudah_opsi(
                toks, awal + 1, {"-s", "--select", "-d", "--delimiter", "-j", "--jobs", "-o", "--output", "-m", "--misrate", "--compare1", "--compare2", "--round", "--seed", "--stats-options", "--subsample"})
            if "\x00gantung" in ops_qpm:
                return False
            return (not ops_qpm
                    or (len(ops_qpm) == 1
                        and ops_qpm[0] == "-"))
        # Audit 2264: sub 'profile' - masukan
        # [<input>] opsional; profile.rs:101,360 - stdin
        # dimaterialkan ke berkas temp.
        if mentah and mentah[0] == "profile":
            if _ada_duplikat(mentah, 1, {"--dcat-discovery-timeout", "--delimiter", "--describegpt", "--initial-context", "--jobs", "--locate", "--output", "--profile", "--spec", "--tag-vocab", "-d", "-j", "-o"}, {"-d": "--delimiter", "-j": "--jobs", "-n": "--no-headers", "-o": "--output"}):
                return False
            ops_qpf = _operand_sesudah_opsi(
                toks, awal + 1, {"-d", "--delimiter", "-j", "--jobs", "-o", "--output", "--describegpt", "--initial-context", "--locate", "--profile", "--spec", "--tag-vocab", "--dcat-discovery-timeout"})
            if "\x00gantung" in ops_qpf:
                return False
            return (not ops_qpf
                    or (len(ops_qpf) == 1
                        and ops_qpf[0] == "-"))
        # Audit 2265: sub 'pseudo' - <kolom>
        # [<input>]; Config qsv (pseudo.rs).
        if mentah and mentah[0] == "pseudo":
            if _ada_duplikat(mentah, 1, {"--delimiter", "--formatstr", "--increment", "--output", "--start", "-d", "-o"}, {"-d": "--delimiter", "-n": "--no-headers", "-o": "--output"}):
                return False
            ops_qps = _operand_sesudah_opsi(
                toks, awal + 1, {"-f", "--formatstr", "--start", "--increment", "-d", "--delimiter", "-o", "--output"})
            if "\x00gantung" in ops_qps:
                return False
            return (len(ops_qps) == 1
                    or (len(ops_qps) == 2
                        and ops_qps[1] == "-"))
        # Audit 2266: sub 'safenames' - masukan
        # [<input>] opsional; Config qsv memakai stdin bila
        # masukan kosong atau '-' (safenames.rs).
        if mentah and mentah[0] == "safenames":
            if _ada_duplikat(mentah, 1, {"--delimiter", "--mode", "--output", "--prefix", "--reserved", "-d", "-o"}, {"-d": "--delimiter", "-o": "--output"}):
                return False
            ops_qsf = _operand_sesudah_opsi(
                toks, awal + 1, {"-d", "--delimiter", "-o", "--output", "--mode", "--prefix", "--reserved"})
            if "\x00gantung" in ops_qsf:
                return False
            return (not ops_qsf
                    or (len(ops_qsf) == 1
                        and ops_qsf[0] == "-"))
        # Audit 2267: sub 'sniff' - masukan
        # [<input>] opsional; sniff.rs:53,796 - stdin dibaca ke
        # berkas temp.
        if mentah and mentah[0] == "sniff":
            if _ada_duplikat(mentah, 1, {"--delimiter", "--quote", "--sample", "--save-urlsample", "--timeout", "--user-agent", "-d"}, {"-Q": "--quick", "-d": "--delimiter", "-p": "--progressbar"}):
                return False
            ops_qsn2 = _operand_sesudah_opsi(
                toks, awal + 1, {"-d", "--delimiter", "--quote", "--sample", "--save-urlsample", "--timeout", "--user-agent"})
            if "\x00gantung" in ops_qsn2:
                return False
            return (not ops_qsn2
                    or (len(ops_qsn2) == 1
                        and ops_qsn2[0] == "-"))
        # Audit 2268: sub 'template' - [<input>]
        # [<outdir>]; Config qsv - masukan pertama
        # kosong/'-' berarti stdin (template.rs).
        if mentah and mentah[0] == "template":
            if _ada_duplikat(mentah, 1, {"--batch", "--cache-dir", "--ckan-api", "--ckan-token", "--customfilter-error", "--delimiter", "--globals-json", "--jobs", "--outfilename", "--output", "--outsubdir-size", "--template", "--template-file", "--timeout", "-J", "-b", "-j", "-o", "-t"}, {"-J": "--globals-json", "-b": "--batch", "-j": "--jobs", "-n": "--no-headers", "-o": "--output", "-p": "--progressbar", "-t": "--template-file"}):
                return False
            ops_qtp = _operand_sesudah_opsi(
                toks, awal + 1,
                {"-t", "--template", "--template-file",
                 "-b", "--batch", "-j", "--jobs",
                 "--cache-dir", "--ckan-api",
                 "--ckan-token", "--customfilter-error",
                 "--globals-json", "-J", "--outfilename",
                 "--outsubdir-size", "--timeout",
                 "--delimiter", "-o", "--output"})
            if "\x00gantung" in ops_qtp:
                return False
            return (not ops_qtp
                    or ops_qtp[0] == "-")
        # Audit 2269: sub 'viz' - <input> wajib;
        # semua tipe chart kecuali smart menerima
        # '-' sebagai stdin (viz.rs:125,257).
        if mentah and mentah[0] == "viz":
            if _ada_duplikat(mentah, 1, {"--agg", "--annotation", "--bins", "--box-points", "--close", "--cluster", "--color", "--color-scale", "--cols", "--dataset-pid", "--delimiter", "--denominator", "--denominator-key", "--denominator-unit", "--dictionary", "--dictionary-context", "--feature-id-key", "--feature-name-key", "--geocode-admin1", "--geocode-country", "--geojson", "--grid-cols", "--heatmap-density", "--height", "--hierarchy-style", "--high", "--language", "--lat", "--limit", "--location-mode", "--locations", "--log-scale", "--lon", "--low", "--max-charts", "--ohlc-open", "--output", "--preview-threshold", "--projection", "--region-state", "--scale", "--series", "--size", "--slider", "--slider-speed", "--snap-max-dist", "--source", "--style", "--target", "--text", "--theme", "--title", "--tour-audience", "--tour-steps", "--value", "--violin", "--width", "--x", "--x-title", "--y", "--y-range", "--y-title", "--z", "-d", "-o", "-x", "-y", "-z"}, {"-d": "--delimiter", "-n": "--no-headers", "-o": "--output", "-x": "--x", "-y": "--y", "-z": "--z"}):
                return False
            if len(mentah) < 2 or mentah[1] == "smart":
                return False
            ops_qvz = _operand_sesudah_opsi(
                toks, awal + 2,
                {"-o", "--output", "-d", "--delimiter",
                 "--agg", "--annotation", "--bins",
                 "--box-points", "--close",
                 "--cluster", "--color",
                 "--color-scale", "--cols",
                 "--dataset-pid", "--denominator",
                 "--denominator-key",
                 "--denominator-unit", "--dictionary",
                 "--dictionary-context",
                 "--feature-id-key",
                 "--feature-name-key",
                 "--geocode-admin1", "--geocode-country",
                 "--geojson", "--grid-cols",
                 "--heatmap-density", "--height",
                 "--hierarchy-style", "--high",
                 "--language", "--lat", "--limit",
                 "--location-mode", "--locations",
                 "--log-scale", "--lon", "--low",
                 "--max-charts", "--ohlc-open",
                 "--preview-threshold", "--projection",
                 "--region-state", "--scale",
                 "--series", "--size", "--slider",
                 "--slider-speed", "--snap-max-dist",
                 "--source", "--style", "--target",
                 "--text", "--theme", "--title",
                 "--tour-audience", "--tour-steps",
                 "--value", "--violin", "--width",
                 "--x", "-x", "--y", "-y", "--z", "-z",
                 "--x-title", "--y-range", "--y-title"})
            if "\x00gantung" in ops_qvz:
                return False
            return ops_qvz == ["-"]
        # Audit 2270: sub 'writestat' - masukan
        # [<input>] opsional; writestat.rs:411-418 - stdin
        # disepul ke stdin.csv.
        if mentah and mentah[0] == "writestat":
            if _ada_duplikat(mentah, 1, {"--delimiter", "--dictionary", "--format", "--output", "--table-name", "-d", "-o"}, {"-d": "--delimiter", "-o": "--output"}):
                return False
            ops_qws = _operand_sesudah_opsi(
                toks, awal + 1, {"-d", "--delimiter", "-o", "--output", "--dictionary", "--format", "--table-name"})
            if "\x00gantung" in ops_qws:
                return False
            return (not ops_qws
                    or (len(ops_qws) == 1
                        and ops_qws[0] == "-"))
        # Audit 2271: sub 'clipboard' - tanpa
        # operand; hanya --save/-s yang menguras
        # (clipboard.rs:75-77 membaca io::stdin);
        # tanpa itu ia menulis clipboard ke stdout.
        if mentah and mentah[0] == "clipboard":
            # Audit 2301/2302: bundel hanya boleh
            # memuat s/h; '--' dan token asing =
            # galat docopt (tak menguras).
            ada_qcb = False
            jumlah_qcb = 0
            for i_qcb, t_qcb in enumerate(mentah[1:]):
                if t_qcb == "--":
                    return (ada_qcb
                            and i_qcb == len(mentah) - 2)
                if t_qcb == "--save":
                    ada_qcb = True
                    jumlah_qcb += 1
                    if jumlah_qcb > 1:
                        return False
                    continue
                if t_qcb.startswith("--"):
                    return False
                if (t_qcb.startswith("-")
                        and len(t_qcb) > 1):
                    for h_qcb in t_qcb[1:]:
                        if h_qcb == "s":
                            ada_qcb = True
                            jumlah_qcb += 1
                            if jumlah_qcb > 1:
                                return False
                        else:
                            return False
                    continue
                return False
            return ada_qcb
        # Audit 2272: sub 'edit' - <input>
        # <column> <row> <value>; input '-'
        # menguras kecuali --in-place yang
        # menolak stdin (edit.rs:87-92).
        if mentah and mentah[0] == "edit":
            if _ada_duplikat(mentah, 1, {"--output", "-o"}, {"-i": "--in-place", "-n": "--no-headers", "-o": "--output"}):
                return False
            ops_qet = _operand_sesudah_opsi(
                toks, awal + 1, {"-o", "--output"})
            if "\x00gantung" in ops_qet:
                return False
            return (len(ops_qet) == 4
                    and ops_qet[0] == "-"
                    and "--in-place" not in mentah)
        # Audit 2273: sub 'excel' - MEMBALIK
        # vonis 0.128.0 Audit 141: di master,
        # '-' dibaca dari stdin ke berkas temp
        # (excel.rs:78,459-474); tanpa operand
        # docopt menolak (arg_input wajib).
        if mentah and mentah[0] == "excel":
            if _ada_duplikat(mentah, 1, {"--cell", "--date-format", "--delimiter", "--error-format", "--header-row", "--jobs", "--metadata", "--output", "--range", "--sheet", "--table", "-d", "-j", "-o", "-s"}, {"-d": "--delimiter", "-j": "--jobs", "-o": "--output", "-q": "--quiet", "-s": "--sheet"}):
                return False
            ops_qxc = _operand_sesudah_opsi(
                toks, awal + 1,
                {"-s", "--sheet", "--range", "--cell",
                 "--metadata", "--date-format",
                 "--error-format", "--header-row",
                 "--table", "-j", "--jobs",
                 "-d", "--delimiter", "-o", "--output"})
            if "\x00gantung" in ops_qxc:
                return False
            return ops_qxc == ["-"]
        # Audit 2274: sub 'exclude' - <columns1>
        # <input1> <columns2> <input2>; salah satu
        # input '-' berarti stdin, keduanya '-'
        # galat (exclude.rs:16,116).
        if mentah and mentah[0] == "exclude":
            if _ada_duplikat(mentah, 1, {"--delimiter", "--memcheck", "--output", "-d", "-o"}, {"-d": "--delimiter", "-i": "--ignore-case", "-n": "--no-headers", "-o": "--output", "-v": "--invert"}):
                return False
            ops_qxu = _operand_sesudah_opsi(
                toks, awal + 1,
                {"-d", "--delimiter", "-o", "--output"})
            if "\x00gantung" in ops_qxu:
                return False
            return (len(ops_qxu) == 4
                    and (ops_qxu[1] == "-"
                         or ops_qxu[3] == "-"))
        # Audit 2275: sub 'geoconvert' - (<input>)
        # (<input-format>) (<output-format>); '-'
        # berarti stdin (geoconvert.rs:8,240).
        if mentah and mentah[0] == "geoconvert":
            if _ada_duplikat(mentah, 1, {"--delimiter", "--geometry", "--latitude", "--longitude", "--max-length", "--output", "-d", "-g", "-l", "-o", "-x", "-y"}, {"-d": "--delimiter", "-g": "--geometry", "-l": "--max-length", "-o": "--output", "-x": "--longitude", "-y": "--latitude"}):
                return False
            ops_qgc = _operand_sesudah_opsi(
                toks, awal + 1,
                {"-d", "--delimiter", "-g", "--geometry",
                 "-x", "--longitude", "-y", "--latitude",
                 "--max-length", "-o", "--output"})
            if "\x00gantung" in ops_qgc:
                return False
            return (len(ops_qgc) == 3
                    and ops_qgc[0] == "-")
        # Audit 2276: sub 'scoresql' - <input>...
        # <sql>; input '-' disepul dari stdin
        # (scoresql.rs:248-257).
        if mentah and mentah[0] == "scoresql":
            if _ada_duplikat(mentah, 1, {"--delimiter", "--infer-len", "--output", "-d", "-o"}, {"-d": "--delimiter", "-o": "--output", "-q": "--quiet"}):
                return False
            ops_qsq = _operand_sesudah_opsi(
                toks, awal + 1,
                {"-d", "--delimiter", "--infer-len",
                 "-o", "--output"})
            if "\x00gantung" in ops_qsq:
                return False
            return (len(ops_qsq) >= 2
                    and "-" in ops_qsq[:-1])
        # Audit 2322: sub 'fixlengths' qsv -
        # stdin hanya sah bila --length terpasang
        # sah (digit, > 0) dan --remove-empty
        # absen (fixlengths.rs:77-80,113-141);
        # cabang fixlengths lama milik xsv.
        if mentah and mentah[0] == "fixlengths":
            if _ada_duplikat(mentah, 1, {"-l", "--length", "-o", "--output", "-d", "--delimiter", "--escape", "-i", "--insert", "--quote"}, {"-l": "--length", "-o": "--output", "-d": "--delimiter", "-r": "--remove-empty", "-i": "--insert", "-q": "--quiet"}):
                return False
            nilai_qfx = None
            ops_qfx = []
            buang_qfx = False
            i_qfx = 1
            while i_qfx < len(mentah):
                t_qfx = mentah[i_qfx]
                if t_qfx == "--":
                    ops_qfx.extend(mentah[i_qfx + 1:])
                    break
                if t_qfx in ("-r", "--remove-empty"):
                    buang_qfx = True
                    i_qfx += 1
                    continue
                if t_qfx in ("-l", "--length"):
                    if i_qfx + 1 >= len(mentah):
                        return False
                    nilai_qfx = mentah[i_qfx + 1]
                    i_qfx += 2
                    continue
                if t_qfx.startswith("--length="):
                    nilai_qfx = t_qfx.split("=", 1)[1]
                    i_qfx += 1
                    continue
                if t_qfx in ("-o", "--output", "-d",
                             "--delimiter", "--escape",
                             "-i", "--insert", "--quote"):
                    if i_qfx + 1 >= len(mentah):
                        return False
                    i_qfx += 2
                    continue
                if any(t_qfx.startswith(p_qfx + "=")
                       for p_qfx in ("--output",
                                     "--delimiter",
                                     "--escape",
                                     "--insert",
                                     "--quote")):
                    i_qfx += 1
                    continue
                if (t_qfx.startswith("-")
                        and not t_qfx.startswith("--")
                        and len(t_qfx) > 1):
                    sah_qfx = True
                    j_qfx = 1
                    while j_qfx < len(t_qfx):
                        h_qfx = t_qfx[j_qfx]
                        if h_qfx == "r":
                            buang_qfx = True
                            j_qfx += 1
                            continue
                        if h_qfx == "q":
                            j_qfx += 1
                            continue
                        if h_qfx == "l":
                            sisa_qfx = t_qfx[j_qfx + 1:]
                            if sisa_qfx:
                                nilai_qfx = sisa_qfx
                            elif i_qfx + 1 < len(mentah):
                                nilai_qfx = mentah[i_qfx + 1]
                                i_qfx += 1
                            else:
                                return False
                            j_qfx = len(t_qfx)
                            continue
                        if h_qfx in ("o", "d", "i"):
                            if j_qfx + 1 >= len(t_qfx):
                                if i_qfx + 1 >= len(mentah):
                                    return False
                                i_qfx += 1
                            j_qfx = len(t_qfx)
                            continue
                        sah_qfx = False
                        break
                    if not sah_qfx:
                        return False
                    i_qfx += 1
                    continue
                if t_qfx == "-" or not t_qfx.startswith("-"):
                    ops_qfx.append(t_qfx)
                elif t_qfx.startswith("--"):
                    return False
                i_qfx += 1
            if not (not ops_qfx or ops_qfx == ["-"]):
                return False
            if buang_qfx or nilai_qfx is None:
                return False
            return (nilai_qfx.isdigit()
                    and 0 < int(nilai_qfx)
                    <= 18446744073709551615)
        # Audit 2323: sub 'table' qsv - masukan
        # [<input>] opsional tanpa penjaga stdin
        # (table.rs); cabang table lama milik xsv.
        if mentah and mentah[0] == "table":
            if _ada_duplikat(mentah, 1, {"-w", "--width", "-a", "--align", "-c", "--condense", "-o", "--output", "-d", "--delimiter", "-p", "--pad"}, {"-w": "--width", "-a": "--align", "-c": "--condense", "-o": "--output", "-d": "--delimiter", "-p": "--pad"}):
                return False
            ops_qtb = _operand_sesudah_opsi(
                toks, awal + 1,
                {"-w", "--width", "-a", "--align",
                 "-c", "--condense", "-o", "--output",
                 "-d", "--delimiter", "-p", "--pad"})
            if "\x00gantung" in ops_qtb:
                return False
            return not ops_qtb or ops_qtb == ["-"]
        # Audit 2286: sub 'prompt' - bentuk
        # polos membuka dialog pemilih berkas
        # GUI (tak menyentuh stdin); bentuk
        # -o/--output dan -f/--fd-output
        # menyalin stdin ke berkas tujuan
        # (prompt.rs:79-87).
        if mentah and mentah[0] == "prompt":
            # Audit 2298/2299: pindaian sadar nilai
            # Audit 2319: bendera duplikat ditolak
            # docopt (maks 1 kemunculan per opsi).
            # opsi + bundel docopt + '--' + gantung.
            bernilai_qpr = {"-m", "--msg", "-F",
                            "--filters", "-d", "--workdir",
                            "--save-fname", "--base-delay-ms"}
            kanon_qpr = {"-m": "m", "--msg": "m",
                         "-F": "F", "--filters": "F",
                         "-d": "d", "--workdir": "d",
                         "--save-fname": "s",
                         "--base-delay-ms": "b"}
            ada_qpr = False
            fd_qpr = False
            hitung_qpr = {}
            i_qpr = 1
            while i_qpr < len(mentah):
                t_qpr = mentah[i_qpr]
                if t_qpr == "--":
                    return (ada_qpr != fd_qpr
                            and i_qpr == len(mentah) - 1
                            and max(hitung_qpr.values(),
                                    default=0) <= 1)
                if t_qpr in ("-f", "--fd-output"):
                    fd_qpr = True
                    hitung_qpr["f"] = hitung_qpr.get("f", 0) + 1
                    i_qpr += 1
                    continue
                if t_qpr in ("-o", "--output"):
                    if i_qpr + 1 >= len(mentah):
                        return False
                    ada_qpr = True
                    hitung_qpr["o"] = hitung_qpr.get("o", 0) + 1
                    i_qpr += 2
                    continue
                if t_qpr.startswith("--output="):
                    ada_qpr = True
                    hitung_qpr["o"] = hitung_qpr.get("o", 0) + 1
                    i_qpr += 1
                    continue
                if t_qpr in bernilai_qpr:
                    if i_qpr + 1 >= len(mentah):
                        return False
                    k_qpr = kanon_qpr[t_qpr]
                    hitung_qpr[k_qpr] = hitung_qpr.get(k_qpr, 0) + 1
                    i_qpr += 2
                    continue
                nama_qpr = [p_qpr for p_qpr in bernilai_qpr
                            if p_qpr.startswith("--")
                            and t_qpr.startswith(p_qpr + "=")]
                if nama_qpr:
                    k_qpr = kanon_qpr[nama_qpr[0]]
                    hitung_qpr[k_qpr] = hitung_qpr.get(k_qpr, 0) + 1
                    i_qpr += 1
                    continue
                if (t_qpr.startswith("-")
                        and not t_qpr.startswith("--")
                        and len(t_qpr) > 1):
                    sah_qpr = True
                    j_qpr = 1
                    while j_qpr < len(t_qpr):
                        h_qpr = t_qpr[j_qpr]
                        if h_qpr == "f":
                            fd_qpr = True
                            hitung_qpr["f"] = hitung_qpr.get("f", 0) + 1
                            j_qpr += 1
                            continue
                        if h_qpr == "q":
                            hitung_qpr["q"] = hitung_qpr.get("q", 0) + 1
                            j_qpr += 1
                            continue
                        if h_qpr == "o":
                            ada_qpr = True
                            hitung_qpr["o"] = hitung_qpr.get("o", 0) + 1
                            if j_qpr + 1 >= len(t_qpr):
                                if i_qpr + 1 >= len(mentah):
                                    return False
                                i_qpr += 1
                            j_qpr = len(t_qpr)
                            continue
                        if h_qpr in ("m", "F", "d"):
                            hitung_qpr[h_qpr] = hitung_qpr.get(h_qpr, 0) + 1
                            if j_qpr + 1 >= len(t_qpr):
                                if i_qpr + 1 >= len(mentah):
                                    return False
                                i_qpr += 1
                            j_qpr = len(t_qpr)
                            continue
                        sah_qpr = False
                        break
                    if not sah_qpr:
                        return False
                    i_qpr += 1
                    continue
                return False
            return (ada_qpr != fd_qpr
                    and max(hitung_qpr.values(),
                            default=0) <= 1)
        return False
    if perintah == "csvpy":
        # Audit 2231: csvpy memuat berkas lalu
        # masuk shell Python interaktif yang
        # membaca stdin sampai EOF; bentuk polos
        # dan '-' ditolak eksplisit (csvpy.py).
        if _jebakan_argparse(mentah, _CSVKIT):
            return False
        # Audit 2422: csvpy mencabut tiga
        # bool umum (override_flags) - awalan
        # yang hanya cocok nama tercabut =
        # tak dikenal argparse.
        tercabut_cpy = ("--linenumbers", "--zero",
                        "--add-bom")
        semesta_cpy = _CSVKIT | (
            _CSVKIT_BOOL - set(tercabut_cpy))
        mentah_cpy = (mentah[:mentah.index("--")]
                      if "--" in mentah else mentah)
        if any(t_cpy2.startswith("--")
               and any(n_cpy.startswith(
                   t_cpy2.split("=", 1)[0])
                       for n_cpy in tercabut_cpy)
               and not any(n_cpy.startswith(
                   t_cpy2.split("=", 1)[0])
                   for n_cpy in semesta_cpy)
               for t_cpy2 in mentah_cpy):
            return False
        toks_cpy = _saring_csvkit(toks, awal, "csvpy")
        if toks_cpy is None:
            return False
        ops_cpy = _operand_csvkit(
            toks_cpy, awal, _CSVKIT,
            kecuali={"--linenumbers", "--zero",
                     "--add-bom"},
            pendek_bool={"-I"})
        if "\x00gantung" in ops_cpy:
            return False
        return (len(ops_cpy) == 1
                and ops_cpy[0] != "-")
    if perintah == "csvjson":
        # Audit 2239: tanpa FILE ia menerima
        # input terpipa via STDIN (csvjson(1)
        # csvkit).
        bernilai_cjn = _CSVKIT | {
            "-i", "--indent", "-k", "--key",
            "--lat", "--lon", "--crs", "--type", "--geometry"}
        if _jebakan_argparse(mentah, bernilai_cjn):
            return False
        toks_cjn = _saring_csvkit(toks, awal, "csvjson")
        if toks_cjn is None:
            return False
        ops_cjn = _operand_csvkit(
            toks_cjn, awal, bernilai_cjn,
            bool_cabang={"--no-bbox",
                         "--no-inference",
                         "--stream"},
            pendek_bool={"-I"})
        if "\x00gantung" in ops_cjn:
            return False
        return not ops_cjn or ops_cjn == ["-"]
    if perintah == "csvclean":
        # Audit 2242: tanpa FILE ia menerima
        # input pada STDIN (csvclean(1) csvkit);
        # opsi khasnya semua boolean.
        if _token_terlarang(mentah, {"--null-value", "--date-format", "--datetime-format", "--blanks", "--no-leading-zeroes", "-L", "--locale", "-y", "--snifflimit", "--remove-empty-columns"}, _CSVKIT):
            return False
        # Audit 2404: --remove-empty-columns
        # HANTU (tak ada di csvkit 2.2.0) -
        # terlarang, bukan pengaktif.
        # Audit 2400: --label/--separator/
        # --fillvalue csvclean BERNILAI
        # (csvclean.py) - bukan boolean.
        bernilai_ccl = _CSVKIT | {"--label", "--separator", "--fillvalue"}
        if _jebakan_argparse(mentah, bernilai_ccl):
            return False
        ops_ccl = _operand_csvkit(
            toks, awal, bernilai_ccl,
            pendek_bool={"-a"},
            bool_cabang={"--enable-all-checks",
                         "--length-mismatch",
                         "--empty-columns",
                         "--header-normalize-space",
                         "--join-short-rows",
                         "--fill-short-rows"},
            kecuali={"--blanks",
                     "--no-leading-zeroes"})
        if "\x00gantung" in ops_ccl:
            return False
        # Audit 2390: tanpa satu pun pengaktif
        # periksa/perbaiki csvclean berhenti
        # sebelum masukan dibuka (csvclean.py
        # main); join+fill bersama juga galat.
        # Audit 2399: bentuk --nama=x untuk
        # store_true ditolak argparse - hanya
        # token utuh yang mengaktifkan.
        if any("=" in t_ccl2
               and t_ccl2.split("=", 1)[0] in {
                   "--length-mismatch",
                   "--empty-columns",
                   "--enable-all-checks",
                   "--header-normalize-space",
                   "--join-short-rows",
                   "--fill-short-rows",
                   "--remove-empty-columns"}
               for t_ccl2 in mentah):
            return False
        nama_ccl = set(mentah)
        # Audit 2405: kehadiran pengaktif juga
        # bentuk singkatan awalan unik;
        # Audit 2404: --remove-empty-columns
        # keluar dari himpunan ini.
        semesta_ccl = {
            "--length-mismatch", "--empty-columns",
            "--enable-all-checks",
            "--header-normalize-space",
            "--join-short-rows", "--fill-short-rows"}
        aktif_ccl = any(
            _hadir_unik(mentah, n_ccl,
                        semesta_ccl | bernilai_ccl)
            for n_ccl in semesta_ccl)
        if not aktif_ccl:
            for t_ccl in mentah:
                if t_ccl == "--":
                    break
                if (t_ccl.startswith("-")
                        and not t_ccl.startswith("--")
                        and len(t_ccl) > 1):
                    for h_ccl in t_ccl[1:]:
                        if h_ccl == "a":
                            aktif_ccl = True
                            break
                        if ("-" + h_ccl) in _CSVKIT:
                            break
                    if aktif_ccl:
                        break
        if not aktif_ccl:
            return False
        if (_hadir_unik(mentah, "--join-short-rows",
                         semesta_ccl | bernilai_ccl)
                and _hadir_unik(
                    mentah, "--fill-short-rows",
                    semesta_ccl | bernilai_ccl)):
            return False
        return not ops_ccl or ops_ccl == ["-"]
    # Audit 2646: argparse menolak opsi panjang
    # tak dikenal (rc=2, berhenti sebelum
    # membaca) - delapan cabang csvkit di
    # bawah hanya menyaring lewat _saring_
    # csvkit sehingga --bogus lolos sebagai
    # penguras, sementara csvgrep/in2csv/
    # csvclean/csvpy sudah memvonis mati.
    # Semesta per alat = ekstrak --help
    # csvkit 2.2.0; argparse menerima
    # singkatan awalan unik (allow_abbrev).
    _SEMESTA_CSVKIT_46 = {
        "csvlook": frozenset({"--add-bom", "--blanks", "--date-format", "--datetime-format", "--delimiter", "--encoding", "--escapechar", "--help", "--linenumbers", "--locale", "--max-column-width", "--max-columns", "--max-precision", "--max-rows", "--maxfieldsize", "--no-doublequote", "--no-header-row", "--no-inference", "--no-leading-zeroes", "--no-number-ellipsis", "--null-value", "--quotechar", "--quoting", "--skip-lines", "--skipinitialspace", "--snifflimit", "--tabs", "--verbose", "--version", "--zero"}),
        "csvstat": frozenset({"--add-bom", "--blanks", "--columns", "--count", "--csv", "--date-format", "--datetime-format", "--decimal-format", "--delimiter", "--encoding", "--escapechar", "--freq", "--freq-count", "--help", "--indent", "--json", "--len", "--linenumbers", "--locale", "--max", "--max-precision", "--maxfieldsize", "--mean", "--median", "--min", "--names", "--no-doublequote", "--no-grouping-separator", "--no-header-row", "--no-inference", "--no-leading-zeroes", "--non-nulls", "--null-value", "--nulls", "--quotechar", "--quoting", "--skip-lines", "--skipinitialspace", "--snifflimit", "--stdev", "--sum", "--tabs", "--type", "--unique", "--verbose", "--version", "--zero"}),
        "csvcut": frozenset({"--add-bom", "--columns", "--delete-empty-rows", "--delimiter", "--encoding", "--escapechar", "--help", "--linenumbers", "--maxfieldsize", "--names", "--no-doublequote", "--no-header-row", "--not-columns", "--quotechar", "--quoting", "--skip-lines", "--skipinitialspace", "--tabs", "--verbose", "--version", "--zero"}),
        "csvjoin": frozenset({"--add-bom", "--blanks", "--columns", "--date-format", "--datetime-format", "--delimiter", "--encoding", "--escapechar", "--help", "--left", "--linenumbers", "--locale", "--maxfieldsize", "--no-doublequote", "--no-header-row", "--no-inference", "--no-leading-zeroes", "--null-value", "--outer", "--quotechar", "--quoting", "--right", "--skip-lines", "--skipinitialspace", "--snifflimit", "--tabs", "--verbose", "--version", "--zero"}),
        "csvsql": frozenset({"--add-bom", "--after-insert", "--before-insert", "--blanks", "--chunk-size", "--col-len-multiplier", "--create-if-not-exists", "--date-format", "--datetime-format", "--db", "--db-schema", "--delimiter", "--dialect", "--encoding", "--engine-option", "--escapechar", "--help", "--insert", "--linenumbers", "--locale", "--maxfieldsize", "--min-col-len", "--no-constraints", "--no-create", "--no-doublequote", "--no-header-row", "--no-inference", "--no-leading-zeroes", "--null-value", "--overwrite", "--prefix", "--query", "--quotechar", "--quoting", "--skip-lines", "--skipinitialspace", "--snifflimit", "--sql-delimiter", "--tables", "--tabs", "--unique-constraint", "--verbose", "--version", "--zero"}),
        "csvformat": frozenset({"--add-bom", "--delimiter", "--encoding", "--escapechar", "--help", "--linenumbers", "--locale", "--maxfieldsize", "--no-doublequote", "--no-header-row", "--out-asv", "--out-delimiter", "--out-escapechar", "--out-lineterminator", "--out-no-doublequote", "--out-quotechar", "--out-quoting", "--out-tabs", "--quotechar", "--quoting", "--skip-header", "--skip-lines", "--skipinitialspace", "--tabs", "--verbose", "--version", "--zero"}),
        "csvsort": frozenset({"--add-bom", "--blanks", "--columns", "--date-format", "--datetime-format", "--delimiter", "--encoding", "--escapechar", "--help", "--ignore-case", "--linenumbers", "--locale", "--maxfieldsize", "--names", "--no-doublequote", "--no-header-row", "--no-inference", "--no-leading-zeroes", "--null-value", "--quotechar", "--quoting", "--reverse", "--skip-lines", "--skipinitialspace", "--snifflimit", "--tabs", "--verbose", "--version", "--zero"}),
        "csvstack": frozenset({"--add-bom", "--delimiter", "--encoding", "--escapechar", "--filenames", "--group-name", "--groups", "--help", "--linenumbers", "--maxfieldsize", "--no-doublequote", "--no-header-row", "--quotechar", "--quoting", "--skip-lines", "--skipinitialspace", "--tabs", "--verbose", "--version", "--zero"}),
    }
    if perintah in _SEMESTA_CSVKIT_46:
        for t_46 in mentah:
            if t_46 == "--":
                break
            if t_46.startswith("--"):
                nm_46 = t_46.split("=", 1)[0]
                if nm_46 in _SEMESTA_CSVKIT_46[perintah]:
                    continue
                calon_46 = [x_46 for x_46
                            in _SEMESTA_CSVKIT_46[perintah]
                            if x_46.startswith(nm_46)]
                if len(calon_46) != 1:
                    return False
    if perintah == "csvlook":
        # Audit 2099: tanpa nama berkas ia menerima
        # input terpipa via STDIN (csvlook(1)
        # csvkit).
        # Audit 2110: himpunan dilengkapi dari
        # csvlook(1) csvkit 2.2.0 penuh.
        if _jebakan_argparse(
                mentah, _CSVKIT | {"--max-rows",
                                   "--max-columns",
                                   "--max-column-width",
                                   "--max-precision"}):
            return False
        toks_cl = _saring_csvkit(toks, awal, "csvlook")
        if toks_cl is None:
            return False
        ops_cl = _operand_csvkit(
            toks_cl, awal, _CSVKIT | {"--max-rows",
                                   "--max-columns",
                                   "--max-column-width",
                                   "--max-precision"},
            bool_cabang={"--no-inference",
                         "--no-number-ellipsis"},
            pendek_bool={"-I"})
        if "\x00gantung" in ops_cl:
            return False
        return not ops_cl or ops_cl == ["-"]
    if perintah == "tsv-select":
        # Audit 2391: getopt alat ini
        # caseInsensitive (pulau caseSensitive
        # hanya H/V bool) - lipat token opsi
        # ke huruf kecil; -V persis = version.
        # Audit 2401/2406: pindaian -V/-h/
        # --help/--version melewati posisi
        # NILAI opsi bernilai (handleOption
        # Phobos memakannya tanpa syarat).
        bernilai_lp = ['--delimiter', '--exclude', '--fields', '--rest', '-V', '-d', '-e', '-f', '-r']
        i_lp = 0
        while i_lp < len(mentah):
            t_lp = mentah[i_lp]
            if t_lp == "--":
                break
            dasar_lp = t_lp.split("=", 1)[0]
            if ("=" in t_lp
                    and (dasar_lp in ("-V", "-h")
                         or dasar_lp.lower() in (
                             "--help", "--version",
                             "--help-verbose",
                             "--help-fields",
                             "--help-options"))
                    and (dasar_lp in ("-h",)
                         or dasar_lp.lower() == "--help"
                         or t_lp.split("=", 1)[1].lower()
                         != "false")):
                return False
            # Audit 2567: ekor nempel -V/-h sejajar
            # model 2553 - -h berekor selalu berhenti
            # (hanya cocok sebagai token persis di
            # sapuan akhir getopt); -V berekor berhenti
            # kecuali ekor bool "false" (version mati,
            # alat tetap jalan).
            if (not t_lp.startswith("--")
                    and len(t_lp) > 2
                    and (t_lp.startswith("-h")
                         or (t_lp.startswith("-V")
                             and t_lp[2:].lower()
                             != "false"))):
                return False
            if (t_lp in ("-V", "-h")
                    or t_lp.lower() in ("--help",
                                        "--version",
                                        "--help-verbose",
                                        "--help-fields",
                                        "--help-options")):
                return False
            if (t_lp.split("=", 1)[0].lower() in bernilai_lp
                    and "=" not in t_lp):
                i_lp += 2
                continue
            i_lp += 1
        mentah = [t.lower() if t.startswith("-")
                  else t for t in mentah]
        toks = [t.lower() if t.startswith("-")
                else t for t in toks]
        # Audit 2100: tanpa argumen berkas ia
        # membaca standard input (dok resmi
        # tsv-utils).
        # Audit 2113 (koreksi 2100): -f/--fields
        # atau -e/--exclude WAJIB - sumber
        # tsv-select.d menegakkan 'One of
        # --f|fields or --e|exclude is required.'
        ada_pilih = False
        for t_ts in mentah:
            if t_ts in ("-f", "--fields", "-e",
                        "--exclude"):
                ada_pilih = True
            elif t_ts.startswith(("--fields=",
                                   "--exclude=")):
                ada_pilih = True
            elif (len(t_ts) > 2 and t_ts[0] == "-"
                  and not t_ts.startswith("--")
                  and t_ts[1] in "fe"):
                ada_pilih = True
        if not ada_pilih:
            return False
        # Audit 2111: --exclude/-e bernilai ikut
        # dilengkapi (referensi tsv-utils).
        ops_ts = _operand_sesudah_opsi(
            toks, awal, {"-f", "--fields", "-d",
                         "--delimiter", "-e",
                         "--exclude", "-r",
                         "--rest"})
        if "\x00gantung" in ops_ts:
            return False
        return not ops_ts or "-" in ops_ts
    if perintah == "csvcut":
        # Audit 2114: tanpa FILE ia menerima input
        # terpipa via STDIN (csvcut(1) csvkit).
        bernilai_ck = _CSVKIT | {"-c", "--columns",
                                 "-C", "--not-columns"}
        if _token_terlarang(mentah, {"--null-value", "--date-format", "--datetime-format", "--blanks", "--no-leading-zeroes", "-L", "--locale", "-y", "--snifflimit"}, bernilai_ck):
            return False
        if _jebakan_argparse(mentah, bernilai_ck):
            return False
        ops_ck = _operand_csvkit(
            toks, awal, bernilai_ck,
            bool_cabang={"--delete-empty-rows",
                         "--names"},
            pendek_bool={"-n", "-x"})
        if "\x00gantung" in ops_ck:
            return False
        return not ops_ck or ops_ck == ["-"]
    if perintah == "csvgrep":
        # Audit 2115: menguras hanya bila -c dan
        # pencocok (-r/-m/-f) ada dan FILE kosong -
        # tanpa -c csvgrep menolak lebih dulu
        # (sumber csvgrep.py csvkit).
        bernilai_cg = _CSVKIT | {"-c", "--columns",
                                 "-r", "--regex",
                                 "-m", "--match",
                                 "-f", "--file"}
        if _token_terlarang(mentah, {"--null-value", "--date-format", "--datetime-format", "--blanks", "--no-leading-zeroes", "-L", "--locale", "-y", "--snifflimit"}, bernilai_cg):
            return False
        if _jebakan_argparse(mentah, bernilai_cg):
            return False
        ops_cg = _operand_csvkit(
            toks, awal, bernilai_cg,
            pendek_bool={"-a", "-i",
                         "-n"})
        if "\x00gantung" in ops_cg:
            return False
        ada_c = any(
            t_cg in ("-c", "--columns")
            or t_cg.startswith("--columns=")
            for t_cg in mentah)
        ada_m = any(
            t_cg in ("-r", "--regex", "-m", "--match",
                     "-f", "--file")
            or t_cg.startswith(("--regex=",
                                 "--match=",
                                 "--file="))
            for t_cg in mentah)
        # Audit 2405: kehadiran juga bentuk
        # singkatan awalan unik.
        semesta_cg = bernilai_cg | {
            "--invert-match", "--no-header-row",
            "--verbose", "--help", "--version"}
        ada_c = ada_c or _hadir_unik(
            mentah, "--columns", semesta_cg)
        ada_m = ada_m or any(
            _hadir_unik(mentah, n_cg, semesta_cg)
            for n_cg in ("--regex", "--match",
                         "--file"))
        # Audit 2389: kehadiran juga di huruf
        # bundel bukan-pertama (argparse
        # mengembangkan bundel; huruf bernilai
        # menghentikan jalannya huruf).
        for t_cg in mentah:
            if t_cg == "--":
                break
            if (t_cg.startswith("-")
                    and not t_cg.startswith("--")
                    and len(t_cg) > 2):
                for h_cg in t_cg[1:]:
                    if h_cg == "c":
                        ada_c = True
                    if h_cg in "rmf":
                        ada_m = True
                    if ("-" + h_cg) in bernilai_cg:
                        break
        return ada_c and ada_m and (not ops_cg
                                    or ops_cg == ["-"])
    if perintah == "csvstat":
        # Audit 2116: tanpa FILE ia menerima input
        # terpipa via STDIN (csvstat(1) csvkit).
        bernilai_cs = _CSVKIT | {"-c", "--columns", "-i", "--indent", "--freq-count", "--decimal-format"}
        if _jebakan_argparse(mentah, bernilai_cs):
            return False
        toks_cs = _saring_csvkit(toks, awal, "csvstat")
        if toks_cs is None:
            return False
        ops_cs = _operand_csvkit(
            toks_cs, awal, bernilai_cs,
            bool_cabang={"--count", "--freq",
                         "--max", "--min", "--sum",
                         "--mean", "--median",
                         "--stdev", "--len",
                         "--type", "--nulls",
                         "--csv", "--json",
                         "--names", "--no-inference",
                         "--no-grouping-separator",
                         "--max-precision",
                         "--non-nulls", "--unique",
                         "--percentile"},
            pendek_bool={"-G", "-I", "-n"})
        if "\x00gantung" in ops_cs:
            return False
        return not ops_cs or ops_cs == ["-"]
    if perintah == "xsv":
        # Audit 2117: sumber xsv - daftar masukan
        # kosong diganti '-' (stdin); cat
        # mewajibkan rows/columns, count polos
        # menguras (util.rs/config.rs).
        # Audit 2121: sub 'search' - pola adalah
        # operand pertama, masukan [<input>]
        # opsional sesudahnya (config.rs xsv).
        # Audit 2424/2429: token
        # strip-angka di subperintah
        # lain juga flag tak dikenal
        # docopt (peta menyeluruh).
        bernilai_angka_xsv = {
            "cat": {"--delimiter", "--output", "-d", "-o"},
            "fixlengths": {"--delimiter", "--length", "--output", "-d", "-l", "-o"},
            "flatten": {"--condense", "--delimiter", "--separator", "-c", "-d", "-s"},
            "fmt": {"--delimiter", "--escape", "--out-delimiter", "--output", "--quote", "-d", "-o", "-t"},
            "frequency": {"--delimiter", "--jobs", "--limit", "--output", "--select", "-d", "-j", "-l", "-o", "-s"},
            "headers": {"--delimiter", "-d"},
            "input": {"--delimiter", "--escape", "--output", "--quote", "-d", "-o"},
            "join": {"--delimiter", "--output", "-d", "-o"},
            "partition": {"--delimiter", "--filename", "--prefix-length", "-d", "-p"},
            "reverse": {"--delimiter", "--output", "-d", "-o"},
            "sample": {"--delimiter", "--output", "--seed", "-d", "-o"},
            "search": {"--delimiter", "--output", "--select", "-d", "-o", "-s"},
            "select": {"--delimiter", "--output", "-d", "-o"},
            "slice": {"--delimiter", "--end", "--index", "--len", "--output", "--start", "-d", "-e", "-i", "-l", "-o", "-s"},
            "sort": {"--delimiter", "--output", "--select", "-d", "-o", "-s"},
            "split": {"--delimiter", "--filename", "--jobs", "--size", "-d", "-j", "-s"},
            "stats": {"--delimiter", "--jobs", "--output", "--select", "-d", "-j", "-o", "-s"},
            "table": {"--condense", "--delimiter", "--output", "--pad", "--width", "-c", "-d", "-o", "-p", "-w"},
            }
        if (mentah
                and mentah[0] in bernilai_angka_xsv
                and _ada_token_angka(
                    mentah,
                    bernilai_angka_xsv[mentah[0]])):
            return False
        if len(mentah) >= 2 and mentah[0] == "search":
            # Audit 2132: kolom xsv search resmi
            # -s/--select (README xsv).
            if _ada_duplikat(mentah, 1, {"-s", "--select", "-o", "--output", "-d", "--delimiter"}, {"-s": "--select", "-o": "--output", "-d": "--delimiter", "-i": "--ignore-case", "-v": "--invert-match", "-n": "--no-headers"}):
                return False
            ops_xse = _operand_sesudah_opsi(
                toks, awal + 1,
                {"-s", "--select", "-o", "--output", "-d", "--delimiter"})
            if "\x00gantung" in ops_xse:
                return False
            return (len(ops_xse) == 1
                    or (len(ops_xse) == 2
                        and ops_xse[1] == "-"))
        if mentah and mentah[0] == "count":
            if _ada_duplikat(mentah, 1, {"-d", "--delimiter"}, {"-d": "--delimiter", "-n": "--no-headers"}):
                return False
            # Audit 2420: token strip-angka =
            # flag tak dikenal docopt.
            i_xc = 1
            while i_xc < len(mentah):
                t_xc = mentah[i_xc]
                if t_xc == "--":
                    break
                if t_xc in ("-d", "--delimiter"):
                    i_xc += 2
                    continue
                if (len(t_xc) > 1
                        and t_xc[0] == "-"
                        and t_xc[1].isdigit()):
                    return False
                i_xc += 1
            ops_xc = _operand_sesudah_opsi(
                toks, awal + 1, {"-d", "--delimiter"})
            if "\x00gantung" in ops_xc:
                return False
            return (not ops_xc
                    or all(o == "-" for o in ops_xc))
        # Audit 2143: sub 'slice' xsv - masukan
        # [<input>] opsional, Config yang sama.
        if mentah and mentah[0] == "slice":
            if _ada_duplikat(mentah, 1, {"-s", "--start", "-e", "--end", "-l", "--len", "-i", "--index", "-o", "--output", "-d", "--delimiter"}, {"-s": "--start", "-e": "--end", "-l": "--len", "-i": "--index", "-o": "--output", "-d": "--delimiter", "-n": "--no-headers"}):
                return False
            ops_xsl = _operand_sesudah_opsi(
                toks, awal + 1,
                {"-s", "--start", "-e", "--end",
                 "-l", "--len", "-i", "--index",
                 "-o", "--output", "-d", "--delimiter"})
            if "\x00gantung" in ops_xsl:
                return False
            return (not ops_xsl
                    or (len(ops_xsl) == 1
                        and ops_xsl[0] == "-"))
        # Audit 2189: sub 'partition' xsv -
        # operand kolom + direktori keluaran,
        # masukan ketiga (partition.rs).
        if mentah and mentah[0] == "partition":
            if _ada_duplikat(mentah, 1, {"--filename", "-d", "--delimiter", "-p", "--prefix-length"}, {"-d": "--delimiter", "-p": "--prefix-length", "-n": "--no-headers"}):
                return False
            ops_xpa = _operand_sesudah_opsi(
                toks, awal + 1,
                {"--filename", "-d", "--delimiter",
                 "-p", "--prefix-length"})
            if "\x00gantung" in ops_xpa:
                return False
            # Audit 2193: template --filename
            # partition wajib tepat satu '{}'.
            for i_pa, t_pa in enumerate(mentah):
                nilai_pa = None
                if (t_pa == "--filename"
                        and i_pa + 1 < len(mentah)):
                    nilai_pa = mentah[i_pa + 1]
                elif t_pa.startswith("--filename="):
                    nilai_pa = t_pa.split("=", 1)[1]
                if (nilai_pa is not None
                        and nilai_pa.count("{}") != 1):
                    return False
            return (len(ops_xpa) == 2
                    or (len(ops_xpa) == 3
                        and ops_xpa[2] == "-"))
        # Audit 2171: sub 'select' xsv - operand
        # pertama pilihan kolom, masukan kedua
        # (select.rs).
        if mentah and mentah[0] == "select":
            if _ada_duplikat(mentah, 1, {"-o", "--output", "-d", "--delimiter"}, {"-o": "--output", "-d": "--delimiter", "-n": "--no-headers"}):
                return False
            ops_xse = _operand_sesudah_opsi(
                toks, awal + 1,
                {"-o", "--output", "-d", "--delimiter"})
            if "\x00gantung" in ops_xse:
                return False
            return (len(ops_xse) == 1
                    or (len(ops_xse) == 2
                        and ops_xse[1] == "-"))
        # Audit 2172: sub 'split' xsv - operand
        # pertama direktori keluaran, masukan
        # kedua (split.rs).
        if mentah and mentah[0] == "split":
            if _ada_duplikat(mentah, 1, {"-s", "--size", "-j", "--jobs", "--filename", "-d", "--delimiter"}, {"-s": "--size", "-j": "--jobs", "-d": "--delimiter", "-n": "--no-headers"}):
                return False
            ops_xsp = _operand_sesudah_opsi(
                toks, awal + 1,
                {"-s", "--size", "-j", "--jobs",
                 "--filename", "-d", "--delimiter"})
            if "\x00gantung" in ops_xsp:
                return False
            # Audit 2176: template --filename wajib
            # memuat tepat satu '{}'.
            for i_sp, t_sp in enumerate(mentah):
                nilai_sp = None
                if (t_sp == "--filename"
                        and i_sp + 1 < len(mentah)):
                    nilai_sp = mentah[i_sp + 1]
                elif t_sp.startswith("--filename="):
                    nilai_sp = t_sp.split("=", 1)[1]
                if (nilai_sp is not None
                        and nilai_sp.count("{}") != 1):
                    return False
            return (len(ops_xsp) == 1
                    or (len(ops_xsp) == 2
                        and ops_xsp[1] == "-"))
        # Audit 2159: sub 'flatten' xsv - masukan
        # [<input>] opsional (flatten.rs).
        if mentah and mentah[0] == "flatten":
            if _ada_duplikat(mentah, 1, {"-c", "--condense", "-s", "--separator", "-d", "--delimiter"}, {"-c": "--condense", "-s": "--separator", "-d": "--delimiter", "-n": "--no-headers"}):
                return False
            ops_xf = _operand_sesudah_opsi(
                toks, awal + 1,
                {"-c", "--condense", "-s",
                 "--separator", "-d", "--delimiter"})
            if "\x00gantung" in ops_xf:
                return False
            return (not ops_xf
                    or (len(ops_xf) == 1
                        and ops_xf[0] == "-"))
        # Audit 2160: sub 'fmt' xsv - masukan
        # [<input>] opsional (fmt.rs).
        if mentah and mentah[0] == "fmt":
            if _ada_duplikat(mentah, 1, {"-t", "--out-delimiter", "--quote", "--escape", "-o", "--output", "-d", "--delimiter"}, {"-t": "--out-delimiter", "-o": "--output", "-d": "--delimiter"}):
                return False
            ops_xfm = _operand_sesudah_opsi(
                toks, awal + 1,
                {"-t", "--out-delimiter", "--quote", "--escape", "-o",
                 "--output", "-d", "--delimiter"})
            if "\x00gantung" in ops_xfm:
                return False
            return (not ops_xfm
                    or (len(ops_xfm) == 1
                        and ops_xfm[0] == "-"))
        # Audit 2161: sub 'input' xsv - masukan
        # [<input>] opsional (input.rs).
        if mentah and mentah[0] == "input":
            if _ada_duplikat(mentah, 1, {"--quote", "--escape", "-o", "--output", "-d", "--delimiter"}, {"-o": "--output", "-d": "--delimiter"}):
                return False
            ops_xi = _operand_sesudah_opsi(
                toks, awal + 1,
                {"--quote", "--escape", "-o",
                 "--output", "-d", "--delimiter"})
            if "\x00gantung" in ops_xi:
                return False
            return (not ops_xi
                    or (len(ops_xi) == 1
                        and ops_xi[0] == "-"))
        # Audit 2162: sub 'table' xsv - masukan
        # [<input>] opsional (table.rs).
        if mentah and mentah[0] == "table":
            if _ada_duplikat(mentah, 1, {"-w", "--width", "-p", "--pad", "-c", "--condense", "-o", "--output", "-d", "--delimiter"}, {"-w": "--width", "-p": "--pad", "-c": "--condense", "-o": "--output", "-d": "--delimiter"}):
                return False
            ops_xt = _operand_sesudah_opsi(
                toks, awal + 1,
                {"-w", "--width", "-p", "--pad",
                 "-c", "--condense", "-o", "--output",
                 "-d", "--delimiter"})
            if "\x00gantung" in ops_xt:
                return False
            return (not ops_xt
                    or (len(ops_xt) == 1
                        and ops_xt[0] == "-"))
        # Audit 2163: sub 'sort' xsv - masukan
        # [<input>] opsional (sort.rs).
        if mentah and mentah[0] == "sort":
            # Audit 2168: '-r' kecil tak dikenal
            # sort.rs (resmi -R, --reverse).
            if "-r" in mentah:
                return False
            if _ada_duplikat(mentah, 1, {"-s", "--select", "-o", "--output", "-d", "--delimiter"}, {"-s": "--select", "-o": "--output", "-d": "--delimiter", "-N": "--numeric", "-R": "--reverse", "-n": "--no-headers"}):
                return False
            ops_xso = _operand_sesudah_opsi(
                toks, awal + 1,
                {"-s", "--select", "-o", "--output",
                 "-d", "--delimiter"})
            if "\x00gantung" in ops_xso:
                return False
            return (not ops_xso
                    or (len(ops_xso) == 1
                        and ops_xso[0] == "-"))
        # Audit 2164: sub 'sample' xsv - operand
        # pertama selalu ukuran; masukan adalah
        # operand kedua (sample.rs).
        if mentah and mentah[0] == "sample":
            if _ada_duplikat(mentah, 1, {"-o", "--output", "-d", "--delimiter", "--seed"}, {"-o": "--output", "-d": "--delimiter", "-n": "--no-headers"}):
                return False
            ops_xsa = _operand_sesudah_opsi(
                toks, awal + 1,
                {"-o", "--output", "-d", "--delimiter", "--seed"})
            if "\x00gantung" in ops_xsa:
                return False
            # Audit 2167 + 2175: operand pertama
            # wajib angka u64 ('-' maupun bukan
            # angka = galat parse).
            if ops_xsa and (not ops_xsa[0].isdigit()
                    or int(ops_xsa[0])
                    > 18446744073709551615):
                return False
            return (len(ops_xsa) == 1
                    or (len(ops_xsa) == 2
                        and ops_xsa[1] == "-"))
        # Audit 2190: sub 'stats' xsv - masukan
        # [<input>] opsional (stats.rs).
        if mentah and mentah[0] == "stats":
            if _ada_duplikat(mentah, 1, {"-s", "--select", "-o", "--output", "-d", "--delimiter", "-j", "--jobs"}, {"-s": "--select", "-o": "--output", "-d": "--delimiter", "-j": "--jobs", "-n": "--no-headers"}):
                return False
            ops_xst = _operand_sesudah_opsi(
                toks, awal + 1,
                {"-s", "--select", "-o", "--output",
                 "-d", "--delimiter", "-j", "--jobs"})
            if "\x00gantung" in ops_xst:
                return False
            return (not ops_xst
                    or (len(ops_xst) == 1
                        and ops_xst[0] == "-"))
        # Audit 2212: sub 'fixlengths' xsv -
        # stdin hanya bila --length terpasang
        # (fixlengths.rs).
        if mentah and mentah[0] == "fixlengths":
            ada_panjang = any(
                t_xfl == "--length"
                or t_xfl.startswith("--length=")
                or t_xfl == "-l"
                or (t_xfl.startswith("-l")
                    and len(t_xfl) > 2
                    and t_xfl[2:].isdigit())
                for t_xfl in mentah)
            if not ada_panjang:
                return False
            nilai_panjang = None
            for i_xfl, t_xfl in enumerate(mentah):
                if (t_xfl in ("-l", "--length")
                        and i_xfl + 1 < len(mentah)):
                    nilai_panjang = mentah[i_xfl + 1]
                elif t_xfl.startswith("--length="):
                    nilai_panjang = t_xfl.split("=", 1)[1]
                elif (t_xfl.startswith("-l")
                        and len(t_xfl) > 2):
                    nilai_panjang = t_xfl[2:]
            if nilai_panjang is not None and not (
                    nilai_panjang.isdigit()
                    and 0 < int(nilai_panjang)
                    <= 18446744073709551615):
                return False
            if _ada_duplikat(mentah, 1, {"-l", "--length", "-o", "--output", "-d", "--delimiter"}, {"-l": "--length", "-o": "--output", "-d": "--delimiter"}):
                return False
            ops_xfl = _operand_sesudah_opsi(
                toks, awal + 1,
                {"-l", "--length", "-o", "--output",
                 "-d", "--delimiter"})
            if "\x00gantung" in ops_xfl:
                return False
            return (not ops_xfl
                    or (len(ops_xfl) == 1
                        and ops_xfl[0] == "-"))
        # Audit 2154: sub 'headers' xsv - daftar
        # masukan Vec lewat many_configs (kosong
        # diganti '-'), seperti cat.
        if mentah and mentah[0] == "headers":
            if _ada_duplikat(mentah, 1, {"-d", "--delimiter"}, {"-d": "--delimiter", "-j": "--just-names"}):
                return False
            ops_xh = _operand_sesudah_opsi(
                toks, awal + 1, {"-d", "--delimiter"})
            if "\x00gantung" in ops_xh:
                return False
            return not ops_xh or "-" in ops_xh
        if (len(mentah) >= 2 and mentah[0] == "cat"
                and mentah[1] in ("rows", "columns")):
            if _ada_duplikat(mentah, 2, {"-o", "--output", "-d", "--delimiter"}, {"-o": "--output", "-d": "--delimiter", "-p": "--pad", "-n": "--no-headers"}):
                return False
            ops_xs = _operand_sesudah_opsi(
                toks, awal + 2, {"-o", "--output", "-d", "--delimiter"})
            if "\x00gantung" in ops_xs:
                return False
            return (not ops_xs
                    or all(o == "-" for o in ops_xs))
        # Audit 2236: sub 'frequency' xsv -
        # masukan [<input>] opsional via Config
        # (frequency.rs xsv).
        if mentah and mentah[0] == "frequency":
            if _ada_duplikat(mentah, 1, {"-s", "--select", "-l", "--limit", "-j", "--jobs", "-o", "--output", "-d", "--delimiter"}, {"-s": "--select", "-l": "--limit", "-j": "--jobs", "-o": "--output", "-d": "--delimiter", "-a": "--asc", "-n": "--no-headers"}):
                return False
            ops_xfr = _operand_sesudah_opsi(
                toks, awal + 1,
                {"-s", "--select", "-l", "--limit",
                 "-j", "--jobs", "-o", "--output",
                 "-d", "--delimiter"})
            if "\x00gantung" in ops_xfr:
                return False
            return (not ops_xfr
                    or (len(ops_xfr) == 1
                        and ops_xfr[0] == "-"))
        # Audit 2277: sub 'reverse' xsv -
        # masukan [<input>] opsional via Config
        # xsv (config.rs:77 - '-' = stdin).
        if mentah and mentah[0] == "reverse":
            if _ada_duplikat(mentah, 1, {"-o", "--output", "-d", "--delimiter"}, {"-o": "--output", "-d": "--delimiter", "-n": "--no-headers"}):
                return False
            ops_xrv = _operand_sesudah_opsi(
                toks, awal + 1,
                {"-o", "--output", "-d", "--delimiter"})
            if "\x00gantung" in ops_xrv:
                return False
            return (not ops_xrv
                    or (len(ops_xrv) == 1
                        and ops_xrv[0] == "-"))
        # Audit 2278: sub 'join' xsv -
        # <columns1> <input1> <columns2> <input2>;
        # MEMBALIK vonis Audit 136: config.rs
        # xsv memetakan '-' ke stdin.
        if mentah and mentah[0] == "join":
            if _ada_duplikat(mentah, 1, {"-o", "--output", "-d", "--delimiter"}, {"-o": "--output", "-d": "--delimiter", "-n": "--no-headers"}):
                return False
            ops_xjn = _operand_sesudah_opsi(
                toks, awal + 1,
                {"-o", "--output", "-d", "--delimiter"})
            if "\x00gantung" in ops_xjn:
                return False
            return (len(ops_xjn) == 4
                    and (ops_xjn[1] == "-"
                         or ops_xjn[3] == "-"))
        return False
    if perintah == "tsv-filter":
        # Audit 2391: getopt alat ini
        # caseInsensitive (pulau caseSensitive
        # hanya H/V bool) - lipat token opsi
        # ke huruf kecil; -V persis = version.
        # Audit 2401/2406: pindaian -V/-h/
        # --help/--version melewati posisi
        # NILAI opsi bernilai (handleOption
        # Phobos memakannya tanpa syarat).
        bernilai_lp = set()  # Audit 2413: getoptInorder - token strip sesudah opsi bernilai BUKAN nilai, jadi tanpa lewatan
        i_lp = 0
        while i_lp < len(mentah):
            t_lp = mentah[i_lp]
            if t_lp == "--":
                break
            dasar_lp = t_lp.split("=", 1)[0]
            if ("=" in t_lp
                    and (dasar_lp in ("-V", "-h")
                         or dasar_lp.lower() in (
                             "--help", "--version",
                             "--help-verbose",
                             "--help-fields",
                             "--help-options"))
                    and (dasar_lp in ("-h",)
                         or dasar_lp.lower() == "--help"
                         or t_lp.split("=", 1)[1].lower()
                         != "false")):
                return False
            # Audit 2567: ekor nempel -V/-h sejajar
            # model 2553 - -h berekor selalu berhenti
            # (hanya cocok sebagai token persis di
            # sapuan akhir getopt); -V berekor berhenti
            # kecuali ekor bool "false" (version mati,
            # alat tetap jalan).
            if (not t_lp.startswith("--")
                    and len(t_lp) > 2
                    and (t_lp.startswith("-h")
                         or (t_lp.startswith("-V")
                             and t_lp[2:].lower()
                             != "false"))):
                return False
            if (t_lp in ("-V", "-h")
                    or t_lp.lower() in ("--help",
                                        "--version",
                                        "--help-verbose",
                                        "--help-fields",
                                        "--help-options")):
                return False
            if (t_lp.split("=", 1)[0].lower() in bernilai_lp
                    and "=" not in t_lp):
                i_lp += 2
                continue
            i_lp += 1
        mentah = [t.lower() if t.startswith("-")
                  else t for t in mentah]
        toks = [t.lower() if t.startswith("-")
                else t for t in toks]
        # Audit 2358: --in/--not-in/--ff/
        # --filter-file HANTU - tak ada di
        # getopt tsv-filter.d master saat ini.
        if any(t_tf in ("--in", "--not-in", "--ff",
                        "--filter-file")
               or t_tf.startswith(("--in=",
                                   "--not-in=",
                                   "--ff=",
                                   "--filter-file="))
               for t_tf in mentah):
            return False
        # Audit 2118: tanpa berkas ia membaca
        # standard input (dok resmi tsv-utils);
        # opsi predikatnya bernilai FIELD:NILAI.
        ops_tf = _operand_sesudah_opsi(
            toks, awal, {"--eq", "--ne", "--gt",
                         "--lt", "--ge", "--le",
                         "--str-eq", "--str-ne",
                         "--regex", "--iregex",
                         "--not-regex", "--label",
                         "--label-values", "--empty",
                         "--not-empty", "--blank",
                         "--not-blank", "--is-numeric",
                         "--is-finite", "--is-nan",
                         "--is-infinity", "--istr-eq",
                         "--istr-ne", "--str-in-fld",
                         "--istr-in-fld",
                         "--str-not-in-fld",
                         "--istr-not-in-fld", "--str-le",
                         "--str-lt", "--str-ge",
                         "--str-gt", "--not-iregex",
                         "--char-len-le", "--char-len-lt",
                         "--char-len-ge", "--char-len-gt",
                         "--char-len-eq", "--char-len-ne",
                         "--byte-len-le", "--byte-len-lt",
                         "--byte-len-ge", "--byte-len-gt",
                         "--byte-len-eq", "--byte-len-ne",
                         "--ff-le", "--ff-lt", "--ff-ge",
                         "--ff-gt", "--ff-eq", "--ff-ne",
                         "--ff-str-eq", "--ff-str-ne",
                         "--ff-istr-eq", "--ff-istr-ne",
                         "--ff-absdiff-le",
                         "--ff-absdiff-gt",
                         "--ff-reldiff-le",
                         "--ff-reldiff-gt", "-d",
                         "--delimiter"})
        if "\x00gantung" in ops_tf:
            return False
        return not ops_tf or "-" in ops_tf
    if perintah == "tsv-append":
        # Audit 2391: getopt alat ini
        # caseInsensitive (pulau caseSensitive
        # hanya H/V bool) - lipat token opsi
        # ke huruf kecil; -V persis = version.
        # Audit 2401/2406: pindaian -V/-h/
        # --help/--version melewati posisi
        # NILAI opsi bernilai (handleOption
        # Phobos memakannya tanpa syarat).
        bernilai_lp = ['--delimiter', '--file', '--source-header', '-V', '-d', '-f', '-s']
        i_lp = 0
        while i_lp < len(mentah):
            t_lp = mentah[i_lp]
            if t_lp == "--":
                break
            dasar_lp = t_lp.split("=", 1)[0]
            if ("=" in t_lp
                    and (dasar_lp in ("-V", "-h")
                         or dasar_lp.lower() in (
                             "--help", "--version",
                             "--help-verbose",
                             "--help-fields",
                             "--help-options"))
                    and (dasar_lp in ("-h",)
                         or dasar_lp.lower() == "--help"
                         or t_lp.split("=", 1)[1].lower()
                         != "false")):
                return False
            # Audit 2567: ekor nempel -V/-h sejajar
            # model 2553 - -h berekor selalu berhenti
            # (hanya cocok sebagai token persis di
            # sapuan akhir getopt); -V berekor berhenti
            # kecuali ekor bool "false" (version mati,
            # alat tetap jalan).
            if (not t_lp.startswith("--")
                    and len(t_lp) > 2
                    and (t_lp.startswith("-h")
                         or (t_lp.startswith("-V")
                             and t_lp[2:].lower()
                             != "false"))):
                return False
            if (t_lp in ("-V", "-h")
                    or t_lp.lower() in ("--help",
                                        "--version",
                                        "--help-verbose",
                                        "--help-fields",
                                        "--help-options")):
                return False
            if (t_lp.split("=", 1)[0].lower() in bernilai_lp
                    and "=" not in t_lp):
                i_lp += 2
                continue
            i_lp += 1
        mentah = [t.lower() if t.startswith("-")
                  else t for t in mentah]
        toks = [t.lower() if t.startswith("-")
                else t for t in toks]
        # Audit 2119: daftar berkas kosong diganti
        # ['-'] = stdin (sumber tsv-append.d).
        # Audit 2420: token strip-angka di
        # posisi opsi = operand berkas/galat
        # getopt - tak pernah menguras.
        i_da = 0
        while i_da < len(mentah):
            t_da = mentah[i_da]
            if t_da == "--":
                break
            if (t_da.split("=", 1)[0]
                    in ("-d", "--delimiter", "-s",
                        "--source-header", "-f",
                        "--file")
                    and "=" not in t_da):
                i_da += 2
                continue
            if (len(t_da) > 1 and t_da[0] == "-"
                    and t_da[1].isdigit()):
                return False
            i_da += 1
        ops_ta = _operand_sesudah_opsi(
            toks, awal, {"-d", "--delimiter", "-s", "--source-header", "-f", "--file"})
        if "\x00gantung" in ops_ta:
            return False
        return not ops_ta or "-" in ops_ta
    if perintah == "csvsort":
        # Audit 2122: tanpa FILE ia menerima input
        # terpipa via STDIN (csvsort(1) csvkit).
        bernilai_cso = _CSVKIT | {"-c", "--columns"}
        if _jebakan_argparse(mentah, bernilai_cso):
            return False
        toks_cso = _saring_csvkit(toks, awal, "csvsort")
        if toks_cso is None:
            return False
        ops_cso = _operand_csvkit(
            toks_cso, awal, bernilai_cso,
            bool_cabang={"--ignore-case",
                         "--names", "--no-inference",
                         "--reverse"},
            pendek_bool={"-I", "-i", "-n",
                         "-r"})
        if "\x00gantung" in ops_cso:
            return False
        return not ops_cso or ops_cso == ["-"]
    if perintah == "tsv-summarize":
        # Audit 2123: tanpa berkas ia membaca
        # standard input (dok resmi tsv-utils).
        # Audit 2136: operasi resmi tsv-summarize
        # adalah opsi bernilai field (daftar
        # operasi di referensi resmi).
        # Audit 2383: -V = version
        # caseSensitive (bukan -v). Audit 2385:
        # bundel yang huruf pertamanya bukan
        # opsi bernilai tak sah (bundling
        # Phobos mati - bool berhuruf ekor =
        # galat). Audit 2386: -h persis = help.
        # Audit 2530: ekor token pendek pada
        # opsi bool H/w/x yang persis literal
        # "true"/"false" BUKAN bundel - optMatch
        # Phobos menjadikannya NILAI opsi dan
        # to!bool menerimanya, jadi alat jalan
        # dan menguras; penjaga 2385 tak boleh
        # menelan bentuk itu.
        # Audit 2544: huruf w/x hidup di zona
        # caseInsensitive sumber (hurufnya ikut
        # dilipat: W = w, X = x) dan parse!bool
        # melipat ekornya - pengecualian 2530
        # yang peka huruf penuh meloloskan
        # -HTrue/-Wfalse/-Xtrue. H tetap huruf
        # persis (pulau caseSensitive sumber).
        i_awal = 0
        while i_awal < len(mentah):
            t_awal = mentah[i_awal]
            i_awal += 1
            if t_awal == "--":
                break
            if t_awal in ("-V", "-h"):
                return False
            # Audit 2566: opsi bantuan panjang juga
            # informasional dalam bentuk polosnya -
            # terdaftar di zona caseInsensitive sumber
            # sehingga hurufnya ikut dilipat; alat
            # berhenti sebelum membaca (atau mati
            # GetOptException untuk --help-options
            # yang tak terdaftar di summarize).
            if t_awal.lower() in ("--help",
                                  "--help-verbose",
                                  "--help-fields",
                                  "--help-options"):
                return False
            # Audit 2423: bentuk "=" opsi
            # informasional - bawaan getopt
            # hanya cocok persis (galat), opsi
            # bool alat mengurai nilai: selain
            # "false" berhenti juga.
            dasar_aw = t_awal.split("=", 1)[0]
            if ("=" in t_awal
                    and (dasar_aw in ("-V", "-h")
                         or dasar_aw.lower() in (
                             "--help", "--version",
                             "--help-verbose",
                             "--help-fields",
                             "--help-options"))
                    and (dasar_aw in ("-h",)
                         or dasar_aw.lower() == "--help"
                         or t_awal.split("=", 1)[1].lower()
                         != "false")):
                return False
            if (t_awal.startswith("-")
                    and not t_awal.startswith("--")
                    and len(t_awal) > 2
                    and t_awal[1].isalpha()
                    and t_awal[1].lower() not in "gdprv"
                    and not ((t_awal[1] == "H"
                              or t_awal[1] in "wxWX")
                             and t_awal[2:].lower() in (
                                 "true", "false"))):
                return False
            # Audit 2575: token sesudah opsi bernilai
            # bentuk terpisah adalah NILAI opsi itu -
            # tak pernah informasional, walau bentuknya
            # persis token bantuan; lewati agar tak
            # dinilai. Himpunan sama dengan yang
            # dilompati pengumpul operand (2374),
            # tanpa --count yang tak bernilai.
            if ("=" not in t_awal
                    and t_awal.lower() in (
                        "--group-by", "-d", "--delimiter",
                        "--sum", "--mean", "--median",
                        "--min", "--max", "--mode",
                        "--first", "--last", "--stdev",
                        "--var", "--range", "--quantile",
                        "--retain", "--values",
                        "--unique-values", "--unique-count",
                        "--mode-count", "--missing-count",
                        "--not-missing-count",
                        "--count-header", "--mad", "-g",
                        "-v", "--values-delimiter", "-p",
                        "--float-precision", "-r",
                        "--replace-missing")):
                i_awal += 1
        # Audit 2374: getopt sumber tak peka
        # huruf - operand dihitung lokal dengan
        # nama opsi dilipat huruf kecil.
        ops_tsm = []
        i_ot = 0
        while i_ot < len(mentah):
            t_ot = mentah[i_ot]
            if t_ot == "--":
                ops_tsm.extend(mentah[i_ot + 1:])
                break
            nama_ot = t_ot.lower().split("=", 1)[0]
            if nama_ot in ("--group-by", "-d",
                           "--delimiter", "--sum",
                           "--mean", "--median",
                           "--min", "--max", "--mode",
                           "--first", "--last",
                           "--stdev", "--var",
                           "--range", "--quantile",
                           "--retain", "--values",
                           "--unique-values",
                           "--unique-count",
                           "--mode-count",
                           "--missing-count",
                           "--not-missing-count",
                           "--count-header", "--mad",
                           "-g", "-v",
                           "--values-delimiter", "-p",
                           "--float-precision", "-r",
                           "--replace-missing"):
                if "=" in t_ot:
                    i_ot += 1
                    continue
                if i_ot + 1 >= len(mentah):
                    ops_tsm.append("\x00gantung")
                    break
                i_ot += 2
                continue
            if t_ot.startswith("-") and t_ot != "-":
                i_ot += 1
                continue
            ops_tsm.append(t_ot)
            i_ot += 1
        if "\x00gantung" in ops_tsm:
            return False
        # Audit 2370: tanpa operator perintah
        # ditolak sebelum masukan dibuka
        # (tsv-summarize.d enforce operator).
        # Audit 2374: nama getopt tak peka huruf
        # (config.caseInsensitive sumber).
        # Audit 2375: token berawalan dash
        # sesudah opsi bernilai = opsi itu
        # menggantung (galat getopt) - model itu
        # dipersempit Audit 2573/2574: yang benar-
        # benar menggantung hanya penerima char/
        # numerik dengan nilai tak sah; penerima
        # string dan callback dua-parameter
        # memakan token berikutnya tanpa syarat.
        ada_op_tsm = False
        gantung_tsm = False
        # Audit 2554: kosakata nama panjang resmi
        # tsv-summarize.d - nama di luar ini mati
        # sebagai GetOptException sebelum membaca.
        # --header/--version pulau caseSensitive:
        # hanya bentuk persisnya yang dikenal.
        panjang_tsm = frozenset({
            "--group-by", "--delimiter",
            "--values-delimiter", "--float-precision",
            "--replace-missing", "--sum", "--mean",
            "--median", "--min", "--max", "--mode",
            "--first", "--last", "--stdev", "--var",
            "--range", "--quantile", "--retain",
            "--values", "--unique-values",
            "--unique-count", "--mode-count",
            "--missing-count", "--not-missing-count",
            "--count-header", "--mad", "--count",
            "--write-header", "--exclude-missing",
            "--help", "--help-verbose",
            "--help-fields", "--help-options"})
        i_tsm = 0
        while i_tsm < len(mentah):
            t_tsm = mentah[i_tsm]
            if t_tsm == "--":
                break
            # Audit 2553: -V berekor nempel -
            # version berhenti sebelum membaca
            # kecuali ekor bool "false" (alat
            # tetap jalan), sejajar perlakuan
            # bentuk "=" di penjaga 2423.
            if (t_tsm.startswith("-V")
                    and not t_tsm.startswith("--")
                    and len(t_tsm) > 2
                    and t_tsm[2:].lower() != "false"):
                return False
            # Audit 2573: nilai nempel token pendek
            # penerima char (-d/-v) - ekor token
            # adalah nilainya; ekor yang bukan
            # tepat satu char satu-byte membunuh
            # alat di getopt (ConvException).
            if (len(t_tsm) > 2
                    and t_tsm.startswith("-")
                    and not t_tsm.startswith("--")
                    and "=" not in t_tsm
                    and t_tsm[1] in "dv"
                    and not _char_getopt_sah(t_tsm[2:])):
                return False
            # Audit 2574: nilai nempel token pendek
            # penerima numerik (-p) - ekor yang bukan
            # literal uint membunuh alat di getopt.
            if (len(t_tsm) > 2
                    and t_tsm.startswith("-")
                    and not t_tsm.startswith("--")
                    and "=" not in t_tsm
                    and t_tsm[1] == "p"
                    and not _uint_getopt_sah(t_tsm[2:])):
                return False
            nama_tsm = t_tsm.lower().split("=", 1)[0]
            mentah_tsm = t_tsm.split("=", 1)[0]
            if mentah_tsm.startswith("--"):
                if mentah_tsm in ("--header",
                                   "--version"):
                    pass
                elif nama_tsm in ("--header",
                                  "--version"):
                    return False
                elif nama_tsm not in panjang_tsm:
                    return False
            if nama_tsm in ("--sum", "--mean",
                            "--median", "--min",
                            "--max", "--mode",
                            "--first", "--last",
                            "--stdev", "--var",
                            "--range", "--quantile",
                            "--retain", "--values",
                            "--unique-values",
                            "--unique-count",
                            "--mode-count",
                            "--missing-count",
                            "--not-missing-count",
                            "--count-header", "--mad",
                            "--count"):
                ada_op_tsm = True
                if ("=" not in t_tsm
                        and nama_tsm != "--count"):
                    # Audit 2574: operator adalah
                    # callback dua-parameter - token
                    # berikutnya dimakan sebagai nilai
                    # TANPA syarat (handleOption
                    # Phobos), sama seperti -g/-r;
                    # model gantung lama untuk operator
                    # terbukti fail-open.
                    i_tsm += 2
                    continue
                i_tsm += 1
                continue
            if nama_tsm in ("--group-by",
                            "--delimiter",
                            "--values-delimiter",
                            "--float-precision",
                            "--replace-missing",
                            "-g", "-d", "-v", "-p",
                            "-r"):
                if "=" in t_tsm:
                    # Audit 2573: nilai nempel penerima
                    # char (-d/-v) sah hanya tepat satu
                    # char satu-byte - di luar itu
                    # to!char melempar ConvException
                    # dan alat mati sebelum membaca.
                    if (nama_tsm in ("-d",
                                     "--delimiter",
                                     "-v",
                                     "--values-delimiter")
                            and not _char_getopt_sah(
                                t_tsm.split("=", 1)[1])):
                        return False
                    # Audit 2574: nilai nempel penerima
                    # numerik (-p) - bukan literal uint
                    # membunuh alat di getopt.
                    if (nama_tsm in ("-p",
                                     "--float-precision")
                            and not _uint_getopt_sah(
                                t_tsm.split("=", 1)[1])):
                        return False
                    i_tsm += 1
                    continue
                # Audit 2560 + koreksi 2573: penerima
                # string (-r) memakan token berikutnya
                # TANPA syarat (handleOption Phobos);
                # penerima char (-d/-v) hanya memakan
                # nilai tepat satu char satu-byte -
                # nilai lain membunuh alat di getopt
                # (gantung). Model gantung -g/-p
                # belum berubah di langkah ini.
                if nama_tsm in ("-d",
                                "--delimiter",
                                "-v",
                                "--values-delimiter"):
                    if (i_tsm + 1 >= len(mentah)
                            or not _char_getopt_sah(
                                mentah[i_tsm + 1])):
                        gantung_tsm = True
                elif nama_tsm in ("-p",
                                  "--float-precision"):
                    # Audit 2574: penerima numerik
                    # hanya memakan literal uint -
                    # nilai lain (termasuk yang
                    # berawalan '-') membunuh alat.
                    if (i_tsm + 1 >= len(mentah)
                            or not _uint_getopt_sah(
                                mentah[i_tsm + 1])):
                        gantung_tsm = True
                # Audit 2574: -g (dan -r) adalah
                # penerima string/callback dua-
                # parameter - token berikutnya
                # dimakan TANPA syarat; tak ada
                # model gantung untuk keduanya.
                i_tsm += 2
                continue
            i_tsm += 1
        if gantung_tsm or not ada_op_tsm:
            return False
        return not ops_tsm or "-" in ops_tsm
    if perintah == "csvjoin":
        # Audit 2125: alat multi-masukan - polos
        # tak menguras, tetapi operand '-' berarti
        # STDIN (Tips resmi csvkit).
        # Audit 2133: --inner BUKAN bendera resmi
        # csvjoin - argparse menolaknya (EXIT2).
        if "--inner" in mentah:
            return False
        bernilai_cj = _CSVKIT | {"-c", "--columns"}
        if _jebakan_argparse(mentah, bernilai_cj):
            return False
        toks_cj = _saring_csvkit(toks, awal, "csvjoin")
        if toks_cj is None:
            return False
        ops_cj = _operand_csvkit(
            toks_cj, awal, bernilai_cj,
            bool_cabang={"--left", "--outer",
                         "--right",
                         "--no-inference"},
            pendek_bool={"-I"})
        if "\x00gantung" in ops_cj:
            return False
        return not ops_cj or "-" in ops_cj
    if perintah == "csvstack":
        # Audit 2126: sama seperti csvjoin - polos
        # tak menguras, operand '-' berarti STDIN
        # (Tips resmi csvkit).
        bernilai_cst = _CSVKIT | {"-g", "--groups",
                                  "-n", "--group-name"}
        if _token_terlarang(mentah, {"--null-value", "--date-format", "--datetime-format", "--blanks", "--no-leading-zeroes", "-L", "--locale", "-y", "--snifflimit"}, bernilai_cst):
            return False
        if _jebakan_argparse(mentah, bernilai_cst):
            return False
        ops_cst = _operand_csvkit(
            toks, awal, bernilai_cst,
            bool_cabang={"--filenames"})
        if "\x00gantung" in ops_cst:
            return False
        return not ops_cst or "-" in ops_cst
    if perintah == "tsv-join":
        # Audit 2391: getopt alat ini
        # caseInsensitive (pulau caseSensitive
        # hanya H/V bool) - lipat token opsi
        # ke huruf kecil; -V persis = version.
        # Audit 2401/2406: pindaian -V/-h/
        # --help/--version melewati posisi
        # NILAI opsi bernilai (handleOption
        # Phobos memakannya tanpa syarat).
        bernilai_lp = ['--append-fields', '--data-fields', '--delimiter', '--filter-file', '--key-fields', '--prefix', '--write-all', '-V', '-a', '-d', '-f', '-k', '-p', '-w']
        # Audit 2554: kosakata nama panjang resmi
        # tsv-join.d - nama di luar ini mati
        # sebagai GetOptException sebelum membaca.
        # --header/--version pulau caseSensitive.
        panjang_tj = frozenset({
            "--help", "--help-verbose",
            "--help-fields", "--help-options",
            "--filter-file", "--key-fields",
            "--data-fields", "--append-fields",
            "--prefix", "--write-all", "--exclude",
            "--delimiter", "--allow-duplicate-keys",
            "--line-buffered"})
        i_lp = 0
        while i_lp < len(mentah):
            t_lp = mentah[i_lp]
            if t_lp == "--":
                break
            dasar_lp = t_lp.split("=", 1)[0]
            if dasar_lp.startswith("--"):
                if dasar_lp in ("--header",
                                "--version"):
                    pass
                elif dasar_lp.lower() in ("--header",
                                          "--version"):
                    return False
                elif dasar_lp.lower() not in panjang_tj:
                    return False
            if ("=" in t_lp
                    and (dasar_lp in ("-V", "-h")
                         or dasar_lp.lower() in (
                             "--help", "--version",
                             "--help-verbose",
                             "--help-fields",
                             "--help-options"))
                    and (dasar_lp in ("-h",)
                         or dasar_lp.lower() == "--help"
                         or t_lp.split("=", 1)[1].lower()
                         != "false")):
                return False
            # Audit 2567: ekor nempel -V/-h sejajar
            # model 2553 - -h berekor selalu berhenti
            # (hanya cocok sebagai token persis di
            # sapuan akhir getopt); -V berekor berhenti
            # kecuali ekor bool "false" (version mati,
            # alat tetap jalan).
            if (not t_lp.startswith("--")
                    and len(t_lp) > 2
                    and (t_lp.startswith("-h")
                         or (t_lp.startswith("-V")
                             and t_lp[2:].lower()
                             != "false"))):
                return False
            if (t_lp in ("-V", "-h")
                    or t_lp.lower() in ("--help",
                                        "--version",
                                        "--help-verbose",
                                        "--help-fields",
                                        "--help-options")):
                return False
            if (t_lp.split("=", 1)[0].lower() in bernilai_lp
                    and "=" not in t_lp):
                i_lp += 2
                continue
            i_lp += 1
        mentah = [t.lower() if t.startswith("-")
                  else t for t in mentah]
        toks = [t.lower() if t.startswith("-")
                else t for t in toks]
        # Audit 2127: --filter-file WAJIB (sumber
        # tsv_join.d); dengan -f dan tanpa berkas
        # data, aliran data utama dari stdin.
        if not any(t_tj in ("-f", "--filter-file")
                   or t_tj.startswith("--filter-file=")
                   or (len(t_tj) > 2
                       and t_tj.startswith("-f")
                       and not t_tj.startswith("--"))
                   for t_tj in mentah):
            return False
        # Audit 2134: kosakata resmi tsv_join.d -
        # k|key-fields, d|data-fields,
        # a|append-fields, p|prefix, w|write-all.
        ops_tj = _operand_sesudah_opsi(
            toks, awal, {"-f", "--filter-file", "-k",
                         "--key-fields", "-d",
                         "--data-fields", "-a",
                         "--append-fields", "-p",
                         "--prefix", "-w",
                         "--write-all",
                         "--delimiter"})
        if "\x00gantung" in ops_tj:
            return False
        # Audit 2135: filter dari stdin ('-f -')
        # membalik syarat - berkas data WAJIB ada
        # (sumber tsv_join.d); stdin tetap terpakai
        # sebagai filter.
        nilai_f = None
        for k_tj, t_tj in enumerate(mentah):
            if t_tj in ("-f", "--filter-file") \
                    and k_tj + 1 < len(mentah):
                nilai_f = mentah[k_tj + 1]
            elif t_tj.startswith("--filter-file="):
                nilai_f = t_tj.split("=", 1)[1]
            elif (len(t_tj) > 2
                    and t_tj.startswith("-f")
                    and not t_tj.startswith("--")):
                nilai_f = t_tj[2:]
        if nilai_f == "-":
            return len(ops_tj) >= 1 and ops_tj[0] != "-"
        return not ops_tj or "-" in ops_tj
    if perintah == "csvformat":
        # Audit 2138: tanpa FILE ia menerima input
        # terpipa via STDIN (csvformat(1) csvkit).
        # Audit 2147: opsi keluaran csvformat
        # bernilai resmi di luar _CSVKIT.
        bernilai_cf = _CSVKIT | {
            "-D", "--out-delimiter", "-Q",
            "--out-quotechar", "-U", "--out-quoting",
            "-P", "--out-escapechar", "-M",
            "--out-lineterminator"}
        if _token_terlarang(mentah, {"--null-value", "--date-format", "--datetime-format", "--blanks", "--no-leading-zeroes", "-y", "--snifflimit"}, bernilai_cf):
            return False
        if _jebakan_argparse(mentah, bernilai_cf):
            return False
        ops_cf = _operand_csvkit(
            toks, awal, bernilai_cf,
            bool_cabang={"--out-asv",
                         "--out-no-doublequote",
                         "--out-tabs",
                         "--skip-header"},
            pendek_bool={"-A", "-B", "-E",
                         "-T"})
        if "\x00gantung" in ops_cf:
            return False
        return not ops_cf or ops_cf == ["-"]
    if perintah == "sql2csv":
        # Audit 2139: tanpa FILE dan tanpa --query,
        # kueri SQL dibaca dari STDIN (sql2csv(1));
        # --query menimpa FILE dan STDIN.
        # Audit 2405: --query juga bentuk
        # singkatan awalan uniknya.
        if _hadir_unik(
                mentah, "--query",
                _CSVKIT | {"--db", "--query",
                           "--engine-option",
                           "--execution-option",
                           "--verbose",
                           "--no-header-row",
                           "--help", "--version"}):
            return False
        bernilai_sq = _CSVKIT | {"--db", "--query", "--engine-option", "--execution-option"}
        if _token_terlarang(mentah, {"-d", "--delimiter", "-q", "--quotechar", "-u", "--quoting", "-p", "--escapechar", "-z", "--maxfieldsize", "-y", "--snifflimit", "-L", "--locale", "-K", "--skip-lines", "--null-value", "--date-format", "--datetime-format", "--zero", "--add-bom", "--blanks", "--no-leading-zeroes", "-t", "--tabs", "-S", "--skipinitialspace", "-b", "--no-doublequote"}, bernilai_sq):
            return False
        if _jebakan_argparse(mentah, bernilai_sq):
            return False
        toks_sq = _saring_csvkit(toks, awal, "sql2csv")
        if toks_sq is None:
            return False
        ops_sq = _operand_csvkit(
            toks_sq, awal, bernilai_sq,
            kecuali={"--zero", "--add-bom",
                     "--blanks",
                     "--no-leading-zeroes",
                     "--tabs",
                     "--skipinitialspace"})
        if "\x00gantung" in ops_sq:
            return False
        return not ops_sq or ops_sq == ["-"]
    if perintah == "tsv-uniq":
        # Audit 2391: getopt alat ini
        # caseInsensitive (pulau caseSensitive
        # hanya H/V bool) - lipat token opsi
        # ke huruf kecil; -V persis = version.
        # Audit 2401/2406: pindaian -V/-h/
        # --help/--version melewati posisi
        # NILAI opsi bernilai (handleOption
        # Phobos memakannya tanpa syarat).
        bernilai_lp = ['--at-least', '--delimiter', '--equiv-header', '--equiv-start', '--fields', '--max', '--number-header', '-V', '-a', '-d', '-f', '-m']
        i_lp = 0
        while i_lp < len(mentah):
            t_lp = mentah[i_lp]
            if t_lp == "--":
                break
            dasar_lp = t_lp.split("=", 1)[0]
            if ("=" in t_lp
                    and (dasar_lp in ("-V", "-h")
                         or dasar_lp.lower() in (
                             "--help", "--version",
                             "--help-verbose",
                             "--help-fields",
                             "--help-options"))
                    and (dasar_lp in ("-h",)
                         or dasar_lp.lower() == "--help"
                         or t_lp.split("=", 1)[1].lower()
                         != "false")):
                return False
            if (t_lp in ("-V", "-h")
                    or t_lp.lower() in ("--help",
                                        "--version",
                                        "--help-verbose",
                                        "--help-fields",
                                        "--help-options")):
                return False
            if not _nama_sah_tsv(
                    t_lp.split("=", 1)[0],
                    _SAH_TSV["tsv-uniq"]):
                # Audit 2498: nama tak dikenal =
                # GetOptException - berhenti.
                return False
            if (t_lp.split("=", 1)[0].lower() in bernilai_lp
                    and "=" not in t_lp):
                i_lp += 2
                continue
            i_lp += 1
        mentah = [t.lower() if t.startswith("-")
                  else t for t in mentah]
        toks = [t.lower() if t.startswith("-")
                else t for t in toks]
        # Audit 2140: tanpa berkas ia membaca
        # standard input (dok resmi tsv-utils).
        # Audit 2420: token strip-angka di
        # posisi opsi = operand berkas/galat
        # getopt - tak pernah menguras.
        i_du = 0
        while i_du < len(mentah):
            t_du = mentah[i_du]
            if t_du == "--":
                break
            if (t_du.split("=", 1)[0]
                    in ("-d", "--delimiter", "-f",
                        "--fields", "-a",
                        "--at-least", "-m", "--max",
                        "--equiv-header",
                        "--equiv-start",
                        "--number-header")
                    and "=" not in t_du):
                i_du += 2
                continue
            if (len(t_du) > 1 and t_du[0] == "-"
                    and t_du[1].isdigit()):
                return False
            i_du += 1
        ops_tu = _operand_sesudah_opsi(
            toks, awal, {"-d", "--delimiter", "-f", "--fields", "-a", "--at-least", "-m", "--max", "--equiv-header", "--equiv-start", "--number-header"})
        if "\x00gantung" in ops_tu:
            return False
        return not ops_tu or "-" in ops_tu
    if perintah == "tsv-sample":
        # Audit 2477: cabang baru - sumber
        # tsv-sample.d:351: tanpa argumen berkas
        # filepaths bawaan ['-'] = stdin (pola
        # sama dengan enam saudara tsv lain).
        # getopt caseInsensitive (2391); pindaian
        # informasional melewati posisi nilai
        # (2401/2406). Opsi bernilai dari sumber:
        # -d/--delimiter, -n/--num, -p/--prob,
        # -v/--seed-value, -w/--weight-field,
        # -k/--key-fields, --random-value-header.
        bernilai_lp = ['--delimiter', '--key-fields', '--num', '--prob', '--random-value-header', '--seed-value', '--weight-field', '-V', '-d', '-k', '-n', '-p', '-v', '-w']
        # Audit 2620: penerima bertipe -n/--num
        # ulong (tsv-sample.d:216) dan -p/--prob
        # double (:217) - nilai tak terkonversi
        # membunuh alat di getopt (ConvException),
        # sama seperti model 2573/2574 di
        # tsv-summarize. Penyusuran sadar nilai:
        # pembawa lain menelan nilainya lebih dulu.
        # Audit 2627: -v/--seed-value uint
        # (tsv-sample.d:223, ikatan :306) ikut
        # divalidasi - 2620 melewatkannya.
        i_mt = 0
        while i_mt < len(mentah):
            t_mt = mentah[i_mt]
            if t_mt == "--":
                break
            nama_mt, eq_mt, isi_mt = t_mt.partition("=")
            kunci_mt = nama_mt.lower()
            if kunci_mt in ("-n", "--num", "-p", "--prob",
                            "-v", "--seed-value"):
                pred_mt = (_double_getopt_sah
                           if kunci_mt in ("-p", "--prob")
                           else _uint_getopt_sah)
                if eq_mt:
                    if not pred_mt(isi_mt):
                        return False
                elif i_mt + 1 < len(mentah):
                    if not pred_mt(mentah[i_mt + 1]):
                        return False
                    i_mt += 1
                i_mt += 1
                continue
            if kunci_mt in bernilai_lp and not eq_mt:
                i_mt += 2
                continue
            if (len(t_mt) > 2 and t_mt.startswith("-")
                    and not t_mt.startswith("--")
                    and t_mt[1].lower() in ("n", "p", "v")):
                pred_mt = (_double_getopt_sah
                           if t_mt[1].lower() == "p"
                           else _uint_getopt_sah)
                if not pred_mt(t_mt[2:]):
                    return False
            i_mt += 1
        # Audit 2483: --seed-value pulau
        # caseSensitive di sumber (tsv-sample.d
        # :305-307) - varian huruf lain galat
        # getopt, bukan opsi bernilai.
        i_cs = 0
        while i_cs < len(mentah):
            t_cs = mentah[i_cs]
            if t_cs == "--":
                break
            d_cs = t_cs.split("=", 1)[0]
            if (d_cs.lower() == "--seed-value"
                    and d_cs != "--seed-value"):
                return False
            if (d_cs.lower() in bernilai_lp
                    and "=" not in t_cs):
                i_cs += 2
                continue
            i_cs += 1
        i_lp = 0
        while i_lp < len(mentah):
            t_lp = mentah[i_lp]
            if t_lp == "--":
                break
            dasar_lp = t_lp.split("=", 1)[0]
            if ("=" in t_lp
                    and (dasar_lp in ("-V", "-h")
                         or dasar_lp.lower() in (
                             "--help", "--version",
                             "--help-verbose",
                             "--help-fields",
                             "--help-options"))
                    and (dasar_lp in ("-h",)
                         or dasar_lp.lower() == "--help"
                         or t_lp.split("=", 1)[1].lower()
                         != "false")):
                return False
            if (t_lp in ("-V", "-h")
                    or t_lp.lower() in ("--help",
                                        "--version",
                                        "--help-verbose",
                                        "--help-fields",
                                        "--help-options")):
                return False
            if not _nama_sah_tsv(
                    t_lp.split("=", 1)[0],
                    _SAH_TSV["tsv-sample"]):
                # Audit 2498: nama tak dikenal =
                # GetOptException - berhenti.
                return False
            if (t_lp.split("=", 1)[0].lower() in bernilai_lp
                    and "=" not in t_lp):
                i_lp += 2
                continue
            i_lp += 1
        mentah = [t.lower() if t.startswith("-")
                  else t for t in mentah]
        toks = [t.lower() if t.startswith("-")
                else t for t in toks]
        # Audit 2420: token strip-angka di
        # posisi opsi = operand berkas/galat
        # getopt - tak pernah menguras.
        i_ds = 0
        while i_ds < len(mentah):
            t_ds = mentah[i_ds]
            if t_ds == "--":
                break
            if (t_ds.split("=", 1)[0]
                    in ("-d", "--delimiter", "-k",
                        "--key-fields", "-n", "--num",
                        "-p", "--prob", "-v",
                        "--seed-value", "-w",
                        "--weight-field",
                        "--random-value-header")
                    and "=" not in t_ds):
                i_ds += 2
                continue
            if (len(t_ds) > 1 and t_ds[0] == "-"
                    and t_ds[1].isdigit()):
                return False
            i_ds += 1
        ops_ts = _operand_sesudah_opsi(
            toks, awal, {"-d", "--delimiter", "-k", "--key-fields", "-n", "--num", "-p", "--prob", "-v", "--seed-value", "-w", "--weight-field", "--random-value-header"})
        if "\x00gantung" in ops_ts:
            return False
        return not ops_ts or "-" in ops_ts
    if perintah == "tsv-pretty":
        # Audit 2484: cabang baru - sumber
        # tsv-pretty.d:252: tanpa argumen berkas
        # filepaths bawaan ['-'] = stdin.
        # getopt caseInsensitive; opsi bernilai:
        # -d/--delimiter, -l/--lookahead,
        # -r/--repeat-header, -p/--precision,
        # -E/--empty-replacement,
        # -s/--space-between-fields,
        # -m/--max-text-width, -b/--preamble.
        bernilai_lp = ['--delimiter', '--empty-replacement', '--lookahead', '--max-text-width', '--preamble', '--precision', '--repeat-header', '--space-between-fields', '-E', '-V', '-b', '-d', '-l', '-m', '-p', '-r', '-s']
        # Audit 2629: cabang ini sebelumnya
        # tanpa model kematian penerima sama
        # sekali (saudara-saudara tsv lain
        # dibangun 2620). Penerima dari sumber
        # tsv-pretty.d: size_t terikat langsung
        # -l/-r/-s/-m/-b (:113-125), handler
        # -p memakai to!size_t (:227-231),
        # -d char (:121). Nilai tak terkonversi
        # membunuh alat di getopt.
        i_mt = 0
        while i_mt < len(mentah):
            t_mt = mentah[i_mt]
            if t_mt == "--":
                break
            nama_mt, eq_mt, isi_mt = t_mt.partition("=")
            kunci_mt = nama_mt.lower()
            if kunci_mt in ("-l", "--lookahead",
                            "-r", "--repeat-header",
                            "-s", "--space-between-fields",
                            "-m", "--max-text-width",
                            "-b", "--preamble",
                            "-p", "--precision",
                            "-d", "--delimiter"):
                pred_mt = (_char_getopt_sah
                           if kunci_mt in ("-d", "--delimiter")
                           else _uint_getopt_sah)
                if eq_mt:
                    if not pred_mt(isi_mt):
                        return False
                elif i_mt + 1 < len(mentah):
                    if not pred_mt(mentah[i_mt + 1]):
                        return False
                    i_mt += 1
                i_mt += 1
                continue
            # Audit 2647: -E adalah pulau
            # caseSensitive di sumber (ikatan
            # "E|empty-replacement") - uji lewati
            # harus memakai nama ASLI, bukan
            # kunci terlipat; sebelumnya nilai
            # -E tak pernah dilompati dan token
            # berbentuk opsi divonis mati palsu.
            if (kunci_mt in bernilai_lp
                    or nama_mt == "-E") and not eq_mt:
                i_mt += 2
                continue
            if (len(t_mt) > 2 and t_mt.startswith("-")
                    and not t_mt.startswith("--")
                    and t_mt[1].lower() in ("l", "r", "s",
                                            "m", "b", "p",
                                            "d")):
                pred_mt = (_char_getopt_sah
                           if t_mt[1].lower() == "d"
                           else _uint_getopt_sah)
                if not pred_mt(t_mt[2:]):
                    return False
            i_mt += 1
        # Audit 2484: --empty-replacement pulau
        # caseSensitive di sumber - varian
        # huruf lain galat getopt.
        i_cs = 0
        while i_cs < len(mentah):
            t_cs = mentah[i_cs]
            if t_cs == "--":
                break
            d_cs = t_cs.split("=", 1)[0]
            if (d_cs.lower() == "--empty-replacement"
                    and d_cs != "--empty-replacement"):
                return False
            if ((d_cs in bernilai_lp
                    or d_cs.lower() in bernilai_lp)
                    and "=" not in t_cs):
                i_cs += 2
                continue
            i_cs += 1
        i_lp = 0
        while i_lp < len(mentah):
            t_lp = mentah[i_lp]
            if t_lp == "--":
                break
            dasar_lp = t_lp.split("=", 1)[0]
            if ("=" in t_lp
                    and (dasar_lp in ("-V", "-h")
                         or dasar_lp.lower() in (
                             "--help", "--version",
                             "--help-verbose",
                             "--help-fields",
                             "--help-options"))
                    and (dasar_lp in ("-h",)
                         or dasar_lp.lower() == "--help"
                         or t_lp.split("=", 1)[1].lower()
                         != "false")):
                return False
            if (t_lp in ("-V", "-h")
                    or t_lp.lower() in ("--help",
                                        "--version",
                                        "--help-verbose",
                                        "--help-fields",
                                        "--help-options")):
                return False
            if not _nama_sah_tsv(
                    t_lp.split("=", 1)[0],
                    _SAH_TSV["tsv-pretty"]):
                # Audit 2498: nama tak dikenal =
                # GetOptException - berhenti.
                return False
            if ((t_lp.split("=", 1)[0] in bernilai_lp
                    or t_lp.split("=", 1)[0].lower()
                    in bernilai_lp)
                    and "=" not in t_lp):
                i_lp += 2
                continue
            i_lp += 1
        mentah = [t.lower() if t.startswith("--")
                  else t for t in mentah]
        toks = [t.lower() if t.startswith("--")
                else t for t in toks]
        # Audit 2420: token strip-angka di
        # posisi opsi = operand berkas/galat
        # getopt - tak pernah menguras.
        i_ds = 0
        while i_ds < len(mentah):
            t_ds = mentah[i_ds]
            if t_ds == "--":
                break
            if (t_ds.split("=", 1)[0]
                    in ('-d', '--delimiter', '-l', '--lookahead', '-r', '--repeat-header',
                        '-p', '--precision', '-E', '--empty-replacement', '-s', '--space-between-fields',
                        '-m', '--max-text-width', '-b', '--preamble',
                        '-D', '-L', '-R', '-P', '-S', '-M', '-B')
                    and "=" not in t_ds):
                i_ds += 2
                continue
            if (len(t_ds) > 1 and t_ds[0] == "-"
                    and t_ds[1].isdigit()):
                return False
            i_ds += 1
        ops_ts = _operand_sesudah_opsi(
            toks, awal, {'-d', '--delimiter', '-l', '--lookahead', '-r', '--repeat-header', '-p', '--precision', '-E', '--empty-replacement', '-s', '--space-between-fields', '-m', '--max-text-width', '-b', '--preamble', '-D', '-L', '-R', '-P', '-S', '-M', '-B'})
        if "\x00gantung" in ops_ts:
            return False
        return not ops_ts or "-" in ops_ts
    if perintah == "tsv-split":
        # Audit 2485: cabang baru - sumber
        # tsv-split.d:352: masukan bawaan ['-']
        # = stdin (keluaran ke berkas pecahan).
        # getopt caseInsensitive; opsi bernilai:
        # -d/--delimiter,
        # -k/--key-fields, -l/--lines-per-file,
        # -n/--num-files, -v/--seed-value,
        # -w/--digit-width, --dir, --prefix,
        # --suffix, --max-open-files.
        bernilai_lp = ['--delimiter', '--digit-width', '--dir', '--key-fields', '--lines-per-file', '--max-open-files', '--num-files', '--prefix', '--seed-value', '--suffix', '-V', '-d', '-k', '-l', '-n', '-v', '-w']
        # Audit 2620: penerima bertipe -l
        # size_t (tsv-split.d:233), -n uint
        # (:234), -w uint (:239) - nilai tak
        # terkonversi membunuh alat di getopt.
        # Penyusuran sadar nilai seperti
        # tsv-sample di atas.
        # Audit 2628: -v/--seed-value uint
        # (:242, ikatan :317) dan
        # --max-open-files uint (:244, ikatan
        # :321, tanpa huruf pendek) ikut
        # divalidasi - 2620 melewatkan keduanya.
        i_mt = 0
        while i_mt < len(mentah):
            t_mt = mentah[i_mt]
            if t_mt == "--":
                break
            nama_mt, eq_mt, isi_mt = t_mt.partition("=")
            kunci_mt = nama_mt.lower()
            if kunci_mt in ("-l", "--lines-per-file",
                            "-n", "--num-files",
                            "-w", "--digit-width",
                            "-v", "--seed-value",
                            "--max-open-files"):
                if eq_mt:
                    if not _uint_getopt_sah(isi_mt):
                        return False
                elif i_mt + 1 < len(mentah):
                    if not _uint_getopt_sah(
                            mentah[i_mt + 1]):
                        return False
                    i_mt += 1
                i_mt += 1
                continue
            if kunci_mt in bernilai_lp and not eq_mt:
                i_mt += 2
                continue
            if (len(t_mt) > 2 and t_mt.startswith("-")
                    and not t_mt.startswith("--")
                    and t_mt[1].lower() in ("l", "n", "w", "v")):
                if not _uint_getopt_sah(t_mt[2:]):
                    return False
            i_mt += 1
        # Audit 2485: --seed-value pulau
        # caseSensitive di sumber - varian
        # huruf lain galat getopt.
        i_cs = 0
        while i_cs < len(mentah):
            t_cs = mentah[i_cs]
            if t_cs == "--":
                break
            d_cs = t_cs.split("=", 1)[0]
            if (d_cs.lower() == "--seed-value"
                    and d_cs != "--seed-value"):
                return False
            if (d_cs.lower() in bernilai_lp
                    and "=" not in t_cs):
                i_cs += 2
                continue
            i_cs += 1
        i_lp = 0
        while i_lp < len(mentah):
            t_lp = mentah[i_lp]
            if t_lp == "--":
                break
            dasar_lp = t_lp.split("=", 1)[0]
            if ("=" in t_lp
                    and (dasar_lp in ("-V", "-h")
                         or dasar_lp.lower() in (
                             "--help", "--version",
                             "--help-verbose",
                             "--help-fields",
                             "--help-options"))
                    and (dasar_lp in ("-h",)
                         or dasar_lp.lower() == "--help"
                         or t_lp.split("=", 1)[1].lower()
                         != "false")):
                return False
            if (t_lp in ("-V", "-h")
                    or t_lp.lower() in ("--help",
                                        "--version",
                                        "--help-verbose",
                                        "--help-fields",
                                        "--help-options")):
                return False
            if not _nama_sah_tsv(
                    t_lp.split("=", 1)[0],
                    _SAH_TSV["tsv-split"]):
                # Audit 2498: nama tak dikenal =
                # GetOptException - berhenti.
                return False
            if (t_lp.split("=", 1)[0].lower() in bernilai_lp
                    and "=" not in t_lp):
                i_lp += 2
                continue
            i_lp += 1
        mentah = [t.lower() if t.startswith("-")
                  else t for t in mentah]
        toks = [t.lower() if t.startswith("-")
                else t for t in toks]
        # Audit 2420: token strip-angka di
        # posisi opsi = operand berkas/galat
        # getopt - tak pernah menguras.
        i_ds = 0
        while i_ds < len(mentah):
            t_ds = mentah[i_ds]
            if t_ds == "--":
                break
            if (t_ds.split("=", 1)[0]
                    in ('-d', '--delimiter', '-k', '--key-fields',
                        '-l', '--lines-per-file', '-n', '--num-files', '-v', '--seed-value',
                        '-w', '--digit-width', '--dir', '--prefix', '--suffix', '--max-open-files')
                    and "=" not in t_ds):
                i_ds += 2
                continue
            if (len(t_ds) > 1 and t_ds[0] == "-"
                    and t_ds[1].isdigit()):
                return False
            i_ds += 1
        ops_ts = _operand_sesudah_opsi(
            toks, awal, {'-d', '--delimiter', '-k', '--key-fields', '-l', '--lines-per-file', '-n', '--num-files', '-v', '--seed-value', '-w', '--digit-width', '--dir', '--prefix', '--suffix', '--max-open-files'})
        if "\x00gantung" in ops_ts:
            return False
        return not ops_ts or "-" in ops_ts
    if perintah == "csv2tsv":
        # Audit 2486: cabang baru - sumber
        # csv2tsv.d:245: tanpa argumen berkas
        # filepaths bawaan ['-'] = stdin.
        # KELIMA opsi bernilainya caseSensitive
        # di sumber (-c/--csv-delim, -t/--tsv-
        # delim, -r/--tab-replacement,
        # -n/--newline-replacement, -q/--quote)
        # - cabang ini TIDAK melipat huruf
        # untuk pencocokan opsi bernilai.
        bernilai_cs = {"-c", "--csv-delim", "-t",
                       "--tsv-delim", "-r",
                       "--tab-replacement", "-n",
                       "--newline-replacement", "-q",
                       "--quote"}
        # Audit 2620: penerima char -c/-t/-q
        # (csv2tsv.d:80-82) - nilai yang bukan
        # tepat satu char satu-byte membunuh
        # alat di getopt. Cabang ini caseSensitive:
        # pencocokan persis tanpa lipat huruf.
        i_mt = 0
        while i_mt < len(mentah):
            t_mt = mentah[i_mt]
            if t_mt == "--":
                break
            nama_mt, eq_mt, isi_mt = t_mt.partition("=")
            if nama_mt in ("-c", "--csv-delim", "-t",
                           "--tsv-delim", "-q", "--quote"):
                if eq_mt:
                    if not _char_getopt_sah(isi_mt):
                        return False
                elif i_mt + 1 < len(mentah):
                    if not _char_getopt_sah(
                            mentah[i_mt + 1]):
                        return False
                    i_mt += 1
                i_mt += 1
                continue
            if nama_mt in bernilai_cs and not eq_mt:
                i_mt += 2
                continue
            if (len(t_mt) > 2 and t_mt.startswith("-")
                    and not t_mt.startswith("--")
                    and t_mt[1] in ("c", "t", "q")):
                if not _char_getopt_sah(t_mt[2:]):
                    return False
            i_mt += 1
        i_lp = 0
        while i_lp < len(mentah):
            t_lp = mentah[i_lp]
            if t_lp == "--":
                break
            dasar_lp = t_lp.split("=", 1)[0]
            if ("=" in t_lp
                    and (dasar_lp in ("-V", "-h")
                         or dasar_lp.lower() in (
                             "--help", "--version",
                             "--help-verbose",
                             "--help-fields",
                             "--help-options"))
                    and (dasar_lp in ("-h",)
                         or dasar_lp.lower() == "--help"
                         or t_lp.split("=", 1)[1].lower()
                         != "false")):
                return False
            if (t_lp in ("-V", "-h")
                    or t_lp.lower() in ("--help",
                                        "--version",
                                        "--help-verbose",
                                        "--help-fields",
                                        "--help-options")):
                return False
            if not _nama_sah_tsv(
                    dasar_lp, _SAH_TSV["csv2tsv"]):
                # Audit 2498: nama tak dikenal =
                # GetOptException - berhenti.
                return False
            if (dasar_lp in bernilai_cs
                    and "=" not in t_lp):
                i_lp += 2
                continue
            i_lp += 1
        i_ds = 0
        while i_ds < len(mentah):
            t_ds = mentah[i_ds]
            if t_ds == "--":
                break
            if (t_ds.split("=", 1)[0] in bernilai_cs
                    and "=" not in t_ds):
                i_ds += 2
                continue
            if (len(t_ds) > 1 and t_ds[0] == "-"
                    and t_ds[1].isdigit()):
                return False
            i_ds += 1
        ops_cs = _operand_sesudah_opsi(
            toks, awal, bernilai_cs)
        if "\x00gantung" in ops_cs:
            return False
        return not ops_cs or "-" in ops_cs

    if perintah == "number-lines":
        # Audit 2488: cabang baru - sumber
        # number-lines.d:146: tanpa argumen
        # berkas filepaths bawaan ['-'] = stdin.
        # getopt caseInsensitive; opsi bernilai:
        # -d/--delimiter, -n/--start-number,
        # -s/--header-string.
        bernilai_lp = ['--delimiter', '--header-string', '--start-number', '-V', '-d', '-n', '-s']
        i_lp = 0
        while i_lp < len(mentah):
            t_lp = mentah[i_lp]
            if t_lp == "--":
                break
            dasar_lp = t_lp.split("=", 1)[0]
            if ("=" in t_lp
                    and (dasar_lp in ("-V", "-h")
                         or dasar_lp.lower() in (
                             "--help", "--version",
                             "--help-verbose",
                             "--help-fields",
                             "--help-options"))
                    and (dasar_lp in ("-h",)
                         or dasar_lp.lower() == "--help"
                         or t_lp.split("=", 1)[1].lower()
                         != "false")):
                return False
            if (t_lp in ("-V", "-h")
                    or t_lp.lower() in ("--help",
                                        "--version",
                                        "--help-verbose",
                                        "--help-fields",
                                        "--help-options")):
                return False
            if not _nama_sah_tsv(
                    t_lp.split("=", 1)[0],
                    _SAH_TSV["number-lines"]):
                # Audit 2498: nama tak dikenal =
                # GetOptException - berhenti.
                return False
            if (t_lp.split("=", 1)[0].lower() in bernilai_lp
                    and "=" not in t_lp):
                i_lp += 2
                continue
            i_lp += 1
        mentah = [t.lower() if t.startswith("-")
                  else t for t in mentah]
        toks = [t.lower() if t.startswith("-")
                else t for t in toks]
        # Audit 2420: token strip-angka di
        # posisi opsi = operand berkas/galat
        # getopt - tak pernah menguras.
        i_ds = 0
        while i_ds < len(mentah):
            t_ds = mentah[i_ds]
            if t_ds == "--":
                break
            if (t_ds.split("=", 1)[0]
                    in ('-d', '--delimiter', '-n', '--start-number', '-s', '--header-string')
                    and "=" not in t_ds):
                i_ds += 2
                continue
            if (len(t_ds) > 1 and t_ds[0] == "-"
                    and t_ds[1].isdigit()):
                return False
            i_ds += 1
        ops_ts = _operand_sesudah_opsi(
            toks, awal, {'-d', '--delimiter', '-n', '--start-number', '-s', '--header-string'})
        if "\x00gantung" in ops_ts:
            return False
        return not ops_ts or "-" in ops_ts

    if perintah == "keep-header":
        # Audit 2487: cabang baru - sumber
        # keep-header.d:96: berkas SEBELUM '--'
        # (bawaan ['-'] = stdin); sisa baris
        # disalurkan ke stdin perintah anak
        # SESUDAH '--'. Tanpa getopt (urai
        # manual); tanpa '--' pipa anak gagal
        # dibuat -> berhenti sebelum membaca.
        berkas_kh = []
        i_kh = 0
        ketemu_kh = False
        while i_kh < len(mentah):
            t_kh = mentah[i_kh]
            if t_kh == "--":
                ketemu_kh = True
                break
            if t_kh in (">", ">>", "<", "2>", "2>>",
                        "&>", "1>", "1>>"):
                i_kh += 2
                continue
            berkas_kh.append(t_kh)
            i_kh += 1
        if not ketemu_kh:
            return False
        # Audit 2501: lewati pasangan redirect
        # sesudah pemisah - shell membuangnya
        # sebelum exec, jadi bukan anak.
        anak_kh = i_kh + 1
        while (anak_kh < len(mentah)
               and mentah[anak_kh] in (">", ">>",
                                       "<", "2>",
                                       "2>>", "&>",
                                       "1>", "1>>")):
            anak_kh += 2
        if anak_kh >= len(mentah):
            return False
        # Audit 2492: perintah anak '--' =
        # nama program tak sah - spawn gagal
        # sebelum membaca (findSplit sumber).
        if mentah[anak_kh] == "--":
            return False
        return not berkas_kh or "-" in berkas_kh
    if perintah == "csvsql":
        # Audit 2153: tanpa FILE data CSV dibaca
        # dari STDIN (csvsql(1)); SQL dari --query.
        bernilai_csq = _CSVKIT | {
            "--db", "--query", "-i", "--dialect",
            "--prefix", "--sql-delimiter", "--tables",
            "--unique-constraint", "--db-schema",
            "--min-col-len", "--col-len-multiplier",
            "--engine-option", "--before-insert",
            "--after-insert", "--chunk-size"}
        if _jebakan_argparse(mentah, bernilai_csq):
            return False
        # Audit 2430: token -- adalah pemisah.
        mentah_csq = (mentah[:mentah.index("--")]
                      if "--" in mentah else mentah)
        # Audit 2410: awalan ambigu = galat
        # argparse (bukan nama persis).
        if any(t_csq.startswith("--")
               and t_csq.split("=", 1)[0] not in bernilai_csq
               and sum(1 for b_csq in bernilai_csq
                       if b_csq.startswith(
                           t_csq.split("=", 1)[0])) > 1
               for t_csq in mentah_csq):
            return False
        # Audit 2418: --execution-option
        # TIDAK ada di csvsql (hanya sql2csv) -
        # bentuk utuh/singkatannya galat.
        if any(t_csq.startswith("--")
               and "--execution-option".startswith(
                   t_csq.split("=", 1)[0])
               and not "--engine-option".startswith(
                   t_csq.split("=", 1)[0])
               for t_csq in mentah_csq):
            return False
        # Audit 2439: validasi pendamping
        # csvsql.py - --dialect bertabrakan
        # --db/--query; --insert butuh salah
        # satunya; opsi turunan insert butuh
        # --insert tersurat/tersirat (query
        # tanpa db); --overwrite x --no-create
        # dan --no-create x
        # --create-if-not-exists terlarang.
        dicari_csq = {"--db", "--query",
                      "--insert", "--dialect",
                      "-i", "--no-create",
                      "--create-if-not-exists",
                      "--overwrite",
                      "--before-insert",
                      "--after-insert",
                      "--chunk-size"}
        ada_csq = set()
        i_acq = 0
        while i_acq < len(mentah):
            t_acq = mentah[i_acq]
            if t_acq == "--":
                break
            if t_acq in bernilai_csq:
                if t_acq in dicari_csq:
                    ada_csq.add(t_acq)
                i_acq += 2
                continue
            if t_acq.startswith("--"):
                dasar_acq = t_acq.split("=", 1)[0]
                if dasar_acq in dicari_csq:
                    ada_csq.add(dasar_acq)
                else:
                    # Audit 2443: singkatan awalan
                    # unik ikut dikenali.
                    semesta_csq = (
                        bernilai_csq | dicari_csq
                        | {"--create-if-not-exists",
                           "--insert",
                           "--no-constraints",
                           "--no-create",
                           "--no-inference",
                           "--overwrite"})
                    cocok_csq = [
                        x_csq for x_csq in semesta_csq
                        if x_csq.startswith("--")
                        and x_csq.startswith(dasar_acq)]
                    if (len(cocok_csq) == 1
                            and cocok_csq[0]
                            in dicari_csq):
                        ada_csq.add(cocok_csq[0])
                i_acq += 1
                continue
            if (t_acq.startswith("-")
                    and len(t_acq) > 1):
                for huruf_acq in t_acq[1:]:
                    eja_acq = "-" + huruf_acq
                    if eja_acq in dicari_csq:
                        ada_csq.add(eja_acq)
                    if eja_acq in bernilai_csq:
                        break
                i_acq += 1
                continue
            i_acq += 1
        db_csq = "--db" in ada_csq
        query_csq = "--query" in ada_csq
        insert_csq = ("--insert" in ada_csq
                      or (query_csq and not db_csq))
        if (("--dialect" in ada_csq
                or "-i" in ada_csq)
                and (db_csq or query_csq)):
            return False
        if ("--insert" in ada_csq
                and not (db_csq or query_csq)):
            return False
        if (ada_csq & {"--no-create",
                       "--create-if-not-exists",
                       "--overwrite",
                       "--before-insert",
                       "--after-insert",
                       "--chunk-size"}
                and not insert_csq):
            return False
        if ("--overwrite" in ada_csq
                and "--no-create" in ada_csq):
            return False
        if ("--no-create" in ada_csq
                and "--create-if-not-exists"
                in ada_csq):
            return False
        toks_csq = _saring_csvkit(toks, awal, "csvsql")
        if toks_csq is None:
            return False
        ops_csq = _operand_csvkit(
            toks_csq, awal, bernilai_csq,
            bool_cabang={"--create-if-not-exists",
                         "--insert",
                         "--no-constraints",
                         "--no-create",
                         "--no-inference",
                         "--overwrite"},
            pendek_bool={"-I"})
        if "\x00gantung" in ops_csq:
            return False
        return not ops_csq or "-" in ops_csq
    # Audit 1854: derdump - sumber masukannya opsi
    # -i (bawaan stdin); -i dalam bentuk apa pun
    # berarti berkas/galat, bukan stdin. -o berkas
    # keluaran bernilai.
    if perintah == "derdump":
        for t_e in mentah:
            if t_e == "--":
                break
            if (t_e in ("-i", "--input")
                    or t_e.startswith("--input=")
                    or (t_e.startswith("-i")
                        and not t_e.startswith("--"))):
                return False
        ops_e = _operand_sesudah_opsi(toks, awal, {"-o"})
        if "\x00gantung" in ops_e:
            return False
        return not ops_e
    # Audit 1829: dasel - sumber berkasnya hanya opsi
    # -f/--file; tanpa itu ia membaca stdin dan
    # operandnya selektor (dokumentasi dasel v2).
    if perintah == "dasel":
        i_d = 0
        while i_d < len(mentah):
            t_d = mentah[i_d]
            if t_d == "--":
                break
            nilai_d = None
            if t_d in ("-f", "--file"):
                nilai_d = (mentah[i_d + 1]
                           if i_d + 1 < len(mentah) else "")
            elif t_d.startswith("--file="):
                nilai_d = t_d.split("=", 1)[1]
            elif (t_d.startswith("-f")
                    and not t_d.startswith("--")):
                # Audit 1857: bentuk -f=nilai pflag.
                nilai_d = t_d[2:]
                if nilai_d.startswith("="):
                    nilai_d = nilai_d[1:]
            if nilai_d is not None:
                # Audit 1850: nilai harfiah "stdin" pada
                # -f adalah cara dasel menyebut stdin.
                return nilai_d == "stdin"
            i_d += 1
        return True
    # Audit 1830: mlr [bendera] KATA-KERJA [opsi kata
    # kerja] [berkas]... - tanpa nama berkas membaca
    # stdin (manual mlr); tanpa kata kerja bawaannya
    # cat. Token persis sesudah token berawalan '-'
    # dianggap nilai opsi kata kerja, bukan berkas.
    if perintah == "mlr":
        if mentah and mentah[0] == "-n":
            return False
        i_m = 0
        kata_kerja = False
        kata = ""
        ekspresi_ditelan = False
        while i_m < len(mentah):
            t_m = mentah[i_m]
            if t_m == "--":
                sisa_m = mentah[i_m + 1:]
                return not sisa_m or "-" in sisa_m
            if not kata_kerja:
                if t_m == "then":
                    i_m += 1
                    continue
                if t_m == "-n":
                    return False
                if t_m.startswith("-") and t_m != "-":
                    # Audit 1848: --from/--mfrom menamai
                    # berkas masukan - stdin tak dibaca
                    # kecuali nilainya "-".
                    if t_m in ("--from", "--mfrom",
                               "--files"):
                        if i_m + 1 >= len(mentah):
                            return False
                        if mentah[i_m + 1] != "-":
                            return False
                        i_m += 2
                        continue
                    if t_m.startswith(("--from=", "--mfrom=",
                                       "--files=")):
                        if t_m.split("=", 1)[1] != "-":
                            return False
                        i_m += 1
                        continue
                    # Audit 1849: opsi global bernilai
                    # lain nilainya ditelan, bukan kata
                    # kerja/berkas.
                    i_m += 2 if t_m in (
                        "--ofmt", "--ofmte", "--ofmtf",
                        "--ofmtg", "--seed", "--tz", "--load",
                        "--profile", "-P",
                        "--nr-progress-mod", "--rs",
                        "--irs", "--ors", "--fs", "--ifs",
                        "--ofs", "--ps", "--ips", "--ops",
                        "--ifs-regex", "--ips-regex") else 1
                    continue
                if "." in t_m or "/" in t_m:
                    return False
                kata_kerja = True
                kata = t_m
                i_m += 1
                continue
            if t_m == "-":
                return True
            if t_m.startswith("-"):
                i_m += 1
                continue
            if mentah[i_m - 1].startswith("-"):
                i_m += 1
                continue
            if t_m == "then":
                if i_m + 1 >= len(mentah):
                    return False
                if (mentah[i_m + 1].startswith("-")
                        or "." in mentah[i_m + 1]
                        or "/" in mentah[i_m + 1]):
                    return False
                kata = mentah[i_m + 1]
                ekspresi_ditelan = False
                i_m += 2
                continue
            if (kata in ("put", "filter")
                    and not ekspresi_ditelan):
                ekspresi_ditelan = True
                i_m += 1
                continue
            return False
        return True
    # Audit 1831: sd CARI GANTI [BERKAS]... - dua
    # operand pertamanya pola dan pengganti; tepat
    # dua operand berarti filter stdin (README sd).
    if perintah == "sd":
        ops_s = _operand_sesudah_opsi(
            toks, awal, {"-f", "--flags", "-n"})
        if "\x00gantung" in ops_s:
            return False
        return len(ops_s) == 2
    # Audit 1832: choose - satu-satunya sumber berkas
    # opsi -i/--input; operandnya pilihan bidang dan
    # bawaannya membaca stdin (manual choose).
    if perintah == "choose":
        for t_c in mentah:
            if (t_c in ("-i", "--input")
                    or t_c.startswith("--input=")
                    or (t_c.startswith("-i")
                        and not t_c.startswith("--"))):
                return False
        return True
    # Audit 1836: aria2c -i - membaca daftar URI dari
    # stdin (pola cabang wget Audit 1068 yang sama).
    if perintah == "aria2c":
        for i, t in enumerate(mentah):
            if t in ("-i", "--input-file"):
                if i + 1 < len(mentah) and mentah[i + 1] == "-":
                    return True
            if t in ("--input-file=-", "-i-"):
                return True
        return False
    # Audit 1837: envsubst menyalin stdin ke stdout
    # dengan penggantian variabel; operandnya format,
    # bukan berkas - kecuali --variables yang
    # mengabaikan stdin (manual GNU gettext).
    if perintah == "envsubst":
        return "--variables" not in mentah
    # Audit 1838: write PENGGUNA [TTY] - baris yang
    # disalin ke terminal penerima dibaca dari stdin
    # sampai EOF; tanpa pengguna ia galat (write(1)).
    if perintah == "write":
        ops_w = _operand_sesudah_opsi(toks, awal, set())
        if "\x00gantung" in ops_w:
            return False
        return len(ops_w) >= 1
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
                    for segmen_sc in pecah_segmen_shell(
                            mentah[k_sc + 1]):
                        if _menguras_stdin(segmen_sc):
                            return True
                return False
            if t_sc.startswith("-c") and len(t_sc) > 2:
                for segmen_sc in pecah_segmen_shell(t_sc[2:]):
                    if _menguras_stdin(segmen_sc):
                        return True
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
        pipe_sr = False
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
                if nama_sr == "--pipe":
                    pipe_sr = True
                # Audit 1876: --scope/--pipe bendera boolean -
                # bentuk berargumen (--pipe=x) ditolak
                # systemd-run, tak ada yang berjalan.
                if (nama_sr in ("--scope", "--pipe")
                        and "=" in t_sr):
                    return False
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
        # Audit 1874: --pipe juga mewarisi stdio pemanggil
        # seperti --scope; penolakan ala --scope tidak
        # berlaku untuk opsi layanan --service-type dan
        # --remain-after-exit, tetapi pemicu timer/jalur/
        # soket tetap bukan pewaris langsung.
        if anak_sr is None:
            return False
        # Audit 1876: --pipe dan --scope tak bisa digabung
        # (systemd-run menolak kombinasi itu).
        if scope_sr and pipe_sr:
            return False
        if scope_sr:
            if ditolak_sr:
                return False
        elif pipe_sr:
            if any(t_p in timer_sr or t_p.split("=", 1)[0]
                   in timer_sr for t_p in mentah):
                return False
        else:
            return False
        if anak_sr >= len(mentah):
            return False
        segmen_sr = pecah_segmen_shell(
            " ".join(mentah[anak_sr:]))
        for segmen_sr_satu in segmen_sr:
            if _menguras_stdin(segmen_sr_satu):
                return True
        return False
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
            # Audit 2033: interaksi stdin ffmpeg
            # AKTIF secara bawaan (ffmpeg(1) -stdin:
            # "On by default unless standard input
            # is used as an input") - tanpa -nostdin
            # ia membaca perintah dari stdin.
            return "-nostdin" not in mentah
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
        # Audit 1931: bentuk badan dari stdin gh -
        # api --input -, field -F berisi @-, dan berkas
        # badan/catatan berupa '-' pada pr/issue/release.
        sisa_gh = mentah[k_gh:]
        # Audit 1949: saklar stdin hanya ada pada
        # api / pr create / issue create /
        # release create - di luar konteks itu
        # kemunculannya (mis. sebagai teks bebas)
        # bukan saklar.
        konteks_gh = (
            sisa_gh[:1] == ["api"]
            or sisa_gh[:2] in (["pr", "create"],
                               ["issue", "create"],
                               ["release", "create"]))
        for k2_gh, t2_gh in enumerate(sisa_gh):
            if not konteks_gh:
                break
            if t2_gh in ("--input", "--body-file",
                         "--notes-file"):
                if (k2_gh + 1 < len(sisa_gh)
                        and sisa_gh[k2_gh + 1] == "-"):
                    return True
            elif t2_gh in ("--input=-", "--body-file=-",
                           "--notes-file=-"):
                return True
            elif (t2_gh == "-F" and k2_gh + 1 < len(sisa_gh)
                    and "@-" in sisa_gh[k2_gh + 1]):
                return True
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


_CHARSET_ICONV_66 = frozenset({
        "1026", "1046", "1047", "1064611993", "1064611993UCS4", "437", "500", "500V1", "850", "851", "852", "855", "856", "857", "858", "860", "861", "862", "863", "864", "865", "866", "866NAV", "869", "874", "88591", "88592", "88593", "88594", "88595", "88596", "88597", "88598", "88599", "904", "ANSIX3110", "ANSIX31101983", "ANSIX34", "ANSIX341968", "ANSIX341986", "ARABIC", "ARABIC7", "ARMSCII8", "ASCII", "ASMO449", "ASMO708", "BALTIC", "BIG5", "BIG5HKSCS", "BIGFIVE", "BRF", "BS4730", "CA", "CN", "CNBIG5", "CNGB", "CP037", "CP038", "CP10007", "CP1004", "CP1008", "CP1025", "CP1026", "CP1046", "CP1047", "CP1070", "CP1079", "CP1081", "CP1084", "CP1089", "CP1097", "CP1112", "CP1122", "CP1123", "CP1124", "CP1125", "CP1129", "CP1130", "CP1132", "CP1133", "CP1137", "CP1140", "CP1141", "CP1142", "CP1143", "CP1144", "CP1145", "CP1146", "CP1147", "CP1148", "CP1149", "CP1153", "CP1154", "CP1155", "CP1156", "CP1157", "CP1158", "CP1160", "CP1161", "CP1162", "CP1163", "CP1164", "CP1166", "CP1167", "CP1250", "CP1251", "CP1252", "CP1253", "CP1254", "CP1255", "CP1256", "CP1257", "CP1258", "CP12712", "CP1282", "CP1361", "CP1364", "CP1371", "CP1388", "CP1390", "CP1399", "CP16804", "CP273", "CP274", "CP275", "CP278", "CP280", "CP281", "CP282", "CP284", "CP285", "CP290", "CP297", "CP367", "CP420", "CP423", "CP424", "CP437", "CP4517", "CP4899", "CP4909", "CP4971", "CP500", "CP5347", "CP737", "CP770", "CP771", "CP772", "CP773", "CP774", "CP775", "CP803", "CP813", "CP819", "CP850", "CP851", "CP852", "CP855", "CP856", "CP857", "CP858", "CP860", "CP861", "CP862", "CP863", "CP864", "CP865", "CP866", "CP866NAV", "CP868", "CP869", "CP870", "CP871", "CP874", "CP875", "CP880", "CP891", "CP901", "CP902", "CP903", "CP9030", "CP904", "CP905", "CP9066", "CP912", "CP915", "CP916", "CP918", "CP920", "CP921", "CP922", "CP930", "CP932", "CP933", "CP935", "CP936", "CP937", "CP939", "CP9448", "CP949", "CP950", "CPAR", "CPGR", "CPHU", "CPIBM861", "CSA71", "CSA72", "CSASCII", "CSAT500", "CSAT5001983", "CSAZ243419851", "CSAZ243419852", "CSDECMCS", "CSEBCDICATDE", "CSEBCDICATDEA", "CSEBCDICCAFR", "CSEBCDICDKNO", "CSEBCDICDKNOA", "CSEBCDICES", "CSEBCDICESA", "CSEBCDICESS", "CSEBCDICFISE", "CSEBCDICFISEA", "CSEBCDICFR", "CSEBCDICIT", "CSEBCDICPT", "CSEBCDICUK", "CSEBCDICUS", "CSEUCKR", "CSEUCPKDFMTJAPANESE", "CSGB2312", "CSHPROMAN8", "CSIBM037", "CSIBM038", "CSIBM1008", "CSIBM1025", "CSIBM1026", "CSIBM1097", "CSIBM1112", "CSIBM1122", "CSIBM1123", "CSIBM1124", "CSIBM1129", "CSIBM1130", "CSIBM1132", "CSIBM1133", "CSIBM1137", "CSIBM1140", "CSIBM1141", "CSIBM1142", "CSIBM1143", "CSIBM1144", "CSIBM1145", "CSIBM1146", "CSIBM1147", "CSIBM1148", "CSIBM1149", "CSIBM1153", "CSIBM1154", "CSIBM1155", "CSIBM1156", "CSIBM1157", "CSIBM1158", "CSIBM1160", "CSIBM1161", "CSIBM11621162", "CSIBM1163", "CSIBM1164", "CSIBM1166", "CSIBM1167", "CSIBM12712", "CSIBM1364", "CSIBM1371", "CSIBM1388", "CSIBM1390", "CSIBM1399", "CSIBM16804", "CSIBM273", "CSIBM274", "CSIBM275", "CSIBM277", "CSIBM278", "CSIBM280", "CSIBM281", "CSIBM284", "CSIBM285", "CSIBM290", "CSIBM297", "CSIBM420", "CSIBM423", "CSIBM424", "CSIBM4517", "CSIBM4899", "CSIBM4909", "CSIBM4971", "CSIBM500", "CSIBM5347", "CSIBM803", "CSIBM851", "CSIBM855", "CSIBM856", "CSIBM857", "CSIBM860", "CSIBM863", "CSIBM864", "CSIBM865", "CSIBM866", "CSIBM868", "CSIBM869", "CSIBM870", "CSIBM871", "CSIBM880", "CSIBM891", "CSIBM901", "CSIBM902", "CSIBM903", "CSIBM9030", "CSIBM904", "CSIBM905", "CSIBM9066", "CSIBM918", "CSIBM921", "CSIBM922", "CSIBM930", "CSIBM932", "CSIBM933", "CSIBM935", "CSIBM937", "CSIBM939", "CSIBM943", "CSIBM9448", "CSISO10367BOX", "CSISO103T618BIT", "CSISO10SWEDISH", "CSISO111ECMACYRILLIC", "CSISO11SWEDISHFORNAMES", "CSISO121CANADIAN1", "CSISO122CANADIAN2", "CSISO139CSN369103", "CSISO141JUSIB1002", "CSISO143IECP271", "CSISO14JISC6220RO", "CSISO150", "CSISO150GREEKCCITT", "CSISO151CUBA", "CSISO153GOST1976874", "CSISO15ITALIAN", "CSISO16PORTUGESE", "CSISO17SPANISH", "CSISO18GREEK7OLD", "CSISO19LATINGREEK", "CSISO2022CN", "CSISO2022JP", "CSISO2022JP2", "CSISO2022KR", "CSISO2033", "CSISO21GERMAN", "CSISO25FRENCH", "CSISO27LATINGREEK1", "CSISO49INIS", "CSISO4UNITEDKINGDOM", "CSISO50INIS8", "CSISO51INISCYRILLIC", "CSISO5427CYRILLIC", "CSISO5427CYRILLIC1981", "CSISO5428GREEK", "CSISO58GB1988", "CSISO60DANISHNORWEGIAN", "CSISO60NORWEGIAN1", "CSISO61NORWEGIAN2", "CSISO646DANISH", "CSISO69FRENCH", "CSISO84PORTUGUESE2", "CSISO85SPANISH2", "CSISO86HUNGARIAN", "CSISO88GREEK7", "CSISO89ASMO449", "CSISO90", "CSISO92JISC62991984B", "CSISO99NAPLPS", "CSISOLATIN1", "CSISOLATIN2", "CSISOLATIN3", "CSISOLATIN4", "CSISOLATIN5", "CSISOLATIN6", "CSISOLATINARABIC", "CSISOLATINCYRILLIC", "CSISOLATINGREEK", "CSISOLATINHEBREW", "CSKOI8R", "CSKSC5636", "CSMACINTOSH", "CSN369103", "CSNATSDANO", "CSNATSSEFI", "CSPC775BALTIC", "CSPC850MULTILINGUAL", "CSPC858MULTILINGUAL", "CSPC862LATINHEBREW", "CSPC8CODEPAGE437", "CSPCP852", "CSSHIFTJIS", "CSUCS4", "CSUNICODE", "CSWINDOWS31J", "CUBA", "CWI", "CWI2", "CYRILLIC", "DE", "DEC", "DECMCS", "DIN66003", "DK", "DS2089", "E13B", "EBCDICATDE", "EBCDICATDEA", "EBCDICBE", "EBCDICBR", "EBCDICCAFR", "EBCDICCPAR1", "EBCDICCPAR2", "EBCDICCPBE", "EBCDICCPCA", "EBCDICCPCH", "EBCDICCPDK", "EBCDICCPES", "EBCDICCPFI", "EBCDICCPFR", "EBCDICCPGB", "EBCDICCPGR", "EBCDICCPHE", "EBCDICCPIS", "EBCDICCPIT", "EBCDICCPNL", "EBCDICCPNO", "EBCDICCPROECE", "EBCDICCPSE", "EBCDICCPTR", "EBCDICCPUS", "EBCDICCPWT", "EBCDICCPYU", "EBCDICCYRILLIC", "EBCDICDKNO", "EBCDICDKNOA", "EBCDICES", "EBCDICESA", "EBCDICESS", "EBCDICFISE", "EBCDICFISEA", "EBCDICFR", "EBCDICGREEK", "EBCDICINT", "EBCDICINT1", "EBCDICISFRISS", "EBCDICIT", "EBCDICJPE", "EBCDICJPKANA", "EBCDICPT", "EBCDICUK", "EBCDICUS", "ECMA114", "ECMA118", "ECMA128", "ECMACYRILLIC", "ELOT928", "ES", "ES2", "EUCCN", "EUCJISX0213", "EUCJP", "EUCJPMS", "EUCJPOPEN", "EUCJPWIN", "EUCKR", "EUCTW", "FI", "FR", "GB", "GB13000", "GB18030", "GB198880", "GB2312", "GBK", "GEORGIANACADEMY", "GEORGIANPS", "GOST19768", "GOST1976874", "GREEK", "GREEK7", "GREEK7OLD", "GREEK8", "GREEKCCITT", "HEBREW", "HPGREEK8", "HPROMAN8", "HPROMAN9", "HPTHAI8", "HPTURKISH8", "HU", "IBM037", "IBM038", "IBM1004", "IBM1008", "IBM1025", "IBM1026", "IBM1046", "IBM1047", "IBM1089", "IBM1097", "IBM1112", "IBM1122", "IBM1123", "IBM1124", "IBM1129", "IBM1130", "IBM1132", "IBM1133", "IBM1137", "IBM1140", "IBM1141", "IBM1142", "IBM1143", "IBM1144", "IBM1145", "IBM1146", "IBM1147", "IBM1148", "IBM1149", "IBM1153", "IBM1154", "IBM1155", "IBM1156", "IBM1157", "IBM1158", "IBM1160", "IBM1161", "IBM1162", "IBM1163", "IBM1164", "IBM1166", "IBM1167", "IBM12712", "IBM1364", "IBM1371", "IBM1388", "IBM1390", "IBM1399", "IBM16804", "IBM256", "IBM273", "IBM274", "IBM275", "IBM277", "IBM278", "IBM280", "IBM281", "IBM284", "IBM285", "IBM290", "IBM297", "IBM367", "IBM420", "IBM423", "IBM424", "IBM437", "IBM4517", "IBM4899", "IBM4909", "IBM4971", "IBM500", "IBM5347", "IBM775", "IBM803", "IBM813", "IBM819", "IBM848", "IBM850", "IBM851", "IBM852", "IBM855", "IBM856", "IBM857", "IBM858", "IBM860", "IBM861", "IBM862", "IBM863", "IBM864", "IBM865", "IBM866", "IBM866NAV", "IBM868", "IBM869", "IBM870", "IBM871", "IBM874", "IBM875", "IBM880", "IBM891", "IBM901", "IBM902", "IBM903", "IBM9030", "IBM904", "IBM905", "IBM9066", "IBM912", "IBM915", "IBM916", "IBM918", "IBM920", "IBM921", "IBM922", "IBM930", "IBM932", "IBM933", "IBM935", "IBM937", "IBM939", "IBM943", "IBM9448", "IECP271", "INIS", "INIS8", "INISCYRILLIC", "ISIRI3342", "ISO10367BOX", "ISO10646", "ISO10646UCS2", "ISO10646UCS4", "ISO10646UTF8", "ISO115481", "ISO2022CN", "ISO2022CNEXT", "ISO2022JP", "ISO2022JP2", "ISO2022JP3", "ISO2022KR", "ISO2033", "ISO20331983", "ISO5427", "ISO54271981", "ISO5427EXT", "ISO5428", "ISO54281980", "ISO646CA", "ISO646CA2", "ISO646CN", "ISO646CU", "ISO646DE", "ISO646DK", "ISO646ES", "ISO646ES2", "ISO646FI", "ISO646FR", "ISO646FR1", "ISO646GB", "ISO646HU", "ISO646IRV1991", "ISO646IT", "ISO646JP", "ISO646JPOCRB", "ISO646KR", "ISO646NO", "ISO646NO2", "ISO646PT", "ISO646PT2", "ISO646SE", "ISO646SE2", "ISO646US", "ISO646YU", "ISO6937", "ISO69371992", "ISO69372", "ISO693721983", "ISO88591", "ISO885910", "ISO8859101992", "ISO885911", "ISO885911987", "ISO885913", "ISO885914", "ISO8859141998", "ISO885915", "ISO8859151998", "ISO885916", "ISO8859162001", "ISO88592", "ISO885921987", "ISO88593", "ISO885931988", "ISO88594", "ISO885941988", "ISO88595", "ISO885951988", "ISO88596", "ISO885961987", "ISO88597", "ISO885971987", "ISO885972003", "ISO88598", "ISO885981988", "ISO88599", "ISO885991989", "ISO88599E", "ISO9036", "ISOCELTIC", "ISOIR10", "ISOIR100", "ISOIR101", "ISOIR103", "ISOIR109", "ISOIR11", "ISOIR110", "ISOIR111", "ISOIR121", "ISOIR122", "ISOIR126", "ISOIR127", "ISOIR138", "ISOIR139", "ISOIR14", "ISOIR141", "ISOIR143", "ISOIR144", "ISOIR148", "ISOIR15", "ISOIR150", "ISOIR151", "ISOIR153", "ISOIR155", "ISOIR156", "ISOIR157", "ISOIR16", "ISOIR166", "ISOIR17", "ISOIR179", "ISOIR18", "ISOIR19", "ISOIR193", "ISOIR197", "ISOIR199", "ISOIR203", "ISOIR209", "ISOIR21", "ISOIR226", "ISOIR25", "ISOIR27", "ISOIR37", "ISOIR4", "ISOIR49", "ISOIR50", "ISOIR51", "ISOIR54", "ISOIR55", "ISOIR57", "ISOIR6", "ISOIR60", "ISOIR61", "ISOIR69", "ISOIR81", "ISOIR84", "ISOIR85", "ISOIR86", "ISOIR88", "ISOIR89", "ISOIR90", "ISOIR91", "ISOIR92", "ISOIR98", "ISOIR99", "ISOTR115481", "IT", "JISC62201969RO", "JISC62291984B", "JOHAB", "JP", "JPOCRB", "JS", "JUSIB1002", "KOI7", "KOI8", "KOI8R", "KOI8RU", "KOI8T", "KOI8U", "KSC5636", "L1", "L10", "L2", "L3", "L4", "L5", "L6", "L7", "L8", "LATIN1", "LATIN10", "LATIN2", "LATIN3", "LATIN4", "LATIN5", "LATIN6", "LATIN7", "LATIN8", "LATIN9", "LATINGREEK", "LATINGREEK1", "MAC", "MACCENTRALEUROPE", "MACCYRILLIC", "MACINTOSH", "MACIS", "MACSAMI", "MACUK", "MACUKRAINIAN", "MIK", "MS932", "MS936", "MSANSI", "MSARAB", "MSCP1361", "MSCP949", "MSCYRL", "MSEE", "MSGREEK", "MSHEBR", "MSKANJI", "MSMACCYRILLIC", "MSTURK", "MSZ77953", "NAPLPS", "NATSDANO", "NATSSEFI", "NCNC0010", "NCNC001081", "NFZ62010", "NFZ620101973", "NO", "NO2", "NS45511", "NS45512", "OS2LATIN1", "OSF00010001", "OSF00010002", "OSF00010003", "OSF00010004", "OSF00010005", "OSF00010006", "OSF00010007", "OSF00010008", "OSF00010009", "OSF0001000A", "OSF00010020", "OSF00010100", "OSF00010101", "OSF00010102", "OSF00010104", "OSF00010105", "OSF00010106", "OSF00030010", "OSF0004000A", "OSF0005000A", "OSF05010001", "OSF10010001", "OSF10010004", "OSF10010006", "OSF10020025", "OSF10020111", "OSF10020115", "OSF10020116", "OSF10020118", "OSF1002011C", "OSF1002011D", "OSF10020122", "OSF10020129", "OSF100201A4", "OSF100201A8", "OSF100201B5", "OSF100201F4", "OSF10020352", "OSF10020354", "OSF10020357", "OSF10020359", "OSF1002035D", "OSF1002035E", "OSF1002035F", "OSF10020360", "OSF10020364", "OSF10020365", "OSF10020366", "OSF10020367", "OSF1002036B", "OSF10020370", "OSF1002037B", "OSF10020387", "OSF10020388", "OSF10020396", "OSF100203B5", "OSF10020402", "OSF10020417", "PT", "PT154", "PT2", "R8", "R9", "RK1048", "ROMAN8", "ROMAN9", "RUSCII", "SE", "SE2", "SEN850200B", "SEN850200C", "SHIFTJIS", "SHIFTJISX0213", "SJIS", "SJISOPEN", "SJISWIN", "SS636127", "STRK10482002", "STSEV35888", "T61", "T618BIT", "TCVN", "TCVN5712", "TCVN57121", "TCVN571211993", "THAI8", "TIS620", "TIS6200", "TIS62025291", "TIS62025330", "TS5881", "TSCII", "TURKISH8", "UCS2", "UCS2BE", "UCS2LE", "UCS4", "UCS4BE", "UCS4LE", "UHC", "UJIS", "UK", "UNICODE", "UNICODEBIG", "UNICODELITTLE", "US", "USASCII", "UTF16", "UTF16BE", "UTF16LE", "UTF32", "UTF32BE", "UTF32LE", "UTF7", "UTF7IMAP", "UTF8", "VISCII", "WCHART", "WINBALTRIM", "WINDOWS1250", "WINDOWS1251", "WINDOWS1252", "WINDOWS1253", "WINDOWS1254", "WINDOWS1255", "WINDOWS1256", "WINDOWS1257", "WINDOWS1258", "WINDOWS31J", "WINDOWS874", "WINDOWS936", "WINSAMI2", "WS2", "YU"})


def _tipe_nilai_sah_66(jenis, nilai):
    """Audit 2666: validasi nilai penerima bertipe untuk
    pembaca generik 2645 - angka/enum/charset yang tak
    sah membunuh alat saat urai, sebelum stdin dibaca
    (preseden model 2619/2626). Daftar charset =
    keluaran iconv -l glibc setempat, dikanonkan
    (huruf besar, alfanumerik saja) seperti cara
    iconv mencocokkan nama."""
    v = nilai.strip("\"'")
    if jenis == "angka0":
        return v.isdigit() and len(v) <= 18
    if jenis == "angka1":
        return v.isdigit() and len(v) <= 18 \
            and int(v) > 0
    if jenis == "ukuran1":
        # Audit 2673: sufiks ukuran xstrtol
        # ditulis persis dari matriks biner
        # coreutils 9.4 - keluarga huruf besar
        # boleh +B/+iB, k kecil setara K
        # (k/kB/kiB sah), b sah; c/w dan B
        # tunggal DITOLAK split (pola lama
        # menolak KiB/kB yang sah dan
        # menerima c/w yang asing).
        # Audit 2696: m kecil juga sah penuh
        # (m/mB/miB - keanehan xstrtol: huruf
        # kecil yang sah hanya k dan m).
        m66 = re.fullmatch(
            r"(\d{1,18})([KMGTPEZY](B|iB)?|[km](B|iB)?|b)?", v)
        return m66 is not None and int(m66.group(1)) > 0
    if jenis == "nomor-split":
        m66 = re.fullmatch(r"(?:[lrk]/)?(\d{1,18})", v)
        return m66 is not None and int(m66.group(1)) > 0
    if jenis == "odnum1":
        # Audit 2677: varian odnum yang menolak
        # nol - khusus lebar od (--width/-w):
        # biner tewas untuk lebar 0, sementara
        # penerima odnum lain (-N/-j/-S) sah
        # bernilai 0.
        # Audit 2697: parser lebar od ternyata
        # hanya menerima digit desimal polos
        # (tanda + boleh di depan) - sufiks
        # dan bentuk heksa yang lolos odnum
        # membunuhnya ("invalid suffix in
        # --width argument", biner 9.4).
        if re.fullmatch(r"\+?\d{1,18}", v) is None:
            return False
        return int(v) > 0
    if jenis == "odnum":
        # Audit 2673: sufiks od sama dengan
        # matriks split di atas (biner od
        # menolak c/w/B tunggal, menerima
        # KiB/kB/kiB/k/b).
        return re.fullmatch(
            r"(0[xX][0-9a-fA-F]{1,16}|\d{1,18})"
            r"([KMGTPEZY](B|iB)?|[km](B|iB)?|b)?", v) is not None
    if jenis == "format-od":
        # Audit 2678: tata bahasa string
        # format od -t/--format, dibuktikan
        # per tipe ke biner coreutils 9.4:
        # a/c tanpa ukuran; d/o/u/x ukuran
        # 1/2/4/8 atau C/S/I/L; f ukuran
        # 4/8/16 atau F/D/L; beberapa
        # spesifikasi boleh berurutan.
        return re.fullmatch(
            r"(?:[ac]|[doux](?:[1248]|[CSIL])?"
            r"|f(?:4|8|16|[FDL])?)+", v) is not None
    if jenis == "enum-radix":
        # Audit 2678: radix strings -t hanya
        # satu aksara dari o/d/x (biner
        # menolak h dan bentuk ganda).
        return v in ("o", "d", "x")
    if jenis == "enum-encoding":
        # Audit 2678: encoding strings -e
        # hanya satu aksara dari s/S/b/l/B/L
        # (biner menolak x).
        return v in ("s", "S", "b", "l", "B", "L")
    if jenis == "enum-cksum":
        return v in {"bsd", "sysv", "crc", "crc32b",
                     "md5", "sha1", "sha224", "sha256",
                     "sha384", "sha512", "blake2b",
                     "sm3"}
    if jenis == "enum-tee":
        return v in {"warn", "warn-nopipe", "exit",
                     "exit-nopipe"}
    if jenis == "charset":
        # Audit 2674: iconv menerima sufiks
        # //IGNORE dan //TRANSLIT menempel
        # pada nama sah (bahkan sufiks asing
        # diabaikan glibc setempat) - yang
        # menentukan sah/tidak hanya nama
        # dasar sebelum "//" pertama.
        # Audit 2698: nama dasar KOSONG
        # (nilai diawali "//") juga sah -
        # glibc membacanya sebagai charset
        # bawaan + sufiks dan perintah
        # menguras; klaim 2674 bahwa dasar
        # kosong tetap tak sah terbantahkan
        # biner setempat.
        if v.startswith("//"):
            return True
        dasar = v.split("//", 1)[0]
        return re.sub(r"[^A-Z0-9]", "", dasar.upper()) \
            in _CHARSET_ICONV_66
    return True


def _dd_operand_sah(tok):
    """Audit 2665: tata bahasa operand dd kunci=nilai -
    kunci asing, nilai angka tak sah, dan anggota enum
    asing membunuh dd sebelum membaca ("unrecognized
    operand"/"invalid number"/"invalid conversion",
    terbukti coreutils 9.4). if/of berkas bebas;
    iflag/oflag berbagi satu tabel bendera di biner
    (iflag=append diterima)."""
    bersih = tok.strip("\"'")
    kunci, _, nilai = bersih.partition("=")
    if kunci in ("if", "of"):
        return True
    if kunci in ("bs", "ibs", "obs", "cbs", "count",
                 "skip", "seek"):
        # Audit 2673: matriks sufiks dd lebih
        # longgar dari od/split - biner dd
        # juga menerima B tunggal dan c/w
        # (xstrtol penuh); KiB/kB/kiB/k kini
        # ikut sah di kedua faktor perkalian.
        cocok = re.fullmatch(
            r"\d+([KMGTPEZY](B|iB)?|k(B|iB)?|B|b|c|w)?"
            r"(x\d+([KMGTPEZY](B|iB)?|k(B|iB)?|B|b|c|w)?)*",
            nilai)
        if cocok is None:
            return False
        # Audit 2676: kunci ukuran blok menolak
        # total nol - biner mati "invalid
        # number: '0'" untuk bs/ibs/obs/cbs=0
        # dan untuk produk berfaktor nol
        # (0x512, 1x0); skip/seek/count boleh
        # nol. Asimetri yang sama dengan
        # doktrin angka1 di 2666.
        if kunci in ("bs", "ibs", "obs", "cbs") and any(
                int(re.match(r"\d+", faktor).group()) == 0
                for faktor in nilai.split("x")):
            return False
        return True
    if kunci == "conv":
        sah = {"ascii", "ebcdic", "ibm", "block",
               "unblock", "lcase", "ucase", "sparse",
               "swab", "sync", "excl", "nocreat",
               "notrunc", "noerror", "fdatasync",
               "fsync"}
        return nilai != "" and all(
            x in sah for x in nilai.split(","))
    if kunci in ("iflag", "oflag"):
        sah = {"append", "count_bytes", "direct",
               "directory", "dsync", "fullblock",
               "noatime", "nocache", "noctty",
               "nofollow", "nonblock", "seek_bytes",
               "skip_bytes", "sync"}
        return nilai != "" and all(
            x in sah for x in nilai.split(","))
    if kunci == "status":
        return nilai in ("none", "noxfer", "progress")
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
    # Audit 2671: setarch hanya menjalankan shell
    # bila ada token pengikut (arsitektur/bendera);
    # telanjang tanpa apa pun ia mati.
    setarch_punya_pengikut = False
    awal_keluarga_setarch = -1
    # Audit 2703: runuser tanpa perintah
    # menjatuhkan shell login pembaca stdin
    # kecuali bila pengguna disebut.
    runuser_punya_user = False
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
                "setpriv", "prlimit", "doas", "watch",
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
            if t0 == "setarch":
                # Audit 2671: ingat ada/tidaknya
                # token sesudah kata setarch ini.
                setarch_punya_pengikut = (
                    len(toks) > awal + 1)
            if t0 == "runuser":
                # Audit 2703: pindai wilayah opsi
                # runuser untuk penyebutan
                # pengguna (-u/--user, segala
                # bentuk) - satu-satunya bentuk
                # tanpa perintah yang mati.
                j_ru = awal + 1
                while j_ru < len(toks):
                    t_ru = toks[j_ru].strip("\"'")
                    if t_ru == "--":
                        break
                    if not t_ru.startswith("-") \
                            or t_ru == "-":
                        break
                    if (t_ru == "--user"
                            or t_ru.startswith(
                                "--user=")):
                        runuser_punya_user = True
                        break
                    if t_ru.startswith("--"):
                        if ("=" not in t_ru
                                and t_ru in (
                                    "--group",
                                    "--supp-group",
                                    "--shell")):
                            j_ru += 1
                        j_ru += 1
                        continue
                    lompat_ru = False
                    for pos_ru, ch_ru in enumerate(
                            t_ru[1:]):
                        if ch_ru == "u":
                            runuser_punya_user = True
                            break
                        if ch_ru in "gGsc":
                            # Audit 2714: huruf
                            # bernilai di ujung
                            # gugus menelan token
                            # berikutnya sebagai
                            # nilai terpisah -
                            # lompati juga, agar
                            # -u sesudahnya tetap
                            # terlihat.
                            if (pos_ru
                                    == len(t_ru[1:])
                                    - 1):
                                lompat_ru = True
                            break
                    if runuser_punya_user:
                        break
                    if lompat_ru:
                        j_ru += 1
                    j_ru += 1
            if t0 in ("setarch", "linux32", "linux64",
                      "i386", "x86_64"):
                # Audit 2671: posisi lapisan
                # keluarga setarch terakhir, untuk
                # penjaga --help/--version di vonis
                # tanpa-anak.
                awal_keluarga_setarch = awal
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
            # Audit 2681: kebijakan chrt yang menuntut
            # prioritas 0 (-o/-b/-i/-d); bawaan dan
            # -f/-r menuntut 1..99.
            chrt_kebijakan_nol = False
            # Audit 2682: chroot hanya menjatuhkan
            # shell bila operand direktorinya
            # benar-benar tertelan; telanjang
            # mati "missing operand".
            chroot_punya_dir = False
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
            if t0 in ("unshare", "systemd-cat",
                      "xargs"):
                # Audit 2699: --help/--version
                # milik pembungkus sendiri
                # mencetak lalu keluar (anak pun
                # tak berjalan) - vonis tanpa-anak
                # 1121/1070 menelannya mentah.
                # Audit 2702: xargs sekelas -
                # bendera xargs_sendiri (971)
                # menelan --help/--version
                # miliknya dengan cara yang sama.
                # Pindai wilayah opsi pembungkus
                # ini; nilai pembawa dilompati.
                j_ih = awal
                while j_ih < len(toks):
                    t_ih = toks[j_ih].strip("\"'")
                    if t_ih == "--":
                        break
                    if not t_ih.startswith("-") \
                            or t_ih == "-":
                        break
                    if t_ih.startswith("--"):
                        # Audit 2710: singkatan
                        # getopt_long atas
                        # --help/--version juga
                        # mati - unik berarti
                        # informasional, ambigu
                        # berarti galat urai;
                        # dua-duanya tak
                        # menjalankan anak.
                        nama_ih = t_ih[2:].partition(
                            "=")[0]
                        if (nama_ih
                                and ("help".startswith(
                                        nama_ih)
                                     or "version".startswith(
                                        nama_ih))):
                            return False
                        if ("=" not in t_ih
                                and t_ih in bernilai):
                            j_ih += 1
                        j_ih += 1
                        continue
                    huruf_ih = t_ih[1:]
                    lompat_ih = False
                    for pos_ih, ch_ih in enumerate(
                            huruf_ih):
                        # Audit 2710: huruf h
                        # sebelum huruf bernilai
                        # pertama = bantuan
                        # (unshare/systemd-cat)
                        # atau opsi tak sah
                        # (xargs) - sama-sama
                        # mati sebelum anak.
                        if ch_ih == "h":
                            return False
                        if ("-" + ch_ih) in bernilai:
                            if (pos_ih
                                    == len(huruf_ih) - 1):
                                lompat_ih = True
                            break
                    if lompat_ih:
                        j_ih += 1
                    j_ih += 1
            if t0 == "sudo":
                # Audit 2701: --help/--version/-V
                # sudo mencetak lalu keluar dan
                # MENANG atas mode shell -s/-i -
                # walk dulu menetapkan sudo_shell
                # dari -s/-i lalu menelan --help
                # sebagai bendera biasa. Pindai
                # wilayah opsi sudo (nilai
                # pembawa dilompati; -V dinilai
                # sebagai huruf bendera sebelum
                # huruf bernilai pertama).
                j_sh = awal
                while j_sh < len(toks):
                    t_sh = toks[j_sh].strip("\"'")
                    if t_sh == "--":
                        break
                    if not t_sh.startswith("-") \
                            or t_sh == "-":
                        break
                    if t_sh in ("--help", "--version"):
                        return False
                    if t_sh.startswith("--"):
                        # Audit 2713: parser
                        # sudo bukan getopt_long
                        # - bentuk seperti
                        # --hel adalah opsi tak
                        # dikenal dan sudo mati
                        # seketika, bukan
                        # singkatan informasional.
                        nama_sh = t_sh[2:].partition(
                            "=")[0]
                        if (nama_sh
                                and ("help".startswith(
                                        nama_sh)
                                     or "version".startswith(
                                        nama_sh))):
                            return False
                        if ("=" not in t_sh
                                and t_sh in bernilai):
                            j_sh += 1
                        j_sh += 1
                        continue
                    huruf_sh = t_sh[1:]
                    lompat_sh = False
                    for pos_sh, ch_sh in enumerate(
                            huruf_sh):
                        if ch_sh == "V":
                            return False
                        if ("-" + ch_sh) in bernilai:
                            if (pos_sh
                                    == len(huruf_sh) - 1):
                                lompat_sh = True
                            break
                    if lompat_sh:
                        j_sh += 1
                    j_sh += 1
            if t0 == "stdbuf":
                # Audit 2680: stdbuf menuntut
                # minimal satu opsi penyangga
                # (-i/-o/-e); tanpa itu biner
                # mati rc=125 sebelum anak
                # berjalan, tetapi gerbang
                # menilai dari anaknya. Pindai
                # wilayah opsi stdbuf sendiri:
                # token tak berawalan strip =
                # anak tercapai.
                ada_penyangga = False
                j_sb = awal
                while j_sb < len(toks):
                    t_sb = toks[j_sb].strip("\"'")
                    if t_sb == "--":
                        break
                    if not t_sb.startswith("-") \
                            or t_sb == "-":
                        break
                    if t_sb.startswith("--"):
                        nama_sb, eq_sb, isi_sb = \
                            t_sb.partition("=")
                        if nama_sb in (
                                "--input", "--output",
                                "--error"):
                            # Audit 2695: nilai mode
                            # ikut divalidasi, bukan
                            # hanya kehadiran opsi.
                            ada_penyangga = True
                            if eq_sb:
                                if not _mode_stdbuf_sah(
                                        isi_sb):
                                    return False
                            elif j_sb + 1 < len(toks):
                                if not _mode_stdbuf_sah(
                                        toks[j_sb + 1].strip(
                                            "\"'")):
                                    return False
                                j_sb += 1
                        j_sb += 1
                        continue
                    # Audit 2695: gugus pendek -
                    # huruf i/o/e menelan sisa
                    # gugus sebagai modenya, atau
                    # token berikut bila di ujung
                    # (getopt menelan token strip
                    # sekalipun, mis. -i -o0).
                    huruf_sb = t_sb[1:]
                    for pos_sb, ch_sb in enumerate(
                            huruf_sb):
                        if ch_sb in "ioe":
                            ada_penyangga = True
                            sisa_sb = huruf_sb[
                                pos_sb + 1:]
                            if sisa_sb:
                                if not _mode_stdbuf_sah(
                                        sisa_sb):
                                    return False
                            elif j_sb + 1 < len(toks):
                                if not _mode_stdbuf_sah(
                                        toks[j_sb + 1].strip(
                                            "\"'")):
                                    return False
                                j_sb += 1
                            break
                    j_sb += 1
                if not ada_penyangga:
                    return False
            if t0 == "runuser":
                # Audit 2715: tata bahasa
                # runuser terbalik dari model
                # lama - operand pertama
                # adalah PENGGUNA, bukan
                # perintah. Tanpa -u,
                # perintah hanya datang
                # lewat -c; operand sesudah
                # pengguna adalah argumen
                # shell (berkas skrip) yang
                # tak membaca stdin bersama.
                # Bentuk -u: operand sesudah
                # nilai -u adalah perintah
                # yang di-exec langsung. -u
                # saling meniadakan dengan
                # -c/-s/-l/--session-command
                # (urutan bebas), dan
                # --help/--version berlaku
                # dari posisi mana pun.
                j_rx = awal
                u_rx = False
                c_rx = None
                scl_rx = False
                operand_rx = []
                while j_rx < len(toks):
                    t_rx = toks[j_rx].strip("\"'")
                    if t_rx == "--":
                        j_rx += 1
                        while j_rx < len(toks):
                            operand_rx.append(
                                toks[j_rx].strip(
                                    "\"'"))
                            j_rx += 1
                        break
                    if (t_rx in (">", ">>", "1>", "2>",
                                 "1>>", "2>>")
                            or re.fullmatch(
                                r"\d*>>?", t_rx)):
                        j_rx += 2
                        continue
                    if re.match(r"\d*>>?\S", t_rx):
                        j_rx += 1
                        continue
                    if t_rx.startswith("--"):
                        nama_rx, eq_rx, isi_rx = (
                            t_rx[2:].partition("="))
                        if nama_rx in ("help",
                                       "version"):
                            return False
                        if nama_rx == "user":
                            u_rx = True
                            if not eq_rx:
                                j_rx += 1
                        elif nama_rx == "command":
                            scl_rx = True
                            if c_rx is None:
                                if eq_rx:
                                    c_rx = isi_rx
                                elif (j_rx + 1
                                        < len(toks)):
                                    j_rx += 1
                                    c_rx = toks[
                                        j_rx].strip(
                                        "\"'")
                        elif nama_rx == "shell":
                            scl_rx = True
                            if not eq_rx:
                                j_rx += 1
                        elif nama_rx in (
                                "group",
                                "supp-group"):
                            if not eq_rx:
                                j_rx += 1
                        elif nama_rx in (
                                "login",
                                "session-command",
                                "fast"):
                            scl_rx = True
                        j_rx += 1
                        continue
                    if (t_rx.startswith("-")
                            and t_rx != "-"):
                        huruf_rx = t_rx[1:]
                        pos_rx = 0
                        while pos_rx < len(huruf_rx):
                            ch_rx = huruf_rx[pos_rx]
                            if ch_rx == "u":
                                u_rx = True
                                if (pos_rx
                                        == len(
                                            huruf_rx)
                                        - 1):
                                    j_rx += 1
                                break
                            if ch_rx == "c":
                                scl_rx = True
                                if c_rx is None:
                                    sisa_rx = (
                                        huruf_rx[
                                            pos_rx
                                            + 1:])
                                    if sisa_rx:
                                        c_rx = sisa_rx
                                    elif (j_rx + 1
                                            < len(
                                                toks)):
                                        j_rx += 1
                                        c_rx = toks[
                                            j_rx].strip(
                                            "\"'")
                                break
                            if ch_rx in "gGs":
                                if ch_rx == "s":
                                    scl_rx = True
                                if (pos_rx
                                        == len(
                                            huruf_rx)
                                        - 1):
                                    j_rx += 1
                                break
                            if ch_rx == "l":
                                scl_rx = True
                            pos_rx += 1
                        j_rx += 1
                        continue
                    operand_rx.append(t_rx)
                    j_rx += 1
                if u_rx and scl_rx:
                    return False
                if c_rx is not None:
                    return _menguras_stdin(
                        c_rx.strip("\"'"))
                if u_rx:
                    if operand_rx:
                        return _menguras_stdin(
                            " ".join(operand_rx))
                    return False
                if len(operand_rx) <= 1:
                    return True
                return False
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
                    mentah_su = t_su.strip("\"'")
                    info_su = (
                        mentah_su
                        in ("--help", "--version",
                            "-h")
                        or (t0 == "su"
                            and mentah_su == "-V"))
                    if (not info_su
                            and mentah_su.startswith(
                                "--")):
                        # Audit 2711: singkatan
                        # getopt_long atas
                        # --help/--version sama
                        # informasionalnya.
                        nama_su = mentah_su[2:].partition(
                            "=")[0]
                        info_su = bool(
                            nama_su
                            and ("help".startswith(
                                    nama_su)
                                 or "version".startswith(
                                    nama_su)))
                    if (not info_su
                            and mentah_su.startswith(
                                "-")
                            and not mentah_su.startswith(
                                "--")
                            and len(mentah_su) > 2):
                        # Audit 2711: di gugus
                        # pendek, huruf h (dan
                        # V khusus su) sebelum
                        # huruf bernilai pertama
                        # tetap informasional.
                        pembawa_su = (
                            "cgGs" if t0 == "su"
                            else "c")
                        for ch_su in mentah_su[1:]:
                            if ch_su in pembawa_su:
                                break
                            if (ch_su == "h"
                                    or (t0 == "su"
                                        and ch_su
                                        == "V")):
                                info_su = True
                                break
                    if info_su:
                        # Audit 2700: --help/--version
                        # (dan -h, serta -V khusus su)
                        # mencetak lalu keluar - blok
                        # 1107 hanya melompati mereka
                        # lewat pindaian -c sehingga
                        # tertelan vonis shell.
                        return False
                    if t_su == "--" or t_su.startswith("-"):
                        j_su += 1
                        continue
                    if not operand_su:
                        operand_su = True
                        j_su += 1
                        continue
                    break
                # Audit 2718: sg mewajibkan
                # operand grup - sg telanjang
                # mati usage, bukan menjatuhkan
                # shell (beda dari su/newgrp
                # yang sah telanjang).
                if t0 == "sg" and not operand_su:
                    return False
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
                if (t0 in ("nice", "ionice", "timeout")
                        and opsi.startswith("--")):
                    # Audit 2712: singkatan
                    # getopt_long atas nama
                    # yang tak divalidasi
                    # nilainya lolos sebagai
                    # bendera tak dikenal -
                    # yang informasional
                    # (nice --h/--v, ionice
                    # --h, timeout --h)
                    # mencetak lalu keluar
                    # dan yang ambigu
                    # (timeout --v, tabrakan
                    # --verbose/--version)
                    # membunuh biner; kedua
                    # kelas itu mati sebelum
                    # anak berjalan.
                    nama_op = opsi[2:].partition(
                        "=")[0]
                    if (nama_op
                            and ("help".startswith(
                                    nama_op)
                                 or "version".startswith(
                                    nama_op))):
                        return False
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
                if (t0 == "watch" and len(opsi) > 2
                        and opsi.startswith("-")
                        and not opsi.startswith("--")):
                    # Audit 1877: bundel watch diurai setia
                    # getopt - 'n' opsi bernilai di posisi mana
                    # pun: sisa token adalah nilainya, atau
                    # token berikutnya bila ia huruf terakhir.
                    # Sisa tak diawali digit/titik (termasuk
                    # bentuk -n=...) membuat watch galat
                    # sebelum menjalankan anak: tak ada anak.
                    for j_w in range(1, len(opsi)):
                        ch_w = opsi[j_w]
                        if ch_w == "n":
                            sisa_w = opsi[j_w + 1:]
                            if sisa_w:
                                if sisa_w[0] not in "0123456789.":
                                    awal = len(toks)
                            else:
                                awal = _sesudah_redirect_berantai(
                                    toks, awal, _batas_argv)
                                if awal < 0:
                                    return False
                                if awal < len(toks):
                                    awal += 1
                            break
                        if ch_w not in "degptx":
                            break
                if t0 == "chrt" and opsi in (
                        "-o", "--other", "-b", "--batch",
                        "-i", "--idle", "-d", "--deadline"):
                    # Audit 2681: kebijakan non-RT -
                    # prioritas sahnya hanya 0.
                    chrt_kebijakan_nol = True
                if t0 in ("nice", "ionice", "timeout"):
                    # Audit 2681: nilai opsi bertipe
                    # pembungkus - nilai sampah
                    # membunuh pembungkus saat urai
                    # (nice/ionice "invalid
                    # adjustment/class data", kelas
                    # ionice di luar 0..3 "unknown
                    # prio class", -k timeout tata
                    # bahasa durasi), tetapi selama
                    # ini tertelan tanpa diperiksa.
                    nama2681 = opsi
                    nilai2681 = None
                    if opsi.startswith("--") \
                            and "=" in opsi:
                        nama2681, _, nilai2681 = \
                            opsi.partition("=")
                    elif (not opsi.startswith("--")
                            and len(opsi) > 2
                            and ((t0 == "nice"
                                  and opsi[1] == "n")
                                 or (t0 == "ionice"
                                     and opsi[1] in "ncu")
                                 or (t0 == "timeout"
                                     and opsi[1] in "ks"))):
                        nama2681 = opsi[:2]
                        nilai2681 = opsi[2:]
                    if nama2681.startswith("--"):
                        # Audit 2693: getopt_long
                        # menyelesaikan singkatan
                        # unik opsi panjang ke nama
                        # penuhnya (semesta penuh
                        # ketiga pembungkus ini
                        # terverifikasi tak punya
                        # tabrakan awalan di luar
                        # nama tervalidasi) -
                        # selesaikan agar validasi
                        # di sini dan konsumsi
                        # nilai di walk berlaku;
                        # singkatan yang ambigu
                        # atas dua nama tervalidasi
                        # membunuh pembungkus.
                        sah2693 = {
                            "nice": ("--adjustment",),
                            "ionice": ("--classdata",
                                       "--class",
                                       "--which"),
                            "timeout": ("--kill-after",
                                        "--signal"),
                        }[t0]
                        if nama2681 not in sah2693:
                            calon2693 = [
                                x for x in sah2693
                                if x.startswith(nama2681)]
                            if len(calon2693) > 1:
                                return False
                            if len(calon2693) == 1:
                                nama2681 = calon2693[0]
                                if nilai2681 is None:
                                    opsi = nama2681
                                else:
                                    opsi = (nama2681
                                            + "="
                                            + nilai2681)
                    if nilai2681 is None \
                            and nama2681 in bernilai:
                        k2681 = _sesudah_redirect_berantai(
                            toks, awal, _batas_argv)
                        if 0 <= k2681 < len(toks):
                            nilai2681 = toks[k2681]
                    if nilai2681 is not None:
                        nilai2681 = nilai2681.strip("\"'")
                        if (t0 == "nice"
                                and nama2681 in (
                                    "-n", "--adjustment")
                                and not re.fullmatch(
                                    r"[+-]?\d+",
                                    nilai2681)):
                            return False
                        if (t0 == "ionice"
                                and nama2681 in (
                                    "-n", "--classdata")
                                and not re.fullmatch(
                                    r"[+-]?\d+",
                                    nilai2681)):
                            return False
                        if (t0 == "ionice"
                                and nama2681 in (
                                    "-c", "--class")
                                and not (
                                    nilai2681.isdigit()
                                    and int(nilai2681) <= 3)):
                            return False
                        if (t0 == "ionice"
                                and nama2681 in (
                                    "-u", "--which")
                                and not nilai2681.isdigit()):
                            # Audit 2694: -u hanya
                            # menerima UID numerik -
                            # nama pengguna pun mati
                            # ("invalid UID
                            # argument").
                            return False
                        if (t0 == "timeout"
                                and nama2681 in (
                                    "-k", "--kill-after")
                                and not _durasi_timeout_sah(
                                    nilai2681)):
                            return False
                        if (t0 == "timeout"
                                and nama2681 in (
                                    "-s", "--signal")
                                and not _sinyal_timeout_sah(
                                    nilai2681)):
                            return False
                if t0 in ("flock", "watch", "choom",
                          "uclampset", "prlimit",
                          "strace", "xargs", "taskset"):
                    # Audit 2719: penerima bertipe
                    # pada pembungkus lain -
                    # nilai sampah membunuh
                    # pembungkus saat urai sama
                    # seperti nice/ionice/timeout
                    # (2681), tetapi selama ini
                    # tertelan tanpa diperiksa.
                    # Singkatan panjang diselesaikan
                    # ke nama tervalidasi bila unik
                    # dan cukup panjang (ambang per
                    # alat dari daftar opsi biner);
                    # ambigu atas nama tervalidasi
                    # membunuh pembungkus.
                    penerima_b = {
                        "flock": {
                            "--timeout": "flock-w",
                            "--wait": "flock-w",
                            "--conflict-exit-code":
                                "flock-E"},
                        "watch": {
                            "--interval":
                                "desimal-tanda"},
                        "choom": {
                            "--adjust": "choom-n"},
                        "uclampset": {},
                        "prlimit": {
                            "--as": "batas-prlimit",
                            "--core": "batas-prlimit",
                            "--cpu": "batas-prlimit",
                            "--data": "batas-prlimit",
                            "--fsize": "batas-prlimit",
                            "--locks": "batas-prlimit",
                            "--memlock": "batas-prlimit",
                            "--msgqueue": "batas-prlimit",
                            "--nice": "batas-prlimit",
                            "--nofile": "batas-prlimit",
                            "--nproc": "batas-prlimit",
                            "--rss": "batas-prlimit",
                            "--rtprio": "batas-prlimit",
                            "--rttime": "batas-prlimit",
                            "--sigpending":
                                "batas-prlimit",
                            "--stack": "batas-prlimit"},
                        "strace": {
                            "--string-limit":
                                "angka-plus"},
                        "xargs": {
                            "--max-chars": "xargs-min1",
                            "--max-args": "xargs-min1",
                            "--max-procs": "xargs-min0",
                            "--max-lines": "xargs-min1"},
                        "taskset": {
                            "--cpu-list": "daftar-cpu"},
                    }
                    pendek_b = {
                        "flock": {"w": "flock-w",
                                  "E": "flock-E"},
                        "watch": {"n": "desimal-tanda"},
                        "choom": {"n": "choom-n"},
                        "uclampset": {"m": "uclamp",
                                      "M": "uclamp"},
                        "prlimit": {},
                        "strace": {"s": "angka-plus"},
                        "xargs": {"s": "xargs-min1",
                                  "n": "xargs-min1",
                                  "P": "xargs-min0",
                                  "L": "xargs-min1"},
                        "taskset": {"c": "daftar-cpu"},
                    }
                    ambang_b = {"flock": 3, "watch": 3,
                                "choom": 3, "uclampset": 3,
                                "prlimit": 3, "strace": 7,
                                "xargs": 5, "taskset": 3}
                    nama_b = opsi
                    nilai_b = None
                    if (opsi.startswith("--")
                            and "=" in opsi):
                        nama_b, _, nilai_b = \
                            opsi.partition("=")
                    elif (not opsi.startswith("--")
                            and len(opsi) > 2
                            and opsi[1]
                            in pendek_b.get(t0, {})):
                        nama_b = opsi[:2]
                        nilai_b = opsi[2:]
                    jenis_b = None
                    if nama_b.startswith("--"):
                        if nama_b in penerima_b[t0]:
                            jenis_b = \
                                penerima_b[t0][nama_b]
                        else:
                            calon_b = [
                                x for x
                                in penerima_b[t0]
                                if x.startswith(
                                    nama_b)]
                            if len(calon_b) > 1:
                                return False
                            if (len(calon_b) == 1
                                    and len(nama_b) - 2
                                    >= ambang_b[t0]):
                                jenis_b = penerima_b[
                                    t0][calon_b[0]]
                                nama_b = calon_b[0]
                    elif (len(nama_b) == 2
                            and nama_b[0] == "-"):
                        jenis_b = pendek_b.get(
                            t0, {}).get(nama_b[1])
                    if jenis_b is not None:
                        if (nilai_b is None
                                and nama_b in bernilai):
                            k_b = \
                                _sesudah_redirect_berantai(
                                    toks, awal,
                                    _batas_argv)
                            if 0 <= k_b < len(toks):
                                nilai_b = toks[k_b]
                        if nilai_b is not None:
                            nilai_b = \
                                nilai_b.strip("\"'")
                            if jenis_b == "flock-w":
                                if not re.fullmatch(
                                        r"\+?(\d+\.?\d*"
                                        r"|\.\d+)"
                                        r"([eE][+-]?"
                                        r"\d+)?",
                                        nilai_b):
                                    return False
                            elif (jenis_b
                                    == "desimal-tanda"):
                                # Audit 2720: interval
                                # watch = strtod tanpa
                                # eksponen, tanda bebas.
                                if not re.fullmatch(
                                        r"[+-]?(\d+\.?\d*"
                                        r"|\.\d+)",
                                        nilai_b):
                                    return False
                            elif jenis_b == "choom-n":
                                # Audit 2721: penyesuaian
                                # choom = bilangan bulat
                                # rentang oom_score_adj
                                # -1000..1000.
                                if not (
                                        re.fullmatch(
                                            r"[+-]?\d+",
                                            nilai_b)
                                        and -1000
                                        <= int(nilai_b)
                                        <= 1000):
                                    return False
                            elif jenis_b == "uclamp":
                                # Audit 2722: nilai
                                # uclampset = bilangan
                                # bulat -1 (reset) atau
                                # rentang [0:1024];
                                # -m/-M hanya bentuk
                                # pendek di biner.
                                if not (
                                        re.fullmatch(
                                            r"-?\d+",
                                            nilai_b)
                                        and (int(nilai_b)
                                             == -1
                                             or 0 <= int(
                                                 nilai_b)
                                             <= 1024)):
                                    return False
                            elif (jenis_b
                                    == "batas-prlimit"):
                                # Audit 2723: parser
                                # batas prlimit sangat
                                # longgar (strtosize) -
                                # sisi [lunak:keras]
                                # sah bila kosong,
                                # berawalan unlimited,
                                # atau berawalan digit
                                # bertanda; selain itu
                                # mati urai. Keenam
                                # belas penerima teruji
                                # biner satu-satu.
                                sisi_b = nilai_b.split(
                                    ":")
                                if (nilai_b == ""
                                        or len(sisi_b)
                                        > 2
                                        or any(
                                            not (
                                                x == ""
                                                or x.startswith(
                                                    "unlimited")
                                                or re.match(
                                                    r"[+-]?"
                                                    r"\d",
                                                    x))
                                            for x in (
                                                y.lstrip()
                                                for y
                                                in sisi_b))):
                                    return False
                            elif (jenis_b
                                    == "angka-plus"):
                                # Audit 2724: batas
                                # string strace =
                                # digit dengan + awal
                                # bebas (0 sah, negatif
                                # mati).
                                if not re.fullmatch(
                                        r"\+?\d+",
                                        nilai_b):
                                    return False
                            elif (jenis_b
                                    == "xargs-min1"
                                    or jenis_b
                                    == "xargs-min0"):
                                # Audit 2725: penerima
                                # numerik xargs =
                                # digit bertanda +;
                                # -s/-n/-L minimal 1,
                                # -P minimal 0.
                                if not (
                                        re.fullmatch(
                                            r"\+?\d+",
                                            nilai_b)
                                        and int(nilai_b)
                                        >= (1 if jenis_b
                                            == "xargs-min1"
                                            else 0)):
                                    return False
                            elif (jenis_b
                                    == "daftar-cpu"):
                                # Audit 2726: daftar
                                # CPU taskset -c =
                                # item N atau N-M(:S)
                                # dipisah koma, ujung
                                # rentang tak boleh
                                # lebih kecil dari
                                # awalnya.
                                sah_cpu = bool(
                                    re.fullmatch(
                                        r"\d+(-\d+"
                                        r"(:\d+)?)?"
                                        r"(,\d+(-\d+"
                                        r"(:\d+)?)?)*",
                                        nilai_b))
                                if sah_cpu:
                                    for item_cpu in (
                                            nilai_b.split(
                                                ",")):
                                        ujung_cpu = (
                                            item_cpu.split(
                                                ":")[0]
                                            .split("-"))
                                        if (len(ujung_cpu)
                                                == 2
                                                and int(
                                                    ujung_cpu[1])
                                                < int(
                                                    ujung_cpu[0])):
                                            sah_cpu = False
                                if not sah_cpu:
                                    return False
                            elif jenis_b == "flock-E":
                                if not (
                                        nilai_b.isdigit()
                                        and int(
                                            nilai_b)
                                        <= 255):
                                    return False
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
                    # Audit 2681: operand posisi
                    # bertipe ikut divalidasi -
                    # bentuk sampah mati parse di
                    # biner (durasi timeout, nama
                    # arsitektur setarch dari
                    # --list setempat, masker hex
                    # taskset bukan nol, prioritas
                    # chrt sesuai kebijakannya).
                    op2681 = toks[awal].strip("\"'")
                    if (t0 == "timeout"
                            and not _durasi_timeout_sah(
                                op2681)):
                        return False
                    if (t0 == "setarch"
                            and op2681 not in (
                                "uname26", "linux32",
                                "linux64", "i386", "i486",
                                "i586", "i686", "athlon",
                                "x86_64")):
                        return False
                    if (t0 == "taskset"
                            and not _masker_taskset_sah(
                                op2681)):
                        return False
                    if t0 == "chrt":
                        if not op2681.isdigit():
                            return False
                        if chrt_kebijakan_nol:
                            if int(op2681) != 0:
                                return False
                        elif not 1 <= int(op2681) <= 99:
                            return False
                    if t0 == "chroot":
                        chroot_punya_dir = True
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
        if pembungkus_terakhir == "unshare":
            return True
        # Audit 2703: runuser tanpa perintah -
        # shell login pembaca stdin (pemanggil
        # root), kecuali pengguna disebut
        # (mati "no command was specified").
        if pembungkus_terakhir == "runuser":
            return not runuser_punya_user
        # Audit 2682: chroot ikut vonis 1121 hanya bila
        # operand direktorinya tertelan; chroot telanjang
        # mati "missing operand" rc=125 di biner, jadi
        # ia jatuh ke vonis bawaan di bawah.
        if (pembungkus_terakhir == "chroot"
                and chroot_punya_dir):
            return True
        # Audit 2671: keluarga setarch tanpa program
        # juga menjalankan $SHELL pembaca stdin
        # (terverifikasi biner: payload perintah
        # tereksekusi) - tertinggal dari daftar
        # 1121. Alias berarsitektur tersirat
        # selalu; setarch polos hanya bila ada
        # token pengikut, telanjang tanpa apa
        # pun mati "no architecture argument".
        if pembungkus_terakhir in ("linux32", "linux64",
                                   "i386", "x86_64",
                                   "setarch"):
            # Audit 2671: --help/--version milik
            # pembungkus sendiri mencetak lalu
            # keluar - jangan tertukar shell.
            if any(t.strip("\"'") in ("--help",
                                       "--version")
                   for t in toks[awal_keluarga_setarch
                                 + 1:]):
                return xargs_sendiri
        if pembungkus_terakhir in ("linux32", "linux64",
                                   "i386", "x86_64"):
            return True
        if (pembungkus_terakhir == "setarch"
                and setarch_punya_pengikut):
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
        # Audit 2717: builtin read - penelan baris
        # bawaan shell, dinilai penilai khususnya.
        if perintah == "read":
            return xargs_sendiri or _menguras_read(toks, awal)
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
                        "wget", "nc", "netcat", "nc.openbsd", "ncat",
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
                        "aria2c", "choose", "cpan", "dasel", "derdump", "envsubst", "mlr", "sd", "recode", "ssh",
                        "docker", "grpcurl", "kubectl", "mariadb", "mosquitto_pub", "podman", "mysql", "psql", "redis-cli", "rsh", "R", "aws", "az", "borg", "buildah", "csvstat", "csvgrep", "csvstack", "csvformat", "csvjoin", "csvsql", "csvsort", "csvcut", "csvlook", "csvpy", "csvjson", "csvclean", "clickhouse", "clickhouse-client", "clickhouse-local", "consul", "convert", "crictl", "cqlsh", "cypher-shell", "duckdb", "entr", "exiftool", "etcdctl", "factor", "glab", "gcloud", "gnuplot", "gst-launch-1.0", "gsutil", "groff", "guix", "helm", "identify", "ipfs", "irb", "in2csv", "kak", "kafka-console-producer", "kafka-console-producer.sh", "litecli", "magick", "mail", "mailx", "micro", "mycli", "minikube", "mpv", "nano", "nats", "nerdctl", "nix", "oc", "pandoc", "parallel", "pg_restore", "rclone", "pv", "racket", "restic", "rev", "rlogin", "qsv", "sbcl", "s-nail", "sendmail", "skopeo", "sql2csv", "snowsql", "sox", "play", "tesseract", "terraform", "tsv-append", "tsv-join", "tsv-uniq", "tsv-summarize", "tsv-filter", "tsv-select", "tsv-sample", "tsv-pretty", "tsv-split", "csv2tsv", "number-lines", "keep-header", "units", "usql", "vagrant", "vault", "websocat", "xonsh", "xsv", "write",
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
    if xargs_dari_berkas:
        # Audit 2670: pengecualian menyeluruh 962
        # DIBATALKAN oleh bukti biner - -a hanya
        # memindahkan sumber DAFTAR ITEM xargs;
        # stdin anak tidak dialihkan, anak
        # mewarisi stdin bersama persis seperti
        # tanpa -a. Berkas argumen kosong (mis.
        # /dev/null) membuat anak berjalan tanpa
        # operand berkas dan membaca stdin
        # bersama sampai EOF (cat/grep/head
        # terverifikasi menunggu 2 detik penuh).
        # Vonis anak kini jatuh ke dispatch
        # normal di bawah, sama seperti tanpa
        # -a; yang berubah hanya xargs sendiri
        # tak ikut menguras (xargs_sendiri tetap
        # False kecuali nilai -a adalah "-").
        pass
    if xargs_sendiri:
        return True
    # Audit 2452: keluarga skrip dirutekan
    # sebelum penjaga 1102 - informasionalnya
    # diperiksa di sini, per perintah (grep
    # -h bendera asli, dikecualikan).

    # Audit 2578/2579: kosakata opsi dari biner asli
    # (GNU grep 3.11, sed 4.9, coreutils 9.4, jq 1.7,
    # rg 14.1.0, yq v4.44.5, fq 0.15.1 - disarikan
    # dari keluaran --help masing-masing).
    # panjang: nama panjang sah. pembawa: wajib
    # bernilai (token terpisah dimakan). opsional:
    # bernilai opsional (hanya bentuk "="; token
    # terpisah tak dimakan). singkat: parser
    # menyingkat awalan tak ambigu (getopt_long
    # GNU). pendek/pembawa_pendek: huruf pendek.
    # pola: pembawa yang nilainya pola/berkas pola.
    # pembawa2: pembawa dua token (nama + nilai).
    # eq_mati: opsi bool berbentuk "=" adalah
    # galat parser (getopt_long/clap).
    _VOKAB_SK = {
        "grep": {
            "panjang": frozenset({
                "after-context", "basic-regexp",
                "before-context", "binary",
                "binary-files", "byte-offset", "color",
                "colour", "context", "count",
                "dereference-recursive", "devices",
                "directories", "exclude", "exclude-dir",
                "exclude-from", "extended-regexp",
                "file", "files-with-matches",
                "files-without-match", "fixed-strings",
                "group-separator", "help", "ignore-case",
                "include", "initial-tab", "invert-match",
                "label", "line-buffered", "line-number",
                "line-regexp", "max-count", "no-filename",
                "no-group-separator", "no-ignore-case",
                "no-messages", "null", "null-data",
                "only-matching", "perl-regexp", "quiet",
                "recursive", "regexp", "silent", "text",
                "version", "with-filename",
                "word-regexp"}),
            "pembawa": frozenset({
                "after-context", "before-context",
                "binary-files", "context", "devices",
                "directories", "exclude", "exclude-dir",
                "exclude-from", "file",
                "group-separator", "include", "label",
                "max-count", "regexp"}),
            "opsional": frozenset({"color", "colour"}),
            "singkat": True,
            "pendek": frozenset(
                "ABCDEFGHILPRTUVZabcdefhilmnoqrsvwxz"),
            "pembawa_pendek": frozenset("ABCDefm"),
            "pola": frozenset({"regexp", "file"}),
            "pola_pendek": frozenset("ef"),
            "pembawa2": frozenset(),
            "eq_mati": True,
        },
        "sed": {
            "panjang": frozenset({
                "debug", "expression", "file",
                "follow-symlinks", "help", "in-place",
                "line-length", "null-data", "posix",
                "quiet", "regexp-extended", "sandbox",
                "separate", "silent", "unbuffered",
                "version"}),
            "pembawa": frozenset({
                "expression", "file", "line-length"}),
            "opsional": frozenset({"in-place"}),
            "singkat": True,
            "pendek": frozenset("Eefilnrsuz"),
            "pembawa_pendek": frozenset("efl"),
            "pola": frozenset(),
            "pola_pendek": frozenset(),
            "pembawa2": frozenset(),
            "eq_mati": True,
        },
        "jq": {
            "panjang": frozenset({
                "arg", "argjson", "args", "ascii-output",
                "build-configuration", "color-output",
                "compact-output", "exit-status",
                "from-file", "help", "indent",
                "join-output", "jsonargs",
                "monochrome-output", "null-input",
                "raw-input", "raw-output", "raw-output0",
                "rawfile", "seq", "slurp", "slurpfile",
                "sort-keys", "stream", "stream-errors",
                "tab", "unbuffered", "version"}),
            "pembawa": frozenset({
                "arg", "argjson", "from-file", "indent",
                "rawfile", "slurpfile"}),
            "opsional": frozenset(),
            "singkat": False,
            "pendek": frozenset("CLMRSVacefhjnrs"),
            "pembawa_pendek": frozenset("fL"),
            "pola": frozenset(),
            "pola_pendek": frozenset(),
            "pembawa2": frozenset(),
            "eq_mati": False,
        },
        "rg": {
            "panjang": frozenset({
                "after-context", "auto-hybrid-regex",
                "before-context", "binary",
                "block-buffered", "byte-offset",
                "case-sensitive", "color", "colors",
                "column", "context", "context-separator",
                "count", "count-matches", "crlf",
                "debug", "dfa-size-limit", "encoding",
                "engine", "field-context-separator",
                "field-match-separator", "file", "files",
                "files-with-matches",
                "files-without-match", "fixed-strings",
                "follow", "generate", "glob",
                "glob-case-insensitive", "heading",
                "help", "hidden", "hostname-bin",
                "hyperlink-format", "iglob", "ignore",
                "ignore-case", "ignore-dot",
                "ignore-exclude", "ignore-file",
                "ignore-file-case-insensitive",
                "ignore-files", "ignore-global",
                "ignore-messages", "ignore-parent",
                "ignore-vcs", "include-zero",
                "invert-match", "json", "line-buffered",
                "line-number", "line-regexp",
                "max-columns", "max-columns-preview",
                "max-count", "max-depth", "max-filesize",
                "maxdepth", "messages", "mmap",
                "multiline", "multiline-dotall",
                "no-auto-hybrid-regex", "no-binary",
                "no-block-buffered", "no-byte-offset",
                "no-column", "no-config",
                "no-context-separator", "no-crlf",
                "no-encoding", "no-filename",
                "no-fixed-strings", "no-follow",
                "no-glob-case-insensitive", "no-heading",
                "no-hidden", "no-ignore", "no-ignore-dot",
                "no-ignore-exclude",
                "no-ignore-file-case-insensitive",
                "no-ignore-files", "no-ignore-global",
                "no-ignore-messages", "no-ignore-parent",
                "no-ignore-vcs", "no-include-zero",
                "no-invert-match", "no-json",
                "no-line-buffered", "no-line-number",
                "no-max-columns-preview", "no-messages",
                "no-mmap", "no-multiline",
                "no-multiline-dotall",
                "no-one-file-system", "no-pcre2",
                "no-pcre2-unicode", "no-pre",
                "no-require-git", "no-search-zip",
                "no-sort-files", "no-stats", "no-text",
                "no-trim", "no-unicode", "null",
                "null-data", "one-file-system",
                "only-matching", "passthrough",
                "passthru", "path-separator", "pcre2",
                "pcre2-unicode", "pcre2-version", "pre",
                "pre-glob", "pretty", "print0", "quiet",
                "regex-size-limit", "regexp", "replace",
                "require-git", "search-zip", "smart-case",
                "sort", "sort-files", "sortr", "stats",
                "stop-on-nonmatch", "text", "threads",
                "trace", "trim", "type", "type-add",
                "type-clear", "type-list", "type-not",
                "unicode", "unrestricted", "version",
                "vimgrep", "with-filename",
                "word-regexp"}),
            "pembawa": frozenset({
                "after-context", "before-context",
                "color", "colors", "context",
                "context-separator", "dfa-size-limit",
                "encoding", "engine",
                "field-context-separator",
                "field-match-separator", "file",
                "generate", "glob", "hostname-bin",
                "hyperlink-format", "iglob",
                "ignore-file", "max-columns",
                "max-count", "max-depth", "max-filesize",
                "maxdepth", "path-separator", "pre",
                "pre-glob", "regex-size-limit", "regexp",
                "replace", "sort", "threads", "type",
                "type-add", "type-not"}),
            "opsional": frozenset(),
            "singkat": False,
            "pendek": frozenset(
                "0ABCEFHILMNPRSTUVabcdefghijlmnopqrst"
                "uvwxz"),
            "pembawa_pendek": frozenset("ABCEefgjmMrtT"),
            "pola": frozenset({"regexp", "file"}),
            "pola_pendek": frozenset("ef"),
            "pembawa2": frozenset(),
            "eq_mati": True,
        },
        "yq": {
            "panjang": frozenset({
                "colors", "csv-auto-parse",
                "csv-separator", "exit-status",
                "expression", "from-file", "front-matter",
                "header-preprocess", "help", "indent",
                "inplace", "input-format", "lua-globals",
                "lua-prefix", "lua-suffix",
                "lua-unquoted", "no-colors", "no-doc",
                "nul-output", "null-input",
                "output-format", "prettyPrint",
                "properties-array-brackets",
                "properties-separator", "split-exp",
                "split-exp-file", "string-interpolation",
                "tsv-auto-parse", "unwrapScalar",
                "verbose", "version",
                "xml-attribute-prefix",
                "xml-content-name", "xml-directive-name",
                "xml-keep-namespace",
                "xml-proc-inst-prefix", "xml-raw-token",
                "xml-skip-directives",
                "xml-skip-proc-inst", "xml-strict-mode"}),
            "pembawa": frozenset({
                "csv-separator", "from-file",
                "front-matter", "indent", "input-format",
                "lua-globals", "lua-prefix", "lua-suffix",
                "output-format", "properties-separator",
                "split-exp", "split-exp-file",
                "xml-attribute-prefix",
                "xml-content-name", "xml-directive-name",
                "xml-proc-inst-prefix"}),
            "opsional": frozenset(),
            "singkat": False,
            "pendek": frozenset("0CIMNPVefhinoprsv"),
            "pembawa_pendek": frozenset("Iops"),
            "pola": frozenset(),
            "pola_pendek": frozenset(),
            "pembawa2": frozenset(),
            "eq_mati": False,
        },
        "fq": {
            "panjang": frozenset({
                "arg", "argdecode", "argjson",
                "color-output", "compact-output",
                "decode", "from-file", "help",
                "include-path", "join-output",
                "monochrome-output", "null-input",
                "option", "raw-file", "raw-input",
                "raw-output", "raw-output0", "repl",
                "slurp", "unicode-output",
                "value-output", "version"}),
            "pembawa": frozenset({
                "arg", "argdecode", "argjson", "decode",
                "from-file", "include-path", "option",
                "raw-file"}),
            "opsional": frozenset(),
            "singkat": False,
            "pendek": frozenset("CLMRUVcdfhijnorsv"),
            "pembawa_pendek": frozenset("dL"),
            "pola": frozenset(),
            "pola_pendek": frozenset(),
            "pembawa2": frozenset({"raw-file"}),
            "eq_mati": False,
        },
        "sort": {
            "panjang": frozenset({
                "batch-size", "buffer-size", "check",
                "compress-program", "debug",
                "dictionary-order", "field-separator",
                "files0-from", "general-numeric-sort",
                "help", "human-numeric-sort",
                "ignore-case", "ignore-leading-blanks",
                "ignore-nonprinting", "key", "merge",
                "month-sort", "numeric-sort", "output",
                "parallel", "random-sort",
                "random-source", "reverse", "sort",
                "stable", "temporary-directory",
                "unique", "version", "version-sort",
                "zero-terminated"}),
            "pembawa": frozenset({
                "batch-size", "buffer-size",
                "compress-program", "field-separator",
                "files0-from", "key", "output",
                "parallel", "random-source", "sort",
                "temporary-directory"}),
            "opsional": frozenset({"check"}),
            "singkat": True,
            "pendek": frozenset(
                "CMRSTVbcdfghikmnorstuz"),
            "pembawa_pendek": frozenset("STkot"),
            "pola": frozenset(),
            "pola_pendek": frozenset(),
            "pembawa2": frozenset(),
            "eq_mati": True,
        },
        "head": {
            "panjang": frozenset({
                "bytes", "help", "lines", "quiet",
                "silent", "verbose", "version",
                "zero-terminated"}),
            "pembawa": frozenset({"bytes", "lines"}),
            "opsional": frozenset(),
            "singkat": True,
            "pendek": frozenset("cnqvz"),
            "pembawa_pendek": frozenset("cn"),
            "pola": frozenset(),
            "pola_pendek": frozenset(),
            "pembawa2": frozenset(),
            "eq_mati": True,
        },
        "tail": {
            "panjang": frozenset({
                "bytes", "follow", "help", "lines",
                "max-unchanged-stats", "pid", "quiet",
                "retry", "silent", "sleep-interval",
                "verbose", "version",
                "zero-terminated"}),
            "pembawa": frozenset({
                "bytes", "lines", "max-unchanged-stats",
                "pid", "sleep-interval"}),
            "opsional": frozenset({"follow"}),
            "singkat": True,
            "pendek": frozenset("Fcfnqsvz"),
            "pembawa_pendek": frozenset("cns"),
            "pola": frozenset(),
            "pola_pendek": frozenset(),
            "pembawa2": frozenset(),
            "eq_mati": True,
        },
        "uniq": {
            "panjang": frozenset({
                "all-repeated", "check-chars", "count",
                "group", "help", "ignore-case",
                "repeated", "skip-chars", "skip-fields",
                "unique", "version",
                "zero-terminated"}),
            "pembawa": frozenset({
                "check-chars", "skip-chars",
                "skip-fields"}),
            "opsional": frozenset({"group"}),
            "singkat": True,
            "pendek": frozenset("Dcdfisuwz"),
            "pembawa_pendek": frozenset("fsw"),
            "pola": frozenset(),
            "pola_pendek": frozenset(),
            "pembawa2": frozenset(),
            "eq_mati": True,
        },
        "cut": {
            "panjang": frozenset({
                "bytes", "characters", "complement",
                "delimiter", "fields", "help",
                "only-delimited", "output-delimiter",
                "version", "zero-terminated"}),
            "pembawa": frozenset({
                "bytes", "characters", "delimiter",
                "fields", "output-delimiter"}),
            "opsional": frozenset(),
            "singkat": True,
            "pendek": frozenset("bcdfnsz"),
            "pembawa_pendek": frozenset("bcdf"),
            "pola": frozenset(),
            "pola_pendek": frozenset(),
            "pembawa2": frozenset(),
            "eq_mati": True,
        },
        "wc": {
            "panjang": frozenset({
                "bytes", "chars", "files0-from", "help",
                "lines", "max-line-length", "total",
                "version", "words"}),
            "pembawa": frozenset({"files0-from"}),
            "opsional": frozenset({"total"}),
            "singkat": True,
            "pendek": frozenset("Lclmw"),
            "pembawa_pendek": frozenset(),
            "pola": frozenset(),
            "pola_pendek": frozenset(),
            "pembawa2": frozenset(),
            "eq_mati": True,
        },
        # Audit 2645: kosakata pembaca generik -
        # ekstrak --help biner lokal (coreutils
        # 9.4, gzip, binutils strings, glibc
        # iconv) + verifikasi pembawa tanpa
        # nilai ke biner, metode yang sama
        # dengan _VOCAB_GREP_25/2579. Sebelumnya
        # perintah-perintah ini tak punya
        # penjaga opsi sama sekali (--bogus/-x/
        # --hel dinilai menguras).
        "cat": {
            "panjang": frozenset({
                "help", "number", "number-nonblank", "show-all", "show-ends", "show-nonprinting", "show-tabs", "squeeze-blank", "version"}),
            "pembawa": frozenset(),
            "opsional": frozenset(),
            "singkat": True,
            "pendek": frozenset("AETbenstuv"),
            "pembawa_pendek": frozenset(),
            "pola": frozenset(),
            "pola_pendek": frozenset(),
            "pembawa2": frozenset(),
            "eq_mati": True,
        },
        "tee": {
            "panjang": frozenset({
                "append", "help", "ignore-interrupts", "output-error", "version"}),
            "pembawa": frozenset(),
            "opsional": frozenset({
                "output-error"}),
            "singkat": True,
            "pendek": frozenset("aip"),
            "pembawa_pendek": frozenset(),
            "pola": frozenset(),
            "pola_pendek": frozenset(),
            "pembawa2": frozenset(),
            "eq_mati": True,
        },
        "dd": {
            "panjang": frozenset({
                "help", "version"}),
            "pembawa": frozenset(),
            "opsional": frozenset(),
            "singkat": True,
            "pendek": frozenset(),
            "pembawa_pendek": frozenset(),
            "pola": frozenset(),
            "pola_pendek": frozenset(),
            "pembawa2": frozenset(),
            "eq_mati": True,
        },
        "sha256sum": {
            "panjang": frozenset({
                "binary", "check", "help", "ignore-missing", "quiet", "status", "strict", "tag", "text", "version", "warn", "zero"}),
            "pembawa": frozenset(),
            "opsional": frozenset(),
            "singkat": True,
            "pendek": frozenset("bctwz"),
            "pembawa_pendek": frozenset(),
            "pola": frozenset(),
            "pola_pendek": frozenset(),
            "pembawa2": frozenset(),
            "eq_mati": True,
        },
        "gzip": {
            "panjang": frozenset({
                "best", "decompress", "fast", "force", "help", "keep", "license", "list", "name", "no-name", "quiet", "recursive", "rsyncable", "stdout", "suffix", "synchronous", "test", "verbose", "version"}),
            "pembawa": frozenset({
                "suffix"}),
            "opsional": frozenset(),
            "singkat": True,
            "pendek": frozenset("123456789LNSVcdfhklnqrstv"),
            "pembawa_pendek": frozenset("S"),
            "pola": frozenset(),
            "pola_pendek": frozenset(),
            "pembawa2": frozenset(),
            "eq_mati": True,
        },
        "base64": {
            "panjang": frozenset({
                "decode", "help", "ignore-garbage", "version", "wrap"}),
            "pembawa": frozenset({
                "wrap"}),
            "opsional": frozenset(),
            "singkat": True,
            "pendek": frozenset("diw"),
            "pembawa_pendek": frozenset("w"),
            "pola": frozenset(),
            "pola_pendek": frozenset(),
            "pembawa2": frozenset(),
            "eq_mati": True,
        },
        "od": {
            "panjang": frozenset({
                "address-radix", "endian", "format", "help", "output-duplicates", "read-bytes", "skip-bytes", "traditional", "version", "width"}),
            "pembawa": frozenset({
                "address-radix", "endian", "format", "read-bytes", "skip-bytes"}),
            "opsional": frozenset({
                "traditional", "width"}),
            "singkat": True,
            "pendek": frozenset("ANSabcdfijlostvwx"),
            "pembawa_pendek": frozenset("ANSjt"),
            "pola": frozenset(),
            "pola_pendek": frozenset(),
            "pembawa2": frozenset(),
            "eq_mati": True,
        },
        "tac": {
            "panjang": frozenset({
                "before", "help", "regex", "separator", "version"}),
            "pembawa": frozenset({
                "separator"}),
            "opsional": frozenset(),
            "singkat": True,
            "pendek": frozenset("brs"),
            "pembawa_pendek": frozenset("s"),
            "pola": frozenset(),
            "pola_pendek": frozenset(),
            "pembawa2": frozenset(),
            "eq_mati": True,
        },
        "fold": {
            "panjang": frozenset({
                "bytes", "help", "spaces", "version", "width"}),
            "pembawa": frozenset({
                "width"}),
            "opsional": frozenset(),
            "singkat": True,
            "pendek": frozenset("bsw"),
            "pembawa_pendek": frozenset("w"),
            "pola": frozenset(),
            "pola_pendek": frozenset(),
            "pembawa2": frozenset(),
            "eq_mati": True,
        },
        "paste": {
            "panjang": frozenset({
                "delimiters", "help", "serial", "version", "zero-terminated"}),
            "pembawa": frozenset({
                "delimiters"}),
            "opsional": frozenset(),
            "singkat": True,
            "pendek": frozenset("dsz"),
            "pembawa_pendek": frozenset("d"),
            "pola": frozenset(),
            "pola_pendek": frozenset(),
            "pembawa2": frozenset(),
            "eq_mati": True,
        },
        "fmt": {
            "panjang": frozenset({
                "crown-margin", "goal", "help", "prefix", "split-only", "tagged-paragraph", "uniform-spacing", "version", "width"}),
            "pembawa": frozenset({
                "goal", "prefix", "width"}),
            "opsional": frozenset(),
            "singkat": True,
            "pendek": frozenset("cgpstuw"),
            "pembawa_pendek": frozenset("gpw"),
            "pola": frozenset(),
            "pola_pendek": frozenset(),
            "pembawa2": frozenset(),
            "eq_mati": True,
        },
        "shuf": {
            "panjang": frozenset({
                "echo", "head-count", "help", "input-range", "output", "random-source", "repeat", "version", "zero-terminated"}),
            "pembawa": frozenset({
                "head-count", "input-range", "output", "random-source"}),
            "opsional": frozenset(),
            "singkat": True,
            "pendek": frozenset("einorz"),
            "pembawa_pendek": frozenset("ino"),
            "pola": frozenset(),
            "pola_pendek": frozenset(),
            "pembawa2": frozenset(),
            "eq_mati": True,
        },
        "strings": {
            "panjang": frozenset({
                "all", "bytes", "data", "encoding", "help", "include-all-whitespace", "output-separator", "print-file-name", "radix", "target", "unicode", "version"}),
            "pembawa": frozenset({
                "bytes", "encoding", "output-separator", "radix", "target", "unicode"}),
            "opsional": frozenset(),
            "singkat": True,
            "pendek": frozenset("TUVadefhnostvw"),
            "pembawa_pendek": frozenset("TUenst"),
            "pola": frozenset(),
            "pola_pendek": frozenset(),
            "pembawa2": frozenset(),
            "eq_mati": True,
        },
        "iconv": {
            "panjang": frozenset({
                "from-code", "help", "list", "output", "silent", "to-code", "usage", "verbose", "version"}),
            "pembawa": frozenset({
                "from-code", "output", "to-code"}),
            "opsional": frozenset(),
            "singkat": True,
            "pendek": frozenset("Vcflost"),
            "pembawa_pendek": frozenset("fot"),
            "pola": frozenset(),
            "pola_pendek": frozenset(),
            "pembawa2": frozenset(),
            "eq_mati": True,
        },
        "cksum": {
            "panjang": frozenset({
                "algorithm", "base64", "check", "debug", "help", "ignore-missing", "length", "quiet", "raw", "status", "strict", "tag", "untagged", "version", "warn", "zero"}),
            "pembawa": frozenset({
                "algorithm", "length"}),
            "opsional": frozenset(),
            "singkat": True,
            "pendek": frozenset("aclrswz"),
            "pembawa_pendek": frozenset("al"),
            "pola": frozenset(),
            "pola_pendek": frozenset(),
            "pembawa2": frozenset(),
            "eq_mati": True,
        },
        "split": {
            "panjang": frozenset({
                "additional-suffix", "bytes", "elide-empty-files", "filter", "help", "line-bytes", "lines", "number", "separator", "suffix-length", "unbuffered", "verbose", "version"}),
            "pembawa": frozenset({
                "additional-suffix", "bytes", "filter", "line-bytes", "lines", "number", "separator", "suffix-length"}),
            "opsional": frozenset(),
            "singkat": True,
            "pendek": frozenset("Cabelntux"),
            "pembawa_pendek": frozenset("Cablnt"),
            "pola": frozenset(),
            "pola_pendek": frozenset(),
            "pembawa2": frozenset(),
            "eq_mati": True,
        },
    }
    # Audit 2645: alias berbagi kosakata biner
    # yang sama (keluarga sum, keluarga gzip,
    # base32).
    for _alias_45 in ("md5sum", "sha1sum",
                      "sha224sum", "sha384sum",
                      "sha512sum", "b2sum"):
        _VOKAB_SK[_alias_45] = _VOKAB_SK["sha256sum"]
    for _alias_45 in ("gunzip", "zcat", "gzcat"):
        _VOKAB_SK[_alias_45] = _VOKAB_SK["gzip"]
    _VOKAB_SK["base32"] = _VOKAB_SK["base64"]

    def _status_panjang(vokab, token):
        # token diawali "--"; hasil (status, nama
        # teresolusi): status "info" (berhenti
        # informasional), "pembawa" (nilai token
        # terpisah), "pembawa=" (nilai nempel),
        # "pembawa2" (dua token nilai), "opsi"
        # (sah polos), "mati" (asing/ambigu/bool
        # berbentuk "=" - alat mati sebelum
        # membaca).
        badan = token[2:]
        nama, eq, _ = badan.partition("=")
        if nama in vokab["panjang"]:
            resolusi = nama
        elif vokab["singkat"] and nama:
            calon = [x for x in vokab["panjang"]
                     if x.startswith(nama)]
            if len(calon) != 1:
                return ("mati", "")
            resolusi = calon[0]
        else:
            return ("mati", "")
        if resolusi in ("help", "version"):
            return ("mati", resolusi) if eq else (
                "info", resolusi)
        if resolusi in vokab["opsional"]:
            return ("opsi", resolusi)
        if resolusi in vokab["pembawa"]:
            if eq:
                return ("pembawa=", resolusi)
            if resolusi in vokab["pembawa2"]:
                return ("pembawa2", resolusi)
            return ("pembawa", resolusi)
        if eq and vokab["eq_mati"]:
            return ("mati", resolusi)
        return ("opsi", resolusi)

    def _status_klaster(vokab, token):
        # token "-xyz" (satu strip); hasil
        # (status, huruf pembawa): "opsi",
        # "pembawa" (huruf terakhir - nilai token
        # terpisah), "pembawa-nempel" (sisa token
        # adalah nilai), "mati" (huruf asing).
        huruf = token[1:]
        for i_h, ch in enumerate(huruf):
            if ch in vokab["pembawa_pendek"]:
                if i_h == len(huruf) - 1:
                    return ("pembawa", ch)
                return ("pembawa-nempel", ch)
            if ch not in vokab["pendek"]:
                return ("mati", "")
        return ("opsi", "")

    info_skrip = {
        "jq": {"-h", "--help", "-V", "--version"},
        "yq": {"-h", "--help", "-V", "--version"},
        "fq": {"-h", "--help", "-v", "--version"},
        "sed": {"-h", "--help", "-V", "--version"},
        "rg": {"-h", "--help", "-V", "--version"},
    }
    info_awk = {"-h", "--help", "-V", "--version"}
    info_grep = {"--help", "-V", "--version"}
    kanon_skrip = {
        "mawk": "awk", "nawk": "awk", "gawk": "awk",
        "pgawk": "awk", "igawk": "awk",
        "dgawk": "awk", "original-awk": "awk",
        "egrep": "grep", "fgrep": "grep",
        "zgrep": "grep", "bzgrep": "grep",
        "xzgrep": "grep", "lzgrep": "grep",
        "zstdgrep": "grep", "zegrep": "grep",
        "zfgrep": "grep", "bzegrep": "grep",
        "bzfgrep": "grep", "xzegrep": "grep",
        "xzfgrep": "grep", "lzegrep": "grep",
        "lzfgrep": "grep",
    }
    if (perintah in info_skrip or perintah == "awk"
            or perintah == "grep"
            or perintah in kanon_skrip):
        if perintah in info_skrip:
            tanda_info = info_skrip[perintah]
        elif (perintah == "awk"
                or kanon_skrip.get(perintah) == "awk"):
            tanda_info = info_awk
        else:
            tanda_info = info_grep
        if perintah in info_skrip:
            keluarga = perintah
        elif perintah in ("awk", "grep"):
            keluarga = perintah
        else:
            keluarga = kanon_skrip.get(perintah, "")
        lewati_info = 0
        pola_opsi = False
        operand_isk = 0
        info_termakan = False
        sisa_isk = toks[awal + 1:]
        for pos_isk, t_isk in enumerate(sisa_isk):
            b_isk = t_isk.strip("\"'")
            if lewati_info > 0:
                lewati_info -= 1
                if b_isk in tanda_info:
                    info_termakan = True
                continue
            if b_isk == "--":
                operand_isk += len(sisa_isk) - pos_isk - 1
                break
            # Audit 2454: pembawa nilai keluarga
            # skrip (pola/berkas/label/glob) -
            # token berikutnya adalah nilai,
            # bukan informasional.
            if (b_isk in ("-e", "-f",
                          # Audit 2639: "--pattern" dibuang
                          # dari pendahulu keluarga skrip -
                          # tak satu pun keluarga ini
                          # (grep/sed/awk/jq/yq/fq) punya opsi
                          # itu; tokennya kini jatuh ke cek
                          # kosakata dan mati sebagaimana
                          # binernya.
                          "--file", "--expression",
                          "--label", "--glob",
                          "--pre", "--pre-glob")
                    and not (b_isk == "-e"
                             and (keluarga in ("jq",
                                               "yq",
                                               "fq")
                                  or (keluarga == "awk"
                                      and toks[awal]
                                      .strip("\"'")
                                      .split("/")[-1]
                                      in ("mawk", "nawk",
                                          "original-awk"))))):
                # Audit 2476: -e di jq/yq adalah
                # --exit-status (bool) dan fq tak
                # punya -e sama sekali - bukan
                # pembawa pola bagi ketiganya.
                # Audit 2482: mawk/nawk/original-awk
                # juga tak punya -e (nama asli dari
                # token perintah, bukan keluarga).
                if b_isk in ("-e", "-f",
                             "--file", "--expression"):
                    pola_opsi = True
                lewati_info = 1
                continue
            # Audit 2459: --regex singkatan unik
            # getopt bagi --regexp khusus grep;
            # Audit 2460: pembawa string bebas
            # khas grep.
            if (keluarga == "grep"
                    and b_isk in ("--regexp",
                                  "--regex",
                                  "--group-separator",
                                  "--exclude",
                                  "--include",
                                  "--exclude-dir",
                                  "--exclude-from")):
                if b_isk in ("--regexp", "--regex"):
                    pola_opsi = True
                lewati_info = 1
                continue
            # Audit 2460: pembawa khas rg
            # (--regexp pola rg ikut di sini;
            # di luar grep/rg, --regexp bukan
            # opsi yang sah).
            if (keluarga == "rg"
                    and b_isk in ("--regexp",
                                  "-r", "--replace",
                                  "--iglob",
                                  "--field-match-"
                                  "separator",
                                  "--field-context-"
                                  "separator",
                                  "--hostname-bin")):
                if b_isk == "--regexp":
                    pola_opsi = True
                lewati_info = 1
                continue
            # Audit 2460: pembawa khas jq/awk.
            if ((keluarga in ("jq", "fq")
                    and b_isk == "-L")
                    or (keluarga == "awk"
                        and b_isk == "-F")):
                lewati_info = 1
                continue
            # Audit 2500: pembawa string bebas
            # yq (opsi 2480) - pflag menelan
            # nilainya mentah; token informasional
            # sesudahnya bukan bendera.
            if (perintah == "yq" and b_isk in (
                    "--lua-prefix", "--lua-suffix",
                    "--properties-separator",
                    "--xml-attribute-prefix",
                    "--xml-content-name",
                    "--xml-directive-name",
                    "--xml-proc-inst-prefix",
                    "--front-matter")):
                lewati_info = 1
                continue
            if ((perintah == "jq"
                    and b_isk in ("--arg",
                                  "--argjson",
                                  "--rawfile",
                                  "--slurpfile",
                                  "--argfile"))
                    or (perintah == "fq"
                        and b_isk in ("--arg",
                                      "--argjson",
                                      "--argdecode",
                                      "--raw-file"))):
                # Audit 2454: pembawa dua token
                # (nama + nilai/berkas).
                # Audit 2468: daftar fq hanya
                # --arg/--argjson - biner fq 0.15.1
                # menolak --rawfile/--slurpfile/
                # --argfile ("no such argument").
                lewati_info = 2
                continue
            # Audit 2457: klausa -v awk DICABUT -
            # argumen -v divalidasi sebagai
            # penugasan nama=nilai, jadi token
            # informasional telanjang sesudahnya
            # selalu galat (berhenti), bukan nilai.
            # Audit 2578: singkatan tak ambigu
            # --help/--version getopt_long GNU
            # (grep/sed/awk) juga informasional -
            # parser jq/rg/yq/fq tak menyingkat
            # (bentuk itu mati sebagai opsi asing,
            # dinilai Audit 2579). Pembawa hasil
            # singkatan ikut menelan nilainya.
            if (b_isk.startswith("--")
                    and keluarga in _VOKAB_SK):
                st_isk, nm_isk = _status_panjang(
                    _VOKAB_SK[keluarga], b_isk)
                if st_isk == "info":
                    return False
                # Audit 2579: nama asing/ambigu -
                # parser keluarga ini mati sebelum
                # membaca (getopt_long/clap/pflag/
                # parser khas; dibuktikan ke biner
                # asli tiap alat).
                if st_isk == "mati":
                    return False
                if st_isk == "pembawa":
                    if nm_isk in _VOKAB_SK[
                            keluarga]["pola"]:
                        pola_opsi = True
                    lewati_info = 1
                    continue
                if st_isk == "pembawa2":
                    lewati_info = 2
                    continue
            # Audit 2579: klaster pendek keluarga
            # berkosakata - huruf asing membunuh
            # alat sebelum membaca. Pengecualian
            # grep: -NUM warisan = konteks (biner
            # membuktikan -1 sah).
            if (keluarga in _VOKAB_SK
                    and b_isk.startswith("-")
                    and not b_isk.startswith("--")
                    and len(b_isk) > 1
                    and not (keluarga == "grep"
                             and b_isk[1:].isdigit())):
                st_isk2, ch_isk = _status_klaster(
                    _VOKAB_SK[keluarga], b_isk)
                if st_isk2 == "mati":
                    return False
                if st_isk2 == "pembawa":
                    if ch_isk in _VOKAB_SK[
                            keluarga]["pola_pendek"]:
                        pola_opsi = True
                    lewati_info = 1
                    continue
            if (keluarga == "awk"
                    and b_isk.startswith("--")
                    and "=" not in b_isk):
                nama_awk = b_isk[2:]
                if (nama_awk
                        and ("help".startswith(nama_awk)
                             or "version".startswith(
                                 nama_awk))):
                    return False
            if b_isk in tanda_info:
                return False
            if (not b_isk.startswith("-")
                    or b_isk == "-"):
                operand_isk += 1
        # Audit 2461: pembawa yang masih menunggu
        # nilai di akhir segmen = argumen hilang,
        # alat berhenti sebelum membaca stdin.
        if lewati_info > 0:
            return False
        # Audit 2458: nilai informasional termakan
        # pembawa tanpa pola dari opsi dan tanpa
        # operand tersisa = kelaparan pola/skrip,
        # alat berhenti dengan galat.
        if (info_termakan and not pola_opsi
                and operand_isk == 0
                and keluarga not in ("jq", "fq")):
            return False
    if perintah in ("sed", "awk", "grep", "jq", "yq", "fq",
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
    # Audit 2578: singkatan tak ambigu --help/
    # --version GNU coreutils juga informasional -
    # penyusuran sadar nilai (pembawa menelan token
    # berikutnya) agar nilai opsi tak dinilai
    # panggilan bantuan.
    # Audit 2626: penerima jumlah head/tail
    # (-n/-c/--lines/--bytes) - nilai bukan
    # angka membunuh alat sebelum membaca
    # stdin; jalur generik sebelumnya tanpa
    # model kematian ini (head -n abc ditolak
    # gerbang padahal alatnya mati). Penyusuran
    # sadar nilai seperti 2622.
    if perintah in ("head", "tail"):
        bernilai_ht = _OPSI_BERNILAI.get(perintah, set())
        toks_ht = [t.strip("\"'") for t in toks[awal + 1:]]
        lewati_ht = 0
        for idx_ht, b_ht in enumerate(toks_ht):
            if lewati_ht > 0:
                lewati_ht -= 1
                continue
            if b_ht == "--":
                break
            if b_ht.startswith("--"):
                nama_ht, eq_ht, isi_ht = (
                    b_ht[2:].partition("="))
                if nama_ht in ("lines", "bytes"):
                    if eq_ht:
                        if not _jumlah_head_sah(isi_ht):
                            return False
                    elif idx_ht + 1 < len(toks_ht):
                        if not _jumlah_head_sah(
                                toks_ht[idx_ht + 1]):
                            return False
                        lewati_ht = 1
                    continue
                if ("--" + nama_ht) in bernilai_ht and not eq_ht:
                    lewati_ht = 1
                continue
            if (b_ht.startswith("-") and b_ht != "-"
                    and not b_ht[1:].isdigit()):
                for pos_ht, ch_ht in enumerate(b_ht[1:]):
                    if ch_ht in ("n", "c"):
                        ekor_ht = b_ht[1:][pos_ht + 1:]
                        if ekor_ht:
                            if not _jumlah_head_sah(ekor_ht):
                                return False
                        elif idx_ht + 1 < len(toks_ht):
                            if not _jumlah_head_sah(
                                    toks_ht[idx_ht + 1]):
                                return False
                            lewati_ht = 1
                        break
                    if "-" + ch_ht in bernilai_ht:
                        if pos_ht == len(b_ht) - 2:
                            lewati_ht = 1
                        break
                continue
    # Audit 2642: cut mewajibkan satu opsi daftar
    # (-b/-c/-f atau --bytes/--characters/
    # --fields); penjaga 2579 di bawah melacak
    # kehadirannya untuk perintah cut.
    daftar_cut = False
    if perintah in ("sort", "head", "tail", "uniq",
                    "cut", "wc",
                    # Audit 2645: pembaca generik kini
                    # ikut dijaga kosakatanya.
                    "cat", "tee", "dd", "sha256sum",
                    "md5sum", "sha1sum", "sha224sum",
                    "sha384sum", "sha512sum", "b2sum",
                    "gzip", "gunzip", "zcat", "gzcat",
                    "base64", "base32", "od", "tac",
                    "fold", "paste", "fmt", "shuf",
                    "strings", "iconv", "cksum",
                    "split"):
        vokab_gu = _VOKAB_SK[perintah]
        # Audit 2666: penerima bertipe per perintah
        # (nama panjang dan huruf pendek -> jenis
        # nilai); nilai tak sah = mati saat urai.
        _TIPE66 = {
            "fmt": {"width": "angka0", "goal": "angka0",
                    "w": "angka0", "g": "angka0"},
            "fold": {"width": "angka1", "w": "angka1"},
            "strings": {"bytes": "angka1", "n": "angka1",
                        # Audit 2678: penerima enum
                        # strings yang tertinggal
                        # 2666.
                        "t": "enum-radix",
                        "radix": "enum-radix",
                        "e": "enum-encoding",
                        "encoding": "enum-encoding"},
            "split": {"lines": "angka1", "l": "angka1",
                      "bytes": "ukuran1", "b": "ukuran1",
                      "line-bytes": "ukuran1",
                      "C": "ukuran1",
                      "number": "nomor-split",
                      "n": "nomor-split", "a": "angka1",
                      # Audit 2679: nama panjang
                      # -a tertinggal dari tabel -
                      # bentuk panjangnya lolos
                      # tanpa validasi.
                      "suffix-length": "angka1"},
            "shuf": {"head-count": "angka0",
                     "n": "angka0"},
            "od": {"read-bytes": "odnum", "N": "odnum",
                   "skip-bytes": "odnum", "j": "odnum",
                   "S": "odnum", "width": "odnum1",
                   # Audit 2678: penerima format
                   # od yang tertinggal 2666.
                   "t": "format-od",
                   "format": "format-od"},
            "base64": {"wrap": "angka0", "w": "angka0"},
            "base32": {"wrap": "angka0", "w": "angka0"},
            "cksum": {"algorithm": "enum-cksum",
                      "a": "enum-cksum",
                      "length": "angka0", "l": "angka0"},
            "tee": {"output-error": "enum-tee"},
            "iconv": {"from-code": "charset",
                      "f": "charset",
                      "to-code": "charset",
                      "t": "charset"},
        }
        lewati_gu = 0
        for j_gu, t_gu in enumerate(toks[awal + 1:]):
            b_gu = t_gu.strip("\"'")
            if lewati_gu > 0:
                lewati_gu -= 1
                continue
            if b_gu == "--":
                break
            if (not b_gu.startswith("-")
                    or b_gu == "-"):
                continue
            if b_gu.startswith("--"):
                st_gu, _nm_gu = _status_panjang(
                    vokab_gu, b_gu)
                if st_gu == "info":
                    return False
                # Audit 2579: nama asing/ambigu
                # membunuh alat coreutils sebelum
                # membaca (getopt_long).
                if st_gu == "mati":
                    return False
                if st_gu == "pembawa":
                    lewati_gu = 1
                if (st_gu in ("pembawa", "pembawa=",
                              "pembawa2")
                        and perintah == "cut" and _nm_gu in (
                            "bytes", "characters", "fields")):
                    daftar_cut = True
                if (st_gu in ("pembawa", "pembawa=")
                        or (st_gu == "opsi"
                            and "=" in b_gu)):
                    # Bentuk opsi-opsional ber-= (tee
                    # --output-error, od --width):
                    # statusnya "opsi" tetapi nilai
                    # nempelnya tetap dinilai alat.
                    jenis66 = _TIPE66.get(
                        perintah, {}).get(_nm_gu)
                    if jenis66:
                        if (st_gu == "pembawa="
                                or st_gu == "opsi"):
                            nilai66 = b_gu.split(
                                "=", 1)[1]
                        elif awal + 2 + j_gu < len(toks):
                            nilai66 = toks[
                                awal + 2 + j_gu]
                        else:
                            nilai66 = None
                        if (nilai66 is not None
                                and not _tipe_nilai_sah_66(
                                    jenis66, nilai66)):
                            return False
                continue
            if (perintah in ("head", "tail")
                    and b_gu[1:].isdigit()):
                continue
            if (perintah in ("fmt", "fold", "strings",
                             "split")
                    and b_gu[1:].isdigit()
                    and len(b_gu[1:]) <= 18
                    and (perintah == "fmt"
                         or int(b_gu[1:]) > 0)):
                # Audit 2656: penerima angka warisan
                # -N (= -w fmt/fold, -n strings,
                # -l split) sah dan menguras di
                # biner; penjaga 2645 membunuhnya
                # sebagai klaster asing. fold/
                # strings/split menolak 0 dan
                # keempatnya menolak luapan -
                # bentuk itu jatuh ke klaster dan
                # tetap mati. od -10 memang mati
                # di biner (bukan penerima warisan).
                continue
            if (perintah == "od"
                    and re.fullmatch(r"-w\d{1,18}",
                                     b_gu)
                    and int(b_gu[2:]) > 0):
                # Audit 2677: lebar 0 dikecualikan
                # dari skip ini - biner tewas
                # untuk -w0, jadi bentuknya jatuh
                # ke klaster asing dan mati.
                # Audit 2659: -w od bernilai
                # OPSIONAL-nempel (-w10 sah dan
                # menguras; bentuk pisah tidak
                # menelan nilai - "10" jadi
                # operand berkas). 'w' hanya
                # huruf polos di kosakata, jadi
                # klaster -w<angka> terbunuh
                # sebagai asing. Luapan >18
                # digit tetap mati persis biner.
                continue
            st_gu, _ch_gu = _status_klaster(
                vokab_gu, b_gu)
            # Audit 2579: huruf pendek asing juga
            # membunuh alat sebelum membaca.
            if st_gu == "mati":
                return False
            if st_gu in ("pembawa", "pembawa-nempel"):
                if st_gu == "pembawa":
                    lewati_gu = 1
                if (perintah == "cut"
                        and _ch_gu in ("b", "c", "f")):
                    daftar_cut = True
                jenis66 = _TIPE66.get(
                    perintah, {}).get(_ch_gu)
                if jenis66:
                    if st_gu == "pembawa-nempel":
                        huruf66 = b_gu[1:]
                        pos66 = next(
                            i for i, c in enumerate(
                                huruf66)
                            if c in vokab_gu[
                                "pembawa_pendek"])
                        nilai66 = huruf66[pos66 + 1:]
                    elif awal + 2 + j_gu < len(toks):
                        nilai66 = toks[awal + 2 + j_gu]
                    else:
                        nilai66 = None
                    if (nilai66 is not None
                            and not _tipe_nilai_sah_66(
                                jenis66, nilai66)):
                        return False
    # Audit 2642: tanpa opsi daftar, cut mati
    # "you must specify a list of bytes,
    # characters, or fields" (rc=1) sebelum
    # membaca stdin (cut, cut -s,
    # cut --complement).
    if perintah == "cut" and not daftar_cut:
        return False
    # Audit 2643: aturan operand tr - tanpa set
    # sama sekali alat mati "missing operand";
    # satu set tanpa -d/-s mati "two strings
    # must be given when translating" (rc=1,
    # stdin tak dibaca). Set dihitung dari
    # operand sesudah opsi (klaster cCdst dan
    # nama panjangnya); redirect dilompati.
    if perintah == "tr":
        set_tr = 0
        d_tr = False
        s_tr = False
        lewati_tr = False
        opsi_tr = True
        for t_tr in toks[awal + 1:]:
            b_tr = t_tr.strip("\"'")
            if lewati_tr:
                lewati_tr = False
                continue
            if b_tr in (">", ">>", "1>", "2>", "1>>",
                        "2>>", "<"):
                lewati_tr = True
                continue
            if re.fullmatch(r"\d*>>?", b_tr):
                lewati_tr = True
                continue
            if re.match(r"\d*>>?\S", b_tr) \
                    or b_tr.startswith("<"):
                continue
            if opsi_tr and b_tr == "--":
                opsi_tr = False
                continue
            if opsi_tr and b_tr.startswith("--"):
                nm_tr = b_tr.split("=", 1)[0]
                # Audit 2662: semua opsi panjang
                # tr tak berargumen - bentuk =
                # membunuh alat, dan nama yang
                # tak terselesaikan ke satu opsi
                # dikenal juga membunuh ("unrecog-
                # nized option", rc=1); pindaian
                # 2643 dulu melewatkan keduanya.
                if "=" in b_tr:
                    return False
                if "=" not in b_tr:
                    # Audit 2660: getopt_long
                    # menyelesaikan singkatan unik
                    # (--del = --delete) - pindaian
                    # 2643 dulu hanya cocok eksak
                    # sehingga singkatan sah tak
                    # menandai bendera dan vonis
                    # berbalik mati (fail-open).
                    calon_tr = [x for x in (
                        "--delete", "--squeeze-repeats",
                        "--complement",
                        "--truncate-set1")
                        if x.startswith(nm_tr)]
                    if len(calon_tr) == 1:
                        nm_tr = calon_tr[0]
                    elif nm_tr not in (
                            "--help", "--version"):
                        return False
                if nm_tr == "--delete":
                    d_tr = True
                elif nm_tr == "--squeeze-repeats":
                    s_tr = True
                continue
            if opsi_tr and b_tr.startswith("-") \
                    and b_tr != "-":
                for ch_tr in b_tr[1:]:
                    if ch_tr == "d":
                        d_tr = True
                    elif ch_tr == "s":
                        s_tr = True
                    elif ch_tr not in "cCt":
                        # Audit 2662: huruf pendek
                        # asing membunuh tr
                        # ("invalid option", rc=1).
                        return False
                continue
            set_tr += 1
        if set_tr == 0 or (set_tr == 1
                           and not d_tr and not s_tr):
            return False
    # Audit 1102: panggilan informasional mencetak dan keluar
    # tanpa membaca stdin (--help/--version umum; iconv -l/--list).
    # Audit 2591: penyusuran sadar nilai (pola Audit 1690) -
    # --help/--version yang menjadi NILAI opsi bernilai perintah
    # itu (mis. sort --output --help) bukan panggilan bantuan;
    # alatnya tetap membaca stdin.
    if perintah in _PEMBACA_STDIN:
        bernilai_umum = _OPSI_BERNILAI.get(perintah, set())
        lewati_umum = 0
        for t_umum in toks[awal + 1:]:
            b_umum = t_umum.strip("\"'")
            if lewati_umum > 0:
                lewati_umum -= 1
                continue
            if b_umum == "--":
                break
            if b_umum in ("--help", "--version"):
                return False
            if b_umum in bernilai_umum:
                lewati_umum = 1
            elif (len(b_umum) > 2 and b_umum.startswith("-")
                    and not b_umum.startswith("--")):
                # Audit 2622: bentuk klaster - huruf
                # bernilai pertama menentukan: di ujung
                # klaster ia menelan token berikutnya
                # (sort -ro --help: --help nilai -o);
                # di tengah, ekor klaster adalah nilai
                # nempelnya (sort -or: --help bebas).
                for idx_kl, ch_kl in enumerate(b_umum[1:]):
                    if "-" + ch_kl in bernilai_umum:
                        if idx_kl == len(b_umum) - 2:
                            lewati_umum = 1
                        break
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
    # Audit 2716: operand masukan split sudah
    # terlihat (operand keduanya awalan keluaran).
    split_sudah_operand = False
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
                    # Audit 2665: tata bahasa operand.
                    if not _dd_operand_sah(awalan):
                        return False
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
            # Audit 2716: '-' adalah operand
            # masukan split - tandai terpakai.
            if perintah == "split":
                split_sudah_operand = True
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
                # Audit 2665: tata bahasa operand.
                if not _dd_operand_sah(t):
                    return False
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
        # Audit 2716: split - operand pertama
        # adalah masukan (berkas = tak membaca
        # stdin, vonis di sini); operand kedua
        # adalah awalan berkas keluaran, bukan
        # masukan, jadi tak memutuskan vonis.
        if perintah == "split":
            if not split_sudah_operand:
                split_sudah_operand = True
                return False
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

def jalan_longgar(*args):
    args = list(args)
    if args and args[0] == "git":
        args = ["git", "-c", "core.quotepath=false"] + args[1:]
    try:
        hasil = subprocess.run(
            args, cwd=AKAR, capture_output=True, text=True,
            encoding="utf-8", errors="replace", timeout=60)
    except Exception:
        return None
    return hasil.stdout

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

    pola_def = re.compile(r'<(string-array|integer-array|array|style|string|color|dimen|plurals)(?=[\s/>])[^>]*?\bname="([^"]+)"')
    JENIS_ARRAY = ("string-array", "integer-array", "array")

    def tambah(kunci, nama):
        definisi.setdefault(kunci, set()).add(rapikan(kunci, nama))

    akar_res = os.path.join(MAIN, "res")
    for path in jalan_repo(os.path.join("app", "src", "main", "res"), ext=(".xml",)):
        isi = kupas_komentar_xml(baca(path))
        for jenis, nama in pola_def.findall(isi):
            tambah("array" if jenis in JENIS_ARRAY else jenis, nama)
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

    JENIS_XML_SPESIFIK = ("style", "string", "color", "dimen", "array",
                          "drawable", "id", "plurals")
    pola_xml_gabung = re.compile(r"@([a-z]+)/([A-Za-z0-9_.]+)")
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
        for tipe, nama in pola_xml_gabung.findall(isi):
            acuan.setdefault(tipe, set()).add(rapikan(tipe, nama))
            if tipe != "style" and tipe in JENIS_XML_SPESIFIK:
                nama_pendek = nama.split(".", 1)[0]
                if nama_pendek:
                    acuan[tipe].add(nama_pendek)
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

def _buka_kutip_git(path):
    """Audit 2568: buka quoting gaya-C git. Nama
    berkas beraksara kontrol/kutip/backslash
    selalu ter-quote ("Tab\\tNama.java") walau
    core.quotepath=false - quotepath hanya
    mengatur byte non-ASCII. Escape di dalamnya:
    \\t \\n \\" \\\\ dan oktal \\NNN per byte
    UTF-8. Path tanpa kutip lewat apa adanya."""
    if (len(path) < 2 or not path.startswith('"')
            or not path.endswith('"')):
        return path
    dalam = path[1:-1]
    hasil = bytearray()
    i = 0
    while i < len(dalam):
        c = dalam[i]
        if c == "\\" and i + 1 < len(dalam):
            n = dalam[i + 1]
            if n == "t":
                hasil.append(9)
                i += 2
            elif n == "n":
                hasil.append(10)
                i += 2
            elif n == '"':
                hasil.append(34)
                i += 2
            elif n == "\\":
                hasil.append(92)
                i += 2
            elif n.isdigit():
                j = i + 1
                while (j < len(dalam) and j < i + 4
                       and dalam[j].isdigit()):
                    j += 1
                hasil.append(int(dalam[i + 1:j], 8) & 0xFF)
                i = j
            else:
                hasil.extend(c.encode("utf-8"))
                i += 1
        else:
            hasil.extend(c.encode("utf-8"))
            i += 1
    return hasil.decode("utf-8", "surrogateescape")


def _path_hilang_name_status(out):
    """Audit 2546: path yang hilang dari keluaran
    git diff --name-status - baris D (pathnya
    sendiri) dan baris R (path LAMA sebuah
    rename ikut hilang dari pohon; salinan C
    tidak menghilangkan sumbernya)."""
    hilang = []
    for x in (out or "").splitlines():
        if x.startswith("D"):
            hilang.append(_buka_kutip_git(x.split(None, 1)[1]))
        elif x.startswith("R"):
            kolom = x.split("\t")
            if len(kolom) >= 3:
                hilang.append(_buka_kutip_git(kolom[1]))
    return hilang


def berkas_dihapus(rentang):
    r = rentang_normal(rentang)
    if r is not None:
        out = jalan("git", "diff", "--name-status", r[0] + r[1] + r[2])
        if out is None:
            return None
        return _path_hilang_name_status(out)
    hapus = set()
    for args in (("git", "diff", "--name-status"),
                 ("git", "diff", "--name-status", "--cached")):
        out = jalan(*args)
        hapus.update(_path_hilang_name_status(out))
    # Penghapusan yang sudah ter-commit lokal tetapi belum ter-push tak
    # terlihat diff working-tree/staged (Audit 763): ikut sertakan rentang
    # commit lokal terhadap upstream (atau origin/main sebagai cadangan).
    for acuan in ("@{upstream}", "origin/main"):
        out = jalan("git", "diff", "--name-status", acuan + "..HEAD")
        if out is not None:
            hapus.update(_path_hilang_name_status(out))
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
        cek = jalan_longgar("git", "diff", "--check", r[0] + "..." + r[2])
    else:
        cek = jalan_longgar("git", "diff", "--check")
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
    parser.add_argument("--hanya-workflow", action="store_true",
                        help="hanya cek YAML .github (skenario generate suite)")
    args = parser.parse_args()

    if args.hanya_workflow:
        gagal = []
        peringatan = []
        cek_workflow_yaml(gagal, peringatan)
        cek_yaml_github_lain(gagal, peringatan)
        for w in peringatan:
            print("PERINGATAN: " + w)
            anotasi("warning", w)
        if gagal:
            for g in gagal:
                print("GAGAL: " + g)
                anotasi("error", g)
            print("Hasil: GAGAL (%d) — perbaiki sebelum push." % len(gagal))
            return 1
        print("Hasil: LOLOS (hanya YAML .github).")
        return 0

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
        if hapus is None:
            gagal.append("Daftar berkas dihapus tak terbaca: git diff "
                         "--name-status gagal untuk rentang — cek hapusan "
                         "tidak bisa dijalankan")
            hapus = []
    elif rentang_cek:
        hapus_rentang = berkas_dihapus(rentang_cek)
        hapus_lokal = berkas_dihapus("")
        if hapus_rentang is None or hapus_lokal is None:
            gagal.append("Daftar berkas dihapus tak terbaca: git diff "
                         "--name-status gagal untuk rentang — cek hapusan "
                         "tidak bisa dijalankan")
        hapus = sorted(set(hapus_rentang or [])
                       | set(hapus_lokal or []))
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
