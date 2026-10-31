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

  function init(root) {
    if (!root || root.getAttribute('data-health-init')) return;
    root.setAttribute('data-health-init', '1');
    var call = client(root.getAttribute('data-cluster-id'));
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

    function renderSummary() {
      var box = body('summary'), s = state.status, v = state.view;
      if (!v) {
        put(box, el('div', { 'class': 'hh-muted', text: (s ? (s.layout + '. ') : '') + 'Not checked since the plugin loaded. Run a full check, or wait for the automatic one.' }));
        return;
      }
      var auto = s ? (s.monitorMinutes > 0 ? 'Automatic check every ' + s.monitorMinutes + ' min.' : 'Automatic checks are off.') : '';
      var fixes = s ? ('Automatic fixes: safe ' + (s.autoFixSafe ? 'on' : 'off') + ', in maintenance ' + (s.autoFixMaintenance ? 'on' : 'off') + '.') : '';
      put(box, el('div', {}, [
        el('div', { 'class': 'hh-banner hh-ov-' + v.overall, text: v.overall + ': ' + v.counts.fail + ' failing, ' + v.counts.warn + ' warnings, ' + v.counts.pass + ' passing' }),
        el('div', { 'class': 'hh-muted', text: v.layout + '. Last ' + v.mode + ' check ' + ago(v.at) + '. ' + auto + ' ' + fixes }),
        (v.readiness && v.readiness.length) ? table(['Ready for', 'Status', 'Findings'], v.readiness.map(function (a) {
          return [a.area, el('span', { 'class': 'st-' + a.status, text: a.status === 'PASS' ? 'OK' : a.status }), (a.ids || []).join(', ')];
        })) : null
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
      put(box, table(['Status', 'Severity', 'Check', 'Host', 'Observed', 'Expected', 'Source', ''], rows.map(function (r) {
        var check = el('div', {}, [el('strong', { text: r.id }), ' ' + r.title]);
        if (r.hint) check.appendChild(el('div', { 'class': 'hh-hint', text: r.hint }));
        if (r.impact) check.appendChild(el('div', { 'class': 'hh-impact', text: 'Affects: ' + r.impact }));
        var f = fixButton(r);
        var act = f ? el('div', {}, [f, el('span', { 'class': 'hh-tier', text: TIER[r.tier] || '' })]) : '';
        return [el('span', { 'class': 'hh-nw st-' + r.status, text: r.status }), el('span', { 'class': 'hh-nw', text: r.severity }), check, el('span', { 'class': 'hh-nw', text: r.host || 'cluster' }), r.observed, r.expected, el('span', { 'class': 'hh-nw', text: r.source }), act];
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
      var wrap = el('div', {}, [el('div', { text: head }), el('div', { 'class': 'hh-muted', text: drift })]);
      if (canFix()) {
        var input = el('input', { type: 'text', 'class': 'hh-time-input', placeholder: 'ntp1.example.net, ntp2.example.net' });
        input.value = t.definedBy === 'cluster' ? t.defined.join(', ') : '';
        var save = btn('Save and verify', function () {
          save.disabled = true;
          call('time-source', { servers: input.value }, true).then(function (j) { follow(j, function (d) { save.disabled = false; refreshAfter(d); }); }, function (e) { save.disabled = false; window.alert(e.message); });
        });
        wrap.appendChild(el('div', { 'class': 'hh-row' }, [input, save, el('span', { 'class': 'hh-muted', text: 'Hosts are verified against this. Nothing changes on a host until you run its fix, in maintenance mode.' })]));
      }
      wrap.appendChild(table(['Host', 'Time source', 'Following now', 'Configured', 'Synced', 'Offset from source', 'Drift from other hosts', 'Stratum'], t.hosts.map(function (h) {
        return [el('span', { 'class': 'hh-nw', text: h.name }), el('span', { 'class': h.state === 'cluster source' ? 'hh-nw st-PASS' : (h.state === 'none configured' ? 'hh-nw st-FAIL' : 'hh-nw st-WARN'), text: h.state }),
          h.following || 'nothing', (h.configured || []).join(', ') || 'none', h.synced === true ? 'yes' : (h.synced === false ? el('span', { 'class': 'st-FAIL', text: 'NO' }) : '?'),
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
      var e = p.eject || {};
      d.appendChild(el('h4', { text: 'Taking it out of the cluster' }));
      d.appendChild(el('div', { 'class': 'hh-hint', text: 'Maintenance mode empties a host but leaves it a cluster member. If it then powers off or is rebuilt, every GFS2 datastore on the other hosts freezes until it is acknowledged as fenced. Eject it first.' }));
      d.appendChild(el('ol', {}, (e.steps || []).map(function (s) { return el('li', { text: s }); })));
      if (e.warning) d.appendChild(el('div', { 'class': 'hh-hint', text: e.warning }));
      (e.blockers || []).forEach(function (b) { d.appendChild(el('div', { 'class': 'hh-err', text: 'Before ejecting: ' + b })); });
      if (e.ready && p.ejectEnabled && canEmergency()) {
        var typed = el('input', { type: 'text', placeholder: 'Type ' + p.host });
        d.appendChild(el('div', { 'class': 'hh-row' }, [typed, btn('Eject from cluster', function (ev) {
          ev.target.disabled = true;
          call('eject', { host: p.host, confirm: typed.value }, true).then(function (j) { follow(j, refreshAfter); }, function (x) { window.alert(x.message); ev.target.disabled = false; });
        }, 'btn-danger')]));
      } else if (e.ready && !p.ejectEnabled) {
        d.appendChild(el('div', { 'class': 'hh-muted', text: 'Safe eject is ready to run, but is switched off in the plugin settings.' }));
      }
      box.appendChild(d);
    }

    /** Master tenant only: every cluster, worst first. */
    function loadFleet() {
      var sec = root.querySelector('[data-section="fleet"]');
      if (!state.status || !state.status.isMaster) return;
      call('fleet').then(function (rows) {
        sec.classList.remove('hh-hidden');
        put(sec.querySelector('.ph-body'), table(['Cluster', 'Owner', 'Health', 'Layout', 'Failing', 'Warnings', 'Not ready for', 'Last check'], rows.map(function (c) {
          var bad = (c.readiness || []).filter(function (a) { return a.status === 'FAIL'; }).map(function (a) { return a.area; });
          return [el('a', { href: '/infrastructure/clusters/' + c.id, text: c.name }), c.owner || '', el('span', { 'class': 'hh-nw hh-ov-' + c.overall, text: c.frozen ? 'FROZEN' : c.overall }), c.layout || '',
            c.counts ? c.counts.fail : '', c.counts ? c.counts.warn : '', bad.join(', '), c.at ? ago(c.at) : ''];
        })));
      }, function () { /* not offered to this user */ });
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
        loadFleet();
      }, function (e) { put(body('summary'), el('div', { 'class': 'hh-err', text: e.message })); });
    }

    root.querySelector('[data-act="check"]').addEventListener('click', function (ev) {
      ev.target.disabled = true;
      call('check', {}, false).then(function (j) { follow(j, function (d) { ev.target.disabled = false; refreshAfter(d); }); }, function (e) { ev.target.disabled = false; window.alert(e.message); });
    });
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
