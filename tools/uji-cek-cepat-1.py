#!/usr/bin/env python3
"""Paruh pertama uji-diri cek-cepat (Audit 2557, kabel Audit 2559).

Suite ini dulu satu berkas 106 skenario yang berjalan serial.
Skenarionya terbagi dua fungsi di tools/uji-cek-cepat.py
(_bagian_awal/_bagian_akhir); berkas ini menjalankan paruh
awalnya saja agar CI dapat menjalankan kedua paruh sebagai dua
job paralel. Audit 2559: sebelum ini job verifikasi-cek tetap
menjalankan berkas utama utuh (kedua paruh) sehingga belahan
tidak membelah beban apa pun dan paruh akhir berjalan dua kali -
berkas titik-masuk ini yang memasang kabelnya. Definisi skenario
tetap satu pintu di berkas utama - berkas ini hanya pemuat +
pelari.
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
    with tempfile.TemporaryDirectory(prefix="uji-cek-cepat-1-") as tmp:
        _U._bagian_awal(tmp)
    return _U.lapor()


if __name__ == "__main__":
    sys.exit(main())
