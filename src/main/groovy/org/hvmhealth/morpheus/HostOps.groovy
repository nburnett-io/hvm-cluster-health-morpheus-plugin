package org.hvmhealth.morpheus

import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.model.ComputeServer
import com.morpheusdata.model.User
import org.hvmhealth.checks.HealthChecker
import org.hvmhealth.fixes.Fixes
import org.hvmhealth.fixes.Preflight
import org.hvmhealth.fixes.VlanTest
import org.hvmhealth.model.HostFacts

/** The two longer operations on a host in maintenance mode: the VLAN traffic test, and ejecting it from the cluster. */
class HostOps {
    /** Runs the test detached, then reads its result, surviving the agent losing the host while uplinks change. */
    static Map vlanTest(MorpheusContext morpheus, HealthContext ctx, String host, List<Map> extra, User who, List<String> progress, Map settings) {
        Map<String, HostFacts> facts = ctx.probe('quick')
        HealthChecker.Input in_ = ctx.input(facts, settings?.siteStandards as String)
        List<String> blocked = Fixes.blockers(HealthChecker.Tier.MAINTENANCE, in_, host)
        if (blocked) {
            Audit.warn(morpheus, who, ctx.cluster, 'VLAN test refused', [host: host, why: blocked.join('; ')])
            throw new IllegalStateException("Not run: ${blocked.join('; ')}")
        }
        List<Map> networks = (in_.networks.findAll { it.vlan && it.gateway } + (extra ?: [])).unique { it.vlan }
        if (!networks) {
            throw new IllegalStateException('This cluster has no Morpheus networks with both a VLAN and a gateway to test. Add them, or give a VLAN and gateway to test.')
        }
        ComputeServer s = ctx.hosts.find { it.name == host }
        progress << "${host}: testing VLAN(s) ${networks*.vlan.join(', ')} on each uplink, which moves this host's active uplink".toString()
        Audit.warn(morpheus, who, ctx.cluster, 'VLAN test started', [host: host, vlans: networks*.vlan.join(',')])
        Map started = ctx.run(s, VlanTest.launchScript(networks), 60)
        if (started.exit != 0) {
            throw new IllegalStateException("${host}: could not start the test (exit ${started.exit})")
        }
        Map parsed = [done: false]
        long deadline = System.currentTimeMillis() + 200_000L
        while (System.currentTimeMillis() < deadline && !parsed.done) {
            Thread.sleep(8000)
            try {
                parsed = VlanTest.parse(ctx.run(s, VlanTest.READ_SCRIPT, 20).output as String)
            } catch (Exception ignored) {
                progress << '  no answer from the host yet: its uplink may be moving'
            }
        }
        if (!parsed.done) {
            Audit.warn(morpheus, who, ctx.cluster, 'VLAN test did not report back', [host: host])
            throw new IllegalStateException("${host} did not report back in time. Its timer puts the original uplink back on its own; check the host.")
        }
        if (parsed.error) {
            throw new IllegalStateException(parsed.error as String)
        }
        if (!parsed.results) {
            throw new IllegalStateException("${host}: the test finished without testing anything. Nothing can be concluded about these VLANs.")
        }
        (parsed.results as List<Map>).each { progress << "  ${it.uplink} VLAN ${it.vlan} -> gateway ${it.gateway}: ${it.replies ? it.replies + ' replies' : 'NO REPLY'}".toString() }
        progress << "  active uplink back on ${parsed.restored}".toString()
        if ((parsed.leftover as String) && parsed.leftover != '0') {
            progress << "  WARNING: ${parsed.leftover} temporary interface(s) were left on the host".toString()
        }
        Map sum = VlanTest.summary(parsed)
        Audit.info(morpheus, who, ctx.cluster, 'VLAN test done', [host: host, failures: (sum.uplinkFailures as List).join('; ') ?: 'none', nowhere: (sum.nowhere as List).join('; ') ?: 'none'])
        [host: host, results: parsed.results, restored: parsed.restored] + sum
    }

    private static void step(List<String> progress, String text) {
        progress << text
    }

