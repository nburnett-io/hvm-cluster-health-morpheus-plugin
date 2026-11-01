package org.hvmhealth.morpheus

import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.data.DataQuery
import com.morpheusdata.model.Account
import com.morpheusdata.model.ComputeServerGroup
import com.morpheusdata.model.OperationNotification
import groovy.json.JsonSlurper
import groovy.util.logging.Slf4j
import org.hvmhealth.checks.CheckCatalog
import org.hvmhealth.checks.HealthChecker

import java.util.concurrent.ConcurrentHashMap

/**
 * What only shows across clusters, for the master tenant alone: the same finding appearing on several clusters within minutes (FLT-01), and which clusters
 * are one failure from an outage. Neither reaches a cluster's tab or a report, because they name other clusters, whose owners can be different tenants.
 *
 * Held in memory, and only a finding's arrival counts. PKG-03 open on 50 clusters for a year is not a shared cause, so a finding raises this if the history says
 * it first appeared in the last 10 minutes. A reload forgets what was correlating, and the findings themselves keep their own alarms.
 *
 * A cluster in a change window does not count, as rolling work across clusters is one cause everyone already knows about. A confirmed isolation failure still counts, as no window holds those.
 */
@Slf4j
class FleetCorrelator {
    static final long WINDOW_MS = 10L * 60 * 1000
    /** A cluster that has not reported for this long, as it was removed or its checks stopped, no longer counts. */
    static final long FORGET_MS = 3600L * 1000
    /** After a load every cluster runs a first full check, and a check new in this version first appears on all of them at once. That is not a shared cause. */
    static final long STARTUP_GRACE_MS = 30L * 60 * 1000
    static final int GROUP_MIN = 2
    static final int ANY_MIN = 3
    /** Its own, as this class clears whatever else carries it, and PRB-04's alarm is also on the fleet. Not a cluster id either, so a cluster's alarm clean-up leaves it alone. */
    static final String ALARM_PREFIX = "${HealthMonitor.CATEGORY}-fleet-FLT-01-".toString()

    /**
     * Automatic risk groups only count for checks they could explain, as nearly every cluster at a site shares a time server, and two bonds
     * failing has nothing to do with that. Groups from the sharedRisk setting are the operator's own statement, and count for every check.
     */
    private static final Map<String, String> EXPLAINS = [portal: /^(STO|GFS|DLM|HB)-.*|^VM-02$/, time: /^TIM-.*/]

    /** check id -> cluster id -> [at: first seen, last: last reported, id, name] */
    private static final Map<String, Map<Long, Map>> RAISED = new ConcurrentHashMap<>()
    /** cluster id -> what it shares automatically, as "portal:x" and "time:x". Kept when no host answers, as that says nothing about it. */
    private static final Map<Long, Set<String>> RISKS = new ConcurrentHashMap<>()
    /** cluster id -> its last run inside a change window. What first appeared by then belongs to the window, even if it is still open just after the window ends. */
    private static final Map<Long, Long> HELD_AT = new ConcurrentHashMap<>()
    private static volatile long startedAt = System.currentTimeMillis()
    private static Set<String> alarmsOpen = null   // null until Morpheus's own list has been read once since loading

    /**
     * The one table for the all-clusters page's "one failure from an outage". CLU-04's warning still has votes to spare above quorum, so only its failure counts here.
     * The others warn when that redundancy is what has gone.
     */
    static final Map<String, Map> ONE_FAILURE = [
            'CLU-04': [reason: 'no quorum margin: one more host lost stops every datastore', warn: false],
            'CAP-01': [reason: 'no N+1: if the largest host fails, not every VM can restart', warn: true],
            'MIG-04': [reason: 'no N+1 for some VMs: they fit on no other host', warn: true],
            'HA-06' : [reason: 'the Morpheus manager runs on this cluster', warn: true],
            'PKG-03': [reason: 'an agent older than the one that handled a freeze correctly', warn: true],
            'STO-01': [reason: 'a redundant storage path is down, or there is only one', warn: true],
            'NET-01': [reason: 'a redundant network link is down', warn: true],
            'HW-01' : [reason: 'a power supply is failing, or redundancy is not full', warn: true],
    ]

    /** The reasons a cluster is on that list, in the table's order. Null for a summary from before it carried ids: that is not known, which is different from none. */
    static List<String> oneFailure(Map summary) {
        if (summary?.failing == null || summary?.warning == null) {
            return null
        }
        ONE_FAILURE.findAll { String id, Map e -> id in (summary.failing as List) || (e.warn && id in (summary.warning as List)) }.collect { k, v -> v.reason as String }
    }

    // ------------------------------------------------------------------ what each run reports

    private static Set<String> risksOf(HealthChecker.Input in_) {
        in_.hosts.collectMany { h ->
            ((h.facts?.portalDevs ?: []) as List<Map>).findAll { it.portal }.collect { "portal:${it.portal}".toString() } +
                    ((h.facts?.timeServers ?: []) as List<String>).collect { "time:${it}".toString() }
        } as Set<String>
    }

