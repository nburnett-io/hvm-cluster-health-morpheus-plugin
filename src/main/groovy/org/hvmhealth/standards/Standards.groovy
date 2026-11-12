package org.hvmhealth.standards

import groovy.json.JsonSlurper

/**
 * The values clusters are checked against: a built-in baseline per layout, with site overrides on top.
 *
 * Every value carries where it came from, because HPE publishes little on this: see docs/STANDARDS.md. A value is one of
 *   hpe       - stated in HPE Morpheus documentation
 *   upstream  - corosync, DLM or GFS2 documentation
 *   morpheus  - what a Morpheus-deployed cluster of that layout is built with
 *   plugin    - this plugin's judgement; a reasonable default rather than a vendor rule
 *   site      - set by this installation in the "Site standards" setting
 *   cluster   - set for one cluster on its tab
 *
 * Site standards are a JSON object in the plugin setting, for example:
 *   {"minHosts": 3, "gfs2WarnPct": 75, "profiles": {"2.0": {"corosyncToken": 30000}}, "disabledChecks": ["PKG-01"]}
 * Top-level keys apply to every layout; "profiles" overrides them per layout version.
 */
class Standards {
    static final Map<String, Map> DEFAULTS = [
            minHosts          : [value: 3, source: 'hpe', note: 'HVM HA and GFS2 need quorum: three hosts'],
            mpio              : [value: true, source: 'hpe', note: 'MPIO for LUN-backed GFS2 datastores'],
            fencingEnabled    : [value: true, source: 'upstream', note: 'dlm.conf enable_fencing=1: GFS2 must not recover until a failed node is known down'],
            consensusRatio    : [value: 1.2, source: 'upstream', note: 'corosync.conf(5): consensus at least 1.2 x token'],
            corosyncToken     : [value: null, source: 'morpheus', note: 'Pinned per layout when known'],
            corosyncConsensus : [value: null, source: 'morpheus', note: 'Pinned per layout when known'],
            gfs2WarnPct       : [value: 80, source: 'hpe', note: 'GFS2 datastore usage warning: HPE\'s documented datastore usage alarm is 80%'],
            gfs2FailPct       : [value: 90, source: 'hpe', note: 'GFS2 datastore usage failure: HPE\'s documented critical datastore alarm is 90%'],
            forecastWarnDays  : [value: 30, source: 'plugin', note: 'Warn when N+1 memory or a datastore is forecast to run out within this many days'],
            vcpuWarnRatio     : [value: 4, source: 'hpe', note: 'vCPUs of running VMs per host thread that warn: HPE\'s capacity guidance is 2:1 general, 4:1 dev/test, 8:1+ with caution'],
            vcpuFailRatio     : [value: 8, source: 'hpe', note: 'vCPUs of running VMs per host thread that fail: HPE calls 8:1 and above "use with caution"'],
            heartbeatMaxAgeSec: [value: 120, source: 'plugin', note: 'HVM heartbeat files are rewritten about every 20 s'],
            agentMaxAgeSec    : [value: 300, source: 'plugin', note: 'Oldest acceptable Morpheus agent check-in'],
        agentMinVersion   : [value: '4.0.1', source: 'plugin', note: 'Oldest Morpheus agent seen to acknowledge the right node in a freeze: 3.2.6 did not'],
            maxClockSkewSec   : [value: 5, source: 'plugin', note: 'Between hosts when they share no time server, which leaves only a coarse comparison'],
            maxHostDriftMs    : [value: 50, source: 'plugin', note: 'Between hosts, measured against the time servers they share'],
            clusterCpuModel   : [value: null, source: 'plugin', note: 'The CPU model this cluster must use (the EVC equivalent). Set it to check the cluster keeps to it'],
            ntpServers        : [value: null, source: 'plugin', note: 'This cluster\'s time sources (a list). Define them on the tab, or here for every cluster'],
            maxNtpOffsetMs    : [value: 100, source: 'plugin', note: 'A host\'s offset from its time source; live migration and cluster timers both suffer from drift'],
            ringRttWarnMs     : [value: 5, source: 'plugin', note: 'Round trip between corosync ring addresses'],
            linkFlapWarnPerHour: [value: 5, source: 'plugin', note: 'NIC carrier changes per hour since boot that warn; anchored on switch err-disable defaults (5 flaps in 10 s), so this is conservative'],
            storageWarnMs     : [value: 20, source: 'plugin', note: 'Average I/O wait a host measures to a LUN over 2 s'],
            storageFailMs     : [value: 100, source: 'plugin', note: 'Average I/O wait that fails the check'],
            gfs2StatWarnMs    : [value: 500, source: 'plugin', note: 'Time to read a heartbeat file\'s attributes, which takes a cluster lock'],
            selDays           : [value: 7, source: 'plugin', note: 'How far back serious hardware log events count'],
            hungTasksWarn     : [value: 1, source: 'plugin', note: 'Kernel hung-task reports in the last hour'],
            iscsiErrorsWarn   : [value: 1, source: 'plugin', note: 'iSCSI session timeouts and offline-device errors in 24 h'],
            noPathRetryPolicy : [value: 180, source: 'plugin', note: 'Ceiling in seconds for the no_path_retry window (STO-02): longer and held writes can land after the host is declared dead. The floor is the corosync token. No vendor ties a value to GFS2, so this is a site policy, not a vendor rule'],
            expectedPaths     : [value: null, source: 'plugin', note: 'Paths expected per LUN (STO-12). Unset by default; vendor designs often use 4. WARN below it. Overridable, as test environments run fewer'],
            osVersion         : [value: null, source: 'hpe', note: 'Ubuntu release the layout is built for'],
            minKernel         : [value: null, source: 'plugin', note: 'Lowest running kernel that carries the GFS2/DLM freeze fixes (KRN-04). Pinned per layout; the exact version is unverified against HVM OS, so it is site-settable'],
            certWarnDays      : [value: 30, source: 'plugin', note: 'Days left on a libvirt or appliance certificate that warn'],
            certFailDays      : [value: 7, source: 'plugin', note: 'Days left on a libvirt or appliance certificate that fail'],
            trunkAllowedVms   : [value: null, source: 'plugin', note: 'VMs meant to have a trunk port (a list of libvirt domain names), such as a firewall. Morpheus has no record of this'],
            swapWarnPagesPerSec: [value: 50, source: 'plugin', note: 'Pages swapped in and out per second over the 2 s sample. A few are normal; a host doing this steadily is short of memory'],
            cpuPressureWarnPct: [value: 25, source: 'plugin', note: 'Share of the last minute with a task waiting for CPU (/proc/pressure/cpu, some avg60)'],
            cpuPressureFailPct: [value: 60, source: 'plugin', note: 'The same, failing the check'],
            memPressureWarnPct: [value: 5, source: 'plugin', note: 'Share of the last minute with a task waiting for memory (/proc/pressure/memory, some avg60)'],
            memPressureFailPct: [value: 20, source: 'plugin', note: 'The same, failing the check'],
            rootDiskWarnPct   : [value: 85, source: 'plugin', note: 'Space or inodes used on the host\'s own filesystems'],
            rootDiskFailPct   : [value: 95, source: 'plugin', note: 'The same, failing the check'],
            hvmcliIgnore      : [value: ['Cluster status', 'Cluster nodes online', 'Cluster failed resources', 'Cluster STONITH'], source: 'plugin', note: 'HPE health checks that raise no finding, by name. These four read Pacemaker, which HVM masks by design, so they warn on every healthy cluster'],
            failedUnitsIgnore : [value: [], source: 'plugin', note: 'Failed systemd units that raise no HST-09, by name, with or without .service. None by default: a unit that fails on every host still says something about the hosts'],
            kernelCveMin      : [value: null, source: 'plugin', note: 'Oldest kernel for this layout that carries the fix for the KVM guest-to-host escapes (Januscape, Zapscape). Pinned per layout to the Ubuntu fix level; unverified against HPE\'s build, so site-settable'],
    ]

