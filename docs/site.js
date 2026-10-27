(() => {
  const tabs = [...document.querySelectorAll('.screen-tab')];
  const image = document.querySelector('[data-screen-image]');
  const caption = document.querySelector('[data-screen-caption]');
  const panel = document.getElementById('screen-panel');

  function activateTab(tab, focus = false) {
    if (!image || !caption || !panel) return;
    for (const item of tabs) {
      const active = item === tab;
      item.classList.toggle('is-active', active);
      item.setAttribute('aria-selected', String(active));
      item.tabIndex = active ? 0 : -1;
    }
    image.src = tab.dataset.image;
    image.alt = tab.dataset.alt;
    caption.textContent = tab.dataset.caption;
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
})();
