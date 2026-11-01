package org.hvmhealth

import groovy.json.JsonOutput
import org.hvmhealth.checks.HealthChecker
import org.hvmhealth.checks.HealthChecker.Status
import org.hvmhealth.model.HostFacts
import org.hvmhealth.morpheus.FindingHistory
import org.hvmhealth.morpheus.HealthContext
import org.hvmhealth.morpheus.HealthReport
import spock.lang.Specification
import spock.lang.Unroll

/** HST-04 to HST-08, VM-06, and the busiest VMs: items 7 and 8 of 0.4.0. Also what the probe's text must say, since nothing here can run it. */
class HostLoadSpec extends Specification {
    /** The fixture's NOW. */
    static final long NOW = 1793385845L
    static final String RECENT = '2026-10-31T00:00:00+0000'
    static final String HEALTH = 'PSI_CPU=0.40\nPSI_MEMORY=0.00\nOOM_READ=1\nVM_LOGS_READ=1\nROOT_DF=/|40|12\nROOT_DF=/boot|30|1'

    private static String probe() {
        HealthContext.getResourceAsStream('/scripts/health-probe.sh').getText('UTF-8')
    }

    private static HealthChecker.Input one(String extra, String site = null, List<Map> vms = [], String sample = 'probe-healthy') {
        Fixture.input([Fixture.host('hvm-node01', Fixture.facts(sample, 'hvm-node01') { String t -> t.replace('@@end', "${extra}\n@@end") })], Fixture.LAYOUT_20, site, vms)
    }

    private static HealthChecker.Input health(String edit, String site = null, List<Map> vms = []) {
        one("@@host_health\n${edit}".toString(), site, vms)
    }

    private static List<HealthChecker.Result> ids(HealthChecker.Input i, String id) {
        new HealthChecker().check(i).findAll { it.id == id }
    }

    private static HealthChecker.Result only(HealthChecker.Input i, String id) {
        List<HealthChecker.Result> rs = ids(i, id)
        assert rs.size() == 1
        rs[0]
    }

    def 'a healthy host passes all of them and raises nothing'() {
        expect:
        ['HST-04', 'HST-05', 'HST-06', 'HST-07', 'HST-08', 'VM-06'].each { String id ->
            assert ids(Fixture.healthy(), id)*.status.unique() == [Status.PASS], id
        }
    }

    def 'a frozen host answers them too, as none of it touches a datastore'() {
        expect:
        ids(Fixture.frozen(), 'HST-08')*.status.unique() == [Status.PASS]
    }

    def 'a probe with no such section says nothing rather than failing'() {
        given:
        HealthChecker.Input i = one('')
        i.hosts[0].facts.sections.remove('host_health')

        expect:
        !new HealthChecker().check(i).any { it.id in ['HST-04', 'HST-05', 'HST-06', 'HST-07', 'HST-08', 'VM-06'] }
    }

    def 'a host that did not answer gets none of them, only PRB-01'() {
        given:
        HealthChecker.Input i = Fixture.input([Fixture.host('hvm-node01', new HostFacts())], Fixture.LAYOUT_20)

        expect:
        !new HealthChecker().check(i).any { it.id in ['HST-04', 'HST-05', 'HST-06', 'HST-07', 'HST-08', 'VM-06'] }
    }

    // ------------------------------------------------------------------ HST-04

    @Unroll
    def 'OOM kills: #why is #want'() {
        when:
        HealthChecker.Result r = only(health("OOM_READ=1\nVM_LOGS_READ=1\n${lines}"), 'HST-04')

        then:
        r.status == want

        where:
        why                    | lines                                                                         | want
        'none'                 | ''                                                                            | Status.PASS
        'a daemon'             | "OOM=${RECENT}|java|"                                                          | Status.WARN
        'a qemu'               | "OOM=${RECENT}|qemu-system-x86|"                                               | Status.FAIL
        'a qemu thread'        | "OOM=${RECENT}|CPU 2/KVM|"                                                     | Status.FAIL
        'anything in a VM cgroup' | "OOM=${RECENT}|worker|machine-qemu\\x2d5\\x2dvm\\x2done.scope"              | Status.FAIL
        'a qemu among others'  | "OOM=${RECENT}|java|\nOOM=${RECENT}|qemu-system-x86|"                           | Status.FAIL
    }

