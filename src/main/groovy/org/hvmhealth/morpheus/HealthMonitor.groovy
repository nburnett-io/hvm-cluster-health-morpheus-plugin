package org.hvmhealth.morpheus

import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.Plugin
import com.morpheusdata.core.data.DataQuery
import com.morpheusdata.model.Account
import com.morpheusdata.model.ComputeServerGroup
import com.morpheusdata.model.OperationNotification
import groovy.util.logging.Slf4j
import org.hvmhealth.ClusterHealthPlugin
import org.hvmhealth.checks.HealthChecker
import org.hvmhealth.fixes.Fixes
import org.hvmhealth.model.HostFacts
import org.hvmhealth.standards.Layout

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.PriorityBlockingQueue
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * The automatic side: a quick check of every HVM cluster every few minutes, so a freeze raises an alarm rather than
 * waiting to be noticed, a full check on a longer interval, and (only when switched on in the settings) automatic fixes.
 *
 * Automatic fixes honour exactly the same guards as the buttons. SAFE fixes run on any host. MAINTENANCE fixes run only on
 * a host an operator has already put in maintenance mode, so putting a host in maintenance is the consent. EMERGENCY
 * actions are never automatic. A finding is tried once per RETRY_MS, so a fix that does not help cannot loop. The switches
 * are global, so nothing runs on a cluster until the autoFixClusters setting names it.
 */
@Slf4j
class HealthMonitor {
    static final String CATEGORY = 'hvm-cluster-health'
    static final long RETRY_MS = 6L * 3600 * 1000
    static final long DEFAULT_QUICK_MIN = 5
    static final long DEFAULT_FULL_HOURS = 24

    /** clusterId -> the last evaluation, as the tab shows it. In memory: a reload checks again. */
    static final Map<Long, Map> LAST = new ConcurrentHashMap<>()
    private static final Map<Long, List<HealthChecker.Result>> LAST_FULL = new ConcurrentHashMap<>()
    /** Findings only a full check produces (ring and packet tests, journal counts, management controllers). */
    static final Set<String> FULL_ONLY = ['NET-03', 'NET-09', 'MIG-02', 'GFS-06', 'FEN-01', 'FEN-02', 'HB-02', 'HW-01', 'HW-02', 'HW-03', 'HW-04', 'HW-05', 'HW-06', 'HW-07', 'HW-08', 'HW-09', 'HST-02', 'HST-03', 'STO-07', 'STO-13', 'HPE-01',
                                           'PKG-04', 'KRN-01', 'KRN-02', 'KRN-03', 'NET-17', 'NET-18', 'NET-19',
                                           'SEC-01', 'SEC-02', 'SEC-03', 'SEC-04', 'SEC-05', 'SEC-06', 'VM-07'] as Set
    private static final Map<String, Long> ATTEMPTS = new ConcurrentHashMap<>()
    /** Clusters waiting for a checker or being checked: one silent host takes minutes, and must not hold the others up or be queued twice. */
    private static final Set<Long> CHECKING = ConcurrentHashMap.newKeySet()
    /** clusterId -> when an automatic check last could not run. It waits the normal interval, as LAST has not moved. */
    private static final Map<Long, Long> COULD_NOT_RUN = new ConcurrentHashMap<>()
    /**
     * Clusters checked at once. A quick check took 14 s on a real host, so say 20 s with reading the cluster and raising alarms, and 200 clusters every 5 minutes then need
     * 200 x 20 / 300 = 13.3 checkers busy all the time. Ten clusters with a silent host take 85 s each rather than 20, which adds about 2. A full check a day adds under 1,
     * even at the 50 s now assumed: 25 s measured before the full-only reads were added, plus HPE's check at 6 s measured, the thin log, busiest VMs and a few disks behind a controller.
     * The first check after a load is always full, so that round is the one the full check's time decides: 200 x 50 / 20 = 500 s, ending about 11 minutes after the load with
     * the 2 before the first sweep. That is inside the status URL's 15 minutes of reload grace, as it still is with 30 clusters holding a silent host for the 160 s limit. At 40 it runs just over.
     * So 16, and 20 leaves room for more silent hosts. Each check calls every host in its cluster, so at 3 hosts a cluster that is about 60 agent calls at the worst moment,
     * and this setting is what limits them. The 4 this used to be keeps up with 60 clusters. That 14 s is from before quick checks read VM ports, and on a busy host the busiest VMs,
     * which are not yet measured. At 30 s a check, 200 clusters need all 20, and PRB-04 says so if they do.
     */
    static final int DEFAULT_CHECK_CONCURRENCY = 20
    static final int MAX_CHECK_CONCURRENCY = 64
    private static final long FIRST_SWEEP_MIN = 2
    /** Waiting for a checker, for the freeze watch to take out and put ahead of the rest. */
    private static final Map<Long, CheckTask> QUEUED = new ConcurrentHashMap<>()
    private static final AtomicLong SEQUENCE = new AtomicLong()
    /** Clusters where a host gave no answer at all last time. */
    private static final Set<Long> SILENT = ConcurrentHashMap.newKeySet()
    /** What the last sweep listed, which the status URL and the freeze watch answer from. */
    private static final Map<Long, ComputeServerGroup> MONITORED = new ConcurrentHashMap<>()
    /** clusterId -> sweeps running that Morpheus has not listed it. */
    private static final Map<Long, Integer> ABSENT = new ConcurrentHashMap<>()
    private static final int URGENT = 0, NORMAL = 1
    /** The most overdue cluster is this many intervals past its turn: PRB-04. */
    static final int BEHIND_INTERVALS = 2
    static final String KEEPING_UP_ALARM = "${CATEGORY}-fleet-PRB-04".toString()
    private static ScheduledExecutorService sweeper
    private static ThreadPoolExecutor checkers
    private static Plugin plugin
    /** When the plugin loaded. */
    private static volatile long startedAt
    /** When its first sweep is due, which turns are timed from. */
    private static volatile long loadedAt
    private static volatile Long lastSweepAt
    private static volatile long quickMs
    private static volatile Map lastSettings = [:]
    /** The numbers behind PRB-04, for the all-clusters page and /alive. */
    private static volatile Map pace = [:]
    /** Whether the PRB-04 alarm is believed open. Null after a load, when it may be left over from before, so the first sweep looks. */
    private static volatile Boolean keepingUpAlarm
    private static volatile List statusMemo
    /** Another appliance node holds the lease, so this one reads no host by itself and raises no alarm, and its status URL answers from that node's row. A person's check here still runs. */
    private static volatile boolean standby = false

