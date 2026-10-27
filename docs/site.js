(() => {
  const tabs = [...document.querySelectorAll('.screen-tab')];
  const image = document.querySelector('[data-screen-image]');
  const panel = document.getElementById('screen-panel');
  const title = document.querySelector('[data-screen-title]');
  const description = document.querySelector('[data-screen-description]');
  const number = document.querySelector('[data-screen-index]');
  const points = [...document.querySelectorAll('[data-screen-point]')];

  function activateTab(tab, focus = false) {
    if (!image || !panel || !title || !description || !number) return;
    for (const item of tabs) {
      const active = item === tab;
      item.classList.toggle('is-active', active);
      item.setAttribute('aria-selected', String(active));
      item.tabIndex = active ? 0 : -1;
    }
    image.src = tab.dataset.image;
    image.alt = tab.dataset.alt;
    title.textContent = tab.dataset.title;
    description.textContent = tab.dataset.description;
    number.textContent = tab.dataset.index;
    const items = tab.dataset.points.split('|');
    points.forEach((point, index) => { point.textContent = items[index] || ''; });
    panel.setAttribute('aria-labelledby', tab.id);
    if (focus) tab.focus();
  }

  tabs.forEach((tab, index) => {
    tab.addEventListener('click', () => activateTab(tab));
    tab.addEventListener('keydown', (event) => {
      let target;
      if (event.key === 'ArrowDown' || event.key === 'ArrowRight') target = (index + 1) % tabs.length;
      if (event.key === 'ArrowUp' || event.key === 'ArrowLeft') target = (index - 1 + tabs.length) % tabs.length;
      if (event.key === 'Home') target = 0;
      if (event.key === 'End') target = tabs.length - 1;
      if (target === undefined) return;
      event.preventDefault();
      activateTab(tabs[target], true);
    });
  });

  const menu = document.querySelector('[data-mobile-menu]');
  menu?.querySelectorAll('a').forEach((link) => {
    link.addEventListener('click', () => menu.removeAttribute('open'));
  });
  menu?.addEventListener('keydown', (event) => {
    if (event.key === 'Escape' && menu.open) {
      menu.removeAttribute('open');
      menu.querySelector('summary')?.focus();
    }
  });
  document.addEventListener('click', (event) => {
    if (menu?.open && !menu.contains(event.target)) menu.removeAttribute('open');
  });

  // 获取失败时保留 HTML 中的 Releases 入口，下载不依赖接口可用性。
  async function updateDownload() {
    const controller = new AbortController();
    const timeout = setTimeout(() => controller.abort(), 5000);
    try {
      const response = await fetch('https://api.github.com/repos/litianfu1997/fit-sync-app/releases/latest', {
        headers: { Accept: 'application/vnd.github+json' },
        signal: controller.signal,
      });
      if (!response.ok) return;
      const release = await response.json();
      if (release.draft || !Array.isArray(release.assets)) return;
      const prefix = 'https://github.com/litianfu1997/fit-sync-app/releases/download/';
      const apk = release.assets.find((asset) =>
        asset.state === 'uploaded' && typeof asset.name === 'string' && /\.apk$/i.test(asset.name) &&
        typeof asset.browser_download_url === 'string' && asset.browser_download_url.startsWith(prefix)
      );
      if (!apk) return;
      document.querySelectorAll('[data-download]').forEach((link) => {
        link.href = apk.browser_download_url;
      });
      const version = typeof release.tag_name === 'string' ? release.tag_name.replace(/-build\..*$/, '') : '最新版本';
      const size = Number.isFinite(apk.size) && apk.size > 0 ? ` · ${(apk.size / 1024 / 1024).toFixed(1)} MB` : '';
      document.querySelectorAll('[data-release-meta]').forEach((meta) => {
        meta.textContent = `${version}${size} · Android 8.0+`;
      });
    } catch {
      // 离线、超时或接口限流时，继续使用静态下载入口。
    } finally {
      clearTimeout(timeout);
    }
  }
  updateDownload();
})();
