#include "gamenative_control.h"

#include <winsock2.h>
#include <ws2tcpip.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

static SOCKET control_socket = INVALID_SOCKET;
static CRITICAL_SECTION control_lock;
static INIT_ONCE control_once = INIT_ONCE_STATIC_INIT;

static BOOL CALLBACK initialize_control(PINIT_ONCE once, PVOID parameter, PVOID *context) {
    WSADATA data;
    (void)once;
    (void)parameter;
    (void)context;
    InitializeCriticalSection(&control_lock);
    return WSAStartup(MAKEWORD(2, 2), &data) == 0;
}

static int send_all(const char *data, int length) {
    while (length > 0) {
        int sent = send(control_socket, data, length, 0);
        if (sent <= 0) return -1;
        data += sent;
        length -= sent;
    }
    return 0;
}

static char receive_buffer[1024];
static uint32_t receive_buffer_length = 0;
static uint32_t receive_buffer_offset = 0;

static int receive_byte(char *value) {
    if (receive_buffer_offset >= receive_buffer_length) {
        int received = recv(control_socket, receive_buffer, sizeof(receive_buffer), 0);
        if (received <= 0) return -1;
        receive_buffer_length = (uint32_t)received;
        receive_buffer_offset = 0;
    }
    *value = receive_buffer[receive_buffer_offset++];
    return 0;
}

static int receive_line(char *response, uint32_t capacity) {
    uint32_t length = 0;
    while (length + 1 < capacity) {
        char value;
        if (receive_byte(&value) != 0) return -1;
        if (value == '\n') {
            response[length] = '\0';
            return 0;
        }
        if (value == '\r' || value == '\0') return -1;
        response[length++] = value;
    }
    return -1;
}

int gamenative_control_connect(void) {
    InitOnceExecuteOnce(&control_once, initialize_control, NULL, NULL);
    EnterCriticalSection(&control_lock);
    if (control_socket != INVALID_SOCKET) {
        LeaveCriticalSection(&control_lock);
        return 0;
    }
    receive_buffer_length = 0;
    receive_buffer_offset = 0;
    control_socket = socket(AF_INET, SOCK_STREAM, IPPROTO_TCP);
    if (control_socket == INVALID_SOCKET) {
        LeaveCriticalSection(&control_lock);
        return -1;
    }
    BOOL no_delay = TRUE;
    setsockopt(control_socket, IPPROTO_TCP, TCP_NODELAY, (const char *)&no_delay, sizeof(no_delay));
    DWORD timeout = 15000;
    setsockopt(control_socket, SOL_SOCKET, SO_RCVTIMEO, (const char *)&timeout, sizeof(timeout));
    struct sockaddr_in address;
    memset(&address, 0, sizeof(address));
    address.sin_family = AF_INET;
    address.sin_port = htons(38476);
    InetPtonA(AF_INET, "127.0.0.1", &address.sin_addr);
    if (connect(control_socket, (const struct sockaddr *)&address, sizeof(address)) != 0) {
        closesocket(control_socket);
        control_socket = INVALID_SOCKET;
        LeaveCriticalSection(&control_lock);
        return -1;
    }
    const char hello[] = "HELLO\n";
    char response[128];
    int result = send_all(hello, (int)strlen(hello)) == 0 && receive_line(response, sizeof(response)) == 0 &&
                 strcmp(response, "OK GameNativeVR 2") == 0 ? 0 : -1;
    if (result != 0) {
        closesocket(control_socket);
        control_socket = INVALID_SOCKET;
    }
    LeaveCriticalSection(&control_lock);
    return result;
}

void gamenative_control_disconnect(void) {
    InitOnceExecuteOnce(&control_once, initialize_control, NULL, NULL);
    EnterCriticalSection(&control_lock);
    if (control_socket != INVALID_SOCKET) {
        send_all("BYE\n", 4);
        shutdown(control_socket, SD_BOTH);
        closesocket(control_socket);
        control_socket = INVALID_SOCKET;
    }
    LeaveCriticalSection(&control_lock);
}

int gamenative_control_request(const char *command, char *response, uint32_t capacity) {
    if (command == NULL || response == NULL || capacity < 2 || strlen(command) > 1023) return -1;
    if (gamenative_control_connect() != 0) return -1;
    EnterCriticalSection(&control_lock);
    int result = send_all(command, (int)strlen(command));
    if (result == 0 && command[strlen(command) - 1] != '\n') result = send_all("\n", 1);
    if (result == 0) result = receive_line(response, capacity);
    if (result != 0) {
        closesocket(control_socket);
        control_socket = INVALID_SOCKET;
    }
    LeaveCriticalSection(&control_lock);
    return result;
}

