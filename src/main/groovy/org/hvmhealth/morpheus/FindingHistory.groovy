package org.hvmhealth.morpheus

import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.data.DataQuery
import com.morpheusdata.model.Account
import com.morpheusdata.model.OperationData
import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import groovy.util.logging.Slf4j
import org.hvmhealth.checks.HealthChecker

import java.util.concurrent.ConcurrentHashMap

/**
 * When each finding first appeared and when it cleared, per cluster, kept across plugin reloads in one OperationData row per
 * cluster: the plugin API's store for a provider's own data. A plugin cannot write its own settings, and the cluster record
 * travels with everything that reads a cluster.
 *
 * Only what is below is stored: no observed text, no raw host output, no tenant, VM or network names. A row is replaced, never
 * updated (OperationData's setters do not mark fields dirty), and read back, because the API does not say how large a field
 * may be. Whatever goes wrong here is logged and shown on the tab; it never fails a check.
 */
@Slf4j
class FindingHistory {
    static final String CATEGORY = 'hvm-cluster-health-history'
    static final long RETENTION_MS = 365L * 24 * 3600 * 1000
    /** A finding back this soon after clearing is the same episode, so flapping does not fill the history. */
    static final long FLAP_MS = 3600L * 1000
    static final long SAVE_EVERY_MS = 15L * 60 * 1000
    static final int MAX_EPISODES = 500
    /** Fits a 64 KiB text column, the smallest the field is likely to be. */
    static final int MAX_BYTES = 60_000
    static final int MAX_TEXT = 160

    /** clusterId -> why its history could not be read or saved, for the tab. */
    static final Map<Long, String> ERRORS = new ConcurrentHashMap<>()
    private static final Map<Long, Object> LOCKS = new ConcurrentHashMap<>()
    private static volatile long lastPrune = 0

    /** NET-11's title carries a Morpheus network's name, which can be a tenant's, and NET-12's and NET-13's end in a VM's. */
    static String safeTitle(String id, String title) {
        id == 'NET-11' ? title?.replaceFirst(/ \(.*\) carries/, ' carries') : (id in ['NET-12', 'NET-13'] ? title?.replaceFirst(/: .*$/, '') : title)
    }

    /** A renamed network is the same finding; two VMs on one host are two, so the VM's name stays in the key, hashed. */
    static String key(String id, String host, String title) {
        "${id}|${host ?: ''}|${id == 'NET-11' ? safeTitle(id, title) : title}".toString().digest('MD5').take(12)
    }

    static Map empty(long now) {
        [v: 1, since: now, savedAt: 0L, dropped: 0, summary: null, episodes: []]
    }

    /** What this run can vouch for: a finding that is absent only counts as cleared if the run could have seen it. */
    static Map coverage(HealthChecker.Input in_, boolean fullKnown) {
        [hosts: in_.hosts*.name as Set, answered: in_.hosts.findAll { it.facts?.complete }*.name as Set, fullKnown: fullKnown]
    }

    private static boolean clearable(Map e, Map cover) {
        Set hosts = cover.hosts as Set
        if (!hosts) {
            return false   // Morpheus listed no hosts: says nothing about the findings
        }
        if (e.id in HealthMonitor.FULL_ONLY && !cover.fullKnown) {
            return false   // only a full check answers this, and none has run since the plugin loaded
        }
        if (e.host) {
            return !hosts.contains(e.host) || (cover.answered as Set).contains(e.host)   // a host no longer in the cluster has nothing left to report
        }
        (cover.answered as Set).containsAll(hosts)
    }

