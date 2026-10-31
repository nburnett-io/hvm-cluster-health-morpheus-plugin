package org.hvmhealth

import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.MorpheusServices
import com.morpheusdata.core.synchronous.MorpheusSynchronousOperationDataService
import com.morpheusdata.model.ComputeServerGroup
import com.morpheusdata.model.ComputeServerGroupType
import com.morpheusdata.model.OperationData
import org.hvmhealth.checks.HealthChecker
import org.hvmhealth.checks.HealthChecker.Severity
import org.hvmhealth.checks.HealthChecker.Status
import org.hvmhealth.model.HostFacts
import org.hvmhealth.morpheus.FindingHistory
import org.hvmhealth.morpheus.HealthContext
import org.hvmhealth.morpheus.HealthMonitor
import spock.lang.Specification

class HistorySpec extends Specification {
    static final long T0 = 1_800_000_000_000L
    static final long MIN = 60_000L
    static final long DAY = 24 * 60 * MIN

    private static HealthChecker.Result finding(String id = 'NET-01', String host = 'hvm-node01', Status st = Status.FAIL, String title = 'Bond members up: bond0') {
        new HealthChecker.Result(id, title, Severity.HIGH, st, host, 'observed text', 'expected text', 'plugin')
    }

    private static final Map ALL_ANSWER = [hosts: ['hvm-node01', 'hvm-node02'] as Set, answered: ['hvm-node01', 'hvm-node02'] as Set, fullKnown: true]

    private static Map only(Map doc) {
        assert (doc.episodes as List).size() == 1
        (doc.episodes as List<Map>)[0]
    }

    def 'a finding keeps the time it first appeared across runs, and gets a time when it clears'() {
        given:
        Map doc = FindingHistory.empty(T0)

        when:
        boolean first = FindingHistory.apply(doc, [finding()], ALL_ANSWER, [overall: 'DEGRADED'], T0)
        boolean again = FindingHistory.apply(doc, [finding()], ALL_ANSWER, [overall: 'DEGRADED'], T0 + 5 * MIN)

        then:
        first
        !again
        only(doc).first == T0
        only(doc).last == T0 + 5 * MIN
        only(doc).cleared == null

        when:
        boolean cleared = FindingHistory.apply(doc, [], ALL_ANSWER, [overall: 'HEALTHY'], T0 + 10 * MIN)

        then:
        cleared
        only(doc).cleared == T0 + 10 * MIN
    }

    def 'one host answering for its own finding does not clear another host finding'() {
        given:
        Map doc = FindingHistory.empty(T0)
        FindingHistory.apply(doc, [finding('NET-01', 'hvm-node01'), finding('NET-01', 'hvm-node02')], ALL_ANSWER, [:], T0)

        when:
        FindingHistory.apply(doc, [finding('NET-01', 'hvm-node02')], ALL_ANSWER, [:], T0 + MIN)

        then:
        (doc.episodes as List<Map>).find { it.host == 'hvm-node01' }.cleared == T0 + MIN
        (doc.episodes as List<Map>).find { it.host == 'hvm-node02' }.cleared == null
    }

    def 'a finding that a run could not have seen is not cleared by that run'() {
        given:
        Map doc = FindingHistory.empty(T0)
        FindingHistory.apply(doc, [finding(id, host)], ALL_ANSWER, [:], T0)

        when:
        FindingHistory.apply(doc, [], cover, [:], T0 + MIN)

        then:
        (only(doc).cleared != null) == clears

        where:
        id       | host         | cover                                                                                       | clears
        'NET-01' | 'hvm-node01' | [hosts: ['hvm-node01', 'hvm-node02'] as Set, answered: ['hvm-node02'] as Set, fullKnown: true] | false
        'CLU-05' | null         | [hosts: ['hvm-node01', 'hvm-node02'] as Set, answered: ['hvm-node02'] as Set, fullKnown: true] | false
        'CLU-05' | null         | ALL_ANSWER                                                                                  | true
        'NET-01' | 'hvm-node01' | [hosts: [] as Set, answered: [] as Set, fullKnown: true]                                    | false
        'NET-01' | 'hvm-node01' | [hosts: ['hvm-node02'] as Set, answered: ['hvm-node02'] as Set, fullKnown: true]            | true
        'HW-01'  | 'hvm-node01' | [hosts: ['hvm-node01'] as Set, answered: ['hvm-node01'] as Set, fullKnown: false]           | false
        'HW-01'  | 'hvm-node01' | [hosts: ['hvm-node01'] as Set, answered: ['hvm-node01'] as Set, fullKnown: true]            | true
    }

