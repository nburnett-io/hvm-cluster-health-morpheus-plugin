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
FreezeWatch         a small read between checks, to find a freeze soon, within a budget of reads
StatusServer        the status URL, off until a port is set; StatusAnswer decides what it says, DetailAnswer what a signed read gets, ReaderKeys who may read it
Incidents           which findings are one incident, and what its alarm says
Impact              which tenants' VMs a finding reaches; operators only
ChangeWindows       planned work that holds paging, kept across reloads
HealthController    routes, tenancy, jobs, audit
FindingHistory      when findings appeared and cleared, per cluster, across reloads
HealthReport        the read-only report, with tenant data withheld
HealthAnalyticsProvider  the all-clusters page; a shell, its rows come from the fleet route
FleetCorrelator     what shows only across clusters: shared causes, one failure from an outage
CapacityTrend       a daily sample per cluster and the line through them, for the forecasts
Availability        freeze time, stalls and VM stops by month, per cluster and per tenant
PluginRow           one document per cluster in one row, for those last two
health-tab.js       the tab
health-pages.js     the all-clusters page and the report view
```

Most important checks only exist across hosts, so the checker takes all hosts at once rather than judging each alone.

## Decisions worth keeping

**Findings reach the outside through a URL.** Plugin alarms do not reach Morpheus alert rules (12 open alarms, 0 incidents), and plugin API 1.5.0 cannot create a check or an incident. A Morpheus Web Check on a URL does open an incident, and N-central cannot log in to a plugin route, so one URL with no login serves both. It says only words and counts, because it has no login: a stranger learns whether a cluster id is healthy and nothing else.

**A reader that wants more than words signs its request.** A dashboard cannot log in to a plugin route either. A token would cross a plain-HTTP port in clear, and the plugin would have to keep it. So the reader signs each request with an Ed25519 key, and the plugin holds only the public half. Anything unsigned answers as a path that does not exist. The answer holds ids, words, counts and times, and check names from the catalog. It never names a host, cluster, tenant or VM.

**What cannot be read is never plain OK on the status URL, except that a reload says so.** A cluster out of date reads STALE, and /alive reads STALE with automatic checks off. A cluster not yet checked since a load reads STALE too, but for the first 15 minutes, when it answers `HVM-HEALTH OK` with a second line `reloading`, and counts under `reloading=N`. We first chose the alert, then reversed it: every reload would open a Web Check incident on every cluster, which trains people to ignore them. The grace needs the monitor to be doing its job: no sweep for 5 minutes, or checks off, ends it. A freeze the watch holds is CRITICAL through it, and the watch does not wait for the first round. `/alive` gets no more than it had.

**The freeze watch reads a cluster whose check is only waiting.** It skipped any cluster with a check queued or running, and a running one will tell it what it reads. One still queued can be minutes away, as all are after a reload, and without this the grace would hide a freeze for as long as the queue. It costs reads inside the watch's budget.

**The status port's limit runs from when a request starts, and its queue is bounded.** Two threads answer, and each request has 5 s from when a thread takes it; the JDK reads the request line on that thread, so a client that stalls partway is cut off there and its connection closed. 0.5.0 counted the 5 s from when a request was queued and cancelled it, and the JDK never answers or closes a request that never ran, so each one held a connection for good. Now up to 32 wait, and past that the JDK closes the connection at once. The JDK's own request limits are system properties for the whole JVM, which Morpheus shares, so the plugin does not set them.

**A watch that has stopped is a monitor problem, so it uses PRB-04 and /alive rather than a line on a page.** Stuck agent calls can hold all its threads. When no read has been able to start for 2 minutes, /alive reads STALE and PRB-04's alarm says so in its own words. It is measured by reads refused rather than by the oldest read, as a cluster all in maintenance or always under check has an old read for good reasons.

**One appliance node runs the checks, by a lease in a row.** Each node used to run its own monitor, which on three nodes tripled the reads, and two could overwrite each other's rows. The plugin API has no compare-and-set, and none is faked: a node reads the row, and if it is its own or over 3 minutes old, writes its own and reads it back. Two nodes doing that in the same moment both act for one sweep, which costs reads and nothing worse. A row that cannot be read leaves the node running, as a freeze found twice beats one not found. A node standing by keeps no monitor running at all, so the watch has nothing to tick; a takeover is treated as a load, so it needs no path of its own, and what it holds cannot be older than what the other node wrote. The row carries what the status URL says, and the holder works that out with the same code that answers it, so a node standing by says what the holder would, a minute or two behind. It reads STALE by the limit the holder uses for its own sweep. Alarms stay with the holder too, so a person's check on another node cannot clear one the holder raised. The node's name appears on the master tenant's page and in the row, and never on the status URL or a cluster's tab.

**At 200 clusters, turns are set by id, not by the last run.** Clusters then keep their spread through a restart, instead of bunching up after a reload and staying bunched. A check that ran late rejoins its place, so one cluster does not run twice in a row and the gap is at most about 1.5 intervals.

**The first check after a reload stays a full one,** so 200 full checks land in the first few minutes, spread by id. A quick check first would clear the alarms of findings only a full check can see. The alternative is to keep those findings across a reload, which FindingHistory could carry, but that is not built.

**The freeze watch would rather be late than wrong.** A lockspace also stops for a moment when a host mounts or leaves, so one sighting does not count: the same host is read again 15 s later. And one host reading clear does not end a freeze, since a host that has just restarted has no lockspaces: it asks for a check, which reads every host. A host that does not answer proves nothing in either direction. Frozen means what `HostFacts.isFrozen` means, a stopped lockspace, never a fence line.

**The watch's reads all start on a tick,** the second host and the confirmation included, so nothing can go round the budget. A check already running means no read, as the check will tell it anyway. It is paid for on the host at one read a turn, and on the database at one host list per cluster per 5 minutes; the sweep hands it the clusters and settings.

**A host that stops answering is left alone for 5 minutes** by the watch if others answer. A check reports it, and the watch needs only one host that answers. Otherwise one silent host would cost 20 s and a second read on every third turn.

**The probe never blocks.** Nothing touches a GFS2 path unless every mounted filesystem has its lockspace listed and none is stopped, and even then from a background job
with a deadline. `timeout` cannot kill a process in uninterruptible sleep, so a timeout alone would not protect the probe. The same goes for what else can sleep that way: `gfs2_edit` on the device, and reading a process's arguments, which takes a memory lock a qemu stuck on its disk can hold.

**A probe that runs long loses everything, so it stops first.** Its caller discards all output from a run that outlasts it, which once hid a freeze behind a slow journal. So the plugin tells it a limit 30 s short of its own (120 s, or 60 s where a browser waits), and past that every read returns at once and the end says `CUT=1`. It takes a few seconds more. DLM and quorum are read first, so they always print. A host cut short names the freeze and counts for the emergency plan; for everything else, fixes above all, it has not answered, because a section it skipped would read as nothing wrong, or no VMs. It cannot prove a node down, and what it does show can still refuse one.

**A full probe's order: what every run needs, then what it can go without.** Thin, ports and busiest come before the disks behind a controller and HPE's check, as those two are the slow ones, with their own limits, and say when they were not read. So the disks stop 45 s before the probe's limit, to leave HPE's check its second look at DLM and its 30 s. A person's raw probe of a host has only 60 s, so it usually gets neither.

**HW-07 fails on a WHEN_FAILED value, on boot disks as on those behind a controller.** Anything but a dash means the drive itself has said an attribute went past its threshold. This includes `In_the_past`, so an old event on a boot disk now raises a failure and stays until the disk is replaced or smartctl stops saying so.

**The host OS, panic, taint, PCIe and network-timing checks detect and raise, never change the host.** HPE owns apt sources, needrestart, the panic sysctls, the GFS2 mount options and bond and bridge settings, and publishes little on them, so PKG-04, KRN-01, KRN-02, KRN-03 and NET-17, NET-18, NET-19 read `/proc`, `/sys` and config files on a full check, name what they found, and point at HPE. `kernel.panic` above zero is a warning though it has a real benefit, a clean reboot that can clear a fence wait, because a reboot loop on a recurring fault is the opposite risk and the choice is HPE's. A zero where a fault would show is never proof: HW-02 and KRN-03 both say so, because a firmware-first server can keep memory and PCIe errors from Linux.

**A layout is HCI Ceph only if its code says so.** Only `hci` or `ceph` as a word of a 1.3 code counts. Skipping DLM, GFS2 and the freeze watch on a real GFS2 cluster costs a missed freeze; reading a Ceph cluster as GFS2 costs a few reads, which come back with no lockspaces, and some findings to judge.

**FLT-01 leaves out a cluster in a change window, apart from a confirmed isolation failure.** Rolling work across clusters would otherwise raise it every time, and everyone knows the cause. What first appeared during the window stays out if it is still open just after. A confirmed NET-12 or NET-13 failure counts because no window holds those. Other findings that always page still page on the cluster, but do not count here, since a freeze during rolling work is still rolling work. The first, confirming, sighting is only a warning, so a window holds it and it does not count.

**HPE's own check is shown, not judged again.** `hvmcli health check` warned five times on a healthy cluster, four of them reading Pacemaker, which HVM masks. So HPE-01 only warns, the Pacemaker four are ignored by default through `hvmcliIgnore`, and no logic is copied from it. It runs last in a full probe, under the same rule as any GFS2 read, with DLM asked again just before because it may stat a mount. Its details can name hosts and VMs, so they stay in the tab's panel: the finding's text names only the checks.

**No SSH.** Everything runs through the Morpheus agent, like the rest of Morpheus.

**Hosts are matched to corosync nodes by ring address.** HVM 2.0 names nodes `host-<nodeid>`, so names do not line up. The
`morpheus_version` header in `corosync.conf` is a template version, 1.3 on 2.0 clusters too, so the layout comes from the
layout code only.

**The agent's quorum is the authority on 1.3 and 2.0, and corosync is not.** HPE states that on these layouts the agent's quorum
service, read at `https://127.0.0.1:7443/quorum`, decides quorum, failure detection and fencing, while corosync only carries
membership, and corosync `Quorate` reads No on a healthy two-node or stretch cluster. So the probe reads that endpoint and
`/opt/morpheus-node/.quorum-nodes` (on the host over HTTPS to localhost, never a GFS2 path), CLU-14 judges it, and CLU-15 checks
that the agent, corosync and Morpheus list the same members: a node corosync counts but the agent has dropped is the agent-side
counterpart of CLU-05 and the precondition suspected behind the founding freeze. CLU-03 and CLU-04 drop to information on a genuine
two-node corosync cluster and are kept on three or more, where a lost vote still means something and a stretch cluster cannot be
told apart from a single-site one. CLU-12's witness is a Distributed Worker over HTTPS, a member of agent quorum, not a corosync
qdevice. The endpoint's shape is only partly documented and has not been seen on a real two-host or stretch cluster, so all of this
parses defensively and reads not checked on any surprise, and none of it changes a host.

