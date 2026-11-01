package org.hvmhealth.morpheus

import groovy.util.logging.Slf4j
import org.hvmhealth.checks.HealthChecker
import org.hvmhealth.checks.HealthChecker.Result

import java.util.regex.Matcher
import java.util.regex.Pattern

/**
 * Which customers a finding reaches. Operators only: the tab and alarms carry this, and HealthReport must never, as a report can leave the tenant.
 * "reach" is not the Result's impact field, which is older and counts every VM on the cluster for anything not on a host.
 *
 * What was not read is never reported as none. A VM on a host that did not answer has a state nobody knows, because Morpheus stops reporting VMs as running on a silent
 * host. A disk Morpheus does not place may be on the datastore in question. And no VM list at all, with no host answering, is not an empty cluster.
 */
@Slf4j
class Impact {
    static final int MAX_TENANTS = 5
    /** Findings about one datastore, and where the title says which: GFS-04's a mount path, GFS-09's a pool name, or the path where the pool has none. */
    static final Map<String, Pattern> DATASTORE_TITLE = ['GFS-04': ~/: (.+)$/, 'GFS-09': ~/ onto (.+)$/, 'GFS-11': ~/: (.+)$/]

    static Map of(HealthChecker.Input in_, Result r) {
        ofAll(in_, [r])
    }

    /** One group's reach: every VM any of these findings reaches, counted once. It is known if the first finding's is, as that is the group's root. */
    static Map ofAll(HealthChecker.Input in_, List<Result> rs) {
        Set<String> silent = in_.hosts.findAll { !it.facts?.complete }*.name as Set<String>
        List<Map> scopes = rs.collect { scope(in_, it, silent) }
        Set<Map> vms = Collections.newSetFromMap(new IdentityHashMap<Map, Boolean>())
        Set<Map> maybe = Collections.newSetFromMap(new IdentityHashMap<Map, Boolean>())
        scopes.each { vms.addAll(it.vms as List<Map>) }
        scopes.each { maybe.addAll(it.maybe as List<Map>) }
        maybe.removeAll(vms)
        Map first = scopes ? scopes[0] : [known: false, why: 'nothing to read']
        tally(vms, maybe, scopes.collectMany { (it.unrecorded ?: []) as List<String> }.unique().size(), first.known as boolean, first.why as String, silent)
    }

    /** The text for an alarm. Null rather than an exception: an alarm must still be raised when this cannot be worked out. */
    static String textOf(HealthChecker.Input in_, List<Result> rs) {
        try {
            ofAll(in_, rs).text
        } catch (Exception e) {
            log.warn("could not work out who a finding on ${in_?.clusterName} reaches: ${e.message}")
            null
        }
    }

    /** Which datastore a finding is about, by Morpheus's name where the probe gives one; null when it is about a host or the cluster. */
    static String datastore(HealthChecker.Input in_, Result r) {
        Pattern p = DATASTORE_TITLE[r.id]
        Matcher m = p ? p.matcher(r.title ?: '') : null
        if (!m?.find()) {
            return null
        }
        String named = m.group(1)
        (in_.hosts.findResult { it.facts?.poolPaths?.get(named) } ?: named) as String
    }

    private static Map scope(HealthChecker.Input in_, Result r, Set<String> silent) {
        List<Map> vms = in_.morpheusVms ?: []
        if (DATASTORE_TITLE.containsKey(r.id)) {
            return datastoreScope(in_, r, vms)
        }
        List<HealthChecker.Host> hosts = r.host ? in_.hosts.findAll { it.name == r.host } : in_.hosts
        List<Map> here = r.host ? vms.findAll { it.host == r.host } : vms
        Set<String> onRecord = vms*.name as Set<String>
        List<String> unrecorded = hosts.findAll { it.facts?.complete }.collectMany { h ->
            (h.facts.vms ?: []).findAll { it.state == 'running' && !onRecord.contains(it.name) }.collect { "${h.name}|${it.name}".toString() }
        }
        boolean anyAnswered = hosts.any { it.facts?.complete }
        if (!here && !anyAnswered) {
            return [known: false, why: 'no host here answered, and Morpheus has no VMs on record for it', vms: [], maybe: []]
        }
        [known: true, vms: here, maybe: [], unrecorded: unrecorded]
    }

