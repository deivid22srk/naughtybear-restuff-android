// restuff - Android port (naughtybear-restuff-android)
//
// STUB Android de video_player.h: Media Foundation/XAudio2 são Windows-only
// e o upstream só constrói o jogo cross p/ Windows (o branch não-WIN32 nunca
// era compilado). Este stub preserva a API completa (VideoPlayer, VideoRequest,
// PlayVideo/StopVideo/IsVideoPlaying, PathToUtf8) para hooks.cpp /
// video_overlay.h / restuff_app.h compilarem sem alteração. Open() falha
// sempre → o attract-mode video fica desabilitado (caminho legítimo: o
// overlay já trata Open() com log WARN e segue o jogo).

#pragma once

#include <chrono>
#include <cstdint>
#include <filesystem>
#include <mutex>
#include <string>
#include <vector>

#include <rex/logging.h>

namespace restuff {

inline std::string PathToUtf8(const std::filesystem::path& p) {
    // Android/bionic: native() é bytes UTF-8; path::string() não lança.
    return p.string();
}

// Player no-op: nenhum frame é produzido, IsOpen() sempre falso.
class VideoPlayer {
 public:
    VideoPlayer() = default;
    ~VideoPlayer() = default;

    bool Open(const std::filesystem::path& path) {
        REXLOG_WARN("[video] playback indisponível no Android ('{}') — Media "
                    "Foundation é Windows-only; attract-mode desabilitado.",
                    PathToUtf8(path));
        return false;
    }
    void Close() {}
    bool IsOpen() const { return false; }
    bool has_audio() const { return false; }
    int width() const { return 0; }
    int height() const { return 0; }
    double elapsed() const { return 0.0; }
    bool Update(std::vector<uint8_t>& out_rgba, bool& finished) {
        out_rgba.clear();
        finished = true;
        return false;
    }
};

// --- Cross-thread control (mesma semântica do original) ----------------------

struct VideoRequest {
    std::mutex mtx;
    std::filesystem::path path;  // non-empty = start playing this file
    bool stop = false;
    bool loop = false;
    bool playing = false;        // published by the overlay
};

inline VideoRequest& video_request() {
    static VideoRequest r;
    return r;
}

inline void PlayVideo(const std::filesystem::path& path, bool loop = false) {
    auto& r = video_request();
    std::lock_guard<std::mutex> lk(r.mtx);
    r.path = path;
    r.loop = loop;
    r.stop = false;
}

inline void StopVideo() {
    auto& r = video_request();
    std::lock_guard<std::mutex> lk(r.mtx);
    r.path.clear();
    r.stop = true;
}

inline bool IsVideoPlaying() {
    auto& r = video_request();
    std::lock_guard<std::mutex> lk(r.mtx);
    return r.playing;
}

}  // namespace restuff
