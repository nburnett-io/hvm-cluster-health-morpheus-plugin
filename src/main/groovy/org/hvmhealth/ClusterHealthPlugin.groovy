package org.hvmhealth

import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.Plugin
import com.morpheusdata.model.OptionType
import com.morpheusdata.model.Permission
import com.morpheusdata.views.HandlebarsRenderer
import groovy.json.JsonSlurper
import org.hvmhealth.morpheus.HealthAnalyticsProvider
import org.hvmhealth.morpheus.HealthController
import org.hvmhealth.morpheus.HealthMonitor
import org.hvmhealth.morpheus.HealthTabProvider

/**
 * HVM Cluster Health: health and compliance for HVM 1.3 and 2.0 clusters. See OVERVIEW.md for the design and
 * docs/STANDARDS.md for what clusters are checked against and where each value comes from.
 */
class ClusterHealthPlugin extends Plugin {
    static final String SOURCE_URL = 'https://github.com/nburnett-io/hvm-cluster-health-morpheus-plugin'

    @Override
    String getCode() {
        'hvm-cluster-health'
    }

    @Override
    void initialize() {
        setName('HVM Cluster Health')
        setDescription('Health and compliance for HVM 1.3 and 2.0 clusters: corosync, DLM, GFS2, heartbeats, multipath and network, with guarded fixes')
        setAuthor('HVM Cluster Health contributors')
        setSourceCodeLocationUrl(SOURCE_URL)
        setIssueTrackerUrl(SOURCE_URL + '/issues')
        // Required whenever the plugin has controllers: without its own renderer, the 9.1.0.1 upload parser rejects the
        // jar with "Failed to parse plugin file".
        setRenderer(new HandlebarsRenderer('renderer', getClassLoader()))

        permissions = [
                // operators (master tenant, cluster owner): read views results, full runs fixes. Customers are refused whatever this says.
                Permission.build('Cluster Health', HealthController.PERM,
                        [Permission.AccessType.none, Permission.AccessType.read, Permission.AccessType.full]),
                // the people trusted to end a cluster-wide freeze by acknowledging a failed node as fenced
                Permission.build('Cluster Health Emergency Actions', HealthController.PERM_EMERGENCY,
                        [Permission.AccessType.none, Permission.AccessType.full]),
        ]

        settings = [
                new OptionType(name: 'Quick check (minutes)', code: 'hvm-health.monitorMinutes', fieldName: 'monitorMinutes',
                        fieldLabel: 'Quick check (minutes)', inputType: OptionType.InputType.TEXT, defaultValue: '5', displayOrder: 0,
                        helpText: 'Checks every HVM cluster this often and raises a Morpheus alarm on failures, so a frozen datastore is found rather than reported. 0 turns automatic checks off.'),
                new OptionType(name: 'Full check (hours)', code: 'hvm-health.fullCheckHours', fieldName: 'fullCheckHours',
                        fieldLabel: 'Full check (hours)', inputType: OptionType.InputType.TEXT, defaultValue: '24', displayOrder: 10,
                        helpText: 'Adds ring latency, journal counts and management controllers. 0 runs only quick checks automatically.'),
                new OptionType(name: 'Fix safe findings automatically', code: 'hvm-health.autoFixSafe', fieldName: 'autoFixSafe',
                        fieldLabel: 'Fix safe findings automatically', inputType: OptionType.InputType.CHECKBOX, defaultValue: 'off', displayOrder: 20,
                        helpText: 'Applies fixes with no effect on the cluster (enabling a running service at boot) without asking. Each is audited.'),
                new OptionType(name: 'Fix findings on hosts in maintenance automatically', code: 'hvm-health.autoFixMaintenance', fieldName: 'autoFixMaintenance',
                        fieldLabel: 'Fix findings on hosts in maintenance automatically', inputType: OptionType.InputType.CHECKBOX, defaultValue: 'off', displayOrder: 30,
                        helpText: 'Applies fixes that could disturb the cluster, only on a host already in maintenance mode, with no VMs, one host at a time, when the cluster keeps quorum without it. Clearing a frozen datastore is never automatic.'),
                new OptionType(name: 'Clusters automatic fixes apply to', code: 'hvm-health.autoFixClusters', fieldName: 'autoFixClusters',
                        fieldLabel: 'Clusters automatic fixes apply to', inputType: OptionType.InputType.TEXT, displayOrder: 32,
                        helpText: 'Ids or names of the clusters (comma separated) that the two automatic switches apply to. Empty means none, so turning a switch on does nothing until a cluster is named. A number is always an id. A name applies to every cluster with that name, in any tenant, so use ids where that matters.'),
                new OptionType(name: 'Site standards', code: 'hvm-health.siteStandards', fieldName: 'siteStandards',
                        fieldLabel: 'Site standards', inputType: OptionType.InputType.TEXTAREA, displayOrder: 40,
                        helpText: 'JSON overriding the built-in standards, for all layouts or per layout. Example: {"gfs2WarnPct": 75, "profiles": {"2.0": {"corosyncToken": 30000}}, "disabledChecks": ["PKG-01"]}. The tab lists every key and where each value came from.'),
                new OptionType(name: 'Operator tenants', code: 'hvm-health.operatorTenants', fieldName: 'operatorTenants',
                        fieldLabel: 'Operator tenants', inputType: OptionType.InputType.TEXT, displayOrder: 50,
                        helpText: 'Ids of tenants (comma separated) that may see every cluster, besides the master tenant and each cluster\'s owner. List only tenants that run infrastructure, never customers: findings name hosts, storage and other tenants\' VMs.'),
        ]

        registerProvider(new HealthTabProvider(this, morpheus))
        registerProvider(new HealthAnalyticsProvider(this, morpheus))
        HealthController controller = new HealthController(this, morpheus)
        registerProvider(controller)
        controllers.add(controller)
        HealthMonitor.start(morpheus, this)
    }

    @Override
    void onDestroy() {
        HealthMonitor.stop()
    }

    static Map readSettings(MorpheusContext morpheus, Plugin plugin) {
        String json = morpheus.getSettings(plugin).blockingGet()
        json ? (new JsonSlurper().parseText(json) as Map) : [:]
    }
}
