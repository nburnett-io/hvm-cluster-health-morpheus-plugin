package org.hvmhealth

import groovy.json.JsonOutput
import org.hvmhealth.checks.CheckCatalog
import org.hvmhealth.checks.HealthChecker
import org.hvmhealth.checks.HealthChecker.Result
import org.hvmhealth.checks.HealthChecker.Severity
import org.hvmhealth.checks.HealthChecker.Status
import org.hvmhealth.model.HostFacts
import org.hvmhealth.morpheus.HealthContext
import org.hvmhealth.morpheus.HealthMonitor
import org.hvmhealth.morpheus.HealthReport
import org.hvmhealth.morpheus.Impact
import org.hvmhealth.morpheus.Incidents
import org.hvmhealth.morpheus.Paging
import org.hvmhealth.standards.Layout
import org.hvmhealth.standards.Standards
import spock.lang.Specification

/** One alarm per cause, who a finding reaches, and what a report must leave out. */
class AlarmLayerSpec extends Specification {

    private static Result r(String id, String host, Status status = Status.FAIL, String title = null, Severity severity = Severity.HIGH) {
        new Result(id, title ?: "${id} title".toString(), severity, status, host, 'seen', 'wanted', 'plugin')
    }

    private static Result freeze(String host) {
        r('DLM-01', host, Status.FAIL, 'Lockspaces waiting for a node to be fenced', Severity.CRITICAL)
    }

    private static Map groupOf(List<Map> groups, String id) {
        groups.find { (it.root as Result).id == id }
    }

    private static List<Result> flat(Map g) {
        [g.root as Result] + (g.repeats as List<Result>) + (g.symptoms as List<Result>)
    }

    // ------------------------------------------------------------------ the cause table

    def 'the cause table names real checks, and a cause is never a symptom'() {
        given:
        Set<String> symptoms = Incidents.CAUSES.values().collectMany { (it.sameHost as List) + (it.anyHost as List) } as Set<String>

        expect:
        CheckCatalog.ENTRIES.keySet().containsAll(symptoms + Incidents.CAUSES.keySet())
        symptoms.intersect(Incidents.CAUSES.keySet()).isEmpty()
    }

    def 'what always pages is never folded into another alarm'() {
        given:
        Set<String> symptoms = Incidents.CAUSES.values().collectMany { (it.sameHost as List) + (it.anyHost as List) } as Set<String>

        expect:
        symptoms.intersect(Paging.ALWAYS_PAGE).isEmpty()
        Paging.ALWAYS_PAGE.containsAll(Incidents.CAUSES.keySet())
    }

    def 'a freeze explains what the design lists'() {
        expect:
        (Incidents.CAUSES['DLM-01'].sameHost + Incidents.CAUSES['DLM-01'].anyHost) == ['GFS-05', 'GFS-07', 'GFS-08', 'VM-02', 'VM-03', 'HB-01', 'MOR-01', 'MOR-02', 'MOR-03', 'PRB-01', 'STO-06'] as Set
    }

    // ------------------------------------------------------------------ grouping

    def 'a real frozen cluster is one alarm for the freeze, and every finding is still accounted for'() {
        given:
        HealthChecker.Input i = Fixture.frozen()
        List<Result> rs = new HealthChecker().check(i)

        when:
        List<Map> groups = Incidents.group(rs)
        Map freeze = groupOf(groups, 'DLM-01')

        then:
        groups.count { (it.root as Result).id == 'DLM-01' } == 1
        freeze.rootHosts == ['hvm-node02', 'hvm-node03']
        !(freeze.symptoms as List<Result>).isEmpty()
        (groups.collectMany { flat(it) }).size() == rs.count { it.status in [Status.FAIL, Status.WARN] }
        !groups.any { (it.root as Result).id == 'VM-02' }
    }

