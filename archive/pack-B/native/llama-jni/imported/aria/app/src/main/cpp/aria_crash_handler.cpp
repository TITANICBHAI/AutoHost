// ─────────────────────────────────────────────────────────────────────────────
// aria_crash_handler.cpp — native signal handler for ARIA.
//
// What it catches:
//   SIGSEGV / SIGBUS / SIGFPE / SIGILL / SIGABRT / SIGPIPE in any native
//   thread. Without this, llama.cpp / Vulkan / OpenCL crashes produce a
//   tombstone in /data/tombstones/ that is unreachable on production devices.
//
// What it produces:
//   <crashDir>/native-crash-<epochMillis>-<tid>.txt
//   …with: signal name, si_code, fault address, register snapshot, backtrace.
//
// Notes on async-signal safety:
//   - Inside a signal handler we may only call async-signal-safe functions
//     (open, write, snprintf is NOT safe — but we use it because the
//     alternative is a 200-line manual int formatter; in practice snprintf in
//     bionic is reentrant enough for a one-shot crash dump).
//   - We DO NOT call back into the JVM from the handler. The JVM may itself
//     be holding the lock that caused the crash.
//   - We use _Unwind_Backtrace from libgcc (shipped with the NDK) which is
//     async-signal-safe in practice on Android.
//   - After writing the report we re-raise the signal with the default
//     handler so the OS still produces a tombstone and ART knows the process
//     died. This matters for Play Console crash reports.
// ─────────────────────────────────────────────────────────────────────────────

#include <jni.h>
#include <android/log.h>
#include <signal.h>
#include <unistd.h>
#include <unwind.h>
#include <dlfcn.h>
#include <cxxabi.h>
#include <fcntl.h>
#include <string.h>
#include <stdio.h>
#include <stdlib.h>
#include <sys/syscall.h>
#include <sys/types.h>
#include <time.h>
#include <pthread.h>

#define TAG "AriaNativeCrash"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

