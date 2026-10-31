package org.hvmhealth.model

/** What one host's read-only probe (scripts/health-probe.sh) reported, parsed. Missing data stays null or empty. */
class HostFacts {
    Map<String, List<String>> sections = [:]
    boolean complete

    String hostname, kernel, osVersion, mode, hvmcli, ovsBridges, bmcIp, watchdog, corosyncKey, ringDev, ringAddr
    Long now, boot, dOldest
    BigDecimal load1
    int nproc, dCount, hung1h, stateLock15m, agentKills15m, sessions, errors24h, virtswitchFiles
    boolean rebootRequired, fsSafe, fsTimeout, confPresent, quorate
    Boolean ntp, ntpSynced

    Map<String, Map> services = [:]              // unit -> [active, enabled]
    Map<String, String> corosync = [:]           // top-level totem/quorum keys from corosync.conf
    Integer expectedVotes, totalVotes, quorumVotes
    List<Map> nodelist = []                      // [nodeid, name, addr]
    List<Map> quorumMembers = []                 // [nodeid, votes, name]
    List<Map> linkFaults = []
    Long runtimeToken, runtimeConsensus
    List<Map> lockspaces = []                    // [name, flags, members, status, newMembers]
    List<Map> fenceWaits = []                    // [node, since, actor]
    List<Map> dlmNodes = []                      // [node, state, fail, fence]
    Map<String, String> dlmConf = [:]
    Map<Integer, Integer> acks = [:]             // node id -> sudo fence_ack count in the last 15 min
    List<Map> mounts = []                        // [dev, target]
    List<Map> tables = []                        // [table, withdraw, freeze]
    List<Map> usage = []                         // [target, size, used]
    List<Map> heartbeats = []                    // [host, ms]
    Map<String, Integer> dComms = [:]
    List<String> dVms = []
    List<Map> vms = []                           // [name, state]
    List<Map> maps = []                          // [wwid, vendor, features, paths: [state, checker]]
    List<Map> portalDevs = []                    // [portal, dev]
    List<Map> interfaces = []                    // [name, mtu, state]
    List<Map> bonds = []                         // [name, mode, slaves: [name, status]]
    List<Map> addrs = []                         // [dev, ip, prefix]
    List<Map> peers = []                         // [addr, loss, rttMs]
    Map<String, String> packages = [:]
    Map<String, Integer> journals = [:]
    /** chrony's own view: [source, stratum, offsetMs, leap]; null when chrony is not what keeps time here. */
    Map chrony
    int chronySelected
    String timeFollowing                         // the source chrony follows, by the name it was configured with
    List<Map> chronySources = []                 // [addr, selected, offsetMs]: comparable across hosts
    List<String> timeServers = []                // configured in chrony or timesyncd
    String cpuModel, cpuFlags, libvirtCpu
    List<String> usableCpus = []                 // named CPU models libvirt says this host can run
    Long memTotalKb, memAvailKb
    List<Map> pools = []                         // [name, state]
    Map<Integer, Integer> vlanSeen = [:]         // VLAN -> MAC addresses learned from the uplinks
    List<Map> storageStats = []                  // [wwid, reads, readMs, writes, writeMs, inflight] over 2 s
    Map<String, Integer> gfsStatMs = [:]         // mount -> ms to stat a heartbeat file
    Map<String, Integer> gfsInUse = [:]          // mount -> processes with it open
    String hwProduct, hwBios, hwBmc
    List<Map> nics = []                          // [name, firmware, speed]
    List<Map> edac = []                          // [mc, ce, ue]
    List<Map> sensors = []                       // [type, name, status, reading]
    List<Map> sel = []                           // [date, text, asserted]
    /** Null when the probe had no LLDP section at all. */
    Boolean lldpAvailable
    Map<String, Map> neighbours = [:]            // local interface -> [chassis, port, pvid, via]

    static HostFacts parse(String text) {
        HostFacts f = new HostFacts()
        String cur = null
        (text ?: '').readLines().each { String l ->
            if (l.startsWith('@@')) {
                cur = l.substring(2).trim()
                f.sections[cur] = []
            } else if (cur) {
                f.sections[cur] << l
            }
        }
        f.complete = f.kv('end').COMPLETE == '1'
        f.fill()
        f
    }

