package org.hvmhealth.checks

import groovy.transform.Canonical
import org.hvmhealth.model.HostFacts
import org.hvmhealth.standards.Layout
import org.hvmhealth.standards.Standards

/**
 * Evaluates every host's facts together against the standards of the cluster's layout. Many of the checks that matter
 * only exist across hosts: what one host reports can look fine while the cluster disagrees with itself.
 */
class HealthChecker {
    static enum Severity { CRITICAL, HIGH, MEDIUM, LOW }
    static enum Status { PASS, FAIL, WARN, NA }
    /**
     * How a finding can be fixed, and what guards the fix:
     *  SAFE         no effect on the cluster: can run any time, and automatically when that setting is on
     *  MAINTENANCE  could disturb the cluster: only on a host in maintenance mode, with no VMs (see MaintenanceGate)
     *  EMERGENCY    the cluster is already down and this ends it: a person confirms, never automatic
     */
    static enum Tier { NONE, SAFE, MAINTENANCE, EMERGENCY }

    @Canonical
    static class Result {
        String id
        String title
        Severity severity
        Status status
        String host        // null: about the whole cluster
        String observed
        String expected
        String source
        String fix
        Tier tier = Tier.NONE
        String hint
        /** Who this touches: "3 VMs: Acme (2), Globex (1)". Only operators ever see it. */
        String impact
    }

    /** What Morpheus says about one host, next to what the host reports. */
    @Canonical
    static class Host {
        String name
        boolean maintenance
        String memberStatus
        String statusMessage
        Long lastAgentMs
        HostFacts facts
        String ip
        Long maxMemory
    }

    @Canonical
    static class Input {
        String clusterName
        Layout layout
        List<Host> hosts = []
        List<Map> morpheusVms = []    // [name, host, power, memory]
        Standards standards
        long nowMs = System.currentTimeMillis()
        Map clusterConfig = [:]       // cpuModel, notHighAvailability
        List<Map> networks = []       // [name, vlan, gateway]: the cluster's Morpheus networks that carry a VLAN
    }

    private Input in_
    private Standards st
    private List<Result> out

    private void add(String id, String title, Severity sev, Status status, String host, String observed, String expected,
                     String source, String fix = null, Tier tier = Tier.NONE, String hint = null) {
        out << new Result(id, title, sev, status, host, observed, expected, source, fix, tier, hint)
    }

    List<Result> check(Input input) {
        in_ = input
        st = input.standards
        out = []
        List<Host> reachable = input.hosts.findAll { it.facts?.complete }
        input.hosts.findAll { !it.facts?.complete }.each {
            add('PRB-01', 'Host probe completed', Severity.HIGH, Status.FAIL, it.name, 'no complete answer from the agent', 'probe finishes', 'plugin', null, Tier.NONE,
                    'The host may be down or its agent stopped. Checks below use only the hosts that answered.')
        }
        layoutChecks(reachable)
        membershipChecks(reachable)
        dlmChecks(reachable)
        gfs2Checks(reachable)
        vmChecks(reachable)
        storageChecks(reachable)
        networkChecks(reachable)
        hostChecks(reachable)
        timeChecks(reachable)
        migrationChecks(reachable)
        capacityChecks()
        haChecks(reachable)
        vlanChecks(reachable)
        storageLatencyChecks(reachable)
        hardwareChecks(reachable)
        morpheusChecks()
        out.removeAll { it.id in st.disabledChecks }
        addImpact()
        out.sort { a, b -> rank(a) <=> rank(b) ?: a.id <=> b.id ?: (a.host ?: '') <=> (b.host ?: '') }
    }

    static int rank(Result r) {
        (r.status in [Status.PASS, Status.NA] ? 100 : 0) + r.severity.ordinal() * 10 + (r.status == Status.FAIL ? 0 : 1)
    }

    /** The questions an operator asks before touching a cluster, and which checks answer each. */
    static final Map<String, String> AREAS = [
            'High availability': /^(CLU|DLM|HB|HA|CAP|FEN|MOR)-.*|^VM-01$/,
            'Live migration'   : /^(MIG|TIM)-.*|^NET-09$|^GFS-02$/,
            'Storage migration': /^(GFS|STO)-.*|^MIG-03$/,
            'Time'             : /^TIM-.*/,
            'Network'          : /^NET-.*/,
            'Hardware'         : /^HW-.*/,
    ]

    /** VMs by tenant, from Morpheus's view. */
    static String impactOf(List<Map> vms) {
        List<Map> on = vms.findAll { it.power == 'on' }
        if (!on) {
            return 'no running VMs'
        }
        String tenants = on.groupBy { (it.tenant ?: 'unknown') as String }.collect { k, v -> [k, v.size()] }.sort { -(it[1] as int) }.collect { "${it[0]} (${it[1]})" }.join(', ')
        "${on.size()} running VM(s): ${tenants}".toString()
    }

    /** A finding on a host touches its VMs; a finding on the cluster touches all of them. */
    private void addImpact() {
        out.findAll { it.status in [Status.FAIL, Status.WARN] }.each { Result r ->
            r.impact = impactOf(r.host ? in_.morpheusVms.findAll { it.host == r.host } : in_.morpheusVms)
        }
    }

    // ------------------------------------------------------------------ VLANs carrying traffic

    /**
     * Passive: whether each host's uplink has heard traffic on each of the cluster's VLANs. Switches rarely advertise a trunk's
     * allowed VLANs, but a VLAN its peers hear and a host does not is a likely pruned trunk. It is a warning only: a quiet
     * VLAN also looks like this, because learned addresses age out after minutes. The active test settles it.
     */
    private void vlanChecks(List<Host> hs) {
        if (hs.size() < 2) {
            return
        }
        in_.networks.findAll { it.vlan }.each { Map n ->
            Integer vlan = n.vlan as Integer
            List<Host> hear = hs.findAll { it.facts.vlanSeen[vlan] }
            List<Host> silent = hs - hear
            if (hear) {
                add('NET-11', "VLAN ${vlan} (${n.name}) carries traffic on every host".toString(), Severity.MEDIUM, silent ? Status.WARN : Status.PASS, null,
                        silent ? "no traffic on ${silent*.name.join(', ')}; heard on ${hear*.name.join(', ')}".toString() : "heard on ${hear.size()} host(s)".toString(), 'heard on every host', 'plugin', null, Tier.NONE,
                        silent ? "The trunk to ${silent*.name.join(', ')} may not pass this VLAN, so VMs moved there would lose their network. Put the host in maintenance mode and run its VLAN test to find out." : null)
            }
        }
    }

    // ------------------------------------------------------------------ storage latency

    private void storageLatencyChecks(List<Host> hs) {
        int warn = st.intVal('storageWarnMs'), fail = st.intVal('storageFailMs'), statWarn = st.intVal('gfs2StatWarnMs')
        hs.each { Host h ->
            h.facts.storageStats.each { Map s ->
                long ios = (s.reads as long) + (s.writes as long)
                if (ios) {
                    long avg = ((s.readMs as long) + (s.writeMs as long)) / ios as long
                    add('STO-06', "Storage latency: ${s.wwid}".toString(), Severity.HIGH, avg >= fail ? Status.FAIL : (avg >= warn ? Status.WARN : Status.PASS), h.name,
                            "${avg} ms average over ${ios} I/Os in 2 s, ${s.inflight} in flight".toString(), "< ${warn} ms".toString(), st.source('storageWarnMs'), null, Tier.NONE,
                            avg >= warn ? 'Check the other hosts. If they are slow too, look at the array; if only this one is, its paths and NICs.' : null)
                }
            }
            h.facts.gfsStatMs.each { String m, Integer ms ->
                add('GFS-08', "GFS2 metadata responsive: ${m}".toString(), Severity.MEDIUM, ms > statWarn ? Status.WARN : Status.PASS, h.name, "${ms} ms to read a file's attributes".toString(), "<= ${statWarn} ms".toString(), st.source('gfs2StatWarnMs'))
            }
        }
    }

    // ------------------------------------------------------------------ hardware

