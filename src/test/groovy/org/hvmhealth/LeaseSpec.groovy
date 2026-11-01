package org.hvmhealth

import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.MorpheusServices
import com.morpheusdata.core.synchronous.MorpheusSynchronousOperationDataService
import com.morpheusdata.model.Account
import com.morpheusdata.model.ComputeServer
import com.morpheusdata.model.ComputeServerGroup
import com.morpheusdata.model.ComputeServerGroupType
import com.morpheusdata.model.OperationData
import com.morpheusdata.model.OperationNotification
import com.morpheusdata.model.TaskResult
import com.morpheusdata.model.User
import com.morpheusdata.response.ServiceResponse
import com.morpheusdata.views.ViewModel
import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import io.reactivex.rxjava3.core.Single
import org.hvmhealth.checks.HealthChecker
import org.hvmhealth.morpheus.Availability
import org.hvmhealth.morpheus.CapacityTrend
import org.hvmhealth.morpheus.FindingHistory
import org.hvmhealth.morpheus.FleetCorrelator
import org.hvmhealth.morpheus.FreezeWatch
import org.hvmhealth.morpheus.HealthContext
import org.hvmhealth.morpheus.HealthController
import org.hvmhealth.morpheus.HealthMonitor
import org.hvmhealth.morpheus.Lease
import org.hvmhealth.morpheus.PluginRow
import org.hvmhealth.morpheus.StatusAnswer
import spock.lang.Specification
import spock.util.concurrent.PollingConditions

import java.util.concurrent.atomic.AtomicInteger

/**
 * One appliance node runs the checks. Two nodes are played in one JVM, by changing which name this one goes by and starting the monitor again, against one store they share.
 */
class LeaseSpec extends Specification {
    static final long MIN = 60_000L
    static final long SEC = 1000L
    static final String A = 'node-a.invalid'
    static final String B = 'node-b.invalid'
    static final Account MASTER = new Account(id: 1L, name: 'Master', masterAccount: true)
    static final Account ACME = new Account(id: 2L, name: 'Acme')
    static final String WORDS_ONLY = /\AHVM-HEALTH (ALIVE|OK|WARNING|CRITICAL|STALE|UNKNOWN)\n(clusters=\d+ critical=\d+ warning=\d+ stale=\d+ held=\d+ reloading=\d+\n|reloading\n)?\z/

    /** A store that can have something happen between a write and its read back. */
    static class RacingStore extends FleetTimeSpec.Store {
        Closure onGet

        @Override
        MorpheusSynchronousOperationDataService service() {
            def base = super.service()
            [list: { q -> base.list(q) }, create: { OperationData o -> base.create(o) }, remove: { OperationData o -> base.remove(o) },
             get: { Long id -> Closure c = onGet; onGet = null; c?.call(); base.get(id) }] as MorpheusSynchronousOperationDataService
        }
    }

    RacingStore store = new RacingStore()
    List<ComputeServerGroup> clusters = [new ComputeServerGroup(id: 27L, name: 'cluster-x', owner: MASTER, type: new ComputeServerGroupType(code: 'mvm-cluster')),
                                         new ComputeServerGroup(id: 28L, name: 'cluster-y', owner: MASTER, type: new ComputeServerGroupType(code: 'mvm-cluster'))]
    String settings = '{"monitorMinutes":"5"}'
    String sample = Fixture.text('probe-healthy').replace(' 5    vm-one   running', '')
    AtomicInteger clusterLists = new AtomicInteger()
    AtomicInteger hostLists = new AtomicInteger()
    AtomicInteger agentCalls = new AtomicInteger()
    List<OperationNotification> created = Collections.synchronizedList([])

