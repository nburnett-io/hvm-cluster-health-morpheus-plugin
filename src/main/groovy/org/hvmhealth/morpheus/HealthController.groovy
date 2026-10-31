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
import org.hvmhealth.checks.HealthChecker
import org.hvmhealth.fixes.Fixes
import org.hvmhealth.model.HostFacts
import org.hvmhealth.standards.Standards

/**
 * Routes under /plugin/hvm-health. Morpheus enforces the role permission; on top of that every route refuses anyone who
 * is not an operator of this cluster (see Tenancy), so tier 3 and customers get nothing whatever their role grants.
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
                Route.build('/hvm-health/check', 'check', read),
                Route.build('/hvm-health/job', 'job', read),
                Route.build('/hvm-health/raw', 'raw', read),
                Route.build('/hvm-health/fix', 'fix', full),
                Route.build('/hvm-health/time-source', 'timeSource', full),
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

    private HealthContext operatorCtx(ViewModel<Map> m) {
        HealthContext ctx = new HealthContext(morpheus, clusterId(m))
        if (!Tenancy.isOperator(m.user, ctx.cluster, Tenancy.operatorTenantIds(settings()), Tenancy.loader(morpheus))) {
            Audit.warn(morpheus, m.user, ctx.cluster, 'refused: tenant is not an operator of this cluster', [tenant: m.user?.account?.name])
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
            ok([cluster: ctx.cluster.name, layout: ctx.layout().label, last: HealthMonitor.LAST[ctx.cluster.id], standards: standards, standardsError: standardsError,
                canFix: canFix(m.user), canEmergency: canEmergency(m.user),
                monitorMinutes: HealthMonitor.quickMinutes(s), fullHours: HealthMonitor.fullHours(s),
                autoFixSafe: s.autoFixSafe in [true, 'true', 'on'], autoFixMaintenance: s.autoFixMaintenance in [true, 'true', 'on'],
                jobs: Jobs.forCluster(ctx.cluster.id).findAll { it.state == 'running' }.collect { it.toMap() }])
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
            HealthContext ctx = operatorCtx(m)
            List<String> servers = ((p(m).servers ?: '') as String).tokenize(', ;\n\t').unique()
            ctx.saveTimeSources(servers)
            Audit.info(morpheus, m.user, ctx.cluster, 'cluster time sources set', [servers: servers.join(',') ?: 'none'])
            Map s = settings()
            ok(Jobs.start('check', ctx.cluster.id, user(m)) { List<String> progress ->
                progress << "Verifying every host against ${servers.join(', ') ?: 'no defined time source'}".toString()
                HealthMonitor.run(morpheus, ctx, s, 'quick', false)
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
        Map<String, HostFacts> facts = ctx.probe('quick')
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
