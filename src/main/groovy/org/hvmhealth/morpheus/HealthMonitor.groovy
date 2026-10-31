package org.hvmhealth.morpheus

import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.Plugin
import com.morpheusdata.core.data.DataQuery
import com.morpheusdata.model.Account
import com.morpheusdata.model.ComputeServerGroup
import com.morpheusdata.model.OperationNotification
import groovy.util.logging.Slf4j
import org.hvmhealth.ClusterHealthPlugin
import org.hvmhealth.checks.HealthChecker
import org.hvmhealth.fixes.Fixes
import org.hvmhealth.model.HostFacts
import org.hvmhealth.standards.Layout

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit

/**
 * The automatic side: a quick check of every HVM cluster every few minutes, so a freeze raises an alarm rather than
 * waiting to be noticed, a full check on a longer interval, and (only when switched on in the settings) automatic fixes.
 *
 * Automatic fixes honour exactly the same guards as the buttons. SAFE fixes run on any host. MAINTENANCE fixes run only on
 * a host an operator has already put in maintenance mode, so putting a host in maintenance is the consent. EMERGENCY
 * actions are never automatic. A finding is tried once per RETRY_MS, so a fix that does not help cannot loop. The switches
 * are global, so nothing runs on a cluster until the autoFixClusters setting names it.
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
    static final Set<String> FULL_ONLY = ['NET-03', 'NET-09', 'MIG-02', 'GFS-06', 'FEN-01', 'HW-01', 'HW-02', 'HW-03', 'HW-04', 'HW-05', 'HW-06', 'HW-07', 'HST-02', 'HST-03', 'NET-12', 'NET-13'] as Set
    private static final Map<String, Long> ATTEMPTS = new ConcurrentHashMap<>()
    /** Clusters waiting for a checker or being checked: one silent host takes minutes, and must not hold the others up or be queued twice. */
    private static final Set<Long> CHECKING = ConcurrentHashMap.newKeySet()
    /** clusterId -> when an automatic check last could not run. It waits the normal interval, as LAST has not moved. */
    private static final Map<Long, Long> COULD_NOT_RUN = new ConcurrentHashMap<>()
    private static final int CHECKERS = 4
    private static ScheduledExecutorService sweeper
    private static ExecutorService checkers
    private static Plugin plugin

    static synchronized void start(MorpheusContext morpheus, Plugin plugin) {
        stop()
        HealthMonitor.plugin = plugin
        ThreadFactory daemons = { Runnable r ->
            Thread t = new Thread(r, 'hvm-cluster-health-monitor')
            t.daemon = true
            t
        } as ThreadFactory
        CHECKING.clear()
        checkers = Executors.newFixedThreadPool(CHECKERS, daemons)
        sweeper = Executors.newSingleThreadScheduledExecutor(daemons)
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
        checkers?.shutdownNow()
        sweeper = null
        checkers = null
        plugin = null
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
        if (FindingHistory.pruneOrphans(morpheus)) {
            clearOrphanAlarms(morpheus, quickMinutes(settings) > 0)
        }
        long now = System.currentTimeMillis()
        morpheus.services.cluster.list(new DataQuery()).findAll { ComputeServerGroup c -> monitored(c) }.each { ComputeServerGroup c ->
            String mode = due(settings, LAST[c.id], now)
            Long failed = COULD_NOT_RUN[c.id]
            if (!mode || Jobs.busy(c.id) || (failed && now - failed < quickMinutes(settings) * 60_000L) || !CHECKING.add(c.id)) {
                return
            }
            checkers.submit {
                try {
                    automaticCheck(morpheus, c, settings, mode)
                } finally {
                    CHECKING.remove(c.id)
                }
            }
        }
    }

    /** A check that cannot run is reported on the cluster, as nothing else would show that monitoring has stopped. What it last found stays as it was. */
    private static void automaticCheck(MorpheusContext morpheus, ComputeServerGroup c, Map settings, String mode) {
        HealthContext ctx = null
        try {
            ctx = new HealthContext(morpheus, c.id)
            run(morpheus, ctx, settings, mode, true)
            COULD_NOT_RUN.remove(c.id)
        } catch (Throwable e) {
            if (!checkers || checkers.shutdown) {
                return   // the plugin is stopping: that is not a finding
            }
            log.warn("automatic check of ${c.name} failed: ${e.message}")
            COULD_NOT_RUN[c.id] = System.currentTimeMillis()
            if (ctx) {
                setAlarms(morpheus, ctx, [new HealthChecker.Result('PRB-02', 'Automatic check ran', HealthChecker.Severity.HIGH, HealthChecker.Status.FAIL, null, FindingHistory.shortError(e), 'the check runs', 'plugin')], true)
            }
        }
    }

    /**
     * Daily. Nothing else ever updates the alarms of a cluster that has gone, or of any cluster once automatic checks are off, so those are cleared.
     * Never when Morpheus lists no clusters. A check pressed by hand raises its alarms again, until the next clean-up.
     */
    private static void clearOrphanAlarms(MorpheusContext morpheus, boolean monitoring) {
        try {
            Set<Long> live = morpheus.services.cluster.list(new DataQuery())*.id as Set<Long>
            if (!live) {
                return
            }
            MorpheusSession.run(morpheus) {
                morpheus.services.operationNotification.list(new DataQuery().withFilter('category', CATEGORY)).findAll { OperationNotification o ->
                    def m = (o.uniqueId ?: '') =~ /^${CATEGORY}-(\d+)(-|$)/
                    o.active != false && m.find() && !(monitoring && live.contains(m.group(1) as Long))
                }.each { morpheus.services.operationNotification.clearAlarm(it) }
            }
        } catch (Throwable e) {
            log.warn("cluster health alarm clean-up failed: ${e.message}")
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
        FindingHistory.record(morpheus, ctx, in_, rs, view, LAST_FULL[ctx.cluster.id] != null)
        view.historyError = FindingHistory.ERRORS[ctx.cluster.id]
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

    /**
     * Whether the autoFixClusters setting names this cluster: ids or names, comma separated, and empty names none. A number is an
     * id and only an id, so one cluster's name can never select another by its id. Names ignore case and can repeat across tenants.
     */
    static boolean autoFixNamed(Map settings, Long clusterId, String clusterName) {
        ((settings?.autoFixClusters ?: '') as String).tokenize(',')*.trim().findAll().any { String t ->
            t.isLong() ? clusterId != null && (t as Long) == clusterId : t.equalsIgnoreCase(clusterName)
        }
    }

    /** Why an automatic pass may not apply a fix of this tier to this cluster; null when it may. */
    static String autoFixRefusal(HealthChecker.Tier tier, HealthChecker.Input in_, Map settings) {
        if (!((tier == HealthChecker.Tier.SAFE && on(settings?.autoFixSafe)) || (tier == HealthChecker.Tier.MAINTENANCE && on(settings?.autoFixMaintenance)))) {
            return "automatic fixes of the ${tier} tier are off".toString()
        }
        autoFixNamed(settings, in_.clusterId, in_.clusterName) ? null : "${in_.clusterName} is not in the autoFixClusters setting".toString()
    }

    /** The findings an automatic pass may act on, given the settings and the guards. */
    static List<HealthChecker.Result> autoFixable(HealthChecker.Input in_, List<HealthChecker.Result> rs, Map settings, long now = System.currentTimeMillis()) {
        rs.findAll { HealthChecker.Result r ->
            if (!r.fix || !r.host || !(r.status in [HealthChecker.Status.FAIL, HealthChecker.Status.WARN])) {
                return false
            }
            if (autoFixRefusal(r.tier, in_, settings) || Fixes.blockers(r.tier, in_, r.host)) {
                return false
            }
            Long tried = ATTEMPTS[attemptKey(in_.clusterId, r)]
            !tried || now - tried >= RETRY_MS
        }.unique { "${it.host}|${it.fix}".toString() }
    }

    /** Host names can repeat across clusters, and one's retry must not hold up the other's. */
    private static String attemptKey(Long clusterId, HealthChecker.Result r) {
        "${clusterId}|${r.host}|${r.fix}".toString()
    }

    private static void autoFix(MorpheusContext morpheus, HealthContext ctx, HealthChecker.Input in_, List<HealthChecker.Result> rs, Map settings) {
        autoFixable(in_, rs, settings).each { HealthChecker.Result r ->
            ATTEMPTS[attemptKey(in_.clusterId, r)] = System.currentTimeMillis()
            try {
                Jobs.start("automatic fix: ${r.fix}".toString(), ctx.cluster.id, 'automatic') { List<String> progress ->
                    applyFix(morpheus, ctx, r.host, r.fix, null, progress, settings, true)
                }
            } catch (IllegalStateException busy) {
                ATTEMPTS.remove(attemptKey(in_.clusterId, r))   // another job has the cluster: try next pass
            }
        }
    }

    /**
     * Applies one fix: probes again, re-evaluates the guards on that fresh state, runs, then checks again. The guards are
     * always evaluated here, so no route or automatic path can skip them. An automatic fix must also still be allowed for
     * this cluster by the settings as they are now, not as they were when the pass chose it.
     */
    static Map applyFix(MorpheusContext morpheus, HealthContext ctx, String host, String key, com.morpheusdata.model.User who, List<String> progress, Map settings, boolean automatic = false) {
        progress << "${host}: reading the current state of every host".toString()
        Map<String, HostFacts> facts = ctx.probe('quick')
        HealthChecker.Input in_ = ctx.input(facts, settings?.siteStandards as String)
        HostFacts f = facts[host]
        if (!f?.complete) {
            throw new IllegalStateException("${host} did not answer; nothing changed")
        }
        Fixes.Fix fix = Fixes.fix(key, f, (in_.standards.val('ntpServers') ?: []) as List<String>)
        List<String> blocked = Fixes.blockers(fix.tier, in_, host)
        if (automatic) {
            String refused = autoFixRefusal(fix.tier, in_, plugin ? ClusterHealthPlugin.readSettings(morpheus, plugin) : settings)
            blocked = refused ? blocked + refused : blocked
        }
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

    private static String alarmId(Long clusterId, HealthChecker.Result r) {
        String key = "${r.id}|${r.host ?: ''}|${r.title}".toString().digest('MD5').take(12)
        "${alarmPrefix(clusterId)}${r.id}-${key}".toString()
    }

    /**
     * One alarm per finding, as Morpheus has no way for a plugin to raise monitoring checks or incidents: error for a failure,
     * warning for a warning. A finding's identity is stable across runs, so an alarm lasts as long as its finding does. Its message is from
     * when it was raised: findings such as "last 900 s ago" change every run, and replacing the alarm for that would lose its acknowledgement and start time.
     */
    static List<Map> alarmsFor(Long clusterId, String clusterName, List<HealthChecker.Result> rs) {
        rs.findAll { it.status in [HealthChecker.Status.FAIL, HealthChecker.Status.WARN] }.collect { HealthChecker.Result r ->
            [uniqueId: alarmId(clusterId, r), host: r.host, status: r.status == HealthChecker.Status.FAIL ? 'error' : 'warning',
             name: "${r.id} ${r.title}".toString(),
             message: "${clusterName}${r.host ? ' / ' + r.host : ''}: ${r.title}: ${r.observed} (expected ${r.expected}). See the cluster's Cluster Health tab.".toString()]
        }.unique { it.uniqueId }
    }

    /** The plugin API cannot list tenants, but the master tenant is the first one created. Checked, not assumed. */
    static Account masterAccount(MorpheusContext morpheus) {
        Account a = Tenancy.loader(morpheus).call(1L)
        a?.masterAccount == true ? a : null
    }

    /**
     * Every cluster's last result, for the master tenant's fleet view. A cluster not yet checked since the plugin loaded shows its
     * last stored result, marked as such, or failing that says so: an unknown cluster must not read as a healthy one.
     */
    static List<Map> fleet(MorpheusContext morpheus) {
        morpheus.services.cluster.list(new DataQuery()).findAll { monitored(it) }.collect { ComputeServerGroup c ->
            Map v = LAST[c.id]
            Map s = v ? FindingHistory.summaryOf(v) : FindingHistory.lastKnown(morpheus, c.id)
            [id: c.id, name: c.name, owner: c.owner?.name, overall: s?.overall ?: 'NOT CHECKED', layout: s?.layout, at: s?.at, counts: s?.counts, notReady: s?.notReady,
             frozen: s?.frozen ?: false, lastKnown: !v && s != null]
        }.sort { a, b -> severityRank(a.overall as String) <=> severityRank(b.overall as String) ?: count(b, 'fail') <=> count(a, 'fail') ?: count(b, 'warn') <=> count(a, 'warn') ?: (a.name as String) <=> (b.name as String) }
    }

    private static int count(Map row, String kind) {
        ((row.counts as Map)?.get(kind) ?: 0) as int
    }

    static int severityRank(String overall) {
        ['CRITICAL': 0, 'NOT CHECKED': 1, 'DEGRADED': 2, 'WARNING': 3, 'HEALTHY': 4].getOrDefault(overall, 5)
    }

    /**
     * Brings this plugin's alarms for the cluster in line with its findings: only ever its own, by uniqueId prefix, so
     * Morpheus's other alarms are never touched. They sit on the host a finding is about, otherwise on the cluster, in
     * the tenant that owns the cluster, so customers never see them. A finding that could not be judged this time keeps its alarm, as does
     * every alarm when {@code onlyAdd} is set, for a run that found nothing out.
     */
    static void setAlarms(MorpheusContext morpheus, HealthContext ctx, List<HealthChecker.Result> rs, boolean onlyAdd = false) {
        ComputeServerGroup cluster = ctx.cluster
        List<Map> want = alarmsFor(cluster.id, cluster.name, rs)
        // the master tenant cannot otherwise see the alarms of a cluster another tenant owns, so it gets its own copy
        Account master = masterAccount(morpheus)
        if (master && cluster.owner?.id != master.id) {
            want = want.collect { [*:it, account: cluster.owner] } + want.collect { [*:it, account: master, uniqueId: "${it.uniqueId}-master".toString()] }
        } else {
            want = want.collect { [*:it, account: cluster.owner] }
        }
        String prefix = alarmPrefix(cluster.id)
        String legacy = "${CATEGORY}-${cluster.id}".toString()   // the single per-cluster alarm of the first builds
        Set<String> unknown = rs.findAll { it.status == HealthChecker.Status.NOT_CHECKED }.collectMany { [alarmId(cluster.id, it), "${alarmId(cluster.id, it)}-master".toString()] } as Set<String>
        try {
            Map<String, Long> hostIds = ctx.hosts.collectEntries { [(it.name): it.id] }
            MorpheusSession.run(morpheus) {
                List<OperationNotification> open = morpheus.services.operationNotification.list(new DataQuery().withFilter('category', CATEGORY))
                        .findAll { it.active != false && (it.uniqueId?.startsWith(prefix) || it.uniqueId == legacy) }
                Map<String, OperationNotification> byId = open.collectEntries { [(it.uniqueId): it] }
                open.findAll { OperationNotification o ->
                    Map w = want.find { it.uniqueId == o.uniqueId }
                    w ? w.status != o.status : !onlyAdd && !unknown.contains(o.uniqueId)
                }.each {
                    morpheus.services.operationNotification.clearAlarm(it)
                    byId.remove(it.uniqueId)
                }
                want.findAll { !byId.containsKey(it.uniqueId) }.each { Map a ->
                    Long hostId = hostIds[a.host as String]
                    def r = morpheus.services.operationNotification.createAlarm(new OperationNotification(
                            account: a.account as Account, category: CATEGORY, eventKey: CATEGORY, uniqueId: a.uniqueId as String, name: a.name as String,
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