    private void hardwareChecks(List<Host> hs) {
        int days = st.intVal('selDays')
        hs.findAll { it.facts.mode == 'full' }.each { Host h ->
            HostFacts f = h.facts
            List<Map> psu = f.sensors.findAll { it.type == 'Power Supply' }
            if (psu) {
                List<Map> bad = psu.findAll { !(it.name =~ /(?i)redundan/) && !(it.status in ['ok', 'ns']) }
                Map red = psu.find { it.name =~ /(?i)redundan/ }
                boolean redundant = !red || red.reading =~ /(?i)fully redundant/
                add('HW-01', 'Power supplies healthy and redundant', Severity.HIGH, bad ? Status.FAIL : (redundant ? Status.PASS : Status.WARN), h.name,
                        bad ? "failing: ${bad*.name.join(', ')}".toString() : (red ? "redundancy: ${red.reading}".toString() : "${psu.size()} sensor(s) ok".toString()), 'fully redundant', 'plugin', null, Tier.NONE,
                        !bad && !redundant ? 'One power supply or feed failing takes this host down, and its cluster membership with it. Check both supplies are cabled and the BMC redundancy policy is on.' : null)
            }
            if (f.edac) {
                long ue = f.edac.sum { it.ue } as long, ce = f.edac.sum { it.ce } as long
                add('HW-02', 'No memory errors', Severity.HIGH, ue ? Status.FAIL : (ce ? Status.WARN : Status.PASS), h.name, "${ue} uncorrectable, ${ce} correctable".toString(), '0', 'plugin', null, Tier.NONE,
                        ue || ce ? 'Rising memory errors come before a host crash. An unclean crash freezes every GFS2 datastore until it is acknowledged, so move VMs off and plan a replacement.' : null)
            }
            List<Map> temps = f.sensors.findAll { it.type == 'Temperature' && !(it.status in ['ok', 'ns']) }
            if (f.sensors.any { it.type == 'Temperature' }) {
                add('HW-03', 'Temperatures within limits', Severity.HIGH, temps ? Status.FAIL : Status.PASS, h.name, temps ? temps.collect { "${it.name}: ${it.reading}" }.join(', ') : 'all ok', 'all ok', 'plugin')
            }
            List<String> recent = recentSerious(f, days)
            if (f.sel) {
                add('HW-04', 'No serious hardware events recently', Severity.MEDIUM, recent ? Status.WARN : Status.PASS, h.name, recent ? recent.take(4).join('; ') : "none in ${days} days".toString(), 'none', st.source('selDays'))
            }
            List<String> speeds = f.nics*.speed.findAll().unique()
            if (speeds.size() > 1) {
                add('HW-05', 'Uplinks at the same speed', Severity.MEDIUM, Status.WARN, h.name, f.nics.collect { "${it.name}=${it.speed}" }.join(', '), 'equal', 'plugin', null, Tier.NONE, 'A failover would change the bandwidth this host has.')
            }
        }
        // firmware among identical servers: different models are expected to differ
        hs.findAll { it.facts.hwProduct }.groupBy { it.facts.hwProduct }.each { String product, List<Host> same ->
            if (same.size() > 1) {
                [['BIOS', { HostFacts f -> f.hwBios }], ['management controller', { HostFacts f -> f.hwBmc }], ['NIC firmware', { HostFacts f -> f.nics*.firmware.findAll().unique().sort().join('/') }]].each { List spec ->
                    Map<String, String> v = same.collectEntries { Host h -> [(h.name): (spec[1] as Closure<String>).call(h.facts)] }.findAll { k, x -> x } as Map<String, String>
                    if ((v.values() as Set).size() > 1) {
                        add('HW-06', "Same ${spec[0]} on identical servers (${product})".toString(), Severity.LOW, Status.WARN, null, v.collect { k, x -> "${k}=${x}" }.join(', '), 'equal', 'plugin')
                    }
                }
            }
        }
    }

    /** Serious events still asserted within the window. The host's own NOW decides "recent", since BMC dates are mm/dd/yy. */
    private static List<String> recentSerious(HostFacts f, int days) {
        long nowSec = f.now ?: 0L
        f.sel.findAll { Map e -> e.asserted && e.text =~ /(?i)critical|uncorrect|non-recoverable|failure|fault|lost|ecc/ && !(e.text =~ /(?i)non-critical/) }.findAll { Map e ->
            try {
                long at = new java.text.SimpleDateFormat('MM/dd/yy').parse(e.date as String).time / 1000L as long
                nowSec - at <= days * 86400L
            } catch (java.text.ParseException ignored) {
                false
            }
        }.collect { "${it.date} ${it.text}".toString() }
    }

    /** Each area's worst status, and how many findings it has. */
    static List<Map> readiness(List<Result> rs) {
        AREAS.collect { String area, String rx ->
            List<Result> mine = rs.findAll { it.id ==~ rx }
            int fail = mine.count { it.status == Status.FAIL }, warn = mine.count { it.status == Status.WARN }
            [area: area, status: fail ? 'FAIL' : (warn ? 'WARN' : (mine.any { it.status == Status.PASS } ? 'PASS' : 'NA')), fail: fail, warn: warn,
             ids: mine.findAll { it.status in [Status.FAIL, Status.WARN] }*.id.unique()]
        }
    }

    /** CRITICAL, DEGRADED, WARNING or HEALTHY: the headline of a set of results. */
    static String overall(List<Result> rs) {
        if (rs.any { it.status == Status.FAIL && it.severity == Severity.CRITICAL }) {
            return 'CRITICAL'
        }
        if (rs.any { it.status == Status.FAIL }) {
            return 'DEGRADED'
        }
        rs.any { it.status == Status.WARN } ? 'WARNING' : 'HEALTHY'
    }

    // ------------------------------------------------------------------ layout

    private void layoutChecks(List<Host> hs) {
        Layout l = in_.layout
        add('LAY-01', 'Cluster layout recognised', Severity.MEDIUM, l?.version ? Status.PASS : Status.WARN, null, l?.label ?: 'unknown',
                'HVM 1.3 or 2.0', 'plugin', null, Tier.NONE, l?.version ? null : 'Only checks that do not depend on the layout are meaningful.')
        hs.each { Host h ->
            String want = st.val('osVersion') as String
            if (want) {
                add('LAY-02', 'Host OS matches the layout', Severity.HIGH, h.facts.osVersion == want ? Status.PASS : Status.FAIL, h.name, h.facts.osVersion, want, st.source('osVersion'))
            }
            if (l?.virtswitch) {
                boolean ok = h.facts.hvmcli && h.facts.virtswitchFiles > 0
                add('LAY-04', 'VirtSwitch network built with hvmcli', Severity.MEDIUM, ok ? Status.PASS : Status.WARN, h.name,
                        ok ? "${h.facts.virtswitchFiles} VirtSwitch file(s)".toString() : 'no hvmcli VirtSwitch netplan file', 'HVM 2.0 network layout', 'morpheus')
            } else if (l?.version == '1.3') {
                boolean ok = (h.facts.ovsBridges ?: '').tokenize(',').contains('mgmt')
                add('LAY-05', 'Open vSwitch management bridge present', Severity.MEDIUM, ok ? Status.PASS : Status.WARN, h.name, h.facts.ovsBridges ?: 'none', 'mgmt bridge', 'morpheus')
            }
        }
    }

    // ------------------------------------------------------------------ corosync membership