    private MorpheusContext morpheus() {
        LeaseSpec self = this
        def svc = store.service()
        def balancer = Stub(MorpheusContext.getMethod('getAsync').returnType.getMethod('getLoadBalancer').returnType) {
            getAccountById(_) >> { Long id -> id == 1L ? Single.just(MASTER) : Single.just(ACME) }
        }
        def async = Stub(MorpheusContext.getMethod('getAsync').returnType) { getLoadBalancer() >> balancer }
        def clusterService = Stub(MorpheusServices.getMethod('getCluster').returnType) {
            list(_) >> { self.clusterLists.incrementAndGet(); self.clusters }
            get(_) >> { Long id -> self.clusters.find { it.id == id } }
        }
        def servers = Stub(MorpheusServices.getMethod('getComputeServer').returnType) {
            list(_) >> { self.hostLists.incrementAndGet(); [new ComputeServer(id: 1L, name: 'hvm-node01')] }
        }
        def notifications = Stub(MorpheusServices.getMethod('getOperationNotification').returnType) {
            list(_) >> { [] }
            createAlarm(_) >> { args -> self.created << args[0]; ServiceResponse.success() }
            clearAlarm(_) >> ServiceResponse.success()
        }
        def auditLog = Stub(MorpheusServices.getMethod('getAdmin').returnType.getMethod('getAuditLog').returnType) { log(_, _) >> { args -> args[1] } }
        def admin = Stub(MorpheusServices.getMethod('getAdmin').returnType) { getAuditLog() >> auditLog }
        def services = Stub(MorpheusServices) {
            getOperationData() >> svc
            getCluster() >> clusterService
            getComputeServer() >> servers
            getOperationNotification() >> notifications
            getAdmin() >> admin
        }
        Stub(MorpheusContext) {
            getServices() >> services
            getAsync() >> async
            getSettings(_) >> { Single.just(self.settings) }
            executeCommandOnServer(_, _) >> { args ->
                self.agentCalls.incrementAndGet()
                Single.just(new TaskResult(success: true, exitCode: '0', output: self.sample))
            }
        }
    }

    def setup() {
        Lease.node = A
        Lease.forget()
        FleetCorrelator.forget(0L)
        Availability.forget()
        CapacityTrend.forget()
    }

    def cleanup() {
        HealthMonitor.stop()
        Lease.node = null
        Lease.forget()
        HealthMonitor.@standby = false
        clusters.each { HealthMonitor.LAST.remove(it.id); HealthMonitor.@LAST_FULL.remove(it.id) }
        FreezeWatch.FROZEN_SINCE.clear()
        FleetCorrelator.forget(System.currentTimeMillis())
        Availability.forget()
        CapacityTrend.forget()
    }

    private List<OperationData> leaseRows() {
        store.rows.findAll { it.category == Lease.CATEGORY }
    }

    private Map row() {
        new JsonSlurper().parseText(leaseRows()[-1].rawData) as Map
    }

    /** A row as another node would have left it. */
    private void rowFrom(MorpheusContext m, String node, long at, Map summary = [:]) {
        PluginRow.replace(m, Lease.CATEGORY, 'test', 0L, JsonOutput.toJson([node: node, at: at, summary: summary]), at)
    }

    private static Map state(Map<Long, Map> views, long now, Map over = [:]) {
        [running: true, startedAt: now - 60 * MIN, loadedAt: now - 60 * MIN, lastSweepAt: now - MIN, quickMs: 5 * MIN, behind: false, ids: views.keySet(), views: views] + over
    }

    private static Map view(String overall, long at) {
        [at: at, overall: overall, hosts: [], results: []]
    }

    /** As a node standing by would read it: through JSON. */
    private static Map through(Map s, long at) {
        new JsonSlurper().parseText(JsonOutput.toJson([node: A, at: at, summary: StatusAnswer.summary(s, at)])) as Map
    }

    // ------------------------------------------------------------------ one node

    def 'a single node takes the row, replaces it on every sweep, leaves one row, and stays as it was'() {
        given:
        MorpheusContext m = morpheus()
        HealthMonitor.start(m, new ClusterHealthPlugin())
        long started = HealthMonitor.@startedAt
        long t = HealthMonitor.@loadedAt - 60 * SEC
        HealthMonitor.LAST[27L] = view('HEALTHY', t)

        when:
        (0..2).each { HealthMonitor.sweep(m, new ClusterHealthPlugin(), t + it * MIN) }

        then:
        !HealthMonitor.standingBy()
        leaseRows().size() == 1
        row().node == A
        row().at == t + 2 * MIN
        clusterLists.get() >= 3
        HealthMonitor.@startedAt == started
        HealthMonitor.LAST[27L].at == t
        HealthMonitor.@MONITORED.keySet() == [27L, 28L] as Set
        FreezeWatch.@ticker != null
    }

    def 'its own row is its own, however old: a node that was stopped for ten minutes just carries on'() {
        given:
        MorpheusContext m = morpheus()
        rowFrom(m, A, 1_000L)
        HealthMonitor.start(m, new ClusterHealthPlugin())
        long t = HealthMonitor.@loadedAt - 60 * SEC

        when:
        HealthMonitor.sweep(m, new ClusterHealthPlugin(), t)

        then:
        !HealthMonitor.standingBy()
        row().at == t
    }

    // ------------------------------------------------------------------ a node that does not hold it

