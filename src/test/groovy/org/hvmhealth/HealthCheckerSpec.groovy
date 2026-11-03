package org.hvmhealth

import org.hvmhealth.checks.HealthChecker
import org.hvmhealth.checks.HealthChecker.Status
import org.hvmhealth.checks.HealthChecker.Tier
import org.hvmhealth.model.HostFacts
import spock.lang.Specification

class HealthCheckerSpec extends Specification {
    private static List<HealthChecker.Result> check(HealthChecker.Input i) { new HealthChecker().check(i) }

    def 'a healthy 2.0 cluster with a defined time source passes with nothing failing or warning'() {
        when:
        List<HealthChecker.Result> rs = check(Fixture.healthy('{"ntpServers":["ntp1.example.invalid"]}'))

        then:
        Fixture.failing(rs).isEmpty()
        Fixture.warning(rs).isEmpty()
        HealthChecker.overall(rs) == 'HEALTHY'
    }

    def 'the frozen datastore is reported, with the node it waits on'() {
        when:
        List<HealthChecker.Result> rs = check(Fixture.frozen())
        HealthChecker.Result dlm = rs.find { it.id == 'DLM-01' }

        then:
        HealthChecker.overall(rs) == 'CRITICAL'
        dlm.status == Status.FAIL
        dlm.observed.contains('hvm-node01')
        dlm.observed.contains('108 min')
        dlm.fix == 'fence-ack:1'
        dlm.tier == Tier.EMERGENCY
    }

    def 'agents acknowledging each other instead of the failed node are named as the reason it will not clear'() {
        when:
        List<HealthChecker.Result> rs = check(Fixture.frozen()).findAll { it.id == 'DLM-02' }

        then:
        rs.size() == 2
        rs*.status.every { it == Status.FAIL }
        rs.find { it.host == 'hvm-node02' }.observed.contains('node 3 x222')
        rs.find { it.host == 'hvm-node03' }.observed.contains('node 2 x260')
        rs*.observed.every { it.contains('never node 1') }
    }

    def 'an agent that does acknowledge the failed node is not reported'() {
        given:
        HostFacts f = Fixture.facts('probe-frozen', 'hvm-node02') { it.replace('@@fence_acks\n3|222', '@@fence_acks\n1|5') }

        expect:
        !check(Fixture.input([Fixture.host('hvm-node02', f)], Fixture.LAYOUT_13)).any { it.id == 'DLM-02' }
    }

    def 'a node corosync still counts but Morpheus no longer has is the early warning, before anything freezes'() {
        given: 'two healthy hosts in Morpheus, three in corosync'
        HealthChecker.Input i = Fixture.input((2..3).collect { Fixture.host("hvm-node0${it}".toString(), Fixture.facts('probe-healthy', "hvm-node0${it}".toString())) }, Fixture.LAYOUT_20)

        when:
        HealthChecker.Result r = check(i).find { it.id == 'CLU-05' }

        then:
        r.status == Status.FAIL
        r.observed.contains('in corosync only: hvm-node01')
        r.hint.contains('Remove it from the cluster properly')
    }

    def 'Morpheus names matching corosync names in another case are not a mismatch'() {
        given:
        HealthChecker.Input i = Fixture.input((1..3).collect { Fixture.host("HVM-NODE0${it}".toString(), Fixture.facts('probe-healthy', "hvm-node0${it}".toString())) }, Fixture.LAYOUT_20)

        expect:
        check(i).findAll { it.id == 'CLU-05' }*.status.every { it == Status.PASS }
    }

    def 'corosync node names that differ from the host name still match by ring address, as HVM 2.0 names them'() {
        given: 'the nodelist has hvm-node02-2 for a host called hvm-node02, at the address that host has'
        Closure<String> rename = { String t -> t.replace('name: hvm-node02\n', 'name: hvm-node02-2\n').replace('name: hvm-node03\n', 'name: hvm-node03-3\n') }
        List<HealthChecker.Host> hosts = (1..3).collect { int n ->
            String name = "hvm-node0${n}".toString()
            HostFacts f = Fixture.facts('probe-healthy', name, rename)
            f.ringAddr = "192.0.2.${n}".toString()
            Fixture.host(name, f)
        }

        expect:
        check(Fixture.input(hosts, Fixture.LAYOUT_20)).findAll { it.id == 'CLU-05' }*.status.every { it == Status.PASS }
    }

    def 'a lost member shows as lost quorum margin'() {
        when:
        HealthChecker.Result r = check(Fixture.frozen()).find { it.id == 'CLU-04' }

        then:
        r.status == Status.FAIL
        r.observed.contains('2 of 3 votes, quorum 2')
    }

    def 'heartbeats are not read while GFS2 is frozen, and that is said rather than reported as a failure'() {
        when:
        HealthChecker.Result r = check(Fixture.frozen()).find { it.id == 'HB-01' }

        then:
        r.status == Status.NA
        r.observed.contains('frozen')
    }

    def 'stuck VMs and agent stops queued during the freeze are both raised'() {
        when:
        List<HealthChecker.Result> rs = check(Fixture.frozen())

        then:
        rs.find { it.id == 'VM-02' }.observed.contains('vm-one')
        rs.find { it.id == 'VM-03' }.hint.contains('power off when the freeze clears')
    }

    def 'a VM running on two hosts is critical'() {
        given: 'the sample VM on two hosts at once'
        HealthChecker.Input i = Fixture.input((1..2).collect { Fixture.host("hvm-node0${it}".toString(), Fixture.facts('probe-healthy', "hvm-node0${it}".toString())) }, Fixture.LAYOUT_20)

        when:
        HealthChecker.Result r = check(i).find { it.id == 'VM-01' }

        then:
        r.status == Status.FAIL
        r.severity == HealthChecker.Severity.CRITICAL
        r.observed == 'vm-one: hvm-node01, hvm-node02'
    }

    def 'a bond member down is a failure even though the host still works'() {
        given:
        HostFacts f = Fixture.facts('probe-healthy', 'hvm-node02') { it.replace('SLAVE=vs0bond|eno1|up', 'SLAVE=vs0bond|eno1|down') }

        when:
        HealthChecker.Result r = check(Fixture.input([Fixture.host('hvm-node02', f)], Fixture.LAYOUT_20)).find { it.id == 'NET-01' }

        then:
        r.status == Status.FAIL
        r.observed == 'down: eno1'
    }

    private static HealthChecker.Input oneHost(Closure<String> edit) {
        Fixture.input([Fixture.host('hvm-node02', Fixture.facts('probe-healthy', 'hvm-node02', edit))], Fixture.LAYOUT_20)
    }

    def 'uplinks on two switches pass, and the neighbour is named'() {
        when:
        List<HealthChecker.Result> rs = check(oneHost { it })

        then:
        rs.find { it.id == 'NET-06' }.status == Status.PASS
        rs.find { it.id == 'NET-05' }.observed == 'eno1 -> switch-a Te1/0/14, eno2 -> switch-b Te1/0/14'
    }

