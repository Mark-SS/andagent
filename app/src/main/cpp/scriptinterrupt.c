#include <jni.h>
#include <dlfcn.h>
#include <stdint.h>
#include <time.h>

typedef struct JSRuntime JSRuntime;
typedef int (*InterruptHandler)(JSRuntime *, void *);
typedef void (*SetInterruptHandler)(JSRuntime *, InterruptHandler, void *);

static _Thread_local int64_t deadline_ns;

static int64_t monotonic_ns(void) {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (int64_t)ts.tv_sec * 1000000000LL + ts.tv_nsec;
}

static int interrupt_check(JSRuntime *runtime, void *opaque) {
    (void)runtime; (void)opaque;
    return deadline_ns > 0 && monotonic_ns() >= deadline_ns;
}

JNIEXPORT jboolean JNICALL
Java_com_dc_andagent_script_QuickJsEngine_installInterrupt(JNIEnv *env, jobject self,
                                                               jobject context, jint timeout_ms) {
    (void)self;
    void *handle = dlopen("libquickjs-android-wrapper.so", RTLD_NOW);
    if (!handle) return JNI_FALSE;
    SetInterruptHandler set_handler = (SetInterruptHandler)dlsym(handle, "JS_SetInterruptHandler");
    if (!set_handler) { dlclose(handle); return JNI_FALSE; }
    jclass clazz = (*env)->GetObjectClass(env, context);
    jfieldID field = (*env)->GetFieldID(env, clazz, "runtime", "J");
    if (!field) { dlclose(handle); return JNI_FALSE; }
    jlong pointer = (*env)->GetLongField(env, context, field);
    if (!pointer) { dlclose(handle); return JNI_FALSE; }
    deadline_ns = monotonic_ns() + (int64_t)timeout_ms * 1000000LL;
    set_handler((JSRuntime *)(uintptr_t)pointer, interrupt_check, NULL);
    dlclose(handle);
    return JNI_TRUE;
}

JNIEXPORT void JNICALL
Java_com_dc_andagent_script_QuickJsEngine_clearInterrupt(JNIEnv *env, jobject self, jobject context) {
    (void)self;
    deadline_ns = 0;
    void *handle = dlopen("libquickjs-android-wrapper.so", RTLD_NOW);
    if (!handle) return;
    SetInterruptHandler set_handler = (SetInterruptHandler)dlsym(handle, "JS_SetInterruptHandler");
    if (!set_handler) { dlclose(handle); return; }
    jclass clazz = (*env)->GetObjectClass(env, context);
    jfieldID field = (*env)->GetFieldID(env, clazz, "runtime", "J");
    if (!field) { dlclose(handle); return; }
    jlong pointer = (*env)->GetLongField(env, context, field);
    if (pointer) set_handler((JSRuntime *)(uintptr_t)pointer, NULL, NULL);
    dlclose(handle);
}