    def 'a finding now not checked stays open: that says nothing either way'() {
        given:
        Map doc = FindingHistory.empty(T0)
        FindingHistory.apply(doc, [finding()], ALL_ANSWER, [:], T0)

        when:
        FindingHistory.apply(doc, [finding('NET-01', 'hvm-node01', Status.NOT_CHECKED)], ALL_ANSWER, [:], T0 + MIN)

        then:
        only(doc).cleared == null
    }

    def 'a finding back within the hour is the same episode; later it is a new one'() {
        given:
        Map doc = FindingHistory.empty(T0)
        FindingHistory.apply(doc, [finding()], ALL_ANSWER, [:], T0)
        FindingHistory.apply(doc, [], ALL_ANSWER, [:], T0 + MIN)

        when:
        FindingHistory.apply(doc, [finding()], ALL_ANSWER, [:], T0 + 30 * MIN)

        then:
        only(doc).first == T0
        only(doc).cleared == null
        only(doc).returns == 1

        when:
        FindingHistory.apply(doc, [], ALL_ANSWER, [:], T0 + 40 * MIN)
        FindingHistory.apply(doc, [finding()], ALL_ANSWER, [:], T0 + 200 * MIN)

        then:
        (doc.episodes as List).size() == 2
        (doc.episodes as List<Map>).find { it.cleared == null }.first == T0 + 200 * MIN
    }

    def 'a warning that becomes a failure stays one episode, and remembers the worst'() {
        given:
        Map doc = FindingHistory.empty(T0)
        FindingHistory.apply(doc, [finding('NET-01', 'hvm-node01', Status.WARN)], ALL_ANSWER, [:], T0)

        when:
        boolean up = FindingHistory.apply(doc, [finding('NET-01', 'hvm-node01', Status.FAIL)], ALL_ANSWER, [:], T0 + MIN)
        FindingHistory.apply(doc, [finding('NET-01', 'hvm-node01', Status.WARN)], ALL_ANSWER, [:], T0 + 2 * MIN)

        then:
        up
        only(doc).first == T0
        only(doc).status == 'WARN'
        only(doc).worst == 'FAIL'
    }

    def 'cleared findings go after a year; open ones never do'() {
        given:
        Map doc = FindingHistory.empty(T0)
        FindingHistory.apply(doc, [finding('NET-01', 'hvm-node01'), finding('NET-01', 'hvm-node02')], ALL_ANSWER, [:], T0)
        FindingHistory.apply(doc, [finding('NET-01', 'hvm-node02')], ALL_ANSWER, [:], T0 + DAY)

        when: 'a day short of the retention period'
        FindingHistory.prune(doc, T0 + DAY + FindingHistory.RETENTION_MS - DAY)

        then:
        (doc.episodes as List).size() == 2

        when:
        boolean changed = FindingHistory.prune(doc, T0 + 2 * DAY + FindingHistory.RETENTION_MS)

        then:
        changed
        only(doc).host == 'hvm-node02'
        only(doc).cleared == null
        doc.dropped == 0
    }

    private static Map episode(int n, Long cleared, String title = 'A title') {
        [k: "k${n}".toString(), id: 'NET-01', title: title, host: 'hvm-node01', severity: 'HIGH', status: 'FAIL', worst: 'FAIL', first: T0 + n, last: T0 + n, cleared: cleared, returns: 0]
    }

    def 'over the episode cap the oldest cleared go, and are counted'() {
        given:
        Map doc = FindingHistory.empty(T0)
        doc.episodes = (1..FindingHistory.MAX_EPISODES + 5).collect { episode(it, T0 + it) }
        (doc.episodes as List<Map>)[0].cleared = null   // the oldest, but open

        when:
        FindingHistory.prune(doc, T0 + DAY)

        then:
        (doc.episodes as List).size() == FindingHistory.MAX_EPISODES
        doc.dropped == 5
        (doc.episodes as List<Map>)[0].k == 'k1'
        !(doc.episodes as List<Map>).any { it.k == 'k2' }
    }

    def 'what is stored stays under the size limit, dropping the oldest cleared first'() {
        given:
        Map doc = FindingHistory.empty(T0)
        doc.episodes = (1..400).collect { episode(it, it == 1 ? null : T0 + it, 'é' * 150) }

        when:
        String json = FindingHistory.encode(doc)

        then:
        json.getBytes('UTF-8').length <= FindingHistory.MAX_BYTES
        doc.dropped > 0
        !doc.overflow
        (doc.episodes as List<Map>).any { it.k == 'k1' }
        (doc.episodes as List<Map>).any { it.k == 'k400' }
    }

