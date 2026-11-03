package org.hvmhealth

import org.hvmhealth.checks.HealthChecker
import org.hvmhealth.checks.HealthChecker.Status
import org.hvmhealth.fixes.Fixes
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

    // ------------------------------------------------------------------ removing a host

    def 'removal needs maintenance mode, and is ready once in it'() {
        expect:
        !Preflight.removeReadiness(cluster(), 'hvm-node02').ready
        Preflight.removeReadiness(cluster(), 'hvm-node02').blockers.any { it.contains('not in maintenance mode') }
        Preflight.removeReadiness(cluster(maintenance: 'hvm-node02'), 'hvm-node02').ready
    }

    def 'a mount something still has open blocks the removal, since Morpheus would carry on regardless'() {
        given:
        HealthChecker.Input i = cluster(maintenance: 'hvm-node02', edit: { String t, String n -> t.replace('FS_TIMEOUT=0', 'FS_TIMEOUT=0\nIN_USE=/mnt/x|2') })

        expect:
        Preflight.removeReadiness(i, 'hvm-node02').blockers.any { it.contains('2 process(es) still have /mnt/x open') }
    }

    def 'a VM recorded on the host blocks the removal even when it is stopped'() {
        given:
        HealthChecker.Input i = cluster(maintenance: 'hvm-node02', vms: [[name: 'parked', host: 'hvm-node02', power: 'off']])

        expect:
        Preflight.removeReadiness(i, 'hvm-node02').blockers.any { it.contains('parked') }
    }

    def 'removal warns that two hosts remain with no tolerance, and refuses to leave one'() {
        when:
        Map three = Preflight.removeReadiness(cluster(maintenance: 'hvm-node02'), 'hvm-node02')
        Map two = Preflight.removeReadiness(cluster(maintenance: 'hvm-node02', hosts: 2), 'hvm-node02')

        then:
        three.warning.contains('Two hosts will remain')
        two.blockers.any { it.contains('would leave one') }
    }

    def 'a host record being removed is not a host, and stays visible until it is gone'() {
        given:
        HealthChecker.Input i = Fixture.healthy()
        i.removing = ['hvm-gone']

        when:
        List<HealthChecker.Result> rs = check(i)

        then:
        rs.find { it.id == 'MOR-04' }.status == Status.WARN
        rs.find { it.id == 'MOR-04' }.observed.contains('hvm-gone')
        !rs.any { it.host == 'hvm-gone' }
    }

    def 'a host that went silent still names the VMs recorded on it'() {
        given:
        HealthChecker.Input i = Fixture.healthy()
        i.hosts[2].facts = new HostFacts()
        i.morpheusVms = [[name: 'domain-uuid', label: 'vm-a', host: 'hvm-node03', power: 'off', tenant: 'Acme']]

        when:
        List<HealthChecker.Result> rs = check(i)

        then:
        rs.find { it.id == 'PRB-01' }.impact.startsWith('1 VM(s) recorded on this host: vm-a.')
    }

    // ------------------------------------------------------------------ VLANs

    private static HealthChecker.Input vlans(Map<String, String> seen) {
        HealthChecker.Input i = Fixture.input(seen.collect { String h, String lines ->
            Fixture.host(h, Fixture.facts('probe-healthy', h) { String t -> t.replace('@@ring\n', "@@vlans\n${lines}\n@@ring\n".toString()).replace(' 5    vm-one   running', '') })
        }, Fixture.LAYOUT_20)
        i.networks = [[name: 'Tenant', vlan: 300, gateway: '192.0.2.254']]
        i
    }

    def 'a VLAN its peers hear and a host does not is a hint, which raises no alarm as it comes and goes with traffic'() {
        when:
        HealthChecker.Result r = check(vlans(['hvm-node01': 'VLAN_SEEN=300|4', 'hvm-node02': 'VLAN_SEEN=195|9'])).find { it.id == 'NET-11' }

        then:
        r.status == Status.NOT_CHECKED
        r.hint.contains('VLAN test')
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

    private static String vlanScript() {
        new String(VlanTest.launchScript([[vlan: 300, gateway: '192.0.2.1']]).find(/echo '([A-Za-z0-9+\/=]+)' \| base64 -d > \/run\/hvm-health-vlan-test\.sh/) { all, b -> b }.decodeBase64(), 'UTF-8')
    }

    def 'the VLAN test always restores, and has a backstop if killed'() {
        given:
        String s = vlanScript()

        expect:
        s.contains('trap finish EXIT')
        s.contains('( for _ in $(seq 1 "$budget"); do sleep 1; done; put_back )')
        s.contains('for pair in 300:192.0.2.1;') && !s.contains('300|')
        VlanTest.launchScript([[vlan: 300, gateway: '192.0.2.1']]).contains('nohup setsid')
    }

    def 'without arping the test stops before anything moves, and says so'() {
        given:
        String s = vlanScript()
        Map parsed = VlanTest.parse('ERROR=arping not installed: not tested\nEND\n')

        expect:
        s.contains("command -v arping >/dev/null 2>&1 || { echo 'ERROR=arping not installed: not tested'; echo END; exit 0; }")
        s.indexOf('command -v arping') < s.indexOf('active_slave')
        s.indexOf('command -v arping') < s.indexOf('trap finish EXIT')
        parsed.done
        parsed.error == 'arping not installed: not tested'
        !VlanTest.summary(parsed).ok
        VlanTest.summary(parsed).uplinkFailures == []
        VlanTest.summary(parsed).nowhere == []
    }

    def 'arping that gives no result is not tested: neither a pass nor a VLAN failing'() {
        when:
        Map parsed = VlanTest.parse('''RESULT=eno1|300|192.0.2.2|none|hhv300|bond0
RESULT=eno2|300|192.0.2.2|none|hhv300|bond0
RESTORED=eno1
END
''')
        Map s = VlanTest.summary(parsed)

        then:
        parsed.results*.untested == [true, true]
        !s.ok
        s.notTested == ['VLAN 300 on eno1: arping gave no result, so this was not tested', 'VLAN 300 on eno2: arping gave no result, so this was not tested']
        s.uplinkFailures == []
        s.nowhere == []
    }

    def 'one failed arping leaves the test short of a pass, and does not hide what the rest proved'() {
        when:
        Map s = VlanTest.summary(VlanTest.parse('''RESULT=eno1|300|192.0.2.2|3|hhv300|bond0
RESULT=eno2|300|192.0.2.2|none|hhv300|bond0
END
'''))

        then:
        !s.ok
        s.notTested.size() == 1
        s.uplinkFailures == []
        s.carriedBy == ['VLAN 300: answered on bond0']
    }

    def 'the script tests every bond on a bridge, leaves the rest alone, and names the bond in each result'() {
        given:
        String s = vlanScript()

        expect:
        !s.contains('break')
        s.contains('for bond in $bonds; do')
        s.contains('[ -n "$o$m" ] || continue')
        s.contains('RESULT=$s|$v|$gw|${n:-none}|$d|$bond')
        s.contains('SKIPPED=')
        s.contains('echo "BUDGET=$budget"')
        !s.contains('/run/hvm-health-vlan-if')
    }

    def 'each bond is put back, its interfaces gone, before the next is touched'() {
        given:
        String s = vlanScript()
        String loop = s.substring(s.indexOf('for bond in $bonds; do'))

        expect:
        loop.indexOf('echo "${ORIG[$bond]}"') > loop.indexOf('RESULT=')
        loop.indexOf('clean') > loop.indexOf('echo "${ORIG[$bond]}"')
    }

    def 'a VLAN that answers on one bond is carried by it, and silence on another bond is not a failure'() {
        when:
        Map parsed = VlanTest.parse('''SKIPPED=vxln|its uplink is not a bond, so it cannot be tested one uplink at a time
BUDGET=112
RESULT=eno1|300|192.0.2.2|0|hhv300|bond0
RESULT=eno2|300|192.0.2.2|0|hhv300|bond0
RESULT=eno3|300|192.0.2.2|3|hhv300|bond1
RESULT=eno4|300|192.0.2.2|3|hhv300|bond1
RESTORED=eno1 eno3
END
''')
        Map s = VlanTest.summary(parsed)

        then:
        parsed.budget == 112L
        parsed.results*.bond == ['bond0', 'bond0', 'bond1', 'bond1']
        parsed.skipped*.bridge == ['vxln']
        s.ok
        s.carriedBy == ['VLAN 300: answered on bond1']
        s.uplinkFailures == []
        s.nowhere == []
    }

    def 'a VLAN pruned from one uplink of the bond that carries it names that bond'() {
        when:
        Map s = VlanTest.summary(VlanTest.parse('''RESULT=eno1|300|192.0.2.2|0|hhv300|bond0
RESULT=eno2|300|192.0.2.2|0|hhv300|bond0
RESULT=eno3|300|192.0.2.2|3|hhv300|bond1
RESULT=eno4|300|192.0.2.2|0|hhv300|bond1
END
'''))

        then:
        !s.ok
        s.uplinkFailures == ['VLAN 300 does not pass on eno4 (bond1)']
    }

    def 'silent on every bond tested, with another bridge untested, is inconclusive rather than a failure'() {
        when:
        Map s = VlanTest.summary(VlanTest.parse('''SKIPPED=cmpt|its uplink is not a bond, so it cannot be tested one uplink at a time
RESULT=eno1|300|192.0.2.2|0|hhv300|bond0
RESULT=eno2|300|192.0.2.2|0|hhv300|bond0
END
'''))

        then:
        !s.ok
        s.nowhere == []
        s.uplinkFailures == []
        s.inconclusive == ['VLAN 300: gateway 192.0.2.2 did not answer on the bonds tested, and cmpt could not be tested']
    }

    def 'silent on every bond, with nothing left untested, says the gateway did not answer'() {
        when:
        Map s = VlanTest.summary(VlanTest.parse('''RESULT=eno1|300|192.0.2.2|0|hhv300|bond0
RESULT=eno3|300|192.0.2.2|0|hhv300|bond1
END
'''))

        then:
        s.nowhere == ['VLAN 300: gateway 192.0.2.2 did not answer on any uplink']
    }

    def 'unsafe input never reaches the host'() {
        when:
        VlanTest.launchScript([[vlan: '300; reboot', gateway: '192.0.2.1']])

        then:
        thrown(IllegalArgumentException)
    }

    // ------------------------------------------------------------------ the unmount before Morpheus's remove

    def 'the unmount refuses what is in use, has a time limit, and survives grep exits under set -e'() {
        given:
        String s = Preflight.unmountScript()

        expect:
        s.indexOf('fuser -m') < s.indexOf('umount "$m"')
        s.contains('REFUSED: still in use')
        s.contains('HUNG: umount')
        s.contains('exit 6')
        s.contains('done | head -1 || true)"')
        s.contains("grep -c '^name' || true)\"")
    }

    def 'a host still recovering holds the removal back'() {
        given:
        HealthChecker.Input i = cluster(maintenance: 'hvm-node02')
        i.hosts.find { it.name == 'hvm-node03' }.facts.fenceWaits = [[node: 9, actor: 3, since: 1L, now: 2L]]

        expect:
        Preflight.removeReadiness(i, 'hvm-node02').blockers.any { it.contains('fence request open') }
    }

    def 'a host that restarted minutes ago holds the removal back'() {
        given:
        HealthChecker.Input i = cluster(maintenance: 'hvm-node02')
        HostFacts f = i.hosts.find { it.name == 'hvm-node03' }.facts
        f.now = 1000000L
        f.boot = 999900L

        expect:
        Preflight.removeReadiness(i, 'hvm-node02').blockers.any { it.contains('restarted less than') }
    }

    // ------------------------------------------------------------------ GFS2 errors that outlive a reboot

    def 'a consistency error in the kernel log is a failure, and an unread log says it could not tell'() {
        given:
        HealthChecker.Input bad = Fixture.healthy()
        bad.hosts[1].facts = Fixture.facts('probe-healthy', 'hvm-node02') { String t -> t.replace(' 5    vm-one   running', '') + '@@gfs2\nGFS2_ERRORS_7D=2\nGFS2_ERROR_LAST=GFS2: fsid=c:d.1: fatal: filesystem consistency error\n' }

        expect:
        check(bad).find { it.id == 'GFS-10' && it.host == 'hvm-node02' }.status == Status.FAIL
        check(bad).find { it.id == 'GFS-10' && it.host == 'hvm-node02' }.observed.contains('consistency error')
        check(Fixture.healthy()).find { it.id == 'GFS-10' }.status == Status.NOT_CHECKED
    }

    def 'a journal count of 0 is a failed read, not a finding'() {
        expect:
        Fixture.facts('probe-healthy', 'x') { String t -> t + '@@journals\nJOURNALS=/dev/mapper/a|0\n' }.journals['/dev/mapper/a'] == null
    }

    // ------------------------------------------------------------------ the emergency plan

    def 'a host restarted into a stopped lockspace does not hold the emergency plan back'() {
        given:
        HealthChecker.Input i = Fixture.frozen()
        // hvm-node03 is stopped too, but dlm has not yet listed this node for it
        i.hosts[1].facts.fenceWaits = []

        when:
        Fixes.FencePlan p = Fixes.fencePlan(i, 1)

        then:
        !p.blockers.any { it.contains('is not waiting') }
    }

    // ------------------------------------------------------------------ an add that did not finish

    def 'an empty authkey says the add did not finish, and offers no rejoin'() {
        given:
        HealthChecker.Input i = Fixture.healthy()
        i.hosts[1].facts = Fixture.facts('probe-healthy', 'hvm-node02') { String t -> t.replace(' 5    vm-one   running', '').replace('@@corosync_key\n0123456789abcdef', '@@corosync_key\ne3b0c44298fc1c14').replace('corosync|active|enabled', 'corosync|failed|enabled') }

        when:
        List<HealthChecker.Result> rs = check(i)

        then:
        rs.find { it.id == 'CLU-13' }.host == 'hvm-node02'
        rs.find { it.id == 'CLU-13' }.hint.contains('add it again')
        rs.find { it.id == 'CLU-02' && it.host == 'hvm-node02' && it.title.startsWith('corosync') }.fix == null
        !rs.any { it.id == 'CLU-13' && it.host == 'hvm-node01' }
    }

    def 'a failed cluster record is noted, and a healthy one is not'() {
        given:
        HealthChecker.Input i = Fixture.healthy()
        i.clusterConfig = [recordStatus: 'failed']
        HealthChecker.Input fine = Fixture.healthy()
        fine.clusterConfig = [recordStatus: 'ok']

        expect:
        check(i).find { it.id == 'MOR-06' }.status == Status.WARN
        !check(fine).any { it.id == 'MOR-06' }
    }

    def 'a freeze shows the clock on the agent stopping VMs'() {
        when:
        HealthChecker.Result early = check(Fixture.frozen()).find { it.id == 'DLM-01' }

        then:
        early.observed.contains('stopping VMs')
    }

    // ------------------------------------------------------------------ what really happened, as checks

    private static HealthChecker.Input withAgent(String version, String site = null) {
        HealthChecker.Input i = Fixture.healthy(site)
        i.hosts.each { it.facts.agentVersion = version }
        i
    }

    def 'an agent older than the one that handled a freeze correctly is called out'() {
        expect:
        check(withAgent('3.2.6')).find { it.id == 'PKG-03' }.status == Status.WARN
        check(withAgent('3.2.6')).find { it.id == 'PKG-03' }.observed.contains('hvm-node01=3.2.6')
        check(withAgent('4.0.1')).find { it.id == 'PKG-03' }.status == Status.PASS
        check(withAgent('4.10.0')).find { it.id == 'PKG-03' }.status == Status.PASS
        check(withAgent('3.2.6', '{"agentMinVersion":"3.0.0"}')).find { it.id == 'PKG-03' }.status == Status.PASS
    }

    def 'an agent version that cannot be read or compared raises nothing'() {
        expect:
        !check(withAgent(null)).any { it.id == 'PKG-03' }
        check(withAgent('custom-build')).find { it.id == 'PKG-03' }.status == Status.PASS
        HealthChecker.compareVersions('3.10.0', '3.2.6') == 1
        HealthChecker.compareVersions('3.2.6', '3.2.6') == 0
        HealthChecker.compareVersions('x', '3.2.6') == null
    }

    def 'the manager running on the cluster it manages is found by address'() {
        given:
        HealthChecker.Input i = Fixture.healthy()
        i.clusterConfig = [managerAddrs: ['192.0.2.50']]
        i.morpheusVms = [[name: 'uuid', label: 'manager-vm', host: 'hvm-node02', power: 'on', ips: ['192.0.2.50']]]

        when:
        HealthChecker.Result r = check(i).find { it.id == 'HA-06' }

        then:
        r.status == Status.WARN
        r.host == 'hvm-node02'
        r.observed.contains('manager-vm')
    }

    def 'a manager elsewhere passes, and unknown addresses say nothing'() {
        given:
        HealthChecker.Input elsewhere = Fixture.healthy()
        elsewhere.clusterConfig = [managerAddrs: ['192.0.2.50']]
        elsewhere.morpheusVms = [[name: 'a', host: 'hvm-node02', power: 'on', ips: ['192.0.2.51']]]

        expect:
        check(elsewhere).find { it.id == 'HA-06' }.status == Status.PASS
        !check(Fixture.healthy()).any { it.id == 'HA-06' }
    }

    def 'a cluster address from DHCP warns'() {
        given:
        HealthChecker.Input i = Fixture.healthy()
        i.hosts[1].facts = Fixture.facts('probe-healthy', 'hvm-node02') { String t -> t.replace(' 5    vm-one   running', '').replace('RING_DEV=vs0', 'RING_DEV=vs0\nRING_DYNAMIC=1') }

        expect:
        check(i).find { it.id == 'NET-16' && it.host == 'hvm-node02' }.status == Status.WARN
        check(i).find { it.id == 'NET-16' && it.host == 'hvm-node01' }.status == Status.PASS
    }

    def 'an error in Morpheus status on a healthy host is noted, once, and a fence message stays MOR-01'() {
        given:
        HealthChecker.Input i = Fixture.healthy()
        i.hosts[1].statusMessage = 'Error loading hypervisor storage pools: Timed out'
        i.hosts[2].statusMessage = 'Host has been fenced (nodedown)'

        when:
        List<HealthChecker.Result> rs = check(i)

        then:
        rs.find { it.id == 'MOR-05' }.host == 'hvm-node02'
        rs.find { it.id == 'MOR-01' }.host == 'hvm-node03'
        rs.count { it.id == 'MOR-05' } == 1
    }

    def 'a datastore missing on a host in maintenance says to reboot before it leaves'() {
        given:
        HealthChecker.Input i = cluster(maintenance: 'hvm-node02')
        i.hosts.find { it.name == 'hvm-node02' }.facts.tables = []

        expect:
        check(i).find { it.id == 'GFS-02' && it.host == 'hvm-node02' }.hint.contains('Reboot')
    }

    // ------------------------------------------------------------------ a freeze with no fence line

    private static HealthChecker.Input frozenNoFenceLine() {
        HealthChecker.Input i = Fixture.healthy()
        i.hosts[1].facts = Fixture.facts('probe-healthy', 'hvm-node02') { String t -> t.replace(' 5    vm-one   running', '') }
        i.hosts[1].facts.fenceWaits = []
        i.hosts[1].facts.lockspaces = [[name: 'ds_01', flags: '0x00000004 kern_stop', members: '1 2 6', status: 'wait fencing', newMembers: '1 2']]
        i
    }

    def 'a lockspace waiting with no fence line still names the node and offers the emergency action'() {
        when:
        List<HealthChecker.Result> rs = check(frozenNoFenceLine())

        then:
        with(rs.find { it.id == 'DLM-01' && it.host == 'hvm-node02' }) {
            it.observed.contains('node 6')
            it.fix == 'fence-ack:6'
            it.tier == HealthChecker.Tier.EMERGENCY
        }
        !rs.any { it.id == 'DLM-03' && it.host == 'hvm-node02' && it.status == Status.FAIL }
        Fixes.fencePlan(frozenNoFenceLine(), 6).runOn == 'hvm-node02'
        Fixes.fencePlan(frozenNoFenceLine(), 6).waitingSinceSec == null
    }

    def 'a host that only just joined is not removed while its mount may be starting'() {
        given:
        HealthChecker.Input i = cluster(maintenance: 'hvm-node02')
        i.hosts.find { it.name == 'hvm-node02' }.facts.corosyncUpSec = 16

        expect:
        Preflight.removeReadiness(i, 'hvm-node02').blockers.any { it.contains('joined the cluster') }
    }

    def 'a GFS2 error before the last boot warns, and one since it fails'() {
        given:
        Closure<HealthChecker.Result> at = { String stamp ->
            HealthChecker.Input i = Fixture.healthy()
            i.hosts[1].facts = Fixture.facts('probe-healthy', 'hvm-node02') { String t -> t.replace(' 5    vm-one   running', '') + "@@gfs2\nGFS2_ERRORS_7D=1\nGFS2_ERROR_LAST=${stamp} host kernel: GFS2: fsid=c:d.1: fatal: filesystem consistency error\n" }
            i.hosts[1].facts.boot = 1793500000L
            check(i).find { it.id == 'GFS-10' && it.host == 'hvm-node02' }
        }

        expect:
        at('2026-10-31T20:16:29+00:00').status == Status.WARN
        at('2027-06-01T00:00:00+00:00').status == Status.FAIL
        at('not-a-date').status == Status.FAIL
    }

    // ------------------------------------------------------------------ fence lines with nothing stopped

    private static HealthChecker.Input retrying() {
        HealthChecker.Input i = Fixture.healthy()
        i.hosts[1].facts = Fixture.facts('probe-healthy', 'hvm-node02') { String t -> t.replace(' 5    vm-one   running', '') + '@@dlm_status\nfence 4 procdown pid 1234 actor 2 fail 1793000000 fence 0 now 1793000300\n' }
        i
    }

    def 'a fence line with every lockspace running is not a freeze, and offers no acknowledgement'() {
        given:
        HealthChecker.Input i = retrying()

        when:
        List<HealthChecker.Result> rs = check(i)

        then:
        !i.hosts[1].facts.frozen
        i.hosts[1].facts.fenceWaits*.node == [4]
        !rs.any { it.id == 'DLM-01' }
        rs.find { it.id == 'DLM-06' && it.host == 'hvm-node02' }.status == Status.WARN
        rs.find { it.id == 'DLM-06' && it.host == 'hvm-node02' }.fix == null
        org.hvmhealth.fixes.Fixes.fencePlan(i, 4).blockers.any { it.contains('No host is waiting') }
    }

    def 'a lockspace stopped with no fence line still fails, as nothing else would raise it'() {
        given:
        HealthChecker.Input i = Fixture.healthy()
        i.hosts[1].facts = Fixture.facts('probe-healthy', 'hvm-node02') { String t -> t.replace(' 5    vm-one   running', '') }
        i.hosts[1].facts.lockspaces = [[name: 'ds_01', flags: '0x00000004 kern_stop', members: '1 2 3']]
        i.hosts[1].facts.fenceWaits = []

        when:
        List<HealthChecker.Result> rs = check(i)

        then:
        rs.find { it.id == 'DLM-03' && it.host == 'hvm-node02' }.status == Status.FAIL
        !rs.any { it.id == 'DLM-01' }
    }
    // ------------------------------------------------------------------ VMs pinned by their CPU settings

    private static HealthChecker.Input pinned(String flag) {
        HealthChecker.Input i = Fixture.healthy()
        i.hosts[1].facts = Fixture.facts('probe-healthy', 'hvm-node02') { String t -> t.replace(' 5    vm-one   running', '') + "@@vm_cpu\nVM_CPU=vm-a|host|${flag}\n" }
        i.morpheusVms = [[name: 'vm-a', host: 'hvm-node02', power: 'on', memory: 1L << 30]]
        i.hosts[1].maintenance = false
        i
    }

    def 'a VM pinned by its CPU settings is found, and the pre-flight says it cannot move'() {
        when:
        HealthChecker.Input i = pinned('1')

        then:
        check(i).find { it.id == 'MIG-07' && it.host == 'hvm-node02' }.status == Status.WARN
        check(i).find { it.id == 'MIG-07' && it.host == 'hvm-node02' }.observed.contains('vm-a')
        Preflight.hostDown(i, 'hvm-node02').moves[0].targets == []
        Preflight.hostDown(i, 'hvm-node02').blockers.any { it.contains('vm-a') }
    }

    def 'a VM free to move passes, and the older two-field line still reads'() {
        expect:
        check(pinned('0')).find { it.id == 'MIG-07' && it.host == 'hvm-node02' }.status == Status.PASS
        Preflight.hostDown(pinned('0'), 'hvm-node02').moves[0].targets
        HostFacts.parse('@@vm_cpu\nVM_CPU=vm-a|host\n@@end\nCOMPLETE=1\n').vmCpus == ['vm-a': 'host']
    }

    // ------------------------------------------------------------------ heartbeat after a re-add

    def 'a heartbeat file under the host name counts when the system hostname has a suffix'() {
        given:
        HealthChecker.Input i = Fixture.healthy()
        HostFacts f = i.hosts[2].facts
        f.hostname = 'hvm-node03-3'

        when:
        HealthChecker.Result r = check(i).find { it.id == 'HB-01' && it.host == 'hvm-node03' }

        then:
        r.title == 'Host heartbeat current'
    }

    // ------------------------------------------------------------------ iSCSI on its own ports

    private static HealthChecker.Result net15(String ports) {
        HealthChecker.Input i = Fixture.healthy()
        i.hosts[1].facts = Fixture.facts('probe-healthy', 'hvm-node02') { String t -> t.replace(' 5    vm-one   running', '').replace('RING_DEV=vs0', 'RING_DEV=vs0\n' + ports) }
        check(i).find { it.id == 'NET-15' && it.host == 'hvm-node02' }
    }

    def 'different interface names on one bond are found by their physical ports'() {
        expect:
        net15('DEV_PORTS=vs0|eno1,eno3\nDEV_PORTS=iscsia|eno1,eno3').status == Status.WARN
        net15('DEV_PORTS=vs0|eno1,eno3\nDEV_PORTS=iscsia|eno1,eno3').observed.contains('eno1, eno3')
        net15('DEV_PORTS=vs0|eno1,eno3\nDEV_PORTS=iscsia|eno2,eno4').status == Status.PASS
    }

    def 'ports it cannot read are not called separate'() {
        expect:
        net15('DEV_PORTS=vs0|eno1,eno3\nDEV_PORTS=iscsia|').status == Status.NOT_CHECKED
        net15('').status == Status.NOT_CHECKED
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

    def 'storage latency is judged per LUN, and a quiet LUN, or one slow I/O, says nothing'() {
        expect:
        check(withStat("STAT=3624a|${r}|${rt}|${w}|${wt}|0")).find { it.id == 'STO-06' }?.status == want

        where:
        r   | rt   | w   | wt    | want
        100 | 200  | 100 | 200   | Status.PASS
        20  | 600  | 0   | 0     | Status.WARN
        20  | 4000 | 0   | 0     | Status.FAIL
        0   | 0    | 0   | 0     | null
        1   | 500  | 0   | 0     | null
        19  | 4000 | 0   | 0     | null
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

    /** What the probe prints for a controller that answered, as on a 2.0 host with an iDRAC, plus {@code extra}. */
    private static List<HealthChecker.Result> bmcCheck(String extra, String selftest = 'passed', String used = '3', String overflow = 'false') {
        String bmc = "WATCHDOG=/dev/watchdog\nIPMITOOL=/usr/bin/ipmitool\nBMC_SELFTEST=${selftest}\nCHASSIS=Power Overload|false\nCHASSIS=Main Power Fault|false\n" +
                "CHASSIS=Power Control Fault|false\nCHASSIS=Drive Fault|false\nCHASSIS=Cooling/Fan Fault|false\nSEL_USED=${used}\nSEL_OVERFLOW=${overflow}\nSDR_READ=1\n${extra}"
        check(Fixture.input([Fixture.host('hvm-node02', Fixture.facts('probe-healthy', 'hvm-node02') {
            it.replace('WATCHDOG=/dev/watchdog', bmc).replace('@@end', hw('SDR=Temperature|Inlet Temp|cr|47 degrees C')).replace(' 5    vm-one   running', '')
        })], Fixture.LAYOUT_20))
    }

    def 'the controller: a failed self-test fails, a log near full or overflowed warns, a healthy one passes'() {
        expect:
        bmcCheck('').find { it.id == 'HW-08' }.with { it.status == Status.PASS && it.observed == 'self-test passed, event log 3% used' }
        bmcCheck('', 'device error').find { it.id == 'HW-08' }.status == Status.FAIL
        bmcCheck('', 'passed', '90').find { it.id == 'HW-08' }.status == Status.WARN
        bmcCheck('', 'passed', '12', 'true').find { it.id == 'HW-08' }.observed == 'self-test passed, event log 12% used, overflowed'
        // a controller without the command has no self-test, which is not a failed one
        bmcCheck('', 'not implemented').find { it.id == 'HW-08' }.with { it.status == Status.PASS && it.observed.startsWith('self-test not implemented') }
    }

    def 'a discrete sensor whose reading names a failure is caught though its status column says ok'() {
        expect:
        bmcCheck('SDR_BAD=Status|ok|Presence detected, Failure detected').find { it.id == 'HW-09' }.with { it.status == Status.FAIL && it.observed.contains('Failure detected') }
        bmcCheck('SDR_BAD=Drive 0|ok|Drive Present, Predictive Failure').find { it.id == 'HW-09' }.status == Status.FAIL
        bmcCheck('SDR_BAD=CPU1 Status|ok|IERR').find { it.id == 'HW-09' }.status == Status.FAIL
    }

    def 'no fault seen in a sensor list cut short is not checked, never a pass'() {
        expect:
        bmcCheck('').find { it.id == 'HW-09' }.status == Status.PASS
        check(Fixture.input([Fixture.host('hvm-node02', Fixture.facts('probe-healthy', 'hvm-node02') {
            it.replace('WATCHDOG=/dev/watchdog', 'IPMITOOL=/usr/bin/ipmitool\nBMC_SELFTEST=passed\nCHASSIS=Drive Fault|false')
        })], Fixture.LAYOUT_20)).find { it.id == 'HW-09' }.with { it.status == Status.NOT_CHECKED && it.observed == 'cannot tell: the sensor list was not read to its end' }
    }

    def 'chassis and sensor faults: a set flag or a critical sensor fails, a non-critical one warns, and HW-03 keeps its own sensors'() {
        expect:
        bmcCheck('').find { it.id == 'HW-09' }.with { it.status == Status.PASS && it.observed == 'none' }
        // the temperature HW-03 already fails on is not counted twice
        bmcCheck('SDR_BAD=Inlet Temp|cr|47 degrees C').find { it.id == 'HW-09' }.status == Status.PASS
        bmcCheck('SDR_BAD=Fan1A|lcr|0 RPM').find { it.id == 'HW-09' }.with { it.status == Status.FAIL && it.observed == 'Fan1A (lcr): 0 RPM' }
        bmcCheck('SDR_BAD=Voltage 1|nc|Upper Non-critical').find { it.id == 'HW-09' }.status == Status.WARN
        check(Fixture.input([Fixture.host('hvm-node02', Fixture.facts('probe-healthy', 'hvm-node02') {
            it.replace('WATCHDOG=/dev/watchdog', 'IPMITOOL=/usr/bin/ipmitool\nBMC_SELFTEST=passed\nCHASSIS=Cooling/Fan Fault|true')
        })], Fixture.LAYOUT_20)).find { it.id == 'HW-09' }.with { it.status == Status.FAIL && it.observed == 'Cooling/Fan Fault: true' }
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

    def 'uplinks at different speeds warn within a bond, and not across bonds'() {
        expect:
        hwCheck('NIC=eno1|9.5|10000|bond0\nNIC=eno2|9.5|1000|bond0').find { it.id == 'HW-05' }.status == Status.WARN
        hwCheck('NIC=eno1|9.5|25000|bond0\nNIC=eno2|9.5|25000|bond0\nNIC=eno3|9.5|1000|bond1\nNIC=eno4|9.5|1000|bond1').find { it.id == 'HW-05' } == null
        hwCheck('NIC=eno1|9.5|10000\nNIC=eno2|9.5|1000').find { it.id == 'HW-05' }.status == Status.WARN
    }

    // ------------------------------------------------------------------ paths that have gone

    private static HostFacts paths(String lines) {
        HostFacts.parse("@@multipath\n3624a0000000000000000 dm-1 VENDOR,Model\nsize=2.0T features='0' hwhandler='1 alua' wp=rw\n`-+- policy='service-time 0' prio=50 status=active\n${lines}\n@@end\nCOMPLETE=1\n")
    }

    def 'a vanished path counts: two good and two gone is two of four'() {
        given:
        HostFacts f = paths('  |- 18:0:0:1 sdd 8:48 active ready running\n  |- 19:0:0:1 sde 8:64 active ready running\n  |- #:#:#:# sdf #:# failed faulty running\n  `- #:#:#:# - #:# failed faulty running')

        when:
        HealthChecker.Result r = check(Fixture.input([Fixture.host('hvm-node02', f)], Fixture.LAYOUT_20)).find { it.id == 'STO-01' }

        then:
        r.status == Status.FAIL
        r.observed == '2 of 4 healthy'
        r.fix == 'multipath-reconfigure'
    }

    def 'a map left with no paths at all fails, rather than warning about too few'() {
        when:
        HealthChecker.Result r = check(Fixture.input([Fixture.host('hvm-node02', paths(''))], Fixture.LAYOUT_20)).find { it.id == 'STO-01' }

        then:
        r.status == Status.FAIL
        r.tier == HealthChecker.Tier.MAINTENANCE
    }

    def 'a kernel log that could not be read is an empty count, which holds rather than clears'() {
        when:
        HealthChecker.Input i = Fixture.input([Fixture.host('hvm-node02', Fixture.facts('probe-healthy', 'hvm-node02') { it + '@@gfs2\nGFS2_ERRORS_7D=\nGFS2_ERROR_LAST=\n' })], Fixture.LAYOUT_20)

        then:
        check(i).find { it.id == 'GFS-10' }.status == Status.NOT_CHECKED
        check(i).find { it.id == 'GFS-10' }.title == 'No GFS2 consistency errors or withdrawals'
    }

    // ------------------------------------------------------------------ heartbeats and the power-on setting

    /** Every host reads the same files, each aged as told. */
    private static HealthChecker.Input heartbeats(Map<String, List<Long>> ages, Map config = [:]) {
        HealthChecker.Input i = Fixture.healthy()
        i.clusterConfig = config
        i.hosts.each { HealthChecker.Host reader ->
            reader.facts.heartbeats = ages.collectMany { String host, List<Long> seconds -> seconds.collect { [host: host, ms: (reader.facts.now - it) * 1000L] } }
        }
        i
    }

    def 'with every file old the setting is off: HA-03 says so once, and no host fails for it'() {
        when:
        List<HealthChecker.Result> rs = check(heartbeats(['hvm-node01': [600L], 'hvm-node02': [600L], 'hvm-node03': [600L]]))

        then:
        rs.find { it.id == 'HA-03' }.status == Status.WARN
        rs.findAll { it.id == 'HB-01' }*.status == [Status.NA, Status.NA, Status.NA]
        rs.findAll { it.id == 'HB-01' }*.observed.every { it.contains('HA-03') }
    }

    def 'one stale host among fresh ones is a real finding, on that host alone, and the setting reads on'() {
        when:
        List<HealthChecker.Result> rs = check(heartbeats(['hvm-node01': [10L], 'hvm-node02': [600L], 'hvm-node03': [10L]]))

        then:
        rs.find { it.id == 'HA-03' }.status == Status.PASS
        rs.find { it.id == 'HB-01' && it.host == 'hvm-node02' }.status == Status.FAIL
        rs.find { it.id == 'HB-01' && it.host == 'hvm-node01' }.status == Status.PASS
    }

    def 'with the setting stated on, files all gone old fail: nothing else explains it'() {
        expect:
        check(heartbeats(['hvm-node01': [600L], 'hvm-node02': [600L], 'hvm-node03': [600L]], [autoRecoverPowerState: true])).findAll { it.id == 'HB-01' }*.status == [Status.FAIL, Status.FAIL, Status.FAIL]
    }

    def 'a file left on an old heartbeat datastore does not hide or fake a fresh one: the newest counts'() {
        expect:
        check(heartbeats(['hvm-node01': [10L], 'hvm-node02': [5000L, 10L], 'hvm-node03': [10L]])).find { it.id == 'HB-01' && it.host == 'hvm-node02' }.status == Status.PASS
    }

    def 'a host just added back is not judged on a file from before it joined, or on having none yet'() {
        given:
        HealthChecker.Input i = heartbeats(['hvm-node01': [10L], 'hvm-node02': age == null ? [] : [age], 'hvm-node03': [10L]])
        i.hosts[1].facts.corosyncUpSec = up

        expect:
        check(i).find { it.id == 'HB-01' && it.host == 'hvm-node02' }.status == want

        where:
        up   | age  | want
        100L | 600L | Status.NA          // left by its earlier incarnation
        100L | null | Status.NA          // not written yet
        280L | 200L | Status.FAIL        // written since it joined, then stopped
        400L | 600L | Status.FAIL        // long enough ago that it should be writing
        400L | null | Status.WARN
        null | 600L | Status.FAIL        // an older probe, or a host that did not say
    }

    def 'corosync uptime is read from the probe'() {
        expect:
        HostFacts.parse('@@services\ncorosync|active|enabled\nCOROSYNC_UP=42\n@@end\nCOMPLETE=1\n').corosyncUpSec == 42L
        HostFacts.parse('@@services\ncorosync|inactive|enabled\nCOROSYNC_UP=\n@@end\nCOMPLETE=1\n').corosyncUpSec == null
    }

    def 'every ring address reaches awk on one line, or the ring device gets no ports and NET-15 cannot tell'() {
        expect:
        probeText().contains('awk -v want=" $(echo $RING_ADDRS) "')
        !probeText().contains('want=" $RING_ADDRS "')
    }

    // ------------------------------------------------------------------ a probe that ran out of time

    private static HealthChecker.Input cutFrozen() {
        HealthChecker.Input i = Fixture.frozen()
        i.hosts[0].facts = Fixture.facts('probe-frozen', 'hvm-node02') { it.replace('COMPLETE=1', 'CUT=1\nCOMPLETE=1') }
        i
    }

    def 'a host cut short names the freeze from what it read first, and counts as not answering for everything else'() {
        given:
        HealthChecker.Input i = cutFrozen()
        HostFacts f = i.hosts[0].facts

        when:
        List<HealthChecker.Result> rs = check(i)

        then:
        f.partial
        !f.complete
        rs.find { it.id == 'PRB-01' && it.host == 'hvm-node02' }.observed.contains('ran out of time')
        rs.find { it.id == 'DLM-01' && it.host == 'hvm-node02' }.fix == 'fence-ack:1'
        !rs.any { it.id == 'DLM-02' && it.host == 'hvm-node02' }
        !rs.any { it.id == 'CLU-01' && it.host == 'hvm-node02' }
    }

    def 'no change runs on a host cut short, as what it skipped could be what the guard needs'() {
        given:
        HealthChecker.Input i = cluster(maintenance: 'hvm-node02', edit: { String t, String h -> h == 'hvm-node02' ? t.replace('COMPLETE=1', 'CUT=1\nCOMPLETE=1') : t })

        expect:
        Fixes.blockers(HealthChecker.Tier.SAFE, i, 'hvm-node02').any { it.contains('did not answer') }
        Fixes.blockers(HealthChecker.Tier.MAINTENANCE, i, 'hvm-node01').any { it.contains('did not answer') }
    }

    def 'the emergency plan uses a waiting host cut short, and never proves a node down from one'() {
        given:
        HealthChecker.Input i = cutFrozen()
        i.hosts << Fixture.host('hvm-node01', Fixture.facts('probe-healthy', 'hvm-node01') { String t ->
            t.replace('BOOT=1784120527', 'BOOT=1793380000').replace('dlm|active|enabled', 'dlm|inactive|disabled').replaceAll(/MOUNT=.*\n/, '').replaceAll(/TABLE=.*\n/, '').replace('COMPLETE=1', 'CUT=1\nCOMPLETE=1')
        })

        when:
        Fixes.FencePlan p = Fixes.fencePlan(i, 1, 1793385845L)

        then:
        p.runOn == 'hvm-node02'
        !p.proven
        p.blockers == []
        p.evidence.contains('ran out of time')
    }

    def 'a target cut short with GFS2 still mounted is alive, so it is refused'() {
        given:
        HealthChecker.Input i = cutFrozen()
        i.hosts << Fixture.host('hvm-node01', Fixture.facts('probe-healthy', 'hvm-node01') { it.replace('COMPLETE=1', 'CUT=1\nCOMPLETE=1') })

        expect:
        Fixes.fencePlan(i, 1, 1793385845L).blockers.any { it.contains('could corrupt the filesystem') }
    }

    // ------------------------------------------------------------------ the probe's own limits, as written. ScriptsSpec runs them where there is bash

    private static String probeText() {
        org.hvmhealth.morpheus.HealthContext.getResourceAsStream('/scripts/health-probe.sh').getText('UTF-8')
    }

    def 'what names a freeze is read first, so it prints however slow the rest is'() {
        given:
        String p = probeText()

        expect:
        p.indexOf('sec dlm_ls') < p.indexOf('sec services')
        p.indexOf('sec dlm_status') < p.indexOf('sec services')
        p.indexOf('sec quorum') < p.indexOf('sec services')
        p.indexOf('sec corosync_conf') < p.indexOf('sec services')
        p.indexOf('journalctl') > p.indexOf('sec services')
    }

    def 'a read past its time returns at once, and the end says the run was cut'() {
        given:
        String p = probeText()

        expect:
        p.contains('[ "$left" -gt 0 ] || return 124')
        p.indexOf("echo 'CUT=1'") < p.indexOf('echo "COMPLETE=1"')
        !(p =~ /(?m)^[^#]*\bsystemctl is-/).findAll().any { !it.contains('t 5 systemctl') }
        p.readLines().findAll { it =~ /(^|[^-\w])timeout / && !it.startsWith('#') && !it.contains('ping timeout of') }*.trim() == ['timeout -k 2 "$s" "$@" 2>/dev/null']
    }

    def 'what can sleep unkillably runs in a job the probe stops waiting for: a process\'s args and the device'() {
        given:
        List<String> lines = probeText().readLines().findAll { !it.startsWith('#') }

        expect:
        lines.findAll { it.contains('args=') }.every { it =~ /^\s*(d_vms|vm_cpus)\(\) \{/ }
        lines.contains('bounded 5 d_vms')
        lines.contains('bounded 5 vm_cpus')
        lines.findAll { it.contains('gfs2_edit') }.every { it =~ /^\s*journal_count\(\) \{/ }
        lines.any { it.contains('$(bounded 15 journal_count "$dev")') }
    }

    def 'GFS2 reads need a lockspace for each filesystem, not just none stopped'() {
        expect:
        probeText().contains('[ "$DLM_NAMES" -ge "$(echo "$GFS2_DEVS" | wc -l)" ]')
    }

    def 'an unread kernel log prints no count, and GFS-10 keeps reading across boots'() {
        given:
        String p = probeText()

        expect:
        p.contains('journalctl -t kernel --since \'7 days ago\'')
        p.contains('exit "${PIPESTATUS[0]}")" && GFS_LOG_READ=1')
        p.contains('echo "GFS2_ERRORS_7D=')
    }

    // ------------------------------------------------------------------ scripts that change something, as written

    def 'recovery is only claimed from a dlm_tool that answered'() {
        expect:
        Fixes.fenceAckScript(1).contains('if o="$(dlm_tool ls)" && ! grep -qE \'kern_stop|wait fenc|new status\' <<<"$o"; then')
    }

    def 'an unmount refused starts its pool again, and a hung one does not touch it'() {
        given:
        List<String> s = Preflight.unmountScript().readLines()

        expect:
        s.find { it.contains('exit 4') }.contains('virsh pool-start "$p"')
        !s.find { it.contains('exit 6') }.contains('pool-start')
    }

    // ------------------------------------------------------------------ smaller

    def 'turning NTP on needs maintenance mode, as it can step a clock'() {
        expect:
        Fixes.fix('ntp-enable', new HostFacts()).tier == HealthChecker.Tier.MAINTENANCE
    }

    def 'a fence wait under the agent\'s usual time does not say it will not clear'() {
        given:
        HealthChecker.Input i = Fixture.frozen()
        i.hosts.each { it.facts.fenceWaits.each { Map w -> w.since = (w.now as Long) - 60L } }

        when:
        List<HealthChecker.Result> rs = check(i)

        then:
        rs.any { it.id == 'DLM-01' }
        !rs.any { it.id == 'DLM-02' }
    }

    def 'VMs that each fit alone but not together are told so'() {
        given:
        long gib = 1L << 30
        HealthChecker.Input i = cluster(vms: (1..3).collect { [name: "vm-${it}".toString(), host: 'hvm-node02', power: 'on', memory: 60 * gib] })
        i.hosts.each { it.facts.memAvailKb = 100L * 1024 * 1024 }

        when:
        Map p = Preflight.hostDown(i, 'hvm-node02')

        then:
        p.verdict == 'NO'
        p.moves*.targets.count { it } == 2
        p.moves.count { !it.targets } == 1
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
