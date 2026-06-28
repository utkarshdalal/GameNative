#ifndef COMPUTE_CONVERTER_H
#define COMPUTE_CONVERTER_H

#include <android/hardware_buffer.h>
#include <EGL/egl.h>
#include <EGL/eglext.h>
#include <GLES3/gl31.h>
#include <GLES2/gl2ext.h>

class ComputeConverter {
public:
    ComputeConverter();
    ~ComputeConverter();

    // Convert BGRA buffer to RGBA buffer using compute shader
    // Returns fence FD for synchronization, or -1 on error
    int convertBGRAtoRGBA(AHardwareBuffer* srcBGRA, AHardwareBuffer* dstRGBA, int srcFenceFd);

private:
    bool initializeGL();
    void cleanup();

    EGLDisplay display;
    EGLContext context;
    EGLSurface surface;

    GLuint computeProgram;

    bool initialized;
};

#endif // COMPUTE_CONVERTER_H
