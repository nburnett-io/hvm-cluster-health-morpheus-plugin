package org.hvmhealth

import org.hvmhealth.checks.HealthChecker
import org.hvmhealth.checks.HealthChecker.Severity
import org.hvmhealth.checks.HealthChecker.Status
import org.hvmhealth.model.HostFacts
import org.hvmhealth.morpheus.HealthMonitor
import spock.lang.Specification
import spock.lang.Unroll

/**
 * Failover settings, quorum device, certificates, boot disks, CPU drift, migration space, tenant ports and frame size.
 * Probe lines below follow what real 2.0 and nested 1.3 hosts printed, with names, addresses and MACs replaced.
 */
class NewChecksSpec extends Specification {
    static final long NOW = 1793385845L   // NOW in probe-healthy
    static final String VLAN_120 = '[{"ifname":"vnet0","vlans":[{"vlan":120,"flags":["PVID","Egress Untagged"]}]}]'

    private static List<HealthChecker.Result> check(HealthChecker.Input i) { new HealthChecker().check(i) }

    private static List<HealthChecker.Result> ids(HealthChecker.Input i, String id) { check(i).findAll { it.id == id } }

    /** The sample's text with sections added before its end, as a later probe would print them. */
    private static Closure<String> plus(String extra) {
        return { String t -> t.replace('@@end', extra.stripIndent().trim() + '\n@@end') }
    }

    private static HealthChecker.Input cluster20(String extra, String site = null, List<Map> vms = []) {
        Fixture.input((1..3).collect { int n ->
            String name = "hvm-node0${n}".toString()
            Fixture.host(name, Fixture.facts('probe-healthy', name, plus(extra)))
        }, Fixture.LAYOUT_20, site, vms)
    }

    private static HealthChecker.Input cluster13(String extra, String site = null, List<Map> vms = []) {
        Fixture.input((1..3).collect { int n ->
            String name = "hvm-node0${n}".toString()
            Fixture.host(name, Fixture.facts('probe-timesyncd', name, plus(extra)))
        }, Fixture.LAYOUT_13, site, vms)
    }

    /** One host differs from the other two. */
    private static HealthChecker.Input onFirst20(String extra, String site = null, List<Map> vms = []) {
        Fixture.input((1..3).collect { int n ->
            String name = "hvm-node0${n}".toString()
            Fixture.host(name, Fixture.facts('probe-healthy', name, n == 1 ? plus(extra) : { String t -> t }))
        }, Fixture.LAYOUT_20, site, vms)
    }

    private static HealthChecker.Input onFirst13(String extra, String site = null, List<Map> vms = []) {
        Fixture.input((1..3).collect { int n ->
            String name = "hvm-node0${n}".toString()
            Fixture.host(name, Fixture.facts('probe-timesyncd', name, n == 1 ? plus(extra) : { String t -> t }))
        }, Fixture.LAYOUT_13, site, vms)
    }

    // ------------------------------------------------------------------ failover settings

    def 'both power-on settings on and a heartbeat target pass'() {
        given:
        HealthChecker.Input i = Fixture.healthy()
        i.clusterConfig = [autoRecoverPowerState: true, cloudAutoRecoverPowerState: true, datastores: [[name: 'ds-01', heartbeatTarget: true, cluster: true]]]

        when:
        List<HealthChecker.Result> rs = check(i)

        then:
        rs.find { it.id == 'HA-03' }.status == Status.PASS
        rs.find { it.id == 'HA-04' }.status == Status.NA
        rs.find { it.id == 'HA-05' }.status == Status.PASS
        rs.find { it.id == 'HA-05' }.observed == 'ds-01'
    }

    @Unroll
    def 'the power-on setting off on #where warns, with a hint naming where to change it'() {
        given:
        HealthChecker.Input i = Fixture.healthy()
        i.clusterConfig = [autoRecoverPowerState: cluster, cloudAutoRecoverPowerState: cloud]

        when:
        HealthChecker.Result r = check(i).find { it.id == id }

        then:
        r.status == Status.WARN
        r.severity == Severity.HIGH
        r.hint.contains("Edit ${where}")

        where:
        where         | id      | cluster | cloud
        'the cluster' | 'HA-03' | false   | true
    }

    def 'the cloud setting raises nothing, because in testing only the cluster setting decided a restart'() {
        given:
        HealthChecker.Input i = Fixture.healthy()
        i.clusterConfig = [autoRecoverPowerState: true, cloudAutoRecoverPowerState: false]

        when:
        HealthChecker.Result r = check(i).find { it.id == 'HA-04' }

        then:
        r.status == Status.NA
        r.observed.startsWith('off')
    }

    def 'with no value from Morpheus and no heartbeats to judge by, the power-on setting is not checked, not off and not on'() {
        given:
        HealthChecker.Input i = Fixture.healthy()
        i.hosts.each { it.facts.heartbeats = [] }

        when:
        List<HealthChecker.Result> rs = check(i)

        then:
        rs.find { it.id == 'HA-03' }.status == Status.NOT_CHECKED
        rs.find { it.id == 'HA-04' }.status == Status.NOT_CHECKED
    }

