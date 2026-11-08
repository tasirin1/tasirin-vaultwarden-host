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
import subprocess
import sys
import tempfile

AKAR = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SKRIP = os.path.join("scripts", "cek-cepat.sh")
GAGAL = []


def jalan(cwd, perintah):
    # Audit 1453: timeout sama seperti uji-verifikasi — satu
    # panggilan menggantung tak lagi membekukan job CI.
    try:
        p = subprocess.run(perintah, cwd=cwd, stdout=subprocess.PIPE,
                           stderr=subprocess.STDOUT, text=True, timeout=300)
    except subprocess.TimeoutExpired as ex:
        return 124, "TIMEOUT 300 detik: %s" % (ex.output or "")
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

        terbelah = klon(tmp, "terbelah")
        paruh = len(token_palsu) // 2
        tanam(terbelah, "catatan-token-terbelah.txt",
              "token uji: " + token_palsu[:paruh] + "\n"
              + token_palsu[paruh:] + "\n", "w")
        rc, out = jalan(terbelah, ["bash", SKRIP])
        periksa("token terbelah dua baris tertangkap (cabang bungkus)",
                rc != 0 and "pola rahasia" in out)
        shutil.rmtree(terbelah, ignore_errors=True)

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
