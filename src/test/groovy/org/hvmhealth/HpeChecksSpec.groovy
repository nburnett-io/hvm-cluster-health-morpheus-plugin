package org.hvmhealth

import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.MorpheusServices
import com.morpheusdata.model.ComputeServer
import com.morpheusdata.model.ComputeServerGroup
import com.morpheusdata.model.ComputeServerGroupType
import groovy.json.JsonOutput
import org.hvmhealth.checks.CheckCatalog
import org.hvmhealth.checks.HealthChecker
import org.hvmhealth.checks.HealthChecker.Status
import org.hvmhealth.model.HostFacts
import org.hvmhealth.morpheus.HealthContext
import org.hvmhealth.morpheus.HealthMonitor
import org.hvmhealth.morpheus.HealthReport
import org.hvmhealth.standards.Layout
import org.hvmhealth.standards.Standards
import spock.lang.Specification

/**
 * HPE's own check (HPE-01) and disks behind a RAID controller (HW-07). Probe lines follow what a real 2.0 host printed, with names, models and values
 * replaced. ScriptsSpec holds the cases that run the probe itself, where there is bash.
 */
class HpeChecksSpec extends Specification {
    private static final List<String> PACEMAKER = ['Cluster status', 'Cluster nodes online', 'Cluster failed resources', 'Cluster STONITH']
    private static final String PRIVATE_DETAIL = 'Cluster nodes not online: secret-node.invalid(offline), vm-private-one'

    private static Map row(String check, String status, String details = 'ok', String type = 'OS') {
        [check: check, type: type, status: status, details: details]
    }

    /** The healthy cluster's shape: four Pacemaker warnings, and the rest passing. */
    private static List<Map> masked() {
        [row('NTP configuration', 'PASS'), row('Cluster quorum', 'PASS', 'Cluster quorum is present.', 'Cluster'), row('Cluster status', 'WARN', 'Cluster health requires attention.', 'Cluster'),
         row('Cluster nodes online', 'WARN', PRIVATE_DETAIL, 'Cluster'), row('Cluster failed resources', 'WARN', 'Cluster has failed resource or fencing actions.', 'Cluster'), row('Cluster STONITH', 'WARN', 'Unable to determine STONITH configuration.', 'Cluster')]
    }

    private static String json(List<Map> checks) {
        JsonOutput.toJson([errorCode: 0, messages: '', data: [summary: [pass: 1, warn: 1, fail: 0, na: 0], checks: checks]])
    }

    private static String read(List<Map> checks) { read(json(checks)) }

    private static String read(String json) { "@@hvmcli_health\nHVMCLI_STATE=read\nHVMCLI_JSON=${json}".toString() }

    private static Closure<String> plus(String extra) {
        return { String t -> t.replace('@@end', extra + '\n@@end') }
    }

    private static HealthChecker.Input cluster(String sample, String layout, String extra, String site = null) {
        Fixture.input((1..3).collect { int n ->
            String name = "hvm-node0${n}".toString()
            Fixture.host(name, Fixture.facts(sample, name, plus(extra)))
        }, layout, site)
    }

    private static HealthChecker.Input cluster20(String extra, String site = null) { cluster('probe-healthy', Fixture.LAYOUT_20, extra, site) }

    private static List<HealthChecker.Result> hpe(HealthChecker.Input i) { new HealthChecker().check(i).findAll { it.id == 'HPE-01' } }

    // ------------------------------------------------------------------ what raises it

    def 'Pacemaker\'s warnings, which HVM masks, raise nothing'() {
        when:
        List<HealthChecker.Result> rs = hpe(cluster20(read(masked())))

        then:
        rs.size() == 3
        rs*.status.every { it == Status.PASS }
        rs[0].observed.contains('6 checks read')
    }

    def 'the default ignored list is exactly the four that read Pacemaker'() {
        expect:
        Standards.forLayout(Layout.of(Fixture.LAYOUT_20)).val('hvmcliIgnore') == PACEMAKER
    }

