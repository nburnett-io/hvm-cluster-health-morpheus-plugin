package org.hvmhealth

import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.MorpheusServices
import com.morpheusdata.model.Account
import com.morpheusdata.model.ComputeServerGroup
import com.morpheusdata.model.ComputeServerGroupType
import com.morpheusdata.model.OperationNotification
import com.morpheusdata.model.User
import com.morpheusdata.response.ServiceResponse
import groovy.json.JsonOutput
import io.reactivex.rxjava3.core.Single
import org.hvmhealth.checks.CheckCatalog
import org.hvmhealth.checks.HealthChecker
import org.hvmhealth.checks.HealthChecker.Result
import org.hvmhealth.checks.HealthChecker.Severity
import org.hvmhealth.checks.HealthChecker.Status
import org.hvmhealth.model.HostFacts
import org.hvmhealth.morpheus.Availability
import org.hvmhealth.morpheus.ChangeWindows
import org.hvmhealth.morpheus.FleetCorrelator
import org.hvmhealth.morpheus.FreezeWatch
import org.hvmhealth.morpheus.HealthContext
import org.hvmhealth.morpheus.HealthMonitor
import org.hvmhealth.morpheus.HealthReport
import org.hvmhealth.morpheus.Isolation
import org.hvmhealth.morpheus.StatusAnswer
import org.hvmhealth.standards.Standards
import spock.lang.Specification

/** What only holds with the parts of 0.4.0 together: each part's own spec has the others stubbed, or did not know them. */
class AllTogetherSpec extends Specification {
    static final long T0 = 1_800_000_000_000L
    static final long SEC = 1000L
    static final long MIN = 60 * SEC
    static final Account MASTER = new Account(id: 1L, name: 'Master', masterAccount: true)
    static final Account ACME = new Account(id: 2L, name: 'Acme', masterAccount: false)

    static final String STOPPED = '@@dlm_ls\nname ds-one\nflags 0x00000004 kern_stop\nchange member 2 joined 0 remove 1 failed 1 seq 4,4\nmembers 2 3\nnew change member 2 joined 0 remove 1 failed 1 seq 5,5\nnew status wait fencing\nnew members 2 3\nDLM_RC=0\n'
    static final String RUNNING = '@@dlm_ls\nname ds-one\nflags 0x00000000\nchange member 3 joined 1 remove 0 failed 0 seq 3,3\nmembers 1 2 3\nDLM_RC=0\n'
    static final String CRASHED = '2026-10-31 00:00:00.123+0000: shutting down, reason=crashed'

    FleetTimeSpec.Store store = new FleetTimeSpec.Store()
    List<OperationNotification> alarms = []
    List<OperationNotification> created = []
    List<OperationNotification> cleared = []
    List<ComputeServerGroup> clusters = [new ComputeServerGroup(id: 27L, name: 'cluster-x', owner: ACME, type: new ComputeServerGroupType(code: 'mvm-cluster'))]

    private MorpheusContext morpheus() {
        AllTogetherSpec self = this
        def svc = store.service()
        def balancer = Stub(MorpheusContext.getMethod('getAsync').returnType.getMethod('getLoadBalancer').returnType) {
            getAccountById(_) >> { Long id -> id == 1L ? Single.just(MASTER) : Single.error(new IllegalArgumentException('no such tenant')) }
        }
        def async = Stub(MorpheusContext.getMethod('getAsync').returnType) { getLoadBalancer() >> balancer }
        def notifications = Stub(MorpheusServices.getMethod('getOperationNotification').returnType) {
            list(_) >> { self.alarms.findAll { !(it in self.cleared) } + self.created.findAll { !(it in self.cleared) } }
            createAlarm(_) >> { args -> self.created << args[0]; ServiceResponse.success() }
            clearAlarm(_) >> { args -> self.cleared << args[0]; ServiceResponse.success() }
        }
        def auditLog = Stub(MorpheusServices.getMethod('getAdmin').returnType.getMethod('getAuditLog').returnType) { log(_, _) >> { args -> args[1] } }
        def admin = Stub(MorpheusServices.getMethod('getAdmin').returnType) { getAuditLog() >> auditLog }
        def services = Stub(MorpheusServices) {
            getOperationData() >> svc
            getOperationNotification() >> notifications
            getAdmin() >> admin
        }
        Stub(MorpheusContext) {
            getServices() >> services
            getAsync() >> async
        }
    }