    def 'both uplinks on one switch warn'() {
        expect:
        check(oneHost { it.replace('switch-b', 'switch-a') }).find { it.id == 'NET-06' }.status == Status.WARN
    }

    def 'different native VLANs on the uplinks of a host warn'() {
        expect:
        check(oneHost { it.replace('lldp.eno2.vlan.vlan-id=193', 'lldp.eno2.vlan.vlan-id=200') }).find { it.id == 'NET-07' }.status == Status.WARN
    }

    def 'a host with a different native VLAN from the others warns'() {
        given:
        HealthChecker.Input i = Fixture.input([
                Fixture.host('hvm-node01', Fixture.facts('probe-healthy', 'hvm-node01')),
                Fixture.host('hvm-node02', Fixture.facts('probe-healthy', 'hvm-node02') { it.replace('vlan-id=193', 'vlan-id=200') })], Fixture.LAYOUT_20)

        expect:
        check(i).find { it.id == 'NET-08' }.observed == 'hvm-node01=193, hvm-node02=200'
    }

    def 'a silent uplink warns, and no lldpd is reported rather than failed'() {
        expect:
        check(oneHost { it.replaceAll(/lldp\.eno2\..*\n/, '') }).find { it.id == 'NET-05' }.observed == 'nothing heard on eno2'
        check(Fixture.frozen()).find { it.id == 'NET-05' }.status == Status.NA
    }

    def 'N+1: running VMs that would not fit without the largest host warn'() {
        given:
        long gib = 1L << 30
        List<HealthChecker.Host> hosts = (1..3).collect { int n ->
            String name = "hvm-node0${n}".toString()
            new HealthChecker.Host(name, false, 'online', null, null, Fixture.facts('probe-healthy', name) { it.replace(' 5    vm-one   running', '') }, null, 100 * gib)
        }

        expect:
        check(Fixture.input(hosts, Fixture.LAYOUT_20, null, [[name: 'a', host: 'hvm-node01', power: 'on', memory: memory * gib], [name: 'off', host: 'hvm-node01', power: 'off', memory: 999 * gib]]))
                .find { it.id == 'CAP-01' }.status == want

        where:
        memory | want
        200    | Status.PASS
        201    | Status.WARN
    }

    // ------------------------------------------------------------------ time

    def 'a host off its time source by too much fails, with the numbers'() {
        when:
        HealthChecker.Result r = check(oneHost { it.replace(',0.000080557,', ',0.250000000,') }).find { it.id == 'TIM-03' }

        then:
        r.status == Status.FAIL
        r.observed.startsWith('250.0 ms from 192.0.2.123')
    }

    def 'no selected time source fails even with a small offset'() {
        expect:
        check(oneHost { it.replace('CHRONY_SOURCE=^,*,', 'CHRONY_SOURCE=^,?,') }).find { it.id == 'TIM-03' }.status == Status.FAIL
    }

    def 'with a cluster time source set, a host not using it fails and is offered the maintenance fix'() {
        given:
        HealthChecker.Input i = Fixture.input([Fixture.host('hvm-node02', Fixture.facts('probe-healthy', 'hvm-node02'))], Fixture.LAYOUT_20, '{"ntpServers":["ntp9.example.invalid"]}')

        when:
        HealthChecker.Result r = check(i).find { it.id == 'TIM-04' }

        then:
        r.status == Status.FAIL
        r.fix == 'ntp-sources'
        r.tier == Tier.MAINTENANCE
        r.source == 'site'
    }

    def 'a host using the cluster time source passes'() {
        given:
        HealthChecker.Input i = Fixture.input([Fixture.host('hvm-node02', Fixture.facts('probe-healthy', 'hvm-node02'))], Fixture.LAYOUT_20, '{"ntpServers":["ntp1.example.invalid"]}')

        expect:
        check(i).find { it.id == 'TIM-04' }.status == Status.PASS
    }

    def 'hosts on different time sources warn'() {
        given:
        HealthChecker.Input differ = Fixture.input([
                Fixture.host('hvm-node01', Fixture.facts('probe-healthy', 'hvm-node01')),
                Fixture.host('hvm-node02', Fixture.facts('probe-healthy', 'hvm-node02') { it.replace('ntp1.example.invalid', 'ntp2.example.invalid') })], Fixture.LAYOUT_20)

        expect:
        check(differ).find { it.id == 'TIM-05' }.status == Status.WARN
    }

    def 'what a host does for time is always said, defined or not'() {
        expect:
        check(oneHost { it.replace('ntp1.example.invalid', '1.ntp.ubuntu.com') }).find { it.id == 'TIM-06' }.observed == 'none defined; hosts follow the OS default public servers'
        check(oneHost { it }).find { it.id == 'TIM-06' }.observed == 'none defined'
        check(oneHost { it.replace('ntp1.example.invalid', '1.ntp.ubuntu.com') }).find { it.id == 'TIM-04' }.observed.startsWith('OS default: following 1.ntp.ubuntu.com')
    }

    def 'each host is classified by what it really does'() {
        given:
        String wanted = 'ntp1.example.invalid'
        Closure<HostFacts> f = { Closure<String> e -> Fixture.facts('probe-healthy', 'hvm-node02', e) }
        Closure<String> ubuntu = { String t -> t.replace(wanted, '1.ntp.ubuntu.com') }

        expect:
        HealthChecker.timeState(f { it }, [wanted]) == 'cluster source'
        HealthChecker.timeState(f { it }, []) == 'other servers'
        HealthChecker.timeState(f(ubuntu), []) == 'OS default'
        HealthChecker.timeState(f(ubuntu), [wanted]) == 'OS default'
        HealthChecker.timeState(f { it.replace("CONF_SERVER=${wanted}\n".toString(), '') }, []) == 'none configured'
        HealthChecker.timeState(f { it.replace("CHRONY_FOLLOWING=${wanted}".toString(), 'CHRONY_FOLLOWING=other.example.invalid') }, [wanted]) == 'other servers'
    }

    // ------------------------------------------------------------------ netmasks

    private static HealthChecker.Input withAddrs(Map<String, List<String>> addrs) {
        Fixture.input(addrs.collect { String name, List<String> lines ->
            Fixture.host(name, Fixture.facts('probe-healthy', name) { String t ->
                t.replace('@@ring\n', "@@addrs\n${lines.collect { 'ADDR=' + it }.join('\n')}\n@@ring\n".toString()).replace(' 5    vm-one   running', '')
            })
        }, Fixture.LAYOUT_20)
    }

    def 'matching netmasks pass'() {
        when:
        HealthChecker.Result r = check(withAddrs(['hvm-node01': ['mgmt|192.0.2.1/24', 'iscsia|198.51.100.1/25'], 'hvm-node02': ['mgmt|192.0.2.2/24', 'iscsia|198.51.100.2/25']])).find { it.id == 'NET-10' }

        then:
        r.status == Status.PASS
        r.observed == '2 network(s) compared'
    }