    def 'a symptom on the frozen host goes under the freeze, with the hosts named'() {
        when:
        List<Map> g = Incidents.group([freeze('a'), freeze('b'), r('VM-02', 'a'), r('GFS-05', 'b', Status.WARN), r('PRB-01', 'c'), r('MOR-03', 'c')])

        then:
        g.size() == 1
        (g[0].root as Result).id == 'DLM-01'
        (g[0].symptoms as List<Result>)*.id as Set == ['VM-02', 'GFS-05', 'PRB-01', 'MOR-03'] as Set
        g[0].rootHosts == ['a', 'b']
        g[0].hosts == ['a', 'b', 'c']
    }

    def 'what a freeze does not explain stands on its own'() {
        when:
        List<Map> g = Incidents.group([freeze('a'), r(id, host)])

        then:
        g.size() == 2
        groupOf(g, id) != null

        where:
        id       | host | why
        'NET-01' | 'a'  | 'not in the table'
        'VM-02'  | 'b'  | 'a VM stuck on a host that is not frozen points at storage'
        'GFS-05' | 'b'  | 'hung tasks on a host that is not frozen'
        'STO-06' | 'b'  | 'a slow LUN on a host that is not frozen'
    }

    def 'what always pages keeps its own alarm during a freeze'() {
        when:
        List<Map> g = Incidents.group([freeze('a'), r(id, 'a', Status.FAIL, null, Severity.CRITICAL)])

        then:
        g.size() == 2

        where:
        id << ['GFS-01', 'GFS-10', 'CLU-03', 'VM-01', 'NET-12', 'NET-13']
    }

    def 'lost quorum and a withdrawal explain their own host, and not the others'() {
        when:
        List<Map> g = Incidents.group([r(cause, 'a', Status.FAIL, null, Severity.CRITICAL), r('GFS-05', 'a', Status.WARN), r('GFS-05', 'b', Status.WARN), r('VM-02', 'a')])

        then:
        (groupOf(g, cause).symptoms as List<Result>).collect { "${it.id}/${it.host}".toString() } as Set == ['GFS-05/a', 'VM-02/a'] as Set
        groupOf(g, 'GFS-05').rootHosts == ['b']

        where:
        cause << ['CLU-03', 'GFS-01']
    }

    def 'storage latency stands apart from a withdrawal, because lost I/O is the usual cause of one'() {
        expect:
        Incidents.group([r('GFS-01', 'a', Status.FAIL, null, Severity.CRITICAL), r('STO-06', 'a')]).size() == 2
    }

    def 'a host that lost quorum may have lost the others too, which is one incident'() {
        when:
        List<Map> g = Incidents.group([r('CLU-03', 'a', Status.FAIL, null, Severity.CRITICAL), r('PRB-01', 'b'), r('PRB-01', 'c')])

        then:
        g.size() == 1
        g[0].hosts == ['a', 'b', 'c']
    }

    def 'with the cause closed, or never read, its symptoms stand on their own'() {
        when:
        List<Map> g = Incidents.group([new Result('DLM-01', 'Lockspaces', Severity.CRITICAL, status, 'a', 'x', 'y', 'p'), r('VM-02', 'a')])

        then:
        g.size() == groupsLeft
        groupOf(g, 'VM-02') != null

        where:
        status             | groupsLeft
        Status.PASS        | 1
        Status.NA          | 1
        Status.NOT_CHECKED | 1
    }

    def 'the same check on several hosts is one group, at the worst of its statuses, with the worst first'() {
        when:
        List<Map> g = Incidents.group([r('NET-01', 'b', Status.WARN), r('NET-01', 'a', Status.FAIL), r('NET-01', 'c', Status.WARN)])

        then:
        g.size() == 1
        (g[0].root as Result).host == 'a'
        g[0].rootHosts == ['a', 'b', 'c']
        g[0].status == Status.FAIL
    }

    def 'one check on two LUNs is two groups, as the title says which'() {
        expect:
        Incidents.group([r('STO-06', 'a', Status.WARN, 'Storage latency: 1'), r('STO-06', 'a', Status.WARN, 'Storage latency: 2')]).size() == 2
    }

