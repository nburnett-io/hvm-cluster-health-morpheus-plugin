package org.hvmhealth

import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.MorpheusServices
import com.morpheusdata.model.Account
import com.morpheusdata.model.ComputeServer
import com.morpheusdata.model.ComputeServerGroup
import com.morpheusdata.model.ComputeServerGroupType
import com.morpheusdata.model.OperationNotification
import com.morpheusdata.model.TaskResult
import com.morpheusdata.response.ServiceResponse
import io.reactivex.rxjava3.core.Single
import org.hvmhealth.morpheus.FreezeWatch
import org.hvmhealth.morpheus.FreezeWatch.Reading
import org.hvmhealth.morpheus.HealthContext
import org.hvmhealth.morpheus.HealthMonitor
import org.hvmhealth.morpheus.StatusAnswer
import spock.lang.Specification
import spock.util.concurrent.PollingConditions

import java.util.concurrent.atomic.AtomicInteger

/**
 * 200 clusters of 3 hosts on one appliance, on a virtual clock. The decisions are the plugin's own (when a cluster is due, the probe's limit, how far behind it is, which
 * reads the watch makes); only the time a check or a read takes is stubbed. So what these prove is the arithmetic and the logic, not how a real appliance bears it.
 */
class ScaleSpec extends Specification {
    static final long T0 = 1_800_000_000_000L
    static final long SEC = 1000L
    static final long MIN = 60_000L
    static final long HOUR = 60 * MIN
    static final int CLUSTERS = 200
    static final int HOSTS = 3
    /** Measured on a real host at 14 s and 25 s, before the reads added since; a little more for reading the cluster and raising alarms, and for a full check HPE's 6 s, the thin log, busiest VMs and a few disks behind a controller. */
    static final long QUICK_MS = 20 * SEC
    static final long FULL_MS = 50 * SEC

    /** Runs the sweep's decisions once a minute, with a pool of checkers working through them. */
    static class CheckSim {
        Map settings = [:]
        List<Long> ids = (1001L..(1000L + CLUSTERS)) as List<Long>
        Set<Long> silent = [] as Set
        long loaded = T0
        Map<Long, Map> last = [:]
        Set<Long> silentLast = [] as Set
        List<Map> queue = []
        List<Map> running = []
        Set<Long> checking = [] as Set
        /** One row per sweep. */
        List<Map> sweeps = []
        /** Every check as it started. */
        List<Map> started = []
        int peak = 0

        int getConcurrency() { HealthMonitor.checkConcurrency(settings) }

        void run(long until) {
            long quick = HealthMonitor.quickMinutes(settings) * MIN
            for (long now = loaded; now <= until; now += MIN) {
                // the checkers finish and take up work to the second, between sweeps
                for (long t = now - MIN + SEC; t <= now; t += SEC) {
                    work(t)
                }
                int due = 0
                ids.each { Long id ->
                    String mode = HealthMonitor.due(settings, last[id], now, id, loaded)
                    if (mode && checking.add(id)) {
                        queue << [id: id, mode: mode]
                        due++
                    }
                }
                work(now)
                sweeps << [at: now, due: due] + HealthMonitor.keepingUp(ids, last, quick, now, loaded, queue.size())
            }
        }

        private void work(long t) {
            running.removeAll { Map r ->
                if ((r.end as long) > t) {
                    return false
                }
                Long id = r.id as Long
                last[id] = [at: r.end, fullAt: r.mode == 'full' ? r.end : last[id]?.fullAt]
                if (id in silent) { silentLast << id } else { silentLast.remove(id) }
                checking.remove(id)
                true
            }
            while (queue && running.size() < concurrency) {
                Map task = queue.remove(0)
                Long limit = HealthMonitor.probeLimit(task.mode as String, true, (task.id as Long) in silentLast)
                long took = (task.id as Long) in silent ? ((limit ?: HealthContext.PROBE_TIMEOUT_SEC) + 10L) * SEC : (task.mode == 'full' ? FULL_MS : QUICK_MS)
                running << [id: task.id, mode: task.mode, end: t + took]
                started << [at: t, id: task.id, mode: task.mode, took: took]
            }
            peak = Math.max(peak, running.size())
        }

        /** The longest gap between one cluster's checks started in the window. */
        long worstGap(long from, Collection<Long> among = ids) {
            among.collect { Long id ->
                List<Long> at = started.findAll { it.id == id && (it.at as long) >= from }*.at as List<Long>
                at.size() < 2 ? 0L : (0..<(at.size() - 1)).collect { at[it + 1] - at[it] }.max()
            }.max()
        }
    }

    def 'after a reload the first checks spread across one interval, rather than all landing at once'() {
        given:
        CheckSim sim = new CheckSim()

        when:
        sim.run(T0 + 12 * MIN)
        List<Integer> firstFive = sim.sweeps.take(6)*.due

        then: 'each minute of the five about a fifth'
        firstFive.every { it <= 60 }
        firstFive.sum() == CLUSTERS
        firstFive.count { it >= 25 } >= 4

        and: 'all the first checks are full ones, and all done within the interval and the time they take'
        sim.started.take(CLUSTERS)*.mode.every { it == 'full' }
        sim.started.take(CLUSTERS)*.id as Set == sim.ids as Set
        (sim.started[CLUSTERS - 1].at as long) - T0 <= 9 * MIN

        and: 'the checkers are the limit: at 3 hosts each, 60 agent calls'
        sim.peak <= HealthMonitor.DEFAULT_CHECK_CONCURRENCY
        sim.peak * HOSTS <= 60
    }