    def 'its observed text counts the victims by process, and names no VM'() {
        when:
        HealthChecker.Result r = only(health("OOM_READ=1\nVM_LOGS_READ=1\nOOM=${RECENT}|java|\nOOM=${RECENT}|java|\nOOM=${RECENT}|qemu-system-x86|machine-qemu\\x2d5\\x2dvm\\x2done.scope"), 'HST-04')

        then:
        r.observed == '3 in 24 h: java (2), qemu-system-x86 (1)'
        !r.observed.contains('vm-one')
    }

    def 'a kernel log that could not be read is not checked, not a pass'() {
        expect:
        only(health('VM_LOGS_READ=1'), 'HST-04').status == Status.NOT_CHECKED
    }

    // ------------------------------------------------------------------ HST-05

    @Unroll
    def 'swap: #sample is #want'() {
        when:
        HealthChecker.Result r = only(one("@@host_health\n${HEALTH}\n@@host_sample\n${sample}".toString()), 'HST-05')

        then:
        r.status == want

        where:
        sample                  | want
        'SWAP_SAMPLE=0|0'       | Status.PASS
        'SWAP_SAMPLE=49|50'     | Status.PASS
        'SWAP_SAMPLE=60|40'     | Status.WARN
        'SWAP_SAMPLE=0|500'     | Status.WARN
        'SWAP_SAMPLE=|'         | Status.NOT_CHECKED
        ''                      | Status.NOT_CHECKED
    }

    def 'swap merely in use is not read at all: only the counters count'() {
        expect:
        probe().contains('pswpin')
        !probe().contains('SwapFree')
        !probe().contains('swapon')
    }

    // ------------------------------------------------------------------ HST-06, HST-07

    @Unroll
    def '#id at #v of the last minute is #want'() {
        when:
        HealthChecker.Result r = only(health("${key}=${v}\nOOM_READ=1\nVM_LOGS_READ=1"), id)

        then:
        r.status == want

        where:
        id       | key          | v       | want
        'HST-06' | 'PSI_CPU'    | '0.00'  | Status.PASS
        'HST-06' | 'PSI_CPU'    | '24.99' | Status.PASS
        'HST-06' | 'PSI_CPU'    | '25.00' | Status.WARN
        'HST-06' | 'PSI_CPU'    | '60.00' | Status.FAIL
        'HST-07' | 'PSI_MEMORY' | '4.99'  | Status.PASS
        'HST-07' | 'PSI_MEMORY' | '5.00'  | Status.WARN
        'HST-07' | 'PSI_MEMORY' | '20.00' | Status.FAIL
    }

    @Unroll
    def 'no pressure file means not checked, never a pass: #line'() {
        expect:
        only(health("${line}\nOOM_READ=1\nVM_LOGS_READ=1"), 'HST-06').status == Status.NOT_CHECKED
        only(health("${line}\nOOM_READ=1\nVM_LOGS_READ=1"), 'HST-07').status == Status.NOT_CHECKED

        where:
        line << ['PSI_CPU=\nPSI_MEMORY=', 'PSI_CPU=some\nPSI_MEMORY=n/a', '']
    }

    def 'site standards move the limits'() {
        expect:
        only(health('PSI_CPU=10\nPSI_MEMORY=1\nOOM_READ=1\nVM_LOGS_READ=1', '{"cpuPressureWarnPct":5,"memPressureWarnPct":1,"memPressureFailPct":1}'), 'HST-06').status == Status.WARN
        only(health('PSI_CPU=10\nPSI_MEMORY=1\nOOM_READ=1\nVM_LOGS_READ=1', '{"cpuPressureWarnPct":5,"memPressureWarnPct":1,"memPressureFailPct":1}'), 'HST-07').status == Status.FAIL
    }

