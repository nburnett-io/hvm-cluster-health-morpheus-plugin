package org.hvmhealth.morpheus

import groovy.util.logging.Slf4j

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Checks and fixes run off the HTTP request thread; the tab polls /job?id=. One job per cluster at a time, so two
 * changes never race. State is in memory: an appliance restart loses the progress view, not the host state.
 */
@Slf4j
class Jobs {
    static class Job {
        String id
        String kind
        Long clusterId
        String user
        String state = 'running'
        List<String> progress = Collections.synchronizedList([])
        Object result
        String error
        long started = System.currentTimeMillis()
        Long ended

        Map toMap() {
            [id: id, kind: kind, clusterId: clusterId, user: user, state: state, progress: new ArrayList(progress),
             result: result, error: error, started: started, ended: ended]
        }
    }

    private static final Map<String, Job> JOBS = new ConcurrentHashMap<>()
    private static final Map<Long, String> RUNNING = new ConcurrentHashMap<>()
    private static final ExecutorService POOL = Executors.newCachedThreadPool()
    static final long KEEP_MS = 24L * 3600 * 1000

    static Job start(String kind, Long clusterId, String user, Closure work) {
        prune()
        Job j = new Job(id: UUID.randomUUID().toString(), kind: kind, clusterId: clusterId, user: user)
        String other = RUNNING.putIfAbsent(clusterId, j.id)
        if (other != null) {
            throw new IllegalStateException("Another cluster health operation (${JOBS[other]?.kind}) is running on this cluster")
        }
        JOBS[j.id] = j
        POOL.submit {
            try {
                j.result = work.call(j.progress)
                j.state = 'done'
            } catch (Throwable t) {
                log.error("cluster health job ${kind} failed", t)
                j.error = t.message ?: t.class.simpleName
                j.state = 'failed'
            } finally {
                j.ended = System.currentTimeMillis()
                RUNNING.remove(clusterId, j.id)
            }
        }
        j
    }

    static Job get(String id) {
        JOBS[id]
    }

    static boolean busy(Long clusterId) {
        RUNNING.containsKey(clusterId)
    }

    static List<Job> forCluster(Long clusterId) {
        JOBS.values().findAll { it.clusterId == clusterId }.sort { -it.started }
    }

    private static void prune() {
        long cutoff = System.currentTimeMillis() - KEEP_MS
        JOBS.values().removeIf { it.ended && it.ended < cutoff }
    }
}