**Maintenance mode is `status == 'maintenance'`.** On 9.1.0.1 the member status is empty for plugins and the REST
`maintenanceMode` field has no counterpart on the model. Maintenance mode also removes the host's heartbeat file, measured at about
90 s after entering, and it returns 40-60 s after leaving, so a missing heartbeat is expected around then. The host stays in corosync
throughout, so powering it off stalls GFS2 on the others until it is acknowledged.

**A clean leave needs no fencing.** DLM fences a node that fails while it is in a lockspace. Unmounting GFS2 first leaves the lockspace, so nothing waits. That is why the order is unmount, dlm, corosync, then the nodelist.

**What a lost host does, measured on nested 1.3 and 2.0 clusters (agent 4.0.1).** The HVM agent's quorum check acknowledged the right node in every plain power-off, about 2 minutes in, including with the heartbeat file gone. GFS2 stalled for that long, and `dlm_tool ls` looks clean for most of it because membership only changes after the token and consensus timeouts (60 s + 72 s). Production's lasting freeze was reproduced only by denying the agent `fence_ack`, which forces that state but does not explain it: its acknowledgements went to the wrong nodes there. Then the agent's heartbeat failover ran `virsh shutdown` at 8 min 48 s (seen once), guests kept writing until the thaw, and qemu ended a second after it.

