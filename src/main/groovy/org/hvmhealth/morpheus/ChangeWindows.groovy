package org.hvmhealth.morpheus

import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.data.DataQuery
import com.morpheusdata.model.ComputeServerGroup
import com.morpheusdata.model.OperationData
import com.morpheusdata.model.User
import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import groovy.util.logging.Slf4j

import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * Planned work: a window holds a cluster's paging, nothing else. Checks, alarms and history carry on, and Paging.ALWAYS_PAGE is never held. Every window has an
 * end, so one nobody remembers to close puts paging back by itself: that takes no thread and no store, only the time compared when asked.
 *
 * Kept in OperationData so it survives a reload, one row per open window, and read into memory once a minute, so the status URL never reaches the database.
 * One read a minute whatever the number of clusters, which also brings another appliance node in line. If the store cannot be read, what was known stands. After a
 * reload that nothing can yet be read for, nothing is held, so a cluster pages rather than stays quiet.
 */
@Slf4j
class ChangeWindows {
    static final String CATEGORY = 'hvm-cluster-health-window'
    static final int DEFAULT_MAX_HOURS = 24
    static final int MAX_NOTE = 200
    private static final long REFRESH_SEC = 60

    /** clusterId -> [clusterId, startedAt, endsAt, by, note]. Replaced whole, so a reader never sees it half filled. */
    private static volatile Map<Long, Map> open = [:]
    private static final Object LOCK = new Object()
    private static ScheduledExecutorService reader

    static synchronized void start(MorpheusContext morpheus) {
        stop()
        reader = Executors.newSingleThreadScheduledExecutor({ Runnable r ->
            Thread t = new Thread(r, 'hvm-cluster-health-windows')
            t.daemon = true
            t
        })
        reader.scheduleWithFixedDelay({ refresh(morpheus) }, 0, REFRESH_SEC, TimeUnit.SECONDS)
    }

    static synchronized void stop() {
        reader?.shutdownNow()
        reader = null
        open = [:]
    }

    static int maxHours(Map settings) {
        String s = settings?.maxWindowHours?.toString()?.trim()
        s?.isInteger() && (s as Integer) > 0 ? s as Integer : DEFAULT_MAX_HOURS
    }

    /** The open window, or null. Past its end it is gone, whether or not anything has cleaned it up. */
    static Map current(Long clusterId, long now = System.currentTimeMillis()) {
        Map w = open[clusterId]
        w && (w.endsAt as long) > now ? w : null
    }

    private static void publish(Long clusterId, Map window) {
        Map<Long, Map> next = new HashMap<>(open)
        window ? next.put(clusterId, window) : next.remove(clusterId)
        open = next
    }

    private static List<OperationData> rows(MorpheusContext morpheus, Long clusterId) {
        String ref = clusterId.toString()
        MorpheusSession.run(morpheus) {
            morpheus.services.operationData.list(new DataQuery().withFilter('category', CATEGORY).withFilter('refId', ref))
                    .findAll { it.category == CATEGORY && it.refId == ref }
        }
    }

    /** Starts a window, or replaces the one open. Saved and read back first: one that would not survive a reload is refused, rather than vanish without a word. */
    static Map begin(MorpheusContext morpheus, ComputeServerGroup cluster, Object minutes, String note, User who, Map settings, long now = System.currentTimeMillis()) {
        String asked = minutes?.toString()?.trim()
        if (!asked?.isInteger() || (asked as Integer) < 1) {
            throw new IllegalArgumentException('Say how long the window lasts, in whole minutes. Paging comes back by itself at the end.')
        }
        int max = maxHours(settings)
        if ((asked as long) > max * 60L) {
            throw new IllegalArgumentException("A window can last at most ${max} hours, from the Longest change window setting. Nothing was started.".toString())
        }
        String why = note?.replaceAll(/\s+/, ' ')?.trim()
        if (!why) {
            throw new IllegalArgumentException('Say what the work is: whoever is paged next needs to know why this cluster is quiet.')
        }
        if (why.length() > MAX_NOTE) {
            throw new IllegalArgumentException("Keep the reason to ${MAX_NOTE} characters.".toString())
        }
        Map window = [clusterId: cluster.id, startedAt: now, endsAt: now + (asked as long) * 60_000L, by: who?.username ?: 'unknown', note: why]
        boolean replaced
        synchronized (LOCK) {
            List<OperationData> old = rows(morpheus, cluster.id)
            replaced = current(cluster.id, now) != null
            save(morpheus, cluster, window, now)
            removeQuietly(morpheus, old)   // a row left behind loses to this one on the next read, and goes then
            publish(cluster.id, window)
        }
        Audit.info(morpheus, who, cluster, replaced ? 'change window replaced' : 'change window started', [minutes: asked, until: HealthReport.iso(window.endsAt), reason: why])
        window
    }