    def 'a node standing by lists no cluster and reads no host, however overdue they are, and raises no alarm'() {
        given:
        MorpheusContext m = morpheus()
        Lease.node = B
        HealthMonitor.start(m, new ClusterHealthPlugin())
        long t = HealthMonitor.@loadedAt + 10 * MIN
        rowFrom(m, A, t)
        clusterLists.set(0)

        when:
        HealthMonitor.sweep(m, new ClusterHealthPlugin(), t + MIN)
        HealthMonitor.sweep(m, new ClusterHealthPlugin(), t + 2 * MIN)
        Thread.sleep(FreezeWatch.TICK_MS + 500L)

        then:
        HealthMonitor.standingBy()
        clusterLists.get() == 0
        hostLists.get() == 0
        agentCalls.get() == 0
        created == []
        HealthMonitor.@checkers.queue.isEmpty()
        FreezeWatch.@ticker == null
        FreezeWatch.summary() == [on: false]

        and: 'the row is left as the holder wrote it'
        leaseRows().size() == 1
        row().node == A
        row().at == t
    }

    def 'nor does a tick reach a host, if one should come while it stands by'() {
        given:
        MorpheusContext m = morpheus()
        Lease.node = B
        HealthMonitor.start(m, new ClusterHealthPlugin())
        long t = HealthMonitor.@loadedAt
        rowFrom(m, A, t)
        FreezeWatch.Schedule s = FreezeWatch.@schedule

        when:
        HealthMonitor.sweep(m, new ClusterHealthPlugin(), t + MIN)

        then:
        s.seconds == 0L
        s.pick(t + 10 * MIN, 8).isEmpty()
        agentCalls.get() == 0
    }

    def 'when the holder stops, another takes over once the row is older than the limit, and not before'() {
        given:
        MorpheusContext m = morpheus()
        Lease.node = B
        HealthMonitor.start(m, new ClusterHealthPlugin())
        long t = System.currentTimeMillis()
        rowFrom(m, A, t)

        when: 'still within the limit, and at it exactly'
        HealthMonitor.sweep(m, new ClusterHealthPlugin(), t + MIN)
        HealthMonitor.sweep(m, new ClusterHealthPlugin(), t + Lease.LIMIT_MS)

        then:
        HealthMonitor.standingBy()
        row().node == A
        agentCalls.get() == 0

        when: 'the holder has stopped writing, and the limit passes'
        long taken = t + Lease.LIMIT_MS + SEC
        HealthMonitor.sweep(m, new ClusterHealthPlugin(), taken)

        then: 'it takes the row, and starts as a load does'
        !HealthMonitor.standingBy()
        row().node == B
        row().at == taken
        leaseRows().size() == 1
        HealthMonitor.@startedAt == taken
        HealthMonitor.@loadedAt == taken
        HealthMonitor.@MONITORED.keySet() == [27L, 28L] as Set
        FreezeWatch.@ticker != null
        FreezeWatch.summary().on == true

        and: 'what it wrote, and what it says, is reloading and not stale'
        (row().summary as Map).other == 'RELOADING'
        HealthMonitor.statusAnswer('/cluster/27', taken) == [200, 'HVM-HEALTH OK\nreloading\n']

        when: 'its turns come round: the first check of each is a full one, and its probes run'
        HealthMonitor.sweep(m, new ClusterHealthPlugin(), taken + 6 * MIN)

        then:
        new PollingConditions(timeout: 30).eventually { assert clusters.every { HealthMonitor.LAST[it.id]?.mode == 'full' } }
        agentCalls.get() > 0
    }

    def 'losing the lease stops it acting at once: nothing waiting starts, and nothing held stands'() {
        given:
        MorpheusContext m = morpheus()
        HealthMonitor.start(m, new ClusterHealthPlugin())
        long t = HealthMonitor.@loadedAt - 60 * SEC
        HealthMonitor.sweep(m, new ClusterHealthPlugin(), t)
        HealthMonitor.LAST[27L] = view('CRITICAL', t)
        HealthMonitor.@LAST_FULL[27L] = []
        FreezeWatch.FROZEN_SINCE[27L] = t
        rowFrom(m, B, t + SEC)
        HealthMonitor.@QUEUED[28L] = new HealthMonitor.CheckTask(1, 28L, { throw new IllegalStateException('must not run') } as Runnable)
        HealthMonitor.@CHECKING << 28L

        when:
        HealthMonitor.sweep(m, new ClusterHealthPlugin(), t + MIN)

        then:
        HealthMonitor.standingBy()
        !HealthMonitor.LAST.containsKey(27L)
        !HealthMonitor.@LAST_FULL.containsKey(27L)
        FreezeWatch.FROZEN_SINCE.isEmpty()
        HealthMonitor.@QUEUED.isEmpty()
        !HealthMonitor.@CHECKING.contains(28L)
        HealthMonitor.@MONITORED.isEmpty()
        FreezeWatch.@ticker == null
        row().node == B
    }

