#include <jni.h>

#include <mutex>
#include <string>

#include "starintel_ecl_adapter.h"

namespace {
constexpr size_t kMaxRequestBytes = 1024U * 1024U;
#ifdef STARINTEL_HAS_ECL_ADAPTER
constexpr size_t kMaxResponseBytes = 2U * 1024U * 1024U;
#endif
std::mutex runtime_mutex;
bool runtime_started = false;

std::string from_jstring(JNIEnv* env, jstring value) {
    if (value == nullptr) return {};
    const char* chars = env->GetStringUTFChars(value, nullptr);
    if (chars == nullptr) return {};
    std::string result(chars);
    env->ReleaseStringUTFChars(value, chars);
    return result;
}

jstring to_jstring(JNIEnv* env, const std::string& value) {
    return env->NewStringUTF(value.c_str());
}
}  // namespace

extern "C" JNIEXPORT jstring JNICALL
Java_actor_starintel_android_lisp_JniNativeLispBridge_nativeStart(
    JNIEnv* env, jobject, jstring runtime_path) {
    std::lock_guard<std::mutex> lock(runtime_mutex);
    if (runtime_started) return to_jstring(env, "ready");
    const std::string path = from_jstring(env, runtime_path);
    if (path.empty()) return to_jstring(env, "Invalid ECL runtime directory");
#ifdef STARINTEL_HAS_ECL_ADAPTER
    char* error = nullptr;
    const int result = starintel_ecl_start(path.c_str(), &error);
    if (result == 0) {
        runtime_started = true;
        if (error != nullptr) starintel_ecl_free(error);
        return to_jstring(env, "ready");
    }
    std::string detail = error == nullptr ? "ECL adapter failed to start" : error;
    if (error != nullptr) starintel_ecl_free(error);
    return to_jstring(env, detail.substr(0, 400));
#else
    return to_jstring(env, "ECL/Tek9 adapter is not packaged for this ABI");
#endif
}

extern "C" JNIEXPORT jstring JNICALL
Java_actor_starintel_android_lisp_JniNativeLispBridge_nativeRequest(
    JNIEnv* env, jobject, jstring request) {
    std::lock_guard<std::mutex> lock(runtime_mutex);
    const std::string encoded = from_jstring(env, request);
    if (encoded.size() > kMaxRequestBytes) {
        return to_jstring(env, R"({"ok":false,"error":{"code":"request-too-large","detail":"Request exceeds 1 MiB"}})");
    }
#ifdef STARINTEL_HAS_ECL_ADAPTER
    if (!runtime_started) {
        return to_jstring(env, R"({"ok":false,"error":{"code":"not-started","detail":"ECL runtime is not started"}})");
    }
    char* response = starintel_ecl_request(encoded.c_str());
    if (response == nullptr) {
        return to_jstring(env, R"({"ok":false,"error":{"code":"empty-response","detail":"ECL returned no response"}})");
    }
    std::string result(response);
    starintel_ecl_free(response);
    if (result.size() > kMaxResponseBytes) {
        return to_jstring(env, R"({"ok":false,"error":{"code":"response-too-large","detail":"Response exceeds 2 MiB"}})");
    }
    return to_jstring(env, result);
#else
    return to_jstring(env, R"({"ok":false,"error":{"code":"adapter-unavailable","detail":"ECL/Tek9 adapter is not packaged for this ABI"}})");
#endif
}

extern "C" JNIEXPORT void JNICALL
Java_actor_starintel_android_lisp_JniNativeLispBridge_nativeStop(JNIEnv*, jobject) {
    std::lock_guard<std::mutex> lock(runtime_mutex);
#ifdef STARINTEL_HAS_ECL_ADAPTER
    if (runtime_started) starintel_ecl_stop();
#endif
    runtime_started = false;
}
