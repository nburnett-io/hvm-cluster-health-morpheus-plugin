# Backlog

## To validate

- A freeze that hand-run `dlm_tool fence_ack` could not clear (`-125`, no need_fencing), seen once after a delete hit a GFS2 mount in progress. It cleared only after the node was added back and went down again. What should clear it is HPE's to answer; the plugin names it and says so.
- Why HB-01 warned once on a healthy host while a peer was silent. Not traced.
- What makes GFS2's unmount hang in `gfs2_gl_hash_clear` during a host removal. Seen once in several removals, about 45 minutes after VMs on that datastore were deleted, which may or may not be related.
- What stopped HVM's agent acknowledging the failed node when a production cluster froze. Agent 3.2.6 acknowledged the other survivors; 4.0.1 acknowledged correctly in every run here. Whether that is a fix is HPE's to confirm.
- Whether acknowledging before the agent's heartbeat failover (8 min 48 s in one run) saves the VMs.
- The GFS2 `lvb free mismatch` consistency error seen after a power loss and journal replay, with `rgrplvb`: how often, and whether it matters. `fsck.gfs2 -n` found nothing afterwards.
- Why a guest was not restarted after a power loss in one 2.0 run with the cluster's setting on, when two 1.3 runs did restart.
- Adding a host back can fail in Morpheus's own Corosync Configure step, with `/tmp/.inherited_corosync.conf` or `/tmp/.corosync_authkey_b64` missing, leaving the host `provisioned` with corosync failed or the cluster record `failed`. More common after a removal that unmounted GFS2 first, on a small sample. Cause unknown. CLU-13 and MOR-06 flag what it leaves; deleting the half-added host and adding it again has worked each time.
- HA-06 on a real cluster with its manager on it: built from E2's case, not run there.
- A witness: a quorum device, on either layout. CLU-12 is built from the man page, and what Morpheus's Witness Worker option expects is unread.
- HA-03 reads the setting from heartbeat files, because the plugin API has no member for it. Whether that holds on 2.0, and with hosts in maintenance.
- `hvmVlanTrunks` on a network, and a real wrong-VLAN or trunk port: NET-12 and NET-13 have only seen a blank-VLAN port and a tagged one, on nested hosts where frames stop at the outer bridge. On a real trunk uplink, an exposed port should reach any VLAN the switch allows, which has not been seen.
- A temporary VLAN interface reaching a real gateway: VLANs with their own host interface are proven, but a temporary interface ARPs from 0.0.0.0, and not every gateway answers that.
- The VLAN test on a host with several bonds on different bridges, and on Open vSwitch at all. Only the 2.0 side with one bond has run. Whether Morpheus or HPE install `arping` on a 1.3 host is unknown.
- How often timesyncd hosts really land on the same pool member, and how far its single samples differ between hosts on one server: the warning threshold for them is chrony's.
- What `notHighAvailability` on the cluster record means. Morpheus has set it on every cluster seen so far, flipping it changed nothing visible, and HPE does not document it. HA-01 is a note showing the raw value until this is known.
- An advertised maximum frame size, libvirt certificates in use, and a physical boot disk with SMART: none has been seen on a host, so NET-14, HST-02 and HW-07 are built from documentation.
- Whether `Datastore.name` always matches the libvirt pool name, which HA-05 and GFS-09 rely on.
- Tenants reading history through the UI, a tier-3 login on the page, and the print window under the appliance's content security policy.
- Pin `corosyncToken` and `corosyncConsensus` for 2.0 once more clusters confirm the values.
- That Morpheus fills `ViewModel.request` for a plugin route, as POST-only refuses every request it does not describe, and that it rejects a POST with no CSRF token.
- That a probe gets back in time with a real slow libvirt or journal, and that `ps` left in a job behaves in a real freeze. Only a short limit on a healthy host has run: 60 s quick and full finished in 14 and 25 s, and a limit of 3 s returned in 5 s with DLM printed and `CUT=1`.
- The VLAN test in several runs, with a standby that really lacks the ring's VLAN. Whether 8 s back on the original uplink between runs is enough for corosync, on a real switch.
- That 5 minutes of corosync uptime covers an add-back's heartbeat: about 4 minutes was seen once.
- The reference popup and Help on an appliance, in Morpheus's own dark and light themes and its content security policy. Only a stub page has run them, in headless Edge.
- That the `ScriptsSpec` stubs behave as written: every script parses under `bash -n` on a 2.0 host, but the spec has not run, as the development workstation has no bash.

## Next

