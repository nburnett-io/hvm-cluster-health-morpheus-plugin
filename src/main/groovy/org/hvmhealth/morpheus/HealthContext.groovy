package org.hvmhealth.morpheus

import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.data.DataQuery
import com.morpheusdata.model.Account
import com.morpheusdata.model.ComputeServer
import com.morpheusdata.model.ComputeServerGroup
import com.morpheusdata.model.TaskResult
import org.hvmhealth.checks.HealthChecker
import org.hvmhealth.model.HostFacts
import org.hvmhealth.standards.Layout
import org.hvmhealth.standards.Standards

import java.util.concurrent.Callable
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit

/** One HVM cluster: its hosts, running scripts on them through the Morpheus agent, and the view the checker needs. */
class HealthContext {
    static final long PROBE_TIMEOUT_SEC = 150
    static final long FIX_TIMEOUT_SEC = 180

    final MorpheusContext morpheus
    final ComputeServerGroup cluster

    HealthContext(MorpheusContext morpheus, Long clusterId) {
        this.morpheus = morpheus
        this.cluster = morpheus.services.cluster.get(clusterId)
        if (!cluster) {
            throw new IllegalArgumentException("No cluster ${clusterId}")
        }
    }

    /** Hypervisor nodes of the cluster (VMs have a parentServer; hosts do not). */
    List<ComputeServer> getHosts() {
        morpheus.services.computeServer.list(new DataQuery().withFilter('serverGroup.id', cluster.id))
                .findAll { it.parentServer == null }
                .sort { it.name }
    }

    Layout layout() {
        String code = cluster.layout?.code
        if (!code && cluster.layout?.id) {
            code = morpheus.services.computeTypeLayout.get(cluster.layout.id)?.code
        }
        Layout.of(code)
    }

    static String wrap(String script) {
        "echo '${script.getBytes('UTF-8').encodeBase64()}' | base64 -d | sudo bash -s"
    }

    /** [exit, output]; a host that does not answer in time is an answer in itself. */
    Map run(ComputeServer host, String script, long timeoutSec = FIX_TIMEOUT_SEC) {
        TaskResult tr = morpheus.executeCommandOnServer(host, wrap(script)).timeout(timeoutSec, TimeUnit.SECONDS).blockingGet()
        String out = tr.output ?: (tr.data instanceof String ? tr.data : '') ?: ''
        Integer code = tr.exitCode?.isInteger() ? tr.exitCode as Integer : (tr.success ? 0 : 1)
        [exit: code, output: out + (tr.error ? "\n${tr.error}" : '')]
    }

    static String probeScript(String mode) {
        // the mode is passed as $1; the script's own "full" default is what a missing argument means
        "set -- ${mode == 'quick' ? 'quick' : 'full'}\n" + HealthContext.getResourceAsStream('/scripts/health-probe.sh').getText('UTF-8')
    }

    /** Probes every host in parallel. A host that fails to answer gets incomplete facts rather than failing the check. */
    Map<String, HostFacts> probe(String mode) {
        List<ComputeServer> hs = hosts
        if (!hs) {
            return [:]
        }
        String script = probeScript(mode)
        ExecutorService pool = Executors.newFixedThreadPool(Math.min(16, hs.size()))
        try {
            Map<String, Future<HostFacts>> fut = hs.collectEntries { ComputeServer s ->
                [(s.name): pool.submit({
                    try {
                        HostFacts.parse(run(s, script, PROBE_TIMEOUT_SEC).output as String)
                    } catch (Throwable ignored) {
                        new HostFacts()
                    }
                } as Callable<HostFacts>)]
            }
            fut.collectEntries { String n, Future<HostFacts> f -> [(n): f.get()] } as Map<String, HostFacts>
        } finally {
            pool.shutdownNow()
        }
    }

    /** One host's raw probe output and what Morpheus has on record for it: for support, when a finding needs a closer look. */
    Map raw(String host) {
        ComputeServer listed = hosts.find { it.name == host }
        if (!listed) {
            throw new IllegalArgumentException("${host} is not in cluster ${cluster.name}")
        }
        ComputeServer full = morpheus.services.computeServer.get(listed.id)
        [host: host, output: run(listed, probeScript('full'), PROBE_TIMEOUT_SEC).output,
         morpheus: [status: full?.status, powerState: full?.powerState?.name(), statusMessage: full?.statusMessage, inMaintenance: inMaintenance(full)]]
    }

    /** VMs on the cluster's hosts. The libvirt domain name is the server's externalId. */
    List<Map> morpheusVms(List<ComputeServer> hs) {
        Set<Long> ids = hs*.id as Set<Long>
        Long cloudId = cluster.cloud?.id
        if (!cloudId) {
            return []
        }
        Map<Long, String> names = hs.collectEntries { [(it.id): it.name] }
        morpheus.services.computeServer.list(new DataQuery().withFilter('cloud.id', cloudId))
                .findAll { it.parentServer?.id && ids.contains(it.parentServer.id) }
                .collect { ComputeServer v -> [name: v.externalId ?: v.name, host: names[v.parentServer.id], power: v.powerState?.name(), memory: v.maxMemory, tenant: tenantName(v.account)] }
    }