    /** True when there was one to end. A window that cannot be removed stays, and says so, as it would come back on the next read anyway. */
    static boolean end(MorpheusContext morpheus, ComputeServerGroup cluster, User who, long now = System.currentTimeMillis()) {
        boolean was
        synchronized (LOCK) {
            List<OperationData> found = rows(morpheus, cluster.id)
            Map held = current(cluster.id, now)
            was = held != null
            MorpheusSession.run(morpheus) {
                found.each {
                    if (morpheus.services.operationData.remove(it) == false) {
                        throw new IllegalStateException("The change window could not be ended, so paging stays held${held ? ' until ' + HealthReport.iso(held.endsAt) : ''}.".toString())
                    }
                }
            }
            publish(cluster.id, null)
        }
        if (was) {
            Audit.info(morpheus, who, cluster, 'change window ended')
        }
        was
    }

    private static void save(MorpheusContext morpheus, ComputeServerGroup cluster, Map window, long now) {
        String json = JsonOutput.toJson(window)
        String ref = cluster.id.toString()
        def store = morpheus.services.operationData
        OperationData saved = MorpheusSession.run(morpheus) {
            String code = "${CATEGORY}-${ref}-${now}".toString()
            store.create(new OperationData(account: HealthMonitor.masterAccount(morpheus) ?: cluster.owner, code: code, keyValue: code, value: now.toString(), category: CATEGORY,
                    name: 'HVM Cluster Health change window', refType: 'computeServerGroup', refId: ref, refDate: new Date(now), rawData: json))
        }
        if (saved?.id == null) {
            throw new IllegalStateException('Morpheus did not store the change window, so paging is not held.')
        }
        if (!MorpheusSession.run(morpheus) { store.get(saved.id)?.rawData == json }) {
            removeQuietly(morpheus, [saved])
            throw new IllegalStateException('Morpheus did not keep the change window, so paging is not held.')
        }
    }

    private static void removeQuietly(MorpheusContext morpheus, List<OperationData> gone) {
        gone.each { OperationData row ->
            try {
                MorpheusSession.run(morpheus) { morpheus.services.operationData.remove(row) }
            } catch (Exception e) {
                log.debug("change window row ${row.id} not removed: ${e.message}")
            }
        }
    }

    private static Map parse(OperationData row) {
        try {
            Map w = row.rawData ? new JsonSlurper().parseText(row.rawData) as Map : null
            w?.clusterId != null && w.endsAt instanceof Number ? [*:w, clusterId: w.clusterId as Long, endsAt: w.endsAt as long] : null
        } catch (Exception e) {
            log.warn("change window row ${row.id} is unreadable: ${e.message}")
            null
        }
    }

    /** Reads what the store holds, and tidies what has ended or cannot be read. Throws nothing: a failed read leaves what was known. */
    static void refresh(MorpheusContext morpheus, long now = System.currentTimeMillis()) {
        try {
            synchronized (LOCK) {
                List<OperationData> all = MorpheusSession.run(morpheus) {
                    morpheus.services.operationData.list(new DataQuery().withFilter('category', CATEGORY)).findAll { it.category == CATEGORY }
                }
                Map<Long, Map> found = [:]
                Map<Long, OperationData> kept = [:]
                List<OperationData> drop = []
                all.each { OperationData row ->
                    Map w = parse(row)
                    if (!w || (w.endsAt as long) <= now) {
                        drop << row
                    } else if (!found[w.clusterId as Long] || ((found[w.clusterId as Long].startedAt ?: 0L) as long) < ((w.startedAt ?: 0L) as long)) {
                        OperationData older = kept[w.clusterId as Long]
                        if (older) {
                            drop << older
                        }
                        found[w.clusterId as Long] = w
                        kept[w.clusterId as Long] = row
                    } else {
                        drop << row
                    }
                }
                open = found
                removeQuietly(morpheus, drop)
            }
        } catch (Throwable e) {
            log.warn("change windows could not be read: ${e.message}")
        }
    }
}
