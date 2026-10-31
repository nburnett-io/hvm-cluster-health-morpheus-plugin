package org.hvmhealth

import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.MorpheusServices
import com.morpheusdata.model.Account
import com.morpheusdata.model.ComputeServer
import com.morpheusdata.model.ComputeServerGroup
import com.morpheusdata.model.ComputeServerGroupType
import com.morpheusdata.model.Network
import com.morpheusdata.model.OperationNotification
import com.morpheusdata.model.TaskResult
import com.morpheusdata.model.User
import com.morpheusdata.response.ServiceResponse
import com.morpheusdata.views.ViewModel
import groovy.json.JsonOutput
import io.reactivex.rxjava3.core.Single
import org.hvmhealth.checks.CheckCatalog
import org.hvmhealth.checks.HealthChecker
import org.hvmhealth.morpheus.HealthContext
import org.hvmhealth.morpheus.HealthController
import org.hvmhealth.morpheus.HealthMonitor
import org.hvmhealth.morpheus.HealthReport
import org.hvmhealth.morpheus.HostOps
import org.hvmhealth.morpheus.Jobs
import spock.lang.Specification
import spock.lang.Unroll
import spock.util.concurrent.PollingConditions

import java.lang.reflect.InvocationHandler
import java.lang.reflect.Proxy
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Every route and the automatic pass, through the controller and the monitor, on a Morpheus that records what it was asked. */
class RoutesSpec extends Specification {
    static final Account MASTER = new Account(id: 1L, name: 'Master', masterAccount: true)
    static final Account ACME = new Account(id: 2L, name: 'Acme', masterAccount: false)
    static final Account INITECH = new Account(id: 4L, name: 'Initech', masterAccount: false)
    static final Map<Long, Account> ACCOUNTS = [1L: MASTER, 2L: ACME, 4L: INITECH]

    ComputeServerGroup cluster = new ComputeServerGroup(id: 27L, name: 'cluster-x', owner: ACME, type: new ComputeServerGroupType(code: 'mvm-cluster'))
    List<ComputeServerGroup> clusters = [cluster]
    List<ComputeServer> hosts = (1..3).collect { new ComputeServer(id: 100L + it, name: "hvm-node0${it}".toString()) }
    String settings = '{}'
    String sample = Fixture.text('probe-healthy').replace(' 5    vm-one   running', '')
    List<String> ran = Collections.synchronizedList([])         // each script a host was asked to run, decoded
    List<String> audited = Collections.synchronizedList([])
    List<String> order = Collections.synchronizedList([])       // the unmount and Morpheus's remove, as they happened
    List<OperationNotification> alarms = []
    List<OperationNotification> created = Collections.synchronizedList([])
    List<OperationNotification> cleared = Collections.synchronizedList([])
    Closure<TaskResult> onScript = { String script -> new TaskResult(success: true, exitCode: '0', output: sample) }
    Closure<ServiceResponse> onRemove = { ServiceResponse.error('Morpheus says no') }
    Closure<List<Network>> onNetworks = { [] }

    private static String decode(String command) {
        new String(command.find(/echo '([^']+)' \| base64/) { all, g -> g }.decodeBase64(), 'UTF-8')
    }

