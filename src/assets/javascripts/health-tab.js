/*
 * HVM Cluster Health - cluster tab UI. Served as a plugin asset (not inline) because Morpheus inserts tab HTML in ways that
 * never execute inline scripts. Starts through Morpheus's React loader markers, or by scanning the page for .hvm-health.
 * All data comes from the plugin controller under /plugin/hvm-health/*.
 */
(function () {
  'use strict';
  window.Morpheus = window.Morpheus || {};
  window.Morpheus.pluginTabs = window.Morpheus.pluginTabs || {};
  var ns = window.Morpheus.pluginTabs.hvmClusterHealth = window.Morpheus.pluginTabs.hvmClusterHealth || {};
  var BASE = '/plugin/hvm-health/';
  var TAB = 'hvm-cluster-health-clusterTab';

  function csrf() {
    var m = document.querySelector('meta[name="_csrf"]') || document.querySelector('meta[name="csrf-token"]');
    return m ? m.getAttribute('content') : null;
  }
  function csrfHeaders() {
    var h = {}, t = csrf(), hn = document.querySelector('meta[name="_csrf_header"]');
    if (t) h[(hn && hn.getAttribute('content')) || 'X-XSRF-TOKEN'] = t;
    return h;
  }
  var NO_PERM = 'Your role does not have the "Cluster Health" permission (Administration > Roles > your role: Read to view, Full to fix). Ask an administrator to grant it.';
  /** The token goes in the POST body, never in a GET URL (it would land in access logs). */
  function client(clusterId) {
    return function call(path, params, post) {
      params = params || {}; params.clusterId = clusterId;
      var body = new URLSearchParams();
      Object.keys(params).forEach(function (k) { if (params[k] !== undefined && params[k] !== null) body.append(k, params[k]); });
      var t = csrf();
      if (post && t) body.append('_csrf', t);
      var opts = { method: post ? 'POST' : 'GET', credentials: 'same-origin', redirect: 'manual', headers: post ? csrfHeaders() : {} };
      if (post) opts.body = body;
      return fetch(BASE + path + (post ? '' : '?' + body.toString()), opts).then(function (r) {
        if (r.type === 'opaqueredirect') throw new Error(path + ': redirected (session expired); reload the page');
        return r.text().then(function (txt) {
          var j; try { j = JSON.parse(txt); } catch (e) {
            // Morpheus answers 404 (not 403) for plugin routes the user's role is not allowed to use
            if (r.status === 404 || r.status === 403) throw new Error(NO_PERM);
            throw new Error('HTTP ' + r.status + ' from ' + path);
          }
          if (!j.success) throw new Error(j.msg || ('HTTP ' + r.status));
          return j.data;
        });
      });
    };
  }
  function el(tag, attrs, kids) {
    var e = document.createElement(tag);
    Object.keys(attrs || {}).forEach(function (k) { if (k === 'text') e.textContent = attrs[k]; else e.setAttribute(k, attrs[k]); });
    (kids || []).forEach(function (c) { if (c != null) e.appendChild(typeof c === 'string' ? document.createTextNode(c) : c); });
    return e;
  }
  function btn(label, fn, cls) { var b = el('button', { 'class': 'btn btn-sm ' + (cls || 'btn-default'), type: 'button', text: label }); b.addEventListener('click', fn); return b; }
  function table(head, rows) {
    return el('table', {}, [el('thead', {}, [el('tr', {}, head.map(function (h) { return el('th', { text: h }); }))]),
      el('tbody', {}, rows.map(function (r) { return r instanceof Node ? r : el('tr', {}, r.map(function (c) { return c instanceof Node ? el('td', {}, [c]) : el('td', { text: c == null ? '' : String(c) }); })); }))]);
  }
  function put(box, node) { box.innerHTML = ''; box.classList.remove('hh-muted'); if (node) box.appendChild(node); }
  function ago(ms) { var m = Math.round((Date.now() - ms) / 60000); return m < 1 ? 'just now' : (m < 120 ? m + ' min ago' : Math.round(m / 60) + ' h ago'); }
  var TIER = { SAFE: 'no effect on the cluster', MAINTENANCE: 'needs maintenance mode', EMERGENCY: 'emergency' };
  ns.ui = { client: client, el: el, btn: btn, table: table, put: put, ago: ago };   // for health-pages.js

  // reference: start. PluginSpec holds this block to hh- classes, and to text only.
  /**
   * What each check id means: a popup for one, and Help for all of them. A native modal dialog, so Esc, focus and its return, and staying above Morpheus's
   * page come with it. It lives under root for the tab's styles. The catalog loads once, on first use, and a failed load is tried again on the next click.
   */
  function reference(root, call) {
    var dialog, title, tools, box, loaded, shown = 0, pressed = null;
    function catalog() {
      if (!loaded) loaded = call('catalog').catch(function (e) { loaded = null; throw e; });
      return loaded;
    }
    function clear(n) { while (n.firstChild) n.removeChild(n.firstChild); }
    /** The tab sits on the page's background whichever theme is on, and a dialog needs one of its own. Only an opaque colour will do: rgb(), never rgba(). */
    function surface() {
      for (var n = root; n; n = n.parentElement) {
        var c = getComputedStyle(n).backgroundColor;
        if (/^rgb\(/.test(c)) return c;
      }
      var text = (getComputedStyle(root).color.match(/\d+/g) || [0, 0, 0]).slice(0, 3).map(Number);
      return text[0] + text[1] + text[2] > 382 ? '#1f2328' : '#ffffff';
    }
    function build() {
      title = el('h3', { 'class': 'hh-dialog-title', id: 'hh-dialog-title' });
      tools = el('div', { 'class': 'hh-dialog-tools' });
      box = el('div', { 'class': 'hh-dialog-body' });
      dialog = el('dialog', { 'class': 'hh-dialog', 'aria-labelledby': 'hh-dialog-title' }, [
        el('div', { 'class': 'hh-dialog-in' }, [el('div', { 'class': 'hh-dialog-head' }, [title, btn('Close', function () { dialog.close(); })]), tools, box])]);
      // a click on the dialog itself is on its backdrop, as its content fills it. Selecting text and letting go outside is not a click outside.
      dialog.addEventListener('mousedown', function (ev) { pressed = ev.target; });
      dialog.addEventListener('click', function (ev) { if (ev.target === dialog && pressed === dialog) dialog.close(); });
      root.appendChild(dialog);
    }
    /** Returns a number that is stale once anything else has opened, so a slow load cannot draw over it. */
    function open(heading, wide) {
      if (!dialog) build();
      title.textContent = heading;
      dialog.classList.toggle('hh-dialog-wide', wide);
      dialog.style.backgroundColor = surface();
      clear(tools);
      clear(box);
      box.appendChild(el('div', { 'class': 'hh-muted', text: 'Loading…' }));
      if (!dialog.open) dialog.showModal();
      return ++shown;
    }
    function failed(mine, e) {
      if (mine !== shown) return;
      clear(box);
      box.appendChild(el('div', { 'class': 'hh-err', text: 'The reference could not be loaded: ' + e.message }));
    }
    function field(label, text) {
      return el('div', { 'class': 'hh-ref-field' }, [el('span', { 'class': 'hh-ref-label', text: label + ': ' }), text]);
    }
    function fields(c) { return [field('What it looks at', c.what), field('Why it matters', c.why), field('What to do', c.action)]; }
    function showId(id) {
      var mine = open(id, false);
      catalog().then(function (cat) {
        if (mine !== shown) return;
        var c = cat.checks.filter(function (k) { return k.id === id; })[0];
        clear(box);
        if (!c) { box.appendChild(el('div', { text: 'This version has no reference entry for ' + id + '.' })); return; }
        title.textContent = id + ': ' + c.name;
        fields(c).forEach(function (f) { box.appendChild(f); });
      }, function (e) { failed(mine, e); });
    }
    function legend(cat) {
      return el('div', { 'class': 'hh-ref-legend' }, cat.statuses.map(function (s) {
        return el('div', { 'class': 'hh-ref-note' }, [el('span', { 'class': 'hh-ref-status hh-st-' + s.status, text: s.status.replace('_', ' ') }), ': ' + s.meaning]);
      }).concat([el('div', { 'class': 'hh-ref-note', text: 'An id starts with its area: ' + cat.areas.map(function (a) { return a.prefix + ' = ' + a.area; }).join(', ') + '.' })],
        cat.notes.map(function (n) { return el('div', { 'class': 'hh-ref-note', text: n }); })));
    }
    function showAll() {
      var mine = open('Findings reference', true);
      catalog().then(function (cat) {
        if (mine !== shown) return;
        var rows = cat.checks.map(function (c) {
          return { c: c, text: [c.id, c.area, c.name, c.what, c.why, c.action].join(' ').toLowerCase(),
            node: el('div', { 'class': 'hh-ref-entry' }, [el('div', { 'class': 'hh-ref-name', text: c.id + ': ' + c.name })].concat(fields(c))) };
        });
        var groups = cat.areas.map(function (a) {
          var own = rows.filter(function (r) { return r.c.area === a.area; });
          return { own: own, node: el('div', {}, [el('div', { 'class': 'hh-ref-area', text: a.area + ' (' + a.prefix + ')' })].concat(own.map(function (r) { return r.node; }))) };
        });
        var input = el('input', { type: 'text', 'class': 'hh-search', placeholder: 'Search by id or words', 'aria-label': 'Search the findings reference' });
        var tally = el('div', { 'class': 'hh-ref-tally', 'aria-live': 'polite' });
        var none = el('div', { 'class': 'hh-hidden hh-muted', text: 'Nothing matches. Try fewer words.' });
        function filter() {
          var words = input.value.toLowerCase().split(/\s+/).filter(Boolean), hits = 0;
          groups.forEach(function (g) {
            var any = false;
            g.own.forEach(function (r) {
              var hit = words.every(function (w) { return r.text.indexOf(w) >= 0; });
              r.node.classList.toggle('hh-hidden', !hit);
              if (hit) { any = true; hits++; }
            });
            g.node.classList.toggle('hh-hidden', !any);
          });
          none.classList.toggle('hh-hidden', hits > 0);
          tally.textContent = words.length ? hits + ' of ' + rows.length + ' checks match' : rows.length + ' checks';
        }
        input.addEventListener('input', filter);
        filter();
        clear(box);
        tools.appendChild(input);
        tools.appendChild(tally);
        box.appendChild(legend(cat));
        box.appendChild(none);
        groups.forEach(function (g) { box.appendChild(g.node); });
        input.focus();
      }, function (e) { failed(mine, e); });
    }
    function idLink(id) {
      var b = el('button', { type: 'button', 'class': 'hh-id', 'aria-haspopup': 'dialog', title: 'What is ' + id + '?', text: id });
      b.addEventListener('click', function () { showId(id); });
      return b;
    }
    /** "CLU-01, MOR-01", each one a link, as nodes to go after a label. */
    function idLinks(ids) {
      var out = [];
      ids.forEach(function (id, i) { if (i) out.push(', '); out.push(idLink(id)); });
      return out;
    }
    return { showAll: showAll, idLink: idLink, idLinks: idLinks };
  }
  // reference: end

  function init(root) {
    if (!root || root.getAttribute('data-health-init')) return;
    root.setAttribute('data-health-init', '1');
    var call = client(root.getAttribute('data-cluster-id'));
    var ref = root.hhReference || (root.hhReference = reference(root, call));   // one dialog, if Morpheus starts the tab again on this element
    var state = { status: null, view: null, showPass: false, watching: {} };
    function body(id) { return root.querySelector('[data-section="' + id + '"] .ph-body'); }
    function canFix() { return !state.status || state.status.canFix !== false; }
    function canEmergency() { return !state.status || state.status.canEmergency !== false; }

    function renderJob(j) {
      var box = root.querySelector('[data-job="' + j.id + '"]');
      if (!box) { box = el('div', { 'data-job': j.id }); body('jobs').prepend(box); }
      box.innerHTML = '';
      box.appendChild(el('div', {}, [el('strong', { text: j.kind }), ' ', el('span', { 'class': j.state === 'failed' ? 'hh-err' : 'hh-muted', text: j.state + (j.error ? ': ' + j.error : '') })]));
      box.appendChild(el('pre', { text: (j.progress || []).join('\n') }));
    }
    function follow(job, then) {
      if (state.watching[job.id]) { renderJob(job); return; }
      state.watching[job.id] = 1;
      var misses = 0;
      renderJob(job);
      (function poll(j) {
        if (j.state !== 'running') { if (then) then(j); return; }
        setTimeout(function () {
          call('job', { id: j.id }).then(function (n) { misses = 0; renderJob(n); poll(n); }, function (e) {
            if (++misses > 3) { renderJob(Object.assign({}, j, { state: 'failed', error: e.message })); return; }
            poll(j);
          });
        }, 2500);
      })(job);
    }
    function refreshAfter(j) { if (j.result && j.result.results) showView(j.result); else load(); }

    /** The way to the all-clusters page, for the master tenant, and a word if history is not being kept. */
    function summaryNotes(v) {
      var s = state.status, err = (v && v.historyError) || (s && s.historyError);
      return el('div', {}, [
        err ? el('div', { 'class': 'hh-err', text: 'History is not being saved: ' + err }) : null,
        s && s.allClustersUrl ? el('a', { href: s.allClustersUrl, text: 'All clusters' }) : null]);
    }

    function renderSummary() {
      var box = body('summary'), s = state.status, v = state.view;
      if (!v) {
        var known = s && s.known;
        var why = s && s.standingBy ? 'Another appliance node runs the automatic checks, and only it holds their findings. Run a full check to see them here.' : 'Not checked since the plugin loaded. Run a full check, or wait for the automatic one.';
        put(box, el('div', {}, [el('div', { 'class': 'hh-muted', text: (s ? (s.layout + '. ') : '') + why + (known ? ' Last known: ' + known.overall + ', ' + new Date(known.at).toLocaleString() + '.' : '') }), summaryNotes(null)]));
        return;
      }
      var auto = s ? (s.standingBy ? 'Another appliance node runs the automatic checks, so this is what was checked here by hand.' : s.monitorMinutes > 0 ? 'Automatic check every ' + s.monitorMinutes + ' min.' : 'Automatic checks are off.') : '';
      if (s && s.watch) { auto += ' Freeze watch about every ' + s.watch.seconds + ' s' + (s.watch.seconds > s.watch.setTo ? ' (set to ' + s.watch.setTo + ' s, stretched to stay within its budget of reads).' : '.'); }
      var fixes = s ? ('Automatic fixes: safe ' + (s.autoFixSafe ? 'on' : 'off') + ', in maintenance ' + (s.autoFixMaintenance ? 'on' : 'off') + ((s.autoFixSafe || s.autoFixMaintenance) ? (s.autoFixNamed ? ', and this cluster is named for them.' : ', but this cluster is not named for them, so none run here.') : '.')) : '';
      put(box, el('div', {}, [
        el('div', { 'class': 'hh-banner hh-ov-' + v.overall, text: v.overall + ': ' + v.counts.fail + ' failing, ' + v.counts.warn + ' warnings, ' + v.counts.pass + ' passing' }),
        el('div', { 'class': 'hh-muted', text: v.layout + '. Last ' + v.mode + ' check ' + ago(v.at) + '. ' + auto + ' ' + fixes }),
        (v.readiness && v.readiness.length) ? table(['Ready for', 'Status', 'Findings'], v.readiness.map(function (a) {
          var ids = a.ids || [], unread = a.notChecked || [];
          var findings = el('span', {}, ref.idLinks(ids).concat(ids.length && unread.length ? ['. '] : [], unread.length ? ['Not checked: '].concat(ref.idLinks(unread)) : []));
          return [a.area, el('span', { 'class': 'st-' + a.status, text: a.status === 'PASS' ? 'OK' : a.status.replace('_', ' ') }), findings];
        })) : null,
        summaryNotes(v)
      ]));
    }

    function fixButton(r) {
      if (!r.fix || r.tier === 'NONE' || r.status === 'PASS') return null;
      if (r.tier === 'EMERGENCY') return null;   // the emergency panel handles it, with its own permission
      if (!canFix()) return null;
      return btn('Fix', function (ev) {
        if (!window.confirm('Run "' + r.fix + '" on ' + r.host + '?\n\n' + (r.tier === 'MAINTENANCE' ? 'This needs ' + r.host + ' in maintenance mode with no VMs; the plugin checks again and refuses otherwise.' : 'This has no effect on the cluster.'))) return;
        ev.target.disabled = true;
        call('fix', { host: r.host, key: r.fix }, true).then(function (j) { follow(j, refreshAfter); }, function (e) { window.alert(e.message); ev.target.disabled = false; });
      });
    }

    function renderFindings() {
      var box = body('findings'), v = state.view;
      if (!v) { put(box, null); return; }
      var rows = v.results.filter(function (r) { return state.showPass || r.status === 'FAIL' || r.status === 'WARN'; });
      var filter = root.querySelector('[data-field="filter"]');
      filter.innerHTML = '';
      filter.appendChild(btn(state.showPass ? 'Show problems only' : 'Show everything (' + v.results.length + ')', function () { state.showPass = !state.showPass; renderFindings(); }));
      if (!rows.length) { put(box, el('div', { 'class': 'st-PASS', text: 'Nothing failing or warning.' })); return; }
      /** explainedBy: the root's id, on a finding another check explains. inGroup: its heading already says who is reached. */
      function row(r, explainedBy, inGroup) {
        var check = el('div', { 'class': inGroup ? 'hh-member' : '' }, [ref.idLink(r.id), ' ' + r.title]);
        if (explainedBy) check.appendChild(el('div', { 'class': 'hh-impact', text: 'Explained by ' + explainedBy + ' while that is open.' }));
        if (r.hint) check.appendChild(el('div', { 'class': 'hh-hint', text: r.hint }));
        if (r.reach && !inGroup) check.appendChild(el('div', { 'class': 'hh-impact', text: sentence(r.reach.text) }));
        // the older text names the VMs of a host that did not answer, which reach only counts
        if (r.impact && (!r.reach || r.reach.unknownState)) check.appendChild(el('div', { 'class': 'hh-impact', text: 'Affects: ' + r.impact }));
        var f = fixButton(r);
        var act = f ? el('div', {}, [f, el('span', { 'class': 'hh-tier', text: TIER[r.tier] || '' })]) : '';
        return [el('span', { 'class': 'hh-nw hh-word st-' + r.status, text: r.status.replace('_', ' ') }), el('span', { 'class': 'hh-nw hh-word', text: r.severity }), check, el('span', { 'class': 'hh-nw', text: r.host || 'cluster' }), r.observed, r.expected, el('span', { 'class': 'hh-nw hh-word', text: r.source }), r.firstSeen ? el('span', { 'class': 'hh-nw hh-word', text: new Date(r.firstSeen).toLocaleString() }) : '', act];
      }
      var head = ['Status', 'Severity', 'Check', 'Host', 'Observed', 'Expected', 'Source', 'First seen', ''];
      if (!v.incidents) { put(box, table(head, rows.map(function (r) { return row(r); }))); return; }
      // every finding is listed, grouped: anything no group holds still gets its row
      var out = [], placed = {};
      v.incidents.forEach(function (g) {
        var root = v.results[g.members[0]], many = g.members.length > 1, firstSymptom = g.members.length - g.symptoms;
        if (many) out.push(incidentRow(g, root));
        g.members.forEach(function (at, n) {
          placed[at] = 1;
          out.push(row(v.results[at], n >= firstSymptom ? root.id : null, many));
        });
      });
      v.results.forEach(function (r, at) { if (!placed[at] && rows.indexOf(r) >= 0) out.push(row(r)); });
      put(box, table(head, out));
    }

    /** The heading of a group: the check, the hosts it is on, how much it explains, and who it reaches. */
    function incidentRow(g, root) {
      var on = g.rootHosts.length ? 'on ' + g.rootHosts.join(', ') : 'on the cluster';
      var also = g.symptoms ? ', and explains ' + g.symptoms + ' related finding' + (g.symptoms === 1 ? '' : 's') : '';
      return el('tr', { 'class': 'hh-incident' }, [el('td', { colspan: '9' }, [
        el('span', { 'class': 'hh-nw st-' + g.status, text: g.status.replace('_', ' ') }), ' ', ref.idLink(root.id), ' ' + root.title + ' ' + on + also + '.',
        g.reach ? el('div', { 'class': 'hh-impact', text: sentence(g.reach.text) }) : null])]);
    }

    function sentence(s) { return s.charAt(0).toUpperCase() + s.slice(1) + '.'; }

    /** Who is working on the cluster and why, and the way to start or end that. Only drawn again when it changed, so what someone is typing survives a refresh. */
    function renderWindow() {
      var s = state.status, w = s && s.window && s.window.endsAt > Date.now() ? s.window : null, shown = JSON.stringify([w, canFix()]);
      if (!s || shown === state.windowShown) return;
      state.windowShown = shown;
      clearTimeout(state.windowTimer);
      var box = body('window');
      function changed(next) { s.window = next; renderWindow(); }
      if (w) {
        state.windowTimer = setTimeout(renderWindow, Math.min(w.endsAt - Date.now() + 1000, 2147483647));   // it ends by itself, so the banner must too
        put(box, el('div', { 'class': 'hh-window' }, [
          el('strong', { text: 'Change window: paging held until ' + new Date(w.endsAt).toLocaleString() + '.' }),
          el('div', { text: w.by + ': ' + w.note }),
          el('div', { 'class': 'hh-muted', text: 'Started ' + new Date(w.startedAt).toLocaleString() + '. Checks, alarms and history carry on. Freezes, withdrawn filesystems and isolation failures still page.' }),
          canFix() ? el('div', { 'class': 'hh-row' }, [btn('End window', function (ev) {
            if (!window.confirm('End the change window? Paging goes back to normal at once.')) return;
            ev.target.disabled = true;
            call('window-end', {}, true).then(function () { changed(null); }, function (e) { ev.target.disabled = false; window.alert(e.message); });
          })]) : null]));
        return;
      }
      if (!canFix()) { put(box, null); return; }
      var minutes = el('input', { type: 'number', min: '1', value: '60', 'class': 'hh-window-minutes', 'aria-label': 'Minutes' });
      var note = el('input', { type: 'text', maxlength: '200', 'class': 'hh-window-note', placeholder: 'What the work is', 'aria-label': 'What the work is' });
      var start = btn('Start window', function () {
        start.disabled = true;
        call('window-start', { minutes: minutes.value, note: note.value }, true).then(changed, function (e) { start.disabled = false; window.alert(e.message); });
      });
      put(box, el('div', { 'class': 'hh-row' }, [start, minutes, el('span', { 'class': 'hh-muted', text: 'minutes, at most ' + s.maxWindowHours + ' hours. Holds paging during planned work, and ends by itself. Checks and alarms carry on.' }), note]));
    }

    /** What is loading a slow host, when the check read it. Not every check does, so no data means no panel, never a quiet host. */
    function renderBusiest() {
      var list = (state.view && state.view.busiest) || [];
      if (!list.length) { put(body('busiest'), null); return; }
      function val(x, unit) { return x == null ? 'not read' : x + (unit || ''); }
      put(body('busiest'), el('div', {}, [
        el('h3', { text: 'Busiest VMs' }),
        el('div', { 'class': 'hh-muted', text: 'The VMs using the most CPU and I/O on each host read, over 2 s. For operators: it is left out of the report.' }),
        table(['Host', 'VM', 'Tenant', 'CPU', 'Read IOPS', 'Write IOPS', 'Read MB/s', 'Write MB/s'], list.map(function (x) {
          var b = Array.isArray(x) ? { host: x[0], vm: x[1], tenant: x[2], cpuPct: x[3], readIops: x[4], writeIops: x[5], readMBs: x[6], writeMBs: x[7] } : x;
          return [el('span', { 'class': 'hh-nw', text: b.host }), b.vm, b.tenant || '', val(b.cpuPct, '%'), val(b.readIops), val(b.writeIops), val(b.readMBs), val(b.writeMBs)];
        }))]));
    }

    /** What the cluster's time source is, what each host really does, and where to define it. */
    function renderTime() {
      var box = body('time'), v = state.view, t = v && v.time;
      if (!t) { put(box, el('div', { 'class': 'hh-muted', text: 'Run a check to see what each host follows.' })); return; }
      var head = t.defined && t.defined.length
        ? 'Cluster time source: ' + t.defined.join(', ') + ' (' + (t.definedBy === 'cluster' ? 'defined here' : (t.definedBy === 'site' ? 'from the site standards' : 'from the layout settings')) + ').'
        : 'No time source is defined for this cluster, so hosts are only checked against whatever they follow.';
      var drift = t.driftMs != null ? 'Hosts are ' + t.driftMs + ' ms apart, measured against ' + t.driftServers + ' shared time server(s).' : 'The hosts share no time server, so drift between them can only be compared roughly.';
      var wrap = el('div', {}, [el('div', { text: head }), el('div', { 'class': 'hh-muted', text: drift + (t.driftNote ? ' ' + t.driftNote : '') })]);
      if (canFix()) {
        var input = el('input', { type: 'text', 'class': 'hh-time-input', placeholder: 'ntp1.example.net, ntp2.example.net' });
        input.value = t.definedBy === 'cluster' ? t.defined.join(', ') : '';
        var save = btn('Save and verify', function () {
          save.disabled = true;
          call('time-source', { servers: input.value }, true).then(function (j) { follow(j, function (d) { save.disabled = false; refreshAfter(d); }); }, function (e) { save.disabled = false; window.alert(e.message); });
        });
        wrap.appendChild(el('div', { 'class': 'hh-row' }, [input, save, el('span', { 'class': 'hh-muted', text: 'Hosts are verified against this. Nothing changes on a host until you run its fix, in maintenance mode.' })]));
      }
      wrap.appendChild(table(['Host', 'Time source', 'Daemon', 'Following now', 'Configured', 'Synced', 'Offset from source', 'Drift from other hosts', 'Stratum'], t.hosts.map(function (h) {
        return [el('span', { 'class': 'hh-nw', text: h.name }), el('span', { 'class': 'hh-word ' + (h.state === 'cluster source' ? 'hh-nw st-PASS' : (h.state === 'none configured' ? 'hh-nw st-FAIL' : 'hh-nw st-WARN')), text: h.state }),
          el('span', { 'class': 'hh-word', text: h.daemon || 'none' }), h.following || 'nothing', (h.configured || []).join(', ') || 'none', h.synced === true ? 'yes' : (h.synced === false ? el('span', { 'class': 'st-FAIL', text: 'NO' }) : '?'),
          h.offsetMs != null ? h.offsetMs + ' ms' : '', h.driftMs != null ? (h.driftMs > 0 ? '+' : '') + h.driftMs + ' ms' : '', h.stratum == null ? '' : h.stratum];
      })));
      put(box, wrap);
    }

    /** The answer to "can I take this host down?", and the way to take it out of the cluster. */
    function renderPreflight(box, p) {
      box.innerHTML = '';
      var d = el('div', { 'class': 'hh-panel' }, [
        el('div', {}, [el('span', { 'class': 'hh-v-' + p.verdict, text: p.host + ': ' + (p.verdict === 'GO' ? 'can be taken down' : (p.verdict === 'CAUTION' ? 'can be taken down, with care' : 'do not take it down yet')) })]),
        el('div', { 'class': 'hh-muted', text: 'On it now: ' + p.impact + '. ' + (p.votesAfter != null ? 'Without it: ' + p.votesAfter + ' votes, ' + p.quorum + ' needed.' : '') })]);
      (p.blockers || []).forEach(function (b) { d.appendChild(el('div', { 'class': 'hh-err', text: b })); });
      (p.cautions || []).forEach(function (b) { d.appendChild(el('div', { 'class': 'hh-hint', text: b })); });
      if (p.moves && p.moves.length) d.appendChild(table(['VM', 'Tenant', 'Memory', 'Can move to'], p.moves.map(function (m) {
        return [m.vm, m.tenant || '', m.memoryGiB + ' GiB', m.targets.length ? m.targets.join(', ') : el('span', { 'class': 'st-FAIL', text: 'nowhere' })];
      })));
      var e = p.removal || {};
      d.appendChild(el('h4', { text: 'Removing it from the cluster' }));
      d.appendChild(el('div', { 'class': 'hh-hint', text: 'Maintenance mode empties a host but leaves it a cluster member. If it then powers off, every GFS2 datastore on the other hosts stalls for about 2 minutes, and freezes if it is not acknowledged. Morpheus\'s remove leaves cleanly, but carries on if GFS2 is in use, so this checks first and then asks Morpheus. Morpheus\'s own Remove button skips these checks.' }));
      d.appendChild(el('ol', {}, (e.steps || []).map(function (s) { return el('li', { text: s }); })));
      if (e.warning) d.appendChild(el('div', { 'class': 'hh-hint', text: e.warning }));
      (e.blockers || []).forEach(function (b) { d.appendChild(el('div', { 'class': 'hh-err', text: 'Before removing: ' + b })); });
      if (e.ready && canEmergency()) {
        var typed = el('input', { type: 'text', placeholder: 'Type ' + p.host });
        d.appendChild(el('div', { 'class': 'hh-row' }, [typed, btn('Remove safely', function (ev) {
          ev.target.disabled = true;
          call('remove-host', { host: p.host, confirm: typed.value }, true).then(function (j) { follow(j, refreshAfter); }, function (x) { window.alert(x.message); ev.target.disabled = false; });
        }, 'btn-danger')]));
      }
      box.appendChild(d);
    }

    /** The host's management controller, read now through ipmitool: status, inventory, network and recent events, and the way to install the tool or reset the controller. */
    function controllerPanel(box, host) {
      box.innerHTML = ''; box.appendChild(el('div', { 'class': 'hh-panel hh-muted', text: 'Reading the management controller of ' + host + '…' }));
      call('controller', { host: host }).then(function (b) { renderController(box, host, b); }, function (e) { box.innerHTML = ''; box.appendChild(el('div', { 'class': 'hh-panel hh-err', text: e.message })); });
    }
    function kvTable(pairs) {
      var rows = pairs.filter(function (p) { return p[1] != null && p[1] !== ''; });
      return rows.length ? table(['', ''], rows) : el('div', { 'class': 'hh-muted', text: 'Nothing read.' });
    }
    function installButton(box, host) {
      if (!canFix()) return null;
      return el('div', { 'class': 'hh-row' }, [btn('Install ipmitool', function (ev) {
        if (!window.confirm('Install ipmitool on ' + host + ' and load its driver?\n\nOnly new packages, from the repositories the host already uses: it refuses if anything else would be upgraded or removed. No effect on the cluster.')) return;
        ev.target.disabled = true;
        call('fix', { host: host, key: 'ipmitool-install' }, true).then(function (j) { follow(j, function () { controllerPanel(box, host); }); }, function (e) { window.alert(e.message); ev.target.disabled = false; });
      }), el('span', { 'class': 'hh-tier', text: TIER.SAFE + '; never automatic' })]);
    }
    function renderController(box, host, b) {
      box.innerHTML = '';
      var d = el('div', { 'class': 'hh-panel' }, [el('strong', { text: host + ': management controller' })]);
      box.appendChild(d);
      if (!b.installed) {
        d.appendChild(el('div', { text: 'ipmitool is not installed on ' + host + ', so the plugin cannot read its management controller. Morpheus-built 1.3 hosts do not include it.' }));
        d.appendChild(el('div', {}, [installButton(box, host)]));   // el skips the null a user who cannot fix gets
        return;
      }
      if (!b.answered) {
        d.appendChild(el('div', { 'class': 'hh-err', text: 'ipmitool is installed, but no management controller answered. Its driver may not be loaded, which Install also does, or the host has none, as a virtual machine does.' }));
        d.appendChild(el('div', {}, [installButton(box, host)]));
      }
      var c = b.controller || {}, ch = b.chassis || {}, log = b.eventLog || {}, wd = b.watchdog || {}, s = b.sensors || {}, inv = b.inventory || {}, n = b.network || {};
      var faults = (ch.faults || []).length ? el('span', { 'class': 'st-FAIL', text: ch.faults.join(', ') }) : 'none';
      var problems = (s.problems || []).length ? el('span', { 'class': 'st-WARN', text: s.problems.map(function (p) { return p.name + ' (' + p.status + '): ' + p.reading; }).join('; ') }) : 'none';
      d.appendChild(el('h4', { text: 'Status' }));
      d.appendChild(kvTable([['Controller', [c.manufacturer, c.firmware ? 'firmware ' + c.firmware : null, c.ipmiVersion ? 'IPMI ' + c.ipmiVersion : null].filter(Boolean).join(', ')],
        ['Self-test', c.selftest ? el('span', { 'class': c.selftest === 'passed' ? 'st-PASS' : (c.selftest === 'not implemented' ? 'hh-muted' : 'st-FAIL'), text: c.selftest }) : null],
        ['Host power', ch.power], ['Power restore policy', ch.restorePolicy], ['Chassis faults', b.chassis ? faults : null], ['Chassis intrusion', ch.intrusion],
        ['Sensors', s.ok != null ? s.ok + ' ok, ' + s.notReading + ' not fitted or not reading' : null], ['Sensors not ok', b.sensors ? problems : null],
        ['Event log', log.entries != null ? log.entries + ' entries, ' + (log.percentUsed || '?') + ' used' + (log.overflow === 'true' ? ', overflowed' : '') + (log.lastAdded ? ', last ' + log.lastAdded : '') : null],
        ['Watchdog', [wd.state, wd.action].filter(Boolean).join(', ')], ['ipmitool', b.version]]));
      d.appendChild(el('h4', { text: 'Inventory' }));
      d.appendChild(kvTable([['Server', [inv.manufacturer, inv.product || inv.productName].filter(Boolean).join(' ')], ['Serial / service tag', inv.serial || inv.productSerial],
        ['Board', inv.board], ['Board serial', inv.boardSerial], ['Part number', inv.partNumber], ['Chassis serial', inv.chassisSerial], ['Board made', inv.made], ['BIOS', inv.bios]]));
      d.appendChild(el('h4', { text: 'Controller network' }));
      d.appendChild(kvTable([['Address', n.ip ? n.ip + (n.mask ? ' / ' + n.mask : '') : null], ['Assigned by', n.source], ['Gateway', n.gateway], ['MAC', n.mac], ['VLAN', n.vlan]]));
      if ((log.recent || []).length) {
        d.appendChild(el('h4', { text: 'Latest events' }));
        d.appendChild(el('pre', { text: log.recent.join('\n') }));
      }
      if (b.answered && canFix()) {
        d.appendChild(el('h4', { text: 'Reset the controller' }));
        d.appendChild(el('div', { 'class': 'hh-hint', text: 'Restarts the management controller (iDRAC, iLO) only, not the host or its VMs. It is out of reach for a few minutes, and fans may speed up meanwhile. Refused during a GFS2 freeze, when the controller may be what proves a host is off.' }));
        var typed = el('input', { type: 'text', placeholder: 'Type ' + host });
        d.appendChild(el('div', { 'class': 'hh-row' }, [typed, btn('Reset controller', function (ev) {
          ev.target.disabled = true;
          call('controller-reset', { host: host, confirm: typed.value }, true).then(function (j) { follow(j, function () { ev.target.disabled = false; }); }, function (e) { window.alert(e.message); ev.target.disabled = false; });
        }, 'btn-danger')]));
      }
    }

    function hpeBad(c) { return c.status === 'FAIL' || c.status === 'WARN'; }
    /** One host's line of HPE's checks, opened for the list, problems first. Text only, and odd output costs that host its line, never the tab. */
    function hpeHost(h) {
      try {
        var checks = Array.isArray(h.checks) ? h.checks : [];
        if (!checks.length) return el('div', { 'class': 'hh-muted', text: h.host + ': ' + (h.note || 'nothing to show') });
        var raised = checks.filter(function (c) { return hpeBad(c) && !c.ignored; });
        var masked = checks.filter(function (c) { return hpeBad(c) && c.pacemaker; });
        var rows = checks.filter(hpeBad).concat(checks.filter(function (c) { return !hpeBad(c); })).map(function (c) {
          var known = ['PASS', 'WARN', 'FAIL', 'NA'].indexOf(c.status) >= 0;
          return [el('span', { 'class': 'hh-word', text: c.check }), el('span', { 'class': 'hh-word', text: c.type }), el('span', { 'class': known ? 'hh-nw hh-word hh-st-' + c.status : 'hh-nw hh-word hh-muted', text: c.status }), c.details,
            c.pacemaker ? 'reads Pacemaker, which HVM masks' : (c.ignored ? 'ignored by hvmcliIgnore' : '')];
        });
        return el('details', {}, [el('summary', { text: h.host + ': ' + checks.length + ' checks, ' + raised.length + ' raised' + (masked.length ? ', ' + masked.length + ' reading Pacemaker' : '') }),
          table(['Check', 'Type', 'Status', 'What HPE said', ''], rows)]);
      } catch (e) {
        return el('div', { 'class': 'hh-err', text: 'One host\'s HPE checks could not be shown.' });
      }
    }
    function hpePanel(v) {
      var hosts = Array.isArray(v.hpeChecks) ? v.hpeChecks : [];
      if (!hosts.length) return null;
      return el('div', { 'class': 'hh-panel' }, [el('strong', { text: 'HPE checks' }),
        el('div', { 'class': 'hh-muted', text: 'HPE\'s own answers from hvmcli health check, at the last full check' + (v.fullAt ? ' (' + ago(v.fullAt) + ')' : '') + '. HPE-01 names any raised outside hvmcliIgnore.' })].concat(hosts.map(hpeHost)));
    }

    function renderHosts() {
      var v = state.view;
      if (!v) { put(body('hosts'), null); return; }
      var wrap = el('div', {}), out = el('pre', {}), panel = el('div', {});
      wrap.appendChild(table(['Host', 'Answered', 'Maintenance', 'Running VMs', 'Frozen', 'Stuck tasks', 'OS', 'Kernel', ''], v.hosts.map(function (h) {
        var acts = el('div', { 'class': 'hh-row' }, [
          btn('Can I take it down?', function () {
            panel.innerHTML = ''; panel.appendChild(el('div', { 'class': 'hh-panel hh-muted', text: 'Checking ' + h.name + '…' }));
            call('preflight', { host: h.name }).then(function (p) { renderPreflight(panel, p); }, function (e) { panel.innerHTML = ''; panel.appendChild(el('div', { 'class': 'hh-panel hh-err', text: e.message })); });
          }),
          canFix() ? btn('Test VLANs', function (ev) {
            if (!window.confirm('Test every VLAN on each uplink of ' + h.name + '?\n\nThis moves its active uplink, so it needs maintenance mode with no VMs; the plugin checks again and refuses otherwise.')) return;
            ev.target.disabled = true;
            call('vlan-test', { host: h.name }, true).then(function (j) { follow(j, function () { ev.target.disabled = false; }); }, function (e) { window.alert(e.message); ev.target.disabled = false; });
          }) : null,
          btn('Management controller', function () { controllerPanel(panel, h.name); }),
          btn('Raw', function () {
            out.textContent = 'Reading ' + h.name + '…';
            call('raw', { host: h.name }).then(function (r) { out.textContent = r.output; }, function (e) { out.textContent = e.message; });
          })]);
        return [el('span', { 'class': 'hh-nw', text: h.name }), h.answered ? 'yes' : el('span', { 'class': 'st-FAIL', text: 'NO' }), h.maintenance ? 'yes' : 'no', h.running,
          h.frozen ? el('span', { 'class': 'hh-word st-FAIL', text: 'FROZEN' }) : 'no', h.dStuck, h.os || '', h.kernel || '', acts];
      })));
      wrap.appendChild(panel);
      wrap.appendChild(out);
      var hpe = hpePanel(v);
      if (hpe) wrap.appendChild(hpe);
      put(body('hosts'), wrap);
    }

    function renderStandards() {
      var s = state.status, list = (state.view && state.view.standards) || (s && s.standards) || [];
      if (s && s.standardsError) { put(body('standards'), el('div', { 'class': 'hh-err', text: s.standardsError })); return; }
      put(body('standards'), table(['Key', 'Value', 'Source', 'Why'], list.map(function (x) {
        return [el('span', { 'class': 'hh-word', text: x.key }), x.value == null ? '(not pinned)' : String(x.value), el('span', { 'class': 'hh-word', text: x.source }), x.note];
      })));
    }

    /** Only while a fence wait exists: says what the evidence is, and asks for the node name to be typed. */
    function renderEmergency() {
      var box = body('emergency'), v = state.view;
      var waits = v ? v.results.filter(function (r) { return r.tier === 'EMERGENCY' && r.status === 'FAIL'; }) : [];
      var nodes = {};
      waits.forEach(function (r) { nodes[r.fix.split(':')[1]] = 1; });
      var ids = Object.keys(nodes);
      if (!ids.length) { put(box, null); return; }
      if (!canEmergency()) {
        put(box, el('div', { 'class': 'hh-emergency hh-err', text: 'GFS2 is frozen: every VM disk on it is blocked. Clearing this needs the "Cluster Health Emergency Actions" permission.' }));
        return;
      }
      var wrap = el('div', {});
      ids.forEach(function (id) {
        var panel = el('div', { 'class': 'hh-emergency' }, [el('strong', { text: 'GFS2 is frozen, waiting for node ' + id + ' to be fenced.' }), el('div', { 'class': 'hh-muted', text: 'Checking the evidence…' })]);
        wrap.appendChild(panel);
        call('fence-plan', { nodeId: id }).then(function (p) {
          panel.innerHTML = '';
          panel.appendChild(el('strong', { text: 'GFS2 is frozen, waiting for ' + p.nodeName + ' to be fenced' + (p.waitingSec ? ' for ' + Math.round(p.waitingSec / 60) + ' min' : '') + '.' }));
          panel.appendChild(el('div', { text: p.evidence || '' }));
          if (p.affectedVms && p.affectedVms.length) panel.appendChild(el('div', { 'class': 'hh-hint', text: 'Stuck now: ' + p.affectedVms.join(', ') + '. The HVM agent may power these off when I/O returns; start them after.' }));
          (p.blockers || []).forEach(function (b) { panel.appendChild(el('div', { 'class': 'hh-err', text: b })); });
          if (p.blockers && p.blockers.length) return;
          panel.appendChild(el('div', { 'class': 'hh-hint', text: 'Acknowledging a node that can still write to the datastore corrupts it. Be certain ' + p.nodeName + ' is down.' }));
          var typed = el('input', { type: 'text', placeholder: 'Type ' + p.nodeName });
          var attest = el('input', { type: 'checkbox' });
          var row = el('div', { 'class': 'hh-row' }, [typed]);
          if (!p.proven) row.appendChild(el('label', {}, [attest, ' I have confirmed ' + p.nodeName + ' is powered off or wiped']));
          row.appendChild(btn('Acknowledge as fenced', function (ev) {
            ev.target.disabled = true;
            call('fence-ack', { nodeId: id, confirm: typed.value, attest: attest.checked }, true).then(function (j) { follow(j, refreshAfter); }, function (e) { window.alert(e.message); ev.target.disabled = false; });
          }, 'btn-danger'));
          panel.appendChild(row);
        }, function (e) { panel.appendChild(el('div', { 'class': 'hh-err', text: e.message })); });
      });
      put(box, wrap);
    }

    /** What the checks forecast. They say for themselves when there is too little data, so nothing here reads as fine for want of it. */
    function renderCapacity() {
      var v = state.view, box = body('capacity');
      if (!v) { put(box, null); return; }
      var rows = v.results.filter(function (r) { return r.id === 'CAP-02' || r.id === 'CAP-03' || r.id === 'GFS-11' || r.id === 'STO-07'; });
      put(box, el('div', {}, [
        v.trendError ? el('div', { 'class': 'hh-err', text: 'Capacity samples are not being saved: ' + v.trendError }) : null,
        rows.length ? table(['Status', 'Check', 'Host', 'Observed', 'Expected'], rows.map(function (r) {
          return [el('span', { 'class': 'hh-nw hh-word st-' + r.status, text: r.status.replace('_', ' ') }), el('div', {}, [ref.idLink(r.id), ' ' + r.title]), el('span', { 'class': 'hh-nw', text: r.host || 'cluster' }), r.observed, r.expected];
        })) : el('div', { 'class': 'hh-muted', text: 'Nothing forecast yet.' })]));
    }

    function showView(v) { state.view = v; renderSummary(); renderEmergency(); renderFindings(); renderBusiest(); renderCapacity(); renderTime(); renderHosts(); renderStandards(); }

    function load() {
      call('status').then(function (s) {
        state.status = s;
        renderWindow();
        (s.jobs || []).forEach(function (j) { follow(j, refreshAfter); });
        showView(s.last || null);
      }, function (e) { put(body('summary'), el('div', { 'class': 'hh-err', text: e.message })); });
    }

    root.querySelector('[data-act="check"]').addEventListener('click', function (ev) {
      ev.target.disabled = true;
      call('check', {}, false).then(function (j) { follow(j, function (d) { ev.target.disabled = false; refreshAfter(d); }); }, function (e) { ev.target.disabled = false; window.alert(e.message); });
    });
    root.querySelector('[data-act="help"]').addEventListener('click', function () { ref.showAll(); });
    root.querySelector('[data-act="report"]').addEventListener('click', function () { ns.pages.report(body('report'), call); });
    root.querySelector('[data-act="availability"]').addEventListener('click', function () { ns.pages.availability(root.querySelector('[data-field="availability"]'), call); });
    load();
  }

  /** A link that ends in #hvm-cluster-health-clusterTab, such as a dashboard's, opens this tab: once a page, by clicking the tab's own link, so Morpheus does what a person's click does. */
  function openFromLink() {
    if (ns.openedFromLink || window.location.hash !== '#' + TAB) return;
    var link = document.querySelector('a[href$="#' + TAB + '"]');
    if (!link) return;
    ns.openedFromLink = true;
    link.click();
  }

  function scan() { Array.prototype.forEach.call(document.querySelectorAll('.hvm-health'), init); openFromLink(); }
  ns.clusterTab = init;
  ns.unmount = function (root) { if (root) root.removeAttribute('data-health-init'); };
  scan();
  if (window.MutationObserver && !ns.observer) {
    ns.observer = new MutationObserver(scan);
    ns.observer.observe(document.documentElement, { childList: true, subtree: true });
  }
})();
