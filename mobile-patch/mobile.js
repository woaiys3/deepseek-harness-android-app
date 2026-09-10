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
  try {
    var meta = document.querySelector('meta[name="viewport"]');
    if (!meta) {
      meta = document.createElement('meta');
      meta.name = 'viewport';
      if (document.head) document.head.appendChild(meta);
    }
    meta.content = 'width=device-width, initial-scale=1, maximum-scale=1, viewport-fit=cover';
    document.documentElement.classList.add('dsh-android-webview');
    function fitPhoneWidth() {
      var root = document.getElementById('root') || document.body;
      if (!root) return;
      root.style.minWidth = '0';
      root.style.width = '100%';
      root.style.maxWidth = '100vw';
      document.documentElement.style.maxWidth = '100vw';
      document.body && (document.body.style.maxWidth = '100vw');
    }
    fitPhoneWidth();
    setTimeout(fitPhoneWidth, 200);
    setTimeout(fitPhoneWidth, 800);
  } catch (e) {}
})();

(function () {
  function isDarkTheme() {
    var el = document.documentElement;
    if (!el) return false;
    if (el.hasAttribute('data-ds-dark-theme')) return true;
    if (el.getAttribute('data-theme') === 'dark') return true;
    if (el.classList.contains('dark')) return true;
    if (el.hasAttribute('data-ds-light-theme')) return false;
    if (el.getAttribute('data-theme') === 'light') return false;
    try {
      return !!(window.matchMedia && window.matchMedia('(prefers-color-scheme: dark)').matches);
    } catch (e) {
      return false;
    }
  }
  function notifyAndroidBars() {
    try {
      if (window.DshAndroid && window.DshAndroid.onThemeChanged) {
        window.DshAndroid.onThemeChanged(isDarkTheme());
      }
    } catch (e) {}
  }
  notifyAndroidBars();
  setTimeout(notifyAndroidBars, 300);
  setTimeout(notifyAndroidBars, 1200);
  try {
    var mo = new MutationObserver(notifyAndroidBars);
    mo.observe(document.documentElement, {
      attributes: true,
      attributeFilter: ['data-ds-dark-theme', 'data-ds-light-theme', 'data-theme', 'class']
    });
  } catch (e) {}
})();

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
