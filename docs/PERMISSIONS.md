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
| Cluster Health | Full | Safe and maintenance fixes, VLAN tests, defining the cluster's time source, starting and ending a change window |
| Cluster Health Emergency Actions | Full | Acknowledging a failed node as fenced; Remove safely |

The all-clusters page (Operations > Analytics > Cluster Health) is for the master tenant only, whatever a role grants. Findings show which tenants are affected, which is why customers must never reach them. It needs the Cluster Health permission to show any rows, and the role's Analytics access for the menu entry to appear.

The report on a cluster is for that cluster's operators, with Cluster Health Read. It can be handed on, so it holds no tenant data: who a finding affects and the names of tenants, VMs and networks are withheld. Host and storage names stay, so read it before it leaves. A change window's reason and the Busiest VMs panel are left out as well.

A finding's own observation can carry names too, so the report withholds it by allow-list: it is included only for checks known to name just hosts, storage, configuration and numbers, and withheld for every other check, including any added later. So the nested-virtualization (SEC-01), storage-VLAN (SEC-05) and per-VM-limit (SEC-06) findings, and cache=unsafe (VM-07), along with the ones that already name VMs, show no observation in a report, and a new check cannot leak by omission. Scrubbing known names stays on top, and the report still fails rather than leak when those names cannot be read.

Who a finding reaches, with tenant names and counts, shows on the tab and in the alarm's text. Both are for operators: alarms go only to the tenants below. The Busiest VMs panel is on the tab only. A change window's user and reason show on the tab to anyone with Read there, and on the all-clusters page for the master tenant.

The busiest VMs on a host, with their tenants, go to a cluster's operators with the rest of its check results, and are never in a report.

Availability figures are for that cluster's operators, with Cluster Health Read, and list the tenants on the cluster, as findings do. A tenant's CSV is meant to leave: it holds that tenant's own figures and VMs, no other tenant, and no host, storage or cluster name, as a cluster goes by its id. An export across clusters, shared causes (FLT-01), and the all-clusters sections are for the master tenant only, whatever a role grants.

Everyone else gets no tab, and every route refuses them. Morpheus answers 404 for a route the role lacks, and the plugin answers 403 to
a non-operator whose role does have it, the same for a cluster that does not exist. A refusal goes to the appliance's log, not the audit log, whose entries land in the caller's tenant and would name the cluster.

The **status URL**, off until a port is set, is the one thing with no login, because a URL monitor has no session. It gives whoever can reach the port only `OK`, `WARNING`, `CRITICAL`, `STALE` or `UNKNOWN`, the word `reloading`, and counts: never a host, cluster, tenant, VM or finding, with no way to list clusters. See [ALERTING.md](ALERTING.md), and limit the port to your monitor's addresses. A reader holding the private half of a key in **Reader keys** also gets `/v1/clusters` there: every monitored cluster by id, with its check ids by status, readiness and times, and still never a host, cluster name, tenant, VM or finding. Give a key only to a tool the master tenant runs.

How the monitor itself is doing, on the all-clusters page, is for the master tenant only, by the same rule as the page. It is numbers, and the name of the appliance node running the checks. A cluster's tab says only that another node runs them, as it goes to whoever owns the cluster.

Fixes, VLAN tests, the time source, change windows, Remove safely and the acknowledgement answer POST only, so a link cannot run them: a GET carries no CSRF token.

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
