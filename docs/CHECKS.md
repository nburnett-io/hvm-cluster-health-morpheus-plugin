# Check reference

What every check id means. The tab shows the same text: click an id, or press Help. Generated from `src/main/groovy/org/hvmhealth/checks/CheckCatalog.groovy`, so edit that, not this file: `CheckCatalogSpec` fails if this one falls behind, and prints the command that regenerates it.

## Statuses

- **FAIL**: Wrong now. Raises an error alarm in Morpheus, or is named in the alarm of what explains it, such as a freeze.
- **WARN**: Wrong or at risk, but not a failure. Raises a warning alarm in Morpheus, or is named in the alarm of what explains it.
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
| FLT | Across clusters |
| GFS | GFS2 |
| HA | High availability |
| HB | Heartbeats |
| HPE | HPE's own checks |
| HST | Host |
| HW | Hardware |
| KRN | Kernel |
| LAY | Layout |
| MIG | Migration |
| MOR | Morpheus's view |
| NET | Network |
| PKG | Packages |
| PRB | The plugin itself |
| SEC | Security and tenant isolation |
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

### CAP-02: vCPUs against host threads

- **What it looks at:** Adds up the vCPUs of the running VMs in Morpheus and divides by the threads the hosts report. WARN and FAIL at the site standards vcpuWarnRatio and vcpuFailRatio. NOT CHECKED: a host did not answer, a running VM has no vCPU count in Morpheus, or Morpheus lists no running VMs while the hosts report some. Hosts in maintenance count. It measures what is allotted, not how busy the hosts are. That Morpheus fills in the vCPU count for every VM has not been seen across many clusters.
- **Why it matters:** Past some point VMs wait for CPU whenever they are busy at once, and a failed host makes that worse. Where that point lies depends on the workloads, so the defaults are a judgement to override.
- **What to do:** Look at how busy the hosts really are, then decide. Clearing it takes fewer vCPUs or more hosts. The plugin has no fix for this.

### CAP-03: N+1 memory lasts

- **What it looks at:** A straight line through one sample a day of the running VMs' memory, for up to 90 days, against what the hosts have now without the largest. WARN: it reaches that within the site standard forecastWarnDays. NOT CHECKED: fewer than 14 daily samples, none in the last 7 days, or the samples could not be read. A sample is only taken when every host answered and none is frozen. Not yet watched for 14 days on a real cluster.
- **Why it matters:** CAP-01 says whether a failed host's VMs would fit today. This says when they stop fitting, while there is still time to buy memory.
- **What to do:** Plan host memory or fewer running VMs. A step, such as a new tenant moving in, moves the line for as long as that step is within the 90 days, so read it next to what you know changed.

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

- **What it looks at:** Reads corosync-quorumtool -s on each host where corosync runs. On a two-node corosync cluster on layout 1.3 or 2.0 it is informational (NA): HPE states corosync Quorate does not decide there, and Quorate: No is expected. On three or more corosync nodes it keeps the corosync reading, FAIL when this host says it is not quorate. On other layouts it reads corosync as before. From HPE docs: the agent quorum (CLU-14) is the authority on 1.3 and 2.0. Stretch clusters, where Quorate: No is also expected, are not told apart from single-site here, so a healthy stretch cluster of three or more may still read FAIL; the re-aim has not been confirmed against a real two-node or stretch cluster.
- **Why it matters:** HPE says not to use corosync Quorate as the HVM health decision on 1.3 and 2.0; the agent quorum decides. A host outside a quorate corosync cluster of three or more is still worth knowing about, and CLU-03 corroborates CLU-14 there.
- **What to do:** On 1.3 and 2.0 read CLU-14 first. Where CLU-03 still fails, restore quorum: CLU-04, CLU-05, CLU-08 and CLU-15 show which member is missing or cut off.

### CLU-04: Quorum margin: a host can fail without losing quorum

- **What it looks at:** Compares total votes, expected votes and the quorum from corosync-quorumtool. On a two-node corosync cluster on layout 1.3 or 2.0 it is informational (NA): a missing vote margin is by design there, and the agent quorum and witness decide. Otherwise PASS when every expected vote is present, WARN when a vote is missing with votes to spare, and FAIL when a vote is missing with no margin left. From HPE docs for the 1.3 and 2.0 re-aim; not yet confirmed against a real two-node cluster.
- **Why it matters:** One more failure at no margin stops every datastore. On 1.3 and 2.0 the agent quorum (CLU-14) is the authority and corosync votes only corroborate.
- **What to do:** A member is missing. Find out which and why before any host work. On a two-node cluster see CLU-12 for the witness and CLU-14 for the agent quorum.

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

- **What it looks at:** Where corosync.conf actually has a corosync quorum device (qdevice), reads corosync-qdevice-tool and corosync-quorumtool on each host. WARN: lost, with votes to spare. FAIL: lost with no margin, or missing from this host's corosync.conf while others have it. NOT CHECKED: nothing reported its state. NA: no corosync qdevice. HVM's own two-node witness is not a corosync qdevice but a Distributed Worker reached at <Worker>/witness/<cluster UUID>/quorum over HTTPS, a quorum-only member of the agent quorum (CLU-14), so on a normal HVM cluster this check is NA and does not cover the witness. Built from documentation: not yet run against a real corosync qdevice.
- **Why it matters:** Where a real corosync qdevice exists, losing it leaves the hosts quorate while they agree, but the tiebreak and the margin it gave are gone. HVM clusters normally have no corosync qdevice.
- **What to do:** Only judged where a corosync qdevice is configured: check corosync-qdevice on this host and qnetd on the witness. For HVM's own two-node witness, read CLU-14 (the agent quorum) instead.

### CLU-13: Corosync authkey has something in it

- **What it looks at:** Fails when /etc/corosync/authkey on a host is an empty file. There is no row when it is fine.
- **Why it matters:** Adding this host did not finish. Morpheus can show it as provisioned while corosync cannot start, leaving the others a vote short.
- **What to do:** Delete the host in Morpheus and add it again, which has worked each time. Fix does not offer rejoin on such a host.

### CLU-14: Agent quorum achieved

- **What it looks at:** Reads HPE's agent quorum endpoint https://127.0.0.1:7443/quorum on each host, on the host over HTTPS to localhost and never a GFS2 path, so safe. FAIL: quorumAchieved false or this host fenced. WARN: waiting to fence a node, or fewer nodes reachable than the agent expects. PASS otherwise. NOT CHECKED: the endpoint gave nothing to read or not the documented JSON (the shape is only partly documented, so any surprise is not checked, never a pass). Nothing is changed on a host: there is no fix. Built from HPE docs: not yet confirmed against a real two-host or stretch cluster, and whether the endpoint needs authentication on some agent versions is unknown.
- **Why it matters:** On layouts 1.3 and 2.0 HPE states the agent quorum, not corosync, decides quorum, failure detection and fencing. Its loss leads a host to self-fence, stopping DLM and corosync. This is the authority that CLU-03 only corroborates.
- **What to do:** Read this before CLU-03. On a loss, DLM-01 and DLM-02 show a fence wait and its timing, and CLU-15 shows a member the agent has dropped. HPE's own Quorum panel shows the same state.

### CLU-15: Agent, corosync and Morpheus list the same members

