# Design overview

## The failure this was built around

A three-host HVM cluster with one GFS2 datastore on an iSCSI LUN. One host was put in maintenance mode and deleted in Morpheus, to move
it to a new cluster on a newer layout. 396 seconds later it left uncleanly: the survivors logged that they never received its leave
message. A test run where Morpheus's unmount hung took the same 396 seconds, so that is the likely path, though the host itself was rebuilt
before anyone could look. What followed, in order:

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
CheckCatalog        what each check id means, for the tab's popups and Help, and docs/CHECKS.md
Fixes               what may change, the guards, the fence plan
HealthContext       one cluster through the plugin API
HealthMonitor       automatic checks, alarm, automatic fixes
HealthController    routes, tenancy, jobs, audit
FindingHistory      when findings appeared and cleared, per cluster, across reloads
HealthReport        the read-only report, with tenant data withheld
HealthAnalyticsProvider  the all-clusters page; a shell, its rows come from the fleet route
health-tab.js       the tab
health-pages.js     the all-clusters page and the report view
```

Most important checks only exist across hosts, so the checker takes all hosts at once rather than judging each alone.

## Decisions worth keeping

**The probe never blocks.** Nothing touches a GFS2 path unless every mounted filesystem has its lockspace listed and none is stopped, and even then from a background job
with a deadline. `timeout` cannot kill a process in uninterruptible sleep, so a timeout alone would not protect the probe. The same goes for what else can sleep that way: `gfs2_edit` on the device, and reading a process's arguments, which takes a memory lock a qemu stuck on its disk can hold.

**A probe that runs long loses everything, so it stops first.** Its caller discards all output from a run that outlasts it, which once hid a freeze behind a slow journal. So the plugin tells it a limit 30 s short of its own (120 s, or 60 s where a browser waits), and past that every read returns at once and the end says `CUT=1`. It takes a few seconds more. DLM and quorum are read first, so they always print. A host cut short names the freeze and counts for the emergency plan; for everything else, fixes above all, it has not answered, because a section it skipped would read as nothing wrong, or no VMs. It cannot prove a node down, and what it does show can still refuse one.

**No SSH.** Everything runs through the Morpheus agent, like the rest of Morpheus.

**Hosts are matched to corosync nodes by ring address.** HVM 2.0 names nodes `host-<nodeid>`, so names do not line up. The
`morpheus_version` header in `corosync.conf` is a template version, 1.3 on 2.0 clusters too, so the layout comes from the
layout code only.

**Maintenance mode is `status == 'maintenance'`.** On 9.1.0.1 the member status is empty for plugins and the REST
`maintenanceMode` field has no counterpart on the model. Maintenance mode also removes the host's heartbeat file, measured at about
90 s after entering, and it returns 40-60 s after leaving, so a missing heartbeat is expected around then. The host stays in corosync
throughout, so powering it off stalls GFS2 on the others until it is acknowledged.

**A clean leave needs no fencing.** DLM fences a node that fails while it is in a lockspace. Unmounting GFS2 first leaves the lockspace, so nothing waits. That is why the order is unmount, dlm, corosync, then the nodelist.

**What a lost host does, measured on nested 1.3 and 2.0 clusters (agent 4.0.1).** The HVM agent's quorum check acknowledged the right node in every plain power-off, about 2 minutes in, including with the heartbeat file gone. GFS2 stalled for that long, and `dlm_tool ls` looks clean for most of it because membership only changes after the token and consensus timeouts (60 s + 72 s). Production's lasting freeze was reproduced only by denying the agent `fence_ack`, which forces that state but does not explain it: its acknowledgements went to the wrong nodes there. Then the agent's heartbeat failover ran `virsh shutdown` at 8 min 48 s (seen once), guests kept writing until the thaw, and qemu ended a second after it.

**The plugin calls Morpheus's remove rather than doing it.** An earlier build had its own eject, which reached the same result by hand-editing `corosync.conf`. When all goes well, Morpheus's remove unmounts, stops dlm and corosync, renames the file, then rewrites and reloads on the others: no freeze on either layout. What it lacks is stopping: it carried on when `umount` failed on a busy mount, leaving a lockspace abandoned, and when `umount` hung, which ended with the record gone, the others' `corosync.conf` not rewritten, and a fence wait. So the plugin makes those checks and does the unmount itself, with a time limit, while the host is still known to Morpheus. Then it calls `computeServer.removeHost`, and follows whatever HPE changes in the remove from then on. `force` stays off, so Morpheus's own checks still apply.

**A storage array cannot prove a host is off.** An automatic acknowledgement was built on this idea and taken out after testing. With the array's session list watched through logouts, power-offs, cuts and hangs, a hung host looks exactly like a dead one within about a minute. On waking it logged back in by itself, and writes it had held for minutes landed on the LUN. Only something that can stop a host proves it, so acknowledgement stays a person's decision.

**A fence line is not a freeze.** `dlm_controld` went on retrying a fence for a node that left cleanly, with every lockspace running and I/O fine. Frozen means a stopped lockspace. Calling that a freeze would have invited an acknowledgement for nothing.

**The heartbeat is how the cluster's power-on setting is read.** The API has `autoRecoverPowerState` on the cluster and the plugin API does not. With it off the agents drop their failover job and stop writing heartbeats, so fresh files mean on. The cloud's setting did not decide restarts. So old files on every host are one finding, HA-03, not a failure per host, and a stale host among fresh ones is HB-01's. All agents dead looks the same to HB-01, and MOR-03 is what shows that.

**A host just added back is not judged on its old heartbeat.** A file can be left from its earlier incarnation, or take a while to reappear: HB-01 read a failure for about 4 minutes after an add-back. For 5 minutes after corosync starts, which covers an add-back with or without a reboot, a file older than that start says nothing. One written since and then gone stale is a real finding.

**A default on the model is not an answer.** `Cloud.autoRecoverPowerState` defaults to true and `Datastore.heartbeatTarget` to false, so a partial record reads as on, or as no target. The cloud is loaded in full, a value that cannot be read is not checked, and "no heartbeat target" also needs the hosts to show no heartbeat files.

**A port's VLAN comes from the bridge, not the VM's definition, and is joined to Morpheus on name and MAC together.** 2.0 is a VLAN-filtering linux bridge, and a network with no VLAN sits on its native VLAN 1. A bridge with filtering off still lists 1 PVID for its ports, which means nothing, so those read as not checked. On 1.3 an Open vSwitch port with no tag is a trunk of all VLANs, which was measured with traffic, and it is what a network with a blank VLAN or VLAN 0 builds. For a customer's VM that is a finding; for an operator's own it is not checked, since it may be there by design. A wrong VLAN is critical only when every network on it belongs to another customer tenant, because the plugin API has no sharing field and operators' networks may be shared.

**A certificate nothing uses is not a finding.** Hosts migrate over SSH, so libvirt certificates only count with libvirt or qemu TLS on. An unread date stays empty, as `date -d ''` means today and would read as expiring now.

**Free space is compared only with disks known to be elsewhere.** Comparing a one-datastore cluster with its own VMs would alarm on a healthy cluster.

**The all-clusters page is an analytics page.** Plugin API 1.5.0 has no menu call and a controller's route cannot reach the navigation, so `AnalyticsProvider` is the only provider with a page and a menu entry of its own, and it has a master-only flag. The page is a shell whose rows come from the fleet route, so Morpheus's route permission and the plugin's tenant check both apply, and nothing rests on the flag alone.

**History lives in OperationData, one row per cluster.** That is the API's store for a provider's own data. A plugin can read its settings but has no call to write them, and each save to the cluster record would write back a whole record that Morpheus also changes. Morpheus rejects a row with no `keyValue` or `value`, though the API does not say they are required.

**A history row is replaced, not updated, and read back.** OperationData's setters do not mark fields dirty, so an update may save nothing. The API does not give the field's size either, so a document is capped at 60,000 bytes and read back: a record Morpheus cut short is removed, the old one stays, and the tab says so.

**A missing finding is not always a cleared one.** A finding only clears if the run could have seen it: its host answered, or for a cluster finding every host did, and a full-only finding needs a full check since the plugin loaded. A host no longer in the cluster clears its findings. Not checked holds a finding open. Otherwise a host going silent would close its findings and restart their first-seen times. A finding back within the hour is the same episode, with a count, and history that cannot be read is never overwritten.

**A report can leave the tenant that ran it, so it holds nothing about tenants.** Who a finding affects is left out. Findings that list VMs lose their observed text, because they can name VMs Morpheus does not know about. Any VM, tenant or network name Morpheus knows on the cluster, and any tenant that owns a network there, is withheld wherever else it appears, except in a title's own wording, and if those names cannot be read the report fails rather than risk one. Hints, fixes and raw output are left out. Host and storage names stay.

**A refusal says nothing about the cluster.** An audit entry lands in the caller's tenant, so one naming the cluster would tell a customer trying ids what it is called, and 400 against 403 would tell which exist. Refusals go to the appliance's log, and a missing cluster gets the same 403.

**A route has no verb, so the routes that change something check it.** The tab sends those by POST with a CSRF token. A link is a GET, which has none, so a user clicking one could otherwise acknowledge a node. `check` stays a GET: it changes nothing on a host, and only does what the automatic pass does.

**An alarm belongs to its finding, not its text.** An observation like "last 900 s ago" changes every run, and replacing the alarm for that loses its acknowledgement and start time. A severity change does replace it. A finding that could not be judged keeps its alarm, so a failed read does not clear a critical one.

**A check that cannot run has to say so, and a typo is not a reason to stop.** A mistake in the site standards once stopped every automatic check, with only a line in the log. Now checks carry on with the built-in standards and PRB-03 warns, because freeze detection matters more than the override. A check that fails for any other reason raises PRB-02 on that cluster and backs off to its normal interval.

**The report is built in the browser from JSON, not served as a page or a file.** How Morpheus treats a controller's HTML, or a response with a CSV content type, is undocumented. A CSV cell that would run as a spreadsheet formula gets a leading apostrophe, as findings can hold host output.

**After a rejoin, reboot before leaving maintenance mode.** With services back but GFS2 unmounted, leaving maintenance made the agent run `reboot -f` twice, left it unmounted for 10 minutes once, and mounted it in 38 s once. A reboot in maintenance brought GFS2 back every time.

**The agent restarts a lost host's VMs when the cluster's power-on setting is on.** With it on, a guest came back elsewhere at between about 3 and 5 minutes. With it off, not in 9.

**A record being removed is not a host.** A failed add can leave a record in `deprovisioning` indefinitely. Counting it blocked every fix on "another host did not answer", so it is reported on its own (MOR-04) instead.

**The VLAN test runs detached.** It moves the active uplink, which can cut off the very agent that started it, so it runs under `setsid`, writes
to a file the plugin polls, and has a timer that puts the uplink back whatever happens. The test and its timer hold a lock on the host, because a retry while the uplink was moved would save the standby as the original. A test that produces no results is a failure,
never a pass: an early build reported success after a shell quoting error stopped it testing anything. A missing or failing `arping` is not a VLAN failing either:
it is reported as not tested, and a missing one stops the test before anything moves.

**The VLAN test goes in runs short enough for corosync.** A standby without the ring's VLAN loses the host's membership for as long as it is active, with GFS2 mounted. An uplink gets 4 s to settle and at most 8 s per VLAN, so at a 60 s token a run tests 3 VLANs, keeping within half of it, then puts the uplink back before the next. A token too short for one VLAN is refused.

**A gateway silent on every uplink is reported differently** from one silent on one uplink. The second is a pruned trunk; the first says more
about the gateway.

**Every bond on a bridge is tested, because nothing on a host says which should carry a VLAN.** HPE's 1.3 script can put management, compute and overlay on separate
bridges, each on its own uplink. So a VLAN that answers on one bond is carried by it, silence on another bond means nothing, and a bridge that could not be tested
makes a silent VLAN inconclusive rather than failed. A bond on no bridge is left alone: it is usually storage.

**Not checked is not the same as not applicable, or fine.** A check that applies but cannot be answered, usually for want of a tool on the host, gets its own status.
It raises no alarm and readiness lists it, because "Hardware: NA" read as if the hosts had no hardware to check.

**Tenancy is by ownership.** See [docs/PERMISSIONS.md](docs/PERMISSIONS.md).

**The reference is held to the code by reading its source.** Many ids only exist in a branch no test reaches, so `CheckCatalogSpec` parses `HealthChecker` and `HealthMonitor` for them, in both directions. It is prose written from the code, so a test can say an entry exists and names its standards key, and not that it is right: changing a check's meaning means changing its entry. A person reads it at 3am, so what was measured, or not, is said in the entry.

**The popup is a native `<dialog>`.** `showModal` puts it above whatever Morpheus's page does with stacking and clipping, and brings Esc, a focus trap and focus returning to the id. The tab has never set a background, inheriting the page's in either theme, and a modal cannot do that: it takes the nearest opaque colour up the page when it opens. That goes through the element's style property, because a content security policy can refuse a style attribute.

**LLDP proves cabling, not VLANs.** Switches commonly advertise only the native VLAN, not a trunk's allowed list.

**CPU recommendations come from `usable='yes'` models, in the host's family.** `virsh cpu-baseline` answers with whichever model is closest
plus added features, which can be an unrelated Atom server model: useless as a model name alone. And libvirt's versioned names (`-v5`) are not
in Morpheus's list.

**Host drift uses offsets to shared time servers.** Two hosts measuring one server compares their clocks directly. Servers are matched by
address, as a pool's members are different clocks under one name, so hosts on different members are not compared: that is not an alarm, and the finding says it
shows neither drift nor agreement. Hosts on one server form a group, and the spread is never taken across groups.

**Time is read from whichever daemon runs.** 2.0 hosts run chrony, Morpheus-built 1.3 hosts `systemd-timesyncd`, which reports only one sample per host, from its
last poll, up to about half an hour old and corrected since. So what it shows warns, and only a host no server has answered fails. Its `NTP=` also turns off its
fallback servers, unlike chrony's `prefer`, so the `ntp-sources` fix puts the old file back if no listed server answers.

**Queueing is read from multipathd, not dm.** dm's features line says `queue_if_no_path` for a finite `no_path_retry` too. Only multipathd's "on" means for ever.

**Automatic fixes need the cluster named.** The two switches are global, so a cluster named nowhere gets nothing. A number in the list is only ever an id, so a
cluster with a number for a name cannot be picked by another's id. The list and the switches are read again when a fix runs: taking a cluster off stops what was
about to run. A person pressing Fix is not bound by it.

**Quick checks keep the last full answer** for what they do not run, so alarms from full-only checks do not come and go.

## What the tab's classes teach

Morpheus's stylesheet and Bootstrap define `.banner`, `.row`, `.muted` and others globally, and a plugin tab inherits them.
Every class in the tab is `hh-` prefixed. Morpheus serves plugin assets with a one-year cache under a fixed name, so the tab script's
URL carries the plugin version: a new script needs a new version.
