package org.hvmhealth

import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.MorpheusServices
import com.morpheusdata.model.ComputeServer
import com.morpheusdata.model.ComputeServerGroup
import com.morpheusdata.model.ComputeServerGroupType
import com.morpheusdata.model.ComputeTypeLayout
import io.reactivex.rxjava3.core.Single
import org.hvmhealth.checks.HealthChecker
import org.hvmhealth.checks.HealthChecker.Tier
import org.hvmhealth.fixes.VlanTest
import org.hvmhealth.model.HostFacts
import org.hvmhealth.morpheus.HealthContext
import org.hvmhealth.morpheus.HealthMonitor
import org.hvmhealth.morpheus.HostOps
import spock.lang.Specification

class MonitorSpec extends Specification {
    static final long NOW = 1_800_000_000_000L
    static final long MIN = 60_000L

    def 'a first run is a full check; after that quick, with full on its own interval'() {
        expect:
        HealthMonitor.due([:], last, NOW) == want

        where:
        last                                                  | want
        null                                                  | 'full'
        [at: NOW - MIN, fullAt: NOW - MIN]                    | null
        [at: NOW - 6 * MIN, fullAt: NOW - 6 * MIN]            | 'quick'
        [at: NOW - 6 * MIN, fullAt: NOW - 25 * 60 * MIN]      | 'full'
    }

    def 'a quick interval of 0 turns automatic checks off'() {
        expect:
        HealthMonitor.due([monitorMinutes: '0'], null, NOW) == null
    }

    def 'only HVM layouts are monitored'() {
        expect:
        HealthMonitor.monitored(new ComputeServerGroup(layout: new ComputeTypeLayout(code: code), type: new ComputeServerGroupType(code: type))) == want

        where:
        code                                              | type             | want
        Fixture.LAYOUT_13                                 | 'mvm-cluster'    | true
        Fixture.LAYOUT_20                                 | 'mvm-cluster'    | true
        'kubernetes-1.35-ubuntu-24.04-hvm-amd64-single'   | 'hvm-kube'       | false
        null                                              | 'mvm-cluster'    | true
        null                                              | 'vmware-cluster' | false
    }

    private static HealthChecker.Input ntpOff(boolean maintenance = false) {
        Fixture.input((1..3).collect { int n ->
            String name = "hvm-node0${n}".toString()
            HostFacts f = Fixture.facts('probe-healthy', name) { String t ->
                String r = t.replace(' 5    vm-one   running', n == 1 ? ' 5    vm-one   running' : '')
                n == 2 ? r.replace('NTP=yes', 'NTP=no').replace('NTP_SYNCED=yes', 'NTP_SYNCED=no').replace('sde 8:64  active ready running', 'sde 8:64  failed faulty running').replace('dlm|active|enabled', 'dlm|active|disabled') : r
            }
            Fixture.host(name, f, [maintenance: maintenance && n == 2])
        }, Fixture.LAYOUT_20)
    }

    private static List<HealthChecker.Result> results(HealthChecker.Input i) { new HealthChecker().check(i) }

    def 'with automatic fixes off, nothing is applied'() {
        given:
        def i = ntpOff(true)

        expect:
        HealthMonitor.autoFixable(i, results(i), [:], NOW) == []
        HealthMonitor.autoFixable(i, results(i), [autoFixSafe: 'off', autoFixMaintenance: 'off'], NOW) == []
    }

    def 'safe fixes run automatically when switched on'() {
        given:
        def i = ntpOff()

        when:
        List<HealthChecker.Result> rs = HealthMonitor.autoFixable(i, results(i), [autoFixSafe: 'on', autoFixClusters: 'cluster-x'], NOW)

        then:
        rs*.fix == ['service-enable']
        rs*.host == ['hvm-node02']
    }

