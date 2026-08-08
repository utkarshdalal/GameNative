// WebGL FBO compat shim for pack:electron / Pixi-style renderers on Android WebView.

// Two defensive fixes; both apply to WebGL1 and WebGL2 contexts since both can hit them.

// 1) Post-attach storage refresh (the load-bearing one).
// Pixi-style code does:
// gl.bindFramebuffer(GL_FRAMEBUFFER, fbo);
// gl.framebufferTexture2D(..., COLOR_ATTACHMENT0, TEXTURE_2D, tex, 0); // tex has no storage yet
// gl.bindTexture(TEXTURE_2D, tex);
// gl.texImage2D(..., w, h, ...); // allocate storage
// Per WebGL spec, the FBO becomes complete on next checkFramebufferStatus / draw.
// On Adreno + ANGLE in WebView 109 the completeness state is cached at attach time and
// NEVER re-validates after the texture gains storage. Result: every draw fails with
// GL_INVALID_FRAMEBUFFER_OPERATION → black render target → black game world.
// Workaround: every framebufferTexture2D records (fbo, attachment, level) per-texture.
// Every texImage2D / texStorage2D on a recorded texture replays a detach+reattach on
// each FBO that holds it, forcing the driver to re-evaluate completeness.

// 2) NPOT mipmap-completeness widening. WebGL1 NPOT textures default to MIN_FILTER=
// NEAREST_MIPMAP_LINEAR + WRAP=REPEAT, both of which make NPOT FBO color attachments
// texture-incomplete. Defensive: when attaching a color tex whose MIN_FILTER is still
// at a default mipmap mode, set MIN_FILTER=LINEAR + WRAP=CLAMP_TO_EDGE. Skips
// textures that have already been explicitly set (preserves NEAREST/LINEAR choices).
// Wayward set its params correctly so this is a no-op for it; kept for other games.

// Future: if other engines hit different FBO-completeness quirks, extend with more
// wrappers (renderbufferStorage post-attach refresh, texSubImage2D growth, etc.).
// Diagnostic version with full GL state + getError tracking lives in git history at
// commit (search for "gn-webgl-diag"); restore if a third edge case shows up.
(function () {
  if (window.__gnWebGLFboCompat) return;
  window.__gnWebGLFboCompat = true;

  var TAG = '[gn-webgl-fbo-compat]';

  function instrument(gl) {
    if (gl.__gnFboCompat) return;
    gl.__gnFboCompat = true;

    // tex → array of {fbo, attachment, level}. WeakMap so dead textures GC cleanly.
    var texAttachments = new WeakMap();

    var origFbTex = gl.framebufferTexture2D.bind(gl);
    var origTI = gl.texImage2D.bind(gl);
    var origTS = gl.texStorage2D ? gl.texStorage2D.bind(gl) : null;

    function refresh(tex) {
      var atts = texAttachments.get(tex);
      if (!atts || !atts.length) return;
      try {
        var prevFbo = gl.getParameter(gl.FRAMEBUFFER_BINDING);
        for (var i = 0; i < atts.length; i++) {
          var a = atts[i];
          gl.bindFramebuffer(gl.FRAMEBUFFER, a.fbo);
          // detach + reattach forces ANGLE to re-eval FBO completeness now that the
          // texture has storage. plain re-attach without intermediate null doesn't
          // bust the cache on the affected drivers.
          origFbTex(gl.FRAMEBUFFER, a.attachment, gl.TEXTURE_2D, null, 0);
          origFbTex(gl.FRAMEBUFFER, a.attachment, gl.TEXTURE_2D, tex, a.level);
        }
        gl.bindFramebuffer(gl.FRAMEBUFFER, prevFbo);
      } catch (e) { /* best-effort, never break the underlying call */ }
    }

    gl.framebufferTexture2D = function (target, attachment, texTarget, texture, level) {
      if (texture && texTarget === gl.TEXTURE_2D) {
        var isColor = (typeof gl.COLOR_ATTACHMENT0 === 'number') &&
          attachment >= gl.COLOR_ATTACHMENT0 && attachment < gl.COLOR_ATTACHMENT0 + 16;

        // (1) record (fbo, attachment, level) for post-attach refresh.
        var curFbo = gl.getParameter(gl.FRAMEBUFFER_BINDING);
        if (curFbo) {
          var arr = texAttachments.get(texture) || [];
          var nxt = [];
          for (var k = 0; k < arr.length; k++) {
            if (!(arr[k].fbo === curFbo && arr[k].attachment === attachment)) nxt.push(arr[k]);
          }
          nxt.push({ fbo: curFbo, attachment: attachment, level: level });
          texAttachments.set(texture, nxt);
        }

        // (2) defensive NPOT widening -- only on color attachments, only when params
        // are still at default mipmap MIN_FILTER (i.e. game never set them).
        if (isColor) {
          try {
            var prev = gl.getParameter(gl.TEXTURE_BINDING_2D);
            gl.bindTexture(gl.TEXTURE_2D, texture);
            var min = gl.getTexParameter(gl.TEXTURE_2D, gl.TEXTURE_MIN_FILTER);
            var ws = gl.getTexParameter(gl.TEXTURE_2D, gl.TEXTURE_WRAP_S);
            var wt = gl.getTexParameter(gl.TEXTURE_2D, gl.TEXTURE_WRAP_T);
            // 0x2700..0x2703 = the four mipmap MIN_FILTER modes. default is 0x2702.
            if (min >= 0x2700 && min <= 0x2703) gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MIN_FILTER, gl.LINEAR);
            // 0x8370 = MIRRORED_REPEAT.
            if (ws === gl.REPEAT || ws === 0x8370) gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_S, gl.CLAMP_TO_EDGE);
            if (wt === gl.REPEAT || wt === 0x8370) gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_T, gl.CLAMP_TO_EDGE);
            gl.bindTexture(gl.TEXTURE_2D, prev);
          } catch (e) { /* best-effort */ }
        }
      }
      return origFbTex(target, attachment, texTarget, texture, level);
    };

    gl.texImage2D = function () {
      var a = arguments;
      var r = origTI.apply(gl, a);
      // only level-0 sized writes are FBO-relevant.
      if (a[1] === 0) {
        var bound = gl.getParameter(gl.TEXTURE_BINDING_2D);
        if (bound) refresh(bound);
      }
      return r;
    };

    if (origTS) {
      gl.texStorage2D = function (t, l, ifmt, w, h) {
        var r = origTS(t, l, ifmt, w, h);
        var bound = gl.getParameter(gl.TEXTURE_BINDING_2D);
        if (bound) refresh(bound);
        return r;
      };
    }
  }

  function patch(P) {
    var orig = P.prototype.getContext;
    P.prototype.getContext = function (type) {
      var ctx = orig.apply(this, arguments);
      if (ctx && (type === 'webgl' || type === 'webgl2' || type === 'experimental-webgl')) {
        try { instrument(ctx); } catch (e) { console.warn(TAG + ' instrument failed', e); }
      }
      return ctx;
    };
  }

  patch(HTMLCanvasElement);
  if (typeof OffscreenCanvas !== 'undefined') patch(OffscreenCanvas);
  console.info(TAG + ' installed');
})();
