# Check reference

What every check id means. The tab shows the same text: click an id, or press Help. Generated from `src/main/groovy/org/hvmhealth/checks/CheckCatalog.groovy`, so edit that, not this file: `CheckCatalogSpec` fails if this one falls behind, and prints the command that regenerates it.

## Statuses

- **FAIL**: Wrong now. Raises an error alarm in Morpheus.
- **WARN**: Wrong or at risk, but not a failure. Raises a warning alarm in Morpheus.
- **NOT CHECKED**: The check applies, but could not be answered here, usually for want of a tool on the host. It says nothing either way. It raises no alarm, and one already open stays open.
- **NA**: The check does not apply here, or is only a note. No alarm.
- **PASS**: Fine at the last check. The readiness table shows this as OK.

## Id prefixes

| Prefix | Area |
|---|---|
| CAP | Capacity |
| CLU | Cluster membership |
| DLM | DLM locking |
| FEN | Fencing |
| GFS | GFS2 |
| HA | High availability |
| HB | Heartbeats |
| HST | Host |
| HW | Hardware |
| LAY | Layout |
| MIG | Migration |
| MOR | Morpheus's view |
| NET | Network |
| PKG | Packages |
| PRB | The plugin itself |
| STO | Storage |
| TIM | Time |
| VM | VMs |

## Notes

- Automatic checks are quick checks, with a full check on a longer interval. Run full check runs one now. A check marked "Full check only" keeps its last answer between full checks.
- A key named here is a site standard: the Standards in force section shows its value, and the Site standards plugin setting overrides it. Listing an id in disabledChecks there drops that check.
- The HCI Ceph layout skips the DLM, GFS2 and heartbeat checks, and has no Ceph checks.
- "Seen" and "measured" mean in real runs, often few of them. "Built from documentation" means the check has not met the real thing yet.

## Capacity

### CAP-01: VMs fit on the remaining hosts if the largest fails

- **What it looks at:** Adds up the memory of the running VMs in Morpheus and compares it with the host memory left without the largest host. WARN: they would not fit. Runs only with 2 or more hosts, and a memory size from Morpheus for each.
- **Why it matters:** Memory is the limit that stops a restart. If they do not fit, automatic failover could not restart every VM, and some tenants would stay down until a host returned.
- **What to do:** Decide whether that risk is acceptable. Clearing it takes fewer running VMs or more host memory. The plugin has no fix for this.

## Cluster membership

### CLU-01: Enough hosts for quorum

- **What it looks at:** Counts the hosts Morpheus has in this cluster against the site standard minHosts. A witness does not count, so this fails under the minimum even with one.
- **Why it matters:** With fewer hosts, one failure stops every GFS2 datastore.
- **What to do:** Add a host or a witness. Sources disagree on two-host GFS2, so minHosts is a judgement you can override.

### CLU-02: corosync and dlm running and enabled

- **What it looks at:** Reads systemctl for corosync and for dlm on each host. PASS: running and enabled at boot. WARN: running, but not enabled. FAIL: not running.
- **Why it matters:** A host without them is not a working cluster member. One that is running but not enabled does not come back after a reboot.
- **What to do:** WARN: press Fix (service-enable), which only enables them at boot and is safe any time. FAIL: Fix offers rejoin, on a host in maintenance mode with no VMs. It refuses if GFS2 is mounted: that host needs a reboot in a maintenance window instead. After a rejoin, reboot before leaving maintenance mode. No corosync.conf: add the host back through Morpheus. Empty authkey: see CLU-13.

### CLU-03: Host sees a quorate cluster

- **What it looks at:** Reads corosync-quorumtool -s on each host where corosync runs. FAIL: this host says it is not quorate.
- **Why it matters:** A host outside a quorate cluster is not part of a working cluster, and a fence acknowledgement cannot complete there, so a freeze cannot be cleared from it.
- **What to do:** Restore quorum first. CLU-04, CLU-05 and CLU-08 show which member is missing or cut off.

### CLU-04: Quorum margin: a host can fail without losing quorum

- **What it looks at:** Compares total votes, expected votes and the quorum from corosync-quorumtool. PASS: every expected vote is present. WARN: a vote is missing, with votes to spare above quorum. FAIL: a vote is missing and no margin is left.
- **Why it matters:** One more failure at no margin stops every datastore.
- **What to do:** A member is missing. Find out which and why before any host work.

### CLU-05: corosync nodelist matches the hosts Morpheus has in this cluster

- **What it looks at:** Compares each host's corosync.conf nodelist with Morpheus's hosts, by ring address and then by name. FAIL lists what is in corosync only and what is in Morpheus only.
- **Why it matters:** A node corosync counts but Morpheus no longer manages led to the freeze this plugin was built around. If it goes down or is rebuilt without leaving the cluster, every GFS2 datastore freezes until it is acknowledged as fenced.
- **What to do:** In corosync only: remove it from the cluster properly before touching that node. Morpheus's own remove can leave this behind when GFS2 will not unmount, and once the record has gone HVM's agent may not acknowledge it either. In Morpheus only: this host's corosync.conf does not list it, so compare with CLU-06.

### CLU-06: corosync.conf identical on every host

- **What it looks at:** Compares config_version and the node ids and addresses across every host with a corosync.conf. FAIL: they differ.
- **Why it matters:** Hosts working from different files disagree about who is in the cluster.
- **What to do:** Find the host that differs from the versions shown. The plugin has no fix for this: it never restarts corosync with GFS2 mounted.

### CLU-07: corosync authkey identical on every host

- **What it looks at:** Compares a truncated hash of /etc/corosync/authkey across hosts. The key itself never leaves the host.
- **Why it matters:** Members of one cluster share this key, so a host with another one is not properly part of it.
- **What to do:** Find the host that differs. An empty key shows here too: see CLU-13. The plugin has no fix for this.

### CLU-08: Corosync links connected

- **What it looks at:** Reads corosync-cfgtool -s on each host where corosync runs. FAIL lists the peers whose link is not connected.
- **Why it matters:** Cluster membership runs over these links, so a lost link puts a member at risk.
- **What to do:** Check the cluster network between the two hosts. NET-01, NET-03 and NET-10 look at the same path.

