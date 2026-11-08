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

    if GAGAL:
        print("GAGAL: %d skenario menyimpang: %s"
              % (len(GAGAL), ", ".join(GAGAL)))
        return 1
    print("LOLOS: semua skenario uji-diri cek-cepat sesuai harapan.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