    def 'CPU steal is shown in HST-06, computed over the sample, and pointed at when it is why'() {
        when:
        HealthChecker.Result r = only(one("@@host_health\nPSI_CPU=30\nOOM_READ=1\nVM_LOGS_READ=1\n@@host_sample\nCPU_SAMPLE=1000|120"), 'HST-06')

        then:
        r.observed.contains('CPU steal 12.0%')
        r.hint.contains('whatever runs this host')
    }

    def 'unread steal says so, and is not shown as none, and no ticks is not a division by zero'() {
        expect:
        only(one('@@host_sample\n@@host_health\nPSI_CPU=1\nOOM_READ=1\nVM_LOGS_READ=1'), 'HST-06').observed.contains('CPU steal not read')
        HostFacts.parse('@@host_sample\nCPU_SAMPLE=0|0').stealPct == null
        HostFacts.parse('@@meta\nNOW=1').stealPct == null
    }

    // ------------------------------------------------------------------ HST-08

    @Unroll
    def 'root disk #lines is #want'() {
        when:
        HealthChecker.Result r = only(health("OOM_READ=1\nVM_LOGS_READ=1\n${lines}"), 'HST-08')

        then:
        r.status == want

        where:
        lines                          | want
        'ROOT_DF=/|40|12'              | Status.PASS
        'ROOT_DF=/|84|84'              | Status.PASS
        'ROOT_DF=/|85|12'              | Status.WARN
        'ROOT_DF=/|40|85'              | Status.WARN
        'ROOT_DF=/|40|12\nROOT_DF=/var|95|12' | Status.FAIL
        'ROOT_DF=/|40|99'              | Status.FAIL
        'ROOT_DF=/|40|-'               | Status.PASS
        'ROOT_DF=/|-|-'                | Status.NOT_CHECKED
        ''                             | Status.NOT_CHECKED
        'ROOT_DF_CUT=1'                | Status.NOT_CHECKED
        'ROOT_DF=/|40|12\nROOT_DF_CUT=1' | Status.NOT_CHECKED
        'ROOT_DF=/|96|12\nROOT_DF_CUT=1' | Status.FAIL
    }

    def 'it says which path, and no filesystem with no inode limit reads as 0'() {
        when:
        HealthChecker.Result r = only(health('OOM_READ=1\nVM_LOGS_READ=1\nROOT_DF=/var|91|-'), 'HST-08')

        then:
        r.observed == '/var: 91% space, no inode limit'
    }

