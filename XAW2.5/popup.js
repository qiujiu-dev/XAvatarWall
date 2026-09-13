const $ = (id) => document.getElementById(id);
const BUSY = ['collecting', 'downloading', 'generating'];

// 内部版本号：从项目创建至今的修改轮次。
const INTERNAL_BUILD = 32;

// 默认个性化配置
const DEFAULT_GEN = {
  genTitle: 'XX fo谢谢大家，感谢大家！',
  genExtraText: '',
  genStyle: 'gradient-v',
  genShape: 'round',
  genTitleColor: '#2563eb',
  genHighlight: 'me',
  genGap: 'auto',
  genColorA: '#dbeafe',
  genColorB: '#93c5fd'
};

let config = {
  active: false,
  status: 'idle',
  username: '',
  maxCount: 'all',
  avatarSize: 160,
  sortMode: 'default',
  bgColor: '#dbeafe'
};
let progress = null;

document.addEventListener('DOMContentLoaded', init);

async function init() {
  bindEvents();
  showVersion();
  checkUpdate();
  const stored = await chrome.storage.local.get(['config', 'progress']).catch(() => ({}));
  if (stored.config) config = { ...config, ...stored.config };
  progress = stored.progress || null;
  applyConfigToUI();
  render();
  toggleGradientColors();
  updateSettingsOverlay();

  chrome.storage.onChanged.addListener((changes, area) => {
    if (area !== 'local') return;
    if (changes.config) {
      config = { ...config, ...(changes.config.newValue || {}) };
      applyConfigToUI();
      toggleGradientColors();
      updateSettingsOverlay();
    }
    if (changes.progress) progress = changes.progress.newValue || null;
    render();
  });
}

function bindEvents() {
  $('bgColor').addEventListener('input', () => {
    $('bgColorText').textContent = $('bgColor').value;
    saveSettings();
  });
  $('cfgTitleColor').addEventListener('input', () => {
    $('cfgTitleColorText').textContent = $('cfgTitleColor').value;
    saveSettings();
  });
  $('cfgColorA').addEventListener('input', () => {
    $('cfgColorAText').textContent = $('cfgColorA').value;
    saveSettings();
  });
  $('cfgColorB').addEventListener('input', () => {
    $('cfgColorBText').textContent = $('cfgColorB').value;
    saveSettings();
  });
  $('cfgStyle').addEventListener('change', () => {
    toggleGradientColors();
    saveSettings();
  });
  ['cfgTitle', 'cfgExtraText', 'cfgShape', 'cfgHighlight', 'cfgGap'].forEach((id) => {
    $(id).addEventListener('input', () => {
      saveSettings();
    });
  });
  $('startBtn').addEventListener('click', onStart);
  $('stopBtn').addEventListener('click', onStop);
  $('openGenBtn').addEventListener('click', openGenerator);
  $('versionText').addEventListener('click', openRepo);
  $('checkUpdateLink').addEventListener('click', (e) => {
    e.preventDefault();
    chrome.tabs.create({ url: 'https://github.com/qiujiu-dev/XAvatarWall/releases' });
  });
}

function openRepo() {
  chrome.tabs.create({ url: 'https://github.com/qiujiu-dev/XAvatarWall' });
}

function toggleGradientColors() {
  const style = $('cfgStyle').value;
  $('genColorRow').hidden = !/^gradient/.test(style);
}

function updateSettingsOverlay() {
  const canEdit = (config.status || 'idle') === 'done';
  $('settingsOverlay').hidden = canEdit;
}

function genSettings() {
  return {
    genTitle: $('cfgTitle').value.trim(),
    genExtraText: $('cfgExtraText').value.trim(),
    genStyle: $('cfgStyle').value,
    genShape: $('cfgShape').value,
    genTitleColor: $('cfgTitleColor').value,
    genHighlight: $('cfgHighlight').value,
    genGap: $('cfgGap').value,
    genColorA: $('cfgColorA').value,
    genColorB: $('cfgColorB').value
  };
}

async function saveSettings() {
  const settings = genSettings();
  const stored = await chrome.storage.local.get(['config']).catch(() => ({}));
  await chrome.storage.local.set({
    config: Object.assign({}, stored.config || {}, settings)
  }).catch(() => {});
}

