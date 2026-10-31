package org.hvmhealth.morpheus

import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.data.DataQuery
import com.morpheusdata.model.Account
import com.morpheusdata.model.ComputeServer
import com.morpheusdata.model.Cloud
import com.morpheusdata.model.ComputeServerGroup
import com.morpheusdata.model.Network
import com.morpheusdata.model.TaskResult
import org.hvmhealth.checks.HealthChecker
import org.hvmhealth.model.HostFacts
import org.hvmhealth.standards.Layout
import org.hvmhealth.standards.Standards

import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import java.security.cert.X509Certificate
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/** One HVM cluster: its hosts, running scripts on them through the Morpheus agent, and the view the checker needs. */
class HealthContext {
    static final long PROBE_TIMEOUT_SEC = 150
    /** A route that answers the browser directly must finish first, or a silent host gets the user a 504 from the proxy at 120 s. */
    static final long INTERACTIVE_PROBE_TIMEOUT_SEC = 90
    /** The script stops reading this long before its limit, so that what it has read gets back here rather than being lost with it. */
    private static final long PROBE_MARGIN_SEC = 30
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
        allHostRecords().findAll { it.status != REMOVING }
    }

    private List<ComputeServer> allHostRecords() {
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

    static String probeScript(String mode, long timeoutSec = PROBE_TIMEOUT_SEC) {
        // the mode is passed as $1, and the seconds it may read for as $2
        "set -- ${mode == 'quick' ? 'quick' : 'full'} ${timeoutSec - PROBE_MARGIN_SEC}\n" + HealthContext.getResourceAsStream('/scripts/health-probe.sh').getText('UTF-8')
    }

    /** Probes every host in parallel. A host that fails to answer gets incomplete facts rather than failing the check. */
    Map<String, HostFacts> probe(String mode, long timeoutSec = PROBE_TIMEOUT_SEC) {
        List<ComputeServer> hs = hosts
        if (!hs) {
            return [:]
        }
        String script = probeScript(mode, timeoutSec)
        // one thread per host, so that a limit on the whole is a limit on each. GFS2 and corosync clusters stop at about 16 hosts anyway
        ExecutorService pool = Executors.newFixedThreadPool(hs.size())
        try {
            Map<String, Future<HostFacts>> fut = hs.collectEntries { ComputeServer s ->
                [(s.name): pool.submit({
                    try {
                        HostFacts.parse(run(s, script, timeoutSec).output as String)
                    } catch (Throwable ignored) {
                        new HostFacts()
                    }
                } as Callable<HostFacts>)]
            }
            // run() has its own limit, but only once the agent call has returned: this one holds whatever the call does
            long deadline = System.currentTimeMillis() + (timeoutSec + 10L) * 1000L
            fut.collectEntries { String n, Future<HostFacts> f -> [(n): answer(f, deadline)] } as Map<String, HostFacts>
        } finally {
            pool.shutdownNow()
        }
    }

    static HostFacts answer(Future<HostFacts> f, long deadline) {
        try {
            f.get(Math.max(0L, deadline - System.currentTimeMillis()), TimeUnit.MILLISECONDS)
        } catch (TimeoutException | ExecutionException ignored) {
            new HostFacts()
        }
    }

    /** One host's raw probe output and what Morpheus has on record for it: for support, when a finding needs a closer look. */
    Map raw(String host) {
        ComputeServer listed = hosts.find { it.name == host }
        if (!listed) {
            throw new IllegalArgumentException("${host} is not in cluster ${cluster.name}")
        }
        ComputeServer full = morpheus.services.computeServer.get(listed.id)
        String output
        try {
            output = run(listed, probeScript('full', INTERACTIVE_PROBE_TIMEOUT_SEC), INTERACTIVE_PROBE_TIMEOUT_SEC).output
        } catch (Exception ignored) {
            output = "${host} did not answer within ${INTERACTIVE_PROBE_TIMEOUT_SEC} s. It may be down, or its agent stopped.".toString()
        }
        [host: host, output: output,
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
        Map<Long, Network> nets = cloudNetworks().collectEntries { [(it.id): it] }
        Map<Long, Boolean> operators = [:]
        morpheus.services.computeServer.list(new DataQuery().withFilter('cloud.id', cloudId))
                .findAll { it.parentServer?.id && ids.contains(it.parentServer.id) }
                .collect { ComputeServer v -> [name: v.externalId ?: v.name, label: v.name, ips: [v.internalIp, v.externalIp].findAll() as List<String>, host: names[v.parentServer.id], power: v.powerState?.name(), memory: v.maxMemory, tenant: tenantName(v.account),
                                         status: v.status, tenantId: v.account?.id, operator: operatorTenant(v.account, operators), nics: nicsOf(v, nets), disks: disksOf(v)] }
    }

    /** Records can come partial, so a name that is missing is looked up. */
    private String tenantName(Account a) {
        a?.name ?: (a?.id ? Tenancy.loader(morpheus).call(a.id)?.name : null)
    }

    /** The record's own network can be partial, so the cloud's list is asked first for its VLAN. */
    private static List<Map> nicsOf(ComputeServer v, Map<Long, Network> nets) {
        (v.interfaces ?: []).findAll { it.macAddress }.collect {
            Network n = nets[it.network?.id] ?: it.network
            [mac: it.macAddress, vlan: n?.vlanId, network: n?.displayName ?: n?.name]
        }
    }

    /** Per volume with the datastore it is on, or the server's total where Morpheus keeps no volumes for it. */
    private static List<Map> disksOf(ComputeServer v) {
        (v.volumes ?: []).findAll { it.maxStorage }.collect { [ds: it.datastore?.name, size: it.maxStorage] } ?: (v.maxStorage ? [[ds: null, size: v.maxStorage]] : [])
    }

    /** True for the master tenant and the cluster's owner, whose networks may be shared with others; null when the account cannot be loaded. */
    private Boolean operatorTenant(Account a, Map<Long, Boolean> seen) {
        if (a?.id == null) {
            return null
        }
        if (!seen.containsKey(a.id)) {
            Account full = Tenancy.loader(morpheus).call(a.id)
            seen[a.id] = full == null ? null : (full.masterAccount == true || a.id == cluster.owner?.id)
        }
        seen[a.id]
    }

    private List<Network> listNetworks() {
        Long cloudId = cluster.cloud?.id
        cloudId ? morpheus.services.network.list(new DataQuery().withFilter('cloud.id', cloudId)) : []
    }

    private List<Network> cloudNetworks() {
        try {
            listNetworks()
        } catch (Exception ignored) {
            []
        }
    }

    /** Every network with a VLAN and who owns it. networks() keeps one per VLAN; here several tenants on one VLAN is the point. */
    List<Map> tenantNetworks() {
        Map<Long, Boolean> operators = [:]
        cloudNetworks().findAll { it.vlanId }.collect { [name: it.displayName ?: it.name, vlan: it.vlanId, ownerId: it.owner?.id, owner: tenantName(it.owner), shared: operatorTenant(it.owner, operators)] }
    }

    /** This appliance's own addresses, and what its URL resolves to, which differs behind a load balancer. Empty when they cannot be read. */
    private List<String> managerAddrs() {
        List<String> found = []
        try {
            NetworkInterface.getNetworkInterfaces().toList().findAll { !it.loopback && it.up }.each { nic ->
                found.addAll(nic.inetAddresses.toList().findAll { it instanceof Inet4Address }*.hostAddress)
            }
            String url = morpheus.services.admin.appliance.find(new DataQuery())?.applianceUrl ?: morpheus.async.loadBalancer.applianceUrl
            if (url) {
                found.addAll(InetAddress.getAllByName(new URI(url).host).findAll { it instanceof Inet4Address && !it.loopbackAddress }*.hostAddress)
            }
        } catch (Exception ignored) {
            // what was read so far still counts; with nothing, the check says it could not tell
        }
        found.unique()
    }

    /** Every network name on the cloud, for withholding, for withholding from reports, including those with no VLAN and tenants with no VM here.
     * Throws if they cannot be read, so the report fails rather than print one.
     */
    List<String> networkNames() {
        listNetworks().collectMany { [it.displayName, it.name, tenantName(it.owner)] }.findAll().unique() as List<String>
    }

    /** A setting stored in a cluster's config: true, false, or null when it is unset or unreadable, which is not the same as off. */
    static Boolean configFlag(Object v) {
        String s = v?.toString()?.toLowerCase()
        s in ['true', 'on', '1'] ? Boolean.TRUE : (s in ['false', 'off', '0'] ? Boolean.FALSE : null)
    }

    /**
     * "Automatically Power On VMs" and the heartbeat target. The cluster's setting has no member on ComputeServerGroup in plugin API 1.5.0, so it is read
     * from the cluster's config. The cloud is loaded in full: its field defaults to true, so a partial record would always read as on.
     */
    private Map failoverConfig() {
        Cloud cloud = null
        try {
            cloud = cluster.cloud?.id ? morpheus.services.cloud.get(cluster.cloud.id) : null
        } catch (Exception ignored) {
            cloud = null
        }
        [autoRecoverPowerState: configFlag(cluster.getConfigProperty('autoRecoverPowerState')), cloudAutoRecoverPowerState: cloud?.autoRecoverPowerState, datastores: datastores()]
    }

    /** Null when nothing answered. Filters use the appliance's own property names, which the API does not document for datastores, so each is tried. */
    private List<Map> datastores() {
        Long cloudId = cluster.cloud?.id
        Map<Long, Map> found = [:]
        boolean answered = false
        [[refType: 'ComputeServerGroup', refId: cluster.id], cloudId ? [refType: 'ComputeZone', refId: cloudId] : null, cloudId ? ['cloud.id': cloudId] : null].findAll().each { Map filter ->
            try {
                morpheus.services.cloud.datastore.list(new DataQuery().withFilters(filter)).each {
                    found[it.id] = [name: it.name, heartbeatTarget: it.heartbeatTarget, online: it.online, active: it.active, cluster: it.refType == 'ComputeServerGroup' && it.refId == cluster.id]
                }
                answered = true
            } catch (Exception ignored) {
                // another filter may be the one this appliance knows
            }
        }
        answered ? found.values() as List<Map> : null
    }

    /** When the appliance's certificate ends, read from the TLS handshake on its own URL, which takes no credential. Null when it cannot be read. */
    private Long applianceCertNotAfterMs() {
        try {
            peerCertNotAfterMs(morpheus.services.admin.appliance.find(new DataQuery())?.applianceUrl ?: morpheus.async.loadBalancer.applianceUrl)
        } catch (Throwable ignored) {
            null
        }
    }

    /** Accepts any certificate because its date is all that is wanted: nothing is sent over this connection and nothing is trusted from it but the date. */
    static Long peerCertNotAfterMs(String url, int timeoutMs = 5000) {
        URI u = url ? new URI(url) : null
        if (u?.scheme != 'https' || !u.host) {
            return null
        }
        TrustManager anyCert = [checkClientTrusted: { a, t -> }, checkServerTrusted: { a, t -> }, getAcceptedIssuers: { new X509Certificate[0] }] as X509TrustManager
        SSLContext tls = SSLContext.getInstance('TLS')
        tls.init(null, [anyCert] as TrustManager[], null)
        Socket raw = new Socket()
        try {
            raw.connect(new InetSocketAddress(u.host, u.port > 0 ? u.port : 443), timeoutMs)
            raw.soTimeout = timeoutMs
            SSLSocket s = tls.socketFactory.createSocket(raw, u.host, u.port > 0 ? u.port : 443, false) as SSLSocket
            s.startHandshake()
            (s.session.peerCertificates[0] as X509Certificate).notAfter.time
        } catch (IOException ignored) {
            null
        } finally {
            raw.close()
        }
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
        // A typo in the settings must not stop freeze detection, so checks carry on with the built-in standards and PRB-03 says so.
        String siteError = null
        Standards std
        try {
            std = Standards.forLayout(l, siteStandards)
        } catch (IllegalArgumentException e) {
            std = Standards.forLayout(l)
            siteError = e.message
        }
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
                [cpuModel: cluster.getConfigProperty('cpuModel'), notHighAvailability: cluster.getConfigProperty('notHighAvailability'), siteStandardsError: siteError, managerAddrs: managerAddrs(), recordStatus: cluster.status] + failoverConfig() +
                        (facts.values().any { it?.mode == 'full' } ? [applianceCertNotAfterMs: applianceCertNotAfterMs()] : [:]), networks(), cluster.id,
                allHostRecords().findAll { it.status == REMOVING }*.name, tenantNetworks())
    }

    static final String CFG_TIME_SOURCES = 'healthTimeSources'
    /** A record stuck in this state was seen to outlive its host: it is not a member, and it must not count as one. */
    private static final String REMOVING = 'deprovisioning'

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
