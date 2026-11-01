package org.hvmhealth

import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.MorpheusServices
import com.morpheusdata.core.synchronous.MorpheusSynchronousOperationDataService
import com.morpheusdata.model.Account
import com.morpheusdata.model.ComputeServerGroup
import com.morpheusdata.model.ComputeServerGroupType
import com.morpheusdata.model.OperationData
import com.morpheusdata.model.OperationNotification
import com.morpheusdata.model.User
import com.morpheusdata.response.ServiceResponse
import com.morpheusdata.views.ViewModel
import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import io.reactivex.rxjava3.core.Single
import org.hvmhealth.checks.CheckCatalog
import org.hvmhealth.checks.HealthChecker
import org.hvmhealth.checks.HealthChecker.Severity
import org.hvmhealth.checks.HealthChecker.Status
import org.hvmhealth.model.HostFacts
import org.hvmhealth.morpheus.Availability
import org.hvmhealth.morpheus.CapacityTrend
import org.hvmhealth.morpheus.FindingHistory
import org.hvmhealth.morpheus.FleetCorrelator
import org.hvmhealth.morpheus.FreezeWatch
import org.hvmhealth.morpheus.HealthController
import org.hvmhealth.morpheus.HealthMonitor
import org.hvmhealth.morpheus.HealthReport
import spock.lang.Specification
import spock.lang.Unroll

/** Items 9 to 12: what shows across clusters, and over time. Master runs clusters with Acme, and Initech and Globex are customers on them. */
class FleetTimeSpec extends Specification {
    static final long T0 = 1_800_000_000_000L   // 2027-01-15
    static final long MIN = 60_000L
    static final long DAY = 24 * 60 * MIN
    static final long GIB = 1L << 30

    static final Account MASTER = new Account(id: 1L, name: 'Master', masterAccount: true)
    static final Account ACME = new Account(id: 2L, name: 'Acme', masterAccount: false)
    static final Account GLOBEX = new Account(id: 3L, name: 'Globex', masterAccount: false)
    static final Account INITECH = new Account(id: 4L, name: 'Initech', masterAccount: false)
    static final Map<Long, Account> ACCOUNTS = [1L: MASTER, 2L: ACME, 3L: GLOBEX, 4L: INITECH]

    def setup() {
        FleetCorrelator.forget(0L)
        Availability.forget()
        CapacityTrend.forget()
        FreezeWatch.FROZEN_SINCE.clear()
    }

    def cleanup() {
        FleetCorrelator.forget(System.currentTimeMillis())
        Availability.forget()
        CapacityTrend.forget()
        FreezeWatch.FROZEN_SINCE.clear()
        HealthMonitor.LAST.remove(27L)
    }

    // ------------------------------------------------------------------ a Morpheus that keeps rows and alarms

    static class Store {
        List<OperationData> rows = []
        long next = 1
        boolean failReads, failCreates
        int reads, creates

        MorpheusSynchronousOperationDataService service() {
            [list: { q -> reads++; if (failReads) { throw new IllegalStateException('database gone') }; new ArrayList(rows) },
             create: { OperationData o ->
                 if (failCreates) { throw new IllegalStateException('database gone') }
                 creates++
                 OperationData kept = new OperationData(id: next++, category: o.category, code: o.code, refId: o.refId, refType: o.refType, refDate: o.refDate, account: o.account, rawData: o.rawData)
                 rows << kept
                 kept
             },
             get: { Long id -> rows.find { it.id == id } },
             remove: { OperationData o -> rows.removeAll { it.id == o.id }; true }] as MorpheusSynchronousOperationDataService
        }
    }

    Store store = new Store()
    List<OperationNotification> alarms = []
    List<OperationNotification> created = []
    List<OperationNotification> cleared = []
    int alarmLists = 0
    List<ComputeServerGroup> clusters = [new ComputeServerGroup(id: 27L, name: 'cluster-x', owner: ACME, type: new ComputeServerGroupType(code: 'mvm-cluster'))]
    String settingsJson = '{}'

    private MorpheusContext morpheus() {
        FleetTimeSpec self = this
        def svc = store.service()
        def balancer = Stub(MorpheusContext.getMethod('getAsync').returnType.getMethod('getLoadBalancer').returnType) {
            getAccountById(_) >> { Long id -> ACCOUNTS[id] ? Single.just(ACCOUNTS[id]) : Single.error(new IllegalArgumentException('no such tenant')) }
        }
        def async = Stub(MorpheusContext.getMethod('getAsync').returnType) { getLoadBalancer() >> balancer }
        def clusterService = Stub(MorpheusServices.getMethod('getCluster').returnType) {
            get(_) >> { Long id -> self.clusters.find { it.id == id } }
            list(_) >> { self.clusters }
        }
        def notifications = Stub(MorpheusServices.getMethod('getOperationNotification').returnType) {
            list(_) >> { self.alarmLists++; self.alarms.findAll { !(it in self.cleared) } + self.created.findAll { !(it in self.cleared) } }
            createAlarm(_) >> { args -> self.created << args[0]; ServiceResponse.success() }
            clearAlarm(_) >> { args -> self.cleared << args[0]; ServiceResponse.success() }
        }
        def servers = Stub(MorpheusServices.getMethod('getComputeServer').returnType) { list(_) >> [] }
        def auditLog = Stub(MorpheusServices.getMethod('getAdmin').returnType.getMethod('getAuditLog').returnType) { log(_, _) >> { args -> args[1] } }
        def admin = Stub(MorpheusServices.getMethod('getAdmin').returnType) { getAuditLog() >> auditLog }
        def services = Stub(MorpheusServices) {
            getOperationData() >> svc
            getCluster() >> clusterService
            getOperationNotification() >> notifications
            getComputeServer() >> servers
            getAdmin() >> admin
        }
        Stub(MorpheusContext) {
            getServices() >> services
            getAsync() >> async
            getSettings(_) >> { Single.just(self.settingsJson) }
        }
    }

    private static ViewModel<Map> asUser(Account a, Map params = [clusterId: '27']) {
        new ViewModel<Map>(object: params, user: new User(id: 7L, username: 'someone', account: a))
    }

    private static HealthChecker.Host host(String name, HostFacts f, Long memory = null) {
        new HealthChecker.Host(name, false, null, null, null, f, null, memory)
    }

    private static HostFacts answered(Map extra = [:]) {
        HostFacts f = new HostFacts(complete: true, nproc: 32, mode: 'full')
        f.lockspaces = [[name: 'ls', flags: 'none', members: '1 2 3']]
        extra.each { k, v -> f[k as String] = v }
        f
    }

    private static HostFacts frozen() {
        answered(lockspaces: [[name: 'ls', flags: 'kern_stop', members: '1 2 3', status: 'wait fencing']])
    }

    private static HealthChecker.Input input(List<HealthChecker.Host> hosts, List<Map> vms = []) {
        Fixture.input(hosts, Fixture.LAYOUT_20, null, vms)
    }

    private static HealthChecker.Result result(String id, Status st, String observed = 'observed', String host = null, String title = 'a title') {
        new HealthChecker.Result(id, title, Severity.MEDIUM, st, host, observed, 'expected', 'plugin')
    }

    private static List<Map> tenantVms() {
        [[name: 'initech-db1', label: 'Initech DB 1', tenant: 'Initech', tenantId: 4L, power: 'on', host: 'hvm-node01', memory: 4 * GIB, cores: 4],
         [name: 'initech-db2', label: 'Initech DB 2', tenant: 'Initech', tenantId: 4L, power: 'on', host: 'hvm-node02', memory: 4 * GIB, cores: 4],
         [name: 'globex-web1', label: 'Globex Web 1', tenant: 'Globex', tenantId: 3L, power: 'on', host: 'hvm-node01', memory: 8 * GIB, cores: 8],
         [name: 'globex-off', label: 'Globex Off', tenant: 'Globex', tenantId: 3L, power: 'off', host: 'hvm-node01', memory: 8 * GIB, cores: 8]]
    }

    private static List<HealthChecker.Host> twoAnswered() {
        [host('hvm-node01', answered(), 64 * GIB), host('hvm-node02', answered(), 64 * GIB)]
    }

    // ------------------------------------------------------------------ one failure from an outage

    @Unroll
    def 'one failure from an outage: #id at #status is #wanted'() {
        given:
        Map summary = FindingHistory.summaryOf([results: [[id: id, status: status]]])

        expect:
        (FleetCorrelator.oneFailure(summary).size() == 1) == wanted

        where:
        id       | status        | wanted
        'CLU-04' | 'FAIL'        | true
        'CLU-04' | 'WARN'        | false    // a vote is missing, with votes to spare
        'CLU-04' | 'PASS'        | false
        'CAP-01' | 'WARN'        | true
        'CAP-01' | 'PASS'        | false
        'MIG-04' | 'WARN'        | true
        'HA-06'  | 'WARN'        | true
        'PKG-03' | 'WARN'        | true
        'STO-01' | 'FAIL'        | true
        'STO-01' | 'WARN'        | true
        'NET-01' | 'FAIL'        | true
        'HW-01'  | 'FAIL'        | true
        'HW-01'  | 'WARN'        | true
        'HW-01'  | 'NOT_CHECKED' | false    // says nothing either way, and is not a reason
        'DLM-01' | 'FAIL'        | false
    }

    def 'every id in the one-failure table is a real check, and each has words of its own'() {
        expect:
        CheckCatalog.ENTRIES.keySet().containsAll(FleetCorrelator.ONE_FAILURE.keySet())
        FleetCorrelator.ONE_FAILURE.values()*.reason.unique().size() == FleetCorrelator.ONE_FAILURE.size()
    }

    def 'a stored summary from before it carried ids is not known, which is not the same as no reasons'() {
        expect:
        FleetCorrelator.oneFailure([overall: 'HEALTHY', counts: [fail: 0]]) == null
        FleetCorrelator.oneFailure(null) == null
        FleetCorrelator.oneFailure([failing: [], warning: []]) == []
    }

