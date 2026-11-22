// device half of an html5 debug run, run once in a small page of its own. reports a single JSON object via
// __gnDiagBridge.report(); every section is fail-soft so one broken API can't blank the report. static facts
// only: this page shares the renderer with the game, so nothing here may measure or block for long.
(function () {
    'use strict';

    function safe(fn) {
        try { return fn(); } catch (e) { return 'error: ' + e; }
    }

    function withTimeout(promise, ms) {
        return Promise.race([
            promise,
            new Promise(function (resolve) { setTimeout(function () { resolve('timeout'); }, ms); }),
        ]).catch(function (e) { return 'error: ' + e; });
    }

    // 10 = fp16, 23 = fp32. mobile GPUs honor mediump, desktop ones silently run it at fp32.
    function precision(gl, shaderType, precType) {
        var f = gl.getShaderPrecisionFormat(shaderType, precType);
        return f ? f.precision : null;
    }

    function loseContext(gl) {
        try { var lc = gl.getExtension('WEBGL_lose_context'); if (lc) lc.loseContext(); } catch (_e) {}
    }

    // true = chromium itself flags this GPU path as slow (software / blocklisted driver).
    function perfCaveat(type) {
        var gl = document.createElement('canvas').getContext(type, { failIfMajorPerformanceCaveat: true });
        if (!gl) return true;
        loseContext(gl);
        return false;
    }

    function gpu() {
        var type = 'webgl2';
        var gl = document.createElement('canvas').getContext('webgl2');
        if (!gl) { type = 'webgl'; gl = document.createElement('canvas').getContext('webgl'); }
        if (!gl) return { error: 'no webgl context' };
        try {
            var dbg = gl.getExtension('WEBGL_debug_renderer_info');
            var out = {
                type: type,
                version: gl.getParameter(gl.VERSION),
                glsl: gl.getParameter(gl.SHADING_LANGUAGE_VERSION),
                vendor: dbg ? gl.getParameter(dbg.UNMASKED_VENDOR_WEBGL) : gl.getParameter(gl.VENDOR),
                renderer: dbg ? gl.getParameter(dbg.UNMASKED_RENDERER_WEBGL) : gl.getParameter(gl.RENDERER),
                perfCaveatWebgl2: safe(function () { return perfCaveat('webgl2'); }),
                perfCaveatWebgl1: safe(function () { return perfCaveat('webgl'); }),
                fragMediumpBits: precision(gl, gl.FRAGMENT_SHADER, gl.MEDIUM_FLOAT),
                fragHighpBits: precision(gl, gl.FRAGMENT_SHADER, gl.HIGH_FLOAT),
                vertMediumpBits: precision(gl, gl.VERTEX_SHADER, gl.MEDIUM_FLOAT),
                maxTextureSize: gl.getParameter(gl.MAX_TEXTURE_SIZE),
                maxRenderbufferSize: gl.getParameter(gl.MAX_RENDERBUFFER_SIZE),
                maxViewportDims: Array.prototype.slice.call(gl.getParameter(gl.MAX_VIEWPORT_DIMS) || []),
                maxVertexUniformVectors: gl.getParameter(gl.MAX_VERTEX_UNIFORM_VECTORS),
                maxFragmentUniformVectors: gl.getParameter(gl.MAX_FRAGMENT_UNIFORM_VECTORS),
                maxVaryingVectors: gl.getParameter(gl.MAX_VARYING_VECTORS),
                maxVertexAttribs: gl.getParameter(gl.MAX_VERTEX_ATTRIBS),
                maxTextureImageUnits: gl.getParameter(gl.MAX_TEXTURE_IMAGE_UNITS),
                maxCombinedTextureImageUnits: gl.getParameter(gl.MAX_COMBINED_TEXTURE_IMAGE_UNITS),
            };
            if (type === 'webgl2') {
                out.maxSamples = gl.getParameter(gl.MAX_SAMPLES);
                out.maxDrawBuffers = gl.getParameter(gl.MAX_DRAW_BUFFERS);
                out.maxUniformBlockSize = gl.getParameter(gl.MAX_UNIFORM_BLOCK_SIZE);
                out.maxCombinedUniformBlocks = gl.getParameter(gl.MAX_COMBINED_UNIFORM_BLOCKS);
            }
            out.extensions = gl.getSupportedExtensions() || [];
            return out;
        } finally {
            loseContext(gl);
        }
    }

    function env() {
        return {
            userAgent: navigator.userAgent,
            devicePixelRatio: window.devicePixelRatio,
            screen: [screen.width, screen.height],
            inner: [window.innerWidth, window.innerHeight],
            hardwareConcurrency: navigator.hardwareConcurrency,
            deviceMemoryGb: navigator.deviceMemory,
            jsHeapLimitMb: performance.memory ? Math.round(performance.memory.jsHeapSizeLimit / 1048576) : null,
        };
    }

    function media() {
        var v = document.createElement('video');
        var a = document.createElement('audio');
        // canPlayType answers "", "maybe" or "probably"; "" = unsupported.
        function c(el, t) { return el.canPlayType(t) || 'no'; }
        return {
            vp8: c(v, 'video/webm; codecs="vp8"'),
            vp9: c(v, 'video/webm; codecs="vp9"'),
            av1: c(v, 'video/mp4; codecs="av01.0.05M.08"'),
            h264: c(v, 'video/mp4; codecs="avc1.42E01E"'),
            theora: c(v, 'video/ogg; codecs="theora"'),
            vorbis: c(a, 'audio/ogg; codecs="vorbis"'),
            opus: c(a, 'audio/ogg; codecs="opus"'),
            aac: c(a, 'audio/mp4; codecs="mp4a.40.2"'),
            mp3: c(a, 'audio/mpeg'),
            wav: c(a, 'audio/wav'),
        };
    }

    function audio() {
        var Ctx = window.AudioContext || window.webkitAudioContext;
        if (!Ctx) return { error: 'no AudioContext' };
        var ctx = new Ctx();
        // outputLatency stays 0 while the context is suspended (no user gesture here), so it's indicative only.
        var out = { sampleRate: ctx.sampleRate, baseLatencyMs: Math.round((ctx.baseLatency || 0) * 10000) / 10,
            outputLatencyMs: Math.round((ctx.outputLatency || 0) * 10000) / 10, state: ctx.state };
        try { ctx.close(); } catch (_e) {}
        return out;
    }

    // minimal modules from wasm-feature-detect.
    var WASM_SIMD = [0, 97, 115, 109, 1, 0, 0, 0, 1, 5, 1, 96, 0, 1, 123, 3, 2, 1, 0, 10, 10, 1, 8, 0, 65, 0, 253, 15, 253, 98, 11];
    var WASM_THREADS = [0, 97, 115, 109, 1, 0, 0, 0, 1, 4, 1, 96, 0, 0, 3, 2, 1, 0, 5, 4, 1, 3, 1, 1, 10, 11, 1, 9, 0, 65, 0, 254, 16, 2, 0, 26, 11];

    function features() {
        var hasWasm = typeof WebAssembly === 'object';
        return {
            wasm: hasWasm,
            wasmSimd: hasWasm ? safe(function () { return WebAssembly.validate(new Uint8Array(WASM_SIMD)); }) : false,
            wasmThreads: hasWasm ? safe(function () { return WebAssembly.validate(new Uint8Array(WASM_THREADS)); }) : false,
            sharedArrayBuffer: typeof SharedArrayBuffer !== 'undefined',
            crossOriginIsolated: !!window.crossOriginIsolated,
            opfs: !!(navigator.storage && navigator.storage.getDirectory),
            opfsSyncAccessHandle: typeof FileSystemSyncAccessHandle !== 'undefined',
            offscreenCanvas: typeof OffscreenCanvas !== 'undefined',
            webCodecs: typeof VideoDecoder !== 'undefined',
            webgpu: !!navigator.gpu,
            gamepadApi: typeof navigator.getGamepads === 'function',
        };
    }

    function webgpuAdapter() {
        if (!navigator.gpu) return Promise.resolve(null);
        return withTimeout(navigator.gpu.requestAdapter().then(function (ad) {
            if (!ad) return 'no adapter';
            var i = ad.info || {};
            return { vendor: i.vendor, architecture: i.architecture, device: i.device, description: i.description };
        }), 3000);
    }

    function storage() {
        if (!navigator.storage || !navigator.storage.estimate) return Promise.resolve(null);
        return withTimeout(navigator.storage.estimate().then(function (e) {
            return { quotaMb: Math.round(e.quota / 1048576), usageMb: Math.round(e.usage / 1048576) };
        }), 3000);
    }

    function report(obj) {
        try { __gnDiagBridge.report(JSON.stringify(obj)); } catch (e) { console.warn('[GN-DIAG] report failed: ' + e); }
    }

    var result = {
        env: safe(env),
        gpu: safe(gpu),
        features: safe(features),
        media: safe(media),
        audio: safe(audio),
    };
    Promise.all([webgpuAdapter(), storage()]).then(function (rest) {
        result.webgpuAdapter = rest[0];
        result.storage = rest[1];
        report(result);
    }).catch(function (e) {
        result.error = String(e);
        report(result);
    });
})();