    /**
     * A disk with no datastore, or on one no host has as a pool, may be on this one under another name: Morpheus's datastore names are not proven to match
     * libvirt's pools. So no VM found here is only "none" when every VM's disks are placed on pools the hosts know.
     */
    private static Map datastoreScope(HealthChecker.Input in_, Result r, List<Map> vms) {
        String name = datastore(in_, r)
        if (!name) {
            return [known: false, why: 'the finding does not say which datastore', vms: [], maybe: []]
        }
        Set<String> pools = in_.hosts.collectMany { it.facts?.pools?.collect { p -> p.name } ?: [] } as Set<String>
        List<Map> on = vms.findAll { v -> (v.disks as List<Map>)?.any { it.ds == name } }
        List<Map> maybe = (vms - on).findAll { v -> !v.disks || (v.disks as List<Map>).any { it.ds == null || !pools.contains(it.ds) } }
        boolean known = on || (vms && !maybe)
        known ? [known: true, vms: on, maybe: maybe] : [known: false, why: 'Morpheus does not say which VMs have disks on this datastore', vms: [], maybe: []]
    }

    private static Map tally(Collection<Map> vms, Collection<Map> maybe, int unrecorded, boolean known, String why, Set<String> silent) {
        if (!known) {
            return [known: false, running: 0, unknownState: 0, operator: 0, maybe: 0, unrecorded: 0, tenants: [], tenantCount: 0, text: "affects an unknown number of VMs: ${why}".toString()]
        }
        int running = 0, unknown = 0, operator = 0
        Map<Object, Map> tenants = [:]
        vms.each { Map v ->
            boolean stateKnown = !silent.contains(v.host) && v.power in ['on', 'off', 'paused']
            if (stateKnown && v.power != 'on') {
                return
            }
            if (v.operator == true) {
                operator++   // a null is an account that could not be loaded, and it may be a customer's
                return
            }
            stateKnown ? running++ : unknown++
            Map t = tenants.computeIfAbsent(v.tenantId ?: v.tenant ?: 'unknown') { [name: (v.tenant ?: 'unknown tenant') as String, count: 0] }
            t.count = (t.count as int) + 1
        }
        List<Map> ranked = tenants.values().sort(false) { a, b -> (b.count as int) <=> (a.count as int) ?: a.name <=> b.name }
        Map out = [known: true, running: running, unknownState: unknown, operator: operator, maybe: maybe.size(), unrecorded: unrecorded,
                   tenants: ranked.take(MAX_TENANTS), tenantCount: ranked.size()]
        out.text = text(out)
        out
    }

    private static String count(int n, String one) {
        "${n} ${one}${n == 1 ? '' : 's'}".toString()
    }

    /** affects 14 running VMs in 3 tenants: A (9), B (4), C (1) */
    private static String text(Map t) {
        int running = t.running as int, unknown = t.unknownState as int, tenantCount = t.tenantCount as int
        StringBuilder s = new StringBuilder('affects ')
        if (running || unknown) {
            s << count(running, 'running VM')
            if (unknown) {
                s << " and ${unknown} whose state is not known"
            }
            s << " in ${count(tenantCount, 'tenant')}: " << (t.tenants as List<Map>).collect { "${it.name} (${it.count})" }.join(', ')
            if (tenantCount > MAX_TENANTS) {
                s << ", and ${tenantCount - MAX_TENANTS} more"
            }
        } else {
            s << 'no running tenant VMs'
        }
        if (t.operator) {
            s << ", plus ${count(t.operator as int, 'operator VM')}"
        }
        if (t.unrecorded) {
            s << ", plus ${count(t.unrecorded as int, 'running VM')} Morpheus has no record of"
        }
        if (t.maybe) {
            s << ", and up to ${t.maybe} more whose disks Morpheus does not place"
        }
        s.toString()
    }
}