**The plugin calls Morpheus's remove rather than doing it.** An earlier build had its own eject, which reached the same result by hand-editing `corosync.conf`. When all goes well, Morpheus's remove unmounts, stops dlm and corosync, renames the file, then rewrites and reloads on the others: no freeze on either layout. What it lacks is stopping: it carried on when `umount` failed on a busy mount, leaving a lockspace abandoned, and when `umount` hung, which ended with the record gone, the others' `corosync.conf` not rewritten, and a fence wait. So the plugin makes those checks and does the unmount itself, with a time limit, while the host is still known to Morpheus. Then it calls `computeServer.removeHost`, and follows whatever HPE changes in the remove from then on. `force` stays off, so Morpheus's own checks still apply.

**A storage array cannot prove a host is off.** An automatic acknowledgement was built on this idea and taken out after testing. With the array's session list watched through logouts, power-offs, cuts and hangs, a hung host looks exactly like a dead one within about a minute. On waking it logged back in by itself, and writes it had held for minutes landed on the LUN. Only something that can stop a host proves it, so acknowledgement stays a person's decision.

**A fence line is not a freeze, but it is not harmless either.** `dlm_controld` went on retrying a fence for a node that left cleanly, with every lockspace running and I/O fine. Frozen means a stopped lockspace, so calling that a freeze would have invited an acknowledgement for nothing. But while that retry stands it blocks the next membership change on the lockspace, so a mount, unmount, new datastore, or a host joining or leaving would freeze it: DLM-06 is now worded as a latent freeze that says to do no host work until it clears, and that a fence result of 177 means no fence agent is loaded, not a fence that failed.