    /** Waits in the checkers' queue: what the freeze watch asks for goes first, and otherwise in the order they came. */
    static class CheckTask implements Runnable, Comparable<CheckTask> {
        final int priority
        final long order
        final Long clusterId
        final Runnable work

        CheckTask(int priority, Long clusterId, Runnable work) {
            this.priority = priority
            this.order = SEQUENCE.incrementAndGet()
            this.clusterId = clusterId
            this.work = work
        }

        @Override void run() {
            QUEUED.remove(clusterId, this)
            work.run()
        }

        @Override int compareTo(CheckTask o) {
            priority <=> o.priority ?: order <=> o.order
        }
    }

    static synchronized void start(MorpheusContext morpheus, Plugin plugin) {
        stop()
        HealthMonitor.plugin = plugin
        ThreadFactory daemons = { Runnable r ->
            Thread t = new Thread(r, 'hvm-cluster-health-monitor')
            t.daemon = true
            t
        } as ThreadFactory
        [CHECKING, QUEUED, SILENT, MONITORED, ABSENT].each { it.clear() }
        startedAt = System.currentTimeMillis()
        loadedAt = startedAt + TimeUnit.MINUTES.toMillis(FIRST_SWEEP_MIN)
        lastSweepAt = null
        pace = [:]
        keepingUpAlarm = null
        statusMemo = null
        standby = false
        Lease.forget()
        checkers = new ThreadPoolExecutor(DEFAULT_CHECK_CONCURRENCY, DEFAULT_CHECK_CONCURRENCY, 60L, TimeUnit.SECONDS, new PriorityBlockingQueue<Runnable>(), daemons)
        checkers.allowCoreThreadTimeOut(true)   // an appliance with few clusters has no use for 20 idle threads
        sweeper = Executors.newSingleThreadScheduledExecutor(daemons)
        sweeper.schedule({
            try {
                syncStatusServer(ClusterHealthPlugin.readSettings(morpheus, plugin))   // not a wait for the first sweep, or a reload reads as a refused connection
            } catch (Throwable e) {
                log.warn("cluster health status URL not started yet: ${e.message}")
            }
        } as Runnable, 5, TimeUnit.SECONDS)
        sweeper.scheduleWithFixedDelay({
            try {
                sweep(morpheus, plugin)
            } catch (Throwable e) {
                log.warn("cluster health monitor pass failed: ${e.message}")
            }
        }, FIRST_SWEEP_MIN, 1, TimeUnit.MINUTES)
        FreezeWatch.start(morpheus)
    }

    static synchronized void stop() {
        sweeper?.shutdownNow()
        checkers?.shutdownNow()
        sweeper = null
        checkers = null
        plugin = null
        FreezeWatch.stop()
        StatusServer.stop()
    }

    static int checkConcurrency(Map settings) {
        String s = settings?.checkConcurrency as String
        s?.isInteger() && (s as Integer) > 0 ? Math.min(s as Integer, MAX_CHECK_CONCURRENCY) : DEFAULT_CHECK_CONCURRENCY
    }

    /** 0 when off, -1 when it is not a port. */
    static int statusPort(Map settings) {
        String s = (settings?.statusPort as String)?.trim()
        if (!s) {
            return 0
        }
        s.isInteger() && (s as Integer) in 1..65535 ? s as Integer : -1
    }

    private static void syncStatusServer(Map settings) {
        StatusServer.ensure(statusPort(settings)) { String path -> statusAnswer(path) }
    }

    private static Map statusState() {
        [running: checkers != null, startedAt: startedAt, loadedAt: loadedAt, lastSweepAt: lastSweepAt, quickMs: quickMs, behind: pace?.behind == true || pace?.watchStopped == true, ids: MONITORED.keySet(), views: LAST]
    }

    /** /status looks at every cluster, and a stranger can ask as often as they like, so its answer is kept for a few seconds. */
    private static List statusAnswer(String path, long now = System.currentTimeMillis()) {
        if (standby) {
            return StatusAnswer.fromRow(path, Lease.row, now)
        }
        Map state = statusState()
        if (path != '/status') {
            return StatusAnswer.answer(path, state, now)
        }
        List memo = statusMemo
        if (memo && now - (memo[0] as long) < 5000L) {
            return memo[1] as List
        }
        List fresh = StatusAnswer.answer(path, state, now)
        statusMemo = [now, fresh]
        fresh
    }

    /** For the all-clusters page: how the monitor itself is doing, which on a node standing by is what the holder last wrote. Numbers only, and who holds the lease. */
    static Map monitorState(long now = System.currentTimeMillis()) {
        Map held = (Lease.row?.summary as Map)?.monitor as Map
        Map ours = standby ? [pace: held?.pace ?: [:], checkers: held?.checkers, watch: held?.watch ?: [on: false], lastSweepAt: Lease.row?.at]
                : [pace: pace, checkers: checkers?.maximumPoolSize, watch: FreezeWatch.summary(now), lastSweepAt: lastSweepAt]
        ours + [statusPort: StatusServer.listening(), statusError: StatusServer.error, lease: [node: Lease.node, holder: Lease.row?.node, standingBy: standby]]
    }

    /** Whether this node is standing by, for the pages: what it shows is stored, not checked here. */
    static boolean standingBy() {
        standby
    }

    /**
     * Whether this node acts on this sweep. It writes first and acts after, and what it writes is the status URL's answers as they stand, for the other nodes to give. A node that has been standing by
     * starts as a load does just before that, so the row it writes says reloading and not stale, as a takeover is a reload to everything that reads it.
     */
    private static boolean holdsLease(MorpheusContext morpheus, long now) {
        boolean acting = Lease.decide(morpheus, {
            if (standby) {
                takeOver(morpheus, now)
            }
            StatusAnswer.summary(statusState(), now) + [monitor: [pace: pace, checkers: checkers?.maximumPoolSize, watch: FreezeWatch.summary(now)]]
        }, now)
        if (acting && standby) {
            takeOver(morpheus, now)   // the row could not be read at all
        } else if (!acting && !standby) {
            standDown()
        }
        acting
    }

