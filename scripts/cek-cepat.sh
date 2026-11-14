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
# Audit 1554: dua celah DALAM label (BEGIN..PRIVATE
# dan PRIVATE..KEY) kini hanya menoleransi strip
# sebagai RANGKAIAN ('--' ke atas), bukan strip
# tunggal — pelebaran 1544 membuat label contoh
# berstrip pada dokumen jinak ('-----BEGIN
# CONTOH-PRIVATE-KEY-----') cocok sebagai header
# sungguhan bila berkas yang sama memuat hash heks
# 40 aksara (positif palsu). Sela rangkaian strip
# ('-----', '-- jeda') tetap tertangkap; sela kata
# berstrip tunggal di dalam label kini lolos
# (diterima — label asli tak pernah berstrip).
POLA_BUNGKUS="$POLA_TOKEN|-----BEGIN([A-Za-z0-9_]|--+){0,200}PRIVATE([A-Za-z0-9_]|--+){0,200}KEY[A-Za-z0-9_-]{0,200}-----[A-Za-z0-9+/=]{16,}"
RAHASIA="$( { git -c core.quotepath=false grep --cached -I -l -E -- "$POLA_RAHASIA" 2>/dev/null; git -c core.quotepath=false grep -I -l -E -- "$POLA_RAHASIA" 2>/dev/null; } | sort -u || true)"
# Audit 1458: satu lintasan untuk dua cabang — entri bertanda t:
# (terlacak) / u: (tak terlacak/terabaikan); berkas u: juga diuji
# pola per-baris di lintasan yang sama (dulu loop terpisah
# memindai berkas tak terlacak dua kali).
  # Audit 1505/1506: komentar yang menyela belahan mematahkan
  # kedua pola bungkus (header maupun token).
  # Audit 1526: yang dibuang hanya AKSARA '#' itu sendiri,
  # BUKAN isi sesudahnya — cara lama ('s/#.*//') menelan
  # potongan rahasia yang duduk sesudah '#' di baris yang
  # sama (regresi: bentuk "warna #fff token: <paruh>" dan
  # "# x -----BEGIN ..." lolos, padahal tertangkap sebelum
  # Audit 1505). Kata komentar yang tersisa adalah pengisi
  # alfanumerik yang ditoleransi kedua pola.
  # Audit 1528: baris tanda-baca murni (tanpa alfanumerik)
  # dibuang sebelum perataan — menyela belahan dengan
  # '***' atau '...' dulu mematahkan kedua pola. Baris
  # '-----' persis DIPERTAHANKAN: itu penutup header
  # kunci, bukan hiasan. (Baris komentar '//' TIDAK
  # dibuang: isinya diperiksa — lihat Audit 1567.)
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
  # Audit 1553: himpunan aksara buang diperluas ke sela
  # campuran yang terprobe lolos: ',' '/' '<' '>' '|'
  # '\' dan apostrof (lewat escape oktal "\047" karena
  # program awk berbalut petik tunggal) — komentar HTML
  # '<!-- jeda -->', sela 'dan/atau', '# don't dulu',
  # dan '|'/'\' sebaris kini ikut menyatu. Kelasnya
  # pada dasarnya terbuka; ini himpunan yang realistis.
  # Audit 1552: pengecualian penetralan ':' diganti —
  # dulu baris berbentuk token dikecualikan utuh, jadi
  # belahan PERSIS di ':'+spasi lolos (titik duanya ikut
  # dinetralkan karena barisnya tak memuat token utuh).
  # Kini ':' yang didahului DIGIT (ciri titik dua token)
  # dilindungi per aksara lewat SUBSEP sebelum
  # penetralan, lalu dipulihkan — titik dua prosa
  # (didahului huruf) tetap dinetralkan.
  # Audit 1560: perlindungan diganti kontekstual — cara
  # 1552 melindungi ':' sesudah DIGIT APA PUN di SEMUA baris,
  # jadi baris sela prosa berisi jam/versi ('# selesai
  # 12: 30', '# versi 2: final') ikut dilindungi dan titik
  # duanya mendarat di blob di antara paruh rahasia,
  # mematahkan kedua pola (REGRESI, terprobe rc=0). Kini
  # yang dilindungi hanya ':' yang didahului >=5 digit
  # beruntun (bentuk titik dua token itu sendiri) — titik
  # dua jam (2 digit) dan versi (1 digit) tetap dinetralkan.
  # Audit 1565: perlindungan 1560 masih lebih sempit
  # daripada penetralan dalam dua bentuk — (a) titik dua
  # token di AWAL baris kedua dengan digitnya terbelah
  # (ekor digit baris sebelumnya + kepala digit baris ini
  # harus dijumlahkan; dilacak lewat 'ekordgt'), dan
  # (b) titik dua diikuti TAB (perlindungan hanya
  # mencocokkan spasi persis, penetralan [[:space:]]).
  # Keduanya kini dicakup: kelas penetralan yang sama
  # dipakai perlindungan, plus aturan lintas-baris itu.
  # Audit 1566: byte SUBSEP (\034) yang SUDAH ada di
  # berkas sumber dibuang lebih dulu sebelum perlindungan
  # menandai apa pun — langkah pulih lama menulis ulang
  # SEMUA byte itu menjadi ':', sehingga byte bawaan bisa
  # "menempa" titik dua token dari ketiadaan (positif
  # palsu) atau menelan belahan di baris sela (lolos).
  # Sesudah pembuangan ini, satu-satunya SUBSEP di blob
  # adalah tanda perlindungan sendiri.
  # Audit 1567: pemeriksaan buang-baris '//' yang dulu
  # ada di program awk ini MATI sejak Audit 1553 ('/'
  # dibuang sebagai aksara lebih dulu, jadi pola
  # '^//'-nya tak pernah bisa cocok) — kini dihapus
  # tuntas. Perilaku yang berlaku dan SENGAJA: baris
  # '//' tidak dibuang; garis miringnya dikupas sebagai
  # tanda baca dan isinya ikut diperiksa (header di baris
  # '// -----BEGIN ...' tertangkap). Terkunci skenario
  # uji 1567.
  # Audit 1584: ekordgt diukur pada salinan baris yang
  # ekor whitespace-nya (spasi/tab/CR) sudah dibuang —
  # whitespace itu dibuang dari blob oleh perataan,
  # jadi baris berkas CRLF tetap dihitung berakhir
  # digit.
  # Audit 1585: baris yang seluruh isinya digit
  # MENAMBAHKAN ekordgt lama (deret digit token bisa
  # terbelah lebih dari dua baris), bukan menimpanya.
  # Audit 1586: baris yang dibuang (kosong/tanda-baca
  # murni) TIDAK menyentuh ekordgt — baris itu
  # transparan di blob, jadi rantai digit
  # lintas-barisnya utuh.
