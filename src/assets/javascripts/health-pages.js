/*
 * HVM Cluster Health - the all-clusters page and the per-cluster report, both read-only. Loads after health-tab.js, whose helpers it
 * uses. Everything shown goes in through textContent or setAttribute (findings can hold host output), never as HTML.
 */
(function () {
  'use strict';
  var ns = window.Morpheus.pluginTabs.hvmClusterHealth;
  var ui = ns.ui, el = ui.el;
  var CSS = [
    '.hh-page, .hh-report { font-size: 13px; white-space: normal; overflow-wrap: break-word; overflow-x: auto; }',
    '.hh-page table, .hh-report table { width: 100%; border-collapse: collapse; font-size: 12px; margin: 6px 0 14px; }',
    '.hh-page th, .hh-page td, .hh-report th, .hh-report td { text-align: left; padding: 4px 6px; border-bottom: 1px solid rgba(128,128,128,.25); vertical-align: top; }',
    '.hh-page th, .hh-report th { white-space: nowrap; }',
    '.hh-page td, .hh-report td { overflow-wrap: anywhere; } .hh-word { overflow-wrap: normal; }',
    '.hh-report h3, .hh-page h3 { margin: 14px 0 6px; font-size: 15px; } .hh-report h4 { margin: 12px 0 4px; font-size: 13px; }',
    '.hh-muted { opacity: .7; } .hh-err { color: #e53935; } .hh-nw { white-space: nowrap; }',
    '.hh-report-actions { display: flex; gap: 8px; margin: 6px 0; }',
    '.hh-st-PASS, .hh-ov-HEALTHY { color: #388e3c; } .hh-st-FAIL, .hh-ov-CRITICAL { color: #e53935; font-weight: 600; }',
    '.hh-st-WARN, .hh-ov-WARNING { color: #b26a00; } .hh-ov-DEGRADED { color: #e65100; } .hh-st-NOT_CHECKED { color: #6b8794; font-style: italic; }',
    '.hh-fleet-report { margin-top: 12px; }',
    '@media print { .hh-report-actions { display: none; } }'
  ].join('\n');

  function addStyle(doc) {
    if (doc.querySelector('style[data-hh-pages]')) return;
    var s = doc.createElement('style'), scr = document.querySelector('script[nonce]');
    s.setAttribute('data-hh-pages', '1');
    if (scr && scr.nonce) s.setAttribute('nonce', scr.nonce);
    s.textContent = CSS;
    doc.head.appendChild(s);
  }
  function when(iso) { return iso ? new Date(iso).toLocaleString() : ''; }
  function plain(s) { return String(s == null ? '' : s).replace('_', ' '); }
  function status(s) { return el('span', { 'class': 'hh-nw hh-word hh-st-' + s, text: plain(s) }); }
  function word(s) { return el('span', { 'class': 'hh-word', text: s == null ? '' : s }); }

  function download(name, type, text) {
    var url = URL.createObjectURL(new Blob([text], { type: type }));
    var a = el('a', { href: url, download: name });
    document.body.appendChild(a);
    a.click();
    a.remove();
    setTimeout(function () { URL.revokeObjectURL(url); }, 10000);
  }

  function reportNode(r) {
    var c = r.counts;
    var d = el('div', { 'class': 'hh-report' }, [
      el('h3', { text: r.title }),
      el('div', { 'class': 'hh-ov-' + r.overall, text: r.overall + (c ? ': ' + c.fail + ' failing, ' + c.warn + ' warnings, ' + c.pass + ' passing' : '') }),
      el('div', { 'class': 'hh-muted', text: (r.cluster.layout || '') + '. Generated ' + when(r.generated) + '. ' + (r.lastCheck ? 'Last ' + (r.lastCheckMode || '') + ' check ' + when(r.lastCheck) + '.' : 'No check on record.') + (r.checkedSinceLoad ? '' : ' Not checked since the plugin loaded, so this is the last result on record.') }),
      r.frozen ? el('div', { 'class': 'hh-err', text: 'GFS2 is frozen on at least one host.' }) : null,
      el('h4', { text: 'Current findings' }),
      r.current.length ? ui.table(['Status', 'Severity', 'Check', 'Title', 'Host', 'Observed', 'Expected', 'Source', 'First seen'], r.current.map(function (f) {
        return [status(f.status), word(f.severity), word(f.check), f.title, f.host || 'cluster', f.observed, f.expected, word(f.source), word(when(f.firstSeen))];
      })) : el('div', { text: 'Nothing failing or warning.' }),
      el('h4', { text: 'History' }),
      r.history.length ? ui.table(['Check', 'Title', 'Host', 'Severity', 'Worst', 'First seen', 'Last seen', 'Cleared', 'Came back'], r.history.map(function (f) {
        return [word(f.check), f.title, f.host || 'cluster', word(f.severity), status(f.worstStatus), word(when(f.firstSeen)), word(when(f.lastSeen)), word(f.cleared ? when(f.cleared) : 'still open'), f.returned || ''];
      })) : el('div', { text: 'No history on record.' }),
      el('div', { 'class': 'hh-muted', text: 'History from ' + (r.historySince ? when(r.historySince) : 'the first check') + '. Cleared findings are kept ' + r.retentionDays + ' days.' + (r.historyDropped ? ' ' + r.historyDropped + ' older cleared finding(s) were dropped to stay within the size limit.' : '') + (r.historyOverflow ? ' History is full, so some current findings are not tracked.' : '') }),
      r.historyError ? el('div', { 'class': 'hh-err', text: 'History could not be read or saved: ' + r.historyError }) : null,
      el('div', { 'class': 'hh-muted', text: r.contains + ' ' + r.excludes })
    ]);
    return d;
  }

  /** Its own window, so what prints is the report and not Morpheus's page around it. Nothing in it runs script. */
  function printReport(r) {
    var w = window.open('', '_blank');
    if (!w) { window.alert('The browser blocked the report window: allow pop-ups for this page, then press Print again.'); return; }
    w.document.title = r.title;
    addStyle(w.document);
    w.document.body.appendChild(w.document.importNode(reportNode(r), true));
    w.focus();
    w.print();
  }

  function exportJson(r) {
    var copy = Object.assign({}, r);
    delete copy.csv;
    return JSON.stringify(copy, null, 2);
  }

  ns.pages = {
    /** The report for the cluster that call is bound to, in box. */
    report: function (box, call) {
      addStyle(document);
      ui.put(box, el('div', { 'class': 'hh-muted', text: 'Building the report…' }));
      call('report').then(function (r) {
        var wrap = el('div', {}, [el('div', { 'class': 'hh-report-actions' }, [
          ui.btn('Print', function () { printReport(r); }),
          ui.btn('Export CSV', function () { download(r.fileBase + '.csv', 'text/csv', r.csv); }),
          ui.btn('Export JSON', function () { download(r.fileBase + '.json', 'application/json', exportJson(r)); })
        ]), reportNode(r)]);
        ui.put(box, wrap);
      }, function (e) { ui.put(box, el('div', { 'class': 'hh-err', text: e.message })); });
    },
    /** One cluster's figures, for the cluster that call is bound to, and a CSV per tenant. */
    availability: function (box, call, month) {
      addStyle(document);
      ui.put(box, el('div', { 'class': 'hh-muted', text: 'Reading the figures…' }));
      call('availability', { month: month }).then(function (a) {
        var pick = monthPicker(a.months, a.month, function (m) { ns.pages.availability(box, call, m); });
        ui.put(box, el('div', {}, [
          el('div', { 'class': 'hh-report-actions' }, [pick]),
          a.error ? el('div', { 'class': 'hh-err', text: 'These figures could not be read or saved, so they may be incomplete: ' + a.error }) : null,
          a.ongoing ? el('div', { 'class': 'hh-err', text: 'A freeze is open now, and is counted up to this moment.' }) : null,
          a.periodMinutes == null ? null : ui.table(['Frozen', 'Freezes', 'Stalls', 'Unplanned VM stops', 'Observed', 'Not observed, so unknown', 'Frozen, tenants unknown'], [[
            minutes(a.frozenMinutes), a.freezes, a.stalls, a.vmStops, minutes(a.observedMinutes), minutes(a.unknownMinutes), minutes(a.unattributedMinutes)]]),
          tenantTable(a.tenants || [], a.month, call),
          el('div', { 'class': 'hh-muted', text: a.measures })
        ]));
      }, function (e) { ui.put(box, el('div', { 'class': 'hh-err', text: e.message })); });
    }
  };

  function minutes(m) { return m == null ? '' : m + ' min'; }

  function monthPicker(months, month, chosen) {
    var s = el('select', { 'class': 'hh-month', 'aria-label': 'Month' }, months.map(function (m) { return el('option', { value: m, text: m }); }));
    s.value = month;
    s.addEventListener('change', function () { chosen(s.value); });
    return s;
  }

  /** Each file holds that tenant's figures and nothing else, which the route enforces. Read it before it leaves all the same. */
  function tenantTable(tenants, month, call) {
    if (!tenants.length) return el('div', { 'class': 'hh-muted', text: 'No tenants on record for this month.' });
    return ui.table(['Tenant', 'Frozen with running VMs', 'Running VMs', 'Unplanned VM stops', ''], tenants.map(function (t) {
      return [t.name || String(t.id), minutes(t.frozenMinutes), t.runningVms == null ? '' : t.runningVms, t.vmStops, ui.btn('Export CSV', function () {
        call('availability-export', { tenantId: t.id, month: month }).then(function (r) { download(r.fileBase + '.csv', 'text/csv', r.csv); }, function (e) { window.alert(e.message); });
      })];
    }));
  }

  /** What may share one cause, newest first. Not on any cluster's tab, as it names other clusters. */
  function sharedSection(i) {
    return el('div', {}, [
      el('h3', { text: 'Possible shared causes' }),
      i.sharedError ? el('div', { 'class': 'hh-err', text: i.sharedError }) : null,
      i.shared.length ? ui.table(['Check', 'Clusters', 'Since', 'What they share'], i.shared.map(function (c) {
        return [c.check + (c.checkName ? ': ' + c.checkName : ''), c.clusters.map(function (x) { return x.name; }).join(', '), ui.ago(c.since), c.shared.length ? c.shared.join(', ') : 'nothing on record'];
      })) : el('div', { 'class': 'hh-muted', text: i.standingBy ? 'Only the appliance node running the checks can say. Its alarms show them, as FLT-01.' : 'No check has appeared on several clusters in the last few minutes.' })
    ]);
  }

  /** Reasons and never a score, from each cluster's last check. A cluster with no answer yet is said to be unknown, not left to read as safe. */
  function oneFailureSection(rows) {
    var listed = rows.filter(function (c) { return c.reasons && c.reasons.length; }).sort(function (a, b) { return b.reasons.length - a.reasons.length || String(a.name).localeCompare(b.name); });
    var unknown = rows.filter(function (c) { return !c.reasons; }).length;
    return el('div', {}, [
      el('h3', { text: 'One failure from an outage' }),
      listed.length ? ui.table(['Cluster', 'Owner', 'Why'], listed.map(function (c) { return [c.name, c.owner || '', c.reasons.join('; ')]; }))
        : el('div', { 'class': 'hh-muted', text: 'No cluster with an answer has any of these findings at its last check.' }),
      unknown ? el('div', { 'class': 'hh-muted', text: unknown + ' cluster(s) have no answer yet, so they are not listed. That does not make them safe.' }) : null
    ]);
  }

  /** Clusters with something to say. The rest are counted, with the words for what they are: nothing recorded in what was observed. */
  function availabilitySection(a, rows) {
    var names = {};
    rows.forEach(function (c) { names[c.id] = c.name; });
    var shown = (a.clusters || []).filter(function (c) { return c.ongoing || parseFloat(c.frozenMinutes) > 0 || c.stalls || c.vmStops || parseFloat(c.unknownMinutes) >= 60; });
    var unread = rows.filter(function (c) { return !(a.clusters || []).some(function (x) { return x.clusterId === c.id; }); }).length;
    return el('div', {}, [
      el('h3', { text: 'Availability, ' + a.month }),
      a.error ? el('div', { 'class': 'hh-err', text: 'The figures could not be read: ' + a.error }) : null,
      shown.length ? ui.table(['Cluster', 'Frozen', 'Freezes', 'Stalls', 'Unplanned VM stops', 'Observed', 'Not observed, so unknown'], shown.map(function (c) {
        return [names[c.clusterId] || 'cluster ' + c.clusterId, minutes(c.frozenMinutes) + (c.ongoing ? ', still open' : ''), c.freezes, c.stalls, c.vmStops, minutes(c.observedMinutes), minutes(c.unknownMinutes)];
      })) : null,
      el('div', { 'class': 'hh-muted', text: ((a.clusters || []).length - shown.length) + ' cluster(s) recorded nothing in the time they were observed, with under an hour unknown.' + (unread ? ' ' + unread + ' cluster(s) have no figures yet, which means unknown.' : '') }),
      tenantTable(a.tenants || [], a.month, ui.client(undefined)),
      el('div', { 'class': 'hh-muted', text: a.measures })
    ]);
  }

  /** How the monitor itself is doing: whether checks keep up (PRB-04), the status URL and the freeze watch. Numbers only. */
  function monitorLines(m) {
    var p = m.pace || {}, w = m.watch || {}, l = m.lease || {}, min = function (ms) { return Math.round((ms || 0) / 60000) + ' min'; };
    return el('div', { 'class': 'hh-monitor' }, [
      !l.node ? null : el('div', { 'class': l.holder ? 'hh-muted' : 'hh-err', text: !l.holder ? 'The record of which appliance node runs the checks could not be read or written, so ' + l.node + ' runs them, and others may too. Its log says why.' :
        l.standingBy ? 'Checks run on appliance node ' + l.holder + '. This page came from ' + l.node + ', which stands by, so its clusters show what was last stored, up to 15 minutes old.' : 'Checks run on appliance node ' + l.holder + ', which answered this page.' }),
      p.behind ? el('div', { 'class': 'hh-err', text: 'PRB-04: checks are falling behind. The most overdue cluster is ' + min(p.mostOverdueMs) + ' past its turn.' }) : null,
      p.watchStopped ? el('div', { 'class': 'hh-err', text: 'PRB-04: the freeze watch has stopped reading, as every thread it has is held by an agent call that never returned. A reload frees them. Meanwhile a freeze waits for the next check.' }) : null,
      el('div', { 'class': 'hh-muted', text: p.checksOff ? 'Automatic checks are off, so nothing is watching these clusters.' : p.clusters === undefined ? 'The first sweep has not run yet.' :
        'Checks: ' + p.clusters + ' clusters, ' + m.checkers + ' at once. Oldest check ' + min(p.roundMs) + ' ago, most overdue ' + min(p.mostOverdueMs) + ', ' + p.waiting + ' waiting.' }),
      el('div', { 'class': 'hh-muted', text: !w.on ? 'Freeze watch: off.' : 'Freeze watch: about every ' + w.realSeconds + ' s (set to ' + w.seconds + ' s), ' + w.clusters + ' clusters within ' + w.budget + ' reads a minute.' + (w.oldestReadSec != null ? ' Oldest read ' + w.oldestReadSec + ' s ago.' : '') }),
      m.statusError ? el('div', { 'class': 'hh-err', text: 'Status URL: ' + m.statusError }) :
        el('div', { 'class': 'hh-muted', text: m.statusPort ? 'Status URL: listening on port ' + m.statusPort + '.' : 'Status URL: off.' })
    ]);
  }

  /** Master tenant only, which the route enforces: every cluster, worst first. */
  function initFleet(root) {
    if (root.getAttribute('data-hh-init')) return;
    root.setAttribute('data-hh-init', '1');
    addStyle(document);
    ui.client(undefined)('monitor').then(function (m) { root.insertBefore(monitorLines(m), root.firstChild); }, function () { /* the rows below say what is wrong */ });
    var body = root.querySelector('.hh-page-body'), panel = el('div', { 'class': 'hh-fleet-report' });
    var shared = el('div', { 'class': 'hh-muted', text: 'Reading what clusters have in common…' }), after = el('div', {});
    ui.client(undefined)('fleet').then(function (rows) {
      if (!rows.length) { ui.put(body, el('div', { text: 'No HVM clusters.' })); return; }
      // separately, so that trouble reading these never costs the table of clusters
      ui.client(undefined)('fleet-insights').then(function (i) {
        ui.put(shared, sharedSection(i));
        ui.put(after, availabilitySection(i.availability, rows));
      }, function (e) { ui.put(shared, el('div', { 'class': 'hh-err', text: e.message })); });
      ui.put(body, el('div', {}, [
        shared,
        oneFailureSection(rows),
        el('h3', { text: 'All clusters' }),
        ui.table(['Cluster', 'Owner', 'Health', 'Layout', 'Failing', 'Warnings', 'Not ready for', 'Last check', ''], rows.map(function (c) {
          // The master tenant cannot open a cluster another tenant owns, so a link here only leads to "Resource not found".
          return [c.name, c.owner || '',
            el('div', {}, [el('span', { 'class': 'hh-nw hh-ov-' + c.overall, text: c.frozen ? 'FROZEN' : c.overall }),
              c.window ? el('div', { 'class': 'hh-muted', text: 'Change window until ' + when(c.window.endsAt) + ': ' + c.window.by + ', ' + c.window.note }) : null]), c.layout || '',
            c.counts ? c.counts.fail : '', c.counts ? c.counts.warn : '', (c.notReady || []).join(', '),
            c.at ? ui.ago(c.at) + (c.lastKnown ? (c.standingBy ? ', as last stored' : ', before the plugin loaded') : '') : '',
            ui.btn('Report', function () { ns.pages.report(panel, ui.client(c.id)); })];
        })),
        panel,
        after
      ]));
    }, function (e) { ui.put(body, el('div', { 'class': 'hh-err', text: e.message })); });
  }

  Array.prototype.forEach.call(document.querySelectorAll('[data-hh-page="fleet"]'), initFleet);
})();
