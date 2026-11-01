package org.hvmhealth

import org.hvmhealth.checks.HealthChecker
import org.hvmhealth.checks.HealthChecker.Severity
import org.hvmhealth.checks.HealthChecker.Status
import org.hvmhealth.model.HostFacts
import org.hvmhealth.morpheus.HealthMonitor
import spock.lang.Specification
import spock.lang.Unroll

import java.util.regex.Matcher

/**
 * 0.4.0 W-QUORUM: the agent quorum (CLU-14), the three-way member agreement (CLU-15), the CLU-03/CLU-04 re-aim on 1.3/2.0,
 * the CLU-12 reword, and TCP 7443 added to MIG-02. The agent payload shape is only partly documented and unproven on a real
 * two-host or stretch cluster, so the checks read NOT CHECKED on any surprise; the tests cover those surprises too.
 */
class QuorumSpec extends Specification {
    private static final String HEALTHY_JSON = '{"quorumAchieved":true,"fenced":false,"waitFencing":false,"totalNodes":3,"reachableNodes":3,"corosyncActive":true,"dlmActive":true,"corosyncQuorate":true,"nodes":[{"name":"hvm-node01"},{"name":"hvm-node02"},{"name":"hvm-node03"}]}'
    private static final String ALL_NODES = '["hvm-node01","hvm-node02","hvm-node03"]'

    private static List<HealthChecker.Result> check(HealthChecker.Input i) { new HealthChecker().check(i) }

    private static List<HealthChecker.Result> ids(HealthChecker.Input i, String id) { check(i).findAll { it.id == id } }

    private static HealthChecker.Input cluster(String sample, String layout, Closure<String> edit) {
        Fixture.input((1..3).collect { int n -> Fixture.host("hvm-node0${n}".toString(), Fixture.facts(sample, "hvm-node0${n}".toString(), edit)) }, layout)
    }

    private static HealthChecker.Input healthy20(Closure<String> edit = { it }) { cluster('probe-healthy', Fixture.LAYOUT_20, edit) }

    private static HealthChecker.Input timesyncd13(Closure<String> edit = { it }) { cluster('probe-timesyncd', Fixture.LAYOUT_13, edit) }

    /** Replace the whole @@agent_quorum section with these two lines (either may be null to leave it out). */
    private static Closure<String> aq(String json, String nodes = ALL_NODES) {
        String body = (json == null ? '' : "AGENT_QUORUM=${json}\n") + (nodes == null ? '' : "QUORUM_NODES=${nodes}\n")
        return { String t -> t.replaceAll(/(?s)@@agent_quorum\n.*?@@links/, Matcher.quoteReplacement("@@agent_quorum\n${body}@@links".toString())) }
    }

    /** Drop the @@agent_quorum section entirely, as an older probe that never gathered it would. */
    private static final Closure<String> NO_SECTION = { String t -> t.replaceAll(/(?s)@@agent_quorum\n.*?(?=@@links)/, '') }

    // ------------------------------------------------------------------ CLU-14: agent quorum

    def 'a healthy agent quorum passes on every host, on both layouts'() {
        expect:
        ids(healthy20(), 'CLU-14')*.status == [Status.PASS, Status.PASS, Status.PASS]
        ids(timesyncd13(), 'CLU-14')*.status == [Status.PASS, Status.PASS, Status.PASS]
        ids(healthy20(), 'CLU-14')[0].observed.contains('3 of 3 nodes reachable')
    }

