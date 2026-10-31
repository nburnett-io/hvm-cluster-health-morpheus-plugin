package org.hvmhealth.morpheus

import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.Plugin
import com.morpheusdata.core.providers.AbstractAnalyticsProvider
import com.morpheusdata.model.User
import com.morpheusdata.response.ServiceResponse
import com.morpheusdata.views.HTMLResponse
import com.morpheusdata.views.HandlebarsRenderer
import com.morpheusdata.views.Renderer
import com.morpheusdata.views.ViewModel

/**
 * The all-clusters view, as a page under Operations > Analytics: the only provider type in the plugin API that gets a page and a
 * navigation entry of its own (the API has no menu call, and a controller's route has no way into the navigation).
 *
 * The page is a shell. Its rows come from the fleet route, so the role permission Morpheus enforces on routes and the tenant
 * check in the controller both apply, and nothing here depends on how Morpheus honours the master-only flag.
 */
class HealthAnalyticsProvider extends AbstractAnalyticsProvider {
    static final String CODE = 'hvm-cluster-health-allClusters'
    static final String PAGE_URL = '/operations/analytics/' + CODE

    final Plugin plugin
    final MorpheusContext morpheus
    private boolean nonceRegistered = false

    HealthAnalyticsProvider(Plugin plugin, MorpheusContext morpheus) {
        this.plugin = plugin
        this.morpheus = morpheus
    }

    @Override String getCode() { CODE }
    @Override String getName() { 'All HVM clusters' }
    @Override MorpheusContext getMorpheus() { morpheus }
    @Override Plugin getPlugin() { plugin }
    @Override String getCategory() { 'Cluster Health' }
    @Override String getDescription() { 'Health of every HVM cluster, worst first' }
    @Override Boolean getMasterTenantOnly() { true }
    @Override Boolean getSubTenantOnly() { false }
    @Override Integer getDisplayOrder() { 0 }

    /** Unlike the tab's base class, this one registers no nonce helper, and the page's content security policy needs one for the scripts. */
    @Override
    Renderer<?> getRenderer() {
        HandlebarsRenderer r = super.getRenderer() as HandlebarsRenderer
        if (!nonceRegistered) {
            r.registerNonceHelper(morpheus.getWebRequest())
            nonceRegistered = true
        }
        r
    }

    private boolean allowed(User user) {
        Tenancy.isMaster(user?.account, Tenancy.loader(morpheus))
    }

    @Override
    ServiceResponse<Map<String, Object>> loadData(User user, Map<String, Object> params) {
        ServiceResponse.success([allowed: allowed(user)])
    }

    @Override
    HTMLResponse renderTemplate(User user, Map<String, Object> analyticsData, Map<String, Object> params) {
        ViewModel<Map> model = new ViewModel<>()
        model.object = [allowed: allowed(user)]
        getRenderer().renderTemplate('hbs/healthFleet', model)
    }
}
