package org.hvmhealth

import org.hvmhealth.checks.HealthChecker
import org.hvmhealth.checks.HealthChecker.Tier
import org.hvmhealth.fixes.Fixes
import org.hvmhealth.model.HostFacts
import spock.lang.Specification

class FixesSpec extends Specification {
    /** Three healthy hosts; node 2 in maintenance mode and empty unless told otherwise. */
    private static def cluster(Map opts = [:]) {
        Fixture.input((1..3).collect { int n ->
            String name = "hvm-node0${n}".toString()
            HostFacts f = Fixture.facts('probe-healthy', name) { String t -> t.replace(' 5    vm-one   running', n == 2 && !opts.vmOnTarget ? '' : (n == 1 ? ' 5    vm-one   running' : '')) }
            Fixture.host(name, f, [maintenance: n == 2 && opts.maintenance != false || n == 3 && opts.twoInMaintenance])
        }, Fixture.LAYOUT_20)
    }

    def 'a host in maintenance mode, empty, in a healthy cluster may be changed'() {
        expect:
        Fixes.blockers(Tier.MAINTENANCE, cluster(), 'hvm-node02') == []
    }

    def 'a host not in maintenance mode is refused'() {
        expect:
        Fixes.blockers(Tier.MAINTENANCE, cluster(maintenance: false), 'hvm-node02').any { it.contains('not in maintenance mode') }
    }

    def 'a host still running VMs is refused, whatever Morpheus says about maintenance'() {
        given:
        def c = Fixture.input((1..3).collect { int n ->
            String name = "hvm-node0${n}".toString()
            HostFacts f = Fixture.facts('probe-healthy', name) { String t -> n == 2 ? t : t.replace(' 5    vm-one   running', '') }
            Fixture.host(name, f, [maintenance: n == 2])
        }, Fixture.LAYOUT_20)

        expect:
        Fixes.blockers(Tier.MAINTENANCE, c, 'hvm-node02').any { it.contains('still runs vm-one') }
    }

    def 'two hosts in maintenance at once are refused'() {
        expect:
        Fixes.blockers(Tier.MAINTENANCE, cluster(twoInMaintenance: true), 'hvm-node02').any { it.contains('one at a time') }
    }

    def 'a frozen cluster refuses maintenance fixes'() {
        given:
        def f = Fixture.frozen()
        f.hosts[0].maintenance = true

        expect:
        Fixes.blockers(Tier.MAINTENANCE, f, 'hvm-node02').any { it.contains('clear the freeze') }
    }

    def 'a change that would cost quorum is refused'() {
        given: 'two of three votes left, so taking one more leaves one, below quorum'
        def hosts = (2..3).collect { int n ->
            String name = "hvm-node0${n}".toString()
            HostFacts f = Fixture.facts('probe-healthy', name) { String t -> t.replace('Total votes:      3', 'Total votes:      2').replace(' 5    vm-one   running', '') }
            Fixture.host(name, f, [maintenance: n == 2])
        }

        expect:
        Fixes.blockers(Tier.MAINTENANCE, Fixture.input(hosts, Fixture.LAYOUT_20), 'hvm-node02').any { it.contains('lose quorum') }
    }

    def 'another host not answering is refused'() {
        given:
        def c = cluster()
        c.hosts[0].facts = new HostFacts()

        expect:
        Fixes.blockers(Tier.MAINTENANCE, c, 'hvm-node02').any { it.contains('did not answer') }
    }

    def 'safe fixes need no maintenance mode, but do need the host to have answered'() {
        given:
        def c = cluster(maintenance: false)

        expect:
        Fixes.blockers(Tier.SAFE, c, 'hvm-node02') == []
        Fixes.blockers(Tier.SAFE, c, 'hvm-node09').any { it.contains('not in this cluster') }
    }

    def 'no fix restarts cluster services on a host with GFS2 mounted, or logs out storage'() {
        given:
        HostFacts f = Fixture.facts('probe-healthy', 'hvm-node02') { it.replace('dlm|active|enabled', 'dlm|active|disabled') }

        when:
        List<String> scripts = ['ntp-enable', 'service-enable', 'rejoin', 'multipath-reconfigure'].collect { Fixes.fix(it, f).script }

        then:
        scripts.every { !(it =~ /systemctl\s+(restart|stop)|--logout|multipath -F|umount|enable_fencing/) }
        Fixes.fix('rejoin', f).script.contains('REFUSED: GFS2 is mounted')
    }

