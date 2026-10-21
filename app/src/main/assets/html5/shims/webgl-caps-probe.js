// verbose-only diagnostic: on the first webgl context, logs device caps as one JSON line via console.error
// (visible in logcat as E/WebViewConsole). for diagnosing cap-floor issues, e.g. Adreno GLES
// MAX_VERTEX_UNIFORM_VECTORS=256 vs 4096+ under ANGLE-on-Vulkan.
(function () {
    'use strict';
    if (!self.__gnShimVerbose) return;
    if (window.__gnWebglCapsProbeFired) return;

    function dump(ctx, type) {
        try {
            var dbg = ctx.getExtension && ctx.getExtension('WEBGL_debug_renderer_info');
            var ven = dbg ? ctx.getParameter(dbg.UNMASKED_VENDOR_WEBGL) : ctx.getParameter(ctx.VENDOR);
            var ren = dbg ? ctx.getParameter(dbg.UNMASKED_RENDERER_WEBGL) : ctx.getParameter(ctx.RENDERER);
            var caps = {
                type: type,
                ver: ctx.getParameter(ctx.VERSION),
                sl: ctx.getParameter(ctx.SHADING_LANGUAGE_VERSION),
                vendor: ven, renderer: ren,
                maxVertexUniformVectors: ctx.getParameter(ctx.MAX_VERTEX_UNIFORM_VECTORS),
                maxFragmentUniformVectors: ctx.getParameter(ctx.MAX_FRAGMENT_UNIFORM_VECTORS),
                maxVaryingVectors: ctx.getParameter(ctx.MAX_VARYING_VECTORS),
                maxVertexAttribs: ctx.getParameter(ctx.MAX_VERTEX_ATTRIBS),
                maxTextureImageUnits: ctx.getParameter(ctx.MAX_TEXTURE_IMAGE_UNITS),
                maxVertexTextureImageUnits: ctx.getParameter(ctx.MAX_VERTEX_TEXTURE_IMAGE_UNITS),
                maxCombinedTextureImageUnits: ctx.getParameter(ctx.MAX_COMBINED_TEXTURE_IMAGE_UNITS),
                maxTextureSize: ctx.getParameter(ctx.MAX_TEXTURE_SIZE),
            };
            if (type === 'webgl2' && ctx.MAX_VERTEX_UNIFORM_COMPONENTS) {
                caps.maxVertexUniformComponents = ctx.getParameter(ctx.MAX_VERTEX_UNIFORM_COMPONENTS);
                caps.maxFragmentUniformComponents = ctx.getParameter(ctx.MAX_FRAGMENT_UNIFORM_COMPONENTS);
                caps.maxUniformBlockSize = ctx.getParameter(ctx.MAX_UNIFORM_BLOCK_SIZE);
                caps.maxVertexUniformBlocks = ctx.getParameter(ctx.MAX_VERTEX_UNIFORM_BLOCKS);
                caps.maxFragmentUniformBlocks = ctx.getParameter(ctx.MAX_FRAGMENT_UNIFORM_BLOCKS);
                caps.maxCombinedUniformBlocks = ctx.getParameter(ctx.MAX_COMBINED_UNIFORM_BLOCKS);
                caps.uniformBufferOffsetAlignment = ctx.getParameter(ctx.UNIFORM_BUFFER_OFFSET_ALIGNMENT);
            }
            console.error('[GN-WEBGL-CAPS] ' + JSON.stringify(caps));
        } catch (e) { console.error('[GN-WEBGL-CAPS] probe failed: ' + e); }
    }

    try {
        if (window.HTMLCanvasElement && window.HTMLCanvasElement.prototype) {
            var origGetContext = window.HTMLCanvasElement.prototype.getContext;
            window.HTMLCanvasElement.prototype.getContext = function (type) {
                var ctx = origGetContext.apply(this, arguments);
                if (!window.__gnWebglCapsProbeFired && ctx &&
                    (type === 'webgl2' || type === 'webgl' ||
                     type === 'experimental-webgl' || type === 'experimental-webgl2')) {
                    window.__gnWebglCapsProbeFired = true;
                    dump(ctx, type);
                    // some ANGLE-on-Vulkan configs report different caps for GL1 vs GL2, so probe webgl2 too.
                    if (type !== 'webgl2' && type !== 'experimental-webgl2') {
                        try {
                            var probeCanvas = document.createElement('canvas');
                            var gl2 = probeCanvas.getContext('webgl2');
                            if (gl2) dump(gl2, 'webgl2-probe');
                        } catch (_e2) { /* webgl2 unsupported */ }
                    }
                    window.HTMLCanvasElement.prototype.getContext = origGetContext;
                }
                return ctx;
            };
        }
    } catch (_e) { /* probe MUST NOT crash the page */ }
})();
