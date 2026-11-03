package org.hvmhealth

import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.MorpheusServices
import com.morpheusdata.model.Account
import com.morpheusdata.model.ComputeServer
import com.morpheusdata.model.ComputeServerGroup
import com.morpheusdata.model.ComputeServerGroupType
import com.morpheusdata.model.Network
import com.morpheusdata.model.OperationData
import com.morpheusdata.model.OperationNotification
import com.morpheusdata.model.TaskResult
import com.morpheusdata.model.User
import com.morpheusdata.response.ServiceResponse
import com.morpheusdata.views.ViewModel
import groovy.json.JsonOutput
import io.reactivex.rxjava3.core.Single
import org.hvmhealth.checks.CheckCatalog
import org.hvmhealth.checks.HealthChecker
import org.hvmhealth.morpheus.ChangeWindows
import org.hvmhealth.morpheus.FreezeWatch
import org.hvmhealth.morpheus.HealthContext
import org.hvmhealth.morpheus.HealthController
import org.hvmhealth.morpheus.HealthMonitor
import org.hvmhealth.morpheus.HealthReport
import org.hvmhealth.morpheus.HostOps
import org.hvmhealth.morpheus.Incidents
import org.hvmhealth.morpheus.Jobs
import org.hvmhealth.morpheus.Paging
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
    List<OperationData> stored = Collections.synchronizedList([])     // what the plugin keeps in Morpheus's data store
    Closure<OperationData> onStore = { OperationData row -> row.id = 1000L + stored.size(); stored << row; row }
    Closure<Boolean> onUnstore = { OperationData row -> stored.remove(row) }
    Closure<List<OperationData>> onStoreList = { stored.toList() }
    List<com.morpheusdata.core.data.DataQuery> alarmQueries = Collections.synchronizedList([])
    Closure<List<OperationNotification>> onAlarmList = { com.morpheusdata.core.data.DataQuery q -> alarms }

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
            list(_) >> { args -> self.alarmQueries << args[0]; self.onAlarmList.call(args[0]) }
            createAlarm(_) >> { args -> self.created << args[0]; ServiceResponse.success() }
            clearAlarm(_) >> { args -> self.cleared << args[0]; ServiceResponse.success() }
        }
        def store = Stub(MorpheusServices.getMethod('getOperationData').returnType) {
            list(_) >> { self.onStoreList.call() }
            create(_) >> { args -> self.onStore.call(args[0]) }
            get(_) >> { args -> self.stored.find { it.id == args[0] } }
            remove(_) >> { args -> self.onUnstore.call(args[0]) }
        }
        def services = Stub(MorpheusServices) {
            getCluster() >> clusterService
            getComputeServer() >> servers
            getNetwork() >> networks
            getAdmin() >> admin
            getOperationNotification() >> notifications
            getOperationData() >> store
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
        ChangeWindows.stop()
        HealthMonitor.uniqueIdFilterWorks = null
        clusters.each { HealthMonitor.LAST.remove(it.id) }
    }

    // ------------------------------------------------------------------ who gets in, and what a refusal gives away

    @Unroll
    def 'a customer whose role grants everything is refused #route by #verb, and nothing runs or is recorded'() {
        given:
        def c = controller()

        when:
        def r = c."${route}"(view(INITECH, verb, [host: 'hvm-node01', key: 'service-enable', nodeId: '1', confirm: 'hvm-node01', attest: 'on', servers: '192.0.2.1', minutes: '60', note: 'planned']))

        then:
        r.status == 403
        !JsonOutput.toJson(r.data).contains('cluster-x')
        ran == []
        audited == []
        created == []
        stored == []
        !Paging.held(27L)
        Jobs.forCluster(27L).every { it.state != 'running' }

        where:
        [route, verb] << [['status', 'catalog', 'check', 'raw', 'fix', 'timeSource', 'preflight', 'fleet', 'monitor', 'fleetInsights', 'availability', 'availabilityExport', 'report', 'vlanTest', 'windowStart', 'windowEnd', 'removeHost', 'fencePlan', 'fenceAck', 'controller', 'controllerReset'], ['GET', 'POST']].combinations()
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
        def r = controller()."${route}"(view(MASTER, 'GET', [host: 'hvm-node01', key: 'service-enable', nodeId: '1', confirm: 'hvm-node01', attest: 'on', servers: '192.0.2.1', minutes: '60', note: 'planned']))

        then:
        r.status == 403
        ran == []
        audited == []
        stored == []
        !Paging.held(27L)
        !cluster.getConfigProperty(HealthContext.CFG_TIME_SOURCES)

        where:
        route << ['fix', 'timeSource', 'vlanTest', 'removeHost', 'fenceAck', 'windowStart', 'windowEnd', 'controllerReset']
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

    // ------------------------------------------------------------------ the management controller

    def 'an operator with read access reads a host controller: one read-only script, parsed, and nothing audited'() {
        given:
        onScript = { String s -> new TaskResult(success: true, exitCode: '0', output: ControllerSpec.READ) }
        Closure access = { String method -> new HealthController(null, null).routes.find { it.method == method }.permissions.collect { [it.code, it.availableAccessTypes] } }

        when:
        def r = controller().controller(view(ACME, 'GET', [host: 'hvm-node01']))
        Map b = r.data.data as Map

        then:
        r.status == 200
        b.host == 'hvm-node01'
        b.controller.firmware == '7.00'
        b.chassis.faults == ['Drive Fault']
        ran.size() == 1
        ran[0].contains('ipmitool sdr elist')
        !ran[0].contains('mc reset')
        audited == []
        access('controller') == access('status')
        access('controllerReset') == access('fix')
    }

    def 'a controller reset needs the host name typed, and changes nothing without it'() {
        when:
        def r = controller().controllerReset(view(ACME, 'POST', [host: 'hvm-node01', confirm: 'hvm-node02']))

        then:
        r.status == 400
        r.data.msg.contains('Type the host name')
        ran == []
        audited == []
    }

    def 'a controller reset is refused during a freeze, before anything is sent'() {
        given:
        sample = Fixture.text('probe-frozen')

        when:
        Jobs.Job j = finished(controller().controllerReset(view(ACME, 'POST', [host: 'hvm-node01', confirm: 'hvm-node01'])))

        then:
        j.state == 'failed'
        j.error.contains('none is reset during a freeze')
        !ran.any { it.contains('mc reset') }
        audited.any { it.contains('controller reset refused') }
    }

    def 'a controller reset sends the reset once and lets go of the cluster, so an acknowledgement never waits behind it'() {
        given:
        onScript = { String s ->
            s.contains('mc reset cold') ? new TaskResult(success: true, exitCode: '0', output: 'Sent cold reset command to MC') : new TaskResult(success: true, exitCode: '0', output: sample)
        }

        when:
        Jobs.Job j = finished(controller().controllerReset(view(ACME, 'POST', [host: 'hvm-node01', confirm: 'HVM-NODE01'])))

        then:
        j.state == 'done'
        j.result.host == 'hvm-node01'
        j.progress.any { it.contains('reset sent') }
        ran.count { it.contains('mc reset cold') } == 1
        !Jobs.busy(27L)
        audited.any { it.contains('controller reset started') }
        audited.any { it.contains('controller reset sent') }
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
        '6'  | 'rush a reboot'
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

    def 'a quick run judges isolation itself: a trunk seen once is a warning marked confirming, on a SECURITY alarm, with no audit'() {
        given:
        ComputeServerGroup own = new ComputeServerGroup(id: 2701L, name: 'cluster-y', owner: ACME, type: new ComputeServerGroupType(code: 'mvm-cluster'))
        clusters = [own]
        hosts = hosts.take(1)
        sample = sample.replace('@@end', '@@vm_ports\nBR_PORT=vm-x|vnet0|02:00:00:00:00:09|hvmbr0|1|[{"ifname":"vnet0","vlans":[{"vlan":120,"flags":["PVID"]},{"vlan":300}]}]\n@@end')
        MorpheusContext m = morpheus()

        when:
        Map view = HealthMonitor.run(m, new HealthContext(m, 2701L), [:], 'quick', false)
        Map found = (view.results as List<Map>).find { it.id == 'NET-13' && it.status == 'WARN' }

        then:
        found.title == 'No unexpected trunk (confirming): vm-x'
        created.find { it.name.startsWith('SECURITY: NET-13 ') }.status == 'warning'
        !audited.any { it.contains('isolation finding confirmed') }
    }

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

        when: 'a sweep after the cluster\'s turn has come'
        HealthMonitor.sweep(m, new ClusterHealthPlugin(), System.currentTimeMillis() + 10 * 60_000L)
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

    def 'no more clusters than checkConcurrency are checked at once, the rest still get their turn, and none is queued twice'() {
        given:
        settings = '{"checkConcurrency":"4"}'
        long later = System.currentTimeMillis() + 10 * 60_000L   // the turn of every cluster has come
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
        HealthMonitor.sweep(m, new ClusterHealthPlugin(), later)
        new PollingConditions(timeout: 10).eventually { assert started.get() == 4 }
        HealthMonitor.sweep(m, new ClusterHealthPlugin(), later)
        Thread.sleep(300)

        then:
        started.get() == 4

        when:
        release.countDown()

        then:
        new PollingConditions(timeout: 20).eventually { assert clusters.every { HealthMonitor.LAST.containsKey(it.id) } }
        started.get() == 6
    }

    // ------------------------------------------------------------------ one alarm per incident, through the monitor

    private static HealthChecker.Result net01(String host, HealthChecker.Status status = HealthChecker.Status.FAIL) {
        new HealthChecker.Result('NET-01', 'Bond members up: bond0', HealthChecker.Severity.HIGH, status, host, 'eno1 down', 'up', 'plugin')
    }

    /** An open alarm as this build raised it, so it holds what its own message would say. */
    private OperationNotification raised(List<HealthChecker.Result> rs) {
        Map a = HealthMonitor.alarmsFor(27L, 'cluster-x', rs)[0]
        new OperationNotification(uniqueId: a.uniqueId as String, status: a.status as String, statusMessage: a.message as String, active: true)
    }

    private HealthContext ownedByMaster() {
        cluster.owner = MASTER
        new HealthContext(morpheus(), 27L)
    }

    def 'a frozen cluster raises one alarm for the freeze, on the cluster, and lists every finding in the tab'() {
        given:
        frozenCluster()
        cluster.owner = MASTER
        MorpheusContext m = morpheus()

        when:
        Map view = HealthMonitor.run(m, new HealthContext(m, 27L), [:], 'quick', false)
        List<OperationNotification> freeze = created.findAll { it.name.startsWith('DLM-01') }

        then:
        freeze.size() == 1
        freeze[0].refType == 'computeServerGroup'
        freeze[0].refId == 27L
        freeze[0].status == 'error'
        !created.any { it.name.startsWith('VM-02') }

        and: 'the tab still has them, with the emergency action'
        view.results.any { it.id == 'VM-02' && it.status == 'FAIL' }
        view.results.any { it.id == 'DLM-01' && it.tier == 'EMERGENCY' }
        view.incidents.find { it.key.startsWith('DLM-01') }.symptoms > 0
    }

    def 'when the freeze clears, its symptoms raise their own alarms, and the freeze goes'() {
        given:
        HealthContext ctx = ownedByMaster()
        List<HealthChecker.Result> during = [new HealthChecker.Result('DLM-01', 'Lockspaces', HealthChecker.Severity.CRITICAL, HealthChecker.Status.FAIL, 'hvm-node01', 'o', 'e', 'p'), net01('hvm-node02'),
                                             new HealthChecker.Result('MOR-03', 'Agent', HealthChecker.Severity.HIGH, HealthChecker.Status.FAIL, 'hvm-node02', 'o', 'e', 'p')]
        alarms = [raised(during)]

        when:
        HealthMonitor.setAlarms(morpheus(), ctx, [during[2]])

        then:
        cleared*.uniqueId == [alarms[0].uniqueId]
        created*.name == ['MOR-03 Agent']
    }

    def 'an acknowledged alarm is left alone across runs, while a symptom appears or a host clears'() {
        given:
        HealthContext ctx = ownedByMaster()
        HealthChecker.Result freeze = new HealthChecker.Result('DLM-01', 'Lockspaces', HealthChecker.Severity.CRITICAL, HealthChecker.Status.FAIL, 'hvm-node01', 'o', 'e', 'p')
        HealthChecker.Result freeze2 = new HealthChecker.Result('DLM-01', 'Lockspaces', HealthChecker.Severity.CRITICAL, HealthChecker.Status.FAIL, 'hvm-node02', 'o', 'e', 'p')
        OperationNotification open = raised([freeze, freeze2])
        open.acknowledged = true
        alarms = [open]

        when:
        HealthMonitor.setAlarms(morpheus(), ctx, [freeze, freeze2])
        HealthMonitor.setAlarms(morpheus(), ctx, [freeze])
        HealthMonitor.setAlarms(morpheus(), ctx, [freeze, new HealthChecker.Result('VM-02', 'Stuck', HealthChecker.Severity.CRITICAL, HealthChecker.Status.FAIL, 'hvm-node01', 'o', 'e', 'p')])

        then:
        cleared == []
        created == []
    }

    def 'a check that spreads to a host the alarm does not name raises it again, under the same identity, naming both'() {
        given:
        HealthContext ctx = ownedByMaster()
        OperationNotification open = raised([net01('hvm-node01')])
        open.acknowledged = true
        alarms = [open]

        when:
        HealthMonitor.setAlarms(morpheus(), ctx, [net01('hvm-node01'), net01('hvm-node02')])

        then:
        cleared*.uniqueId == [open.uniqueId]
        created*.uniqueId == [open.uniqueId]
        created[0].statusMessage.startsWith('cluster-x / hvm-node01, hvm-node02: ')
        created[0].refType == 'computeServerGroup'
    }

    def 'raised again, it keeps its start, and says it had been acknowledged and by whom, in the words that still end the message'() {
        given:
        HealthContext ctx = ownedByMaster()
        Date first = new Date(1_800_000_000_000L)
        OperationNotification open = raised([net01('hvm-node01')])
        open.acknowledged = true
        open.acknowledgedByUser = 'someone'
        open.startDate = first
        alarms = [open]

        when:
        HealthMonitor.setAlarms(morpheus(), ctx, [net01('hvm-node01'), net01('hvm-node02')])

        then:
        created[0].startDate == first
        created[0].statusMessage.contains('Acknowledged earlier by someone, before this changed.')
        created[0].statusMessage.endsWith(Incidents.ALARM_TAIL)
        created[0].acknowledged == false
    }

    def 'one nobody acknowledged says nothing about that'() {
        given:
        HealthContext ctx = ownedByMaster()
        OperationNotification open = raised([net01('hvm-node01')])
        alarms = [open]

        when:
        HealthMonitor.setAlarms(morpheus(), ctx, [net01('hvm-node01'), net01('hvm-node02')])

        then:
        !created[0].statusMessage.contains('Acknowledged')
    }

    def 'a symptom that appears after the alarm was raised gets into its text if nobody has acknowledged it, keeping its start, and then it stays'() {
        given:
        HealthContext ctx = ownedByMaster()
        Date first = new Date(1_800_000_000_000L)
        HealthChecker.Result freeze = new HealthChecker.Result('DLM-01', 'Lockspaces', HealthChecker.Severity.CRITICAL, HealthChecker.Status.FAIL, 'hvm-node01', 'o', 'e', 'p')
        HealthChecker.Result stuck = new HealthChecker.Result('VM-02', 'Stuck', HealthChecker.Severity.CRITICAL, HealthChecker.Status.FAIL, 'hvm-node01', 'o', 'e', 'p')
        HealthChecker.Result silent = new HealthChecker.Result('PRB-01', 'Answers', HealthChecker.Severity.HIGH, HealthChecker.Status.FAIL, 'hvm-node03', 'o', 'e', 'p')
        OperationNotification open = raised([freeze])
        open.startDate = first
        alarms = [open]

        when:
        HealthMonitor.setAlarms(morpheus(), ctx, [freeze, stuck])

        then:
        cleared*.uniqueId == [open.uniqueId]
        created.size() == 1
        created[0].statusMessage.contains('Also explains 1 related finding: VM-02')
        created[0].startDate == first

        when: 'the same again: it is named now, so nothing changes'
        alarms = [created[0]]
        cleared.clear()
        created.clear()
        HealthMonitor.setAlarms(morpheus(), ctx, [freeze, stuck])

        then:
        cleared == []
        created == []

        when: 'a symptom on a host the text does not name yet'
        HealthMonitor.setAlarms(morpheus(), ctx, [freeze, stuck, silent])

        then:
        created.size() == 1
        created[0].statusMessage.contains('PRB-01')
        created[0].statusMessage.contains('hvm-node03')
    }

    def 'a message cut short does not raise itself again for a symptom either'() {
        given:
        HealthContext ctx = ownedByMaster()
        HealthChecker.Result freeze = new HealthChecker.Result('DLM-01', 'Lockspaces', HealthChecker.Severity.CRITICAL, HealthChecker.Status.FAIL, 'hvm-node01', 'o', 'e', 'p')
        HealthChecker.Result stuck = new HealthChecker.Result('VM-02', 'Stuck', HealthChecker.Severity.CRITICAL, HealthChecker.Status.FAIL, 'hvm-node01', 'o', 'e', 'p')
        OperationNotification open = raised([freeze])
        open.statusMessage = open.statusMessage.take(20)
        alarms = [open]

        when:
        HealthMonitor.setAlarms(morpheus(), ctx, [freeze, stuck])

        then:
        created == []
    }

    private static HealthChecker.Result fullOnly(String host) {
        new HealthChecker.Result('HW-07', 'Boot disk healthy (SMART): sda', HealthChecker.Severity.HIGH, HealthChecker.Status.FAIL, host, 'FAILED', 'healthy', 'plugin')
    }

    def 'with no full check since the load, a quick check cannot clear what only a full one can judge, in either copy, and once one has run it can'() {
        given:
        HealthContext ctx = new HealthContext(morpheus(), 27L)
        OperationNotification tenants = raised([fullOnly('hvm-node01')])
        OperationNotification master = raised([fullOnly('hvm-node01')])
        master.uniqueId = "${master.uniqueId}-master".toString()
        OperationNotification quick = raised([net01('hvm-node01')])
        alarms = [tenants, master, quick]
        HealthMonitor.@LAST_FULL.remove(27L)

        when:
        HealthMonitor.setAlarms(morpheus(), ctx, [])

        then: 'what a quick check does judge still clears'
        cleared == [quick]

        when:
        alarms = [tenants, master]
        cleared.clear()
        HealthMonitor.@LAST_FULL[27L] = []
        HealthMonitor.setAlarms(morpheus(), ctx, [])

        then:
        cleared as Set == [tenants, master] as Set

        cleanup:
        HealthMonitor.@LAST_FULL.remove(27L)
    }

    private HealthChecker.Input withPorts(Map<String, Map> facts) {
        Fixture.input(hosts.collect { new HealthChecker.Host(it.name, false, 'online', null, null, new org.hvmhealth.model.HostFacts([complete: true] + (facts[it.name] ?: [:]))) }, Fixture.LAYOUT_20)
    }

    private static HealthChecker.Result trunk(String host) {
        new HealthChecker.Result('NET-13', 'No unexpected trunk: vm-one', HealthChecker.Severity.CRITICAL, HealthChecker.Status.FAIL, host, 'o', 'e', 'plugin')
    }

    def 'after a reload an isolation alarm on a host whose ports were not read stays, though the host answered, and clears once they are read'() {
        given:
        HealthContext ctx = ownedByMaster()
        OperationNotification open = raised([trunk('hvm-node02')])
        alarms = [open]

        when: 'cut short, on the first run after the load: nothing in memory holds the finding'
        HealthMonitor.setAlarms(morpheus(), ctx, [], false, withPorts(('hvm-node01'): [sections: [vm_ports: []]], ('hvm-node02'): [sections: [vm_ports: []], portsCut: true], ('hvm-node03'): [sections: [vm_ports: []]]))

        then:
        cleared == []

        when: 'read whole, and the finding has gone'
        HealthMonitor.setAlarms(morpheus(), ctx, [], false, withPorts(('hvm-node01'): [sections: [vm_ports: []]], ('hvm-node02'): [sections: [vm_ports: []]], ('hvm-node03'): [sections: [vm_ports: []]]))

        then:
        cleared == [open]
    }

    def 'it does not hold any other alarm, or an isolation alarm on a host that was read'() {
        given:
        HealthContext ctx = ownedByMaster()
        OperationNotification net = raised([net01('hvm-node02')])
        OperationNotification other = raised([trunk('hvm-node01')])
        alarms = [net, other]

        when:
        HealthMonitor.setAlarms(morpheus(), ctx, [], false, withPorts(('hvm-node01'): [sections: [vm_ports: []]], ('hvm-node02'): [sections: [vm_ports: []], portsCut: true], ('hvm-node03'): [sections: [vm_ports: []]]))

        then:
        cleared as Set == [net, other] as Set
    }

    def 'both copies of an alarm follow the same rule'() {
        given:
        HealthContext ctx = new HealthContext(morpheus(), 27L)   // owned by a tenant, so the master tenant has its own
        OperationNotification tenants = raised([net01('hvm-node01')])
        OperationNotification master = raised([net01('hvm-node01')])
        master.uniqueId = "${master.uniqueId}-master".toString()
        alarms = [tenants, master]

        when:
        HealthMonitor.setAlarms(morpheus(), ctx, [net01('hvm-node01'), net01('hvm-node02')])

        then:
        cleared.size() == 2
        created.size() == 2
        created*.uniqueId as Set == [tenants.uniqueId, master.uniqueId] as Set
    }

    def 'an alarm from before grouping is replaced by the group on the first run, and a cluster-wide one keeps its identity'() {
        given:
        HealthContext ctx = ownedByMaster()
        String oldKey = 'NET-01|hvm-node01|Bond members up: bond0'.digest('MD5').take(12)
        OperationNotification old = new OperationNotification(uniqueId: "hvm-cluster-health-27-NET-01-${oldKey}".toString(), status: 'error', statusMessage: 'as it was', active: true)
        HealthChecker.Result wide = new HealthChecker.Result('STO-05', 'Paths', HealthChecker.Severity.MEDIUM, HealthChecker.Status.WARN, null, 'o', 'e', 'p')
        OperationNotification keeps = raised([wide])
        alarms = [old, keeps]

        when:
        HealthMonitor.setAlarms(morpheus(), ctx, [net01('hvm-node01'), wide])

        then:
        cleared == [old]
        created*.name == ['NET-01 Bond members up: bond0']
    }

    private HealthChecker.Input withSilent(String host) {
        Fixture.input(hosts.collect { new HealthChecker.Host(it.name, false, 'online', null, null, it.name == host ? new org.hvmhealth.model.HostFacts() : new org.hvmhealth.model.HostFacts(complete: true)) }, Fixture.LAYOUT_20)
    }

    def 'an alarm naming a host that did not answer stays: that says nothing about the finding'() {
        given:
        HealthContext ctx = ownedByMaster()
        OperationNotification open = raised([net01('hvm-node02')])
        open.acknowledged = true
        alarms = [open]

        when:
        HealthMonitor.setAlarms(morpheus(), ctx, [], false, withSilent('hvm-node02'))

        then:
        cleared == []

        when: 'it answers, and the finding has gone'
        HealthMonitor.setAlarms(morpheus(), ctx, [], false, withSilent(null))

        then:
        cleared == [open]
    }

    def 'an alarm on a host that did answer still clears when another host is silent'() {
        given:
        HealthContext ctx = ownedByMaster()
        OperationNotification open = raised([net01('hvm-node02')])
        alarms = [open]

        when:
        HealthMonitor.setAlarms(morpheus(), ctx, [], false, withSilent('hvm-node03'))

        then:
        cleared == [open]
    }

    def 'one cluster never clears another: a longer id the filter also matches is left alone'() {
        given:
        HealthContext ctx = ownedByMaster()
        OperationNotification other = new OperationNotification(uniqueId: 'hvm-cluster-health-271-NET-01-abc', status: 'error', active: true)
        OperationNotification mine = new OperationNotification(uniqueId: 'hvm-cluster-health-27-NET-01-abc', status: 'error', active: true)
        alarms = [other, mine]

        when:
        HealthMonitor.setAlarms(morpheus(), ctx, [])

        then:
        cleared == [mine]
    }

    // ------------------------------------------------------------------ listing alarms at scale

    /** What Morpheus would do with the filter if ':' means "contains". */
    private List<OperationNotification> honouringFilter(com.morpheusdata.core.data.DataQuery q) {
        def f = q.filters.find { it.name == 'uniqueId' }
        f ? alarms.findAll { it.uniqueId.contains(f.value as String) } : alarms
    }

    def 'with 200 clusters, each lists its own alarms once the filter is shown to work, not every alarm on the appliance'() {
        given:
        clusters = (1000L..1199L).collect { new ComputeServerGroup(id: it, name: "cluster-${it}".toString(), owner: MASTER, type: new ComputeServerGroupType(code: 'mvm-cluster')) }
        alarms = clusters.collectMany { c -> (1..3).collect { new OperationNotification(uniqueId: "hvm-cluster-health-${c.id}-NET-0${it}-abc".toString(), status: 'error', active: true) } }
        int rowsRead = 0
        onAlarmList = { com.morpheusdata.core.data.DataQuery q -> List<OperationNotification> found = honouringFilter(q); rowsRead += found.size(); found }
        MorpheusContext m = morpheus()

        when:
        clusters.each { c -> HealthMonitor.setAlarms(m, new HealthContext(m, c.id), []) }

        then: 'quadratic would have been 120,000 rows'
        rowsRead < 2000
        alarmQueries.count { q -> !q.filters.any { it.name == 'uniqueId' } } == 1
        cleared.size() == alarms.size()
    }

    def 'a filter that loses an open alarm is not trusted, so nothing is raised twice'() {
        given:
        HealthContext ctx = ownedByMaster()
        alarms = [raised([net01('hvm-node01')])]
        onAlarmList = { com.morpheusdata.core.data.DataQuery q -> q.filters.any { it.name == 'uniqueId' } ? [] : alarms }
        MorpheusContext m = morpheus()

        when:
        3.times { HealthMonitor.setAlarms(m, new HealthContext(m, 27L), [net01('hvm-node01')]) }

        then:
        created == []
        cleared == []
        HealthMonitor.uniqueIdFilterWorks == false
        alarmQueries.count { q -> q.filters.any { it.name == 'uniqueId' } } == 1
    }

    def 'a filter Morpheus refuses is not trusted either, and alarms carry on'() {
        given:
        ownedByMaster()
        alarms = [raised([net01('hvm-node01')])]
        onAlarmList = { com.morpheusdata.core.data.DataQuery q ->
            if (q.filters.any { it.name == 'uniqueId' }) {
                throw new IllegalArgumentException('no such operator')
            }
            alarms
        }
        MorpheusContext m = morpheus()

        when:
        HealthMonitor.setAlarms(m, new HealthContext(m, 27L), [net01('hvm-node01'), net01('hvm-node02')])

        then:
        HealthMonitor.uniqueIdFilterWorks == false
        created.size() == 1
    }

    def 'with nothing open the filter proves nothing, so it is not yet trusted, and the alarm is raised once'() {
        given:
        HealthContext ctx = ownedByMaster()
        onAlarmList = { com.morpheusdata.core.data.DataQuery q -> q.filters.any { it.name == 'uniqueId' } ? [] : alarms }

        when:
        HealthMonitor.setAlarms(morpheus(), ctx, [net01('hvm-node01')])

        then:
        HealthMonitor.uniqueIdFilterWorks == null
        created.size() == 1
        alarmQueries.every { q -> !q.filters.any { it.name == 'uniqueId' } }
    }

    // ------------------------------------------------------------------ change windows

    private static final Map START = [minutes: '60', note: 'firmware on the hosts']

    private List<OperationData> windowRows() {
        stored.findAll { it.category == ChangeWindows.CATEGORY }
    }

    def 'the window routes need full access, like a fix'() {
        given:
        def routes = new HealthController(null, null).routes
        Closure access = { String method -> routes.find { it.method == method }.permissions.collect { [it.code, it.availableAccessTypes] } }

        expect:
        access('windowStart') == access('fix')
        access('windowEnd') == access('fix')
        routes.find { it.method == 'windowStart' }.url == '/hvm-health/window-start'
        routes.find { it.method == 'windowEnd' }.url == '/hvm-health/window-end'
    }

    def 'an operator starts a window: it holds paging, says who and why, and is audited and stored'() {
        when:
        long before = System.currentTimeMillis()
        def r = controller().windowStart(view(ACME, 'POST', START))
        Map w = r.data.data as Map

        then:
        r.status == 200
        w.by == 'someone'
        w.note == 'firmware on the hosts'
        (w.endsAt as long) >= before + 3_600_000L
        (w.endsAt as long) <= System.currentTimeMillis() + 3_600_000L
        Paging.held(27L)
        !Paging.held(28L)
        windowRows().size() == 1
        windowRows()[0].refId == '27'
        audited.any { it.contains('change window started') && it.contains('firmware on the hosts') && it.contains('cluster-x') }

        and: 'the tab is told'
        controller().status(view(ACME, 'GET')).data.data.window.note == 'firmware on the hosts'
        controller().status(view(ACME, 'GET')).data.data.maxWindowHours == 24
    }

    def 'with no window the tab is told that too'() {
        expect:
        controller().status(view(ACME, 'GET')).data.data.window == null
    }

    def 'a window needs an end and a reason, and nothing is started without them'() {
        when:
        def r = controller().windowStart(view(ACME, 'POST', params))

        then:
        r.status == 400
        r.data.msg
        windowRows() == []
        !Paging.held(27L)
        audited == []

        where:
        params << [[note: 'x'], [minutes: '', note: 'x'], [minutes: '0', note: 'x'], [minutes: '-5', note: 'x'], [minutes: 'soon', note: 'x'], [minutes: '1.5', note: 'x'], [minutes: '1441', note: 'x'],
                   [minutes: '99999999999', note: 'x'], [minutes: '60'], [minutes: '60', note: '   '], [minutes: '60', note: 'x' * 201]]
    }

    def 'the longest window follows the setting, and a setting that makes no sense leaves the default'() {
        given:
        settings = JsonOutput.toJson([maxWindowHours: setting])

        expect:
        controller().windowStart(view(ACME, 'POST', [minutes: minutes, note: 'x'])).status == status

        where:
        setting | minutes | status
        '2'     | '120'   | 200
        '2'     | '121'   | 400
        '0'     | '1440'  | 200
        '0'     | '1441'  | 400
        '-1'    | '1441'  | 400
        'lots'  | '1441'  | 400
    }

    def 'a window the store did not take is refused, rather than vanish at the next reload'() {
        given:
        onStore = store

        when:
        def r = controller().windowStart(view(ACME, 'POST', START))

        then:
        r.status == 400
        r.data.msg.contains('paging is not held')
        !Paging.held(27L)
        audited == []

        where:
        store << [{ OperationData row -> row }, { OperationData row -> row.id = 5L; null }, { OperationData row -> row.id = 77L; row }]
    }

    def 'a new window replaces the open one, which goes from the store'() {
        given:
        def c = controller()
        c.windowStart(view(ACME, 'POST', START))

        when:
        c.windowStart(view(ACME, 'POST', [minutes: '30', note: 'longer job']))

        then:
        windowRows().size() == 1
        ChangeWindows.current(27L).note == 'longer job'
        audited.count { it.contains('change window replaced') } == 1
    }

    def 'a window survives a reload'() {
        given:
        controller().windowStart(view(ACME, 'POST', START))
        MorpheusContext m = morpheus()

        when: 'the plugin reloads, with nothing in memory yet'
        ChangeWindows.stop()

        then:
        !Paging.held(27L)

        when:
        ChangeWindows.refresh(m)

        then:
        Paging.held(27L)
        ChangeWindows.current(27L).by == 'someone'
    }

    def 'the plugin reads open windows as soon as it starts, without waiting for a monitor pass'() {
        given:
        controller().windowStart(view(ACME, 'POST', START))
        MorpheusContext m = morpheus()
        ChangeWindows.stop()

        when:
        ChangeWindows.start(m)

        then:
        new PollingConditions(timeout: 10).eventually { assert Paging.held(27L) }
    }

    def 'a window that could not be read after a reload holds nothing, so a cluster pages rather than stays quiet'() {
        given:
        controller().windowStart(view(ACME, 'POST', START))
        ChangeWindows.stop()
        onStoreList = { throw new IllegalStateException('store down') }

        when:
        ChangeWindows.refresh(morpheus())

        then:
        noExceptionThrown()
        !Paging.held(27L)
    }

    def 'a store that fails later leaves what was known'() {
        given:
        controller().windowStart(view(ACME, 'POST', START))
        onStoreList = { throw new IllegalStateException('store down') }

        when:
        ChangeWindows.refresh(morpheus())

        then:
        Paging.held(27L)
    }

    def 'a window ends by itself at its time, with nothing having to run, and the store is tidied later'() {
        given:
        Map w = controller().windowStart(view(ACME, 'POST', START)).data.data as Map
        long ends = w.endsAt as long

        expect:
        Paging.held(27L, ends - 1)
        !Paging.held(27L, ends)
        ChangeWindows.current(27L, ends + 1) == null
        windowRows().size() == 1

        when:
        ChangeWindows.refresh(morpheus(), ends + 1)

        then:
        windowRows() == []
        !Paging.held(27L)
    }

    def 'a row that cannot be read is no window, and goes'() {
        given:
        stored << new OperationData(id: 1L, category: ChangeWindows.CATEGORY, refId: '27', rawData: raw)

        when:
        ChangeWindows.refresh(morpheus())

        then:
        !Paging.held(27L)
        windowRows() == []

        where:
        raw << ['{not json', '{"clusterId":27}', '{"endsAt":"tomorrow","clusterId":27}', null]
    }

    def 'of two rows for one cluster the newest wins, and the other goes, in either order'() {
        given:
        long far = System.currentTimeMillis() + 3_600_000L
        OperationData older = new OperationData(id: 1L, category: ChangeWindows.CATEGORY, refId: '27', rawData: JsonOutput.toJson([clusterId: 27, startedAt: 1L, endsAt: far, by: 'a', note: 'older']))
        OperationData newer = new OperationData(id: 2L, category: ChangeWindows.CATEGORY, refId: '27', rawData: JsonOutput.toJson([clusterId: 27, startedAt: 2L, endsAt: far, by: 'b', note: 'newer']))
        stored.addAll(first ? [older, newer] : [newer, older])

        when:
        ChangeWindows.refresh(morpheus())

        then:
        ChangeWindows.current(27L).note == 'newer'
        windowRows()*.id == [2L]

        where:
        first << [true, false]
    }

    def 'ending a window puts paging back, and it stays ended after a reload'() {
        given:
        def c = controller()
        c.windowStart(view(ACME, 'POST', START))
        audited.clear()

        when:
        def r = c.windowEnd(view(ACME, 'POST'))

        then:
        r.status == 200
        r.data.data.ended == true
        !Paging.held(27L)
        windowRows() == []
        audited.count { it.contains('change window ended') } == 1

        when:
        ChangeWindows.stop()
        ChangeWindows.refresh(morpheus())

        then:
        !Paging.held(27L)
    }

    def 'ending with nothing open is no error and no audit entry, as a second click would be'() {
        when:
        def r = controller().windowEnd(view(ACME, 'POST'))

        then:
        r.status == 200
        r.data.data.ended == false
        audited == []
    }

    def 'a window that cannot be removed stays, and says so, as it would come back on the next read'() {
        given:
        def c = controller()
        c.windowStart(view(ACME, 'POST', START))
        audited.clear()
        onUnstore = { OperationData row -> false }

        when:
        def r = c.windowEnd(view(ACME, 'POST'))

        then:
        r.status == 400
        r.data.msg.contains('paging stays held')
        Paging.held(27L)
        audited == []
    }

    def 'the report says nothing of a window, whose reason can name anyone'() {
        given:
        def c = controller()
        c.windowStart(view(ACME, 'POST', [minutes: '60', note: 'moving Initech off this cluster']))
        HealthMonitor.LAST[27L] = [overall: 'HEALTHY', at: 1L, mode: 'quick', counts: [fail: 0, warn: 0, pass: 1], readiness: [], hosts: [], results: []]

        when:
        def r = c.report(view(ACME, 'GET'))

        then:
        r.status == 200
        !JsonOutput.toJson(r.data).contains('Initech')
    }

    def 'the all-clusters page shows who holds a window, and only the master tenant reaches that'() {
        given:
        def c = controller()
        c.windowStart(view(ACME, 'POST', START))

        expect:
        c.fleet(view(MASTER, 'GET')).data.data[0].window.note == 'firmware on the hosts'
        c.fleet(view(ACME, 'GET')).status == 403
    }

    def 'with no window, a row on the all-clusters page says none'() {
        expect:
        controller().fleet(view(MASTER, 'GET')).data.data[0].window == null
    }

    def 'a tenant with no claim on a cluster cannot end the window its operator started'() {
        given:
        def c = controller()
        c.windowStart(view(ACME, 'POST', START))

        when:
        def r = c.windowEnd(view(INITECH, 'POST'))

        then:
        r.status == 403
        Paging.held(27L)
        windowRows().size() == 1
    }

    def 'a tenant listed in the Operator tenants setting may start one too'() {
        given:
        settings = '{"operatorTenants":"4"}'

        expect:
        controller().windowStart(view(INITECH, 'POST', START)).status == 200
    }

    def 'checks, alarms and history carry on inside a window'() {
        given:
        cluster.owner = MASTER
        frozenCluster()
        MorpheusContext m = morpheus()
        controller().windowStart(view(MASTER, 'POST', START))

        when:
        Map v = HealthMonitor.run(m, new HealthContext(m, 27L), [:], 'quick', false)

        then:
        v.results.any { it.id == 'DLM-01' }
        created.any { it.name.startsWith('DLM-01') }
        stored.any { it.category == 'hvm-cluster-health-history' }
    }

    // ------------------------------------------------------------------ what a window holds

    private static Map viewWith(String overall, List<Map> results, List<Map> hostsNow = []) {
        [overall: overall, results: results, hosts: hostsNow]
    }

    def 'what always pages is never held'() {
        given:
        controller().windowStart(view(ACME, 'POST', START))

        expect:
        Paging.held(27L)
        Paging.state(27L, viewWith('CRITICAL', [[id: id, status: 'FAIL']])) == 'CRITICAL'

        where:
        id << Paging.ALWAYS_PAGE
    }

    def 'a frozen cluster pages through a window, whichever way the freeze is known'() {
        given:
        controller().windowStart(view(ACME, 'POST', START))

        expect:
        Paging.state(27L, viewWith('HEALTHY', [], [[frozen: true]])) == 'CRITICAL'

        when:
        FreezeWatch.FROZEN_SINCE[27L] = 1L

        then:
        Paging.state(27L, viewWith('HEALTHY', [])) == 'CRITICAL'

        cleanup:
        FreezeWatch.FROZEN_SINCE.remove(27L)
    }

    def 'anything else is held inside a window, and pages again once it has ended'() {
        given:
        Map w = controller().windowStart(view(ACME, 'POST', START)).data.data as Map
        Map v = viewWith(overall, [[id: 'NET-01', status: 'FAIL']])

        expect:
        Paging.state(27L, v) == 'OK'
        Paging.state(27L, v, (w.endsAt as long) + 1) == outside

        where:
        overall    | outside
        'CRITICAL' | 'CRITICAL'
        'DEGRADED' | 'WARNING'
        'WARNING'  | 'WARNING'
    }

    def 'only a failure of what always pages breaks through: a warning on one is held'() {
        given:
        controller().windowStart(view(ACME, 'POST', START))

        expect:
        Paging.state(27L, viewWith('WARNING', [[id: 'NET-13', status: 'WARN']])) == 'OK'
    }

    def 'a window on one cluster holds no other'() {
        given:
        controller().windowStart(view(ACME, 'POST', START))

        expect:
        Paging.state(28L, viewWith('DEGRADED', [[id: 'NET-01', status: 'FAIL']])) == 'WARNING'
    }

    def 'no evaluation is unknown, window or not'() {
        given:
        controller().windowStart(view(ACME, 'POST', START))

        expect:
        Paging.state(27L, null) == 'UNKNOWN'
    }

    def 'no evaluation is still CRITICAL when the freeze watch holds the cluster frozen: frozen wins, window or not'() {
        given:
        FreezeWatch.FROZEN_SINCE[27L] = 1L

        expect:
        Paging.state(27L, null) == 'CRITICAL'
        Paging.state(28L, null) == 'UNKNOWN'

        when:
        controller().windowStart(view(ACME, 'POST', START))

        then:
        Paging.state(27L, null) == 'CRITICAL'

        cleanup:
        FreezeWatch.FROZEN_SINCE.remove(27L)
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
