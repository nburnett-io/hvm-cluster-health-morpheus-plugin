package org.hvmhealth

import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.MorpheusServices
import com.morpheusdata.core.Plugin
import com.morpheusdata.model.ComputeServer
import com.morpheusdata.model.ComputeServerGroup
import com.morpheusdata.model.OptionType
import com.morpheusdata.model.TaskResult
import io.reactivex.rxjava3.core.Single
import org.hvmhealth.morpheus.HealthAnalyticsProvider
import org.hvmhealth.morpheus.HealthContext
import org.hvmhealth.morpheus.HealthController
import org.hvmhealth.morpheus.HealthMonitor
import org.hvmhealth.morpheus.HealthTabProvider
import spock.lang.Specification

import java.util.jar.JarInputStream

/** Loads the plugin the way the appliance does, and pins every Morpheus member it touches (Groovy is dynamic). */
class PluginSpec extends Specification {
    static boolean hasMethod(Class c, String name, int args = -1) {
        c.methods.any { it.name == name && (args < 0 || it.parameterCount == args) }
    }

    static boolean hasProperty(Class c, String prop) {
        String cap = prop.capitalize()
        hasMethod(c, "get${cap}") || hasMethod(c, "is${cap}") || c.fields.any { it.name == prop }
    }

    def cleanup() {
        HealthMonitor.stop()
    }

    def 'plugin initializes the way PluginManager loads it'() {
        given:
        Plugin plugin = new ClusterHealthPlugin()
        Plugin.getDeclaredMethod('setMorpheus', MorpheusContext).with { accessible = true; invoke(plugin, Mock(MorpheusContext)) }

        when:
        plugin.initialize()

        then:
        noExceptionThrown()
        plugin.code == 'hvm-cluster-health'
        plugin.providers*.code.containsAll(['hvm-cluster-health-clusterTab', 'hvm-cluster-health.controller'])
        // tab codes become href="#<code>" + a Bootstrap selector on the cluster page: no '.', ':' or spaces
        plugin.providers.findAll { it instanceof com.morpheusdata.core.providers.ClusterTabProvider }*.code.every { it ==~ /[A-Za-z][A-Za-z0-9_-]*/ }
        plugin.controllers.size() == 1
        plugin.hasCustomRender()
        plugin.permissions*.code as Set == ['hvm-cluster-health-ops', 'hvm-cluster-health-emergency'] as Set
    }

    def 'automatic fixes are off on a new install'() {
        given:
        Plugin plugin = new ClusterHealthPlugin()
        Plugin.getDeclaredMethod('setMorpheus', MorpheusContext).with { accessible = true; invoke(plugin, Mock(MorpheusContext)) }

        when:
        plugin.initialize()

        then:
        plugin.settings.find { it.fieldName == 'autoFixSafe' }.defaultValue == 'off'
        plugin.settings.find { it.fieldName == 'autoFixMaintenance' }.defaultValue == 'off'

        and: 'and no cluster is named for them, so turning a switch on does nothing yet'
        !plugin.settings.find { it.fieldName == 'autoFixClusters' }.defaultValue
        plugin.settings.find { it.fieldName == 'autoFixClusters' }.inputType == OptionType.InputType.TEXT
    }

    def 'the probe reads what the checks need from a 1.3 host, without touching anything'() {
        given:
        String probe = HealthContext.getResourceAsStream('/scripts/health-probe.sh').getText('UTF-8')

        expect:
        probe.contains("multipathd show maps format '%n %Q'")
        probe.contains('IPMITOOL=$(command -v ipmitool)')
        probe.contains('/opt/morpheus-node/lib/morpheus/morpheus-agent.jar')
        // asking timesyncd over D-Bus could start it on a host that keeps time with chrony
        probe.indexOf('if t 5 systemctl is-active --quiet systemd-timesyncd') > 0
        probe.indexOf('if t 5 systemctl is-active --quiet systemd-timesyncd') < probe.indexOf('timedatectl timesync-status')
    }