    /** Everything a node holds as the one running the checks. What another node writes meanwhile is newer, so none of it can stand. */
    private static void forgetRuns() {
        [LAST, LAST_FULL, SILENT, ABSENT, COULD_NOT_RUN, MONITORED].each { it.clear() }
        lastSweepAt = null
        pace = [:]
        keepingUpAlarm = null
        statusMemo = null
        FreezeWatch.FROZEN_SINCE.clear()
        Availability.forget()
        CapacityTrend.forget()
    }

    /** A check already running finishes, and those waiting never start. The freeze watch stops altogether, so it cannot read a host. */
    private static void standDown() {
        log.info("cluster health: ${Lease.row?.node} runs the checks, so ${Lease.node} stands by")
        standby = true
        checkers?.queue?.clear()
        CHECKING.removeAll(QUEUED.keySet())
        QUEUED.clear()
        FreezeWatch.stop()
        forgetRuns()
    }

    /** The same as a load, so that the clusters spread across an interval, the first check of each is full, and the status URL reads reloading until then. */
    private static synchronized void takeOver(MorpheusContext morpheus, long now) {
        standby = false
        forgetRuns()
        FleetCorrelator.forget(now)
        startedAt = now
        loadedAt = now
        if (sweeper != null) {   // otherwise the plugin is stopping
            FreezeWatch.start(morpheus)
        }
    }

    static long quickMinutes(Map settings) {
        String s = settings?.monitorMinutes as String
        s?.isLong() ? Math.max(0L, s as Long) : DEFAULT_QUICK_MIN
    }

    static long fullHours(Map settings) {
        String s = settings?.fullCheckHours as String
        s?.isLong() ? Math.max(0L, s as Long) : DEFAULT_FULL_HOURS
    }

    /**
     * Where a cluster's turn falls within a period. Multiples of the golden ratio spread evenly however ids are numbered, and the same id keeps its place across
     * reloads, so a restart, or a change of interval, does not bring every cluster due at one moment.
     */
    static long phase(Long id, long period) {
        if (id == null) {
            return 0L
        }
        double x = id * 0.6180339887498949d
        (long) ((x - Math.floor(x)) * period)
    }

    /** The first of this cluster's turns at or after {@code from}. */
    static long turnAt(Long id, long period, long from) {
        from + Math.floorMod(phase(id, period) - from, period)
    }

    /**
     * When the next quick check is due: the first turn at least half an interval after the last check, so one that ran late rejoins its place rather than
     * running twice in a row. With no check yet, the first turn after the load, which spreads a restart across one interval.
     */
    static long quickDueAt(Long id, long quick, Map last, long loaded) {
        turnAt(id, quick, last?.at ? (last.at as long) + (quick >> 1) : loaded)
    }

    /**
     * "quick", "full", or null when this cluster is not due yet. The first check after a load is full, since a quick one would clear the alarms of findings only a full
     * one can see; later ones fall in the cluster's own place in the day, so that 200 clusters do not all run them together a day after a restart.
     */
    static String due(Map settings, Map last, long now = System.currentTimeMillis(), Long id = null, long loaded = 0L) {
        long quick = quickMinutes(settings) * 60_000L
        if (quick <= 0) {
            return null
        }
        long full = fullHours(settings) * 3_600_000L
        long quickAt = quickDueAt(id, quick, last, loaded)
        boolean fullDue = full > 0 && (last?.fullAt ? turnAt(id, full, (last.fullAt as long) + (full >> 1)) : quickAt) <= now
        if (fullDue) {
            return 'full'
        }
        quickAt <= now ? 'quick' : null
    }

    /**
     * How the checks are keeping up: the oldest check, the most overdue cluster, and how many wait for a checker. Pure, and cheap: no database. A cluster whose check cannot run
     * has its own alarm (PRB-02), and one with a job running is taken, so neither says anything about keeping up.
     */
    static Map keepingUp(Collection<Long> ids, Map<Long, Map> last, long quick, long now, long loaded, int waiting, Collection<Long> excused = []) {
        if (quick <= 0) {
            return [clusters: ids.size(), waiting: waiting, checksOff: true, roundMs: null, mostOverdueMs: 0L, behind: false]
        }
        List<Long> counted = ids.findAll { !(it in excused) } as List<Long>
        long overdue = counted.collect { now - quickDueAt(it, quick, last[it], loaded) }.max() ?: 0L
        Long oldest = ids.collect { (last[it]?.at ?: loaded) as long }.min()
        [clusters: ids.size(), waiting: waiting, checksOff: false, roundMs: oldest == null ? null : Math.max(0L, now - oldest), mostOverdueMs: Math.max(0L, overdue), behind: overdue > BEHIND_INTERVALS * quick]
    }

    private static void sweep(MorpheusContext morpheus, Plugin plugin, long now = System.currentTimeMillis()) {
        Map settings = ClusterHealthPlugin.readSettings(morpheus, plugin)
        syncStatusServer(settings)
        if (!holdsLease(morpheus, now)) {
            return
        }
        if (FindingHistory.pruneOrphans(morpheus)) {
            clearOrphanAlarms(morpheus, quickMinutes(settings) > 0)
        }
        List<ComputeServerGroup> listed = morpheus.services.cluster.list(new DataQuery())
        clearGone(morpheus, listed)
        List<ComputeServerGroup> clusters = listed.findAll { ComputeServerGroup c -> monitored(c) }
        if (listed) {   // no clusters at all more likely means Morpheus did not answer, so what is known stays, goes stale and says so
            MONITORED.keySet().retainAll(clusters*.id)
            clusters.each { MONITORED[it.id] = it }
        }
        lastSettings = settings
        long quick = quickMinutes(settings) * 60_000L
        quickMs = quick
        sizeCheckers(checkConcurrency(settings))
        clusters.each { ComputeServerGroup c ->
            String mode = due(settings, LAST[c.id], now, c.id, loadedAt)
            Long failed = COULD_NOT_RUN[c.id]
            if (!mode || Jobs.busy(c.id) || (failed && now - failed < quick)) {
                return
            }
            enqueue(morpheus, c, settings, mode, NORMAL)
        }
        pace = keepingUp(MONITORED.keySet(), LAST, quick, now, loadedAt, checkers.queue.size(), MONITORED.keySet().findAll { COULD_NOT_RUN.containsKey(it) || Jobs.busy(it) }) + [watchStopped: FreezeWatch.stopped(now)]
        lastSweepAt = now
        FreezeWatch.update(clusters, settings, quick > 0, now)
        raiseKeepingUp(morpheus, pace)
    }

