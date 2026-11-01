package org.hvmhealth

import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.MorpheusServices
import com.morpheusdata.model.Account
import com.morpheusdata.model.ComputeServerGroup
import org.hvmhealth.checks.HealthChecker
import org.hvmhealth.checks.HealthChecker.Result
import org.hvmhealth.checks.HealthChecker.Severity
import org.hvmhealth.checks.HealthChecker.Status
import org.hvmhealth.morpheus.FindingHistory
import org.hvmhealth.morpheus.HealthMonitor
import org.hvmhealth.morpheus.HealthReport
import org.hvmhealth.morpheus.Isolation
import org.hvmhealth.morpheus.Paging
import spock.lang.Specification

/** Two runs in a row before an isolation finding counts: item 6 of 0.4.0. Each test uses its own cluster id, as the state is per cluster and in memory. */
class IsolationSpec extends Specification {
    static final long T0 = 1_800_000_000_000L
    static final long MIN = 60_000L
    static final String TITLE = 'No unexpected trunk: vm-one'
    static final Closure<List<Map>> NOTHING_STORED = { [] }

    /** A host that read its ports (none of them is a finding unless the run says so), unless told otherwise. */
    private static HealthChecker.Input cluster(String ports = "@@vm_ports\n", boolean answered = true) {
        HealthChecker.Host h = answered
                ? Fixture.host('hvm-node01', Fixture.facts('probe-healthy', 'hvm-node01') { String t -> t.replace('@@end', "${ports}\n@@end") })
                : Fixture.host('hvm-node01', new org.hvmhealth.model.HostFacts())
        Fixture.input([h], Fixture.LAYOUT_20)
    }

    private static Result failing(String host = 'hvm-node01', String title = TITLE) {
        new Result('NET-13', title, Severity.CRITICAL, Status.FAIL, host, 'vnet0 carries tagged VLANs 300, including Tenant B on VLAN 300; this VM is Tenant A', 'one VLAN per port', 'plugin')
    }

    private static Result warning() {
        new Result('NET-12', 'VM port on the VLAN Morpheus says: vm-one', Severity.HIGH, Status.WARN, 'hvm-node01', 'vnet0 is on VLAN 700', 'x', 'plugin')
    }

    private static Result other() {
        new Result('LAY-01', 'Cluster layout recognised', Severity.MEDIUM, Status.PASS, null, 'HVM 2.0', 'x', 'plugin')
    }

    private static Map judge(Long id, HealthChecker.Input i, List<Result> rs, long at, Closure<List<Map>> stored = NOTHING_STORED) {
        Isolation.judge(id, i, rs, at, stored)
    }

    private static Result only(Map judged, String id = 'NET-13') {
        (judged.results as List<Result>).find { it.id == id }
    }

    def 'a first sighting is a warning that says so, whatever it would be, and nothing is audited'() {
        when:
        Map j = judge(101L, cluster(), [failing(), other()], T0)
        Result r = only(j)

        then:
        r.status == Status.WARN
        r.severity == Severity.HIGH
        r.title == 'No unexpected trunk (confirming): vm-one'
        r.observed.startsWith('seen at one check so far')
        r.hint.contains('next check')
        j.audit == []
        (j.results as List<Result>).any { it.id == 'LAY-01' }
    }

    def 'a first sighting cannot page: the cluster is at most a warning'() {
        given:
        List<Result> rs = judge(102L, cluster(), [failing()], T0).results as List<Result>
        Map view = [overall: HealthChecker.overall(rs), results: rs.collect { [id: it.id, status: it.status.name()] }, hosts: []]

        expect:
        HealthChecker.overall(rs) == 'WARNING'
        Paging.state(102L, view, T0) != 'CRITICAL'
    }

    def 'the second run in a row counts it as found, and audits it once'() {
        given:
        judge(103L, cluster(), [failing()], T0)

        when:
        Map second = judge(103L, cluster(), [failing()], T0 + 5 * MIN)
        Map third = judge(103L, cluster(), [failing()], T0 + 10 * MIN)

        then:
        only(second).status == Status.FAIL
        only(second).severity == Severity.CRITICAL
        only(second).title == TITLE
        second.audit*.id == ['NET-13']
        only(third).status == Status.FAIL
        third.audit == []
    }

