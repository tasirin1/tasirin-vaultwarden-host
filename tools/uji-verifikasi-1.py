#!/usr/bin/env python3
"""Paruh pertama uji-diri gerbang verifikasi-patch (Audit 2979).

Suite ini dulu satu main() serial ribuan skenario (terukur
22:18 di CI, Audit 1617) dan menjadi jalur kritis tiap push.
Skenarionya kini terbagi dua fungsi di tools/uji-verifikasi.py
(_bagian_awal/_bagian_akhir); berkas ini menjalankan paruh
awalnya saja agar CI dapat menjalankan kedua paruh sebagai dua
job paralel - pola yang sama dengan belahan suite cek-cepat
(Audit 2557/2559). Definisi skenario tetap satu pintu di
berkas utama - berkas ini hanya pemuat + pelari.

Audit 2794: badan berkas ini kini hanya mendelegasikan ke pelari bersama tools/lari-paruh.py.
"""

import importlib.util
import os
import sys

_SPES = importlib.util.spec_from_file_location(
    "lari_paruh",
    os.path.join(os.path.dirname(os.path.abspath(__file__)),
                 "lari-paruh.py"))
_L = importlib.util.module_from_spec(_SPES)
_SPES.loader.exec_module(_L)

if __name__ == "__main__":
    sys.exit(_L.jalankan("uji-verifikasi.py", 1))
