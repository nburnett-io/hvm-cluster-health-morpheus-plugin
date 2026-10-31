package org.hvmhealth

import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.MorpheusServices
import com.morpheusdata.core.Plugin
import com.morpheusdata.core.web.MorpheusWebRequestService
import com.morpheusdata.model.Account
import com.morpheusdata.model.ComputeServerGroup
import com.morpheusdata.model.ComputeServerGroupType
import com.morpheusdata.model.User
import com.morpheusdata.views.ViewModel
import groovy.json.JsonOutput
import io.reactivex.rxjava3.core.Single
import org.hvmhealth.morpheus.HealthAnalyticsProvider
import org.hvmhealth.morpheus.HealthController
import org.hvmhealth.morpheus.HealthMonitor
import org.hvmhealth.morpheus.HealthReport
import spock.lang.Specification

/** The all-clusters page and the report. Master, Acme and Globex run clusters, Initech is a customer on Acme's. */
class PagesSpec extends Specification {
    static final long T0 = 1_800_000_000_000L
    static final Account MASTER = new Account(id: 1L, name: 'Master', masterAccount: true)
    static final Account ACME = new Account(id: 2L, name: 'Acme', masterAccount: false)
    static final Account GLOBEX = new Account(id: 3L, name: 'Globex', masterAccount: false)
    static final Account INITECH = new Account(id: 4L, name: 'Initech', masterAccount: false)
    static final Map<Long, Account> ACCOUNTS = [1L: MASTER, 2L: ACME, 3L: GLOBEX, 4L: INITECH]

    static final ComputeServerGroup CLUSTER = new ComputeServerGroup(id: 27L, name: 'cluster-x', owner: ACME, type: new ComputeServerGroupType(code: 'mvm-cluster'))

    private MorpheusContext morpheus(String settings = '{}') {
        def balancer = Stub(MorpheusContext.getMethod('getAsync').returnType.getMethod('getLoadBalancer').returnType) {
            getAccountById(_) >> { Long id -> ACCOUNTS[id] ? Single.just(ACCOUNTS[id]) : Single.error(new IllegalArgumentException('no such tenant')) }
        }
        def async = Stub(MorpheusContext.getMethod('getAsync').returnType) { getLoadBalancer() >> balancer }
        def clusters = Stub(MorpheusServices.getMethod('getCluster').returnType) {
            get(_) >> CLUSTER
            list(_) >> [CLUSTER]
        }
        def servers = Stub(MorpheusServices.getMethod('getComputeServer').returnType) { list(_) >> [] }
        def services = Stub(MorpheusServices) { getCluster() >> clusters; getComputeServer() >> servers }
        Stub(MorpheusContext) {
            getServices() >> services
            getAsync() >> async
            getSettings(_) >> Single.just(settings)
            getWebRequest() >> Stub(MorpheusWebRequestService) { getNonceToken() >> 'test-nonce' }
        }
    }

    private static ViewModel<Map> asUser(Account a, Map params = [clusterId: '27']) {
        new ViewModel<Map>(object: params, user: new User(id: 7L, username: 'someone', account: a))
    }

    def cleanup() {
        HealthMonitor.LAST.remove(27L)
    }

    // ------------------------------------------------------------------ who reaches the all-clusters page

    def 'the page is flagged master-only to Morpheus'() {
        given:
        def page = new HealthAnalyticsProvider(new ClusterHealthPlugin(), morpheus())

        expect:
        page.masterTenantOnly
        !page.subTenantOnly
        page.code ==~ /[A-Za-z][A-Za-z0-9_-]*/
        HealthAnalyticsProvider.PAGE_URL == '/operations/analytics/' + page.code
    }

    def 'only the master tenant gets the page, and it holds no data even then'() {
        given:
        Plugin plugin = new ClusterHealthPlugin()
        plugin.name = 'HVM Cluster Health'
        plugin.classLoader = ClusterHealthPlugin.classLoader
        def page = new HealthAnalyticsProvider(plugin, morpheus())
        User user = new User(account: tenant)

        when:
        Map data = page.loadData(user, [:]).data as Map
        String html = page.renderTemplate(user, data, [:]).html

        then:
        data.allowed == allowed
        html.contains('data-hh-page="fleet"') == allowed
        html.contains('/health-pages.js') == allowed
        !html.contains('cluster-x')
        // what the content security policy needs for a script to run at all
        !allowed || html.contains('nonce="test-nonce"')
        !allowed || html.contains('/assets/plugin/hvm-cluster-health/health-tab.js')

        where:
        tenant  | allowed
        MASTER  | true
        ACME    | false
        INITECH | false
        null    | false
    }

    def 'a partial master record is recognised on the page too, and a partial tenant record is not promoted'() {
        given:
        def page = new HealthAnalyticsProvider(new ClusterHealthPlugin(), morpheus())

        expect:
        page.loadData(new User(account: new Account(id: 1L)), [:]).data.allowed == true
        page.loadData(new User(account: new Account(id: 2L, masterAccount: true)), [:]).data.allowed == false
    }

