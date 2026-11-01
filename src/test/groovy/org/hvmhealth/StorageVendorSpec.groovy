package org.hvmhealth

import org.hvmhealth.checks.HealthChecker
import org.hvmhealth.checks.HealthChecker.Status
import org.hvmhealth.checks.StorageProfiles
import org.hvmhealth.morpheus.HealthMonitor
import spock.lang.Specification
import spock.lang.Unroll

/**
 * 0.4.0 W-STO: vendor-neutral storage. STO-01 ghost standby, STO-02 the no_path_retry window, STO-03/04 by transport,
 * STO-07 hard space-allocation failures, and the new STO-08..STO-13. Fibre Channel and per-vendor values are built from
 * documentation (storage_vendor_matrix.md), not seen live; these tests drive the probe output a real host would print.
 */
class StorageVendorSpec extends Specification {
    static final String WWID = '3624a9370aaaaaaaaaaaaaaaa00000001'
    static final String DEFAULT_FC = "FC_HOST=host6|Online|16_Gbit|0x50aaaa|0|0|0|0\nFC_HOST=host7|Online|16_Gbit|0x50bbbb|0|0|0|0\nFC_RPORT=rport-6:0-0|Online|FCP_Target|60|5\nFC_RPORT=rport-7:0-0|Online|FCP_Target|60|5"

    private static List<HealthChecker.Result> check(HealthChecker.Input i) { new HealthChecker().check(i) }
    private static List<HealthChecker.Result> ids(HealthChecker.Input i, String id) { check(i).findAll { it.id == id } }

    private static String withSection(String t, String extra) { t.replace('@@end', extra.trim() + '\n@@end') }
    private static Closure<String> sect(String extra) { return { String t -> withSection(t, extra) } }

    /** One 2.0 host (PURE iSCSI map) with the raw probe text edited by {@code edit}. */
    private static HealthChecker.Input in20(String site = null, Closure<String> edit = { it }) {
        Fixture.input([Fixture.host('hvm-node01', Fixture.facts('probe-healthy', 'hvm-node01', edit))], Fixture.LAYOUT_20, site)
    }

    /** An FC-only host: no iSCSI sessions, a fc_host present. */
    private static HealthChecker.Input fcOnly(String fc = DEFAULT_FC) {
        in20(null) { withSection(it, "@@fc\nFC_PRESENT=1\n${fc}").replace('SESSIONS=2', 'SESSIONS=0') }
    }

    // ------------------------------------------------------------------ STO-01 ghost / standby

    def 'an ALUA standby (ghost) path is healthy standby, not a false failure'() {
        when:
        HealthChecker.Result r = ids(in20(null) { it.replace('sde 8:64  active ready running', 'sde 8:64  active ghost running') }, 'STO-01')[0]

        then:
        r.status == Status.PASS
        r.observed == '2 of 2 healthy (1 standby)'
    }

    def 'only standby paths left, with none active, is all-paths-gone and fails'() {
        when:
        HealthChecker.Result r = ids(in20(null) { it.replace('sdd 8:48  active ready running', 'sdd 8:48  active ghost running').replace('sde 8:64  active ready running', 'sde 8:64  active ghost running') }, 'STO-01')[0]

        then: 'standby paths pass their checker, so they count as healthy; the FAIL is that none is active'
        r.status == Status.FAIL
        r.observed == '2 of 2 healthy (2 standby)'
        r.hint.contains('standby')
    }

    def 'a map with no paths at all still fails'() {
        when:
        HealthChecker.Result r = ids(in20(null) { it.replaceAll(/(?m)^ {2,}.- \d+:\d+:\d+:\d+ .*\n/, '') }, 'STO-01')[0]

        then:
        r.status == Status.FAIL
        r.observed == '0 of 0 healthy'
    }

    def 'a genuinely faulty path among healthy ones still fails, as before'() {
        expect:
        ids(in20(null) { it.replace('sde 8:64  active ready running', 'sde 8:64  failed faulty running') }, 'STO-01')[0].status == Status.FAIL
    }

    // ------------------------------------------------------------------ STO-02 no_path_retry window

