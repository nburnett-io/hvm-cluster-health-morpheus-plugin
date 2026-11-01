package org.hvmhealth.morpheus

import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.model.ComputeServer
import com.morpheusdata.model.User
import com.morpheusdata.request.RemoveHostRequest
import com.morpheusdata.response.ServiceResponse
import org.hvmhealth.checks.HealthChecker
import org.hvmhealth.fixes.Fixes
import org.hvmhealth.fixes.Preflight
import org.hvmhealth.fixes.VlanTest
import org.hvmhealth.model.HostFacts

/** The two longer operations on a host in maintenance mode: the VLAN traffic test, and removing it from the cluster. */
class HostOps {
    /** Under a minute when all is well. Morpheus gives up on a hung unmount at 5 minutes, and what follows takes a few more. */
    private static final long REMOVAL_WAIT_MS = 600_000L
    /** Leaving maintenance mode with GFS2 unmounted can make the agent force a reboot, so a removal that stopped after the unmount has to say so. */
    private static final String UNMOUNTED = 'It is still a member, with GFS2 unmounted: reboot it in maintenance mode, which mounts GFS2 again, before it leaves maintenance mode or the removal is tried again.'

    /** Between reads of a running VLAN test, and the least time back on the original uplink between its runs. Not final, for the tests. */
    static long pollMs = 8000L

    /** Runs the test detached, then reads its result, surviving the agent losing the host while uplinks change. Split into runs short enough for corosync's token. */
    static Map vlanTest(MorpheusContext morpheus, HealthContext ctx, String host, User who, List<String> progress, Map settings) {
        Map<String, HostFacts> facts = ctx.probe('quick')
        HealthChecker.Input in_ = ctx.input(facts, settings?.siteStandards as String)
        List<String> blocked = Fixes.blockers(HealthChecker.Tier.MAINTENANCE, in_, host)
        if (blocked) {
            Audit.warn(morpheus, who, ctx.cluster, 'VLAN test refused', [host: host, why: blocked.join('; ')])
            throw new IllegalStateException("Not run: ${blocked.join('; ')}")
        }
        List<Map> networks = in_.networks.findAll { it.vlan && it.gateway }.unique { it.vlan }
        if (!networks) {
            throw new IllegalStateException('This cluster has no Morpheus networks with both a VLAN and a gateway to test. Add them first.')
        }
        int perRun = VlanTest.vlansPerRun(facts[host]?.runtimeToken, networks.size())
        if (perRun < 1) {
            Audit.warn(morpheus, who, ctx.cluster, 'VLAN test refused', [host: host, why: "corosync token ${facts[host].runtimeToken} ms"])
            throw new IllegalStateException("Not run: testing one VLAN can keep ${host} on one uplink for 12 s, more than half its corosync token of ${facts[host].runtimeToken} ms. If that uplink does not carry the ring's VLAN, the host would drop out of the cluster with GFS2 mounted.")
        }
        ComputeServer s = ctx.hosts.find { it.name == host }
        Audit.warn(morpheus, who, ctx.cluster, 'VLAN test started', [host: host, vlans: networks*.vlan.join(',')])
        Map parsed = [results: [], skipped: []]
        networks.collate(perRun).eachWithIndex { List<Map> part, int i ->
            if (i) {
                Thread.sleep(pollMs)
            }
            Map run = vlanRun(morpheus, ctx, s, part, who, progress)
            parsed.results += run.results
            parsed.skipped += run.skipped
            parsed.restored = run.restored
        }
        parsed.skipped = (parsed.skipped as List<Map>).unique { it.bridge }
        Map sum = VlanTest.summary(parsed)
        ((sum.carriedBy as List<String>) + (sum.uplinkFailures as List<String>) + (sum.nowhere as List<String>) + (sum.inconclusive as List<String>) + (sum.notTested as List<String>)).each { progress << "  ${it}".toString() }
        Audit.info(morpheus, who, ctx.cluster, 'VLAN test done', [host: host, failures: (sum.uplinkFailures as List).join('; ') ?: 'none', nowhere: (sum.nowhere as List).join('; ') ?: 'none',
                                                                 inconclusive: (sum.inconclusive as List).join('; ') ?: 'none', notTested: (sum.notTested as List).join('; ') ?: 'none'])
        [host: host, results: parsed.results, restored: parsed.restored] + sum
    }

