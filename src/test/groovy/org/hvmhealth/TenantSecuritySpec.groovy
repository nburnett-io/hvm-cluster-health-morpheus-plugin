package org.hvmhealth

import org.hvmhealth.checks.CheckCatalog
import org.hvmhealth.checks.HealthChecker
import org.hvmhealth.checks.HealthChecker.Status
import org.hvmhealth.model.HostFacts
import org.hvmhealth.morpheus.HealthMonitor
import org.hvmhealth.morpheus.HealthReport
import spock.lang.Specification
import spock.lang.Unroll

/**
 * W-TENANT: nested-virtualization exposure (SEC-01), kernel escape-fix level (SEC-02), side-channel and SMT note (SEC-03), the 2.0 bridge STP
 * note (SEC-04), a customer port on an infrastructure VLAN (SEC-05), the per-VM-limits fleet note (SEC-06), cache=unsafe (VM-07), the probe
 * parser hardening against tenant-chosen names, and the report's allow-list. Probe lines follow what the probe prints, with names made up.
 */
class TenantSecuritySpec extends Specification {

    private static List<HealthChecker.Result> check(HealthChecker.Input i) { new HealthChecker().check(i) }

    private static HealthChecker.Result one(HealthChecker.Input i, String id) { check(i).find { it.id == id } }

    /** A full-mode host built from the healthy 2.0 sample with the given extra probe sections spliced in before @@end. */
    private static HostFacts facts(String extra, String host = 'hvm-node01', String sample = 'probe-healthy') {
        Fixture.facts(sample, host) { String t -> t.replace('@@end', extra.stripIndent().trim() + '\n@@end') }
    }

    private static HealthChecker.Input cluster(String extra, List<Map> vms, String layout = Fixture.LAYOUT_20, String host = 'hvm-node01') {
        Fixture.input([Fixture.host(host, facts(extra, host, layout == Fixture.LAYOUT_13 ? 'probe-timesyncd' : 'probe-healthy'))], layout, null, vms)
    }

    private static Map customerVm(Map over = [:]) {
        [name: 'vm-one', label: 'VM One', host: 'hvm-node01', power: 'on', status: 'provisioned', operator: false, tenant: 'Acme', tenantId: 9L, nics: [], disks: []] + over
    }

    private static String sec(Map m) {
        StringBuilder b = new StringBuilder('@@sec\n')
        b << "KVM_NESTED_INTEL=${m.get('intel', '')}\n"
        b << "KVM_NESTED_AMD=${m.get('amd', '')}\n"
        if (m.vuln) { b << "CPU_VULN=${m.vuln}\n" }
        b << "SMT_ACTIVE=${m.get('smt', '1')}\n"
        (m.infra ?: []).each { b << "INFRA_VLAN=${it}\n" }
        (m.bridges ?: []).each { b << "BRIDGE_STP=${it}\n" }
        (m.vmsec ?: []).each { b << "VM_SEC=${it}\n" }
        if (m.cut) { b << 'SEC_CUT=1\n' }
        b.toString()
    }

    // ------------------------------------------------------------------ SEC-01 nested-virtualization exposure

    @Unroll
    def 'SEC-01 #desc'() {
        when:
        HealthChecker.Result r = one(cluster(sec(intel: nested, vmsec: ["vm-one|${cpu}|${feats}|cache='none'|0"]), [customerVm()]), 'SEC-01')

        then:
        r.status == status

        where:
        desc                                                     | nested | cpu                                  | feats                          || status
        'host-passthrough with nested on and no disable warns'   | 'Y'    | "<cpu mode='host-passthrough'>"      | ''                             || Status.WARN
        'host-passthrough with vmx disabled is not a finding'    | 'Y'    | "<cpu mode='host-passthrough'>"      | "policy='disable' name='vmx'"  || Status.PASS
        'named model that requires vmx warns'                    | 'Y'    | "<cpu mode='custom'>"                | "policy='require' name='vmx'"  || Status.WARN
        'named model with vmx disabled is not a finding'         | 'Y'    | "<cpu mode='custom'>"                | "policy='disable' name='vmx'"  || Status.PASS
        'nested off hides it, even host-passthrough'             | 'N'    | "<cpu mode='host-passthrough'>"      | ''                             || Status.PASS
        'host-passthrough with nested unreadable is not checked' | ''     | "<cpu mode='host-passthrough'>"      | ''                             || Status.NOT_CHECKED
    }

