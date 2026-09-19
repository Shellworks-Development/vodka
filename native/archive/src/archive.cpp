#include "vodka/archive.h"

#include <fcntl.h>
#include <sys/stat.h>
#include <unistd.h>
#include <zlib.h>

#include <cerrno>
#include <cstdint>
#include <cstdlib>
#include <cstring>
#include <string>
#include <vector>

namespace vodka {
namespace {

constexpr size_t kBlockSize = 512;
constexpr size_t kCopyBuffer = 64 * 1024;

class Reader {
 public:
  virtual ~Reader() = default;
  virtual ssize_t read(void* buffer, size_t count) = 0;
};

class GzipReader final : public Reader {
 public:
  explicit GzipReader(const std::string& path) : file_(gzopen(path.c_str(), "rb")) {}
  ~GzipReader() override {
    if (file_ != nullptr) gzclose(file_);
  }
  bool ok() const { return file_ != nullptr; }
  ssize_t read(void* buffer, size_t count) override {
    if (file_ == nullptr) return -1;
    return gzread(file_, buffer, static_cast<unsigned>(count));
  }

 private:
  gzFile file_ = nullptr;
};

class FileReader final : public Reader {
 public:
  explicit FileReader(const std::string& path)
      : fd_(open(path.c_str(), O_RDONLY | O_CLOEXEC)) {}
  ~FileReader() override {
    if (fd_ >= 0) close(fd_);
  }
  bool ok() const { return fd_ >= 0; }
  ssize_t read(void* buffer, size_t count) override { return ::read(fd_, buffer, count); }

