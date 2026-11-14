// JNI bindings for com.llsl.viper4android.rootless.ViperNative.

#include "viper_engine.h"
#include <jni.h>

namespace {

viper_engine *Engine(jlong handle) { return reinterpret_cast<viper_engine *>(handle); }

} // namespace

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_llsl_viper4android_rootless_ViperNative_create(JNIEnv *, jobject, jint sampling_rate) {
    return reinterpret_cast<jlong>(viper_engine_create(static_cast<uint32_t>(sampling_rate)));
}

JNIEXPORT void JNICALL
Java_com_llsl_viper4android_rootless_ViperNative_destroy(JNIEnv *, jobject, jlong handle) {
    viper_engine_destroy(Engine(handle));
}

JNIEXPORT void JNICALL Java_com_llsl_viper4android_rootless_ViperNative_setSamplingRate(
    JNIEnv *, jobject, jlong handle, jint sampling_rate
) {
    viper_engine_set_sampling_rate(Engine(handle), static_cast<uint32_t>(sampling_rate));
}

JNIEXPORT void JNICALL
Java_com_llsl_viper4android_rootless_ViperNative_reset(JNIEnv *, jobject, jlong handle) {
    viper_engine_reset(Engine(handle));
}

JNIEXPORT jboolean JNICALL Java_com_llsl_viper4android_rootless_ViperNative_setParam(
    JNIEnv *env, jobject, jlong handle, jint param, jbyteArray payload
) {
    const jsize size = env->GetArrayLength(payload);
    jbyte *bytes = env->GetByteArrayElements(payload, nullptr);
    if (bytes == nullptr) return JNI_FALSE;
    const int ok = viper_engine_set_param(
        Engine(handle), param, reinterpret_cast<const uint8_t *>(bytes), static_cast<size_t>(size)
    );
    env->ReleaseByteArrayElements(payload, bytes, JNI_ABORT);
    return ok ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL Java_com_llsl_viper4android_rootless_ViperNative_process(
    JNIEnv *env, jobject, jlong handle, jfloatArray samples, jint frame_count
) {
    if (frame_count <= 0 || env->GetArrayLength(samples) < frame_count * 2) return;
    auto *data = static_cast<float *>(env->GetPrimitiveArrayCritical(samples, nullptr));
    if (data == nullptr) return;
    viper_engine_process(Engine(handle), data, static_cast<uint32_t>(frame_count));
    env->ReleasePrimitiveArrayCritical(samples, data, 0);
}

JNIEXPORT jint JNICALL
Java_com_llsl_viper4android_rootless_ViperNative_samplingRate(JNIEnv *, jobject, jlong handle) {
    return static_cast<jint>(viper_engine_sampling_rate(Engine(handle)));
}

JNIEXPORT jlong JNICALL
Java_com_llsl_viper4android_rootless_ViperNative_processedFrames(JNIEnv *, jobject, jlong handle) {
    return static_cast<jlong>(viper_engine_processed_frames(Engine(handle)));
}

JNIEXPORT jint JNICALL Java_com_llsl_viper4android_rootless_ViperNative_convolverKernelId(
    JNIEnv *, jobject, jlong handle
) {
    return static_cast<jint>(viper_engine_convolver_kernel_id(Engine(handle)));
}

JNIEXPORT jstring JNICALL
Java_com_llsl_viper4android_rootless_ViperNative_versionName(JNIEnv *env, jobject) {
    return env->NewStringUTF(viper_engine_version_name());
}

JNIEXPORT jint JNICALL
Java_com_llsl_viper4android_rootless_ViperNative_versionCode(JNIEnv *, jobject) {
    return viper_engine_version_code();
}

} // extern "C"