    /** KEY=value lines of a section (the last value of a repeated key). */
    Map<String, String> kv(String section) {
        (sections[section] ?: []).findAll { it ==~ /[A-Z0-9_]+=.*/ }.collectEntries { String l ->
            int i = l.indexOf('=')
            [(l.substring(0, i)): l.substring(i + 1)]
        }
    }

    /** All values of a repeated KEY=a|b|c line, split on '|'. */
    List<List<String>> rows(String section, String key) {
        (sections[section] ?: []).findAll { it.startsWith(key + '=') }.collect { (it.substring(key.length() + 1).split(/\|/, -1) as List<String>) }
    }

    private static Long lng(String v) { v?.trim()?.isLong() ? v.trim() as Long : null }
    private static int int0(String v) { v?.trim()?.isInteger() ? v.trim() as Integer : 0 }
    private static Boolean yes(String v) { v == null || v.trim() == '' ? null : v.trim() == 'yes' }

    private void fill() {
        Map m = kv('meta')
        hostname = m.HOSTNAME
        now = lng(m.NOW)
        boot = lng(m.BOOT)
        kernel = m.KERNEL
        osVersion = m.OS_VERSION
        load1 = m.LOAD1?.isBigDecimal() ? m.LOAD1 as BigDecimal : null
        nproc = int0(m.NPROC)
        rebootRequired = m.REBOOT_REQUIRED == '1'
        hvmcli = m.HVMCLI ?: null
        mode = m.MODE

        (sections.services ?: []).each { String l ->
            List<String> p = l.split(/\|/, -1) as List<String>
            if (p.size() >= 3) {
                services[p[0]] = [active: p[1], enabled: p[2]]
            }
        }
        corosyncKey = sections.corosync_key?.find { it?.trim() }?.trim()
        parseCorosyncConf(sections.corosync_conf ?: [])
        parseQuorum(sections.quorum ?: [])
        parseLinks(sections.links ?: [])
        (sections.totem ?: []).each { String l ->
            def t = l =~ /totem\.(token|consensus) \(\w+\) = (\d+)/
            if (t.find()) {
                if (t.group(1) == 'token') { runtimeToken = t.group(2) as Long } else { runtimeConsensus = t.group(2) as Long }
            }
        }
        parseDlmLs(sections.dlm_ls ?: [])
        parseDlmStatus(sections.dlm_status ?: [])
        (sections.dlm_conf ?: []).each { String l ->
            int i = l.indexOf('=')
            if (i > 0) { dlmConf[l.substring(0, i).trim()] = l.substring(i + 1).trim() }
        }
        (sections.fence_acks ?: []).each { String l ->
            List<String> p = l.split(/\|/) as List<String>
            if (p.size() == 2 && p[0].isInteger() && p[1].isInteger()) { acks[p[0] as Integer] = p[1] as Integer }
        }

        mounts = rows('gfs2', 'MOUNT').collect { [dev: it[0], target: it[1]] }
        tables = rows('gfs2', 'TABLE').collect { [table: it[0], withdraw: it[1], freeze: it[2]] }
        fsSafe = kv('gfs2').FS_SAFE == '1'
        fsTimeout = kv('gfs2_fs').FS_TIMEOUT == '1'
        usage = rows('gfs2_fs', 'USAGE').findAll { it.size() >= 3 && lng(it[1]) }.collect { [target: it[0], size: lng(it[1]), used: lng(it[2])] }
        heartbeats = rows('gfs2_fs', 'HB').findAll { it[0] }.collect { [host: it[0], ms: lng(it.size() > 1 ? it[1] : null)] }

        Map st = kv('stuck')
        dCount = int0(st.D_COUNT)
        dOldest = lng(st.D_OLDEST)
        hung1h = int0(st.HUNG_1H)
        rows('stuck', 'D_COMM').each { if (it.size() == 2) { dComms[it[0]] = int0(it[1]) } }
        dVms = (sections.stuck ?: []).findAll { it.startsWith('D_VM=') }*.substring(5)

        // "  5    east    running" / " -   other   shut off"
        vms = (sections.vms ?: []).findAll { it.trim() }.collect { String l ->
            List<String> p = l.trim().split(/\s+/, 3) as List<String>
            p.size() == 3 ? [name: p[1], state: p[2].trim()] : null
        }.findAll()
        Map vo = kv('vm_ops')
        stateLock15m = int0(vo.STATE_LOCK_15M)
        agentKills15m = int0(vo.AGENT_KILLS_15M)

        parseMultipath(sections.multipath ?: [])
        Map is = kv('iscsi')
        sessions = int0(is.SESSIONS)
        errors24h = int0(is.ERRORS_24H)
        portalDevs = rows('iscsi', 'PORTAL_DEV').collect { [portal: it[0], dev: it.size() > 1 ? it[1] : null] }

        interfaces = (sections.links_net ?: []).collect { String l ->
            List<String> p = l.split(/\|/, -1) as List<String>
            p.size() == 3 ? [name: p[0].trim(), mtu: p[1].isInteger() ? p[1] as Integer : null, state: p[2]] : null
        }.findAll()
        Map<String, Map> bs = [:]
        rows('bonds', 'BOND').each { bs[it[0]] = [name: it[0], mode: it.size() > 1 ? it[1] : null, slaves: []] }
        rows('bonds', 'SLAVE').each { if (it.size() == 3 && bs[it[0]]) { bs[it[0]].slaves << [name: it[1], status: it[2]] } }
        bonds = bs.values() as List

        addrs = rows('addrs', 'ADDR').collect { List<String> r ->
            List<String> c = (r.size() > 1 ? r[1] : '').split('/') as List<String>
            c.size() == 2 && c[1].isInteger() ? [dev: r[0], ip: c[0], prefix: c[1] as Integer] : null
        }.findAll()
        ringDev = kv('ring').RING_DEV
        ringAddr = kv('ring').RING_ADDR
        peers = rows('ring', 'PEER').collect { [addr: it[0], loss: it.size() > 1 && it[1]?.isInteger() ? it[1] as Integer : null,
                                                rttMs: it.size() > 2 && it[2]?.isBigDecimal() ? it[2] as BigDecimal : null,
                                                ssh: it.size() > 3 ? it[3] == '1' : null, pathMtuOk: it.size() > 4 ? it[4] == '1' : null,
                                                mtu: it.size() > 5 && it[5]?.isInteger() ? it[5] as Integer : null] }

        Map lo = kv('layout')
        ovsBridges = lo.OVS_BRIDGES
        virtswitchFiles = int0(lo.VIRTSWITCH_FILES)

        parseLldp(sections.lldp)
        rows('vlans', 'VLAN_SEEN').each { if (it.size() == 2 && it[0].isInteger() && it[1].isInteger()) { vlanSeen[it[0] as Integer] = (vlanSeen[it[0] as Integer] ?: 0) + (it[1] as Integer) } }
        storageStats = rows('storage', 'STAT').findAll { it.size() >= 6 }.collect {
            [wwid: it[0], reads: lng(it[1]) ?: 0L, readMs: lng(it[2]) ?: 0L, writes: lng(it[3]) ?: 0L, writeMs: lng(it[4]) ?: 0L, inflight: lng(it[5]) ?: 0L]
        }
        rows('gfs2_fs', 'IN_USE').each { if (it.size() == 2 && it[1].isInteger()) { gfsInUse[it[0]] = it[1] as Integer } }
        rows('gfs2_fs', 'GFS_STAT').each { if (it.size() == 2 && it[1].isInteger()) { gfsStatMs[it[0]] = it[1] as Integer } }
        Map hw = kv('hardware')
        hwProduct = hw.HW_PRODUCT ?: null
        hwBios = hw.HW_BIOS ?: null
        hwBmc = hw.HW_BMC ?: null
        nics = rows('hardware', 'NIC').collect { [name: it[0], firmware: it.size() > 1 ? it[1] : null, speed: it.size() > 2 ? it[2] : null] }
        edac = rows('hardware', 'EDAC').findAll { it.size() == 3 }.collect { [mc: it[0], ce: lng(it[1]) ?: 0L, ue: lng(it[2]) ?: 0L] }
        sensors = rows('hardware', 'SDR').findAll { it.size() >= 4 }.collect { [type: it[0], name: it[1].trim(), status: it[2].trim(), reading: it[3].trim()] }
        // "  21 | 07/19/26 | 06:45:28 EDT | Temperature Inlet Temp | Upper Critical going high | Asserted | Reading 47"
        sel = (sections.hardware ?: []).findAll { it.startsWith('SEL=') }.collect { String l ->
            List<String> p = l.substring(4).split(/\s*\|\s*/) as List<String>
            p.size() >= 5 ? [date: p[1], text: p[3..<(p.size() > 5 ? 5 : p.size())].join(' '), asserted: p.any { it.trim() == 'Asserted' }] : null
        }.findAll()

        Map ti = kv('time')
        ntp = yes(ti.NTP)
        ntpSynced = yes(ti.NTP_SYNCED)
        // chronyc -c tracking: refid,source,stratum,reftime,system offset (s),...,leap status
        List<String> ch = (ti.CHRONY ?: '').split(',', -1) as List<String>
        if (ch.size() >= 14 && ch[4]?.isBigDecimal()) {
            chrony = [source: ch[1], stratum: ch[2]?.isInteger() ? ch[2] as Integer : null, offsetMs: (ch[4] as BigDecimal) * 1000, leap: ch[13]?.trim()]
        }
        // chronyc -c sources: mode,state,address,stratum,poll,reach,last rx,adjusted offset (s),measured offset (s),error (s)
        chronySources = (sections.time ?: []).findAll { it.startsWith('CHRONY_SOURCE=') }.collect { String l ->
            List<String> p = l.substring(14).split(',', -1) as List<String>
            p.size() >= 8 && p[7]?.isBigDecimal() ? [addr: p[2], selected: p[1] == '*', reach: p[5], offsetMs: (p[7] as BigDecimal) * 1000] : null
        }.findAll()
        chronySelected = chronySources.count { it.selected }
        timeFollowing = ti.CHRONY_FOLLOWING ?: null
        timeServers = (sections.time ?: []).findAll { it.startsWith('CONF_SERVER=') }*.substring(12).sort()
        Map cp = kv('cpu')
        cpuModel = cp.CPU_MODEL
        cpuFlags = cp.CPU_FLAGS
        libvirtCpu = cp.LIBVIRT_CPU ?: null
        usableCpus = (cp.USABLE_CPUS ?: '').tokenize(',')
        memTotalKb = lng(cp.MEM_TOTAL_KB)
        memAvailKb = lng(cp.MEM_AVAIL_KB)
        pools = (sections.pools ?: []).findAll { it.trim() }.collect { String l ->
            List<String> p = l.trim().split(/\s+/) as List<String>
            p.size() >= 2 ? [name: p[0], state: p[1]] : null
        }.findAll()

        (sections.packages ?: []).each { String l ->
            List<String> p = l.split(/\|/, 2) as List<String>
            if (p.size() == 2) { packages[p[0]] = p[1] }
        }
        rows('journals', 'JOURNALS').each { if (it.size() == 2 && it[1]?.isInteger()) { journals[it[0]] = it[1] as Integer } }
        Map bm = kv('bmc')
        bmcIp = bm.BMC_IP ?: null
        watchdog = bm.WATCHDOG ?: null
    }

