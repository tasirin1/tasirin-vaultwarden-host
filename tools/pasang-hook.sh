#!/bin/sh
# Pasang kait pre-push: tiap `git push` otomatis menjalankan verifikasi patch.
# Bila verifikasi GAGAL, push dibatalkan sebelum menyentuh GitHub/CI.
# Dipasang sekali per clone: sh tools/pasang-hook.sh
#
# Dua jebakan yang ditangani di sini:
# 1. `chmod +x` + `[ -x ]` hanya membuktikan BIT IZIN, bukan opsi mount.
#    Di penyimpanan noexec (/sdcard, tempat repo ini sering berada) chmod
#    tetap berhasil tetapi execve ditolak kernel -> git mengabaikan hook
#    tanpa pesan. Karena itu hook dijalankan sungguhan (--self-test).
# 2. Hook pre-push milik tool lain dulu ditimpa tanpa jejak. Sekarang
#    dicadangkan ke pre-push.bak (atau .bak.N) lebih dulu.
set -e
HOOK=".git/hooks/pre-push"
if [ ! -d .git ]; then
    echo "GAGAL: jalankan skrip ini dari akar repo (folder .git tidak ada)." >&2
    exit 1
fi
if [ -f "$HOOK" ] && ! grep -qF "Kait otomatis (dipasang tools/pasang-hook.sh)" "$HOOK"; then
    cadang="$HOOK.bak"
    n=1
    while [ -e "$cadang" ]; do
        cadang="$HOOK.bak.$n"
        n=$((n + 1))
    done
    if ! cp -p "$HOOK" "$cadang"; then
        echo "GAGAL: hook lama tak bisa dicadangkan ke $cadang; hook tidak diubah." >&2
        exit 1
    fi
    echo "Hook pre-push lama dicadangkan: $cadang"
fi
cat > "$HOOK" <<'HOOK'
#!/bin/sh
# Kait otomatis (dipasang tools/pasang-hook.sh): verifikasi patch + cek cepat
# yang akan di-push. Keluar bukan-nol = push dibatalkan.
#
# --self-test: keluar 0 segera. Dipakai pemasang untuk membuktikan berkas ini
# benar-benar bisa dieksekusi (bukan hanya ber-bit +x di mount noexec).
if [ "$1" = "--self-test" ]; then
    exit 0
fi
if ! python3 tools/verifikasi-patch.py; then exit 1; fi
exec bash scripts/cek-cepat.sh
HOOK
chmod +x "$HOOK"
if "$HOOK" --self-test 2>/dev/null; then
    echo "Kait pre-push terpasang dan terbukti bisa dieksekusi: $HOOK"
else
    echo "PERINGATAN: perintah $HOOK ditolak kernel (penyimpanan noexec?) — kait"
    echo "  git diabaikan di sini walau bit +x ada. Cadangan: job Verifikasi Patch"
    echo "  di kedua workflow CI tetap memeriksa tiap push; atau jalankan manual"
    echo "  sebelum commit: python3 tools/verifikasi-patch.py"
fi