    def 'one host on the same network with a different netmask fails, naming both'() {
        when:
        HealthChecker.Result r = check(withAddrs(['hvm-node01': ['mgmt|192.0.2.1/24'], 'hvm-node02': ['mgmt|192.0.2.2/23']])).find { it.id == 'NET-10' }

        then:
        r.status == Status.FAIL
        r.observed.contains('hvm-node01 mgmt /24')
        r.observed.contains('hvm-node02 mgmt /23')
    }

    def 'interfaces named differently are still counterparts when their subnets overlap'() {
        expect:
        check(withAddrs(['hvm-node01': ['eno1.60|198.51.100.1/25'], 'hvm-node02': ['bond0.60|198.51.100.2/24']])).find { it.id == 'NET-10' }.status == Status.FAIL
    }

    def 'the same interface name on different networks with different netmasks is still flagged'() {
        expect:
        check(withAddrs(['hvm-node01': ['iscsia|192.0.2.1/24'], 'hvm-node02': ['iscsia|198.51.100.2/25']])).find { it.id == 'NET-10' }.status == Status.FAIL
    }

    def 'a /32 VIP a host holds inside its own /24 is not a different netmask'() {
        expect:
        check(withAddrs(['hvm-node01': ['mgmt|192.0.2.1/24', 'mgmt|192.0.2.50/32'], 'hvm-node02': ['mgmt|192.0.2.2/24']])).find { it.id == 'NET-10' }.status == Status.PASS
    }

    def 'a real difference still shows beside a VIP'() {
        when:
        HealthChecker.Result r = check(withAddrs(['hvm-node01': ['mgmt|192.0.2.1/24', 'mgmt|192.0.2.50/32'], 'hvm-node02': ['mgmt|192.0.2.2/23']])).find { it.id == 'NET-10' }

        then:
        r.status == Status.FAIL
        r.observed.contains('hvm-node01 mgmt /24')
        r.observed.contains('hvm-node02 mgmt /23')
        !r.observed.contains('/32')
    }

    def 'different networks on different interfaces are not counterparts'() {
        expect:
        check(withAddrs(['hvm-node01': ['mgmt|192.0.2.1/24', 'iscsia|198.51.100.1/25'], 'hvm-node02': ['mgmt|192.0.2.2/24']])).find { it.id == 'NET-10' }.status == Status.PASS
    }

    def 'configured with the cluster source but following another server warns, with no fix to offer'() {
        given:
        HealthChecker.Input i = Fixture.input([Fixture.host('hvm-node02', Fixture.facts('probe-healthy', 'hvm-node02') { it.replace('CHRONY_FOLLOWING=ntp1.example.invalid', 'CHRONY_FOLLOWING=other.example.invalid') })], Fixture.LAYOUT_20, '{"ntpServers":["ntp1.example.invalid"]}')

        when:
        HealthChecker.Result r = check(i).find { it.id == 'TIM-04' }

        then:
        r.status == Status.WARN
        r.fix == null
    }

    private static HealthChecker.Input withOffsets(Map<String, String> offsetSeconds) {
        Fixture.input(offsetSeconds.collect { String name, String off ->
            Fixture.host(name, Fixture.facts('probe-healthy', name) { String t -> t.replace('251,-0.0004,-0.0004', "251,${off},${off}").replace(' 5    vm-one   running', '') })
        }, Fixture.LAYOUT_20, '{"ntpServers":["ntp1.example.invalid"]}')
    }

    def 'drift between hosts is measured against the time server they share'() {
        when:
        HealthChecker.Result r = check(withOffsets(['hvm-node01': '0.001', 'hvm-node02': '0.003'])).find { it.id == 'TIM-02' }

        then:
        r.status == Status.PASS
        r.observed == '2.0 ms apart, measured against 1 shared time server(s)'
    }

    def 'hosts each in sync but apart from each other fail'() {
        when:
        HealthChecker.Result r = check(withOffsets(['hvm-node01': '-0.040', 'hvm-node02': '0.040'])).find { it.id == 'TIM-02' }

        then:
        r.status == Status.FAIL
        r.observed.startsWith('80.0 ms apart')
    }

    def 'hosts sharing no time server fall back to a coarse comparison and say so'() {
        given:
        HealthChecker.Input i = Fixture.input([
                Fixture.host('hvm-node01', Fixture.facts('probe-healthy', 'hvm-node01') { it.replace('NOW=1793385845', 'NOW=1793385900').replace(' 5    vm-one   running', '') }),
                Fixture.host('hvm-node02', Fixture.facts('probe-healthy', 'hvm-node02') { it.replace('CHRONY_SOURCE=^,*,192.0.2.123', 'CHRONY_SOURCE=^,*,198.51.100.9').replace(' 5    vm-one   running', '') })], Fixture.LAYOUT_20)

        when:
        HealthChecker.Result r = check(i).find { it.id == 'TIM-02' }

        then:
        r.status == Status.WARN
        r.observed.contains('coarse')
    }

    def 'the time view lists what each host does'() {
        when:
        Map t = HealthChecker.timeView(withOffsets(['hvm-node01': '0.001', 'hvm-node02': '0.003']))

        then:
        t.defined == ['ntp1.example.invalid']
        t.definedBy == 'site'
        t.driftMs == 2.0
        t.hosts*.state == ['cluster source', 'cluster source']
        t.hosts*.following == ['ntp1.example.invalid', 'ntp1.example.invalid']
    }

    // ------------------------------------------------------------------ migration

    private static HealthChecker.Input twoCpus(String cpuModel) {
        HealthChecker.Input i = Fixture.input([
                Fixture.host('hvm-node01', Fixture.facts('probe-healthy', 'hvm-node01')),
                Fixture.host('hvm-node02', Fixture.facts('probe-healthy', 'hvm-node02') { it.replace('CPU_FLAGS=08dd0072dea29009', 'CPU_FLAGS=60e9ed41fa1e8889').replace('Example CPU 3000', 'Example CPU 5000').replace(' 5    vm-one   running', '') })], Fixture.LAYOUT_20)
        i.clusterConfig = [cpuModel: cpuModel]
        i
    }

    def 'different CPUs fail under host-passthrough and warn with a named CPU model'() {
        expect:
        check(twoCpus(model)).find { it.id == 'MIG-01' }.status == want

        where:
        model              | want
        'host-passthrough' | Status.FAIL
        'Haswell-noTSX'    | Status.WARN
    }

    def 'different CPU flags are named by host'() {
        expect:
        check(twoCpus('host-passthrough')).find { it.id == 'MIG-01' }.observed.contains('Example CPU 5000: hvm-node02')
    }