- **What it looks at:** Compares the members the agent lists (its /quorum nodes and /opt/morpheus-node/.quorum-nodes) with the corosync nodelist and with Morpheus's hosts, per host. FAIL: a node the corosync nodelist counts is not in the agent's quorum-node list. PASS: every corosync node is in it. NOT CHECKED: the agent gave no member data, or its identifiers matched nothing known, so its format could not be read (the file format is undocumented, so a surprise is not checked, never a pass). Read only: HPE says not to hand-edit corosync or agent quorum files, so there is no fix. Built from HPE docs and not yet confirmed against a real freeze; an agent that listed some members by an identifier this plugin cannot read could in principle flag a present member.
- **Why it matters:** A node corosync counts but the agent does not is the precondition suspected behind the production freeze: if that node fails, DLM waits for a fence the agent will never issue, and every GFS2 datastore freezes until it is acknowledged. It is the agent-side counterpart of CLU-05.
- **What to do:** Compare with CLU-05. Remove the node through Morpheus, or add it back cleanly; do not hand-edit the files. Read CLU-14 for the agent's own quorum state.

### CLU-16: No host rejoined corosync with a stale config_version

- **What it looks at:** Compares the corosync.conf config_version across every host, and WARN names any host below the newest. Sharpens CLU-06, which fails on any file difference. Built from corosync source and a Proxmox reproduction, not seen on HVM.
- **Why it matters:** Morpheus increments config_version on every host add or remove and reloads only the hosts that were up, so a host down during such a change returns with an older version. On the reproduction such a node rejoined and left a ghost CPG member that no membership change removed, and the cluster filesystem hung on every node until corosync was restarted everywhere.
- **What to do:** Do not start or restart the named host until its corosync.conf matches the others, which CLU-06 also flags. The plugin never restarts corosync. The backport is HPE's and Canonical's to ship: see docs/HPE-QUESTIONS.md.

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

- **What it looks at:** Reads dlm_tool ls, and lock_module/block, on hosts where dlm runs. FAIL: a lockspace is stopped (kern_stop or a wait state) that no fence wait, inferred wait or -125 split state already explains, and the hint names the wait state from dlm_tool ls (ringid, quorum, messages, fsdone). WARN: dlm_tool ls shows nothing stopped but lock_module/block is 1 (DFL_BLOCK_LOCKS), which normal recovery also sets for a moment, so confirm it persists. A stopped lockspace with a fence wait shows as DLM-01, and the -125 split state as DLM-07.
- **Why it matters:** While a lockspace is stopped, any access to a GFS2 mount on that host blocks. lock_module/block set with no fence line can be a stop that dlm_tool ls does not show. The block signal is read from kernel source and has not been seen set on a host here.
- **What to do:** Nothing on PASS. On a WARN, re-check that block is still set and read the kernel log. If the Hosts section says FROZEN with no DLM-01 or DLM-07, press Raw there and read dlm_tool ls for that host.

### DLM-04: dlm.conf enable_fencing

- **What it looks at:** Reads enable_fencing in /etc/dlm/dlm.conf on hosts where dlm runs, against the site standard fencingEnabled. FAIL: it differs, or is unset.
- **Why it matters:** GFS2 must not recover until a failed node is known to be down. With fencing off it would recover while that node may still write, which corrupts the filesystem.
- **What to do:** Make dlm.conf match. Do not turn fencing off to get past a freeze. The plugin has no fix for this.

### DLM-05: Hosts agree on lockspace members

- **What it looks at:** Compares the members of each lockspace across hosts, from dlm_tool ls. FAIL: hosts list different members.
- **Why it matters:** Hosts that disagree on who holds a lockspace are not in one settled cluster.
- **What to do:** Run the check again, as this can be a cluster still changing. If it stays, find the host that differs before any host work.

### DLM-06: dlm_controld is not fencing a departed node

- **What it looks at:** WARN: dlm_controld is still retrying a fence for a node, with every lockspace on this host running. No GFS2 I/O is blocked now.
- **Why it matters:** It is not harmless: this is a latent freeze. While the retry stands it blocks the next membership change on that lockspace, so the next mount, unmount, new datastore, or a host joining or leaving will freeze it. A fence result of 177 means no fence agent is loaded, so no fence was ever attempted, not that one failed. Seen for minutes after a host left cleanly.
- **What to do:** Do no host work on this cluster until it clears. There is nothing to acknowledge unless a lockspace stops, which would show as DLM-01.

### DLM-07: Lockspace stuck in the -125 split state

- **What it looks at:** FAIL: a stopped lockspace is waiting to fence a node that dlm_tool status records as departed (a non-member whose fence time never caught up to its failure) while dlm_controld is not fencing it. All GFS2 I/O on this host is blocked. Read from dlm 4.2.0 source and seen once in production for 27 minutes, not reproduced; telling it apart from an ordinary no-fence-line wait (DLM-01) rests on dlm_tool status records that have not been checked live, and 2.0 (dlm 4.3.0) is unconfirmed.
- **Why it matters:** This is the freeze a single hand-run acknowledgement cannot clear: dlm_tool fence_ack is refused with -125 (no need_fencing), because the daemon has no fence pending for this node while the lockspace still waits on it. That fence_ack cannot clear it was established from source; what triggers it, and how 2.0 behaves, were not.
- **What to do:** Do not acknowledge in a loop, and never automatically. The way out is a clean rejoin of the node: reboot it and start corosync and dlm with GFS2 left unmounted, and when its protocol is accepted the wait clears with no acknowledgement. Involve HPE. There is no Fix button for this.

## Fencing

### FEN-01: Host management controller known

- **What it looks at:** Full check only. Reads the management controller's address with ipmitool. WARN: none found, which can also mean the tool is there without its driver. NOT CHECKED: ipmitool is not installed, as on Morpheus-built 1.3 hosts (2.0 hosts include it), so the host may well have one.
- **Why it matters:** Without one there is no out-of-band way to prove a failed host is powered off.
- **What to do:** NOT CHECKED: the Fix button installs ipmitool and loads its driver (never automatic). WARN: check the controller is configured. Each host's Management controller panel shows its status, inventory and network.

### FEN-02: No SCSI fence agent from the 8.0.11/8.0.12 defect

- **What it looks at:** Full check only. WARN when the SCSI fence agent /usr/sbin/fence_scsi_hpevm is present on a 1.3 or 2.0 host. No row when it is absent, or on HCI Ceph. Built from documentation (advisory read through search extracts), not seen on a host.
- **Why it matters:** HPE advisory a00156214: a cluster created on Manager 8.0.11 or 8.0.12 used Pacemaker and this agent, and one host failure then powers off every surviving VM, a defect that follows the cluster even after an upgrade. Layout 1.3 and 2.0 do their own fencing and should not carry the script, so its presence suggests the cluster began in that era.
- **What to do:** The plugin cannot read when a cluster was created, so confirm the creation version with HPE and apply hotfix 8.0.12-2 or a layout upgrade through HPE. Never hand-edit the agent script as a fix. See docs/HPE-QUESTIONS.md.

## Across clusters

### FLT-01: Possible shared cause

