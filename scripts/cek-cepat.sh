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
a=$(struktur_judul README.md)
b=$(struktur_judul README.id.md)
c=$(struktur_judul README.ru.md)
if [ "$a" != "$b" ]; then gagal_msg "struktur judul README.md ($a) != README.id.md ($b)"; fi
if [ "$a" != "$c" ]; then gagal_msg "struktur judul README.md ($a) != README.ru.md ($c)"; fi

POLA_TOKEN='gh[pousr]_[A-Za-z0-9]{30,}|github_pat_[A-Za-z0-9_]{40,}|AKIA[0-9A-Z]{16}|xox[abp]-[0-9A-Za-z-]{10,}|[0-9]{8,10}:AA[A-Za-z0-9_-]{30,}'
POLA_RAHASIA="^[[:space:]]*-----BEGIN [A-Z0-9 ]*PRIVATE KEY-----|$POLA_TOKEN"
RAHASIA="$( { git -c core.quotepath=false grep --cached -I -l -E -- "$POLA_RAHASIA" 2>/dev/null; git -c core.quotepath=false grep -I -l -E -- "$POLA_RAHASIA" 2>/dev/null; } | sort -u || true)"
BUNGKUS="$({ git -c core.quotepath=false ls-files; git -c core.quotepath=false ls-files --others --exclude-standard; } | sort -u | while IFS= read -r f; do
  if [ -f "$f" ] && tr -d '\n\r' < "$f" 2>/dev/null | grep -q -E -- "$POLA_TOKEN"; then
    printf '%s\n' "$f"
  fi
done || true)"
RAHASIA="$(printf '%s\n%s\n' "$RAHASIA" "$BUNGKUS" | sed '/^$/d' | sort -u || true)"
BARU_RAHASIA="$(git -c core.quotepath=false ls-files --others --exclude-standard | while IFS= read -r f; do
  if [ -f "$f" ] && grep -q -E -- "$POLA_RAHASIA" "$f" 2>/dev/null; then
    printf '%s\n' "$f"
  fi
done || true)"
if [ -n "$BARU_RAHASIA" ]; then
  RAHASIA="$(printf '%s\n%s\n' "$RAHASIA" "$BARU_RAHASIA" | sed '/^$/d' | sort -u || true)"
fi
if [ -n "$RAHASIA" ]; then
  gagal_msg "isi berkas memuat pola rahasia (private key/token): $(printf '%s' "$RAHASIA" | tr '\n' ' ')"
fi
exit $gagal
