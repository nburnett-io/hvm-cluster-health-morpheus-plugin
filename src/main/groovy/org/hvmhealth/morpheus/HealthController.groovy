package org.hvmhealth.morpheus

import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.Plugin
import com.morpheusdata.model.Permission
import com.morpheusdata.model.User
import com.morpheusdata.views.JsonResponse
import com.morpheusdata.views.ViewModel
import com.morpheusdata.web.PluginController
import com.morpheusdata.web.Route
import groovy.util.logging.Slf4j
import org.hvmhealth.ClusterHealthPlugin
import org.hvmhealth.checks.CheckCatalog
import org.hvmhealth.checks.HealthChecker
import org.hvmhealth.fixes.Fixes
import org.hvmhealth.fixes.Preflight
import org.hvmhealth.model.HostFacts
import org.hvmhealth.standards.Standards

/**
 * Routes under /plugin/hvm-health. Morpheus enforces the role permission; on top of that every route refuses anyone who
 * is not an operator of this cluster (see Tenancy), so tier 3 and customers get nothing whatever their role grants.
 * A route carries no verb, so those that change something check it themselves: see postOnly.
 */
@Slf4j
class HealthController implements PluginController {
    static final String PERM = 'hvm-cluster-health-ops'
    static final String PERM_EMERGENCY = 'hvm-cluster-health-emergency'

    final Plugin plugin
    final MorpheusContext morpheus

    HealthController(Plugin plugin, MorpheusContext morpheus) {
        this.plugin = plugin
        this.morpheus = morpheus
    }

    @Override String getCode() { 'hvm-cluster-health.controller' }
    @Override String getName() { 'HVM Cluster Health controller' }
    @Override MorpheusContext getMorpheus() { morpheus }
    @Override Plugin getPlugin() { plugin }

    @Override
    List<Route> getRoutes() {
        Permission read = Permission.build(PERM, 'read')
        Permission full = Permission.build(PERM, 'full')
        Permission emergency = Permission.build(PERM_EMERGENCY, 'full')
        [
                Route.build('/hvm-health/status', 'status', read),
                Route.build('/hvm-health/catalog', 'catalog', read),
                Route.build('/hvm-health/check', 'check', read),
                Route.build('/hvm-health/job', 'job', read),
                Route.build('/hvm-health/raw', 'raw', read),
                Route.build('/hvm-health/fix', 'fix', full),
                Route.build('/hvm-health/time-source', 'timeSource', full),
                Route.build('/hvm-health/preflight', 'preflight', read),
                Route.build('/hvm-health/fleet', 'fleet', read),
                Route.build('/hvm-health/monitor', 'monitor', read),
                Route.build('/hvm-health/report', 'report', read),
                Route.build('/hvm-health/fleet-insights', 'fleetInsights', read),
                Route.build('/hvm-health/availability', 'availability', read),
                Route.build('/hvm-health/availability-export', 'availabilityExport', read),
                Route.build('/hvm-health/vlan-test', 'vlanTest', full),
                Route.build('/hvm-health/controller', 'controller', read),
                Route.build('/hvm-health/controller-reset', 'controllerReset', full),
                Route.build('/hvm-health/window-start', 'windowStart', full),
                Route.build('/hvm-health/window-end', 'windowEnd', full),
                Route.build('/hvm-health/remove-host', 'removeHost', emergency),
                Route.build('/hvm-health/fence-plan', 'fencePlan', emergency),
                Route.build('/hvm-health/fence-ack', 'fenceAck', emergency),
        ]
    }

    private static JsonResponse ok(Object data) {
        JsonResponse.of([success: true, data: data])
    }

    private static JsonResponse fail(String msg, int status = 400) {
        JsonResponse r = JsonResponse.of([success: false, msg: msg])
        r.status = status
        r
    }

