# Changelog

## 0.3.0

The first release tested against live HVM 1.3 and 2.0 clusters, including real freezes.

**Removing a host**
- **Remove safely replaces safe eject,** whose `enableEject` setting is gone. Morpheus's remove is a clean leave when all goes well, but it carries on when GFS2 will not unmount. So the plugin checks what that remove does not (nothing has GFS2 open, no VMs on record, quorum kept, no host recovering), unmounts GFS2 itself with a time limit, stops there if that hangs, and only then calls Morpheus's remove and watches the remaining hosts.

**New checks**
- **Failover (HA-03, HA-04, HA-05):** the cluster's "Automatically Power On VMs", which decides whether a lost host's VMs restart, the cloud's for reference, and a heartbeat target.
- **GFS-10:** consistency errors and withdrawals in the kernel log over 7 days, because a reboot clears the live state and the agent reboots a host soon after a withdrawal.
- **Tenant network isolation (NET-12, NET-13):** VM ports against their Morpheus VLAN, and trunks, with `trunkAllowedVms`. On 1.3, a customer VM on a network with a blank VLAN or VLAN 0 has a port with no tag, and a guest there reached a guest on a tagged VLAN by tagging its traffic. Critical when another tenant's VLAN is reached.
- **NET-15:** iSCSI on physical ports of its own, traced under VLANs, bonds and bridges. NET-02 compares only interface names, which can sit on one bond. Where sharing is deliberate, list it in `disabledChecks`.
- **MIG-07:** VMs that cannot live-migrate because of their CPU settings, which the pre-flight now says too. **MIG-06:** VMs still on host-passthrough under a named cluster CPU model.
- **DLM-06:** `dlm_controld` still retrying a fence with every lockspace running. This used to read as a freeze, and offer an acknowledgement.
- **PKG-03:** the Morpheus agent older than 4.0.1. Agent 3.2.6 acknowledged the wrong nodes in a real freeze, while newer agents acknowledged correctly in every test. Set `agentMinVersion` to change it.
- **HA-06:** the Morpheus manager running on the cluster it manages, where a freeze takes the console and every alarm with it.
- **NET-16:** a cluster network address from DHCP. A lost or changed lease takes a host out of the cluster.
- **MOR-05:** an error in Morpheus's status for a host that looks healthy. **MOR-06:** a cluster record marked failed, usually an add whose last steps failed.
- **CLU-13:** an empty corosync authkey. An add can fail partway and still show the host as provisioned, leaving the others a vote short. `rejoin` is no longer offered on such a host, as deleting and adding it again is what works.
- **Morpheus's own Remove** is called out in HA-02 and CLU-05, as its checks do not run through there. **DLM-01** shows the time before HVM's agent starts stopping VMs.
- **PRB-03:** a mistake in the site standards warns, and checks carry on with the built-in ones. **PRB-02:** an automatic check that could not run at all.
- **Quorum device (CLU-12), certificates (HST-02, HST-03), boot disks (HW-07), storage migration space (GFS-09), frame size (NET-14).**

- **A freeze with no fence line** is named too: DLM-01 works out the node from the lockspace's members, and the emergency plan offers it. A delete during a GFS2 mount froze a test cluster this way for 27 minutes, with no finding at all. Remove safely now also holds back a host that joined under 10 minutes ago.
- **GFS-10** is critical for an error since the host's last boot, and a warning afterwards, for up to 7 days.

**Pages and history**
- **A reference for every finding.** Each check id in the readiness and findings tables opens a popup with what it looks at, why it matters and what to do, and **Help**, beside Run full check, searches all of them by id or words and explains the statuses and id prefixes. The text is written from the code and comes from one place, `CheckCatalog`, through a new read route with the same gate as `status`. [docs/CHECKS.md](docs/CHECKS.md) is generated from it, and tests fail if an id, a standards key or that file falls out of step. The report keeps its ids as plain text.
- **All-clusters page** under Operations > Analytics > Cluster Health, for the master tenant only, replacing the section at the top of the cluster tab.
- **Finding history:** when each finding first appeared and cleared, kept across plugin reloads, and shown as First seen. Cleared findings are kept for a year, up to 500 per cluster. No observed text, raw output, or tenant, VM or network names are stored.
- **Cluster report:** current findings and their history, to print or export as CSV or JSON. Generating one is audited. It holds no tenant data; see docs/PERMISSIONS.md.

**Fixes from running on live clusters**
- **Frozen** now requires a stopped lockspace, not just a fence line, and the emergency plan accepts a host restarted into a stopped lockspace.
- **The pre-flight** answers within 90 s on a silent host, rather than a 504 from the proxy.
- **Heartbeats** are matched on the host's name as well as its system hostname, which changes on a re-add.
- **All-clusters order** counts failures and warnings within a health level. **A journal count of 0** is a failed read, not a finding.

