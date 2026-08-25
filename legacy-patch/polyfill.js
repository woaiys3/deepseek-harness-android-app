// legacy 兼容 polyfill：老 WebView（Chromium < 80）缺的运行时 API
// 在 esbuild bundle 前注入（banner），只补实际用到的 API，最小化
(function () {
  'use strict';
  if (!window.globalThis) window.globalThis = window;
  if (!Object.fromEntries) {
    Object.fromEntries = function (es) {
      var o = {};
      for (var i = 0; i < es.length; i++) { var e = es[i]; o[e[0]] = e[1]; }
      return o;
    };
  }
  if (!Promise.allSettled) {
    Promise.allSettled = function (ps) {
      return Promise.all(ps.map(function (p) {
        return Promise.resolve(p).then(
          function (v) { return { status: 'fulfilled', value: v }; },
          function (r) { return { status: 'rejected', reason: r }; }
        );
      }));
    };
  }
  if (!window.queueMicrotask) {
    window.queueMicrotask = function (fn) { Promise.resolve().then(fn); };
  }
  if (!Array.prototype.at) {
    Array.prototype.at = function (i) {
      var n = +i || 0, l = this.length >>> 0;
      var k = n < 0 ? Math.max(l + n, 0) : Math.min(n, l);
      return k < 0 || k >= l ? undefined : this[k];
    };
  }
  if (!String.prototype.at) {
    String.prototype.at = function (i) {
      var n = +i || 0, l = this.length >>> 0;
      var k = n < 0 ? Math.max(l + n, 0) : Math.min(n, l);
      return k < 0 || k >= l ? undefined : this.charAt(k);
    };
  }
})();
