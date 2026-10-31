# Design overview

## The failure this was built around

A three-host HVM cluster with one GFS2 datastore on an iSCSI LUN. One host was being taken out to move it to a new cluster on a
newer layout, and it left uncleanly: the survivors logged that they never received its leave message. What followed, in order:

1. Corosync on the survivors lost the node and formed a new membership. They kept quorum, 2 of 3 votes.
2. DLM stopped the lockspace and waited for the node to be fenced. Pacemaker is masked by design on HVM, so the normal fence call
   fails, and the HVM agent's `dlm_tool fence_ack` is what normally clears the wait.
3. The agents acknowledged **each other**, never the failed node, about every 4 seconds. The wait never cleared.
4. Every GFS2 access blocked: VM disks, and the agent's heartbeat file writes every 10 seconds, which piled up into hundreds of
   stuck tasks. Load average passed 600 on a host at 3% CPU.
5. The manager VM lived on that datastore, so the Morpheus console went down with it.
6. A manual `fence_ack` ended it in under a second. Then the VMs died anyway, because the agent had been queueing
   shutdown, destroy and `kill -9` throughout the freeze, and those all took effect when I/O returned.

Every symptom pointed elsewhere: the host pinged, multipath was healthy, the array was idle. So the plugin's first duty is
to name this state, and its second is to warn about the precondition: a node corosync counts that Morpheus no longer does (CLU-05), or
a host in maintenance mode, which is still a member (HA-02).

## Structure

```
health-probe.sh     read-only bash, run on each host through the Morpheus agent; prints @@sections
HostFacts           parses one host's output
Layout, Standards   which layout this is; what it is checked against, with sources
HealthChecker       every host's facts together -> findings, readiness
Fixes               what may change, the guards, the fence plan
HealthContext       one cluster through the plugin API
HealthMonitor       automatic checks, alarm, automatic fixes
HealthController    routes, tenancy, jobs, audit
health-tab.js       the tab
```

Most important checks only exist across hosts, so the checker takes all hosts at once rather than judging each alone.

## Decisions worth keeping

**The probe never blocks.** Nothing touches a GFS2 path unless every lockspace is healthy, and even then from a background job
with a deadline. `timeout` cannot kill a process in uninterruptible sleep, so a timeout alone would not protect the probe.

**No SSH.** Everything runs through the Morpheus agent, like the rest of Morpheus.

**Hosts are matched to corosync nodes by ring address.** HVM 2.0 names nodes `host-<nodeid>`, so names do not line up. The
`morpheus_version` header in `corosync.conf` is a template version, 1.3 on 2.0 clusters too, so the layout comes from the
layout code only.

**Maintenance mode is `status == 'maintenance'`.** On 9.1.0.1 the member status is empty for plugins and the REST
`maintenanceMode` field has no counterpart on the model. Maintenance mode also removes the host's heartbeat file, measured at about
90 s after entering, and it returns 40-60 s after leaving, so a missing heartbeat is expected around then. The host stays in corosync
throughout, so powering it off still freezes the cluster.

**A clean leave needs no fencing.** DLM fences a node that fails while it is in a lockspace. Safe eject removes the reason: unmounting
GFS2 leaves the lockspace first. That is why the order is unmount, dlm, corosync, then the nodelist, and why it stops if a survivor shows a wait.

**The VLAN test runs detached.** It moves the active uplink, which can cut off the very agent that started it, so it runs under `setsid`, writes
to a file the plugin polls, and has a timer that puts the uplink back whatever happens. A test that produces no results is a failure,
never a pass: an early build reported success after a shell quoting error stopped it testing anything.

**A gateway silent on every uplink is reported differently** from one silent on one uplink. The second is a pruned trunk; the first says more
about the gateway.

**Tenancy is by ownership.** See [docs/PERMISSIONS.md](docs/PERMISSIONS.md).

**LLDP proves cabling, not VLANs.** Switches commonly advertise only the native VLAN, not a trunk's allowed list.

**CPU recommendations come from `usable='yes'` models, in the host's family.** `virsh cpu-baseline` answers with whichever model is closest
plus added features, which can be an unrelated Atom server model: useless as a model name alone. And libvirt's versioned names (`-v5`) are not
in Morpheus's list.

**Host drift uses offsets to shared time servers.** Two hosts measuring one server compares their clocks directly.

**Quick checks keep the last full answer** for what they do not run, so alarms from full-only checks do not come and go.

## What the tab's classes teach

Morpheus's stylesheet and Bootstrap define `.banner`, `.row`, `.muted` and others globally, and a plugin tab inherits them.
Every class in the tab is `hh-` prefixed. Morpheus serves plugin assets with a one-year cache under a fixed name, so the tab script's
URL carries the plugin version: a new script needs a new version.