    def 'SEC-01 warns on an AMD host exposing SVM, and names VMX for an Intel host'() {
        given:
        HealthChecker.Result amd = one(cluster(sec(amd: 'Y', intel: '', vmsec: ["vm-one|<cpu mode='host-passthrough'>||cache='none'|0"]), [customerVm()]), 'SEC-01')
        HealthChecker.Result intel = one(cluster(sec(intel: 'Y', vmsec: ["vm-one|<cpu mode='host-passthrough'>||cache='none'|0"]), [customerVm()]), 'SEC-01')

        expect:
        amd.status == Status.WARN
        amd.observed.contains('VM One')
        amd.expected.contains('SVM')
        intel.expected.contains('VMX')
    }

    def 'SEC-01 is not raised for an operator VM, only customers'() {
        expect:
        one(cluster(sec(intel: 'Y', vmsec: ["vm-one|<cpu mode='host-passthrough'>||cache='none'|0"]), [customerVm(operator: true)]), 'SEC-01') == null
    }

    def 'SEC-01 is a SECURITY alarm and its observed is withheld from a report'() {
        given:
        HealthChecker.Result r = one(cluster(sec(intel: 'Y', vmsec: ["vm-one|<cpu mode='host-passthrough'>||cache='none'|0"]), [customerVm()]), 'SEC-01')

        expect:
        org.hvmhealth.morpheus.Isolation.alarmName(r).startsWith('SECURITY: ')
        !HealthReport.observedSafe('SEC-01')
    }

    // ------------------------------------------------------------------ SEC-02 kernel escape-fix level

    def 'SEC-02 fails below the fix with nesting exposed, warns with it off, passes at or above'() {
        expect:
        secWithKernel('7.0.0-15-generic', 'Y').status == Status.FAIL
        secWithKernel('7.0.0-15-generic', 'N').status == Status.WARN
        secWithKernel('7.0.0-38-generic', 'Y').status == Status.PASS
        secWithKernel('7.0.0-99-generic', 'N').status == Status.PASS
    }

    def 'SEC-02 cannot be judged when the kernel is unreadable'() {
        expect:
        secWithKernel('(none)', 'Y').status == Status.NOT_CHECKED
    }

    private static HealthChecker.Result secWithKernel(String kernel, String nested) {
        HostFacts f = Fixture.facts('probe-healthy', 'hvm-node01') { String t ->
            t.replace('KERNEL=7.0.0-15-generic', "KERNEL=${kernel}").replace('@@end', sec(intel: nested, vmsec: []).stripIndent().trim() + '\n@@end')
        }
        new HealthChecker().check(Fixture.input([Fixture.host('hvm-node01', f)], Fixture.LAYOUT_20, null, [customerVm()])).find { it.id == 'SEC-02' }
    }

    def 'kernelCompare reads the Ubuntu ABI, which plain version compare drops'() {
        expect:
        HealthChecker.kernelCompare('7.0.0-15-generic', '7.0.0-38') < 0
        HealthChecker.kernelCompare('7.0.0-38-generic', '7.0.0-38') == 0
        HealthChecker.kernelCompare('7.0.0-40-generic', '7.0.0-38') > 0
        HealthChecker.kernelCompare('6.8.0-120', '7.0.0-1') < 0
        HealthChecker.kernelCompare('nonsense', '7.0.0-38') == null
    }

    // ------------------------------------------------------------------ SEC-03 / SEC-04 notes

    def 'SEC-03 lists what the kernel reports Vulnerable and the SMT state, and raises nothing'() {
        given:
        HealthChecker.Result r = one(cluster(sec(intel: 'Y', vuln: 'l1tf|Mitigation: PTE Inversion; VMX vulnerable', smt: '1', vmsec: []), [customerVm()]), 'SEC-03')

        expect:
        r.status == Status.NA
        r.observed.contains('l1tf')
        r.observed.contains('SMT on')
    }

    def 'SEC-04 notes the 2.0 bridge STP state but does not run on 1.3'() {
        given:
        HealthChecker.Result r = one(cluster(sec(intel: 'Y', bridges: ['vs0|1|1', 'docker0|0|0'], vmsec: []), [customerVm()]), 'SEC-04')

        expect:
        r.status == Status.NA
        r.observed.contains('vs0')
        r.observed.contains('STP on')
        one(cluster(sec(intel: 'Y', bridges: ['br0|1|1'], vmsec: []), [customerVm()], Fixture.LAYOUT_13), 'SEC-04') == null
    }

    // ------------------------------------------------------------------ SEC-05 customer port on an infrastructure VLAN