    private MorpheusContext morpheus() {
        RoutesSpec self = this   // the stubs below would otherwise look for these on themselves first
        def balancer = Stub(MorpheusContext.getMethod('getAsync').returnType.getMethod('getLoadBalancer').returnType) {
            getAccountById(_) >> { Long id -> ACCOUNTS[id] ? Single.just(ACCOUNTS[id]) : Single.error(new IllegalArgumentException('no such tenant')) }
        }
        def async = Stub(MorpheusContext.getMethod('getAsync').returnType) { getLoadBalancer() >> balancer }
        def clusterService = Stub(MorpheusServices.getMethod('getCluster').returnType) {
            get(_) >> { Long id -> self.clusters.find { it.id == id } }
            list(_) >> { self.clusters }
        }
        def servers = Stub(MorpheusServices.getMethod('getComputeServer').returnType) {
            list(_) >> { self.hosts }
            get(_) >> { Long id -> self.hosts.find { it.id == id } }
            removeHost(_, _) >> { args -> self.order << 'remove'; self.onRemove.call() }
        }
        def networks = Stub(MorpheusServices.getMethod('getNetwork').returnType) { list(_) >> { self.onNetworks.call() } }
        def auditLog = Stub(MorpheusServices.getMethod('getAdmin').returnType.getMethod('getAuditLog').returnType) {
            log(_, _) >> { args -> self.audited << args[1].description; args[1] }
        }
        def admin = Stub(MorpheusServices.getMethod('getAdmin').returnType) { getAuditLog() >> auditLog }
        def notifications = Stub(MorpheusServices.getMethod('getOperationNotification').returnType) {
            list(_) >> { self.alarms }
            createAlarm(_) >> { args -> self.created << args[0]; ServiceResponse.success() }
            clearAlarm(_) >> { args -> self.cleared << args[0]; ServiceResponse.success() }
        }
        def services = Stub(MorpheusServices) {
            getCluster() >> clusterService
            getComputeServer() >> servers
            getNetwork() >> networks
            getAdmin() >> admin
            getOperationNotification() >> notifications
        }
        Stub(MorpheusContext) {
            getServices() >> services
            getAsync() >> async
            getSettings(_) >> { Single.just(self.settings) }
            executeCommandOnServer(_, _) >> { args ->
                String script = RoutesSpec.decode(args[1] as String)
                self.ran << script
                if (script.contains('umount "$m"')) {
                    self.order << 'unmount'
                }
                // when it is subscribed to, outside the stub: Spock takes one call at a time, so a stub that blocks would hold every other thread
                Single.fromCallable { self.onScript.call(script) }
            }
        }
    }

    private HealthController controller() {
        new HealthController(new ClusterHealthPlugin(), morpheus())
    }

    /** jakarta.servlet is on the appliance and in the test runtime, not on the compile path, as the plugin only reads the verb. */
    private static Object request(String verb) {
        Class c = Class.forName('jakarta.servlet.http.HttpServletRequest')
        Proxy.newProxyInstance(c.classLoader, [c] as Class[], { p, m, a -> m.name == 'getMethod' ? verb : null } as InvocationHandler)
    }

    private static ViewModel<Map> view(Account a, String verb, Map params = [:]) {
        ViewModel<Map> m = new ViewModel<Map>(object: [clusterId: '27'] + params, user: new User(id: 7L, username: 'someone', account: a))
        m.request = request(verb)
        m
    }

    private static Jobs.Job finished(Object response) {
        Jobs.Job j = Jobs.get(response.data.data.id as String)
        new PollingConditions(timeout: 20).eventually { assert j.state != 'running' }
        j
    }

    def setup() {
        HostOps.pollMs = 1L
    }

    def cleanup() {
        HostOps.pollMs = 8000L
        HealthMonitor.stop()
        clusters.each { HealthMonitor.LAST.remove(it.id) }
    }

    // ------------------------------------------------------------------ who gets in, and what a refusal gives away

    @Unroll
    def 'a customer whose role grants everything is refused #route by #verb, and nothing runs or is recorded'() {
        given:
        def c = controller()

        when:
        def r = c."${route}"(view(INITECH, verb, [host: 'hvm-node01', key: 'service-enable', nodeId: '1', confirm: 'hvm-node01', attest: 'on', servers: '192.0.2.1']))

        then:
        r.status == 403
        !JsonOutput.toJson(r.data).contains('cluster-x')
        ran == []
        audited == []
        created == []
        Jobs.forCluster(27L).every { it.state != 'running' }

        where:
        [route, verb] << [['status', 'catalog', 'check', 'raw', 'fix', 'timeSource', 'preflight', 'fleet', 'report', 'vlanTest', 'removeHost', 'fencePlan', 'fenceAck'], ['GET', 'POST']].combinations()
    }

    def 'an operator gets the catalog, which says nothing about the cluster and changes nothing'() {
        given:
        def c = controller()

        when:
        def r = c.catalog(view(ACME, 'GET'))
        Map data = r.data.data as Map
        String json = JsonOutput.toJson(r.data)

        then:
        r.status == 200
        data.checks*.id as Set == CheckCatalog.ENTRIES.keySet()
        data.checks[0].keySet() == ['id', 'area', 'name', 'what', 'why', 'action'] as Set
        !json.contains('cluster-x')
        !hosts.any { json.contains(it.name) }
        ran == []
        audited == []
    }

    def 'a customer asking about a job on someone else cluster is refused too'() {
        given:
        Jobs.Job j = Jobs.start('t', 27L, 'someone') { List<String> p -> null }

        when:
        def r = controller().job(view(INITECH, 'GET', [id: j.id]))

        then:
        r.status == 403
    }

