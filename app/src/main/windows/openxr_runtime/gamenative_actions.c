#include "gamenative_actions.h"
#include "gamenative_control.h"

#include <math.h>
#include <stdio.h>
#include <string.h>

#define GN_PATHS 128
#define GN_ACTION_SETS 8
#define GN_ACTIONS 64
#define GN_ACTION_SPACES 32
#define GN_SET_MAGIC 0x4758415345543032ull
#define GN_ACTION_MAGIC 0x47584143544e3032ull
#define GN_SPACE_MAGIC 0x4758535041433032ull

typedef struct gn_path_entry { char value[XR_MAX_PATH_LENGTH]; } gn_path_entry;
typedef struct gn_action_set { uint64_t magic; XrInstance owner; uint32_t attached; } gn_action_set;
typedef struct gn_action { uint64_t magic; gn_action_set *set; XrActionType type; XrPath bindings[2]; } gn_action;
typedef struct gn_action_space { uint64_t magic; XrSession owner; gn_action *action; uint32_t hand; XrPosef offset; } gn_action_space;

static XrInstance current_instance;
static XrSession current_session;
static gn_path_entry paths[GN_PATHS];
static uint32_t path_count;
static gn_action_set sets[GN_ACTION_SETS];
static gn_action actions[GN_ACTIONS];
static gn_action_space spaces[GN_ACTION_SPACES];
static gamenative_control_input inputs[2];
static uint64_t sync_serial;

static gn_action_set *valid_set(XrActionSet handle) {
    gn_action_set *set = (gn_action_set *)handle;
    return set >= sets && set < sets + GN_ACTION_SETS && set->magic == GN_SET_MAGIC ? set : NULL;
}

static gn_action *valid_action(XrAction handle) {
    gn_action *action = (gn_action *)handle;
    return action >= actions && action < actions + GN_ACTIONS && action->magic == GN_ACTION_MAGIC ? action : NULL;
}

static const char *path_value(XrPath path) {
    uint64_t index = (uint64_t)path;
    return index && index <= path_count ? paths[index - 1].value : NULL;
}

static int path_hand(XrPath path) {
    const char *value = path_value(path);
    if (!value) return -1;
    if (strstr(value, "/user/hand/left") == value) return 0;
    if (strstr(value, "/user/hand/right") == value) return 1;
    return -1;
}

static XrPath action_binding(gn_action *action, XrPath subaction, int *hand) {
    int requested = path_hand(subaction);
    if (requested >= 0 && action->bindings[requested]) { *hand = requested; return action->bindings[requested]; }
    for (int i = 0; i < 2; ++i) if (action->bindings[i]) { *hand = i; return action->bindings[i]; }
    *hand = requested >= 0 ? requested : 0;
    return XR_NULL_PATH;
}

void gamenative_actions_set_context(XrInstance instance, XrSession session) {
    current_instance = instance;
    current_session = session;
}

void gamenative_actions_reset(void) {
    memset(sets, 0, sizeof(sets));
    memset(actions, 0, sizeof(actions));
    memset(spaces, 0, sizeof(spaces));
    memset(inputs, 0, sizeof(inputs));
    current_session = XR_NULL_HANDLE;
    current_instance = XR_NULL_HANDLE;
}

void gamenative_actions_end_session(void) {
    memset(spaces, 0, sizeof(spaces));
    memset(inputs, 0, sizeof(inputs));
    for (uint32_t i = 0; i < GN_ACTION_SETS; ++i) sets[i].attached = 0;
    current_session = XR_NULL_HANDLE;
}

