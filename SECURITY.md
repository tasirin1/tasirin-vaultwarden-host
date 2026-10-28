# Kebijakan Keamanan

## Versi yang didukung

Hanya rilis terbaru yang menerima perbaikan keamanan. Rilis lama
dipertahankan sebagai riwayat, tetapi tidak di-patch.

| Versi | Didukung |
|---|---|
| Rilis terbaru (`releases/latest`) | Ya |
| Rilis lama | Tidak (perbarui dulu, lalu uji ulang) |

## Melaporkan kerentanan

Jangan buat issue publik untuk kerentanan keamanan. Gunakan fitur
**Private vulnerability reporting** di tab Security repository GitHub ini.
Sertakan:

- versi APK atau commit yang diuji;
- langkah reproduksi singkat;
- dampak yang mungkin terjadi;
- proof-of-concept bila tersedia.

Laporan akan ditinjau dan pemilik repository akan menindaklanjuti lewat
GitHub dalam waktu 7 hari kerja. Kerentanan kritis akan diprioritaskan.

## Cakupan

Termasuk namun tidak terbatas pada:

- bypass PIN atau token admin Vaultwarden yang di-host aplikasi;
- kebocoran token bot Telegram, token admin, atau data vault lewat log,
  berkas bersama, atau antarmuka aplikasi;
- unduhan/pemasangan binary atau web-vault tanpa verifikasi SHA-256;
- eskalasi izin di luar yang dinyatakan manifest.

Di luar cakupan:

- serangan fisik setelah perangkat sudah root/tidak terkunci;
- keamanan instalasi Vaultwarden upstream itu sendiri (laporkan ke
  proyek dani-garcia/vaultwarden);
- laporan tanpa dampak nyata terhadap aplikasi atau datanya;
- hasil pemindaian otomatis tanpa analisis dan langkah reproduksi.

## Praktik keamanan maintainer

- Jangan pernah menyimpan atau mengirim `keystore.jks`, password signing,
  personal access token, atau API key di issue/PR/chat.
- Simpan secret hanya di GitHub Actions Secrets atau password manager.
- Rilis resmi hanya dari workflow GitHub Actions; fingerprint sertifikat
  APK diverifikasi otomatis terhadap konstanta di workflow.