    private JsonResponse guarded(Closure<JsonResponse> c) {
        try {
            c.call()
        } catch (SecurityException e) {
            fail(e.message, 403)
        } catch (IllegalArgumentException | IllegalStateException e) {
            fail(e.message)
        } catch (Exception e) {
            log.error('cluster health request failed', e)
            fail("${e.class.simpleName}: ${e.message}", 500)
        }
    }

    private static Map p(ViewModel<Map> m) {
        (m.object ?: [:]) as Map
    }

    private static Long clusterId(ViewModel<Map> m) {
        Object v = p(m).clusterId
        if (!v?.toString()?.isLong()) {
            throw new IllegalArgumentException('clusterId is required')
        }
        v as Long
    }

    private Map settings() {
        ClusterHealthPlugin.readSettings(morpheus, plugin)
    }

    private static String user(ViewModel<Map> m) {
        m.user?.username ?: 'unknown'
    }

    /**
     * A link clicked by a logged-in user is a GET, which carries no CSRF token. So what changes something answers POST only, as the tab sends it.
     * First thing in those routes. A request Morpheus did not describe is refused too.
     */
    private static void postOnly(ViewModel<Map> m) {
        if (m.request?.method != 'POST') {
            throw new SecurityException('This action must be a POST from the Cluster Health tab.')
        }
    }

    /**
     * A refusal says nothing about the cluster: a missing id gets the same answer as someone else's, and it goes to the log, because an audit entry
     * lands in the caller's tenant and would hand a customer the cluster's name.
     */
    private HealthContext operatorCtx(ViewModel<Map> m) {
        Long id = clusterId(m)
        HealthContext ctx = null
        try {
            ctx = new HealthContext(morpheus, id)
        } catch (IllegalArgumentException ignored) {
            // no such cluster: refused below
        }
        if (!ctx || !Tenancy.isOperator(m.user, ctx.cluster, Tenancy.operatorTenantIds(settings()), Tenancy.loader(morpheus))) {
            log.warn("cluster health refused ${user(m)} of tenant ${m.user?.account?.id} on cluster ${id}${ctx ? '' : ', which does not exist'}")
            throw new SecurityException('Cluster health is available only to the tenants that run this cluster.')
        }
        ctx
    }

    /** What the tab shows this user. Null when Morpheus did not pass permissions: the buttons stay and the routes decide. */
    static Boolean canFix(User u) {
        u?.permissions == null ? null : u.permissions[PERM] == 'full'
    }

    static Boolean canEmergency(User u) {
        u?.permissions == null ? null : u.permissions[PERM_EMERGENCY] == 'full'
    }

    /** The last evaluation, the standards in force (also before the first check) and the jobs still of interest. */
    def status(ViewModel<Map> m) {
        guarded {
            HealthContext ctx = operatorCtx(m)
            Map s = settings()
            String standardsError = null
            List<Map> standards = []
            try {
                standards = Standards.forLayout(ctx.layout(), s.siteStandards as String).toList()
            } catch (IllegalArgumentException e) {
                standardsError = e.message
            }
            Map last = HealthMonitor.LAST[ctx.cluster.id]
            ok([cluster: ctx.cluster.name, layout: ctx.layout().label, last: last, standards: standards, standardsError: standardsError,
                allClustersUrl: Tenancy.isMaster(m.user?.account, Tenancy.loader(morpheus)) ? HealthAnalyticsProvider.PAGE_URL : null,
                known: last ? null : FindingHistory.lastKnown(morpheus, ctx.cluster.id), historyError: FindingHistory.ERRORS[ctx.cluster.id], standingBy: HealthMonitor.standingBy(),
                canFix: canFix(m.user), canEmergency: canEmergency(m.user),
                window: ChangeWindows.current(ctx.cluster.id), maxWindowHours: ChangeWindows.maxHours(s),
                monitorMinutes: HealthMonitor.quickMinutes(s), fullHours: HealthMonitor.fullHours(s), watch: FreezeWatch.forCluster(ctx.cluster.id),
                autoFixSafe: s.autoFixSafe in [true, 'true', 'on'], autoFixMaintenance: s.autoFixMaintenance in [true, 'true', 'on'],
                autoFixNamed: HealthMonitor.autoFixNamed(s, ctx.cluster.id, ctx.cluster.name),
                jobs: Jobs.forCluster(ctx.cluster.id).findAll { it.state == 'running' }.collect { it.toMap() }])
        }
    }