    def 'maintenance fixes wait for the host to be in maintenance mode, even when switched on'() {
        given:
        def out = ntpOff(false)
        def in_ = ntpOff(true)
        Map on = [autoFixMaintenance: 'on', autoFixClusters: 'cluster-x']

        expect:
        !HealthMonitor.autoFixable(out, results(out), on, NOW).any { it.tier == Tier.MAINTENANCE }
        HealthMonitor.autoFixable(in_, results(in_), on, NOW)*.fix as Set == ['multipath-reconfigure', 'ntp-enable'] as Set
    }

    def 'emergency actions are never automatic, whatever is switched on'() {
        given:
        def f = Fixture.frozen()

        expect:
        !HealthMonitor.autoFixable(f, results(f), [autoFixSafe: 'on', autoFixMaintenance: 'on', autoFixClusters: 'cluster-x'], NOW).any { it.tier == Tier.EMERGENCY }
    }

    // ------------------------------------------------------------------ which clusters automatic fixes apply to

    def 'automatic fixes apply only to clusters the setting names'() {
        expect:
        HealthMonitor.autoFixNamed(value == null ? [:] : [autoFixClusters: value], 27L, 'cluster-x') == want

        where:
        value                 | want
        null                  | false
        ''                    | false
        '   '                 | false
        ' , ,'                | false
        '27'                  | true
        '12, 27'              | true
        'cluster-x'           | true
        ' Cluster-X , other'  | true
        '28'                  | false
        'other'               | false
        'cluster'             | false
        '*'                   | false
    }

    def 'a number is only ever an id, so a cluster named with digits is not chosen by another cluster id'() {
        expect:
        !HealthMonitor.autoFixNamed([autoFixClusters: '27'], 99L, '27')
        HealthMonitor.autoFixNamed([autoFixClusters: '99'], 99L, '27')
        !HealthMonitor.autoFixNamed([autoFixClusters: '27'], null, 'cluster-x')
    }

    def 'with the switches on and no cluster named, nothing is applied'() {
        given:
        def out = ntpOff(false)
        def in_ = ntpOff(true)

        expect:
        HealthMonitor.autoFixable(out, results(out), [autoFixSafe: 'on', autoFixMaintenance: 'on'], NOW) == []
        HealthMonitor.autoFixable(in_, results(in_), [autoFixSafe: 'on', autoFixMaintenance: 'on', autoFixClusters: ''], NOW) == []
        HealthMonitor.autoFixable(in_, results(in_), [autoFixSafe: 'on', autoFixMaintenance: 'on', autoFixClusters: 'another-cluster, 9'], NOW) == []
    }

    def 'naming a cluster turns nothing on by itself'() {
        given:
        def i = ntpOff(true)

        expect:
        HealthMonitor.autoFixable(i, results(i), [autoFixClusters: 'cluster-x'], NOW) == []
    }

    def 'a cluster can be named by its id'() {
        given:
        def i = ntpOff()
        i.clusterId = 27L

        expect:
        HealthMonitor.autoFixable(i, results(i), [autoFixSafe: 'on', autoFixClusters: '27'], NOW)*.fix == ['service-enable']
        HealthMonitor.autoFixable(i, results(i), [autoFixSafe: 'on', autoFixClusters: '28'], NOW) == []
    }

    /** One cluster with its state served from a fixture, recording what would run on a host. */
    static class FakeContext extends HealthContext {
        List<String> ran = []
        boolean maintenance
        String vlanOutput
        List<Map> networks = [[vlan: 300, gateway: '192.0.2.1']]
        Closure<HostFacts> edit = { HostFacts f -> f }
        int launchExit = 0

        FakeContext(MorpheusContext morpheus) { super(morpheus, 27L) }

