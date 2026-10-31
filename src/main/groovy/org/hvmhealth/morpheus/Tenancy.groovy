package org.hvmhealth.morpheus

import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.model.Account
import com.morpheusdata.model.ComputeServerGroup
import com.morpheusdata.model.User

/**
 * Who may see cluster health, for a multi-tier Morpheus (master tenant -> environment tenant -> customers).
 *
 *  Operators: the master tenant (every cluster), the tenant that OWNS the cluster, and tenants listed in the
 *  "Operator tenants" setting. Everyone else is refused: no tab, and every route refuses them whatever their role
 *  grants, because health findings name hosts, networks, storage and other tenants' VMs.
 *
 * Ownership decides, not depth in the tenant tree: on 9.1 the plugin API does not expose the tree (Account.parentAccount
 * is null on every record it returns), so a tier cannot be read. Clusters belong to the master tenant or a second-tier
 * tenant, so customers own none and are refused. Only a customer tenant that was made the owner of a cluster, or
 * listed in the setting, would get in: see docs/PERMISSIONS.md.
 */
class Tenancy {
    /** Tenant ids from the "Operator tenants" setting (comma or space separated; names are not accepted). */
    static Set<Long> operatorTenantIds(Map settings) {
        ((settings?.operatorTenants ?: '') as String).tokenize(', ;').findAll { it.isLong() }.collect { it as Long } as Set<Long>
    }

    /** A partial record reads masterAccount=false by default, so the full record is preferred when it can be loaded. */
    static boolean isMaster(Account a, Closure<Account> load = { null }) {
        if (a == null) {
            return false
        }
        ((a.id != null ? load.call(a.id) : null) ?: a).masterAccount == true
    }

    static boolean isOperator(User user, ComputeServerGroup cluster, Collection<Long> extraOperators = [], Closure<Account> load = { null }) {
        Account a = user?.account
        if (a?.id == null) {
            return false
        }
        isMaster(a, load) || (cluster?.owner?.id != null && a.id == cluster.owner.id) || extraOperators.contains(a.id)
    }

    static Closure<Account> loader(MorpheusContext morpheus) {
        return { Long id ->
            try {
                // Plugin API 1.5.0 has no account service; this is the only lookup of an account by id it exposes
                morpheus.async.loadBalancer.getAccountById(id).blockingGet()
            } catch (Throwable ignored) {
                null
            }
        }
    }
}
