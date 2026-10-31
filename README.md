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

**Findings**, each with what was observed, what was expected, where that expectation comes from, and what to do. Click a check's id for what it looks at, why it matters and what to do about it, or press **Help** to search all of them. The same reference is in [docs/CHECKS.md](docs/CHECKS.md).

| Area | Examples |
|---|---|
| Membership | Quorum and quorum margin, quorum device, corosync nodelist vs the hosts Morpheus has, config and authkey identical on all hosts |
| DLM | Lockspaces waiting on fencing, how long, and whether the HVM agent is acknowledging the right node |
| GFS2 | Withdrawn filesystems, consistency errors in the kernel log, datastore missing on a host, foreign lock tables, journals vs hosts, space, hung tasks |
| High availability | Whether a lost host's VMs restart ("Automatically Power On VMs"), a heartbeat target |
| VMs | VMs stuck on disk, a VM running on two hosts, agent stops queued during a freeze, Morpheus vs libvirt power state |
| Time | What each host really follows (your cluster's source, the OS default or nothing), offset from it, and drift between hosts in milliseconds |
| Migration | CPU compatibility, naming the CPU model to set (HVM's equivalent of EVC), VMs pinned to their host or left on host-passthrough, port reachability, full-size packets between hosts, datastores active everywhere, every VM fitting elsewhere |
| Network | Netmask drift between hosts per network, bond members up, uplinks on different switches and native VLANs (LLDP), iSCSI on physical ports of its own, ring latency, frame size |
| Tenant isolation | VM ports on the VLAN Morpheus says, trunks, and ports with no VLAN tag, which let a guest on 1.3 reach other VLANs |
| Capacity | Whether running VMs fit if the largest host fails, free space for the largest VM's disks |
| Storage | Latency each host measures to each LUN, and how long GFS2 metadata takes to answer |
| Hardware | Power supply redundancy, memory errors, boot disk SMART, certificates, temperatures, serious hardware log events, BIOS and firmware differences among identical servers, uplink speeds |

**Every finding names who it affects**: "2 running VMs: Acme (1), Globex (1)".

**Per host:**
- **Can I take it down?** A read-only answer: quorum without it, where each VM can go, and what stands in the way.
- **Test VLANs**, on a host in maintenance mode: ARPs for each network's gateway through each uplink in turn, including the standby one, on every active-backup bond on a bridge, then puts everything back. VLANs go in runs short enough for corosync's token, so a standby that lacks the ring's VLAN cannot cost the host its membership. It needs `arping` on the host, and says "not tested" without it.
- **Remove safely**: runs the checks Morpheus's remove does not make, unmounts GFS2 with a time limit, then calls that remove and watches the remaining hosts. See below.

**A Time panel** shows each host's source and drift, and is where you define the cluster's time source to verify against.

**An all-clusters page** for the master tenant, worst first, under Operations > Analytics > Cluster Health.

**History and a report.** Each finding shows when it first appeared. A cluster's report lists current findings and when past ones appeared and cleared, to print or export as CSV or JSON. It names no tenants, VMs or networks.

**Morpheus alarms**: every warning and failure raises its own alarm in Operations, warning or error to match, on the host it concerns or otherwise on the cluster, and clears when the finding does, or when its cluster is gone or automatic checks are off. Its text is from when it was raised, so an acknowledgement and start time last. A check that cannot run, such as with a typo in the site standards, raises its own alarm. They go to the tenant that owns the cluster, and the master tenant gets its own copy. The plugin API has no way to create Morpheus monitoring checks or incidents, so alarms are the integration.

Reading the cluster never changes anything. The probe does not touch a GFS2 path unless DLM reports it healthy, because
any access to a frozen GFS2 mount blocks forever and adds to the problem.

## Fixes, and what guards them

| Tier | Runs when | Examples |
|---|---|---|
| Safe | Any time; automatically if you turn that on | Enable a running service at boot |
| Maintenance | Only on a host in maintenance mode, with no running VMs, one host at a time, no freeze anywhere, every host answering, and quorum kept without it. Automatically on such a host if you turn that on | Rejoin the cluster, rescan iSCSI and reload multipath, turn on NTP, point the host at the cluster time source |
| Emergency | A person confirms by typing the node name. Never automatic | Acknowledge a failed node as fenced, to end a freeze |

The guards are evaluated at the moment of the fix on freshly probed state, so neither a stale screen nor an automatic pass can
skip them. Both automatic settings are **off** on a new install, and they are global, so they do nothing on a cluster until **Clusters automatic fixes apply to** names it. Every fix, and every fix refused, goes to the Morpheus audit log.

Network changes (MTU, bonds) are left as instructions, because they bounce the link the agent itself uses. Nothing restarts
corosync or DLM on a host with GFS2 mounted.

## Removing a host without freezing the cluster

Maintenance mode empties a host but leaves it a cluster member. Power it off from there and DLM treats it as failed: every GFS2 datastore on the other hosts stalls until it is acknowledged as fenced. HVM's agent normally does that within about 2 minutes. When it doesn't, the stall becomes a freeze, and after about 9 minutes the agent starts stopping VMs.

A node that **leaves cleanly** is simply gone. Morpheus's own remove does this when all goes well: it unmounts GFS2, which takes the host out of every lockspace; stops dlm, then corosync, so it announces it is leaving; and updates `corosync.conf` on the rest. But it carries on if GFS2 is in use, or if the unmount hangs. Then the host's services stop about six minutes later, its record goes, and the others can wait to fence a node nothing acts on any more.

**Remove safely**, in a host's pre-flight, deals with that first. It checks the host is in maintenance mode with no VMs on record, nothing has GFS2 open, the rest keep quorum, and none of them is recovering. It unmounts GFS2 with a time limit, and a hang stops there, with the host still known to Morpheus. Only then does it call Morpheus's remove, and it watches until the remaining hosts count one fewer. Morpheus's own Remove button still works and skips these checks, as the plugin API cannot intercept it.

After losing a host, leave its record alone until every remaining host is healthy.

Taking one host from three leaves two with both votes needed, so either one failing stops every datastore. Finish the move quickly, or add a witness.

## The emergency action

Fence acknowledgement is never automatic. Live tests showed why a storage array cannot decide it: a hung host's sessions vanish within about a minute, then it logs back in on waking, and writes it was holding land on the LUN.

The emergency action deserves care: acknowledging a node that can still write to shared storage **corrupts the filesystem**.
The plugin treats a node as proven down only if the node itself answers that it rebooted after failing, with no GFS2 mounted
and no DLM running. "It stopped answering" is not proof. A node nobody can reach needs someone to state it is off.

## Before you install

- **It runs as root on your hypervisors, through the Morpheus agent, as soon as it loads.** A read-only probe runs on every HVM host in every cluster about every 5 minutes, and a fuller one daily. To look first, set **Quick check** to 0 straight after installing.
- **The first check can raise a lot of alarms.** Each finding is its own alarm, for the cluster's owner and the master tenant. If your design differs from the defaults, such as iSCSI sharing ports with management on purpose, set **Site standards** first: see [docs/STANDARDS.md](docs/STANDARDS.md).
- **Nothing changes on a host until someone runs a fix,** unless you turn on automatic fixes and name the clusters they apply to.
- **Remove safely removes a host for real,** through Morpheus. It needs a host in maintenance mode, the Emergency permission and the host's name typed in.
- **Removing the plugin** leaves its history records in Morpheus's data store, on the master tenant.

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
| Clusters automatic fixes apply to | empty | Cluster ids or names, comma separated, that the two settings above apply to. Empty means none. A number is always an id, and a name applies to every cluster with that name in any tenant. Checked again when a fix runs |
| Site standards | empty | JSON overriding any standard, see [docs/STANDARDS.md](docs/STANDARDS.md) |
| Operator tenants | empty | Tenant ids that may see every cluster, besides the master tenant and each cluster's owner |

## Status

**Pre-1.0.** Tested on one appliance version, Morpheus 9.1.0.1, against three-host **HVM 2.0** and **HVM 1.3** clusters. The 1.3 cluster, and most of the disruptive testing, ran on nested hosts with a single iSCSI path. That covered the checks, tenancy including a tier-3 login, alarms, the pre-flight, Remove safely, and the fixes.

The emergency acknowledgement ended a freeze when DLM was waiting on a fence request, including a real one forced by blocking the agent's own acknowledgement. **It does not always work.** In one freeze with no fence request, DLM refused the acknowledgement (`-125`, no need_fencing), and the plugin says so.

Not yet run on a real system: management controllers and other hardware checks on 1.3 hosts, a witness, tenant isolation across a real trunk uplink, Remove safely against a real hung unmount, and HA-06 finding a manager on its own cluster. See [BACKLOG.md](BACKLOG.md).

## Build

```
PLUGIN_BUILD_DIR=/some/path/outside/cloud/sync ./gradlew test shadowJar
```

Needs JDK 21 or later; the jar targets Java 17 bytecode. Licensed under Apache-2.0. Not affiliated with or supported by Hewlett Packard Enterprise.