    def 'the first round after a reload is done inside the status URL\'s grace, with #silent clusters holding a silent host, and PRB-04 stays quiet'() {
        given: 'turns are timed from the first sweep, which is 2 minutes after the load'
        Set<Long> those = silent ? (1001L..(1000L + silent)) as Set<Long> : [] as Set<Long>
        CheckSim sim = new CheckSim(silent: those)

        when:
        sim.run(T0 + 20 * MIN)
        long lastDone = sim.started.take(CLUSTERS).collect { (it.at as long) + (it.took as long) }.max()

        then:
        sim.started.take(CLUSTERS)*.id as Set == sim.ids as Set
        2 * MIN + (lastDone - T0) < StatusAnswer.RELOAD_GRACE_MS
        !sim.sweeps.any { it.behind }

        where:
        silent << [0, 10, 30]
    }

    def 'the old behaviour would have had every cluster due at the first sweep: the stagger is what prevents it'() {
        given:
        int unstaggered = (1001L..1200L).count { Long id -> HealthMonitor.due([:], null, T0, null, 0L) }
        int staggered = (1001L..1200L).count { Long id -> HealthMonitor.due([:], null, T0, id, T0) }

        expect:
        unstaggered == CLUSTERS
        staggered <= 3
    }

    def 'a sequential run of ids spreads evenly, and ids in any other order too'() {
        expect:
        spread(1001L..1200L) >= 30
        spread((1..200).collect { it * 7L }) >= 30
        spread((1..200).collect { 500L + it * 1000L }) >= 30
    }

    /** The fewest clusters in any one of five equal parts of an interval. */
    private static int spread(Collection<Long> ids) {
        (0..4).collect { int part -> ids.count { Long id -> (HealthMonitor.phase(id, 5 * MIN) / MIN) as int == part } }.min()
    }

    def 'a cluster keeps its place across a reload, so restarts do not change who is due when'() {
        given:
        long a = HealthMonitor.turnAt(1234L, 5 * MIN, T0)
        long b = HealthMonitor.turnAt(1234L, 5 * MIN, T0 + 17 * MIN)

        expect:
        (b - a) % (5 * MIN) == 0
        b >= T0 + 17 * MIN
        b < T0 + 22 * MIN
    }

    def 'full checks then spread across the day, instead of 200 at once a day after a restart'() {
        given:
        CheckSim sim = new CheckSim()

        when:
        sim.run(T0 + 49 * HOUR)
        List<Map> later = sim.started.findAll { it.mode == 'full' && (it.at as long) > T0 + 10 * MIN }
        List<Map> oneDay = later.findAll { (it.at as long) >= T0 + 24 * HOUR && (it.at as long) < T0 + 48 * HOUR }
        Map<Long, List<Map>> byHour = oneDay.groupBy { (((it.at as long) - T0) / HOUR) as long }

        then: 'every cluster gets one a day'
        oneDay*.id.unique().size() >= CLUSTERS - 2
        oneDay.size() <= CLUSTERS + 2

        and: 'no hour holds more than a fair share'
        byHour.values()*.size().max() <= 16
        byHour.size() == 24

        and: 'and no five minutes holds more than a couple'
        oneDay.groupBy { (((it.at as long) - T0) / (5 * MIN)) as long }.values()*.size().max() <= 5
    }

    def 'with 200 clusters and the default setting, every cluster is checked on time, for hours, and PRB-04 stays quiet'() {
        given:
        CheckSim sim = new CheckSim()

        when:
        sim.run(T0 + 6 * HOUR)
        List<Map> settled = sim.sweeps.findAll { (it.at as long) > T0 + 30 * MIN }

        then:
        sim.worstGap(T0 + 30 * MIN) <= 6 * MIN
        settled*.mostOverdueMs.max() <= 2 * MIN
        settled*.waiting.max() <= 40
        !sim.sweeps.any { it.behind }
        sim.peak <= HealthMonitor.DEFAULT_CHECK_CONCURRENCY
    }

    def 'the 4 checkers it used to have cannot do it: PRB-04 says so. If this passes, the test above proves nothing'() {
        given:
        CheckSim sim = new CheckSim(settings: [checkConcurrency: '4'])

        when:
        sim.run(T0 + 3 * HOUR)

        then:
        sim.sweeps.any { it.behind }
        (sim.sweeps.last().waiting as int) > 50
        sim.peak == 4
    }

    def '10 clusters with a silent host do not starve the rest, and take the shorter limit after their first run'() {
        given:
        Set<Long> silent = (1001L..1010L) as Set
        CheckSim sim = new CheckSim(silent: silent)
        List<Long> others = sim.ids - silent

        when:
        sim.run(T0 + 3 * HOUR)
        List<Map> theirs = sim.started.findAll { it.id in silent && (it.at as long) > T0 + 30 * MIN && it.mode == 'quick' }

        then: 'the others are still on time'
        sim.worstGap(T0 + 30 * MIN, others) <= 6 * MIN
        !sim.sweeps.any { it.behind }

        and: 'their first check was full and held the whole limit, and quick ones after it did not'
        sim.started.findAll { it.id in silent && it.mode == 'full' }*.took.every { it == 160 * SEC }
        theirs*.took.every { it == 85 * SEC }
    }

    def 'many silent hosts at once, a switch say: 40 clusters with a host silent still leave the others no more than 2 intervals apart'() {
        given:
        Set<Long> silent = (1001L..1040L) as Set
        CheckSim sim = new CheckSim(silent: silent)

        when:
        sim.run(T0 + 3 * HOUR)

        then:
        sim.worstGap(T0 + 30 * MIN, sim.ids - silent) <= 10 * MIN
        !sim.sweeps.findAll { (it.at as long) > T0 + 30 * MIN }.any { it.behind }
    }