### CLU-09: corosync consensus timeout at least 1.2 x token

- **What it looks at:** Compares totem.consensus with totem.token in corosync.conf, where both are set. The ratio is the site standard consensusRatio. FAIL: consensus is too short.
- **Why it matters:** corosync.conf(5) sets this minimum.
- **What to do:** Correct the values in corosync.conf. The plugin has no fix for this: it never restarts corosync with GFS2 mounted.

### CLU-10: corosync token timeout matches the standard

- **What it looks at:** Compares totem.token in corosync.conf with the site standard corosyncToken. WARN: it differs. Pinned on 1.3, to what that layout deploys. Not pinned on 2.0 yet, so it runs there only if you set the key.
- **Why it matters:** Membership only changes after the token and consensus timeouts, so they set how long a lost host goes unnoticed while GFS2 stalls.
- **What to do:** Find out why it differs from what the layout deploys, or set corosyncToken if this value is what you want.

### CLU-11: corosync consensus timeout matches the standard

- **What it looks at:** Compares totem.consensus in corosync.conf with the site standard corosyncConsensus. WARN: it differs. Pinned on 1.3, to what that layout deploys. Not pinned on 2.0 yet, so it runs there only if you set the key.
- **Why it matters:** Membership only changes after the token and consensus timeouts, so they set how long a lost host goes unnoticed while GFS2 stalls.
- **What to do:** Find out why it differs from what the layout deploys, or set corosyncConsensus if this value is what you want.

### CLU-12: Quorum device connected and voting

- **What it looks at:** Where corosync.conf has a quorum device, reads corosync-qdevice-tool and corosync-quorumtool on each host. NA: no device. WARN: lost, with votes to spare. FAIL: lost with no margin, or missing from this host's corosync.conf while others have it. NOT CHECKED: nothing reported its state. Built from documentation: not yet run against a real witness.
- **Why it matters:** Losing it leaves the hosts quorate while they agree, but the tiebreak and the margin it gave are gone.
- **What to do:** Check corosync-qdevice on this host and qnetd on the witness.

### CLU-13: Corosync authkey has something in it

- **What it looks at:** Fails when /etc/corosync/authkey on a host is an empty file. There is no row when it is fine.
- **Why it matters:** Adding this host did not finish. Morpheus can show it as provisioned while corosync cannot start, leaving the others a vote short.
- **What to do:** Delete the host in Morpheus and add it again, which has worked each time. Fix does not offer rejoin on such a host.

## DLM locking

### DLM-01: Lockspaces waiting for a node to be fenced

- **What it looks at:** A lockspace on this host is stopped, and dlm_tool status shows it waiting for the named node to be fenced. All GFS2 I/O on this host is blocked.
- **Why it matters:** Every heartbeat and metadata change on GFS2 is frozen until the node is acknowledged as fenced. HVM's agent normally does this within about 2 minutes, so a wait that lasts longer will not clear by itself. If it runs on, the agent decides its datastores are dead and stops VMs: seen at about 9 minutes, in the one run that reached it, and they power off the moment I/O returns.
- **What to do:** Use the emergency panel on this tab, which needs the Cluster Health Emergency Actions permission and the node's name typed. It runs dlm_tool fence_ack. Only acknowledge once the node is certainly down: acknowledging a node that can still write corrupts the filesystem. It is never automatic. Afterwards, start any VMs the agent powered off.

### DLM-02: HVM agent is acknowledging the failed node

- **What it looks at:** After 3 minutes of a fence wait, looks in this host's journal for the agent's dlm_tool fence_ack commands in the last 15 minutes. FAIL: it acknowledged other nodes or nothing, never the failed one. Not judged on a host that ran out of time.
- **Why it matters:** The freeze will not clear by itself. In a real freeze an older agent acknowledged the other hosts and never the failed node.
- **What to do:** Usually the node has gone from Morpheus while corosync still counts it: see CLU-05. To end the freeze, follow DLM-01.

### DLM-03: DLM lockspaces running

- **What it looks at:** Reads dlm_tool ls on hosts where dlm runs. It is judged only while the host is not frozen, and frozen is the same test, so as written it reads PASS or does not appear. A stopped lockspace with a fence wait shows as DLM-01.
- **Why it matters:** While a lockspace is stopped, any access to a GFS2 mount on that host blocks.
- **What to do:** Nothing on PASS. If the Hosts section says FROZEN with no DLM-01, press Raw there and read dlm_tool ls for that host.

### DLM-04: dlm.conf enable_fencing

- **What it looks at:** Reads enable_fencing in /etc/dlm/dlm.conf on hosts where dlm runs, against the site standard fencingEnabled. FAIL: it differs, or is unset.
- **Why it matters:** GFS2 must not recover until a failed node is known to be down. With fencing off it would recover while that node may still write, which corrupts the filesystem.
- **What to do:** Make dlm.conf match. Do not turn fencing off to get past a freeze. The plugin has no fix for this.

### DLM-05: Hosts agree on lockspace members

- **What it looks at:** Compares the members of each lockspace across hosts, from dlm_tool ls. FAIL: hosts list different members.
- **Why it matters:** Hosts that disagree on who holds a lockspace are not in one settled cluster.
- **What to do:** Run the check again, as this can be a cluster still changing. If it stays, find the host that differs before any host work.

### DLM-06: dlm_controld is not fencing a departed node

- **What it looks at:** WARN: dlm_controld is still retrying a fence for a node, with every lockspace on this host running. This is not a freeze.
- **Why it matters:** GFS2 I/O is not blocked. This was seen for minutes after a host left cleanly.
- **What to do:** There is nothing to acknowledge unless a lockspace stops, which would show as DLM-01.

## Fencing

### FEN-01: Host management controller known

- **What it looks at:** Full check only. Reads the management controller's address with ipmitool. WARN: none found, which can also mean the tool is there without its driver. NOT CHECKED: ipmitool is not installed, as on Morpheus-built hosts, so the host may well have one.
- **Why it matters:** Without one there is no out-of-band way to prove a failed host is powered off.
- **What to do:** Install ipmitool to let the plugin read it. WARN: check the controller is configured.

