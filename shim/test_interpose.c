// Uji interposisi getrandom() via LD_PRELOAD (jalan di runner CI, host x86_64).
// Memanggil getrandom dengan flag bogus: libc asli menolak (EINVAL),
// shim harus tetap melayani 32 byte acak. Return 0 = lolos.
#include <stdio.h>
#include <sys/random.h>

int main(void) {
    unsigned char b[32];
    ssize_t n = getrandom(b, sizeof b, 0xFFFFu);
    if (n != 32) {
        printf("GAGAL n=%zd\n", n);
        return 1;
    }
    unsigned long x = 0;
    for (int i = 0; i < 32; i++) {
        x += b[i];
    }
    printf("OK n=%zd checksum=%lu\n", n, x);
    return x == 0;
}