    def 'without the shorter limit, those same 40 would cost nearly twice as much: this is the difference it makes'() {
        expect:
        (HealthContext.PROBE_TIMEOUT_SEC + 10L) as double >= 1.8d * (HealthMonitor.probeLimit('quick', true, true) + 10L)
    }

    def 'the shorter limit is for automatic quick checks that had a silent host, only'() {
        expect:
        HealthMonitor.probeLimit('quick', true, true) == HealthContext.SILENT_PROBE_TIMEOUT_SEC
        HealthMonitor.probeLimit('quick', true, false) == null
        HealthMonitor.probeLimit('full', true, true) == null
        HealthMonitor.probeLimit('quick', false, true) == null
        HealthContext.probeScript('quick', HealthContext.SILENT_PROBE_TIMEOUT_SEC).startsWith('set -- quick 45\n')
    }

    // ------------------------------------------------------------------ PRB-04's numbers

    def 'PRB-04 is about being 2 intervals past a turn, and not 1'() {
        given:
        long quick = 5 * MIN
        long turn = HealthMonitor.quickDueAt(7L, quick, [at: T0], T0)

        expect:
        !HealthMonitor.keepingUp([7L], [7L: [at: T0]], quick, turn + 2 * quick, T0, 0).behind
        HealthMonitor.keepingUp([7L], [7L: [at: T0]], quick, turn + 2 * quick + 1, T0, 0).behind
        HealthMonitor.keepingUp([7L], [7L: [at: T0]], quick, turn + 2 * quick + 1, T0, 0).mostOverdueMs == 2 * quick + 1
    }

    def 'a cluster that cannot run has its own alarm, and is not called falling behind'() {
        given:
        long quick = 5 * MIN

        expect:
        HealthMonitor.keepingUp([7L, 8L], [7L: [at: T0 - 60 * MIN], 8L: [at: T0 - 60_000L]], quick, T0, T0 - 2 * HOUR, 0).behind
        !HealthMonitor.keepingUp([7L, 8L], [7L: [at: T0 - 60 * MIN], 8L: [at: T0 - 60_000L]], quick, T0, T0 - 2 * HOUR, 0, [7L]).behind
    }

    def 'a cluster never checked since the load is timed from the load, not from the epoch'() {
        expect:
        !HealthMonitor.keepingUp([7L], [:], 5 * MIN, T0, T0 - 3 * MIN, 0).behind
        HealthMonitor.keepingUp([7L], [:], 5 * MIN, T0 + 30 * MIN, T0 - 3 * MIN, 0).behind
    }

    def 'with automatic checks off nothing is behind, as nothing is meant to run'() {
        given:
        Map p = HealthMonitor.keepingUp([7L], [:], 0L, T0, T0 - 3 * MIN, 0)

        expect:
        !p.behind
        p.checksOff
    }

    def 'its numbers are the oldest check, the most overdue, and the queue, with no ids or names'() {
        given:
        Map p = HealthMonitor.keepingUp([7L, 8L], [7L: [at: T0 - 7 * MIN], 8L: [at: T0 - MIN]], 5 * MIN, T0, T0 - HOUR, 3)

        expect:
        p.keySet() == ['clusters', 'waiting', 'checksOff', 'roundMs', 'mostOverdueMs', 'behind'] as Set
        p.roundMs == 7 * MIN
        p.waiting == 3
        p.clusters == 2
    }

    // ------------------------------------------------------------------ settings

    def 'checkConcurrency #value reads as #want'() {
        expect:
        HealthMonitor.checkConcurrency(value == null ? [:] : [checkConcurrency: value]) == want

        where:
        value  | want
        null   | HealthMonitor.DEFAULT_CHECK_CONCURRENCY
        ''     | HealthMonitor.DEFAULT_CHECK_CONCURRENCY
        'x'    | HealthMonitor.DEFAULT_CHECK_CONCURRENCY
        '0'    | HealthMonitor.DEFAULT_CHECK_CONCURRENCY
        '-3'   | HealthMonitor.DEFAULT_CHECK_CONCURRENCY
        '8'    | 8
        8      | 8
        '500'  | HealthMonitor.MAX_CHECK_CONCURRENCY
    }

    // ------------------------------------------------------------------ the freeze watch

    /** Ticks the plugin's schedule through a virtual hour, with latencies per host. */
    static class WatchSim {
        FreezeWatch.Schedule schedule = new FreezeWatch.Schedule(new Random(42))
        List<Long> ids = (1001L..(1000L + CLUSTERS)) as List<Long>
        /** cluster -> the host that is silent. */
        Map<Long, String> silent = [:]
        Set<Long> frozen = [] as Set
        Map<String, Reading> always = [:]
        Closure<Boolean> checking = { false }
        List<String> names = (1..HOSTS).collect { "hvm-${it}.invalid".toString() }
        List<Map> inFlight = []
        List<Long> starts = []
        Map<Long, List<Long>> readsOf = [:].withDefault { [] }
        Map<Long, List<String>> hostsRead = [:].withDefault { [] }
        List<Map> events = []
        int peak = 0
        long healthyMs = 2000L