        @Override Map<String, HostFacts> probe(String mode) { ntpOff(maintenance).hosts.collectEntries { [(it.name): edit.call(it.facts)] } }
        @Override HealthChecker.Input input(Map<String, HostFacts> facts, String site) { HealthChecker.Input i = ntpOff(maintenance); i.clusterId = 27L; i.networks = networks; i }
        @Override List<ComputeServer> getHosts() { ntpOff().hosts.collect { new ComputeServer(name: it.name) } }
        @Override Map run(ComputeServer host, String script, long timeoutSec) {
            if (script == VlanTest.CLEAN_SCRIPT) {
                return [exit: 0, output: '']   // tidying up is not a launch
            }
            if (script == VlanTest.READ_SCRIPT) {
                return [exit: 0, output: vlanOutput]
            }
            ran << script
            [exit: script.contains(VlanTest.LOCK) ? launchExit : 0, output: 'done']
        }
    }

    def 'a VLAN test with no arping on the host fails as not tested, and reports nothing about any VLAN'() {
        given:
        FakeContext ctx = context()
        ctx.maintenance = true
        ctx.vlanOutput = 'ERROR=arping not installed: not tested\nEND\n'
        List<String> progress = []

        when:
        HostOps.vlanTest(morpheus, ctx, 'hvm-node02', null, progress, [:])

        then:
        IllegalStateException e = thrown()
        e.message == 'arping not installed: not tested'
        !progress.any { it.contains('NO REPLY') || it.contains('does not pass') || it.contains('did not answer') }
    }

    def 'a VLAN test reports which bond answered, and what it could not test'() {
        given:
        FakeContext ctx = context()
        ctx.maintenance = true
        ctx.vlanOutput = '''SKIPPED=vxln|its uplink is not a bond, so it cannot be tested one uplink at a time
RESULT=eno1|300|192.0.2.1|0|hhv300|bond0
RESULT=eno2|300|192.0.2.1|0|hhv300|bond0
RESULT=eno3|300|192.0.2.1|3|hhv300|bond1
RESULT=eno4|300|192.0.2.1|3|hhv300|bond1
RESTORED=eno1 eno3
LEFTOVER=0
END
'''
        List<String> progress = []

        when:
        Map r = HostOps.vlanTest(morpheus, ctx, 'hvm-node02', null, progress, [:])

        then:
        r.ok
        r.carriedBy == ['VLAN 300: answered on bond1']
        progress.any { it.contains('bond1/eno3 VLAN 300 -> gateway 192.0.2.1: 3 replies') }
        progress.any { it.contains('not tested: bridge vxln') }
        progress.any { it.contains('active uplink back on eno1 eno3') }
    }

    private static final String ONE_RUN = 'ORIGINAL=eno1\nRESULT=eno1|300|192.0.2.1|3|hhv300|bond0\nRESULT=eno2|300|192.0.2.1|3|hhv300|bond0\nRESTORED=eno1\nLEFTOVER=0\nEND\n'

    private static List<String> tested(List<String> launches) {
        launches.collect { new String(it.find(/echo '([A-Za-z0-9+\/=]+)' \| base64 -d > /) { all, g -> g }.decodeBase64(), 'UTF-8').find(/for pair in ([^;]*);/) { all, g -> g } }
    }

    def 'VLANs are tested in runs short enough for the corosync token, each run putting the uplink back'() {
        given:
        FakeContext ctx = context()
        ctx.maintenance = true
        ctx.vlanOutput = ONE_RUN
        ctx.networks = (1..7).collect { [vlan: 300 + it, gateway: "192.0.2.${it}".toString()] }
        List<String> progress = []

        when: 'a 60 s token allows 4 s + 8 s per VLAN up to 30 s: three'
        Map r = HostOps.vlanTest(morpheus, ctx, 'hvm-node02', null, progress, [:])

        then:
        tested(ctx.ran) == ['301:192.0.2.1 302:192.0.2.2 303:192.0.2.3', '304:192.0.2.4 305:192.0.2.5 306:192.0.2.6', '307:192.0.2.7']
        (r.results as List).size() == 6
        progress.count { it.contains('active uplink back on eno1') } == 3
    }

    def 'how many VLANs fit in a run follows the token'() {
        expect:
        VlanTest.vlansPerRun(token, 20) == want

        where:
        token  | want
        null   | 20
        60650L | 3
        60000L | 3
        24000L | 1
        23000L | 0
        3000L  | 0
    }

