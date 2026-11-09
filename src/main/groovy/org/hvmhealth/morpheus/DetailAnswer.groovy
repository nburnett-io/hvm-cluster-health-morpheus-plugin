package org.hvmhealth.morpheus

import org.hvmhealth.checks.CheckCatalog

/**
 * What a signed read of /v1/clusters gets (see ReaderKeys), from what the monitor holds, with no socket or Morpheus in sight, like StatusAnswer. Ids, words, counts and
 * times only: never a host, cluster name, owner, tenant, VM, network, a finding's text, a change window's user or reason, or a node's name. Check names come from
 * CheckCatalog, which holds no cluster data. Times are epoch milliseconds, or null.
 *
 * A readiness area's status is PASS, WARN, FAIL, NOT_CHECKED or NA (nothing in the area applies). Checks and counts leave NA results out.
 */
class DetailAnswer {
    static final int VERSION = 1
    /** What checks and counts go by, worst first. */
    static final List<String> STATUSES = ['FAIL', 'WARN', 'NOT_CHECKED', 'PASS']
    /** A cluster's state, as StatusAnswer.state gives it. */
    static final List<String> WORDS = ['OK', 'WARNING', 'CRITICAL', 'STALE', 'RELOADING']

    /** From the monitor's state (see StatusAnswer). No cluster is listed before the first sweep, as /status counts none. */
    static Map answer(Map s, long now) {
        List<Long> ids = s.lastSweepAt == null ? [] : ((s.ids ?: []) as Collection<Long>).sort(false)
        List<Map> clusters = ids.collect { Long id -> cluster(id, s, now) }
        [version: VERSION, standingBy: false, alive: StatusAnswer.answer('/alive', s, now)[0] == 200, sweptAt: s.lastSweepAt,
         quickMinutes: ((s.quickMs ?: 0L) as long).intdiv(60_000L), clusters: clusters, names: names(clusters)]
    }

    /** The cluster's id, state and whether a change window holds its paging; and, once it has an evaluation, what that found, by named fields only. */
    private static Map cluster(Long id, Map s, long now) {
        Map c = [id: id, state: StatusAnswer.state(id, s, now), held: Paging.held(id, now)]
        Map v = (s.views as Map)?.get(id) as Map
        if (!v) {
            return c
        }
        List<Map> rows = (v.results ?: []) as List<Map>
        c + [checkedAt: v.at, mode: v.mode, fullAt: v.fullAt, overall: v.overall,
             counts: [fail: rows.count { it.status == 'FAIL' }, warn: rows.count { it.status == 'WARN' }, pass: rows.count { it.status == 'PASS' }, notChecked: rows.count { it.status == 'NOT_CHECKED' }],
             readiness: ((v.readiness ?: []) as List<Map>).collect { Map a -> [area: a.area, status: a.status] },
             checks: STATUSES.collectEntries { String st -> [(st): rows.findAll { it.status == st }*.id.unique().sort()] }]
    }

    /** The catalog's name for each check id listed, where it has one. */
    private static Map<String, String> names(List<Map> clusters) {
        Set<String> ids = new TreeSet<String>()
        clusters.each { Map c -> ((c.checks ?: [:]) as Map).values().flatten().each { if (it != null) { ids << it.toString() } } }
        ids.findAll { CheckCatalog.ENTRIES[it]?.name }.collectEntries { [(it): CheckCatalog.ENTRIES[it].name] }
    }

    /**
     * On a node standing by, from the holder's row: each cluster's word and nothing more, as this node holds no evaluation. A row older than a sweep may be reads
     * as StatusAnswer.fromRow reads it: not alive, and every cluster STALE. Row keys are strings after JSON, and a word this version does not know reads STALE.
     */
    static Map fromRow(Map row, long now) {
        Map sum = row?.summary as Map
        boolean old = !sum || Lease.age(row, now) > StatusAnswer.SWEEP_LATE_MS
        Map words = sum?.clusters instanceof Map ? sum.clusters as Map : [:]
        List<Map> clusters = words.findAll { k, w -> (k as String) ==~ /\d{1,18}/ }
                .collect { k, w -> [id: k as Long, state: !old && w in WORDS ? w as String : 'STALE'] }.sort { it.id }
        [version: VERSION, standingBy: true, alive: !old && sum.alive == true, sweptAt: row?.at instanceof Number ? row.at as long : null, quickMinutes: null,
         clusters: clusters, names: [:]]
    }
}