    def 'a bare df cannot run: every df in the probe names a path, and only the GFS2 one is a mount, behind FS_SAFE'() {
        given:
        List<String> code = probe().readLines().findAll { !it.trim().startsWith('#') }
        List<String> calls = code.collectMany { it.findAll(/\bdf\b[^|;)"]*/) }

        expect:
        calls.size() == 2
        calls.any { it.contains('--output=source,target,pcent,ipcent / /var /var/log /tmp /boot') }
        calls.any { it.contains('--output=size,used') }
        !code.any { it.contains('-x gfs2') || it.contains('df -x') }
        probe().indexOf('if [ "$FS_SAFE" = 1 ]; then\n  gfs_read()') > 0
        probe().indexOf('gfs_read()') < probe().indexOf('df -B1')
    }

    // ------------------------------------------------------------------ VM-06

    @Unroll
    def 'VM-06: #why is #want'() {
        when:
        HealthChecker.Result r = only(health(lines), 'VM-06')

        then:
        r.status == want

        where:
        why                             | lines                                                                       | want
        'nothing'                       | 'OOM_READ=1\nVM_LOGS_READ=1'                                                | Status.PASS
        'a crash today'                 | 'OOM_READ=1\nVM_LOGS_READ=1\nVM_CRASH=vm-one|2026-10-31 00:00:00.123+0000: shutting down, reason=crashed' | Status.WARN
        'a crash 2 days ago in a log touched today' | 'OOM_READ=1\nVM_LOGS_READ=1\nVM_CRASH=vm-one|2026-10-29 00:00:00.123+0000: shutting down, reason=crashed' | Status.PASS
        'a crash with no readable time' | 'OOM_READ=1\nVM_LOGS_READ=1\nVM_CRASH=vm-one|shutting down, reason=crashed'  | Status.NOT_CHECKED
        'logs unread'                   | 'OOM_READ=1'                                                                | Status.NOT_CHECKED
        'kernel log unread'             | 'VM_LOGS_READ=1'                                                            | Status.NOT_CHECKED
        'logs unread, but a qemu OOM'   | "OOM_READ=1\nOOM=${RECENT}|qemu-system-x86|"                                | Status.WARN
        'kernel log unread, but a crash'| 'VM_LOGS_READ=1\nVM_CRASH=vm-one|2026-10-31 00:00:00.123+0000: shutting down, reason=crashed' | Status.WARN
        'a daemon OOM'                  | "OOM_READ=1\nVM_LOGS_READ=1\nOOM=${RECENT}|java|"                           | Status.PASS
    }

    def 'it names the VM by its Morpheus name, and says how it stopped'() {
        given:
        List<Map> vms = [[name: 'i-1001', label: 'web-1', host: 'hvm-node01', tenant: 'Tenant A', power: 'off'],
                         [name: 'i-1002', label: 'db-1', host: 'hvm-node01', tenant: 'Tenant B', power: 'off']]

        when:
        HealthChecker.Result r = only(health("OOM_READ=1\nVM_LOGS_READ=1\nVM_CRASH=i-1001|2026-10-31 00:00:00.123+0000: shutting down, reason=crashed\nOOM=${RECENT}|qemu-system-x86|machine-qemu\\x2d5\\x2di\\x2d1002.scope", null, vms), 'VM-06')

        then:
        r.observed.contains('web-1 crashed at 2026-10-31 00:00 UTC')
        r.observed.contains('db-1 killed for lack of memory')
        !r.observed.contains('Tenant')
    }

    def 'the kernel saying a VM\'s cgroup is the same VM as its crash line: one VM, one mention'() {
        when:
        HealthChecker.Result r = only(health("OOM_READ=1\nVM_LOGS_READ=1\nVM_CRASH=vm-one|2026-10-31 00:00:00.123+0000: shutting down, reason=crashed\nOOM=${RECENT}|qemu-system-x86|machine-qemu\\x2d5\\x2dvm\\x2done.scope"), 'VM-06')

        then:
        r.observed.count('vm-one') == 1
        r.observed.contains('killed for lack of memory')
    }

    def 'a qemu kill with no cgroup is still a finding, unnamed'() {
        when:
        HealthChecker.Result r = only(health("OOM_READ=1\nVM_LOGS_READ=1\nOOM=${RECENT}|qemu-system-x86|"), 'VM-06')

        then:
        r.status == Status.WARN
        r.observed.contains('did not name')
    }

    def 'a long list is cut, and says so'() {
        given:
        String lines = (1..12).collect { "VM_CRASH=vm-${it}|2026-10-31 00:00:00.123+0000: shutting down, reason=crashed" }.join('\n')

        when:
        HealthChecker.Result r = only(health("OOM_READ=1\nVM_LOGS_READ=1\n${lines}"), 'VM-06')

        then:
        r.observed.endsWith('and 4 more')
    }

    def 'a report withholds the VMs VM-06 names, whatever Morpheus knows of them'() {
        given:
        HealthChecker.Result f = only(health('OOM_READ=1\nVM_LOGS_READ=1\nVM_CRASH=unknown-vm|2026-10-31 00:00:00.123+0000: shutting down, reason=crashed'), 'VM-06')
        Map last = [overall: 'WARNING', at: 1L, mode: 'quick', counts: [:], readiness: [], hosts: [],
                    results: [[id: f.id, title: f.title, severity: f.severity.name(), status: f.status.name(), host: f.host, observed: f.observed, expected: f.expected, source: f.source]]]

        when:
        String json = JsonOutput.toJson(HealthReport.build([id: 1L, name: 'cluster-x'], last, null, null, [], 2L))

        then:
        !json.contains('unknown-vm')
        json.contains('withheld')
    }

    def 'history keeps no VM name from these: their titles hold none'() {
        given:
        HealthChecker.Result f = only(health('OOM_READ=1\nVM_LOGS_READ=1\nVM_CRASH=vm-one|2026-10-31 00:00:00.123+0000: shutting down, reason=crashed'), 'VM-06')
        Map doc = FindingHistory.empty(1L)

        when:
        FindingHistory.apply(doc, [f], [hosts: ['hvm-node01'] as Set, answered: ['hvm-node01'] as Set, fullKnown: true], [overall: 'WARNING'], 1L)

        then:
        !JsonOutput.toJson(doc).contains('vm-one')
    }

    // ------------------------------------------------------------------ parsing

    def 'the kernel\'s spelling of a VM\'s cgroup comes back as the VM'() {
        expect:
        HostFacts.scopeVm(scope) == want

        where:
        scope                                     | want
        'machine-qemu\\x2d5\\x2dvm\\x2done.scope' | 'vm-one'
        'machine-qemu\\x2d12\\x2dplain.scope'     | 'plain'
        'machine-lxc.scope'                       | null
        ''                                        | null
        null                                      | null
    }

    def 'libvirt\'s log time is read in its own offset'() {
        expect:
        HostFacts.crashSeconds(line) == want

        where:
        line                                                           | want
        '2026-10-31 00:00:00.123+0000: shutting down, reason=crashed'  | 1793404800L
        '2026-10-31 02:00:00.123+0200: shutting down, reason=crashed'  | 1793404800L
        '2026-10-31 00:00:00+0000: shutting down'                      | 1793404800L
        'shutting down, reason=crashed'                                | null
        ''                                                             | null
        null                                                           | null
    }

    def 'an old GFS2 error line is read the same way after the shared parser moved'() {
        expect:
        HostFacts.parse('@@gfs2\nGFS2_ERROR_LAST=2026-10-31T00:00:00+0000 host kernel: x').gfsErrorAt == 1793404800L
        HostFacts.parse('@@gfs2\nGFS2_ERROR_LAST=nonsense').gfsErrorAt == null
    }

    // ------------------------------------------------------------------ busiest VMs

    /** Two samples 2000 ms apart, on the fixture's 56 threads: vm|cpu ns|read reqs|read bytes|write reqs|write bytes. */
    private static String sampled(Map<String, List<Long>> perVm) {
        (['@@busiest', 'BUSY_MS=2000'] + perVm.collect { String vm, List<Long> d -> "BUSY_A=${vm}|1000|10|1000|20|2000" } +
                perVm.collect { String vm, List<Long> d -> "BUSY_B=${vm}|${1000 + d[0]}|${10 + d[1]}|${1000 + d[2]}|${20 + d[3]}|${2000 + d[4]}" }).join('\n')
    }

    def 'rates come from the difference over the measured time, and CPU is of the whole host'() {
        when:
        Map a = HostFacts.parse("@@meta\nNPROC=4\n${sampled('vm-one': [4_000_000_000L, 200L, 4_000_000L, 100L, 2_000_000L])}").vmActivity[0]

        then:
        a.vm == 'vm-one'
        a.cpuPct == 50.0
        a.readIops == 100
        a.writeIops == 50
        a.readMBs == 2.0
        a.writeMBs == 1.0
    }

    def 'with half a sample, or a VM restarted between them, there is no rate rather than a wrong one'() {
        expect:
        HostFacts.parse(text).vmActivity == []

        where:
        text << ['@@busiest',
                 '@@busiest\nBUSY_MS=2000\nBUSY_A=vm|1|1|1|1|1',
                 '@@busiest\nBUSY_MS=2000\nBUSY_B=vm|1|1|1|1|1',
                 '@@busiest\nBUSY_A=vm|1|1|1|1|1\nBUSY_B=vm|2|2|2|2|2',
                 '@@busiest\nBUSY_MS=0\nBUSY_A=vm|1|1|1|1|1\nBUSY_B=vm|2|2|2|2|2',
                 '@@busiest\nBUSY_MS=2000\nBUSY_A=vm|9|1|1|1|1\nBUSY_B=vm|2|2|2|2|2',
                 '@@busiest\nBUSY_MS=2000\nBUSY_A=vm|x|1|1|1|1\nBUSY_B=vm|2|2|2|2|2',
                 '@@busiest\nBUSY_MS=2000\nBUSY_A=other|1|1|1|1|1\nBUSY_B=vm|2|2|2|2|2']
    }

    def 'the view has the top five per host by share of I/O and CPU, with the Morpheus name and tenant'() {
        given:
        Map<String, List<Long>> vms = (1..7).collectEntries { int n -> [("i-${n}".toString()): [0L, n * 100L, 0L, 0L, 0L]] }
        vms['i-cpu'] = [40_000_000_000L, 0L, 0L, 0L, 0L]
        HealthChecker.Input i = one(sampled(vms), null, [[name: 'i-7', label: 'web-7', host: 'hvm-node01', tenant: 'Tenant A'],
                                                         [name: 'i-cpu', label: 'batch', host: 'hvm-node01', tenant: 'Tenant B']])

        when:
        List<Map> rows = HealthChecker.busiest(i)

        then:
        rows.size() == 5
        rows*.host.unique() == ['hvm-node01']
        rows[0].vm == 'batch'
        rows[0].tenant == 'Tenant B'
        rows[0].keySet() == ['host', 'vm', 'tenant', 'cpuPct', 'readIops', 'writeIops', 'readMBs', 'writeMBs'] as Set
        rows[1].vm == 'web-7'
        rows[1].tenant == 'Tenant A'
        rows[1].readIops == 350
        rows[2].vm == 'i-6'
        rows[2].tenant == null
        !rows*.vm.contains('i-1')
    }

    def 'idle VMs are not busy, and a frozen or silent host adds nothing'() {
        given:
        HealthChecker.Input idle = one(sampled('i-1': [0L, 0L, 0L, 0L, 0L]))
        HealthChecker.Input frozen = Fixture.frozen()
        HealthChecker.Input silent = Fixture.input([Fixture.host('hvm-node01', new HostFacts())], Fixture.LAYOUT_20)

        expect:
        HealthChecker.busiest(idle) == []
        HealthChecker.busiest(frozen) == []
        HealthChecker.busiest(silent) == []
    }

    def 'the tab gets it in the view'() {
        given:
        HealthChecker.Input i = one(sampled('i-1': [1_000_000_000L, 100L, 0L, 0L, 0L]), null, [[name: 'i-1', label: 'web-1', host: 'hvm-node01', tenant: 'Tenant A']])

        when:
        Map view = HealthContext.view(i, new HealthChecker().check(i), 'full')

        then:
        view.busiest*.vm == ['web-1']
        view.busiest*.tenant == ['Tenant A']
    }

    def 'neither a report nor the stored summary carries it, tenant names least of all'() {
        given:
        HealthChecker.Input i = one(sampled('i-secret': [1_000_000_000L, 100L, 0L, 0L, 0L]), null, [[name: 'i-secret', label: 'secret-vm', host: 'hvm-node01', tenant: 'Secret Tenant']])
        i.hosts[0].facts.storageStats << [wwid: '3624a', reads: 100L, readMs: 5000L, writes: 0L, writeMs: 0L, inflight: 0L]
        Map view = HealthContext.view(i, new HealthChecker().check(i), 'full')

        when:
        String report = JsonOutput.toJson(HealthReport.build([id: 1L, name: 'cluster-x'], view, null, null, [], 2L))
        String summary = JsonOutput.toJson(FindingHistory.summaryOf(view))

        then: 'the view itself has it, or this test proves nothing'
        JsonOutput.toJson(view).contains('secret-vm')
        JsonOutput.toJson(view.busiest).contains('Secret Tenant')

        and:
        !report.contains('secret-vm')
        !report.contains('i-secret')
        !report.contains('Secret Tenant')
        !summary.contains('secret-vm')
        !summary.contains('Secret Tenant')
    }

    def 'STO-06\'s hint names the VM doing the most I/O, by its Morpheus name, and never its tenant'() {
        given:
        HealthChecker.Input i = one(sampled('i-1': [0L, 100L, 0L, 0L, 0L], 'i-2': [0L, 400L, 0L, 200L, 0L]) + "\n@@storage\nSTAT=3624a|100|5000|0|0|0", null,
                [[name: 'i-2', label: 'db-1', host: 'hvm-node01', tenant: 'Tenant B']])

        when:
        HealthChecker.Result r = ids(i, 'STO-06')[0]

        then:
        r.status == Status.WARN
        r.hint.contains('Busiest VM on this host: db-1, 300 IOPS')
        r.hint.startsWith('Check the other hosts')
        !r.hint.contains('Tenant')
    }

    def 'with no sample STO-06 keeps its plain hint, and a passing one has none'() {
        expect:
        ids(one('@@storage\nSTAT=3624a|100|5000|0|0|0'), 'STO-06')[0].hint == 'Check the other hosts. If they are slow too, look at the array; if only this one is, its paths and NICs.'
        ids(one(sampled('i-1': [0L, 100L, 0L, 0L, 0L]) + '\n@@storage\nSTAT=3624a|100|100|0|0|0'), 'STO-06')[0].hint == null
    }

    // ------------------------------------------------------------------ the probe's text, as it cannot run here

    private static String between(String from, String to) {
        String p = probe()
        p.substring(p.indexOf(from), p.indexOf(to, p.indexOf(from)))
    }

    def 'ports are read on a quick run, out of the full-only block, and left alone in a freeze'() {
        given:
        String p = probe()
        String ports = between('sec vm_ports', 'sec busiest')

        expect:
        p.contains('\nsec vm_ports\n')
        p.lastIndexOf('if [ "$MODE" = full ]; then\n  sec journals') < p.indexOf('\nfi\n\nsec vm_ports')
        ports.contains('[ "$MODE" != full ] && [ -n "$GFS2_TARGETS" ] && [ "$FS_SAFE" != 1 ]')
        ports.contains('ports_end=$((SECONDS + 12))')
    }

    def 'a port read that failed is said to have failed, never read as a host with no ports'() {
        given:
        String ports = between('sec vm_ports', 'sec busiest')

        expect:
        ports.contains('elif VM_NAMES="$(t 15 virsh -q list --name)"; then')
        ports.count("echo 'PORTS_CUT=1'") == 4
        ports.contains('ifl="$(t 5 virsh domiflist "$d")" || { echo \'PORTS_CUT=1\'; continue; }')
    }

    def 'the busiest read runs in a job, and only with DLM healthy, on a quick run only when I/O is waiting'() {
        given:
        String busy = between('sec busiest', 'sec end')

        expect:
        busy.contains('{ [ -z "$GFS2_TARGETS" ] || [ "$FS_SAFE" = 1 ]; } && { [ "$MODE" = full ] || [ "$HOT" = 1 ]; }')
        busy.contains('BUSY1="$(bounded 7 vm_stats)"')
        busy.contains('BUSY2="$(bounded 7 vm_stats)"')
        busy.contains('virsh domstats --cpu-total --block')
        // disk paths stay on the host
        !busy.contains('.path')
        busy.indexOf('BUSY_MS') > busy.indexOf('BUSY2=')
    }

    def 'nothing it adds writes to the host, or reads a datastore path'() {
        given:
        String added = between('sec host_health', 'if [ "$MODE" = full ]; then\n  sec journals') + between('sec vm_ports', 'sec end')

        expect:
        !(added =~ /(?m)(?<![0-9&])>\s*\/(?!dev\/null)/)
        !added.contains('/mnt')
        !added.contains('$GFS2_TARGETS"; do')
        !added.contains('mvm-hb')
        !added.contains('rm ')
        !added.contains('virsh destroy')
    }

    def 'DLM still comes first, and the new sections after the ones a freeze needs'() {
        given:
        String p = probe()

        expect:
        p.indexOf('sec dlm_ls') < p.indexOf('sec host_health')
        p.indexOf('sec gfs2') < p.indexOf('sec host_health')
        p.indexOf('sec host_health') < p.indexOf('sec vm_ports')
        p.indexOf('sec vm_ports') < p.indexOf('sec busiest')
        p.indexOf('sec busiest') < p.indexOf('sec end')
    }

    /**
     * What is left of a script once quoted text and comments are gone, or null if a quote or a bracket never closes. Far less than bash -n, which ScriptsSpec runs
     * where there is bash, but the development workstation has none, and a stray apostrophe in an awk program breaks every probe on every host.
     */
    private static String structure(String script) {
        StringBuilder code = new StringBuilder()
        List<String> open = []      // what we are inside: " or $( or (
        int i = 0
        int n = script.length()
        while (i < n) {
            String c = script[i]
            if (open && open.last() == '"') {
                if (c == '\\') { i += 2; continue }
                if (c == '"') { open.removeLast() } else if (c == '$' && i + 1 < n && script[i + 1] == '(') { open << '('; i++ }
                i++
                continue
            }
            if (c == '\\') { i += 2; continue }
            if (c == "'") {
                int end = script.indexOf("'", i + 1)
                if (end < 0) { return null }
                i = end + 1
                continue
            }
            if (c == '"') { open << '"'; i++; continue }
            if (c == '#' && (i == 0 || script[i - 1] in [' ', '\t', '\n', ';'])) {
                int end = script.indexOf('\n', i)
                i = end < 0 ? n : end
                continue
            }
            if (c == '(') { open << '(' }
            // a case pattern's ) closes nothing, at the top level where this script's one case is
            if (c == ')' && open) {
                if (open.last() != '(') { return null }
                open.removeLast()
            }
            code << c
            i++
        }
        open ? null : code.toString()
    }

    private static int words(String code, String word) {
        code.findAll(/(?<![\w-])${word}(?![\w-])/).size()
    }

    def 'the structure check itself sees what breaks a script'() {
        expect:
        structure(text) == want

        where:
        text                         | want
        'echo a # it\'s a comment'   | 'echo a '
        "awk '{print \$1}' f"        | 'awk  f'
        'x="$(a "b")"; y'            | 'x=a ); y'
        "awk '{print"                | null
        'x="abc'                     | null
        'x="$(a'                     | null
        'x=$(a'                      | null
    }

    def 'the probe closes every quote and bracket, and every if, loop and case, in both modes'() {
        given:
        // ping -M do is the one "do" that opens nothing
        String code = structure(HealthContext.probeScript(mode))?.replace('-M do', '')

        expect:
        code != null
        words(code, 'if') == words(code, 'fi')
        words(code, 'do') == words(code, 'done')
        words(code, 'case') == words(code, 'esac')
        words(code, 'if') > 10

        where:
        mode << ['quick', 'full']
    }
}