    /** What each check id means, for the tab's popups and Help. It holds no cluster data, and has the same gate as status all the same. */
    def catalog(ViewModel<Map> m) {
        guarded {
            operatorCtx(m)
            ok(CheckCatalog.forTab())
        }
    }

    def check(ViewModel<Map> m) {
        guarded {
            HealthContext ctx = operatorCtx(m)
            Map s = settings()
            ok(Jobs.start('check', ctx.cluster.id, user(m)) { List<String> progress ->
                progress << "Probing ${ctx.hosts.size()} host(s)".toString()
                Map view = HealthMonitor.run(morpheus, ctx, s, 'full', false)
                progress << "${view.overall}: ${view.counts.fail} failing, ${view.counts.warn} warnings".toString()
                view
            }.toMap())
        }
    }

    def job(ViewModel<Map> m) {
        guarded {
            Jobs.Job j = Jobs.get(p(m).id as String)
            if (!j || j.clusterId != clusterId(m)) {
                return fail('No such job', 404)
            }
            operatorCtx(m)
            ok(j.toMap())
        }
    }

    /** The time sources this cluster is verified against; changes nothing on the hosts. Empty clears them. */
    def timeSource(ViewModel<Map> m) {
        guarded {
            postOnly(m)
            HealthContext ctx = operatorCtx(m)
            List<String> servers = ((p(m).servers ?: '') as String).tokenize(', ;\n\t').unique()
            if (Jobs.busy(ctx.cluster.id)) {
                throw new IllegalStateException('Another cluster health operation is running on this cluster, so nothing was saved. Try again when it ends.')
            }
            ctx.saveTimeSources(servers)
            Audit.info(morpheus, m.user, ctx.cluster, 'cluster time sources set', [servers: servers.join(',') ?: 'none'])
            Map s = settings()
            ok(Jobs.start('check', ctx.cluster.id, user(m)) { List<String> progress ->
                progress << "Verifying every host against ${servers.join(', ') ?: 'no defined time source'}".toString()
                HealthMonitor.run(morpheus, ctx, s, 'quick', false)
            }.toMap())
        }
    }

    /** "Can I take this host down?" Read-only. */
    def preflight(ViewModel<Map> m) {
        guarded {
            HealthContext ctx = operatorCtx(m)
            Map s = settings()
            String host = p(m).host as String
            ok(Preflight.hostDown(ctx.input(ctx.probe('quick', HealthContext.INTERACTIVE_PROBE_TIMEOUT_SEC), s.siteStandards as String), host))
        }
    }

    /** Every cluster, for the master tenant only: other tenants see their own tab, not each other's clusters. */
    def fleet(ViewModel<Map> m) {
        guarded {
            if (!Tenancy.isMaster(m.user?.account, Tenancy.loader(morpheus))) {
                throw new SecurityException('The fleet view is for the master tenant.')
            }
            ok(HealthMonitor.fleet(morpheus).collect { [*:it, window: ChangeWindows.current(it.id as Long)] })
        }
    }

    /** How the monitor itself is doing, for the all-clusters page: numbers, and why the status URL is not listening if it is not. */
    def monitor(ViewModel<Map> m) {
        guarded {
            masterOnly(m, 'The fleet view')
            ok(HealthMonitor.monitorState())
        }
    }

