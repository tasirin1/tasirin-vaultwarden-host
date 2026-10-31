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
