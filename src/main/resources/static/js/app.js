/* ==========================================================================
   MiHome 知识引擎 — 公共前端脚本
   暴露全局 KE：接口封装 / 鉴权 / Toast / Markdown 渲染 / 主题 / 图标
   ========================================================================== */

(function (global) {
  'use strict';

  /* ------------------------------ 图标 ------------------------------ */
  const ICONS = {
    spark: '<path d="M12 2l2.2 5.8L20 10l-5.8 2.2L12 18l-2.2-5.8L4 10l5.8-2.2z"/>',
    plus: '<path d="M12 5v14M5 12h14"/>',
    chat: '<path d="M21 11.5a8.4 8.4 0 01-9 8.4 9 9 0 01-3.8-.8L3 21l1.9-5.1A8.4 8.4 0 013 11.5 8.4 8.4 0 0112 3a8.4 8.4 0 019 8.5z"/>',
    send: '<path d="M4 12l16-8-6 8 6 8z"/>',
    trash: '<path d="M3 6h18M8 6V4h8v2M19 6l-1 14H6L5 6M10 11v6M14 11v6"/>',
    logout: '<path d="M9 21H5a2 2 0 01-2-2V5a2 2 0 012-2h4M16 17l5-5-5-5M21 12H9"/>',
    copy: '<rect x="9" y="9" width="12" height="12" rx="2"/><path d="M5 15V5a2 2 0 012-2h10"/>',
    check: '<path d="M20 6L9 17l-5-5"/>',
    sun: '<circle cx="12" cy="12" r="4"/><path d="M12 2v2M12 20v2M4.9 4.9l1.4 1.4M17.7 17.7l1.4 1.4M2 12h2M20 12h2M4.9 19.1l1.4-1.4M17.7 6.3l1.4-1.4"/>',
    moon: '<path d="M21 12.8A9 9 0 1111.2 3a7 7 0 009.8 9.8z"/>',
    upload: '<path d="M21 15v4a2 2 0 01-2 2H5a2 2 0 01-2-2v-4M7 9l5-5 5 5M12 4v12"/>',
    file: '<path d="M14 2H6a2 2 0 00-2 2v16a2 2 0 002 2h12a2 2 0 002-2V8z"/><path d="M14 2v6h6"/>',
    doc: '<path d="M14 2H6a2 2 0 00-2 2v16a2 2 0 002 2h12a2 2 0 002-2V8z"/><path d="M14 2v6h6"/>',
    chevron: '<path d="M9 18l6-6-6-6"/>',
    user: '<circle cx="12" cy="8" r="4"/><path d="M4 21v-1a6 6 0 016-6h4a6 6 0 016 6v1"/>',
    alert: '<path d="M12 9v4M12 17h.01M10.3 3.9L1.8 18a2 2 0 001.7 3h17a2 2 0 001.7-3L13.7 3.9a2 2 0 00-3.4 0z"/>',
    info: '<circle cx="12" cy="12" r="9"/><path d="M12 16v-4M12 8h.01"/>',
    pin: '<path d="M12 21s7-5.6 7-11a7 7 0 10-14 0c0 5.4 7 11 7 11z"/><circle cx="12" cy="10" r="2.5"/>',
    search: '<circle cx="11" cy="11" r="7"/><path d="M20 20l-3.5-3.5"/>',
    refresh: '<path d="M21 12a9 9 0 11-2.6-6.4M21 4v5h-5"/>',
    layers: '<path d="M12 2L2 7l10 5 10-5z"/><path d="M2 17l10 5 10-5M2 12l10 5 10-5"/>',
    edit: '<path d="M11 4H4a2 2 0 00-2 2v14a2 2 0 002 2h14a2 2 0 002-2v-7"/><path d="M18.5 2.5a2.1 2.1 0 013 3L12 15l-4 1 1-4z"/>',
  };

  function svg(name, size) {
    const d = ICONS[name] || '';
    const s = size || 16;
    return '<svg viewBox="0 0 24 24" width="' + s + '" height="' + s + '" fill="none" ' +
      'stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round">' +
      d + '</svg>';
  }

  /* ------------------------------ 工具 ------------------------------ */
  function esc(s) {
    return String(s == null ? '' : s)
      .replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
      .replace(/"/g, '&quot;').replace(/'/g, '&#39;');
  }

  function timeText(v) {
    if (!v) return '';
    const d = typeof v === 'string' ? new Date(String(v).replace(' ', 'T')) : new Date(v);
    if (isNaN(d.getTime())) return '';
    const p = n => String(n).padStart(2, '0');
    return p(d.getHours()) + ':' + p(d.getMinutes());
  }

  function bytes(n) {
    if (n == null) return '';
    if (n < 1024) return n + ' B';
    if (n < 1048576) return (n / 1024).toFixed(1) + ' KB';
    return (n / 1048576).toFixed(1) + ' MB';
  }

  /* ------------------------------ Toast ------------------------------ */
  let toastWrap = null;
  function toast(msg, type, ms) {
    if (!toastWrap) {
      toastWrap = document.createElement('div');
      toastWrap.className = 'toast-wrap';
      document.body.appendChild(toastWrap);
    }
    const icon = type === 'err' ? 'alert' : (type === 'warn' ? 'alert' : (type === 'ok' ? 'check' : 'info'));
    const el = document.createElement('div');
    el.className = 'toast ' + (type || '');
    el.innerHTML = svg(icon, 15) + '<span>' + esc(msg) + '</span>';
    toastWrap.appendChild(el);
    setTimeout(function () {
      el.style.transition = 'opacity .2s, transform .2s';
      el.style.opacity = '0';
      el.style.transform = 'translateY(-6px)';
      setTimeout(function () { el.remove(); }, 220);
    }, ms || 2600);
  }

  /* ------------------------------ 主题 ------------------------------ */
  const THEME_KEY = 'ke-theme';
  const theme = {
    get: function () {
      return localStorage.getItem(THEME_KEY) ||
        (window.matchMedia && window.matchMedia('(prefers-color-scheme: dark)').matches ? 'dark' : 'light');
    },
    apply: function (t) {
      document.documentElement.setAttribute('data-theme', t);
      document.querySelectorAll('[data-theme-toggle]').forEach(function (b) {
        b.innerHTML = svg(t === 'dark' ? 'sun' : 'moon', 17);
        b.title = t === 'dark' ? '切换到浅色' : '切换到深色';
      });
    },
    toggle: function () {
      const t = theme.get() === 'dark' ? 'light' : 'dark';
      localStorage.setItem(THEME_KEY, t);
      theme.apply(t);
    },
    init: function () { theme.apply(theme.get()); }
  };

  /* ------------------------------ 接口 ------------------------------ */
  function unwrap(json) {
    // 兼容两种返回：统一封装 R{code,msg,data} 与直接返回业务对象
    if (json && typeof json === 'object' && 'code' in json && 'data' in json) {
      if (json.code !== 0 && json.code !== 200) {
        const e = new Error(json.msg || '请求失败');
        e.code = json.code;
        throw e;
      }
      return json.data;
    }
    return json;
  }

  async function request(url, opts) {
    opts = opts || {};
    const init = { method: opts.method || 'GET', credentials: 'same-origin' };
    if (opts.body instanceof FormData) {
      init.body = opts.body;
    } else if (opts.body !== undefined) {
      init.headers = { 'Content-Type': 'application/json' };
      init.body = JSON.stringify(opts.body);
    }
    const res = await fetch(url, init);
    if (res.status === 401) {
      const back = encodeURIComponent(location.pathname + location.search);
      location.href = '/login.html?redirect=' + back;
      throw new Error('未登录');
    }
    if (!res.ok) throw new Error('HTTP ' + res.status);
    const ct = res.headers.get('content-type') || '';
    if (ct.indexOf('application/json') >= 0) return unwrap(await res.json());
    return await res.text();
  }

  const api = {
    get:  function (u) { return request(u, { method: 'GET' }); },
    post: function (u, b) { return request(u, { method: 'POST', body: b }); },
    del:  function (u) { return request(u, { method: 'DELETE' }); },

    // 带进度的上传
    upload: function (url, formData, onProgress) {
      return new Promise(function (resolve, reject) {
        const xhr = new XMLHttpRequest();
        xhr.open('POST', url, true);
        xhr.withCredentials = true;
        if (onProgress && xhr.upload) {
          xhr.upload.onprogress = function (e) {
            if (e.lengthComputable) onProgress(Math.round(e.loaded / e.total * 100));
          };
        }
        xhr.onload = function () {
          if (xhr.status === 401) { location.href = '/login.html'; return reject(new Error('未登录')); }
          if (xhr.status < 200 || xhr.status >= 300) return reject(new Error('HTTP ' + xhr.status));
          try { resolve(JSON.parse(xhr.responseText)); } catch (e) { resolve(xhr.responseText); }
        };
        xhr.onerror = function () { reject(new Error('网络错误')); };
        xhr.send(formData);
      });
    }
  };

  /* ------------------------------ 鉴权 ------------------------------ */
  const auth = {
    user: null,
    async load() {
      try {
        if (await api.get('/auth/isLogin')) {
          this.user = await api.get('/auth/userInfo');
          return this.user;
        }
      } catch (e) { /* 未登录 */ }
      return null;
    },
    async require() {
      const u = await this.load();
      if (!u) {
        const back = encodeURIComponent(location.pathname + location.search);
        location.href = '/login.html?redirect=' + back;
        return null;
      }
      return u;
    },
    async logout() {
      try { await api.post('/auth/logout'); } catch (e) { /* 忽略 */ }
      location.href = '/login.html';
    }
  };

  /* ------------------------------ Markdown ------------------------------ */
  const md = {
    ready: !!(global.marked && global.DOMPurify),
    init: function () {
      if (!global.marked) return;
      global.marked.setOptions({ breaks: true, gfm: true });
      if (global.hljs) {
        global.marked.use({
          renderer: {
            code: function (code, infostring) {
              const lang = (infostring || '').match(/\S*/)[0];
              let body = code;
              try {
                if (lang && global.hljs.getLanguage(lang)) body = global.hljs.highlight(code, { language: lang }).value;
                else if (global.hljs.highlightAuto) body = global.hljs.highlightAuto(code).value;
              } catch (e) { body = code; }
              return '<pre><code class="hljs language-' + esc(lang || 'text') + '">' + body + '</code></pre>';
            }
          }
        });
      }
      this.ready = true;
    },
    render: function (raw) {
      if (!raw) return '';
      if (!global.marked) return esc(raw).replace(/\n/g, '<br>');
      let html;
      try { html = global.marked.parse(raw); } catch (e) { html = esc(raw); }
      if (global.DOMPurify) html = global.DOMPurify.sanitize(html, { ADD_ATTR: ['target'] });
      return html;
    }
  };

  /* ------------------------------ 确认框 ------------------------------ */
  function confirmDialog(title, desc, okText) {
    return new Promise(function (resolve) {
      const mask = document.createElement('div');
      mask.style.cssText = 'position:fixed;inset:0;background:rgba(9,12,17,.5);z-index:9000;' +
        'display:grid;place-items:center;padding:24px;backdrop-filter:blur(2px)';
      mask.innerHTML =
        '<div class="card" style="max-width:360px;width:100%;padding:22px;text-align:left">' +
        '<div style="font-size:15.5px;font-weight:620;margin-bottom:7px">' + esc(title) + '</div>' +
        '<div style="font-size:13px;color:var(--text-2);line-height:1.6;margin-bottom:18px">' + esc(desc || '') + '</div>' +
        '<div style="display:flex;gap:9px;justify-content:flex-end">' +
        '<button class="btn" data-no>取消</button>' +
        '<button class="btn btn-primary" data-yes>' + esc(okText || '确认') + '</button>' +
        '</div></div>';
      document.body.appendChild(mask);
      mask.addEventListener('click', function (e) {
        if (e.target === mask || e.target.hasAttribute('data-no')) { mask.remove(); resolve(false); }
        if (e.target.hasAttribute('data-yes')) { mask.remove(); resolve(true); }
      });
    });
  }

  /* ------------------------------ 提示条 ------------------------------ */
  function alertBox(el, msg) {
    if (!el) return;
    el.innerHTML = svg('alert', 15) + '<span>' + esc(msg) + '</span>';
    el.classList.add('show');
  }
  function alertHide(el) { if (el) el.classList.remove('show'); }

  /* ------------------------------ 导出 ------------------------------ */
  global.KE = {
    icons: ICONS, svg: svg,
    esc: esc, timeText: timeText, bytes: bytes,
    toast: toast, theme: theme,
    api: api, auth: auth, md: md,
    confirmDialog: confirmDialog,
    alertBox: alertBox, alertHide: alertHide
  };

  // 主题尽早应用，避免闪烁
  try { document.documentElement.setAttribute('data-theme', theme.get()); } catch (e) { /* ignore */ }
})(window);
