#!/usr/bin/env bash
# Cek cepat pra-push tanpa toolchain Android (lihat AGENTS.md Aturan No.1).
# Gagal cepat sebelum push agar hemat menit CI.
set -euo pipefail
gagal=0
# Anotasi GitHub: di runner CI, tiap GAGAL juga ditulis sebagai annotation
# (::error::) agar muncul di tab Files; lokal hanya teks biasa.
if [ "${GITHUB_ACTIONS:-}" = "true" ]; then ANO_ERR="::error::"; else ANO_ERR=""; fi
gagal_msg() { echo "${ANO_ERR}GAGAL: $*"; gagal=1; }
# Kemampuan rg diuji eksekusi nyata (command -v menipu: shim mati tetap "ada").
if rg --version >/dev/null 2>&1; then ADA_RG=1; else ADA_RG=0; fi
# 1. Path vektor harus 0.x (lint InvalidVectorPath menggagalkan build).
# Tiga tingkat: rg PCRE2, grep -P, grep -E portabel — selalu memeriksa,
# tak pernah gagal-tertutup karena perkakas hilang.
if [ "$ADA_RG" = 1 ] && rg --pcre2 -n '' app/src/main/res/drawable >/dev/null 2>&1; then
  if rg -n --pcre2 '(?<![0-9])\.-?[0-9]' app/src/main/res/drawable 2>/dev/null | grep -q .; then
    gagal_msg "path vektor tanpa nol depan (pakai 0.9 bukan .9)";
  fi
elif grep -R -P '' . >/dev/null 2>&1; then
  if grep -R -n -P '(?<![0-9])\.-?[0-9]' app/src/main/res/drawable 2>/dev/null | grep -q .; then
    gagal_msg "path vektor tanpa nol depan (pakai 0.9 bukan .9)";
  fi
elif grep -R -E -n '(^|[^0-9])\.-?[0-9]' app/src/main/res/drawable 2>/dev/null | grep -q .; then
  gagal_msg "path vektor tanpa nol depan (pakai 0.9 bukan .9)";
fi
# 2. ID layout portrait vs landscape wajib sama persis.
# Loop semua layout agar kartu baru ikut terjaga (portabel: bandingkan via cmp).
# Mesin kerja BusyBox sering tanpa rg: pakai rg bila ada, grep -o bila tidak.
ekstrak_id() {
  if [ "$ADA_RG" = 1 ]; then rg -o 'android:id="@\+id/[A-Za-z0-9_]+"' "$1" | sort -u;
  else grep -o 'android:id="@+id/[A-Za-z0-9_]*"' "$1" | sort -u; fi
}
for f in app/src/main/res/layout/*.xml; do
  land="app/src/main/res/layout-land/$(basename "$f")"
  if [ -f "$land" ]; then
    if ! cmp -s <(ekstrak_id "$f") <(ekstrak_id "$land"); then gagal_msg "ID $f != $land"; fi
  fi
done
# 3. Jangan commit binary/APK/zip ke repo (APK tetap kecil).
# Termasuk keystore/secret agar password tak bocor ke repo.
if git diff --cached --name-only | grep -E -q '\.(apk|zip|jks|p12|pfx)$|/vaultwarden-armeabi|/libgetrandom-shim-|keystore|\.env$'; then
  gagal_msg "binary/APK/zip/secret ikut staged (jks|keystore|p12|pfx|.env dilarang)";
fi
# 4. Jumlah section README Inggris vs Indonesia vs Rusia wajib sama (jaga sinkron).
a=$(rg -c '^## ' README.md 2>/dev/null || grep -c '^## ' README.md)
b=$(rg -c '^## ' README.id.md 2>/dev/null || grep -c '^## ' README.id.md)
c=$(rg -c '^## ' README.ru.md 2>/dev/null || grep -c '^## ' README.ru.md)
if [ "$a" != "$b" ]; then gagal_msg "jumlah ## README.md ($a) != README.id.md ($b)"; fi
if [ "$a" != "$c" ]; then gagal_msg "jumlah ## README.md ($a) != README.ru.md ($c)"; fi
exit $gagal
