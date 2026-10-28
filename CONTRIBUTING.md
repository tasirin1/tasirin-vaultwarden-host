# Contributing — Tasirin Vaultwarden Host

Terima kasih sudah mau berkontribusi! Panduan singkat ini menjaga repo tetap
rapi dan rilis selalu hijau. **Untuk AI yang mengelola repo: baca
[AGENTS.md](AGENTS.md) dulu — dokumen ini aturan ringkas untuk kontributor.**

## Alur kerja

1. **Satu commit, satu tujuan logis.** Gaya commit: `feat:` / `fix:` /
   `docs:` / `chore:` / `perf:` + deskripsi singkat Bahasa Indonesia.
2. **Dilarang keras build/test/lint lokal** — semua verifikasi penuh lewat
   GitHub Actions. Gerbang lokal tanpa toolchain wajib lolos sebelum push:
   `python3 tools/verifikasi-patch.py` dan `bash scripts/cek-cepat.sh`.
3. **Setiap perubahan penting dicatat di `CHANGELOG.md`.**
4. **Jangan ubah versi manual** — versi app berasal dari waktu build UTC.
5. **Jangan commit keystore/binary/APK** ke repo.
6. Bila fitur berubah, jaga `README.md`, `README.id.md`, dan `README.ru.md`
   tetap sinkron.

## PR Dependabot

- Update GitHub Actions (tidak menyentuh dependensi Gradle) di-auto-merge
  setelah CI hijau.
- Update dependensi Gradle **tidak** di-auto-merge — ditangani manual
  setelah gerbang Verifikasi Patch dan build hijau.

## Label otomatis & issue/PR lama

- Workflow `Labeler` memberi label otomatis per path (`app`, `ci`, `docs` —
  lihat `.github/labeler.yml`).
- Workflow `Stale` menandai issue/PR tidak aktif (issue 45 hari, PR 21
  hari) dan menutupnya bila 14 hari tidak ada respons.

Kerentanan keamanan tidak boleh dibuat sebagai issue publik; ikuti
[SECURITY.md](SECURITY.md).
