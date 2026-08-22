#ifndef GAMENATIVE_ACTIONS_H
#define GAMENATIVE_ACTIONS_H

#include <openxr/openxr.h>

void gamenative_actions_set_context(XrInstance instance, XrSession session);
void gamenative_actions_reset(void);
void gamenative_actions_end_session(void);
XrResult XRAPI_CALL gamenative_string_to_path(XrInstance instance, const char *string, XrPath *path);
XrResult XRAPI_CALL gamenative_path_to_string(XrInstance instance, XrPath path, uint32_t capacity, uint32_t *count, char *buffer);
XrResult XRAPI_CALL gamenative_create_action_set(XrInstance instance, const XrActionSetCreateInfo *info, XrActionSet *set);
XrResult XRAPI_CALL gamenative_destroy_action_set(XrActionSet set);
XrResult XRAPI_CALL gamenative_create_action(XrActionSet set, const XrActionCreateInfo *info, XrAction *action);
XrResult XRAPI_CALL gamenative_destroy_action(XrAction action);
XrResult XRAPI_CALL gamenative_suggest_bindings(XrInstance instance, const XrInteractionProfileSuggestedBinding *bindings);
XrResult XRAPI_CALL gamenative_attach_action_sets(XrSession session, const XrSessionActionSetsAttachInfo *info);
XrResult XRAPI_CALL gamenative_sync_actions(XrSession session, const XrActionsSyncInfo *info);
XrResult XRAPI_CALL gamenative_get_action_boolean(XrSession session, const XrActionStateGetInfo *info, XrActionStateBoolean *state);
XrResult XRAPI_CALL gamenative_get_action_float(XrSession session, const XrActionStateGetInfo *info, XrActionStateFloat *state);
XrResult XRAPI_CALL gamenative_get_action_vector(XrSession session, const XrActionStateGetInfo *info, XrActionStateVector2f *state);
XrResult XRAPI_CALL gamenative_get_action_pose(XrSession session, const XrActionStateGetInfo *info, XrActionStatePose *state);
XrResult XRAPI_CALL gamenative_create_action_space(XrSession session, const XrActionSpaceCreateInfo *info, XrSpace *space);
XrResult XRAPI_CALL gamenative_locate_space(XrSpace space, XrSpace base, XrTime time, XrSpaceLocation *location);
XrResult gamenative_destroy_action_space(XrSpace space);
XrResult XRAPI_CALL gamenative_enumerate_bound_sources(XrSession session, const XrBoundSourcesForActionEnumerateInfo *info, uint32_t capacity, uint32_t *count, XrPath *sources);
XrResult XRAPI_CALL gamenative_get_source_name(XrSession session, const XrInputSourceLocalizedNameGetInfo *info, uint32_t capacity, uint32_t *count, char *buffer);
XrResult XRAPI_CALL gamenative_get_interaction_profile(XrSession session, XrPath top_level, XrInteractionProfileState *state);
XrResult XRAPI_CALL gamenative_apply_haptic(XrSession session, const XrHapticActionInfo *info, const XrHapticBaseHeader *feedback);
XrResult XRAPI_CALL gamenative_stop_haptic(XrSession session, const XrHapticActionInfo *info);

#endif
