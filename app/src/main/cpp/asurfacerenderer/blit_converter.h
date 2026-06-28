#ifndef BLIT_CONVERTER_H
#define BLIT_CONVERTER_H

#include <android/hardware_buffer.h>
#include <EGL/egl.h>
#include <EGL/eglext.h>
#include <GLES3/gl3.h>
#include <GLES2/gl2ext.h>

class BlitConverter {
public:
    BlitConverter();
    ~BlitConverter();

    // Convert BGRA buffer to RGBA buffer using GPU
    // Returns fence FD for synchronization, or -1 on error
    int convertBGRAtoRGBA(AHardwareBuffer* srcBGRA, AHardwareBuffer* dstRGBA, int srcFenceFd);

private:
    bool initializeGL();
    void cleanup();

    EGLDisplay display;
    EGLContext context;
    EGLSurface surface;

    GLuint program;
    GLuint vao;
    GLuint vbo;
    GLuint fbo;

    bool initialized;
};

#endif // BLIT_CONVERTER_H