    def 'fresh heartbeats mean the cluster setting is on, and stale ones that it is off, as measured when flipping it'() {
        given:
        HealthChecker.Input i = Fixture.healthy()
        i.hosts.each { it.facts.heartbeats = [[host: it.name, ms: (it.facts.now - age) * 1000L]] }

        expect:
        check(i).find { it.id == 'HA-03' }.status == status

        where:
        age  | status
        20L  | Status.PASS
        600L | Status.WARN
    }

    def 'a 1.3 VM on a network with no VLAN matches what Morpheus says, even though the port is exposed'() {
        expect:
        ids(ports('1.3', ovsPort('vm-one', MAC, '[]'), tenantVm(null)), 'NET-12')*.status == [Status.PASS]
    }

    def 'a single host has nothing to fail over to, so its settings are not judged'() {
        given:
        HealthChecker.Input i = Fixture.input([Fixture.host('hvm-node01', Fixture.facts('probe-healthy', 'hvm-node01'))], Fixture.LAYOUT_20)
        i.clusterConfig = [autoRecoverPowerState: false, cloudAutoRecoverPowerState: false]

        when:
        List<HealthChecker.Result> rs = check(i)

        then:
        rs.find { it.id == 'HA-03' }.status == Status.NA
        !rs.any { it.id in ['HA-04', 'HA-05'] }
    }

    private static HealthChecker.Input noHeartbeatFiles(List<Map> datastores) {
        HealthChecker.Input i = Fixture.input((1..3).collect { int n ->
            String name = "hvm-node0${n}".toString()
            Fixture.host(name, Fixture.facts('probe-healthy', name) { String t -> t.readLines().findAll { !it.startsWith('HB=') }.join('\n') })
        }, Fixture.LAYOUT_20)
        i.clusterConfig = [datastores: datastores]
        i
    }

    def 'no datastore flagged and no heartbeat files warns'() {
        when:
        HealthChecker.Result r = ids(noHeartbeatFiles([[name: 'ds-01', heartbeatTarget: false]]), 'HA-05')[0]

        then:
        r.status == Status.WARN
        r.observed.contains('none of 1 datastore')
    }

    def 'heartbeat files prove a target whatever the record says, because the flag defaults to false on a partial record'() {
        given:
        HealthChecker.Input i = Fixture.healthy()
        i.clusterConfig = [datastores: [[name: 'ds-01', heartbeatTarget: false]]]

        when:
        HealthChecker.Result r = ids(i, 'HA-05')[0]

        then:
        r.status == Status.PASS
        r.observed.contains('writing heartbeat files')
    }

    def 'an unreadable or empty datastore list says nothing, and neither does GFS2 that could not be read'() {
        expect:
        ids(noHeartbeatFiles(null), 'HA-05')[0].status == Status.NOT_CHECKED
        ids(noHeartbeatFiles([]), 'HA-05')[0].status == Status.NOT_CHECKED

        when: 'the cluster is frozen, so heartbeat files were not read'
        HealthChecker.Input i = Fixture.frozen()
        i.clusterConfig = [datastores: [[name: 'ds-13', heartbeatTarget: false]]]

        then:
        ids(i, 'HA-05')[0].status == Status.NOT_CHECKED
    }

    def 'a target on another cluster in the same cloud, or offline, does not count'() {
        expect:
        ids(noHeartbeatFiles([[name: 'other-clusters', heartbeatTarget: true], [name: 'ds-01', heartbeatTarget: false]]), 'HA-05')[0].status == Status.WARN
        ids(noHeartbeatFiles([[name: 'ds-01', heartbeatTarget: true, online: false]]), 'HA-05')[0].status == Status.WARN
    }

    def 'HCI Ceph is not judged on a heartbeat target'() {
        given:
        HealthChecker.Input i = Fixture.input((1..3).collect { Fixture.host("hvm-node0${it}".toString(), Fixture.facts('probe-timesyncd', "hvm-node0${it}".toString())) }, 'mvm-1.3-ubuntu-24.04-morpheus-amd64')

        expect:
        ids(i, 'HA-05')[0].status == Status.NA
    }

    // ------------------------------------------------------------------ quorum device

    static final String QD_CONF = '''quorum {
    provider: corosync_votequorum
    device {
        model: net
        votes: 1
        net {
            host: witness.example.invalid
            algorithm: ffsplit
        }
    }
}'''

    /** Two hosts and a witness, as corosync-quorumtool and corosync-qdevice-tool print them. */
    private static HealthChecker.Input witness(String row, String state, int votes = 3, boolean tool = true) {
        Fixture.input((1..2).collect { int n ->
            String name = "hvm-node0${n}".toString()
            Fixture.host(name, Fixture.facts('probe-timesyncd', name) { String t ->
                String out = t.replace('quorum {\n    provider: corosync_votequorum\n}', QD_CONF)
                        .replace('Total votes:      3', "Total votes:      ${votes}")
                        .replace('Flags:            Quorate', 'Flags:            Quorate Qdevice')
                if (row) {
                    out = out.replace('    Nodeid      Votes Name', '    Nodeid      Votes    Qdevice Name')
                            .replace('         2          1 hvm-node02 (local)', "         ${n}          1    ${row} ${name} (local)")
                }
                out = out.replace('@@end', "@@qdevice\nQDEVICE_TOOL=${tool ? '/usr/sbin/corosync-qdevice-tool' : ''}\n" +
                        (state ? "QD=Qdevice-net information\nQD=Cluster name:           cluster-c\nQD=QNetd host:             witness.example.invalid:5403\nQD=Algorithm:              Fifty-Fifty split\nQD=State:                  ${state}\n" : '') + '@@end')
                out
            })
        }, Fixture.LAYOUT_13)
    }

