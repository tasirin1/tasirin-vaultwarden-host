#!/bin/sh
# Cek cepat pra-push tanpa toolchain Android (lihat AGENTS.md Aturan No.1).
# Gagal cepat sebelum push agar hemat menit CI.
set -u
gagal=0
# 1. Path vektor harus 0.x (lint InvalidVectorPath menggagalkan build).
# Pilih mesin regex yang tersedia; gagal-tertutup bila tak ada yang mampu.
if rg --pcre2 -n '' app/src/main/res/drawable >/dev/null 2>&1; then
  if rg -n --pcre2 '(?<![0-9])\.-?[0-9]' app/src/main/res/drawable 2>/dev/null | grep -q .; then
    echo "GAGAL: path vektor tanpa nol depan (pakai 0.9 bukan .9)"; gagal=1;
  fi
elif grep -R -P '' . >/dev/null 2>&1; then
  if grep -R -n -P '(?<![0-9])\.-?[0-9]' app/src/main/res/drawable 2>/dev/null | grep -q .; then
    echo "GAGAL: path vektor tanpa nol depan (pakai 0.9 bukan .9)"; gagal=1;
  fi
else
  echo "GAGAL: butuh rg --pcre2 atau grep -P untuk cek vektor"; gagal=1;
fi
# 2. ID layout portrait vs landscape wajib sama persis.
for f in app/src/main/res/layout/activity_main.xml; do
  land="app/src/main/res/layout-land/$(basename $f)"
  if [ -f "$land" ]; then
    a=$(rg -o 'android:id="@\+id/[A-Za-z0-9_]+"' "$f" | sort -u | md5sum | cut -d' ' -f1)
    b=$(rg -o 'android:id="@\+id/[A-Za-z0-9_]+"' "$land" | sort -u | md5sum | cut -d' ' -f1)
    if [ "$a" != "$b" ]; then echo "GAGAL: ID $f != $land"; gagal=1; fi
  fi
done
# 3. Jangan commit binary/APK/zip ke repo (APK tetap kecil).
if git diff --cached --name-only | rg -q '\.(apk|zip)$|/vaultwarden-armeabi|/libgetrandom-shim-'; then
  echo "GAGAL: binary/APK/zip ikut staged"; gagal=1;
fi
# 4. Jumlah section README Inggris vs Indonesia vs Rusia wajib sama (jaga sinkron).
a=$(rg -c '^## ' README.md 2>/dev/null || grep -c '^## ' README.md)
b=$(rg -c '^## ' README.id.md 2>/dev/null || grep -c '^## ' README.id.md)
c=$(rg -c '^## ' README.ru.md 2>/dev/null || grep -c '^## ' README.ru.md)
if [ "$a" != "$b" ]; then echo "GAGAL: jumlah ## README.md ($a) != README.id.md ($b)"; gagal=1; fi
if [ "$a" != "$c" ]; then echo "GAGAL: jumlah ## README.md ($a) != README.ru.md ($c)"; gagal=1; fi
exit $gagal