## GFS2

### GFS-01: No withdrawn GFS2 filesystems

- **What it looks at:** Reads withdraw under /sys/fs/gfs2 for each mounted filesystem. FAIL: one has withdrawn.
- **Why it matters:** A withdrawn filesystem stays unusable on this host until it is unmounted and remounted.
- **What to do:** Move its VMs off in a maintenance window. Check storage paths first, since lost I/O is the usual cause. See GFS-10 for the log.

### GFS-02: Every GFS2 datastore mounted on this host

- **What it looks at:** Compares each host's mounted GFS2 lock tables with all those seen on any host. FAIL: this host is missing one.
- **Why it matters:** A VM on a datastore this host has not mounted cannot run here, so it counts against live migration.
- **What to do:** In maintenance mode: reboot this host before it leaves maintenance mode, which brings GFS2 back. Leaving without that has made the agent force a reboot. Otherwise find out why it is not mounted.

### GFS-03: GFS2 lock tables belong to this cluster

- **What it looks at:** Checks each mounted lock table starts with this cluster's name from corosync.conf. FAIL lists those that do not.
- **Why it matters:** It is a filesystem from another cluster, or an unresignatured snapshot clone. Two clusters mounting one GFS2 filesystem corrupts it.
- **What to do:** Find out where that filesystem came from. The plugin has no fix for this.

### GFS-04: GFS2 datastore space

- **What it looks at:** Reads df on each GFS2 mount when DLM says that is safe. WARN and FAIL at the site standards gfs2WarnPct and gfs2FailPct.
- **Why it matters:** VM disks on the datastore need room to grow.
- **What to do:** Free space or add capacity. GFS-09 shows whether the largest VM could still move there.

### GFS-05: No hung kernel tasks

- **What it looks at:** Counts "blocked for more than" reports in the kernel log over the last hour. WARN at the site standard hungTasksWarn. Tasks in D state now are shown, and do not change the status.
- **Why it matters:** Tasks stuck on I/O are how lost storage or a stopped lockspace shows up on a host.
- **What to do:** Check DLM-01 first, then storage latency and paths (STO-06, STO-01).

### GFS-06: GFS2 journals cover every host

- **What it looks at:** Full check only. Counts each filesystem's journals with gfs2_edit when DLM says that is safe, against the hosts in the cluster. WARN: too few.
- **Why it matters:** A host beyond the journal count cannot mount this filesystem.
- **What to do:** Add journals before adding hosts. The plugin has no fix for this.

### GFS-07: GFS2 answers promptly

- **What it looks at:** FAIL: df and heartbeat reads did not finish in 15 s although DLM looks healthy. There is no row when they finish.
- **Why it matters:** Slow GFS2 with healthy DLM points below it, at the storage, and VM disks wait the same way.
- **What to do:** Look at storage latency and paths next (STO-06, STO-01).

### GFS-08: GFS2 metadata responsive

- **What it looks at:** Times reading a heartbeat file's attributes, which takes a cluster lock. WARN over the site standard gfs2StatWarnMs.
- **Why it matters:** This is how long a VM's metadata access would wait.
- **What to do:** Compare with STO-06 on the same host, and with the other hosts.

### GFS-09: Room to move the largest VM onto this datastore

- **What it looks at:** Compares free space with the largest VM's disks not already there, at full size. WARN: a VM whose disks are known to be elsewhere would not fit. NOT CHECKED: Morpheus gave no sizes, or did not say where the disks are. NA: every VM is already there. Rests on Morpheus's datastore name matching the libvirt pool name, which is not proven.
- **Why it matters:** A storage migration onto this datastore would not fit at the disks' full size. A thin disk may need less.
- **What to do:** Free space there before planning that move, or choose another datastore.

### GFS-10: No GFS2 consistency errors or withdrawals

- **What it looks at:** Searches the kernel log over 7 days for GFS2 consistency errors and withdrawals. NOT CHECKED: the log could not be read. With a journal that does not persist across reboots, it loses what a reboot clears.
- **Why it matters:** A reboot clears the live state, and HVM's agent reboots a host soon after a withdrawal, so this log is the evidence that remains.
- **What to do:** Run fsck.gfs2 -n with every host unmounted when you can, and raise it with HPE. It clears 7 days after the event.

## High availability

### HA-01: Morpheus notHighAvailability flag (for information)

- **What it looks at:** Shows the raw notHighAvailability value from the cluster record. It is a note, never a finding: always NA, never an alarm.
- **Why it matters:** Its meaning is unknown. HPE does not document it, Morpheus has set it on every cluster seen so far, and flipping it changed nothing visible. It says nothing about this cluster on its own.
- **What to do:** Nothing to do. Do not read it as a verdict on high availability.

### HA-02: Host in maintenance mode is still a cluster member

- **What it looks at:** WARN on any host in maintenance mode that corosync and DLM still count. There is no row otherwise.
- **Why it matters:** Maintenance mode empties a host but does not take it out of the cluster. Powering it off now stalls every GFS2 datastore on the other hosts for about 2 minutes while HVM's agent acknowledges it, and freezes them if the agent does not.
- **What to do:** Remove it from the cluster first, with Remove safely in the Hosts section (Can I take it down?). Morpheus's own Remove carries on if GFS2 will not unmount, and those checks do not run on it.

### HA-03: "Automatically Power On VMs" is on for this cluster

- **What it looks at:** Reads the setting from the cluster record, or else from the heartbeat files, as the plugin API has no member for it: with it off the agents stop writing them, so fresh files mean on. WARN: off. NOT CHECKED: neither gave an answer. NA: fewer than 2 hosts. Whether the heartbeat reading holds on 2.0 is not proven.
- **Why it matters:** With it off, Morpheus will not power on VMs that a lost host took down, so they stay down until someone starts them.
- **What to do:** Edit the cluster and turn it on, unless that is what you want.