- **Time on systemd-timesyncd:** the time view, offset, drift and source checks now read timesyncd, which is what a Morpheus-built 1.3 host runs. Synced hosts no longer show "none configured: following nothing".
- **Drift** compares only hosts reading the same server, by address, and says what is left unknown when they read different members of a pool. Timesyncd reports one sample per host, so what it shows warns rather than fails.
- **`ntp-sources` on timesyncd:** a drop-in in `/etc/systemd/timesyncd.conf.d/`, a restart and a check that a listed server answers, putting the previous configuration back if none does. Same maintenance-mode guard.
- **STO-02** reads `multipathd show maps format '%n %Q'`. Only "on" is the finding: a finite retry such as `12 chk` was a false alarm. Without that value it says it cannot tell, rather than claiming endless queueing from the dm features line.
- **New status, not checked:** a check that applies but could not be answered. A missing `ipmitool` now reads "cannot tell: ipmitool not installed" on FEN-01, HW-01, HW-03 and HW-04, not "none", and readiness shows it.
- **VLAN test:** without `arping` it says "arping not installed: not tested" before anything moves. An arping that gives no result is not tested, never a pass or a VLAN failing. It tests every active-backup bond on a bridge and names the bond in each result, and silence on one bond is only a failure if none answers. A bridge it cannot test is reported, and a VLAN silent on the rest is inconclusive. Its timer and the plugin's wait now scale with bonds and VLANs.
- **PKG-01** compares the Morpheus agent's version from `/opt/morpheus-node`, as it is not a dpkg package there, and says not checked when it cannot read one.
- **LAY-05** no longer shows a trailing comma.
- **HA-01** is a note, not a warning: `notHighAvailability` is set on every cluster seen so far and its meaning is unknown.
- **`service-enable`** had its tier inverted, which hid the button. **NTP turned off** now reads as a finding with its fix while the clock still says synchronised, since the kernel keeps saying so long afterwards.
- **A record Morpheus is still removing is not a host:** it blocked every fix. It now gets its own warning, MOR-04.
- **A silent host names the VMs recorded on it**, rather than "no running VMs". `rejoin` says that it does not mount GFS2, and to reboot in maintenance mode: leaving it without that can make the agent force a reboot.
- **Corrected what a lost host does**, from measurements: HVM's agent normally acknowledges within about 2 minutes, and if nothing does, the agent stops VMs at about 9 minutes.
- **New setting, Clusters automatic fixes apply to** (`autoFixClusters`): ids or names, comma separated. Empty means none, so **automatic fixes that were on stop until a cluster is named**. Checked again on fresh state and the settings as saved, when a fix runs.

**Fixes from auditing the release candidate**
- **Routes that change something answer POST only.** Fixes, the time source, VLAN tests, Remove safely and the acknowledgement ran on a GET, which has no CSRF token, so a link could trigger them.
- **The emergency panel and raw output answer within 90 s,** before the proxy's 504, as the pre-flight already did.
- **A slow probe no longer hides a freeze.** The probe reads DLM and quorum first, and stops reading at a limit it is told, 30 s before the caller gives up, then says it was cut. A host cut short gets PRB-01 and still names the freeze; for everything else, fixes above all, it has not answered.
- **GFS2 guard:** every mounted filesystem needs a lockspace listed, as an empty list after `dlm_controld` restarts is not healthy. `gfs2_edit` and reading process arguments, which can sleep unkillably, run in a job the probe stops waiting for.
- **The VLAN test cannot cost a host its membership.** VLANs go in runs of at most half the corosync token on any uplink (3 at 60 s), with the uplink back between them. A lock on the host refuses a retry while a test or its timer runs, and the test prints the uplink it found and fails if it did not get back there.
- **A refusal gives nothing away.** It goes to the appliance's log, not an audit entry in the caller's tenant, and a missing cluster answers 403 like someone else's.
- **Report and history:** tenants that own a network here are withheld, a network read that fails fails the report, and MIG-06, MIG-07, NET-12 and NET-13 lose their observed text. History no longer stores the VM name in the NET-12 and NET-13 titles.
- **Monitoring:** a check that cannot run, such as with a typo in the site standards, raises an alarm (PRB-02), leaves the others as they were and waits the normal interval. Four clusters are checked at once, so silent hosts on one do not delay the rest. Site standards of the wrong type are refused by name.
- **Alarms** are no longer replaced when only their text changes, which lost acknowledgements and start times. A finding that could not be judged keeps its alarm. Those of a deleted cluster, or of any cluster with automatic checks off, clear daily.
- **Failures no longer read as healthy:** an unread kernel log is not "no GFS2 errors" (GFS-10), vanished paths count, and a map with none fails (STO-01).
- **Alarms on healthy clusters:** HB-01 leaves stale files to HA-03 when every host has them, reads a host's newest file, and does not judge a host just added back on a file from before it joined. STO-06 needs 20 I/Os. HW-05 compares within a bond. NET-10 ignores a /32 VIP inside a host's own subnet. NET-11 is not checked rather than a warning, as it comes and goes with traffic. DLM-02 waits for the agent's usual 3 minutes.
- **NET-15** read not checked on real clusters: the ring addresses reached `awk` across several lines, so the ring device got no ports.
- **`ntp-enable` needs maintenance mode,** as it can step a clock. **An unmount refused** starts its pool again, and says so. **A fence acknowledgement** only reports recovery from a `dlm_tool` that answered. **The pre-flight** sizes VMs together, not each alone. **Removal stopped after the unmount** says GFS2 is unmounted and to reboot in maintenance first. **The time source** is not saved when the cluster is busy. Automatic retries are per cluster.
- **Removed:** the optional VLAN and gateway on the VLAN test, the tab's opener by URL, and `quorumFencing`, which nothing checked.

## 0.2.0

- **Can I take it down?** A read-only pre-flight per host: quorum without it, where each VM can go, what stands in the way.
- **Safe eject**, off by default: takes a host out of the cluster so nothing waits to fence it.
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
