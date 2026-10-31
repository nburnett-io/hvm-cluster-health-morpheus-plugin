package org.hvmhealth

import com.morpheusdata.model.ComputeServerGroup
import com.morpheusdata.model.ComputeServerGroupType
import com.morpheusdata.model.ComputeTypeLayout
import org.hvmhealth.checks.HealthChecker
import org.hvmhealth.checks.HealthChecker.Tier
import org.hvmhealth.model.HostFacts
import org.hvmhealth.morpheus.HealthMonitor
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
                n == 2 ? r.replace('NTP=yes', 'NTP=no').replace('NTP_SYNCED=yes', 'NTP_SYNCED=no').replace('sde 8:64  active ready running', 'sde 8:64  failed faulty running') : r
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
        List<HealthChecker.Result> rs = HealthMonitor.autoFixable(i, results(i), [autoFixSafe: 'on'], NOW)

        then:
        rs*.fix == ['ntp-enable']
        rs*.host == ['hvm-node02']
    }

    def 'maintenance fixes wait for the host to be in maintenance mode, even when switched on'() {
        given:
        def out = ntpOff(false)
        def in_ = ntpOff(true)

        expect:
        !HealthMonitor.autoFixable(out, results(out), [autoFixMaintenance: 'on'], NOW).any { it.tier == Tier.MAINTENANCE }
        HealthMonitor.autoFixable(in_, results(in_), [autoFixMaintenance: 'on'], NOW)*.fix == ['multipath-reconfigure']
    }

    def 'emergency actions are never automatic, whatever is switched on'() {
        given:
        def f = Fixture.frozen()

        expect:
        !HealthMonitor.autoFixable(f, results(f), [autoFixSafe: 'on', autoFixMaintenance: 'on'], NOW).any { it.tier == Tier.EMERGENCY }
    }

    def 'every failure and warning gets its own alarm, at the right severity, and passes get none'() {
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

    def 'a finding keeps its alarm identity across runs, and each host its own'() {
        given:
        Closure<HealthChecker.Result> r = { String host, String observed -> new HealthChecker.Result('NET-01', 'Bond', HealthChecker.Severity.HIGH, HealthChecker.Status.FAIL, host, observed, 'up') }

        expect:
        HealthMonitor.alarmsFor(15L, 'c', [r('a', 'one')])[0].uniqueId == HealthMonitor.alarmsFor(15L, 'c', [r('a', 'two')])[0].uniqueId
        HealthMonitor.alarmsFor(15L, 'c', [r('a', 'one')])[0].uniqueId != HealthMonitor.alarmsFor(15L, 'c', [r('b', 'one')])[0].uniqueId
        HealthMonitor.alarmsFor(15L, 'c', [r('a', 'one')])[0].uniqueId != HealthMonitor.alarmsFor(151L, 'c', [r('a', 'one')])[0].uniqueId
    }

    def 'cluster prefixes do not collide, so one cluster never clears another cluster alarm'() {
        expect:
        !HealthMonitor.alarmsFor(151L, 'c', [new HealthChecker.Result('A-1', 't', HealthChecker.Severity.LOW, HealthChecker.Status.WARN, null, 'o', 'e')])[0].uniqueId.startsWith(HealthMonitor.alarmPrefix(15L))
    }
}