XrResult XRAPI_CALL gamenative_string_to_path(XrInstance instance, const char *string, XrPath *path) {
    if (instance != current_instance) return XR_ERROR_HANDLE_INVALID;
    if (!string || !path || string[0] != '/' || strlen(string) >= XR_MAX_PATH_LENGTH) return XR_ERROR_PATH_FORMAT_INVALID;
    for (uint32_t i = 0; i < path_count; ++i) if (!strcmp(paths[i].value, string)) { *path = (XrPath)(i + 1); return XR_SUCCESS; }
    if (path_count == GN_PATHS) return XR_ERROR_PATH_COUNT_EXCEEDED;
    strcpy_s(paths[path_count].value, sizeof(paths[path_count].value), string);
    *path = (XrPath)(++path_count);
    return XR_SUCCESS;
}

XrResult XRAPI_CALL gamenative_path_to_string(XrInstance instance, XrPath path, uint32_t capacity, uint32_t *count, char *buffer) {
    if (instance != current_instance) return XR_ERROR_HANDLE_INVALID;
    const char *value = path_value(path);
    if (!value) return XR_ERROR_PATH_INVALID;
    if (!count) return XR_ERROR_VALIDATION_FAILURE;
    *count = (uint32_t)strlen(value) + 1;
    if (!capacity) return XR_SUCCESS;
    if (capacity < *count || !buffer) return XR_ERROR_SIZE_INSUFFICIENT;
    memcpy(buffer, value, *count);
    return XR_SUCCESS;
}

XrResult XRAPI_CALL gamenative_create_action_set(XrInstance instance, const XrActionSetCreateInfo *info, XrActionSet *handle) {
    if (instance != current_instance) return XR_ERROR_HANDLE_INVALID;
    if (!info || info->type != XR_TYPE_ACTION_SET_CREATE_INFO || !handle || !info->actionSetName[0]) return XR_ERROR_VALIDATION_FAILURE;
    for (uint32_t i = 0; i < GN_ACTION_SETS; ++i) if (!sets[i].magic) { sets[i].magic = GN_SET_MAGIC; sets[i].owner = instance; *handle = (XrActionSet)&sets[i]; return XR_SUCCESS; }
    return XR_ERROR_LIMIT_REACHED;
}

XrResult XRAPI_CALL gamenative_destroy_action_set(XrActionSet handle) {
    gn_action_set *set = valid_set(handle);
    if (!set) return XR_ERROR_HANDLE_INVALID;
    for (uint32_t i = 0; i < GN_ACTIONS; ++i) if (actions[i].magic && actions[i].set == set) memset(&actions[i], 0, sizeof(actions[i]));
    memset(set, 0, sizeof(*set));
    return XR_SUCCESS;
}

XrResult XRAPI_CALL gamenative_create_action(XrActionSet set_handle, const XrActionCreateInfo *info, XrAction *handle) {
    gn_action_set *set = valid_set(set_handle);
    if (!set) return XR_ERROR_HANDLE_INVALID;
    if (!info || info->type != XR_TYPE_ACTION_CREATE_INFO || !handle || !info->actionName[0] || set->attached) return XR_ERROR_VALIDATION_FAILURE;
    if (info->countSubactionPaths > 2 || (info->countSubactionPaths && !info->subactionPaths)) return XR_ERROR_PATH_UNSUPPORTED;
    for (uint32_t i = 0; i < info->countSubactionPaths; ++i) if (path_hand(info->subactionPaths[i]) < 0) return XR_ERROR_PATH_UNSUPPORTED;
    for (uint32_t i = 0; i < GN_ACTIONS; ++i) if (!actions[i].magic) { actions[i].magic = GN_ACTION_MAGIC; actions[i].set = set; actions[i].type = info->actionType; *handle = (XrAction)&actions[i]; return XR_SUCCESS; }
    return XR_ERROR_LIMIT_REACHED;
}

XrResult XRAPI_CALL gamenative_destroy_action(XrAction handle) {
    gn_action *action = valid_action(handle);
    if (!action) return XR_ERROR_HANDLE_INVALID;
    memset(action, 0, sizeof(*action));
    return XR_SUCCESS;
}

