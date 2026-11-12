package org.hvmhealth

import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.MorpheusServices
import com.morpheusdata.model.Account
import com.morpheusdata.model.Backup
import com.morpheusdata.model.BackupResult
import com.morpheusdata.model.ComputeServerGroup
import com.morpheusdata.model.StorageBucket
import org.hvmhealth.checks.CheckCatalog
import org.hvmhealth.checks.HealthChecker
import org.hvmhealth.checks.HealthChecker.Status
import org.hvmhealth.morpheus.HealthContext
import org.hvmhealth.morpheus.HealthMonitor
import org.hvmhealth.standards.Layout
import org.hvmhealth.standards.Standards
import spock.lang.Specification
import spock.lang.Unroll

/** HST-09 (failed systemd units) and MOR-07 (appliance backups), new in 0.7.0. */
class UnitsAndBackupsSpec extends Specification {
    static final long NOW_MS = 1793385845000L
    static final long DAY = 86400000L

    private static HealthChecker.Input health(String lines, String site = null) {
        Fixture.input([Fixture.host('hvm-node01', Fixture.facts('probe-healthy', 'hvm-node01') { String t ->
            t.replace('@@end', "@@host_health\nOOM_READ=1\nVM_LOGS_READ=1\nROOT_DF=/|40|12\n${lines}\n@@end")
        })], Fixture.LAYOUT_20, site)
    }

    private static List<HealthChecker.Result> ids(HealthChecker.Input i, String id) {
        new HealthChecker().check(i).findAll { it.id == id }
    }

    private static HealthChecker.Result only(HealthChecker.Input i, String id) {
        List<HealthChecker.Result> rs = ids(i, id)
        assert rs.size() == 1
        rs[0]
    }

    // ------------------------------------------------------------------ HST-09

    def 'a healthy host has no failed unit, and a probe with no such section says nothing'() {
        given:
        HealthChecker.Input i = health('FAILED_READ=1')
        i.hosts[0].facts.sections.remove('host_health')

        expect:
        ids(Fixture.healthy(), 'HST-09')*.status.unique() == [Status.PASS]
        ids(i, 'HST-09') == []
    }

    @Unroll
    def 'failed units #lines is #want'() {
        when:
        HealthChecker.Result r = only(health(lines), 'HST-09')

        then:
        r.status == want
        r.severity == HealthChecker.Severity.HIGH

        where:
        lines                                                                  | want
        'FAILED_READ=1'                                                        | Status.PASS
        'FAILED_READ=1\nFAILED_UNIT=dailyaidecheck.service'                    | Status.WARN
        'FAILED_READ=1\nFAILED_UNIT=systemd-networkd-wait-online.service'      | Status.WARN
        'FAILED_READ=1\nFAILED_UNIT=smartmontools.service'                     | Status.WARN
        'FAILED_READ=1\nFAILED_UNIT=corosync.service'                          | Status.FAIL
        'FAILED_READ=1\nFAILED_UNIT=dlm.service'                               | Status.FAIL
        'FAILED_READ=1\nFAILED_UNIT=multipathd.service'                        | Status.FAIL
        'FAILED_READ=1\nFAILED_UNIT=iscsid.socket'                             | Status.FAIL
        'FAILED_READ=1\nFAILED_UNIT=open-iscsi.service'                        | Status.FAIL
        'FAILED_READ=1\nFAILED_UNIT=virtqemud.service'                         | Status.FAIL
        'FAILED_READ=1\nFAILED_UNIT=libvirtd.service'                          | Status.FAIL
        'FAILED_READ=1\nFAILED_UNIT=chrony.service'                            | Status.FAIL
        'FAILED_READ=1\nFAILED_UNIT=systemd-timesyncd.service'                 | Status.FAIL
        'FAILED_READ=1\nFAILED_UNIT=morpheus-morphd.service'                   | Status.FAIL
        'FAILED_READ=1\nFAILED_UNIT=dailyaidecheck.service\nFAILED_UNIT=dlm.service' | Status.FAIL
        'FAILED_READ=1\nFAILED_UNIT=corosync-notifyd.service'                  | Status.WARN
        'FAILED_UNIT=dlm.service'                                              | Status.NOT_CHECKED
        ''                                                                     | Status.NOT_CHECKED
    }

    def 'it names the units, and why a critical one matters, and says smartd stops watching the disks'() {
        when:
        HealthChecker.Result fail = only(health('FAILED_READ=1\nFAILED_UNIT=dailyaidecheck.service\nFAILED_UNIT=multipathd.service'), 'HST-09')
        HealthChecker.Result smart = only(health('FAILED_READ=1\nFAILED_UNIT=smartmontools.service'), 'HST-09')

        then:
        fail.observed == 'failed: dailyaidecheck.service, multipathd.service'
        fail.hint.contains('multipathd.service (storage paths)')
        fail.hint.contains('never to be restarted with GFS2 mounted')
        fail.fix == null
        smart.hint.contains('smartd is down')
        smart.hint.contains('HW-07')
    }