    /** lldpcli -f keyvalue: lldp.<interface>.<key>=<value>. A vlan-id is followed by vlan.pvid=yes when it is the native one. */
    private void parseLldp(List<String> lines) {
        if (lines == null) {
            return
        }
        lldpAvailable = kv('lldp').LLDPD == 'active'
        Map<String, String> lastVlan = [:]
        lines.each { String l ->
            def m = l =~ /^lldp\.([^.=]+)\.([^=]+)=(.*)$/
            if (!m.find()) {
                return
            }
            String ifc = m.group(1), k = m.group(2), v = m.group(3)
            Map n = neighbours.computeIfAbsent(ifc) { [:] }
            switch (k) {
                case 'via': n.via = v; break
                case 'chassis.name': n.chassis = v; break
                case 'port.ifname': n.port = v; break
                case 'vlan.vlan-id': lastVlan[ifc] = v; break
                case 'vlan.pvid': if (v == 'yes') { n.pvid = lastVlan[ifc] }; break
            }
        }
    }

    private void parseCorosyncConf(List<String> lines) {
        confPresent = lines.any { it.trim() }
        List<String> stack = []
        Map cur = null
        lines.each { String raw ->
            String l = raw.trim()
            if (!l || l.startsWith('#')) {
                return
            }
            if (l.endsWith('{')) {
                stack << l[0..-2].trim()
                if (stack.last() == 'node') { cur = [:] }
                return
            }
            if (l == '}') {
                if (stack && stack.last() == 'node' && cur) { nodelist << cur; cur = null }
                if (stack) { stack.removeLast() }
                return
            }
            int i = l.indexOf(':')
            if (i < 0) {
                return
            }
            String k = l.substring(0, i).trim(), v = l.substring(i + 1).trim()
            if (stack && stack.last() == 'node' && cur != null) {
                if (k == 'ring0_addr') { cur.addr = v } else if (k == 'name') { cur.name = v } else if (k == 'nodeid') { cur.nodeid = v.isInteger() ? v as Integer : null }
            } else if (stack) {
                corosync["${stack.last()}.${k}".toString()] = v
            }
        }
    }

