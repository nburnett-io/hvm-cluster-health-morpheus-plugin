package org.hvmhealth

import org.hvmhealth.checks.HealthChecker
import org.hvmhealth.checks.HealthChecker.Status
import org.hvmhealth.fixes.Preflight
import org.hvmhealth.fixes.VlanTest
import org.hvmhealth.model.HostFacts
import org.hvmhealth.morpheus.HealthMonitor
import spock.lang.Specification

class OperationsSpec extends Specification {
    private static List<HealthChecker.Result> check(HealthChecker.Input i) { new HealthChecker().check(i) }

    /** Three empty healthy hosts; opts: maintenance host name, edit per host, vms. */
    private static HealthChecker.Input cluster(Map opts = [:]) {
        Fixture.input((1..((opts.hosts ?: 3) as int)).collect { int n ->
            String name = "hvm-node0${n}".toString()
            Closure<String> e = (opts.edit as Closure<String>) ?: { String t, String h -> t }
            HostFacts f = Fixture.facts('probe-healthy', name) { String t -> e.call(t.replace(' 5    vm-one   running', ''), name) }
            Fixture.host(name, f, [maintenance: name == opts.maintenance])
        }, Fixture.LAYOUT_20, null, (opts.vms ?: []) as List<Map>)
    }

    // ------------------------------------------------------------------ pre-flight

    def 'an empty host in a full cluster can go, with the warning about what no margin means'() {
        when:
        Map p = Preflight.hostDown(cluster(), 'hvm-node02')

        then:
        p.verdict == 'CAUTION'
        p.votesAfter == 2
        p.cautions[0].contains('No margin left')
        p.blockers == []
        p.impact == 'no running VMs'
    }

    def 'a four-host cluster keeps margin, so the answer is simply go'() {
        given:
        Closure<String> four = { String t, String n -> t.replace('Total votes:      3', 'Total votes:      4').replace('Expected votes:   3', 'Expected votes:   4').replace('Quorum:           2', 'Quorum:           3') + '' }
        HealthChecker.Input i = cluster(edit: four)
        i.hosts.each { it.facts.totalVotes = 5; it.facts.quorumVotes = 3 }

        expect:
        Preflight.hostDown(i, 'hvm-node02').verdict == 'GO'
    }

    def 'a cluster that would lose quorum is told no'() {
        given:
        HealthChecker.Input i = cluster()
        i.hosts.each { it.facts.totalVotes = 2 }

        when:
        Map p = Preflight.hostDown(i, 'hvm-node02')

        then:
        p.verdict == 'NO'
        p.blockers.any { it.contains('lose quorum') }
    }

    def 'a frozen cluster is told no'() {
        expect:
        Preflight.hostDown(Fixture.frozen(), 'hvm-node02').blockers.any { it.contains('clear the freeze') }
    }

    def 'every VM says where it can go, and a VM with nowhere to go is a no'() {
        given:
        long gib = 1L << 30
        HealthChecker.Input i = cluster(vms: [[name: 'small', host: 'hvm-node02', power: 'on', memory: 4 * gib, tenant: 'Acme'], [name: 'huge', host: 'hvm-node02', power: 'on', memory: 500 * gib, tenant: 'Globex'], [name: 'off', host: 'hvm-node02', power: 'off', memory: 500 * gib]])

        when:
        Map p = Preflight.hostDown(i, 'hvm-node02')

        then:
        p.verdict == 'NO'
        p.moves.find { it.vm == 'small' }.targets == ['hvm-node01', 'hvm-node03']
        p.moves.find { it.vm == 'huge' }.targets == []
        p.blockers.any { it.contains('huge') }
        !p.moves.any { it.vm == 'off' }
        p.impact == '2 running VM(s): Acme (1), Globex (1)'
    }

    def 'a CPU the target lacks blocks a move under host-passthrough, and not with a named model'() {
        given:
        Closure<HealthChecker.Input> build = { String model ->
            Closure<String> e = { String t, String n -> n == 'hvm-node03' ? t.replace('CPU_FLAGS=08dd0072dea29009', 'CPU_FLAGS=60e9ed41fa1e8889') : t }
            HealthChecker.Input i = cluster(edit: e, vms: [[name: 'vm', host: 'hvm-node01', power: 'on', memory: 1L << 30]])
            i.clusterConfig = [cpuModel: model]
            i
        }

        expect:
        Preflight.hostDown(build('host-passthrough'), 'hvm-node01').moves[0].targets == ['hvm-node02']
        Preflight.hostDown(build('Skylake-Server'), 'hvm-node01').moves[0].targets == ['hvm-node02', 'hvm-node03']
    }

    // ------------------------------------------------------------------ safe eject

