package org.hvmhealth.morpheus

import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.model.ComputeServer
import com.morpheusdata.model.ComputeServerGroup
import groovy.util.logging.Slf4j
import org.hvmhealth.model.HostFacts
import org.hvmhealth.standards.Layout

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.FutureTask
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * Finds a freeze between quick checks, because a quick check can be minutes away and many clusters may be queued ahead of it. It has its own
 * scheduler and threads, so slow checks never delay it. Each turn reads one host per cluster, in rotation, with HealthContext.WATCH_SCRIPT: DLM and
 * quorum, nothing that touches GFS2. A second host is read only if the first does not answer. Hosts in maintenance, and clusters with no GFS2, are skipped.
 *
 * Reads are budgeted across all clusters: past maxWatchPerMinute the interval stretches rather than the load growing. Every read starts on a tick, so no path
 * can go round the budget. The sweep hands over the clusters and settings, so a tick reaches neither the database nor a host itself.
 *
 * Frozen means what HostFacts.isFrozen means: a stopped lockspace. A false freeze is worse than a late one, so:
 *  - A lockspace also stops for a moment when a host mounts or leaves, so a sighting is read again CONFIRM_MS later before it counts.
 *  - One host reading clear does not end a freeze, as a host that has just restarted has no lockspaces at all. It asks for a check, which reads every host, and fromCheck decides.
 *  - A host that does not answer proves nothing, either way.
 *
 * All in memory. So after a reload a freeze still open is found again and Availability is told it started again, and the first check that finds a cluster clear tells it that one is over, in case it ended while the plugin was not loaded. Nothing watched that end, so Availability keeps the time since it last saw that freeze as unknown. It takes all of these calmly.
 */
@Slf4j
class FreezeWatch {
    enum Reading { FROZEN, CLEAR, NONE }

    /** clusterId -> when the watch first saw it frozen. Gone once it has thawed. */
    static final Map<Long, Long> FROZEN_SINCE = new ConcurrentHashMap<>()

    static final long DEFAULT_SECONDS = 60
    static final long MIN_SECONDS = 10
    static final int DEFAULT_BUDGET = 120
    /** Short, so that a budget spreads across ticks: at 120 a minute, 4 on each. */
    static final long TICK_MS = 2000
    static final long CONFIRM_MS = 15_000
    static final double JITTER = 0.1
    /** The host list is one database read per cluster, so it is kept this long, which also keeps a turn from being a database read. */
    static final long HOSTS_KEEP_MS = 5L * 60 * 1000
    /** After a host does not answer, others are read first for this long. A check says it is silent (PRB-01): the watch needs one host that answers, and 20 s on this one every few turns would cost more than all the rest. */
    static final long QUIET_MS = 5L * 60 * 1000
    /** Reads at once: at about 2 s each, this does twice the default budget, and leaves room for silent hosts at 20 s each. */
    static final int READERS = 8
    /** A call the agent never returns from may also ignore being interrupted. It no longer counts against READERS, so such calls cannot stop the watch quietly; this limits how many threads they can hold. */
    static final int MAX_THREADS = 32
    /** No read has been able to start for this long, as every thread is held: PRB-04 and /alive then say so, since nothing else would. */
    static final long STOPPED_MS = 2L * 60 * 1000
    /** The agent call's own limit only starts when the call returns, so this one holds whatever the call does. */
    static final long READ_LIMIT_MS = (HealthContext.WATCH_TIMEOUT_SEC + 5L) * 1000L

    static long seconds(Map settings) {
        String s = settings?.freezeWatchSeconds as String
        if (!s?.isLong()) {
            return DEFAULT_SECONDS
        }
        (s as Long) <= 0 ? 0L : Math.max(MIN_SECONDS, s as Long)
    }

    static int budget(Map settings) {
        String s = settings?.maxWatchPerMinute as String
        s?.isInteger() && (s as Integer) > 0 ? s as Integer : DEFAULT_BUDGET
    }

    /** The real interval: what was asked for, unless that many clusters would exceed the budget. */
    static long intervalMs(long seconds, int budget, int clusters) {
        Math.max(seconds * 1000L, (long) Math.ceil(clusters * 60_000d / budget))
    }