    private void parseQuorum(List<String> lines) {
        boolean inMembers = false
        lines.each { String l ->
            def m = l =~ /^(Quorate|Expected votes|Total votes|Quorum):\s+(\S+)/
            if (m.find()) {
                switch (m.group(1)) {
                    case 'Quorate': quorate = m.group(2) == 'Yes'; break
                    case 'Expected votes': expectedVotes = m.group(2) as Integer; break
                    case 'Total votes': totalVotes = m.group(2) as Integer; break
                    case 'Quorum': quorumVotes = m.group(2) as Integer; break
                }
            }
            if (l.contains('Membership information')) { inMembers = true; return }
            if (inMembers) {
                def r = l =~ /^\s*(\d+)\s+(\d+)\s+(\S+)/
                if (r.find()) { quorumMembers << [nodeid: r.group(1) as Integer, votes: r.group(2) as Integer, name: r.group(3)] }
            }
        }
    }

    /** corosync-cfgtool -s: a line per peer, "nodeid: N: connected" (or "disconnected"). */
    private void parseLinks(List<String> lines) {
        lines.each { String l ->
            def r = l =~ /nodeid:\s*(\d+):\s*(\S+)/
            if (r.find() && !(r.group(2) in ['connected', 'localhost'])) {
                linkFaults << [nodeid: r.group(1) as Integer, status: r.group(2)]
            }
        }
    }

