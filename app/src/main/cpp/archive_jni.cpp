#include <jni.h>

#include <string>

#include "vodka/archive.h"

namespace {

std::string to_string(JNIEnv* env, jstring value) {
  if (value == nullptr) return {};
  const char* chars = env->GetStringUTFChars(value, nullptr);
  std::string result = chars != nullptr ? chars : "";
  if (chars != nullptr) env->ReleaseStringUTFChars(value, chars);
  return result;
}

}  // namespace

extern "C" JNIEXPORT jstring JNICALL
Java_dev_vodka_runtime_NativeArchive_extractTarGz(JNIEnv* env, jobject, jstring archive_path,
                                                  jstring dest_dir) {
  const std::string archive = to_string(env, archive_path);
  const std::string dest = to_string(env, dest_dir);
  const vodka::ExtractResult result = vodka::extract_tar_gz(archive, dest);
  if (!result.ok) {
    const std::string message = "error: " + result.message;
    return env->NewStringUTF(message.c_str());
  }
  const std::string message =
      "ok " + std::to_string(result.files) + " " + std::to_string(result.bytes);
  return env->NewStringUTF(message.c_str());
}
