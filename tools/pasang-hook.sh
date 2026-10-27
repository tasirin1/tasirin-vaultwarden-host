#!/bin/sh

set -e
HOOK_DIR=$(git rev-parse --git-path hooks 2>/dev/null) || {
    echo "GAGAL: jalankan skrip ini dari dalam repo git (git rev-parse gagal)." >&2
    exit 1
}
HOOK="$HOOK_DIR/pre-push"
mkdir -p "$HOOK_DIR"
sementara="$HOOK.baru-$$"
cat > "$sementara" <<'HOOK'
#!/bin/sh

if [ "$1" = "--self-test" ]; then
    exit 0
fi
if ! python3 tools/verifikasi-patch.py; then exit 1; fi
exec bash scripts/cek-cepat.sh
HOOK
if [ -f "$HOOK" ] && ! cmp -s "$sementara" "$HOOK"; then
    cadang="$HOOK.bak"
    n=1
    while [ -e "$cadang" ]; do
        cadang="$HOOK.bak.$n"
        n=$((n + 1))
    done
    if ! cp -p "$HOOK" "$cadang"; then
        echo "GAGAL: hook lama tak bisa dicadangkan ke $cadang; hook tidak diubah." >&2
        rm -f "$sementara"
        exit 1
    fi
    echo "Hook pre-push lama dicadangkan: $cadang"
fi
cat "$sementara" > "$HOOK"
rm -f "$sementara"
chmod +x "$HOOK"
if "$HOOK" --self-test 2>/dev/null; then
    echo "Kait pre-push terpasang dan terbukti bisa dieksekusi: $HOOK"
else
    echo "PERINGATAN: perintah $HOOK ditolak kernel (penyimpanan noexec?) — kait"
    echo "  git diabaikan di sini walau bit +x ada. Cadangan: job Verifikasi Patch"
    echo "  di kedua workflow CI tetap memeriksa tiap push; atau jalankan manual"
    echo "  sebelum commit: python3 tools/verifikasi-patch.py"
fi
