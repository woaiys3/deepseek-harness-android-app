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
  // AbortSignal.any 是 Chromium 116+ 的新 API（2023 年 9 月）；老 WebView（尤其兼容版目标
  // Chromium 51）没有它。某些依赖库（如 fetch 聚合、CancelSignal 组合）会用到，缺失会导致
  // 工作区/文件选择器等 UI 白屏 + 控制台报 "AbortSignal.any is not a function"。
  // 最小 polyfill：把任意个 AbortSignal 聚合成一个，任一中止则聚合也中止。
  if (typeof AbortSignal !== 'undefined' && typeof AbortSignal.any !== 'function') {
    AbortSignal.any = function (signals) {
      var ctrl = new AbortController();
      function onAbort() { ctrl.abort(); }
      (signals || []).forEach(function (sig) {
        if (!sig) return;
        if (sig.aborted) { ctrl.abort(); return; }
        sig.addEventListener('abort', onAbort, { once: true });
      });
      return ctrl.signal;
    };
  }
  // 前置基本防护：AbortSignal 本身老 WebView 可能没有（极老内核）。
  if (typeof AbortController === 'undefined') {
    window.AbortController = function () {
      var _this = this;
      this.signal = { aborted: false, _listeners: [] };
      this.abort = function () { _this.signal.aborted = true; _this.signal._listeners.forEach(function (f) { f(); }); };
      this.signal.addEventListener = function (ev, fn) { _this.signal._listeners.push(fn); };
      this.signal.removeEventListener = function () {};
    };
  }

  // v1.13 新增：Iterator Helpers（ES2025；Chrome/WebView 122+ 才有）。
  // @deepseek-ai/dsh-client-ui-sidebar-documentpreview 的 client.js 顶层直接读
  // `typeof Iterator.prototype.join` —— 老 WebView 上 Iterator 未定义 → ReferenceError
  // → 插件 import 失败 → “Failed to load plugins” 白页。已扫描全树：仅此一处 Iterator 用法。
  (function () {
    var iterProto = null;
    try {
      if (typeof Symbol !== 'undefined' && Symbol.iterator) {
        iterProto = Object.getPrototypeOf(Object.getPrototypeOf([][Symbol.iterator]()));
      }
    } catch (e) { iterProto = null; }

    function joinImpl(sep) {
      sep = (sep === undefined) ? ',' : String(sep);
      if (this == null || typeof this.next !== 'function') {
        throw new TypeError('Iterator.prototype.join called on incompatible receiver');
      }
      var out = '', first = true, step;
      while (!(step = this.next()).done) {
        if (!first) out += sep;
        first = false;
        var v = step.value;
        out += (v === null || v === undefined) ? '' : String(v);
      }
      return out;
    }

    if (iterProto && typeof iterProto.join !== 'function') iterProto.join = joinImpl;

    if (typeof window.Iterator === 'undefined') {
      var It = function Iterator() { throw new TypeError('Iterator is not constructible'); };
      if (iterProto) { It.prototype = iterProto; } else { It.prototype.join = joinImpl; }
      window.Iterator = It;
    } else if (window.Iterator.prototype && typeof window.Iterator.prototype.join !== 'function') {
      window.Iterator.prototype.join = joinImpl;
    }
  })();
})();
