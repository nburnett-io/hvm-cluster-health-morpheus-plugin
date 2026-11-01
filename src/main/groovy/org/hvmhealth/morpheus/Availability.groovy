package org.hvmhealth.morpheus

import com.morpheusdata.core.MorpheusContext
import groovy.json.JsonOutput
import groovy.util.logging.Slf4j
import org.hvmhealth.checks.HealthChecker
import org.hvmhealth.model.HostFacts

import java.math.RoundingMode
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneOffset
import java.time.format.DateTimeParseException
import java.util.concurrent.ConcurrentHashMap

/**
 * Freeze time, stalls and unplanned VM stops, per cluster and per tenant, by month in UTC. Evidence for SLA credits and customer outage reports, so it
 * only claims what the plugin saw: time with no check is counted as not observed, which is shown as unknown and never as up.
 *
 * Its own row per cluster, written when a freeze starts, so a reload does not lose it, and when it ends. In between about hourly, or every 15 minutes during
 * a freeze. Everything else stays in memory, so a check reads no row. Holds tenant and VM names, as the point is to say whose: no host, storage or finding text.
 */
@Slf4j
class Availability {
    static final String CATEGORY = 'hvm-cluster-health-availability'
    /** A freeze this short is a stall: well past the 2 minutes or so HVM's agent takes to acknowledge a lost host by itself. */
    static final long STALL_SEC = 300
    static final long SAVE_EVERY_MS = 3600L * 1000
    static final long SAVE_FROZEN_EVERY_MS = 15L * 60 * 1000
    /** Checks further apart than this, or 3 intervals if that is longer, mean nobody was looking in between. */
    static final long MIN_GAP_MS = 15L * 60 * 1000
    /** A freeze found over on the first look after this long says when it started, and not when it ended: the rest is not observed. */
    static final long UNKNOWN_AFTER_MS = 30L * 60 * 1000
    static final int MAX_MONTHS = 13
    static final int MAX_VM_STOPS = 20
    private static final long SAME_STOP_MS = 60_000L
    private static final long STORED_MS = 5L * 60 * 1000
    static final String MEASURES = 'These figures measure what the plugin saw. A period with no checks is shown as not observed, which means unknown, never as up. Months are in UTC. Frozen means a DLM lockspace was stopped, so storage on that cluster was blocked. A stall is a freeze of 5 minutes or less, or storage not answering while DLM looked healthy.'
    static final List<String> CSV_COLUMNS = ['section', 'tenant', 'month', 'cluster_ref', 'frozen_minutes', 'running_vms', 'unplanned_vm_stops', 'observed_minutes', 'unknown_minutes', 'unattributed_frozen_minutes', 'vm', 'stopped_at', 'note']

    /** clusterId -> why its figures could not be read or saved. */
    static final Map<Long, String> ERRORS = new ConcurrentHashMap<>()
    private static final Map<Long, Map> DOCS = new ConcurrentHashMap<>()
    private static final Set<Long> UNSAVED = ConcurrentHashMap.newKeySet()
    private static final Map<Long, Object> LOCKS = new ConcurrentHashMap<>()
    private static volatile Map<Long, Map> stored = null
    private static volatile long storedAt = 0L

    private static Object lock(Long clusterId) {
        LOCKS.computeIfAbsent(clusterId) { new Object() }
    }

    // ------------------------------------------------------------------ the document, with no storage

    static Map empty(long now) {
        [v: 1, since: now, savedAt: 0L, lastRun: null, open: null, stalling: false, running: null, stops: [:], months: [:]]
    }

    static String monthKey(long ms) {
        YearMonth.from(Instant.ofEpochMilli(ms).atZone(ZoneOffset.UTC)).toString()
    }

    private static long monthStart(String key) {
        YearMonth.parse(key).atDay(1).atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli()
    }

    private static long monthEnd(String key) {
        YearMonth.parse(key).plusMonths(1).atDay(1).atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli()
    }

    /** Whole seconds of a period per month it falls in. */
    static Map<String, Long> split(long from, long to) {
        Map<String, Long> out = [:]
        long at = from
        while (at < to) {
            String k = monthKey(at)
            long end = Math.min(to, monthEnd(k))
            out[k] = (out[k] ?: 0L) + (((end - at) / 1000) as long)
            at = end
        }
        out
    }