    /** Brings the document in line with one evaluation. True when something changed that is worth saving now. */
    static boolean apply(Map doc, List<HealthChecker.Result> rs, Map cover, Map summary, long now) {
        List<Map> eps = doc.episodes as List<Map>
        Map<String, HealthChecker.Result> active = [:]
        rs.findAll { it.status in [HealthChecker.Status.FAIL, HealthChecker.Status.WARN] }.each { active.putIfAbsent(key(it.id, it.host, it.title), it) }   // worst first
        Set<String> notChecked = rs.findAll { it.status == HealthChecker.Status.NOT_CHECKED }.collect { key(it.id, it.host, it.title) } as Set
        boolean changed = false
        active.each { String k, HealthChecker.Result r ->
            Map e = eps.find { it.k == k && it.cleared == null }
            if (!e) {
                e = eps.findAll { it.k == k && now - (it.cleared as long) <= FLAP_MS }.max { it.cleared as long }
                if (e) {
                    e.cleared = null
                    e.returns = ((e.returns ?: 0) as int) + 1
                } else {
                    e = [k: k, id: r.id, title: safeTitle(r.id, r.title)?.take(MAX_TEXT), host: r.host?.take(MAX_TEXT), first: now, cleared: null, returns: 0, worst: r.status.name()]
                    eps << e
                }
                changed = true
            }
            if (e.status != r.status.name()) {
                changed = true
            }
            e.status = r.status.name()
            e.severity = r.severity.name()
            e.worst = e.worst == 'FAIL' || r.status == HealthChecker.Status.FAIL ? 'FAIL' : 'WARN'
            e.last = now
        }
        eps.findAll { it.cleared == null && !active.containsKey(it.k) && !notChecked.contains(it.k) && clearable(it, cover) }.each {
            it.cleared = now
            changed = true
        }
        changed = prune(doc, now) || changed
        changed = (doc.summary as Map)?.overall != summary?.overall || changed
        doc.summary = summary
        changed
    }

    /** Retention: a cleared finding is kept a year, then at most MAX_EPISODES. What is still open always stays. */
    static boolean prune(Map doc, long now) {
        List<Map> eps = doc.episodes as List<Map>
        int before = eps.size()
        eps.removeAll { it.cleared != null && now - (it.cleared as long) > RETENTION_MS }
        boolean changed = eps.size() != before
        int over = eps.size() - MAX_EPISODES
        if (over > 0) {
            dropOldestCleared(doc, over)
            changed = true
        }
        changed
    }

    private static int dropOldestCleared(Map doc, int n) {
        List<Map> eps = doc.episodes as List<Map>
        List<Map> drop = eps.findAll { it.cleared != null }.sort { it.cleared as long }.take(n)
        eps.removeAll { Map e -> drop.any { it.is(e) } }
        doc.dropped = ((doc.dropped ?: 0) as int) + drop.size()
        drop.size()
    }

    /** JSON within MAX_BYTES. Older cleared findings go first, and are counted; the newest open ones only if nothing else is left. */
    static String encode(Map doc) {
        doc.remove('overflow')
        String json = JsonOutput.toJson(doc)
        while (json.getBytes('UTF-8').length > MAX_BYTES) {
            List<Map> eps = doc.episodes as List<Map>
            if (!dropOldestCleared(doc, Math.max(1, (eps.size() / 20) as int))) {
                if (!eps) {
                    throw new IllegalStateException('history does not fit even when empty')
                }
                eps.remove(eps.indexOf(eps.max { it.first as long }))
                doc.overflow = true
            }
            json = JsonOutput.toJson(doc)
        }
        json
    }

    static Map summaryOf(Map view) {
        [overall: view.overall, at: view.at, mode: view.mode, layout: view.layout, counts: view.counts,
         notReady: (view.readiness as List<Map>)?.findAll { it.status?.toString() == 'FAIL' }?.collect { it.area },
         frozen: (view.hosts as List<Map>)?.any { it.frozen } ?: false]
    }

    /** Stamps each current finding with when it first appeared. */
    static void stamp(Map view, Map doc) {
        Map<String, Map> open = (doc.episodes as List<Map>).findAll { it.cleared == null }.collectEntries { [(it.k): it] }
        (view.results as List<Map>)?.each { Map r ->
            if (r.status in ['FAIL', 'WARN']) {
                r.firstSeen = open[key(r.id as String, r.host as String, r.title as String)]?.first
            }
        }
    }

    // ------------------------------------------------------------------ storage

    private static List<OperationData> rows(MorpheusContext morpheus, Long clusterId) {
        String ref = clusterId.toString()
        MorpheusSession.run(morpheus) {
            morpheus.services.operationData.list(new DataQuery().withFilter('category', CATEGORY).withFilter('refId', ref))
                    .findAll { it.category == CATEGORY && it.refId == ref }
        }
    }

    private static Map parse(OperationData row) {
        try {
            row?.rawData ? (new JsonSlurper().parseText(row.rawData) as Map) : null
        } catch (Exception e) {
            log.warn("cluster health history row ${row.id} is unreadable, starting again: ${e.message}")
            null
        }
    }

