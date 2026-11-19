// Polar Client launcher redesign: small UI additions that sit on top of app.js.
// app.js keeps all of the launcher's behaviour; this file only adds the
// Discord links, the offline banner, the play-button details and the
// "Ask on Discord" button in the crash helper.
(() => {
  'use strict';
  const DISCORD = 'https://discord.gg/3HxaywjbT4';
  const $ = (id) => document.getElementById(id);
  const openDiscord = () => { try { polar.openLink(DISCORD); } catch (e) { /* bridge missing */ } };

  // Every [data-discord] button opens the Polar Discord in the browser.
  document.addEventListener('click', (e) => {
    if (e.target.closest('[data-discord]')) { e.preventDefault(); openDiscord(); }
  });

  // ------------------------------------------------------------ offline banner
  const banner = $('offlineBanner');
  const syncOnline = () => { if (banner) banner.hidden = navigator.onLine !== false; };
  window.addEventListener('online', syncOnline);
  window.addEventListener('offline', syncOnline);
  syncOnline();

  // ------------------------------------------------------------ play button
  // app.js drives the button (label, fill, disabled, .running). Here we add:
  //  - a small second line while the game is getting ready (what it is doing + %)
  //  - "Playing · 12m" and a Stop button while the game is open
  const play = $('playBtn');
  const label = $('playLabel');
  const fill = $('playFill');
  if (play && label && fill) {
    const sub = document.createElement('span');
    sub.className = 'play-sub';
    sub.id = 'playSub';
    label.after(sub);

    const stop = document.createElement('button');
    stop.className = 'stop-btn';
    stop.id = 'playStop';
    stop.type = 'button';
    stop.hidden = true;
    stop.innerHTML = '<svg><use href="#i-stop"/></svg><span>Stop</span>';
    stop.addEventListener('click', () => { const k = $('killGame'); if (k && !k.disabled) k.click(); });
    play.after(stop);

    let startedAt = 0;
    let timer = 0;
    const fmt = (ms) => {
      const m = Math.max(0, Math.floor(ms / 60000));
      return m < 1 ? 'just started' : m < 60 ? `${m}m` : `${Math.floor(m / 60)}h ${String(m % 60).padStart(2, '0')}m`;
    };
    const tick = () => { if (play.classList.contains('running')) label.textContent = `PLAYING · ${fmt(Date.now() - startedAt)}`; };

    const sync = () => {
      const running = play.classList.contains('running');
      const busy = play.disabled && !running;
      play.classList.toggle('busy', busy);
      stop.hidden = !running;
      if (running && !startedAt) {
        startedAt = Date.now();
        tick();
        timer = setInterval(tick, 20000);
      } else if (!running && startedAt) {
        startedAt = 0;
        clearInterval(timer);
      }
      if (busy) {
        const pct = Math.round(parseFloat(fill.style.width) || 0);
        const what = ($('statusMain') && $('statusMain').textContent) || '';
        sub.textContent = pct > 1 ? `${what} · ${pct}%` : what;
      } else {
        sub.textContent = '';
      }
    };
    const watch = new MutationObserver(sync);
    watch.observe(play, { attributes: true, attributeFilter: ['class', 'disabled'] });
    watch.observe(fill, { attributes: true, attributeFilter: ['style'] });
    if ($('statusMain')) watch.observe($('statusMain'), { childList: true, characterData: true, subtree: true });
    sync();
  }

  // ------------------------------------------------------------ crash helper
  const crashRow = document.querySelector('#crashModal .row-actions');
  if (crashRow && !crashRow.querySelector('[data-discord]')) {
    const ask = document.createElement('button');
    ask.className = 'btn ghost';
    ask.type = 'button';
    ask.setAttribute('data-discord', '');
    ask.innerHTML = '<svg><use href="#i-chat"/></svg>Ask on Discord';
    crashRow.prepend(ask);
  }
})();