    def setup() {
        FleetCorrelator.forget(0L)
        Availability.forget()
        FreezeWatch.FROZEN_SINCE.clear()
    }

    def cleanup() {
        FreezeWatch.stop()
        ChangeWindows.stop()
        FreezeWatch.FROZEN_SINCE.clear()
        FleetCorrelator.forget(System.currentTimeMillis())
        Availability.forget()
    }

    // ------------------------------------------------------------------ isolation, in an alarm per incident

    def 'an isolation finding keeps SECURITY in its one alarm per incident, and confirming has an alarm of its own'() {
        given:
        HealthChecker.Input in_ = Fixture.healthy()
        List<Result> rs = in_.hosts.take(2).collect { new Result('NET-13', 'Port on a VLAN of another tenant', Severity.HIGH, Status.FAIL, it.name, 'vm-one on VLAN 5', 'its own VLAN', 'plugin') }

        when:
        Map first = Isolation.judge(9001L, in_, rs, T0) { [] }
        List<Map> held = HealthMonitor.alarmsFor(9001L, 'cluster-x', first.results as List<Result>)
        Map second = Isolation.judge(9001L, in_, rs, T0 + 5 * MIN) { [] }
        List<Map> counted = HealthMonitor.alarmsFor(9001L, 'cluster-x', second.results as List<Result>)

        then:
        held*.name == ['SECURITY: NET-13 Port on a VLAN of another tenant (confirming)']
        held[0].status == 'warning'
        counted*.name == ['SECURITY: NET-13 Port on a VLAN of another tenant']
        counted[0].status == 'error'
        counted[0].uniqueId != held[0].uniqueId
        counted[0].uniqueId.startsWith(HealthMonitor.alarmPrefix(9001L))
        first.audit == []
        (second.audit as List).size() == 2

        cleanup:
        Isolation.SEEN.remove(9001L)
    }

    // ------------------------------------------------------------------ the status URL and change windows

    private static Map state(Map<Long, Map> views, long now) {
        [running: true, loadedAt: now - 60 * MIN, lastSweepAt: now - MIN, quickMs: 5 * MIN, behind: false, ids: views.keySet(), views: views]
    }

    private static Map view(String overall, long at, List<Map> results = []) {
        [at: at, overall: overall, hosts: [], results: results]
    }

    def 'a change window holds what the status URL says, counts as held, and ends with its time or by hand'() {
        given:
        MorpheusContext m = morpheus()
        Map s = state([27L: view('WARNING', T0 - MIN)], T0)

        expect:
        StatusAnswer.answer('/cluster/27', s, T0) == [200, 'HVM-HEALTH WARNING\n']
        StatusAnswer.answer('/status', s, T0)[1].contains('held=0')

        when:
        ChangeWindows.begin(m, clusters[0], '60', 'planned work', new User(username: 'someone', account: MASTER), [:], T0)

        then:
        StatusAnswer.answer('/cluster/27', s, T0) == [200, 'HVM-HEALTH OK\n']
        StatusAnswer.answer('/status', s, T0) == [200, 'HVM-HEALTH OK\nclusters=1 critical=0 warning=0 stale=0 held=1 reloading=0\n']

        and: 'it runs out by itself'
        long later = T0 + 61 * MIN
        StatusAnswer.answer('/cluster/27', state([27L: view('WARNING', later - MIN)], later), later) == [200, 'HVM-HEALTH WARNING\n']

        when: 'or is ended'
        ChangeWindows.end(m, clusters[0], null, T0)

        then:
        StatusAnswer.answer('/cluster/27', s, T0) == [200, 'HVM-HEALTH WARNING\n']
    }

    def 'a change window does not hide #why'() {
        given:
        ChangeWindows.begin(morpheus(), clusters[0], '60', 'planned work', new User(username: 'someone', account: MASTER), [:], T0)
        if (frozen) {
            FreezeWatch.FROZEN_SINCE[27L] = T0 - MIN
        }

        expect:
        StatusAnswer.answer('/cluster/27', state([27L: v], T0), T0) == want

        where:
        why                       | frozen | v                                                            | want
        'a freeze'                | true   | view('HEALTHY', T0 - MIN)                                    | [503, 'HVM-HEALTH CRITICAL\n']
        'a failure always paged'  | false  | view('CRITICAL', T0 - MIN, [[id: 'NET-13', status: 'FAIL']]) | [503, 'HVM-HEALTH CRITICAL\n']
        'a cluster out of date'   | false  | view('HEALTHY', T0 - 60 * MIN)                               | [503, 'HVM-HEALTH STALE\n']
    }