    /** Holds this cluster's paging until the time given, or until ended: see ChangeWindows. Checks, alarms and history carry on. */
    def windowStart(ViewModel<Map> m) {
        guarded {
            postOnly(m)
            HealthContext ctx = operatorCtx(m)
            ok(ChangeWindows.begin(morpheus, ctx.cluster, p(m).minutes, p(m).note as String, m.user, settings()))
        }
    }

    def windowEnd(ViewModel<Map> m) {
        guarded {
            postOnly(m)
            HealthContext ctx = operatorCtx(m)
            ok([ended: ChangeWindows.end(morpheus, ctx.cluster, m.user)])
        }
    }

    private void masterOnly(ViewModel<Map> m, String what) {
        if (!Tenancy.isMaster(m.user?.account, Tenancy.loader(morpheus))) {
            throw new SecurityException("${what} is for the master tenant.".toString())
        }
    }

    /** Shared causes and each cluster's availability. Master tenant only: they name other tenants and clusters, and none of it reads a row per cluster. */
    def fleetInsights(ViewModel<Map> m) {
        guarded {
            masterOnly(m, 'The fleet view')
            Map shared = FleetCorrelator.current(settings().sharedRisk as String)
            ok([shared: shared.shared, sharedError: shared.error, standingBy: HealthMonitor.standingBy(), availability: Availability.forFleet(morpheus, p(m).month as String)])
        }
    }

    /** One cluster's figures, with the tenants on it, for that cluster's operators. */
    def availability(ViewModel<Map> m) {
        guarded {
            HealthContext ctx = operatorCtx(m)
            ok(Availability.forCluster(morpheus, ctx.cluster.id, p(m).month as String))
        }
    }

    /**
     * One tenant's figures, to hand to that customer, built in the browser from JSON as the report is. With a clusterId, for that cluster's operators and from that
     * cluster alone; without one, for the master tenant and from every cluster. If the figures cannot be read this fails, rather than print a customer an empty record.
     */
    def availabilityExport(ViewModel<Map> m) {
        guarded {
            HealthContext ctx = p(m).clusterId != null ? operatorCtx(m) : null
            if (!ctx) {
                masterOnly(m, 'An export across clusters')
            }
            Object tenant = p(m).tenantId
            if (!tenant?.toString()?.isLong()) {
                throw new IllegalArgumentException('tenantId is required')
            }
            long now = System.currentTimeMillis()
            String month = (p(m).month as String) ?: Availability.monthKey(now)
            if (!(month ==~ /\d{4}-(0[1-9]|1[0-2])/)) {
                throw new IllegalArgumentException('month must be like 2030-01')
            }
            Map<Long, Map> docs = ctx ? [(ctx.cluster.id): Availability.doc(morpheus, ctx.cluster.id, now)] : Availability.allDocs(morpheus, now)
            Map r = Availability.tenantReport(docs, tenant as Long, month, now)
            if (!r.clusters) {
                return fail('No figures for that tenant in that month.', 404)
            }
            Audit.info(morpheus, m.user, ctx?.cluster, 'availability export generated', [tenantId: tenant, month: month])
            ok(r)
        }
    }

    /**
     * The read-only report on one cluster, for whoever runs it. It can leave the tenant that fetched it, so nothing about any tenant
     * goes in (see HealthReport). If the names to withhold cannot be read this fails, rather than risk printing one.
     */
    def report(ViewModel<Map> m) {
        guarded {
            HealthContext ctx = operatorCtx(m)
            Long id = ctx.cluster.id
            String historyError = FindingHistory.ERRORS[id]
            Map doc = null
            try {
                doc = FindingHistory.read(morpheus, id)
            } catch (Exception e) {
                historyError = e.message ?: e.class.simpleName
            }
            List<String> names = ctx.morpheusVms(ctx.hosts).collectMany { [it.name, it.label, it.tenant] } + ctx.networkNames() + [ctx.cluster.owner?.name]
            Map r = HealthReport.build([id: id, name: ctx.cluster.name, layout: ctx.layout().label], HealthMonitor.LAST[id], doc, historyError, names.findAll() as List<String>)
            Audit.info(morpheus, m.user, ctx.cluster, 'report generated')
            ok(r)
        }
    }