### HA-04: "Automatically Power On VMs" on this cluster's cloud

- **What it looks at:** Shows the cloud's setting, for information. NA: always, with its value. NOT CHECKED: Morpheus gave no value.
- **Why it matters:** The cluster's setting is the one that decides: with the cloud's off and the cluster's on, the agent restarted VMs.
- **What to do:** Nothing to do. Look at HA-03.

### HA-05: A datastore is the heartbeat target

- **What it looks at:** PASS: Morpheus flags a datastore, or hosts are writing heartbeat files, which is what matters. WARN: neither. NOT CHECKED: Morpheus flags none and GFS2 could not be read, or it listed no datastores. NA on HCI Ceph.
- **Why it matters:** Without one, Morpheus only finds a lost host at its next cluster sync, about 5 minutes by default according to HPE staff, rather than within seconds.
- **What to do:** Edit a shared datastore and make it the heartbeat target.

### HA-06: The Morpheus manager runs outside this cluster

- **What it looks at:** Looks for a VM on this cluster with one of the appliance's own addresses. WARN: it found one. No row when the addresses cannot be read. Not yet run on a real cluster with its manager on it.
- **Why it matters:** A freeze on this cluster would take down the console and every alarm, including this one. That happened in the freeze this plugin was built around.
- **What to do:** Move the manager, or watch these hosts from somewhere else as well.

## Heartbeats

### HB-01: Host heartbeat current

- **What it looks at:** Reads each host's heartbeat file on GFS2, from one host where DLM says that is safe. FAIL: older than the site standard heartbeatMaxAgeSec. WARN: no file. NA: GFS2 is frozen, the host is in maintenance mode, the cluster's power-on setting is off, or corosync started under 5 minutes ago and no file has been written since.
- **Why it matters:** HVM failover judges hosts by these files. A stale file among fresh ones points at this host.
- **What to do:** No file is expected for up to a minute after a host leaves maintenance mode. If it stays, check its agent. MOR-03 shows whether agents are checking in at all.

## Host

### HST-01: No pending reboot

- **What it looks at:** WARN: /var/run/reboot-required exists. There is no row otherwise.
- **Why it matters:** An update on this host is waiting for a reboot to take effect.
- **What to do:** Reboot in a maintenance window, one host at a time.

### HST-02: Libvirt certificates in date

- **What it looks at:** Full check only. Reads the end date of libvirt and qemu certificates. WARN and FAIL at the site standards certWarnDays and certFailDays, only with TLS in use on the host. NA: no certificates, or TLS not in use. NOT CHECKED: the date could not be read. Built from documentation: certificates in use have not been seen on a host.
- **Why it matters:** Hosts migrate over SSH by default, so a certificate nothing uses cannot break anything. With TLS in use, migrations or consoles that depend on it stop when it expires.
- **What to do:** Replace it in a maintenance window.

### HST-03: Appliance certificate in date

- **What it looks at:** Full check only. Reads the end date from a TLS handshake with the appliance's own URL. WARN and FAIL at the site standards certWarnDays and certFailDays. NOT CHECKED: no certificate could be read.
- **Why it matters:** Agents and users reach the appliance through this certificate. It is one certificate, so every cluster reports it.
- **What to do:** Renew the appliance certificate before it ends.

## Hardware

### HW-01: Power supplies healthy and redundant

- **What it looks at:** Full check only. Reads power supply sensors with ipmitool. FAIL: a supply is failing. WARN: redundancy is not full. NOT CHECKED: no sensors read, or ipmitool is not installed, as on Morpheus-built hosts.
- **Why it matters:** One power supply or feed failing takes this host down, and its cluster membership with it.
- **What to do:** Check both supplies are cabled and the BMC redundancy policy is on. NOT CHECKED: install ipmitool.

### HW-02: No memory errors

- **What it looks at:** Full check only. Reads the kernel's EDAC counters. FAIL: uncorrectable errors. WARN: correctable errors. There is no row where the host has no EDAC counters.
- **Why it matters:** Rising memory errors come before a host crash, and an unclean crash freezes every GFS2 datastore until it is acknowledged.
- **What to do:** Move VMs off and plan a replacement.

### HW-03: Temperatures within limits

- **What it looks at:** Full check only. Reads temperature sensors with ipmitool. FAIL lists any not ok. NOT CHECKED: no sensors read, or ipmitool is not installed.
- **Why it matters:** A sensor outside its limits is a warning about the host's hardware, which a cluster member depends on.
- **What to do:** Check the host's cooling and the sensors listed. NOT CHECKED: install ipmitool.

### HW-04: No serious hardware events recently

- **What it looks at:** Full check only. Reads the last 40 entries of the hardware event log with ipmitool, for serious events still asserted within the site standard selDays. WARN lists them. NOT CHECKED: ipmitool is not installed. An empty log with the tool present is an answer, and gives no row.
- **Why it matters:** A serious event can come before a host failure, and a host that fails uncleanly stalls every GFS2 datastore.
- **What to do:** Read the events listed, on the host's management controller. NOT CHECKED: install ipmitool.

### HW-05: Uplinks in a bond at the same speed

- **What it looks at:** Full check only. Compares the speeds of the NICs within each bond. WARN: they differ. There is no row otherwise. 1G for management beside 25G for storage is a design, so bonds are not compared with each other.
- **Why it matters:** A failover would change the bandwidth this host has.
- **What to do:** Check the slower link's cable, optic and switch port, or accept it if the bond was built that way.

### HW-06: Same firmware on identical servers

- **What it looks at:** Full check only. Among hosts of one product name, compares BIOS, management controller and NIC firmware. WARN lists the versions. Different models are expected to differ, so they are not compared.
- **Why it matters:** Identical servers on different firmware can behave differently, which makes a host problem harder to read.
- **What to do:** Bring them in line in your normal firmware work. The plugin has no fix for this.

### HW-07: Boot disks healthy (SMART)