- **What it looks at:** The same check appearing on two or more clusters within 10 minutes when they share an iSCSI portal (for storage, GFS2, DLM and heartbeat checks), a time server (for time checks) or a group in the Shared risk groups setting (for any check), or on three or more clusters whatever they share. Only a finding's arrival counts, not one that has been open for months. A cluster in a change window does not count, so rolling work does not raise this, and neither does what first appeared during its window. The exception is a confirmed isolation failure (NET-12, NET-13 at FAIL), as no window holds those. Shown on the all-clusters page and as one alarm on the master tenant, never on a cluster's tab, as it names other clusters. It stays while the clusters still have the finding. Held in memory, so a reload ends it, and the findings keep their own alarms.
- **Why it matters:** Three separate problems within minutes are more often one problem underneath: a shared array, switch or time server.
- **What to do:** Look at what the clusters share before working on each one. It is only a possibility: nothing here proves a shared cause. Add what the plugin cannot see, such as a switch or a rack, to the Shared risk groups setting.

## GFS2

### GFS-01: No withdrawn GFS2 filesystems

- **What it looks at:** Reads withdraw under /sys/fs/gfs2 for each mounted filesystem. FAIL: one has withdrawn.
- **Why it matters:** A withdrawn filesystem stays unusable on this host until it is unmounted and remounted.
- **What to do:** Move its VMs off in a maintenance window. Check storage paths first, since lost I/O is the usual cause. See GFS-10 for the log.

### GFS-02: Every GFS2 datastore mounted on this host

- **What it looks at:** Compares each host's mounted GFS2 lock tables with all those seen on any host. FAIL: this host is missing one.
- **Why it matters:** A VM on a datastore this host has not mounted cannot run here, so it counts against live migration. On 2.0 (kernel 7.0, dlm 4.3.0), source reading suggests a withdrawn node's journal may not be replayed until that node remounts, so a datastore unmounted here could leave its writes unrecovered on the survivors: unproven, in the backlog, and no check is built on it.
- **What to do:** In maintenance mode: reboot this host before it leaves maintenance mode, which brings GFS2 back. Leaving without that has made the agent force a reboot. Otherwise find out why it is not mounted.

### GFS-03: GFS2 lock tables belong to this cluster

- **What it looks at:** Checks each mounted lock table starts with this cluster's name from corosync.conf. FAIL lists those that do not.
- **Why it matters:** It is a filesystem from another cluster, or an unresignatured snapshot clone. Two clusters mounting one GFS2 filesystem corrupts it.
- **What to do:** Find out where that filesystem came from. The plugin has no fix for this.

### GFS-04: GFS2 datastore space

- **What it looks at:** Reads df on each GFS2 mount when DLM says that is safe. WARN and FAIL at the site standards gfs2WarnPct and gfs2FailPct.
- **Why it matters:** VM disks on the datastore need room to grow.
- **What to do:** Free space or add capacity. GFS-09 shows whether the largest VM could still move there.

### GFS-11: Datastore space forecast

- **What it looks at:** A straight line through one sample a day of each datastore's used space, for up to 90 days, against the site standard gfs2FailPct of its size now. WARN: it gets there within the site standard forecastWarnDays. NOT CHECKED: fewer than 14 daily samples, none in the last 7 days with the datastore still mounted, or the samples could not be read. Read only when DLM says that is safe, so a frozen day has no sample rather than a low one. Not yet watched for 14 days on a real cluster.
- **Why it matters:** GFS-04 describes today. This says when it will need capacity, while there is still time to add it. GFS2 free space does not show how full a thin LUN's array is: see STO-07.
- **What to do:** Free space or add capacity. A big deletion or a resize moves the line for as long as it is within the 90 days, so read it next to what you know changed.

### GFS-05: No hung kernel tasks

- **What it looks at:** Counts "blocked for more than" reports in the kernel log over the last hour. WARN at the site standard hungTasksWarn. Tasks in D state now are shown, and do not change the status.
- **Why it matters:** Tasks stuck on I/O are how lost storage or a stopped lockspace shows up on a host.
- **What to do:** Check DLM-01 first, then storage latency and paths (STO-06, STO-01).

### GFS-06: GFS2 journals cover every host

- **What it looks at:** Full check only. Counts each filesystem's journals with gfs2_edit when DLM says that is safe, against the hosts in the cluster. WARN: too few.
- **Why it matters:** A host beyond the journal count cannot mount this filesystem. Separately, on 2.0 (kernel 7.0, dlm 4.3.0) source reading suggests a withdrawn node's journal may not replay until it remounts, which would leave its writes unrecovered on the survivors meanwhile: unproven, in the backlog, and no check is built on it.
- **What to do:** Morpheus runs gfs2_jadd itself when it adds a host, so too few journals usually means that add-host step did not finish, rather than something to add by hand. Older datastores may carry fewer than the 32 a current mkfs makes. The plugin has no fix for this.

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

- **What it looks at:** Searches the kernel log over 7 days, case-insensitively, for GFS2 consistency errors and withdrawals: the fsid line with consistency error, withdrawn, about to withdraw, a fatal: message or an lvb mismatch. The exact wordings are read from GFS2 source and not yet seen against a real log, so a withdrawal could still be worded in a way this misses. The -22 conversion defect is counted on its own (KRN-05), not here. NOT CHECKED: the log could not be read. With a journal that does not persist across reboots, it loses what a reboot clears.
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

### HB-02: No all-paths-down VM shutdown recorded

- **What it looks at:** Full check only. Searches the host journal over 7 days for the agent's all-paths-down shutdown: the line "Shutting down all VMs to protect data integrity" and the precursor "heartbeat datastore paths ... unhealthy ... for N checks". FAIL on any sighting. NOT CHECKED: the journal could not be read in time. Searches the whole journal, as the agent's unit name is not known here, and the exact wording is unverified against a host.
- **Why it matters:** The agent counts failed heartbeat writes and, after about 6 cycles (roughly 2 minutes), destroys every non-pinned VM on shared storage to guard against split-brain. Recovery is manual, and HPE says to alert on any occurrence. This is the biggest outage the plugin did not detect, and after the fact the journal is the evidence.
- **What to do:** Find what lost the heartbeat datastore then (STO-01 paths, STO-06 latency, a withdrawal in GFS-10), start the VMs that were stopped, and keep the evidence for HPE. The destroy timer under path loss, and the exact agent wording, are unverified: see docs/HPE-QUESTIONS.md. It clears 7 days after the last line.

## HPE's own checks

### HPE-01: HPE health check (hvmcli)

- **What it looks at:** Full check only. On 2.0 hosts, runs hvmcli health check --json as a time-limited background read, and shows every answer in the HPE checks panel under Hosts. WARN: a check not named in the site standard hvmcliIgnore says WARN or FAIL; this names them. It only warns, even for HPE's FAIL. NOT CHECKED: no hvmcli on a 2.0 host, output that will not parse, no answer in 30 s, too little time left, or DLM not saying GFS2 is safe, as it may read GFS2 for its disk space check. It never runs on a frozen host. 1.3 hosts have no hvmcli and get no result. Measured once, by hand, on one host of a healthy cluster: 6 s, 31 checks, five warnings, of which four read Pacemaker. As for changing nothing: its help describes only checks, and its answers read as pings to the gateway and iSCSI portals and reads of local state. Its code has not been read and its calls have not been traced, so read-only is what could be seen, not what was proven.
- **Why it matters:** A second opinion from the vendor. These are HPE's thresholds, and the plugin's own checks judge much the same things, so this shows HPE's answer and repeats none of its logic. Pacemaker is masked by design on HVM, so "Cluster status", "Cluster nodes online", "Cluster failed resources" and "Cluster STONITH" warn on every healthy cluster: alarming on them would be false every time, which is why the default hvmcliIgnore names them.
- **What to do:** Open the host in the HPE checks panel to read what HPE said. Where the plugin has its own check for the same thing (time, quorum, corosync links, multipath, uplinks), read that as well. Once you have judged one harmless here, add its exact name to hvmcliIgnore. NOT CHECKED on a 2.0 host with no hvmcli: see LAY-04.

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