        void run(long from, long until, long seconds = 60L, int budget = 120) {
            for (long t = from; t <= until; t += FreezeWatch.TICK_MS) {
                schedule.update(ids, seconds, budget, t)
                inFlight.removeAll { Map r ->
                    if ((r.end as long) > t) {
                        return false
                    }
                    Long id = r.id as Long
                    Reading rd = r.reading as Reading
                    events << schedule.result(r.watch as FreezeWatch.Watch, r.host as String, rd, rd == Reading.NONE ? null : true, names, r.end as long) + [id: id, at: r.end]
                    true
                }
                schedule.pick(t, FreezeWatch.READERS - inFlight.size(), checking).each { FreezeWatch.Watch w ->
                    String host = schedule.chooseHost(w, names, t)
                    boolean isSilent = silent[w.id] == host
                    starts << t
                    readsOf[w.id] << t
                    hostsRead[w.id] << host
                    inFlight << [id: w.id, watch: w, host: host, end: t + (isSilent ? HealthContext.WATCH_TIMEOUT_SEC * SEC : healthyMs),
                                 reading: isSilent ? Reading.NONE : (w.id in frozen ? Reading.FROZEN : Reading.CLEAR)]
                }
                peak = Math.max(peak, inFlight.size())
            }
        }

        /** The most reads started in any 60 s. */
        int busiestMinute() {
            List<Long> s = starts.sort(false)
            int best = 0, left = 0
            s.eachWithIndex { long at, int right ->
                while (at - s[left] >= 60 * SEC) { left++ }
                best = Math.max(best, right - left + 1)
            }
            best
        }

        long worstGap(long from, Collection<Long> among = ids) {
            among.collect { Long id ->
                List<Long> at = readsOf[id].findAll { it >= from }
                at.size() < 2 ? 0L : (0..<(at.size() - 1)).collect { at[it + 1] - at[it] }.max()
            }.max()
        }
    }

    def 'with 200 clusters the budget holds, and the interval stretches evenly instead of the load growing'() {
        given:
        WatchSim sim = new WatchSim()

        when:
        sim.run(T0, T0 + HOUR)

        then: 'the budget, in any minute'
        sim.busiestMinute() <= 120

        and: 'nearly all of it used, so the watch is as quick as the budget allows'
        sim.starts.findAll { it >= T0 + 10 * MIN }.size() >= 0.9 * 120 * 50

        and: 'every cluster read about every 100 s, none left waiting'
        sim.schedule.forCluster(1001L).seconds == 100L
        sim.worstGap(T0 + 5 * MIN) <= 150 * SEC
        sim.ids.every { sim.readsOf[it].size() >= 25 }

        and: 'reads at once stay small'
        sim.peak <= FreezeWatch.READERS
    }

    def 'it does not line up: reads are spread over the interval, not in a burst each round'() {
        given:
        WatchSim sim = new WatchSim()

        when:
        sim.run(T0, T0 + HOUR)
        Map<Long, Integer> perTick = sim.starts.findAll { it >= T0 + 10 * MIN }.countBy { it }

        then: 'the budget is 4 a tick, and nearly every tick has reads'
        perTick.values().max() <= 4
        perTick.size() >= 0.95 * (50 * 30)
    }

    def 'it spreads from the very first ticks after a load'() {
        given:
        WatchSim sim = new WatchSim()

        when:
        sim.run(T0, T0 + 2 * MIN)

        then:
        sim.starts.findAll { it <= T0 + 4 * SEC }.size() <= 8
        sim.busiestMinute() <= 120
    }

    def 'under the budget it keeps the interval asked for, and spends only what it needs'() {
        given:
        WatchSim sim = new WatchSim(ids: (1001L..1030L) as List<Long>)

        when:
        sim.run(T0, T0 + 30 * MIN)

        then:
        sim.schedule.forCluster(1001L).seconds == 60L
        sim.busiestMinute() <= 45
        sim.worstGap(T0 + 5 * MIN) <= 75 * SEC
    }

    def 'a tighter budget stretches further, and still holds'() {
        given:
        WatchSim sim = new WatchSim()

        when:
        sim.run(T0, T0 + HOUR, 60L, 30)

        then:
        sim.schedule.forCluster(1001L).seconds == 400L
        sim.busiestMinute() <= 30
        sim.worstGap(T0 + 15 * MIN) <= 600 * SEC
    }

    def 'silent hosts count against the budget too, and neither they nor the second host they cost can break it'() {
        given: 'a quarter of the clusters have one silent host, with a 20 s read each time it comes round'
        WatchSim sim = new WatchSim(silent: (1001L..1050L).collectEntries { [(it): 'hvm-2.invalid'] })

        when:
        sim.run(T0, T0 + HOUR)

        then:
        sim.busiestMinute() <= 120
        sim.peak <= FreezeWatch.READERS

        and: 'clusters with no silent host are not starved, and neither are those with one'
        sim.worstGap(T0 + 5 * MIN, sim.ids - (1001L..1050L)) <= 180 * SEC
        sim.worstGap(T0 + 5 * MIN, (1001L..1050L) as List<Long>) <= 240 * SEC

        and: 'and no silent host is mistaken for anything'
        sim.events.every { !it.started && !it.ended }
    }

    def 'every host in every cluster silent, the worst case: the budget and the readers still hold, and it finds nothing'() {
        given:
        WatchSim sim = new WatchSim(silent: [:].withDefault { 'hvm-1.invalid' })
        sim.healthyMs = 20 * SEC
        sim.silent = (1001L..1200L).collectEntries { [(it): 'hvm-1.invalid'] }

        when:
        sim.run(T0, T0 + 30 * MIN)

        then:
        sim.busiestMinute() <= 120
        sim.peak <= FreezeWatch.READERS
        sim.events.every { !it.started }
    }

