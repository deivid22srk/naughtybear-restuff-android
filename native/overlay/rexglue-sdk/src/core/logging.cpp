/**
 * @file        core/logging.cpp
 * @brief       Logging infrastructure implementation
 *
 * @copyright   Copyright (c) 2026 Tom Clay <tomc@tctechstuff.com>
 *              All rights reserved.
 *
 * @license     BSD 3-Clause License
 *              See LICENSE file in the project root for full license text.
 */

#include <algorithm>
#include <cctype>
#include <charconv>
#include <filesystem>
#include <mutex>
#include <unordered_map>
#include <vector>

#include <spdlog/sinks/rotating_file_sink.h>
#include <spdlog/sinks/stdout_color_sinks.h>

#include <toml++/toml.hpp>

#include <rex/cvar.h>
#include <rex/logging.h>
#include <rex/platform.h>
#include <rex/platform/env.h>

#if REX_PLATFORM_WIN32
#include <spdlog/sinks/msvc_sink.h>
#else
#include <spdlog/sinks/stdout_sinks.h>
#endif
#if REX_PLATFORM_ANDROID
#include <spdlog/sinks/android_sink.h>
#include <android/log.h>
#endif

REXCVAR_DEFINE_STRING(log_level, "info", "Log",
                      "Global log level: trace, debug, info, warn, error, critical, off")
    .allowed({"trace", "debug", "info", "warn", "error", "critical", "off"});

REXCVAR_DEFINE_STRING(log_file, "", "Log", "Log file path (empty = auto sequential naming)");

REXCVAR_DEFINE_BOOL(log_verbose, false, "Log", "Enable verbose logging (sets level to trace)")
    .debug_only();

REXCVAR_DEFINE_BOOL(log_noisy, false, "Log", "Enable noisy/high-frequency log macros");

REXCVAR_DEFINE_INT32(log_flush_interval, 0, "Log", "Periodic flush interval in seconds (0 = off)")
    .range(0, 60)
    .lifecycle(rex::cvar::Lifecycle::kInitOnly);

REXCVAR_DEFINE_INT32(log_max_file_size_mb, 5, "Log", "Max log file size in MB before rotation")
    .range(1, 100)
    .lifecycle(rex::cvar::Lifecycle::kInitOnly);

REXCVAR_DEFINE_INT32(log_max_files, 20, "Log", "Max number of rotated log files to keep")
    .range(1, 100)
    .lifecycle(rex::cvar::Lifecycle::kInitOnly);

namespace rex {

namespace {

std::vector<LogCategoryEntry> g_registry;
spdlog::sink_ptr g_console_sink;
spdlog::sink_ptr g_file_sink;
spdlog::sink_ptr g_early_sink;
std::vector<spdlog::sink_ptr> g_extra_sinks;
bool g_early_initialized = false;
bool g_initialized = false;
std::mutex g_mutex;
LogConfig g_config;

#if REX_PLATFORM_ANDROID
// Port Android (naughtybear-restuff-android): sink logcat que FATIA mensagens
// longas. O logd trunca cada entrada em ~4068 bytes (LOGGER_ENTRY_MAX_PAYLOAD)
// e o android_sink do spdlog entrega a string inteira numa única chamada —
// stack traces e dumps de shaders saíam MUTILADOS no logcat (o arquivo em
// disco continuava íntegro, mas o canal imediato de diagnóstico não).
// Este sink divide a mensagem formatada em blocos de ~3500 bytes com sufixo
// (i/N) — nenhum byte perdido no logcat.
class ChunkedAndroidSink final : public spdlog::sinks::base_sink<std::mutex> {
 public:
  explicit ChunkedAndroidSink(std::string tag) : tag_(std::move(tag)) {}

 protected:
  void sink_it_(const spdlog::details::log_msg& msg) override {
    const android_LogPriority priority = ConvertToAndroid(msg.level);
    spdlog::memory_buf_t formatted;
    base_sink<std::mutex>::formatter_->format(msg, formatted);
    // Copia para garantir terminador NUL em cada bloco.
    std::string text(formatted.data(), formatted.size());
    while (!text.empty() && (text.back() == '\n' || text.back() == '\r')) {
      text.pop_back();
    }
    constexpr std::size_t kMaxChunk = 3500;
    if (text.size() <= kMaxChunk) {
      __android_log_write(priority, tag_.c_str(), text.c_str());
      return;
    }
    const std::size_t total = (text.size() + kMaxChunk - 1) / kMaxChunk;
    for (std::size_t i = 0; i < total; ++i) {
      const std::size_t begin = i * kMaxChunk;
      const std::size_t len = std::min(kMaxChunk, text.size() - begin);
      std::string chunk = text.substr(begin, len);
      chunk += " (";
      chunk += std::to_string(i + 1);
      chunk += "/";
      chunk += std::to_string(total);
      chunk += ")";
      __android_log_write(priority, tag_.c_str(), chunk.c_str());
    }
  }

