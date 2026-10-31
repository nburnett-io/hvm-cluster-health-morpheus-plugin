# Backlog

## To validate

- Run against a live HVM 1.3 cluster.
- Inject a real freeze on a disposable cluster: detection end to end, the emergency action, and which VMs the agent stops on thaw.
- Run the rejoin and ntp-sources fixes for real.
- Find out what `notHighAvailability` on the cluster record means.
- Pin `corosyncToken` and `corosyncConsensus` for 2.0 once more clusters confirm the values.

## VLANs actually passing traffic

LLDP and CDP prove cabling and the native VLAN, but switches commonly do not advertise a trunk's allowed VLANs. Proving a VLAN passes needs traffic:

- **Passive, safe to automate:** which VLANs each host's bridge has learned MAC addresses on (OVS on 1.3, Linux bridge on 2.0), compared
  across hosts and with the VLANs of the cluster's Morpheus networks. A VLAN seen on two hosts and silent on a third is a likely pruned trunk.
- **Active, maintenance tier:** a temporary tagged interface on a host in maintenance mode, ARP to the network's gateway and to a peer
  on each VLAN, removed afterwards.
- **The standby uplink:** in active-backup, the second switch's trunk carries nothing until a failover, so a pruned VLAN stays
  invisible until the worst moment. Testing it means failing over, on a host in maintenance mode.
- Optional read-only switch integration, for the allowed list itself.

## For a multi-tenant operator

- **Tenant impact on every finding:** which tenants have VMs on the affected host or datastore.
- **Maintenance pre-flight:** "can I take this host down?" as one button, with the VMs that cannot move and why, and the steps to take it out of the cluster.
- **Alarms for the master tenant** on clusters second-tier tenants own.
- **Storage latency:** what each host measures to the array, and a GFS2 write timing, since a slow datastore looks like a slow cluster.
- **Per-VM CPU drift:** which VMs still run host-passthrough after a cluster CPU model change, and need a power cycle.
- **Firmware and BIOS:** versions compared across hosts through the management controller.
- **Tenant network isolation:** VM ports on the VLAN their Morpheus network says, no VM with an unexpected trunk.
- **Hardware health from the management controller:** ECC errors, power redundancy, failing disks.
- **Fleet view:** every cluster's readiness on one page, across tenants, for the master tenant.
- **History:** when a finding first appeared, and a report for customers or audits.
- **Certificates:** expiry of libvirt and appliance certificates.
- **Storage migration:** free space on the target against the largest VM's disks.
- **Witness and QDevice** state when the layout has one.
- Maximum frame size from LLDP where the switch advertises it.

## Known limits

- Tenancy relies on ownership; see docs/PERMISSIONS.md.
- Results and job history are in memory and reset when the plugin reloads.
- The HCI Ceph layout skips DLM and GFS2 checks and has no Ceph checks yet.