    def 'SEC-05 warns when a customer port shares the host storage VLAN, and passes when it does not'() {
        given:
        HealthChecker.Result r = one(cluster(sec(intel: 'Y', infra: ['storage|778'], vmsec: []) + brPort('vm-one', 778), [customerVm()]), 'SEC-05')

        expect:
        r.status == Status.WARN
        r.observed.contains('778')
        r.observed.contains('storage')
        one(cluster(sec(intel: 'Y', infra: ['storage|778'], vmsec: []) + brPort('vm-one', 120), [customerVm()]), 'SEC-05').status == Status.PASS
    }

    def 'SEC-05 does not run when no infrastructure interface is tagged'() {
        expect:
        one(cluster(sec(intel: 'Y', vmsec: []) + brPort('vm-one', 778), [customerVm()]), 'SEC-05') == null
    }

    def 'SEC-05 ignores an operator VM on the storage VLAN'() {
        expect:
        one(cluster(sec(intel: 'Y', infra: ['storage|778'], vmsec: []) + brPort('vm-one', 778), [customerVm(operator: true)]), 'SEC-05') == null
    }

    private static String brPort(String vm, int vlan) {
        String json = "[{\"ifname\":\"vnet0\",\"vlans\":[{\"vlan\":${vlan},\"flags\":[\"PVID\",\"Egress Untagged\"]}]}]"
        "\n@@vm_ports\nBR_PORT=${vm}|vnet0|02:00:00:aa:bb:cc|vs0|1|${json}"
    }

    // ------------------------------------------------------------------ SEC-06 per-VM limits fleet note

    def 'SEC-06 is one cluster-level note counting customer VMs and how many are limited'() {
        given:
        HealthChecker.Result r = one(cluster(sec(intel: 'Y', vmsec: ["vm-one|<cpu mode='custom'>||cache='none'|1"]), [customerVm()]), 'SEC-06')

        expect:
        r.status == Status.NA
        r.host == null
        r.observed == '1 running customer VM(s), 1 with a per-VM I/O, CPU or network limit set'
    }

    // ------------------------------------------------------------------ VM-07 cache=unsafe

    def 'VM-07 fails on cache=unsafe and passes otherwise'() {
        given:
        HealthChecker.Result r = one(cluster(sec(intel: 'Y', vmsec: ["vm-one|<cpu mode='custom'>||cache='none',cache='unsafe'|0"]), [customerVm()]), 'VM-07')

        expect:
        r.status == Status.FAIL
        r.observed.contains('VM One')
        one(cluster(sec(intel: 'Y', vmsec: ["vm-one|<cpu mode='custom'>||cache='none'|0"]), [customerVm()]), 'VM-07').status == Status.PASS
    }

    def 'VM-07 applies to any VM, not only customers, and its observed is withheld from a report'() {
        expect:
        one(cluster(sec(intel: 'Y', vmsec: ["vm-one|<cpu mode='custom'>||cache='unsafe'|0"]), [customerVm(operator: true)]), 'VM-07').status == Status.FAIL
        !HealthReport.observedSafe('VM-07')
    }

    def 'all the new checks are full-check-only'() {
        expect:
        ['SEC-01', 'SEC-02', 'SEC-03', 'SEC-04', 'SEC-05', 'SEC-06', 'VM-07'].every { it in HealthMonitor.FULL_ONLY }
    }

    // ------------------------------------------------------------------ NET-12 / NET-13 macvtap and SR-IOV

    @Unroll
    def 'a #type port is reported NOT CHECKED by NET-12 and NET-13, not passed'() {
        given:
        HealthChecker.Input i = cluster("\n@@vm_ports\nPORT_SKIP=vm-one|${dev}|02:00:00:aa:bb:cc|${type}", [customerVm()])
        List<HealthChecker.Result> rs = check(i)

        expect:
        rs.find { it.id == 'NET-12' }.status == Status.NOT_CHECKED
        rs.find { it.id == 'NET-13' }.status == Status.NOT_CHECKED
        rs.find { it.id == 'NET-12' }.observed.contains(word)

        where:
        type      | dev       || word
        'direct'  | 'macvtap0' || 'macvtap'
        'hostdev' | '-'        || 'SR-IOV'
    }

    // ------------------------------------------------------------------ probe parser hardening

    def 'the probe-sanitised form of a name (its pipe turned to _) parses as a single field'() {
        given: 'what the probe emits for a VM a tenant named "ev|il": san() has already turned the pipe into _'
        HostFacts f = HostFacts.parse('@@meta\nHOSTNAME=h1\n@@vm_ports\nOVS_PORT=ev_il|vnet0|02:00:00:00:00:02|vs0|779|[]|access\n@@end\nCOMPLETE=1')

        expect:
        f.vmPorts.size() == 1
        f.vmPorts[0].vm == 'ev_il' && f.vmPorts[0].access == 779
    }

