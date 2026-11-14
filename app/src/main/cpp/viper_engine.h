#pragma once

// Platform-neutral C API over ViPERDSP's ViPER class, for hosts that run the
// engine in their own process (the rootless Android app, a desktop daemon).
// Audio is interleaved stereo float. viper_engine_set_param() and
// viper_engine_process() may be called from different threads.

#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

typedef struct viper_engine viper_engine;

viper_engine *viper_engine_create(uint32_t sampling_rate);
void viper_engine_destroy(viper_engine *engine);

// Changes the sampling rate and resets every effect, as the driver does on
// EFFECT_CMD_SET_CONFIG.
void viper_engine_set_sampling_rate(viper_engine *engine, uint32_t sampling_rate);
void viper_engine_reset(viper_engine *engine);

// `payload` uses the app's ViperParamPayload encoding: little-endian
// u32 type, i32 index, u32 count-or-size, then the value bytes.
// Returns 1 if the engine accepted the parameter, 0 otherwise.
int viper_engine_set_param(
    viper_engine *engine, int32_t param, const uint8_t *payload, size_t payload_size
);

// Processes `frame_count` interleaved stereo frames in place.
void viper_engine_process(viper_engine *engine, float *samples, uint32_t frame_count);

uint32_t viper_engine_sampling_rate(const viper_engine *engine);
uint64_t viper_engine_processed_frames(const viper_engine *engine);
uint32_t viper_engine_convolver_kernel_id(const viper_engine *engine);
const char *viper_engine_version_name(void);
int32_t viper_engine_version_code(void);

#ifdef __cplusplus
}
#endif