    @Unroll
    def 'the agent quorum is #expected when #why'() {
        when:
        HealthChecker.Result r = ids(healthy20(aq(json)), 'CLU-14')[0]

        then:
        r.status == expected
        r.severity == Severity.CRITICAL

        where:
        why                        | json                                                                                       | expected
        'quorum not achieved'      | '{"quorumAchieved":false,"fenced":false,"waitFencing":false,"totalNodes":3,"reachableNodes":1}' | Status.FAIL
        'this host is fenced'      | '{"quorumAchieved":true,"fenced":true,"waitFencing":false,"totalNodes":3,"reachableNodes":3}'    | Status.FAIL
        'a node is being fenced'   | '{"quorumAchieved":true,"fenced":false,"waitFencing":true,"totalNodes":3,"reachableNodes":3}'    | Status.WARN
        'a node is unreachable'    | '{"quorumAchieved":true,"fenced":false,"waitFencing":false,"totalNodes":3,"reachableNodes":2}'   | Status.WARN
    }

    def 'quorum not achieved names the agent as the authority and points away from corosync Quorate'() {
        when:
        HealthChecker.Result r = ids(healthy20(aq('{"quorumAchieved":false,"totalNodes":3,"reachableNodes":1}')), 'CLU-14')[0]

        then:
        r.status == Status.FAIL
        r.observed.contains('quorum NOT achieved')
        r.hint.contains('authority on 1.3 and 2.0')
        r.hint.contains('self-fence')
        r.fix == null
        r.tier == HealthChecker.Tier.NONE
    }

    def 'an endpoint that gives nothing, or not the documented JSON, is not checked, never a pass'() {
        expect:
        ids(healthy20(aq(null, null)), 'CLU-14')*.status == [Status.NOT_CHECKED] * 3
        ids(healthy20(aq('<html>502 Bad Gateway</html>', null)), 'CLU-14')[0].status == Status.NOT_CHECKED
        ids(healthy20(aq('{"somethingElse":1}', null)), 'CLU-14')[0].status == Status.NOT_CHECKED
        ids(healthy20(aq(null, null)), 'CLU-14')[0].observed.contains('7443/quorum')
    }

    def 'a probe too old to carry the section raises no agent-quorum row at all'() {
        expect:
        ids(healthy20(NO_SECTION), 'CLU-14') == []
        ids(healthy20(NO_SECTION), 'CLU-15') == []
    }

    def 'HCI Ceph is not judged on agent quorum'() {
        given:
        HealthChecker.Input i = Fixture.input((1..3).collect { Fixture.host("hvm-node0${it}".toString(), Fixture.facts('probe-timesyncd', "hvm-node0${it}".toString())) }, 'mvm-1.3-ubuntu-24.04-ceph-hci-amd64')

        expect:
        ids(i, 'CLU-14') == []
        ids(i, 'CLU-15') == []
    }

    def 'the agent quorum is a quick check too, not kept for full checks only'() {
        expect:
        !('CLU-14' in HealthMonitor.FULL_ONLY)
        !('CLU-15' in HealthMonitor.FULL_ONLY)
    }

    // ------------------------------------------------------------------ CLU-15: three-way agreement

    def 'with the agent, corosync and Morpheus listing the same members, the three-way check passes'() {
        expect:
        ids(healthy20(), 'CLU-15')*.status == [Status.PASS, Status.PASS, Status.PASS]
    }

    def 'a corosync member the agent has dropped fails, naming it as the freeze precondition'() {
        when:
        HealthChecker.Result r = ids(healthy20(aq('{"quorumAchieved":true,"totalNodes":2,"reachableNodes":2,"nodes":[{"name":"hvm-node02"},{"name":"hvm-node03"}]}', '["hvm-node02","hvm-node03"]')), 'CLU-15')[0]

        then:
        r.status == Status.FAIL
        r.severity == Severity.HIGH
        r.observed.contains('hvm-node01')
        r.hint.contains('precondition')
        r.hint.contains('CLU-05')
        r.fix == null
    }

    def 'a member listed by its ring address rather than its name still counts as present'() {
        expect:
        ids(healthy20(aq(HEALTHY_JSON, '["192.0.2.1","192.0.2.2","192.0.2.3"]')), 'CLU-15')*.status == [Status.PASS] * 3
    }