    def 'a network change seen once and gone by the next run counts for nothing, and starts again if it returns'() {
        given:
        judge(104L, cluster(), [failing()], T0)

        when:
        Map gone = judge(104L, cluster(), [other()], T0 + 5 * MIN)
        Map back = judge(104L, cluster(), [failing()], T0 + 10 * MIN)

        then:
        only(gone) == null
        gone.audit == []
        only(back).status == Status.WARN
        only(back).title.contains('confirming')
    }

    def 'two looks within a minute are one: a button pressed twice does not confirm it'() {
        given:
        judge(105L, cluster(), [failing()], T0)

        when:
        Map soon = judge(105L, cluster(), [failing()], T0 + 30_000L)
        Map later = judge(105L, cluster(), [failing()], T0 + MIN)

        then:
        only(soon).status == Status.WARN
        only(later).status == Status.FAIL
    }

    def 'a warning needs confirming too, and stays a warning: a tenant on an operator VLAN never becomes critical here'() {
        given:
        judge(106L, cluster(), [warning()], T0)

        when:
        Map second = judge(106L, cluster(), [warning()], T0 + 5 * MIN)

        then:
        only(second, 'NET-12').status == Status.WARN
        only(second, 'NET-12').severity == Severity.HIGH
        !only(second, 'NET-12').title.contains('confirming')
        second.audit == []
    }

    def 'each VM is its own finding: another VM on the host is a first sighting'() {
        given:
        judge(107L, cluster(), [failing()], T0)

        when:
        Map j = judge(107L, cluster(), [failing(), failing('hvm-node01', 'No unexpected trunk: vm-two')], T0 + 5 * MIN)
        List<Result> rs = j.results as List<Result>

        then:
        rs.find { it.title.contains('vm-one') }.status == Status.FAIL
        rs.find { it.title.contains('vm-two') }.status == Status.WARN
    }

    def 'a silent host keeps its finding as last read: it neither clears nor confirms'() {
        given:
        judge(108L, cluster(), [failing()], T0)
        judge(108L, cluster(), [failing()], T0 + 5 * MIN)

        when: 'the host stops answering'
        Map silent = judge(108L, cluster('', false), [], T0 + 10 * MIN)

        then:
        only(silent).status == Status.FAIL
        only(silent).observed.startsWith('as last read')
        silent.audit == []

        when: 'it answers, still wrong: no second audit, and no text pile-up'
        Map back = judge(108L, cluster(), [failing()], T0 + 15 * MIN)

        then:
        only(back).status == Status.FAIL
        !only(back).observed.startsWith('as last read')
        back.audit == []

        when: 'it answers, and the port is right'
        Map fixed = judge(108L, cluster(), [other()], T0 + 20 * MIN)

        then:
        only(fixed) == null
    }

    def 'a first sighting on a host that goes silent stays confirming, and the next read confirms it'() {
        given:
        judge(109L, cluster(), [failing()], T0)

        when:
        Map silent = judge(109L, cluster('', false), [], T0 + 5 * MIN)
        Map back = judge(109L, cluster(), [failing()], T0 + 10 * MIN)

        then:
        only(silent).status == Status.WARN
        only(silent).title.contains('confirming')
        only(back).status == Status.FAIL
    }

    def 'a run that was cut or skipped the read holds the finding: a freeze must not close it'() {
        given:
        judge(id, cluster(), [failing()], T0)
        judge(id, cluster(), [failing()], T0 + 5 * MIN)

        when:
        Map j = judge(id, cluster(ports), [], T0 + 10 * MIN)

        then:
        only(j).status == Status.FAIL

        where:
        id   | ports
        110L | "@@vm_ports\nPORTS_CUT=1"
        111L | ''
    }

    def 'a host gone from the cluster, or a check since disabled, holds nothing'() {
        given:
        judge(112L, cluster(), [failing('hvm-gone')], T0)
        judge(113L, cluster(), [failing()], T0)
        HealthChecker.Input disabled = Fixture.input([Fixture.host('hvm-node01', new org.hvmhealth.model.HostFacts())], Fixture.LAYOUT_20, '{"disabledChecks":["NET-13"]}')

        expect:
        only(judge(112L, cluster(), [], T0 + 5 * MIN)) == null
        only(judge(113L, disabled, [], T0 + 5 * MIN)) == null
    }