    def 'severity is the worst in the group, and order of the findings does not matter'() {
        given:
        List<Result> rs = [r('GFS-01', 'a', Status.FAIL, null, Severity.HIGH), r('VM-02', 'a', Status.FAIL, null, Severity.CRITICAL), r('NET-01', 'b', Status.WARN), r('GFS-05', 'a', Status.WARN)]

        when:
        List<Map> forward = Incidents.group(rs)
        List<Map> back = Incidents.group(rs.reverse())

        then:
        groupOf(forward, 'GFS-01').severity == Severity.CRITICAL
        forward*.key == back*.key
        forward.collect { (it.root as Result).host } == back.collect { (it.root as Result).host }
    }

    def 'passes and things not judged are in no group'() {
        expect:
        Incidents.group([r('A-1', 'a', Status.PASS), r('A-2', 'a', Status.NA), r('A-3', 'a', Status.NOT_CHECKED)]) == []
    }

    // ------------------------------------------------------------------ the alarm

    def 'one alarm per group: on the cluster when it spans hosts, naming its hosts and how much it explains'() {
        when:
        List<Map> a = HealthMonitor.alarmsFor(15L, 'cluster-x', [freeze('a'), freeze('b'), r('VM-02', 'a'), r('MOR-03', 'c'), r('NET-01', 'd', Status.WARN)])
        Map f = a.find { it.name.startsWith('DLM-01') }

        then:
        a.size() == 2
        f.status == 'error'
        f.host == null
        f.message.startsWith('cluster-x / a, b: Lockspaces waiting for a node to be fenced: seen on a (expected wanted).')
        f.message.contains('Also explains 2 related findings: MOR-03, VM-02 on a, c.')
        f.message.endsWith(Incidents.ALARM_TAIL)
    }

    def 'a group on one host is on that host, and an alarm with nothing to explain says nothing about it'() {
        when:
        Map a = HealthMonitor.alarmsFor(15L, 'cluster-x', [r('NET-01', 'a', Status.WARN)])[0]

        then:
        a.host == 'a'
        a.status == 'warning'
        !a.message.contains('Also explains')
        a.message == "cluster-x / a: NET-01 title: seen (expected wanted). ${Incidents.ALARM_TAIL}".toString()
    }

    def 'a warning symptom under a failing root, and a failure beside warnings, both raise an error'() {
        expect:
        HealthMonitor.alarmsFor(15L, 'c', rs)*.status == ['error']

        where:
        rs << [[freeze('a'), r('GFS-05', 'a', Status.WARN)], [r('NET-01', 'a', Status.WARN), r('NET-01', 'b', Status.FAIL)],
               [r('NET-01', 'a', Status.WARN, null, Severity.CRITICAL), r('NET-01', 'b', Status.FAIL, null, Severity.LOW)]]   // a critical warning ranks above a low failure
    }

    def 'a very long observation does not push the rest of the message away'() {
        given:
        Result long1 = new Result('NET-01', 'Bond', Severity.HIGH, Status.FAIL, 'a', 'x' * 5000, 'up', 'plugin')

        when:
        String m = HealthMonitor.alarmsFor(15L, 'c', [long1])[0].message

        then:
        m.length() < 500
        m.endsWith(Incidents.ALARM_TAIL)
    }

    def 'an alarm keeps its identity as hosts come and go, and as symptoms do'() {
        given:
        Closure<String> id = { List<Result> rs -> HealthMonitor.alarmsFor(15L, 'c', rs)[0].uniqueId }

        expect:
        id([r('NET-01', 'a')]) == id([r('NET-01', 'a'), r('NET-01', 'b')])
        id([r('NET-01', 'a')]) == id([r('NET-01', 'b')])
        id([freeze('a')]) == id([freeze('a'), r('VM-02', 'a'), r('PRB-01', 'c')])
        id([r('NET-01', 'a')]) != id([r('NET-02', 'a')])
    }

