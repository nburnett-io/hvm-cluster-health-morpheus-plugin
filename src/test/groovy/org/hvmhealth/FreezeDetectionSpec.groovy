package org.hvmhealth

import org.hvmhealth.checks.HealthChecker
import org.hvmhealth.checks.HealthChecker.Severity
import org.hvmhealth.checks.HealthChecker.Status
import org.hvmhealth.checks.HealthChecker.Tier
import org.hvmhealth.model.HostFacts
import spock.lang.Specification
import spock.lang.Unroll

/**
 * 0.4.0 W-GFS-DLM: freeze detection from source reading. The -125 split state (DLM-07), DLM-03 wait states and the
 * lock_module/block signal, the kernel-fix floor (KRN-04) and the -22 conversion defect (KRN-05). Facts are set directly,
 * as the probe cannot run here (no bash); the probe's own reads are covered by ScriptsSpec, which skips without bash.
 */
class FreezeDetectionSpec extends Specification {
    private static List<HealthChecker.Result> check(HealthChecker.Input i) { new HealthChecker().check(i) }

    /** A healthy 2.0 cluster whose second host's facts the closure adjusts. */
    private static HealthChecker.Input cluster(Closure onNode2) {
        HealthChecker.Input i = Fixture.healthy()
        onNode2.call(i.hosts[1].facts)
        i
    }

    // ------------------------------------------------------------------ DLM-07, the -125 split state

    def 'the -125 split state is DLM-07, offers no acknowledgement, and silences DLM-01 and DLM-03 for that node'() {
        given:
        HealthChecker.Input i = cluster { HostFacts f ->
            f.lockspaces = [[name: 'cluster-b:ds_01', flags: '0x00000004 kern_stop', members: '1 2 6', status: 'wait fencing', newMembers: '1 2']]
            f.fenceWaits = []
            f.dlmNodes = [[node: 6, state: 'X', fail: 9257593L, fence: 0L]]
        }

        when:
        List<HealthChecker.Result> rs = check(i).findAll { it.host == 'hvm-node02' }

        then:
        i.hosts[1].facts.divergedWaits == [6]
        i.hosts[1].facts.inferredWaits == []
        with(rs.find { it.id == 'DLM-07' }) {
            status == Status.FAIL
            severity == Severity.CRITICAL
            tier == Tier.NONE
            fix == null
            observed.contains('node 6')
            hint.contains('-125')
            hint.contains('clean rejoin')
        }
        !rs.any { it.id == 'DLM-01' }
        !rs.any { it.id == 'DLM-03' && it.status == Status.FAIL }
    }

    def 'a no-fence-line wait with no daemon record stays DLM-01 with the acknowledgement, not DLM-07'() {
        given:
        HealthChecker.Input i = cluster { HostFacts f ->
            f.lockspaces = [[name: 'cluster-b:ds_01', flags: '0x00000004 kern_stop', members: '1 2 6', status: 'wait fencing', newMembers: '1 2']]
            f.fenceWaits = []
            f.dlmNodes = []
        }

        when:
        List<HealthChecker.Result> rs = check(i).findAll { it.host == 'hvm-node02' }

        then:
        i.hosts[1].facts.divergedWaits == []
        with(rs.find { it.id == 'DLM-01' }) {
            status == Status.FAIL
            fix == 'fence-ack:6'
            tier == Tier.EMERGENCY
        }
        !rs.any { it.id == 'DLM-07' }
    }

    def 'a departed node whose fence time caught up is not the split state, so the acknowledgement is still offered'() {
        given:
        HealthChecker.Input i = cluster { HostFacts f ->
            f.lockspaces = [[name: 'cluster-b:ds_01', flags: '0x00000004 kern_stop', members: '1 2 6', status: 'wait fencing', newMembers: '1 2']]
            f.fenceWaits = []
            f.dlmNodes = [[node: 6, state: 'X', fail: 100L, fence: 200L]]
        }

        expect:
        i.hosts[1].facts.divergedWaits == []
        check(i).findAll { it.host == 'hvm-node02' }.any { it.id == 'DLM-01' }
        !check(i).any { it.id == 'DLM-07' }
    }

    // ------------------------------------------------------------------ DLM-03 wait states and lock_module/block