    private static Map month(Map doc, String key) {
        Map months = doc.months as Map
        if (months[key] == null) {
            months[key] = [watched: 0L, frozen: 0L, freezes: 0, stalls: 0, stops: 0, unattributed: 0L, t: [:]]
        }
        months[key] as Map
    }

    private static Map tenant(Map m, Object id, Object name) {
        Map t = m.t as Map
        String k = id.toString()
        if (t[k] == null) {
            t[k] = [n: name, sec: 0L, vms: 0, stops: 0, list: []]
        }
        if (name) {
            (t[k] as Map).n = name
        }
        t[k] as Map
    }

    private static void bump(Map m, String field, long by) {
        m[field] = ((m[field] ?: 0L) as long) + by
    }

    /** Tenants with running VMs, from Morpheus: tenant id -> [n: name, vms: count]. */
    static Map tenantsOn(List<Map> vms) {
        vms.findAll { it.power == 'on' && it.tenantId != null }.groupBy { it.tenantId.toString() }.collectEntries { String id, List<Map> own ->
            [(id): [n: own[0].tenant, vms: own.size()]]
        }
    }

    /** Idempotent. Who it reaches comes from the last run that could vouch for it, as Morpheus stops reporting VMs as running on a host that has gone silent. */
    static boolean openFreeze(Map doc, long at, long seen) {
        if (doc.open != null) {
            (doc.open as Map).seen = Math.max(((doc.open as Map).seen ?: 0L) as long, seen)
            return false
        }
        doc.open = [at: at, seen: seen, t: doc.running]
        true
    }

    /** With {@code trusted}, the caller watched it to the end. Otherwise a long silence before this look stays not observed, rather than counted as frozen. */
    static boolean closeFreeze(Map doc, long at, boolean trusted) {
        Map o = doc.open as Map
        if (o == null) {
            return false
        }
        long start = o.at as long, seen = (o.seen ?: o.at) as long, end = Math.max(start, at)
        long frozenTo = !trusted && end - seen > UNKNOWN_AFTER_MS ? seen : end
        split(start, frozenTo).each { String k, Long sec ->
            Map m = month(doc, k)
            bump(m, 'frozen', sec)
            if (o.t == null) {
                bump(m, 'unattributed', sec)
            } else {
                (o.t as Map).each { Object id, Object v ->
                    Map t = tenant(m, id, (v as Map).n)
                    bump(t, 'sec', sec)
                    t.vms = Math.max((t.vms ?: 0) as int, ((v as Map).vms ?: 0) as int)
                }
            }
        }
        Map first = month(doc, monthKey(start))
        bump(first, (frozenTo - start) / 1000 <= STALL_SEC ? 'stalls' : 'freezes', 1L)
        doc.open = null
        true
    }

    private static boolean namedIn(String name, String text) {
        name && (text =~ /(?<![\w-])${java.util.regex.Pattern.quote(name)}(?![\w-])/).find()
    }