**A hung GFS2 unmount is waited out, not rebooted.** When an unmount hangs in `gfs2_gl_hash_clear`, kernel source shows the wait is bounded at about 600 s and then unmount finishes and leaves the lockspace cleanly, so rushing a reboot is the unclean departure that freezes the survivors. So the removal and its advice (Preflight, HostOps, BACKLOG) say to wait out Morpheus's own timeout and reboot only once the host is certainly out of use, rather than to reboot to "clear" it. The 600 s self-clear is read from source and needs one test run to confirm.

**Some outages are only visible after the fact, and some only from documentation.** HB-02 reads the agent's own journal for its all-paths-down VM shutdown rather than trying to catch it live: the destroy happens in about 2 minutes, faster than any poll, so the plugin names the aftermath from the 7-day log and leaves the lead time to HPE. FEN-02 and CLU-16 are built from an HPE advisory and from corosync source, not from anything seen on a host: the fence-agent defect is keyed on a cluster's creation version, which the plugin API does not expose, so FEN-02 warns on the leftover SCSI fence agent as a proxy; CLU-16 reads the `config_version` every host already reports and sharpens CLU-06 rather than adding a read. All three stay detect-and-recommend, and `docs/HPE-QUESTIONS.md` records the one answer each needs from HPE.

**The heartbeat is how the cluster's power-on setting is read.** The API has `autoRecoverPowerState` on the cluster and the plugin API does not. With it off the agents drop their failover job and stop writing heartbeats, so fresh files mean on. The cloud's setting did not decide restarts. So old files on every host are one finding, HA-03, not a failure per host, and a stale host among fresh ones is HB-01's. All agents dead looks the same to HB-01, and MOR-03 is what shows that.