    /** What one read says. A read with no clean answer from dlm_tool is no answer: it must not pass for a cluster with no lockspaces. */
    static Reading reading(HostFacts f) {
        if (f?.kv('dlm_ls')?.DLM_RC != '0') {
            return Reading.NONE
        }
        f.frozen ? Reading.FROZEN : Reading.CLEAR
    }

    /** Null when corosync gave no answer, which is not the same as no quorum. */
    static Boolean quorate(HostFacts f) {
        f?.sections?.quorum?.any { it.startsWith('Quorate:') } ? f.quorate : null
    }

    static class Watch {
        Long id
        long nextAt
        int turn
        /** Ahead of the rest: a second host, or a confirmation. */
        boolean soon
        boolean reading
        String mustRead
        Set<String> tried = [] as Set
        /** host -> when it last did not answer. */
        Map<String, Long> quiet = [:]
        Long pendingAt
        String pendingHost
        String frozenHost
        Long clearAt
        Boolean quorate
        Long lastReadAt
        boolean endChecked
        List<ComputeServer> hosts
        long hostsAt
    }

    /**
     * Who is read when, and what a reading means. Nothing here reaches Morpheus, or a clock or a thread of its own, so it can be run in simulation.
     * Its answers are events for the caller to act on: [started: when, ended: when, check: true].
     */
    static class Schedule {
        final Random random
        final Map<Long, Watch> watches = [:]
        Set<Long> targets = [] as Set
        long seconds
        int budget = DEFAULT_BUDGET
        long intervalMs
        double credit
        Long lastPick

        Schedule(Random random = new Random()) {
            this.random = random
        }

        synchronized void update(Collection<Long> ids, long seconds, int budget, long now) {
            this.seconds = seconds
            this.budget = budget
            lastPick = lastPick ?: now
            this.targets = ids as Set
            this.intervalMs = FreezeWatch.intervalMs(seconds, budget, targets.size())
            ids.each { watch(it, now) }
        }

        private Watch watch(Long id, long now) {
            // spread by id, so that a load does not read every cluster on the first tick
            watches.computeIfAbsent(id) { new Watch(id: id, nextAt: now + HealthMonitor.phase(id, Math.max(intervalMs, 1L))) }
        }

        private long next(long now) {
            now + (long) (intervalMs * (1 - JITTER + random.nextDouble() * 2 * JITTER))
        }

        /** The watches to read now, at most what the budget and {@code free} readers allow. */
        synchronized List<Watch> pick(long now, int free, Closure<Boolean> checking = { false }) {
            double cap = Math.max(1d, budget * TICK_MS / 60_000d)
            credit = Math.min(cap, credit + Math.max(0L, now - (lastPick ?: now)) * (budget / 60_000d))
            lastPick = now
            if (seconds <= 0) {
                return []
            }
            List<Watch> due = targets.collect { watches[it] }.findAll { it && !it.reading && it.nextAt <= now }
            // a check already running reads every host, and tells fromCheck: reading as well would cost something and say nothing
            due = due.findAll { Watch w ->
                if (!checking.call(w.id)) {
                    return true
                }
                if (!w.soon) {
                    w.nextAt = next(now)
                }
                false
            }
            List<Watch> out = due.sort(false) { a, b -> b.soon <=> a.soon ?: a.nextAt <=> b.nextAt }.take(Math.min(Math.max(free, 0), (int) Math.floor(credit + 1e-9d)))
            credit -= out.size()
            out.each { it.reading = true }
            out
        }

        /** Whom to read: the host a sighting or freeze was on, otherwise the next in rotation, leaving out one that has not answered unless none has. */
        synchronized String chooseHost(Watch w, List<String> names, long now) {
            List<String> untried = (names ?: []) - w.tried
            List<String> answering = untried.findAll { !w.quiet[it] || now - w.quiet[it] >= QUIET_MS }
            if (w.mustRead in answering) {
                return w.mustRead
            }
            List<String> left = answering ?: untried
            left ? left[w.turn++ % left.size()] : null
        }

        synchronized void noHosts(Watch w, long now) {
            w.reading = false
            w.tried.clear()
            w.soon = false
            w.nextAt = next(now)
        }

