// Shim getrandom() + syscall(SYS_getrandom) untuk kernel STB lama
// (mis. 3.14.x di Android 5/6).
//
// Latar: binary Vaultwarden (Rust modern) mengambil acak lewat dua jalur:
//  - std Rust via pembungkus libc getrandom(buf, len, flags) (PLT), dan
//  - crate getrandom 0.2 (dipakai ring 0.17 ke ticketer TLS Rocket rustls)
//    via syscall mentah libc::syscall(SYS_getrandom, ...) (PLT).
// Di kernel 3.14 (getrandom hadir sejak 3.17) keduanya gagal — bionic
// mengembalikan EINVAL (errno=22), bukan ENOSYS, sehingga fallback
// /dev/urandom di getrandom 0.2 tidak aktif: std panic setiap start
// (exit 101, "failed to generate random data") dan TLS Rocket gagal
// ("bad TLS ticketer: failed to get random bytes") walau HTTP jalan.
// Shim ini mencegat kedua simbol (LD_PRELOAD) dan melayaninya dari
// /dev/urandom — cukup untuk kebutuhan Vaultwarden (UUID, token, salt,
// kunci ticketer TLS).
//
// Build (CI, NDK r25, API 21):
//   armv7a-linux-androideabi21-clang -shared -fPIC -Os
//     -o libgetrandom-shim-armeabi-v7a.so getrandom_shim.c
// Hasil hanya belasan KB; tanpa dependensi selain libc dasar
// (open/read/close/dlsym).

#include <errno.h>
#include <fcntl.h>
#include <stddef.h>
#include <unistd.h>
#include <stdarg.h>
#include <dlfcn.h>
#include <pthread.h>

// Nomor syscall getrandom bila header NDK (API 21) tak menyediakannya.
// Target rilis: ARM 32-bit (armeabi-v7a) = 384; sisanya untuk uji host CI.
#ifndef SYS_getrandom
#if defined(__arm__)
#define SYS_getrandom 384
#elif defined(__aarch64__)
#define SYS_getrandom 278
#elif defined(__i386__)
#define SYS_getrandom 355
#elif defined(__x86_64__)
#define SYS_getrandom 318
#elif defined(__riscv) && __riscv_xlen == 64
#define SYS_getrandom 278
#else
#define SYS_getrandom 0
#endif
#endif

// fd /dev/urandom dipakai ulang antar panggilan (hemat open/close saat TLS sibuk).
static int fd_urandom = -1;
static pthread_mutex_t kunci_urandom = PTHREAD_MUTEX_INITIALIZER;
static pthread_mutex_t kunci_syscall = PTHREAD_MUTEX_INITIALIZER;

// Isi buf dari /dev/urandom; 0 bila len 0, -1 + errno bila gagal.
static ssize_t isi_urandom(void *buf, size_t len) {
    if (len == 0) {
        return 0;
    }
    if (buf == 0) {
        errno = 22; // EINVAL, samakan perilaku glibc untuk argumen buruk.
        return -1;
    }
    if (fd_urandom < 0) {
        pthread_mutex_lock(&kunci_urandom);
        if (fd_urandom < 0) {
            int fd = open("/dev/urandom", O_RDONLY | O_CLOEXEC);
            if (fd < 0) {
                pthread_mutex_unlock(&kunci_urandom);
                return -1; // errno sudah diisi open().
            }
            fd_urandom = fd;
        }
        pthread_mutex_unlock(&kunci_urandom);
    }
    size_t sudah = 0;
    while (sudah < len) {
        ssize_t n = read(fd_urandom, (char *) buf + sudah, len - sudah);
        if (n < 0) {
            int e = errno;
            if (e == 4) { // EINTR: coba lagi, bukan gagal.
                continue;
            }
            if (e == 9) { // EBADF: fd rusak, buka ulang di dalam kunci.
                int fd_lama = fd_urandom;
                pthread_mutex_lock(&kunci_urandom);
                // Hanya thread pertama yang menutup/membuka ulang; thread lain
                // yang antre memakai fd baru di iterasi berikut (tanpa
                // double-close fd yang sudah ditutup thread pertama).
                if (fd_urandom == fd_lama) {
                    close(fd_urandom);
                    fd_urandom = -1;
                    int fd = open("/dev/urandom", O_RDONLY | O_CLOEXEC);
                    if (fd < 0) {
                        pthread_mutex_unlock(&kunci_urandom);
                        return -1; // errno sudah diisi open().
                    }
                    fd_urandom = fd;
                }
                pthread_mutex_unlock(&kunci_urandom);
                continue;
            }
            errno = e;
            return -1;
        }
        if (n == 0) { // /dev/urandom tak pernah EOF; anggap galat bila terjadi.
            errno = 5; // EIO.
            return -1;
        }
        sudah += (size_t) n;
    }
    return (ssize_t) sudah;
}

// Tanda tangan sama persis dengan getrandom(2) agar interposisi PLT tepat.
ssize_t getrandom(void *buf, size_t buflen, unsigned int flags) {
    (void) flags; // flag diabaikan: /dev/urandom tidak pernah blokir.
    return isi_urandom(buf, buflen);
}

// Mencegat syscall(SYS_getrandom, buf, len, flags) mentah dari getrandom 0.2
// (jalur ring/rustls ticketer TLS). Nomor lain diteruskan ke syscall asli
// via dlsym(RTLD_NEXT). Argumen dibaca sebagai long (seukuran pointer di
// LP32/LP64); register ekstra yang terbaca untuk panggilan argumen
// sedikit diabaikan kernel/penerusan, jadi aman diteruskan apa adanya.
long syscall(long n, ...) {
    va_list ap;
    va_start(ap, n);
    long a = va_arg(ap, long);
    long b = va_arg(ap, long);
    long c = va_arg(ap, long);
    long d = va_arg(ap, long);
    long e = va_arg(ap, long);
    long f = va_arg(ap, long);
    va_end(ap);
    if (n == (long) SYS_getrandom
#ifdef __NR_getrandom
        || n == (long) __NR_getrandom
#endif
    ) {
        return (long) isi_urandom((void *) a, (size_t) b);
    }
    static long (*nyata)(long, long, long, long, long, long, long) = 0;
    if (!nyata) {
        pthread_mutex_lock(&kunci_syscall);
        if (!nyata) {
            *(void **) (&nyata) = dlsym(RTLD_NEXT, "syscall");
        }
        pthread_mutex_unlock(&kunci_syscall);
        if (!nyata) {
            errno = 38; // ENOSYS bila penerusan tak ditemukan.
            return -1;
        }
    }
    return nyata(n, a, b, c, d, e, f);
}