    def 'an agent list in identifiers nothing known matches is not judged, rather than failed'() {
        when:
        HealthChecker.Result r = ids(healthy20(aq('{"quorumAchieved":true,"nodes":[{"name":"aaaa"},{"name":"bbbb"},{"name":"cccc"}]}', '["aaaa","bbbb","cccc"]')), 'CLU-15')[0]

        then:
        r.status == Status.NOT_CHECKED
        r.observed.contains('could not match')
    }

    def 'no member data from the agent is not checked'() {
        expect:
        ids(healthy20(aq('{"quorumAchieved":true,"totalNodes":3,"reachableNodes":3}', null)), 'CLU-15')*.status == [Status.NOT_CHECKED] * 3
    }

    // ------------------------------------------------------------------ CLU-03 / CLU-04 re-aim

    /** A genuine two-node corosync cluster: expected votes 2. */
    private static Closure<String> twoNodeVotes = { String t -> t.replace('Expected votes:   3', 'Expected votes:   2').replace('Highest expected: 3', 'Highest expected: 2').replace('Total votes:      3', 'Total votes:      2') }

    @Unroll
    def 'on a two-node corosync cluster CLU-03 and CLU-04 are informational on #layout, whatever Quorate says'() {
        when:
        List<HealthChecker.Result> q3 = ids(cluster(sample, layout, twoNodeVotes), 'CLU-03')
        List<HealthChecker.Result> q4 = ids(cluster(sample, layout, twoNodeVotes), 'CLU-04')

        then:
        q3*.status.every { it == Status.NA }
        q4*.status.every { it == Status.NA }
        q3[0].observed.contains('CLU-14')
        q4[0].expected.contains('agent quorum')

        where:
        layout            | sample
        Fixture.LAYOUT_20 | 'probe-healthy'
        Fixture.LAYOUT_13 | 'probe-timesyncd'
    }

    def 'a two-node cluster that reads Quorate No is still only informational, not a failure'() {
        when:
        HealthChecker.Result r = ids(timesyncd13 { String t -> twoNodeVotes.call(t).replace('Quorate:          Yes', 'Quorate:          No').replace('Flags:            Quorate', 'Flags:') }, 'CLU-03')[0]

        then:
        r.status == Status.NA
        r.observed.contains('expected on a two-node')
    }

    def 'a three-node cluster that is genuinely not quorate still fails CLU-03, with a note that the agent decides'() {
        when:
        HealthChecker.Result r = ids(healthy20 { it.replace('Quorate:          Yes', 'Quorate:          No') }, 'CLU-03').find { it.host == 'hvm-node01' }

        then:
        r.status == Status.FAIL
        r.hint.contains('CLU-14')
        r.hint.contains('stretch')
    }

    def 'on a layout that is not 1.3 or 2.0, corosync Quorate still decides CLU-03 as before'() {
        when:
        HealthChecker.Result r = check(cluster('probe-timesyncd', 'mvm-1.2-ubuntu-22.04-std-morpheus-amd64', { it.replace('Quorate:          Yes', 'Quorate:          No') })).find { it.id == 'CLU-03' && it.host == 'hvm-node01' }

        then:
        r.status == Status.FAIL
        r.hint == null
    }

    def 'the frozen three-node cluster still fails CLU-04, as a lost vote there is not by design'() {
        expect:
        check(Fixture.frozen()).find { it.id == 'CLU-04' }.status == Status.FAIL
    }

    // ------------------------------------------------------------------ CLU-12 reword

    def 'with no corosync qdevice, CLU-12 is NA and says the HVM witness is a Distributed Worker, not a qdevice'() {
        when:
        HealthChecker.Result r = ids(healthy20(), 'CLU-12')[0]

        then:
        r.status == Status.NA
        r.observed.contains('Distributed Worker')
        r.observed.contains('not a corosync qdevice')
        r.observed.contains('CLU-14')
        r.hint.contains('agent quorum')
    }

    // ------------------------------------------------------------------ MIG-02: TCP 7443 beside 22