        synchronized Map result(Watch w, String host, Reading r, Boolean quorate, List<String> names, long now) {
            w.reading = false
            w.lastReadAt = now
            Map out = [:]
            if (r == Reading.NONE) {
                if (host != null) {
                    w.tried << host
                    w.quiet[host] = now
                }
                if (host != null && w.tried.size() < 2 && (names ?: []).size() > w.tried.size()) {
                    w.soon = true
                    w.nextAt = now
                    return out
                }
                w.tried.clear()
                w.soon = false
                w.nextAt = next(now)
                if (w.pendingAt != null) {
                    forgetPending(w)   // seen once and it cannot be confirmed: a check reads every host
                    out.check = true
                }
                return out
            }
            w.tried.clear()
            w.quiet.remove(host)
            if (quorate != null) {
                out.check = w.quorate != quorate && (w.quorate != null || !quorate) ?: null
                w.quorate = quorate
            }
            if (r == Reading.FROZEN) {
                frozen(w, host, now, out)
            } else {
                clear(w, host, names, now, out)
            }
            out.findAll { k, v -> v != null }
        }

        private void frozen(Watch w, String host, long now, Map out) {
            w.soon = false
            if (w.frozenHost != null) {
                w.frozenHost = host
                w.mustRead = host
                w.clearAt = null
                w.nextAt = next(now)
            } else if (w.pendingAt == null) {
                w.pendingAt = now
                w.pendingHost = host
                w.mustRead = host
                w.soon = true
                w.nextAt = now + CONFIRM_MS
            } else if (now - w.pendingAt < CONFIRM_MS) {
                w.soon = true
                w.nextAt = w.pendingAt + CONFIRM_MS
            } else {
                out.started = declare(w, host)
                out.check = true
                w.nextAt = next(now)
            }
        }

        private Long declare(Watch w, String host) {
            Long at = w.pendingAt
            forgetPending(w)
            w.frozenHost = host
            w.mustRead = host
            w.clearAt = null
            w.endChecked = true
            at
        }

        private static void forgetPending(Watch w) {
            w.pendingAt = null
            w.pendingHost = null
            w.mustRead = w.frozenHost
        }

        private void clear(Watch w, String host, List<String> names, long now, Map out) {
            w.soon = false
            w.nextAt = next(now)
            if (w.frozenHost != null) {
                if (host == w.frozenHost || !(w.frozenHost in names)) {
                    w.clearAt = w.clearAt ?: now
                    out.check = true
                }
            } else if (w.pendingAt != null) {
                boolean same = host == w.pendingHost
                forgetPending(w)
                if (!same) {
                    out.check = true   // the host it was seen on stopped answering, and this one says nothing about it
                }
            }
        }

        /** What a check read, on every host not in maintenance. Unlike one read by the watch, this can end a freeze. */
        synchronized Map fromCheck(Long id, Map<String, HostFacts> facts, long now) {
            Watch w = watch(id, now)
            Map out = [:]
            List<String> frozen = facts.findAll { k, f -> f?.frozen }.keySet() as List<String>
            if (frozen) {
                if (w.frozenHost != null) {
                    if (!(w.frozenHost in frozen)) {
                        w.frozenHost = frozen[0]
                        w.mustRead = frozen[0]
                    }
                    w.clearAt = null
                } else if (w.pendingAt == null) {
                    w.pendingAt = now
                    w.pendingHost = frozen[0]
                    w.mustRead = frozen[0]
                    w.soon = true
                    w.nextAt = now + CONFIRM_MS
                } else if (now - w.pendingAt >= CONFIRM_MS) {
                    out.started = declare(w, frozen[0])
                }
                return out
            }
            // hosts that gave a clean answer from dlm_tool. The silent and the unread prove nothing
            Set<String> read = facts.findAll { k, f -> f?.kv('dlm_ls')?.DLM_RC == '0' }.keySet()
            // one that restarted has no lockspaces, so it alone cannot speak for the rest. But a survivor of two can, or a freeze would outlast its dead host for ever
            boolean enough = read && read.size() * 2 >= facts.size()
            if (w.pendingAt != null && (w.pendingHost in read || !facts.containsKey(w.pendingHost))) {
                forgetPending(w)
                w.soon = false
            }
            if (w.frozenHost != null) {
                if (enough && (w.frozenHost in read || !facts.containsKey(w.frozenHost))) {
                    out.ended = w.clearAt ?: now
                    w.frozenHost = null
                    w.mustRead = null
                    w.clearAt = null
                    w.endChecked = true
                }
            } else if (enough && !w.endChecked) {
                out.ended = now   // one that ended while the plugin was not loaded. Availability ignores this if it has none open
                w.endChecked = true
            }
            out
        }

