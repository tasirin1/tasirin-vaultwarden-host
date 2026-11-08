#!/usr/bin/env python3
"""Uji-diri gerbang cek-cepat (Audit 830).

Alasan yang sama dengan tools/uji-verifikasi.py (Audit 782): di CI,
cek-cepat hanya pernah berjalan pada pohon bersih, jadi yang teruji
hanya cabang "tidak ada temuan" — cabang deteksinya (pola rahasia, token
terbelah, vektor tanpa nol depan) tidak pernah terpicu dan regresinya bisa mengubah skrip itu
menjadi cap lolos exit-0 secara diam-diam. Skrip ini menanam pelanggaran
pada salinan buang pohon kerja (di-commit sebagai snapshot git lokal;
pohon asli tak disentuh) dan mengasersi kode keluar + pesan cek-cepat.
Sejak Audit 831, cek layout dan berkas biner/nama terlarang dimiliki
satu pintu oleh verifikasi-patch (skenarionya hidup di
tools/uji-verifikasi.py); skrip ini mengunci cabang milik cek-cepat
sendiri: pola rahasia (satu baris dan terbelah antar-baris, cabang
bungkus `tr -d` di cek-cepat), vektor tanpa nol depan (Audit 841), dan
struktur judul README 3 bahasa (Audit 868).

Jalankan: python3 tools/uji-cek-cepat.py (berhenti dengan kode 1 bila
ada skenario yang menyimpang).
"""

import os
import shutil
import sys
import tempfile

# Audit 1460: helper jalan/git/klon/periksa satu pintu di
# tools/uji_bersama.py (dulu dua salinan nyaris identik di sini
# dan di uji-verifikasi.py).
import uji_bersama
from uji_bersama import GAGAL, git, jalan, periksa

AKAR = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SKRIP = os.path.join("scripts", "cek-cepat.sh")

_SNAPSHOT = uji_bersama.Snapshot()


def klon(tmp, nama):
    return uji_bersama.klon(tmp, nama, AKAR, "Uji Cek Cepat", _SNAPSHOT)


def tanam(repo, rel, isi, mode="wb"):
    path = os.path.join(repo, rel)
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, mode) as f:
        f.write(isi)
    git(repo, "add", "-A")
    git(repo, "commit", "-q", "-m", "tanam pelanggaran uji")