    def 'each host of a cluster gets its turn'() {
        given:
        WatchSim sim = new WatchSim()

        when:
        sim.run(T0, T0 + HOUR)

        then:
        sim.ids.every { Long id -> sim.hostsRead[id].countBy { it }.keySet().size() == HOSTS }
        sim.ids.every { Long id -> sim.hostsRead[id].countBy { it }.values().with { max() - min() } <= 1 }
    }

    def 'a frozen cluster costs what any other does: one read a round'() {
        given:
        Set<Long> frozen = [1001L, 1002L, 1003L] as Set
        WatchSim sim = new WatchSim(frozen: frozen)
        List<Long> others = sim.ids - frozen

        when:
        sim.run(T0, T0 + HOUR)

        then: 'found, each once, with a check'
        sim.events.count { it.started != null } == 3
        sim.events.findAll { it.started != null }.every { it.check }

        and: 'after that, the same reads as anyone else, and nothing extra for the rest to pay for'
        Math.abs(sim.readsOf[1001L].size() - sim.readsOf[others[0]].size()) <= 3
        sim.busiestMinute() <= 120
        sim.worstGap(T0 + 5 * MIN, others) <= 150 * SEC

        and: 'and it does not ask for a check again on every read'
        sim.events.count { it.check } == 3
    }

    def 'a cluster being checked costs no read until that check ends, and the others are not held up'() {
        given:
        Set<Long> busy = (1001L..1100L) as Set
        WatchSim sim = new WatchSim(checking: { Long id -> id in busy })

        when:
        sim.run(T0, T0 + 30 * MIN)

        then:
        busy.every { sim.readsOf[it].isEmpty() }
        sim.busiestMinute() <= 120
        sim.worstGap(T0 + 5 * MIN, sim.ids - busy) <= 150 * SEC
    }

    // ------------------------------------------------------------------ with real threads: the sweep itself, 200 clusters, stubbed agent

    List<ComputeServerGroup> clusters = (1..CLUSTERS).collect { new ComputeServerGroup(id: 1000L + it, name: "cluster-${it}".toString(), owner: new Account(id: 2L, name: 'Acme'), type: new ComputeServerGroupType(code: 'mvm-cluster')) }
    String settings = '{}'
    List<OperationNotification> alarms = Collections.synchronizedList([])
    List<OperationNotification> created = Collections.synchronizedList([])
    List<OperationNotification> cleared = Collections.synchronizedList([])
    AtomicInteger clusterLists = new AtomicInteger()
    boolean listFails = false
    boolean clusterFails = false
    AtomicInteger hostLists = new AtomicInteger()
    AtomicInteger agentCalls = new AtomicInteger()
    AtomicInteger inAgent = new AtomicInteger()
    AtomicInteger peakAgent = new AtomicInteger()
    long agentMs = 5L
    String sample = Fixture.text('probe-healthy').replace(' 5    vm-one   running', '')

    private MorpheusContext morpheus() {
        ScaleSpec self = this
        def balancer = Stub(MorpheusContext.getMethod('getAsync').returnType.getMethod('getLoadBalancer').returnType) {
            getAccountById(_) >> { Long id -> Single.just(id == 1L ? new Account(id: 1L, name: 'Master', masterAccount: true) : new Account(id: id, name: 'Acme')) }
        }
        def async = Stub(MorpheusContext.getMethod('getAsync').returnType) { getLoadBalancer() >> balancer }
        def clusterService = Stub(MorpheusServices.getMethod('getCluster').returnType) {
            list(_) >> { self.clusterLists.incrementAndGet(); if (self.clusterFails) { throw new RuntimeException('no database') }; self.clusters }
            get(_) >> { Long id -> self.clusters.find { it.id == id } }
        }
        def servers = Stub(MorpheusServices.getMethod('getComputeServer').returnType) {
            list(_) >> { self.hostLists.incrementAndGet(); (1..HOSTS).collect { new ComputeServer(id: it as Long, name: "hvm-${it}.invalid".toString()) } }
        }
        def notifications = Stub(MorpheusServices.getMethod('getOperationNotification').returnType) {
            list(_) >> { if (self.listFails) { throw new RuntimeException('no database') }; new ArrayList(self.alarms) }
            createAlarm(_) >> { args -> self.created << args[0]; self.alarms << args[0]; ServiceResponse.success() }
            clearAlarm(_) >> { args -> self.cleared << args[0]; self.alarms.remove(args[0]); ServiceResponse.success() }
        }
        def services = Stub(MorpheusServices) {
            getCluster() >> clusterService
            getComputeServer() >> servers
            getOperationNotification() >> notifications
        }
        Stub(MorpheusContext) {
            getServices() >> services
            getAsync() >> async
            getSettings(_) >> { Single.just(self.settings) }
            executeCommandOnServer(_, _) >> { args ->
                self.agentCalls.incrementAndGet()
                Single.fromCallable {
                    int n = self.inAgent.incrementAndGet()
                    self.peakAgent.accumulateAndGet(n) { a, b -> Math.max(a, b) }
                    Thread.sleep(self.agentMs)
                    self.inAgent.decrementAndGet()
                    new TaskResult(success: true, exitCode: '0', output: self.sample)
                }
            }
        }
    }

    def cleanup() {
        HealthMonitor.stop()
        clusters.each { HealthMonitor.LAST.remove(it.id) }
        [27L, 7L, 8L].each { HealthMonitor.LAST.remove(it) }
        FreezeWatch.FROZEN_SINCE.clear()
    }