XrResult XRAPI_CALL gamenative_suggest_bindings(XrInstance instance, const XrInteractionProfileSuggestedBinding *info) {
    if (instance != current_instance) return XR_ERROR_HANDLE_INVALID;
    if (!info || info->type != XR_TYPE_INTERACTION_PROFILE_SUGGESTED_BINDING || !path_value(info->interactionProfile) || (info->countSuggestedBindings && !info->suggestedBindings)) return XR_ERROR_VALIDATION_FAILURE;
    for (uint32_t i = 0; i < info->countSuggestedBindings; ++i) {
        gn_action *action = valid_action(info->suggestedBindings[i].action);
        int hand = path_hand(info->suggestedBindings[i].binding);
        if (!action || hand < 0) return XR_ERROR_PATH_UNSUPPORTED;
        action->bindings[hand] = info->suggestedBindings[i].binding;
    }
    return XR_SUCCESS;
}

XrResult XRAPI_CALL gamenative_attach_action_sets(XrSession session, const XrSessionActionSetsAttachInfo *info) {
    if (session != current_session) return XR_ERROR_HANDLE_INVALID;
    if (!info || info->type != XR_TYPE_SESSION_ACTION_SETS_ATTACH_INFO || !info->countActionSets || !info->actionSets) return XR_ERROR_VALIDATION_FAILURE;
    for (uint32_t i = 0; i < info->countActionSets; ++i) { gn_action_set *set = valid_set(info->actionSets[i]); if (!set) return XR_ERROR_HANDLE_INVALID; set->attached = 1; }
    return XR_SUCCESS;
}

XrResult XRAPI_CALL gamenative_sync_actions(XrSession session, const XrActionsSyncInfo *info) {
    if (session != current_session) return XR_ERROR_HANDLE_INVALID;
    if (!info || info->type != XR_TYPE_ACTIONS_SYNC_INFO || !info->countActiveActionSets || !info->activeActionSets) return XR_ERROR_VALIDATION_FAILURE;
    for (uint32_t i = 0; i < info->countActiveActionSets; ++i) if (!valid_set(info->activeActionSets[i].actionSet)) return XR_ERROR_HANDLE_INVALID;
    if (gamenative_control_get_input(0, &inputs[0]) || gamenative_control_get_input(1, &inputs[1])) return XR_ERROR_RUNTIME_FAILURE;
    ++sync_serial;
    return XR_SUCCESS;
}

static XrResult action_info(XrSession session, const XrActionStateGetInfo *info, XrActionType type, gn_action **action, int *hand, const char **binding) {
    if (session != current_session) return XR_ERROR_HANDLE_INVALID;
    if (!info || info->type != XR_TYPE_ACTION_STATE_GET_INFO) return XR_ERROR_VALIDATION_FAILURE;
    *action = valid_action(info->action);
    if (!*action) return XR_ERROR_HANDLE_INVALID;
    if ((*action)->type != type) return XR_ERROR_ACTION_TYPE_MISMATCH;
    XrPath path = action_binding(*action, info->subactionPath, hand);
    *binding = path_value(path);
    return XR_SUCCESS;
}

XrResult XRAPI_CALL gamenative_get_action_boolean(XrSession session, const XrActionStateGetInfo *info, XrActionStateBoolean *state) {
    if (!state || state->type != XR_TYPE_ACTION_STATE_BOOLEAN) return XR_ERROR_VALIDATION_FAILURE;
    gn_action *action; int hand; const char *binding;
    XrResult result = action_info(session, info, XR_ACTION_TYPE_BOOLEAN_INPUT, &action, &hand, &binding);
    if (XR_FAILED(result)) return result;
    uint32_t mask = 0;
    if (binding) {
        if (strstr(binding, "/input/a/")) mask = 1u << 0;
        else if (strstr(binding, "/input/b/")) mask = 1u << 1;
        else if (strstr(binding, "/input/x/")) mask = 1u << 2;
        else if (strstr(binding, "/input/y/")) mask = 1u << 3;
        else if (strstr(binding, "/squeeze/")) mask = 1u << (hand ? 5 : 4);
        else if (strstr(binding, "/menu/")) mask = 1u << 6;
        else if (strstr(binding, "/thumbstick/")) mask = 1u << (hand ? 9 : 8);
    }
    state->currentState = mask && (inputs[hand].buttons & mask) ? XR_TRUE : XR_FALSE;
    state->changedSinceLastSync = XR_FALSE;
    state->lastChangeTime = 0;
    state->isActive = binding && inputs[hand].active ? XR_TRUE : XR_FALSE;
    return XR_SUCCESS;
}