    def 'a check that was waiting when the lease was lost does nothing if it gets a thread after all'() {
        given:
        MorpheusContext m = morpheus()
        HealthMonitor.start(m, new ClusterHealthPlugin())
        HealthMonitor.@standby = true

        when:
        HealthMonitor.enqueue(m, clusters[0], [:], 'quick', 1)
        new PollingConditions(timeout: 5).eventually { assert HealthMonitor.@CHECKING.isEmpty() }

        then:
        agentCalls.get() == 0
    }

    // ------------------------------------------------------------------ the race, and what cannot be read

    def 'another node writing between my write and my read back wins: I do not act on that sweep'() {
        given:
        MorpheusContext m = morpheus()
        long t = 1_800_000_000_000L
        store.onGet = { store.rows << new OperationData(id: 999L, category: Lease.CATEGORY, refId: '0', rawData: JsonOutput.toJson([node: B, at: t + 1, summary: [:]])) }

        expect:
        !Lease.decide(m, { [:] }, t)
        Lease.row.node == B
    }

    def 'two nodes that both acted leave two rows, and the next read settles it for both, leaving one'() {
        given:
        MorpheusContext m = morpheus()
        long t = 1_800_000_000_000L
        store.rows << new OperationData(id: 1L, category: Lease.CATEGORY, refId: '0', rawData: JsonOutput.toJson([node: A, at: t, summary: [:]]))
        store.rows << new OperationData(id: 2L, category: Lease.CATEGORY, refId: '0', rawData: JsonOutput.toJson([node: B, at: t + 1, summary: [:]]))
        store.next = 10

        expect: 'the older one stands down, without touching a row'
        !Lease.decide(m, { [:] }, t + MIN)
        leaseRows().size() == 2

        when:
        Lease.node = B
        boolean acts = Lease.decide(m, { [:] }, t + MIN)

        then:
        acts
        leaseRows().size() == 1
        row().node == B
    }

    def 'a row that cannot be read leaves it running, whichever node it is'() {
        given:
        MorpheusContext m = morpheus()
        Lease.node = B
        HealthMonitor.start(m, new ClusterHealthPlugin())
        long t = HealthMonitor.@loadedAt
        rowFrom(m, A, t)
        HealthMonitor.sweep(m, new ClusterHealthPlugin(), t + MIN)
        assert HealthMonitor.standingBy()
        clusterLists.set(0)

        when:
        store.failReads = true
        HealthMonitor.sweep(m, new ClusterHealthPlugin(), t + 2 * MIN)

        then:
        !HealthMonitor.standingBy()
        clusterLists.get() >= 1
        Lease.row == null
        HealthMonitor.monitorState().lease == [node: B, holder: null, standingBy: false]

        when: 'it can be read again, and the holder is still there'
        store.failReads = false
        rowFrom(m, A, t + 2 * MIN)
        HealthMonitor.sweep(m, new ClusterHealthPlugin(), t + 3 * MIN)

        then:
        HealthMonitor.standingBy()
    }

    def 'a row that cannot be written leaves it running too'() {
        given:
        MorpheusContext m = morpheus()
        long t = 1_800_000_000_000L
        store.failCreates = true

        expect:
        Lease.decide(m, { [:] }, t)
    }

    def 'clocks: a live holder ahead by clock skew still holds; only an absurd future date or a stale past is taken over, and a row with no date never held'() {
        given:
        MorpheusContext m = morpheus()
        long t = 1_800_000_000_000L

        when: 'B wrote its row dated within the limit in the future (its clock is a touch ahead)'
        rowFrom(m, B, t + Lease.LIMIT_MS)

        then: 'this node stands by'
        !Lease.decide(m, { [:] }, t)

        when: 'B is dated well ahead - minutes of clock skew, but still a live holder renewing every sweep'
        store.rows.clear()
        rowFrom(m, B, t + Lease.LIMIT_MS + 10 * MIN)

        then: 'this node still stands by: skew no longer reads as an expired lease (no double-run)'
        !Lease.decide(m, { [:] }, t)

        when: 'B is dated absurdly far ahead - a dead holder with a broken clock'
        store.rows.clear()
        rowFrom(m, B, t + Lease.FUTURE_SKEW_MS + MIN)

        then: 'this node takes over'
        Lease.decide(m, { [:] }, t)

        when: 'B is stale in the past, beyond the limit'
        store.rows.clear()
        rowFrom(m, B, t - Lease.LIMIT_MS - SEC)

        then: 'this node takes over'
        Lease.decide(m, { [:] }, t)

        and: 'a row with no date never held anything'
        Lease.age([node: B], t) == Long.MAX_VALUE
    }