- **What it looks at:** Full check only. Reads smartctl for the disks under /. FAIL: SMART health is not good, or an NVMe critical warning. WARN: pending or uncorrectable sectors, or media errors. Reallocated sectors are only shown, as vendors encode them differently. NOT CHECKED: no smartctl, or a RAID volume or virtual disk that hides SMART. Built from documentation: a physical boot disk has not been seen.
- **Why it matters:** A host that loses its boot disk leaves the cluster uncleanly, which stalls every GFS2 datastore until it is acknowledged.
- **What to do:** Replace this disk in a maintenance window. No smartctl: install smartmontools. RAID or virtual: read the physical disks through their controller.

## Layout

### LAY-01: Cluster layout recognised

- **What it looks at:** Reads the HVM version from the cluster's Morpheus layout code. PASS: 1.3 or 2.0. WARN: anything else.
- **Why it matters:** Standards are chosen by layout, so only checks that do not depend on the layout are meaningful.
- **What to do:** Read the results with that in mind. The plugin has no fix for this.

### LAY-02: Host OS matches the layout

- **What it looks at:** Compares the Ubuntu version in /etc/os-release with the site standard osVersion, which comes from the layout. FAIL: it differs.
- **Why it matters:** Each layout is built for one OS release.
- **What to do:** Find out how this host came to differ. The plugin has no fix for this.

### LAY-04: VirtSwitch network built with hvmcli

- **What it looks at:** On a VirtSwitch layout, looks for hvmcli and its netplan files. WARN: either is missing.
- **Why it matters:** This is how the 2.0 layout builds its network, so a host without them was not built the way the layout expects.
- **What to do:** Compare this host's network with another host's. The plugin has no fix for this.

### LAY-05: Open vSwitch management bridge present

- **What it looks at:** On a 1.3 layout, looks for a bridge named mgmt in Open vSwitch. WARN: not there.
- **Why it matters:** This is how the 1.3 layout builds its network, so a host without it was not built the way the layout expects.
- **What to do:** Compare this host's bridges with another host's. The plugin has no fix for this.

## Migration

### MIG-01: VMs can live-migrate between every host (CPU)

- **What it looks at:** Compares a hash of each host's CPU feature flags. PASS: the same everywhere. WARN: they differ, under a named cluster CPU model. FAIL: they differ, with no named model.
- **Why it matters:** A VM on host-passthrough sees its host's CPU, so hosts that differ cannot be relied on to take each other's VMs.
- **What to do:** Edit the cluster and set its CPU Model, HVM's equivalent of EVC, to the model the finding names, which libvirt reports usable on every host. It can be set on an existing cluster, but running VMs keep their CPU until at least their next power cycle, and reports differ on whether existing VMs pick it up at all. Test on one VM first, and test a live migration in both directions.

### MIG-02: Reaches every peer on the migration port

- **What it looks at:** Full check only. Tries TCP 22 from each host to each corosync ring address. FAIL lists those closed.
- **Why it matters:** HPE documentation states TCP 22 between hosts, and hosts migrate over SSH by default.
- **What to do:** Open TCP 22 between these hosts, on the host and on whatever is between them.

### MIG-03: Same datastores active on every host

- **What it looks at:** Compares libvirt's storage pools across hosts. FAIL: a pool is missing here, or not active.
- **Why it matters:** Without the same datastores active, a VM has nowhere to go.
- **What to do:** Find out why this host lacks that pool. See GFS-02 if its filesystem is not mounted.

### MIG-04: Every running VM fits on another host

- **What it looks at:** Compares each running VM's memory with what other hosts, not in maintenance, have available now. WARN lists VMs that fit nowhere else.
- **Why it matters:** These cannot move, so their host cannot be emptied for maintenance.
- **What to do:** Free memory elsewhere before that host's maintenance. Can I take it down? in the Hosts section shows where each VM can go.

### MIG-05: Cluster CPU model matches the standard

- **What it looks at:** Runs only with the site standard clusterCpuModel set. Compares the cluster's CPU model with it. FAIL: it differs.
- **Why it matters:** You set this key to hold the cluster to one model, the equivalent of EVC.
- **What to do:** Edit the cluster's CPU Model, or change the key. MIG-01 says what to expect of running VMs.

### MIG-06: Running VMs use the cluster CPU model

- **What it looks at:** Under a named cluster CPU model, reads each qemu process for -cpu host. WARN lists VMs still on host-passthrough. NOT CHECKED: no qemu process could be read. NA: the cluster has no named model.
- **Why it matters:** New VMs get the cluster model. An existing VM kept host-passthrough through a stop and start in Morpheus, in the runs seen so far.
- **What to do:** Expect to rebuild the VM, and until then do not live-migrate it to a host with a different CPU.

### MIG-07: VMs can live-migrate

- **What it looks at:** Reads each running qemu process for CPU settings that stop migration (migratable=off, invtsc=on). WARN lists those VMs.
- **Why it matters:** Their CPU is pinned to this host, usually by nested virtualization, and libvirt refuses to move them. Automatic placement will keep failing on them.
- **What to do:** They must be stopped to empty this host. Plan that before its maintenance.

## Morpheus's view

### MOR-01: Morpheus host status matches the host

- **What it looks at:** WARN: Morpheus's status message for a healthy cluster member says fenced, nodedown or unclean. It stays until Morpheus clears its own message, which can outlast the event by a long time.
- **Why it matters:** The message is stale. Whether it affects placement has not been established.
- **What to do:** Find out whether it affects placement before relying on it.

### MOR-02: Morpheus member status matches the host

- **What it looks at:** WARN: Morpheus has a healthy cluster member as unclean, fenced or offline. On 9.1.0.1 plugins were given an empty member status, so expect it to stay silent there.
- **Why it matters:** Morpheus and the cluster disagree about this host.
- **What to do:** Compare with the host in Morpheus. The plugin has no fix for this.

### MOR-03: Morpheus agent checking in

- **What it looks at:** Compares the agent's last check-in with the site standard agentMaxAgeSec. FAIL: older than that.
- **Why it matters:** The agent is how Morpheus, and this plugin, reach the host. All agents dead looks the same to HB-01, and this is what shows it.
- **What to do:** Check the agent on that host.

### MOR-04: Morpheus has finished removing host records