    def 'DLM-03 fails on a stopped lockspace nothing else explains, and names the wait state'() {
        given:
        HealthChecker.Input i = cluster { HostFacts f ->
            f.lockspaces = [[name: 'cluster-b:ds_01', flags: '0x00000004 kern_stop', members: '1 2 3', status: 'wait messages 3']]
            f.fenceWaits = []
        }

        when:
        List<HealthChecker.Result> rs = check(i).findAll { it.host == 'hvm-node02' }

        then:
        with(rs.find { it.id == 'DLM-03' }) {
            status == Status.FAIL
            hint.contains('start message')
        }
        !rs.any { it.id in ['DLM-01', 'DLM-07'] }
    }

    @Unroll
    def 'lock_module/block set with nothing stopped is a DLM-03 warning, and clear block passes (#block)'() {
        given:
        HealthChecker.Input i = cluster { HostFacts f ->
            f.lockspaces = [[name: 'cluster-b:ds_01', flags: '0x00000000', members: '1 2 3']]
            f.fenceWaits = []
            f.gfsBlock = block
        }

        expect:
        check(i).find { it.id == 'DLM-03' && it.host == 'hvm-node02' }.status == status

        where:
        block                  | status
        ['cluster-b:ds_01': 1] | Status.WARN
        ['cluster-b:ds_01': 0] | Status.PASS
        [:]                    | Status.PASS
    }

    // ------------------------------------------------------------------ KRN-04 kernel floor

    @Unroll
    def 'KRN-04 warns below the kernel floor and passes at or above it (#kernel)'() {
        given:
        HealthChecker.Input i = cluster { HostFacts f -> f.kernel = kernel }

        expect:
        check(i).find { it.id == 'KRN-04' && it.host == 'hvm-node02' }.status == status

        where:
        kernel             | status
        '7.0.0-10-generic' | Status.PASS         // the 2.0 floor is the 7.0 base
        '7.0.0-40-generic' | Status.PASS
        '6.8.0-90-generic' | Status.WARN         // a 6.8 kernel on a 2.0 cluster is below 7.0.0
        'not-a-kernel'     | Status.NOT_CHECKED
    }

    def 'the kernel comparison reads major.minor.patch-abi, and says nothing it cannot parse'() {
        expect:
        HealthChecker.kernelAtLeast('6.8.0-138-generic', '6.8.0-100')
        !HealthChecker.kernelAtLeast('6.8.0-99-generic', '6.8.0-100')
        HealthChecker.kernelAtLeast('7.0.0-1-generic', '6.8.0-100')
        HealthChecker.kernelAtLeast('6.8.0-100-generic', '6.8.0-100')
        HealthChecker.kernelAtLeast('7.0.0-15-generic', '7.0.0') == true
        HealthChecker.kernelAtLeast('weird', '6.8.0-100') == null
        HealthChecker.kernelAtLeast('6.8.0-100', 'weird') == null
    }

    // ------------------------------------------------------------------ KRN-05 the -22 conversion defect

    @Unroll
    def 'KRN-05 fails on a -22 signature since boot, warns before boot, passes on none, says so when unread (#desc)'() {
        given:
        HealthChecker.Input i = cluster { HostFacts f ->
            f.dlmConvErrors = count
            f.dlmConvErrorLast = last
            f.boot = 1793500000L
        }

        expect:
        check(i).find { it.id == 'KRN-05' && it.host == 'hvm-node02' }.status == status

        where:
        desc          | count | last                                                                       | status
        'since boot'  | 1     | '2027-06-01T00:00:00+00:00 host kernel: gfs2: fsid=c:d.0: validate_lock_args -22' | Status.FAIL
        'before boot' | 1     | '2026-10-31T20:16:29+00:00 host kernel: gfs2: fsid=c:d.0: lm_lock ret -22'  | Status.WARN
        'none'        | 0     | ''                                                                         | Status.PASS
        'unread'      | null  | ''                                                                         | Status.NOT_CHECKED
    }

    def 'a healthy cluster is clean of the new checks: KRN-04 passes everywhere, KRN-05 is not checked from the sample'() {
        when:
        List<HealthChecker.Result> rs = check(Fixture.healthy())

        then:
        rs.findAll { it.id == 'KRN-04' }*.status.unique() == [Status.PASS]
        rs.findAll { it.id == 'KRN-05' }*.status.unique() == [Status.NOT_CHECKED]
        !Fixture.warning(rs).contains('KRN-04')
        !Fixture.failing(rs).contains('KRN-05')
    }
}