    def 'a real warning raises it, naming the check, and a failure of HPE\'s is still only a warning'() {
        when:
        List<HealthChecker.Result> rs = hpe(cluster20(read(masked() + row('Kernel errors', 'WARN', 'Found 72 critical errors.') + row('Multipath path state', 'FAIL', 'A path failed.', 'Storage'))))

        then:
        rs*.status.every { it == Status.WARN }
        rs[0].observed.contains('FAIL Multipath path state')
        rs[0].observed.contains('WARN Kernel errors')
        rs[0].observed.indexOf('FAIL') < rs[0].observed.indexOf('WARN')
        !rs[0].observed.contains('Cluster status')
        rs[0].hint
        new HealthChecker().check(cluster20(read(masked() + row('Multipath path state', 'FAIL')))).every { it.id != 'HPE-01' || it.status != Status.FAIL }
    }

    def 'a long list of raised checks stays short in the finding'() {
        when:
        HealthChecker.Result r = hpe(cluster20(read((1..30).collect { row("Check ${it}".toString(), 'WARN') })))[0]

        then:
        r.observed.contains('and 22 more')
    }

    def 'a site list replaces the default, by name and in any case'() {
        given:
        String extra = read(masked() + row('Kernel errors', 'WARN'))

        expect:
        hpe(cluster20(extra, '{"hvmcliIgnore":["kernel ERRORS","Cluster status","Cluster nodes online","Cluster failed resources","Cluster STONITH"]}'))*.status.every { it == Status.PASS }
        hpe(cluster20(read(masked()), '{"hvmcliIgnore":[]}'))[0].observed.contains('WARN Cluster status')
        Standards.forLayout(Layout.of(Fixture.LAYOUT_20), '{"hvmcliIgnore":["Kernel errors"]}').source('hvmcliIgnore') == 'site'
    }

    def 'an ignored list of the wrong type is refused by name'() {
        when:
        Standards.forLayout(Layout.of(Fixture.LAYOUT_20), json)

        then:
        IllegalArgumentException e = thrown()
        e.message.contains('hvmcliIgnore')

        where:
        json << ['{"hvmcliIgnore":"Kernel errors"}', '{"hvmcliIgnore":[1]}', '{"hvmcliIgnore":null}']
    }

    // ------------------------------------------------------------------ what it could not read

    def 'output that will not parse is not checked, never a pass, and does not stop the host being read'() {
        when:
        HealthChecker.Input i = cluster20(read(output as String))
        List<HealthChecker.Result> rs = hpe(i)

        then:
        i.hosts.every { it.facts.complete }
        rs*.status.every { it == Status.NOT_CHECKED }
        rs[0].observed.contains('reads as its JSON')

        where:
        output << ['{"errorCode" : 0, "data" : {"checks" : [ {', 'not json at all', '', '[]', '42', '{"errorCode":0,"data":"none"}', '{"errorCode":0,"data":{"checks":"none"}}',
                   '{"errorCode":0,"data":{"checks":[]}}', '{"errorCode":0,"data":{"checks":[1,"two",null]}}', '{"errorCode":3,"messages":"failed","data":{"checks":[{"check":"x","status":"PASS"}]}}',
                   '['.repeat(40000)]
    }

    def 'a probe that printed no JSON line at all is not checked'() {
        expect:
        hpe(cluster20('@@hvmcli_health\nHVMCLI_STATE=read'))*.status.every { it == Status.NOT_CHECKED }
    }

    def 'a 2.0 host with no hvmcli is not checked, and a 1.3 host has no finding at all'() {
        when:
        List<HealthChecker.Result> two = hpe(cluster20('@@hvmcli_health\nHVMCLI_STATE=missing'))
        HealthChecker.Input i13 = cluster('probe-timesyncd', Fixture.LAYOUT_13, '@@hvmcli_health\nHVMCLI_STATE=missing')

        then:
        two*.status.every { it == Status.NOT_CHECKED }
        two[0].observed.contains('no hvmcli')
        hpe(i13) == []
        HealthChecker.hpeView(i13) == null
    }

    def 'a host DLM does not call safe is skipped, and that is said rather than passed'() {
        when:
        HealthChecker.Result r = hpe(cluster20('@@hvmcli_health\nHVMCLI_STATE=unsafe'))[0]

        then:
        r.status == Status.NOT_CHECKED
        r.observed.contains('DLM')
        r.observed.contains('GFS2')
    }

    def 'running out of time or an answer that never came are not checked either'() {
        expect:
        hpe(cluster20("@@hvmcli_health\nHVMCLI_STATE=${state}"))[0].status == Status.NOT_CHECKED
        hpe(cluster20("@@hvmcli_health\nHVMCLI_STATE=${state}"))[0].observed.contains(says)

        where:
        state     | says
        'timeout' | '30 s'
        'no_time' | 'time limit'
        'other'   | 'cannot tell'
    }

