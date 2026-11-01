package org.hvmhealth.morpheus

/**
 * What a monitor outside Morpheus should be told about a cluster. Detection never stops: a change window holds this answer, not the checks.
 * The findings below are never held, because no planned work makes them acceptable.
 */
class Paging {
    static final Set<String> ALWAYS_PAGE = ['DLM-01', 'GFS-01', 'GFS-10', 'CLU-03', 'VM-01', 'NET-12', 'NET-13'] as Set

    /** Whether a change window is holding this cluster's paging. */
    static boolean held(Long clusterId, long now = System.currentTimeMillis()) {
        ChangeWindows.current(clusterId, now) != null
    }

    /** OK, WARNING or CRITICAL for one evaluation; UNKNOWN when there is none and the freeze watch does not hold it frozen. Staleness is the caller's, as it depends on the check interval. */
    static String state(Long clusterId, Map view, long now = System.currentTimeMillis()) {
        if (FreezeWatch.FROZEN_SINCE.containsKey(clusterId)) {
            return 'CRITICAL'
        }
        if (!view) {
            return 'UNKNOWN'
        }
        if ((view.hosts as List<Map>)?.any { it.frozen } || (view.results as List<Map>)?.any { it.status == 'FAIL' && it.id in ALWAYS_PAGE }) {
            return 'CRITICAL'
        }
        if (held(clusterId, now)) {
            return 'OK'
        }
        switch (view.overall) {
            case 'CRITICAL': return 'CRITICAL'
            case ['DEGRADED', 'WARNING']: return 'WARNING'
            default: return 'OK'
        }
    }
}
