#pragma once

#include "Drivers/Backend.h"

namespace DrvGameNative {

// Creates the GameNative OpenVR backend. OpenComposite keeps its mature
// OpenXR-backed input compatibility session, while all eye images bypass the
// Windows OpenXR compositor and are submitted to GameNative's Wine unixlib.
IBackend* CreateGameNativeBackend(const char* startupInfo);

} // namespace DrvGameNative