    def 'a service running but not enabled is offered the safe fix, and an enabled one nothing'() {
        given:
        HealthChecker.Input i = Fixture.healthy()
        i.hosts[1].facts = Fixture.facts('probe-healthy', 'hvm-node02') { it.replace('dlm|active|enabled', 'dlm|active|disabled').replace(' 5    vm-one   running', '') }

        when:
        List<HealthChecker.Result> rs = new HealthChecker().check(i).findAll { it.id == 'CLU-02' }

        then:
        rs.find { it.host == 'hvm-node02' && it.title.startsWith('dlm') }.fix == 'service-enable'
        rs.find { it.host == 'hvm-node02' && it.title.startsWith('dlm') }.tier == Tier.SAFE
        rs.find { it.host == 'hvm-node01' && it.title.startsWith('dlm') }.tier == Tier.NONE
    }

    def 'NTP turned off is a finding with the fix, even while the clock still reads synchronised'() {
        given:
        HealthChecker.Input i = Fixture.healthy()
        i.hosts[1].facts = Fixture.facts('probe-healthy', 'hvm-node02') { it.replace('NTP=yes', 'NTP=no').replace(' 5    vm-one   running', '') }

        when:
        HealthChecker.Result r = new HealthChecker().check(i).find { it.id == 'TIM-01' && it.host == 'hvm-node02' }

        then:
        r.status == HealthChecker.Status.WARN
        r.fix == 'ntp-enable'
        r.tier == Tier.MAINTENANCE
    }

    def 'service-enable only enables, and only what is already running'() {
        given:
        HostFacts f = Fixture.facts('probe-healthy', 'hvm-node02') { it.replace('dlm|active|enabled', 'dlm|active|disabled') }

        when:
        Fixes.Fix fix = Fixes.fix('service-enable', f)

        then:
        fix.tier == Tier.SAFE
        fix.script.contains('systemctl enable dlm')
        !fix.script.contains('--now')
        !fix.script.contains('corosync')
    }

    def 'the tiers are what the guards rely on'() {
        given:
        HostFacts f = Fixture.facts('probe-healthy', 'hvm-node02') { it.replace('dlm|active|enabled', 'dlm|active|disabled') }

        expect:
        Fixes.fix(key, f).tier == tier

        where:
        key                     | tier
        'ntp-enable'            | Tier.MAINTENANCE
        'service-enable'        | Tier.SAFE
        'rejoin'                | Tier.MAINTENANCE
        'multipath-reconfigure' | Tier.MAINTENANCE
    }

    def 'pointing a host at the cluster time source needs maintenance mode, a source, and a safe name'() {
        given:
        HostFacts f = Fixture.facts('probe-healthy', 'hvm-node02')

        when:
        Fixes.Fix fix = Fixes.fix('ntp-sources', f, ['ntp1.example.invalid', '192.0.2.123'])

        then:
        fix.tier == Tier.MAINTENANCE
        fix.script.contains('server ntp1.example.invalid iburst prefer')
        fix.script.contains('chronyc reload sources')
        !(fix.script =~ /chrony\.conf|systemctl\s+restart/)

        when:
        Fixes.fix('ntp-sources', f, [])

        then:
        thrown(IllegalStateException)

        when:
        Fixes.fix('ntp-sources', f, ['x; rm -rf /'])

        then:
        thrown(IllegalArgumentException)
    }

    def 'on timesyncd, the time source goes in a drop-in, is verified, and comes back out if nothing answers'() {
        given:
        HostFacts f = Fixture.facts('probe-timesyncd', 'hvm-node02')

        when:
        Fixes.Fix fix = Fixes.fix('ntp-sources', f, ['ntp1.example.invalid', '192.0.2.123'])

        then:
        fix.tier == Tier.MAINTENANCE
        fix.script.contains("printf '[Time]\\nNTP=ntp1.example.invalid 192.0.2.123\\n' > \"\$f.new\"")
        fix.script.contains('f=$d/cluster-health.conf')
        fix.script.contains('d=/etc/systemd/timesyncd.conf.d')
        fix.script.contains('systemctl restart systemd-timesyncd')
        fix.script.contains('timedatectl show-timesync -p ServerName --value')
        fix.script.contains('case " ntp1.example.invalid 192.0.2.123 " in')
        fix.script.contains('putting the previous configuration back')
        fix.script.endsWith('exit 1\n')
        !fix.script.contains('chrony')
        !fix.script.contains('REFUSED: this host does not use chrony')

        and: 'the OS file stays as shipped, and only timesyncd restarts'
        !(fix.script =~ /> *\/etc\/systemd\/timesyncd\.conf\b/)
        (fix.script.findAll(/systemctl\s+restart\s+\S+/) as Set) == ['systemctl restart systemd-timesyncd'] as Set
    }

