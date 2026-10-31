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
sendiri: pola rahasia dan vektor tanpa nol depan.

Jalankan: python3 tools/uji-cek-cepat.py (berhenti dengan kode 1 bila
ada skenario yang menyimpang).
"""

import os
import shutil
import subprocess
import sys
import tempfile

AKAR = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SKRIP = os.path.join("scripts", "cek-cepat.sh")
GAGAL = []


def jalan(cwd, perintah):
    p = subprocess.run(perintah, cwd=cwd, stdout=subprocess.PIPE,
                       stderr=subprocess.STDOUT, text=True)
    return p.returncode, p.stdout


def git(repo, *args):
    rc, out = jalan(repo, ["git", "-c", "safe.directory=*", *args])
    if rc != 0:
        raise RuntimeError("git gagal: %s\n%s" % (args, out))
    return out


def klon(tmp, nama):
    tujuan = os.path.join(tmp, nama)
    shutil.copytree(AKAR, tujuan, ignore=shutil.ignore_patterns(
        ".git", "__pycache__"))
    git(tujuan, "init", "-q")
    git(tujuan, "config", "user.email", "uji@lokal")
    git(tujuan, "config", "user.name", "Uji Cek Cepat")
    git(tujuan, "add", "-A")
    git(tujuan, "commit", "-q", "-m", "snapshot uji")
    git(tujuan, "commit", "-q", "--allow-empty", "-m", "riwayat cadangan")
    return tujuan


def tanam(repo, rel, isi, mode="wb"):
    path = os.path.join(repo, rel)
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, mode) as f:
        f.write(isi)
    git(repo, "add", "-A")
    git(repo, "commit", "-q", "-m", "tanam pelanggaran uji")


def periksa(nama, ok, detail=""):
    print("%s %s %s" % ("LOLOS " if ok else "GAGAL ", nama, detail))
    if not ok:
        GAGAL.append(nama)


def main():
    with tempfile.TemporaryDirectory(prefix="uji-cek-cepat-") as tmp:
        bersih = klon(tmp, "bersih")
        rc, out = jalan(bersih, ["bash", SKRIP])
        periksa("kontrol: pohon bersih lolos", rc == 0,
                "" if rc == 0 else out[-400:])
        shutil.rmtree(bersih, ignore_errors=True)




        rahasia = klon(tmp, "rahasia")
        token_palsu = "123456789" + ":" + "AA" + "Ab3_" * 10
        tanam(rahasia, "catatan-token.txt",
              "token uji: " + token_palsu + "\n", "w")
        rc, out = jalan(rahasia, ["bash", SKRIP])
        periksa("pola token rahasia tertangkap",
                rc != 0 and "pola rahasia" in out)
        shutil.rmtree(rahasia, ignore_errors=True)

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

    if GAGAL:
        print("GAGAL: %d skenario menyimpang: %s"
              % (len(GAGAL), ", ".join(GAGAL)))
        return 1
    print("LOLOS: semua skenario uji-diri cek-cepat sesuai harapan.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