    private static HealthChecker.Input mixedCpus(String model, String oldUsable = 'Skylake-Server,Denverton', String newUsable = 'Cascadelake-Server,Skylake-Server,Denverton') {
        HealthChecker.Input i = Fixture.input([
                Fixture.host('hvm-node01', Fixture.facts('probe-healthy', 'hvm-node01') { it.replace('CPU_FLAGS=08dd0072dea29009', "CPU_FLAGS=08dd0072dea29009\nLIBVIRT_CPU=Skylake-Server\nUSABLE_CPUS=${oldUsable}".toString()) }),
                Fixture.host('hvm-node02', Fixture.facts('probe-healthy', 'hvm-node02') { it.replace('CPU_FLAGS=08dd0072dea29009', "CPU_FLAGS=60e9ed41fa1e8889\nLIBVIRT_CPU=Cascadelake-Server\nUSABLE_CPUS=${newUsable}".toString()).replace(' 5    vm-one   running', '') })], Fixture.LAYOUT_20)
        i.clusterConfig = [cpuModel: model]
        i
    }

    def 'the finding says exactly which CPU model to set, the EVC equivalent, from the hosts own models'() {
        when:
        HealthChecker.Result r = check(mixedCpus('host-passthrough')).find { it.id == 'MIG-01' }

        then: 'the older host model, usable everywhere, not an unrelated model that merely appears on every list'
        r.status == Status.FAIL
        r.hint.contains('equivalent of EVC')
        r.hint.contains('set it to Skylake-Server, which libvirt reports usable on every host')
        !r.hint.contains('Denverton')
        r.hint.contains('test on one VM first')
    }

    def 'the recommendation is a name Morpheus offers, never a versioned libvirt one, and prefers the mitigated variant'() {
        given:
        Closure<HostFacts> host = { String cpu, String usable -> Fixture.facts('probe-healthy', 'x') { it.replace('CPU_FLAGS=08dd0072dea29009', "CPU_FLAGS=a\nLIBVIRT_CPU=${cpu}\nUSABLE_CPUS=${usable}".toString()) } }

        expect:
        HealthChecker.commonCpu([host('Skylake-Server-v5', 'Skylake-Server-v5,Skylake-Server-noTSX-IBRS,Denverton'),
                                 host('Cascadelake-Server-v5', 'Cascadelake-Server-v5,Skylake-Server-v5,Skylake-Server-noTSX-IBRS,Denverton')]) == 'Skylake-Server-noTSX-IBRS'
        HealthChecker.commonCpu([host('Skylake-Server-v5', 'Skylake-Server-v5,Denverton'),
                                 host('Cascadelake-Server-v5', 'Skylake-Server-v5,Denverton')]) == null
        HealthChecker.commonCpu([host('Skylake-Server-v5', 'Skylake-Server'), new HostFacts()]) == null
    }

    def 'no CPU model is recommended when none of the hosts own models fits them all'() {
        when:
        HealthChecker.Result r = check(mixedCpus('host-passthrough', 'Denverton', 'Cascadelake-Server,Denverton')).find { it.id == 'MIG-01' }

        then:
        r.hint.contains('set it to a model libvirt reports usable on every host')
    }

    def 'a named cluster CPU model is checked against what each host can run'() {
        expect:
        check(mixedCpus('Skylake-Server')).find { it.id == 'MIG-01' }.hint.contains('Libvirt reports it usable on every host')
        check(mixedCpus('Cascadelake-Server')).find { it.id == 'MIG-01' }.hint.contains('does NOT report it usable on every host: Skylake-Server is')
    }

    def 'a site can require a CPU model'() {
        given:
        HealthChecker.Input i = Fixture.input([Fixture.host('hvm-node02', Fixture.facts('probe-healthy', 'hvm-node02'))], Fixture.LAYOUT_20, '{"clusterCpuModel":"Skylake-Server-noTSX-IBRS"}')
        i.clusterConfig = [cpuModel: 'host-passthrough']

        expect:
        check(i).find { it.id == 'MIG-05' }.status == Status.FAIL
    }

    def 'a closed migration port and a path that drops full-size packets both fail'() {
        given:
        HealthChecker.Input i = oneHost { it.replace('PEER=192.0.2.1|0|0.25|1|1|1500', 'PEER=192.0.2.1|0|0.25|0|0|1500') }

        when:
        List<HealthChecker.Result> rs = check(i)

        then:
        rs.find { it.id == 'MIG-02' }.observed == 'TCP 22 closed to 192.0.2.1'
        rs.find { it.id == 'NET-09' }.status == Status.FAIL
        rs.find { it.id == 'NET-09' }.observed.contains('192.0.2.1 at MTU 1500')
    }

    def 'a datastore missing or inactive on one host fails'() {
        given:
        HealthChecker.Input i = Fixture.input([
                Fixture.host('hvm-node01', Fixture.facts('probe-healthy', 'hvm-node01')),
                Fixture.host('hvm-node02', Fixture.facts('probe-healthy', 'hvm-node02') { it.replace(' ds-01                  active   yes', ' ds-01                  inactive   yes').replace(' 5    vm-one   running', '') })], Fixture.LAYOUT_20)

        expect:
        check(i).find { it.id == 'MIG-03' && it.host == 'hvm-node02' }.observed == 'inactive ds-01'
    }

    def 'a VM too big for any other host warns, because its host cannot be emptied'() {
        given:
        long big = 200L << 30
        HealthChecker.Input i = Fixture.input([
                Fixture.host('hvm-node01', Fixture.facts('probe-healthy', 'hvm-node01')),
                Fixture.host('hvm-node02', Fixture.facts('probe-healthy', 'hvm-node02') { it.replace(' 5    vm-one   running', '') })], Fixture.LAYOUT_20, null,
                [[name: 'vm-one', host: 'hvm-node01', power: 'on', memory: big]])

        expect:
        check(i).find { it.id == 'MIG-04' }.observed == 'vm-one'
    }

    // ------------------------------------------------------------------ maintenance and HA

    def 'a host in maintenance mode is expected to have no heartbeat, and is warned as still a member'() {
        given:
        HostFacts peer = Fixture.facts('probe-healthy', 'hvm-node01') { it.replace('HB=hvm-node02|1793385840000\n', '') }
        HostFacts target = Fixture.facts('probe-healthy', 'hvm-node02') { it.replace('HB=hvm-node02|1793385840000\n', '').replace(' 5    vm-one   running', '') }
        HealthChecker.Input i = Fixture.input([Fixture.host('hvm-node01', peer), Fixture.host('hvm-node02', target, [maintenance: true])], Fixture.LAYOUT_20)

        when:
        List<HealthChecker.Result> rs = check(i)

        then:
        rs.find { it.id == 'HB-01' && it.host == 'hvm-node02' }.status == Status.NA
        rs.find { it.id == 'HA-02' }.status == Status.WARN
        rs.find { it.id == 'HA-02' }.hint.contains('stalls every GFS2 datastore')
    }

    def 'a host out of maintenance with no heartbeat warns, as it can be just back, and a stale one fails'() {
        given:
        HealthChecker.Input absent = Fixture.input([Fixture.host('hvm-node02', Fixture.facts('probe-healthy', 'hvm-node02') { it.replace('HB=hvm-node02|1793385840000\n', '') })], Fixture.LAYOUT_20)
        HealthChecker.Input stale = Fixture.input([Fixture.host('hvm-node02', Fixture.facts('probe-healthy', 'hvm-node02') { it.replace('HB=hvm-node02|1793385840000', 'HB=hvm-node02|1793385000000') })], Fixture.LAYOUT_20)

        expect:
        check(absent).find { it.id == 'HB-01' && it.host == 'hvm-node02' }.status == Status.WARN
        check(stale).find { it.id == 'HB-01' && it.host == 'hvm-node02' }.status == Status.FAIL
    }

