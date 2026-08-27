#ifndef GAMENATIVE_CONTROL_H
#define GAMENATIVE_CONTROL_H

#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

typedef struct gamenative_control_frame {
    uint64_t serial;
    int64_t display_time;
    int64_t display_period;
    uint32_t should_render;
    uint32_t session_state;
} gamenative_control_frame;

typedef struct gamenative_control_view {
    int32_t orientation[4];
    int32_t position[3];
    int32_t fov[4];
} gamenative_control_view;

typedef struct gamenative_control_input {
    uint32_t active;
    uint32_t buttons;
    int32_t trigger;
    int32_t squeeze;
    int32_t stick[2];
    int32_t grip_orientation[4];
    int32_t grip_position[3];
    int32_t aim_orientation[4];
    int32_t aim_position[3];
} gamenative_control_input;

int gamenative_control_connect(void);
void gamenative_control_disconnect(void);
int gamenative_control_request(const char *command, char *response, uint32_t capacity);
int gamenative_control_wait_frame(gamenative_control_frame *frame);
int gamenative_control_get_views(uint32_t *width, uint32_t *height);
int gamenative_control_get_bounds(uint32_t *available, uint32_t *width, uint32_t *height);
int gamenative_control_locate_views(gamenative_control_view views[2], uint32_t *flags);
int gamenative_control_get_input(uint32_t hand, gamenative_control_input *input);
int gamenative_control_haptic(uint32_t hand, int32_t amplitude, int64_t duration, int32_t frequency);

#ifdef __cplusplus
}
#endif

#endif