    def 'a frozen host is skipped: no read, no pass, no new alarm, and an open one keeps its identity'() {
        given:
        HostFacts frozen = Fixture.facts('probe-frozen', 'hvm-node01') { String t -> plus('@@hvmcli_health\nHVMCLI_STATE=unsafe').call(t.replace('MODE=quick', 'MODE=full')) }
        HealthChecker.Input i = Fixture.input([Fixture.host('hvm-node01', frozen)], Fixture.LAYOUT_20)
        HealthChecker.Result warned = hpe(cluster20(read(masked() + row('Kernel errors', 'WARN'))))[0]

        when:
        HealthChecker.Result skipped = hpe(i)[0]

        then:
        frozen.frozen
        !frozen.hvmcliChecks
        skipped.status == Status.NOT_CHECKED
        skipped.observed.startsWith('not run')
        HealthChecker.hpeView(i)[0].checks == []
        // one identity whatever the answer, as an alarm stays open only while its finding could not be judged
        warned.title == skipped.title
        HealthMonitor.alarmsFor(27L, 'cluster-x', [skipped]) == []
    }

    def 'a quick probe adds no result, and it keeps its last answer between full checks'() {
        given:
        String quick = read(masked() + row('Kernel errors', 'WARN'))
        HealthChecker.Input i = cluster('probe-healthy', Fixture.LAYOUT_20, quick)
        i.hosts.each { it.facts.mode = 'quick' }

        expect:
        hpe(i) == []
        'HPE-01' in HealthMonitor.FULL_ONLY
        CheckCatalog.ENTRIES['HPE-01'].what.contains('read-only is what could be seen, not what was proven')
    }

    // ------------------------------------------------------------------ the panel

    def 'the panel marks Pacemaker\'s checks and what the site ignores, whatever the site lists'() {
        when:
        List<Map> v = HealthChecker.hpeView(cluster20(read(masked() + row('Kernel errors', 'WARN')), '{"hvmcliIgnore":["Kernel errors"]}'))
        Map<String, Map> rows = v[0].checks.collectEntries { [(it.check): it] }

        then:
        v.size() == 3
        rows['Cluster STONITH'].pacemaker == true
        rows['Cluster STONITH'].ignored == false
        rows['Kernel errors'].pacemaker == false
        rows['Kernel errors'].ignored == true
        rows['NTP configuration'].pacemaker == false
    }

    def 'a quick check keeps the last full check\'s panel, and a full check replaces it'() {
        given:
        ComputeServerGroup record = new ComputeServerGroup(id: 27L, name: 'cluster-x', type: new ComputeServerGroupType(code: 'mvm-cluster'))
        def clusters = Stub(MorpheusServices.getMethod('getCluster').returnType) { get(_) >> record; list(_) >> [record] }
        def store = new HistorySpec.Store().service()
        def services = Stub(MorpheusServices) { getOperationData() >> store; getCluster() >> clusters }
        MorpheusContext m = Stub(MorpheusContext) { getServices() >> services }
        PanelContext ctx = new PanelContext(m)

        when:
        ctx.next = cluster20(read(masked()))
        List<Map> first = HealthMonitor.run(m, ctx, [:], 'full', false).hpeChecks

        then:
        first.size() == 3

        when:
        ctx.next = cluster('probe-healthy', Fixture.LAYOUT_20, '')
        ctx.next.hosts.each { it.facts.mode = 'quick' }
        Map quick = HealthMonitor.run(m, ctx, [:], 'quick', false)

        then:
        quick.hpeChecks == first
        quick.results.count { it.id == 'HPE-01' } == 3

        when:
        ctx.next = cluster20('@@hvmcli_health\nHVMCLI_STATE=unsafe')
        Map full = HealthMonitor.run(m, ctx, [:], 'full', false)

        then:
        full.hpeChecks*.checks.every { it == [] }

        cleanup:
        HealthMonitor.LAST.remove(27L)
    }

    /** Serves the input a test sets, for {@code HealthMonitor.run}. */
    static class PanelContext extends MonitorSpec.FakeContext {
        HealthChecker.Input next

        PanelContext(MorpheusContext m) { super(m) }

