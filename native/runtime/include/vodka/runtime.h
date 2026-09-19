#pragma once

#include <string>
#include <vector>

namespace vodka {

struct RuntimePaths {
  std::string fex_binary = "/usr/bin/FEX";
  std::string wine_binary = "/usr/bin/wine";
  std::string guest_rootfs = "/opt/vodka/rootfs-x86_64";
  std::string host_thunks = "/usr/lib/fex-emu/HostThunks";
  std::string home = "/home/vodka";
  std::string wine_prefix = "/home/vodka/.wine";
  std::string display = ":0";
  std::string pulse_server;
  std::string studio_exe;
  std::string app_config = "/home/vodka/.config/fex-emu/RobloxStudio.json";
  std::string path = "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin";
  std::string wine_dll_overrides = "d3d11,dxgi,d3d10core,d3d9,d3d8=n,b";
  std::vector<std::string> studio_args;
  std::vector<std::string> extra_env;
  bool tso = true;
  bool hide_hypervisor = true;
};

struct LaunchPlan {
  std::vector<std::string> argv;
  std::vector<std::string> env;
  std::string working_dir;
};

LaunchPlan build_launch_plan(const RuntimePaths& paths);
std::string fex_config_json(const RuntimePaths& paths);
std::string fex_app_config_json(const RuntimePaths& paths);
std::string json_escape(const std::string& value);
std::string directory_of(const std::string& path);

}  // namespace vodka