    def 'every change needs more than read, and the emergency action its own permission'() {
        when:
        Map routes = new HealthController(null, null).routes.collectEntries { r -> [(r.method): r.permissions*.code + r.permissions*.availableAccessTypes.flatten()*.toString()] }

        then:
        routes.status.contains('read')
        routes.catalog.contains(HealthController.PERM)
        routes.catalog.contains('read')
        routes.check.contains('read')
        routes.fix.contains('full')
        !routes.fix.contains(HealthController.PERM_EMERGENCY)
        routes.fenceAck.contains(HealthController.PERM_EMERGENCY)
        routes.fencePlan.contains(HealthController.PERM_EMERGENCY)
    }

    def 'the probe touches GFS2 only when DLM says it is safe, and only from a job it does not wait on'() {
        given:
        String probe = HealthContext.getResourceAsStream('/scripts/health-probe.sh').getText('UTF-8')
        int gate = probe.indexOf('if [ "$FS_SAFE" = 1 ]')
        int background = probe.indexOf('bounded 15 gfs_read')

        expect:
        gate > 0
        background > gate
        probe.indexOf('df -B1') > gate && probe.indexOf('df -B1') < background
        probe.indexOf('mvm-hb') > gate && probe.indexOf('mvm-hb') < background
        probe.contains("grep -qE 'kern_stop|new change|new status|wait fenc'")
    }