        @Override Map<String, HostFacts> probe(String mode) { [:] }
        @Override HealthChecker.Input input(Map<String, HostFacts> facts, String site) { next.clusterId = 27L; next }
        @Override List<ComputeServer> getHosts() { [] }
    }

    def 'one host\'s odd output is bounded and costs only that host its answer'() {
        given:
        String huge = 'x' * 5000
        HealthChecker.Input i = Fixture.input([
                Fixture.host('hvm-node01', Fixture.facts('probe-healthy', 'hvm-node01', plus(read([[check: 'a' * 500, type: 't' * 500, status: 'warn', details: huge], [check: 'bare'], [check: 7, status: 9, details: [nested: true]], 'not a map'] as List<Map>)))),
                Fixture.host('hvm-node02', Fixture.facts('probe-healthy', 'hvm-node02', plus(read('{broken')))),
                Fixture.host('hvm-node03', Fixture.facts('probe-healthy', 'hvm-node03', plus(read(masked()))))], Fixture.LAYOUT_20)

        when:
        List<Map> v = HealthChecker.hpeView(i)
        List<HealthChecker.Result> rs = new HealthChecker().check(i)
        List<Map> first = v[0].checks

        then:
        first.size() == 3
        first[0].details.length() <= 203
        first[0].check.length() <= 83
        first[0].status == 'WARN'
        first[1].status == ''
        first[2].check == '7'
        !first[2].details.contains('\n')
        v[1].checks == []
        v[1].note.contains('reads as its JSON')
        v[2].checks.size() == masked().size()
        JsonOutput.toJson(v)
        rs.find { it.id == 'HPE-01' && it.host == 'hvm-node01' }.status == Status.WARN
        rs.find { it.id == 'HPE-01' && it.host == 'hvm-node02' }.status == Status.NOT_CHECKED
        rs.find { it.id == 'HPE-01' && it.host == 'hvm-node03' }.status == Status.PASS
    }

