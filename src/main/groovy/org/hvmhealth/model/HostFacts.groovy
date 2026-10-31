package org.hvmhealth.model

/** What one host's read-only probe (scripts/health-probe.sh) reported, parsed. Missing data stays null or empty. */
class HostFacts {
    Map<String, List<String>> sections = [:]
    boolean complete
    /** The probe ran out of its time: what it read first holds, DLM above all, and the rest was skipped, so this is not a complete answer. */
    boolean partial

    String hostname, kernel, osVersion, mode, hvmcli, ovsBridges, bmcIp, watchdog, corosyncKey, ringDev, ringAddr
    Long now, boot, dOldest, corosyncUpSec
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
    Integer gfsErrors                            // GFS2 consistency errors and withdrawals in the kernel log over 7 days, across reboots; null if unread
    String gfsErrorLast
    Map<String, Integer> dComms = [:]
    List<String> dVms = []
    List<Map> vms = []                           // [name, state]
    List<Map> maps = []                          // [wwid, vendor, features, paths: [state, checker]]
    List<Map> portalDevs = []                    // [portal, dev]
    Map<String, List<String>> devPorts = [:]     // device -> the physical ports under it
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
    /** 'chrony', 'timesyncd', or null when neither runs. */
    String timeDaemon
    /** systemd-timesyncd's view when it keeps time here: [addr, name, offsetMs, packets, stratum, leap]. Addr is null until a server answers. */
    Map timesyncd
    /** Null when the probe did not look, as on a quick check. */
    Boolean ipmiToolPresent
    /** The Morpheus agent, from wherever this host keeps it. */
    String agentVersion
    boolean ringDynamic                          // the cluster network address came from DHCP
    String cpuModel, cpuFlags, libvirtCpu
    List<String> usableCpus = []                 // named CPU models libvirt says this host can run
    Long memTotalKb, memAvailKb
    List<Map> pools = []                         // [name, state]
    Map<Integer, Integer> vlanSeen = [:]         // VLAN -> MAC addresses learned from the uplinks
    List<Map> storageStats = []                  // [wwid, reads, readMs, writes, writeMs, inflight] over 2 s
    Map<String, Integer> gfsStatMs = [:]         // mount -> ms to stat a heartbeat file
    Map<String, Integer> gfsInUse = [:]          // mount -> processes with it open
    String hwProduct, hwBios, hwBmc
    List<Map> nics = []                          // [name, firmware, speed, bond]
    List<Map> edac = []                          // [mc, ce, ue]
    List<Map> sensors = []                       // [type, name, status, reading]
    List<Map> sel = []                           // [date, text, asserted]
    /** Null when the probe had no LLDP section at all. */
    Boolean lldpAvailable
    Map<String, Map> neighbours = [:]            // local interface -> [chassis, port, pvid, via, mfs]
    /** What a quorum device looks like from here. The state is corosync-qdevice-tool's, the row and flags corosync-quorumtool's. */
    String qdeviceState, qdeviceRow, quorumFlags
    Boolean qdeviceToolPresent
    Map<String, String> vmCpus = [:]             // running VM -> first word of qemu's -cpu; "host" is host-passthrough
    Set<String> vmNoMigrate = [] as Set          // running VMs whose CPU settings stop them live-migrating
    Map<String, String> poolPaths = [:]          // libvirt pool path -> pool name
    Boolean opensslPresent, smartctlPresent
    boolean certsInUse, portsCut
    List<Map> certs = []                         // [path, notAfter (epoch s, null when unread)]
    List<Map> bootDisks = []                     // [name, model, virtual, health, pending, uncorrectable, reallocated, mediaErrors, critical]
    /** [vm, dev, mac, bridge, kind (ovs, bridge), read, why, access, tagged, all, untagged] */
    List<Map> vmPorts = []

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
        f.partial = f.kv('end').COMPLETE == '1' && f.kv('end').CUT == '1'
        f.complete = f.kv('end').COMPLETE == '1' && !f.partial
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
        corosyncUpSec = lng(kv('services').COROSYNC_UP)
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
        String gfsErr = kv('gfs2').GFS2_ERRORS_7D
        gfsErrors = gfsErr?.isInteger() ? gfsErr as Integer : null
        gfsErrorLast = kv('gfs2').GFS2_ERROR_LAST
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
        Map<String, String> queueing = rows('multipath_queue', 'MP_QUEUE').findAll { it.size() == 2 }.collectEntries { [(it[0]): it[1].trim()] }
        maps.each { it.queueing = queueing[it.wwid] }
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
        ringDynamic = kv('ring').RING_DYNAMIC == '1'
        rows('ring', 'DEV_PORTS').each { devPorts[it[0]] = it.size() > 1 && it[1] ? it[1].split(',') as List<String> : [] }
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
        nics = rows('hardware', 'NIC').collect { [name: it[0], firmware: it.size() > 1 ? it[1] : null, speed: it.size() > 2 ? it[2] : null, bond: it.size() > 3 ? it[3] : null] }
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
        timeDaemon = active('chrony') ? 'chrony' : (active('systemd-timesyncd') ? 'timesyncd' : null)
        if (timeDaemon == 'timesyncd') {
            parseTimesyncd(ti)
        }
        List<String> chronyConf = (sections.time ?: []).findAll { it.startsWith('CONF_SERVER=') }*.substring(12)
        timeServers = (timeDaemon == 'timesyncd' || (!timeDaemon && !chronyConf) ? timesyncdServers(ti) : chronyConf).sort()
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
        agentVersion = packages['morpheus-agent'] ?: packages['morpheus-node']
        // 0 was read once during provisioning, while others read 32: nothing has no journals, so it is a failed read
        rows('journals', 'JOURNALS').each { if (it.size() == 2 && it[1]?.isInteger() && (it[1] as Integer) > 0) { journals[it[0]] = it[1] as Integer } }
        Map bm = kv('bmc')
        bmcIp = bm.BMC_IP ?: null
        watchdog = bm.WATCHDOG ?: null
        ipmiToolPresent = bm.containsKey('IPMITOOL') ? (bm.IPMITOOL as boolean) : null
        parseQdevice()
        rows('vm_cpu', 'VM_CPU').each {
            if (it.size() >= 2) { vmCpus[it[0]] = it[1] }
            if (it.size() >= 3 && it[2] == '1') { vmNoMigrate << it[0] }
        }
        rows('pool_paths', 'POOL_PATH').each { if (it.size() == 2 && it[0]) { poolPaths[it[0]] = it[1] } }
        parseCerts()
        parseSmart()
        parseVmPorts()
    }

    /** corosync-quorumtool -s with a device adds a column: "1 1 A,V,NMW node1 (local)", or NR for not registered. */
    private void parseQdevice() {
        Map<String, String> q = kv('qdevice')
        qdeviceToolPresent = q.containsKey('QDEVICE_TOOL') ? (q.QDEVICE_TOOL as boolean) : null
        (sections.qdevice ?: []).each { String l ->
            def m = l =~ /^QD=State:\s+(.+?)\s*$/
            if (m.find()) { qdeviceState = m.group(1) }
        }
        (sections.quorum ?: []).each { String l ->
            def m = l =~ /^\s*\d+\s+\d+\s+(NR|N?A,N?V,N?MW)\s+\S+\s+\(local\)/
            if (m.find()) { qdeviceRow = m.group(1) }
            def fl = l =~ /^Flags:\s+(.*?)\s*$/
            if (fl.find()) { quorumFlags = fl.group(1) }
        }
    }

    private void parseCerts() {
        Map<String, String> c = kv('certs')
        opensslPresent = c.containsKey('OPENSSL') ? (c.OPENSSL as boolean) : null
        certsInUse = int0(c.TLS_SOCKETS) > 0 || int0(c.TLS_CONF) > 0
        certs = rows('certs', 'CERT').collect { [path: it[0], notAfter: lng(it.size() > 1 ? it[1] : null)] }
    }

    private void parseSmart() {
        Map<String, String> s = kv('smart')
        smartctlPresent = s.containsKey('SMARTCTL') ? (s.SMARTCTL as boolean) : null
        Map<String, Map> disks = [:]
        (sections.smart ?: []).each { String l ->
            if (l.startsWith('BOOT_DISK=')) {
                disks[l.substring(10)] = [name: l.substring(10)]
            } else if (l.startsWith('SMART=') && l.contains('|')) {
                Map d = disks[l.substring(6, l.indexOf('|'))]
                if (d != null) { smartLine(d, l.substring(l.indexOf('|') + 1)) }
            }
        }
        bootDisks = disks.values() as List
    }

    /** One line of smartctl -H -A -i, for ATA, SCSI or NVMe. The raw value of an attribute is its tenth column. */
    private static void smartLine(Map d, String l) {
        def model = l =~ /^(?:Device Model|Model Number|Product):\s+(.+?)\s*$/
        if (model.find()) {
            d.model = model.group(1)
            d.virtual = (d.model as String) ==~ /(?i).*(\bVD\b|LOGICAL VOLUME|RAID|PERC|VIRTUAL|QEMU|VBOX|VMWARE).*/
        }
        def health = l =~ /^SMART (?:overall-health self-assessment test result|Health Status):\s+(.+?)\s*$/
        if (health.find()) { d.health = health.group(1) }
        def attr = l =~ /^\s*(5|197|198)\s+\S+\s+0x[0-9a-fA-F]+\s/
        if (attr.find()) {
            List<String> p = l.trim().split(/\s+/) as List<String>
            def raw = p.size() >= 10 ? p[9] =~ /^\d+/ : null
            if (raw?.find()) { d[[5: 'reallocated', 197: 'pending', 198: 'uncorrectable'][attr.group(1) as Integer]] = raw.group() as Long }
        }
        def nvme = l =~ /^(Critical Warning|Media and Data Integrity Errors):\s+(\S+)/
        if (nvme.find()) {
            if (nvme.group(1) == 'Critical Warning') { d.critical = nvme.group(2) } else { d.mediaErrors = lng(nvme.group(2).replace(',', '')) }
        }
    }

    private void parseVmPorts() {
        portsCut = kv('vm_ports').PORTS_CUT == '1'
        rows('vm_ports', 'OVS_PORT').findAll { it.size() >= 7 }.each { vmPorts << ovsPort(it) }
        rows('vm_ports', 'BR_PORT').findAll { it.size() >= 6 }.each { vmPorts << bridgePort(it) }
    }

    /**
     * Open vSwitch: with no vlan_mode a tag makes an access port and no tag a trunk, of every VLAN when trunks is empty as well.
     * That last one is how 1.3 builds a port on an untagged network, so it stays its own case rather than reading as a trunk.
     */
    private static Map ovsPort(List<String> r) {
        Integer tag = r[4]?.trim()?.isInteger() ? r[4].trim() as Integer : null
        List<Integer> trunks = (r[5] ?: '').findAll(/\d+/).collect { it as Integer }
        String mode = r[6]?.trim() in ['[]', ''] ? null : r[6].trim().replace('"', '')
        Map p = [vm: r[0], dev: r[1], mac: r[2], bridge: r[3], kind: 'ovs', read: true, access: null, tagged: [], all: false, untagged: false]
        if (mode == 'trunk' || mode?.startsWith('native-')) {
            p.access = mode == 'trunk' ? null : tag
            p.tagged = trunks
            p.all = !trunks
        } else if (tag != null) {
            p.access = tag
        } else if (mode || !trunks) {
            p.untagged = true
        } else {
            p.tagged = trunks
        }
        p
    }

    /** Linux bridge, from bridge -j vlan show. With vlan_filtering off the port's VLAN list means nothing: it reads 1 PVID whatever it carries. */
    private static Map bridgePort(List<String> r) {
        Map p = [vm: r[0], dev: r[1], mac: r[2], bridge: r[3], kind: 'bridge', read: false, access: null, tagged: [], all: false, untagged: false]
        if (r[4]?.trim() != '1') {
            p.why = r[3] ? "bridge ${r[3]} does not filter VLANs".toString() : 'port is on no bridge'
            return p
        }
        try {
            List rows = (new groovy.json.JsonSlurper().parseText(r[5] ?: '[]') as List)
            List vlans = rows.collectMany { (it as Map).vlans ?: [] }
            vlans.each { Map v ->
                if ((v.flags as List)?.contains('PVID')) {
                    p.access = v.vlan as Integer
                } else {
                    p.tagged.addAll(((v.vlan as Integer)..((v.vlanEnd ?: v.vlan) as Integer)) as List)
                }
            }
            p.read = vlans as boolean
            p.why = vlans ? null : 'bridge listed no VLANs for this port'
        } catch (Exception ignored) {
            p.why = 'could not read the bridge VLAN list'
        }
        p
    }

    /** timedatectl timesync-status: "Server: 192.0.2.123 (ntp1.example.invalid)", or n/a until a server answers. */
    private void parseTimesyncd(Map<String, String> ti) {
        def s = (ti.TS_SERVER ?: '') =~ /^(\S+)(?:\s+\((.*)\))?$/
        boolean named = s.find()
        String addr = named ? s.group(1) : null
        String name = named ? s.group(2) : null
        addr = addr == 'n/a' ? null : addr
        name = name == 'n/a' ? null : name
        timesyncd = [addr: addr, name: name, offsetMs: timespanMs(ti.TS_OFFSET), packets: lng(ti.TS_PACKETS),
                     stratum: ti.TS_STRATUM?.isInteger() ? ti.TS_STRATUM as Integer : null, leap: ti.TS_LEAP?.trim() ?: null]
        timeFollowing = addr ? (name ?: addr) : null
    }

    /**
     * The servers timesyncd will use, in its own order of preference: what it reports first, then its config files, where an
     * empty assignment resets the list. FallbackNTP counts only when nothing else is set. A stock host sets nothing at all,
     * and follows the fallback compiled into the package.
     */
    private List<String> timesyncdServers(Map<String, String> ti) {
        List<String> reported = [ti.TS_RUNTIME, ti.TS_LINK, ti.TS_SYSTEM].collectMany { (it ?: '').tokenize() }.unique()
        if (reported) {
            return reported
        }
        Map<String, List<String>> conf = [NTP: [], FallbackNTP: []]
        (sections.time ?: []).findAll { it.startsWith('CONF_TIMESYNCD=') }.each { String l ->
            List<String> p = l.substring(15).trim().split('=', 2) as List<String>
            if (p.size() == 2 && conf.containsKey(p[0])) {
                if (p[1].trim()) { conf[p[0]].addAll(p[1].tokenize()) } else { conf[p[0]].clear() }
            }
        }
        if (conf.NTP) {
            return conf.NTP.unique()
        }
        List<String> fallback = (ti.TS_FALLBACK ?: '').tokenize() ?: conf.FallbackNTP.unique()
        fallback ?: (timeFollowing ? [timeFollowing] : [])
    }

    /** systemd's time spans as it prints them with LC_ALL=C: "+5.093ms", "-120us", "1min 2.5s", "0". Null when unreadable. */
    static BigDecimal timespanMs(String v) {
        String t = v?.trim()
        if (!t) {
            return null
        }
        boolean negative = t.startsWith('-')
        t = t.replaceFirst(/^[+-]/, '')
        if (t == '0') {
            return 0G
        }
        Map<String, BigDecimal> perUnit = [us: 0.001G, ms: 1G, s: 1000G, min: 60000G, h: 3600000G, d: 86400000G]
        BigDecimal total = null
        def m = t =~ /([0-9.]+)\s*(us|ms|min|s|h|d)\b/
        while (m.find()) {
            total = (total ?: 0G) + (m.group(1) as BigDecimal) * perUnit[m.group(2)]
        }
        total != null && negative ? -total : total
    }

    /** What this host measures against the servers it reads, for comparing clocks across hosts: [addr, offsetMs]. */
    List<Map> getTimeSamples() {
        chronySources.findAll { it.reach != '0' } + (timesyncd?.addr && timesyncd.offsetMs != null ? [[addr: timesyncd.addr, offsetMs: timesyncd.offsetMs]] : [])
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
                case 'port.mfs': n.mfs = v; break
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
            // a path that has vanished prints #:#:#:# for its address and #:# for its device numbers
            def p = l =~ /[\d#]+:[\d#]+:[\d#]+:[\d#]+\s+\S+\s+[\d#]+:[\d#]+\s+(\S+)\s+(\S+)/
            if (p.find()) {
                cur.paths << [state: p.group(1), checker: p.group(2)]
            }
        }
        maps = maps.findAll { !(it.vendor as String).startsWith('ATA') }   // local boot disks that multipath also lists
    }

    /** True when any lockspace is stopped or in recovery: GFS2 I/O is blocked on this host. */
    /**
     * A lockspace says so. dlm_controld can go on retrying a fence for a node that left cleanly, with every lockspace running and I/O
     * fine, so a fence line alone is only a freeze when there are no lockspaces to ask.
     */
    /** What sha256sum prints for a file with nothing in it. Seen when Morpheus's add failed partway: corosync cannot start on such a key. */
    boolean isAuthkeyEmpty() {
        corosyncKey == 'e3b0c44298fc1c14'
    }

    /**
     * Who a stopped lockspace is waiting on when dlm_tool status has no fence line for it, which is how a delete during a mount looked for 27 minutes:
     * whoever was a member and is not among the new members.
     */
    List<Integer> getInferredWaits() {
        Closure<Set<Integer>> ids = { Object s -> ((s ?: '') as String).tokenize().findAll { it.isInteger() }.collect { it as Integer } as Set<Integer> }
        lockspaces.findAll { (it.status as String)?.contains('wait fencing') }.collectMany { (ids(it.members) - ids(it.newMembers)) as List<Integer> }.unique().findAll { !(it in fenceWaits*.node) } as List<Integer>
    }

    /** When the last GFS2 error was logged, in seconds, from the line's own stamp; null if it cannot be read. */
    Long getGfsErrorAt() {
        def m = (gfsErrorLast ?: '') =~ /^(\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}[+-]\d{2}:?\d{2})/
        if (!m.find()) {
            return null
        }
        try {
            java.time.OffsetDateTime.parse(m.group(1).replaceFirst(/([+-]\d{2})(\d{2})$/, '$1:$2')).toEpochSecond()
        } catch (java.time.format.DateTimeParseException ignored) {
            null
        }
    }

    boolean isFrozen() {
        lockspaces.any { (it.flags as String)?.contains('kern_stop') || it.status } || (!lockspaces && fenceWaits)
    }

    Map service(String n) { services[n] ?: [:] }
    boolean active(String n) { service(n).active == 'active' }

    /** Node ids in corosync.conf -> name. */
    Map<Integer, String> getNodeNames() { nodelist.collectEntries { [(it.nodeid): it.name] } }
}