    def 'open findings alone over the limit: the newest wait, and the report can say so'() {
        given:
        Map doc = FindingHistory.empty(T0)
        doc.episodes = (1..400).collect { episode(it, null, 'é' * 150) }

        when:
        String json = FindingHistory.encode(doc)

        then:
        json.getBytes('UTF-8').length <= FindingHistory.MAX_BYTES
        doc.overflow == true
        (doc.episodes as List<Map>).any { it.k == 'k1' }
        !(doc.episodes as List<Map>).any { it.k == 'k400' }
    }

    def 'nothing about a tenant is stored: no observed text, and no network name in a title'() {
        given:
        Map doc = FindingHistory.empty(T0)
        HealthChecker.Result net = new HealthChecker.Result('NET-11', 'VLAN 300 (acme-prod) carries traffic on every host', Severity.MEDIUM, Status.WARN, null, 'no traffic', 'heard', 'plugin')
        net.impact = '3 running VM(s): Acme (3)'

        when:
        FindingHistory.apply(doc, [net], ALL_ANSWER, [overall: 'WARNING'], T0)
        String json = FindingHistory.encode(doc)

        then:
        only(doc).title == 'VLAN 300 carries traffic on every host'
        !json.contains('acme')
        !json.contains('Acme')
        !json.contains('no traffic')
    }

    def 'a VM\'s name is not stored from NET-12 or NET-13, and each VM stays its own finding'() {
        given:
        Map doc = FindingHistory.empty(T0)
        Closure<HealthChecker.Result> port = { String vm -> new HealthChecker.Result(id, "${title}: ${vm}".toString(), Severity.HIGH, Status.WARN, 'hvm-node01', "observed ${vm}", 'x', 'plugin') }

        when:
        FindingHistory.apply(doc, [port('initech-db1'), port('initech-db2')], ALL_ANSWER, [overall: 'WARNING'], T0)
        String json = FindingHistory.encode(doc)

        then:
        (doc.episodes as List<Map>).size() == 2
        (doc.episodes as List<Map>)*.title.unique() == [title]
        !json.contains('initech')
        FindingHistory.safeTitle(id, "${title}: initech-db1".toString()) == title

        where:
        id       | title
        'NET-12' | 'VM port on the VLAN Morpheus says'
        'NET-13' | 'No unexpected trunk'
    }

    def 'a title with no VM in it, such as a host\'s own row, is left alone'() {
        expect:
        FindingHistory.safeTitle('NET-13', 'No unexpected trunk') == 'No unexpected trunk'
        FindingHistory.safeTitle('STO-01', 'Multipath paths healthy: 3624a0000000000000000') == 'Multipath paths healthy: 3624a0000000000000000'
    }

    def 'a renamed network does not start a new episode'() {
        expect:
        FindingHistory.key('NET-11', null, 'VLAN 300 (old) carries traffic on every host') == FindingHistory.key('NET-11', null, 'VLAN 300 (new) carries traffic on every host')
    }

    // ------------------------------------------------------------------ persistence, through a store like Morpheus's

    /** Keeps rows as Morpheus would; `limit` is a column that silently cuts long values. */
    static class Store {
        List<OperationData> rows = []
        long next = 1
        Integer limit
        boolean failReads, failCreates

        MorpheusSynchronousOperationDataService service() {
            [list: { q -> if (failReads) { throw new IllegalStateException('database gone') }; new ArrayList(rows) },
             create: { OperationData o ->
                 if (failCreates) { throw new IllegalStateException('database gone') }
                 OperationData kept = new OperationData(id: next++, category: o.category, code: o.code, refId: o.refId, refType: o.refType, refDate: o.refDate, account: o.account,
                         rawData: limit ? o.rawData.take(limit) : o.rawData)
                 rows << kept
                 kept
             },
             get: { Long id -> rows.find { it.id == id } },
             remove: { OperationData o -> rows.removeAll { it.id == o.id }; true }] as MorpheusSynchronousOperationDataService
        }
    }

    Store store = new Store()

    private MorpheusContext morpheus() {
        def svc = store.service()
        def clusters = Stub(MorpheusServices.getMethod('getCluster').returnType) { get(_) >> new ComputeServerGroup(id: 27L, name: 'cluster-x') }
        def services = Stub(MorpheusServices) { getOperationData() >> svc; getCluster() >> clusters }
        Stub(MorpheusContext) { getServices() >> services }
    }

    private HealthContext ctx() {
        new HealthContext(morpheus(), 27L)
    }