    def 'after a reload, a finding the history has open is not a first sighting, and is not audited again'() {
        given:
        Closure<List<Map>> stored = { [[k: FindingHistory.key('NET-13', 'hvm-node01', TITLE), status: 'FAIL', cleared: null]] }

        when:
        Map j = judge(114L, cluster(), [failing()], T0, stored)

        then:
        only(j).status == Status.FAIL
        j.audit == []
    }

    def 'after a reload, one the history has as confirming is on its second sighting, and audited now'() {
        given:
        Closure<List<Map>> stored = { [[k: FindingHistory.key('NET-13', 'hvm-node01', 'No unexpected trunk (confirming): vm-one'), status: 'WARN', cleared: null]] }

        when:
        Map j = judge(115L, cluster(), [failing()], T0, stored)

        then:
        only(j).status == Status.FAIL
        j.audit*.id == ['NET-13']
    }

    def 'one the history has cleared is a first sighting'() {
        given:
        Closure<List<Map>> stored = { [[k: FindingHistory.key('NET-13', 'hvm-node01', TITLE), status: 'FAIL', cleared: T0 - MIN]] }

        expect:
        only(judge(116L, cluster(), [failing()], T0, stored)).status == Status.WARN
    }

    def 'history that cannot be read does not stop the check, and is asked again; history that answered is not'() {
        given:
        int asked = 0
        Closure<List<Map>> broken = { asked++; throw new IllegalStateException('no store') }
        Closure<List<Map>> fine = { asked++; [] }

        when:
        Map j = judge(117L, cluster(), [failing()], T0, broken)

        then:
        only(j).status == Status.WARN

        when:
        judge(117L, cluster(), [failing()], T0 + 30_000L, fine)
        judge(117L, cluster(), [failing()], T0 + 40_000L, fine)

        then:
        asked == 2
    }

    def 'a cluster with no finding never reads the store'() {
        given:
        boolean read = false

        when:
        judge(118L, cluster(), [other()], T0) { read = true; [] }

        then:
        !read
    }

    def 'results stay in rank order with the worst first'() {
        given:
        judge(119L, cluster(), [failing()], T0)

        when:
        List<Result> rs = judge(119L, cluster(), [other(), failing()], T0 + 5 * MIN).results as List<Result>

        then:
        rs*.id == ['NET-13', 'LAY-01']
    }

    // ------------------------------------------------------------------ what it says, and where

    def 'its alarm names security, in first place, and others are left alone'() {
        expect:
        Isolation.alarmName(failing()) == "SECURITY: NET-13 ${TITLE}".toString()
        Isolation.alarmName(warning()).startsWith('SECURITY: NET-12 ')
        Isolation.alarmName(other()) == 'LAY-01 Cluster layout recognised'
        HealthMonitor.alarmsFor(1L, 'cluster-x', [failing()])[0].name.startsWith('SECURITY: NET-13')
    }

    def 'a confirming alarm is a different alarm from the one that counts, so "seen once" cannot outlive the sighting'() {
        given:
        Result held = only(judge(120L, cluster(), [failing()], T0))

        expect:
        HealthMonitor.alarmsFor(1L, 'cluster-x', [held])[0].uniqueId != HealthMonitor.alarmsFor(1L, 'cluster-x', [failing()])[0].uniqueId
        HealthMonitor.alarmsFor(1L, 'cluster-x', [held])[0].status == 'warning'
        HealthMonitor.alarmsFor(1L, 'cluster-x', [held])[0].name.startsWith('SECURITY: ')
    }

    /** Enough of Morpheus to take an audit entry, with a history that holds nothing. */
    private MorpheusContext morpheus(List<String> audited) {
        def auditLog = Stub(MorpheusServices.getMethod('getAdmin').returnType.getMethod('getAuditLog').returnType) {
            log(_, _) >> { args -> audited << args[1].description; args[1] }
        }
        def admin = Stub(MorpheusServices.getMethod('getAdmin').returnType) { getAuditLog() >> auditLog }
        def data = Stub(MorpheusServices.getMethod('getOperationData').returnType) { list(_) >> [] }
        MorpheusServices services = Stub(MorpheusServices) {
            getAdmin() >> admin
            getOperationData() >> data
        }
        Stub(MorpheusContext) { getServices() >> services }
    }