    def 'the fleet rows carry the reasons, and a cluster with nothing stored has none known'() {
        given:
        MorpheusContext m = morpheus()

        when:
        def unknown = HealthMonitor.fleet(m)[0]
        HealthMonitor.LAST[27L] = [overall: 'WARNING', at: T0, results: [[id: 'HA-06', status: 'WARN'], [id: 'CAP-01', status: 'WARN']]]
        def known = HealthMonitor.fleet(m)[0]

        then:
        unknown.reasons == null
        known.reasons == [FleetCorrelator.ONE_FAILURE['CAP-01'].reason, FleetCorrelator.ONE_FAILURE['HA-06'].reason]
    }

    def 'the fleet page reads no more rows per view than before: none at all for clusters already checked'() {
        given:
        MorpheusContext m = morpheus()
        clusters = (1..200).collect { new ComputeServerGroup(id: it as Long, name: "c${it}".toString(), type: new ComputeServerGroupType(code: 'mvm-cluster')) }
        clusters.each { HealthMonitor.LAST[it.id] = [overall: 'HEALTHY', at: T0, results: []] }

        when:
        store.reads = 0
        HealthMonitor.fleet(m)
        Availability.forFleet(m, null, T0)
        Availability.forFleet(m, null, T0 + MIN)
        FleetCorrelator.current(null, T0)

        then:
        store.reads == 1     // the one query the availability section shares for 5 minutes, across every cluster

        when:
        Availability.forFleet(m, null, T0 + 6 * MIN)

        then:
        store.reads == 2

        cleanup:
        clusters.each { HealthMonitor.LAST.remove(it.id) }
    }

    // ------------------------------------------------------------------ FLT-01

    private static List<Map> raised(String id, long first) {
        [[id: id, status: 'WARN', firstSeen: first]]
    }

    private static void arrives(Long cluster, String check, long at, Set<String> risks = [] as Set, boolean all = true) {
        FleetCorrelator.note(cluster, "cluster-${cluster}".toString(), risks, raised(check, at), at - 30 * DAY, all, at)
    }

    private static void inWindow(Long cluster, List<Map> results, long at) {
        FleetCorrelator.note(cluster, "cluster-${cluster}".toString(), [] as Set, results, at - 30 * DAY, true, at, true)
    }

    def 'a cluster in a change window does not count: three with the finding and one in a window raise nothing'() {
        when:
        arrives(1L, 'NET-01', T0)
        arrives(2L, 'NET-01', T0 + MIN)
        inWindow(3L, raised('NET-01', T0 + 2 * MIN), T0 + 2 * MIN)

        then:
        FleetCorrelator.current(null, T0 + 2 * MIN).shared == []

        when: 'one more, outside a window, is the third'
        arrives(4L, 'NET-01', T0 + 3 * MIN)

        then:
        FleetCorrelator.current(null, T0 + 3 * MIN).shared[0].clusters*.id as Set == [1L, 2L, 4L] as Set
    }

    def 'rolling work across clusters in windows raises nothing at all'() {
        when:
        (1L..5L).each { inWindow(it, raised('STO-01', T0), T0 + MIN) }

        then:
        FleetCorrelator.current(null, T0 + MIN).shared == []
    }

    def 'a window starting takes a cluster out of what was already raised'() {
        when:
        (1L..3L).each { arrives(it, 'NET-01', T0) }

        then:
        FleetCorrelator.current(null, T0).shared.size() == 1

        when:
        inWindow(3L, raised('NET-01', T0), T0 + MIN)

        then:
        FleetCorrelator.current(null, T0 + MIN).shared == []
    }

    def 'what first appeared in a window does not count once it ends, and what appears after does'() {
        when: 'the window has just ended, with its finding still open'
        inWindow(3L, raised('NET-01', T0), T0 + MIN)
        arrives(1L, 'NET-01', T0 + 2 * MIN)
        arrives(2L, 'NET-01', T0 + 2 * MIN)
        FleetCorrelator.note(3L, 'cluster-3', [] as Set, raised('NET-01', T0), T0 - 30 * DAY, true, T0 + 3 * MIN)

        then:
        FleetCorrelator.current(null, T0 + 3 * MIN).shared == []

        when: 'it clears, and comes back with the window over'
        FleetCorrelator.note(3L, 'cluster-3', [] as Set, [], T0 - 30 * DAY, true, T0 + 4 * MIN)
        arrives(3L, 'NET-01', T0 + 5 * MIN)

        then:
        FleetCorrelator.current(null, T0 + 5 * MIN).shared[0].clusters*.id as Set == [1L, 2L, 3L] as Set
    }

    def 'a confirmed isolation failure is never held, so a window does not take it out of FLT-01'() {
        given:
        List<Map> confirmed = [[id: check, status: 'FAIL', firstSeen: T0]]

        when:
        inWindow(1L, confirmed, T0 + MIN)
        inWindow(2L, confirmed, T0 + MIN)
        inWindow(3L, confirmed, T0 + MIN)

        then:
        FleetCorrelator.current(null, T0 + MIN).shared[0].check == check

        where:
        check << ['NET-12', 'NET-13']
    }

    def 'its first, confirming, sighting is only a warning, and a window holds that'() {
        given:
        List<Map> confirming = [[id: check, status: 'WARN', firstSeen: T0]]

        when:
        (1L..3L).each { inWindow(it, confirming, T0 + MIN) }

        then:
        FleetCorrelator.current(null, T0 + MIN).shared == []

        where:
        check << ['NET-12', 'NET-13']
    }

    def 'another failure that always pages still does not count in a window: only isolation is named'() {
        when:
        (1L..3L).each { inWindow(it, [[id: 'DLM-01', status: 'FAIL', firstSeen: T0]], T0 + MIN) }

        then:
        FleetCorrelator.current(null, T0 + MIN).shared == []
    }

    def 'three clusters overall on one check within 10 minutes correlate; two with nothing shared do not'() {
        when:
        arrives(1L, 'NET-01', T0)
        arrives(2L, 'NET-01', T0 + MIN)

        then:
        FleetCorrelator.current(null, T0 + 2 * MIN).shared == []

        when:
        arrives(3L, 'NET-01', T0 + 2 * MIN)
        List<Map> rows = FleetCorrelator.current(null, T0 + 2 * MIN).shared

        then:
        rows.size() == 1
        rows[0].id == 'FLT-01'
        rows[0].check == 'NET-01'
        rows[0].clusters*.id as Set == [1L, 2L, 3L] as Set
        rows[0].observed.contains('cluster-1')
        rows[0].shared == []
    }

    def 'two clusters sharing an iSCSI portal correlate on a storage check, and several shared portals make one row'() {
        given:
        Set<String> risks = ['portal:192.0.2.10', 'portal:192.0.2.11'] as Set

        when:
        arrives(1L, 'GFS-07', T0, risks)
        arrives(2L, 'GFS-07', T0 + MIN, risks)
        List<Map> rows = FleetCorrelator.current(null, T0 + MIN).shared

        then:
        rows.size() == 1
        rows[0].shared == ['iSCSI portal 192.0.2.10', 'iSCSI portal 192.0.2.11']
    }

    def 'automatic groups only count for what they could explain: a shared time server says nothing about a bond'() {
        given:
        Set<String> risks = ['time:ntp1.example.invalid'] as Set

        when:
        [1L, 2L].each { FleetCorrelator.note(it, "cluster-${it}".toString(), risks, raised('NET-01', T0) + raised('TIM-01', T0), T0 - DAY, true, T0) }
        List<Map> rows = FleetCorrelator.current(null, T0).shared

        then:
        rows*.check == ['TIM-01']
    }

    def 'a site group counts for any check, by id or name, and a number is only ever an id'() {
        when:
        arrives(1L, 'NET-01', T0)
        arrives(2L, 'NET-01', T0)
        arrives(5L, 'HW-01', T0)
        FleetCorrelator.note(6L, '1', [] as Set, raised('HW-01', T0), T0 - 30 * DAY, true, T0)   // a cluster named "1" must not be picked by id 1
        List<Map> byIdAndName = FleetCorrelator.current('{"rack-5":[1,"CLUSTER-2"]}', T0).shared
        List<Map> byId = FleetCorrelator.current('{"rack-9":[1,5]}', T0).shared

        then:
        byIdAndName*.check == ['NET-01']
        byIdAndName[0].shared == ['site group rack-5']
        byId == []
    }

    def 'a mistake in the setting is shown, and correlation carries on without it'() {
        when:
        arrives(1L, 'NET-01', T0)
        arrives(2L, 'NET-01', T0)
        arrives(3L, 'NET-01', T0)
        Map r = FleetCorrelator.current(json, T0)

        then:
        r.error.contains('Shared risk groups')
        r.shared.size() == 1

        where:
        json << ['{not json', '[1,2]', '{"a":"b"}']
    }

    def 'clusters further apart than the window are not one event, however long both stay open'() {
        when:
        arrives(1L, 'NET-01', T0)
        arrives(2L, 'NET-01', T0 + 11 * MIN)
        arrives(3L, 'NET-01', T0 + 22 * MIN)
        // all three still reporting it
        [1L, 2L, 3L].each { FleetCorrelator.note(it, "cluster-${it}".toString(), [] as Set, raised('NET-01', T0), T0 - DAY, true, T0 + 23 * MIN) }

        then:
        FleetCorrelator.current(null, T0 + 23 * MIN).shared == []
    }

    def 'a finding open for months is no arrival: fifty clusters on an old agent are not a shared cause'() {
        when:
        (1L..50L).each { FleetCorrelator.note(it, "c${it}".toString(), [] as Set, raised('PKG-03', T0 - 90 * DAY), T0 - 365 * DAY, true, T0) }

        then:
        FleetCorrelator.current(null, T0).shared == []
    }

