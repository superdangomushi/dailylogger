// 共通ユーティリティ（時刻・文字列・UUID・環境変数）。
#pragma once

#include <chrono>
#include <ctime>
#include <cctype>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <fstream>
#include <random>
#include <regex>
#include <string>

namespace util {

inline std::string now_iso() {
  using namespace std::chrono;
  const auto now = system_clock::now();
  const auto ms = duration_cast<milliseconds>(now.time_since_epoch()) % 1000;
  const std::time_t t = system_clock::to_time_t(now);
  std::tm tm{};
  gmtime_r(&t, &tm);
  char buf[80];
  std::snprintf(buf, sizeof(buf), "%04d-%02d-%02dT%02d:%02d:%02d.%03dZ",
                tm.tm_year + 1900, tm.tm_mon + 1, tm.tm_mday,
                tm.tm_hour, tm.tm_min, tm.tm_sec, static_cast<int>(ms.count()));
  return buf;
}

inline std::string trim(const std::string& s) {
  size_t b = 0, e = s.size();
  while (b < e && std::isspace(static_cast<unsigned char>(s[b]))) b++;
  while (e > b && std::isspace(static_cast<unsigned char>(s[e - 1]))) e--;
  return s.substr(b, e - b);
}

inline std::string to_lower(std::string s) {
  for (auto& c : s) c = static_cast<char>(std::tolower(static_cast<unsigned char>(c)));
  return s;
}

inline std::string env_or(const char* name, const std::string& fallback) {
  const char* v = std::getenv(name);
  return (v && *v) ? std::string(v) : fallback;
}

// UTF-8を壊さないよう、コードポイント数ではなくバイト数でおおまかに制限する
// （表示名などの上限用。元のJS実装の slice(100) 相当）。
inline std::string clip_utf8(const std::string& s, size_t max_bytes) {
  if (s.size() <= max_bytes) return s;
  size_t n = max_bytes;
  while (n > 0 && (static_cast<unsigned char>(s[n]) & 0xC0) == 0x80) n--;
  return s.substr(0, n);
}

inline const std::regex& uuid_re() {
  static const std::regex re(
      "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$",
      std::regex::icase);
  return re;
}

inline bool is_uuid(const std::string& s) { return std::regex_match(s, uuid_re()); }

// UUID v4 を生成する（/dev/urandom ベース）。
inline std::string uuid4() {
  unsigned char b[16];
  std::ifstream ur("/dev/urandom", std::ios::binary);
  if (!ur.read(reinterpret_cast<char*>(b), sizeof(b))) {
    std::random_device rd;
    for (auto& x : b) x = static_cast<unsigned char>(rd());
  }
  b[6] = static_cast<unsigned char>((b[6] & 0x0F) | 0x40);
  b[8] = static_cast<unsigned char>((b[8] & 0x3F) | 0x80);
  char out[37];
  std::snprintf(out, sizeof(out),
                "%02x%02x%02x%02x-%02x%02x-%02x%02x-%02x%02x-%02x%02x%02x%02x%02x%02x",
                b[0], b[1], b[2], b[3], b[4], b[5], b[6], b[7],
                b[8], b[9], b[10], b[11], b[12], b[13], b[14], b[15]);
  return out;
}

// ファイル名等に使えない文字を _ に置き換える（JS版の safeName と同じ規則）。
inline std::string sanitize_name(const std::string& s, size_t max_len) {
  std::string out;
  out.reserve(s.size());
  for (char c : s) {
    const bool ok = std::isalnum(static_cast<unsigned char>(c)) || c == '.' || c == '_' || c == '-';
    out.push_back(ok ? c : '_');
    if (out.size() >= max_len) break;
  }
  return out;
}

inline std::string basename_of(const std::string& p) {
  const auto pos = p.find_last_of('/');
  return pos == std::string::npos ? p : p.substr(pos + 1);
}

// URLデコード（管理UIの /api/accounts/:email 用）。
inline std::string url_decode(const std::string& s) {
  std::string out;
  out.reserve(s.size());
  for (size_t i = 0; i < s.size(); i++) {
    if (s[i] == '%' && i + 2 < s.size() && std::isxdigit(static_cast<unsigned char>(s[i + 1])) &&
        std::isxdigit(static_cast<unsigned char>(s[i + 2]))) {
      out.push_back(static_cast<char>(std::stoi(s.substr(i + 1, 2), nullptr, 16)));
      i += 2;
    } else if (s[i] == '+') {
      out.push_back(' ');
    } else {
      out.push_back(s[i]);
    }
  }
  return out;
}

}  // namespace util
