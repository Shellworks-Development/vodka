#include "vodka/container.h"

#include <fcntl.h>
#include <signal.h>
#include <sys/stat.h>
#include <sys/types.h>
#include <sys/wait.h>
#include <unistd.h>

#ifdef __linux__
#include <sched.h>
#include <sys/mount.h>
#endif

#include <cerrno>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <string>
#include <vector>

extern char** environ;

namespace vodka {
namespace {

std::string join_path(const std::string& base, const std::string& leaf) {
  if (base.empty() || base == "/") return leaf;
  if (leaf.empty()) return base;
  if (base.back() == '/') return base + (leaf.front() == '/' ? leaf.substr(1) : leaf);
  return base + (leaf.front() == '/' ? leaf : "/" + leaf);
}

bool ensure_dirs(const std::string& path) {
  if (path.empty()) return true;
  std::string current;
  size_t i = 0;
  if (path[0] == '/') {
    current = "/";
    i = 1;
  }
  while (i <= path.size()) {
    size_t slash = path.find('/', i);
    std::string part = path.substr(i, slash == std::string::npos ? std::string::npos : slash - i);
    if (!part.empty()) {
      current = current == "/" ? "/" + part : current + "/" + part;
      if (mkdir(current.c_str(), 0755) != 0 && errno != EEXIST) return false;
    }
    if (slash == std::string::npos) break;
    i = slash + 1;
  }
  return true;
}

bool write_file(const std::string& path, const std::string& data) {
  int fd = open(path.c_str(), O_WRONLY | O_CLOEXEC);
  if (fd < 0) return false;
  ssize_t written = write(fd, data.data(), data.size());
  close(fd);
  return written == static_cast<ssize_t>(data.size());
}

std::vector<std::string> build_arguments(const ContainerConfig& config, Backend backend) {
  std::vector<std::string> args;
  if (backend == Backend::Proot) {
    args.push_back(config.proot_path);
    args.push_back("-r");
    args.push_back(config.rootfs);
    args.push_back("-w");
    args.push_back(config.working_dir);
    if (config.fake_root) args.push_back("-0");
    for (const auto& bind : config.binds) {
      args.push_back("-b");
      args.push_back(bind.source + ":" + bind.target);
    }
  }
  for (const auto& arg : config.command) args.push_back(arg);
  return args;
}

std::string parent_directory(const std::string& path) {
  const size_t slash = path.find_last_of('/');
  if (slash == std::string::npos) return ".";
  if (slash == 0) return "/";
  return path.substr(0, slash);
}

std::vector<std::string> build_environment(const ContainerConfig& config, Backend backend) {
  std::vector<std::string> env;
  if (config.env.empty()) {
    for (char** entry = environ; entry != nullptr && *entry != nullptr; ++entry) {
      env.emplace_back(*entry);
    }
  } else {
    env = config.env;
  }
  bool has_path = false;
  bool has_no_seccomp = false;
  bool has_tmp = false;
  bool has_ld_path = false;
  bool has_loader = false;
  for (const auto& item : env) {
    if (item.rfind("PATH=", 0) == 0) has_path = true;
    if (item.rfind("PROOT_NO_SECCOMP=", 0) == 0) has_no_seccomp = true;
    if (item.rfind("PROOT_TMP_DIR=", 0) == 0) has_tmp = true;
    if (item.rfind("PROOT_LOADER=", 0) == 0) has_loader = true;
    if (item.rfind("LD_LIBRARY_PATH=", 0) == 0) has_ld_path = true;
  }
  if (!has_path) {
    env.emplace_back("PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin");
  }
  if (backend == Backend::Proot) {
    const std::string proot_dir = parent_directory(config.proot_path);
    if (!has_tmp) env.emplace_back("PROOT_TMP_DIR=" + proot_dir);
    if (!has_loader) env.emplace_back("PROOT_LOADER=" + proot_dir + "/libproot_loader.so");
    if (has_ld_path) {
      for (auto& item : env) {
        if (item.rfind("LD_LIBRARY_PATH=", 0) == 0) {
          item = "LD_LIBRARY_PATH=" + proot_dir + ":" + item.substr(16);
          break;
        }
      }
    } else {
      env.emplace_back("LD_LIBRARY_PATH=" + proot_dir);
    }
  }
  return env;
}

std::vector<char*> to_vector(std::vector<std::string>& storage) {
  std::vector<char*> out;
  out.reserve(storage.size() + 1);
  for (auto& item : storage) out.push_back(item.data());
  out.push_back(nullptr);
  return out;
}

void redirect_output(const std::string& path) {
  if (path.empty()) return;
  const int fd = open(path.c_str(), O_WRONLY | O_CREAT | O_TRUNC | O_CLOEXEC, 0644);
  if (fd < 0) return;
  dup2(fd, STDOUT_FILENO);
  dup2(fd, STDERR_FILENO);
  if (fd > STDERR_FILENO) close(fd);
}

#ifdef __linux__
[[noreturn]] void fail_child(const char* stage) {
  int saved = errno;
  dprintf(STDERR_FILENO, "vodka: child setup failed at %s: %s\n", stage, strerror(saved));
  _exit(127);
}

void apply_bind(const ContainerConfig& config, const BindMount& bind) {
  std::string target = join_path(config.rootfs, bind.target);
  if (!ensure_dirs(target)) fail_child("mkdir bind target");
  if (mount(bind.source.c_str(), target.c_str(), nullptr, MS_BIND | MS_REC, nullptr) != 0) {
    fail_child("bind mount");
  }
  if (bind.read_only) {
    if (mount(nullptr, target.c_str(), nullptr, MS_REMOUNT | MS_BIND | MS_RDONLY, nullptr) != 0) {
      fail_child("remount read-only");
    }
  }
}

[[noreturn]] void exec_namespaced(const ContainerConfig& config, char* const argv[],
                                  char* const envp[]) {
  uid_t uid = getuid();
  gid_t gid = getgid();

  if (unshare(CLONE_NEWUSER) != 0) fail_child("unshare(CLONE_NEWUSER)");

  write_file("/proc/self/setgroups", "deny");

  char uid_map[64];
  std::snprintf(uid_map, sizeof(uid_map), "0 %u 1", static_cast<unsigned>(uid));
  if (!write_file("/proc/self/uid_map", uid_map)) fail_child("uid_map");

  char gid_map[64];
  std::snprintf(gid_map, sizeof(gid_map), "0 %u 1", static_cast<unsigned>(gid));
  if (!write_file("/proc/self/gid_map", gid_map)) fail_child("gid_map");

  if (unshare(CLONE_NEWNS) != 0) fail_child("unshare(CLONE_NEWNS)");

  if (mount(nullptr, "/", nullptr, MS_REC | MS_PRIVATE, nullptr) != 0) {
    fail_child("make mounts private");
  }

  for (const auto& bind : config.binds) apply_bind(config, bind);

  if (config.mount_proc) {
    std::string target = join_path(config.rootfs, "/proc");
    ensure_dirs(target);
    if (mount("proc", target.c_str(), "proc", MS_NOSUID | MS_NOEXEC | MS_NODEV, nullptr) != 0) {
      fail_child("mount /proc");
    }
  }
  if (config.mount_dev) {
    std::string target = join_path(config.rootfs, "/dev");
    ensure_dirs(target);
    mount("/dev", target.c_str(), nullptr, MS_BIND | MS_REC, nullptr);
  }

  if (chroot(config.rootfs.c_str()) != 0) fail_child("chroot");
  if (chdir(config.working_dir.c_str()) != 0) fail_child("chdir");

  execvpe(config.command.front().c_str(), argv, envp);
  fail_child("execvpe");
}
#endif

ExitStatus interpret(pid_t pid, int status, const std::string& start_error) {
  ExitStatus result;
  if (pid < 0) {
    result.kind = ExitKind::StartFailed;
    result.message = start_error.empty() ? "fork failed" : start_error;
    return result;
  }
  if (WIFEXITED(status)) {
    result.kind = ExitKind::Exited;
    result.code = WEXITSTATUS(status);
    return result;
  }
  if (WIFSIGNALED(status)) {
    result.kind = ExitKind::Signaled;
    result.code = WTERMSIG(status);
    return result;
  }
  result.kind = ExitKind::StartFailed;
  result.message = "unknown child state";
  return result;
}

}  // namespace

Container::Container(ContainerConfig config) : config_(std::move(config)) {}

bool Container::running() const {
  pid_t pid = child_.load();
  if (pid <= 0) return false;
  return kill(pid, 0) == 0 || errno == EPERM;
}

void Container::request_stop() {
  stop_requested_.store(true);
  pid_t pid = child_.load();
  if (pid > 0) kill(pid, SIGTERM);
}

ExitStatus Container::run() {
  if (config_.command.empty()) {
    return {ExitKind::StartFailed, -1, "empty command"};
  }
#ifndef __linux__
  return {ExitKind::StartFailed, -1, "container requires linux"};
#else
  Capabilities caps = probe_capabilities(config_.rootfs);
  resolved_ = select_backend(config_.backend, caps);
  if (resolved_ == Backend::Namespaces && !caps.user_namespace) {
    return {ExitKind::StartFailed, -1, "namespaces requested but unavailable"};
  }

  std::vector<std::string> arguments = build_arguments(config_, resolved_);
  std::vector<std::string> environment = build_environment(config_, resolved_);
  std::vector<char*> argv = to_vector(arguments);
  std::vector<char*> envp = to_vector(environment);

  pid_t pid = fork();
  if (pid < 0) {
    return {ExitKind::StartFailed, -1, std::string("fork: ") + strerror(errno)};
  }
  if (pid == 0) {
    redirect_output(config_.log_path);
    if (resolved_ == Backend::Proot) {
      execvpe(config_.proot_path.c_str(), argv.data(), envp.data());
      fail_child("execvpe(proot)");
    }
    exec_namespaced(config_, argv.data(), envp.data());
  }

  child_.store(pid);
  int status = 0;
  pid_t waited = waitpid(pid, &status, 0);
  if (waited < 0 && errno == EINTR) waited = waitpid(pid, &status, 0);
  child_.store(-1);
  return interpret(waited, status, "");
#endif
}

}  // namespace vodka