    private static HealthChecker.Input oneHost20(Closure<String> edit) {
        Fixture.input([Fixture.host('hvm-node02', Fixture.facts('probe-healthy', 'hvm-node02', edit))], Fixture.LAYOUT_20)
    }

    @Unroll
    def 'MIG-02 reports #why'() {
        when:
        HealthChecker.Result r = ids(oneHost20 { it.replace('PEER=192.0.2.1|0|0.25|1|1|1500', line).replace('PEER=192.0.2.3|0|0.31|1|1|1500', 'PEER=192.0.2.3|0|0.31|1|1|1500|1') }, 'MIG-02')[0]

        then:
        r.status == status
        r.observed == observed

        where:
        why                               | line                                     | status      | observed
        'both ports open'                 | 'PEER=192.0.2.1|0|0.25|1|1|1500|1'       | Status.PASS | '2 peer(s)'
        'TCP 7443 closed, 22 open'        | 'PEER=192.0.2.1|0|0.25|1|1|1500|0'       | Status.FAIL | 'TCP 7443 closed to 192.0.2.1'
        'TCP 22 closed, 7443 open'        | 'PEER=192.0.2.1|0|0.25|0|1|1500|1'       | Status.FAIL | 'TCP 22 closed to 192.0.2.1'
        'both ports closed'               | 'PEER=192.0.2.1|0|0.25|0|1|1500|0'       | Status.FAIL | 'TCP 22 closed to 192.0.2.1; TCP 7443 closed to 192.0.2.1'
    }

    def 'an older probe with no 7443 field is judged on 22 alone, as before'() {
        when:
        HealthChecker.Result r = ids(oneHost20 { it.replace('PEER=192.0.2.1|0|0.25|1|1|1500', 'PEER=192.0.2.1|0|0.25|0|1|1500') }, 'MIG-02')[0]

        then:
        r.status == Status.FAIL
        r.observed == 'TCP 22 closed to 192.0.2.1'
    }

    def 'MIG-02 still carries 7443 in what it expects'() {
        expect:
        ids(healthy20(), 'MIG-02')[0].expected == 'TCP 22 and 7443 between hosts'
    }

    // ------------------------------------------------------------------ HostFacts parsing

    def 'the agent quorum JSON is parsed into its documented fields, defensively'() {
        when:
        HostFacts f = HostFacts.parse(Fixture.text('probe-healthy'))

        then:
        f.agentQuorum.achieved == true
        f.agentQuorum.fenced == false
        f.agentQuorum.total == 3
        f.agentQuorum.reachable == 3
        f.agentQuorumNodes.containsAll(['hvm-node01', 'hvm-node02', 'hvm-node03'])
    }

    def 'a /quorum body that is not a JSON object leaves the map null but still reads the node file'() {
        given:
        String t = aq('[1,2,3]', '["hvm-node02"]').call(Fixture.text('probe-healthy'))

        when:
        HostFacts f = HostFacts.parse(t)

        then:
        f.agentQuorum == null
        f.agentQuorumNodes == ['hvm-node02']
    }

    def 'a peer line carries the 7443 result as its seventh field, null when absent'() {
        expect:
        HostFacts.parse(Fixture.text('probe-healthy')).peers.every { it.agent == null }
        HostFacts.parse(aq(HEALTHY_JSON).call(Fixture.text('probe-healthy')).replace('PEER=192.0.2.1|0|0.25|1|1|1500', 'PEER=192.0.2.1|0|0.25|1|1|1500|0')).peers.find { it.addr == '192.0.2.1' }.agent == false
    }

    def 'a host whose probe predates the section has no agent quorum, and it is not an error'() {
        when:
        HostFacts f = HostFacts.parse(NO_SECTION.call(Fixture.text('probe-healthy')))

        then:
        !f.sections.containsKey('agent_quorum')
        f.agentQuorum == null
        f.agentQuorumNodes == []
    }
}