    private void membershipChecks(List<Host> hs) {
        int minHosts = st.intVal('minHosts')
        add('CLU-01', 'Enough hosts for quorum', Severity.HIGH, in_.hosts.size() >= minHosts ? Status.PASS : Status.FAIL, null,
                "${in_.hosts.size()} host(s) in Morpheus".toString(), ">= ${minHosts}".toString(), st.source('minHosts'), null, Tier.NONE,
                in_.hosts.size() >= minHosts ? null : 'Add a host or a witness: with fewer, one failure stops every GFS2 datastore.')

        hs.each { Host h ->
            HostFacts f = h.facts
            ['corosync', 'dlm'].each { String u ->
                boolean up = f.active(u)
                boolean enabled = f.service(u).enabled in ['enabled', 'static', 'indirect']
                add('CLU-02', "${u} running and enabled".toString(), Severity.CRITICAL, up && enabled ? Status.PASS : (up ? Status.WARN : Status.FAIL), h.name,
                        "${f.service(u).active ?: '?'}/${f.service(u).enabled ?: '?'}".toString(), 'active/enabled', 'morpheus',
                        up ? (enabled ? null : 'service-enable') : (f.confPresent ? 'rejoin' : null),
                        up ? (enabled ? Tier.SAFE : Tier.NONE) : (f.confPresent ? Tier.MAINTENANCE : Tier.NONE),
                        f.confPresent ? null : 'This host has no corosync.conf: it is not a cluster member, so it needs adding back through Morpheus rather than a service start.')
            }
            if (f.active('corosync')) {
                add('CLU-03', 'Host sees a quorate cluster', Severity.CRITICAL, f.quorate ? Status.PASS : Status.FAIL, h.name, f.quorate ? 'quorate' : 'NOT quorate', 'quorate', 'upstream')
                if (f.totalVotes != null && f.expectedVotes != null) {
                    boolean full = f.totalVotes >= f.expectedVotes
                    boolean margin = f.quorumVotes == null || f.totalVotes > f.quorumVotes
                    add('CLU-04', 'Quorum margin: a host can fail without losing quorum', Severity.HIGH, full ? Status.PASS : (margin ? Status.WARN : Status.FAIL), h.name,
                            "${f.totalVotes} of ${f.expectedVotes} votes, quorum ${f.quorumVotes}".toString(), 'all expected votes present', 'upstream', null, Tier.NONE,
                            full ? null : 'A member is missing: find out which and why before any host work. One more failure at no margin stops every datastore.')
                }
                if (f.linkFaults) {
                    add('CLU-08', 'Corosync links connected', Severity.HIGH, Status.FAIL, h.name, f.linkFaults.collect { "node ${it.nodeid}: ${it.status}" }.join(', '), 'every peer connected', 'upstream')
                }
            }
        }

        // the cluster agreeing with itself and with Morpheus
        List<Host> withConf = hs.findAll { it.facts.confPresent }
        if (withConf) {
            withConf.each { Host h ->
                List<Map> nodes = h.facts.nodelist
                List<String> ghost = nodes.findAll { Map n -> !in_.hosts.any { isNode(n, it) } }.collect { it.name as String }.sort()
                List<String> absent = in_.hosts.findAll { Host m -> !nodes.any { isNode(it, m) } }*.name.sort()
                boolean ok = !ghost && !absent
                add('CLU-05', 'corosync nodelist matches the hosts Morpheus has in this cluster', Severity.HIGH, ok ? Status.PASS : Status.FAIL, h.name,
                        ok ? "${nodes.size()} node(s)".toString() : ([ghost ? "in corosync only: ${ghost.join(', ')}" : null, absent ? "in Morpheus only: ${absent.join(', ')}" : null].findAll().join('; ')),
                        'same set', 'morpheus', null, Tier.NONE,
                        ghost ? 'A node corosync still counts but Morpheus no longer manages. If it goes down or is rebuilt without leaving the cluster, every GFS2 datastore freezes until it is acknowledged as fenced. Remove it from the cluster properly before touching that node.' : null)
            }
            List<String> sigs = withConf.collect { Host h -> [h.facts.corosync['totem.config_version'], h.facts.nodelist.collect { "${it.nodeid}=${it.addr}" }.sort()].toString() }.unique()
            add('CLU-06', 'corosync.conf identical on every host', Severity.HIGH, sigs.size() == 1 ? Status.PASS : Status.FAIL, null,
                    sigs.size() == 1 ? "config_version ${withConf[0].facts.corosync['totem.config_version']}".toString() : "${sigs.size()} different versions: ${withConf.collect { "${it.name}=v${it.facts.corosync['totem.config_version']}" }.join(', ')}".toString(),
                    'one version', 'upstream')
            List<String> keys = withConf*.facts*.corosyncKey.findAll().unique()
            add('CLU-07', 'corosync authkey identical on every host', Severity.CRITICAL, keys.size() <= 1 ? Status.PASS : Status.FAIL, null,
                    keys.size() <= 1 ? 'matches' : "${keys.size()} different keys".toString(), 'one key', 'upstream', null, Tier.NONE,
                    'Compared by a truncated hash only; the key itself never leaves the host.')
            totemChecks(withConf)
        }
    }

    /**
     * Whether a corosync node is this Morpheus host. By ring address first: HVM 2.0 names nodes host-<nodeid>, and a Morpheus
     * name can differ from the host's own in more than case.
     */
    static boolean isNode(Map node, Host h) {
        String addr = node.addr as String
        if (addr && (addr == h.ip || addr == h.facts?.ringAddr)) {
            return true
        }
        String name = (node.name as String)?.toLowerCase()
        name && (name == h.name?.toLowerCase() || name == h.facts?.hostname?.toLowerCase())
    }

    private void totemChecks(List<Host> hs) {
        hs.each { Host h ->
            Map<String, String> c = h.facts.corosync
            Integer token = c['totem.token']?.isInteger() ? c['totem.token'] as Integer : null
            Integer consensus = c['totem.consensus']?.isInteger() ? c['totem.consensus'] as Integer : null
            if (token && consensus) {
                BigDecimal ratio = st.val('consensusRatio') as BigDecimal
                add('CLU-09', 'corosync consensus timeout at least 1.2 x token', Severity.MEDIUM, consensus >= token * ratio ? Status.PASS : Status.FAIL, h.name,
                        "token ${token}, consensus ${consensus}".toString(), "consensus >= ${(token * ratio) as int}".toString(), st.source('consensusRatio'))
            }
            Integer wantToken = st.val('corosyncToken') as Integer
            if (wantToken && token) {
                add('CLU-10', 'corosync token timeout matches the standard', Severity.MEDIUM, token == wantToken ? Status.PASS : Status.WARN, h.name, "${token}".toString(), "${wantToken}".toString(), st.source('corosyncToken'))
            }
            Integer wantCons = st.val('corosyncConsensus') as Integer
            if (wantCons && consensus) {
                add('CLU-11', 'corosync consensus timeout matches the standard', Severity.MEDIUM, consensus == wantCons ? Status.PASS : Status.WARN, h.name, "${consensus}".toString(), "${wantCons}".toString(), st.source('corosyncConsensus'))
            }
        }
    }

    // ------------------------------------------------------------------ DLM

    private void dlmChecks(List<Host> hs) {
        if (in_.layout?.hciCeph) {
            return
        }
        hs.each { Host h ->
            HostFacts f = h.facts
            if (!f.active('dlm')) {
                return
            }
            String fenceWanted = st.val('fencingEnabled') ? '1' : '0'
            add('DLM-04', 'dlm.conf enable_fencing', Severity.HIGH, f.dlmConf.enable_fencing == fenceWanted ? Status.PASS : Status.FAIL, h.name,
                    f.dlmConf.enable_fencing ?: 'unset', fenceWanted, st.source('fencingEnabled'), null, Tier.NONE,
                    f.dlmConf.enable_fencing == fenceWanted ? null : 'Do not turn fencing off to get past a freeze: GFS2 would recover while the failed node may still write, which corrupts the filesystem.')
            f.fenceWaits.each { Map w ->
                long mins = ((w.now as Long) - (w.since as Long)) / 60L as long
                String name = f.nodeNames[w.node as Integer] ?: "node ${w.node}"
                add('DLM-01', 'Lockspaces waiting for a node to be fenced', Severity.CRITICAL, Status.FAIL, h.name,
                        "waiting on ${name} (node ${w.node}) for ${mins} min: all GFS2 I/O on this host is blocked".toString(), 'no fence waits', 'upstream', 'fence-ack:' + w.node, Tier.EMERGENCY,
                        'Every VM disk and heartbeat on GFS2 is frozen until the node is acknowledged as fenced. Only acknowledge it once the node is certainly down: acknowledging a node that can still write corrupts the filesystem.')
                Map<Integer, Integer> acks = f.acks
                if (!acks.containsKey(w.node as Integer)) {
                    String wrong = acks ? acks.collect { k, v -> "node ${k} x${v}" }.join(', ') : 'nothing'
                    add('DLM-02', 'HVM agent is acknowledging the failed node', Severity.CRITICAL, Status.FAIL, h.name,
                            "agent acked ${wrong} in the last 15 min, never node ${w.node}".toString(), "acks node ${w.node}".toString(), 'morpheus', null, Tier.NONE,
                            'The freeze will not clear by itself. Usually the node has gone from Morpheus while corosync still counts it (see CLU-05).')
                }
            }
            if (!f.fenceWaits) {
                List<Map> stopped = f.lockspaces.findAll { (it.flags as String)?.contains('kern_stop') || it.status }
                add('DLM-03', 'DLM lockspaces running', Severity.CRITICAL, stopped ? Status.FAIL : Status.PASS, h.name,
                        stopped ? stopped.collect { "${it.name}: ${it.status ?: it.flags}" }.join(', ') : "${f.lockspaces.size()} lockspace(s) running".toString(), 'running', 'upstream')
            }
        }
        List<Host> withDlm = hs.findAll { it.facts.lockspaces }
        if (withDlm.size() > 1) {
            Map<String, List<String>> views = [:]
            withDlm.each { Host h -> h.facts.lockspaces.each { Map s -> views.computeIfAbsent(s.name as String) { [] } << ((s.members as String) ?: '').trim() } }
            views.each { String ls, List<String> ms ->
                if (ms.unique(false).size() > 1) {
                    add('DLM-05', 'Hosts agree on lockspace members', Severity.HIGH, Status.FAIL, null, "${ls}: ${ms.unique(false).join(' vs ')}".toString(), 'same members', 'upstream')
                }
            }
        }
    }