    /** One run of the test over some of the VLANs; the uplink is back where it was when this returns, or it says otherwise. */
    private static Map vlanRun(MorpheusContext morpheus, HealthContext ctx, ComputeServer s, List<Map> networks, User who, List<String> progress) {
        String host = s.name
        progress << "${host}: starting the test of VLAN(s) ${networks*.vlan.join(', ')} on each uplink".toString()
        Map started = ctx.run(s, VlanTest.launchScript(networks), 60)
        if (started.exit == VlanTest.BUSY_EXIT) {
            throw new IllegalStateException("${host}: an earlier VLAN test is still running there, perhaps from an attempt that lost contact. Its timer puts the original uplink back, so try again once it has finished.")
        }
        if (started.exit != 0) {
            throw new IllegalStateException("${host}: could not start the test (exit ${started.exit})")
        }
        Map parsed = [done: false]
        long began = System.currentTimeMillis()
        long deadline = began + 200_000L
        while (System.currentTimeMillis() < deadline && !parsed.done) {
            Thread.sleep(pollMs)
            try {
                parsed = VlanTest.parse(ctx.run(s, VlanTest.READ_SCRIPT, 20).output as String)
                // several bonds and many VLANs take longer: the test says how long its own timer allows
                deadline = Math.max(deadline, began + ((parsed.budget ?: 0L) as long) * 1000L + 60_000L)
            } catch (Exception ignored) {
                progress << '  no answer from the host yet: its uplink may be moving'
            }
        }
        if (parsed.done) {
            try {
                ctx.run(s, VlanTest.CLEAN_SCRIPT, 20)
            } catch (Exception ignored) {
                // a file in /run is gone at the next reboot, and the next test replaces it
            }
        }
        if (!parsed.done) {
            Audit.warn(morpheus, who, ctx.cluster, 'VLAN test did not report back', [host: host])
            throw new IllegalStateException("${host} did not report back in time. Its timer puts the original uplink back on its own; check the host.")
        }
        if (parsed.error) {
            Audit.warn(morpheus, who, ctx.cluster, 'VLAN test did not run', [host: host, why: parsed.error])
            throw new IllegalStateException(parsed.error as String)
        }
        if (!parsed.results || (parsed.results as List<Map>).every { it.untested }) {
            throw new IllegalStateException("${host}: the test finished without testing anything. Nothing can be concluded about these VLANs.")
        }
        (parsed.results as List<Map>).each { Map r ->
            String on = r.bond ? "${r.bond}/${r.uplink}" : r.uplink
            progress << "  ${on} VLAN ${r.vlan} -> gateway ${r.gateway}: ${r.untested ? 'NOT TESTED' : (r.replies ? r.replies + ' replies' : 'NO REPLY')}".toString()
        }
        (parsed.skipped as List<Map>).each { progress << "  not tested: bridge ${it.bridge}: ${it.why}".toString() }
        progress << "  active uplink back on ${parsed.restored}".toString()
        if ((parsed.leftover as String) && parsed.leftover != '0') {
            progress << "  WARNING: ${parsed.leftover} temporary interface(s) were left on the host".toString()
        }
        if (parsed.original && parsed.restored != parsed.original) {
            Audit.warn(morpheus, who, ctx.cluster, 'VLAN test did not put the uplink back', [host: host, was: parsed.original, now: parsed.restored])
            throw new IllegalStateException("${host}: the active uplink is ${parsed.restored}, and was ${parsed.original} before the test. Check the host, and put it back before anything else runs there.")
        }
        parsed
    }