    def 'a cluster with no device has one NA line and no noise, on either layout'() {
        expect:
        ids(Fixture.healthy(), 'CLU-12')*.status == [Status.NA]
        ids(Fixture.timesyncd(), 'CLU-12')*.status == [Status.NA]
    }

    def 'a connected, voting device passes'() {
        when:
        List<HealthChecker.Result> rs = ids(witness('A,V,NMW', 'Connected'), 'CLU-12')

        then:
        rs.size() == 2
        rs*.status.every { it == Status.PASS }
        rs[0].observed.contains('witness witness.example.invalid')
    }

    @Unroll
    def 'a device reading #row / #state at #votes votes of quorum 2 is #expected'() {
        expect:
        ids(witness(row, state, votes), 'CLU-12')*.status.every { it == expected }

        where:
        row       | state          | votes | expected
        'NA,NV,NMW' | 'Connected'  | 2     | Status.FAIL
        'NR'      | null           | 2     | Status.FAIL
        'A,V,NMW' | 'Disconnected' | 2     | Status.FAIL
        'NA,NV,NMW' | null         | 3     | Status.WARN
    }

    def 'a device nothing reports on is not checked, and a missing tool is said'() {
        when:
        HealthChecker.Result r = ids(witness(null, null, 3, false), 'CLU-12')[0]

        then:
        r.status == Status.NOT_CHECKED
        r.observed.contains('corosync-qdevice-tool is not installed')
    }

    def 'the tool alone can answer, as the quorum tool reads no device row on an old corosync'() {
        expect:
        ids(witness(null, 'Connected'), 'CLU-12')*.status.every { it == Status.PASS }
    }

    def 'a device in one host only fails that host'() {
        given:
        HealthChecker.Input i = witness('A,V,NMW', 'Connected')
        i.hosts << Fixture.host('hvm-node03', Fixture.facts('probe-timesyncd', 'hvm-node03'))

        when:
        List<HealthChecker.Result> rs = ids(i, 'CLU-12')

        then:
        rs.find { it.host == 'hvm-node03' }.status == Status.FAIL
        rs.find { it.host == 'hvm-node01' }.status == Status.PASS
    }

    // ------------------------------------------------------------------ certificates

    private static String certs(String lines, int sockets = 1) {
        "@@certs\nOPENSSL=/usr/bin/openssl\nTLS_SOCKETS=${sockets}\nTLS_CONF=0\n${lines}".toString()
    }

    def 'hosts with no libvirt certificates are one NA line, as real hosts of both layouts have none'() {
        expect:
        ids(cluster20("@@certs\nOPENSSL=/usr/bin/openssl\nTLS_SOCKETS=0\nTLS_CONF=0"), 'HST-02')*.status == [Status.NA]
        ids(cluster13("@@certs\nOPENSSL=/usr/bin/openssl\nTLS_SOCKETS=0\nTLS_CONF=0"), 'HST-02')*.status == [Status.NA]
    }

    @Unroll
    def 'a certificate in use with #days days left is #expected'() {
        given:
        HealthChecker.Input i = onFirst20(certs("CERT=/etc/pki/libvirt/servercert.pem|${NOW + days * 86400L}"))

        when:
        HealthChecker.Result r = ids(i, 'HST-02').find { it.host == 'hvm-node01' }

        then:
        r.status == expected
        r.title.contains('/etc/pki/libvirt/servercert.pem')

        where:
        days | expected
        400  | Status.PASS
        31   | Status.PASS
        30   | Status.WARN
        8    | Status.WARN
        7    | Status.FAIL
        -2   | Status.FAIL
    }

    def 'the thresholds come from the standards'() {
        given:
        HealthChecker.Input i = onFirst20(certs("CERT=/etc/pki/CA/cacert.pem|${NOW + 50 * 86400L}"), '{"certWarnDays": 60, "certFailDays": 10}')

        expect:
        ids(i, 'HST-02')[0].status == Status.WARN
    }

    def 'an expired certificate nothing uses raises no alarm: hosts migrate over SSH'() {
        when:
        HealthChecker.Result r = ids(onFirst20(certs("CERT=/etc/pki/CA/cacert.pem|${NOW - 86400L}", 0)), 'HST-02')[0]

        then:
        r.status == Status.NA
        r.observed.contains('not in use')
    }

    def 'qemu configured for TLS counts as in use'() {
        given:
        String lines = "@@certs\nOPENSSL=/usr/bin/openssl\nTLS_SOCKETS=0\nTLS_CONF=1\nCERT=/etc/pki/qemu/server-cert.pem|${NOW - 86400L}"

        expect:
        ids(onFirst20(lines), 'HST-02')[0].status == Status.FAIL
    }

