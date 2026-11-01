package org.hvmhealth.morpheus

import com.morpheusdata.core.MorpheusContext
import groovy.json.JsonOutput
import groovy.util.logging.Slf4j
import org.hvmhealth.checks.HealthChecker

import java.util.concurrent.ConcurrentHashMap

/**
 * One sample a day per cluster, for at most 90 days, and the straight line through them that CAP-03 and GFS-11 read. Its own row per cluster, read
 * once a day and written when a day's sample gains something, so at 200 clusters this is a few hundred row writes a day, not one per check.
 *
 * A sample holds only what the run could vouch for. Morpheus stops reporting VMs as running on a silent host, and a frozen datastore has no usage to
 * read, so a morning like that leaves those parts empty rather than a dip to zero, and a later check the same day fills them in.
 */
@Slf4j
class CapacityTrend {
    static final String CATEGORY = 'hvm-cluster-health-capacity'
    static final int MAX_DAYS = 90
    static final long DAY_MS = 24L * 3600 * 1000

    /** clusterId -> why its samples could not be read or saved. */
    static final Map<Long, String> ERRORS = new ConcurrentHashMap<>()
    private static final Map<Long, Map> DOCS = new ConcurrentHashMap<>()
    private static final Map<Long, Long> READ_DAY = new ConcurrentHashMap<>()
    private static final Set<Long> UNSAVED = ConcurrentHashMap.newKeySet()
    private static final Map<Long, Object> LOCKS = new ConcurrentHashMap<>()

    static long day(long ms) {
        ms.intdiv(DAY_MS) as long
    }

    /** What this run can vouch for: [ds: [mount: [used, size]], m: [running, survivors], c: [vcpus, threads]], each only if it was read. */
    static Map sampleOf(HealthChecker.Input in_) {
        Map out = [:]
        Map<String, List<Long>> ds = [:]
        in_.hosts.findAll { it.facts?.complete }.each { h ->
            h.facts.usage.each { Map u -> ds[u.target as String] = [u.used as long, u.size as long] }
        }
        if (ds) {
            out.ds = ds
        }
        if (in_.hosts && in_.hosts.every { it.facts?.complete } && !in_.hosts.any { it.facts.frozen }) {
            Map m = HealthChecker.memoryHeadroom(in_)
            if (m) {
                out.m = [m.running, m.survivors]
            }
            Map c = HealthChecker.vcpuLoad(in_)
            if (!c.why) {
                out.c = [c.vcpus, c.threads]
            }
        }
        out
    }

    /** Puts a run's reading into today's sample where today's has none yet. True when that changed anything worth saving. */
    static boolean merge(Map doc, Map reading, long today) {
        List<Map> samples = doc.samples as List<Map>
        boolean changed = samples.removeAll { today - (it.d as long) >= MAX_DAYS }
        Map s = samples.find { (it.d as long) == today }
        if (s == null) {
            if (!reading) {
                return changed
            }
            s = [d: today]
            samples << s
            samples.sort { it.d as long }
            changed = true
        }
        ['m', 'c'].each { String k ->
            if (s[k] == null && reading[k] != null) {
                s[k] = reading[k]
                changed = true
            }
        }
        (reading.ds as Map)?.each { String t, Object v ->
            Map have = (s.ds ?: (s.ds = [:])) as Map
            if (have[t] == null) {
                have[t] = v
                changed = true
            }
        }
        changed
    }

    /** Within the cap, oldest samples first. */
    static String encode(Map doc) {
        String json = JsonOutput.toJson(doc)
        while (json.getBytes('UTF-8').length > FindingHistory.MAX_BYTES) {
            List samples = doc.samples as List
            if (!samples) {
                throw new IllegalStateException('capacity samples do not fit even when empty')
            }
            samples.remove(0)
            doc.dropped = ((doc.dropped ?: 0) as int) + 1
            json = JsonOutput.toJson(doc)
        }
        json
    }

    /** Least squares over [day, value] pairs, with the newest value. Without enough days it carries only the count, so nothing can read a slope that is not there. */
    static Map line(List<List> points, long today) {
        Map out = [n: points.size()]
        if (points.size() < HealthChecker.FORECAST_MIN_DAYS) {
            return out
        }
        double mx = points.sum { it[0] as double } as double, my = points.sum { it[1] as double } as double
        mx /= points.size()
        my /= points.size()
        double sxx = points.sum { Math.pow((it[0] as double) - mx, 2) } as double
        double sxy = points.sum { ((it[0] as double) - mx) * ((it[1] as double) - my) } as double
        out + [slope: sxx ? (sxy / sxx) as BigDecimal : 0G, latest: points[-1][1] as BigDecimal, age: today - (points[-1][0] as long)]
    }