    def 'a token too short for even one VLAN is refused before anything moves'() {
        given:
        FakeContext ctx = context()
        ctx.maintenance = true
        ctx.edit = { HostFacts f -> f.runtimeToken = 3000L; f }

        when:
        HostOps.vlanTest(morpheus, ctx, 'hvm-node02', null, [], [:])

        then:
        IllegalStateException e = thrown()
        e.message.contains('corosync token of 3000 ms')
        ctx.ran == []
    }

    def 'a test still running on the host refuses a retry, whose original would be the standby'() {
        given:
        FakeContext ctx = context()
        ctx.maintenance = true
        ctx.launchExit = VlanTest.BUSY_EXIT

        when:
        HostOps.vlanTest(morpheus, ctx, 'hvm-node02', null, [], [:])

        then:
        IllegalStateException e = thrown()
        e.message.contains('still running there')
    }

    def 'the script holds a lock on the host, checked before anything is overwritten, and its timer holds it too'() {
        given:
        String launch = VlanTest.launchScript([[vlan: 300, gateway: '192.0.2.1']])
        String body = new String(launch.find(/echo '([A-Za-z0-9+\/=]+)' \| base64 -d > /) { all, g -> g }.decodeBase64(), 'UTF-8')

        expect:
        launch.indexOf("flock -n ${VlanTest.LOCK} true") < launch.indexOf("rm -f ${VlanTest.OUT}")
        body.contains("exec 9>${VlanTest.LOCK}")
        body.indexOf('flock -n 9') < body.indexOf('active_slave')
        body.indexOf('echo "ORIGINAL=') < body.indexOf('trap finish EXIT')
        !body.contains('9>&-')
    }

    def 'an uplink that did not go back is reported, and stops the test'() {
        given:
        FakeContext ctx = context()
        ctx.maintenance = true
        ctx.vlanOutput = ONE_RUN.replace('RESTORED=eno1', 'RESTORED=eno2')
        ctx.networks = (1..4).collect { [vlan: 300 + it, gateway: "192.0.2.${it}".toString()] }

        when:
        HostOps.vlanTest(morpheus, ctx, 'hvm-node02', null, [], [:])

        then:
        IllegalStateException e = thrown()
        e.message.contains('is eno2, and was eno1')
        ctx.ran.size() == 1
    }

    private FakeContext context(String liveSettings = '{}') {
        def clusters = Stub(MorpheusServices.getMethod('getCluster').returnType) { get(_) >> new ComputeServerGroup(id: 27L, name: 'cluster-x') }
        def services = Stub(MorpheusServices) { getCluster() >> clusters }
        morpheus = Stub(MorpheusContext) { getServices() >> services; getSettings(_) >> Single.just(liveSettings) }
        new FakeContext(morpheus)
    }

    MorpheusContext morpheus

    def setup() {
        HostOps.pollMs = 1L
    }

    def cleanup() {
        HostOps.pollMs = 8000L
        HealthMonitor.stop()
        HealthMonitor.LAST.remove(27L)
    }

    def 'the automatic path checks the cluster is named again, on fresh state, and runs nothing if it is not'() {
        given:
        FakeContext ctx = context()

        when:
        HealthMonitor.applyFix(morpheus, ctx, 'hvm-node02', 'service-enable', null, [], [autoFixSafe: 'on'], true)

        then:
        IllegalStateException e = thrown()
        e.message.contains('not in the autoFixClusters setting')
        ctx.ran == []
    }

    def 'the automatic path runs a fix on a named cluster'() {
        given:
        FakeContext ctx = context()

        when:
        HealthMonitor.applyFix(morpheus, ctx, 'hvm-node02', 'service-enable', null, [], [autoFixSafe: 'on', autoFixClusters: '27'], true)

        then:
        ctx.ran.size() == 1
        ctx.ran[0].contains('systemctl enable dlm')
    }