    def 'a certificate with no openssl to read it, or an unreadable date, is not checked: an empty date is not expired'() {
        when:
        HealthChecker.Result none = ids(onFirst20("@@certs\nOPENSSL=\nTLS_SOCKETS=1\nTLS_CONF=0\nCERT=/etc/pki/CA/cacert.pem|"), 'HST-02')[0]
        HealthChecker.Result bad = ids(onFirst20(certs('CERT=/etc/pki/CA/cacert.pem|')), 'HST-02')[0]

        then:
        none.status == Status.NOT_CHECKED
        none.observed.contains('openssl not installed')
        bad.status == Status.NOT_CHECKED
        bad.observed.contains('could not be read')
    }

    @Unroll
    def 'the appliance certificate with #days days left is #expected'() {
        given:
        HealthChecker.Input i = Fixture.healthy()
        i.nowMs = NOW * 1000L
        i.clusterConfig = [applianceCertNotAfterMs: (NOW + days * 86400L) * 1000L]

        expect:
        ids(i, 'HST-03')*.status == [expected]

        where:
        days | expected
        200  | Status.PASS
        20   | Status.WARN
        3    | Status.FAIL
    }

    def 'the appliance certificate is not checked when unreadable, and left out when not asked'() {
        given:
        HealthChecker.Input i = Fixture.healthy()

        expect:
        ids(i, 'HST-03') == []

        when:
        i.clusterConfig = [applianceCertNotAfterMs: null]

        then:
        ids(i, 'HST-03')*.status == [Status.NOT_CHECKED]
    }

    // ------------------------------------------------------------------ boot disks

    private static String smart(String... lines) {
        "@@smart\nSMARTCTL=/usr/sbin/smartctl\nBOOT_DISK=sda\n" + lines.collect { "SMART=sda|${it}" }.join('\n')
    }

    static final List<String> ATA_OK = ['Device Model:     Example SSD 240',
                                        'SMART overall-health self-assessment test result: PASSED',
                                        '  5 Reallocated_Sector_Ct   0x0033   100   100   010    Pre-fail  Always       -       0',
                                        '197 Current_Pending_Sector  0x0032   100   100   000    Old_age   Always       -       0',
                                        '198 Offline_Uncorrectable   0x0030   100   100   000    Old_age   Offline      -       0']

    private static String ataWith(String attr, long raw) {
        smart(*ATA_OK.collect { it.contains(attr) ? it.replaceFirst(/\d+$/, "${raw}") : it } as String[])
    }

    def 'a healthy ATA disk passes, on either layout'() {
        expect:
        ids(cluster20(smart(*ATA_OK as String[])), 'HW-07')*.status.every { it == Status.PASS }
        ids(cluster13(smart(*ATA_OK as String[])), 'HW-07')*.status.every { it == Status.PASS }
    }

    def 'pending or uncorrectable sectors warn, and reallocated ones are only shown'() {
        when:
        HealthChecker.Result pending = ids(onFirst20(ataWith('Current_Pending', 8)), 'HW-07')[0]
        HealthChecker.Result uncorrectable = ids(onFirst20(ataWith('Offline_Uncorrectable', 2)), 'HW-07')[0]
        HealthChecker.Result reallocated = ids(onFirst20(ataWith('Reallocated', 12)), 'HW-07')[0]

        then:
        pending.status == Status.WARN
        pending.observed.contains('8 bad sector')
        uncorrectable.status == Status.WARN
        reallocated.status == Status.PASS
        reallocated.observed.contains('12 reallocated')
    }

    def 'a disk that says it is failing fails'() {
        when:
        HealthChecker.Result r = ids(onFirst20(smart('Device Model:     Example SSD 240', 'SMART overall-health self-assessment test result: FAILED!')), 'HW-07')[0]

        then:
        r.status == Status.FAIL
        r.observed.contains('FAILED!')
    }

    def 'NVMe and SCSI disks are read as well'() {
        expect:
        ids(onFirst20(smart('Model Number:    Example NVMe', 'SMART overall-health self-assessment test result: PASSED', 'Critical Warning:                   0x00', 'Media and Data Integrity Errors:    0')), 'HW-07')[0].status == Status.PASS
        ids(onFirst20(smart('Model Number:    Example NVMe', 'SMART overall-health self-assessment test result: PASSED', 'Critical Warning:                   0x04', 'Media and Data Integrity Errors:    0')), 'HW-07')[0].status == Status.FAIL
        ids(onFirst20(smart('Model Number:    Example NVMe', 'SMART overall-health self-assessment test result: PASSED', 'Critical Warning:                   0x00', 'Media and Data Integrity Errors:    3')), 'HW-07')[0].status == Status.WARN
        ids(onFirst20(smart('Product:              Example SAS', 'SMART Health Status: OK')), 'HW-07')[0].status == Status.PASS
        ids(onFirst20(smart('Product:              Example SAS', 'SMART Health Status: LOGICAL UNIT FAILURE PREDICTION THRESHOLD EXCEEDED')), 'HW-07')[0].status == Status.FAIL
    }

    def 'smartctl missing is not checked, never a pass or a failure'() {
        when:
        HealthChecker.Result r = ids(onFirst20("@@smart\nSMARTCTL=\nBOOT_DISK=sda"), 'HW-07')[0]

        then:
        r.status == Status.NOT_CHECKED
        r.observed.contains('smartctl not installed')
    }

