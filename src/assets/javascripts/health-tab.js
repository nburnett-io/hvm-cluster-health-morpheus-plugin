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
      el('tbody', {}, rows.map(function (r) { return el('tr', {}, r.map(function (c) { return c instanceof Node ? el('td', {}, [c]) : el('td', { text: c == null ? '' : String(c) }); })); }))]);
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
        put(box, el('div', {}, [el('div', { 'class': 'hh-muted', text: (s ? (s.layout + '. ') : '') + 'Not checked since the plugin loaded. Run a full check, or wait for the automatic one.' + (known ? ' Last known: ' + known.overall + ', ' + new Date(known.at).toLocaleString() + '.' : '') }), summaryNotes(null)]));
        return;
      }
      var auto = s ? (s.monitorMinutes > 0 ? 'Automatic check every ' + s.monitorMinutes + ' min.' : 'Automatic checks are off.') : '';
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
      put(box, table(['Status', 'Severity', 'Check', 'Host', 'Observed', 'Expected', 'Source', 'First seen', ''], rows.map(function (r) {
        var check = el('div', {}, [ref.idLink(r.id), ' ' + r.title]);
        if (r.hint) check.appendChild(el('div', { 'class': 'hh-hint', text: r.hint }));
        if (r.impact) check.appendChild(el('div', { 'class': 'hh-impact', text: 'Affects: ' + r.impact }));
        var f = fixButton(r);
        var act = f ? el('div', {}, [f, el('span', { 'class': 'hh-tier', text: TIER[r.tier] || '' })]) : '';
        return [el('span', { 'class': 'hh-nw st-' + r.status, text: r.status.replace('_', ' ') }), el('span', { 'class': 'hh-nw', text: r.severity }), check, el('span', { 'class': 'hh-nw', text: r.host || 'cluster' }), r.observed, r.expected, el('span', { 'class': 'hh-nw', text: r.source }), r.firstSeen ? el('span', { 'class': 'hh-nw', text: new Date(r.firstSeen).toLocaleString() }) : '', act];
      })));
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
        return [el('span', { 'class': 'hh-nw', text: h.name }), el('span', { 'class': h.state === 'cluster source' ? 'hh-nw st-PASS' : (h.state === 'none configured' ? 'hh-nw st-FAIL' : 'hh-nw st-WARN'), text: h.state }),
          h.daemon || 'none', h.following || 'nothing', (h.configured || []).join(', ') || 'none', h.synced === true ? 'yes' : (h.synced === false ? el('span', { 'class': 'st-FAIL', text: 'NO' }) : '?'),
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
          btn('Raw', function () {
            out.textContent = 'Reading ' + h.name + '…';
            call('raw', { host: h.name }).then(function (r) { out.textContent = r.output; }, function (e) { out.textContent = e.message; });
          })]);
        return [el('span', { 'class': 'hh-nw', text: h.name }), h.answered ? 'yes' : el('span', { 'class': 'st-FAIL', text: 'NO' }), h.maintenance ? 'yes' : 'no', h.running,
          h.frozen ? el('span', { 'class': 'st-FAIL', text: 'FROZEN' }) : 'no', h.dStuck, h.os || '', h.kernel || '', acts];
      })));
      wrap.appendChild(panel);
      wrap.appendChild(out);
      put(body('hosts'), wrap);
    }

    function renderStandards() {
      var s = state.status, list = (state.view && state.view.standards) || (s && s.standards) || [];
      if (s && s.standardsError) { put(body('standards'), el('div', { 'class': 'hh-err', text: s.standardsError })); return; }
      put(body('standards'), table(['Key', 'Value', 'Source', 'Why'], list.map(function (x) {
        return [x.key, x.value == null ? '(not pinned)' : String(x.value), x.source, x.note];
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

    function showView(v) { state.view = v; renderSummary(); renderEmergency(); renderFindings(); renderTime(); renderHosts(); renderStandards(); }

    function load() {
      call('status').then(function (s) {
        state.status = s;
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
    load();
  }

  function scan() { Array.prototype.forEach.call(document.querySelectorAll('.hvm-health'), init); }
  ns.clusterTab = init;
  ns.unmount = function (root) { if (root) root.removeAttribute('data-health-init'); };
  scan();
  if (window.MutationObserver && !ns.observer) {
    ns.observer = new MutationObserver(scan);
    ns.observer.observe(document.documentElement, { childList: true, subtree: true });
  }
})();