    def 'a first check on new history is no arrival either: the finding may be years old, and no history says nothing'() {
        when:
        (1L..5L).each { FleetCorrelator.note(it, "c${it}".toString(), [] as Set, raised('STO-01', T0), T0, true, T0) }
        (6L..10L).each { FleetCorrelator.note(it, "c${it}".toString(), [] as Set, raised('NET-01', T0), null, true, T0) }
        (11L..15L).each { FleetCorrelator.note(it, "c${it}".toString(), [] as Set, [[id: 'HW-01', status: 'WARN']], T0 - DAY, true, T0) }

        then:
        FleetCorrelator.current(null, T0).shared == []
    }

    def 'nothing correlates just after the plugin loads, when a new check first appears everywhere at once'() {
        given:
        FleetCorrelator.forget(T0 - 5 * MIN)

        when:
        (1L..5L).each { arrives(it, 'HST-99', T0) }

        then:
        FleetCorrelator.current(null, T0).shared == []
    }

    def 'it stays while the clusters still have the finding, and goes when they clear it'() {
        when:
        (1L..3L).each { arrives(it, 'NET-01', T0) }
        (1L..3L).each { FleetCorrelator.note(it, "cluster-${it}".toString(), [] as Set, raised('NET-01', T0), T0 - DAY, true, T0 + 60 * MIN) }

        then: 'an hour later, well past the window'
        FleetCorrelator.current(null, T0 + 60 * MIN).shared.size() == 1

        when:
        FleetCorrelator.note(1L, 'cluster-1', [] as Set, [], T0 - DAY, true, T0 + 65 * MIN)

        then:
        FleetCorrelator.current(null, T0 + 65 * MIN).shared == []
    }

    def 'a host going silent does not end it, as its findings go missing without being cleared; a removed cluster ages out'() {
        when:
        (1L..3L).each { arrives(it, 'NET-01', T0) }
        FleetCorrelator.note(1L, 'cluster-1', [] as Set, [], T0 - DAY, false, T0 + 5 * MIN)

        then:
        FleetCorrelator.current(null, T0 + 5 * MIN).shared.size() == 1

        and: 'nobody reports for over an hour'
        FleetCorrelator.current(null, T0 + 2 * 60 * MIN).shared == []
    }

    def 'one alarm per correlation, on the master tenant only, on nothing a cluster clean-up would take for its own'() {
        given:
        MorpheusContext m = morpheus()
        HealthChecker.Input in_ = input(twoAnswered())
        Map view = [results: raised('NET-01', T0), historySince: T0 - DAY]

        when:
        (1L..3L).each { Long id -> FleetCorrelator.afterRun(m, new ComputeServerGroup(id: id, name: "cluster-${id}".toString(), owner: ACME), in_, view, [:], T0) }

        then:
        created.size() == 1
        created[0].account.id == MASTER.id
        created[0].name.startsWith('FLT-01')
        created[0].statusMessage.contains('cluster-1')
        created[0].status == 'warning'
        !(created[0].uniqueId ==~ /^${HealthMonitor.CATEGORY}-\d+(-.*)?$/)
    }

    def 'Morpheus is asked about alarms only when the set changes, not on every check, at any number of clusters'() {
        given:
        MorpheusContext m = morpheus()
        HealthChecker.Input in_ = input(twoAnswered())
        Map view = [results: raised('NET-01', T0), historySince: T0 - DAY]
        (1L..3L).each { Long id -> FleetCorrelator.afterRun(m, new ComputeServerGroup(id: id, name: "cluster-${id}".toString()), in_, view, [:], T0) }
        int before = alarmLists

        when:
        (1L..200L).each { Long id -> FleetCorrelator.afterRun(m, new ComputeServerGroup(id: id, name: "cluster-${id}".toString()), in_, [results: [], historySince: T0 - DAY], [:], T0 - 1) ; }
        int afterAll = alarmLists

        then: 'the three cleared, so the correlation ended once, and that is one question'
        afterAll - before == 1
        cleared.size() == 1
    }

    def 'after a reload a stale alarm from before is cleared once, and with no correlation nothing is raised'() {
        given:
        alarms << new OperationNotification(uniqueId: "${FleetCorrelator.ALARM_PREFIX}NET-01-abc".toString(), active: true)
        alarms << new OperationNotification(uniqueId: "${HealthMonitor.CATEGORY}-27-NET-01-abc".toString(), active: true)
        MorpheusContext m = morpheus()

        when:
        FleetCorrelator.afterRun(m, clusters[0], input(twoAnswered()), [results: []], [:], T0)

        then:
        cleared*.uniqueId == ["${FleetCorrelator.ALARM_PREFIX}NET-01-abc".toString()]
        created == []
    }

    def 'FLT-01 reaches no cluster view and no report, as it names other clusters'() {
        given:
        Map view = HealthMonitor.LAST[27L] = [overall: 'WARNING', at: T0, results: raised('NET-01', T0), readiness: [], hosts: []]
        (1L..3L).each { arrives(it, 'NET-01', T0) }

        when:
        String reportJson = JsonOutput.toJson(HealthReport.build([id: 27L, name: 'cluster-x'], view, null, null, [], T0))

        then:
        FleetCorrelator.current(null, T0).shared.size() == 1
        !JsonOutput.toJson(view).contains('FLT-01')
        !reportJson.contains('FLT-01')
        !reportJson.contains('cluster-2')
    }

    def 'a failure in correlation never fails the check'() {
        given:
        MorpheusContext broken = Stub(MorpheusContext) { getServices() >> { throw new IllegalStateException('nothing works') } }

        when:
        FleetCorrelator.afterRun(broken, clusters[0], input(twoAnswered()), [results: []], [:], T0)

        then:
        noExceptionThrown()
    }

    // ------------------------------------------------------------------ CAP-02

    private static List<HealthChecker.Result> checked(HealthChecker.Input i, String id) {
        new HealthChecker().check(i).findAll { it.id == id }
    }

    @Unroll
    def 'CAP-02 at #vcpus vCPUs on 64 threads is #want'() {
        given:
        List<Map> vms = [[name: 'a', power: 'on', cores: vcpus], [name: 'b', power: 'off', cores: 9999]]

        when:
        HealthChecker.Result r = checked(input(twoAnswered(), vms), 'CAP-02')[0]

        then:
        r.status == want

        where:
        vcpus | want
        16    | Status.PASS
        255   | Status.PASS
        256   | Status.WARN
        511   | Status.WARN
        512   | Status.FAIL
    }

    def 'CAP-02 takes its limits from the site standards'() {
        given:
        HealthChecker.Input i = Fixture.input(twoAnswered(), Fixture.LAYOUT_20, '{"vcpuWarnRatio":1,"vcpuFailRatio":2}', [[name: 'a', power: 'on', cores: 64]])

        expect:
        checked(i, 'CAP-02')[0].status == Status.WARN
    }

    def 'CAP-02 is not checked, never a pass, when one side of it cannot be read'() {
        when:
        HealthChecker.Result r = checked(i, 'CAP-02')[0]

        then:
        r.status == Status.NOT_CHECKED
        r.observed.contains(says)

        where:
        i                                                                                                  | says
        input([host('hvm-node01', answered()), host('hvm-node02', new HostFacts())], [[power: 'on', cores: 4]]) | 'hvm-node02'
        input([host('hvm-node01', answered(nproc: 0))], [[power: 'on', cores: 4]])                         | 'hvm-node01'
        input(twoAnswered(), [[power: 'on', cores: 4], [power: 'on', cores: null]])                        | 'no vCPU count'
        input([host('hvm-node01', answered(vms: [[name: 'x', state: 'running']]))], [])                    | 'no running VMs'
    }

    // ------------------------------------------------------------------ the line

    private static List<List> rising(int days, double start, double perDay, long today) {
        (0..<days).collect { int i -> [today - (days - 1 - i), start + perDay * i] }
    }

    def 'the slope is the least-squares slope, and the newest value is the newest sample'() {
        when:
        Map l = CapacityTrend.line(rising(30, 100, 2.5, 1000L), 1000L)

        then:
        Math.abs((l.slope as double) - 2.5) < 1e-9
        (l.latest as double) == 100 + 2.5 * 29
        l.age == 0
        l.n == 30
    }

    def 'it ignores noise it can average away, and tolerates missing days'() {
        given:
        List<List> pts = rising(60, 1000, 10, 1000L).indexed().collect { int i, List p -> [p[0], (p[1] as double) + (i % 2 ? 7 : -7)] }.findAll { (it[0] as long) % 3 != 0 }

        when:
        Map l = CapacityTrend.line(pts, 1000L)

        then:
        Math.abs((l.slope as double) - 10) < 0.5
    }

    def 'fewer than 14 samples carry no slope to misread'() {
        expect:
        CapacityTrend.line(rising(13, 1, 1, 1000L), 1000L) == [n: 13]
        CapacityTrend.line(rising(14, 1, 1, 1000L), 1000L).slope != null
        CapacityTrend.line([], 1000L) == [n: 0]
    }

    @Unroll
    def 'days to a limit: #latest growing #slope against #limit, #age days old, is #want'() {
        expect:
        HealthChecker.forecastDays(latest, slope, age, limit) == want

        where:
        latest | slope | age | limit | want
        100G   | 10G   | 0L  | 200G  | 10L
        100G   | 3G    | 0L  | 200G  | 34L      // rounds up
        100G   | 10G   | 4L  | 200G  | 6L       // from today, not from the newest sample
        100G   | 10G   | 40L | 200G  | 0L
        100G   | 0G    | 0L  | 200G  | null     // not growing
        100G   | -5G   | 0L  | 200G  | null
        200G   | 0G    | 0L  | 200G  | 0L       // already there, however flat
        250G   | -5G   | 0L  | 200G  | 0L
    }

    // ------------------------------------------------------------------ CAP-03 and GFS-11

    private static Map fcLine(Map over = [:]) {
        [n: 30, slope: GIB as BigDecimal, latest: (100 * GIB) as BigDecimal, age: 0L, limit: (200 * GIB) as BigDecimal] + over
    }

    private static HealthChecker.Input withForecast(Map fc) {
        HealthChecker.Input i = input(twoAnswered())
        i.clusterConfig.forecast = fc
        i
    }

