package org.hvmhealth.morpheus

import com.morpheusdata.core.AbstractClusterTabProvider
import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.Plugin
import com.morpheusdata.model.Account
import com.morpheusdata.model.ComputeServerGroup
import com.morpheusdata.model.User
import com.morpheusdata.views.HTMLResponse
import com.morpheusdata.views.ViewModel
import org.hvmhealth.ClusterHealthPlugin
import groovy.util.logging.Slf4j

/** "Cluster Health" tab on HVM cluster pages. All data loads client-side from /plugin/hvm-health/*. */
@Slf4j
class HealthTabProvider extends AbstractClusterTabProvider {
    final Plugin plugin
    final MorpheusContext morpheus

    /**
     * Must be a valid CSS id: Morpheus renders the tab as href="#<code>" and Bootstrap selects the pane with it,
     * so a '.' (read as a class selector) leaves the tab permanently blank.
     */
    static final String CODE = 'hvm-cluster-health-clusterTab'

    HealthTabProvider(Plugin plugin, MorpheusContext morpheus) {
        this.plugin = plugin
        this.morpheus = morpheus
    }

    @Override String getCode() { CODE }
    @Override String getName() { 'Cluster Health' }
    @Override MorpheusContext getMorpheus() { morpheus }
    @Override Plugin getPlugin() { plugin }

    @Override
    HTMLResponse renderTemplate(ComputeServerGroup cluster) {
        ViewModel<ComputeServerGroup> model = new ViewModel<>()
        model.object = cluster
        getRenderer().renderTemplate('hbs/healthTab', model)
    }

    /** An HVM (MVM/KVM) type code, or none recorded. Kubernetes clusters on HVM have their own health. */
    static boolean hvmType(String code) {
        !code || (code.toLowerCase() ==~ /.*(mvm|hvm|kvm).*/ && !code.toLowerCase().contains('kube'))
    }

    /**
     * HVM clusters, for operators only: the master tenant or the cluster's owner (see Tenancy). The cluster and user Morpheus
     * passes in can be partial records (no owner, no account), so what is missing is loaded first.
     */
    @Override
    Boolean show(ComputeServerGroup cluster, User user, Account account) {
        if (!hvmType(cluster?.type?.code)) {
            return false
        }
        Set<Long> extra = [] as Set<Long>
        try {
            extra = Tenancy.operatorTenantIds(ClusterHealthPlugin.readSettings(morpheus, plugin))
        } catch (Exception ignored) {
            // settings unreadable: master tenant and cluster owner still qualify
        }
        ComputeServerGroup full = cluster?.owner == null && cluster?.id ? (morpheus.services.cluster.get(cluster.id) ?: cluster) : cluster
        User who = user?.account != null || account == null ? user : new User(id: user?.id, username: user?.username, account: account)
        boolean ok = Tenancy.isOperator(who, full, extra, Tenancy.loader(morpheus))
        if (!ok) {
            log.debug("cluster health tab hidden on ${full?.name}: user ${who?.username} account ${who?.account?.id}, owner ${full?.owner?.id}")
        }
        ok
    }
}