def main():
    with tempfile.TemporaryDirectory(prefix="uji-cek-cepat-") as tmp:
        bersih = klon(tmp, "bersih")
        rc, out = jalan(bersih, ["bash", SKRIP])
        periksa("kontrol: pohon bersih lolos", rc == 0,
                "" if rc == 0 else out[-400:])
        shutil.rmtree(bersih, ignore_errors=True)




        # Audit 1459: token_palsu dipakai banyak blok skenario —
        # definisikan sekali di sini, bukan di dalam blok rahasia
        # (menghapus blok itu dulu merusak empat blok lain).
        token_palsu = "123456789" + ":" + "AA" + "Ab3_" * 10

        rahasia = klon(tmp, "rahasia")
        tanam(rahasia, "catatan-token.txt",
              "token uji: " + token_palsu + "\n", "w")
        rc, out = jalan(rahasia, ["bash", SKRIP])
        periksa("pola token rahasia tertangkap",
                rc != 0 and "pola rahasia" in out)
        shutil.rmtree(rahasia, ignore_errors=True)

        terbelah = klon(tmp, "terbelah")
        paruh = len(token_palsu) // 2
        tanam(terbelah, "catatan-token-terbelah.txt",
              "token uji: " + token_palsu[:paruh] + "\n"
              + token_palsu[paruh:] + "\n", "w")
        rc, out = jalan(terbelah, ["bash", SKRIP])
        periksa("token terbelah dua baris tertangkap (cabang bungkus)",
                rc != 0 and "pola rahasia" in out)
        shutil.rmtree(terbelah, ignore_errors=True)

        tokenjeda = klon(tmp, "tokenjeda")
        tanam(tokenjeda, "catatan-token-jeda.txt",
              "token uji: " + token_palsu[:paruh] + "\n"
              "# jeda\n"
              + token_palsu[paruh:] + "\n", "w")
        rc, out = jalan(tokenjeda, ["bash", SKRIP])
        periksa("token terbelah tersela baris komentar tertangkap "
                "(Audit 1506)",
                rc != 0 and "pola rahasia" in out)
        shutil.rmtree(tokenjeda, ignore_errors=True)

        readme = klon(tmp, "readme")
        berkas_rm = os.path.join(readme, "README.id.md")
        with open(berkas_rm, encoding="utf-8") as f:
            isi_rm = f.read()
        with open(berkas_rm, "w", encoding="utf-8") as f:
            f.write(isi_rm.replace("\n## ", "\n### ", 1))
        rc, out = jalan(readme, ["bash", SKRIP])
        periksa("struktur judul README beda: cek-cepat gagal (Audit 868)",
                rc != 0 and "struktur judul" in out)
        shutil.rmtree(readme, ignore_errors=True)

        vektor = klon(tmp, "vektor")
        tanam(vektor, "app/src/main/res/drawable/ic_uji_vektor.xml",
              "<vector xmlns:android="
              "\"http://schemas.android.com/apk/res/android\""
              " android:width=\"24dp\" android:height=\"24dp\""
              " android:viewportWidth=\"24\" android:viewportHeight=\"24\">"
              "<path android:fillColor=\"#000000\""
              " android:pathData=\"M.9,5 h5 v5 z\" /></vector>\n", "w")
        rc, out = jalan(vektor, ["bash", SKRIP])
        periksa("path vektor tanpa nol depan tertangkap",
                rc != 0 and "tanpa nol depan" in out)

        terabaikan = klon(tmp, "terabaikan")
        tanam(terabaikan, ".gitignore", "rahasia-terabaikan.txt\n", "w")
        with open(os.path.join(terabaikan, "rahasia-terabaikan.txt"),
                  "w") as f:
            f.write("token uji: " + token_palsu + "\n")
        rc, out = jalan(terabaikan, ["bash", SKRIP])
        periksa("token di berkas ter-.gitignore tertangkap (Audit 1436)",
                rc != 0 and "pola rahasia" in out
                and "rahasia-terabaikan.txt" in out)
        shutil.rmtree(terabaikan, ignore_errors=True)

        kunciterbelah = klon(tmp, "kunciterbelah")
        tanam(kunciterbelah, "kunci-terbelah.txt",
              "-----BEGIN OPENSSH\nPRIVATE KEY-----\n"
              "AAAAB3NzaC1yc2EAAAADAQABAAABAQC7uji\n", "w")
        rc, out = jalan(kunciterbelah, ["bash", SKRIP])
        periksa("header private key terbelah tertangkap "
                "(cabang bungkus) (Audit 1437)",
                rc != 0 and "pola rahasia" in out
                and "kunci-terbelah.txt" in out)
        shutil.rmtree(kunciterbelah, ignore_errors=True)

        kuncitersela = klon(tmp, "kuncitersela")
        tanam(kuncitersela, "kunci-tersela.txt",
              "-----BEGIN OPENSSH\nsisipan komentar lain\n"
              "PRIVATE KEY-----\n"
              "AAAAB3NzaC1yc2EAAAADAQABAAABAQC7uji\n", "w")
        rc, out = jalan(kuncitersela, ["bash", SKRIP])
        periksa("header terbelah tersela baris lain tertangkap "
                "(Audit 1456)",
                rc != 0 and "pola rahasia" in out
                and "kunci-tersela.txt" in out)
        shutil.rmtree(kuncitersela, ignore_errors=True)

        kuncikomentar = klon(tmp, "kuncikomentar")
        tanam(kuncikomentar, "kunci-komentar.txt",
              "-----BEGIN OPENSSH\n# catatan penting\n"
              "PRIVATE KEY-----\n"
              "AAAAB3NzaC1yc2EAAAADAQABAAABAQC7uji\n", "w")
        rc, out = jalan(kuncikomentar, ["bash", SKRIP])
        periksa("header tersela baris komentar tertangkap "
                "(Audit 1505)",
                rc != 0 and "pola rahasia" in out
                and "kunci-komentar.txt" in out)
        shutil.rmtree(kuncikomentar, ignore_errors=True)

        kuncitengah = klon(tmp, "kuncitengah")
        tanam(kuncitengah, "kunci-tengah.txt",
              "-----BEGIN RSA PRIVATE\nxyz123\n"
              "KEY-----\n"
              "AAAAB3NzaC1yc2EAAAADAQABAAABAQC7uji\n", "w")
        rc, out = jalan(kuncitengah, ["bash", SKRIP])
        periksa("header terbelah di tengah frasa tertangkap "
                "(Audit 1505)",
                rc != 0 and "pola rahasia" in out
                and "kunci-tengah.txt" in out)
        shutil.rmtree(kuncitengah, ignore_errors=True)

        kuncipanjang = klon(tmp, "kuncipanjang")
        tanam(kuncipanjang, "kunci-panjang.txt",
              "-----BEGIN " + "a" * 250 + " PRIVATE KEY-----\n"
              "AAAAB3NzaC1yc2EAAAADAQABAAABAQC7uji\n", "w")
        rc, out = jalan(kuncipanjang, ["bash", SKRIP])
        periksa("header label huruf kecil panjang tertangkap "
                "(Audit 1505)",
                rc != 0 and "pola rahasia" in out
                and "kunci-panjang.txt" in out)
        shutil.rmtree(kuncipanjang, ignore_errors=True)

        kuncitab = klon(tmp, "kuncitab")
        tanam(kuncitab, "kunci-tab.txt",
              "-----BEGIN OPENSSH\tPRIVATE KEY-----\n"
              "AAAAB3NzaC1yc2EAAAADAQABAAABAQC7uji\n", "w")
        rc, out = jalan(kuncitab, ["bash", SKRIP])
        periksa("header private key ber-TAB tertangkap (Audit 1455)",
                rc != 0 and "pola rahasia" in out
                and "kunci-tab.txt" in out)
        shutil.rmtree(kuncitab, ignore_errors=True)

        namabarisbaru = klon(tmp, "namabarisbaru")
        paruh2 = len(token_palsu) // 2
        tanam(namabarisbaru, "token\nterbelah.txt",
              "token uji: " + token_palsu[:paruh2] + "\n"
              + token_palsu[paruh2:] + "\n", "w")
        rc, out = jalan(namabarisbaru, ["bash", SKRIP])
        periksa("token terbelah di berkas bernama newline tertangkap "
                "(Audit 1454)",
                rc != 0 and "pola rahasia" in out)
        shutil.rmtree(namabarisbaru, ignore_errors=True)

        readmehilang = klon(tmp, "readmehilang")
        os.remove(os.path.join(readmehilang, "README.id.md"))
        tanam(readmehilang, "catatan-token.txt",
              "token uji: " + token_palsu + "\n", "w")
        rc, out = jalan(readmehilang, ["bash", SKRIP])
        periksa("README hilang: skrip tetap memindai rahasia "
                "(Audit 1438)",
                rc == 1 and "berkas wajib hilang" in out
                and "pola rahasia" in out)
        shutil.rmtree(readmehilang, ignore_errors=True)

        takterlacak = klon(tmp, "takterlacak")
        with open(os.path.join(takterlacak, "catatan-baru.txt"),
                  "w") as f:
            f.write("token uji: " + token_palsu + "\n")
        rc, out = jalan(takterlacak, ["bash", SKRIP])
        periksa("token di berkas tak terlacak tertangkap (Audit 1439)",
                rc != 0 and "pola rahasia" in out
                and "catatan-baru.txt" in out)
        shutil.rmtree(takterlacak, ignore_errors=True)

        tokenparuhpagar = klon(tmp, "tokenparuhpagar")
        tanam(tokenparuhpagar, "token-paruh-pagar.txt",
              "warna #fff token: " + token_palsu[:paruh] + "\n"
              + token_palsu[paruh:] + "\n", "w")
        rc, out = jalan(tokenparuhpagar, ["bash", SKRIP])
        periksa("token terbelah dengan paruh sesudah '#' sebaris "
                "tertangkap (Audit 1526)",
                rc != 0 and "pola rahasia" in out
                and "token-paruh-pagar.txt" in out)
        shutil.rmtree(tokenparuhpagar, ignore_errors=True)

        kuncimulaipagar = klon(tmp, "kuncimulaipagar")
        tanam(kuncimulaipagar, "kunci-mulai-pagar.txt",
              "# x -----BEGIN OPENSSH\n"
              "PRIVATE KEY-----\n"
              "AAAAB3NzaC1yc2EAAAADAQABAAABAQC7uji\n", "w")
        rc, out = jalan(kuncimulaipagar, ["bash", SKRIP])
        periksa("header diawali baris '#' tertangkap (Audit 1526)",
                rc != 0 and "pola rahasia" in out
                and "kunci-mulai-pagar.txt" in out)
        shutil.rmtree(kuncimulaipagar, ignore_errors=True)

        tokenparuhkomentar = klon(tmp, "tokenparuhkomentar")
        tanam(tokenparuhkomentar, "token-paruh-komentar.txt",
              "token uji: " + token_palsu[:paruh] + "\n"
              "# " + token_palsu[paruh:] + "\n", "w")
        rc, out = jalan(tokenparuhkomentar, ["bash", SKRIP])
        periksa("paruh token di dalam baris komentar tertangkap "
                "(Audit 1527)",
                rc != 0 and "pola rahasia" in out
                and "token-paruh-komentar.txt" in out)
        shutil.rmtree(tokenparuhkomentar, ignore_errors=True)

        tokenparuhjarak = klon(tmp, "tokenparuhjarak")
        tanam(tokenparuhjarak, "token-paruh-jarak.txt",
              "token uji: " + token_palsu[:paruh] + "\n"
              "   # " + token_palsu[paruh:] + "\n", "w")
        rc, out = jalan(tokenparuhjarak, ["bash", SKRIP])
        periksa("paruh token di baris komentar berindentasi "
                "tertangkap (Audit 1527)",
                rc != 0 and "pola rahasia" in out
                and "token-paruh-jarak.txt" in out)
        shutil.rmtree(tokenparuhjarak, ignore_errors=True)

        tokentergabung = klon(tmp, "tokentergabung")
        tanam(tokentergabung, "token-tergabung-pagar.txt",
              "token uji: " + token_palsu[:paruh] + "#"
              + token_palsu[paruh:] + "\n", "w")
        rc, out = jalan(tokentergabung, ["bash", SKRIP])
        periksa("token tergabung '#' sebaris tertangkap "
                "(Audit 1527)",
                rc != 0 and "pola rahasia" in out
                and "token-tergabung-pagar.txt" in out)
        shutil.rmtree(tokentergabung, ignore_errors=True)

        tokenselabintang = klon(tmp, "tokenselabintang")
        tanam(tokenselabintang, "token-sela-bintang.txt",
              "token uji: " + token_palsu[:paruh] + "\n"
              "***\n"
              + token_palsu[paruh:] + "\n", "w")
        rc, out = jalan(tokenselabintang, ["bash", SKRIP])
        periksa("token tersela baris tanda bintang tertangkap "
                "(Audit 1528)",
                rc != 0 and "pola rahasia" in out
                and "token-sela-bintang.txt" in out)
        shutil.rmtree(tokenselabintang, ignore_errors=True)

        kunciselatitik = klon(tmp, "kunciselatitik")
        tanam(kunciselatitik, "kunci-sela-titik.txt",
              "-----BEGIN OPENSSH\n...\n"
              "PRIVATE KEY-----\n"
              "AAAAB3NzaC1yc2EAAAADAQABAAABAQC7uji\n", "w")
        rc, out = jalan(kunciselatitik, ["bash", SKRIP])
        periksa("header tersela baris titik-titik tertangkap "
                "(Audit 1528)",
                rc != 0 and "pola rahasia" in out
                and "kunci-sela-titik.txt" in out)
        shutil.rmtree(kunciselatitik, ignore_errors=True)

        kunciselamiring = klon(tmp, "kunciselamiring")
        tanam(kunciselamiring, "kunci-sela-miring.txt",
              "-----BEGIN OPENSSH\n// jeda\n"
              "PRIVATE KEY-----\n"
              "AAAAB3NzaC1yc2EAAAADAQABAAABAQC7uji\n", "w")
        rc, out = jalan(kunciselamiring, ["bash", SKRIP])
        periksa("header tersela komentar '//' tertangkap "
                "(Audit 1528)",
                rc != 0 and "pola rahasia" in out
                and "kunci-sela-miring.txt" in out)
        shutil.rmtree(kunciselamiring, ignore_errors=True)

        tokenselamiring = klon(tmp, "tokenselamiring")
        tanam(tokenselamiring, "token-sela-miring.txt",
              "token uji: " + token_palsu[:paruh] + "\n"
              "// jeda\n"
              + token_palsu[paruh:] + "\n", "w")
        rc, out = jalan(tokenselamiring, ["bash", SKRIP])
        periksa("token tersela komentar '//' tertangkap "
                "(Audit 1528)",
                rc != 0 and "pola rahasia" in out
                and "token-sela-miring.txt" in out)
        shutil.rmtree(tokenselamiring, ignore_errors=True)

        kunciselaakhir = klon(tmp, "kunciselaakhir")
        tanam(kunciselaakhir, "kunci-sela-akhir.txt",
              "-----BEGIN RSA PRIVATE KEY\nxyz\n"
              "-----\n"
              "AAAAB3NzaC1yc2EAAAADAQABAAABAQC7uji\n", "w")
        rc, out = jalan(kunciselaakhir, ["bash", SKRIP])
        periksa("header tersela antara KEY dan '-----' tertangkap "
                "(Audit 1529)",
                rc != 0 and "pola rahasia" in out
                and "kunci-sela-akhir.txt" in out)
        shutil.rmtree(kunciselaakhir, ignore_errors=True)

        tokenselaseru = klon(tmp, "tokenselaseru")
        tanam(tokenselaseru, "token-sela-seru.txt",
              "token uji: " + token_palsu[:paruh] + "\n"
              "# hati-hati!\n"
              + token_palsu[paruh:] + "\n", "w")
        rc, out = jalan(tokenselaseru, ["bash", SKRIP])
        periksa("token tersela '# hati-hati!' tertangkap "
                "(Audit 1543)",
                rc != 0 and "pola rahasia" in out
                and "token-sela-seru.txt" in out)
        shutil.rmtree(tokenselaseru, ignore_errors=True)

        tokenselakolon = klon(tmp, "tokenselakolon")
        tanam(tokenselakolon, "token-sela-kolon.txt",
              "token uji: " + token_palsu[:paruh] + "\n"
              "# catatan: penting\n"
              + token_palsu[paruh:] + "\n", "w")
        rc, out = jalan(tokenselakolon, ["bash", SKRIP])
        periksa("token tersela '# catatan: penting' tertangkap "
                "(Audit 1543)",
                rc != 0 and "pola rahasia" in out
                and "token-sela-kolon.txt" in out)
        shutil.rmtree(tokenselakolon, ignore_errors=True)

        tokenselablok = klon(tmp, "tokenselablok")
        tanam(tokenselablok, "token-sela-blok.txt",
              "token uji: " + token_palsu[:paruh] + "\n"
              "/* jeda */\n"
              + token_palsu[paruh:] + "\n", "w")
        rc, out = jalan(tokenselablok, ["bash", SKRIP])
        periksa("token tersela komentar blok tertangkap "
                "(Audit 1543)",
                rc != 0 and "pola rahasia" in out
                and "token-sela-blok.txt" in out)
        shutil.rmtree(tokenselablok, ignore_errors=True)

        tokenselakoma = klon(tmp, "tokenselakoma")
        tanam(tokenselakoma, "token-sela-koma.txt",
              "token uji: " + token_palsu[:paruh] + "\n"
              "; jeda\n"
              + token_palsu[paruh:] + "\n", "w")
        rc, out = jalan(tokenselakoma, ["bash", SKRIP])
        periksa("token tersela '; jeda' tertangkap "
                "(Audit 1543)",
                rc != 0 and "pola rahasia" in out
                and "token-sela-koma.txt" in out)
        shutil.rmtree(tokenselakoma, ignore_errors=True)

        tokenparuhseru = klon(tmp, "tokenparuhseru")
        tanam(tokenparuhseru, "token-paruh-seru.txt",
              "token uji: " + token_palsu[:paruh] + " # hati-hati!\n"
              + token_palsu[paruh:] + "\n", "w")
        rc, out = jalan(tokenparuhseru, ["bash", SKRIP])
        periksa("token paruh-1 diakhiri komentar sebaris tertangkap "
                "(Audit 1543)",
                rc != 0 and "pola rahasia" in out
                and "token-paruh-seru.txt" in out)
        shutil.rmtree(tokenparuhseru, ignore_errors=True)

        tokensebarisbintang = klon(tmp, "tokensebarisbintang")
        tanam(tokensebarisbintang, "token-sebaris-bintang.txt",
              "token uji: " + token_palsu[:paruh] + " *** "
              + token_palsu[paruh:] + "\n", "w")
        rc, out = jalan(tokensebarisbintang, ["bash", SKRIP])
        periksa("token dua paruh sebaris dipisah ' *** ' tertangkap "
                "(Audit 1543)",
                rc != 0 and "pola rahasia" in out
                and "token-sebaris-bintang.txt" in out)
        shutil.rmtree(tokensebarisbintang, ignore_errors=True)

        kunciselastrip = klon(tmp, "kunciselastrip")
        tanam(kunciselastrip, "kunci-sela-strip.txt",
              "-----BEGIN OPENSSH\n-----\n"
              "PRIVATE KEY-----\n"
              "AAAAB3NzaC1yc2EAAAADAQABAAABAQC7uji\n", "w")
        rc, out = jalan(kunciselastrip, ["bash", SKRIP])
        periksa("header tersela baris '-----' persis tertangkap "
                "(Audit 1544)",
                rc != 0 and "pola rahasia" in out
                and "kunci-sela-strip.txt" in out)
        shutil.rmtree(kunciselastrip, ignore_errors=True)

        kunciselastrip2 = klon(tmp, "kunciselastrip2")
        tanam(kunciselastrip2, "kunci-sela-strip2.txt",
              "-----BEGIN OPENSSH\n ----- \n"
              "PRIVATE KEY-----\n"
              "AAAAB3NzaC1yc2EAAAADAQABAAABAQC7uji\n", "w")
        rc, out = jalan(kunciselastrip2, ["bash", SKRIP])
        periksa("header tersela baris ' ----- ' berspasi tertangkap "
                "(Audit 1544)",
                rc != 0 and "pola rahasia" in out
                and "kunci-sela-strip2.txt" in out)
        shutil.rmtree(kunciselastrip2, ignore_errors=True)

        kunciselagarisbawah = klon(tmp, "kunciselagarisbawah")
        tanam(kunciselagarisbawah, "kunci-sela-garisbawah.txt",
              "-----BEGIN OPENSSH\njeda_sesaat\n"
              "PRIVATE KEY-----\n"
              "AAAAB3NzaC1yc2EAAAADAQABAAABAQC7uji\n", "w")
        rc, out = jalan(kunciselagarisbawah, ["bash", SKRIP])
        periksa("header tersela kata bergaris bawah tertangkap "
                "(Audit 1544)",
                rc != 0 and "pola rahasia" in out
                and "kunci-sela-garisbawah.txt" in out)
        shutil.rmtree(kunciselagarisbawah, ignore_errors=True)

        kunciseladuastrip = klon(tmp, "kunciseladuastrip")
        tanam(kunciseladuastrip, "kunci-sela-duastrip.txt",
              "-----BEGIN OPENSSH\n-- jeda\n"
              "PRIVATE KEY-----\n"
              "AAAAB3NzaC1yc2EAAAADAQABAAABAQC7uji\n", "w")
        rc, out = jalan(kunciseladuastrip, ["bash", SKRIP])
        periksa("header tersela baris '-- jeda' tertangkap "
                "(Audit 1544)",
                rc != 0 and "pola rahasia" in out
                and "kunci-sela-duastrip.txt" in out)
        shutil.rmtree(kunciseladuastrip, ignore_errors=True)

        kuncisebaristik = klon(tmp, "kuncisebaristik")
        tanam(kuncisebaristik, "kunci-sebaris-titik.txt",
              "-----BEGIN OPENSSH ... PRIVATE KEY-----\n"
              "AAAAB3NzaC1yc2EAAAADAQABAAABAQC7uji\n", "w")
        rc, out = jalan(kuncisebaristik, ["bash", SKRIP])
        periksa("header tersela ' ... ' sebaris tertangkap "
                "(Audit 1543)",
                rc != 0 and "pola rahasia" in out
                and "kunci-sebaris-titik.txt" in out)
        shutil.rmtree(kuncisebaristik, ignore_errors=True)

        kuncibullet = klon(tmp, "kuncibullet")
        tanam(kuncibullet, "kunci-bullet.txt",
              "* -----BEGIN OPENSSH PRIVATE KEY-----\n"
              "AAAAB3NzaC1yc2EAAAADAQABAAABAQC7uji\n", "w")
        rc, out = jalan(kuncibullet, ["bash", SKRIP])
        periksa("header utuh di baris bullet '*' tertangkap "
                "(Audit 1547)",
                rc != 0 and "pola rahasia" in out
                and "kunci-bullet.txt" in out)
        shutil.rmtree(kuncibullet, ignore_errors=True)

        kunciblok = klon(tmp, "kunciblok")
        tanam(kunciblok, "kunci-blok.txt",
              "/*\n * -----BEGIN OPENSSH PRIVATE KEY-----\n */\n"
              "AAAAB3NzaC1yc2EAAAADAQABAAABAQC7uji\n", "w")
        rc, out = jalan(kunciblok, ["bash", SKRIP])
        periksa("header utuh di baris lanjutan komentar blok "
                "tertangkap (Audit 1547)",
                rc != 0 and "pola rahasia" in out
                and "kunci-blok.txt" in out)
        shutil.rmtree(kunciblok, ignore_errors=True)

        kuncibloksebaris = klon(tmp, "kuncibloksebaris")
        tanam(kuncibloksebaris, "kunci-blok-sebaris.txt",
              "/* -----BEGIN OPENSSH PRIVATE KEY----- */\n"
              "AAAAB3NzaC1yc2EAAAADAQABAAABAQC7uji\n", "w")
        rc, out = jalan(kuncibloksebaris, ["bash", SKRIP])
        periksa("header utuh sebaris sesudah '/*' tertangkap "
                "(Audit 1547)",
                rc != 0 and "pola rahasia" in out
                and "kunci-blok-sebaris.txt" in out)
        shutil.rmtree(kuncibloksebaris, ignore_errors=True)

        tokenbelahduaa = klon(tmp, "tokenbelahduaa")
        tanam(tokenbelahduaa, "token-belah-dua-a.txt",
              "token uji: " + token_palsu[:10] + " \n"
              + token_palsu[10:] + "\n", "w")
        rc, out = jalan(tokenbelahduaa, ["bash", SKRIP])
        periksa("token terbelah persis sesudah ':' tertangkap "
                "(Audit 1552)",
                rc != 0 and "pola rahasia" in out
                and "token-belah-dua-a.txt" in out)
        shutil.rmtree(tokenbelahduaa, ignore_errors=True)

        tokenbelahduab = klon(tmp, "tokenbelahduab")
        tanam(tokenbelahduab, "token-belah-dua-b.txt",
              "token uji: " + token_palsu[:7] + "\n"
              + token_palsu[7:9] + ": " + token_palsu[10:] + "\n",
              "w")
        rc, out = jalan(tokenbelahduab, ["bash", SKRIP])
        periksa("token terbelah dengan ':' di awal baris kedua "
                "tertangkap (Audit 1552)",
                rc != 0 and "pola rahasia" in out
                and "token-belah-dua-b.txt" in out)
        shutil.rmtree(tokenbelahduab, ignore_errors=True)

        tokenselaapos = klon(tmp, "tokenselaapos")
        tanam(tokenselaapos, "token-sela-apostrof.txt",
              "token uji: " + token_palsu[:paruh] + "\n"
              "# don't dulu\n" + token_palsu[paruh:] + "\n", "w")
        rc, out = jalan(tokenselaapos, ["bash", SKRIP])
        periksa("token tersela baris apostrof tertangkap "
                "(Audit 1553)",
                rc != 0 and "pola rahasia" in out
                and "token-sela-apostrof.txt" in out)
        shutil.rmtree(tokenselaapos, ignore_errors=True)

        tokenselahtml = klon(tmp, "tokenselahtml")
        tanam(tokenselahtml, "token-sela-html.txt",
              "token uji: " + token_palsu[:paruh] + "\n"
              "<!-- jeda -->\n" + token_palsu[paruh:] + "\n", "w")
        rc, out = jalan(tokenselahtml, ["bash", SKRIP])
        periksa("token tersela komentar HTML tertangkap "
                "(Audit 1553)",
                rc != 0 and "pola rahasia" in out
                and "token-sela-html.txt" in out)
        shutil.rmtree(tokenselahtml, ignore_errors=True)

        tokenselapipa = klon(tmp, "tokenselapipa")
        tanam(tokenselapipa, "token-sela-pipa.txt",
              "token uji: " + token_palsu[:paruh] + " | "
              + token_palsu[paruh:] + "\n", "w")
        rc, out = jalan(tokenselapipa, ["bash", SKRIP])
        periksa("token dua paruh sebaris dipisah ' | ' tertangkap "
                "(Audit 1553)",
                rc != 0 and "pola rahasia" in out
                and "token-sela-pipa.txt" in out)
        shutil.rmtree(tokenselapipa, ignore_errors=True)

        doccontoh = klon(tmp, "doccontoh")
        tanam(doccontoh, "dok-contoh.txt",
              "contoh header (sudah diredaksi):\n"
              "-----BEGIN CONTOH-PRIVATE-KEY-----\n"
              "hash rilis: da39a3ee5e6b4b0d3255bfef95601890"
              "afd80709\n", "w")
        rc, out = jalan(doccontoh, ["bash", SKRIP])
        periksa("dokumen contoh header teredaksi + hash rilis "
                "lolos bersih (Audit 1554)",
                rc == 0)
        shutil.rmtree(doccontoh, ignore_errors=True)

    if GAGAL:
        print("GAGAL: %d skenario menyimpang: %s"
              % (len(GAGAL), ", ".join(GAGAL)))
        return 1
    print("LOLOS: semua skenario uji-diri cek-cepat sesuai harapan.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
