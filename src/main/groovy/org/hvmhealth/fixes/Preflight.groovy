package org.hvmhealth.fixes

import org.hvmhealth.checks.HealthChecker
import org.hvmhealth.checks.HealthChecker.Host
import org.hvmhealth.checks.HealthChecker.Input
import org.hvmhealth.model.HostFacts

/**
 * "Can I take this host down?", and whether Morpheus can safely remove it from the cluster.
 *
 * Maintenance mode empties a host but leaves it a cluster member. If it then goes away, DLM treats it as failed and stops
 * every lockspace until it is fenced. Morpheus's own remove leaves cleanly, in the right order, but carries on when GFS2 is in
 * use. So the plugin checks first and then calls that remove, rather than doing the cluster work itself.
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
        // largest first, and what one VM takes is not there for the next: they all have to fit at once
        Map<String, Long> free = others.collectEntries { [(it.name): (it.facts?.memAvailKb ?: 0L) * 1024L] }
        List<Map> moves = vms.sort(false) { -((it.memory ?: 0L) as Long) }.collect { Map v ->
            long memory = (v.memory ?: 0L) as Long
            List<String> targets = others.findAll { Host o ->
                !((v.name as String) in (h.facts?.vmNoMigrate ?: [])) && !o.maintenance && o.facts?.complete && o.facts.memAvailKb && free[o.name] >= memory &&
                        !(passthrough && h.facts?.cpuFlags && o.facts.cpuFlags && o.facts.cpuFlags != h.facts.cpuFlags)
            }*.name as List<String>
            if (targets) {
                String into = targets.max { free[it] }
                free[into] -= memory
            }
            [vm: v.name, tenant: v.tenant, memoryGiB: memory >> 30, targets: targets]
        }
        List<Map> stuck = moves.findAll { !it.targets }
        if (stuck) {
            blockers << "Nowhere to live-migrate ${stuck*.vm.join(', ')}: with the others moved, no other host has the memory and a compatible CPU, or the VM's CPU settings pin it here. They would have to be stopped.".toString()
        }
        [host: hostName, verdict: blockers ? 'NO' : (cautions ? 'CAUTION' : 'GO'), blockers: blockers, cautions: cautions, votesAfter: votesAfter, quorum: peer?.quorumVotes,
         maintenance: h.maintenance, member: member, moves: moves, impact: HealthChecker.impactOf(vms), removal: removeReadiness(in_, hostName)]
    }

    /** What must be true before Morpheus removes this host from the cluster. Empty blockers means it may start. */
    static Map removeReadiness(Input in_, String hostName) {
        Host h = in_.hosts.find { it.name == hostName }
        List<String> why = new ArrayList<>(Fixes.blockers(HealthChecker.Tier.MAINTENANCE, in_, hostName))
        h?.facts?.gfsInUse?.findAll { k, v -> v > 0 }?.each { String m, Integer n ->
            why << "${n} process(es) still have ${m} open on ${hostName}: Morpheus would carry on without unmounting it, leaving its lockspace abandoned".toString()
        }
        List<String> recorded = in_.morpheusVms.findAll { it.host == hostName }*.name as List<String>
        if (recorded) {
            why << "Morpheus still has VM(s) on ${hostName}, running or not: ${recorded.join(', ')}. Move them first".toString()
        }
        List<Host> others = in_.hosts.findAll { it.name != hostName }
        // Morpheus's agent acknowledges only nodes it has a record of: remove one while the rest are recovering, and a host that restarts then waits forever
        others.findAll { it.facts?.fenceWaits }.each { why << "${it.name} still has a fence request open: wait until it clears".toString() }
        // deleted while its GFS2 mount was still starting, a host froze the others for 27 minutes in a way nothing could acknowledge
        if (h?.facts?.corosyncUpSec != null && h.facts.corosyncUpSec < RECENT_BOOT_SEC) {
            why << "${hostName} joined the cluster less than ${(RECENT_BOOT_SEC / 60L) as int} minutes ago, and its GFS2 mount may still be starting: wait until it has settled".toString()
        }
        others.findAll { it.facts?.boot && it.facts?.now && it.facts.now - it.facts.boot < RECENT_BOOT_SEC }.each { why << "${it.name} restarted less than ${(RECENT_BOOT_SEC / 60L) as int} minutes ago: wait until it has settled".toString() }
        if (others.size() < 2) {
            why << 'Removing this host would leave one: the cluster could not keep quorum'
        }
        [ready: !why, blockers: why, leaves: others*.name,
         warning: others.size() == 2 ? 'Two hosts will remain with both votes needed: either one failing then stops every GFS2 datastore until the other is told it is alone. Finish the move quickly, or add a witness.' : null,
         steps: REMOVAL_STEPS]
    }

    private static final long RECENT_BOOT_SEC = 600L
    private static final int UNMOUNT_WAIT_SEC = 60

    static final List<String> REMOVAL_STEPS = [
            'Check that the host is in maintenance mode with nothing on it, that nothing has GFS2 open there, that the rest keep quorum, and that none of them is recovering.',
            'Unmount GFS2 first, with a time limit. A hung unmount stops here, with the host still known to Morpheus, so its agent can still acknowledge it if it goes down.',
            'Morpheus removes it: stop dlm then corosync so it announces it is leaving, then update corosync.conf on the remaining hosts.',
            'Watch the remaining hosts until they count one host fewer, and report any sign of a fence wait.',
            'It can then be powered off or rebuilt. Adding it back is an ordinary add in Morpheus.',
    ]

    /**
     * Before Morpheus is asked. Its own unmount has no limit and it carries on regardless: when one hung, its record went but the others kept
     * expecting the host, and a fence wait followed. Exit 3 means nothing changed. Exit 4 is an unmount refused, so that mount's pool is started again.
     * Exit 6 is a hung unmount: from kernel source the kernel's own wait is bounded at about 600 s (unconfirmed on a live host) and then
     * completes and leaves cleanly, so a reboot rushed mid-unmount is the unclean departure that freezes the survivors; wait it out rather than reboot. Its pool stays stopped: starting one would touch the hung mount.
     */
    static String unmountScript() {
        Fixes.HEADER + '''mounts="$(awk '$3=="gfs2"{print $2}' /proc/mounts)"
for m in $mounts; do
  if fuser -m "$m" >/dev/null 2>&1; then echo "REFUSED: still in use: $m"; fuser -vm "$m" 2>&1 | head -12; exit 3; fi
done
for m in $mounts; do
  p="$(virsh -q pool-list --all 2>/dev/null | awk '{print $1}' | while read -r n; do virsh pool-dumpxml "$n" 2>/dev/null | grep -q "<path>$m</path>" && echo "$n"; done | head -1 || true)"
  if [ -n "$p" ]; then virsh pool-destroy "$p" >/dev/null 2>&1 || echo "could not stop pool $p"; fi
  umount "$m" & u=$!
  for _ in $(seq 1 ''' + UNMOUNT_WAIT_SEC + '''); do kill -0 "$u" 2>/dev/null || break; sleep 1; done
  if kill -0 "$u" 2>/dev/null; then echo "HUNG: umount of $m has not finished after ''' + UNMOUNT_WAIT_SEC + ''' s"; exit 6; fi
  wait "$u" || { echo "FAILED to unmount $m"; [ -z "$p" ] || timeout 30 virsh pool-start "$p" >/dev/null 2>&1 || echo "could not start pool $p again"; exit 4; }
  echo "unmounted $m"
done
left="$(dlm_tool ls 2>/dev/null | grep -c '^name' || true)"
[ "$left" = 0 ] || { echo "FAILED: still in $left lockspace(s)"; exit 5; }
echo 'out of every lockspace'
'''
    }
}
