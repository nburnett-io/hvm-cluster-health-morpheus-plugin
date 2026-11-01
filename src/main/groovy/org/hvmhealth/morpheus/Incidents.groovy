package org.hvmhealth.morpheus

import groovy.util.logging.Slf4j
import org.hvmhealth.checks.HealthChecker
import org.hvmhealth.checks.HealthChecker.Result
import org.hvmhealth.checks.HealthChecker.Status

import java.util.regex.Pattern

/**
 * One incident per cause: a freeze raises a dozen findings across every host, and an operator needs one thing to act on. Only what goes to a person
 * is grouped. Every finding still stands in the results, the history and the tab, and nothing here decides whether a cluster is paged (see Paging).
 *
 * A finding is every warning or failure. Each is either the root of a group or named in one: nothing is dropped.
 */
@Slf4j
class Incidents {
    /** The last words of every alarm's message. A stored message without them was cut short, so what it names cannot be judged. */
    static final String ALARM_TAIL = "See the cluster's Cluster Health tab."
    /** A very long observation would push the rest of the message out of sight, and the tab has it whole. */
    private static final int MAX_OBSERVED = 300

    /**
     * What each cause explains while it is open. Written from each check's meaning in docs/CHECKS.md, and only a FAIL is a cause.
     * sameHost: explained only on a host the cause is on. anyHost: on any host, because the cause is cluster-wide or the finding is about a host
     * that cannot report the cause itself. First listed wins when two could explain one finding. Causes are never each other's symptoms: DLM-01 has
     * its own emergency action, and all three always page.
     *
     * DLM-01: GFS-07 and GFS-08 run only where DLM still looks healthy, so they can only be on another host, which waits on the frozen ones' locks. HB-01 is read
     * from a host that is not frozen, about one that is. MOR-01 and MOR-02 are records left by the same fence, on a host that reads as healthy. PRB-01 and MOR-03 cover
     * the failed node itself, which reports nothing. VM-02 and VM-03 are the frozen host's own by their meaning: elsewhere, VM-02 points at storage. So does STO-06.
     *
     * CLU-03: a host out of quorum stops its lockspaces, so it has a freeze's effects there. The hosts it lost show as PRB-01, MOR-03 and HB-01 anywhere.
     * MOR-01 and MOR-02 are left out, because they only fire on a quorate host. So are CLU-04, CLU-05 and CLU-08, because they show which member is missing.
     *
     * GFS-01: a withdrawal is one host's, so only that host's symptoms. STO-06 is left out, because lost I/O is the usual cause of a withdrawal, and GFS-10 too,
     * because it needs its own action and outlasts this. VM-03 needs a frozen host, and PRB-01 and MOR-03 are not what a withdrawal causes.
     */
    static final Map<String, Map<String, Set<String>>> CAUSES = [
            'DLM-01': [sameHost: ['GFS-05', 'VM-02', 'VM-03', 'STO-06'] as Set<String>,
                       anyHost : ['GFS-07', 'GFS-08', 'HB-01', 'MOR-01', 'MOR-02', 'MOR-03', 'PRB-01'] as Set<String>],
            'CLU-03': [sameHost: ['GFS-05', 'GFS-07', 'GFS-08', 'VM-02', 'VM-03', 'STO-06'] as Set<String>,
                       anyHost : ['HB-01', 'MOR-03', 'PRB-01'] as Set<String>],
            'GFS-01': [sameHost: ['GFS-05', 'GFS-07', 'GFS-08', 'VM-02', 'HB-01'] as Set<String>,
                       anyHost : [] as Set<String>],
    ]

    private static boolean open(Result r) {
        r.status in [Status.FAIL, Status.WARN]
    }

    /** The host is not part of this: the same check on several hosts is one group, and it stays one as hosts come and go. */
    static String key(Result r) {
        "${r.id}|${r.title}".toString()
    }

    private static Result causeOf(Result r, Map<String, List<Result>> causes) {
        for (Map.Entry<String, List<Result>> c : causes.entrySet()) {
            Map<String, Set<String>> explains = CAUSES[c.key]
            if (r.id in explains.anyHost) {
                return c.value[0]
            }
            if (r.id in explains.sameHost) {
                Result here = c.value.find { it.host != null && it.host == r.host }
                if (here) {
                    return here
                }
            }
        }
        null
    }