    def 'a RAID card volume with no SMART, as seen on real 2.0 hosts, is not checked'() {
        when:
        HealthChecker.Result r = ids(onFirst20(smart('Device Model:     EXAMPLEBOOT VD', 'SMART support is: Unavailable - device lacks SMART capability.',
                'A mandatory SMART command failed: exiting. To continue, add one or more \'-T permissive\' options.')), 'HW-07')[0]

        then:
        r.status == Status.NOT_CHECKED
        r.observed.contains('virtual disk')
    }

    def 'a virtual disk claiming to be healthy still says nothing about real hardware'() {
        expect:
        ids(onFirst20(smart('Product:              LOGICAL VOLUME', 'SMART Health Status: OK')), 'HW-07')[0].status == Status.NOT_CHECKED
    }

    def 'a virtio disk that smartctl cannot open, as on a nested 1.3 host, is not checked'() {
        given:
        String nested = "@@smart\nSMARTCTL=/usr/sbin/smartctl\nBOOT_DISK=vda\nSMART=vda|/dev/vda: Unable to detect device type\nSMART=vda|Please specify device type with the -d option."

        when:
        HealthChecker.Result r = ids(onFirst13(nested), 'HW-07')[0]

        then:
        r.status == Status.NOT_CHECKED
        r.observed.contains('gave no SMART health')
    }

    def 'no disk found under / is not checked'() {
        expect:
        ids(onFirst20("@@smart\nSMARTCTL=/usr/sbin/smartctl"), 'HW-07')*.status == [Status.NOT_CHECKED]
    }

    // ------------------------------------------------------------------ CPU drift

    def 'with no named model the check is NA, whatever VMs run'() {
        given:
        HealthChecker.Input none = onFirst20("@@vm_cpu\nVM_CPU=vm-one|host")
        HealthChecker.Input passthrough = onFirst20("@@vm_cpu\nVM_CPU=vm-one|host")
        passthrough.clusterConfig = [cpuModel: 'host-passthrough']

        expect:
        ids(none, 'MIG-06')*.status == [Status.NA]
        ids(passthrough, 'MIG-06')*.status == [Status.NA]
    }

    @Unroll
    def 'a VM on host-passthrough under a named model warns, on #name'() {
        given:
        i.clusterConfig = [cpuModel: 'Skylake-Server-noTSX-IBRS']

        when:
        HealthChecker.Result r = ids(i, 'MIG-06').find { it.host == 'hvm-node01' }

        then:
        r.status == Status.WARN
        r.observed.contains('vm-one')
        r.hint.contains('rebuild')

        where:
        name  | i
        '2.0' | onFirst20("@@vm_cpu\nVM_CPU=vm-one|host\nVM_CPU=vm-two|Skylake-Server-noTSX-IBRS")
        '1.3' | onFirst13("@@vm_cpu\nVM_CPU=vm-one|host")
    }

    def 'a VM already on the named model passes'() {
        given:
        HealthChecker.Input i = onFirst20("@@vm_cpu\nVM_CPU=vm-one|Skylake-Server-noTSX-IBRS")
        i.clusterConfig = [cpuModel: 'Skylake-Server-noTSX-IBRS']

        expect:
        ids(i, 'MIG-06')*.status == [Status.PASS]
    }

    def 'running VMs with no qemu process read are not checked'() {
        given:
        HealthChecker.Input i = onFirst20('@@vm_cpu')
        i.clusterConfig = [cpuModel: 'Skylake-Server-noTSX-IBRS']

        expect:
        ids(i, 'MIG-06')*.status == [Status.NOT_CHECKED]
    }

    // ------------------------------------------------------------------ storage migration space

    static final String TARGET = '/mnt/11111111-2222-3333-4444-555555555555'
    static final long GIB = 1L << 30

    private static HealthChecker.Input spaced(List<Map> vms) {
        cluster20("@@pool_paths\nPOOL_PATH=${TARGET}|ds-01", null, vms)
    }

    def 'a VM elsewhere that fits passes, against what the datastore has free'() {
        when:
        HealthChecker.Result r = ids(spaced([[name: 'big', power: 'off', disks: [[ds: 'local', size: 200 * GIB]]]]), 'GFS-09')[0]

        then: 'the sample has 4096 GiB of which 10% used'
        r.status == Status.PASS
        r.title.contains('ds-01')
        r.observed.contains('largest to move big at 200 GiB')
    }

    def 'a VM elsewhere that would not fit warns'() {
        when:
        HealthChecker.Result r = ids(spaced([[name: 'big', power: 'off', disks: [[ds: 'local', size: 5000 * GIB]]]]), 'GFS-09')[0]

        then:
        r.status == Status.WARN
        r.observed.contains('big has 5000 GiB elsewhere')
    }

    def 'a VM wholly on the only datastore has nowhere to move from, so a healthy one-datastore cluster raises nothing'() {
        when:
        List<HealthChecker.Result> rs = ids(spaced([[name: 'big', power: 'on', disks: [[ds: 'ds-01', size: 9000 * GIB]]]]), 'GFS-09')

        then:
        rs*.status == [Status.NA]
    }

