/**
 * v1.13 新增：Iterator Helpers polyfill。
 * 背景：@deepseek-ai/dsh-client-ui-sidebar-documentpreview 的 client.js 顶层有
 *   "function"!=typeof Iterator.prototype.join&&(Iterator.prototype.join=...)
 * 而 Iterator 是 ES2025（Chrome/WebView 122+ 才有）。老 WebView 上 Iterator 未定义
 * → 这一行直接 ReferenceError → 整个插件 import 失败 → 前端白页显示“Failed to load plugins”。
 * （用户反馈的 GitHub 最新版 bug；在 WebView ≥122 的设备上不复现。）
 * 位置：mobile.js 是 body 末尾的普通脚本，早于所有 module 脚本执行 —— 时机正确。
 * 只补全树实际用到的 Iterator.prototype.join（已扫描：仅此一处用法）。
 */
(function () {
  // 真实 %IteratorPrototype%（数组/字符串/Map/Set/生成器迭代器都继承它）
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
      out += (v === null || v === undefined) ? '' : String(v);   // 与规范一致：null/undefined 当作空串
    }
    return out;
  }

  if (iterProto && typeof iterProto.join !== 'function') iterProto.join = joinImpl;

  // 老 WebView 没有全局 Iterator：补一个占位，让 `typeof Iterator.prototype.join` 不抛
  if (typeof window.Iterator === 'undefined') {
    var It = function Iterator() { throw new TypeError('Iterator is not constructible'); };
    if (iterProto) { It.prototype = iterProto; } else { It.prototype.join = joinImpl; }
    window.Iterator = It;
  } else if (window.Iterator.prototype && typeof window.Iterator.prototype.join !== 'function') {
    window.Iterator.prototype.join = joinImpl;
  }
})();

/**
 * 移动端软键盘适配 v0.3（对应 APK v1.3.1）
 * v0.2（历史）：VisualViewport + translateY 方案，竖屏横屏通用，
 *   rAF 节流 + 异常保护，暴露 --kb-height 供 CSS 使用。
 * v0.3 新增（v1.3.1）：键盘防自动聚焦 —— 用 pointerdown 位置判断焦点来源，
 *   切换话题/新会话自动聚焦输入框时立即 blur（不弹键盘），
 *   只有用户真的点击输入框才弹键盘。
 * 注：窄屏侧栏改造（三条杠 + 浮层）在核心源码 dsh-client-ui-layout，不在此文件。
 */
(function () {
  if (!window.visualViewport) return;
  var vv = window.visualViewport;
  var app = document.getElementById('root') || document.body;
  var lastKb = 0;
  var rafId = 0;
  var raf = window.requestAnimationFrame || function (fn) { return setTimeout(fn, 16); };

  function computeKb() {
    // 键盘高度 ≈ 布局视口高度 - 视觉视口高度 - 视觉视口顶部偏移
    var kb = window.innerHeight - vv.height - vv.offsetTop;
    return Math.max(0, Math.round(kb));
  }

  function apply() {
    var kb = computeKb();
    if (Math.abs(kb - lastKb) < 6) return;
    lastKb = kb;
    document.documentElement.style.setProperty('--kb-height', kb + 'px');
    if (!app) return;
    if (kb > 120) {
      // 键盘弹出：把 App 容器向上平移，露出底部输入栏
      app.style.transform = 'translateY(' + (-kb) + 'px)';
      app.style.transition = 'transform 0.12s ease-out';
      document.documentElement.classList.add('kb-open');
    } else {
      // 键盘收起：恢复原位
      app.style.transform = '';
      app.style.transition = 'transform 0.12s ease-out';
      document.documentElement.classList.remove('kb-open');
    }
  }

  function schedule() {
    if (rafId) return;
    rafId = raf(function () {
      rafId = 0;
      apply();
    });
  }

  vv.addEventListener('resize', schedule);
  vv.addEventListener('scroll', schedule);
  window.addEventListener('resize', schedule);
  window.addEventListener('orientationchange', function () {
    // 旋转后等布局稳定再算一次
    setTimeout(schedule, 200);
  });
  // 记录用户最后一次真实点击（pointerdown）位置，用于区分
  // 「用户主动点击输入框」与「程序化聚焦」（如切换新话题后输入框自动 focus）
  var lastPointer = null;
  document.addEventListener('pointerdown', function (e) {
    lastPointer = { x: e.clientX, y: e.clientY, t: Date.now() };
  }, true);

  function isInputLike(t) {
    return t && (t.tagName === 'INPUT' || t.tagName === 'TEXTAREA' || t.isContentEditable);
  }

  document.addEventListener('focusin', function (e) {
    var t = e && e.target;
    if (!isInputLike(t)) return;
    // 判断这次聚焦是否由用户直接点击该输入框产生
    var userTapped = false;
    if (lastPointer && Date.now() - lastPointer.t < 800) {
      var r = t.getBoundingClientRect();
      userTapped = r.left <= lastPointer.x && lastPointer.x <= r.right &&
                   r.top <= lastPointer.y && lastPointer.y <= r.bottom;
    }
    if (!userTapped) {
      // 程序化聚焦（切换话题/新会话自动 focus）：立即失焦，避免键盘自动弹出
      setTimeout(function () {
        if (document.activeElement === t) t.blur();
      }, 0);
      return;
    }
    setTimeout(schedule, 150);
  });
  document.addEventListener('focusout', function (e) {
    if (isInputLike(e && e.target)) setTimeout(schedule, 150);
  });

  // 初始计算（等待首帧布局稳定）
  setTimeout(schedule, 100);
})();