- **Automatic freeze recovery.** Wanted, and not safe yet. A first design relied on the storage array to prove a failed host was off, and live tests showed it cannot: a hung host's sessions vanish from the array within about a minute, then it logs back in on waking and its held writes land. Only something that can stop the host proves it. The next design powers the host off through its management controller, confirms that, then acknowledges, and refuses when the controller cannot be reached. It needs a controller login on every host and real hardware to test on. Worth revisiting once HPE has answered on agent versions: if 4.0.1 fixes the wrong-node acknowledgement, little may be left for it to do.
- **HPE's own checks:** show `hvmcli health` on 2.0 hosts, rather than repeat what it covers.
- Disks behind a RAID controller, with `smartctl -d megaraid,N`.
- Optional read-only switch integration, for a trunk's allowed VLANs.

## Known limits

- Below a window about 1,000 px wide, the Findings and Time tables scroll sideways inside their sections. Nothing is lost.
- The dark theme has not been checked on a real dark setup.
- The plugin's checks on a removal cannot stop someone using Morpheus's Remove directly.
- A hung unmount cannot be foreseen: nothing holds the mount when it happens. Remove safely stops there and asks for a reboot.
- The probe's GFS2 job can add a few stuck tasks in the 2 minutes before membership changes, because DLM still reads as healthy then. It is time-boxed, and cannot be avoided without skipping GFS2 reads.
- The probe's limit holds for what can be killed. A `t` read stuck in uninterruptible sleep outside GFS2, such as `journalctl` on a dead root disk, still holds it, and the host then reads as silent. So can a reader left behind by the job.
- A check run by hand with automatic checks off raises alarms that the next daily clean-up clears, though the finding may remain.
- An alarm's message is from when it was raised, so one like MOR-03's "last 900 s ago" goes stale. The tab has the current value.
- A VLAN test of many VLANs takes minutes: each run costs about 4 s and up to 8 s per VLAN on each uplink, plus the wait between runs.
- MOR-01 stays until Morpheus clears its own status message, which can outlast the event by a long time. That is the finding: the record is stale.
- GFS-10 reads the kernel log through `journalctl -t kernel`, so with a journal that does not persist across reboots it loses what a reboot clears.
- Tenancy relies on ownership; see docs/PERMISSIONS.md.
- The master tenant is found as tenant id 1, checked to be the master, because the plugin API cannot list tenants.
- Current results and job history are in memory. After a reload the all-clusters page shows the last stored summary, up to 15 minutes old, until the next check, and a cluster's tab shows it as a line of text.
- The master tenant cannot open a cluster another tenant owns, so all-clusters rows do not link to the tab. Their Report button works.
- Two appliance nodes each run the monitor, so they could overwrite each other's history row. The last write wins, and the next check corrects open findings.
- A name under 3 characters is not withheld from a report by name, because it would hit ordinary words. Findings that list VMs are withheld by check regardless. A cluster's own name stays in its report, even if it contains a tenant's name.
- Over 60,000 bytes of open findings, which takes several hundred, the newest are not tracked until there is room. The report says so. Retention is fixed in code (a year, 500 per cluster), not a setting.
- The API has no sharing field for networks, so a tenant reaching an operator-owned VLAN is a warning, not critical. NET-12 and NET-13 run on full checks, so a finding from a network being changed at that moment stays until the next.
- DLM-03 cannot fail as written: it is judged only when a host is not frozen, and frozen is the same test on the same lockspaces. So a stopped lockspace with no fence line in `dlm_tool status` raises no DLM finding, and shows only as FROZEN in the Hosts section.
- CLU-01 fails under 3 hosts even with a witness.
- An existing VM kept host-passthrough through a stop and start after the cluster's CPU model changed, so MIG-06 expects a rebuild. A nested VM cannot live-migrate while it has `migratable=off`.
- The passive VLAN check is a hint: learned addresses age out after minutes, so a quiet VLAN looks like a pruned one. It reads not checked, and only the VLAN test settles it.
- The HCI Ceph layout skips DLM and GFS2 checks and has no Ceph checks.
- The VLAN test cannot tell which bridge should carry a VLAN, only which one answered. A bridge whose uplink is not an active-backup bond (a single NIC, LACP or an Open vSwitch bond) is reported as not tested, and a VLAN on it reads as inconclusive.
- Hardware checks read through `ipmitool`, which Morpheus-built hosts do not include. With the tool present but its driver not loaded, FEN-01 reads "none" though a controller may exist.
- A name in the `autoFixClusters` setting applies to every cluster with that name, in any tenant, because names can repeat. Ids are exact.
