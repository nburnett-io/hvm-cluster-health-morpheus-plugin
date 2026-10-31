package org.hvmhealth.fixes

import org.hvmhealth.checks.HealthChecker
import org.hvmhealth.checks.HealthChecker.Host
import org.hvmhealth.checks.HealthChecker.Input
import org.hvmhealth.model.HostFacts

/**
 * "Can I take this host down?", and how to take it out of the cluster without freezing it.
 *
 * Maintenance mode empties a host but leaves it a cluster member. If it then goes away, DLM treats it as failed and stops
 * every lockspace until it is fenced. A node that first leaves cleanly is simply gone: it unmounts GFS2, which takes it out of
 * the lockspaces, then stops dlm, then corosync, which announces its leaving. Only then is it safe to power off or rebuild.
 */
class Preflight {
    /** Verdict plus what stands in the way, what to expect, and which VMs cannot move. */
    static Map hostDown(Input in_, String hostName) {
        Host h = in_.hosts.find { it.name == hostName }
        if (!h) {
            throw new IllegalArgumentException("${hostName} is not in this cluster")
        }
        List<String> blockers = [], cautions = []
        List<Host> others = in_.hosts.findAll { it.name != hostName }
        if (!h.facts?.complete) {
            blockers << "${hostName} did not answer the probe".toString()
        }
        others.findAll { !it.facts?.complete }.each { blockers << "${it.name} did not answer: find out why before taking another host away".toString() }
        if (in_.hosts.any { it.facts?.frozen }) {
            blockers << 'A DLM lockspace is stopped: clear the freeze first'
        }
        others.findAll { it.maintenance }.each { blockers << "${it.name} is in maintenance mode too: one host at a time".toString() }

        HostFacts peer = others*.facts.find { it?.active('corosync') }
        Integer votesAfter = null
        boolean member = h.facts?.active('corosync')
        if (peer) {
            votesAfter = (peer.totalVotes ?: 0) - (member ? 1 : 0)
            if (votesAfter < (peer.quorumVotes ?: 0)) {
                blockers << "The cluster would lose quorum without this host: ${votesAfter} votes left, needs ${peer.quorumVotes}. Every GFS2 datastore would stop.".toString()
            } else if (votesAfter == (peer.quorumVotes ?: 0)) {
                cautions << "No margin left: ${votesAfter} votes with ${peer.quorumVotes} needed, so any further host failure stops every GFS2 datastore. Add a host or witness if this will last.".toString()
            }
        } else if (others) {
            blockers << 'No other host has a running corosync to carry the cluster'
        }

        // where each VM on this host can go
        List<Map> vms = in_.morpheusVms.findAll { it.host == hostName && it.power == 'on' }
        boolean passthrough = !(in_.clusterConfig?.cpuModel) || in_.clusterConfig.cpuModel in ['host-passthrough', 'host-model']
        List<Map> moves = vms.collect { Map v ->
            List<String> targets = others.findAll { Host o ->
                !o.maintenance && o.facts?.complete && o.facts.memAvailKb && o.facts.memAvailKb * 1024L >= ((v.memory ?: 0L) as Long) &&
                        !(passthrough && h.facts?.cpuFlags && o.facts.cpuFlags && o.facts.cpuFlags != h.facts.cpuFlags)
            }*.name as List<String>
            [vm: v.name, tenant: v.tenant, memoryGiB: ((v.memory ?: 0L) as Long) >> 30, targets: targets]
        }
        List<Map> stuck = moves.findAll { !it.targets }
        if (stuck) {
            blockers << "Nowhere to live-migrate ${stuck*.vm.join(', ')}: no other host has the memory and a compatible CPU. They would have to be stopped.".toString()
        }
        [host: hostName, verdict: blockers ? 'NO' : (cautions ? 'CAUTION' : 'GO'), blockers: blockers, cautions: cautions, votesAfter: votesAfter, quorum: peer?.quorumVotes,
         maintenance: h.maintenance, member: member, moves: moves, impact: HealthChecker.impactOf(vms), eject: ejectReadiness(in_, hostName)]
    }