    def 'a cluster that does not exist gets the same answer as one that is not yours, so ids cannot be tried'() {
        given:
        def c = controller()

        when:
        def missing = c.status(view(INITECH, 'GET', [clusterId: '999']))
        def theirs = c.status(view(INITECH, 'GET', [clusterId: '27']))

        then:
        missing.status == 403
        theirs.status == 403
        missing.data.msg == theirs.data.msg
        audited == []
    }

    // ------------------------------------------------------------------ what changes something answers POST only

    @Unroll
    def 'an operator following a link does not reach #route: a GET has no CSRF token'() {
        when:
        def r = controller()."${route}"(view(MASTER, 'GET', [host: 'hvm-node01', key: 'service-enable', nodeId: '1', confirm: 'hvm-node01', attest: 'on', servers: '192.0.2.1']))

        then:
        r.status == 403
        ran == []
        audited == []
        !cluster.getConfigProperty(HealthContext.CFG_TIME_SOURCES)

        where:
        route << ['fix', 'timeSource', 'vlanTest', 'removeHost', 'fenceAck']
    }

    def 'a request Morpheus does not describe is refused too'() {
        given:
        ViewModel<Map> m = new ViewModel<Map>(object: [clusterId: '27', host: 'hvm-node01', key: 'service-enable'], user: new User(id: 7L, username: 'someone', account: MASTER))

        expect:
        controller().fix(m).status == 403
        ran == []
    }

    def 'the routes the tab reads with a GET still answer one, and what it sends by POST is accepted'() {
        given:
        def c = controller()

        expect:
        c.status(view(ACME, 'GET')).status == 200
        c.fencePlan(view(ACME, 'GET', [nodeId: '1'])).status == 200

        and:
        finished(c.fix(view(ACME, 'POST', [host: 'hvm-node02', key: 'service-enable']))).kind == 'fix: service-enable'
    }

    // ------------------------------------------------------------------ answering before the proxy gives up

    def 'the emergency plan and raw output probe within the time the browser waits, with the probe told to stop in time'() {
        given:
        def c = controller()

        when:
        c.fencePlan(view(ACME, 'GET', [nodeId: '1']))
        c.raw(view(ACME, 'GET', [host: 'hvm-node01']))

        then:
        ran.every { it.startsWith('set -- ') }
        ran.findAll { it.startsWith('set -- quick 60\n') }.size() == 3
        ran.findAll { it.startsWith('set -- full 60\n') }.size() == 1
        HealthContext.INTERACTIVE_PROBE_TIMEOUT_SEC + 10 < 120
    }

    def 'a host stuck inside the agent call does not hold the whole answer: the wait has a limit of its own'() {
        given:
        MorpheusContext m = morpheus()
        HealthContext ctx = new HealthContext(m, 27L) {
            @Override
            Map run(ComputeServer host, String script, long timeoutSec) {
                if (host.name == 'hvm-node02') {
                    new CountDownLatch(1).await(60, TimeUnit.SECONDS)   // ignores the limit it was given, as a call that blocks before its timer starts would
                }
                [exit: 0, output: sample]
            }
        }

        when:
        long began = System.currentTimeMillis()
        Map<String, org.hvmhealth.model.HostFacts> facts = ctx.probe('quick', 1L)

        then:
        System.currentTimeMillis() - began < 30_000L
        facts['hvm-node01'].complete
        !facts['hvm-node02'].complete
    }

    // ------------------------------------------------------------------ an emergency, through the route

    private void frozenCluster() {
        hosts = hosts.findAll { it.name != 'hvm-node01' }
        sample = Fixture.text('probe-frozen')
    }

    def 'a fence acknowledgement runs only on a typed name, and names that node on one survivor'() {
        given:
        frozenCluster()

        when:
        Jobs.Job j = finished(controller().fenceAck(view(ACME, 'POST', [nodeId: '1', confirm: 'hvm-node01', attest: 'on'])))

        then:
        j.state == 'done'
        ran.count { it.contains('dlm_tool fence_ack 1') } == 1
        audited.any { it.contains('fence acknowledgement started') }
        audited.any { it.contains('lockspaces recovered') }
    }

    def 'with the wrong name typed, or nothing attested, no acknowledgement is run'() {
        given:
        frozenCluster()

        when:
        Jobs.Job j = finished(controller().fenceAck(view(ACME, 'POST', params)))

        then:
        j.state == 'failed'
        j.error.contains(says)
        !ran.any { it.contains('dlm_tool fence_ack 1') }

        where:
        params                                       | says
        [nodeId: '1', confirm: 'hvm-node03', attest: 'on'] | 'Type the node name'
        [nodeId: '1', confirm: 'hvm-node01']         | 'State that it is down'
    }