    def 'a VM scope unit is counted, never named, as a report carries HST observations'() {
        when:
        HealthChecker.Result r = only(health('FAILED_READ=1\nFAILED_UNIT=machine-qemu\\x2d5\\x2dvm\\x2done.scope'), 'HST-09')

        then:
        r.status == Status.WARN
        r.observed == 'failed: 1 VM scope unit(s)'
        !r.observed.contains('x2d')
    }

    @Unroll
    def 'failedUnitsIgnore #ignore leaves #want, and lists what it ignored'() {
        when:
        HealthChecker.Result r = only(health('FAILED_READ=1\nFAILED_UNIT=dailyaidecheck.service\nFAILED_UNIT=systemd-networkd-wait-online.service',
                "{\"failedUnitsIgnore\":${ignore}}"), 'HST-09')

        then:
        r.status == want
        r.observed.contains('ignored by failedUnitsIgnore') == (ignore != '[]')
        r.source == 'site'

        where:
        ignore                                                           | want
        '[]'                                                             | Status.WARN
        '["dailyaidecheck"]'                                             | Status.WARN
        '["dailyaidecheck.service","systemd-networkd-wait-online"]'      | Status.PASS
    }

    def 'the ignore list can quiet a critical unit too, as the site has judged it'() {
        expect:
        only(health('FAILED_READ=1\nFAILED_UNIT=morpheus-morphd.service', '{"failedUnitsIgnore":["morpheus-morphd"]}'), 'HST-09').status == Status.PASS
    }

    def 'failedUnitsIgnore must be a list of names, and defaults to none'() {
        when:
        Standards.forLayout(Layout.of(Fixture.LAYOUT_20), '{"failedUnitsIgnore":"dailyaidecheck"}')

        then:
        IllegalArgumentException e = thrown()
        e.message.contains('failedUnitsIgnore')

        expect:
        Standards.forLayout(Layout.of(Fixture.LAYOUT_20)).val('failedUnitsIgnore') == []
    }

    def 'the probe reads failed units on every check, time-limited, read-only, and passes only unit-name characters'() {
        given:
        String p = HealthContext.getResourceAsStream('/scripts/health-probe.sh').getText('UTF-8').replace('\r\n', '\n')
        int at = p.indexOf('t 5 systemctl --failed --no-legend --plain')

        expect:
        at > p.indexOf('sec host_health')
        at < p.indexOf('if [ "$MODE" = full ]; then\n  sec journals')
        p.contains('''u = ($1 ~ /\\./) ? $1 : $2; gsub(/[^A-Za-z0-9@._:\\\\-]/, "_", u)''')
        p.contains("echo 'FAILED_READ=1'")
        !('HST-09' in HealthMonitor.FULL_ONLY)
    }

    // ------------------------------------------------------------------ MOR-07

    private static HealthChecker.Input backup(Map b) {
        HealthChecker.Input i = Fixture.healthy()
        i.nowMs = NOW_MS
        i.clusterConfig = [applianceBackup: b]
        i
    }

    private static Map ok(Map over = [:]) {
        [found: true, enabled: true, cron: '0 0 * * 7', lastStatus: 'SUCCEEDED', lastSuccessMs: NOW_MS - 3 * DAY, bucket: 'backups-offsite', bucketLocal: false] + over
    }

    @Unroll
    def 'appliance backup: #why is #want'() {
        when:
        HealthChecker.Result r = only(backup(b), 'MOR-07')

        then:
        r.status == want
        r.host == null
        r.severity == HealthChecker.Severity.HIGH
        (r.hint != null) == (want in [Status.FAIL, Status.WARN])

        where:
        why                                    | b                                                    | want
        'recent, off the appliance'            | ok()                                                 | Status.PASS
        'no such backup'                       | [found: false]                                       | Status.FAIL
        'disabled'                             | ok(enabled: false)                                   | Status.FAIL
        'never succeeded, never run'           | ok(lastSuccessMs: null, lastStatus: null)            | Status.FAIL
        'never succeeded, last run failed'     | ok(lastSuccessMs: null, lastStatus: 'FAILED')        | Status.FAIL
        'weekly, 13 days old'                  | ok(lastSuccessMs: NOW_MS - 13 * DAY)                 | Status.PASS
        'weekly, 15 days old'                  | ok(lastSuccessMs: NOW_MS - 15 * DAY)                 | Status.WARN
        'daily, 3 days old'                    | ok(cron: '0 2 * * *')                                | Status.WARN
        'no schedule read, 10 days old'        | ok(cron: null, lastSuccessMs: NOW_MS - 10 * DAY)     | Status.PASS
        'no schedule read, 20 days old'        | ok(cron: null, lastSuccessMs: NOW_MS - 20 * DAY)     | Status.WARN
        'no bucket'                            | ok(bucket: null)                                     | Status.WARN
        'a local folder on the appliance'      | ok(bucketLocal: true)                                | Status.WARN
        'Morpheus did not answer'              | null                                                 | Status.NOT_CHECKED
    }