int gamenative_control_wait_frame(gamenative_control_frame *frame) {
    char response[512];
    if (frame == NULL || gamenative_control_request("WAIT_FRAME", response, sizeof(response)) != 0) return -1;
    unsigned long long serial = 0;
    long long time = 0;
    long long period = 0;
    unsigned int render = 0;
    unsigned int state = 0;
    if (sscanf_s(response, "OK serial=%llu time=%lld period=%lld shouldRender=%u state=%u",
                 &serial, &time, &period, &render, &state) != 5) return -1;
    frame->serial = serial;
    frame->display_time = time;
    frame->display_period = period;
    frame->should_render = render;
    frame->session_state = state;
    return 0;
}

int gamenative_control_get_views(uint32_t *width, uint32_t *height) {
    char response[256];
    unsigned int count = 0;
    if (width == NULL || height == NULL ||
        gamenative_control_request("GET_VIEWS", response, sizeof(response)) != 0) return -1;
    if (sscanf_s(response, "OK count=%u width=%u height=%u", &count, width, height) != 3 ||
        count != 2 || *width == 0 || *height == 0 || *width > 8192 || *height > 8192) return -1;
    return 0;
}

int gamenative_control_get_bounds(uint32_t *available, uint32_t *width, uint32_t *height) {
    char response[256];
    if (available == NULL || width == NULL || height == NULL ||
        gamenative_control_request("GET_BOUNDS", response, sizeof(response)) != 0) return -1;
    if (sscanf_s(response, "OK available=%u width=%u height=%u", available, width, height) != 3 ||
        *available > 1 || *width > 100000000 || *height > 100000000) return -1;
    return 0;
}

int gamenative_control_locate_views(gamenative_control_view views[2], uint32_t *flags) {
    char response[1024];
    if (views == NULL || flags == NULL || gamenative_control_request("LOCATE_VIEWS", response, sizeof(response)) != 0) return -1;
    int consumed = 0;
    if (sscanf_s(response, "OK flags=%u %n", flags, &consumed) < 1 || consumed <= 0) return -1;
    const char *cursor = response + consumed;
    for (uint32_t eye = 0; eye < 2; ++eye) {
        int *values[] = {
            &views[eye].orientation[0], &views[eye].orientation[1], &views[eye].orientation[2], &views[eye].orientation[3],
            &views[eye].position[0], &views[eye].position[1], &views[eye].position[2],
            &views[eye].fov[0], &views[eye].fov[1], &views[eye].fov[2], &views[eye].fov[3],
        };
        for (uint32_t value = 0; value < 11; ++value) {
            char *end = NULL;
            long parsed = strtol(cursor, &end, 10);
            if (end == cursor) return -1;
            *values[value] = (int32_t)parsed;
            cursor = end;
            while (*cursor == ' ') ++cursor;
        }
    }
    return 0;
}

int gamenative_control_get_input(uint32_t hand, gamenative_control_input *input) {
    if (hand > 1 || input == NULL) return -1;
    char command[32];
    char response[512];
    sprintf_s(command, sizeof(command), "GET_INPUT hand=%u", hand);
    if (gamenative_control_request(command, response, sizeof(response)) != 0) return -1;
    return sscanf_s(response,
                    "OK active=%u buttons=%u %d %d %d %d %d %d %d %d %d %d %d %d %d %d %d %d %d %d",
                    &input->active, &input->buttons, &input->trigger, &input->squeeze,
                    &input->stick[0], &input->stick[1],
                    &input->grip_orientation[0], &input->grip_orientation[1],
                    &input->grip_orientation[2], &input->grip_orientation[3],
                    &input->grip_position[0], &input->grip_position[1], &input->grip_position[2],
                    &input->aim_orientation[0], &input->aim_orientation[1],
                    &input->aim_orientation[2], &input->aim_orientation[3],
                    &input->aim_position[0], &input->aim_position[1], &input->aim_position[2]) == 20 ? 0 : -1;
}

int gamenative_control_haptic(uint32_t hand, int32_t amplitude, int64_t duration, int32_t frequency) {
    if (hand > 1 || amplitude < 0 || amplitude > 1000000 || duration < 0 || frequency < 0) return -1;
    char command[160];
    char response[128];
    sprintf_s(command, sizeof(command), "HAPTIC hand=%u amp=%d dur=%lld freq=%d", hand, amplitude, duration, frequency);
    return gamenative_control_request(command, response, sizeof(response)) == 0 && strcmp(response, "OK") == 0 ? 0 : -1;
}