    def 'a change window holds a failure that is not always paged'() {
        given:
        ChangeWindows.begin(morpheus(), clusters[0], '60', 'planned work', new User(username: 'someone', account: MASTER), [:], T0)

        expect:
        StatusAnswer.answer('/cluster/27', state([27L: view('CRITICAL', T0 - MIN, [[id: 'HW-01', status: 'FAIL']])], T0), T0) == [200, 'HVM-HEALTH OK\n']
    }

    // ------------------------------------------------------------------ the freeze watch and the availability figures

    private static Map<String, HostFacts> hosts(String a, String b) {
        [a: HostFacts.parse(a), b: HostFacts.parse(b)]
    }

    private Map doc(MorpheusContext m) {
        Availability.doc(m, 27L, T0)
    }

    def 'a freeze the watch confirms is written when it starts, dated from the first sighting, and a check ends it'() {
        given:
        MorpheusContext m = morpheus()
        FreezeWatch.start(m)

        when: 'one sighting'
        FreezeWatch.fromCheck(m, 27L, hosts(STOPPED, RUNNING), T0)

        then:
        doc(m).open == null

        when: 'the second'
        FreezeWatch.fromCheck(m, 27L, hosts(STOPPED, RUNNING), T0 + 20 * SEC)

        then:
        FreezeWatch.FROZEN_SINCE[27L] == T0
        (doc(m).open as Map).at == T0
        store.rows.size() == 1

        when: 'every host clear'
        FreezeWatch.fromCheck(m, 27L, hosts(RUNNING, RUNNING), T0 + 10 * MIN)
        Map f = Availability.figures(doc(m), Availability.monthKey(T0), T0 + 10 * MIN)

        then:
        !FreezeWatch.FROZEN_SINCE.containsKey(27L)
        doc(m).open == null
        f.frozenMinutes == 10
        f.freezes == 1
    }

    def 'a freeze left open across a long reload is found over without counting the time nobody saw as frozen'() {
        given:
        MorpheusContext m = morpheus()
        FreezeWatch.start(m)
        Availability.freezeStarted(m, 27L, T0)

        when: 'loaded again, holding nothing, and a check hours later finds every host clear'
        FreezeWatch.start(m)
        FreezeWatch.fromCheck(m, 27L, hosts(RUNNING, RUNNING), T0 + 360 * MIN)
        Map f = Availability.figures(doc(m), Availability.monthKey(T0), T0 + 360 * MIN)

        then:
        doc(m).open == null
        f.frozenMinutes == 0
    }

    def 'a freeze left open across a short reload is counted to the check that found it over'() {
        given:
        MorpheusContext m = morpheus()
        FreezeWatch.start(m)
        Availability.freezeStarted(m, 27L, T0)

        when:
        FreezeWatch.start(m)
        FreezeWatch.fromCheck(m, 27L, hosts(RUNNING, RUNNING), T0 + 10 * MIN)

        then:
        Availability.figures(doc(m), Availability.monthKey(T0), T0 + 10 * MIN).frozenMinutes == 10
    }

    private static HealthChecker.Input frozenCluster() {
        HostFacts f = new HostFacts(complete: true, nproc: 32, mode: 'quick', lockspaces: [[name: 'ls', flags: 'kern_stop', members: '1 2 3', status: 'wait fencing']])
        Fixture.input([new HealthChecker.Host('hvm-node01', false, null, null, null, f, null, null)], Fixture.LAYOUT_20, null, [])
    }

    def 'with the watch on, a check seeing a lockspace stopped for a moment is no freeze until the watch says so'() {
        given: 'the watch has this cluster, with its turn far off so that nothing else reads it'
        MorpheusContext m = morpheus()
        FreezeWatch.start(m)
        FreezeWatch.update(clusters, [:], true, System.currentTimeMillis() + 24 * 60 * MIN)

        when:
        Availability.afterRun(m, 27L, frozenCluster(), [], [:], T0)

        then:
        doc(m).open == null

        when:
        FreezeWatch.FROZEN_SINCE[27L] = T0 - SEC
        Availability.afterRun(m, 27L, frozenCluster(), [], [:], T0 + MIN)

        then:
        (doc(m).open as Map).at == T0 - SEC

        when: 'it stays open though the watch has lost it, while a host still reads frozen'
        FreezeWatch.FROZEN_SINCE.remove(27L)
        Availability.afterRun(m, 27L, frozenCluster(), [], [:], T0 + 2 * MIN)

        then:
        doc(m).open != null
    }