    private void parseDlmLs(List<String> lines) {
        Map cur = null
        lines.each { String l ->
            def m = l =~ /^(name|flags|members|new status|new members)\s+(.*)$/
            if (!m.find()) {
                return
            }
            String k = m.group(1), v = m.group(2).trim()
            if (k == 'name') {
                cur = [name: v]
                lockspaces << cur
            } else if (cur != null) {
                cur[k == 'new status' ? 'status' : (k == 'new members' ? 'newMembers' : k)] = v
            }
        }
    }

    private void parseDlmStatus(List<String> lines) {
        lines.each { String l ->
            def w = l =~ /^fence (\d+) \S+ pid \d+ actor (\d+) fail (\d+) fence (\d+) now (\d+)/
            if (w.find()) {
                fenceWaits << [node: w.group(1) as Integer, actor: w.group(2) as Integer, since: w.group(3) as Long, now: w.group(5) as Long]
                return
            }
            def n = l =~ /^node (\d+) (\S) add \d+ rem \d+ fail (\d+) fence (\d+)/
            if (n.find()) {
                dlmNodes << [node: n.group(1) as Integer, state: n.group(2), fail: n.group(3) as Long, fence: n.group(4) as Long]
            }
        }
    }

    private void parseMultipath(List<String> lines) {
        Map cur = null
        lines.each { String l ->
            def h = l =~ /^(\S+)\s+(?:\(\S+\)\s+)?dm-\d+\s+(\S+)/
            if (h.find()) {
                cur = [wwid: h.group(1), vendor: h.group(2), features: '', paths: []]
                maps << cur
                return
            }
            if (!cur) {
                return
            }
            def f = l =~ /features='([^']*)'/
            if (f.find()) {
                cur.features = f.group(1)
                return
            }
            def p = l =~ /\d+:\d+:\d+:\d+\s+\S+\s+\d+:\d+\s+(\S+)\s+(\S+)/
            if (p.find()) {
                cur.paths << [state: p.group(1), checker: p.group(2)]
            }
        }
        maps = maps.findAll { !(it.vendor as String).startsWith('ATA') }   // local boot disks that multipath also lists
    }

    /** True when any lockspace is stopped or in recovery: GFS2 I/O is blocked on this host. */
    boolean isFrozen() {
        lockspaces.any { (it.flags as String)?.contains('kern_stop') || it.status } || fenceWaits as boolean
    }

    Map service(String n) { services[n] ?: [:] }
    boolean active(String n) { service(n).active == 'active' }

    /** Node ids in corosync.conf -> name. */
    Map<Integer, String> getNodeNames() { nodelist.collectEntries { [(it.nodeid): it.name] } }
}