function applyConfigToUI() {
  if (!$('username').matches(':focus')) $('username').value = config.username || '';
  if (!$('maxCount').matches(':focus')) $('maxCount').value = config.maxCount === 'all' ? '' : config.maxCount;
  $('avatarSize').value = String(config.avatarSize || 160);
  const radio = document.querySelector(`input[name="sort"][value="${config.sortMode || 'default'}"]`);
  if (radio) radio.checked = true;
  if (config.bgColor) {
    $('bgColor').value = config.bgColor;
    $('bgColorText').textContent = config.bgColor;
  }
  const g = Object.assign({}, DEFAULT_GEN, config);
  $('cfgTitle').value = g.genTitle;
  $('cfgExtraText').value = g.genExtraText || '';
  $('cfgStyle').value = g.genStyle;
  $('cfgShape').value = g.genShape;
  $('cfgTitleColor').value = g.genTitleColor;
  $('cfgTitleColorText').textContent = g.genTitleColor;
  $('cfgHighlight').value = g.genHighlight;
  $('cfgGap').value = g.genGap;
  $('cfgColorA').value = g.genColorA || '#dbeafe';
  $('cfgColorAText').textContent = $('cfgColorA').value;
  $('cfgColorB').value = g.genColorB || '#93c5fd';
  $('cfgColorBText').textContent = $('cfgColorB').value;
}

async function onStart() {
  const input = readInput();
  if (!isValidUsername(input.username)) {
    setMessage('请输入有效的 X 用户名');
    return;
  }
  await saveSettings();
  try {
    const res = await chrome.runtime.sendMessage({ type: 'START', payload: input });
    if (res && res.ok === false) setMessage(res.error || '启动失败');
  } catch (e) {
    setMessage('启动失败：' + ((e && e.message) || e));
  }
}

async function onStop() {
  try {
    await chrome.runtime.sendMessage({ type: 'STOP' });
  } catch (e) {
    /* 忽略 */
  }
}

function openGenerator() {
  chrome.tabs.create({ url: chrome.runtime.getURL('generator.html') });
}

function showVersion() {
  const v = chrome.runtime.getManifest().version;
  $('versionText').textContent = `v${v}（内部版本 ${INTERNAL_BUILD}）`;
}

async function checkUpdate() {
  try {
    const res = await chrome.runtime.sendMessage({ type: 'CHECK_UPDATE' });
    if (res && res.ok && res.hasUpdate) {
      const badge = $('updateBadge');
      badge.hidden = false;
      badge.textContent = `发现新版本 v${res.latest}`;
    }
  } catch (e) {
    /* 网络失败静默，不影响使用 */
  }
}

function readInput() {
  const username = ($('username').value || '').trim().replace(/^@/, '').trim();
  const maxRaw = ($('maxCount').value || '').trim();
  const maxCount = maxRaw === '' || isNaN(parseInt(maxRaw, 10)) ? 'all' : Math.max(1, parseInt(maxRaw, 10));
  return Object.assign({}, genSettings(), {
    username,
    maxCount,
    avatarSize: parseInt($('avatarSize').value, 10) || 160,
    sortMode: (document.querySelector('input[name="sort"]:checked') || {}).value || 'default',
    bgColor: $('bgColor').value || '#dbeafe'
  });
}

function render() {
  const status = config.status || 'idle';
  const busy = BUSY.includes(status);
  $('startBtn').disabled = busy;
  $('startBtn').textContent = busy ? '制作中…' : '开始制作';
  $('stopBtn').hidden = !['collecting', 'downloading'].includes(status);
  $('statusSection').hidden = status === 'idle';

  const total = progress && progress.max && progress.max !== 'all' ? progress.max : null;
  const current = progress ? (progress.current || 0) : 0;
  const message = (progress && progress.message) || statusLabel(status);
  $('statusText').textContent = message;

  if (total) {
    $('statusCount').textContent = current + ' / ' + total;
    $('progressBar').style.width = Math.min(100, Math.round((current / total) * 100)) + '%';
  } else {
    $('statusCount').textContent = current ? String(current) : '';
    $('progressBar').style.width = status === 'done' ? '100%' : '0%';
  }
}

function setMessage(text) {
  $('statusSection').hidden = false;
  $('statusText').textContent = text;
  $('statusCount').textContent = '';
}

function statusLabel(status) {
  const map = {
    collecting: '采集中…',
    downloading: '下载头像中…',
    generating: '生成图片中…',
    done: '完成',
    error: '出错了'
  };
  return map[status] || '';
}

function isValidUsername(name) {
  return /^[A-Za-z0-9_]{1,15}$/.test(name);
}