    /** Every remaining host must be quorate with no fence wait; stops the eject if not. */
    private static Map<String, HostFacts> assertHealthy(HealthContext ctx, String leaving, List<String> progress, String after) {
        Map<String, HostFacts> facts = ctx.probe('quick')
        facts.findAll { k, v -> k != leaving }.each { String h, HostFacts f ->
            if (!f.complete) {
                throw new IllegalStateException("Stopped ${after}: ${h} did not answer. Check it before going on.")
            }
            if (f.frozen || !f.quorate) {
                throw new IllegalStateException("Stopped ${after}: ${h} is ${f.frozen ? 'frozen' : 'not quorate'}. If a fence wait is shown, acknowledge the node only once it is certainly down.")
            }
        }
        progress << "  remaining hosts healthy ${after}".toString()
        facts
    }

    /**
     * Takes a host out of the cluster so that nothing waits for it. Stops at the first sign of trouble and says where, since
     * each step leaves the cluster in a safe state: a host that has only unmounted is still a healthy member.
     */
    static Map eject(MorpheusContext morpheus, HealthContext ctx, String host, User who, List<String> progress, Map settings) {
        Map<String, HostFacts> facts = ctx.probe('quick')
        HealthChecker.Input in_ = ctx.input(facts, settings?.siteStandards as String)
        Map ready = Preflight.ejectReadiness(in_, host)
        if (!ready.ready) {
            Audit.warn(morpheus, who, ctx.cluster, 'eject refused', [host: host, why: (ready.blockers as List).join('; ')])
            throw new IllegalStateException("Not run: ${(ready.blockers as List).join('; ')}")
        }
        ComputeServer target = ctx.hosts.find { it.name == host }
        List<ComputeServer> remaining = ctx.hosts.findAll { it.name != host }
        Audit.warn(morpheus, who, ctx.cluster, 'eject started', [host: host, nodeId: ready.nodeId])

        step(progress, "1. ${host}: unmounting GFS2")
        Map r = ctx.run(target, Preflight.unmountScript())
        (r.output as String)?.readLines()?.findAll { it.trim() }?.take(20)?.each { progress << "  ${it}".toString() }
        if (r.exit != 0) {
            Audit.warn(morpheus, who, ctx.cluster, 'eject stopped at unmount', [host: host, exit: r.exit])
            throw new IllegalStateException("Stopped: ${host} could not unmount GFS2 (exit ${r.exit}). It is still a healthy cluster member; nothing else changed.")
        }
        assertHealthy(ctx, host, progress, 'after the unmount')

        step(progress, "2. ${host}: stopping dlm and corosync")
        r = ctx.run(target, Preflight.leaveScript())
        (r.output as String)?.readLines()?.findAll { it.trim() }?.take(10)?.each { progress << "  ${it}".toString() }
        if (r.exit != 0) {
            Audit.warn(morpheus, who, ctx.cluster, 'eject stopped at leave', [host: host, exit: r.exit])
            throw new IllegalStateException("Stopped: ${host} could not stop its cluster services (exit ${r.exit}).")
        }
        Thread.sleep(5000)
        Map<String, HostFacts> after = assertHealthy(ctx, host, progress, 'after it left')

        step(progress, '3. Removing it from corosync.conf on the remaining hosts')
        HostFacts peer = after[remaining[0].name]
        String conf = Preflight.withoutNode(peer.sections.corosync_conf as List<String>, ready.nodeAddr as String)
        remaining.each { ComputeServer s ->
            Map w = ctx.run(s, Preflight.writeConfScript(conf))
            if (w.exit != 0) {
                throw new IllegalStateException("Stopped: could not write corosync.conf on ${s.name} (exit ${w.exit}). The cluster is healthy, but the hosts' files now differ: the old one is corosync.conf.before-eject.")
            }
            progress << "  written on ${s.name}, the old file kept as corosync.conf.before-eject".toString()
        }
        Map reload = ctx.run(remaining[0], Preflight.RELOAD_SCRIPT, 60)
        (reload.output as String)?.readLines()?.findAll { it.trim() }?.each { progress << "  ${it}".toString() }
        if (reload.exit != 0) {
            throw new IllegalStateException("Stopped: corosync did not take the new configuration (exit ${reload.exit}). Check the remaining hosts.")
        }
        assertHealthy(ctx, host, progress, 'after the reload')
        Audit.warn(morpheus, who, ctx.cluster, 'eject done: host is out of the cluster', [host: host])
        progress << "Done. Now remove ${host} in Morpheus, and it can be powered off or rebuilt.".toString()
        if (ready.warning) {
            progress << ready.warning.toString()
        }
        HealthMonitor.run(morpheus, ctx, settings, 'quick', false)
    }
}
