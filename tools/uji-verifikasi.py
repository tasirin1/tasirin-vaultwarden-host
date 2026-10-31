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
        with open(berkas_aksi8, "w", encoding="utf-8") as f:
            f.write(isi_aksi8.replace(
                baris_tulis8, baris_tulis8 + " # tulis keluaran", 1))
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
        with open(berkas_aksi10, "w", encoding="utf-8") as f:
            f.write(isi_aksi10.replace(
                baris_tulis10,
                baris_tulis10.replace('>> "$GITHUB_OUTPUT"',
                                      '>> "$GITHUB_OUTPUT"#semu'), 1))
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
        with open(berkas_aksi12, "w", encoding="utf-8") as f:
            f.write(isi_aksi12.replace(
                baris_tulis12,
                baris_tulis12.replace(
                    '>> "$GITHUB_OUTPUT"',
                    '>> "$GITHUB_OUTPUT.bak" >> "$GITHUB_OUTPUT"'), 1))
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
        with open(berkas_aksi13, "w", encoding="utf-8") as f:
            f.write(isi_aksi13.replace(
                baris_tulis13,
                indent13 + "{ " + baris_tulis13.strip() + "; }", 1))
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
        with open(berkas_aksi14, "w", encoding="utf-8") as f:
            f.write(isi_aksi14.replace(
                baris_tulis14,
                indent14 + "(" + baris_tulis14.strip() + ")", 1))
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
        with open(berkas_aksi15, "w", encoding="utf-8") as f:
            f.write(isi_aksi15.replace(
                baris_tulis15,
                indent15 + 'test -n "$TAG" || '
                + baris_tulis15.strip(), 1))
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
        with open(berkas_aksi16, "w", encoding="utf-8") as f:
            f.write(isi_aksi16.replace(
                baris_tulis16,
                baris_tulis16.replace(
                    '>> "$GITHUB_OUTPUT"',
                    '>> "$GITHUB_OUTPUT.bak" > "$GITHUB_OUTPUT"'), 1))
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
        with open(berkas_aksi17, "w", encoding="utf-8") as f:
            f.write(isi_aksi17.replace(
                baris_tulis17,
                baris_tulis17.replace('>> "$GITHUB_OUTPUT"',
                                      '2>> "$GITHUB_OUTPUT"'), 1))
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
        with open(berkas_aksi18, "w", encoding="utf-8") as f:
            f.write(isi_aksi18.replace(
                baris_tulis18,
                baris_tulis18.replace('>> "$GITHUB_OUTPUT"',
                                      '>> ${GITHUB_OUTPUT}'), 1))
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
        with open(berkas_aksi19, "w", encoding="utf-8") as f:
            f.write(isi_aksi19.replace(
                baris_tulis19,
                baris_tulis19.replace('>> "$GITHUB_OUTPUT"',
                                      '>| "$GITHUB_OUTPUT"'), 1))
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
        with open(berkas_aksi20, "w", encoding="utf-8") as f:
            f.write(isi_aksi20.replace(
                baris_tulis20,
                baris_tulis20.replace('>> "$GITHUB_OUTPUT"',
                                      '>& "$GITHUB_OUTPUT"'), 1))
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
        with open(berkas_aksi21, "w", encoding="utf-8") as f:
            f.write(isi_aksi21.replace(
                baris_tulis21,
                baris_tulis21.replace('>> "$GITHUB_OUTPUT"',
                                      '<> "$GITHUB_OUTPUT"'), 1))
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
        with open(berkas_aksi22, "w", encoding="utf-8") as f:
            f.write(isi_aksi22.replace(
                baris_tulis22,
                baris_tulis22.replace('>> "$GITHUB_OUTPUT"',
                                      '>> > "$GITHUB_OUTPUT"'), 1))
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
        with open(berkas_aksi23, "w", encoding="utf-8") as f:
            f.write(isi_aksi23.replace(
                baris_tulis23,
                baris_tulis23.replace('>> "$GITHUB_OUTPUT"',
                                      '>>| "$GITHUB_OUTPUT"'), 1))
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
        with open(berkas_aksi24, "w", encoding="utf-8") as f:
            f.write(isi_aksi24.replace(
                baris_tulis24,
                baris_tulis24.replace('>> "$GITHUB_OUTPUT"',
                                      '>> $GITHUB_OUTPUT}'), 1))
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
        with open(berkas_aksi25, "w", encoding="utf-8") as f:
            f.write(isi_aksi25.replace(
                baris_tulis25,
                baris_tulis25.replace('>> "$GITHUB_OUTPUT"',
                                      '< > "$GITHUB_OUTPUT"'), 1))
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
        with open(berkas_aksi26, "w", encoding="utf-8") as f:
            f.write(isi_aksi26.replace(
                baris_tulis26,
                baris_tulis26 + " $(true > /dev/null)", 1))
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
        with open(berkas_aksi27, "w", encoding="utf-8") as f:
            f.write(isi_aksi27.replace(
                baris_tulis27,
                baris_tulis27.replace(
                    '>> "$GITHUB_OUTPUT"',
                    '$(true >> "$GITHUB_OUTPUT")'), 1))
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
        with open(berkas_aksi28, "w", encoding="utf-8") as f:
            f.write(isi_aksi28.replace(
                baris_tulis28,
                baris_tulis28.replace(
                    '>> "$GITHUB_OUTPUT"',
                    '>(cat >> "$GITHUB_OUTPUT")'), 1))
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
        with open(berkas_aksi29, "w", encoding="utf-8") as f:
            f.write(isi_aksi29.replace(
                baris_tulis29,
                baris_tulis29 + " >(cat > /dev/null)", 1))
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
        with open(berkas_aksi30, "w", encoding="utf-8") as f:
            f.write(isi_aksi30.replace(
                baris_tulis30,
                baris_tulis30.replace(
                    '>> "$GITHUB_OUTPUT"',
                    '`true >> "$GITHUB_OUTPUT" `'), 1))
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
        with open(berkas_aksi31, "w", encoding="utf-8") as f:
            f.write(isi_aksi31.replace(
                baris_tulis31,
                baris_tulis31 + " `true > /dev/null`", 1))
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
        with open(berkas_aksi32, "w", encoding="utf-8") as f:
            f.write(isi_aksi32.replace(
                baris_tulis32,
                indentasi32 + "{ " + baris_tulis32.strip() + " }", 1))
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
        with open(berkas_aksi33, "w", encoding="utf-8") as f:
            f.write(isi_aksi33.replace(
                baris_tulis33,
                baris_tulis33.replace(
                    '>> "$GITHUB_OUTPUT"',
                    '`echo x; echo y` >> "$GITHUB_OUTPUT"'), 1))
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
        with open(berkas_aksi34, "w", encoding="utf-8") as f:
            f.write(isi_aksi34.replace(
                baris_tulis34,
                baris_tulis34.replace(
                    '>> "$GITHUB_OUTPUT"',
                    '`echo #` >> "$GITHUB_OUTPUT"'), 1))
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
        with open(berkas_aksi35, "w", encoding="utf-8") as f:
            f.write(isi_aksi35.replace(
                baris_tulis35,
                baris_tulis35.replace(
                    '>> "$GITHUB_OUTPUT"',
                    '\\` >> "$GITHUB_OUTPUT"'), 1))
        rc, out = jalan(luput, [sys.executable, GERBANG, "--semua"])
        periksa("backtick ter-escape literal: gerbang lolos (Audit 908)",
                rc == 0)
        shutil.rmtree(luput, ignore_errors=True)

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
