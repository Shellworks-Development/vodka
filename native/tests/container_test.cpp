#include "vodka/container.h"

#include <sys/stat.h>
#include <unistd.h>

#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <string>

namespace {

int g_failures = 0;

void check(bool condition, const char* label) {
  std::printf("[%s] %s\n", condition ? " ok " : "FAIL", label);
  if (!condition) ++g_failures;
}

std::string temp_dir() {
  char pattern[] = "/tmp/vodka-test-XXXXXX";
  char* dir = mkdtemp(pattern);
  return dir != nullptr ? std::string(dir) : std::string();
}

void write_executable(const std::string& path, const std::string& body) {
  FILE* file = std::fopen(path.c_str(), "w");
  if (file == nullptr) return;
  std::fputs(body.c_str(), file);
  std::fclose(file);
  chmod(path.c_str(), 0755);
}

std::string first_present(const char* const* candidates, size_t count) {
  for (size_t i = 0; i < count; ++i) {
    if (access(candidates[i], X_OK) == 0) return candidates[i];
  }
  return {};
}

void test_backend_parsing() {
  check(vodka::to_string(vodka::Backend::Namespaces) == "namespaces", "to_string namespaces");
  auto parsed = vodka::parse_backend("proot");
  check(parsed.has_value() && *parsed == vodka::Backend::Proot, "parse_backend proot");
  check(!vodka::parse_backend("nonsense").has_value(), "parse_backend rejects unknown");
}

void test_capabilities() {
  vodka::Capabilities caps = vodka::probe_capabilities();
  check(!caps.host_arch.empty(), "host arch detected");
  check(caps.page_size > 0, "page size detected");
  std::printf("       arch=%s kernel=%s page=%ld user_ns=%d mount_ns=%d pid_ns=%d proot=%d\n",
              caps.host_arch.c_str(), caps.kernel_release.c_str(), caps.page_size,
              caps.user_namespace, caps.mount_namespace, caps.pid_namespace, caps.proot_present);
}

void test_selection() {
  vodka::Capabilities caps;
  caps.user_namespace = true;
  caps.mount_namespace = true;
  check(vodka::select_backend(vodka::Backend::Auto, caps) == vodka::Backend::Namespaces,
        "auto selects namespaces when available");
  caps.mount_namespace = false;
  check(vodka::select_backend(vodka::Backend::Auto, caps) == vodka::Backend::Proot,
        "auto falls back to proot");
  check(vodka::select_backend(vodka::Backend::Proot, caps) == vodka::Backend::Proot,
        "explicit proot honored");
}

void test_proot_detection_in_rootfs() {
  std::string root = temp_dir();
  check(!root.empty(), "temp dir created");
  if (root.empty()) return;
  mkdir((root + "/bin").c_str(), 0755);
  write_executable(root + "/bin/proot", "#!/bin/sh\nexit 0\n");
  vodka::Capabilities caps = vodka::probe_capabilities(root);
  check(caps.proot_present, "proot detected inside rootfs");
}

void test_proot_launch_plumbing() {
  const char* candidates[] = {"/bin/echo", "/usr/bin/echo"};
  std::string echo = first_present(candidates, 2);
  check(!echo.empty(), "echo binary located");
  if (echo.empty()) return;

  vodka::ContainerConfig config;
  config.rootfs = "/";
  config.backend = vodka::Backend::Proot;
  config.proot_path = echo;
  config.command = {"vodka-exec-ok"};
  vodka::Container container(config);
  vodka::ExitStatus status = container.run();
  check(status.kind == vodka::ExitKind::Exited && status.code == 0,
        "proot backend exec/wait plumbing");
}

void test_namespace_launch() {
  const char* candidates[] = {"/bin/echo", "/usr/bin/echo"};
  std::string echo = first_present(candidates, 2);
  vodka::Capabilities caps = vodka::probe_capabilities();
  if (!caps.user_namespace || !caps.mount_namespace) {
    std::printf("[skip] namespace test (unavailable in this environment)\n");
    return;
  }
  if (echo.empty()) {
    std::printf("[skip] namespace test (no echo binary)\n");
    return;
  }

  vodka::ContainerConfig config;
  config.rootfs = "/";
  config.backend = vodka::Backend::Namespaces;
  config.mount_proc = false;
  config.mount_dev = false;
  config.command = {echo, "vodka-namespace-ok"};
  vodka::Container container(config);
  vodka::ExitStatus status = container.run();
  std::printf("       namespace exit kind=%d code=%d message=%s\n",
              static_cast<int>(status.kind), status.code, status.message.c_str());
  check(container.resolved_backend() == vodka::Backend::Namespaces, "namespaces backend resolved");
  check(status.kind == vodka::ExitKind::Exited && status.code == 0,
        "namespace backend exec/wait plumbing");
}

}  // namespace

int main() {
  test_backend_parsing();
  test_capabilities();
  test_selection();
  test_proot_detection_in_rootfs();
  test_proot_launch_plumbing();
  test_namespace_launch();

  if (g_failures == 0) {
    std::printf("\nall container tests passed\n");
    return 0;
  }
  std::printf("\n%d container test(s) failed\n", g_failures);
  return 1;
}