    // ------------------------------------------------------------------ GFS2

    private void gfs2Checks(List<Host> hs) {
        if (in_.layout?.hciCeph) {
            return
        }
        hs.each { Host h ->
            HostFacts f = h.facts
            List<Map> withdrawn = f.tables.findAll { it.withdraw == '1' }
            add('GFS-01', 'No withdrawn GFS2 filesystems', Severity.CRITICAL, withdrawn ? Status.FAIL : Status.PASS, h.name,
                    withdrawn ? withdrawn*.table.join(', ') : "${f.tables.size()} mounted".toString(), 'withdraw=0', 'upstream', null, Tier.NONE,
                    withdrawn ? 'A withdrawn filesystem stays unusable on this host until it is unmounted and remounted: move its VMs off in a maintenance window. Check storage paths first, since lost I/O is the usual cause.' : null)
            String cluster = f.corosync['totem.cluster_name']
            if (cluster) {
                List<String> foreign = f.tables.findAll { !(it.table as String).startsWith(cluster + ':') && !(it.table as String).startsWith('recover:') }*.table as List<String>
                add('GFS-03', 'GFS2 lock tables belong to this cluster', Severity.HIGH, foreign ? Status.FAIL : Status.PASS, h.name,
                        foreign ? foreign.join(', ') : 'all match', "${cluster}:<fs>".toString(), 'upstream', null, Tier.NONE,
                        foreign ? 'A filesystem from another cluster, or an unresignatured snapshot clone. Two clusters mounting one GFS2 filesystem corrupts it.' : null)
            }
            int warn = st.intVal('gfs2WarnPct'), fail = st.intVal('gfs2FailPct')
            f.usage.each { Map u ->
                int pct = (((u.used as Long) * 100) / (u.size as Long)) as int
                add('GFS-04', "GFS2 datastore space: ${u.target}".toString(), Severity.MEDIUM, pct >= fail ? Status.FAIL : (pct >= warn ? Status.WARN : Status.PASS), h.name,
                        "${pct}% used".toString(), "< ${warn}%".toString(), st.source('gfs2WarnPct'))
            }
            f.journals.each { String dev, Integer n ->
                add('GFS-06', 'GFS2 journals cover every host', Severity.MEDIUM, n >= in_.hosts.size() ? Status.PASS : Status.WARN, h.name, "${n} journal(s) on ${dev}".toString(),
                        ">= ${in_.hosts.size()} (hosts)".toString(), 'upstream', null, Tier.NONE, n >= in_.hosts.size() ? null : 'A host beyond the journal count cannot mount this filesystem.')
            }
            add('GFS-05', 'No hung kernel tasks', Severity.HIGH, f.hung1h >= st.intVal('hungTasksWarn') ? Status.WARN : Status.PASS, h.name,
                    "${f.hung1h} report(s) in the last hour; ${f.dCount} task(s) in D state now, oldest ${f.dOldest ?: 0} s".toString(), 'none', st.source('hungTasksWarn'))
            if (f.fsTimeout) {
                add('GFS-07', 'GFS2 answers promptly', Severity.HIGH, Status.FAIL, h.name, 'df and heartbeat reads did not finish in 15 s although DLM looks healthy', 'answers in seconds', 'plugin', null, Tier.NONE,
                        'Look at storage latency and paths next.')
            }
        }

        // same datastores on every host
        List<Host> mounted = hs.findAll { it.facts.tables }
        Set<String> allTables = mounted.collectMany { it.facts.tables*.table as List<String> }.findAll { !(it as String).startsWith('recover:') } as Set<String>
        hs.each { Host h ->
            List<String> missing = (allTables - ((h.facts.tables*.table ?: []) as Set<String>)).sort()
            if (allTables) {
                add('GFS-02', 'Every GFS2 datastore mounted on this host', Severity.HIGH, missing ? Status.FAIL : Status.PASS, h.name,
                        missing ? "missing ${missing.join(', ')}".toString() : "${allTables.size()} mounted".toString(), 'same on every host', 'morpheus')
            }
        }
        heartbeatChecks(hs)
    }

    private void heartbeatChecks(List<Host> hs) {
        HostFacts reader = hs*.facts.find { it.fsSafe && it.heartbeats }
        if (!reader) {
            boolean frozen = hs*.facts.any { it.frozen }
            add('HB-01', 'Host heartbeats current', Severity.HIGH, frozen ? Status.NA : Status.WARN, null,
                    frozen ? 'not read: GFS2 is frozen, and reading it would add another stuck task' : 'no heartbeat files found', 'one fresh file per host', 'morpheus')
            return
        }
        long nowMs = (reader.now ?: 0L) * 1000L
        long maxAge = st.intVal('heartbeatMaxAgeSec') * 1000L
        in_.hosts.each { Host h ->
            Map hb = reader.heartbeats.find { (it.host as String)?.equalsIgnoreCase(h.facts?.hostname ?: h.name) }
            if (h.maintenance) {
                // HVM removes a host's heartbeat file about 90 s into maintenance mode, and writes it again 40-60 s after leaving
                add('HB-01', 'Host heartbeat present', Severity.HIGH, Status.NA, h.name, 'host is in maintenance mode, which removes its heartbeat', 'one per host', 'morpheus')
            } else if (!hb?.ms) {
                add('HB-01', 'Host heartbeat present', Severity.HIGH, Status.WARN, h.name, 'no heartbeat file', 'one per host', 'morpheus', null, Tier.NONE,
                        'Expected for up to a minute after a host leaves maintenance mode. If it stays, HVM failover cannot judge this host: check its agent.')
            } else {
                long age = (nowMs - (hb.ms as Long)) / 1000L as long
                add('HB-01', 'Host heartbeat current', Severity.HIGH, age * 1000L > maxAge ? Status.FAIL : Status.PASS, h.name, "${age} s old".toString(), "<= ${st.val('heartbeatMaxAgeSec')} s".toString(), st.source('heartbeatMaxAgeSec'))
            }
        }
    }

    // ------------------------------------------------------------------ VMs