    def 'eject needs maintenance mode, and is ready once in it'() {
        expect:
        !Preflight.ejectReadiness(cluster(), 'hvm-node02').ready
        Preflight.ejectReadiness(cluster(), 'hvm-node02').blockers.any { it.contains('not in maintenance mode') }
        Preflight.ejectReadiness(cluster(maintenance: 'hvm-node02'), 'hvm-node02').ready
    }

    def 'a mount something still has open blocks the eject before it starts'() {
        given:
        HealthChecker.Input i = cluster(maintenance: 'hvm-node02', edit: { String t, String n -> t.replace('FS_TIMEOUT=0', 'FS_TIMEOUT=0\nIN_USE=/mnt/x|2') })

        expect:
        Preflight.ejectReadiness(i, 'hvm-node02').blockers.any { it.contains('2 process(es) still have /mnt/x open') }
    }

    def 'eject warns that two hosts remain with no tolerance, and refuses to leave one'() {
        when:
        Map three = Preflight.ejectReadiness(cluster(maintenance: 'hvm-node02'), 'hvm-node02')
        Map two = Preflight.ejectReadiness(cluster(maintenance: 'hvm-node02', hosts: 2), 'hvm-node02')

        then:
        three.warning.contains('Two hosts will remain')
        three.nodeId == 2
        three.nodeAddr == '192.0.2.2'
        two.blockers.any { it.contains('would leave one') }
    }

    def 'the node is cut from corosync.conf cleanly, and the version raised so the reload takes'() {
        given:
        List<String> conf = Fixture.facts('probe-healthy', 'x').sections.corosync_conf

        when:
        String out = Preflight.withoutNode(conf, '192.0.2.2')

        then:
        !out.contains('192.0.2.2')
        !out.contains('name: hvm-node02')
        out.contains('192.0.2.1')
        out.contains('192.0.2.3')
        out.contains('config_version: 4')
        HostFacts.parse("@@corosync_conf\n${out}@@end\nCOMPLETE=1\n").nodelist*.nodeid == [1, 3]
    }

    def 'an address not in the file is refused rather than writing the same file back'() {
        when:
        Preflight.withoutNode(Fixture.facts('probe-healthy', 'x').sections.corosync_conf, '203.0.113.9')

        then:
        thrown(IllegalStateException)
    }

    def 'the leaving host refuses to unmount anything in use, and to stop services with GFS2 mounted'() {
        expect:
        Preflight.unmountScript().contains('fuser -m')
        Preflight.unmountScript().contains('REFUSED: still in use')
        Preflight.unmountScript().indexOf('fuser -m') < Preflight.unmountScript().indexOf('umount "$m"')
        Preflight.leaveScript().contains('REFUSED: GFS2 is still mounted')
        Preflight.leaveScript().indexOf('--now dlm') < Preflight.leaveScript().indexOf('--now corosync')
    }

    // ------------------------------------------------------------------ VLANs

    private static HealthChecker.Input vlans(Map<String, String> seen) {
        HealthChecker.Input i = Fixture.input(seen.collect { String h, String lines ->
            Fixture.host(h, Fixture.facts('probe-healthy', h) { String t -> t.replace('@@ring\n', "@@vlans\n${lines}\n@@ring\n".toString()).replace(' 5    vm-one   running', '') })
        }, Fixture.LAYOUT_20)
        i.networks = [[name: 'Tenant', vlan: 300, gateway: '192.0.2.254']]
        i
    }

    def 'a VLAN its peers hear and a host does not is flagged as a likely pruned trunk'() {
        when:
        HealthChecker.Result r = check(vlans(['hvm-node01': 'VLAN_SEEN=300|4', 'hvm-node02': 'VLAN_SEEN=195|9'])).find { it.id == 'NET-11' }

        then:
        r.status == Status.WARN
        r.observed == 'no traffic on hvm-node02; heard on hvm-node01'
    }

    def 'a VLAN every host hears passes, and one nobody hears says nothing'() {
        expect:
        check(vlans(['hvm-node01': 'VLAN_SEEN=300|4', 'hvm-node02': 'VLAN_SEEN=300|2'])).find { it.id == 'NET-11' }.status == Status.PASS
        !check(vlans(['hvm-node01': 'VLAN_SEEN=195|4', 'hvm-node02': 'VLAN_SEEN=195|2'])).any { it.id == 'NET-11' }
    }

    def 'the VLAN test reads per uplink, separating a pruned trunk from a silent gateway'() {
        when:
        Map parsed = VlanTest.parse('''RESULT=eno1|195|192.0.2.1|3|br.195
RESULT=eno1|300|192.0.2.2|3|hhv300
RESULT=eno1|400|192.0.2.3|0|hhv400
RESULT=eno2|195|192.0.2.1|3|br.195
RESULT=eno2|300|192.0.2.2|0|hhv300
RESULT=eno2|400|192.0.2.3|0|hhv400
RESTORED=eno1
END
''')
        Map s = VlanTest.summary(parsed)

        then:
        parsed.done
        parsed.restored == 'eno1'
        !s.ok
        s.uplinkFailures == ['VLAN 300 does not pass on eno2']
        s.nowhere == ['VLAN 400: gateway 192.0.2.3 did not answer on any uplink']
    }

