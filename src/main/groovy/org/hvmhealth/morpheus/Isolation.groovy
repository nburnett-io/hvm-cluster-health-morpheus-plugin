package org.hvmhealth.morpheus

import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.model.ComputeServerGroup
import groovy.util.logging.Slf4j
import org.hvmhealth.checks.HealthChecker
import org.hvmhealth.checks.HealthChecker.Result
import org.hvmhealth.checks.HealthChecker.Severity
import org.hvmhealth.checks.HealthChecker.Status
import org.hvmhealth.checks.HealthChecker.Tier

import java.util.concurrent.ConcurrentHashMap

/**
 * NET-12 and NET-13 run on every check, where a network being changed could look like one tenant reaching another for a moment. So a finding counts
 * only once two runs in a row have seen it, and until then is a warning that says so. Its title differs while it does, which gives a confirming alarm its own
 * identity: an alarm's text is from when it was raised, and "seen once" must not outlive that.
 *
 * Runs that could not read a host's ports say nothing either way: what it last had stands, as the finding still needs someone to look at it, and the count waits.
 * In memory, so a reload starts again, except for what the stored history shows was already open.
 */
@Slf4j
class Isolation {
    static final Set<String> CHECKS = ['NET-12', 'NET-13'] as Set
    /** Two looks this close are one: a check pressed twice, or a run queued straight after another. */
    static final long MIN_GAP_MS = 60_000L
    private static final String CONFIRMING = ' (confirming)'

    /** clusterId -> finding -> [at: first sighting, confirmed, audited, result: as the checker last gave it]. */
    private static final Map<Long, Map<String, Map>> SEEN = new ConcurrentHashMap<>()
    /** Clusters whose stored history has been asked what was open. Once per load, and only with a finding to ask about. */
    private static final Set<Long> SEEDED = ConcurrentHashMap.newKeySet()

    /** NET-12 and NET-13 need the two-sighting confirmation below; the SEC-* findings are a stable read that does not flap, so they are only named SECURITY here, not run through it. */
    static String alarmName(Result r) {
        "${r.id in CHECKS || r.id?.startsWith('SEC-') ? 'SECURITY: ' : ''}${r.id} ${r.title}".toString()
    }

    /** The results as this run should count them, with the audit entry for each finding newly failing. Never fails a check. */
    static List<Result> confirm(MorpheusContext morpheus, ComputeServerGroup cluster, HealthChecker.Input in_, List<Result> rs, long now = System.currentTimeMillis()) {
        Map judged = judge(cluster.id, in_, rs, now) { (FindingHistory.read(morpheus, cluster.id)?.episodes ?: []) as List<Map> }
        (judged.audit as List<Result>).each { Result r ->
            // the finding's own text can name another tenant, and this entry lands in the cluster owner's log
            Audit.warn(morpheus, null, cluster, "tenant isolation finding confirmed: ${r.id}".toString(), [host: r.host, finding: r.title])
        }
        judged.results as List<Result>
    }

    /**
     * @param stored the history's episodes, asked for at most once a load; throwing just means no help from it this time
     * @return [results, audit]
     */
    static Map judge(Long clusterId, HealthChecker.Input in_, List<Result> rs, long now, Closure<List<Map>> stored) {
        List<Result> found = rs.findAll { it.id in CHECKS && it.status in [Status.FAIL, Status.WARN] }
        Map<String, Map> open = found && !SEEDED.contains(clusterId) ? openEpisodes(clusterId, stored) : [:]
        synchronized (SEEN) {
            Map<String, Map> before = SEEN[clusterId] ?: [:]
            Map<String, Map> after = [:]
            List<Result> audit = []
            List<Result> shown = found.collect { Result r ->
                String k = "${r.id}|${r.host}|${r.title}".toString()
                Map e = sighting(before[k], r, open, now)
                if (e.confirmed && r.status == Status.FAIL && !e.audited) {
                    e.audited = true
                    audit << r
                }
                after[k] = e
                e.confirmed ? r : held(r)
            }
            before.each { String k, Map e ->
                Result last = e.result as Result
                if (!after.containsKey(k) && cannotTell(in_, last)) {
                    after[k] = e
                    shown << carried(e.confirmed ? last : held(last))
                }
            }
            if (after) {
                SEEN[clusterId] = after
            } else {
                SEEN.remove(clusterId)
            }
            [results: (rs - found + shown).sort(false) { a, b -> HealthChecker.rank(a) <=> HealthChecker.rank(b) ?: a.id <=> b.id ?: (a.host ?: '') <=> (b.host ?: '') }, audit: audit]
        }
    }

    private static Map sighting(Map prior, Result r, Map<String, Map> open, long now) {
        if (prior) {
            return [at: prior.at, confirmed: prior.confirmed || now - (prior.at as long) >= MIN_GAP_MS, audited: prior.audited, result: r]
        }
        // open since before a reload, counted or still confirming: either way this is not its first sighting
        Map whole = open[FindingHistory.key(r.id, r.host, r.title)]
        boolean once = open.containsKey(FindingHistory.key(r.id, r.host, heldTitle(r.title)))
        [at: now, confirmed: whole != null || once, audited: whole?.status == 'FAIL', result: r]
    }

    /** Open findings in the stored history, by their key. Read outside the lock, and a read that fails is tried again by the next run with a finding. */
    private static Map<String, Map> openEpisodes(Long clusterId, Closure<List<Map>> stored) {
        try {
            Map<String, Map> open = stored.call().findAll { it.cleared == null }.collectEntries { [(it.k): it] }
            SEEDED << clusterId
            open
        } catch (Throwable e) {
            log.warn("cluster health history for cluster ${clusterId} could not say which isolation findings were open: ${e.message}")
            [:]
        }
    }

    /** The host is gone, or a disabled check: nothing to hold. Otherwise whether this run failed to read the ports its finding is about. */
    private static boolean cannotTell(HealthChecker.Input in_, Result r) {
        HealthChecker.Host h = in_.hosts.find { it.name == r.host }
        if (!h || r.id in in_.standards.disabledChecks) {
            return false
        }
        portsNotRead(h)
    }

    private static boolean portsNotRead(HealthChecker.Host h) {
        !h.facts?.complete || !h.facts.sections.containsKey('vm_ports') || h.facts.portsCut
    }

    /** Hosts whose ports this run could not read. After a reload nothing in memory holds what they had, so an alarm naming one, and a history entry, have to be left as they are. */
    static Set<String> portsUnread(HealthChecker.Input in_) {
        in_.hosts.findAll { portsNotRead(it) }*.name as Set<String>
    }

    private static String heldTitle(String title) {
        title.replaceFirst(/(: |$)/, CONFIRMING + '$1')
    }

    private static Result held(Result r) {
        new Result(r.id, heldTitle(r.title), Severity.HIGH, Status.WARN, r.host, "seen at one check so far: ${r.observed}".toString(), r.expected, r.source, null, Tier.NONE,
                'A network being changed looks like this for a moment, so this counts only if the next check sees it too.', r.impact)
    }

    private static Result carried(Result r) {
        new Result(r.id, r.title, r.severity, r.status, r.host, "as last read, since this host's ports could not be read this time: ${r.observed}".toString(), r.expected, r.source, r.fix, r.tier, r.hint, r.impact)
    }
}
