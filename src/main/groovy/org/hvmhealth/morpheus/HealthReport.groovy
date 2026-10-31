package org.hvmhealth.morpheus

import java.text.SimpleDateFormat
import java.util.regex.Pattern

/**
 * The read-only report for one cluster, for handing to a customer or an auditor. A report can leave the tenant that ran it,
 * so it holds nothing about any tenant: who a finding affects is left out, findings that name VMs lose their observed text,
 * and any VM, tenant or network name Morpheus knows on this cluster is withheld wherever it turns up. Host and storage names stay.
 */
class HealthReport {
    static final String WITHHELD = '[withheld]'
    /** The observed text of these lists VMs, including ones Morpheus does not know about, so no list of names could catch them all. */
    static final Set<String> NAMES_VMS = ['HA-06', 'VM-01', 'VM-02', 'VM-05', 'MIG-04', 'MIG-06', 'MIG-07', 'NET-12', 'NET-13'] as Set
    static final String CONTAINS = 'The cluster and its layout, the latest result, current findings (check, severity, status, host, what was observed and expected, source, first seen) and the history of findings (first seen, last seen, cleared).'
    static final String EXCLUDES = 'No tenant data: who a finding affects and the names of tenants, VMs and networks are withheld. Hints, fixes and raw host output are left out. Host and storage names stay.'
    static final List<String> CSV_COLUMNS = ['cluster', 'generated', 'section', 'status', 'worst_status', 'severity', 'check', 'title', 'host', 'observed', 'expected', 'source', 'first_seen', 'last_seen', 'cleared', 'returned']

    static String iso(Object ms) {
        if (ms == null) {
            return null
        }
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'")
        f.timeZone = TimeZone.getTimeZone('UTC')
        f.format(new Date(ms as long))
    }

    /** Whole names only, longest first, so one name inside a longer word or name is left alone. Very short names would hit ordinary words. */
    static Closure<String> scrubber(Collection<String> names) {
        List<Pattern> ps = names.findAll { it && it.length() >= 3 }.unique().sort { -it.length() }.collect {
            Pattern.compile('(?<![\\w-])' + Pattern.quote(it) + '(?![\\w-])', Pattern.CASE_INSENSITIVE)
        }
        return { String s -> s == null ? null : ps.inject(s) { String acc, Pattern p -> p.matcher(acc).replaceAll(WITHHELD) } }
    }

    /**
     * @param cluster [id, name, layout]
     * @param last the last evaluation, null if none since the plugin loaded
     * @param doc the stored history, null if none
     * @param names every VM, tenant and network name to withhold
     */
    static Map build(Map cluster, Map last, Map doc, String historyError, Collection<String> names, long now = System.currentTimeMillis()) {
        Closure<String> scrub = scrubber(names)
        List<Map> eps = ((doc?.episodes ?: []) as List<Map>).sort(false) { -((it.first ?: 0L) as long) }
        // what comes after the colon is the part that can name something; the words before it are ours, and a tenant called "Cloud" must not eat them
        Closure<String> title = { String t ->
            int colon = t == null ? -1 : t.indexOf(': ')
            colon < 0 ? t : t.substring(0, colon + 2) + scrub(t.substring(colon + 2))
        }
        Closure<Map> episode = { Map e ->
            [check: e.id, title: title(FindingHistory.safeTitle(e.id as String, e.title as String)), host: e.host, severity: e.severity, status: e.status, worstStatus: e.worst,
             firstSeen: iso(e.first), lastSeen: iso(e.last), cleared: iso(e.cleared), returned: (e.returns ?: 0) as int]
        }
        List<Map> current
        if (last) {
            Map<String, Map> open = eps.findAll { it.cleared == null }.collectEntries { [(it.k): it] }
            current = (last.results as List<Map>).findAll { it.status in ['FAIL', 'WARN', 'NOT_CHECKED'] }.collect { Map r ->
                Object first = r.firstSeen ?: open[FindingHistory.key(r.id as String, r.host as String, r.title as String)]?.first
                [check: r.id, title: title(FindingHistory.safeTitle(r.id as String, r.title as String)), host: r.host, severity: r.severity, status: r.status,
                 observed: r.id in NAMES_VMS ? 'withheld: this finding names VMs' : scrub(r.observed as String), expected: scrub(r.expected as String), source: r.source, firstSeen: iso(first)]
            }
        } else {
            current = eps.findAll { it.cleared == null }.collect { Map e -> episode(e) + [observed: null, expected: null, source: null] }
        }
        Map s = (last ? FindingHistory.summaryOf(last) : doc?.summary) as Map
        String safeName = ((cluster.name ?: 'cluster') as String).replaceAll(/[^A-Za-z0-9._-]+/, '-')
        Map report = [title: "Cluster health report: ${cluster.name}".toString(), cluster: [id: cluster.id, name: cluster.name, layout: cluster.layout],
                      generated: iso(now), overall: s?.overall ?: 'NOT CHECKED', counts: s?.counts, lastCheck: iso(s?.at), lastCheckMode: s?.mode,
                      checkedSinceLoad: last != null, frozen: s?.frozen ?: false,
                      current: current, history: eps.collect { episode(it) },
                      historySince: iso(doc?.since), historyDropped: (doc?.dropped ?: 0) as int, historyOverflow: doc?.overflow == true, historyError: historyError,
                      retentionDays: (FindingHistory.RETENTION_MS / (24L * 3600 * 1000)) as long, contains: CONTAINS, excludes: EXCLUDES,
                      fileBase: "cluster-health-${safeName}-${iso(now).take(10)}".toString()]
        report.csv = csv(report)
        report
    }

    /** A cell a spreadsheet would run as a formula gets a leading apostrophe: findings can hold host output. */
    static String cell(Object v) {
        String s = v == null ? '' : v.toString()
        if (s && '=+-@\t\r'.indexOf((int) s.charAt(0)) >= 0) {
            s = "'" + s
        }
        s ==~ /(?s).*[",\r\n].*/ ? '"' + s.replace('"', '""') + '"' : s
    }

    static String csv(Map r) {
        Closure<List> row = { String section, Map x ->
            [r.cluster.name, r.generated, section, x.status, x.worstStatus, x.severity, x.check, x.title, x.host, x.observed, x.expected, x.source, x.firstSeen, x.lastSeen, x.cleared, x.returned]
        }
        List<List> rows = (r.current as List<Map>).collect { row('current', it) } + (r.history as List<Map>).collect { row('history', it) }
        ([CSV_COLUMNS] + rows).collect { List c -> c.collect { cell(it) }.join(',') }.join('\r\n') + '\r\n'
    }
}
