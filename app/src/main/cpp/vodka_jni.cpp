#include <jni.h>

#include <memory>
#include <mutex>
#include <string>
#include <vector>

#include "vodka/container.h"

namespace {

std::mutex g_mutex;
std::shared_ptr<vodka::Container> g_active;

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

std::vector<vodka::BindMount> to_binds(JNIEnv* env, jobjectArray array) {
  std::vector<vodka::BindMount> out;
  for (const auto& spec : to_strings(env, array)) {
    vodka::BindMount bind;
    const size_t first = spec.find(':');
    if (first == std::string::npos) {
      bind.source = spec;
      bind.target = spec;
    } else {
      bind.source = spec.substr(0, first);
      const std::string rest = spec.substr(first + 1);
      const size_t second = rest.find(':');
      if (second == std::string::npos) {
        bind.target = rest;
      } else {
        bind.target = rest.substr(0, second);
        bind.read_only = rest.substr(second + 1) == "ro";
      }
    }
    out.push_back(std::move(bind));
  }
  return out;
}

std::shared_ptr<vodka::Container> active() {
  std::lock_guard<std::mutex> lock(g_mutex);
  return g_active;
}

}  // namespace

extern "C" JNIEXPORT jstring JNICALL
Java_dev_vodka_runtime_NativeContainer_probe(JNIEnv* env, jobject, jstring rootfs) {
  const vodka::Capabilities caps = vodka::probe_capabilities(to_string(env, rootfs));
  std::string out;
  out += "kernel=" + caps.kernel_release + "\n";
  out += "arch=" + caps.host_arch + "\n";
  out += "page=" + std::to_string(caps.page_size) + "\n";
  out += std::string("user_ns=") + (caps.user_namespace ? "1" : "0") + "\n";
  out += std::string("mount_ns=") + (caps.mount_namespace ? "1" : "0") + "\n";
  out += std::string("pid_ns=") + (caps.pid_namespace ? "1" : "0") + "\n";
  out += std::string("proot=") + (caps.proot_present ? "1" : "0") + "\n";
  return env->NewStringUTF(out.c_str());
}

extern "C" JNIEXPORT jint JNICALL
Java_dev_vodka_runtime_NativeContainer_run(JNIEnv* env, jobject, jstring rootfs, jstring working_dir,
                                           jstring backend, jstring proot_path, jobjectArray binds,
                                           jobjectArray environment, jobjectArray command,
                                           jboolean mount_proc, jboolean mount_dev,
                                           jboolean fake_root, jstring log_path) {
  {
    std::lock_guard<std::mutex> lock(g_mutex);
    if (g_active && g_active->running()) return -2;
  }

  vodka::ContainerConfig config;
  config.rootfs = to_string(env, rootfs);
  config.working_dir = to_string(env, working_dir);
  config.proot_path = to_string(env, proot_path);
  const std::string backend_name = to_string(env, backend);
  config.backend = vodka::parse_backend(backend_name).value_or(vodka::Backend::Auto);
  config.binds = to_binds(env, binds);
  config.env = to_strings(env, environment);
  config.command = to_strings(env, command);
  config.mount_proc = mount_proc == JNI_TRUE;
  config.mount_dev = mount_dev == JNI_TRUE;
  config.fake_root = fake_root == JNI_TRUE;
  config.log_path = to_string(env, log_path);
  if (config.working_dir.empty()) config.working_dir = "/";

  auto container = std::make_shared<vodka::Container>(std::move(config));
  {
    std::lock_guard<std::mutex> lock(g_mutex);
    g_active = container;
  }

  const vodka::ExitStatus status = container->run();

  {
    std::lock_guard<std::mutex> lock(g_mutex);
    if (g_active == container) g_active.reset();
  }

  if (status.kind == vodka::ExitKind::Exited) return status.code;
  if (status.kind == vodka::ExitKind::Signaled) return -3;
  return -1;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_dev_vodka_runtime_NativeContainer_stop(JNIEnv*, jobject) {
  const auto container = active();
  if (!container) return JNI_FALSE;
  container->request_stop();
  return JNI_TRUE;
}

extern "C" JNIEXPORT jint JNICALL
Java_dev_vodka_runtime_NativeContainer_pid(JNIEnv*, jobject) {
  const auto container = active();
  return container ? static_cast<jint>(container->pid()) : -1;
}
