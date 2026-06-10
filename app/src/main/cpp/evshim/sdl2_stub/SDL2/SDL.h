/*
 * Minimal SDL2 type stubs for cross-compiling evshim.c with the Android NDK.
 * Only the types, constants, and struct layouts that evshim.c actually uses
 * are defined here.  All SDL functions are loaded at runtime via dlsym(), so
 * no function prototypes are needed.
 *
 * Struct layout must match SDL2 >= 2.24.0 (SDL_VirtualJoystickDesc).
 */
#ifndef SDL_STUB_H
#define SDL_STUB_H

#include <stdint.h>

typedef uint8_t  Uint8;
typedef uint16_t Uint16;
typedef uint32_t Uint32;
typedef int32_t  Sint32;

#define SDLCALL

#define SDL_INIT_JOYSTICK 0x00000200u

typedef struct SDL_Joystick SDL_Joystick;

typedef struct SDL_version {
    Uint8 major;
    Uint8 minor;
    Uint8 patch;
} SDL_version;

typedef enum {
    SDL_JOYSTICK_TYPE_UNKNOWN = 0,
    SDL_JOYSTICK_TYPE_GAMECONTROLLER,
    SDL_JOYSTICK_TYPE_WHEEL,
    SDL_JOYSTICK_TYPE_ARCADE_STICK,
    SDL_JOYSTICK_TYPE_FLIGHT_STICK,
    SDL_JOYSTICK_TYPE_DANCE_PAD,
    SDL_JOYSTICK_TYPE_GUITAR,
    SDL_JOYSTICK_TYPE_DRUM_KIT,
    SDL_JOYSTICK_TYPE_ARCADE_PAD,
    SDL_JOYSTICK_TYPE_THROTTLE
} SDL_JoystickType;

#define SDL_VIRTUAL_JOYSTICK_DESC_VERSION 1

typedef struct SDL_VirtualJoystickDesc {
    Uint16 version;
    Uint16 type;
    Uint16 naxes;
    Uint16 nbuttons;
    Uint16 nhats;
    Uint16 vendor_id;
    Uint16 product_id;
    Uint16 padding;
    Uint32 button_mask;
    Uint32 axis_mask;
    const char *name;
    void *userdata;
    void (SDLCALL *Update)(void *userdata);
    void (SDLCALL *SetPlayerIndex)(void *userdata, int player_index);
    int  (SDLCALL *Rumble)(void *userdata, Uint16 low_frequency_rumble, Uint16 high_frequency_rumble);
    int  (SDLCALL *RumbleTriggers)(void *userdata, Uint16 left_rumble, Uint16 right_rumble);
    int  (SDLCALL *SetLED)(void *userdata, Uint8 red, Uint8 green, Uint8 blue);
    int  (SDLCALL *SendEffect)(void *userdata, const void *data, int size);
} SDL_VirtualJoystickDesc;

#endif /* SDL_STUB_H */
