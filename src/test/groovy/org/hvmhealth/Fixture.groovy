package org.hvmhealth

import org.hvmhealth.checks.HealthChecker
import org.hvmhealth.model.HostFacts
import org.hvmhealth.standards.Layout
import org.hvmhealth.standards.Standards

/** Builds clusters out of the sample probe outputs. */
class Fixture {
    static final String LAYOUT_13 = 'mvm-1.3-ubuntu-24.04-std-morpheus-amd64'
    static final String LAYOUT_20 = 'mvm-2.0-virtswitch-ubuntu-26.04-morpheus-amd64'

    static String text(String name) {
        Fixture.getResourceAsStream("/${name}.txt").getText('UTF-8')
    }

    /** The sample as if it came from {@code host}; {@code edit} changes the raw probe text. */
    static HostFacts facts(String sample, String host, Closure<String> edit = { it }) {
        String t = edit.call(text(sample).replace('HOSTNAME=hvm-node02', "HOSTNAME=${host}"))
        HostFacts.parse(t)
    }

    static HealthChecker.Host host(String name, HostFacts f, Map opts = [:]) {
        new HealthChecker.Host(name, opts.maintenance == true, (opts.member ?: 'online') as String, opts.status as String, null, f)
    }

    static HealthChecker.Input input(List<HealthChecker.Host> hosts, String layoutCode, String site = null, List<Map> vms = []) {
        Layout l = Layout.of(layoutCode)
        new HealthChecker.Input('cluster-x', l, hosts, vms, Standards.forLayout(l, site))
    }

    /** Three healthy 2.0 hosts, with the sample's VM on the first only. */
    static HealthChecker.Input healthy(String site = null) {
        input((1..3).collect { int n ->
            String name = "hvm-node0${n}".toString()
            host(name, facts('probe-healthy', name) { String t -> n == 1 ? t : t.replace(' 5    vm-one   running', '') })
        }, LAYOUT_20, site)
    }

    /**
     * The failure this plugin exists for: node 1 taken out of Morpheus and rebuilt without leaving the cluster, so corosync
     * and DLM still count it, while the HVM agents on the two survivors acknowledge each other and never it.
     */
    static HealthChecker.Input frozen() {
        HostFacts two = facts('probe-frozen', 'hvm-node02')
        HostFacts three = facts('probe-frozen', 'hvm-node03') { String t ->
            t.replace('@@fence_acks\n3|222', '@@fence_acks\n2|260').replace('Node ID:          2', 'Node ID:          3')
        }
        input([host('hvm-node02', two), host('hvm-node03', three)], LAYOUT_13)
    }

    static Set<String> failing(List<HealthChecker.Result> rs) {
        rs.findAll { it.status == HealthChecker.Status.FAIL }*.id as Set<String>
    }

    static Set<String> warning(List<HealthChecker.Result> rs) {
        rs.findAll { it.status == HealthChecker.Status.WARN }*.id as Set<String>
    }
}
