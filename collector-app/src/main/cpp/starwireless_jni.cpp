#include <jni.h>
#include <android/log.h>
#include <cmath>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <string>
#include <vector>

#include "whisper.h"

namespace {

constexpr const char* kTag = "starwireless-jni";
constexpr int kSampleRate = 16000;
constexpr int kMaxWavBytes = 96 * 1024 * 1024;

std::string jniToStd(JNIEnv* env, jstring value) {
    if (value == nullptr) return {};
    const char* chars = env->GetStringUTFChars(value, nullptr);
    if (chars == nullptr) return {};
    std::string result(chars);
    env->ReleaseStringUTFChars(value, chars);
    return result;
}

void appendEscaped(std::string& out, const std::string& text) {
    static const char* kHex = "0123456789abcdef";
    for (unsigned char c : text) {
        switch (c) {
            case '"': out += "\\\""; break;
            case '\\': out += "\\\\"; break;
            case '\b': out += "\\b"; break;
            case '\f': out += "\\f"; break;
            case '\n': out += "\\n"; break;
            case '\r': out += "\\r"; break;
            case '\t': out += "\\t"; break;
            default:
                if (c < 0x20) {
                    out += "\\u00";
                    out += kHex[(c >> 4) & 0xF];
                    out += kHex[c & 0xF];
                } else {
                    out += static_cast<char>(c);
                }
        }
    }
}

bool readU32Le(const std::vector<uint8_t>& raw, size_t offset, uint32_t& value) {
    if (offset + 4 > raw.size()) return false;
    value = static_cast<uint32_t>(raw[offset]) |
            (static_cast<uint32_t>(raw[offset + 1]) << 8) |
            (static_cast<uint32_t>(raw[offset + 2]) << 16) |
            (static_cast<uint32_t>(raw[offset + 3]) << 24);
    return true;
}

bool readU16Le(const std::vector<uint8_t>& raw, size_t offset, uint16_t& value) {
    if (offset + 2 > raw.size()) return false;
    value = static_cast<uint16_t>(raw[offset]) | static_cast<uint16_t>(static_cast<uint16_t>(raw[offset + 1]) << 8);
    return true;
}

// Strict reader for the exact format this app records: PCM16 mono 16 kHz RIFF/WAVE.
bool loadMono16kWav(const std::string& path, std::vector<float>& samples) {
    FILE* file = std::fopen(path.c_str(), "rb");
    if (file == nullptr) return false;

    bool ok = false;
    std::vector<uint8_t> raw;
    do {
        if (std::fseek(file, 0, SEEK_END) != 0) break;
        long size = std::ftell(file);
        if (size < 44 || size > kMaxWavBytes) break;
        std::rewind(file);
        raw.resize(static_cast<size_t>(size));
        if (std::fread(raw.data(), 1, raw.size(), file) != raw.size()) break;

        if (std::memcmp(raw.data(), "RIFF", 4) != 0 || std::memcmp(raw.data() + 8, "WAVE", 4) != 0) break;

        size_t offset = 12;
        uint16_t audioFormat = 0;
        uint16_t channels = 0;
        uint32_t sampleRate = 0;
        uint16_t bitsPerSample = 0;
        bool fmtSeen = false;

        while (offset + 8 <= raw.size()) {
            char chunkId[5] = {0, 0, 0, 0, 0};
            std::memcpy(chunkId, raw.data() + offset, 4);
            uint32_t chunkSize = 0;
            if (!readU32Le(raw, offset + 4, chunkSize)) break;
            size_t body = offset + 8;
            if (std::memcmp(chunkId, "fmt ", 4) == 0 && body + 16 <= raw.size()) {
                uint16_t unused = 0;
                if (!readU16Le(raw, body, audioFormat)) break;
                if (!readU16Le(raw, body + 2, channels)) break;
                if (!readU32Le(raw, body + 4, sampleRate)) break;
                if (!readU16Le(raw, body + 14, bitsPerSample)) break;
                (void)unused;
                fmtSeen = true;
            } else if (std::memcmp(chunkId, "data", 4) == 0 && fmtSeen) {
                if (audioFormat != 1 || channels != 1 || sampleRate != kSampleRate || bitsPerSample != 16) break;
                size_t available = (raw.size() - body) / 2 * 2;
                size_t usable = chunkSize < available ? chunkSize : available;
                samples.reserve(usable / 2);
                for (size_t i = 0; i + 1 < usable; i += 2) {
                    uint16_t frame = 0;
                    if (!readU16Le(raw, body + i, frame)) break;
                    samples.push_back(static_cast<int16_t>(frame) / 32768.0f);
                }
                ok = !samples.empty();
                break;
            }
            offset = body + chunkSize + (chunkSize & 1);
        }
    } while (false);

    std::fclose(file);
    return ok;
}

}  // namespace

extern "C" JNIEXPORT jstring JNICALL
Java_actor_starintel_collector_WhisperBridge_transcribe(
        JNIEnv* env, jclass /*clazz*/, jstring modelPath, jstring wavPath,
        jint threads, jstring language) {
    std::string model = jniToStd(env, modelPath);
    std::string wav = jniToStd(env, wavPath);
    std::string lang = jniToStd(env, language);
    int threadCount = threads > 0 ? threads : 2;

    std::vector<float> samples;
    if (!loadMono16kWav(wav, samples)) {
        return env->NewStringUTF("{\"ok\":false,\"error\":\"unreadable wav (must be PCM16 mono 16 kHz)\"}");
    }

    whisper_context_params contextParams = whisper_context_default_params();
    contextParams.use_gpu = false;
    whisper_context* context = whisper_init_from_file_with_params(model.c_str(), contextParams);
    if (context == nullptr) {
        return env->NewStringUTF("{\"ok\":false,\"error\":\"could not load whisper model\"}");
    }

    whisper_full_params params = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    params.n_threads = threadCount;
    params.translate = false;
    params.language = lang.empty() ? "en" : lang.c_str();
    params.print_special = false;
    params.print_progress = false;
    params.print_realtime = false;
    params.print_timestamps = false;
    params.no_context = true;

    std::string json;
    int segmentCount = 0;
    int state = whisper_full(context, params, samples.data(), static_cast<int>(samples.size()));
    if (state != 0) {
        json = "{\"ok\":false,\"error\":\"whisper_full failed\"}";
    } else {
        segmentCount = whisper_full_n_segments(context);
        json = "{\"ok\":true,\"segments\":[";
        for (int i = 0; i < segmentCount; i++) {
            if (i > 0) json += ",";
            int64_t t0 = whisper_full_get_segment_t0(context, i);
            int64_t t1 = whisper_full_get_segment_t1(context, i);
            const char* text = whisper_full_get_segment_text(context, i);
            json += "{\"start\":";
            json += std::to_string(static_cast<double>(t0) / 100.0);
            json += ",\"end\":";
            json += std::to_string(static_cast<double>(t1) / 100.0);
            json += ",\"text\":\"";
            appendEscaped(json, text == nullptr ? "" : text);
            json += "\"}";
        }
        json += "]}";
    }

    whisper_free(context);
    __android_log_print(ANDROID_LOG_INFO, kTag, "transcribed %zu samples, %d segments",
                        samples.size(), state == 0 ? segmentCount : 0);
    return env->NewStringUTF(json.c_str());
}