namespace {

constexpr size_t kMaxFrames     = 64;
constexpr size_t kCrashDirMax   = 512;
constexpr size_t kReportPathMax = 640;

// Path to the directory where reports are written. Set once at install time.
char g_crash_dir[kCrashDirMax] = {0};

// Already-installed flag. Re-installing on a second JNI call is a no-op so we
// do not chain handlers.
volatile sig_atomic_t g_installed = 0;

// Re-entrancy guard — if our own handler crashes (or another signal arrives
// while we are mid-dump) we must not loop forever.
volatile sig_atomic_t g_in_handler = 0;

// Saved previous handlers per signal — restored before re-raise so the OS
// produces its normal tombstone.
struct sigaction g_prev_segv, g_prev_bus, g_prev_fpe, g_prev_ill,
                 g_prev_abrt, g_prev_pipe;

// ─── _Unwind backtrace state ────────────────────────────────────────────────

struct UnwindCtx {
    void**  frames;
    size_t  count;
    size_t  capacity;
};

_Unwind_Reason_Code unwind_cb(struct _Unwind_Context* ctx, void* arg) {
    auto* state = static_cast<UnwindCtx*>(arg);
    uintptr_t ip = _Unwind_GetIP(ctx);
    if (ip != 0 && state->count < state->capacity) {
        state->frames[state->count++] = reinterpret_cast<void*>(ip);
    }
    return _URC_NO_REASON;
}

// ─── Helpers (all async-signal-safe in practice) ────────────────────────────

const char* signal_name(int sig) {
    switch (sig) {
        case SIGSEGV: return "SIGSEGV";
        case SIGBUS:  return "SIGBUS";
        case SIGFPE:  return "SIGFPE";
        case SIGILL:  return "SIGILL";
        case SIGABRT: return "SIGABRT";
        case SIGPIPE: return "SIGPIPE";
        default:      return "SIG?";
    }
}

ssize_t write_str(int fd, const char* s) {
    return write(fd, s, strlen(s));
}

void write_fmt(int fd, const char* fmt, ...) {
    char buf[512];
    va_list ap;
    va_start(ap, fmt);
    int n = vsnprintf(buf, sizeof(buf), fmt, ap);
    va_end(ap);
    if (n > 0) write(fd, buf, (size_t)n);
}

void write_backtrace(int fd) {
    void*       frames[kMaxFrames];
    UnwindCtx   ctx { frames, 0, kMaxFrames };
    _Unwind_Backtrace(unwind_cb, &ctx);

    write_str(fd, "===== native backtrace =====\n");
    for (size_t i = 0; i < ctx.count; ++i) {
        Dl_info info{};
        const char* libname = "?";
        const char* symname = "?";
        uintptr_t   relpc   = 0;

        if (dladdr(frames[i], &info)) {
            if (info.dli_fname) libname = info.dli_fname;
            if (info.dli_sname) symname = info.dli_sname;
            relpc = (uintptr_t)frames[i] - (uintptr_t)info.dli_fbase;
        }

        // Demangle C++ names if possible (allocates with malloc — best-effort,
        // skipped if it fails).
        char demangled_buf[256] = {0};
        const char* display_name = symname;
        if (symname[0] == '_' && symname[1] == 'Z') {
            int status = 0;
            size_t outlen = sizeof(demangled_buf);
            char* out = abi::__cxa_demangle(symname, demangled_buf, &outlen, &status);
            if (status == 0 && out) display_name = out;
        }

        write_fmt(fd, "  #%02zu pc %012lx  %s (%s+%lu)\n",
                  i,
                  relpc,
                  libname,
                  display_name,
                  (unsigned long)((uintptr_t)frames[i] - (uintptr_t)info.dli_saddr));
    }
}

#if defined(__aarch64__)
void write_registers(int fd, ucontext_t* uc) {
    write_str(fd, "===== registers (aarch64) =====\n");
    auto* mc = &uc->uc_mcontext;
    for (int i = 0; i < 31; ++i) {
        write_fmt(fd, "  x%-2d  %016llx", i, (unsigned long long)mc->regs[i]);
        if ((i % 2) == 1) write_str(fd, "\n");
        else              write_str(fd, "   ");
    }
    write_fmt(fd, "  sp   %016llx   pc   %016llx\n",
              (unsigned long long)mc->sp, (unsigned long long)mc->pc);
}
#else
void write_registers(int fd, ucontext_t*) {
    write_str(fd, "===== registers (unsupported arch) =====\n");
}
#endif

void write_thread_name(int fd) {
    char name[32] = {0};
    if (pthread_getname_np(pthread_self(), name, sizeof(name)) == 0) {
        write_fmt(fd, "thread:    %s (tid=%d)\n", name, (int)syscall(SYS_gettid));
    } else {
        write_fmt(fd, "thread:    (unnamed) (tid=%d)\n", (int)syscall(SYS_gettid));
    }
}

void restore_default(int sig) {
    struct sigaction* prev = nullptr;
    switch (sig) {
        case SIGSEGV: prev = &g_prev_segv; break;
        case SIGBUS:  prev = &g_prev_bus;  break;
        case SIGFPE:  prev = &g_prev_fpe;  break;
        case SIGILL:  prev = &g_prev_ill;  break;
        case SIGABRT: prev = &g_prev_abrt; break;
        case SIGPIPE: prev = &g_prev_pipe; break;
    }
    if (prev) sigaction(sig, prev, nullptr);
    else      signal(sig, SIG_DFL);
}

// ─── The actual signal handler ──────────────────────────────────────────────

void aria_signal_handler(int sig, siginfo_t* si, void* ucv) {
    if (g_in_handler) {
        // Second crash in the handler — give up and re-raise.
        restore_default(sig);
        raise(sig);
        return;
    }
    g_in_handler = 1;

    // Build the report path: <crashDir>/native-crash-<epochSec>-<tid>.txt
    char path[kReportPathMax];
    time_t now = time(nullptr);
    int tid = (int)syscall(SYS_gettid);
    snprintf(path, sizeof(path), "%s/native-crash-%lld-%d.txt",
             g_crash_dir, (long long)now, tid);

    int fd = open(path, O_WRONLY | O_CREAT | O_TRUNC, 0644);
    if (fd < 0) {
        // No file — at least dump to logcat so a connected Studio sees it.
        LOGE("native crash: signal=%s code=%d addr=%p — could not open %s",
             signal_name(sig), si ? si->si_code : 0,
             si ? si->si_addr : nullptr, path);
        restore_default(sig);
        raise(sig);
        return;
    }

    write_str(fd, "===== ARIA native crash report =====\n");
    write_fmt(fd, "when:      %lld (epoch sec)\n", (long long)now);
    write_fmt(fd, "signal:    %s (%d)\n", signal_name(sig), sig);
    write_fmt(fd, "si_code:   %d\n", si ? si->si_code : 0);
    write_fmt(fd, "fault:     %p\n", si ? si->si_addr : nullptr);
    write_fmt(fd, "pid:       %d\n", getpid());
    write_thread_name(fd);
    write_str(fd, "\n");

    if (ucv) write_registers(fd, static_cast<ucontext_t*>(ucv));
    write_str(fd, "\n");

    write_backtrace(fd);
    write_str(fd, "\n===== end =====\n");

    fsync(fd);
    close(fd);

    LOGE("native crash captured: %s", path);

    // Hand back to the default handler so ART / the OS still record death.
    restore_default(sig);
    raise(sig);
}

void install_one(int sig, struct sigaction* prev) {
    struct sigaction sa{};
    sa.sa_sigaction = aria_signal_handler;
    sa.sa_flags = SA_SIGINFO | SA_ONSTACK;
    sigemptyset(&sa.sa_mask);
    sigaction(sig, &sa, prev);
}

} // anonymous namespace

// ─── JNI entry point ────────────────────────────────────────────────────────
// Called by NativeCrashHandler.install(). Idempotent.

extern "C"
JNIEXPORT void JNICALL
Java_com_ariaagent_mobile_core_logging_NativeCrashHandler_nativeInstall(
        JNIEnv* env, jclass /*clazz*/, jstring crash_dir_path) {
    if (g_installed) return;

    const char* path = env->GetStringUTFChars(crash_dir_path, nullptr);
    if (!path) return;
    strncpy(g_crash_dir, path, sizeof(g_crash_dir) - 1);
    g_crash_dir[sizeof(g_crash_dir) - 1] = '\0';
    env->ReleaseStringUTFChars(crash_dir_path, path);

    install_one(SIGSEGV, &g_prev_segv);
    install_one(SIGBUS,  &g_prev_bus);
    install_one(SIGFPE,  &g_prev_fpe);
    install_one(SIGILL,  &g_prev_ill);
    install_one(SIGABRT, &g_prev_abrt);
    install_one(SIGPIPE, &g_prev_pipe);

    g_installed = 1;
    LOGI("aria_crash_handler installed → %s", g_crash_dir);
}
