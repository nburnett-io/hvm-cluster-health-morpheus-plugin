package org.hvmhealth

import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.model.Account
import com.morpheusdata.model.ComputeServerGroup
import com.morpheusdata.model.ComputeServerGroupType
import com.morpheusdata.model.User
import org.hvmhealth.morpheus.HealthTabProvider
import org.hvmhealth.morpheus.Tenancy
import spock.lang.Specification

/** Master tenant, Acme and Globex (each runs its own clusters), Initech (a customer on Acme's cluster). */
class TenancySpec extends Specification {
    static final Account MASTER = new Account(id: 1, name: 'Master', masterAccount: true)
    static final Account ACME = new Account(id: 2, name: 'Acme', masterAccount: false)
    static final Account GLOBEX = new Account(id: 3, name: 'Globex', masterAccount: false)
    static final Account INITECH = new Account(id: 4, name: 'Initech', masterAccount: false)

    private static ComputeServerGroup owned(Account a) {
        new ComputeServerGroup(id: 15, owner: a, type: new ComputeServerGroupType(code: 'mvm-cluster'))
    }

    def 'the master tenant sees every cluster'() {
        expect:
        Tenancy.isOperator(new User(account: MASTER), owned(ACME))
        Tenancy.isOperator(new User(account: MASTER), owned(MASTER))
    }

    def 'a tenant that owns the cluster sees it, and no other tenant does'() {
        expect:
        Tenancy.isOperator(new User(account: ACME), owned(ACME))
        !Tenancy.isOperator(new User(account: GLOBEX), owned(ACME))
        !Tenancy.isOperator(new User(account: ACME), owned(MASTER))
    }

    def 'a customer of the owning tenant is refused'() {
        expect:
        !Tenancy.isOperator(new User(account: INITECH), owned(ACME))
        !Tenancy.isOperator(new User(account: INITECH), owned(MASTER))
    }

    def 'the setting lets a listed tenant see other clusters'() {
        expect:
        Tenancy.isOperator(new User(account: GLOBEX), owned(ACME), [3L])
    }

    def 'no user, no account or a cluster with no owner is refused'() {
        expect:
        !Tenancy.isOperator(new User(), owned(ACME))
        !Tenancy.isOperator(null, owned(ACME))
        !Tenancy.isOperator(new User(account: INITECH), new ComputeServerGroup(id: 15))
    }

    def 'a partial master record is recognised from the full one'() {
        given: 'a partial record reads masterAccount=false by default'
        Closure<Account> load = { Long id -> [1L: MASTER, 2L: ACME][id] }

        expect:
        Tenancy.isOperator(new User(account: new Account(id: 1)), owned(ACME), [], load)
        !Tenancy.isOperator(new User(account: new Account(id: 2)), owned(GLOBEX), [], load)
    }

    def 'the operator tenants setting reads ids only'() {
        expect:
        Tenancy.operatorTenantIds([operatorTenants: '2, 7;x 9']) == [2L, 7L, 9L] as Set
        Tenancy.operatorTenantIds([:]) == [] as Set
    }

    def 'the tab shows on HVM clusters only'() {
        expect:
        HealthTabProvider.hvmType(code) == shown

        where:
        code               | shown
        'mvm-cluster'      | true
        'kvm-cluster'      | true
        null               | true
        'vmware-cluster'   | false
        'hvm-kube-cluster' | false
    }

    def 'the tab is hidden from a customer'() {
        given:
        def tab = new HealthTabProvider(new ClusterHealthPlugin(), Stub(MorpheusContext))

        expect:
        tab.show(owned(ACME), new User(account: ACME), ACME)
        !tab.show(owned(ACME), new User(account: INITECH), INITECH)
    }
}
