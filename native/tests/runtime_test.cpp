#include "vodka/runtime.h"

#include <algorithm>
#include <cstdio>
#include <string>
#include <vector>

namespace {

int g_failures = 0;

void check(bool condition, const char* label) {
  std::printf("[%s] %s\n", condition ? " ok " : "FAIL", label);
  if (!condition) ++g_failures;
}

bool has_env(const std::vector<std::string>& env, const std::string& entry) {
  return std::find(env.begin(), env.end(), entry) != env.end();
}

bool starts_with(const std::string& value, const std::string& prefix) {
  return value.rfind(prefix, 0) == 0;
}

void test_launch_plan_argv() {
  vodka::RuntimePaths paths;
  paths.studio_exe = "/home/vodka/.wine/drive_c/Program Files/Roblox/RobloxStudioBeta.exe";
  paths.studio_args = {"-test"};
  const vodka::LaunchPlan plan = vodka::build_launch_plan(paths);

  check(plan.argv.size() == 4, "argv size");
  check(plan.argv[0] == "/usr/bin/FEX", "argv[0] is FEX");
  check(plan.argv[1] == "/usr/bin/wine", "argv[1] is wine");
  check(plan.argv[2] == paths.studio_exe, "argv[2] is studio");
  check(plan.argv[3] == "-test", "argv[3] is studio arg");
}

void test_launch_plan_env() {
  vodka::RuntimePaths paths;
  vodka::LaunchPlan plan = vodka::build_launch_plan(paths);

  check(has_env(plan.env, "HOME=/home/vodka"), "HOME set");
  check(has_env(plan.env, "WINEPREFIX=/home/vodka/.wine"), "WINEPREFIX set");
  check(has_env(plan.env, "FEX_APP_CONFIG=/home/vodka/.config/fex-emu/RobloxStudio.json"),
        "FEX_APP_CONFIG set");
  check(has_env(plan.env, "XDG_DATA_HOME=/home/vodka/.local/share"), "XDG_DATA_HOME set");
  check(has_env(plan.env, "DISPLAY=:0"), "DISPLAY set");
  check(!has_env(plan.env, "PULSE_SERVER="), "PULSE_SERVER omitted when empty");

  paths.pulse_server = "unix:/tmp/pulse";
  plan = vodka::build_launch_plan(paths);
  check(has_env(plan.env, "PULSE_SERVER=unix:/tmp/pulse"), "PULSE_SERVER set when provided");
}

void test_working_dir() {
  vodka::RuntimePaths paths;
  paths.studio_exe = "/opt/roblox/RobloxStudioBeta.exe";
  vodka::LaunchPlan plan = vodka::build_launch_plan(paths);
  check(plan.working_dir == "/opt/roblox", "working dir is studio directory");

  paths.studio_exe.clear();
  plan = vodka::build_launch_plan(paths);
  check(plan.working_dir == "/home/vodka", "working dir falls back to home");
}

void test_config_json() {
  vodka::RuntimePaths paths;
  paths.guest_rootfs = "/data/data/dev.vodka/files/runtime/rootfs-x86_64";
  const std::string config = vodka::fex_config_json(paths);
  check(config.find("\"RootFS\": \"/data/data/dev.vodka/files/runtime/rootfs-x86_64\"") !=
            std::string::npos,
        "RootFS written to config");
  check(config.find("\"TSOEnabled\": \"1\"") != std::string::npos, "TSO enabled by default");
  check(config.find("\"HideHypervisorBit\": \"1\"") != std::string::npos, "hypervisor bit hidden");
  check(config.find("\"ThunkHostLibs\"") != std::string::npos, "thunk host libs present");

  paths.tso = false;
  check(vodka::fex_config_json(paths).find("\"TSOEnabled\": \"0\"") != std::string::npos,
        "TSO disabled when requested");
}

void test_json_escape() {
  check(vodka::json_escape("plain") == "plain", "escape plain");
  check(vodka::json_escape("a\"b") == "a\\\"b", "escape quote");
  check(vodka::json_escape("a\\b") == "a\\\\b", "escape backslash");
  check(vodka::json_escape("a\nb") == "a\\nb", "escape newline");
  check(vodka::json_escape(std::string("x\x01y")) == "x\\u0001y", "escape control char");

  vodka::RuntimePaths paths;
  paths.guest_rootfs = "/weird\"path";
  const std::string config = vodka::fex_config_json(paths);
  check(config.find("\"/weird\\\"path\"") != std::string::npos, "config escapes path");
}

void test_directory_of() {
  check(vodka::directory_of("/a/b/c") == "/a/b", "directory of nested");
  check(vodka::directory_of("/a") == "/", "directory of root child");
  check(vodka::directory_of("bare") == ".", "directory of bare name");
}

void test_app_config() {
  vodka::RuntimePaths paths;
  paths.hide_hypervisor = true;
  const std::string app = vodka::fex_app_config_json(paths);
  check(starts_with(app, "{\n  \"Config\": {"), "app config shape");
  check(app.find("\"HideHypervisorBit\": \"1\"") != std::string::npos, "app config hides hypervisor");
}

}  // namespace

int main() {
  test_launch_plan_argv();
  test_launch_plan_env();
  test_working_dir();
  test_config_json();
  test_json_escape();
  test_directory_of();
  test_app_config();

  if (g_failures == 0) {
    std::printf("\nall runtime tests passed\n");
    return 0;
  }
  std::printf("\n%d runtime test(s) failed\n", g_failures);
  return 1;
}
