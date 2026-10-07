/* Run on a Linux CI runner with SDL2; include the production bridge so the
 * virtual joystick descriptor and button application code are exercised. */
#include <stdbool.h>
#include "../evshim.c"

#define CHECK(condition, message) do { \
    if (!(condition)) { fprintf(stderr, "FAIL: %s (%s)\n", message, SDL_GetError()); exit(1); } \
} while (0)

int main(void)
{
    /* Wine's SDL bus initializes the game-controller subsystem as well. */
    CHECK(SDL_Init(SDL_INIT_GAMECONTROLLER) == 0, "SDL game-controller subsystem initialized");
    initialize_wine(0);
    CHECK(sdl_handle != NULL, "SDL loaded");
    SDL_GameController *pads[2];
    for (int slot = 0; slot < 2; slot++) {
        CHECK(attach_vjoy(slot) == 0, "virtual joystick attached");
        pads[slot] = SDL_GameControllerOpen(vjoy_ids[slot]);
        CHECK(pads[slot] != NULL, "virtual joystick recognized as a game controller");
    }
    const SDL_GameControllerButton buttons[] = {
        SDL_CONTROLLER_BUTTON_A,
        SDL_CONTROLLER_BUTTON_LEFTSHOULDER,
        SDL_CONTROLLER_BUTTON_RIGHTSHOULDER,
    };
    for (unsigned int b = 0; b < sizeof(buttons) / sizeof(buttons[0]); b++) {
        struct gamepad_state state = {0};
        state.lt = state.rt = -32767;
        state.btn[buttons[b]] = 1;
        apply_vjoy_state(vjoy_handles[0], &state);
        SDL_JoystickUpdate();
        CHECK(SDL_GameControllerGetButton(pads[0], buttons[b]) == 1, "P1 press");
        CHECK(SDL_GameControllerGetButton(pads[1], buttons[b]) == 0, "P2 untouched");
        apply_vjoy_state(vjoy_handles[1], &state);
        SDL_JoystickUpdate();
        CHECK(SDL_GameControllerGetButton(pads[1], buttons[b]) == 1, "P2 press");
        state.btn[buttons[b]] = 0;
        apply_vjoy_state(vjoy_handles[1], &state);
        SDL_JoystickUpdate();
        CHECK(SDL_GameControllerGetButton(pads[1], buttons[b]) == 0, "P2 release");
        CHECK(SDL_GameControllerGetButton(pads[0], buttons[b]) == 1, "P1 stays pressed");
        apply_vjoy_state(vjoy_handles[0], &state);
        SDL_JoystickUpdate();
    }
    for (int slot = 0; slot < 2; slot++) {
        SDL_GameControllerClose(pads[slot]);
        detach_vjoy(slot);
    }
    puts("PASS: A, LB and RB press/release reach both SDL controllers independently");
    return 0;
}
