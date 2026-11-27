#!/usr/bin/env python3
"""Paruh kedua uji-diri cek-cepat (Audit 2557).

Suite ini dulu satu berkas 105 skenario yang berjalan serial
19m20s di CI (43% timeout job) dan menjadi penentu jalur kritis
Assemble APK. Skenarionya kini terbagi dua fungsi di
tools/uji-cek-cepat.py (_bagian_awal/_bagian_akhir); berkas ini
menjalankan paruh akhirnya saja agar CI dapat menjalankan kedua
paruh sebagai dua job paralel. Definisi skenario tetap satu
pintu di berkas pertama - berkas ini hanya pemuat + pelari.

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
    sys.exit(_L.jalankan("uji-cek-cepat.py", 2))
