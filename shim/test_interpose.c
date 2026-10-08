// Uji interposisi shim via LD_PRELOAD (jalan di runner CI, host x86_64).
// Dua jalur yang dipakai binary Vaultwarden di kernel STB lama:
//  1) getrandom() libc (std Rust), dan
//  2) syscall(SYS_getrandom, ...) mentah (getrandom 0.2 via ring 0.17 ke
//     ticketer TLS Rocket rustls — jalur yang merusak HTTPS walau HTTP jalan).
// Keduanya dipanggil dengan flag bogus: libc/kernel asli menolak (EINVAL),
// shim harus tetap melayani 32 byte acak. Return 0 = lolos.
#include <stdio.h>
#include <unistd.h>
#include <sys/random.h>
#include <sys/syscall.h>

#ifndef SYS_getrandom
#if defined(__x86_64__)
#define SYS_getrandom 318
#elif defined(__aarch64__)
#define SYS_getrandom 278
#elif defined(__arm__)
#define SYS_getrandom 384
#elif defined(__i386__)
#define SYS_getrandom 355
#else
#error "SYS_getrandom tak dikenal di arch ini"
#endif
#endif

static unsigned long cek(const char *nama, unsigned char *b) {
    unsigned long x = 0;
    for (int i = 0; i < 32; i++) {
        x += b[i];
    }
    printf("%s checksum=%lu\n", nama, x);
    return x;
}

// Kembalikan 1 bila seluruh 32 byte bernilai sama (acak palsu/rusak).
static int semua_byte_sama(unsigned char *b) {
    for (int i = 1; i < 32; i++) {
        if (b[i] != b[0]) {
            return 0;
        }
    }
    return 1;
}

int main(void) {
    unsigned char b[32];
    unsigned char c[32];
    ssize_t n = getrandom(b, sizeof b, 0xFFFFu);
    if (n != 32) {
        printf("GAGAL getrandom n=%zd\n", n);
        return 1;
    }
    long m = syscall(SYS_getrandom, c, sizeof c, 0xFFFFu);
    if (m != 32) {
        printf("GAGAL syscall(SYS_getrandom) m=%ld\n", m);
        return 1;
    }
    unsigned long x = cek("getrandom", b);
    unsigned long y = cek("syscall", c);
    printf("OK n=%zd m=%ld\n", n, m);
    // Tolak bila kosong (all-zero), dua jalur kembar persis (x==y, bukan acak
    // segar), atau tiap buffer semua-bytenya sama (mis. 0xFF semua).
    if (x == 0 || y == 0) {
        printf("GAGAL: entropi nol\n");
        return 1;
    }
    if (x == y) {
        printf("GAGAL: dua jalur acak identik\n");
        return 1;
    }
    if (semua_byte_sama(b) || semua_byte_sama(c)) {
        printf("GAGAL: buffer semua-byte-sama\n");
        return 1;
    }
    return 0;
}
