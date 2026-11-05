#include <jni.h>
#include <errno.h>
#include <fcntl.h>
#include <string.h>
#include <stdlib.h>
#include <stdio.h>
#include <unistd.h>
#include <sys/syscall.h>
#include <sys/stat.h>
#include <sys/wait.h>
#include <sys/types.h>

#ifndef MFD_CLOEXEC
#define MFD_CLOEXEC 1U
#endif

/*
 * memfd-based exec for Android 10+ W^X workaround.
 *
 * targetSdk>=29 SELinux policy blocks execve() on files inside the app data
 * directory (execute_no_trans). But fexecve() on an anonymous memfd bypasses
 * the path check: the kernel never sees a /data-data path, and memfd pages
 * are executable by construction (the fd itself carries exec permission).
 *
 * Returns: 0 and sets *pidOut to the child pid on success.
 *         -errno on failure (child never ran).
 * The child replaces itself with the ELF; parent waits via waitpid in Kotlin
 * (ProcessBuilder can't wrap a raw fd, so we return the pid and pipe fds).
 *
 * argv/envp are NUL-separated, double-NUL terminated (from Kotlin side).
 */
JNIEXPORT jint JNICALL
Java_com_dc_andagent_shell_MemExec_nativeExec(
    JNIEnv *env, jclass clazz,
    jbyteArray elfBytes,
    jbyteArray argvBytes,
    jbyteArray envpBytes,
    jobjectArray pipeFds,   /* [in/out] 2-element array: [0]=childStdoutRead,[1]=childStdinWrite set by us? -> simpler: we create pipes and return via int array */
    jintArray outFds,       /* out: [0]=stdoutReadFd, [1]=stderrReadFd, [2]=stdinWriteFd, [3]=childPid */
    jstring workDirJ)
{
    (void)clazz; (void)pipeFds;

    jsize elfLen = (*env)->GetArrayLength(env, elfBytes);
    jbyte *elf = (*env)->GetByteArrayElements(env, elfBytes, NULL);
    if (!elf) return -ENOMEM;

    /* --- memfd --- */
    int fd = (int)syscall(__NR_memfd_create, "andagent-exec", MFD_CLOEXEC);
    if (fd < 0) {
        (*env)->ReleaseByteArrayElements(env, elfBytes, elf, JNI_ABORT);
        return -errno ? -errno : -1;
    }
    ssize_t off = 0;
    while (off < elfLen) {
        ssize_t n = write(fd, (const char *)elf + off, (size_t)(elfLen - off));
        if (n <= 0) { close(fd); (*env)->ReleaseByteArrayElements(env, elfBytes, elf, JNI_ABORT); return -(int)(errno ? errno : EIO); }
        off += n;
    }
    (*env)->ReleaseByteArrayElements(env, elfBytes, elf, JNI_ABORT);

    /* --- fexecve needs /proc/self/fd/N path on Android (no direct syscall wrapper in older bionic) --- */
    char fdpath[64];
    snprintf(fdpath, sizeof(fdpath), "/proc/self/fd/%d", fd);

    /* --- pipes: stdout, stderr, stdin --- */
    int outPipe[2], errPipe[2], inPipe[2];
    if (pipe(outPipe) || pipe(errPipe) || pipe(inPipe)) {
        int e = errno; close(fd);
        return -e;
    }

    /* --- argv/envp to C arrays --- */
    jsize argvLen = (*env)->GetArrayLength(env, argvBytes);
    jbyte *argvBuf = (*env)->GetByteArrayElements(env, argvBytes, NULL);
    jsize envpLen = (*env)->GetArrayLength(env, envpBytes);
    jbyte *envpBuf = envpLen > 0 ? (*env)->GetByteArrayElements(env, envpBytes, NULL) : NULL;

    /* Count entries */
    int argc = 0;
    for (jsize i = 0; i < argvLen; i++) if (argvBuf[i] == 0) argc++;
    char **argv = calloc((size_t)argc + 1, sizeof(char *));
    int envc = 0;
    for (jsize i = 0; envpBuf && i < envpLen; i++) if (envpBuf[i] == 0) envc++;
    char **envp = calloc((size_t)envc + 1, sizeof(char *));
    if (!argv || !envp) { close(fd); return -ENOMEM; }

    int idx = 0;
    argv[idx++] = (char *)argvBuf;
    for (jsize i = 0; i < argvLen - 1 && idx < argc; i++)
        if (argvBuf[i] == 0) argv[idx++] = (char *)&argvBuf[i + 1];
    idx = 0;
    if (envpBuf && envpLen > 0) {
        envp[idx++] = (char *)envpBuf;
        for (jsize i = 0; i < envpLen - 1 && idx < envc; i++)
            if (envpBuf[i] == 0) envp[idx++] = (char *)&envpBuf[i + 1];
    }

    const char *workDir = workDirJ ? (*env)->GetStringUTFChars(env, workDirJ, NULL) : NULL;

    fflush(NULL);
    pid_t pid = fork();
    if (pid == 0) {
        /* child */
        if (workDir) chdir(workDir);
        dup2(inPipe[0], STDIN_FILENO);
        dup2(outPipe[1], STDOUT_FILENO);
        dup2(errPipe[1], STDERR_FILENO);
        close(outPipe[0]); close(outPipe[1]);
        close(errPipe[0]); close(errPipe[1]);
        close(inPipe[0]);  close(inPipe[1]);
        /* fexecve() may be absent in older bionic; execve on /proc/self/fd/N is equivalent */
        execve(fdpath, argv, envp);
        /* fexecve failed — report via stderr then die */
        dprintf(STDERR_FILENO, "fexecve failed errno=%d\n", errno);
        _exit(127);
    }

    /* parent */
    int savedErrno = errno;
    close(fd);
    close(outPipe[1]); close(errPipe[1]); close(inPipe[0]);
    if (workDirJ) (*env)->ReleaseStringUTFChars(env, workDirJ, workDir);
    (*env)->ReleaseByteArrayElements(env, argvBytes, argvBuf, JNI_ABORT);
    if (envpBuf) (*env)->ReleaseByteArrayElements(env, envpBytes, envpBuf, JNI_ABORT);
    free(argv); free(envp);

    if (pid < 0) {
        close(outPipe[0]); close(errPipe[0]); close(inPipe[1]);
        return -(int)(savedErrno ? savedErrno : EAGAIN);
    }

    jint fds[4] = { outPipe[0], errPipe[0], inPipe[1], pid };
    (*env)->SetIntArrayRegion(env, outFds, 0, 4, fds);
    return 0;
}

/* Wait for the child; returns exit status (waitpid raw status). */
JNIEXPORT jint JNICALL
Java_com_dc_andagent_shell_MemExec_nativeWait(JNIEnv *env, jclass clazz, jint pid) {
    (void)env; (void)clazz;
    int status = 0;
    pid_t r = waitpid((pid_t)pid, &status, 0);
    if (r < 0) return -1;
    if (WIFEXITED(status)) return WEXITSTATUS(status);
    if (WIFSIGNALED(status)) return 128 + WTERMSIG(status);
    return -1;
}