# Audit 1595/1625: SATU proses awk untuk semua berkas (dulu
# awk+tr+grep per berkas: 3 proses x jumlah berkas, program
# awk di-parse ulang tiap berkas). Program per barisnya sama
# persis dengan program lama (kini berbentuk fungsi proses());
# blob tiap berkas dirangkai di dalam awk lalu dicocokkan ke
# POLA_BUNGKUS sekali per berkas. Daftar berkas diteruskan
# sebagai argumen awk dari array bash aliran -z yang sama,
# jadi nama berkas ber-newline tetap utuh (Audit 1454).
# Audit 1626: pengukuran ekordgt hanya membayar salinan ekort
# bila baris memuat digit — baris tanpa digit pasti berakhir
# ekordgt 0 di kedua cabang lama, jadi pintasannya setara.
mapfile -d '' ENTRI_BUNGKUS < <({ git -c core.quotepath=false ls-files -z | sed -z 's/^/t:/'; git -c core.quotepath=false ls-files --others --exclude-standard -z | sed -z 's/^/u:/'; git -c core.quotepath=false ls-files --others --ignored --exclude-standard -z | sed -z 's/^/u:/'; } | sort -zu)
DAFTAR_BUNGKUS=()
for entri in "${ENTRI_BUNGKUS[@]}"; do
  f=${entri#?:}
  if [ -f "$f" ]; then
    DAFTAR_BUNGKUS+=("$f")
  fi
done
BUNGKUS="$(awk -v POLA="$POLA_BUNGKUS" '
function proses(line,   awal, hd, sp, pos, s, ekort) {
  $0 = line
  sub(/^[[:space:]]*\/\*/, ""); sub(/^[[:space:]]*\*+/, ""); sub(/\*\/[[:space:]]*$/, "")
  gsub(/\\[tnr]/, "")
  gsub(/#/, ""); gsub(/[!;*.,\/<>|\\]/, ""); gsub("\047", ""); gsub(SUBSEP, "")
  gsub(/[@=+%&~$^?{}\[\]]/, "")
  gsub("\357\273\277", "")
  gsub("\302\240", ""); gsub("\342\200\203", "")
  awal = $0; sub(/^[[:space:]]*/, "", awal); hd = 0
  if (match(awal, /^[0-9]+/)) hd = RLENGTH
  if (ekordgt + hd >= 5 && substr(awal, hd + 1, 1) == ":" && substr(awal, hd + 2, 1) ~ /[[:space:]]/) {
    sp = length($0) - length(awal); pos = sp + hd + 1
    $0 = substr($0, 1, pos - 1) SUBSEP substr($0, pos + 2)
  }
  while (match($0, /[0-9][0-9][0-9][0-9][0-9]:[[:space:]]/)) {
    $0 = substr($0, 1, RSTART + 4) SUBSEP substr($0, RSTART + 7)
  }
  gsub(/:[[:space:]]/, " "); gsub(SUBSEP, ":")
  s = $0; gsub(/[[:space:]\r]/, "", s)
  if (s != "-----" && s ~ /^[^A-Za-z0-9]*$/) { return "" }
  if ($0 ~ /[0-9]/) {
    ekort = $0; sub(/[ \t\r]+$/, "", ekort)
    if (ekort ~ /^[0-9]+$/) { ekordgt = ekordgt + length(ekort) }
    else { ekordgt = 0; if (match(ekort, /[0-9]+$/)) ekordgt = RLENGTH }
  } else { ekordgt = 0 }
  gsub(/[ \t\r]/, "", $0)
  return $0
}
BEGIN {
  for (i = 1; i < ARGC; i++) {
    f = ARGV[i]
    blob = ""; ekordgt = 0
    while ((getline baris < f) > 0) { blob = blob proses(baris) }
    close(f)
    if (blob ~ POLA) { print f }
  }
  exit
}' "${DAFTAR_BUNGKUS[@]}" || true)"
for entri in "${ENTRI_BUNGKUS[@]}"; do
  f=${entri#?:}
  if [ "${entri%%:*}" = "u" ] && [ -f "$f" ] && grep -q -E -- "$POLA_RAHASIA" "$f" 2>/dev/null; then
    BUNGKUS="$(printf '%s\n%s\n' "$BUNGKUS" "$f")"
  fi
done
RAHASIA="$(printf '%s\n%s\n' "$RAHASIA" "$BUNGKUS" | sed '/^$/d' | sort -u || true)"
if [ -n "$RAHASIA" ]; then
  gagal_msg "isi berkas memuat pola rahasia (private key/token): $(printf '%s' "$RAHASIA" | tr '\n' ' ')"
fi
exit $gagal
