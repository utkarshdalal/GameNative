// gamenative html5 WebGL caps probe -- gated on window.__gnShimVerbose since the hoist set
// made this shim always-inject. hooks HTMLCanvasElement.prototype.getContext, fires once on
// first webgl/webgl2 context, dumps device caps as a single JSONL line via console.error
// (surfaces in logcat as E/WebViewConsole). unhooks itself after firing. used to diagnose
// WebGL-spec-floor cap issues (e.g. Adreno GLES MAX_VERTEX_UNIFORM_VECTORS=256 vs ANGLE-on-
// Vulkan 4096+). see project_alabaster_dawn_uniform_block memory note for context.
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
                    // also probe webgl2 explicitly via a side canvas so we get the full cap
                    // matrix even when the game asks for webgl1 only (e.g. C3 titles). same
                    // device, different reported caps for the GL1 vs GL2 paths in some
                    // ANGLE-on-Vulkan configs.
                    if (type !== 'webgl2' && type !== 'experimental-webgl2') {
                        try {
                            var probeCanvas = document.createElement('canvas');
                            var gl2 = probeCanvas.getContext('webgl2');
                            if (gl2) dump(gl2, 'webgl2-probe');
                        } catch (_e2) { /* ignore — webgl2 may be unsupported */ }
                    }
                    // unhook -- diagnostic is one-shot
                    window.HTMLCanvasElement.prototype.getContext = origGetContext;
                }
                return ctx;
            };
        }
    } catch (_e) { /* swallow — probe MUST NOT crash the host */ }
})();