    private static HealthChecker.Input queue20(String q, String polling = '5', String features = '0') {
        in20(null) { withSection(it, "@@multipath_queue\nMP_QUEUE=${WWID}|${q}\nMP_POLLING=${polling}").replace("features='0'", "features='${features}'") }
    }

    def 'a finite window within the token and ceiling passes, reporting seconds and the vendor value'() {
        when:
        HealthChecker.Result r = ids(queue20('12 chk'), 'STO-02')[0]

        then: 'polling 5 gives a 61 s window; the 2.0 cluster has no token floor and the default 180 s ceiling'
        r.status == Status.PASS
        r.observed == "no_path_retry 12, about 61 s at polling 5; Pure FlashArray documents no_path_retry 0 (Pure (Everpure) recommended settings, not seen live)"
    }

    def 'a window over the site ceiling warns, and never fails'() {
        when:
        HealthChecker.Result r = ids(queue20('40 chk'), 'STO-02')[0]

        then: '40 x 5 + 1 = 201 s, over the 180 s noPathRetryPolicy'
        r.status == Status.WARN
        r.observed.startsWith('no_path_retry 40, about 201 s')
        r.hint.contains('site ceiling')
    }

    def 'a window under the corosync token warns'() {
        when: 'a 1.3 cluster has a 60 s token; 5 x 5 + 1 = 26 s'
        HealthChecker.Result r = check(Fixture.timesyncd(null) { String t, String h -> t.replace('|12 chk', '|5 chk') }).find { it.id == 'STO-02' }

        then:
        r.status == Status.WARN
        r.hint.contains('corosync token')
    }

    def 'off with no active path is a live failure, not a quiet pass'() {
        when:
        HealthChecker.Result r = ids(in20(null) {
            withSection(it, "@@multipath_queue\nMP_QUEUE=${WWID}|off").replace('sdd 8:48  active ready running', 'sdd 8:48  failed faulty running').replace('sde 8:64  active ready running', 'sde 8:64  failed faulty running')
        }, 'STO-02')[0]

        then:
        r.status == Status.WARN
        r.observed.startsWith('no_path_retry off with no active path')
    }

    def 'the ceiling is the site standard'() {
        expect:
        ids(queue20('40 chk'), 'STO-02')[0].status == Status.WARN
        ids(in20('{"noPathRetryPolicy": 300}') { withSection(it, "@@multipath_queue\nMP_QUEUE=${WWID}|40 chk\nMP_POLLING=5") }, 'STO-02')[0].status == Status.PASS
    }

    // ------------------------------------------------------------------ STO-03 / STO-04 by transport

    def 'on an FC-only host the iSCSI error check is not checked, never a quiet pass'() {
        when:
        HealthChecker.Result r = ids(fcOnly(), 'STO-03')[0]

        then:
        r.status == Status.NOT_CHECKED
        r.observed.contains('Fibre Channel')
    }

    def 'multipathd is judged on an FC host where the old iSCSI-only check saw nothing'() {
        expect:
        ids(fcOnly(), 'STO-04')[0].status == Status.PASS
        ids(fcOnly(), 'STO-04')[0].observed.contains('iscsid is not needed')

        when: 'multipathd down on the FC host'
        HealthChecker.Result r = ids(in20(null) {
            withSection(it, "@@fc\nFC_PRESENT=1\nFC_HOST=host6|Online|16_Gbit|0x50aaaa|0|0|0|0").replace('SESSIONS=2', 'SESSIONS=0').replace('multipathd|active|enabled', 'multipathd|inactive|enabled')
        }, 'STO-04')[0]

        then:
        r.status == Status.FAIL
    }

    def 'the STO-01 fix rescans by transport and never runs an iSCSI rescan on an FC host'() {
        expect:
        org.hvmhealth.fixes.Fixes.fix('multipath-reconfigure', null).script.with {
            contains('iscsiadm -m session >/dev/null 2>&1') && contains('/sys/class/scsi_host/') && contains('multipathd reconfigure')
        }
    }

    // ------------------------------------------------------------------ STO-09 Fibre Channel fabric

