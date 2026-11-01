package org.hvmhealth.morpheus

import com.morpheusdata.core.MorpheusContext
import groovy.json.JsonOutput
import groovy.util.logging.Slf4j

/**
 * Which appliance node runs the checks, as a multi-node appliance otherwise reads every host once per node. One row, replaced every sweep by the node that holds it, with what the
 * status URL says in it, so that the other nodes can answer from that alone.
 *
 * The API has no compare-and-set, and none is faked: the row is read, written and read back. Two nodes that do that at the same moment can both act for one sweep, and the next
 * read settles it. That costs reads, and nothing worse: alarms are identified by check, and history is read fresh on every write. Anything going wrong here means carrying on, as a
 * freeze found twice is better than one not found at all.
 *
 * Its category is not one FindingHistory.pruneOrphans goes through, so this row, which is about no cluster, is never taken for an orphan.
 */
@Slf4j
class Lease {
    static final String CATEGORY = 'hvm-cluster-health-lease'
    /** Three sweeps missed, measured in the PAST: a row written within this of now is still held. */
    static final long LIMIT_MS = 3L * 60 * 1000
    /** PluginRow keeps one row per id. No cluster has this one. */
    private static final Long NOT_A_CLUSTER = 0L

    /** This node's own name, for this load only. Nothing but this row and the master tenant's all-clusters page holds it, and never the status URL. */
    static volatile String node
    /** The row as this node last read it: null until then, and while it cannot be read. What a node standing by answers from. */
    static volatile Map row

    static String self() {
        node ?: (node = hostName())
    }

    /** Two nodes that give the same name each take the row for their own, and both run, which is what they would do with no lease at all. */
    private static String hostName() {
        try {
            InetAddress.localHost.hostName
        } catch (Exception e) {
            log.warn("cluster health could not read this appliance node's name: ${e.message}")
            "node-${UUID.randomUUID().toString().take(8)}".toString()
        }
    }

    /** How far the row's date is from now, either way. A row with no date never held anything. */
    static long age(Map r, long now) {
        r?.at instanceof Number ? Math.abs(now - (r.at as long)) : Long.MAX_VALUE
    }

    /** A live holder may run a little ahead of a correct node; only a date absurdly far in the future is a dead holder with a broken clock, to be reclaimed. */
    static final long FUTURE_SKEW_MS = 24L * 60 * 60 * 1000

    /**
     * True while the row's holder is still live: written within LIMIT_MS in the PAST, and not dated absurdly far ahead. A one-sided past window means a few minutes of clock
     * skew between appliance nodes no longer reads a live holder's row as expired, which used to make every node take over and run every sweep (double the host load, duplicate alarms).
     */
    static boolean held(Map r, long now) {
        if (!(r?.at instanceof Number)) {
            return false
        }
        long at = r.at as long
        (now - at) <= LIMIT_MS && (at - now) <= FUTURE_SKEW_MS
    }

    /**
     * True when this node should act on this sweep. {@code summary} is only asked for when this node is about to write, and is what it writes.
     * A row that cannot be read, or cannot be written, leaves it acting.
     */
    static boolean decide(MorpheusContext morpheus, Closure<Map> summary, long now) {
        Map found
        try {
            found = PluginRow.read(morpheus, CATEGORY, NOT_A_CLUSTER)
        } catch (Throwable e) {
            log.warn("cluster health could not read which appliance node runs the checks, so this one carries on, and others may too: ${e.message}")
            row = null
            return true
        }
        if (found && found.node != self() && held(found, now)) {
            row = found
            return false
        }
        if (found && found.node != self()) {
            log.warn("cluster health: ${found.node} has not renewed its lease for ${(age(found, now) / 1000L) as long} s, so ${self()} takes over the checks")
        }
        try {
            Map mine = [node: self(), at: now, summary: summary.call()]
            PluginRow.replace(morpheus, CATEGORY, 'HVM Cluster Health lease', NOT_A_CLUSTER, JsonOutput.toJson(mine), now)
            Map back = PluginRow.read(morpheus, CATEGORY, NOT_A_CLUSTER)
            row = back ?: mine
            row.node == self()
        } catch (Throwable e) {
            log.warn("cluster health could not renew the lease, so this node carries on, and another may too: ${e.message}")
            row = found
            true
        }
    }

    /** What a reload does. The name stays, as it is the same host. */
    static void forget() {
        row = null
    }
}