- **What it looks at:** WARN: a host record is still being removed (deprovisioning). It is not counted as a host.
- **Why it matters:** A record that stays here is stuck, and holds on to its name.
- **What to do:** Normal for a few minutes after a removal. If it stays, it is stuck: look at it in Morpheus.

### MOR-05: Morpheus reports no problem on this host

- **What it looks at:** WARN: Morpheus's status message for a healthy cluster member has error, failed or timed out in it.
- **Why it matters:** The host looks healthy from here. Morpheus can leave an old message in place after the event.
- **What to do:** Check whether the message is current.

### MOR-06: Morpheus has no failure recorded on this cluster

- **What it looks at:** WARN: the cluster record's status is failed.
- **Why it matters:** Usually the last steps of adding a host failed, as its Post-Provision process will show. Those steps did not run on that host, even if it joined.
- **What to do:** Check its votes and CLU-13, and consider deleting and adding it again.

## Network

### NET-01: Bond members up

- **What it looks at:** Reads /proc/net/bonding. FAIL lists members that are down.
- **Why it matters:** The host still works but has lost its redundancy: one more failure takes the cluster network with it.
- **What to do:** Check the cable, optic and switch port.

### NET-02: Corosync does not share an interface with iSCSI

- **What it looks at:** Compares the interface corosync's address is on with the interfaces routing to iSCSI portals. WARN: they are the same. This compares names, which can sit on one bond: NET-15 traces the physical ports.
- **Why it matters:** Storage traffic can delay corosync messages enough to get a host evicted.
- **What to do:** Separate them where you can. The plugin has no fix for this: network changes bounce the link its agent uses.

### NET-03: Ring reachability

- **What it looks at:** Full check only. Pings each corosync ring address from each host. WARN: any loss, no answer, or a round trip over the site standard ringRttWarnMs.
- **Why it matters:** Cluster membership depends on these addresses answering promptly.
- **What to do:** Look at the path between the two hosts: NET-01, NET-02 and NET-10.

### NET-04: Same MTU on the corosync interface of every host

- **What it looks at:** Compares the MTU of the interface carrying corosync. WARN: they differ. There is no row otherwise.
- **Why it matters:** Hosts that disagree on packet size can see each other and still lose large packets: see NET-09.
- **What to do:** Make them the same in a maintenance window. The plugin has no fix for this: changing MTU bounces the link its agent uses.

### NET-05: Switch neighbour on every uplink

- **What it looks at:** Reads LLDP, or CDP where lldpd listens for it, per bond member that is up. WARN: nothing heard on an uplink. NA: lldpd is not running.
- **Why it matters:** Without it the plugin cannot see which switch each uplink lands on. It proves cabling, not VLANs: switches commonly advertise only the native VLAN.
- **What to do:** Check that the switch sends LLDP on that port. NA: run lldpd on the host if you want this and NET-06 to NET-08 answered.

### NET-06: Uplinks on different switches

- **What it looks at:** From LLDP, compares the switches heard on a bond's uplinks, with two or more up. WARN: all on one switch.
- **Why it matters:** One switch failing or rebooting takes every uplink of this host, and its cluster membership with it.
- **What to do:** Put an uplink on a second switch.

### NET-07: Same native VLAN on every uplink

- **What it looks at:** From LLDP, compares the native VLAN on each uplink of a bond. WARN: they differ.
- **Why it matters:** A failover to the other uplink would change the VLAN of untagged traffic.
- **What to do:** Correct the switch ports.

### NET-08: Same native VLAN on every host

- **What it looks at:** From LLDP, compares native VLANs across hosts. WARN: they differ.
- **Why it matters:** Untagged traffic would land on a different VLAN depending on the host.
- **What to do:** Correct the switch ports, or accept it where hosts are built differently on purpose.

### NET-09: Full-size packets reach every peer

- **What it looks at:** Full check only. Pings each corosync ring address unfragmented at the interface's MTU. FAIL lists those dropped.
- **Why it matters:** Small traffic works and large traffic vanishes: migrations hang partway with no clear error.
- **What to do:** A switch or VLAN on this path has a lower MTU than the host. Find it and correct it.

### NET-10: Netmasks match their counterparts on other hosts

- **What it looks at:** Groups every host's addresses by overlapping subnet and by interface name, and compares netmasks. FAIL lists those that differ.
- **Why it matters:** The host with the narrower mask treats peers outside its range as off-subnet and sends them to the gateway, or nowhere: management, migration or iSCSI to those peers fails while everything else works.
- **What to do:** Correct it in the host's network configuration, in a maintenance window.

### NET-11: VLAN carries traffic on every host

- **What it looks at:** Passive: whether each host's uplink has learned addresses on each VLAN Morpheus has for the cluster. PASS: heard on every host. NOT CHECKED: heard on some. A quiet VLAN looks the same, so this is a hint and raises no alarm. No row for a VLAN no host hears.
- **Why it matters:** The trunk to the silent host may not pass this VLAN, so VMs moved there would lose their network.
- **What to do:** Put the host in maintenance mode and press Test VLANs in the Hosts section, which is what settles it.

### NET-12: VM ports on the VLANs Morpheus says

- **What it looks at:** Full check only. Reads each VM port's VLAN from the bridge and compares it with its Morpheus network, joined on name and MAC. WARN: it differs. FAIL: it differs, and every network on that VLAN belongs to another customer tenant. NOT CHECKED: nothing could be compared. So far only a blank-VLAN port and a tagged one have been seen, on nested hosts, not a real wrong-VLAN port.
- **Why it matters:** FAIL: this VM can reach another tenant's network. WARN: the VM is not on the network its owner thinks.
- **What to do:** FAIL: confirm on the host with its bridge's VLAN list and the VM's definition, then take the port off that VLAN, and find out how it got there. WARN: find out which is right. It clears by itself if its network was being changed just now, at the next full check.

### NET-13: No unexpected trunk