    private static void sizeCheckers(int n) {
        if (n > checkers.maximumPoolSize) {
            checkers.maximumPoolSize = n
            checkers.corePoolSize = n
        } else if (n < checkers.maximumPoolSize) {
            checkers.corePoolSize = n
            checkers.maximumPoolSize = n
        }
    }

    /** False if it is already waiting or running: one silent host takes minutes, and must not hold the others up or be queued twice. */
    private static boolean enqueue(MorpheusContext morpheus, ComputeServerGroup c, Map settings, String mode, int priority) {
        if (!CHECKING.add(c.id)) {
            return false
        }
        CheckTask task = new CheckTask(priority, c.id, {
            try {
                if (!standby) {
                    automaticCheck(morpheus, c, settings, mode)
                }
            } finally {
                CHECKING.remove(c.id)
            }
        } as Runnable)
        QUEUED[c.id] = task
        checkers.execute(task)
        true
    }

    /** A quick check ahead of the queue, for the freeze watch. One waiting in the queue is replaced; one running or a job on the cluster will already see what there is to see. */
    static boolean checkNow(MorpheusContext morpheus, Long id) {
        ThreadPoolExecutor pool = checkers
        ComputeServerGroup c = MONITORED[id]
        if (!pool || !c || Jobs.busy(id)) {
            return false
        }
        CheckTask waiting = QUEUED.remove(id)
        if (waiting && pool.remove(waiting)) {
            CHECKING.remove(id)
        }
        enqueue(morpheus, c, lastSettings, 'quick', URGENT)
    }

    /** Whether a check of this cluster is running: the freeze watch need not read it then. One still waiting is another matter, as after a load it can be minutes away, and checkNow moves it up if the watch finds something. */
    static boolean checking(Long id) {
        CHECKING.contains(id) && !QUEUED.containsKey(id)
    }

    /**
     * Which clusters have gone: {@code absent} counts the sweeps running that a cluster known to have existed has not been listed. Absent on two sweeps running, it goes on the
     * next. Never when the list is empty, which more likely means Morpheus did not answer, and that sweep does not count.
     */
    static Set<Long> goneClusters(Map<Long, Integer> absent, Collection<Long> listed) {
        if (!listed) {
            return [] as Set
        }
        absent.keySet().each { Long id -> absent[id] = id in listed ? 0 : absent[id] + 1 }
        listed.each { absent.putIfAbsent(it, 0) }
        absent.findAll { k, v -> v > 2 }.keySet() as Set<Long>
    }

    /** So alarms of a removed cluster do not wait for the daily clean-up, and nothing is kept about it or said about it on the status URL. */
    private static void clearGone(MorpheusContext morpheus, List<ComputeServerGroup> listed) {
        Set<Long> gone = goneClusters(ABSENT, listed*.id).findAll { !CHECKING.contains(it) }   // a check still running would raise them again
        if (!gone) {
            return
        }
        try {
            MorpheusSession.run(morpheus) {
                morpheus.services.operationNotification.list(new DataQuery().withFilter('category', CATEGORY)).findAll { OperationNotification o ->
                    def m = (o.uniqueId ?: '') =~ /^${CATEGORY}-(\d+)(-|$)/
                    o.active != false && m.find() && (m.group(1) as Long) in gone
                }.each { morpheus.services.operationNotification.clearAlarm(it) }
            }
        } catch (Throwable e) {
            log.warn("cluster health alarm clean-up of removed clusters failed: ${e.message}")
            return   // the counts stand, so the next sweep tries again
        }
        gone.each { Long id ->
            ABSENT.remove(id)
            LAST.remove(id)
            LAST_FULL.remove(id)
            COULD_NOT_RUN.remove(id)
            SILENT.remove(id)
            FreezeWatch.forget(morpheus, id)
        }
    }

    /** PRB-04: one alarm for the whole appliance, on the master tenant, as other tenants have no use for it. It covers the freeze watch having stopped too, as that is the monitor falling behind on what matters most. The database is only touched when the answer changes. */
    private static void raiseKeepingUp(MorpheusContext morpheus, Map p) {
        boolean behind = p.behind == true || p.watchStopped == true
        if (keepingUpAlarm == behind) {
            return
        }
        try {
            Account master = masterAccount(morpheus)
            if (!master) {
                return   // tried again next sweep
            }
            MorpheusSession.run(morpheus) {
                List<OperationNotification> open = morpheus.services.operationNotification.list(new DataQuery().withFilter('category', CATEGORY))
                        .findAll { it.uniqueId == KEEPING_UP_ALARM && it.active != false }
                if (!behind) {
                    open.each { morpheus.services.operationNotification.clearAlarm(it) }
                } else if (!open) {
                    long minutes = ((p.mostOverdueMs as long) / 60_000L) as long
                    String observed = p.behind == true ? "the most overdue cluster is ${minutes} min past its turn, with ${p.waiting} checks waiting".toString() : 'the freeze watch has stopped reading, as every thread it has is held by an agent call that never returned'
                    HealthChecker.Result r = new HealthChecker.Result('PRB-04', 'Checks are keeping up', HealthChecker.Severity.MEDIUM, HealthChecker.Status.WARN, null,
                            observed, 'every cluster checked within 2 intervals of its turn, and the freeze watch reading', 'plugin')
                    def created = morpheus.services.operationNotification.createAlarm(new OperationNotification(account: master, category: CATEGORY, eventKey: CATEGORY, uniqueId: KEEPING_UP_ALARM,
                            name: "${r.id} ${r.title}".toString(), resourceName: 'All HVM clusters', refType: 'account', refId: master.id, status: 'warning',
                            statusMessage: "${r.title}: ${r.observed}. See the all-clusters page.".toString(), startDate: new Date(), acknowledged: false, active: true))
                    if (created != null && !created.success) {
                        log.warn("cluster health PRB-04 alarm refused: ${created.msg ?: created.errors}")
                    }
                }
            }
            keepingUpAlarm = behind
        } catch (Throwable e) {
            log.warn("cluster health PRB-04 alarm failed: ${e.message}")
        }
    }