    def 'with the watch off, a check is all there is, so its sighting counts'() {
        given:
        MorpheusContext m = morpheus()

        when:
        Availability.afterRun(m, 27L, frozenCluster(), [], [:], T0)

        then:
        (doc(m).open as Map).at == T0
    }

    // ------------------------------------------------------------------ VM-06 and the unplanned stops

    private static HealthChecker.Input crashes(List<String> lines, List<Map> vms) {
        String health = (['@@host_health', 'OOM_READ=1', 'VM_LOGS_READ=1'] + lines).join('\n')
        HostFacts f = Fixture.facts('probe-healthy', 'hvm-node01') { String t -> t.replace('@@end', "${health}\n@@end") }
        Fixture.input([Fixture.host('hvm-node01', f)], Fixture.LAYOUT_20, null, vms)
    }

    private static Map vm(String name, String label, String tenant, Long tenantId) {
        [name: name, label: label, tenant: tenant, tenantId: tenantId, power: 'on', host: 'hvm-node01']
    }

    private static Map stops(HealthChecker.Input in_) {
        Map d = Availability.empty(T0)
        List<Result> rs = new HealthChecker().check(in_)
        Availability.observe(d, in_, rs, 5 * MIN, null, T0)
        [doc: d, figures: Availability.figures(d, Availability.monthKey(T0), T0), observed: rs.find { it.id == 'VM-06' }.observed]
    }

    def 'the crash VM-06 reports counts for the tenant whose VM it was, even with another tenant using the same label'() {
        when:
        Map s = stops(crashes(["VM_CRASH=vm-one|${CRASHED}".toString()], [vm('vm-one', 'web', 'Initech', 4L), vm('vm-two', 'web', 'Globex', 3L)]))
        List<Map> tenants = s.figures.tenants as List<Map>
        String key = Availability.monthKey(T0)

        then:
        s.observed.startsWith('web crashed at')
        s.figures.vmStops == 1
        tenants.find { it.id == 4L }.vmStops == 1
        tenants.find { it.id == 3L }.vmStops == 0
        Availability.tenantReport([27L: s.doc as Map], 4L, key, T0).vmStops*.vm == ['web']
        Availability.tenantReport([27L: s.doc as Map], 3L, key, T0).vmStops == []
    }

    def 'every crash counts, though VM-06 only has room to name 8'() {
        given:
        List<String> names = (0..9).collect { "vm-${it}".toString() }

        when:
        Map s = stops(crashes(names.collect { "VM_CRASH=${it}|${CRASHED}".toString() }, names.collect { vm(it, it, 'Initech', 4L) }))

        then:
        s.observed.endsWith('and 2 more')
        s.figures.vmStops == 10
        (s.figures.tenants as List<Map>)[0].vmStops == 10
    }

    def 'a VM both crashed and killed for lack of memory is one stop'() {
        when:
        Map s = stops(crashes(["VM_CRASH=vm-one|${CRASHED}".toString(), 'OOM=2026-10-31T00:00:00+0000|qemu-system-x86|machine-qemu\\x2d5\\x2dvm\\x2done.scope'], [vm('vm-one', 'web', 'Initech', 4L)]))

        then:
        s.figures.vmStops == 1
    }

    def 'a crash of a VM Morpheus does not know counts for the cluster and no tenant, and a kill the kernel did not name counts too'() {
        expect:
        stops(crashes(lines, [])).figures.vmStops == 1
        (stops(crashes(lines, [])).figures.tenants as List).isEmpty()

        where:
        lines << [["VM_CRASH=vm-ghost|${CRASHED}".toString()], ['OOM=2026-10-31T00:00:00+0000|qemu-system-x86|']]
    }

    // ------------------------------------------------------------------ the alarms on the master tenant

    def 'clearing a shared cause leaves PRB-04, which is also on the fleet, alone, and cluster clean-up leaves FLT-01 alone'() {
        given:
        OperationNotification keepingUp = new OperationNotification(uniqueId: HealthMonitor.KEEPING_UP_ALARM, active: true)
        OperationNotification shared = new OperationNotification(uniqueId: "${FleetCorrelator.ALARM_PREFIX}NET-01-abc".toString(), active: true)
        alarms.addAll([keepingUp, shared])

        when:
        FleetCorrelator.afterRun(morpheus(), clusters[0], frozenCluster(), [results: []], [:], T0)

        then:
        cleared*.uniqueId == [shared.uniqueId]
        !(shared.uniqueId ==~ /^${HealthMonitor.CATEGORY}-(\d+)(-.*|$)/)
    }