**A host just added back is not judged on its old heartbeat.** A file can be left from its earlier incarnation, or take a while to reappear: HB-01 read a failure for about 4 minutes after an add-back. For 5 minutes after corosync starts, which covers an add-back with or without a reboot, a file older than that start says nothing. One written since and then gone stale is a real finding.

**A default on the model is not an answer.** `Cloud.autoRecoverPowerState` defaults to true and `Datastore.heartbeatTarget` to false, so a partial record reads as on, or as no target. The cloud is loaded in full, a value that cannot be read is not checked, and "no heartbeat target" also needs the hosts to show no heartbeat files.

**A port's VLAN comes from the bridge, not the VM's definition, and is joined to Morpheus on name and MAC together.** 2.0 is a VLAN-filtering linux bridge, and a network with no VLAN sits on its native VLAN 1. A bridge with filtering off still lists 1 PVID for its ports, which means nothing, so those read as not checked. On 1.3 an Open vSwitch port with no tag is a trunk of all VLANs, which was measured with traffic, and it is what a network with a blank VLAN or VLAN 0 builds. For a customer's VM that is a finding; for an operator's own it is not checked, since it may be there by design. A wrong VLAN is critical only when every network on it belongs to another customer tenant, because the plugin API has no sharing field and operators' networks may be shared.

**Nested-virtualization exposure is gated on the host CPU, not the checkbox.** SEC-01 warns only when the host's kvm module has nesting on and a customer VM's effective CPU block presents VMX or SVM: host-passthrough with nothing disabling it, or a named model that requires it. The per-VM "Enable Nested Virtualization" box exposes nothing on a named-model cluster, and there is no role for it, so the real control is the cluster CPU Model. It reads the domain XML, not inside the guest, and says it is built from HPE docs and one hand-edited VM. It pairs with SEC-02, the kernel-fix level, because exposure only bites on a kernel below the escape fix.

**A tenant names its own VM, and that name reaches `|`-delimited, `@@`-sectioned probe output.** So the probe strips control characters and turns `|` into `_` in a VM name before it becomes a field, and the parser accepts only an exact `@@<lowercase token>` as a section marker. A crafted name cannot then shift another field or forge a section, and because each marker resets its list, the genuine trailing section wins over an injected earlier one. Surfaced names are scrubbed and length-capped as a second line.

**The report withholds a finding's observation by allow-list.** It is carried only for a check known to name just hosts, storage, configuration and numbers; every other check, including one added later, is withheld. A deny-list let a new VM-naming check leak until someone remembered it; the allow-list fails safe. Scrubbing by known name stays on top, and the report still fails rather than leak when those names cannot be read.

**`cache=unsafe` is the one disk cache mode that is a failure.** It drops the guest's flushes, so a host crash can lose writes the guest believed were durable. The other modes (writeback, none, directsync) still honour flushes on GFS2 and are left alone: libvirt does not refuse any cache mode on a clustered filesystem, so the plugin is the only thing that would notice.

**A certificate nothing uses is not a finding.** Hosts migrate over SSH, so libvirt certificates only count with libvirt or qemu TLS on. An unread date stays empty, as `date -d ''` means today and would read as expiring now.

**Free space is compared only with disks known to be elsewhere.** Comparing a one-datastore cluster with its own VMs would alarm on a healthy cluster.

**The all-clusters page is an analytics page.** Plugin API 1.5.0 has no menu call and a controller's route cannot reach the navigation, so `AnalyticsProvider` is the only provider with a page and a menu entry of its own, and it has a master-only flag. The page is a shell whose rows come from the fleet route, so Morpheus's route permission and the plugin's tenant check both apply, and nothing rests on the flag alone.

**History lives in OperationData, one row per cluster.** That is the API's store for a provider's own data. A plugin can read its settings but has no call to write them, and each save to the cluster record would write back a whole record that Morpheus also changes. Morpheus rejects a row with no `keyValue` or `value`, though the API does not say they are required.