    /**
     * The checks Morpheus's remove does not make, then that remove. It carries on by itself once started, so a problem seen
     * afterwards is reported rather than stopped.
     */
    static Map removeHost(MorpheusContext morpheus, HealthContext ctx, String host, User who, List<String> progress, Map settings) {
        HealthChecker.Input in_ = ctx.input(ctx.probe('quick'), settings?.siteStandards as String)
        Map ready = Preflight.removeReadiness(in_, host)
        if (!ready.ready) {
            Audit.warn(morpheus, who, ctx.cluster, 'host removal refused', [host: host, why: (ready.blockers as List).join('; ')])
            throw new IllegalStateException("Not run: ${(ready.blockers as List).join('; ')}")
        }
        ComputeServer target = ctx.hosts.find { it.name == host }
        int remaining = ctx.hosts.size() - 1
        Audit.warn(morpheus, who, ctx.cluster, 'host removal started', [host: host])
        progress << "${host}: the checks passed, so unmounting GFS2 first".toString()
        Map un = ctx.run(target, Preflight.unmountScript(), 120)
        (un.output as String)?.readLines()?.findAll { it.trim() }?.take(20)?.each { progress << "  ${it}".toString() }
        if (un.exit != 0) {
            Audit.warn(morpheus, who, ctx.cluster, 'host removal stopped at the unmount', [host: host, exit: un.exit])
            throw new IllegalStateException(un.exit == 3
                    ? "Stopped: something still has GFS2 open on ${host} (see above). Nothing was changed, and Morpheus was not asked."
                    : (un.exit == 4
                    ? "Stopped: ${host} refused to unmount GFS2 (see above), so Morpheus was not asked to remove it. That datastore stays mounted, with its pool started again. Find what holds it, then try again."
                    : "Stopped: ${host} did not finish unmounting GFS2 within the plugin's limit (exit ${un.exit}), so Morpheus was not asked to remove it. Do not rush a reboot: a GFS2 unmount can hang while a lock is cleared and, from kernel source, the wait is bounded at about 600 s and then completes and leaves cleanly (not yet confirmed on a live host), so a reboot mid-unmount is the unclean departure that freezes the survivors. Wait out Morpheus's own timeout and re-check; reboot only once it is certainly out of use, then try again."))
        }
        progress << "${host}: Morpheus is removing it".toString()
        ServiceResponse r
        try {
            // a job thread has no web request's session, and removeHost needs one
            r = MorpheusSession.run(morpheus) { morpheus.services.computeServer.removeHost(target, new RemoveHostRequest(userId: who?.id)) }
        } catch (Exception e) {
            Audit.warn(morpheus, who, ctx.cluster, 'host removal failed', [host: host, why: e.message])
            throw new IllegalStateException("Morpheus could not remove ${host}: ${e.message}. ${UNMOUNTED}", e)
        }
        if (!r.success) {
            Audit.warn(morpheus, who, ctx.cluster, 'host removal refused by Morpheus', [host: host, why: r.msg ?: r.errors])
            throw new IllegalStateException("Morpheus did not remove ${host}: ${r.msg ?: r.errors}. ${UNMOUNTED}")
        }
        long deadline = System.currentTimeMillis() + REMOVAL_WAIT_MS
        while (System.currentTimeMillis() < deadline) {
            Thread.sleep(10_000L)
            Map<String, HostFacts> peers = ctx.probe('quick').findAll { k, v -> k != host }
            peers.each { String h, HostFacts f ->
                if (f.frozen) {
                    Audit.warn(morpheus, who, ctx.cluster, 'freeze during host removal', [host: host, frozen: h])
                    throw new IllegalStateException("${h} is frozen during the removal of ${host}, which Morpheus is still carrying out. See the emergency section. Acknowledge a node only once it is certainly down.")
                }
            }
            if (peers.size() == remaining && peers.values().every { it.complete && it.quorate && it.expectedVotes == remaining && it.nodelist.size() == remaining }) {
                progress << "The remaining ${remaining} hosts are healthy and count ${remaining} votes, with no fence wait.".toString()
                Audit.warn(morpheus, who, ctx.cluster, 'host removed from the cluster', [host: host])
                if (ready.warning) {
                    progress << ready.warning.toString()
                }
                progress << "${host} can now be powered off or rebuilt.".toString()
                return HealthMonitor.run(morpheus, ctx, settings, 'quick', false)
            }
        }
        Audit.warn(morpheus, who, ctx.cluster, 'host removal did not finish in time', [host: host])
        throw new IllegalStateException("Morpheus accepted the removal of ${host}, but the remaining hosts did not settle on ${remaining} votes within ${(REMOVAL_WAIT_MS / 60000L) as int} minutes. The likeliest cause is a hung GFS2 unmount on ${host}, which Morpheus gives up on while its removal carries on: its services can stop later, and the others can then wait to fence it. Keep watching this cluster's emergency section, reboot ${host} once it is certainly out of use, and check that corosync.conf on the rest no longer lists it (CLU-05).")
    }
}