    /** What must be true before ejecting this host from the cluster. Empty blockers means it may start. */
    static Map ejectReadiness(Input in_, String hostName) {
        Host h = in_.hosts.find { it.name == hostName }
        List<String> why = new ArrayList<>(Fixes.blockers(HealthChecker.Tier.MAINTENANCE, in_, hostName))
        Map node = h?.facts?.nodelist?.find { HealthChecker.isNode(it, h) }
        if (h?.facts?.complete && !node) {
            why << "${hostName} is not in corosync.conf: it is not a cluster member".toString()
        }
        h?.facts?.gfsInUse?.findAll { k, v -> v > 0 }?.each { String m, Integer n ->
            why << "${n} process(es) still have ${m} open on ${hostName}: it could not unmount".toString()
        }
        List<Host> others = in_.hosts.findAll { it.name != hostName }
        if (others.size() < 2) {
            why << 'Ejecting this host would leave one: the cluster could not keep quorum'
        }
        [ready: !why, blockers: why, nodeId: node?.nodeid, nodeAddr: node?.addr,
         leaves: others*.name,
         warning: others.size() == 2 ? 'Two hosts will remain with both votes needed: either one failing then stops every GFS2 datastore until the other is told it is alone. Finish the move quickly, or add a witness.' : null,
         steps: EJECT_STEPS]
    }

    static final List<String> EJECT_STEPS = [
            'Unmount GFS2 on the host, which takes it out of every DLM lockspace. Refused if anything still has files open there.',
            'Stop and disable dlm, then corosync, on the host, so it announces it is leaving rather than vanishing.',
            'Remove it from corosync.conf on the remaining hosts, and reload corosync there.',
            'Check the remaining hosts after each step. Any sign of a fence wait stops the eject.',
            'Then remove the host in Morpheus, and power it off or rebuild it.',
    ]

    // ------------------------------------------------------------------ the scripts

    /** Step 1, on the leaving host. Exit 3 means something still uses the mount; nothing has changed. */
    static String unmountScript() {
        Fixes.HEADER + '''mounts="$(awk '$3=="gfs2"{print $2}' /proc/mounts)"
for m in $mounts; do
  if fuser -m "$m" >/dev/null 2>&1; then echo "REFUSED: still in use: $m"; fuser -vm "$m" 2>&1 | head -12; exit 3; fi
done
for m in $mounts; do
  p="$(virsh -q pool-list --all 2>/dev/null | awk '{print $1}' | while read -r n; do virsh pool-dumpxml "$n" 2>/dev/null | grep -q "<path>$m</path>" && echo "$n"; done | head -1)"
  [ -n "$p" ] && virsh pool-destroy "$p" >/dev/null 2>&1
  timeout -k 5 60 umount "$m" && echo "unmounted $m" || { echo "FAILED to unmount $m"; exit 4; }
done
left="$(dlm_tool ls 2>/dev/null | grep -c '^name')"
[ "$left" = 0 ] || { echo "FAILED: still in $left lockspace(s)"; exit 5; }
echo 'out of every lockspace'
'''
    }

    /** Step 2, on the leaving host. */
    static String leaveScript() {
        Fixes.HEADER + '''if awk '$3=="gfs2"{f=1} END{exit !f}' /proc/mounts; then echo 'REFUSED: GFS2 is still mounted'; exit 3; fi
systemctl disable --now dlm 2>&1 | tail -1
systemctl disable --now corosync 2>&1 | tail -1
echo "dlm $(systemctl is-active dlm), corosync $(systemctl is-active corosync)"
'''
    }

    /** corosync.conf without one node, and with its config_version raised so the reload is taken. */
    static String withoutNode(List<String> lines, String addr) {
        List<String> out = []
        int i = 0
        boolean removed = false
        while (i < lines.size()) {
            String l = lines[i]
            if (l.trim() == 'node {') {
                int j = i
                while (j < lines.size() && lines[j].trim() != '}') { j++ }
                if (lines[i..j].any { it.trim() == "ring0_addr: ${addr}".toString() }) {
                    removed = true
                    i = j + 1
                    if (i < lines.size() && !lines[i].trim()) { i++ }
                    continue
                }
            }
            def m = l =~ /^(\s*config_version:\s*)(\d+)\s*$/
            out << (m.find() ? "${m.group(1)}${(m.group(2) as Integer) + 1}".toString() : l)
            i++
        }
        if (!removed) {
            throw new IllegalStateException("No node at ${addr} in corosync.conf")
        }
        out.join('\n') + '\n'
    }

    /** Step 3, on each remaining host: replaces the file, keeping the old one, and checks it parses as the node list expected. */
    static String writeConfScript(String conf) {
        Fixes.HEADER + "cp -p /etc/corosync/corosync.conf /etc/corosync/corosync.conf.before-eject\n" +
                "echo '${conf.getBytes('UTF-8').encodeBase64()}' | base64 -d > /etc/corosync/corosync.conf.new\n" +
                'mv /etc/corosync/corosync.conf.new /etc/corosync/corosync.conf\necho written\n'
    }

    static final String RELOAD_SCRIPT = Fixes.HEADER + 'corosync-cfgtool -R\nsleep 3\ncorosync-quorumtool -s | grep -E "^(Quorate|Expected votes|Total votes|Quorum):"\n'
}