    def 'only the disks not on the target count'() {
        expect:
        ids(spaced([[name: 'split', power: 'on', disks: [[ds: 'ds-01', size: 9000 * GIB], [ds: 'local', size: 10 * GIB]]]]), 'GFS-09')[0].status == Status.PASS
    }

    def 'with disk placement unknown a shortfall is not raised, as the VM may be there already'() {
        when:
        HealthChecker.Result r = ids(spaced([[name: 'big', power: 'on', disks: [[ds: null, size: 5000 * GIB]]]]), 'GFS-09')[0]

        then:
        r.status == Status.NOT_CHECKED
        r.observed.contains('may be here already')

        and: 'and one that fits wherever it is passes'
        ids(spaced([[name: 'small', power: 'on', disks: [[ds: null, size: 5 * GIB]]]]), 'GFS-09')[0].status == Status.PASS
    }

    def 'no disk sizes from Morpheus is not checked'() {
        expect:
        ids(Fixture.healthy(), 'GFS-09')*.status == [Status.NOT_CHECKED]
    }

    def 'a frozen cluster did not read free space, so nothing is said'() {
        expect:
        ids(Fixture.frozen(), 'GFS-09') == []
    }

    def 'the datastore is named by its path where the host did not give a pool, on 1.3 too'() {
        when:
        HealthChecker.Result r = ids(cluster13('@@pool_paths', null, [[name: 'big', power: 'off', disks: [[ds: 'local', size: 5 * GIB]]]]), 'GFS-09')[0]

        then:
        r.title.contains(TARGET)
        r.status == Status.PASS
    }

    // ------------------------------------------------------------------ tenant network isolation

    private static String brPort(String vm, String mac, String json = VLAN_120, String filtering = '1', String dev = 'vnet0') {
        "BR_PORT=${vm}|${dev}|${mac}|hvmbr0|${filtering}|${json}".toString()
    }

    private static String ovsPort(String vm, String mac, String tag, String trunks = '[]', String mode = '[]') {
        "OVS_PORT=${vm}|vnet0|${mac}|mgmt|${tag}|${trunks}|${mode}".toString()
    }

    private static final String MAC = '02:00:00:00:00:01'

    private static Map tenantVm(Integer vlan, Map over = [:]) {
        [name: 'vm-one', host: 'hvm-node01', power: 'on', status: 'provisioned', tenant: 'Tenant A', tenantId: 11L, operator: false,
         nics: [[mac: MAC.toUpperCase(), vlan: vlan, network: 'net-a']]] + over
    }

    private static final List<Map> OWNERS = [[vlan: 120, name: 'net-a', ownerId: 11L, owner: 'Tenant A', shared: false],
                                             [vlan: 300, name: 'net-b', ownerId: 22L, owner: 'Tenant B', shared: false],
                                             [vlan: 400, name: 'net-shared', ownerId: 1L, owner: 'Operators', shared: true],
                                             [vlan: 500, name: 'net-a2', ownerId: 11L, owner: 'Tenant A', shared: false],
                                             [vlan: 500, name: 'net-b2', ownerId: 22L, owner: 'Tenant B', shared: false],
                                             [vlan: 600, name: 'net-unknown', ownerId: null, owner: null, shared: null]]

    /** 2.0 or 1.3, with the port saying this VLAN and Morpheus that one. */
    private static HealthChecker.Input ports(String layout, String line, Map vm, List<Map> owners = OWNERS, String site = null) {
        HealthChecker.Input i = layout == '2.0' ? onFirst20("@@vm_ports\n${line}", site, [vm]) : onFirst13("@@vm_ports\n${line}", site, [vm])
        i.tenantNetworks = owners
        i
    }

    private static String vlan(int v) {
        "[{\"ifname\":\"vnet0\",\"vlans\":[{\"vlan\":${v},\"flags\":[\"PVID\",\"Egress Untagged\"]}]}]".toString()
    }

    def 'a port on the VLAN Morpheus says passes on each layout, matching the MAC in any case'() {
        expect:
        ids(ports('2.0', brPort('vm-one', MAC), tenantVm(120)), 'NET-12')*.status == [Status.PASS]
        ids(ports('1.3', ovsPort('vm-one', MAC, '120'), tenantVm(120)), 'NET-12')*.status == [Status.PASS]
    }

    @Unroll
    def 'a port on another tenant\'s VLAN is critical on #layout'() {
        when:
        List<HealthChecker.Result> rs = check(ports(layout, line, tenantVm(120)))
        HealthChecker.Result r = rs.find { it.id == 'NET-12' }

        then:
        r.status == Status.FAIL
        r.severity == Severity.CRITICAL
        r.observed.contains('VLAN 300')
        r.observed.contains('Tenant B')
        HealthChecker.overall(rs) == 'CRITICAL'

        where:
        layout | line
        '2.0'  | brPort('vm-one', MAC, vlan(300))
        '1.3'  | ovsPort('vm-one', MAC, '300')
    }