    def 'a finding about the whole cluster keeps the identity it had before grouping, so its acknowledgement survives the upgrade'() {
        given:
        String key = 'STO-05||Paths'.digest('MD5').take(12)

        expect:
        HealthMonitor.alarmsFor(15L, 'c', [new Result('STO-05', 'Paths', Severity.MEDIUM, Status.WARN, null, 'o', 'e', 'p')])[0].uniqueId == "hvm-cluster-health-15-STO-05-${key}".toString()
    }

    def 'an alarm is raised again when the check reaches a host it does not name, and only then'() {
        given:
        String raised = "c / node1: t: o (expected e). ${Incidents.ALARM_TAIL}".toString()

        expect:
        Incidents.spread(raised, hosts) == again

        where:
        hosts              | again
        ['node1']          | false
        ['node1', 'node2'] | true
        ['node2']          | true
        ['node10']         | true
        []                 | false
    }

    def 'a host is named whole: node1 is not found in node10 or node1-b'() {
        expect:
        Incidents.names('on node1, node2', 'node1')
        !Incidents.names('on node10', 'node1')
        !Incidents.names('on node1-b', 'node1')
        !Incidents.names(null, 'node1')
    }

    def 'a message cut short by Morpheus never raises itself again, or it would on every run'() {
        expect:
        !Incidents.spread('c / node1: t: o (expected', ['node2'])
        !Incidents.spread(null, ['node2'])
    }

    // ------------------------------------------------------------------ who a finding reaches

    private static Map vm(String name, String host, String tenant, Map more = [:]) {
        [name: name, label: name, host: host, power: 'on', tenant: tenant, tenantId: tenant?.hashCode(), operator: false, disks: [[ds: 'ds1', size: 1L]]] + more
    }

    private static HostFacts answered(List<String> running = [], Map pools = [:]) {
        new HostFacts(complete: true, vms: running.collect { [name: it, state: 'running'] }, poolPaths: pools, pools: pools.values().collect { [name: it, state: 'running'] })
    }

    private static HealthChecker.Input input(List<Map> vms, Map<String, HostFacts> hosts = [a: answered(), b: answered()]) {
        Layout l = Layout.of(Fixture.LAYOUT_20)
        new HealthChecker.Input('cluster-x', l, hosts.collect { n, f -> new HealthChecker.Host(n, false, 'online', null, null, f) }, vms, Standards.forLayout(l))
    }

    private static List<Map> tenants(Map<String, Integer> counts, String host = 'a') {
        counts.collectMany { t, n -> (1..n).collect { vm("${t}-${it}".toString(), host, t) } }
    }

    def 'the text reads as the design has it'() {
        when:
        Map m = Impact.of(input(tenants(A: 9, B: 4, C: 1)), r('X-1', null))

        then:
        m.text == 'affects 14 running VMs in 3 tenants: A (9), B (4), C (1)'
        m.running == 14
        m.tenantCount == 3
    }

    def 'one VM in one tenant is singular'() {
        expect:
        Impact.of(input(tenants(A: 1)), r('X-1', null)).text == 'affects 1 running VM in 1 tenant: A (1)'
    }

    def 'about five tenants are named, the largest first, and the rest are counted'() {
        when:
        Map m = Impact.of(input(tenants(T1: 1, T2: 2, T3: 3, T4: 4, T5: 5, T6: 6, T7: 7)), r('X-1', null))

        then:
        m.text == 'affects 28 running VMs in 7 tenants: T7 (7), T6 (6), T5 (5), T4 (4), T3 (3), and 2 more'
        m.tenantCount == 7
        (m.tenants as List).size() == 5
    }

    def 'a host finding reaches the VMs on that host, and a cluster finding reaches them all'() {
        given:
        HealthChecker.Input i = input(tenants([A: 2], 'a') + tenants([B: 3], 'b'))

        expect:
        Impact.of(i, r('X-1', 'a')).text == 'affects 2 running VMs in 1 tenant: A (2)'
        Impact.of(i, r('X-1', 'b')).text == 'affects 3 running VMs in 1 tenant: B (3)'
        Impact.of(i, r('X-1', null)).running == 5
    }

