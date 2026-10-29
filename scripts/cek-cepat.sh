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
for f in app/src/main/res/layout-land/*.xml; do
  [ -f "$f" ] || continue
  potret="app/src/main/res/layout/$(basename "$f")"
  if [ ! -f "$potret" ]; then gagal_msg "layout-land yatim tanpa pasangan: $f"; fi
done
for wajib in activity_pin.xml; do
  if [ ! -f "app/src/main/res/layout/$wajib" ]; then gagal_msg "layout wajib hilang: $wajib"; fi
  if [ ! -f "app/src/main/res/layout-land/$wajib" ]; then gagal_msg "layout-land wajib hilang: $wajib (varian landscape berkas ini wajib ada)"; fi
done

POLA_TERLARANG='\.(apk|aab|zip|jks|keystore|p12|pfx|so|a|dex)$|/vaultwarden-armeabi|/libgetrandom-shim-|(^|/)keystore|(^|/)\.env(\.|$)|app/src/main/assets/bin/|(^|/)vw_version\.txt$'
TERLARANG="$(
  git diff --cached --name-only --diff-filter=ACMR 2>/dev/null || true
  git ls-files 2>/dev/null || true
  git ls-files --others --exclude-standard 2>/dev/null || true
)"
if [ -n "$(printf '%s\n' "$TERLARANG" | sort -u | grep -iE "$POLA_TERLARANG" || true)" ]; then
  gagal_msg "binary/APK/zip/secret ada di repo/staged (apk|aab|zip|jks|keystore|p12|pfx|so|a|dex|assets/bin|vw_version.txt|.env dilarang)"
fi
biner_bungkus() {
  od -An -tx1 -v "$1" 2>/dev/null | tr -d ' \n' | grep -qiE '7f454c46|504b0304|6465780a|cffaedfe|cefaedfe|cafebabe'
}
while IFS= read -r berkas_b; do
  [ -f "$berkas_b" ] || continue
  [ "$berkas_b" = "gradle/wrapper/gradle-wrapper.jar" ] && continue
  if [ -s "$berkas_b" ] && ! grep -qI . "$berkas_b" 2>/dev/null; then
    sig_b="$(head -c 12 "$berkas_b" | od -An -tx1 | tr -d ' \n')"
    ext_b=".$(printf '%s' "${berkas_b##*.}" | tr 'A-Z' 'a-z')"
    pasif_b=0
    case "$sig_b" in
      89504e47*) [ "$ext_b" = ".png" ] && pasif_b=1 ;;
      ffd8ff*) case "$ext_b" in .jpg|.jpeg) pasif_b=1 ;; esac ;;
      47494638*) [ "$ext_b" = ".gif" ] && pasif_b=1 ;;
      25504446*) [ "$ext_b" = ".pdf" ] && pasif_b=1 ;;
      00000100*) [ "$ext_b" = ".ico" ] && pasif_b=1 ;;
      52494646????????57454250*) [ "$ext_b" = ".webp" ] && pasif_b=1 ;;
    esac
    if [ "$pasif_b" = "1" ] && biner_bungkus "$berkas_b"; then
      pasif_b=0
    fi
    if [ "$pasif_b" != "1" ]; then
      gagal_msg "berkas biner format tak dikenal: $berkas_b"
    fi
  fi
done <<EOF_B
$(printf '%s\n' "$TERLARANG" | sort -u)
EOF_B

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
RAHASIA="$( { git grep --cached -I -l -E -- "$POLA_RAHASIA" 2>/dev/null; git grep -I -l -E -- "$POLA_RAHASIA" 2>/dev/null; } | sort -u || true)"
BUNGKUS="$({ git ls-files; git ls-files --others --exclude-standard; } | sort -u | while IFS= read -r f; do
  if [ -f "$f" ] && tr -d '\n\r' < "$f" 2>/dev/null | grep -q -E -- "$POLA_TOKEN"; then
    printf '%s\n' "$f"
  fi
done || true)"
RAHASIA="$(printf '%s\n%s\n' "$RAHASIA" "$BUNGKUS" | sed '/^$/d' | sort -u || true)"
BARU_RAHASIA="$(git ls-files --others --exclude-standard | while IFS= read -r f; do
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
