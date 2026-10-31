#!/usr/bin/env python3
"""Uji-diri gerbang verifikasi-patch (Audit 782).

Perilaku cabang langka gerbang — fail-keras tanpa PyYAML (Audit 758/770),
sumber lokal berkas_dihapus + gabungan mode --semua (759/763/771/775/777),
dan cabang kedua strings.xml kosong = GAGAL (765) — tidak pernah terpicu
pada lari permanen: CI selalu memakai --rentang dengan PyYAML terpasang,
jadi regresinya tak akan membuat lari mana pun merah. Skrip ini
menguncinya secara permanen: tiap skenario dijalankan pada salinan
buang pohon kerja yang di-commit sebagai snapshot git lokal (pohon asli
tak disentuh; cara ini juga kebal keadaan checkout CI yang dangkal/
detached) dan harapan keluarannya diasersikan.

Jalankan: python3 tools/uji-verifikasi.py (berhenti dengan kode 1 bila
ada skenario yang menyimpang).
"""

import os
import shutil
import subprocess
import sys
import tempfile

AKAR = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
GERBANG = os.path.join("tools", "verifikasi-patch.py")
KELAS = "app/src/main/java/com/tasirin/vaultwardenhost/DialogAman.java"
GAGAL = []


def jalan(cwd, perintah, env=None):
    e = dict(os.environ)
    if env:
        e.update(env)
    p = subprocess.run(perintah, cwd=cwd, env=e, stdout=subprocess.PIPE,
                       stderr=subprocess.STDOUT, text=True)
    return p.returncode, p.stdout


def git(repo, *args):
    rc, out = jalan(repo, ["git", "-c", "safe.directory=*", *args])
    if rc != 0:
        raise RuntimeError("git gagal: %s\n%s" % (args, out))
    return out


def klon(tmp, nama):
    # Salinan pohon kerja (tanpa .git) yang di-init + di-commit snapshot:
    # menguji persis pohon yang sedang diperiksa CI, tanpa bergantung pada
    # riwayat/upstream repo asal, dan ringan (pohon kerja hanya beberapa
    # MB; klon penuh menyalin ratusan MB riwayat dan memenuhi /tmp kecil).
    tujuan = os.path.join(tmp, nama)
    shutil.copytree(AKAR, tujuan, ignore=shutil.ignore_patterns(
        ".git", "__pycache__"))
    git(tujuan, "init", "-q")
    git(tujuan, "config", "user.email", "uji@lokal")
    git(tujuan, "config", "user.name", "Uji Gerbang")
    git(tujuan, "add", "-A")
    git(tujuan, "commit", "-q", "-m", "snapshot uji")
    # Commit kosong kedua: gerbang pada mode tanpa --rentang memakai diff
    # cadangan HEAD~1...HEAD bila upstream tak ada; repo berkomit tunggal
    # membuat diff itu gagal dan gerbang GAGAL keras (bukan LOLOS) — dua
    # commit adalah riwayat minimum agar kontrol bersih bisa lulus.
    git(tujuan, "commit", "-q", "--allow-empty", "-m", "riwayat cadangan")
    return tujuan


def periksa(nama, ok, detail=""):
    print("%s %s %s" % ("LOLOS " if ok else "GAGAL ", nama, detail))
    if not ok:
        GAGAL.append(nama)