    private void vmChecks(List<Host> hs) {
        Map<String, List<String>> runningOn = [:]
        hs.each { Host h ->
            h.facts.vms.findAll { it.state == 'running' }.each { Map v -> runningOn.computeIfAbsent(v.name as String) { [] } << h.name }
        }
        List<String> dupes = runningOn.findAll { k, v -> v.size() > 1 }.collect { k, v -> "${k}: ${v.join(', ')}".toString() }
        add('VM-01', 'No VM running on two hosts', Severity.CRITICAL, dupes ? Status.FAIL : Status.PASS, null, dupes ? dupes.join('; ') : "${runningOn.size()} running VM(s)".toString(), 'one host per VM', 'plugin', null, Tier.NONE,
                dupes ? 'Two copies writing one disk corrupt it. Stop one copy immediately; decide which by what its guest was serving.' : null)
        hs.each { Host h ->
            if (h.facts.dVms) {
                add('VM-02', 'No VM stuck on its disk', Severity.CRITICAL, Status.FAIL, h.name, h.facts.dVms.join(', '), 'none in D state', 'plugin', null, Tier.NONE,
                        h.facts.frozen ? 'Caused by the frozen lockspace. These VMs cannot answer on the network until it clears.' : 'Look at storage paths and latency.')
            }
            if (h.facts.frozen && h.facts.agentKills15m > 0) {
                add('VM-03', 'HVM agent has VM stops queued during a freeze', Severity.CRITICAL, Status.FAIL, h.name, "${h.facts.agentKills15m} shutdown, destroy or kill command(s) in 15 min".toString(), 'none', 'plugin', null, Tier.NONE,
                        'They take effect the moment I/O returns, so expect these VMs to power off when the freeze clears, then start them.')
            }
            if (h.maintenance) {
                int running = h.facts.vms.count { it.state == 'running' }
                add('VM-04', 'Host in maintenance mode has no running VMs', Severity.MEDIUM, running ? Status.WARN : Status.PASS, h.name, "${running} running".toString(), '0', 'plugin')
                if (h.facts.active('corosync') && h.facts.lockspaces) {
                    add('HA-02', 'Host in maintenance mode is still a cluster member', Severity.MEDIUM, Status.WARN, h.name, 'corosync and DLM still count it', 'understood before it goes down', 'plugin', null, Tier.NONE,
                            'Maintenance mode empties a host but does not take it out of the cluster. Powering it off or rebuilding it now freezes every GFS2 datastore on the other hosts until it is acknowledged as fenced. Remove it from the cluster first.')
                }
            }
        }
        in_.morpheusVms.each { Map vm ->
            List<String> seen = runningOn[vm.name as String] ?: []
            boolean morpheusOn = vm.power == 'on'
            boolean anyReached = in_.hosts.find { it.name == vm.host }?.facts?.complete
            if (anyReached && morpheusOn != (seen as boolean)) {
                add('VM-05', 'Morpheus and libvirt agree on VM power', Severity.LOW, Status.WARN, vm.host as String,
                        "${vm.name}: Morpheus ${vm.power}, libvirt ${seen ? 'running' : 'not running'}".toString(), 'same', 'morpheus', null, Tier.NONE,
                        morpheusOn ? null : 'Do not press Start on it in Morpheus until this clears: it is already running.')
            }
        }
    }

    // ------------------------------------------------------------------ storage

    private void storageChecks(List<Host> hs) {
        hs.each { Host h ->
            HostFacts f = h.facts
            if (st.val('mpio')) {
                f.maps.each { Map m ->
                    List<Map> ps = m.paths as List<Map>
                    List<Map> bad = ps.findAll { !(it.state == 'active' && it.checker == 'ready') }
                    add('STO-01', "Multipath paths healthy: ${m.wwid}".toString(), Severity.HIGH, bad ? Status.FAIL : (ps.size() < 2 ? Status.WARN : Status.PASS), h.name,
                            "${ps.size() - bad.size()} of ${ps.size()} healthy".toString(), 'all healthy, at least 2', st.source('mpio'), bad ? 'multipath-reconfigure' : null, bad ? Tier.MAINTENANCE : Tier.NONE)
                    if ((m.features as String)?.contains('queue_if_no_path')) {
                        add('STO-02', "Map does not queue I/O forever: ${m.wwid}".toString(), Severity.HIGH, Status.WARN, h.name, m.features as String, 'no queue_if_no_path', 'upstream', null, Tier.NONE,
                                'Queueing with no paths hangs GFS2 instead of failing the I/O. Set no_path_retry in the multipath configuration, in a maintenance window.')
                    }
                }
            }
            add('STO-03', 'No iSCSI session errors', Severity.MEDIUM, f.errors24h >= st.intVal('iscsiErrorsWarn') ? Status.WARN : Status.PASS, h.name, "${f.errors24h} in 24 h".toString(), '0', st.source('iscsiErrorsWarn'))
            if (f.sessions && f.maps) {
                add('STO-04', 'iSCSI multipath daemons running', Severity.HIGH, f.active('multipathd') && f.active('iscsid') ? Status.PASS : Status.FAIL, h.name,
                        "multipathd ${f.service('multipathd').active}, iscsid ${f.service('iscsid').active}".toString(), 'active', 'morpheus')
            }
        }
        // same LUN path count on every host
        Map<String, Map<String, Integer>> counts = [:]
        hs.each { Host h -> h.facts.maps.each { Map m -> counts.computeIfAbsent(m.wwid as String) { [:] }[h.name] = (m.paths as List).size() } }
        counts.each { String wwid, Map<String, Integer> byHost ->
            if ((byHost.values() as Set).size() > 1) {
                add('STO-05', "Same path count on every host: ${wwid}".toString(), Severity.MEDIUM, Status.WARN, null, byHost.collect { k, v -> "${k}=${v}" }.join(', '), 'equal', 'plugin')
            }
        }
    }

    // ------------------------------------------------------------------ network

    private void networkChecks(List<Host> hs) {
        hs.each { Host h ->
            HostFacts f = h.facts
            f.bonds.each { Map b ->
                List<Map> slaves = b.slaves as List<Map>
                List<Map> down = slaves.findAll { it.status != 'up' }
                add('NET-01', "Bond members up: ${b.name}".toString(), Severity.HIGH, down ? Status.FAIL : Status.PASS, h.name,
                        down ? "down: ${down*.name.join(', ')}".toString() : "${slaves.size()} up".toString(), 'all up', 'plugin', null, Tier.NONE,
                        down ? 'The host still works but has lost its redundancy: one more failure takes the cluster network with it. Check the cable, optic and switch port.' : null)
            }
            if (f.ringDev) {
                List<String> shared = f.portalDevs.findAll { it.dev == f.ringDev }*.portal as List<String>
                add('NET-02', 'Corosync does not share an interface with iSCSI', Severity.MEDIUM, shared ? Status.WARN : Status.PASS, h.name,
                        shared ? "${f.ringDev} also carries ${shared.join(', ')}".toString() : "corosync on ${f.ringDev}".toString(), 'separate', 'plugin', null, Tier.NONE,
                        shared ? 'Storage traffic can delay corosync messages enough to get a host evicted.' : null)
            }
            int warnMs = st.intVal('ringRttWarnMs')
            f.peers.each { Map p ->
                boolean bad = (p.loss ?: 0) > 0 || (p.rttMs != null && (p.rttMs as BigDecimal) > warnMs) || p.rttMs == null
                add('NET-03', "Ring reachability to ${p.addr}".toString(), Severity.MEDIUM, bad ? Status.WARN : Status.PASS, h.name,
                        "${p.loss ?: 0}% loss, ${p.rttMs ?: '?'} ms".toString(), "0% loss, <= ${warnMs} ms".toString(), st.source('ringRttWarnMs'))
            }
        }
        lldpChecks(hs)
        netmaskChecks(hs)
        // MTU of the corosync interface should match across hosts
        Map<String, Integer> ringMtu = hs.findAll { it.facts.ringDev }.collectEntries { Host h ->
            [(h.name): h.facts.interfaces.find { it.name == h.facts.ringDev }?.mtu]
        }
        if ((ringMtu.values().findAll() as Set).size() > 1) {
            add('NET-04', 'Same MTU on the corosync interface of every host', Severity.MEDIUM, Status.WARN, null, ringMtu.collect { k, v -> "${k}=${v}" }.join(', '), 'equal', 'plugin')
        }
    }

