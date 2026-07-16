// リソース使用率（CPU/メモリ/GPU）の計測。
// ダッシュボードの「処理に使うPC」選択画面に表示される。
#pragma once

#include <sys/wait.h>

#include <algorithm>
#include <cstdio>
#include <optional>
#include <sstream>
#include <string>
#include <vector>

namespace metrics {

struct CpuTimes {
  unsigned long long idle = 0;
  unsigned long long total = 0;
  bool valid = false;
};

inline CpuTimes read_cpu_times() {
  CpuTimes t;
  std::FILE* f = std::fopen("/proc/stat", "r");
  if (!f) return t;
  char line[512];
  // 先頭行 "cpu  user nice system idle iowait irq softirq steal ..."
  if (std::fgets(line, sizeof(line), f)) {
    unsigned long long v[10] = {0};
    const int n = std::sscanf(line, "cpu %llu %llu %llu %llu %llu %llu %llu %llu %llu %llu",
                              &v[0], &v[1], &v[2], &v[3], &v[4], &v[5], &v[6], &v[7], &v[8], &v[9]);
    if (n >= 4) {
      for (int i = 0; i < n; i++) t.total += v[i];
      t.idle = v[3] + (n >= 5 ? v[4] : 0);  // idle + iowait
      t.valid = true;
    }
  }
  std::fclose(f);
  return t;
}

inline CpuTimes g_last_cpu_times;

// 前回サンプルとの差分からCPU使用率(%)を出す。初回は基準がないので nullopt。
inline std::optional<double> sample_cpu_pct() {
  const CpuTimes now = read_cpu_times();
  std::optional<double> pct;
  if (g_last_cpu_times.valid && now.valid && now.total > g_last_cpu_times.total) {
    const double d_total = static_cast<double>(now.total - g_last_cpu_times.total);
    const double d_idle = static_cast<double>(now.idle - g_last_cpu_times.idle);
    double p = (1.0 - d_idle / d_total) * 100.0;
    pct = std::min(std::max(p, 0.0), 100.0);
  }
  g_last_cpu_times = now;
  return pct;
}

inline std::optional<double> sample_mem_pct() {
  std::FILE* f = std::fopen("/proc/meminfo", "r");
  if (!f) return std::nullopt;
  unsigned long long total = 0, avail = 0, free_kb = 0;
  char key[64];
  unsigned long long val;
  while (std::fscanf(f, "%63s %llu kB\n", key, &val) == 2) {
    const std::string k = key;
    if (k == "MemTotal:") total = val;
    else if (k == "MemAvailable:") avail = val;
    else if (k == "MemFree:") free_kb = val;
    if (total && avail) break;
  }
  std::fclose(f);
  if (!total) return std::nullopt;
  const unsigned long long unused = avail ? avail : free_kb;
  double p = (static_cast<double>(total - unused) / static_cast<double>(total)) * 100.0;
  return std::min(std::max(p, 0.0), 100.0);
}

inline bool g_gpu_unavailable = false;

// GPU使用率は nvidia-smi があれば取得（複数GPUは最大値）。無い環境では nullopt。
inline std::optional<double> sample_gpu_pct() {
  if (g_gpu_unavailable) return std::nullopt;
  std::FILE* p = ::popen(
      "nvidia-smi --query-gpu=utilization.gpu --format=csv,noheader,nounits 2>/dev/null", "r");
  if (!p) return std::nullopt;
  std::string out;
  char buf[256];
  size_t n;
  while ((n = std::fread(buf, 1, sizeof(buf), p)) > 0) out.append(buf, n);
  const int rc = ::pclose(p);
  if (rc != 0) {
    // コマンドが無い（シェルの exit 127）ときは以後試さない。
    if (WIFEXITED(rc) && WEXITSTATUS(rc) == 127) g_gpu_unavailable = true;
    return std::nullopt;
  }
  std::optional<double> best;
  std::istringstream iss(out);
  std::string line;
  while (std::getline(iss, line)) {
    try {
      const double v = std::stod(line);
      if (!best || v > *best) best = v;
    } catch (...) {
    }
  }
  return best;
}

}  // namespace metrics