    def 'a test that tested nothing is never a pass'() {
        expect:
        !VlanTest.summary(VlanTest.parse('RESTORED=eno1\nEND\n')).ok
    }

    def 'an unfinished test is not done'() {
        expect:
        !VlanTest.parse('RESULT=eno1|195|192.0.2.1|3|x\n').done
        VlanTest.parse('ERROR=no active-backup bond\nEND\n').error.startsWith('no active-backup')
    }

    def 'the VLAN test always restores, and has a backstop if killed'() {
        given:
        String s = new String(VlanTest.launchScript([[vlan: 300, gateway: '192.0.2.1']]).find(/echo '([A-Za-z0-9+\/=]+)' \| base64 -d > \/run\/hvm-health-vlan-test\.sh/) { all, b -> b }.decodeBase64(), 'UTF-8')

        expect:
        s.contains('trap finish EXIT')
        s.contains('sleep 150')
        s.contains('for pair in 300:192.0.2.1;') && !s.contains('300|')
        VlanTest.launchScript([[vlan: 300, gateway: '192.0.2.1']]).contains('nohup setsid')
    }

    def 'unsafe input never reaches the host'() {
        when:
        VlanTest.launchScript([[vlan: '300; reboot', gateway: '192.0.2.1']])

        then:
        thrown(IllegalArgumentException)
    }

    // ------------------------------------------------------------------ tenant impact

    def 'failures on a host name its tenants, failures on the cluster name them all, passes name nobody'() {
        given:
        HealthChecker.Input i = Fixture.input((1..2).collect { int n ->
            String name = "hvm-node0${n}".toString()
            Fixture.host(name, Fixture.facts('probe-healthy', name) { String t -> (n == 2 ? t.replace('SLAVE=vs0bond|eno1|up', 'SLAVE=vs0bond|eno1|down') : t).replace(' 5    vm-one   running', '') })
        }, Fixture.LAYOUT_20, null, [[name: 'a', host: 'hvm-node01', power: 'on', tenant: 'Globex'], [name: 'b', host: 'hvm-node02', power: 'on', tenant: 'Acme'], [name: 'c', host: 'hvm-node02', power: 'on', tenant: 'Acme'], [name: 'd', host: 'hvm-node02', power: 'off', tenant: 'Initech']])

        when:
        List<HealthChecker.Result> rs = check(i)

        then:
        rs.find { it.id == 'NET-01' && it.status == Status.FAIL }.impact == '2 running VM(s): Acme (2)'
        rs.find { it.id == 'TIM-06' }.impact == '3 running VM(s): Acme (2), Globex (1)'
        rs.find { it.id == 'CLU-03' }.impact == null
    }

    // ------------------------------------------------------------------ storage latency

    private static HealthChecker.Input withStat(String line) {
        Fixture.input([Fixture.host('hvm-node02', Fixture.facts('probe-healthy', 'hvm-node02') { it.replace('@@ring\n', "@@storage\n${line}\n@@ring\n".toString()) })], Fixture.LAYOUT_20)
    }

    def 'storage latency is judged per LUN, and a quiet LUN says nothing'() {
        expect:
        check(withStat("STAT=3624a|${r}|${rt}|${w}|${wt}|0")).find { it.id == 'STO-06' }?.status == want

        where:
        r   | rt   | w   | wt    | want
        100 | 200  | 100 | 200   | Status.PASS
        10  | 300  | 0   | 0     | Status.WARN
        10  | 2000 | 0   | 0     | Status.FAIL
        0   | 0    | 0   | 0     | null
    }

    def 'slow GFS2 metadata warns'() {
        given:
        HealthChecker.Input i = Fixture.input([Fixture.host('hvm-node02', Fixture.facts('probe-healthy', 'hvm-node02') { it.replace('FS_TIMEOUT=0', 'FS_TIMEOUT=0\nGFS_STAT=/mnt/x|900') })], Fixture.LAYOUT_20)

        expect:
        check(i).find { it.id == 'GFS-08' }.status == Status.WARN
    }

    // ------------------------------------------------------------------ hardware

    private static String hw(String extra) {
        "@@hardware\nHW_PRODUCT=Server X\nHW_BIOS=1.0\nHW_BMC=7.00\n${extra}\n@@end"
    }

    private static HealthChecker.Host hwHost(String name, String extra) {
        Fixture.host(name, Fixture.facts('probe-healthy', name) { it.replace('@@end', hw(extra)).replace(' 5    vm-one   running', '') })
    }