    def 'the HA flag on the cluster record is a note with its raw value, and raises nothing until it is understood'() {
        given:
        HealthChecker.Input i = Fixture.healthy('{"ntpServers":["ntp1.example.invalid"]}')
        i.clusterConfig = [notHighAvailability: value]

        when:
        List<HealthChecker.Result> rs = check(i)
        HealthChecker.Result r = rs.find { it.id == 'HA-01' }

        then:
        r.status == Status.NA
        r.observed == "cluster config has notHighAvailability=${value}".toString()
        HealthChecker.overall(rs) == 'HEALTHY'
        !org.hvmhealth.morpheus.HealthMonitor.alarmsFor(1L, 'c', rs).any { it.name.startsWith('HA-01') }

        where:
        value << [true, false, 'on']
    }

    def 'with no HA flag on the cluster record, nothing is said about it'() {
        expect:
        !check(oneHost { it }).any { it.id == 'HA-01' }
    }

    // ------------------------------------------------------------------ a 1.3 host on systemd-timesyncd

    def 'the sample from a real 1.3 host parses to what the time checks read'() {
        when:
        HostFacts f = Fixture.facts('probe-timesyncd', 'hvm-node02')

        then:
        f.timeDaemon == 'timesyncd'
        f.timesyncd == [addr: '203.0.113.57', name: 'ntp.ubuntu.com', offsetMs: 5.093G, packets: 6L, stratum: 2, leap: 'normal']
        f.timeFollowing == 'ntp.ubuntu.com'
        f.timeServers == ['ntp.ubuntu.com']
        f.timeSamples == [[addr: '203.0.113.57', offsetMs: 5.093G]]
        !f.chrony
    }

    def 'a chrony host ignores what timesyncd files say'() {
        when:
        HostFacts f = Fixture.facts('probe-healthy', 'hvm-node02') { it.replace('CONF_SERVER=', 'CONF_TIMESYNCD=NTP=other.example.invalid\nCONF_SERVER=') }

        then:
        f.timeDaemon == 'chrony'
        f.timesyncd == null
        f.timeServers == ['ntp1.example.invalid']
    }

    def 'an IPv6 time server keeps its whole address'() {
        expect:
        Fixture.facts('probe-timesyncd', 'x') { it.replace('TS_SERVER=203.0.113.57 (ntp.ubuntu.com)', 'TS_SERVER=2001:db8::40 (ntp.ubuntu.com)') }.timesyncd.addr == '2001:db8::40'
    }

    def 'systemd time spans read in milliseconds'() {
        expect:
        HostFacts.timespanMs(text) == want

        where:
        text           | want
        '+5.093ms'     | 5.093G
        '-120us'       | -0.120G
        '+1.5s'        | 1500G
        '-1min 2.5s'   | -62500G
        '0'            | 0G
        ''             | null
        null           | null
        'soon'         | null
    }

    def 'timesyncd servers come from its own report, then its files, then its fallback'() {
        given:
        Closure<HostFacts> f = { String lines -> Fixture.facts('probe-timesyncd', 'x') { it.replace('TS_FALLBACK=ntp.ubuntu.com', lines) } }

        expect: 'the fallback on a stock host, which configures nothing'
        f('TS_FALLBACK=ntp.ubuntu.com').timeServers == ['ntp.ubuntu.com']

        and: 'what it reports'
        Fixture.facts('probe-timesyncd', 'x') { it.replace('TS_SYSTEM=', 'TS_SYSTEM=ntp2.example.invalid ntp1.example.invalid') }.timeServers == ['ntp1.example.invalid', 'ntp2.example.invalid']

        and: 'its files, if it reported nothing, where an empty NTP= resets what came before and NTP= outranks the fallback'
        f('CONF_TIMESYNCD=NTP=old.example.invalid\nCONF_TIMESYNCD=NTP=\nCONF_TIMESYNCD=NTP=ntp1.example.invalid\nCONF_TIMESYNCD=FallbackNTP=fb.example.invalid').timeServers == ['ntp1.example.invalid']
        f('CONF_TIMESYNCD=FallbackNTP=fb.example.invalid').timeServers == ['fb.example.invalid']

        and: 'at the very least, the server it is reading'
        f('').timeServers == ['ntp.ubuntu.com']
    }

    def 'a synced timesyncd host reads as following its server, not as following nothing'() {
        when:
        List<HealthChecker.Result> rs = check(Fixture.timesyncd())
        HealthChecker.Result tim3 = rs.find { it.id == 'TIM-03' && it.host == 'hvm-node01' }
        HealthChecker.Result tim4 = rs.find { it.id == 'TIM-04' && it.host == 'hvm-node01' }

        then:
        tim3.status == Status.PASS
        tim3.observed == '5.1 ms from ntp.ubuntu.com (203.0.113.57) at its last poll, stratum 2, normal'
        tim4.status == Status.NA
        tim4.observed == 'OS default: following ntp.ubuntu.com via systemd-timesyncd; configured ntp.ubuntu.com'
        rs.find { it.id == 'TIM-06' }.observed == 'none defined; hosts follow the OS default public servers'
        Fixture.failing(rs).isEmpty()
    }

    def 'a timesyncd host is held to the cluster time source like any other'() {
        given:
        Closure<String> onIt = { String t, String h -> t.replace('TS_SYSTEM=', 'TS_SYSTEM=ntp1.example.invalid').replace('(ntp.ubuntu.com)', '(ntp1.example.invalid)') }
        Closure<String> elsewhere = { String t, String h -> t.replace('TS_SYSTEM=', 'TS_SYSTEM=ntp1.example.invalid other.example.invalid').replace('(ntp.ubuntu.com)', '(other.example.invalid)') }
        String site = '{"ntpServers":["ntp1.example.invalid"]}'

        expect: 'using it'
        check(Fixture.timesyncd(site, onIt)).find { it.id == 'TIM-04' }.status == Status.PASS

        and: 'configured but reading another'
        HealthChecker.Result other = check(Fixture.timesyncd(site, elsewhere)).find { it.id == 'TIM-04' }
        other.status == Status.WARN
        other.hint.contains('systemd-timesyncd is following another server')

        and: 'not using it, with the fix offered'
        HealthChecker.Result not = check(Fixture.timesyncd(site)).find { it.id == 'TIM-04' }
        not.status == Status.FAIL
        not.fix == 'ntp-sources'
        not.tier == Tier.MAINTENANCE
    }