    /** What CAP-03 and GFS-11 are given. A datastore neither mounted now nor sampled lately has gone, and is left out rather than unread for 90 days. */
    static Map forecast(Map doc, HealthChecker.Input in_, long today) {
        List<Map> samples = doc.samples as List<Map>
        List<Map> withM = samples.findAll { it.m != null }
        Map nPlusOne = line(withM.collect { [it.d, (it.m as List)[0]] }, today)
        if (withM) {
            nPlusOne.limit = ((withM[-1].m as List)[1]) as BigDecimal
        }
        Set<String> mounted = in_.hosts.collectMany { (it.facts?.mounts ?: [])*.target } as Set<String>
        Set<String> targets = samples.collectMany { ((it.ds ?: [:]) as Map).keySet() as List } as Set<String>
        List<Map> datastores = targets.sort().collect { String t ->
            List<Map> with = samples.findAll { (it.ds as Map)?.get(t) != null }
            Map l = line(with.collect { [it.d, ((it.ds as Map)[t] as List)[0]] }, today)
            l + [target: t, size: (((with[-1].ds as Map)[t] as List)[1]) as BigDecimal, newest: with[-1].d]
        }.findAll { Map l -> l.target in mounted || today - (l.newest as long) <= HealthChecker.FORECAST_MAX_AGE_DAYS }
        [nPlusOne: nPlusOne, datastores: datastores]
    }

    /**
     * Today's forecast for one cluster, saving today's sample on the way. Never throws: a store that cannot be read gives an error the checks
     * show as not checked, and nothing is written over it. A store that cannot be written keeps the samples in memory and tries again next run.
     */
    static Map forecastFor(MorpheusContext morpheus, Long clusterId, HealthChecker.Input in_, long now = System.currentTimeMillis()) {
        long today = day(now)
        try {
            synchronized (LOCKS.computeIfAbsent(clusterId) { new Object() }) {
                Map doc = loaded(morpheus, clusterId, today, now)
                boolean changed = merge(doc, sampleOf(in_), today)
                if (changed || UNSAVED.contains(clusterId)) {
                    save(morpheus, clusterId, doc, now)
                }
                return forecast(doc, in_, today)
            }
        } catch (Throwable e) {
            log.warn("cluster health capacity samples for cluster ${clusterId}: ${e.message}")
            ERRORS[clusterId] = FindingHistory.shortError(e)
            return [error: 'its capacity samples could not be read']
        }
    }

    /** Memory holds the document, and the row is read again once a day, which also picks up what another appliance node wrote. If that read fails, memory will do. */
    private static Map loaded(MorpheusContext morpheus, Long clusterId, long today, long now) {
        Map doc = DOCS[clusterId]
        if (doc == null || READ_DAY[clusterId] != today) {
            try {
                Map stored = PluginRow.read(morpheus, CATEGORY, clusterId)
                if (stored != null && !UNSAVED.contains(clusterId)) {
                    doc = stored
                }
                doc = doc ?: [v: 1, since: now, savedAt: 0L, samples: []]
                DOCS[clusterId] = doc
                READ_DAY[clusterId] = today
            } catch (Throwable e) {
                if (doc == null) {
                    throw e
                }
                log.warn("cluster health capacity row for cluster ${clusterId} not read today, using what is in memory: ${e.message}")
            }
        }
        doc
    }

    private static void save(MorpheusContext morpheus, Long clusterId, Map doc, long now) {
        try {
            doc.savedAt = now
            PluginRow.replace(morpheus, CATEGORY, 'HVM Cluster Health capacity', clusterId, encode(doc), now)
            UNSAVED.remove(clusterId)
            ERRORS.remove(clusterId)
        } catch (Throwable e) {
            UNSAVED << clusterId
            log.warn("cluster health capacity samples for cluster ${clusterId} not saved: ${e.message}")
            ERRORS[clusterId] = FindingHistory.shortError(e)
        }
    }

    /** What a reload does, for tests. */
    static void forget() {
        DOCS.clear()
        READ_DAY.clear()
        UNSAVED.clear()
        ERRORS.clear()
    }
}
