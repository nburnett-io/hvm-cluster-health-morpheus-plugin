package org.hvmhealth.morpheus

/**
 * What the status URL says, from what the monitor holds, with no socket or Morpheus in sight. A stranger can read this, so a body is only ever
 * the words and counts below: never a host, cluster, tenant, VM or finding, and never anything taken from the request. A cluster that has not been
 * read is never plain OK: for a short time after a load it is OK and says it is reloading, and after that STALE. See docs/ALERTING.md.
 *
 * The state: running, startedAt (when the plugin loaded), loadedAt (when its first sweep is due), lastSweepAt (null until a sweep finishes), quickMs (0 when automatic checks are off), behind (PRB-04), ids (what is monitored), views (id -> last evaluation).
 */
class StatusAnswer {
    static final String ALIVE = 'HVM-HEALTH ALIVE\n'
    static final String STALE = 'HVM-HEALTH STALE\n'
    static final String OK = 'HVM-HEALTH OK\n'
    static final String WARNING = 'HVM-HEALTH WARNING\n'
    static final String CRITICAL = 'HVM-HEALTH CRITICAL\n'
    static final String UNKNOWN = 'HVM-HEALTH UNKNOWN\n'
    /** The second line of an OK that vouches for nothing yet. */
    static final String RELOADING = 'reloading\n'

    /** The sweep runs every minute, so this is several missed. */
    static final long SWEEP_LATE_MS = 5L * 60 * 1000
    /** A cluster's last check is this many intervals old. PRB-04 fires about here too: 2 intervals past a turn is about 3 since the last check. */
    static final int STALE_INTERVALS = 3
    /** A silent host holds a check for up to 160 s, so a very short interval must not make one read as stale. */
    static final long MIN_STALE_MS = 5L * 60 * 1000
    /** After a load, unchecked clusters read as reloading for this long. The first round takes about 11 minutes at 200 clusters: see HealthMonitor.DEFAULT_CHECK_CONCURRENCY. */
    static final long RELOAD_GRACE_MS = 15L * 60 * 1000

    static List answer(String path, Map s, long now = System.currentTimeMillis()) {
        if (path == '/alive') {
            return alive(s, now) ? [200, ALIVE] : [503, STALE]
        }
        if (path == '/status') {
            return status(s, now)
        }
        def m = (path ?: '') =~ /^\/cluster\/(\d{1,18})$/
        m.matches() ? cluster(m.group(1) as Long, s, now) : [404, UNKNOWN]
    }

    private static boolean sweepLate(Map s, long now) {
        s.running != true || now - ((s.lastSweepAt ?: s.loadedAt ?: 0L) as long) > SWEEP_LATE_MS
    }

    /** Whether the monitor is doing its job. Until the first sweep, loading counts: a plugin reload is not an outage. With automatic checks off, nothing is watching, and that must not read as fine. */
    private static boolean alive(Map s, long now) {
        !sweepLate(s, now) && s.behind != true && (s.lastSweepAt == null || (s.quickMs as long) > 0)
    }

    static boolean stale(Map view, long quickMs, long now) {
        quickMs <= 0 || !view?.at || now - (view.at as long) > Math.max(STALE_INTERVALS * quickMs, MIN_STALE_MS)
    }

    /**
     * Just after a load, while the monitor is doing its job. A Web Check takes STALE for an incident, so a reload would otherwise open one per cluster for what is nobody's problem. A sweep
     * that does not come, or automatic checks turned off, means nothing is on its way to end this, so neither reads as reloading.
     */
    private static boolean inGrace(Map s, long now) {
        s.startedAt != null && now - (s.startedAt as long) < RELOAD_GRACE_MS && !sweepLate(s, now) && (s.lastSweepAt == null || (s.quickMs as long) > 0)
    }

    /** CRITICAL, RELOADING, STALE, WARNING or OK. What is known to be wrong outranks all of it, and being out of date outranks a window's OK. */
    static String state(Long id, Map s, long now) {
        Map view = (s.views as Map)?.get(id) as Map
        String paged = Paging.state(id, view, now)
        if (paged == 'CRITICAL') {
            return paged
        }
        if (inGrace(s, now) && (!view?.at || (view.at as long) < (s.startedAt as long))) {
            return 'RELOADING'
        }
        !view || stale(view, s.quickMs as long, now) ? 'STALE' : paged
    }

    private static final Map<String, List> ANSWERS = [CRITICAL: [503, CRITICAL], RELOADING: [200, OK + RELOADING], STALE: [503, STALE], WARNING: [200, WARNING], OK: [200, OK], UNKNOWN: [404, UNKNOWN]]

    private static String word(Long id, Map s, long now) {
        if (s.lastSweepAt == null && !inGrace(s, now)) {
            return 'STALE'   // nothing is known yet, including whether this id is monitored
        }
        if (s.lastSweepAt != null && !((s.ids as Collection)?.contains(id))) {
            return 'UNKNOWN'
        }
        state(id, s, now)
    }

    private static List cluster(Long id, Map s, long now) {
        ANSWERS[word(id, s, now)]
    }

    /** What the node holding the lease puts in its row: everything another node needs to say what this one would, a minute behind. 200 clusters come to a few KB. */
    static Map summary(Map s, long now) {
        [alive: alive(s, now), status: status(s, now), clusters: ((s.ids ?: []) as Collection<Long>).collectEntries { [(it.toString()): word(it, s, now)] }, other: word(0L, s, now)]
    }

    /** What a node standing by says, from that row. A row older than a sweep may be says STALE, whatever was in it. */
    static List fromRow(String path, Map row, long now) {
        Map sum = row?.summary as Map
        boolean old = !sum || Lease.age(row, now) > SWEEP_LATE_MS
        if (path == '/alive') {
            return !old && sum.alive == true ? [200, ALIVE] : [503, STALE]
        }
        if (path == '/status') {
            return old ? [503, STALE] : sum.status as List
        }
        def m = (path ?: '') =~ /^\/cluster\/(\d{1,18})$/
        if (!m.matches()) {
            return [404, UNKNOWN]
        }
        old ? [503, STALE] : ANSWERS[(sum.clusters as Map)?.get(m.group(1)) ?: sum.other] ?: [503, STALE]
    }

    private static List status(Map s, long now) {
        Collection<Long> ids = (s.lastSweepAt == null ? [] : s.ids ?: []) as Collection<Long>
        List<String> states = ids.collect { state(it, s, now) }
        int critical = states.count('CRITICAL'), warning = states.count('WARNING'), stale = states.count('STALE')
        String counts = "clusters=${ids.size()} critical=${critical} warning=${warning} stale=${stale} held=${ids.count { Paging.held(it, now) }} reloading=${states.count('RELOADING')}\n".toString()
        if (critical) {
            return [503, CRITICAL + counts]
        }
        if (s.lastSweepAt == null && inGrace(s, now)) {
            return [200, OK + counts]   // the two minutes before the first sweep: the same grace, with no clusters to count yet
        }
        // none of it can be vouched for: the monitor is not running, or every cluster is out of date
        if (s.lastSweepAt == null || sweepLate(s, now) || (s.quickMs as long) <= 0 || (ids && stale == ids.size())) {
            return [503, STALE + counts]
        }
        warning || stale ? [200, WARNING + counts] : [200, OK + counts]
    }
}
