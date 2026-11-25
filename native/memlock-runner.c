/*
 * memlock-runner — 以 root 身份解除 RLIMIT_MEMLOCK 后 exec 目标程序。
 *
 * 背景 / 为什么需要它：
 *   APP 的启动链是 `su` -> /system/bin/sh（mksh）-> unixnu-boot.sh -> crosvm。
 *   部分 ROM 出厂把 RLIMIT_MEMLOCK 的 soft/hard 都锁成 64KB。crosvm 在准备
 *   Gunyah 借出区（lend memory）时要对其做 mlock(2)，64KB 的上限直接 ENOMEM，
 *   表现为 boot.log 里 "mlock FAILED errno=12" / "ShimHandoff region could not
 *   be mlocked" / "failed to create vm: Out of memory (os error 12)"。
 *
 *   2.0.48 尝试在脚本里用 `ulimit -Hl unlimited; ulimit -l unlimited` 修复，
 *   但 mksh 的 ulimit 不支持 `unlimited` 字面量：
 *       $ mksh -c 'ulimit -Hl unlimited'
 *       mksh: ulimit: unlimited exceeds allowable lockedmem(KiB) limit
 *   于是这两行静默失败（2>/dev/null 吞掉），soft/hard 依旧 64。
 *   用户手动在 bash 里执行相同命令能成功 —— 差别只在 shell，不在权限。
 *
 *   本 helper 直接调用 setrlimit(2)，不经过任何 shell 的 ulimit 实现。
 *   它在 su 派生的 root 进程里运行（有 CAP_SYS_RESOURCE，可提升 hard limit），
 *   把 soft/hard 都设为 RLIM_INFINITY，然后 execv 目标程序。
 *   rlimit 是进程属性，exec 会保留，并由之后 fork 出的 crosvm 继承。
 *
 * 用法：
 *   memlock-runner PROGRAM [ARGS...]
 *   例：setsid /data/.../memlock-runner /system/bin/sh /data/.../unixnu-boot.sh
 *
 * 编译（NDK，arm64）：
 *   aarch64-linux-android21-clang -O2 -o assets/memlock-runner native/memlock-runner.c
 */

#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <errno.h>
#include <sys/resource.h>
#include <unistd.h>

int main(int argc, char **argv) {
    if (argc < 2) {
        fprintf(stderr, "[memlock-runner] usage: %s PROGRAM [ARGS...]\n", argv[0]);
        return 64;
    }

    struct rlimit before;
    int got_before = (getrlimit(RLIMIT_MEMLOCK, &before) == 0);

    struct rlimit rl;
    rl.rlim_cur = RLIM_INFINITY;
    rl.rlim_max = RLIM_INFINITY;

    int rc = setrlimit(RLIMIT_MEMLOCK, &rl);
    if (rc != 0) {
        fprintf(stderr,
                "[memlock-runner] WARN setrlimit(RLIMIT_MEMLOCK) rc=%d errno=%d (%s)\n",
                rc, errno, strerror(errno));
    }

    struct rlimit after;
    if (getrlimit(RLIMIT_MEMLOCK, &after) == 0) {
        if (got_before) {
            fprintf(stderr, "[memlock-runner] RLIMIT_MEMLOCK before: soft=%llu hard=%llu\n",
                    (unsigned long long)before.rlim_cur,
                    (unsigned long long)before.rlim_max);
        }
        fprintf(stderr,
                "[memlock-runner] RLIMIT_MEMLOCK after:  soft=%llu hard=%llu%s\n",
                (unsigned long long)after.rlim_cur,
                (unsigned long long)after.rlim_max,
                (after.rlim_cur == RLIM_INFINITY && after.rlim_max == RLIM_INFINITY)
                    ? " (INFINITY OK)" : " (NOT unlimited!)");
        fflush(stderr);
    }

    /* exec 保留 rlimit；crosvm 作为脚本的子进程继承 INFINITY 上限。 */
    execv(argv[1], &argv[1]);

    /* 只有 exec 失败才会走到这里。 */
    fprintf(stderr, "[memlock-runner] FATAL execv(%s) errno=%d (%s)\n",
            argv[1], errno, strerror(errno));
    fflush(stderr);
    return 127;
}