    def 'with no forecast given, the checker says nothing about one'() {
        when:
        List<HealthChecker.Result> rs = new HealthChecker().check(input(twoAnswered()))

        then:
        !rs.any { it.id in ['CAP-03', 'GFS-11'] }
    }

    @Unroll
    def 'CAP-03: #what'() {
        when:
        HealthChecker.Result r = checked(withForecast([nPlusOne: line, datastores: []]), 'CAP-03')[0]

        then:
        r.status == want
        r.observed.contains(says)

        where:
        what                     | line                                                    | want               | says
        'runs out soon'          | fcLine(slope: (5 * GIB) as BigDecimal)                  | Status.WARN        | 'about 20 days'
        'at the very limit'      | fcLine(slope: (100 * GIB / 30) as BigDecimal)           | Status.WARN        | 'about 30 days'
        'runs out far off'       | fcLine()                                                | Status.PASS        | 'about 100 days'
        'shrinking'              | fcLine(slope: -GIB as BigDecimal)                       | Status.PASS        | 'not growing'
        'already past'           | fcLine(latest: (300 * GIB) as BigDecimal)               | Status.WARN        | 'already past'
        'too few days'           | [n: 13]                                                 | Status.NOT_CHECKED | '13 of 14'
        'no samples'             | [n: 0]                                                  | Status.NOT_CHECKED | '0 of 14'
        'gone stale'             | fcLine(age: 8L)                                         | Status.NOT_CHECKED | '8 days old'
    }

    def 'a store that cannot be read is not checked, never a pass, on both forecasts'() {
        when:
        List<HealthChecker.Result> rs = new HealthChecker().check(withForecast([error: 'its capacity samples could not be read']))

        then:
        rs.find { it.id == 'CAP-03' }.status == Status.NOT_CHECKED
        rs.find { it.id == 'CAP-03' }.observed.contains('could not be read')
        rs.find { it.id == 'GFS-11' }.status == Status.NOT_CHECKED
    }

    def 'forecastWarnDays is a site standard, and 30 is the default'() {
        given:
        HealthChecker.Input i = Fixture.input(twoAnswered(), Fixture.LAYOUT_20, '{"forecastWarnDays":120}')
        i.clusterConfig.forecast = [nPlusOne: fcLine(), datastores: []]

        expect:
        checked(withForecast([nPlusOne: fcLine(), datastores: []]), 'CAP-03')[0].status == Status.PASS
        checked(i, 'CAP-03')[0].status == Status.WARN
    }

    def 'GFS-11 is against gfs2FailPct of the current size, per datastore, on the cluster'() {
        given:
        Map near = fcLine(target: '/mnt/ds-a', size: (1000 * GIB) as BigDecimal, latest: (880 * GIB) as BigDecimal, slope: GIB as BigDecimal)
        Map far = fcLine(target: '/mnt/ds-b', size: (1000 * GIB) as BigDecimal, latest: (100 * GIB) as BigDecimal, slope: GIB as BigDecimal)
        Map young = [n: 3, target: '/mnt/ds-c', size: (1000 * GIB) as BigDecimal]

        when:
        List<HealthChecker.Result> rs = checked(withForecast([nPlusOne: [n: 0], datastores: [near, far, young]]), 'GFS-11')

        then:
        rs.size() == 3
        rs.find { it.title.endsWith('ds-a') }.status == Status.WARN
        rs.find { it.title.endsWith('ds-a') }.observed.contains('about 20 days')
        rs.find { it.title.endsWith('ds-b') }.status == Status.PASS
        rs.find { it.title.endsWith('ds-c') }.status == Status.NOT_CHECKED
        rs.every { it.host == null }
    }

    def 'with GFS2 mounted and no usage ever read, GFS-11 says so; with none mounted, or HCI Ceph, it has nothing to say'() {
        given:
        HealthChecker.Input none = withForecast([nPlusOne: [n: 0], datastores: []])
        HealthChecker.Input some = input([host('hvm-node01', answered(mounts: [[dev: 'x', target: '/mnt/ds-a']])), host('hvm-node02', answered())])
        some.clusterConfig.forecast = [nPlusOne: [n: 0], datastores: []]
        HealthChecker.Input ceph = Fixture.input(twoAnswered(), 'mvm-1.3-ubuntu-24.04-ceph-hci-amd64')
        ceph.clusterConfig.forecast = [nPlusOne: [n: 0], datastores: []]

        expect:
        checked(none, 'GFS-11') == []
        checked(some, 'GFS-11')[0].status == Status.NOT_CHECKED
        checked(ceph, 'GFS-11') == []
    }

    def 'a single host has no N+1 to forecast'() {
        given:
        HealthChecker.Input i = input([host('hvm-node01', answered())])
        i.clusterConfig.forecast = [nPlusOne: [n: 0], datastores: []]

        expect:
        checked(i, 'CAP-03') == []
    }

    // ------------------------------------------------------------------ samples

    def 'a sample holds what the run can vouch for: everything when all is well'() {
        when:
        Map s = CapacityTrend.sampleOf(input([host('hvm-node01', answered(usage: [[target: '/mnt/ds-a', used: 10L, size: 100L]]), 64 * GIB), host('hvm-node02', answered(), 64 * GIB)], tenantVms()))

        then:
        s.ds == ['/mnt/ds-a': [10L, 100L]]
        s.m == [16 * GIB, 64 * GIB]
        s.c == [16L, 64L]
    }

    def 'a silent host leaves memory and vCPUs out of the sample rather than a false dip, and what others read of the datastore stays'() {
        when:
        Map s = CapacityTrend.sampleOf(input([host('hvm-node01', answered(usage: [[target: '/mnt/ds-a', used: 10L, size: 100L]]), 64 * GIB), host('hvm-node02', new HostFacts(), 64 * GIB)], tenantVms()))

        then:
        s.keySet() == ['ds'] as Set
    }

    def 'a frozen cluster gives no sample at all, as a frozen datastore has no usage to read and Morpheus cannot be believed about VMs'() {
        when:
        Map s = CapacityTrend.sampleOf(input([host('hvm-node01', frozen(), 64 * GIB), host('hvm-node02', frozen(), 64 * GIB)], tenantVms()))

        then:
        s == [:]
    }

    def 'one sample a day: the first run writes it, the rest of the day changes nothing, and a later run fills in what a frozen morning left out'() {
        given:
        Map doc = [samples: []]

        when:
        boolean first = CapacityTrend.merge(doc, [ds: [a: [10L, 100L]]], 500L)
        boolean again = CapacityTrend.merge(doc, [ds: [a: [99L, 100L]]], 500L)
        boolean filled = CapacityTrend.merge(doc, [ds: [a: [99L, 100L]], m: [1L, 2L], c: [3L, 4L]], 500L)
        boolean nothing = CapacityTrend.merge(doc, [:], 500L)
        boolean nextDay = CapacityTrend.merge(doc, [ds: [a: [11L, 100L]]], 501L)
        boolean emptyDay = CapacityTrend.merge(doc, [:], 502L)

        then:
        first
        !again
        filled
        !nothing
        nextDay
        !emptyDay
        doc.samples.size() == 2
        doc.samples[0] == [d: 500L, ds: [a: [10L, 100L]], m: [1L, 2L], c: [3L, 4L]]
    }

    def 'at most 90 days are kept, and under the cap with many datastores, dropping the oldest and counting them'() {
        given:
        Map<String, List<Long>> many = (1..40).collectEntries { ["/var/morpheus/kvm/mnt/datastore-number-${it}".toString(), [Long.MAX_VALUE, Long.MAX_VALUE]] }
        Map doc = [samples: []]
        (1L..200L).each { CapacityTrend.merge(doc, [ds: many, m: [1L, 2L], c: [3L, 4L]], it) }

        when:
        int kept = doc.samples.size()
        String json = CapacityTrend.encode(doc)

        then:
        kept == 90
        json.getBytes('UTF-8').length <= FindingHistory.MAX_BYTES
        (new JsonSlurper().parseText(json) as Map).dropped > 0
        doc.samples[-1].d == 200L
    }

    def 'an ordinary cluster keeps all 90 days within the cap'() {
        given:
        Map doc = [samples: []]
        (1L..90L).each { CapacityTrend.merge(doc, [ds: ['/var/morpheus/kvm/mnt/datastore-a': [Long.MAX_VALUE, Long.MAX_VALUE], '/var/morpheus/kvm/mnt/datastore-b': [Long.MAX_VALUE, Long.MAX_VALUE]], m: [Long.MAX_VALUE, Long.MAX_VALUE], c: [999L, 999L]], it) }

        expect:
        CapacityTrend.encode(doc).getBytes('UTF-8').length <= FindingHistory.MAX_BYTES
        doc.samples.size() == 90
    }

    def 'through the store: fourteen days of growth becomes a forecast, row once a day, one row kept, and it survives a reload'() {
        given:
        MorpheusContext m = morpheus()
        long used = 500 * GIB
        Closure<HealthChecker.Input> today = { long at ->
            input([host('hvm-node01', answered(usage: [[target: '/mnt/ds-a', used: used + (at - T0).intdiv(DAY) * 10 * GIB, size: 1000 * GIB]], mounts: [[dev: 'x', target: '/mnt/ds-a']]), 64 * GIB), host('hvm-node02', answered(), 64 * GIB)], tenantVms())
        }
        List<Map> seen = []

        when:
        (0L..<13L).each { long d -> seen << CapacityTrend.forecastFor(m, 27L, today(T0 + d * DAY), T0 + d * DAY) }

        then: 'thirteen samples say nothing yet'
        seen[-1].nPlusOne.n == 13
        seen[-1].nPlusOne.slope == null
        seen[-1].datastores[0].slope == null

        when:
        int writes = store.creates
        int reads = store.reads
        (1..50).each { CapacityTrend.forecastFor(m, 27L, today(T0 + 12 * DAY + it * 5 * MIN), T0 + 12 * DAY + it * 5 * MIN) }

        then: 'fifty more checks that day cost no read and no write'
        store.creates == writes
        store.reads == reads
        store.rows.size() == 1

        when: 'a reload, then the fourteenth day'
        CapacityTrend.forget()
        Map fc = CapacityTrend.forecastFor(m, 27L, today(T0 + 13 * DAY), T0 + 13 * DAY)

        then:
        fc.datastores[0].n == 14
        Math.abs((fc.datastores[0].slope as double) - 10 * GIB) < 1
        fc.datastores[0].target == '/mnt/ds-a'
        store.rows.size() == 1
        !store.rows[0].rawData.contains('Initech')
    }

