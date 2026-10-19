/*
 * Rad Widgets: one widget engine for Rad's start page and the TV home screen.
 *
 *   RadWidgets.mount(host)   host = {
 *     get(key) -> string, set(key, value),            saved settings
 *     fetch(url) -> Promise<string>,                    network (the host's own way round browser limits)
 *     calendar() -> Promise<[{title,start,end,allDay,color}]>   (optional)
 *     photos() -> [url],  open(what)   (optional)
 *   }
 *   RadWidgets.add()               opens the "Add a widget" sheet
 *   RadWidgets.hold(x, y) -> bool  a long press at a point: opens that widget's options, true if one was there
 *   RadWidgets.back() -> bool      Back pressed: closes a sheet or cancels a move, true if it did
 *
 * Every widget has a size (s, m, l), a style (clear, smoke, blue, pink, green), a position, and its own options.
 * Moving: choose Move in the options; the widget follows the pointer (or the arrow keys) and OK drops it.
 */
(function () {
  const KINDS = {
    clock:     { name: 'Clock', about: 'The time, plus a second city', size: 's' },
    weather:   { name: 'Weather', about: 'Now and the next days', size: 'm' },
    scores:    { name: 'Scores', about: 'Every game today, turning like a wheel', size: 'm' },
    calendar:  { name: 'Calendar', about: 'What is coming up', size: 'm' },
    countdown: { name: 'Countdown', about: 'Days until a date', size: 's' },
    photo:     { name: 'Photo', about: 'A rotating picture', size: 'm' },
    note:      { name: 'Note', about: 'A sticky note', size: 's' },
    speed:     { name: 'Speed test', about: 'Download speed and ping', size: 's' },
  };
  const LEAGUES = [['NFL', 'football/nfl'], ['NBA', 'basketball/nba'], ['MLB', 'baseball/mlb'], ['NHL', 'hockey/nhl'],
    ['NCAAF', 'football/college-football'], ['NCAAB', 'basketball/mens-college-basketball'], ['WNBA', 'basketball/wnba'],
    ['MLS', 'soccer/usa.1'], ['EPL', 'soccer/eng.1']];
  const CITIES = [['Here', ''], ['New York', 'America/New_York'], ['Chicago', 'America/Chicago'], ['London', 'Europe/London'],
    ['Paris', 'Europe/Paris'], ['Dubai', 'Asia/Dubai'], ['Tokyo', 'Asia/Tokyo'], ['Sydney', 'Australia/Sydney']];
  const STYLES = [['clear', 'Clear'], ['smoke', 'Smoked'], ['tint-blue', 'Blue'], ['tint-pink', 'Sunset'], ['tint-green', 'Mint']];
  const wmo = c => c === 0 ? 'Clear' : c <= 2 ? 'Partly cloudy' : c === 3 ? 'Cloudy' : (c === 45 || c === 48) ? 'Fog'
    : c <= 57 ? 'Drizzle' : c <= 67 ? 'Rain' : c <= 77 ? 'Snow' : c <= 82 ? 'Showers' : c <= 86 ? 'Snow showers' : 'Storms';
  const icon = c => c === 0 ? '☀️' : c <= 2 ? '⛅' : c === 3 ? '☁️' : c <= 48 ? '🌫️'
    : c <= 67 || (c >= 80 && c <= 82) ? '🌧️' : c <= 86 ? '❄️' : '⛈️';
  const el = (tag, cls, text) => { const e = document.createElement(tag); if (cls) e.className = cls; if (text != null) e.textContent = text; return e; };
  const hm = (d, tz) => d.toLocaleTimeString('en-US', { hour: 'numeric', minute: '2-digit', timeZone: tz || undefined });

  let host, list = [], layer, sheet, hint, moving = null, timers = [];
  const save = () => host.set('rw.widgets', JSON.stringify(list));

  // ---- placing: new widgets go top right, down the right edge, then the next column in
  function freeSpot(size) {
    const W = innerWidth, H = innerHeight, w = size === 's' ? 150 : 300, h = size === 'l' ? 196 : 92;
    for (let col = 0; col < 4; col++) for (let y = 128; y + h < H - 140; y += 20) {
      const x = W - 40 - w - col * 316;
      const box = { x, y, w, h };
      const hit = list.some(o => { const r = rectOf(o); return !(r.x + r.w + 12 < box.x || box.x + box.w + 12 < r.x || r.y + r.h + 12 < box.y || box.y + box.h + 12 < r.y); });
      if (!hit) return { x: x / W * 100, y: y / H * 100 };
    }
    return { x: 50, y: 30 };
  }
  function rectOf(o) {
    return { x: o.x / 100 * innerWidth, y: o.y / 100 * innerHeight, w: o.size === 's' ? 150 : 300, h: o.size === 'l' ? 196 : 92 };
  }

  // ---- drawing
  function render() {
    timers.forEach(clearInterval);
    timers = [];
    layer.innerHTML = '';
    list.forEach(o => {
      const w = el('div', 'rw ' + o.size + (o.style && o.style !== 'clear' ? ' ' + o.style : ''));
      w.dataset.id = o.id;
      w.style.left = o.x + 'vw';
      w.style.top = o.y + 'vh';
      layer.append(w);
      const draw = () => { if (w.dataset.busy) return; try { FILL[o.type](w, o); } catch (e) { w.textContent = ''; } };
      draw();
      timers.push(setInterval(draw, o.type === 'clock' || o.type === 'countdown' ? 20000 : o.type === 'scores' ? 60000 : 900000));
    });
  }

  const FILL = {
    clock(w, o) {
      const now = new Date(), tz = o.opts.tz;
      w.innerHTML = '';
      w.append(el('div', 'rw-big', hm(now).replace(/\s?[AP]M/, '')));
      w.append(el('div', 'rw-dim', now.toLocaleDateString('en-US', { weekday: 'long', month: 'short', day: 'numeric' })));
      if (tz) {
        const r = el('div', 'rw-row'); r.style.marginTop = '8px';
        r.append(el('span', 'rw-dim', (CITIES.find(c => c[1] === tz) || [tz])[0]), el('span', 'rw-sp'), el('span', 'rw-mid', hm(now, tz)));
        w.append(r);
      }
    },
    weather(w, o) {
      const lat = o.opts.lat || 39.7392, lon = o.opts.lon || -104.9903;
      host.fetch('https://api.open-meteo.com/v1/forecast?latitude=' + lat + '&longitude=' + lon + '&current=temperature_2m,weather_code'
        + '&daily=weather_code,temperature_2m_max,temperature_2m_min&temperature_unit=fahrenheit&timezone=auto&forecast_days=5').then(t => {
        const d = JSON.parse(t);
        w.innerHTML = '';
        w.append(el('h5', '', o.opts.place || 'Denver'));
        const r = el('div', 'rw-row');
        r.append(el('span', 'rw-big', Math.round(d.current.temperature_2m) + '°'), el('span', 'rw-mid', ' ' + icon(d.current.weather_code) + ' ' + wmo(d.current.weather_code)));
        w.append(r);
        if (o.size !== 's') {
          const days = el('div', 'rw-days');
          const n = o.size === 'l' ? 5 : 4;
          for (let i = 1; i < Math.min(n + 1, d.daily.time.length); i++) {
            const c = el('div');
            c.append(el('div', 'rw-dim', new Date(d.daily.time[i] + 'T12:00').toLocaleDateString('en-US', { weekday: 'short' })));
            c.append(el('div', '', icon(d.daily.weather_code[i])));
            c.append(el('div', '', Math.round(d.daily.temperature_2m_max[i]) + '°'));
            days.append(c);
          }
          if (o.size === 'm') { days.style.marginTop = '2px'; r.style.display = 'none'; w.querySelector('h5').textContent += '  ·  ' + Math.round(d.current.temperature_2m) + '° ' + wmo(d.current.weather_code); }
          w.append(days);
        }
      }).catch(() => { w.textContent = 'Weather unavailable'; });
    },
    scores(w, o) {
      // Every game today across the leagues, live first; any teams named in the options come first of all.
      const teams = (o.opts.teams || '').split(',').map(t => t.trim().toLowerCase()).filter(Boolean);
      Promise.all(LEAGUES.map(([n, p]) => host.fetch('https://site.api.espn.com/apis/site/v2/sports/' + p + '/scoreboard')
        .then(t => (JSON.parse(t || '{}').events || []).map(e => ({ n, e }))).catch(() => []))).then(all => {
        const today = new Date().toDateString();
        const mine = g => teams.length && g.e.competitions[0].competitors.some(c => {
          const tm = c.team || {};
          return teams.some(t => [tm.displayName, tm.shortDisplayName, tm.name, tm.abbreviation].filter(Boolean).some(x => x.toLowerCase().includes(t)));
        });
        const games = all.flat().filter(g => g.e.status.type.state === 'in' || new Date(g.e.date).toDateString() === today);
        const rank = g => (mine(g) ? 0 : 2) + (g.e.status.type.state === 'in' ? 0 : g.e.status.type.state === 'pre' ? 1 : 1.5);
        games.sort((a, b) => rank(a) - rank(b) || new Date(a.e.date) - new Date(b.e.date));
        w.innerHTML = '';
        w.append(el('h5', '', 'Scores'));
        if (!games.length) { w.append(el('div', 'rw-dim', 'No games today')); return; }
        // The drum: rows sit on a wheel that turns one game at a time; the middle one is full size, the
        // ones above and below tilt away and fade, the same as the scores wheel on the home screen.
        const drum = el('div', 'rw-drum');
        const rows = games.map(({ n, e }) => {
          const st = e.status.type, c = e.competitions[0].competitors;
          const away = c.find(x => x.homeAway === 'away') || c[0], home = c.find(x => x.homeAway === 'home') || c[1];
          const g = el('div', 'rw-game');
          const side = t => { const s = el('span', 'rw-row'); const im = el('img', 'rw-logo'); im.onerror = () => im.remove(); im.src = (t.team || {}).logo || ''; s.append(im, el('b', '', (t.team || {}).abbreviation || '')); if (st.state !== 'pre') s.append(el('span', '', t.score)); return s; };
          g.append(el('span', 'rw-lg', n), side(away), el('span', 'rw-dim', '@'), side(home), el('span', 'rw-sp'));
          g.append(el('span', st.state === 'in' ? 'rw-live' : 'rw-dim', st.state === 'pre' ? hm(new Date(e.date)) : st.shortDetail));
          drum.append(g);
          return g;
        });
        w.append(drum);
        const rowH = 26, n = rows.length;
        o._off = (o._off || 0) % n;
        const place = () => rows.forEach((r, i) => {
          let d = i - o._off;
          if (n > 2) { while (d > n / 2) d -= n; while (d < -n / 2) d += n; }
          const a = Math.min(1, Math.abs(d));
          r.style.transform = 'translateY(' + (d * rowH) + 'px) rotateX(' + (-d * 38) + 'deg) scale(' + (1 - 0.12 * a) + ')';
          r.style.opacity = Math.abs(d) > 1.2 ? 0 : 1 - 0.6 * a;
        });
        rows.forEach(r => r.style.transition = 'none');
        place();
        requestAnimationFrame(() => rows.forEach(r => r.style.transition = ''));
        if (n > 1) {
          clearInterval(w._turn);
          w._turn = setInterval(() => {
            if (!w.isConnected) { clearInterval(w._turn); return; }
            const before = o._off;
            o._off = (o._off + 1) % n;
            // the row that wraps from top to bottom jumps unseen
            rows.forEach((r, i) => { let d = i - before; if (n > 2) { while (d > n / 2) d -= n; while (d < -n / 2) d += n; }
              r.style.transition = d <= -n / 2 + 1 && n > 3 ? 'none' : ''; });
            place();
          }, 3500);
        }
      });
    },
    calendar(w, o) {
      if (!host.calendar) { w.textContent = 'Calendar not available here'; return; }
      host.calendar().then(evs => {
        w.innerHTML = '';
        w.append(el('h5', '', 'Up next'));
        const now = Date.now(), max = o.size === 'l' ? 6 : o.size === 'm' ? 2 : 1;
        const list2 = (evs || []).filter(e => e.end > now).sort((a, b) => a.start - b.start).slice(0, max);
        if (!list2.length) { w.append(el('div', 'rw-dim', 'Nothing coming up')); return; }
        list2.forEach(e => {
          const r = el('div', 'rw-ev');
          const bar = el('i'); bar.style.background = e.color || '#0a84ff';
          const d = new Date(e.start), today = new Date().toDateString() === d.toDateString();
          r.append(bar, el('span', '', e.title), el('span', 'rw-sp'),
            el('span', 'rw-dim', (today ? '' : d.toLocaleDateString('en-US', { weekday: 'short' }) + ' ') + (e.allDay ? 'All day' : hm(d))));
          w.append(r);
        });
      }).catch(() => { w.textContent = 'Calendar unavailable'; });
    },
    countdown(w, o) {
      const to = o.opts.date ? new Date(o.opts.date + 'T00:00') : null;
      w.innerHTML = '';
      w.append(el('h5', '', o.opts.title || 'Countdown'));
      if (!to || isNaN(to)) { w.append(el('div', 'rw-dim', 'Hold OK to pick a date')); return; }
      const ms = to - Date.now(), days = Math.ceil(ms / 864e5);
      w.append(el('div', 'rw-count', ms <= 0 ? 'Today!' : days + (days === 1 ? ' day' : ' days')));
      w.append(el('div', 'rw-dim', to.toLocaleDateString('en-US', { weekday: 'short', month: 'short', day: 'numeric' })));
    },
    photo(w, o) {
      const ph = (host.photos && host.photos()) || [];
      w.innerHTML = '';
      const p = el('div', 'rw-photo');
      if (ph.length) p.style.backgroundImage = 'url("' + ph[Math.floor(Math.random() * ph.length)] + '")';
      w.append(p);
    },
    note(w, o) {
      if (w.querySelector('textarea')) return;   // keep what is being typed
      w.innerHTML = '';
      w.append(el('h5', '', 'Note'));
      const t = el('textarea');
      t.value = o.opts.text || '';
      t.placeholder = 'Click to type';
      t.oninput = () => { o.opts.text = t.value; save(); };
      w.append(t);
    },
    speed(w, o) {
      const draw = (state) => {
        w.innerHTML = '';
        w.append(el('h5', '', 'Internet speed'));
        if (state === 'run') { w.append(el('div', 'rw-count', '\u2026'), el('div', 'rw-dim', 'Testing')); return; }
        const r = o.opts.last;
        if (!r) { w.append(el('div', 'rw-dim', 'Click to test')); return; }
        const row = el('div', 'rw-row');
        row.append(el('span', 'rw-count', String(r.down)), el('span', 'rw-dim', 'Mbps'));
        w.append(row, el('div', 'rw-dim', 'Ping ' + r.ping + ' ms  \u00B7  ' + new Date(r.at).toLocaleTimeString('en-US', { hour: 'numeric', minute: '2-digit' })));
      };
      draw();
      w.onclick = () => {
        if (!host.speed || w.dataset.busy) return;
        w.dataset.busy = '1';
        draw('run');
        host.speed().then(r => { o.opts.last = { down: r.down, ping: r.ping, at: Date.now() }; save(); }).catch(() => {})
          .finally(() => { delete w.dataset.busy; draw(); });
      };
    },
  };

  // ---- sheets
  function openSheet(build) {
    sheet.innerHTML = '';
    const card = el('div', 'rw-card');
    build(card);
    sheet.append(card);
    sheet.classList.add('on');
  }
  function closeSheet() { sheet.classList.remove('on'); sheet.innerHTML = ''; }
  function chips(card, label, options, current, pick) {
    card.append(el('div', 'lbl', label));
    const box = el('div', 'rw-chips');
    options.forEach(([v, t]) => {
      const c = el('div', 'rw-chip' + (v === current ? ' on' : ''), t);
      c.onclick = () => { pick(v); [...box.children].forEach(x => x.classList.remove('on')); c.classList.add('on'); };
      box.append(c);
    });
    card.append(box);
  }
  function field(card, label, value, placeholder, onSave) {
    card.append(el('div', 'lbl', label));
    const i = el('input');
    i.value = value || '';
    i.placeholder = placeholder || '';
    i.onchange = () => onSave(i.value.trim());
    card.append(i);
    return i;
  }

  function add() {
    openSheet(card => {
      card.append(el('h3', '', 'Add a widget'));
      const g = el('div', 'rw-grid');
      Object.entries(KINDS).forEach(([k, v]) => {
        if (k === 'calendar' && !host.calendar) return;
        if (k === 'speed' && !host.speed) return;
        const p = el('div', 'rw-pick');
        p.append(el('b', '', v.name), el('span', '', v.about));
        p.onclick = () => {
          const spot = freeSpot(v.size);
          list.push({ id: 'w' + Date.now(), type: k, size: v.size, style: 'clear', x: spot.x, y: spot.y, opts: {} });
          save(); render(); closeSheet();
        };
        g.append(p);
      });
      card.append(g);
      const f = el('div', 'rw-foot'); f.append(el('span'), btn('Close', closeSheet)); card.append(f);
    });
  }
  const btn = (t, fn) => { const b = el('div', 'rw-chip', t); b.onclick = fn; return b; };

  function options(o) {
    openSheet(card => {
      card.append(el('h3', '', KINDS[o.type].name));
      chips(card, 'Size', [['s', 'Small'], ['m', 'Medium'], ['l', 'Large']], o.size, v => { o.size = v; save(); render(); });
      chips(card, 'Style', STYLES, o.style || 'clear', v => { o.style = v; save(); render(); });
      if (o.type === 'clock') chips(card, 'Second city', CITIES.map(c => [c[1], c[0]]), o.opts.tz || '', v => { o.opts.tz = v; save(); render(); });
      if (o.type === 'scores') field(card, 'Teams to show first (optional, comma separated)', o.opts.teams || '', 'Steelers, Warriors, Lakers', v => { o.opts.teams = v; save(); render(); });
      if (o.type === 'countdown') {
        field(card, 'Name', o.opts.title, 'Birthday', v => { o.opts.title = v; save(); render(); });
        const d = field(card, 'Date (YYYY-MM-DD)', o.opts.date, '2026-12-25', v => { o.opts.date = v; save(); render(); });
        d.type = 'text';
      }
      if (o.type === 'weather') field(card, 'City', o.opts.place, 'Denver', v => {
        host.fetch('https://geocoding-api.open-meteo.com/v1/search?count=1&name=' + encodeURIComponent(v)).then(t => {
          const r = (JSON.parse(t).results || [])[0];
          if (r) { o.opts.place = r.name; o.opts.lat = r.latitude; o.opts.lon = r.longitude; save(); render(); }
        });
      });
      const f = el('div', 'rw-foot');
      const left = el('div', 'rw-chips');
      left.append(btn('Move', () => { closeSheet(); startMove(o); }), btn('Remove', () => { list = list.filter(x => x !== o); save(); render(); closeSheet(); }));
      f.append(left, btn('Done', closeSheet));
      card.append(f);
    });
  }

  // ---- moving: the widget follows the pointer (or arrows); OK / click drops it, Back cancels
  let moveFrom = null, held = 0, dim;
  function startMove(o) {
    moving = o;
    moveFrom = { x: o.x, y: o.y };
    const w = layer.querySelector('[data-id="' + o.id + '"]');
    if (w) w.classList.add('moving');
    held = 0;
    dim.classList.add('on');
    layer.classList.add('moving');
    if (host.keys) host.keys(true);            // the arrows come to the page while moving, the pointer rests
    hint.textContent = 'Move it with the arrows  ·  OK to place  ·  Back to cancel';
    hint.classList.add('on');
  }
  function endMove(keep) {
    if (!moving) return;
    if (!keep) { moving.x = moveFrom.x; moving.y = moveFrom.y; }
    moving = null;
    dim.classList.remove('on');
    layer.classList.remove('moving');
    if (host.keys) host.keys(false);
    hint.classList.remove('on');
    save(); render();
  }
  function moveTo(px, py) {
    if (!moving) return;
    const r = rectOf(moving), m = 12;
    moving.x = Math.max(m, Math.min(innerWidth - r.w - m, px - r.w / 2)) / innerWidth * 100;
    moving.y = Math.max(m, Math.min(innerHeight - r.h - m, py - r.h / 2)) / innerHeight * 100;
    const w = layer.querySelector('[data-id="' + moving.id + '"]');
    if (w) { w.style.left = moving.x + 'vw'; w.style.top = moving.y + 'vh'; }
  }

  function mount(h) {
    host = h;
    try { list = JSON.parse(host.get('rw.widgets') || '[]'); } catch (e) { list = []; }
    layer = el('div', 'rw-layer');
    sheet = el('div', 'rw-sheet');
    hint = el('div', 'rw-hint');
    dim = el('div', 'rw-movedim');
    document.body.append(layer, sheet, hint);
    layer.before(dim);
    sheet.addEventListener('click', e => { if (e.target === sheet) closeSheet(); });
    addEventListener('click', e => { if (moving) { e.preventDefault(); e.stopPropagation(); endMove(true); } }, true);
    addEventListener('keydown', e => {
      if (!moving) return;
      // like the home screen: 10 px a press, picking up speed while an arrow is held
      held = e.repeat ? held + 1 : 0;
      const r = rectOf(moving), step = 10 + Math.min(held, 12) * 3;
      const k = e.key;
      if (k === 'ArrowLeft') moveTo(r.x + r.w / 2 - step, r.y + r.h / 2);
      else if (k === 'ArrowRight') moveTo(r.x + r.w / 2 + step, r.y + r.h / 2);
      else if (k === 'ArrowUp') moveTo(r.x + r.w / 2, r.y + r.h / 2 - step);
      else if (k === 'ArrowDown') moveTo(r.x + r.w / 2, r.y + r.h / 2 + step);
      else if (k === 'Enter') endMove(true);
      else return;
      e.preventDefault();
    }, true);
    addEventListener('resize', render);
    render();
  }

  window.RadWidgets = {
    mount, add,
    hold(x, y) {
      const e = document.elementFromPoint(x, y), w = e && e.closest && e.closest('.rw');
      if (!w) return false;
      const o = list.find(i => i.id === w.dataset.id);
      if (o) options(o);
      return !!o;
    },
    back() {
      if (moving) { endMove(false); return true; }
      if (sheet.classList.contains('on')) { closeSheet(); return true; }
      return false;
    },
    count: () => list.length,
  };
})();
