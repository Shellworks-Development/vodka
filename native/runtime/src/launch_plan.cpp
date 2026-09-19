#include "vodka/runtime.h"

#include <cstdio>

namespace vodka {
namespace {

void set_env(std::vector<std::string>& env, const std::string& key, const std::string& value) {
  env.push_back(key + "=" + value);
}

std::string bool_string(bool value) { return value ? "1" : "0"; }

}  // namespace

std::string directory_of(const std::string& path) {
  const size_t slash = path.find_last_of('/');
  if (slash == std::string::npos) return ".";
  if (slash == 0) return "/";
  return path.substr(0, slash);
}

std::string json_escape(const std::string& value) {
  std::string out;
  out.reserve(value.size() + 8);
  for (const char c : value) {
    switch (c) {
      case '"':
        out += "\\\"";
        break;
      case '\\':
        out += "\\\\";
        break;
      case '\n':
        out += "\\n";
        break;
      case '\r':
        out += "\\r";
        break;
      case '\t':
        out += "\\t";
        break;
      default:
        if (static_cast<unsigned char>(c) < 0x20) {
          char buffer[8];
          std::snprintf(buffer, sizeof(buffer), "\\u%04x", static_cast<unsigned char>(c));
          out += buffer;
        } else {
          out += c;
        }
    }
  }
  return out;
}

LaunchPlan build_launch_plan(const RuntimePaths& paths) {
  LaunchPlan plan;
  plan.argv.push_back(paths.fex_binary);

  if (!paths.wine_binary.empty()) plan.argv.push_back(paths.wine_binary);
  if (!paths.studio_exe.empty()) plan.argv.push_back(paths.studio_exe);
  for (const auto& arg : paths.studio_args) plan.argv.push_back(arg);

  set_env(plan.env, "HOME", paths.home);
  set_env(plan.env, "XDG_CONFIG_HOME", paths.home + "/.config");
  set_env(plan.env, "XDG_DATA_HOME", paths.home + "/.local/share");
  set_env(plan.env, "XDG_CACHE_HOME", paths.home + "/.cache");
  set_env(plan.env, "PATH", paths.path);
  set_env(plan.env, "FEX_APP_CONFIG", paths.app_config);
  set_env(plan.env, "WINEPREFIX", paths.wine_prefix);
  set_env(plan.env, "WINEDEBUG", "-all");

  if (!paths.display.empty()) set_env(plan.env, "DISPLAY", paths.display);
  if (!paths.pulse_server.empty()) set_env(plan.env, "PULSE_SERVER", paths.pulse_server);
  if (!paths.wine_dll_overrides.empty()) {
    set_env(plan.env, "WINEDLLOVERRIDES", paths.wine_dll_overrides);
  }

  for (const auto& item : paths.extra_env) plan.env.push_back(item);

  plan.working_dir = !paths.studio_exe.empty() ? directory_of(paths.studio_exe) : paths.home;
  if (plan.working_dir.empty()) plan.working_dir = "/";
  return plan;
}

std::string fex_config_json(const RuntimePaths& paths) {
  std::string out;
  out += "{\n  \"Config\": {\n";
  out += "    \"RootFS\": \"" + json_escape(paths.guest_rootfs) + "\",\n";
  out += "    \"ThunkHostLibs\": \"" + json_escape(paths.host_thunks) + "\",\n";
  out += "    \"TSOEnabled\": \"" + bool_string(paths.tso) + "\",\n";
  out += "    \"HideHypervisorBit\": \"" + bool_string(paths.hide_hypervisor) + "\"\n";
  out += "  }\n}\n";
  return out;
}

std::string fex_app_config_json(const RuntimePaths& paths) {
  std::string out;
  out += "{\n  \"Config\": {\n";
  out += "    \"HideHypervisorBit\": \"" + bool_string(paths.hide_hypervisor) + "\",\n";
  out += "    \"RootFS\": \"" + json_escape(paths.guest_rootfs) + "\"\n";
  out += "  }\n}\n";
  return out;
}

}  // namespace vodka
