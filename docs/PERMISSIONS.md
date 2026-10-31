# Permissions and tenancy

Findings name hosts, networks, storage and other tenants' VMs, so customers must never see them.

## Who gets in

A user needs **both**:

1. **To be an operator of that cluster**: in the master tenant, in the tenant that owns the cluster, or in a tenant listed in the
   *Operator tenants* setting.
2. **The role permission**:

| Permission | Access | Allows |
|---|---|---|
| Cluster Health | Read | The tab, checks, pre-flights, raw probe output |
| Cluster Health | Full | Safe and maintenance fixes, VLAN tests, defining the cluster's time source |
| Cluster Health Emergency Actions | Full | Acknowledging a failed node as fenced; Remove safely |

The all-clusters page (Operations > Analytics > Cluster Health) is for the master tenant only, whatever a role grants. Findings show which tenants are affected, which is why customers must never reach them. It needs the Cluster Health permission to show any rows, and the role's Analytics access for the menu entry to appear.

The report on a cluster is for that cluster's operators, with Cluster Health Read. It can be handed on, so it holds no tenant data: who a finding affects and the names of tenants, VMs and networks are withheld. Host and storage names stay, so read it before it leaves.

Everyone else gets no tab, and every route refuses them. Morpheus answers 404 for a route the role lacks, and the plugin answers 403 to
a non-operator whose role does have it, the same for a cluster that does not exist. A refusal goes to the appliance's log, not the audit log, whose entries land in the caller's tenant and would name the cluster.

Fixes, VLAN tests, the time source, Remove safely and the acknowledgement answer POST only, so a link cannot run them: a GET carries no CSRF token.

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

Alarms go to the tenant that owns the cluster, and the master tenant gets its own copy.
