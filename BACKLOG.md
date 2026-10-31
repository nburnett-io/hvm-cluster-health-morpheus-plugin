# Backlog

## To validate

- Run against a live HVM 1.3 cluster, including the Open vSwitch side of the VLAN checks and test.
- Run safe eject on a test cluster, then re-add the host. Find out what Morpheus's own remove-host does to `corosync.conf`, and whether its agent remounts anything during the eject.
- Inject a real freeze on a disposable cluster: detection end to end, the emergency action, and which VMs the agent stops on thaw.
- Run the rejoin and ntp-sources fixes for real.
- A temporary VLAN interface reaching a real gateway: VLANs with their own host interface are proven, but a temporary interface ARPs from 0.0.0.0, and not every gateway answers that.
- Find out what `notHighAvailability` on the cluster record means.
- Pin `corosyncToken` and `corosyncConsensus` for 2.0 once more clusters confirm the values.

## Next

- **Tenant network isolation:** VM ports on the VLAN their Morpheus network says, no VM with an unexpected trunk.
- **History:** when a finding first appeared, and a report for customers or audits.
- **Per-VM CPU drift:** which VMs still run host-passthrough after a cluster CPU model change, and need a power cycle.
- **Certificates:** expiry of libvirt and appliance certificates.
- **Storage migration:** free space on the target against the largest VM's disks.
- **Witness and QDevice** state when the layout has one.
- **Disks:** SMART on boot disks.
- Maximum frame size from LLDP where the switch advertises it.
- Optional read-only switch integration, for a trunk's allowed VLANs.
- A page of its own for the all-clusters view, in Morpheus's navigation.

## Known limits

- Tenancy relies on ownership; see docs/PERMISSIONS.md.
- The master tenant is found as tenant id 1, checked to be the master, because the plugin API cannot list tenants.
- Results and job history are in memory and reset when the plugin reloads, so the all-clusters view starts as "not checked".
- The passive VLAN check is a hint: learned addresses age out after minutes, so a quiet VLAN looks like a pruned one.
- The HCI Ceph layout skips DLM and GFS2 checks and has no Ceph checks yet.