    @Unroll
    def 'a wrong VLAN is only a warning unless it is solid: #why'() {
        when:
        HealthChecker.Result r = ids(ports('2.0', brPort('vm-one', MAC, vlan(port)), vm), 'NET-12')[0]

        then:
        r.status == Status.WARN
        r.severity == Severity.HIGH

        where:
        why                                    | port | vm
        'no network has that VLAN'             | 700  | tenantVm(120)
        'the operators may share it'           | 400  | tenantVm(120)
        'the VM\'s own tenant is on it too'    | 500  | tenantVm(120)
        'its owner is unknown'                 | 600  | tenantVm(120)
        'Morpheus has it on another host'      | 300  | tenantVm(120, [host: 'hvm-node02'])
        'the VM is mid-change'                 | 300  | tenantVm(120, [status: 'reconfiguring'])
        'Morpheus has it powered off'          | 300  | tenantVm(120, [power: 'off'])
        'the VM is the operators\' own'        | 300  | tenantVm(120, [operator: true])
        'the tenant of the VM could not load'  | 300  | tenantVm(120, [operator: null])
        'the VM has no tenant on record'       | 300  | tenantVm(120, [tenantId: null])
    }

    def 'the same MAC on a VM of another name is not joined, so a clone cannot raise a finding'() {
        expect:
        ids(ports('2.0', brPort('vm-clone', MAC, vlan(300)), tenantVm(120)), 'NET-12')*.status == [Status.NOT_CHECKED]
    }

    @Unroll
    def 'what cannot be compared raises nothing: #why'() {
        expect:
        ids(ports(layout, line, vm), 'NET-12')*.status == [Status.NOT_CHECKED]

        where:
        why                           | layout | line                                      | vm
        'a bridge not filtering VLANs' | '2.0'  | brPort('vm-one', MAC, vlan(1), '0')       | tenantVm(120)
        'an empty VLAN list'          | '2.0'  | brPort('vm-one', MAC, '[]')               | tenantVm(120)
        'unreadable VLAN output'      | '2.0'  | brPort('vm-one', MAC, 'nonsense')         | tenantVm(120)
        'a network with no VLAN'      | '2.0'  | brPort('vm-one', MAC)                     | tenantVm(null)
        'no nics from Morpheus'       | '2.0'  | brPort('vm-one', MAC)                     | tenantVm(120, [nics: []])
        'a VM Morpheus does not have' | '2.0'  | brPort('vm-other', MAC)                   | tenantVm(120)
        'an untagged 1.3 port'        | '1.3'  | ovsPort('vm-one', MAC, '[]')              | tenantVm(120)
    }

    def 'a host with no running VMs reports no ports, and one out of time says so'() {
        expect:
        ids(onFirst20('@@vm_ports'), 'NET-12') == []
        ids(onFirst20("@@vm_ports\nPORTS_CUT=1"), 'NET-12')*.status == [Status.NOT_CHECKED]
    }

    def 'ports are only read on a full check, and their answer stands between them'() {
        expect:
        ['NET-12', 'NET-13', 'HW-07', 'HST-02', 'HST-03'].every { it in HealthMonitor.FULL_ONLY }
    }

    // trunks

    def 'one VLAN per port passes on 2.0'() {
        expect:
        ids(ports('2.0', brPort('vm-one', MAC), tenantVm(120)), 'NET-13')*.status == [Status.PASS]
    }

    def 'an access port on 1.3 passes'() {
        expect:
        ids(ports('1.3', ovsPort('vm-one', MAC, '120'), tenantVm(120)), 'NET-13')*.status == [Status.PASS]
    }

    def 'on 1.3 an operator VM on an untagged port raises nothing, and is said not to prove isolation'() {
        given:
        HealthChecker.Input i = ports('1.3', ovsPort('vm-one', MAC, '[]'), tenantVm(null))
        i.morpheusVms.each { it.operator = true }

        when:
        HealthChecker.Result r = ids(i, 'NET-13')[0]

        then:
        r.status == Status.NOT_CHECKED
        r.observed.contains('passes any VLAN')
    }

    def 'on 1.3 a customer VM on an untagged port is exposed, as measured with traffic'() {
        when:
        HealthChecker.Result r = ids(ports('1.3', ovsPort('vm-one', MAC, '[]'), tenantVm(null)), 'NET-13')[0]

        then:
        r.status in [Status.WARN, Status.FAIL]
        r.observed.contains('no VLAN tag')
        r.hint.contains('Move this VM')
    }

    @Unroll
    def 'a trunk with no other tenant on it warns on #layout'() {
        when:
        HealthChecker.Result r = ids(ports(layout, line, tenantVm(120), [OWNERS[0]]), 'NET-13')[0]

        then:
        r.status == Status.WARN
        r.severity == Severity.HIGH
        r.observed.contains('tagged VLANs')
        r.title.contains('vm-one')

        where:
        layout | line
        '2.0'  | brPort('vm-one', MAC, '[{"ifname":"vnet0","vlans":[{"vlan":120,"flags":["PVID","Egress Untagged"]},{"vlan":210},{"vlan":220,"vlanEnd":222}]}]')
        '1.3'  | ovsPort('vm-one', MAC, '[]', '[120, 210]')
        '1.3'  | ovsPort('vm-one', MAC, '120', '[210, 220]', 'native-untagged')
    }