    def 'the row is not an orphan: the daily clean-up leaves it however old, as it does no other row it does not know'() {
        given:
        MorpheusContext m = morpheus()
        long now = 1_800_000_000_000L
        long old = now - FindingHistory.RETENTION_MS - 24 * 60 * MIN
        store.rows << new OperationData(id: 1L, category: Lease.CATEGORY, refId: '0', refDate: new Date(old), rawData: JsonOutput.toJson([node: A, at: old]))
        store.rows << new OperationData(id: 2L, category: FindingHistory.CATEGORY, refId: '999', refDate: new Date(old), rawData: '{}')
        FindingHistory.lastPrune = 0

        when:
        FindingHistory.pruneOrphans(m, now)

        then:
        store.rows*.id == [1L]

        cleanup:
        FindingHistory.lastPrune = 0
    }

    // ------------------------------------------------------------------ what a node standing by says

    def 'the row holds what the status URL says, under the cap at 200 clusters, even with the longest ids and words'() {
        given:
        long now = 1_800_000_000_000L
        Map<Long, Map> views = (1..200).collectEntries { [(100_000_000_000_000_000L + it): null] }
        Map s = state(views, now, [startedAt: now - MIN, loadedAt: now - MIN])

        when:
        Map doc = [node: 'a-long-appliance-node-name.a-long-domain.example.invalid', at: now, summary: StatusAnswer.summary(s, now) + [monitor: [pace: [clusters: 200, waiting: 200, checksOff: false, roundMs: 3_600_000L, mostOverdueMs: 3_600_000L, behind: true, watchStopped: true], checkers: 64, watch: [on: true, seconds: 60L, realSeconds: 100L, clusters: 200, budget: 120, oldestReadSec: 100L]]]]
        int bytes = JsonOutput.toJson(doc).getBytes('UTF-8').length

        then:
        (doc.summary.clusters as Map).size() == 200
        (doc.summary.clusters as Map).values().every { it == 'RELOADING' }
        bytes < FindingHistory.MAX_BYTES
        bytes < 10_000   // so there is room to spare, and it is not one more thing to prune
    }

    def 'and it fits through the real sweep, as stored, with the 200 clusters listed'() {
        given:
        clusters = (1..200).collect { new ComputeServerGroup(id: 1000L + it, name: "cluster-${it}".toString(), owner: MASTER, type: new ComputeServerGroupType(code: 'mvm-cluster')) }
        MorpheusContext m = morpheus()
        HealthMonitor.start(m, new ClusterHealthPlugin())
        long t = HealthMonitor.@loadedAt - 60 * SEC

        when:
        HealthMonitor.sweep(m, new ClusterHealthPlugin(), t)
        HealthMonitor.sweep(m, new ClusterHealthPlugin(), t + 1)

        then:
        (row().summary.clusters as Map).size() == 200
        leaseRows()[-1].rawData.getBytes('UTF-8').length < FindingHistory.MAX_BYTES
    }

    def 'a node standing by says what the holder would, for #why'() {
        given:
        long now = 1_800_000_000_000L
        Map s = state(views, now, over)
        Map row = through(s, now)

        expect:
        paths.every { StatusAnswer.fromRow(it, row, now) == StatusAnswer.answer(it, s, now) }

        where:
        why                       | views                                                                          | over
        'all healthy'             | [7L: view('HEALTHY', 1_800_000_000_000L - MIN)]                                | [:]
        'a warning'               | [7L: view('WARNING', 1_800_000_000_000L - MIN)]                                | [:]
        'a critical cluster'      | [7L: view('CRITICAL', 1_800_000_000_000L - MIN)]                               | [:]
        'one out of date'         | [7L: view('HEALTHY', 1_800_000_000_000L - 60 * MIN)]                           | [:]
        'one not checked'         | [7L: null]                                                                     | [:]
        'just after a load'       | [7L: null]                                                                     | [startedAt: 1_800_000_000_000L - MIN, loadedAt: 1_800_000_000_000L - MIN]
        'before the first sweep'  | [:]                                                                            | [startedAt: 1_800_000_000_000L - MIN, loadedAt: 1_800_000_000_000L + MIN, lastSweepAt: null, quickMs: 0L]
        'checks off'              | [7L: view('HEALTHY', 1_800_000_000_000L - MIN)]                                | [quickMs: 0L]
        'checks behind'           | [7L: view('HEALTHY', 1_800_000_000_000L - MIN)]                                | [behind: true]

        paths = ['/alive', '/status', '/cluster/7', '/cluster/8', '/', '/cluster/x', '/status/x']
    }