XrResult XRAPI_CALL gamenative_get_action_float(XrSession session, const XrActionStateGetInfo *info, XrActionStateFloat *state) {
    if (!state || state->type != XR_TYPE_ACTION_STATE_FLOAT) return XR_ERROR_VALIDATION_FAILURE;
    gn_action *action; int hand; const char *binding;
    XrResult result = action_info(session, info, XR_ACTION_TYPE_FLOAT_INPUT, &action, &hand, &binding);
    if (XR_FAILED(result)) return result;
    state->currentState = binding && strstr(binding, "/trigger/") ? inputs[hand].trigger / 1000000.0f : inputs[hand].squeeze / 1000000.0f;
    state->changedSinceLastSync = XR_FALSE;
    state->lastChangeTime = 0;
    state->isActive = binding && inputs[hand].active ? XR_TRUE : XR_FALSE;
    return XR_SUCCESS;
}

XrResult XRAPI_CALL gamenative_get_action_vector(XrSession session, const XrActionStateGetInfo *info, XrActionStateVector2f *state) {
    if (!state || state->type != XR_TYPE_ACTION_STATE_VECTOR2F) return XR_ERROR_VALIDATION_FAILURE;
    gn_action *action; int hand; const char *binding;
    XrResult result = action_info(session, info, XR_ACTION_TYPE_VECTOR2F_INPUT, &action, &hand, &binding);
    if (XR_FAILED(result)) return result;
    state->currentState.x = inputs[hand].stick[0] / 1000000.0f;
    state->currentState.y = inputs[hand].stick[1] / 1000000.0f;
    state->changedSinceLastSync = XR_FALSE;
    state->lastChangeTime = 0;
    state->isActive = binding && inputs[hand].active ? XR_TRUE : XR_FALSE;
    return XR_SUCCESS;
}

XrResult XRAPI_CALL gamenative_get_action_pose(XrSession session, const XrActionStateGetInfo *info, XrActionStatePose *state) {
    if (!state || state->type != XR_TYPE_ACTION_STATE_POSE) return XR_ERROR_VALIDATION_FAILURE;
    gn_action *action; int hand; const char *binding;
    XrResult result = action_info(session, info, XR_ACTION_TYPE_POSE_INPUT, &action, &hand, &binding);
    if (XR_FAILED(result)) return result;
    state->isActive = binding && inputs[hand].active ? XR_TRUE : XR_FALSE;
    return XR_SUCCESS;
}

XrResult XRAPI_CALL gamenative_create_action_space(XrSession session, const XrActionSpaceCreateInfo *info, XrSpace *handle) {
    if (session != current_session) return XR_ERROR_HANDLE_INVALID;
    if (!info || info->type != XR_TYPE_ACTION_SPACE_CREATE_INFO || !handle) return XR_ERROR_VALIDATION_FAILURE;
    gn_action *action = valid_action(info->action);
    if (!action || action->type != XR_ACTION_TYPE_POSE_INPUT) return action ? XR_ERROR_ACTION_TYPE_MISMATCH : XR_ERROR_HANDLE_INVALID;
    int hand = path_hand(info->subactionPath);
    if (hand < 0) { int selected; action_binding(action, XR_NULL_PATH, &selected); hand = selected; }
    for (uint32_t i = 0; i < GN_ACTION_SPACES; ++i) if (!spaces[i].magic) { spaces[i].magic = GN_SPACE_MAGIC; spaces[i].owner = session; spaces[i].action = action; spaces[i].hand = hand; spaces[i].offset = info->poseInActionSpace; *handle = (XrSpace)&spaces[i]; return XR_SUCCESS; }
    return XR_ERROR_LIMIT_REACHED;
}

