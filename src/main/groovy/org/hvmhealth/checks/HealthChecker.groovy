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
    /** NA: the check does not apply here. NOT_CHECKED: it applies, but this host could not answer it, which says nothing either way. */
    static enum Status { PASS, FAIL, WARN, NA, NOT_CHECKED }
    static final String NO_IPMITOOL = 'cannot tell: ipmitool not installed'
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
        List<Map> morpheusVms = []    // [name: the libvirt domain, label: its name in Morpheus, host, power, memory]
        Standards standards
        long nowMs = System.currentTimeMillis()
        Map clusterConfig = [:]       // cpuModel, notHighAvailability, autoRecoverPowerState, cloudAutoRecoverPowerState, datastores, applianceCertNotAfterMs
        List<Map> networks = []       // [name, vlan, gateway]: the cluster's Morpheus networks that carry a VLAN
        Long clusterId
        List<String> removing = []    // host records Morpheus has not finished removing; they are not hosts any more
        List<Map> tenantNetworks = [] // [name, vlan, ownerId, owner, shared]: every network with a VLAN, where several can share one
    }

    /** 8 min 48 s in the one run that reached it. */
    private static final int AGENT_STOPS_VMS_MIN = 9

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
            add('PRB-01', 'Host probe completed', Severity.HIGH, Status.FAIL, it.name, it.facts?.partial ? 'ran out of time before it finished, so only its DLM state is used' : 'no complete answer from the agent', 'probe finishes', 'plugin', null, Tier.NONE,
                    it.facts?.partial ? 'Something on this host is answering slowly: libvirt, the journal or multipath. Checks below use only the hosts that finished.' : 'The host may be down or its agent stopped. Checks below use only the hosts that answered.')
        }
        layoutChecks(reachable)
        membershipChecks(reachable)
        agentQuorumChecks(reachable)
        // DLM is read first, so a host that ran out of time can still name a freeze
        dlmChecks(input.hosts.findAll { it.facts?.complete || it.facts?.partial })
        gfs2Checks(reachable)
        krnChecks(reachable)
        vmChecks(reachable)
        storageChecks(reachable)
        fibreChannelChecks(reachable)
        iscsiTransportChecks(reachable)
        allPathsLostChecks(reachable)
        networkChecks(reachable)
        hostChecks(reachable)
        timeChecks(reachable)
        migrationChecks(reachable)
        capacityChecks()
        overcommitCheck()
        forecastChecks()
        thinLunChecks(reachable)
        haChecks(reachable)
        vlanChecks(reachable)
        storageLatencyChecks(reachable)
        hardwareChecks(reachable)
        morpheusChecks()
        pinnedVmChecks(reachable)
        managerOnClusterCheck()
        if (input.clusterConfig?.siteStandardsError) {
            add('PRB-03', 'Site standards are in force', Severity.HIGH, Status.WARN, null, input.clusterConfig.siteStandardsError as String, 'valid site standards', 'plugin', null, Tier.NONE,
                    'Checks are running on the built-in standards until this is fixed, so none of your overrides or disabled checks apply. Correct it in the plugin settings.')
        }
        failoverChecks(reachable)
        quorumDeviceChecks(reachable)
        certificateChecks(reachable)
        bootDiskChecks(reachable)
        hpeCheck(reachable)
        cpuDriftChecks(reachable)
        storageSpaceChecks(reachable)
        vmPortChecks(reachable)
        frameSizeChecks(reachable)
        hostLoadChecks(reachable)
        patchExposureChecks(reachable)
        kernelPostureChecks(reachable)
        netTimingChecks(reachable)
        tenantSecurityChecks(reachable)
        out.removeAll { it.id in st.disabledChecks }
        addImpact()
        out.sort { a, b -> rank(a) <=> rank(b) ?: a.id <=> b.id ?: (a.host ?: '') <=> (b.host ?: '') }
    }

    static int rank(Result r) {
        (r.status in [Status.PASS, Status.NA, Status.NOT_CHECKED] ? 100 : 0) + r.severity.ordinal() * 10 + (r.status == Status.FAIL ? 0 : 1)
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
            r.impact = impactFor(r.host)
        }
    }

    /** Morpheus stops reporting VMs as running on a host that went silent, which says nothing about whether they are. */
    private String impactFor(String host) {
        if (!host) {
            return impactOf(in_.morpheusVms)
        }
        List<Map> here = in_.morpheusVms.findAll { it.host == host }
        boolean silent = !in_.hosts.find { it.name == host }?.facts?.complete
        silent && here
                ? "${here.size()} VM(s) recorded on this host: ${here.collect { it.label ?: it.name }.join(', ')}. Their state is unknown while it does not answer".toString()
                : impactOf(here)
    }

    // ------------------------------------------------------------------ VLANs carrying traffic

    /**
     * Passive: whether each host's uplink has heard traffic on each of the cluster's VLANs. Switches rarely advertise a trunk's
     * allowed VLANs, but a VLAN its peers hear and a host does not may be a pruned trunk. A quiet VLAN looks the same, because
     * learned addresses age out after minutes, so it would come and go as an alarm: it stays not checked until the active test settles it.
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
                add('NET-11', "VLAN ${vlan} (${n.name}) carries traffic on every host".toString(), Severity.MEDIUM, silent ? Status.NOT_CHECKED : Status.PASS, null,
                        silent ? "no traffic on ${silent*.name.join(', ')}; heard on ${hear*.name.join(', ')}".toString() : "heard on ${hear.size()} host(s)".toString(), 'heard on every host', 'plugin', null, Tier.NONE,
                        silent ? "The trunk to ${silent*.name.join(', ')} may not pass this VLAN, so VMs moved there would lose their network. Put the host in maintenance mode and run its VLAN test to find out." : null)
            }
        }
    }

    // ------------------------------------------------------------------ storage latency

    /** Below this in the 2 s sample an average says little: one slow I/O on a quiet LUN would fail the check. */
    private static final int LATENCY_MIN_IOS = 20

    private void storageLatencyChecks(List<Host> hs) {
        int warn = st.intVal('storageWarnMs'), fail = st.intVal('storageFailMs'), statWarn = st.intVal('gfs2StatWarnMs')
        hs.each { Host h ->
            h.facts.storageStats.each { Map s ->
                long ios = (s.reads as long) + (s.writes as long)
                if (ios >= LATENCY_MIN_IOS) {
                    long avg = ((s.readMs as long) + (s.writeMs as long)) / ios as long
                    add('STO-06', "Storage latency: ${s.wwid}".toString(), Severity.HIGH, avg >= fail ? Status.FAIL : (avg >= warn ? Status.WARN : Status.PASS), h.name,
                            "${avg} ms average over ${ios} I/Os in 2 s, ${s.inflight} in flight".toString(), "< ${warn} ms".toString(), st.source('storageWarnMs'), null, Tier.NONE,
                            avg >= warn ? "Check the other hosts. If they are slow too, look at the array; if only this one is, its paths and NICs.${busiestHint(h)}".toString() : null)
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
            // these read through ipmitool, so nothing read is not the same as nothing wrong
            String unread = f.ipmiToolPresent == null ? null : (f.ipmiToolPresent ? 'cannot tell: ipmitool read no such sensors here' : NO_IPMITOOL)
            String install = f.ipmiToolPresent == false ? 'Morpheus-built hosts do not include ipmitool. Install it to read the hardware through the management controller.' : null
            List<Map> psu = f.sensors.findAll { it.type == 'Power Supply' }
            if (!psu && unread) {
                add('HW-01', 'Power supplies healthy and redundant', Severity.HIGH, Status.NOT_CHECKED, h.name, unread, 'fully redundant', 'plugin', null, Tier.NONE, install)
            }
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
                boolean clean = !ue && !ce
                add('HW-02', 'No memory errors', Severity.HIGH, ue ? Status.FAIL : (ce ? Status.WARN : Status.PASS), h.name,
                        clean ? '0 uncorrectable, 0 correctable reported by EDAC; a firmware-first server may not report corrected errors to Linux, so this is not proof' : "${ue} uncorrectable, ${ce} correctable".toString(),
                        '0', 'plugin', null, Tier.NONE,
                        ue || ce ? 'Rising memory errors come before a host crash. An unclean crash freezes every GFS2 datastore until it is acknowledged, so move VMs off and plan a replacement.'
                                : 'A zero here does not prove the DIMMs are healthy: on HPE firmware-first servers the controller handles memory errors and can hide corrected ones from Linux. Read the management controller or iLO/iDRAC memory health as well.')
            }
            List<Map> temps = f.sensors.findAll { it.type == 'Temperature' && !(it.status in ['ok', 'ns']) }
            if (f.sensors.any { it.type == 'Temperature' }) {
                add('HW-03', 'Temperatures within limits', Severity.HIGH, temps ? Status.FAIL : Status.PASS, h.name, temps ? temps.collect { "${it.name}: ${it.reading}" }.join(', ') : 'all ok', 'all ok', 'plugin')
            } else if (unread) {
                add('HW-03', 'Temperatures within limits', Severity.HIGH, Status.NOT_CHECKED, h.name, unread, 'all ok', 'plugin', null, Tier.NONE, install)
            }
            List<String> recent = recentSerious(f, days)
            if (f.sel) {
                add('HW-04', 'No serious hardware events recently', Severity.MEDIUM, recent ? Status.WARN : Status.PASS, h.name, recent ? recent.take(4).join('; ') : "none in ${days} days".toString(), 'none', st.source('selDays'))
            } else if (f.ipmiToolPresent == false) {
                // with the tool present, an empty log is an answer
                add('HW-04', 'No serious hardware events recently', Severity.MEDIUM, Status.NOT_CHECKED, h.name, NO_IPMITOOL, 'none', st.source('selDays'), null, Tier.NONE, install)
            }
            // a failover only moves traffic within a bond: 1G for management beside 25G for storage is a design
            List<Map> mixed = f.nics.groupBy { it.bond }.values().findAll { it*.speed.findAll().unique().size() > 1 }.flatten() as List<Map>
            if (mixed) {
                add('HW-05', 'Uplinks in a bond at the same speed', Severity.MEDIUM, Status.WARN, h.name, mixed.collect { "${it.name}=${it.speed}" }.join(', '), 'equal', 'plugin', null, Tier.NONE, 'A failover would change the bandwidth this host has.')
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

    /**
     * Each area's worst status, and how many findings it has. An area where nothing passed and something could not be read is
     * NOT_CHECKED rather than NA; one with passes as well stays PASS, with what was not checked listed beside it.
     */
    static List<Map> readiness(List<Result> rs) {
        AREAS.collect { String area, String rx ->
            List<Result> mine = rs.findAll { it.id ==~ rx }
            int fail = mine.count { it.status == Status.FAIL }, warn = mine.count { it.status == Status.WARN }
            List<String> notChecked = mine.findAll { it.status == Status.NOT_CHECKED }*.id.unique() as List<String>
            [area: area, status: fail ? 'FAIL' : (warn ? 'WARN' : (mine.any { it.status == Status.PASS } ? 'PASS' : (notChecked ? 'NOT_CHECKED' : 'NA'))), fail: fail, warn: warn,
             ids: mine.findAll { it.status in [Status.FAIL, Status.WARN] }*.id.unique(), notChecked: notChecked]
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
                add('LAY-05', 'Open vSwitch management bridge present', Severity.MEDIUM, ok ? Status.PASS : Status.WARN, h.name, (h.facts.ovsBridges ?: '').tokenize(',').join(', ') ?: 'none', 'mgmt bridge', 'morpheus')
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
            if (f.authkeyEmpty) {
                add('CLU-13', 'Corosync authkey has something in it', Severity.CRITICAL, Status.FAIL, h.name, '/etc/corosync/authkey is empty', 'the cluster\'s key', 'morpheus', null, Tier.NONE,
                        'Adding this host did not finish: Morpheus can show it as provisioned while corosync cannot start, leaving the others a vote short. Delete the host in Morpheus and add it again, which has worked each time.')
            }
            ['corosync', 'dlm'].each { String u ->
                boolean up = f.active(u)
                boolean enabled = f.service(u).enabled in ['enabled', 'static', 'indirect']
                add('CLU-02', "${u} running and enabled".toString(), Severity.CRITICAL, up && enabled ? Status.PASS : (up ? Status.WARN : Status.FAIL), h.name,
                        "${f.service(u).active ?: '?'}/${f.service(u).enabled ?: '?'}".toString(), 'active/enabled', 'morpheus',
                        up ? (enabled ? null : 'service-enable') : (f.confPresent && !f.authkeyEmpty ? 'rejoin' : null),
                        up ? (enabled ? Tier.NONE : Tier.SAFE) : (f.confPresent && !f.authkeyEmpty ? Tier.MAINTENANCE : Tier.NONE),
                        f.confPresent ? null : 'This host has no corosync.conf: it is not a cluster member, so it needs adding back through Morpheus rather than a service start.')
            }
            if (f.active('corosync')) {
                // HPE: on layouts 1.3 and 2.0 corosync's Quorate does not decide quorum, and reads No on a healthy two-node (and stretch) cluster.
                // So on a genuine two-node corosync cluster CLU-03/CLU-04 are informational, and the agent quorum (CLU-14) is the authority. A
                // degraded cluster of 3+ (a node lost) still means something, and stretch cannot be told apart here, so those keep the corosync check.
                boolean agentDecides = in_.layout?.version in ['1.3', '2.0']
                boolean twoNode = agentDecides && (f.expectedVotes == 2 || (f.expectedVotes == null && f.nodelist?.size() == 2))
                if (twoNode) {
                    add('CLU-03', 'Host sees a quorate cluster', Severity.CRITICAL, Status.NA, h.name,
                            f.quorate ? 'corosync quorate; the agent quorum decides (CLU-14)' : 'corosync Quorate: No, expected on a two-node HVM cluster: the agent quorum decides (CLU-14)',
                            'the agent quorum, not corosync', 'hpe', null, Tier.NONE,
                            'HPE: on 1.3 and 2.0 the agent quorum, not corosync, decides, and Quorate: No is expected on a two-node cluster. Read CLU-14. Built from HPE docs, not yet confirmed against a real two-node cluster.')
                } else {
                    add('CLU-03', 'Host sees a quorate cluster', Severity.CRITICAL, f.quorate ? Status.PASS : Status.FAIL, h.name, f.quorate ? 'quorate' : 'NOT quorate', 'quorate', 'upstream', null, Tier.NONE,
                            agentDecides && !f.quorate ? 'On 1.3 and 2.0 the agent quorum (CLU-14) is the operational authority, and Quorate: No is expected on two-node and stretch clusters, which are not told apart here: confirm against CLU-14 before acting.' : null)
                }
                if (f.totalVotes != null && f.expectedVotes != null) {
                    boolean full = f.totalVotes >= f.expectedVotes
                    boolean margin = f.quorumVotes == null || f.totalVotes > f.quorumVotes
                    if (twoNode) {
                        add('CLU-04', 'Quorum margin: a host can fail without losing quorum', Severity.HIGH, Status.NA, h.name,
                                "${f.totalVotes} of ${f.expectedVotes} votes, quorum ${f.quorumVotes}".toString(), 'the agent quorum and witness, not a corosync vote margin', 'hpe', null, Tier.NONE,
                                'A two-host HVM cluster has no corosync vote margin by design; the agent quorum and a Distributed Worker witness decide (CLU-14, CLU-12). Built from HPE docs, not yet confirmed against a real two-node cluster.')
                    } else {
                        add('CLU-04', 'Quorum margin: a host can fail without losing quorum', Severity.HIGH, full ? Status.PASS : (margin ? Status.WARN : Status.FAIL), h.name,
                                "${f.totalVotes} of ${f.expectedVotes} votes, quorum ${f.quorumVotes}".toString(), 'all expected votes present', 'upstream', null, Tier.NONE,
                                full ? null : ('A member is missing: find out which and why before any host work. One more failure at no margin stops every datastore.' + (agentDecides ? ' On 1.3 and 2.0 the agent quorum (CLU-14) is the authority; corosync votes only corroborate.' : '')))
                    }
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
                        ghost ? 'A node corosync still counts but Morpheus no longer manages. If it goes down or is rebuilt without leaving the cluster, every GFS2 datastore freezes until it is acknowledged as fenced. Remove it from the cluster properly before touching that node. Morpheus\'s own remove can leave this behind when GFS2 will not unmount, and once the record has gone HVM\'s agent may not acknowledge it either.' : null)
            }
            List<String> sigs = withConf.collect { Host h -> [h.facts.corosync['totem.config_version'], h.facts.nodelist.collect { "${it.nodeid}=${it.addr}" }.sort()].toString() }.unique()
            add('CLU-06', 'corosync.conf identical on every host', Severity.HIGH, sigs.size() == 1 ? Status.PASS : Status.FAIL, null,
                    sigs.size() == 1 ? "config_version ${withConf[0].facts.corosync['totem.config_version']}".toString() : "${sigs.size()} different versions: ${withConf.collect { "${it.name}=v${it.facts.corosync['totem.config_version']}" }.join(', ')}".toString(),
                    'one version', 'upstream')
            ghostMemberCheck(withConf)
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

    /**
     * CLU-16, sharpening CLU-06: a host whose corosync.conf config_version is below the others rejoined with a stale file. Built from corosync
     * source and a Proxmox reproduction (PR #828), not seen on HVM: such a node can leave a ghost CPG member that no membership change removes,
     * and nothing clears it until corosync restarts across the cluster. Compared from the file every host already reports, so it needs no new read.
     */
    private void ghostMemberCheck(List<Host> hs) {
        List<Map> vers = hs.collect { Host h -> [name: h.name, v: (h.facts.corosync['totem.config_version'] ==~ /\d+/ ? h.facts.corosync['totem.config_version'] as Integer : null)] }.findAll { it.v != null }
        if (vers.size() < 2) {
            return
        }
        int top = vers*.v.max() as int
        List<String> stale = vers.findAll { (it.v as Integer) < top }.collect { "${it.name}=v${it.v}".toString() }
        add('CLU-16', 'No host rejoined corosync with a stale config_version', Severity.HIGH, stale ? Status.WARN : Status.PASS, null,
                stale ? "${stale.join(', ')}, the rest at v${top}".toString() : "every host at config_version ${top}".toString(), 'all at the newest config_version', 'upstream', null, Tier.NONE,
                stale ? 'A host that was down during a host add or remove returns with an older corosync config_version, because Morpheus reloads only the hosts that were up. Built from corosync source and a Proxmox reproduction, not seen on HVM: the stale node can rejoin and leave a ghost CPG member that no membership change removes, and on that reproduction the cluster filesystem hung on every node until corosync was restarted everywhere. Do not start or restart it until its corosync.conf matches the others, which CLU-06 also flags; the plugin never restarts corosync. See docs/HPE-QUESTIONS.md.' : null)
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

    // ------------------------------------------------------------------ agent quorum (HPE's authority on 1.3 and 2.0)

    /**
     * HPE's agent quorum (https://127.0.0.1:7443/quorum) is the operational authority on layouts 1.3 and 2.0; corosync is only membership.
     * CLU-14 reads that endpoint; CLU-15 checks that the agent, corosync and Morpheus list the same members, which is the real split signal.
     * Both are built from HPE documentation, the payload's shape is only partly known, so anything unexpected reads NOT CHECKED, never PASS,
     * and neither has yet been confirmed against a real two-host or stretch cluster. Neither changes a host, so there is no fix.
     */
    private void agentQuorumChecks(List<Host> hs) {
        if (in_.layout?.hciCeph) {
            return
        }
        // only when the probe gathered the section at all: an older probe did not, and a result invented from nothing would be worse than none
        if (!hs.any { it.facts.sections.containsKey('agent_quorum') }) {
            return
        }
        String want = 'quorum achieved, every node reachable'
        hs.each { Host h ->
            HostFacts f = h.facts
            if (!f.sections.containsKey('agent_quorum')) {
                return
            }
            Map q = f.agentQuorum
            if (q == null || q.achieved == null) {
                add('CLU-14', 'Agent quorum achieved', Severity.CRITICAL, Status.NOT_CHECKED, h.name,
                        'the agent quorum endpoint (https://127.0.0.1:7443/quorum) gave nothing to read, or not the documented JSON', want, 'hpe', null, Tier.NONE,
                        'HPE\'s agent quorum is the authority on 1.3 and 2.0. Check the host agent is running and answering on 7443. Built from HPE docs: the payload shape is only partly known, so anything unexpected is read as not checked.')
                return
            }
            boolean down = q.achieved == false || q.fenced == true
            boolean unreachable = q.reachable != null && q.total != null && (q.reachable as int) < (q.total as int)
            Status s = down ? Status.FAIL : ((q.waitFencing == true || unreachable) ? Status.WARN : Status.PASS)
            List<String> obs = [q.achieved == false ? 'quorum NOT achieved' : 'quorum achieved']
            if (q.fenced == true) { obs << 'this host fenced' }
            if (q.waitFencing == true) { obs << 'waiting to fence a node' }
            if (q.reachable != null && q.total != null) { obs << "${q.reachable} of ${q.total} nodes reachable".toString() }
            if (q.corosyncActive == false) { obs << 'corosync not active' }
            if (q.dlmActive == false) { obs << 'dlm not active' }
            add('CLU-14', 'Agent quorum achieved', Severity.CRITICAL, s, h.name, obs.join(', '), want, 'hpe', null, Tier.NONE,
                    down ? 'HPE\'s agent quorum is the authority on 1.3 and 2.0: its loss leads a host to self-fence, stopping DLM and corosync. Corosync Quorate (CLU-03) does not decide this. DLM-01 and DLM-02 show a fence wait and its timing. Built from HPE docs, not yet confirmed against a real two-host or stretch cluster.'
                            : ((q.waitFencing == true || unreachable) ? 'A node is being fenced or is unreachable to the agent. Read DLM-01 for a fence wait and its timing, and CLU-15 for a member the agent has dropped. Built from HPE docs, not yet confirmed against a real two-host or stretch cluster.' : null))
        }
        threeWayAgreement(hs)
    }

    /**
     * CLU-15: the members the agent lists (/quorum and .quorum-nodes), the corosync nodelist and Morpheus's hosts should be the same set.
     * A corosync member the agent does not list is the precondition suspected behind the production freeze. Read only, no fix: HPE says not to
     * hand-edit these files. The agent file's format is undocumented, so when its tokens match nothing known this reads NOT CHECKED, not FAIL.
     */
    private void threeWayAgreement(List<Host> hs) {
        hs.each { Host h ->
            HostFacts f = h.facts
            if (!f.sections.containsKey('agent_quorum')) {
                return
            }
            String want = 'the same members in the agent, corosync and Morpheus'
            Set<String> agent = f.agentQuorumNodes as Set<String>
            if (!agent) {
                add('CLU-15', 'Agent, corosync and Morpheus list the same members', Severity.HIGH, Status.NOT_CHECKED, h.name,
                        'the agent listed no member data to compare', want, 'hpe', null, Tier.NONE,
                        'Compares /opt/morpheus-node/.quorum-nodes and the /quorum nodes with the corosync nodelist and Morpheus. Built from HPE docs; the file format is undocumented, and this has not been confirmed against a real host.')
                return
            }
            // only judge once the agent's identifiers line up with something known, or its scheme is one this plugin cannot read
            boolean recognised = f.nodelist.any { inAgent(it, null, agent) } || in_.hosts.any { inAgent(null, it, agent) }
            if (!recognised) {
                add('CLU-15', 'Agent, corosync and Morpheus list the same members', Severity.HIGH, Status.NOT_CHECKED, h.name,
                        "could not match the agent's member list (${agent.size()} entr${agent.size() == 1 ? 'y' : 'ies'}) to any known host", want, 'hpe', null, Tier.NONE,
                        'The agent lists members by identifiers this plugin could not line up with corosync or Morpheus, so it is not judged; the file format is undocumented. Built from HPE docs, not yet confirmed against a real host.')
                return
            }
            List<String> missing = f.nodelist.findAll { !inAgent(it, null, agent) }.collect { (it.name ?: it.addr ?: "node ${it.nodeid}") as String }.sort()
            add('CLU-15', 'Agent, corosync and Morpheus list the same members', Severity.HIGH, missing ? Status.FAIL : Status.PASS, h.name,
                    missing ? "in corosync but not the agent's quorum-node list: ${missing.join(', ')}".toString() : "${f.nodelist.size()} corosync node(s), all in the agent's list".toString(),
                    want, 'hpe', null, Tier.NONE,
                    missing ? 'A node corosync counts but the agent does not is the precondition suspected behind the production freeze: if that node fails, DLM waits for a fence the agent will never issue. Compare with CLU-05. HPE says not to hand-edit corosync or agent quorum files, so there is no fix: remove the node through Morpheus, or add it back cleanly. Built from HPE docs, the file format undocumented and not yet confirmed against a real freeze.' : null)
        }
    }

    /** Whether a corosync node or a Morpheus host is represented in the agent's lower-cased token set, by name, address or id. */
    private static boolean inAgent(Map node, Host host, Set<String> agent) {
        List<String> ids = []
        if (node != null) {
            if (node.name) { ids << (node.name as String) }
            if (node.addr) { ids << (node.addr as String) }
            if (node.nodeid != null) { ids << node.nodeid.toString() }
        }
        if (host != null) {
            if (host.name) { ids << host.name }
            if (host.ip) { ids << host.ip }
            if (host.facts?.hostname) { ids << host.facts.hostname }
        }
        ids.any { agent.contains(it.toLowerCase()) }
    }

    // ------------------------------------------------------------------ DLM

    private static final int AGENT_ACK_MINUTES = 3

    /** Names the wait state of a stopped lockspace for DLM-03, from dlm_tool ls (ringid, quorum, messages, fsdone). */
    private static String dlm03Hint(List<Map> stopped) {
        String s = stopped.collect { it.status as String }.findAll().join(' ')
        List<String> notes = []
        if (s.contains('wait ringid')) { notes << 'wait ringid: corosync and the lockspace are out of step, usually a membership change in progress' }
        if (s.contains('wait quorum')) { notes << 'wait quorum: this does not happen with HVM\'s dlm settings, so dlm.conf differs on this host' }
        if (s.contains('wait messages')) { notes << 'wait messages: a member never sent its start message, so look at a hung dlm_controld on a peer or a stale (ghost) member' }
        if (s.contains('wait fsdone')) { notes << 'wait fsdone: GFS2 has not finished, so read the kernel log' }
        notes ? notes.join('. ') + '.' : 'A lockspace is stopped for recovery. If it does not clear, read dlm_tool ls and the kernel log on this host.'
    }

    private void dlmChecks(List<Host> hs) {
        if (in_.layout?.hciCeph) {
            return
        }
        hs.each { Host h ->
            HostFacts f = h.facts
            if (!f.active('dlm') && !f.partial) {
                return
            }
            String fenceWanted = st.val('fencingEnabled') ? '1' : '0'
            add('DLM-04', 'dlm.conf enable_fencing', Severity.HIGH, f.dlmConf.enable_fencing == fenceWanted ? Status.PASS : Status.FAIL, h.name,
                    f.dlmConf.enable_fencing ?: 'unset', fenceWanted, st.source('fencingEnabled'), null, Tier.NONE,
                    f.dlmConf.enable_fencing == fenceWanted ? null : 'Do not turn fencing off to get past a freeze: GFS2 would recover while the failed node may still write, which corrupts the filesystem.')
            f.fenceWaits.each { Map w ->
                long mins = ((w.now as Long) - (w.since as Long)) / 60L as long
                String name = f.nodeNames[w.node as Integer] ?: "node ${w.node}"
                if (!f.frozen) {
                    add('DLM-06', 'dlm_controld is not fencing a departed node', Severity.LOW, Status.WARN, h.name,
                            "still retrying to fence ${name} (node ${w.node}), with every lockspace running".toString(), 'no fence requests', 'upstream', null, Tier.NONE,
                            'No GFS2 I/O is blocked now, but this is a latent freeze: the retry blocks the next membership change on that lockspace, so the next mount, unmount, new datastore, or host joining or leaving will freeze it until this clears. Do no host work on this cluster until it does. A fence result of 177 means no fence agent is loaded, so no fence was ever attempted, not that one failed. There is nothing to acknowledge unless a lockspace stops, which shows as DLM-01.')
                    return
                }
                add('DLM-01', 'Lockspaces waiting for a node to be fenced', Severity.CRITICAL, Status.FAIL, h.name,
                        "waiting on ${name} (node ${w.node}) for ${mins} min: all GFS2 I/O on this host is blocked${mins < AGENT_STOPS_VMS_MIN ? '. HVM\'s agent starts stopping VMs at about ' + AGENT_STOPS_VMS_MIN + ' min' : '. HVM\'s agent may have started stopping VMs'}".toString(), 'no fence waits', 'upstream', 'fence-ack:' + w.node, Tier.EMERGENCY,
                        'Every heartbeat and metadata change on GFS2 is frozen until the node is acknowledged as fenced. HVM\'s agent normally does this within about 2 minutes, so a wait that lasts longer will not clear by itself. If it runs on, the agent decides its datastores are dead and stops VMs: seen at about 9 minutes, and they power off the moment I/O returns. Only acknowledge once the node is certainly down: acknowledging a node that can still write corrupts the filesystem.')
                Map<Integer, Integer> acks = f.acks
                // the agent normally acknowledges within about 2 minutes, so before that there is nothing to say, and a host cut short may not have read its acknowledgements
                if (mins >= AGENT_ACK_MINUTES && !f.partial && !acks.containsKey(w.node as Integer)) {
                    String wrong = acks ? acks.collect { k, v -> "node ${k} x${v}" }.join(', ') : 'nothing'
                    add('DLM-02', 'HVM agent is acknowledging the failed node', Severity.CRITICAL, Status.FAIL, h.name,
                            "agent acked ${wrong} in the last 15 min, never node ${w.node}".toString(), "acks node ${w.node}".toString(), 'morpheus', null, Tier.NONE,
                            'The freeze will not clear by itself. Usually the node has gone from Morpheus while corosync still counts it (see CLU-05).')
                }
            }
            f.inferredWaits.each { Integer node ->
                String name = f.nodeNames[node] ?: "node ${node}"
                add('DLM-01', 'Lockspaces waiting for a node to be fenced', Severity.CRITICAL, Status.FAIL, h.name,
                        "waiting on ${name} (node ${node}), with no fence request showing: all GFS2 I/O on this host is blocked".toString(), 'no fence waits', 'upstream', 'fence-ack:' + node, Tier.EMERGENCY,
                        'Every heartbeat and metadata change on GFS2 is frozen. This kind has been seen when a host was deleted while its GFS2 mount was still starting, and it lasted 27 minutes. Acknowledging by hand was refused by DLM with -125 (no need_fencing); it cleared only after the node had been added back and gone down again, when the agent acknowledged it. Try the acknowledgement once the node is certainly down, and if it does not clear, involve HPE.')
            }
            // A lockspace waiting on a node the daemon records as departed but is not fencing: the -125 split state, which a fence_ack cannot clear
            f.divergedWaits.each { Integer node ->
                String name = f.nodeNames[node] ?: "node ${node}"
                add('DLM-07', 'Lockspace stuck in the -125 split state', Severity.CRITICAL, Status.FAIL, h.name,
                        "waiting to fence ${name} (node ${node}) while dlm_controld records it as departed and is not fencing it: all GFS2 I/O on this host is blocked".toString(), 'no stuck lockspaces', 'upstream', null, Tier.NONE,
                        'This is the freeze a single hand-run acknowledgement cannot clear: dlm_tool fence_ack is refused with -125 (no need_fencing), because the daemon has no fence pending for this node while the lockspace still waits on it. Read from dlm source and seen once in production for 27 minutes, not reproduced. The way out is a clean rejoin of the node: reboot it and start corosync and dlm with GFS2 left unmounted, and when its protocol is accepted the wait clears with no acknowledgement. Do not acknowledge in a loop, and never automatically. Involve HPE. 2.0 (dlm 4.3.0) behaviour here is unconfirmed.')
            }
            // DLM-01/DLM-07 speak for a stopped lockspace with a fence line, an inferred node or a diverged node; one stopped without any is this finding, or nothing would raise it
            if (!f.frozen || (!f.fenceWaits && !f.inferredWaits && !f.divergedWaits)) {
                List<Map> stopped = f.lockspaces.findAll { (it.flags as String)?.contains('kern_stop') || it.status }
                List<String> blocked = f.gfsBlock.findAll { String k, Integer v -> v == 1 }.keySet().sort() as List<String>
                Status s03 = stopped ? Status.FAIL : (blocked ? Status.WARN : Status.PASS)
                add('DLM-03', 'DLM lockspaces running', Severity.CRITICAL, s03, h.name,
                        stopped ? (stopped.collect { "${it.name}: ${it.status ?: it.flags}" }.join(', ') + (blocked ? "; locking blocked on ${blocked.join(', ')}".toString() : ''))
                                : (blocked ? "locking blocked on ${blocked.join(', ')} (lock_module/block=1), with no stopped lockspace in dlm_tool ls".toString() : "${f.lockspaces.size()} lockspace(s) running".toString()),
                        'running', 'upstream', null, Tier.NONE,
                        stopped ? dlm03Hint(stopped) : (blocked ? 'DFL_BLOCK_LOCKS is set: GFS2 locking is held here. Normal recovery sets it for a moment, so confirm it persists on the next check; if it stays set with no fence line, a lockspace is stopped in a way dlm_tool ls does not show.' : null))
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

    // ------------------------------------------------------------------ kernel freeze fixes

    /** Whether a running kernel ("6.8.0-139-generic") is at least a minimum ("6.8.0-100"); null when either will not parse. Compares major.minor.patch-abi. */
    static Boolean kernelAtLeast(String running, String min) {
        List<Integer> a = kernelRank(running), b = kernelRank(min)
        if (a == null || b == null) {
            return null
        }
        for (int i = 0; i < 4; i++) {
            if (a[i] != b[i]) {
                return a[i] > b[i]
            }
        }
        true
    }

    private static List<Integer> kernelRank(String v) {
        def m = (v ?: '') =~ /^(\d+)\.(\d+)\.(\d+)(?:[.-](\d+))?/
        m.find() ? [m.group(1) as Integer, m.group(2) as Integer, m.group(3) as Integer, (m.group(4) ?: '0') as Integer] : null
    }

    private void krnChecks(List<Host> hs) {
        if (in_.layout?.hciCeph) {
            return
        }
        String min = st.val('minKernel') as String
        hs.each { Host h ->
            HostFacts f = h.facts
            // KRN-04: the running kernel against a site-set floor that carries the DLM/GFS2 freeze fixes. Runs only where a floor is set for this layout
            if (min) {
                Boolean ok = kernelAtLeast(f.kernel, min)
                add('KRN-04', 'Kernel carries the DLM and GFS2 freeze fixes', Severity.MEDIUM, ok == null ? Status.NOT_CHECKED : (ok ? Status.PASS : Status.WARN), h.name,
                        ok == null ? "cannot read a version from kernel '${f.kernel ?: 'unknown'}' or the floor '${min}'".toString() : (f.kernel ?: 'unknown'), ">= ${min}".toString(), st.source('minKernel'), null, Tier.NONE,
                        ok == false ? 'This kernel is below the site floor minKernel, so it is missing fixes that matter to GFS2/DLM recovery. Even at or above the floor, Ubuntu\'s 6.8 kernels still lack the request-cancel (a431d49243a0), evict (e9e38ed7250f) and QUECVT (b6900ce15191) fixes that 7.0 carries, so raise a backport with HPE. The floor and these facts are read from the kernel changelog and source, not verified against HVM OS kernel naming: set minKernel per layout to match what HPE ships.' : null)
            }
            // KRN-05: HPE's documented -22 lock-conversion defect, from the kernel log. Only a host reboot onto a fixed kernel and a VM restart clears the guest impact
            boolean sinceBoot = f.dlmConvErrors && !(f.dlmConvErrorAt && f.boot && f.dlmConvErrorAt < f.boot)
            add('KRN-05', 'No DLM lock-conversion failures after recovery', sinceBoot ? Severity.HIGH : Severity.MEDIUM, f.dlmConvErrors == null ? Status.NOT_CHECKED : (sinceBoot ? Status.FAIL : (f.dlmConvErrors ? Status.WARN : Status.PASS)), h.name,
                    f.dlmConvErrors == null ? 'cannot tell: the kernel log could not be read' : (f.dlmConvErrors ? "${f.dlmConvErrors} in the kernel log over 7 days, last: ${f.dlmConvErrorLast}".toString() : 'none in 7 days'), 'none', 'hpe', null, Tier.NONE,
                    f.dlmConvErrors ? 'After a DLM recovery, downward lock conversions are rejected with EINVAL (-22) and GFS2 returns EIO to the guests, so VMs on this host can see I/O errors, read-only filesystems or a shut-down guest while storage looks healthy. A guest reboot does not clear it; HPE\'s procedure is to shut down the affected VMs, put the host in maintenance mode and reboot it. Present on Ubuntu 6.8 (not backported) and fixed on 7.0. From HPE\'s troubleshooting note, confirmed in kernel source; not yet seen on a host here. It clears 7 days after the last line.' : null)
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
            // a log that could not be read says nothing either way, and a finding already open stays open
            // before its last boot, the host has already been through what follows a withdrawal: worth knowing for 7 days, not critical for 7 days
                boolean sinceBoot = f.gfsErrors && !(f.gfsErrorAt && f.boot && f.gfsErrorAt < f.boot)
                add('GFS-10', 'No GFS2 consistency errors or withdrawals', sinceBoot ? Severity.CRITICAL : Severity.HIGH, f.gfsErrors == null ? Status.NOT_CHECKED : (sinceBoot ? Status.FAIL : (f.gfsErrors ? Status.WARN : Status.PASS)), h.name,
                    f.gfsErrors == null ? 'cannot tell: the kernel log could not be read' : (f.gfsErrors ? "${f.gfsErrors} in the kernel log over 7 days, last: ${f.gfsErrorLast}".toString() : 'none in 7 days'), 'none', 'upstream', null, Tier.NONE,
                    f.gfsErrors ? 'A reboot clears the live state, and HVM\'s agent reboots a host soon after a withdrawal, so this log is the evidence that remains. Run fsck.gfs2 -n with every host unmounted when you can, and raise it with HPE. It clears 7 days after the event.' : null)
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
                        ">= ${in_.hosts.size()} (hosts)".toString(), 'upstream', null, Tier.NONE, n >= in_.hosts.size() ? null : 'Morpheus runs gfs2_jadd itself when it adds a host, so too few journals usually means that add-host step did not finish, rather than something you add by hand. A host beyond the journal count cannot mount this filesystem. Older datastores may carry fewer than the 32 a current mkfs makes.')
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
                        missing ? "missing ${missing.join(', ')}".toString() : "${allTables.size()} mounted".toString(), 'same on every host', 'morpheus', null, Tier.NONE,
                        missing && h.maintenance ? 'Reboot this host before it leaves maintenance mode, which brings GFS2 back. Leaving without that has made the agent force a reboot.' : null)
            }
        }
        heartbeatChecks(hs)
    }

    private static final long HEARTBEAT_SETTLE_SEC = 300

    /**
     * After an add-back a file can be left from the host's earlier incarnation, or take a while to reappear: seen for about 4 minutes. Corosync's
     * uptime covers an add-back with or without a reboot. A file written since it started and then gone stale is a real finding, however new the host.
     */
    private static boolean justJoined(Host h, Map hb) {
        Long up = h.facts?.corosyncUpSec
        up != null && up < HEARTBEAT_SETTLE_SEC && h.facts.now && (!hb || (hb.ms as Long) < (h.facts.now - up) * 1000L)
    }

    private void heartbeatChecks(List<Host> hs) {
        HostFacts reader = hs*.facts.find { it.fsSafe && it.heartbeats }
        if (!reader) {
            boolean frozen = in_.hosts*.facts.any { it?.frozen }
            add('HB-01', 'Host heartbeats current', Severity.HIGH, frozen ? Status.NA : Status.WARN, null,
                    frozen ? 'not read: GFS2 is frozen, and reading it would add another stuck task' : 'no heartbeat files found', 'one fresh file per host', 'morpheus')
            return
        }
        long nowMs = (reader.now ?: 0L) * 1000L
        long maxAge = st.intVal('heartbeatMaxAgeSec') * 1000L
        // With "Automatically Power On VMs" off the agents stop writing these on every host. HA-03 reports that, and it is not a failure of each host.
        boolean off = in_.hosts.size() > 1 && powerOnState(hs) == false
        in_.hosts.each { Host h ->
            // After a re-add the host's name can differ from the one the agent writes: the system hostname gains a suffix the file does not have. A file can also linger on a datastore that was the target before, so the newest counts.
            Map hb = reader.heartbeats.findAll { Map b -> b.ms && [h.facts?.hostname, h.name].any { (b.host as String)?.equalsIgnoreCase(it as String) } }.max { it.ms as Long }
            if (h.maintenance) {
                // HVM removes a host's heartbeat file about 90 s into maintenance mode, and writes it again 40-60 s after leaving
                add('HB-01', 'Host heartbeat present', Severity.HIGH, Status.NA, h.name, 'host is in maintenance mode, which removes its heartbeat', 'one per host', 'morpheus')
            } else if (off) {
                add('HB-01', 'Host heartbeat current', Severity.HIGH, Status.NA, h.name, 'not judged: agents stop writing heartbeats while the cluster\'s power-on setting is off (HA-03)', 'one fresh file per host, with that setting on', 'morpheus')
            } else if (justJoined(h, hb)) {
                add('HB-01', 'Host heartbeat current', Severity.HIGH, Status.NA, h.name, "not judged yet: corosync started ${h.facts.corosyncUpSec} s ago, and this host has written no heartbeat since".toString(), 'one fresh file per host', 'morpheus')
            } else if (!hb) {
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
                            'Maintenance mode empties a host but does not take it out of the cluster. Powering it off now stalls every GFS2 datastore on the other hosts for about 2 minutes while HVM\'s agent acknowledges it, and freezes them if the agent does not. Remove it from the cluster first, with Remove safely in this tab: Morpheus\'s own Remove carries on if GFS2 will not unmount, and those checks do not run on it.')
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
                    // an ALUA standby path reports checker 'ghost' (tur sense 04/0b on Alletra/Nimble and Peer Persistence legs): healthy standby, not a fault
                    List<Map> standby = ps.findAll { it.checker == 'ghost' }
                    List<Map> bad = ps.findAll { !(it.state == 'active' && it.checker == 'ready') && it.checker != 'ghost' }
                    boolean noActive = !ps.any { it.state == 'active' && it.checker == 'ready' }
                    boolean failed = bad || !ps || noActive   // 0 paths, a genuinely failed path, or no active path at all (only standby left)
                    add('STO-01', "Multipath paths healthy: ${m.wwid}".toString(), Severity.HIGH, failed ? Status.FAIL : (ps.size() < 2 ? Status.WARN : Status.PASS), h.name,
                            "${ps.size() - bad.size()} of ${ps.size()} healthy${standby ? " (${standby.size()} standby)" : ''}".toString(), 'all healthy, at least 2', st.source('mpio'), failed ? 'multipath-reconfigure' : null, failed ? Tier.MAINTENANCE : Tier.NONE,
                            failed && !bad && standby ? 'Only ALUA standby paths remain: every path to the active controller is gone. Check the array and the storage network before one more failure leaves no path at all.' : null)
                    queueCheck(h, m)
                    expectedPathCheck(h, m)
                }
            }
            storageVendorChecks(h)
            // on an FC-only host the iSCSI error log says nothing, so it must not read as a clean pass: the FC fabric is STO-09
            if (f.fcPresent && f.sessions == 0) {
                add('STO-03', 'No iSCSI session errors', Severity.MEDIUM, Status.NOT_CHECKED, h.name, 'no iSCSI sessions on this host: its storage transport is Fibre Channel', 'n/a on an FC host', st.source('iscsiErrorsWarn'), null, Tier.NONE,
                        'This host uses Fibre Channel, not iSCSI, so the iSCSI error count says nothing either way. Its fabric health is STO-09.')
            } else {
                add('STO-03', 'No iSCSI session errors', Severity.MEDIUM, f.errors24h >= st.intVal('iscsiErrorsWarn') ? Status.WARN : Status.PASS, h.name, "${f.errors24h} in 24 h".toString(), '0', st.source('iscsiErrorsWarn'))
            }
            // multipathd looks after FC and iSCSI maps alike, so it is judged wherever maps exist; iscsid only where there are iSCSI sessions
            if (f.maps) {
                boolean mpOk = f.active('multipathd')
                boolean needIscsid = f.sessions > 0
                boolean iscOk = !needIscsid || f.active('iscsid')
                add('STO-04', 'Storage path daemons running', Severity.HIGH, mpOk && iscOk ? Status.PASS : Status.FAIL, h.name,
                        "multipathd ${f.service('multipathd').active ?: '?'}${needIscsid ? ", iscsid ${f.service('iscsid').active ?: '?'}" : ' (no iSCSI sessions, so iscsid is not needed)'}".toString(), 'active', 'morpheus', null, Tier.NONE,
                        mpOk && iscOk ? null : 'These daemons look after the paths under every GFS2 datastore on this host. Find out why one stopped.')
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

    /**
     * The no_path_retry window, in seconds, for a map: how long a host holds I/O with all paths gone before it fails it. Vendors
     * differ by an order of magnitude and none ties a value to GFS2, so this is policy, never a vendor pass or fail: it never
     * FAILs. It WARNs only when the window is under the corosync token (a brief all-paths event could withdraw GFS2 before a
     * controller failover completes) or over the site ceiling (noPathRetryPolicy; held writes land after the host is declared
     * dead). It reports the effective seconds and the vendor's documented value. Two misreads are fixed: "N sec" means the map
     * has no paths right now (a countdown), and "off" on a map with no active path means its retries are exhausted.
     */
    private void queueCheck(Host h, Map m) {
        String title = "no_path_retry window is safe: ${m.wwid}".toString()
        int ceiling = st.intVal('noPathRetryPolicy')
        String expected = "within the corosync token and the ${ceiling} s site ceiling".toString()
        String q = (m.queueing as String)?.trim()
        String features = m.features as String
        String vn = StorageProfiles.nprText(StorageProfiles.forMap(m.vendor as String))
        String vendorNote = vn ? "; ${vn}".toString() : ''
        int activeReady = (m.paths as List<Map>).count { it.state == 'active' && it.checker == 'ready' }
        Integer tokenMs = st.val('corosyncToken') as Integer
        Integer tokenSec = tokenMs == null ? null : (tokenMs / 1000) as Integer
        int polling = h.facts.mpPolling ?: 5
        if (q == 'on') {
            add('STO-02', title, Severity.HIGH, Status.WARN, h.name, "queueing on, with no limit${vendorNote}".toString(), expected, 'upstream', null, Tier.NONE,
                    'Queueing for ever hangs GFS2 on an all-paths event instead of failing the I/O, so a withdraw never clears by itself. Set a finite no_path_retry in the multipath configuration, in a maintenance window.')
            return
        }
        def chk = q =~ /^(\d+)\s*chk$/
        def sec = q =~ /^(\d+)\s*sec$/
        if (chk.find()) {
            int n = chk.group(1) as int
            int window = n * polling + 1
            String why = tokenSec != null && window < tokenSec ? "under the ${tokenSec} s corosync token, so a brief all-paths event could withdraw GFS2 before a controller failover completes" :
                    (window > ceiling ? "over the ${ceiling} s site ceiling, so held writes could land after this host has been declared dead" : null)
            add('STO-02', title, Severity.HIGH, why ? Status.WARN : Status.PASS, h.name, "no_path_retry ${n}, about ${window} s at polling ${polling}${vendorNote}".toString(), expected, 'upstream', null, Tier.NONE,
                    why ? "The ${window} s window is ${why}. This is site policy (noPathRetryPolicy), not a vendor rule: raise it with HPE and the array vendor.".toString() : null)
            return
        }
        if (sec.find()) {
            add('STO-02', title, Severity.HIGH, Status.WARN, h.name, "no paths on this map right now: ${sec.group(1)} s left before I/O fails${vendorNote}".toString(), expected, 'upstream', null, Tier.NONE,
                    'multipathd is counting down its retries because the map has no path at this moment. See STO-01 for the paths now, and STO-13 for whether a map has lost every path before.')
            return
        }
        if (q == 'off') {
            if (activeReady == 0) {
                add('STO-02', title, Severity.HIGH, Status.WARN, h.name, "no_path_retry off with no active path: I/O is failing now${vendorNote}".toString(), expected, 'upstream', null, Tier.NONE,
                        'Either no_path_retry is off, or a finite window has run its retries out with no path left. See STO-01.')
            } else {
                add('STO-02', title, Severity.HIGH, tokenSec != null ? Status.WARN : Status.PASS, h.name, "no_path_retry off: fails I/O at once, a 0 s window${vendorNote}".toString(), expected, 'upstream', null, Tier.NONE,
                        tokenSec != null ? "A 0 s window is under the ${tokenSec} s corosync token, so a brief all-paths event withdraws GFS2 at once. This is often the vendor's own value: raise it with HPE before changing it.".toString() : null)
            }
            return
        }
        // q is '-' (undefined) or absent; dm's features line reads queue_if_no_path for a finite retry too, so it cannot answer alone
        if (features?.contains('queue_if_no_path')) {
            add('STO-02', title, Severity.HIGH, Status.NOT_CHECKED, h.name, "cannot tell: features '${features}' shows queueing, and multipathd gave no retry limit".toString(), expected, 'upstream', null, Tier.NONE,
                    'Run multipathd show maps format "%n %Q" on the host: "on" queues for ever, a number of checks or seconds does not.')
        } else {
            add('STO-02', title, Severity.HIGH, Status.PASS, h.name, "not queueing${vendorNote}".toString(), expected, 'upstream')
        }
    }

    /** Below the site standard expectedPaths for one LUN on one host: a design often wants 4, and fewer is less redundancy. Unset by default and overridable, as test environments run fewer. */
    private void expectedPathCheck(Host h, Map m) {
        Integer want = st.val('expectedPaths') as Integer
        if (!want) {
            return
        }
        int n = (m.paths as List).size()
        add('STO-12', "Enough storage paths: ${m.wwid}".toString(), Severity.MEDIUM, n < want ? Status.WARN : Status.PASS, h.name,
                "${n} path(s)".toString(), ">= ${want}".toString(), st.source('expectedPaths'), null, Tier.NONE,
                n < want ? "Fewer paths than the site design (${want}). STO-05 compares across hosts; this is the absolute floor. Set expectedPaths to your design, or lower it where fewer is deliberate.".toString() : null)
    }

    /**
     * Array identity per map, from its trimmed INQUIRY vendor and product (StorageProfiles). A recognised family PASSes and
     * carries its documented values to the other storage checks; an unknown vendor is NOT CHECKED, never PASS, so a
     * vendor-specific rule never judges it. The transport is named. Target-port-group detail needs multipathd's %A, which
     * exists only on 0.14.3 (26.04), so on 24.04 that part reads as not read rather than erroring.
     */
    private void storageVendorChecks(Host h) {
        HostFacts f = h.facts
        f.maps.each { Map m ->
            StorageProfiles.Profile pr = StorageProfiles.forMap(m.vendor as String)
            String vp = (m.vendor as String)?.replace(',', ' ') ?: 'unknown'
            String transport = m.transport ?: (f.fcPresent && f.sessions == 0 ? 'fibre channel (host)' : (f.sessions ? 'iscsi (host)' : 'unknown'))
            String tpg = tpgNote(f, m)
            if (pr) {
                add('STO-08', "Storage array recognised: ${m.wwid}".toString(), Severity.LOW, Status.PASS, h.name,
                        "${vp} = ${pr.family}; transport ${transport}${tpg}".toString(), 'a known vendor profile', 'plugin', null, Tier.NONE,
                        'Built from documentation, not seen live. The vendor/product pair gives a family, not a model.')
            } else {
                add('STO-08', "Storage array recognised: ${m.wwid}".toString(), Severity.LOW, Status.NOT_CHECKED, h.name,
                        "unknown vendor ${vp}; transport ${transport}${tpg}".toString(), 'a known vendor profile', 'plugin', null, Tier.NONE,
                        'No profile for this vendor, so its vendor-specific items are not checked and its values are shown, never judged against an absolute. The universal path, queue and daemon checks still run.')
            }
        }
    }

    /** Distinct ALUA target port groups a map's paths sit in, from multipathd's %A, which is readable only on 0.14.3 (26.04). */
    private static String tpgNote(HostFacts f, Map m) {
        if (f.mpathPathsExt == null) {
            return ''
        }
        if (!f.mpathPathsExt) {
            return '; target port groups not read (needs multipath-tools 0.14.3 on Ubuntu 26.04)'
        }
        List<String> tpgs = f.mpathPaths.findAll { (it.wwid as String)?.equalsIgnoreCase(m.wwid as String) && it.tpg }*.tpg.unique() as List<String>
        tpgs ? "; ${tpgs.size()} target port group(s)".toString() : ''
    }

    /**
     * Fibre Channel fabric health, read-only from sysfs. Each fc_host should be Online, at least one remote port a target and
     * Online, and two adapters give redundancy. Link error counters are cumulative and rise on switch reboots and zoning
     * changes, so they are shown, not judged. Built from documentation: no FC host has been seen, as the test hosts are iSCSI.
     */
    private void fibreChannelChecks(List<Host> hs) {
        hs.findAll { it.facts.fcPresent && it.facts.fcHosts }.each { Host h ->
            HostFacts f = h.facts
            List<Map> online = f.fcHosts.findAll { (it.portState as String) =~ /(?i)online/ }
            List<Map> targets = f.fcRports.findAll { (it.roles as String) =~ /(?i)target/ && (it.portState as String) =~ /(?i)online/ }
            long errs = (f.fcHosts.sum { (it.linkFailures as long) + (it.lossOfSync as long) + (it.lossOfSignal as long) + (it.invalidCrc as long) } ?: 0L) as long
            Status s = !online || (f.fcRports && !targets) ? Status.FAIL : (online.size() < 2 ? Status.WARN : Status.PASS)
            add('STO-09', 'Fibre Channel fabric healthy', Severity.HIGH, s, h.name,
                    "${online.size()} of ${f.fcHosts.size()} FC port(s) Online, ${targets.size()} target rport(s) up, ${errs} link error(s) cumulative".toString(),
                    'every port Online, a target up, two adapters', 'plugin', null, Tier.NONE,
                    s == Status.FAIL ? 'A Fibre Channel port is down, or no remote target port is Online, so paths may be lost. Check the HBA, optic, zoning and fabric. Built from documentation, not seen live.'
                            : (s == Status.WARN ? 'Only one Fibre Channel adapter is Online, so one optic or HBA failing takes every path. Built from documentation, not seen live.'
                            : 'Built from documentation, not seen live. Link error counters are cumulative and shown, not judged.'))
        }
    }

    /** iSCSI boot login (node.startup) and the live recovery_tmo each session holds. Both apply only to iSCSI hosts; an FC host has neither section. */
    private void iscsiTransportChecks(List<Host> hs) {
        hs.each { Host h ->
            HostFacts f = h.facts
            if (f.iscsiNodes) {
                List<Map> notAuto = f.iscsiNodes.findAll { it.startup != 'automatic' }
                add('STO-10', 'iSCSI targets log in at boot', Severity.MEDIUM, notAuto ? Status.WARN : Status.PASS, h.name,
                        notAuto ? "node.startup not automatic for ${notAuto.size()} of ${f.iscsiNodes.size()} target(s)".toString() : "${f.iscsiNodes.size()} target(s) automatic".toString(),
                        'node.startup automatic', 'plugin', null, Tier.NONE,
                        notAuto ? 'A rebooted host returns with no iSCSI session to these targets, so their GFS2 datastores do not mount. Set node.startup to automatic; the open-iscsi default is manual.' : null)
            }
            if (f.iscsiTmo) {
                List<Integer> vals = f.iscsiTmo*.recoveryTmo.findAll { it != null }.unique().sort() as List<Integer>
                Integer fromConfig = f.mpFastIoFail
                String vendorExp = vendorRecoveryTmo(f)
                boolean offConfig = fromConfig != null && vals.any { it != fromConfig }
                boolean offVendor = !offConfig && vendorExp && vals.any { "${it}".toString() != vendorExp }
                add('STO-11', 'iSCSI path recovery timeout', Severity.MEDIUM, offConfig || offVendor ? Status.WARN : Status.PASS, h.name,
                        "recovery_tmo ${vals.join(', ')} s${fromConfig != null ? "; multipathd fast_io_fail_tmo ${fromConfig} s" : ''}".toString(),
                        fromConfig != null ? "all sessions at ${fromConfig} s" : 'consistent across hosts', 'plugin', null, Tier.NONE,
                        offConfig ? "A session's recovery_tmo is not multipathd's fast_io_fail_tmo (${fromConfig} s). A value of 120 means multipathd has not applied its setting, usually a path not in a map; the path then holds I/O far longer than intended."
                                : (offVendor ? "recovery_tmo differs from the ${vendorExp} s this vendor documents. Built from documentation and not yet confirmed on a host, so this is unverified." : null))
            }
        }
        Map<String, List<Integer>> perHost = hs.findAll { it.facts.iscsiTmo }.collectEntries { [(it.name): it.facts.iscsiTmo*.recoveryTmo.findAll { v -> v != null }.unique().sort()] }
        if (perHost.size() > 1 && (perHost.values() as Set).size() > 1) {
            add('STO-11', 'iSCSI path recovery timeout agrees across hosts', Severity.MEDIUM, Status.WARN, null,
                    perHost.collect { k, v -> "${k}=${v.join('/')}" }.join(', '), 'same on every host', 'plugin', null, Tier.NONE,
                    'Hosts hold I/O for different times during one failover. Bring their multipath fast_io_fail_tmo into line.')
        }
    }

    /** The recovery_tmo a host's recognised vendor maps document, as a string; null when no known vendor, several vendors, or none stated. */
    private static String vendorRecoveryTmo(HostFacts f) {
        List<String> exp = f.maps.collect { StorageProfiles.forMap(it.vendor as String)?.recoveryTmo }.findAll { it != null }*.toString().unique() as List<String>
        exp.size() == 1 ? exp[0] : null
    }

    /** multipathd's 7-day log of a map losing every path: "remaining active paths: 0" or "Entering recovery mode". After the fact this is the key near-miss, kept in a storage section rather than @@gfs2. */
    private void allPathsLostChecks(List<Host> hs) {
        hs.findAll { it.facts.sections.containsKey('mpath_log') }.each { Host h ->
            Integer n = h.facts.pathsLost7d
            add('STO-13', 'No all-paths-lost events in the storage log', Severity.HIGH, n == null ? Status.NOT_CHECKED : (n ? Status.FAIL : Status.PASS), h.name,
                    n == null ? 'cannot tell: the multipathd log could not be read' : (n ? "${n} sighting(s) over 7 days, last: ${h.facts.pathsLostLast}".toString() : 'none in 7 days'),
                    'none', 'upstream', null, Tier.NONE,
                    n ? 'A map ran out of paths: on a GFS2 map that is a withdraw, and the moment before an all-paths-down outage. Find what took the paths, an array, a controller or a fabric, before it happens again. It clears 7 days after the last sighting.' : null)
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
                add('NET-16', 'Cluster network address is static', Severity.HIGH, f.ringDynamic ? Status.WARN : Status.PASS, h.name,
                        f.ringDynamic ? "${f.ringAddr} on ${f.ringDev} came from DHCP".toString() : "${f.ringAddr ?: 'address'} on ${f.ringDev}".toString(), 'static', 'plugin', null, Tier.NONE,
                        f.ringDynamic ? 'A lost or changed lease takes this host out of the cluster, because corosync.conf still lists the old address, and another machine can then be given it. Seen after a NIC rename changed the DHCP client id. Make it static or reserve it.' : null)
                add('NET-02', 'Corosync does not share an interface with iSCSI', Severity.MEDIUM, shared ? Status.WARN : Status.PASS, h.name,
                        shared ? "${f.ringDev} also carries ${shared.join(', ')}".toString() : "corosync on ${f.ringDev}".toString(), 'separate', 'plugin', null, Tier.NONE,
                        shared ? 'Storage traffic can delay corosync messages enough to get a host evicted.' : null)
                List<String> ringPorts = f.devPorts[f.ringDev] ?: []
                List<Map> portals = f.portalDevs.findAll { it.dev }
                if (portals) {
                    List<String> iscsiPorts = portals.collectMany { f.devPorts[it.dev] ?: [] }.unique() as List<String>
                    List<String> both = ringPorts.intersect(iscsiPorts) as List<String>
                    // different interface names can sit on one bond, which NET-02 cannot see
                    add('NET-15', 'iSCSI has physical ports of its own', Severity.MEDIUM, !ringPorts || !iscsiPorts ? Status.NOT_CHECKED : (both ? Status.WARN : Status.PASS), h.name,
                            !ringPorts || !iscsiPorts ? 'cannot tell which physical ports carry them' : (both ? "${both.join(', ')} carry both iSCSI and the cluster network".toString() : "iSCSI on ${iscsiPorts.join(', ')}, cluster network on ${ringPorts.join(', ')}".toString()),
                            'separate physical ports', 'plugin', null, Tier.NONE,
                            both ? 'A burst of storage traffic can delay corosync on the shared ports, and losing them takes both away at once. Where this is deliberate, as in a test environment, list NET-15 in disabledChecks in the site standards.' : null)
                }
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
        // Each host's widest address only: a /32 VIP inside a host's /24 is not a different view of that network.
        Closure<List<Map>> widest = { List<Map> ms -> ms.groupBy { it.host }.values().collect { List<Map> v -> v.min { it.prefix as int } } }
        List<String> drift = groups.collect { widest(it.members as List<Map>) }.findAll { (it*.prefix as Set).size() > 1 }.collect { List<Map> w -> w*.text.sort().join(', ') }
        // the same interface name with different netmasks, where the subnets do not even overlap
        all.groupBy { it.dev }.each { dev, List<Map> all_ ->
            List<Map> members = widest(all_)
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
     *
     * Servers are matched by address, as the members of a pool are different clocks under one name. Only hosts joined by a
     * shared server have been compared: groups says which, and the spread is the widest within any group, never across them.
     */
    static Map hostDrift(List<Host> hs) {
        Map<String, Map<String, BigDecimal>> byServer = [:]
        hs.each { Host h ->
            h.facts.timeSamples.each { Map s ->
                byServer.computeIfAbsent(s.addr as String) { [:] }[h.name] = s.offsetMs as BigDecimal
            }
        }
        Map<String, Map<String, BigDecimal>> shared = byServer.findAll { k, v -> v.size() > 1 }
        if (!shared) {
            return null
        }
        Map<String, List<BigDecimal>> deviations = [:]
        List<Set<String>> groups = []
        shared.values().each { Map<String, BigDecimal> offs ->
            BigDecimal mean = offs.values().sum() / offs.size()
            offs.each { String h, BigDecimal o -> deviations.computeIfAbsent(h) { [] } << (o - mean) }
            Set<String> joined = new TreeSet<String>(offs.keySet())
            List<Set<String>> touching = groups.findAll { !it.disjoint(joined) }
            touching.each { joined.addAll(it) }
            groups = (groups - touching) + [joined]
        }
        Map<String, BigDecimal> perHost = deviations.collectEntries { String h, List<BigDecimal> d -> [(h): d.sort()[d.size() / 2 as int]] }
        List<BigDecimal> spreads = groups.collect { Set<String> g -> List<BigDecimal> v = g.collect { perHost[it] }; v.max() - v.min() }
        [perHost: perHost, spreadMs: spreads.max(), servers: shared.size(), groups: groups,
         sampled: hs.any { it.name in perHost.keySet() && it.facts.timeDaemon == 'timesyncd' }]
    }

    /** What the comparison of clocks cannot say, when some hosts read other servers than the rest. Null when every host was compared. */
    private static String driftLimits(List<Host> hs, Map drift) {
        Collection<String> compared = drift ? (drift.perHost as Map).keySet() : []
        List<Host> apart = hs.findAll { !(it.name in compared) }
        List<String> says = []
        if (drift && (drift.groups as List).size() > 1) {
            says << "Compared only within ${(drift.groups as List<Set<String>>).collect { it.join(', ') }.join('; and within ')}: nothing has measured one group against another.".toString()
        }
        if (hs.size() > 1 && apart) {
            String own = apart.collect { Host h ->
                Map s = h.facts.timeSamples.find { it.selected } ?: (h.facts.timeSamples ? h.facts.timeSamples[0] : null)
                s ? "${h.name} is ${(s.offsetMs as BigDecimal).abs().setScale(1, BigDecimal.ROUND_HALF_UP)} ms from ${s.addr}" : "${h.name} has no reading"
            }.join(', ')
            says << "Not compared to the millisecond, because no other host reads the same server: ${own}. Each is only known to be that far from its own server, and those servers can differ, so this shows neither drift nor agreement.".toString()
        }
        says ? says.join(' ') : null
    }

    /** For the tab's time panel: what is defined for the cluster, and what each host really does. */
    static Map timeView(Input in_) {
        List<Host> hs = in_.hosts.findAll { it.facts?.complete }
        List<String> wanted = (in_.standards.val('ntpServers') ?: []) as List<String>
        Map drift = hostDrift(hs)
        [defined: wanted, definedBy: wanted ? in_.standards.source('ntpServers') : null, driftMs: drift ? (drift.spreadMs as BigDecimal).setScale(1, BigDecimal.ROUND_HALF_UP) : null, driftServers: drift?.servers,
         driftNote: driftLimits(hs, drift),
         hosts: hs.collect { Host h ->
             Map c = h.facts.chrony
             Map t = h.facts.timesyncd
             [name: h.name, daemon: h.facts.timeDaemon, state: timeState(h.facts, wanted), configured: h.facts.timeServers, following: h.facts.timeFollowing ?: c?.source,
              synced: h.facts.ntpSynced, offsetMs: (c || t?.offsetMs != null) ? ((c ? c.offsetMs : t.offsetMs) as BigDecimal).abs().setScale(1, BigDecimal.ROUND_HALF_UP) : null, stratum: c ? c.stratum : t?.stratum,
              driftMs: drift?.perHost?.get(h.name) != null ? (drift.perHost[h.name] as BigDecimal).setScale(1, BigDecimal.ROUND_HALF_UP) : null]
         }]
    }

    /**
     * TIM-03 on a host kept by systemd-timesyncd. It reports one sample from its last poll, up to half an hour old and already
     * corrected since, so a large offset warns and only a host nothing has answered fails.
     */
    private void timesyncdSyncCheck(Host h, BigDecimal maxOffset) {
        Map t = h.facts.timesyncd
        String title = 'In sync with a time source'
        String expected = "<= ${maxOffset} ms, a server answering".toString()
        String source = st.source('maxNtpOffsetMs')
        if (!t.addr && t.packets == null) {
            add('TIM-03', title, Severity.HIGH, Status.NOT_CHECKED, h.name, 'cannot tell: systemd-timesyncd is running and reported no status', expected, source)
        } else if (!t.addr || !t.packets) {
            add('TIM-03', title, Severity.HIGH, Status.FAIL, h.name, "no time server has answered systemd-timesyncd (configured ${h.facts.timeServers.join(', ') ?: 'none'})".toString(), expected, source)
        } else if (t.offsetMs == null) {
            add('TIM-03', title, Severity.HIGH, Status.NOT_CHECKED, h.name, "cannot tell: systemd-timesyncd reads ${t.addr} and reported no offset".toString(), expected, source)
        } else {
            BigDecimal off = (t.offsetMs as BigDecimal).abs().setScale(1, BigDecimal.ROUND_HALF_UP)
            boolean leapOk = !t.leap || t.leap == 'normal'
            Status s = !leapOk ? Status.FAIL : (off > maxOffset ? Status.WARN : Status.PASS)
            add('TIM-03', title, Severity.HIGH, s, h.name, "${off} ms from ${t.name ? t.name + ' (' + t.addr + ')' : t.addr} at its last poll, stratum ${t.stratum ?: '?'}, ${t.leap ?: 'leap unknown'}".toString(), expected, source, null, Tier.NONE,
                    s == Status.WARN ? 'systemd-timesyncd corrects each sample as it takes it, so see whether this repeats on the next check.' : null)
        }
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
            } else if (h.facts.timesyncd) {
                timesyncdSyncCheck(h, maxOffset)
            }
            String state = timeState(h.facts, wanted)
            String daemon = h.facts.timesyncd ? 'systemd-timesyncd' : 'chrony'
            String what = "${state}: following ${h.facts.timeFollowing ?: c?.source ?: 'nothing'}${h.facts.timesyncd ? ' via systemd-timesyncd' : ''}; configured ${h.facts.timeServers.join(', ') ?: 'none'}".toString()
            if (wanted) {
                boolean listed = wanted.any { it in h.facts.timeServers }
                add('TIM-04', 'Host uses the cluster time source', Severity.MEDIUM, state == 'cluster source' ? Status.PASS : (listed ? Status.WARN : Status.FAIL), h.name, what, wanted.join(', '), st.source('ntpServers'),
                        listed ? null : 'ntp-sources', listed ? Tier.NONE : Tier.MAINTENANCE,
                        listed && state != 'cluster source' ? "Configured, but ${daemon} is following another server. Check this host can reach the cluster source.".toString() : null)
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
        String limits = driftLimits(hs, drift)
        List<Long> times = hs*.facts*.now.findAll()
        long skew = times.size() > 1 ? times.max() - times.min() : 0L
        int maxSkew = st.intVal('maxClockSkewSec')
        if (drift) {
            BigDecimal max = st.val('maxHostDriftMs') as BigDecimal
            BigDecimal spread = (drift.spreadMs as BigDecimal).setScale(1, BigDecimal.ROUND_HALF_UP)
            boolean over = spread > max
            // systemd-timesyncd gives one sample per host, taken at different times and corrected since: enough to warn on, not to fail a cluster on
            Status s = over ? (drift.sampled ? Status.WARN : Status.FAIL) : (limits && skew > maxSkew ? Status.WARN : Status.PASS)
            String overHint = over ? 'Live migration moves a running clock between hosts, and corosync and GFS2 logs stop lining up. Find the host that differs in the Time section.' + (drift.sampled ? ' These are single samples from each host\'s last poll, so see whether it repeats on the next check.' : '') : null
            add('TIM-02', 'Host clocks agree', Severity.HIGH, s, null, "${spread} ms apart, measured against ${drift.servers} shared time server(s)${limits ? "; ${skew} s across all hosts (coarse)" : ''}".toString(), "<= ${max} ms".toString(), st.source('maxHostDriftMs'), null, Tier.NONE,
                    [overHint, limits].findAll().join(' ') ?: null)
        } else if (times.size() > 1) {
            add('TIM-02', 'Host clocks agree', Severity.MEDIUM, skew > maxSkew ? Status.WARN : Status.PASS, null, "${skew} s apart (coarse: these hosts share no time server to compare against)".toString(), "<= ${maxSkew} s".toString(), st.source('maxClockSkewSec'), null, Tier.NONE, limits)
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
            List<Map> noAgent = h.facts.peers.findAll { it.agent == false }
            if (h.facts.peers.any { it.ssh != null || it.agent != null }) {
                List<String> closed = []
                if (noSsh) { closed << "TCP 22 closed to ${noSsh*.addr.join(', ')}".toString() }
                if (noAgent) { closed << "TCP 7443 closed to ${noAgent*.addr.join(', ')}".toString() }
                add('MIG-02', 'Reaches every peer on the migration port', Severity.HIGH, closed ? Status.FAIL : Status.PASS, h.name,
                        closed ? closed.join('; ') : "${h.facts.peers.size()} peer(s)".toString(), 'TCP 22 and 7443 between hosts', 'hpe', null, Tier.NONE,
                        closed ? 'HPE requires TCP 22 (hosts migrate over SSH) and TCP 7443 (the agent\'s heartbeat and quorum pings) between every host. A blocked 7443 makes a peer count as unreachable to the agent. Open them on the host and on whatever is between them.' : null)
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

    /** A note, never a finding: Morpheus has set this on every cluster seen so far and what it means is unknown. See BACKLOG.md. */
    private void haChecks(List<Host> hs) {
        Object flag = in_.clusterConfig?.notHighAvailability
        if (flag != null) {
            add('HA-01', 'Morpheus notHighAvailability flag (for information)', Severity.LOW, Status.NA, null, "cluster config has notHighAvailability=${flag}".toString(), 'meaning not known yet', 'morpheus', null, Tier.NONE,
                    'HPE does not document this flag, and Morpheus has set it on every cluster seen so far, so it says nothing about this cluster on its own.')
        }
    }

    // ------------------------------------------------------------------ capacity

    /** N+1: if the largest host fails, can the rest hold every running VM? Memory is the limit that stops a restart. */
    private void capacityChecks() {
        Map m = memoryHeadroom(in_)
        if (!m) {
            return
        }
        long running = m.running as long, survivors = m.survivors as long
        long gib = 1L << 30
        add('CAP-01', 'VMs fit on the remaining hosts if the largest fails', Severity.MEDIUM, running > survivors ? Status.WARN : Status.PASS, null,
                "${(running / gib) as long} GiB of running VMs, ${(survivors / gib) as long} GiB left without the largest host".toString(),
                'running VMs fit', 'plugin', null, Tier.NONE, running > survivors ? 'Automatic failover could not restart every VM: some tenants would stay down until a host returned.' : null)
    }

    /** Memory of the running VMs, and what the hosts have without the largest. Null with fewer than 2 hosts, or a host of unknown size. */
    static Map memoryHeadroom(Input input) {
        List<Host> sized = input.hosts.findAll { it.maxMemory }
        if (sized.size() != input.hosts.size() || sized.size() < 2) {
            return null
        }
        long running = input.morpheusVms.findAll { it.power == 'on' }.sum { (it.memory ?: 0L) as Long } as Long ?: 0L
        long total = sized.sum { it.maxMemory } as Long
        [running: running, survivors: total - (sized*.maxMemory.max() as Long)]
    }

    /**
     * vCPUs of the running VMs against the hosts' threads, or why that cannot be said: a host that did not answer, or a running VM with no size
     * in Morpheus, would understate one side of it and read as room to spare.
     */
    static Map vcpuLoad(Input input) {
        List<Host> unread = input.hosts.findAll { !it.facts?.complete || !it.facts.nproc }
        if (!input.hosts || unread) {
            return [why: input.hosts ? "no thread count from ${unread*.name.join(', ')}".toString() : 'no hosts']
        }
        List<Map> on = input.morpheusVms.findAll { it.power == 'on' }
        int unsized = on.count { !it.cores }
        if (unsized) {
            return [why: "${unsized} running VM(s) have no vCPU count in Morpheus".toString()]
        }
        int seen = input.hosts.sum(0) { Host h -> h.facts.vms.count { it.state == 'running' } } as int
        if (!on && seen) {
            return [why: "Morpheus lists no running VMs, and the hosts report ${seen}".toString()]
        }
        [vcpus: on.sum(0L) { it.cores as long } as long, threads: input.hosts.sum(0L) { Host h -> h.facts.nproc as long } as long]
    }

    private void overcommitCheck() {
        Map load = vcpuLoad(in_)
        BigDecimal warn = st.val('vcpuWarnRatio') as BigDecimal, fail = st.val('vcpuFailRatio') as BigDecimal
        String title = 'vCPUs against host threads'
        if (load.why) {
            add('CAP-02', title, Severity.MEDIUM, Status.NOT_CHECKED, null, "cannot tell: ${load.why}".toString(), "under ${warn} vCPUs per thread".toString(), st.source('vcpuWarnRatio'))
            return
        }
        BigDecimal ratio = ((load.vcpus as long) / (load.threads as long)).setScale(2, java.math.RoundingMode.HALF_UP)
        add('CAP-02', title, Severity.MEDIUM, ratio >= fail ? Status.FAIL : (ratio >= warn ? Status.WARN : Status.PASS), null,
                "${load.vcpus} vCPUs of running VMs on ${load.threads} host threads: ${ratio} per thread".toString(), "under ${warn} vCPUs per thread".toString(), st.source('vcpuWarnRatio'), null, Tier.NONE,
                ratio >= warn ? 'VMs wait for CPU when they are busy at once. This counts what is allotted, not what is used, so look at how busy the hosts really are before acting on it.' : null)
    }

    /** Below this many daily samples a straight line says little, so a forecast is not checked rather than a pass. */
    static final int FORECAST_MIN_DAYS = 14
    /** A line whose newest sample is older than this describes a cluster that is no longer being read. */
    static final int FORECAST_MAX_AGE_DAYS = 7
    private static final long GIB = 1L << 30

    /** Days until a line reaches its limit, from today. 0 when it is already there; null when it is not growing, however close it is. */
    static Long forecastDays(BigDecimal latest, BigDecimal slopePerDay, long ageDays, BigDecimal limit) {
        if (latest >= limit) {
            return 0L
        }
        slopePerDay > 0 ? Math.max(0L, (Math.ceil(((limit - latest) / slopePerDay) as double) as long) - ageDays) : null
    }

    /** Why a line says nothing yet, or null when it can be read. */
    private static String unreadLine(Map line) {
        if (!line || (line.n as int) < FORECAST_MIN_DAYS) {
            return "${line?.n ?: 0} of ${FORECAST_MIN_DAYS} daily samples so far".toString()
        }
        (line.age as long) > FORECAST_MAX_AGE_DAYS ? "the newest sample is ${line.age} days old".toString() : null
    }

    /** CAP-03 and GFS-11 read what the monitor fills in from CapacityTrend, so the checker stays free of storage access. With no forecast given, it says nothing. */
    private void forecastChecks() {
        Map fc = in_.clusterConfig?.forecast as Map
        if (fc == null) {
            return
        }
        int days = st.intVal('forecastWarnDays')
        String expected = "more than ${days} days".toString()
        if (in_.hosts.size() >= 2) {
            nPlusOneForecast(fc, days, expected)
        }
        if (!in_.layout?.hciCeph) {
            datastoreForecast(fc, days, expected)
        }
    }

    private void nPlusOneForecast(Map fc, int days, String expected) {
        String title = 'N+1 memory lasts'
        Map line = fc.nPlusOne as Map
        String unread = fc.error ? "cannot tell: ${fc.error}".toString() : unreadLine(line)
        if (unread) {
            add('CAP-03', title, Severity.MEDIUM, Status.NOT_CHECKED, null, unread, expected, st.source('forecastWarnDays'))
            return
        }
        Long left = forecastDays(line.latest as BigDecimal, line.slope as BigDecimal, line.age as long, line.limit as BigDecimal)
        String trend = "${((line.latest as BigDecimal) / GIB) as long} GiB of running VMs, ${((line.limit as BigDecimal) / GIB) as long} GiB without the largest host, over ${line.n} daily samples".toString()
        add('CAP-03', title, Severity.MEDIUM, left != null && left <= days ? Status.WARN : Status.PASS, null,
                (left == null ? "not growing: ${trend}" : (left ? "about ${left} days left: ${trend}" : "already past: ${trend}")).toString(), expected, st.source('forecastWarnDays'), null, Tier.NONE,
                left != null && left <= days ? 'A straight line through up to 90 days, so a step such as a new tenant moves it. Plan host memory, or fewer running VMs, before failover stops being able to restart everything.' : null)
    }

    private void datastoreForecast(Map fc, int days, String expected) {
        BigDecimal failAt = st.val('gfs2FailPct') as BigDecimal
        String title = 'Datastore space forecast'
        boolean mounted = in_.hosts.any { it.facts?.tables || it.facts?.mounts }
        List<Map> lines = (fc.datastores ?: []) as List<Map>
        if (fc.error || (!lines && mounted)) {
            add('GFS-11', title, Severity.MEDIUM, Status.NOT_CHECKED, null, fc.error ? "cannot tell: ${fc.error}".toString() : "no usage read yet, and 0 of ${FORECAST_MIN_DAYS} daily samples".toString(), expected, st.source('forecastWarnDays'))
            return
        }
        lines.each { Map line ->
            String unread = unreadLine(line)
            String named = "${title}: ${line.target}".toString()
            if (unread) {
                add('GFS-11', named, Severity.MEDIUM, Status.NOT_CHECKED, null, unread, expected, st.source('forecastWarnDays'))
                return
            }
            BigDecimal size = line.size as BigDecimal
            Long left = forecastDays(line.latest as BigDecimal, line.slope as BigDecimal, line.age as long, size * failAt / 100)
            String trend = "${((line.latest as BigDecimal) / GIB) as long} of ${(size / GIB) as long} GiB used, ${failAt}% is the limit, over ${line.n} daily samples".toString()
            add('GFS-11', named, Severity.MEDIUM, left != null && left <= days ? Status.WARN : Status.PASS, null,
                    (left == null ? "not growing: ${trend}" : (left ? "about ${left} days left: ${trend}" : "already there: ${trend}")).toString(), expected, st.source('forecastWarnDays'), null, Tier.NONE,
                    left != null && left <= days ? 'A straight line through up to 90 days. GFS2 free space does not show how full a thin LUN\'s array is: see STO-07.' : null)
        }
    }

    /** The array's own warning, which reaches a host as a SCSI sense code. A log that could not be read says nothing either way. */
    private void thinLunChecks(List<Host> hs) {
        if (in_.layout?.hciCeph) {
            return
        }
        hs.findAll { it.facts.mode == 'full' && it.facts.sections.containsKey('thin') }.each { Host h ->
            Integer soft = h.facts.thinSoft
            Integer hard = h.facts.thinHard7d
            List<String> woMaps = h.facts.maps.findAll { (it.wp as String) in ['ro', 'read-only'] }*.wwid as List<String>
            boolean fail = (hard != null && hard > 0) || woMaps
            Status s = fail ? Status.FAIL : (soft == null && hard == null ? Status.NOT_CHECKED : (soft ? Status.WARN : Status.PASS))
            String obs = fail ? [hard ? "${hard} hard space-allocation failure(s) over 7 days, last: ${h.facts.thinHardLast}" : null, woMaps ? "write-protected map(s): ${woMaps.join(', ')}" : null].findAll().join('; ')
                    : (soft == null && hard == null ? 'cannot tell: the kernel log could not be read' : (soft ? "${soft} in the kernel log over 7 days, last: ${h.facts.thinSoftLast}" : 'none in 7 days'))
            add('STO-07', 'Array has not warned that a thin LUN is filling', Severity.HIGH, s, h.name, obs.toString(), 'none', 'plugin', null, Tier.NONE,
                    fail ? 'The array has refused writes for lack of space (a hard space-allocation failure, SCSI sense 27/07 DATA PROTECT) or has write-protected a map. On a GFS2 map this withdraws the filesystem, however much free space df shows. Look at the array\'s pool now.'
                            : (soft ? 'The array says a thin-provisioned LUN has crossed its soft limit. If its pool fills, writes fail and GFS2 withdraws, however much free space df shows. Look at the array now.' : null))
        }
    }

    // ------------------------------------------------------------------ host

    private void hostChecks(List<Host> hs) {
        hs.each { Host h ->
            HostFacts f = h.facts
            // The kernel keeps saying synchronised long after NTP is turned off, so off is a finding even while it does.
            boolean ntpOff = f.ntp == false
            add('TIM-01', 'Clock synchronised', Severity.HIGH, f.ntpSynced == null ? Status.NA : (f.ntpSynced ? (ntpOff ? Status.WARN : Status.PASS) : Status.FAIL), h.name,
                    f.ntpSynced == null ? 'unknown' : (f.ntpSynced ? (ntpOff ? 'synchronised for now, but NTP is off' : 'synchronised') : "not synchronised (NTP ${f.ntp ? 'on' : 'off'})".toString()), 'synchronised, NTP on', 'upstream',
                    ntpOff && f.ntpSynced != null ? 'ntp-enable' : null, ntpOff && f.ntpSynced != null ? Tier.MAINTENANCE : Tier.NONE,
                    f.ntpSynced == false && f.ntp ? 'NTP is on but not synchronised: check that the time servers are reachable.' : (ntpOff && f.ntpSynced ? 'Nothing is correcting this clock now, so it will drift.' : null))
            if (f.rebootRequired) {
                add('HST-01', 'No pending reboot', Severity.LOW, Status.WARN, h.name, 'reboot required', 'none', 'plugin', null, Tier.NONE, 'Reboot in a maintenance window, one host at a time.')
            }
        }
        agentVersionCheck(hs)
        ['corosync', 'dlm-controld', 'gfs2-utils', 'libvirt-daemon-system', 'qemu-system-x86'].each { String p ->
            Map<String, String> versions = hs.findAll { it.facts.packages[p] }.collectEntries { [(it.name): it.facts.packages[p]] }
            if ((versions.values() as Set).size() > 1) {
                add('PKG-01', "Same ${p} on every host".toString(), Severity.LOW, Status.WARN, null, versions.collect { k, v -> "${k}=${v}" }.join(', '), 'equal', 'plugin')
            }
        }
        agentCheck(hs)
        Map<String, String> kernels = hs.findAll { it.facts.kernel }.collectEntries { [(it.name): it.facts.kernel] }
        if ((kernels.values() as Set).size() > 1) {
            add('PKG-02', 'Same kernel on every host', Severity.LOW, Status.WARN, null, kernels.collect { k, v -> "${k}=${v}" }.join(', '), 'equal', 'plugin')
        }
        hs.findAll { it.facts.mode == 'full' }.each { Host h ->
            if (h.facts.ipmiToolPresent == false) {
                add('FEN-01', 'Host management controller known', Severity.LOW, Status.NOT_CHECKED, h.name, NO_IPMITOOL, 'configured', 'plugin', null, Tier.NONE,
                        'This host may well have a management controller: the plugin reads it through ipmitool, which Morpheus-built hosts do not include. Without one there is no out-of-band way to prove a failed host is powered off.')
            } else {
                add('FEN-01', 'Host management controller known', Severity.LOW, h.facts.bmcIp && h.facts.bmcIp != '0.0.0.0' ? Status.PASS : Status.WARN, h.name, h.facts.bmcIp ?: 'none', 'configured', 'plugin', null, Tier.NONE,
                        h.facts.bmcIp ? null : 'Without one there is no out-of-band way to prove this host is powered off when it fails.')
            }
        }
        fenceAgentCheck(hs)
        apdChecks(hs)
    }

    /**
     * FEN-02: advisory a00156214. Clusters created on Manager 8.0.11 or 8.0.12 used Pacemaker and the SCSI fence agent fence_scsi_hpevm, and a single
     * host failure then powers off every surviving VM. The plugin API does not expose when a cluster was created, so the host-side agent is the proxy: a
     * 1.3/2.0 host should not carry it. Built from documentation (search extracts), not seen on a host. Quiet on HCI Ceph, which does not use it.
     */
    private void fenceAgentCheck(List<Host> hs) {
        if (in_.layout?.hciCeph) {
            return
        }
        List<String> carriers = hs.findAll { it.facts.mode == 'full' && it.facts.sections.containsKey('fence_agent') && it.facts.fenceScsiAgent }*.name.sort()
        if (!carriers) {
            return
        }
        add('FEN-02', 'No SCSI fence agent from the 8.0.11/8.0.12 defect', Severity.HIGH, Status.WARN, null,
                "fence_scsi_hpevm is present on ${carriers.join(', ')}".toString(), 'absent on layout 1.3/2.0', 'hpe', null, Tier.NONE,
                'HPE advisory a00156214, built from documentation and not seen on a host: a cluster created on Manager 8.0.11 or 8.0.12, which used Pacemaker and this SCSI fence agent, turns off every surviving VM when one host fails, and the defect follows the cluster even after an upgrade. Layout 1.3 and 2.0 do their own fencing and should not carry this script, so its presence suggests this cluster began in that era. The plugin cannot read when a cluster was created, so confirm the creation version with HPE and apply hotfix 8.0.12-2 or a layout upgrade through HPE. Do not hand-edit the agent script; see docs/HPE-QUESTIONS.md.')
    }

    /**
     * HB-02: the agent's all-paths-down shutdown. After about 6 failed heartbeat-write cycles it destroys every non-pinned VM on shared storage, and recovery
     * is manual: the biggest uncovered outage, and after the fact the journal is the evidence. Full check only, as the event persists 7 days in the log. Quiet on HCI Ceph.
     */
    private void apdChecks(List<Host> hs) {
        if (in_.layout?.hciCeph) {
            return
        }
        hs.findAll { it.facts.mode == 'full' && it.facts.sections.containsKey('apd') }.each { Host h ->
            Integer n = h.facts.apdSeen
            add('HB-02', 'No all-paths-down VM shutdown recorded', Severity.CRITICAL, n == null ? Status.NOT_CHECKED : (n ? Status.FAIL : Status.PASS), h.name,
                    n == null ? 'cannot tell: the host journal could not be read in time' : (n ? "${n} line(s) in the journal over 7 days, last: ${h.facts.apdLast}".toString() : 'none in 7 days'), 'none', 'hpe', null, Tier.NONE,
                    n ? 'The agent counts failed heartbeat writes and, after about 6 cycles (roughly 2 minutes), destroys every non-pinned VM on shared storage to guard against split-brain; recovery is manual, and HPE says to alert on any occurrence. The decisive line is "Shutting down all VMs to protect data integrity", with the precursor "heartbeat datastore paths ... unhealthy ... for N checks". Find what lost the heartbeat datastore then (STO-01 paths, STO-06 latency, a withdrawal in GFS-10), start the VMs that were stopped, and keep the evidence for HPE. The exact agent wording and the destroy timer under path loss are unverified here: see docs/HPE-QUESTIONS.md. It clears 7 days after the last line.' : null)
        }
    }

    /** The agent is not a dpkg package on Morpheus-built hosts. A version that could not be read is said, not taken as a match. */
    private void agentCheck(List<Host> hs) {
        if (!hs) {
            return
        }
        String title = 'Same Morpheus agent on every host'
        Map<String, String> versions = hs.findAll { it.facts.agentVersion }.collectEntries { [(it.name): it.facts.agentVersion] }
        List<String> unread = hs*.name - versions.keySet()
        String listed = versions.collect { k, v -> "${k}=${v}" }.join(', ')
        String missing = unread ? "cannot tell for ${unread.join(', ')}: no agent version found on the host".toString() : null
        if ((versions.values() as Set).size() > 1) {
            add('PKG-01', title, Severity.LOW, Status.WARN, null, [listed, missing].findAll().join('; '), 'equal', 'plugin')
        } else if (unread) {
            add('PKG-01', title, Severity.LOW, Status.NOT_CHECKED, null, [listed, missing].findAll().join('; '), 'equal', 'plugin')
        } else {
            add('PKG-01', title, Severity.LOW, Status.PASS, null, "${versions.values()[0]} on every host".toString(), 'equal', 'plugin')
        }
    }

    // ------------------------------------------------------------------ Morpheus's view

    /** Dotted numbers compared in turn, so 3.10.0 is newer than 3.2.6. Null when either cannot be read that way. */
    static Integer compareVersions(String a, String b) {
        if (!(a ==~ /\d+(\.\d+)*.*/) || !(b ==~ /\d+(\.\d+)*.*/)) {
            return null
        }
        List<Integer> x = (a =~ /^\d+(?:\.\d+)*/)[0].tokenize('.')*.toInteger(), y = (b =~ /^\d+(?:\.\d+)*/)[0].tokenize('.')*.toInteger()
        for (int i = 0; i < Math.max(x.size(), y.size()); i++) {
            int c = (i < x.size() ? x[i] : 0) <=> (i < y.size() ? y[i] : 0)
            if (c) {
                return c
            }
        }
        0
    }

    /** On a production freeze, 3.2.6 acknowledged the other survivors and never the failed node, for two hours. */
    private void agentVersionCheck(List<Host> hs) {
        String min = st.val('agentMinVersion') as String
        Map<String, String> read = hs.findAll { it.facts.agentVersion }.collectEntries { [(it.name): it.facts.agentVersion] }
        if (!min || !read) {
            return
        }
        List<String> old = read.findAll { k, v -> compareVersions(v, min) == -1 }.collect { k, v -> "${k}=${v}".toString() }
        add('PKG-03', 'Morpheus agent is at least the version that handled a freeze correctly', Severity.HIGH, old ? Status.WARN : Status.PASS, null,
                old ? old.join(', ') : "${read.size()} host(s) at ${min} or later".toString(), ">= ${min}".toString(), st.source('agentMinVersion'), null, Tier.NONE,
                old ? 'An older agent (3.2.6) acknowledged the wrong nodes in a real freeze, so GFS2 stayed frozen until someone acknowledged the right node by hand. Newer agents acknowledged correctly in every test. Upgrade through HPE, but in a maintenance window and one host at a time: an agent upgrade has itself triggered failover when run on a host outside maintenance (HPE fixed one such case in 8.0.13-2, and whether later versions are safe outside maintenance is unproven). Until then watch dlm_tool on the other hosts during any host removal. See docs/HPE-QUESTIONS.md.' : null)
    }

    /** If the manager's datastore freezes, the console and every alarm go with it, which is when they are needed. */
    private void managerOnClusterCheck() {
        List<String> addrs = (in_.clusterConfig?.managerAddrs ?: []) as List<String>
        if (!addrs) {
            return
        }
        Map vm = in_.morpheusVms.find { Map v -> (v.ips as List)?.any { it in addrs } }
        add('HA-06', 'The Morpheus manager runs outside this cluster', Severity.MEDIUM, vm ? Status.WARN : Status.PASS, vm?.host as String,
                vm ? "the manager is VM ${vm.label ?: vm.name}".toString() : 'not found on this cluster', 'outside the cluster it manages', 'plugin', null, Tier.NONE,
                vm ? 'A freeze on this cluster would take down the console and every alarm, including this one. Move the manager, or watch these hosts from somewhere else as well.' : null)
    }

    /** Nested virtualization among others: such a VM stays on its host's CPU, so emptying the host means stopping it. */
    private void pinnedVmChecks(List<Host> hs) {
        hs.findAll { it.facts.sections.containsKey('vm_cpu') && it.facts.vmCpus }.each { Host h ->
            List<String> pinned = h.facts.vmNoMigrate.sort()
            add('MIG-07', 'VMs can live-migrate', Severity.MEDIUM, pinned ? Status.WARN : Status.PASS, h.name,
                    pinned ? "cannot live-migrate: ${pinned.join(', ')}".toString() : "${h.facts.vmCpus.size()} running VM(s), none pinned".toString(), 'none pinned by their CPU settings', 'plugin', null, Tier.NONE,
                    pinned ? 'Their CPU is pinned to this host, usually by nested virtualization, and libvirt refuses to move them. They must be stopped to empty the host, and automatic placement will keep failing on them.' : null)
        }
    }

    private void morpheusChecks() {
        if (in_.clusterConfig?.recordStatus == 'failed') {
            add('MOR-06', 'Morpheus has no failure recorded on this cluster', Severity.LOW, Status.WARN, null, 'cluster record status: failed', 'not failed', 'morpheus', null, Tier.NONE,
                    'Usually the last steps of adding a host failed, as its Post-Provision process will show. Those steps did not run on that host, even if it joined. Check its votes and CLU-13, and consider deleting and adding it again.')
        }
        in_.removing.each { String name ->
            add('MOR-04', 'Morpheus has finished removing host records', Severity.LOW, Status.WARN, null, "${name} is still being removed".toString(), 'none in progress', 'plugin', null, Tier.NONE,
                    'Not counted as a host. Normal for a few minutes after a removal; a record that stays here is stuck and holds on to its name.')
        }
        in_.hosts.each { Host h ->
            String msg = h.statusMessage ?: ''
            boolean healthy = h.facts?.complete && h.facts.active('corosync') && h.facts.quorate
            if (msg =~ /(?i)fenced|nodedown|unclean/ && healthy) {
                add('MOR-01', 'Morpheus host status matches the host', Severity.LOW, Status.WARN, h.name, msg, 'no fence status on a healthy host', 'plugin', null, Tier.NONE,
                        'The host is a healthy cluster member, so this message is stale. Find out whether it affects placement before relying on it.')
            }
            else if (msg =~ /(?i)error|failed|timed out/ && healthy) {
                add('MOR-05', 'Morpheus reports no problem on this host', Severity.LOW, Status.WARN, h.name, msg, 'no error in its status', 'plugin', null, Tier.NONE,
                        'The host looks healthy from here. Morpheus can leave an old message in place after the event, so check whether it is current.')
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

    // ------------------------------------------------------------------ failover settings

    private void failoverChecks(List<Host> hs) {
        if (in_.hosts.size() < 2) {
            add('HA-03', 'Failover settings', Severity.HIGH, Status.NA, null, "${in_.hosts.size()} host(s): no other host to restart VMs on".toString(), 'at least 2 hosts', 'plugin')
            return
        }
        powerOnSetting('HA-03', 'this cluster', powerOnState(hs), 'the cluster')
        // The cluster's setting decides: with the cloud's off and the cluster's on, the agent restarted VMs. Shown, as Morpheus has both.
        Boolean cloudOn = in_.clusterConfig?.cloudAutoRecoverPowerState as Boolean
        add('HA-04', "\"Automatically Power On VMs\" on this cluster's cloud", Severity.LOW, cloudOn == null ? Status.NOT_CHECKED : Status.NA, null,
                cloudOn == null ? 'cannot tell: Morpheus gave no value for this setting' : "${cloudOn ? 'on' : 'off'}; the cluster's setting is the one that decides".toString(), 'informational', 'plugin')
        heartbeatDatastoreCheck(hs)
    }

    /** The cluster's "Automatically Power On VMs": what its record states, or else what its heartbeats show. HA-03 and HB-01 both answer from this. */
    private Boolean powerOnState(List<Host> hs) {
        Boolean stated = in_.clusterConfig?.autoRecoverPowerState as Boolean
        stated != null ? stated : powerOnFromHeartbeats(hs)
    }

    /**
     * The plugin API has no member for the cluster's setting, though REST does. With it off the agents drop their failover job and stop writing
     * heartbeats within a cycle of about 20 s, as measured, so fresh files mean on. Null when frozen or when there are no files to judge by.
     */
    private Boolean powerOnFromHeartbeats(List<Host> hs) {
        HostFacts reader = hs*.facts.find { it.fsSafe && it.heartbeats }
        if (!reader || !reader.now || in_.hosts*.facts.any { it?.frozen }) {
            return null
        }
        reader.heartbeats.any { it.ms && (reader.now * 1000L - (it.ms as Long)) < 60_000L }
    }

    /** Null is an answer of its own: an unset checkbox and a record that could not be read look alike, and neither is proof the setting is off. */
    private void powerOnSetting(String id, String where, Boolean on, String edit) {
        String title = "\"Automatically Power On VMs\" is on for ${where}".toString()
        if (on == null) {
            add(id, title, Severity.HIGH, Status.NOT_CHECKED, null, 'cannot tell: Morpheus gave no value for this setting', 'on', 'hpe')
        } else {
            add(id, title, Severity.HIGH, on ? Status.PASS : Status.WARN, null, on ? 'on' : 'off', 'on', 'hpe', null, Tier.NONE,
                    on ? null : "Morpheus will not power on VMs that a lost host took down, so they stay down until someone starts them. Edit ${edit} and turn it on, unless that is what you want.".toString())
        }
    }

    /**
     * The flag defaults to false on a partial record, so it is only taken as "none" when the hosts also show no heartbeat
     * files, which they write once a datastore is the target. Files with no flag is a pass: heartbeating is what matters.
     */
    private void heartbeatDatastoreCheck(List<Host> hs) {
        String title = 'A datastore is the heartbeat target'
        String expected = 'one on a shared datastore'
        if (in_.layout?.hciCeph) {
            add('HA-05', title, Severity.MEDIUM, Status.NA, null, 'not judged on the HCI Ceph layout', expected, 'plugin')
            return
        }
        List<Map> all = in_.clusterConfig?.datastores as List<Map>
        Set<String> pools = hs.collectMany { it.facts.pools*.name as List<String> } as Set<String>
        List<Map> ours = (all ?: []).findAll { it.cluster || !pools || it.name in pools }
        List<String> flagged = ours.findAll { it.heartbeatTarget && it.online != false && it.active != false }*.name as List<String>
        boolean files = hs.any { it.facts.heartbeats as boolean }
        if (flagged) {
            add('HA-05', title, Severity.MEDIUM, Status.PASS, null, flagged.join(', '), expected, 'hpe')
        } else if (files) {
            add('HA-05', title, Severity.MEDIUM, Status.PASS, null, "hosts are writing heartbeat files${ours ? ', though Morpheus flags none of ' + ours.size() + ' datastore(s)' : ''}".toString(), expected, 'hpe')
        } else if (ours && hs.any { it.facts.fsSafe }) {
            add('HA-05', title, Severity.MEDIUM, Status.WARN, null, "none of ${ours.size()} datastore(s), and no heartbeat files on the hosts".toString(), expected, 'hpe', null, Tier.NONE,
                    'Without one, Morpheus only finds a lost host at its next cluster sync, about 5 minutes by default according to HPE staff, rather than within seconds. Edit a shared datastore and make it the heartbeat target.')
        } else {
            add('HA-05', title, Severity.MEDIUM, Status.NOT_CHECKED, null, ours ? 'cannot tell: Morpheus flags none, and GFS2 could not be read for heartbeat files' : 'cannot tell: Morpheus listed no datastores for this cluster', expected, 'hpe')
        }
    }

    // ------------------------------------------------------------------ witness and quorum device

    /**
     * A device is configured when corosync.conf has a device block in quorum, and it is what reports whether it votes. Losing it leaves the
     * hosts quorate while the margin it gave is gone, so it fails only with no margin left, which CLU-04 reads the same way.
     */
    private void quorumDeviceChecks(List<Host> hs) {
        String title = 'Quorum device connected and voting'
        String expected = 'connected, alive and voting on every host'
        List<Host> up = hs.findAll { it.facts.active('corosync') }
        List<Host> configured = up.findAll { it.facts.corosync['device.model'] }
        if (!configured && !up.any { it.facts.qdeviceRow || it.facts.quorumFlags?.contains('Qdevice') }) {
            if (up) {
                add('CLU-12', title, Severity.HIGH, Status.NA, null,
                        'no corosync quorum device: HVM\'s two-node witness is a Distributed Worker over HTTPS and part of the agent quorum (CLU-14), not a corosync qdevice', 'only where a corosync qdevice is configured', 'upstream', null, Tier.NONE,
                        'HPE\'s witness for a two-node cluster is a Distributed Worker reached at <Worker>/witness/<cluster UUID>/quorum over HTTPS, a quorum-only member of the agent quorum. It does not appear to corosync, so this check stays NA in normal HVM use and does not cover a witness. Built from HPE docs, not yet run against a real witness.')
            }
            return
        }
        up.each { Host h ->
            HostFacts f = h.facts
            String row = f.qdeviceRow, state = f.qdeviceState
            if (!f.corosync['device.model']) {
                add('CLU-12', title, Severity.HIGH, Status.FAIL, h.name, 'no quorum device in this host\'s corosync.conf, though other hosts have one', expected, 'upstream')
            } else if (row == null && state == null) {
                add('CLU-12', title, Severity.HIGH, Status.NOT_CHECKED, h.name, "cannot tell: nothing reported the device's state${f.qdeviceToolPresent == false ? ' and corosync-qdevice-tool is not installed' : ''}".toString(), expected, 'upstream')
            } else {
                boolean ok = (state == null || state == 'Connected') && (row == null || row.startsWith('A,V,'))
                boolean margin = f.totalVotes != null && f.quorumVotes != null && f.totalVotes > f.quorumVotes
                add('CLU-12', title, Severity.HIGH, ok ? Status.PASS : (margin ? Status.WARN : Status.FAIL), h.name,
                        [state ? "qnetd ${state}" : null, row ? "votequorum ${row}" : null, f.corosync['net.host'] ? "witness ${f.corosync['net.host']}" : null].findAll().join(', '), expected, 'upstream', null, Tier.NONE,
                        ok ? null : 'The witness is lost, or this host cannot reach it. The cluster still has quorum while its hosts agree, but its tiebreak is gone. Check corosync-qdevice on this host and qnetd on the witness.')
            }
        }
    }

    // ------------------------------------------------------------------ certificates

    private Status certStatus(long days) {
        days <= st.intVal('certFailDays') ? Status.FAIL : (days <= st.intVal('certWarnDays') ? Status.WARN : Status.PASS)
    }

    private static String daysLeft(long days) {
        days < 0 ? "expired ${-days} day(s) ago".toString() : "${days} day(s) left".toString()
    }

    /** Hosts migrate over SSH by default, so a certificate nothing uses cannot break anything: it is listed, and raises no alarm. */
    private void certificateChecks(List<Host> hs) {
        String expected = "> ${st.val('certWarnDays')} days left".toString()
        List<Host> read = hs.findAll { it.facts.sections.containsKey('certs') }
        if (read && !read.any { it.facts.certs }) {
            add('HST-02', 'Libvirt certificates in date', Severity.HIGH, Status.NA, null, 'no libvirt certificates on these hosts', expected, 'plugin')
        }
        read.each { Host h ->
            h.facts.certs.each { Map c ->
                String title = "Libvirt certificate in date: ${c.path}".toString()
                if (c.notAfter == null) {
                    add('HST-02', title, Severity.HIGH, Status.NOT_CHECKED, h.name, h.facts.opensslPresent == false ? 'cannot tell: openssl not installed' : 'cannot tell: its expiry date could not be read', expected, st.source('certWarnDays'))
                } else {
                    long days = Math.floorDiv((c.notAfter as Long) - (h.facts.now ?: (in_.nowMs / 1000L as long)), 86400L)
                    Status s = h.facts.certsInUse ? certStatus(days) : Status.NA
                    add('HST-02', title, Severity.HIGH, s, h.name, h.facts.certsInUse ? daysLeft(days) : "${daysLeft(days)}; libvirt TLS is not in use on this host".toString(), expected, st.source('certWarnDays'), null, Tier.NONE,
                            s in [Status.FAIL, Status.WARN] ? 'Libvirt or qemu on this host uses TLS, so migrations or consoles that depend on it stop when this expires. Replace it in a maintenance window.' : null)
                }
            }
        }
        if (in_.clusterConfig?.containsKey('applianceCertNotAfterMs')) {
            Long at = in_.clusterConfig.applianceCertNotAfterMs as Long
            if (at == null) {
                add('HST-03', 'Appliance certificate in date', Severity.MEDIUM, Status.NOT_CHECKED, null, 'cannot tell: the appliance URL gave no certificate to read', expected, st.source('certWarnDays'))
            } else {
                long days = Math.floorDiv(at - in_.nowMs, 86400000L)
                Status s = certStatus(days)
                add('HST-03', 'Appliance certificate in date', Severity.MEDIUM, s, null, daysLeft(days), expected, st.source('certWarnDays'), null, Tier.NONE,
                        s == Status.PASS ? null : 'Agents and users reach the appliance through this certificate. It is one certificate, so every cluster reports it.')
            }
        }
    }

    // ------------------------------------------------------------------ boot disks

    private void bootDiskChecks(List<Host> hs) {
        hs.findAll { it.facts.mode == 'full' && it.facts.sections.containsKey('smart') }.each { Host h ->
            HostFacts f = h.facts
            if (f.smartctlPresent == false) {
                add('HW-07', 'Boot disks healthy (SMART)', Severity.HIGH, Status.NOT_CHECKED, h.name, 'cannot tell: smartctl not installed', 'healthy, no bad sectors', 'plugin', null, Tier.NONE, 'Install smartmontools to read the boot disks.')
            } else if (!f.bootDisks) {
                add('HW-07', 'Boot disks healthy (SMART)', Severity.HIGH, Status.NOT_CHECKED, h.name, 'cannot tell: could not find the disk under /', 'healthy, no bad sectors', 'plugin')
            }
            if (f.smartctlPresent != false) {
                f.bootDisks.each { Map d -> bootDiskCheck(h, d) }
                f.raidDisks.each { Map d -> bootDiskCheck(h, d) }
                if (f.raidDisksUnread) {
                    add('HW-07', 'Physical disks behind the RAID controller read', Severity.HIGH, Status.NOT_CHECKED, h.name, "cannot tell: ${f.raidDisksUnread} physical disk(s) not read: over the limit of 24, out of time, or the controller stopped answering".toString(), 'healthy, no bad sectors', 'plugin')
                }
            }
        }
    }

    /**
     * The disks under /, and physical disks behind a RAID controller, which have a slot. Reallocated sectors are only shown: vendors encode that counter differently, and a disk can run for years with a few.
     * A disk read through its controller is physical whatever its model says. A WHEN_FAILED value other than a dash fails either kind.
     */
    private void bootDiskCheck(Host h, Map d) {
        String title = (d.slot ? "Disk healthy (SMART): ${d.slot} on bus ${d.bus}" : "Boot disk healthy (SMART): ${d.name}").toString()
        String expected = 'healthy, no bad sectors'
        String model = d.model ?: 'unknown model'
        boolean hidden = d.virtual && !d.slot
        if (hidden || d.health == null) {
            // only disks on the volume's own controller: a boot card's volume says nothing about the disks on a RAID controller beside it
            List<Map> read = d.scsiHost ? h.facts.raidDisks.findAll { it.bus == d.scsiHost } : []
            add('HW-07', title, Severity.HIGH, Status.NOT_CHECKED, h.name,
                    "cannot tell: ${model} ${hidden ? 'is a virtual disk' : 'gave no SMART health'}${read ? ", so the physical disks on its controller were read instead: ${read*.slot.join(', ')}" : ''}".toString(), expected, 'plugin', null, Tier.NONE,
                    d.slot ? 'The controller did not pass this disk\'s SMART health through. Read it with the controller\'s own tools.'
                            : (read ? 'A RAID volume hides SMART from the host. Each physical disk on its controller has its own result here.' : 'A RAID volume or a hypervisor\'s disk hides SMART from the host. Read the physical disks through their controller.'))
            return
        }
        long bad = ((d.pending ?: 0L) as long) + ((d.uncorrectable ?: 0L) as long) + ((d.mediaErrors ?: 0L) as long)
        List whenFailed = (d.whenFailed ?: []) as List
        boolean failing = !(d.health in ['PASSED', 'OK']) || !(d.critical in [null, '0x00', '0']) || whenFailed
        add('HW-07', title, Severity.HIGH, failing ? Status.FAIL : (bad ? Status.WARN : Status.PASS), h.name,
                "${model}: ${d.health}${d.critical && d.critical != '0x00' ? ', critical warning ' + d.critical : ''}, ${bad} bad sector(s) or media error(s), ${d.reallocated ?: 0} reallocated${whenFailed ? ', failed attributes: ' + whenFailed.join(', ') : ''}".toString(), expected, 'plugin', null, Tier.NONE,
                failing || bad ? (d.slot ? 'Replace this disk in a maintenance window, by the controller\'s own procedure. Check the state of its volume first.' : 'Replace this disk in a maintenance window. A host that loses its boot disk leaves the cluster uncleanly, which stalls every GFS2 datastore until it is acknowledged.') : null)
    }

    // ------------------------------------------------------------------ HPE's own checks

    private static final Map<String, String> HVMCLI_UNREAD = [
            missing   : 'cannot tell: this 2.0 host has no hvmcli',
            unsafe    : 'not run: DLM did not say GFS2 is safe to touch, and this check may read GFS2',
            no_time   : 'not run: too little of the probe\'s time limit was left',
            timeout   : 'cannot tell: hvmcli did not answer within 30 s',
            unreadable: 'cannot tell: hvmcli printed nothing that reads as its JSON',
    ]

    private static boolean hvmcliIgnored(Map c, Collection ignored) {
        (c.check as String).toLowerCase() in ignored
    }

    private static Set<String> hvmcliIgnoreSet(Standards s) {
        (s.val('hvmcliIgnore') as List<String>)*.toLowerCase() as Set<String>
    }

    /**
     * Only shows HPE's answer, on HPE's thresholds, and only warns, as this plugin's own checks judge the same things. Its observed text names checks and never carries
     * their details, which can name hosts and VMs: those stay in the tab's panel, out of alarms and reports. The title never changes, so an alarm survives a run that could not read.
     */
    private void hpeCheck(List<Host> hs) {
        if (in_.layout?.version != '2.0') {
            return
        }
        String title = 'HPE health check (hvmcli)'
        String expected = 'no warning or failure outside hvmcliIgnore'
        Set<String> ignored = hvmcliIgnoreSet(st)
        hs.findAll { it.facts.mode == 'full' && it.facts.hvmcliState }.each { Host h ->
            HostFacts f = h.facts
            if (f.hvmcliState != 'read') {
                add('HPE-01', title, Severity.MEDIUM, Status.NOT_CHECKED, h.name, HVMCLI_UNREAD[f.hvmcliState] ?: HVMCLI_UNREAD.unreadable, expected, 'hpe')
                return
            }
            List<Map> raised = f.hvmcliChecks.findAll { it.status in ['WARN', 'FAIL'] && !hvmcliIgnored(it, ignored) }.sort { it.status == 'FAIL' ? 0 : 1 }
            String names = raised.take(8).collect { "${it.status} ${it.check}" }.join(', ') + (raised.size() > 8 ? ", and ${raised.size() - 8} more" : '')
            add('HPE-01', title, Severity.MEDIUM, raised ? Status.WARN : Status.PASS, h.name, raised ? names : "${f.hvmcliChecks.size()} checks read, nothing raised outside the ignored list".toString(), expected, 'hpe', null, Tier.NONE,
                    raised ? 'These are HPE\'s thresholds. The HPE checks panel under Hosts shows what each one said. Where the plugin has its own check for the same thing, read that too.' : null)
        }
    }

    /** The tab's panel: what HPE said on each host, marked. A quick check asks nothing, so the monitor keeps the last full check's. Null on a layout with no hvmcli. */
    static List<Map> hpeView(Input in_) {
        if (in_.layout?.version != '2.0') {
            return null
        }
        Set<String> ignored = hvmcliIgnoreSet(in_.standards)
        List<String> pacemaker = (Standards.DEFAULTS.hvmcliIgnore.value as List<String>)
        in_.hosts.findAll { it.facts?.hvmcliState }.collect { Host h ->
            [host: h.name, note: h.facts.hvmcliState == 'read' ? null : (HVMCLI_UNREAD[h.facts.hvmcliState] ?: HVMCLI_UNREAD.unreadable),
             checks: h.facts.hvmcliChecks.collect { Map c -> c + [pacemaker: c.check in pacemaker, ignored: hvmcliIgnored(c, ignored)] }]
        }
    }

    // ------------------------------------------------------------------ per-VM CPU drift

    /** A VM keeps its CPU until at least its next power cycle, so -cpu host under a named cluster model is a VM the change has not reached. */
    private void cpuDriftChecks(List<Host> hs) {
        String title = 'Running VMs use the cluster CPU model'
        String model = in_.clusterConfig?.cpuModel as String
        if (!model || model in ['host-passthrough', 'host-model']) {
            if (hs) {
                add('MIG-06', title, Severity.MEDIUM, Status.NA, null, model ? "the cluster uses ${model}".toString() : 'no CPU model on record for this cluster', 'a named CPU model', 'hpe')
            }
            return
        }
        String expected = "${model}, not host-passthrough".toString()
        hs.findAll { it.facts.sections.containsKey('vm_cpu') }.each { Host h ->
            List<String> running = h.facts.vms.findAll { it.state == 'running' }*.name as List<String>
            List<String> old = h.facts.vmCpus.findAll { k, v -> v == 'host' }.keySet().sort()
            if (old) {
                add('MIG-06', title, Severity.MEDIUM, Status.WARN, h.name, "still on host-passthrough: ${old.join(', ')}".toString(), expected, 'hpe', null, Tier.NONE,
                        'New VMs get the cluster model. An existing VM kept host-passthrough through a stop and start in Morpheus, so expect to rebuild it, and until then not to live-migrate it to a host with a different CPU.')
            } else if (running && !h.facts.vmCpus) {
                add('MIG-06', title, Severity.MEDIUM, Status.NOT_CHECKED, h.name, "cannot tell: no qemu process read for ${running.size()} running VM(s)".toString(), expected, 'hpe')
            } else {
                add('MIG-06', title, Severity.MEDIUM, Status.PASS, h.name, "${h.facts.vmCpus.size()} running VM(s), none on host-passthrough".toString(), expected, 'hpe')
            }
        }
    }

    // ------------------------------------------------------------------ storage migration space

    private static long gibOf(long bytes) { bytes / (1L << 30) as long }

    /**
     * Whether the largest VM could move onto each GFS2 datastore: its disks not there already, at their full size, since a thin disk can fill up.
     * Only disks known to be elsewhere can raise it. A disk Morpheus does not place may be on this datastore already, so a shortfall that rests on one is not raised.
     */
    private void storageSpaceChecks(List<Host> hs) {
        if (in_.layout?.hciCeph) {
            return
        }
        Map<String, Map> byTarget = [:]
        Map<String, String> names = [:]
        hs.each { Host h ->
            h.facts.usage.each { Map u -> byTarget.putIfAbsent(u.target as String, u) }
            names.putAll(h.facts.poolPaths)
        }
        Set<String> pools = hs.collectMany { it.facts.pools*.name as List<String> } as Set<String>
        List<Map> vms = in_.morpheusVms.findAll { it.disks }
        byTarget.each { String target, Map u ->
            String name = names[target] ?: target
            String title = "Room to move the largest VM onto ${name}".toString()
            String expected = 'free space at least the largest VM\'s disks'
            long free = (u.size as long) - (u.used as long)
            if (!vms) {
                add('GFS-09', title, Severity.MEDIUM, Status.NOT_CHECKED, null, "${gibOf(free)} GiB free; cannot tell: Morpheus gave no VM disk sizes".toString(), expected, 'plugin')
                return
            }
            Closure<Long> sized = { Map v, Closure<Boolean> counts -> ((v.disks as List<Map>).findAll(counts).sum { it.size as long } ?: 0L) as Long }
            Map most = vms.max { Map v -> sized(v) { it.ds != name } }
            long need = sized(most) { it.ds != name }
            Map known = vms.max { Map v -> sized(v) { names[target] && it.ds in pools && it.ds != name } }
            long knownNeed = sized(known) { names[target] && it.ds in pools && it.ds != name }
            if (!need) {
                add('GFS-09', title, Severity.MEDIUM, Status.NA, null, 'every VM\'s disks are already on this datastore', expected, 'plugin')
            } else if (free >= need) {
                add('GFS-09', title, Severity.MEDIUM, Status.PASS, null, "${gibOf(free)} GiB free; largest to move ${most.name} at ${gibOf(need)} GiB".toString(), expected, 'plugin')
            } else if (free < knownNeed) {
                add('GFS-09', title, Severity.MEDIUM, Status.WARN, null, "${gibOf(free)} GiB free; ${known.name} has ${gibOf(knownNeed)} GiB elsewhere".toString(), expected, 'plugin', null, Tier.NONE,
                        'A storage migration of that VM onto this datastore would not fit at the disks\' full size. A thin disk may need less.')
            } else {
                add('GFS-09', title, Severity.MEDIUM, Status.NOT_CHECKED, null, "${gibOf(free)} GiB free, ${most.name} has ${gibOf(need)} GiB; cannot tell: Morpheus did not say which datastore its disks are on, and they may be here already".toString(), expected, 'plugin')
            }
        }
    }

    // ------------------------------------------------------------------ tenant network isolation

    /**
     * Tenants other than this VM's that own every network on these VLANs. Empty unless all of it is known: the VM is running where Morpheus
     * says and is not mid-change, its tenant is a customer, and no such network belongs to it or is the operators' to share. The API has no
     * sharing field, so a network the operator owns may be shared, and a VLAN several tenants have networks on is theirs to reach.
     */
    private List<String> otherTenants(Host h, Map vm, Collection<Integer> vlans) {
        if (!vm || vm.power != 'on' || vm.host != h.name || vm.status != 'provisioned' || !vm.tenantId || vm.operator != false) {
            return []
        }
        (vlans.collectMany { Integer v ->
            List<Map> owners = in_.tenantNetworks.findAll { (it.vlan as Integer) == v }
            owners && owners.every { it.ownerId && it.shared == false && it.ownerId != vm.tenantId } ? owners.collect { "${it.owner ?: it.ownerId} on VLAN ${v}".toString() } : []
        } as List<String>).unique()
    }

    /**
     * Ports as the bridge has them against what Morpheus says, joined on the VM's name and the port's MAC together. 1.3 is Open vSwitch and 2.0 a
     * VLAN-filtering linux bridge; the host says which each port is, and HostFacts reads both into one shape. What cannot be compared is
     * said and raises nothing: only a port read directly that disagrees with a known VLAN is a finding, and only otherTenants() makes it critical.
     */
    private void vmPortChecks(List<Host> hs) {
        hs.findAll { it.facts.sections.containsKey('vm_ports') }.each { Host h ->
            List<Map> ports = h.facts.vmPorts
            String cut = h.facts.portsCut ? '; not every VM could be read in time' : ''
            if (!ports) {
                if (h.facts.portsCut) {
                    String why = 'cannot tell: no VM was read, as DLM is stopped, libvirt did not answer or time ran out'
                    add('NET-12', 'VM ports on the VLANs Morpheus says', Severity.HIGH, Status.NOT_CHECKED, h.name, why, 'each port on its network\'s VLAN', 'plugin')
                    add('NET-13', 'No unexpected trunk', Severity.HIGH, Status.NOT_CHECKED, h.name, why, 'one VLAN per port, unless the VM is in trunkAllowedVms', st.source('trunkAllowedVms'))
                }
                return
            }
            vlanMatchCheck(h, ports, cut)
            trunkCheck(h, ports, cut)
        }
    }

    /** Names can repeat across hosts, so the one Morpheus has on this host comes first. */
    private Map vmOf(Host h, Map p) {
        vmRecord(in_.morpheusVms, h.name, p.vm as String)
    }

    private void vlanMatchCheck(Host h, List<Map> ports, String cut) {
        String expected = 'each port on its network\'s VLAN'
        int same = 0
        List<String> unread = []
        ports.each { Map p ->
            Map vm = vmOf(h, p)
            Map nic = (vm?.nics as List<Map>)?.find { (it.mac as String)?.equalsIgnoreCase(p.mac as String) }
            Integer want = nic?.vlan as Integer
            // a network with no VLAN is the bridge's native one, which a filtering bridge holds on VLAN 1
            boolean onNative = p.read && nic && !want && ((p.access == 1 && !p.tagged && !p.all) || p.untagged)
            String reason = onNative ? null : !p.read ? p.why : (!nic ? 'not on a Morpheus record' : (!want ? "network ${nic.network ?: ''} has no VLAN in Morpheus".toString() : (p.untagged ? 'untagged Open vSwitch port, so its VLAN is the uplink\'s native one' : null)))
            if (reason) {
                unread << "${p.vm} ${p.dev}: ${reason}".toString()
            } else if (onNative || p.access == want || want in (p.tagged as List) || p.all) {
                same++
            } else {
                String on = p.access != null ? "VLAN ${p.access}" : "tagged VLANs ${(p.tagged as List).join(', ')}"
                List<String> others = otherTenants(h, vm, p.access != null ? [p.access as Integer] : (p.tagged as List<Integer>))
                add('NET-12', "VM port on the VLAN Morpheus says: ${p.vm}".toString(), others ? Severity.CRITICAL : Severity.HIGH, others ? Status.FAIL : Status.WARN, h.name,
                        "${p.dev} (${p.mac}) is on ${on}; network ${nic.network} is VLAN ${want}${others ? '; that VLAN belongs to ' + others.join(', ') + ', and this VM to ' + (vm.tenant ?: vm.tenantId) : ''}".toString(), expected, 'plugin', null, Tier.NONE,
                        others ? 'This VM can reach another tenant\'s network. Confirm on the host with its bridge\'s VLAN list and the VM\'s definition, then take the port off that VLAN. Find out how it got there.'
                                : 'Find out which is right, as the VM is not on the network its owner thinks. It clears by itself if its network was being changed just now.')
            }
        }
        if (unread && !same) {
            add('NET-12', 'VM ports on the VLANs Morpheus says', Severity.HIGH, Status.NOT_CHECKED, h.name, "cannot tell: ${unread.take(4).join('; ')}${unread.size() > 4 ? '; and ' + (unread.size() - 4) + ' more' : ''}${cut}".toString(), expected, 'plugin')
        } else if (same) {
            add('NET-12', 'VM ports on the VLANs Morpheus says', Severity.HIGH, Status.PASS, h.name, "${same} port(s) match${unread ? '; not compared: ' + unread.take(4).join('; ') : ''}${cut}".toString(), expected, 'plugin')
        }
    }

    private void trunkCheck(Host h, List<Map> ports, String cut) {
        String expected = 'one VLAN per port, unless the VM is in trunkAllowedVms'
        List<String> allowed = (st.val('trunkAllowedVms') ?: []) as List<String>
        List<Map> trunks = ports.findAll { it.read && (it.all || it.tagged) && !(it.vm in allowed) }
        trunks.each { Map p ->
            Map vm = vmOf(h, p)
            List<Integer> vlans = p.all ? (in_.tenantNetworks*.vlan.unique().collect { it as Integer }) : (p.tagged as List<Integer>)
            List<String> others = otherTenants(h, vm, vlans)
            add('NET-13', "No unexpected trunk: ${p.vm}".toString(), others ? Severity.CRITICAL : Severity.HIGH, others ? Status.FAIL : Status.WARN, h.name,
                    "${p.dev} carries ${p.all ? 'every VLAN' : 'tagged VLANs ' + (p.tagged as List).join(', ')}${others ? ', including ' + others.join(', ') + '; this VM is ' + (vm.tenant ?: vm.tenantId) : ''}".toString(), expected, st.source('trunkAllowedVms'), null, Tier.NONE,
                    others ? 'This VM can reach another tenant\'s network by tagging its traffic. Confirm on the host, then take those VLANs off the port.'
                            : 'If this VM should have a trunk, such as a firewall, add it to trunkAllowedVms in the site standards.')
        }
        List<Map> open = ports.findAll { it.untagged && !(it.vm in allowed) }
        // Measured on 1.3: a guest on such a port reached a guest on a tagged VLAN by tagging its traffic. Operators' own VMs may be there by design.
        List<Map> exposed = open.findAll { vmOf(h, it)?.operator == false }
        exposed.each { Map p ->
            Map vm = vmOf(h, p)
            List<String> others = otherTenants(h, vm, in_.tenantNetworks*.vlan.unique().collect { it as Integer })
            add('NET-13', "No unexpected trunk: ${p.vm}".toString(), others ? Severity.CRITICAL : Severity.HIGH, others ? Status.FAIL : Status.WARN, h.name,
                    "${p.dev} has no VLAN tag, so this VM can put its traffic on any VLAN on this bridge${others ? ', including ' + others.join(', ') + '; this VM is ' + (vm.tenant ?: vm.tenantId) : ''}".toString(), expected, st.source('trunkAllowedVms'), null, Tier.NONE,
                    'Open vSwitch treats a port with no tag as a trunk of all VLANs. This is what a network with a blank VLAN or VLAN 0 builds on 1.3. Move this VM to a network with a VLAN.')
        }
        open = open - exposed
        List<Map> unread = ports.findAll { !it.read }
        if (exposed) {
            // the port findings above are the answer for this host
        } else if (!trunks && open) {
            add('NET-13', 'No unexpected trunk', Severity.HIGH, Status.NOT_CHECKED, h.name, "cannot tell for ${open*.dev.join(', ')}: an untagged Open vSwitch port passes any VLAN a guest tags, unless the uplink or a flow stops it${cut}".toString(), expected, st.source('trunkAllowedVms'), null, Tier.NONE,
                    'This is how 1.3 builds a port on a network with no VLAN, so it raises no alarm, and it is not proof of isolation either.')
        } else if (!trunks && unread && unread.size() == ports.size()) {
            add('NET-13', 'No unexpected trunk', Severity.HIGH, Status.NOT_CHECKED, h.name, "cannot tell: ${unread.take(4).collect { "${it.dev}: ${it.why}" }.join('; ')}${cut}".toString(), expected, st.source('trunkAllowedVms'))
        } else if (!trunks) {
            add('NET-13', 'No unexpected trunk', Severity.HIGH, Status.PASS, h.name, "${ports.size() - unread.size()} port(s) read, none trunked${cut}".toString(), expected, st.source('trunkAllowedVms'))
        }
    }

    // ------------------------------------------------------------------ tenant isolation and side channels (full checks only)

    /**
     * The W-TENANT checks, all from the probe's @@sec section and so full-only. Nested-virtualization exposure, the kernel's escape-fix level,
     * the host's side-channel state, the 2.0 bridge STP design, a customer port on an infrastructure VLAN, cache=unsafe, and a fleet note on per-VM limits.
     * Everything here is detect-and-recommend: HPE owns the kernel, the CPU model and the bridge, and nothing changes a host by itself.
     */
    private void tenantSecurityChecks(List<Host> hs) {
        hs.findAll { it.facts.sections.containsKey('sec') }.each { Host h ->
            nestedVirtCheck(h)
            kernelCveCheck(h)
            cpuVulnNote(h)
            bridgeStpNote(h)
            infraVlanCheck(h)
            vmCacheCheck(h)
        }
        limitsNote(hs)
    }

    /** Only a customer's running VM carries a tenant-isolation finding: an operator VM may be built this way on purpose, and one Morpheus does not know is judged by nothing. */
    private boolean isCustomerVm(Host h, String libvirtName) {
        Map vm = vmRecord(in_.morpheusVms, h.name, libvirtName)
        vm?.operator == false && vm.status == 'provisioned' && vm.power == 'on'
    }

    private String vmLabel(Host h, String libvirtName) {
        vmRecord(in_.morpheusVms, h.name, libvirtName)?.label ?: libvirtName
    }

    private static List<Integer> kernelParts(String s) {
        def m = (s ?: '') =~ /(\d+)\.(\d+)\.(\d+)-(\d+)/
        m.find() ? (1..4).collect { m.group(it) as Integer } : null
    }

    /** -1, 0 or 1 comparing two Ubuntu kernels by upstream version and ABI (the -NN that carries the fix); null when either cannot be parsed. */
    static Integer kernelCompare(String a, String b) {
        List<Integer> x = kernelParts(a), y = kernelParts(b)
        if (x == null || y == null) {
            return null
        }
        for (int i = 0; i < 4; i++) {
            int c = x[i] <=> y[i]
            if (c) {
                return c
            }
        }
        0
    }

    /**
     * SEC-01. The gate is the host CPU exposing nested virtualization, not the per-VM "Enable Nested Virtualization" box: without it, even host-passthrough hides VMX/SVM.
     * host-passthrough exposes it when the host's kvm module has nested on and no feature disables it; a named model exposes it only when it requires VMX/SVM. A named
     * model with VMX disabled is not a finding. Built from HPE docs and one hand-edited VM, not seen as a Morpheus default.
     */
    private void nestedVirtCheck(Host h) {
        HostFacts f = h.facts
        boolean amd = f.nestedAmd != null && f.nestedIntel == null   // the loaded kvm module is the host's architecture
        Boolean nested = amd ? f.nestedAmd : f.nestedIntel
        String feat = amd ? 'SVM' : 'VMX'
        List<Map> customer = f.vmSec.findAll { isCustomerVm(h, it.vm as String) }
        if (!customer) {
            return
        }
        List<String> exposed = [], unsure = []
        customer.each { Map v ->
            String policy = amd ? v.svm : v.vmx
            if (policy == 'disable') {
                return   // explicitly disabled: the HPE-template case, never a finding
            }
            if (v.cpuMode == 'host-passthrough') {
                if (nested == Boolean.TRUE) {
                    exposed << vmLabel(h, v.vm as String)
                } else if (nested == null) {
                    unsure << vmLabel(h, v.vm as String)
                }
            } else if (policy in ['require', 'force']) {
                exposed << vmLabel(h, v.vm as String)   // a running named-model VM that requires it is presenting it to the guest
            }
        }
        String expected = "customer VMs do not expose ${feat} to their guests".toString()
        if (exposed) {
            add('SEC-01', 'Customer VM exposes CPU virtualization to its guest', Severity.HIGH, Status.WARN, h.name,
                    "${exposed.unique().sort().join(', ')}".toString(), expected, 'plugin', null, Tier.NONE,
                    "A customer VM presenting ${feat} to its guest can run a nested hypervisor, which widens the guest-to-host attack surface; see SEC-02 for whether the kernel carries the escape fix. The real control is the cluster CPU Model, not the per-VM box: a named model with ${feat} disabled removes this. Built from HPE docs and one hand-edited VM, not a Morpheus default. Operators only; raise the cluster CPU Model with HPE.")
        } else if (unsure) {
            add('SEC-01', 'Customer VM exposes CPU virtualization to its guest', Severity.HIGH, Status.NOT_CHECKED, h.name,
                    "cannot tell for ${unsure.unique().sort().join(', ')}: host-passthrough, and the kvm nested parameter could not be read".toString(), expected, 'plugin')
        } else {
            add('SEC-01', 'Customer VM exposes CPU virtualization to its guest', Severity.HIGH, Status.PASS, h.name,
                    "${customer.size()} customer VM(s), none exposing ${feat}".toString(), expected, 'plugin')
        }
    }

    /** SEC-02. The running kernel against the layout's known escape-fix level, failing only where nesting is actually exposed. A FAIL nobody can clear if HPE has not published the kernel, so the text says to ask HPE. */
    private void kernelCveCheck(Host h) {
        HostFacts f = h.facts
        String min = st.val('kernelCveMin') as String
        String expected = min ? "at or above ${min}".toString() : 'a kernel carrying the KVM escape fix'
        if (!min) {
            add('SEC-02', 'Kernel carries the KVM guest-to-host escape fix', Severity.HIGH, Status.NOT_CHECKED, h.name, 'cannot tell: no fixed kernel version is set for this layout', expected, st.source('kernelCveMin'))
            return
        }
        Integer cmp = kernelCompare(f.kernel, min)
        if (cmp == null) {
            add('SEC-02', 'Kernel carries the KVM guest-to-host escape fix', Severity.HIGH, Status.NOT_CHECKED, h.name, "cannot tell: could not read the kernel version (${f.kernel ?: 'none'})".toString(), expected, st.source('kernelCveMin'))
            return
        }
        boolean nestedOn = f.nestedIntel == Boolean.TRUE || f.nestedAmd == Boolean.TRUE || !f.vmNoMigrate.isEmpty()
        String tail = "; ${nestedOn ? 'nested virtualization is exposed on this host' : 'nested virtualization appears off here'}".toString()
        add('SEC-02', 'Kernel carries the KVM guest-to-host escape fix', Severity.HIGH, cmp >= 0 ? Status.PASS : (nestedOn ? Status.FAIL : Status.WARN), h.name,
                "${f.kernel}${cmp >= 0 ? '' : tail}".toString(), expected, st.source('kernelCveMin'), null, Tier.NONE,
                cmp >= 0 ? null : "This kernel is below the level that fixes the 2026 KVM guest-to-host escapes (Januscape, Zapscape). ${nestedOn ? 'Nesting is exposed here, so an untrusted guest is one module load from the host.' : 'Nesting looks off, which limits the exposure.'} Ask HPE which HVM OS kernel carries the fix; do not patch outside HPE's workflow. The ABI level is unverified against HPE's build, so set kernelCveMin if yours differs.".toString())
    }

    /** SEC-03. Detection only, no exploit: the host's own side-channel mitigation state and SMT, for an operator to read. HPE owns the kernel, microcode and SMT policy, so this raises nothing. */
    private void cpuVulnNote(Host h) {
        HostFacts f = h.facts
        if (!f.cpuVulns && f.smtActive == null) {
            return
        }
        List<String> vuln = f.cpuVulns.findAll { k, v -> v?.toLowerCase()?.contains('vulnerable') }.collect { k, v -> "${k}: ${v}".toString() }.sort()
        String smt = f.smtActive == null ? 'SMT state unknown' : "SMT ${f.smtActive.trim() == '1' ? 'on' : 'off'}".toString()
        add('SEC-03', 'CPU side-channel mitigations (for information)', Severity.LOW, Status.NA, h.name,
                (vuln ? "${vuln.size()} marked Vulnerable: ${vuln.take(6).join('; ')}${vuln.size() > 6 ? '; ...' : ''}".toString() : 'none marked Vulnerable') + "; ${smt}".toString(), 'detection only', 'plugin', null, Tier.NONE,
                'Detection only, no exploit. HPE owns the kernel, microcode and SMT policy; this surfaces the host\'s own reported state for an operator. SEC-02 is the actionable kernel-fix level.')
    }

    /** SEC-04. The 2.0 VLAN-filtering bridges' STP state, a note to raise with HPE: either setting has an isolation or outage risk, and HPE does not document which it chooses. */
    private void bridgeStpNote(Host h) {
        if (in_.layout?.profile != '2.0') {
            return   // 1.3 is Open vSwitch; NET-13 covers its trunking
        }
        List<Map> vmBridges = h.facts.secBridges.findAll { it.filtering == true }
        if (!vmBridges) {
            return
        }
        add('SEC-04', '2.0 bridge STP design (for information)', Severity.LOW, Status.NA, h.name,
                vmBridges.collect { "${it.bridge}: STP ${it.stp == null ? 'unknown' : (it.stp ? 'on' : 'off')}".toString() }.join(', '), 'raise the STP design with HPE', 'plugin', null, Tier.NONE,
                'With STP on, a guest can send superior BPDUs and take the root role on this VLAN-aware bridge; with it off, guest BPDUs reach the uplink, where a switch\'s BPDU guard can err-disable the host\'s trunk. HPE documents neither. Do not change STP on a live bridge: it can drop corosync. Raise the design with HPE.')
    }

    /** SEC-05. A customer VM port sharing a VLAN with host infrastructure. The storage VLAN is the dangerous one: a guest there could try to reach the LUN that holds every tenant's disks. Built from docs. */
    private void infraVlanCheck(Host h) {
        HostFacts f = h.facts
        List<Integer> infra = f.infraVlans*.vlan.unique() as List<Integer>
        List<Map> ports = f.vmPorts.findAll { it.read && isCustomerVm(h, it.vm as String) }
        if (!infra || !ports) {
            return   // an untagged infrastructure interface gives nothing to compare, and no customer port means nothing of ours here
        }
        boolean any = false
        ports.each { Map p ->
            List<Integer> vlans = (([p.access] + (p.tagged ?: [])).findAll { it != null }) as List<Integer>
            List<Integer> hit = vlans.findAll { it in infra }
            if (hit) {
                any = true
                List<String> roles = f.infraVlans.findAll { it.vlan in hit }*.role.unique()
                add('SEC-05', "Customer VM on a host ${roles.contains('storage') ? 'storage' : 'infrastructure'} VLAN: ${vmLabel(h, p.vm as String)}".toString(), roles.contains('storage') ? Severity.HIGH : Severity.MEDIUM, Status.WARN, h.name,
                        "${p.dev} is on VLAN ${hit.join(', ')}, which also carries the host's ${roles.join(' and ')} traffic".toString(), 'customer ports off the host storage and cluster VLANs', 'plugin', null, Tier.NONE,
                        "This customer VM shares a VLAN with host ${roles.join(' and ')} traffic. On the storage VLAN a guest could try to reach the LUN that holds every tenant's disks. Confirm on the host, then move the VM off that VLAN. Built from HPE docs, not seen live. Operators only.".toString())
            }
        }
        if (!any) {
            add('SEC-05', 'Customer VM on a host infrastructure VLAN', Severity.MEDIUM, Status.PASS, h.name, "${ports.size()} customer port(s), none on a host infrastructure VLAN".toString(), 'customer ports off the host storage and cluster VLANs', 'plugin')
        }
    }

    /** VM-07. cache=unsafe tells QEMU to drop the guest's flushes, so a host crash can lose data the guest believed written: the one cache mode that is wrong on a clustered datastore. */
    private void vmCacheCheck(Host h) {
        List<Map> seen = h.facts.vmSec.findAll { (it.caches as List)?.contains('unsafe') }
        if (seen) {
            add('VM-07', 'No VM disk ignores guest flushes', Severity.HIGH, Status.FAIL, h.name,
                    "cache=unsafe: ${seen.collect { vmLabel(h, it.vm as String) }.unique().sort().join(', ')}".toString(), 'no disk on cache=unsafe', 'plugin', null, Tier.NONE,
                    'A disk with cache=unsafe ignores the guest\'s flushes, so a host crash can lose data the guest believed was written. It is the one cache mode that is wrong on a clustered datastore. Reconfigure the VM off cache=unsafe. A CD-ROM on cache=unsafe would read here too, so confirm it is a data disk.')
        } else if (h.facts.vmSec) {
            add('VM-07', 'No VM disk ignores guest flushes', Severity.HIGH, Status.PASS, h.name, "${h.facts.vmSec.size()} VM(s), none on cache=unsafe".toString(), 'no disk on cache=unsafe', 'plugin')
        }
    }

    /** SEC-06. Morpheus documents almost no per-VM limit, so this is universally true today: one fleet note, never an alarm per VM. */
    private void limitsNote(List<Host> hs) {
        List<Map> customerVms = hs.findAll { it.facts.sections.containsKey('sec') }.collectMany { Host h -> h.facts.vmSec.findAll { isCustomerVm(h, it.vm as String) } }
        if (!customerVms) {
            return
        }
        int limited = customerVms.count { it.limited }
        add('SEC-06', 'Per-VM resource limits', Severity.LOW, Status.NA, null,
                "${customerVms.size()} running customer VM(s), ${limited} with a per-VM I/O, CPU or network limit set".toString(), 'a fleet note, not an alarm', 'plugin', null, Tier.NONE,
                'HVM documents almost no per-VM disk, CPU or network limit, so fairness on a shared datastore or uplink rests on the array and the host, not on Morpheus. This is one fleet note, never an alarm per VM. Raise plan-level I/O and bandwidth limits with HPE.')
    }

    // ------------------------------------------------------------------ what a host is short of

    /** Only for a host whose probe had these sections. None of it touches a datastore, so a frozen host still answers. */
    private void hostLoadChecks(List<Host> hs) {
        hs.findAll { it.facts.sections.containsKey('host_health') }.each { Host h ->
            oomCheck(h)
            swapCheck(h)
            cpuPressureCheck(h)
            memoryPressureCheck(h)
            rootDiskCheck(h)
            vmCrashCheck(h)
        }
    }

    private void oomCheck(Host h) {
        String title = 'No process killed for lack of memory'
        if (!h.facts.oomRead) {
            add('HST-04', title, Severity.HIGH, Status.NOT_CHECKED, h.name, 'cannot tell: the kernel log could not be read', 'none in 24 h', 'plugin')
            return
        }
        List<Map> kills = h.facts.oomKills
        boolean vm = kills.any { it.qemu }
        add('HST-04', title, Severity.HIGH, vm ? Status.FAIL : (kills ? Status.WARN : Status.PASS), h.name,
                kills ? "${kills.size()} in 24 h: ${kills.groupBy { it.comm }.collect { k, v -> "${k} (${v.size()})" }.join(', ')}".toString() : 'none in 24 h', 'none in 24 h', 'plugin', null, Tier.NONE,
                vm ? 'The kernel killed a VM to free memory, so this host is overcommitted. VM-06 names it. Start it on a host with room, and move others off before it happens again.'
                        : (kills ? 'The kernel ran out of memory here, and the next one it picks can be a VM or a cluster daemon. Find what grew.' : null))
    }

    private void swapCheck(Host h) {
        String title = 'Host not swapping'
        BigDecimal rate = h.facts.swapPerSec
        String expected = "< ${st.intVal('swapWarnPagesPerSec')} pages per second".toString()
        if (rate == null) {
            add('HST-05', title, Severity.MEDIUM, Status.NOT_CHECKED, h.name, 'cannot tell: the swap counters could not be read', expected, st.source('swapWarnPagesPerSec'))
            return
        }
        boolean over = rate >= st.intVal('swapWarnPagesPerSec')
        add('HST-05', title, Severity.MEDIUM, over ? Status.WARN : Status.PASS, h.name, "${rate} pages per second over 2 s".toString(), expected, st.source('swapWarnPagesPerSec'), null, Tier.NONE,
                over ? 'A VM whose memory is on swap stalls on every page it touches. Move VMs off, and see HST-07.' : null)
    }

    private void cpuPressureCheck(Host h) {
        String title = 'CPU not under pressure'
        int warn = st.intVal('cpuPressureWarnPct'), fail = st.intVal('cpuPressureFailPct')
        BigDecimal psi = h.facts.psiCpu
        String steal = h.facts.stealPct == null ? 'CPU steal not read' : "CPU steal ${h.facts.stealPct}%"
        String expected = "< ${warn}% of the last minute".toString()
        if (psi == null) {
            add('HST-06', title, Severity.MEDIUM, Status.NOT_CHECKED, h.name, "cannot tell: this kernel offers no /proc/pressure/cpu; ${steal}".toString(), expected, st.source('cpuPressureWarnPct'))
            return
        }
        boolean stolen = h.facts.stealPct != null && h.facts.stealPct >= 5
        add('HST-06', title, Severity.MEDIUM, psi >= fail ? Status.FAIL : (psi >= warn ? Status.WARN : Status.PASS), h.name, "a task waited for CPU ${psi}% of the last minute; ${steal}".toString(), expected, st.source('cpuPressureWarnPct'), null, Tier.NONE,
                psi >= warn ? (stolen ? 'Steal this high means whatever runs this host is taking its CPU, so look there first.' : 'See which VMs are busiest on this host, and whether vCPUs are overcommitted.') : null)
    }

    private void memoryPressureCheck(Host h) {
        String title = 'Memory not under pressure'
        int warn = st.intVal('memPressureWarnPct'), fail = st.intVal('memPressureFailPct')
        BigDecimal psi = h.facts.psiMemory
        String expected = "< ${warn}% of the last minute".toString()
        if (psi == null) {
            add('HST-07', title, Severity.MEDIUM, Status.NOT_CHECKED, h.name, 'cannot tell: this kernel offers no /proc/pressure/memory', expected, st.source('memPressureWarnPct'))
            return
        }
        add('HST-07', title, Severity.MEDIUM, psi >= fail ? Status.FAIL : (psi >= warn ? Status.WARN : Status.PASS), h.name, "a task waited for memory ${psi}% of the last minute".toString(), expected, st.source('memPressureWarnPct'), null, Tier.NONE,
                psi >= warn ? 'Every VM here slows down while the host reclaims memory, and the next step is HST-04. Move VMs off.' : null)
    }

    /** What the probe read is judged, so a bad filesystem still shows beside one it could not read. Nothing bad and something unread is not a pass. */
    private void rootDiskCheck(Host h) {
        String title = 'Room on the host\'s own disks'
        int warn = st.intVal('rootDiskWarnPct'), fail = st.intVal('rootDiskFailPct')
        String expected = "< ${warn}% of space and inodes".toString()
        List<Map> read = h.facts.rootFs.findAll { it.pct != null || it.inodePct != null }
        if (!read) {
            add('HST-08', title, Severity.HIGH, Status.NOT_CHECKED, h.name, 'cannot tell: df did not answer', expected, st.source('rootDiskWarnPct'))
            return
        }
        BigDecimal worst = read.collectMany { [it.pct, it.inodePct] }.findAll { it != null }.max()
        String seen = read.collect { Map d -> "${d.target}: ${d.pct == null ? 'no space figure' : d.pct + '% space'}, ${d.inodePct == null ? 'no inode limit' : d.inodePct + '% inodes'}" }.join('; ')
        Status status = worst >= fail ? Status.FAIL : (worst >= warn ? Status.WARN : (h.facts.rootFsCut ? Status.NOT_CHECKED : Status.PASS))
        add('HST-08', title, Severity.HIGH, status, h.name, h.facts.rootFsCut ? "${seen}; df did not finish, so not every path was read".toString() : seen, expected, st.source('rootDiskWarnPct'), null, Tier.NONE,
                worst >= warn ? 'A host with a full disk stops logging, and the agent and libvirt can fail with it, leaving it unmanageable. Clear space before it fills.' : null)
    }

    private String crashTime(long seconds) {
        java.text.SimpleDateFormat f = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm 'UTC'")
        f.timeZone = TimeZone.getTimeZone('UTC')
        f.format(new Date(seconds * 1000L))
    }

    private static List<Map> recentCrashes(HostFacts f, long nowMs) {
        long now = f.now ?: (nowMs / 1000L) as long
        f.vmCrashes.findAll { it.at != null && now - (it.at as long) <= 86400L }
    }

    /** The libvirt names of the VMs VM-06 reports on this host, whatever room its text has. For the availability figures. */
    static Set<String> stoppedVms(HostFacts f, long nowMs) {
        (recentCrashes(f, nowMs)*.vm + f.oomKills.findAll { it.qemu && it.vm }*.vm) as Set<String>
    }

    /** The same VMs, each with the times it stopped, in ms: none for a kill whose line had no time. */
    static Map<String, List<Long>> stopTimes(HostFacts f, long nowMs) {
        Map<String, List<Long>> out = stoppedVms(f, nowMs).collectEntries { [(it): []] }
        (recentCrashes(f, nowMs) + f.oomKills.findAll { it.qemu && it.vm && it.at }).each { out[it.vm as String] << ((it.at as long) * 1000L) }
        out
    }

    /** VMs by Morpheus's name where it has one. A log touched today can still hold an older crash, so the line's own time decides. */
    private void vmCrashCheck(Host h) {
        String title = 'No VM crashed or killed for lack of memory'
        String expected = 'none in 24 h'
        HostFacts f = h.facts
        Closure<String> label = { String vm -> vmRecord(in_.morpheusVms, h.name, vm)?.label ?: vm }
        Map<String, String> found = [:]
        recentCrashes(f, in_.nowMs).each { found[it.vm as String] = "${label(it.vm as String)} crashed at ${crashTime(it.at as long)}".toString() }
        List<Map> killed = f.oomKills.findAll { it.qemu }
        killed.findAll { it.vm }.each { found[it.vm as String] = "${label(it.vm as String)} killed for lack of memory${it.at ? ' at ' + crashTime(it.at as long) : ''}".toString() }
        List<String> seen = found.values() as List<String>
        int unnamed = killed.count { !it.vm }
        if (unnamed && !found) {
            seen << "${unnamed} VM(s) killed for lack of memory; the kernel log did not name them".toString()
        }
        List<String> undated = f.vmCrashes.findAll { it.at == null }*.vm.unique().collect { label(it as String) }
        if (seen) {
            add('VM-06', title, Severity.MEDIUM, Status.WARN, h.name, seen.take(8).join('; ') + (seen.size() > 8 ? "; and ${seen.size() - 8} more" : ''), expected, 'plugin', null, Tier.NONE,
                    'Its guest stopped without warning, so its owner may need to know. HST-04 shows whether this host ran out of memory. Otherwise look in the VM\'s log under /var/log/libvirt/qemu.')
        } else if (undated) {
            add('VM-06', title, Severity.MEDIUM, Status.NOT_CHECKED, h.name, "cannot tell: a crash line with a time that could not be read, for ${undated.take(4).join(', ')}".toString(), expected, 'plugin')
        } else if (!f.vmLogsRead || !f.oomRead) {
            add('VM-06', title, Severity.MEDIUM, Status.NOT_CHECKED, h.name, "cannot tell: ${f.vmLogsRead ? 'the kernel log' : 'libvirt\'s VM logs'} could not be read".toString(), expected, 'plugin')
        } else {
            add('VM-06', title, Severity.MEDIUM, Status.PASS, h.name, expected, expected, 'plugin')
        }
    }

    // ------------------------------------------------------------------ host OS: patching, kernel posture, network timing (full check only)

    /**
     * Can an automatic update reach this host and restart corosync or dlm? The danger is a fleet-wide 06:00 restart. The safe case is an
     * HVM-sourced host whose needrestart drop-in leaves the cluster stack alone. All read-only; the plugin never changes apt or needrestart.
     */
    private void patchExposureChecks(List<Host> hs) {
        hs.findAll { it.facts.sections.containsKey('patch_exposure') }.each { Host h ->
            HostFacts f = h.facts
            String title = 'Automatic updates cannot restart the cluster stack'
            String expected = 'no stock-Ubuntu auto-upgrade, or the cluster daemons exempt from needrestart'
            boolean exempt = f.nrListOnly || f.nrClusterExempt > 0
            boolean uuActive = f.uuInstalled && f.uuPeriodic?.isInteger() && (f.uuPeriodic as int) > 0 && !(f.uuTimer in ['disabled', 'masked'])
            boolean stock = f.aptUbuntuSources > 0
            String found = "unattended-upgrades ${f.uuInstalled ? 'installed' : 'not installed'}${f.uuInstalled ? ", periodic=${f.uuPeriodic ?: 'unset'}, timer ${f.uuTimer ?: 'unknown'}" : ''}; ${stock ? f.aptUbuntuSources + ' stock Ubuntu source(s)' : 'HPE sources only'}; needrestart ${f.nrInstalled ? 'installed' : 'not installed'}${exempt ? ', cluster stack exempt' : (f.nrInstalled ? ', no cluster-stack exemption' : '')}".toString()
            if (stock && uuActive && !exempt) {
                add('PKG-04', title, Severity.HIGH, Status.WARN, h.name, found, expected, 'plugin', null, Tier.NONE,
                        'This host takes updates from Ubuntu\'s archive, unattended-upgrades is on, and needrestart may restart corosync or dlm after a library update. On a GFS2 member that stalls every datastore, and it fires on every host inside one 06:00 window. Raise with HPE; until then patch through maintenance or add a needrestart list-only drop-in. The plugin changes nothing.')
            } else {
                add('PKG-04', title, Severity.HIGH, Status.PASS, h.name, found, expected, 'plugin', null, Tier.NONE,
                        exempt ? null : 'A package upgrade can still restart corosync or dlm itself (PKG-01, PKG-03): patch one host at a time in a maintenance window, per HPE.')
            }
        }
    }

    /** What makes a sick node die rather than hang. Read-only sysctls, taint, the GFS2 mount error policy and PCIe AER. These are HPE's to own; the plugin changes none of them. */
    private void kernelPostureChecks(List<Host> hs) {
        hs.findAll { it.facts.sections.containsKey('kernel_posture') }.each { Host h ->
            panicCheck(h)
            taintCheck(h)
            aerCheck(h)
        }
    }

    private void panicCheck(Host h) {
        HostFacts f = h.facts
        String title = 'Panic settings safe for a clustered host'
        String expected = 'hung_task_panic off; kernel.panic 0; errors=withdraw'
        Long hung = f.panicSysctls['hung_task_panic']
        Long soft = f.panicSysctls['softlockup_panic']
        Long panic = f.panicSysctls['panic']
        boolean errorsPanic = f.gfs2MountOpts.any { (it.options as String)?.tokenize(',')?.contains('errors=panic') }
        if (f.panicSysctls.isEmpty()) {
            add('KRN-01', title, Severity.HIGH, Status.NOT_CHECKED, h.name, 'cannot tell: the panic sysctls could not be read', expected, 'plugin')
            return
        }
        List<String> warns = []
        if (soft != null && soft != 0L) { warns << 'softlockup_panic set' }
        if (panic != null && panic > 0L) { warns << "kernel.panic=${panic}".toString() }
        if (errorsPanic) { warns << 'errors=panic on a GFS2 mount' }
        List<String> seen = f.panicSysctls.collect { k, v -> "${k}=${v}".toString() }
        if (errorsPanic) { seen << 'errors=panic' }
        Status status = hung != null && hung != 0L ? Status.FAIL : (warns ? Status.WARN : Status.PASS)
        add('KRN-01', title, Severity.HIGH, status, h.name, seen.join(', '), expected, 'plugin', null, Tier.NONE,
                status == Status.FAIL ? 'hung_task_panic is set, so a normal fence wait on a frozen datastore could panic every surviving host at once, taking the whole cluster down from one host failure. Ask HPE to clear it; the plugin does not change sysctls.'
                        : (warns ? "Review with HPE: ${warns.join('; ')}. A non-zero kernel.panic reboots a panicked host, which can clear a fence wait by a clean rejoin, but reboot-loops on a recurring fault; softlockup_panic and errors=panic turn a survivable event into a crash. HPE owns these; the plugin does not change them.".toString() : null))
    }

    private void taintCheck(Host h) {
        String title = 'Kernel not tainted by a fault'
        String expected = 'no fault bits'
        Long t = h.facts.taint
        if (t == null) {
            add('KRN-02', title, Severity.MEDIUM, Status.NOT_CHECKED, h.name, 'cannot tell: /proc/sys/kernel/tainted could not be read', expected, 'plugin')
            return
        }
        Map<Long, String> bits = [(32L): 'a bad page', (128L): 'an oops or BUG', (16L): 'a machine check', (16384L): 'a soft lockup']
        List<String> fail = [32L, 128L].findAll { (t & it) != 0L }.collect { bits[it] }
        List<String> warn = [16L, 16384L].findAll { (t & it) != 0L }.collect { bits[it] }
        Status status = fail ? Status.FAIL : (warn ? Status.WARN : Status.PASS)
        add('KRN-02', title, Severity.MEDIUM, status, h.name, fail || warn ? "tainted ${t}: ${(fail + warn).join(', ')}".toString() : "tainted ${t}".toString(), expected, 'plugin', null, Tier.NONE,
                fail || warn ? 'The kernel marked itself after a fault, and the bit holds until reboot, so this host has already hit something. A crash from it freezes every GFS2 datastore. Find the cause in the kernel log and plan a reboot. Proprietary and out-of-tree module bits are ignored here.' : null)
    }

    private void aerCheck(Host h) {
        HostFacts f = h.facts
        String title = 'No PCIe bus errors'
        if (f.aerFatal == null && f.aerNonfatal == null && f.aerCorrectable == null) {
            add('KRN-03', title, Severity.MEDIUM, Status.NOT_CHECKED, h.name, 'cannot tell: no PCIe AER counters here (firmware-first platforms may expose none)', 'none', 'plugin')
            return
        }
        long fatal = f.aerFatal ?: 0L, nonfatal = f.aerNonfatal ?: 0L, corr = f.aerCorrectable ?: 0L
        boolean bad = fatal > 0 || nonfatal > 0
        add('KRN-03', title, Severity.MEDIUM, bad ? Status.WARN : Status.PASS, h.name,
                "fatal ${fatal}, non-fatal ${nonfatal}, correctable ${corr} since boot".toString(), 'none', 'plugin', null, Tier.NONE,
                bad ? 'A device on the PCIe bus is reporting errors, which can mean a NIC or HBA failing under iSCSI or corosync load. Find it in the kernel log (PCIe Bus Error) and plan a replacement. Correctable errors alone are shown for context, not alarmed, as the counter only grows.' : null)
    }

    /** Bond failover timing, link-flap counters, and 2.0 bridge VLAN filtering. Read-only; changing a bond or a bridge is HPE's, in maintenance. */
    private void netTimingChecks(List<Host> hs) {
        int flapWarn = st.intVal('linkFlapWarnPerHour')
        hs.findAll { it.facts.sections.containsKey('net_timing') }.each { Host h ->
            bondTimingCheck(h)
            linkFlapCheck(h, flapWarn)
            if (in_.layout?.version == '2.0') {
                bridgeFilterCheck(h)
            }
        }
    }

    private static final Set<String> SLOW_BOND_MODES = ['balance-rr', 'balance-xor', 'balance-tlb', 'balance-alb'] as Set

    private void bondTimingCheck(Host h) {
        h.facts.bondTiming.each { Map b ->
            String title = "Bond fails over fast enough for corosync: ${b.name}".toString()
            List<String> why = []
            if (b.mode == '802.3ad' && b.lacpRate == 'slow') { why << 'LACP rate slow (up to 90 s to fail over)' }
            if ((b.miimon == null || b.miimon == 0L) && (b.arpInterval == null || b.arpInterval == 0L)) { why << 'no link monitoring (miimon and ARP both off)' }
            if (b.mode in SLOW_BOND_MODES) { why << "mode ${b.mode} is not advised with corosync".toString() }
            String seen = "mode ${b.mode ?: '?'}, miimon ${b.miimon ?: 0} ms, up/down ${b.updelay ?: 0}/${b.downdelay ?: 0} ms${b.lacpRate ? ', LACP ' + b.lacpRate : ''}".toString()
            add('NET-17', title, Severity.MEDIUM, why ? Status.WARN : Status.PASS, h.name, why ? "${seen}; ${why.join('; ')}".toString() : seen,
                    'fast failover: MII polling on, LACP fast, an active-backup or 802.3ad mode', 'plugin', null, Tier.NONE,
                    why ? 'A slow bond takes longer to notice a dead link than corosync takes to evict the host, so a cable fault can cost cluster membership and freeze GFS2. Change bond timing only on a host in maintenance, through hvmcli, with both switch ports set for LACP fast where that is the fix. The plugin changes nothing.' : null)
        }
    }

    private void linkFlapCheck(Host h, int warnPerHour) {
        List<Map> flaps = h.facts.linkFlaps.findAll { it.changes != null }
        String title = 'Network links are not flapping'
        String expected = "< ${warnPerHour} carrier changes per hour".toString()
        if (!flaps) {
            add('NET-18', title, Severity.MEDIUM, Status.NOT_CHECKED, h.name, 'cannot tell: no carrier counters could be read', expected, st.source('linkFlapWarnPerHour'))
            return
        }
        Long upSec = h.facts.now != null && h.facts.boot != null ? h.facts.now - h.facts.boot : null
        BigDecimal hours = upSec != null && upSec > 0 ? ([upSec / 3600G, 1G].max() as BigDecimal) : 1G
        Closure<BigDecimal> perHour = { Map m -> ((m.changes as Long) / hours).setScale(1, java.math.RoundingMode.HALF_UP) }
        List<Map> over = flaps.findAll { perHour(it) >= warnPerHour }
        add('NET-18', title, Severity.MEDIUM, over ? Status.WARN : Status.PASS, h.name,
                over ? over.collect { "${it.name}=${perHour(it)}/h (${it.changes} since boot)" }.join(', ')
                     : "${flaps.size()} NIC(s), most ${flaps.collect { perHour(it) }.max()} per hour".toString(),
                expected, st.source('linkFlapWarnPerHour'), null, Tier.NONE,
                over ? 'A link going up and down drops cluster and storage traffic each time, while reading up whenever the check samples. Find the optic, cable or switch port. This is a rate since boot, so a short recent burst on a long-running host is diluted.' : null)
    }

    private void bridgeFilterCheck(Host h) {
        h.facts.bridges.each { Map br ->
            String name = br.name as String
            String title = "Bridge filters VLANs: ${name}".toString()
            String stp = br.stpState == '1' ? ', STP on' : ''
            if (br.vlanFiltering == null) {
                add('NET-19', title, Severity.HIGH, Status.NOT_CHECKED, h.name, "cannot tell: vlan_filtering could not be read for ${name}".toString(), 'VLAN filtering on', 'plugin')
                return
            }
            if (br.vlanFiltering != '0') {
                add('NET-19', title, Severity.HIGH, Status.PASS, h.name, "vlan_filtering on${stp}".toString(), 'VLAN filtering on', 'plugin')
                return
            }
            // A per-VLAN bridge carries one VLAN in its name (e.g. hvmbr0.60), so filtering off does not cross tenants: isolation is the separate bridge. Seen as the real HVM 2.0 layout on a host.
            if (name ==~ /.*\.\d+/) {
                add('NET-19', title, Severity.LOW, Status.NA, h.name, "vlan_filtering off${stp}; single-VLAN bridge".toString(), 'VLAN filtering on, or a single-VLAN bridge', 'plugin', null, Tier.NONE,
                        'This is a per-VLAN bridge (one bridge for one VLAN), so VLAN filtering off does not cross tenants: the isolation is the separate bridge, not the filter. HVM 2.0 can instead use one VLAN-aware bridge with filtering on. Confirm the intended 2.0 bridge model with HPE; the plugin changes nothing.')
                return
            }
            add('NET-19', title, Severity.HIGH, Status.WARN, h.name, "vlan_filtering off${stp}".toString(), 'VLAN filtering on', 'plugin', null, Tier.NONE,
                    'With VLAN filtering off on a bridge that carries more than one VLAN, a VM on it can put its traffic on any VLAN the bridge carries, so tenant isolation is gone. HPE\'s 2.0 virtswitch sets filtering on for the VLAN-aware bridge. A per-VLAN bridge usually carries its VLAN in the name; this one does not, so confirm the 2.0 bridge model with HPE. The plugin does not change the bridge.')
        }
    }

    // ------------------------------------------------------------------ busiest VMs

    private static BigDecimal iops(Map a) { (a.readIops as BigDecimal) + (a.writeIops as BigDecimal) }

    private static Map vmRecord(List<Map> vms, String host, String vm) {
        vms.find { it.name == vm && it.host == host } ?: vms.find { it.name == vm }
    }

    /**
     * For the tab, operators only, and never in a report: it names tenants. [host, vm, tenant, cpuPct, readIops, writeIops, readMBs, writeMBs], the five busiest VMs
     * per host that sampled, by share of the host's I/O plus share of its CPU, so a VM heavy in either shows. cpuPct is of the whole host, not of one CPU.
     */
    static List<Map> busiest(Input in_) {
        in_.hosts.collectMany { Host h ->
            List<Map> all = h.facts?.vmActivity ?: []
            BigDecimal io = (all.sum { iops(it) } ?: 0G) as BigDecimal
            BigDecimal cpu = (all.sum { (it.cpuPct ?: 0G) as BigDecimal } ?: 0G) as BigDecimal
            Closure<BigDecimal> share = { Map a -> (io ? iops(a) / io : 0G) + (cpu ? ((a.cpuPct ?: 0G) as BigDecimal) / cpu : 0G) }
            all.findAll { share(it) > 0 }.sort(false) { a, b -> share(b) <=> share(a) ?: (a.vm as String) <=> (b.vm as String) }.take(5).collect { Map a ->
                Map vm = vmRecord(in_.morpheusVms, h.name, a.vm as String)
                [host: h.name, vm: vm?.label ?: a.vm, tenant: vm?.tenant, cpuPct: a.cpuPct, readIops: a.readIops, writeIops: a.writeIops, readMBs: a.readMBs, writeMBs: a.writeMBs]
            }
        } as List<Map>
    }

    /** Names a VM and never its tenant. Empty when the probe did not sample, or nothing was doing I/O. */
    private String busiestHint(Host h) {
        Map top = h.facts.vmActivity.findAll { iops(it) > 0 }.max { iops(it) }
        if (!top) {
            return ''
        }
        " Busiest VM on this host: ${vmRecord(in_.morpheusVms, h.name, top.vm as String)?.label ?: top.vm}, ${iops(top)} IOPS.".toString()
    }

    // ------------------------------------------------------------------ maximum frame size

    /**
     * What the switch advertises against what the host sends. Only a frame size below the MTU is raised: vendors differ on whether
     * they count headers, so equal may or may not fit, and NET-09 measures that. A switch that advertises nothing gets no row.
     */
    private void frameSizeChecks(List<Host> hs) {
        hs.each { Host h ->
            h.facts.neighbours.each { String ifc, Map n ->
                Integer mfs = (n.mfs as String)?.isInteger() ? n.mfs as Integer : null
                Integer mtu = h.facts.interfaces.find { it.name == ifc }?.mtu as Integer
                if (mfs && mtu) {
                    add('NET-14', "Switch takes this uplink's frames: ${ifc}".toString(), Severity.LOW, mfs < mtu ? Status.WARN : Status.PASS, h.name,
                            "switch advertises ${mfs}, host MTU ${mtu}".toString(), 'advertised frame size at least the MTU', 'plugin', null, Tier.NONE,
                            mfs < mtu ? 'A hint, from what the switch says of itself: large frames may be dropped here. NET-09 and a VLAN test measure the real path.' : null)
                }
            }
        }
    }
}