        synchronized boolean on() {
            seconds > 0 && targets as boolean
        }

        synchronized void forget(Long id) {
            watches.remove(id)
            targets = targets - id
        }

        synchronized Map summary(long now) {
            List<Long> ages = targets.collect { watches[it]?.lastReadAt }.findAll().collect { now - (it as long) }
            [on: seconds > 0, seconds: seconds, realSeconds: seconds > 0 ? (long) Math.ceil(intervalMs / 1000d) : 0L, clusters: targets.size(), budget: budget,
             oldestReadSec: ages ? (long) (ages.max() / 1000L) : null]
        }

        synchronized Map forCluster(Long id) {
            seconds > 0 && id in targets ? [seconds: (long) Math.ceil(intervalMs / 1000d), setTo: seconds] : null
        }
    }

    private static volatile Schedule schedule
    private static ScheduledExecutorService ticker
    private static ThreadPoolExecutor threads
    /** Reads under way: watch -> [started, task, host, names]. */
    private static final Map<Watch, Map> READING = new ConcurrentHashMap<>()
    /** When a read was first refused a thread, until one is next given one. */
    private static volatile Long refusedSince

    static synchronized void start(MorpheusContext morpheus) {
        stop()
        FROZEN_SINCE.clear()
        refusedSince = null
        ThreadFactory daemons = { Runnable r ->
            Thread t = new Thread(r, 'hvm-cluster-health-watch')
            t.daemon = true
            t
        } as ThreadFactory
        Schedule s = new Schedule()
        schedule = s
        threads = new ThreadPoolExecutor(0, MAX_THREADS, 60L, TimeUnit.SECONDS, new SynchronousQueue<Runnable>(), daemons)
        ticker = Executors.newSingleThreadScheduledExecutor(daemons)
        ticker.scheduleWithFixedDelay({
            try {
                tick(morpheus, s, System.currentTimeMillis())
            } catch (Throwable e) {
                log.warn("cluster health freeze watch failed: ${e.message}")
            }
        }, TICK_MS, TICK_MS, TimeUnit.MILLISECONDS)
    }

    static synchronized void stop() {
        ticker?.shutdownNow()
        threads?.shutdownNow()
        ticker = null
        threads = null
        schedule = null
        READING.clear()
    }

    /** From the sweep. With automatic checks off the watch is off too: that setting promises no automatic calls to a host. */
    static void update(List<ComputeServerGroup> clusters, Map settings, boolean checksOn, long now = System.currentTimeMillis()) {
        schedule?.update(clusters.findAll { hasGfs2(it) }*.id, checksOn ? seconds(settings) : 0L, budget(settings), now)
    }

    /** An HCI Ceph cluster has nothing to freeze. A cluster whose layout is not on its record may have, so it is read. */
    static boolean hasGfs2(ComputeServerGroup c) {
        String code = c?.layout?.code
        !(code && Layout.of(code).hciCeph)
    }

    /** Whether the watch should be reading and cannot start a read. Its threads are held by calls that never returned, and there is no getting them back short of a reload. */
    static boolean stopped(long now = System.currentTimeMillis()) {
        Long since = refusedSince
        since != null && now - since >= STOPPED_MS && schedule?.on()
    }

    /** Everything held on a cluster that has gone. */
    static void forget(MorpheusContext morpheus, Long id) {
        schedule?.forget(id)
        if (FROZEN_SINCE.remove(id) != null) {
            quietly { Availability.freezeEnded(morpheus, id, System.currentTimeMillis()) }
        }
    }

    static Map summary(long now = System.currentTimeMillis()) {
        schedule?.summary(now) ?: [on: false]
    }

    static Map forCluster(Long id) {
        schedule?.forCluster(id)
    }