    def 'a person pressing Fix is not bound by the setting'() {
        given:
        FakeContext ctx = context()

        when:
        HealthMonitor.applyFix(morpheus, ctx, 'hvm-node02', 'service-enable', null, [], [:])

        then:
        ctx.ran.size() == 1
    }

    def 'a cluster taken off the list after the pass chose its fix is refused: the settings are read again'() {
        given: 'the pass saw the cluster named; the settings as saved now do not name it'
        FakeContext ctx = context('{"autoFixSafe":"on","autoFixClusters":"another-cluster"}')
        HealthMonitor.start(morpheus, new ClusterHealthPlugin())

        when:
        HealthMonitor.applyFix(morpheus, ctx, 'hvm-node02', 'service-enable', null, [], [autoFixSafe: 'on', autoFixClusters: '27'], true)

        then:
        IllegalStateException e = thrown()
        e.message.contains('not in the autoFixClusters setting')
        ctx.ran == []
    }

    def 'the switch turned off after the pass chose its fix is refused too'() {
        given:
        FakeContext ctx = context('{"autoFixClusters":"27"}')
        HealthMonitor.start(morpheus, new ClusterHealthPlugin())

        when:
        HealthMonitor.applyFix(morpheus, ctx, 'hvm-node02', 'service-enable', null, [], [autoFixSafe: 'on', autoFixClusters: '27'], true)

        then:
        thrown(IllegalStateException)
        ctx.ran == []
    }

    def 'unrelated failures and warnings get an alarm each, at the right severity, and passes get none'() {
        given:
        List<HealthChecker.Result> rs = [
                new HealthChecker.Result('DLM-01', 'Waiting', HealthChecker.Severity.CRITICAL, HealthChecker.Status.FAIL, 'hvm-node02', 'stuck', 'none'),
                new HealthChecker.Result('STO-05', 'Paths', HealthChecker.Severity.MEDIUM, HealthChecker.Status.WARN, null, '4 vs 8', 'equal'),
                new HealthChecker.Result('CLU-03', 'Quorate', HealthChecker.Severity.CRITICAL, HealthChecker.Status.PASS, 'hvm-node02', 'yes', 'yes'),
                new HealthChecker.Result('HB-01', 'Heartbeat', HealthChecker.Severity.HIGH, HealthChecker.Status.NA, 'hvm-node02', 'n/a', 'one')]

        when:
        List<Map> a = HealthMonitor.alarmsFor(15L, 'cluster-x', rs)

        then:
        a*.status == ['error', 'warning']
        a[0].host == 'hvm-node02'
        a[0].message.startsWith('cluster-x / hvm-node02: Waiting: stuck')
        a[1].host == null
        a*.uniqueId.every { it.startsWith('hvm-cluster-health-15-') }
    }

    def 'a finding keeps its alarm identity across runs, and each cluster its own'() {
        given:
        Closure<HealthChecker.Result> r = { String host, String observed -> new HealthChecker.Result('NET-01', 'Bond', HealthChecker.Severity.HIGH, HealthChecker.Status.FAIL, host, observed, 'up') }

        expect:
        HealthMonitor.alarmsFor(15L, 'c', [r('a', 'one')])[0].uniqueId == HealthMonitor.alarmsFor(15L, 'c', [r('a', 'two')])[0].uniqueId
        HealthMonitor.alarmsFor(15L, 'c', [r('a', 'one')])[0].uniqueId != HealthMonitor.alarmsFor(151L, 'c', [r('a', 'one')])[0].uniqueId
    }

    def 'cluster prefixes do not collide, so one cluster never clears another cluster alarm'() {
        expect:
        !HealthMonitor.alarmsFor(151L, 'c', [new HealthChecker.Result('A-1', 't', HealthChecker.Severity.LOW, HealthChecker.Status.WARN, null, 'o', 'e')])[0].uniqueId.startsWith(HealthMonitor.alarmPrefix(15L))
    }
}