    /** How long a probe may take: null for the normal limit. Automatic quick checks only, as a person pressing Check wants the full answer, and a full check runs once a day. */
    static Long probeLimit(String mode, boolean automatic, boolean silentLastTime) {
        automatic && mode == 'quick' && silentLastTime ? HealthContext.SILENT_PROBE_TIMEOUT_SEC : null
    }

    /** A check that cannot run is reported on the cluster, as nothing else would show that monitoring has stopped. What it last found stays as it was. */
    private static void automaticCheck(MorpheusContext morpheus, ComputeServerGroup c, Map settings, String mode) {
        HealthContext ctx = null
        try {
            ctx = new HealthContext(morpheus, c.id)
            run(morpheus, ctx, settings, mode, true)
            COULD_NOT_RUN.remove(c.id)
        } catch (Throwable e) {
            if (!checkers || checkers.shutdown) {
                return   // the plugin is stopping: that is not a finding
            }
            log.warn("automatic check of ${c.name} failed: ${e.message}")
            COULD_NOT_RUN[c.id] = System.currentTimeMillis()
            if (ctx) {
                setAlarms(morpheus, ctx, [new HealthChecker.Result('PRB-02', 'Automatic check ran', HealthChecker.Severity.HIGH, HealthChecker.Status.FAIL, null, FindingHistory.shortError(e), 'the check runs', 'plugin')], true)
            }
        }
    }

    /**
     * Daily. Nothing else ever updates the alarms of a cluster that has gone, or of any cluster once automatic checks are off, so those are cleared.
     * Never when Morpheus lists no clusters. A check pressed by hand raises its alarms again, until the next clean-up.
     */
    private static void clearOrphanAlarms(MorpheusContext morpheus, boolean monitoring) {
        try {
            Set<Long> live = morpheus.services.cluster.list(new DataQuery())*.id as Set<Long>
            if (!live) {
                return
            }
            MorpheusSession.run(morpheus) {
                morpheus.services.operationNotification.list(new DataQuery().withFilter('category', CATEGORY)).findAll { OperationNotification o ->
                    def m = (o.uniqueId ?: '') =~ /^${CATEGORY}-(\d+)(-|$)/
                    o.active != false && m.find() && !(monitoring && live.contains(m.group(1) as Long))
                }.each { morpheus.services.operationNotification.clearAlarm(it) }
            }
        } catch (Throwable e) {
            log.warn("cluster health alarm clean-up failed: ${e.message}")
        }
    }

    /** HVM clusters this plugin understands; Kubernetes on HVM and external KVM are not. */
    static boolean monitored(ComputeServerGroup c) {
        String code = c?.layout?.code
        code ? Layout.of(code).version != null : (c?.type?.code ?: '').toLowerCase() ==~ /.*(mvm|hvm).*/ && !((c?.type?.code ?: '').toLowerCase().contains('kube'))
    }

    /**
     * What a full check could not have seen stands, as nothing else raises it until the next one, a day away: the full-only findings of a host that did not answer, and those of its disks behind a
     * controller when some were not read. Unlike an alarm, these also stay on the tab, saying they are as last read.
     */
    static List<HealthChecker.Result> unreadFullOnly(List<HealthChecker.Result> before, HealthChecker.Input in_, List<HealthChecker.Result> rs) {
        (before ?: []).findAll { HealthChecker.Result r ->
            HealthChecker.Host h = r.host ? in_.hosts.find { it.name == r.host } : null
            r.status in [HealthChecker.Status.FAIL, HealthChecker.Status.WARN] && h && (!h.facts?.complete || (r.id == 'HW-07' && h.facts.raidDisksUnread)) && !rs.any { it.id == r.id && it.host == r.host && it.title == r.title }
        }.collect { HealthChecker.Result r ->
            r.observed?.startsWith(LAST_READ) ? r : new HealthChecker.Result(r.id, r.title, r.severity, r.status, r.host, "${LAST_READ}${r.observed}".toString(), r.expected, r.source, r.fix, r.tier, r.hint, r.impact)
        }
    }

    private static final String LAST_READ = 'as last read, since the last full check could not read this: '

    /** One evaluation; stores it, keeps the alarm in line, and applies automatic fixes when told to. A person's check on a node standing by shows on its tab and writes history, and the rest is the holder's. */
    static Map run(MorpheusContext morpheus, HealthContext ctx, Map settings, String mode, boolean automatic) {
        Long limit = probeLimit(mode, automatic, SILENT.contains(ctx.cluster.id))
        Map<String, HostFacts> facts = limit ? ctx.probe(mode, limit) : ctx.probe(mode)
        if (facts.values().any { !it?.complete && !it?.partial }) {
            SILENT.add(ctx.cluster.id)
        } else {
            SILENT.remove(ctx.cluster.id)
        }
        HealthChecker.Input in_ = ctx.input(facts, settings?.siteStandards as String)
        FreezeWatch.fromCheck(morpheus, ctx.cluster.id, in_.hosts.findAll { !it.maintenance }.collectEntries { [(it.name): it.facts] } as Map<String, HostFacts>)   // a no-op on a node standing by, whose watch is stopped
        in_.clusterConfig.forecast = CapacityTrend.forecastFor(morpheus, ctx.cluster.id, in_)
        List<HealthChecker.Result> rs = new HealthChecker().check(in_)
        // a quick check does not run these, so their last answer stands: otherwise they would come and go every few minutes
        List<HealthChecker.Result> stands = mode == 'full' ? unreadFullOnly(LAST_FULL[ctx.cluster.id], in_, rs) : (LAST_FULL[ctx.cluster.id] ?: [])
        if (stands) {
            rs = (rs + stands).sort(false) { a, b -> HealthChecker.rank(a) <=> HealthChecker.rank(b) ?: a.id <=> b.id ?: (a.host ?: '') <=> (b.host ?: '') }
        }
        if (mode == 'full') {
            LAST_FULL[ctx.cluster.id] = rs.findAll { it.id in FULL_ONLY }
        }
        rs = Isolation.confirm(morpheus, ctx.cluster, in_, rs)
        Map before = LAST[ctx.cluster.id]
        Map view = HealthContext.view(in_, rs, mode)
        Incidents.decorate(view, in_, rs)
        view.fullAt = mode == 'full' ? view.at : before?.fullAt
        view.hpeChecks = mode == 'full' ? HealthChecker.hpeView(in_) : before?.hpeChecks
        FindingHistory.record(morpheus, ctx, in_, rs, view, LAST_FULL[ctx.cluster.id] != null)
        view.historyError = FindingHistory.ERRORS[ctx.cluster.id]
        view.trendError = CapacityTrend.ERRORS[ctx.cluster.id]
        LAST[ctx.cluster.id] = view
        setAlarms(morpheus, ctx, rs, false, in_)
        if (!standby) {
            Availability.afterRun(morpheus, ctx.cluster.id, in_, rs, settings)
            FleetCorrelator.afterRun(morpheus, ctx.cluster, in_, view, settings)
        }
        if (before?.overall != view.overall && before != null) {
            Audit.warn(morpheus, null, ctx.cluster, "health changed from ${before.overall} to ${view.overall}", [failing: failingIds(rs)])
        }
        if (automatic && !standby) {
            autoFix(morpheus, ctx, in_, rs, settings)
        }
        view
    }