    /** From each check, on the hosts it read that are not in maintenance. Never fails the check. */
    static void fromCheck(MorpheusContext morpheus, Long id, Map<String, HostFacts> facts, long now = System.currentTimeMillis()) {
        try {
            Schedule s = schedule
            if (s) {
                act(morpheus, id, s.fromCheck(id, facts, now))
            }
        } catch (Throwable e) {
            log.warn("cluster health freeze watch could not use a check of cluster ${id}: ${e.message}")
        }
    }

    private static void quietly(Closure c) {
        try {
            c.call()
        } catch (Throwable e) {
            log.warn("cluster health freeze watch: ${e.message}")
        }
    }

    private static void act(MorpheusContext morpheus, Long id, Map e) {
        if (e.started != null) {
            FROZEN_SINCE[id] = e.started as Long
            log.warn("cluster ${id} is frozen")
            quietly { Availability.freezeStarted(morpheus, id, e.started as long) }
        }
        if (e.ended != null) {
            if (FROZEN_SINCE.remove(id) != null) {
                log.warn("cluster ${id} has thawed")
                quietly { Availability.freezeEnded(morpheus, id, e.ended as long) }
            } else {
                quietly { Availability.freezeFoundOver(morpheus, id, e.ended as long) }
            }
        }
        if (e.check) {
            quietly { HealthMonitor.checkNow(morpheus, id) }
        }
    }

    static void tick(MorpheusContext morpheus, Schedule s, long now) {
        READING.each { Watch w, Map r ->
            if (now - (r.started as long) > READ_LIMIT_MS && READING.remove(w) != null) {
                (r.task as FutureTask).cancel(true)
                act(morpheus, w.id, s.result(w, r.host as String, Reading.NONE, null, r.names as List<String>, now))
            }
        }
        s.pick(now, READERS - READING.size()) { Long id -> HealthMonitor.checking(id) }.each { Watch w ->
            Map r = [started: now] as ConcurrentHashMap
            FutureTask task = new FutureTask({ read(morpheus, s, w, r) } as Runnable, null)
            r.task = task
            READING[w] = r
            try {
                threads.execute(task)
                refusedSince = null
            } catch (RejectedExecutionException e) {
                READING.remove(w)   // stuck calls have taken every thread
                s.noHosts(w, now)
                if (refusedSince == null) {
                    refusedSince = now
                    log.warn('cluster health freeze watch: every thread is held by an agent call that has not returned, so reads cannot start')
                }
            } catch (NullPointerException e) {
                READING.remove(w)   // it is stopping
                s.noHosts(w, now)
            }
        }
    }

    private static List<ComputeServer> hostsOf(MorpheusContext morpheus, Watch w, long now) {
        if (w.hosts == null || now - w.hostsAt >= HOSTS_KEEP_MS) {
            w.hosts = HealthContext.liveHosts(morpheus, w.id).findAll { !HealthContext.inMaintenance(it) }
            w.hostsAt = now
        }
        w.hosts
    }

    private static void read(MorpheusContext morpheus, Schedule s, Watch w, Map r) {
        Reading reading = Reading.NONE
        Boolean quorate = null
        String host = null
        List<String> names = []
        try {
            List<ComputeServer> hosts = hostsOf(morpheus, w, System.currentTimeMillis())
            names = hosts*.name
            r.names = names
            host = s.chooseHost(w, names, System.currentTimeMillis())
            if (host == null) {
                if (READING.remove(w, r)) {
                    s.noHosts(w, System.currentTimeMillis())
                }
                return
            }
            r.host = host
            HostFacts f = HostFacts.parse(HealthContext.runOn(morpheus, hosts.find { it.name == host }, HealthContext.WATCH_SCRIPT, HealthContext.WATCH_TIMEOUT_SEC).output as String)
            reading = FreezeWatch.reading(f)
            quorate = FreezeWatch.quorate(f)
        } catch (Throwable e) {
            log.debug("freeze watch read of cluster ${w.id} on ${host}: ${e.message}")
        }
        if (READING.remove(w, r)) {   // otherwise its limit has passed, and the tick has dealt with it
            act(morpheus, w.id, s.result(w, host, reading, quorate, names, System.currentTimeMillis()))
        }
    }
}
