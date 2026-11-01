# Questions for HPE

Each question below decides a default this plugin currently has to guess. The plugin never changes a host by itself, so
every default here is detect-and-recommend: HPE's answer only changes what a check says, or how loudly. This sheet is
internal and safe to share with HPE as written: it names no host, address, customer or site, and quotes no private
repository. "Today" is what the plugin does until HPE answers.

## All-paths-down (APD) VM shutdown — HB-02

1. **What exactly does the agent log when APD shuts VMs down, and where?** The one line seen is
   `Shutting down all VMs to protect data integrity`, with a precursor counting `heartbeat datastore paths ... unhealthy
   ... for N checks`. Is that the wording on current agents, and under which systemd unit or syslog identifier?
   - Decides: the 7-day search pattern HB-02 uses, and whether it can be scoped to one unit instead of the whole journal.
   - Today: HB-02 greps the whole host journal for those phrases and `APD threshold`, and reads NOT CHECKED when it cannot
     finish in time rather than assuming nothing happened.

2. **Does a blocked write count toward APD the same as a failed write, and what is the real timer?** HPE documents about
   6 failed heartbeat-write cycles (~2 minutes). A freeze in testing instead destroyed VMs at about 8 min 48 s, where writes were
   blocked on a stopped lockspace rather than failing outright, so the two may behave differently.
   - Decides: whether HB-02's aftermath text and the heartbeat age warning can state a single budget operators can plan a
     storage change inside.
   - Today: HB-02 reports the sighting after the fact and does not claim a timer; it points here.

3. **When every host loses the heartbeat datastore at once, should the agent still destroy VMs on all of them?** That
   pattern points at storage, not at one host being isolated, and destroying everywhere turns a stall into a fleet outage.
   - Decides: nothing in the plugin changes a host, but the answer tells operators whether a simultaneous APD is expected
     behaviour or something to raise with HPE.

## SCSI fence-agent defect — FEN-02

4. **Is a cluster created on Manager 8.0.11 or 8.0.12 still affected by advisory a00156214 after an upgrade, and how does
   an operator read a cluster's creation version?** The advisory says the defect follows the cluster even after upgrade,
   and that older clusters are unaffected. The plugin API does not expose when a cluster was created.
   - Decides: whether FEN-02 can warn on the real signal (creation version) or must stay on the host-side proxy (the
     presence of `/usr/sbin/fence_scsi_hpevm`).
   - Today: FEN-02 warns when that script is present on a 1.3/2.0 host and asks the operator to confirm the creation
     version with HPE.

5. **Is hotfix 8.0.12-2 or a layout upgrade the supported remedy, and is the interim sed edit of the fence agent ever
   something a monitoring tool should suggest?** The plugin will not offer the hand edit as a fix under any circumstances.
   - Decides: FEN-02's recommended action.

## Ghost CPG member from a stale config_version — CLU-16

6. **Do HPE's corosync builds for 24.04 and 26.04 carry the fix for the stale-config_version ghost CPG member (corosync
   PR #828, released in 3.2.0)?** A node that was down during a host add or remove returns with an older config_version,
   and on a public reproduction it rejoined and left a ghost member that hung the cluster filesystem until corosync was
   restarted everywhere. This is built from corosync source and that reproduction, not seen on HVM.
   - Decides: whether CLU-16 should stay a WARN (sharpening CLU-06) or whether a stale rejoined member warrants more.
   - Today: CLU-16 warns and tells the operator not to start the stale host until its file matches the others.

## Patching an HVM host — PKG-01

7. **Which is the supported way to patch an HVM host: the rolling cluster update only, or `apt upgrade` per host in
   maintenance mode?** HPE's own 9.1 docs conflict: the upgrade guide forbids `apt upgrade` outside the rolling update,
   while the lifecycle page permits it per host in maintenance mode.
   - Decides: whether PKG-01 can recommend a patching method again.
   - Today: PKG-01 reports version drift but recommends no method, and points to HPE's procedure.

## Agent upgrade and maintenance mode — PKG-03

8. **Is an agent upgrade safe to run on a host that is not in maintenance mode, on current versions?** One agent upgrade
   triggered failover when run outside maintenance; HPE fixed that case in 8.0.13-2, but whether later versions are safe
   outside maintenance is not stated.
   - Decides: whether PKG-03 can drop its "maintenance window, one host at a time" caution.
   - Today: PKG-03 keeps flagging old agents and advises upgrading through HPE, in a maintenance window, one host at a time.

## GFS2 journals — GFS-06

9. **Does Morpheus always run `gfs2_jadd` when it adds a host, so a journal count below the host count always means that
   step did not finish — or can a supported cluster run with fewer journals than hosts?** HPE's current mkfs makes 32
   journals, but older datastores may carry fewer.
   - Decides: whether GFS-06 should treat a low count as a failed add-host step (as it now says) or as a routine state.
   - Today: GFS-06 warns and says it usually points at an add-host step that did not finish, not something to do by hand.

## Carried over from the other research tracks

These decide defaults owned by checks outside this release note, and are listed so the sheet is whole:

- **`no_path_retry` on a GFS2 datastore.** The intended value, polling interval and iSCSI timers, so the storage checks
  can judge against a real target rather than a per-vendor guess.
- **Do HPE's 6.8-series kernels carry the GFS2/DLM lock-conversion fix (and the two KVM CVE fixes)?** This sets the kernel
  floor the DLM and tenant checks warn below.
- **Is a manual `fence_ack` ever correct, and when?** HPE's troubleshooting says not to acknowledge a fence manually unless
  Support directs it, while the monitoring page says manual intervention may be needed. The emergency action stays a
  person's decision; the wording should match HPE's.
- **Does a hand-edited host setting survive the agent, and does it affect support?** Several checks read settings an
  operator may have set by hand (iSCSI startup, timers, time sources); whether the agent reverts them, and whether editing
  them affects support, changes whether a drift is worth flagging.
- **The supported minimum hosts for a single-site cluster, and the support stance on running at two with a witness.** This
  sets the host-count floor.

## Not a question

HPE does not support Ceph as HVM storage (notice, 2026). The plugin stays quiet on an HCI Ceph cluster: it skips the DLM,
GFS2, heartbeat, thin-LUN, APD (HB-02) and fence-agent (FEN-02) checks there, and has no Ceph checks of its own. Confirmed
in this release.