    private static String failingIds(List<HealthChecker.Result> rs) {
        rs.findAll { it.status == HealthChecker.Status.FAIL }*.id.unique().join(',') ?: 'none'
    }

    private static boolean on(Object v) {
        v in [true, 'true', 'on']
    }

    /**
     * Whether the autoFixClusters setting names this cluster: ids or names, comma separated, and empty names none. A number is an
     * id and only an id, so one cluster's name can never select another by its id. Names ignore case and can repeat across tenants.
     */
    static boolean autoFixNamed(Map settings, Long clusterId, String clusterName) {
        ((settings?.autoFixClusters ?: '') as String).tokenize(',')*.trim().findAll().any { String t ->
            t.isLong() ? clusterId != null && (t as Long) == clusterId : t.equalsIgnoreCase(clusterName)
        }
    }

    /** Why an automatic pass may not apply a fix of this tier to this cluster; null when it may. */
    static String autoFixRefusal(HealthChecker.Tier tier, HealthChecker.Input in_, Map settings) {
        if (!((tier == HealthChecker.Tier.SAFE && on(settings?.autoFixSafe)) || (tier == HealthChecker.Tier.MAINTENANCE && on(settings?.autoFixMaintenance)))) {
            return "automatic fixes of the ${tier} tier are off".toString()
        }
        autoFixNamed(settings, in_.clusterId, in_.clusterName) ? null : "${in_.clusterName} is not in the autoFixClusters setting".toString()
    }

    /** The findings an automatic pass may act on, given the settings and the guards. */
    static List<HealthChecker.Result> autoFixable(HealthChecker.Input in_, List<HealthChecker.Result> rs, Map settings, long now = System.currentTimeMillis()) {
        rs.findAll { HealthChecker.Result r ->
            if (!r.fix || !r.host || r.fix in Fixes.MANUAL_ONLY || !(r.status in [HealthChecker.Status.FAIL, HealthChecker.Status.WARN])) {
                return false
            }
            if (autoFixRefusal(r.tier, in_, settings) || Fixes.blockers(r.tier, in_, r.host)) {
                return false
            }
            Long tried = ATTEMPTS[attemptKey(in_.clusterId, r)]
            !tried || now - tried >= RETRY_MS
        }.unique { "${it.host}|${it.fix}".toString() }
    }

    /** Host names can repeat across clusters, and one's retry must not hold up the other's. */
    private static String attemptKey(Long clusterId, HealthChecker.Result r) {
        "${clusterId}|${r.host}|${r.fix}".toString()
    }

    private static void autoFix(MorpheusContext morpheus, HealthContext ctx, HealthChecker.Input in_, List<HealthChecker.Result> rs, Map settings) {
        autoFixable(in_, rs, settings).each { HealthChecker.Result r ->
            ATTEMPTS[attemptKey(in_.clusterId, r)] = System.currentTimeMillis()
            try {
                Jobs.start("automatic fix: ${r.fix}".toString(), ctx.cluster.id, 'automatic') { List<String> progress ->
                    applyFix(morpheus, ctx, r.host, r.fix, null, progress, settings, true)
                }
            } catch (IllegalStateException busy) {
                ATTEMPTS.remove(attemptKey(in_.clusterId, r))   // another job has the cluster: try next pass
            }
        }
    }

    /**
     * Applies one fix: probes again, re-evaluates the guards on that fresh state, runs, then checks again. The guards are
     * always evaluated here, so no route or automatic path can skip them. An automatic fix must also still be allowed for
     * this cluster by the settings as they are now, not as they were when the pass chose it.
     */
    static Map applyFix(MorpheusContext morpheus, HealthContext ctx, String host, String key, com.morpheusdata.model.User who, List<String> progress, Map settings, boolean automatic = false) {
        progress << "${host}: reading the current state of every host".toString()
        Map<String, HostFacts> facts = ctx.probe('quick')
        HealthChecker.Input in_ = ctx.input(facts, settings?.siteStandards as String)
        HostFacts f = facts[host]
        if (!f?.complete) {
            throw new IllegalStateException("${host} did not answer; nothing changed")
        }
        Fixes.Fix fix = Fixes.fix(key, f, (in_.standards.val('ntpServers') ?: []) as List<String>)
        List<String> blocked = Fixes.blockers(fix.tier, in_, host)
        if (automatic) {
            String refused = key in Fixes.MANUAL_ONLY ? "${key} is only ever started by a person".toString()
                    : autoFixRefusal(fix.tier, in_, plugin ? ClusterHealthPlugin.readSettings(morpheus, plugin) : settings)
            blocked = refused ? blocked + refused : blocked
        }
        if (blocked) {
            Audit.warn(morpheus, who, ctx.cluster, "fix refused: ${key}", [host: host, why: blocked.join('; ')])
            throw new IllegalStateException("Not run: ${blocked.join('; ')}")
        }
        progress << "${host}: ${fix.title}".toString()
        Audit.info(morpheus, who, ctx.cluster, "fix started: ${key}", [host: host, tier: fix.tier.name()])
        Map r = ctx.run(ctx.hosts.find { it.name == host }, fix.script, fix.timeoutSec ?: HealthContext.FIX_TIMEOUT_SEC)
        (r.output as String)?.readLines()?.findAll { it.trim() }?.take(40)?.each { progress << "  ${it}".toString() }
        if (r.exit != 0) {
            Audit.warn(morpheus, who, ctx.cluster, "fix failed: ${key}", [host: host, exit: r.exit])
            throw new IllegalStateException("${host}: ${fix.title} failed (exit ${r.exit})")
        }
        Audit.info(morpheus, who, ctx.cluster, "fix done: ${key}", [host: host])
        progress << 'Checking again'
        run(morpheus, ctx, settings, 'quick', false)
    }