    @Unroll
    def 'FC fabric: #why'() {
        expect:
        ids(fcOnly(fc), 'STO-09')[0].status == expected

        where:
        why                         | fc                                                                                                                             | expected
        'all online, two adapters'  | "FC_HOST=host6|Online|16_Gbit|0x50a|0|0|0|0\nFC_HOST=host7|Online|16_Gbit|0x50b|0|0|0|0\nFC_RPORT=r6|Online|FCP_Target|60|5"   | Status.PASS
        'one adapter only'          | "FC_HOST=host6|Online|16_Gbit|0x50a|0|0|0|0\nFC_RPORT=r6|Online|FCP_Target|60|5"                                                 | Status.WARN
        'a port down, no target up' | "FC_HOST=host6|Online|16_Gbit|0x50a|0|0|0|0\nFC_HOST=host7|Linkdown|unknown|0x50b|9|0|0|0\nFC_RPORT=r6|Blocked|FCP_Target|60|5" | Status.FAIL
    }

    def 'an iSCSI host has no FC fabric row'() {
        expect:
        ids(in20(), 'STO-09') == []
    }

    // ------------------------------------------------------------------ STO-10 node.startup / STO-11 recovery_tmo

    def 'a target not set to automatic warns, and all automatic passes'() {
        expect:
        ids(in20(null, sect("@@iscsi_timers\nNODE_STARTUP=iqn.example:a|automatic\nNODE_STARTUP=iqn.example:b|manual")), 'STO-10')[0].status == Status.WARN
        ids(in20(null, sect("@@iscsi_timers\nNODE_STARTUP=iqn.example:a|automatic")), 'STO-10')[0].status == Status.PASS
    }

    def 'recovery_tmo that is not multipathd fast_io_fail warns, 120 meaning never applied'() {
        when:
        HealthChecker.Result r = ids(in20(null, sect("@@multipath_queue\nMP_QUEUE=${WWID}|12 chk\nMP_FAST_IO_FAIL=10\n@@iscsi_timers\nRECOVERY_TMO=session1|120\nRECOVERY_TMO=session2|10")), 'STO-11').find { it.host == 'hvm-node01' }

        then:
        r.status == Status.WARN
        r.observed.contains('recovery_tmo 10, 120 s')
        r.hint.contains('120')
    }

    def 'recovery_tmo at the vendor value passes, and the vendor miss is labelled unverified'() {
        expect:
        ids(in20(null, sect("@@iscsi_timers\nRECOVERY_TMO=session1|10")), 'STO-11').find { it.host == 'hvm-node01' }.status == Status.PASS
        ids(in20(null, sect("@@iscsi_timers\nRECOVERY_TMO=session1|20")), 'STO-11').find { it.host == 'hvm-node01' }.with { status == Status.WARN && hint.contains('unverified') }
    }

    def 'hosts holding different recovery_tmo warn at cluster level'() {
        given:
        HealthChecker.Input i = Fixture.input((1..2).collect { int n ->
            Fixture.host("hvm-node0${n}".toString(), Fixture.facts('probe-healthy', "hvm-node0${n}".toString(), sect("@@iscsi_timers\nRECOVERY_TMO=session1|${n == 1 ? 10 : 120}")))
        }, Fixture.LAYOUT_20)

        when:
        HealthChecker.Result r = ids(i, 'STO-11').find { it.host == null }

        then:
        r.status == Status.WARN
        r.observed.contains('hvm-node01=10')
    }

    // ------------------------------------------------------------------ STO-08 vendor identity + transport

    def 'a known vendor passes and names its family and transport'() {
        when:
        HealthChecker.Result r = ids(in20(null, sect("@@mpath_paths\nMPP_EXT=0\nMPP=${WWID}|sdd|scsi:iscsi|host16")), 'STO-08')[0]

        then:
        r.status == Status.PASS
        r.observed.contains('Pure FlashArray')
        r.observed.contains('transport iscsi')
        r.observed.contains('target port groups not read')
    }

    def 'an unknown vendor is not checked, never passed on a vendor rule'() {
        when:
        HealthChecker.Result r = check(Fixture.timesyncd()).find { it.id == 'STO-08' }

        then: 'the 1.3 fixture map is LIO-ORG,examplelun, an unknown vendor'
        r.status == Status.NOT_CHECKED
        r.observed.contains('unknown vendor LIO-ORG')
    }