    /**
     * What the switch says about each uplink, over LLDP (or CDP where lldpd listens for it). This proves cabling and
     * redundancy. It does not prove a tenant VLAN passes: switches commonly advertise only the native VLAN, not the trunk's
     * allowed list, so an absent VLAN here means nothing and a present one is only the native VLAN.
     */
    private void lldpChecks(List<Host> hs) {
        Map<String, Set<String>> nativeByHost = [:]
        hs.each { Host h ->
            HostFacts f = h.facts
            if (f.lldpAvailable == null) {
                return   // an older probe, or the section was skipped
            }
            if (!f.lldpAvailable) {
                add('NET-05', 'Switch neighbours visible (LLDP)', Severity.LOW, Status.NA, h.name, 'lldpd is not running', 'running', 'plugin', null, Tier.NONE,
                        'Without it the plugin cannot see which switch each uplink lands on.')
                return
            }
            f.bonds.each { Map b ->
                List<Map> up = (b.slaves as List<Map>).findAll { it.status == 'up' }
                List<String> silent = up.findAll { !f.neighbours[it.name] }*.name as List<String>
                add('NET-05', "Switch neighbour on every uplink of ${b.name}".toString(), Severity.LOW, silent ? Status.WARN : Status.PASS, h.name,
                        silent ? "nothing heard on ${silent.join(', ')}".toString() : up.collect { "${it.name} -> ${f.neighbours[it.name]?.chassis} ${f.neighbours[it.name]?.port}" }.join(', '),
                        'a neighbour per uplink', 'plugin')
                Set<String> switches = up.collect { f.neighbours[it.name]?.chassis }.findAll() as Set<String>
                if (up.size() > 1 && switches) {
                    add('NET-06', "Uplinks of ${b.name} on different switches".toString(), Severity.MEDIUM, switches.size() > 1 ? Status.PASS : Status.WARN, h.name,
                            switches.join(', '), 'at least two switches', 'plugin', null, Tier.NONE,
                            switches.size() > 1 ? null : 'One switch failing or rebooting takes every uplink of this host, and its cluster membership with it.')
                }
                Set<String> natives = up.collect { f.neighbours[it.name]?.pvid }.findAll() as Set<String>
                if (natives) {
                    nativeByHost.computeIfAbsent(h.name) { [] as Set } .addAll(natives)
                }
                if (natives.size() > 1) {
                    add('NET-07', "Same native VLAN on every uplink of ${b.name}".toString(), Severity.MEDIUM, Status.WARN, h.name, natives.join(', '), 'one native VLAN', 'plugin', null, Tier.NONE,
                            'A failover to the other uplink would change the VLAN of untagged traffic.')
                }
            }
        }
        Set<Set<String>> distinct = nativeByHost.values() as Set<Set<String>>
        if (distinct.size() > 1) {
            add('NET-08', 'Same native VLAN on every host', Severity.MEDIUM, Status.WARN, null, nativeByHost.collect { k, v -> "${k}=${v.join('/')}" }.join(', '), 'equal', 'plugin')
        }
    }

    private static long ipv4(String ip) {
        ip.tokenize('.').inject(0L) { long acc, String o -> (acc << 8) | (o as Long) }
    }

    private static long network(long ip, int prefix) {
        prefix == 0 ? 0L : ip & ((0xFFFFFFFFL << (32 - prefix)) & 0xFFFFFFFFL)
    }

    /**
     * Management, migration and storage interfaces must carry the same netmask as their counterparts on the other hosts. A
     * host with the wrong one still reaches most of its subnet, and silently fails for peers outside its narrower range.
     * Counterparts are found by overlapping subnets, since interface names can differ with hardware, and also by name.
     */
    private void netmaskChecks(List<Host> hs) {
        List<Map> all = hs.collectMany { Host h ->
            h.facts.addrs.findAll { !(it.ip as String).startsWith('169.254.') && !(it.ip as String).startsWith('127.') && !(it.dev as String).startsWith('virbr') }
                    .collect { [host: h.name, dev: it.dev, prefix: it.prefix, ip: ipv4(it.ip as String), text: "${h.name} ${it.dev} /${it.prefix}".toString()] }
        }
        if (hs.size() < 2 || !all) {
            return
        }
        List<Map> groups = []
        all.sort(false) { it.prefix }.each { Map a ->
            Map g = groups.find { (network(a.ip as long, it.prefix as int)) == (it.net as long) }
            if (g) {
                g.members << a
            } else {
                groups << [net: network(a.ip as long, a.prefix as int), prefix: a.prefix, members: [a]]
            }
        }
        List<String> drift = groups.findAll { (it.members*.prefix as Set).size() > 1 }.collect { Map g -> (g.members as List<Map>)*.text.sort().join(', ') }
        // the same interface name with different netmasks, where the subnets do not even overlap
        all.groupBy { it.dev }.each { dev, List<Map> members ->
            if ((members*.prefix as Set).size() > 1 && !drift.any { d -> members.every { d.contains(it.text as String) } }) {
                drift << (members*.text.sort().join(', ') as String)
            }
        }
        add('NET-10', 'Netmasks match their counterparts on other hosts', Severity.HIGH, drift ? Status.FAIL : Status.PASS, null,
                drift ? drift.join('; ') : "${groups.size()} network(s) compared".toString(), 'same netmask on every host per network', 'plugin', null, Tier.NONE,
                drift ? 'The host with the narrower mask treats peers outside its range as off-subnet and sends them to the gateway, or nowhere: management, migration or iSCSI to those peers fails while everything else works. Correct it in the host\'s network configuration, in a maintenance window.' : null)
    }

    // ------------------------------------------------------------------ time

    private static boolean osDefault(String server) {
        server =~ /\.ubuntu\.com$|pool\.ntp\.org$/
    }

    /** What a host's time configuration amounts to: "cluster source", "OS default", "other servers" or "none configured". */
    static String timeState(HostFacts f, List<String> wanted) {
        List<String> have = f.timeServers
        if (!have) {
            return 'none configured'
        }
        if (wanted && wanted.any { it in have } && (!f.timeFollowing || f.timeFollowing in wanted)) {
            return 'cluster source'
        }
        have.every { osDefault(it) } ? 'OS default' : 'other servers'
    }

    /**
     * How far each host's clock sits from the others, in ms, from the offsets each host measures to the time servers they
     * share: two hosts reading the same server is a direct comparison of their clocks, without the seconds of agent latency
     * that comparing their own timestamps would carry. Null when no server is seen by two hosts. Offsets drift apart a
     * little between polls, so the result is an estimate good to a few milliseconds.
     */
    static Map hostDrift(List<Host> hs) {
        Map<String, Map<String, BigDecimal>> byServer = [:]
        hs.each { Host h ->
            h.facts.chronySources.findAll { it.reach != '0' }.each { Map s ->
                byServer.computeIfAbsent(s.addr as String) { [:] }[h.name] = s.offsetMs as BigDecimal
            }
        }
        Map<String, Map<String, BigDecimal>> shared = byServer.findAll { k, v -> v.size() > 1 }
        if (!shared) {
            return null
        }
        Map<String, List<BigDecimal>> deviations = [:]
        shared.values().each { Map<String, BigDecimal> offs ->
            BigDecimal mean = offs.values().sum() / offs.size()
            offs.each { String h, BigDecimal o -> deviations.computeIfAbsent(h) { [] } << (o - mean) }
        }
        Map<String, BigDecimal> perHost = deviations.collectEntries { String h, List<BigDecimal> d -> [(h): d.sort()[d.size() / 2 as int]] }
        [perHost: perHost, spreadMs: perHost.size() > 1 ? perHost.values().max() - perHost.values().min() : 0G, servers: shared.size()]
    }

    /** For the tab's time panel: what is defined for the cluster, and what each host really does. */
    static Map timeView(Input in_) {
        List<Host> hs = in_.hosts.findAll { it.facts?.complete }
        List<String> wanted = (in_.standards.val('ntpServers') ?: []) as List<String>
        Map drift = hostDrift(hs)
        [defined: wanted, definedBy: wanted ? in_.standards.source('ntpServers') : null, driftMs: drift ? (drift.spreadMs as BigDecimal).setScale(1, BigDecimal.ROUND_HALF_UP) : null, driftServers: drift?.servers,
         hosts: hs.collect { Host h ->
             Map c = h.facts.chrony
             [name: h.name, state: timeState(h.facts, wanted), configured: h.facts.timeServers, following: h.facts.timeFollowing ?: c?.source,
              synced: h.facts.ntpSynced, offsetMs: c ? (c.offsetMs as BigDecimal).abs().setScale(1, BigDecimal.ROUND_HALF_UP) : null, stratum: c?.stratum,
              driftMs: drift?.perHost?.get(h.name) != null ? (drift.perHost[h.name] as BigDecimal).setScale(1, BigDecimal.ROUND_HALF_UP) : null]
         }]
    }