    // ------------------------------------------------------------------ alarms

    static String alarmPrefix(Long clusterId) {
        "${CATEGORY}-${clusterId}-".toString()
    }

    /** The check, never the host: a group keeps its alarm as hosts come and go. The empty host keeps a cluster-wide finding's identity from before grouping. */
    private static String alarmId(Long clusterId, HealthChecker.Result r) {
        String key = "${r.id}||${r.title}".toString().digest('MD5').take(12)
        "${alarmPrefix(clusterId)}${r.id}-${key}".toString()
    }

    /**
     * One alarm per incident (see Incidents), as Morpheus has no way for a plugin to raise monitoring checks or incidents: error if the group holds a failure,
     * warning otherwise. It sits on the cluster when the group spans hosts. An incident's identity is stable across runs, so an alarm lasts as long as its incident does. Its
     * message is from when it was raised: findings such as "last 900 s ago" change every run, and replacing the alarm for that would lose its acknowledgement and start time.
     */
    static List<Map> alarmsFor(Long clusterId, String clusterName, List<HealthChecker.Result> rs, HealthChecker.Input in_ = null) {
        Incidents.group(rs).collect { Map g ->
            HealthChecker.Result r = g.root as HealthChecker.Result
            List<String> hosts = g.hosts as List<String>
            String reach = in_ ? Impact.textOf(in_, [r] + (g.repeats as List) + (g.symptoms as List)) : null
            List<HealthChecker.Result> symptoms = g.symptoms as List<HealthChecker.Result>
            [uniqueId: alarmId(clusterId, r), host: hosts.size() == 1 && !g.onCluster ? hosts[0] : null, hosts: g.rootHosts, status: g.status == HealthChecker.Status.FAIL ? 'error' : 'warning',
             name: Isolation.alarmName(r), also: (symptoms*.id + symptoms*.host.findAll()).unique(),
             message: Incidents.message(clusterName, g, reach)]
        }
    }

    /** The plugin API cannot list tenants, but the master tenant is the first one created. Checked, not assumed. */
    static Account masterAccount(MorpheusContext morpheus) {
        Account a = Tenancy.loader(morpheus).call(1L)
        a?.masterAccount == true ? a : null
    }

    /**
     * Every cluster's last result, for the master tenant's fleet view. A cluster not yet checked since the plugin loaded shows its
     * last stored result, marked as such, or failing that says so: an unknown cluster must not read as a healthy one.
     */
    static List<Map> fleet(MorpheusContext morpheus) {
        List<ComputeServerGroup> clusters = morpheus.services.cluster.list(new DataQuery()).findAll { monitored(it) }
        // one query, not one per cluster: a node standing by has none of these in memory for as long as it stands by
        Map<Long, Map> stored = clusters.every { LAST[it.id] } ? [:] : storedSummaries(morpheus)
        clusters.collect { ComputeServerGroup c ->
            Map v = LAST[c.id]
            Map s = v ? FindingHistory.summaryOf(v) : stored[c.id]?.summary as Map
            [id: c.id, name: c.name, owner: c.owner?.name, overall: s?.overall ?: 'NOT CHECKED', layout: s?.layout, at: s?.at, counts: s?.counts, notReady: s?.notReady,
             frozen: s?.frozen ?: false, lastKnown: !v && s != null, standingBy: standby,
             reasons: FleetCorrelator.oneFailure(s)]
        }.sort { a, b -> severityRank(a.overall as String) <=> severityRank(b.overall as String) ?: count(b, 'fail') <=> count(a, 'fail') ?: count(b, 'warn') <=> count(a, 'warn') ?: (a.name as String) <=> (b.name as String) }
    }

    /** Nothing readable is not a failure here: those clusters read as not checked. */
    private static Map<Long, Map> storedSummaries(MorpheusContext morpheus) {
        try {
            PluginRow.readAll(morpheus, FindingHistory.CATEGORY)
        } catch (Throwable e) {
            log.debug("no stored summaries for the all-clusters page: ${e.message}")
            [:]
        }
    }

    private static int count(Map row, String kind) {
        ((row.counts as Map)?.get(kind) ?: 0) as int
    }

    static int severityRank(String overall) {
        ['CRITICAL': 0, 'NOT CHECKED': 1, 'DEGRADED': 2, 'WARNING': 3, 'HEALTHY': 4].getOrDefault(overall, 5)
    }