    def 'the audit entry says which check and host, and never the tenant the finding names'() {
        given:
        List<String> audited = []
        ComputeServerGroup c = new ComputeServerGroup(id: 121L, name: 'cluster-x', owner: new Account(id: 2L, name: 'Provider'))
        MorpheusContext m = morpheus(audited)

        when:
        List<Result> first = Isolation.confirm(m, c, cluster(), [failing()], T0)
        List<Result> second = Isolation.confirm(m, c, cluster(), [failing()], T0 + 5 * MIN)

        then:
        first[0].status == Status.WARN
        second[0].status == Status.FAIL
        audited.size() == 1
        audited[0].contains('NET-13')
        audited[0].contains('hvm-node01')
        !audited[0].contains('Tenant')
    }

    def 'a report never carries the VM a confirming finding names'() {
        given:
        Result held = only(judge(122L, cluster(), [failing()], T0))
        Map last = [overall: 'WARNING', at: 1L, mode: 'quick', counts: [:], readiness: [], hosts: [],
                    results: [[id: held.id, title: held.title, severity: held.severity.name(), status: held.status.name(), host: held.host, observed: held.observed, expected: held.expected, source: held.source, hint: held.hint]]]

        when:
        Map r = HealthReport.build([id: 122L, name: 'cluster-x'], last, null, null, [], 2L)

        then:
        r.current[0].title == 'No unexpected trunk (confirming)'
        r.current[0].observed.startsWith('withheld')
        !groovy.json.JsonOutput.toJson(r).contains('vm-one')
        !groovy.json.JsonOutput.toJson(r).contains('Tenant')
    }

    def 'an isolation finding stays open in the history while its host answers with its ports unread, and only that finding'() {
        given:
        Map doc = FindingHistory.empty(T0)
        Result other = new Result('NET-01', 'Bond members up: bond0', Severity.HIGH, Status.WARN, 'hvm-node01', 'o', 'e', 'plugin')
        Map all = [hosts: ['hvm-node01'] as Set, answered: ['hvm-node01'] as Set, fullKnown: true]
        FindingHistory.apply(doc, [failing(), other], all, [overall: 'CRITICAL'], T0)

        when: 'after a reload nothing holds the finding, and its ports were cut short'
        FindingHistory.apply(doc, [], all + [portsUnread: ['hvm-node01'] as Set], [overall: 'HEALTHY'], T0 + 5 * MIN)

        then:
        doc.episodes.find { it.id == 'NET-13' }.cleared == null
        doc.episodes.find { it.id == 'NET-01' }.cleared == T0 + 5 * MIN

        when: 'read whole'
        FindingHistory.apply(doc, [], all + [portsUnread: [] as Set], [overall: 'HEALTHY'], T0 + 10 * MIN)

        then:
        doc.episodes.find { it.id == 'NET-13' }.cleared == T0 + 10 * MIN
    }

    def 'which hosts had their ports read: #why'() {
        given:
        HealthChecker.Input in_ = Fixture.input([Fixture.host('h', facts)], Fixture.LAYOUT_20)

        expect:
        Isolation.portsUnread(in_) == (unread ? ['h'] as Set : [] as Set)
        FindingHistory.coverage(in_, true).portsUnread == Isolation.portsUnread(in_)

        where:
        why                   | facts                                                                  | unread
        'silent'              | new org.hvmhealth.model.HostFacts()                                    | true
        'no section'          | new org.hvmhealth.model.HostFacts(complete: true)                      | true
        'cut short'           | new org.hvmhealth.model.HostFacts(complete: true, sections: [vm_ports: []], portsCut: true) | true
        'read'                | new org.hvmhealth.model.HostFacts(complete: true, sections: [vm_ports: []]) | false
    }

    def 'history stores neither the VM nor the tenant of a confirming finding, and it clears like any other'() {
        given:
        Result held = only(judge(123L, cluster(), [failing()], T0))
        Map doc = FindingHistory.empty(T0)
        Map cover = [hosts: ['hvm-node01'] as Set, answered: ['hvm-node01'] as Set, fullKnown: true]

        when:
        FindingHistory.apply(doc, [held], cover, [overall: 'WARNING'], T0)
        String stored = groovy.json.JsonOutput.toJson(doc)

        then:
        !stored.contains('vm-one')
        !stored.contains('Tenant')
        doc.episodes[0].title == 'No unexpected trunk (confirming)'

        when:
        FindingHistory.apply(doc, [], cover, [overall: 'HEALTHY'], T0 + 5 * MIN)

        then:
        doc.episodes[0].cleared == T0 + 5 * MIN
    }
}