    /** The newest row's document; null when there is none. Throws if the store cannot be read. */
    static Map read(MorpheusContext morpheus, Long clusterId) {
        rows(morpheus, clusterId).collect { parse(it) }.findAll().max { (it.savedAt ?: 0L) as long }
    }

    /** The summary from before the plugin loaded, if there is one. */
    static Map lastKnown(MorpheusContext morpheus, Long clusterId) {
        try {
            read(morpheus, clusterId)?.summary as Map
        } catch (Throwable e) {
            log.debug("no stored summary for cluster ${clusterId}: ${e.message}")
            null
        }
    }

    /**
     * Records one evaluation and stamps the view. If the stored history cannot be read nothing is written, so a passing
     * failure cannot replace a year of history with an empty one.
     */
    static void record(MorpheusContext morpheus, HealthContext ctx, HealthChecker.Input in_, List<HealthChecker.Result> rs, Map view, boolean fullKnown, long now = System.currentTimeMillis()) {
        Long id = ctx.cluster.id
        try {
            synchronized (LOCKS.computeIfAbsent(id) { new Object() }) {
                List<OperationData> old = rows(morpheus, id)
                Map doc = old.collect { parse(it) }.findAll().max { (it.savedAt ?: 0L) as long } ?: empty(now)
                boolean changed = apply(doc, rs, coverage(in_, fullKnown), summaryOf(view), now)
                stamp(view, doc)
                if (changed || !old || now - ((doc.savedAt ?: 0L) as long) >= SAVE_EVERY_MS) {
                    doc.savedAt = now
                    write(morpheus, ctx, doc, old, now)
                }
            }
            ERRORS.remove(id)
        } catch (Throwable e) {
            log.warn("cluster health history for ${ctx.cluster.name} not saved: ${e.message}")
            ERRORS[id] = shortError(e)
        }
    }

    /** Grails validation errors run to several KB, which is no use on a tab. The log keeps the whole message. */
    static String shortError(Throwable e) {
        String first = (e.message ?: e.class.simpleName).toString().readLines().find { it.trim() } ?: e.class.simpleName
        first.length() > 200 ? first.take(200) + '...' : first
    }

    /** The new row first, read back, and only then the old one goes: a failure leaves what was there. */
    private static void write(MorpheusContext morpheus, HealthContext ctx, Map doc, List<OperationData> old, long now) {
        String json = encode(doc)
        Account owner = HealthMonitor.masterAccount(morpheus) ?: ctx.cluster.owner   // kept with the master tenant, whichever tenant owns the cluster
        String ref = ctx.cluster.id.toString()
        def store = morpheus.services.operationData
        OperationData saved = MorpheusSession.run(morpheus) {
            // Morpheus rejects a row with no keyValue or value, though the plugin API does not say they are required
            String code = "${CATEGORY}-${ref}-${now}".toString()
            store.create(new OperationData(account: owner, code: code, keyValue: code, value: now.toString(), category: CATEGORY, name: 'HVM Cluster Health history',
                    refType: 'computeServerGroup', refId: ref, refDate: new Date(now), rawData: json))
        }
        if (saved?.id == null) {
            throw new IllegalStateException('Morpheus did not store the history record')
        }
        boolean kept = MorpheusSession.run(morpheus) { store.get(saved.id)?.rawData == json }
        MorpheusSession.run(morpheus) { (kept ? old : [saved]).each { store.remove(it) } }
        if (!kept) {
            throw new IllegalStateException("Morpheus did not keep the history record whole (${json.length()} characters)")
        }
    }

    /** A cluster's history outlives it by the retention period at most. Daily, and never when Morpheus lists no clusters. True when today's turn ran. */
    static boolean pruneOrphans(MorpheusContext morpheus, long now = System.currentTimeMillis()) {
        if (now - lastPrune < 24L * 3600 * 1000) {
            return false
        }
        lastPrune = now
        try {
            Set<String> live = morpheus.services.cluster.list(new DataQuery()).collect { it.id.toString() } as Set
            if (!live) {
                return true
            }
            MorpheusSession.run(morpheus) {
                morpheus.services.operationData.list(new DataQuery().withFilter('category', CATEGORY))
                        .findAll { it.category == CATEGORY && !live.contains(it.refId) && now - (it.refDate?.time ?: 0L) > RETENTION_MS }
                        .each { morpheus.services.operationData.remove(it) }
            }
        } catch (Throwable e) {
            log.warn("cluster health history clean-up failed: ${e.message}")
        }
        true
    }
}