    def 'a host that ran out of time still gets the freeze named and the plan offered, with nothing proven'() {
        given:
        frozenCluster()
        sample = sample.replace('COMPLETE=1', 'CUT=1\nCOMPLETE=1')

        when:
        def r = controller().fencePlan(view(ACME, 'GET', [nodeId: '1']))

        then:
        r.status == 200
        r.data.data.blockers == []
        r.data.data.proven == false
        r.data.data.runOn == 'hvm-node02'
    }

    // ------------------------------------------------------------------ removing a host

    private void removable() {
        hosts.find { it.name == 'hvm-node02' }.status = 'maintenance'
    }

    def 'the unmount comes first, then Morpheus, and a refusal says GFS2 is already unmounted'() {
        given:
        removable()

        when:
        Jobs.Job j = finished(controller().removeHost(view(ACME, 'POST', [host: 'hvm-node02', confirm: 'hvm-node02'])))

        then:
        order == ['unmount', 'remove']
        j.state == 'failed'
        j.error.contains('Morpheus says no')
        j.error.contains('GFS2 unmounted')
        j.error.contains('reboot it in maintenance mode')
    }

    def 'Morpheus throwing says so as well'() {
        given:
        removable()
        onRemove = { throw new IllegalStateException('it broke') }

        when:
        Jobs.Job j = finished(controller().removeHost(view(ACME, 'POST', [host: 'hvm-node02', confirm: 'hvm-node02'])))

        then:
        j.error.contains('it broke')
        j.error.contains('GFS2 unmounted')
    }

    def 'when the unmount fails Morpheus is never asked, and what it says fits how it failed'() {
        given:
        removable()
        onScript = { String script -> new TaskResult(success: !script.contains('umount "$m"'), exitCode: script.contains('umount "$m"') ? code : '0', output: script.contains('umount "$m"') ? 'no' : sample) }

        when:
        Jobs.Job j = finished(controller().removeHost(view(ACME, 'POST', [host: 'hvm-node02', confirm: 'hvm-node02'])))

        then:
        order == ['unmount']
        j.error.contains(says)

        where:
        code | says
        '3'  | 'Nothing was changed'
        '4'  | 'its pool started again'
        '6'  | 'clears a hung unmount'
    }

    def 'with a typo in the confirmation nothing is touched'() {
        given:
        removable()

        when:
        def r = controller().removeHost(view(ACME, 'POST', [host: 'hvm-node02', confirm: 'hvm-node03']))

        then:
        r.status == 400
        order == []
    }

    // ------------------------------------------------------------------ the time source, and the VLAN test's single entry

    def 'a busy cluster saves no time source, rather than say busy after saving'() {
        given:
        CountDownLatch hold = new CountDownLatch(1)
        Jobs.start('held', 27L, 'someone') { List<String> p -> hold.await(20, TimeUnit.SECONDS) }

        when:
        def r = controller().timeSource(view(ACME, 'POST', [servers: '192.0.2.1']))

        then:
        r.status == 400
        r.data.msg.contains('nothing was saved')
        !cluster.getConfigProperty(HealthContext.CFG_TIME_SOURCES)

        cleanup:
        hold.countDown()
        new PollingConditions(timeout: 5).eventually { assert !Jobs.busy(27L) }
    }

    // ------------------------------------------------------------------ the report and what it withholds

    def 'a tenant that owns a network but has no VM here is withheld from the report'() {
        given:
        cluster.cloud = new com.morpheusdata.model.Cloud(id: 5L)
        onNetworks = { [new Network(id: 1L, name: 'globex-net', owner: new Account(id: 3L, name: 'Globex'))] }
        Map last = [overall: 'WARNING', at: 1L, mode: 'quick', counts: [fail: 0, warn: 1, pass: 0], readiness: [], hosts: [],
                    results: [[id: 'LAY-01', title: 'Cluster layout recognised', severity: 'LOW', status: 'WARN', host: null, observed: 'seen by Globex on globex-net', expected: 'x', source: 'plugin']]]
        HealthMonitor.LAST[27L] = last

        when:
        def r = controller().report(view(ACME, 'GET'))

        then:
        r.status == 200
        !JsonOutput.toJson(r.data).contains('Globex')
        !JsonOutput.toJson(r.data).contains('globex-net')
    }