    def 'the probe changes nothing'() {
        given:
        // comments aside, and the journal searches: those lines look FOR these commands having been run by the agent
        String probe = HealthContext.getResourceAsStream('/scripts/health-probe.sh').getText('UTF-8').readLines()
                .findAll { !it.startsWith('#') && !it.contains('journalctl') }.join('\n')

        expect:
        !(probe =~ /systemctl\s+(start|stop|restart|enable|disable|reload)|dlm_tool fence_ack|iscsiadm [^|\n]*(--login|--logout|-o )|multipath -[Fr]|virsh (start|destroy|shutdown|undefine)|\bmount\b |umount|\breboot\b(?!-required)/)
        // the temp file for the time-boxed job is the one thing it writes; /dev/tcp is bash opening a socket, not a file
        probe.findAll(/(?:^|\s)\d?>\s*"?\/(?!dev\/(null|tcp))[^ ]*/).isEmpty()
    }

    def 'probe modes reach the script'() {
        expect:
        HealthContext.probeScript('quick').startsWith('set -- quick 120\n')
        HealthContext.probeScript('full').startsWith('set -- full 120\n')
        HealthContext.probeScript('anything').startsWith('set -- full 120\n')
        HealthContext.probeScript('quick', HealthContext.INTERACTIVE_PROBE_TIMEOUT_SEC).startsWith('set -- quick 60\n')
    }

    def 'maintenance mode is read from the server status'() {
        expect:
        HealthContext.inMaintenance(new ComputeServer(status: 'maintenance'))
        !HealthContext.inMaintenance(new ComputeServer(status: 'provisioned'))
        !HealthContext.inMaintenance(new ComputeServer())
        !HealthContext.inMaintenance(null)
    }

    def 'Morpheus members the plugin uses exist'() {
        expect:
        hasMethod(MorpheusContext, 'getServices', 0)
        hasMethod(MorpheusContext, 'getSettings', 1)
        hasMethod(MorpheusContext, 'executeCommandOnServer', 2)
        ['getCluster', 'getComputeServer', 'getAdmin', 'getOperationNotification'].every { hasMethod(MorpheusServices, it, 0) }
        hasMethod(MorpheusServices.getMethod('getComputeServer').returnType, 'removeHost', 2)
        ['userId', 'force', 'removeResources', 'removeInstances'].every { com.morpheusdata.request.RemoveHostRequest.getField(it) }
        hasMethod(MorpheusContext, 'getAsync', 0)
        hasMethod(MorpheusContext.getMethod('getAsync').returnType, 'getLoadBalancer', 0)
        hasMethod(MorpheusContext.getMethod('getAsync').returnType.getMethod('getLoadBalancer').returnType, 'getAccountById', 1)
        hasMethod(MorpheusServices, 'getComputeTypeLayout', 0)
        hasMethod(MorpheusServices.getMethod('getComputeTypeLayout').returnType, 'get', 1)
        ['name', 'parentServer', 'status', 'serverGroupMemberStatus', 'statusMessage', 'lastAgentUpdate', 'powerState', 'externalId', 'internalIp', 'externalIp', 'maxMemory'].every { hasProperty(ComputeServer, it) }
        ['layout', 'owner', 'cloud', 'type', 'name'].every { hasProperty(ComputeServerGroup, it) }
        ['success', 'output', 'exitCode', 'error', 'data'].every { f -> TaskResult.fields*.name.contains(f) }
        ['clearAlarm', 'createAlarm', 'list'].every { hasMethod(MorpheusServices.getMethod('getOperationNotification').returnType, it) }
        hasProperty(com.morpheusdata.model.Account, 'parentAccount')
        hasProperty(com.morpheusdata.model.Account, 'masterAccount')
        OptionType.InputType.TEXTAREA != null
        hasMethod(MorpheusServices, 'getNetwork', 0)
        hasMethod(MorpheusServices.getMethod('getNetwork').returnType, 'list', 1)
        ['vlanId', 'gateway', 'displayName', 'name'].every { hasProperty(com.morpheusdata.model.Network, it) }
        hasProperty(ComputeServer, 'account')
        hasProperty(com.morpheusdata.model.Account, 'name')
        ['category', 'uniqueId', 'refType', 'refId', 'status', 'statusMessage', 'name', 'account', 'active'].every { hasProperty(com.morpheusdata.model.OperationNotification, it) }
        hasMethod(MorpheusServices.getMethod('getCluster').returnType, 'save', 1)
        hasMethod(ComputeServerGroup, 'getConfigProperty', 1)
        hasMethod(ComputeServerGroup, 'setConfigProperty', 2)
        // postOnly rests on these two
        Class.forName('com.morpheusdata.views.ViewModel').getField('request').type.getMethod('getMethod') != null
    }

    def 'Morpheus members the new checks use exist'() {
        expect:
        hasProperty(com.morpheusdata.model.Cloud, 'autoRecoverPowerState')
        // not a member on the cluster in this API, so it is read from config. If it appears, read it from there instead
        !hasProperty(ComputeServerGroup, 'autoRecoverPowerState')
        hasMethod(MorpheusServices, 'getCloud', 0)
        hasMethod(MorpheusServices.getMethod('getCloud').returnType, 'get', 1)
        hasMethod(MorpheusServices.getMethod('getCloud').returnType, 'getDatastore', 0)
        hasMethod(MorpheusServices.getMethod('getCloud').returnType.getMethod('getDatastore').returnType, 'list', 1)
        ['heartbeatTarget', 'name', 'online', 'active', 'refType', 'refId', 'id'].every { hasProperty(com.morpheusdata.model.Datastore, it) }
        ['interfaces', 'volumes', 'status', 'maxStorage'].every { hasProperty(ComputeServer, it) }
        ['macAddress', 'network'].every { hasProperty(com.morpheusdata.model.ComputeServerInterface, it) }
        ['datastore', 'maxStorage'].every { hasProperty(com.morpheusdata.model.StorageVolume, it) }
        hasProperty(com.morpheusdata.model.projection.DatastoreIdentity, 'name')
        ['owner', 'id'].every { hasProperty(com.morpheusdata.model.Network, it) }
        hasMethod(MorpheusServices.getMethod('getAdmin').returnType, 'getAppliance', 0)
        hasMethod(MorpheusServices.getMethod('getAdmin').returnType.getMethod('getAppliance').returnType, 'find', 1)
        hasProperty(com.morpheusdata.model.ApplianceInstance, 'applianceUrl')
        hasMethod(MorpheusContext.getMethod('getAsync').returnType.getMethod('getLoadBalancer').returnType, 'getApplianceUrl', 0)
        hasMethod(com.morpheusdata.core.data.DataQuery, 'withFilters', 1)
    }

    /** One cluster on a mocked Morpheus. */
    private HealthContext context(Map opts) {
        def clusters = Mock(com.morpheusdata.core.synchronous.compute.MorpheusSynchronousComputeServerGroupService) { get(_) >> opts.cluster }
        def datastores = Mock(com.morpheusdata.core.synchronous.cloud.MorpheusSynchronousDatastoreService)
        datastores.list(_) >> { args -> (opts.datastores as Closure).call(args[0]) }
        def clouds = Mock(com.morpheusdata.core.synchronous.cloud.MorpheusSynchronousCloudService) {
            getDatastore() >> datastores
            get(_) >> { args -> if (opts.cloudFails) { throw new RuntimeException('no') }; opts.cloud }
        }
        def services = Mock(MorpheusServices) { getCluster() >> clusters; getCloud() >> clouds }
        new HealthContext(Mock(MorpheusContext) { getServices() >> services }, 19L)
    }

    private static List callFailover(HealthContext c) {
        Map m = HealthContext.getDeclaredMethod('failoverConfig').with { accessible = true; invoke(c) } as Map
        [m.autoRecoverPowerState, m.cloudAutoRecoverPowerState, m.datastores]
    }

    def 'the cloud is read in full, because a partial record reads as on'() {
        given:
        def partial = new com.morpheusdata.model.Cloud(id: 5L)
        partial.autoRecoverPowerState = true
        def full = new com.morpheusdata.model.Cloud(id: 5L)
        full.autoRecoverPowerState = false
        ComputeServerGroup g = new ComputeServerGroup(id: 19L, cloud: partial)

        expect:
        callFailover(context(cluster: g, cloud: full, datastores: { [] }))[1] == false
    }

    def 'a cloud that cannot be read has no answer, rather than the default'() {
        given:
        ComputeServerGroup g = new ComputeServerGroup(id: 19L, cloud: new com.morpheusdata.model.Cloud(id: 5L))

        expect:
        callFailover(context(cluster: g, cloudFails: true, datastores: { [] }))[1] == null
    }

    def 'a filter the appliance refuses leaves the others, and nothing answering is unknown rather than none'() {
        given:
        ComputeServerGroup g = new ComputeServerGroup(id: 19L, cloud: new com.morpheusdata.model.Cloud(id: 5L))
        def store = new com.morpheusdata.model.Datastore(id: 7L, name: 'ds-01', refType: 'ComputeServerGroup', refId: 19L)
        store.heartbeatTarget = true
        boolean first = true

        when:
        List mixed = callFailover(context(cluster: g, cloud: null, datastores: { if (first) { first = false; throw new RuntimeException('no such property') }; [store] }))[2]
        List none = callFailover(context(cluster: g, cloud: null, datastores: { throw new RuntimeException('no such property') }))[2]

        then:
        mixed*.name == ['ds-01']
        mixed[0].heartbeatTarget == true
        mixed[0].cluster == true
        none == null
    }

    def 'cluster config values read as on, off or unknown, never unknown as off'() {
        expect:
        HealthContext.configFlag(v) == want

        where:
        v       | want
        true    | true
        'true'  | true
        'on'    | true
        '1'     | true
        false   | false
        'false' | false
        'off'   | false
        '0'     | false
        null    | null
        ''      | null
        'maybe' | null
    }

    def 'the appliance certificate is only read over https, and an address that does not answer has no answer'() {
        expect:
        HealthContext.peerCertNotAfterMs(null) == null
        HealthContext.peerCertNotAfterMs('http://appliance.example.invalid') == null
        HealthContext.peerCertNotAfterMs('https://appliance.example.invalid', 500) == null
    }

    def 'the appliance certificate date comes from a real handshake, whatever signed it'() {
        given: 'a throwaway self-signed certificate on the loopback, good for 40 days'
        File store = File.createTempFile('hh-test', '.p12')
        store.delete()
        char[] pass = 'test-only'.toCharArray()
        Process tool = new ProcessBuilder("${System.getProperty('java.home')}/bin/keytool".toString(), '-genkeypair', '-alias', 't', '-keyalg', 'RSA', '-keysize', '2048', '-validity', '40',
                '-storetype', 'PKCS12', '-keystore', store.path, '-storepass', 'test-only', '-dname', 'CN=appliance.example.invalid').redirectErrorStream(true).start()
        tool.inputStream.text
        tool.waitFor()
        java.security.KeyStore keys = java.security.KeyStore.getInstance('PKCS12')
        store.withInputStream { keys.load(it, pass) }
        def managers = javax.net.ssl.KeyManagerFactory.getInstance(javax.net.ssl.KeyManagerFactory.defaultAlgorithm)
        managers.init(keys, pass)
        def tls = javax.net.ssl.SSLContext.getInstance('TLS')
        tls.init(managers.keyManagers, null, null)
        def server = tls.serverSocketFactory.createServerSocket(0)
        Thread.start {
            try {
                def s = server.accept()
                s.startHandshake()
                s.close()
            } catch (IOException ignored) {
                // the client hangs up once it has the certificate
            }
        }

        when:
        Long at = HealthContext.peerCertNotAfterMs("https://localhost:${server.localPort}".toString())

        then:
        at != null
        Math.abs(at - (System.currentTimeMillis() + 40L * 86400000L)) < 86400000L

        cleanup:
        server?.close()
        store?.delete()
    }

    def 'the new probe sections read only, and read no private key'() {
        given:
        String probe = HealthContext.getResourceAsStream('/scripts/health-probe.sh').getText('UTF-8')

        expect:
        probe.contains('corosync-qdevice-tool -s')
        probe.contains('smartctl -H -A -i')
        !(probe =~ /smartctl[^|\n]*( -s | -t | -X )/)
        probe.contains('bridge -j vlan show dev')
        probe.contains('ovs-vsctl get Port')
        !(probe =~ /ovs-vsctl (set|add|del|--may)/)
        probe.findAll(/openssl [^\n]*/).every { it.startsWith('openssl x509 -noout -enddate') }
        !probe.contains('key_file') && !probe.contains('/private')
        !probe.contains('/opt/morpheus-node/conf') && !probe.contains('morpheus-node.conf')
        // an unread date must stay empty: date -d '' would say today, and a certificate would read as expiring now
        probe.contains('[ -n "$e" ] && date -d "$e" +%s')
    }

    def 'the probe asks for datastore paths only when DLM says it is safe'() {
        given:
        String probe = HealthContext.getResourceAsStream('/scripts/health-probe.sh').getText('UTF-8')
        String line = probe.readLines().find { it.contains('virsh -q pool-list --name') }

        expect:
        line.startsWith('[ "$FS_SAFE" = 1 ] && ')
    }

    def 'Morpheus members the page and the history use exist'() {
        given:
        Class store = MorpheusServices.getMethod('getOperationData').returnType

        expect:
        ['list', 'create', 'get', 'remove'].every { hasMethod(store, it, 1) }
        ['account', 'code', 'category', 'name', 'refType', 'refId', 'refDate', 'rawData'].every { hasProperty(com.morpheusdata.model.OperationData, it) }
        hasMethod(com.morpheusdata.views.HandlebarsRenderer, 'registerNonceHelper', 1)
        hasMethod(MorpheusContext, 'getWebRequest', 0)
        ['getCode', 'getName', 'getCategory', 'getDescription', 'getMasterTenantOnly', 'getSubTenantOnly', 'getDisplayOrder'].every { hasMethod(com.morpheusdata.core.providers.AnalyticsProvider, it, 0) }
        hasMethod(com.morpheusdata.core.providers.AnalyticsProvider, 'loadData', 2)
        hasMethod(com.morpheusdata.core.providers.AnalyticsProvider, 'renderTemplate', 3)
        hasMethod(com.morpheusdata.core.providers.AbstractAnalyticsProvider, 'getRenderer', 0)
    }

    def 'the all-clusters page is registered, and the tab links to it instead of holding it'() {
        given:
        Plugin plugin = new ClusterHealthPlugin()
        Plugin.getDeclaredMethod('setMorpheus', MorpheusContext).with { accessible = true; invoke(plugin, Mock(MorpheusContext)) }
        plugin.initialize()
        String tab = HealthContext.getResourceAsStream('/renderer/hbs/healthTab.hbs').getText('UTF-8')

        expect:
        plugin.providers.any { it instanceof com.morpheusdata.core.providers.AnalyticsProvider && it.code == HealthAnalyticsProvider.CODE }
        !tab.contains('data-section="fleet"')
        tab.contains('/health-pages.js')
        tab.contains('data-section="report"')
    }

    def 'everything in the new script goes in as text, with hh- classes'() {
        given:
        String js = new File('src/assets/javascripts/health-pages.js').getText('UTF-8')

        expect:
        !js.contains('innerHTML')
        !js.contains('document.write')
        js.findAll(/'class': '([^']*)'/) { m, c -> c }.every { c -> c.tokenize().every { it.startsWith('hh-') } }
        js.findAll(/(?m)^\s+'\..*'/).every { !(it =~ /\.(?=[a-zA-Z])(?!hh-)/) }
        // the master tenant cannot open another tenant's cluster, so a row must not link to one
        !js.contains('/infrastructure/clusters/')
    }