    def 'the rows come only from a route the master tenant passes, whatever the role grants'() {
        given:
        def c = new HealthController(new ClusterHealthPlugin(), morpheus('{"operatorTenants":"2,3"}'))

        when:
        def r = c.fleet(asUser(tenant, [:]))

        then:
        r.status == status
        (r.data.data*.name == ['cluster-x']) == (status == 200)
        !JsonOutput.toJson(r.data).contains('cluster-x') || status == 200

        where:
        tenant  | status
        MASTER  | 200
        ACME    | 403
        GLOBEX  | 403
        INITECH | 403
        null    | 403
    }

    def 'the fleet route needs the role permission'() {
        expect:
        new HealthController(null, null).routes.find { it.method == 'fleet' }.permissions*.code == [HealthController.PERM]
        new HealthController(null, null).routes.find { it.method == 'report' }.permissions*.code == [HealthController.PERM]
    }

    def 'the master tenant alone gets the link to the page from the tab'() {
        given:
        def c = new HealthController(new ClusterHealthPlugin(), morpheus())

        expect:
        c.status(asUser(MASTER)).data.data.allClustersUrl == HealthAnalyticsProvider.PAGE_URL
        c.status(asUser(ACME)).data.data.allClustersUrl == null
    }

    def 'with nothing stored and no check yet, a cluster reads as not checked, never as healthy'() {
        when:
        def rows = HealthMonitor.fleet(morpheus())

        then:
        rows[0].overall == 'NOT CHECKED'
        rows[0].lastKnown == false
    }

    // ------------------------------------------------------------------ who gets the report

    def 'the report goes to the cluster operators only'() {
        given:
        def c = new HealthController(new ClusterHealthPlugin(), morpheus(settings))

        when:
        def r = c.report(asUser(tenant))

        then:
        r.status == status
        (r.status == 200) == (r.data.data != null)

        where:
        tenant  | settings                     | status
        MASTER  | '{}'                         | 200
        ACME    | '{}'                         | 200
        GLOBEX  | '{}'                         | 403
        GLOBEX  | '{"operatorTenants":"3"}'    | 200
        INITECH | '{}'                         | 403
        null    | '{}'                         | 403
    }

    private static Map lastView() {
        [overall: 'DEGRADED', at: T0, mode: 'quick', layout: 'HVM 2.0', counts: [fail: 2, warn: 1, pass: 30], readiness: [], hosts: [],
         results: [
                 [id: 'VM-02', title: 'No VM stuck on its disk', severity: 'CRITICAL', status: 'FAIL', host: 'hvm-node01', observed: 'initech-db1, initech-db2', expected: 'none in D state', source: 'plugin', fix: 'x', tier: 'NONE', hint: 'Initech is down', impact: '2 running VM(s): Initech (2)', firstSeen: T0],
                 [id: 'NET-11', title: 'VLAN 300 (initech-prod) carries traffic on every host', severity: 'MEDIUM', status: 'WARN', host: null, observed: 'no traffic on hvm-node02', expected: 'heard on every host', source: 'plugin', impact: '5 running VM(s): Initech (2), Globex (3)'],
                 [id: 'MOR-01', title: 'Morpheus host status matches the host', severity: 'LOW', status: 'WARN', host: 'hvm-node02', observed: '=HYPERLINK("http://evil.invalid") globex-web1 fenced', expected: 'no fence status', source: 'plugin'],
                 [id: 'CLU-03', title: 'Quorate', severity: 'CRITICAL', status: 'PASS', host: 'hvm-node02', observed: 'yes', expected: 'yes', source: 'plugin'],
         ]]
    }

    private static Map doc() {
        [v: 1, since: T0 - 100_000L, dropped: 2, summary: [overall: 'WARNING', at: T0 - 50_000L, counts: [fail: 0, warn: 1, pass: 3], mode: 'full'],
         episodes: [[k: 'aaa', id: 'NET-01', title: 'Bond members up: bond0', host: 'hvm-node01', severity: 'HIGH', status: 'FAIL', worst: 'FAIL', first: T0 - 90_000L, last: T0 - 80_000L, cleared: T0 - 70_000L, returns: 1],
                    [k: 'bbb', id: 'STO-01', title: 'Multipath paths healthy: 36000', host: 'hvm-node02', severity: 'HIGH', status: 'WARN', worst: 'FAIL', first: T0 - 60_000L, last: T0, cleared: null, returns: 0]]]
    }

    static final List<String> NAMES = ['initech-db1', 'initech-db2', 'Initech', 'Globex', 'globex-web1', 'initech-prod']

    def 'the report holds no tenant data, in any format'() {
        when:
        Map r = HealthReport.build([id: 27L, name: 'cluster-x', layout: 'HVM 2.0'], lastView(), doc(), null, NAMES, T0)
        String json = JsonOutput.toJson(r).toLowerCase()

        then:
        !json.contains('initech')
        !json.contains('globex')
        !json.contains('running vm')
        !r.current.any { it.containsKey('impact') || it.containsKey('hint') || it.containsKey('fix') }
        r.current.find { it.check == 'VM-02' }.observed.startsWith('withheld')
        r.current.find { it.check == 'NET-11' }.title == 'VLAN 300 carries traffic on every host'
        r.current.find { it.check == 'MOR-01' }.observed.contains(HealthReport.WITHHELD)
    }

