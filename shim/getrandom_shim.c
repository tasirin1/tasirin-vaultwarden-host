// Shim getrandom() untuk kernel STB lama (mis. 3.14.x di Android 5/6).
//
// Latar: binary Vaultwarden (Rust modern) memanggil getrandom(buf, len,
// GRND_NONBLOCK) via PLT. Di kernel lama panggilan itu gagal dengan
// EINVAL (errno=22) sehingga Rust panic setiap start (exit 101).
// Shim ini mencegat simbol getrandom (LD_PRELOAD) dan melayaninya dari
// /dev/urandom — cukup untuk kebutuhan Vaultwarden (UUID, token, salt).
//
// Build (CI, NDK r25, API 21):
//   armv7a-linux-androideabi21-clang -shared -fPIC -Os -o \
//     libgetrandom-shim-armeabi-v7a.so getrandom_shim.c
// Hasil hanya belasan KB; tanpa dependensi selain libc dasar (open/read/close).

#include <errno.h>
#include <fcntl.h>
#include <stddef.h>
#include <unistd.h>

// Tanda tangan sama persis dengan getrandom(2) agar interposisi PLT tepat.
ssize_t getrandom(void *buf, size_t buflen, unsigned int flags) {
    (void) flags; // flag diabaikan: /dev/urandom tidak pernah blokir.
    if (buflen == 0) {
        return 0;
    }
    if (buf == 0) {
        errno = 22; // EINVAL, samakan perilaku glibc untuk argumen buruk.
        return -1;
    }
    int fd = open("/dev/urandom", O_RDONLY | O_CLOEXEC);
    if (fd < 0) {
        return -1; // errno sudah diisi open().
    }
    size_t sudah = 0;
    while (sudah < buflen) {
        ssize_t n = read(fd, (char *) buf + sudah, buflen - sudah);
        if (n < 0) {
            int e = errno;
            if (e == 4) { // EINTR: coba lagi, bukan gagal.
                continue;
            }
            close(fd);
            errno = e;
            return -1;
        }
        if (n == 0) { // /dev/urandom tak pernah EOF; anggap galat bila terjadi.
            close(fd);
            errno = 5; // EIO.
            return -1;
        }
        sudah += (size_t) n;
    }
    close(fd);
    return (ssize_t) sudah;
}
