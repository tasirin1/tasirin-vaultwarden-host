#!/usr/bin/env python3
"""Pembangkit daftar Audit -> commit (Audit 2836).

Memetakan tiap nomor `Audit NNNN` yang hidup di pohon
(app/, tools/, scripts/, .github/) ke commit tertua
yang menambahkannya, dari SATU lintasan
`git log -p --pickaxe-regex -S 'Audit [0-9]+'`
(riwayat dibaca terbaru -> tertua; entri terakhir
yang menimpa satu nomor adalah penambah tertuanya).
Jalankan ulang berkas ini untuk menyegarkan
tools/daftar-audit.txt; berkas hasil di-commit apa
adanya. Nomor yang tak ditemukan di riwayat (kerja
belum ter-commit / hitungan pickaxe netral) dicatat
terpisah di akhir daftar, bukan ditebak.
"""

import os
import re
import subprocess
import sys

AKAR = os.path.dirname(os.path.dirname(
    os.path.abspath(__file__)))
POLA = re.compile(r"Audit\s+(\d{3,})")
LINGKUP = ["app", "tools", "scripts", ".github"]


def nomor_hari_ini():
    hasil = {}
    for dasar in LINGKUP:
        for akar, _dirs, berkas in os.walk(
                os.path.join(AKAR, dasar)):
            if "__pycache__" in akar:
                continue
            for nama in berkas:
                path = os.path.join(akar, nama)
                rel = os.path.relpath(path, AKAR)
                if rel == os.path.join(
                        "tools", "daftar-audit.txt"):
                    continue
                try:
                    with open(path, encoding="utf-8",
                              errors="replace") as f:
                        isi = f.read()
                except OSError:
                    continue
                for m in POLA.finditer(isi):
                    hasil.setdefault(m.group(1), set()).add(rel)
    return hasil


def peta_commit():
    peta = {}
    proc = subprocess.Popen(
        ["git", "-C", AKAR, "log", "-p", "--pickaxe-regex",
         "-S", "Audit [0-9]+",
         "--format=KOMIT%x09%H%x09%s", "--"] + LINGKUP,
        stdout=subprocess.PIPE, text=True,
        encoding="utf-8", errors="replace")
    sha = ""
    subjek = ""
    for baris in proc.stdout:
        if baris.startswith("KOMIT\t"):
            _t, sha, subjek = baris.rstrip("\n").split(
                "\t", 2)
            continue
        if baris.startswith("+") and not baris.startswith(
                "+++"):
            for m in POLA.finditer(baris):
                peta[m.group(1)] = (sha, subjek)
    proc.wait()
    return peta


def main():
    hari_ini = nomor_hari_ini()
    peta = peta_commit()
    kepala = subprocess.run(
        ["git", "-C", AKAR, "rev-parse", "HEAD"],
        capture_output=True, text=True).stdout.strip()
    keluar = []
    keluar.append("# Daftar Audit -> commit (Audit 2836)")
    keluar.append("# Dibangkitkan tools/daftar-audit.py "
                  "pada %s - jalankan ulang skrip itu "
                  "untuk menyegarkan." % kepala[:12])
    keluar.append("# Catatan: nomor bisa bermakna ganda - "
                  "rangkaian internal lama dan nomor issue "
                  "GitHub tumpang tindih; yang dicatat di "
                  "sini selalu penambah TERTUA di riwayat.")
    keluar.append("# Format: nomor | commit penambah "
                  "tertua | rujukan issue di subjek | "
                  "subjek | jumlah berkas memuat nomor "
                  "itu hari ini.")
    tak_terlacak = []
    for nomor in sorted(hari_ini, key=int):
        berkas = len(hari_ini[nomor])
        if nomor in peta:
            sha, subjek = peta[nomor]
            rujuk = re.search(r"#(\d+)", subjek)
            keluar.append(
                "Audit %s | %s | %s | %s | %d"
                % (nomor, sha[:12],
                   ("#" + rujuk.group(1)) if rujuk else "-",
                   subjek, berkas))
        else:
            tak_terlacak.append(nomor)
    keluar.append("")
    keluar.append("# Tak terlacak di riwayat (kerja "
                  "belum ter-commit / netral pickaxe): "
                  + (", ".join("Audit " + n
                               for n in tak_terlacak)
                     if tak_terlacak else "-"))
    tujuan = os.path.join(AKAR, "tools", "daftar-audit.txt")
    with open(tujuan, "w", encoding="utf-8") as f:
        f.write("\n".join(keluar) + "\n")
    print("tertulis %s: %d nomor, %d tak terlacak"
          % (tujuan, len(hari_ini), len(tak_terlacak)))
    return 0


if __name__ == "__main__":
    sys.exit(main())