    def 'a store that cannot be read gives an error, and nothing is written over what is there'() {
        given:
        MorpheusContext m = morpheus()
        CapacityTrend.forecastFor(m, 27L, input(twoAnswered()), T0)
        String before = store.rows[0].rawData
        CapacityTrend.forget()
        store.failReads = true

        when:
        Map fc = CapacityTrend.forecastFor(m, 27L, input(twoAnswered()), T0 + DAY)

        then:
        fc.error
        CapacityTrend.ERRORS[27L]
        store.rows.size() == 1
        store.rows[0].rawData == before
    }

    def 'a store that cannot be written keeps the samples in memory, says so, and catches up'() {
        given:
        MorpheusContext m = morpheus()
        store.failCreates = true

        when:
        Map fc = CapacityTrend.forecastFor(m, 27L, input(twoAnswered(), tenantVms()), T0)

        then:
        fc.nPlusOne.n == 1
        CapacityTrend.ERRORS[27L]
        store.rows == []

        when:
        store.failCreates = false
        CapacityTrend.forecastFor(m, 27L, input(twoAnswered(), tenantVms()), T0 + MIN)

        then:
        store.rows.size() == 1
        !CapacityTrend.ERRORS[27L]
    }

    def 'a datastore that has gone is left out, and one still mounted with old samples is not checked rather than dropped'() {
        given:
        Map doc = [samples: (1L..20L).collect { [d: it, ds: [gone: [1L, 10L], still: [1L, 10L]]] }]

        when:
        Map fc = CapacityTrend.forecast(doc, input([host('hvm-node01', answered(mounts: [[dev: 'x', target: 'still']]))]), 40L)

        then:
        fc.datastores*.target == ['still']
        fc.datastores[0].age == 20
    }

    // ------------------------------------------------------------------ STO-07

    private static final String THIN_LINE = '2026-10-30T03:14:15+0000 host kernel: sd 3:0:0:1: Warning! Received an indication that the LUN reached a thin provisioning soft threshold.'

    private static HostFacts thinFacts(String lines) {
        HostFacts f = HostFacts.parse("@@meta\nMODE=full\n@@thin\n${lines}\n@@end\nCOMPLETE=1\n")
        f
    }

    def 'STO-07 warns on the array\'s warning, passes on a log read clean, and is not checked on a log that could not be read'() {
        when:
        HealthChecker.Result r = checked(input([host('hvm-node01', thinFacts(lines))]), 'STO-07')[0]

        then:
        r.status == want
        r.observed.contains(says)
        r.host == 'hvm-node01'

        where:
        lines                                                       | want               | says
        "THIN_SOFT_7D=2\nTHIN_SOFT_LAST=${THIN_LINE}"               | Status.WARN        | '2 in the kernel log'
        'THIN_SOFT_7D=0\nTHIN_SOFT_LAST='                           | Status.PASS        | 'none in 7 days'
        'THIN_SOFT_LAST='                                           | Status.NOT_CHECKED | 'cannot tell'
    }

    def 'STO-07 belongs to the full check: a quick probe has no section, and says nothing rather than pass'() {
        given:
        HostFacts quick = HostFacts.parse('@@meta\nMODE=quick\n@@end\nCOMPLETE=1\n')

        expect:
        checked(input([host('hvm-node01', quick)]), 'STO-07') == []
        'STO-07' in HealthMonitor.FULL_ONLY
    }

    def 'the probe reads the log on full checks only, read-only, and prints no count from a log it could not read'() {
        given:
        String probe = HealthMonitor.getResourceAsStream('/scripts/health-probe.sh').getText('UTF-8')
        String line = probe.readLines().find { it.contains('THIN_SOFT_7D') }

        expect:
        probe.indexOf('sec thin') > probe.indexOf('if [ "$MODE" = full ]; then\n  sec journals')
        line.contains('exit "${PIPESTATUS[0]}")" && echo "THIN_SOFT_7D=')
        line.contains('t 20 journalctl')
    }

    // ------------------------------------------------------------------ availability: the document

    def 'seconds fall in the month they happened, across a boundary'() {
        given:
        long jan31 = java.time.Instant.parse('2030-01-31T23:50:00Z').toEpochMilli()

        expect:
        Availability.split(jan31, jan31 + 20 * MIN) == ['2030-01': 600L, '2030-02': 600L]
        Availability.split(jan31, jan31) == [:]
        Availability.split(jan31, jan31 - MIN) == [:]
    }

    private static Map fresh(long at = T0) {
        Availability.empty(at)
    }

    private static HealthChecker.Input healthyRun(List<Map> vms = tenantVms()) {
        input([host('hvm-node01', answered()), host('hvm-node02', answered())], vms)
    }

    private static HealthChecker.Input frozenRun(List<Map> vms = tenantVms()) {
        input([host('hvm-node01', frozen()), host('hvm-node02', frozen())], vms)
    }

    private static final long Q = 5 * MIN

    def 'a freeze reaches the tenants that had running VMs before it, for as long as it lasted'() {
        given:
        Map doc = fresh()
        Availability.observe(doc, healthyRun(), [], Q, null, T0)

        when: 'Morpheus has stopped listing VMs by now: what was known before stands'
        Availability.observe(doc, frozenRun([]), [], Q, null, T0 + Q)
        Availability.observe(doc, healthyRun(), [], Q, null, T0 + 3 * Q + 20 * MIN)
        Map f = Availability.figures(doc, Availability.monthKey(T0), T0 + 3 * Q + 20 * MIN)

        then:
        f.frozenMinutes == 30.0
        f.freezes == 1
        f.stalls == 0
        f.tenants.find { it.name == 'Initech' }.frozenMinutes == 30.0
        f.tenants.find { it.name == 'Initech' }.runningVms == 2
        f.tenants.find { it.name == 'Globex' }.frozenMinutes == 30.0
        f.tenants.find { it.name == 'Globex' }.runningVms == 1
        f.unattributedMinutes == 0.0
    }

    def 'a tenant with VMs only stopped gets no frozen minutes, and one arriving during the freeze is not counted'() {
        given:
        Map doc = fresh()
        List<Map> before = [[name: 'a', tenant: 'Initech', tenantId: 4L, power: 'on'], [name: 'b', tenant: 'Globex', tenantId: 3L, power: 'off']]
        Availability.observe(doc, healthyRun(before), [], Q, null, T0)

        when:
        Availability.observe(doc, frozenRun(tenantVms()), [], Q, null, T0 + Q)
        Availability.observe(doc, healthyRun(before), [], Q, null, T0 + 2 * Q)
        Map f = Availability.figures(doc, Availability.monthKey(T0), T0 + 2 * Q)

        then:
        f.tenants*.name == ['Initech']
    }

    def 'a freeze of 5 minutes or less is a stall, not a freeze'() {
        given:
        Map doc = fresh()

        when:
        Availability.openFreeze(doc, T0, T0)
        Availability.closeFreeze(doc, T0 + 2 * MIN, true)
        Availability.openFreeze(doc, T0 + DAY, T0 + DAY)
        Availability.closeFreeze(doc, T0 + DAY + 6 * MIN, true)
        Map f = Availability.figures(doc, Availability.monthKey(T0), T0 + 2 * DAY)

        then:
        f.stalls == 1
        f.freezes == 1
        f.frozenMinutes == 8.0
    }

    def 'GFS2 not answering with DLM healthy is one stall per episode, however many checks see it'() {
        given:
        Map doc = fresh()
        List<HealthChecker.Result> slow = [result('GFS-07', Status.FAIL)]

        when:
        Availability.observe(doc, healthyRun(), slow, Q, null, T0)
        Availability.observe(doc, healthyRun(), slow, Q, null, T0 + Q)
        Availability.observe(doc, healthyRun(), [], Q, null, T0 + 2 * Q)
        Availability.observe(doc, healthyRun(), slow, Q, null, T0 + 3 * Q)
        Map f = Availability.figures(doc, Availability.monthKey(T0), T0 + 3 * Q)

        then:
        f.stalls == 2
        f.frozenMinutes == 0.0
    }

    def 'starting a freeze twice keeps the first start, and ending one that is not open changes nothing'() {
        given:
        Map doc = fresh()

        expect:
        !Availability.closeFreeze(doc, T0, true)
        Availability.openFreeze(doc, T0, T0)
        !Availability.openFreeze(doc, T0 + MIN, T0 + MIN)
        doc.open.at == T0
        doc.open.seen == T0 + MIN
    }

    def 'a freeze still open counts up to now, in the month it is in, for the tenants it reaches'() {
        given:
        Map doc = fresh()
        Availability.observe(doc, healthyRun(), [], Q, null, T0)
        Availability.openFreeze(doc, T0, T0)

        when:
        Map f = Availability.figures(doc, Availability.monthKey(T0), T0 + 40 * MIN)

        then:
        f.frozenMinutes == 40.0
        f.ongoing
        f.tenants.find { it.name == 'Initech' }.frozenMinutes == 40.0
    }