### HST-04: No process killed for lack of memory

- **What it looks at:** Reads the kernel's OOM kills in the log over 24 h, across reboots. FAIL: the victim was a VM's qemu. WARN: something else. NOT CHECKED: the log could not be read. Built from documentation: a real OOM kill has not been seen on a host.
- **Why it matters:** A VM killed this way stops at once with no warning to its guest. A daemon killed this way can be the agent or part of the cluster.
- **What to do:** FAIL: see VM-06 for which VM, start it on a host with room, and move others off. WARN: find what grew.

### HST-05: Host not swapping

- **What it looks at:** Counts pages swapped in and out over the 2 s sample, from /proc/vmstat, against the site standard swapWarnPagesPerSec. WARN: at or over it. Swap merely in use does not count. NOT CHECKED: the counters could not be read. One 2 s sample, so a burst can show, and one between samples cannot.
- **Why it matters:** A VM whose memory is on swap stalls on every page it touches, which looks like slow storage from inside the guest.
- **What to do:** Move VMs off this host, and see HST-07.

### HST-06: CPU not under pressure

- **What it looks at:** Reads some avg60 in /proc/pressure/cpu, the share of the last minute with a task waiting for CPU, against the site standards cpuPressureWarnPct and cpuPressureFailPct. Shows CPU steal over the 2 s sample too, which is not judged, as only a nested host has any. NOT CHECKED: the kernel offers no such file. The limits are a first judgement, not measured on busy hosts.
- **Why it matters:** Every VM here gets its CPU late, and a host short of CPU can miss cluster timers.
- **What to do:** See which VMs are busiest in the tab, and whether vCPUs are overcommitted. High steal: look at whatever runs this host.

### HST-07: Memory not under pressure

- **What it looks at:** Reads some avg60 in /proc/pressure/memory, the share of the last minute with a task waiting for memory, against the site standards memPressureWarnPct and memPressureFailPct. NOT CHECKED: the kernel offers no such file. The limits are a first judgement, not measured on busy hosts.
- **Why it matters:** Every VM here slows down while the host reclaims memory, and the next step is an OOM kill (HST-04).
- **What to do:** Move VMs off this host.

### HST-08: Room on the host's own disks

- **What it looks at:** Reads space and inodes with df, naming / /var /var/log /tmp /boot only, one line per device, against the site standards rootDiskWarnPct and rootDiskFailPct. Never a bare df, as that stats every mount and blocks on a frozen GFS2. NOT CHECKED: df did not answer, or did not finish with nothing wrong in what it read.
- **Why it matters:** A host with a full disk stops logging, and the agent and libvirt can fail with it, leaving it unmanageable.
- **What to do:** Clear space before it fills, starting with old logs and kernels. The plugin has no fix for this.

## Hardware

### HW-01: Power supplies healthy and redundant

- **What it looks at:** Full check only. Reads power supply sensors with ipmitool. FAIL: a supply is failing. WARN: redundancy is not full. NOT CHECKED: no sensors read, or ipmitool is not installed, as on Morpheus-built 1.3 hosts.
- **Why it matters:** One power supply or feed failing takes this host down, and its cluster membership with it.
- **What to do:** Check both supplies are cabled and the BMC redundancy policy is on. NOT CHECKED: the Fix button installs ipmitool.

### HW-02: No memory errors

- **What it looks at:** Full check only. Reads the kernel's EDAC counters. FAIL: uncorrectable errors. WARN: correctable errors. PASS: none reported. There is no row where the host has no EDAC counters at all. A zero is not proof of healthy DIMMs: an HPE firmware-first server manages memory errors in the controller and may not report corrected ones to Linux, so EDAC can read clean on a host the controller knows is faulting.
- **Why it matters:** Rising memory errors come before a host crash, and an unclean crash freezes every GFS2 datastore until it is acknowledged.
- **What to do:** FAIL or WARN: move VMs off and plan a replacement. PASS: do not read it as healthy on its own; check the management controller or iLO/iDRAC memory health too.

### HW-03: Temperatures within limits

- **What it looks at:** Full check only. Reads temperature sensors with ipmitool. FAIL lists any not ok. NOT CHECKED: no sensors read, or ipmitool is not installed.
- **Why it matters:** A sensor outside its limits is a warning about the host's hardware, which a cluster member depends on.
- **What to do:** Check the host's cooling and the sensors listed. NOT CHECKED: the Fix button installs ipmitool.

### HW-04: No serious hardware events recently

- **What it looks at:** Full check only. Reads the last 40 entries of the hardware event log with ipmitool, for serious events still asserted within the site standard selDays. WARN lists them. NOT CHECKED: ipmitool is not installed. An empty log with the tool present is an answer, and gives no row.
- **Why it matters:** A serious event can come before a host failure, and a host that fails uncleanly stalls every GFS2 datastore.
- **What to do:** Read the events listed, on the host's management controller or its Management controller panel. NOT CHECKED: the Fix button installs ipmitool.

### HW-05: Uplinks in a bond at the same speed

- **What it looks at:** Full check only. Compares the speeds of the NICs within each bond. WARN: they differ. There is no row otherwise. 1G for management beside 25G for storage is a design, so bonds are not compared with each other.
- **Why it matters:** A failover would change the bandwidth this host has.
- **What to do:** Check the slower link's cable, optic and switch port, or accept it if the bond was built that way.

### HW-06: Same firmware on identical servers

- **What it looks at:** Full check only. Among hosts of one product name, compares BIOS, management controller and NIC firmware. WARN lists the versions. Different models are expected to differ, so they are not compared.
- **Why it matters:** Identical servers on different firmware can behave differently, which makes a host problem harder to read.
- **What to do:** Bring them in line in your normal firmware work. The plugin has no fix for this.

### HW-07: Disks healthy (SMART)

- **What it looks at:** Full check only. Reads smartctl for the disks under /, and for physical disks behind a MegaRAID controller, each with its own result naming its slot: up to 24 within 40 s, only the megaraid entries of smartctl --scan, and never an iSCSI LUN, which the scan lists too. FAIL: SMART health is not good, or an NVMe critical warning, or an attribute with a WHEN_FAILED value other than a dash, on a boot disk as on one behind the controller. WARN: pending or uncorrectable sectors, or media errors. Reallocated sectors are only shown, as vendors encode them differently. NOT CHECKED: no smartctl, a RAID volume or virtual disk that hides SMART, or a disk behind the controller that gave no health or was not reached. A RAID volume says which physical disks were read instead when they are on its own controller. Seen: physical disks behind a MegaRAID controller read by hand with these commands, on one 2.0 host, and a boot card volume with no SMART. This check itself has not yet run on them. A physical boot disk has not been seen. Other controllers, such as HPE's cciss, have not been seen, and are not read.
- **Why it matters:** A host that loses its boot disk leaves the cluster uncleanly, which stalls every GFS2 datastore until it is acknowledged. A RAID volume hides a failing disk behind it until the volume itself is at risk.
- **What to do:** Replace this disk in a maintenance window. No smartctl: install smartmontools. A RAID volume with its physical disks read: go by their results. Any other RAID or virtual disk: read the physical disks with the controller's own tools.