    @Unroll
    def 'a trunk reaching another tenant is critical on #layout'() {
        when:
        HealthChecker.Result r = ids(ports(layout, line, tenantVm(120)), 'NET-13')[0]

        then:
        r.status == Status.FAIL
        r.severity == Severity.CRITICAL
        r.observed.contains('Tenant B')

        where:
        layout | line
        '2.0'  | brPort('vm-one', MAC, '[{"ifname":"vnet0","vlans":[{"vlan":120,"flags":["PVID","Egress Untagged"]},{"vlan":300}]}]')
        '1.3'  | ovsPort('vm-one', MAC, '[]', '[300]')
        '1.3'  | ovsPort('vm-one', MAC, '120', '[]', 'trunk')
    }

    def 'a VM named in trunkAllowedVms may have a trunk'() {
        when:
        List<HealthChecker.Result> rs = ids(ports('2.0', brPort('vm-one', MAC, '[{"ifname":"vnet0","vlans":[{"vlan":120,"flags":["PVID","Egress Untagged"]},{"vlan":300}]}]'), tenantVm(120), OWNERS, '{"trunkAllowedVms":["vm-one"]}'), 'NET-13')

        then:
        rs*.status == [Status.PASS]
    }

    def 'trunks on a bridge not filtering VLANs cannot be seen, so that is not checked rather than passed'() {
        expect:
        ids(ports('2.0', brPort('vm-one', MAC, vlan(1), '0'), tenantVm(120)), 'NET-13')*.status == [Status.NOT_CHECKED]
    }

    // ------------------------------------------------------------------ frame size

    private static Closure<String> mfs(String v) {
        return { String t -> t.replace('lldp.eno1.port.ifname=Te1/0/14', "lldp.eno1.port.ifname=Te1/0/14\nlldp.eno1.port.mfs=${v}") }
    }

    private static HealthChecker.Input framed(String advertised, int mtu) {
        Fixture.input([Fixture.host('hvm-node01', Fixture.facts('probe-healthy', 'hvm-node01') { String t ->
            mfs(advertised).call(t).replace('@@links_net\n', "@@links_net\neno1|${mtu}|UP\n")
        })], Fixture.LAYOUT_20)
    }

    def 'a switch that advertises nothing gets no row, as on the real switches seen so far'() {
        expect:
        ids(Fixture.healthy(), 'NET-14') == []
    }

    @Unroll
    def 'frame size #advertised against MTU #mtu is #expected'() {
        when:
        HealthChecker.Result r = ids(framed(advertised, mtu), 'NET-14')[0]

        then:
        r.status == expected
        r.severity == Severity.LOW
        r.observed == "switch advertises ${advertised}, host MTU ${mtu}".toString()

        where:
        advertised | mtu  | expected
        '9216'     | 9000 | Status.PASS
        '9000'     | 9000 | Status.PASS
        '1522'     | 1500 | Status.PASS
        '1522'     | 9000 | Status.WARN
    }

    def 'an unreadable frame size, or no MTU for the uplink, gets no row'() {
        expect:
        ids(framed('unknown', 9000), 'NET-14') == []
        ids(Fixture.input([Fixture.host('hvm-node01', Fixture.facts('probe-healthy', 'hvm-node01', mfs('9216')))], Fixture.LAYOUT_20), 'NET-14') == []
    }

    // ------------------------------------------------------------------ noise

    def 'a healthy cluster of either layout raises no alarm from any of these, with every section present and only real-host values'() {
        given:
        String common = """@@qdevice
QDEVICE_TOOL=
@@certs
OPENSSL=/usr/bin/openssl
TLS_SOCKETS=0
TLS_CONF=0
@@smart
SMARTCTL=/usr/sbin/smartctl
BOOT_DISK=sdc
SMART=sdc|Device Model:     EXAMPLEBOOT VD
SMART=sdc|SMART support is: Unavailable - device lacks SMART capability."""
        List<Map> vms = [[name: 'vm-one', host: 'hvm-node01', power: 'on', status: 'provisioned', tenantId: 1L, operator: true, nics: [[mac: MAC, vlan: null, network: 'Management']], disks: [[ds: 'ds-01', size: 80 * GIB]]]]
        String two = "${common}\n@@vm_cpu\nVM_CPU=vm-one|host\n@@vm_ports\n${brPort('vm-one', MAC)}".toString()
        String one = "${common}\n@@vm_cpu\nVM_CPU=vm-one|host\n@@vm_ports\n${ovsPort('vm-one', MAC, '[]')}".toString()
        List<String> ours = ['HA-03', 'HA-04', 'HA-05', 'CLU-12', 'HST-02', 'HST-03', 'HW-07', 'MIG-06', 'GFS-09', 'NET-12', 'NET-13', 'NET-14']

        expect:
        check(cluster20(two, '{"ntpServers":["ntp1.example.invalid"]}', vms)).findAll { it.id in ours && it.status in [Status.FAIL, Status.WARN] } == []
        check(cluster13(one, null, vms)).findAll { it.id in ours && it.status in [Status.FAIL, Status.WARN] } == []
    }

    def 'facts a probe did not gather stay empty rather than becoming findings'() {
        given:
        HostFacts f = HostFacts.parse(Fixture.text('probe-frozen'))

        expect:
        !f.vmPorts && !f.bootDisks && !f.certs && !f.vmCpus && !f.poolPaths
        f.qdeviceRow == null && f.qdeviceState == null
        f.smartctlPresent == null && f.opensslPresent == null && f.qdeviceToolPresent == null
    }
}