    /**
     * VM-06 keeps a crash open for 24 h, so what is remembered is when each VM last stopped, until it leaves there: a stop counts when its time is later than that, so a second crash in those 24 h counts too. The
     * kernel's line and libvirt's for one kill, seconds apart, are one stop. A stop with no time on record counts when the VM first appears. A VM Morpheus does not know counts for the cluster and for no tenant.
     */
    private static boolean recordStops(Map doc, HealthChecker.Input in_, List<HealthChecker.Result> rs, boolean allAnswered, long now) {
        List<Map> vms = in_.morpheusVms ?: []
        Map open = doc.stops as Map
        Set<String> current = [] as Set
        boolean changed = false
        Map m = month(doc, monthKey(now))
        Closure<List<Long>> newStops = { String key, List<Long> times ->
            current << key
            Long known = open[key] as Long
            long last = known ?: 0L
            List<Long> found = times.sort(false).findAll { Long t ->
                boolean again = t - last > SAME_STOP_MS
                last = again ? t : last
                again
            }
            if (!found && known == null) {
                found = [now]
                last = now
            }
            if (found) {
                open[key] = last
                bump(m, 'stops', found.size() as long)
                changed = true
            }
            found
        }
        rs.findAll { it.id == 'VM-06' && it.status in [HealthChecker.Status.FAIL, HealthChecker.Status.WARN] }.each { HealthChecker.Result r ->
            // by libvirt's name where the host's facts have one: a label can repeat across tenants, and VM-06's text stops at 8 VMs
            HostFacts f = in_.hosts.find { it.name == r.host }?.facts
            Map<String, List<Long>> times = f ? HealthChecker.stopTimes(f, in_.nowMs) : [:]
            Set<String> libvirt = times.keySet()
            String text = "${r.title} ${r.observed}".toString()
            List<Map> named = libvirt
                    ? libvirt.findResults { String d -> vms.find { it.name == d && it.host == r.host } ?: vms.find { it.name == d } } as List<Map>
                    : vms.findAll { Map v -> namedIn(v.name as String, text) || namedIn(v.label as String, text) }
            named.each { Map v ->
                List<Long> found = newStops.call("${v.tenantId}|${v.name}".toString().digest('MD5').take(12), (times[v.name as String] ?: []) as List<Long>)
                if (found && v.tenantId != null) {
                    Map t = tenant(m, v.tenantId, v.tenant)
                    bump(t, 'stops', found.size() as long)
                    found.each { Long at ->
                        if ((t.list as List).size() < MAX_VM_STOPS) {
                            (t.list as List) << [vm: v.label ?: v.name, at: at]
                        }
                    }
                }
            }
            (libvirt - named*.name).each { newStops.call(FindingHistory.key(r.id, r.host, it), times[it]) }
            if (!named && !libvirt) {
                newStops.call(FindingHistory.key(r.id, r.host, r.title), [])
            }
        }
        // a finding only clears if the run could have seen it: a silent host's crash must not count twice when it comes back
        if (allAnswered && !rs.any { it.id == 'VM-06' && it.status == HealthChecker.Status.NOT_CHECKED }) {
            changed = open.keySet().removeAll { !(it in current) } || changed
        }
        changed
    }

    /**
     * One run's worth. True when something happened that should be saved now. A pure function of its arguments, apart from the document.
     * With {@code watching}, the freeze watch has this cluster, and it reads one sighting again before that counts, as a lockspace also stops for a moment when a host mounts or
     * leaves. So a check's sighting alone starts nothing here: if it was a freeze, the watch says so, dated from that sighting. One already open stays open.
     */
    static boolean observe(Map doc, HealthChecker.Input in_, List<HealthChecker.Result> rs, long quickMs, Long watchSince, long now, boolean watching = false) {
        List<HealthChecker.Host> hs = in_.hosts
        boolean anyAnswer = hs.any { it.facts?.complete || it.facts?.partial }
        boolean allAnswered = hs && hs.every { it.facts?.complete }
        boolean sighted = hs.any { it.facts?.frozen }
        boolean changed = false
        boolean wasOpen = doc.open != null
        boolean frozen = watchSince != null || (sighted && (wasOpen || !watching))
        if (allAnswered && !sighted && watchSince == null) {
            doc.running = tenantsOn(in_.morpheusVms)
            Map m = month(doc, monthKey(now))
            (doc.running as Map).each { Object id, Object v ->
                Map t = tenant(m, id, (v as Map).n)
                t.vms = Math.max((t.vms ?: 0) as int, ((v as Map).vms ?: 0) as int)
            }
        }
        if (frozen) {
            changed = openFreeze(doc, watchSince ?: now, now)
            if ((doc.open as Map).t == null && in_.morpheusVms) {
                (doc.open as Map).t = tenantsOn(in_.morpheusVms)   // no run before it could vouch for anyone, and this beats nothing
                changed = true
            }
        } else if (wasOpen && (allAnswered || hs.any { it.facts?.complete && it.facts.lockspaces })) {
            changed = closeFreeze(doc, now, false)
        }
        // nothing was seen by a run no host answered, so that time stays unknown
        if (anyAnswer) {
            Long last = doc.lastRun as Long
            if (last != null && now > last && (now - last <= Math.max(MIN_GAP_MS, 3 * quickMs) || (wasOpen && doc.open != null))) {
                split(last, now).each { String k, Long sec -> bump(month(doc, k), 'watched', sec) }
            }
            doc.lastRun = now
        }
        boolean stalled = rs.any { it.id == 'GFS-07' && it.status == HealthChecker.Status.FAIL }
        if (stalled && !doc.stalling) {
            bump(month(doc, monthKey(now)), 'stalls', 1L)
            doc.stalling = true
            changed = true
        } else if (!stalled && doc.stalling && allAnswered) {
            doc.stalling = false
            changed = true
        }
        recordStops(doc, in_, rs, allAnswered, now) || changed
    }