- **What it looks at:** Full check only. Reads each VM port from the bridge. WARN: a trunk not listed in the site standard trunkAllowedVms, or a customer VM on an untagged Open vSwitch port. FAIL: the same, reaching a VLAN that belongs to another customer tenant. NOT CHECKED: an untagged port on a VM not known to be a customer's, or ports that could not be read.
- **Why it matters:** A guest on a trunk can reach other networks by tagging its traffic. Measured on 1.3: a guest on a port with no tag reached a guest on a tagged VLAN. That port is what a network with a blank VLAN or VLAN 0 builds on 1.3. That was on nested hosts, and it has not been seen on a real trunk uplink.
- **What to do:** If this VM should have a trunk, such as a firewall, add it to trunkAllowedVms. Untagged customer port: move the VM to a network with a VLAN. FAIL: confirm on the host, then take those VLANs off the port.

### NET-14: Switch takes this uplink's frames

- **What it looks at:** Compares the maximum frame size the switch advertises over LLDP with the host's MTU. WARN: smaller. No row if the switch advertises nothing. Vendors differ on counting headers, so equal may or may not fit. Built from documentation: an advertised size has not been seen on a host.
- **Why it matters:** A hint, from what the switch says of itself: large frames may be dropped here.
- **What to do:** NET-09 and a VLAN test measure the real path, so go by those.

### NET-15: iSCSI has physical ports of its own

- **What it looks at:** Traces the cluster network and iSCSI down through VLANs, bonds and bridges to physical ports. WARN: a port carries both. NOT CHECKED: the ports could not be traced.
- **Why it matters:** A burst of storage traffic can delay corosync on the shared ports, and losing them takes both away at once.
- **What to do:** Separate them. Where this is deliberate, as in a test environment, list NET-15 in disabledChecks in the site standards.

### NET-16: Cluster network address is static

- **What it looks at:** Reads whether corosync's address on this host came from DHCP. WARN: it did.
- **Why it matters:** A lost or changed lease takes this host out of the cluster, because corosync.conf still lists the old address, and another machine can then be given it. Seen after a NIC rename changed the DHCP client id.
- **What to do:** Make it static or reserve it.

## Packages

### PKG-01: Same packages and Morpheus agent on every host

- **What it looks at:** Compares corosync, dlm-controld, gfs2-utils, libvirt-daemon-system, qemu-system-x86 and the Morpheus agent across hosts. WARN: versions differ. NOT CHECKED: no agent version was found on a host.
- **Why it matters:** Hosts on different versions may not behave the same in a failure. Agent versions did not: see PKG-03.
- **What to do:** Bring them to one version in your normal patching, one host at a time. The plugin has no fix for this.

### PKG-02: Same kernel on every host

- **What it looks at:** Compares uname -r across hosts. WARN: they differ. There is no row otherwise.
- **Why it matters:** GFS2 and DLM are in the kernel, so hosts on different kernels are not running the same code.
- **What to do:** Bring them in line in your normal patching. HST-01 shows hosts waiting on a reboot.

### PKG-03: Morpheus agent is at least the version that handled a freeze correctly

- **What it looks at:** Compares each host's agent version with the site standard agentMinVersion. WARN lists those older.
- **Why it matters:** An older agent (3.2.6) acknowledged the wrong nodes in a real freeze, so GFS2 stayed frozen until someone acknowledged the right node by hand. Newer agents acknowledged correctly in every test. Whether that is a fix is HPE's to confirm.
- **What to do:** Ask HPE how to update it, and until then watch dlm_tool on the other hosts during any host removal.

## The plugin itself

### PRB-01: Host probe completed

- **What it looks at:** FAIL: a host gave no complete answer to the read-only probe. Either it ran out of time, and only its DLM state is used, or nothing came back.
- **Why it matters:** Checks use only the hosts that finished, so findings for this host may be missing, and it counts as not answering for fixes.
- **What to do:** Ran out of time: something on this host is answering slowly, such as libvirt, the journal or multipath. No answer: the host may be down, or its agent stopped.

### PRB-02: Automatic check ran

- **What it looks at:** An alarm only: it never appears in the tab's tables. Raised when an automatic check of this cluster could not run at all, with the error as its text.
- **Why it matters:** Nothing else would show that monitoring has stopped. What the plugin last found stays as it was, and a new freeze is not found while this lasts.
- **What to do:** Read the error in the alarm, and the appliance's log. It tries again at the normal interval, and the next check that runs clears the alarm: Run full check tries now.

### PRB-03: Site standards are in force

- **What it looks at:** WARN: the Site standards plugin setting could not be read, because of a JSON error, an unknown key or a wrong type. The finding gives the error.
- **Why it matters:** Checks are running on the built-in standards until this is fixed, so none of your overrides or disabled checks apply.
- **What to do:** Correct it in the plugin settings.

## Storage

### STO-01: Multipath paths healthy

- **What it looks at:** Reads multipath -ll per LUN, while the site standard mpio is on. FAIL: a path is not active and ready, or the map has no paths at all. WARN: only one path.
- **Why it matters:** A map with no paths has lost its storage, and one with a single path has no redundancy left. Lost I/O is the usual cause of a GFS2 withdrawal.
- **What to do:** FAIL: Fix offers multipath-reconfigure (iscsiadm -m session --rescan, then multipathd reconfigure), on a host in maintenance mode with no VMs. Also check the array and the storage network.

### STO-02: Map does not queue I/O forever

- **What it looks at:** Reads no_path_retry from multipathd, while the site standard mpio is on. WARN: "on", which queues for ever. PASS: off, or a finite retry. NOT CHECKED: dm shows queueing and multipathd gave no limit. dm's features line reads the same for a finite retry, so it cannot answer this alone.
- **Why it matters:** Queueing with no paths hangs GFS2 instead of failing the I/O.
- **What to do:** Set no_path_retry in the multipath configuration, in a maintenance window. NOT CHECKED: run multipathd show maps format "%n %Q" on the host.

### STO-03: No iSCSI session errors

- **What it looks at:** Counts iSCSI ping timeouts and offline-device errors in the kernel log over 24 h. WARN at the site standard iscsiErrorsWarn.
- **Why it matters:** These are I/O paths failing, even if multipath has recovered since.
- **What to do:** Look at STO-01 and STO-06, then the storage network on this host.

