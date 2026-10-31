# HVM Cluster Health for HPE Morpheus

A Morpheus 9.1 plugin that adds a **Cluster Health** tab to HVM clusters (layouts 1.3 and 2.0). It checks corosync, DLM, GFS2,
heartbeats, multipath, network and time across every host, compares them with a per-layout standard you can override, and
raises a Morpheus alarm when something fails.

It exists because the worst HVM failures look like something else. When a cluster member leaves uncleanly, DLM stops
every lockspace until that node is acknowledged as fenced, so **all GFS2 I/O on every remaining host blocks**. VMs stop
answering, load climbs into the hundreds, yet the hosts ping and the storage paths look fine. This plugin names that
state, says which node it waits on, and warns about the conditions that lead to it before they happen.

## What you get

**A readiness answer at the top of the tab**: High availability, Live migration, Storage migration, Time, Network.

**Findings**, each with what was observed, what was expected, where that expectation comes from, and what to do.

| Area | Examples |
|---|---|
| Membership | Quorum and quorum margin, corosync nodelist vs the hosts Morpheus has, config and authkey identical on all hosts |
| DLM | Lockspaces waiting on fencing, how long, and whether the HVM agent is acknowledging the right node |
| GFS2 | Withdrawn filesystems, datastore missing on a host, foreign lock tables, journals vs hosts, space, hung tasks |
| VMs | VMs stuck on disk, a VM running on two hosts, agent stops queued during a freeze, Morpheus vs libvirt power state |
| Time | What each host really follows (your cluster's source, the OS default or nothing), offset from it, and drift between hosts in milliseconds |
| Migration | CPU compatibility, naming the CPU model to set (HVM's equivalent of EVC), port reachability, full-size packets between hosts, datastores active everywhere, every VM fitting elsewhere |
| Network | Netmask drift between hosts per network, bond members up, uplinks on different switches and native VLANs (LLDP), corosync sharing a NIC with iSCSI, ring latency |
| Capacity | Whether running VMs fit if the largest host fails |

**A Time panel** shows each host's source and drift, and is where you define the cluster's time source to verify against.

**Morpheus alarms**: every warning and failure raises its own alarm in Operations, warning or error to match, on the host it concerns or otherwise on the cluster, and clears when the finding does. They belong to the tenant that owns the cluster. The plugin API has no way to create Morpheus monitoring checks or incidents, so alarms are the integration.

Reading the cluster never changes anything. The probe does not touch a GFS2 path unless DLM reports it healthy, because
any access to a frozen GFS2 mount blocks forever and adds to the problem.

## Fixes, and what guards them

| Tier | Runs when | Examples |
|---|---|---|
| Safe | Any time; automatically if you turn that on | Turn on NTP, enable a running service at boot |
| Maintenance | Only on a host in maintenance mode, with no running VMs, one host at a time, no freeze anywhere, every host answering, and quorum kept without it. Automatically on such a host if you turn that on | Rejoin the cluster, rescan iSCSI and reload multipath, point the host at the cluster time source |
| Emergency | A person confirms by typing the node name. Never automatic | Acknowledge a failed node as fenced, to end a freeze |

The guards are evaluated at the moment of the fix on freshly probed state, so neither a stale screen nor an automatic pass can
skip them. Both automatic settings are **off** on a new install. Every fix and refusal goes to the Morpheus audit log.

Network changes (MTU, bonds) are left as instructions, because they bounce the link the agent itself uses. Nothing restarts
corosync or DLM on a host with GFS2 mounted.

The emergency action deserves care: acknowledging a node that can still write to shared storage **corrupts the filesystem**.
The plugin treats a node as proven down only if the node itself answers that it rebooted after failing, with no GFS2 mounted
and no DLM running. "It stopped answering" is not proof. A node nobody can reach needs someone to state it is off.

## Install

1. Download the jar from [Releases](https://github.com/nburnett-io/hvm-cluster-health-morpheus-plugin/releases).
2. **Administration > Integrations > Plugins > Add**, upload it.
3. Grant **Cluster Health** (Read to view, Full to fix) to the roles that operate clusters, and **Cluster Health Emergency Actions**
   only to the people trusted to end a freeze. In a multi-tenant setup, raise it on the tenant's base role first: see
   [docs/PERMISSIONS.md](docs/PERMISSIONS.md).
4. Open an HVM cluster and go to **Cluster Health**.

## Settings

| Setting | Default | |
|---|---|---|
| Quick check (minutes) | 5 | Checks every HVM cluster and raises the alarm. 0 turns automatic checks off |
| Full check (hours) | 24 | Adds ring latency, port and packet-size tests, journal counts |
| Fix safe findings automatically | off | |
| Fix findings on hosts in maintenance automatically | off | Putting a host in maintenance mode then counts as consent |
| Site standards | empty | JSON overriding any standard, see [docs/STANDARDS.md](docs/STANDARDS.md) |
| Operator tenants | empty | Tenant ids that may see every cluster, besides the master tenant and each cluster's owner |

## Status

Validated on Morpheus 9.1.0.1 against a live three-host **HVM 2.0** cluster: the checks, tenancy, the maintenance gate, and alarms.

Not yet validated against a real cluster: the **HVM 1.3** layout on a live system (its checks are tested against sample data
built from real 1.3 output), a real freeze and the emergency action (unit tested only; injecting a freeze disrupts every VM
on the cluster), and the rejoin and time-source fixes. See [BACKLOG.md](BACKLOG.md).

## Build

```
PLUGIN_BUILD_DIR=/some/path/outside/cloud/sync ./gradlew test shadowJar
```

Needs JDK 21 or later; the jar targets Java 17 bytecode. Licensed under Apache-2.0. Not affiliated with or supported by Hewlett Packard Enterprise.