    def vlanTest(ViewModel<Map> m) {
        guarded {
            postOnly(m)
            HealthContext ctx = operatorCtx(m)
            String host = p(m).host as String
            if (!ctx.hosts.any { it.name == host }) {
                throw new IllegalArgumentException("${host} is not in cluster ${ctx.cluster.name}")
            }
            Map s = settings()
            User who = m.user
            ok(Jobs.start("VLAN test: ${host}".toString(), ctx.cluster.id, user(m)) { List<String> progress ->
                HostOps.vlanTest(morpheus, ctx, host, who, progress, s)
            }.toMap())
        }
    }

    /** Its status, inventory and network, read now through ipmitool. Read-only. */
    def controller(ViewModel<Map> m) {
        guarded {
            HealthContext ctx = operatorCtx(m)
            String host = p(m).host as String
            if (!host || !ctx.hosts.any { it.name == host }) {
                throw new IllegalArgumentException("${host} is not in cluster ${ctx.cluster.name}")
            }
            ok(HostOps.controller(ctx, host))
        }
    }

    /** Restarts the host's management controller, not the host. Confirmed by typing the host's name. */
    def controllerReset(ViewModel<Map> m) {
        guarded {
            postOnly(m)
            HealthContext ctx = operatorCtx(m)
            String host = p(m).host as String
            if (!host || !ctx.hosts.any { it.name == host }) {
                throw new IllegalArgumentException("${host} is not in cluster ${ctx.cluster.name}")
            }
            if (!(p(m).confirm as String)?.equalsIgnoreCase(host)) {
                throw new IllegalArgumentException("Type the host name, ${host}, to confirm")
            }
            Map s = settings()
            User who = m.user
            ok(Jobs.start("controller reset: ${host}".toString(), ctx.cluster.id, user(m)) { List<String> progress ->
                HostOps.resetController(morpheus, ctx, host, who, progress, s)
            }.toMap())
        }
    }

    /** Confirmed by typing the host's name. The checks run again on fresh state before Morpheus is asked. */
    def removeHost(ViewModel<Map> m) {
        guarded {
            postOnly(m)
            HealthContext ctx = operatorCtx(m)
            Map s = settings()
            String host = p(m).host as String
            if (!host || !ctx.hosts.any { it.name == host }) {
                throw new IllegalArgumentException("${host} is not in cluster ${ctx.cluster.name}")
            }
            if (!(p(m).confirm as String)?.equalsIgnoreCase(host)) {
                throw new IllegalArgumentException("Type the host name, ${host}, to confirm")
            }
            User who = m.user
            ok(Jobs.start("remove: ${host}".toString(), ctx.cluster.id, user(m)) { List<String> progress ->
                HostOps.removeHost(morpheus, ctx, host, who, progress, s)
            }.toMap())
        }
    }

    def raw(ViewModel<Map> m) {
        guarded {
            HealthContext ctx = operatorCtx(m)
            ok(ctx.raw(p(m).host as String))
        }
    }

    def fix(ViewModel<Map> m) {
        guarded {
            postOnly(m)
            HealthContext ctx = operatorCtx(m)
            String host = p(m).host as String
            String key = p(m).key as String
            if (!host || !ctx.hosts.any { it.name == host }) {
                throw new IllegalArgumentException("${host} is not in cluster ${ctx.cluster.name}")
            }
            if (!key || key.startsWith('fence-ack')) {
                throw new IllegalArgumentException('Not a fix this route runs')
            }
            Map s = settings()
            User who = m.user
            ok(Jobs.start("fix: ${key}".toString(), ctx.cluster.id, user(m)) { List<String> progress ->
                HealthMonitor.applyFix(morpheus, ctx, host, key, who, progress, s)
            }.toMap())
        }
    }

