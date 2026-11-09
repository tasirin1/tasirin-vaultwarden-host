#!/usr/bin/env python3
"""Helper bersama dua suite uji-diri (Audit 1460).

jalan/git/klon/periksa dulu hidup sebagai dua salinan nyaris
identik di tools/uji-verifikasi.py dan tools/uji-cek-cepat.py —
perbaikan helper (mis. timeout Audit 1453) harus dilakukan dua
kali dan bisa timpang diam-diam. Kini logikanya satu pintu di
sini; kedua suite mengimpor modul ini (direktori skrip otomatis
masuk sys.path saat berkas suite dijalankan langsung) dan hanya
menyediakan adaptor klon() tipis berisi AKAR + nama ujinya.
"""

import os
import shutil
import subprocess

GAGAL = []


def jalan(cwd, perintah, env=None):
    e = dict(os.environ)
    if env:
        e.update(env)
    # Audit 1453: tanpa timeout, satu panggilan yang menggantung
    # membekukan seluruh suite sampai timeout job CI 45 menit;
    # kini skenario itu GAGAL keras dengan label jelas.
    try:
        p = subprocess.run(perintah, cwd=cwd, env=e, stdout=subprocess.PIPE,
                           stderr=subprocess.STDOUT, text=True, timeout=300)
    except subprocess.TimeoutExpired as ex:
        # Audit 1508: ex.output bertipe bytes walau text=True,
        # jadi pesan timeout dulu memuat repr b'...' yang kabur.
        keluaran = ex.output or ""
        if isinstance(keluaran, bytes):
            keluaran = keluaran.decode("utf-8", "replace")
        return 124, "TIMEOUT 300 detik: %s" % keluaran
    return p.returncode, p.stdout


def git(repo, *args):
    rc, out = jalan(repo, ["git", "-c", "safe.directory=*", *args])
    if rc != 0:
        raise RuntimeError("git gagal: %s\n%s" % (args, out))
    return out


class Snapshot:
    """Penampung snapshot pohon bersama satu lari suite."""

    def __init__(self):
        self.asal = None


def klon(tmp, nama, akar, nama_uji, snapshot):
    # Audit 1457: snapshot pohon dibangun SEKALI per lari suite;
    # sebelumnya tiap skenario membayar copytree + init + add +
    # 2 commit sendiri (±0,5 detik x ratusan skenario). Klon lokal
    # dari snapshot menguji persis pohon yang sedang diperiksa CI,
    # tanpa bergantung pada riwayat/upstream repo asal, dan ringan
    # (pohon kerja hanya beberapa MB; klon penuh menyalin ratusan
    # MB riwayat dan memenuhi /tmp kecil). Remote origin dibuang
    # agar keadaan git setara repo hasil init (gerbang memakai
    # diff cadangan HEAD~1...HEAD). Commit kosong kedua adalah
    # riwayat minimum itu: repo berkomit tunggal membuat diff
    # cadangan gagal dan gerbang GAGAL keras (bukan LOLOS).
    if snapshot.asal is None:
        asal = os.path.join(tmp, ".snapshot-uji")
        shutil.copytree(akar, asal, ignore=shutil.ignore_patterns(
            ".git", "__pycache__"))
        git(asal, "init", "-q")
        git(asal, "config", "user.email", "uji@lokal")
        git(asal, "config", "user.name", nama_uji)
        git(asal, "add", "-A")
        git(asal, "commit", "-q", "-m", "snapshot uji")
        git(asal, "commit", "-q", "--allow-empty", "-m",
            "riwayat cadangan")
        snapshot.asal = asal
    tujuan = os.path.join(tmp, nama)
    git(tmp, "clone", "-q", "--local", snapshot.asal, tujuan)
    git(tujuan, "remote", "remove", "origin")
    git(tujuan, "config", "user.email", "uji@lokal")
    git(tujuan, "config", "user.name", nama_uji)
    return tujuan


def periksa(nama, ok, detail=""):
    # Audit 1628: flush per vonis — keluaran suite yang ter-buffer
    # penuh membuat lari yang mati di tengah meninggalkan log
    # KOSONG (seluruh vonis yang sudah tercetak ikut hilang).
    print("%s %s %s" % ("LOLOS " if ok else "GAGAL ", nama, detail),
          flush=True)
    if not ok:
        GAGAL.append(nama)
