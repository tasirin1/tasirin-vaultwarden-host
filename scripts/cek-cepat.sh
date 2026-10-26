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
  # Tangkap keluaran dulu: `rg ... | grep -q` dengan pipefail membuat rg mati
  # SIGPIPE (141) saat kecocokan banyak sehingga gate diam-diam lolos.
  TEMUAN="$(rg -n --pcre2 '(?<![0-9])\.-?[0-9]' app/src/main/res/drawable 2>/dev/null || true)"
  if [ -n "$TEMUAN" ]; then
    gagal_msg "path vektor tanpa nol depan (pakai 0.9 bukan .9)";
  fi
elif grep -R -P '' . >/dev/null 2>&1; then
  TEMUAN="$(grep -R -n -P '(?<![0-9])\.-?[0-9]' app/src/main/res/drawable 2>/dev/null || true)"
  if [ -n "$TEMUAN" ]; then
    gagal_msg "path vektor tanpa nol depan (pakai 0.9 bukan .9)";
  fi
else
  TEMUAN="$(grep -R -E -n '(^|[^0-9])\.-?[0-9]' app/src/main/res/drawable 2>/dev/null || true)"
  if [ -n "$TEMUAN" ]; then
    gagal_msg "path vektor tanpa nol depan (pakai 0.9 bukan .9)";
  fi
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
# 3. Jangan commit binary/APK/zip/secret ke repo (APK tetap kecil).
# Termasuk keystore/secret agar password tak bocor ke repo.
# Di CI tak ada yang staged (index = HEAD), jadi periksa staged + SEMUA
# berkas tracked; dulu hanya `git diff --cached` sehingga gate buta di runner.
POLA_TERLARANG='\.(apk|aab|zip|jks|keystore|p12|pfx|so|a|dex)$|/vaultwarden-armeabi|/libgetrandom-shim-|(^|/)keystore|(^|/)\.env(\.|$)|app/src/main/assets/bin/|(^|/)vw_version\.txt$'
TERLARANG="$(
  git diff --cached --name-only --diff-filter=ACMR 2>/dev/null || true
  git ls-files 2>/dev/null || true
)"
if [ -n "$(printf '%s\n' "$TERLARANG" | sort -u | grep -E "$POLA_TERLARANG" || true)" ]; then
  gagal_msg "binary/APK/zip/secret ada di repo/staged (apk|aab|zip|jks|keystore|p12|pfx|so|a|dex|assets/bin|vw_version.txt|.env dilarang)"
fi
# 4. Jumlah section README Inggris vs Indonesia vs Rusia wajib sama (jaga sinkron).
# Hitung via fungsi: `a=$(rg -c ... || grep -c ...)` dulu gagal di bawah set -e
# saat tak ada heading '## ' (rg & grep sama-sama keluar 1) sehingga cek rahasia
# di bawahnya tak pernah jalan. Berkas hilang/heading kosong dihitung 0.
hitung_judul() {
  local n
  n="$(rg -c '^## ' "$1" 2>/dev/null || true)"
  if [ -z "$n" ]; then
    n="$(grep -c '^## ' "$1" 2>/dev/null || true)"
  fi
  echo "${n:-0}"
}
a=$(hitung_judul README.md)
b=$(hitung_judul README.id.md)
c=$(hitung_judul README.ru.md)
if [ "$a" != "$b" ]; then gagal_msg "jumlah ## README.md ($a) != README.id.md ($b)"; fi
if [ "$a" != "$c" ]; then gagal_msg "jumlah ## README.md ($a) != README.ru.md ($c)"; fi
# 5. Isi berkas tracked/staged: pola rahasia nyata (private key, token cloud).
# Nama berkas saja tak cukup: token yang ditempel di kode/dokumen tetap bocor
# walau namanya bukan keystore/.env. --cached = index (HEAD di CI, staged di
# lokal), -I melewati berkas biner. Pola sengaja spesifik agar tak berisik;
# header PEM diakhiri baris ($) supaya kode yang MENULIS header (TlsCert) lolos.
POLA_RAHASIA='-----BEGIN [A-Z0-9 ]*PRIVATE KEY-----$|gh[pousr]_[A-Za-z0-9]{30,}|github_pat_[A-Za-z0-9_]{40,}|AKIA[0-9A-Z]{16}|xox[abp]-[0-9A-Za-z-]{10,}|[0-9]{8,10}:AA[A-Za-z0-9_-]{30,}'
RAHASIA="$(git grep --cached -I -l -E -- "$POLA_RAHASIA" 2>/dev/null || true)"
if [ -n "$RAHASIA" ]; then
  gagal_msg "isi berkas memuat pola rahasia (private key/token): $(printf '%s' "$RAHASIA" | tr '\n' ' ')"
fi
exit $gagal