    def 'a datastore finding reaches the VMs with a disk there, by the name the host gives its mount path'() {
        given:
        Map<String, HostFacts> hosts = [a: answered([], ['/mnt/uuid1': 'ds1', '/mnt/uuid2': 'ds2'])]
        List<Map> vms = [vm('on1', 'a', 'A'), vm('on2', 'a', 'B', [disks: [[ds: 'ds2', size: 1L]]]), vm('both', 'a', 'C', [disks: [[ds: 'ds1', size: 1L], [ds: 'ds2', size: 1L]]])]

        expect:
        Impact.of(input(vms, hosts), r('GFS-04', 'a', Status.WARN, 'GFS2 datastore space: /mnt/uuid1')).text == 'affects 2 running VMs in 2 tenants: A (1), C (1)'
        Impact.of(input(vms, hosts), r('GFS-09', null, Status.WARN, 'Room to move the largest VM onto ds2')).text == 'affects 2 running VMs in 2 tenants: B (1), C (1)'
    }

    def 'operator VMs count separately, and an owner that could not be read counts as a customer'() {
        given:
        List<Map> vms = [vm('t1', 'a', 'A'), vm('op1', 'a', 'Owner', [operator: true]), vm('op2', 'a', 'Owner', [operator: true]), vm('who', 'a', 'B', [operator: null])]

        when:
        Map m = Impact.of(input(vms), r('X-1', null))

        then:
        m.text == 'affects 2 running VMs in 2 tenants: A (1), B (1), plus 2 operator VMs'
        m.operator == 2
    }

    def 'only operator VMs is said plainly, not as no effect'() {
        expect:
        Impact.of(input([vm('op1', 'a', 'Owner', [operator: true])]), r('X-1', null)).text == 'affects no running tenant VMs, plus 1 operator VM'
    }

    def 'a VM that is off or paused is not reached'() {
        expect:
        Impact.of(input([vm('off', 'a', 'A', [power: 'off']), vm('p', 'a', 'A', [power: 'paused'])]), r('X-1', null)).text == 'affects no running tenant VMs'
    }

    def 'a host that did not answer is not an empty host: its VMs have a state nobody knows, whatever Morpheus last recorded'() {
        given:
        HealthChecker.Input i = input([vm('v1', 'a', 'A', [power: 'off']), vm('v2', 'a', 'B'), vm('v3', 'b', 'A')], [a: new HostFacts(), b: answered()])

        when:
        Map silent = Impact.of(i, r('PRB-01', 'a'))
        Map cluster = Impact.of(i, r('X-1', null))

        then:
        silent.running == 0
        silent.unknownState == 2
        silent.text == 'affects 0 running VMs and 2 whose state is not known in 2 tenants: A (1), B (1)'
        !silent.text.contains('no running')
        cluster.running == 1
        cluster.unknownState == 2
    }

    def 'a power state Morpheus does not know is not a VM that is off'() {
        expect:
        Impact.of(input([vm('v', 'a', 'A', [power: pow])]), r('X-1', null)).unknownState == 1

        where:
        pow << ['unknown', null]
    }

    def 'a silent host with no VMs on record is not known to be empty'() {
        when:
        Map m = Impact.of(input([], [a: new HostFacts()]), r('PRB-01', 'a'))

        then:
        !m.known
        m.text.startsWith('affects an unknown number of VMs')
    }

    def 'an empty VM list is not an empty cluster: running VMs the hosts report still count'() {
        when:
        Map m = Impact.of(input([], [a: answered(['vm-1', 'vm-2'])]), r('X-1', null))

        then:
        m.known
        m.unrecorded == 2
        m.text == 'affects no running tenant VMs, plus 2 running VMs Morpheus has no record of'
    }