    def 'a freeze the holder knows of reaches a node standing by as critical'() {
        given:
        long now = 1_800_000_000_000L
        FreezeWatch.FROZEN_SINCE[7L] = now - 5000L
        Map row = through(state([7L: view('HEALTHY', now - MIN)], now), now)
        FreezeWatch.FROZEN_SINCE.clear()

        expect:
        StatusAnswer.fromRow('/cluster/7', row, now) == [503, 'HVM-HEALTH CRITICAL\n']
        (StatusAnswer.fromRow('/status', row, now)[1] as String).startsWith('HVM-HEALTH CRITICAL\nclusters=1 critical=1')
    }

    def 'a row older than the limit the holder itself uses reads STALE on all three, and at that limit does not'() {
        given:
        long now = 1_800_000_000_000L
        Map row = through(state([7L: view('HEALTHY', now - MIN)], now), now)
        long limit = StatusAnswer.SWEEP_LATE_MS

        expect: 'the same one: a holder whose sweep has not run for that long reads STALE too'
        StatusAnswer.answer('/alive', state([7L: view('HEALTHY', now - MIN)], now, [lastSweepAt: now - limit - 1]), now) == [503, StatusAnswer.STALE]
        StatusAnswer.answer('/alive', state([7L: view('HEALTHY', now - MIN)], now, [lastSweepAt: now - limit]), now) == [200, StatusAnswer.ALIVE]

        and:
        StatusAnswer.fromRow('/alive', row, now + limit) == [200, StatusAnswer.ALIVE]
        StatusAnswer.fromRow('/status', row, now + limit)[0] == 200
        StatusAnswer.fromRow('/cluster/7', row, now + limit) == [200, StatusAnswer.OK]

        and:
        ['/alive', '/status', '/cluster/7', '/cluster/8'].every { StatusAnswer.fromRow(it, row, now + limit + 1) == [503, StatusAnswer.STALE] }

        and: 'what is not a route stays that, however old'
        StatusAnswer.fromRow('/x', row, now + limit + 1) == [404, StatusAnswer.UNKNOWN]
    }

    def 'with no row at all, or a row from a version that says something else, it reads STALE'() {
        given:
        long now = 1_800_000_000_000L

        expect:
        ['/alive', '/status', '/cluster/7'].every { StatusAnswer.fromRow(it, null, now) == [503, StatusAnswer.STALE] }
        ['/alive', '/status', '/cluster/7'].every { StatusAnswer.fromRow(it, [node: A, at: now], now) == [503, StatusAnswer.STALE] }
        StatusAnswer.fromRow('/cluster/7', [node: A, at: now, summary: [clusters: ['7': 'SOMETHING-NEW']]], now) == [503, StatusAnswer.STALE]
    }

    def 'whatever it says holds only the words, and never the name of a node, however the row reads'() {
        given:
        long now = 1_800_000_000_000L
        Map row = through(state([7L: view('CRITICAL', now - MIN)], now), now)
        row.node = 'secret-appliance.invalid'

        when:
        List<List> all = ['/alive', '/status', '/cluster/7', '/cluster/8', '/x'].collect { StatusAnswer.fromRow(it, row, now) }

        then:
        all.every { (it[1] as String) ==~ WORDS_ONLY }
        !JsonOutput.toJson(all).contains('secret')
    }

    def 'through the monitor: a node standing by answers from the row and not from what it holds'() {
        given:
        MorpheusContext m = morpheus()
        Lease.node = B
        HealthMonitor.start(m, new ClusterHealthPlugin())
        long t = HealthMonitor.@loadedAt
        rowFrom(m, A, t, through(state([27L: view('WARNING', t - MIN)], t), t).summary as Map)

        when:
        HealthMonitor.sweep(m, new ClusterHealthPlugin(), t + MIN)
        HealthMonitor.LAST[27L] = view('CRITICAL', t + MIN)

        then:
        HealthMonitor.statusAnswer('/cluster/27', t + MIN) == [200, StatusAnswer.WARNING]
        HealthMonitor.statusAnswer('/alive', t + MIN) == [200, StatusAnswer.ALIVE]
        HealthMonitor.statusAnswer('/cluster/27', t + StatusAnswer.SWEEP_LATE_MS + 1) == [503, StatusAnswer.STALE]
    }

    // ------------------------------------------------------------------ a person on a node standing by

    private static ViewModel<Map> asUser(Account a, Map params = [clusterId: '27']) {
        new ViewModel<Map>(object: params, user: new User(id: 7L, username: 'someone', account: a))
    }