    /**
     * Keeps RAISED in line with one run. A finding that was open when the history began may be years old, so it says nothing about now. One that has gone only
     * leaves if every host answered, as a silent host's findings go missing without being cleared. With {@code inWindow}, this cluster takes itself out, apart from what is never held.
     */
    static void note(Long clusterId, String name, Set<String> risks, List<Map> results, Object historySince, boolean allAnswered, long now, boolean inWindow = false) {
        if (risks) {
            RISKS[clusterId] = risks
        }
        Map<String, Map> open = (results ?: []).findAll { it.status in ['FAIL', 'WARN'] }.collectEntries { [(it.id): it] }
        if (inWindow) {
            HELD_AT[clusterId] = now
            RAISED.each { String check, Map<Long, Map> byCluster ->
                if (!neverHeld(results, check)) {
                    byCluster.remove(clusterId)
                }
            }
            open = open.findAll { String check, Map r -> neverHeld(results, check) }
        }
        Long heldAt = inWindow ? null : HELD_AT[clusterId]
        open.each { String check, Map r ->
            Map<Long, Map> byCluster = RAISED.computeIfAbsent(check) { new ConcurrentHashMap<Long, Map>() }
            Map e = byCluster[clusterId]
            if (e) {
                e.last = now
            } else {
                Long first = results.findAll { it.id == check && it.firstSeen != null }.collect { it.firstSeen as long }.min()
                if (first != null && historySince != null && first > (historySince as long) && now - first <= WINDOW_MS && now - startedAt >= STARTUP_GRACE_MS && (heldAt == null || first > heldAt)) {
                    byCluster[clusterId] = [id: clusterId, name: name, at: first, last: now]
                }
            }
        }
        if (allAnswered) {
            RAISED.each { String check, Map<Long, Map> byCluster ->
                if (!open.containsKey(check)) {
                    byCluster.remove(clusterId)
                }
            }
        }
    }

    /** A window takes nothing out of this but what it holds: not a confirmed isolation failure. The first, confirming, sighting is only a WARN, and a window holds that. */
    private static boolean neverHeld(List<Map> results, String check) {
        check in Isolation.CHECKS && results?.any { it.id == check && it.status == 'FAIL' }
    }

    // ------------------------------------------------------------------ what they add up to

    /** [groups: [name: [id or name]], error]. A number is only ever an id, as in autoFixClusters. A mistake is shown on the page, and stops nothing. */
    static Map parseSharedRisk(String json) {
        if (!json?.trim()) {
            return [groups: [:]]
        }
        Object o
        try {
            o = new JsonSlurper().parseText(json)
        } catch (Exception e) {
            return [groups: [:], error: "The Shared risk groups setting is not valid JSON: ${e.message?.readLines()?.first()}".toString()]
        }
        if (!(o instanceof Map) || !(o as Map).values().every { it instanceof List }) {
            return [groups: [:], error: 'The Shared risk groups setting must be a JSON object of lists: {"group name": [cluster id or name]}']
        }
        [groups: o]
    }

    private static boolean inGroup(List members, Map e) {
        members.any { Object m ->
            String s = m?.toString()?.trim()
            s?.isLong() ? (s as Long) == e.id : s?.equalsIgnoreCase(e.name as String)
        }
    }

    /** The largest set raised within one window of each other. */
    private static List<Map> bestWindow(Collection<Map> entries) {
        List<Map> sorted = entries.sort(false) { it.at as long }
        List<Map> best = []
        int start = 0
        for (int i = 0; i < sorted.size(); i++) {
            while ((sorted[i].at as long) - (sorted[start].at as long) > WINDOW_MS) {
                start++
            }
            if (i - start + 1 > best.size()) {
                best = sorted.subList(start, i + 1)
            }
        }
        best
    }

    private static String label(String risk) {
        risk.startsWith('portal:') ? "iSCSI portal ${risk.substring(7)}".toString() : (risk.startsWith('time:') ? "time server ${risk.substring(5)}".toString() : "site group ${risk.substring(5)}".toString())
    }

    private static boolean explains(String risk, String check) {
        String kind = risk.tokenize(':')[0]
        !EXPLAINS.containsKey(kind) || check ==~ EXPLAINS[kind]
    }

    /** One row per check and set of clusters, with every reason they share, so that two portals of one array do not make two alarms. */
    private static List<Map> forCheck(String check, Collection<Map> entries, Map<String, List> groups) {
        Map<String, List<Map>> byRisk = [:]
        entries.each { Map e ->
            ((RISKS[e.id as Long] ?: []) as Set<String>).findAll { explains(it, check) }.each { byRisk.computeIfAbsent(it) { [] } << e }
            groups.findAll { String g, List members -> inGroup(members, e) }.each { String g, List members -> byRisk.computeIfAbsent("site:${g}".toString()) { [] } << e }
        }
        Map<Set<Long>, Map> found = [:]
        byRisk.each { String risk, List<Map> es ->
            List<Map> w = bestWindow(es)
            if (w.size() >= GROUP_MIN) {
                Set<Long> ids = w*.id as Set<Long>
                Map row = found[ids] ?: (found[ids] = [members: w, risks: []])
                (row.risks as List) << risk
            }
        }
        List<Map> any = bestWindow(entries)
        if (any.size() >= ANY_MIN && !found.keySet().any { it.containsAll(any*.id) }) {
            found[any*.id as Set<Long>] = [members: any, risks: []]
        }
        found.values().collect { Map r -> row(check, r.members as List<Map>, (r.risks as List<String>).sort()) }
    }

