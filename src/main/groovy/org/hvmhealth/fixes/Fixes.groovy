package org.hvmhealth.fixes

import groovy.transform.Canonical
import org.hvmhealth.checks.HealthChecker
import org.hvmhealth.checks.HealthChecker.Host
import org.hvmhealth.checks.HealthChecker.Input
import org.hvmhealth.checks.HealthChecker.Tier
import org.hvmhealth.model.HostFacts

/**
 * What the plugin may change, and the guards around it. Anything that could disturb the cluster runs only on a host
 * that an operator has put in maintenance mode, with no VMs on it, while the rest of the cluster can lose it.
 *
 * Deliberately absent: anything that restarts corosync or dlm on a host with GFS2 mounted, because a member leaving
 * uncleanly freezes every datastore on every other host; turning fencing off; unmounting; changing MTU or bonds, since
 * those bounce the link the agent itself uses. Those findings carry instructions instead.
 */
class Fixes {
    @Canonical
    static class Fix {
        String key
        String title
        Tier tier
        String script
    }

    static final String HEADER = '#!/bin/bash\nset -euo pipefail\nexport LC_ALL=C PATH=/usr/sbin:/usr/bin:/sbin:/bin\n'

    /**
     * @param ntpServers the cluster's time sources from the standards, for the ntp-sources fix
     * @throws IllegalArgumentException for a key this plugin does not know
     */
    static Fix fix(String key, HostFacts f, List<String> ntpServers = []) {
        switch (key) {
            case 'ntp-sources':
                if (!ntpServers) {
                    throw new IllegalStateException('No time sources set: add ntpServers to the Site standards setting')
                }
                ntpServers.each {
                    if (!(it ==~ /[A-Za-z0-9.:-]+/)) {
                        throw new IllegalArgumentException("Unsafe time source ${it}")
                    }
                }
                // a drop-in, so the OS file stays as shipped; "prefer" makes these win over its default pool. chrony slews a
                // running clock rather than stepping it, but a change of time source is still not done under running VMs.
                String lines = ntpServers.collect { "server ${it} iburst prefer" }.join('\\n')
                return new Fix(key, "Use the cluster time sources: ${ntpServers.join(', ')}".toString(), Tier.MAINTENANCE, HEADER +
                        "[ -d /etc/chrony/sources.d ] || { echo 'REFUSED: this host does not use chrony sources.d'; exit 1; }\n" +
                        "printf '${lines}\\n' > /etc/chrony/sources.d/cluster-health.sources.new\n" +
                        "mv /etc/chrony/sources.d/cluster-health.sources.new /etc/chrony/sources.d/cluster-health.sources\n" +
                        "chronyc reload sources\nsleep 10\nchronyc -n sources | head -12\n")
            case 'ntp-enable':
                return new Fix(key, 'Turn on NTP time synchronisation', Tier.SAFE, HEADER + 'timedatectl set-ntp true\necho "NTP on: $(timedatectl show -p NTP --value)"\n')
            case 'service-enable':
                // starts nothing: only makes units that are already running come back after a reboot
                List<String> units = ['corosync', 'dlm'].findAll { f.active(it) && !(f.service(it).enabled in ['enabled', 'static', 'indirect']) }
                if (!units) {
                    throw new IllegalStateException('Nothing to enable: every running cluster service is already enabled')
                }
                return new Fix(key, "Enable ${units.join(' and ')} at boot (already running)".toString(), Tier.SAFE,
                        HEADER + "systemctl enable ${units.join(' ')}\necho 'enabled ${units.join(' ')}'\n")
            case 'rejoin':
                return new Fix(key, 'Start corosync and dlm so this host rejoins the cluster', Tier.MAINTENANCE, HEADER + REJOIN)
            case 'multipath-reconfigure':
                return new Fix(key, 'Rescan iSCSI sessions and reload multipath', Tier.MAINTENANCE, HEADER +
                        'iscsiadm -m session --rescan >/dev/null\nmultipathd reconfigure\nsleep 5\nmultipath -ll | grep -cE "active ready" | sed "s/^/healthy paths: /"\n')
            default:
                throw new IllegalArgumentException("Unknown fix ${key}")
        }
    }

    private static final String REJOIN = '''[ -r /etc/corosync/corosync.conf ] || { echo 'REFUSED: no corosync.conf; add this host back through Morpheus'; exit 1; }
if awk '$3=="gfs2"{f=1} END{exit !f}' /proc/mounts; then
  echo 'REFUSED: GFS2 is mounted while cluster services are down: this host needs a reboot in the maintenance window, not a service start'
  exit 1
fi
systemctl enable --now corosync
for _ in $(seq 1 30); do
  corosync-quorumtool -s 2>/dev/null | grep -qE '^Quorate: +Yes' && break
  sleep 2
done
corosync-quorumtool -s 2>/dev/null | grep -qE '^Quorate: +Yes' || { echo 'corosync started but the host did not join a quorate cluster; dlm left stopped'; exit 1; }
systemctl enable --now dlm
echo "corosync $(systemctl is-active corosync), dlm $(systemctl is-active dlm)"
'''