    def 'the real sweep over 200 clusters: one list call, no per-cluster reads, a fifth due at a time, and the checkers hold'() {
        given:
        MorpheusContext m = morpheus()
        HealthMonitor.start(m, new ClusterHealthPlugin())
        long loaded = HealthMonitor.@loadedAt
        settings = '{"monitorMinutes":"5","fullCheckHours":"0","checkConcurrency":"8","freezeWatchSeconds":"0"}'
        // no hosts or checks are asked for by the sweep itself: nothing is due yet
        clusterLists.set(0)

        when:
        HealthMonitor.sweep(m, new ClusterHealthPlugin(), loaded - 60_000L)
        int listsForASweep = clusterLists.get()

        then: 'a sweep with nothing due reads no host at all, however many clusters there are'
        hostLists.get() == 0
        agentCalls.get() == 0
        listsForASweep <= 3   // this one, and the two daily clean-ups on the first sweep after a load

        when: 'a second sweep: the daily clean-ups are done'
        clusterLists.set(0)
        HealthMonitor.sweep(m, new ClusterHealthPlugin(), loaded - 30_000L)

        then:
        clusterLists.get() == 1
        hostLists.get() == 0

        when: 'one minute into the interval, about a fifth have had their turn'
        HealthMonitor.sweep(m, new ClusterHealthPlugin(), loaded + 60_000L)
        long dueNow = clusters.count { HealthMonitor.turnAt(it.id, 5 * MIN, loaded) <= loaded + 60_000L }
        new PollingConditions(timeout: 60).eventually { assert HealthMonitor.LAST.keySet().count { it > 1000L && it <= 1000L + CLUSTERS } == dueNow }

        then:
        dueNow >= 20
        dueNow <= 60
        peakAgent.get() <= 8 * HOSTS
        HealthMonitor.@checkers.largestPoolSize <= 8

        when: 'past the whole interval, every one has'
        HealthMonitor.sweep(m, new ClusterHealthPlugin(), loaded + 6 * 60_000L)

        then:
        new PollingConditions(timeout: 120).eventually { assert clusters.every { HealthMonitor.LAST.containsKey(it.id) } }
        new PollingConditions(timeout: 30).eventually { assert HealthMonitor.@CHECKING.isEmpty() }
        peakAgent.get() <= 8 * HOSTS
        HealthMonitor.@checkers.largestPoolSize <= 8
        // those that ran a minute in have had a turn since, as their last check is on the real clock and this sweep on a later one
        agentCalls.get() == (CLUSTERS + dueNow) * HOSTS
    }

    def 'a reload mid-way: nothing carries over, nothing is stuck, and it starts spreading again'() {
        given:
        MorpheusContext m = morpheus()
        clusters = clusters.take(20)
        settings = '{"fullCheckHours":"0"}'
        HealthMonitor.start(m, new ClusterHealthPlugin())
        long loaded = HealthMonitor.@loadedAt
        agentMs = 200L
        HealthMonitor.sweep(m, new ClusterHealthPlugin(), loaded + 10 * MIN)
        new PollingConditions(timeout: 10).eventually { assert agentCalls.get() > 0 }

        when: 'unloaded with checks under way, and loaded again'
        HealthMonitor.stop()
        HealthMonitor.start(m, new ClusterHealthPlugin())

        then:
        HealthMonitor.@CHECKING.isEmpty()
        HealthMonitor.@QUEUED.isEmpty()
        HealthMonitor.monitorState().lastSweepAt == null
        HealthMonitor.@checkers.queue.isEmpty()
    }

    def 'changing checkConcurrency takes effect at the next sweep, up or down'() {
        given:
        MorpheusContext m = morpheus()
        clusters = clusters.take(1)
        HealthMonitor.start(m, new ClusterHealthPlugin())
        long early = HealthMonitor.@loadedAt - 60_000L

        when:
        settings = '{"checkConcurrency":"30"}'
        HealthMonitor.sweep(m, new ClusterHealthPlugin(), early)

        then:
        HealthMonitor.@checkers.maximumPoolSize == 30
        HealthMonitor.@checkers.corePoolSize == 30

        when:
        settings = '{"checkConcurrency":"6"}'
        HealthMonitor.sweep(m, new ClusterHealthPlugin(), early)

        then:
        HealthMonitor.@checkers.maximumPoolSize == 6
        HealthMonitor.@checkers.corePoolSize == 6
    }

    // ------------------------------------------------------------------ alarms of clusters that have gone

    def 'a cluster is gone once absent on two sweeps running, and on the next its alarms go'() {
        given:
        Map<Long, Integer> absent = [:]

        expect:
        HealthMonitor.goneClusters(absent, [1L, 2L]) == [] as Set   // both known
        HealthMonitor.goneClusters(absent, [1L]) == [] as Set       // 2 absent once
        HealthMonitor.goneClusters(absent, [1L]) == [] as Set       // twice
        HealthMonitor.goneClusters(absent, [1L]) == [2L] as Set     // the next sweep
    }

    def 'a cluster back before then starts again from nothing'() {
        given:
        Map<Long, Integer> absent = [:]
        HealthMonitor.goneClusters(absent, [1L, 2L])
        HealthMonitor.goneClusters(absent, [1L])
        HealthMonitor.goneClusters(absent, [1L])

        expect:
        HealthMonitor.goneClusters(absent, [1L, 2L]) == [] as Set
        HealthMonitor.goneClusters(absent, [1L]) == [] as Set
        HealthMonitor.goneClusters(absent, [1L]) == [] as Set
        HealthMonitor.goneClusters(absent, [1L]) == [2L] as Set
    }

