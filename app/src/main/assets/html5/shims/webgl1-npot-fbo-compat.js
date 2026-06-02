// FBO completeness workarounds for Pixi-style renderers on Android WebView (WebGL1 and WebGL2).
//
// 1) post-attach refresh (the load-bearing one). Pixi attaches a texture to an FBO BEFORE allocating its
// storage with texImage2D. per spec the FBO becomes complete at the next draw, but Adreno + ANGLE in WebView 109
// caches completeness at attach time and never re-validates, so every draw fails with
// GL_INVALID_FRAMEBUFFER_OPERATION and the game renders black. fix: remember each texture's FBO attachments and
// detach+reattach them whenever the texture gets storage.
//
// 2) NPOT widening. WebGL1 NPOT textures default to a mipmap MIN_FILTER and REPEAT wrap, both of which make them
// incomplete as color attachments. switch such textures to LINEAR + CLAMP_TO_EDGE unless the game set them.
(function () {
  if (window.__gnWebGLFboCompat) return;
  window.__gnWebGLFboCompat = true;

  var TAG = '[gn-webgl-fbo-compat]';

  function instrument(gl) {
    if (gl.__gnFboCompat) return;
    gl.__gnFboCompat = true;

    // tex -> [{fbo, attachment, level}]
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
          // a plain re-attach without the intermediate null doesn't bust the cache on affected drivers.
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