    private static List<HealthChecker.Result> hwCheck(String extra) {
        check(Fixture.input([hwHost('hvm-node02', extra)], Fixture.LAYOUT_20))
    }

    def 'power supply redundancy, as a real BMC reports it'() {
        expect:
        hwCheck('SDR=Power Supply|PS Redundancy|ok|Fully Redundant\nSDR=Power Supply|Status|ok|Presence detected').find { it.id == 'HW-01' }.status == Status.PASS
        hwCheck('SDR=Power Supply|PS Redundancy|ns|Disabled\nSDR=Power Supply|Status|ok|Presence detected').find { it.id == 'HW-01' }.status == Status.WARN
        hwCheck('SDR=Power Supply|Status|cr|Failure detected').find { it.id == 'HW-01' }.status == Status.FAIL
    }

    def 'memory errors: uncorrectable fails and correctable warns'() {
        expect:
        hwCheck('EDAC=mc0|0|0').find { it.id == 'HW-02' }.status == Status.PASS
        hwCheck('EDAC=mc0|4|0').find { it.id == 'HW-02' }.status == Status.WARN
        hwCheck('EDAC=mc0|4|1').find { it.id == 'HW-02' }.status == Status.FAIL
    }

    def 'a temperature out of limits fails'() {
        expect:
        hwCheck('SDR=Temperature|Inlet Temp|ok|20 degrees C').find { it.id == 'HW-03' }.status == Status.PASS
        hwCheck('SDR=Temperature|Inlet Temp|cr|47 degrees C').find { it.id == 'HW-03' }.observed == 'Inlet Temp: 47 degrees C'
    }

    def 'recent serious hardware log events warn; old ones, cleared ones and non-critical ones do not'() {
        given:
        def fmt = new java.text.SimpleDateFormat('MM/dd/yy')
        String recent = fmt.format(new Date(1793385845000L - 86400000L))
        String old = fmt.format(new Date(1793385845000L - 40L * 86400000L))

        expect:
        hwCheck("SEL=  21 | ${recent} | 06:45:28 EDT | Memory ECC | Uncorrectable ECC | Asserted").find { it.id == 'HW-04' }.status == Status.WARN
        hwCheck("SEL=  21 | ${old} | 06:45:28 EDT | Memory ECC | Uncorrectable ECC | Asserted").find { it.id == 'HW-04' }.status == Status.PASS
        hwCheck("SEL=  21 | ${recent} | 06:45:28 EDT | Temp | Upper Critical going high | Deasserted").find { it.id == 'HW-04' }.status == Status.PASS
        hwCheck("SEL=  21 | ${recent} | 06:45:28 EDT | Temp | Upper Non-critical going high | Asserted").find { it.id == 'HW-04' }.status == Status.PASS
    }

    def 'firmware is compared among identical servers, not across models'() {
        given:
        HealthChecker.Host a = hwHost('hvm-node01', 'NIC=eno1|9.5|10000')
        HealthChecker.Host b = Fixture.host('hvm-node02', Fixture.facts('probe-healthy', 'hvm-node02') { it.replace('@@end', hw('NIC=eno1|9.5|10000').replace('HW_BIOS=1.0', 'HW_BIOS=2.0')).replace(' 5    vm-one   running', '') })
        HealthChecker.Host other = Fixture.host('hvm-node02', Fixture.facts('probe-healthy', 'hvm-node02') { it.replace('@@end', hw('NIC=eno1|9.5|10000').replace('HW_BIOS=1.0', 'HW_BIOS=2.0').replace('Server X', 'Server Y')).replace(' 5    vm-one   running', '') })

        expect:
        check(Fixture.input([a, b], Fixture.LAYOUT_20)).find { it.id == 'HW-06' }.observed == 'hvm-node01=1.0, hvm-node02=2.0'
        !check(Fixture.input([a, other], Fixture.LAYOUT_20)).any { it.id == 'HW-06' }
    }

    def 'uplinks at different speeds warn'() {
        expect:
        hwCheck('NIC=eno1|9.5|10000\nNIC=eno2|9.5|1000').find { it.id == 'HW-05' }.status == Status.WARN
    }

    // ------------------------------------------------------------------ fleet

    def 'the fleet sorts the worst first, and an unchecked cluster above a healthy one'() {
        expect:
        ['HEALTHY', 'NOT CHECKED', 'WARNING', 'CRITICAL', 'DEGRADED'].sort { HealthMonitor.severityRank(it) } == ['CRITICAL', 'NOT CHECKED', 'DEGRADED', 'WARNING', 'HEALTHY']
    }

    def 'hardware findings only come from full checks, so quick checks keep them'() {
        expect:
        HealthMonitor.FULL_ONLY.containsAll(['HW-01', 'HW-02', 'HW-06'])
    }
}
