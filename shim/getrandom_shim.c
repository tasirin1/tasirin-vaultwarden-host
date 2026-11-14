

#include <errno.h>
#include <limits.h>
#include <fcntl.h>
#include <stddef.h>
#include <unistd.h>
#include <stdarg.h>
#include <dlfcn.h>
#include <pthread.h>

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
#error "Nomor syscall getrandom tak dikenal untuk arsitektur ini"
#endif
#endif

static int fd_urandom = -1;
static int fd_random = -1;
static pthread_mutex_t kunci_urandom = PTHREAD_MUTEX_INITIALIZER;
static pthread_mutex_t kunci_random = PTHREAD_MUTEX_INITIALIZER;
static pthread_once_t sekali_syscall = PTHREAD_ONCE_INIT;
static long (*nyata_syscall)(long, long, long, long, long, long, long) = 0;

static void init_syscall_nyata(void) {
    *(void **) (&nyata_syscall) = dlsym(RTLD_NEXT, "syscall");
}

__attribute__((constructor)) static void init_syscall_dini(void) {
    init_syscall_nyata();
}

#define GRND_NONBLOCK_SHIM 0x0001u
#define GRND_RANDOM_SHIM 0x0002u
#define GRND_INSECURE_SHIM 0x0004u
#define GRND_DIKENAL_SHIM \
    (GRND_NONBLOCK_SHIM | GRND_RANDOM_SHIM | GRND_INSECURE_SHIM)

static int fd_urandom_nb = -1;
static int fd_random_nb = -1;
static pthread_mutex_t kunci_urandom_nb = PTHREAD_MUTEX_INITIALIZER;
static pthread_mutex_t kunci_random_nb = PTHREAD_MUTEX_INITIALIZER;

static ssize_t isi_dari_inti(void *buf, size_t len, int pakai_random,
        int nonblok) {
    if (len == 0) {
        return 0;
    }
    if (buf == 0) {
        /* Audit 1730: kontrak kernel untuk buffer NULL dengan
         * panjang > 0 adalah EFAULT, bukan EINVAL (EINVAL juga
         * tanda khas kegagalan kernel lama di jalur ini, jadi
         * errno yang salah mengaburkan diagnosis). */
        errno = EFAULT;
        return -1;
    }
    int *wadah_fd = nonblok
            ? (pakai_random ? &fd_random_nb : &fd_urandom_nb)
            : (pakai_random ? &fd_random : &fd_urandom);
    const char *sumber = pakai_random ? "/dev/random" : "/dev/urandom";
    pthread_mutex_t *kunci = nonblok
            ? (pakai_random ? &kunci_random_nb : &kunci_urandom_nb)
            : (pakai_random ? &kunci_random : &kunci_urandom);
    int flag_buka = O_RDONLY | O_CLOEXEC | (nonblok ? O_NONBLOCK : 0);
    size_t sudah = 0;
    int coba_ebadf = 0;

    pthread_mutex_lock(kunci);
    for (;;) {
        if (*wadah_fd < 0) {
            int fd = open(sumber, flag_buka);
            if (fd < 0) {
                int e = errno;
                pthread_mutex_unlock(kunci);
                errno = e;
                return -1;
            }
            *wadah_fd = fd;
        }
        int pulih = 0;
        while (sudah < len) {
            /* Audit 1746: pengisian dipotong per 4096 byte agar
             * fault di tengah buffer berhenti sebagai hasil
             * parsial, bukan kegagalan total. */
            size_t sisa = len - sudah;
            if (sisa > 4096) {
                sisa = 4096;
            }
            ssize_t n = read(*wadah_fd, (char *) buf + sudah, sisa);
            if (n < 0) {
                int e = errno;
                if (e == EINTR) {
                    continue;
                }
                if (e == EBADF) {
                    if (++coba_ebadf > 3) {
                        pthread_mutex_unlock(kunci);
                        errno = EIO;
                        return -1;
                    }
                    close(*wadah_fd);
                    *wadah_fd = -1;
                    pulih = 1;
                    break;
                }
                pthread_mutex_unlock(kunci);
                if (sudah > 0) {
                    /* Audit 1746: byte yang sudah terkirim menang
                     * atas galat - kernel mengembalikan hitungan
                     * parsial bila penyalinan fault di tengah
                     * (kasus nonblok/EAGAIN lama termasuk di sini). */
                    return (ssize_t) sudah;
                }
                errno = e;
                return -1;
            }
            if (n == 0) {
                pthread_mutex_unlock(kunci);
                if (nonblok && sudah > 0) {
                    return (ssize_t) sudah;
                }
                errno = EIO;
                return -1;
            }
            sudah += (size_t) n;
        }
        if (!pulih) {
            break;
        }
    }
    pthread_mutex_unlock(kunci);
    return (ssize_t) sudah;
}

static ssize_t isi_dari(void *buf, size_t len, int pakai_random) {
    return isi_dari_inti(buf, len, pakai_random, 0);
}

static ssize_t isi_dari_nonblok(void *buf, size_t len, int pakai_random) {
    return isi_dari_inti(buf, len, pakai_random, 1);
}

static ssize_t getrandom_bendera(void *buf, size_t buflen, unsigned int flags) {
    if (flags & ~GRND_DIKENAL_SHIM) {
        errno = EINVAL;
        return -1;
    }
    /* Audit 1746: kernel membatasi permintaan ke INT_MAX. */
    if (buflen > (size_t) INT_MAX) {
        buflen = (size_t) INT_MAX;
    }
    int pakai_random = (flags & GRND_RANDOM_SHIM) != 0
            && (flags & GRND_INSECURE_SHIM) == 0;
    if (flags & (GRND_NONBLOCK_SHIM | GRND_INSECURE_SHIM)) {
        return isi_dari_nonblok(buf, buflen, pakai_random);
    }
    return isi_dari(buf, buflen, pakai_random);
}

ssize_t getrandom(void *buf, size_t buflen, unsigned int flags) {
    return getrandom_bendera(buf, buflen, flags);
}

long syscall(long n, ...) {

    va_list ap;
    va_start(ap, n);
    long a = va_arg(ap, long);
    long b = va_arg(ap, long);
    long c = va_arg(ap, long);
    if (n == (long) SYS_getrandom
#ifdef __NR_getrandom
        || n == (long) __NR_getrandom
#endif
    ) {
        va_end(ap);
        return (long) getrandom_bendera((void *) a, (size_t) b,
                (unsigned int) c);
    }

    long d = va_arg(ap, long);
    long e = va_arg(ap, long);
    long f = va_arg(ap, long);
    va_end(ap);

    if (!nyata_syscall) {
        pthread_once(&sekali_syscall, init_syscall_nyata);
    }
    if (!nyata_syscall) {
        errno = ENOSYS;
        return -1;
    }
    return nyata_syscall(n, a, b, c, d, e, f);
}