    def 'a freeze with nobody known before it, seen by a check, takes who Morpheus lists now; with nothing at all it is unattributed, not nobody'() {
        given:
        Map listed = fresh()
        Map bare = fresh()

        when:
        Availability.observe(listed, frozenRun(), [], Q, null, T0)
        Availability.closeFreeze(listed, T0 + 10 * MIN, true)
        Availability.freezeStarted(null, 999L, T0)   // nothing to read it with: must not throw
        Availability.openFreeze(bare, T0, T0)
        Availability.closeFreeze(bare, T0 + 10 * MIN, true)
        Map a = Availability.figures(listed, Availability.monthKey(T0), T0 + DAY)
        Map b = Availability.figures(bare, Availability.monthKey(T0), T0 + DAY)

        then:
        a.tenants*.name.containsAll(['Initech', 'Globex'])
        a.unattributedMinutes == 0.0
        b.tenants == []
        b.unattributedMinutes == 10.0
    }

    def 'silent hosts do not close a freeze, and one host answering with its lockspaces running does'() {
        given:
        Map doc = fresh()
        Availability.openFreeze(doc, T0, T0)

        when:
        Availability.observe(doc, input([host('hvm-node01', new HostFacts()), host('hvm-node02', new HostFacts())]), [], Q, null, T0 + Q)

        then:
        doc.open != null

        when: 'a rebooted host with no lockspace yet vouches for nothing, while another stays silent'
        Availability.observe(doc, input([host('hvm-node01', answered(lockspaces: [])), host('hvm-node02', new HostFacts())]), [], Q, null, T0 + 2 * Q)

        then:
        doc.open != null

        when: 'a member says its lockspaces run, though another host is still silent'
        Availability.observe(doc, input([host('hvm-node01', answered()), host('hvm-node02', new HostFacts())]), [], Q, null, T0 + 3 * Q)

        then:
        doc.open == null
    }

    def 'while the watch says it is frozen, a quick check that disagrees does not close it'() {
        given:
        Map doc = fresh()

        when:
        Availability.observe(doc, healthyRun(), [], Q, T0 - MIN, T0)

        then:
        doc.open.at == T0 - MIN

        when:
        Availability.observe(doc, healthyRun(), [], Q, T0 - MIN, T0 + Q)

        then:
        doc.open != null

        when:
        Availability.observe(doc, healthyRun(), [], Q, null, T0 + 2 * Q)

        then:
        doc.open == null
    }

    def 'a freeze found over after a long silence counts only to when it was last seen: the rest is unknown, never frozen or up'() {
        given:
        Map doc = fresh()
        Availability.observe(doc, healthyRun(), [], Q, null, T0)
        Availability.observe(doc, frozenRun(), [], Q, null, T0 + Q)

        when: 'the plugin is gone for two hours and the freeze is over when it returns'
        Availability.observe(doc, healthyRun(), [], Q, null, T0 + Q + 120 * MIN)
        Map f = Availability.figures(doc, Availability.monthKey(T0), T0 + Q + 120 * MIN)

        then:
        f.frozenMinutes == 0.0
        f.stalls == 1
        f.unknownMinutes == 120.0
        f.observedMinutes == 5.0
    }

    def 'the watch saw it to the end, so all of it counts, however long since a check'() {
        given:
        Map doc = fresh()
        Availability.openFreeze(doc, T0, T0)

        when:
        Availability.closeFreeze(doc, T0 + 120 * MIN, true)

        then:
        Availability.figures(doc, Availability.monthKey(T0), T0 + 120 * MIN).frozenMinutes == 120.0
    }

    def 'a freeze across a month end falls in both, for the cluster and the tenant, and counts once'() {
        given:
        long jan31 = java.time.Instant.parse('2030-01-31T23:50:00Z').toEpochMilli()
        Map doc = Availability.empty(jan31 - DAY)
        Availability.observe(doc, healthyRun(), [], Q, null, jan31 - MIN)

        when:
        Availability.openFreeze(doc, jan31, jan31)
        Availability.closeFreeze(doc, jan31 + 20 * MIN, true)

        then:
        Availability.figures(doc, '2030-01', jan31 + DAY).frozenMinutes == 10.0
        Availability.figures(doc, '2030-02', jan31 + DAY).frozenMinutes == 10.0
        Availability.figures(doc, '2030-01', jan31 + DAY).tenants.find { it.name == 'Initech' }.frozenMinutes == 10.0
        Availability.figures(doc, '2030-02', jan31 + DAY).tenants.find { it.name == 'Initech' }.frozenMinutes == 10.0
        Availability.figures(doc, '2030-01', jan31 + DAY).freezes == 1
        Availability.figures(doc, '2030-02', jan31 + DAY).freezes == 0
    }

    // ------------------------------------------------------------------ availability: unknown is unknown

    def 'a gap between checks is unknown, not observed'() {
        given:
        Map doc = fresh()
        String key = Availability.monthKey(T0)

        when:
        (0..3).each { Availability.observe(doc, healthyRun(), [], Q, null, T0 + it * Q) }
        Map steady = Availability.figures(doc, key, T0 + 3 * Q)
        Availability.observe(doc, healthyRun(), [], Q, null, T0 + 3 * Q + 300 * MIN)
        Map gap = Availability.figures(doc, key, T0 + 3 * Q + 300 * MIN)

        then:
        steady.observedMinutes == 15.0
        steady.unknownMinutes == 0.0
        gap.observedMinutes == 15.0
        gap.unknownMinutes == 300.0
    }

    def 'a run no host answered saw nothing, so that time is unknown'() {
        given:
        Map doc = fresh()
        HealthChecker.Input silent = input([host('hvm-node01', new HostFacts()), host('hvm-node02', new HostFacts())])
        String key = Availability.monthKey(T0)

        when:
        Availability.observe(doc, healthyRun(), [], Q, null, T0)
        (1..4).each { Availability.observe(doc, silent, [], Q, null, T0 + it * Q) }
        Map f = Availability.figures(doc, key, T0 + 4 * Q)

        then:
        f.observedMinutes == 0.0
        f.unknownMinutes == 20.0
    }

    def 'before the first check of a cluster nothing is claimed either way, and a month it was not watched has no period'() {
        given:
        Map doc = fresh()

        expect:
        Availability.figures(doc, '2020-01', T0).periodMinutes == 0.0
        Availability.figures(doc, Availability.monthKey(T0), T0 + 60 * MIN).observedMinutes == 0.0
        Availability.figures(doc, Availability.monthKey(T0), T0 + 60 * MIN).unknownMinutes == 60.0
    }

    def 'a long check interval is still observed, up to 3 intervals'() {
        given:
        Map doc = fresh()

        when:
        Availability.observe(doc, healthyRun(), [], 60 * MIN, null, T0)
        Availability.observe(doc, healthyRun(), [], 60 * MIN, null, T0 + 180 * MIN)
        Availability.observe(doc, healthyRun(), [], 60 * MIN, null, T0 + 181 * MIN + 180 * MIN)

        then:
        Availability.figures(doc, Availability.monthKey(T0), T0 + 361 * MIN).observedMinutes == 180.0
    }

    // ------------------------------------------------------------------ availability: VM stops

    def 'a crash counts once while VM-06 keeps it open, for its tenant, by name or label; again only after it has gone'() {
        given:
        Map doc = fresh()
        List<HealthChecker.Result> crash = [result('VM-06', Status.WARN, 'initech-db1 crashed at 03:14 on hvm-node01')]
        String key = Availability.monthKey(T0)

        when:
        Availability.observe(doc, healthyRun(), crash, Q, null, T0)
        Availability.observe(doc, healthyRun(), crash, Q, null, T0 + Q)
        Map f = Availability.figures(doc, key, T0 + Q)

        then:
        f.vmStops == 1
        f.tenants.find { it.name == 'Initech' }.vmStops == 1
        f.tenants.find { it.name == 'Globex' }.vmStops == 0

        when: 'it leaves the 24 h window, then the VM crashes again'
        Availability.observe(doc, healthyRun(), [], Q, null, T0 + 2 * Q)
        Availability.observe(doc, healthyRun(), crash, Q, null, T0 + 3 * Q)

        then:
        Availability.figures(doc, key, T0 + 3 * Q).tenants.find { it.name == 'Initech' }.vmStops == 2
    }

    private static HealthChecker.Input crashed(List<Long> atSeconds, List<Map> kills = []) {
        HostFacts f = answered(vmCrashes: atSeconds.collect { [vm: 'initech-db1', at: it] }, oomKills: kills, now: (T0 / 1000L) as long + 7200L)
        input([host('hvm-node01', f), host('hvm-node02', answered())], tenantVms())
    }

    private static final List<HealthChecker.Result> STOPPED = [result('VM-06', Status.WARN, 'Initech DB 1 crashed', 'hvm-node01')]
    private static final long SEC_T0 = (T0 / 1000L) as long

    def 'a second crash of one VM inside VM-06 window counts, on its own time, and a crash seen again does not'() {
        given:
        Map doc = fresh()
        String key = Availability.monthKey(T0)

        when: 'one crash, seen by two checks'
        Availability.observe(doc, crashed([SEC_T0]), STOPPED, Q, null, T0 + Q)
        Availability.observe(doc, crashed([SEC_T0]), STOPPED, Q, null, T0 + 2 * Q)

        then:
        Availability.figures(doc, key, T0 + 2 * Q).tenants.find { it.name == 'Initech' }.vmStops == 1

        when: 'it crashes again an hour later, with the first still in the 24 h'
        Availability.observe(doc, crashed([SEC_T0, SEC_T0 + 3600L]), STOPPED, Q, null, T0 + 3 * Q)
        Availability.observe(doc, crashed([SEC_T0, SEC_T0 + 3600L]), STOPPED, Q, null, T0 + 4 * Q)
        Map f = Availability.figures(doc, key, T0 + 4 * Q)
        Map t = doc.months[key].t['4'] as Map

        then:
        f.vmStops == 2
        f.tenants.find { it.name == 'Initech' }.vmStops == 2
        t.list*.at == [T0, T0 + 3600_000L]
    }