    def 'the words say what is wrong, and point at HA-06'() {
        expect:
        only(backup(ok(lastSuccessMs: null, lastStatus: null)), 'MOR-07').observed == 'the Morpheus Appliance backup has never succeeded; it has never run'
        only(backup(ok(lastSuccessMs: null, lastStatus: 'FAILED')), 'MOR-07').observed.endsWith('its last run: FAILED')
        only(backup(ok(bucket: null)), 'MOR-07').observed == 'no storage bucket, so it is written to the appliance\'s own disk'
        only(backup(ok(lastSuccessMs: NOW_MS - 15 * DAY, bucket: null)), 'MOR-07').observed.startsWith('last success 15 days ago, over twice its schedule (0 0 * * 7); no storage bucket')
        only(backup(ok(cron: '0 * * * *', lastSuccessMs: NOW_MS - 3 * 3600000L)), 'MOR-07').observed.startsWith('last success 3 h ago, over twice its schedule')
        only(backup(ok()), 'MOR-07').observed == 'last success 3 days ago, kept off the appliance'
        only(backup(ok(lastSuccessMs: NOW_MS - 5 * 3600000L)), 'MOR-07').observed == 'last success 5 h ago, kept off the appliance'
        only(backup(ok(bucketLocal: true)), 'MOR-07').observed == 'its storage bucket is a local folder on the appliance itself'
        !new HealthChecker().check(backup(ok())).find { it.id == 'MOR-07' }.observed.contains('backups-offsite')
        only(backup([found: false]), 'MOR-07').hint.contains('HA-06')
        CheckCatalog.ENTRIES['MOR-07'].why.contains('HA-06')
    }

    def 'it is read on full checks only, and left out when not read'() {
        expect:
        'MOR-07' in HealthMonitor.FULL_ONLY
        ids(Fixture.healthy(), 'MOR-07') == []
    }

    @Unroll
    def 'cron #cron runs at least every #ms ms'() {
        expect:
        HealthChecker.cronPeriodMs(cron) == ms

        where:
        cron              | ms
        '0 0 * * 7'       | 7 * DAY
        '0 0 * * 0,3'     | 7 * DAY
        '0 2 * * *'       | DAY
        '0 0 1 * *'       | 31 * DAY
        '0 0 0 * * ?'     | DAY
        '0 0 1 1 *'       | 366 * DAY
        '*/15 * * * *'    | 3600000L
        null              | null
        'weekly'          | null
    }

    // ------------------------------------------------------------------ reading it from Morpheus

    private static BackupResult result(String status, long at) {
        BackupResult r = new BackupResult()
        r.status = status
        r.endDate = new Date(at)
        r
    }

    private static Backup applianceBackup(Map over = [:]) {
        Backup b = new Backup()
        b.id = 1L
        b.enabled = true
        b.cronExpression = '0 0 * * 7'
        b.account = new Account(id: 1L)
        over.each { k, v -> b."${k}" = v }
        b
    }

    /** opts: backups (list or a closure that throws), results (closure of the filter), buckets, bucket */
    private HealthContext context(Map opts) {
        def results = Mock(com.morpheusdata.core.synchronous.backup.MorpheusSynchronousBackupResultService)
        results.list(_) >> { args -> (opts.results as Closure).call(args[0].filters) }
        def backups = Mock(com.morpheusdata.core.synchronous.backup.MorpheusSynchronousBackupService)
        backups.getBackupResult() >> results
        backups.list(_) >> { args -> opts.backups instanceof Closure ? (opts.backups as Closure).call() : opts.backups }
        def buckets = Mock(com.morpheusdata.core.synchronous.MorpheusSynchronousStorageBucketService)
        buckets.list(_) >> { opts.buckets ?: [] }
        buckets.get(_) >> opts.bucket
        def clusters = Mock(com.morpheusdata.core.synchronous.compute.MorpheusSynchronousComputeServerGroupService) { get(_) >> new ComputeServerGroup(id: 19L) }
        def services = Mock(MorpheusServices) { getCluster() >> clusters; getBackup() >> backups; getStorageBucket() >> buckets }
        new HealthContext(Mock(MorpheusContext) { getServices() >> services }, 19L)
    }

