package org.hvmhealth.morpheus

import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.Plugin
import com.morpheusdata.core.data.DataQuery
import com.morpheusdata.model.ComputeServerGroup
import com.morpheusdata.model.OperationNotification
import groovy.util.logging.Slf4j
import org.hvmhealth.ClusterHealthPlugin
import org.hvmhealth.checks.HealthChecker
import org.hvmhealth.fixes.Fixes
import org.hvmhealth.model.HostFacts
import org.hvmhealth.standards.Layout

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * The automatic side: a quick check of every HVM cluster every few minutes, so a freeze raises an alarm rather than
 * waiting to be noticed, a full check on a longer interval, and (only when switched on in the settings) automatic fixes.
 *
 * Automatic fixes honour exactly the same guards as the buttons. SAFE fixes run on any host. MAINTENANCE fixes run only on
 * a host an operator has already put in maintenance mode, so putting a host in maintenance is the consent. EMERGENCY
 * actions are never automatic. A finding is tried once per RETRY_MS, so a fix that does not help cannot loop.
 */
@Slf4j
class HealthMonitor {
    static final String CATEGORY = 'hvm-cluster-health'
    static final long RETRY_MS = 6L * 3600 * 1000
    static final long DEFAULT_QUICK_MIN = 5
    static final long DEFAULT_FULL_HOURS = 24

    /** clusterId -> the last evaluation, as the tab shows it. In memory: a reload checks again. */
    static final Map<Long, Map> LAST = new ConcurrentHashMap<>()
    private static final Map<Long, List<HealthChecker.Result>> LAST_FULL = new ConcurrentHashMap<>()
    /** Findings only a full check produces (ring and packet tests, journal counts, management controllers). */
    static final Set<String> FULL_ONLY = ['NET-03', 'NET-09', 'MIG-02', 'GFS-06', 'FEN-01'] as Set
    private static final Map<String, Long> ATTEMPTS = new ConcurrentHashMap<>()
    private static ScheduledExecutorService sweeper

    static synchronized void start(MorpheusContext morpheus, Plugin plugin) {
        stop()
        sweeper = Executors.newSingleThreadScheduledExecutor { Runnable r ->
            Thread t = new Thread(r, 'hvm-cluster-health-monitor')
            t.daemon = true
            t
        }
        sweeper.scheduleWithFixedDelay({
            try {
                sweep(morpheus, plugin)
            } catch (Throwable e) {
                log.warn("cluster health monitor pass failed: ${e.message}")
            }
        }, 2, 1, TimeUnit.MINUTES)
    }

    static synchronized void stop() {
        sweeper?.shutdownNow()
        sweeper = null
    }

    static long quickMinutes(Map settings) {
        String s = settings?.monitorMinutes as String
        s?.isLong() ? Math.max(0L, s as Long) : DEFAULT_QUICK_MIN
    }

    static long fullHours(Map settings) {
        String s = settings?.fullCheckHours as String
        s?.isLong() ? Math.max(0L, s as Long) : DEFAULT_FULL_HOURS
    }

    /** "quick", "full", or null when this cluster is not due yet. */
    static String due(Map settings, Map last, long now = System.currentTimeMillis()) {
        long quick = quickMinutes(settings) * 60_000L
        if (quick <= 0) {
            return null
        }
        long full = fullHours(settings) * 3_600_000L
        boolean fullDue = full > 0 && (!last?.fullAt || now - (last.fullAt as Long) >= full)
        if (fullDue) {
            return 'full'
        }
        !last?.at || now - (last.at as Long) >= quick ? 'quick' : null
    }

    private static void sweep(MorpheusContext morpheus, Plugin plugin) {
        Map settings = ClusterHealthPlugin.readSettings(morpheus, plugin)
        morpheus.services.cluster.list(new DataQuery()).findAll { ComputeServerGroup c -> monitored(c) }.each { ComputeServerGroup c ->
            if (Jobs.busy(c.id)) {
                return
            }
            String mode = due(settings, LAST[c.id])
            if (!mode) {
                return
            }
            try {
                run(morpheus, new HealthContext(morpheus, c.id), settings, mode, true)
            } catch (Throwable e) {
                log.warn("automatic check of ${c.name} failed: ${e.message}")
            }
        }
    }

    /** HVM clusters this plugin understands; Kubernetes on HVM and external KVM are not. */
    static boolean monitored(ComputeServerGroup c) {
        String code = c?.layout?.code
        code ? Layout.of(code).version != null : (c?.type?.code ?: '').toLowerCase() ==~ /.*(mvm|hvm).*/ && !((c?.type?.code ?: '').toLowerCase().contains('kube'))
    }