    def 'pressing Check on a node standing by still works here, and raises no alarm, tells no watch and writes no figures'() {
        given:
        MorpheusContext m = morpheus()
        Lease.node = B
        HealthMonitor.start(m, new ClusterHealthPlugin())
        long t = HealthMonitor.@loadedAt
        rowFrom(m, A, t)
        HealthMonitor.sweep(m, new ClusterHealthPlugin(), t + MIN)
        settings = '{"monitorMinutes":"5","fullCheckHours":"24"}'
        sample = Fixture.text('probe-frozen')

        when:
        Map v = HealthMonitor.run(m, new HealthContext(m, 27L), [:], 'full', false)

        then: 'it ran, and the tab has it'
        agentCalls.get() > 0
        v.results
        HealthMonitor.LAST[27L].mode == 'full'

        and: 'the rest stays with the holder'
        created == []
        FreezeWatch.FROZEN_SINCE.isEmpty()
        store.rows.count { it.category == Availability.CATEGORY } == 0
        HealthMonitor.standingBy()
    }

    def 'its figures are the row as it is now, never a copy it read earlier'() {
        given:
        MorpheusContext m = morpheus()
        Lease.node = B
        HealthMonitor.start(m, new ClusterHealthPlugin())
        long t = HealthMonitor.@loadedAt
        rowFrom(m, A, t)
        HealthMonitor.sweep(m, new ClusterHealthPlugin(), t + MIN)
        PluginRow.replace(m, Availability.CATEGORY, 'test', 27L, JsonOutput.toJson([v: 1, since: 1L, savedAt: 1L, months: [:], stops: [:], tag: 'first']), 1L)

        expect:
        Availability.doc(m, 27L).tag == 'first'

        when:
        PluginRow.replace(m, Availability.CATEGORY, 'test', 27L, JsonOutput.toJson([v: 1, since: 1L, savedAt: 2L, months: [:], stops: [:], tag: 'second']), 2L)

        then:
        Availability.doc(m, 27L).tag == 'second'
    }

    def 'the tab on a node standing by is told so, and gets the last stored summary, the fallback that already exists'() {
        given:
        MorpheusContext m = morpheus()
        Lease.node = B
        HealthMonitor.start(m, new ClusterHealthPlugin())
        long t = HealthMonitor.@loadedAt
        rowFrom(m, A, t)
        HealthMonitor.sweep(m, new ClusterHealthPlugin(), t + MIN)
        PluginRow.replace(m, FindingHistory.CATEGORY, 'test', 27L, JsonOutput.toJson([v: 1, since: 1L, savedAt: 5L, episodes: [], summary: [overall: 'DEGRADED', at: t, layout: 'HVM 2.0', counts: [fail: 1, warn: 0, pass: 2], failing: [], warning: []]]), 5L)

        when:
        Map s = new HealthController(new ClusterHealthPlugin(), m).status(asUser(MASTER)).data.data as Map

        then:
        s.standingBy == true
        s.last == null
        s.known.overall == 'DEGRADED'
        !JsonOutput.toJson(s).contains(A)   // the tab goes to whoever runs the cluster, and the node's name is the master tenant's
    }

    def 'the all-clusters page shows who holds it, with the numbers that node wrote, and reads its rows in one query'() {
        given:
        MorpheusContext m = morpheus()
        Lease.node = B
        HealthMonitor.start(m, new ClusterHealthPlugin())
        long t = HealthMonitor.@loadedAt
        rowFrom(m, A, t, [monitor: [pace: [clusters: 200, mostOverdueMs: 60000, waiting: 3, behind: false], checkers: 20, watch: [on: true, realSeconds: 100]]])
        HealthMonitor.sweep(m, new ClusterHealthPlugin(), t + MIN)
        clusters.each { Long id = it.id; PluginRow.replace(m, FindingHistory.CATEGORY, 'test', id, JsonOutput.toJson([v: 1, savedAt: 5L, episodes: [], summary: [overall: 'WARNING', at: t, failing: [], warning: []]]), 5L + id) }
        def c = new HealthController(new ClusterHealthPlugin(), m)
        store.reads = 0

        when:
        Map state = c.monitor(asUser(MASTER, [:])).data.data as Map
        List<Map> rows = c.fleet(asUser(MASTER, [:])).data.data as List<Map>

        then:
        state.lease == [node: B, holder: A, standingBy: true]
        state.pace.clusters == 200
        state.checkers == 20
        state.watch.realSeconds == 100
        rows*.overall == ['WARNING', 'WARNING']
        rows.every { it.lastKnown && it.standingBy }
        store.reads == 1
    }

