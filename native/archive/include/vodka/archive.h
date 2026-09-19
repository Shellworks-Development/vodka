#pragma once

#include <string>

namespace vodka {

struct ExtractResult {
  bool ok = false;
  size_t files = 0;
  size_t bytes = 0;
  std::string message;
};

ExtractResult extract_tar_gz(const std::string& archive_path, const std::string& dest_dir);
ExtractResult extract_tar(const std::string& archive_path, const std::string& dest_dir);

}  // namespace vodka
