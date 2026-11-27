#!/usr/bin/env python3
"""Paruh kedua uji-diri gerbang verifikasi-patch (Audit 2979).

Suite ini dulu satu main() serial ribuan skenario (terukur
22:18 di CI, Audit 1617) dan menjadi jalur kritis tiap push.
Skenarionya kini terbagi dua fungsi di tools/uji-verifikasi.py
(_bagian_awal/_bagian_akhir); berkas ini menjalankan paruh
akhirnya saja agar CI dapat menjalankan kedua paruh sebagai dua
job paralel - pola yang sama dengan belahan suite cek-cepat
(Audit 2557/2559). Definisi skenario tetap satu pintu di
berkas utama - berkas ini hanya pemuat + pelari.
"""

import importlib.util
import os
import sys
import tempfile

_SPES = importlib.util.spec_from_file_location(
    "uji_verifikasi",
    os.path.join(os.path.dirname(os.path.abspath(__file__)),
                 "uji-verifikasi.py"))
_U = importlib.util.module_from_spec(_SPES)
_SPES.loader.exec_module(_U)


def main():
    with tempfile.TemporaryDirectory(prefix="uji-verifikasi-2-") as tmp:
        _U.uji_bersama.paruh_aman(_U._bagian_akhir, tmp)
    return _U.lapor()


if __name__ == "__main__":
    sys.exit(main())