    def 'a raw pipe in a name (if the probe ever failed to sanitise) corrupts only its own row, never another and never the parse'() {
        given:
        String t = '''@@meta
HOSTNAME=h1
@@vm_ports
OVS_PORT=good|vnet0|02:00:00:00:00:01|vs0|778|[]|access
OVS_PORT=ev|il|vnet1|02:00:00:00:00:02|vs0|779|[]|access
@@end
COMPLETE=1'''
        HostFacts f = HostFacts.parse(t)

        expect: 'the good port is intact, the read did not throw, and completion still reads from the real @@end'
        f.complete
        f.vmPorts.find { it.vm == 'good' }?.access == 778
        f.vmPorts.size() == 2
    }

    def 'the probe sanitises VM names and carries the @@sec reads'() {
        given:
        String p = HostFacts.getResourceAsStream('/scripts/health-probe.sh').getText('UTF-8').replace('\r\n', '\n')

        expect: 'san() is defined, applied to the port name, and @@sec is a full-only read'
        p.contains("san() { printf '%s' \"\$1\" | tr -d")
        p.contains('sd="$(san "$d")"')
        p.contains('OVS_PORT=$sd')
        p.contains('PORT_SKIP=')
        p.contains('sec sec')
        p.indexOf('sec sec') > p.indexOf('if [ "$MODE" = full ]; then')
    }

    def 'a later section marker resets its list, so an injected earlier @@end cannot fake completion state'() {
        given: 'an earlier @@end claiming CUT=1, then the genuine trailing one with only COMPLETE=1'
        HostFacts f = HostFacts.parse('@@meta\nHOSTNAME=h1\n@@end\nCOMPLETE=1\nCUT=1\n@@vms\n 5    realvm   running\n@@end\nCOMPLETE=1')

        expect: 'the real trailing @@end decides, not the earlier CUT=1 one'
        f.complete
        !f.partial
        f.vms*.name == ['realvm']
    }

    def 'a section line with trailing text is content, not a section'() {
        given:
        HostFacts f = HostFacts.parse('@@meta\nHOSTNAME=h1\n@@end COMPLETE=1\nKEY=value\n@@end\nCOMPLETE=1')

        expect: 'the "@@end COMPLETE=1" line did not start a section; the real one did'
        f.complete
        f.sections.containsKey('meta')
        !f.sections.containsKey('end COMPLETE=1')
    }

    def 'control characters in a surfaced name are scrubbed and length is capped'() {
        expect:
        HostFacts.safeName("a\u0000b\tc\nd") == 'a b c d'
        HostFacts.safeName('x|y') == 'x y'
        HostFacts.safeName('z' * 200).length() == 120
        HostFacts.safeName(null) == null
    }

    def 'unreadable @@sec input is NOT CHECKED or absent, never a pass'() {
        expect: 'a host with no @@sec section emits none of the new checks'
        check(Fixture.healthy()).findAll { it.id.startsWith('SEC-') || it.id == 'VM-07' }.isEmpty()
    }

    // ------------------------------------------------------------------ report allow-list, both directions

    def 'the report allow-list carries a safe check and withholds a name-bearing one, and defaults unknown checks to withheld'() {
        expect: 'safe, host/storage/number observations'
        HealthReport.observedSafe('CLU-05')
        HealthReport.observedSafe('STO-06')
        HealthReport.observedSafe('SEC-02')
        HealthReport.observedSafe('NET-05')

        and: 'anything that can name a VM, tenant, network or other cluster'
        !HealthReport.observedSafe('NET-12')
        !HealthReport.observedSafe('VM-06')
        !HealthReport.observedSafe('HA-06')
        !HealthReport.observedSafe('FLT-01')
        !HealthReport.observedSafe('SEC-01')

        and: 'a check in no safe prefix and no safe id, including a hypothetical new one, is withheld'
        !HealthReport.observedSafe('XYZ-99')
        !HealthReport.observedSafe(null)
    }

    def 'every mixed-prefix check in the catalog is classified, so none can leak by omission'() {
        given: 'the prefixes that elsewhere name a VM, tenant or network'
        Set<String> mixed = CheckCatalog.ENTRIES.keySet().findAll { !(it.replaceFirst(/-.*/, '') in HealthReport.SAFE_PREFIXES) }

        expect: 'each is explicitly safe or explicitly withheld, never unclassified'
        mixed.every { it in HealthReport.SAFE_IDS || it in HealthReport.WITHHELD_IDS }
        (HealthReport.SAFE_IDS + HealthReport.WITHHELD_IDS).every { CheckCatalog.ENTRIES.containsKey(it) }
        HealthReport.SAFE_IDS.disjoint(HealthReport.WITHHELD_IDS)
    }
}