    /** One evaluation; stores it, keeps the alarm in line, and applies automatic fixes when told to. */
    static Map run(MorpheusContext morpheus, HealthContext ctx, Map settings, String mode, boolean automatic) {
        Map<String, HostFacts> facts = ctx.probe(mode)
        HealthChecker.Input in_ = ctx.input(facts, settings?.siteStandards as String)
        List<HealthChecker.Result> rs = new HealthChecker().check(in_)
        // a quick check does not run these, so their last answer stands: otherwise they would come and go every few minutes
        if (mode == 'full') {
            LAST_FULL[ctx.cluster.id] = rs.findAll { it.id in FULL_ONLY }
        } else {
            rs = (rs + (LAST_FULL[ctx.cluster.id] ?: [])).sort(false) { a, b -> HealthChecker.rank(a) <=> HealthChecker.rank(b) ?: a.id <=> b.id ?: (a.host ?: '') <=> (b.host ?: '') }
        }
        Map before = LAST[ctx.cluster.id]
        Map view = HealthContext.view(in_, rs, mode)
        view.fullAt = mode == 'full' ? view.at : before?.fullAt
        LAST[ctx.cluster.id] = view
        setAlarms(morpheus, ctx, rs)
        if (before?.overall != view.overall && before != null) {
            Audit.warn(morpheus, null, ctx.cluster, "health changed from ${before.overall} to ${view.overall}", [failing: failingIds(rs)])
        }
        if (automatic) {
            autoFix(morpheus, ctx, in_, rs, settings)
        }
        view
    }

    private static String failingIds(List<HealthChecker.Result> rs) {
        rs.findAll { it.status == HealthChecker.Status.FAIL }*.id.unique().join(',') ?: 'none'
    }

    private static boolean on(Object v) {
        v in [true, 'true', 'on']
    }

    /** The findings an automatic pass may act on, given the settings and the guards. */
    static List<HealthChecker.Result> autoFixable(HealthChecker.Input in_, List<HealthChecker.Result> rs, Map settings, long now = System.currentTimeMillis()) {
        rs.findAll { HealthChecker.Result r ->
            if (!r.fix || !r.host || !(r.status in [HealthChecker.Status.FAIL, HealthChecker.Status.WARN])) {
                return false
            }
            boolean allowed = (r.tier == HealthChecker.Tier.SAFE && on(settings?.autoFixSafe)) || (r.tier == HealthChecker.Tier.MAINTENANCE && on(settings?.autoFixMaintenance))
            if (!allowed || Fixes.blockers(r.tier, in_, r.host)) {
                return false
            }
            Long tried = ATTEMPTS["${r.host}|${r.fix}".toString()]
            !tried || now - tried >= RETRY_MS
        }.unique { "${it.host}|${it.fix}".toString() }
    }

    private static void autoFix(MorpheusContext morpheus, HealthContext ctx, HealthChecker.Input in_, List<HealthChecker.Result> rs, Map settings) {
        autoFixable(in_, rs, settings).each { HealthChecker.Result r ->
            ATTEMPTS["${r.host}|${r.fix}".toString()] = System.currentTimeMillis()
            try {
                Jobs.start("automatic fix: ${r.fix}".toString(), ctx.cluster.id, 'automatic') { List<String> progress ->
                    applyFix(morpheus, ctx, r.host, r.fix, null, progress, settings)
                }
            } catch (IllegalStateException busy) {
                ATTEMPTS.remove("${r.host}|${r.fix}".toString())   // another job has the cluster: try next pass
            }
        }
    }

    /**
     * Applies one fix: probes again, re-evaluates the guards on that fresh state, runs, then checks again. The guards are
     * always evaluated here, so no route or automatic path can skip them.
     */
    static Map applyFix(MorpheusContext morpheus, HealthContext ctx, String host, String key, com.morpheusdata.model.User who, List<String> progress, Map settings) {
        progress << "${host}: reading the current state of every host".toString()
        Map<String, HostFacts> facts = ctx.probe('quick')
        HealthChecker.Input in_ = ctx.input(facts, settings?.siteStandards as String)
        HostFacts f = facts[host]
        if (!f?.complete) {
            throw new IllegalStateException("${host} did not answer; nothing changed")
        }
        Fixes.Fix fix = Fixes.fix(key, f, (in_.standards.val('ntpServers') ?: []) as List<String>)
        List<String> blocked = Fixes.blockers(fix.tier, in_, host)
        if (blocked) {
            Audit.warn(morpheus, who, ctx.cluster, "fix refused: ${key}", [host: host, why: blocked.join('; ')])
            throw new IllegalStateException("Not run: ${blocked.join('; ')}")
        }
        progress << "${host}: ${fix.title}".toString()
        Audit.info(morpheus, who, ctx.cluster, "fix started: ${key}", [host: host, tier: fix.tier.name()])
        Map r = ctx.run(ctx.hosts.find { it.name == host }, fix.script)
        (r.output as String)?.readLines()?.findAll { it.trim() }?.take(40)?.each { progress << "  ${it}".toString() }
        if (r.exit != 0) {
            Audit.warn(morpheus, who, ctx.cluster, "fix failed: ${key}", [host: host, exit: r.exit])
            throw new IllegalStateException("${host}: ${fix.title} failed (exit ${r.exit})")
        }
        Audit.info(morpheus, who, ctx.cluster, "fix done: ${key}", [host: host])
        progress << 'Checking again'
        run(morpheus, ctx, settings, 'quick', false)
    }