    def 'control characters in a detail do not survive, and a tab script takes only text'() {
        given:
        HostFacts f = Fixture.facts('probe-healthy', 'hvm-node01', plus(read([row('Kernel errors', 'WARN', 'one\ntwo\u0007three')])))
        String tab = new File('src/assets/javascripts/health-tab.js').getText('UTF-8')
        String panel = tab.substring(tab.indexOf('function hpeBad'), tab.indexOf('function renderHosts'))

        expect:
        f.hvmcliChecks[0].details == 'one two three'
        !panel.contains('innerHTML')
        panel.contains('reads Pacemaker, which HVM masks')
        panel.contains('catch (e)')
        panel.findAll(/'class': '([^']*)'/) { m, c -> c }.every { c -> c.tokenize().every { it.startsWith('hh-') } }
    }

    // ------------------------------------------------------------------ reports

    def 'HPE\'s details and the panel stay out of a report and an alarm, and the finding names only the checks'() {
        given:
        String detail = 'Found errors on secret-node.invalid for vm-private-one'
        HealthChecker.Input i = cluster20(read(masked() + row('Kernel errors', 'WARN', detail)))
        List<HealthChecker.Result> rs = new HealthChecker().check(i)
        Map view = HealthContext.view(i, rs, 'full', 1000L)
        view.hpeChecks = HealthChecker.hpeView(i)

        when:
        Map report = HealthReport.build([id: 27L, name: 'cluster-x', layout: 'HVM 2.0'], view, null, null, [], 1000L)
        String json = JsonOutput.toJson(report)
        String alarms = JsonOutput.toJson(HealthMonitor.alarmsFor(27L, 'cluster-x', rs))

        then: 'the tab has them'
        JsonOutput.toJson(view).contains('vm-private-one')

        and: 'nothing that leaves does'
        ['secret-node.invalid', 'vm-private-one', 'Found errors', 'hvmcliChecks', 'hpeChecks', 'hint'].every { !json.contains(it) }
        !report.csv.contains('vm-private-one')
        !alarms.contains('vm-private-one')
        !alarms.contains('secret-node.invalid')

        and: 'the finding itself is there'
        report.current.find { it.check == 'HPE-01' }.observed.contains('WARN Kernel errors')
        alarms.contains('WARN Kernel errors')
    }

    // ------------------------------------------------------------------ disks behind a RAID controller

    /** As the sample printed it for a physical SATA disk: a line saying status is not supported, then the health that a real result gives. */
    private static List<String> notSupported(String whenFailed = '-', long pending = 0) {
        ['Device Model:     EXAMPLE SSD 240',
         'SMART support is: Available - device has SMART capability.',
         'SMART Status not supported: ATA return descriptor not supported by controller firmware',
         'SMART overall-health self-assessment test result: PASSED',
         'Warning: This result is based on an Attribute check.',
         'ID# ATTRIBUTE_NAME          FLAG     VALUE WORST THRESH TYPE      UPDATED  WHEN_FAILED RAW_VALUE',
         '  1 Raw_Read_Error_Rate     0x002e   100   100   050    Old_age   Always       -       0',
         "  5 Reallocated_Sector_Ct   0x0033   100   100   010    Pre-fail  Always       ${whenFailed}       0".toString(),
         '194 Temperature_Celsius     0x0022   080   051   000    Old_age   Always       -       20 (Min/Max 12/49)',
         "197 Current_Pending_Sector  0x0032   100   100   000    Old_age   Always       -       ${pending}".toString(),
         '198 Offline_Uncorrectable   0x0032   100   100   000    Old_age   Always       -       0']
    }

    private static String raid(List<String> boot = [], Map<String, List<String>> disks = ['megaraid,0@6': notSupported()], int unread = 0) {
        ("@@smart\nSMARTCTL=/usr/sbin/smartctl\n" + boot.join('\n') + (boot ? '\n' : '') + '@@raid\n' +
                disks.collect { String k, List<String> v -> "RAID_DISK=${k}\n" + v.collect { "RAID_SMART=${k}|${it}" }.join('\n') }.join('\n') +
                "\nRAID_DISKS_UNREAD=${unread}").toString()
    }

    private static List<HealthChecker.Result> hw07(String extra, String sample = 'probe-healthy', String layout = Fixture.LAYOUT_20) {
        new HealthChecker().check(cluster(sample, layout, extra)).findAll { it.id == 'HW-07' && it.host == 'hvm-node01' }
    }

    def 'the "not supported" line is not taken for a failed health result, and the title names the slot, not a serial'() {
        when:
        List<HealthChecker.Result> rs = hw07(raid())
        HealthChecker.Result r = rs.find { it.title.contains('megaraid') }

        then:
        r.status == Status.PASS
        r.title == 'Disk healthy (SMART): megaraid,0 on bus 6'
        r.observed.contains('PASSED')
    }

    def 'health is the only thing read from the "not supported" line: with no real result it is not checked'() {
        when:
        HealthChecker.Result r = hw07(raid([], ['megaraid,1@6': ['Device Model:     EXAMPLE SSD 240', 'SMART Status not supported: ATA return descriptor not supported by controller firmware']])).find { it.title.contains('megaraid') }

        then:
        r.status == Status.NOT_CHECKED
        r.observed.contains('gave no SMART health')
    }

    def 'a disk behind the controller goes through the existing rules, one result each'() {
        when:
        List<HealthChecker.Result> rs = hw07(raid([], ['megaraid,0@6': notSupported(), 'megaraid,1@6': notSupported('-', 8), 'megaraid,2@6': ['Product:   EXAMPLE SAS', 'SMART Health Status: FAILURE PREDICTION THRESHOLD EXCEEDED']]))

        then:
        rs.find { it.title.contains('megaraid,0') }.status == Status.PASS
        rs.find { it.title.contains('megaraid,1') }.status == Status.WARN
        rs.find { it.title.contains('megaraid,1') }.observed.contains('8 bad sector')
        rs.find { it.title.contains('megaraid,2') }.status == Status.FAIL
    }

    def 'any WHEN_FAILED value but a dash fails, behind a controller and on a boot disk alike'() {
        when:
        HealthChecker.Result r = hw07(raid([], ['megaraid,0@6': notSupported(value)])).find { it.title.contains('megaraid') }

        then:
        r.status == Status.FAIL
        r.observed.contains("Reallocated_Sector_Ct (${value})")
        boot.title == 'Boot disk healthy (SMART): sda'
        boot.status == Status.FAIL
        boot.observed.contains("Reallocated_Sector_Ct (${value})")
        boot.hint.contains('Replace this disk')

        where:
        value << ['FAILING_NOW', 'In_the_past']
        boot = hw07("@@smart\nSMARTCTL=/usr/sbin/smartctl\nBOOT_DISK=sda\n" + notSupported(value).collect { "SMART=sda|${it}" }.join('\n'))[0]
    }

    def 'a dash in WHEN_FAILED is the normal answer, on a boot disk too'() {
        expect:
        hw07("@@smart\nSMARTCTL=/usr/sbin/smartctl\nBOOT_DISK=sda\n" + notSupported().collect { "SMART=sda|${it}" }.join('\n'))[0].status == Status.PASS
    }

    def 'a boot disk that hides SMART stays not checked, whatever else its lines say'() {
        when:
        HealthChecker.Result r = hw07("@@smart\nSMARTCTL=/usr/sbin/smartctl\nBOOT_DISK=sda\nSMART=sda|Device Model:     QEMU HARDDISK\nSMART=sda|  5 Reallocated_Sector_Ct   0x0033   100   100   010    Pre-fail  Always       In_the_past       0")[0]

        then:
        r.status == Status.NOT_CHECKED
    }

    def 'a model that looks like a volume is still a physical disk when read through its controller'() {
        expect:
        hw07(raid([], ['megaraid,0@6': ['Device Model:     EXAMPLE PERC SSD', 'SMART overall-health self-assessment test result: PASSED']])).find { it.title.contains('megaraid') }.status == Status.PASS
    }

    def 'a RAID volume says what was read instead, when its own controller\'s disks were read'() {
        when:
        HealthChecker.Result r = hw07(raid(['BOOT_DISK=sdb', 'BOOT_HOST=sdb|6', 'SMART=sdb|Device Model:     EXAMPLEBOOT VD', 'SMART=sdb|SMART support is: Unavailable - device lacks SMART capability.'],
                ['megaraid,0@6': notSupported(), 'megaraid,1@6': notSupported()])).find { it.title.contains('sdb') }

        then:
        r.status == Status.NOT_CHECKED
        r.observed.contains('were read instead: megaraid,0, megaraid,1')
        !r.hint.contains('Read the physical disks through their controller')
    }

    def 'a volume on another controller, such as a boot card, does not claim disks that are not its own'() {
        when:
        HealthChecker.Result r = hw07(raid(['BOOT_DISK=sdc', hostLine, 'SMART=sdc|Device Model:     EXAMPLEBOOT VD', 'SMART=sdc|SMART support is: Unavailable - device lacks SMART capability.'].findAll())).find { it.title.contains('sdc') }

        then:
        r.status == Status.NOT_CHECKED
        !r.observed.contains('read instead')
        r.hint.contains('Read the physical disks through their controller')

        where:
        hostLine << ['BOOT_HOST=sdc|2', 'BOOT_HOST=sdc|', null]
    }

    def 'disks over the limit are counted and said, and never read as nothing wrong'() {
        when:
        List<HealthChecker.Result> rs = hw07(raid([], (0..<24).collectEntries { ["megaraid,${it}@6".toString(), notSupported()] }, 6))
        HealthChecker.Result left = rs.find { it.title.contains('Physical disks') }

        then:
        rs.count { it.title.startsWith('Disk healthy') } == 24
        left.status == Status.NOT_CHECKED
        left.observed.contains('6 physical disk(s) not read')
    }

    def 'no smartctl means nothing behind the controller is claimed'() {
        when:
        List<HealthChecker.Result> rs = hw07('@@smart\nSMARTCTL=\nBOOT_DISK=sda\n@@raid\nRAID_DISK=megaraid,0@6\nRAID_DISKS_UNREAD=1')

        then:
        rs*.status == [Status.NOT_CHECKED]
        rs[0].observed.contains('smartctl not installed')
    }

    def 'the probe\'s filter keeps serials out, and a serial that got through by another name is not used in a title'() {
        given:
        String probe = HealthContext.getResourceAsStream('/scripts/health-probe.sh').getText('UTF-8')

        expect:
        probe.findAll(/grep -viE 'serial\|wwn\|logical unit id'/).size() == 2
        hw07(raid()).every { !it.title.contains('EXAMPLE SSD') }
    }

    def 'facts a probe did not gather stay empty'() {
        given:
        HostFacts f = HostFacts.parse(Fixture.text('probe-frozen'))

        expect:
        f.hvmcliState == null
        !f.hvmcliChecks && !f.raidDisks && f.raidDisksUnread == 0
    }
}