    def 'two crashes between two checks are two, and a reload changes nothing, as the row holds when it last stopped'() {
        given:
        Map doc = fresh()
        String key = Availability.monthKey(T0)

        when:
        Availability.observe(doc, crashed([SEC_T0, SEC_T0 + 600L]), STOPPED, Q, null, T0 + Q)
        Map back = new JsonSlurper().parseText(JsonOutput.toJson(doc)) as Map
        Availability.observe(back, crashed([SEC_T0, SEC_T0 + 600L]), STOPPED, Q, null, T0 + 2 * Q)

        then:
        Availability.figures(back, key, T0 + 2 * Q).vmStops == 2
    }

    def 'the kernel and libvirt reporting one kill seconds apart are one stop'() {
        given:
        Map doc = fresh()

        when:
        Availability.observe(doc, crashed([SEC_T0 + 2L], [[at: SEC_T0, vm: 'initech-db1', qemu: true, comm: 'qemu-system-x86']]), STOPPED, Q, null, T0 + Q)

        then:
        Availability.figures(doc, Availability.monthKey(T0), T0 + Q).vmStops == 1
    }

    def 'a kill whose line has no time counts once, when the VM first appears'() {
        given:
        Map doc = fresh()
        List<Map> kill = [[at: null, vm: 'initech-db1', qemu: true, comm: 'qemu-system-x86']]

        when:
        Availability.observe(doc, crashed([], kill), STOPPED, Q, null, T0 + Q)
        Availability.observe(doc, crashed([], kill), STOPPED, Q, null, T0 + 2 * Q)
        Map t = doc.months[Availability.monthKey(T0)].t['4'] as Map

        then:
        Availability.figures(doc, Availability.monthKey(T0), T0 + 2 * Q).vmStops == 1
        t.list*.at == [T0 + Q]
    }

    def 'a row from before this kept when it first saw the stop, which is later than the stop, so that stop is not counted again'() {
        given:
        Map doc = fresh()
        Availability.observe(doc, crashed([SEC_T0]), STOPPED, Q, null, T0 + Q)
        String stored = (doc.stops as Map).keySet()[0]
        (doc.stops as Map)[stored] = T0 + Q   // how 0.3 and the first builds of this one held it

        when:
        Availability.observe(doc, crashed([SEC_T0]), STOPPED, Q, null, T0 + 2 * Q)

        then:
        Availability.figures(doc, Availability.monthKey(T0), T0 + 2 * Q).vmStops == 1
    }

    def 'a silent host going quiet does not re-count its crash when it returns, nor does a VM-06 that is not checked'() {
        given:
        Map doc = fresh()
        List<HealthChecker.Result> crash = [result('VM-06', Status.WARN, 'initech-db1 crashed')]
        HealthChecker.Input partial = input([host('hvm-node01', answered()), host('hvm-node02', new HostFacts())], tenantVms())

        when:
        Availability.observe(doc, healthyRun(), crash, Q, null, T0)
        Availability.observe(doc, partial, [], Q, null, T0 + Q)
        Availability.observe(doc, healthyRun(), [result('VM-06', Status.NOT_CHECKED)], Q, null, T0 + 2 * Q)
        Availability.observe(doc, healthyRun(), crash, Q, null, T0 + 3 * Q)

        then:
        Availability.figures(doc, Availability.monthKey(T0), T0 + 3 * Q).vmStops == 1
    }

    def 'a crash of a VM Morpheus does not know counts for the cluster and no tenant, and its text goes nowhere'() {
        given:
        Map doc = fresh()

        when:
        Availability.observe(doc, healthyRun(), [result('VM-06', Status.WARN, 'stranger-vm crashed on hvm-node01')], Q, null, T0)
        Map f = Availability.figures(doc, Availability.monthKey(T0), T0)

        then:
        f.vmStops == 1
        f.tenants.every { it.vmStops == 0 }
        !JsonOutput.toJson(doc).contains('stranger-vm')
        !JsonOutput.toJson(doc).contains('hvm-node01')
    }

    def 'a VM named inside a longer word is not that VM'() {
        given:
        Map doc = fresh()
        List<Map> vms = [[name: 'db1', label: 'db1', tenant: 'Initech', tenantId: 4L, power: 'on']]

        when:
        Availability.observe(doc, healthyRun(vms), [result('VM-06', Status.WARN, 'mydb1-copy crashed')], Q, null, T0)

        then:
        Availability.figures(doc, Availability.monthKey(T0), T0).tenants[0].vmStops == 0
    }

    // ------------------------------------------------------------------ availability: storage

    def 'a freeze is written when it starts, so a reload mid-way loses nothing, and it closes after'() {
        given:
        MorpheusContext m = morpheus()
        Availability.afterRun(m, 27L, healthyRun(), [], [:], T0)

        when:
        Availability.freezeStarted(m, 27L, T0 + MIN)

        then:
        (new JsonSlurper().parseText(store.rows[0].rawData) as Map).open.at == T0 + MIN

        when: 'the plugin reloads'
        Availability.forget()
        Availability.afterRun(m, 27L, frozenRun(), [], [:], T0 + 3 * MIN)
        Availability.freezeEnded(m, 27L, T0 + 11 * MIN)
        Map f = Availability.forCluster(m, 27L, null, T0 + 11 * MIN)

        then:
        f.frozenMinutes == 10.0
        f.tenants.find { it.name == 'Initech' }.frozenMinutes == 10.0
        store.rows.size() == 1
    }

    def 'a second start from the watch is no second write, and an end with none open is no write'() {
        given:
        MorpheusContext m = morpheus()
        Availability.freezeEnded(m, 27L, T0)
        int none = store.creates
        Availability.freezeStarted(m, 27L, T0)
        int one = store.creates
        Availability.freezeStarted(m, 27L, T0 + MIN)

        expect:
        none == 0
        one == 1
        store.creates == 1
    }

    def 'a check reads no row and writes about hourly, or every 15 minutes during a freeze: at 200 clusters this must stay small'() {
        given:
        MorpheusContext m = morpheus()
        Availability.afterRun(m, 27L, healthyRun(), [], [:], T0)
        int reads = store.reads, writes = store.creates

        when:
        (1..11).each { Availability.afterRun(m, 27L, healthyRun(), [], [:], T0 + it * Q) }

        then:
        store.reads == reads
        store.creates == writes

        when:
        Availability.afterRun(m, 27L, healthyRun(), [], [:], T0 + 12 * Q)

        then:
        store.creates == writes + 1

        when:
        Availability.afterRun(m, 27L, frozenRun(), [], [:], T0 + 13 * Q)
        int frozenAt = store.creates
        (14..15).each { Availability.afterRun(m, 27L, frozenRun(), [], [:], T0 + it * Q) }
        int soon = store.creates
        Availability.afterRun(m, 27L, frozenRun(), [], [:], T0 + 16 * Q)

        then:
        frozenAt == writes + 2
        soon == frozenAt
        store.creates == frozenAt + 1
    }

    def 'a store that cannot be read is said, never shown as no freezes, and nothing is written over it'() {
        given:
        MorpheusContext m = morpheus()
        Availability.afterRun(m, 27L, healthyRun(), [], [:], T0)
        String before = store.rows[0].rawData
        Availability.forget()
        store.failReads = true

        when:
        Availability.afterRun(m, 27L, frozenRun(), [], [:], T0 + Q)
        Map f = Availability.forCluster(m, 27L, null, T0 + Q)

        then:
        f.error
        f.frozenMinutes == null
        store.rows.size() == 1
        store.rows[0].rawData == before
    }

    def 'a store that cannot be written keeps the freeze in memory and catches up at the next check'() {
        given:
        MorpheusContext m = morpheus()
        store.failCreates = true

        when:
        Availability.freezeStarted(m, 27L, T0)

        then:
        noExceptionThrown()
        Availability.ERRORS[27L]
        store.rows == []

        when:
        store.failCreates = false
        Availability.afterRun(m, 27L, frozenRun(), [], [:], T0 + MIN)

        then:
        store.rows.size() == 1
        (new JsonSlurper().parseText(store.rows[0].rawData) as Map).open.at == T0
        !Availability.ERRORS[27L]
    }

    def 'a document survives JSON: numbers come back as they were and counting goes on'() {
        given:
        Map doc = fresh()
        Availability.observe(doc, healthyRun(), [result('VM-06', Status.WARN, 'initech-db1')], Q, null, T0)
        Availability.openFreeze(doc, T0, T0)

        when:
        Map back = new JsonSlurper().parseText(Availability.encode(doc)) as Map
        Availability.closeFreeze(back, T0 + 10 * MIN, true)
        Availability.observe(back, healthyRun(), [result('VM-06', Status.WARN, 'initech-db1'), result('VM-06', Status.WARN, 'globex-web1')], Q, null, T0 + 11 * MIN)
        Map f = Availability.figures(back, Availability.monthKey(T0), T0 + 11 * MIN)

        then:
        f.frozenMinutes == 10.0
        f.tenants.find { it.name == 'Initech' }.vmStops == 1
        f.tenants.find { it.name == 'Globex' }.vmStops == 1
        f.tenants.find { it.name == 'Initech' }.frozenMinutes == 10.0
    }

    def 'at most 13 months, under the cap: old months go first, and then lists of VMs'() {
        given:
        Map doc = fresh()
        Map huge = (1..400).collectEntries { [(it.toString()): [n: "A tenant with quite a long name indeed ${it}".toString(), sec: 1L, vms: 1, stops: 20, list: (1..20).collect { [vm: 'a-vm-with-a-long-name', at: T0] }]] }
        (1..20).each { int i -> doc.months[String.format('20%02d-01', i)] = [watched: 1L, frozen: 1L, t: [:]] }
        doc.months['2040-01'] = [watched: 1L, frozen: 1L, t: huge]

        when:
        String json = Availability.encode(doc)
        Map back = new JsonSlurper().parseText(json) as Map

        then:
        json.getBytes('UTF-8').length <= FindingHistory.MAX_BYTES
        (back.months['2040-01'].t['1'] as Map).stops == 20
        (back.months['2040-01'].t['1'] as Map).list == []
        back.months.containsKey('2040-01')
        back.months.size() <= 13
    }