    def 'if networks cannot be read, the report fails rather than print a name'() {
        given:
        cluster.cloud = new com.morpheusdata.model.Cloud(id: 5L)
        onNetworks = { throw new IllegalStateException('cannot list') }

        when:
        def r = controller().report(view(ACME, 'GET'))

        then:
        r.status != 200
        r.data.data == null
    }

    def 'checks still run when networks cannot be read: only the report is that strict'() {
        given:
        cluster.cloud = new com.morpheusdata.model.Cloud(id: 5L)
        onNetworks = { throw new IllegalStateException('cannot list') }

        expect:
        new HealthContext(morpheus(), 27L).tenantNetworks() == []
    }

    def 'findings that list VMs are withheld, whatever Morpheus knows'() {
        given:
        Map last = [overall: 'WARNING', at: 1L, mode: 'quick', counts: [fail: 0, warn: 1, pass: 0], readiness: [], hosts: [],
                    results: [[id: id, title: 'VM port on the VLAN Morpheus says: unknown-vm', severity: 'HIGH', status: 'WARN', host: 'hvm-node01', observed: 'unknown-vm is here', expected: 'x', source: 'plugin']]]

        when:
        Map r = HealthReport.build([id: 27L, name: 'cluster-x'], last, null, null, [], 2L)

        then:
        r.current[0].observed.startsWith('withheld')

        where:
        id << ['MIG-06', 'MIG-07', 'NET-12', 'NET-13']
    }

    // ------------------------------------------------------------------ alarms

    private static HealthChecker.Result finding(String observed, HealthChecker.Status status = HealthChecker.Status.FAIL) {
        new HealthChecker.Result('MOR-03', 'Morpheus agent checking in', HealthChecker.Severity.HIGH, status, 'hvm-node01', observed, 'soon', 'plugin')
    }

    private OperationNotification openAlarmFor(HealthChecker.Result r, String status) {
        Map a = HealthMonitor.alarmsFor(27L, 'cluster-x', [new HealthChecker.Result(r.id, r.title, r.severity, HealthChecker.Status.FAIL, r.host, r.observed, r.expected, r.source)])[0]
        new OperationNotification(uniqueId: a.uniqueId as String, status: status, statusMessage: 'as it was raised', active: true)
    }

    def 'an alarm whose text changes stays as it is, keeping its acknowledgement and start time'() {
        given:
        cluster.owner = MASTER
        alarms = [openAlarmFor(finding('last 900 s ago'), 'error')]

        when:
        HealthMonitor.setAlarms(morpheus(), new HealthContext(morpheus(), 27L), [finding('last 960 s ago')])

        then:
        cleared == []
        created == []
    }

    def 'an alarm whose severity changes is raised again at the new one'() {
        given:
        cluster.owner = MASTER
        alarms = [openAlarmFor(finding('x'), 'error')]

        when:
        HealthMonitor.setAlarms(morpheus(), new HealthContext(morpheus(), 27L), [finding('x', HealthChecker.Status.WARN)])

        then:
        cleared*.uniqueId == alarms*.uniqueId
        created*.status == ['warning']
    }

    def 'a finding that clears takes its alarm with it, and one that could not be judged keeps it'() {
        given:
        cluster.owner = MASTER
        alarms = [openAlarmFor(finding('x'), 'error')]
        MorpheusContext m = morpheus()

        when:
        HealthMonitor.setAlarms(m, new HealthContext(m, 27L), [finding('cannot tell', HealthChecker.Status.NOT_CHECKED)])

        then:
        cleared == []

        when:
        HealthMonitor.setAlarms(m, new HealthContext(m, 27L), [finding('fine', HealthChecker.Status.PASS)])

        then:
        cleared.size() == 1
    }

    def 'the master tenant copy of a finding that could not be judged stays too'() {
        given:
        OperationNotification tenants = openAlarmFor(finding('x'), 'error')
        alarms = [new OperationNotification(uniqueId: "${tenants.uniqueId}-master".toString(), status: 'error', active: true), tenants]
        MorpheusContext m = morpheus()

        when:
        HealthMonitor.setAlarms(m, new HealthContext(m, 27L), [finding('cannot tell', HealthChecker.Status.NOT_CHECKED)])

        then:
        cleared == []
    }