    private static HealthChecker.Input input() {
        HostFacts complete = new HostFacts(complete: true)
        new HealthChecker.Input(clusterName: 'cluster-x', hosts: [new HealthChecker.Host('hvm-node01', false, null, null, null, complete, null, null)])
    }

    private static Map view(List<HealthChecker.Result> rs, String overall = 'DEGRADED') {
        [overall: overall, at: T0, mode: 'full', layout: 'HVM 2.0', counts: [fail: rs.size(), warn: 0, pass: 3], readiness: [[area: 'Network', status: 'FAIL'], [area: 'Time', status: 'PASS']], hosts: [[frozen: false]],
         results: rs.collect { [id: it.id, title: it.title, status: it.status.name(), host: it.host] }]
    }

    def cleanup() {
        FindingHistory.ERRORS.clear()
        FindingHistory.lastPrune = 0
        HealthMonitor.LAST.remove(27L)
    }

    def 'a real run stamps first seen, and the all-clusters view still knows the cluster after a reload'() {
        given:
        def svc = store.service()
        ComputeServerGroup cluster = new ComputeServerGroup(id: 27L, name: 'cluster-x', type: new ComputeServerGroupType(code: 'mvm-cluster'))
        def clusters = Stub(MorpheusServices.getMethod('getCluster').returnType) { get(_) >> cluster; list(_) >> [cluster] }
        def services = Stub(MorpheusServices) { getOperationData() >> svc; getCluster() >> clusters }
        MorpheusContext m = Stub(MorpheusContext) { getServices() >> services }

        when:
        Map v = HealthMonitor.run(m, new MonitorSpec.FakeContext(m), [:], 'full', false)
        List<Map> problems = v.results.findAll { it.status in ['FAIL', 'WARN'] }

        then:
        problems
        problems.every { it.firstSeen }
        !v.results.find { it.status == 'PASS' }.firstSeen
        !v.historyError
        store.rows.size() == 1
        !store.rows[0].rawData.contains('running VM')

        when: 'the plugin reloads, which empties what is in memory'
        HealthMonitor.LAST.remove(27L)
        List<Map> rows = HealthMonitor.fleet(m)

        then:
        rows[0].lastKnown == true
        rows[0].overall == v.overall
        rows[0].at == v.at
        rows[0].counts.fail == v.counts.fail
    }

    def 'first seen survives a plugin reload: nothing is held in memory between runs'() {
        given:
        MorpheusContext m = morpheus()
        Map v1 = view([finding()])

        when:
        FindingHistory.record(m, ctx(), input(), [finding()], v1, true, T0)

        then:
        store.rows.size() == 1
        v1.results[0].firstSeen == T0

        when: 'a new plugin instance reads the same store'
        MorpheusContext reloaded = morpheus()
        Map v2 = view([finding()])
        FindingHistory.record(reloaded, ctx(), input(), [finding()], v2, true, T0 + DAY)

        then:
        v2.results[0].firstSeen == T0
        FindingHistory.read(reloaded, 27L).since == T0
        FindingHistory.lastKnown(reloaded, 27L).overall == 'DEGRADED'
        FindingHistory.lastKnown(reloaded, 27L).notReady == ['Network']
    }

    def 'a run that changes nothing saves at most every 15 minutes, replacing the row'() {
        given:
        MorpheusContext m = morpheus()
        FindingHistory.record(m, ctx(), input(), [finding()], view([finding()]), true, T0)
        Long firstRow = store.rows[0].id

        when:
        FindingHistory.record(m, ctx(), input(), [finding()], view([finding()]), true, T0 + 5 * MIN)

        then:
        store.rows*.id == [firstRow]

        when:
        FindingHistory.record(m, ctx(), input(), [finding()], view([finding()]), true, T0 + 16 * MIN)

        then:
        store.rows.size() == 1
        store.rows[0].id != firstRow
    }

    def 'an unreadable store writes nothing, so a blip cannot replace the history with an empty one'() {
        given:
        MorpheusContext m = morpheus()
        FindingHistory.record(m, ctx(), input(), [finding()], view([finding()]), true, T0)
        String before = store.rows[0].rawData
        store.failReads = true
        Map v = view([finding()])

        when:
        FindingHistory.record(m, ctx(), input(), [finding(), finding('NET-02')], v, true, T0 + MIN)

        then:
        noExceptionThrown()
        store.rows[0].rawData == before
        FindingHistory.ERRORS[27L].contains('database gone')
        FindingHistory.lastKnown(m, 27L) == null

        when: 'it comes back'
        store.failReads = false
        FindingHistory.record(m, ctx(), input(), [finding()], view([finding()]), true, T0 + 2 * MIN)

        then:
        !FindingHistory.ERRORS.containsKey(27L)
    }