    def 'on timesyncd, the fix has the same guards: maintenance mode, a source, and a safe name'() {
        given:
        HostFacts f = Fixture.facts('probe-timesyncd', 'hvm-node02')
        def cluster = { boolean inMaintenance ->
            Fixture.input((1..3).collect { int n ->
                String name = "hvm-node0${n}".toString()
                Fixture.host(name, Fixture.facts('probe-timesyncd', name) { it.replace(' 1   vm-one   running', '') }, [maintenance: n == 2 && inMaintenance])
            }, Fixture.LAYOUT_13)
        }

        expect:
        Fixes.blockers(Fixes.fix('ntp-sources', f, ['ntp1.example.invalid']).tier, cluster(false), 'hvm-node02').any { it.contains('not in maintenance mode') }
        Fixes.blockers(Fixes.fix('ntp-sources', f, ['ntp1.example.invalid']).tier, cluster(true), 'hvm-node02') == []

        when:
        Fixes.fix('ntp-sources', f, [])

        then:
        thrown(IllegalStateException)

        when:
        Fixes.fix('ntp-sources', f, ['x; rm -rf /'])

        then:
        thrown(IllegalArgumentException)
    }

    def 'the time source fix refuses a host running neither daemon, rather than guess'() {
        when:
        Fixes.fix('ntp-sources', Fixture.facts('probe-timesyncd', 'x') { it.replace('systemd-timesyncd|active|enabled', 'systemd-timesyncd|inactive|disabled') }, ['ntp1.example.invalid'])

        then:
        IllegalStateException e = thrown()
        e.message.contains('Neither chrony nor systemd-timesyncd')
    }

    def 'a chrony host keeps the chrony way of doing it'() {
        expect:
        Fixes.fix('ntp-sources', Fixture.facts('probe-healthy', 'x'), ['ntp1.example.invalid']).script.contains('chronyc reload sources')
    }

    def 'an unknown fix is refused'() {
        when:
        Fixes.fix('rm-everything', new HostFacts())

        then:
        thrown(IllegalArgumentException)
    }

    // ------------------------------------------------------------------ the emergency action

    def 'a failed node nobody can reach is never proven down: a person has to say so'() {
        when:
        Fixes.FencePlan p = Fixes.fencePlan(Fixture.frozen(), 1, 1793385845L)

        then:
        p.nodeName == 'hvm-node01'
        !p.proven
        p.blockers == []
        p.evidence.contains('nothing proves it is down')
        p.runOn == 'hvm-node02'
        p.waitingSinceSec == 6534L
        p.affectedVms == ['vm-one', 'vm-two']
    }

    def 'a node that answers, rebooted after failing, with no mount and no dlm is proven down'() {
        given:
        def c = Fixture.frozen()
        c.hosts << Fixture.host('hvm-node01', Fixture.facts('probe-healthy', 'hvm-node01') { String t ->
            t.replace('BOOT=1784120527', 'BOOT=1793380000').replace('dlm|active|enabled', 'dlm|inactive|disabled').replaceAll(/MOUNT=.*\n/, '').replaceAll(/TABLE=.*\n/, '')
        })

        when:
        Fixes.FencePlan p = Fixes.fencePlan(c, 1, 1793385845L)

        then:
        p.proven
        p.blockers == []
        p.evidence.contains('rebooted after it failed')
    }

    def 'a node that lost the cluster but still has GFS2 mounted is refused outright'() {
        given: 'it answers the agent but has not rebooted: it could still be writing'
        def c = Fixture.frozen()
        c.hosts << Fixture.host('hvm-node01', Fixture.facts('probe-healthy', 'hvm-node01'))

        when:
        Fixes.FencePlan p = Fixes.fencePlan(c, 1, 1793385845L)

        then:
        !p.proven
        p.blockers.any { it.contains('could corrupt the filesystem') }
    }

    def 'a node no host is waiting on is refused'() {
        expect:
        Fixes.fencePlan(Fixture.frozen(), 3).blockers.any { it.contains('No host is waiting') }
        Fixes.fencePlan(Fixture.healthy(), 1).blockers.any { it.contains('No host is waiting') }
    }

    def 'without quorum the acknowledgement is refused'() {
        given:
        def c = Fixture.frozen()
        c.hosts.each { it.facts.quorate = false }

        expect:
        Fixes.fencePlan(c, 1, 1793385845L).blockers.any { it.contains('restore quorum') }
    }

    def 'the acknowledgement names only that node, and fails if the freeze does not clear'() {
        when:
        String s = Fixes.fenceAckScript(1)

        then:
        s.contains('dlm_tool fence_ack 1\n')
        s.contains('exit 1')
    }
}