  void flush_() override {}

 private:
  static android_LogPriority ConvertToAndroid(spdlog::level::level_enum level) {
    switch (level) {
      case spdlog::level::trace:
        return ANDROID_LOG_VERBOSE;
      case spdlog::level::debug:
        return ANDROID_LOG_DEBUG;
      case spdlog::level::info:
        return ANDROID_LOG_INFO;
      case spdlog::level::warn:
        return ANDROID_LOG_WARN;
      case spdlog::level::err:
        return ANDROID_LOG_ERROR;
      case spdlog::level::critical:
        return ANDROID_LOG_FATAL;
      default:
        return ANDROID_LOG_DEFAULT;
    }
  }

  std::string tag_;
};
#endif  // REX_PLATFORM_ANDROID

std::filesystem::path NextSequentialLogPath(const std::filesystem::path& logs_dir,
                                            std::string_view app_name) {
  std::filesystem::create_directories(logs_dir);

  int max_seq = 0;
  std::string prefix = std::string(app_name) + "_";
  std::error_code ec;
  for (const auto& entry : std::filesystem::directory_iterator(logs_dir, ec)) {
    if (!entry.is_regular_file())
      continue;
    auto stem = entry.path().stem().string();
    if (stem.starts_with(prefix)) {
      auto num_str = stem.substr(prefix.size());
      int num = 0;
      auto [ptr, parse_ec] = std::from_chars(num_str.data(), num_str.data() + num_str.size(), num);
      if (parse_ec == std::errc() && ptr == num_str.data() + num_str.size())
        max_seq = std::max(max_seq, num);
    }
  }

  return logs_dir / fmt::format("{}_{:03d}.log", app_name, max_seq + 1);
}

std::vector<spdlog::sink_ptr> BuildDefaultSinks() {
  std::vector<spdlog::sink_ptr> sinks;
  if (g_early_sink)
    sinks.push_back(g_early_sink);
  if (g_console_sink)
    sinks.push_back(g_console_sink);
  if (g_file_sink)
    sinks.push_back(g_file_sink);
  for (auto& s : g_extra_sinks)
    sinks.push_back(s);
  return sinks;
}

// Build sinks for a specific category (handles per-category sinks from config)
std::vector<spdlog::sink_ptr> BuildCategorySinks(const std::string& name) {
  auto it = g_config.category_sinks.find(name);
  if (it != g_config.category_sinks.end()) {
    if (g_config.category_sinks_exclusive) {
      // Replace default sinks entirely
      return it->second;
    }
    // Additive: default sinks + category-specific sinks
    auto sinks = BuildDefaultSinks();
    for (auto& s : it->second)
      sinks.push_back(s);
    return sinks;
  }
  return BuildDefaultSinks();
}

// Resolve per-category level from config, or return default
spdlog::level::level_enum ResolveCategoryLevel(const std::string& name) {
  auto it = g_config.category_levels.find(name);
  if (it != g_config.category_levels.end())
    return it->second;
  return g_config.default_level;
}

// Create a logger and register it
std::shared_ptr<spdlog::logger> CreateCategoryLogger(const std::string& name) {
  auto sinks = BuildCategorySinks(name);
  auto logger = std::make_shared<spdlog::logger>(name, sinks.begin(), sinks.end());
  logger->set_level(ResolveCategoryLevel(name));
  logger->flush_on(g_config.flush_level);
  spdlog::register_logger(logger);
  return logger;
}

}  // namespace

void InitLoggingEarly() {
  std::lock_guard lock(g_mutex);
  if (g_early_initialized || g_initialized)
    return;

#if REX_PLATFORM_WIN32
  auto sink = std::make_shared<spdlog::sinks::msvc_sink_mt>();
#elif REX_PLATFORM_ANDROID
  // Port Android (naughtybear-restuff-android): stdout vai para /dev/null
  // em processos app_process — o canal visível é o LOGCAT. Sem este sink,
  // o boot inteiro do motor é invisível (ex.: tela preta sem diagnóstico).
  // ChunkedAndroidSink: mensagens longas são fatiadas (~3500 B) porque o
  // logd trunca em ~4068 B — sem isso, stack traces/dumps saem mutilados.
  auto sink = std::make_shared<ChunkedAndroidSink>("restuff-rex");
#else
  auto sink = std::make_shared<spdlog::sinks::stdout_sink_mt>();
#endif
  sink->set_level(spdlog::level::trace);
  sink->set_pattern("[%l] [%n] %v");
  g_early_sink = sink;

  g_config.default_level = kDefaultLogLevel;
#if REX_PLATFORM_ANDROID
  // Port Android: a fase EARLY também respeita o nível pedido pelo launcher
  // (REX_LOG_LEVEL, exportado pelo android_main a partir de --log-level=).
  // Antes: hardcoded info em Release → as primeiras dezenas de mensagens do
  // boot (cvar init, paths) ficavam invisíveis mesmo com log_level=debug no
  // restuff.toml (que só valia para a fase tardia).
  if (auto early_level = rex::platform::env::get("REX_LOG_LEVEL")) {
    if (auto level = ParseLogLevel(*early_level)) {
      g_config.default_level = *level;
    }
  }
#endif

  // Create loggers for any categories already registered during static init
  for (auto& entry : g_registry) {
    if (!entry.name.empty() && !entry.logger)
      entry.logger = CreateCategoryLogger(entry.name);
  }

  // Set default logger to "core" if registered
  for (auto& entry : g_registry) {
    if (entry.name == "core") {
      spdlog::set_default_logger(entry.logger);
      break;
    }
  }
  g_early_initialized = true;
}

void InitLogging(const LogConfig& config) {
  std::lock_guard lock(g_mutex);

  if (g_initialized) {
    g_config = config;
    for (auto& entry : g_registry) {
      if (!entry.logger)
        continue;
      entry.logger->set_level(ResolveCategoryLevel(entry.name));
      entry.logger->flush_on(config.flush_level);
      if (g_config.category_levels.count(entry.name))
        entry.has_explicit_level = true;
    }
    return;
  }

  g_config = config;

  // Early sink handling:
  //   Windows: the early msvc_sink is the persistent debug channel for GUI
  //     apps and does not conflict with the stdout console sink, so keep it.
  //   Non-Windows: drop the early stdout sink unconditionally so file-only
  //     configs don't leak to stdout and console configs don't duplicate.
#if !REX_PLATFORM_WIN32
  if (g_early_sink) {
    for (auto& entry : g_registry) {
      if (entry.logger)
        std::erase(entry.logger->sinks(), g_early_sink);
    }
    g_early_sink.reset();
  }
#endif

  // Console sink (stdout, colored). Intended for console-subsystem processes.
  if (config.log_to_console) {
    auto sink = std::make_shared<spdlog::sinks::stdout_color_sink_mt>();
    sink->set_level(spdlog::level::trace);
    sink->set_pattern(config.console_pattern);
    g_console_sink = sink;
  }

  // File sink (rotating) with sequential naming fallback
  std::string resolved_path;
  if (config.log_file) {
    resolved_path = config.log_file;
  } else if (!config.app_name.empty()) {
    auto log_dir = config.log_dir.empty() ? std::filesystem::current_path() / "logs"
                                          : std::filesystem::path(config.log_dir);
    resolved_path = NextSequentialLogPath(log_dir, config.app_name).string();
  }
  if (!resolved_path.empty()) {
    // Port Android (naughtybear-restuff-android): o caminho do log agora pode
    // apontar para o STORAGE PÚBLICO (/storage/emulated/<user>/Naughty Bear
    // ReStuff/logs) — gravável apenas com MANAGE_EXTERNAL_STORAGE concedido.
    // O spdlog lança spdlog_ex em falha (dir inexistente/EACCES) e NADA acima
    // capturava → std::terminate → boot morto. Estratégia: tentar o caminho
    // pedido; em falha, cair para o storage PRIVADO do app; em falha total,
    // seguir com logcat apenas. O log NUNCA derruba o boot.
#if REX_PLATFORM_ANDROID
    bool sink_ok = false;
    try {
      auto sink = std::make_shared<spdlog::sinks::rotating_file_sink_mt>(
          resolved_path, static_cast<size_t>(REXCVAR_GET(log_max_file_size_mb)) * 1024 * 1024,
          static_cast<size_t>(REXCVAR_GET(log_max_files)), false);
      sink->set_level(spdlog::level::trace);
      sink->set_pattern(config.file_pattern);
      g_file_sink = sink;
      sink_ok = true;
    } catch (const std::exception& e) {
      __android_log_print(ANDROID_LOG_ERROR, "restuff-rex",
                          "log file '%s' inacessível: %s — caindo para o storage "
                          "privado",
                          resolved_path.c_str(), e.what());
      // Fallback: <files>/logs com numeração sequencial (sempre gravável).
      std::string fallback_dir;
      if (auto files_dir = rex::platform::env::get("REX_ANDROID_FILES_DIR");
          files_dir && !files_dir->empty()) {
        fallback_dir = *files_dir + "/logs";
      } else {
        fallback_dir = "/data/local/tmp/restuff/logs";
      }
      try {
        auto fallback_path =
            NextSequentialLogPath(std::filesystem::path(fallback_dir),
                                  config.app_name.empty() ? "restuff" : config.app_name);
        auto sink = std::make_shared<spdlog::sinks::rotating_file_sink_mt>(
            fallback_path, static_cast<size_t>(REXCVAR_GET(log_max_file_size_mb)) * 1024 * 1024,
            static_cast<size_t>(REXCVAR_GET(log_max_files)), false);
        sink->set_level(spdlog::level::trace);
        sink->set_pattern(config.file_pattern);
        g_file_sink = sink;
        sink_ok = true;
        __android_log_print(ANDROID_LOG_WARN, "restuff-rex",
                            "log ativo (fallback): %s", fallback_path.string().c_str());
      } catch (const std::exception& e2) {
        __android_log_print(
            ANDROID_LOG_ERROR, "restuff-rex",
            "log em arquivo indisponível (%s) — seguindo apenas com logcat",
            e2.what());
      }
    }
    (void)sink_ok;
#else
    auto sink = std::make_shared<spdlog::sinks::rotating_file_sink_mt>(
        resolved_path, static_cast<size_t>(REXCVAR_GET(log_max_file_size_mb)) * 1024 * 1024,
        static_cast<size_t>(REXCVAR_GET(log_max_files)), false);
    sink->set_level(spdlog::level::trace);
    sink->set_pattern(config.file_pattern);
    g_file_sink = sink;
#endif
  }

  // Port Android: com nível verbose (debug/trace) o flush passa a acontecer
  // em debug+ (não só info+) — crash não perde o rabo do buffer do arquivo.
  if (g_config.default_level <= spdlog::level::debug &&
      g_config.flush_level > spdlog::level::debug) {
    g_config.flush_level = spdlog::level::debug;
  }

  g_extra_sinks = config.extra_sinks;
#if REX_PLATFORM_ANDROID
  // Port Android: espelha TODOS os logs do motor (fase tardia) no logcat,
  // além do arquivo rotativo — diagnóstico no device sem adb pull. O sink do
  // stdout é inútil aqui (app_process → /dev/null). ChunkedAndroidSink: fatia
  // mensagens > ~3500 B (logd trunca em ~4068 B) — stack traces inteiros.
  g_extra_sinks.push_back(std::make_shared<ChunkedAndroidSink>("restuff-rex"));
#endif

  // Rebuild all loggers with new sinks
  for (auto& entry : g_registry) {
    if (entry.name.empty())
      continue;
    if (entry.logger)
      spdlog::drop(entry.name);
    entry.logger = CreateCategoryLogger(entry.name);
    if (g_config.category_levels.count(entry.name))
      entry.has_explicit_level = true;
  }

  // Set default logger to "core"
  for (auto& entry : g_registry) {
    if (entry.name == "core") {
      spdlog::set_default_logger(entry.logger);
      break;
    }
  }
  g_initialized = true;

  // Periodic flush
  int flush_interval = REXCVAR_GET(log_flush_interval);
  if (flush_interval > 0)
    spdlog::flush_every(std::chrono::seconds(flush_interval));
}

void InitLogging(const char* log_file, spdlog::level::level_enum level) {
  LogConfig config;
  config.log_file = log_file;
  config.default_level = level;
  InitLogging(config);
}

void ShutdownLogging() {
  std::lock_guard lock(g_mutex);
  if (!g_initialized && !g_early_initialized)
    return;

  for (auto& entry : g_registry)
    if (entry.logger)
      entry.logger->flush();

  spdlog::shutdown();
  g_registry.clear();
  g_console_sink.reset();
  g_file_sink.reset();
  g_early_sink.reset();
  g_extra_sinks.clear();
  g_initialized = false;
  g_early_initialized = false;
}

void FlushLogging() {
  std::lock_guard lock(g_mutex);
  for (auto& entry : g_registry)
    if (entry.logger)
      entry.logger->flush();
}

LogCategoryId RegisterLogCategory(const char* name) {
  std::lock_guard lock(g_mutex);

  // Check for duplicates
  for (size_t i = 0; i < g_registry.size(); ++i) {
    if (g_registry[i].name == name) {
      return LogCategoryId{static_cast<uint16_t>(i)};
    }
  }

  uint16_t id = static_cast<uint16_t>(g_registry.size());
  LogCategoryEntry entry;
  entry.name = name;
  if (g_initialized || g_early_initialized) {
    entry.logger = CreateCategoryLogger(name);
  }
  g_registry.push_back(std::move(entry));
  return LogCategoryId{id};
}

LogCategoryId RegisterLogSubcategory(const char* name, LogCategoryId parent) {
  std::lock_guard lock(g_mutex);

  std::string parent_name;
  if (parent.id < g_registry.size())
    parent_name = g_registry[parent.id].name;
  std::string full_name = parent_name + "." + name;

  for (size_t i = 0; i < g_registry.size(); ++i) {
    if (g_registry[i].name == full_name)
      return LogCategoryId{static_cast<uint16_t>(i)};
  }

  uint16_t id = static_cast<uint16_t>(g_registry.size());
  LogCategoryEntry entry;
  entry.name = full_name;
  entry.parent = parent;
  if (g_initialized || g_early_initialized) {
    entry.logger = CreateCategoryLogger(full_name);
    if (g_config.category_levels.count(full_name))
      entry.has_explicit_level = true;
  }
  g_registry.push_back(std::move(entry));
  return LogCategoryId{id};
}

std::optional<LogCategoryId> FindCategory(const std::string& name) {
  std::lock_guard lock(g_mutex);
  for (size_t i = 0; i < g_registry.size(); ++i) {
    if (g_registry[i].name == name) {
      return LogCategoryId{static_cast<uint16_t>(i)};
    }
  }
  return std::nullopt;
}

std::span<const LogCategoryEntry> GetAllCategories() {
  // No lock: only safe to call from main thread or after init.
  return {g_registry.data(), g_registry.size()};
}

spdlog::logger* GetLoggerRaw(LogCategoryId category) {
  if (!g_initialized && !g_early_initialized)
    InitLoggingEarly();
  if (category.id < g_registry.size()) {
    return g_registry[category.id].logger.get();
  }
  return nullptr;
}

std::shared_ptr<spdlog::logger> GetLogger(LogCategoryId category) {
  if (!g_initialized && !g_early_initialized)
    InitLoggingEarly();
  if (category.id < g_registry.size()) {
    return g_registry[category.id].logger;
  }
  return nullptr;
}

std::shared_ptr<spdlog::logger> GetLogger() {
  return GetLogger(rex::log::core());
}

void SetCategoryLevel(LogCategoryId category, spdlog::level::level_enum level) {
  if (category.id < g_registry.size()) {
    if (auto& logger = g_registry[category.id].logger) {
      logger->set_level(level);
    }
  }
}

void SetAllLevels(spdlog::level::level_enum level) {
  for (auto& entry : g_registry) {
    if (entry.logger)
      entry.logger->set_level(level);
  }
}

void SetRootLevel(LogCategoryId root, spdlog::level::level_enum level) {
  SetCategoryLevel(root, level);
  for (auto& entry : g_registry) {
    if (entry.parent.has_value() && entry.parent->id == root.id && !entry.has_explicit_level &&
        entry.logger)
      entry.logger->set_level(level);
  }
}

void RegisterLogLevelCallback() {
  rex::cvar::RegisterChangeCallback("log_level", [](std::string_view, std::string_view value) {
    if (auto level = ParseLogLevel(std::string(value))) {
      for (size_t i = 0; i < g_registry.size(); ++i) {
        auto& entry = g_registry[i];
        if (!entry.parent.has_value() && entry.logger && !entry.has_explicit_level)
          SetRootLevel(LogCategoryId{static_cast<uint16_t>(i)}, *level);
      }
    }
  });
}

void AddSink(spdlog::sink_ptr sink) {
  std::lock_guard lock(g_mutex);
  g_extra_sinks.push_back(sink);
  for (auto& entry : g_registry) {
    if (entry.logger)
      entry.logger->sinks().push_back(sink);
  }
}

void AddSink(LogCategoryId category, spdlog::sink_ptr sink) {
  std::lock_guard lock(g_mutex);
  if (category.id < g_registry.size()) {
    if (auto& logger = g_registry[category.id].logger) {
      logger->sinks().push_back(sink);
    }
  }
}

void RemoveSink(spdlog::sink_ptr sink) {
  std::lock_guard lock(g_mutex);
  std::erase(g_extra_sinks, sink);
  for (auto& entry : g_registry) {
    if (entry.logger)
      std::erase(entry.logger->sinks(), sink);
  }
}

void RemoveSink(LogCategoryId category, spdlog::sink_ptr sink) {
  std::lock_guard lock(g_mutex);
  if (category.id < g_registry.size()) {
    if (auto& logger = g_registry[category.id].logger) {
      std::erase(logger->sinks(), sink);
    }
  }
}

void ReplaceConsoleSink(spdlog::sink_ptr sink) {
  std::lock_guard lock(g_mutex);
  for (auto& entry : g_registry) {
    if (!entry.logger)
      continue;
    auto& sinks = entry.logger->sinks();
    if (g_console_sink)
      std::erase(sinks, g_console_sink);
    if (sink)
      sinks.push_back(sink);
  }
  g_console_sink = sink;
}

void SetConsolePattern(const std::string& pattern) {
  if (g_console_sink)
    g_console_sink->set_pattern(pattern);
}

void SetFilePattern(const std::string& pattern) {
  if (g_file_sink)
    g_file_sink->set_pattern(pattern);
}

// ==========================================================================
// CLI Helpers
// ==========================================================================

std::optional<spdlog::level::level_enum> ParseLogLevel(const std::string& level_str) {
  static const std::unordered_map<std::string, spdlog::level::level_enum> level_map = {
      {"trace", spdlog::level::trace},  {"debug", spdlog::level::debug},
      {"info", spdlog::level::info},    {"warn", spdlog::level::warn},
      {"warning", spdlog::level::warn}, {"error", spdlog::level::err},
      {"err", spdlog::level::err},      {"critical", spdlog::level::critical},
      {"off", spdlog::level::off},
  };

  std::string lower = level_str;
  std::transform(lower.begin(), lower.end(), lower.begin(),
                 [](unsigned char c) { return std::tolower(c); });

  auto it = level_map.find(lower);
  if (it != level_map.end())
    return it->second;
  return std::nullopt;
}

spdlog::level::level_enum ParseLogLevelOr(const std::string& level_str,
                                          spdlog::level::level_enum default_level) {
  return ParseLogLevel(level_str).value_or(default_level);
}

LogConfig BuildLogConfig(const char* log_file, const std::string& cli_level,
                         const std::map<std::string, std::string>& category_levels) {
  LogConfig config;
  config.log_file = log_file;

  // Build-type default
  config.default_level = kDefaultLogLevel;

  // Environment variable
  if (auto env_level = rex::platform::env::get("REX_LOG_LEVEL")) {
    if (auto level = ParseLogLevel(*env_level))
      config.default_level = *level;
  } else if (auto env_level2 = rex::platform::env::get("SPDLOG_LEVEL")) {
    if (auto level = ParseLogLevel(*env_level2))
      config.default_level = *level;
  }

  // CLI global level overrides environment
  if (!cli_level.empty()) {
    if (auto level = ParseLogLevel(cli_level))
      config.default_level = *level;
  }

  // Per-category CLI levels (string-keyed)
  for (const auto& [cat_name, level_str] : category_levels) {
    if (level_str.empty())
      continue;
    if (auto level = ParseLogLevel(level_str)) {
      config.category_levels[cat_name] = *level;
    }
  }

  return config;
}

std::map<std::string, std::string> ParseCategoryLevelsFromConfig(
    const std::filesystem::path& config_path) {
  std::map<std::string, std::string> result;
  if (!std::filesystem::exists(config_path))
    return result;

  try {
    auto config = toml::parse_file(config_path.string());
    auto* log_table = config["log"].as_table();
    if (!log_table)
      return result;
    auto* levels_table = (*log_table)["levels"].as_table();
    if (!levels_table)
      return result;

    for (const auto& [key, value] : *levels_table) {
      if (value.is_string())
        result[std::string(key)] = value.as_string()->get();
    }
  } catch (const toml::parse_error&) {}

  return result;
}

}  // namespace rex
