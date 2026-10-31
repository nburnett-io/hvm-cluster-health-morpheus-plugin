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
    '.hh-report h3, .hh-page h3 { margin: 14px 0 6px; font-size: 15px; } .hh-report h4 { margin: 12px 0 4px; font-size: 13px; }',
    '.hh-muted { opacity: .7; } .hh-err { color: #c62828; } .hh-nw { white-space: nowrap; }',
    '.hh-report-actions { display: flex; gap: 8px; margin: 6px 0; }',
    '.hh-st-PASS, .hh-ov-HEALTHY { color: #2e7d32; } .hh-st-FAIL, .hh-ov-CRITICAL { color: #c62828; font-weight: 600; }',
    '.hh-st-WARN, .hh-ov-WARNING { color: #b26a00; } .hh-ov-DEGRADED { color: #e65100; } .hh-st-NOT_CHECKED { color: #546e7a; font-style: italic; }',
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
  function status(s) { return el('span', { 'class': 'hh-nw hh-st-' + s, text: plain(s) }); }

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
        return [status(f.status), f.severity, f.check, f.title, f.host || 'cluster', f.observed, f.expected, f.source, when(f.firstSeen)];
      })) : el('div', { text: 'Nothing failing or warning.' }),
      el('h4', { text: 'History' }),
      r.history.length ? ui.table(['Check', 'Title', 'Host', 'Severity', 'Worst', 'First seen', 'Last seen', 'Cleared', 'Came back'], r.history.map(function (f) {
        return [f.check, f.title, f.host || 'cluster', f.severity, status(f.worstStatus), when(f.firstSeen), when(f.lastSeen), f.cleared ? when(f.cleared) : 'still open', f.returned || ''];
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
    }
  };

  /** Master tenant only, which the route enforces: every cluster, worst first. */
  function initFleet(root) {
    if (root.getAttribute('data-hh-init')) return;
    root.setAttribute('data-hh-init', '1');
    addStyle(document);
    var body = root.querySelector('.hh-page-body'), panel = el('div', { 'class': 'hh-fleet-report' });
    ui.client(undefined)('fleet').then(function (rows) {
      if (!rows.length) { ui.put(body, el('div', { text: 'No HVM clusters.' })); return; }
      ui.put(body, el('div', {}, [
        ui.table(['Cluster', 'Owner', 'Health', 'Layout', 'Failing', 'Warnings', 'Not ready for', 'Last check', ''], rows.map(function (c) {
          // The master tenant cannot open a cluster another tenant owns, so a link here only leads to "Resource not found".
          return [c.name, c.owner || '',
            el('span', { 'class': 'hh-nw hh-ov-' + c.overall, text: c.frozen ? 'FROZEN' : c.overall }), c.layout || '',
            c.counts ? c.counts.fail : '', c.counts ? c.counts.warn : '', (c.notReady || []).join(', '),
            c.at ? ui.ago(c.at) + (c.lastKnown ? ', before the plugin loaded' : '') : '',
            ui.btn('Report', function () { ns.pages.report(panel, ui.client(c.id)); })];
        })),
        panel
      ]));
    }, function (e) { ui.put(body, el('div', { 'class': 'hh-err', text: e.message })); });
  }

  Array.prototype.forEach.call(document.querySelectorAll('[data-hh-page="fleet"]'), initFleet);
})();