    def 'timesyncd with no server answering fails, and with no status says it cannot tell'() {
        when:
        HealthChecker.Result silent = check(Fixture.timesyncd(null) { String t, String h -> t.replace('TS_SERVER=203.0.113.57 (ntp.ubuntu.com)', 'TS_SERVER=n/a (ntp.ubuntu.com)').replace('TS_PACKETS=6', 'TS_PACKETS=0').replaceAll(/TS_OFFSET=.*\n/, '') }).find { it.id == 'TIM-03' }
        HealthChecker.Result unread = check(Fixture.timesyncd(null) { String t, String h -> t.replaceAll(/TS_(SERVER|OFFSET|PACKETS|LEAP|STRATUM)=.*\n/, '') }).find { it.id == 'TIM-03' }

        then:
        silent.status == Status.FAIL
        silent.observed.contains('no time server has answered')
        unread.status == Status.NOT_CHECKED
        unread.observed.startsWith('cannot tell')
    }

    def 'a large timesyncd offset warns rather than fails, as it is one sample already corrected'() {
        when:
        HealthChecker.Result r = check(Fixture.timesyncd(null) { String t, String h -> t.replace('TS_OFFSET=+5.093ms', 'TS_OFFSET=-250ms') }).find { it.id == 'TIM-03' }

        then:
        r.status == Status.WARN
        r.observed.startsWith('250.0 ms from')
    }

    private static HealthChecker.Input timesyncdServers(Map<String, List<String>> addrAndOffset) {
        Fixture.timesyncd(null) { String t, String h ->
            List<String> v = addrAndOffset[h]
            t.replace('203.0.113.57', v[0]).replace('+5.093ms', v[1])
        }
    }

    def 'hosts reading the same server are compared directly'() {
        when:
        HealthChecker.Input i = timesyncdServers(['hvm-node01': ['203.0.113.1', '+1ms'], 'hvm-node02': ['203.0.113.1', '+3ms'], 'hvm-node03': ['203.0.113.1', '+2ms']])
        HealthChecker.Result r = check(i).find { it.id == 'TIM-02' }

        then:
        r.status == Status.PASS
        r.observed == '2.0 ms apart, measured against 1 shared time server(s)'
        r.hint == null
        HealthChecker.timeView(i).driftMs == 2.0
    }

    def 'hosts apart on one server warn rather than fail, as these are single samples'() {
        when:
        HealthChecker.Result r = check(timesyncdServers(['hvm-node01': ['203.0.113.1', '-40ms'], 'hvm-node02': ['203.0.113.1', '+40ms'], 'hvm-node03': ['203.0.113.1', '+1ms']])).find { it.id == 'TIM-02' }

        then:
        r.status == Status.WARN
        r.observed.startsWith('80.0 ms apart')
        r.hint.contains('single samples')
    }

    def 'hosts on different members of a pool are no alarm, and it is said what that leaves unknown'() {
        when:
        HealthChecker.Input i = timesyncdServers(['hvm-node01': ['203.0.113.1', '+40ms'], 'hvm-node02': ['203.0.113.2', '-40ms'], 'hvm-node03': ['203.0.113.3', '+1ms']])
        List<HealthChecker.Result> rs = check(i)
        HealthChecker.Result r = rs.find { it.id == 'TIM-02' }

        then: 'same configured name, so not a difference in configuration either'
        r.status == Status.PASS
        r.observed.contains('coarse')
        r.hint.contains('hvm-node01 is 40.0 ms from 203.0.113.1')
        r.hint.contains('neither drift nor agreement')
        !rs.any { it.id == 'TIM-05' }
        HealthChecker.timeView(i).driftMs == null
        HealthChecker.timeView(i).driftNote.contains('neither drift nor agreement')
        HealthChecker.timeView(i).hosts*.offsetMs == [40.0, 40.0, 1.0]
        HealthChecker.timeView(i).hosts*.daemon == ['timesyncd', 'timesyncd', 'timesyncd']
    }

    def 'one host on another pool member is left out of the comparison, and named'() {
        when:
        HealthChecker.Result r = check(timesyncdServers(['hvm-node01': ['203.0.113.1', '+1ms'], 'hvm-node02': ['203.0.113.1', '+3ms'], 'hvm-node03': ['203.0.113.9', '+90ms']])).find { it.id == 'TIM-02' }

        then:
        r.status == Status.PASS
        r.observed.startsWith('2.0 ms apart')
        r.observed.contains('across all hosts (coarse)')
        r.hint.contains('hvm-node03 is 90.0 ms from 203.0.113.9')
    }

    def 'two groups on different servers are measured within each, never across'() {
        given:
        HealthChecker.Input i = Fixture.input((1..4).collect { int n ->
            String name = "hvm-node0${n}".toString()
            Fixture.host(name, Fixture.facts('probe-timesyncd', name) { String t ->
                t.replace('203.0.113.57', n < 3 ? '203.0.113.1' : '203.0.113.2').replace('+5.093ms', ['+1ms', '+2ms', '+90ms', '+94ms'][n - 1]).replace(' 1   vm-one   running', '')
            })
        }, Fixture.LAYOUT_13)

        when:
        Map drift = HealthChecker.hostDrift(i.hosts)
        HealthChecker.Result r = check(i).find { it.id == 'TIM-02' }

        then: 'the wider group is 4 ms apart, not the 93 ms between groups, which nothing measured'
        drift.spreadMs == 4
        drift.groups.size() == 2
        r.status == Status.PASS
        r.hint.contains('Compared only within hvm-node01, hvm-node02; and within hvm-node03, hvm-node04')
    }

    def 'a chrony host and a timesyncd host reading one server are still compared'() {
        given:
        HealthChecker.Input i = Fixture.input([
                Fixture.host('hvm-node01', Fixture.facts('probe-healthy', 'hvm-node01') { it.replace(' 5    vm-one   running', '') }),
                Fixture.host('hvm-node02', Fixture.facts('probe-timesyncd', 'hvm-node02') { it.replace('203.0.113.57', '192.0.2.123').replace('+5.093ms', '-0.4ms').replace(' 1   vm-one   running', '') })], Fixture.LAYOUT_13)

        expect:
        HealthChecker.hostDrift(i.hosts).spreadMs == 0
    }

    // ------------------------------------------------------------------ multipath queueing

    private static HealthChecker.Result queueing(String queueLine, String features = '1 queue_if_no_path') {
        check(Fixture.timesyncd(null) { String t, String h ->
            t.replace('MP_QUEUE=360014059aaaaaaaaaaaaaaaaaaaaaaaa|12 chk', queueLine).replace("features='1 queue_if_no_path'", "features='${features}'".toString())
        }).find { it.id == 'STO-02' }
    }

    def 'the no_path_retry window is reported in seconds, and the N-sec and off-on-finite misreads are fixed'() {
        when:
        HealthChecker.Result r = queueing("MP_QUEUE=360014059aaaaaaaaaaaaaaaaaaaaaaaa|${value}".toString())

        then: 'the 1.3 fixture has a 60 s token, polling defaults to 5, and its LIO-ORG vendor is unknown so there is no vendor note'
        r.status == status
        r.observed == observed

        where:
        value    | status      | observed
        '12 chk' | Status.PASS | 'no_path_retry 12, about 61 s at polling 5'
        '30 sec' | Status.WARN | 'no paths on this map right now: 30 s left before I/O fails'
        'off'    | Status.WARN | 'no_path_retry off: fails I/O at once, a 0 s window'
        'on'     | Status.WARN | 'queueing on, with no limit'
    }