**A history row is replaced, not updated, and read back.** OperationData's setters do not mark fields dirty, so an update may save nothing. The API does not give the field's size either, so a document is capped at 60,000 bytes and read back: a record Morpheus cut short is removed, the old one stays, and the tab says so.

**A missing finding is not always a cleared one.** A finding only clears if the run could have seen it: its host answered, or for a cluster finding every host did, and a full-only finding needs a full check since the plugin loaded. A host no longer in the cluster clears its findings. Not checked holds a finding open. Otherwise a host going silent would close its findings and restart their first-seen times. A finding back within the hour is the same episode, with a count, and history that cannot be read is never overwritten.

**A report can leave the tenant that ran it, so it holds nothing about tenants.** Who a finding affects is left out. Findings that list VMs lose their observed text, because they can name VMs Morpheus does not know about. Any VM, tenant or network name Morpheus knows on the cluster, and any tenant that owns a network there, is withheld wherever else it appears, except in a title's own wording, and if those names cannot be read the report fails rather than risk one. Hints, fixes and raw output are left out. Host and storage names stay.

**A refusal says nothing about the cluster.** An audit entry lands in the caller's tenant, so one naming the cluster would tell a customer trying ids what it is called, and 400 against 403 would tell which exist. Refusals go to the appliance's log, and a missing cluster gets the same 403.

**A route has no verb, so the routes that change something check it.** The tab sends those by POST with a CSRF token. A link is a GET, which has none, so a user clicking one could otherwise acknowledge a node. `check` stays a GET: it changes nothing on a host, and only does what the automatic pass does.

**An alarm belongs to its incident, not its text.** An observation like "last 900 s ago" changes every run, and replacing the alarm for that loses its acknowledgement. A severity change does replace it, keeping the start. A finding that could not be judged keeps its alarm, so a failed read does not clear a critical one, and so does an alarm naming a host that did not answer, an isolation alarm naming a host whose ports were not read, and one only a full check can judge when none has run since the load.

**An isolation finding counts on its second sighting.** NET-12 and NET-13 run on every check so a tenant reaching another's network is not left for up to a day, but a network being changed can look like that for a moment, and these two always page. So the first sighting is a warning, with its own alarm and history entry because its title differs, as an alarm's text is from when it was raised and "seen once" must not outlive that. Two looks under a minute apart are one. A run that could not read a host's ports says nothing: its finding stands and the count waits, so a freeze cannot close one. The count is in memory, and after a reload the stored history says what was open, so a reload neither pages again nor audits again. That read is once per load, and only for a cluster with a finding.

**Every warning and failure has an alarm or is named in its root's alarm.** That replaces "every warning and failure is an alarm", because a freeze raised a dozen alarms for one thing to do. Only what goes to a person is grouped: results, history, the tab and paging all still see every finding. The cause table in `Incidents` is written from each check's meaning, so it is narrower than "anything during a freeze". A VM stuck on a host that is not frozen stands alone, as that points at storage, and so does whatever has its own action. Nothing that always pages is ever a symptom. A symptom that appears after the alarm was raised gets into its text by raising it again, while nobody has acknowledged it. Once someone has, it is on the tab only, because that acknowledgement is what grouping is meant to protect.

**A quick probe reads no more than a quick probe needs.** The port read has 12 s on a quick check against 25 on a full one, and is skipped in a freeze, when libvirt is the likeliest thing to hang. The busiest-VM read costs about 3 s, so a quick check does it only when a LUN is at 20 ms or the kernel's I/O pressure at 10%. Those are fixed in the probe, because it is sent before the standards are worked out, and getting them wrong only changes when a hint appears. Swap and steal share the storage sample's 2 s rather than adding a wait.

**A check reaching a new host raises its alarm again; a symptom or a host clearing does not.** A group's alarm is identified by check, not host. If that were all, acknowledging NET-01 on one host would silence the same fault on the next, which used to raise its own alarm. So the one change worth losing an acknowledgement for is a host the alarm does not name. Morpheus cannot be told a new alarm was acknowledged, and setting the flag would hide the new host, so the new alarm keeps the old one's start and says who acknowledged it.

