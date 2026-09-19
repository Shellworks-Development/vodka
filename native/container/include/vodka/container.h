#pragma once

#include <sys/types.h>

#include <atomic>
#include <optional>
#include <string>
#include <vector>

namespace vodka {

enum class Backend { Auto, Namespaces, Proot };

std::string to_string(Backend backend);
std::optional<Backend> parse_backend(const std::string& name);

struct BindMount {
  std::string source;
  std::string target;
  bool read_only = false;
};

struct Capabilities {
  bool user_namespace = false;
  bool mount_namespace = false;
  bool pid_namespace = false;
  bool proot_present = false;
  std::string kernel_release;
  std::string host_arch;
  long page_size = 0;
};

struct ContainerConfig {
  std::string rootfs;
  std::string working_dir = "/";
  std::string proot_path = "proot";
  Backend backend = Backend::Auto;
  std::vector<BindMount> binds;
  std::vector<std::string> env;
  std::vector<std::string> command;
  bool mount_proc = true;
  bool mount_dev = true;
  bool fake_root = true;
  std::string log_path;
};

enum class ExitKind { Exited, Signaled, StartFailed };

struct ExitStatus {
  ExitKind kind = ExitKind::StartFailed;
  int code = -1;
  std::string message;

  bool ok() const { return kind == ExitKind::Exited && code == 0; }
};

Capabilities probe_capabilities(const std::string& rootfs = {});
Backend select_backend(Backend requested, const Capabilities& caps);

class Container {
 public:
  explicit Container(ContainerConfig config);

  ExitStatus run();
  pid_t pid() const { return child_.load(); }
  bool running() const;
  void request_stop();

  Backend resolved_backend() const { return resolved_; }

 private:
  ContainerConfig config_;
  Backend resolved_ = Backend::Proot;
  std::atomic<pid_t> child_{-1};
  std::atomic<bool> stop_requested_{false};
};

}  // namespace vodka