    def 'with no limit to read, the dm features line alone never claims endless queueing'() {
        expect:
        queueing(line).status == Status.NOT_CHECKED
        queueing(line).observed.startsWith('cannot tell')
        queueing(line, '0').status == Status.PASS
        queueing(line, '0').observed == 'not queueing'

        where:
        line << ['', 'MP_QUEUE=360014059aaaaaaaaaaaaaaaaaaaaaaaa|-']
    }

    // ------------------------------------------------------------------ ipmitool

    def 'a missing ipmitool reads as cannot tell, never as missing hardware, and the readiness says not checked'() {
        when:
        List<HealthChecker.Result> rs = check(Fixture.timesyncd())
        Map<String, Map> ready = HealthChecker.readiness(rs).collectEntries { [(it.area): it] }

        then:
        rs.findAll { it.id == 'FEN-01' }*.status.every { it == Status.NOT_CHECKED }
        rs.findAll { it.id == 'FEN-01' }*.observed.every { it == 'cannot tell: ipmitool not installed' }
        ['HW-01', 'HW-03', 'HW-04', 'HW-08'].every { String id -> rs.findAll { it.id == id }*.status == [Status.NOT_CHECKED] * 3 }
        rs.find { it.id == 'HW-01' }.hint.contains('Install it')
        ready['Hardware'].status == 'NOT_CHECKED'
        ready['Hardware'].notChecked == ['HW-01', 'HW-03', 'HW-04', 'HW-08']
        'FEN-01' in ready['High availability'].notChecked
        org.hvmhealth.morpheus.HealthMonitor.alarmsFor(1L, 'c', rs.findAll { it.status == Status.NOT_CHECKED }) == []
        !rs.any { it.id == 'HW-09' }
    }

    def 'every finding a missing ipmitool leaves unread offers the install, as a safe fix'() {
        when:
        List<HealthChecker.Result> unread = check(Fixture.timesyncd()).findAll { it.observed == HealthChecker.NO_IPMITOOL }

        then:
        unread*.id as Set == ['FEN-01', 'HW-01', 'HW-03', 'HW-04', 'HW-08'] as Set
        unread.every { it.fix == 'ipmitool-install' && it.tier == HealthChecker.Tier.SAFE }
    }

    def 'ipmitool with no controller answering is said once, in HW-08, with the fix that also loads the driver'() {
        when:
        List<HealthChecker.Result> rs = check(Fixture.timesyncd(null) { String t, String h -> t.replace('IPMITOOL=', 'IPMITOOL=/usr/bin/ipmitool') })
        HealthChecker.Result r = rs.find { it.id == 'HW-08' }

        then:
        r.status == Status.NOT_CHECKED
        r.observed == 'cannot tell: ipmitool is installed, but no management controller answered'
        r.fix == 'ipmitool-install'
        !rs.any { it.id == 'HW-09' }
    }

    def 'with ipmitool, no management controller is the real answer none, and no sensors is said'() {
        when:
        List<HealthChecker.Result> rs = check(Fixture.timesyncd(null) { String t, String h -> t.replace('IPMITOOL=', 'IPMITOOL=/usr/bin/ipmitool') })

        then:
        rs.find { it.id == 'FEN-01' }.status == Status.WARN
        rs.find { it.id == 'FEN-01' }.observed == 'none'
        rs.find { it.id == 'HW-01' }.status == Status.NOT_CHECKED
        rs.find { it.id == 'HW-01' }.observed == 'cannot tell: ipmitool read no such sensors here'
        !rs.any { it.id == 'HW-04' }
    }

    def 'a quick check, which does not look for ipmitool, says nothing about it'() {
        expect:
        !check(Fixture.timesyncd(null) { String t, String h -> t.replace('MODE=full', 'MODE=quick').replaceAll(/(?s)@@journals.*?@@end/, '@@end') }).any { it.id in ['FEN-01', 'HW-01', 'HW-03', 'HW-04', 'HW-08', 'HW-09'] }
    }

    def 'a management controller ipmitool reads still passes'() {
        expect:
        check(Fixture.timesyncd(null) { String t, String h -> t.replace('IPMITOOL=', 'IPMITOOL=/usr/bin/ipmitool').replace('BMC_IP=', 'BMC_IP=203.0.113.5') }).find { it.id == 'FEN-01' }.status == Status.PASS
    }

    def 'an area with passes stays OK, with what was not checked beside it, and nothing at all reads NA'() {
        given:
        List<HealthChecker.Result> rs = [
                new HealthChecker.Result('HW-02', 'mem', HealthChecker.Severity.HIGH, Status.PASS, 'a', 'ok', 'ok'),
                new HealthChecker.Result('HW-01', 'psu', HealthChecker.Severity.HIGH, Status.NOT_CHECKED, 'a', 'cannot tell', 'ok')]

        when:
        Map<String, Map> ready = HealthChecker.readiness(rs).collectEntries { [(it.area): it] }

        then:
        ready['Hardware'].status == 'PASS'
        ready['Hardware'].notChecked == ['HW-01']
        ready['Network'].status == 'NA'
    }

    // ------------------------------------------------------------------ the agent's version

    private static HealthChecker.Result agent(Map<String, String> versions) {
        check(Fixture.timesyncd(null) { String t, String h ->
            versions[h] ? t.replace('morpheus-agent|4.0.1', "morpheus-agent|${versions[h]}".toString()) : t.replace('morpheus-agent|4.0.1\n', '')
        }).find { it.title == 'Same Morpheus agent on every host' }
    }

    def 'the agent version is compared, from where the agent really lives'() {
        expect:
        agent(['hvm-node01': '4.0.1', 'hvm-node02': '4.0.1', 'hvm-node03': '4.0.1']).status == Status.PASS
        agent(['hvm-node01': '4.0.1', 'hvm-node02': '4.0.1', 'hvm-node03': '4.0.1']).observed == '4.0.1 on every host'
        agent(['hvm-node01': '4.0.1', 'hvm-node02': '4.0.2', 'hvm-node03': '4.0.1']).status == Status.WARN
        agent(['hvm-node01': '4.0.1', 'hvm-node02': '4.0.2', 'hvm-node03': '4.0.1']).observed == 'hvm-node01=4.0.1, hvm-node02=4.0.2, hvm-node03=4.0.1'
    }

    def 'an agent version that cannot be read is not checked, rather than a quiet pass'() {
        expect:
        agent([:]).status == Status.NOT_CHECKED
        agent([:]).observed.contains('cannot tell for hvm-node01, hvm-node02, hvm-node03')
        agent(['hvm-node01': '4.0.1', 'hvm-node03': '4.0.1']).status == Status.NOT_CHECKED
        agent(['hvm-node01': '4.0.1', 'hvm-node03': '4.0.1']).observed == 'hvm-node01=4.0.1, hvm-node03=4.0.1; cannot tell for hvm-node02: no agent version found on the host'
    }

