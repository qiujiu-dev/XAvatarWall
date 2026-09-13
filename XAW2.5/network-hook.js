// 运行在页面 MAIN world：拦截 X 自己的 GraphQL 请求，把粉丝列表用户发给内容脚本。
// 必须在 document_start 注入，才能赶上第一页 Followers 响应。

(() => {
  if (window.__xavatarwall_net_hook__) return;
  window.__xavatarwall_net_hook__ = true;

  const LIST_RE = /\/graphql\/[^/?]+\/(Followers|BlueVerifiedFollowers|FollowersYouKnow)(?:\?|$)/i;

  function postUsers(users) {
    if (!users || !users.length) return;
    try {
      window.postMessage({ source: 'xavatarwall', type: 'USERS', users }, '*');
    } catch (e) {
      /* 忽略 */
    }
  }

  function pickScreen(obj) {
    const cands = [
      obj.screen_name,
      obj.core && obj.core.screen_name,
      obj.legacy && obj.legacy.screen_name
    ];
    for (const c of cands) {
      if (typeof c === 'string' && /^[A-Za-z0-9_]{1,15}$/.test(c)) return c;
    }
    return '';
  }

  function pickAvatar(obj) {
    const cands = [
      obj.profile_image_url_https,
      obj.profile_image_url,
      obj.avatar && (obj.avatar.image_url || obj.avatar.image_url_https),
      obj.legacy && obj.legacy.profile_image_url_https
    ];
    for (const c of cands) {
      if (typeof c === 'string' && /twimg\.com/.test(c)) return c;
    }
    return '';
  }

  function asUser(obj) {
    if (!obj || typeof obj !== 'object' || Array.isArray(obj)) return null;
    const type = obj.__typename;
    if (type && type !== 'User') return null;
    const username = pickScreen(obj);
    if (!username) return null;
    if (!type && !obj.rest_id && !obj.id_str && !obj.legacy && !obj.avatar && !obj.core) return null;
    return { username, avatar: pickAvatar(obj) };
  }

  function walk(node, out) {
    if (!node || typeof node !== 'object') return;
    const user = asUser(node);
    if (user) out.push(user);
    if (Array.isArray(node)) {
      for (let i = 0; i < node.length; i++) walk(node[i], out);
      return;
    }
    for (const key in node) {
      if (Object.prototype.hasOwnProperty.call(node, key)) walk(node[key], out);
    }
  }

  function ingest(json) {
    try {
      const users = [];
      walk(json, users);
      if (!users.length) return;
      const uniq = [];
      const byKey = new Map();
      for (let i = 0; i < users.length; i++) {
        const u = users[i];
        const key = u.username.toLowerCase();
        const prev = byKey.get(key);
        if (!prev) {
          byKey.set(key, u);
          uniq.push(u);
        } else if (u.avatar && !prev.avatar) {
          prev.avatar = u.avatar;
        }
      }
      postUsers(uniq);
    } catch (e) {
      /* 解析失败不影响页面 */
    }
  }

  function urlOf(args) {
    try {
      const a0 = args[0];
      if (typeof a0 === 'string') return a0;
      if (a0 && typeof a0.url === 'string') return a0.url;
    } catch (e) {
      /* 忽略 */
    }
    return '';
  }

  try {
    const origFetch = window.fetch;
    if (typeof origFetch === 'function') {
      window.fetch = function () {
        const p = origFetch.apply(this, arguments);
        try {
          const url = urlOf(arguments);
          if (LIST_RE.test(url)) {
            p.then((res) => {
              try { return res.clone().json(); } catch (e) { return null; }
            }).then((json) => { if (json) ingest(json); }).catch(() => {});
          }
        } catch (e) {
          /* 忽略 */
        }
        return p;
      };
    }
  } catch (e) {
    /* 忽略 */
  }

  try {
    const origOpen = XMLHttpRequest.prototype.open;
    const origSend = XMLHttpRequest.prototype.send;
    XMLHttpRequest.prototype.open = function (method, url) {
      this.__xaw_url = url == null ? '' : String(url);
      return origOpen.apply(this, arguments);
    };
    XMLHttpRequest.prototype.send = function () {
      this.addEventListener('load', function () {
        try {
          if (!LIST_RE.test(this.__xaw_url || '')) return;
          ingest(JSON.parse(this.responseText));
        } catch (e) {
          /* 忽略 */
        }
      });
      return origSend.apply(this, arguments);
    };
  } catch (e) {
    /* 忽略 */
  }
})();