    def 'a host that answered and runs nothing really is empty'() {
        expect:
        Impact.of(input([], [a: answered()]), r('X-1', 'a')).text == 'affects no running tenant VMs'
    }

    def 'a VM known to Morpheus under its libvirt name is not counted twice'() {
        expect:
        Impact.of(input([vm('vm-1', 'a', 'A')], [a: answered(['vm-1'])]), r('X-1', 'a')).unrecorded == 0
    }

    def 'a datastore no VM is placed on is unknown while disks are unplaced or on names the hosts do not have'() {
        when:
        Map m = Impact.of(input(vms, [a: answered([], ['/mnt/uuid1': 'ds1'])]), r('GFS-04', 'a', Status.WARN, 'GFS2 datastore space: /mnt/uuid1'))

        then:
        !m.known
        m.text.startsWith('affects an unknown number of VMs')

        where:
        vms << [[vm('v', 'a', 'A', [disks: [[ds: null, size: 1L]]])], [vm('v', 'a', 'A', [disks: []])], [vm('v', 'a', 'A', [disks: [[ds: 'Morpheus name', size: 1L]]])], []]
    }

    def 'a datastore with no VMs, when every disk is placed on a pool the hosts have, really has none'() {
        given:
        Map<String, HostFacts> hosts = [a: answered([], ['/mnt/uuid1': 'ds1', '/mnt/uuid2': 'ds2'])]

        expect:
        Impact.of(input([vm('v', 'a', 'A', [disks: [[ds: 'ds2', size: 1L]]])], hosts), r('GFS-04', 'a', Status.WARN, 'GFS2 datastore space: /mnt/uuid1')).text == 'affects no running tenant VMs'
    }

    def 'VMs that may be on the datastore are counted as that, not as reached'() {
        given:
        Map<String, HostFacts> hosts = [a: answered([], ['/mnt/uuid1': 'ds1'])]
        List<Map> vms = [vm('on', 'a', 'A'), vm('where', 'a', 'B', [disks: [[ds: null, size: 1L]]])]

        expect:
        Impact.of(input(vms, hosts), r('GFS-04', 'a', Status.WARN, 'GFS2 datastore space: /mnt/uuid1')).text == 'affects 1 running VM in 1 tenant: A (1), and up to 1 more whose disks Morpheus does not place'
    }

    def 'a datastore finding whose title names none does not claim a reach'() {
        expect:
        !Impact.of(input([vm('v', 'a', 'A')]), r('GFS-04', 'a', Status.WARN, 'no datastore in this title')).known
    }

    def 'a group counts each VM once, however many of its findings reach it'() {
        given:
        HealthChecker.Input i = input(tenants([A: 2], 'a') + tenants([B: 1], 'b'))

        expect:
        Impact.ofAll(i, [freeze('a'), r('VM-02', 'a'), r('PRB-01', 'b')]).text == 'affects 3 running VMs in 2 tenants: A (2), B (1)'
    }

    def 'a name tenants share does not merge them when their ids differ, and no tenant at all still counts'() {
        given:
        List<Map> vms = [vm('v1', 'a', 'Same', [tenantId: 1L]), vm('v2', 'a', 'Same', [tenantId: 2L]), vm('v3', 'a', null, [tenantId: null])]

        when:
        Map m = Impact.of(input(vms), r('X-1', null))

        then:
        m.tenantCount == 3
        m.text.contains('unknown tenant (1)')
    }

    def 'an alarm says who it reaches'() {
        expect:
        HealthMonitor.alarmsFor(15L, 'c', [r('NET-01', 'a')], input(tenants(A: 2, B: 1)))[0].message.contains('(expected wanted); affects 3 running VMs in 2 tenants: A (2), B (1).')
    }

    def 'an alarm is still raised when its reach cannot be worked out'() {
        given:
        HealthChecker.Input broken = new HealthChecker.Input() {
            @Override List<HealthChecker.Host> getHosts() { throw new IllegalStateException('no') }
        }

        when:
        List<Map> a = HealthMonitor.alarmsFor(15L, 'c', [r('NET-01', 'a')], broken)

        then:
        a.size() == 1
        !a[0].message.contains('affects')
    }