    def 'the reference in the tab goes in as text, with hh- classes, and the report keeps its ids as plain text'() {
        given:
        String tab = new File('src/assets/javascripts/health-tab.js').getText('UTF-8')
        String reference = tab.substring(tab.indexOf('// reference: start'), tab.indexOf('// reference: end'))
        String pages = new File('src/assets/javascripts/health-pages.js').getText('UTF-8')
        String hbs = new File('src/main/resources/renderer/hbs/healthTab.hbs').getText('UTF-8')

        expect:
        reference.contains('function reference')
        !reference.contains('innerHTML')
        !reference.contains('insertAdjacentHTML')
        !reference.contains('document.write')
        !reference.contains("'style'")
        reference.findAll(/'class': '([^']*)'/) { m, c -> c }.every { c -> c.tokenize().every { it.startsWith('hh-') } }
        // every new rule in the tab's stylesheet is scoped and prefixed, a dialog has no display of its own to lose, and Help is on the page
        hbs.readLines().findAll { it.contains('.hh-dialog') || it.contains('.hh-ref-') || it.contains('.hh-id') }.every { it.trim().startsWith('.hvm-health .hh-') }
        !hbs.contains('.hh-dialog { display')
        hbs.contains('data-act="help"')
        hbs.indexOf('data-act="help"') > hbs.indexOf('data-act="check"')
        !pages.contains('idLink')
        !pages.contains('hh-id')
    }

    def 'built jar (if present) has the manifest PluginManager reads and loads in a fresh classloader'() {
        given:
        String dir = System.getenv('PLUGIN_BUILD_DIR') ?: 'build'
        File jar = new File(dir, 'libs').listFiles()?.findAll { it.name.endsWith('-all.jar') }?.max { it.lastModified() }
        if (!jar) {
            return   // shadowJar not built in this run
        }

        when:
        def mf = new JarInputStream(new FileInputStream(jar)).manifest
        String cls = mf.mainAttributes.getValue('Plugin-Class')
        def loader = new URLClassLoader([jar.toURI().toURL()] as URL[], Plugin.classLoader)
        def plugin = loader.loadClass(cls).getDeclaredConstructor().newInstance()

        then:
        cls == 'org.hvmhealth.ClusterHealthPlugin'
        mf.mainAttributes.getValue('Plugin-Version')
        plugin instanceof Plugin
    }
}