XrResult XRAPI_CALL gamenative_locate_space(XrSpace handle, XrSpace base, XrTime time, XrSpaceLocation *location) {
    (void)time;
    if (!handle || !base) return XR_ERROR_HANDLE_INVALID;
    if (!location || location->type != XR_TYPE_SPACE_LOCATION) return XR_ERROR_VALIDATION_FAILURE;
    location->pose.orientation.w = 1.0f;
    location->locationFlags = XR_SPACE_LOCATION_ORIENTATION_VALID_BIT | XR_SPACE_LOCATION_POSITION_VALID_BIT;
    gn_action_space *space = (gn_action_space *)handle;
    if (space >= spaces && space < spaces + GN_ACTION_SPACES && space->magic == GN_SPACE_MAGIC) {
        uint32_t hand = space->hand;
        int32_t *orientation = inputs[hand].aim_orientation;
        int32_t *position = inputs[hand].aim_position;
        const char *binding = path_value(space->action->bindings[hand]);
        if (binding && strstr(binding, "/grip/")) {
            orientation = inputs[hand].grip_orientation;
            position = inputs[hand].grip_position;
        }
        location->pose.orientation.x = orientation[0] / 1000000.0f;
        location->pose.orientation.y = orientation[1] / 1000000.0f;
        location->pose.orientation.z = orientation[2] / 1000000.0f;
        location->pose.orientation.w = orientation[3] / 1000000.0f;
        location->pose.position.x = position[0] / 1000000.0f + space->offset.position.x;
        location->pose.position.y = position[1] / 1000000.0f + space->offset.position.y;
        location->pose.position.z = position[2] / 1000000.0f + space->offset.position.z;
        if (!inputs[hand].active) location->locationFlags = 0;
    }
    return XR_SUCCESS;
}

XrResult gamenative_destroy_action_space(XrSpace handle) {
    gn_action_space *space = (gn_action_space *)handle;
    if (space < spaces || space >= spaces + GN_ACTION_SPACES || space->magic != GN_SPACE_MAGIC) return XR_ERROR_HANDLE_INVALID;
    memset(space, 0, sizeof(*space));
    return XR_SUCCESS;
}

XrResult XRAPI_CALL gamenative_enumerate_bound_sources(XrSession session, const XrBoundSourcesForActionEnumerateInfo *info, uint32_t capacity, uint32_t *count, XrPath *sources) {
    if (session != current_session) return XR_ERROR_HANDLE_INVALID;
    if (!info || info->type != XR_TYPE_BOUND_SOURCES_FOR_ACTION_ENUMERATE_INFO || !count) return XR_ERROR_VALIDATION_FAILURE;
    gn_action *action = valid_action(info->action);
    if (!action) return XR_ERROR_HANDLE_INVALID;
    uint32_t total = (action->bindings[0] ? 1u : 0u) + (action->bindings[1] ? 1u : 0u);
    *count = total;
    if (!capacity) return XR_SUCCESS;
    if (capacity < total || !sources) return XR_ERROR_SIZE_INSUFFICIENT;
    uint32_t output = 0;
    for (uint32_t hand = 0; hand < 2; ++hand) if (action->bindings[hand]) sources[output++] = action->bindings[hand];
    return XR_SUCCESS;
}

