#!/usr/bin/env bash

set -euo pipefail
gagal=0

if [ "${GITHUB_ACTIONS:-}" = "true" ]; then ANO_ERR="::error::"; else ANO_ERR=""; fi
gagal_msg() { echo "${ANO_ERR}GAGAL: $*"; gagal=1; }

if ! git rev-parse --is-inside-work-tree >/dev/null 2>&1 || ! git ls-files >/dev/null 2>&1; then
  gagal_msg "git tak bisa dipakai di sini - cek berbasis git (biner terlarang, pola rahasia) tak berjalan, hasil tak bisa dipercaya; jalankan dari clone git yang sehat"
fi

if rg --version >/dev/null 2>&1; then ADA_RG=1; else ADA_RG=0; fi

if [ "$ADA_RG" = 1 ] && rg --pcre2 -n '' app/src/main/res/drawable >/dev/null 2>&1; then

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

struktur_judul() {
  awk '/^#{1,6} / { n = 0; while (substr($0, n + 1, 1) == "#") n++; printf "%d,", n }' "$1" 2>/dev/null
}
for r in README.md README.id.md README.ru.md; do
  if [ ! -f "$r" ]; then gagal_msg "berkas wajib hilang: $r"; fi
done
a=$(struktur_judul README.md || true)
b=$(struktur_judul README.id.md || true)
c=$(struktur_judul README.ru.md || true)
if [ "$a" != "$b" ]; then gagal_msg "struktur judul README.md ($a) != README.id.md ($b)"; fi
if [ "$a" != "$c" ]; then gagal_msg "struktur judul README.md ($a) != README.ru.md ($c)"; fi

POLA_TOKEN='gh[pousr]_[A-Za-z0-9]{30,}|github_pat_[A-Za-z0-9_]{40,}|AKIA[0-9A-Z]{16}|xox[abp]-[0-9A-Za-z-]{10,}|[0-9]{8,10}:AA[A-Za-z0-9_-]{30,}'
POLA_RAHASIA="^[[:space:]]*-----BEGIN [A-Za-z0-9[:blank:]]*PRIVATE KEY-----|$POLA_TOKEN"
# Audit 1505: pola bungkus juga menoleransi sisipan di TENGAH
# frasa (PRIVATE ... KEY), bukan hanya di antara BEGIN dan
# frasa — belahan di tengah frasa dulu lolos.
# Audit 1529: toleransi yang sama sesudah KEY — sisipan di
# antara KEY dan '-----' (satu baris 'xyz' di antaranya)
# dulu mematahkan pola walau badan base64-nya utuh.
# Audit 1544: celah header ikut menoleransi '-' dan
# '_' — baris '-----' persis (yang dipertahankan
# Audit 1528 untuk penutup header) justru bisa
# duduk sebagai sela di antara BEGIN dan PRIVATE
# KEY, dan kata sela bergaris bawah ('jeda_sesaat')
# mematahkan celah alfanumerik murni.
POLA_BUNGKUS="$POLA_TOKEN|-----BEGIN[A-Za-z0-9_-]{0,200}PRIVATE[A-Za-z0-9_-]{0,200}KEY[A-Za-z0-9_-]{0,200}-----[A-Za-z0-9+/=]{16,}"
RAHASIA="$( { git -c core.quotepath=false grep --cached -I -l -E -- "$POLA_RAHASIA" 2>/dev/null; git -c core.quotepath=false grep -I -l -E -- "$POLA_RAHASIA" 2>/dev/null; } | sort -u || true)"
# Audit 1458: satu lintasan untuk dua cabang — entri bertanda t:
# (terlacak) / u: (tak terlacak/terabaikan); berkas u: juga diuji
# pola per-baris di lintasan yang sama (dulu loop terpisah
# memindai berkas tak terlacak dua kali).
BUNGKUS="$({ git -c core.quotepath=false ls-files -z | sed -z 's/^/t:/'; git -c core.quotepath=false ls-files --others --exclude-standard -z | sed -z 's/^/u:/'; git -c core.quotepath=false ls-files --others --ignored --exclude-standard -z | sed -z 's/^/u:/'; } | sort -zu | while IFS= read -r -d '' entri; do
  f=${entri#?:}
  # Audit 1505/1506: komentar yang menyela belahan mematahkan
  # kedua pola bungkus (header maupun token).
  # Audit 1526: yang dibuang hanya AKSARA '#' itu sendiri,
  # BUKAN isi sesudahnya — cara lama ('s/#.*//') menelan
  # potongan rahasia yang duduk sesudah '#' di baris yang
  # sama (regresi: bentuk "warna #fff token: <paruh>" dan
  # "# x -----BEGIN ..." lolos, padahal tertangkap sebelum
  # Audit 1505). Kata komentar yang tersisa adalah pengisi
  # alfanumerik yang ditoleransi kedua pola.
  # Audit 1528: baris komentar '//' dan baris tanda-baca
  # murni (tanpa alfanumerik) juga dibuang sebelum
  # perataan — menyela belahan dengan '***', '...', atau
  # '// jeda' dulu mematahkan kedua pola. Baris '-----'
  # persis DIPERTAHANKAN: itu penutup header kunci, bukan
  # hiasan.
  # Audit 1543: sela CAMPURAN (kata + simbol) ikut
  # ditangani — aksara pemutus '!', ';', '*', '.'
  # dibuang dari semua baris (tak satu pun anggota
  # charset token/header/badan base64); ':' yang diikuti
  # spasi dinetralkan kecuali pada baris berbentuk token
  # (komentar '# catatan: penting' dulu mematahkannya).
  # Audit 1547: penanda komentar blok TIDAK lagi membuang
  # barisnya — cara 1543 menelan baris PEMBAWA rahasia
  # ('* -----BEGIN ...' sebagai bullet/komentar blok
  # lolos). Kini hanya penandanya yang dikupas (pola
  # Audit 1526 untuk '#'): isi baris tetap diperiksa.
  if [ -f "$f" ] && awk '{ sub(/^[[:space:]]*\/\*/, ""); sub(/^[[:space:]]*\*+/, ""); sub(/\*\/[[:space:]]*$/, ""); gsub(/#/, ""); gsub(/[!;*.]/, ""); if ($0 !~ /[0-9]{8,10}:AA/) { gsub(/:[[:space:]]/, " "); } s = $0; gsub(/[[:space:]\r]/, "", s); if ($0 ~ /^[[:space:]]*\/\//) next; if (s != "-----" && s ~ /^[^A-Za-z0-9]*$/) next; print }' "$f" 2>/dev/null | tr -d '\n\r \t' | grep -q -E -- "$POLA_BUNGKUS"; then
    printf '%s\n' "$f"
  fi
  if [ "${entri%%:*}" = "u" ] && [ -f "$f" ] && grep -q -E -- "$POLA_RAHASIA" "$f" 2>/dev/null; then
    printf '%s\n' "$f"
  fi
done || true)"
RAHASIA="$(printf '%s\n%s\n' "$RAHASIA" "$BUNGKUS" | sed '/^$/d' | sort -u || true)"
if [ -n "$RAHASIA" ]; then
  gagal_msg "isi berkas memuat pola rahasia (private key/token): $(printf '%s' "$RAHASIA" | tr '\n' ' ')"
fi
exit $gagal