    def 'target port groups are read on 26.04 and not on 24.04'() {
        expect:
        ids(in20(null, sect("@@mpath_paths\nMPP_EXT=1\nMPP=${WWID}|sdd|scsi:iscsi|host16|1|4096\nMPP=${WWID}|sde|scsi:iscsi|host17|2|4096")), 'STO-08')[0].observed.contains('2 target port group(s)')
    }

    def 'the vendor profile table maps the families and leaves the unknown null'() {
        expect:
        StorageProfiles.forMap('PURE,FlashArray').key == 'pure'
        StorageProfiles.forMap('3PARdata,VV').family.contains('3PARdata')
        StorageProfiles.forMap('NETAPP,LUN C-Mode').key == 'netapp-ontap'
        StorageProfiles.forMap('NETAPP,INF-01-00').key == 'netapp-eseries'
        StorageProfiles.forMap('Nimble,Server').recoveryTmo == 5
        StorageProfiles.forMap('LIO-ORG,examplelun') == null
        StorageProfiles.forMap(null) == null
    }

    // ------------------------------------------------------------------ STO-12 expected paths

    def 'below the site design warns, and it is silent when the key is unset'() {
        expect:
        ids(in20(), 'STO-12') == []
        ids(in20('{"expectedPaths": 4}'), 'STO-12')[0].status == Status.WARN
        ids(in20('{"expectedPaths": 2}'), 'STO-12')[0].status == Status.PASS
    }

    // ------------------------------------------------------------------ STO-07 hard space-allocation failure

    def 'a hard space-allocation failure fails, where the soft threshold only warns'() {
        expect:
        ids(in20(null, sect('@@thin\nTHIN_SOFT_7D=1\nTHIN_SOFT_LAST=x')), 'STO-07')[0].status == Status.WARN
        ids(in20(null, sect('@@thin\nTHIN_SOFT_7D=0\nTHIN_HARD_7D=1\nTHIN_HARD_LAST=2026-11-01 sd 1:0 critical space allocation')), 'STO-07')[0].with {
            status == Status.FAIL && observed.contains('hard space-allocation')
        }
    }

    def 'a write-protected map fails the thin check'() {
        when:
        HealthChecker.Result r = ids(in20(null) { withSection(it, '@@thin\nTHIN_SOFT_7D=0').replace('wp=rw', 'wp=ro') }, 'STO-07')[0]

        then:
        r.status == Status.FAIL
        r.observed.contains('write-protected')
    }

    def 'an unreadable thin log is not checked'() {
        expect:
        ids(in20(null, sect('@@thin')), 'STO-07')[0].status == Status.NOT_CHECKED
    }

    // ------------------------------------------------------------------ STO-13 all-paths-lost log

    @Unroll
    def 'all-paths-lost over 7 days is #expected'() {
        expect:
        ids(in20(null, sect(section)), 'STO-13')[0].status == expected

        where:
        section                                                                    | expected
        '@@mpath_log\nPATHS_LOST_7D=2\nPATHS_LOST_LAST=x remaining active paths: 0' | Status.FAIL
        '@@mpath_log\nPATHS_LOST_7D=0'                                             | Status.PASS
        '@@mpath_log'                                                              | Status.NOT_CHECKED
    }

    def 'STO-13 is full check only and has no row with no log section'() {
        expect:
        'STO-13' in HealthMonitor.FULL_ONLY
        ids(in20(), 'STO-13') == []
    }

    // ------------------------------------------------------------------ no noise on healthy clusters

    def 'none of the storage checks raise an alarm on a healthy cluster of either layout'() {
        given:
        List<String> ours = ['STO-01', 'STO-02', 'STO-03', 'STO-04', 'STO-08', 'STO-09', 'STO-10', 'STO-11', 'STO-12', 'STO-13']

        expect:
        check(Fixture.healthy('{"ntpServers":["ntp1.example.invalid"]}')).findAll { it.id in ours && it.status in [Status.FAIL, Status.WARN] } == []
        check(Fixture.timesyncd()).findAll { it.id in ours && it.status in [Status.FAIL, Status.WARN] } == []
    }
}