**Reach is separate from the older impact text, and never says none about what was not read.** Morpheus stops reporting VMs as running on a silent host, so its VMs count as state not known. A disk with no datastore, or on a datastore name no host has as a pool, may be on the one in question. An empty VM list with no host answering is unknown, not an empty cluster. Reach goes to operators only, and a test holds `HealthReport` to carrying none of it, with no name list to scrub it by.

**A change window holds paging, not detection, and takes nothing running to end.** `Paging.held` compares the time, so a window nobody closes ends with no thread or store involved. Windows are read into memory once a minute, whatever the number of clusters, because the status URL must not reach the database per request; that also brings a second appliance node in line within a minute. If the store cannot be read after a reload, nothing is held, so a cluster pages rather than stays quiet. A window that could not be saved is refused rather than lost at the next reload, and the length is refused above the limit rather than cut, so nobody believes paging is held for longer than it is.

**A filter whose meaning is undocumented has to earn its place.** Listing the whole alarm category per cluster is quadratic, and `DataQuery` has a `:` operator, but no source says exactly what it matches. A filter that dropped an open alarm would raise it again on every run, on every cluster. So the plugin compares the filtered and whole lists on the first cluster that has alarms, and uses the filter only if they match, with the prefix still checked on what comes back.

**A check that cannot run has to say so, and a typo is not a reason to stop.** A mistake in the site standards once stopped every automatic check, with only a line in the log. Now checks carry on with the built-in standards and PRB-03 warns, because freeze detection matters more than the override. A check that fails for any other reason raises PRB-02 on that cluster and backs off to its normal interval.

**Availability only counts a freeze as long as something saw it.** With the watch on, a check's single sighting opens nothing, as a lockspace also stops for a moment when a host mounts or leaves: if it is a freeze the watch says so, dated from that sighting. A freeze the watch held and a check ended is counted whole. One left open across a reload is different: nothing watched its end, so a long silence after it was last seen stays unknown rather than counting as frozen.

**A VM's stop is joined to its tenant by libvirt's name, not by text.** VM-06's text uses Morpheus's label, which can repeat across tenants, and it has room for 8 VMs. A customer's availability CSV must not show another tenant's crash, so the count comes from the host's facts, by the name Morpheus holds as `externalId`.

**A shared cause is about arrivals.** PKG-03 open on 50 clusters for a year is not one event, so a finding counts only if the history says it first appeared in the last 10 minutes, and the clusters' arrivals fall within 10 minutes of each other. A finding open when a cluster's history began says nothing about its age, and a check new in this version first appears on every cluster at once, so neither counts, nor does anything in the first 30 minutes after a load. It then lasts as long as the clusters have the finding, rather than clearing 10 minutes later with the cause still there. Automatic groups only count for what they could explain: nearly every cluster at a site shares a time server, and that says nothing about two bonds. A group in the setting is the operator's own statement, and counts for any check. It stays off cluster tabs, as it names clusters whose owners can be different tenants.

**A forecast's sample holds only what the run could vouch for.** Morpheus stops reporting VMs as running on a silent host, and a frozen datastore has no usage to read, so such a run leaves those parts out, and a later check that day fills them in. A dip to zero would bend the line for 90 days. The line is of what is growing, against the limit as it is now, so a host added last week counts in full. It is read once a day and written when a day's sample gains something, so 200 clusters cost a few hundred writes a day.

**Availability only claims what was seen.** Time counts as observed if a host answered and the last check was recent, up to 3 intervals or 15 minutes. The rest is unknown, and never up. A freeze is written when it starts. If the first look after a long silence finds it over, it counts up to when it was last seen, and the rest stays unknown, unless FreezeWatch saw it to the end. Who it reached comes from the last run that could vouch for the VMs, since Morpheus's list goes wrong during the freeze. With nobody known at all, its time is unattributed, which is not the same as nobody. These rows hold tenant and VM names, which is their point, and no host, storage or finding text.