    /** Within the cap: the oldest months go first, then lists of VMs from the oldest on. */
    static String encode(Map doc) {
        Map months = doc.months as Map
        (months.keySet().sort() as List<String>).reverse().drop(MAX_MONTHS).each { months.remove(it) }
        String json = JsonOutput.toJson(doc)
        while (json.getBytes('UTF-8').length > FindingHistory.MAX_BYTES) {
            List<String> keys = months.keySet().sort() as List<String>
            if (keys.size() > 1) {
                months.remove(keys[0])
            } else {
                List<Map> lists = keys ? (months[keys[0]] as Map).t.values().findAll { it.list } as List<Map> : []
                if (!lists) {
                    throw new IllegalStateException('availability figures do not fit even when cut back')
                }
                lists.each { it.list = [] }
            }
            json = JsonOutput.toJson(doc)
        }
        json
    }

    private static BigDecimal minutes(Object sec) {
        ((sec ?: 0L) as BigDecimal).divide(60G, 1, RoundingMode.HALF_UP)
    }

    /** The seconds of a freeze still open that fall in this month. */
    private static long ongoing(Map doc, String key, long now) {
        Map o = doc.open as Map
        o ? (split(o.at as long, now)[key] ?: 0L) : 0L
    }

    /** One month of one cluster, with a freeze still open counted up to now. */
    static Map figures(Map doc, String key, long now) {
        Map m = ((doc.months as Map)[key] ?: [:]) as Map
        long from = Math.max(monthStart(key), (doc.since ?: 0L) as long), to = Math.min(monthEnd(key), now)
        long period = Math.max(0L, ((to - from) / 1000) as long)
        long open = ongoing(doc, key, now)
        long observed = Math.min(period, ((m.watched ?: 0L) as long))
        Map t = ((doc.open as Map)?.t ?: [:]) as Map
        [month: key, frozenMinutes: minutes(((m.frozen ?: 0L) as long) + open), freezes: (m.freezes ?: 0) as int, stalls: (m.stalls ?: 0) as int, vmStops: (m.stops ?: 0) as int,
         periodMinutes: minutes(period), observedMinutes: minutes(observed), unknownMinutes: minutes(period - observed),
         unattributedMinutes: minutes(((m.unattributed ?: 0L) as long) + (doc.open && (doc.open as Map).t == null ? open : 0L)), ongoing: open > 0L,
         tenants: (((m.t ?: [:]) as Map).keySet() + t.keySet()).toSet().collect { Object id ->
             Map own = ((m.t as Map)?.get(id) ?: [:]) as Map
             Map now_ = (t[id] ?: [:]) as Map
             [id: id as Long, name: own.n ?: now_.n, frozenMinutes: minutes(((own.sec ?: 0L) as long) + (t[id] != null ? open : 0L)),
              runningVms: Math.max((own.vms ?: 0) as int, (now_.vms ?: 0) as int), vmStops: (own.stops ?: 0) as int]
         }.sort { a, b -> b.frozenMinutes <=> a.frozenMinutes ?: b.vmStops <=> a.vmStops ?: (a.name as String) <=> (b.name as String) }]
    }