    def 'an empty list is not a sweep without them: it never counts, however many there are'() {
        given:
        Map<Long, Integer> absent = [:]
        HealthMonitor.goneClusters(absent, [1L, 2L])

        expect:
        (1..10).every { HealthMonitor.goneClusters(absent, []) == [] as Set }
        absent == [1L: 0, 2L: 0]
        HealthMonitor.goneClusters(absent, null) == [] as Set
    }

    def 'an id it has never seen is not gone: there is nothing to say it existed'() {
        given:
        Map<Long, Integer> absent = [:]

        expect:
        HealthMonitor.goneClusters(absent, [1L]) == [] as Set
        !absent.containsKey(99L)
    }

    private OperationNotification alarm(Long clusterId, String rest = 'MOR-03-abc') {
        new OperationNotification(uniqueId: "hvm-cluster-health-${clusterId}-${rest}".toString(), status: 'error', active: true)
    }

    def 'through the sweep: only the removed cluster loses its alarms, both copies, on the third sweep, and what was held about it goes too'() {
        given:
        clusters = clusters.take(2)
        Long stays = clusters[0].id
        Long goes = clusters[1].id
        OperationNotification keep = alarm(stays)
        OperationNotification one = alarm(goes)
        OperationNotification two = new OperationNotification(uniqueId: "hvm-cluster-health-${goes}-MOR-03-abc-master".toString(), status: 'error', active: true)
        OperationNotification other = new OperationNotification(uniqueId: "hvm-cluster-health-${goes}0-X".toString(), status: 'error', active: true)
        alarms.addAll([keep, one, two, other])
        MorpheusContext m = morpheus()
        HealthMonitor.start(m, new ClusterHealthPlugin())
        long early = HealthMonitor.@loadedAt - 60_000L
        // the first sweep's daily clean-up would otherwise clear the test's stray alarm, whose cluster never existed
        alarms.remove(other)
        HealthMonitor.sweep(m, new ClusterHealthPlugin(), early)
        alarms.add(other)
        HealthMonitor.LAST[goes] = [at: System.currentTimeMillis(), overall: 'CRITICAL']
        FreezeWatch.FROZEN_SINCE[goes] = 1L

        when: 'it is removed'
        clusters = clusters.take(1)
        HealthMonitor.sweep(m, new ClusterHealthPlugin(), early)
        HealthMonitor.sweep(m, new ClusterHealthPlugin(), early)

        then: 'gone from the status URL at once, its alarms not yet touched'
        cleared == []
        !HealthMonitor.@MONITORED.containsKey(goes)

        when:
        HealthMonitor.sweep(m, new ClusterHealthPlugin(), early)

        then:
        cleared as Set == [one, two] as Set
        keep in alarms
        other in alarms
        !HealthMonitor.LAST.containsKey(goes)
        !FreezeWatch.FROZEN_SINCE.containsKey(goes)
    }

    def 'Morpheus listing nothing, however long, clears nothing, and what is known stays and goes stale'() {
        given:
        clusters = clusters.take(1)
        Long id = clusters[0].id
        alarms.add(alarm(id))
        MorpheusContext m = morpheus()
        HealthMonitor.start(m, new ClusterHealthPlugin())
        long early = HealthMonitor.@loadedAt - 60_000L
        HealthMonitor.sweep(m, new ClusterHealthPlugin(), early)

        when:
        clusters = []
        (1..5).each { HealthMonitor.sweep(m, new ClusterHealthPlugin(), early) }

        then:
        cleared == []
        HealthMonitor.@MONITORED.containsKey(id)
    }

    def 'an alarm clean-up that fails is tried again on the next sweep, with nothing forgotten meanwhile'() {
        given:
        clusters = clusters.take(2)
        Long stays = clusters[0].id
        Long goes = clusters[1].id
        OperationNotification one = alarm(goes)
        alarms.add(one)
        MorpheusContext m = morpheus()
        HealthMonitor.start(m, new ClusterHealthPlugin())
        long early = HealthMonitor.@loadedAt - 60_000L
        HealthMonitor.sweep(m, new ClusterHealthPlugin(), early)
        HealthMonitor.LAST[goes] = [at: System.currentTimeMillis(), overall: 'CRITICAL']
        clusters = clusters.take(1)
        HealthMonitor.sweep(m, new ClusterHealthPlugin(), early)
        HealthMonitor.sweep(m, new ClusterHealthPlugin(), early)

        when: 'the sweep whose turn it is cannot list them'
        listFails = true
        HealthMonitor.sweep(m, new ClusterHealthPlugin(), early)

        then:
        noExceptionThrown()
        cleared == []
        HealthMonitor.LAST.containsKey(goes)

        when:
        listFails = false
        HealthMonitor.sweep(m, new ClusterHealthPlugin(), early)

        then:
        cleared == [one]
        !HealthMonitor.LAST.containsKey(goes)
    }

    def 'a sweep that fails does not count as one, so the status URL goes stale rather than vouch for it'() {
        given:
        clusters = clusters.take(1)
        MorpheusContext m = morpheus()
        HealthMonitor.start(m, new ClusterHealthPlugin())
        long early = HealthMonitor.@loadedAt - 60_000L
        HealthMonitor.sweep(m, new ClusterHealthPlugin(), early)

        when:
        clusterFails = true
        HealthMonitor.sweep(m, new ClusterHealthPlugin(), early + 60_000L)

        then:
        thrown(RuntimeException)
        HealthMonitor.@lastSweepAt == early
    }

    // ------------------------------------------------------------------ the PRB-04 alarm