**A tenant's availability file is built from that tenant's entries alone.** It is meant to leave, so rather than scrub a shared document, it never reads anyone else's, and a cluster goes by its id because a cluster's name can hold another tenant's. An export whose figures cannot be read fails, rather than hand a customer an empty record.

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

**Installing ipmitool is the one fix that adds software, so it is never automatic.** It installs new packages only: apt's own simulation is read first, and an install that would also upgrade or remove a package is refused, so it cannot carry a hypervisor's libraries forward by the way. needrestart is held off, because after any apt run it restarts every service still on an old library, whoever updated it, and on a cluster member that can be corosync, dlm or libvirt with GFS2 mounted. It ran after the first live install, before this was added. Morpheus-built 1.3 hosts lack ipmitool; 2.0 hosts ship it from HPE's repository and their agent polls the controller with it every minute.

**A controller reset is refused during a freeze.** It restarts the iDRAC or iLO only, and the host carries on, but while a lockspace waits on fencing the controller may be what proves a failed host is off. It does not wait for the controller to come back, because an operation holds its cluster, and a fence acknowledgement or a freeze check queued behind a ten-minute wait is the outage this plugin exists to shorten.

**One answer from the controller gates every other ipmitool read in the probe.** A wedged controller, the very case HW-08 is for, held each read to its limit and spent the full probe's whole budget, so the host's later sections came back cut.

**A controller that is not there reads as not checked, never as healthy.** On a virtual machine ipmitool installs and finds nothing. The panel then shows no status at all rather than empty sections, and HW-08 says no controller answered.

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

**Queueing is read from multipathd, not dm.** dm's features line says `queue_if_no_path` for a finite `no_path_retry` too. Only multipathd's "on" means for ever. STO-02 reports the window in seconds (`retry x polling + 1`) and never fails; it warns only when the window is under the corosync token or over the `noPathRetryPolicy` ceiling, as no vendor ties a value to GFS2. `N sec` from `%Q` means no path at that moment, and `off` with no active path means the retries are exhausted: both were false passes before.

**Storage is vendor-neutral.** No array is assumed. A value that depends on the array is a per-vendor profile (`StorageProfiles`) keyed on the trimmed INQUIRY vendor and product the host reports; an unknown vendor is NOT CHECKED on any vendor-specific rule, never judged against another vendor's value. The profiles are built from documentation and say so until a real array confirms them. Pure-only specifics (detect_prio, the Pure udev rules, the Pure stanza) stay in the Pure plugin.

**An ALUA standby path is healthy.** Its checker reads `ghost` (tur sense 04/0b), which the old STO-01 treated as a failed path, a permanent false alarm on Alletra, Nimble and Peer Persistence legs. A map still fails with no path at all, or with no active path (only standby left).

**Transport is read per path, and Fibre Channel is not iSCSI.** Many storage checks ran only when iSCSI sessions existed, so on an FC host they passed silently and the STO-01 fix ran an iSCSI rescan. The probe now detects transport from multipathd `%P` and `/sys/class/fc_host`; STO-03 is NOT CHECKED on an FC host, STO-04 judges multipathd wherever maps exist, STO-09 reads the FC fabric, and the fix rescans by transport. Every FC path is built from documentation: the test hosts are iSCSI.

**Automatic fixes need the cluster named.** The two switches are global, so a cluster named nowhere gets nothing. A number in the list is only ever an id, so a
cluster with a number for a name cannot be picked by another's id. The list and the switches are read again when a fix runs: taking a cluster off stops what was
about to run. A person pressing Fix is not bound by it.

**Quick checks keep the last full answer** for what they do not run, so alarms from full-only checks do not come and go.

## What the tab's classes teach

Morpheus's stylesheet and Bootstrap define `.banner`, `.row`, `.muted` and others globally, and a plugin tab inherits them.
Every class in the tab is `hh-` prefixed. Morpheus serves plugin assets with a one-year cache under a fixed name, so the tab script's
URL carries the plugin version: a new script needs a new version.
