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
rentang_semua=""
perlu_fallback=0
while read -r ref_lokal sha_lokal ref_remote sha_remote; do
    # Audit 2928: sha_lokal nol semua = penghapusan
    # branch - tak ada konten baru; membentuk rentang
    # ke sha nol hanya memicu fallback tak terkait.
    case "$sha_lokal" in
        ""|0000000000000000000000000000000000000000) continue ;;
    esac
    case "$sha_remote" in
        ""|0000000000000000000000000000000000000000)
            # Audit 3030: ref BARU (branch/tag yang belum
            # ada di remote) - dulu dilewati diam-diam
            # sehingga push campuran (ref lama + ref
            # baru) tak pernah memindai isi ref baru.
            # Rentangnya kini dibentuk dari titik temu
            # dengan origin/main; ref yang menunjuk
            # commit yang sudah di main tak menambah
            # rentang (jalurnya sudah terverifikasi);
            # tanpa titik temu, fallback ikut jalan.
            dasar=$(git merge-base origin/main "$sha_lokal" 2>/dev/null) || dasar=""
            if [ -n "$dasar" ] && [ "$dasar" != "$sha_lokal" ]; then
                rentang_semua="$rentang_semua $dasar..$sha_lokal"
            elif [ -z "$dasar" ]; then
                perlu_fallback=1
            fi
            ;;
        *) rentang_semua="$rentang_semua $sha_remote..$sha_lokal" ;;
    esac
done
if [ -n "$rentang_semua" ]; then
    for rentang in $rentang_semua; do
        if ! python3 tools/verifikasi-patch.py --rentang "$rentang"; then exit 1; fi
    done
    if [ "$perlu_fallback" = 1 ]; then
        if ! python3 tools/verifikasi-patch.py; then exit 1; fi
    fi
else
    if ! python3 tools/verifikasi-patch.py; then exit 1; fi
fi
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
