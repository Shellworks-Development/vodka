#include <jni.h>

#include <string>
#include <vector>

#include "vodka/runtime.h"

namespace {

std::string to_string(JNIEnv* env, jstring value) {
  if (value == nullptr) return {};
  const char* chars = env->GetStringUTFChars(value, nullptr);
  std::string result = chars != nullptr ? chars : "";
  if (chars != nullptr) env->ReleaseStringUTFChars(value, chars);
  return result;
}

std::vector<std::string> to_strings(JNIEnv* env, jobjectArray array) {
  std::vector<std::string> out;
  if (array == nullptr) return out;
  const jsize count = env->GetArrayLength(array);
  out.reserve(static_cast<size_t>(count));
  for (jsize i = 0; i < count; ++i) {
    auto element = static_cast<jstring>(env->GetObjectArrayElement(array, i));
    out.push_back(to_string(env, element));
    env->DeleteLocalRef(element);
  }
  return out;
}

jobjectArray to_array(JNIEnv* env, const std::vector<std::string>& values) {
  jclass string_class = env->FindClass("java/lang/String");
  jobjectArray array =
      env->NewObjectArray(static_cast<jsize>(values.size()), string_class, nullptr);
  for (jsize i = 0; i < static_cast<jsize>(values.size()); ++i) {
    jstring value = env->NewStringUTF(values[static_cast<size_t>(i)].c_str());
    env->SetObjectArrayElement(array, i, value);
    env->DeleteLocalRef(value);
  }
  return array;
}

vodka::RuntimePaths parse_paths(JNIEnv* env, jobjectArray config) {
  vodka::RuntimePaths paths;
  for (const auto& entry : to_strings(env, config)) {
    const size_t eq = entry.find('=');
    if (eq == std::string::npos) continue;
    const std::string key = entry.substr(0, eq);
    const std::string value = entry.substr(eq + 1);
    if (key == "fex") paths.fex_binary = value;
    else if (key == "wine") paths.wine_binary = value;
    else if (key == "rootfs") paths.guest_rootfs = value;
    else if (key == "thunks") paths.host_thunks = value;
    else if (key == "home") paths.home = value;
    else if (key == "wineprefix") paths.wine_prefix = value;
    else if (key == "display") paths.display = value;
    else if (key == "pulse") paths.pulse_server = value;
    else if (key == "studio") paths.studio_exe = value;
    else if (key == "appconfig") paths.app_config = value;
    else if (key == "path") paths.path = value;
    else if (key == "overrides") paths.wine_dll_overrides = value;
    else if (key == "tso") paths.tso = value == "1";
    else if (key == "hidehypervisor") paths.hide_hypervisor = value == "1";
    else if (key == "arg") paths.studio_args.push_back(value);
    else if (key == "env") paths.extra_env.push_back(value);
  }
  return paths;
}

}  // namespace

extern "C" JNIEXPORT jobjectArray JNICALL
Java_dev_vodka_runtime_NativeRuntime_planArgs(JNIEnv* env, jobject, jobjectArray config) {
  return to_array(env, vodka::build_launch_plan(parse_paths(env, config)).argv);
}

extern "C" JNIEXPORT jobjectArray JNICALL
Java_dev_vodka_runtime_NativeRuntime_planEnv(JNIEnv* env, jobject, jobjectArray config) {
  return to_array(env, vodka::build_launch_plan(parse_paths(env, config)).env);
}

extern "C" JNIEXPORT jstring JNICALL
Java_dev_vodka_runtime_NativeRuntime_planWorkdir(JNIEnv* env, jobject, jobjectArray config) {
  return env->NewStringUTF(vodka::build_launch_plan(parse_paths(env, config)).working_dir.c_str());
}

extern "C" JNIEXPORT jstring JNICALL
Java_dev_vodka_runtime_NativeRuntime_fexConfigJson(JNIEnv* env, jobject, jobjectArray config) {
  return env->NewStringUTF(vodka::fex_config_json(parse_paths(env, config)).c_str());
}

extern "C" JNIEXPORT jstring JNICALL
Java_dev_vodka_runtime_NativeRuntime_fexAppConfigJson(JNIEnv* env, jobject, jobjectArray config) {
  return env->NewStringUTF(vodka::fex_app_config_json(parse_paths(env, config)).c_str());
}
