#!/bin/sh
# Pasang kait pre-push: tiap `git push` otomatis menjalankan verifikasi path.
# Bila verifikasi GAGAL, push dibatalkan sebelum menyentuh GitHub/CI.
# Dipasang sekali per clone: sh tools/pasang-hook.sh
set -e
HOOK=".git/hooks/pre-push"
cat > "$HOOK" <<'HOOK'
#!/bin/sh
# Kait otomatis (dipasang tools/pasang-hook.sh): verifikasi path yang akan
# di-push. Keluar bukan-nol = push dibatalkan.
exec python3 tools/verifikasi-path.py
HOOK
chmod +x "$HOOK"
echo "Kait pre-push terpasang: $HOOK"
