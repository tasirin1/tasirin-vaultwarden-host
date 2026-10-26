

#include <errno.h>
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
static pthread_mutex_t kunci_urandom = PTHREAD_MUTEX_INITIALIZER;
static pthread_once_t sekali_syscall = PTHREAD_ONCE_INIT;
static long (*nyata_syscall)(long, long, long, long, long, long, long) = 0;

static void init_syscall_nyata(void) {
    *(void **) (&nyata_syscall) = dlsym(RTLD_NEXT, "syscall");
}

__attribute__((constructor)) static void init_syscall_dini(void) {
    init_syscall_nyata();
}

static ssize_t isi_urandom(void *buf, size_t len) {
    if (len == 0) {
        return 0;
    }
    if (buf == 0) {
        errno = EINVAL;
        return -1;
    }
    size_t sudah = 0;
    int coba_ebadf = 0;

    pthread_mutex_lock(&kunci_urandom);
    for (;;) {
        if (fd_urandom < 0) {
            int fd = open("/dev/urandom", O_RDONLY | O_CLOEXEC);
            if (fd < 0) {
                int e = errno;
                pthread_mutex_unlock(&kunci_urandom);
                errno = e;
                return -1;
            }
            fd_urandom = fd;
        }
        while (sudah < len) {
            ssize_t n = read(fd_urandom, (char *) buf + sudah, len - sudah);
            if (n < 0) {
                int e = errno;
                if (e == EINTR) {
                    continue;
                }
                if (e == EBADF) {

                    if (++coba_ebadf > 3) {
                        pthread_mutex_unlock(&kunci_urandom);
                        errno = EIO;
                        return -1;
                    }
                    close(fd_urandom);
                    fd_urandom = -1;
                    break;
                }
                pthread_mutex_unlock(&kunci_urandom);
                errno = e;
                return -1;
            }
            if (n == 0) {
                pthread_mutex_unlock(&kunci_urandom);
                errno = EIO;
                return -1;
            }
            sudah += (size_t) n;
        }
        if (sudah == len) {
            break;
        }
    }
    pthread_mutex_unlock(&kunci_urandom);
    return (ssize_t) sudah;
}

ssize_t getrandom(void *buf, size_t buflen, unsigned int flags) {
    (void) flags;
    return isi_urandom(buf, buflen);
}

long syscall(long n, ...) {

    va_list ap;
    va_start(ap, n);
    long a = va_arg(ap, long);
    long b = va_arg(ap, long);
    if (n == (long) SYS_getrandom
#ifdef __NR_getrandom
        || n == (long) __NR_getrandom
#endif
    ) {
        va_end(ap);
        return (long) isi_urandom((void *) a, (size_t) b);
    }

    long c = va_arg(ap, long);
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