    private static Map row(String check, List<Map> members, List<String> risks) {
        String shared = risks ? "They share ${risks.take(3).collect { label(it) }.join(', ')}${risks.size() > 3 ? ' and more' : ''}.".toString() : 'They have nothing on record in common, so look for something they do.'
        List<Map> clusters = members.sort(false) { it.name as String }.collect { [id: it.id, name: it.name] }
        HealthChecker.Result r = new HealthChecker.Result('FLT-01', 'Possible shared cause', HealthChecker.Severity.MEDIUM, HealthChecker.Status.WARN, null,
                "${check} appeared on ${clusters.size()} clusters within ${(WINDOW_MS / 60000) as long} minutes: ${clusters*.name.join(', ')}. ${shared}".toString(), 'unrelated causes', 'plugin')
        String identity = "${check}|${risks ? risks[0] : 'any'}".toString()
        [id: r.id, title: r.title, severity: r.severity.name(), status: r.status.name(), observed: r.observed, check: check, checkName: CheckCatalog.ENTRIES[check]?.name, clusters: clusters,
         shared: risks.collect { label(it) }, since: members*.at.min(), uniqueId: "${ALARM_PREFIX}${check}-${identity.digest('MD5').take(12)}".toString()]
    }

    /** [shared: rows, error]. Cheap enough for every run and every page view: all of it is in memory. */
    static Map current(String sharedRisk, long now = System.currentTimeMillis()) {
        Map parsed = parseSharedRisk(sharedRisk)
        List<Map> rows = RAISED.collectMany { String check, Map<Long, Map> byCluster ->
            byCluster.values().removeAll { now - (it.last as long) > FORGET_MS }
            forCheck(check, byCluster.values(), parsed.groups as Map<String, List>)
        }
        [shared: rows.sort { a, b -> (b.since as long) <=> (a.since as long) ?: (a.check as String) <=> (b.check as String) }, error: parsed.error]
    }

    /** One alarm per correlation, on the master tenant alone. Morpheus is only asked when the set changes, and once after a load, to clear what is left from before. */
    private static synchronized void syncAlarms(MorpheusContext morpheus, List<Map> rows, long now) {
        Set<String> want = rows*.uniqueId as Set<String>
        if (alarmsOpen != null && alarmsOpen == want) {
            return
        }
        Account master = HealthMonitor.masterAccount(morpheus)
        if (!master) {
            return   // nowhere that only the master tenant sees, and these name other tenants' clusters
        }
        MorpheusSession.run(morpheus) {
            List<OperationNotification> open = morpheus.services.operationNotification.list(new DataQuery().withFilter('category', HealthMonitor.CATEGORY))
                    .findAll { it.active != false && it.uniqueId?.startsWith(ALARM_PREFIX) }
            open.findAll { !(it.uniqueId in want) }.each { morpheus.services.operationNotification.clearAlarm(it) }
            Set<String> have = open*.uniqueId as Set<String>
            rows.findAll { !(it.uniqueId in have) }.each { Map r ->
                def done = morpheus.services.operationNotification.createAlarm(new OperationNotification(
                        account: master, category: HealthMonitor.CATEGORY, eventKey: HealthMonitor.CATEGORY, uniqueId: r.uniqueId as String, name: "${r.id} ${r.title}: ${r.check}".toString(),
                        resourceName: 'All HVM clusters', refType: 'computeServerGroup', refId: (r.clusters as List<Map>)[0].id as Long,
                        status: 'warning', statusMessage: "${r.observed} See Operations > Analytics > Cluster Health.".toString(), startDate: new Date(now), acknowledged: false, active: true))
                if (done != null && !done.success) {
                    log.warn("cluster health shared cause alarm refused: ${done.msg ?: done.errors}")
                }
            }
        }
        alarmsOpen = want
    }

    /** After a check, by the monitor. Never fails the check. */
    static void afterRun(MorpheusContext morpheus, ComputeServerGroup cluster, HealthChecker.Input in_, Map view, Map settings, long now = System.currentTimeMillis()) {
        try {
            note(cluster.id, cluster.name, risksOf(in_), view.results as List<Map>, view.historySince, in_.hosts && in_.hosts.every { it.facts?.complete }, now, Paging.held(cluster.id, now))
            syncAlarms(morpheus, current(settings?.sharedRisk as String, now).shared as List<Map>, now)
        } catch (Throwable e) {
            log.warn("cluster health correlation failed: ${e.message}")
        }
    }

    /** What a reload does, for tests. {@code started} is when the plugin notionally loaded. */
    static void forget(long started) {
        RAISED.clear()
        RISKS.clear()
        HELD_AT.clear()
        alarmsOpen = null
        startedAt = started
    }
}