    def 'the newest success is found among the results, with the default backup bucket when the backup has none'() {
        given:
        StorageBucket other = new StorageBucket(id: 4L, name: 'tenant-bucket', account: new Account(id: 7L))
        StorageBucket mine = new StorageBucket(id: 5L, name: 'offsite', account: new Account(id: 1L))
        Map b = context(backups: [applianceBackup()], buckets: [other, mine],
                results: { f -> [result('SUCCEEDED', NOW_MS - 9 * DAY), result('SUCCEEDED', NOW_MS - 2 * DAY), result('FAILED', NOW_MS - DAY)] }).applianceBackup()

        expect:
        b.found == true
        b.lastSuccessMs == NOW_MS - 2 * DAY
        b.lastStatus == 'FAILED'
        b.bucket == 'offsite'
        b.bucketLocal == false
        b.cron == '0 0 * * 7'
    }

    def 'the backup\'s own bucket wins, and a local one is flagged'() {
        given:
        StorageBucket local = new StorageBucket(id: 6L, name: 'on-box')
        local.providerType = 'local'
        Map b = context(backups: [applianceBackup(storageProvider: new StorageBucket(id: 6L))], bucket: local,
                results: { f -> [result('SUCCEEDED', NOW_MS)] }).applianceBackup()

        expect:
        b.bucket == 'on-box'
        b.bucketLocal == true
    }

    def 'no results and nothing pointed at is never run; no backup record is none found'() {
        expect:
        context(backups: [applianceBackup()], results: { f -> [] }).applianceBackup().with { it.found && it.lastSuccessMs == null && it.lastStatus == null && it.bucket == null }
        context(backups: [], results: { f -> [] }).applianceBackup() == [found: false]
        context(backups: [applianceBackup(active: false)], results: { f -> [] }).applianceBackup() == [found: false]
    }

    def 'a results filter the appliance refuses falls back to the next, and none answering with no last result is unknown, not never'() {
        given:
        List seen = []
        Closure refuseFirst = { f -> seen << f; if (f*.name.contains('backup.id')) { throw new RuntimeException('no such property') }; [result('SUCCEEDED', NOW_MS)] }

        when:
        Map fell = context(backups: [applianceBackup()], results: refuseFirst).applianceBackup()
        Map unknown = context(backups: [applianceBackup()], results: { f -> throw new RuntimeException('no') }).applianceBackup()
        Map pointed = context(backups: [applianceBackup(lastResult: result('SUCCEEDED', NOW_MS - DAY))], results: { f -> throw new RuntimeException('no') }).applianceBackup()
        Map pointedFailed = context(backups: [applianceBackup(lastResult: result('FAILED', NOW_MS - DAY))], results: { f -> [] }).applianceBackup()

        then:
        fell.lastSuccessMs == NOW_MS
        seen.size() == 2
        unknown == null
        pointed.lastSuccessMs == NOW_MS - DAY
        pointedFailed == null
    }

    def 'Morpheus failing to answer is no answer, never a pass or a fail'() {
        expect:
        context(backups: { throw new RuntimeException('no') }, results: { f -> [] }).applianceBackup() == null
    }

    def 'Morpheus members the backup read uses exist'() {
        expect:
        PluginSpec.hasMethod(MorpheusServices, 'getBackup', 0)
        PluginSpec.hasMethod(MorpheusServices, 'getStorageBucket', 0)
        PluginSpec.hasMethod(MorpheusServices.getMethod('getBackup').returnType, 'list', 1)
        PluginSpec.hasMethod(MorpheusServices.getMethod('getBackup').returnType, 'getBackupResult', 0)
        PluginSpec.hasMethod(MorpheusServices.getMethod('getBackup').returnType.getMethod('getBackupResult').returnType, 'list', 1)
        ['list', 'get'].every { PluginSpec.hasMethod(MorpheusServices.getMethod('getStorageBucket').returnType, it, 1) }
        ['active', 'enabled', 'cronExpression', 'backupJob', 'lastResult', 'storageProvider', 'account', 'id'].every { PluginSpec.hasProperty(Backup, it) }
        PluginSpec.hasProperty(com.morpheusdata.model.BackupJob, 'cronExpression')
        ['status', 'endDate', 'startDate', 'dateCreated'].every { PluginSpec.hasProperty(BackupResult, it) }
        ['name', 'bucketName', 'providerType', 'account', 'defaultBackupTarget', 'id'].every { PluginSpec.hasProperty(StorageBucket, it) }
    }
}
