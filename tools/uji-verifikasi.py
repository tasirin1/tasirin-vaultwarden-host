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

Audit 2979: skenario terbagi dua fungsi (_bagian_awal/_bagian_akhir)
agar CI menjalankannya sebagai dua job paralel lewat berkas
titik-masuk tools/uji-verifikasi-1.py dan tools/uji-verifikasi-2.py;
berkas utama ini tetap menjalankan keduanya berurutan.
"""

import os
import shutil
import sys
import tempfile

# Audit 1460: helper jalan/git/klon/periksa satu pintu di
# tools/uji_bersama.py (dulu dua salinan nyaris identik di sini
# dan di uji-cek-cepat.py).
import uji_bersama
from uji_bersama import GAGAL, git, jalan, periksa

AKAR = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
GERBANG = os.path.join("tools", "verifikasi-patch.py")
KELAS = "app/src/main/java/com/tasirin/vaultwardenhost/DialogAman.java"

_SNAPSHOT = uji_bersama.Snapshot()


def klon(tmp, nama):
    return uji_bersama.klon(tmp, nama, AKAR, "Uji Gerbang", _SNAPSHOT)


def skenario_substitusi(tmp, nama, ganti, label, lolos):
    # Audit 2770: rangka bersama keluarga skenario
    # substitusi proses pada baris tag_mentah keluaran
    # resolve-vw (dulu disalin utuh per skenario).
    k = klon(tmp, nama)
    berkas = os.path.join(k, ".github", "actions",
                          "resolve-vw", "action.yml")
    with open(berkas, encoding="utf-8") as f:
        isi = f.read()
    baris = [b for b in isi.splitlines()
             if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
    isi_baru = isi.replace(
        baris,
        baris.replace('>> "$GITHUB_OUTPUT"', ganti), 1)
    angka = "".join(c for c in nama if c.isdigit())
    assert isi_baru != isi, \
        "suntikan aksi" + angka + " tidak menembak"
    with open(berkas, "w", encoding="utf-8") as f:
        f.write(isi_baru)
    rc, out = jalan(k, [sys.executable, GERBANG,
                        "--hanya-workflow"])
    if lolos:
        periksa(label, rc == 0)
    else:
        periksa(label,
                rc != 0 and rc != 124 and "Traceback" not in out)
    shutil.rmtree(k, ignore_errors=True)


def _bagian_awal(tmp):
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
            rc != 0 and rc != 124 and "PyYAML tak tersedia" in out)
    shutil.rmtree(bersih, ignore_errors=True)

    hapus = klon(tmp, "hapus")
    git(hapus, "rm", "-q", KELAS)
    rc, out = jalan(hapus, [sys.executable, GERBANG])
    periksa("mode lokal: hapusan staged tertangkap (Audit 759/763)",
            rc != 0 and rc != 124 and "DialogAman" in out)
    rc, out = jalan(hapus, [sys.executable, GERBANG, "--semua",
                            "README.md"])
    periksa("mode --semua <jalur>: sumber lokal tetap ikut (Audit 775)",
            rc != 0 and rc != 124 and "DialogAman" in out)
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
            rc != 0 and rc != 124 and "Kedua strings.xml" in out)
    shutil.rmtree(kosong, ignore_errors=True)

    nonascii = klon(tmp, "nonascii")
    with open(os.path.join(nonascii, "data-\u00fc.zip"), "wb") as f:
        f.write(b"PK\x03\x04")
    git(nonascii, "add", "-A")
    git(nonascii, "commit", "-q", "-m", "uji non-ascii")
    rc, out = jalan(nonascii, [sys.executable, GERBANG,
                               "--rentang", "HEAD~1..HEAD"])
    periksa("berkas terlarang bernama non-ASCII tertangkap (Audit 812)",
            rc != 0 and rc != 124 and "data-\u00fc.zip" in out)
    shutil.rmtree(nonascii, ignore_errors=True)

    hilang = klon(tmp, "hilang")
    os.remove(os.path.join(hilang, ".github", "workflows", "gitleaks.yml"))
    rc, out = jalan(hilang, [sys.executable, GERBANG, "--semua"])
    periksa("workflow wajib hilang: gerbang gagal (Audit 813)",
            rc != 0 and rc != 124 and "gitleaks.yml" in out)
    shutil.rmtree(hilang, ignore_errors=True)

    def skenario_ganti_gagal(nama, rel, lama, baru, label,
                             frags, hitung=0):
        # Audit 2770 (tahap 1): rangka bersama keluarga
        # skenario "satu berkas diganti teksnya lalu
        # gerbang --semua wajib GAGAL menyebut frags" -
        # dulu rangka ini disalin utuh per skenario.
        k = klon(tmp, nama)
        berkas = os.path.join(k, *rel)
        with open(berkas, encoding="utf-8") as f:
            isi = f.read()
        if hitung:
            isi = isi.replace(lama, baru, hitung)
        else:
            isi = isi.replace(lama, baru)
        with open(berkas, "w", encoding="utf-8") as f:
            f.write(isi)
        rc, out = jalan(k, [sys.executable, GERBANG, "--semua"])
        periksa(label,
                rc != 0 and rc != 124
                and all(x in out for x in frags))
        shutil.rmtree(k, ignore_errors=True)

    skenario_ganti_gagal(
        "kontrak",
        (".github", "actions", "resolve-vw", "action.yml"),
        "\n  tag_mentah:\n", "\n  tagmentah:\n",
        "keluaran resolve-vw diganti nama: gerbang gagal (Audit 814)",
        ("tag_mentah",))
    skenario_ganti_gagal(
        "gradle", ("settings.gradle.kts",),
        "FAIL_ON_PROJECT_REPOS", "PREFER_PROJECT",
        "repositoriesMode dilonggarkan: gerbang gagal (Audit 815)",
        ("FAIL_ON_PROJECT_REPOS",))
    skenario_ganti_gagal(
        "gradle2", ("settings.gradle.kts",),
        "        google()\n        mavenCentral()\n    }",
        "        google()\n        mavenCentral()\n"
        "        maven { url = \"https://evil.example.com/maven\" }\n    }",
        "repositori maven tanpa kurung di settings: gerbang gagal "
        "(Audit 2898)",
        ("tak diizinkan",), hitung=1)
    gradle3 = klon(tmp, "gradle3")
    berkas_akar3 = os.path.join(gradle3, "build.gradle.kts")
    with open(berkas_akar3, encoding="utf-8") as f:
        isi_akar3 = f.read()
    with open(berkas_akar3, "w", encoding="utf-8") as f:
        f.write(isi_akar3 + "\nbuildscript {\n    repositories {\n"
                "        maven { url = uri(\"https://evil.example.com\") }\n"
                "    }\n}\n")
    rc, out = jalan(gradle3, [sys.executable, GERBANG, "--semua"])
    periksa("repositori buildscript di build.gradle.kts akar: gerbang "
            "gagal (Audit 2898)",
            rc != 0 and rc != 124 and "tak diizinkan" in out)
    shutil.rmtree(gradle3, ignore_errors=True)

    lok = klon(tmp, "lok")
    berkas_lok = os.path.join(lok, "app", "src", "main", "java", "com",
                              "tasirin", "vaultwardenhost",
                              "MemoLocaleUji.java")
    with open(berkas_lok, "w", encoding="utf-8") as f:
        f.write("package com.tasirin.vaultwardenhost;\n\n"
                "final class MemoLocaleUji {\n"
                "    static String kecil(String s) {\n"
                "        return s.toLowerCase(java.util.Locale.getDefault());\n"
                "    }\n}\n")
    git(lok, "add", "-A")
    git(lok, "commit", "-q", "-m", "tanam locale getDefault uji")
    rc, out = jalan(lok, [sys.executable, GERBANG, "--semua"])
    periksa("toLowerCase(Locale.getDefault()) ter-commit: gerbang gagal "
            "(Audit 2902)",
            rc != 0 and rc != 124 and "Locale" in out)
    shutil.rmtree(lok, ignore_errors=True)

    jenis = klon(tmp, "jenis")
    berkas_jenis = os.path.join(jenis, "tools", "bekas-hapusan.txt")
    with open(berkas_jenis, "a", encoding="utf-8") as f:
        f.write("field:capUjiHapus2915\n")
        f.write("python:_cap_uji_hapus_2915\n")
    berkas_jv = os.path.join(jenis, "app", "src", "main", "java", "com",
                             "tasirin", "vaultwardenhost",
                             "MemoJenisUji.java")
    with open(berkas_jv, "w", encoding="utf-8") as f:
        f.write("package com.tasirin.vaultwardenhost;\n\n"
                "final class MemoJenisUji {\n"
                "    static long capUjiHapus2915;\n}\n")
    berkas_py = os.path.join(jenis, "tools", "cap_uji_2915.py")
    with open(berkas_py, "w", encoding="utf-8") as f:
        f.write("def _cap_uji_hapus_2915():\n    return 1\n")
    git(jenis, "add", "-A")
    git(jenis, "commit", "-q", "-m", "tanam bekas berjenis uji")
    rc, out = jalan(jenis, [sys.executable, GERBANG, "--semua"])
    periksa("bekas-hapusan berjenis field:/python: ditegakkan "
            "(Audit 2915)",
            rc != 0 and rc != 124 and "capUjiHapus2915" in out
            and "_cap_uji_hapus_2915" in out)
    shutil.rmtree(jenis, ignore_errors=True)

    skenario_ganti_gagal(
        "umpan",
        (".github", "actions", "resolve-vw", "action.yml"),
        'echo "tag_mentah=$TAG" >> "$GITHUB_OUTPUT"',
        'echo "tagmentah=$TAG" >> "$GITHUB_OUTPUT"\n'
        '        echo "tag_mentah=$TAG"',
        "echo log umpan nama produsen: gerbang gagal (Audit 867)",
        ("tag_mentah",))
    skenario_ganti_gagal(
        "umpankomen",
        (".github", "actions", "resolve-vw", "action.yml"),
        'echo "tag_mentah=$TAG" >> "$GITHUB_OUTPUT"',
        'echo "tagmentah=$TAG" >> "$GITHUB_OUTPUT"\n'
        '        # echo "tag_mentah=$TAG" >> "$GITHUB_OUTPUT"',
        "baris komentar shell nama produsen: gerbang gagal (Audit 872)",
        ("tag_mentah",))
    skenario_ganti_gagal(
        "umpanmajemuk",
        (".github", "actions", "resolve-vw", "action.yml"),
        'echo "tag_mentah=$TAG" >> "$GITHUB_OUTPUT"',
        'echo "tag_mentah=umpan"; '
        'echo "tagmentah=$TAG" >> "$GITHUB_OUTPUT"',
        "echo umpan baris majemuk nama produsen: gerbang gagal (Audit 872)",
        ("tag_mentah",))
    skenario_ganti_gagal(
        "umpankutip",
        (".github", "actions", "resolve-vw", "action.yml"),
        'echo "tag_mentah=$TAG" >> "$GITHUB_OUTPUT"',
        'echo "tagmentah=$TAG" >> "$GITHUB_OUTPUT"\n'
        '        echo "catatan; echo tag_mentah=x >> $GITHUB_OUTPUT"',
        "titik koma dalam kutip nama produsen: gerbang gagal (Audit 874)",
        ("tag_mentah",))
    prodtrue = klon(tmp, "prodtrue")
    berkas_pt = os.path.join(prodtrue, ".github", "actions",
                             "resolve-vw", "action.yml")
    with open(berkas_pt, encoding="utf-8") as f:
        isi_pt = f.read()
    # Audit 1452: pola isi_baru + asersi (kelas Audit 1410) —
    # suntikan yang tak menembak kini menggagalkan suite,
    # bukan lagi LOLOS palsu.
    isi_baru_pt = isi_pt.replace(
        'echo "tag_mentah=$TAG" >> "$GITHUB_OUTPUT"',
        'echo "tag_mentah=$TAG" >> "$GITHUB_OUTPUT" || true')
    assert isi_baru_pt != isi_pt, \
        "suntikan prodtrue tidak menembak"
    with open(berkas_pt, "w", encoding="utf-8") as f:
        f.write(isi_baru_pt)
    rc, out = jalan(prodtrue, [sys.executable, GERBANG, "--semua"])
    periksa("produsen sah sufiks || true: gerbang lolos (Audit 875)",
            rc == 0)
    shutil.rmtree(prodtrue, ignore_errors=True)

    prodkutip1 = klon(tmp, "prodkutip1")
    berkas_pk = os.path.join(prodkutip1, ".github", "actions",
                             "resolve-vw", "action.yml")
    with open(berkas_pk, encoding="utf-8") as f:
        isi_pk = f.read()
    isi_baru_pk = isi_pk.replace(
        'echo "tag_mentah=$TAG" >> "$GITHUB_OUTPUT"',
        "echo 'tag_mentah=$TAG' >> \"$GITHUB_OUTPUT\"")
    assert isi_baru_pk != isi_pk, \
        "suntikan prodkutip1 tidak menembak"
    with open(berkas_pk, "w", encoding="utf-8") as f:
        f.write(isi_baru_pk)
    rc, out = jalan(prodkutip1, [sys.executable, GERBANG, "--semua"])
    periksa("produsen sah echo kutip tunggal: gerbang lolos (Audit 875)",
            rc == 0)
    shutil.rmtree(prodkutip1, ignore_errors=True)

    prodkom = klon(tmp, "prodkom")
    berkas_aksi8 = os.path.join(
        prodkom, ".github", "actions", "resolve-vw", "action.yml")
    with open(berkas_aksi8, encoding="utf-8") as f:
        isi_aksi8 = f.read()
    baris_tulis8 = [b for b in isi_aksi8.splitlines()
                    if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
    isi_baru8 = isi_aksi8.replace(
            baris_tulis8, baris_tulis8 + " # tulis keluaran", 1)
    assert isi_baru8 != isi_aksi8, \
        "suntikan aksi8 tidak menembak"
    with open(berkas_aksi8, "w", encoding="utf-8") as f:
        f.write(isi_baru8)
    rc, out = jalan(prodkom, [sys.executable, GERBANG, "--semua"])
    periksa("produsen ber-komentar akhir baris: gerbang lolos (Audit 878)",
            rc == 0)
    shutil.rmtree(prodkom, ignore_errors=True)

    umpankomen = klon(tmp, "umpankomen2")
    berkas_aksi9 = os.path.join(
        umpankomen, ".github", "actions", "resolve-vw", "action.yml")
    with open(berkas_aksi9, encoding="utf-8") as f:
        isi_aksi9 = f.read()
    baris_tulis9 = [b for b in isi_aksi9.splitlines()
                    if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
    isi_aksi9 = isi_aksi9.replace(
        'echo "tag_mentah=', 'echo "salah_nama=', 1)
    isi_aksi9 = isi_aksi9.replace(
        baris_tulis9.replace("tag_mentah", "salah_nama"),
        baris_tulis9.replace("tag_mentah", "salah_nama")
        + '\n          echo "catatan" # komentar && echo'
        ' "tag_mentah=$TAG" >> "$GITHUB_OUTPUT"', 1)
    with open(berkas_aksi9, "w", encoding="utf-8") as f:
        f.write(isi_aksi9)
    rc, out = jalan(umpankomen, [sys.executable, GERBANG, "--semua"])
    periksa("produsen semu di komentar tengah baris: gerbang gagal (Audit 881)",
            rc != 0 and rc != 124 and "tag_mentah" in out)
    shutil.rmtree(umpankomen, ignore_errors=True)

    tempel = klon(tmp, "tempel")
    berkas_aksi10 = os.path.join(
        tempel, ".github", "actions", "resolve-vw", "action.yml")
    with open(berkas_aksi10, encoding="utf-8") as f:
        isi_aksi10 = f.read()
    baris_tulis10 = [b for b in isi_aksi10.splitlines()
                     if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
    isi_baru10 = isi_aksi10.replace(
            baris_tulis10,
            baris_tulis10.replace('>> "$GITHUB_OUTPUT"',
                                  '>> "$GITHUB_OUTPUT"#semu'), 1)
    assert isi_baru10 != isi_aksi10, \
        "suntikan aksi10 tidak menembak"
    with open(berkas_aksi10, "w", encoding="utf-8") as f:
        f.write(isi_baru10)
    rc, out = jalan(tempel, [sys.executable, GERBANG, "--semua"])
    periksa("target redirect berekor # menempel: gerbang gagal (Audit 882)",
            rc != 0 and rc != 124 and "tag_mentah" in out)
    shutil.rmtree(tempel, ignore_errors=True)

    umpankurung = klon(tmp, "umpankurung")
    berkas_aksi11 = os.path.join(
        umpankurung, ".github", "actions", "resolve-vw", "action.yml")
    with open(berkas_aksi11, encoding="utf-8") as f:
        isi_aksi11 = f.read()
    baris_tulis11 = [b for b in isi_aksi11.splitlines()
                     if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
    isi_aksi11 = isi_aksi11.replace(
        'echo "tag_mentah=', 'echo "salah_nama=', 1)
    isi_aksi11 = isi_aksi11.replace(
        baris_tulis11.replace("tag_mentah", "salah_nama"),
        baris_tulis11.replace("tag_mentah", "salah_nama")
        + '\n          (true)#teks; echo "tag_mentah=$TAG"'
        ' >> "$GITHUB_OUTPUT"', 1)
    with open(berkas_aksi11, "w", encoding="utf-8") as f:
        f.write(isi_aksi11)
    rc, out = jalan(umpankurung, [sys.executable, GERBANG, "--semua"])
    periksa("produsen semu di komentar sesudah kurung tutup: gerbang gagal (Audit 884)",
            rc != 0 and rc != 124 and "tag_mentah" in out)
    shutil.rmtree(umpankurung, ignore_errors=True)

    dualredir = klon(tmp, "dualredir")
    berkas_aksi12 = os.path.join(
        dualredir, ".github", "actions", "resolve-vw", "action.yml")
    with open(berkas_aksi12, encoding="utf-8") as f:
        isi_aksi12 = f.read()
    baris_tulis12 = [b for b in isi_aksi12.splitlines()
                     if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
    isi_baru12 = isi_aksi12.replace(
            baris_tulis12,
            baris_tulis12.replace(
                '>> "$GITHUB_OUTPUT"',
                '>> "$GITHUB_OUTPUT.bak" >> "$GITHUB_OUTPUT"'), 1)
    assert isi_baru12 != isi_aksi12, \
        "suntikan aksi12 tidak menembak"
    with open(berkas_aksi12, "w", encoding="utf-8") as f:
        f.write(isi_baru12)
    rc, out = jalan(dualredir, [sys.executable, GERBANG, "--semua"])
    periksa("redirect ganda target terakhir keluaran: gerbang lolos (Audit 885)",
            rc == 0)
    shutil.rmtree(dualredir, ignore_errors=True)

    bungkus = klon(tmp, "bungkus")
    berkas_aksi13 = os.path.join(
        bungkus, ".github", "actions", "resolve-vw", "action.yml")
    with open(berkas_aksi13, encoding="utf-8") as f:
        isi_aksi13 = f.read()
    baris_tulis13 = [b for b in isi_aksi13.splitlines()
                     if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
    indent13 = baris_tulis13[:len(baris_tulis13)
                              - len(baris_tulis13.lstrip())]
    isi_baru13 = isi_aksi13.replace(
            baris_tulis13,
            indent13 + "{ " + baris_tulis13.strip() + "; }", 1)
    assert isi_baru13 != isi_aksi13, \
        "suntikan aksi13 tidak menembak"
    with open(berkas_aksi13, "w", encoding="utf-8") as f:
        f.write(isi_baru13)
    rc, out = jalan(bungkus, [sys.executable, GERBANG, "--semua"])
    periksa("produsen dibungkus grup shell: gerbang lolos (Audit 886)",
            rc == 0)
    shutil.rmtree(bungkus, ignore_errors=True)

    subkulit = klon(tmp, "subkulit")
    berkas_aksi14 = os.path.join(
        subkulit, ".github", "actions", "resolve-vw", "action.yml")
    with open(berkas_aksi14, encoding="utf-8") as f:
        isi_aksi14 = f.read()
    baris_tulis14 = [b for b in isi_aksi14.splitlines()
                     if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
    indent14 = baris_tulis14[:len(baris_tulis14)
                              - len(baris_tulis14.lstrip())]
    isi_baru14 = isi_aksi14.replace(
            baris_tulis14,
            indent14 + "(" + baris_tulis14.strip() + ")", 1)
    assert isi_baru14 != isi_aksi14, \
        "suntikan aksi14 tidak menembak"
    with open(berkas_aksi14, "w", encoding="utf-8") as f:
        f.write(isi_baru14)
    rc, out = jalan(subkulit, [sys.executable, GERBANG, "--semua"])
    periksa("produsen dibungkus subshell: gerbang lolos (Audit 887)",
            rc == 0)
    shutil.rmtree(subkulit, ignore_errors=True)

    rantaiatau = klon(tmp, "rantaiatau")
    berkas_aksi15 = os.path.join(
        rantaiatau, ".github", "actions", "resolve-vw", "action.yml")
    with open(berkas_aksi15, encoding="utf-8") as f:
        isi_aksi15 = f.read()
    baris_tulis15 = [b for b in isi_aksi15.splitlines()
                     if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
    indent15 = baris_tulis15[:len(baris_tulis15)
                              - len(baris_tulis15.lstrip())]
    isi_baru15 = isi_aksi15.replace(
            baris_tulis15,
            indent15 + 'test -n "$TAG" || '
            + baris_tulis15.strip(), 1)
    assert isi_baru15 != isi_aksi15, \
        "suntikan aksi15 tidak menembak"
    with open(berkas_aksi15, "w", encoding="utf-8") as f:
        f.write(isi_baru15)
    rc, out = jalan(rantaiatau, [sys.executable, GERBANG, "--semua"])
    periksa("produsen sesudah operator ||: gerbang lolos (Audit 888)",
            rc == 0)
    shutil.rmtree(rantaiatau, ignore_errors=True)

    potongakhir = klon(tmp, "potongakhir")
    berkas_aksi16 = os.path.join(
        potongakhir, ".github", "actions", "resolve-vw", "action.yml")
    with open(berkas_aksi16, encoding="utf-8") as f:
        isi_aksi16 = f.read()
    baris_tulis16 = [b for b in isi_aksi16.splitlines()
                     if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
    isi_baru16 = isi_aksi16.replace(
            baris_tulis16,
            baris_tulis16.replace(
                '>> "$GITHUB_OUTPUT"',
                '>> "$GITHUB_OUTPUT.bak" > "$GITHUB_OUTPUT"'), 1)
    assert isi_baru16 != isi_aksi16, \
        "suntikan aksi16 tidak menembak"
    with open(berkas_aksi16, "w", encoding="utf-8") as f:
        f.write(isi_baru16)
    rc, out = jalan(potongakhir, [sys.executable, GERBANG, "--semua"])
    periksa("redirect terakhir > tunggal ke keluaran: gerbang lolos (Audit 889)",
            rc == 0)
    shutil.rmtree(potongakhir, ignore_errors=True)

    fddua = klon(tmp, "fddua")
    berkas_aksi17 = os.path.join(
        fddua, ".github", "actions", "resolve-vw", "action.yml")
    with open(berkas_aksi17, encoding="utf-8") as f:
        isi_aksi17 = f.read()
    baris_tulis17 = [b for b in isi_aksi17.splitlines()
                     if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
    isi_baru17 = isi_aksi17.replace(
            baris_tulis17,
            baris_tulis17.replace('>> "$GITHUB_OUTPUT"',
                                  '2>> "$GITHUB_OUTPUT"'), 1)
    assert isi_baru17 != isi_aksi17, \
        "suntikan aksi17 tidak menembak"
    with open(berkas_aksi17, "w", encoding="utf-8") as f:
        f.write(isi_baru17)
    rc, out = jalan(fddua, [sys.executable, GERBANG, "--semua"])
    periksa("redirect stderr 2>> bukan produsen: gerbang gagal (Audit 890)",
            rc != 0 and rc != 124 and "tag_mentah" in out
            and "tak pernah ditulis" in out)
    shutil.rmtree(fddua, ignore_errors=True)

    targetpolos = klon(tmp, "targetpolos")
    berkas_aksi18 = os.path.join(
        targetpolos, ".github", "actions", "resolve-vw", "action.yml")
    with open(berkas_aksi18, encoding="utf-8") as f:
        isi_aksi18 = f.read()
    baris_tulis18 = [b for b in isi_aksi18.splitlines()
                     if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
    isi_baru18 = isi_aksi18.replace(
            baris_tulis18,
            baris_tulis18.replace('>> "$GITHUB_OUTPUT"',
                                  '>> ${GITHUB_OUTPUT}'), 1)
    assert isi_baru18 != isi_aksi18, \
        "suntikan aksi18 tidak menembak"
    with open(berkas_aksi18, "w", encoding="utf-8") as f:
        f.write(isi_baru18)
    rc, out = jalan(targetpolos, [sys.executable, GERBANG, "--semua"])
    periksa("target polos kurung tanpa kutip: gerbang lolos (Audit 891)",
            rc == 0)
    shutil.rmtree(targetpolos, ignore_errors=True)

    paksa = klon(tmp, "paksa")
    berkas_aksi19 = os.path.join(
        paksa, ".github", "actions", "resolve-vw", "action.yml")
    with open(berkas_aksi19, encoding="utf-8") as f:
        isi_aksi19 = f.read()
    baris_tulis19 = [b for b in isi_aksi19.splitlines()
                     if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
    isi_baru19 = isi_aksi19.replace(
            baris_tulis19,
            baris_tulis19.replace('>> "$GITHUB_OUTPUT"',
                                  '>| "$GITHUB_OUTPUT"'), 1)
    assert isi_baru19 != isi_aksi19, \
        "suntikan aksi19 tidak menembak"
    with open(berkas_aksi19, "w", encoding="utf-8") as f:
        f.write(isi_baru19)
    rc, out = jalan(paksa, [sys.executable, GERBANG, "--semua"])
    periksa("redirect clobber >|: gerbang lolos (Audit 892)",
            rc == 0)
    shutil.rmtree(paksa, ignore_errors=True)

    keduafd = klon(tmp, "keduafd")
    berkas_aksi20 = os.path.join(
        keduafd, ".github", "actions", "resolve-vw", "action.yml")
    with open(berkas_aksi20, encoding="utf-8") as f:
        isi_aksi20 = f.read()
    baris_tulis20 = [b for b in isi_aksi20.splitlines()
                     if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
    isi_baru20 = isi_aksi20.replace(
            baris_tulis20,
            baris_tulis20.replace('>> "$GITHUB_OUTPUT"',
                                  '>& "$GITHUB_OUTPUT"'), 1)
    assert isi_baru20 != isi_aksi20, \
        "suntikan aksi20 tidak menembak"
    with open(berkas_aksi20, "w", encoding="utf-8") as f:
        f.write(isi_baru20)
    rc, out = jalan(keduafd, [sys.executable, GERBANG, "--semua"])
    periksa("redirect >& ke berkas: gerbang lolos (Audit 893)",
            rc == 0)
    shutil.rmtree(keduafd, ignore_errors=True)

    bacatulis = klon(tmp, "bacatulis")
    berkas_aksi21 = os.path.join(
        bacatulis, ".github", "actions", "resolve-vw", "action.yml")
    with open(berkas_aksi21, encoding="utf-8") as f:
        isi_aksi21 = f.read()
    baris_tulis21 = [b for b in isi_aksi21.splitlines()
                     if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
    isi_baru21 = isi_aksi21.replace(
            baris_tulis21,
            baris_tulis21.replace('>> "$GITHUB_OUTPUT"',
                                  '<> "$GITHUB_OUTPUT"'), 1)
    assert isi_baru21 != isi_aksi21, \
        "suntikan aksi21 tidak menembak"
    with open(berkas_aksi21, "w", encoding="utf-8") as f:
        f.write(isi_baru21)
    rc, out = jalan(bacatulis, [sys.executable, GERBANG, "--semua"])
    periksa("redirect <> stdin bukan produsen: gerbang gagal (Audit 894)",
            rc != 0 and rc != 124 and "tag_mentah" in out
            and "tak pernah ditulis" in out)
    shutil.rmtree(bacatulis, ignore_errors=True)

    opganda = klon(tmp, "opganda")
    berkas_aksi22 = os.path.join(
        opganda, ".github", "actions", "resolve-vw", "action.yml")
    with open(berkas_aksi22, encoding="utf-8") as f:
        isi_aksi22 = f.read()
    baris_tulis22 = [b for b in isi_aksi22.splitlines()
                     if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
    isi_baru22 = isi_aksi22.replace(
            baris_tulis22,
            baris_tulis22.replace('>> "$GITHUB_OUTPUT"',
                                  '>> > "$GITHUB_OUTPUT"'), 1)
    assert isi_baru22 != isi_aksi22, \
        "suntikan aksi22 tidak menembak"
    with open(berkas_aksi22, "w", encoding="utf-8") as f:
        f.write(isi_baru22)
    rc, out = jalan(opganda, [sys.executable, GERBANG, "--semua"])
    periksa("operator ganda >> > bukan produsen: gerbang gagal (Audit 895)",
            rc != 0 and rc != 124 and "tag_mentah" in out
            and "tak pernah ditulis" in out)
    shutil.rmtree(opganda, ignore_errors=True)

    lampau = klon(tmp, "lampau")
    berkas_aksi23 = os.path.join(
        lampau, ".github", "actions", "resolve-vw", "action.yml")
    with open(berkas_aksi23, encoding="utf-8") as f:
        isi_aksi23 = f.read()
    baris_tulis23 = [b for b in isi_aksi23.splitlines()
                     if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
    isi_baru23 = isi_aksi23.replace(
            baris_tulis23,
            baris_tulis23.replace('>> "$GITHUB_OUTPUT"',
                                  '>>| "$GITHUB_OUTPUT"'), 1)
    assert isi_baru23 != isi_aksi23, \
        "suntikan aksi23 tidak menembak"
    with open(berkas_aksi23, "w", encoding="utf-8") as f:
        f.write(isi_baru23)
    rc, out = jalan(lampau, [sys.executable, GERBANG, "--semua"])
    periksa("append+clobber >>| bukan produsen: gerbang gagal (Audit 896)",
            rc != 0 and rc != 124 and "tag_mentah" in out
            and "tak pernah ditulis" in out)
    shutil.rmtree(lampau, ignore_errors=True)

    sufiks = klon(tmp, "sufiks")
    berkas_aksi24 = os.path.join(
        sufiks, ".github", "actions", "resolve-vw", "action.yml")
    with open(berkas_aksi24, encoding="utf-8") as f:
        isi_aksi24 = f.read()
    baris_tulis24 = [b for b in isi_aksi24.splitlines()
                     if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
    isi_baru24 = isi_aksi24.replace(
            baris_tulis24,
            baris_tulis24.replace('>> "$GITHUB_OUTPUT"',
                                  '>> $GITHUB_OUTPUT}'), 1)
    assert isi_baru24 != isi_aksi24, \
        "suntikan aksi24 tidak menembak"
    with open(berkas_aksi24, "w", encoding="utf-8") as f:
        f.write(isi_baru24)
    rc, out = jalan(sufiks, [sys.executable, GERBANG, "--semua"])
    periksa("sufiks kurung nama berkas bukan produsen: gerbang gagal (Audit 897)",
            rc != 0 and rc != 124 and "tag_mentah" in out
            and "tak pernah ditulis" in out)
    shutil.rmtree(sufiks, ignore_errors=True)

    kurangspasi = klon(tmp, "kurangspasi")
    berkas_aksi25 = os.path.join(
        kurangspasi, ".github", "actions", "resolve-vw", "action.yml")
    with open(berkas_aksi25, encoding="utf-8") as f:
        isi_aksi25 = f.read()
    baris_tulis25 = [b for b in isi_aksi25.splitlines()
                     if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
    isi_baru25 = isi_aksi25.replace(
            baris_tulis25,
            baris_tulis25.replace('>> "$GITHUB_OUTPUT"',
                                  '< > "$GITHUB_OUTPUT"'), 1)
    assert isi_baru25 != isi_aksi25, \
        "suntikan aksi25 tidak menembak"
    with open(berkas_aksi25, "w", encoding="utf-8") as f:
        f.write(isi_baru25)
    rc, out = jalan(kurangspasi, [sys.executable, GERBANG, "--semua"])
    periksa("bentuk < > berjarak bukan produsen: gerbang gagal (Audit 898)",
            rc != 0 and rc != 124 and "tag_mentah" in out
            and "tak pernah ditulis" in out)
    shutil.rmtree(kurangspasi, ignore_errors=True)

    subekor = klon(tmp, "subekor")
    berkas_aksi26 = os.path.join(
        subekor, ".github", "actions", "resolve-vw", "action.yml")
    with open(berkas_aksi26, encoding="utf-8") as f:
        isi_aksi26 = f.read()
    baris_tulis26 = [b for b in isi_aksi26.splitlines()
                     if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
    isi_baru26 = isi_aksi26.replace(
            baris_tulis26,
            baris_tulis26 + " $(true > /dev/null)", 1)
    assert isi_baru26 != isi_aksi26, \
        "suntikan aksi26 tidak menembak"
    with open(berkas_aksi26, "w", encoding="utf-8") as f:
        f.write(isi_baru26)
    rc, out = jalan(subekor, [sys.executable, GERBANG, "--semua"])
    periksa("redirect dalam substitusi ekor tak menimpa: gerbang lolos (Audit 899)",
            rc == 0)
    shutil.rmtree(subekor, ignore_errors=True)

    subarg = klon(tmp, "subarg")
    berkas_aksi27 = os.path.join(
        subarg, ".github", "actions", "resolve-vw", "action.yml")
    with open(berkas_aksi27, encoding="utf-8") as f:
        isi_aksi27 = f.read()
    baris_tulis27 = [b for b in isi_aksi27.splitlines()
                     if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
    isi_baru27 = isi_aksi27.replace(
            baris_tulis27,
            baris_tulis27.replace(
                '>> "$GITHUB_OUTPUT"',
                '$(true >> "$GITHUB_OUTPUT")'), 1)
    assert isi_baru27 != isi_aksi27, \
        "suntikan aksi27 tidak menembak"
    with open(berkas_aksi27, "w", encoding="utf-8") as f:
        f.write(isi_baru27)
    rc, out = jalan(subarg, [sys.executable, GERBANG, "--semua"])
    periksa("redirect dalam substitusi argumen bukan produsen: gerbang gagal (Audit 900)",
            rc != 0 and rc != 124 and "tag_mentah" in out
            and "tak pernah ditulis" in out)
    shutil.rmtree(subarg, ignore_errors=True)

    proses = klon(tmp, "proses")
    berkas_aksi28 = os.path.join(
        proses, ".github", "actions", "resolve-vw", "action.yml")
    with open(berkas_aksi28, encoding="utf-8") as f:
        isi_aksi28 = f.read()
    baris_tulis28 = [b for b in isi_aksi28.splitlines()
                     if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
    isi_baru28 = isi_aksi28.replace(
            baris_tulis28,
            baris_tulis28.replace(
                '>> "$GITHUB_OUTPUT"',
                '>(cat >> "$GITHUB_OUTPUT")'), 1)
    assert isi_baru28 != isi_aksi28, \
        "suntikan aksi28 tidak menembak"
    with open(berkas_aksi28, "w", encoding="utf-8") as f:
        f.write(isi_baru28)
    rc, out = jalan(proses, [sys.executable, GERBANG, "--semua"])
    periksa("substitusi proses bukan produsen: gerbang gagal (Audit 901)",
            rc != 0 and rc != 124 and "tag_mentah" in out
            and "tak pernah ditulis" in out)
    shutil.rmtree(proses, ignore_errors=True)

    proekor = klon(tmp, "proekor")
    berkas_aksi29 = os.path.join(
        proekor, ".github", "actions", "resolve-vw", "action.yml")
    with open(berkas_aksi29, encoding="utf-8") as f:
        isi_aksi29 = f.read()
    baris_tulis29 = [b for b in isi_aksi29.splitlines()
                     if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
    isi_baru29 = isi_aksi29.replace(
            baris_tulis29,
            baris_tulis29 + " >(cat > /dev/null)", 1)
    assert isi_baru29 != isi_aksi29, \
        "suntikan aksi29 tidak menembak"
    with open(berkas_aksi29, "w", encoding="utf-8") as f:
        f.write(isi_baru29)
    rc, out = jalan(proekor, [sys.executable, GERBANG, "--semua"])
    periksa("substitusi proses ekor tak menimpa: gerbang lolos (Audit 902)",
            rc == 0)
    shutil.rmtree(proekor, ignore_errors=True)

    purba = klon(tmp, "purba")
    berkas_aksi30 = os.path.join(
        purba, ".github", "actions", "resolve-vw", "action.yml")
    with open(berkas_aksi30, encoding="utf-8") as f:
        isi_aksi30 = f.read()
    baris_tulis30 = [b for b in isi_aksi30.splitlines()
                     if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
    isi_baru30 = isi_aksi30.replace(
            baris_tulis30,
            baris_tulis30.replace(
                '>> "$GITHUB_OUTPUT"',
                '`true >> "$GITHUB_OUTPUT" `'), 1)
    assert isi_baru30 != isi_aksi30, \
        "suntikan aksi30 tidak menembak"
    with open(berkas_aksi30, "w", encoding="utf-8") as f:
        f.write(isi_baru30)
    rc, out = jalan(purba, [sys.executable, GERBANG, "--semua"])
    periksa("substitusi backtick bukan produsen: gerbang gagal (Audit 903)",
            rc != 0 and rc != 124 and "tag_mentah" in out
            and "tak pernah ditulis" in out)
    shutil.rmtree(purba, ignore_errors=True)

    purbaekor = klon(tmp, "purbaekor")
    berkas_aksi31 = os.path.join(
        purbaekor, ".github", "actions", "resolve-vw", "action.yml")
    with open(berkas_aksi31, encoding="utf-8") as f:
        isi_aksi31 = f.read()
    baris_tulis31 = [b for b in isi_aksi31.splitlines()
                     if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
    isi_baru31 = isi_aksi31.replace(
            baris_tulis31,
            baris_tulis31 + " `true > /dev/null`", 1)
    assert isi_baru31 != isi_aksi31, \
        "suntikan aksi31 tidak menembak"
    with open(berkas_aksi31, "w", encoding="utf-8") as f:
        f.write(isi_baru31)
    rc, out = jalan(purbaekor, [sys.executable, GERBANG, "--semua"])
    periksa("backtick ekor tak menimpa: gerbang lolos (Audit 904)",
            rc == 0)
    shutil.rmtree(purbaekor, ignore_errors=True)

    grupcacat = klon(tmp, "grupcacat")
    berkas_aksi32 = os.path.join(
        grupcacat, ".github", "actions", "resolve-vw", "action.yml")
    with open(berkas_aksi32, encoding="utf-8") as f:
        isi_aksi32 = f.read()
    baris_tulis32 = [b for b in isi_aksi32.splitlines()
                     if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
    indentasi32 = baris_tulis32[:len(baris_tulis32)
                                 - len(baris_tulis32.lstrip())]
    isi_baru32 = isi_aksi32.replace(
            baris_tulis32,
            indentasi32 + "{ " + baris_tulis32.strip() + " }", 1)
    assert isi_baru32 != isi_aksi32, \
        "suntikan aksi32 tidak menembak"
    with open(berkas_aksi32, "w", encoding="utf-8") as f:
        f.write(isi_baru32)
    rc, out = jalan(grupcacat, [sys.executable, GERBANG, "--semua"])
    periksa("grup tanpa titik-koma bukan produsen: gerbang gagal "
            "(Audit 905)",
            rc != 0 and rc != 124 and "tag_mentah" in out
            and "tak pernah ditulis" in out)
    shutil.rmtree(grupcacat, ignore_errors=True)

    pecahsub = klon(tmp, "pecahsub")
    berkas_aksi33 = os.path.join(
        pecahsub, ".github", "actions", "resolve-vw", "action.yml")
    with open(berkas_aksi33, encoding="utf-8") as f:
        isi_aksi33 = f.read()
    baris_tulis33 = [b for b in isi_aksi33.splitlines()
                     if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
    isi_baru33 = isi_aksi33.replace(
            baris_tulis33,
            baris_tulis33.replace(
                '>> "$GITHUB_OUTPUT"',
                '`echo x; echo y` >> "$GITHUB_OUTPUT"'), 1)
    assert isi_baru33 != isi_aksi33, \
        "suntikan aksi33 tidak menembak"
    with open(berkas_aksi33, "w", encoding="utf-8") as f:
        f.write(isi_baru33)
    rc, out = jalan(pecahsub, [sys.executable, GERBANG, "--semua"])
    periksa("pemisah dalam substitusi tak memecah: gerbang lolos "
            "(Audit 906)", rc == 0)
    shutil.rmtree(pecahsub, ignore_errors=True)

    pagartikus = klon(tmp, "pagartikus")
    berkas_aksi34 = os.path.join(
        pagartikus, ".github", "actions", "resolve-vw", "action.yml")
    with open(berkas_aksi34, encoding="utf-8") as f:
        isi_aksi34 = f.read()
    baris_tulis34 = [b for b in isi_aksi34.splitlines()
                     if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
    isi_baru34 = isi_aksi34.replace(
            baris_tulis34,
            baris_tulis34.replace(
                '>> "$GITHUB_OUTPUT"',
                '`echo #` >> "$GITHUB_OUTPUT"'), 1)
    assert isi_baru34 != isi_aksi34, \
        "suntikan aksi34 tidak menembak"
    with open(berkas_aksi34, "w", encoding="utf-8") as f:
        f.write(isi_baru34)
    rc, out = jalan(pagartikus, [sys.executable, GERBANG, "--semua"])
    periksa("pagar dalam backtick bukan komentar: gerbang lolos "
            "(Audit 907)", rc == 0)
    shutil.rmtree(pagartikus, ignore_errors=True)

    luput = klon(tmp, "luput")
    berkas_aksi35 = os.path.join(
        luput, ".github", "actions", "resolve-vw", "action.yml")
    with open(berkas_aksi35, encoding="utf-8") as f:
        isi_aksi35 = f.read()
    baris_tulis35 = [b for b in isi_aksi35.splitlines()
                     if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
    isi_baru35 = isi_aksi35.replace(
            baris_tulis35,
            baris_tulis35.replace(
                '>> "$GITHUB_OUTPUT"',
                '\\` >> "$GITHUB_OUTPUT"'), 1)
    assert isi_baru35 != isi_aksi35, \
        "suntikan aksi35 tidak menembak"
    with open(berkas_aksi35, "w", encoding="utf-8") as f:
        f.write(isi_baru35)
    rc, out = jalan(luput, [sys.executable, GERBANG, "--semua"])
    periksa("backtick ter-escape literal: gerbang lolos (Audit 908)",
            rc == 0)
    shutil.rmtree(luput, ignore_errors=True)

    luputpanah = klon(tmp, "luputpanah")
    berkas_aksi36 = os.path.join(
        luputpanah, ".github", "actions", "resolve-vw", "action.yml")
    with open(berkas_aksi36, encoding="utf-8") as f:
        isi_aksi36 = f.read()
    baris_tulis36 = [b for b in isi_aksi36.splitlines()
                     if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
    isi_baru36 = isi_aksi36.replace(
            baris_tulis36,
            baris_tulis36.replace(
                '>> "$GITHUB_OUTPUT"',
                '> /dev/null \\> "$GITHUB_OUTPUT"'), 1)
    assert isi_baru36 != isi_aksi36, \
        "suntikan aksi36 tidak menembak"
    with open(berkas_aksi36, "w", encoding="utf-8") as f:
        f.write(isi_baru36)
    rc, out = jalan(luputpanah, [sys.executable, GERBANG, "--semua"])
    periksa("panah ter-escape bukan penulisan: gerbang gagal "
            "(Audit 909)",
            rc != 0 and rc != 124 and "tag_mentah" in out
            and "tak pernah ditulis" in out)
    shutil.rmtree(luputpanah, ignore_errors=True)

    tempelpro = klon(tmp, "tempelpro")
    berkas_aksi37 = os.path.join(
        tempelpro, ".github", "actions", "resolve-vw", "action.yml")
    with open(berkas_aksi37, encoding="utf-8") as f:
        isi_aksi37 = f.read()
    baris_tulis37 = [b for b in isi_aksi37.splitlines()
                     if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
    isi_baru37 = isi_aksi37.replace(
            baris_tulis37,
            baris_tulis37.replace(
                '>> "$GITHUB_OUTPUT"',
                '>>(cat >> "$GITHUB_OUTPUT")'), 1)
    assert isi_baru37 != isi_aksi37, \
        "suntikan aksi37 tidak menembak"
    with open(berkas_aksi37, "w", encoding="utf-8") as f:
        f.write(isi_baru37)
    rc, out = jalan(tempelpro, [sys.executable, GERBANG, "--semua"])
    periksa("target subshell menempel bukan produsen: gerbang gagal "
            "(Audit 910)",
            rc != 0 and rc != 124 and "tag_mentah" in out
            and "tak pernah ditulis" in out)
    shutil.rmtree(tempelpro, ignore_errors=True)

    jarakpro = klon(tmp, "jarakpro")
    berkas_aksi38 = os.path.join(
        jarakpro, ".github", "actions", "resolve-vw", "action.yml")
    with open(berkas_aksi38, encoding="utf-8") as f:
        isi_aksi38 = f.read()
    baris_tulis38 = [b for b in isi_aksi38.splitlines()
                     if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
    isi_baru38 = isi_aksi38.replace(
            baris_tulis38,
            baris_tulis38.replace(
                '>> "$GITHUB_OUTPUT"',
                '> >(cat >> "$GITHUB_OUTPUT")'), 1)
    assert isi_baru38 != isi_aksi38, \
        "suntikan aksi38 tidak menembak"
    with open(berkas_aksi38, "w", encoding="utf-8") as f:
        f.write(isi_baru38)
    rc, out = jalan(jarakpro, [sys.executable, GERBANG, "--semua"])
    periksa("target substitusi proses berjarak produsen: gerbang "
            "lolos (Audit 911)", rc == 0)
    shutil.rmtree(jarakpro, ignore_errors=True)

    grupredir = klon(tmp, "grupredir")
    berkas_aksi39 = os.path.join(
        grupredir, ".github", "actions", "resolve-vw", "action.yml")
    with open(berkas_aksi39, encoding="utf-8") as f:
        isi_aksi39 = f.read()
    baris_tulis39 = [b for b in isi_aksi39.splitlines()
                     if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
    indentasi39 = baris_tulis39[:len(baris_tulis39)
                                - len(baris_tulis39.lstrip())]
    inti39 = baris_tulis39.strip().replace(
        ' >> "$GITHUB_OUTPUT"', "")
    isi_baru39 = isi_aksi39.replace(
            baris_tulis39,
            indentasi39 + "{ " + inti39
            + "; } >> \"$GITHUB_OUTPUT\"", 1)
    assert isi_baru39 != isi_aksi39, \
        "suntikan aksi39 tidak menembak"
    with open(berkas_aksi39, "w", encoding="utf-8") as f:
        f.write(isi_baru39)
    rc, out = jalan(grupredir, [sys.executable, GERBANG, "--semua"])
    periksa("redirect tingkat grup produsen: gerbang lolos "
            "(Audit 912)", rc == 0)
    shutil.rmtree(grupredir, ignore_errors=True)

    kurungtempel = klon(tmp, "kurungtempel")
    berkas_aksi40 = os.path.join(
        kurungtempel, ".github", "actions", "resolve-vw", "action.yml")
    with open(berkas_aksi40, encoding="utf-8") as f:
        isi_aksi40 = f.read()
    baris_tulis40 = [b for b in isi_aksi40.splitlines()
                     if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
    indentasi40 = baris_tulis40[:len(baris_tulis40)
                                - len(baris_tulis40.lstrip())]
    isi_baru40 = isi_aksi40.replace(
            baris_tulis40,
            indentasi40 + "{" + baris_tulis40.strip() + "; }", 1)
    assert isi_baru40 != isi_aksi40, \
        "suntikan aksi40 tidak menembak"
    with open(berkas_aksi40, "w", encoding="utf-8") as f:
        f.write(isi_baru40)
    rc, out = jalan(kurungtempel, [sys.executable, GERBANG, "--semua"])
    periksa("kurung menempel bukan grup: gerbang gagal (Audit 913)",
            rc != 0 and rc != 124 and "tag_mentah" in out
            and "tak pernah ditulis" in out)
    shutil.rmtree(kurungtempel, ignore_errors=True)

    grupsendiri = klon(tmp, "grupsendiri")
    berkas_aksi41 = os.path.join(
        grupsendiri, ".github", "actions", "resolve-vw", "action.yml")
    with open(berkas_aksi41, encoding="utf-8") as f:
        isi_aksi41 = f.read()
    baris_tulis41 = [b for b in isi_aksi41.splitlines()
                     if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
    indentasi41 = baris_tulis41[:len(baris_tulis41)
                                - len(baris_tulis41.lstrip())]
    inti41 = baris_tulis41.strip().replace(
        '>> "$GITHUB_OUTPUT"', '> /dev/null')
    isi_baru41 = isi_aksi41.replace(
            baris_tulis41,
            indentasi41 + "{ " + inti41
            + "; } >> \"$GITHUB_OUTPUT\"", 1)
    assert isi_baru41 != isi_aksi41, \
        "suntikan aksi41 tidak menembak"
    with open(berkas_aksi41, "w", encoding="utf-8") as f:
        f.write(isi_baru41)
    rc, out = jalan(grupsendiri, [sys.executable, GERBANG, "--semua"])
    periksa("echo grup ber-redirect sendiri bukan kandidat: gerbang "
            "gagal (Audit 914)",
            rc != 0 and rc != 124 and "tag_mentah" in out
            and "tak pernah ditulis" in out)
    shutil.rmtree(grupsendiri, ignore_errors=True)

    kulitredir = klon(tmp, "kulitredir")
    berkas_aksi42 = os.path.join(
        kulitredir, ".github", "actions", "resolve-vw", "action.yml")
    with open(berkas_aksi42, encoding="utf-8") as f:
        isi_aksi42 = f.read()
    baris_tulis42 = [b for b in isi_aksi42.splitlines()
                     if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
    indentasi42 = baris_tulis42[:len(baris_tulis42)
                                - len(baris_tulis42.lstrip())]
    inti42 = baris_tulis42.strip().replace(
        ' >> "$GITHUB_OUTPUT"', "")
    isi_baru42 = isi_aksi42.replace(
            baris_tulis42,
            indentasi42 + "( " + inti42
            + "; ) >> \"$GITHUB_OUTPUT\"", 1)
    assert isi_baru42 != isi_aksi42, \
        "suntikan aksi42 tidak menembak"
    with open(berkas_aksi42, "w", encoding="utf-8") as f:
        f.write(isi_baru42)
    rc, out = jalan(kulitredir, [sys.executable, GERBANG, "--semua"])
    periksa("redirect tingkat subshell produsen: gerbang lolos "
            "(Audit 915)", rc == 0)
    shutil.rmtree(kulitredir, ignore_errors=True)

    proseimbang = klon(tmp, "proseimbang")
    berkas_aksi43 = os.path.join(
        proseimbang, ".github", "actions", "resolve-vw", "action.yml")
    with open(berkas_aksi43, encoding="utf-8") as f:
        isi_aksi43 = f.read()
    baris_tulis43 = [b for b in isi_aksi43.splitlines()
                     if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
    isi_baru43 = isi_aksi43.replace(
            baris_tulis43,
            baris_tulis43.replace(
                '>> "$GITHUB_OUTPUT"',
                '> >(cat >> "$GITHUB_OUTPUT"'), 1)
    assert isi_baru43 != isi_aksi43, \
        "suntikan aksi43 tidak menembak"
    with open(berkas_aksi43, "w", encoding="utf-8") as f:
        f.write(isi_baru43)
    rc, out = jalan(proseimbang, [sys.executable, GERBANG, "--semua"])
    periksa("target substitusi tak seimbang bukan produsen: gerbang "
            "gagal (Audit 916)",
            rc != 0 and rc != 124 and "tag_mentah" in out
            and "tak pernah ditulis" in out)
    shutil.rmtree(proseimbang, ignore_errors=True)

    prosisa = klon(tmp, "prosisa")
    berkas_aksi44 = os.path.join(
        prosisa, ".github", "actions", "resolve-vw", "action.yml")
    with open(berkas_aksi44, encoding="utf-8") as f:
        isi_aksi44 = f.read()
    baris_tulis44 = [b for b in isi_aksi44.splitlines()
                     if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
    isi_baru44 = isi_aksi44.replace(
            baris_tulis44,
            baris_tulis44.replace(
                '>> "$GITHUB_OUTPUT"',
                '> >(cat > /tmp/lain) >> "$GITHUB_OUTPUT"'), 1)
    assert isi_baru44 != isi_aksi44, \
        "suntikan aksi44 tidak menembak"
    with open(berkas_aksi44, "w", encoding="utf-8") as f:
        f.write(isi_baru44)
    rc, out = jalan(prosisa, [sys.executable, GERBANG, "--semua"])
    periksa("redirect sah sesudah target substitusi: gerbang lolos "
            "(Audit 917)", rc == 0)
    shutil.rmtree(prosisa, ignore_errors=True)

    potongluput = klon(tmp, "potongluput")
    berkas_aksi45 = os.path.join(
        potongluput, ".github", "actions", "resolve-vw", "action.yml")
    with open(berkas_aksi45, encoding="utf-8") as f:
        isi_aksi45 = f.read()
    baris_tulis45 = [b for b in isi_aksi45.splitlines()
                     if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
    isi_baru45 = isi_aksi45.replace(
            baris_tulis45,
            baris_tulis45.replace(
                '>> "$GITHUB_OUTPUT"',
                '\\` >> "$GITHUB_OUTPUT" # dulu > /tmp/lain'), 1)
    assert isi_baru45 != isi_aksi45, \
        "suntikan aksi45 tidak menembak"
    with open(berkas_aksi45, "w", encoding="utf-8") as f:
        f.write(isi_baru45)
    rc, out = jalan(potongluput, [sys.executable, GERBANG, "--semua"])
    periksa("komentar sesudah backtick literal terpotong: gerbang "
            "lolos (Audit 918)", rc == 0)
    shutil.rmtree(potongluput, ignore_errors=True)

    parameter = klon(tmp, "parameter")
    berkas_aksi46 = os.path.join(
        parameter, ".github", "actions", "resolve-vw", "action.yml")
    with open(berkas_aksi46, encoding="utf-8") as f:
        isi_aksi46 = f.read()
    baris_tulis46 = [b for b in isi_aksi46.splitlines()
                     if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
    isi_baru46 = isi_aksi46.replace(
            baris_tulis46,
            baris_tulis46.replace(
                'echo "tag_mentah=$TAG"',
                '{ echo "tag_mentah=$TAG" ${x:-a>b}; }'), 1)
    assert isi_baru46 != isi_aksi46, \
        "suntikan aksi46 tidak menembak"
    with open(berkas_aksi46, "w", encoding="utf-8") as f:
        f.write(isi_baru46)
    rc, out = jalan(parameter, [sys.executable, GERBANG, "--semua"])
    periksa("panah di nilai bawaan ekspansi tak menyaring kandidat: "
            "gerbang lolos (Audit 919)", rc == 0)
    shutil.rmtree(parameter, ignore_errors=True)

    sarang = klon(tmp, "sarang")
    berkas_aksi47 = os.path.join(
        sarang, ".github", "actions", "resolve-vw", "action.yml")
    with open(berkas_aksi47, encoding="utf-8") as f:
        isi_aksi47 = f.read()
    baris_tulis47 = [b for b in isi_aksi47.splitlines()
                     if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
    isi_baru47 = isi_aksi47.replace(
            baris_tulis47,
            '        run: |\n'
            '          TAG="v1"\n'
            '          { ( echo "tag_mentah=$TAG"; ) > /dev/null; } '
            '>> "$GITHUB_OUTPUT"\n', 1)
    assert isi_baru47 != isi_aksi47, \
        "suntikan aksi47 tidak menembak"
    with open(berkas_aksi47, "w", encoding="utf-8") as f:
        f.write(isi_baru47)
    rc, out = jalan(sarang, [sys.executable, GERBANG, "--semua"])
    periksa("redirect dalam mengalahkan kredit luar: gerbang gagal "
            "(Audit 920)", rc != 0 and rc != 124 and "Traceback" not in out)
    shutil.rmtree(sarang, ignore_errors=True)

    sarangbalik = klon(tmp, "sarangbalik")
    berkas_aksi48 = os.path.join(
        sarangbalik, ".github", "actions", "resolve-vw", "action.yml")
    with open(berkas_aksi48, encoding="utf-8") as f:
        isi_aksi48 = f.read()
    # Audit 1440: jarum lama skenario ini (blok run:
    # versi awal action) sudah tak ada di action.yml
    # sehingga suntikannya tak pernah menembak dan
    # skenario diam-diam menguji pohon bersih. Jarum
    # kini baris tag_mentah yang hidup, diganti
    # susunan Audit 921 yang persis: redirect
    # subshell-DALAM ke $GITHUB_OUTPUT mengesahkan
    # tulisan walau grup luarnya ke /dev/null (bentuk
    # sebaliknya — dalam /dev/null, luar output —
    # milik Audit 920 dan wajib GAGAL).
    isi_baru48 = isi_aksi48.replace(
            '        echo "tag_mentah=$TAG" >> "$GITHUB_OUTPUT"\n',
            '        { ( echo "tag_mentah=$TAG"; ) '
            '>> "$GITHUB_OUTPUT"; } > /dev/null\n', 1)
    assert isi_baru48 != isi_aksi48, \
        "suntikan aksi48 tidak menembak"
    with open(berkas_aksi48, "w", encoding="utf-8") as f:
        f.write(isi_baru48)
    rc, out = jalan(sarangbalik, [sys.executable, GERBANG, "--semua"])
    periksa("redirect subshell-dalam mengesahkan kandidat: gerbang "
            "lolos (Audit 921)", rc == 0)
    shutil.rmtree(sarangbalik, ignore_errors=True)

    kurungutip = klon(tmp, "kurungutip")
    berkas_aksi49 = os.path.join(
        kurungutip, ".github", "actions", "resolve-vw", "action.yml")
    with open(berkas_aksi49, encoding="utf-8") as f:
        isi_aksi49 = f.read()
    baris_tulis49 = [b for b in isi_aksi49.splitlines()
                     if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
    isi_baru49 = isi_aksi49.replace(
            baris_tulis49,
            baris_tulis49.replace(
                '>> "$GITHUB_OUTPUT"',
                '> >(echo "("; cat >> "$GITHUB_OUTPUT")'), 1)
    assert isi_baru49 != isi_aksi49, \
        "suntikan aksi49 tidak menembak"
    with open(berkas_aksi49, "w", encoding="utf-8") as f:
        f.write(isi_baru49)
    rc, out = jalan(kurungutip, [sys.executable, GERBANG, "--semua"])
    periksa("kurung berkutip di target substitusi tak membatalkan "
            "vonis: gerbang lolos (Audit 922)", rc == 0)
    shutil.rmtree(kurungutip, ignore_errors=True)

    pagarkata = klon(tmp, "pagarkata")
    berkas_aksi50 = os.path.join(
        pagarkata, ".github", "actions", "resolve-vw", "action.yml")
    with open(berkas_aksi50, encoding="utf-8") as f:
        isi_aksi50 = f.read()
    baris_tulis50 = [b for b in isi_aksi50.splitlines()
                     if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
    isi_baru50 = isi_aksi50.replace(
            baris_tulis50,
            baris_tulis50.replace(
                'echo "tag_mentah=$TAG"',
                'echo "tag_mentah=$TAG" x\\ #'), 1)
    assert isi_baru50 != isi_aksi50, \
        "suntikan aksi50 tidak menembak"
    with open(berkas_aksi50, "w", encoding="utf-8") as f:
        f.write(isi_baru50)
    rc, out = jalan(pagarkata, [sys.executable, GERBANG, "--semua"])
    periksa("pagar tengah kata sesudah spasi ter-escape bukan "
            "komentar: gerbang lolos (Audit 923)", rc == 0)
    shutil.rmtree(pagarkata, ignore_errors=True)

    grupgeser = klon(tmp, "grupgeser")
    berkas_aksi51 = os.path.join(
        grupgeser, ".github", "actions", "resolve-vw", "action.yml")
    with open(berkas_aksi51, encoding="utf-8") as f:
        isi_aksi51 = f.read()
    baris_tulis51 = [b for b in isi_aksi51.splitlines()
                     if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
    isi_baru51 = isi_aksi51.replace(
            baris_tulis51,
            baris_tulis51.replace(
                'echo "tag_mentah=$TAG"',
                '{ echo "tag_mentah=$TAG" }'), 1)
    assert isi_baru51 != isi_aksi51, \
        "suntikan aksi51 tidak menembak"
    with open(berkas_aksi51, "w", encoding="utf-8") as f:
        f.write(isi_baru51)
    rc, out = jalan(grupgeser, [sys.executable, GERBANG, "--semua"])
    periksa("grup tak sah bergeser redirect tetap ditolak: gerbang "
            "gagal (Audit 924)", rc != 0 and rc != 124 and "Traceback" not in out)
    shutil.rmtree(grupgeser, ignore_errors=True)

    paramsub = klon(tmp, "paramsub")
    berkas_aksi52 = os.path.join(
        paramsub, ".github", "actions", "resolve-vw", "action.yml")
    with open(berkas_aksi52, encoding="utf-8") as f:
        isi_aksi52 = f.read()
    baris_tulis52 = [b for b in isi_aksi52.splitlines()
                     if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
    isi_baru52 = isi_aksi52.replace(
            baris_tulis52,
            baris_tulis52.replace(
                'echo "tag_mentah=$TAG"',
                '{ echo "tag_mentah=$TAG" ${x:-$(echo })>y}; }'), 1)
    assert isi_baru52 != isi_aksi52, \
        "suntikan aksi52 tidak menembak"
    with open(berkas_aksi52, "w", encoding="utf-8") as f:
        f.write(isi_baru52)
    rc, out = jalan(paramsub, [sys.executable, GERBANG, "--semua"])
    periksa("kurung isi substitusi tak menutup ekspansi: gerbang "
            "lolos (Audit 925)", rc == 0)
    shutil.rmtree(paramsub, ignore_errors=True)

    aritmetika = klon(tmp, "aritmetika")
    berkas_aksi53 = os.path.join(
        aritmetika, ".github", "actions", "resolve-vw", "action.yml")
    with open(berkas_aksi53, encoding="utf-8") as f:
        isi_aksi53 = f.read()
    baris_tulis53 = [b for b in isi_aksi53.splitlines()
                     if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
    isi_baru53 = isi_aksi53.replace(
            baris_tulis53,
            baris_tulis53.replace(
                'echo "tag_mentah=$TAG"',
                '{ (( 1 > 0 )); echo "tag_mentah=$TAG"; }'), 1)
    assert isi_baru53 != isi_aksi53, \
        "suntikan aksi53 tidak menembak"
    with open(berkas_aksi53, "w", encoding="utf-8") as f:
        f.write(isi_baru53)
    rc, out = jalan(aritmetika, [sys.executable, GERBANG, "--semua"])
    periksa("operator aritmetika bukan subshell gadungan: gerbang "
            "lolos (Audit 926)", rc == 0)
    shutil.rmtree(aritmetika, ignore_errors=True)

    kurungluput = klon(tmp, "kurungluput")
    berkas_aksi54 = os.path.join(
        kurungluput, ".github", "actions", "resolve-vw", "action.yml")
    with open(berkas_aksi54, encoding="utf-8") as f:
        isi_aksi54 = f.read()
    baris_tulis54 = [b for b in isi_aksi54.splitlines()
                     if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
    isi_baru54 = isi_aksi54.replace(
            baris_tulis54,
            baris_tulis54.replace(
                '>> "$GITHUB_OUTPUT"',
                '> >(echo \\(; cat >> "$GITHUB_OUTPUT")'), 1)
    assert isi_baru54 != isi_aksi54, \
        "suntikan aksi54 tidak menembak"
    with open(berkas_aksi54, "w", encoding="utf-8") as f:
        f.write(isi_baru54)
    rc, out = jalan(kurungluput, [sys.executable, GERBANG, "--semua"])
    periksa("kurung ter-escape di target substitusi tak membatalkan "
            "vonis: gerbang lolos (Audit 927)", rc == 0)
    shutil.rmtree(kurungluput, ignore_errors=True)

    polakutip = klon(tmp, "polakutip")
    berkas_aksi55 = os.path.join(
        polakutip, ".github", "actions", "resolve-vw", "action.yml")
    with open(berkas_aksi55, encoding="utf-8") as f:
        isi_aksi55 = f.read()
    baris_tulis55 = [b for b in isi_aksi55.splitlines()
                     if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
    isi_baru55 = isi_aksi55.replace(
            baris_tulis55,
            baris_tulis55.replace(
                'echo "tag_mentah=$TAG"',
                '{ echo "tag_mentah=$TAG } >> x"; }'), 1)
    assert isi_baru55 != isi_aksi55, \
        "suntikan aksi55 tidak menembak"
    with open(berkas_aksi55, "w", encoding="utf-8") as f:
        f.write(isi_baru55)
    rc, out = jalan(polakutip, [sys.executable, GERBANG, "--semua"])
    periksa("pola kurung di dalam string argumen bukan kurung "
            "argumen: gerbang lolos (Audit 928)", rc == 0)
    shutil.rmtree(polakutip, ignore_errors=True)

    grupgeser2 = klon(tmp, "grupgeser2")
    berkas_aksi56 = os.path.join(
        grupgeser2, ".github", "actions", "resolve-vw", "action.yml")
    with open(berkas_aksi56, encoding="utf-8") as f:
        isi_aksi56 = f.read()
    baris_tulis56 = [b for b in isi_aksi56.splitlines()
                     if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
    isi_baru56 = isi_aksi56.replace(
            baris_tulis56,
            baris_tulis56.replace(
                'echo "tag_mentah=$TAG" >> "$GITHUB_OUTPUT"',
                '{ echo "tag_mentah=$TAG" } | cat '
                '>> "$GITHUB_OUTPUT"'), 1)
    assert isi_baru56 != isi_aksi56, \
        "suntikan aksi56 tidak menembak"
    with open(berkas_aksi56, "w", encoding="utf-8") as f:
        f.write(isi_baru56)
    rc, out = jalan(grupgeser2, [sys.executable, GERBANG, "--semua"])
    periksa("grup tak sah bergeser pipa tetap ditolak: gerbang "
            "gagal (Audit 929)", rc != 0 and rc != 124 and "Traceback" not in out)
    shutil.rmtree(grupgeser2, ignore_errors=True)

    hitung = klon(tmp, "hitung")
    berkas_aksi57 = os.path.join(
        hitung, ".github", "actions", "resolve-vw", "action.yml")
    with open(berkas_aksi57, encoding="utf-8") as f:
        isi_aksi57 = f.read()
    baris_tulis57 = [b for b in isi_aksi57.splitlines()
                     if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
    isi_baru57 = isi_aksi57.replace(
            baris_tulis57,
            baris_tulis57.replace(
                'echo "tag_mentah=$TAG"',
                '(( echo "tag_mentah=$TAG"))'), 1)
    assert isi_baru57 != isi_aksi57, \
        "suntikan aksi57 tidak menembak"
    with open(berkas_aksi57, "w", encoding="utf-8") as f:
        f.write(isi_baru57)
    rc, out = jalan(hitung, [sys.executable, GERBANG, "--semua"])
    periksa("teks mirip echo di dalam aritmetika tak terkredit: "
            "gerbang gagal (Audit 930)", rc != 0 and rc != 124 and "Traceback" not in out)
    shutil.rmtree(hitung, ignore_errors=True)

    subbanyak = klon(tmp, "subbanyak")
    berkas_aksi58 = os.path.join(
        subbanyak, ".github", "actions", "resolve-vw", "action.yml")
    with open(berkas_aksi58, encoding="utf-8") as f:
        isi_aksi58 = f.read()
    baris_tulis58 = [b for b in isi_aksi58.splitlines()
                     if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
    isi_baru58 = isi_aksi58.replace(
            baris_tulis58,
            baris_tulis58.replace(
                '>> "$GITHUB_OUTPUT"',
                '> >(cat >> "$GITHUB_OUTPUT"; echo x '
                '> /dev/null)'), 1)
    assert isi_baru58 != isi_aksi58, \
        "suntikan aksi58 tidak menembak"
    with open(berkas_aksi58, "w", encoding="utf-8") as f:
        f.write(isi_baru58)
    rc, out = jalan(subbanyak, [sys.executable, GERBANG, "--semua"])
    periksa("penulis pertama isi substitusi yang berlaku: gerbang "
            "lolos (Audit 931)", rc == 0)
    shutil.rmtree(subbanyak, ignore_errors=True)

    subkuras = klon(tmp, "subkuras")
    berkas_aksi59 = os.path.join(
        subkuras, ".github", "actions", "resolve-vw", "action.yml")
    with open(berkas_aksi59, encoding="utf-8") as f:
        isi_aksi59 = f.read()
    baris_tulis59 = [b for b in isi_aksi59.splitlines()
                     if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
    isi_baru59 = isi_aksi59.replace(
            baris_tulis59,
            baris_tulis59.replace(
                '>> "$GITHUB_OUTPUT"',
                '> >(cat > /dev/null; cat '
                '>> "$GITHUB_OUTPUT")'), 1)
    assert isi_baru59 != isi_aksi59, \
        "suntikan aksi59 tidak menembak"
    with open(berkas_aksi59, "w", encoding="utf-8") as f:
        f.write(isi_baru59)
    rc, out = jalan(subkuras, [sys.executable, GERBANG, "--semua"])
    periksa("penulis kedua kehabisan stdin tak terkredit: gerbang "
            "gagal (Audit 931)", rc != 0 and rc != 124 and "Traceback" not in out)
    shutil.rmtree(subkuras, ignore_errors=True)

    kurungekspansi = klon(tmp, "kurungekspansi")
    berkas_aksi60 = os.path.join(
        kurungekspansi, ".github", "actions", "resolve-vw",
        "action.yml")
    with open(berkas_aksi60, encoding="utf-8") as f:
        isi_aksi60 = f.read()
    baris_tulis60 = [b for b in isi_aksi60.splitlines()
                     if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
    isi_baru60 = isi_aksi60.replace(
            baris_tulis60,
            baris_tulis60.replace(
                'echo "tag_mentah=$TAG"',
                '{ echo "tag_mentah=$TAG" {a,b}c; }'), 1)
    assert isi_baru60 != isi_aksi60, \
        "suntikan aksi60 tidak menembak"
    with open(berkas_aksi60, "w", encoding="utf-8") as f:
        f.write(isi_baru60)
    rc, out = jalan(kurungekspansi,
                    [sys.executable, GERBANG, "--semua"])
    periksa("kurung tutup ekspansi kurung bukan kurung argumen "
            "yatim: gerbang lolos (Audit 933)", rc == 0)
    shutil.rmtree(kurungekspansi, ignore_errors=True)

    kurungtempel = klon(tmp, "kurungtempel2")
    berkas_aksi61 = os.path.join(
        kurungtempel, ".github", "actions", "resolve-vw",
        "action.yml")
    with open(berkas_aksi61, encoding="utf-8") as f:
        isi_aksi61 = f.read()
    baris_tulis61 = [b for b in isi_aksi61.splitlines()
                     if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
    isi_baru61 = isi_aksi61.replace(
            baris_tulis61,
            baris_tulis61.replace(
                'echo "tag_mentah=$TAG"',
                '{ echo "tag_mentah=$TAG" x}; }'), 1)
    assert isi_baru61 != isi_aksi61, \
        "suntikan aksi61 tidak menembak"
    with open(berkas_aksi61, "w", encoding="utf-8") as f:
        f.write(isi_baru61)
    rc, out = jalan(kurungtempel,
                    [sys.executable, GERBANG, "--semua"])
    periksa("kurung menempel kata tanpa redirect sendiri sah "
            "bila penutup mengikuti: gerbang lolos (Audit 933)",
            rc == 0)
    shutil.rmtree(kurungtempel, ignore_errors=True)

    subberkas = klon(tmp, "subberkas")
    berkas_aksi62 = os.path.join(
        subberkas, ".github", "actions", "resolve-vw", "action.yml")
    with open(berkas_aksi62, encoding="utf-8") as f:
        isi_aksi62 = f.read()
    baris_tulis62 = [b for b in isi_aksi62.splitlines()
                     if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
    isi_baru62 = isi_aksi62.replace(
            baris_tulis62,
            baris_tulis62.replace(
                '>> "$GITHUB_OUTPUT"',
                '> >(cat /tmp/berkas > /dev/null; cat '
                '>> "$GITHUB_OUTPUT")'), 1)
    assert isi_baru62 != isi_aksi62, \
        "suntikan aksi62 tidak menembak"
    with open(berkas_aksi62, "w", encoding="utf-8") as f:
        f.write(isi_baru62)
    rc, out = jalan(subberkas, [sys.executable, GERBANG, "--semua"])
    periksa("pembaca berargumen berkas tak menguras stdin: "
            "gerbang lolos (Audit 934)", rc == 0)
    shutil.rmtree(subberkas, ignore_errors=True)

    sublingkar = klon(tmp, "sublingkar")
    berkas_aksi63 = os.path.join(
        sublingkar, ".github", "actions", "resolve-vw",
        "action.yml")
    with open(berkas_aksi63, encoding="utf-8") as f:
        isi_aksi63 = f.read()
    baris_tulis63 = [b for b in isi_aksi63.splitlines()
                     if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
    isi_baru63 = isi_aksi63.replace(
            baris_tulis63,
            baris_tulis63.replace(
                '>> "$GITHUB_OUTPUT"',
                '> >(while read l; do echo "$l" > /dev/null; '
                'done; cat >> "$GITHUB_OUTPUT")'), 1)
    assert isi_baru63 != isi_aksi63, \
        "suntikan aksi63 tidak menembak"
    with open(berkas_aksi63, "w", encoding="utf-8") as f:
        f.write(isi_baru63)
    rc, out = jalan(sublingkar, [sys.executable, GERBANG, "--semua"])
    periksa("lingkar while-read menguras stdin: penulis pembaca "
            "sesudahnya tak terkredit (Audit 935)", rc != 0 and rc != 124 and "Traceback" not in out)
    shutil.rmtree(sublingkar, ignore_errors=True)

    kurungtikus = klon(tmp, "kurungtikus")
    berkas_aksi64 = os.path.join(
        kurungtikus, ".github", "actions", "resolve-vw",
        "action.yml")
    with open(berkas_aksi64, encoding="utf-8") as f:
        isi_aksi64 = f.read()
    baris_tulis64 = [b for b in isi_aksi64.splitlines()
                     if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
    isi_baru64 = isi_aksi64.replace(
            baris_tulis64,
            baris_tulis64.replace(
                'echo "tag_mentah=$TAG"',
                '{ echo "tag_mentah=$TAG" {a`echo }` }'), 1)
    assert isi_baru64 != isi_aksi64, \
        "suntikan aksi64 tidak menembak"
    with open(berkas_aksi64, "w", encoding="utf-8") as f:
        f.write(isi_baru64)
    rc, out = jalan(kurungtikus, [sys.executable, GERBANG, "--semua"])
    periksa("kurung buka kata sebelumnya tak menyerap kurung "
            "yatim sesudah backtick: gerbang gagal (Audit 936)",
            rc != 0 and rc != 124 and "Traceback" not in out)
    shutil.rmtree(kurungtikus, ignore_errors=True)

    subbungkus = klon(tmp, "subbungkus")
    berkas_aksi65 = os.path.join(
        subbungkus, ".github", "actions", "resolve-vw",
        "action.yml")
    with open(berkas_aksi65, encoding="utf-8") as f:
        isi_aksi65 = f.read()
    baris_tulis65 = [b for b in isi_aksi65.splitlines()
                     if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
    isi_baru65 = isi_aksi65.replace(
            baris_tulis65,
            baris_tulis65.replace(
                '>> "$GITHUB_OUTPUT"',
                '> >(LC_ALL=C cat > /dev/null; cat '
                '>> "$GITHUB_OUTPUT")'), 1)
    assert isi_baru65 != isi_aksi65, \
        "suntikan aksi65 tidak menembak"
    with open(berkas_aksi65, "w", encoding="utf-8") as f:
        f.write(isi_baru65)
    rc, out = jalan(subbungkus, [sys.executable, GERBANG, "--semua"])
    periksa("penguras berawalan variabel lingkungan tetap "
            "menguras: gerbang gagal (Audit 937)", rc != 0 and rc != 124 and "Traceback" not in out)
    shutil.rmtree(subbungkus, ignore_errors=True)

    subopsi = klon(tmp, "subopsi")
    berkas_aksi66 = os.path.join(
        subopsi, ".github", "actions", "resolve-vw", "action.yml")
    with open(berkas_aksi66, encoding="utf-8") as f:
        isi_aksi66 = f.read()
    baris_tulis66 = [b for b in isi_aksi66.splitlines()
                     if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
    isi_baru66 = isi_aksi66.replace(
            baris_tulis66,
            baris_tulis66.replace(
                '>> "$GITHUB_OUTPUT"',
                '> >(sort -k 2 > /dev/null; cat '
                '>> "$GITHUB_OUTPUT")'), 1)
    assert isi_baru66 != isi_aksi66, \
        "suntikan aksi66 tidak menembak"
    with open(berkas_aksi66, "w", encoding="utf-8") as f:
        f.write(isi_baru66)
    rc, out = jalan(subopsi, [sys.executable, GERBANG, "--semua"])
    periksa("nilai opsi sort -k bukan operand berkas: gerbang "
            "gagal (Audit 938)", rc != 0 and rc != 124 and "Traceback" not in out)
    shutil.rmtree(subopsi, ignore_errors=True)

    subkutip = klon(tmp, "subkutip")
    berkas_aksi67 = os.path.join(
        subkutip, ".github", "actions", "resolve-vw", "action.yml")
    with open(berkas_aksi67, encoding="utf-8") as f:
        isi_aksi67 = f.read()
    baris_tulis67 = [b for b in isi_aksi67.splitlines()
                     if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
    isi_baru67 = isi_aksi67.replace(
            baris_tulis67,
            baris_tulis67.replace(
                '>> "$GITHUB_OUTPUT"',
                '> >(cat "a>b" > /dev/null; cat '
                '>> "$GITHUB_OUTPUT")'), 1)
    assert isi_baru67 != isi_aksi67, \
        "suntikan aksi67 tidak menembak"
    with open(berkas_aksi67, "w", encoding="utf-8") as f:
        f.write(isi_baru67)
    rc, out = jalan(subkutip, [sys.executable, GERBANG, "--semua"])
    periksa("operand berkutip memuat > adalah berkas, bukan "
            "redirect: gerbang lolos (Audit 939)", rc == 0)
    shutil.rmtree(subkutip, ignore_errors=True)

    subkunci = klon(tmp, "subkunci")
    berkas_aksi68 = os.path.join(
        subkunci, ".github", "actions", "resolve-vw", "action.yml")
    with open(berkas_aksi68, encoding="utf-8") as f:
        isi_aksi68 = f.read()
    baris_tulis68 = [b for b in isi_aksi68.splitlines()
                     if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
    isi_baru68 = isi_aksi68.replace(
            baris_tulis68,
            baris_tulis68.replace(
                '>> "$GITHUB_OUTPUT"',
                '> >(for i in 1; do cat > /dev/null; done; cat '
                '>> "$GITHUB_OUTPUT")'), 1)
    assert isi_baru68 != isi_aksi68, \
        "suntikan aksi68 tidak menembak"
    with open(berkas_aksi68, "w", encoding="utf-8") as f:
        f.write(isi_baru68)
    rc, out = jalan(subkunci, [sys.executable, GERBANG, "--semua"])
    periksa("penguras di badan lingkar sesudah do tetap "
            "menguras: gerbang gagal (Audit 940)", rc != 0 and rc != 124 and "Traceback" not in out)
    shutil.rmtree(subkunci, ignore_errors=True)

    subputus = klon(tmp, "subputus")
    berkas_aksi69 = os.path.join(
        subputus, ".github", "actions", "resolve-vw", "action.yml")
    with open(berkas_aksi69, encoding="utf-8") as f:
        isi_aksi69 = f.read()
    baris_tulis69 = [b for b in isi_aksi69.splitlines()
                     if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
    isi_baru69 = isi_aksi69.replace(
            baris_tulis69,
            baris_tulis69.replace(
                '>> "$GITHUB_OUTPUT"',
                '> >(while read l; do break; done; cat '
                '>> "$GITHUB_OUTPUT")'), 1)
    assert isi_baru69 != isi_aksi69, \
        "suntikan aksi69 tidak menembak"
    with open(berkas_aksi69, "w", encoding="utf-8") as f:
        f.write(isi_baru69)
    rc, out = jalan(subputus, [sys.executable, GERBANG, "--semua"])
    periksa("break mengakhiri lingkar baca lebih awal: penulis "
            "tetap menulis sisa stdin (Audit 941)", rc == 0)
    shutil.rmtree(subputus, ignore_errors=True)

    subdone = klon(tmp, "subdone")
    berkas_aksi70 = os.path.join(
        subdone, ".github", "actions", "resolve-vw", "action.yml")
    with open(berkas_aksi70, encoding="utf-8") as f:
        isi_aksi70 = f.read()
    baris_tulis70 = [b for b in isi_aksi70.splitlines()
                     if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
    isi_baru70 = isi_aksi70.replace(
            baris_tulis70,
            baris_tulis70.replace(
                '>> "$GITHUB_OUTPUT"',
                '> >(while read l; do echo "$l" > /dev/null; '
                "'done'; cat >> \"$GITHUB_OUTPUT\"; done)"), 1)
    assert isi_baru70 != isi_aksi70, \
        "suntikan aksi70 tidak menembak"
    with open(berkas_aksi70, "w", encoding="utf-8") as f:
        f.write(isi_baru70)
    rc, out = jalan(subdone, [sys.executable, GERBANG, "--semua"])
    periksa("'done' berkutip adalah perintah biasa di badan "
            "lingkar: gerbang lolos (Audit 942)", rc == 0)
    shutil.rmtree(subdone, ignore_errors=True)

    subenv = klon(tmp, "subenv")
    berkas_aksi71 = os.path.join(
        subenv, ".github", "actions", "resolve-vw", "action.yml")
    with open(berkas_aksi71, encoding="utf-8") as f:
        isi_aksi71 = f.read()
    baris_tulis71 = [b for b in isi_aksi71.splitlines()
                     if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
    isi_baru71 = isi_aksi71.replace(
            baris_tulis71,
            baris_tulis71.replace(
                '>> "$GITHUB_OUTPUT"',
                '> >(env -i cat > /dev/null; cat '
                '>> "$GITHUB_OUTPUT")'), 1)
    assert isi_baru71 != isi_aksi71, \
        "suntikan aksi71 tidak menembak"
    with open(berkas_aksi71, "w", encoding="utf-8") as f:
        f.write(isi_baru71)
    rc, out = jalan(subenv, [sys.executable, GERBANG, "--semua"])
    periksa("penguras di balik opsi env tetap menguras: gerbang "
            "gagal (Audit 951)", rc != 0 and rc != 124 and "Traceback" not in out)
    shutil.rmtree(subenv, ignore_errors=True)

    subkutipsebagian = klon(tmp, "subkutipsebagian")
    berkas_aksi72 = os.path.join(
        subkutipsebagian, ".github", "actions", "resolve-vw",
        "action.yml")
    with open(berkas_aksi72, encoding="utf-8") as f:
        isi_aksi72 = f.read()
    baris_tulis72 = [b for b in isi_aksi72.splitlines()
                     if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
    isi_baru72 = isi_aksi72.replace(
            baris_tulis72,
            baris_tulis72.replace(
                '>> "$GITHUB_OUTPUT"',
                '> >(cat a">b" > /dev/null; cat '
                '>> "$GITHUB_OUTPUT")'), 1)
    assert isi_baru72 != isi_aksi72, \
        "suntikan aksi72 tidak menembak"
    with open(berkas_aksi72, "w", encoding="utf-8") as f:
        f.write(isi_baru72)
    rc, out = jalan(subkutipsebagian, [sys.executable, GERBANG,
                                       "--semua"])
    periksa("operand berkutip sebagian memuat > adalah berkas, "
            "bukan redirect: gerbang lolos (Audit 952)", rc == 0)
    shutil.rmtree(subkutipsebagian, ignore_errors=True)

    subpycache = klon(tmp, "subpycache")
    folder_cache = os.path.join(subpycache, "tools", "__pycache__")
    os.makedirs(folder_cache)
    with open(os.path.join(folder_cache,
                           "verifikasi-patch.cpython-311.pyc"),
              "wb") as f:
        f.write(b"\x42\x0d\x0d\x0a" + b"\x00" * 32)
    rc, out = jalan(subpycache, [sys.executable, GERBANG, "--semua"])
    periksa("sisa __pycache__ tak mengubah vonis gerbang: lolos "
            "(Audit 954)", rc == 0)
    shutil.rmtree(subpycache, ignore_errors=True)

    subbungkusnilai = klon(tmp, "subbungkusnilai")
    berkas_aksi73 = os.path.join(
        subbungkusnilai, ".github", "actions", "resolve-vw",
        "action.yml")
    with open(berkas_aksi73, encoding="utf-8") as f:
        isi_aksi73 = f.read()
    baris_tulis73 = [b for b in isi_aksi73.splitlines()
                     if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
    isi_baru73 = isi_aksi73.replace(
            baris_tulis73,
            baris_tulis73.replace(
                '>> "$GITHUB_OUTPUT"',
                '> >(nice -n 5 cat > /dev/null; cat '
                '>> "$GITHUB_OUTPUT")'), 1)
    assert isi_baru73 != isi_aksi73, \
        "suntikan aksi73 tidak menembak"
    with open(berkas_aksi73, "w", encoding="utf-8") as f:
        f.write(isi_baru73)
    rc, out = jalan(subbungkusnilai,
                    [sys.executable, GERBANG, "--semua"])
    periksa("nilai opsi pembungkus bukan perintah: penguras di "
            "balik nice -n tetap menguras, gerbang gagal "
            "(Audit 957)", rc != 0 and rc != 124 and "Traceback" not in out)
    shutil.rmtree(subbungkusnilai, ignore_errors=True)

    subteekutip = klon(tmp, "subteekutip")
    berkas_aksi74 = os.path.join(
        subteekutip, ".github", "actions", "resolve-vw",
        "action.yml")
    with open(berkas_aksi74, encoding="utf-8") as f:
        isi_aksi74 = f.read()
    baris_tulis74 = [b for b in isi_aksi74.splitlines()
                     if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
    isi_baru74 = isi_aksi74.replace(
            baris_tulis74,
            baris_tulis74.replace(
                '>> "$GITHUB_OUTPUT"',
                '> >(tee "keluaran.txt" > /dev/null; cat '
                '>> "$GITHUB_OUTPUT")'), 1)
    assert isi_baru74 != isi_aksi74, \
        "suntikan aksi74 tidak menembak"
    with open(berkas_aksi74, "w", encoding="utf-8") as f:
        f.write(isi_baru74)
    rc, out = jalan(subteekutip,
                    [sys.executable, GERBANG, "--semua"])
    periksa("operand tee berkutip tetap keluaran: tee tetap "
            "menguras, gerbang gagal (Audit 958)", rc != 0 and rc != 124 and "Traceback" not in out)
    shutil.rmtree(subteekutip, ignore_errors=True)

    submerekat = klon(tmp, "submerekat")
    berkas_aksi75 = os.path.join(
        submerekat, ".github", "actions", "resolve-vw",
        "action.yml")
    with open(berkas_aksi75, encoding="utf-8") as f:
        isi_aksi75 = f.read()
    baris_tulis75 = [b for b in isi_aksi75.splitlines()
                     if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
    isi_baru75 = isi_aksi75.replace(
            baris_tulis75,
            baris_tulis75.replace(
                '>> "$GITHUB_OUTPUT"',
                '> >(cat keluaran.txt>log1; cat '
                '>> "$GITHUB_OUTPUT")'), 1)
    assert isi_baru75 != isi_aksi75, \
        "suntikan aksi75 tidak menembak"
    with open(berkas_aksi75, "w", encoding="utf-8") as f:
        f.write(isi_baru75)
    rc, out = jalan(submerekat,
                    [sys.executable, GERBANG, "--semua"])
    periksa("operand merekat ke redirect adalah berkas yang "
            "dibaca: penulis tetap menerima stdin, gerbang "
            "lolos (Audit 959)", rc == 0)
    shutil.rmtree(submerekat, ignore_errors=True)

    subenvs = klon(tmp, "subenvs")
    berkas_aksi76 = os.path.join(
        subenvs, ".github", "actions", "resolve-vw",
        "action.yml")
    with open(berkas_aksi76, encoding="utf-8") as f:
        isi_aksi76 = f.read()
    baris_tulis76 = [b for b in isi_aksi76.splitlines()
                     if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
    isi_baru76 = isi_aksi76.replace(
            baris_tulis76,
            baris_tulis76.replace(
                '>> "$GITHUB_OUTPUT"',
                '> >(env -S cat > /dev/null; cat '
                '>> "$GITHUB_OUTPUT")'), 1)
    assert isi_baru76 != isi_aksi76, \
        "suntikan aksi76 tidak menembak"
    with open(berkas_aksi76, "w", encoding="utf-8") as f:
        f.write(isi_baru76)
    rc, out = jalan(subenvs, [sys.executable, GERBANG, "--semua"])
    periksa("nilai env -S adalah perintah yang dijalankan: "
            "tetap menguras, gerbang gagal (Audit 960)", rc != 0 and rc != 124 and "Traceback" not in out)
    shutil.rmtree(subenvs, ignore_errors=True)

    subcommandv = klon(tmp, "subcommandv")
    berkas_aksi77 = os.path.join(
        subcommandv, ".github", "actions", "resolve-vw",
        "action.yml")
    with open(berkas_aksi77, encoding="utf-8") as f:
        isi_aksi77 = f.read()
    baris_tulis77 = [b for b in isi_aksi77.splitlines()
                     if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
    isi_baru77 = isi_aksi77.replace(
            baris_tulis77,
            baris_tulis77.replace(
                '>> "$GITHUB_OUTPUT"',
                '> >(command -v cat > /dev/null; cat '
                '>> "$GITHUB_OUTPUT")'), 1)
    assert isi_baru77 != isi_aksi77, \
        "suntikan aksi77 tidak menembak"
    with open(berkas_aksi77, "w", encoding="utf-8") as f:
        f.write(isi_baru77)
    rc, out = jalan(subcommandv,
                    [sys.executable, GERBANG, "--semua"])
    periksa("command -v hanya bertanya, tak menjalankan: "
            "penulis tetap menerima stdin, gerbang lolos "
            "(Audit 961)", rc == 0)
    shutil.rmtree(subcommandv, ignore_errors=True)

    subxargsa = klon(tmp, "subxargsa")
    berkas_aksi78 = os.path.join(
        subxargsa, ".github", "actions", "resolve-vw",
        "action.yml")
    with open(berkas_aksi78, encoding="utf-8") as f:
        isi_aksi78 = f.read()
    baris_tulis78 = [b for b in isi_aksi78.splitlines()
                     if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
    isi_baru78 = isi_aksi78.replace(
            baris_tulis78,
            baris_tulis78.replace(
                '>> "$GITHUB_OUTPUT"',
                '> >(xargs -a daftar cat > /dev/null; cat '
                '>> "$GITHUB_OUTPUT")'), 1)
    assert isi_baru78 != isi_aksi78, \
        "suntikan aksi78 tidak menembak"
    with open(berkas_aksi78, "w", encoding="utf-8") as f:
        f.write(isi_baru78)
    rc, out = jalan(subxargsa, [sys.executable, GERBANG, "--semua"])
    periksa("item xargs -a dari berkas bernama: anak tetap "
            "mewarisi stdin bersama dan dinilai normal, "
            "cat menguras: gerbang gagal "
            "(Audit 2670, membalik 962)", rc != 0 and rc != 124 and "Traceback" not in out)
    shutil.rmtree(subxargsa, ignore_errors=True)

    subenvsredir = klon(tmp, "subenvsredir")
    berkas_aksi79 = os.path.join(
        subenvsredir, ".github", "actions", "resolve-vw",
        "action.yml")
    with open(berkas_aksi79, encoding="utf-8") as f:
        isi_aksi79 = f.read()
    baris_tulis79 = [b for b in isi_aksi79.splitlines()
                     if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
    isi_baru79 = isi_aksi79.replace(
            baris_tulis79,
            baris_tulis79.replace(
                '>> "$GITHUB_OUTPUT"',
                "> >(env -S 'cat > /dev/null'; cat "
                ">> \"$GITHUB_OUTPUT\")"), 1)
    assert isi_baru79 != isi_aksi79, \
        "suntikan aksi79 tidak menembak"
    with open(berkas_aksi79, "w", encoding="utf-8") as f:
        f.write(isi_baru79)
    rc, out = jalan(subenvsredir,
                    [sys.executable, GERBANG, "--semua"])
    periksa("redirect di dalam string env -S adalah operand "
            "harfiah: penulis tetap menerima stdin, gerbang "
            "lolos (Audit 963)", rc == 0)
    shutil.rmtree(subenvsredir, ignore_errors=True)

    subenvsarang = klon(tmp, "subenvsarang")
    berkas_aksi80 = os.path.join(
        subenvsarang, ".github", "actions", "resolve-vw",
        "action.yml")
    with open(berkas_aksi80, encoding="utf-8") as f:
        isi_aksi80 = f.read()
    baris_tulis80 = [b for b in isi_aksi80.splitlines()
                     if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
    isi_baru80 = isi_aksi80.replace(
            baris_tulis80,
            baris_tulis80.replace(
                '>> "$GITHUB_OUTPUT"',
                "> >(env -S 'env -S cat > x'; cat "
                ">> \"$GITHUB_OUTPUT\")"), 1)
    assert isi_baru80 != isi_aksi80, \
        "suntikan aksi80 tidak menembak"
    with open(berkas_aksi80, "w", encoding="utf-8") as f:
        f.write(isi_baru80)
    rc, out = jalan(subenvsarang,
                    [sys.executable, GERBANG, "--semua"])
    periksa("batas argv diwariskan di env -S bersarang: "
            "redirect string terdalam tetap operand, gerbang "
            "lolos (Audit 964)", rc == 0)
    shutil.rmtree(subenvsarang, ignore_errors=True)

    subredirdepan = klon(tmp, "subredirdepan")
    berkas_aksi81 = os.path.join(
        subredirdepan, ".github", "actions", "resolve-vw",
        "action.yml")
    with open(berkas_aksi81, encoding="utf-8") as f:
        isi_aksi81 = f.read()
    baris_tulis81 = [b for b in isi_aksi81.splitlines()
                     if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
    isi_baru81 = isi_aksi81.replace(
            baris_tulis81,
            baris_tulis81.replace(
                '>> "$GITHUB_OUTPUT"',
                "> >(> /dev/null cat; "
                "cat >> \"$GITHUB_OUTPUT\")"), 1)
    assert isi_baru81 != isi_aksi81, \
        "suntikan aksi81 tidak menembak"
    with open(berkas_aksi81, "w", encoding="utf-8") as f:
        f.write(isi_baru81)
    rc, out = jalan(subredirdepan,
                    [sys.executable, GERBANG, "--semua"])
    periksa("redirect di depan perintah tidak lagi menyembunyikan "
            "pembaca: penulis menguras stdin, gerbang gagal "
            "(Audit 965)", rc != 0 and rc != 124 and "Traceback" not in out)
    shutil.rmtree(subredirdepan, ignore_errors=True)

    subtawar = klon(tmp, "subtawar")
    berkas_aksi82 = os.path.join(
        subtawar, ".github", "actions", "resolve-vw",
        "action.yml")
    with open(berkas_aksi82, encoding="utf-8") as f:
        isi_aksi82 = f.read()
    baris_tulis82 = [b for b in isi_aksi82.splitlines()
                     if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
    isi_baru82 = isi_aksi82.replace(
            baris_tulis82,
            baris_tulis82.replace(
                '>> "$GITHUB_OUTPUT"',
                "> >(nice A=1 cat; "
                "cat >> \"$GITHUB_OUTPUT\")"), 1)
    assert isi_baru82 != isi_aksi82, \
        "suntikan aksi82 tidak menembak"
    with open(berkas_aksi82, "w", encoding="utf-8") as f:
        f.write(isi_baru82)
    rc, out = jalan(subtawar,
                    [sys.executable, GERBANG, "--semua"])
    periksa("penetapan sesudah pembungkus non-env adalah nama "
            "program: gerbang lolos (Audit 966)", rc == 0)
    shutil.rmtree(subtawar, ignore_errors=True)

    subredfd = klon(tmp, "subredfd")
    berkas_aksi83 = os.path.join(
        subredfd, ".github", "actions", "resolve-vw",
        "action.yml")
    with open(berkas_aksi83, encoding="utf-8") as f:
        isi_aksi83 = f.read()
    baris_tulis83 = [b for b in isi_aksi83.splitlines()
                     if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
    isi_baru83 = isi_aksi83.replace(
            baris_tulis83,
            baris_tulis83.replace(
                '>> "$GITHUB_OUTPUT"',
                "> >(3> /dev/null cat; "
                "cat >> \"$GITHUB_OUTPUT\")"), 1)
    assert isi_baru83 != isi_aksi83, \
        "suntikan aksi83 tidak menembak"
    with open(berkas_aksi83, "w", encoding="utf-8") as f:
        f.write(isi_baru83)
    rc, out = jalan(subredfd,
                    [sys.executable, GERBANG, "--semua"])
    periksa("redirect ber-fd di depan perintah tidak lagi "
            "menyembunyikan pembaca: gerbang gagal (Audit 967)",
            rc != 0 and rc != 124 and "Traceback" not in out)
    shutil.rmtree(subredfd, ignore_errors=True)

    subrednilai = klon(tmp, "subrednilai")
    berkas_aksi84 = os.path.join(
        subrednilai, ".github", "actions", "resolve-vw",
        "action.yml")
    with open(berkas_aksi84, encoding="utf-8") as f:
        isi_aksi84 = f.read()
    baris_tulis84 = [b for b in isi_aksi84.splitlines()
                     if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
    isi_baru84 = isi_aksi84.replace(
            baris_tulis84,
            baris_tulis84.replace(
                '>> "$GITHUB_OUTPUT"',
                "> >(timeout -s > /dev/null KILL 5 cat; "
                "cat >> \"$GITHUB_OUTPUT\")"), 1)
    assert isi_baru84 != isi_aksi84, \
        "suntikan aksi84 tidak menembak"
    with open(berkas_aksi84, "w", encoding="utf-8") as f:
        f.write(isi_baru84)
    rc, out = jalan(subrednilai,
                    [sys.executable, GERBANG, "--semua"])
    periksa("redirect yang menyela nilai opsi tidak lagi "
            "menelan perintah: gerbang gagal (Audit 968)",
            rc != 0 and rc != 124 and "Traceback" not in out)
    shutil.rmtree(subrednilai, ignore_errors=True)

    subenvtawar = klon(tmp, "subenvtawar")
    berkas_aksi85 = os.path.join(
        subenvtawar, ".github", "actions", "resolve-vw",
        "action.yml")
    with open(berkas_aksi85, encoding="utf-8") as f:
        isi_aksi85 = f.read()
    baris_tulis85 = [b for b in isi_aksi85.splitlines()
                     if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
    isi_baru85 = isi_aksi85.replace(
            baris_tulis85,
            baris_tulis85.replace(
                '>> "$GITHUB_OUTPUT"',
                "> >(nice env A=1 cat; "
                "cat >> \"$GITHUB_OUTPUT\")"), 1)
    assert isi_baru85 != isi_aksi85, \
        "suntikan aksi85 tidak menembak"
    with open(berkas_aksi85, "w", encoding="utf-8") as f:
        f.write(isi_baru85)
    rc, out = jalan(subenvtawar,
                    [sys.executable, GERBANG, "--semua"])
    periksa("env sesudah pembungkus memulihkan penetapan: "
            "penulis menguras stdin, gerbang gagal (Audit 969)",
            rc != 0 and rc != 124 and "Traceback" not in out)
    shutil.rmtree(subenvtawar, ignore_errors=True)

    subbungkusbaru = klon(tmp, "subbungkusbaru")
    berkas_aksi86 = os.path.join(
        subbungkusbaru, ".github", "actions", "resolve-vw",
        "action.yml")
    with open(berkas_aksi86, encoding="utf-8") as f:
        isi_aksi86 = f.read()
    baris_tulis86 = [b for b in isi_aksi86.splitlines()
                     if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
    isi_baru86 = isi_aksi86.replace(
            baris_tulis86,
            baris_tulis86.replace(
                '>> "$GITHUB_OUTPUT"',
                "> >(setsid cat; "
                "cat >> \"$GITHUB_OUTPUT\")"), 1)
    assert isi_baru86 != isi_aksi86, \
        "suntikan aksi86 tidak menembak"
    with open(berkas_aksi86, "w", encoding="utf-8") as f:
        f.write(isi_baru86)
    rc, out = jalan(subbungkusbaru,
                    [sys.executable, GERBANG, "--semua"])
    periksa("pembungkus setsid kini dikenal resolver: penulis "
            "menguras stdin, gerbang gagal (Audit 970)",
            rc != 0 and rc != 124 and "Traceback" not in out)
    shutil.rmtree(subbungkusbaru, ignore_errors=True)

    subxargssendiri = klon(tmp, "subxargssendiri")
    berkas_aksi87 = os.path.join(
        subxargssendiri, ".github", "actions", "resolve-vw",
        "action.yml")
    with open(berkas_aksi87, encoding="utf-8") as f:
        isi_aksi87 = f.read()
    baris_tulis87 = [b for b in isi_aksi87.splitlines()
                     if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
    isi_baru87 = isi_aksi87.replace(
            baris_tulis87,
            baris_tulis87.replace(
                '>> "$GITHUB_OUTPUT"',
                "> >(xargs echo; "
                "cat >> \"$GITHUB_OUTPUT\")"), 1)
    assert isi_baru87 != isi_aksi87, \
        "suntikan aksi87 tidak menembak"
    with open(berkas_aksi87, "w", encoding="utf-8") as f:
        f.write(isi_baru87)
    rc, out = jalan(subxargssendiri,
                    [sys.executable, GERBANG, "--semua"])
    periksa("xargs tanpa -a menguras stdin sendiri walau "
            "anaknya bukan pembaca: gerbang gagal (Audit 971)",
            rc != 0 and rc != 124 and "Traceback" not in out)
    shutil.rmtree(subxargssendiri, ignore_errors=True)

    subcommandprog = klon(tmp, "subcommandprog")
    berkas_aksi88 = os.path.join(
        subcommandprog, ".github", "actions", "resolve-vw",
        "action.yml")
    with open(berkas_aksi88, encoding="utf-8") as f:
        isi_aksi88 = f.read()
    baris_tulis88 = [b for b in isi_aksi88.splitlines()
                     if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
    isi_baru88 = isi_aksi88.replace(
            baris_tulis88,
            baris_tulis88.replace(
                '>> "$GITHUB_OUTPUT"',
                "> >(nice command cat; "
                "cat >> \"$GITHUB_OUTPUT\")"), 1)
    assert isi_baru88 != isi_aksi88, \
        "suntikan aksi88 tidak menembak"
    with open(berkas_aksi88, "w", encoding="utf-8") as f:
        f.write(isi_baru88)
    rc, out = jalan(subcommandprog,
                    [sys.executable, GERBANG, "--semua"])
    periksa("'command' di balik pembungkus adalah program tak "
            "ada: penulis sah lolos, gerbang lolos (Audit 972)",
            rc == 0)
    shutil.rmtree(subcommandprog, ignore_errors=True)

    subtutupout = klon(tmp, "subtutupout")
    berkas_aksi89 = os.path.join(
        subtutupout, ".github", "actions", "resolve-vw",
        "action.yml")
    with open(berkas_aksi89, encoding="utf-8") as f:
        isi_aksi89 = f.read()
    baris_tulis89 = [b for b in isi_aksi89.splitlines()
                     if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
    isi_baru89 = isi_aksi89.replace(
            baris_tulis89,
            baris_tulis89.replace(
                '>> "$GITHUB_OUTPUT"',
                "> >(&>- wc -l; "
                "cat >> \"$GITHUB_OUTPUT\")"), 1)
    assert isi_baru89 != isi_aksi89, \
        "suntikan aksi89 tidak menembak"
    with open(berkas_aksi89, "w", encoding="utf-8") as f:
        f.write(isi_baru89)
    rc, out = jalan(subtutupout,
                    [sys.executable, GERBANG, "--semua"])
    periksa("stdout ditutup di awalan tidak menyembunyikan "
            "pembaca: gerbang gagal (Audit 985)", rc != 0 and rc != 124 and "Traceback" not in out)
    shutil.rmtree(subtutupout, ignore_errors=True)

    subxargsredir = klon(tmp, "subxargsredir")
    berkas_aksi90 = os.path.join(
        subxargsredir, ".github", "actions", "resolve-vw",
        "action.yml")
    with open(berkas_aksi90, encoding="utf-8") as f:
        isi_aksi90 = f.read()
    baris_tulis90 = [b for b in isi_aksi90.splitlines()
                     if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
    isi_baru90 = isi_aksi90.replace(
            baris_tulis90,
            baris_tulis90.replace(
                '>> "$GITHUB_OUTPUT"',
                "> >(xargs cat < /dev/null; "
                "cat >> \"$GITHUB_OUTPUT\")"), 1)
    assert isi_baru90 != isi_aksi90, \
        "suntikan aksi90 tidak menembak"
    with open(berkas_aksi90, "w", encoding="utf-8") as f:
        f.write(isi_baru90)
    rc, out = jalan(subxargsredir,
                    [sys.executable, GERBANG, "--semua"])
    periksa("redirect masukan sesudah anak xargs milik xargs "
            "sendiri: gerbang lolos (Audit 986)", rc == 0)
    shutil.rmtree(subxargsredir, ignore_errors=True)

    subcommandstr = klon(tmp, "subcommandstr")
    berkas_aksi91 = os.path.join(
        subcommandstr, ".github", "actions", "resolve-vw",
        "action.yml")
    with open(berkas_aksi91, encoding="utf-8") as f:
        isi_aksi91 = f.read()
    baris_tulis91 = [b for b in isi_aksi91.splitlines()
                     if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
    isi_baru91 = isi_aksi91.replace(
            baris_tulis91,
            baris_tulis91.replace(
                '>> "$GITHUB_OUTPUT"',
                "> >(env -S 'command cat'; "
                "cat >> \"$GITHUB_OUTPUT\")"), 1)
    assert isi_baru91 != isi_aksi91, \
        "suntikan aksi91 tidak menembak"
    with open(berkas_aksi91, "w", encoding="utf-8") as f:
        f.write(isi_baru91)
    rc, out = jalan(subcommandstr,
                    [sys.executable, GERBANG, "--semua"])
    periksa("'command' di dalam string env -S adalah program "
            "tak ada: gerbang lolos (Audit 987)", rc == 0)
    shutil.rmtree(subcommandstr, ignore_errors=True)

    subcommandganda = klon(tmp, "subcommandganda")
    berkas_aksi92 = os.path.join(
        subcommandganda, ".github", "actions", "resolve-vw",
        "action.yml")
    with open(berkas_aksi92, encoding="utf-8") as f:
        isi_aksi92 = f.read()
    baris_tulis92 = [b for b in isi_aksi92.splitlines()
                     if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
    isi_baru92 = isi_aksi92.replace(
            baris_tulis92,
            baris_tulis92.replace(
                '>> "$GITHUB_OUTPUT"',
                "> >(command command wc -l; "
                "cat >> \"$GITHUB_OUTPUT\")"), 1)
    assert isi_baru92 != isi_aksi92, \
        "suntikan aksi92 tidak menembak"
    with open(berkas_aksi92, "w", encoding="utf-8") as f:
        f.write(isi_baru92)
    rc, out = jalan(subcommandganda,
                    [sys.executable, GERBANG, "--semua"])
    periksa("'command' sesudah 'command' tetap transparan: "
            "gerbang gagal (Audit 988)", rc != 0 and rc != 124 and "Traceback" not in out)
    shutil.rmtree(subcommandganda, ignore_errors=True)

    subpidpalsu = klon(tmp, "subpidpalsu")
    berkas_aksi93 = os.path.join(
        subpidpalsu, ".github", "actions", "resolve-vw",
        "action.yml")
    with open(berkas_aksi93, encoding="utf-8") as f:
        isi_aksi93 = f.read()
    baris_tulis93 = [b for b in isi_aksi93.splitlines()
                     if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
    isi_baru93 = isi_aksi93.replace(
            baris_tulis93,
            baris_tulis93.replace(
                '>> "$GITHUB_OUTPUT"',
                "> >(ionice -p 1234 wc -l; "
                "cat >> \"$GITHUB_OUTPUT\")"), 1)
    assert isi_baru93 != isi_aksi93, \
        "suntikan aksi93 tidak menembak"
    with open(berkas_aksi93, "w", encoding="utf-8") as f:
        f.write(isi_baru93)
    rc, out = jalan(subpidpalsu,
                    [sys.executable, GERBANG, "--semua"])
    periksa("bentuk pid ionice adalah mode pid, bukan perintah: "
            "gerbang lolos (Audit 989)", rc == 0)
    shutil.rmtree(subpidpalsu, ignore_errors=True)

    subxargsfd = klon(tmp, "subxargsfd")
    berkas_aksi94 = os.path.join(
        subxargsfd, ".github", "actions", "resolve-vw",
        "action.yml")
    with open(berkas_aksi94, encoding="utf-8") as f:
        isi_aksi94 = f.read()
    baris_tulis94 = [b for b in isi_aksi94.splitlines()
                     if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
    isi_baru94 = isi_aksi94.replace(
            baris_tulis94,
            baris_tulis94.replace(
                '>> "$GITHUB_OUTPUT"',
                "> >(xargs cat 3<berkasKeluaran; "
                "cat >> \"$GITHUB_OUTPUT\")"), 1)
    assert isi_baru94 != isi_aksi94, \
        "suntikan aksi94 tidak menembak"
    with open(berkas_aksi94, "w", encoding="utf-8") as f:
        f.write(isi_baru94)
    rc, out = jalan(subxargsfd,
                    [sys.executable, GERBANG, "--semua"])
    periksa("redirect fd bukan-0 sesudah anak xargs tak "
            "mengganti stdin xargs, jadi xargs sungguh menguras "
            "dan penulis kebagian EOF: gerbang gagal (Audit 995)",
            rc != 0 and rc != 124 and "Traceback" not in out)
    shutil.rmtree(subxargsfd, ignore_errors=True)

    subxargslekat = klon(tmp, "subxargslekat")
    berkas_aksi95 = os.path.join(
        subxargslekat, ".github", "actions", "resolve-vw",
        "action.yml")
    with open(berkas_aksi95, encoding="utf-8") as f:
        isi_aksi95 = f.read()
    baris_tulis95 = [b for b in isi_aksi95.splitlines()
                     if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
    isi_baru95 = isi_aksi95.replace(
            baris_tulis95,
            baris_tulis95.replace(
                '>> "$GITHUB_OUTPUT"',
                "> >(xargs cat a<berkasKeluaran; "
                "cat >> \"$GITHUB_OUTPUT\")"), 1)
    assert isi_baru95 != isi_aksi95, \
        "suntikan aksi95 tidak menembak"
    with open(berkas_aksi95, "w", encoding="utf-8") as f:
        f.write(isi_baru95)
    rc, out = jalan(subxargslekat,
                    [sys.executable, GERBANG, "--semua"])
    periksa("redirect masukan merekat di tengah kata sesudah "
            "anak xargs milik xargs sendiri: gerbang lolos "
            "(Audit 996)", rc == 0)
    shutil.rmtree(subxargslekat, ignore_errors=True)

    suboperandfd = klon(tmp, "suboperandfd")
    berkas_aksi96 = os.path.join(
        suboperandfd, ".github", "actions", "resolve-vw",
        "action.yml")
    with open(berkas_aksi96, encoding="utf-8") as f:
        isi_aksi96 = f.read()
    baris_tulis96 = [b for b in isi_aksi96.splitlines()
                     if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
    isi_baru96 = isi_aksi96.replace(
            baris_tulis96,
            baris_tulis96.replace(
                '>> "$GITHUB_OUTPUT"',
                "> >(cat 3<berkasKeluaran; "
                "cat >> \"$GITHUB_OUTPUT\")"), 1)
    assert isi_baru96 != isi_aksi96, \
        "suntikan aksi96 tidak menembak"
    with open(berkas_aksi96, "w", encoding="utf-8") as f:
        f.write(isi_baru96)
    rc, out = jalan(suboperandfd,
                    [sys.executable, GERBANG, "--semua"])
    periksa("redirect fd bukan-0 di posisi operand tak "
            "mengganti stdin, jadi cat sungguh menguras dan "
            "penulis kebagian EOF: gerbang gagal (Audit 997)",
            rc != 0 and rc != 124 and "Traceback" not in out)
    shutil.rmtree(suboperandfd, ignore_errors=True)

    subtasksetgabung = klon(tmp, "subtasksetgabung")
    berkas_aksi97 = os.path.join(
        subtasksetgabung, ".github", "actions", "resolve-vw",
        "action.yml")
    with open(berkas_aksi97, encoding="utf-8") as f:
        isi_aksi97 = f.read()
    baris_tulis97 = [b for b in isi_aksi97.splitlines()
                     if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
    isi_baru97 = isi_aksi97.replace(
            baris_tulis97,
            baris_tulis97.replace(
                '>> "$GITHUB_OUTPUT"',
                "> >(taskset -pc 0 wc -l; "
                "cat >> \"$GITHUB_OUTPUT\")"), 1)
    assert isi_baru97 != isi_aksi97, \
        "suntikan aksi97 tidak menembak"
    with open(berkas_aksi97, "w", encoding="utf-8") as f:
        f.write(isi_baru97)
    rc, out = jalan(subtasksetgabung,
                    [sys.executable, GERBANG, "--semua"])
    periksa("bendera gabungan taskset -pc adalah mode pid, "
            "bukan perintah: gerbang lolos (Audit 998)",
            rc == 0)
    shutil.rmtree(subtasksetgabung, ignore_errors=True)

    subfdnol = klon(tmp, "subfdnol")
    berkas_aksi98 = os.path.join(
        subfdnol, ".github", "actions", "resolve-vw", "action.yml")
    with open(berkas_aksi98, encoding="utf-8") as f:
        isi_aksi98 = f.read()
    baris_tulis98 = [b for b in isi_aksi98.splitlines()
                     if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
    isi_baru98 = isi_aksi98.replace(
            baris_tulis98,
            baris_tulis98.replace(
                '>> "$GITHUB_OUTPUT"',
                "> >(00<berkasKeluaran cat; "
                "cat >> \"$GITHUB_OUTPUT\")"), 1)
    assert isi_baru98 != isi_aksi98, \
        "suntikan aksi98 tidak menembak"
    with open(berkas_aksi98, "w", encoding="utf-8") as f:
        f.write(isi_baru98)
    rc, out = jalan(subfdnol,
                    [sys.executable, GERBANG, "--semua"])
    periksa("redirect fd 00 adalah fd 0: segmen pertama bukan "
            "penguras — gerbang lolos (Audit 1004)", rc == 0)
    shutil.rmtree(subfdnol, ignore_errors=True)

    subopsiredirect = klon(tmp, "subopsiredirect")
    berkas_aksi99 = os.path.join(
        subopsiredirect, ".github", "actions", "resolve-vw",
        "action.yml")
    with open(berkas_aksi99, encoding="utf-8") as f:
        isi_aksi99 = f.read()
    baris_tulis99 = [b for b in isi_aksi99.splitlines()
                     if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
    isi_baru99 = isi_aksi99.replace(
            baris_tulis99,
            baris_tulis99.replace(
                '>> "$GITHUB_OUTPUT"',
                "> >(head -n 3<berkasKeluaran; "
                "cat >> \"$GITHUB_OUTPUT\")"), 1)
    assert isi_baru99 != isi_aksi99, \
        "suntikan aksi99 tidak menembak"
    with open(berkas_aksi99, "w", encoding="utf-8") as f:
        f.write(isi_baru99)
    rc, out = jalan(subopsiredirect,
                    [sys.executable, GERBANG, "--semua"])
    periksa("token redirect sesudah opsi bernilai bukan nilai "
            "opsi: segmen pertama bukan penguras — gerbang "
            "lolos (Audit 1005)", rc == 0)
    shutil.rmtree(subopsiredirect, ignore_errors=True)

    subionicebundel = klon(tmp, "subionicebundel")
    berkas_aksi100 = os.path.join(
        subionicebundel, ".github", "actions", "resolve-vw",
        "action.yml")
    with open(berkas_aksi100, encoding="utf-8") as f:
        isi_aksi100 = f.read()
    baris_tulis100 = [b for b in isi_aksi100.splitlines()
                      if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
    isi_baru100 = isi_aksi100.replace(
            baris_tulis100,
            baris_tulis100.replace(
                '>> "$GITHUB_OUTPUT"',
                "> >(ionice -tc 2 cat; "
                "cat >> \"$GITHUB_OUTPUT\")"), 1)
    assert isi_baru100 != isi_aksi100, \
        "suntikan aksi100 tidak menembak"
    with open(berkas_aksi100, "w", encoding="utf-8") as f:
        f.write(isi_baru100)
    rc, out = jalan(subionicebundel,
                    [sys.executable, GERBANG, "--semua"])
    periksa("bundel ionice -tc bernilai-terpisah adalah "
            "penguras: gerbang gagal (Audit 1006)", rc != 0 and rc != 124 and "Traceback" not in out)
    shutil.rmtree(subionicebundel, ignore_errors=True)

    subdupambig = klon(tmp, "subdupambig")
    berkas_aksi101 = os.path.join(
        subdupambig, ".github", "actions", "resolve-vw",
        "action.yml")
    with open(berkas_aksi101, encoding="utf-8") as f:
        isi_aksi101 = f.read()
    baris_tulis101 = [b for b in isi_aksi101.splitlines()
                      if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
    isi_baru101 = isi_aksi101.replace(
            baris_tulis101,
            baris_tulis101.replace(
                '>> "$GITHUB_OUTPUT"',
                "> >(xargs cat <&0x; "
                "cat >> \"$GITHUB_OUTPUT\")"), 1)
    assert isi_baru101 != isi_aksi101, \
        "suntikan aksi101 tidak menembak"
    with open(berkas_aksi101, "w", encoding="utf-8") as f:
        f.write(isi_baru101)
    rc, out = jalan(subdupambig,
                    [sys.executable, GERBANG, "--semua"])
    periksa("target duplikasi bersufiks huruf ditolak bash: "
            "segmen pertama bukan penguras — gerbang lolos "
            "(Audit 1007)", rc == 0)
    shutil.rmtree(subdupambig, ignore_errors=True)

    subpindahfd = klon(tmp, "subpindahfd")
    berkas_aksi102 = os.path.join(
        subpindahfd, ".github", "actions", "resolve-vw",
        "action.yml")
    with open(berkas_aksi102, encoding="utf-8") as f:
        isi_aksi102 = f.read()
    baris_tulis102 = [b for b in isi_aksi102.splitlines()
                      if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
    isi_baru102 = isi_aksi102.replace(
            baris_tulis102,
            baris_tulis102.replace(
                '>> "$GITHUB_OUTPUT"',
                "> >(cat <&0-; "
                "cat >> \"$GITHUB_OUTPUT\")"), 1)
    assert isi_baru102 != isi_aksi102, \
        "suntikan aksi102 tidak menembak"
    with open(berkas_aksi102, "w", encoding="utf-8") as f:
        f.write(isi_baru102)
    rc, out = jalan(subpindahfd,
                    [sys.executable, GERBANG, "--semua"])
    periksa("duplikasi bersufiks pindah-fd <&0- adalah "
            "penguras: gerbang gagal (Audit 1011)", rc != 0 and rc != 124 and "Traceback" not in out)
    shutil.rmtree(subpindahfd, ignore_errors=True)

    subdupnol = klon(tmp, "subdupnol")
    berkas_aksi103 = os.path.join(
        subdupnol, ".github", "actions", "resolve-vw",
        "action.yml")
    with open(berkas_aksi103, encoding="utf-8") as f:
        isi_aksi103 = f.read()
    baris_tulis103 = [b for b in isi_aksi103.splitlines()
                      if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
    isi_baru103 = isi_aksi103.replace(
            baris_tulis103,
            baris_tulis103.replace(
                '>> "$GITHUB_OUTPUT"',
                "> >(cat 0<&0; "
                "cat >> \"$GITHUB_OUTPUT\")"), 1)
    assert isi_baru103 != isi_aksi103, \
        "suntikan aksi103 tidak menembak"
    with open(berkas_aksi103, "w", encoding="utf-8") as f:
        f.write(isi_baru103)
    rc, out = jalan(subdupnol,
                    [sys.executable, GERBANG, "--semua"])
    periksa("duplikasi berawalan fd 0<&0 adalah penguras: "
            "gerbang gagal (Audit 1014)", rc != 0 and rc != 124 and "Traceback" not in out)
    shutil.rmtree(subdupnol, ignore_errors=True)

    subflock = klon(tmp, "subflock")
    berkas_aksi104 = os.path.join(
        subflock, ".github", "actions", "resolve-vw",
        "action.yml")
    with open(berkas_aksi104, encoding="utf-8") as f:
        isi_aksi104 = f.read()
    baris_tulis104 = [b for b in isi_aksi104.splitlines()
                      if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
    isi_baru104 = isi_aksi104.replace(
            baris_tulis104,
            baris_tulis104.replace(
                '>> "$GITHUB_OUTPUT"',
                "> >(flock kunci.lock cat; "
                "cat >> \"$GITHUB_OUTPUT\")"), 1)
    assert isi_baru104 != isi_aksi104, \
        "suntikan aksi104 tidak menembak"
    with open(berkas_aksi104, "w", encoding="utf-8") as f:
        f.write(isi_baru104)
    rc, out = jalan(subflock,
                    [sys.executable, GERBANG, "--semua"])
    periksa("pembaca terbungkus flock adalah penguras: "
            "gerbang gagal (Audit 1015)", rc != 0 and rc != 124 and "Traceback" not in out)
    shutil.rmtree(subflock, ignore_errors=True)

    subpindahbatal = klon(tmp, "subpindahbatal")
    berkas_aksi105 = os.path.join(
        subpindahbatal, ".github", "actions", "resolve-vw",
        "action.yml")
    with open(berkas_aksi105, encoding="utf-8") as f:
        isi_aksi105 = f.read()
    baris_tulis105 = [b for b in isi_aksi105.splitlines()
                      if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
    isi_baru105 = isi_aksi105.replace(
            baris_tulis105,
            baris_tulis105.replace(
                '>> "$GITHUB_OUTPUT"',
                "> >(cat 1<&0-; "
                "cat >> \"$GITHUB_OUTPUT\")"), 1)
    assert isi_baru105 != isi_aksi105, \
        "suntikan aksi105 tidak menembak"
    with open(berkas_aksi105, "w", encoding="utf-8") as f:
        f.write(isi_baru105)
    rc, out = jalan(subpindahbatal,
                    [sys.executable, GERBANG, "--semua"])
    periksa("pindah-fd bersumber 0 menutup stdin: segmen "
            "pertama bukan penguras — gerbang lolos "
            "(Audit 1016)", rc == 0)
    shutil.rmtree(subpindahbatal, ignore_errors=True)

    subkata = klon(tmp, "subkata")
    berkas_aksi106 = os.path.join(
        subkata, ".github", "actions", "resolve-vw",
        "action.yml")
    with open(berkas_aksi106, encoding="utf-8") as f:
        isi_aksi106 = f.read()
    baris_tulis106 = [b for b in isi_aksi106.splitlines()
                      if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
    isi_baru106 = isi_aksi106.replace(
            baris_tulis106,
            baris_tulis106.replace(
                '>> "$GITHUB_OUTPUT"',
                "> >(exec cat; "
                "cat >> \"$GITHUB_OUTPUT\")"), 1)
    assert isi_baru106 != isi_aksi106, \
        "suntikan aksi106 tidak menembak"
    with open(berkas_aksi106, "w", encoding="utf-8") as f:
        f.write(isi_baru106)
    rc, out = jalan(subkata,
                    [sys.executable, GERBANG, "--semua"])
    periksa("awalan kata kunci exec transparan: pembaca di "
            "baliknya adalah penguras — gerbang gagal "
            "(Audit 1020)", rc != 0 and rc != 124 and "Traceback" not in out)
    shutil.rmtree(subkata, ignore_errors=True)

    subgrup = klon(tmp, "subgrup")
    berkas_aksi107 = os.path.join(
        subgrup, ".github", "actions", "resolve-vw",
        "action.yml")
    with open(berkas_aksi107, encoding="utf-8") as f:
        isi_aksi107 = f.read()
    baris_tulis107 = [b for b in isi_aksi107.splitlines()
                      if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
    isi_baru107 = isi_aksi107.replace(
            baris_tulis107,
            baris_tulis107.replace(
                '>> "$GITHUB_OUTPUT"',
                "> >((cat); "
                "cat >> \"$GITHUB_OUTPUT\")"), 1)
    assert isi_baru107 != isi_aksi107, \
        "suntikan aksi107 tidak menembak"
    with open(berkas_aksi107, "w", encoding="utf-8") as f:
        f.write(isi_baru107)
    rc, out = jalan(subgrup,
                    [sys.executable, GERBANG, "--semua"])
    periksa("grup subshell terurai: (cat) adalah penguras — "
            "gerbang gagal (Audit 1021)", rc != 0 and rc != 124 and "Traceback" not in out)
    shutil.rmtree(subgrup, ignore_errors=True)

    subpipa = klon(tmp, "subpipa")
    berkas_aksi108 = os.path.join(
        subpipa, ".github", "actions", "resolve-vw",
        "action.yml")
    with open(berkas_aksi108, encoding="utf-8") as f:
        isi_aksi108 = f.read()
    baris_tulis108 = [b for b in isi_aksi108.splitlines()
                      if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
    isi_baru108 = isi_aksi108.replace(
            baris_tulis108,
            baris_tulis108.replace(
                '>> "$GITHUB_OUTPUT"',
                "> >(cat | tee log.txt; "
                "cat >> \"$GITHUB_OUTPUT\")"), 1)
    assert isi_baru108 != isi_aksi108, \
        "suntikan aksi108 tidak menembak"
    with open(berkas_aksi108, "w", encoding="utf-8") as f:
        f.write(isi_baru108)
    rc, out = jalan(subpipa,
                    [sys.executable, GERBANG, "--semua"])
    periksa("pipa dalam segmen: elemen pertama yang dinilai — "
            "gerbang gagal (Audit 1022)", rc != 0 and rc != 124 and "Traceback" not in out)
    shutil.rmtree(subpipa, ignore_errors=True)

    subflockc = klon(tmp, "subflockc")
    berkas_aksi109 = os.path.join(
        subflockc, ".github", "actions", "resolve-vw",
        "action.yml")
    with open(berkas_aksi109, encoding="utf-8") as f:
        isi_aksi109 = f.read()
    baris_tulis109 = [b for b in isi_aksi109.splitlines()
                      if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
    isi_baru109 = isi_aksi109.replace(
            baris_tulis109,
            baris_tulis109.replace(
                '>> "$GITHUB_OUTPUT"',
                "> >(flock kunci.lock -c cat; "
                "cat >> \"$GITHUB_OUTPUT\")"), 1)
    assert isi_baru109 != isi_aksi109, \
        "suntikan aksi109 tidak menembak"
    with open(berkas_aksi109, "w", encoding="utf-8") as f:
        f.write(isi_baru109)
    rc, out = jalan(subflockc,
                    [sys.executable, GERBANG, "--semua"])
    periksa("flock bentuk string -c: nilainya adalah perintah "
            "penguras — gerbang gagal (Audit 1023)", rc != 0 and rc != 124 and "Traceback" not in out)
    shutil.rmtree(subflockc, ignore_errors=True)

    suboperand = klon(tmp, "suboperand")
    berkas_aksi110 = os.path.join(
        suboperand, ".github", "actions", "resolve-vw",
        "action.yml")
    with open(berkas_aksi110, encoding="utf-8") as f:
        isi_aksi110 = f.read()
    baris_tulis110 = [b for b in isi_aksi110.splitlines()
                      if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
    isi_baru110 = isi_aksi110.replace(
            baris_tulis110,
            baris_tulis110.replace(
                '>> "$GITHUB_OUTPUT"',
                "> >(cat <(cat); "
                "cat >> \"$GITHUB_OUTPUT\")"), 1)
    assert isi_baru110 != isi_aksi110, \
        "suntikan aksi110 tidak menembak"
    with open(berkas_aksi110, "w", encoding="utf-8") as f:
        f.write(isi_baru110)
    rc, out = jalan(suboperand,
                    [sys.executable, GERBANG, "--semua"])
    periksa("substitusi masukan sebagai operand: isinya "
            "menguras stdin bersama — gerbang gagal "
            "(Audit 1024)", rc != 0 and rc != 124 and "Traceback" not in out)
    shutil.rmtree(suboperand, ignore_errors=True)

    substitusimati = klon(tmp, "substitusimati")
    berkas_gerbang_2923a = os.path.join(
        substitusimati, "tools", "verifikasi-patch.py")
    with open(berkas_gerbang_2923a, encoding="utf-8") as f:
        isi_gerbang_2923a = f.read()
    jangkar_2923a = ("    if _substitusi_masukan_menguras"
                     "(bagian):\n        return True")
    assert isi_gerbang_2923a.count(jangkar_2923a) == 1, \
        "jangkar panggilan substitusi berubah"
    with open(berkas_gerbang_2923a, "w",
              encoding="utf-8") as f:
        f.write(isi_gerbang_2923a.replace(
            jangkar_2923a,
            "    if False:\n        return True"))
    berkas_aksi2923a = os.path.join(
        substitusimati, ".github", "actions", "resolve-vw",
        "action.yml")
    with open(berkas_aksi2923a, encoding="utf-8") as f:
        isi_aksi2923a = f.read()
    baris_tulis2923a = [b for b in isi_aksi2923a.splitlines()
                        if "tag_mentah" in b
                        and "GITHUB_OUTPUT" in b][0]
    isi_baru2923a = isi_aksi2923a.replace(
            baris_tulis2923a,
            baris_tulis2923a.replace(
                '>> "$GITHUB_OUTPUT"',
                "> >(cat <(cat); "
                "cat >> \"$GITHUB_OUTPUT\")"), 1)
    assert isi_baru2923a != isi_aksi2923a, \
        "suntikan aksi2923a tidak menembak"
    with open(berkas_aksi2923a, "w", encoding="utf-8") as f:
        f.write(isi_baru2923a)
    rc, out = jalan(substitusimati,
                    [sys.executable, GERBANG, "--semua"])
    periksa("panggilan _substitusi_masukan_menguras "
            "dimatikan di klon: suntikan substitusi "
            "Audit 1024 yang tertangkap gerbang utuh kini "
            "lolos - vonis perilaku, bukan hitungan teks "
            "(Audit 2923)", rc == 0)
    shutil.rmtree(substitusimati, ignore_errors=True)

    subcmdsub = klon(tmp, "subcmdsub")
    berkas_aksi116 = os.path.join(
        subcmdsub, ".github", "actions", "resolve-vw",
        "action.yml")
    with open(berkas_aksi116, encoding="utf-8") as f:
        isi_aksi116 = f.read()
    baris_aksi116 = [b for b in isi_aksi116.splitlines()
                      if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
    isi_baru116 = isi_aksi116.replace(
            baris_aksi116,
            baris_aksi116.replace(
                '>> "$GITHUB_OUTPUT"',
                "> >(cat $(cat); cat >> \"$GITHUB_OUTPUT\")"), 1)
    assert isi_baru116 != isi_aksi116, \
        "suntikan aksi116 tidak menembak"
    with open(berkas_aksi116, "w", encoding="utf-8") as f:
        f.write(isi_baru116)
    rc, out = jalan(subcmdsub,
                    [sys.executable, GERBANG, "--semua"])
    periksa("substitusi perintah sebagai operand: isinya menguras — gerbang gagal (Audit 1036)", rc != 0 and rc != 124 and "Traceback" not in out)
    shutil.rmtree(subcmdsub, ignore_errors=True)

    subflockstr = klon(tmp, "subflockstr")
    berkas_aksi115 = os.path.join(
        subflockstr, ".github", "actions", "resolve-vw",
        "action.yml")
    with open(berkas_aksi115, encoding="utf-8") as f:
        isi_aksi115 = f.read()
    baris_aksi115 = [b for b in isi_aksi115.splitlines()
                      if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
    isi_baru115 = isi_aksi115.replace(
            baris_aksi115,
            baris_aksi115.replace(
                '>> "$GITHUB_OUTPUT"',
                "> >(flock kunci.lock -c 'cat berkasLain'; cat >> \"$GITHUB_OUTPUT\")"), 1)
    assert isi_baru115 != isi_aksi115, \
        "suntikan aksi115 tidak menembak"
    with open(berkas_aksi115, "w", encoding="utf-8") as f:
        f.write(isi_baru115)
    rc, out = jalan(subflockstr,
                    [sys.executable, GERBANG, "--semua"])
    periksa("string flock -c multi-kata dinilai utuh: cat berkas bukan penguras — gerbang lolos (Audit 1035)", rc == 0)
    shutil.rmtree(subflockstr, ignore_errors=True)

    subsudo = klon(tmp, "subsudo")
    berkas_aksi117 = os.path.join(
        subsudo, ".github", "actions", "resolve-vw",
        "action.yml")
    with open(berkas_aksi117, encoding="utf-8") as f:
        isi_aksi117 = f.read()
    baris_aksi117 = [b for b in isi_aksi117.splitlines()
                      if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
    isi_baru117 = isi_aksi117.replace(
            baris_aksi117,
            baris_aksi117.replace(
                '>> "$GITHUB_OUTPUT"',
                "> >(sudo cat; cat >> \"$GITHUB_OUTPUT\")"), 1)
    assert isi_baru117 != isi_aksi117, \
        "suntikan aksi117 tidak menembak"
    with open(berkas_aksi117, "w", encoding="utf-8") as f:
        f.write(isi_baru117)
    rc, out = jalan(subsudo,
                    [sys.executable, GERBANG, "--semua"])
    periksa("pembungkus sudo transparan: pembaca di baliknya penguras — gerbang gagal (Audit 1044)", rc != 0 and rc != 124 and "Traceback" not in out)
    shutil.rmtree(subsudo, ignore_errors=True)

    subexecopsi = klon(tmp, "subexecopsi")
    berkas_aksi111 = os.path.join(
        subexecopsi, ".github", "actions", "resolve-vw",
        "action.yml")
    with open(berkas_aksi111, encoding="utf-8") as f:
        isi_aksi111 = f.read()
    baris_aksi111 = [b for b in isi_aksi111.splitlines()
                      if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
    isi_baru111 = isi_aksi111.replace(
            baris_aksi111,
            baris_aksi111.replace(
                '>> "$GITHUB_OUTPUT"',
                "> >(exec -c cat; cat >> \"$GITHUB_OUTPUT\")"), 1)
    assert isi_baru111 != isi_aksi111, \
        "suntikan aksi111 tidak menembak"
    with open(berkas_aksi111, "w", encoding="utf-8") as f:
        f.write(isi_baru111)
    rc, out = jalan(subexecopsi,
                    [sys.executable, GERBANG, "--semua"])
    periksa("opsi milik exec dilompati: exec -c cat adalah penguras — gerbang gagal (Audit 1031)", rc != 0 and rc != 124 and "Traceback" not in out)
    shutil.rmtree(subexecopsi, ignore_errors=True)

    subtimeakh = klon(tmp, "subtimeakh")
    berkas_aksi112 = os.path.join(
        subtimeakh, ".github", "actions", "resolve-vw",
        "action.yml")
    with open(berkas_aksi112, encoding="utf-8") as f:
        isi_aksi112 = f.read()
    baris_aksi112 = [b for b in isi_aksi112.splitlines()
                      if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
    isi_baru112 = isi_aksi112.replace(
            baris_aksi112,
            baris_aksi112.replace(
                '>> "$GITHUB_OUTPUT"',
                "> >(time -- cat; cat >> \"$GITHUB_OUTPUT\")"), 1)
    assert isi_baru112 != isi_aksi112, \
        "suntikan aksi112 tidak menembak"
    with open(berkas_aksi112, "w", encoding="utf-8") as f:
        f.write(isi_baru112)
    rc, out = jalan(subtimeakh,
                    [sys.executable, GERBANG, "--semua"])
    periksa("time -- adalah akhir opsi: pembaca sesudahnya penguras — gerbang gagal (Audit 1032)", rc != 0 and rc != 124 and "Traceback" not in out)
    shutil.rmtree(subtimeakh, ignore_errors=True)

    subexecset = klon(tmp, "subexecset")
    berkas_aksi113 = os.path.join(
        subexecset, ".github", "actions", "resolve-vw",
        "action.yml")
    with open(berkas_aksi113, encoding="utf-8") as f:
        isi_aksi113 = f.read()
    baris_aksi113 = [b for b in isi_aksi113.splitlines()
                      if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
    isi_baru113 = isi_aksi113.replace(
            baris_aksi113,
            baris_aksi113.replace(
                '>> "$GITHUB_OUTPUT"',
                "> >(exec A=1 cat; cat >> \"$GITHUB_OUTPUT\")"), 1)
    assert isi_baru113 != isi_aksi113, \
        "suntikan aksi113 tidak menembak"
    with open(berkas_aksi113, "w", encoding="utf-8") as f:
        f.write(isi_baru113)
    rc, out = jalan(subexecset,
                    [sys.executable, GERBANG, "--semua"])
    periksa("penetapan sesudah exec adalah nama program: bukan penguras — gerbang lolos (Audit 1033)", rc == 0)
    shutil.rmtree(subexecset, ignore_errors=True)

    subcmdkata = klon(tmp, "subcmdkata")
    berkas_aksi114 = os.path.join(
        subcmdkata, ".github", "actions", "resolve-vw",
        "action.yml")
    with open(berkas_aksi114, encoding="utf-8") as f:
        isi_aksi114 = f.read()
    baris_aksi114 = [b for b in isi_aksi114.splitlines()
                      if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
    isi_baru114 = isi_aksi114.replace(
            baris_aksi114,
            baris_aksi114.replace(
                '>> "$GITHUB_OUTPUT"',
                "> >(command time cat; cat >> \"$GITHUB_OUTPUT\")"), 1)
    assert isi_baru114 != isi_aksi114, \
        "suntikan aksi114 tidak menembak"
    with open(berkas_aksi114, "w", encoding="utf-8") as f:
        f.write(isi_baru114)
    rc, out = jalan(subcmdkata,
                    [sys.executable, GERBANG, "--semua"])
    periksa("kata kunci di balik command tak transparan: bukan penguras — gerbang lolos (Audit 1034)", rc == 0)
    shutil.rmtree(subcmdkata, ignore_errors=True)

    subfilter = klon(tmp, "subfilter")
    berkas_aksi118 = os.path.join(
        subfilter, ".github", "actions", "resolve-vw",
        "action.yml")
    with open(berkas_aksi118, encoding="utf-8") as f:
        isi_aksi118 = f.read()
    baris_aksi118 = [b for b in isi_aksi118.splitlines()
                      if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
    isi_baru118 = isi_aksi118.replace(
            baris_aksi118,
            baris_aksi118.replace(
                '>> "$GITHUB_OUTPUT"',
                "> >(sha256sum; cat >> \"$GITHUB_OUTPUT\")"), 1)
    assert isi_baru118 != isi_aksi118, \
        "suntikan aksi118 tidak menembak"
    with open(berkas_aksi118, "w", encoding="utf-8") as f:
        f.write(isi_baru118)
    rc, out = jalan(subfilter,
                    [sys.executable, GERBANG, "--semua"])
    periksa("filter aliran murni tanpa operand adalah penguras — gerbang gagal (Audit 1037)", rc != 0 and rc != 124 and "Traceback" not in out)
    shutil.rmtree(subfilter, ignore_errors=True)

    subskrip = klon(tmp, "subskrip")
    berkas_aksi119 = os.path.join(
        subskrip, ".github", "actions", "resolve-vw",
        "action.yml")
    with open(berkas_aksi119, encoding="utf-8") as f:
        isi_aksi119 = f.read()
    baris_aksi119 = [b for b in isi_aksi119.splitlines()
                      if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
    isi_baru119 = isi_aksi119.replace(
            baris_aksi119,
            baris_aksi119.replace(
                '>> "$GITHUB_OUTPUT"',
                "> >(jq -r .tag; cat >> \"$GITHUB_OUTPUT\")"), 1)
    assert isi_baru119 != isi_aksi119, \
        "suntikan aksi119 tidak menembak"
    with open(berkas_aksi119, "w", encoding="utf-8") as f:
        f.write(isi_baru119)
    rc, out = jalan(subskrip,
                    [sys.executable, GERBANG, "--semua"])
    periksa("perintah beroperand filter: jq tanpa berkas adalah penguras — gerbang gagal (Audit 1038)", rc != 0 and rc != 124 and "Traceback" not in out)
    shutil.rmtree(subskrip, ignore_errors=True)

    subinterp = klon(tmp, "subinterp")
    berkas_aksi120 = os.path.join(
        subinterp, ".github", "actions", "resolve-vw",
        "action.yml")
    with open(berkas_aksi120, encoding="utf-8") as f:
        isi_aksi120 = f.read()
    baris_aksi120 = [b for b in isi_aksi120.splitlines()
                      if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
    isi_baru120 = isi_aksi120.replace(
            baris_aksi120,
            baris_aksi120.replace(
                '>> "$GITHUB_OUTPUT"',
                "> >(python3 -; cat >> \"$GITHUB_OUTPUT\")"), 1)
    assert isi_baru120 != isi_aksi120, \
        "suntikan aksi120 tidak menembak"
    with open(berkas_aksi120, "w", encoding="utf-8") as f:
        f.write(isi_baru120)
    rc, out = jalan(subinterp,
                    [sys.executable, GERBANG, "--semua"])
    periksa("interpreter membaca skrip dari stdin: python3 - adalah penguras — gerbang gagal (Audit 1039)", rc != 0 and rc != 124 and "Traceback" not in out)
    shutil.rmtree(subinterp, ignore_errors=True)

    submapfile = klon(tmp, "submapfile")
    berkas_aksi121 = os.path.join(
        submapfile, ".github", "actions", "resolve-vw",
        "action.yml")
    with open(berkas_aksi121, encoding="utf-8") as f:
        isi_aksi121 = f.read()
    baris_aksi121 = [b for b in isi_aksi121.splitlines()
                      if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
    isi_baru121 = isi_aksi121.replace(
            baris_aksi121,
            baris_aksi121.replace(
                '>> "$GITHUB_OUTPUT"',
                "> >(mapfile; cat >> \"$GITHUB_OUTPUT\")"), 1)
    assert isi_baru121 != isi_aksi121, \
        "suntikan aksi121 tidak menembak"
    with open(berkas_aksi121, "w", encoding="utf-8") as f:
        f.write(isi_baru121)
    rc, out = jalan(submapfile,
                    [sys.executable, GERBANG, "--semua"])
    periksa("builtin penampung tanpa batas menguras — gerbang gagal (Audit 1040)", rc != 0 and rc != 124 and "Traceback" not in out)
    shutil.rmtree(submapfile, ignore_errors=True)

    subddstdin = klon(tmp, "subddstdin")
    berkas_aksi122 = os.path.join(
        subddstdin, ".github", "actions", "resolve-vw",
        "action.yml")
    with open(berkas_aksi122, encoding="utf-8") as f:
        isi_aksi122 = f.read()
    baris_aksi122 = [b for b in isi_aksi122.splitlines()
                      if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
    isi_baru122 = isi_aksi122.replace(
            baris_aksi122,
            baris_aksi122.replace(
                '>> "$GITHUB_OUTPUT"',
                "> >(dd if=/dev/stdin; cat >> \"$GITHUB_OUTPUT\")"), 1)
    assert isi_baru122 != isi_aksi122, \
        "suntikan aksi122 tidak menembak"
    with open(berkas_aksi122, "w", encoding="utf-8") as f:
        f.write(isi_baru122)
    rc, out = jalan(subddstdin,
                    [sys.executable, GERBANG, "--semua"])
    periksa("dd if=/dev/stdin membaca stdin itu sendiri — gerbang gagal (Audit 1041)", rc != 0 and rc != 124 and "Traceback" not in out)
    shutil.rmtree(subddstdin, ignore_errors=True)

    subbacktick = klon(tmp, "subbacktick")
    berkas_aksi123 = os.path.join(
        subbacktick, ".github", "actions", "resolve-vw",
        "action.yml")
    with open(berkas_aksi123, encoding="utf-8") as f:
        isi_aksi123 = f.read()
    baris_aksi123 = [b for b in isi_aksi123.splitlines()
                      if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
    isi_baru123 = isi_aksi123.replace(
            baris_aksi123,
            baris_aksi123.replace(
                '>> "$GITHUB_OUTPUT"',
                "> >(cat `cat`; cat >> \"$GITHUB_OUTPUT\")"), 1)
    assert isi_baru123 != isi_aksi123, \
        "suntikan aksi123 tidak menembak"
    with open(berkas_aksi123, "w", encoding="utf-8") as f:
        f.write(isi_baru123)
    rc, out = jalan(subbacktick,
                    [sys.executable, GERBANG, "--semua"])
    periksa("substitusi backtick dinilai isinya: penguras — gerbang gagal (Audit 1045)", rc != 0 and rc != 124 and "Traceback" not in out)
    shutil.rmtree(subbacktick, ignore_errors=True)

    subkutipgabung = klon(tmp, "subkutipgabung")
    berkas_aksi124 = os.path.join(
        subkutipgabung, ".github", "actions", "resolve-vw",
        "action.yml")
    with open(berkas_aksi124, encoding="utf-8") as f:
        isi_aksi124 = f.read()
    baris_aksi124 = [b for b in isi_aksi124.splitlines()
                      if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
    isi_baru124 = isi_aksi124.replace(
            baris_aksi124,
            baris_aksi124.replace(
                '>> "$GITHUB_OUTPUT"',
                "> >(c''at; cat >> \"$GITHUB_OUTPUT\")"), 1)
    assert isi_baru124 != isi_aksi124, \
        "suntikan aksi124 tidak menembak"
    with open(berkas_aksi124, "w", encoding="utf-8") as f:
        f.write(isi_baru124)
    rc, out = jalan(subkutipgabung,
                    [sys.executable, GERBANG, "--semua"])
    periksa("penggabungan kutip bersebelahan membentuk nama perintah — gerbang gagal (Audit 1046)", rc != 0 and rc != 124 and "Traceback" not in out)
    shutil.rmtree(subkutipgabung, ignore_errors=True)

    subsettr = klon(tmp, "subsettr")
    berkas_aksi125 = os.path.join(
        subsettr, ".github", "actions", "resolve-vw",
        "action.yml")
    with open(berkas_aksi125, encoding="utf-8") as f:
        isi_aksi125 = f.read()
    baris_aksi125 = [b for b in isi_aksi125.splitlines()
                      if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
    isi_baru125 = isi_aksi125.replace(
            baris_aksi125,
            baris_aksi125.replace(
                '>> "$GITHUB_OUTPUT"',
                "> >(tr 'a-z' 'A-Z'; cat >> \"$GITHUB_OUTPUT\")"), 1)
    assert isi_baru125 != isi_aksi125, \
        "suntikan aksi125 tidak menembak"
    with open(berkas_aksi125, "w", encoding="utf-8") as f:
        f.write(isi_baru125)
    rc, out = jalan(subsettr,
                    [sys.executable, GERBANG, "--semua"])
    periksa("set tr berkutip adalah operand himpunan: tetap menguras — gerbang gagal (Audit 1047)", rc != 0 and rc != 124 and "Traceback" not in out)
    shutil.rmtree(subsettr, ignore_errors=True)

    subspasikutip = klon(tmp, "subspasikutip")
    berkas_aksi126 = os.path.join(
        subspasikutip, ".github", "actions", "resolve-vw",
        "action.yml")
    with open(berkas_aksi126, encoding="utf-8") as f:
        isi_aksi126 = f.read()
    baris_aksi126 = [b for b in isi_aksi126.splitlines()
                      if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
    isi_baru126 = isi_aksi126.replace(
            baris_aksi126,
            baris_aksi126.replace(
                '>> "$GITHUB_OUTPUT"',
                "> >(grep 'UT UH'; cat >> \"$GITHUB_OUTPUT\")"), 1)
    assert isi_baru126 != isi_aksi126, \
        "suntikan aksi126 tidak menembak"
    with open(berkas_aksi126, "w", encoding="utf-8") as f:
        f.write(isi_baru126)
    rc, out = jalan(subspasikutip,
                    [sys.executable, GERBANG, "--semua"])
    periksa("operand berkutip berisi spasi satu token: pola grep menguras — gerbang gagal (Audit 1047)", rc != 0 and rc != 124 and "Traceback" not in out)
    shutil.rmtree(subspasikutip, ignore_errors=True)

    subtac = klon(tmp, "subtac")
    berkas_aksi132 = os.path.join(
        subtac, ".github", "actions", "resolve-vw",
        "action.yml")
    with open(berkas_aksi132, encoding="utf-8") as f:
        isi_aksi132 = f.read()
    baris_aksi132 = [b for b in isi_aksi132.splitlines()
                      if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
    isi_baru132 = isi_aksi132.replace(
            baris_aksi132,
            baris_aksi132.replace(
                '>> "$GITHUB_OUTPUT"',
                "> >(tac -s .; cat >> \"$GITHUB_OUTPUT\")"), 1)
    assert isi_baru132 != isi_aksi132, \
        "suntikan aksi132 tidak menembak"
    with open(berkas_aksi132, "w", encoding="utf-8") as f:
        f.write(isi_baru132)
    rc, out = jalan(subtac,
                    [sys.executable, GERBANG, "--semua"])
    periksa("nilai -s tac adalah opsi: tac tetap penguras — gerbang gagal (Audit 1048)", rc != 0 and rc != 124 and "Traceback" not in out)
    shutil.rmtree(subtac, ignore_errors=True)

    subpyi = klon(tmp, "subpyi")
    berkas_aksi131 = os.path.join(
        subpyi, ".github", "actions", "resolve-vw",
        "action.yml")
    with open(berkas_aksi131, encoding="utf-8") as f:
        isi_aksi131 = f.read()
    baris_aksi131 = [b for b in isi_aksi131.splitlines()
                      if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
    isi_baru131 = isi_aksi131.replace(
            baris_aksi131,
            baris_aksi131.replace(
                '>> "$GITHUB_OUTPUT"',
                "> >(python3 -i skrip.py; cat >> \"$GITHUB_OUTPUT\")"), 1)
    assert isi_baru131 != isi_aksi131, \
        "suntikan aksi131 tidak menembak"
    with open(berkas_aksi131, "w", encoding="utf-8") as f:
        f.write(isi_baru131)
    rc, out = jalan(subpyi,
                    [sys.executable, GERBANG, "--semua"])
    periksa("python3 -i jatuh ke REPL sesudah skrip: penguras — gerbang gagal (Audit 1049)", rc != 0 and rc != 124 and "Traceback" not in out)
    shutil.rmtree(subpyi, ignore_errors=True)

    subsudoshell = klon(tmp, "subsudoshell")
    berkas_aksi130 = os.path.join(
        subsudoshell, ".github", "actions", "resolve-vw",
        "action.yml")
    with open(berkas_aksi130, encoding="utf-8") as f:
        isi_aksi130 = f.read()
    baris_aksi130 = [b for b in isi_aksi130.splitlines()
                      if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
    isi_baru130 = isi_aksi130.replace(
            baris_aksi130,
            baris_aksi130.replace(
                '>> "$GITHUB_OUTPUT"',
                "> >(sudo -s; cat >> \"$GITHUB_OUTPUT\")"), 1)
    assert isi_baru130 != isi_aksi130, \
        "suntikan aksi130 tidak menembak"
    with open(berkas_aksi130, "w", encoding="utf-8") as f:
        f.write(isi_baru130)
    rc, out = jalan(subsudoshell,
                    [sys.executable, GERBANG, "--semua"])
    periksa("sudo -s menjalankan shell pembaca perintah: penguras — gerbang gagal (Audit 1050)", rc != 0 and rc != 124 and "Traceback" not in out)
    shutil.rmtree(subsudoshell, ignore_errors=True)

    subexeckata = klon(tmp, "subexeckata")
    berkas_aksi127 = os.path.join(
        subexeckata, ".github", "actions", "resolve-vw",
        "action.yml")
    with open(berkas_aksi127, encoding="utf-8") as f:
        isi_aksi127 = f.read()
    baris_aksi127 = [b for b in isi_aksi127.splitlines()
                      if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
    isi_baru127 = isi_aksi127.replace(
            baris_aksi127,
            baris_aksi127.replace(
                '>> "$GITHUB_OUTPUT"',
                "> >(exec time cat; cat >> \"$GITHUB_OUTPUT\")"), 1)
    assert isi_baru127 != isi_aksi127, \
        "suntikan aksi127 tidak menembak"
    with open(berkas_aksi127, "w", encoding="utf-8") as f:
        f.write(isi_baru127)
    rc, out = jalan(subexeckata,
                    [sys.executable, GERBANG, "--semua"])
    periksa("kata kunci sesudah exec adalah nama program: bukan penguras — gerbang lolos (Audit 1051)", rc == 0)
    shutil.rmtree(subexeckata, ignore_errors=True)

    subawkset = klon(tmp, "subawkset")
    berkas_aksi128 = os.path.join(
        subawkset, ".github", "actions", "resolve-vw",
        "action.yml")
    with open(berkas_aksi128, encoding="utf-8") as f:
        isi_aksi128 = f.read()
    baris_aksi128 = [b for b in isi_aksi128.splitlines()
                      if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
    isi_baru128 = isi_aksi128.replace(
            baris_aksi128,
            baris_aksi128.replace(
                '>> "$GITHUB_OUTPUT"',
                "> >(awk FS=: '{print}'; cat >> \"$GITHUB_OUTPUT\")"), 1)
    assert isi_baru128 != isi_aksi128, \
        "suntikan aksi128 tidak menembak"
    with open(berkas_aksi128, "w", encoding="utf-8") as f:
        f.write(isi_baru128)
    rc, out = jalan(subawkset,
                    [sys.executable, GERBANG, "--semua"])
    periksa("penetapan awk sebelum program membatalkan perintah: bukan penguras — gerbang lolos (Audit 1052)", rc == 0)
    shutil.rmtree(subawkset, ignore_errors=True)

    submapredir = klon(tmp, "submapredir")
    berkas_aksi129 = os.path.join(
        submapredir, ".github", "actions", "resolve-vw",
        "action.yml")
    with open(berkas_aksi129, encoding="utf-8") as f:
        isi_aksi129 = f.read()
    baris_aksi129 = [b for b in isi_aksi129.splitlines()
                      if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
    isi_baru129 = isi_aksi129.replace(
            baris_aksi129,
            baris_aksi129.replace(
                '>> "$GITHUB_OUTPUT"',
                "> >(mapfile < berkasIn; cat >> \"$GITHUB_OUTPUT\")"), 1)
    assert isi_baru129 != isi_aksi129, \
        "suntikan aksi129 tidak menembak"
    with open(berkas_aksi129, "w", encoding="utf-8") as f:
        f.write(isi_baru129)
    rc, out = jalan(submapredir,
                    [sys.executable, GERBANG, "--semua"])
    periksa("redirect sesudah mapfile mengganti stdin: bukan penguras — gerbang lolos (Audit 1053)", rc == 0)
    shutil.rmtree(submapredir, ignore_errors=True)

    envsmutasi = klon(tmp, "envsmutasi")
    berkas_gerbang_2923b = os.path.join(
        envsmutasi, "tools", "verifikasi-patch.py")
    with open(berkas_gerbang_2923b, encoding="utf-8") as f:
        isi_gerbang_2923b = f.read()
    assert isi_gerbang_2923b.count(
        "_posisi_awal=False)") == 1, \
        "jangkar rekursi env -S berubah"
    with open(berkas_gerbang_2923b, "w",
              encoding="utf-8") as f:
        f.write(isi_gerbang_2923b.replace(
            "_posisi_awal=False)", "_posisi_awal=True)"))
    berkas_aksi2923b = os.path.join(
        envsmutasi, ".github", "actions", "resolve-vw",
        "action.yml")
    with open(berkas_aksi2923b, encoding="utf-8") as f:
        isi_aksi2923b = f.read()
    baris_tulis2923b = [b for b in isi_aksi2923b.splitlines()
                        if "tag_mentah" in b
                        and "GITHUB_OUTPUT" in b][0]
    isi_baru2923b = isi_aksi2923b.replace(
            baris_tulis2923b,
            baris_tulis2923b.replace(
                '>> "$GITHUB_OUTPUT"',
                "> >(env -S command cat; "
                "cat >> \"$GITHUB_OUTPUT\")"), 1)
    assert isi_baru2923b != isi_aksi2923b, \
        "suntikan aksi2923b tidak menembak"
    with open(berkas_aksi2923b, "w", encoding="utf-8") as f:
        f.write(isi_baru2923b)
    rc, out = jalan(envsmutasi,
                    [sys.executable, GERBANG, "--semua"])
    periksa("rekursi env -S diposisi-awalkan di klon: "
            "'command' di string -S terkupas sebagai "
            "builtin sehingga suntikan yang lolos di "
            "gerbang utuh (semantik Audit 987) kini gagal "
            "- vonis perilaku, bukan hitungan teks "
            "(Audit 2923)",
            rc != 0 and rc != 124 and "Traceback" not in out)
    shutil.rmtree(envsmutasi, ignore_errors=True)

    subchecksum = klon(tmp, "subchecksum")
    berkas_aksi133 = os.path.join(
        subchecksum, ".github", "actions", "resolve-vw",
        "action.yml")
    with open(berkas_aksi133, encoding="utf-8") as f:
        isi_aksi133 = f.read()
    baris_aksi133 = [b for b in isi_aksi133.splitlines()
                      if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
    isi_baru133 = isi_aksi133.replace(
            baris_aksi133,
            baris_aksi133.replace(
                '>> "$GITHUB_OUTPUT"',
                "> >(sha1sum; cat >> \"$GITHUB_OUTPUT\")"), 1)
    assert isi_baru133 != isi_aksi133, \
        "suntikan aksi133 tidak menembak"
    with open(berkas_aksi133, "w", encoding="utf-8") as f:
        f.write(isi_baru133)
    rc, out = jalan(subchecksum,
                    [sys.executable, GERBANG, "--semua"])
    periksa("sisa keluarga checksum adalah penguras — gerbang gagal (Audit 1055)", rc != 0 and rc != 124 and "Traceback" not in out)
    shutil.rmtree(subchecksum, ignore_errors=True)

    subfilter3 = klon(tmp, "subfilter3")
    berkas_aksi134 = os.path.join(
        subfilter3, ".github", "actions", "resolve-vw",
        "action.yml")
    with open(berkas_aksi134, encoding="utf-8") as f:
        isi_aksi134 = f.read()
    baris_aksi134 = [b for b in isi_aksi134.splitlines()
                      if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
    isi_baru134 = isi_aksi134.replace(
            baris_aksi134,
            baris_aksi134.replace(
                '>> "$GITHUB_OUTPUT"',
                "> >(fmt; cat >> \"$GITHUB_OUTPUT\")"), 1)
    assert isi_baru134 != isi_aksi134, \
        "suntikan aksi134 tidak menembak"
    with open(berkas_aksi134, "w", encoding="utf-8") as f:
        f.write(isi_baru134)
    rc, out = jalan(subfilter3,
                    [sys.executable, GERBANG, "--semua"])
    periksa("filter teks tingkat-3 adalah penguras — gerbang gagal (Audit 1056)", rc != 0 and rc != 124 and "Traceback" not in out)
    shutil.rmtree(subfilter3, ignore_errors=True)

    subkompres = klon(tmp, "subkompres")
    berkas_aksi135 = os.path.join(
        subkompres, ".github", "actions", "resolve-vw",
        "action.yml")
    with open(berkas_aksi135, encoding="utf-8") as f:
        isi_aksi135 = f.read()
    baris_aksi135 = [b for b in isi_aksi135.splitlines()
                      if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
    isi_baru135 = isi_aksi135.replace(
            baris_aksi135,
            baris_aksi135.replace(
                '>> "$GITHUB_OUTPUT"',
                "> >(xz; cat >> \"$GITHUB_OUTPUT\")"), 1)
    assert isi_baru135 != isi_aksi135, \
        "suntikan aksi135 tidak menembak"
    with open(berkas_aksi135, "w", encoding="utf-8") as f:
        f.write(isi_baru135)
    rc, out = jalan(subkompres,
                    [sys.executable, GERBANG, "--semua"])
    periksa("kompresor saudara gzip adalah penguras — gerbang gagal (Audit 1057)", rc != 0 and rc != 124 and "Traceback" not in out)
    shutil.rmtree(subkompres, ignore_errors=True)

    subshell = klon(tmp, "subshell")
    berkas_aksi136 = os.path.join(
        subshell, ".github", "actions", "resolve-vw",
        "action.yml")
    with open(berkas_aksi136, encoding="utf-8") as f:
        isi_aksi136 = f.read()
    baris_aksi136 = [b for b in isi_aksi136.splitlines()
                      if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
    isi_baru136 = isi_aksi136.replace(
            baris_aksi136,
            baris_aksi136.replace(
                '>> "$GITHUB_OUTPUT"',
                "> >(bash -s; cat >> \"$GITHUB_OUTPUT\")"), 1)
    assert isi_baru136 != isi_aksi136, \
        "suntikan aksi136 tidak menembak"
    with open(berkas_aksi136, "w", encoding="utf-8") as f:
        f.write(isi_baru136)
    rc, out = jalan(subshell,
                    [sys.executable, GERBANG, "--semua"])
    periksa("shell membaca perintah dari stdin: penguras — gerbang gagal (Audit 1058)", rc != 0 and rc != 124 and "Traceback" not in out)
    shutil.rmtree(subshell, ignore_errors=True)

    subsqlite = klon(tmp, "subsqlite")
    berkas_aksi137 = os.path.join(
        subsqlite, ".github", "actions", "resolve-vw",
        "action.yml")
    with open(berkas_aksi137, encoding="utf-8") as f:
        isi_aksi137 = f.read()
    baris_aksi137 = [b for b in isi_aksi137.splitlines()
                      if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
    isi_baru137 = isi_aksi137.replace(
            baris_aksi137,
            baris_aksi137.replace(
                '>> "$GITHUB_OUTPUT"',
                "> >(sqlite3; cat >> \"$GITHUB_OUTPUT\")"), 1)
    assert isi_baru137 != isi_aksi137, \
        "suntikan aksi137 tidak menembak"
    with open(berkas_aksi137, "w", encoding="utf-8") as f:
        f.write(isi_baru137)
    rc, out = jalan(subsqlite,
                    [sys.executable, GERBANG, "--semua"])
    periksa("sqlite3 membaca SQL dari stdin: penguras — gerbang gagal (Audit 1059)", rc != 0 and rc != 124 and "Traceback" not in out)
    shutil.rmtree(subsqlite, ignore_errors=True)

    subopenssl = klon(tmp, "subopenssl")
    berkas_aksi138 = os.path.join(
        subopenssl, ".github", "actions", "resolve-vw",
        "action.yml")
    with open(berkas_aksi138, encoding="utf-8") as f:
        isi_aksi138 = f.read()
    baris_aksi138 = [b for b in isi_aksi138.splitlines()
                      if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
    isi_baru138 = isi_aksi138.replace(
            baris_aksi138,
            baris_aksi138.replace(
                '>> "$GITHUB_OUTPUT"',
                "> >(openssl dgst -sha256; cat >> \"$GITHUB_OUTPUT\")"), 1)
    assert isi_baru138 != isi_aksi138, \
        "suntikan aksi138 tidak menembak"
    with open(berkas_aksi138, "w", encoding="utf-8") as f:
        f.write(isi_baru138)
    rc, out = jalan(subopenssl,
                    [sys.executable, GERBANG, "--semua"])
    periksa("openssl dgst tanpa berkas adalah penguras — gerbang gagal (Audit 1060)", rc != 0 and rc != 124 and "Traceback" not in out)
    shutil.rmtree(subopenssl, ignore_errors=True)

    subtar = klon(tmp, "subtar")
    berkas_aksi139 = os.path.join(
        subtar, ".github", "actions", "resolve-vw",
        "action.yml")
    with open(berkas_aksi139, encoding="utf-8") as f:
        isi_aksi139 = f.read()
    baris_aksi139 = [b for b in isi_aksi139.splitlines()
                      if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
    isi_baru139 = isi_aksi139.replace(
            baris_aksi139,
            baris_aksi139.replace(
                '>> "$GITHUB_OUTPUT"',
                "> >(tar -tf -; cat >> \"$GITHUB_OUTPUT\")"), 1)
    assert isi_baru139 != isi_aksi139, \
        "suntikan aksi139 tidak menembak"
    with open(berkas_aksi139, "w", encoding="utf-8") as f:
        f.write(isi_baru139)
    rc, out = jalan(subtar,
                    [sys.executable, GERBANG, "--semua"])
    periksa("tar membaca arsip dari stdin: penguras — gerbang gagal (Audit 1061)", rc != 0 and rc != 124 and "Traceback" not in out)
    shutil.rmtree(subtar, ignore_errors=True)

    subgitstdin = klon(tmp, "subgitstdin")
    berkas_aksi140 = os.path.join(
        subgitstdin, ".github", "actions", "resolve-vw",
        "action.yml")
    with open(berkas_aksi140, encoding="utf-8") as f:
        isi_aksi140 = f.read()
    baris_aksi140 = [b for b in isi_aksi140.splitlines()
                      if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
    isi_baru140 = isi_aksi140.replace(
            baris_aksi140,
            baris_aksi140.replace(
                '>> "$GITHUB_OUTPUT"',
                "> >(git hash-object --stdin; cat >> \"$GITHUB_OUTPUT\")"), 1)
    assert isi_baru140 != isi_aksi140, \
        "suntikan aksi140 tidak menembak"
    with open(berkas_aksi140, "w", encoding="utf-8") as f:
        f.write(isi_baru140)
    rc, out = jalan(subgitstdin,
                    [sys.executable, GERBANG, "--semua"])
    periksa("git hash-object --stdin adalah penguras — gerbang gagal (Audit 1062)", rc != 0 and rc != 124 and "Traceback" not in out)
    shutil.rmtree(subgitstdin, ignore_errors=True)

    subdiff = klon(tmp, "subdiff")
    berkas_aksi141 = os.path.join(
        subdiff, ".github", "actions", "resolve-vw",
        "action.yml")
    with open(berkas_aksi141, encoding="utf-8") as f:
        isi_aksi141 = f.read()
    baris_aksi141 = [b for b in isi_aksi141.splitlines()
                      if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
    isi_baru141 = isi_aksi141.replace(
            baris_aksi141,
            baris_aksi141.replace(
                '>> "$GITHUB_OUTPUT"',
                "> >(diff - berkasIn; cat >> \"$GITHUB_OUTPUT\")"), 1)
    assert isi_baru141 != isi_aksi141, \
        "suntikan aksi141 tidak menembak"
    with open(berkas_aksi141, "w", encoding="utf-8") as f:
        f.write(isi_baru141)
    rc, out = jalan(subdiff,
                    [sys.executable, GERBANG, "--semua"])
    periksa("operand '-' diff adalah stdin: penguras — gerbang gagal (Audit 1063)", rc != 0 and rc != 124 and "Traceback" not in out)
    shutil.rmtree(subdiff, ignore_errors=True)

    subziplist = klon(tmp, "subziplist")
    berkas_aksi142 = os.path.join(
        subziplist, ".github", "actions", "resolve-vw",
        "action.yml")
    with open(berkas_aksi142, encoding="utf-8") as f:
        isi_aksi142 = f.read()
    baris_aksi142 = [b for b in isi_aksi142.splitlines()
                      if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
    isi_baru142 = isi_aksi142.replace(
            baris_aksi142,
            baris_aksi142.replace(
                '>> "$GITHUB_OUTPUT"',
                "> >(zip a.zip -@; cat >> \"$GITHUB_OUTPUT\")"), 1)
    assert isi_baru142 != isi_aksi142, \
        "suntikan aksi142 tidak menembak"
    with open(berkas_aksi142, "w", encoding="utf-8") as f:
        f.write(isi_baru142)
    rc, out = jalan(subziplist,
                    [sys.executable, GERBANG, "--semua"])
    periksa("zip -@ membaca daftar nama dari stdin: penguras — gerbang gagal (Audit 1064)", rc != 0 and rc != 124 and "Traceback" not in out)
    shutil.rmtree(subziplist, ignore_errors=True)

    subunshare = klon(tmp, "subunshare")
    berkas_aksi143 = os.path.join(
        subunshare, ".github", "actions", "resolve-vw",
        "action.yml")
    with open(berkas_aksi143, encoding="utf-8") as f:
        isi_aksi143 = f.read()
    baris_aksi143 = [b for b in isi_aksi143.splitlines()
                      if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
    isi_baru143 = isi_aksi143.replace(
            baris_aksi143,
            baris_aksi143.replace(
                '>> "$GITHUB_OUTPUT"',
                "> >(unshare -Ur cat; cat >> \"$GITHUB_OUTPUT\")"), 1)
    assert isi_baru143 != isi_aksi143, \
        "suntikan aksi143 tidak menembak"
    with open(berkas_aksi143, "w", encoding="utf-8") as f:
        f.write(isi_baru143)
    rc, out = jalan(subunshare,
                    [sys.executable, GERBANG, "--semua"])
    periksa("pembungkus unshare transparan: pembaca di baliknya penguras — gerbang gagal (Audit 1065)", rc != 0 and rc != 124 and "Traceback" not in out)
    shutil.rmtree(subunshare, ignore_errors=True)

    subsocat = klon(tmp, "subsocat")
    berkas_aksi144 = os.path.join(
        subsocat, ".github", "actions", "resolve-vw",
        "action.yml")
    with open(berkas_aksi144, encoding="utf-8") as f:
        isi_aksi144 = f.read()
    baris_aksi144 = [b for b in isi_aksi144.splitlines()
                      if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
    isi_baru144 = isi_aksi144.replace(
            baris_aksi144,
            baris_aksi144.replace(
                '>> "$GITHUB_OUTPUT"',
                "> >(socat - /dev/null; cat >> \"$GITHUB_OUTPUT\")"), 1)
    assert isi_baru144 != isi_aksi144, \
        "suntikan aksi144 tidak menembak"
    with open(berkas_aksi144, "w", encoding="utf-8") as f:
        f.write(isi_baru144)
    rc, out = jalan(subsocat,
                    [sys.executable, GERBANG, "--semua"])
    periksa("alamat STDIO socat membaca stdin: penguras — gerbang gagal (Audit 1066)", rc != 0 and rc != 124 and "Traceback" not in out)
    shutil.rmtree(subsocat, ignore_errors=True)

    submawk = klon(tmp, "submawk")
    berkas_aksi145 = os.path.join(
        submawk, ".github", "actions", "resolve-vw",
        "action.yml")
    with open(berkas_aksi145, encoding="utf-8") as f:
        isi_aksi145 = f.read()
    baris_aksi145 = [b for b in isi_aksi145.splitlines()
                      if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
    isi_baru145 = isi_aksi145.replace(
            baris_aksi145,
            baris_aksi145.replace(
                '>> "$GITHUB_OUTPUT"',
                "> >(mawk '{print}'; cat >> \"$GITHUB_OUTPUT\")"), 1)
    assert isi_baru145 != isi_aksi145, \
        "suntikan aksi145 tidak menembak"
    with open(berkas_aksi145, "w", encoding="utf-8") as f:
        f.write(isi_baru145)
    rc, out = jalan(submawk,
                    [sys.executable, GERBANG, "--semua"])
    periksa("varian nama awk dinilai keluarga skrip: penguras — gerbang gagal (Audit 1067)", rc != 0 and rc != 124 and "Traceback" not in out)
    shutil.rmtree(submawk, ignore_errors=True)

    subcurl = klon(tmp, "subcurl")
    berkas_aksi146 = os.path.join(
        subcurl, ".github", "actions", "resolve-vw",
        "action.yml")
    with open(berkas_aksi146, encoding="utf-8") as f:
        isi_aksi146 = f.read()
    baris_aksi146 = [b for b in isi_aksi146.splitlines()
                      if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
    isi_baru146 = isi_aksi146.replace(
            baris_aksi146,
            baris_aksi146.replace(
                '>> "$GITHUB_OUTPUT"',
                "> >(curl -d @- https://contoh.invalid/; cat >> \"$GITHUB_OUTPUT\")"), 1)
    assert isi_baru146 != isi_aksi146, \
        "suntikan aksi146 tidak menembak"
    with open(berkas_aksi146, "w", encoding="utf-8") as f:
        f.write(isi_baru146)
    rc, out = jalan(subcurl,
                    [sys.executable, GERBANG, "--semua"])
    periksa("curl -d @- membaca data dari stdin: penguras — gerbang gagal (Audit 1068)", rc != 0 and rc != 124 and "Traceback" not in out)
    shutil.rmtree(subcurl, ignore_errors=True)

    subgpg = klon(tmp, "subgpg")
    berkas_aksi147 = os.path.join(
        subgpg, ".github", "actions", "resolve-vw",
        "action.yml")
    with open(berkas_aksi147, encoding="utf-8") as f:
        isi_aksi147 = f.read()
    baris_aksi147 = [b for b in isi_aksi147.splitlines()
                      if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
    isi_baru147 = isi_aksi147.replace(
            baris_aksi147,
            baris_aksi147.replace(
                '>> "$GITHUB_OUTPUT"',
                "> >(gpg --batch --pinentry-mode loopback --passphrase x -c; cat >> \"$GITHUB_OUTPUT\")"), 1)
    assert isi_baru147 != isi_aksi147, \
        "suntikan aksi147 tidak menembak"
    with open(berkas_aksi147, "w", encoding="utf-8") as f:
        f.write(isi_baru147)
    rc, out = jalan(subgpg,
                    [sys.executable, GERBANG, "--semua"])
    periksa("gpg batch simetris membaca plaintext dari stdin: penguras — gerbang gagal (Audit 1069)", rc != 0 and rc != 124 and "Traceback" not in out)
    shutil.rmtree(subgpg, ignore_errors=True)

    subsyscat = klon(tmp, "subsyscat")
    berkas_aksi148 = os.path.join(
        subsyscat, ".github", "actions", "resolve-vw",
        "action.yml")
    with open(berkas_aksi148, encoding="utf-8") as f:
        isi_aksi148 = f.read()
    baris_aksi148 = [b for b in isi_aksi148.splitlines()
                      if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
    isi_baru148 = isi_aksi148.replace(
            baris_aksi148,
            baris_aksi148.replace(
                '>> "$GITHUB_OUTPUT"',
                "> >(systemd-cat; cat >> \"$GITHUB_OUTPUT\")"), 1)
    assert isi_baru148 != isi_aksi148, \
        "suntikan aksi148 tidak menembak"
    with open(berkas_aksi148, "w", encoding="utf-8") as f:
        f.write(isi_baru148)
    rc, out = jalan(subsyscat,
                    [sys.executable, GERBANG, "--semua"])
    periksa("systemd-cat tanpa perintah meneruskan stdin: penguras — gerbang gagal (Audit 1070)", rc != 0 and rc != 124 and "Traceback" not in out)
    shutil.rmtree(subsyscat, ignore_errors=True)

    submake = klon(tmp, "submake")
    berkas_aksi149 = os.path.join(
        submake, ".github", "actions", "resolve-vw",
        "action.yml")
    with open(berkas_aksi149, encoding="utf-8") as f:
        isi_aksi149 = f.read()
    baris_aksi149 = [b for b in isi_aksi149.splitlines()
                      if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
    isi_baru149 = isi_aksi149.replace(
            baris_aksi149,
            baris_aksi149.replace(
                '>> "$GITHUB_OUTPUT"',
                "> >(make -f -; cat >> \"$GITHUB_OUTPUT\")"), 1)
    assert isi_baru149 != isi_aksi149, \
        "suntikan aksi149 tidak menembak"
    with open(berkas_aksi149, "w", encoding="utf-8") as f:
        f.write(isi_baru149)
    rc, out = jalan(submake,
                    [sys.executable, GERBANG, "--semua"])
    periksa("make -f - membaca makefile dari stdin: penguras — gerbang gagal (Audit 1071)", rc != 0 and rc != 124 and "Traceback" not in out)
    shutil.rmtree(submake, ignore_errors=True)

    submore = klon(tmp, "submore")
    berkas_aksi150 = os.path.join(
        submore, ".github", "actions", "resolve-vw",
        "action.yml")
    with open(berkas_aksi150, encoding="utf-8") as f:
        isi_aksi150 = f.read()
    baris_aksi150 = [b for b in isi_aksi150.splitlines()
                      if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
    isi_baru150 = isi_aksi150.replace(
            baris_aksi150,
            baris_aksi150.replace(
                '>> "$GITHUB_OUTPUT"',
                "> >(more; cat >> \"$GITHUB_OUTPUT\")"), 1)
    assert isi_baru150 != isi_aksi150, \
        "suntikan aksi150 tidak menembak"
    with open(berkas_aksi150, "w", encoding="utf-8") as f:
        f.write(isi_baru150)
    rc, out = jalan(submore,
                    [sys.executable, GERBANG, "--semua"])
    periksa("pager pada stdin pipa menguras: gerbang gagal (Audit 1072)", rc != 0 and rc != 124 and "Traceback" not in out)
    shutil.rmtree(submore, ignore_errors=True)

    skenario_substitusi(tmp, 'sub151',
                        '> >(comm -1 - berkas; cat >> "$GITHUB_OUTPUT")',
                        'comm -1 bendera: gerbang gagal (Audit 1074)', False)

    skenario_substitusi(tmp, 'sub152',
                        '> >(openssl dgst -sha256 -; cat >> "$GITHUB_OUTPUT")',
                        "openssl dgst operand '-': gerbang gagal (Audit 1075)", False)

    skenario_substitusi(tmp, 'sub153',
                        '> >(openssl sha256 -; cat >> "$GITHUB_OUTPUT")',
                        "openssl alias digest '-': gerbang gagal (Audit 1075)", False)

    skenario_substitusi(tmp, 'sub154',
                        '> >(openssl enc -base64 -in -; cat >> "$GITHUB_OUTPUT")',
                        "openssl enc -in '-': gerbang gagal (Audit 1075)", False)

    skenario_substitusi(tmp, 'sub155',
                        '> >(openssl dgst -rand berkas -sha256; cat >> "$GITHUB_OUTPUT")',
                        'openssl -rand bernilai: gerbang gagal (Audit 1076)', False)

    skenario_substitusi(tmp, 'sub156',
                        '> >(tar tf -; cat >> "$GITHUB_OUTPUT")',
                        'tar bundel gaya lama: gerbang gagal (Audit 1077)', False)

    skenario_substitusi(tmp, 'sub157',
                        '> >(tar -tf-; cat >> "$GITHUB_OUTPUT")',
                        'tar nilai -f merekat: gerbang gagal (Audit 1077)', False)

    skenario_substitusi(tmp, 'sub158',
                        '> >(tar -cf - berkas; cat >> "$GITHUB_OUTPUT")',
                        "tar mode buat '-f -' keluaran: gerbang lolos (Audit 1078)", True)

    skenario_substitusi(tmp, 'sub159',
                        '> >(git hash-object --stdin-paths; cat >> "$GITHUB_OUTPUT")',
                        'git hash-object --stdin-paths: gerbang gagal (Audit 1079)', False)

    skenario_substitusi(tmp, 'sub160',
                        '> >(git credential fill; cat >> "$GITHUB_OUTPUT")',
                        'git credential fill: gerbang gagal (Audit 1079)', False)

    skenario_substitusi(tmp, 'sub161',
                        '> >(git mktree; cat >> "$GITHUB_OUTPUT")',
                        'git mktree: gerbang gagal (Audit 1079)', False)

    skenario_substitusi(tmp, 'sub162',
                        '> >(git commit -F -; cat >> "$GITHUB_OUTPUT")',
                        "git commit -F '-': gerbang gagal (Audit 1079)", False)

    skenario_substitusi(tmp, 'sub163',
                        '> >(pr +3; cat >> "$GITHUB_OUTPUT")',
                        'operand +N pr: gerbang gagal (Audit 1080)', False)

    skenario_substitusi(tmp, 'sub164',
                        '> >(tail +2; cat >> "$GITHUB_OUTPUT")',
                        'operand +N tail: gerbang gagal (Audit 1080)', False)

    skenario_substitusi(tmp, 'sub165',
                        '> >(sort +2; cat >> "$GITHUB_OUTPUT")',
                        'operand +N sort: gerbang gagal (Audit 1080)', False)

    skenario_substitusi(tmp, 'sub166',
                        '> >(python3 -i -c pass; cat >> "$GITHUB_OUTPUT")',
                        'python -i sesudah -c: gerbang gagal (Audit 1081)', False)

    skenario_substitusi(tmp, 'sub167',
                        '> >(bash -ec cat; cat >> "$GITHUB_OUTPUT")',
                        'bundel shell -ec: gerbang gagal (Audit 1082)', False)

    skenario_substitusi(tmp, 'sub168',
                        '> >(python3 -ic pass; cat >> "$GITHUB_OUTPUT")',
                        'bundel python -ic: gerbang gagal (Audit 1082)', False)

    skenario_substitusi(tmp, 'sub169',
                        '> >(sudo -ns; cat >> "$GITHUB_OUTPUT")',
                        'bundel sudo -ns: gerbang gagal (Audit 1082)', False)

    skenario_substitusi(tmp, 'sub170',
                        '> >(bash -s berkas; cat >> "$GITHUB_OUTPUT")',
                        'shell -s operand $0: gerbang gagal (Audit 1083)', False)

    skenario_substitusi(tmp, 'sub171',
                        '> >(chroot --userspec root / cat; cat >> "$GITHUB_OUTPUT")',
                        'chroot tabel opsi: gerbang gagal (Audit 1084)', False)

    skenario_substitusi(tmp, 'sub172',
                        '> >(curl -d@- http://127.0.0.1:9/x; cat >> "$GITHUB_OUTPUT")',
                        'curl -d@- merekat: gerbang gagal (Audit 1085)', False)

    skenario_substitusi(tmp, 'sub173',
                        '> >(curl -F a=@- http://127.0.0.1:9/x; cat >> "$GITHUB_OUTPUT")',
                        'curl -F a=@-: gerbang gagal (Audit 1085)', False)

    skenario_substitusi(tmp, 'sub174',
                        '> >(curl --form a=@- http://127.0.0.1:9/x; cat >> "$GITHUB_OUTPUT")',
                        'curl --form a=@-: gerbang gagal (Audit 1085)', False)

    skenario_substitusi(tmp, 'sub175',
                        '> >(curl --data-urlencode name@- http://127.0.0.1:9/x; cat >> "$GITHUB_OUTPUT")',
                        'curl urlencode name@-: gerbang gagal (Audit 1085)', False)

    skenario_substitusi(tmp, 'sub176',
                        '> >(curl --data-raw - http://127.0.0.1:9/x; cat >> "$GITHUB_OUTPUT")',
                        "curl --data-raw '-' harfiah: gerbang lolos (Audit 1086)", True)

    skenario_substitusi(tmp, 'sub177',
                        '> >(tr -d a b; cat >> "$GITHUB_OUTPUT")',
                        'tr -d dua himpunan galat: gerbang lolos (Audit 1087)', True)

    skenario_substitusi(tmp, 'sub178',
                        '> >(tar -tf - < berkas; cat >> "$GITHUB_OUTPUT")',
                        'khusus buta redirect ekor tar: gerbang lolos (Audit 1088)', True)

    skenario_substitusi(tmp, 'sub179',
                        '> >(git hash-object --stdin < berkas; cat >> "$GITHUB_OUTPUT")',
                        'khusus buta redirect ekor git: gerbang lolos (Audit 1088)', True)

    skenario_substitusi(tmp, 'sub180',
                        '> >(socat - /dev/null < berkas; cat >> "$GITHUB_OUTPUT")',
                        'khusus buta redirect ekor socat: gerbang lolos (Audit 1088)', True)

    skenario_substitusi(tmp, 'sub181',
                        '> >(more berkas; cat >> "$GITHUB_OUTPUT")',
                        'more beroperand tetap menguras: gerbang gagal (Audit 1089)', False)

    skenario_substitusi(tmp, 'sub182',
                        '> >(python3 -m json.tool berkas; cat >> "$GITHUB_OUTPUT")',
                        'python -m operand berkas: gerbang lolos (Audit 1090)', True)

    skenario_substitusi(tmp, 'sub183',
                        '> >(python3 -m asyncio; cat >> "$GITHUB_OUTPUT")',
                        'python -m asyncio REPL: gerbang gagal (Audit 1091)', False)

    skenario_substitusi(tmp, 'sub184',
                        '> >(nc -z 127.0.0.1 9; cat >> "$GITHUB_OUTPUT")',
                        'nc -z pindai: gerbang lolos (Audit 1092)', True)

    skenario_substitusi(tmp, 'sub185',
                        '> >(patch -i berkas2 berkas; cat >> "$GITHUB_OUTPUT")',
                        'patch -i sumber berkas: gerbang lolos (Audit 1093)', True)

    skenario_substitusi(tmp, 'sub186',
                        '> >(zip -@; cat >> "$GITHUB_OUTPUT")',
                        'zip -@ tanpa arsip: gerbang lolos (Audit 1094)', True)

    skenario_substitusi(tmp, 'sub187',
                        '> >(rg --files; cat >> "$GITHUB_OUTPUT")',
                        'rg --files: gerbang lolos (Audit 1095)', True)

    skenario_substitusi(tmp, 'sub188',
                        '> >(openssl dgst -sign; cat >> "$GITHUB_OUTPUT")',
                        'opsi menggantung openssl: gerbang lolos (Audit 1096)', True)

    skenario_substitusi(tmp, 'sub189',
                        '> >(sqlite3 -cmd; cat >> "$GITHUB_OUTPUT")',
                        'opsi menggantung sqlite3: gerbang lolos (Audit 1096)', True)

    skenario_substitusi(tmp, 'sub190',
                        '> >(head -n; cat >> "$GITHUB_OUTPUT")',
                        'opsi menggantung head: gerbang lolos (Audit 1096)', True)

    skenario_substitusi(tmp, 'sub191',
                        '> >(sudo -si; cat >> "$GITHUB_OUTPUT")',
                        'sudo -si ditolak: gerbang lolos (Audit 1097)', True)

    skenario_substitusi(tmp, 'sub192',
                        '> >(bunzip2; cat >> "$GITHUB_OUTPUT")',
                        'bunzip2: gerbang gagal (Audit 1098)', False)

    skenario_substitusi(tmp, 'sub193',
                        '> >(bzcat; cat >> "$GITHUB_OUTPUT")',
                        'bzcat: gerbang gagal (Audit 1098)', False)

    skenario_substitusi(tmp, 'sub194',
                        '> >(xzcat; cat >> "$GITHUB_OUTPUT")',
                        'xzcat: gerbang gagal (Audit 1098)', False)

    skenario_substitusi(tmp, 'sub195',
                        '> >(unxz; cat >> "$GITHUB_OUTPUT")',
                        'unxz: gerbang gagal (Audit 1098)', False)

    skenario_substitusi(tmp, 'sub196',
                        '> >(unlzma; cat >> "$GITHUB_OUTPUT")',
                        'unlzma: gerbang gagal (Audit 1098)', False)

    skenario_substitusi(tmp, 'sub197',
                        '> >(cat /dev/stdin; cat >> "$GITHUB_OUTPUT")',
                        'operand /dev/stdin: gerbang gagal (Audit 1099)', False)

    skenario_substitusi(tmp, 'sub198',
                        '> >(head /dev/fd/0; cat >> "$GITHUB_OUTPUT")',
                        'operand /dev/fd/0: gerbang gagal (Audit 1099)', False)

    skenario_substitusi(tmp, 'sub199',
                        '> >(grep UTUH /dev/stdin; cat >> "$GITHUB_OUTPUT")',
                        'grep berkas /dev/stdin: gerbang gagal (Audit 1099)', False)

    skenario_substitusi(tmp, 'sub200',
                        '> >(sort --random-source berkas2 -; cat >> "$GITHUB_OUTPUT")',
                        'sort --random-source bernilai: gerbang gagal (Audit 1100)', False)

    skenario_substitusi(tmp, 'sub201',
                        '> >(wc --total -; cat >> "$GITHUB_OUTPUT")',
                        'wc --total bernilai: gerbang gagal (Audit 1100)', False)

    skenario_substitusi(tmp, 'sub202',
                        '> >(fmt -p x -; cat >> "$GITHUB_OUTPUT")',
                        'fmt -p bernilai: gerbang gagal (Audit 1100)', False)

    skenario_substitusi(tmp, 'sub203',
                        '> >(basenc; cat >> "$GITHUB_OUTPUT")',
                        'basenc polos galat: gerbang lolos (Audit 1101)', True)

    skenario_substitusi(tmp, 'sub204',
                        '> >(basenc --base64; cat >> "$GITHUB_OUTPUT")',
                        'basenc bermode: gerbang gagal (Audit 1101)', False)

    skenario_substitusi(tmp, 'sub205',
                        '> >(cat --version; cat >> "$GITHUB_OUTPUT")',
                        'cat --version informasional: gerbang lolos (Audit 1102)', True)

    skenario_substitusi(tmp, 'sub206',
                        '> >(sort --help; cat >> "$GITHUB_OUTPUT")',
                        'sort --help informasional: gerbang lolos (Audit 1102)', True)

    skenario_substitusi(tmp, 'sub207',
                        '> >(iconv -l; cat >> "$GITHUB_OUTPUT")',
                        'iconv -l informasional: gerbang lolos (Audit 1102)', True)

    skenario_substitusi(tmp, 'sub208',
                        '> >(grep -f - berkas; cat >> "$GITHUB_OUTPUT")',
                        "grep -f '-' beroperand: gerbang gagal (Audit 1103)", False)

    skenario_substitusi(tmp, 'sub209',
                        '> >(sed -f - berkas; cat >> "$GITHUB_OUTPUT")',
                        "sed -f '-' beroperand: gerbang gagal (Audit 1103)", False)

    skenario_substitusi(tmp, 'sub210',
                        '> >(awk -f - berkas; cat >> "$GITHUB_OUTPUT")',
                        "awk -f '-' beroperand: gerbang gagal (Audit 1103)", False)

    skenario_substitusi(tmp, 'sub211',
                        '> >(jq -f -; cat >> "$GITHUB_OUTPUT")',
                        "jq -f '-' ditolak: gerbang lolos (Audit 1103)", True)

    skenario_substitusi(tmp, 'sub212',
                        '> >(openssl base64; cat >> "$GITHUB_OUTPUT")',
                        'openssl base64: gerbang gagal (Audit 1104)', False)

    skenario_substitusi(tmp, 'sub213',
                        '> >(openssl smime -encrypt sert.pem; cat >> "$GITHUB_OUTPUT")',
                        'openssl smime: gerbang gagal (Audit 1104)', False)

    skenario_substitusi(tmp, 'sub214',
                        '> >(openssl passwd -stdin; cat >> "$GITHUB_OUTPUT")',
                        'openssl passwd -stdin: gerbang gagal (Audit 1104)', False)

    skenario_substitusi(tmp, 'sub215',
                        '> >(openssl pkeyutl -inkey kunci.pem -sign; cat >> "$GITHUB_OUTPUT")',
                        'openssl pkeyutl: gerbang gagal (Audit 1104)', False)

    skenario_substitusi(tmp, 'sub216',
                        '> >(openssl x509; cat >> "$GITHUB_OUTPUT")',
                        'openssl x509 tanpa -in: gerbang gagal (Audit 1105)', False)

    skenario_substitusi(tmp, 'sub217',
                        '> >(gpg --batch --import; cat >> "$GITHUB_OUTPUT")',
                        'gpg --import: gerbang gagal (Audit 1106)', False)

    skenario_substitusi(tmp, 'sub218',
                        '> >(gpg --batch --dearmor; cat >> "$GITHUB_OUTPUT")',
                        'gpg --dearmor: gerbang gagal (Audit 1106)', False)

    skenario_substitusi(tmp, 'sub219',
                        '> >(gpg --batch --print-md SHA256; cat >> "$GITHUB_OUTPUT")',
                        'gpg --print-md: gerbang gagal (Audit 1106)', False)

    skenario_substitusi(tmp, 'sub220',
                        '> >(strace cat; cat >> "$GITHUB_OUTPUT")',
                        'strace pembungkus: gerbang gagal (Audit 1107)', False)

    skenario_substitusi(tmp, 'sub221',
                        '> >(strace -f -o jejak cat; cat >> "$GITHUB_OUTPUT")',
                        'strace beropsi: gerbang gagal (Audit 1107)', False)

    skenario_substitusi(tmp, 'sub222',
                        '> >(dbus-run-session cat; cat >> "$GITHUB_OUTPUT")',
                        'dbus-run-session: gerbang gagal (Audit 1107)', False)

    skenario_substitusi(tmp, 'sub223',
                        '> >(runuser -u root -- cat; cat >> "$GITHUB_OUTPUT")',
                        'runuser: gerbang gagal (Audit 1107)', False)

    skenario_substitusi(tmp, 'sub224',
                        '> >(su -c cat; cat >> "$GITHUB_OUTPUT")',
                        'su -c string: gerbang gagal (Audit 1107)', False)

    skenario_substitusi(tmp, 'sub225',
                        '> >(sg root -c cat; cat >> "$GITHUB_OUTPUT")',
                        'sg -c string: gerbang gagal (Audit 1107)', False)

    skenario_substitusi(tmp, 'sub226',
                        '> >(vi berkas; cat >> "$GITHUB_OUTPUT")',
                        'vi beroperand: gerbang gagal (Audit 1108)', False)

    skenario_substitusi(tmp, 'sub227',
                        '> >(view berkas; cat >> "$GITHUB_OUTPUT")',
                        'view beroperand: gerbang gagal (Audit 1108)', False)

    skenario_substitusi(tmp, 'sub228',
                        '> >(ex; cat >> "$GITHUB_OUTPUT")',
                        'ex polos: gerbang gagal (Audit 1108)', False)

    skenario_substitusi(tmp, 'sub229',
                        '> >(less -p UTUH; cat >> "$GITHUB_OUTPUT")',
                        'less -p pola bernilai: gerbang gagal (Audit 1109)', False)

    skenario_substitusi(tmp, 'sub230',
                        '> >(comm --output-delimiter - berkas berkas2; cat >> "$GITHUB_OUTPUT")',
                        "comm --output-delimiter '-': gerbang lolos (Audit 1110)", True)

    skenario_substitusi(tmp, 'sub231',
                        '> >(du --files0-from=-; cat >> "$GITHUB_OUTPUT")',
                        'du --files0-from=-: gerbang gagal (Audit 1111)', False)

    skenario_substitusi(tmp, 'sub232',
                        '> >(find -files0-from - -maxdepth 0; cat >> "$GITHUB_OUTPUT")',
                        'find -files0-from: gerbang gagal (Audit 1111)', False)

    skenario_substitusi(tmp, 'sub233',
                        '> >(wall; cat >> "$GITHUB_OUTPUT")',
                        'wall polos: gerbang gagal (Audit 1112)', False)

    skenario_substitusi(tmp, 'sub234',
                        '> >(c++filt; cat >> "$GITHUB_OUTPUT")',
                        'c++filt polos: gerbang gagal (Audit 1112)', False)

    skenario_substitusi(tmp, 'sub235',
                        '> >(addr2line -e /usr/bin/cat; cat >> "$GITHUB_OUTPUT")',
                        'addr2line -e: gerbang gagal (Audit 1112)', False)

    skenario_substitusi(tmp, 'sub236',
                        '> >(dig -f -; cat >> "$GITHUB_OUTPUT")',
                        "dig -f '-': gerbang gagal (Audit 1112)", False)

    skenario_substitusi(tmp, 'sub237',
                        '> >(nslookup; cat >> "$GITHUB_OUTPUT")',
                        'nslookup polos: gerbang gagal (Audit 1112)', False)

    skenario_substitusi(tmp, 'sub238',
                        '> >(original-awk \'{print}\'; cat >> "$GITHUB_OUTPUT")',
                        'original-awk: gerbang gagal (Audit 1113)', False)

    skenario_substitusi(tmp, 'sub239',
                        '> >(perl; cat >> "$GITHUB_OUTPUT")',
                        'perl telanjang: gerbang gagal (Audit 1119)', False)

    skenario_substitusi(tmp, 'sub240',
                        '> >(perl -w; cat >> "$GITHUB_OUTPUT")',
                        'perl -w: gerbang gagal (Audit 1119)', False)

    skenario_substitusi(tmp, 'sub241',
                        '> >(perl skrip.pl; cat >> "$GITHUB_OUTPUT")',
                        'perl berkas skrip: gerbang lolos (Audit 1119)', True)

    skenario_substitusi(tmp, 'sub242',
                        '> >(perl -e print; cat >> "$GITHUB_OUTPUT")',
                        'perl -e: gerbang lolos (Audit 1119)', True)

    skenario_substitusi(tmp, 'sub243',
                        '> >(tar t; cat >> "$GITHUB_OUTPUT")',
                        'tar t tanpa -f: gerbang gagal (Audit 1120)', False)

    skenario_substitusi(tmp, 'sub244',
                        '> >(tar x; cat >> "$GITHUB_OUTPUT")',
                        'tar x tanpa -f: gerbang gagal (Audit 1120)', False)

    skenario_substitusi(tmp, 'sub245',
                        '> >(tar tf contoh.tar; cat >> "$GITHUB_OUTPUT")',
                        'tar tf berarsip: gerbang lolos (Audit 1120)', True)

    skenario_substitusi(tmp, 'sub246',
                        '> >(tar c; cat >> "$GITHUB_OUTPUT")',
                        'tar c tanpa operand: gerbang lolos (Audit 1120)', True)

    skenario_substitusi(tmp, 'sub247',
                        '> >(unshare -r; cat >> "$GITHUB_OUTPUT")',
                        'unshare tanpa perintah: gerbang gagal (Audit 1121)', False)

    skenario_substitusi(tmp, 'sub248',
                        '> >(chroot /baru; cat >> "$GITHUB_OUTPUT")',
                        'chroot tanpa perintah: gerbang gagal (Audit 1121)', False)

    skenario_substitusi(tmp, 'sub249',
                        '> >(unshare -r cat berkas; cat >> "$GITHUB_OUTPUT")',
                        'unshare berperintah: gerbang lolos (Audit 1121)', True)

    skenario_substitusi(tmp, 'sub250',
                        '> >(expect; cat >> "$GITHUB_OUTPUT")',
                        'expect telanjang: gerbang gagal (Audit 1122)', False)

    skenario_substitusi(tmp, 'sub251',
                        '> >(expect -f -; cat >> "$GITHUB_OUTPUT")',
                        'expect -f -: gerbang gagal (Audit 1122)', False)

    skenario_substitusi(tmp, 'sub252',
                        '> >(expect -f skrip.exp; cat >> "$GITHUB_OUTPUT")',
                        'expect -f berkas: gerbang lolos (Audit 1122)', True)

    skenario_substitusi(tmp, 'sub253',
                        '> >(gpg --verify; cat >> "$GITHUB_OUTPUT")',
                        'gpg --verify: gerbang gagal (Audit 1123)', False)

    skenario_substitusi(tmp, 'sub254',
                        '> >(gpg --verify berkas.sig; cat >> "$GITHUB_OUTPUT")',
                        'gpg --verify beroperand: gerbang lolos (Audit 1123)', True)

    skenario_substitusi(tmp, 'sub255',
                        '> >(git stripspace; cat >> "$GITHUB_OUTPUT")',
                        'git stripspace: gerbang gagal (Audit 1124)', False)

    skenario_substitusi(tmp, 'sub256',
                        '> >(git patch-id; cat >> "$GITHUB_OUTPUT")',
                        'git patch-id: gerbang gagal (Audit 1124)', False)

    skenario_substitusi(tmp, 'sub257',
                        '> >(git mktag; cat >> "$GITHUB_OUTPUT")',
                        'git mktag: gerbang gagal (Audit 1124)', False)

    skenario_substitusi(tmp, 'sub258',
                        '> >(git update-index --index-info; cat >> "$GITHUB_OUTPUT")',
                        'git update-index --index-info: gerbang gagal (Audit 1124)', False)

    skenario_substitusi(tmp, 'sub259',
                        '> >(git index-pack --stdin; cat >> "$GITHUB_OUTPUT")',
                        'git index-pack --stdin: gerbang gagal (Audit 1124)', False)

    skenario_substitusi(tmp, 'sub260',
                        '> >(git index-pack berkas.pack; cat >> "$GITHUB_OUTPUT")',
                        'git index-pack beroperand: gerbang lolos (Audit 1124)', True)

    skenario_substitusi(tmp, 'sub261',
                        '> >(numfmt --from auto; cat >> "$GITHUB_OUTPUT")',
                        'numfmt --from: gerbang gagal (Audit 1125)', False)

    skenario_substitusi(tmp, 'sub262',
                        '> >(numfmt --to si; cat >> "$GITHUB_OUTPUT")',
                        'numfmt --to: gerbang gagal (Audit 1125)', False)

    skenario_substitusi(tmp, 'sub263',
                        '> >(numfmt --from-unit 1000; cat >> "$GITHUB_OUTPUT")',
                        'numfmt --from-unit: gerbang gagal (Audit 1125)', False)

    skenario_substitusi(tmp, 'sub264',
                        '> >(numfmt berkas; cat >> "$GITHUB_OUTPUT")',
                        'numfmt beroperand: gerbang lolos (Audit 1125)', True)

    skenario_substitusi(tmp, 'sub265',
                        '> >(jq --indent 2 .; cat >> "$GITHUB_OUTPUT")',
                        'jq --indent: gerbang gagal (Audit 1126)', False)

    skenario_substitusi(tmp, 'sub266',
                        '> >(jq . berkas.json; cat >> "$GITHUB_OUTPUT")',
                        'jq beroperand: gerbang lolos (Audit 1126)', True)

    skenario_substitusi(tmp, 'sub267',
                        '> >(grep --exclude-from daftar pola; cat >> "$GITHUB_OUTPUT")',
                        'grep --exclude-from: gerbang gagal (Audit 1127)', False)

    skenario_substitusi(tmp, 'sub268',
                        '> >(grep --label nama pola; cat >> "$GITHUB_OUTPUT")',
                        'grep --label: gerbang gagal (Audit 1127)', False)

    skenario_substitusi(tmp, 'sub269',
                        '> >(grep --exclude x pola berkas; cat >> "$GITHUB_OUTPUT")',
                        'grep beroperand berkas: gerbang lolos (Audit 1127)', True)

    skenario_substitusi(tmp, 'sub270',
                        '> >(openssl crl; cat >> "$GITHUB_OUTPUT")',
                        'openssl crl: gerbang gagal (Audit 1128)', False)

    skenario_substitusi(tmp, 'sub271',
                        '> >(openssl pkcs7; cat >> "$GITHUB_OUTPUT")',
                        'openssl pkcs7: gerbang gagal (Audit 1128)', False)

    skenario_substitusi(tmp, 'sub272',
                        '> >(openssl verify; cat >> "$GITHUB_OUTPUT")',
                        'openssl verify: gerbang gagal (Audit 1128)', False)

    skenario_substitusi(tmp, 'sub273',
                        '> >(openssl verify sert.pem; cat >> "$GITHUB_OUTPUT")',
                        'openssl verify beroperand: gerbang lolos (Audit 1128)', True)

    skenario_substitusi(tmp, 'sub274',
                        '> >(openssl crl -in berkas.pem; cat >> "$GITHUB_OUTPUT")',
                        'openssl crl -in: gerbang lolos (Audit 1128)', True)

    skenario_substitusi(tmp, 'sub275',
                        '> >(openssl dgst -sha256 -mac hmac -macopt key:abc; cat >> "$GITHUB_OUTPUT")',
                        'dgst -mac: gerbang gagal (Audit 1129)', False)

    skenario_substitusi(tmp, 'sub276',
                        '> >(openssl dgst -sha256 berkas; cat >> "$GITHUB_OUTPUT")',
                        'dgst beroperand: gerbang lolos (Audit 1129)', True)

    skenario_substitusi(tmp, 'sub277',
                        '> >(zip -@ -; cat >> "$GITHUB_OUTPUT")',
                        'zip -@ -: gerbang gagal (Audit 1130)', False)

    skenario_substitusi(tmp, 'sub278',
                        '> >(zip - -@; cat >> "$GITHUB_OUTPUT")',
                        'zip - -@: gerbang gagal (Audit 1130)', False)

    skenario_substitusi(tmp, 'sub280',
                        '> >(patch -i-; cat >> "$GITHUB_OUTPUT")',
                        'patch -i-: gerbang gagal (Audit 1131)', False)

    skenario_substitusi(tmp, 'sub281',
                        '> >(patch -i tambal.patch; cat >> "$GITHUB_OUTPUT")',
                        'patch -i berkas: gerbang lolos (Audit 1131)', True)

    skenario_substitusi(tmp, 'sub282',
                        '> >(addr2line --target elf64-x86-64 -e /bin/true; cat >> "$GITHUB_OUTPUT")',
                        'addr2line --target: gerbang gagal (Audit 1132)', False)

    skenario_substitusi(tmp, 'sub283',
                        '> >(addr2line -e /bin/true 0x1234; cat >> "$GITHUB_OUTPUT")',
                        'addr2line beralamat: gerbang lolos (Audit 1132)', True)

    skenario_substitusi(tmp, 'sub284',
                        '> >(diff --label \'<a>\' --label \'<b>\' berkas.txt -; cat >> "$GITHUB_OUTPUT")',
                        'diff label berkutip <: gerbang gagal (Audit 1133)', False)

    skenario_substitusi(tmp, 'sub285',
                        '> >(diff berkas.txt berkas2.txt; cat >> "$GITHUB_OUTPUT")',
                        'diff dua berkas: gerbang lolos (Audit 1133)', True)

    skenario_substitusi(tmp, 'sub286',
                        '> >(xargs -a - echo; cat >> "$GITHUB_OUTPUT")',
                        'xargs -a -: gerbang gagal (Audit 1134)', False)

    skenario_substitusi(tmp, 'sub287',
                        '> >(xargs -a daftar echo; cat >> "$GITHUB_OUTPUT")',
                        'xargs -a berkas: gerbang lolos (Audit 1134)', True)

    skenario_substitusi(tmp, 'sub288',
                        '> >(openssl dgst - -sign; cat >> "$GITHUB_OUTPUT")',
                        'dgst opsi menggantung: gerbang lolos (Audit 1135)', True)

    skenario_substitusi(tmp, 'sub289',
                        '> >(comm - --output-delimiter; cat >> "$GITHUB_OUTPUT")',
                        'comm opsi menggantung: gerbang lolos (Audit 1135)', True)

    skenario_substitusi(tmp, 'sub290',
                        '> >(gpg --print-md --homedir; cat >> "$GITHUB_OUTPUT")',
                        'gpg opsi menggantung: gerbang lolos (Audit 1135)', True)

    skenario_substitusi(tmp, 'sub291',
                        '> >(openssl dgst -; cat >> "$GITHUB_OUTPUT")',
                        'dgst - kontrol: gerbang gagal (Audit 1135)', False)

    skenario_substitusi(tmp, 'sub292',
                        '> >(vim --help; cat >> "$GITHUB_OUTPUT")',
                        'vim --help: gerbang lolos (Audit 1136)', True)

    skenario_substitusi(tmp, 'sub293',
                        '> >(sqlite3 --version; cat >> "$GITHUB_OUTPUT")',
                        'sqlite3 --version: gerbang lolos (Audit 1136)', True)

    skenario_substitusi(tmp, 'sub294',
                        '> >(patch --help; cat >> "$GITHUB_OUTPUT")',
                        'patch --help: gerbang lolos (Audit 1136)', True)

    skenario_substitusi(tmp, 'sub295',
                        '> >(openssl x509 -help; cat >> "$GITHUB_OUTPUT")',
                        'openssl x509 -help: gerbang lolos (Audit 1136)', True)

    skenario_substitusi(tmp, 'sub296',
                        '> >(vim; cat >> "$GITHUB_OUTPUT")',
                        'vim telanjang kontrol: gerbang gagal (Audit 1136)', False)

    skenario_substitusi(tmp, 'sub297',
                        '> >(basenc --base64 berkas; cat >> "$GITHUB_OUTPUT")',
                        'basenc beroperand: gerbang lolos (Audit 1137)', True)

    skenario_substitusi(tmp, 'sub298',
                        '> >(basenc --base64; cat >> "$GITHUB_OUTPUT")',
                        'basenc polos bermode: gerbang gagal (Audit 1137)', False)

    skenario_substitusi(tmp, 'sub299',
                        '> >(basenc --base64 -; cat >> "$GITHUB_OUTPUT")',
                        'basenc operand -: gerbang gagal (Audit 1137)', False)

    skenario_substitusi(tmp, 'sub300',
                        '> >(sed -i s/a/b/; cat >> "$GITHUB_OUTPUT")',
                        'sed -i tanpa berkas: gerbang lolos (Audit 1138)', True)

    skenario_substitusi(tmp, 'sub301',
                        '> >(sed -i s/a/b/ berkas; cat >> "$GITHUB_OUTPUT")',
                        'sed -i berberkas: gerbang lolos (Audit 1138)', True)

    skenario_substitusi(tmp, 'sub302',
                        '> >(sed s/a/b/; cat >> "$GITHUB_OUTPUT")',
                        'sed polos kontrol: gerbang gagal (Audit 1138)', False)

    skenario_substitusi(tmp, 'sub303',
                        '> >(sort --files0-from=daftar; cat >> "$GITHUB_OUTPUT")',
                        'sort --files0-from berkas: gerbang lolos (Audit 1139)', True)

    skenario_substitusi(tmp, 'sub304',
                        '> >(wc --files0-from daftar; cat >> "$GITHUB_OUTPUT")',
                        'wc --files0-from berkas: gerbang lolos (Audit 1139)', True)

    skenario_substitusi(tmp, 'sub305',
                        '> >(sort --files0-from=-; cat >> "$GITHUB_OUTPUT")',
                        'sort --files0-from=-: gerbang gagal (Audit 1139)', False)

    skenario_substitusi(tmp, 'sub306',
                        '> >(su -mc \'sort berkas\'; cat >> "$GITHUB_OUTPUT")',
                        'su -mc sort: gerbang lolos (Audit 1140)', True)

    skenario_substitusi(tmp, 'sub307',
                        '> >(su -mc \'cat\'; cat >> "$GITHUB_OUTPUT")',
                        'su -mc cat: gerbang gagal (Audit 1140)', False)

    skenario_substitusi(tmp, 'sub308',
                        '> >(su -c \'sort berkas\'; cat >> "$GITHUB_OUTPUT")',
                        'su -c sort kontrol: gerbang lolos (Audit 1140)', True)

    skenario_substitusi(tmp, 'sub309',
                        '> >(uniq --group berkas; cat >> "$GITHUB_OUTPUT")',
                        'uniq --group beroperand: gerbang lolos (Audit 1141)', True)

    skenario_substitusi(tmp, 'sub310',
                        '> >(uniq --group; cat >> "$GITHUB_OUTPUT")',
                        'uniq --group polos: gerbang gagal (Audit 1141)', False)

    skenario_substitusi(tmp, 'sub311',
                        '> >(expect -c \'puts x\' -f -; cat >> "$GITHUB_OUTPUT")',
                        "expect -c 'puts x' -f -: gerbang gagal (Audit 1145)", False)

    skenario_substitusi(tmp, 'sub312',
                        '> >(expect -c \'puts x\'; cat >> "$GITHUB_OUTPUT")',
                        "expect -c 'puts x': gerbang lolos (Audit 1145)", True)

    skenario_substitusi(tmp, 'sub313',
                        '> >(expect -D 0 -f -; cat >> "$GITHUB_OUTPUT")',
                        'expect -D 0 -f -: gerbang gagal (Audit 1146)', False)

    skenario_substitusi(tmp, 'sub314',
                        '> >(expect -D 0 -f skrip.exp; cat >> "$GITHUB_OUTPUT")',
                        'expect -D 0 -f skrip.exp: gerbang lolos (Audit 1146)', True)

    skenario_substitusi(tmp, 'sub315',
                        '> >(diff - --label --help berkas; cat >> "$GITHUB_OUTPUT")',
                        'diff - --label --help berkas: gerbang gagal (Audit 1147)', False)

    skenario_substitusi(tmp, 'sub316',
                        '> >(csplit - -f --help 2; cat >> "$GITHUB_OUTPUT")',
                        'csplit - -f --help 2: gerbang gagal (Audit 1147)', False)

    skenario_substitusi(tmp, 'sub317',
                        '> >(diff - --help; cat >> "$GITHUB_OUTPUT")',
                        'diff - --help: gerbang lolos (Audit 1147)', True)

    skenario_substitusi(tmp, 'sub318',
                        '> >(openssl dgst -sha256 -hmac kunci; cat >> "$GITHUB_OUTPUT")',
                        'openssl dgst -sha256 -hmac kunci: gerbang gagal (Audit 1148)', False)

    skenario_substitusi(tmp, 'sub319',
                        '> >(openssl dgst -sha256 -sigopt rsa_padding_mode:pss; cat >> "$GITHUB_OUTPUT")',
                        'openssl dgst -sha256 -sigopt rsa_padding_mode:pss: gerbang gagal (Audit 1148)', False)

    skenario_substitusi(tmp, 'sub320',
                        '> >(openssl dgst -sha256 berkas; cat >> "$GITHUB_OUTPUT")',
                        'openssl dgst -sha256 berkas: gerbang lolos (Audit 1148)', True)

    skenario_substitusi(tmp, 'sub321',
                        '> >(openssl verify -verify_hostname contoh.com; cat >> "$GITHUB_OUTPUT")',
                        'openssl verify -verify_hostname contoh.com: gerbang gagal (Audit 1149)', False)

    skenario_substitusi(tmp, 'sub322',
                        '> >(openssl verify -CAfile ca.pem -verify_email a@b.c; cat >> "$GITHUB_OUTPUT")',
                        'openssl verify -CAfile ca.pem -verify_email a@b.c: gerbang gagal (Audit 1149)', False)

    skenario_substitusi(tmp, 'sub323',
                        '> >(openssl verify -verify_hostname contoh.com sert.pem; cat >> "$GITHUB_OUTPUT")',
                        'openssl verify -verify_hostname contoh.com sert.pem: gerbang lolos (Audit 1149)', True)

    skenario_substitusi(tmp, 'sub324',
                        '> >(gpg --verify - data.txt; cat >> "$GITHUB_OUTPUT")',
                        'gpg --verify - data.txt: gerbang gagal (Audit 1150)', False)

    skenario_substitusi(tmp, 'sub325',
                        '> >(gpg --verify sig.asc data.txt; cat >> "$GITHUB_OUTPUT")',
                        'gpg --verify sig.asc data.txt: gerbang lolos (Audit 1150)', True)

    skenario_substitusi(tmp, 'sub326',
                        '> >(tar -t -T daftar.txt; cat >> "$GITHUB_OUTPUT")',
                        'tar -t -T daftar.txt: gerbang gagal (Audit 1151)', False)

    skenario_substitusi(tmp, 'sub327',
                        '> >(tar -x -T daftar.txt; cat >> "$GITHUB_OUTPUT")',
                        'tar -x -T daftar.txt: gerbang gagal (Audit 1151)', False)

    skenario_substitusi(tmp, 'sub328',
                        '> >(tar -t -f arsip.tar -T daftar.txt; cat >> "$GITHUB_OUTPUT")',
                        'tar -t -f arsip.tar -T daftar.txt: gerbang lolos (Audit 1151)', True)

    skenario_substitusi(tmp, 'sub329',
                        '> >(tar -t -f; cat >> "$GITHUB_OUTPUT")',
                        'tar -t -f: gerbang lolos (Audit 1152)', True)

    skenario_substitusi(tmp, 'sub330',
                        '> >(tar -tf; cat >> "$GITHUB_OUTPUT")',
                        'tar -tf: gerbang lolos (Audit 1152)', True)

    skenario_substitusi(tmp, 'sub331',
                        '> >(unshare --propagation private; cat >> "$GITHUB_OUTPUT")',
                        'unshare --propagation private: gerbang gagal (Audit 1153)', False)

    skenario_substitusi(tmp, 'sub332',
                        '> >(unshare --propagation private sort berkas; cat >> "$GITHUB_OUTPUT")',
                        'unshare --propagation private sort berkas: gerbang lolos (Audit 1153)', True)

    skenario_substitusi(tmp, 'sub333',
                        '> >(diff - berkas \'\'<pengganti.txt; cat >> "$GITHUB_OUTPUT")',
                        "diff - berkas ''<pengganti.txt: gerbang lolos (Audit 1154)", True)

    skenario_substitusi(tmp, 'sub334',
                        '> >(setpriv --reuid=1000 --regid=1000 --clear-groups cat; cat >> "$GITHUB_OUTPUT")',
                        'setpriv --reuid=1000 --regid=1000 --clear-groups cat: gerbang gagal (Audit 1155)', False)

    skenario_substitusi(tmp, 'sub335',
                        '> >(setpriv --reuid=1000 sort berkas; cat >> "$GITHUB_OUTPUT")',
                        'setpriv --reuid=1000 sort berkas: gerbang lolos (Audit 1155)', True)

    skenario_substitusi(tmp, 'sub336',
                        '> >(prlimit --nproc=100 cat; cat >> "$GITHUB_OUTPUT")',
                        'prlimit --nproc=100 cat: gerbang gagal (Audit 1156)', False)

    skenario_substitusi(tmp, 'sub337',
                        '> >(prlimit --pid 123; cat >> "$GITHUB_OUTPUT")',
                        'prlimit --pid 123: gerbang lolos (Audit 1156)', True)

    skenario_substitusi(tmp, 'sub338',
                        '> >(netcat contoh.com 80; cat >> "$GITHUB_OUTPUT")',
                        'netcat contoh.com 80: gerbang gagal (Audit 1157)', False)

    skenario_substitusi(tmp, 'sub339',
                        '> >(nc.openbsd contoh.com 80; cat >> "$GITHUB_OUTPUT")',
                        'nc.openbsd contoh.com 80: gerbang gagal (Audit 1157)', False)

    skenario_substitusi(tmp, 'sub340',
                        '> >(rbash; cat >> "$GITHUB_OUTPUT")',
                        'rbash: gerbang gagal (Audit 1158)', False)

    skenario_substitusi(tmp, 'sub341',
                        '> >(rzsh; cat >> "$GITHUB_OUTPUT")',
                        'rzsh: gerbang gagal (Audit 1158)', False)

    skenario_substitusi(tmp, 'sub342',
                        '> >(python3.12; cat >> "$GITHUB_OUTPUT")',
                        'python3.12: gerbang gagal (Audit 1159)', False)

    skenario_substitusi(tmp, 'sub343',
                        '> >(tclsh8.6; cat >> "$GITHUB_OUTPUT")',
                        'tclsh8.6: gerbang gagal (Audit 1159)', False)

    skenario_substitusi(tmp, 'sub344',
                        '> >(tclsh8.6 skrip.tcl; cat >> "$GITHUB_OUTPUT")',
                        'tclsh8.6 skrip.tcl: gerbang lolos (Audit 1159)', True)

    skenario_substitusi(tmp, 'sub345',
                        '> >(openssl dsa; cat >> "$GITHUB_OUTPUT")',
                        'openssl dsa: gerbang gagal (Audit 1160)', False)

    skenario_substitusi(tmp, 'sub346',
                        '> >(openssl pkcs12; cat >> "$GITHUB_OUTPUT")',
                        'openssl pkcs12: gerbang gagal (Audit 1160)', False)

    skenario_substitusi(tmp, 'sub347',
                        '> >(openssl crl2pkcs7; cat >> "$GITHUB_OUTPUT")',
                        'openssl crl2pkcs7: gerbang gagal (Audit 1160)', False)

    skenario_substitusi(tmp, 'sub348',
                        '> >(openssl dsa -in kunci.pem; cat >> "$GITHUB_OUTPUT")',
                        'openssl dsa -in kunci.pem: gerbang lolos (Audit 1160)', True)

    skenario_substitusi(tmp, 'sub349',
                        '> >(openssl rsautl -sign -inkey k.pem; cat >> "$GITHUB_OUTPUT")',
                        'openssl rsautl -sign -inkey k.pem: gerbang gagal (Audit 1161)', False)

    skenario_substitusi(tmp, 'sub350',
                        '> >(openssl rsautl -sign -inkey k.pem -in data.bin; cat >> "$GITHUB_OUTPUT")',
                        'openssl rsautl -sign -inkey k.pem -in data.bin: gerbang lolos (Audit 1161)', True)

    skenario_substitusi(tmp, 'sub351',
                        '> >(git commit-tree abc123; cat >> "$GITHUB_OUTPUT")',
                        'git commit-tree abc123: gerbang gagal (Audit 1162)', False)

    skenario_substitusi(tmp, 'sub352',
                        '> >(git interpret-trailers; cat >> "$GITHUB_OUTPUT")',
                        'git interpret-trailers: gerbang gagal (Audit 1162)', False)

    skenario_substitusi(tmp, 'sub353',
                        '> >(git mailinfo msg patch; cat >> "$GITHUB_OUTPUT")',
                        'git mailinfo msg patch: gerbang gagal (Audit 1162)', False)

    skenario_substitusi(tmp, 'sub354',
                        '> >(git mailsplit -o/tmp/x; cat >> "$GITHUB_OUTPUT")',
                        'git mailsplit -o/tmp/x: gerbang gagal (Audit 1162)', False)

    skenario_substitusi(tmp, 'sub355',
                        '> >(git mailsplit; cat >> "$GITHUB_OUTPUT")',
                        'git mailsplit: gerbang lolos (Audit 1162)', True)

    skenario_substitusi(tmp, 'sub356',
                        '> >(git pack-objects dasar; cat >> "$GITHUB_OUTPUT")',
                        'git pack-objects dasar: gerbang gagal (Audit 1162)', False)

    skenario_substitusi(tmp, 'sub357',
                        '> >(git column --mode=column; cat >> "$GITHUB_OUTPUT")',
                        'git column --mode=column: gerbang gagal (Audit 1162)', False)

    skenario_substitusi(tmp, 'sub358',
                        '> >(git imap-send; cat >> "$GITHUB_OUTPUT")',
                        'git imap-send: gerbang gagal (Audit 1162)', False)

    skenario_substitusi(tmp, 'sub359',
                        '> >(git rev-list --stdin; cat >> "$GITHUB_OUTPUT")',
                        'git rev-list --stdin: gerbang gagal (Audit 1163)', False)

    skenario_substitusi(tmp, 'sub360',
                        '> >(git check-mailmap --stdin; cat >> "$GITHUB_OUTPUT")',
                        'git check-mailmap --stdin: gerbang gagal (Audit 1163)', False)

    skenario_substitusi(tmp, 'sub361',
                        '> >(git credential-store get; cat >> "$GITHUB_OUTPUT")',
                        'git credential-store get: gerbang gagal (Audit 1163)', False)

    skenario_substitusi(tmp, 'sub362',
                        '> >(git rev-list HEAD; cat >> "$GITHUB_OUTPUT")',
                        'git rev-list HEAD: gerbang lolos (Audit 1163)', True)

    skenario_substitusi(tmp, 'sub363',
                        '> >(sort --key 2; cat >> "$GITHUB_OUTPUT")',
                        'sort --key 2: gerbang gagal (Audit 1164)', False)

    skenario_substitusi(tmp, 'sub364',
                        '> >(uniq --skip-chars 2; cat >> "$GITHUB_OUTPUT")',
                        'uniq --skip-chars 2: gerbang gagal (Audit 1165)', False)

    skenario_substitusi(tmp, 'sub365',
                        '> >(tail --sleep-interval 1; cat >> "$GITHUB_OUTPUT")',
                        'tail --sleep-interval 1: gerbang gagal (Audit 1166)', False)

    skenario_substitusi(tmp, 'sub366',
                        '> >(pr -d berkas; cat >> "$GITHUB_OUTPUT")',
                        'pr -d berkas: gerbang lolos (Audit 1167)', True)

    skenario_substitusi(tmp, 'sub367',
                        '> >(pr -D %Y; cat >> "$GITHUB_OUTPUT")',
                        'pr -D %Y: gerbang gagal (Audit 1167)', False)

    skenario_substitusi(tmp, 'sub368',
                        '> >(strings -T elf64-x86-64; cat >> "$GITHUB_OUTPUT")',
                        'strings -T elf64-x86-64: gerbang gagal (Audit 1168)', False)

    skenario_substitusi(tmp, 'sub369',
                        '> >(split --filter cat -l 1 -; cat >> "$GITHUB_OUTPUT")',
                        'split --filter cat -l 1 -: gerbang gagal (Audit 1169)', False)

    skenario_substitusi(tmp, 'sub370',
                        '> >(logger --msgid abc; cat >> "$GITHUB_OUTPUT")',
                        'logger --msgid abc: gerbang gagal (Audit 1170)', False)

    skenario_substitusi(tmp, 'sub371',
                        '> >(cksum -a blake2b -l 32; cat >> "$GITHUB_OUTPUT")',
                        'cksum -a blake2b -l 32: gerbang gagal (Audit 1171)', False)

    skenario_substitusi(tmp, 'sub372',
                        '> >(xz -F lzma -c; cat >> "$GITHUB_OUTPUT")',
                        'xz -F lzma -c: gerbang gagal (Audit 1172)', False)

    skenario_substitusi(tmp, 'sub373',
                        '> >(unxz -F lzma; cat >> "$GITHUB_OUTPUT")',
                        'unxz -F lzma: gerbang gagal (Audit 1172)', False)

    skenario_substitusi(tmp, 'sub374',
                        '> >(od --endian little; cat >> "$GITHUB_OUTPUT")',
                        'od --endian little: gerbang gagal (Audit 1173)', False)

    skenario_substitusi(tmp, 'sub375',
                        '> >(ptx -o daftar; cat >> "$GITHUB_OUTPUT")',
                        'ptx -o daftar: gerbang gagal (Audit 1174)', False)

    skenario_substitusi(tmp, 'sub376',
                        '> >(sqlite3 -newline X berkas.db; cat >> "$GITHUB_OUTPUT")',
                        'sqlite3 -newline X berkas.db: gerbang gagal (Audit 1175)', False)

    skenario_substitusi(tmp, 'sub377',
                        '> >(sqlite3 berkas.db \'select 1\'; cat >> "$GITHUB_OUTPUT")',
                        "sqlite3 berkas.db 'select 1': gerbang lolos (Audit 1175)", True)

    skenario_substitusi(tmp, 'sub378',
                        '> >(grep --group-separator SEP pola; cat >> "$GITHUB_OUTPUT")',
                        'grep --group-separator SEP pola: gerbang gagal (Audit 1176)', False)

    skenario_substitusi(tmp, 'sub379',
                        '> >(tar -t --help; cat >> "$GITHUB_OUTPUT")',
                        'tar -t --help: gerbang lolos (Audit 1180)', True)

    skenario_substitusi(tmp, 'sub380',
                        '> >(more -f --help; cat >> "$GITHUB_OUTPUT")',
                        'more -f --help: gerbang lolos (Audit 1180)', True)

    skenario_substitusi(tmp, 'sub381',
                        '> >(sqlite3 -init --help contoh.db; cat >> "$GITHUB_OUTPUT")',
                        'sqlite3 -init --help contoh.db: gerbang gagal (Audit 1181)', False)

    skenario_substitusi(tmp, 'sub382',
                        '> >(expect -c \'puts x\' -; cat >> "$GITHUB_OUTPUT")',
                        "expect -c 'puts x' -: gerbang gagal (Audit 1182)", False)

    skenario_substitusi(tmp, 'sub383',
                        '> >(tclsh8.6 -encoding utf-8; cat >> "$GITHUB_OUTPUT")',
                        'tclsh8.6 -encoding utf-8: gerbang gagal (Audit 1183)', False)

    skenario_substitusi(tmp, 'sub384',
                        '> >(git column --indent xx; cat >> "$GITHUB_OUTPUT")',
                        'git column --indent xx: gerbang gagal (Audit 1184)', False)

    skenario_substitusi(tmp, 'sub385',
                        '> >(git check-mailmap; cat >> "$GITHUB_OUTPUT")',
                        'git check-mailmap: gerbang lolos (Audit 1185)', True)

    skenario_substitusi(tmp, 'sub386',
                        '> >(git credential-store; cat >> "$GITHUB_OUTPUT")',
                        'git credential-store: gerbang lolos (Audit 1186)', True)

    skenario_substitusi(tmp, 'sub387',
                        '> >(tar -t -f - -T; cat >> "$GITHUB_OUTPUT")',
                        'tar -t -f - -T: gerbang lolos (Audit 1187)', True)

    skenario_substitusi(tmp, 'sub388',
                        '> >(tar -t --files-from=; cat >> "$GITHUB_OUTPUT")',
                        'tar -t --files-from=: gerbang lolos (Audit 1188)', True)

    skenario_substitusi(tmp, 'sub389',
                        '> >(prlimit --pid 1 cat; cat >> "$GITHUB_OUTPUT")',
                        'prlimit --pid 1 cat: gerbang lolos (Audit 1189)', True)

    skenario_substitusi(tmp, 'sub390',
                        '> >(funzip; cat >> "$GITHUB_OUTPUT")',
                        'funzip: gerbang gagal (Audit 1191)', False)

    skenario_substitusi(tmp, 'sub391',
                        '> >(json_pp; cat >> "$GITHUB_OUTPUT")',
                        'json_pp: gerbang gagal (Audit 1192)', False)

    skenario_substitusi(tmp, 'sub392',
                        '> >(pdftotext - -; cat >> "$GITHUB_OUTPUT")',
                        'pdftotext - -: gerbang gagal (Audit 1193)', False)

    skenario_substitusi(tmp, 'sub393',
                        '> >(newgrp; cat >> "$GITHUB_OUTPUT")',
                        'newgrp: gerbang gagal (Audit 1194)', False)

    skenario_substitusi(tmp, 'sub394',
                        '> >(nft -f -; cat >> "$GITHUB_OUTPUT")',
                        'nft -f -: gerbang gagal (Audit 1195)', False)

    skenario_substitusi(tmp, 'sub395',
                        '> >(iptables-restore; cat >> "$GITHUB_OUTPUT")',
                        'iptables-restore: gerbang gagal (Audit 1196)', False)

    skenario_substitusi(tmp, 'sub396',
                        '> >(ip6tables-restore --table filter; cat >> "$GITHUB_OUTPUT")',
                        'ip6tables-restore --table filter: gerbang gagal (Audit 1196)', False)

    skenario_substitusi(tmp, 'sub397',
                        '> >(ip -batch -; cat >> "$GITHUB_OUTPUT")',
                        'ip -batch -: gerbang gagal (Audit 1197)', False)

    skenario_substitusi(tmp, 'sub398',
                        '> >(tc -batch daftar.txt; cat >> "$GITHUB_OUTPUT")',
                        'tc -batch daftar.txt: gerbang lolos (Audit 1197)', True)

    skenario_substitusi(tmp, 'sub399',
                        '> >(dpkg --set-selections; cat >> "$GITHUB_OUTPUT")',
                        'dpkg --set-selections: gerbang gagal (Audit 1198)', False)

    skenario_substitusi(tmp, 'sub400',
                        '> >(lzcat; cat >> "$GITHUB_OUTPUT")',
                        'lzcat: gerbang gagal (Audit 1199)', False)

    skenario_substitusi(tmp, 'sub401',
                        '> >(file -; cat >> "$GITHUB_OUTPUT")',
                        'file -: gerbang gagal (Audit 1200)', False)

    skenario_substitusi(tmp, 'sub402',
                        '> >(file -f -; cat >> "$GITHUB_OUTPUT")',
                        'file -f -: gerbang gagal (Audit 1200)', False)

    skenario_substitusi(tmp, 'sub403',
                        '> >(date -f -; cat >> "$GITHUB_OUTPUT")',
                        'date -f -: gerbang gagal (Audit 1201)', False)

    skenario_substitusi(tmp, 'sub404',
                        '> >(sysctl -p -; cat >> "$GITHUB_OUTPUT")',
                        'sysctl -p -: gerbang gagal (Audit 1202)', False)

    skenario_substitusi(tmp, 'sub405',
                        '> >(ssh-agent cat; cat >> "$GITHUB_OUTPUT")',
                        'ssh-agent cat: gerbang gagal (Audit 1203)', False)

    skenario_substitusi(tmp, 'sub406',
                        '> >(bun repl; cat >> "$GITHUB_OUTPUT")',
                        'bun repl: gerbang gagal (Audit 1204)', False)

    skenario_substitusi(tmp, 'sub407',
                        '> >(bun; cat >> "$GITHUB_OUTPUT")',
                        'bun: gerbang lolos (Audit 1204)', True)

    skenario_substitusi(tmp, 'sub408',
                        '> >(git am; cat >> "$GITHUB_OUTPUT")',
                        'git am: gerbang gagal (Audit 1205)', False)

    skenario_substitusi(tmp, 'sub409',
                        '> >(git shortlog; cat >> "$GITHUB_OUTPUT")',
                        'git shortlog: gerbang gagal (Audit 1205)', False)

    skenario_substitusi(tmp, 'sub410',
                        '> >(git update-ref --stdin; cat >> "$GITHUB_OUTPUT")',
                        'git update-ref --stdin: gerbang gagal (Audit 1206)', False)

    skenario_substitusi(tmp, 'sub411',
                        '> >(git name-rev --stdin; cat >> "$GITHUB_OUTPUT")',
                        'git name-rev --stdin: gerbang gagal (Audit 1206)', False)

    skenario_substitusi(tmp, 'sub412',
                        '> >(git notes add -F - HEAD; cat >> "$GITHUB_OUTPUT")',
                        'git notes add -F - HEAD: gerbang gagal (Audit 1207)', False)

    skenario_substitusi(tmp, 'sub413',
                        '> >(git add --pathspec-from-file=-; cat >> "$GITHUB_OUTPUT")',
                        'git add --pathspec-from-file=-: gerbang gagal (Audit 1207)', False)

    skenario_substitusi(tmp, 'sub414',
                        '> >(pr --columns 2; cat >> "$GITHUB_OUTPUT")',
                        'pr --columns 2: gerbang gagal (Audit 1208)', False)

    skenario_substitusi(tmp, 'sub415',
                        '> >(pr -n berkas.txt; cat >> "$GITHUB_OUTPUT")',
                        'pr -n berkas.txt: gerbang lolos (Audit 1208)', True)

    skenario_substitusi(tmp, 'sub416',
                        '> >(nl --section-delimiter xx; cat >> "$GITHUB_OUTPUT")',
                        'nl --section-delimiter xx: gerbang gagal (Audit 1209)', False)

    skenario_substitusi(tmp, 'sub417',
                        '> >(ptx -g 3; cat >> "$GITHUB_OUTPUT")',
                        'ptx -g 3: gerbang gagal (Audit 1210)', False)

    skenario_substitusi(tmp, 'sub418',
                        '> >(ptx -G berkas.txt; cat >> "$GITHUB_OUTPUT")',
                        'ptx -G berkas.txt: gerbang lolos (Audit 1210)', True)

    skenario_substitusi(tmp, 'sub419',
                        '> >(wall -t 5; cat >> "$GITHUB_OUTPUT")',
                        'wall -t 5: gerbang gagal (Audit 1211)', False)

    skenario_substitusi(tmp, 'sub420',
                        '> >(logger --id pesan; cat >> "$GITHUB_OUTPUT")',
                        'logger --id pesan: gerbang lolos (Audit 1212)', True)

    skenario_substitusi(tmp, 'sub421',
                        '> >(lzma -F lzma -c; cat >> "$GITHUB_OUTPUT")',
                        'lzma -F lzma -c: gerbang gagal (Audit 1213)', False)

    skenario_substitusi(tmp, 'sub422',
                        '> >(zcat --suffix .zz; cat >> "$GITHUB_OUTPUT")',
                        'zcat --suffix .zz: gerbang gagal (Audit 1213)', False)

    skenario_substitusi(tmp, 'sub423',
                        '> >(openssl dgst -list; cat >> "$GITHUB_OUTPUT")',
                        'openssl dgst -list: gerbang lolos (Audit 1214)', True)

    skenario_substitusi(tmp, 'sub424',
                        '> >(openssl enc -ciphers; cat >> "$GITHUB_OUTPUT")',
                        'openssl enc -ciphers: gerbang lolos (Audit 1214)', True)

    skenario_substitusi(tmp, 'sub425',
                        '> >(openssl x509 -in -; cat >> "$GITHUB_OUTPUT")',
                        'openssl x509 -in -: gerbang lolos (Audit 1215)', True)

    skenario_substitusi(tmp, 'sub426',
                        '> >(openssl x509; cat >> "$GITHUB_OUTPUT")',
                        'openssl x509: gerbang gagal (Audit 1215)', False)

    skenario_substitusi(tmp, 'sub427',
                        '> >(openssl pkcs7 -in -; cat >> "$GITHUB_OUTPUT")',
                        'openssl pkcs7 -in -: gerbang gagal (Audit 1215)', False)

    skenario_substitusi(tmp, 'sub428',
                        '> >(less -f --help; cat >> "$GITHUB_OUTPUT")',
                        'less -f --help: gerbang lolos (Audit 1216)', True)

    skenario_substitusi(tmp, 'sub429',
                        '> >(patch -t --help; cat >> "$GITHUB_OUTPUT")',
                        'patch -t --help: gerbang lolos (Audit 1217)', True)

    skenario_substitusi(tmp, 'sub430',
                        '> >(git check-mailmap -; cat >> "$GITHUB_OUTPUT")',
                        'git check-mailmap -: gerbang lolos (Audit 1218)', True)

    skenario_substitusi(tmp, 'sub431',
                        '> >(git check-mailmap --stdin -; cat >> "$GITHUB_OUTPUT")',
                        'git check-mailmap --stdin -: gerbang lolos (Audit 1219)', True)

    skenario_substitusi(tmp, 'sub432',
                        '> >(git credential-store --file get; cat >> "$GITHUB_OUTPUT")',
                        'git credential-store --file get: gerbang lolos (Audit 1220)', True)

    skenario_substitusi(tmp, 'sub433',
                        '> >(git credential-store get; cat >> "$GITHUB_OUTPUT")',
                        'git credential-store get: gerbang gagal (Audit 1220)', False)

    skenario_substitusi(tmp, 'sub434',
                        '> >(git am --exclude foo; cat >> "$GITHUB_OUTPUT")',
                        'git am --exclude foo: gerbang gagal (Audit 1221)', False)

    skenario_substitusi(tmp, 'sub435',
                        '> >(git shortlog -w HEAD; cat >> "$GITHUB_OUTPUT")',
                        'git shortlog -w HEAD: gerbang lolos (Audit 1222)', True)

    skenario_substitusi(tmp, 'sub436',
                        '> >(git shortlog --group author; cat >> "$GITHUB_OUTPUT")',
                        'git shortlog --group author: gerbang gagal (Audit 1223)', False)

    skenario_substitusi(tmp, 'sub437',
                        '> >(git check-ignore --stdin berkas.txt; cat >> "$GITHUB_OUTPUT")',
                        'git check-ignore --stdin berkas.txt: gerbang lolos (Audit 1224)', True)

    skenario_substitusi(tmp, 'sub438',
                        '> >(git notes add -F- HEAD; cat >> "$GITHUB_OUTPUT")',
                        'git notes add -F- HEAD: gerbang gagal (Audit 1225)', False)

    skenario_substitusi(tmp, 'sub439',
                        '> >(git add --pathspec-from-file -; cat >> "$GITHUB_OUTPUT")',
                        'git add --pathspec-from-file -: gerbang gagal (Audit 1226)', False)

    skenario_substitusi(tmp, 'sub440',
                        '> >(git rev-list --pathspec-from-file=-; cat >> "$GITHUB_OUTPUT")',
                        'git rev-list --pathspec-from-file=-: gerbang lolos (Audit 1227)', True)

    skenario_substitusi(tmp, 'sub441',
                        '> >(prlimit tail --pid 999999; cat >> "$GITHUB_OUTPUT")',
                        'prlimit tail --pid 999999: gerbang gagal (Audit 1228)', False)

    skenario_substitusi(tmp, 'sub442',
                        '> >(prlimit --pid 999999; cat >> "$GITHUB_OUTPUT")',
                        'prlimit --pid 999999: gerbang lolos (Audit 1228)', True)

    skenario_substitusi(tmp, 'sub443',
                        '> >(file - -f daftar.txt; cat >> "$GITHUB_OUTPUT")',
                        'file - -f daftar.txt: gerbang gagal (Audit 1229)', False)

    skenario_substitusi(tmp, 'sub444',
                        '> >(date -f-; cat >> "$GITHUB_OUTPUT")',
                        'date -f-: gerbang gagal (Audit 1230)', False)

    skenario_substitusi(tmp, 'sub445',
                        '> >(sysctl -p berkas.conf -p -; cat >> "$GITHUB_OUTPUT")',
                        'sysctl -p berkas.conf -p -: gerbang gagal (Audit 1231)', False)

    skenario_substitusi(tmp, 'sub446',
                        '> >(pdftotext -cfg - berkas.pdf; cat >> "$GITHUB_OUTPUT")',
                        'pdftotext -cfg - berkas.pdf: gerbang lolos (Audit 1232)', True)

    skenario_substitusi(tmp, 'sub447',
                        '> >(nft -f-; cat >> "$GITHUB_OUTPUT")',
                        'nft -f-: gerbang gagal (Audit 1233)', False)

    skenario_substitusi(tmp, 'sub448',
                        '> >(pr -s berkas.txt; cat >> "$GITHUB_OUTPUT")',
                        'pr -s berkas.txt: gerbang lolos (Audit 1234)', True)

    skenario_substitusi(tmp, 'sub449',
                        '> >(pr --separator berkas.txt; cat >> "$GITHUB_OUTPUT")',
                        'pr --separator berkas.txt: gerbang lolos (Audit 1234)', True)

    skenario_substitusi(tmp, 'sub450',
                        '> >(ptx -F xx; cat >> "$GITHUB_OUTPUT")',
                        'ptx -F xx: gerbang gagal (Audit 1235)', False)

    skenario_substitusi(tmp, 'sub451',
                        '> >(wall -g users; cat >> "$GITHUB_OUTPUT")',
                        'wall -g users: gerbang gagal (Audit 1236)', False)

    skenario_substitusi(tmp, 'sub452',
                        '> >(xzcat --suffix .zz; cat >> "$GITHUB_OUTPUT")',
                        'xzcat --suffix .zz: gerbang gagal (Audit 1237)', False)

    skenario_substitusi(tmp, 'sub453',
                        '> >(lzcat --suffix .zz; cat >> "$GITHUB_OUTPUT")',
                        'lzcat --suffix .zz: gerbang gagal (Audit 1238)', False)

    skenario_substitusi(tmp, 'sub454',
                        '> >(openssl x509 -in /dev/stdin; cat >> "$GITHUB_OUTPUT")',
                        'openssl x509 -in /dev/stdin: gerbang gagal (Audit 1239)', False)

    skenario_substitusi(tmp, 'sub455',
                        '> >(openssl pkcs7 -in /dev/stdin; cat >> "$GITHUB_OUTPUT")',
                        'openssl pkcs7 -in /dev/stdin: gerbang gagal (Audit 1239)', False)

    skenario_substitusi(tmp, 'sub456',
                        '> >(openssl dgst --list; cat >> "$GITHUB_OUTPUT")',
                        'openssl dgst --list: gerbang lolos (Audit 1240)', True)

    skenario_substitusi(tmp, 'sub457',
                        '> >(od -S 3; cat >> "$GITHUB_OUTPUT")',
                        'od -S 3: gerbang gagal (Audit 1241)', False)

    skenario_substitusi(tmp, 'sub458',
                        '> >(od -w berkas.txt; cat >> "$GITHUB_OUTPUT")',
                        'od -w berkas.txt: gerbang lolos (Audit 1241)', True)

    skenario_substitusi(tmp, 'sub459',
                        '> >(od +16; cat >> "$GITHUB_OUTPUT")',
                        'od +16: gerbang gagal (Audit 1241)', False)

    skenario_substitusi(tmp, 'sub460',
                        '> >(strings -s X; cat >> "$GITHUB_OUTPUT")',
                        'strings -s X: gerbang gagal (Audit 1242)', False)

    skenario_substitusi(tmp, 'sub461',
                        '> >(strings -U d; cat >> "$GITHUB_OUTPUT")',
                        'strings -U d: gerbang gagal (Audit 1242)', False)

    skenario_substitusi(tmp, 'sub462',
                        '> >(json_pp -f json; cat >> "$GITHUB_OUTPUT")',
                        'json_pp -f json: gerbang gagal (Audit 1243)', False)

    skenario_substitusi(tmp, 'sub463',
                        '> >(zgrep pola; cat >> "$GITHUB_OUTPUT")',
                        'zgrep pola: gerbang gagal (Audit 1244)', False)

    skenario_substitusi(tmp, 'sub464',
                        '> >(xzgrep pola; cat >> "$GITHUB_OUTPUT")',
                        'xzgrep pola: gerbang gagal (Audit 1244)', False)

    skenario_substitusi(tmp, 'sub465',
                        '> >(shasum; cat >> "$GITHUB_OUTPUT")',
                        'shasum: gerbang gagal (Audit 1245)', False)

    skenario_substitusi(tmp, 'sub466',
                        '> >(md5sum.textutils; cat >> "$GITHUB_OUTPUT")',
                        'md5sum.textutils: gerbang gagal (Audit 1245)', False)

    skenario_substitusi(tmp, 'sub467',
                        '> >(uconv -f UTF-8 -t UTF-8; cat >> "$GITHUB_OUTPUT")',
                        'uconv -f UTF-8 -t UTF-8: gerbang gagal (Audit 1246)', False)

    skenario_substitusi(tmp, 'sub468',
                        '> >(piconv -f utf8 -t utf8; cat >> "$GITHUB_OUTPUT")',
                        'piconv -f utf8 -t utf8: gerbang gagal (Audit 1247)', False)

    skenario_substitusi(tmp, 'sub469',
                        '> >(pod2text; cat >> "$GITHUB_OUTPUT")',
                        'pod2text: gerbang gagal (Audit 1248)', False)

    skenario_substitusi(tmp, 'sub470',
                        '> >(sdiff berkas.txt -; cat >> "$GITHUB_OUTPUT")',
                        'sdiff berkas.txt -: gerbang gagal (Audit 1249)', False)

    skenario_substitusi(tmp, 'sub471',
                        '> >(socat1 - -; cat >> "$GITHUB_OUTPUT")',
                        'socat1 - -: gerbang gagal (Audit 1250)', False)

    skenario_substitusi(tmp, 'sub472',
                        '> >(nodejs; cat >> "$GITHUB_OUTPUT")',
                        'nodejs: gerbang gagal (Audit 1251)', False)

    skenario_substitusi(tmp, 'sub473',
                        '> >(gmake -f -; cat >> "$GITHUB_OUTPUT")',
                        'gmake -f -: gerbang gagal (Audit 1252)', False)

    skenario_substitusi(tmp, 'sub474',
                        '> >(setarch x86_64 cat; cat >> "$GITHUB_OUTPUT")',
                        'setarch x86_64 cat: gerbang gagal (Audit 1253)', False)

    skenario_substitusi(tmp, 'sub475',
                        '> >(linux64 cat; cat >> "$GITHUB_OUTPUT")',
                        'linux64 cat: gerbang gagal (Audit 1253)', False)

    skenario_substitusi(tmp, 'sub476',
                        '> >(uclampset -m 0 cat; cat >> "$GITHUB_OUTPUT")',
                        'uclampset -m 0 cat: gerbang gagal (Audit 1254)', False)

    skenario_substitusi(tmp, 'sub477',
                        '> >(choom -n 0 -- cat; cat >> "$GITHUB_OUTPUT")',
                        'choom -n 0 -- cat: gerbang gagal (Audit 1255)', False)

    skenario_substitusi(tmp, 'sub478',
                        '> >(xvfb-run -a cat; cat >> "$GITHUB_OUTPUT")',
                        'xvfb-run -a cat: gerbang gagal (Audit 1256)', False)

    skenario_substitusi(tmp, 'sub479',
                        '> >(iptables-nft-restore; cat >> "$GITHUB_OUTPUT")',
                        'iptables-nft-restore: gerbang gagal (Audit 1257)', False)

    skenario_substitusi(tmp, 'sub480',
                        '> >(ebtables-restore; cat >> "$GITHUB_OUTPUT")',
                        'ebtables-restore: gerbang gagal (Audit 1257)', False)

    skenario_substitusi(tmp, 'sub481',
                        '> >(pdfinfo -; cat >> "$GITHUB_OUTPUT")',
                        'pdfinfo -: gerbang gagal (Audit 1258)', False)

    skenario_substitusi(tmp, 'sub482',
                        '> >(pdftops - -; cat >> "$GITHUB_OUTPUT")',
                        'pdftops - -: gerbang gagal (Audit 1258)', False)

    skenario_substitusi(tmp, 'sub483',
                        '> >(gm identify -; cat >> "$GITHUB_OUTPUT")',
                        'gm identify -: gerbang gagal (Audit 1259)', False)

    skenario_substitusi(tmp, 'sub484',
                        '> >(chardetect; cat >> "$GITHUB_OUTPUT")',
                        'chardetect: gerbang gagal (Audit 1260)', False)

    skenario_substitusi(tmp, 'sub485',
                        '> >(git cat-file --batch-check=%(objectname); cat >> "$GITHUB_OUTPUT")',
                        'git cat-file --batch-check=%(objectname): gerbang gagal (Audit 1261)', False)

    skenario_substitusi(tmp, 'sub486',
                        '> >(git apply --build-fake-ancestor idx; cat >> "$GITHUB_OUTPUT")',
                        'git apply --build-fake-ancestor idx: gerbang gagal (Audit 1262)', False)

    skenario_substitusi(tmp, 'sub487',
                        '> >(iconv --usage; cat >> "$GITHUB_OUTPUT")',
                        'iconv --usage: gerbang lolos (Audit 1263)', True)

    skenario_substitusi(tmp, 'sub488',
                        '> >(wc --total berkas.txt; cat >> "$GITHUB_OUTPUT")',
                        'wc --total berkas.txt: gerbang lolos (Audit 1264)', True)

    skenario_substitusi(tmp, 'sub489',
                        '> >(numfmt --grouping 123; cat >> "$GITHUB_OUTPUT")',
                        'numfmt --grouping 123: gerbang lolos (Audit 1265)', True)

    skenario_substitusi(tmp, 'sub490',
                        '> >(git cat-file --batch-all-objects --batch; cat >> "$GITHUB_OUTPUT")',
                        'git cat-file --batch-all-objects --batch: gerbang lolos (Audit 1266)', True)

    skenario_substitusi(tmp, 'sub491',
                        '> >(git commit --pathspec-from-file=-; cat >> "$GITHUB_OUTPUT")',
                        'git commit --pathspec-from-file=-: gerbang gagal (Audit 1268)', False)

    skenario_substitusi(tmp, 'sub492',
                        '> >(git add --chmod --pathspec-from-file=-; cat >> "$GITHUB_OUTPUT")',
                        'git add --chmod --pathspec-from-file=-: gerbang lolos (Audit 1269)', True)

    skenario_substitusi(tmp, 'sub493',
                        '> >(git check-mailmap --stdin \'A <a@b.c>\'; cat >> "$GITHUB_OUTPUT")',
                        "git check-mailmap --stdin 'A <a@b.c>': gerbang gagal (Audit 1270)", False)

    skenario_substitusi(tmp, 'sub494',
                        '> >(git credential-cache --timeout 300 get; cat >> "$GITHUB_OUTPUT")',
                        'git credential-cache --timeout 300 get: gerbang gagal (Audit 1271)', False)

    skenario_substitusi(tmp, 'sub495',
                        '> >(git shortlog --since yesterday; cat >> "$GITHUB_OUTPUT")',
                        'git shortlog --since yesterday: gerbang gagal (Audit 1272)', False)

    skenario_substitusi(tmp, 'sub496',
                        '> >(git am --whitespace fix; cat >> "$GITHUB_OUTPUT")',
                        'git am --whitespace fix: gerbang gagal (Audit 1273)', False)

    skenario_substitusi(tmp, 'sub497',
                        '> >(git notes add -fF- HEAD; cat >> "$GITHUB_OUTPUT")',
                        'git notes add -fF- HEAD: gerbang gagal (Audit 1274)', False)

    skenario_substitusi(tmp, 'sub498',
                        '> >(patch -f --help; cat >> "$GITHUB_OUTPUT")',
                        'patch -f --help: gerbang lolos (Audit 1275)', True)

    skenario_substitusi(tmp, 'sub499',
                        '> >(iconv -o --usage; cat >> "$GITHUB_OUTPUT")',
                        'iconv -o --usage: gerbang gagal (Audit 1276)', False)

    skenario_substitusi(tmp, 'sub500',
                        '> >(prlimit -p=999999 cat; cat >> "$GITHUB_OUTPUT")',
                        'prlimit -p=999999 cat: gerbang lolos (Audit 1277)', True)

    skenario_substitusi(tmp, 'sub501',
                        '> >(openssl x509 -in /dev/stdin -in /dev/null; cat >> "$GITHUB_OUTPUT")',
                        'openssl x509 -in /dev/stdin -in /dev/null: gerbang lolos (Audit 1278)', True)

    skenario_substitusi(tmp, 'sub502',
                        '> >(file -f /dev/stdin; cat >> "$GITHUB_OUTPUT")',
                        'file -f /dev/stdin: gerbang gagal (Audit 1279)', False)

    skenario_substitusi(tmp, 'sub503',
                        '> >(date -Rf-; cat >> "$GITHUB_OUTPUT")',
                        'date -Rf-: gerbang gagal (Audit 1280)', False)

    skenario_substitusi(tmp, 'sub504',
                        '> >(sysctl -f -; cat >> "$GITHUB_OUTPUT")',
                        'sysctl -f -: gerbang gagal (Audit 1281)', False)

    skenario_substitusi(tmp, 'sub505',
                        '> >(sdiff -W - target.txt; cat >> "$GITHUB_OUTPUT")',
                        'sdiff -W - target.txt: gerbang gagal (Audit 1282)', False)

    skenario_substitusi(tmp, 'sub506',
                        '> >(pdfinfo -enc UTF-8 -; cat >> "$GITHUB_OUTPUT")',
                        'pdfinfo -enc UTF-8 -: gerbang gagal (Audit 1283)', False)

    skenario_substitusi(tmp, 'sub507',
                        '> >(gm identify -format - /tmp/p81.png; cat >> "$GITHUB_OUTPUT")',
                        'gm identify -format - /tmp/p81.png: gerbang lolos (Audit 1284)', True)

    skenario_substitusi(tmp, 'sub508',
                        '> >(gm convert - -; cat >> "$GITHUB_OUTPUT")',
                        'gm convert - -: gerbang gagal (Audit 1285)', False)

    skenario_substitusi(tmp, 'sub509',
                        '> >(pr --separator - berkas.txt; cat >> "$GITHUB_OUTPUT")',
                        'pr --separator - berkas.txt: gerbang gagal (Audit 1286)', False)

    skenario_substitusi(tmp, 'sub510',
                        '> >(json_pp -t json; cat >> "$GITHUB_OUTPUT")',
                        'json_pp -t json: gerbang gagal (Audit 1287)', False)

    skenario_substitusi(tmp, 'sub511',
                        '> >(uconv -b 4096 -f UTF-8 -t UTF-8; cat >> "$GITHUB_OUTPUT")',
                        'uconv -b 4096 -f UTF-8 -t UTF-8: gerbang gagal (Audit 1288)', False)

    skenario_substitusi(tmp, 'sub512',
                        '> >(piconv -S scheme -f utf8 -t utf8; cat >> "$GITHUB_OUTPUT")',
                        'piconv -S scheme -f utf8 -t utf8: gerbang gagal (Audit 1289)', False)

    skenario_substitusi(tmp, 'sub513',
                        '> >(pod2text -e utf8; cat >> "$GITHUB_OUTPUT")',
                        'pod2text -e utf8: gerbang gagal (Audit 1290)', False)

    skenario_substitusi(tmp, 'sub514',
                        '> >(shasum -a 256; cat >> "$GITHUB_OUTPUT")',
                        'shasum -a 256: gerbang gagal (Audit 1291)', False)

    skenario_substitusi(tmp, 'sub515',
                        '> >(xzcat --block-size 1MiB; cat >> "$GITHUB_OUTPUT")',
                        'xzcat --block-size 1MiB: gerbang gagal (Audit 1292)', False)

    skenario_substitusi(tmp, 'sub516',
                        '> >(iptables-nft-restore -W 1000; cat >> "$GITHUB_OUTPUT")',
                        'iptables-nft-restore -W 1000: gerbang gagal (Audit 1293)', False)

    skenario_substitusi(tmp, 'sub517',
                        '> >(numfmt --round up; cat >> "$GITHUB_OUTPUT")',
                        'numfmt --round up: gerbang gagal (Audit 1294)', False)

    skenario_substitusi(tmp, 'sub518',
                        '> >(uclampset -M 0 cat; cat >> "$GITHUB_OUTPUT")',
                        'uclampset -M 0 cat: gerbang gagal (Audit 1295)', False)

    skenario_substitusi(tmp, 'sub519',
                        '> >(choom -p 999999 cat; cat >> "$GITHUB_OUTPUT")',
                        'choom -p 999999 cat: gerbang lolos (Audit 1296)', True)

    skenario_substitusi(tmp, 'sub520',
                        '> >(xvfb-run -p MIT-MAGIC-COOKIE-1 cat; cat >> "$GITHUB_OUTPUT")',
                        'xvfb-run -p MIT-MAGIC-COOKIE-1 cat: gerbang gagal (Audit 1297)', False)

    skenario_substitusi(tmp, 'sub521',
                        '> >(linux64 --list cat; cat >> "$GITHUB_OUTPUT")',
                        'linux64 --list cat: gerbang lolos (Audit 1298)', True)

    skenario_substitusi(tmp, 'sub522',
                        '> >(nodejs -r fs; cat >> "$GITHUB_OUTPUT")',
                        'nodejs -r fs: gerbang gagal (Audit 1299)', False)

    skenario_substitusi(tmp, 'sub523',
                        '> >(gmake -f /dev/stdin; cat >> "$GITHUB_OUTPUT")',
                        'gmake -f /dev/stdin: gerbang gagal (Audit 1300)', False)

    skenario_substitusi(tmp, 'sub524',
                        '> >(perl5.38.2; cat >> "$GITHUB_OUTPUT")',
                        'perl5.38.2: gerbang gagal (Audit 1301)', False)

    skenario_substitusi(tmp, 'sub525',
                        '> >(cpp; cat >> "$GITHUB_OUTPUT")',
                        'cpp: gerbang gagal (Audit 1302)', False)

    skenario_substitusi(tmp, 'sub526',
                        '> >(gcc -x c -E -; cat >> "$GITHUB_OUTPUT")',
                        'gcc -x c -E -: gerbang gagal (Audit 1303)', False)

    skenario_substitusi(tmp, 'sub527',
                        '> >(as -o out -; cat >> "$GITHUB_OUTPUT")',
                        'as -o out -: gerbang gagal (Audit 1304)', False)

    skenario_substitusi(tmp, 'sub528',
                        '> >(zcmp - -; cat >> "$GITHUB_OUTPUT")',
                        'zcmp - -: gerbang gagal (Audit 1305)', False)

    skenario_substitusi(tmp, 'sub529',
                        '> >(zegrep UTUH; cat >> "$GITHUB_OUTPUT")',
                        'zegrep UTUH: gerbang gagal (Audit 1306)', False)

    skenario_substitusi(tmp, 'sub530',
                        '> >(bzegrep UTUH; cat >> "$GITHUB_OUTPUT")',
                        'bzegrep UTUH: gerbang gagal (Audit 1307)', False)

    skenario_substitusi(tmp, 'sub531',
                        '> >(xzcmp - -; cat >> "$GITHUB_OUTPUT")',
                        'xzcmp - -: gerbang gagal (Audit 1308)', False)

    skenario_substitusi(tmp, 'sub532',
                        '> >(lzcmp - -; cat >> "$GITHUB_OUTPUT")',
                        'lzcmp - -: gerbang gagal (Audit 1309)', False)

    skenario_substitusi(tmp, 'sub533',
                        '> >(uncompress; cat >> "$GITHUB_OUTPUT")',
                        'uncompress: gerbang gagal (Audit 1310)', False)

    skenario_substitusi(tmp, 'sub534',
                        '> >(cmp berkas -; cat >> "$GITHUB_OUTPUT")',
                        'cmp berkas -: gerbang gagal (Audit 1311)', False)

    skenario_substitusi(tmp, 'sub535',
                        '> >(ptar -t -f -; cat >> "$GITHUB_OUTPUT")',
                        'ptar -t -f -: gerbang gagal (Audit 1312)', False)

    skenario_substitusi(tmp, 'sub536',
                        '> >(streamzip; cat >> "$GITHUB_OUTPUT")',
                        'streamzip: gerbang gagal (Audit 1313)', False)

    skenario_substitusi(tmp, 'sub537',
                        '> >(zless; cat >> "$GITHUB_OUTPUT")',
                        'zless: gerbang gagal (Audit 1314)', False)

    skenario_substitusi(tmp, 'sub538',
                        '> >(sensible-pager; cat >> "$GITHUB_OUTPUT")',
                        'sensible-pager: gerbang gagal (Audit 1315)', False)

    skenario_substitusi(tmp, 'sub539',
                        '> >(vim.tiny -n; cat >> "$GITHUB_OUTPUT")',
                        'vim.tiny -n: gerbang gagal (Audit 1316)', False)

    skenario_substitusi(tmp, 'sub540',
                        '> >(script -qec cat /dev/null; cat >> "$GITHUB_OUTPUT")',
                        'script -qec cat /dev/null: gerbang gagal (Audit 1317)', False)

    skenario_substitusi(tmp, 'sub541',
                        '> >(i386 cat; cat >> "$GITHUB_OUTPUT")',
                        'i386 cat: gerbang gagal (Audit 1318)', False)

    skenario_substitusi(tmp, 'sub542',
                        '> >(systemd-run --scope --quiet cat; cat >> "$GITHUB_OUTPUT")',
                        'systemd-run --scope --quiet cat: gerbang gagal (Audit 1319)', False)

    skenario_substitusi(tmp, 'sub543',
                        '> >(tmux load-buffer -; cat >> "$GITHUB_OUTPUT")',
                        'tmux load-buffer -: gerbang gagal (Audit 1320)', False)

    skenario_substitusi(tmp, 'sub544',
                        '> >(systemd-sysusers --root=/tmp -; cat >> "$GITHUB_OUTPUT")',
                        'systemd-sysusers --root=/tmp -: gerbang gagal (Audit 1321)', False)

    skenario_substitusi(tmp, 'sub545',
                        '> >(systemd-tmpfiles --root=/tmp --create -; cat >> "$GITHUB_OUTPUT")',
                        'systemd-tmpfiles --root=/tmp --create -: gerbang gagal (Audit 1322)', False)

    skenario_substitusi(tmp, 'sub546',
                        '> >(systemd-creds encrypt --name=uji - out; cat >> "$GITHUB_OUTPUT")',
                        'systemd-creds encrypt --name=uji - out: gerbang gagal (Audit 1323)', False)

    skenario_substitusi(tmp, 'sub547',
                        '> >(debconf-set-selections --checkonly; cat >> "$GITHUB_OUTPUT")',
                        'debconf-set-selections --checkonly: gerbang gagal (Audit 1324)', False)

    skenario_substitusi(tmp, 'sub548',
                        '> >(debconf-communicate paketuji; cat >> "$GITHUB_OUTPUT")',
                        'debconf-communicate paketuji: gerbang gagal (Audit 1325)', False)

    skenario_substitusi(tmp, 'sub549',
                        '> >(dpkg-deb --contents -; cat >> "$GITHUB_OUTPUT")',
                        'dpkg-deb --contents -: gerbang gagal (Audit 1326)', False)

    skenario_substitusi(tmp, 'sub550',
                        '> >(dpkg-parsechangelog -l -; cat >> "$GITHUB_OUTPUT")',
                        'dpkg-parsechangelog -l -: gerbang gagal (Audit 1327)', False)

    skenario_substitusi(tmp, 'sub551',
                        '> >(cvtsudoers -f json; cat >> "$GITHUB_OUTPUT")',
                        'cvtsudoers -f json: gerbang gagal (Audit 1328)', False)

    skenario_substitusi(tmp, 'sub552',
                        '> >(tic -o out -; cat >> "$GITHUB_OUTPUT")',
                        'tic -o out -: gerbang gagal (Audit 1329)', False)

    skenario_substitusi(tmp, 'sub553',
                        '> >(lesskey -o out -; cat >> "$GITHUB_OUTPUT")',
                        'lesskey -o out -: gerbang gagal (Audit 1330)', False)

    skenario_substitusi(tmp, 'sub554',
                        '> >(xkbcomp -xkb - out; cat >> "$GITHUB_OUTPUT")',
                        'xkbcomp -xkb - out: gerbang gagal (Audit 1331)', False)

    skenario_substitusi(tmp, 'sub555',
                        '> >(wayland-scanner client-header /dev/stdin out; cat >> "$GITHUB_OUTPUT")',
                        'wayland-scanner client-header /dev/stdin out: gerbang gagal (Audit 1332)', False)

    skenario_substitusi(tmp, 'sub556',
                        '> >(localedef --prefix=/tmp -i - -f UTF-8 uji_XX; cat >> "$GITHUB_OUTPUT")',
                        'localedef --prefix=/tmp -i - -f UTF-8 uji_XX: gerbang gagal (Audit 1333)', False)

    skenario_substitusi(tmp, 'sub557',
                        '> >(glib-genmarshal -; cat >> "$GITHUB_OUTPUT")',
                        'glib-genmarshal -: gerbang gagal (Audit 1334)', False)

    skenario_substitusi(tmp, 'sub558',
                        '> >(genrb -d outdir -; cat >> "$GITHUB_OUTPUT")',
                        'genrb -d outdir -: gerbang gagal (Audit 1335)', False)

    skenario_substitusi(tmp, 'sub559',
                        '> >(pod2man; cat >> "$GITHUB_OUTPUT")',
                        'pod2man: gerbang gagal (Audit 1336)', False)

    skenario_substitusi(tmp, 'sub560',
                        '> >(splain; cat >> "$GITHUB_OUTPUT")',
                        'splain: gerbang gagal (Audit 1337)', False)

    skenario_substitusi(tmp, 'sub561',
                        '> >(instmodsh; cat >> "$GITHUB_OUTPUT")',
                        'instmodsh: gerbang gagal (Audit 1338)', False)

    skenario_substitusi(tmp, 'sub562',
                        '> >(pygettext3 -o out -; cat >> "$GITHUB_OUTPUT")',
                        'pygettext3 -o out -: gerbang gagal (Audit 1339)', False)

    skenario_substitusi(tmp, 'sub563',
                        '> >(nsupdate; cat >> "$GITHUB_OUTPUT")',
                        'nsupdate: gerbang gagal (Audit 1340)', False)

    skenario_substitusi(tmp, 'sub564',
                        '> >(ssh-keygen -L -f -; cat >> "$GITHUB_OUTPUT")',
                        'ssh-keygen -L -f -: gerbang gagal (Audit 1341)', False)

    skenario_substitusi(tmp, 'sub565',
                        '> >(tree --fromfile; cat >> "$GITHUB_OUTPUT")',
                        'tree --fromfile: gerbang gagal (Audit 1342)', False)

    skenario_substitusi(tmp, 'sub566',
                        '> >(gio save tujuan; cat >> "$GITHUB_OUTPUT")',
                        'gio save tujuan: gerbang gagal (Audit 1343)', False)

    skenario_substitusi(tmp, 'sub567',
                        '> >(debugfs /tmp/citra.img; cat >> "$GITHUB_OUTPUT")',
                        'debugfs /tmp/citra.img: gerbang gagal (Audit 1344)', False)

    skenario_substitusi(tmp, 'sub568',
                        '> >(xauth -f berkas; cat >> "$GITHUB_OUTPUT")',
                        'xauth -f berkas: gerbang gagal (Audit 1345)', False)

    skenario_substitusi(tmp, 'sub569',
                        '> >(systemd-run --remain-after-exit --scope cat; cat >> "$GITHUB_OUTPUT")',
                        'systemd-run --remain-after-exit --scope cat: gerbang lolos (Audit 1347)', True)

    skenario_substitusi(tmp, 'sub570',
                        '> >(gm convert -append - gabung.png; cat >> "$GITHUB_OUTPUT")',
                        'gm convert -append - gabung.png: gerbang gagal (Audit 1348)', False)

    skenario_substitusi(tmp, 'sub571',
                        '> >(pr berkas.txt -; cat >> "$GITHUB_OUTPUT")',
                        'pr berkas.txt -: gerbang gagal (Audit 1349)', False)

    skenario_substitusi(tmp, 'sub572',
                        '> >(make --file=/dev/stdin; cat >> "$GITHUB_OUTPUT")',
                        'make --file=/dev/stdin: gerbang gagal (Audit 1350)', False)

    skenario_substitusi(tmp, 'sub573',
                        '> >(aarch64-linux-gnu-gcc -x c -E -; cat >> "$GITHUB_OUTPUT")',
                        'aarch64-linux-gnu-gcc -x c -E -: gerbang gagal (Audit 1351)', False)

    skenario_substitusi(tmp, 'sub574',
                        '> >(as -o out.o; cat >> "$GITHUB_OUTPUT")',
                        'as -o out.o: gerbang gagal (Audit 1352)', False)

    skenario_substitusi(tmp, 'sub575',
                        '> >(ptar -t -f - -f arsip.tar; cat >> "$GITHUB_OUTPUT")',
                        'ptar -t -f - -f arsip.tar: gerbang lolos (Audit 1353)', True)

    skenario_substitusi(tmp, 'sub576',
                        '> >(tmux -S /tmp/sok load-buffer -; cat >> "$GITHUB_OUTPUT")',
                        'tmux -S /tmp/sok load-buffer -: gerbang gagal (Audit 1354)', False)

    skenario_substitusi(tmp, 'sub577',
                        '> >(localedef --prefix=/tmp -i- -f UTF-8 uji_XX; cat >> "$GITHUB_OUTPUT")',
                        'localedef --prefix=/tmp -i- -f UTF-8 uji_XX: gerbang gagal (Audit 1355)', False)

    skenario_substitusi(tmp, 'sub578',
                        '> >(tree -P --fromfile; cat >> "$GITHUB_OUTPUT")',
                        'tree -P --fromfile: gerbang lolos (Audit 1356)', True)

    skenario_substitusi(tmp, 'sub579',
                        '> >(debugfs -Rstats citra.img; cat >> "$GITHUB_OUTPUT")',
                        'debugfs -Rstats citra.img: gerbang lolos (Audit 1357)', True)

    skenario_substitusi(tmp, 'sub580',
                        '> >(ffmpeg -i - -f null -; cat >> "$GITHUB_OUTPUT")',
                        'ffmpeg -i - -f null -: gerbang gagal (Audit 1358)', False)

    skenario_substitusi(tmp, 'sub581',
                        '> >(chpasswd; cat >> "$GITHUB_OUTPUT")',
                        'chpasswd: gerbang gagal (Audit 1359)', False)

    skenario_substitusi(tmp, 'sub582',
                        '> >(systemd-run --scope --on-clock-change cat; cat >> "$GITHUB_OUTPUT")',
                        'systemd-run --scope --on-clock-change cat: gerbang lolos (Audit 1364)', True)

    skenario_substitusi(tmp, 'sub583',
                        '> >(make --file=/dev/fd/0; cat >> "$GITHUB_OUTPUT")',
                        'make --file=/dev/fd/0: gerbang gagal (Audit 1365)', False)

    skenario_substitusi(tmp, 'sub584',
                        '> >(tmux -V load-buffer -; cat >> "$GITHUB_OUTPUT")',
                        'tmux -V load-buffer -: gerbang lolos (Audit 1366)', True)

    skenario_substitusi(tmp, 'sub585',
                        '> >(tree -- --fromfile; cat >> "$GITHUB_OUTPUT")',
                        'tree -- --fromfile: gerbang lolos (Audit 1367)', True)

    skenario_substitusi(tmp, 'sub586',
                        '> >(tree -aP --fromfile; cat >> "$GITHUB_OUTPUT")',
                        'tree -aP --fromfile: gerbang lolos (Audit 1368)', True)

    skenario_substitusi(tmp, 'sub587',
                        '> >(debugfs -wf perintah.txt citra.img; cat >> "$GITHUB_OUTPUT")',
                        'debugfs -wf perintah.txt citra.img: gerbang lolos (Audit 1369)', True)

    skenario_substitusi(tmp, 'sub588',
                        '> >(ffprobe berkas.mp4 pipe:; cat >> "$GITHUB_OUTPUT")',
                        'ffprobe berkas.mp4 pipe:: gerbang lolos (Audit 1370)', True)

    skenario_substitusi(tmp, 'sub589',
                        '> >(chpasswd berkasuji; cat >> "$GITHUB_OUTPUT")',
                        'chpasswd berkasuji: gerbang gagal (Audit 1371)', False)

    skenario_substitusi(tmp, 'sub590',
                        '> >(python3 -m sqlite3; cat >> "$GITHUB_OUTPUT")',
                        'python3 -m sqlite3: gerbang gagal (Audit 1372)', False)

    skenario_substitusi(tmp, 'sub591',
                        '> >(python3 -m tokenize; cat >> "$GITHUB_OUTPUT")',
                        'python3 -m tokenize: gerbang gagal (Audit 1373)', False)

    skenario_substitusi(tmp, 'sub592',
                        '> >(python3 -m code; cat >> "$GITHUB_OUTPUT")',
                        'python3 -m code: gerbang gagal (Audit 1374)', False)

    skenario_substitusi(tmp, 'sub593',
                        '> >(perl -d skrip.pl; cat >> "$GITHUB_OUTPUT")',
                        'perl -d skrip.pl: gerbang gagal (Audit 1375)', False)

    skenario_substitusi(tmp, 'sub594',
                        '> >(cpan; cat >> "$GITHUB_OUTPUT")',
                        'cpan: gerbang gagal (Audit 1376)', False)

    skenario_substitusi(tmp, 'sub595',
                        '> >(gh auth login --with-token; cat >> "$GITHUB_OUTPUT")',
                        'gh auth login --with-token: gerbang gagal (Audit 1377)', False)

    skenario_substitusi(tmp, 'sub596',
                        '> >(utmpdump; cat >> "$GITHUB_OUTPUT")',
                        'utmpdump: gerbang gagal (Audit 1378)', False)

    skenario_substitusi(tmp, 'sub597',
                        '> >(tree -Pa --fromfile; cat >> "$GITHUB_OUTPUT")',
                        'tree -Pa --fromfile: gerbang lolos (Audit 1384)', True)

    skenario_substitusi(tmp, 'sub598',
                        '> >(tree -aPx --fromfile; cat >> "$GITHUB_OUTPUT")',
                        'tree -aPx --fromfile: gerbang lolos (Audit 1384)', True)

    skenario_substitusi(tmp, 'sub599',
                        '> >(tree --gitfile --fromfile; cat >> "$GITHUB_OUTPUT")',
                        'tree --gitfile --fromfile: gerbang lolos (Audit 1385)', True)

    skenario_substitusi(tmp, 'sub600',
                        '> >(tmux -LV load-buffer -; cat >> "$GITHUB_OUTPUT")',
                        'tmux -LV load-buffer -: gerbang gagal (Audit 1386)', False)

    skenario_substitusi(tmp, 'sub601',
                        '> >(tmux -2V load-buffer -; cat >> "$GITHUB_OUTPUT")',
                        'tmux -2V load-buffer -: gerbang lolos (Audit 1387)', True)

    skenario_substitusi(tmp, 'sub602',
                        '> >(make -f/dev/fd/0; cat >> "$GITHUB_OUTPUT")',
                        'make -f/dev/fd/0: gerbang gagal (Audit 1388)', False)

    skenario_substitusi(tmp, 'sub603',
                        '> >(make -nf /dev/fd/0; cat >> "$GITHUB_OUTPUT")',
                        'make -nf /dev/fd/0: gerbang gagal (Audit 1389)', False)

    skenario_substitusi(tmp, 'sub604',
                        '> >(python3 -m sqlite3 -c select :memory:; cat >> "$GITHUB_OUTPUT")',
                        'python3 -m sqlite3 -c select :memory:: gerbang lolos (Audit 1390)', True)

    skenario_substitusi(tmp, 'sub605',
                        '> >(python3 -m sqlite3 --version; cat >> "$GITHUB_OUTPUT")',
                        'python3 -m sqlite3 --version: gerbang lolos (Audit 1390)', True)

    skenario_substitusi(tmp, 'sub606',
                        '> >(python3 -m gzip -; cat >> "$GITHUB_OUTPUT")',
                        'python3 -m gzip -: gerbang gagal (Audit 1391)', False)

    skenario_substitusi(tmp, 'sub607',
                        '> >(python3 -m json.tool -; cat >> "$GITHUB_OUTPUT")',
                        'python3 -m json.tool -: gerbang gagal (Audit 1391)', False)

    skenario_substitusi(tmp, 'sub608',
                        '> >(perl -Id skrip.pl; cat >> "$GITHUB_OUTPUT")',
                        'perl -Id skrip.pl: gerbang lolos (Audit 1392)', True)

    skenario_substitusi(tmp, 'sub609',
                        '> >(perl -mdiagnostics skrip.pl; cat >> "$GITHUB_OUTPUT")',
                        'perl -mdiagnostics skrip.pl: gerbang lolos (Audit 1392)', True)

    skenario_substitusi(tmp, 'sub610',
                        '> >(ffprobe -i berkas.mp4 -i pipe:; cat >> "$GITHUB_OUTPUT")',
                        'ffprobe -i berkas.mp4 -i pipe:: gerbang lolos (Audit 1393)', True)

    skenario_substitusi(tmp, 'sub611',
                        '> >(debugfs -z -f citra.img; cat >> "$GITHUB_OUTPUT")',
                        'debugfs -z -f citra.img: gerbang gagal (Audit 1394)', False)

    skenario_substitusi(tmp, 'sub612',
                        '> >(debugfs -zf citra.img; cat >> "$GITHUB_OUTPUT")',
                        'debugfs -zf citra.img: gerbang gagal (Audit 1394)', False)

    skenario_substitusi(tmp, 'sub613',
                        '> >(gh --hostname contoh.invalid auth login --with-token; cat >> "$GITHUB_OUTPUT")',
                        'gh --hostname contoh.invalid auth login --with-token: gerbang gagal (Audit 1395)', False)

    skenario_substitusi(tmp, 'sub614',
                        '> >(/bin/cat; cat >> "$GITHUB_OUTPUT")',
                        '/bin/cat: gerbang gagal (Audit 1396)', False)

    skenario_substitusi(tmp, 'sub615',
                        '> >(/usr/bin/openssl sha256; cat >> "$GITHUB_OUTPUT")',
                        '/usr/bin/openssl sha256: gerbang gagal (Audit 1396)', False)

    skenario_substitusi(tmp, 'sub616',
                        '> >(setsid /bin/cat; cat >> "$GITHUB_OUTPUT")',
                        'setsid /bin/cat: gerbang gagal (Audit 1396)', False)

    skenario_substitusi(tmp, 'sub617',
                        '> >(chgpasswd; cat >> "$GITHUB_OUTPUT")',
                        'chgpasswd: gerbang gagal (Audit 1397)', False)

    skenario_substitusi(tmp, 'sub618',
                        '> >(chgpasswd berkasuji; cat >> "$GITHUB_OUTPUT")',
                        'chgpasswd berkasuji: gerbang gagal (Audit 1397)', False)

    skenario_substitusi(tmp, 'sub619',
                        '> >(pl2pm; cat >> "$GITHUB_OUTPUT")',
                        'pl2pm: gerbang gagal (Audit 1398)', False)

    skenario_substitusi(tmp, 'sub620',
                        '> >(git credential approve; cat >> "$GITHUB_OUTPUT")',
                        'git credential approve: gerbang gagal (Audit 1399)', False)

    skenario_substitusi(tmp, 'sub621',
                        '> >(git format-patch --stdin; cat >> "$GITHUB_OUTPUT")',
                        'git format-patch --stdin: gerbang gagal (Audit 1400)', False)

    skenario_substitusi(tmp, 'sub622',
                        '> >(git add -p; cat >> "$GITHUB_OUTPUT")',
                        'git add -p: gerbang gagal (Audit 1401)', False)

    skenario_substitusi(tmp, 'sub623',
                        '> >(git clean -i; cat >> "$GITHUB_OUTPUT")',
                        'git clean -i: gerbang gagal (Audit 1401)', False)

    skenario_substitusi(tmp, 'sub624',
                        '> >(openssl sess_id; cat >> "$GITHUB_OUTPUT")',
                        'openssl sess_id: gerbang gagal (Audit 1402)', False)

    skenario_substitusi(tmp, 'sub625',
                        '> >(openssl asn1parse berkas.der; cat >> "$GITHUB_OUTPUT")',
                        'openssl asn1parse berkas.der: gerbang lolos (Audit 1402)', True)

    skenario_substitusi(tmp, 'sub626',
                        '> >(openssl req -new -key kunci.pem; cat >> "$GITHUB_OUTPUT")',
                        'openssl req -new -key kunci.pem: gerbang gagal (Audit 1403)', False)

    skenario_substitusi(tmp, 'sub627',
                        '> >(pdftoppm - hasil; cat >> "$GITHUB_OUTPUT")',
                        'pdftoppm - hasil: gerbang gagal (Audit 1404)', False)

    skenario_substitusi(tmp, 'sub628',
                        '> >(pdftocairo -png - hasil.png; cat >> "$GITHUB_OUTPUT")',
                        'pdftocairo -png - hasil.png: gerbang gagal (Audit 1404)', False)

    skenario_substitusi(tmp, 'sub629',
                        '> >(npm init; cat >> "$GITHUB_OUTPUT")',
                        'npm init: gerbang gagal (Audit 1405)', False)

    skenario_substitusi(tmp, 'sub630',
                        '> >(strings -; cat >> "$GITHUB_OUTPUT")',
                        'strings -: gerbang lolos (Audit 1406)', True)

    skenario_substitusi(tmp, 'sub631',
                        '> >(cpan -v; cat >> "$GITHUB_OUTPUT")',
                        'cpan -v: gerbang lolos (Audit 1407)', True)

    skenario_substitusi(tmp, 'sub632',
                        '> >(utmpdump -h; cat >> "$GITHUB_OUTPUT")',
                        'utmpdump -h: gerbang lolos (Audit 1407)', True)

    skenario_substitusi(tmp, 'sub633',
                        '> >(X=/a/b cat; cat >> "$GITHUB_OUTPUT")',
                        'X=/a/b cat: gerbang gagal (Audit 1408)', False)

    skenario_substitusi(tmp, 'sub634',
                        '> >(FOO=/bin/cat; cat >> "$GITHUB_OUTPUT")',
                        'FOO=/bin/cat: gerbang lolos (Audit 1408)', True)

    skenario_substitusi(tmp, 'sub635',
                        '> >(openssl asn1parse; cat >> "$GITHUB_OUTPUT")',
                        'openssl asn1parse: gerbang gagal (Audit 1409)', False)

    skenario_substitusi(tmp, 'sub636',
                        '> >(perl -0777pe skrip.pl; cat >> "$GITHUB_OUTPUT")',
                        'perl -0777pe skrip.pl: gerbang gagal (Audit 1411)', False)

    skenario_substitusi(tmp, 'sub637',
                        '> >(perl -ned skrip.pl; cat >> "$GITHUB_OUTPUT")',
                        'perl -ned skrip.pl: gerbang lolos (Audit 1412)', True)

    skenario_substitusi(tmp, 'sub638',
                        '> >(debugfs -z; cat >> "$GITHUB_OUTPUT")',
                        'debugfs -z: gerbang lolos (Audit 1413)', True)

    skenario_substitusi(tmp, 'sub639',
                        '> >(debugfs -f -; cat >> "$GITHUB_OUTPUT")',
                        'debugfs -f -: gerbang gagal (Audit 1414)', False)

    skenario_substitusi(tmp, 'sub640',
                        '> >(git stash push -m -p; cat >> "$GITHUB_OUTPUT")',
                        'git stash push -m -p: gerbang lolos (Audit 1415)', True)

    skenario_substitusi(tmp, 'sub641',
                        '> >(git add -- -patch; cat >> "$GITHUB_OUTPUT")',
                        'git add -- -patch: gerbang lolos (Audit 1415)', True)

    skenario_substitusi(tmp, 'sub642',
                        '> >(git format-patch --output-directory --stdin HEAD; cat >> "$GITHUB_OUTPUT")',
                        'git format-patch --output-directory --stdin HEAD: gerbang lolos (Audit 1416)', True)

    skenario_substitusi(tmp, 'sub643',
                        '> >(openssl asn1parse -length 100; cat >> "$GITHUB_OUTPUT")',
                        'openssl asn1parse -length 100: gerbang gagal (Audit 1417)', False)

    skenario_substitusi(tmp, 'sub644',
                        '> >(openssl sess_id -context abc; cat >> "$GITHUB_OUTPUT")',
                        'openssl sess_id -context abc: gerbang gagal (Audit 1418)', False)

    skenario_substitusi(tmp, 'sub645',
                        '> >(openssl req -text -noout; cat >> "$GITHUB_OUTPUT")',
                        'openssl req -text -noout: gerbang gagal (Audit 1419)', False)

    skenario_substitusi(tmp, 'sub646',
                        '> >(pdftoppm -scale-to 800 - hasil; cat >> "$GITHUB_OUTPUT")',
                        'pdftoppm -scale-to 800 - hasil: gerbang gagal (Audit 1420)', False)

    skenario_substitusi(tmp, 'sub647',
                        '> >(npm --loglevel warn init; cat >> "$GITHUB_OUTPUT")',
                        'npm --loglevel warn init: gerbang gagal (Audit 1421)', False)

    skenario_substitusi(tmp, 'sub648',
                        '> >(python3 -m gzip berkas.gz -; cat >> "$GITHUB_OUTPUT")',
                        'python3 -m gzip berkas.gz -: gerbang gagal (Audit 1422)', False)

    skenario_substitusi(tmp, 'sub649',
                        '> >(pl2pm -v; cat >> "$GITHUB_OUTPUT")',
                        'pl2pm -v: gerbang lolos (Audit 1423)', True)

    skenario_substitusi(tmp, 'sub650',
                        '> >(gh --hostname --with-token auth login; cat >> "$GITHUB_OUTPUT")',
                        'gh --hostname --with-token auth login: gerbang lolos (Audit 1424)', True)

    skenario_substitusi(tmp, 'sub651',
                        '> >(pinentry; cat >> "$GITHUB_OUTPUT")',
                        'pinentry: gerbang gagal (Audit 1425)', False)

    skenario_substitusi(tmp, 'sub652',
                        '> >(dirmngr; cat >> "$GITHUB_OUTPUT")',
                        'dirmngr: gerbang gagal (Audit 1426)', False)

    skenario_substitusi(tmp, 'sub653',
                        '> >(gpg-connect-agent; cat >> "$GITHUB_OUTPUT")',
                        'gpg-connect-agent: gerbang gagal (Audit 1427)', False)

    skenario_substitusi(tmp, 'sub654',
                        '> >(gpgsm --decrypt; cat >> "$GITHUB_OUTPUT")',
                        'gpgsm --decrypt: gerbang gagal (Audit 1428)', False)

    skenario_substitusi(tmp, 'sub655',
                        '> >(derdump; cat >> "$GITHUB_OUTPUT")',
                        'derdump: gerbang gagal (Audit 1429)', False)

    skenario_substitusi(tmp, 'sub656',
                        '> >(certutil -A -n nama -t ,, -d db; cat >> "$GITHUB_OUTPUT")',
                        'certutil -A -n nama -t ,, -d db: gerbang gagal (Audit 1430)', False)

    skenario_substitusi(tmp, 'sub657',
                        '> >(cmsutil -D; cat >> "$GITHUB_OUTPUT")',
                        'cmsutil -D: gerbang gagal (Audit 1431)', False)

    skenario_substitusi(tmp, 'sub658',
                        '> >(openssl ts -query; cat >> "$GITHUB_OUTPUT")',
                        'openssl ts -query: gerbang gagal (Audit 1432)', False)

    skenario_substitusi(tmp, 'sub659',
                        '> >(openssl ca -in -; cat >> "$GITHUB_OUTPUT")',
                        'openssl ca -in -: gerbang gagal (Audit 1433)', False)

    skenario_substitusi(tmp, 'sub660',
                        '> >(git fmt-merge-msg; cat >> "$GITHUB_OUTPUT")',
                        'git fmt-merge-msg: gerbang gagal (Audit 1434)', False)

    skenario_substitusi(tmp, 'sub661',
                        '> >(apt-key add -; cat >> "$GITHUB_OUTPUT")',
                        'apt-key add -: gerbang gagal (Audit 1435)', False)

    skenario_substitusi(tmp, 'sub662',
                        '> >(git fmt-merge-msg -m judul; cat >> "$GITHUB_OUTPUT")',
                        'git fmt-merge-msg -m judul: gerbang gagal (Audit 1441)', False)

    skenario_substitusi(tmp, 'sub663',
                        '> >(git fmt-merge-msg -F -; cat >> "$GITHUB_OUTPUT")',
                        'git fmt-merge-msg -F -: gerbang gagal (Audit 1442)', False)

    skenario_substitusi(tmp, 'sub664',
                        '> >(openssl asn1parse -item ASN1_OCTET_STRING; cat >> "$GITHUB_OUTPUT")',
                        'openssl asn1parse -item: gerbang gagal (Audit 1443)', False)

    skenario_substitusi(tmp, 'sub665',
                        '> >(openssl ts -query -data -; cat >> "$GITHUB_OUTPUT")',
                        'openssl ts -query -data -: gerbang gagal (Audit 1444)', False)

    skenario_substitusi(tmp, 'sub666',
                        '> >(npm --init-module /tmp/modul.js init; cat >> "$GITHUB_OUTPUT")',
                        'npm --init-module init: gerbang gagal (Audit 1445)', False)

    skenario_substitusi(tmp, 'sub667',
                        '> >(gpgsm --decrypt --cipher-algo AES256; cat >> "$GITHUB_OUTPUT")',
                        'gpgsm --decrypt --cipher-algo: gerbang gagal (Audit 1446)', False)

    skenario_substitusi(tmp, 'sub668',
                        '> >(cmsutil -D -p -i; cat >> "$GITHUB_OUTPUT")',
                        'cmsutil -D -p -i: gerbang gagal (Audit 1447)', False)

    skenario_substitusi(tmp, 'sub669',
                        '> >(pdftoppm -aa yes - hasil; cat >> "$GITHUB_OUTPUT")',
                        'pdftoppm -aa yes - hasil: gerbang gagal (Audit 1448)', False)

    skenario_substitusi(tmp, 'sub670',
                        '> >(git format-patch --start-number --stdin HEAD; cat >> "$GITHUB_OUTPUT")',
                        'git format-patch --start-number --stdin: gerbang lolos (Audit 1449)', True)

    sub671 = klon(tmp, "sub671")
    berkas_gerbang671 = os.path.join(
        sub671, "tools", "verifikasi-patch.py")
    with open(berkas_gerbang671, encoding="utf-8") as f:
        isi_gerbang671 = f.read()
    isi_baru671 = isi_gerbang671.replace(
            '"status_on", "hero_start",', '"status_on",', 1)
    assert isi_baru671 != isi_gerbang671, \
        "suntikan gerbang671 tidak menembak"
    with open(berkas_gerbang671, "w", encoding="utf-8") as f:
        f.write(isi_baru671)
    rc, out = jalan(sub671, [sys.executable, GERBANG, "--semua"])
    periksa("pengecualian paritas malam dicabut satu: "
            "gerbang gagal (Audit 1491)",
            rc != 0 and rc != 124 and "hero_start" in out)
    shutil.rmtree(sub671, ignore_errors=True)
    sub672 = klon(tmp, "sub672")
    berkas_warna672 = os.path.join(
        sub672, "app", "src", "main", "res", "values-night",
        "colors.xml")
    with open(berkas_warna672, encoding="utf-8") as f:
        isi_warna672 = f.read()
    isi_baru672 = "".join(
        b for b in isi_warna672.splitlines(keepends=True)
        if 'name="status_off"' not in b)
    assert isi_baru672 != isi_warna672, \
        "suntikan warna672 tidak menembak"
    with open(berkas_warna672, "w", encoding="utf-8") as f:
        f.write(isi_baru672)
    rc, out = jalan(sub672, [sys.executable, GERBANG, "--semua"])
    periksa("warna tak terkecuali absen di malam: "
            "gerbang gagal (Audit 1491)",
            rc != 0 and rc != 124 and "status_off" in out)
    shutil.rmtree(sub672, ignore_errors=True)
    skenario_substitusi(tmp, 'sub673',
                        '> >(openssl pkeyparam; cat >> "$GITHUB_OUTPUT")',
                        'openssl pkeyparam telanjang menguras stdin: gerbang gagal (Audit 1495)', False)

    skenario_substitusi(tmp, 'sub674',
                        '> >(openssl mac -macopt key:x -digest SHA256 HMAC; cat >> "$GITHUB_OUTPUT")',
                        'openssl mac tanpa -in menguras stdin: gerbang gagal (Audit 1496)', False)

    skenario_substitusi(tmp, 'sub675',
                        '> >(openssl ts -query -data data.bin -data -; cat >> "$GITHUB_OUTPUT")',
                        'openssl ts -data terakhir - menguras stdin: gerbang gagal (Audit 1497)', False)

    skenario_substitusi(tmp, 'sub676',
                        '> >(pdftocairo -antialias best -png - out; cat >> "$GITHUB_OUTPUT")',
                        'pdftocairo -antialias bernilai: operand - adalah stdin: gerbang gagal (Audit 1498)', False)

    skenario_substitusi(tmp, 'sub677',
                        '> >(openssl sess_id -item FOO; cat >> "$GITHUB_OUTPUT")',
                        'openssl sess_id -item adalah galat usage, bukan stdin: gerbang lolos (Audit 1499)', True)

    skenario_substitusi(tmp, 'sub678',
                        '> >(cmsutil -D -d sql:db -i -; cat >> "$GITHUB_OUTPUT")',
                        'cmsutil -i - adalah nama berkas harfiah, bukan stdin: gerbang lolos (Audit 1500)', True)

    skenario_substitusi(tmp, 'sub679',
                        '> >(openssl enc -aes-256-cbc -in - -in data.bin -pass pass:x; cat >> "$GITHUB_OUTPUT")',
                        'openssl enc -in terakhir berkas, bukan stdin: gerbang lolos (Audit 1501)', True)

    skenario_substitusi(tmp, 'sub680',
                        '> >(git commit -m -F -; cat >> "$GITHUB_OUTPUT")',
                        'git commit -m -F -: -F adalah nilai -m, bukan berkas stdin: gerbang lolos (Audit 1502)', True)

    skenario_substitusi(tmp, 'sub681',
                        '> >(git format-patch --signature --stdin; cat >> "$GITHUB_OUTPUT")',
                        'git format-patch --signature menelan --stdin sebagai nilai: gerbang lolos (Audit 1503)', True)

    skenario_substitusi(tmp, 'sub682',
                        '> >(gpgsm --options --decrypt; cat >> "$GITHUB_OUTPUT")',
                        'gpgsm --options --decrypt: --decrypt adalah nilai opsi: gerbang lolos (Audit 1504)', True)

    sub683 = klon(tmp, "sub683")
    berkas_warna683 = os.path.join(
        sub683, "app", "src", "main", "res", "values-night",
        "colors.xml")
    with open(berkas_warna683, encoding="utf-8") as f:
        isi_warna683 = f.read()
    isi_baru683 = isi_warna683.replace(
            "</resources>",
            '    <color name="warna_khusus_malam88">#FF000000</color>\n'
            "</resources>", 1)
    assert isi_baru683 != isi_warna683, \
        "suntikan warna683 tidak menembak"
    with open(berkas_warna683, "w", encoding="utf-8") as f:
        f.write(isi_baru683)
    rc, out = jalan(sub683, [sys.executable, GERBANG, "--semua"])
    periksa("warna hanya ada di malam (arah malam-siang): "
            "gerbang gagal (Audit 1507)",
            rc != 0 and rc != 124 and "warna_khusus_malam88" in out)
    shutil.rmtree(sub683, ignore_errors=True)
    skenario_substitusi(tmp, 'sub684',
                        '> >(openssl spkac; cat >> "$GITHUB_OUTPUT")',
                        'openssl spkac telanjang menguras stdin: gerbang gagal (Audit 1520)', False)

    skenario_substitusi(tmp, 'sub685',
                        '> >(openssl verify -vfyopt nama; cat >> "$GITHUB_OUTPUT")',
                        'openssl verify -vfyopt bernilai: tanpa operand membaca stdin: gerbang gagal (Audit 1521)', False)

    skenario_substitusi(tmp, 'sub686',
                        '> >(git commit -aF -; cat >> "$GITHUB_OUTPUT")',
                        'git commit -aF -: -F di bundel membaca pesan dari stdin: gerbang gagal (Audit 1522)', False)

    skenario_substitusi(tmp, 'sub687',
                        '> >(openssl storeutl /dev/stdin; cat >> "$GITHUB_OUTPUT")',
                        'openssl storeutl operand /dev/stdin membaca stdin: gerbang gagal (Audit 1523)', False)

    skenario_substitusi(tmp, 'sub688',
                        '> >(git commit --cleanup -F -; cat >> "$GITHUB_OUTPUT")',
                        'git commit --cleanup -F -: -F adalah nilai --cleanup: gerbang lolos (Audit 1524)', True)

    skenario_substitusi(tmp, 'sub689',
                        '> >(gpgsm --log-file --decrypt; cat >> "$GITHUB_OUTPUT")',
                        'gpgsm --log-file --decrypt: --decrypt adalah nilai opsi: gerbang lolos (Audit 1525)', True)

    skenario_substitusi(tmp, 'sub690',
                        '> >(git commit -s -F -; cat >> "$GITHUB_OUTPUT")',
                        'git commit -s -F -: -s bendera tanpa nilai di commit: gerbang gagal (Audit 1535)', False)

    skenario_substitusi(tmp, 'sub691',
                        '> >(gpgsm --pinentry-mode loopback --decrypt; cat >> "$GITHUB_OUTPUT")',
                        'gpgsm --pinentry-mode bernilai: --decrypt tak tertelan sebagai nilai maupun tertekan operand: gerbang gagal (Audit 1545)', False)

    skenario_substitusi(tmp, 'sub692',
                        '> >(openssl storeutl file:///dev/stdin; cat >> "$GITHUB_OUTPUT")',
                        'storeutl URI file:// ke /dev/stdin dinilai membaca stdin: gerbang gagal (Audit 1546)', False)

    skenario_substitusi(tmp, 'sub693',
                        '> >(openssl aes-256-cbc -pass pass:uji12152; cat >> "$GITHUB_OUTPUT")',
                        'perintah openssl nama-cipher (aes-256-cbc) dinilai membaca stdin: gerbang gagal (Audit 1548)', False)

    skenario_substitusi(tmp, 'sub694',
                        '> >(gpg --batch --pinentry-mode loopback --passphrase uji12152 --s2k-mode 3 -c; cat >> "$GITHUB_OUTPUT")',
                        'gpg opsi --s2k-mode bernilai: gerbang gagal (Audit 1549)', False)

    skenario_substitusi(tmp, 'sub695',
                        '> >(gpgsm --debug 1024 --decrypt; cat >> "$GITHUB_OUTPUT")',
                        'gpgsm opsi --debug bernilai: gerbang gagal (Audit 1550)', False)

    skenario_substitusi(tmp, 'sub696',
                        '> >(openssl storeutl file:/dev/stdin; cat >> "$GITHUB_OUTPUT")',
                        'storeutl URI file: satu garis miring dinilai membaca stdin: gerbang gagal (Audit 1551)', False)

    skenario_substitusi(tmp, 'sub697',
                        '> >(openssl AES-256-CBC -pass pass:uji; cat >> "$GITHUB_OUTPUT")',
                        'openssl nama cipher kapital (AES-256-CBC) dinilai membaca stdin: gerbang gagal (Audit 1561)', False)

    skenario_substitusi(tmp, 'sub698',
                        '> >(openssl SHA256; cat >> "$GITHUB_OUTPUT")',
                        'openssl nama digest kapital (SHA256) dinilai membaca stdin: gerbang gagal (Audit 1561)', False)

    skenario_substitusi(tmp, 'sub699',
                        '> >(gpg --batch --pinentry-mode loopback --passphrase uji1562 --options opsi.txt --clearsign; cat >> "$GITHUB_OUTPUT")',
                        'gpg opsi --options bernilai: gerbang gagal (Audit 1562)', False)

    skenario_substitusi(tmp, 'sub700',
                        '> >(gpg --encrypt-to Uji92 -r Uji92 -e; cat >> "$GITHUB_OUTPUT")',
                        'gpg opsi --encrypt-to bernilai: gerbang gagal (Audit 1562)', False)

    skenario_substitusi(tmp, 'sub701',
                        '> >(gpg --batch --pinentry-mode loopback --passphrase uji1562 -z 3 --clearsign; cat >> "$GITHUB_OUTPUT")',
                        'gpg opsi pendek -z bernilai terpisah: gerbang gagal (Audit 1562)', False)

    skenario_substitusi(tmp, 'sub702',
                        '> >(gpgsm --debug-wait 0 --decrypt; cat >> "$GITHUB_OUTPUT")',
                        'gpgsm opsi --debug-wait bernilai: gerbang gagal (Audit 1563)', False)

    skenario_substitusi(tmp, 'sub703',
                        '> >(openssl storeutl file:////dev/stdin; cat >> "$GITHUB_OUTPUT")',
                        'storeutl URI miring ganda dinilai membaca stdin: gerbang gagal (Audit 1564)', False)

    skenario_substitusi(tmp, 'sub704',
                        '> >(openssl storeutl file://example.com/dev/stdin; cat >> "$GITHUB_OUTPUT")',
                        'storeutl URI host asing BUKAN stdin: gerbang tetap lolos (Audit 1564)', True)

    sub705 = klon(tmp, "sub705")
    berkas_aksi705 = os.path.join(
        sub705, ".github", "actions", "resolve-vw", "action.yml")
    with open(berkas_aksi705, encoding="utf-8") as f:
        isi_aksi705 = f.read()
    isi_baru705 = isi_aksi705 + (
        "\n# contoh: -----BEGIN OPENSSH PRIVATE "
        "KEY-----\n")
    assert isi_baru705 != isi_aksi705, \
        "tambahan aksi705 tidak menembak"
    with open(berkas_aksi705, "w", encoding="utf-8") as f:
        f.write(isi_baru705)
    rc, out = jalan(sub705, [sys.executable, GERBANG, "--semua"])
    periksa("literal header kunci utuh di baris baru: gerbang gagal (Audit 1571)",
            rc != 0 and rc != 124 and "literal header kunci" in out)
    shutil.rmtree(sub705, ignore_errors=True)
    sub706 = klon(tmp, "sub706")
    berkas_aksi706 = os.path.join(
        sub706, ".github", "actions", "resolve-vw", "action.yml")
    with open(berkas_aksi706, encoding="utf-8") as f:
        isi_aksi706 = f.read()
    isi_baru706 = isi_aksi706 + (
        "\n# contoh rakitan: \"-----BEGIN OPENSSH PRIVATE \" "
        "\"KEY-----\"\n")
    assert isi_baru706 != isi_aksi706, \
        "tambahan aksi706 tidak menembak"
    with open(berkas_aksi706, "w", encoding="utf-8") as f:
        f.write(isi_baru706)
    rc, out = jalan(sub706, [sys.executable, GERBANG, "--hanya-workflow"])
    periksa("header terbelah dua literal di baris baru: gerbang tetap lolos (Audit 1571)",
            rc == 0)
    shutil.rmtree(sub706, ignore_errors=True)
    sub707 = klon(tmp, "sub707")
    berkas_aksi707 = os.path.join(
        sub707, ".github", "actions", "resolve-vw", "action.yml")
    with open(berkas_aksi707, encoding="utf-8") as f:
        isi_aksi707 = f.read()
    isi_baru707 = isi_aksi707 + (
        "\n# contoh: -----BEGIN PGP PRIVATE "
        "KEY BLOCK-----\n")
    assert isi_baru707 != isi_aksi707, \
        "tambahan aksi707 tidak menembak"
    with open(berkas_aksi707, "w", encoding="utf-8") as f:
        f.write(isi_baru707)
    rc, out = jalan(sub707, [sys.executable, GERBANG, "--semua"])
    periksa("blok PGP PRIVATE KEY BLOCK utuh: gerbang gagal (Audit 1578)",
            rc != 0 and rc != 124 and "literal header kunci" in out)
    shutil.rmtree(sub707, ignore_errors=True)

    sub708 = klon(tmp, "sub708")
    berkas_aksi708 = os.path.join(
        sub708, ".github", "actions", "resolve-vw", "action.yml")
    with open(berkas_aksi708, encoding="utf-8") as f:
        isi_aksi708 = f.read()
    isi_baru708 = isi_aksi708 + (
        "\n# contoh: -----begin private "
        "key-----\n")
    assert isi_baru708 != isi_aksi708, \
        "tambahan aksi708 tidak menembak"
    with open(berkas_aksi708, "w", encoding="utf-8") as f:
        f.write(isi_baru708)
    rc, out = jalan(sub708, [sys.executable, GERBANG, "--semua"])
    periksa("header kunci huruf kecil utuh: gerbang gagal (Audit 1578)",
            rc != 0 and rc != 124 and "literal header kunci" in out)
    shutil.rmtree(sub708, ignore_errors=True)

    sub709 = klon(tmp, "sub709")
    berkas_aksi709 = os.path.join(
        sub709, ".github", "actions", "resolve-vw", "action.yml")
    with open(berkas_aksi709, encoding="utf-8") as f:
        isi_aksi709 = f.read()
    isi_baru709 = isi_aksi709 + (
        "\n# contoh: -----BEGIN FOO-BAR PRIVATE "
        "KEY-----\n")
    assert isi_baru709 != isi_aksi709, \
        "tambahan aksi709 tidak menembak"
    with open(berkas_aksi709, "w", encoding="utf-8") as f:
        f.write(isi_baru709)
    rc, out = jalan(sub709, [sys.executable, GERBANG, "--semua"])
    periksa("label header berstrip utuh: gerbang gagal (Audit 1578)",
            rc != 0 and rc != 124 and "literal header kunci" in out)
    shutil.rmtree(sub709, ignore_errors=True)
    sub710 = klon(tmp, "sub710")
    with open(os.path.join(sub710, "uji-biner710.png"),
              "wb") as f:
        f.write(b"\x89PNG\r\n\x1a\n" + b"\x00" * 32
           + b"-----BEGIN PRIVATE " b"KEY-----\n"
           b"QUJD\n"
           b"-----END PRIVATE " b"KEY-----\n")
    git(sub710, "add", "-A")
    git(sub710, "commit", "-q", "-m", "tanam biner uji")
    rc, out = jalan(sub710, [sys.executable, GERBANG, "--semua"])
    periksa("blok kunci utuh di dalam PNG: gerbang gagal (Audit 1579)",
            rc != 0 and rc != 124 and "Berkas biner memuat" in out)
    shutil.rmtree(sub710, ignore_errors=True)

    sub711 = klon(tmp, "sub711")
    with open(os.path.join(sub711, "uji-biner711.png"),
              "wb") as f:
        f.write(b"\x89PNG\r\n\x1a\n" + b"\x00" * 64)
    git(sub711, "add", "-A")
    git(sub711, "commit", "-q", "-m", "tanam biner uji")
    rc, out = jalan(sub711, [sys.executable, GERBANG, "--semua"])
    periksa("PNG bersih tanpa blok kunci: gerbang lolos (Audit 1579)",
            rc == 0)
    shutil.rmtree(sub711, ignore_errors=True)
    skenario_substitusi(tmp, 'sub712',
                        '> >(openssl sha3-256; cat >> "$GITHUB_OUTPUT")',
                        'openssl sha3-256 di substitusi proses: gerbang gagal (Audit 1580)', False)

    skenario_substitusi(tmp, 'sub713',
                        '> >(openssl dgst -verify kunci.pub -signature tanda.bin; cat >> "$GITHUB_OUTPUT")',
                        'openssl dgst -signature di substitusi proses: gerbang gagal (Audit 1581)', False)

    skenario_substitusi(tmp, 'sub714',
                        '> >(openssl enc -aes-256-cbc -pass pass:uji -out -in; cat >> "$GITHUB_OUTPUT")',
                        'openssl enc -out -in: gerbang gagal (Audit 1582)', False)

    sub715 = klon(tmp, "sub715")
    berkas_aksi715 = os.path.join(
        sub715, ".github", "actions", "resolve-vw",
        "action.yml")
    with open(berkas_aksi715, encoding="utf-8") as f:
        isi_aksi715 = f.read()
    baris_aksi715 = [b for b in isi_aksi715.splitlines()
                      if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
    isi_baru715 = isi_aksi715.replace(
            baris_aksi715,
            baris_aksi715.replace(
                '>> "$GITHUB_OUTPUT"',
                "> >(openssl aes-256-cbc -ciphers; cat >> \"$GITHUB_OUTPUT\")"), 1)
    assert isi_baru715 != isi_aksi715, \
        "suntikan aksi715 tidak menembak"
    with open(berkas_aksi715, "w", encoding="utf-8") as f:
        f.write(isi_baru715)
    rc, out = jalan(sub715,
                    [sys.executable, GERBANG, "--hanya-workflow"])
    periksa("openssl aes-256-cbc -ciphers: gerbang lolos (Audit 1583)", rc == 0)
    shutil.rmtree(sub715, ignore_errors=True)

    kurungjava = klon(tmp, "kurungjava")
    berkas_kj = os.path.join(
        kurungjava, "app", "src", "main", "java", "com", "tasirin",
        "vaultwardenhost", "PinGate.java")
    with open(berkas_kj, "a", encoding="utf-8") as f:
        f.write("\nclass UjiKurungRusak { int y = )()(; }\n")
    rc, out = jalan(kurungjava, [sys.executable, GERBANG, "--semua"])
    periksa("kurung urutan rusak seimbang-neto di .java: gerbang gagal (Audit 2918)",
            rc != 0 and rc != 124 and "urutannya rusak" in out)
    shutil.rmtree(kurungjava, ignore_errors=True)

    indukstyle = klon(tmp, "indukstyle")
    berkas_is = os.path.join(
        indukstyle, "app", "src", "main", "res", "values", "styles.xml")
    with open(berkas_is, "a", encoding="utf-8") as f:
        f.write('\n<style name="GayaUji2919" parent="TidakAdaSamaSekali2919" />\n')
    rc, out = jalan(indukstyle, [sys.executable, GERBANG, "--semua"])
    periksa("parent style telanjang yatim: gerbang gagal (Audit 2919)",
            rc != 0 and rc != 124 and "TidakAdaSamaSekali2919" in out)
    shutil.rmtree(indukstyle, ignore_errors=True)

    hapusuji = klon(tmp, "hapusuji")
    berkas_hu = os.path.join(
        hapusuji, "app", "src", "test", "java", "com", "tasirin",
        "vaultwardenhost", "TgBotTest.java")
    with open(berkas_hu, "a", encoding="utf-8") as f:
        f.write("\n// PinGateTest\n")
    git(hapusuji, "rm", "-q", os.path.join(
        "app", "src", "test", "java", "com", "tasirin",
        "vaultwardenhost", "PinGateTest.java"))
    git(hapusuji, "add", "-A")
    rc, out = jalan(hapusuji, [sys.executable, GERBANG])
    periksa("berkas uji dihapus tapi masih disebut: gerbang gagal (Audit 2921)",
            rc != 0 and rc != 124 and "PinGateTest" in out and "masih disebut" in out)
    shutil.rmtree(hapusuji, ignore_errors=True)

    berkasbesar = klon(tmp, "berkasbesar")
    with open(os.path.join(berkasbesar, "catatan-besar.txt"), "w",
              encoding="utf-8") as f:
        f.write("x" * 1_200_000)
    rc, out = jalan(berkasbesar, [sys.executable, GERBANG, "--semua"])
    periksa("berkas teks >1 MB: gerbang gagal (Audit 2920)",
            rc != 0 and rc != 124 and "catatan-besar.txt" in out)
    shutil.rmtree(berkasbesar, ignore_errors=True)

    varianenv = klon(tmp, "varianenv")
    with open(os.path.join(varianenv, "config.env"), "w",
              encoding="utf-8") as f:
        f.write("TOKEN=contoh\n")
    rc, out = jalan(varianenv, [sys.executable, GERBANG, "--semua"])
    periksa("varian nama config.env: gerbang gagal (Audit 2920)",
            rc != 0 and rc != 124 and "config.env" in out)
    shutil.rmtree(varianenv, ignore_errors=True)

    kurungkts = klon(tmp, "kurungkts")
    berkas_kk = os.path.join(kurungkts, "build.gradle.kts")
    with open(berkas_kk, "a", encoding="utf-8") as f:
        f.write("\nval ujiKurung = )()(\n")
    rc, out = jalan(kurungkts, [sys.executable, GERBANG, "--semua"])
    periksa("kurung urutan rusak di .kts: gerbang gagal (Audit 2918)",
            rc != 0 and rc != 124 and "urutannya rusak" in out)
    shutil.rmtree(kurungkts, ignore_errors=True)


    acu = klon(tmp, "acuhantu")
    berkas_agen = os.path.join(acu, "AGENTS.md")
    with open(berkas_agen, encoding="utf-8") as f:
        isi_agen = f.read()
    with open(berkas_agen, "w", encoding="utf-8") as f:
        f.write(isi_agen + "\nAcuan hantu: `KelasHantu.metodeHantu` dan "
                "metodeHantuPolos() polos.\n")
    rc, out = jalan(acu, [sys.executable, GERBANG])
    periksa("acuan AGENTS tanpa () / tanpa backtick tertangkap (Audit 2929)",
            rc != 0 and rc != 124 and "metodeHantu" in out)
    shutil.rmtree(acu, ignore_errors=True)

    bekasuji = klon(tmp, "bekasuji")
    berkas_uji_baru = os.path.join(
        bekasuji, "app", "src", "test", "java", "com", "tasirin",
        "vaultwardenhost", "BekasNamaTest.java")
    os.makedirs(os.path.dirname(berkas_uji_baru), exist_ok=True)
    with open(berkas_uji_baru, "w", encoding="utf-8") as f:
        f.write("package com.tasirin.vaultwardenhost;\n\n"
                "public class BekasNamaTest {\n"
                "    static boolean batalTunda() { return true; }\n"
                "    @org.junit.Test public void pakai() {\n"
                "        org.junit.Assert.assertTrue(batalTunda());\n"
                "    }\n}\n")
    rc, out = jalan(bekasuji, [sys.executable, GERBANG, "--semua"])
    periksa("helper uji bernama bekas-hapusan tak lagi GAGAL palsu "
            "(Audit 2930)", rc == 0 and "bekas-hapusan" not in out,
            "" if rc == 0 else out[-400:])
    shutil.rmtree(bekasuji, ignore_errors=True)

    selaras1 = klon(tmp, "selaras1")
    berkas_am1 = os.path.join(
        selaras1, ".github", "workflows", "auto-merge.yml")
    with open(berkas_am1, encoding="utf-8") as f:
        isi_am1 = f.read()
    isi_am1b = isi_am1.replace("tools/uji-cek-cepat-1\\.py$|", "", 1)
    assert isi_am1b != isi_am1, "racun selaras1 tidak menembak"
    with open(berkas_am1, "w", encoding="utf-8") as f:
        f.write(isi_am1b)
    rc, out = jalan(selaras1,
                    [sys.executable, GERBANG, "--hanya-workflow"])
    periksa("pola HARUS_BUILD kurang satu path: gerbang gagal "
            "(Audit 3029)",
            rc != 0 and rc != 124 and "HARUS_BUILD" in out)
    shutil.rmtree(selaras1, ignore_errors=True)

    selaras0 = klon(tmp, "selaras0")
    rc, out = jalan(selaras0,
                    [sys.executable, GERBANG, "--hanya-workflow"])
    periksa("workflow bersih: cek selarasan lolos (Audit 3029)",
            rc == 0, "" if rc == 0 else out[-400:])
    shutil.rmtree(selaras0, ignore_errors=True)

    def _rentang2893_racun(klon_r):
        p = os.path.join(
            klon_r, "app", "src", "main", "java", "com",
            "tasirin", "vaultwardenhost", "Util.java")
        with open(p, encoding="utf-8") as f:
            isi = f.read()
        with open(p, "w", encoding="utf-8") as f:
            f.write(isi.replace(
                "public final class Util {",
                "public final class Util {\n"
                "    static String ujiRentang2893(String s) "
                "{ return s.toLowerCase(); }", 1))
        p2 = os.path.join(klon_r, "CHANGELOG.md")
        with open(p2, "a", encoding="utf-8") as f:
            f.write("\n")

    rentang2893a = klon(tmp, "rentang2893a")
    _rentang2893_racun(rentang2893a)
    git(rentang2893a, "add", "-A")
    git(rentang2893a, "commit", "-qm", "test: racun rentang uji")
    rc, out = jalan(rentang2893a,
                    [sys.executable, GERBANG,
                     "--rentang", "ngawur..banget"])
    periksa("rentang tak valid: cadangan HEAD~1..HEAD "
            "tetap menangkap racun (Audit 2893)",
            rc != 0 and rc != 124 and "rentang tak valid"
            in out and "tanpa Locale.US/" in out)
    shutil.rmtree(rentang2893a, ignore_errors=True)

    rentang2893b = klon(tmp, "rentang2893b")
    rc, out = jalan(rentang2893b,
                    [sys.executable, GERBANG,
                     "--rentang", "HEAD~1..HEAD"])
    periksa("rentang sah di pohon bersih: gerbang lolos "
            "(Audit 2893)", rc == 0,
            "" if rc == 0 else out[-400:])
    shutil.rmtree(rentang2893b, ignore_errors=True)

    rentang2893c = klon(tmp, "rentang2893c")
    git(rentang2893c, "checkout", "-q", "--orphan",
        "satu2893")
    git(rentang2893c, "add", "-A")
    git(rentang2893c, "commit", "-qm", "satu-satunya")
    rc, out = jalan(rentang2893c,
                    [sys.executable, GERBANG,
                     "--rentang", "HEAD~1..HEAD"])
    periksa("repo satu commit: gerbang gagal terang "
            "bukan lolos diam-diam (Audit 2893)",
            rc != 0 and rc != 124 and "tak terbaca" in out)
    shutil.rmtree(rentang2893c, ignore_errors=True)

    mode2894 = klon(tmp, "mode2894")
    _rentang2893_racun(mode2894)
    git(mode2894, "add", "-A")
    git(mode2894, "commit", "-qm", "racun mode uji")
    rc, out = jalan(mode2894,
                    [sys.executable, GERBANG,
                     "--hanya-workflow"])
    periksa("kontrak mode: --hanya-workflow buta racun "
            "diff Java (Audit 2894)", rc == 0,
            "" if rc == 0 else out[-400:])
    rc, out = jalan(mode2894,
                    [sys.executable, GERBANG, "--semua"])
    periksa("klon sama di --semua: racun diff tertangkap "
            "(Audit 2894)",
            rc != 0 and rc != 124 and "tanpa Locale.US/"
            in out)
    shutil.rmtree(mode2894, ignore_errors=True)

    def racun_workflow(nama, ubah, label, frag):
        klon_w = klon(tmp, nama)
        ubah(klon_w)
        git(klon_w, "add", "-A")
        git(klon_w, "commit", "-qm", "racun workflow uji")
        rc, out = jalan(klon_w,
                        [sys.executable, GERBANG,
                         "--hanya-workflow"])
        periksa(label, rc != 0 and rc != 124 and frag in out)
        shutil.rmtree(klon_w, ignore_errors=True)

    def _wf2908_labeler(klon_w):
        p = os.path.join(klon_w, ".github", "labeler.yml")
        with open(p, encoding="utf-8") as f:
            isi = f.read()
        with open(p, "w", encoding="utf-8") as f:
            f.write(isi.replace("app/src/main/**",
                                "app/src/ngawur2896/**", 1))
    def _wf2917_kuras(klon_w):
        p = os.path.join(klon_w, ".github", "workflows",
                         "label-audit.yml")
        with open(p, encoding="utf-8") as f:
            isi = f.read()
        with open(p, "w", encoding="utf-8") as f:
            f.write(isi.replace(
                "dipercaya=0",
                "dipercaya=0\n          "
                "x=$(cat <(echo uji))", 1))
    racun_workflow("wf2917kuras", _wf2917_kuras,
                   "substitusi proses di run: workflow: "
                   "gerbang gagal (Audit 2917)",
                   "substitusi proses")
    def _wf2889_komentar(klon_w):
        p = os.path.join(klon_w, ".github", "workflows",
                         "build-apk.yml")
        with open(p, encoding="utf-8") as f:
            isi = f.read()
        with open(p, "w", encoding="utf-8") as f:
            f.write(isi.replace(
                "python3 tools/verifikasi-patch.py --rentang",
                "# python3 tools/verifikasi-patch.py --rentang"))
    racun_workflow("wf2889komentar", _wf2889_komentar,
                   "langkah wajib dikomentari utuh: gerbang "
                   "gagal (Audit 2889)",
                   "kehilangan langkah wajib")

    racun_workflow("wf2908labeler", _wf2908_labeler,
                   "glob labeler tak cocok berkas apa pun: "
                   "gerbang gagal (Audit 2908)",
                   "tak mencocokkan satu pun")

    def _wf2908_dependabot(klon_w):
        p = os.path.join(klon_w, ".github", "dependabot.yml")
        with open(p, encoding="utf-8") as f:
            baris_w = f.read().splitlines(keepends=True)
        with open(p, "w", encoding="utf-8") as f:
            f.write("".join(l for l in baris_w
                            if "directory:" not in l))
    racun_workflow("wf2908dependabot", _wf2908_dependabot,
                   "dependabot tanpa directory: gerbang "
                   "gagal (Audit 2908)", "tanpa directory")

    def _wf2908_jobs(klon_w):
        p = os.path.join(klon_w, ".github", "workflows",
                         "gitleaks.yml")
        with open(p, encoding="utf-8") as f:
            isi = f.read()
        with open(p, "w", encoding="utf-8") as f:
            f.write(isi.replace("\njobs:", "\njobx:", 1))
    racun_workflow("wf2908jobs", _wf2908_jobs,
                   "workflow tanpa kunci jobs: gerbang "
                   "gagal (Audit 2908)", "tanpa kunci on/jobs")

    def racun_diff(nama, ubah, label, frag, harus_rc=1):
        klon_d = klon(tmp, nama)
        ubah(klon_d)
        git(klon_d, "add", "-A")
        git(klon_d, "commit", "-qm", "test: racun diff uji")
        rc, out = jalan(klon_d,
                        [sys.executable, GERBANG,
                         "--rentang", "HEAD~1..HEAD"])
        if harus_rc:
            ok = rc != 0 and rc != 124 and frag in out
        else:
            ok = rc == 0 and frag in out
        periksa(label, ok)
        shutil.rmtree(klon_d, ignore_errors=True)

    def _sentuh_changelog(klon_d):
        p = os.path.join(klon_d, "CHANGELOG.md")
        with open(p, "a", encoding="utf-8") as f:
            f.write("\n")

    def _jalur_util(klon_d):
        return os.path.join(
            klon_d, "app", "src", "main", "java", "com",
            "tasirin", "vaultwardenhost", "Util.java")

    def _diff2897_locale(klon_d):
        p = _jalur_util(klon_d)
        with open(p, encoding="utf-8") as f:
            isi = f.read()
        with open(p, "w", encoding="utf-8") as f:
            f.write(isi.replace(
                "public final class Util {",
                "public final class Util {\n"
                "    static String ujiLocale2897(String s) "
                "{ return s.toLowerCase(); }", 1))
        _sentuh_changelog(klon_d)
    racun_diff("diff2897locale", _diff2897_locale,
               "toLowerCase tanpa Locale di diff: gerbang "
               "gagal (Audit 2897)", "tanpa Locale.US/")

    def _diff2897_bekas(klon_d):
        p = _jalur_util(klon_d)
        with open(p, encoding="utf-8") as f:
            isi = f.read()
        with open(p, "w", encoding="utf-8") as f:
            f.write(isi.replace(
                "public final class Util {",
                "public final class Util {\n"
                "    static void ujiBekas2897() "
                "{ batalTunda(); }", 1))
        _sentuh_changelog(klon_d)
    racun_diff("diff2897bekas", _diff2897_bekas,
               "memanggil metode bekas hapusan di diff: "
               "gerbang gagal (Audit 2897)",
               "yang sudah dihapus")

    def _diff2897_izin(klon_d):
        p = os.path.join(klon_d, "app", "src", "main",
                         "AndroidManifest.xml")
        with open(p, encoding="utf-8") as f:
            isi = f.read()
        with open(p, "w", encoding="utf-8") as f:
            f.write(isi.replace(
                "</manifest>",
                "    <uses-permission android:name="
                "\"android.permission.CAMERA\" />\n"
                "</manifest>", 1))
        _sentuh_changelog(klon_d)
    racun_diff("diff2897izin", _diff2897_izin,
               "izin berbahaya baru di diff: gerbang "
               "gagal (Audit 2897)", "Izin berbahaya baru")

    def _diff2895_print(klon_d):
        p = _jalur_util(klon_d)
        with open(p, encoding="utf-8") as f:
            isi = f.read()
        with open(p, "w", encoding="utf-8") as f:
            f.write(isi.replace(
                "public final class Util {",
                "public final class Util {\n"
                "    static void ujiPrint2895() "
                "{ System.out.println(\"x\"); }", 1))
        _sentuh_changelog(klon_d)
    racun_diff("diff2895print", _diff2895_print,
               "System.out.println di diff: PERINGATAN "
               "terbit, gerbang lolos (Audit 2895)",
               "System.out.println di diff", harus_rc=0)

    def _diff2895_ujibaru(klon_d):
        p = _jalur_util(klon_d)
        with open(p, encoding="utf-8") as f:
            isi = f.read()
        with open(p, "w", encoding="utf-8") as f:
            f.write(isi.replace(
                "public final class Util {",
                "public final class Util {\n"
                "    static int ujiBaru2895(int x) "
                "{ return x + 1; }", 1))
        _sentuh_changelog(klon_d)
    racun_diff("diff2895ujibaru", _diff2895_ujibaru,
               "metode baru tanpa uji: PERINGATAN terbit, "
               "gerbang lolos (Audit 2895)",
               "tanpa jejak di app/src/test", harus_rc=0)

    def _diff2895_ujiubah(klon_d):
        p = _jalur_util(klon_d)
        with open(p, encoding="utf-8") as f:
            baris_u = f.read().splitlines(keepends=True)
        awal_u = next(i for i, l in enumerate(baris_u)
                      if "samaHurufAscii(char" in l)
        for i in range(awal_u, len(baris_u)):
            if "a + 32" in baris_u[i]:
                baris_u[i] = baris_u[i].replace(
                    "a + 32", "a + 33", 1)
                break
        with open(p, "w", encoding="utf-8") as f:
            f.write("".join(baris_u))
        _sentuh_changelog(klon_d)
    racun_diff("diff2895ujiubah", _diff2895_ujiubah,
               "metode diubah tanpa uji: PERINGATAN "
               "terbit, gerbang lolos (Audit 2895)",
               "diubah tapi tanpa jejak", harus_rc=0)

    def _diff2895_definisi(klon_d):
        for folder in ("values", "values-in"):
            p = os.path.join(klon_d, "app", "src", "main",
                             "res", folder, "strings.xml")
            with open(p, encoding="utf-8") as f:
                isi = f.read()
            with open(p, "w", encoding="utf-8") as f:
                f.write(isi.replace(
                    "</resources>",
                    "    <string name=\"yatim_2895\">"
                    "x</string>\n</resources>", 1))
        _sentuh_changelog(klon_d)
    racun_diff("diff2895definisi", _diff2895_definisi,
               "definisi string baru tanpa acuan: "
               "PERINGATAN terbit, gerbang lolos "
               "(Audit 2895)", "tanpa acuan", harus_rc=0)

    def _diff2895_boros(klon_d):
        p = _jalur_util(klon_d)
        with open(p, encoding="utf-8") as f:
            isi = f.read()
        with open(p, "w", encoding="utf-8") as f:
            f.write(isi.replace(
                "public final class Util {",
                "public final class Util {\n"
                "    static boolean ujiBoros2895(String s) "
                "{ return s.matches(\"[0-9]+\"); }", 1))
        _sentuh_changelog(klon_d)
    racun_diff("diff2895boros", _diff2895_boros,
               ".matches( di diff: PERINGATAN pola boros "
               "terbit, gerbang lolos (Audit 2895)",
               "Pola boros di baris baru", harus_rc=0)

    def _diff2895_izin(klon_d):
        p = os.path.join(klon_d, "app", "src", "main",
                         "AndroidManifest.xml")
        with open(p, encoding="utf-8") as f:
            isi = f.read()
        with open(p, "w", encoding="utf-8") as f:
            f.write(isi.replace(
                "</manifest>",
                "    <uses-permission android:name="
                "\"android.permission.VIBRATE\" />\n"
                "</manifest>", 1))
        _sentuh_changelog(klon_d)
    racun_diff("diff2895izin", _diff2895_izin,
               "izin baru tak berbahaya di diff: "
               "PERINGATAN terbit, gerbang lolos "
               "(Audit 2895)", "Izin baru", harus_rc=0)

    format2922 = klon(tmp, "format2922")
    p_fmt = os.path.join(format2922, "CHANGELOG.md")
    with open(p_fmt, "a", encoding="utf-8") as f:
        f.write("\n")
    git(format2922, "add", "-A")
    git(format2922, "commit", "-qm",
        "ubah sembarang tanpa awalan jenis")
    rc, out = jalan(format2922,
                    [sys.executable, GERBANG,
                     "--rentang", "HEAD~1..HEAD"])
    periksa("subjek commit tanpa awalan jenis: gerbang "
            "gagal (Audit 2922)",
            rc != 0 and rc != 124
            and "format aturan repo #3" in out)
    shutil.rmtree(format2922, ignore_errors=True)

    def _diff2922_komentar(klon_d):
        _sentuh_changelog(klon_d)
        p = _jalur_util(klon_d)
        with open(p, encoding="utf-8") as f:
            isi = f.read()
        with open(p, "w", encoding="utf-8") as f:
            f.write(isi.replace(
                "public final class Util {",
                "public final class Util {\n"
                "    // komentar bebas tanpa nomor audit",
                1))
    racun_diff("diff2922komentar", _diff2922_komentar,
               "komentar Java di luar gaya Audit: gerbang "
               "gagal (Audit 2922)", "di luar gaya")

    def _diff2922_komentar_sah(klon_d):
        _sentuh_changelog(klon_d)
        p = _jalur_util(klon_d)
        with open(p, encoding="utf-8") as f:
            isi = f.read()
        with open(p, "w", encoding="utf-8") as f:
            f.write(isi.replace(
                "public final class Util {",
                "public final class Util {\n"
                "    // Audit 2922: metode penanda skenario\n"
                "    // lanjutan bloknya ikut sah.\n"
                "    static int ujiKomentar2922() "
                "{ return 29; }", 1))
    racun_diff("diff2922sah", _diff2922_komentar_sah,
               "blok komentar // Audit NNNN: beserta "
               "lanjutannya: gerbang lolos (Audit 2922)",
               "Hasil: LOLOS", harus_rc=0)

    def _diff2922_api(klon_d):
        _sentuh_changelog(klon_d)
        p = _jalur_util(klon_d)
        with open(p, encoding="utf-8") as f:
            isi = f.read()
        with open(p, "w", encoding="utf-8") as f:
            f.write(isi.replace(
                "public final class Util {",
                "public final class Util {\n"
                "    static java.util.List<String> "
                "ujiApi2922() { return "
                "java.util.List.of(\"a\"); }", 1))
    racun_diff("diff2922api", _diff2922_api,
               "List.of di baris baru main: gerbang gagal "
               "(Audit 2922)", "API di atas 21")

    def _tanam_string2922(klon_d, nama, teks_values,
                          teks_in):
        # Nama wajib ditanam di KEDUA strings.xml - gerbang
        # paritas nama (Audit 765) menggagalkan nama yang
        # cuma ada di satu sisi.
        for folder, teks in (("values", teks_values),
                             ("values-in", teks_in)):
            p = os.path.join(klon_d, "app", "src", "main",
                             "res", folder, "strings.xml")
            with open(p, encoding="utf-8") as f:
                isi = f.read()
            with open(p, "w", encoding="utf-8") as f:
                f.write(isi.replace(
                    "</resources>",
                    '    <string name="' + nama + '">'
                    + teks + "</string>\n</resources>", 1))

    def _diff2922_bahasa(klon_d):
        _sentuh_changelog(klon_d)
        _tanam_string2922(
            klon_d, "uji_bahasa2922",
            "Silakan periksa koneksi anda dan coba lagi",
            "Silakan periksa koneksi anda dan coba lagi")
    racun_diff("diff2922bahasa", _diff2922_bahasa,
               "string UI Indonesia baru di values/: "
               "PERINGATAN bahasa terbit, gerbang lolos "
               "(Audit 2922)", "terindikasi Bahasa Indonesia",
               harus_rc=0)

    bahasa2922ok = klon(tmp, "bahasa2922ok")
    _sentuh_changelog(bahasa2922ok)
    _tanam_string2922(
        bahasa2922ok, "uji_bahasa2922ok",
        "Please check your connection and try again",
        "Silakan periksa koneksi anda dan coba lagi")
    git(bahasa2922ok, "add", "-A")
    git(bahasa2922ok, "commit", "-qm", "test: string Inggris")
    rc, out = jalan(bahasa2922ok,
                    [sys.executable, GERBANG,
                     "--rentang", "HEAD~1..HEAD"])
    periksa("string UI Inggris baru di values/: tanpa "
            "PERINGATAN bahasa (Audit 2922)",
            rc == 0 and rc != 124
            and "terindikasi Bahasa" not in out)
    shutil.rmtree(bahasa2922ok, ignore_errors=True)

    def _diff2836_audit_baru(klon_d):
        _sentuh_changelog(klon_d)
        p = _jalur_util(klon_d)
        with open(p, encoding="utf-8") as f:
            isi = f.read()
        with open(p, "w", encoding="utf-8") as f:
            f.write(isi.replace(
                "public final class Util {",
                "public final class Util {\n"
                "    // Audit " + "99" + "99: penanda skenario "
                "jejak audit", 1))
    racun_diff("diff2836audit", _diff2836_audit_baru,
               "nomor Audit baru tak disebut commit: "
               "PERINGATAN terbit, gerbang tetap lolos "
               "(Audit 2836)", "tak disebut di pesan commit",
               harus_rc=0)

    def racun_pohon(nama, ubah, label, frag):
        klon_p = klon(tmp, nama)
        ubah(klon_p)
        git(klon_p, "add", "-A")
        git(klon_p, "commit", "-qm", "racun pohon uji")
        rc, out = jalan(klon_p,
                        [sys.executable, GERBANG, "--semua"])
        periksa(label, rc != 0 and rc != 124 and frag in out)
        shutil.rmtree(klon_p, ignore_errors=True)

    def _pohon2896_izin(klon_p):
        p = os.path.join(klon_p, "app", "src", "main",
                         "AndroidManifest.xml")
        with open(p, encoding="utf-8") as f:
            isi = f.read()
        with open(p, "w", encoding="utf-8") as f:
            f.write(isi.replace(
                "android.permission.RECEIVE_BOOT_COMPLETED",
                "android.permission.VIBRATE", 1))
    racun_pohon("pohon2896izin", _pohon2896_izin,
                "izin wajib hilang dari manifest: gerbang "
                "gagal (Audit 2896)", "Izin wajib")

    def _pohon2896_target(klon_p):
        p = os.path.join(klon_p, "app", "build.gradle.kts")
        with open(p, encoding="utf-8") as f:
            isi = f.read()
        with open(p, "w", encoding="utf-8") as f:
            f.write(isi.replace("targetSdk = 28",
                                "targetSdk = 29", 1))
    racun_pohon("pohon2896target", _pohon2896_target,
                "targetSdk naik ke 29: gerbang gagal "
                "(Audit 2896)", "targetSdk")

    def _pohon2896_xml(klon_p):
        p = os.path.join(klon_p, "app", "src", "main",
                         "res", "values", "strings.xml")
        with open(p, "a", encoding="utf-8") as f:
            f.write("<string name=\"rusak2896\">tak ditutup\n")
    racun_pohon("pohon2896xml", _pohon2896_xml,
                "strings.xml rusak: gerbang gagal "
                "(Audit 2896)", "XML")

    def _pohon2896_terjemah(klon_p):
        p = os.path.join(klon_p, "app", "src", "main",
                         "res", "values", "strings.xml")
        with open(p, encoding="utf-8") as f:
            isi = f.read()
        with open(p, "w", encoding="utf-8") as f:
            f.write(isi.replace(
                "</resources>",
                "    <string name=\"hanya_values_2896\">"
                "x</string>\n</resources>", 1))
    racun_pohon("pohon2896terjemah", _pohon2896_terjemah,
                "string hanya di values: gerbang gagal "
                "(Audit 2896)", "values-in kurang string")

    def _pohon2896_ganda(klon_p):
        for folder in ("values", "values-in"):
            p = os.path.join(klon_p, "app", "src", "main",
                             "res", folder, "strings.xml")
            with open(p, encoding="utf-8") as f:
                isi = f.read()
            with open(p, "w", encoding="utf-8") as f:
                f.write(isi.replace(
                    "</resources>",
                    "    <string name=\"app_name\">"
                    "ganda</string>\n</resources>", 1))
    racun_pohon("pohon2896ganda", _pohon2896_ganda,
                "definisi string ganda: gerbang gagal "
                "(Audit 2896)", "Definisi ganda")

    def _pohon2896_impor(klon_p):
        p = os.path.join(klon_p, "app", "src", "main",
                         "java", "com", "tasirin",
                         "vaultwardenhost", "Util.java")
        with open(p, encoding="utf-8") as f:
            isi = f.read()
        kandidat = "java.util.LinkedList"
        assert "LinkedList" not in isi
        with open(p, "w", encoding="utf-8") as f:
            f.write(isi.replace(
                "package com.tasirin.vaultwardenhost;",
                "package com.tasirin.vaultwardenhost;\n\n"
                "import " + kandidat + ";", 1))
    racun_pohon("pohon2896impor", _pohon2896_impor,
                "import mati di Java: gerbang gagal "
                "(Audit 2896)", "Import mati")

    def _pohon2896_agents(klon_p):
        p = os.path.join(klon_p, "AGENTS.md")
        with open(p, "a", encoding="utf-8") as f:
            f.write("\nGunakan metodeNgawur2896() untuk "
                    "semua hal.\n")
    racun_pohon("pohon2896agents", _pohon2896_agents,
                "AGENTS menyebut metode tak ada: gerbang "
                "gagal (Audit 2896)",
                "AGENTS.md menyebut metode")

    def _pohon2896_manifest(klon_p):
        p = os.path.join(klon_p, "app", "src", "main",
                         "AndroidManifest.xml")
        with open(p, encoding="utf-8") as f:
            isi = f.read()
        with open(p, "w", encoding="utf-8") as f:
            f.write(isi.replace(
                "</application>",
                "        <activity android:name="
                "\".KelasNgawur2896\" "
                "android:exported=\"false\" />\n"
                "    </application>", 1))
    racun_pohon("pohon2896manifest", _pohon2896_manifest,
                "komponen manifest tanpa kelas: gerbang "
                "gagal (Audit 2896)",
                "Komponen manifest tanpa kelas")

    naik2907a = klon(tmp, "naik2907a")
    berkas_main = os.path.join(
        naik2907a, "app", "src", "main", "java", "com",
        "tasirin", "vaultwardenhost", "Util.java")
    with open(berkas_main, encoding="utf-8") as f:
        isi_main = f.read()
    with open(berkas_main, "w", encoding="utf-8") as f:
        f.write(isi_main + "\n")
    git(naik2907a, "add", "-A")
    git(naik2907a, "commit", "-qm", "sentuh sumber tanpa changelog")
    rc, out = jalan(naik2907a,
                    [sys.executable, GERBANG,
                     "--rentang", "HEAD~1..HEAD"])
    periksa("sumber berubah tanpa CHANGELOG: gerbang gagal "
            "(Audit 2907)",
            rc != 0 and rc != 124 and "CHANGELOG" in out)
    shutil.rmtree(naik2907a, ignore_errors=True)

    naik2907b = klon(tmp, "naik2907b")
    berkas_memo = None
    for akar_b, _, berkas_b in os.walk(
            os.path.join(naik2907b, "app", "src", "main", "java")):
        if "MemoVersiCocok.java" in berkas_b:
            berkas_memo = os.path.join(akar_b, "MemoVersiCocok.java")
    assert berkas_memo, "MemoVersiCocok.java tak ditemukan"
    with open(berkas_memo, encoding="utf-8") as f:
        baris_memo = f.read().splitlines(keepends=True)
    awal_m = next(i for i, l in enumerate(baris_memo)
                  if "boolean cocokTahap1(" in l)
    akhir_m = next(i for i in range(awal_m + 1, len(baris_memo))
                   if baris_memo[i].rstrip() == "    }")
    with open(berkas_memo, "w", encoding="utf-8") as f:
        f.write("".join(baris_memo[:awal_m]
                        + baris_memo[akhir_m + 1:]))
    berkas_cl = os.path.join(naik2907b, "CHANGELOG.md")
    with open(berkas_cl, encoding="utf-8") as f:
        isi_cl = f.read()
    with open(berkas_cl, "w", encoding="utf-8") as f:
        f.write(isi_cl + "\n")
    git(naik2907b, "add", "-A")
    git(naik2907b, "commit", "-qm", "hapus metode tanpa catatan")
    rc, out = jalan(naik2907b,
                    [sys.executable, GERBANG,
                     "--rentang", "HEAD~1..HEAD"])
    periksa("metode terhapus tanpa bekas-hapusan: gerbang "
            "gagal (Audit 2907)",
            rc != 0 and rc != 124 and "bekas" in out.lower())
    shutil.rmtree(naik2907b, ignore_errors=True)

    pohon2900 = klon(tmp, "pohon2900")
    kepala = "-----BEGIN RSA " + "PRIVATE" + " KEY-----"
    with open(os.path.join(pohon2900, "kunci-bocor.txt"),
              "w", encoding="utf-8") as f:
        f.write(kepala + "\n")
    git(pohon2900, "add", "-A")
    git(pohon2900, "commit", "-qm", "tanam kunci uji")
    with open(os.path.join(pohon2900, "sepele.txt"),
              "w", encoding="utf-8") as f:
        f.write("sepele\n")
    git(pohon2900, "add", "-A")
    git(pohon2900, "commit", "-qm", "commit sepele")
    rc, out = jalan(pohon2900,
                    [sys.executable, GERBANG, "--semua"])
    periksa("header kunci ter-commit lama: --semua gagal "
            "(Audit 2900)",
            rc != 0 and rc != 124 and "kunci" in out)
    shutil.rmtree(pohon2900, ignore_errors=True)

    selaras2 = klon(tmp, "selaras2")
    berkas_cw2 = os.path.join(
        selaras2, ".github", "workflows", "cek-wajib.yml")
    with open(berkas_cw2, encoding="utf-8") as f:
        isi_cw2 = f.read()
    isi_cw2b = isi_cw2.replace("|.editorconfig", "", 1)
    assert isi_cw2b != isi_cw2, "racun selaras2 tidak menembak"
    with open(berkas_cw2, "w", encoding="utf-8") as f:
        f.write(isi_cw2b)
    rc, out = jalan(selaras2,
                    [sys.executable, GERBANG, "--hanya-workflow"])
    periksa("daftar case cek-wajib kurang satu: gerbang gagal "
            "(Audit 3028)",
            rc != 0 and rc != 124 and "cek-wajib" in out)
    shutil.rmtree(selaras2, ignore_errors=True)

def _bagian_akhir(tmp):
    skenario_substitusi(tmp, 'sub716',
                        '> >(openssl sha256 -list; cat >> "$GITHUB_OUTPUT")',
                        'openssl sha256 -list: gerbang lolos (Audit 1583)', True)

    skenario_substitusi(tmp, 'sub717',
                        '> >(gpg --batch --cert-notation nama@uji=nilai --clearsign; cat >> "$GITHUB_OUTPUT")',
                        'gpg --cert-notation di substitusi proses: gerbang gagal (Audit 1587)', False)

    skenario_substitusi(tmp, 'sub718',
                        '> >(gpg --batch -N nama@uji=nilai --clearsign; cat >> "$GITHUB_OUTPUT")',
                        'gpg -N di substitusi proses: gerbang gagal (Audit 1588)', False)

    skenario_substitusi(tmp, 'sub719',
                        '> >(gpg --remote-user Uji91 -r Uji91 -e; cat >> "$GITHUB_OUTPUT")',
                        'gpg --remote-user di substitusi proses: gerbang gagal (Audit 1589)', False)

    skenario_substitusi(tmp, 'sub720',
                        '> >(gpg --batch --ungroup grupuji --clearsign; cat >> "$GITHUB_OUTPUT")',
                        'gpg --ungroup di substitusi proses: gerbang gagal (Audit 1590)', False)

    skenario_substitusi(tmp, 'sub721',
                        '> >(gpg --batch --options --clearsign; cat >> "$GITHUB_OUTPUT")',
                        'gpg --options --clearsign (modus tertelan nilai): gerbang lolos (Audit 1591)', True)

    skenario_substitusi(tmp, 'sub722',
                        '> >(gawk \'{print}\'; cat >> "$GITHUB_OUTPUT")',
                        'varian GNU awk gawk di substitusi proses: penguras — gerbang gagal (Audit 1638)', False)

    skenario_substitusi(tmp, 'sub723',
                        '> >(gawk \'{print}\' data.txt; cat >> "$GITHUB_OUTPUT")',
                        'gawk beroperand berkas data: gerbang lolos (Audit 1638)', True)

    skenario_substitusi(tmp, 'sub724',
                        '> >(pgawk \'{print}\'; cat >> "$GITHUB_OUTPUT")',
                        'varian GNU awk pgawk di substitusi proses: penguras — gerbang gagal (Audit 1638)', False)

    skenario_substitusi(tmp, 'sub725',
                        '> >(igawk \'{print}\'; cat >> "$GITHUB_OUTPUT")',
                        'varian GNU awk igawk di substitusi proses: penguras — gerbang gagal (Audit 1638)', False)

    skenario_substitusi(tmp, 'sub726',
                        '> >(gpg2 --encrypt-to Uji92 -r Uji92 -e; cat >> "$GITHUB_OUTPUT")',
                        'gpg2 dinilai sama dengan gpg di substitusi proses: penguras — gerbang gagal (Audit 1638)', False)

    skenario_substitusi(tmp, 'sub727',
                        '> >(zstd; cat >> "$GITHUB_OUTPUT")',
                        'kompresor zstd tanpa operand di substitusi proses: penguras — gerbang gagal (Audit 1638)', False)

    skenario_substitusi(tmp, 'sub728',
                        '> >(zstd berkas.bin; cat >> "$GITHUB_OUTPUT")',
                        'zstd beroperand berkas: gerbang lolos (Audit 1638)', True)

    skenario_substitusi(tmp, 'sub729',
                        '> >(pigz; cat >> "$GITHUB_OUTPUT")',
                        'kompresor pigz tanpa operand di substitusi proses: penguras — gerbang gagal (Audit 1638)', False)

    skenario_substitusi(tmp, 'sub730',
                        '> >(ruby; cat >> "$GITHUB_OUTPUT")',
                        'interpreter ruby tanpa skrip di substitusi proses: penguras — gerbang gagal (Audit 1638)', False)

    skenario_substitusi(tmp, 'sub731',
                        '> >(ruby skrip.rb; cat >> "$GITHUB_OUTPUT")',
                        'ruby beroperand berkas skrip: gerbang lolos (Audit 1638)', True)

    skenario_substitusi(tmp, 'sub732',
                        '> >(php; cat >> "$GITHUB_OUTPUT")',
                        'interpreter php tanpa skrip di substitusi proses: penguras — gerbang gagal (Audit 1638)', False)

    skenario_substitusi(tmp, 'sub733',
                        '> >(lua; cat >> "$GITHUB_OUTPUT")',
                        'interpreter lua tanpa skrip di substitusi proses: penguras — gerbang gagal (Audit 1638)', False)

    skenario_substitusi(tmp, 'sub734',
                        '> >(ash; cat >> "$GITHUB_OUTPUT")',
                        'shell ash tanpa skrip di substitusi proses: penguras — gerbang gagal (Audit 1638)', False)

    skenario_substitusi(tmp, 'sub735',
                        '> >(ksh; cat >> "$GITHUB_OUTPUT")',
                        'shell ksh tanpa skrip di substitusi proses: penguras — gerbang gagal (Audit 1638)', False)

    skenario_substitusi(tmp, 'sub736',
                        '> >(fish; cat >> "$GITHUB_OUTPUT")',
                        'shell fish tanpa skrip di substitusi proses: penguras — gerbang gagal (Audit 1638)', False)

    skenario_substitusi(tmp, 'sub737',
                        '> >(gpgv --keyring keyring.gpg; cat >> "$GITHUB_OUTPUT")',
                        'gpgv tanpa operand data di substitusi proses: penguras — gerbang gagal (Audit 1639)', False)

    skenario_substitusi(tmp, 'sub738',
                        '> >(gpgv data.sig; cat >> "$GITHUB_OUTPUT")',
                        'gpgv beroperand berkas data: gerbang lolos (Audit 1639)', True)

    skenario_substitusi(tmp, 'sub739',
                        '> >(gawk -i inplace \'{print}\'; cat >> "$GITHUB_OUTPUT")',
                        'gawk -i bernilai GNU di substitusi proses: penguras — gerbang gagal (Audit 1640)', False)

    skenario_substitusi(tmp, 'sub740',
                        '> >(gawk -l ext \'{print}\'; cat >> "$GITHUB_OUTPUT")',
                        'gawk -l bernilai GNU di substitusi proses: penguras — gerbang gagal (Audit 1640)', False)

    skenario_substitusi(tmp, 'sub741',
                        '> >(ruby -I lib; cat >> "$GITHUB_OUTPUT")',
                        'ruby -I bernilai di substitusi proses: penguras — gerbang gagal (Audit 1641)', False)

    skenario_substitusi(tmp, 'sub742',
                        '> >(php -d x=y; cat >> "$GITHUB_OUTPUT")',
                        'php -d bernilai di substitusi proses: penguras — gerbang gagal (Audit 1641)', False)

    skenario_substitusi(tmp, 'sub743',
                        '> >(lua -l lib; cat >> "$GITHUB_OUTPUT")',
                        'lua -l bernilai di substitusi proses: penguras — gerbang gagal (Audit 1641)', False)

    skenario_substitusi(tmp, 'sub744',
                        '> >(ruby -I lib skrip.rb; cat >> "$GITHUB_OUTPUT")',
                        'ruby -I bernilai + berkas skrip: gerbang lolos (Audit 1641)', True)

    skenario_substitusi(tmp, 'sub745',
                        '> >(zstd -M 512MB; cat >> "$GITHUB_OUTPUT")',
                        'zstd -M bernilai di substitusi proses: penguras — gerbang gagal (Audit 1642)', False)

    skenario_substitusi(tmp, 'sub746',
                        '> >(zstd --format gzip; cat >> "$GITHUB_OUTPUT")',
                        'zstd --format bernilai di substitusi proses: penguras — gerbang gagal (Audit 1642)', False)

    skenario_substitusi(tmp, 'sub747',
                        '> >(zstdmt; cat >> "$GITHUB_OUTPUT")',
                        'varian zstdmt tanpa operand di substitusi proses: penguras — gerbang gagal (Audit 1642)', False)

    skenario_substitusi(tmp, 'sub748',
                        '> >(zstdgrep pola; cat >> "$GITHUB_OUTPUT")',
                        'zstdgrep tanpa operand berkas di substitusi proses: penguras — gerbang gagal (Audit 1642)', False)

    skenario_substitusi(tmp, 'sub749',
                        '> >(zstdgrep pola berkas.zst; cat >> "$GITHUB_OUTPUT")',
                        'zstdgrep beroperand berkas: gerbang lolos (Audit 1642)', True)

    skenario_substitusi(tmp, 'sub750',
                        '> >(gpgv --weak-digest SHA1; cat >> "$GITHUB_OUTPUT")',
                        'gpgv --weak-digest bernilai di substitusi proses: penguras — gerbang gagal (Audit 1648)', False)

    skenario_substitusi(tmp, 'sub751',
                        '> >(gpgv --output hasil; cat >> "$GITHUB_OUTPUT")',
                        'gpgv --output bernilai di substitusi proses: penguras — gerbang gagal (Audit 1648)', False)

    skenario_substitusi(tmp, 'sub752',
                        '> >(gpgv data.sig -; cat >> "$GITHUB_OUTPUT")',
                        "gpgv sigfile + operand '-' di substitusi proses: data dari stdin, penguras — gerbang gagal (Audit 1649)", False)

    skenario_substitusi(tmp, 'sub753',
                        '> >(gpgv data.sig data.txt; cat >> "$GITHUB_OUTPUT")',
                        'gpgv dua operand berkas: gerbang lolos (Audit 1649)', True)

    skenario_substitusi(tmp, 'sub754',
                        '> >(ruby -C dir; cat >> "$GITHUB_OUTPUT")',
                        'ruby -C bernilai di substitusi proses: penguras — gerbang gagal (Audit 1650)', False)

    skenario_substitusi(tmp, 'sub755',
                        '> >(ruby -E UTF-8; cat >> "$GITHUB_OUTPUT")',
                        'ruby -E bernilai di substitusi proses: penguras — gerbang gagal (Audit 1650)', False)

    skenario_substitusi(tmp, 'sub756',
                        '> >(php -z ext.so; cat >> "$GITHUB_OUTPUT")',
                        'php -z bernilai di substitusi proses: penguras — gerbang gagal (Audit 1651)', False)

    skenario_substitusi(tmp, 'sub757',
                        '> >(php -R \'echo 1;\'; cat >> "$GITHUB_OUTPUT")',
                        'php -R pemroses baris di substitusi proses: penguras — gerbang gagal (Audit 1651)', False)

    skenario_substitusi(tmp, 'sub758',
                        '> >(dgawk -f prog.awk; cat >> "$GITHUB_OUTPUT")',
                        'dgawk debugger di substitusi proses: perintah dari stdin, penguras — gerbang gagal (Audit 1652)', False)

    skenario_substitusi(tmp, 'sub759',
                        '> >(dgawk -f prog.awk data.txt; cat >> "$GITHUB_OUTPUT")',
                        'dgawk beroperand berkas tetap membaca perintah debug dari stdin: penguras — gerbang gagal (Audit 1652)', False)

    skenario_substitusi(tmp, 'sub760',
                        '> >(ruby3.2 -I lib; cat >> "$GITHUB_OUTPUT")',
                        'ruby berversi -I bernilai di substitusi proses: penguras — gerbang gagal (Audit 1653)', False)

    skenario_substitusi(tmp, 'sub761',
                        '> >(php8.2 -d x=y; cat >> "$GITHUB_OUTPUT")',
                        'php berversi -d bernilai di substitusi proses: penguras — gerbang gagal (Audit 1653)', False)

    skenario_substitusi(tmp, 'sub762',
                        '> >(lua5.4 -l lib; cat >> "$GITHUB_OUTPUT")',
                        'lua berversi -l bernilai di substitusi proses: penguras — gerbang gagal (Audit 1653)', False)

    skenario_substitusi(tmp, 'sub763',
                        '> >(gpgv --debug all; cat >> "$GITHUB_OUTPUT")',
                        'gpgv --debug bernilai di substitusi proses: penguras — gerbang gagal (Audit 1658)', False)

    skenario_substitusi(tmp, 'sub764',
                        '> >(gpgv --assert-pubkey-algo eddsa; cat >> "$GITHUB_OUTPUT")',
                        'gpgv --assert-pubkey-algo bernilai di substitusi proses: penguras — gerbang gagal (Audit 1658)', False)

    skenario_substitusi(tmp, 'sub765',
                        '> >(gpgv - data.txt; cat >> "$GITHUB_OUTPUT")',
                        "gpgv '-' tanda tangan dari stdin di substitusi proses: penguras — gerbang gagal (Audit 1659)", False)

    skenario_substitusi(tmp, 'sub766',
                        '> >(luajit -l lib; cat >> "$GITHUB_OUTPUT")',
                        'luajit -l bernilai di substitusi proses: penguras — gerbang gagal (Audit 1660)', False)

    skenario_substitusi(tmp, 'sub767',
                        '> >(luajit skrip.lua; cat >> "$GITHUB_OUTPUT")',
                        'luajit beroperand skrip: gerbang lolos (Audit 1660)', True)

    skenario_substitusi(tmp, 'sub768',
                        '> >(ruby -W skrip.rb; cat >> "$GITHUB_OUTPUT")',
                        'ruby -W token terpisah adalah berkas skrip: gerbang lolos (Audit 1661)', True)

    skenario_substitusi(tmp, 'sub769',
                        '> >(lua -W skrip.lua; cat >> "$GITHUB_OUTPUT")',
                        'lua -W bendera, token terpisah berkas skrip: gerbang lolos (Audit 1661)', True)

    skenario_substitusi(tmp, 'sub770',
                        '> >(gpgv sig - data; cat >> "$GITHUB_OUTPUT")',
                        "gpgv tiga operand dengan '-' kedua di substitusi proses: penguras — gerbang gagal (Audit 1671)", False)

    skenario_substitusi(tmp, 'sub771',
                        '> >(gpgv sig data ekstra; cat >> "$GITHUB_OUTPUT")',
                        'gpgv tiga operand berkas: gerbang lolos (Audit 1671)', True)

    skenario_substitusi(tmp, 'sub772',
                        '> >(luajit -j dump; cat >> "$GITHUB_OUTPUT")',
                        'luajit -j bernilai terpisah di substitusi proses: penguras — gerbang gagal (Audit 1672)', False)

    skenario_substitusi(tmp, 'sub773',
                        '> >(lua -j dump; cat >> "$GITHUB_OUTPUT")',
                        'lua tak punya -j, token berikutnya berkas skrip: gerbang lolos (Audit 1672)', True)

    skenario_substitusi(tmp, 'sub774',
                        '> >(luajit -b; cat >> "$GITHUB_OUTPUT")',
                        'luajit -b telanjang usage lalu keluar: gerbang lolos (Audit 1673)', True)

    skenario_substitusi(tmp, 'sub775',
                        '> >(luajit -b -l skrip.lua; cat >> "$GITHUB_OUTPUT")',
                        'luajit -b -l melist berkas, bukan stdin: gerbang lolos (Audit 1673)', True)

    skenario_substitusi(tmp, 'sub776',
                        '> >(luajit -b - out; cat >> "$GITHUB_OUTPUT")',
                        "luajit -b masukan '-' membaca stdin: penguras — gerbang gagal (Audit 1673)", False)

    skenario_substitusi(tmp, 'sub777',
                        '> >(python3 -Wignore skrip.py; cat >> "$GITHUB_OUTPUT")',
                        'python -Wignore nilai menempel, bukan bundel: gerbang lolos (Audit 1674)', True)

    skenario_substitusi(tmp, 'sub778',
                        '> >(gpgv --warranty; cat >> "$GITHUB_OUTPUT")',
                        'gpgv --warranty meta cetak lalu keluar: gerbang lolos (Audit 1675)', True)

    skenario_substitusi(tmp, 'sub779',
                        '> >(gpgv sig data -; cat >> "$GITHUB_OUTPUT")',
                        "gpgv konkatenasi '-' operand ketiga di substitusi proses: penguras — gerbang gagal (Audit 1688)", False)

    skenario_substitusi(tmp, 'sub780',
                        '> >(gpgv sig data e1; cat >> "$GITHUB_OUTPUT")',
                        "gpgv tiga operand berkas tanpa '-': gerbang lolos (Audit 1688)", True)

    skenario_substitusi(tmp, 'sub781',
                        '> >(gpgv --enable-special-filenames sig -&0; cat >> "$GITHUB_OUTPUT")',
                        'gpgv nama khusus -&0 membaca fd 0 di substitusi proses: penguras — gerbang gagal (Audit 1689)', False)

    skenario_substitusi(tmp, 'sub782',
                        '> >(gpgv -o --warranty sig -; cat >> "$GITHUB_OUTPUT")',
                        "gpgv --warranty sebagai NILAI -o bukan meta; '-' menguras: gerbang gagal (Audit 1690)", False)

    skenario_substitusi(tmp, 'sub783',
                        '> >(gpgv --dump-option-table; cat >> "$GITHUB_OUTPUT")',
                        'gpgv --dump-option-table meta cetak lalu keluar: gerbang lolos (Audit 1691)', True)

    skenario_substitusi(tmp, 'sub784',
                        '> >(python3 -sWignore skrip.py; cat >> "$GITHUB_OUTPUT")',
                        'python -Wignore di tengah bundel adalah nilai, bukan bendera: gerbang lolos (Audit 1692)', True)

    skenario_substitusi(tmp, 'sub785',
                        '> >(python3 -i -m calendar; cat >> "$GITHUB_OUTPUT")',
                        'python -i -m modul: REPL sesudah modul menguras — gerbang gagal (Audit 1693)', False)

    skenario_substitusi(tmp, 'sub786',
                        '> >(python3 -Z; cat >> "$GITHUB_OUTPUT")',
                        'python opsi satu huruf tak dikenal galat lalu keluar: gerbang lolos (Audit 1694)', True)

    skenario_substitusi(tmp, 'sub787',
                        '> >(python3 --bogus; cat >> "$GITHUB_OUTPUT")',
                        'python opsi panjang tak dikenal galat lalu keluar: gerbang lolos (Audit 1694)', True)

    skenario_substitusi(tmp, 'sub788',
                        '> >(lua -b; cat >> "$GITHUB_OUTPUT")',
                        'lua opsi tak dikenal usage lalu keluar: gerbang lolos (Audit 1697)', True)

    skenario_substitusi(tmp, 'sub789',
                        '> >(python3 --version; cat >> "$GITHUB_OUTPUT")',
                        'python --version cetak lalu keluar: gerbang lolos (Audit 1705)', True)

    skenario_substitusi(tmp, 'sub790',
                        '> >(python3 -q; cat >> "$GITHUB_OUTPUT")',
                        'python -q sah dan membaca program dari stdin: penguras — gerbang gagal (Audit 1706)', False)

    skenario_substitusi(tmp, 'sub791',
                        '> >(python3 --check-hash-based-pycs default; cat >> "$GITHUB_OUTPUT")',
                        'python --check-hash-based-pycs bernilai spasi; tanpa skrip membaca stdin: gerbang gagal (Audit 1707)', False)

    skenario_substitusi(tmp, 'sub792',
                        '> >(python3 --check-hash-based-pycs=default; cat >> "$GITHUB_OUTPUT")',
                        'python --check-hash-based-pycs bentuk = ditolak build ini: gerbang lolos (Audit 1707)', True)

    skenario_substitusi(tmp, 'sub793',
                        '> >(python3 -W; cat >> "$GITHUB_OUTPUT")',
                        'python -W menggantung galat lalu keluar: gerbang lolos (Audit 1708)', True)

    skenario_substitusi(tmp, 'sub794',
                        '> >(python3 -sW skrip.py; cat >> "$GITHUB_OUTPUT")',
                        'python -sW menelan skrip.py sebagai nilai -W lalu membaca stdin: gerbang gagal (Audit 1708)', False)

    skenario_substitusi(tmp, 'sub795',
                        '> >(gpgv gab.sig -- -; cat >> "$GITHUB_OUTPUT")',
                        "gpgv '--' sesudah operand adalah nama berkas harfiah, galat tanpa membaca: gerbang lolos (Audit 1709)", True)

    skenario_substitusi(tmp, 'sub796',
                        '> >(gpgv --bogus gab.sig -; cat >> "$GITHUB_OUTPUT")',
                        'gpgv opsi panjang tak dikenal invalid option lalu keluar: gerbang lolos (Audit 1710)', True)

    skenario_substitusi(tmp, 'sub797',
                        '> >(node --bogus; cat >> "$GITHUB_OUTPUT")',
                        'node opsi tak dikenal bad option lalu keluar: gerbang lolos (Audit 1711)', True)

    skenario_substitusi(tmp, 'sub798',
                        '> >(node --version; cat >> "$GITHUB_OUTPUT")',
                        'node --version cetak lalu keluar: gerbang lolos (Audit 1712)', True)

    skenario_substitusi(tmp, 'sub799',
                        '> >(lua -e; cat >> "$GITHUB_OUTPUT")',
                        'lua -e menggantung needs argument lalu keluar: gerbang lolos (Audit 1713)', True)

    skenario_substitusi(tmp, 'sub800',
                        '> >(ruby -Z; cat >> "$GITHUB_OUTPUT")',
                        'ruby opsi tak dikenal galat lalu keluar: gerbang lolos (Audit 1714)', True)

    skenario_substitusi(tmp, 'sub801',
                        '> >(python3 -hi; cat >> "$GITHUB_OUTPUT")',
                        'python -hi di bundel: bantuan lalu keluar - gerbang lolos (Audit 1719)', True)

    skenario_substitusi(tmp, 'sub802',
                        '> >(python3 --check-hash-based-pycs; cat >> "$GITHUB_OUTPUT")',
                        'python --check-hash-based-pycs gantung: galat lalu keluar - gerbang lolos (Audit 1720)', True)

    skenario_substitusi(tmp, 'sub803',
                        '> >(node --title judul; cat >> "$GITHUB_OUTPUT")',
                        'node --title menelan nilai lalu node membaca REPL dari stdin: penguras - gerbang gagal (Audit 1721)', False)

    skenario_substitusi(tmp, 'sub804',
                        '> >(node --title; cat >> "$GITHUB_OUTPUT")',
                        'node --title gantung: galat lalu keluar - gerbang lolos (Audit 1721)', True)

    skenario_substitusi(tmp, 'sub805',
                        '> >(node -r; cat >> "$GITHUB_OUTPUT")',
                        'node -r gantung: galat lalu keluar - gerbang lolos (Audit 1722)', True)

    skenario_substitusi(tmp, 'sub806',
                        '> >(php -t; cat >> "$GITHUB_OUTPUT")',
                        'php -t gantung: galat lalu keluar - gerbang lolos (Audit 1723)', True)

    skenario_substitusi(tmp, 'sub807',
                        '> >(php -t /srv/www; cat >> "$GITHUB_OUTPUT")',
                        'php -t menelan docroot lalu membaca kode dari stdin: penguras - gerbang gagal (Audit 1723)', False)

    skenario_substitusi(tmp, 'sub808',
                        '> >(ruby -0; cat >> "$GITHUB_OUTPUT")',
                        'ruby -0 opsi sah; ruby telanjang membaca program dari stdin: penguras - gerbang gagal (Audit 1724)', False)

    skenario_substitusi(tmp, 'sub809',
                        '> >(lua -e x; cat >> "$GITHUB_OUTPUT")',
                        'lua -e tanpa skrip: aksi jalan lalu keluar - gerbang lolos (Audit 1725)', True)

    skenario_substitusi(tmp, 'sub810',
                        '> >(lua -v; cat >> "$GITHUB_OUTPUT")',
                        'lua -v telanjang: cetak versi lalu keluar - gerbang lolos (Audit 1726)', True)

    skenario_substitusi(tmp, 'sub811',
                        '> >(bun -; cat >> "$GITHUB_OUTPUT")',
                        'bun - menjalankan skrip dari stdin: penguras - gerbang gagal (Audit 1732)', False)

    skenario_substitusi(tmp, 'sub812',
                        '> >(bun run -; cat >> "$GITHUB_OUTPUT")',
                        'bun run - menjalankan skrip dari stdin: penguras - gerbang gagal (Audit 1732)', False)

    skenario_substitusi(tmp, 'sub813',
                        '> >(sftp -b - host; cat >> "$GITHUB_OUTPUT")',
                        'sftp -b - membaca batch dari stdin: penguras - gerbang gagal (Audit 1734)', False)

    skenario_substitusi(tmp, 'sub814',
                        '> >(sftp -b batch.txt host; cat >> "$GITHUB_OUTPUT")',
                        'sftp -b berkas membaca batch dari berkas: gerbang lolos (Audit 1734)', True)

    skenario_substitusi(tmp, 'sub815',
                        '> >(pwsh -File -; cat >> "$GITHUB_OUTPUT")',
                        'pwsh -File - mengeksekusi stdin sebagai perintah: penguras - gerbang gagal (Audit 1735)', False)

    skenario_substitusi(tmp, 'sub816',
                        '> >(pwsh -Command x; cat >> "$GITHUB_OUTPUT")',
                        'pwsh -Command string mengeksekusi string lalu keluar: gerbang lolos (Audit 1735)', True)

    skenario_substitusi(tmp, 'sub817',
                        '> >(wish; cat >> "$GITHUB_OUTPUT")',
                        'wish telanjang membaca perintah dari stdin: penguras - gerbang gagal (Audit 1736)', False)

    skenario_substitusi(tmp, 'sub818',
                        '> >(wish skrip.tcl; cat >> "$GITHUB_OUTPUT")',
                        'wish dengan berkas skrip tidak membaca stdin: gerbang lolos (Audit 1736)', True)

    skenario_substitusi(tmp, 'sub819',
                        '> >(mksh; cat >> "$GITHUB_OUTPUT")',
                        'mksh telanjang membaca perintah dari stdin: penguras - gerbang gagal (Audit 1737)', False)

    skenario_substitusi(tmp, 'sub820',
                        '> >(tcsh skrip.sh; cat >> "$GITHUB_OUTPUT")',
                        'tcsh dengan berkas skrip tidak membaca stdin: gerbang lolos (Audit 1737)', True)

    skenario_substitusi(tmp, 'sub821',
                        '> >(busybox sh; cat >> "$GITHUB_OUTPUT")',
                        'busybox sh dinilai sebagai applet sh yang membaca stdin: penguras - gerbang gagal (Audit 1738)', False)

    skenario_substitusi(tmp, 'sub822',
                        '> >(busybox ls; cat >> "$GITHUB_OUTPUT")',
                        'busybox ls dinilai sebagai applet ls yang tidak membaca stdin: gerbang lolos (Audit 1738)', True)

    skenario_substitusi(tmp, 'sub823',
                        '> >(busybox timeout 5 cat; cat >> "$GITHUB_OUTPUT")',
                        'busybox timeout: applet pembungkus dikupas tahap yang sama, cat membaca stdin - gerbang gagal (Audit 1739)', False)

    skenario_substitusi(tmp, 'sub825',
                        '> >(pwsh -File skrip.ps1 -NoExit; cat >> "$GITHUB_OUTPUT")',
                        'pwsh -NoExit sesudah -File berkas: host tetap interaktif membaca stdin - gerbang gagal (Audit 1740)', False)

    skenario_substitusi(tmp, 'sub826',
                        '> >(pwsh -File skrip.ps1; cat >> "$GITHUB_OUTPUT")',
                        'pwsh -File berkas tanpa -NoExit: jalankan skrip lalu keluar - gerbang lolos (Audit 1740)', True)

    skenario_substitusi(tmp, 'sub827',
                        '> >(busybox X=1 cat; cat >> "$GITHUB_OUTPUT")',
                        'penetapan di posisi applet busybox adalah nama applet tak dikenal: gerbang lolos (Audit 1743)', True)

    skenario_substitusi(tmp, 'sub828',
                        '> >(busybox env X=1 cat; cat >> "$GITHUB_OUTPUT")',
                        'env di posisi applet memulihkan wilayah penetapan, cat membaca stdin: gerbang gagal (Audit 1743)', False)

    skenario_substitusi(tmp, 'sub829',
                        '> >(pwsh -File x -Command -; cat >> "$GITHUB_OUTPUT")',
                        'aksi kedua pwsh membuat host menolak gabungan -File/-Command: gerbang lolos (Audit 1744)', True)

    skenario_substitusi(tmp, 'sub830',
                        '> >(pwsh -Command -; cat >> "$GITHUB_OUTPUT")',
                        'pwsh -Command - mengeksekusi stdin: gerbang gagal (Audit 1744)', False)

    skenario_substitusi(tmp, 'sub831',
                        '> >(pwsh -WorkingDirectory /tmp; cat >> "$GITHUB_OUTPUT")',
                        'nilai opsi bernilai asing pwsh bukan naskah; tanpa aksi pwsh interaktif membaca stdin: gerbang gagal (Audit 1745)', False)

    skenario_substitusi(tmp, 'sub832',
                        '> >(pwsh -EncodedCommand aGVjaG8=; cat >> "$GITHUB_OUTPUT")',
                        'pwsh -EncodedCommand mengeksekusi string lalu keluar: gerbang lolos (Audit 1745)', True)

    skenario_substitusi(tmp, 'sub833',
                        '> >(bc berkas.bc; cat >> "$GITHUB_OUTPUT")',
                        'bc membaca stdin sesudah berkas operand: gerbang gagal (Audit 1748)', False)

    skenario_substitusi(tmp, 'sub834',
                        '> >(bc --version; cat >> "$GITHUB_OUTPUT")',
                        'bc --version cetak versi lalu keluar: gerbang lolos (Audit 1748)', True)

    skenario_substitusi(tmp, 'sub835',
                        '> >(dc; cat >> "$GITHUB_OUTPUT")',
                        'dc telanjang membaca perintah dari stdin: gerbang gagal (Audit 1749)', False)

    skenario_substitusi(tmp, 'sub836',
                        '> >(dc berkas.dc; cat >> "$GITHUB_OUTPUT")',
                        'dc dengan berkas operand membaca berkas itu: gerbang lolos (Audit 1749)', True)

    skenario_substitusi(tmp, 'sub837',
                        '> >(ed berkas.txt; cat >> "$GITHUB_OUTPUT")',
                        'perintah ed selalu dari stdin walau beroperand berkas: gerbang gagal (Audit 1750)', False)

    skenario_substitusi(tmp, 'sub838',
                        '> >(ed --version; cat >> "$GITHUB_OUTPUT")',
                        'ed --version cetak versi lalu keluar: gerbang lolos (Audit 1750)', True)

    skenario_substitusi(tmp, 'sub839',
                        '> >(dc -f -; cat >> "$GITHUB_OUTPUT")',
                        'berkas skrip dc adalah stdin lewat -f -: gerbang gagal (Audit 1751)', False)

    skenario_substitusi(tmp, 'sub840',
                        '> >(dc -f berkas.dc; cat >> "$GITHUB_OUTPUT")',
                        'dc -f berkas membaca skrip dari berkas itu: gerbang lolos (Audit 1751)', True)

    skenario_substitusi(tmp, 'sub841',
                        '> >(pwsh -Version; cat >> "$GITHUB_OUTPUT")',
                        'pwsh -Version cetak versi lalu keluar: gerbang lolos (Audit 1752)', True)

    skenario_substitusi(tmp, 'sub842',
                        '> >(pwsh -Version -NoExit; cat >> "$GITHUB_OUTPUT")',
                        'argumen sesudah -Version pwsh diabaikan host: gerbang lolos (Audit 1752)', True)

    skenario_substitusi(tmp, 'sub843',
                        '> >(powershell -Version 5.1; cat >> "$GITHUB_OUTPUT")',
                        'powershell -Version memilih mesin lalu interaktif membaca stdin: gerbang gagal (Audit 1753)', False)

    skenario_substitusi(tmp, 'sub844',
                        '> >(powershell -Version 5.1 -File x; cat >> "$GITHUB_OUTPUT")',
                        'powershell -Version lalu -File berkas menjalankan berkas itu: gerbang lolos (Audit 1753)', True)

    skenario_substitusi(tmp, 'sub845',
                        '> >(gpg --decrypt -; cat >> "$GITHUB_OUTPUT")',
                        'operand - pada modus kripto gpg adalah stdin: gerbang gagal (Audit 1756)', False)

    skenario_substitusi(tmp, 'sub846',
                        '> >(gpg --decrypt berkas.gpg; cat >> "$GITHUB_OUTPUT")',
                        'gpg dekripsi berkas operand membaca berkas itu: gerbang lolos (Audit 1756)', True)

    skenario_substitusi(tmp, 'sub847',
                        '> >(hexdump -C; cat >> "$GITHUB_OUTPUT")',
                        'hexdump tanpa berkas membaca stdin: gerbang gagal (Audit 1757)', False)

    skenario_substitusi(tmp, 'sub848',
                        '> >(hexdump berkas.bin; cat >> "$GITHUB_OUTPUT")',
                        'hexdump berkas operand menampilkan berkas itu: gerbang lolos (Audit 1757)', True)

    skenario_substitusi(tmp, 'sub849',
                        '> >(xxd -; cat >> "$GITHUB_OUTPUT")',
                        'xxd dengan infile - membaca stdin: gerbang gagal (Audit 1758)', False)

    skenario_substitusi(tmp, 'sub850',
                        '> >(xxd berkas.bin; cat >> "$GITHUB_OUTPUT")',
                        'xxd berkas operand membaca berkas itu: gerbang lolos (Audit 1758)', True)

    skenario_substitusi(tmp, 'sub851',
                        '> >(pwsh -NoExit -Version; cat >> "$GITHUB_OUTPUT")',
                        '-Version pwsh mengabaikan parameter lain walau disebut belakangan: gerbang lolos (Audit 1759)', True)

    skenario_substitusi(tmp, 'sub852',
                        '> >(pwsh -File - -Version; cat >> "$GITHUB_OUTPUT")',
                        '-Version pwsh sesudah -File - tetap informasional: gerbang lolos (Audit 1759)', True)

    skenario_substitusi(tmp, 'sub853',
                        '> >(powershell -Version -Command -; cat >> "$GITHUB_OUTPUT")',
                        'nilai -Version powershell bersaklar bukan versi sah, host galat: gerbang lolos (Audit 1760)', True)

    skenario_substitusi(tmp, 'sub854',
                        '> >(powershell -Version 5.1; cat >> "$GITHUB_OUTPUT")',
                        'powershell -Version bernilai sah interaktif membaca stdin: gerbang gagal (Audit 1760)', False)

    skenario_substitusi(tmp, 'sub855',
                        '> >(pwsh -Versionx; cat >> "$GITHUB_OUTPUT")',
                        'perpanjangan nama -Version bukan parameter pwsh: gerbang lolos (Audit 1761)', True)

    skenario_substitusi(tmp, 'sub856',
                        '> >(pwsh -ver; cat >> "$GITHUB_OUTPUT")',
                        'awalan unik -ver terikat sebagai -Version informasional: gerbang lolos (Audit 1762)', True)

    skenario_substitusi(tmp, 'sub857',
                        '> >(zip arsip.zip -; cat >> "$GITHUB_OUTPUT")',
                        'zip mengarsipkan berkas masukan - dari stdin: gerbang gagal (Audit 1763)', False)

    skenario_substitusi(tmp, 'sub858',
                        '> >(zip arsip.zip berkas.txt; cat >> "$GITHUB_OUTPUT")',
                        'zip berkas masukan biasa membaca berkas itu: gerbang lolos (Audit 1763)', True)

    skenario_substitusi(tmp, 'sub859',
                        '> >(less +10 berkas.txt; cat >> "$GITHUB_OUTPUT")',
                        'less +N beroperand berkas membuka berkasnya: gerbang lolos (Audit 1764)', True)

    skenario_substitusi(tmp, 'sub860',
                        '> >(less +10; cat >> "$GITHUB_OUTPUT")',
                        'less +N tanpa berkas membaca stdin: gerbang gagal (Audit 1764)', False)

    skenario_substitusi(tmp, 'sub861',
                        '> >(cpio -o; cat >> "$GITHUB_OUTPUT")',
                        'cpio copy-out membaca daftar nama dari stdin: gerbang gagal (Audit 1765)', False)

    skenario_substitusi(tmp, 'sub862',
                        '> >(cpio -i -F arsip.cpio; cat >> "$GITHUB_OUTPUT")',
                        'cpio copy-in ber-F berkas membaca arsip itu: gerbang lolos (Audit 1765)', True)

    skenario_substitusi(tmp, 'sub863',
                        '> >(pax -r; cat >> "$GITHUB_OUTPUT")',
                        'pax mode read membaca arsip dari stdin: gerbang gagal (Audit 1766)', False)

    skenario_substitusi(tmp, 'sub864',
                        '> >(pax -r -f arsip.pax; cat >> "$GITHUB_OUTPUT")',
                        'pax read ber-f berkas membaca arsip itu: gerbang lolos (Audit 1766)', True)

    skenario_substitusi(tmp, 'sub865',
                        '> >(pg; cat >> "$GITHUB_OUTPUT")',
                        'pg tanpa berkas membaca stdin: gerbang gagal (Audit 1767)', False)

    skenario_substitusi(tmp, 'sub866',
                        '> >(bat -; cat >> "$GITHUB_OUTPUT")',
                        'bat dengan FILE - membaca stdin: gerbang gagal (Audit 1768)', False)

    skenario_substitusi(tmp, 'sub867',
                        '> >(bat berkas.txt; cat >> "$GITHUB_OUTPUT")',
                        'bat ber-FILE berkas menampilkan berkas itu: gerbang lolos (Audit 1768)', True)

    skenario_substitusi(tmp, 'sub868',
                        '> >(lzip -dc; cat >> "$GITHUB_OUTPUT")',
                        'lzip tanpa berkas membuka dari stdin: gerbang gagal (Audit 1769)', False)

    skenario_substitusi(tmp, 'sub869',
                        '> >(lz4 -dc; cat >> "$GITHUB_OUTPUT")',
                        'lz4 tanpa berkas membuka dari stdin: gerbang gagal (Audit 1770)', False)

    skenario_substitusi(tmp, 'sub870',
                        '> >(rpm2cpio; cat >> "$GITHUB_OUTPUT")',
                        'rpm2cpio tanpa argumen membaca aliran RPM dari stdin: gerbang gagal (Audit 1771)', False)

    skenario_substitusi(tmp, 'sub871',
                        '> >(7z a -si arsip.7z; cat >> "$GITHUB_OUTPUT")',
                        '7z a -si membaca data dari stdin: gerbang gagal (Audit 1772)', False)

    skenario_substitusi(tmp, 'sub872',
                        '> >(pwsh -ve; cat >> "$GITHUB_OUTPUT")',
                        'awalan unik -ve terikat sebagai -Version pwsh informasional: gerbang lolos (Audit 1773)', True)

    skenario_substitusi(tmp, 'sub873',
                        '> >(powershell -ver 5.1; cat >> "$GITHUB_OUTPUT")',
                        'awalan -ver pada powershell adalah -Version bernilai, interaktif: gerbang gagal (Audit 1774)', False)

    skenario_substitusi(tmp, 'sub874',
                        '> >(hexdump -f -; cat >> "$GITHUB_OUTPUT")',
                        'nilai -f hexdump adalah berkas format harfiah, bukan stdin: gerbang lolos (Audit 1775)', True)

    skenario_substitusi(tmp, 'sub875',
                        '> >(cpio -i -F -; cat >> "$GITHUB_OUTPUT")',
                        'nilai -F cpio berupa - adalah berkas harfiah, bukan stdin: gerbang lolos (Audit 1780)', True)

    skenario_substitusi(tmp, 'sub876',
                        '> >(7z a -siNama; cat >> "$GITHUB_OUTPUT")',
                        '7z a -si tanpa nama arsip gugur sebelum membaca stdin: gerbang lolos (Audit 1781)', True)

    skenario_substitusi(tmp, 'sub877',
                        '> >(7z a -- -si; cat >> "$GITHUB_OUTPUT")',
                        '-si sesudah -- pada 7z adalah nama berkas, bukan saklar: gerbang lolos (Audit 1782)', True)

    skenario_substitusi(tmp, 'sub878',
                        '> >(7z u -si arsip.7z; cat >> "$GITHUB_OUTPUT")',
                        '7z u -si membaca data dari stdin untuk update: gerbang gagal (Audit 1783)', False)

    skenario_substitusi(tmp, 'sub879',
                        '> >(7za a -si arsip.7z; cat >> "$GITHUB_OUTPUT")',
                        'varian 7za dengan a -si membaca stdin seperti 7z: gerbang gagal (Audit 1784)', False)

    skenario_substitusi(tmp, 'sub880',
                        '> >(powershell -v; cat >> "$GITHUB_OUTPUT")',
                        '-v bukan alias pada powershell.exe, host keluar tanpa sesi: gerbang lolos (Audit 1785)', True)

    skenario_substitusi(tmp, 'sub881',
                        '> >(cpio -i -Farsip.cpio; cat >> "$GITHUB_OUTPUT")',
                        'nilai -F cpio menempel adalah berkas arsip, bukan stdin: gerbang lolos (Audit 1786)', True)

    skenario_substitusi(tmp, 'sub882',
                        '> >(7z a @daftar.txt -si; cat >> "$GITHUB_OUTPUT")',
                        '@listfile bukan nama arsip 7z - tanpa arsip perintah gugur: gerbang lolos (Audit 1787)', True)

    skenario_substitusi(tmp, 'sub883',
                        '> >(bun --cwd /tmp -; cat >> "$GITHUB_OUTPUT")',
                        "nilai opsi global bun dilompati, '-' adalah skrip stdin: gerbang gagal (Audit 1788)", False)

    skenario_substitusi(tmp, 'sub884',
                        '> >(bun -e x -; cat >> "$GITHUB_OUTPUT")',
                        "sesudah -e, '-' adalah argumen posisi skrip eval: gerbang lolos (Audit 1789)", True)

    skenario_substitusi(tmp, 'sub885',
                        '> >(sftp -; cat >> "$GITHUB_OUTPUT")',
                        "operand '-' pada sftp adalah host tak sah, sftp keluar: gerbang lolos (Audit 1790)", True)

    skenario_substitusi(tmp, 'sub886',
                        '> >(sftp -o - host; cat >> "$GITHUB_OUTPUT")',
                        "nilai -o berupa '-' fatal di konfigurasi ssh: gerbang lolos (Audit 1791)", True)

    skenario_substitusi(tmp, 'sub887',
                        '> >(sftp -b - -b berkas.txt host; cat >> "$GITHUB_OUTPUT")',
                        '-b kedua pada sftp ditolak OpenSSH saat parsing: gerbang lolos (Audit 1792)', True)

    skenario_substitusi(tmp, 'sub888',
                        '> >(dc berkas.dc -; cat >> "$GITHUB_OUTPUT")',
                        "operand '-' dc sesudah berkas tetap aliran stdin: gerbang gagal (Audit 1793)", False)

    skenario_substitusi(tmp, 'sub889',
                        '> >(dc -e2p; cat >> "$GITHUB_OUTPUT")',
                        'nilai menempel -e pada dc adalah ekspresi aksi: gerbang lolos (Audit 1794)', True)

    skenario_substitusi(tmp, 'sub890',
                        '> >(xargs dc berkas.dc; cat >> "$GITHUB_OUTPUT")',
                        'xargs tanpa -a di depan perintah khusus tetap menguras untuk itemnya: gerbang gagal (Audit 1796)', False)

    skenario_substitusi(tmp, 'sub891',
                        '> >(cpio -iFarsip.cpio; cat >> "$GITHUB_OUTPUT")',
                        'cluster cpio dengan -F di tengah token adalah arsip berkas: gerbang lolos (Audit 1797)', True)

    skenario_substitusi(tmp, 'sub892',
                        '> >(7z a -si -- arsip.7z; cat >> "$GITHUB_OUTPUT")',
                        'nama arsip 7z sah sesudah -- dan -si tetap aktif: gerbang gagal (Audit 1798)', False)

    skenario_substitusi(tmp, 'sub893',
                        '> >(column -t; cat >> "$GITHUB_OUTPUT")',
                        'column tanpa berkas membaca stdin untuk ditabelkan: gerbang gagal (Audit 1801)', False)

    skenario_substitusi(tmp, 'sub894',
                        '> >(gzcat; cat >> "$GITHUB_OUTPUT")',
                        'gzcat adalah zcat - tanpa operand membaca aliran dari stdin: gerbang gagal (Audit 1802)', False)

    skenario_substitusi(tmp, 'sub895',
                        '> >(uuencode laporan.bin; cat >> "$GITHUB_OUTPUT")',
                        'satu-satunya operand uuencode adalah nama keluaran, masukan dari stdin: gerbang gagal (Audit 1803)', False)

    skenario_substitusi(tmp, 'sub896',
                        '> >(uudecode; cat >> "$GITHUB_OUTPUT")',
                        'uudecode tanpa FILE membaca stdin: gerbang gagal (Audit 1804)', False)

    skenario_substitusi(tmp, 'sub897',
                        '> >(plzip; cat >> "$GITHUB_OUTPUT")',
                        'plzip tanpa berkas mengompres dari stdin: gerbang gagal (Audit 1806)', False)

    skenario_substitusi(tmp, 'sub898',
                        '> >(lbzcat; cat >> "$GITHUB_OUTPUT")',
                        'lbzcat tanpa FILE adalah filter dekompresi stdin: gerbang gagal (Audit 1807)', False)

    skenario_substitusi(tmp, 'sub899',
                        '> >(pixz -t; cat >> "$GITHUB_OUTPUT")',
                        'pixz beropsi saja tetap memakai stdin: gerbang gagal (Audit 1808)', False)

    skenario_substitusi(tmp, 'sub900',
                        '> >(compress; cat >> "$GITHUB_OUTPUT")',
                        'compress tanpa berkas mengompres stdin: gerbang gagal (Audit 1809)', False)

    skenario_substitusi(tmp, 'sub901',
                        '> >(brotli; cat >> "$GITHUB_OUTPUT")',
                        'brotli tanpa berkas membaca stdin: gerbang gagal (Audit 1810)', False)

    skenario_substitusi(tmp, 'sub902',
                        '> >(lzop; cat >> "$GITHUB_OUTPUT")',
                        'lzop tanpa berkas mengompres stdin: gerbang gagal (Audit 1811)', False)

    skenario_substitusi(tmp, 'sub903',
                        '> >(lziprecover; cat >> "$GITHUB_OUTPUT")',
                        'lziprecover tanpa berkas mendekompres dari stdin: gerbang gagal (Audit 1812)', False)

    skenario_substitusi(tmp, 'sub904',
                        '> >(dos2unix; cat >> "$GITHUB_OUTPUT")',
                        'dos2unix tanpa FILE adalah filter stdin: gerbang gagal (Audit 1813)', False)

    skenario_substitusi(tmp, 'sub905',
                        '> >(sponge berkas.txt; cat >> "$GITHUB_OUTPUT")',
                        'sponge selalu menyedot stdin, operandnya berkas keluaran: gerbang gagal (Audit 1814)', False)

    skenario_substitusi(tmp, 'sub906',
                        '> >(ts -i; cat >> "$GITHUB_OUTPUT")',
                        'ts memberi stempel waktu pada aliran stdin: gerbang gagal (Audit 1815)', False)

    skenario_substitusi(tmp, 'sub907',
                        '> >(vipe; cat >> "$GITHUB_OUTPUT")',
                        'vipe membaca seluruh stdin ke editor: gerbang gagal (Audit 1816)', False)

    skenario_substitusi(tmp, 'sub908',
                        '> >(pee cat; cat >> "$GITHUB_OUTPUT")',
                        'pee menyalurkan stdin ke pipa-pipa tujuannya: gerbang gagal (Audit 1817)', False)

    skenario_substitusi(tmp, 'sub909',
                        '> >(recode utf8..latin1; cat >> "$GITHUB_OUTPUT")',
                        'recode dengan request tanpa FILE adalah filter stdin: gerbang gagal (Audit 1818)', False)

    skenario_substitusi(tmp, 'sub910',
                        '> >(plzip --threads 2; cat >> "$GITHUB_OUTPUT")',
                        'nilai --threads plzip bukan operand - tanpa berkas ia membaca stdin: gerbang gagal (Audit 1819)', False)

    skenario_substitusi(tmp, 'sub911',
                        '> >(lbzip2 -n 4; cat >> "$GITHUB_OUTPUT")',
                        'nilai -n lbzip2 bukan operand - tanpa FILE ia filter stdin: gerbang gagal (Audit 1820, dikoreksi Audit 1866)', False)

    skenario_substitusi(tmp, 'sub912',
                        '> >(pixz -p 2; cat >> "$GITHUB_OUTPUT")',
                        'nilai -p pixz bukan operand - tanpa INPUT ia memakai stdin: gerbang gagal (Audit 1821)', False)

    skenario_substitusi(tmp, 'sub913',
                        '> >(compress -b 12; cat >> "$GITHUB_OUTPUT")',
                        'nilai -b compress bukan operand - tanpa berkas stdin dikompres: gerbang gagal (Audit 1822)', False)

    skenario_substitusi(tmp, 'sub914',
                        '> >(brotli -q 11; cat >> "$GITHUB_OUTPUT")',
                        'nilai -q brotli bukan operand - tanpa berkas ia membaca stdin: gerbang gagal (Audit 1823)', False)

    skenario_substitusi(tmp, 'sub915',
                        '> >(lzop -o out; cat >> "$GITHUB_OUTPUT")',
                        'nilai -o lzop bukan operand - tanpa berkas ia mengompres stdin: gerbang gagal (Audit 1824)', False)

    skenario_substitusi(tmp, 'sub916',
                        '> >(dos2unix -c mac; cat >> "$GITHUB_OUTPUT")',
                        'nilai -c dos2unix bukan operand - tanpa FILE ia filter stdin: gerbang gagal (Audit 1825)', False)

    skenario_substitusi(tmp, 'sub917',
                        '> >(recode -x latin1 utf8..latin1; cat >> "$GITHUB_OUTPUT")',
                        '-x recode menelan nilai charset - request tanpa FILE adalah filter stdin: gerbang gagal (Audit 1826)', False)

    skenario_substitusi(tmp, 'sub918',
                        '> >(yq .a; cat >> "$GITHUB_OUTPUT")',
                        'yq dengan ekspresi tanpa berkas membaca stdin: gerbang gagal (Audit 1827)', False)

    skenario_substitusi(tmp, 'sub919',
                        '> >(fq .; cat >> "$GITHUB_OUTPUT")',
                        'fq dengan ekspresi tanpa berkas membaca stdin seperti jq: gerbang gagal (Audit 1828)', False)

    skenario_substitusi(tmp, 'sub920',
                        '> >(dasel -r json .; cat >> "$GITHUB_OUTPUT")',
                        'dasel tanpa -f membaca stdin, operandnya selektor: gerbang gagal (Audit 1829)', False)

    skenario_substitusi(tmp, 'sub921',
                        '> >(mlr --csv cut -f a; cat >> "$GITHUB_OUTPUT")',
                        'mlr tanpa nama berkas membaca stdin, -f a milik kata kerja: gerbang gagal (Audit 1830)', False)

    skenario_substitusi(tmp, 'sub922',
                        '> >(sd lama baru; cat >> "$GITHUB_OUTPUT")',
                        'dua operand sd adalah pola dan pengganti - tanpa berkas ia filter stdin: gerbang gagal (Audit 1831)', False)

    skenario_substitusi(tmp, 'sub923',
                        '> >(choose -f : 0; cat >> "$GITHUB_OUTPUT")',
                        'choose membaca stdin, -f-nya pemisah bidang bukan berkas: gerbang gagal (Audit 1832)', False)

    skenario_substitusi(tmp, 'sub924',
                        '> >(hck -f 1; cat >> "$GITHUB_OUTPUT")',
                        'hck tanpa berkas membaca stdin seperti cut: gerbang gagal (Audit 1833)', False)

    skenario_substitusi(tmp, 'sub925',
                        '> >(hexyl -n 16; cat >> "$GITHUB_OUTPUT")',
                        'hexyl tanpa FILE membaca stdin, -n opsi bernilai: gerbang gagal (Audit 1834)', False)

    skenario_substitusi(tmp, 'sub926',
                        '> >(ncat contoh 80; cat >> "$GITHUB_OUTPUT")',
                        'ncat meneruskan stdin ke koneksi seperti nc: gerbang gagal (Audit 1835)', False)

    skenario_substitusi(tmp, 'sub927',
                        '> >(aria2c -i -; cat >> "$GITHUB_OUTPUT")',
                        'aria2c -i - membaca daftar URI dari stdin: gerbang gagal (Audit 1836)', False)

    skenario_substitusi(tmp, 'sub928',
                        '> >(envsubst; cat >> "$GITHUB_OUTPUT")',
                        'envsubst selalu menyalin stdin ke stdout: gerbang gagal (Audit 1837)', False)

    skenario_substitusi(tmp, 'sub929',
                        '> >(write tasirin; cat >> "$GITHUB_OUTPUT")',
                        'write membaca baris dari stdin untuk terminal penerima: gerbang gagal (Audit 1838)', False)

    skenario_substitusi(tmp, 'sub930',
                        '> >(xz --block-list 1MiB; cat >> "$GITHUB_OUTPUT")',
                        'nilai --block-list xz bukan operand - tanpa berkas ia membaca stdin: gerbang gagal (Audit 1839)', False)

    skenario_substitusi(tmp, 'sub931',
                        '> >(lzma --block-list 1MiB; cat >> "$GITHUB_OUTPUT")',
                        'nilai --block-list lzma bukan operand - tanpa berkas ia membaca stdin: gerbang gagal (Audit 1839)', False)

    skenario_substitusi(tmp, 'sub932',
                        '> >(unxz --block-list 1MiB; cat >> "$GITHUB_OUTPUT")',
                        'nilai --block-list unxz bukan operand: gerbang gagal (Audit 1839)', False)

    skenario_substitusi(tmp, 'sub933',
                        '> >(xzcat --block-list 1MiB; cat >> "$GITHUB_OUTPUT")',
                        'nilai --block-list xzcat bukan operand: gerbang gagal (Audit 1839)', False)

    skenario_substitusi(tmp, 'sub934',
                        '> >(lzcat --block-list 1MiB; cat >> "$GITHUB_OUTPUT")',
                        'nilai --block-list lzcat bukan operand: gerbang gagal (Audit 1839)', False)

    skenario_substitusi(tmp, 'sub935',
                        '> >(unlzma --block-list 1MiB; cat >> "$GITHUB_OUTPUT")',
                        'nilai --block-list unlzma bukan operand: gerbang gagal (Audit 1839)', False)

    skenario_substitusi(tmp, 'sub936',
                        '> >(xz --memlimit 100MiB; cat >> "$GITHUB_OUTPUT")',
                        'nilai --memlimit xz bukan operand: gerbang gagal (Audit 1840)', False)

    skenario_substitusi(tmp, 'sub937',
                        '> >(xz --memlimit-compress 100MiB; cat >> "$GITHUB_OUTPUT")',
                        'keluarga --memlimit xz seragam di semua entri: gerbang gagal (Audit 1840)', False)

    skenario_substitusi(tmp, 'sub938',
                        '> >(unxz --memlimit-compress 100MiB; cat >> "$GITHUB_OUTPUT")',
                        'unxz memuat --memlimit-compress: gerbang gagal (Audit 1840)', False)

    skenario_substitusi(tmp, 'sub939',
                        '> >(lzma --memlimit 100MiB; cat >> "$GITHUB_OUTPUT")',
                        'lzma memuat --memlimit: gerbang gagal (Audit 1840)', False)

    skenario_substitusi(tmp, 'sub940',
                        '> >(xzcat --memlimit-decompress 100MiB; cat >> "$GITHUB_OUTPUT")',
                        'xzcat memuat --memlimit-decompress: gerbang gagal (Audit 1840)', False)

    skenario_substitusi(tmp, 'sub941',
                        '> >(unlzma --memlimit 100MiB; cat >> "$GITHUB_OUTPUT")',
                        'unlzma memuat --memlimit: gerbang gagal (Audit 1840)', False)

    skenario_substitusi(tmp, 'sub942',
                        '> >(unxz -C crc32; cat >> "$GITHUB_OUTPUT")',
                        '-C unxz bernilai nama checksum: gerbang gagal (Audit 1841)', False)

    skenario_substitusi(tmp, 'sub943',
                        '> >(xzcat --check crc32; cat >> "$GITHUB_OUTPUT")',
                        '--check xzcat bernilai: gerbang gagal (Audit 1841)', False)

    skenario_substitusi(tmp, 'sub944',
                        '> >(unlzma -T 2; cat >> "$GITHUB_OUTPUT")',
                        'unlzma entri penuh menyamai lzma - -T bernilai: gerbang gagal (Audit 1842)', False)

    skenario_substitusi(tmp, 'sub945',
                        '> >(unlzma --threads 2; cat >> "$GITHUB_OUTPUT")',
                        '--threads unlzma bernilai: gerbang gagal (Audit 1842)', False)

    skenario_substitusi(tmp, 'sub946',
                        '> >(unlzma -C crc32; cat >> "$GITHUB_OUTPUT")',
                        '-C unlzma bernilai: gerbang gagal (Audit 1842)', False)

    skenario_substitusi(tmp, 'sub947',
                        '> >(derdump -o hasil; cat >> "$GITHUB_OUTPUT")',
                        '-o derdump berkas keluaran bernilai - tanpa operand ia membaca stdin: gerbang gagal (Audit 1843)', False)

    skenario_substitusi(tmp, 'sub948',
                        '> >(unbrotli -o hasil; cat >> "$GITHUB_OUTPUT")',
                        'unbrotli berbagi entri brotli - -o bernilai: gerbang gagal (Audit 1844)', False)

    skenario_substitusi(tmp, 'sub949',
                        '> >(unbrotli -w 22; cat >> "$GITHUB_OUTPUT")',
                        '-w unbrotli bernilai: gerbang gagal (Audit 1844)', False)

    skenario_substitusi(tmp, 'sub950',
                        '> >(brcat -o hasil; cat >> "$GITHUB_OUTPUT")',
                        'brcat berbagi entri brotli: gerbang gagal (Audit 1844)', False)

    skenario_substitusi(tmp, 'sub951',
                        '> >(lbzcat -n 4; cat >> "$GITHUB_OUTPUT")',
                        'lbzcat berbagi entri lbzip2 - -n bernilai: gerbang gagal (Audit 1845, dikoreksi Audit 1866)', False)

    skenario_substitusi(tmp, 'sub952',
                        '> >(lbunzip2 -n 4; cat >> "$GITHUB_OUTPUT")',
                        'lbunzip2 berbagi entri lbzip2 - -n bernilai: gerbang gagal (Audit 1845, dikoreksi Audit 1866)', False)

    skenario_substitusi(tmp, 'sub953',
                        '> >(lbunzip2 --block-size 900; cat >> "$GITHUB_OUTPUT")',
                        '--block-size bukan opsi lbunzip2 - 900 operand berkas: gerbang lolos (Audit 1845, dikoreksi Audit 1866)', True)

    skenario_substitusi(tmp, 'sub954',
                        '> >(lziprecover -o hasil; cat >> "$GITHUB_OUTPUT")',
                        '-o lziprecover berkas keluaran bernilai - tanpa FILE ia mendekompresi stdin: gerbang gagal (Audit 1846)', False)

    skenario_substitusi(tmp, 'sub955',
                        '> >(lziprecover --output hasil; cat >> "$GITHUB_OUTPUT")',
                        '--output lziprecover bernilai: gerbang gagal (Audit 1846)', False)

    skenario_substitusi(tmp, 'sub956',
                        '> >(zstd --output-dir-flat dir; cat >> "$GITHUB_OUTPUT")',
                        '--output-dir-flat zstd bernilai direktori: gerbang gagal (Audit 1847)', False)

    skenario_substitusi(tmp, 'sub957',
                        '> >(zstd --patch-from ref; cat >> "$GITHUB_OUTPUT")',
                        '--patch-from zstd bernilai berkas rujukan: gerbang gagal (Audit 1847)', False)

    skenario_substitusi(tmp, 'sub958',
                        '> >(unzstd --output-dir-flat dir; cat >> "$GITHUB_OUTPUT")',
                        'unzstd memuat --output-dir-flat: gerbang gagal (Audit 1847)', False)

    skenario_substitusi(tmp, 'sub959',
                        '> >(zstdmt --output-dir-mirror dir; cat >> "$GITHUB_OUTPUT")',
                        'zstdmt memuat --output-dir-mirror: gerbang gagal (Audit 1847)', False)

    skenario_substitusi(tmp, 'sub960',
                        '> >(zstdcat --output-dir-flat dir; cat >> "$GITHUB_OUTPUT")',
                        'zstdcat memuat --output-dir-flat: gerbang gagal (Audit 1847)', False)

    skenario_substitusi(tmp, 'sub961',
                        '> >(mlr --from stdin.csv cat; cat >> "$GITHUB_OUTPUT")',
                        '--from mlr menamai berkas masukan - stdin tak dibaca: gerbang lolos (Audit 1848)', True)

    skenario_substitusi(tmp, 'sub962',
                        '> >(mlr --from a.dat --from b.dat cat; cat >> "$GITHUB_OUTPUT")',
                        '--from ganda mlr tetap berkas: gerbang lolos (Audit 1848)', True)

    skenario_substitusi(tmp, 'sub963',
                        '> >(mlr --from - cat; cat >> "$GITHUB_OUTPUT")',
                        '--from - mlr berarti stdin: gerbang gagal (Audit 1848)', False)

    skenario_substitusi(tmp, 'sub964',
                        '> >(mlr --ofmt %.3f cat; cat >> "$GITHUB_OUTPUT")',
                        'nilai --ofmt mlr ditelan, bukan berkas: gerbang gagal (Audit 1849)', False)

    skenario_substitusi(tmp, 'sub965',
                        '> >(mlr --seed 42 stats1 -a mean -f harga; cat >> "$GITHUB_OUTPUT")',
                        'nilai --seed mlr ditelan sebelum kata kerja: gerbang gagal (Audit 1849)', False)

    skenario_substitusi(tmp, 'sub966',
                        '> >(mlr --tz UTC cat; cat >> "$GITHUB_OUTPUT")',
                        'nilai --tz mlr ditelan: gerbang gagal (Audit 1849)', False)

    skenario_substitusi(tmp, 'sub967',
                        '> >(mlr --load skrip.mlr put \'$x=1\'; cat >> "$GITHUB_OUTPUT")',
                        '--load mlr berkas skrip DSL dan operand put ekspresi - tanpa berkas masukan membaca stdin: gerbang gagal (Audit 1849)', False)

    skenario_substitusi(tmp, 'sub968',
                        '> >(mlr --csv put \'$x=1\'; cat >> "$GITHUB_OUTPUT")',
                        'operand pertama put adalah ekspresi DSL, bukan berkas: gerbang gagal (Audit 1849)', False)

    skenario_substitusi(tmp, 'sub969',
                        '> >(mlr --mfrom data.csv cat; cat >> "$GITHUB_OUTPUT")',
                        '--mfrom mlr menamai berkas masukan: gerbang lolos (Audit 1849)', True)

    skenario_substitusi(tmp, 'sub970',
                        '> >(dasel -f stdin .; cat >> "$GITHUB_OUTPUT")',
                        'nilai harfiah stdin pada -f dasel berarti stdin: gerbang gagal (Audit 1850)', False)

    skenario_substitusi(tmp, 'sub971',
                        '> >(dasel --file=stdin .; cat >> "$GITHUB_OUTPUT")',
                        '--file=stdin dasel berarti stdin: gerbang gagal (Audit 1850)', False)

    skenario_substitusi(tmp, 'sub972',
                        '> >(dasel -f - .; cat >> "$GITHUB_OUTPUT")',
                        'dasel tak memperlakukan - sebagai stdin - -f apa pun berkas: gerbang lolos (Audit 1850)', True)

    skenario_substitusi(tmp, 'sub973',
                        '> >(yq -f -; cat >> "$GITHUB_OUTPUT")',
                        "yq -f = --front-matter, bukan from-file: 'yq -f -' membaca stdin: gerbang menolak (Audit 1851, dibalik Audit 2500)", False)

    skenario_substitusi(tmp, 'sub974',
                        '> >(yq --from-file skrip.yq .; cat >> "$GITHUB_OUTPUT")',
                        '--from-file ada di yq: ekspresi dari berkas, operand berkas: gerbang lolos (Audit 1851, teks dikoreksi Audit 2500)', True)

    skenario_substitusi(tmp, 'sub975',
                        '> >(xz -S .zz; cat >> "$GITHUB_OUTPUT")',
                        '-S xz bernilai sufiks - tanpa berkas ia membaca stdin: gerbang gagal (Audit 1852)', False)

    skenario_substitusi(tmp, 'sub976',
                        '> >(xz --suffix .zz; cat >> "$GITHUB_OUTPUT")',
                        '--suffix xz bernilai menyamai saudara xz lain: gerbang gagal (Audit 1852)', False)

    skenario_substitusi(tmp, 'sub977',
                        '> >(unxz --block-size 1MiB; cat >> "$GITHUB_OUTPUT")',
                        '--block-size unxz bernilai menyamai keluarga xz: gerbang gagal (Audit 1853)', False)

    skenario_substitusi(tmp, 'sub978',
                        '> >(derdump -i -; cat >> "$GITHUB_OUTPUT")',
                        'derdump -i - galat membuka - - tak membaca stdin: gerbang lolos (Audit 1854)', True)

    skenario_substitusi(tmp, 'sub979',
                        '> >(derdump -o hasil; cat >> "$GITHUB_OUTPUT")',
                        '-o derdump berkas keluaran - tanpa -i ia membaca stdin: gerbang gagal (Audit 1854)', False)

    skenario_substitusi(tmp, 'sub980',
                        '> >(mlr --files daftar.txt cat; cat >> "$GITHUB_OUTPUT")',
                        '--files mlr menamai berkas daftar masukan - stdin tak dibaca: gerbang lolos (Audit 1855)', True)

    skenario_substitusi(tmp, 'sub981',
                        '> >(mlr --files - cat; cat >> "$GITHUB_OUTPUT")',
                        '--files - mlr berarti stdin: gerbang gagal (Audit 1855)', False)

    skenario_substitusi(tmp, 'sub982',
                        '> >(mlr --ifs \';\' cat; cat >> "$GITHUB_OUTPUT")',
                        'nilai --ifs mlr pemisah bernilai, ditelan sebelum kata kerja: gerbang gagal (Audit 1856)', False)

    skenario_substitusi(tmp, 'sub983',
                        '> >(mlr --ofs \'|\' cat; cat >> "$GITHUB_OUTPUT")',
                        '--ofs mlr bernilai pemisah: gerbang gagal (Audit 1856)', False)

    skenario_substitusi(tmp, 'sub984',
                        '> >(mlr --rs lf cat; cat >> "$GITHUB_OUTPUT")',
                        '--rs mlr bernilai pemisah: gerbang gagal (Audit 1856)', False)

    skenario_substitusi(tmp, 'sub985',
                        '> >(mlr --ips-regex x cat; cat >> "$GITHUB_OUTPUT")',
                        '--ips-regex mlr bernilai pola pemisah: gerbang gagal (Audit 1856)', False)

    skenario_substitusi(tmp, 'sub986',
                        '> >(dasel -f=stdin .; cat >> "$GITHUB_OUTPUT")',
                        'bentuk -f=stdin dasel (pflag) berarti stdin: gerbang gagal (Audit 1857)', False)

    skenario_substitusi(tmp, 'sub987',
                        '> >(unzstd --patch-from ref; cat >> "$GITHUB_OUTPUT")',
                        '--patch-from berlaku juga di mode dekompresi unzstd: gerbang gagal (Audit 1858)', False)

    skenario_substitusi(tmp, 'sub988',
                        '> >(zstdcat --patch-from ref; cat >> "$GITHUB_OUTPUT")',
                        '--patch-from berlaku juga di zstdcat: gerbang gagal (Audit 1858)', False)

    skenario_substitusi(tmp, 'sub989',
                        '> >(c++filt -s gnu-v3; cat >> "$GITHUB_OUTPUT")',
                        '-s c++filt bernilai gaya demangling - tanpa operand ia membaca stdin: gerbang gagal (Audit 1859)', False)

    skenario_substitusi(tmp, 'sub990',
                        '> >(c++filt --format gnu-v3; cat >> "$GITHUB_OUTPUT")',
                        '--format c++filt bernilai: gerbang gagal (Audit 1859)', False)

    skenario_substitusi(tmp, 'sub991',
                        '> >(utmpdump -o hasil; cat >> "$GITHUB_OUTPUT")',
                        '-o utmpdump berkas keluaran - sumber bawaannya stdin: gerbang gagal (Audit 1860)', False)

    skenario_substitusi(tmp, 'sub992',
                        '> >(pinentry --timeout 30; cat >> "$GITHUB_OUTPUT")',
                        '--timeout pinentry bernilai detik - ia membaca perintah Assuan dari stdin: gerbang gagal (Audit 1861)', False)

    skenario_substitusi(tmp, 'sub993',
                        '> >(pinentry-curses --display :0; cat >> "$GITHUB_OUTPUT")',
                        '--display pinentry-curses bernilai - berbagi opsi pinentry: gerbang gagal (Audit 1862)', False)

    skenario_substitusi(tmp, 'sub994',
                        '> >(gpg-connect-agent --homedir /tmp; cat >> "$GITHUB_OUTPUT")',
                        '--homedir gpg-connect-agent bernilai - ia membaca perintah dari stdin: gerbang gagal (Audit 1863)', False)

    skenario_substitusi(tmp, 'sub995',
                        '> >(dirmngr --server --homedir /tmp; cat >> "$GITHUB_OUTPUT")',
                        'mode --server dirmngr membaca perintah Assuan dari stdin, --homedir bernilai: gerbang gagal (Audit 1864)', False)

    skenario_substitusi(tmp, 'sub996',
                        '> >(cpan -s -j konfig.pm; cat >> "$GITHUB_OUTPUT")',
                        '-s memaksa cpan masuk mode shell yang membaca perintah dari stdin: gerbang gagal (Audit 1865)', False)

    skenario_substitusi(tmp, 'sub997',
                        '> >(cpan -j konfig.pm; cat >> "$GITHUB_OUTPUT")',
                        'cpan -j tanpa -s mode pasang - tak membaca stdin: gerbang lolos (Audit 1865)', True)

    skenario_substitusi(tmp, 'sub998',
                        '> >(lbzip2 -n 4; cat >> "$GITHUB_OUTPUT")',
                        '-n lbzip2 satu-satunya opsi bernilai (jumlah benang): gerbang gagal (Audit 1866)', False)

    skenario_substitusi(tmp, 'sub999',
                        '> >(lbzip2 -b 2; cat >> "$GITHUB_OUTPUT")',
                        '-b bukan opsi lbzip2 - 2 adalah operand berkas: gerbang lolos (Audit 1866)', True)

    skenario_substitusi(tmp, 'sub1000',
                        '> >(plzip -B 1MiB; cat >> "$GITHUB_OUTPUT")',
                        '-B/--data-size plzip bernilai: gerbang gagal (Audit 1867)', False)

    skenario_substitusi(tmp, 'sub1001',
                        '> >(plzip --format lzip; cat >> "$GITHUB_OUTPUT")',
                        'plzip tak punya --format - nilainya operand: gerbang lolos (Audit 1867)', True)

    skenario_substitusi(tmp, 'sub1002',
                        '> >(zstdcat -o hasil; cat >> "$GITHUB_OUTPUT")',
                        '-o zstdcat berkas keluaran dari manual bersama zstd: gerbang gagal (Audit 1868)', False)

    skenario_substitusi(tmp, 'sub1003',
                        '> >(pigz -X indeks; cat >> "$GITHUB_OUTPUT")',
                        '-X/--index pigz bernilai berkas indeks: gerbang gagal (Audit 1869)', False)

    skenario_substitusi(tmp, 'sub1004',
                        '> >(unpigz --index indeks; cat >> "$GITHUB_OUTPUT")',
                        '--index unpigz bernilai menyamai pigz: gerbang gagal (Audit 1869)', False)

    skenario_substitusi(tmp, 'sub1005',
                        '> >(bunzip2 -S .zz berkas.bz2; cat >> "$GITHUB_OUTPUT")',
                        'bzip2 tak punya -S - berkas.bz2 operand sungguhan: gerbang lolos (Audit 1870)', True)

    skenario_substitusi(tmp, 'sub1006',
                        '> >(newusers -c SHA512; cat >> "$GITHUB_OUTPUT")',
                        '-c/--crypt-method newusers bernilai menyamai chpasswd: gerbang gagal (Audit 1871)', False)

    skenario_substitusi(tmp, 'sub1007',
                        '> >(doas cpan -s; cat >> "$GITHUB_OUTPUT")',
                        'doas pembungkus pewaris stdin sekelas sudo: gerbang gagal (Audit 1872)', False)

    skenario_substitusi(tmp, 'sub1008',
                        '> >(watch -n 1 cpan -s; cat >> "$GITHUB_OUTPUT")',
                        'watch meneruskan stdin ke anaknya, -n bernilai selang: gerbang gagal (Audit 1873)', False)

    skenario_substitusi(tmp, 'sub1009',
                        '> >(systemd-run --pipe cpan -s; cat >> "$GITHUB_OUTPUT")',
                        '--pipe systemd-run mewarisi stdio pemanggil seperti --scope: gerbang gagal (Audit 1874)', False)

    skenario_substitusi(tmp, 'sub1010',
                        '> >(sleep 1 & cpan -s; cat >> "$GITHUB_OUTPUT")',
                        'perintah sesudah & tunggal adalah segmen sendiri: gerbang gagal (Audit 1875)', False)

    skenario_substitusi(tmp, 'sub1012',
                        '> >(systemd-run --pipe=x cpan -s; cat >> "$GITHUB_OUTPUT")',
                        '--pipe berargumen ditolak systemd-run: gerbang lolos (Audit 1876)', True)

    skenario_substitusi(tmp, 'sub1013',
                        '> >(systemd-run --pipe --scope cpan -s; cat >> "$GITHUB_OUTPUT")',
                        '--pipe + --scope konflik: gerbang lolos (Audit 1876)', True)

    skenario_substitusi(tmp, 'sub1014',
                        '> >(watch -tn 1 cpan -s; cat >> "$GITHUB_OUTPUT")',
                        'bundel watch -tn: -n di ujung menelan nilai: gerbang gagal (Audit 1877)', False)

    skenario_substitusi(tmp, 'sub1015',
                        '> >(watch -nt cpan -s; cat >> "$GITHUB_OUTPUT")',
                        'bundel watch -nt: nilai bundel tak sah, anak tak jalan: gerbang lolos (Audit 1877)', True)

    skenario_substitusi(tmp, 'sub1016',
                        '> >(tail -s 2; cat >> "$GITHUB_OUTPUT")',
                        '-s tail bernilai (interval tidur): gerbang gagal (Audit 1878)', False)

    skenario_substitusi(tmp, 'sub1017',
                        '> >(cpio -i -I berkas.cpio; cat >> "$GITHUB_OUTPUT")',
                        '-I cpio berkas arsip sekelas -F: gerbang lolos (Audit 1879)', True)

    skenario_substitusi(tmp, 'sub1018',
                        '> >(curl --json @- https://contoh.invalid; cat >> "$GITHUB_OUTPUT")',
                        '--json curl opsi data - @- dari stdin: gerbang gagal (Audit 1880)', False)

    skenario_substitusi(tmp, 'sub1019',
                        '> >(ssh host perintah; cat >> "$GITHUB_OUTPUT")',
                        'ssh meneruskan stdin ke sesi jauh: gerbang gagal (Audit 1881)', False)

    skenario_substitusi(tmp, 'sub1020',
                        '> >(ssh -n host perintah; cat >> "$GITHUB_OUTPUT")',
                        'ssh -n mengalihkan stdin dari /dev/null: gerbang lolos (Audit 1881)', True)

    skenario_substitusi(tmp, 'sub1021',
                        '> >(ssh host -n; cat >> "$GITHUB_OUTPUT")',
                        'opsi pemutus ssh sesudah tujuan berlaku: gerbang lolos (Audit 1887)', True)

    skenario_substitusi(tmp, 'sub1022',
                        '> >(ssh -; cat >> "$GITHUB_OUTPUT")',
                        "'-' tunggal bukan tujuan ssh: gerbang lolos (Audit 1888)", True)

    skenario_substitusi(tmp, 'sub1023',
                        '> >(rsh host perintah; cat >> "$GITHUB_OUTPUT")',
                        'rsh meneruskan stdin ke perintah jauh: gerbang gagal (Audit 1894)', False)

    skenario_substitusi(tmp, 'sub1024',
                        '> >(mosquitto_pub -t topik -s; cat >> "$GITHUB_OUTPUT")',
                        'mosquitto_pub -s membaca pesan dari stdin: gerbang gagal (Audit 1895)', False)

    skenario_substitusi(tmp, 'sub1025',
                        '> >(grpcurl -d @ host:443 svc/m; cat >> "$GITHUB_OUTPUT")',
                        'grpcurl -d @ membaca badan dari stdin: gerbang gagal (Audit 1896)', False)

    skenario_substitusi(tmp, 'sub1026',
                        '> >(kubectl exec -i pod -- perintah; cat >> "$GITHUB_OUTPUT")',
                        'kubectl exec -i meneruskan stdin: gerbang gagal (Audit 1897)', False)

    skenario_substitusi(tmp, 'sub1027',
                        '> >(docker exec -i kontainer perintah; cat >> "$GITHUB_OUTPUT")',
                        'docker exec -i meneruskan stdin: gerbang gagal (Audit 1898)', False)

    skenario_substitusi(tmp, 'sub1028',
                        '> >(podman run -i citra; cat >> "$GITHUB_OUTPUT")',
                        'podman run -i mengonsumsi stdin: gerbang gagal (Audit 1899)', False)

    skenario_substitusi(tmp, 'sub1029',
                        '> >(terraform console; cat >> "$GITHUB_OUTPUT")',
                        'terraform console membaca perintah dari stdin: gerbang gagal (Audit 1900)', False)

    skenario_substitusi(tmp, 'sub1030',
                        '> >(websocat ws://contoh.invalid; cat >> "$GITHUB_OUTPUT")',
                        'websocat klien sederhana meneruskan stdin: gerbang gagal (Audit 1901)', False)

    skenario_substitusi(tmp, 'sub1031',
                        '> >(redis-cli; cat >> "$GITHUB_OUTPUT")',
                        'redis-cli polos terpipa membaca perintah dari stdin: gerbang gagal (Audit 1902)', False)

    skenario_substitusi(tmp, 'sub1032',
                        '> >(psql -f -; cat >> "$GITHUB_OUTPUT")',
                        'psql -f - membaca stdin: gerbang gagal (Audit 1903)', False)

    skenario_substitusi(tmp, 'sub1033',
                        '> >(mysql db; cat >> "$GITHUB_OUTPUT")',
                        'mysql polos terpipa membaca stdin: gerbang gagal (Audit 1904)', False)

    skenario_substitusi(tmp, 'sub1034',
                        '> >(mariadb db; cat >> "$GITHUB_OUTPUT")',
                        'mariadb polos terpipa membaca stdin: gerbang gagal (Audit 1905)', False)

    skenario_substitusi(tmp, 'sub1035',
                        '> >(socat -u FILE:x STDIO; cat >> "$GITHUB_OUTPUT")',
                        'socat -u: sumber berkas, stdin tak dikonsumsi: gerbang lolos (Audit 1906)', True)

    skenario_substitusi(tmp, 'sub1036',
                        '> >(sftp -P - host; cat >> "$GITHUB_OUTPUT")',
                        "nilai '-' opsi sftp gugur parsing: gerbang lolos (Audit 1907)", True)

    skenario_substitusi(tmp, 'sub1037',
                        '> >(sftp host:berkas lokal; cat >> "$GITHUB_OUTPUT")',
                        'sftp host:berkas lokal mode unduh otomatis: gerbang lolos (Audit 1908)', True)

    skenario_substitusi(tmp, 'sub1038',
                        '> >(nc -I 10; cat >> "$GITHUB_OUTPUT")',
                        '-I nc bernilai - tanpa host ia galat: gerbang lolos (Audit 1909)', True)

    skenario_substitusi(tmp, 'sub1039',
                        '> >(ncat --recv-only host 80; cat >> "$GITHUB_OUTPUT")',
                        'ncat --recv-only tak meneruskan stdin: gerbang lolos (Audit 1910)', True)

    skenario_substitusi(tmp, 'sub1040',
                        '> >(ssh host -p; cat >> "$GITHUB_OUTPUT")',
                        'opsi ssh menggantung sesudah tujuan galat: gerbang lolos (Audit 1911)', True)

    skenario_substitusi(tmp, 'sub1041',
                        '> >(ssh -- -n; cat >> "$GITHUB_OUTPUT")',
                        "'-n' sesudah -- bukan tujuan ssh: gerbang lolos (Audit 1912)", True)

    skenario_substitusi(tmp, 'sub1042',
                        '> >(psql -c x -f -; cat >> "$GITHUB_OUTPUT")',
                        'psql memproses -c/-f berurutan: -f - tetap membaca: gerbang gagal (Audit 1913)', False)

    skenario_substitusi(tmp, 'sub1043',
                        '> >(mysql --execute=select; cat >> "$GITHUB_OUTPUT")',
                        '--execute= mysql menjalankan lalu keluar: gerbang lolos (Audit 1914)', True)

    skenario_substitusi(tmp, 'sub1044',
                        '> >(mariadb --execute=select; cat >> "$GITHUB_OUTPUT")',
                        '--execute= mariadb menjalankan lalu keluar: gerbang lolos (Audit 1915)', True)

    skenario_substitusi(tmp, 'sub1045',
                        '> >(docker exec --interactive x c; cat >> "$GITHUB_OUTPUT")',
                        '--interactive docker meneruskan stdin: gerbang gagal (Audit 1916)', False)

    skenario_substitusi(tmp, 'sub1046',
                        '> >(docker exec x grep -i pola berkas; cat >> "$GITHUB_OUTPUT")',
                        "'-i' wilayah perintah docker bukan bendera: gerbang lolos (Audit 1918)", True)

    skenario_substitusi(tmp, 'sub1047',
                        '> >(docker run -w/dir citra; cat >> "$GITHUB_OUTPUT")',
                        'bundel -w docker bernilai bukan -i: gerbang lolos (Audit 1921)', True)

    skenario_substitusi(tmp, 'sub1048',
                        '> >(podman run --interactive citra; cat >> "$GITHUB_OUTPUT")',
                        '--interactive podman meneruskan stdin: gerbang gagal (Audit 1917)', False)

    skenario_substitusi(tmp, 'sub1049',
                        '> >(kubectl exec pod -- grep -i x; cat >> "$GITHUB_OUTPUT")',
                        "'-i' sesudah -- kubectl milik perintah: gerbang lolos (Audit 1919)", True)

    skenario_substitusi(tmp, 'sub1050',
                        '> >(kubectl exec -cinit pod -- c; cat >> "$GITHUB_OUTPUT")',
                        'bundel -c kubectl bernilai bukan -i: gerbang lolos (Audit 1920)', True)

    skenario_substitusi(tmp, 'sub1051',
                        '> >(mosquitto_pub -t t -sl; cat >> "$GITHUB_OUTPUT")',
                        'bundel -sl mosquitto_pub mode stdin: gerbang gagal (Audit 1922)', False)

    skenario_substitusi(tmp, 'sub1052',
                        '> >(redis-cli -a -x get k; cat >> "$GITHUB_OUTPUT")',
                        "'-x' nilai -a redis-cli bukan saklar: gerbang lolos (Audit 1923)", True)

    skenario_substitusi(tmp, 'sub1053',
                        '> >(nerdctl run -i citra; cat >> "$GITHUB_OUTPUT")',
                        'nerdctl run -i meneruskan stdin: gerbang gagal (Audit 1926)', False)

    skenario_substitusi(tmp, 'sub1054',
                        '> >(oc exec -i pod -- c; cat >> "$GITHUB_OUTPUT")',
                        'oc exec -i meneruskan stdin: gerbang gagal (Audit 1927)', False)

    skenario_substitusi(tmp, 'sub1055',
                        '> >(consul kv put kunci -; cat >> "$GITHUB_OUTPUT")',
                        "consul kv put '-' membaca nilai dari stdin: gerbang gagal (Audit 1928)", False)

    skenario_substitusi(tmp, 'sub1056',
                        '> >(vault kv put jalur -; cat >> "$GITHUB_OUTPUT")',
                        "vault kv put '-' membaca nilai dari stdin: gerbang gagal (Audit 1929)", False)

    skenario_substitusi(tmp, 'sub1057',
                        '> >(etcdctl put kunci; cat >> "$GITHUB_OUTPUT")',
                        'etcdctl put tanpa nilai membaca stdin: gerbang gagal (Audit 1930)', False)

    skenario_substitusi(tmp, 'sub1058',
                        '> >(gh api --input -; cat >> "$GITHUB_OUTPUT")',
                        'gh api --input - membaca badan dari stdin: gerbang gagal (Audit 1931)', False)

    skenario_substitusi(tmp, 'sub1059',
                        '> >(glab api --input -; cat >> "$GITHUB_OUTPUT")',
                        'glab api --input - membaca badan dari stdin: gerbang gagal (Audit 1932)', False)

    skenario_substitusi(tmp, 'sub1060',
                        '> >(clickhouse-client; cat >> "$GITHUB_OUTPUT")',
                        'clickhouse-client polos membaca query dari stdin: gerbang gagal (Audit 1933)', False)

    skenario_substitusi(tmp, 'sub1061',
                        '> >(aws s3 cp - s3://bkt/k; cat >> "$GITHUB_OUTPUT")',
                        "aws s3 cp dari '-' mengunggah stdin: gerbang gagal (Audit 1934)", False)

    skenario_substitusi(tmp, 'sub1062',
                        '> >(gsutil cp - gs://bkt/k; cat >> "$GITHUB_OUTPUT")',
                        "gsutil cp dari '-' mengunggah stdin: gerbang gagal (Audit 1935)", False)

    skenario_substitusi(tmp, 'sub1063',
                        '> >(gcloud storage cp - gs://bkt/k; cat >> "$GITHUB_OUTPUT")',
                        "gcloud storage cp dari '-' mengunggah stdin: gerbang gagal (Audit 1936)", False)

    skenario_substitusi(tmp, 'sub1064',
                        '> >(buildah build --stdin ctx; cat >> "$GITHUB_OUTPUT")',
                        'buildah build --stdin meneruskan stdin: gerbang gagal (Audit 1937)', False)

    skenario_substitusi(tmp, 'sub1065',
                        '> >(skopeo copy docker-archive:/dev/stdin docker://x; cat >> "$GITHUB_OUTPUT")',
                        'skopeo copy dari /dev/stdin membaca aliran: gerbang gagal (Audit 1938)', False)

    skenario_substitusi(tmp, 'sub1066',
                        '> >(sqlite3 -version; cat >> "$GITHUB_OUTPUT")',
                        '-version sqlite3 informasional strip-tunggal: gerbang lolos (Audit 1939)', True)

    skenario_substitusi(tmp, 'sub1067',
                        '> >(crictl exec -i id perintah; cat >> "$GITHUB_OUTPUT")',
                        'crictl exec -i meneruskan stdin: gerbang gagal (Audit 1940)', False)

    skenario_substitusi(tmp, 'sub1068',
                        '> >(clickhouse-client --query select; cat >> "$GITHUB_OUTPUT")',
                        'clickhouse --query tetap menambahkan stdin: gerbang gagal (Audit 1941)', False)

    skenario_substitusi(tmp, 'sub1069',
                        '> >(aws --region x s3 cp - s3://b/k; cat >> "$GITHUB_OUTPUT")',
                        "aws opsi global sebelum s3 cp dari '-': gerbang gagal (Audit 1942)", False)

    skenario_substitusi(tmp, 'sub1070',
                        '> >(mysql -psecret db; cat >> "$GITHUB_OUTPUT")',
                        "'e' di nilai -p mysql bukan -e: gerbang gagal menguras (Audit 1943)", False)

    skenario_substitusi(tmp, 'sub1071',
                        '> >(mariadb -psecret db; cat >> "$GITHUB_OUTPUT")',
                        "'e' di nilai -p mariadb bukan -e: gerbang gagal menguras (Audit 1944)", False)

    skenario_substitusi(tmp, 'sub1072',
                        '> >(consul kv put - kunci; cat >> "$GITHUB_OUTPUT")',
                        "'-' posisi kunci consul bukan stdin: gerbang lolos (Audit 1945)", True)

    skenario_substitusi(tmp, 'sub1073',
                        '> >(mosquitto_pub -t -s; cat >> "$GITHUB_OUTPUT")',
                        "'-s' nilai -t mosquitto bukan mode stdin: gerbang lolos (Audit 1946)", True)

    skenario_substitusi(tmp, 'sub1074',
                        '> >(vault kv put jalur catatan=teks=-; cat >> "$GITHUB_OUTPUT")',
                        "nilai vault berakhiran '=-' di tengah bukan stdin: gerbang lolos (Audit 1947)", True)

    skenario_substitusi(tmp, 'sub1075',
                        '> >(crictl exec -c -i id perintah; cat >> "$GITHUB_OUTPUT")',
                        'opsi tak dikenal crictl galat parsing: gerbang lolos (Audit 1948)', True)

    skenario_substitusi(tmp, 'sub1076',
                        '> >(gh --hostname ghexample --input - auth; cat >> "$GITHUB_OUTPUT")',
                        '--input gh di luar konteks sub-perintah bukan saklar: gerbang lolos (Audit 1949)', True)

    skenario_substitusi(tmp, 'sub1077',
                        '> >(rclone rcat jauh:berkas; cat >> "$GITHUB_OUTPUT")',
                        'rclone rcat menyalin stdin: gerbang gagal (Audit 1950)', False)

    skenario_substitusi(tmp, 'sub1078',
                        '> >(restic backup --stdin; cat >> "$GITHUB_OUTPUT")',
                        'restic backup --stdin membaca stdin: gerbang gagal (Audit 1951)', False)

    skenario_substitusi(tmp, 'sub1079',
                        '> >(borg create repo::arsip -; cat >> "$GITHUB_OUTPUT")',
                        "borg create path '-' membaca stdin: gerbang gagal (Audit 1952)", False)

    skenario_substitusi(tmp, 'sub1080',
                        '> >(ipfs dag put; cat >> "$GITHUB_OUTPUT")',
                        'ipfs dag put membaca stdin: gerbang gagal (Audit 1953)', False)

    skenario_substitusi(tmp, 'sub1081',
                        '> >(rsync --exclude-from=- src dst; cat >> "$GITHUB_OUTPUT")',
                        'rsync --exclude-from=- membaca daftar dari stdin: gerbang gagal (Audit 1954)', False)

    skenario_substitusi(tmp, 'sub1082',
                        '> >(mysql -h -e x db; cat >> "$GITHUB_OUTPUT")',
                        "'-e' nilai -h mysql bukan -e: gerbang gagal menguras (Audit 1955)", False)

    skenario_substitusi(tmp, 'sub1083',
                        '> >(mariadb -u -e db; cat >> "$GITHUB_OUTPUT")',
                        "'-e' nilai -u mariadb bukan -e: gerbang gagal menguras (Audit 1956)", False)

    skenario_substitusi(tmp, 'sub1084',
                        '> >(rclone rcat; cat >> "$GITHUB_OUTPUT")',
                        'rclone rcat tanpa tujuan galat: gerbang lolos (Audit 1957)', True)

    skenario_substitusi(tmp, 'sub1085',
                        '> >(rclone --files-from daftar rcat jauh:x; cat >> "$GITHUB_OUTPUT")',
                        'opsi global rclone tak merusak sub: gerbang gagal (Audit 1958)', False)

    skenario_substitusi(tmp, 'sub1086',
                        '> >(restic --repo x backup --stdin; cat >> "$GITHUB_OUTPUT")',
                        'opsi global restic tak merusak sub: gerbang gagal (Audit 1959)', False)

    skenario_substitusi(tmp, 'sub1087',
                        '> >(restic backup --stdin=true; cat >> "$GITHUB_OUTPUT")',
                        '--stdin=true restic membaca stdin: gerbang gagal (Audit 1960)', False)

    skenario_substitusi(tmp, 'sub1088',
                        '> >(ipfs --api /ip4/x add; cat >> "$GITHUB_OUTPUT")',
                        'opsi global ipfs tak merusak sub: gerbang gagal (Audit 1961)', False)

    skenario_substitusi(tmp, 'sub1089',
                        '> >(ipfs dag put berkas.json; cat >> "$GITHUB_OUTPUT")',
                        'dag put berkas membaca berkas: gerbang lolos (Audit 1962)', True)

    skenario_substitusi(tmp, 'sub1090',
                        '> >(ipfs block put berkas; cat >> "$GITHUB_OUTPUT")',
                        'block put berkas membaca berkas: gerbang lolos (Audit 1963)', True)

    skenario_substitusi(tmp, 'sub1091',
                        '> >(mosquitto_pub -m -s; cat >> "$GITHUB_OUTPUT")',
                        "'-s' nilai -m mosquitto bukan stdin: gerbang lolos (Audit 1964)", True)

    skenario_substitusi(tmp, 'sub1092',
                        '> >(mosquitto_pub -c -s; cat >> "$GITHUB_OUTPUT")',
                        '-c bendera polos: -s mode stdin: gerbang gagal (Audit 1965)', False)

    skenario_substitusi(tmp, 'sub1093',
                        '> >(crictl exec -it id c; cat >> "$GITHUB_OUTPUT")',
                        'bundel -it crictl meneruskan stdin: gerbang gagal (Audit 1966)', False)

    skenario_substitusi(tmp, 'sub1094',
                        '> >(nano -; cat >> "$GITHUB_OUTPUT")',
                        'nano - membaca stdin: gerbang gagal (Audit 1968)', False)

    skenario_substitusi(tmp, 'sub1095',
                        '> >(gnuplot; cat >> "$GITHUB_OUTPUT")',
                        'gnuplot polos membaca stdin: gerbang gagal (Audit 1969)', False)

    skenario_substitusi(tmp, 'sub1096',
                        '> >(kak -f keys; cat >> "$GITHUB_OUTPUT")',
                        'kak -f memproses stdin: gerbang gagal (Audit 1970)', False)

    skenario_substitusi(tmp, 'sub1097',
                        '> >(micro; cat >> "$GITHUB_OUTPUT")',
                        'micro polos membuat buffer dari stdin: gerbang gagal (Audit 1971)', False)

    skenario_substitusi(tmp, 'sub1098',
                        '> >(pandoc; cat >> "$GITHUB_OUTPUT")',
                        'pandoc polos membaca stdin: gerbang gagal (Audit 1972)', False)

    skenario_substitusi(tmp, 'sub1099',
                        '> >(magick - png:x; cat >> "$GITHUB_OUTPUT")',
                        "magick '-' masukan stdin: gerbang gagal (Audit 1973)", False)

    skenario_substitusi(tmp, 'sub1100',
                        '> >(sendmail -t; cat >> "$GITHUB_OUTPUT")',
                        'sendmail -t membaca pesan dari stdin: gerbang gagal (Audit 1974)', False)

    skenario_substitusi(tmp, 'sub1101',
                        '> >(mailx budi@x.id; cat >> "$GITHUB_OUTPUT")',
                        'mailx mode kirim membaca isi dari stdin: gerbang gagal (Audit 1975)', False)

    skenario_substitusi(tmp, 'sub1102',
                        '> >(R --slave; cat >> "$GITHUB_OUTPUT")',
                        'R --slave membaca dari stdin: gerbang gagal (Audit 1976)', False)

    skenario_substitusi(tmp, 'sub1103',
                        '> >(gnuplot --persist; cat >> "$GITHUB_OUTPUT")',
                        '--persist bendera polos: gnuplot telanjang membaca stdin (Audit 1977)', False)

    skenario_substitusi(tmp, 'sub1104',
                        '> >(mailx -t; cat >> "$GITHUB_OUTPUT")',
                        'mailx -t membaca pesan dari stdin: gerbang gagal (Audit 1978)', False)

    skenario_substitusi(tmp, 'sub1105',
                        '> >(pandoc -; cat >> "$GITHUB_OUTPUT")',
                        "operand '-' pandoc adalah stdin: gerbang gagal (Audit 1979)", False)

    skenario_substitusi(tmp, 'sub1106',
                        '> >(R CMD BATCH skrip.R; cat >> "$GITHUB_OUTPUT")',
                        'R CMD BATCH membaca berkas: gerbang lolos (Audit 1980)', True)

    skenario_substitusi(tmp, 'sub1107',
                        '> >(mysql -h; cat >> "$GITHUB_OUTPUT")',
                        '-h menggantung mysql galat: gerbang lolos (Audit 1981)', True)

    skenario_substitusi(tmp, 'sub1108',
                        '> >(mariadb --host; cat >> "$GITHUB_OUTPUT")',
                        '--host menggantung mariadb galat: gerbang lolos (Audit 1982)', True)

    skenario_substitusi(tmp, 'sub1109',
                        '> >(magick identify -; cat >> "$GITHUB_OUTPUT")',
                        "magick identify '-' masukan stdin: gerbang gagal (Audit 1983)", False)

    skenario_substitusi(tmp, 'sub1110',
                        '> >(sendmail -F -bp budi@x.id; cat >> "$GITHUB_OUTPUT")',
                        "'-bp' nilai -F sendmail bukan mode: gerbang gagal (Audit 1984)", False)

    skenario_substitusi(tmp, 'sub1111',
                        '> >(kafka-console-producer; cat >> "$GITHUB_OUTPUT")',
                        'kafka producer membaca stdin: gerbang gagal (Audit 1985)', False)

    skenario_substitusi(tmp, 'sub1112',
                        '> >(duckdb; cat >> "$GITHUB_OUTPUT")',
                        'duckdb polos membaca stdin: gerbang gagal (Audit 1986)', False)

    skenario_substitusi(tmp, 'sub1113',
                        '> >(pg_restore -d dbku; cat >> "$GITHUB_OUTPUT")',
                        'pg_restore tanpa berkas membaca stdin: gerbang gagal (Audit 1987)', False)

    skenario_substitusi(tmp, 'sub1114',
                        '> >(nats pub subjek; cat >> "$GITHUB_OUTPUT")',
                        'nats pub subjek saja membaca body dari stdin: gerbang gagal (Audit 1988)', False)

    skenario_substitusi(tmp, 'sub1115',
                        '> >(cypher-shell; cat >> "$GITHUB_OUTPUT")',
                        'cypher-shell polos membaca stdin: gerbang gagal (Audit 1989)', False)

    skenario_substitusi(tmp, 'sub1116',
                        '> >(cqlsh; cat >> "$GITHUB_OUTPUT")',
                        'cqlsh polos membaca stdin: gerbang gagal (Audit 1990)', False)

    skenario_substitusi(tmp, 'sub1117',
                        '> >(clickhouse client; cat >> "$GITHUB_OUTPUT")',
                        'clickhouse client multicall membaca stdin: gerbang gagal (Audit 1991)', False)

    skenario_substitusi(tmp, 'sub1118',
                        '> >(snowsql; cat >> "$GITHUB_OUTPUT")',
                        'snowsql polos membaca stdin: gerbang gagal (Audit 1992)', False)

    skenario_substitusi(tmp, 'sub1119',
                        '> >(redis-cli --bigkeys; cat >> "$GITHUB_OUTPUT")',
                        'mode khusus redis-cli tak membaca stdin: gerbang lolos (Audit 1993)', True)

    skenario_substitusi(tmp, 'sub1120',
                        '> >(duckdb -s select; cat >> "$GITHUB_OUTPUT")',
                        '-s duckdb penjalan perintah: gerbang lolos (Audit 1994)', True)

    skenario_substitusi(tmp, 'sub1121',
                        '> >(duckdb -cmd x db; cat >> "$GITHUB_OUTPUT")',
                        '-cmd berjalan sebelum stdin duckdb: gerbang gagal (Audit 1997)', False)

    skenario_substitusi(tmp, 'sub1122',
                        '> >(R CMD BATCH -; cat >> "$GITHUB_OUTPUT")',
                        'R CMD BATCH - berakhir galat, bukan stdin: gerbang lolos (Audit 2014)', True)

    skenario_substitusi(tmp, 'sub1123',
                        '> >(docker build -f - .; cat >> "$GITHUB_OUTPUT")',
                        'docker build -f - dari stdin: gerbang gagal (Audit 2004)', False)

    skenario_substitusi(tmp, 'sub1124',
                        '> >(docker import -; cat >> "$GITHUB_OUTPUT")',
                        'docker import - dari stdin: gerbang gagal (Audit 2005)', False)

    skenario_substitusi(tmp, 'sub1125',
                        '> >(docker load; cat >> "$GITHUB_OUTPUT")',
                        'docker load tanpa -i dari stdin: gerbang gagal (Audit 2006)', False)

    skenario_substitusi(tmp, 'sub1126',
                        '> >(podman import -; cat >> "$GITHUB_OUTPUT")',
                        'podman import - dari stdin: gerbang gagal (Audit 2007)', False)

    skenario_substitusi(tmp, 'sub1127',
                        '> >(podman build -f - .; cat >> "$GITHUB_OUTPUT")',
                        'podman build -f - dari stdin: gerbang gagal (Audit 2008)', False)

    skenario_substitusi(tmp, 'sub1128',
                        '> >(podman load; cat >> "$GITHUB_OUTPUT")',
                        'podman load tanpa -i dari stdin: gerbang gagal (Audit 2009)', False)

    skenario_substitusi(tmp, 'sub1129',
                        '> >(buildah bud -f - .; cat >> "$GITHUB_OUTPUT")',
                        'buildah bud -f - dari stdin: gerbang gagal (Audit 2010)', False)

    skenario_substitusi(tmp, 'sub1130',
                        '> >(az rest --body @-; cat >> "$GITHUB_OUTPUT")',
                        'az rest --body @- dari pipa: gerbang gagal (Audit 2011)', False)

    skenario_substitusi(tmp, 'sub1131',
                        '> >(vagrant ssh; cat >> "$GITHUB_OUTPUT")',
                        'vagrant ssh sesi interaktif: gerbang gagal (Audit 2012)', False)

    skenario_substitusi(tmp, 'sub1132',
                        '> >(minikube ssh; cat >> "$GITHUB_OUTPUT")',
                        'minikube ssh sesi interaktif: gerbang gagal (Audit 2013)', False)

    skenario_substitusi(tmp, 'sub1133',
                        '> >(R CMD BATCH -- -; cat >> "$GITHUB_OUTPUT")',
                        'R CMD BATCH -- - adalah R -f -: gerbang gagal (Audit 2015)', False)

    skenario_substitusi(tmp, 'sub1134',
                        '> >(duckdb -no-stdin db; cat >> "$GITHUB_OUTPUT")',
                        '-no-stdin duckdb menekan stdin: gerbang lolos (Audit 2016)', True)

    skenario_substitusi(tmp, 'sub1135',
                        '> >(duckdb -cmd -c db; cat >> "$GITHUB_OUTPUT")',
                        '-c sebagai nilai -cmd duckdb bukan penekan: gerbang gagal (Audit 2017)', False)

    skenario_substitusi(tmp, 'sub1136',
                        '> >(docker --config build load; cat >> "$GITHUB_OUTPUT")',
                        'sub docker sadar nilai opsi global: gerbang gagal (Audit 2018)', False)

    skenario_substitusi(tmp, 'sub1137',
                        '> >(docker ps --format load; cat >> "$GITHUB_OUTPUT")',
                        'load sebagai nilai --format bukan sub: gerbang lolos (Audit 2018)', True)

    skenario_substitusi(tmp, 'sub1138',
                        '> >(podman machine ssh; cat >> "$GITHUB_OUTPUT")',
                        'podman machine ssh sesi interaktif: gerbang gagal (Audit 2020)', False)

    skenario_substitusi(tmp, 'sub1140',
                        '> >(minikube -p profil ssh; cat >> "$GITHUB_OUTPUT")',
                        '-p minikube bernilai: gerbang gagal (Audit 2022)', False)

    skenario_substitusi(tmp, 'sub1141',
                        '> >(minikube ssh -h; cat >> "$GITHUB_OUTPUT")',
                        '-h minikube bantuan: gerbang lolos (Audit 2023)', True)

    skenario_substitusi(tmp, 'sub1142',
                        '> >(vagrant ssh -h; cat >> "$GITHUB_OUTPUT")',
                        '-h vagrant bantuan: gerbang lolos (Audit 2024)', True)

    skenario_substitusi(tmp, 'sub1143',
                        '> >(vagrant ssh -- -n; cat >> "$GITHUB_OUTPUT")',
                        '-n diteruskan ke ssh oleh vagrant: gerbang lolos (Audit 2025)', True)

    skenario_substitusi(tmp, 'sub1139',
                        '> >(docker build -f- .; cat >> "$GITHUB_OUTPUT")',
                        'bentuk merekat -f- docker build: gerbang gagal (Audit 2021)', False)

    skenario_substitusi(tmp, 'sub1144',
                        '> >(buildah bud --label --stdin .; cat >> "$GITHUB_OUTPUT")',
                        '--stdin sebagai nilai --label buildah: gerbang lolos (Audit 2027)', True)

    skenario_substitusi(tmp, 'sub1145',
                        '> >(ffmpeg -i berkas.mp4 out.mp4; cat >> "$GITHUB_OUTPUT")',
                        'interaksi stdin ffmpeg bawaan aktif: gerbang gagal (Audit 2033)', False)

    skenario_substitusi(tmp, 'sub1146',
                        '> >(ffmpeg -nostdin -i berkas.mp4 out.mp4; cat >> "$GITHUB_OUTPUT")',
                        '-nostdin ffmpeg menekan interaksi: gerbang lolos (Audit 2033)', True)

    skenario_substitusi(tmp, 'sub1147',
                        '> >(mpv -; cat >> "$GITHUB_OUTPUT")',
                        "mpv '-' membaca media dari stdin: gerbang gagal (Audit 2034)", False)

    skenario_substitusi(tmp, 'sub1148',
                        '> >(sox - out.wav; cat >> "$GITHUB_OUTPUT")',
                        "sox '-' masukan adalah stdin: gerbang gagal (Audit 2035)", False)

    skenario_substitusi(tmp, 'sub1149',
                        '> >(tesseract - stdout; cat >> "$GITHUB_OUTPUT")',
                        "tesseract '-' masukan adalah stdin: gerbang gagal (Audit 2036)", False)

    skenario_substitusi(tmp, 'sub1150',
                        '> >(guix repl; cat >> "$GITHUB_OUTPUT")',
                        'guix repl membaca stdin: gerbang gagal (Audit 2037)', False)

    skenario_substitusi(tmp, 'sub1151',
                        '> >(nix eval --arg-from-stdin x; cat >> "$GITHUB_OUTPUT")',
                        '--arg-from-stdin nix meneruskan stdin: gerbang gagal (Audit 2038)', False)

    skenario_substitusi(tmp, 'sub1152',
                        '> >(rlogin host; cat >> "$GITHUB_OUTPUT")',
                        'rlogin sesi interaktif: gerbang gagal (Audit 2039)', False)

    skenario_substitusi(tmp, 'sub1153',
                        '> >(rsh host; cat >> "$GITHUB_OUTPUT")',
                        'rsh tanpa perintah mode rlogin: gerbang gagal (Audit 2039)', False)

    skenario_substitusi(tmp, 'sub1154',
                        '> >(gst-launch-1.0 fdsrc fd=0 ! fakesink; cat >> "$GITHUB_OUTPUT")',
                        'fdsrc fd=0 membaca stdin: gerbang gagal (Audit 2040)', False)

    skenario_substitusi(tmp, 'sub1155',
                        '> >(R CMD BATCH --vanilla -- -; cat >> "$GITHUB_OUTPUT")',
                        "opsi BATCH sebelum '-- -': gerbang gagal (Audit 2041)", False)

    skenario_substitusi(tmp, 'sub1156',
                        '> >(docker --tlscert cert.pem load; cat >> "$GITHUB_OUTPUT")',
                        'opsi TLS global docker bernilai: gerbang gagal (Audit 2042)', False)

    skenario_substitusi(tmp, 'sub1157',
                        '> >(podman --storage-driver build load; cat >> "$GITHUB_OUTPUT")',
                        'opsi global podman tambahan bernilai: gerbang gagal (Audit 2043)', False)

    skenario_substitusi(tmp, 'sub1158',
                        '> >(buildah bud -v --stdin .; cat >> "$GITHUB_OUTPUT")',
                        '-v buildah menelan --stdin: gerbang lolos (Audit 2044)', True)

    skenario_substitusi(tmp, 'sub1159',
                        '> >(minikube --user bob ssh; cat >> "$GITHUB_OUTPUT")',
                        'opsi warisan minikube --user bernilai: gerbang gagal (Audit 2045)', False)

    skenario_substitusi(tmp, 'sub1160',
                        '> >(vagrant ssh --command -h; cat >> "$GITHUB_OUTPUT")',
                        '-h nilai --command vagrant bukan bantuan: gerbang gagal (Audit 2046)', False)

    skenario_substitusi(tmp, 'sub1161',
                        '> >(guix repl -t machine; cat >> "$GITHUB_OUTPUT")',
                        '-t guix repl bernilai: gerbang gagal (Audit 2047)', False)

    skenario_substitusi(tmp, 'sub1162',
                        '> >(irb; cat >> "$GITHUB_OUTPUT")',
                        'irb membaca ekspresi dari stdin: gerbang gagal (Audit 2052)', False)

    skenario_substitusi(tmp, 'sub1163',
                        '> >(litecli; cat >> "$GITHUB_OUTPUT")',
                        'litecli membaca stdin saat terpipa: gerbang gagal (Audit 2053)', False)

    skenario_substitusi(tmp, 'sub1164',
                        '> >(mycli; cat >> "$GITHUB_OUTPUT")',
                        'mycli modus batch dari stdin: gerbang gagal (Audit 2054)', False)

    skenario_substitusi(tmp, 'sub1165',
                        '> >(usql; cat >> "$GITHUB_OUTPUT")',
                        'usql membaca masukan dari pipa: gerbang gagal (Audit 2055)', False)

    skenario_substitusi(tmp, 'sub1166',
                        '> >(helm install x repo/c -f -; cat >> "$GITHUB_OUTPUT")',
                        "helm values '-' dari stdin: gerbang gagal (Audit 2056)", False)

    skenario_substitusi(tmp, 'sub1167',
                        '> >(sbcl; cat >> "$GITHUB_OUTPUT")',
                        'REPL sbcl berjalan di stdin: gerbang gagal (Audit 2057)', False)

    skenario_substitusi(tmp, 'sub1168',
                        '> >(racket; cat >> "$GITHUB_OUTPUT")',
                        'racket polos masuk REPL: gerbang gagal (Audit 2058)', False)

    skenario_substitusi(tmp, 'sub1169',
                        '> >(docker -c ctx load; cat >> "$GITHUB_OUTPUT")',
                        'alias -c docker bernilai: gerbang gagal (Audit 2059)', False)

    skenario_substitusi(tmp, 'sub1170',
                        '> >(podman --conmon /x load; cat >> "$GITHUB_OUTPUT")',
                        'opsi global podman --conmon bernilai: gerbang gagal (Audit 2060)', False)

    skenario_substitusi(tmp, 'sub1171',
                        '> >(buildah bud --add-host --stdin .; cat >> "$GITHUB_OUTPUT")',
                        '--add-host buildah menelan --stdin: gerbang lolos (Audit 2061)', True)

    skenario_substitusi(tmp, 'sub1172',
                        '> >(buildah --root /x bud --stdin .; cat >> "$GITHUB_OUTPUT")',
                        'opsi global buildah sebelum sub bernilai: gerbang gagal (Audit 2062)', False)

    skenario_substitusi(tmp, 'sub1173',
                        '> >(minikube -v 5 ssh; cat >> "$GITHUB_OUTPUT")',
                        'opsi warisan minikube -v bernilai: gerbang gagal (Audit 2063)', False)

    skenario_substitusi(tmp, 'sub1174',
                        '> >(sbcl --quit; cat >> "$GITHUB_OUTPUT")',
                        '--quit sbcl menekan REPL: gerbang lolos (Audit 2064)', True)

    skenario_substitusi(tmp, 'sub1175',
                        '> >(parallel echo; cat >> "$GITHUB_OUTPUT")',
                        'GNU parallel membangun perintah dari stdin: gerbang gagal (Audit 2069)', False)

    skenario_substitusi(tmp, 'sub1176',
                        '> >(entr make; cat >> "$GITHUB_OUTPUT")',
                        'entr membaca daftar berkas dari stdin: gerbang gagal (Audit 2070)', False)

    skenario_substitusi(tmp, 'sub1177',
                        '> >(pv; cat >> "$GITHUB_OUTPUT")',
                        'pv tanpa operand membaca stdin: gerbang gagal (Audit 2071)', False)

    skenario_substitusi(tmp, 'sub1179',
                        '> >(rev -; cat >> "$GITHUB_OUTPUT")',
                        "'-' bagi rev nama berkas harfiah: gerbang lolos (Audit 2073)", True)

    skenario_substitusi(tmp, 'sub1178',
                        '> >(xonsh; cat >> "$GITHUB_OUTPUT")',
                        'xonsh terpipa mode script-from-stdin: gerbang gagal (Audit 2072)', False)

    skenario_substitusi(tmp, 'sub1180',
                        '> >(podman --tls-cert cert.pem load; cat >> "$GITHUB_OUTPUT")',
                        'opsi TLS global podman bernilai: gerbang gagal (Audit 2075)', False)

    skenario_substitusi(tmp, 'sub1181',
                        '> >(buildah --log-level debug bud --stdin .; cat >> "$GITHUB_OUTPUT")',
                        'opsi global buildah --log-level bernilai: gerbang gagal (Audit 2076)', False)

    skenario_substitusi(tmp, 'sub1182',
                        '> >(buildah bud --cache-from --stdin .; cat >> "$GITHUB_OUTPUT")',
                        '--cache-from buildah menelan --stdin: gerbang lolos (Audit 2077)', True)

    skenario_substitusi(tmp, 'sub1183',
                        '> >(buildah --root --stdin bud; cat >> "$GITHUB_OUTPUT")',
                        '--stdin nilai --root bukan saklar: gerbang lolos (Audit 2078)', True)

    skenario_substitusi(tmp, 'sub1184',
                        '> >(minikube --log_file /tmp/x ssh; cat >> "$GITHUB_OUTPUT")',
                        '--log_file minikube bernilai: gerbang gagal (Audit 2079)', False)

    skenario_substitusi(tmp, 'sub1185',
                        '> >(docker -v load; cat >> "$GITHUB_OUTPUT")',
                        '-v pendek docker informasional: gerbang lolos (Audit 2084)', True)

    skenario_substitusi(tmp, 'sub1186',
                        '> >(docker -Dc ctx load; cat >> "$GITHUB_OUTPUT")',
                        'bundel -Dc docker diurai: gerbang gagal (Audit 2080)', False)

    skenario_substitusi(tmp, 'sub1187',
                        '> >(pv -F berkas; cat >> "$GITHUB_OUTPUT")',
                        '-F pv bernilai menelan nama berkas: gerbang gagal (Audit 2081)', False)

    skenario_substitusi(tmp, 'sub1188',
                        '> >(xonsh --rc a.xsh skrip.xsh; cat >> "$GITHUB_OUTPUT")',
                        '--rc xonsh jamak: gerbang gagal (Audit 2082)', False)

    skenario_substitusi(tmp, 'sub1189',
                        '> >(xonsh -st readline; cat >> "$GITHUB_OUTPUT")',
                        '-st xonsh bernilai: gerbang gagal (Audit 2083)', False)

    skenario_substitusi(tmp, 'sub1190',
                        '> >(parallel --semaphore echo; cat >> "$GITHUB_OUTPUT")',
                        '--semaphore parallel dari argv: gerbang lolos (Audit 2085)', True)

    skenario_substitusi(tmp, 'sub1191',
                        '> >(exiftool -; cat >> "$GITHUB_OUTPUT")',
                        "exiftool '-' membaca stdin: gerbang gagal (Audit 2086)", False)

    skenario_substitusi(tmp, 'sub1192',
                        '> >(factor -; cat >> "$GITHUB_OUTPUT")',
                        "'-' factor galat angka: gerbang lolos (Audit 2091)", True)

    skenario_substitusi(tmp, 'sub1193',
                        '> >(groff; cat >> "$GITHUB_OUTPUT")',
                        'groff tanpa operand membaca stdin: gerbang gagal (Audit 2089)', False)

    skenario_substitusi(tmp, 'sub1194',
                        '> >(in2csv; cat >> "$GITHUB_OUTPUT")',
                        'in2csv telanjang tanpa format galat: gerbang lolos (Audit 2087, koreksi 2431)', True)

    skenario_substitusi(tmp, 'sub1195',
                        '> >(units; cat >> "$GITHUB_OUTPUT")',
                        'units membaca pasangan dari stdin: gerbang gagal (Audit 2088)', False)

    skenario_substitusi(tmp, 'sub1196',
                        '> >(mawk -; cat >> "$GITHUB_OUTPUT")',
                        "'-' mawk teks program: gerbang lolos (Audit 2090)", True)

    skenario_substitusi(tmp, 'sub1197',
                        '> >(docker -vD load; cat >> "$GITHUB_OUTPUT")',
                        'bundel -vD docker informasional: gerbang lolos (Audit 2092)', True)

    skenario_substitusi(tmp, 'sub1198',
                        '> >(pv -o out; cat >> "$GITHUB_OUTPUT")',
                        'opsi pv generasi baru -o bernilai: gerbang gagal (Audit 2093)', False)

    skenario_substitusi(tmp, 'sub1199',
                        '> >(parallel echo --semaphore; cat >> "$GITHUB_OUTPUT")',
                        '--semaphore sesudah perintah argumen biasa: gerbang gagal (Audit 2094)', False)

    skenario_substitusi(tmp, 'sub1200',
                        '> >(xonsh -D VAR; cat >> "$GITHUB_OUTPUT")',
                        '-D xonsh terpisah bernilai: gerbang gagal (Audit 2095)', False)

    skenario_substitusi(tmp, 'sub1201',
                        '> >(in2csv --format -; cat >> "$GITHUB_OUTPUT")',
                        "format '-' in2csv ditolak: gerbang lolos (Audit 2096)", True)

    skenario_substitusi(tmp, 'sub1202',
                        '> >(qsv cat rows; cat >> "$GITHUB_OUTPUT")',
                        'qsv cat membaca stdin: gerbang gagal (Audit 2098)', False)

    skenario_substitusi(tmp, 'sub1203',
                        '> >(csvlook; cat >> "$GITHUB_OUTPUT")',
                        'csvlook menerima data terpipa: gerbang gagal (Audit 2099)', False)

    skenario_substitusi(tmp, 'sub1204',
                        '> >(tsv-select -f 1; cat >> "$GITHUB_OUTPUT")',
                        'tsv-select membaca standard input: gerbang gagal (Audit 2100)', False)

    skenario_substitusi(tmp, 'sub1205',
                        '> >(podman -vD load; cat >> "$GITHUB_OUTPUT")',
                        'bundel -vD podman informasional: gerbang lolos (Audit 2101)', True)

    skenario_substitusi(tmp, 'sub1206',
                        '> >(pv -d; cat >> "$GITHUB_OUTPUT")',
                        '-d pv menggantung modus pantau: gerbang lolos (Audit 2102)', True)

    skenario_substitusi(tmp, 'sub1207',
                        '> >(pv -Mboth -- cat; cat >> "$GITHUB_OUTPUT")',
                        'monitor pv merekat terdeteksi: gerbang gagal (Audit 2103)', False)

    skenario_substitusi(tmp, 'sub1208',
                        '> >(parallel --max-args 2 --semaphore echo; cat >> "$GITHUB_OUTPUT")',
                        'nilai_pl parallel lengkap: gerbang lolos (Audit 2104)', True)

    skenario_substitusi(tmp, 'sub1209',
                        '> >(parallel --semaphorename nm echo; cat >> "$GITHUB_OUTPUT")',
                        '--semaphorename menyiratkan semaphore: gerbang lolos (Audit 2105)', True)

    skenario_substitusi(tmp, 'sub1210',
                        '> >(in2csv --delimiter --format; cat >> "$GITHUB_OUTPUT")',
                        'jebakan nilai argparse in2csv: gerbang lolos (Audit 2106)', True)

    skenario_substitusi(tmp, 'sub1211',
                        '> >(qsv cat; cat >> "$GITHUB_OUTPUT")',
                        'qsv cat tanpa sub-subperintah galat: gerbang lolos (Audit 2107)', True)

    skenario_substitusi(tmp, 'sub1212',
                        '> >(qsv cat rows; cat >> "$GITHUB_OUTPUT")',
                        'qsv cat rows membaca stdin: gerbang gagal (Audit 2108)', False)

    skenario_substitusi(tmp, 'sub1213',
                        '> >(csvlook --maxrows 5; cat >> "$GITHUB_OUTPUT")',
                        'ejaan --maxrows karangan ditolak: gerbang lolos (Audit 2109)', True)

    skenario_substitusi(tmp, 'sub1214',
                        '> >(csvlook --max-rows 5; cat >> "$GITHUB_OUTPUT")',
                        'opsi csvlook --max-rows bernilai: gerbang gagal (Audit 2110)', False)

    skenario_substitusi(tmp, 'sub1215',
                        '> >(tsv-select --exclude 3; cat >> "$GITHUB_OUTPUT")',
                        '--exclude tsv-select bernilai: gerbang gagal (Audit 2111)', False)

    skenario_substitusi(tmp, 'sub1216',
                        '> >(tsv-select -f 1 --rest last; cat >> "$GITHUB_OUTPUT")',
                        '--rest tsv-select bernilai: gerbang gagal (Audit 2112)', False)

    skenario_substitusi(tmp, 'sub1217',
                        '> >(tsv-select; cat >> "$GITHUB_OUTPUT")',
                        'tsv-select tanpa -f/-e galat: gerbang lolos (Audit 2113)', True)

    skenario_substitusi(tmp, 'sub1218',
                        '> >(csvcut; cat >> "$GITHUB_OUTPUT")',
                        'csvcut menerima data terpipa: gerbang gagal (Audit 2114)', False)

    skenario_substitusi(tmp, 'sub1219',
                        '> >(csvgrep -c 1 -r foo; cat >> "$GITHUB_OUTPUT")',
                        'csvgrep -c + pencocok menguras: gerbang gagal (Audit 2115)', False)

    skenario_substitusi(tmp, 'sub1220',
                        '> >(csvstat; cat >> "$GITHUB_OUTPUT")',
                        'csvstat menerima data terpipa: gerbang gagal (Audit 2116)', False)

    skenario_substitusi(tmp, 'sub1221',
                        '> >(xsv cat rows; cat >> "$GITHUB_OUTPUT")',
                        'xsv cat rows membaca stdin: gerbang gagal (Audit 2117)', False)

    skenario_substitusi(tmp, 'sub1222',
                        '> >(tsv-filter; cat >> "$GITHUB_OUTPUT")',
                        'tsv-filter membaca standard input: gerbang gagal (Audit 2118)', False)

    skenario_substitusi(tmp, 'sub1223',
                        '> >(tsv-append; cat >> "$GITHUB_OUTPUT")',
                        'tsv-append tanpa berkas stdin: gerbang gagal (Audit 2119)', False)

    skenario_substitusi(tmp, 'sub1224',
                        '> >(csvgrep -c1 -rfoo; cat >> "$GITHUB_OUTPUT")',
                        'pencocok csvgrep merekat terdeteksi: gerbang gagal (Audit 2120)', False)

    skenario_substitusi(tmp, 'sub1225',
                        '> >(xsv search pola; cat >> "$GITHUB_OUTPUT")',
                        'xsv search membaca stdin: gerbang gagal (Audit 2121)', False)

    skenario_substitusi(tmp, 'sub1226',
                        '> >(csvsort; cat >> "$GITHUB_OUTPUT")',
                        'csvsort menerima data terpipa: gerbang gagal (Audit 2122)', False)

    skenario_substitusi(tmp, 'sub1227',
                        '> >(tsv-summarize --count; cat >> "$GITHUB_OUTPUT")',
                        'tsv-summarize membaca standard input: gerbang gagal (Audit 2123)', False)

    skenario_substitusi(tmp, 'sub1228',
                        '> >(qsv search pola; cat >> "$GITHUB_OUTPUT")',
                        'qsv search membaca stdin: gerbang gagal (Audit 2124)', False)

    skenario_substitusi(tmp, 'sub1229',
                        '> >(csvjoin - b.csv; cat >> "$GITHUB_OUTPUT")',
                        "csvjoin operand '-' adalah stdin: gerbang gagal (Audit 2125)", False)

    skenario_substitusi(tmp, 'sub1230',
                        '> >(csvstack -; cat >> "$GITHUB_OUTPUT")',
                        "csvstack operand '-' adalah stdin: gerbang gagal (Audit 2126)", False)

    skenario_substitusi(tmp, 'sub1231',
                        '> >(tsv-join -f luar.tsv; cat >> "$GITHUB_OUTPUT")',
                        'tsv-join -f tanpa berkas data stdin: gerbang gagal (Audit 2127)', False)

    skenario_substitusi(tmp, 'sub1232',
                        '> >(qsv search --flag F pola; cat >> "$GITHUB_OUTPUT")',
                        'qsv search --flag bernilai: gerbang gagal (Audit 2131)', False)

    skenario_substitusi(tmp, 'sub1233',
                        '> >(xsv search -s 1 pola; cat >> "$GITHUB_OUTPUT")',
                        'xsv search -s kolom resmi: gerbang gagal (Audit 2132)', False)

    skenario_substitusi(tmp, 'sub1234',
                        '> >(csvjoin --inner - a.csv; cat >> "$GITHUB_OUTPUT")',
                        '--inner csvjoin ditolak: gerbang lolos (Audit 2133)', True)

    skenario_substitusi(tmp, 'sub1235',
                        '> >(tsv-join -f luar.tsv --append-fields 3; cat >> "$GITHUB_OUTPUT")',
                        'tsv-join --append-fields bernilai: gerbang gagal (Audit 2134)', False)

    skenario_substitusi(tmp, 'sub1236',
                        '> >(tsv-join -f - data.tsv; cat >> "$GITHUB_OUTPUT")',
                        'tsv-join filter dari stdin: gerbang gagal (Audit 2135)', False)

    skenario_substitusi(tmp, 'sub1237',
                        '> >(tsv-summarize --sum 1; cat >> "$GITHUB_OUTPUT")',
                        'operasi tsv-summarize bernilai: gerbang gagal (Audit 2136)', False)

    skenario_substitusi(tmp, 'sub1238',
                        '> >(csvformat; cat >> "$GITHUB_OUTPUT")',
                        'csvformat menerima data terpipa: gerbang gagal (Audit 2138)', False)

    skenario_substitusi(tmp, 'sub1239',
                        '> >(sql2csv; cat >> "$GITHUB_OUTPUT")',
                        'sql2csv kueri dari stdin: gerbang gagal (Audit 2139)', False)

    skenario_substitusi(tmp, 'sub1240',
                        '> >(tsv-uniq; cat >> "$GITHUB_OUTPUT")',
                        'tsv-uniq membaca standard input: gerbang gagal (Audit 2140)', False)

    skenario_substitusi(tmp, 'sub1241',
                        '> >(qsv apply operations trim kolom; cat >> "$GITHUB_OUTPUT")',
                        'qsv apply operations stdin: gerbang gagal (Audit 2141)', False)

    skenario_substitusi(tmp, 'sub1242',
                        '> >(qsv slice; cat >> "$GITHUB_OUTPUT")',
                        'qsv slice membaca stdin: gerbang gagal (Audit 2142)', False)

    skenario_substitusi(tmp, 'sub1243',
                        '> >(xsv slice; cat >> "$GITHUB_OUTPUT")',
                        'xsv slice membaca stdin: gerbang gagal (Audit 2143)', False)

    skenario_substitusi(tmp, 'sub1244',
                        '> >(tsv-join -f luar.tsv --delimiter X; cat >> "$GITHUB_OUTPUT")',
                        'tsv-join --delimiter bernilai: gerbang gagal (Audit 2144)', False)

    skenario_substitusi(tmp, 'sub1245',
                        '> >(tsv-summarize --quantile 1; cat >> "$GITHUB_OUTPUT")',
                        'operasi tsv-summarize diperluas: gerbang gagal (Audit 2145)', False)

    skenario_substitusi(tmp, 'sub1246',
                        '> >(tsv-summarize --variance 1; cat >> "$GITHUB_OUTPUT")',
                        '--variance hantu ditolak: gerbang lolos (Audit 2146)', True)

    skenario_substitusi(tmp, 'sub1247',
                        '> >(csvformat -D \'|\' ;; cat >> "$GITHUB_OUTPUT")',
                        'opsi keluaran csvformat bernilai: gerbang gagal (Audit 2147)', False)

    skenario_substitusi(tmp, 'sub1248',
                        '> >(qsv apply dynfmt --formatstr X --new-column Y; cat >> "$GITHUB_OUTPUT")',
                        'qsv apply dynfmt stdin: gerbang gagal (Audit 2148)', False)

    skenario_substitusi(tmp, 'sub1249',
                        '> >(qsv stats; cat >> "$GITHUB_OUTPUT")',
                        'qsv stats membaca stdin: gerbang gagal (Audit 2151)', False)

    skenario_substitusi(tmp, 'sub1250',
                        '> >(qsv headers; cat >> "$GITHUB_OUTPUT")',
                        'qsv headers membaca stdin: gerbang gagal (Audit 2152)', False)

    skenario_substitusi(tmp, 'sub1251',
                        '> >(csvsql; cat >> "$GITHUB_OUTPUT")',
                        'csvsql data CSV dari stdin: gerbang gagal (Audit 2153)', False)

    skenario_substitusi(tmp, 'sub1252',
                        '> >(xsv headers; cat >> "$GITHUB_OUTPUT")',
                        'xsv headers membaca stdin: gerbang gagal (Audit 2154)', False)

    skenario_substitusi(tmp, 'sub1253',
                        '> >(qsv stats -s 1; cat >> "$GITHUB_OUTPUT")',
                        'qsv stats opsi bernilai lengkap: gerbang gagal (Audit 2155)', False)

    skenario_substitusi(tmp, 'sub1254',
                        '> >(tsv-summarize --mad 1; cat >> "$GITHUB_OUTPUT")',
                        'tsv-summarize --mad bernilai: gerbang gagal (Audit 2156)', False)

    skenario_substitusi(tmp, 'sub1255',
                        '> >(csvsql --tables t; cat >> "$GITHUB_OUTPUT")',
                        'csvsql opsi bernilai lengkap: gerbang gagal (Audit 2157)', False)

    skenario_substitusi(tmp, 'sub1256',
                        '> >(xsv flatten; cat >> "$GITHUB_OUTPUT")',
                        'xsv flatten membaca stdin: gerbang gagal (Audit 2159)', False)

    skenario_substitusi(tmp, 'sub1257',
                        '> >(xsv fmt; cat >> "$GITHUB_OUTPUT")',
                        'xsv fmt membaca stdin: gerbang gagal (Audit 2160)', False)

    skenario_substitusi(tmp, 'sub1258',
                        '> >(xsv input; cat >> "$GITHUB_OUTPUT")',
                        'xsv input membaca stdin: gerbang gagal (Audit 2161)', False)

    skenario_substitusi(tmp, 'sub1259',
                        '> >(xsv table; cat >> "$GITHUB_OUTPUT")',
                        'xsv table membaca stdin: gerbang gagal (Audit 2162)', False)

    skenario_substitusi(tmp, 'sub1260',
                        '> >(xsv sort; cat >> "$GITHUB_OUTPUT")',
                        'xsv sort membaca stdin: gerbang gagal (Audit 2163)', False)

    skenario_substitusi(tmp, 'sub1261',
                        '> >(xsv sample 5; cat >> "$GITHUB_OUTPUT")',
                        'xsv sample membaca stdin: gerbang gagal (Audit 2164)', False)

    skenario_substitusi(tmp, 'sub1262',
                        '> >(xsv flatten --condense 5; cat >> "$GITHUB_OUTPUT")',
                        'xsv flatten bentuk panjang bernilai: gerbang gagal (Audit 2165)', False)

    skenario_substitusi(tmp, 'sub1263',
                        '> >(xsv table --width 40; cat >> "$GITHUB_OUTPUT")',
                        'xsv table bentuk panjang bernilai: gerbang gagal (Audit 2166)', False)

    skenario_substitusi(tmp, 'sub1264',
                        '> >(xsv sample -; cat >> "$GITHUB_OUTPUT")',
                        "xsv sample '-' galat parse: gerbang lolos (Audit 2167)", True)

    skenario_substitusi(tmp, 'sub1265',
                        '> >(xsv sort -r; cat >> "$GITHUB_OUTPUT")',
                        'xsv sort -r tak dikenal: gerbang lolos (Audit 2168)', True)

    skenario_substitusi(tmp, 'sub1266',
                        '> >(qsv stats --cache-threshold 100; cat >> "$GITHUB_OUTPUT")',
                        'qsv stats cache-threshold bernilai: gerbang gagal (Audit 2169)', False)

    skenario_substitusi(tmp, 'sub1267',
                        '> >(qsv headers -d \';\'; cat >> "$GITHUB_OUTPUT")',
                        'qsv headers delimiter bernilai: gerbang gagal (Audit 2170)', False)

    skenario_substitusi(tmp, 'sub1268',
                        '> >(xsv select 1; cat >> "$GITHUB_OUTPUT")',
                        'xsv select membaca stdin: gerbang gagal (Audit 2171)', False)

    skenario_substitusi(tmp, 'sub1269',
                        '> >(xsv split outdir; cat >> "$GITHUB_OUTPUT")',
                        'xsv split membaca stdin: gerbang gagal (Audit 2172)', False)

    skenario_substitusi(tmp, 'sub1270',
                        '> >(qsv validate; cat >> "$GITHUB_OUTPUT")',
                        'qsv validate membaca stdin: gerbang gagal (Audit 2173)', False)

    skenario_substitusi(tmp, 'sub1271',
                        '> >(qsv schema; cat >> "$GITHUB_OUTPUT")',
                        'qsv schema membaca stdin: gerbang gagal (Audit 2174)', False)

    skenario_substitusi(tmp, 'sub1272',
                        '> >(xsv sample abc; cat >> "$GITHUB_OUTPUT")',
                        'xsv sample ukuran bukan angka: gerbang lolos (Audit 2175)', True)

    skenario_substitusi(tmp, 'sub1273',
                        '> >(xsv split --filename potong.csv outdir; cat >> "$GITHUB_OUTPUT")',
                        'xsv split template tanpa kurung: gerbang lolos (Audit 2176)', True)

    skenario_substitusi(tmp, 'sub1274',
                        '> >(qsv sort; cat >> "$GITHUB_OUTPUT")',
                        'qsv sort membaca stdin: gerbang gagal (Audit 2178)', False)

    skenario_substitusi(tmp, 'sub1275',
                        '> >(qsv select 1; cat >> "$GITHUB_OUTPUT")',
                        'qsv select membaca stdin: gerbang gagal (Audit 2179)', False)

    skenario_substitusi(tmp, 'sub1276',
                        '> >(qsv sample 5; cat >> "$GITHUB_OUTPUT")',
                        'qsv sample membaca stdin: gerbang gagal (Audit 2180)', False)

    skenario_substitusi(tmp, 'sub1277',
                        '> >(qsv split outdir; cat >> "$GITHUB_OUTPUT")',
                        'qsv split membaca stdin: gerbang gagal (Audit 2181)', False)

    skenario_substitusi(tmp, 'sub1278',
                        '> >(qsv join 1 - 2 b.csv; cat >> "$GITHUB_OUTPUT")',
                        'qsv join stdin lewat strip: gerbang gagal (Audit 2182)', False)

    skenario_substitusi(tmp, 'sub1279',
                        '> >(qsv dedup; cat >> "$GITHUB_OUTPUT")',
                        'qsv dedup membaca stdin: gerbang gagal (Audit 2183)', False)

    skenario_substitusi(tmp, 'sub1280',
                        '> >(qsv sample abc; cat >> "$GITHUB_OUTPUT")',
                        'qsv sample ukuran bukan angka: gerbang lolos (Audit 2184)', True)

    skenario_substitusi(tmp, 'sub1281',
                        '> >(qsv split --filename potong.csv outdir; cat >> "$GITHUB_OUTPUT")',
                        'qsv split template tanpa kurung: gerbang lolos (Audit 2185)', True)

    skenario_substitusi(tmp, 'sub1282',
                        '> >(qsv sort - -; cat >> "$GITHUB_OUTPUT")',
                        'operand strip ganda masukan tunggal: gerbang lolos (Audit 2186)', True)

    skenario_substitusi(tmp, 'sub1283',
                        '> >(xsv sample 18446744073709551616; cat >> "$GITHUB_OUTPUT")',
                        'xsv sample ukuran meluap u64: gerbang lolos (Audit 2187)', True)

    skenario_substitusi(tmp, 'sub1284',
                        '> >(xsv partition 1 outdir; cat >> "$GITHUB_OUTPUT")',
                        'xsv partition membaca stdin: gerbang gagal (Audit 2189)', False)

    skenario_substitusi(tmp, 'sub1285',
                        '> >(xsv stats; cat >> "$GITHUB_OUTPUT")',
                        'xsv stats membaca stdin: gerbang gagal (Audit 2190)', False)

    skenario_substitusi(tmp, 'sub1286',
                        '> >(xsv stats --jobs 4; cat >> "$GITHUB_OUTPUT")',
                        'xsv stats jobs bernilai: gerbang gagal (Audit 2191)', False)

    skenario_substitusi(tmp, 'sub1287',
                        '> >(xsv partition -p 2 1 outdir; cat >> "$GITHUB_OUTPUT")',
                        'xsv partition prefix-length bernilai: gerbang gagal (Audit 2192)', False)

    skenario_substitusi(tmp, 'sub1288',
                        '> >(xsv partition --filename potong.csv 1 outdir; cat >> "$GITHUB_OUTPUT")',
                        'xsv partition template tanpa kurung: gerbang lolos (Audit 2193)', True)

    skenario_substitusi(tmp, 'sub1289',
                        '> >(qsv apply calcconv --formatstr {a} --new-column n; cat >> "$GITHUB_OUTPUT")',
                        'qsv apply calcconv membaca stdin: gerbang gagal (Audit 2194)', False)

    skenario_substitusi(tmp, 'sub1290',
                        '> >(qsv apply datefmt kolom; cat >> "$GITHUB_OUTPUT")',
                        'qsv apply datefmt hantu: gerbang lolos (Audit 2195)', True)

    skenario_substitusi(tmp, 'sub1291',
                        '> >(qsv apply operations upper nama -r baru -; cat >> "$GITHUB_OUTPUT")',
                        'qsv apply operations opsi bernilai: gerbang gagal (Audit 2196)', False)

    skenario_substitusi(tmp, 'sub1292',
                        '> >(qsv apply dynfmt -f {a} -c baru; cat >> "$GITHUB_OUTPUT")',
                        'qsv apply dynfmt pendek bernilai: gerbang gagal (Audit 2197)', False)

    skenario_substitusi(tmp, 'sub1293',
                        '> >(qsv apply dynfmt --formatstr {a} --new-column n - ekstra; cat >> "$GITHUB_OUTPUT")',
                        'qsv apply dynfmt operand berlebih: gerbang lolos (Audit 2198)', True)

    skenario_substitusi(tmp, 'sub1294',
                        '> >(qsv apply emptyreplace -R X kolom; cat >> "$GITHUB_OUTPUT")',
                        'qsv apply emptyreplace -R bernilai: gerbang gagal (Audit 2199)', False)

    skenario_substitusi(tmp, 'sub1295',
                        '> >(qsv sample 1_000; cat >> "$GITHUB_OUTPUT")',
                        'qsv sample underscore ditolak f64: gerbang lolos (Audit 2200)', True)

    skenario_substitusi(tmp, 'sub1296',
                        '> >(qsv transpose; cat >> "$GITHUB_OUTPUT")',
                        'qsv transpose membaca stdin: gerbang gagal (Audit 2203)', False)

    skenario_substitusi(tmp, 'sub1297',
                        '> >(qsv reverse; cat >> "$GITHUB_OUTPUT")',
                        'qsv reverse membaca stdin: gerbang gagal (Audit 2204)', False)

    skenario_substitusi(tmp, 'sub1298',
                        '> >(qsv rename new; cat >> "$GITHUB_OUTPUT")',
                        'qsv rename membaca stdin: gerbang gagal (Audit 2205)', False)

    skenario_substitusi(tmp, 'sub1299',
                        '> >(qsv replace pola ganti; cat >> "$GITHUB_OUTPUT")',
                        'qsv replace membaca stdin: gerbang gagal (Audit 2206)', False)

    skenario_substitusi(tmp, 'sub1300',
                        '> >(qsv to xlsx data.xlsx -; cat >> "$GITHUB_OUTPUT")',
                        'qsv to membaca stdin: gerbang gagal (Audit 2207)', False)

    skenario_substitusi(tmp, 'sub1301',
                        '> >(qsv sqlp - \'select 1\'; cat >> "$GITHUB_OUTPUT")',
                        'qsv sqlp stdin lewat strip: gerbang gagal (Audit 2208)', False)

    skenario_substitusi(tmp, 'sub1302',
                        '> >(qsv sqlp data.csv --format - \'select 1\'; cat >> "$GITHUB_OUTPUT")',
                        'qsv sqlp nilai opsi strip: gerbang lolos (Audit 2211)', True)

    skenario_substitusi(tmp, 'sub1303',
                        '> >(xsv fixlengths -l 5; cat >> "$GITHUB_OUTPUT")',
                        'xsv fixlengths length membaca stdin: gerbang gagal (Audit 2212)', False)

    skenario_substitusi(tmp, 'sub1304',
                        '> >(qsv behead; cat >> "$GITHUB_OUTPUT")',
                        'qsv behead membaca stdin: gerbang gagal (Audit 2213)', False)

    skenario_substitusi(tmp, 'sub1305',
                        '> >(qsv snappy compress; cat >> "$GITHUB_OUTPUT")',
                        'qsv snappy compress membaca stdin: gerbang gagal (Audit 2214)', False)

    skenario_substitusi(tmp, 'sub1306',
                        '> >(qsv fetch kolom; cat >> "$GITHUB_OUTPUT")',
                        'qsv fetch membaca stdin: gerbang gagal (Audit 2215)', False)

    skenario_substitusi(tmp, 'sub1307',
                        '> >(xsv fixlengths -l 5 -o hasil.csv; cat >> "$GITHUB_OUTPUT")',
                        'xsv fixlengths output bernilai: gerbang gagal (Audit 2216)', False)

    skenario_substitusi(tmp, 'sub1308',
                        '> >(qsv snappy compress -; cat >> "$GITHUB_OUTPUT")',
                        'qsv snappy strip bukan stdin: gerbang lolos (Audit 2217)', True)

    skenario_substitusi(tmp, 'sub1309',
                        '> >(xsv fixlengths -l 0; cat >> "$GITHUB_OUTPUT")',
                        'xsv fixlengths panjang nol ditolak: gerbang lolos (Audit 2218)', True)

    skenario_substitusi(tmp, 'sub1310',
                        '> >(qsv input; cat >> "$GITHUB_OUTPUT")',
                        'qsv input membaca stdin: gerbang gagal (Audit 2220)', False)

    skenario_substitusi(tmp, 'sub1311',
                        '> >(qsv luau map kolom skrip; cat >> "$GITHUB_OUTPUT")',
                        'qsv luau map membaca stdin: gerbang gagal (Audit 2221)', False)

    skenario_substitusi(tmp, 'sub1312',
                        '> >(qsv py map kolom skrip; cat >> "$GITHUB_OUTPUT")',
                        'qsv py map membaca stdin: gerbang gagal (Audit 2222)', False)

    skenario_substitusi(tmp, 'sub1313',
                        '> >(qsv foreach kolom perintah; cat >> "$GITHUB_OUTPUT")',
                        'qsv foreach membaca stdin: gerbang gagal (Audit 2223)', False)

    skenario_substitusi(tmp, 'sub1314',
                        '> >(xsv fixlengths -l 00; cat >> "$GITHUB_OUTPUT")',
                        'xsv fixlengths panjang 00 ditolak: gerbang lolos (Audit 2224)', True)

    skenario_substitusi(tmp, 'sub1315',
                        '> >(qsv extsort; cat >> "$GITHUB_OUTPUT")',
                        'qsv extsort membaca stdin: gerbang gagal (Audit 2227)', False)

    skenario_substitusi(tmp, 'sub1316',
                        '> >(qsv partition kolom outdir; cat >> "$GITHUB_OUTPUT")',
                        'qsv partition membaca stdin: gerbang gagal (Audit 2228)', False)

    skenario_substitusi(tmp, 'sub1317',
                        '> >(qsv sortcheck; cat >> "$GITHUB_OUTPUT")',
                        'qsv sortcheck membaca stdin: gerbang gagal (Audit 2229)', False)

    skenario_substitusi(tmp, 'sub1318',
                        '> >(qsv frequency; cat >> "$GITHUB_OUTPUT")',
                        'qsv frequency membaca stdin: gerbang gagal (Audit 2230)', False)

    skenario_substitusi(tmp, 'sub1319',
                        '> >(csvpy data.csv; cat >> "$GITHUB_OUTPUT")',
                        'csvpy berkas masuk REPL stdin: gerbang gagal (Audit 2231)', False)

    skenario_substitusi(tmp, 'sub1320',
                        '> >(qsv extsort -s; cat >> "$GITHUB_OUTPUT")',
                        'qsv extsort -s gantung: galat - gerbang lolos (Audit 2235)', True)

    skenario_substitusi(tmp, 'sub1321',
                        '> >(qsv extsort -s col; cat >> "$GITHUB_OUTPUT")',
                        'qsv extsort -s col tanpa masukan: penguras - gerbang gagal (Audit 2235)', False)

    skenario_substitusi(tmp, 'sub1322',
                        '> >(xsv frequency; cat >> "$GITHUB_OUTPUT")',
                        'xsv frequency polos: penguras - gerbang gagal (Audit 2236)', False)

    skenario_substitusi(tmp, 'sub1323',
                        '> >(xsv frequency data.csv; cat >> "$GITHUB_OUTPUT")',
                        'xsv frequency berkas: bukan penguras - gerbang lolos (Audit 2236)', True)

    skenario_substitusi(tmp, 'sub1324',
                        '> >(qsv geocode suggest kolom; cat >> "$GITHUB_OUTPUT")',
                        'qsv geocode suggest kolom tanpa masukan: penguras - gerbang gagal (Audit 2237)', False)

    skenario_substitusi(tmp, 'sub1325',
                        '> >(qsv geocode suggestnow paris; cat >> "$GITHUB_OUTPUT")',
                        'qsv geocode suggestnow lokasi harfiah: bukan penguras - gerbang lolos (Audit 2237)', True)

    skenario_substitusi(tmp, 'sub1326',
                        '> >(qsv describegpt; cat >> "$GITHUB_OUTPUT")',
                        'qsv describegpt polos: penguras - gerbang gagal (Audit 2238)', False)

    skenario_substitusi(tmp, 'sub1327',
                        '> >(csvjson; cat >> "$GITHUB_OUTPUT")',
                        'csvjson polos: penguras - gerbang gagal (Audit 2239)', False)

    skenario_substitusi(tmp, 'sub1328',
                        '> >(qsv cat rows --group fname; cat >> "$GITHUB_OUTPUT")',
                        'qsv cat rows --group fname tanpa masukan: penguras - gerbang gagal (Audit 2240)', False)

    skenario_substitusi(tmp, 'sub1329',
                        '> >(qsv cat rows --group-by fname -; cat >> "$GITHUB_OUTPUT")',
                        'qsv cat rows --group-by hantu: galat docopt - gerbang lolos (Audit 2240)', True)

    skenario_substitusi(tmp, 'sub1330',
                        '> >(qsv searchset pola.txt; cat >> "$GITHUB_OUTPUT")',
                        'qsv searchset tanpa masukan: penguras - gerbang gagal (Audit 2241)', False)

    skenario_substitusi(tmp, 'sub1331',
                        '> >(csvclean; cat >> "$GITHUB_OUTPUT")',
                        'csvclean polos: berhenti tanpa pengaktif - gerbang lolos (Audit 2242, dibalik Audit 2390)', True)

    skenario_substitusi(tmp, 'sub1332',
                        '> >(qsv describegpt --fewshot-examples data.csv; cat >> "$GITHUB_OUTPUT")',
                        'qsv describegpt --fewshot-examples boolean: bukan penguras - gerbang lolos (Audit 2243)', True)

    skenario_substitusi(tmp, 'sub1333',
                        '> >(qsv describegpt --limit 5; cat >> "$GITHUB_OUTPUT")',
                        'qsv describegpt --limit hantu: galat docopt - gerbang lolos (Audit 2243)', True)

    skenario_substitusi(tmp, 'sub1334',
                        '> >(csvjson -; cat >> "$GITHUB_OUTPUT")',
                        "csvjson '-': penguras - gerbang gagal (Audit 2244)", False)

    skenario_substitusi(tmp, 'sub1335',
                        '> >(csvlook -; cat >> "$GITHUB_OUTPUT")',
                        "csvlook '-': penguras - gerbang gagal (Audit 2244)", False)

    skenario_substitusi(tmp, 'sub1336',
                        '> >(csvgrep -c 1 -r x -; cat >> "$GITHUB_OUTPUT")',
                        "csvgrep '-': penguras - gerbang gagal (Audit 2244)", False)

    skenario_substitusi(tmp, 'sub1337',
                        '> >(qsv blake3; cat >> "$GITHUB_OUTPUT")',
                        'qsv blake3 polos: penguras - gerbang gagal (Audit 2245)', False)

    skenario_substitusi(tmp, 'sub1338',
                        '> >(qsv blake3 data.csv; cat >> "$GITHUB_OUTPUT")',
                        'qsv blake3 berkas: bukan penguras - gerbang lolos (Audit 2245)', True)

    skenario_substitusi(tmp, 'sub1339',
                        '> >(qsv color; cat >> "$GITHUB_OUTPUT")',
                        'qsv color polos: penguras - gerbang gagal (Audit 2246)', False)

    skenario_substitusi(tmp, 'sub1340',
                        '> >(qsv datefmt kolom; cat >> "$GITHUB_OUTPUT")',
                        'qsv datefmt kolom tanpa masukan: penguras - gerbang gagal (Audit 2247)', False)

    skenario_substitusi(tmp, 'sub1341',
                        '> >(qsv denull; cat >> "$GITHUB_OUTPUT")',
                        'qsv denull polos: penguras - gerbang gagal (Audit 2248)', False)

    skenario_substitusi(tmp, 'sub1342',
                        '> >(qsv enum; cat >> "$GITHUB_OUTPUT")',
                        'qsv enum polos: penguras - gerbang gagal (Audit 2249)', False)

    skenario_substitusi(tmp, 'sub1343',
                        '> >(qsv explode kolom ,; cat >> "$GITHUB_OUTPUT")',
                        'qsv explode tanpa masukan: penguras - gerbang gagal (Audit 2250)', False)

    skenario_substitusi(tmp, 'sub1344',
                        '> >(qsv extdedup; cat >> "$GITHUB_OUTPUT")',
                        'qsv extdedup polos: penguras - gerbang gagal (Audit 2251)', False)

    skenario_substitusi(tmp, 'sub1345',
                        '> >(qsv fetchpost urlkol daftar; cat >> "$GITHUB_OUTPUT")',
                        'qsv fetchpost tanpa masukan: penguras - gerbang gagal (Audit 2252)', False)

    skenario_substitusi(tmp, 'sub1346',
                        '> >(qsv fill grup; cat >> "$GITHUB_OUTPUT")',
                        'qsv fill tanpa masukan: penguras - gerbang gagal (Audit 2253)', False)

    skenario_substitusi(tmp, 'sub1347',
                        '> >(qsv fixedwidth; cat >> "$GITHUB_OUTPUT")',
                        'qsv fixedwidth polos: penguras - gerbang gagal (Audit 2254)', False)

    skenario_substitusi(tmp, 'sub1348',
                        '> >(qsv flatten; cat >> "$GITHUB_OUTPUT")',
                        'qsv flatten polos: penguras - gerbang gagal (Audit 2255)', False)

    skenario_substitusi(tmp, 'sub1349',
                        '> >(qsv fmt; cat >> "$GITHUB_OUTPUT")',
                        'qsv fmt polos: penguras - gerbang gagal (Audit 2256)', False)

    skenario_substitusi(tmp, 'sub1350',
                        '> >(qsv implode -k a -v b ,; cat >> "$GITHUB_OUTPUT")',
                        'qsv implode tanpa masukan: penguras - gerbang gagal (Audit 2257)', False)

    skenario_substitusi(tmp, 'sub1351',
                        '> >(qsv json; cat >> "$GITHUB_OUTPUT")',
                        'qsv json polos: penguras - gerbang gagal (Audit 2258)', False)

    skenario_substitusi(tmp, 'sub1352',
                        '> >(qsv jsonl; cat >> "$GITHUB_OUTPUT")',
                        'qsv jsonl polos: penguras - gerbang gagal (Audit 2259)', False)

    skenario_substitusi(tmp, 'sub1353',
                        '> >(qsv lens; cat >> "$GITHUB_OUTPUT")',
                        'qsv lens polos: penguras - gerbang gagal (Audit 2260)', False)

    skenario_substitusi(tmp, 'sub1354',
                        '> >(qsv tojsonl; cat >> "$GITHUB_OUTPUT")',
                        'qsv tojsonl polos: penguras - gerbang gagal (Audit 2261)', False)

    skenario_substitusi(tmp, 'sub1355',
                        '> >(qsv moarstats; cat >> "$GITHUB_OUTPUT")',
                        'qsv moarstats polos: penguras - gerbang gagal (Audit 2262)', False)

    skenario_substitusi(tmp, 'sub1356',
                        '> >(qsv pragmastat; cat >> "$GITHUB_OUTPUT")',
                        'qsv pragmastat polos: penguras - gerbang gagal (Audit 2263)', False)

    skenario_substitusi(tmp, 'sub1357',
                        '> >(qsv profile; cat >> "$GITHUB_OUTPUT")',
                        'qsv profile polos: penguras - gerbang gagal (Audit 2264)', False)

    skenario_substitusi(tmp, 'sub1358',
                        '> >(qsv pseudo kolom; cat >> "$GITHUB_OUTPUT")',
                        'qsv pseudo kolom tanpa masukan: penguras - gerbang gagal (Audit 2265)', False)

    skenario_substitusi(tmp, 'sub1359',
                        '> >(qsv safenames; cat >> "$GITHUB_OUTPUT")',
                        'qsv safenames polos: penguras - gerbang gagal (Audit 2266)', False)

    skenario_substitusi(tmp, 'sub1360',
                        '> >(qsv sniff; cat >> "$GITHUB_OUTPUT")',
                        'qsv sniff polos: penguras - gerbang gagal (Audit 2267)', False)

    skenario_substitusi(tmp, 'sub1361',
                        '> >(qsv template; cat >> "$GITHUB_OUTPUT")',
                        'qsv template polos: penguras - gerbang gagal (Audit 2268)', False)

    skenario_substitusi(tmp, 'sub1362',
                        '> >(qsv viz bar -; cat >> "$GITHUB_OUTPUT")',
                        "qsv viz bar '-': penguras - gerbang gagal (Audit 2269)", False)

    skenario_substitusi(tmp, 'sub1363',
                        '> >(qsv viz smart -; cat >> "$GITHUB_OUTPUT")',
                        "qsv viz smart '-': smart tak membaca stdin - gerbang lolos (Audit 2269)", True)

    skenario_substitusi(tmp, 'sub1364',
                        '> >(qsv writestat; cat >> "$GITHUB_OUTPUT")',
                        'qsv writestat polos: penguras - gerbang gagal (Audit 2270)', False)

    skenario_substitusi(tmp, 'sub1365',
                        '> >(qsv clipboard --save; cat >> "$GITHUB_OUTPUT")',
                        'qsv clipboard --save: penguras - gerbang gagal (Audit 2271)', False)

    skenario_substitusi(tmp, 'sub1366',
                        '> >(qsv clipboard; cat >> "$GITHUB_OUTPUT")',
                        'qsv clipboard polos: bukan penguras - gerbang lolos (Audit 2271)', True)

    skenario_substitusi(tmp, 'sub1367',
                        '> >(qsv edit - kolom 1 nilai; cat >> "$GITHUB_OUTPUT")',
                        "qsv edit '-' tanpa --in-place: penguras - gerbang gagal (Audit 2272)", False)

    skenario_substitusi(tmp, 'sub1368',
                        '> >(qsv excel -; cat >> "$GITHUB_OUTPUT")',
                        "qsv excel '-': penguras di master - gerbang gagal (Audit 2273)", False)

    skenario_substitusi(tmp, 'sub1369',
                        '> >(qsv excel; cat >> "$GITHUB_OUTPUT")',
                        'qsv excel polos: argumen wajib - gerbang lolos (Audit 2273)', True)

    skenario_substitusi(tmp, 'sub1370',
                        '> >(qsv exclude c1 - c2 data.csv; cat >> "$GITHUB_OUTPUT")',
                        "qsv exclude input1 '-': penguras - gerbang gagal (Audit 2274)", False)

    skenario_substitusi(tmp, 'sub1371',
                        '> >(qsv geoconvert - geojson json; cat >> "$GITHUB_OUTPUT")',
                        "qsv geoconvert '-': penguras - gerbang gagal (Audit 2275)", False)

    skenario_substitusi(tmp, 'sub1372',
                        '> >(qsv scoresql - SELECT; cat >> "$GITHUB_OUTPUT")',
                        "qsv scoresql input '-': penguras - gerbang gagal (Audit 2276)", False)

    skenario_substitusi(tmp, 'sub1373',
                        '> >(xsv reverse; cat >> "$GITHUB_OUTPUT")',
                        'xsv reverse polos: penguras - gerbang gagal (Audit 2277)', False)

    skenario_substitusi(tmp, 'sub1374',
                        '> >(xsv join c1 - c2 data.csv; cat >> "$GITHUB_OUTPUT")',
                        "xsv join input1 '-': penguras - gerbang gagal (Audit 2278)", False)

    skenario_substitusi(tmp, 'sub1375',
                        '> >(qsv viz bar --color col -; cat >> "$GITHUB_OUTPUT")',
                        "qsv viz bar --color bernilai lalu '-': penguras - gerbang gagal (Audit 2279)", False)

    skenario_substitusi(tmp, 'sub1376',
                        '> >(qsv viz bar --jobs 4 -; cat >> "$GITHUB_OUTPUT")',
                        'qsv viz bar --jobs hantu: galat docopt - gerbang lolos (Audit 2280)', True)

    skenario_substitusi(tmp, 'sub1377',
                        '> >(qsv template -J g.json; cat >> "$GITHUB_OUTPUT")',
                        'qsv template -J bernilai: penguras - gerbang gagal (Audit 2281)', False)

    skenario_substitusi(tmp, 'sub1378',
                        '> >(qsv fetchpost -t tpl.txt urlkol; cat >> "$GITHUB_OUTPUT")',
                        'qsv fetchpost -t payload-tpl: penguras - gerbang gagal (Audit 2282)', False)

    skenario_substitusi(tmp, 'sub1379',
                        '> >(qsv fetchpost urlkol daftar -j g.json; cat >> "$GITHUB_OUTPUT")',
                        'qsv fetchpost -j globals-json: penguras - gerbang gagal (Audit 2283)', False)

    skenario_substitusi(tmp, 'sub1380',
                        '> >(qsv writestat --enum-threshold 5; cat >> "$GITHUB_OUTPUT")',
                        'qsv writestat --enum-threshold hantu: galat - gerbang lolos (Audit 2284)', True)

    skenario_substitusi(tmp, 'sub1381',
                        '> >(qsv clipboard -ss; cat >> "$GITHUB_OUTPUT")',
                        'qsv clipboard -ss bundel save: duplikat docopt - gerbang lolos (Audit 2379)', True)

    skenario_substitusi(tmp, 'sub1382',
                        '> >(qsv prompt --output hasil.csv; cat >> "$GITHUB_OUTPUT")',
                        'qsv prompt --output: penguras - gerbang gagal (Audit 2286)', False)

    skenario_substitusi(tmp, 'sub1383',
                        '> >(qsv prompt; cat >> "$GITHUB_OUTPUT")',
                        'qsv prompt polos dialog GUI: bukan penguras - gerbang lolos (Audit 2286)', True)

    skenario_substitusi(tmp, 'sub1384',
                        '> >(qsv count -d \';\'; cat >> "$GITHUB_OUTPUT")',
                        'qsv count -d bernilai: penguras - gerbang gagal (Audit 2287)', False)

    skenario_substitusi(tmp, 'sub1385',
                        '> >(qsv search -j 2 pola; cat >> "$GITHUB_OUTPUT")',
                        'qsv search -j bernilai: penguras - gerbang gagal (Audit 2288)', False)

    skenario_substitusi(tmp, 'sub1386',
                        '> >(qsv slice -o out.csv; cat >> "$GITHUB_OUTPUT")',
                        'qsv slice -o bernilai: penguras - gerbang gagal (Audit 2289)', False)

    skenario_substitusi(tmp, 'sub1387',
                        '> >(qsv fetch --jaq .x urlkol; cat >> "$GITHUB_OUTPUT")',
                        'qsv fetch --jaq bernilai: penguras - gerbang gagal (Audit 2290)', False)

    skenario_substitusi(tmp, 'sub1388',
                        '> >(qsv luau filter --luau-path x expr; cat >> "$GITHUB_OUTPUT")',
                        'qsv luau --luau-path hantu: galat - gerbang lolos (Audit 2291)', True)

    skenario_substitusi(tmp, 'sub1389',
                        '> >(qsv partition --limit 5 kolom outdir; cat >> "$GITHUB_OUTPUT")',
                        'qsv partition --limit bernilai: penguras - gerbang gagal (Audit 2292)', False)

    skenario_substitusi(tmp, 'sub1390',
                        '> >(qsv frequency --weight bobot; cat >> "$GITHUB_OUTPUT")',
                        'qsv frequency --weight bernilai: penguras - gerbang gagal (Audit 2293)', False)

    skenario_substitusi(tmp, 'sub1391',
                        '> >(qsv validate --size-limit 100; cat >> "$GITHUB_OUTPUT")',
                        'qsv validate --size-limit bernilai: penguras - gerbang gagal (Audit 2294)', False)

    skenario_substitusi(tmp, 'sub1392',
                        '> >(qsv schema -o out.json; cat >> "$GITHUB_OUTPUT")',
                        'qsv schema -o bernilai: penguras - gerbang gagal (Audit 2295)', False)

    skenario_substitusi(tmp, 'sub1393',
                        '> >(qsv cat rows -d \';\'; cat >> "$GITHUB_OUTPUT")',
                        'qsv cat rows -d bernilai: penguras - gerbang gagal (Audit 2296)', False)

    skenario_substitusi(tmp, 'sub1394',
                        '> >(qsv viz bar --enum-threshold 5 -; cat >> "$GITHUB_OUTPUT")',
                        'qsv viz --enum-threshold hantu: galat - gerbang lolos (Audit 2297)', True)

    skenario_substitusi(tmp, 'sub1395',
                        '> >(qsv prompt -of; cat >> "$GITHUB_OUTPUT")',
                        'qsv prompt -of bundel fd-output+output: penguras - gerbang gagal (Audit 2298)', False)

    skenario_substitusi(tmp, 'sub1396',
                        '> >(qsv prompt -m --output; cat >> "$GITHUB_OUTPUT")',
                        'qsv prompt -m menelan --output sebagai nilai: bukan penguras - gerbang lolos (Audit 2299)', True)

    skenario_substitusi(tmp, 'sub1397',
                        '> >(qsv fetchpost urlkol -c -t daftar; cat >> "$GITHUB_OUTPUT")',
                        'qsv fetchpost -t tertelan -c: dua operand penguras - gerbang gagal (Audit 2300)', False)

    skenario_substitusi(tmp, 'sub1398',
                        '> >(qsv clipboard -xs; cat >> "$GITHUB_OUTPUT")',
                        'qsv clipboard -xs bundel tak sah: galat - gerbang lolos (Audit 2301)', True)

    skenario_substitusi(tmp, 'sub1399',
                        '> >(qsv clipboard -- -s; cat >> "$GITHUB_OUTPUT")',
                        'qsv clipboard -- -s sesudah pemisah: galat - gerbang lolos (Audit 2302)', True)

    skenario_substitusi(tmp, 'sub1400',
                        '> >(qsv template -d \';\' -; cat >> "$GITHUB_OUTPUT")',
                        'qsv template -d hantu: galat - gerbang lolos (Audit 2303)', True)

    skenario_substitusi(tmp, 'sub1401',
                        '> >(qsv fetch --jql x urlkol; cat >> "$GITHUB_OUTPUT")',
                        'qsv fetch --jql hantu: galat - gerbang lolos (Audit 2304)', True)

    skenario_substitusi(tmp, 'sub1402',
                        '> >(qsv stats --weight bobot; cat >> "$GITHUB_OUTPUT")',
                        'qsv stats --weight bernilai: penguras - gerbang gagal (Audit 2305)', False)

    skenario_substitusi(tmp, 'sub1403',
                        '> >(qsv select --seed 42 nama; cat >> "$GITHUB_OUTPUT")',
                        'qsv select --seed bernilai: penguras - gerbang gagal (Audit 2306)', False)

    skenario_substitusi(tmp, 'sub1404',
                        '> >(qsv sample --max-size 10 5; cat >> "$GITHUB_OUTPUT")',
                        'qsv sample --max-size bernilai: penguras - gerbang gagal (Audit 2307)', False)

    skenario_substitusi(tmp, 'sub1405',
                        '> >(qsv split --filter gzip outdir; cat >> "$GITHUB_OUTPUT")',
                        'qsv split --filter bernilai: penguras - gerbang gagal (Audit 2308)', False)

    skenario_substitusi(tmp, 'sub1406',
                        '> >(qsv join kol1 - kol2 b.csv --keys-output f; cat >> "$GITHUB_OUTPUT")',
                        'qsv join --keys-output bernilai: penguras - gerbang gagal (Audit 2309)', False)

    skenario_substitusi(tmp, 'sub1407',
                        '> >(qsv transpose --long 1; cat >> "$GITHUB_OUTPUT")',
                        'qsv transpose --long bernilai: penguras - gerbang gagal (Audit 2310)', False)

    skenario_substitusi(tmp, 'sub1408',
                        '> >(qsv replace -j 2 pola ganti; cat >> "$GITHUB_OUTPUT")',
                        'qsv replace -j bernilai: penguras - gerbang gagal (Audit 2311)', False)

    skenario_substitusi(tmp, 'sub1409',
                        '> >(qsv to xlsx out.xlsx --table Sales -; cat >> "$GITHUB_OUTPUT")',
                        "qsv to xlsx --table bernilai lalu '-': penguras - gerbang gagal (Audit 2312)", False)

    skenario_substitusi(tmp, 'sub1410',
                        '> >(qsv to ods out.ods -; cat >> "$GITHUB_OUTPUT")',
                        "qsv to ods '-': penguras - gerbang gagal (Audit 2313)", False)

    skenario_substitusi(tmp, 'sub1411',
                        '> >(qsv to sqlite test.db; cat >> "$GITHUB_OUTPUT")',
                        'qsv to sqlite tanpa masukan: galat - gerbang lolos (Audit 2314)', True)

    skenario_substitusi(tmp, 'sub1412',
                        '> >(qsv prompt -f --output hasil.csv; cat >> "$GITHUB_OUTPUT")',
                        'qsv prompt -f + --output bersamaan: galat usage - gerbang lolos (Audit 2315)', True)

    skenario_substitusi(tmp, 'sub1413',
                        '> >(qsv geocode suggest kolom -f %city -; cat >> "$GITHUB_OUTPUT")',
                        "qsv geocode -f formatstr bernilai lalu '-': penguras - gerbang gagal (Audit 2316)", False)

    skenario_substitusi(tmp, 'sub1414',
                        '> >(qsv apply summarize kolom -c ringkasan; cat >> "$GITHUB_OUTPUT")',
                        'qsv apply summarize tanpa masukan: penguras - gerbang gagal (Audit 2317)', False)

    skenario_substitusi(tmp, 'sub1415',
                        '> >(qsv apply operations trim kolom --prompt x -; cat >> "$GITHUB_OUTPUT")',
                        "qsv apply operations --prompt bernilai lalu '-': penguras - gerbang gagal (Audit 2318)", False)

    skenario_substitusi(tmp, 'sub1416',
                        '> >(qsv prompt -f -f; cat >> "$GITHUB_OUTPUT")',
                        'qsv prompt -f duplikat: docopt menolak - gerbang lolos (Audit 2319)', True)

    skenario_substitusi(tmp, 'sub1417',
                        '> >(qsv apply summarize -cj kolom; cat >> "$GITHUB_OUTPUT")',
                        'qsv apply summarize -c merekat tanpa masukan: penguras - gerbang gagal (Audit 2320)', False)

    skenario_substitusi(tmp, 'sub1418',
                        '> >(qsv apply dynfmt -ffmt -cnama; cat >> "$GITHUB_OUTPUT")',
                        'qsv apply dynfmt syarat merekat tanpa masukan: penguras - gerbang gagal (Audit 2321)', False)

    skenario_substitusi(tmp, 'sub1419',
                        '> >(qsv fixlengths --length 5; cat >> "$GITHUB_OUTPUT")',
                        'qsv fixlengths --length sah tanpa masukan: penguras - gerbang gagal (Audit 2322)', False)

    skenario_substitusi(tmp, 'sub1420',
                        '> >(qsv table; cat >> "$GITHUB_OUTPUT")',
                        'qsv table polos: penguras - gerbang gagal (Audit 2323)', False)

    skenario_substitusi(tmp, 'sub1421',
                        '> >(qsv fixlengths -l 5 -i 3; cat >> "$GITHUB_OUTPUT")',
                        'qsv fixlengths -i insert bernilai tanpa masukan: penguras - gerbang gagal (Audit 2324)', False)

    skenario_substitusi(tmp, 'sub1422',
                        '> >(qsv table -p 4; cat >> "$GITHUB_OUTPUT")',
                        'qsv table -p pad bernilai tanpa masukan: penguras - gerbang gagal (Audit 2325)', False)

    skenario_substitusi(tmp, 'sub1423',
                        '> >(qsv apply dynfmt -f a -f b -c x; cat >> "$GITHUB_OUTPUT")',
                        'qsv apply dynfmt -f duplikat: docopt menolak - gerbang lolos (Audit 2326)', True)

    skenario_substitusi(tmp, 'sub1424',
                        '> >(xsv count -d \';\' -; cat >> "$GITHUB_OUTPUT")',
                        "xsv count -d delimiter lalu '-': penguras - gerbang gagal (Audit 2327)", False)

    skenario_substitusi(tmp, 'sub1425',
                        '> >(xsv search pola -d \';\' -; cat >> "$GITHUB_OUTPUT")',
                        "xsv search -d delimiter lalu '-': penguras - gerbang gagal (Audit 2328)", False)

    skenario_substitusi(tmp, 'sub1426',
                        '> >(xsv slice -i 3 -; cat >> "$GITHUB_OUTPUT")',
                        "xsv slice -i index lalu '-': penguras - gerbang gagal (Audit 2329)", False)

    skenario_substitusi(tmp, 'sub1427',
                        '> >(xsv fmt --out-delimiter \';\' -; cat >> "$GITHUB_OUTPUT")',
                        "xsv fmt --out-delimiter lalu '-': penguras - gerbang gagal (Audit 2330)", False)

    skenario_substitusi(tmp, 'sub1428',
                        '> >(xsv sample 5 --seed 42 -; cat >> "$GITHUB_OUTPUT")',
                        "xsv sample --seed lalu '-': penguras - gerbang gagal (Audit 2331)", False)

    skenario_substitusi(tmp, 'sub1429',
                        '> >(xsv headers -d \';\'; cat >> "$GITHUB_OUTPUT")',
                        'xsv headers -d delimiter tanpa masukan: penguras - gerbang gagal (Audit 2332)', False)

    skenario_substitusi(tmp, 'sub1430',
                        '> >(xsv cat rows -d \';\' -; cat >> "$GITHUB_OUTPUT")',
                        "xsv cat rows -d delimiter lalu '-': penguras - gerbang gagal (Audit 2333)", False)

    skenario_substitusi(tmp, 'sub1431',
                        '> >(csvsql --db sqlite:// --insert --before-insert x -; cat >> "$GITHUB_OUTPUT")',
                        "csvsql --before-insert bernilai lalu '-': penguras - gerbang gagal (Audit 2334)", False)

    skenario_substitusi(tmp, 'sub1432',
                        '> >(csvsql --engine-option key value -; cat >> "$GITHUB_OUTPUT")',
                        "csvsql --engine-option nargs=2 lalu '-': penguras - gerbang gagal (Audit 2335)", False)

    skenario_substitusi(tmp, 'sub1433',
                        '> >(csvsql --null-value x y; cat >> "$GITHUB_OUTPUT")',
                        'csvsql --null-value nargs+ menelan nilai: penguras - gerbang gagal (Audit 2336)', False)

    skenario_substitusi(tmp, 'sub1434',
                        '> >(csvjoin --null-value x data1.csv data2.csv; cat >> "$GITHUB_OUTPUT")',
                        'csvjoin --null-value menelan kedua berkas: penguras - gerbang gagal (Audit 2337)', False)

    skenario_substitusi(tmp, 'sub1435',
                        '> >(csvstack --null-value x -; cat >> "$GITHUB_OUTPUT")',
                        'csvstack tak mengenal --null-value: argparse berhenti - gerbang lolos (Audit 2338)', True)

    skenario_substitusi(tmp, 'sub1436',
                        '> >(csvformat --null-value x -; cat >> "$GITHUB_OUTPUT")',
                        'csvformat tak mengenal --null-value: argparse berhenti - gerbang lolos (Audit 2339)', True)

    skenario_substitusi(tmp, 'sub1437',
                        '> >(csvlook --null-value x file149.csv; cat >> "$GITHUB_OUTPUT")',
                        'csvlook --null-value menelan nama berkas: penguras - gerbang gagal (Audit 2340)', False)

    skenario_substitusi(tmp, 'sub1438',
                        '> >(csvpy --null-value x file149.csv; cat >> "$GITHUB_OUTPUT")',
                        'csvpy --null-value menelan nama berkas lalu menolak stdin - gerbang lolos (Audit 2341)', True)

    skenario_substitusi(tmp, 'sub1439',
                        '> >(csvclean --null-value x -; cat >> "$GITHUB_OUTPUT")',
                        'csvclean tak mengenal --null-value: argparse berhenti - gerbang lolos (Audit 2342)', True)

    skenario_substitusi(tmp, 'sub1440',
                        '> >(tsv-uniq -f 1; cat >> "$GITHUB_OUTPUT")',
                        'tsv-uniq -f fields bernilai tanpa berkas: penguras - gerbang gagal (Audit 2343)', False)

    skenario_substitusi(tmp, 'sub1441',
                        '> >(csvjoin; cat >> "$GITHUB_OUTPUT")',
                        'csvjoin polos membaca stdin (gabung dengan dirinya) - gerbang gagal (Audit 2344)', False)

    skenario_substitusi(tmp, 'sub1442',
                        '> >(qsv stats -Eo --output -; cat >> "$GITHUB_OUTPUT")',
                        'qsv stats bundel -Eo: -o ujung bundel menelan --output - penguras, gerbang gagal (Audit 2345)', False)

    skenario_substitusi(tmp, 'sub1443',
                        '> >(csvlook -- --null-value file.csv; cat >> "$GITHUB_OUTPUT")',
                        "csvlook sesudah '--': --null-value operand kedua - argparse berhenti, gerbang lolos (Audit 2346)", True)

    skenario_substitusi(tmp, 'sub1444',
                        '> >(csvsql --engine-option=key value -; cat >> "$GITHUB_OUTPUT")',
                        "csvsql --engine-option bentuk '=' tak memenuhi nargs=2 - gerbang lolos (Audit 2347)", True)

    skenario_substitusi(tmp, 'sub1445',
                        '> >(csvstack -Ly -; cat >> "$GITHUB_OUTPUT")',
                        'csvstack bundel -Ly memuat token terlarang - gerbang lolos (Audit 2348)', True)

    skenario_substitusi(tmp, 'sub1446',
                        '> >(xsv slice -l 1 -l 2; cat >> "$GITHUB_OUTPUT")',
                        'xsv slice -l duplikat: docopt menolak - gerbang lolos (Audit 2349)', True)

    skenario_substitusi(tmp, 'sub1447',
                        '> >(sql2csv --execution-option stream_results True; cat >> "$GITHUB_OUTPUT")',
                        'sql2csv --execution-option nargs=2 tanpa berkas: penguras - gerbang gagal (Audit 2350)', False)

    skenario_substitusi(tmp, 'sub1448',
                        '> >(sql2csv -d \';\' -; cat >> "$GITHUB_OUTPUT")',
                        'sql2csv tak mengenal -d: argparse berhenti - gerbang lolos (Audit 2351)', True)

    skenario_substitusi(tmp, 'sub1449',
                        '> >(sql2csv berkas.sql -; cat >> "$GITHUB_OUTPUT")',
                        "sql2csv dua positional: argparse menolak '-' - gerbang lolos (Audit 2352)", True)

    skenario_substitusi(tmp, 'sub1450',
                        '> >(in2csv -f csv -; cat >> "$GITHUB_OUTPUT")',
                        "in2csv -f csv '-': stdin dengan format - penguras, gerbang gagal (Audit 2353)", False)

    skenario_substitusi(tmp, 'sub1451',
                        '> >(in2csv -f csv --sheet Sheet1; cat >> "$GITHUB_OUTPUT")',
                        'in2csv --sheet bernilai tanpa berkas: penguras - gerbang gagal (Audit 2354)', False)

    skenario_substitusi(tmp, 'sub1452',
                        '> >(csvsql berkas.csv -; cat >> "$GITHUB_OUTPUT")',
                        "csvsql berkas + '-': input_paths nargs='*' membaca stdin - gerbang gagal (Audit 2355)", False)

    skenario_substitusi(tmp, 'sub1453',
                        '> >(tsv-append -s sumber; cat >> "$GITHUB_OUTPUT")',
                        'tsv-append -s source-header tanpa berkas: penguras - gerbang gagal (Audit 2356)', False)

    skenario_substitusi(tmp, 'sub1454',
                        '> >(tsv-filter --label Lulus; cat >> "$GITHUB_OUTPUT")',
                        'tsv-filter --label mode label tanpa berkas: penguras - gerbang gagal (Audit 2357)', False)

    skenario_substitusi(tmp, 'sub1455',
                        '> >(tsv-filter --in 1; cat >> "$GITHUB_OUTPUT")',
                        'tsv-filter --in hantu: getopt menolak - gerbang lolos (Audit 2358)', True)

    skenario_substitusi(tmp, 'sub1456',
                        '> >(xsv count -n --no-headers; cat >> "$GITHUB_OUTPUT")',
                        'xsv count -n + --no-headers: alias bool duplikat ditolak docopt - gerbang lolos (Audit 2359)', True)

    skenario_substitusi(tmp, 'sub1457',
                        '> >(sql2csv -b -; cat >> "$GITHUB_OUTPUT")',
                        'sql2csv tak mengenal -b: argparse berhenti - gerbang lolos (Audit 2360)', True)

    skenario_substitusi(tmp, 'sub1458',
                        '> >(sql2csv --engine-option --db x -; cat >> "$GITHUB_OUTPUT")',
                        'sql2csv --engine-option menelan token mirip-opsi: argparse berhenti - gerbang lolos (Audit 2361)', True)

    skenario_substitusi(tmp, 'sub1459',
                        '> >(in2csv -f xyz -; cat >> "$GITHUB_OUTPUT")',
                        'in2csv -f di luar choices: argparse menolak - gerbang lolos (Audit 2362)', True)

    skenario_substitusi(tmp, 'sub1460',
                        '> >(in2csv -n -f csv -; cat >> "$GITHUB_OUTPUT")',
                        'in2csv -n dengan format non-Excel: galat sebelum membaca - gerbang lolos (Audit 2363)', True)

    skenario_substitusi(tmp, 'sub1461',
                        '> >(csvgrep -c nama --file pola.csv; cat >> "$GITHUB_OUTPUT")',
                        'csvgrep --file pola resmi tanpa berkas data: penguras - gerbang gagal (Audit 2364)', False)

    skenario_substitusi(tmp, 'sub1462',
                        '> >(csvstat -i 2; cat >> "$GITHUB_OUTPUT")',
                        'csvstat -i indent tanpa berkas: penguras - gerbang gagal (Audit 2365)', False)

    skenario_substitusi(tmp, 'sub1463',
                        '> >(csvjson --lat 1 --lon 2 --type Point --geometry x; cat >> "$GITHUB_OUTPUT")',
                        'csvjson --type/--geometry tanpa berkas: penguras - gerbang gagal (Audit 2366)', False)

    skenario_substitusi(tmp, 'sub1464',
                        '> >(mlr --csv cat then sort -f nama; cat >> "$GITHUB_OUTPUT")',
                        'mlr rantai then tanpa berkas: penguras - gerbang gagal (Audit 2367)', False)

    skenario_substitusi(tmp, 'sub1465',
                        '> >(mlr --csv -n put \'$x=1\'; cat >> "$GITHUB_OUTPUT")',
                        'mlr -n sesudah opsi global: tak membaca apa pun - gerbang lolos (Audit 2368)', True)

    skenario_substitusi(tmp, 'sub1466',
                        '> >(qsv search -s a -s b pola; cat >> "$GITHUB_OUTPUT")',
                        'qsv search -s duplikat: docopt menolak - gerbang lolos (Audit 2369)', True)

    skenario_substitusi(tmp, 'sub1467',
                        '> >(tsv-summarize -g 1; cat >> "$GITHUB_OUTPUT")',
                        'tsv-summarize tanpa operator: ditolak sebelum membaca - gerbang lolos (Audit 2370)', True)

    skenario_substitusi(tmp, 'sub1468',
                        '> >(qsv apply operations -p --progressbar trim kolom -; cat >> "$GITHUB_OUTPUT")',
                        'qsv apply -p + --progressbar: alias bool duplikat - gerbang lolos (Audit 2371)', True)

    skenario_substitusi(tmp, 'sub1469',
                        '> >(in2csv -tn -f csv -; cat >> "$GITHUB_OUTPUT")',
                        'in2csv -n dalam bundel dengan format non-Excel: berhenti - gerbang lolos (Audit 2372)', True)

    skenario_substitusi(tmp, 'sub1470',
                        '> >(in2csv --names=x -f csv -; cat >> "$GITHUB_OUTPUT")',
                        'in2csv --names= berargumen: argparse menolak - gerbang lolos (Audit 2373)', True)

    skenario_substitusi(tmp, 'sub1471',
                        '> >(tsv-summarize --SUM 1; cat >> "$GITHUB_OUTPUT")',
                        'tsv-summarize operator huruf besar (caseInsensitive): penguras - gerbang gagal (Audit 2374)', False)

    skenario_substitusi(tmp, 'sub1472',
                        '> >(tsv-summarize --sum --count; cat >> "$GITHUB_OUTPUT")',
                        'tsv-summarize --sum memakan --count sebagai nilai operator: alat jalan - gerbang gagal (Audit 2375, koreksi model gantung oleh Audit 2574)', False)

    skenario_substitusi(tmp, 'sub1473',
                        '> >(in2csv -qn -f csv -; cat >> "$GITHUB_OUTPUT")',
                        'in2csv -q bernilai dalam bundel: -n tak hadir, penguras - gerbang gagal (Audit 2376)', False)

    skenario_substitusi(tmp, 'sub1474',
                        '> >(in2csv -nf xlsx -; cat >> "$GITHUB_OUTPUT")',
                        'in2csv -f di bundel dengan format Excel: penguras - gerbang gagal (Audit 2377)', False)

    skenario_substitusi(tmp, 'sub1475',
                        '> >(qsv dedup -s a -s b -; cat >> "$GITHUB_OUTPUT")',
                        'qsv dedup -s duplikat: docopt menolak - gerbang lolos (Audit 2378)', True)

    skenario_substitusi(tmp, 'sub1476',
                        '> >(qsv clipboard --save --save; cat >> "$GITHUB_OUTPUT")',
                        'qsv clipboard --save duplikat: docopt menolak - gerbang lolos (Audit 2379)', True)

    skenario_substitusi(tmp, 'sub1477',
                        '> >(qsv to sqlite out.db -d \';\' --delimiter \';\' -; cat >> "$GITHUB_OUTPUT")',
                        'qsv to -d = --delimiter: duplikat - gerbang lolos (Audit 2380)', True)

    skenario_substitusi(tmp, 'sub1478',
                        '> >(qsv fetch --http-header --output=x.csv --output y.csv -; cat >> "$GITHUB_OUTPUT")',
                        'qsv fetch nilai --http-header mirip opsi: bukan duplikat - gerbang gagal (Audit 2381)', False)

    skenario_substitusi(tmp, 'sub1479',
                        '> >(qsv fixedwidth -o a --output b -; cat >> "$GITHUB_OUTPUT")',
                        'qsv fixedwidth -o + --output duplikat - gerbang lolos (Audit 2382)', True)

    skenario_substitusi(tmp, 'sub1480',
                        '> >(tsv-summarize --count -V x; cat >> "$GITHUB_OUTPUT")',
                        'tsv-summarize -V versi: berhenti - gerbang lolos (Audit 2383)', True)

    skenario_substitusi(tmp, 'sub1481',
                        '> >(tsv-summarize --count -r -1; cat >> "$GITHUB_OUTPUT")',
                        'tsv-summarize -r nilai negatif sah: penguras - gerbang gagal (Audit 2384)', False)

    skenario_substitusi(tmp, 'sub1482',
                        '> >(tsv-summarize --count -Hg; cat >> "$GITHUB_OUTPUT")',
                        'tsv-summarize -Hg bundel bool berekor: galat - gerbang lolos (Audit 2385)', True)

    skenario_substitusi(tmp, 'sub1483',
                        '> >(tsv-summarize -h --count; cat >> "$GITHUB_OUTPUT")',
                        'tsv-summarize -h help: berhenti - gerbang lolos (Audit 2386)', True)

    skenario_substitusi(tmp, 'sub1484',
                        '> >(in2csv -Zf csv -; cat >> "$GITHUB_OUTPUT")',
                        'in2csv huruf tak dikenal di bundel: argparse menolak - gerbang lolos (Audit 2387)', True)

    skenario_substitusi(tmp, 'sub1485',
                        '> >(csvlook -ve utf-8; cat >> "$GITHUB_OUTPUT")',
                        'csvlook bundel bool+bernilai: nilai tertelan, penguras - gerbang gagal (Audit 2388)', False)

    skenario_substitusi(tmp, 'sub1486',
                        '> >(csvgrep -c nama -if pola.csv; cat >> "$GITHUB_OUTPUT")',
                        'csvgrep -f di bundel: pencocok hadir, penguras - gerbang gagal (Audit 2389)', False)

    skenario_substitusi(tmp, 'sub1488',
                        '> >(tsv-select --FIELDS 1; cat >> "$GITHUB_OUTPUT")',
                        'tsv-select --FIELDS huruf besar: syarat terpenuhi, penguras - gerbang gagal (Audit 2391)', False)

    skenario_substitusi(tmp, 'sub1489',
                        '> >(csvlook -ve --verbose; cat >> "$GITHUB_OUTPUT")',
                        'csvlook nilai bundel mirip-opsi: argparse menolak - gerbang lolos (Audit 2392)', True)

    skenario_substitusi(tmp, 'sub1490',
                        '> >(csvsql --engine-option a --db -; cat >> "$GITHUB_OUTPUT")',
                        'csvsql nilai ke-2 engine-option mirip-opsi: galat - gerbang lolos (Audit 2393)', True)

    skenario_substitusi(tmp, 'sub1491',
                        '> >(csvlook --null-v x; cat >> "$GITHUB_OUTPUT")',
                        'csvlook singkatan awalan --null-v: nilai tertelan, penguras - gerbang gagal (Audit 2394)', False)

    skenario_substitusi(tmp, 'sub1492',
                        '> >(csvlook -v-; cat >> "$GITHUB_OUTPUT")',
                        'csvlook bundel beraksara strip: galat - gerbang lolos (Audit 2395)', True)

    skenario_substitusi(tmp, 'sub1493',
                        '> >(csvpy -- -ve; cat >> "$GITHUB_OUTPUT")',
                        'csvpy operand sesudah -- harfiah: berkas dimuat, penguras - gerbang gagal (Audit 2396)', False)

    skenario_substitusi(tmp, 'sub1494',
                        '> >(tsv-summarize --count -G1; cat >> "$GITHUB_OUTPUT")',
                        'tsv-summarize -G merekat huruf besar sah: penguras - gerbang gagal (Audit 2397)', False)

    skenario_substitusi(tmp, 'sub1495',
                        '> >(tsv-summarize --count -r -1x; cat >> "$GITHUB_OUTPUT")',
                        'tsv-summarize -r -1x termakan sebagai nilai (alat jalan): gerbang menolak (Audit 2560, koreksi model 2398)', False)

    skenario_substitusi(tmp, 'sub1496',
                        '> >(csvclean --enable-all-checks=x; cat >> "$GITHUB_OUTPUT")',
                        'csvclean pengaktif ber-= untuk store_true: ditolak - gerbang lolos (Audit 2399)', True)

    skenario_substitusi(tmp, 'sub1497',
                        '> >(csvclean -a --label berkas.csv; cat >> "$GITHUB_OUTPUT")',
                        'csvclean --label menelan nama berkas: tanpa operand, penguras - gerbang gagal (Audit 2400)', False)

    skenario_substitusi(tmp, 'sub1498',
                        '> >(tsv-append -s -V; cat >> "$GITHUB_OUTPUT")',
                        'tsv-append -V jadi nilai -s: bukan version, penguras - gerbang gagal (Audit 2401)', False)

    skenario_substitusi(tmp, 'sub1499',
                        '> >(tsv-summarize --count -r -1e-5; cat >> "$GITHUB_OUTPUT")',
                        'tsv-summarize -r eksponen bertanda sah: penguras - gerbang gagal (Audit 2402)', False)

    skenario_substitusi(tmp, 'sub1500',
                        '> >(csvlook --n x; cat >> "$GITHUB_OUTPUT")',
                        'csvlook --n ambigu vs opsi bool: galat - gerbang lolos (Audit 2403)', True)

    skenario_substitusi(tmp, 'sub1501',
                        '> >(csvclean -a --remove-empty-columns; cat >> "$GITHUB_OUTPUT")',
                        'csvclean pengaktif hantu: tak dikenal - gerbang lolos (Audit 2404)', True)

    skenario_substitusi(tmp, 'sub1502',
                        '> >(csvclean --enable-all; cat >> "$GITHUB_OUTPUT")',
                        'csvclean singkatan pengaktif unik: penguras - gerbang gagal (Audit 2405)', False)

    skenario_substitusi(tmp, 'sub1503',
                        '> >(csvgrep --col 1 --reg x; cat >> "$GITHUB_OUTPUT")',
                        'csvgrep singkatan -c/-r unik: penguras - gerbang gagal (Audit 2405)', False)

    skenario_substitusi(tmp, 'sub1504',
                        '> >(tsv-select --HELP -f 1; cat >> "$GITHUB_OUTPUT")',
                        'tsv-select --HELP informasional: berhenti - gerbang lolos (Audit 2406)', True)

    skenario_substitusi(tmp, 'sub1505',
                        '> >(csvsql --engine-opt a b -; cat >> "$GITHUB_OUTPUT")',
                        'csvsql singkatan engine-option di saringan: penguras - gerbang gagal (Audit 2407)', False)

    skenario_substitusi(tmp, 'sub1506',
                        '> >(csvlook --enc --verbose; cat >> "$GITHUB_OUTPUT")',
                        'csvlook singkatan bernilai menggantung: galat - gerbang lolos (Audit 2408)', True)

    skenario_substitusi(tmp, 'sub1507',
                        '> >(csvclean -a --null-v x; cat >> "$GITHUB_OUTPUT")',
                        'csvclean singkatan nama terlarang: tak dikenal - gerbang lolos (Audit 2409)', True)

    skenario_substitusi(tmp, 'sub1508',
                        '> >(csvsql --d -; cat >> "$GITHUB_OUTPUT")',
                        'csvsql awalan ambigu: galat - gerbang lolos (Audit 2410)', True)

    skenario_substitusi(tmp, 'sub1509',
                        '> >(csvlook -12; cat >> "$GITHUB_OUTPUT")',
                        'csvlook angka negatif operand berkas: tak menguras - gerbang lolos (Audit 2411)', True)

    skenario_substitusi(tmp, 'sub1510',
                        '> >(tsv-append --SOURCE-HEADER -V; cat >> "$GITHUB_OUTPUT")',
                        'tsv-append opsi huruf besar + -V nilai: penguras - gerbang gagal (Audit 2412)', False)

    skenario_substitusi(tmp, 'sub1511',
                        '> >(tsv-filter --label -V --eq 1:2; cat >> "$GITHUB_OUTPUT")',
                        'tsv-filter inorder: -V batas opsi, berhenti - gerbang lolos (Audit 2413)', True)

    skenario_substitusi(tmp, 'sub1512',
                        '> >(tsv-select --help-verbose -f 1; cat >> "$GITHUB_OUTPUT")',
                        'tsv-select --help-verbose informasional: berhenti - gerbang lolos (Audit 2414)', True)

    skenario_substitusi(tmp, 'sub1513',
                        '> >(tsv-summarize --count -r -1i; cat >> "$GITHUB_OUTPUT")',
                        'tsv-summarize -r sufiks imajiner sah: penguras - gerbang gagal (Audit 2415)', False)

    skenario_substitusi(tmp, 'sub1514',
                        '> >(tsv-summarize --count -r -.5; cat >> "$GITHUB_OUTPUT")',
                        'tsv-summarize -r -.5 termakan sebagai nilai (alat jalan): gerbang menolak (Audit 2560, koreksi model 2415)', False)

    skenario_substitusi(tmp, 'sub1515',
                        '> >(csvlook --no; cat >> "$GITHUB_OUTPUT")',
                        'csvlook --no ambigu bool: galat - gerbang lolos (Audit 2416)', True)

    skenario_substitusi(tmp, 'sub1516',
                        '> >(csvclean -a --h; cat >> "$GITHUB_OUTPUT")',
                        'csvclean --h ambigu help/header: galat - gerbang lolos (Audit 2417)', True)

    skenario_substitusi(tmp, 'sub1517',
                        '> >(csvlook --null x berkas.csv; cat >> "$GITHUB_OUTPUT")',
                        'csvlook --null singkatan pendek sah: penguras - gerbang gagal (Audit 2418)', False)

    skenario_substitusi(tmp, 'sub1518',
                        '> >(csvsql --execution-option a b -; cat >> "$GITHUB_OUTPUT")',
                        'csvsql --execution-option tak ada: galat - gerbang lolos (Audit 2418)', True)

    skenario_substitusi(tmp, 'sub1519',
                        '> >(csvlook --sk 1; cat >> "$GITHUB_OUTPUT")',
                        'csvlook --sk ambigu skip-lines/skipinitialspace: galat - gerbang lolos (Audit 2419)', True)

    skenario_substitusi(tmp, 'sub1520',
                        '> >(qsv count -12; cat >> "$GITHUB_OUTPUT")',
                        'qsv count flag angka tak dikenal docopt: galat - gerbang lolos (Audit 2420)', True)

    skenario_substitusi(tmp, 'sub1521',
                        '> >(jq -12 data.json; cat >> "$GITHUB_OUTPUT")',
                        'jq filter angka + berkas: membaca berkas - gerbang lolos (Audit 2421)', True)

    skenario_substitusi(tmp, 'sub1522',
                        '> >(csvpy skrip.py --l en_US; cat >> "$GITHUB_OUTPUT")',
                        'csvpy --l unik ke --locale: konsol menguras - gerbang gagal (Audit 2422)', False)

    skenario_substitusi(tmp, 'sub1523',
                        '> >(csvpy skrip.py --linenumbers; cat >> "$GITHUB_OUTPUT")',
                        'csvpy --linenumbers tercabut: tak dikenal - gerbang lolos (Audit 2422)', True)

    skenario_substitusi(tmp, 'sub1524',
                        '> >(tsv-select -f 1 --help=x; cat >> "$GITHUB_OUTPUT")',
                        'tsv-select --help=x galat getopt: berhenti - gerbang lolos (Audit 2423)', True)

    skenario_substitusi(tmp, 'sub1525',
                        '> >(tsv-select -f 1 --version=false; cat >> "$GITHUB_OUTPUT")',
                        'tsv-select --version=false berjalan normal: penguras - gerbang gagal (Audit 2423)', False)

    skenario_substitusi(tmp, 'sub1526',
                        '> >(qsv stats -12; cat >> "$GITHUB_OUTPUT")',
                        'qsv stats flag angka tak dikenal docopt: galat - gerbang lolos (Audit 2424)', True)

    skenario_substitusi(tmp, 'sub1527',
                        '> >(xsv stats -12; cat >> "$GITHUB_OUTPUT")',
                        'xsv stats flag angka tak dikenal docopt: galat - gerbang lolos (Audit 2424)', True)

    skenario_substitusi(tmp, 'sub1528',
                        '> >(in2csv -f csv --no; cat >> "$GITHUB_OUTPUT")',
                        'in2csv --no ambigu bool: galat - gerbang lolos (Audit 2425)', True)

    skenario_substitusi(tmp, 'sub1529',
                        '> >(in2csv -f csv --h; cat >> "$GITHUB_OUTPUT")',
                        'in2csv --h cetak bantuan: berhenti - gerbang lolos (Audit 2425)', True)

    skenario_substitusi(tmp, 'sub1530',
                        '> >(csvlook --tabs=x; cat >> "$GITHUB_OUTPUT")',
                        'csvlook bool ber-= ditolak argparse: galat - gerbang lolos (Audit 2426)', True)

    skenario_substitusi(tmp, 'sub1531',
                        '> >(csvpy -- --zero; cat >> "$GITHUB_OUTPUT")',
                        'csvpy berkas bernama --zero sesudah --: konsol menguras - gerbang gagal (Audit 2427)', False)

    skenario_substitusi(tmp, 'sub1532',
                        '> >(tsv-select --version=False -f 1; cat >> "$GITHUB_OUTPUT")',
                        'tsv-select =False tak peka huruf Phobos: berjalan, penguras - gerbang gagal (Audit 2428)', False)

    skenario_substitusi(tmp, 'sub1533',
                        '> >(qsv extsort -12; cat >> "$GITHUB_OUTPUT")',
                        'qsv extsort flag angka docopt: galat - gerbang lolos (Audit 2429)', True)

    skenario_substitusi(tmp, 'sub1534',
                        '> >(xsv search berkas.csv -12; cat >> "$GITHUB_OUTPUT")',
                        'xsv search beroperand flag angka docopt: galat - gerbang lolos (Audit 2429)', True)

    skenario_substitusi(tmp, 'sub1535',
                        '> >(in2csv -f csv -- -; cat >> "$GITHUB_OUTPUT")',
                        'in2csv pemisah -- lalu -: membaca stdin - gerbang gagal (Audit 2430)', False)

    skenario_substitusi(tmp, 'sub1536',
                        '> >(csvsql --query \'select 1\' -- -; cat >> "$GITHUB_OUTPUT")',
                        'csvsql pemisah -- tak ditelan penjaga: membaca stdin - gerbang gagal (Audit 2430)', False)

    skenario_substitusi(tmp, 'sub1537',
                        '> >(in2csv -f xyz; cat >> "$GITHUB_OUTPUT")',
                        'in2csv format tak dikenal tanpa operand: galat - gerbang lolos (Audit 2431)', True)

    skenario_substitusi(tmp, 'sub1539',
                        '> >(csvlook -t=x; cat >> "$GITHUB_OUTPUT")',
                        'csvlook bool pendek ber-=: galat argparse - gerbang lolos (Audit 2432)', True)

    skenario_substitusi(tmp, 'sub1540',
                        '> >(csvstat --csv=x; cat >> "$GITHUB_OUTPUT")',
                        'csvstat bool khas ber-=: galat argparse - gerbang lolos (Audit 2433)', True)

    skenario_substitusi(tmp, 'sub1541',
                        '> >(csvjson --stream=x; cat >> "$GITHUB_OUTPUT")',
                        'csvjson bool khas ber-=: galat argparse - gerbang lolos (Audit 2433)', True)

    skenario_substitusi(tmp, 'sub1542',
                        '> >(qsv describegpt -12; cat >> "$GITHUB_OUTPUT")',
                        'qsv describegpt flag angka docopt: galat - gerbang lolos (Audit 2434)', True)

    skenario_substitusi(tmp, 'sub1543',
                        '> >(qsv geocode suggest kolom -12; cat >> "$GITHUB_OUTPUT")',
                        'qsv geocode beroperand flag angka docopt: galat - gerbang lolos (Audit 2434)', True)

    skenario_substitusi(tmp, 'sub1544',
                        '> >(yq -12 data.json; cat >> "$GITHUB_OUTPUT")',
                        'yq flag angka: galat urai - gerbang lolos (Audit 2435)', True)

    skenario_substitusi(tmp, 'sub1545',
                        '> >(fq -12 data.json; cat >> "$GITHUB_OUTPUT")',
                        'fq ekspresi angka pada berkas: stdin tak dibaca - gerbang lolos (Audit 2435)', True)

    skenario_substitusi(tmp, 'sub1546',
                        '> >(csvlook -Z; cat >> "$GITHUB_OUTPUT")',
                        'csvlook huruf pendek asing: galat argparse - gerbang lolos (Audit 2436)', True)

    skenario_substitusi(tmp, 'sub1547',
                        '> >(csvlook -lt=x; cat >> "$GITHUB_OUTPUT")',
                        'csvlook bundel pendek ber-=: galat argparse - gerbang lolos (Audit 2437)', True)

    skenario_substitusi(tmp, 'sub1548',
                        '> >(in2csv --na -f csv; cat >> "$GITHUB_OUTPUT")',
                        'in2csv singkatan --na pada format non-Excel: galat - gerbang lolos (Audit 2438)', True)

    skenario_substitusi(tmp, 'sub1549',
                        '> >(csvsql --insert -; cat >> "$GITHUB_OUTPUT")',
                        'csvsql --insert tanpa --db/--query: galat - gerbang lolos (Audit 2439)', True)

    skenario_substitusi(tmp, 'sub1550',
                        '> >(csvsql --no-create --query \'select 1\'; cat >> "$GITHUB_OUTPUT")',
                        'csvsql turunan insert tersirat dari query: membaca stdin - gerbang gagal (Audit 2439)', False)

    skenario_substitusi(tmp, 'sub1551',
                        '> >(csvlook -d=; cat >> "$GITHUB_OUTPUT")',
                        'csvlook nilai kosong merekat: membaca stdin - gerbang gagal (Audit 2440)', False)

    skenario_substitusi(tmp, 'sub1552',
                        '> >(yq -- -12; cat >> "$GITHUB_OUTPUT")',
                        'yq ekspresi angka sesudah --: membaca stdin - gerbang gagal (Audit 2441)', False)

    skenario_substitusi(tmp, 'sub1553',
                        '> >(csvcut -I -c 1; cat >> "$GITHUB_OUTPUT")',
                        'csvcut -I bukan benderanya: galat argparse - gerbang lolos (Audit 2442)', True)

    skenario_substitusi(tmp, 'sub1554',
                        '> >(csvsql --ins -; cat >> "$GITHUB_OUTPUT")',
                        'csvsql singkatan --ins tanpa db/query: galat - gerbang lolos (Audit 2443)', True)

    skenario_substitusi(tmp, 'sub1555',
                        '> >(csvsql --que \'select 1\' --no-create; cat >> "$GITHUB_OUTPUT")',
                        'csvsql singkatan --que memicu insert tersirat: membaca stdin - gerbang gagal (Audit 2443)', False)

    skenario_substitusi(tmp, 'sub1556',
                        '> >(csvlook -lh; cat >> "$GITHUB_OUTPUT")',
                        'csvlook bundel memuat -h: bantuan tercetak - gerbang lolos (Audit 2444)', True)

    skenario_substitusi(tmp, 'sub1557',
                        '> >(in2csv -lv -f csv; cat >> "$GITHUB_OUTPUT")',
                        'in2csv bundel bool sah -lv: membaca stdin - gerbang gagal (Audit 2445)', False)

    skenario_substitusi(tmp, 'sub1558',
                        '> >(csvlook -K=; cat >> "$GITHUB_OUTPUT")',
                        'csvlook nilai kosong merekat bukan -d: galat - gerbang lolos (Audit 2446)', True)

    skenario_substitusi(tmp, 'sub1559',
                        '> >(yq -- -12 data.yaml; cat >> "$GITHUB_OUTPUT")',
                        'yq angka sesudah -- bersama berkas: nama berkas - gerbang lolos (Audit 2447)', True)

    skenario_substitusi(tmp, 'sub1560',
                        '> >(csvsql --dial=sqlite --db sqlite://; cat >> "$GITHUB_OUTPUT")',
                        'csvsql singkatan dialek ber-= dengan db: galat pendamping - gerbang lolos (Audit 2448)', True)

    skenario_substitusi(tmp, 'sub1561',
                        '> >(xsv cat rows -h; cat >> "$GITHUB_OUTPUT")',
                        'xsv informasional pendek -h: usage tercetak - gerbang lolos (Audit 2449)', True)

    skenario_substitusi(tmp, 'sub1562',
                        '> >(qsv select 1 -V; cat >> "$GITHUB_OUTPUT")',
                        'qsv informasional pendek -V: galat docopt - gerbang lolos (Audit 2450)', True)

    skenario_substitusi(tmp, 'sub1563',
                        '> >(head -h; cat >> "$GITHUB_OUTPUT")',
                        'head -h tak sah: berhenti galat - gerbang lolos (Audit 2451)', True)

    skenario_substitusi(tmp, 'sub1564',
                        '> >(jq --help; cat >> "$GITHUB_OUTPUT")',
                        'jq informasional di keluarga skrip: bantuan tercetak - gerbang lolos (Audit 2452)', True)

    skenario_substitusi(tmp, 'sub1565',
                        '> >(grep --help pola; cat >> "$GITHUB_OUTPUT")',
                        'grep informasional panjang: bantuan tercetak - gerbang lolos (Audit 2452)', True)

    skenario_substitusi(tmp, 'sub1566',
                        '> >(qsv fetch --url-template -h; cat >> "$GITHUB_OUTPUT")',
                        'qsv -h nilai opsi khas docopt: template terbaca - gerbang menolak (Audit 2453)', False)

    skenario_substitusi(tmp, 'sub1567',
                        '> >(jq --arg x --help .; cat >> "$GITHUB_OUTPUT")',
                        'jq --arg dua token: --help adalah nilai - gerbang menolak (Audit 2454)', False)

    skenario_substitusi(tmp, 'sub1568',
                        '> >(grep --label --help pola; cat >> "$GITHUB_OUTPUT")',
                        'grep --label: --help adalah label - gerbang menolak (Audit 2454)', False)

    skenario_substitusi(tmp, 'sub1569',
                        '> >(qsv sort -N -h; cat >> "$GITHUB_OUTPUT")',
                        'qsv sort -N bool: -h bantuan terpintas - gerbang lolos (Audit 2455)', True)

    skenario_substitusi(tmp, 'sub1570',
                        '> >(qsv select --url-template -h 1; cat >> "$GITHUB_OUTPUT")',
                        'qsv opsi asing subperintah: galat docopt - gerbang lolos (Audit 2456)', True)

    skenario_substitusi(tmp, 'sub1571',
                        '> >(awk -v --help \'{print}\'; cat >> "$GITHUB_OUTPUT")',
                        'awk -v nilai informasional: galat penugasan - gerbang lolos (Audit 2457)', True)

    skenario_substitusi(tmp, 'sub1572',
                        '> >(rg --glob --help; cat >> "$GITHUB_OUTPUT")',
                        'rg glob menelan pola: kelaparan pola - gerbang lolos (Audit 2458)', True)

    skenario_substitusi(tmp, 'sub1573',
                        '> >(grep --regex --help; cat >> "$GITHUB_OUTPUT")',
                        'grep --regex singkatan getopt: pola informasional - gerbang menolak (Audit 2459)', False)

    skenario_substitusi(tmp, 'sub1574',
                        '> >(grep --exclude --help pola; cat >> "$GITHUB_OUTPUT")',
                        'grep --exclude: --help adalah nilai saring - gerbang menolak (Audit 2460)', False)

    skenario_substitusi(tmp, 'sub1575',
                        '> >(grep -e; cat >> "$GITHUB_OUTPUT")',
                        'grep -e menggantung: argumen hilang - gerbang lolos (Audit 2461)', True)

    skenario_substitusi(tmp, 'sub1576',
                        '> >(qsv count -d -h; cat >> "$GITHUB_OUTPUT")',
                        'qsv count -d: -h nilai delimiter - gerbang menolak (Audit 2462)', False)

    skenario_substitusi(tmp, 'sub1577',
                        '> >(qsv frequency --pct-nulls -h; cat >> "$GITHUB_OUTPUT")',
                        'qsv frequency --pct-nulls bool: bantuan terpintas - gerbang lolos (Audit 2463)', True)

    skenario_substitusi(tmp, 'sub1578',
                        '> >(qsv describegpt --limit -h; cat >> "$GITHUB_OUTPUT")',
                        'qsv describegpt --limit asing: galat docopt - gerbang lolos (Audit 2464)', True)

    skenario_substitusi(tmp, 'sub1579',
                        '> >(jq --arg x --help; cat >> "$GITHUB_OUTPUT")',
                        'jq tanpa filter: filter implisit titik tetap menguras - gerbang menolak (Audit 2465)', False)

    skenario_substitusi(tmp, 'sub1580',
                        '> >(fq --arg x --help .; cat >> "$GITHUB_OUTPUT")',
                        'fq --arg dua token: --help adalah nilai - gerbang menolak (Audit 2466)', False)

    skenario_substitusi(tmp, 'sub1581',
                        '> >(qsv select -f -h 1; cat >> "$GITHUB_OUTPUT")',
                        'qsv select -f asing: galat docopt - gerbang lolos (Audit 2467)', True)

    skenario_substitusi(tmp, 'sub1582',
                        '> >(fq --slurpfile x --help .; cat >> "$GITHUB_OUTPUT")',
                        'fq --slurpfile tak dikenal fq: alat berhenti - gerbang lolos (Audit 2468)', True)

    skenario_substitusi(tmp, 'sub1583',
                        '> >(fq --arg x --help; cat >> "$GITHUB_OUTPUT")',
                        'fq tanpa filter: filter implisit titik tetap menguras - gerbang menolak (Audit 2469)', False)

    skenario_substitusi(tmp, 'sub1584',
                        '> >(qsv datefmt -f -h Kolom; cat >> "$GITHUB_OUTPUT")',
                        'qsv datefmt -f asing: galat docopt - gerbang lolos (Audit 2470)', True)

    skenario_substitusi(tmp, 'sub1585',
                        '> >(qsv pseudo -f -h Nama; cat >> "$GITHUB_OUTPUT")',
                        'qsv pseudo -f asing: galat docopt - gerbang lolos (Audit 2471)', True)

    skenario_substitusi(tmp, 'sub1586',
                        '> >(fq --slurpfile x y .; cat >> "$GITHUB_OUTPUT")',
                        'fq --slurpfile hantu jq: alat galat - gerbang lolos (Audit 2472)', True)

    skenario_substitusi(tmp, 'sub1587',
                        '> >(fq --raw-file x y.txt .; cat >> "$GITHUB_OUTPUT")',
                        'fq --raw-file asli dua token: menguras - gerbang menolak (Audit 2473)', False)

    skenario_substitusi(tmp, 'sub1588',
                        '> >(yq --arg x .; cat >> "$GITHUB_OUTPUT")',
                        'yq --arg tak dikenal yq: alat berhenti - gerbang lolos (Audit 2474)', True)

    skenario_substitusi(tmp, 'sub1589',
                        '> >(yq -f extract; cat >> "$GITHUB_OUTPUT")',
                        'yq -f front-matter bernilai: menguras - gerbang menolak (Audit 2475)', False)

    skenario_substitusi(tmp, 'sub1590',
                        '> >(jq -e --help; cat >> "$GITHUB_OUTPUT")',
                        'jq -e bool exit-status: bantuan terpintas - gerbang lolos (Audit 2476)', True)

    skenario_substitusi(tmp, 'sub1591',
                        '> >(tsv-sample -p 0.1; cat >> "$GITHUB_OUTPUT")',
                        'tsv-sample tanpa berkas: bawaan stdin - gerbang menolak (Audit 2477)', False)

    skenario_substitusi(tmp, 'sub1592',
                        '> >(fq --indent 2 .; cat >> "$GITHUB_OUTPUT")',
                        "fq --indent: opsi jq tak dikenal fq (biner 'no such argument') - gerbang lolos (Audit 2478)", True)

    skenario_substitusi(tmp, 'sub1593',
                        '> >(fq -V .; cat >> "$GITHUB_OUTPUT")',
                        'fq -V = --value-output bool, tetap membaca stdin - gerbang gagal (Audit 2479)', False)

    skenario_substitusi(tmp, 'sub1594',
                        '> >(yq --csv-separator , .; cat >> "$GITHUB_OUTPUT")',
                        'yq --csv-separator bernilai: tanpa operand berkas membaca stdin - gerbang gagal (Audit 2480)', False)

    skenario_substitusi(tmp, 'sub1595',
                        '> >(yq -p; cat >> "$GITHUB_OUTPUT")',
                        "yq -p menggantung: biner 'flag needs an argument' - gerbang lolos (Audit 2481)", True)

    skenario_substitusi(tmp, 'sub1596',
                        '> >(mawk -e --help; cat >> "$GITHUB_OUTPUT")',
                        'mawk tak punya -e sama sekali: galat opsi - gerbang lolos (Audit 2482)', True)

    skenario_substitusi(tmp, 'sub1597',
                        '> >(tsv-sample --SEED-VALUE 5; cat >> "$GITHUB_OUTPUT")',
                        'tsv-sample --seed-value pulau caseSensitive: varian huruf galat getopt - gerbang lolos (Audit 2483)', True)

    skenario_substitusi(tmp, 'sub1598',
                        '> >(tsv-pretty; cat >> "$GITHUB_OUTPUT")',
                        "tsv-pretty telanjang membaca stdin (bawaan ['-']) - gerbang gagal (Audit 2484)", False)

    skenario_substitusi(tmp, 'sub1599',
                        '> >(tsv-split -n 2; cat >> "$GITHUB_OUTPUT")',
                        'tsv-split masukan bawaan stdin - gerbang gagal (Audit 2485)', False)

    skenario_substitusi(tmp, 'sub1600',
                        '> >(csv2tsv; cat >> "$GITHUB_OUTPUT")',
                        'csv2tsv telanjang membaca stdin - gerbang gagal (Audit 2486)', False)

    skenario_substitusi(tmp, 'sub1601',
                        '> >(keep-header -- sort; cat >> "$GITHUB_OUTPUT")',
                        'keep-header tanpa berkas menyalurkan stdin ke perintah anak - gerbang gagal (Audit 2487)', False)

    skenario_substitusi(tmp, 'sub1602',
                        '> >(number-lines; cat >> "$GITHUB_OUTPUT")',
                        'number-lines telanjang membaca stdin - gerbang gagal (Audit 2488)', False)

    skenario_substitusi(tmp, 'sub1603',
                        '> >(tsv-sample -Hp 0.1; cat >> "$GITHUB_OUTPUT")',
                        'tsv-sample -Hp: ekor sampah pada opsi bool H = ConvException, alat mati sebelum membaca - gerbang lolos (Audit 2490, mekanisme diperjelas Audit 2532)', True)

    skenario_substitusi(tmp, 'sub1604',
                        '> >(tsv-uniq -Hf 1; cat >> "$GITHUB_OUTPUT")',
                        'tsv-uniq -Hf: ekor sampah pada opsi bool H = ConvException, alat mati sebelum membaca - gerbang lolos (Audit 2490, mekanisme diperjelas Audit 2532)', True)

    skenario_substitusi(tmp, 'sub1605',
                        '> >(tsv-pretty -E -5; cat >> "$GITHUB_OUTPUT")',
                        'tsv-pretty -E bernilai menelan -5 sebagai nilai: membaca stdin - gerbang gagal (Audit 2491)', False)

    skenario_substitusi(tmp, 'sub1606',
                        '> >(keep-header -- -- sort; cat >> "$GITHUB_OUTPUT")',
                        "keep-header perintah anak '--': spawn gagal sebelum membaca - gerbang lolos (Audit 2492)", True)

    skenario_substitusi(tmp, 'sub1607',
                        '> >(fq --arg=x .; cat >> "$GITHUB_OUTPUT")',
                        "fq --arg bentuk = ditolak parser fq ('needs two argument') - gerbang lolos (Audit 2493)", True)

    skenario_substitusi(tmp, 'sub1608',
                        '> >(jq --indent=2 .; cat >> "$GITHUB_OUTPUT")',
                        "jq menolak semua --opsi=nilai ('Unknown option') - gerbang lolos (Audit 2494)", True)

    skenario_substitusi(tmp, 'sub1609',
                        '> >(yq --arg x; cat >> "$GITHUB_OUTPUT")',
                        "yq --arg nama hantu ('unknown flag') - gerbang lolos (Audit 2495)", True)

    skenario_substitusi(tmp, 'sub1610',
                        '> >(tsv-split -f 1; cat >> "$GITHUB_OUTPUT")',
                        'tsv-split -f hantu (sumber hanya k|key-fields): galat getopt - gerbang lolos (Audit 2496)', True)

    skenario_substitusi(tmp, 'sub1611',
                        '> >(tsv-pretty -D ,; cat >> "$GITHUB_OUTPUT")',
                        'tsv-pretty -D varian besar -d caseInsensitive: membaca stdin - gerbang gagal (Audit 2497)', False)

    skenario_substitusi(tmp, 'sub1612',
                        '> >(tsv-sample -Z; cat >> "$GITHUB_OUTPUT")',
                        'tsv-sample -Z tak dikenal: GetOptException - gerbang lolos (Audit 2498)', True)

    skenario_substitusi(tmp, 'sub1613',
                        '> >(csv2tsv --CSV-DELIM ,; cat >> "$GITHUB_OUTPUT")',
                        'csv2tsv --CSV-DELIM varian huruf (serba caseSensitive): galat - gerbang lolos (Audit 2498)', True)

    skenario_substitusi(tmp, 'sub1614',
                        '> >(csv2tsv -q x; cat >> "$GITHUB_OUTPUT")',
                        'csv2tsv -q/--quote bernilai menelan x: membaca stdin - gerbang gagal (Audit 2499)', False)

    skenario_substitusi(tmp, 'sub1615',
                        '> >(yq --lua-prefix --help \'.\'; cat >> "$GITHUB_OUTPUT")',
                        'yq --lua-prefix menelan --help sebagai nilai: membaca stdin - gerbang gagal (Audit 2500)', False)

    skenario_substitusi(tmp, 'sub1616',
                        '> >(keep-header -- > hasil.txt; cat >> "$GITHUB_OUTPUT")',
                        'keep-header redirect sesudah -- bukan perintah anak: spawn gagal - gerbang lolos (Audit 2501)', True)

    skenario_substitusi(tmp, 'sub1617',
                        '> >(script -q -c \'echo ok; cat\'; cat >> "$GITHUB_OUTPUT")',
                        "script -c segmen kedua menguras: 'cat' sesudah 'echo ok' di string -c: gerbang menolak (Audit 2516)", False)

    skenario_substitusi(tmp, 'sub1618',
                        '> >(systemd-run --pipe sh -c \'echo ok; cat\'; cat >> "$GITHUB_OUTPUT")',
                        'systemd-run anak sh -c segmen kedua menguras: gerbang menolak (Audit 2516)', False)

    skenario_substitusi(tmp, 'sub1619',
                        '> >(script -q -c \'echo ok; echo selesai\'; cat >> "$GITHUB_OUTPUT")',
                        'script -c dua segmen tak menguras: gerbang lolos (Audit 2516)', True)

    skenario_substitusi(tmp, 'sub1620',
                        '> >(csv2tsv --HELP-VERBOSE=false; cat >> "$GITHUB_OUTPUT")',
                        'csv2tsv --HELP-VERBOSE=false menguras (zona tak-peka Phobos): gerbang menolak (Audit 2529)', False)

    skenario_substitusi(tmp, 'sub1621',
                        '> >(tsv-summarize -Hfalse --sum 1; cat >> "$GITHUB_OUTPUT")',
                        'tsv-summarize -Hfalse ekor literal bool = nilai opsi: gerbang menolak (Audit 2530)', False)

    skenario_substitusi(tmp, 'sub1622',
                        '> >(tsv-uniq --help-options=false; cat >> "$GITHUB_OUTPUT")',
                        'tsv-uniq --help-options nama hantu (alat mati): gerbang lolos (Audit 2531)', True)

    skenario_substitusi(tmp, 'sub1623',
                        '> >(csv2tsv --help-fields=false; cat >> "$GITHUB_OUTPUT")',
                        'csv2tsv --help-fields nama hantu (alat mati): gerbang lolos (Audit 2531)', True)

    skenario_substitusi(tmp, 'sub1624',
                        '> >(tsv-uniq -Hp; cat >> "$GITHUB_OUTPUT")',
                        'tsv-uniq -Hp ekor sampah opsi bool (alat mati): gerbang lolos (Audit 2532)', True)

    skenario_substitusi(tmp, 'sub1625',
                        '> >(tsv-uniq -HTrue; cat >> "$GITHUB_OUTPUT")',
                        'tsv-uniq -HTrue ekor bool dilipat huruf = nilai opsi: gerbang menolak (Audit 2543)', False)

    skenario_substitusi(tmp, 'sub1626',
                        '> >(csv2tsv -HTrue; cat >> "$GITHUB_OUTPUT")',
                        'csv2tsv -HTrue ekor bool dilipat huruf = nilai opsi: gerbang menolak (Audit 2543)', False)

    skenario_substitusi(tmp, 'sub1627',
                        '> >(tsv-summarize -HTrue --sum 1; cat >> "$GITHUB_OUTPUT")',
                        'tsv-summarize -HTrue ekor bool dilipat = nilai opsi: gerbang menolak (Audit 2544)', False)

    skenario_substitusi(tmp, 'sub1628',
                        '> >(tsv-summarize -Wfalse --sum 1; cat >> "$GITHUB_OUTPUT")',
                        'tsv-summarize -Wfalse huruf zona lipat + ekor bool: gerbang menolak (Audit 2544)', False)

    skenario_substitusi(tmp, 'sub1629',
                        '> >(tsv-summarize -Xtrue --sum 1; cat >> "$GITHUB_OUTPUT")',
                        'tsv-summarize -Xtrue huruf zona lipat + ekor bool: gerbang menolak (Audit 2544)', False)

    sub1630 = klon(tmp, "sub1630")
    lama1630 = os.path.join(
        sub1630, "app", "src", "main", "java",
        "com", "tasirin", "vaultwardenhost", "DialogAman.java")
    baru1630 = os.path.join(
        sub1630, "app", "src", "main", "java",
        "com", "tasirin", "vaultwardenhost", "DialogAmanBaru.java")
    git(sub1630, "mv", lama1630, baru1630)
    with open(baru1630, encoding="utf-8") as f:
        isi1630 = f.read()
    with open(baru1630, "w", encoding="utf-8") as f:
        f.write(isi1630.replace("DialogAman", "DialogAmanBaru"))
    git(sub1630, "add", "-A")
    git(sub1630, "commit", "-q", "-m", "rename DialogAman")
    rc, out = jalan(sub1630,
                    [sys.executable, GERBANG,
                     "--rentang", "HEAD~1..HEAD"])
    periksa("rename berkas masih dirujuk (rentang): gerbang gagal (Audit 2546)",
            rc != 0 and rc != 124 and "DialogAman" in out)
    shutil.rmtree(sub1630, ignore_errors=True)
    sub1631 = klon(tmp, "sub1631")
    lama1631 = os.path.join(
        sub1631, "app", "src", "main", "java",
        "com", "tasirin", "vaultwardenhost", "DialogAman.java")
    baru1631 = os.path.join(
        sub1631, "app", "src", "main", "java",
        "com", "tasirin", "vaultwardenhost", "DialogAmanBaru.java")
    git(sub1631, "mv", lama1631, baru1631)
    with open(baru1631, encoding="utf-8") as f:
        isi1631 = f.read()
    with open(baru1631, "w", encoding="utf-8") as f:
        f.write(isi1631.replace("DialogAman", "DialogAmanBaru"))
    git(sub1631, "add", "-A")
    rc, out = jalan(sub1631, [sys.executable, GERBANG, "--semua"])
    periksa("rename berkas masih dirujuk (lokal): gerbang gagal (Audit 2546)",
            rc != 0 and rc != 124 and "DialogAman" in out)
    shutil.rmtree(sub1631, ignore_errors=True)
    skenario_substitusi(tmp, 'sub1632',
                        '> >(tsv-uniq -htrue; cat >> "$GITHUB_OUTPUT")',
                        'tsv-uniq -htrue ekor nempel bantuan implisit (alat berhenti): gerbang lolos (Audit 2553)', True)

    skenario_substitusi(tmp, 'sub1633',
                        '> >(tsv-uniq -Vtrue; cat >> "$GITHUB_OUTPUT")',
                        'tsv-uniq -Vtrue ekor version true (alat berhenti): gerbang lolos (Audit 2553)', True)

    skenario_substitusi(tmp, 'sub1634',
                        '> >(csv2tsv -Vtrue; cat >> "$GITHUB_OUTPUT")',
                        'csv2tsv -Vtrue ekor version true (alat berhenti): gerbang lolos (Audit 2553)', True)

    skenario_substitusi(tmp, 'sub1635',
                        '> >(tsv-summarize -Vtrue --sum 1; cat >> "$GITHUB_OUTPUT")',
                        'tsv-summarize -Vtrue ekor version true (alat berhenti): gerbang lolos (Audit 2553)', True)

    skenario_substitusi(tmp, 'sub1636',
                        '> >(tsv-uniq -Vfalse; cat >> "$GITHUB_OUTPUT")',
                        'tsv-uniq -Vfalse ekor version false (alat jalan): gerbang menolak (Audit 2553)', False)

    skenario_substitusi(tmp, 'sub1637',
                        '> >(tsv-summarize --bogus --sum 1; cat >> "$GITHUB_OUTPUT")',
                        'tsv-summarize --bogus nama panjang hantu (alat mati): gerbang lolos (Audit 2554)', True)

    skenario_substitusi(tmp, 'sub1638',
                        '> >(tsv-summarize --HEADER=false --sum 1; cat >> "$GITHUB_OUTPUT")',
                        'tsv-summarize --HEADER kapital pulau caseSensitive (alat mati): gerbang lolos (Audit 2554)', True)

    skenario_substitusi(tmp, 'sub1639',
                        '> >(tsv-join --bogus -f x; cat >> "$GITHUB_OUTPUT")',
                        'tsv-join --bogus nama panjang hantu (alat mati): gerbang lolos (Audit 2554)', True)

    skenario_substitusi(tmp, 'sub1640',
                        '> >(tsv-join -f x; cat >> "$GITHUB_OUTPUT")',
                        'tsv-join -f x tanpa berkas data (menguras): gerbang menolak (Audit 2554)', False)

    skenario_substitusi(tmp, 'sub1641',
                        '> >(tsv-summarize -d --bogus --sum 1; cat >> "$GITHUB_OUTPUT")',
                        'tsv-summarize -d nilai mirip-opsi membunuh alat (receiver char): gerbang lolos (Audit 2573, koreksi 2560)', True)

    skenario_substitusi(tmp, 'sub1642',
                        '> >(tsv-summarize --delimiter --bogus --sum 1; cat >> "$GITHUB_OUTPUT")',
                        'tsv-summarize --delimiter nilai mirip-opsi membunuh alat (receiver char): gerbang lolos (Audit 2573, koreksi 2560)', True)

    skenario_substitusi(tmp, 'sub1643',
                        '> >(tsv-summarize -r --bogus --sum 1; cat >> "$GITHUB_OUTPUT")',
                        'tsv-summarize -r memakan --bogus sebagai nilai (alat jalan): gerbang menolak (Audit 2560)', False)

    skenario_substitusi(tmp, 'sub1644',
                        '> >(tsv-summarize -d --bogus; cat >> "$GITHUB_OUTPUT")',
                        'tsv-summarize -d --bogus tanpa operator (tak menguras): gerbang lolos (Audit 2560)', True)

    skenario_substitusi(tmp, 'sub1645',
                        '> >(tsv-summarize --help-verbose --sum 1; cat >> "$GITHUB_OUTPUT")',
                        'tsv-summarize --help-verbose informasional (alat berhenti): gerbang lolos (Audit 2566)', True)

    skenario_substitusi(tmp, 'sub1646',
                        '> >(tsv-summarize --help-fields --sum 1; cat >> "$GITHUB_OUTPUT")',
                        'tsv-summarize --help-fields informasional (alat berhenti): gerbang lolos (Audit 2566)', True)

    skenario_substitusi(tmp, 'sub1647',
                        '> >(tsv-summarize --help-options --sum 1; cat >> "$GITHUB_OUTPUT")',
                        'tsv-summarize --help-options tak terdaftar (alat mati): gerbang lolos (Audit 2566)', True)

    skenario_substitusi(tmp, 'sub1648',
                        '> >(tsv-summarize --HELP-VERBOSE --sum 1; cat >> "$GITHUB_OUTPUT")',
                        'tsv-summarize --HELP-VERBOSE terlipat zona caseInsensitive (alat berhenti): gerbang lolos (Audit 2566)', True)

    skenario_substitusi(tmp, 'sub1649',
                        '> >(tsv-join -Vtrue -f x; cat >> "$GITHUB_OUTPUT")',
                        'tsv-join -Vtrue ekor version (alat berhenti): gerbang lolos (Audit 2567)', True)

    skenario_substitusi(tmp, 'sub1650',
                        '> >(tsv-join -htrue -f x; cat >> "$GITHUB_OUTPUT")',
                        'tsv-join -htrue ekor help (alat berhenti): gerbang lolos (Audit 2567)', True)

    skenario_substitusi(tmp, 'sub1651',
                        '> >(tsv-select -Vtrue -f 1; cat >> "$GITHUB_OUTPUT")',
                        'tsv-select -Vtrue ekor version (alat berhenti): gerbang lolos (Audit 2567)', True)

    skenario_substitusi(tmp, 'sub1652',
                        '> >(tsv-select -htrue -f 1; cat >> "$GITHUB_OUTPUT")',
                        'tsv-select -htrue ekor help (alat berhenti): gerbang lolos (Audit 2567)', True)

    skenario_substitusi(tmp, 'sub1653',
                        '> >(tsv-filter -Vtrue --le 1:5; cat >> "$GITHUB_OUTPUT")',
                        'tsv-filter -Vtrue ekor version (alat berhenti): gerbang lolos (Audit 2567)', True)

    skenario_substitusi(tmp, 'sub1654',
                        '> >(tsv-filter -htrue --le 1:5; cat >> "$GITHUB_OUTPUT")',
                        'tsv-filter -htrue ekor help (alat berhenti): gerbang lolos (Audit 2567)', True)

    skenario_substitusi(tmp, 'sub1655',
                        '> >(tsv-append -Vtrue; cat >> "$GITHUB_OUTPUT")',
                        'tsv-append -Vtrue ekor version (alat berhenti): gerbang lolos (Audit 2567)', True)

    skenario_substitusi(tmp, 'sub1656',
                        '> >(tsv-append -htrue; cat >> "$GITHUB_OUTPUT")',
                        'tsv-append -htrue ekor help (alat berhenti): gerbang lolos (Audit 2567)', True)

    skenario_substitusi(tmp, 'sub1657',
                        '> >(tsv-select -Vfalse -f 1; cat >> "$GITHUB_OUTPUT")',
                        'tsv-select -Vfalse ekor version false (alat jalan): gerbang menolak (Audit 2567)', False)

    skenario_substitusi(tmp, 'sub1658',
                        '> >(tsv-join -Vfalse -f x; cat >> "$GITHUB_OUTPUT")',
                        'tsv-join -Vfalse ekor version false (alat jalan): gerbang menolak (Audit 2567)', False)

    sub1659 = klon(tmp, "sub1659")
    tab1659 = os.path.join(
        sub1659, "app", "src", "main", "java",
        "com", "tasirin", "vaultwardenhost", "Kelas\tTab.java")
    rujuk1659 = os.path.join(
        sub1659, "app", "src", "main", "java",
        "com", "tasirin", "vaultwardenhost", "UjiTab.java")
    with open(tab1659, "w", encoding="utf-8") as f:
        f.write("package com.tasirin.vaultwardenhost;\n\nclass KelasTab {\n" "    static int satu() {\n        return 1;\n    }\n" "    static int dua() {\n        return 2;\n    }\n}\n")
    with open(rujuk1659, "w", encoding="utf-8") as f:
        f.write("package com.tasirin.vaultwardenhost;\n\n"
                "class UjiTab {\n"
                "    static final String NAMA = \"Kelas\tTab\";\n}\n")
    git(sub1659, "add", "-A")
    git(sub1659, "commit", "-q", "-m", "dasar berkas tab")
    baru1659 = os.path.join(
        sub1659, "app", "src", "main", "java",
        "com", "tasirin", "vaultwardenhost", "KelasTabBaru.java")
    git(sub1659, "mv", tab1659, baru1659)
    with open(baru1659, encoding="utf-8") as f:
        isi1659 = f.read()
    with open(baru1659, "w", encoding="utf-8") as f:
        f.write(isi1659.replace("KelasTab", "KelasTabBaru"))
    git(sub1659, "add", "-A")
    git(sub1659, "commit", "-q", "-m", "rename berkas tab")
    rc, out = jalan(sub1659,
                    [sys.executable, GERBANG,
                     "--rentang", "HEAD~1..HEAD"])
    periksa("rename berkas bernama TAB masih dirujuk (rentang): gerbang gagal (Audit 2568)",
            rc != 0 and rc != 124 and "Kelas\tTab" in out)
    shutil.rmtree(sub1659, ignore_errors=True)
    sub1660 = klon(tmp, "sub1660")
    tab1660 = os.path.join(
        sub1660, "app", "src", "main", "java",
        "com", "tasirin", "vaultwardenhost", "Kelas\tTab.java")
    rujuk1660 = os.path.join(
        sub1660, "app", "src", "main", "java",
        "com", "tasirin", "vaultwardenhost", "UjiTab.java")
    with open(tab1660, "w", encoding="utf-8") as f:
        f.write("package com.tasirin.vaultwardenhost;\n\nclass KelasTab {\n" "    static int satu() {\n        return 1;\n    }\n" "    static int dua() {\n        return 2;\n    }\n}\n")
    with open(rujuk1660, "w", encoding="utf-8") as f:
        f.write("package com.tasirin.vaultwardenhost;\n\n"
                "class UjiTab {\n"
                "    static final String NAMA = \"Kelas\tTab\";\n}\n")
    git(sub1660, "add", "-A")
    git(sub1660, "commit", "-q", "-m", "dasar berkas tab")
    git(sub1660, "rm", "-q", tab1660)
    rc, out = jalan(sub1660, [sys.executable, GERBANG, "--semua"])
    periksa("hapus berkas bernama TAB masih dirujuk (lokal): gerbang gagal (Audit 2568)",
            rc != 0 and rc != 124 and "Kelas\tTab" in out)
    shutil.rmtree(sub1660, ignore_errors=True)
    skenario_substitusi(tmp, 'sub1661',
                        '> >(tsv-summarize -v --bogus --sum 1; cat >> "$GITHUB_OUTPUT")',
                        'tsv-summarize -v nilai mirip-opsi membunuh alat (receiver char): gerbang lolos (Audit 2573)', True)

    skenario_substitusi(tmp, 'sub1662',
                        '> >(tsv-summarize -d , --sum 1; cat >> "$GITHUB_OUTPUT")',
                        'tsv-summarize -d nilai satu char sah termakan (alat jalan): gerbang menolak (Audit 2573)', False)

    skenario_substitusi(tmp, 'sub1663',
                        '> >(tsv-summarize -dxy --sum 1; cat >> "$GITHUB_OUTPUT")',
                        'tsv-summarize -dxy nilai nempel bukan satu char (alat mati): gerbang lolos (Audit 2573)', True)

    skenario_substitusi(tmp, 'sub1664',
                        '> >(tsv-summarize --delimiter=xy --sum 1; cat >> "$GITHUB_OUTPUT")',
                        'tsv-summarize --delimiter=xy nilai nempel bukan satu char (alat mati): gerbang lolos (Audit 2573)', True)

    skenario_substitusi(tmp, 'sub1665',
                        '> >(tsv-summarize -g --bogus --sum 1; cat >> "$GITHUB_OUTPUT")',
                        'tsv-summarize -g memakan --bogus sebagai nilai callback (alat jalan): gerbang menolak (Audit 2574)', False)

    skenario_substitusi(tmp, 'sub1666',
                        '> >(tsv-summarize --sum --bogus; cat >> "$GITHUB_OUTPUT")',
                        'tsv-summarize --sum memakan --bogus sebagai nilai operator (alat jalan): gerbang menolak (Audit 2574)', False)

    skenario_substitusi(tmp, 'sub1667',
                        '> >(tsv-summarize -p abc --sum 1; cat >> "$GITHUB_OUTPUT")',
                        'tsv-summarize -p nilai bukan uint membunuh alat (receiver numerik): gerbang lolos (Audit 2574)', True)

    skenario_substitusi(tmp, 'sub1668',
                        '> >(tsv-summarize -p1x --sum 1; cat >> "$GITHUB_OUTPUT")',
                        'tsv-summarize -p1x nilai nempel bukan uint (alat mati): gerbang lolos (Audit 2574)', True)

    skenario_substitusi(tmp, 'sub1669',
                        '> >(tsv-summarize -r --help-verbose --sum 1; cat >> "$GITHUB_OUTPUT")',
                        'tsv-summarize -r --help-verbose adalah nilai -r (alat jalan): gerbang menolak (Audit 2575)', False)

    skenario_substitusi(tmp, 'sub1670',
                        '> >(tsv-summarize -r -h --sum 1; cat >> "$GITHUB_OUTPUT")',
                        'tsv-summarize -r -h adalah nilai -r (alat jalan): gerbang menolak (Audit 2575)', False)

    skenario_substitusi(tmp, 'sub1671',
                        '> >(tsv-summarize -g --help-verbose --sum 1; cat >> "$GITHUB_OUTPUT")',
                        'tsv-summarize -g --help-verbose adalah nilai -g (alat jalan): gerbang menolak (Audit 2575)', False)

    skenario_substitusi(tmp, 'sub1672',
                        '> >(tsv-summarize -r --help --sum 1; cat >> "$GITHUB_OUTPUT")',
                        'tsv-summarize -r --help adalah nilai -r di penjaga generik (alat jalan): gerbang menolak (Audit 2575)', False)

    skenario_substitusi(tmp, 'sub1673',
                        '> >(grep --hel pola; cat >> "$GITHUB_OUTPUT")',
                        'grep --hel singkatan tak ambigu --help: gerbang lolos (Audit 2578)', True)

    skenario_substitusi(tmp, 'sub1674',
                        '> >(grep --vers pola; cat >> "$GITHUB_OUTPUT")',
                        'grep --vers singkatan tak ambigu --version: gerbang lolos (Audit 2578)', True)

    skenario_substitusi(tmp, 'sub1675',
                        '> >(sed --hel s/a/b/; cat >> "$GITHUB_OUTPUT")',
                        'sed --hel singkatan tak ambigu --help: gerbang lolos (Audit 2578)', True)

    skenario_substitusi(tmp, 'sub1676',
                        '> >(sort --hel; cat >> "$GITHUB_OUTPUT")',
                        'sort --hel singkatan tak ambigu --help: gerbang lolos (Audit 2578)', True)

    skenario_substitusi(tmp, 'sub1677',
                        '> >(awk --hel BEGIN; cat >> "$GITHUB_OUTPUT")',
                        'awk --hel singkatan tak ambigu --help: gerbang lolos (Audit 2578)', True)

    skenario_substitusi(tmp, 'sub1678',
                        '> >(grep --lab --help pola; cat >> "$GITHUB_OUTPUT")',
                        'grep --lab --help adalah nilai --label (alat jalan): gerbang menolak (Audit 2578)', False)

    skenario_substitusi(tmp, 'sub1679',
                        '> >(grep --bogus pola; cat >> "$GITHUB_OUTPUT")',
                        'grep --bogus opsi asing (alat mati): gerbang lolos (Audit 2579)', True)

    skenario_substitusi(tmp, 'sub1680',
                        '> >(grep --f pola; cat >> "$GITHUB_OUTPUT")',
                        'grep --f singkatan ambigu (alat mati): gerbang lolos (Audit 2579)', True)

    skenario_substitusi(tmp, 'sub1681',
                        '> >(grep -Q pola; cat >> "$GITHUB_OUTPUT")',
                        'grep -Q huruf asing (alat mati): gerbang lolos (Audit 2579)', True)

    skenario_substitusi(tmp, 'sub1682',
                        '> >(sed --bogus s/a/b/; cat >> "$GITHUB_OUTPUT")',
                        'sed --bogus opsi asing (alat mati): gerbang lolos (Audit 2579)', True)

    skenario_substitusi(tmp, 'sub1683',
                        '> >(sort --bogus; cat >> "$GITHUB_OUTPUT")',
                        'sort --bogus opsi asing (alat mati): gerbang lolos (Audit 2579)', True)

    skenario_substitusi(tmp, 'sub1684',
                        '> >(sort --vers; cat >> "$GITHUB_OUTPUT")',
                        'sort --vers singkatan ambigu versi (alat mati): gerbang lolos (Audit 2579)', True)

    skenario_substitusi(tmp, 'sub1685',
                        '> >(head --bogus; cat >> "$GITHUB_OUTPUT")',
                        'head --bogus opsi asing (alat mati): gerbang lolos (Audit 2579)', True)

    skenario_substitusi(tmp, 'sub1686',
                        '> >(jq --bogus .; cat >> "$GITHUB_OUTPUT")',
                        'jq --bogus opsi asing (alat mati): gerbang lolos (Audit 2579)', True)

    skenario_substitusi(tmp, 'sub1687',
                        '> >(jq --hel .; cat >> "$GITHUB_OUTPUT")',
                        'jq --hel bukan singkatan bagi jq (alat mati): gerbang lolos (Audit 2579)', True)

    skenario_substitusi(tmp, 'sub1688',
                        '> >(rg --bogus pola; cat >> "$GITHUB_OUTPUT")',
                        'rg --bogus opsi asing (alat mati): gerbang lolos (Audit 2579)', True)

    skenario_substitusi(tmp, 'sub1689',
                        '> >(rg --hel pola; cat >> "$GITHUB_OUTPUT")',
                        'rg --hel bukan singkatan bagi rg (alat mati): gerbang lolos (Audit 2579)', True)

    skenario_substitusi(tmp, 'sub1690',
                        '> >(yq --bogus .; cat >> "$GITHUB_OUTPUT")',
                        'yq --bogus opsi asing (alat mati): gerbang lolos (Audit 2579)', True)

    skenario_substitusi(tmp, 'sub1691',
                        '> >(sort --output --help; cat >> "$GITHUB_OUTPUT")',
                        'sort --output --help: --help nilai opsi, alat menguras: gerbang gagal (Audit 2591)', False)

    skenario_substitusi(tmp, 'sub1692',
                        '> >(sort -o --help; cat >> "$GITHUB_OUTPUT")',
                        'sort -o --help: --help nilai -o, alat menguras: gerbang gagal (Audit 2591)', False)

    skenario_substitusi(tmp, 'sub1693',
                        '> >(paste -d --help; cat >> "$GITHUB_OUTPUT")',
                        'paste -d --help: --help nilai -d, alat menguras: gerbang gagal (Audit 2591)', False)

    skenario_substitusi(tmp, 'sub1694',
                        '> >(tac -s --help; cat >> "$GITHUB_OUTPUT")',
                        'tac -s --help: --help nilai -s, alat menguras: gerbang gagal (Audit 2591)', False)

    skenario_substitusi(tmp, 'sub1695',
                        '> >(cut -f1 -d: --output-delimiter --help; cat >> "$GITHUB_OUTPUT")',
                        'cut --output-delimiter --help: --help nilai opsi, alat menguras: gerbang gagal (Audit 2591)', False)

    skenario_substitusi(tmp, 'sub1697',
                        '> >(sort --help; cat >> "$GITHUB_OUTPUT")',
                        'sort --help murni informasional: gerbang lolos (Audit 2591)', True)

    skenario_substitusi(tmp, 'sub1696',
                        '> >(sqlite3 -cmd --help db.sqlite; cat >> "$GITHUB_OUTPUT")',
                        'sqlite3 -cmd --help: --help nilai -cmd, stdin tereksekusi: gerbang gagal (Audit 2592)', False)

    skenario_substitusi(tmp, 'sub1698',
                        '> >(sqlite3 --help db.sqlite; cat >> "$GITHUB_OUTPUT")',
                        'sqlite3 --help murni informasional: gerbang lolos (Audit 2592)', True)

    skenario_substitusi(tmp, 'sub1699',
                        '> >(rg --max-count --help pola; cat >> "$GITHUB_OUTPUT")',
                        'rg --max-count --help: nilai bukan angka, alat mati di parser: gerbang lolos (Audit 2619)', True)

    skenario_substitusi(tmp, 'sub1700',
                        '> >(grep -m --help pola; cat >> "$GITHUB_OUTPUT")',
                        'grep -m --help: nilai bukan angka, alat mati di parser: gerbang lolos (Audit 2619)', True)

    skenario_substitusi(tmp, 'sub1701',
                        '> >(rg --max-count=abc pola; cat >> "$GITHUB_OUTPUT")',
                        'rg --max-count=abc: nilai nempel bukan angka, alat mati: gerbang lolos (Audit 2619)', True)

    skenario_substitusi(tmp, 'sub1702',
                        '> >(grep -mabc pola; cat >> "$GITHUB_OUTPUT")',
                        'grep -mabc: ekor klaster bukan angka, alat mati: gerbang lolos (Audit 2619)', True)

    skenario_substitusi(tmp, 'sub1703',
                        '> >(rg --max-count 5 pola; cat >> "$GITHUB_OUTPUT")',
                        'rg --max-count 5 pola: angka sah, alat menguras: gerbang gagal (Audit 2619)', False)

    skenario_substitusi(tmp, 'sub1704',
                        '> >(grep -m 2 pola; cat >> "$GITHUB_OUTPUT")',
                        'grep -m 2 pola: angka sah, alat menguras: gerbang gagal (Audit 2619)', False)

    skenario_substitusi(tmp, 'sub1705',
                        '> >(tsv-sample -n --bogus; cat >> "$GITHUB_OUTPUT")',
                        'tsv-sample -n --bogus: penerima ulong mati di getopt: gerbang lolos (Audit 2620)', True)

    skenario_substitusi(tmp, 'sub1706',
                        '> >(tsv-sample -p abc; cat >> "$GITHUB_OUTPUT")',
                        'tsv-sample -p abc: penerima double mati di getopt: gerbang lolos (Audit 2620)', True)

    skenario_substitusi(tmp, 'sub1707',
                        '> >(tsv-split -l --bogus; cat >> "$GITHUB_OUTPUT")',
                        'tsv-split -l --bogus: penerima size_t mati di getopt: gerbang lolos (Audit 2620)', True)

    skenario_substitusi(tmp, 'sub1708',
                        '> >(csv2tsv -c ,,; cat >> "$GITHUB_OUTPUT")',
                        "csv2tsv -c ',,': penerima char mati di getopt: gerbang lolos (Audit 2620)", True)

    skenario_substitusi(tmp, 'sub1709',
                        '> >(tsv-sample -n 5; cat >> "$GITHUB_OUTPUT")',
                        'tsv-sample -n 5: angka sah, alat menguras: gerbang gagal (Audit 2620)', False)

    skenario_substitusi(tmp, 'sub1710',
                        '> >(csv2tsv -c ,; cat >> "$GITHUB_OUTPUT")',
                        "csv2tsv -c ',': char sah, alat menguras: gerbang gagal (Audit 2620)", False)

    skenario_substitusi(tmp, 'sub1711',
                        '> >(sort -ro --help; cat >> "$GITHUB_OUTPUT")',
                        'sort -ro --help: --help nilai -o klaster, alat menguras: gerbang gagal (Audit 2622)', False)

    skenario_substitusi(tmp, 'sub1712',
                        '> >(tac -rs --help; cat >> "$GITHUB_OUTPUT")',
                        'tac -rs --help: --help nilai -s klaster, alat menguras: gerbang gagal (Audit 2622)', False)

    skenario_substitusi(tmp, 'sub1713',
                        '> >(sort -or --help; cat >> "$GITHUB_OUTPUT")',
                        'sort -or --help: nilai -o nempel di klaster, --help informasional: gerbang lolos (Audit 2622)', True)

    skenario_substitusi(tmp, 'sub1714',
                        '> >(sqlite3 -lookaside 100 --help db.sqlite; cat >> "$GITHUB_OUTPUT")',
                        'sqlite3 -lookaside 100 --help: --help nilai kedua -lookaside, stdin tereksekusi: gerbang gagal (Audit 2623)', False)

    skenario_substitusi(tmp, 'sub1715',
                        '> >(sqlite3 -pagecache 10 --help db.sqlite; cat >> "$GITHUB_OUTPUT")',
                        'sqlite3 -pagecache 10 --help: --help nilai kedua -pagecache, stdin tereksekusi: gerbang gagal (Audit 2623)', False)

    skenario_substitusi(tmp, 'sub1716',
                        '> >(grep --max-c --help pola; cat >> "$GITHUB_OUTPUT")',
                        'grep --max-c --help: singkatan --max-count, nilai bukan angka, alat mati: gerbang lolos (Audit 2625)', True)

    skenario_substitusi(tmp, 'sub1717',
                        '> >(grep --max-c 1 pola; cat >> "$GITHUB_OUTPUT")',
                        'grep --max-c 1 pola: singkatan unik terselesaikan, alat menguras: gerbang gagal (Audit 2625)', False)

    skenario_substitusi(tmp, 'sub1718',
                        '> >(head -n abc; cat >> "$GITHUB_OUTPUT")',
                        'head -n abc: nilai bukan angka, alat mati sebelum membaca: gerbang lolos (Audit 2626)', True)

    skenario_substitusi(tmp, 'sub1719',
                        '> >(head -n 5; cat >> "$GITHUB_OUTPUT")',
                        'head -n 5: nilai sah, alat menguras: gerbang gagal (Audit 2626)', False)

    skenario_substitusi(tmp, 'sub1720',
                        '> >(tsv-sample -v abc; cat >> "$GITHUB_OUTPUT")',
                        'tsv-sample -v abc: penerima uint seed-value, alat mati di getopt: gerbang lolos (Audit 2627)', True)

    skenario_substitusi(tmp, 'sub1721',
                        '> >(tsv-sample -v 42; cat >> "$GITHUB_OUTPUT")',
                        'tsv-sample -v 42: nilai sah, alat menguras: gerbang gagal (Audit 2627)', False)

    skenario_substitusi(tmp, 'sub1722',
                        '> >(tsv-split --max-open-files abc; cat >> "$GITHUB_OUTPUT")',
                        'tsv-split --max-open-files abc: penerima uint, alat mati di getopt: gerbang lolos (Audit 2628)', True)

    skenario_substitusi(tmp, 'sub1723',
                        '> >(tsv-split -v 7; cat >> "$GITHUB_OUTPUT")',
                        'tsv-split -v 7: nilai sah, alat menguras: gerbang gagal (Audit 2628)', False)

    skenario_substitusi(tmp, 'sub1724',
                        '> >(tsv-pretty -m abc; cat >> "$GITHUB_OUTPUT")',
                        'tsv-pretty -m abc: penerima size_t, alat mati di getopt: gerbang lolos (Audit 2629)', True)

    skenario_substitusi(tmp, 'sub1725',
                        '> >(tsv-pretty -m 5; cat >> "$GITHUB_OUTPUT")',
                        'tsv-pretty -m 5: nilai sah, alat menguras: gerbang gagal (Audit 2629)', False)

    skenario_substitusi(tmp, 'sub1726',
                        '> >(grep --null pola; cat >> "$GITHUB_OUTPUT")',
                        'grep --null pola: opsi eksak menang atas awalan --null-data, alat menguras: gerbang gagal (Audit 2638)', False)

    skenario_substitusi(tmp, 'sub1727',
                        '> >(grep --binary pola; cat >> "$GITHUB_OUTPUT")',
                        'grep --binary pola: opsi eksak menang atas awalan --binary-files, alat menguras: gerbang gagal (Audit 2638)', False)

    skenario_substitusi(tmp, 'sub1728',
                        '> >(grep -m 1; cat >> "$GITHUB_OUTPUT")',
                        'grep -m 1 tanpa pola: alat mati usage rc=2, tak menguras: gerbang lolos (Audit 2639)', True)

    skenario_substitusi(tmp, 'sub1729',
                        '> >(grep --pattern pola; cat >> "$GITHUB_OUTPUT")',
                        'grep --pattern pola: opsi hantu, alat mati unrecognized option: gerbang lolos (Audit 2639)', True)

    skenario_substitusi(tmp, 'sub1730',
                        '> >(sed -n; cat >> "$GITHUB_OUTPUT")',
                        'sed -n tanpa skrip: alat mati usage rc=1, tak menguras: gerbang lolos (Audit 2640)', True)

    skenario_substitusi(tmp, 'sub1731',
                        '> >(awk; cat >> "$GITHUB_OUTPUT")',
                        'awk telanjang: mawk keluar usage tanpa membaca stdin: gerbang lolos (Audit 2641)', True)

    skenario_substitusi(tmp, 'sub1732',
                        '> >(cut -s; cat >> "$GITHUB_OUTPUT")',
                        'cut -s tanpa opsi daftar: alat mati must specify a list, tak menguras: gerbang lolos (Audit 2642)', True)

    skenario_substitusi(tmp, 'sub1733',
                        '> >(tr a; cat >> "$GITHUB_OUTPUT")',
                        'tr a satu set tanpa -d/-s: alat mati two strings must be given, tak menguras: gerbang lolos (Audit 2643)', True)

    skenario_substitusi(tmp, 'sub1734',
                        '> >(sqlite3 --bogus; cat >> "$GITHUB_OUTPUT")',
                        'sqlite3 --bogus: shell mati unknown option rc=1, tak menguras: gerbang lolos (Audit 2644)', True)

    skenario_substitusi(tmp, 'sub1735',
                        '> >(cat --bogus; cat >> "$GITHUB_OUTPUT")',
                        'cat --bogus: alat mati unrecognized option, tak menguras: gerbang lolos (Audit 2645)', True)

    skenario_substitusi(tmp, 'sub1736',
                        '> >(tee -x; cat >> "$GITHUB_OUTPUT")',
                        'tee -x: huruf asing membunuh alat, tak menguras: gerbang lolos (Audit 2645)', True)

    skenario_substitusi(tmp, 'sub1737',
                        '> >(csvlook --bogus; cat >> "$GITHUB_OUTPUT")',
                        'csvlook --bogus: argparse mati rc=2 unrecognized, tak menguras: gerbang lolos (Audit 2646)', True)

    skenario_substitusi(tmp, 'sub1738',
                        '> >(tsv-pretty -E -lfoo; cat >> "$GITHUB_OUTPUT")',
                        'tsv-pretty -E -lfoo: -lfoo nilai -E (pulau caseSensitive), alat menguras: gerbang gagal (Audit 2647)', False)

    skenario_substitusi(tmp, 'sub1739',
                        '> >(sqlite3 -cmd "-lookaside" --help; cat >> "$GITHUB_OUTPUT")',
                        'sqlite3 -cmd -lookaside: nilai -cmd syntax error di prepare, alat mati tanpa membaca: gerbang lolos (Audit 2653)', True)

    skenario_substitusi(tmp, 'sub1740',
                        '> >(rg --glob x pola; cat >> "$GITHUB_OUTPUT")',
                        'rg --glob x pola: nilai --glob tertelan pembawa khas rg, pola terisi, alat menguras: gerbang gagal (Audit 2654)', False)

    skenario_substitusi(tmp, 'sub1741',
                        '> >(rg -j 2 pola; cat >> "$GITHUB_OUTPUT")',
                        'rg -j 2 pola: nilai -j tertelan, alat menguras: gerbang gagal (Audit 2654)', False)

    skenario_substitusi(tmp, 'sub1742',
                        '> >(grep -; cat >> "$GITHUB_OUTPUT")',
                        'grep -: strip adalah pola grep, alat menguras stdin: gerbang gagal (Audit 2655)', False)

    skenario_substitusi(tmp, 'sub1743',
                        '> >(fmt -10; cat >> "$GITHUB_OUTPUT")',
                        'fmt -10: penerima angka warisan sah, alat menguras: gerbang gagal (Audit 2656)', False)

    skenario_substitusi(tmp, 'sub1744',
                        '> >(sqlite3 -maxsize 100 :memory:; cat >> "$GITHUB_OUTPUT")',
                        'sqlite3 -maxsize 100 :memory:: nilai opsi bukan operand, SQL dari stdin jalan, alat menguras: gerbang gagal (Audit 2657)', False)

    skenario_substitusi(tmp, 'sub1745',
                        '> >(sqlite3 -lookaside 100 8 :memory:; cat >> "$GITHUB_OUTPUT")',
                        'sqlite3 -lookaside 100 8 :memory:: dua nilai tertelan, SQL dari stdin jalan, alat menguras: gerbang gagal (Audit 2658)', False)

    skenario_substitusi(tmp, 'sub1746',
                        '> >(od -w10; cat >> "$GITHUB_OUTPUT")',
                        'od -w10: lebar nempel sah, alat menguras: gerbang gagal (Audit 2659)', False)

    skenario_substitusi(tmp, 'sub1747',
                        '> >(tr --del a; cat >> "$GITHUB_OUTPUT")',
                        'tr --del a: singkatan unik --delete, alat menguras: gerbang gagal (Audit 2660)', False)

    skenario_substitusi(tmp, 'sub1748',
                        '> >(awk -x; cat >> "$GITHUB_OUTPUT")',
                        'awk -x: opsi asing membunuh mawk sebelum membaca, alat mati: gerbang lolos (Audit 2661)', True)

    skenario_substitusi(tmp, 'sub1749',
                        '> >(tr --bogus a b; cat >> "$GITHUB_OUTPUT")',
                        'tr --bogus a b: opsi asing membunuh tr sebelum membaca, alat mati: gerbang lolos (Audit 2662)', True)

    skenario_substitusi(tmp, 'sub1750',
                        '> >(sqlite3 --cmd "-lookaside" --help; cat >> "$GITHUB_OUTPUT")',
                        'sqlite3 --cmd bentuk panjang: nilai berawalan strip mati di prepare, alat mati: gerbang lolos (Audit 2663)', True)

    skenario_substitusi(tmp, 'sub1751',
                        '> >(sqlite3 -excel :memory:; cat >> "$GITHUB_OUTPUT")',
                        'sqlite3 -excel: opsi tak dikenal biner 3.45.1, alat mati: gerbang lolos (Audit 2664)', True)

    skenario_substitusi(tmp, 'sub1752',
                        '> >(dd foo=bar; cat >> "$GITHUB_OUTPUT")',
                        'dd foo=bar: kunci operand asing membunuh dd sebelum membaca, alat mati: gerbang lolos (Audit 2665)', True)

    skenario_substitusi(tmp, 'sub1753',
                        '> >(fmt -w abc; cat >> "$GITHUB_OUTPUT")',
                        'fmt -w abc: nilai lebar bukan angka membunuh fmt sebelum membaca, alat mati: gerbang lolos (Audit 2666)', True)

    skenario_substitusi(tmp, 'sub1754',
                        '> >(rg -t rust pola; cat >> "$GITHUB_OUTPUT")',
                        'rg -t rust pola: -t pembawa tipe tertelan, pola terisi, alat menguras: gerbang gagal (Audit 2669)', False)

    skenario_substitusi(tmp, 'sub1755',
                        '> >(rg --generate man; cat >> "$GITHUB_OUTPUT")',
                        'rg --generate man: mode cetak informasional, alat tak membaca: gerbang lolos (Audit 2669)', True)

    skenario_substitusi(tmp, 'sub1756',
                        '> >(xargs -a /dev/null cat; cat >> "$GITHUB_OUTPUT")',
                        'xargs -a /dev/null cat: anak mewarisi stdin bersama, berkas argumen kosong membuat cat membaca stdin: gerbang gagal (Audit 2670)', False)

    skenario_substitusi(tmp, 'sub1757',
                        '> >(xargs -a /dev/null echo; cat >> "$GITHUB_OUTPUT")',
                        'xargs -a /dev/null echo: anak bukan pembaca stdin dan xargs sendiri tak menguras dari berkas: gerbang lolos (Audit 2670)', True)

    skenario_substitusi(tmp, 'sub1758',
                        '> >(linux32; cat >> "$GITHUB_OUTPUT")',
                        'linux32 tanpa program: menjalankan shell pembaca perintah dari stdin: gerbang gagal (Audit 2671)', False)

    skenario_substitusi(tmp, 'sub1759',
                        '> >(sqlite3 -maxwidth 5 :memory:; cat >> "$GITHUB_OUTPUT")',
                        'sqlite3 -maxwidth 5: opsi hantu ditolak biner, alat mati sebelum membaca: gerbang lolos (Audit 2672)', True)

    skenario_substitusi(tmp, 'sub1760',
                        '> >(sqlite3 -unsafe-testing :memory:; cat >> "$GITHUB_OUTPUT")',
                        'sqlite3 -unsafe-testing: opsi asli 3.45.1 mengeksekusi SQL terpipa, alat menguras: gerbang gagal (Audit 2672)', False)

    skenario_substitusi(tmp, 'sub1761',
                        '> >(dd bs=1KiB; cat >> "$GITHUB_OUTPUT")',
                        'dd bs=1KiB: sufiks KiB sah di coreutils, dd menyalin dari stdin: gerbang gagal (Audit 2673)', False)

    skenario_substitusi(tmp, 'sub1762',
                        '> >(split -b 1kB; cat >> "$GITHUB_OUTPUT")',
                        'split -b 1kB: sufiks kB sah di coreutils, split memotong stdin: gerbang gagal (Audit 2673)', False)

    skenario_substitusi(tmp, 'sub1763',
                        '> >(iconv -f UTF-8//IGNORE; cat >> "$GITHUB_OUTPUT")',
                        'iconv -f UTF-8//IGNORE: sufiks //IGNORE sah pada charset sah, iconv mengonversi stdin: gerbang gagal (Audit 2674)', False)

    skenario_substitusi(tmp, 'sub1764',
                        '> >(awk -- -x; cat >> "$GITHUB_OUTPUT")',
                        'awk -- -x: sesudah -- token strip adalah teks program yang sah, mawk menguras stdin: gerbang gagal (Audit 2675)', False)

    skenario_substitusi(tmp, 'sub1765',
                        '> >(dd bs=0; cat >> "$GITHUB_OUTPUT")',
                        'dd bs=0: ukuran blok nol ditolak biner sebagai invalid number, alat mati: gerbang lolos (Audit 2676)', True)

    skenario_substitusi(tmp, 'sub1766',
                        '> >(od -w0; cat >> "$GITHUB_OUTPUT")',
                        'od -w0: lebar nol membuat od tewas sebelum membaca, alat mati: gerbang lolos (Audit 2677)', True)

    skenario_substitusi(tmp, 'sub1767',
                        '> >(od -t zzz; cat >> "$GITHUB_OUTPUT")',
                        'od -t zzz: aksara tipe asing membunuh od saat urai, alat mati: gerbang lolos (Audit 2678)', True)

    skenario_substitusi(tmp, 'sub1768',
                        '> >(strings -e z; cat >> "$GITHUB_OUTPUT")',
                        'strings -e z: aksara encoding asing membunuh strings saat urai, alat mati: gerbang lolos (Audit 2678)', True)

    skenario_substitusi(tmp, 'sub1769',
                        '> >(split --suffix-length abc; cat >> "$GITHUB_OUTPUT")',
                        'split --suffix-length abc: nilai bukan angka membunuh split saat urai, alat mati: gerbang lolos (Audit 2679)', True)

    skenario_substitusi(tmp, 'sub1770',
                        '> >(stdbuf cat; cat >> "$GITHUB_OUTPUT")',
                        'stdbuf cat: tanpa opsi penyangga stdbuf mati rc=125 sebelum cat berjalan, alat mati: gerbang lolos (Audit 2680)', True)

    skenario_substitusi(tmp, 'sub1771',
                        '> >(timeout abc cat; cat >> "$GITHUB_OUTPUT")',
                        'timeout abc cat: durasi sampah membunuh timeout saat urai, alat mati: gerbang lolos (Audit 2681)', True)

    skenario_substitusi(tmp, 'sub1772',
                        '> >(nice --adjustment=abc cat; cat >> "$GITHUB_OUTPUT")',
                        'nice --adjustment=abc cat: nilai penyesuaian sampah membunuh nice saat urai, alat mati: gerbang lolos (Audit 2681)', True)

    skenario_substitusi(tmp, 'sub1773',
                        '> >(chroot; cat >> "$GITHUB_OUTPUT")',
                        'chroot telanjang: tanpa operand direktori chroot mati missing operand rc=125, alat mati: gerbang lolos (Audit 2682)', True)

    skenario_substitusi(tmp, 'sub1774',
                        '> >(chroot /; cat >> "$GITHUB_OUTPUT")',
                        'chroot / tanpa perintah: shell interaktif membaca stdin: gerbang gagal (Audit 2682)', False)

    skenario_substitusi(tmp, 'sub1775',
                        '> >(rg --sort bogus pola; cat >> "$GITHUB_OUTPUT")',
                        'item rg --sort bogus: nilai enum asing membunuh rg, gerbang lolos (Audit 2687)', True)

    skenario_substitusi(tmp, 'sub1776',
                        '> >(grep --binary-files=bogus pola; cat >> "$GITHUB_OUTPUT")',
                        'item grep --binary-files=bogus: nilai enum asing membunuh grep, gerbang lolos (Audit 2687)', True)

    skenario_substitusi(tmp, 'sub1777',
                        '> >(rg -t bogus pola; cat >> "$GITHUB_OUTPUT")',
                        'item rg -t bogus: nama tipe asing membunuh rg, gerbang lolos (Audit 2688)', True)

    skenario_substitusi(tmp, 'sub1778',
                        '> >(rg -r --generate pola; cat >> "$GITHUB_OUTPUT")',
                        'item rg -r --generate pola: --generate berposisi nilai -r, rg menguras, gerbang menolak (Audit 2689)', False)

    skenario_substitusi(tmp, 'sub1779',
                        '> >(timeout 5. cat; cat >> "$GITHUB_OUTPUT")',
                        'item timeout 5. cat: durasi titik-akhir diterima strtod, timeout menguras, gerbang menolak (Audit 2690)', False)

    skenario_substitusi(tmp, 'sub1780',
                        '> >(timeout -s BOGUS 5 cat; cat >> "$GITHUB_OUTPUT")',
                        'item timeout -s BOGUS: nama sinyal asing membunuh timeout, gerbang lolos (Audit 2691)', True)

    skenario_substitusi(tmp, 'sub1781',
                        '> >(taskset ff,ff cat; cat >> "$GITHUB_OUTPUT")',
                        'item taskset ff,ff: masker grup koma diterima biner, taskset menguras, gerbang menolak (Audit 2692)', False)

    skenario_substitusi(tmp, 'sub1782',
                        '> >(nice --adj=abc cat; cat >> "$GITHUB_OUTPUT")',
                        'item nice --adj=abc: singkatan getopt_long lolos validasi 2681, gerbang lolos (Audit 2693)', True)

    skenario_substitusi(tmp, 'sub1783',
                        '> >(ionice -u abc cat; cat >> "$GITHUB_OUTPUT")',
                        'item ionice -u abc: UID bukan angka membunuh ionice, gerbang lolos (Audit 2694)', True)

    skenario_substitusi(tmp, 'sub1784',
                        '> >(stdbuf -o abc cat; cat >> "$GITHUB_OUTPUT")',
                        'item stdbuf -o abc: mode sampah membunuh stdbuf, gerbang lolos (Audit 2695)', True)

    skenario_substitusi(tmp, 'sub1785',
                        '> >(split -b 1m; cat >> "$GITHUB_OUTPUT")',
                        'item split -b 1m: sufiks m kecil diterima xstrtol, split menguras, gerbang menolak (Audit 2696)', False)

    skenario_substitusi(tmp, 'sub1786',
                        '> >(od --width=1K; cat >> "$GITHUB_OUTPUT")',
                        'item od --width=1K: sufiks membunuh parser lebar od, gerbang lolos (Audit 2697)', True)

    skenario_substitusi(tmp, 'sub1787',
                        '> >(iconv -f //IGNORE; cat >> "$GITHUB_OUTPUT")',
                        'item iconv -f //IGNORE: dasar kosong sah di glibc, iconv menguras, gerbang menolak (Audit 2698)', False)

    skenario_substitusi(tmp, 'sub1788',
                        '> >(unshare --help; cat >> "$GITHUB_OUTPUT")',
                        'item unshare --help: informasional tertelan vonis 1121, gerbang lolos (Audit 2699)', True)

    skenario_substitusi(tmp, 'sub1789',
                        '> >(su --help; cat >> "$GITHUB_OUTPUT")',
                        'item su --help: informasional tertelan blok 1107, gerbang lolos (Audit 2700)', True)

    skenario_substitusi(tmp, 'sub1790',
                        '> >(sudo -s --help; cat >> "$GITHUB_OUTPUT")',
                        'item sudo -s --help: --help menang atas mode shell, gerbang lolos (Audit 2701)', True)

    skenario_substitusi(tmp, 'sub1791',
                        '> >(xargs --help; cat >> "$GITHUB_OUTPUT")',
                        'item xargs --help: informasional tertelan xargs_sendiri, gerbang lolos (Audit 2702)', True)

    skenario_substitusi(tmp, 'sub1792',
                        '> >(runuser; cat >> "$GITHUB_OUTPUT")',
                        'item runuser telanjang: shell login root membaca stdin, gerbang menolak (Audit 2703)', False)

    skenario_substitusi(tmp, 'sub1793',
                        '> >(rg -e --type-add -; cat >> "$GITHUB_OUTPUT")',
                        'item rg -e --type-add -: --type-add nilai pembawa bukan definisi, gerbang menolak (Audit 2708)', False)

    skenario_substitusi(tmp, 'sub1794',
                        '> >(rg --type-add b:include:a --type-add a:*.a -t b pola; cat >> "$GITHUB_OUTPUT")',
                        'item rg include ke definisi sesudahnya: rg mati urutan, gerbang lolos (Audit 2709)', True)

    skenario_substitusi(tmp, 'sub1795',
                        '> >(unshare -h; cat >> "$GITHUB_OUTPUT")',
                        'item unshare -h: bantuan pendek informasional, gerbang lolos (Audit 2710)', True)

    skenario_substitusi(tmp, 'sub1796',
                        '> >(su --hel; cat >> "$GITHUB_OUTPUT")',
                        'item su --hel: singkatan bantuan informasional, gerbang lolos (Audit 2711)', True)

    skenario_substitusi(tmp, 'sub1797',
                        '> >(timeout --v 5 cat; cat >> "$GITHUB_OUTPUT")',
                        'item timeout --v 5 cat: singkatan ambigu membunuh timeout, gerbang lolos (Audit 2712)', True)

    skenario_substitusi(tmp, 'sub1798',
                        '> >(sudo --hel cat; cat >> "$GITHUB_OUTPUT")',
                        'item sudo --hel cat: sudo mati opsi tak dikenal, gerbang lolos (Audit 2713)', True)

    skenario_substitusi(tmp, 'sub1799',
                        '> >(runuser -g root -u root; cat >> "$GITHUB_OUTPUT")',
                        'item runuser -g root -u root: runuser mati tanpa perintah, gerbang lolos (Audit 2714)', True)

    skenario_substitusi(tmp, 'sub1800',
                        '> >(runuser root; cat >> "$GITHUB_OUTPUT")',
                        'item runuser root: shell login pengguna menguras, gerbang menolak (Audit 2715)', False)

    skenario_substitusi(tmp, 'sub1801',
                        '> >(split - hasil; cat >> "$GITHUB_OUTPUT")',
                        'item split - hasil: operand - adalah stdin split, gerbang menolak (Audit 2716)', False)

    skenario_substitusi(tmp, 'sub1802',
                        '> >(read baris; cat >> "$GITHUB_OUTPUT")',
                        'item read baris: builtin read menelan baris bersama, gerbang menolak (Audit 2717)', False)

    skenario_substitusi(tmp, 'sub1803',
                        '> >(sg; cat >> "$GITHUB_OUTPUT")',
                        'item sg telanjang: sg mati grup wajib, gerbang lolos (Audit 2718)', True)

    skenario_substitusi(tmp, 'sub1804',
                        '> >(flock -w abc /tmp/x cat; cat >> "$GITHUB_OUTPUT")',
                        'item flock -w abc: flock mati nilai waktu, gerbang lolos (Audit 2719)', True)

    skenario_substitusi(tmp, 'sub1805',
                        '> >(watch -n abc cat; cat >> "$GITHUB_OUTPUT")',
                        'item watch -n abc cat: watch mati nilai interval, gerbang lolos (Audit 2720)', True)

    skenario_substitusi(tmp, 'sub1806',
                        '> >(choom -n abc cat; cat >> "$GITHUB_OUTPUT")',
                        'item choom -n abc cat: choom mati nilai penyesuaian, gerbang lolos (Audit 2721)', True)

    skenario_substitusi(tmp, 'sub1807',
                        '> >(uclampset -m abc cat; cat >> "$GITHUB_OUTPUT")',
                        'item uclampset -m abc cat: uclampset mati nilai util, gerbang lolos (Audit 2722)', True)

    skenario_substitusi(tmp, 'sub1808',
                        '> >(prlimit --cpu=abc cat; cat >> "$GITHUB_OUTPUT")',
                        'item prlimit --cpu=abc cat: prlimit mati nilai batas, gerbang lolos (Audit 2723)', True)

    skenario_substitusi(tmp, 'sub1809',
                        '> >(strace -s abc cat; cat >> "$GITHUB_OUTPUT")',
                        'item strace -s abc cat: strace mati nilai batas string, gerbang lolos (Audit 2724)', True)

    skenario_substitusi(tmp, 'sub1810',
                        '> >(xargs -n abc cat; cat >> "$GITHUB_OUTPUT")',
                        'item xargs -n abc cat: xargs mati nilai numerik, gerbang lolos (Audit 2725)', True)

    skenario_substitusi(tmp, 'sub1811',
                        '> >(taskset -c xyz cat; cat >> "$GITHUB_OUTPUT")',
                        'item taskset -c xyz cat: taskset mati daftar CPU, gerbang lolos (Audit 2726)', True)

    skenario_substitusi(tmp, 'sub1812',
                        '> >(cut -f abc; cat >> "$GITHUB_OUTPUT")',
                        'item cut -f abc: cut mati nilai daftar, gerbang lolos (Audit 2727)', True)

    skenario_substitusi(tmp, 'sub1813',
                        '> >(sort -k abc; cat >> "$GITHUB_OUTPUT")',
                        'item sort -k abc: sort mati spesifikasi kunci, gerbang lolos (Audit 2728)', True)

    skenario_substitusi(tmp, 'sub1814',
                        '> >(runuser --session-command cat root; cat >> "$GITHUB_OUTPUT")',
                        'item runuser --session-command cat root: nilai perintah kini ditelan, gerbang menolak (Audit 2731)', False)

    skenario_substitusi(tmp, 'sub1815',
                        '> >(sort -k +1; cat >> "$GITHUB_OUTPUT")',
                        'item sort -k +1: bentuk usang +POS kini sah, gerbang menolak (Audit 2732)', False)

    skenario_substitusi(tmp, 'sub1816',
                        '> >(uclampset -m +5 cat; cat >> "$GITHUB_OUTPUT")',
                        'item uclampset -m +5 cat: nilai bertanda + kini sah, gerbang menolak (Audit 2733)', False)

    skenario_substitusi(tmp, 'sub1817',
                        '> >(flock -E +5 berkas cat; cat >> "$GITHUB_OUTPUT")',
                        'item flock -E +5 berkas cat: kode keluar bertanda + kini sah, gerbang menolak (Audit 2734)', False)

    skenario_substitusi(tmp, 'sub1818',
                        '> >(xargs -eh cat; cat >> "$GITHUB_OUTPUT")',
                        'item xargs -eh cat: huruf nilai -e tak lagi divonis bantuan, gerbang menolak (Audit 2735)', False)

    skenario_substitusi(tmp, 'sub1819',
                        '> >(runuser -c; cat >> "$GITHUB_OUTPUT")',
                        'item runuser -c: opsi menggantung mati urai, gerbang lolos (Audit 2736)', True)

    skenario_substitusi(tmp, 'sub1820',
                        '> >(read -t 0; cat >> "$GITHUB_OUTPUT")',
                        'item read -t 0: read nol byte tak menguras, gerbang lolos (Audit 2737)', True)

    skenario_substitusi(tmp, 'sub1821',
                        '> >(taskset -c 0-3:0 cat; cat >> "$GITHUB_OUTPUT")',
                        'item taskset -c 0-3:0 cat: stride 0 mati di biner, gerbang lolos (Audit 2738)', True)

    skenario_substitusi(tmp, 'sub1822',
                        '> >(prlimit --cpu=:5 cat; cat >> "$GITHUB_OUTPUT")',
                        'item prlimit --cpu=:5 cat: sisi lunak kosong mati di biner, gerbang lolos (Audit 2739)', True)

    skenario_substitusi(tmp, 'sub1823',
                        '> >(uniq -f abc; cat >> "$GITHUB_OUTPUT")',
                        'item uniq -f abc: uniq mati nilai angka, gerbang lolos (Audit 2740)', True)

    skenario_substitusi(tmp, 'sub1824',
                        '> >(sort --parallel=abc; cat >> "$GITHUB_OUTPUT")',
                        'item sort --parallel=abc: sort mati nilai, gerbang lolos (Audit 2741)', True)

    skenario_substitusi(tmp, 'sub1825',
                        '> >(tail -s abc; cat >> "$GITHUB_OUTPUT")',
                        'item tail -s abc: tail mati nilai, gerbang lolos (Audit 2742)', True)

    skenario_substitusi(tmp, 'sub1826',
                        '> >(wc --total=abc; cat >> "$GITHUB_OUTPUT")',
                        'item wc --total=abc: wc mati nilai enum, gerbang lolos (Audit 2743)', True)

    skenario_substitusi(tmp, 'sub1827',
                        '> >(od -A q; cat >> "$GITHUB_OUTPUT")',
                        'item od -A q: od mati nilai radix, gerbang lolos (Audit 2744)', True)

    skenario_substitusi(tmp, 'sub1828',
                        '> >(strings --unicode=abc; cat >> "$GITHUB_OUTPUT")',
                        'item strings --unicode=abc: strings mati nilai, gerbang lolos (Audit 2745)', True)

    skenario_substitusi(tmp, 'sub1829',
                        '> >(b2sum -l 8; cat >> "$GITHUB_OUTPUT")',
                        'item b2sum -l 8: b2sum menguras lewat -l sah, gerbang menolak (Audit 2746)', False)

    skenario_substitusi(tmp, 'sub1830',
                        '> >(numfmt --padding=abc; cat >> "$GITHUB_OUTPUT")',
                        'item numfmt --padding=abc: numfmt mati nilai, gerbang lolos (Audit 2747)', True)

    skenario_substitusi(tmp, 'sub1831',
                        '> >(nl --number-width=abc; cat >> "$GITHUB_OUTPUT")',
                        'item nl --number-width=abc: nl mati nilai, gerbang lolos (Audit 2748)', True)

    skenario_substitusi(tmp, 'sub1832',
                        '> >(pr --width=abc; cat >> "$GITHUB_OUTPUT")',
                        'item pr --width=abc: pr mati nilai, gerbang lolos (Audit 2749)', True)

    skenario_substitusi(tmp, 'sub1833',
                        '> >(ptx --width=abc; cat >> "$GITHUB_OUTPUT")',
                        'item ptx --width=abc: ptx mati nilai, gerbang lolos (Audit 2750)', True)

    skenario_substitusi(tmp, 'sub1834',
                        '> >(expand --tabs=abc; cat >> "$GITHUB_OUTPUT")',
                        'item expand --tabs=abc: expand mati nilai, gerbang lolos (Audit 2751)', True)

    skenario_substitusi(tmp, 'sub1835',
                        '> >(xz --threads=abc; cat >> "$GITHUB_OUTPUT")',
                        'item xz --threads=abc: xz mati nilai, gerbang lolos (Audit 2752)', True)

    skenario_substitusi(tmp, 'sub1836',
                        '> >(chpasswd -c BOGUS99; cat >> "$GITHUB_OUTPUT")',
                        'item chpasswd -c BOGUS99: chpasswd mati metode, gerbang lolos (Audit 2753)', True)

    skenario_substitusi(tmp, 'sub1837',
                        '> >(uconv -f bogus99; cat >> "$GITHUB_OUTPUT")',
                        'item uconv -f bogus99: uconv mati charset, gerbang lolos (Audit 2754)', True)

    skenario_substitusi(tmp, 'sub1838',
                        '> >(piconv -f bogus99 -t UTF-8; cat >> "$GITHUB_OUTPUT")',
                        'item piconv -f bogus99: piconv mati encoding, gerbang lolos (Audit 2755)', True)

    skenario_substitusi(tmp, 'sub1839',
                        '> >(shasum -a abc; cat >> "$GITHUB_OUTPUT")',
                        'item shasum -a abc: shasum mati algoritma, gerbang lolos (Audit 2756)', True)

    skenario_substitusi(tmp, 'sub1840',
                        '> >(logger -p abc; cat >> "$GITHUB_OUTPUT")',
                        'item logger -p abc: logger mati prioritas, gerbang lolos (Audit 2757)', True)

    skenario_substitusi(tmp, 'sub1841',
                        '> >(pod2text --width=abc; cat >> "$GITHUB_OUTPUT")',
                        'item pod2text --width=abc: pod2text mati lebar, gerbang lolos (Audit 2758)', True)

    skenario_substitusi(tmp, 'sub1842',
                        '> >(grep -d xyz dua; cat >> "$GITHUB_OUTPUT")',
                        'item grep -d xyz dua: grep mati nilai enum, gerbang lolos (Audit 2759)', True)

    skenario_substitusi(tmp, 'sub1843',
                        '> >(env --block-signal=abc cat; cat >> "$GITHUB_OUTPUT")',
                        'item env --block-signal=abc cat: env mati sinyal, gerbang lolos (Audit 2760)', True)

    skenario_substitusi(tmp, 'sub1844',
                        '> >(ssh-agent -t abc cat; cat >> "$GITHUB_OUTPUT")',
                        'item ssh-agent -t abc cat: ssh-agent mati masa hidup, gerbang lolos (Audit 2761)', True)

    skenario_substitusi(tmp, 'sub1845',
                        '> >(systemd-cat -p abc cat; cat >> "$GITHUB_OUTPUT")',
                        'item systemd-cat -p abc cat: systemd-cat mati prioritas, gerbang lolos (Audit 2762)', True)

    skenario_substitusi(tmp, 'sub1846',
                        '> >(unshare --setuid=abc cat; cat >> "$GITHUB_OUTPUT")',
                        'item unshare --setuid=abc cat: unshare mati uid, gerbang lolos (Audit 2763)', True)

    skenario_substitusi(tmp, 'sub1847',
                        '> >(setpriv --securebits=+abc cat; cat >> "$GITHUB_OUTPUT")',
                        'item setpriv --securebits=+abc cat: setpriv mati nama, gerbang lolos (Audit 2764)', True)

    skenario_substitusi(tmp, 'sub1848',
                        '> >(join -1 abc - /dev/null; cat >> "$GITHUB_OUTPUT")',
                        'item join -1 abc: join mati nomor bidang, gerbang lolos (Audit 2765)', True)

    skenario_substitusi(tmp, 'sub1849',
                        '> >(csplit - {abc}; cat >> "$GITHUB_OUTPUT")',
                        'item csplit - {abc}: csplit mati pola, gerbang lolos (Audit 2766)', True)

    skenario_substitusi(tmp, 'sub1850',
                        '> >(cmp -n abc - /dev/null; cat >> "$GITHUB_OUTPUT")',
                        'item cmp -n abc: cmp mati nilai byte, gerbang lolos (Audit 2767)', True)

    skenario_substitusi(tmp, 'sub1851',
                        '> >(diff --width abc - /dev/null; cat >> "$GITHUB_OUTPUT")',
                        'item diff --width abc: diff mati lebar, gerbang lolos (Audit 2768)', True)

    skenario_substitusi(tmp, 'sub1852',
                        '> >(du --files0-from=- --max-depth=abc; cat >> "$GITHUB_OUTPUT")',
                        'item du --max-depth=abc: du mati kedalaman, gerbang lolos (Audit 2769)', True)

    skenario_substitusi(tmp, 'sub1853',
                        '> >(tar -tf - -b abc; cat >> "$GITHUB_OUTPUT")',
                        'item tar -b abc: tar mati faktor blok, gerbang lolos (Audit 2770)', True)

    skenario_substitusi(tmp, 'sub1854',
                        '> >(ffmpeg -t abc -i - -f null -; cat >> "$GITHUB_OUTPUT")',
                        'item ffmpeg -t abc: ffmpeg mati durasi, gerbang lolos (Audit 2771)', True)

    skenario_substitusi(tmp, 'sub1855',
                        '> >(ssh -p abc host; cat >> "$GITHUB_OUTPUT")',
                        'item ssh -p abc: ssh mati port, gerbang lolos (Audit 2772)', True)

    skenario_substitusi(tmp, 'sub1856',
                        '> >(make -jabc -f -; cat >> "$GITHUB_OUTPUT")',
                        'item make -jabc: make mati hitungan job, gerbang lolos (Audit 2773)', True)

    skenario_substitusi(tmp, 'sub1857',
                        '> >(tree --fromfile - -L abc; cat >> "$GITHUB_OUTPUT")',
                        'item tree -L abc: tree mati level, gerbang lolos (Audit 2774)', True)

    skenario_substitusi(tmp, 'sub1858',
                        '> >(curl --max-time abc -d @- https://x.invalid; cat >> "$GITHUB_OUTPUT")',
                        'item curl --max-time abc: curl mati batas waktu, gerbang lolos (Audit 2775)', True)

    skenario_substitusi(tmp, 'sub1859',
                        '> >(flock -E ++5 /tmp/x cat; cat >> "$GITHUB_OUTPUT")',
                        'item flock -E ++5: kode keluar asing membunuh flock, gerbang lolos tanpa crash (Audit 2780)', True)

    skenario_substitusi(tmp, 'sub1860',
                        '> >(ssh host echo -p abc; cat >> "$GITHUB_OUTPUT")',
                        'item ssh host echo -p abc: -p sesudah tujuan kata perintah jauh, gerbang menolak (Audit 2781)', False)

    skenario_substitusi(tmp, 'sub1861',
                        '> >(tree -P -L --fromfile .; cat >> "$GITHUB_OUTPUT")',
                        'item tree -P -L --fromfile .: -L nilai pola -P bukan level, gerbang menolak (Audit 2782)', False)

    skenario_substitusi(tmp, 'sub1862',
                        '> >(diff --label --width - /dev/null; cat >> "$GITHUB_OUTPUT")',
                        'item diff --label --width -: --width nilai --label bukan opsi lebar, gerbang menolak (Audit 2783)', False)

    skenario_substitusi(tmp, 'sub1863',
                        '> >(tar -fb0 -T - -c; cat >> "$GITHUB_OUTPUT")',
                        'item tar -fb0 -T -: b ekor nilai -f bukan faktor blok, gerbang menolak (Audit 2784)', False)

    skenario_substitusi(tmp, 'sub1864',
                        '> >(tar --record-size=10k -tf -; cat >> "$GITHUB_OUTPUT")',
                        'item tar --record-size=10k: sufiks k kecil kini sah, gerbang menolak (Audit 2785)', False)

    skenario_substitusi(tmp, 'sub1865',
                        '> >(cmp -n +5 - /dev/null; cat >> "$GITHUB_OUTPUT")',
                        'item cmp -n +5: byte bertanda + kini sah, gerbang menolak (Audit 2786)', False)

    skenario_substitusi(tmp, 'sub1866',
                        '> >(csplit - 2 {1} {2}; cat >> "$GITHUB_OUTPUT")',
                        'item csplit {1} {2}: pengulangan berurutan membunuh csplit, gerbang lolos (Audit 2787)', True)

    skenario_substitusi(tmp, 'sub1867',
                        '> >(env --block-signal= cat; cat >> "$GITHUB_OUTPUT")',
                        'item env --block-signal= cat: daftar sinyal kosong sah, gerbang menolak (Audit 2788)', False)

    skenario_substitusi(tmp, 'sub1868',
                        '> >(tail -s 1e2; cat >> "$GITHUB_OUTPUT")',
                        'item tail -s 1e2: jeda eksponen kini sah, gerbang menolak (Audit 2789)', False)

    skenario_substitusi(tmp, 'sub1869',
                        '> >(logger -p USER.ERR; cat >> "$GITHUB_OUTPUT")',
                        'item logger -p USER.ERR: prioritas huruf besar kini sah, gerbang menolak (Audit 2790)', False)

    skenario_substitusi(tmp, 'sub1870',
                        '> >(setpriv --bounding-set=+landlock cat; cat >> "$GITHUB_OUTPUT")',
                        'item setpriv +landlock: bukan capability, setpriv mati, gerbang lolos (Audit 2791)', True)

    skenario_substitusi(tmp, 'sub1871',
                        '> >(join -e -1 - /tmp/j2; cat >> "$GITHUB_OUTPUT")',
                        'item join -e -1: -1 nilai -e bukan opsi bidang, gerbang menolak (Audit 2792)', False)

    skenario_substitusi(tmp, 'sub1872',
                        '> >(ffmpeg -t 100:00 -i - -f null -; cat >> "$GITHUB_OUTPUT")',
                        'item ffmpeg -t 100:00: durasi dua ruas komponen pertama >= 60 membunuh ffmpeg, gerbang lolos (Audit 2793)', True)

    skenario_substitusi(tmp, 'sub1873',
                        '> >(sort --batch-size=+3 -; cat >> "$GITHUB_OUTPUT")',
                        'item sort --batch-size=+3: hitungan bertanda + kini sah, gerbang menolak (Audit 2794)', False)

    skenario_substitusi(tmp, 'sub1874',
                        '> >(xz --threa=abc -; cat >> "$GITHUB_OUTPUT")',
                        'item xz --threa=abc: singkatan opsi panjang kini divalidasi, gerbang lolos (Audit 2795)', True)

    skenario_substitusi(tmp, 'sub1875',
                        '> >(xz --memlimit=5k -; cat >> "$GITHUB_OUTPUT")',
                        'item xz --memlimit=5k: sufiks k kecil kini sah, gerbang menolak (Audit 2796)', False)

    skenario_substitusi(tmp, 'sub1876',
                        '> >(python3 --check-hash-based-pycs xyz; cat >> "$GITHUB_OUTPUT")',
                        'item python --check-hash-based-pycs xyz: nilai asing membunuh python, gerbang lolos (Audit 2797)', True)

    skenario_substitusi(tmp, 'sub1877',
                        '> >(python3 -X utf8=2; cat >> "$GITHUB_OUTPUT")',
                        'item python -X utf8=2: nilai -X per-kunci kini divalidasi, gerbang lolos (Audit 2798)', True)

    skenario_substitusi(tmp, 'sub1878',
                        '> >(node --input-type xyz; cat >> "$GITHUB_OUTPUT")',
                        'item node --input-type xyz: nilai di luar enum membunuh node, gerbang lolos (Audit 2799)', True)

    skenario_substitusi(tmp, 'sub1879',
                        '> >(node --max-old-space-size=abc; cat >> "$GITHUB_OUTPUT")',
                        'item node --max-old-space-size=abc: nilai ukuran V8 non-numerik membunuh node, gerbang lolos (Audit 2800)', True)

    skenario_substitusi(tmp, 'sub1880',
                        '> >(node --experimental-foo; cat >> "$GITHUB_OUTPUT")',
                        'item node --experimental-foo: keluarga awalan node kini keanggotaan tertutup, gerbang lolos (Audit 2801)', True)

    skenario_substitusi(tmp, 'sub1881',
                        '> >(node --use-largepages xyz; cat >> "$GITHUB_OUTPUT")',
                        'item node --use-largepages xyz: nilai di luar {off, on} membunuh node, gerbang lolos (Audit 2802)', True)

    skenario_substitusi(tmp, 'sub1882',
                        '> >(node --stack-size=100; cat >> "$GITHUB_OUTPUT")',
                        'item node --stack-size=100: opsi V8 nyata yang tak dikenal gerbang kini menguras, gerbang menolak (Audit 2803)', False)

    skenario_substitusi(tmp, 'sub1883',
                        '> >(awk -v foo {print}; cat >> "$GITHUB_OUTPUT")',
                        'item awk -v foo: nilai -v tanpa penugasan nama=nilai membunuh awk, gerbang lolos (Audit 2804)', True)

    skenario_substitusi(tmp, 'sub1884',
                        '> >(perl -Z -ne print; cat >> "$GITHUB_OUTPUT")',
                        'item perl -Z: saklar tak dikenal membunuh perl, gerbang lolos (Audit 2805)', True)

    skenario_substitusi(tmp, 'sub1885',
                        '> >(perl -CZ -ne print; cat >> "$GITHUB_OUTPUT")',
                        'item perl -CZ: huruf -C di luar himpunan sah membunuh perl, gerbang lolos (Audit 2806)', True)

    skenario_substitusi(tmp, 'sub1886',
                        '> >(sqlite3 -vfs bogus; cat >> "$GITHUB_OUTPUT")',
                        'item sqlite3 -vfs bogus: nama VFS tak terdaftar membunuh sqlite3, gerbang lolos (Audit 2807)', True)

    skenario_substitusi(tmp, 'sub1887',
                        '> >(jq --argjson a bukan .; cat >> "$GITHUB_OUTPUT")',
                        'item jq --argjson bukan-JSON: nilai --argjson tak sah membunuh jq, gerbang lolos (Audit 2808)', True)

    skenario_substitusi(tmp, 'sub1888',
                        '> >(flock -E 99999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999 /tmp/lk cat; cat >> "$GITHUB_OUTPUT")',
                        'flock -E digit raksasa tak lagi crash gerbang', True)

    skenario_substitusi(tmp, 'sub1889',
                        '> >(tree -oL abc 2 --fromfile; cat >> "$GITHUB_OUTPUT")',
                        'tree bundel -oL kini dinilai menguras', False)

    skenario_substitusi(tmp, 'sub1890',
                        '> >(ssh -p +22 host; cat >> "$GITHUB_OUTPUT")',
                        'ssh port bertanda + kini dinilai menguras', False)

    skenario_substitusi(tmp, 'sub1891',
                        '> >(node --no-use-largepages -; cat >> "$GITHUB_OUTPUT")',
                        'node negasi opsi enum kini dinilai mati', True)

    skenario_substitusi(tmp, 'sub1892',
                        '> >(node --expose-gc; cat >> "$GITHUB_OUTPUT")',
                        'node --expose-gc kini dinilai menguras', False)

    skenario_substitusi(tmp, 'sub1893',
                        '> >(diff -Uabc - -; cat >> "$GITHUB_OUTPUT")',
                        'diff -U nilai konteks asing ditolak (Audit 2822)', True)

    skenario_substitusi(tmp, 'sub1894',
                        '> >(tar -cb20 -f - /etc/hostname; cat >> "$GITHUB_OUTPUT")',
                        'bundel tar berdigit dikenali walk utama (Audit 2823)', True)

    skenario_substitusi(tmp, 'sub1895',
                        '> >(timeout infinity cat -; cat >> "$GITHUB_OUTPUT")',
                        'timeout infinity diterima biner, ditolak gerbang (Audit 2824)', False)

    skenario_substitusi(tmp, 'sub1896',
                        '> >(awk -v print=1 \'{print}\' -; cat >> "$GITHUB_OUTPUT")',
                        'awk -v nama terlarang mawk ditolak (Audit 2825)', True)

    skenario_substitusi(tmp, 'sub1897',
                        '> >(perl -V; cat >> "$GITHUB_OUTPUT")',
                        'perl -V informasional tak menguras (Audit 2826)', True)

    skenario_substitusi(tmp, 'sub1898',
                        '> >(sort --out --help; cat >> "$GITHUB_OUTPUT")',
                        'singkatan opsi bernilai menelan --help (Audit 2827)', False)

    skenario_substitusi(tmp, 'sub1899',
                        '> >(curl -o --max-time -d @- http://127.0.0.1/; cat >> "$GITHUB_OUTPUT")',
                        'curl -o menelan --max-time sebagai nilainya (Audit 2828)', False)

    skenario_substitusi(tmp, 'sub1900',
                        '> >(cmp -nabc - -; cat >> "$GITHUB_OUTPUT")',
                        'cmp -n merekat asing ditolak (Audit 2829)', True)

    skenario_substitusi(tmp, 'sub1901',
                        '> >(dd bs=99999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999 if=-; cat >> "$GITHUB_OUTPUT")',
                        'faktor ukuran dd berdigit ribuan tak lagi meledakkan gerbang (Audit 2832)', True)

    skenario_substitusi(tmp, 'sub1902',
                        '> >(join -1 99999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999 - j2.txt; cat >> "$GITHUB_OUTPUT")',
                        'nomor field join berdigit ribuan tak lagi meledakkan gerbang (Audit 2833)', False)

    skenario_substitusi(tmp, 'sub1903',
                        '> >(node --watch=x -; cat >> "$GITHUB_OUTPUT")',
                        'node --watch=<nilai> kini dinilai menguras (Audit 2834)', False)

    skenario_substitusi(tmp, 'sub1904',
                        '> >(node --prof=x -; cat >> "$GITHUB_OUTPUT")',
                        'boolean V8 --prof diberi =nilai kini ditolak gerbang (Audit 2835)', True)

    skenario_substitusi(tmp, 'sub1905',
                        '> >(sftp -b - -P abc host; cat >> "$GITHUB_OUTPUT")',
                        'port sftp tak sah kini ditolak gerbang (Audit 2838)', True)

    skenario_substitusi(tmp, 'sub1906',
                        '> >(node --test -; cat >> "$GITHUB_OUTPUT")',
                        'operand - dalam mode --test node kini tak dinilai stdin (Audit 2839)', True)

    skenario_substitusi(tmp, 'sub1907',
                        '> >(join -0 - -; cat >> "$GITHUB_OUTPUT")',
                        'join -0 kini ditolak gerbang (Audit 2842)', True)

    skenario_substitusi(tmp, 'sub1908',
                        '> >(tree --fromtabfile; cat >> "$GITHUB_OUTPUT")',
                        'tree --fromtabfile kini dinilai menguras (Audit 2843)', False)

    skenario_substitusi(tmp, 'sub1909',
                        '> >(tree --fromfile daftar.txt; cat >> "$GITHUB_OUTPUT")',
                        'operand berkas --fromfile kini tak dinilai stdin (Audit 2844)', True)

    skenario_substitusi(tmp, 'sub1910',
                        '> >(tree --bogus --fromfile; cat >> "$GITHUB_OUTPUT")',
                        'opsi panjang tree tak dikenal kini membunuh model gerbang (Audit 2845)', True)

    skenario_substitusi(tmp, 'sub1911',
                        '> >(tree --hintro --fromfile; cat >> "$GITHUB_OUTPUT")',
                        '--fromfile tertelan --hintro kini tak dinilai mode stdin (Audit 2846)', True)

    skenario_substitusi(tmp, 'sub1912',
                        '> >(diff3 -A - f2 f3; cat >> "$GITHUB_OUTPUT")',
                        'diff3 -A bendera kini tak menelan operand stdin (Audit 2847)', False)

    skenario_substitusi(tmp, 'sub1913',
                        '> >(diff3 --width=10 - f2 f3; cat >> "$GITHUB_OUTPUT")',
                        'opsi khas-diff --width kini ditolak untuk diff3 (Audit 2848)', True)

    skenario_substitusi(tmp, 'sub1914',
                        '> >(diff -W abc - f2; cat >> "$GITHUB_OUTPUT")',
                        'nilai -W diff tak sah kini ditolak gerbang (Audit 2849)', True)

    skenario_substitusi(tmp, 'sub1915',
                        '> >(diff -A 2 - f2; cat >> "$GITHUB_OUTPUT")',
                        'opsi hantu diff -A kini ditolak gerbang (Audit 2850)', True)

    skenario_substitusi(tmp, 'sub1916',
                        '> >(diff3 -L - f2 f3; cat >> "$GITHUB_OUTPUT")',
                        'diff3 -L kini bernilai di model gerbang (Audit 2851)', True)

    skenario_substitusi(tmp, 'sub1917',
                        '> >(curl --data-b @- http://x/; cat >> "$GITHUB_OUTPUT")',
                        'singkatan sumber stdin curl kini diselesaikan walk kedua (Audit 2852)', False)

    skenario_substitusi(tmp, 'sub1918',
                        '> >(curl -sd @- http://x/; cat >> "$GITHUB_OUTPUT")',
                        'bundel pendek curl kini diurai walk kedua (Audit 2853)', False)

    skenario_substitusi(tmp, 'sub1919',
                        '> >(curl --header -d @- http://x/; cat >> "$GITHUB_OUTPUT")',
                        '-d @- sebagai nilai opsi lain kini bukan sumber stdin (Audit 2854)', True)

    skenario_substitusi(tmp, 'sub1920',
                        '> >(curl -- -d @- http://x/; cat >> "$GITHUB_OUTPUT")',
                        'terminator -- menghentikan pindai sumber stdin curl (Audit 2855)', True)

    skenario_substitusi(tmp, 'sub1921',
                        '> >(node --max-lazy=abc -; cat >> "$GITHUB_OUTPUT")',
                        'anggota _MAX_V8 node kini divalidasi nilainya (Audit 2856)', True)

    skenario_substitusi(tmp, 'sub1922',
                        '> >(diff3 --show-a -L lab - f2 f3; cat >> "$GITHUB_OUTPUT")',
                        'diff3 singkatan --show-a kini dinilai menguras (Audit 2858)', False)

    skenario_substitusi(tmp, 'sub1923',
                        '> >(diff3 --lab lab - f2 f3; cat >> "$GITHUB_OUTPUT")',
                        'diff3 singkatan --lab tanpa mode kini ditolak (Audit 2858)', True)

    skenario_substitusi(tmp, 'sub1924',
                        '> >(diff3 --show- -L lab - f2 f3; cat >> "$GITHUB_OUTPUT")',
                        'diff3 singkatan ambigu --show- kini ditolak (Audit 2858)', True)

    skenario_substitusi(tmp, 'sub1925',
                        '> >(node --no-jitless=x -; cat >> "$GITHUB_OUTPUT")',
                        'node negasi boolean V8 bernilai kini ditolak (Audit 2859)', True)

    skenario_substitusi(tmp, 'sub1926',
                        '> >(node --no-trace-deopt=x -; cat >> "$GITHUB_OUTPUT")',
                        'node negasi trace boolean bernilai kini ditolak (Audit 2859)', True)

    skenario_substitusi(tmp, 'sub1927',
                        '> >(join -o auto - j2.txt; cat >> "$GITHUB_OUTPUT")',
                        'join -o auto kini dinilai menguras (Audit 2868)', False)

    skenario_substitusi(tmp, 'sub1928',
                        '> >(join -o auto,1.1 - j2.txt; cat >> "$GITHUB_OUTPUT")',
                        'join -o campuran auto tetap ditolak (Audit 2868)', True)

    skenario_substitusi(tmp, 'sub1929',
                        '> >(node --test -- -; cat >> "$GITHUB_OUTPUT")',
                        'node mode uji sesudah -- kini ditolak (Audit 2860)', True)

    skenario_substitusi(tmp, 'sub1930',
                        '> >(tree --fromfile -Z; cat >> "$GITHUB_OUTPUT")',
                        'tree opsi pendek tak sah kini ditolak di walk fromfile (Audit 2861)', True)

    skenario_substitusi(tmp, 'sub1931',
                        '> >(tree --fromfile --infofile -; cat >> "$GITHUB_OUTPUT")',
                        'tree --infofile - kini ditolak di walk fromfile (Audit 2862)', True)

    skenario_substitusi(tmp, 'sub1932',
                        '> >(node --inspect=80 -; cat >> "$GITHUB_OUTPUT")',
                        'node --inspect port kecil kini ditolak (Audit 2863)', True)

    skenario_substitusi(tmp, 'sub1933',
                        '> >(node --inspect= -; cat >> "$GITHUB_OUTPUT")',
                        'node --inspect kosong kini ditolak (Audit 2863)', True)

    skenario_substitusi(tmp, 'sub1934',
                        '> >(join -j abc - -; cat >> "$GITHUB_OUTPUT")',
                        'join -j nilai asing kini ditolak (Audit 2864)', True)

    skenario_substitusi(tmp, 'sub1935',
                        '> >(join --bogus - -; cat >> "$GITHUB_OUTPUT")',
                        'join opsi panjang tak dikenal kini ditolak (Audit 2865)', True)

    skenario_substitusi(tmp, 'sub1936',
                        '> >(join --separator , - -; cat >> "$GITHUB_OUTPUT")',
                        'join --separator hantu kini ditolak (Audit 2865)', True)

    skenario_substitusi(tmp, 'sub1937',
                        '> >(join --header=x - -; cat >> "$GITHUB_OUTPUT")',
                        'join bendera panjang bernilai = kini ditolak (Audit 2866)', True)

    skenario_substitusi(tmp, 'sub1938',
                        '> >(join - -; cat >> "$GITHUB_OUTPUT")',
                        'join dua operand stdin kini ditolak (Audit 2867)', True)

    skenario_substitusi(tmp, 'sub1939',
                        '> >(diff --label x -; cat >> "$GITHUB_OUTPUT")',
                        'diff label dengan operand kurang kini ditolak (Audit 2869)', True)

    skenario_substitusi(tmp, 'sub1940',
                        '> >(diff3 - f2; cat >> "$GITHUB_OUTPUT")',
                        'diff3 operand kurang kini ditolak (Audit 2869)', True)

    skenario_substitusi(tmp, 'sub1941',
                        '> >(diff --label a --label b --label c - f2; cat >> "$GITHUB_OUTPUT")',
                        'diff label berlebih kini ditolak (Audit 2870)', True)

    skenario_substitusi(tmp, 'sub1942',
                        '> >(diff --bogus - f2; cat >> "$GITHUB_OUTPUT")',
                        'diff opsi panjang tak dikenal kini ditolak (Audit 2871)', True)

    skenario_substitusi(tmp, 'sub1943',
                        '> >(curl --v X -d @- http://a; cat >> "$GITHUB_OUTPUT")',
                        'curl awalan ambigu himpunan penuh kini ditolak (Audit 2872)', True)

    skenario_substitusi(tmp, 'sub1944',
                        '> >(curl --vers -d @- http://a; cat >> "$GITHUB_OUTPUT")',
                        'curl singkatan informatif kini ditolak (Audit 2873)', True)

    skenario_substitusi(tmp, 'sub1945',
                        '> >(curl --fail=x -d @- http://x/; cat >> "$GITHUB_OUTPUT")',
                        'curl bendera panjang diberi =nilai kini ditolak (Audit 2882)', True)

    skenario_substitusi(tmp, 'sub1946',
                        '> >(curl --data=@- http://x/; cat >> "$GITHUB_OUTPUT")',
                        'curl bentuk --opsi=nilai bernilai pun ditolak di curl < 8.16 (Audit 2882)', True)

    skenario_substitusi(tmp, 'sub1947',
                        '> >(tree --sort zzz --fromfile; cat >> "$GITHUB_OUTPUT")',
                        'tree nilai --sort asing kini ditolak (Audit 2883)', True)

    skenario_substitusi(tmp, 'sub1948',
                        '> >(tree --prune=x --fromfile; cat >> "$GITHUB_OUTPUT")',
                        'tree bendera panjang diberi =value kini ditolak (Audit 2884)', True)

    skenario_substitusi(tmp, 'sub1949',
                        '> >(diff --brief=x - /etc/hosts; cat >> "$GITHUB_OUTPUT")',
                        'diff bendera panjang diberi =value kini ditolak (Audit 2885)', True)

    skenario_substitusi(tmp, 'sub1950',
                        '> >(diff3 --merge=x - f2 f3; cat >> "$GITHUB_OUTPUT")',
                        'diff3 bendera panjang diberi =value kini ditolak (Audit 2885)', True)

    skenario_substitusi(tmp, 'sub1951',
                        '> >(curl --retry abc -d @- http://x/; cat >> "$GITHUB_OUTPUT")',
                        'curl nilai numerik --retry asing kini ditolak (Audit 2886)', True)

    skenario_substitusi(tmp, 'sub1952',
                        '> >(curl --limit-rate abc -d @- http://x/; cat >> "$GITHUB_OUTPUT")',
                        'curl nilai --limit-rate asing kini ditolak (Audit 2886)', True)

    skenario_substitusi(tmp, 'sub1953',
                        '> >(diff --color=zzz - /etc/hosts; cat >> "$GITHUB_OUTPUT")',
                        'diff nilai --color asing kini ditolak (Audit 2887)', True)

    skenario_substitusi(tmp, 'sub1954',
                        '> >(diff --horizon-lines=abc - /etc/hosts; cat >> "$GITHUB_OUTPUT")',
                        'diff nilai --horizon-lines asing kini ditolak (Audit 2887)', True)

    skenario_substitusi(tmp, 'sub1955',
                        '> >(sdiff --text=x - /etc/hosts; cat >> "$GITHUB_OUTPUT")',
                        'sdiff bendera panjang diberi =value kini ditolak (Audit 2894)', True)

    skenario_substitusi(tmp, 'sub1956',
                        '> >(curl --proxy http://p:8080 -d @- http://x/; cat >> "$GITHUB_OUTPUT")',
                        'curl --proxy bernilai kini dikenali, -d @- menguras (Audit 2896)', False)

    skenario_substitusi(tmp, 'sub1957',
                        '> >(curl --rate 5/seconds -d @- http://x/; cat >> "$GITHUB_OUTPUT")',
                        'curl --rate satuan awalan huruf kecil sah, -d @- menguras (Audit 2897)', False)

    skenario_substitusi(tmp, 'sub1958',
                        '> >(curl --rate 5/SEC -d @- http://x/; cat >> "$GITHUB_OUTPUT")',
                        'curl --rate satuan huruf besar mati di biner, tak menguras (Audit 2897)', True)

    skenario_substitusi(tmp, 'sub1959',
                        '> >(curl -C 100 -d @- http://x/; cat >> "$GITHUB_OUTPUT")',
                        'curl -C offset>0 campur -d: -d @- terkuras lebih dulu, konflik akhir-urai (Audit 2898; dikoreksi Audit 2952)', False)

    skenario_substitusi(tmp, 'sub1960',
                        '> >(curl -C 100 -G -d @- http://x/; cat >> "$GITHUB_OUTPUT")',
                        'curl -C offset>0 dengan -G membatalkan campuran data, -d @- menguras (Audit 2898)', False)

    skenario_substitusi(tmp, 'sub1961',
                        '> >(sdiff --tabsize=abc - /etc/hosts; cat >> "$GITHUB_OUTPUT")',
                        'sdiff --tabsize nilai tak sah kini ditolak (Audit 2899)', True)

    skenario_substitusi(tmp, 'sub1962',
                        '> >(sdiff --wid=abc - /etc/hosts; cat >> "$GITHUB_OUTPUT")',
                        'sdiff singkatan --wid=abc kini diselesaikan dan ditolak (Audit 2900)', True)

    skenario_substitusi(tmp, 'sub1963',
                        '> >(curl --tls-max 1.4 -d @- http://x/; cat >> "$GITHUB_OUTPUT")',
                        'curl --tls-max di luar enum kini ditolak (Audit 2901)', True)

    skenario_substitusi(tmp, 'sub1964',
                        '> >(curl --hostpubmd5 abc -d @- http://x/; cat >> "$GITHUB_OUTPUT")',
                        'curl --hostpubmd5 panjang salah kini ditolak (Audit 2902)', True)

    skenario_substitusi(tmp, 'sub1965',
                        '> >(curl --form abc -d @- http://x/; cat >> "$GITHUB_OUTPUT")',
                        'curl --form tanpa = kini ditolak (Audit 2903)', True)

    skenario_substitusi(tmp, 'sub1966',
                        '> >(diff --exclude - /etc/hosts; cat >> "$GITHUB_OUTPUT")',
                        'diff --exclude terpisah: nilai - tertelan opsi, tak menguras (Audit 2904)', True)

    skenario_substitusi(tmp, 'sub1967',
                        '> >(curl --max-filesize 9223372036854775807k -d @- http://x/; cat >> "$GITHUB_OUTPUT")',
                        'curl --max-filesize luapan sufiks kini ditolak (Audit 2907)', True)

    skenario_substitusi(tmp, 'sub1968',
                        '> >(curl --max-redirs -0 -d @- http://x/; cat >> "$GITHUB_OUTPUT")',
                        'curl --max-redirs -0 sah kini diterima, -d @- menguras (Audit 2908)', False)

    skenario_substitusi(tmp, 'sub1969',
                        '> >(curl --max-time 0x10 -d @- http://x/; cat >> "$GITHUB_OUTPUT")',
                        'curl --max-time heksadesimal sah kini diterima, -d @- menguras (Audit 2909)', False)

    skenario_substitusi(tmp, 'sub1970',
                        '> >(curl --form a=b -d @- https://example.com/; cat >> "$GITHUB_OUTPUT")',
                        'konflik metode curl form+data: -d @- terkuras lebih dulu, konflik akhir-urai (Audit 2916; dikoreksi Audit 2952)', False)

    skenario_substitusi(tmp, 'sub1971',
                        '> >(curl --retry \' 5\' -d @- https://example.com/; cat >> "$GITHUB_OUTPUT")',
                        'nilai numerik curl berawalan spasi sah di biner: gerbang gagal (Audit 2917)', False)

    skenario_substitusi(tmp, 'sub1972',
                        '> >(curl --buffer -d @- https://example.com/; cat >> "$GITHUB_OUTPUT")',
                        'bendera positif --buffer dikenal gerbang: gerbang gagal (Audit 2918)', False)

    skenario_substitusi(tmp, 'sub1973',
                        '> >(diff -F regex - /etc/hosts; cat >> "$GITHUB_OUTPUT")',
                        'diff -F terpisah menelan pola, operand - menguras: gerbang gagal (Audit 2919)', False)

    skenario_substitusi(tmp, 'sub1974',
                        '> >(diff --tabsize 8 - /etc/hosts; cat >> "$GITHUB_OUTPUT")',
                        'diff --tabsize terpisah menelan nilai, operand - menguras: gerbang gagal (Audit 2920)', False)

    skenario_substitusi(tmp, 'sub1975',
                        '> >(diff --wid 130 - /etc/hosts; cat >> "$GITHUB_OUTPUT")',
                        'singkatan --wid menelan nilai di vonis akhir: gerbang gagal (Audit 2921)', False)

    skenario_substitusi(tmp, 'sub1976',
                        '> >(curl --rate 1001/s -d @- https://example.com/; cat >> "$GITHUB_OUTPUT")',
                        'rate di atas plafon 1000/detik mati di biner: gerbang lolos (Audit 2924)', True)

    skenario_substitusi(tmp, 'sub1977',
                        '> >(curl --tlsauthtype XXX -d @- https://example.com/; cat >> "$GITHUB_OUTPUT")',
                        'tlsauthtype selain SRP mati di biner: gerbang lolos (Audit 2925)', True)

    skenario_substitusi(tmp, 'sub1978',
                        '> >(curl --parallel -d @- https://example.com/; cat >> "$GITHUB_OUTPUT")',
                        'bendera persis --parallel bukan singkatan --parallel-max: gerbang gagal (Audit 2926)', False)

    skenario_substitusi(tmp, 'sub1979',
                        '> >(curl --dns-servers 8.8.8.8 -d @- https://example.com/; cat >> "$GITHUB_OUTPUT")',
                        'dns-servers tak didukung build libcurl: gerbang lolos (Audit 2927)', True)

    skenario_substitusi(tmp, 'sub1980',
                        '> >(curl --next -d @- https://example.com/; cat >> "$GITHUB_OUTPUT")',
                        '--next tanpa URL pendahulu mati di biner: gerbang lolos (Audit 2928)', True)

    skenario_substitusi(tmp, 'sub1981',
                        '> >(diff --old-group-format fmt - /etc/hosts; cat >> "$GITHUB_OUTPUT")',
                        'nama format grup nyata dikenal gerbang: gerbang gagal (Audit 2929)', False)

    skenario_substitusi(tmp, 'sub1982',
                        '> >(sdiff --diff-program - /etc/hosts; cat >> "$GITHUB_OUTPUT")',
                        'sdiff --diff-program menelan nilai: gerbang lolos (Audit 2930)', True)

    skenario_substitusi(tmp, 'sub1983',
                        '> >(sdiff --out - /etc/hosts; cat >> "$GITHUB_OUTPUT")',
                        'singkatan --out menelan nilai juga di pas operand: gerbang lolos (Audit 2931)', True)

    skenario_substitusi(tmp, 'sub1984',
                        '> >(sdiff /etc/hosts /etc/passwd -; cat >> "$GITHUB_OUTPUT")',
                        'sdiff 3 operand mati extra operand: gerbang lolos (Audit 2932)', True)

    skenario_substitusi(tmp, 'sub1985',
                        '> >(sdiff -o /tmp/o.txt - /etc/hosts; cat >> "$GITHUB_OUTPUT")',
                        'sdiff -o menolak operand stdin secara eksplisit: gerbang lolos (Audit 2933)', True)

    skenario_substitusi(tmp, 'sub1986',
                        '> >(diff3 -m - /etc/hosts /etc/passwd; cat >> "$GITHUB_OUTPUT")',
                        'diff3 -m operand pertama stdin mati failed to reopen: gerbang lolos (Audit 2934)', True)

    skenario_substitusi(tmp, 'sub1987',
                        '> >(diff --unified - /etc/hosts; cat >> "$GITHUB_OUTPUT")',
                        'diff --unified tanpa nilai: token berikut operand stdin (Audit 2939)', False)

    skenario_substitusi(tmp, 'sub1988',
                        '> >(diff -m - /etc/hosts; cat >> "$GITHUB_OUTPUT")',
                        'diff -m huruf tak dikenal membunuh biner (Audit 2940)', True)

    skenario_substitusi(tmp, 'sub1989',
                        '> >(curl -W -d @- http://127.0.0.1:9/; cat >> "$GITHUB_OUTPUT")',
                        'curl -W huruf tak dikenal membunuh biner (Audit 2941)', True)

    skenario_substitusi(tmp, 'sub1990',
                        '> >(curl -G -I -d @- http://127.0.0.1:9/; cat >> "$GITHUB_OUTPUT")',
                        'curl -G menetralkan data x head (Audit 2942)', False)

    skenario_substitusi(tmp, 'sub1991',
                        '> >(diff3 - /etc/hosts -; cat >> "$GITHUB_OUTPUT")',
                        'diff3 dua operand stdin posisi 1-3 mati (Audit 2943)', True)

    skenario_substitusi(tmp, 'sub1992',
                        '> >(curl -d @- -I http://127.0.0.1:9/; cat >> "$GITHUB_OUTPUT")',
                        'curl sumber saat-urai mendahului konflik tetap menguras (Audit 2952)', False)

    skenario_substitusi(tmp, 'sub1993',
                        '> >(curl -# -d @- http://127.0.0.1:9/; cat >> "$GITHUB_OUTPUT")',
                        'curl simbol -# sah, sumber tetap menguras (Audit 2953)', False)

    skenario_substitusi(tmp, 'sub1994',
                        '> >(diff -iF - - /etc/hosts; cat >> "$GITHUB_OUTPUT")',
                        'diff bundel berakhiran huruf bernilai menelan token berikutnya (Audit 2960)', False)

    skenario_substitusi(tmp, 'sub1995',
                        '> >(curl -h -d @- http://127.0.0.1:9/; cat >> "$GITHUB_OUTPUT")',
                        'curl huruf informasional -h memintas sebelum sumber (Audit 2951)', True)

    skenario_substitusi(tmp, 'sub1996',
                        '> >(curl -x -I -d @- http://127.0.0.1:9/; cat >> "$GITHUB_OUTPUT")',
                        'curl -x menelan -I, -d @- tetap menguras (Audit 2954)', False)

    skenario_substitusi(tmp, 'sub1997',
                        '> >(curl --variable x@- http://127.0.0.1:9/; cat >> "$GITHUB_OUTPUT")',
                        'curl --variable nama@- sumber stdin saat-urai (Audit 2955)', False)

    skenario_substitusi(tmp, 'sub1998',
                        '> >(diff -iF - /etc/hosts; cat >> "$GITHUB_OUTPUT")',
                        'diff bundel menelan satu-satunya - sebagai nilai, tak menguras (Audit 2961)', True)

    skenario_substitusi(tmp, 'sub1999',
                        '> >(curl -d x http://a/ --next -T - http://b/; cat >> "$GITHUB_OUTPUT")',
                        'konflik metode per seksi: data di seksi 1 tak membunuh unggahan stdin seksi 2 (Audit 2962)', False)

    skenario_substitusi(tmp, 'sub2000',
                        '> >(curl -F a=<- http://x/; cat >> "$GITHUB_OUTPUT")',
                        'form isi-dari-stdin <- adalah sumber saat-urai (Audit 2964)', False)

    skenario_substitusi(tmp, 'sub2001',
                        '> >(curl -d @-x http://x/; cat >> "$GITHUB_OUTPUT")',
                        'data @-x adalah berkas bernama -x, bukan stdin (Audit 2965)', True)

    skenario_substitusi(tmp, 'sub2002',
                        '> >(curl --variable x=@- http://x/; cat >> "$GITHUB_OUTPUT")',
                        '--variable dengan nama tak sah bukan impor stdin (Audit 2966)', True)

    skenario_substitusi(tmp, 'sub2003',
                        '> >(curl -s http://x/ -F \'a=<-;type=\'; cat >> "$GITHUB_OUTPUT")',
                        'form type= kosong mati saat urai - bukan sumber stdin (Audit 2980)', True)

    skenario_substitusi(tmp, 'sub2004',
                        '> >(curl -s http://x/ -F \'a=@-;type=\'; cat >> "$GITHUB_OUTPUT")',
                        'form @- type= kosong mati saat urai - bukan sumber stdin (Audit 2980)', True)

    skenario_substitusi(tmp, 'sub2005',
                        '> >(curl -s http://x/ -F \'a=<-;type=text/plain\'; cat >> "$GITHUB_OUTPUT")',
                        'form type= sah tetap sumber saat-urai (Audit 2980)', False)

    skenario_substitusi(tmp, 'sub2006',
                        '> >(curl -s http://x/ -F \'a=<-;type=;type=text/plain\'; cat >> "$GITHUB_OUTPUT")',
                        'satu type= sah menyelamatkan bagian form (Audit 2980)', False)

    skenario_substitusi(tmp, 'sub2007',
                        '> >(curl -s http://x/ -F \'a=<-;type=\' -d @-; cat >> "$GITHUB_OUTPUT")',
                        'type= maut sebelum sumber data - biner mati lebih dulu (Audit 2980)', True)

    skenario_substitusi(tmp, 'sub2008',
                        '> >(curl -s http://x/ -d @- -F \'a=<-;type=\'; cat >> "$GITHUB_OUTPUT")',
                        'sumber data sebelum type= maut tetap menguras (Audit 2980)', False)

    umpanbak = klon(tmp, "umpanbak")
    berkas_ub = os.path.join(umpanbak, ".github", "actions",
                             "resolve-vw", "action.yml")
    with open(berkas_ub, encoding="utf-8") as f:
        isi_ub = f.read()
    with open(berkas_ub, "w", encoding="utf-8") as f:
        f.write(isi_ub.replace(
            'echo "tag_mentah=$TAG" >> "$GITHUB_OUTPUT"',
            'echo "tagmentah=$TAG" >> "$GITHUB_OUTPUT"\n'
            '        echo "tag_mentah=$TAG" >> "$GITHUB_OUTPUT.bak"'))
    rc, out = jalan(umpanbak, [sys.executable, GERBANG, "--semua"])
    periksa("umpan redirect .bak nama produsen: gerbang gagal (Audit 870)",
            rc != 0 and rc != 124 and "tag_mentah" in out)
    shutil.rmtree(umpanbak, ignore_errors=True)

    prod = klon(tmp, "prod")
    berkas_prod = os.path.join(prod, ".github", "actions",
                               "resolve-vw", "action.yml")
    with open(berkas_prod, encoding="utf-8") as f:
        isi_prod = f.read()
    with open(berkas_prod, "w", encoding="utf-8") as f:
        f.write(isi_prod.replace('echo "tag_mentah=$TAG"',
                                 'echo "tagmentah=$TAG"'))
    rc, out = jalan(prod, [sys.executable, GERBANG, "--semua"])
    periksa("nama ditulis produsen resolve-vw diganti: gerbang gagal (Audit 855)",
            rc != 0 and rc != 124 and "tag_mentah" in out)
    shutil.rmtree(prod, ignore_errors=True)

    idact = klon(tmp, "idact")
    berkas_act = os.path.join(idact, ".github", "actions",
                              "resolve-vw", "action.yml")
    with open(berkas_act, encoding="utf-8") as f:
        isi_act = f.read()
    with open(berkas_act, "w", encoding="utf-8") as f:
        f.write(isi_act.replace("- id: vw", "- id: vwx"))
    rc, out = jalan(idact, [sys.executable, GERBANG, "--semua"])
    periksa("id langkah internal action diganti: gerbang gagal (Audit 840)",
            rc != 0 and rc != 124 and "vw" in out)
    shutil.rmtree(idact, ignore_errors=True)

    lintas = klon(tmp, "lintas")
    berkas_bin = os.path.join(lintas, ".github", "workflows",
                              "build-binary.yml")
    with open(berkas_bin, encoding="utf-8") as f:
        isi_bin = f.read()
    with open(berkas_bin, "a", encoding="utf-8") as f:
        f.write("\n  jobuji:\n    runs-on: ubuntu-latest\n"
                "    steps:\n      - run: echo \"${{ steps.ambil.outputs.tag }}\"\n")
    rc, out = jalan(lintas, [sys.executable, GERBANG, "--semua"])
    periksa("rujukan steps lintas job: gerbang gagal (Audit 856)",
            rc != 0 and rc != 124 and "ambil" in out)
    shutil.rmtree(lintas, ignore_errors=True)

    idl = klon(tmp, "idl")
    berkas_wf = os.path.join(idl, ".github", "workflows", "build-apk.yml")
    with open(berkas_wf, encoding="utf-8") as f:
        isi_wf = f.read()
    with open(berkas_wf, "w", encoding="utf-8") as f:
        f.write(isi_wf.replace("- id: ambil", "- id: ambil2"))
    rc, out = jalan(idl, [sys.executable, GERBANG, "--semua"])
    periksa("id langkah resolve-vw diganti: gerbang gagal (Audit 836)",
            rc != 0 and rc != 124 and "ambil" in out)
    shutil.rmtree(idl, ignore_errors=True)

    sarang = klon(tmp, "sarangakhir")
    berkas_set5 = os.path.join(sarang, "settings.gradle.kts")
    with open(berkas_set5, encoding="utf-8") as f:
        isi_set5 = f.read()
    with open(berkas_set5, "w", encoding="utf-8") as f:
        f.write(isi_set5.replace(
            "repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)",
            "/* luar /* dalam */ repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS) */"))
    rc, out = jalan(sarang, [sys.executable, GERBANG, "--semua"])
    periksa("komentar blok bersarang menyembunyikan mode: gerbang gagal (Audit 866)",
            rc != 0 and rc != 124 and "FAIL_ON_PROJECT_REPOS" in out)
    shutil.rmtree(sarang, ignore_errors=True)

    charagp = klon(tmp, "charagp")
    berkas_agp2 = os.path.join(charagp, "build.gradle.kts")
    with open(berkas_agp2, encoding="utf-8") as f:
        isi_agp2 = f.read()
    with open(berkas_agp2, "w", encoding="utf-8") as f:
        f.write('val penanda = \'"\'\n'
                '/* id("com.android.application") version'
                ' "8.13.2" apply false */\n'
                + isi_agp2.replace('version "8.13.2"',
                                   'version "9.9.9"'))
    rc, out = jalan(charagp, [sys.executable, GERBANG, "--semua"])
    periksa("char literal petik + AGP di komentar: gerbang gagal (Audit 873)",
            rc != 0 and rc != 124 and "8.13.2" in out)
    shutil.rmtree(charagp, ignore_errors=True)

    charset = klon(tmp, "charset")
    berkas_set8 = os.path.join(charset, "settings.gradle.kts")
    with open(berkas_set8, encoding="utf-8") as f:
        isi_set8 = f.read()
    with open(berkas_set8, "w", encoding="utf-8") as f:
        f.write('val penanda = \'"\'\n' + isi_set8)
    rc, out = jalan(charset, [sys.executable, GERBANG, "--semua"])
    periksa("char literal petik di settings sehat: gerbang lolos (Audit 873)",
            rc == 0)
    shutil.rmtree(charset, ignore_errors=True)

    tplchar = klon(tmp, "tplchar")
    berkas_set9 = os.path.join(tplchar, "settings.gradle.kts")
    with open(berkas_set9, encoding="utf-8") as f:
        isi_set9 = f.read()
    with open(berkas_set9, "w", encoding="utf-8") as f:
        f.write('val penanda = "${\'"\'}"\n' + isi_set9)
    rc, out = jalan(tplchar, [sys.executable, GERBANG, "--semua"])
    periksa("template berisi char petik di settings sehat: gerbang lolos (Audit 877)",
            rc == 0)
    shutil.rmtree(tplchar, ignore_errors=True)

    tplagp = klon(tmp, "tplagp")
    berkas_agp3 = os.path.join(tplagp, "build.gradle.kts")
    with open(berkas_agp3, encoding="utf-8") as f:
        isi_agp3 = f.read()
    with open(berkas_agp3, "w", encoding="utf-8") as f:
        f.write('val penanda = "${\'"\'}"\n'
                '/* id("com.android.application") version'
                ' "8.13.2" apply false */\n'
                + isi_agp3.replace('version "8.13.2"',
                                   'version "9.9.9"'))
    rc, out = jalan(tplagp, [sys.executable, GERBANG, "--semua"])
    periksa("template char petik + AGP di komentar: gerbang gagal (Audit 877)",
            rc != 0 and rc != 124 and "8.13.2" in out)
    shutil.rmtree(tplagp, ignore_errors=True)

    tplkomen = klon(tmp, "tplkomen")
    berkas_set10 = os.path.join(tplkomen, "settings.gradle.kts")
    with open(berkas_set10, encoding="utf-8") as f:
        isi_set10 = f.read()
    with open(berkas_set10, "w", encoding="utf-8") as f:
        f.write('val penanda = "${x /* " */ + y}"\n' + isi_set10)
    rc, out = jalan(tplkomen, [sys.executable, GERBANG, "--semua"])
    periksa("komentar berisi petik di dalam template settings sehat: gerbang lolos (Audit 880)",
            rc == 0)
    shutil.rmtree(tplkomen, ignore_errors=True)

    tplraw = klon(tmp, "tplraw")
    berkas_set11 = os.path.join(tplraw, "settings.gradle.kts")
    with open(berkas_set11, encoding="utf-8") as f:
        isi_set11 = f.read()
    with open(berkas_set11, "w", encoding="utf-8") as f:
        f.write('val penanda = "${"""a"b"""}"\n' + isi_set11)
    rc, out = jalan(tplraw, [sys.executable, GERBANG, "--semua"])
    periksa("raw string berpetik di dalam template settings sehat: gerbang lolos (Audit 883)",
            rc == 0)
    shutil.rmtree(tplraw, ignore_errors=True)

    strmode = klon(tmp, "strmode")
    berkas_set6 = os.path.join(strmode, "settings.gradle.kts")
    with open(berkas_set6, encoding="utf-8") as f:
        isi_set6 = f.read()
    with open(berkas_set6, "w", encoding="utf-8") as f:
        f.write(isi_set6.replace(
            "    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)",
            "    // repositoriesMode dimatikan\n"
            '    val penanda = "RepositoriesMode.FAIL_ON_PROJECT_REPOS"'))
    rc, out = jalan(strmode, [sys.executable, GERBANG, "--semua"])
    periksa("mode hanya di dalam string biasa: gerbang gagal (Audit 869)",
            rc != 0 and rc != 124 and "FAIL_ON_PROJECT_REPOS" in out)
    shutil.rmtree(strmode, ignore_errors=True)

    rawmode = klon(tmp, "rawmode")
    berkas_set7 = os.path.join(rawmode, "settings.gradle.kts")
    with open(berkas_set7, encoding="utf-8") as f:
        isi_set7 = f.read()
    with open(berkas_set7, "w", encoding="utf-8") as f:
        f.write(isi_set7.replace(
            "    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)",
            "    // repositoriesMode dimatikan\n"
            '    val penanda = """RepositoriesMode.FAIL_ON_PROJECT_REPOS"""'))
    rc, out = jalan(rawmode, [sys.executable, GERBANG, "--semua"])
    periksa("mode hanya di dalam raw string: gerbang gagal (Audit 869)",
            rc != 0 and rc != 124 and "FAIL_ON_PROJECT_REPOS" in out)
    shutil.rmtree(rawmode, ignore_errors=True)

    rawagp = klon(tmp, "rawagp")
    berkas_agp = os.path.join(rawagp, "build.gradle.kts")
    with open(berkas_agp, encoding="utf-8") as f:
        isi_agp = f.read()
    with open(berkas_agp, "w", encoding="utf-8") as f:
        f.write(isi_agp.replace('version "8.13.2"', 'version "9.9.9"')
                + '\nval penanda = """id("com.android.application")'
                  ' version "8.13.2" apply false"""\n')
    rc, out = jalan(rawagp, [sys.executable, GERBANG, "--semua"])
    periksa("versi AGP terpaku hanya di raw string: gerbang gagal (Audit 869)",
            rc != 0 and rc != 124 and "8.13.2" in out)
    shutil.rmtree(rawagp, ignore_errors=True)

    komen = klon(tmp, "komen")
    berkas_set3 = os.path.join(komen, "settings.gradle.kts")
    with open(berkas_set3, encoding="utf-8") as f:
        isi_set3 = f.read()
    with open(berkas_set3, "w", encoding="utf-8") as f:
        f.write(isi_set3.replace(
            "repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)",
            "// repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)"))
    rc, out = jalan(komen, [sys.executable, GERBANG, "--semua"])
    periksa("baris repositoriesMode dikomentari: gerbang gagal (Audit 854)",
            rc != 0 and rc != 124 and "FAIL_ON_PROJECT_REPOS" in out)
    shutil.rmtree(komen, ignore_errors=True)

    selip = klon(tmp, "selip")
    berkas_set4 = os.path.join(selip, "settings.gradle.kts")
    with open(berkas_set4, encoding="utf-8") as f:
        isi_set4 = f.read()
    with open(berkas_set4, "w", encoding="utf-8") as f:
        f.write(isi_set4.replace(
            "    repositories {\n        google()\n        mavenCentral()\n    }\n}\nrootProject",
            "    repositories /* dicoba */ { maven { url = uri(\"https://repo-asing.example.com/\") } }\n}\nrootProject"))
    rc, out = jalan(selip, [sys.executable, GERBANG, "--semua"])
    periksa("repo asing terselip komentar blok: gerbang gagal (Audit 854)",
            rc != 0 and rc != 124 and "maven" in out)
    shutil.rmtree(selip, ignore_errors=True)

    ganda = klon(tmp, "ganda")
    berkas_prop = os.path.join(ganda, "gradle.properties")
    with open(berkas_prop, "a", encoding="utf-8") as f:
        f.write("android.useAndroidX=true\n")
    rc, out = jalan(ganda, [sys.executable, GERBANG, "--semua"])
    periksa("useAndroidX duplikat last-wins true: gerbang gagal (Audit 854)",
            rc != 0 and rc != 124 and "useAndroidX" in out)
    shutil.rmtree(ganda, ignore_errors=True)

    titikdua = klon(tmp, "titikdua")
    berkas_prop2 = os.path.join(titikdua, "gradle.properties")
    with open(berkas_prop2, "a", encoding="utf-8") as f:
        f.write("android.useAndroidX:true\n")
    rc, out = jalan(titikdua, [sys.executable, GERBANG, "--semua"])
    periksa("useAndroidX pemisah titik dua last-wins: gerbang gagal (Audit 871)",
            rc != 0 and rc != 124 and "useAndroidX" in out)
    shutil.rmtree(titikdua, ignore_errors=True)

    sambung = klon(tmp, "sambung")
    berkas_prop3 = os.path.join(sambung, "gradle.properties")
    with open(berkas_prop3, "a", encoding="utf-8") as f:
        f.write("android.useAndroidX\\\n=true\n")
    rc, out = jalan(sambung, [sys.executable, GERBANG, "--semua"])
    periksa("useAndroidX sambung-baris belah kunci: gerbang gagal (Audit 876)",
            rc != 0 and rc != 124 and "useAndroidX" in out)
    shutil.rmtree(sambung, ignore_errors=True)

    esckunci = klon(tmp, "esckunci")
    with open(os.path.join(esckunci, "gradle.properties"), "a",
              encoding="utf-8") as f:
        f.write("android.useAndroid\\u0058=true\n")
    rc, out = jalan(esckunci, [sys.executable, GERBANG, "--semua"])
    periksa("useAndroidX escape unicode di kunci: gerbang gagal (Audit 879)",
            rc != 0 and rc != 124 and "useAndroidX" in out)
    shutil.rmtree(esckunci, ignore_errors=True)

    escpisah = klon(tmp, "escpisah")
    with open(os.path.join(escpisah, "gradle.properties"), "a",
              encoding="utf-8") as f:
        f.write("android.useAndroidX\\u003dtrue\n")
    rc, out = jalan(escpisah, [sys.executable, GERBANG, "--semua"])
    periksa("useAndroidX escape unicode di pemisah: gerbang gagal (Audit 879)",
            rc != 0 and rc != 124 and "useAndroidX" in out)
    shutil.rmtree(escpisah, ignore_errors=True)

    agp = klon(tmp, "agp")
    berkas_akar = os.path.join(agp, "build.gradle.kts")
    with open(berkas_akar, encoding="utf-8") as f:
        isi_akar = f.read()
    with open(berkas_akar, "w", encoding="utf-8") as f:
        f.write(isi_akar.replace('version "8.13.2"', 'version "9.9.9"'))
    rc, out = jalan(agp, [sys.executable, GERBANG, "--semua"])
    periksa("versi AGP diganti dari terpaku: gerbang gagal (Audit 835)",
            rc != 0 and rc != 124 and "8.13.2" in out)
    shutil.rmtree(agp, ignore_errors=True)

    asing = klon(tmp, "asing")
    berkas_set2 = os.path.join(asing, "settings.gradle.kts")
    with open(berkas_set2, encoding="utf-8") as f:
        isi_set2 = f.read()
    with open(berkas_set2, "w", encoding="utf-8") as f:
        f.write(isi_set2.replace(
            "        google()\n        mavenCentral()\n    }\n}\nrootProject",
            "        google()\n        mavenCentral()\n"
            "        maven { url = uri(\"https://repo-asing.example.com/\") }\n"
            "    }\n}\nrootProject"))
    rc, out = jalan(asing, [sys.executable, GERBANG, "--semua"])
    periksa("repositori asing di settings: gerbang gagal (Audit 835)",
            rc != 0 and rc != 124 and "maven" in out)
    shutil.rmtree(asing, ignore_errors=True)

    yatim = klon(tmp, "yatim")
    berkas_yatim = os.path.join(yatim, "app", "src", "main", "res",
                                "layout-land", "yatimuji.xml")
    with open(berkas_yatim, "w") as f:
        f.write("<LinearLayout xmlns:android="
                "\"http://schemas.android.com/apk/res/android\" />\n")
    rc, out = jalan(yatim, [sys.executable, GERBANG, "--semua"])
    periksa("layout-land yatim: gerbang gagal (Audit 831)",
            rc != 0 and rc != 124 and "layout-land" in out)
    shutil.rmtree(yatim, ignore_errors=True)

    nyamar = klon(tmp, "nyamar")
    with open(os.path.join(nyamar, "catatan-uji.txt"), "wb") as f:
        f.write(b"\x7fELF\x02\x01\x01\x00" + b"\x00" * 16)
    git(nyamar, "add", "-A")
    git(nyamar, "commit", "-q", "-m", "tanam biner menyamar")
    rc, out = jalan(nyamar, [sys.executable, GERBANG,
                            "--rentang", "HEAD~1..HEAD"])
    periksa("biner menyamar sebagai teks: gerbang gagal (Audit 831)",
            rc != 0 and rc != 124 and "catatan-uji.txt" in out)
    shutil.rmtree(nyamar, ignore_errors=True)

    bersih2 = klon(tmp, "bersih2")
    rc, out = jalan(bersih2,
                    [sys.executable, GERBANG,
                     "app/src/main/java/com/tasirin/vaultwardenhost/"
                     "BerkasNgawur.java"])
    periksa("jalur eksplisit tak ada: gerbang gagal (Audit 829)",
            rc != 0 and rc != 124 and "BerkasNgawur.java" in out)


def lapor():
    if GAGAL:
        print("GAGAL: %d skenario menyimpang: %s"
              % (len(GAGAL), ", ".join(GAGAL)))
        return 1
    print("LOLOS: semua skenario uji-diri gerbang sesuai harapan.")
    return 0




def main():
    with tempfile.TemporaryDirectory(prefix="uji-verifikasi-") as tmp:
        uji_bersama.paruh_aman(_bagian_awal, tmp)
        uji_bersama.paruh_aman(_bagian_akhir, tmp)
    return lapor()


if __name__ == "__main__":
    sys.exit(main())