 private:
  int fd_ = -1;
};

bool read_exact(Reader& reader, void* buffer, size_t count) {
  auto* out = static_cast<unsigned char*>(buffer);
  size_t total = 0;
  while (total < count) {
    const ssize_t got = reader.read(out + total, count - total);
    if (got <= 0) return false;
    total += static_cast<size_t>(got);
  }
  return true;
}

bool skip(Reader& reader, size_t count) {
  std::vector<unsigned char> scratch(16 * 1024);
  while (count > 0) {
    const size_t chunk = count < scratch.size() ? count : scratch.size();
    if (!read_exact(reader, scratch.data(), chunk)) return false;
    count -= chunk;
  }
  return true;
}

long parse_octal(const unsigned char* field, size_t length) {
  long value = 0;
  size_t i = 0;
  while (i < length && (field[i] == ' ' || field[i] == '\0')) ++i;
  for (; i < length; ++i) {
    const unsigned char c = field[i];
    if (c < '0' || c > '7') break;
    value = value * 8 + static_cast<long>(c - '0');
  }
  return value;
}

std::string parse_string(const unsigned char* field, size_t length) {
  size_t n = 0;
  while (n < length && field[n] != '\0') ++n;
  return std::string(reinterpret_cast<const char*>(field), n);
}

std::string trim_trailing_nul(const std::string& value) {
  size_t n = value.size();
  while (n > 0 && value[n - 1] == '\0') --n;
  return value.substr(0, n);
}

bool mkdirs(const std::string& path) {
  if (path.empty()) return true;
  std::string current;
  size_t i = 0;
  if (path[0] == '/') {
    current = "/";
    i = 1;
  }
  while (i <= path.size()) {
    const size_t slash = path.find('/', i);
    const std::string part =
        path.substr(i, slash == std::string::npos ? std::string::npos : slash - i);
    if (!part.empty()) {
      current = current == "/" ? "/" + part : (current.empty() ? part : current + "/" + part);
      if (mkdir(current.c_str(), 0755) != 0 && errno != EEXIST) return false;
    }
    if (slash == std::string::npos) break;
    i = slash + 1;
  }
  return true;
}

bool sanitize_relative(const std::string& raw, std::string& out) {
  out.clear();
  std::string path = raw;
  while (!path.empty() && path.front() == '/') path.erase(path.begin());
  size_t start = 0;
  while (start <= path.size()) {
    const size_t slash = path.find('/', start);
    const std::string part =
        path.substr(start, slash == std::string::npos ? std::string::npos : slash - start);
    if (!part.empty() && part != ".") {
      if (part == "..") return false;
      out += out.empty() ? part : "/" + part;
    }
    if (slash == std::string::npos) break;
    start = slash + 1;
  }
  return true;
}

std::string parent_of(const std::string& full) {
  const size_t slash = full.find_last_of('/');
  if (slash == std::string::npos || slash == 0) return "/";
  return full.substr(0, slash);
}

void parse_pax(const std::string& data, std::string& name, std::string& link, long& size,
               bool& has_size) {
  size_t pos = 0;
  while (pos < data.size()) {
    const size_t space = data.find(' ', pos);
    if (space == std::string::npos) break;
    const long record_length = std::strtol(data.substr(pos, space - pos).c_str(), nullptr, 10);
    if (record_length <= 0 || pos + static_cast<size_t>(record_length) > data.size()) break;
    const std::string record =
        data.substr(space + 1, static_cast<size_t>(record_length) - (space + 1 - pos));
    pos += static_cast<size_t>(record_length);
    const size_t eq = record.find('=');
    if (eq == std::string::npos) continue;
    const std::string key = record.substr(0, eq);
    std::string value = record.substr(eq + 1);
    if (!value.empty() && value.back() == '\n') value.pop_back();
    if (key == "path") {
      name = value;
    } else if (key == "linkpath") {
      link = value;
    } else if (key == "size") {
      size = std::strtol(value.c_str(), nullptr, 10);
      has_size = true;
    }
  }
}

bool write_regular(Reader& reader, const std::string& full, const std::string& relative, long size,
                   long mode, size_t& files, size_t& bytes, std::string& error) {
  if (!mkdirs(parent_of(full))) {
    error = "mkdir " + parent_of(full) + ": " + std::strerror(errno);
    return false;
  }
  const unsigned perms = static_cast<unsigned>(mode) & 0777;
  const int fd = open(full.c_str(), O_CREAT | O_WRONLY | O_TRUNC | O_NOFOLLOW | O_CLOEXEC,
                      perms | 0600);
  if (fd < 0) {
    error = "open " + relative + ": " + std::strerror(errno);
    return false;
  }

  std::vector<unsigned char> buffer(kCopyBuffer);
  long remaining = size;
  while (remaining > 0) {
    const size_t chunk = static_cast<size_t>(remaining) < buffer.size()
                             ? static_cast<size_t>(remaining)
                             : buffer.size();
    if (!read_exact(reader, buffer.data(), chunk)) {
      close(fd);
      error = "truncated archive while reading " + relative;
      return false;
    }
    size_t offset = 0;
    while (offset < chunk) {
      const ssize_t written = ::write(fd, buffer.data() + offset, chunk - offset);
      if (written <= 0) {
        if (errno == EINTR) continue;
        close(fd);
        error = "write " + relative + ": " + std::strerror(errno);
        return false;
      }
      offset += static_cast<size_t>(written);
    }
    remaining -= static_cast<long>(chunk);
  }
  fchmod(fd, perms);
  close(fd);
  ++files;
  bytes += static_cast<size_t>(size);
  return true;
}

bool copy_regular(const std::string& source, const std::string& target, long mode,
                  std::string& error) {
  const int in = open(source.c_str(), O_RDONLY | O_CLOEXEC);
  if (in < 0) {
    error = "open " + source + ": " + std::strerror(errno);
    return false;
  }
  const unsigned perms = static_cast<unsigned>(mode) & 0777;
  const int out =
      open(target.c_str(), O_CREAT | O_WRONLY | O_TRUNC | O_CLOEXEC, perms | 0600);
  if (out < 0) {
    error = "create " + target + ": " + std::strerror(errno);
    close(in);
    return false;
  }
  unsigned char buffer[kCopyBuffer];
  for (;;) {
    const ssize_t got = ::read(in, buffer, sizeof(buffer));
    if (got < 0) {
      if (errno == EINTR) continue;
      error = "read " + source + ": " + std::strerror(errno);
      close(in);
      close(out);
      return false;
    }
    if (got == 0) break;
    size_t offset = 0;
    while (offset < static_cast<size_t>(got)) {
      const ssize_t written = ::write(out, buffer + offset, static_cast<size_t>(got) - offset);
      if (written < 0) {
        if (errno == EINTR) continue;
        error = "write " + target + ": " + std::strerror(errno);
        close(in);
        close(out);
        return false;
      }
      offset += static_cast<size_t>(written);
    }
  }
  fchmod(out, perms);
  close(in);
  close(out);
  return true;
}

bool extract_stream(Reader& reader, const std::string& dest_dir, ExtractResult& result) {
  if (!mkdirs(dest_dir)) {
    result.message = "cannot create destination: " + std::string(std::strerror(errno));
    return false;
  }

  std::string gnu_name;
  std::string gnu_link;
  std::string pax_name;
  std::string pax_link;
  long pax_size = 0;
  bool has_pax_size = false;

  for (;;) {
    unsigned char block[kBlockSize];
    if (!read_exact(reader, block, kBlockSize)) break;

    bool empty = true;
    for (size_t i = 0; i < kBlockSize; ++i) {
      if (block[i] != 0) {
        empty = false;
        break;
      }
    }
    if (empty) break;

    const long stored = parse_octal(block + 148, 8);
    long computed = 0;
    for (size_t i = 0; i < kBlockSize; ++i) {
      const unsigned char value = (i >= 148 && i < 156) ? ' ' : block[i];
      computed += static_cast<long>(value);
    }
    if (stored != computed) {
      result.message = "bad tar header checksum";
      return false;
    }

    const std::string name = parse_string(block, 100);
    const std::string prefix = parse_string(block + 345, 155);
    const char type = static_cast<char>(block[156]);
    const std::string link_field = parse_string(block + 157, 100);
    const long raw_size = parse_octal(block + 124, 12);
    const long mode = parse_octal(block + 100, 8);
    const size_t header_data = static_cast<size_t>(raw_size > 0 ? raw_size : 0);
    const size_t header_padding =
        ((header_data + kBlockSize - 1) / kBlockSize) * kBlockSize - header_data;

    if (type == 'S') {
      result.message = "GNU sparse archives are not supported: " + name;
      return false;
    }

    if (type == 'L' || type == 'K') {
      std::string data(header_data, '\0');
      if (header_data > 0 && !read_exact(reader, &data[0], header_data)) {
        result.message = "truncated long-name entry";
        return false;
      }
      if (header_padding > 0 && !skip(reader, header_padding)) {
        result.message = "truncated long-name padding";
        return false;
      }
      if (type == 'L') {
        gnu_name = trim_trailing_nul(data);
      } else {
        gnu_link = trim_trailing_nul(data);
      }
      continue;
    }

    if (type == 'x' || type == 'g') {
      std::string data(header_data, '\0');
      if (header_data > 0 && !read_exact(reader, &data[0], header_data)) {
        result.message = "truncated pax entry";
        return false;
      }
      if (header_padding > 0 && !skip(reader, header_padding)) {
        result.message = "truncated pax padding";
        return false;
      }
      if (data.find("GNU.sparse") != std::string::npos) {
        result.message = "GNU sparse archives are not supported";
        return false;
      }
      if (type == 'x') parse_pax(data, pax_name, pax_link, pax_size, has_pax_size);
      continue;
    }

    const std::string raw_name = !gnu_name.empty()
                                     ? gnu_name
                                     : (!pax_name.empty() ? pax_name
                                                          : (prefix.empty() ? name : prefix + "/" + name));
    const std::string raw_link = !gnu_link.empty() ? gnu_link : (!pax_link.empty() ? pax_link : link_field);
    const long entry_size = has_pax_size ? pax_size : raw_size;
    gnu_name.clear();
    gnu_link.clear();
    pax_name.clear();
    pax_link.clear();
    has_pax_size = false;

    const size_t entry_data = static_cast<size_t>(entry_size > 0 ? entry_size : 0);
    const size_t entry_padding =
        ((entry_data + kBlockSize - 1) / kBlockSize) * kBlockSize - entry_data;

    const bool is_dir = type == '5' || (!raw_name.empty() && raw_name.back() == '/');
    std::string relative;
    if (!sanitize_relative(raw_name, relative)) {
      result.message = "unsafe path in archive: " + raw_name;
      return false;
    }
    if (relative.empty()) {
      if (entry_data + entry_padding > 0 && !skip(reader, entry_data + entry_padding)) {
        result.message = "truncated archive while skipping root entry";
        return false;
      }
      continue;
    }
    const std::string full = dest_dir + "/" + relative;

    if (is_dir) {
      if (!mkdirs(full)) {
        result.message = "mkdir " + relative + ": " + std::strerror(errno);
        return false;
      }
      chmod(full.c_str(), (static_cast<unsigned>(mode) & 0777) | 0700);
      if (entry_data + entry_padding > 0 &&
          !skip(reader, entry_data + entry_padding)) {
        result.message = "truncated archive while skipping directory data";
        return false;
      }
      continue;
    }

    if (type == '2') {
      if (!mkdirs(parent_of(full))) {
        result.message = "mkdir for symlink " + relative;
        return false;
      }
      unlink(full.c_str());
      if (symlink(raw_link.c_str(), full.c_str()) != 0) {
        result.message = "symlink " + relative + ": " + std::strerror(errno);
        return false;
      }
      continue;
    }

    if (type == '1') {
      std::string link_rel;
      if (!sanitize_relative(raw_link, link_rel) || link_rel.empty()) {
        result.message = "unsafe hardlink in archive: " + raw_link;
        return false;
      }
      if (!mkdirs(parent_of(full))) {
        result.message = "mkdir for hardlink " + relative;
        return false;
      }
      unlink(full.c_str());
      const std::string link_target = dest_dir + "/" + link_rel;
      if (link(link_target.c_str(), full.c_str()) != 0) {
        if (!copy_regular(link_target, full, mode, result.message)) {
          result.message = "hardlink " + relative + ": " + result.message;
          return false;
        }
      }
      continue;
    }

    if (type == '0' || type == '\0') {
      if (!write_regular(reader, full, relative, entry_size, mode, result.files, result.bytes,
                         result.message)) {
        return false;
      }
      if (entry_padding > 0 && !skip(reader, entry_padding)) {
        result.message = "truncated archive padding after " + relative;
        return false;
      }
      continue;
    }

    if (entry_data + entry_padding > 0 && !skip(reader, entry_data + entry_padding)) {
      result.message = "truncated archive while skipping " + relative;
      return false;
    }
  }

  result.ok = true;
  return true;
}

ExtractResult run(Reader& reader, bool readable, const std::string& dest_dir) {
  ExtractResult result;
  if (!readable) {
    result.message = "cannot open archive";
    return result;
  }
  extract_stream(reader, dest_dir, result);
  return result;
}

}  // namespace

ExtractResult extract_tar_gz(const std::string& archive_path, const std::string& dest_dir) {
  GzipReader reader(archive_path);
  return run(reader, reader.ok(), dest_dir);
}

ExtractResult extract_tar(const std::string& archive_path, const std::string& dest_dir) {
  FileReader reader(archive_path);
  return run(reader, reader.ok(), dest_dir);
}

}  // namespace vodka