    // ------------------------------------------------------------------ change windows and FLT-01

    private static Map found(String check, String status, long first) {
        [results: [[id: check, status: status, firstSeen: first]], historySince: first - 30 * 24 * 60 * MIN]
    }

    def 'FLT-01 reads the real change window: with one cluster of three in a window nothing is raised, and without it the same findings are'() {
        given:
        MorpheusContext m = morpheus()
        ComputeServerGroup two = new ComputeServerGroup(id: 28L, name: 'cluster-y', owner: ACME)
        ComputeServerGroup three = new ComputeServerGroup(id: 29L, name: 'cluster-z', owner: ACME)
        Closure<List> run = { [clusters[0], two, three].each { FleetCorrelator.afterRun(m, it, frozenCluster(), found('STO-01', 'WARN', T0 - MIN), [:], T0) }; FleetCorrelator.current(null, T0).shared }

        when:
        ChangeWindows.begin(m, clusters[0], '60', 'rolling work', new User(username: 'someone', account: MASTER), [:], T0 - 10 * MIN)
        List<Map> held = run.call()

        then:
        held == []
        created.findAll { it.uniqueId?.startsWith(FleetCorrelator.ALARM_PREFIX) } == []

        when:
        ChangeWindows.end(m, clusters[0], null, T0)
        FleetCorrelator.forget(0L)
        List<Map> plain = run.call()

        then:
        plain*.id == ['FLT-01']
        created.count { it.uniqueId?.startsWith(FleetCorrelator.ALARM_PREFIX) } == 1
    }

    // ------------------------------------------------------------------ the busiest VMs, from the probe to the tab

    def 'what the check puts in view.busiest is what the tab reads, and none of it reaches a report'() {
        given:
        String sample = '@@busiest\nBUSY_MS=2000\nBUSY_A=vm-one|1000|10|1000|20|2000\nBUSY_B=vm-one|2000001000|110|2098152|220|4196304'
        HostFacts f = Fixture.facts('probe-healthy', 'hvm-node01') { String t -> t.replace('@@end', "${sample}\n@@end") }
        HealthChecker.Input in_ = Fixture.input([Fixture.host('hvm-node01', f)], Fixture.LAYOUT_20, null, [vm('vm-one', 'Web One', 'Initech', 4L)])

        when:
        Map v = HealthContext.view(in_, new HealthChecker().check(in_), 'full')
        Map row = (v.busiest as List<Map>)[0]
        String tab = new File('src/assets/javascripts/health-tab.js').getText('UTF-8')
        String report = JsonOutput.toJson(HealthReport.build([id: 27L, name: 'cluster-x'], v, null, null, [], T0))

        then:
        row.host == 'hvm-node01'
        row.vm == 'Web One'
        row.tenant == 'Initech'
        row.keySet().every { tab.contains("b.${it}") }
        !report.contains('Initech')
        !report.contains('Web One')
        !report.contains('busiest')
    }

    // ------------------------------------------------------------------ what merging could leave out of step

    def 'every route has its method, and every route but job is in the matrix of refusals'() {
        given:
        Map<String, String> routes = new File('src/main/groovy/org/hvmhealth/morpheus/HealthController.groovy').getText('UTF-8')
                .findAll(/Route\.build\('([^']+)', '(\w+)'/) { m, url, name -> [url, name] }.collectEntries()
        String matrix = new File('src/test/groovy/org/hvmhealth/RoutesSpec.groovy').readLines('UTF-8').find { it.contains('[route, verb] <<') }

        expect:
        routes.size() >= 20
        routes.values().every { org.hvmhealth.morpheus.HealthController.metaClass.respondsTo(org.hvmhealth.morpheus.HealthController, it) }
        routes.values().findAll { it != 'job' && !matrix.contains("'${it}'") } == []
    }

    def 'each setting is in the README, and no two share a place on the form'() {
        given:
        String source = new File('src/main/groovy/org/hvmhealth/ClusterHealthPlugin.groovy').getText('UTF-8')
        String readme = new File('README.md').getText('UTF-8')
        List<String> labels = source.findAll(/fieldLabel: '([^']+)'/) { m, l -> l }
        List<String> order = source.findAll(/displayOrder: (\d+)/) { m, n -> n }

