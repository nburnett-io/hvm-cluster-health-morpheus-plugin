# Changelog

## 0.2.0

- **Can I take it down?** A read-only pre-flight per host: quorum without it, where each VM can go, what stands in the way.
- **Safe eject**, off by default and not yet proven on a live cluster: takes a host out of the cluster so nothing waits to fence it.
- **VLANs:** a passive check of which VLANs each host hears, and a test on a host in maintenance mode that ARPs for each gateway through each uplink, including the standby one.
- **Tenant impact** on every finding.
- **All-clusters view** for the master tenant, and the master tenant gets its own copy of every alarm.
- **Storage latency** per LUN, and GFS2 metadata response.
- **Hardware:** power supply redundancy, memory errors, temperatures, serious hardware log events, firmware differences among identical servers, uplink speeds.
- A missing heartbeat is now a warning: HVM removes it about 90 s into maintenance mode, and it returns up to a minute after leaving.

## 0.1.2

First release. Cluster Health tab for HVM 1.3 and 2.0 clusters.

- Read-only probe through the Morpheus agent that never touches a frozen GFS2 mount.
- Checks across hosts: membership and quorum margin, DLM fence waits and agent acknowledgements, GFS2, heartbeats, VMs, multipath,
  network, netmask drift, LLDP neighbours, time, live and storage migration readiness, N+1 capacity, and Morpheus's view against the hosts'.
- Readiness summary: high availability, live migration, storage migration, time, network.
- Time panel: what each host really follows, offset, drift between hosts in milliseconds, and a cluster time source to verify against.
- The CPU model to set when hosts differ, HVM's equivalent of EVC.
- Standards per layout with a named source for every value, and site overrides.
- Fixes in three tiers (safe, maintenance, emergency) with guards evaluated on fresh state, automatic fixes off by default.
- Automatic checks, a Morpheus alarm per finding at matching severity, audit log entries.
- Operators only: master tenant and the cluster's owner; customers are refused.