    private void timeChecks(List<Host> hs) {
        BigDecimal maxOffset = st.val('maxNtpOffsetMs') as BigDecimal
        List<String> wanted = (st.val('ntpServers') ?: []) as List<String>
        hs.each { Host h ->
            Map c = h.facts.chrony
            if (c) {
                BigDecimal off = (c.offsetMs as BigDecimal).abs().setScale(1, BigDecimal.ROUND_HALF_UP)
                boolean ok = off <= maxOffset && c.leap == 'Normal' && h.facts.chronySelected > 0
                add('TIM-03', 'In sync with a time source', Severity.HIGH, ok ? Status.PASS : Status.FAIL, h.name,
                        "${off} ms from ${c.source}, stratum ${c.stratum}, ${c.leap}${h.facts.chronySelected ? '' : ', no source selected'}".toString(),
                        "<= ${maxOffset} ms, a source selected".toString(), st.source('maxNtpOffsetMs'))
            }
            String state = timeState(h.facts, wanted)
            String what = "${state}: following ${h.facts.timeFollowing ?: c?.source ?: 'nothing'}; configured ${h.facts.timeServers.join(', ') ?: 'none'}".toString()
            if (wanted) {
                boolean listed = wanted.any { it in h.facts.timeServers }
                add('TIM-04', 'Host uses the cluster time source', Severity.MEDIUM, state == 'cluster source' ? Status.PASS : (listed ? Status.WARN : Status.FAIL), h.name, what, wanted.join(', '), st.source('ntpServers'),
                        listed ? null : 'ntp-sources', listed ? Tier.NONE : Tier.MAINTENANCE,
                        listed && state != 'cluster source' ? 'Configured, but chrony is following another server. Check this host can reach the cluster source.' : null)
            } else {
                add('TIM-04', 'Host uses the cluster time source', Severity.MEDIUM, Status.NA, h.name, what, 'none defined for this cluster', 'plugin')
            }
        }
        List<List<String>> sets = hs*.facts*.timeServers.findAll().unique()
        if (sets.size() > 1) {
            add('TIM-05', 'Every host configured with the same time sources', Severity.MEDIUM, Status.WARN, null, hs.collect { "${it.name}=${it.facts.timeServers.join('/')}" }.join('; '), 'same on every host', 'plugin', null, Tier.NONE,
                    'Hosts following different sources drift apart, even with each one "in sync".')
        }
        if (hs) {
            boolean allDefault = sets && sets.flatten().every { osDefault(it as String) }
            add('TIM-06', 'A time source is defined for this cluster', Severity.LOW, wanted ? Status.PASS : Status.WARN, null,
                    wanted ? "${wanted.join(', ')} (${st.source('ntpServers')})".toString() : (allDefault ? 'none defined; hosts follow the OS default public servers' : 'none defined'),
                    'a time source you run', 'plugin', null, Tier.NONE,
                    wanted ? null : 'Define it in the Time section below. Then every host is verified against it, and hosts on a segment that loses internet are not left drifting together unnoticed.')
        }
        Map drift = hostDrift(hs)
        if (drift) {
            BigDecimal max = st.val('maxHostDriftMs') as BigDecimal
            BigDecimal spread = (drift.spreadMs as BigDecimal).setScale(1, BigDecimal.ROUND_HALF_UP)
            add('TIM-02', 'Host clocks agree', Severity.HIGH, spread > max ? Status.FAIL : Status.PASS, null, "${spread} ms apart, measured against ${drift.servers} shared time server(s)".toString(), "<= ${max} ms".toString(), st.source('maxHostDriftMs'), null, Tier.NONE,
                    spread > max ? 'Live migration moves a running clock between hosts, and corosync and GFS2 logs stop lining up. Find the host that differs in the Time section.' : null)
        } else {
            List<Long> times = hs*.facts*.now.findAll()
            if (times.size() > 1) {
                long skew = times.max() - times.min()
                int max = st.intVal('maxClockSkewSec')
                add('TIM-02', 'Host clocks agree', Severity.MEDIUM, skew > max ? Status.WARN : Status.PASS, null, "${skew} s apart (coarse: these hosts share no time server to compare against)".toString(), "<= ${max} s".toString(), st.source('maxClockSkewSec'))
            }
        }
    }

    // ------------------------------------------------------------------ live and storage migration

    /**
     * A CPU model to recommend: from the family of one of the hosts' own models, and usable on every host per libvirt. The
     * name is the unversioned one, as Morpheus's CPU Model list has Skylake-Server-noTSX-IBRS, never libvirt's
     * Skylake-Server-v5, and the IBRS variant is preferred for its Spectre mitigation. Null rather than a guess when no
     * model fits: the usable list is unordered, and libvirt's own baseline tool can answer with an unrelated model plus a
     * list of added features, which means nothing as a model name alone.
     */
    static String commonCpu(List<HostFacts> hosts) {
        List<HostFacts> known = hosts.findAll { it?.usableCpus }
        if (!known || known.size() != hosts.size()) {
            return null
        }
        Collection<String> everywhere = known*.usableCpus.inject(known[0].usableCpus) { Collection<String> a, List<String> b -> a.intersect(b) }
        List<String> named = everywhere.findAll { !(it ==~ /.*-v\d+/) } as List<String>
        for (String family : known*.libvirtCpu.findAll().collect { it.replaceFirst(/-v\d+$/, '') }.unique()) {
            List<String> matches = named.findAll { it == family || it.startsWith(family + '-') }
            if (matches) {
                return matches.find { it.contains('IBRS') } ?: matches[0]
            }
        }
        null
    }