    /** Why a change cannot run on this host now; empty means it may. SAFE fixes need only an up-to-date probe. */
    static List<String> blockers(Tier tier, Input in_, String hostName) {
        Host h = in_.hosts.find { it.name == hostName }
        if (!h) {
            return ["${hostName} is not in this cluster".toString()]
        }
        if (!h.facts?.complete) {
            return ["${hostName} did not answer the probe".toString()]
        }
        if (tier != Tier.MAINTENANCE) {
            return []
        }
        List<String> why = []
        if (!h.maintenance) {
            why << "${hostName} is not in maintenance mode: put it there in Morpheus first".toString()
        }
        List<String> running = h.facts.vms.findAll { it.state == 'running' }*.name as List<String>
        if (running) {
            why << "${hostName} still runs ${running.join(', ')}".toString()
        }
        List<Host> others = in_.hosts.findAll { it.name != hostName }
        if (others.any { it.facts?.frozen } || h.facts.frozen) {
            why << 'A DLM lockspace is stopped: clear the freeze first'
        }
        List<String> alsoInMaintenance = others.findAll { it.maintenance }*.name as List<String>
        if (alsoInMaintenance) {
            why << "Another host is in maintenance mode too (${alsoInMaintenance.join(', ')}): one at a time".toString()
        }
        if (others.any { !it.facts?.complete }) {
            why << "Another host did not answer: ${others.findAll { !it.facts?.complete }*.name.join(', ')}".toString()
        }
        HostFacts peer = others*.facts.find { it?.active('corosync') }
        if (peer) {
            boolean counted = h.facts.active('corosync')
            int after = (peer.totalVotes ?: 0) - (counted ? 1 : 0)
            if (!peer.quorate || after < (peer.quorumVotes ?: 0)) {
                why << "The cluster would lose quorum without this host (${after} votes left, needs ${peer.quorumVotes})".toString()
            }
        } else if (others) {
            why << 'No other host has a running corosync to carry the cluster'
        }
        why
    }

    // ------------------------------------------------------------------ emergency: clearing a fence wait

    /**
     * What an operator is told before acknowledging a failed node as fenced, the one action that ends a cluster-wide
     * freeze when automatic fencing cannot.
     *
     * A node is proven down only by evidence from the node itself: it rebooted after it failed, holds no GFS2 mount and
     * runs no DLM. "It stopped answering" is not proof: a node can lose the cluster network and keep writing to shared
     * storage. A node nobody can reach needs a person to state it is off. Acknowledging a node that is
     * still writing corrupts the filesystem, so this never runs automatically.
     */
    @Canonical
    static class FencePlan {
        Integer nodeId
        String nodeName
        Long waitingSinceSec
        String runOn
        boolean proven
        String evidence
        List<String> blockers = []
        List<String> affectedVms = []
    }

    static FencePlan fencePlan(Input in_, Integer nodeId, long nowSec = System.currentTimeMillis() / 1000L as long) {
        List<Host> answered = in_.hosts.findAll { it.facts?.complete }
        List<Host> waiting = answered.findAll { h -> h.facts.fenceWaits.any { it.node == nodeId } }
        FencePlan p = new FencePlan(nodeId: nodeId)
        if (!waiting) {
            p.blockers << "No host is waiting to fence node ${nodeId}".toString()
            return p
        }
        HostFacts first = waiting[0].facts
        p.nodeName = first.nodeNames[nodeId] ?: "node ${nodeId}".toString()
        Long since = first.fenceWaits.find { it.node == nodeId }.since as Long
        p.waitingSinceSec = nowSec - since
        p.runOn = waiting.find { it.facts.quorate }?.name
        if (!p.runOn) {
            p.blockers << 'No waiting host is in a quorate cluster: restore quorum first, or the acknowledgement cannot complete'
        }
        List<Host> members = answered.findAll { it.facts.active('dlm') && it.facts.lockspaces }
        List<String> notWaiting = (members - waiting)*.name.findAll { it != p.nodeName } as List<String>
        Map node = first.nodelist.find { it.nodeid == nodeId }
        Host target = node ? in_.hosts.find { HealthChecker.isNode(node, it) } : null
        notWaiting.removeAll { it == target?.name }
        if (notWaiting) {
            p.blockers << "${notWaiting.join(', ')} is not waiting on this node: run the check again, the cluster is still changing".toString()
        }
        if (target?.facts?.complete) {
            HostFacts t = target.facts
            boolean rebooted = (t.boot ?: 0L) > since
            boolean noMount = !t.mounts
            boolean noDlm = !t.active('dlm')
            if (!noMount || !noDlm) {
                p.blockers << "${p.nodeName} is alive and still ${noMount ? 'runs dlm' : 'has GFS2 mounted'}: acknowledging it could corrupt the filesystem. Reboot it, or get it to leave the cluster cleanly.".toString()
            } else if (rebooted) {
                p.proven = true
                p.evidence = "${p.nodeName} answered: it rebooted after it failed, has no GFS2 mounted and is not running dlm".toString()
            } else {
                p.evidence = "${p.nodeName} answered and has no GFS2 mounted and no dlm, but has not rebooted since it failed".toString()
            }
        } else {
            p.evidence = "The plugin cannot reach ${p.nodeName}, so nothing proves it is down. Check it is powered off or wiped before going on.".toString()
        }
        p.affectedVms = answered.collectMany { it.facts.dVms ?: [] }.unique() as List<String>
        p
    }

    /** Runs on one surviving member; waits for the lockspace to recover. */
    static String fenceAckScript(Integer nodeId) {
        HEADER + """dlm_tool fence_ack ${nodeId}
for _ in \$(seq 1 30); do
  if ! dlm_tool ls | grep -qE 'kern_stop|wait fenc|new status'; then echo 'lockspaces recovered'; exit 0; fi
  sleep 2
done
echo 'acknowledged, but the lockspaces have not recovered after 60 s'
exit 1
"""
    }

    /** The tier of a key, or EMERGENCY for fence-ack:<n>. */
    static Tier tierOf(String key, HostFacts f) {
        key?.startsWith('fence-ack:') ? Tier.EMERGENCY : fix(key, f).tier
    }
}