    /**
     * What may be handed to one customer: this tenant's own figures, on clusters it has VMs on, and nothing else. A cluster goes by its id, as its name can
     * hold another tenant's. Built only from the tenant's own entries, so nothing else on the cluster can reach it. Cluster-wide counts are left out for that reason.
     */
    static Map tenantReport(Map<Long, Map> docs, Long tenantId, String key, long now) {
        String id = tenantId.toString()
        String name = null
        List<Map> rows = []
        List<Map> stops = []
        docs.keySet().sort().each { Long clusterId ->
            Map doc = docs[clusterId]
            synchronized (lock(clusterId)) {
                Map own = (((doc.months as Map)[key] as Map)?.t as Map)?.get(id) as Map
                Map openT = ((doc.open as Map)?.t as Map)?.get(id) as Map
                if (own == null && openT == null) {
                    return
                }
                Map f = figures(doc, key, now)
                Map mine = f.tenants.find { it.id == tenantId } as Map
                name = name ?: mine.name
                rows << [cluster: "cluster-${clusterId}".toString(), frozenMinutes: mine.frozenMinutes, runningVms: mine.runningVms, vmStops: mine.vmStops,
                         observedMinutes: f.observedMinutes, unknownMinutes: f.unknownMinutes, unattributedMinutes: f.unattributedMinutes, ongoing: f.ongoing]
                stops.addAll(((own?.list ?: []) as List<Map>).collect { [cluster: "cluster-${clusterId}".toString(), vm: it.vm, at: HealthReport.iso(it.at)] })
            }
        }
        Map r = [tenantId: tenantId, tenant: name, month: key, generated: HealthReport.iso(now), clusters: rows, vmStops: stops, measures: MEASURES,
                 fileBase: "availability-${((name ?: 'tenant') as String).replaceAll(/[^A-Za-z0-9._-]+/, '-')}-${key}".toString()]
        r.csv = csv(r)
        r
    }

    static String csv(Map r) {
        List<List> rows = [CSV_COLUMNS]
        (r.clusters as List<Map>).each { Map c ->
            rows << ['summary', r.tenant, r.month, c.cluster, c.frozenMinutes, c.runningVms, c.vmStops, c.observedMinutes, c.unknownMinutes, c.unattributedMinutes, null, null, c.ongoing ? 'a freeze is still open' : null]
        }
        (r.vmStops as List<Map>).each { Map s ->
            rows << ['vm_stop', r.tenant, r.month, s.cluster, null, null, null, null, null, null, s.vm, s.at, null]
        }
        rows << ['note', r.tenant, r.month, null, null, null, null, null, null, null, null, null, r.measures]
        rows.collect { List c -> c.collect { HealthReport.cell(it) }.join(',') }.join('\r\n') + '\r\n'
    }

    // ------------------------------------------------------------------ storage

    /** The document, from memory once it has been read. Throws if the store cannot be read, and is then never written over. On a node standing by the row is always newer, and whoever asked is a person. */
    static Map doc(MorpheusContext morpheus, Long clusterId, long now = System.currentTimeMillis()) {
        if (HealthMonitor.standingBy()) {
            return PluginRow.read(morpheus, CATEGORY, clusterId) ?: empty(now)
        }
        Map d = DOCS[clusterId]
        if (d == null) {
            d = PluginRow.read(morpheus, CATEGORY, clusterId) ?: empty(now)
            DOCS[clusterId] = d
        }
        d
    }

    private static void save(MorpheusContext morpheus, Long clusterId, Map d, long now) {
        try {
            d.savedAt = now
            PluginRow.replace(morpheus, CATEGORY, 'HVM Cluster Health availability', clusterId, encode(d), now)
            UNSAVED.remove(clusterId)
            ERRORS.remove(clusterId)
        } catch (Throwable e) {
            UNSAVED << clusterId
            throw e
        }
    }

    /** What a freeze must not lose: written now. Never fails the watch that calls it. Starting twice, or ending one that is not open, changes nothing. */
    private static void change(MorpheusContext morpheus, Long clusterId, long at, Closure<Boolean> c) {
        try {
            synchronized (lock(clusterId)) {
                Map d = doc(morpheus, clusterId, at)
                if (c.call(d) || UNSAVED.contains(clusterId)) {
                    save(morpheus, clusterId, d, at)
                }
            }
            ERRORS.remove(clusterId)
        } catch (Throwable e) {
            log.warn("cluster health availability for cluster ${clusterId} not saved: ${e.message}")
            ERRORS[clusterId] = FindingHistory.shortError(e)
        }
    }

    static void freezeStarted(MorpheusContext morpheus, Long clusterId, long at) {
        change(morpheus, clusterId, at) { Map d -> openFreeze(d, at, at) }
    }

    static void freezeEnded(MorpheusContext morpheus, Long clusterId, long at) {
        change(morpheus, clusterId, at) { Map d -> closeFreeze(d, at, true) }
    }

    /** A freeze left open from before a load, found over by a check. Nothing watched its end, so a long silence stays not observed. Ending none changes nothing. */
    static void freezeFoundOver(MorpheusContext morpheus, Long clusterId, long at) {
        change(morpheus, clusterId, at) { Map d -> closeFreeze(d, at, false) }
    }