    def 'alarms of a cluster that has gone are cleared, and those of a live one and others stay'() {
        given:
        OperationNotification live = new OperationNotification(uniqueId: 'hvm-cluster-health-27-MOR-03-abc', status: 'error', active: true)
        OperationNotification gone = new OperationNotification(uniqueId: 'hvm-cluster-health-99-MOR-03-abc', status: 'error', active: true)
        OperationNotification legacy = new OperationNotification(uniqueId: 'hvm-cluster-health-98', status: 'error', active: true)
        OperationNotification closed = new OperationNotification(uniqueId: 'hvm-cluster-health-97-X', status: 'error', active: false)
        alarms = [live, gone, legacy, closed]

        when:
        HealthMonitor.clearOrphanAlarms(morpheus(), true)

        then:
        cleared as Set == [gone, legacy] as Set
    }

    def 'with automatic checks off nothing can update an alarm again, so all of them are cleared'() {
        given:
        alarms = [new OperationNotification(uniqueId: 'hvm-cluster-health-27-MOR-03-abc', status: 'error', active: true)]

        when:
        HealthMonitor.clearOrphanAlarms(morpheus(), false)

        then:
        cleared == alarms
    }

    def 'Morpheus listing no clusters clears nothing'() {
        given:
        clusters = []
        alarms = [new OperationNotification(uniqueId: 'hvm-cluster-health-99-MOR-03-abc', status: 'error', active: true)]

        when:
        HealthMonitor.clearOrphanAlarms(morpheus(), true)

        then:
        cleared == []
    }

    // ------------------------------------------------------------------ the automatic pass

    def 'a typo in the site standards raises its alarm, and checking carries on with the built-in standards'() {
        given:
        settings = '{"siteStandards":"{not json"}'
        MorpheusContext m = morpheus()
        HealthMonitor.start(m, new ClusterHealthPlugin())

        when:
        HealthMonitor.sweep(m, new ClusterHealthPlugin())
        new PollingConditions(timeout: 10).eventually { assert created.any { it.name.startsWith('PRB-03') } }

        then:
        created.find { it.name.startsWith('PRB-03') }.statusMessage.contains('not valid JSON')
        HealthMonitor.LAST.containsKey(27L)
        !HealthMonitor.COULD_NOT_RUN.containsKey(27L)

        cleanup:
        HealthMonitor.LAST.remove(27L)
    }

    def 'the next check that runs clears that alarm'() {
        given:
        Map a = HealthMonitor.alarmsFor(27L, 'cluster-x', [new HealthChecker.Result('PRB-02', 'Automatic check ran', HealthChecker.Severity.HIGH, HealthChecker.Status.FAIL, null, 'x', 'y', 'plugin')])[0]
        alarms = [new OperationNotification(uniqueId: a.uniqueId as String, status: 'error', active: true)]
        MorpheusContext m = morpheus()

        when:
        HealthMonitor.run(m, new HealthContext(m, 27L), [:], 'quick', false)

        then:
        cleared*.uniqueId == [a.uniqueId]
    }

    def 'at most four clusters are checked at once, the rest still get their turn, and none is queued twice'() {
        given:
        clusters = (1..6).collect { new ComputeServerGroup(id: 500L + it, name: "cluster-${it}".toString(), owner: MASTER, type: new ComputeServerGroupType(code: 'mvm-cluster')) }
        hosts = hosts.take(1)
        CountDownLatch release = new CountDownLatch(1)
        AtomicInteger started = new AtomicInteger()
        onScript = { String script ->
            started.incrementAndGet()
            release.await(30, TimeUnit.SECONDS)
            new TaskResult(success: true, exitCode: '0', output: sample)
        }
        MorpheusContext m = morpheus()
        HealthMonitor.start(m, new ClusterHealthPlugin())

        when: 'all four checkers are held on a silent host, and the next pass comes round'
        HealthMonitor.sweep(m, new ClusterHealthPlugin())
        new PollingConditions(timeout: 10).eventually { assert started.get() == 4 }
        HealthMonitor.sweep(m, new ClusterHealthPlugin())
        Thread.sleep(300)

        then:
        started.get() == 4

        when:
        release.countDown()

        then:
        new PollingConditions(timeout: 20).eventually { assert clusters.every { HealthMonitor.LAST.containsKey(it.id) } }
        started.get() == 6
    }

    def 'same-named hosts in two clusters do not share the retry'() {
        given:
        HealthChecker.Result r = finding('x')
        r.fix = 'service-enable'
        r.tier = HealthChecker.Tier.SAFE

        expect:
        HealthMonitor.attemptKey(27L, r) != HealthMonitor.attemptKey(28L, r)
    }
}
