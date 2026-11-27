#!/usr/bin/env python3
"""Pelari paruh suite bersama (Audit 2794).

Empat berkas titik-masuk suite (uji-verifikasi-1/-2 dan
uji-cek-cepat-1/-2) dulu menyalin logika muat + lari yang
sama persis; kini logika itu satu pintu di berkas ini dan
keempatnya hanya mendelegasikan - nama mereka tetap,
karena workflow CI memanggil nama berkas itu. Berkas ini
juga bisa dipanggil langsung:
lari-paruh.py <berkas-suite> <1|2>.
"""

import importlib.util
import os
import sys
import tempfile


def jalankan(nama_berkas, paruh):
    dasar = os.path.dirname(os.path.abspath(__file__))
    spes = importlib.util.spec_from_file_location(
        "suite_paruh", os.path.join(dasar, nama_berkas))
    u = importlib.util.module_from_spec(spes)
    spes.loader.exec_module(u)
    bagian = (u._bagian_awal if int(paruh) == 1
              else u._bagian_akhir)
    awalan = nama_berkas[:-3] + "-%d-" % int(paruh)
    with tempfile.TemporaryDirectory(prefix=awalan) as tmp:
        u.uji_bersama.paruh_aman(bagian, tmp)
    return u.lapor()


def main():
    if len(sys.argv) != 3 or sys.argv[2] not in ("1", "2"):
        print("pakai: lari-paruh.py <berkas-suite> <1|2>")
        return 2
    return jalankan(sys.argv[1], sys.argv[2])


if __name__ == "__main__":
    sys.exit(main())
