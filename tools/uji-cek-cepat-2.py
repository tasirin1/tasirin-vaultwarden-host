#!/usr/bin/env python3
"""Paruh kedua uji-diri cek-cepat (Audit 2557).

Suite ini dulu satu berkas 105 skenario yang berjalan serial
19m20s di CI (43% timeout job) dan menjadi penentu jalur kritis
Assemble APK. Skenarionya kini terbagi dua fungsi di
tools/uji-cek-cepat.py (_bagian_awal/_bagian_akhir); berkas ini
menjalankan paruh akhirnya saja agar CI dapat menjalankan kedua
paruh sebagai dua job paralel. Definisi skenario tetap satu
pintu di berkas pertama - berkas ini hanya pemuat + pelari.
"""

import importlib.util
import os
import sys
import tempfile

_SPES = importlib.util.spec_from_file_location(
    "uji_cek_cepat",
    os.path.join(os.path.dirname(os.path.abspath(__file__)),
                 "uji-cek-cepat.py"))
_U = importlib.util.module_from_spec(_SPES)
_SPES.loader.exec_module(_U)


def main():
    with tempfile.TemporaryDirectory(prefix="uji-cek-cepat-2-") as tmp:
        _U.uji_bersama.paruh_aman(_U._bagian_akhir, tmp)
    return _U.lapor()


if __name__ == "__main__":
    sys.exit(main())