    private void migrationChecks(List<Host> hs) {
        Map<String, List<Host>> byFlags = hs.findAll { it.facts.cpuFlags }.groupBy { it.facts.cpuFlags }
        String model = in_.clusterConfig?.cpuModel as String
        if (byFlags) {
            boolean same = byFlags.size() == 1
            String observed = hs.groupBy { it.facts.cpuModel }.collect { k, v -> "${k}${v[0].facts.libvirtCpu ? ' (' + v[0].facts.libvirtCpu + ')' : ''}: ${v*.name.join(', ')}" }.join('; ')
            boolean named = model && !(model in ['host-passthrough', 'host-model'])
            Status s = same ? Status.PASS : (named ? Status.WARN : Status.FAIL)
            String common = commonCpu(hs*.facts)
            boolean namedUsable = named && hs.every { !it.facts.usableCpus || model in it.facts.usableCpus }
            String hint = same ? null : (named
                    ? "Hosts differ, and the cluster uses the named model ${model}, the EVC equivalent. ${namedUsable ? 'Libvirt reports it usable on every host.' : 'Libvirt does NOT report it usable on every host' + (common ? ': ' + common + ' is.' : '.')} Test a live migration in both directions."
                    : "HVM's equivalent of EVC is the cluster's CPU Model: edit the cluster and set it to ${common ? common + ', which libvirt reports usable on every host' : 'a model libvirt reports usable on every host'}. It can be set on an existing cluster, but running VMs keep their CPU until at least their next power cycle, and reports differ on whether existing VMs pick it up at all, so test on one VM first, and test a live migration in both directions.").toString()
            add('MIG-01', 'VMs can live-migrate between every host (CPU)', Severity.HIGH, s, null, "${observed}${model ? '; cluster CPU model ' + model : ''}".toString(), 'same CPU features on every host, or a named CPU model all of them support', 'hpe', null, Tier.NONE, hint)
        }
        String wantedCpu = st.val('clusterCpuModel') as String
        if (wantedCpu) {
            add('MIG-05', 'Cluster CPU model matches the standard', Severity.MEDIUM, model == wantedCpu ? Status.PASS : Status.FAIL, null, model ?: 'not set', wantedCpu, st.source('clusterCpuModel'))
        }
        hs.each { Host h ->
            List<Map> noSsh = h.facts.peers.findAll { it.ssh == false }
            if (h.facts.peers.any { it.ssh != null }) {
                add('MIG-02', 'Reaches every peer on the migration port', Severity.HIGH, noSsh ? Status.FAIL : Status.PASS, h.name,
                        noSsh ? "TCP 22 closed to ${noSsh*.addr.join(', ')}".toString() : "${h.facts.peers.size()} peer(s)".toString(), 'TCP 22 between hosts', 'hpe')
            }
            List<Map> badPath = h.facts.peers.findAll { it.pathMtuOk == false }
            if (h.facts.peers.any { it.pathMtuOk != null }) {
                add('NET-09', 'Full-size packets reach every peer', Severity.HIGH, badPath ? Status.FAIL : Status.PASS, h.name,
                        badPath ? "${badPath.collect { "${it.addr} at MTU ${it.mtu}" }.join(', ')} dropped".toString() : "MTU ${h.facts.peers[0].mtu}".toString(), 'unfragmented at the interface MTU', 'plugin', null, Tier.NONE,
                        badPath ? 'Small traffic works and large traffic vanishes: migrations hang partway with no clear error. A switch or VLAN on this path has a lower MTU than the host.' : null)
            }
        }
        // the same datastores defined and active everywhere, or a VM has nowhere to go
        Set<String> allPools = hs.collectMany { it.facts.pools*.name as List<String> } as Set<String>
        hs.findAll { it.facts.sections.containsKey('pools') }.each { Host h ->
            List<String> missing = (allPools - (h.facts.pools*.name as Set<String>)).sort()
            List<String> inactive = h.facts.pools.findAll { it.state != 'active' }*.name as List<String>
            add('MIG-03', 'Same datastores active on every host', Severity.HIGH, missing || inactive ? Status.FAIL : Status.PASS, h.name,
                    missing || inactive ? [missing ? "missing ${missing.join(', ')}" : null, inactive ? "inactive ${inactive.join(', ')}" : null].findAll().join('; ') : "${h.facts.pools.size()} pool(s) active".toString(),
                    'same pools, all active', 'morpheus')
        }
        // somewhere for each running VM to go
        List<Map> tooBig = in_.morpheusVms.findAll { Map v ->
            v.power == 'on' && v.memory && hs.size() > 1 && !hs.any { Host o -> o.name != v.host && !o.maintenance && o.facts.memAvailKb && o.facts.memAvailKb * 1024L >= (v.memory as Long) }
        }
        if (hs.any { it.facts.memAvailKb }) {
            add('MIG-04', 'Every running VM fits on another host', Severity.MEDIUM, tooBig ? Status.WARN : Status.PASS, null,
                    tooBig ? tooBig*.name.join(', ') : "${in_.morpheusVms.count { it.power == 'on' }} running VM(s)".toString(), 'enough free memory elsewhere', 'plugin', null, Tier.NONE,
                    tooBig ? 'These cannot move, so their host cannot be emptied for maintenance.' : null)
        }
    }

    // ------------------------------------------------------------------ HA

    private void haChecks(List<Host> hs) {
        if (in_.clusterConfig?.notHighAvailability == true) {
            add('HA-01', 'Morpheus records this cluster as highly available', Severity.LOW, Status.WARN, null, 'cluster config has notHighAvailability=true', 'not set', 'morpheus', null, Tier.NONE,
                    'HPE does not document this flag. Confirm with HPE whether it limits failover on this cluster.')
        }
    }

    // ------------------------------------------------------------------ capacity

    /** N+1: if the largest host fails, can the rest hold every running VM? Memory is the limit that stops a restart. */
    private void capacityChecks() {
        List<Host> sized = in_.hosts.findAll { it.maxMemory }
        if (sized.size() != in_.hosts.size() || sized.size() < 2) {
            return
        }
        long running = in_.morpheusVms.findAll { it.power == 'on' }.sum { (it.memory ?: 0L) as Long } as Long ?: 0L
        long total = sized.sum { it.maxMemory } as Long
        long survivors = total - (sized*.maxMemory.max() as Long)
        long gib = 1L << 30
        add('CAP-01', 'VMs fit on the remaining hosts if the largest fails', Severity.MEDIUM, running > survivors ? Status.WARN : Status.PASS, null,
                "${(running / gib) as long} GiB of running VMs, ${(survivors / gib) as long} GiB left without the largest host".toString(),
                'running VMs fit', 'plugin', null, Tier.NONE, running > survivors ? 'Automatic failover could not restart every VM: some tenants would stay down until a host returned.' : null)
    }

    // ------------------------------------------------------------------ host

    private void hostChecks(List<Host> hs) {
        hs.each { Host h ->
            HostFacts f = h.facts
            add('TIM-01', 'Clock synchronised', Severity.HIGH, f.ntpSynced == null ? Status.NA : (f.ntpSynced ? Status.PASS : Status.FAIL), h.name,
                    f.ntpSynced == null ? 'unknown' : (f.ntpSynced ? 'synchronised' : "not synchronised (NTP ${f.ntp ? 'on' : 'off'})".toString()), 'synchronised', 'upstream',
                    f.ntpSynced == false && f.ntp == false ? 'ntp-enable' : null, f.ntpSynced == false && f.ntp == false ? Tier.SAFE : Tier.NONE,
                    f.ntpSynced == false && f.ntp ? 'NTP is on but not synchronised: check that the time servers are reachable.' : null)
            if (f.rebootRequired) {
                add('HST-01', 'No pending reboot', Severity.LOW, Status.WARN, h.name, 'reboot required', 'none', 'plugin', null, Tier.NONE, 'Reboot in a maintenance window, one host at a time.')
            }
        }
        ['corosync', 'dlm-controld', 'gfs2-utils', 'libvirt-daemon-system', 'qemu-system-x86', 'morpheus-node'].each { String p ->
            Map<String, String> versions = hs.findAll { it.facts.packages[p] }.collectEntries { [(it.name): it.facts.packages[p]] }
            if ((versions.values() as Set).size() > 1) {
                add('PKG-01', "Same ${p} on every host".toString(), Severity.LOW, Status.WARN, null, versions.collect { k, v -> "${k}=${v}" }.join(', '), 'equal', 'plugin')
            }
        }
        Map<String, String> kernels = hs.findAll { it.facts.kernel }.collectEntries { [(it.name): it.facts.kernel] }
        if ((kernels.values() as Set).size() > 1) {
            add('PKG-02', 'Same kernel on every host', Severity.LOW, Status.WARN, null, kernels.collect { k, v -> "${k}=${v}" }.join(', '), 'equal', 'plugin')
        }
        hs.findAll { it.facts.mode == 'full' }.each { Host h ->
            add('FEN-01', 'Host management controller known', Severity.LOW, h.facts.bmcIp && h.facts.bmcIp != '0.0.0.0' ? Status.PASS : Status.WARN, h.name, h.facts.bmcIp ?: 'none', 'configured', 'plugin', null, Tier.NONE,
                    h.facts.bmcIp ? null : 'Without one there is no out-of-band way to prove this host is powered off when it fails.')
        }
    }

    // ------------------------------------------------------------------ Morpheus's view

    private void morpheusChecks() {
        in_.hosts.each { Host h ->
            String msg = h.statusMessage ?: ''
            boolean healthy = h.facts?.complete && h.facts.active('corosync') && h.facts.quorate
            if (msg =~ /(?i)fenced|nodedown|unclean/ && healthy) {
                add('MOR-01', 'Morpheus host status matches the host', Severity.LOW, Status.WARN, h.name, msg, 'no fence status on a healthy host', 'plugin', null, Tier.NONE,
                        'The host is a healthy cluster member, so this message is stale. Find out whether it affects placement before relying on it.')
            }
            if (h.memberStatus in ['unclean', 'fenced', 'offline'] && healthy) {
                add('MOR-02', 'Morpheus member status matches the host', Severity.MEDIUM, Status.WARN, h.name, h.memberStatus, 'online', 'plugin')
            }
            if (h.lastAgentMs) {
                long age = (in_.nowMs - h.lastAgentMs) / 1000L as long
                int max = st.intVal('agentMaxAgeSec')
                add('MOR-03', 'Morpheus agent checking in', Severity.HIGH, age > max ? Status.FAIL : Status.PASS, h.name, "last ${age} s ago".toString(), "<= ${max} s".toString(), st.source('agentMaxAgeSec'))
            }
        }
    }
}