XrResult XRAPI_CALL gamenative_get_source_name(XrSession session, const XrInputSourceLocalizedNameGetInfo *info, uint32_t capacity, uint32_t *count, char *buffer) {
    if (session != current_session) return XR_ERROR_HANDLE_INVALID;
    if (!info || info->type != XR_TYPE_INPUT_SOURCE_LOCALIZED_NAME_GET_INFO || !count) return XR_ERROR_VALIDATION_FAILURE;
    const char *path = path_value(info->sourcePath);
    if (!path) return XR_ERROR_PATH_INVALID;
    char name[256] = "";
    if (info->whichComponents & XR_INPUT_SOURCE_LOCALIZED_NAME_USER_PATH_BIT) strcat_s(name, sizeof(name), strstr(path, "/left/") ? "Left Hand" : "Right Hand");
    if (info->whichComponents & XR_INPUT_SOURCE_LOCALIZED_NAME_INTERACTION_PROFILE_BIT) strcat_s(name, sizeof(name), name[0] ? " Quest Touch" : "Quest Touch");
    if (info->whichComponents & XR_INPUT_SOURCE_LOCALIZED_NAME_COMPONENT_BIT) { const char *component = strstr(path, "/input/"); if (!component) component = strstr(path, "/output/"); if (component) strcat_s(name, sizeof(name), component); }
    *count = (uint32_t)strlen(name) + 1;
    if (!capacity) return XR_SUCCESS;
    if (capacity < *count || !buffer) return XR_ERROR_SIZE_INSUFFICIENT;
    memcpy(buffer, name, *count);
    return XR_SUCCESS;
}

XrResult XRAPI_CALL gamenative_get_interaction_profile(XrSession session, XrPath top_level, XrInteractionProfileState *state) {
    if (session != current_session) return XR_ERROR_HANDLE_INVALID;
    if (!state || state->type != XR_TYPE_INTERACTION_PROFILE_STATE || path_hand(top_level) < 0) return XR_ERROR_VALIDATION_FAILURE;
    return gamenative_string_to_path(current_instance, "/interaction_profiles/oculus/touch_controller", &state->interactionProfile);
}

XrResult XRAPI_CALL gamenative_apply_haptic(XrSession session, const XrHapticActionInfo *info, const XrHapticBaseHeader *feedback) {
    if (session != current_session) return XR_ERROR_HANDLE_INVALID;
    if (!info || info->type != XR_TYPE_HAPTIC_ACTION_INFO || !feedback || feedback->type != XR_TYPE_HAPTIC_VIBRATION) return XR_ERROR_VALIDATION_FAILURE;
    gn_action *action = valid_action(info->action);
    if (!action || action->type != XR_ACTION_TYPE_VIBRATION_OUTPUT) return action ? XR_ERROR_ACTION_TYPE_MISMATCH : XR_ERROR_HANDLE_INVALID;
    int hand; action_binding(action, info->subactionPath, &hand);
    const XrHapticVibration *vibration = (const XrHapticVibration *)feedback;
    int32_t amplitude = (int32_t)(vibration->amplitude * 1000000.0f);
    int32_t frequency = vibration->frequency == XR_FREQUENCY_UNSPECIFIED ? 0 : (int32_t)(vibration->frequency * 1000000.0f);
    return gamenative_control_haptic(hand, amplitude, vibration->duration, frequency) == 0 ? XR_SUCCESS : XR_ERROR_RUNTIME_FAILURE;
}

XrResult XRAPI_CALL gamenative_stop_haptic(XrSession session, const XrHapticActionInfo *info) {
    if (!info || info->type != XR_TYPE_HAPTIC_ACTION_INFO) return XR_ERROR_VALIDATION_FAILURE;
    gn_action *action = valid_action(info->action);
    if (!action) return XR_ERROR_HANDLE_INVALID;
    int hand; action_binding(action, info->subactionPath, &hand);
    return session == current_session && gamenative_control_haptic(hand, 0, 0, 0) == 0 ? XR_SUCCESS : XR_ERROR_RUNTIME_FAILURE;
}