    /** Records can come partial, so a name that is missing is looked up. */
    private String tenantName(Account a) {
        a?.name ?: (a?.id ? Tenancy.loader(morpheus).call(a.id)?.name : null)
    }

    /** The cluster's Morpheus networks that carry a VLAN. */
    List<Map> networks() {
        Long cloudId = cluster.cloud?.id
        if (!cloudId) {
            return []
        }
        try {
            morpheus.services.network.list(new DataQuery().withFilter('cloud.id', cloudId)).findAll { it.vlanId }
                    .collect { [name: it.displayName ?: it.name, vlan: it.vlanId, gateway: it.gateway] }.unique { it.vlan }
        } catch (Exception ignored) {
            []
        }
    }

    /**
     * Maintenance mode shows as status "maintenance" on the server record. The member status stays empty for plugins, and the
     * REST field maintenanceMode has no counterpart on the plugin model (seen on 9.1.0.1 with a host entering and leaving it).
     */
    static boolean inMaintenance(ComputeServer s) {
        s?.status == 'maintenance'
    }

    HealthChecker.Input input(Map<String, HostFacts> facts, String siteStandards) {
        List<ComputeServer> hs = hosts
        Layout l = layout()
        Standards std = Standards.forLayout(l, siteStandards)
        List<String> own = timeSources(cluster)
        List<String> fromLayout = ['ntp1', 'ntp2'].collect { cluster.getConfigProperty(it) as String }.findAll()
        if (own) {
            std.set('ntpServers', own, 'cluster', "Defined on this cluster's Cluster Health tab")
        } else if (!std.val('ntpServers') && fromLayout) {
            std.set('ntpServers', fromLayout, 'morpheus', "The NTP servers in this cluster's layout settings")
        }
        new HealthChecker.Input(cluster.name, l, hs.collect { ComputeServer s ->
            new HealthChecker.Host(s.name, inMaintenance(s), s.serverGroupMemberStatus?.name(), s.statusMessage, s.lastAgentUpdate?.time, facts[s.name], s.internalIp ?: s.externalIp, s.maxMemory)
        }, morpheusVms(hs), std, System.currentTimeMillis(),
                [cpuModel: cluster.getConfigProperty('cpuModel'), notHighAvailability: cluster.getConfigProperty('notHighAvailability')], networks())
    }

    static final String CFG_TIME_SOURCES = 'healthTimeSources'

    static List<String> timeSources(ComputeServerGroup c) {
        Object v = c?.getConfigProperty(CFG_TIME_SOURCES)
        v instanceof List ? (v as List<String>) : []
    }

    /** Sets or, with an empty list, clears the time sources this cluster is verified against. */
    void saveTimeSources(List<String> servers) {
        servers.each {
            if (!(it ==~ /[A-Za-z0-9][A-Za-z0-9.:-]*/)) {
                throw new IllegalArgumentException("Not a host name or address: ${it}")
            }
        }
        cluster.setConfigProperty(CFG_TIME_SOURCES, servers ?: null)
        morpheus.services.cluster.save(cluster)
    }


    /** One full evaluation for the tab: the headline, the results, the standards in force, and per-host maintenance. */
    static Map view(HealthChecker.Input in_, List<HealthChecker.Result> rs, String mode, long at = System.currentTimeMillis()) {
        [at: at, mode: mode, overall: HealthChecker.overall(rs), layout: in_.layout?.label, profile: in_.standards.profile, readiness: HealthChecker.readiness(rs), time: HealthChecker.timeView(in_),
         counts: [fail: rs.count { it.status == HealthChecker.Status.FAIL }, warn: rs.count { it.status == HealthChecker.Status.WARN },
                  pass: rs.count { it.status == HealthChecker.Status.PASS }],
         hosts: in_.hosts.collect { [name: it.name, maintenance: it.maintenance, member: it.memberStatus, answered: it.facts?.complete == true,
                                     running: it.facts?.vms?.count { v -> v.state == 'running' } ?: 0, frozen: it.facts?.frozen == true, kernel: it.facts?.kernel,
                                     os: it.facts?.osVersion, dStuck: it.facts?.dCount ?: 0] },
         results: rs.collect { [id: it.id, title: it.title, severity: it.severity.name(), status: it.status.name(), host: it.host, observed: it.observed,
                                expected: it.expected, source: it.source, fix: it.fix, tier: it.tier.name(), hint: it.hint, impact: it.impact] },
         standards: in_.standards.toList()]
    }
}
