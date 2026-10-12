#!/bin/sh
# Pasang kait pre-push: tiap `git push` otomatis menjalankan verifikasi patch.
# Bila verifikasi GAGAL, push dibatalkan sebelum menyentuh GitHub/CI.
# Dipasang sekali per clone: sh tools/pasang-hook.sh
set -e
HOOK=".git/hooks/pre-push"
cat > "$HOOK" <<'HOOK'
#!/bin/sh
# Kait otomatis (dipasang tools/pasang-hook.sh): verifikasi patch + cek cepat
# yang akan di-push. Keluar bukan-nol = push dibatalkan.
if ! python3 tools/verifikasi-patch.py; then exit 1; fi
exec bash scripts/cek-cepat.sh
HOOK
chmod +x "$HOOK"
if [ -x "$HOOK" ]; then
    echo "Kait pre-push terpasang dan bisa dieksekusi: $HOOK"
else
    echo "PERINGATAN: $HOOK tak bisa dieksekusi (penyimpanan noexec?) — kait"
    echo "  git diabaikan di sini. Cadangan: workflow Verifikasi Patch di CI"
    echo "  tetap memeriksa tiap push; atau jalankan manual sebelum commit:"
    echo "  python3 tools/verifikasi-patch.py"
fi