    /** Layout-specific baseline. Both token values for 1.3 are what Morpheus writes into corosync.conf on that layout. */
    static final Map<String, Map<String, Map>> PROFILES = [
            '1.3': [
                    corosyncToken    : [value: 60000, source: 'morpheus', note: 'corosync.conf as deployed by the 1.3 layout'],
                    corosyncConsensus: [value: 72000, source: 'morpheus', note: 'corosync.conf as deployed by the 1.3 layout'],
                    osVersion        : [value: '24.04', source: 'hpe', note: 'Layout code: Ubuntu 24.04'],
                    minKernel        : [value: '6.8.0-100', source: 'plugin', note: 'A 6.8 floor from the Ubuntu CVE/changelog reading (CVE page -100). No 6.8 carries the DLM request-cancel or QUECVT fixes, so KRN-04 warns below and still says so above'],
                    kernelCveMin     : [value: '6.8.0-147', source: 'plugin', note: 'Ubuntu noble fix level for CVE-2026-53359 and CVE-2026-64561; unverified against HPE\'s 24.04 build'],
            ],
            '2.0': [
                    osVersion   : [value: '26.04', source: 'hpe', note: 'Layout code: Ubuntu 26.04'],
                    minKernel   : [value: '7.0.0', source: 'plugin', note: 'The 7.0 base carries the DLM/GFS2 freeze fixes 6.8 lacks. Raise it (e.g. 7.0.0-28 for the later unmount CVE fix) for a stricter floor'],
                    kernelCveMin: [value: '7.0.0-38', source: 'plugin', note: 'Ubuntu resolute fix level for CVE-2026-53359 and CVE-2026-64561; unverified against HPE\'s 26.04 build'],
            ],
    ]