    // ------------------------------------------------------------------ the view

    def 'the view gets a reach for each finding, and positions the tab can group by'() {
        given:
        HealthChecker.Input i = input(tenants(A: 2))
        List<Result> rs = [r('PAS-1', 'a', Status.PASS), freeze('a'), r('VM-02', 'a'), r('NET-01', 'b', Status.WARN), r('NEV-1', 'a', Status.NOT_CHECKED)]
        Map view = [results: rs.collect { [id: it.id] }]

        when:
        Incidents.decorate(view, i, rs)
        Map f = view.incidents.find { it.key.startsWith('DLM-01') }

        then:
        !view.results[0].containsKey('reach')
        !view.results[4].containsKey('reach')
        view.results[1].reach.running == 2
        f.members == [1, 2]
        f.symptoms == 1
        f.status == 'FAIL'
        f.reach.text == 'affects 2 running VMs in 1 tenant: A (2)'
        view.incidents*.members.flatten().sort() == [1, 2, 3]
    }

    def 'two identical findings keep a row each'() {
        given:
        List<Result> rs = [freeze('a'), freeze('a')]
        Map view = [results: rs.collect { [:] }]

        when:
        Incidents.decorate(view, input([]), rs)

        then:
        view.incidents[0].members == [0, 1]
    }

    def 'a failure here costs the tab its grouping, never the check'() {
        given:
        HealthChecker.Input broken = new HealthChecker.Input() {
            @Override List<HealthChecker.Host> getHosts() { throw new IllegalStateException('no') }
            @Override String getClusterName() { 'cluster-x' }
        }
        Map view = [results: [[id: 'NET-01']]]

        when:
        Incidents.decorate(view, broken, [r('NET-01', 'a')])

        then:
        noExceptionThrown()
        !view.containsKey('incidents')
    }

    def 'a real view can go through the whole of it'() {
        given:
        HealthChecker.Input i = Fixture.frozen()
        List<Result> rs = new HealthChecker().check(i)
        Map view = HealthContext.view(i, rs, 'quick')

        when:
        Incidents.decorate(view, i, rs)

        then:
        view.incidents
        JsonOutput.toJson(view).contains('"incidents"')
    }

    // ------------------------------------------------------------------ what never leaves

    private static Map decorated(List<String> tenantNames) {
        HealthChecker.Input i = input(tenantNames.collectMany { tenants([(it): 2]) })
        List<Result> rs = [freeze('a'), r('VM-02', 'a'), r('NET-01', 'a', Status.WARN)]
        Map view = HealthContext.view(i, rs, 'quick')
        Incidents.decorate(view, i, rs)
        view.busiest = [[host: 'a', vm: 'vm-secret', tenant: tenantNames[0], cpuPct: 5, readIops: 1, writeIops: 1, readMBs: 1, writeMBs: 1]]
        view.window = [by: 'someone', note: 'patching for ' + tenantNames[0]]
        view
    }

    def 'the report carries no reach, no groups and no busiest VMs, in any format, with nothing to scrub by name'() {
        given:
        Map view = decorated(['Initech', 'Globex'])

        when:
        Map report = HealthReport.build([id: 27L, name: 'cluster-x', layout: 'HVM 2.0'], view, null, null, [], 1L)
        String all = JsonOutput.toJson(report)

        then: 'the view really did hold them'
        JsonOutput.toJson(view).contains('Initech')
        JsonOutput.toJson(view).contains('affects')

        and:
        ['Initech', 'Globex', 'running VM', 'reach', 'incidents', 'busiest', 'vm-secret', 'patching'].every { !all.contains(it) }
        !(report.csv as String).contains('Initech')
        report.current*.keySet().flatten().intersect(['reach', 'impact', 'tenants', 'running', 'members']).isEmpty()
    }
}