    def 'and on the node that holds it, that it is this one'() {
        given:
        MorpheusContext m = morpheus()
        HealthMonitor.start(m, new ClusterHealthPlugin())

        expect: 'nothing to say before the first sweep'
        HealthMonitor.monitorState().lease == [node: A, holder: null, standingBy: false]

        when:
        HealthMonitor.sweep(m, new ClusterHealthPlugin(), HealthMonitor.@loadedAt - 60 * SEC)

        then:
        HealthMonitor.monitorState().lease == [node: A, holder: A, standingBy: false]
    }

    def 'the page and the tab say so, in words'() {
        given:
        String pages = new File('src/assets/javascripts/health-pages.js').getText('UTF-8')
        String tab = new File('src/assets/javascripts/health-tab.js').getText('UTF-8')

        expect:
        pages.contains('l.holder')
        pages.contains('l.standingBy')
        pages.contains('c.standingBy')
        pages.contains('i.standingBy')
        tab.contains('s.standingBy')
    }

    def 'a node name is read once and kept in memory: no setting holds it'() {
        given:
        Lease.node = null

        expect:
        Lease.self()
        Lease.self().is(Lease.node)
        !new File('src/main/groovy/org/hvmhealth/ClusterHealthPlugin.groovy').getText('UTF-8').toLowerCase().contains('lease')
    }

    // ------------------------------------------------------------------ what a full check could not read

    private static HealthChecker.Result disk(String host) {
        new HealthChecker.Result('HW-07', 'Disk healthy (SMART): 3 on bus 0', HealthChecker.Severity.HIGH, HealthChecker.Status.FAIL, host, 'FAILED', 'healthy', 'plugin')
    }

    def 'a full check that could not read a host keeps what only a full check finds on it, as last read, into the quick checks after'() {
        given:
        MorpheusContext m = morpheus()
        HealthMonitor.start(m, new ClusterHealthPlugin())
        HealthMonitor.@LAST_FULL[27L] = [disk('hvm-node01')]
        sample = ''

        when: 'the host is silent for the full check'
        Map full = HealthMonitor.run(m, new HealthContext(m, 27L), [:], 'full', false)

        then:
        full.results.find { it.id == 'HW-07' && it.host == 'hvm-node01' }.observed.startsWith('as last read')
        full.results.find { it.id == 'HW-07' && it.host == 'hvm-node01' }.status == 'FAIL'

        when: 'and it answers the next quick one, which cannot judge it'
        sample = Fixture.text('probe-healthy')
        Map quick = HealthMonitor.run(m, new HealthContext(m, 27L), [:], 'quick', false)

        then:
        quick.results.find { it.id == 'HW-07' && it.host == 'hvm-node01' }.status == 'FAIL'
    }

    def 'the disk that was failing stands only while it cannot be read, and only for the host that could not'() {
        given:
        org.hvmhealth.model.HostFacts cut = new org.hvmhealth.model.HostFacts(complete: true, raidDisksUnread: 2)
        org.hvmhealth.model.HostFacts whole = new org.hvmhealth.model.HostFacts(complete: true)
        HealthChecker.Input in_ = Fixture.input([Fixture.host('a', cut), Fixture.host('b', whole), Fixture.host('c', new org.hvmhealth.model.HostFacts())], Fixture.LAYOUT_20)
        HealthChecker.Result other = new HealthChecker.Result('HST-02', 'x', HealthChecker.Severity.LOW, HealthChecker.Status.WARN, 'a', 'o', 'e', 'p')
        HealthChecker.Result silent = new HealthChecker.Result('HST-02', 'x', HealthChecker.Severity.LOW, HealthChecker.Status.WARN, 'c', 'o', 'e', 'p')
        HealthChecker.Result gone = disk('removed-host')

        when:
        List<HealthChecker.Result> kept = HealthMonitor.unreadFullOnly([disk('a'), disk('b'), other, silent, gone], in_, [])

        then:
        kept.collect { "${it.id}/${it.host}".toString() } == ['HW-07/a', 'HST-02/c']

        when: 'read again, the new answer stands, even a pass'
        List<HealthChecker.Result> read = [new HealthChecker.Result('HW-07', disk('a').title, HealthChecker.Severity.HIGH, HealthChecker.Status.PASS, 'a', 'ok', 'healthy', 'plugin')]

        then:
        HealthMonitor.unreadFullOnly([disk('a')], in_, read) == []

        and: 'as last read is said once'
        HealthMonitor.unreadFullOnly(kept, in_, [])[0].observed.count('as last read') == 1
    }
}