    final Map<String, Map> effective
    final Set<String> disabledChecks
    final String profile

    private Standards(String profile, Map<String, Map> effective, Set<String> disabled) {
        this.profile = profile
        this.effective = effective
        this.disabledChecks = disabled
    }

    /** @throws IllegalArgumentException when the site JSON cannot be read: a broken standard must not silently pass */
    static Standards forLayout(Layout layout, String siteJson = null) {
        String p = layout?.profile ?: 'unknown'
        Map<String, Map> out = [:]
        DEFAULTS.each { k, v -> out[k] = new LinkedHashMap(v) }
        (PROFILES[p] ?: [:]).each { k, v -> out[k] = new LinkedHashMap(v) }
        Map site = parseSite(siteJson)
        Closure apply = { Map vals, String note ->
            vals.each { k, v ->
                if (k in ['profiles', 'disabledChecks']) {
                    return
                }
                if (!DEFAULTS.containsKey(k)) {
                    throw new IllegalArgumentException("Site standards: unknown key \"${k}\". Known keys: ${DEFAULTS.keySet().join(', ')}")
                }
                checkType(k as String, v)
                out[k as String] = [value: v, source: 'site', note: note]
            }
        }
        apply(site, 'Site standard')
        if (site.profiles != null && !(site.profiles instanceof Map && (site.profiles as Map).values().every { it instanceof Map })) {
            throw new IllegalArgumentException('Site standards: "profiles" must be an object of objects, by layout version')
        }
        Map perLayout = (site.profiles as Map)?.get(p) as Map
        if (perLayout) {
            apply(perLayout, "Site standard for HVM ${p}")
        }
        if (site.disabledChecks != null && !stringList(site.disabledChecks)) {
            throw new IllegalArgumentException('Site standards: "disabledChecks" must be a list of check ids')
        }
        Set<String> disabled = ((site.disabledChecks ?: []) as List<String>) as Set<String>
        new Standards(p, out, disabled)
    }

    private static boolean stringList(Object v) {
        v instanceof List && (v as List).every { it instanceof String }
    }

    /** A string where a list belongs becomes single characters, and a word where a number belongs fails every check, so each is refused here, by name. */
    private static void checkType(String key, Object v) {
        if (v == null && DEFAULTS[key].value == null) {
            return
        }
        boolean ok
        if (key in ['ntpServers', 'trunkAllowedVms', 'hvmcliIgnore', 'failedUnitsIgnore']) {
            ok = stringList(v)
        } else if (key in ['clusterCpuModel', 'osVersion', 'agentMinVersion', 'minKernel', 'kernelCveMin']) {
            ok = v instanceof String
        } else if (DEFAULTS[key].value instanceof Boolean) {
            ok = v instanceof Boolean
        } else {
            ok = v instanceof Number
        }
        if (!ok) {
            throw new IllegalArgumentException("Site standards: \"${key}\" has the wrong type: ${v}")
        }
    }

    private static Map parseSite(String json) {
        if (!json?.trim()) {
            return [:]
        }
        Object o
        try {
            o = new JsonSlurper().parseText(json)
        } catch (Exception e) {
            throw new IllegalArgumentException("Site standards setting is not valid JSON: ${e.message?.readLines()?.first()}")
        }
        if (!(o instanceof Map)) {
            throw new IllegalArgumentException('Site standards setting must be a JSON object')
        }
        o as Map
    }

    /** A value that belongs to one cluster rather than to the site or the layout. */
    void set(String key, Object value, String source, String note) {
        effective[key] = [value: value, source: source, note: note]
    }

    def val(String key) {
        effective[key]?.value
    }

    int intVal(String key) {
        val(key) as Integer
    }

    String source(String key) {
        effective[key]?.source
    }

    /** For the tab: key, value, source, note. */
    List<Map> toList() {
        effective.collect { k, v -> [key: k, value: v.value, source: v.source, note: v.note] }
    }
}