    def 'an agent installed as a package still counts'() {
        expect:
        check(Fixture.healthy()).find { it.title == 'Same Morpheus agent on every host' }.observed == '4.0.1 on every host'
    }

    def 'the management bridge is listed without the probe trailing comma'() {
        expect:
        check(Fixture.timesyncd()).find { it.id == 'LAY-05' }.observed == 'mgmt'
        check(Fixture.frozen()).find { it.id == 'LAY-05' }.observed == 'compute, mgmt'
    }

    def 'a healthy 1.3 cluster on timesyncd has nothing failing'() {
        when:
        List<HealthChecker.Result> rs = check(Fixture.timesyncd())

        then:
        Fixture.failing(rs).isEmpty()
        HealthChecker.overall(rs) in ['HEALTHY', 'WARNING']
        !Fixture.warning(rs).contains('STO-02')
        !Fixture.warning(rs).contains('FEN-01')
    }

    def 'readiness answers each question with its worst status'() {
        when:
        Map<String, Map> r = HealthChecker.readiness(check(twoCpus('host-passthrough'))).collectEntries { [(it.area): it] }

        then:
        r['Live migration'].status == 'FAIL'
        r['Live migration'].ids.contains('MIG-01')
        r['Network'].status == 'PASS'
        r['Time'].status == 'WARN'   // no cluster time source defined in this sample
    }

    def 'a frozen cluster is not ready for anything that matters'() {
        when:
        Map<String, Map> r = HealthChecker.readiness(check(Fixture.frozen())).collectEntries { [(it.area): it] }

        then:
        r['High availability'].status == 'FAIL'
        r['High availability'].ids.contains('DLM-01')
    }

    def 'failed paths offer a fix that needs maintenance mode'() {
        given:
        HostFacts f = Fixture.facts('probe-healthy', 'hvm-node02') { it.replace('sde 8:64  active ready running', 'sde 8:64  failed faulty running') }

        when:
        HealthChecker.Result r = check(Fixture.input([Fixture.host('hvm-node02', f)], Fixture.LAYOUT_20)).find { it.id == 'STO-01' }

        then:
        r.status == Status.FAIL
        r.observed == '1 of 2 healthy'
        r.fix == 'multipath-reconfigure'
        r.tier == Tier.MAINTENANCE
    }

    def 'a host on the wrong OS for its layout fails, per layout'() {
        given:
        HostFacts f = Fixture.facts('probe-healthy', 'hvm-node02')

        expect:
        check(Fixture.input([Fixture.host('hvm-node02', f)], code)).find { it.id == 'LAY-02' }.status == status

        where:
        code               | status
        Fixture.LAYOUT_20  | Status.PASS
        Fixture.LAYOUT_13  | Status.FAIL
    }

    def 'layout network checks follow the layout'() {
        when:
        List<HealthChecker.Result> two = check(Fixture.healthy())
        List<HealthChecker.Result> one = check(Fixture.frozen())

        then:
        two.any { it.id == 'LAY-04' && it.status == Status.PASS }
        !two.any { it.id == 'LAY-05' }
        one.any { it.id == 'LAY-05' && it.status == Status.PASS }
        !one.any { it.id == 'LAY-04' }
    }

    def 'a host with no corosync.conf is not offered a service start'() {
        given:
        HostFacts f = Fixture.facts('probe-healthy', 'hvm-node02') { String t ->
            t.replaceAll(/(?s)@@corosync_conf.*?@@corosync_key/, '@@corosync_conf\n@@corosync_key').replace('corosync|active|enabled', 'corosync|failed|disabled').replace('dlm|active|enabled', 'dlm|inactive|disabled')
        }

        when:
        HealthChecker.Result r = check(Fixture.input([Fixture.host('hvm-node02', f)], Fixture.LAYOUT_20)).find { it.id == 'CLU-02' }

        then:
        r.status == Status.FAIL
        r.fix == null
        r.hint.contains('adding back through Morpheus')
    }

    def 'a host that did not answer does not stop the others being checked'() {
        given:
        HealthChecker.Input i = Fixture.input([Fixture.host('hvm-node01', new HostFacts()), Fixture.host('hvm-node02', Fixture.facts('probe-healthy', 'hvm-node02'))], Fixture.LAYOUT_20)

        when:
        List<HealthChecker.Result> rs = check(i)

        then:
        rs.find { it.id == 'PRB-01' }.host == 'hvm-node01'
        rs.any { it.host == 'hvm-node02' && it.id == 'CLU-03' }
    }

    def 'a stale fenced status on a healthy host is flagged as stale'() {
        given:
        HealthChecker.Input i = Fixture.input([Fixture.host('hvm-node02', Fixture.facts('probe-healthy', 'hvm-node02'), [status: 'Host has been fenced (nodedown)'])], Fixture.LAYOUT_20)

        expect:
        check(i).find { it.id == 'MOR-01' }.status == Status.WARN
    }

    def 'a disabled check is dropped, and a site value replaces the baseline'() {
        when:
        List<HealthChecker.Result> rs = check(Fixture.healthy('{"disabledChecks":["VM-01"],"gfs2WarnPct":5}'))

        then:
        !rs.any { it.id == 'VM-01' }
        rs.find { it.id == 'GFS-04' }.status == Status.WARN
        rs.find { it.id == 'GFS-04' }.source == 'site'
    }

    def 'results sort worst first'() {
        when:
        List<HealthChecker.Result> rs = check(Fixture.frozen())

        then:
        rs.first().severity == HealthChecker.Severity.CRITICAL
        rs.first().status == Status.FAIL
    }

    def 'the probe output parses to what the checks read'() {
        when:
        HostFacts f = Fixture.facts('probe-frozen', 'hvm-node02')

        then:
        f.complete
        f.frozen
        f.quorate
        f.expectedVotes == 3 && f.totalVotes == 2 && f.quorumVotes == 2
        f.nodelist*.nodeid == [1, 2, 3]
        f.corosync['totem.token'] == '60000'
        f.lockspaces[0].status == 'wait fencing'
        f.lockspaces[0].flags.contains('kern_stop')
        f.fenceWaits == [[node: 1, actor: 2, since: 1793379311L, now: 1793385845L]]
        f.dlmNodes*.state == ['X', 'M', 'M']
        f.acks == [3: 222]
        f.dCount == 647
        f.dVms == ['vm-one', 'vm-two']
        f.maps[0].paths.size() == 4
        f.vms*.name == ['vm-one', 'vm-two']
        f.bonds[0].slaves*.status == ['up', 'up']
        !f.fsSafe
    }

    def 'a truncated probe is incomplete'() {
        expect:
        !HostFacts.parse('@@meta\nHOSTNAME=x').complete
        !new HostFacts().complete
    }
}
