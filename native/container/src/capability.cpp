#include "vodka/container.h"

#include <fcntl.h>
#include <sys/utsname.h>
#include <unistd.h>

#ifdef __linux__
#include <sched.h>
#include <sys/wait.h>
#endif

#include <cstdio>

#include <cstdlib>
#include <cstring>
#include <string>
#include <vector>

namespace vodka {
namespace {

#ifdef __linux__
constexpr int kProbeUser = 1 << 0;
constexpr int kProbeMount = 1 << 1;
constexpr int kProbePid = 1 << 2;

bool write_control(const char* path, const std::string& data) {
  int fd = open(path, O_WRONLY | O_CLOEXEC);
  if (fd < 0) return false;
  ssize_t written = write(fd, data.data(), data.size());
  close(fd);
  return written == static_cast<ssize_t>(data.size());
}

bool map_identity(uid_t uid, gid_t gid) {
  write_control("/proc/self/setgroups", "deny");
  char uid_map[64];
  char gid_map[64];
  std::snprintf(uid_map, sizeof(uid_map), "0 %u 1", static_cast<unsigned>(uid));
  std::snprintf(gid_map, sizeof(gid_map), "0 %u 1", static_cast<unsigned>(gid));
  return write_control("/proc/self/uid_map", uid_map) &&
         write_control("/proc/self/gid_map", gid_map);
}

int probe_namespace_mask() {
  int fds[2];
  if (pipe(fds) != 0) return 0;

  pid_t pid = fork();
  if (pid < 0) {
    close(fds[0]);
    close(fds[1]);
    return 0;
  }
  if (pid == 0) {
    close(fds[0]);
    uid_t uid = getuid();
    gid_t gid = getgid();
    int mask = 0;
    if (unshare(CLONE_NEWUSER) == 0 && map_identity(uid, gid)) {
      mask |= kProbeUser;
      if (unshare(CLONE_NEWNS) == 0) mask |= kProbeMount;
      if (unshare(CLONE_NEWPID) == 0) mask |= kProbePid;
    }
    ssize_t ignored = write(fds[1], &mask, sizeof(mask));
    (void)ignored;
    _exit(0);
  }

  close(fds[1]);
  int mask = 0;
  ssize_t got = read(fds[0], &mask, sizeof(mask));
  close(fds[0]);
  int status = 0;
  waitpid(pid, &status, 0);
  if (got != static_cast<ssize_t>(sizeof(mask))) return 0;
  return mask;
}
#endif

bool exists_executable(const std::string& path) {
  return access(path.c_str(), X_OK) == 0;
}

bool find_on_path(const std::string& name) {
  const char* path_env = getenv("PATH");
  if (path_env == nullptr) return false;
  std::string path(path_env);
  size_t start = 0;
  while (start <= path.size()) {
    size_t end = path.find(':', start);
    std::string dir = path.substr(start, end - start);
    if (!dir.empty() && exists_executable(dir + "/" + name)) return true;
    if (end == std::string::npos) break;
    start = end + 1;
  }
  return false;
}

bool detect_proot(const std::string& rootfs) {
  if (!rootfs.empty()) {
    if (exists_executable(rootfs + "/usr/bin/proot")) return true;
    if (exists_executable(rootfs + "/bin/proot")) return true;
  }
  return find_on_path("proot");
}

}  // namespace

std::string to_string(Backend backend) {
  switch (backend) {
    case Backend::Auto:
      return "auto";
    case Backend::Namespaces:
      return "namespaces";
    case Backend::Proot:
      return "proot";
  }
  return "unknown";
}

std::optional<Backend> parse_backend(const std::string& name) {
  if (name == "auto") return Backend::Auto;
  if (name == "namespaces") return Backend::Namespaces;
  if (name == "proot") return Backend::Proot;
  return std::nullopt;
}

Capabilities probe_capabilities(const std::string& rootfs) {
  Capabilities caps;

  struct utsname info {};
  if (uname(&info) == 0) {
    caps.kernel_release = info.release;
    caps.host_arch = info.machine;
  }

  long page = sysconf(_SC_PAGESIZE);
  caps.page_size = page > 0 ? page : 0;

#ifdef __linux__
  int mask = probe_namespace_mask();
  caps.user_namespace = (mask & kProbeUser) != 0;
  caps.mount_namespace = (mask & kProbeMount) != 0;
  caps.pid_namespace = (mask & kProbePid) != 0;
#endif

  caps.proot_present = detect_proot(rootfs);
  return caps;
}

Backend select_backend(Backend requested, const Capabilities& caps) {
  if (requested != Backend::Auto) return requested;
  if (caps.user_namespace && caps.mount_namespace) return Backend::Namespaces;
  return Backend::Proot;
}

}  // namespace vodka