    /**
     * Brings this plugin's alarms for the cluster in line with its findings: only ever its own, by uniqueId prefix, so
     * Morpheus's other alarms are never touched. They sit on the host a finding is about, otherwise on the cluster, in
     * the tenant that owns the cluster, so customers never see them. A finding that could not be judged this time keeps its alarm, as does one naming a host
     * that did not answer, one only a full check can judge when none has run since the load, an isolation alarm naming a host whose ports were not read, and every alarm when {@code onlyAdd} is set, for a run
     * that found nothing out. {@code in_} is what says who a finding reaches. A new alarm for an old one keeps its start, and says if it had been acknowledged.
     */
    static void setAlarms(MorpheusContext morpheus, HealthContext ctx, List<HealthChecker.Result> rs, boolean onlyAdd = false, HealthChecker.Input in_ = null) {
        if (standby) {
            return   // the holder's, and its next check of this cluster sees what this one saw
        }
        ComputeServerGroup cluster = ctx.cluster
        List<Map> want = alarmsFor(cluster.id, cluster.name, rs, in_)
        // the master tenant cannot otherwise see the alarms of a cluster another tenant owns, so it gets its own copy
        Account master = masterAccount(morpheus)
        if (master && cluster.owner?.id != master.id) {
            want = want.collect { [*:it, account: cluster.owner] } + want.collect { [*:it, account: master, uniqueId: "${it.uniqueId}-master".toString()] }
        } else {
            want = want.collect { [*:it, account: cluster.owner] }
        }
        Set<String> unknown = rs.findAll { it.status == HealthChecker.Status.NOT_CHECKED }.collectMany { [alarmId(cluster.id, it), "${alarmId(cluster.id, it)}-master".toString()] } as Set<String>
        Set<String> silent = (in_ ? in_.hosts.findAll { !it.facts?.complete }*.name : []) as Set<String>
        Set<String> portsUnread = in_ ? Isolation.portsUnread(in_) : [] as Set<String>
        boolean fullKnown = LAST_FULL.containsKey(cluster.id)
        Closure<Boolean> standsUnread = { OperationNotification o ->
            String check = alarmCheck(cluster.id, o.uniqueId)
            (check in FULL_ONLY && !fullKnown) || (check in Isolation.CHECKS && portsUnread.any { Incidents.names(o.statusMessage, it) })
        }
        try {
            Map<String, Long> hostIds = ctx.hosts.collectEntries { [(it.name): it.id] }
            MorpheusSession.run(morpheus) {
                List<OperationNotification> open = openAlarms(morpheus, cluster.id)
                Map<String, OperationNotification> byId = open.collectEntries { [(it.uniqueId): it] }
                Map<String, OperationNotification> replaced = [:]
                open.findAll { OperationNotification o ->
                    Map w = want.find { it.uniqueId == o.uniqueId }
                    if (!w) {
                        return !onlyAdd && !unknown.contains(o.uniqueId) && !silent.any { Incidents.names(o.statusMessage, it) } && !standsUnread.call(o)
                    }
                    // a symptom is not worth losing an acknowledgement for, and an alarm nobody has acknowledged has none to lose
                    w.status != o.status || Incidents.spread(o.statusMessage, w.hosts as List<String>) || (o.acknowledged != true && Incidents.spread(o.statusMessage, w.also as List<String>))
                }.each {
                    morpheus.services.operationNotification.clearAlarm(it)
                    byId.remove(it.uniqueId)
                    replaced[it.uniqueId] = it
                }
                want.findAll { !byId.containsKey(it.uniqueId) }.each { Map a ->
                    Long hostId = hostIds[a.host as String]
                    OperationNotification was = replaced[a.uniqueId as String]
                    def r = morpheus.services.operationNotification.createAlarm(new OperationNotification(
                            account: a.account as Account, category: CATEGORY, eventKey: CATEGORY, uniqueId: a.uniqueId as String, name: a.name as String,
                            resourceName: (a.host ?: cluster.name) as String, refType: hostId ? 'computeServer' : 'computeServerGroup', refId: hostId ?: cluster.id,
                            status: a.status as String, statusMessage: Incidents.withEarlierAck(a.message as String, was), startDate: was?.startDate ?: new Date(), acknowledged: false, active: true))
                    if (r != null && !r.success) {
                        log.warn("cluster health alarm on ${cluster.name} refused: ${r.msg ?: r.errors}")
                    }
                }
            }
        } catch (Throwable e) {
            log.warn("cluster health alarms on ${cluster.name} failed: ${e.message}", e)
        }
    }

    /** The check an alarm of this cluster is for, from its unique id; null for anything else. */
    private static String alarmCheck(Long clusterId, String uniqueId) {
        String prefix = alarmPrefix(clusterId)
        uniqueId?.startsWith(prefix) ? uniqueId.substring(prefix.length()).replaceFirst(/-[0-9a-f]{12}(-master)?$/, '') : null
    }

    /**
     * Whether Morpheus really answers the uniqueId filter below: null until shown, by comparing it once with the whole category on a cluster that has alarms. The operator's
     * meaning is not documented, and a filter that dropped an open alarm would raise it again on every run, so it has to earn its place.
     */
    private static volatile Boolean uniqueIdFilterWorks = null

    /**
     * This cluster's open alarms. Listing the whole category for each cluster is quadratic: 200 clusters with 10 alarms each read 2,000 rows 200 times a round. So the query
     * narrows by uniqueId, and what comes back is still checked by prefix, as the value also matches a longer id such as cluster 151's for cluster 15. It also holds the single
     * per-cluster alarm of the first builds.
     */
    private static List<OperationNotification> openAlarms(MorpheusContext morpheus, Long clusterId) {
        String prefix = alarmPrefix(clusterId), legacy = "${CATEGORY}-${clusterId}".toString()
        Closure<List<OperationNotification>> mine = { List<OperationNotification> found ->
            found.findAll { it.active != false && (it.uniqueId?.startsWith(prefix) || it.uniqueId == legacy) }
        }
        Closure<List<OperationNotification>> narrow = {
            mine(morpheus.services.operationNotification.list(new DataQuery().withFilter('category', CATEGORY).withFilter('uniqueId', ':', legacy)))
        }
        if (uniqueIdFilterWorks == Boolean.TRUE) {
            return narrow.call()
        }
        List<OperationNotification> all = mine(morpheus.services.operationNotification.list(new DataQuery().withFilter('category', CATEGORY)))
        if (uniqueIdFilterWorks == null && all) {
            try {
                uniqueIdFilterWorks = narrow.call()*.uniqueId as Set == all*.uniqueId as Set
            } catch (Exception e) {
                log.warn("cluster health alarms: the uniqueId filter failed: ${e.message}")
                uniqueIdFilterWorks = false
            }
            log.info("cluster health alarms: the uniqueId filter ${uniqueIdFilterWorks ? 'works, so each cluster now lists only its own' : 'did not match the whole list, so each cluster keeps listing the whole category'}")
        }
        all
    }
}