    def 'a failed save never fails the check, and is reported'() {
        given:
        store.failCreates = true

        when:
        FindingHistory.record(morpheus(), ctx(), input(), [finding()], view([finding()]), true, T0)

        then:
        noExceptionThrown()
        FindingHistory.ERRORS[27L].contains('database gone')
    }

    def 'a column that cuts the record is caught by reading it back, and the old row stays'() {
        given:
        MorpheusContext m = morpheus()
        FindingHistory.record(m, ctx(), input(), [finding()], view([finding()]), true, T0)
        String good = store.rows[0].rawData
        store.limit = 100

        when:
        FindingHistory.record(m, ctx(), input(), [finding(), finding('NET-02')], view([finding(), finding('NET-02')]), true, T0 + MIN)

        then:
        store.rows.size() == 1
        store.rows[0].rawData == good
        FindingHistory.ERRORS[27L].contains('did not keep')
    }

    def 'a row that does not parse is replaced, not fatal'() {
        given:
        store.rows << new OperationData(id: 9L, category: FindingHistory.CATEGORY, refId: '27', rawData: '{"cut off')
        MorpheusContext m = morpheus()

        when:
        FindingHistory.record(m, ctx(), input(), [finding()], view([finding()]), true, T0)

        then:
        store.rows.size() == 1
        store.rows[0].id != 9L
        FindingHistory.read(m, 27L).episodes.size() == 1
    }

    def 'another cluster and another category are not mistaken for this one, even if the query filter were ignored'() {
        given:
        store.rows << new OperationData(id: 9L, category: FindingHistory.CATEGORY, refId: '28', rawData: '{"savedAt":5,"episodes":[]}')
        store.rows << new OperationData(id: 10L, category: 'someone-else', refId: '27', rawData: '{"savedAt":5,"episodes":[]}')
        MorpheusContext m = morpheus()

        when:
        FindingHistory.record(m, ctx(), input(), [finding()], view([finding()]), true, T0)

        then:
        store.rows*.id.containsAll([9L, 10L])
        store.rows.size() == 3
    }

    def 'the history of a deleted cluster goes after the retention period; a live one, or a recent one, stays'() {
        given:
        long now = T0 + 2 * FindingHistory.RETENTION_MS
        long old = now - FindingHistory.RETENTION_MS - DAY
        store.rows << new OperationData(id: 1L, category: FindingHistory.CATEGORY, refId: '27', refDate: new Date(old), rawData: '{}')
        store.rows << new OperationData(id: 2L, category: FindingHistory.CATEGORY, refId: '99', refDate: new Date(old), rawData: '{}')
        store.rows << new OperationData(id: 3L, category: FindingHistory.CATEGORY, refId: '98', refDate: new Date(now - DAY), rawData: '{}')
        def svc = store.service()
        def clusters = Stub(MorpheusServices.getMethod('getCluster').returnType) { list(_) >> [new ComputeServerGroup(id: 27L)] }
        def services = Stub(MorpheusServices) { getOperationData() >> svc; getCluster() >> clusters }
        MorpheusContext m = Stub(MorpheusContext) { getServices() >> services }

        when:
        FindingHistory.pruneOrphans(m, now)

        then:
        store.rows*.id == [1L, 3L]
    }

    def 'Morpheus listing no clusters removes nothing'() {
        given:
        long now = T0 + 3 * FindingHistory.RETENTION_MS
        store.rows << new OperationData(id: 2L, category: FindingHistory.CATEGORY, refId: '99', refDate: new Date(T0), rawData: '{}')
        def svc = store.service()
        def clusters = Stub(MorpheusServices.getMethod('getCluster').returnType) { list(_) >> [] }
        def services = Stub(MorpheusServices) { getOperationData() >> svc; getCluster() >> clusters }

        when:
        FindingHistory.pruneOrphans(Stub(MorpheusContext) { getServices() >> services }, now)

        then:
        store.rows*.id == [2L]
    }

    def 'a long validation error is cut to one short line for the tab'() {
        expect:
        FindingHistory.shortError(new RuntimeException('Validation failed\nfield keyValue rejected\n' + 'x' * 5000)) == 'Validation failed'
        FindingHistory.shortError(new RuntimeException('y' * 5000)).length() == 203
        FindingHistory.shortError(new RuntimeException()) == 'RuntimeException'
    }
}