        expect:
        labels.size() >= 13
        labels.findAll { !readme.contains(it) } == []
        order.size() == order.unique(false).size()
    }

    def 'every standards key is in docs/STANDARDS.md'() {
        given:
        String doc = new File('docs/STANDARDS.md').getText('UTF-8')

        expect:
        Standards.DEFAULTS.keySet().findAll { !doc.containsIgnoreCase(it) }.isEmpty()
    }

    def 'the changelog has every check new in this version'() {
        given:
        String log = new File('CHANGELOG.md').getText('UTF-8')
        String current = log.substring(log.indexOf('## 0.4.0'), log.indexOf('## 0.3.0'))

        expect:
        ['HST-04', 'HST-05', 'HST-06', 'HST-07', 'HST-08', 'VM-06', 'PRB-04', 'FLT-01', 'CAP-02', 'CAP-03', 'GFS-11', 'STO-07', 'HPE-01', 'DLM-07', 'KRN-04', 'KRN-05'].findAll { !current.contains(it) } == []
    }

    def 'the probe kept every part of its order: DLM first with its exit code, thin inside the full check, then ports and busiest on every check'() {
        given:
        String p = HealthContext.getResourceAsStream('/scripts/health-probe.sh').getText('UTF-8').replace('\r\n', '\n')

        expect:
        p.indexOf('sec dlm_ls') < p.indexOf('sec storage')
        p.contains('echo "DLM_RC=$DLM_RC"')
        p.indexOf('sec smart') < p.indexOf('sec thin')
        p.indexOf('sec thin') < p.indexOf('\nfi\n\nsec vm_ports')
        p.indexOf('sec vm_ports') < p.indexOf('sec busiest')
    }

    def 'what runs last is what a full check can go without: running short costs the disks behind a controller and HPE\'s check, never thin, ports or busiest'() {
        given:
        String p = HealthContext.getResourceAsStream('/scripts/health-probe.sh').getText('UTF-8').replace('\r\n', '\n')
        int raid = p.indexOf('  sec raid\n')

        expect:
        ['sec thin', 'sec vm_ports', 'sec busiest'].every { p.indexOf(it) > 0 && p.indexOf(it) < raid }
        raid < p.indexOf('sec hvmcli_health')
        p.indexOf('sec hvmcli_health') < p.indexOf('sec end')
        // each of them is out of every other run's way, and only a full run gets there
        p.substring(0, raid).endsWith('if [ "$MODE" = full ]; then\n')
        !p.substring(0, raid).contains('RAID_')
        !p.substring(0, raid).contains('hvmcli health')
        p.substring(p.indexOf('sec smart'), p.indexOf('sec thin')).contains('BOOT_DISK=')
    }

    def 'the disks stop early enough to leave HPE\'s check its time, after its second look at DLM and the last read running over'() {
        given:
        String p = HealthContext.getResourceAsStream('/scripts/health-probe.sh').getText('UTF-8')
        int reserve = (p =~ /raid_end=\$\(\(BUDGET - (\d+)\)\)/)[0][1] as int
        int calm = (p =~ /l="\$\(t (\d+) dlm_tool ls\)"/)[0][1] as int
        int hvmcli = (p =~ /bounded (\d+) hvmcli health check/)[0][1] as int
        int disk = (p =~ /bounded (\d+) raid_smart/)[0][1] as int

        expect:
        reserve >= calm + hvmcli + disk
        // and that much of a real full check's limit is left for the disks
        HealthContext.probeScript('full').startsWith('set -- full 120\n')
        120 - reserve >= 40 + 35
    }

    def 'which findings wait for a full check: isolation moved out when it went on every check, and the two added since are in'() {
        expect:
        !('NET-12' in HealthMonitor.FULL_ONLY)
        !('NET-13' in HealthMonitor.FULL_ONLY)
        'STO-07' in HealthMonitor.FULL_ONLY
        'HPE-01' in HealthMonitor.FULL_ONLY
        HealthMonitor.FULL_ONLY.every { CheckCatalog.ENTRIES.containsKey(it) }
    }

    def 'the tab draws both panels the parts added'() {
        given:
        String show = new File('src/assets/javascripts/health-tab.js').readLines('UTF-8').find { it.contains('function showView') }
        String page = new File('src/main/resources/renderer/hbs/healthTab.hbs').getText('UTF-8')

        expect:
        ['renderBusiest', 'renderCapacity'].every { show.contains(it) }
        ['"busiest"', '"capacity"', '"window"'].every { page.contains("data-section=${it}") }
    }
}