### HW-08: Management controller healthy

- **What it looks at:** Full check only. Reads the management controller (iDRAC, iLO) through ipmitool: its self-test, and how full its event log is. FAIL: the self-test did not pass; a controller that answers "not implemented" has no self-test, which is not a failure. WARN: the event log is 90% full or has overflowed. NOT CHECKED: ipmitool is not installed, or it is and no controller answered, which can mean its driver is not loaded or there is no controller, as on a virtual machine. Seen on a 2.0 host with an iDRAC; a failing self-test and a full log have not been seen.
- **Why it matters:** The controller is how a host's hardware faults are seen and how a failed host is proven off. One that fails its self-test may report nothing and refuse power commands, and a full log stops recording the events HW-04 reads.
- **What to do:** FAIL: reset the controller from the host's Management controller panel (the host keeps running), and call the vendor if it still fails. WARN: save and clear the event log on the controller. NOT CHECKED: the Fix button installs ipmitool and loads its driver, and is never automatic.

### HW-09: No chassis or sensor faults

- **What it looks at:** Full check only. Reads the chassis fault flags (power overload, main power, power control, drive, cooling or fan) and every sensor through ipmitool. FAIL: a fault flag is set, or a sensor is critical or non-recoverable, or a discrete sensor names a failure, fault or IERR in its reading (ipmitool's status column judges thresholds only, so a failed power supply can still say ok). WARN: a sensor is non-critical, or names an error or a predictive failure. Sensors HW-01 or HW-03 already flag are left to them, and a sensor with no reading is not a fault. PASS only when both the chassis status and the whole sensor list were read; NOT CHECKED when one of them was cut short. No row when neither was read: HW-08 says so. Seen clean on a 2.0 host with an iDRAC; a raised fault has not been seen.
- **Why it matters:** A failing fan, voltage or drive takes the host down sooner or later, and an unclean failure stalls every GFS2 datastore until it is acknowledged.
- **What to do:** Read the fault on the controller or the host's Management controller panel. If it is real, move VMs off and plan the repair in a maintenance window.

## Kernel

### KRN-01: Panic settings safe for a clustered host

- **What it looks at:** Full check only. Reads kernel.hung_task_panic, kernel.softlockup_panic, kernel.panic and kernel.panic_on_oops from /proc/sys, and each GFS2 mount's error policy from /proc/mounts. FAIL: hung_task_panic is non-zero. WARN: softlockup_panic set, kernel.panic above 0, or errors=panic on a GFS2 mount. NOT CHECKED: none of these could be read. panic_on_oops is shown for context and does not change the status. Built from kernel documentation, not seen set on a host.
- **Why it matters:** With hung_task_panic on, a normal fence wait on a frozen datastore makes every surviving host's blocked tasks panic at about the same moment, so one host failure takes the whole cluster down. A non-zero kernel.panic reboots a panicked host, which can clear a fence wait by rejoining cleanly, but reboot-loops on a recurring fault; softlockup_panic and errors=panic turn an event a host could survive into a crash. HPE owns these and publishes no position yet.
- **What to do:** Raise with HPE before changing anything. The plugin does not change sysctls or mount options.

### KRN-02: Kernel not tainted by a fault

- **What it looks at:** Full check only. Reads /proc/sys/kernel/tainted. FAIL: a bad-page or oops/BUG bit is set. WARN: a machine-check or soft-lockup bit is set. NOT CHECKED: the file could not be read. Proprietary and out-of-tree module bits are ignored, as they are normal here.
- **Why it matters:** A taint bit means the kernel already hit a fault, and it holds until reboot, so it outlasts the log line. A host that has oopsed or seen a machine check or a bad page can crash, and an unclean crash freezes every GFS2 datastore until it is acknowledged.
- **What to do:** Find the cause in the kernel log and plan a reboot in a maintenance window. The plugin has no fix for this.

### KRN-03: No PCIe bus errors

- **What it looks at:** Full check only. Sums the PCIe AER error counts across devices from /sys/bus/pci/devices/*/aer_dev_*. WARN: any fatal or non-fatal error. NOT CHECKED: no AER counters are exposed, as on firmware-first platforms. Correctable errors are shown for context and raise nothing, as that counter only grows and a per-interval rate is not kept yet. Built from the kernel ABI, not seen non-zero on a host.
- **Why it matters:** AER errors point at a NIC or HBA failing on the PCIe bus, which under iSCSI or corosync load can take storage or cluster traffic with it. A firmware-first platform may report none, so silence is not proof.
- **What to do:** Find the device in the kernel log (PCIe Bus Error) and plan a replacement. The plugin has no fix for this.

### KRN-04: Kernel carries the DLM and GFS2 freeze fixes

- **What it looks at:** Compares the running kernel (uname -r) with the site standard minKernel, a floor pinned per layout. WARN: below it. PASS: at or above. NOT CHECKED: the kernel or the floor will not parse, or no floor is set for this layout. Runs on every check.
- **Why it matters:** Below the floor the kernel is missing fixes that matter to GFS2 and DLM recovery. Even at or above it, Ubuntu's 6.8 kernels still lack the request-cancel, evict and QUECVT fixes that 7.0 carries, so this is a floor, not an all-clear. The floor and these facts are read from the Ubuntu changelog and kernel source, and are not verified against HVM OS kernel naming.
- **What to do:** Plan a kernel update through HPE's own procedure, one host at a time; the plugin has no fix for this. Raise the missing backports with HPE. Set minKernel per layout to match what HPE ships, or to raise the floor.

### KRN-05: No DLM lock-conversion failures after recovery

- **What it looks at:** Searches the kernel log over 7 days for HPE's -22 signature (validate_lock_args -22 or lm_lock ret -22). FAIL: seen since the last boot. WARN: seen only before the last boot. PASS: none. NOT CHECKED: the log could not be read. From HPE's troubleshooting note, confirmed in kernel source; not yet seen on a host here.
- **Why it matters:** After a DLM recovery, downward lock conversions are rejected with EINVAL (-22) and GFS2 returns EIO to the guests, so VMs on this host can see I/O errors, read-only filesystems or a shut-down guest while storage looks healthy. A guest reboot does not clear it. Present on Ubuntu 6.8 (not backported) and fixed on 7.0.
- **What to do:** Follow HPE's procedure: shut down the affected VMs, put the host in maintenance mode and reboot it. See KRN-04 for the kernel floor. It clears 7 days after the last line.

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

- **What it looks at:** Full check only. Tries TCP 22 and TCP 7443 from each host to each corosync ring address. FAIL names the port and the peers it is closed to. A peer whose field is missing, as from an older probe, is not judged on that port.
- **Why it matters:** HPE documentation states TCP 22 between hosts (hosts migrate over SSH by default) and TCP 7443 between all hosts (the agent's heartbeat and quorum pings). A blocked 7443 makes a peer count as unreachable to the agent, which can skew quorum decisions.
- **What to do:** Open the named port between these hosts, on the host and on whatever is between them. 7443 is the agent port; see CLU-14 for the agent quorum it carries.

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

- **What it looks at:** Reads each VM port's VLAN from the bridge on every check and compares it with its Morpheus network, joined on name and MAC. WARN: it differs. FAIL: it differs, and every network on that VLAN belongs to another customer tenant. A first sighting is only a WARN marked confirming: it counts when two checks in a row see it. A FAIL writes an audit entry, and its alarm is named SECURITY. On a host whose ports could not be read, including a quick check while DLM is stopped, what it last had stands. NOT CHECKED: nothing could be compared. So far only a blank-VLAN port and a tagged one have been seen, on nested hosts, not a real wrong-VLAN port.
- **Why it matters:** FAIL: this VM can reach another tenant's network. WARN: the VM is not on the network its owner thinks.
- **What to do:** Confirming: nothing yet, unless you know of no network change here. FAIL: confirm on the host with its bridge's VLAN list and the VM's definition, then take the port off that VLAN, and find out how it got there. WARN: find out which is right.

### NET-13: No unexpected trunk

- **What it looks at:** Reads each VM port from the bridge on every check. WARN: a trunk not listed in the site standard trunkAllowedVms, or a customer VM on an untagged Open vSwitch port. FAIL: the same, reaching a VLAN that belongs to another customer tenant. A first sighting is only a WARN marked confirming: it counts when two checks in a row see it. A FAIL writes an audit entry, and its alarm is named SECURITY. On a host whose ports could not be read, including a quick check while DLM is stopped, what it last had stands. NOT CHECKED: an untagged port on a VM not known to be a customer's, or ports that could not be read.
- **Why it matters:** A guest on a trunk can reach other networks by tagging its traffic. Measured on 1.3: a guest on a port with no tag reached a guest on a tagged VLAN. That port is what a network with a blank VLAN or VLAN 0 builds on 1.3. That was on nested hosts, and it has not been seen on a real trunk uplink.
- **What to do:** Confirming: nothing yet, unless you know of no network change here. If this VM should have a trunk, such as a firewall, add it to trunkAllowedVms. Untagged customer port: move the VM to a network with a VLAN. FAIL: confirm on the host, then take those VLANs off the port.

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

### NET-17: Bond fails over fast enough for corosync

- **What it looks at:** Full check only. Reads each bond's mode, miimon, up/down delay, LACP rate and hash policy from sysfs and /proc/net/bonding. WARN: LACP rate slow on an 802.3ad bond, no link monitoring (miimon and ARP both off), or a balance-rr, balance-xor, balance-tlb or balance-alb mode. Built from kernel and HPE documentation; HPE says the bond timing defaults are not guaranteed.
- **Why it matters:** A bond slow to notice a dead link can take longer than corosync takes to evict the host, so a single cable or optic fault costs cluster membership and freezes GFS2. Slow LACP alone can mean up to 90 seconds.
- **What to do:** Change bond timing only on a host in maintenance, through hvmcli, and set both switch ports for LACP fast where that is the fix. The plugin changes nothing.

### NET-18: Network links are not flapping

- **What it looks at:** Full check only. Reads each physical NIC's carrier-change count since boot from sysfs and divides by uptime, against the site standard linkFlapWarnPerHour. WARN: a NIC at or above it. NOT CHECKED: no carrier counters could be read. This is a rate since boot, so a short recent burst on a long-running host is diluted; a per-interval rate is a backlog item.
- **Why it matters:** A link going up and down drops cluster and storage traffic each time, while reading up whenever a check samples it, so it hides from the other network checks.
- **What to do:** Find the optic, cable or switch port. The plugin has no fix for this.

### NET-19: Bridge filters VLANs

- **What it looks at:** Full check only. 2.0 only. Reads /sys/class/net/<bridge>/bridge/vlan_filtering and stp_state for each linux bridge. PASS: filtering on. For information (NA): filtering off on a per-VLAN bridge, whose name carries the VLAN (e.g. hvmbr0.60) and which isolates by being one bridge for one VLAN. WARN: filtering off on a bridge whose name does not carry a VLAN, so it may carry several. NOT CHECKED: the flag could not be read. The STP state is shown beside it.
- **Why it matters:** On a bridge that carries several VLANs, filtering off lets a VM reach any of them, so tenant isolation is gone. A per-VLAN bridge isolates by structure, so filtering off there is expected. Whether HPE's 2.0 virtswitch uses one VLAN-aware bridge or a bridge per VLAN is unconfirmed; a real host used per-VLAN bridges.
- **What to do:** Confirm the 2.0 bridge model with HPE. The plugin does not change the bridge.

## Packages

### PKG-01: Same packages and Morpheus agent on every host

- **What it looks at:** Compares corosync, dlm-controld, gfs2-utils, libvirt-daemon-system, qemu-system-x86 and the Morpheus agent across hosts. WARN: versions differ. NOT CHECKED: no agent version was found on a host.
- **Why it matters:** Hosts on different versions may not behave the same in a failure. Agent versions did not: see PKG-03.
- **What to do:** HPE's own 9.1 docs disagree on whether apt upgrade is allowed on an HVM host at all: the upgrade guide forbids it outside the rolling update, the lifecycle page permits it per host in maintenance mode. So the plugin no longer recommends a patching method. Follow HPE's rolling cluster update, or confirm the supported procedure with HPE before patching. The plugin has no fix for this. See docs/HPE-QUESTIONS.md.

### PKG-02: Same kernel on every host

- **What it looks at:** Compares uname -r across hosts. WARN: they differ. There is no row otherwise.
- **Why it matters:** GFS2 and DLM are in the kernel, so hosts on different kernels are not running the same code.
- **What to do:** Bring them in line in your normal patching. HST-01 shows hosts waiting on a reboot.

### PKG-03: Morpheus agent is at least the version that handled a freeze correctly

- **What it looks at:** Compares each host's agent version with the site standard agentMinVersion. WARN lists those older.
- **Why it matters:** An older agent (3.2.6) acknowledged the wrong nodes in a real freeze, so GFS2 stayed frozen until someone acknowledged the right node by hand. Newer agents acknowledged correctly in every test. Whether that is a fix is HPE's to confirm.
- **What to do:** Upgrade through HPE, but in a maintenance window and one host at a time: an agent upgrade has itself triggered failover when run on a host outside maintenance (HPE fixed one such case in 8.0.13-2, and whether later versions are safe outside maintenance is unproven). Until then watch dlm_tool on the other hosts during any host removal. See docs/HPE-QUESTIONS.md.

### PKG-04: Automatic updates cannot restart the cluster stack

- **What it looks at:** Full check only. Reads apt sources, the unattended-upgrades state and the needrestart config, all read-only. WARN: a stock Ubuntu archive is configured, unattended-upgrades is on (APT::Periodic::Unattended-Upgrade "1" and the timer not disabled), and no needrestart drop-in sets list-only or names the cluster daemons. PASS otherwise, including an HVM-sourced host whose mvm needrestart drop-in exempts the stack; the observed text says which was found. Built from HPE package scripts and Ubuntu defaults, not seen fire on a host.
- **Why it matters:** On a stock-sourced host, unattended-upgrades plus needrestart can restart corosync or dlm within the 06:00 window after a library update, on every host inside one hour, which on a GFS2 member stalls every datastore. An HVM-sourced host should match nothing to upgrade, so the risk moves into the patch itself (PKG-01, PKG-03).
- **What to do:** Raise with HPE. Until then patch through maintenance one host at a time, or add a needrestart list-only drop-in. The plugin changes nothing.

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

### PRB-04: Checks are keeping up

- **What it looks at:** An alarm only, on the master tenant: it never appears on a cluster's tab. WARN: the most overdue cluster is more than twice its quick check interval past its turn. A cluster whose check cannot run (PRB-02) or that has a job running is left out. It also warns when the freeze watch has not been able to start a read for 2 minutes, as every thread it has is held by an agent call that never returned. It measures the queue and clock of the appliance node running the checks, and reads no host. The all-clusters page shows the oldest check, the most overdue and how many are waiting.
- **Why it matters:** A finding is only as new as the last check, so while this lasts a new problem can go unseen for that long. The status URL's /alive reads STALE meanwhile. The freeze watch does not wait in this queue, so it still finds a freeze, unless it is the part that has stopped. Then a freeze waits for the next check, which reads every host.
- **What to do:** Look on the all-clusters page for many silent hosts, which hold a checker longest. If the appliance has room, raise the Clusters checked at once setting; otherwise lengthen the quick check interval. The alarm clears itself once checks have caught up.

## Security and tenant isolation

### SEC-01: Customer VM exposes CPU virtualization to its guest

- **What it looks at:** Full check only. Reads each running customer VM's effective CPU block from its domain XML, with the host's kvm nested parameter. WARN: a customer VM presents VMX (Intel) or SVM (AMD) to its guest, which is host-passthrough on a host with nested on and no feature disabling it, or a named model that requires it. A named model with VMX or SVM disabled is not a finding. NOT CHECKED: host-passthrough while the nested parameter could not be read. Operators only, never in a report. Built from HPE docs and one hand-edited VM, not seen as a Morpheus default.
- **Why it matters:** A guest that sees VMX or SVM can run its own hypervisor, which widens the guest-to-host attack surface the 2026 KVM escapes use. The gate is the cluster CPU Model, not the per-VM Nested Virtualization box and not a role.
- **What to do:** Set the cluster CPU Model to a named model with virtualization disabled, which HPE owns, rather than changing the box. See SEC-02 for whether the kernel carries the escape fix.

### SEC-02: Kernel carries the KVM guest-to-host escape fix

- **What it looks at:** Full check only. Compares the running kernel with the site standard kernelCveMin, the layout's fix level for the 2026 KVM escapes (Januscape, Zapscape), by upstream version and ABI. FAIL: below it and nested virtualization is exposed on the host. WARN: below it with nesting off. PASS: at or above it. NOT CHECKED: no fixed version is set for the layout, or the kernel could not be read. The ABI level is unverified against HPE's build.
- **Why it matters:** Below the fix an untrusted guest with nesting exposed is close to a host compromise, which on a GFS2 host reaches the shared storage.
- **What to do:** Ask HPE which HVM OS kernel carries the fix for this layout, and set kernelCveMin if yours differs. Do not patch outside HPE's workflow. A FAIL with no HPE-published kernel cannot be cleared by patching, so treat it as a question for HPE.

### SEC-03: CPU side-channel mitigations (for information)

- **What it looks at:** Full check only. Reads /sys/devices/system/cpu/vulnerabilities/* and the SMT state, and lists any vulnerability the kernel marks Vulnerable. NA: a note, detection only, no exploit; it raises no alarm.
- **Why it matters:** An operator should be able to see the host's own reported side-channel state on a shared cluster. HPE owns the kernel, microcode and SMT policy.
- **What to do:** For information. SEC-02 is the actionable kernel-fix level; raise SMT and microcode with HPE.

### SEC-04: 2.0 bridge STP design (for information)

- **What it looks at:** Full check only. On the 2.0 layout, reads the STP state of each VLAN-filtering Linux bridge. NA: a note, no alarm. It does not run on 1.3, whose Open vSwitch trunking is covered by NET-13.
- **Why it matters:** With STP on a guest can send superior BPDUs and take the bridge root; with it off guest BPDUs reach the uplink, where a switch's BPDU guard can err-disable the host's trunk. HPE documents neither.
- **What to do:** Raise the STP design with HPE. Do not change STP on a live bridge: it can drop corosync.

### SEC-05: Customer VM on a host infrastructure VLAN

- **What it looks at:** Full check only. Compares each customer VM port's VLAN with the VLANs the host's cluster and storage interfaces are tagged with. WARN: a customer port shares one. PASS: none do. No row when no infrastructure interface is tagged, so there is nothing to compare, or there are no customer ports. Operators only, never in a report. Built from documentation, not seen live.
- **Why it matters:** A customer VM on the host storage VLAN could try to reach the LUN that holds every tenant's disks; on the cluster VLAN it reaches corosync and DLM.
- **What to do:** Confirm on the host, then move the VM off that VLAN. The plugin never changes a port by itself.

### SEC-06: Per-VM resource limits

- **What it looks at:** Full check only. Counts running customer VMs and how many have a per-VM I/O, CPU or network limit set in their domain XML. NA: one fleet note, never an alarm per VM.
- **Why it matters:** Morpheus documents almost no per-VM disk, CPU or network limit, so fairness on a shared datastore or uplink rests on the array and the host, not on Morpheus. Today this is universally zero.
- **What to do:** Raise plan-level I/O and bandwidth limits with HPE. There is nothing to act on per host.

## Storage

### STO-01: Multipath paths healthy

- **What it looks at:** Reads multipath -ll per LUN, while the site standard mpio is on. FAIL: the map has no paths, a path is genuinely failed, or no path is active and ready (only ALUA standby left). WARN: a single path. An ALUA standby path (checker ghost, as Alletra, Nimble and Peer Persistence legs report) is healthy standby and counts as healthy, not a fault.
- **Why it matters:** A map with no active path has lost its storage, and one with a single path has no redundancy left. Lost I/O is the usual cause of a GFS2 withdrawal. A permanent false alarm on standby paths would hide a real loss.
- **What to do:** FAIL: Fix offers multipath-reconfigure (an iSCSI rescan where there are sessions, else a SCSI rescan of the Fibre Channel hosts, then multipathd reconfigure), on a host in maintenance mode with no VMs. Also check the array and the storage network.

### STO-02: no_path_retry window is safe

- **What it looks at:** Reads no_path_retry from multipathd per LUN, while the site standard mpio is on, and reports the window in seconds (the retry count times the polling interval). It never fails. WARN: the window is under the corosync token, or over the site standard noPathRetryPolicy (180 s by default). "N sec" means the map has no path right now, a countdown, so WARN; "off" with no active path means its retries are exhausted, so WARN. NOT CHECKED: dm shows queueing and multipathd gave no limit, which the dm features line cannot answer alone. The vendor's documented value is shown, from a profile built from documentation.
- **Why it matters:** With all paths gone, a window shorter than a controller failover withdraws GFS2 before the array recovers; one far longer lets held writes land after the host has been declared dead. No vendor ties a value to GFS2, so this is a policy, not a vendor pass or fail.
- **What to do:** Set no_path_retry in the multipath configuration, in a maintenance window, between the token and noPathRetryPolicy, raising the value with HPE and the array vendor first. NOT CHECKED: run multipathd show maps format "%n %Q" on the host.

### STO-03: No iSCSI session errors

- **What it looks at:** Counts iSCSI ping timeouts and offline-device errors in the kernel log over 24 h. WARN at the site standard iscsiErrorsWarn. NOT CHECKED on a Fibre Channel host with no iSCSI sessions, where the iSCSI error count says nothing either way.
- **Why it matters:** These are I/O paths failing, even if multipath has recovered since. On an FC host they do not apply, so a clean count must not read as assurance.
- **What to do:** Look at STO-01 and STO-06, then the storage network on this host. On a Fibre Channel host see STO-09.

### STO-04: Storage path daemons running

- **What it looks at:** Where multipath maps exist, reads systemctl for multipathd, which looks after iSCSI and Fibre Channel maps alike, and for iscsid where there are iSCSI sessions. FAIL: one that is needed is not active.
- **Why it matters:** These daemons look after the paths under every GFS2 datastore on this host. On a Fibre Channel host iscsid is not needed, but a dead multipathd still matters, which the old iSCSI-only check could not see.
- **What to do:** Find out why it stopped. The plugin has no fix for this.

### STO-05: Same path count on every host

- **What it looks at:** Compares the number of paths per LUN across hosts. WARN: they differ. There is no row otherwise.
- **Why it matters:** A difference means the hosts do not see this LUN the same way, and one with fewer paths has less redundancy.
- **What to do:** Compare the paths on the host that differs. STO-12 sets an absolute floor where a design is known.

### STO-06: Storage latency

- **What it looks at:** Measures average I/O wait per LUN over 2 s on each host. WARN and FAIL at the site standards storageWarnMs and storageFailMs. Skipped below 20 I/Os in the sample, where one slow I/O would fail it. When the host sampled its VMs, the hint names the one doing the most I/O there. A quick check samples them only at 20 ms or more, a fixed value in the probe.
- **Why it matters:** VM disks wait this long on every I/O.
- **What to do:** Check the other hosts. If they are slow too, look at the array; if only this one is, its paths and NICs. See whether the VM the hint names is the cause, as the host-wide figure does not say which LUN it was on.

### STO-07: Array has not warned that a thin LUN is filling

- **What it looks at:** Full check only. Searches the kernel log over 7 days. WARN: the array sent a thin-provisioning soft-threshold warning (SCSI sense 38/07). FAIL: a hard space-allocation failure (sense 27/07 DATA PROTECT, "critical space allocation" or "space allocation failed write protect"), or multipath shows a map write-protected. NOT CHECKED: the kernel log could not be read. An array with no soft threshold set never sends the warning, so a pass does not say the array has room. Built from the kernel source: no array has sent one here yet. It does not read the array's real usage.
- **Why it matters:** GFS2 free space does not show how full a thin LUN's array is. The soft warning is time to act; the hard failure or a write-protect is a withdraw in progress, however much df says is free.
- **What to do:** Look at the array now: its pool usage, and what else shares it. The finding clears 7 days after the last warning.

### STO-08: Storage array recognised

- **What it looks at:** Per LUN, maps the trimmed INQUIRY vendor and product to a vendor family and names the transport (iSCSI or Fibre Channel). PASS: a known family. NOT CHECKED: an unknown vendor, whose vendor-specific items are then not judged and whose values are only shown, never compared with another vendor's. Target port group detail needs multipathd %A, which exists only on Ubuntu 26.04, so it reads as not read on 24.04. Built from documentation: the pair gives a family (3PARdata/VV covers several), not a model.
- **Why it matters:** The vendor decides what the other storage checks expect, so an unknown vendor must never be judged against another vendor's value, and a vendor-specific rule returns not checked for it.
- **What to do:** Nothing on PASS or NOT CHECKED. A vendor that should be recognised can be added to the plugin's profile table. Pure-specific tuning stays in the Pure plugin.

### STO-09: Fibre Channel fabric healthy

- **What it looks at:** On a host with Fibre Channel, reads /sys/class/fc_host and fc_remote_ports. FAIL: a port is not Online, or no remote target port is Online. WARN: only one adapter Online. Link error counters are cumulative, so they are shown, not judged. No row on an iSCSI-only host. Built from documentation: no FC host has been seen, as the test hosts are iSCSI.
- **Why it matters:** A failing optic or a single-fabric host goes unnoticed until the second failure takes every path and GFS2 withdraws.
- **What to do:** Check the HBA, optic, zoning and fabric, and confirm two independent fabrics. Confirm the reading on a real fabric, as this is built from documentation.

### STO-10: iSCSI targets log in at boot

- **What it looks at:** Reads node.startup for each iSCSI target. WARN: any target is not automatic. No row on a Fibre Channel host, or where no node records were read.
- **Why it matters:** A rebooted host returns with no session to a manual target, so its GFS2 datastores do not mount. The open-iscsi default is manual.
- **What to do:** Set node.startup to automatic for those targets. The plugin has no fix for this.

### STO-11: iSCSI path recovery timeout

- **What it looks at:** Reads each iSCSI session's live recovery_tmo, which multipathd sets from fast_io_fail_tmo. WARN: a value differs from multipathd's own fast_io_fail_tmo (120 s means it was never applied), or from the vendor's documented value, or hosts hold different values. The vendor comparison is built from documentation and labelled unverified. No row on a Fibre Channel host, or where no session was read.
- **Why it matters:** This is how long a path holds I/O before failing over. A session left at 120 s stalls far longer than intended, past the storage-loss clocks, and hosts that differ hold I/O for different times during one failover.
- **What to do:** Read multipathd show config and the session's sysfs where they differ. The plugin has no fix for this.

### STO-12: Enough storage paths

- **What it looks at:** Compares each LUN's path count on each host with the site standard expectedPaths, while mpio is on. WARN: below it. No row unless expectedPaths is set, as designs differ. STO-05 compares across hosts; this is the absolute floor.
- **Why it matters:** A LUN uniformly below the design's path count passes STO-05, which only compares hosts, yet has less redundancy than intended.
- **What to do:** Set expectedPaths to the design (often 4), or lower it where fewer is deliberate, such as in a test environment.

### STO-13: No all-paths-lost events in the storage log

- **What it looks at:** Full check only. Searches the multipathd log over 7 days for a map losing every path ("remaining active paths: 0", "Entering recovery mode"). FAIL: any sighting. NOT CHECKED: the log could not be read. With a journal that does not persist across reboots, it loses what a reboot clears.
- **Why it matters:** On a GFS2 map an all-paths-lost event is a withdraw, and the moment before an all-paths-down outage. After the fact this log is the key near-miss.
- **What to do:** Find what took the paths, an array, a controller or a fabric, before it happens again. It clears 7 days after the last sighting.

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

### VM-06: No VM crashed or killed for lack of memory

- **What it looks at:** Reads the end of each log under /var/log/libvirt/qemu touched in the last day for reason=crashed, and the VMs among HST-04's victims. WARN: one in the last 24 h. NOT CHECKED: a log could not be read, or a crash line has no readable time. Built from documentation: a real crash has not been seen on a host.
- **Why it matters:** Its guest stopped without warning, and its owner may not know why.
- **What to do:** See whether the VM came back and tell its owner. HST-04 shows whether this host ran out of memory. Otherwise look in the VM's own log. It clears by itself after 24 h.

### VM-07: No VM disk ignores guest flushes

- **What it looks at:** Full check only. Reads each running VM's disk driver cache mode from its domain XML. FAIL: a disk uses cache=unsafe. PASS: none do. Built from documentation: not seen on a host, and a CD-ROM on cache=unsafe would read here too, so confirm it is a data disk.
- **Why it matters:** cache=unsafe tells QEMU to ignore the guest's flushes, so a host crash can lose data the guest believed was written. It is the one cache mode that is wrong on a clustered datastore; the others trade speed for host-RAM use but still honour flushes.
- **What to do:** Reconfigure the VM off cache=unsafe. The plugin has no fix for this: Morpheus owns the VM definition.