    def 'observed text of a finding that lists VMs is withheld even when Morpheus does not know the VM'() {
        when:
        Map r = HealthReport.build([id: 27L, name: 'cluster-x'], lastView(), null, null, [], T0)

        then:
        !JsonOutput.toJson(r.current.find { it.check == 'VM-02' }).contains('initech-db1')
    }

    def 'the report has current findings with first seen, and the history'() {
        when:
        Map r = HealthReport.build([id: 27L, name: 'cluster-x', layout: 'HVM 2.0'], lastView(), doc(), null, NAMES, T0)

        then:
        r.overall == 'DEGRADED'
        r.checkedSinceLoad
        r.current*.check == ['VM-02', 'NET-11', 'MOR-01']
        r.current[0].firstSeen == HealthReport.iso(T0)
        r.history*.check == ['STO-01', 'NET-01']
        r.history[1].cleared == HealthReport.iso(T0 - 70_000L)
        r.history[1].returned == 1
        r.history[0].cleared == null
        r.historyDropped == 2
        r.retentionDays == 365
        r.fileBase == "cluster-health-cluster-x-${HealthReport.iso(T0).take(10)}".toString()
    }

    def 'with no check since the plugin loaded the report says so, and lists what is still open from the history'() {
        when:
        Map r = HealthReport.build([id: 27L, name: 'cluster-x'], null, doc(), null, NAMES, T0)

        then:
        !r.checkedSinceLoad
        r.overall == 'WARNING'
        r.current*.check == ['STO-01']
        r.current[0].observed == null
    }

    def 'with no history at all it still builds, and reads as not checked'() {
        when:
        Map r = HealthReport.build([id: 27L, name: 'cluster-x'], null, null, 'store unavailable', [], T0)

        then:
        r.overall == 'NOT CHECKED'
        r.current == []
        r.history == []
        r.historyError == 'store unavailable'
    }

    def 'CSV: one header, one row each, quoted, and a formula cannot run'() {
        when:
        Map r = HealthReport.build([id: 27L, name: 'cluster, "x"'], lastView(), doc(), null, NAMES, T0)
        List<String> lines = (r.csv as String).split('\r\n') as List

        then:
        lines[0] == HealthReport.CSV_COLUMNS.join(',')
        lines.size() == 1 + 3 + 2
        lines[1].startsWith('"cluster, ""x""",')
        lines.find { it.contains('MOR-01') }.contains('"\'=HYPERLINK(""http://evil.invalid"")')
    }

    def 'CSV cells that start a formula are defused'() {
        expect:
        HealthReport.cell(value) == want

        where:
        value       | want
        null        | ''
        'plain'     | 'plain'
        '=1+1'      | "'=1+1"
        '+1'        | "'+1"
        '-1'        | "'-1"
        '@x'        | "'@x"
        'a,b'       | '"a,b"'
        'a"b'       | '"a""b"'
        'a\nb'      | '"a\nb"'
        12          | '12'
    }

    def 'names are withheld whole: a name inside a longer word stays, and case does not matter'() {
        given:
        Closure<String> scrub = HealthReport.scrubber(['acme', 'acme-prod', 'db', null, ''])

        expect:
        scrub('ACME-prod and Acme') == '[withheld] and [withheld]'
        scrub('acmeish') == 'acmeish'
        scrub('db stays: too short to tell from a word') == 'db stays: too short to tell from a word'
        scrub(null) == null
    }

    def 'regex characters in a name are taken literally'() {
        expect:
        HealthReport.scrubber(['vm(1)$x'])('saw vm(1)$x here') == 'saw [withheld] here'
    }

    def 'if the names to withhold cannot be read, the report fails rather than risk one'() {
        given:
        CLUSTER.cloud = new com.morpheusdata.model.Cloud(id: 5L)
        def c = new HealthController(new ClusterHealthPlugin(), failingVms())

        when:
        def r = c.report(asUser(MASTER))

        then:
        r.status != 200
        r.data.data == null

        cleanup:
        CLUSTER.cloud = null
    }

    private MorpheusContext failingVms() {
        def balancer = Stub(MorpheusContext.getMethod('getAsync').returnType.getMethod('getLoadBalancer').returnType) { getAccountById(_) >> { Long id -> Single.just(ACCOUNTS[id]) } }
        def async = Stub(MorpheusContext.getMethod('getAsync').returnType) { getLoadBalancer() >> balancer }
        def clusters = Stub(MorpheusServices.getMethod('getCluster').returnType) { get(_) >> CLUSTER }
        def servers = Stub(MorpheusServices.getMethod('getComputeServer').returnType) { list(_) >> { throw new IllegalStateException('cannot list') } }
        def services = Stub(MorpheusServices) { getCluster() >> clusters; getComputeServer() >> servers }
        Stub(MorpheusContext) { getServices() >> services; getAsync() >> async; getSettings(_) >> Single.just('{}') }
    }
}
