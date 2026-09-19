#include "vodka/archive.h"

#include <sys/stat.h>
#include <unistd.h>
#include <zlib.h>

#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <fstream>
#include <string>

namespace {

int g_failures = 0;

void check(bool condition, const char* label) {
  std::printf("[%s] %s\n", condition ? " ok " : "FAIL", label);
  if (!condition) ++g_failures;
}

std::string temp_path() {
  char pattern[] = "/tmp/vodka-archive-XXXXXX";
  char* dir = mkdtemp(pattern);
  return dir != nullptr ? std::string(dir) : std::string();
}

bool write_file(const std::string& path, const std::string& data) {
  std::ofstream out(path, std::ios::binary);
  if (!out) return false;
  out.write(data.data(), static_cast<std::streamsize>(data.size()));
  return out.good();
}

std::string read_file(const std::string& path) {
  std::ifstream in(path, std::ios::binary);
  std::string data((std::istreambuf_iterator<char>(in)), std::istreambuf_iterator<char>());
  return data;
}

bool is_file(const std::string& path) {
  struct stat info {};
  return stat(path.c_str(), &info) == 0 && S_ISREG(info.st_mode);
}

bool is_dir(const std::string& path) {
  struct stat info {};
  return stat(path.c_str(), &info) == 0 && S_ISDIR(info.st_mode);
}

bool is_symlink(const std::string& path) {
  struct stat info {};
  return lstat(path.c_str(), &info) == 0 && S_ISLNK(info.st_mode);
}

void append_tar_header(std::string& out, const std::string& name, long size, char type,
                       const std::string& link) {
  unsigned char header[512] = {0};
  std::snprintf(reinterpret_cast<char*>(header), 100, "%s", name.c_str());
  std::snprintf(reinterpret_cast<char*>(header + 100), 8, "%07o", 0644);
  std::snprintf(reinterpret_cast<char*>(header + 108), 8, "%07o", 0);
  std::snprintf(reinterpret_cast<char*>(header + 116), 8, "%07o", 0);
  std::snprintf(reinterpret_cast<char*>(header + 124), 12, "%011o",
                static_cast<unsigned>(size > 0 ? size : 0));
  std::snprintf(reinterpret_cast<char*>(header + 136), 12, "%011o", 0);
  header[156] = static_cast<unsigned char>(type);
  if (!link.empty()) {
    std::snprintf(reinterpret_cast<char*>(header + 157), 100, "%s", link.c_str());
  }
  std::memcpy(header + 257, "ustar", 5);
  header[263] = '0';
  header[264] = '0';
  for (size_t i = 148; i < 156; ++i) header[i] = ' ';
  long checksum = 0;
  for (size_t i = 0; i < 512; ++i) checksum += header[i];
  std::snprintf(reinterpret_cast<char*>(header + 148), 8, "%06o", static_cast<unsigned>(checksum));
  header[154] = '\0';
  out.append(reinterpret_cast<char*>(header), 512);
}

void append_tar_data(std::string& out, const std::string& data) {
  out.append(data);
  const size_t remainder = data.size() % 512;
  if (remainder != 0) out.append(512 - remainder, '\0');
}

std::string build_tar(const std::string& name, const std::string& content) {
  std::string tar;
  append_tar_header(tar, name, static_cast<long>(content.size()), '0', "");
  append_tar_data(tar, content);
  tar.append(1024, '\0');
  return tar;
}

void test_roundtrip_tar_gz() {
  const std::string root = temp_path();
  check(!root.empty(), "temp dir created");
  if (root.empty()) return;

  const std::string source = root + "/source";
  mkdir(source.c_str(), 0755);
  mkdir((source + "/sub").c_str(), 0755);
  write_file(source + "/hello.txt", "hello vodka\n");
  write_file(source + "/sub/nested.bin", std::string(4096, 'x'));
  symlink("hello.txt", (source + "/link").c_str());

  const std::string archive = root + "/rootfs.tar.gz";
  const std::string command = "tar -czf '" + archive + "' -C '" + source + "' .";
  const int rc = std::system(command.c_str());
  check(rc == 0, "fixture tar.gz created");

  const std::string dest = root + "/dest";
  vodka::ExtractResult result = vodka::extract_tar_gz(archive, dest);
  check(result.ok, "tar.gz extraction ok");
  if (!result.ok) std::printf("       error: %s\n", result.message.c_str());
  check(is_file(dest + "/hello.txt") && read_file(dest + "/hello.txt") == "hello vodka\n",
        "regular file extracted");
  check(is_dir(dest + "/sub"), "subdirectory extracted");
  check(read_file(dest + "/sub/nested.bin").size() == 4096, "large file size preserved");
  check(is_symlink(dest + "/link"), "symlink extracted");

  const std::string deep_archive = root + "/deep.tar.gz";
  std::system(("tar -czf '" + deep_archive + "' -C '" + source + "' .").c_str());
  result = vodka::extract_tar_gz(deep_archive, root + "/dest2");
  check(result.ok && result.files >= 2, "file count reported");
}

void test_plain_tar_and_traversal() {
  const std::string root = temp_path();
  if (root.empty()) return;

  const std::string dest = root + "/dest";
  const std::string evil = build_tar("../escaped.txt", "nope");
  const std::string evil_path = root + "/evil.tar";
  write_file(evil_path, evil);
  vodka::ExtractResult result = vodka::extract_tar(evil_path, dest);
  check(!result.ok, "path traversal rejected");
  check(access((root + "/escaped.txt").c_str(), F_OK) != 0, "no file written outside dest");

  const std::string absolute = build_tar("/etc/vodka-abs-test", "nope");
  const std::string absolute_path = root + "/absolute.tar";
  write_file(absolute_path, absolute);
  result = vodka::extract_tar(absolute_path, dest);
  check(result.ok, "absolute path sanitized and accepted");
  check(is_file(dest + "/etc/vodka-abs-test"), "absolute path landed inside dest");
  check(access("/etc/vodka-abs-test", F_OK) != 0, "nothing written to real /etc");
}

void test_hardlink() {
  const std::string root = temp_path();
  if (root.empty()) return;
  std::string tar;
  append_tar_header(tar, "a.txt", 7, '0', "");
  append_tar_data(tar, "payload");
  append_tar_header(tar, "b.txt", 0, '1', "a.txt");
  tar.append(1024, '\0');
  const std::string path = root + "/links.tar";
  write_file(path, tar);
  vodka::ExtractResult result = vodka::extract_tar(path, root + "/dest");
  check(result.ok, "hardlink archive extracted");
  check(read_file(root + "/dest/b.txt") == "payload", "hardlink content matches target");
}

void test_sparse_rejected() {
  const std::string root = temp_path();
  if (root.empty()) return;
  std::string tar;
  append_tar_header(tar, "disk.raw", 1024, 'S', "");
  append_tar_data(tar, std::string(1024, '\0'));
  tar.append(1024, '\0');
  const std::string path = root + "/sparse.tar";
  write_file(path, tar);
  vodka::ExtractResult result = vodka::extract_tar(path, root + "/dest");
  check(!result.ok && result.message.find("sparse") != std::string::npos,
        "sparse archive rejected clearly");
}

void test_corrupt_archive() {
  const std::string root = temp_path();
  if (root.empty()) return;
  const std::string path = root + "/broken.tar";
  write_file(path, std::string(512, 'Z'));
  vodka::ExtractResult result = vodka::extract_tar(path, root + "/dest");
  check(!result.ok, "corrupt header rejected");
}

}  // namespace

int main() {
  test_roundtrip_tar_gz();
  test_plain_tar_and_traversal();
  test_hardlink();
  test_sparse_rejected();
  test_corrupt_archive();

  if (g_failures == 0) {
    std::printf("\nall archive tests passed\n");
    return 0;
  }
  std::printf("\n%d archive test(s) failed\n", g_failures);
  return 1;
}
