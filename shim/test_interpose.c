// Uji interposisi shim via LD_PRELOAD (jalan di runner CI, host x86_64).
// Tiga jalur yang dipakai binary Vaultwarden di kernel STB lama:
//  1) getrandom() libc (std Rust), dan
//  2) syscall(SYS_getrandom, ...) mentah (getrandom 0.2 via ring 0.17 ke
//     ticketer TLS Rocket rustls — jalur yang merusak HTTPS walau HTTP jalan),
//  3) syscall() non-getrandom (wajib diteruskan apa adanya; hook mencegat
//     seluruh simbol syscall sehingga penerusan yang rusak mematikan syscall
//     lain yang dipakai libc/Rust).
// Semua dipanggil dengan flag bogus: libc/kernel asli menolak (EINVAL),
// shim harus tetap melayani 32 byte acak. Return 0 = lolos.
#include <errno.h>
#include <stdio.h>
#include <string.h>
#include <unistd.h>
#include <sys/random.h>
#include <sys/syscall.h>

// Deklarasi eksplisit: pada header bionic (mis. Termux) getrandom hanya
// tersedia untuk API >= 28 sehingga uji host bisa gagal kompilasi walau
// glibc di runner sudah mendeklarasikannya. Signature sama persis.
extern ssize_t getrandom(void *buf, size_t buflen, unsigned int flags);

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
    // Tolak bila kosong (all-zero) atau tiap buffer semua-bytenya sama
    // (mis. 0xFF semua). Dua jalur TIDAK dibandingkan lewat checksum: jumlah
    // 32 byte acak punya ruang hanya ~8 ribu nilai sehingga tabrakan sesekali
    // menggagalkan CI tanpa bug nyata (flaky). Bandingkan isi buffer penuh.
    if (x == 0 || y == 0) {
        printf("GAGAL: entropi nol\n");
        return 1;
    }
    if (memcmp(b, c, sizeof b) == 0) {
        printf("GAGAL: dua jalur acak identik\n");
        return 1;
    }
    if (semua_byte_sama(b) || semua_byte_sama(c)) {
        printf("GAGAL: buffer semua-byte-sama\n");
        return 1;
    }
    // Pass-through syscall() non-getrandom: shim mencegat SEMUA panggilan
    // syscall(), jadi penerusan yang rusak (dlsym gagal, argumen kacau,
    // deadlock reentransi) mematikan syscall lain yang dipakai libc/Rust di
    // perangkat. Dulu uji ini hanya memanggil jalur getrandom sehingga
    // regresi penerusan lolos CI.
    long pid = syscall(SYS_getpid, 0, 0, 0, 0, 0, 0);
    if (pid <= 0 || pid != (long) getpid()) {
        printf("GAGAL pass-through SYS_getpid (%ld vs %ld)\n",
               pid, (long) getpid());
        return 1;
    }
    errno = 0;
    long tutup = syscall(SYS_close, -1, 0, 0, 0, 0, 0);
    if (tutup != -1 || errno != EBADF) {
        printf("GAGAL pass-through SYS_close (ret=%ld errno=%d)\n", tutup, errno);
        return 1;
    }
    static const char pesan[] = "pass-through\n";
    long tulis = syscall(SYS_write, 1, pesan, sizeof pesan - 1, 0, 0, 0);
    if (tulis != (long) (sizeof pesan - 1)) {
        printf("GAGAL pass-through SYS_write (ret=%ld)\n", tulis);
        return 1;
    }
    return 0;
}