    def 'the row keeps no host, storage or finding text: only tenants and VMs, which is the point of it'() {
        given:
        Map doc = fresh()

        when:
        Availability.observe(doc, input([host('hvm-node01', answered(usage: [[target: '/mnt/datastore-a', used: 1L, size: 2L]])), host('hvm-node02', frozen())], tenantVms()), [result('VM-06', Status.WARN, 'initech-db1 crashed on hvm-node01', 'hvm-node01', 'VM on hvm-node01 crashed')], Q, null, T0)
        String json = JsonOutput.toJson(doc)

        then:
        !json.contains('hvm-node')
        !json.contains('datastore-a')
        !json.contains('crashed')
    }

    // ------------------------------------------------------------------ the per-tenant CSV

    /** A cluster with two customers, a freeze and a crash each, through the same path a real one takes. */
    private Map<Long, Map> history() {
        Map doc = Availability.doc(morpheus(), 27L, T0)
        Availability.observe(doc, healthyRun(), [], Q, null, T0)
        Availability.observe(doc, frozenRun(), [], Q, null, T0 + Q)
        Availability.observe(doc, healthyRun(), [result('VM-06', Status.WARN, 'initech-db1 and globex-web1 crashed on hvm-node01, storage /mnt/datastore-a', 'hvm-node01', 'VM crashed on hvm-node01')], Q, null, T0 + 3 * Q)
        [27L: doc]
    }

    private static final List<String> NOT_FOR_INITECH = ['globex', 'acme', 'hvm-node', 'datastore', '/mnt', 'cluster-x', 'portal', 'stranger', 'master']

    def 'a tenant\'s CSV holds its own figures and VMs, and no other tenant, host, storage or cluster name, in any part of what the route returns'() {
        when:
        Map r = Availability.tenantReport(history(), 4L, Availability.monthKey(T0), T0 + 3 * Q)
        String csv = (r.csv as String).toLowerCase()
        String all = JsonOutput.toJson(r).toLowerCase()

        then:
        csv.contains('initech')
        csv.contains('initech db 1')
        NOT_FOR_INITECH.every { !csv.contains(it) }
        NOT_FOR_INITECH.every { !all.contains(it) }
        !all.contains('hvm')
        !all.contains('initech db 2')     // it did not crash

        and: 'and the other way about'
        Map g = Availability.tenantReport(history(), 3L, Availability.monthKey(T0), T0 + 3 * Q)
        !JsonOutput.toJson(g).toLowerCase().contains('initech')
    }

    def 'the CSV has its figures, says what it measures, and shows what it did not observe as unknown'() {
        given:
        Map docs = history()
        Availability.observe(docs[27L], healthyRun(), [], Q, null, T0 + 3 * Q + 300 * MIN)

        when:
        Map r = Availability.tenantReport(docs, 4L, Availability.monthKey(T0), T0 + 3 * Q + 300 * MIN)
        List<String> lines = (r.csv as String).split('\r\n') as List

        then:
        lines[0] == Availability.CSV_COLUMNS.join(',')
        r.clusters[0].cluster == 'cluster-27'
        r.clusters[0].frozenMinutes == 10.0
        r.clusters[0].vmStops == 1
        r.clusters[0].runningVms == 2
        r.clusters[0].unknownMinutes == 300.0
        lines[1].startsWith("summary,Initech,${Availability.monthKey(T0)},cluster-27,10.0,2,1,".toString())
        lines.find { it.startsWith('vm_stop,') }.contains('Initech DB 1')
        (r.csv as String).contains('never as up')
        r.fileBase == "availability-Initech-${Availability.monthKey(T0)}".toString()
    }

    def 'a tenant name that would run as a formula cannot, in the CSV or the file name'() {
        given:
        Map doc = fresh()
        Availability.observe(doc, healthyRun([[name: 'a', tenant: '=HYPERLINK("x")', tenantId: 9L, power: 'on']]), [], Q, null, T0)

        when:
        Map r = Availability.tenantReport([27L: doc], 9L, Availability.monthKey(T0), T0)

        then:
        (r.csv as String).contains('"\'=HYPERLINK(""x"")"')
        r.fileBase ==~ /[A-Za-z0-9._-]+/
    }

    def 'a tenant with nothing on record gets nothing, not a report of zeros'() {
        expect:
        Availability.tenantReport(history(), 777L, Availability.monthKey(T0), T0).clusters == []
    }

    def 'across clusters it adds up that tenant alone, and takes no name from a cluster it is not on'() {
        given:
        Map one = fresh()
        Map two = fresh()
        Map other = fresh()
        Availability.observe(one, healthyRun(), [], Q, null, T0)
        Availability.observe(two, healthyRun(), [], Q, null, T0)
        Availability.observe(other, healthyRun([[name: 'x', tenant: 'Globex', tenantId: 3L, power: 'on']]), [], Q, null, T0)

        when:
        Map r = Availability.tenantReport([27L: one, 28L: two, 29L: other], 4L, Availability.monthKey(T0), T0)

        then:
        r.clusters*.cluster == ['cluster-27', 'cluster-28']
    }

    // ------------------------------------------------------------------ the routes

    @Unroll
    def 'a customer whose role grants everything is refused #route, and nothing leaves'() {
        given:
        MorpheusContext m = morpheus()
        Availability.afterRun(m, 27L, healthyRun(), [], [:], T0)
        def c = new HealthController(new ClusterHealthPlugin(), m)

        when:
        def r = c."${route}"(asUser(INITECH, params))

        then:
        r.status == 403
        r.data.data == null
        !JsonOutput.toJson(r.data).contains('Globex')
        !JsonOutput.toJson(r.data).contains('cluster-x')

        where:
        route                | params
        'availability'       | [clusterId: '27']
        'availabilityExport' | [clusterId: '27', tenantId: '4']
        'availabilityExport' | [tenantId: '4']
        'fleetInsights'      | [:]
    }

    def 'the routes need the role permission, and read is enough, as none changes anything'() {
        when:
        Map routes = new HealthController(null, null).routes.collectEntries { [(it.method): it.permissions*.code] }

        then:
        ['fleetInsights', 'availability', 'availabilityExport'].every { routes[it] == [HealthController.PERM] }
    }

    def 'the cluster\'s owner gets its own cluster\'s figures and a tenant\'s file; only the master tenant gets across clusters'() {
        given:
        MorpheusContext m = morpheus()
        Availability.afterRun(m, 27L, healthyRun(), [], [:], System.currentTimeMillis())
        def c = new HealthController(new ClusterHealthPlugin(), m)

        expect:
        c.availability(asUser(ACME)).data.data.tenants*.name.containsAll(['Initech', 'Globex'])
        c.availabilityExport(asUser(ACME, [clusterId: '27', tenantId: '4'])).data.data.tenant == 'Initech'
        c.availabilityExport(asUser(ACME, [tenantId: '4'])).status == 403
        c.availabilityExport(asUser(MASTER, [tenantId: '4'])).data.data.clusters*.cluster == ['cluster-27']
        c.fleetInsights(asUser(ACME, [:])).status == 403
        c.fleetInsights(asUser(MASTER, [:])).data.data.availability.clusters*.clusterId == [27L]
    }

    @Unroll
    def 'an export with #params answers #status and nothing else'() {
        given:
        MorpheusContext m = morpheus()
        Availability.afterRun(m, 27L, healthyRun(), [], [:], System.currentTimeMillis())
        def c = new HealthController(new ClusterHealthPlugin(), m)

        when:
        def r = c.availabilityExport(asUser(MASTER, [clusterId: '27'] + params))

        then:
        r.status == status
        r.data.data == null

        where:
        params                                 | status
        [:]                                    | 400
        [tenantId: 'x']                        | 400
        [tenantId: '4', month: 'soon']         | 400
        [tenantId: '4', month: '2030-13']      | 400
        [tenantId: '777']                      | 404
        [tenantId: '4', month: '2001-01']      | 404
    }

    def 'an export whose figures cannot be read fails, rather than hand a customer an empty record'() {
        given:
        MorpheusContext m = morpheus()
        Availability.afterRun(m, 27L, healthyRun(), [], [:], T0)
        Availability.forget()
        store.failReads = true
        def c = new HealthController(new ClusterHealthPlugin(), m)

        when:
        def r = c.availabilityExport(asUser(MASTER, [clusterId: '27', tenantId: '4']))

        then:
        r.status != 200
        r.data.data == null
    }

    // ------------------------------------------------------------------ the monitor's hook

    def 'a whole check fills the forecast, the figures and the correlation, none of it on the tab\'s findings as FLT-01, and a store outage fails none of it'() {
        given:
        def svc = store.service()
        ComputeServerGroup cluster = new ComputeServerGroup(id: 27L, name: 'cluster-x', type: new ComputeServerGroupType(code: 'mvm-cluster'))
        def clusterService = Stub(MorpheusServices.getMethod('getCluster').returnType) { get(_) >> cluster; list(_) >> [cluster] }
        def services = Stub(MorpheusServices) { getOperationData() >> svc; getCluster() >> clusterService }
        MorpheusContext m = Stub(MorpheusContext) { getServices() >> services }

        when:
        Map v = HealthMonitor.run(m, new MonitorSpec.FakeContext(m), [:], 'full', false)

        then:
        v.results.any { it.id == 'CAP-03' && it.status == 'NOT_CHECKED' }
        !v.results.any { it.id == 'FLT-01' }
        v.historySince != null
        store.rows*.category.containsAll([FindingHistory.CATEGORY, CapacityTrend.CATEGORY, Availability.CATEGORY])
        Availability.forCluster(m, 27L, null).observedMinutes != null

        when:
        store.failCreates = true
        store.failReads = true
        CapacityTrend.forget()
        Availability.forget()
        Map again = HealthMonitor.run(m, new MonitorSpec.FakeContext(m), [:], 'quick', false)

        then:
        noExceptionThrown()
        again.overall != null
        again.results.find { it.id == 'CAP-03' }.status == 'NOT_CHECKED'
        again.results.find { it.id == 'CAP-03' }.observed.contains('could not be read')
    }
}