// 注：插件按钮已改为核心实现（dsh-client-ui-cordis 注册到
// conversation.session.header.utilities，单一实例），由核心渲染；
// 位置用 mobile.css 的 position:fixed 挪到三条杠下方（不动 DOM，
// 保证 React 事件委托有效）。

/**
 * 手机端回车行为 v0.4——**回车换行，Ctrl/Cmd+回车 发送**
 *
 * 问题（用户反馈）：手机软键盘上按回车 = 直接把消息发出去，换不了行。
 * 成因：DSH 前端 composer 的键盘映射（dsh-client-ui-conversation 的
 *   registerComposerKeymap）对 Enter 命令只在 event.shiftKey === true 时
 *   放行给编辑器做换行，其余情况一律 handlers.submit()。实体键盘有 Shift，
 *   手机软键盘没有 —— 所以手机上永远触发不了换行。
 *
 * 做法：**不改内核代码**（保持 mobile-patch “只注入、不覆盖原生”的原则）。
 *   在 document 的捕获阶段拦下裸 Enter（仅限聊天输入栏 [data-composer-card]
 *   内、且不是输入法组词中），阻止默认行为与冒泡（=> Lexical 收不到“发送”），
 *   再补发一个 shiftKey=true 的合成 keydown：让编辑器走它自己本来就有的
 *   「Shift+Enter = 换行」路径（不自己拼 DOM / 不直接改 React 状态，
 *   避免受控组件状态脱节）。合成事件打 __dshSynthEnter 标记避免自拦截。
 *   万一 KeyboardEvent 不可用，退化用 execCommand('insertLineBreak')。
 *
 * 保留：Ctrl/Cmd + Enter 仍是原语义（发送/插队加速）；右下角发送按钮照常可用。
 */
(function () {
  var SYNTH_FLAG = '__dshSynthEnter';

  function inComposer(t) {
    try {
      // data-composer-card 是 composer 卡片的稳定标记（占位符 data-composer-placeholder
      // 只在草稿为空时存在，不能用来定位输入栏）
      return !!(t && t.closest && t.closest('[data-composer-card]'));
    } catch (e) { return false; }
  }

  document.addEventListener('keydown', function (e) {
    if (e[SYNTH_FLAG]) return;                                  // 自己补发的事件：放行
    if (e.key !== 'Enter' && e.keyCode !== 13) return;
    if (e.isComposing || e.keyCode === 229) return;              // 输入法组词中的回车：交给输入法
    if (e.shiftKey || e.ctrlKey || e.metaKey || e.altKey) return; // 组合键保持原语义
    var t = e.target;
    if (!t || !t.isContentEditable || !inComposer(t)) return;    // 只管聊天输入栏
    e.preventDefault();
    e.stopPropagation();                                        // 拦住 Lexical 的“回车即发送”
    try {
      var ev = new KeyboardEvent('keydown', {
        key: 'Enter', code: 'Enter', keyCode: 13, which: 13,
        shiftKey: true, bubbles: true, cancelable: true
      });
      ev[SYNTH_FLAG] = true;
      t.dispatchEvent(ev);
    } catch (err) {
      try { document.execCommand('insertLineBreak'); } catch (e2) {}
    }
  }, true);
})();

/**
 * 禁止网页被双指缩放 v0.5
 *
 * 症状：**预览窗存在时**双指捏合会把整个对话页缩放；
 * 没有预览窗时又缩不动 —— 行为不一致，是 bug。
 *
 * 成因：DSH 的 index.html viewport 只写了 `width=device-width, initial-scale=1`，
 *   **没有** `user-scalable=no` / `maximum-scale`；而 WebView 侧的
 *   `setSupportZoom(false)` 在现代 WebView 上并不能可靠地禁掉 pinch-zoom
 *   （viewport 声明才是权威）。预览窗出现时 WebView 会重排，于是"有时能缩"被暴露出来。
 *
 * 做法（两层，都不改内核代码）：
 *   ① inject.sh 给 viewport 补 `maximum-scale=1.0, user-scalable=no`（权威手段）；
 *   ② 这里再兜一道：捕获阶段拦 ≥2 指的 touchmove / gesture*，preventDefault 掉，
 *      Chrome 就不会把它当翻页缩放。（touchmove 必须 passive:false 才拦得住）
 *
 * 注意：虚拟屏预览窗是**独立原生窗口**，不走网页事件 —— 它的双指缩放不受影响。
 */
(function () {
  function blockMulti(e) {
    if (e && e.touches && e.touches.length > 1 && e.cancelable) e.preventDefault();
  }
  document.addEventListener('touchmove', blockMulti, { passive: false, capture: true });
  ['gesturestart', 'gesturechange', 'gestureend'].forEach(function (n) {
    document.addEventListener(n, function (e) { if (e && e.cancelable) e.preventDefault(); },
      { passive: false, capture: true });
  });
})();
