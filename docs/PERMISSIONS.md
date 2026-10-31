# Permissions and tenancy

Findings name hosts, networks, storage and other tenants' VMs, so customers must never see them.

## Who gets in

A user needs **both**:

1. **To be an operator of that cluster**: in the master tenant, in the tenant that owns the cluster, or in a tenant listed in the
   *Operator tenants* setting.
2. **The role permission**:

| Permission | Access | Allows |
|---|---|---|
| Cluster Health | Read | The tab, checks, raw probe output |
| Cluster Health | Full | Safe and maintenance fixes; defining the cluster's time source |
| Cluster Health Emergency Actions | Full | Acknowledging a failed node as fenced |

Everyone else gets no tab, and every route refuses them. Morpheus answers 404 for a route the role lacks, and the plugin answers 403 to
a non-operator whose role does have it.

## In a three-tier Morpheus

| Tenant | Result |
|---|---|
| Master tenant | Every cluster, with the role permission |
| Second-tier tenant that owns the cluster | That cluster, with the role permission |
| Another second-tier tenant | Refused |
| Third-tier tenant, a customer | Refused, whatever its role grants |

Permissions start at none. Raise them on the tenant's base role first, then on the user role; a role below cannot exceed the role above
it (Morpheus reports "This permission is locked in the parent role").

## The limit you should know about

The rule is ownership, not depth in the tenant tree, because the plugin API does not expose the tree: `Account.parentAccount` is null on
every record it returns on 9.1. Keeping customers out therefore relies on two things staying true:

- **Clusters belong to the master tenant or a second-tier tenant.** A customer tenant made the owner of a cluster would be its operator.
- **The Operator tenants setting lists only tenants that run infrastructure.**

Alarms go to the tenant that owns the cluster, so a master tenant does not see the alarms of a cluster a second-tier tenant owns, though it can open that cluster's tab.