### STO-04: iSCSI multipath daemons running

- **What it looks at:** On a host with iSCSI sessions and multipath maps, reads systemctl for multipathd and iscsid. FAIL: either is not active.
- **Why it matters:** These daemons look after the paths under every GFS2 datastore on this host.
- **What to do:** Find out why it stopped. The plugin has no fix for this.

### STO-05: Same path count on every host

- **What it looks at:** Compares the number of paths per LUN across hosts. WARN: they differ. There is no row otherwise.
- **Why it matters:** A difference means the hosts do not see this LUN the same way, and one with fewer paths has less redundancy.
- **What to do:** Compare iSCSI sessions and multipath on the host that differs.

### STO-06: Storage latency

- **What it looks at:** Measures average I/O wait per LUN over 2 s on each host. WARN and FAIL at the site standards storageWarnMs and storageFailMs. Skipped below 20 I/Os in the sample, where one slow I/O would fail it.
- **Why it matters:** VM disks wait this long on every I/O.
- **What to do:** Check the other hosts. If they are slow too, look at the array; if only this one is, its paths and NICs.

## Time

### TIM-01: Clock synchronised

- **What it looks at:** Reads NTP and NTPSynchronized from timedatectl. FAIL: not synchronised. WARN: synchronised for now, with NTP off: the kernel keeps saying so long afterwards. NA: no answer.
- **Why it matters:** With NTP off nothing is correcting this clock, so it will drift. See TIM-02 for what drift does.
- **What to do:** NTP off: Fix offers ntp-enable, on a host in maintenance mode with no VMs, as starting it can step a clock under running VMs. NTP on and not synchronised: check that the time servers are reachable.

### TIM-02: Host clocks agree

- **What it looks at:** Compares clocks through the offsets hosts measure to time servers they share, matched by address, against the site standard maxHostDriftMs. Over it: FAIL, or WARN from systemd-timesyncd's single samples. Hosts sharing no server get a coarse comparison in seconds against maxClockSkewSec, at most WARN. Hosts not compared are named, and that shows neither drift nor agreement.
- **Why it matters:** Live migration moves a running clock between hosts, and corosync and GFS2 logs stop lining up.
- **What to do:** Find the host that differs in the Time section. On systemd-timesyncd, see whether it repeats on the next check.

### TIM-03: In sync with a time source

- **What it looks at:** Reads chrony or systemd-timesyncd against the site standard maxNtpOffsetMs. Chrony: FAIL when over it, with no source selected, or an abnormal leap status. Timesyncd: WARN when over it, as its one sample is up to half an hour old and already corrected. FAIL only if no server has answered or the leap status is abnormal. NOT CHECKED: it reported nothing to judge.
- **Why it matters:** Live migration and cluster timers both suffer from drift.
- **What to do:** Look at the Time section. Check this host can reach its source. On systemd-timesyncd, see whether a warning repeats on the next check.

### TIM-04: Host uses the cluster time source

- **What it looks at:** Compares each host's time servers with the site standard ntpServers, which the Time section or the layout settings can set for one cluster. FAIL: not configured. WARN: configured, but following another server. NA: none defined for this cluster.
- **Why it matters:** A host on another source is not verified against the one you run, and hosts on different sources drift apart.
- **What to do:** FAIL: Fix offers ntp-sources, on a host in maintenance mode with no VMs: a drop-in file, marked prefer on chrony, undone on systemd-timesyncd if no listed server answers. WARN: check this host can reach the cluster source.

### TIM-05: Every host configured with the same time sources

- **What it looks at:** Compares the configured time servers across hosts. WARN: they differ. There is no row otherwise.
- **Why it matters:** Hosts following different sources drift apart, even with each one "in sync".
- **What to do:** Define the cluster's time source in the Time section, then use TIM-04's fix on the hosts that differ.

### TIM-06: A time source is defined for this cluster

- **What it looks at:** Whether the site standard ntpServers has a value, from the Time section, the site standards or the layout settings. WARN: none.
- **Why it matters:** With one defined every host is verified against it, and hosts on a segment that loses internet are not left drifting together unnoticed.
- **What to do:** Define it in the Time section. That changes nothing on the hosts until you run their fix.

## VMs

### VM-01: No VM running on two hosts

- **What it looks at:** Looks for one libvirt domain name running on more than one host. FAIL lists them.
- **Why it matters:** Two copies writing one disk corrupt it.
- **What to do:** Stop one copy immediately. Decide which by what its guest was serving.

### VM-02: No VM stuck on its disk

- **What it looks at:** FAIL: a qemu process on this host is in uninterruptible sleep (D state). There is no row otherwise.
- **Why it matters:** These VMs cannot answer on the network while they are stuck.
- **What to do:** On a frozen host, this comes from the frozen lockspace: follow DLM-01. Otherwise look at storage paths and latency (STO-01, STO-06).

### VM-03: HVM agent has VM stops queued during a freeze

- **What it looks at:** On a frozen host, counts the agent's virsh shutdown, destroy and kill commands in the journal over 15 minutes. FAIL: there are some.
- **Why it matters:** They take effect the moment I/O returns. In the freeze this plugin was built around, the VMs died when it ended.
- **What to do:** Expect these VMs to power off when the freeze clears, then start them.

### VM-04: Host in maintenance mode has no running VMs

- **What it looks at:** Counts running VMs in libvirt on a host in maintenance mode. WARN: there are some.
- **Why it matters:** Maintenance mode is meant to have emptied this host, and fixes and removal refuse while VMs run on it.
- **What to do:** Move or stop them before working on the host.

### VM-05: Morpheus and libvirt agree on VM power

- **What it looks at:** Compares Morpheus's power state with libvirt on the host Morpheus has the VM on. WARN: they differ. Only for hosts that answered.
- **Why it matters:** What Morpheus shows for this VM is not what is running.
- **What to do:** Morpheus says off and libvirt says running: do not press Start on it in Morpheus until this clears, as it is already running. Otherwise find out which is right.
