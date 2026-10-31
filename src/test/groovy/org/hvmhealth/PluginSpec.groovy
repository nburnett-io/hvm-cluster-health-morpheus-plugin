package org.hvmhealth

import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.MorpheusServices
import com.morpheusdata.core.Plugin
import com.morpheusdata.model.ComputeServer
import com.morpheusdata.model.ComputeServerGroup
import com.morpheusdata.model.OptionType
import com.morpheusdata.model.TaskResult
import io.reactivex.rxjava3.core.Single
import org.hvmhealth.morpheus.HealthContext
import org.hvmhealth.morpheus.HealthController
import org.hvmhealth.morpheus.HealthMonitor
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
    }

    def 'every change needs more than read, and the emergency action its own permission'() {
        when:
        Map routes = new HealthController(null, null).routes.collectEntries { r -> [(r.method): r.permissions*.code + r.permissions*.availableAccessTypes.flatten()*.toString()] }

        then:
        routes.status.contains('read')
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
        int background = probe.indexOf(') > "$OUT" 2>/dev/null &')

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
        HealthContext.probeScript('quick').startsWith('set -- quick\n')
        HealthContext.probeScript('full').startsWith('set -- full\n')
        HealthContext.probeScript('anything').startsWith('set -- full\n')
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
