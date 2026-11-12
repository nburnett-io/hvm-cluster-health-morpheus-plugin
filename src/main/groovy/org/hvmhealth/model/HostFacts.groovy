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
    /** HPE agent quorum from https://127.0.0.1:7443/quorum, the operational authority on 1.3/2.0: [achieved, fenced, waitFencing, reachable, total, corosyncActive, dlmActive, corosyncQuorate]. Null when it could not be read or did not parse as JSON; field names are only partly documented, so each is read defensively. */
    Map agentQuorum
    /** Member identifiers the agent lists, from the /quorum nodes and /opt/morpheus-node/.quorum-nodes, lower-cased. Empty when none could be read. Its exact format is undocumented. */
    List<String> agentQuorumNodes = []
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
    Integer dlmConvErrors                        // DLM lock-conversion failures (validate_lock_args/lm_lock ret -22, the QUECVT defect) in the kernel log over 7 days; null if unread
    String dlmConvErrorLast
    Map<String, Integer> gfsBlock = [:]          // GFS2 lock table -> lock_module/block (DFL_BLOCK_LOCKS): 1 = locking blocked on this host
    Integer thinSoft                             // thin provisioning soft threshold warnings in the kernel log over 7 days; null if unread
    String thinSoftLast
    Integer apdSeen                              // agent all-paths-down VM-shutdown sightings in the host journal over 7 days; null if unread
    String apdLast
    Boolean fenceScsiAgent                       // /usr/sbin/fence_scsi_hpevm present: the layout-1.2 SCSI fence agent (advisory a00156214); null when the section was not read
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
    /** From ipmitool, null when not read: the controller's self-test, how full its event log is, the chassis fault flags that are set, sensors not ok. */
    String bmcSelftest
    Integer selUsedPct
    Boolean selOverflow
    List<String> chassisFaults = []
    boolean chassisRead
    boolean sensorsRead                          // the whole sensor list was read, so none listed means none failing
    List<Map> sensorFaults = []                  // [name, status, reading]
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
    List<Map> bootDisks = []                     // [name, scsiHost, model, virtual, health, pending, uncorrectable, reallocated, mediaErrors, critical, whenFailed]
    List<Map> raidDisks = []                     // physical disks behind a RAID controller: the same, plus [slot, bus]
    int raidDisksUnread                          // listed by smartctl's scan, but not read: over the limit, out of time, or the controller stopped answering
    /** How reading hvmcli health check went: read, missing, unsafe, no_time, timeout or unreadable. Null when the probe did not try. */
    String hvmcliState
    List<Map> hvmcliChecks = []                  // [check, type, status, details], each bounded
    /** [vm, dev, mac, bridge, kind (ovs, bridge), read, why, access, tagged, all, untagged] */
    List<Map> vmPorts = []
    /** "some avg60" of /proc/pressure, as a percentage; null where the kernel offers none. */
    BigDecimal psiCpu, psiMemory
    /** Over the 2 s storage sample; null when the counters could not be read. Steal is the share of CPU time a hypervisor under this host took. */
    BigDecimal stealPct, swapPerSec
    /** Whether the kernel log and libvirt's VM logs were read; null when the probe had no such section. */
    Boolean oomRead, vmLogsRead
    List<Map> oomKills = []                      // [at (epoch s), comm, vm, qemu]; vm only where the kernel named its cgroup
    List<Map> vmCrashes = []                     // [vm (libvirt domain), at (epoch s, null when unread)]
    List<Map> rootFs = []                        // [target, pct, inodePct]; a percentage is null when it has none
    boolean rootFsCut
    /** Whether systemctl --failed answered; null when the probe had no such section. The units it named, each a systemd unit name. */
    Boolean failedUnitsRead
    List<String> failedUnits = []
    /** Rates between two samples of every running VM: [vm, cpuPct (of the whole host), readIops, writeIops, readMBs, writeMBs]. Empty when the probe did not sample. */
    List<Map> vmActivity = []
    /** @@patch_exposure, @@kernel_posture, @@net_timing are full-check only. Each stays null or empty when the probe had no such section. */
    Boolean uuInstalled, nrInstalled             // unattended-upgrades and needrestart installed
    String uuPeriodic, uuTimer                   // APT::Periodic::Unattended-Upgrade value, and the apt-daily-upgrade.timer state
    int aptUbuntuSources                         // apt source entries pointing at a stock ubuntu.com archive
    boolean nrListOnly                           // needrestart set to list-only, so an upgrade restarts nothing
    int nrClusterExempt                          // needrestart drop-ins that name the cluster daemons (HVM's mvm drop-in)
    List<String> aptSourceFiles = []
    Map<String, Long> panicSysctls = [:]         // hung_task_panic, softlockup_panic, panic, panic_on_oops; only the ones read
    Long taint                                   // /proc/sys/kernel/tainted; null when unread
    List<Map> gfs2MountOpts = []                 // [target, options] from /proc/mounts
    Long aerFatal, aerNonfatal, aerCorrectable   // PCIe AER counts summed across devices; null when no AER files were found
    List<Map> bondTiming = []                    // [name, mode, miimon, updelay, downdelay, arpInterval, lacpRate, hashPolicy]
    List<Map> linkFlaps = []                     // [name, changes, downCount] carrier transitions since boot per physical NIC
    List<Map> bridges = []                       // [name, vlanFiltering, stpState] linux bridges

    // ---- 0.4.0 W-STO: vendor-neutral storage (transport, Fibre Channel, iSCSI timers) ----
    /** Any /sys/class/fc_host present, so this host has a Fibre Channel transport. */
    boolean fcPresent
    List<Map> fcHosts = []                       // [host, portState, speed, portName, linkFailures, lossOfSync, lossOfSignal, invalidCrc]
    List<Map> fcRports = []                      // [rport, portState, roles, devLossTmo, fastIoFailTmo]
    /** Per-path transport, from multipathd show paths %P; tpg/maxSectors only when mpathPathsExt. */
    List<Map> mpathPaths = []                    // [wwid, dev, proto, adapter, tpg, maxSectors]
    /** %A (TPG) and %k (max_sectors_kb) are 0.14.3 (26.04) only; null when the probe had no @@mpath_paths section. */
    Boolean mpathPathsExt
    List<Map> iscsiNodes = []                    // [target, startup]: node.startup from iscsiadm -m node -o show
    List<Map> iscsiTmo = []                      // [session, recoveryTmo]: live /sys/class/iscsi_session/*/recovery_tmo
    /** multipathd defaults polling_interval and fast_io_fail_tmo; null when unread (polling then taken as 5). */
    Integer mpPolling, mpFastIoFail
    /** All-paths-lost sightings over 7 days ("remaining active paths: 0" / "Entering recovery mode"); null if unread. */
    Integer pathsLost7d
    String pathsLostLast
    /** Hard space-allocation failures over 7 days (sense 27/07 / "critical space allocation" / write-protect); null if unread. */
    Integer thinHard7d
    String thinHardLast

    // ---- @@sec: tenant isolation and side-channel state (full checks only). Null/empty when the probe had no such section.
    /** Whether kvm_intel / kvm_amd expose nested virtualization: true=on, false=off, null=the module parameter could not be read. */
    Boolean nestedIntel, nestedAmd
    /** /sys/devices/system/cpu/vulnerabilities/<name> -> its one-line value. */
    Map<String, String> cpuVulns = [:]
    String smtControl, smtActive, cpuCmdline
    /** [role (cluster, storage), vlan]: the VLAN a host infrastructure interface is tagged with. Absent for an untagged interface. */
    List<Map> infraVlans = []
    /** [bridge, stp (Boolean), filtering (Boolean)] for every Linux bridge on the host, read from @@sec for SEC-04. */
    List<Map> secBridges = []
    /** [vm, cpuMode, vmx, svm, caches (List), limited]: from each running VM's domain XML. vmx/svm is 'disable', 'require', 'force' or null. */
    List<Map> vmSec = []
    boolean secCut

    static HostFacts parse(String text) {
        HostFacts f = new HostFacts()
        String cur = null
        (text ?: '').readLines().each { String l ->
            // Only an exact "@@" plus a lowercase section token starts a section. A tenant's VM name reaches this output as a field value, so a
            // line such as "@@end COMPLETE=1" smuggled through a name must read as content, not forge a section; and should a bare "@@end" slip
            // through, the probe prints the real one last and each marker resets its list, so the genuine trailing section wins.
            String name = l.startsWith('@@') ? l.substring(2) : null
            if (name != null && name ==~ /[a-z][a-z0-9_]*/) {
                cur = name
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
        parseAgentQuorum()
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
        String convErr = kv('gfs2').DLM_CONV_ERR_7D
        dlmConvErrors = convErr?.isInteger() ? convErr as Integer : null
        dlmConvErrorLast = kv('gfs2').DLM_CONV_ERR_LAST
        rows('gfs2', 'BLOCK').each { if (it.size() == 2 && it[1]?.trim()?.isInteger()) { gfsBlock[it[0]] = it[1].trim() as Integer } }
        String thin = kv('thin').THIN_SOFT_7D
        thinSoft = thin?.isInteger() ? thin as Integer : null
        thinSoftLast = kv('thin').THIN_SOFT_LAST
        String thinH = kv('thin').THIN_HARD_7D
        thinHard7d = thinH?.isInteger() ? thinH as Integer : null
        thinHardLast = kv('thin').THIN_HARD_LAST
        String apd = kv('apd').APD_7D
        apdSeen = apd?.isInteger() ? apd as Integer : null
        apdLast = kv('apd').APD_LAST
        String fsAgent = kv('fence_agent').FENCE_SCSI
        fenceScsiAgent = fsAgent == null ? null : (fsAgent.trim() == '1')
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
        mpPolling = kv('multipath_queue').MP_POLLING?.isInteger() ? kv('multipath_queue').MP_POLLING as Integer : null
        mpFastIoFail = kv('multipath_queue').MP_FAST_IO_FAIL?.isInteger() ? kv('multipath_queue').MP_FAST_IO_FAIL as Integer : null
        parseFc(sections.fc)
        parseMpathPaths(sections.mpath_paths)
        parseIscsiTimers(sections.iscsi_timers)
        // transport per map, from the paths' %P; host-level fcPresent covers a map no path row matched
        maps.each { Map mp ->
            List<String> protos = mpathPaths.findAll { (it.wwid as String)?.equalsIgnoreCase(mp.wwid as String) }*.proto.findAll().unique() as List<String>
            mp.transport = protos.size() == 1 ? protos[0] : (protos.size() > 1 ? 'mixed' : null)
        }
        String pl = kv('mpath_log').PATHS_LOST_7D
        pathsLost7d = pl?.isInteger() ? pl as Integer : null
        pathsLostLast = kv('mpath_log').PATHS_LOST_LAST
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
                                                mtu: it.size() > 5 && it[5]?.isInteger() ? it[5] as Integer : null,
                                                agent: it.size() > 6 ? it[6] == '1' : null] }

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
        bmcSelftest = bm.BMC_SELFTEST?.trim() ?: null
        selUsedPct = bm.SEL_USED?.trim()?.isInteger() ? bm.SEL_USED.trim() as Integer : null
        selOverflow = bm.SEL_OVERFLOW?.trim() ? bm.SEL_OVERFLOW.trim() == 'true' : null
        List<List<String>> chassis = rows('bmc', 'CHASSIS').findAll { it.size() == 2 }
        chassisRead = !chassis.isEmpty()
        chassisFaults = chassis.findAll { it[1].trim() == 'true' }.collect { it[0].trim() }
        sensorsRead = bm.SDR_READ == '1'
        sensorFaults = rows('bmc', 'SDR_BAD').findAll { it.size() >= 3 }.collect { [name: it[0].trim(), status: it[1].trim(), reading: it[2].trim()] }
        parseQdevice()
        rows('vm_cpu', 'VM_CPU').each {
            if (it.size() >= 2) { vmCpus[it[0]] = it[1] }
            if (it.size() >= 3 && it[2] == '1') { vmNoMigrate << it[0] }
        }
        rows('pool_paths', 'POOL_PATH').each { if (it.size() == 2 && it[0]) { poolPaths[it[0]] = it[1] } }
        parseCerts()
        parseSmart()
        parseHvmcli()
        parseVmPorts()
        parseHostHealth()
        parseVmActivity()
        parsePatchExposure()
        parseKernelPosture()
        parseNetTiming()
        parseSec()
    }

    /** @@patch_exposure: whether an automatic update can reach this host and restart the cluster stack. Absent on a quick check. */
    private void parsePatchExposure() {
        if (!sections.containsKey('patch_exposure')) {
            return
        }
        Map<String, String> p = kv('patch_exposure')
        uuInstalled = p.UU_INSTALLED == 'ii'
        nrInstalled = p.NR_INSTALLED == 'ii'
        uuPeriodic = p.UU_PERIODIC ?: null
        uuTimer = p.UU_TIMER ?: null
        aptUbuntuSources = int0(p.APT_UBUNTU)
        nrListOnly = int0(p.NR_LISTONLY) > 0
        nrClusterExempt = int0(p.NR_EXEMPT)
        aptSourceFiles = (p.APT_SOURCES ?: '').tokenize(',')
    }

    /** @@kernel_posture: panic sysctls, taint, GFS2 mount error policy and PCIe AER. A value that could not be read stays absent, never a pass. */
    private void parseKernelPosture() {
        if (!sections.containsKey('kernel_posture')) {
            return
        }
        rows('kernel_posture', 'SYSCTL').each { if (it.size() == 2 && lng(it[1]) != null) { panicSysctls[it[0]] = lng(it[1]) } }
        Map<String, String> k = kv('kernel_posture')
        taint = lng(k.TAINT)
        gfs2MountOpts = rows('kernel_posture', 'GFS2_OPTS').findAll { it.size() == 2 && it[0] }.collect { [target: it[0], options: it[1]] }
        aerFatal = lng(k.AER_FATAL)
        aerNonfatal = lng(k.AER_NONFATAL)
        aerCorrectable = lng(k.AER_CORRECTABLE)
    }

    /** @@net_timing: bond failover timing, link-flap counters and linux-bridge VLAN filtering. */
    private void parseNetTiming() {
        if (!sections.containsKey('net_timing')) {
            return
        }
        bondTiming = rows('net_timing', 'BONDOPT').findAll { it.size() >= 8 && it[0] }.collect {
            [name: it[0], mode: it[1] ?: null, miimon: lng(it[2]), updelay: lng(it[3]), downdelay: lng(it[4]), arpInterval: lng(it[5]), lacpRate: it[6] ?: null, hashPolicy: it[7] ?: null]
        }
        linkFlaps = rows('net_timing', 'FLAP').findAll { it.size() >= 2 && it[0] }.collect { [name: it[0], changes: lng(it[1]), downCount: lng(it.size() > 2 ? it[2] : null)] }
        bridges = rows('net_timing', 'BRIDGE').findAll { it.size() >= 2 && it[0] }.collect { [name: it[0], vlanFiltering: it[1] ?: null, stpState: it.size() > 2 ? it[2] : null] }
    }

    /**
     * A probe value that may carry a tenant-chosen name: control characters gone, '|' gone, length capped. The probe sanitizes names before they
     * become fields, so this is the second line of that defence, for anything that reaches a view, an alarm or a report.
     */
    static String safeName(String s) {
        if (s == null) {
            return null
        }
        String t = s.replaceAll(/[\p{Cntrl}|]/, ' ').trim()
        t.length() > 120 ? t.take(120) : t
    }

    private static Boolean nestedYes(String v) {
        String t = v?.trim()
        t ? (t in ['Y', 'y', '1'] ? Boolean.TRUE : (t in ['N', 'n', '0'] ? Boolean.FALSE : null)) : null
    }

    private void parseSec() {
        if (!sections.containsKey('sec')) {
            return
        }
        Map<String, String> m = kv('sec')
        nestedIntel = nestedYes(m.KVM_NESTED_INTEL)
        nestedAmd = nestedYes(m.KVM_NESTED_AMD)
        smtControl = m.SMT_CONTROL ?: null
        smtActive = m.SMT_ACTIVE ?: null
        cpuCmdline = m.CPU_CMDLINE ?: null
        secCut = m.SEC_CUT == '1'
        rows('sec', 'CPU_VULN').each { if (it.size() >= 2 && it[0]) { cpuVulns[it[0]] = it[1] } }
        rows('sec', 'INFRA_VLAN').each { if (it.size() >= 2 && it[1]?.isInteger()) { infraVlans << [role: it[0], vlan: it[1] as Integer] } }
        rows('sec', 'BRIDGE_STP').each { if (it.size() >= 3 && it[0]) { secBridges << [bridge: it[0], stp: boolOrNull(it[1]), filtering: boolOrNull(it[2])] } }
        rows('sec', 'VM_SEC').each { if (it.size() >= 5 && it[0]) { vmSec << vmSecOf(it) } }
    }

    private static Boolean boolOrNull(String v) {
        v?.trim() == '1' ? Boolean.TRUE : (v?.trim() == '0' ? Boolean.FALSE : null)
    }

    /** One VM's effective CPU block, disk cache modes and limit flag, as the probe grepped them from its domain XML. */
    private static Map vmSecOf(List<String> r) {
        def mode = (r[1] ?: '') =~ /mode='([a-z-]+)'/
        def vmx = (r[2] ?: '') =~ /policy='([a-z]+)'[^,]*name='vmx'|name='vmx'[^,]*policy='([a-z]+)'/
        def svm = (r[2] ?: '') =~ /policy='([a-z]+)'[^,]*name='svm'|name='svm'[^,]*policy='([a-z]+)'/
        [vm: safeName(r[0]), cpuMode: mode.find() ? mode.group(1) : null,
         vmx: vmx.find() ? (vmx.group(1) ?: vmx.group(2)) : null, svm: svm.find() ? (svm.group(1) ?: svm.group(2)) : null,
         caches: ((r[3] ?: '').findAll(/cache='([a-z]+)'/) { it[1] }).unique(), limited: r[4]?.trim() == '1']
    }

    private static BigDecimal dec(String v) { v?.trim()?.isBigDecimal() ? v.trim() as BigDecimal : null }

    private void parseHostHealth() {
        List<String> swap = rows('host_sample', 'SWAP_SAMPLE').find { it.size() == 2 }
        Long swapIn = lng(swap?.getAt(0)), swapOut = lng(swap?.getAt(1))
        swapPerSec = swapIn == null || swapOut == null ? null : (swapIn + swapOut) / 2G
        List<String> cpu = rows('host_sample', 'CPU_SAMPLE').find { it.size() == 2 }
        Long ticks = lng(cpu?.getAt(0)), steal = lng(cpu?.getAt(1))
        stealPct = ticks && steal != null ? rate(steal * 100G, ticks, 1) : null
        if (!sections.containsKey('host_health')) {
            return
        }
        Map<String, String> h = kv('host_health')
        psiCpu = dec(h.PSI_CPU)
        psiMemory = dec(h.PSI_MEMORY)
        oomRead = h.OOM_READ == '1'
        vmLogsRead = h.VM_LOGS_READ == '1'
        rootFsCut = h.ROOT_DF_CUT == '1'
        failedUnitsRead = h.FAILED_READ == '1'
        failedUnits = rows('host_health', 'FAILED_UNIT').collect { it[0] }.findAll().unique()
        oomKills = rows('host_health', 'OOM').findAll { it.size() >= 2 }.collect { List<String> r ->
            String vm = scopeVm(r.size() > 2 ? r[2] : null)
            [at: isoSeconds(r[0]), comm: r[1], vm: vm, qemu: vm != null || r[1] ==~ /(?i).*(qemu|kvm).*/]
        }
        vmCrashes = rows('host_health', 'VM_CRASH').findAll { it.size() >= 2 && it[0] }.collect { [vm: it[0], at: crashSeconds(it[1])] }
        rootFs = rows('host_health', 'ROOT_DF').findAll { it.size() == 3 && it[0] }.collect { [target: it[0], pct: dec(it[1]), inodePct: dec(it[2])] }
    }

    /** The cgroup of a VM in the kernel's words: machine-qemu\x2d5\x2dvm\x2done.scope is vm-one, as libvirt spells it there, which can drop characters. */
    static String scopeVm(String scope) {
        def m = (scope ?: '') =~ /^machine-qemu\\x2d\d+\\x2d(.+)\.scope$/
        m.find() ? m.group(1).replace('\\x2d', '-') : null
    }

    /** libvirt's "2026-10-30 12:34:56.789+0000: shutting down, reason=crashed". */
    static Long crashSeconds(String line) {
        def m = (line ?: '') =~ /^(\d{4}-\d{2}-\d{2}) (\d{2}:\d{2}:\d{2})(?:\.\d+)?([+-]\d{2}:?\d{2})/
        m.find() ? isoSeconds("${m.group(1)}T${m.group(2)}${m.group(3)}".toString()) : null
    }

    private static BigDecimal rate(Number amount, Number per, int scale) {
        (amount as BigDecimal).divide(per as BigDecimal, scale, java.math.RoundingMode.HALF_UP)
    }

    /** Two whole samples or nothing. A VM in one sample only, or whose counters went backwards because it restarted between them, has no rate. */
    private void parseVmActivity() {
        Long ms = lng(kv('busiest').BUSY_MS)
        if (!ms || ms <= 0L) {
            return
        }
        Closure<Map<String, List<Long>>> sample = { String key ->
            rows('busiest', key).findAll { it.size() == 6 && it[0] && it.drop(1).every { String v -> lng(v) != null } }.collectEntries { [(it[0]): it.drop(1).collect { String v -> lng(v) }] }
        }
        Map<String, List<Long>> first = sample('BUSY_A')
        BigDecimal secs = ms / 1000G
        sample('BUSY_B').each { String vm, List<Long> b ->
            List<Long> a = first[vm]
            List<Long> d = a ? [b, a].transpose().collect { List<Long> p -> p[0] - p[1] } : null
            if (d && d.every { it >= 0L }) {
                vmActivity << [vm: vm, cpuPct: nproc > 0 ? rate(d[0] / 10000G, ms * nproc, 1) : null, readIops: rate(d[1], secs, 0), writeIops: rate(d[3], secs, 0),
                               readMBs: rate(d[2] / 1000000G, secs, 1), writeMBs: rate(d[4] / 1000000G, secs, 1)]
            }
        }
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
        Map<String, Map> raid = [:]
        (sections.smart ?: []).each { String l ->
            if (l.startsWith('BOOT_DISK=')) {
                disks[l.substring(10)] = [name: l.substring(10)]
            } else if (l.startsWith('SMART=') && l.contains('|')) {
                Map d = disks[l.substring(6, l.indexOf('|'))]
                if (d != null) { smartLine(d, l.substring(l.indexOf('|') + 1)) }
            }
        }
        (sections.raid ?: []).each { String l ->
            if (l.startsWith('RAID_DISK=')) {
                // "megaraid,0@6": the slot as smartctl names it, on /dev/bus/6
                List<String> p = l.substring(10).split('@', 2) as List<String>
                raid[l.substring(10)] = [name: l.substring(10), slot: p[0], bus: p.size() > 1 ? p[1] : null]
            } else if (l.startsWith('RAID_SMART=') && l.contains('|')) {
                Map d = raid[l.substring(11, l.indexOf('|'))]
                if (d != null) { smartLine(d, l.substring(l.indexOf('|') + 1)) }
            }
        }
        rows('smart', 'BOOT_HOST').each { if (it.size() == 2 && it[1] && disks[it[0]]) { disks[it[0]].scsiHost = it[1] } }
        bootDisks = disks.values() as List
        raidDisks = raid.values() as List
        raidDisksUnread = int0(kv('raid').RAID_DISKS_UNREAD)
    }

    /** hvmcli health check --json, as the probe printed it on one line. Output of any other shape reads as unreadable, which is never a pass. */
    private void parseHvmcli() {
        hvmcliState = kv('hvmcli_health').HVMCLI_STATE
        if (hvmcliState == 'read') {
            hvmcliChecks = hvmcliRows(sections.hvmcli_health.find { it.startsWith('HVMCLI_JSON=') }?.substring(12))
            hvmcliState = hvmcliChecks ? 'read' : 'unreadable'
        }
    }

    /** One host's output must never reach the tab unbounded or break a check, so what is kept is cut to size here, and text only. A failure hvmcli admits to is not an answer. */
    private static List<Map> hvmcliRows(String json) {
        try {
            Object o = new groovy.json.JsonSlurper().parseText(json)
            Object rows = o instanceof Map && o.errorCode in [0, null] && o.data instanceof Map ? o.data.checks : null
            rows instanceof List ? rows.findAll { it instanceof Map && it.check }.collect { Map c ->
                [check: clip(c.check, 80), type: clip(c.type, 20), status: clip(c.status, 12).toUpperCase(), details: clip(c.details, 200)]
            } : []
        } catch (Exception | StackOverflowError ignored) {
            []
        }
    }

    private static String clip(Object v, int max) {
        String s = v == null ? '' : v.toString().replaceAll(/\p{Cntrl}+/, ' ').trim()
        s.length() > max ? s.take(max) + '...' : s
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
        // ID NAME FLAG VALUE WORST THRESH TYPE UPDATED WHEN_FAILED RAW: "-" there is the normal answer
        def row = l =~ /^\s*\d+\s+(\S+)\s+0x[0-9a-fA-F]+\s+\S+\s+\S+\s+\S+\s+\S+\s+\S+\s+(\S+)/
        if (row.find() && row.group(2) != '-') { d.whenFailed = ((d.whenFailed ?: []) as List) + "${row.group(1)} (${row.group(2)})".toString() }
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
        rows('vm_ports', 'PORT_SKIP').findAll { it.size() >= 4 }.each { vmPorts << skipPort(it) }
    }

    /**
     * A macvtap (type direct, a Private network) or SR-IOV (type hostdev) port. It sits on no readable bridge, so its VLAN cannot be read here: it
     * counts as not read, which the isolation checks report as NOT CHECKED rather than pass.
     */
    private static Map skipPort(List<String> r) {
        String kind = r[3] == 'hostdev' ? 'sriov' : 'macvtap'
        [vm: safeName(r[0]), dev: r[1], mac: r[2], bridge: null, kind: kind, read: false, access: null, tagged: [], all: false, untagged: false,
         why: "${kind == 'sriov' ? 'SR-IOV passthrough' : 'macvtap (Private)'} port, which is on no bridge, so its VLAN cannot be read here".toString()]
    }

    /**
     * Open vSwitch: with no vlan_mode a tag makes an access port and no tag a trunk, of every VLAN when trunks is empty as well.
     * That last one is how 1.3 builds a port on an untagged network, so it stays its own case rather than reading as a trunk.
     */
    private static Map ovsPort(List<String> r) {
        // Open vSwitch prints [] for a column with nothing set, so a blank is a read that timed out. Taken as unset, it would read as a port with no tag, which is a finding
        if (r[4..6].any { !it?.trim() }) {
            return [vm: safeName(r[0]), dev: r[1], mac: r[2], bridge: r[3], kind: 'ovs', read: false, why: 'could not read the port from Open vSwitch', access: null, tagged: [], all: false, untagged: false]
        }
        Integer tag = r[4]?.trim()?.isInteger() ? r[4].trim() as Integer : null
        List<Integer> trunks = (r[5] ?: '').findAll(/\d+/).collect { it as Integer }
        String mode = r[6]?.trim() in ['[]', ''] ? null : r[6].trim().replace('"', '')
        Map p = [vm: safeName(r[0]), dev: r[1], mac: r[2], bridge: r[3], kind: 'ovs', read: true, access: null, tagged: [], all: false, untagged: false]
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
        Map p = [vm: safeName(r[0]), dev: r[1], mac: r[2], bridge: r[3], kind: 'bridge', read: false, access: null, tagged: [], all: false, untagged: false]
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

    /**
     * The agent's /quorum JSON and its .quorum-nodes file, both read defensively. HPE documents quorumAchieved, fenced, waitFencing,
     * reachableNodes, totalNodes, corosyncActive, dlmActive and corosyncQuorate, and a nodes list of a shape only partly known, so any
     * value that is missing or the wrong type stays null. The .quorum-nodes format is undocumented, so its tokens are extracted loosely.
     */
    private void parseAgentQuorum() {
        Map<String, String> q = kv('agent_quorum')
        Set<String> ids = [] as LinkedHashSet
        String raw = q.AGENT_QUORUM
        if (raw?.trim()) {
            try {
                Object o = new groovy.json.JsonSlurper().parseText(raw)
                if (o instanceof Map) {
                    Map j = o as Map
                    agentQuorum = [achieved: asBool(j.quorumAchieved), fenced: asBool(j.fenced), waitFencing: asBool(j.waitFencing),
                                   reachable: asInt(j.reachableNodes), total: asInt(j.totalNodes), corosyncActive: asBool(j.corosyncActive),
                                   dlmActive: asBool(j.dlmActive), corosyncQuorate: asBool(j.corosyncQuorate)]
                    ids.addAll(nodeTokens(j.nodes))
                }
            } catch (Exception | StackOverflowError ignored) {
                agentQuorum = null
            }
        }
        if (q.QUORUM_NODES?.trim()) {
            ids.addAll(rawTokens(q.QUORUM_NODES))
        }
        agentQuorumNodes = ids as List<String>
    }

    private static Boolean asBool(Object v) {
        v instanceof Boolean ? (Boolean) v : (v instanceof String && v.trim().toLowerCase() in ['true', 'false'] ? v.trim().toLowerCase() == 'true' : null)
    }

    private static Integer asInt(Object v) {
        v instanceof Number ? (v as Integer) : (v instanceof String && v.trim().isInteger() ? v.trim() as Integer : null)
    }

    /** Lower-cased member identifiers from a JSON value of any shape: a list of names or of objects with a name/address/id field, or a map keyed by them. */
    private static List<String> nodeTokens(Object v) {
        List<String> out = []
        if (v instanceof List) {
            (v as List).each { Object n ->
                if (n instanceof Map) {
                    ['name', 'hostname', 'host', 'address', 'addr', 'ip', 'id', 'nodeId', 'nodeid'].each { String k -> if ((n as Map)[k] != null) { out << (n as Map)[k].toString() } }
                } else if (n != null) {
                    out << n.toString()
                }
            }
        } else if (v instanceof Map) {
            (v as Map).each { k, val -> if (val instanceof List || val instanceof Map) { out.addAll(nodeTokens(val)) } else { out << k.toString() } }
        } else if (v != null) {
            out << v.toString()
        }
        out.collect { it.trim().toLowerCase() }.findAll { it && it != 'null' }.unique()
    }

    /** The .quorum-nodes file as JSON if it parses, otherwise its host/address-like words. */
    private static List<String> rawTokens(String s) {
        try {
            Object o = new groovy.json.JsonSlurper().parseText(s)
            if (o instanceof List || o instanceof Map) {
                return nodeTokens(o)
            }
        } catch (Exception ignored) {
        }
        (s.split(/[^A-Za-z0-9._:-]+/) as List<String>).findAll { it }.collect { it.toLowerCase() }.unique()
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
                def w = l =~ /\bwp=(\S+)/
                if (w.find()) { cur.wp = w.group(1) }
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

    /** /sys/class/fc_host and fc_remote_ports. A null section means the probe did not look; an empty one means no Fibre Channel. */
    private void parseFc(List<String> lines) {
        if (lines == null) {
            return
        }
        fcPresent = kv('fc').FC_PRESENT == '1'
        rows('fc', 'FC_HOST').findAll { it.size() >= 8 }.each {
            fcHosts << [host: it[0], portState: it[1], speed: it[2], portName: it[3],
                        linkFailures: lng(it[4]) ?: 0L, lossOfSync: lng(it[5]) ?: 0L, lossOfSignal: lng(it[6]) ?: 0L, invalidCrc: lng(it[7]) ?: 0L]
        }
        rows('fc', 'FC_RPORT').findAll { it.size() >= 5 }.each {
            fcRports << [rport: it[0], portState: it[1], roles: it[2], devLossTmo: lng(it[3]), fastIoFailTmo: lng(it[4])]
        }
    }

    /** multipathd show paths format, pipe-joined by the probe. %A (TPG) and %k are only asked for, and present, on 0.14.3 (26.04). */
    private void parseMpathPaths(List<String> lines) {
        if (lines == null) {
            return
        }
        mpathPathsExt = kv('mpath_paths').MPP_EXT == '1'
        rows('mpath_paths', 'MPP').findAll { it.size() >= 4 }.each {
            Map p = [wwid: it[0], dev: it[1], proto: normProto(it[2]), adapter: it[3]]
            if (it.size() >= 6) {
                p.tpg = it[4]
                p.maxSectors = it[5]?.isInteger() ? it[5] as Integer : null
            }
            mpathPaths << p
        }
    }

    /** multipathd prints protocol as scsi:iscsi, scsi:fcp, scsi:sas, nvme:tcp; reduce to the family word. */
    private static String normProto(String p) {
        String s = (p ?: '').toLowerCase()
        s.contains('fcp') ? 'fcp' : (s.contains('iscsi') ? 'iscsi' : (s.contains('sas') ? 'sas' : (s.contains('nvme') ? 'nvme' : (s ?: null))))
    }

    private void parseIscsiTimers(List<String> lines) {
        if (lines == null) {
            return
        }
        rows('iscsi_timers', 'NODE_STARTUP').findAll { it.size() == 2 && it[0] }.each { iscsiNodes << [target: it[0], startup: it[1]] }
        rows('iscsi_timers', 'RECOVERY_TMO').findAll { it.size() == 2 }.each { iscsiTmo << [session: it[0], recoveryTmo: (it[1]?.isInteger() ? it[1] as Integer : null)] }
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
        List<Integer> diverged = divergedWaits
        lockspaces.findAll { (it.status as String)?.contains('wait fencing') }.collectMany { (ids(it.members) - ids(it.newMembers)) as List<Integer> }.unique().findAll { !(it in fenceWaits*.node) && !(it in diverged) } as List<Integer>
    }

    /**
     * The '-125' split state: a stopped lockspace waits to fence a node that dlm_controld is NOT fencing (no fence line) and whose daemon
     * record shows a departed non-member (state X) whose fence time never caught up to its failure. From dlm 4.2.0 source a manual
     * dlm_tool fence_ack is then refused with -125 (no need_fencing), so only a clean rejoin of the node clears it. Narrower than
     * inferredWaits, which keeps a node with no such record (it may be a startup wait, where the acknowledgement works). Built from source,
     * seen once in production (27 minutes) and not reproduced; 2.0 (dlm 4.3.0) behaviour here is unconfirmed.
     */
    List<Integer> getDivergedWaits() {
        Closure<Set<Integer>> ids = { Object s -> ((s ?: '') as String).tokenize().findAll { it.isInteger() }.collect { it as Integer } as Set<Integer> }
        lockspaces.findAll { (it.status as String)?.contains('wait fencing') }.collectMany { (ids(it.members) - ids(it.newMembers)) as List<Integer> }.unique().findAll { Integer n ->
            !(n in fenceWaits*.node) && dlmNodes.any { it.node == n && (it.state as String) == 'X' && (it.fail as Long) > 0L && (it.fence as Long) < (it.fail as Long) }
        } as List<Integer>
    }

    /** When the last GFS2 error was logged, in seconds, from the line's own stamp; null if it cannot be read. */
    Long getGfsErrorAt() {
        isoSeconds(gfsErrorLast)
    }

    /** When the last DLM conversion failure (-22) was logged, in seconds; null if it cannot be read. */
    Long getDlmConvErrorAt() {
        isoSeconds(dlmConvErrorLast)
    }

    /** A journalctl -o short-iso stamp at the start of a line, in seconds; null if it cannot be read. */
    static Long isoSeconds(String line) {
        def m = (line ?: '') =~ /^(\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}[+-]\d{2}:?\d{2})/
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