    private Fixes.FencePlan plan(HealthContext ctx, Integer node, Map s) {
        Map<String, HostFacts> facts = ctx.probe('quick', HealthContext.INTERACTIVE_PROBE_TIMEOUT_SEC)
        Fixes.fencePlan(ctx.input(facts, s.siteStandards as String), node)
    }

    def fencePlan(ViewModel<Map> m) {
        guarded {
            HealthContext ctx = operatorCtx(m)
            Fixes.FencePlan fp = plan(ctx, nodeId(m), settings())
            ok([nodeId: fp.nodeId, nodeName: fp.nodeName, waitingSec: fp.waitingSinceSec, runOn: fp.runOn, proven: fp.proven, evidence: fp.evidence,
                blockers: fp.blockers, affectedVms: fp.affectedVms])
        }
    }

    private static Integer nodeId(ViewModel<Map> m) {
        Object v = p(m).nodeId
        if (!v?.toString()?.isInteger()) {
            throw new IllegalArgumentException('nodeId is required')
        }
        v as Integer
    }

    /**
     * Acknowledges a failed node as fenced, on one survivor. The request names the node by typing it, and states it is
     * down when nothing proves it; the state is read again here, so a stale screen cannot acknowledge a node that came back.
     */
    def fenceAck(ViewModel<Map> m) {
        guarded {
            postOnly(m)
            HealthContext ctx = operatorCtx(m)
            Integer node = nodeId(m)
            String typed = p(m).confirm as String
            boolean attest = p(m).attest in [true, 'true', 'on']
            Map s = settings()
            User who = m.user
            ok(Jobs.start('emergency: acknowledge fenced node', ctx.cluster.id, user(m)) { List<String> progress ->
                progress << 'Reading the current state of every host'
                Fixes.FencePlan fp = plan(ctx, node, s)
                if (fp.blockers) {
                    Audit.warn(morpheus, who, ctx.cluster, 'fence acknowledgement refused', [node: node, why: fp.blockers.join('; ')])
                    throw new IllegalStateException(fp.blockers.join('; '))
                }
                if (!typed?.equalsIgnoreCase(fp.nodeName)) {
                    throw new IllegalArgumentException("Type the node name, ${fp.nodeName}, to confirm")
                }
                if (!fp.proven && !attest) {
                    throw new IllegalStateException("${fp.evidence} State that it is down to go on.")
                }
                progress << "Evidence: ${fp.evidence}".toString()
                Audit.warn(morpheus, who, ctx.cluster, 'fence acknowledgement started', [node: "${fp.nodeName} (${node})", proven: fp.proven, attested: attest, evidence: fp.evidence, runOn: fp.runOn])
                progress << "Acknowledging ${fp.nodeName} from ${fp.runOn}".toString()
                Map r = ctx.run(ctx.hosts.find { it.name == fp.runOn }, Fixes.fenceAckScript(node))
                (r.output as String)?.readLines()?.findAll { it.trim() }?.take(10)?.each { progress << "  ${it}".toString() }
                if (r.exit != 0) {
                    Audit.warn(morpheus, who, ctx.cluster, 'fence acknowledgement did not clear the freeze', [node: node, exit: r.exit])
                    throw new IllegalStateException("The acknowledgement ran, but the lockspaces did not recover (exit ${r.exit})")
                }
                Audit.warn(morpheus, who, ctx.cluster, 'fence acknowledgement done: lockspaces recovered', [node: "${fp.nodeName} (${node})"])
                progress << 'Checking again. VMs that were stuck may have been powered off by the HVM agent: start them after checking.'
                Map view = HealthMonitor.run(morpheus, ctx, s, 'quick', false)
                view.previouslyStuck = fp.affectedVms
                view
            }.toMap())
        }
    }
}