    // ------------------------------------------------------------------ alarms

    static String alarmPrefix(Long clusterId) {
        "${CATEGORY}-${clusterId}-".toString()
    }

    /**
     * One alarm per finding, as Morpheus has no way for a plugin to raise monitoring checks or incidents: error for a failure,
     * warning for a warning. A finding's identity is stable across runs, so an alarm lasts as long as its finding does.
     */
    static List<Map> alarmsFor(Long clusterId, String clusterName, List<HealthChecker.Result> rs) {
        rs.findAll { it.status in [HealthChecker.Status.FAIL, HealthChecker.Status.WARN] }.collect { HealthChecker.Result r ->
            String key = "${r.id}|${r.host ?: ''}|${r.title}".toString().digest('MD5').take(12)
            [uniqueId: "${alarmPrefix(clusterId)}${r.id}-${key}".toString(), host: r.host, status: r.status == HealthChecker.Status.FAIL ? 'error' : 'warning',
             name: "${r.id} ${r.title}".toString(),
             message: "${clusterName}${r.host ? ' / ' + r.host : ''}: ${r.title}: ${r.observed} (expected ${r.expected}). See the cluster's Cluster Health tab.".toString()]
        }.unique { it.uniqueId }
    }

    /**
     * Brings this plugin's alarms for the cluster in line with its findings: only ever its own, by uniqueId prefix, so
     * Morpheus's other alarms are never touched. They sit on the host a finding is about, otherwise on the cluster, in
     * the tenant that owns the cluster, so customers never see them.
     */
    static void setAlarms(MorpheusContext morpheus, HealthContext ctx, List<HealthChecker.Result> rs) {
        ComputeServerGroup cluster = ctx.cluster
        List<Map> want = alarmsFor(cluster.id, cluster.name, rs)
        Map<String, Long> hostIds = ctx.hosts.collectEntries { [(it.name): it.id] }
        String prefix = alarmPrefix(cluster.id)
        String legacy = "${CATEGORY}-${cluster.id}".toString()   // the single per-cluster alarm of the first builds
        try {
            MorpheusSession.run(morpheus) {
                List<OperationNotification> open = morpheus.services.operationNotification.list(new DataQuery().withFilter('category', CATEGORY))
                        .findAll { it.active != false && (it.uniqueId?.startsWith(prefix) || it.uniqueId == legacy) }
                Map<String, OperationNotification> byId = open.collectEntries { [(it.uniqueId): it] }
                open.findAll { OperationNotification o ->
                    Map w = want.find { it.uniqueId == o.uniqueId }
                    !w || w.status != o.status || w.message != o.statusMessage
                }.each {
                    morpheus.services.operationNotification.clearAlarm(it)
                    byId.remove(it.uniqueId)
                }
                want.findAll { !byId.containsKey(it.uniqueId) }.each { Map a ->
                    Long hostId = hostIds[a.host as String]
                    def r = morpheus.services.operationNotification.createAlarm(new OperationNotification(
                            account: cluster.owner, category: CATEGORY, eventKey: CATEGORY, uniqueId: a.uniqueId as String, name: a.name as String,
                            resourceName: (a.host ?: cluster.name) as String, refType: hostId ? 'computeServer' : 'computeServerGroup', refId: hostId ?: cluster.id,
                            status: a.status as String, statusMessage: a.message as String, startDate: new Date(), acknowledged: false, active: true))
                    if (r != null && !r.success) {
                        log.warn("cluster health alarm on ${cluster.name} refused: ${r.msg ?: r.errors}")
                    }
                }
            }
        } catch (Throwable e) {
            log.warn("cluster health alarms on ${cluster.name} failed: ${e.message}", e)
        }
    }
}