    /**
     * [root: the worst finding, repeats: the same check on other hosts, symptoms: findings another check explains, hosts: every host in the group,
     * rootHosts: those the check itself is on, status and severity: the worst in the group]. Worst group first, in the same order on every run.
     */
    static List<Map> group(List<Result> results) {
        List<Result> findings = results.findAll { open(it) }
        Map<String, List<Result>> causes = CAUSES.keySet().collectEntries { String id -> [(id): findings.findAll { it.id == id && it.status == Status.FAIL }] }.findAll { it.value }
        Map<String, List<Result>> symptoms = [:]
        List<Result> standing = []
        findings.each { Result r ->
            Result cause = causes.containsKey(r.id) ? null : causeOf(r, causes)
            if (cause) {
                symptoms.computeIfAbsent(key(cause)) { [] } << r
            } else {
                standing << r
            }
        }
        standing.groupBy { key(it) }.collect { String k, List<Result> same ->
            List<Result> ranked = same.sort(false) { a, b -> HealthChecker.rank(a) <=> HealthChecker.rank(b) ?: (a.host ?: '') <=> (b.host ?: '') }
            List<Result> theirs = (symptoms[k] ?: []) as List<Result>
            List<Result> all = ranked + theirs
            [key: k, root: ranked[0], repeats: ranked.drop(1), symptoms: theirs, hosts: all*.host.findAll().unique().sort(), rootHosts: ranked*.host.findAll().unique().sort(),
             onCluster: ranked.any { it.host == null }, status: all.any { it.status == Status.FAIL } ? Status.FAIL : Status.WARN, severity: all*.severity.min()]
        }.sort { a, b -> HealthChecker.rank(a.root as Result) <=> HealthChecker.rank(b.root as Result) ?: a.key <=> b.key }
    }

    private static String list(List<String> names) {
        names.join(', ')
    }

    /** What an alarm says: its hosts first, then the finding, who it reaches when known, and what else this group explains. As raised: see BACKLOG. */
    static String message(String clusterName, Map g, String reach) {
        Result r = g.root as Result
        List<String> hosts = g.rootHosts as List<String>
        List<Result> symptoms = g.symptoms as List<Result>
        String observed = r.observed?.length() > MAX_OBSERVED ? r.observed.take(MAX_OBSERVED) + '...' : r.observed
        String whose = hosts.size() > 1 && r.host ? " on ${r.host}" : ''
        String also = symptoms ? " Also explains ${symptoms.size()} related finding${symptoms.size() == 1 ? '' : 's'}: ${list(symptoms*.id.unique().sort())}${symptoms*.host.findAll() ? ' on ' + list(symptoms*.host.findAll().unique().sort()) : ''}." : ''
        "${clusterName}${hosts ? ' / ' + list(hosts) : ''}: ${r.title}: ${observed}${whose} (expected ${r.expected})${reach ? '; ' + reach : ''}.${also} ${ALARM_TAIL}".toString()
    }

    /** Whole names only, so a host called node1 is not found inside node10. */
    static boolean names(String text, String host) {
        text != null && host && Pattern.compile('(?<![\\w-])' + Pattern.quote(host) + '(?![\\w-])').matcher(text).find()
    }

    /**
     * Whether a check has reached a host its open alarm does not name, or a symptom it does not name has appeared. A new host is new, and an acknowledgement of the old hosts must not hide it, so the alarm is raised
     * again. A host that clears changes nothing. A message cut short says nothing either way: raising it again would never stop.
     */
    static boolean spread(String raised, List<String> wanted) {
        raised?.endsWith(ALARM_TAIL) && wanted?.any { !names(raised, it) }
    }

    /** Raising an alarm again loses Morpheus's acknowledgement, as a new alarm cannot be told it had one, so the text says it. Whoever reads it sees what they already looked at, and what is new. */
    static String withEarlierAck(String message, com.morpheusdata.model.OperationNotification was) {
        if (was?.acknowledged != true || !message?.endsWith(ALARM_TAIL)) {
            return message
        }
        String by = was.acknowledgedByUser ? " by ${was.acknowledgedByUser}" : ''
        "${message.substring(0, message.length() - ALARM_TAIL.length())}Acknowledged earlier${by}, before this changed. ${ALARM_TAIL}".toString()
    }

    /** What the tab needs to group its findings, as positions in the view's results, which are in the same order as rs. */
    static List<Map> forView(HealthChecker.Input in_, List<Result> rs) {
        Map<Result, Integer> at = new IdentityHashMap<>()
        rs.eachWithIndex { Result r, int i -> at.put(r, i) }
        group(rs).collect { Map g ->
            List<Result> members = [g.root as Result] + (g.repeats as List<Result>) + (g.symptoms as List<Result>)
            [key: g.key, members: members.collect { at.get(it) }, symptoms: (g.symptoms as List).size(), hosts: g.hosts, rootHosts: g.rootHosts,
             status: (g.status as Status).name(), severity: g.severity.toString(), reach: Impact.ofAll(in_, members)]
        }
    }

    /** Adds who each finding reaches and the groups to a view. For the tab, so a failure here costs only that: the check itself carries on, and the tab lists findings flat. */
    static void decorate(Map view, HealthChecker.Input in_, List<Result> rs) {
        try {
            List<Map> results = view.results as List<Map>
            rs.eachWithIndex { Result r, int i ->
                if (open(r)) {
                    results[i].reach = Impact.of(in_, r)
                }
            }
            view.incidents = forView(in_, rs)
        } catch (Exception e) {
            log.warn("could not group the findings of ${in_?.clusterName}: ${e.message}")
        }
    }
}