    /** After a check, by the monitor. A freeze with the watch off still counts, and one it missed the end of still closes. Never fails the check. */
    static void afterRun(MorpheusContext morpheus, Long clusterId, HealthChecker.Input in_, List<HealthChecker.Result> rs, Map settings, long now = System.currentTimeMillis()) {
        change(morpheus, clusterId, now) { Map d ->
            boolean changed = observe(d, in_, rs, HealthMonitor.quickMinutes(settings) * 60_000L, FreezeWatch.FROZEN_SINCE[clusterId], now, FreezeWatch.forCluster(clusterId) != null)
            changed || now - ((d.savedAt ?: 0L) as long) >= (d.open ? SAVE_FROZEN_EVERY_MS : SAVE_EVERY_MS)
        }
    }

    /**
     * Every cluster's document for the master tenant's views, in at most one query per 5 minutes however many clusters there are or how often the page is opened.
     * What the monitor holds in memory is newer, and wins.
     */
    static Map<Long, Map> allDocs(MorpheusContext morpheus, long now = System.currentTimeMillis()) {
        if (stored == null || now - storedAt >= STORED_MS) {
            stored = PluginRow.readAll(morpheus, CATEGORY)
            storedAt = now
        }
        [:] + stored + DOCS
    }

    private static String checkedMonth(String month, long now) {
        if (!month) {
            return monthKey(now)
        }
        try {
            YearMonth.parse(month).toString()
        } catch (DateTimeParseException ignored) {
            throw new IllegalArgumentException('month must be like 2030-01')
        }
    }

    /** The availability route: one cluster, for its operators. An unread store is said, never shown as no freezes. */
    static Map forCluster(MorpheusContext morpheus, Long clusterId, String month, long now = System.currentTimeMillis()) {
        String key = checkedMonth(month, now)
        try {
            synchronized (lock(clusterId)) {
                Map d = doc(morpheus, clusterId, now)
                return figures(d, key, now) + [months: ((d.months as Map).keySet() + [monthKey(now)]).toSet().sort().reverse(), measures: MEASURES, error: ERRORS[clusterId]]
            }
        } catch (Throwable e) {
            log.warn("cluster health availability for cluster ${clusterId} not read: ${e.message}")
            return [month: key, months: [key], measures: MEASURES, error: FindingHistory.shortError(e)]
        }
    }

    /** The all-clusters section: each cluster with figures, and each tenant across them. */
    static Map forFleet(MorpheusContext morpheus, String month, long now = System.currentTimeMillis()) {
        String key = checkedMonth(month, now)
        Map<Long, Map> docs
        try {
            docs = allDocs(morpheus, now)
        } catch (Throwable e) {
            log.warn("cluster health availability not read: ${e.message}")
            return [month: key, clusters: [], tenants: [], measures: MEASURES, error: FindingHistory.shortError(e)]
        }
        Map<Long, Map> tenants = [:]
        List<Map> clusters = docs.keySet().sort().collect { Long clusterId ->
            Map f
            synchronized (lock(clusterId)) {
                f = figures(docs[clusterId], key, now)
            }
            (f.tenants as List<Map>).each { Map t ->
                Map a = tenants[t.id as Long] ?: (tenants[t.id as Long] = [id: t.id, name: t.name, frozenMinutes: 0G, runningVms: 0, vmStops: 0, clusters: 0])
                a.frozenMinutes = (a.frozenMinutes as BigDecimal) + (t.frozenMinutes as BigDecimal)
                a.runningVms = (a.runningVms as int) + (t.runningVms as int)
                a.vmStops = (a.vmStops as int) + (t.vmStops as int)
                a.clusters = (a.clusters as int) + 1
            }
            f.findAll { k, v -> k != 'tenants' } + [clusterId: clusterId]
        }
        [month: key, clusters: clusters, tenants: tenants.values().sort { a, b -> b.frozenMinutes <=> a.frozenMinutes ?: b.vmStops <=> a.vmStops ?: (a.name as String) <=> (b.name as String) },
         measures: MEASURES, error: null]
    }

    /** What a reload does, for tests. */
    static void forget() {
        DOCS.clear()
        UNSAVED.clear()
        ERRORS.clear()
        stored = null
        storedAt = 0L
    }
}
