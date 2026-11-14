#include "viper_engine.h"

#include "viper/ViPER.h"
#include <cstring>
#include <mutex>
#include <vector>

namespace {

constexpr size_t kPayloadHeaderSize = sizeof(uint32_t) + sizeof(int32_t) + sizeof(uint32_t);

// Mirrors the driver's decoder (ViPERFX_RE src/ViperContext.cpp), so the app's
// payloads mean the same thing in process as they do through AudioEffect.
bool DecodeParamValue(const uint8_t *bytes, size_t byte_count, viper::ParamValue *out) {
    if (bytes == nullptr || byte_count < kPayloadHeaderSize) return false;

    uint32_t type;
    uint32_t count_or_size;
    std::memcpy(&type, bytes, sizeof(type));
    std::memcpy(&out->index, bytes + sizeof(type), sizeof(out->index));
    std::memcpy(&count_or_size, bytes + sizeof(type) + sizeof(out->index), sizeof(count_or_size));

    out->type = static_cast<viper::ParamValueType>(type);
    const uint8_t *payload = bytes + kPayloadHeaderSize;
    const size_t payload_size = byte_count - kPayloadHeaderSize;

    switch (out->type) {
        case viper::ParamValueType::kBool:
            if (count_or_size != 1 || payload_size != 1) return false;
            out->bool_value = payload[0] != 0;
            return true;
        case viper::ParamValueType::kInt:
            if (count_or_size != 1 || payload_size != sizeof(int32_t)) return false;
            std::memcpy(&out->int_value, payload, sizeof(out->int_value));
            return true;
        case viper::ParamValueType::kFloat:
            if (count_or_size != 1 || payload_size != sizeof(float)) return false;
            std::memcpy(&out->float_value, payload, sizeof(out->float_value));
            return true;
        case viper::ParamValueType::kFloatArray:
            if (payload_size != count_or_size * sizeof(float)) return false;
            out->float_array = reinterpret_cast<const float *>(payload);
            out->float_count = count_or_size;
            return true;
        case viper::ParamValueType::kBytes:
            if (payload_size != count_or_size) return false;
            out->bytes = payload;
            out->byte_count = count_or_size;
            return true;
        case viper::ParamValueType::kIntArray:
            if (payload_size != count_or_size * sizeof(int32_t)) return false;
            out->int_array = reinterpret_cast<const int32_t *>(payload);
            out->int_count = count_or_size;
            return true;
        default:
            return false;
    }
}

// Flushes denormals to zero while the engine runs, like the driver does:
// decaying filter tails otherwise fall into slow denormal arithmetic.
class FlushDenormals {
public:
    FlushDenormals() {
#if defined(__aarch64__)
        asm volatile("mrs %0, fpcr" : "=r"(saved_));
        asm volatile("msr fpcr, %0" ::"r"(saved_ | (1 << 24)));
#elif defined(__arm__)
        asm volatile("vmrs %0, fpscr" : "=r"(saved_));
        asm volatile("vmsr fpscr, %0" ::"r"(saved_ | (1 << 24)));
#endif
    }

    ~FlushDenormals() {
#if defined(__aarch64__)
        asm volatile("msr fpcr, %0" ::"r"(saved_));
#elif defined(__arm__)
        asm volatile("vmsr fpscr, %0" ::"r"(saved_));
#endif
    }

private:
#if defined(__aarch64__)
    uint64_t saved_ = 0;
#else
    uint32_t saved_ = 0;
#endif
};

} // namespace

struct viper_engine {
    std::mutex lock;
    ViPER viper;
    std::vector<float> buffer;
};

viper_engine *viper_engine_create(uint32_t sampling_rate) {
    auto *engine = new viper_engine();
    viper_engine_set_sampling_rate(engine, sampling_rate);
    return engine;
}

void viper_engine_destroy(viper_engine *engine) { delete engine; }

void viper_engine_set_sampling_rate(viper_engine *engine, uint32_t sampling_rate) {
    std::lock_guard<std::mutex> guard(engine->lock);
    engine->viper.SetSamplingRate(sampling_rate);
    engine->viper.ResetAllEffects();
}

void viper_engine_reset(viper_engine *engine) {
    std::lock_guard<std::mutex> guard(engine->lock);
    engine->viper.ResetAllEffects();
}

int viper_engine_set_param(
    viper_engine *engine, int32_t param, const uint8_t *payload, size_t payload_size
) {
    viper::ParamValue value;
    if (!DecodeParamValue(payload, payload_size, &value)) return 0;
    std::lock_guard<std::mutex> guard(engine->lock);
    return engine->viper.DispatchParamValue(param, value) ? 1 : 0;
}

void viper_engine_process(viper_engine *engine, float *samples, uint32_t frame_count) {
    if (frame_count == 0) return;
    std::lock_guard<std::mutex> guard(engine->lock);
    FlushDenormals ftz;
    auto &buffer = engine->buffer;
    if (buffer.size() < frame_count * 2) buffer.resize(frame_count * 2);
    std::memcpy(buffer.data(), samples, frame_count * 2 * sizeof(float));
    engine->viper.Process(buffer, frame_count);
    std::memcpy(samples, buffer.data(), frame_count * 2 * sizeof(float));
}

uint32_t viper_engine_sampling_rate(const viper_engine *engine) {
    return engine->viper.GetSamplingRate();
}

uint64_t viper_engine_processed_frames(const viper_engine *engine) {
    return engine->viper.GetProcessedFrames();
}

uint32_t viper_engine_convolver_kernel_id(const viper_engine *engine) {
    return engine->viper.GetConvolverKernelID();
}

const char *viper_engine_version_name(void) { return VERSION_NAME; }

int32_t viper_engine_version_code(void) { return VERSION_CODE; }