    private static final Map BEHIND = [behind: true, mostOverdueMs: 12 * MIN, waiting: 40]
    private static final Map FINE = [behind: false, mostOverdueMs: 0L, waiting: 0]

    def 'the alarm is raised once, on the master tenant, says only numbers, and clears itself'() {
        given:
        MorpheusContext m = morpheus()
        HealthMonitor.start(m, new ClusterHealthPlugin())

        when:
        HealthMonitor.raiseKeepingUp(m, BEHIND)
        HealthMonitor.raiseKeepingUp(m, BEHIND)
        HealthMonitor.raiseKeepingUp(m, BEHIND)

        then:
        created.size() == 1
        created[0].account.masterAccount
        created[0].uniqueId == HealthMonitor.KEEPING_UP_ALARM
        created[0].name.startsWith('PRB-04')
        created[0].status == 'warning'
        created[0].statusMessage.contains('12 min')
        !created[0].statusMessage.contains('cluster-')
        !created[0].statusMessage.contains('Acme')

        when:
        HealthMonitor.raiseKeepingUp(m, FINE)
        HealthMonitor.raiseKeepingUp(m, FINE)

        then:
        cleared == [created[0]]
    }

    def 'a freeze watch that has stopped raises PRB-04 under its own words, and clears with it'() {
        given:
        MorpheusContext m = morpheus()
        HealthMonitor.start(m, new ClusterHealthPlugin())

        when:
        HealthMonitor.raiseKeepingUp(m, FINE + [watchStopped: true])
        HealthMonitor.raiseKeepingUp(m, FINE + [watchStopped: true])

        then:
        created.size() == 1
        created[0].uniqueId == HealthMonitor.KEEPING_UP_ALARM
        created[0].statusMessage.contains('freeze watch has stopped reading')
        !created[0].statusMessage.contains('overdue')

        when:
        HealthMonitor.raiseKeepingUp(m, FINE + [watchStopped: false])

        then:
        cleared == created
    }

    def 'the sweep carries it: with the watch refused every thread, /alive reads STALE and PRB-04 is raised, on no check falling behind'() {
        given:
        clusters = clusters.take(1)
        MorpheusContext m = morpheus()
        HealthMonitor.start(m, new ClusterHealthPlugin())
        long now = HealthMonitor.@loadedAt - 60_000L
        HealthMonitor.sweep(m, new ClusterHealthPlugin(), now)

        expect:
        HealthMonitor.statusAnswer('/alive', now)[0] == 200
        created == []

        when:
        FreezeWatch.@refusedSince = now - FreezeWatch.STOPPED_MS
        HealthMonitor.sweep(m, new ClusterHealthPlugin(), now)

        then:
        HealthMonitor.statusAnswer('/alive', now) == [503, 'HVM-HEALTH STALE\n']
        HealthMonitor.@pace.behind == false
        created*.uniqueId == [HealthMonitor.KEEPING_UP_ALARM]

        when: 'a read starts again'
        FreezeWatch.@refusedSince = null
        HealthMonitor.sweep(m, new ClusterHealthPlugin(), now)

        then:
        HealthMonitor.statusAnswer('/alive', now)[0] == 200
        cleared*.uniqueId == [HealthMonitor.KEEPING_UP_ALARM]
    }

    def 'the daily clean-up does not take PRB-04 for an alarm of a cluster that has gone'() {
        given:
        alarms.add(new OperationNotification(uniqueId: HealthMonitor.KEEPING_UP_ALARM, status: 'warning', active: true))

        when:
        HealthMonitor.clearOrphanAlarms(morpheus(), true)

        then:
        cleared == []
    }

    def 'an alarm left open by the last load is found: cleared when all is well, and not raised twice when it is not'() {
        given:
        OperationNotification left = new OperationNotification(uniqueId: HealthMonitor.KEEPING_UP_ALARM, status: 'warning', active: true)
        MorpheusContext m = morpheus()

        when:
        alarms.add(left)
        HealthMonitor.start(m, new ClusterHealthPlugin())
        HealthMonitor.raiseKeepingUp(m, BEHIND)

        then:
        created == []

        when:
        HealthMonitor.start(m, new ClusterHealthPlugin())
        HealthMonitor.raiseKeepingUp(m, FINE)

        then:
        cleared == [left]
    }

    def 'with the database touched only on a change, a healthy appliance reads alarms once for this, after a load'() {
        given:
        MorpheusContext m = morpheus()
        HealthMonitor.start(m, new ClusterHealthPlugin())
        def calls = new AtomicInteger()
        alarms = new ArrayList<OperationNotification>() {
            @Override Object[] toArray() { calls.incrementAndGet(); super.toArray() }
        }

        when:
        (1..50).each { HealthMonitor.raiseKeepingUp(m, FINE) }

        then:
        calls.get() <= 1
    }

    // ------------------------------------------------------------------ the route

    def 'the monitor state, for the all-clusters page, is numbers and no names'() {
        given:
        MorpheusContext m = morpheus()
        clusters = clusters.take(3)
        HealthMonitor.start(m, new ClusterHealthPlugin())
        HealthMonitor.sweep(m, new ClusterHealthPlugin(), HealthMonitor.@loadedAt - 60_000L)

        when:
        Map state = HealthMonitor.monitorState()
        String json = groovy.json.JsonOutput.toJson(state)

        then:
        state.pace.clusters == 3
        state.pace.behind == false
        state.checkers == HealthMonitor.DEFAULT_CHECK_CONCURRENCY
        state.watch.on == true
        state.watch.realSeconds == 60L
        state.statusPort == 0
        !json.contains('cluster-')
        !json.contains('Acme')
    }
}