def main():
    with tempfile.TemporaryDirectory(prefix="uji-verifikasi-") as tmp:
        bersih = klon(tmp, "bersih")
        rc, out = jalan(bersih, [sys.executable, GERBANG, "--semua"])
        periksa("kontrol: pohon bersih lolos --semua", rc == 0,
                "" if rc == 0 else out[-400:])

        blok = os.path.join(tmp, "blokir")
        os.mkdir(blok)
        with open(os.path.join(blok, "yaml.py"), "w") as f:
            f.write("raise ImportError('diblokir uji')\n")
        rc, out = jalan(bersih, [sys.executable, GERBANG, "--semua"],
                        env={"PYTHONPATH": blok})
        periksa("tanpa PyYAML: gerbang gagal keras (Audit 758/770)",
                rc != 0 and "PyYAML tak tersedia" in out)
        shutil.rmtree(bersih, ignore_errors=True)

        hapus = klon(tmp, "hapus")
        git(hapus, "rm", "-q", KELAS)
        rc, out = jalan(hapus, [sys.executable, GERBANG])
        periksa("mode lokal: hapusan staged tertangkap (Audit 759/763)",
                rc != 0 and "DialogAman" in out)
        rc, out = jalan(hapus, [sys.executable, GERBANG, "--semua",
                                "README.md"])
        periksa("mode --semua <jalur>: sumber lokal tetap ikut (Audit 775)",
                rc != 0 and "DialogAman" in out)
        shutil.rmtree(hapus, ignore_errors=True)

        kosong = klon(tmp, "kosong")
        isi = ('<?xml version="1.0" encoding="utf-8"?>\n'
               "<resources></resources>\n")
        for rel in ("app/src/main/res/values/strings.xml",
                    "app/src/main/res/values-in/strings.xml"):
            with open(os.path.join(kosong, rel), "w") as f:
                f.write(isi)
        rc, out = jalan(kosong, [sys.executable, GERBANG, "--semua"])
        periksa("kedua strings.xml kosong: gerbang gagal (Audit 765)",
                rc != 0 and "Kedua strings.xml" in out)
        shutil.rmtree(kosong, ignore_errors=True)

        nonascii = klon(tmp, "nonascii")
        with open(os.path.join(nonascii, "data-\u00fc.zip"), "wb") as f:
            f.write(b"PK\x03\x04")
        git(nonascii, "add", "-A")
        git(nonascii, "commit", "-q", "-m", "uji non-ascii")
        rc, out = jalan(nonascii, [sys.executable, GERBANG,
                                   "--rentang", "HEAD~1..HEAD"])
        periksa("berkas terlarang bernama non-ASCII tertangkap (Audit 812)",
                rc != 0 and "data-\u00fc.zip" in out)
        shutil.rmtree(nonascii, ignore_errors=True)

        hilang = klon(tmp, "hilang")
        os.remove(os.path.join(hilang, ".github", "workflows", "gitleaks.yml"))
        rc, out = jalan(hilang, [sys.executable, GERBANG, "--semua"])
        periksa("workflow wajib hilang: gerbang gagal (Audit 813)",
                rc != 0 and "gitleaks.yml" in out)
        shutil.rmtree(hilang, ignore_errors=True)

        kontrak = klon(tmp, "kontrak")
        berkas_action = os.path.join(kontrak, ".github", "actions",
                                     "resolve-vw", "action.yml")
        with open(berkas_action, encoding="utf-8") as f:
            isi_action = f.read()
        with open(berkas_action, "w", encoding="utf-8") as f:
            f.write(isi_action.replace("\n  tag_mentah:\n",
                                       "\n  tagmentah:\n"))
        rc, out = jalan(kontrak, [sys.executable, GERBANG, "--semua"])
        periksa("keluaran resolve-vw diganti nama: gerbang gagal (Audit 814)",
                rc != 0 and "tag_mentah" in out)
        shutil.rmtree(kontrak, ignore_errors=True)

        gradle = klon(tmp, "gradle")
        berkas_settings = os.path.join(gradle, "settings.gradle.kts")
        with open(berkas_settings, encoding="utf-8") as f:
            isi_settings = f.read()
        with open(berkas_settings, "w", encoding="utf-8") as f:
            f.write(isi_settings.replace("FAIL_ON_PROJECT_REPOS",
                                         "PREFER_PROJECT"))
        rc, out = jalan(gradle, [sys.executable, GERBANG, "--semua"])
        periksa("repositoriesMode dilonggarkan: gerbang gagal (Audit 815)",
                rc != 0 and "FAIL_ON_PROJECT_REPOS" in out)

    if GAGAL:
        print("GAGAL: %d skenario menyimpang: %s"
              % (len(GAGAL), ", ".join(GAGAL)))
        return 1
    print("LOLOS: semua skenario uji-diri gerbang sesuai harapan.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
