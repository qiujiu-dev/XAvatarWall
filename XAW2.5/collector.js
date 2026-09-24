// XAvatarWall 粉丝采集内容脚本（运行于 x.com / twitter.com 的 followers 页面）。

(() => {
  if (window.__xavatarwall_collector__) return;
  window.__xavatarwall_collector__ = true;

  let running = false;
  let stopRequested = false;
  let seen = new Map();
  let targetUsername = '';
  let observer = null;
  let scanTimer = null;
  let persistTimer = null;
  let persistDirty = false;
  let persistConfig = null;
  let scanScheduled = false;
  const pendingGraphqlUsers = [];

  const DEFAULT_AVATAR = 'https://abs.twimg.com/sticky/default_profile_images/default_profile_400x400.png';

  const RESERVED = new Set([
    'i', 'home', 'explore', 'notifications', 'messages', 'search',
    'settings', 'compose', 'login', 'signup', 'download', 'tos',
    'privacy', 'help', 'about', 'jobs', 'twitter', 'x'
  ]);

  chrome.runtime.onMessage.addListener((msg, sender, sendResponse) => {
    if (msg.type === 'COLLECT_START') {
      startCollection(msg.config).catch(() => {});
      sendResponse({ ok: true });
      return false;
    }
    if (msg.type === 'COLLECT_STOP') {
      stopRequested = true;
      sendResponse({ ok: true });
      return false;
    }
    if (msg.type === 'GET_MY_PROFILE') {
      sendResponse(extractMyProfile());
      return false;
    }
  });

  window.addEventListener('message', (event) => {
    if (event.source !== window) return;
    const data = event.data;
    if (!data || data.source !== 'xavatarwall' || data.type !== 'USERS') return;
    const users = data.users || [];
    if (!running) {
      for (let i = 0; i < users.length; i++) pendingGraphqlUsers.push(users[i]);
      if (pendingGraphqlUsers.length > 20000) pendingGraphqlUsers.splice(0, pendingGraphqlUsers.length - 10000);
      return;
    }
    let added = 0;
    for (let i = 0; i < users.length; i++) {
      if (upsertFan(users[i].username, users[i].avatar)) added++;
    }
    if (added) schedulePersist();
  });

  // 页面加载时尝试自动恢复任务（后台可能未能及时投递消息）
  setTimeout(autoStartIfNeeded, 600);
  setTimeout(autoStartIfNeeded, 2500);

  async function autoStartIfNeeded() {
    if (running) return;
    const stored = await chrome.storage.local.get(['config']).catch(() => ({}));
    const cfg = stored.config;
    if (!cfg || !cfg.active || cfg.status !== 'collecting') return;
    if (!isFollowersPage(cfg.targetUsername || cfg.username)) return;
    startCollection(cfg).catch(() => {});
  }

  function isFollowersPage(username) {
    if (!username) return false;
    const path = (location.pathname || '').toLowerCase();
    const u = username.toLowerCase();
    return path === '/' + u + '/followers'
      || path === '/' + u + '/verified_followers'
      || path.startsWith('/' + u + '/followers');
  }

  /**
   * 提取当前登录用户（自己）的头像与显示名。
   * 在自己的主页上，资料头部的第一张大 twimg 图片就是自己的头像。
   */
  function extractMyProfile() {
    const profile = { avatar: '', name: '', username: '' };

    // 1) 优先：资料头部 / 主列第一张尺寸合适的 twimg 图片
    const imgs = document.querySelectorAll('img[src*="twimg.com"]');
    for (const img of imgs) {
      const src = img.getAttribute('src') || img.currentSrc || '';
      if (!src) continue;
      const rect = img.getBoundingClientRect();
      if (rect.width >= 64 && rect.width <= 500) {
        profile.avatar = normalizeAvatar(src);
        break;
      }
    }

    // 2) 用户名：取第一个指向 /用户名 的链接
    const links = document.querySelectorAll('a[href^="/"]');
    for (const a of links) {
      const href = a.getAttribute('href') || '';
      const m = parseUsernameFromHref(href);
      if (m) {
        profile.username = m;
        break;
      }
    }

    // 3) 显示名：资料头部用户名字段
    const nameEl = document.querySelector('[data-testid="UserName"] [dir="ltr"], [data-testid="UserName"] span');
    if (nameEl) profile.name = nameEl.textContent.trim();

    return profile;
  }

  async function startCollection(config) {
    if (running) return;
    running = true;
    stopRequested = false;

    const target = config.targetUsername || config.username;
    targetUsername = target;
    const maxCount = normalizeMax(config.maxCount);
    persistConfig = config;

    seen = new Map();
    const existing = await chrome.storage.local.get(['fansData']).catch(() => ({}));
    if (existing.fansData && existing.fansData.username === target) {
      (existing.fansData.fans || []).forEach((f) => {
        if (f && f.username) {
          seen.set(f.username.toLowerCase(), f);
        }
      });
    }
    if (pendingGraphqlUsers.length) {
      for (let i = 0; i < pendingGraphqlUsers.length; i++) {
        upsertFan(pendingGraphqlUsers[i].username, pendingGraphqlUsers[i].avatar);
      }
      pendingGraphqlUsers.length = 0;
    }

    clickAllFollowersTab(target);
    await waitForCells(15000);
    clickAllFollowersTab(target);

    startDomWatch();

    let noNewStreak = 0;
    const MAX_NO_NEW = 15;
    let lastPersistAt = 0;

    try {
      while (running && !stopRequested) {
        const before = seen.size;
        scanPage();

        if (Date.now() - lastPersistAt > 1200) {
          lastPersistAt = Date.now();
          await persist(target, config).catch(() => {});
        }

        if (maxCount !== 'all' && seen.size >= maxCount) break;

        if (clickRetryIfNeeded()) {
          noNewStreak = 0;
          await sleep(2200);
          continue;
        }

        const after = seen.size;
        const loading = isLoadingMore();

        if (after > before) {
          noNewStreak = 0;
        } else if (loading) {
          noNewStreak = Math.max(0, noNewStreak - 1);
        } else {
          noNewStreak++;
        }

        if (noNewStreak >= MAX_NO_NEW && !loading) break;

        scrollToLoadMore();
        await sleep(1400 + Math.floor(Math.random() * 700));
      }
    } finally {
      stopDomWatch();
    }

    running = false;

    if (stopRequested) {
      await persist(target, config).catch(() => {});
      await chrome.storage.local.set({
        progress: { current: seen.size, max: config.maxCount, message: '已停止' }
      }).catch(() => {});
      return;
    }

    scanPage();
    let fans = Array.from(seen.values()).sort((a, b) => a.index - b.index);
    if (maxCount !== 'all') fans = fans.slice(0, maxCount);
    fans.forEach((f) => {
      if (!f.avatar) f.avatar = DEFAULT_AVATAR;
    });

    const fansData = { username: target, fans, total: fans.length, collectedAt: Date.now() };
    await chrome.storage.local.set({
      fansData,
      progress: { current: fans.length, max: config.maxCount, message: fans.length ? '采集完成' : '未采集到粉丝' }
    }).catch(() => {});

    try {
      await chrome.runtime.sendMessage({ type: 'COLLECT_DONE', payload: { total: fans.length, username: target } });
    } catch (e) {
      // 后台会被消息唤醒；此处失败则依赖弹窗手动“打开生成页面”
    }
  }

  function startDomWatch() {
    stopDomWatch();
    if (document.body) {
      observer = new MutationObserver(() => scheduleScan());
      observer.observe(document.body, { childList: true, subtree: true });
    }
    scanTimer = setInterval(() => {
      if (running) scanPage();
    }, 450);
  }

  function stopDomWatch() {
    if (observer) {
      observer.disconnect();
      observer = null;
    }
    if (scanTimer) {
      clearInterval(scanTimer);
      scanTimer = null;
    }
    if (persistTimer) {
      clearTimeout(persistTimer);
      persistTimer = null;
    }
  }

  function scheduleScan() {
    if (!running || scanScheduled) return;
    scanScheduled = true;
    setTimeout(() => {
      scanScheduled = false;
      if (running) scanPage();
    }, 200);
  }

  function schedulePersist() {
    persistDirty = true;
    if (persistTimer) return;
    persistTimer = setTimeout(() => {
      persistTimer = null;
      if (!persistDirty || !running) return;
      persistDirty = false;
      persist(targetUsername, persistConfig).catch(() => {});
    }, 800);
  }

  function isHandle(name) {
    return typeof name === 'string'
      && /^[A-Za-z0-9_]{1,15}$/.test(name)
      && !RESERVED.has(name.toLowerCase());
  }

  function upsertFan(username, avatar) {
    if (!isHandle(username)) return false;
    if (targetUsername && username.toLowerCase() === targetUsername.toLowerCase()) return false;
    const key = username.toLowerCase();
    const existing = seen.get(key);
    const normalized = avatar ? normalizeAvatar(avatar) : '';
    if (existing) {
      if (normalized && !existing.avatar) existing.avatar = normalized;
      return false;
    }
    seen.set(key, {
      username,
      avatar: normalized,
      index: seen.size + 1,
      time: Date.now()
    });
    return true;
  }

  function scanPage() {
    const cells = getCells();
    for (const cell of cells) {
      if (seen.size >= 100000) return;
      const username = extractUsername(cell);
      if (!username) continue;
      upsertFan(username, extractAvatar(cell));
    }
  }

  function getPrimaryColumn() {
    return document.querySelector('[data-testid="primaryColumn"]') || document;
  }

  function getCells() {
    const root = getPrimaryColumn();
    const userCells = root.querySelectorAll('[data-testid="UserCell"]');
    if (userCells.length) return userCells;
    return root.querySelectorAll('[data-testid="cellInnerDiv"]');
  }

  function parseUsernameFromHref(href) {
    if (!href) return null;
    let path = href;
    try {
      if (/^https?:\/\//i.test(href)) path = new URL(href).pathname;
    } catch (e) {
      path = href;
    }
    const m = String(path).match(/^\/([A-Za-z0-9_]{1,15})(?:\/|\?|#|$)/);
    if (m && isHandle(m[1])) return m[1];
    return null;
  }

  function extractUsername(cell) {
    const av = cell.querySelector('[data-testid^="UserAvatar-Container-"]');
    if (av) {
      const id = av.getAttribute('data-testid') || '';
      const name = id.replace(/^UserAvatar-Container-/, '');
      if (isHandle(name) && name.toLowerCase() !== 'unknown') return name;
    }

    const desc = cell.querySelector('[data-testid="UserDescription"]');
    const anchors = cell.querySelectorAll('a[href]');
    for (const a of anchors) {
      if (desc && desc.contains(a)) continue;
      const u = parseUsernameFromHref(a.getAttribute('href'));
      if (u) return u;
    }

    const nameBox = cell.querySelector('[data-testid="User-Name"]') || cell;
    const m = (nameBox.textContent || '').match(/@([A-Za-z0-9_]{1,15})/);
    if (m && isHandle(m[1])) return m[1];
    return null;
  }

  function extractAvatar(cell) {
    const scoped = cell.querySelectorAll('[data-testid^="UserAvatar-Container-"] img, img');
    for (const img of scoped) {
      const src = img.getAttribute('src') || img.currentSrc || '';
      if (isProfileImage(src)) return src;
      const srcset = img.getAttribute('srcset') || '';
      const m = srcset.match(/https?:\/\/[^\s,]*twimg\.com[^\s,]*/);
      if (m && isProfileImage(m[0])) return m[0];
    }
    const bgNodes = cell.querySelectorAll('[data-testid^="UserAvatar-Container-"] [style], [data-testid^="UserAvatar-Container-"]');
    for (const n of bgNodes) {
      const bg = (n.getAttribute('style') || '') + (n.style && n.style.backgroundImage ? n.style.backgroundImage : '');
      const m = bg.match(/url\(["']?(https?:\/\/[^"')]*twimg\.com[^"')]*)/);
      if (m && isProfileImage(m[1])) return m[1];
    }
    return '';
  }

  function isProfileImage(src) {
    if (!src || !/twimg\.com/.test(src)) return false;
    if (/\/emoji\/|ext_tw_video|card_img|\/media\//.test(src)) return false;
    return true;
  }

  function normalizeAvatar(url) {
    try {
      const u = new URL(url);
      u.pathname = u.pathname
        .replace(/_normal(\.[A-Za-z]+)$/, '_400x400$1')
        .replace(/_mini(\.[A-Za-z]+)$/, '_400x400$1')
        .replace(/_bigger(\.[A-Za-z]+)$/, '_400x400$1')
        .replace(/_200x200(\.[A-Za-z]+)$/, '_400x400$1');
      return u.toString();
    } catch (e) {
      return url;
    }
  }

  function getScrollRoot() {
    const cell = document.querySelector('[data-testid="UserCell"]')
      || document.querySelector('[data-testid="primaryColumn"]');
    let node = cell;
    while (node && node !== document.documentElement) {
      const style = window.getComputedStyle(node);
      const oy = style.overflowY;
      if ((oy === 'auto' || oy === 'scroll' || oy === 'overlay') && node.scrollHeight > node.clientHeight + 20) {
        return node;
      }
      node = node.parentElement;
    }
    return document.scrollingElement || document.documentElement;
  }

  function scrollToLoadMore() {
    const cells = getCells();
    if (cells.length) {
      const last = cells[cells.length - 1];
      try {
        last.scrollIntoView({ block: 'end', inline: 'nearest' });
      } catch (e) {
        /* 部分环境不支持 options 参数 */
      }
    }
    const root = getScrollRoot();
    const step = Math.max(520, Math.floor((root.clientHeight || window.innerHeight) * 0.65));
    if (root === document.scrollingElement || root === document.documentElement || root === document.body) {
      window.scrollBy(0, step);
    } else {
      root.scrollTop += step;
    }
  }

  function isLoadingMore() {
    const col = getPrimaryColumn();
    return !!(col.querySelector('[role="progressbar"], [data-testid="indicator"]'));
  }

  function clickRetryIfNeeded() {
    const col = getPrimaryColumn();
    const els = col.querySelectorAll('[role="button"], button');
    for (const el of els) {
      const t = (el.textContent || '').trim();
      if (/^(Retry|Try again|重试|再试一次|重新加载)$/i.test(t)) {
        el.click();
        return true;
      }
    }
    return false;
  }

  function clickAllFollowersTab(username) {
    if (!username) return false;
    const needle = '/' + username.toLowerCase() + '/followers';
    const links = document.querySelectorAll('a[href]');
    for (const a of links) {
      let path = (a.getAttribute('href') || '').toLowerCase();
      try {
        if (/^https?:/.test(path)) path = new URL(a.href).pathname.toLowerCase();
      } catch (e) {
        /* 保持原 path */
      }
      if (path === needle || path === needle + '/') {
        if (a.getAttribute('aria-selected') !== 'true') {
          a.click();
          return true;
        }
        return false;
      }
    }
    const tabs = document.querySelectorAll('[role="tab"]');
    for (const tab of tabs) {
      const text = (tab.textContent || '').replace(/\s+/g, ' ').trim();
      if (/verified|认证/i.test(text)) continue;
      if (/you know|你认识|认识的/i.test(text)) continue;
      if (!/followers|粉丝/i.test(text)) continue;
      if (tab.getAttribute('aria-selected') !== 'true') {
        tab.click();
        return true;
      }
    }
    return false;
  }

  async function waitForCells(timeoutMs) {
    const start = Date.now();
    while (Date.now() - start < timeoutMs) {
      if (stopRequested) return;
      if (getCells().length > 0) return;
      await sleep(500);
    }
  }

  async function persist(target, config) {
    if (!target || !config) return;
    const fans = Array.from(seen.values()).sort((a, b) => a.index - b.index);
    await chrome.storage.local.set({
      fansData: { username: target, fans, total: fans.length, collectedAt: Date.now() },
      progress: { current: fans.length, max: config.maxCount, message: `采集中 ${fans.length} 位…` }
    });
  }

  function normalizeMax(maxCount) {
    if (maxCount === 'all' || maxCount == null) return 'all';
    const n = parseInt(maxCount, 10);
    return isNaN(n) || n < 1 ? 'all' : n;
  }

  function sleep(ms) {
    return new Promise((resolve) => setTimeout(resolve, ms));
  }
})();
