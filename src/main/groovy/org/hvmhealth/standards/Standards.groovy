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
            quorumFencing     : [value: false, source: 'morpheus', note: 'dlm.conf enable_quorum_fencing=0, as deployed'],
            consensusRatio    : [value: 1.2, source: 'upstream', note: 'corosync.conf(5): consensus at least 1.2 x token'],
            corosyncToken     : [value: null, source: 'morpheus', note: 'Pinned per layout when known'],
            corosyncConsensus : [value: null, source: 'morpheus', note: 'Pinned per layout when known'],
            gfs2WarnPct       : [value: 80, source: 'plugin', note: 'GFS2 datastore usage warning'],
            gfs2FailPct       : [value: 90, source: 'plugin', note: 'GFS2 datastore usage failure'],
            heartbeatMaxAgeSec: [value: 120, source: 'plugin', note: 'HVM heartbeat files are rewritten about every 20 s'],
            agentMaxAgeSec    : [value: 300, source: 'plugin', note: 'Oldest acceptable Morpheus agent check-in'],
            maxClockSkewSec   : [value: 5, source: 'plugin', note: 'Between hosts when they share no time server, which leaves only a coarse comparison'],
            maxHostDriftMs    : [value: 50, source: 'plugin', note: 'Between hosts, measured against the time servers they share'],
            clusterCpuModel   : [value: null, source: 'plugin', note: 'The CPU model this cluster must use (the EVC equivalent). Set it to check the cluster keeps to it'],
            ntpServers        : [value: null, source: 'plugin', note: 'This cluster\'s time sources (a list). Define them on the tab, or here for every cluster'],
            maxNtpOffsetMs    : [value: 100, source: 'plugin', note: 'A host\'s offset from its time source; live migration and cluster timers both suffer from drift'],
            ringRttWarnMs     : [value: 5, source: 'plugin', note: 'Round trip between corosync ring addresses'],
            storageWarnMs     : [value: 20, source: 'plugin', note: 'Average I/O wait a host measures to a LUN over 2 s'],
            storageFailMs     : [value: 100, source: 'plugin', note: 'Average I/O wait that fails the check'],
            gfs2StatWarnMs    : [value: 500, source: 'plugin', note: 'Time to read a heartbeat file\'s attributes, which takes a cluster lock'],
            selDays           : [value: 7, source: 'plugin', note: 'How far back serious hardware log events count'],
            hungTasksWarn     : [value: 1, source: 'plugin', note: 'Kernel hung-task reports in the last hour'],
            iscsiErrorsWarn   : [value: 1, source: 'plugin', note: 'iSCSI session timeouts and offline-device errors in 24 h'],
            osVersion         : [value: null, source: 'hpe', note: 'Ubuntu release the layout is built for'],
    ]

    /** Layout-specific baseline. Both token values for 1.3 are what Morpheus writes into corosync.conf on that layout. */
    static final Map<String, Map<String, Map>> PROFILES = [
            '1.3': [
                    corosyncToken    : [value: 60000, source: 'morpheus', note: 'corosync.conf as deployed by the 1.3 layout'],
                    corosyncConsensus: [value: 72000, source: 'morpheus', note: 'corosync.conf as deployed by the 1.3 layout'],
                    osVersion        : [value: '24.04', source: 'hpe', note: 'Layout code: Ubuntu 24.04'],
            ],
            '2.0': [
                    osVersion: [value: '26.04', source: 'hpe', note: 'Layout code: Ubuntu 26.04'],
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
                out[k as String] = [value: v, source: 'site', note: note]
            }
        }
        apply(site, 'Site standard')
        Map perLayout = (site.profiles instanceof Map ? (site.profiles as Map)[p] : null) as Map
        if (perLayout) {
            apply(perLayout, "Site standard for HVM ${p}")
        }
        Set<String> disabled = ((site.disabledChecks ?: []) as List<String>) as Set<String>
        new Standards(p, out, disabled)
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
