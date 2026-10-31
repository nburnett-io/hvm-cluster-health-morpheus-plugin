# Standards

Each cluster is checked against the baseline for its layout, with your site standards on top. The tab lists every value in force
and where it came from.

## Where the values come from

HPE publishes little on this subject, so the plugin does not pretend otherwise. Every value carries one of these sources:

| Source | Meaning |
|---|---|
| `hpe` | Stated in HPE Morpheus documentation, or in the layout's own definition |
| `upstream` | corosync, DLM or GFS2 documentation |
| `morpheus` | How a Morpheus-deployed cluster of that layout is built, as observed. Not a vendor recommendation |
| `plugin` | This plugin's judgement: a reasonable default, for you to override |
| `site` | Set by you in the plugin setting |
| `cluster` | Set for one cluster on its tab |

What HPE documentation does state: an HA cluster needs three hosts, MPIO for LUN-backed GFS2 datastores, TCP 22 between
hosts, and the OS each layout is built for. Sources disagree on two-node GFS2 and fencing, so treat `minHosts` as a judgement on anything
newer than the documentation.

## Layouts

| Layout code | Version | OS | Network |
|---|---|---|---|
| `mvm-1.3-ubuntu-24.04-std-morpheus-amd64` | 1.3 | 24.04 | Open vSwitch |
| `mvm-1.3-ubuntu-24.04-morpheus-amd64` | 1.3, HCI Ceph | 24.04 | Open vSwitch; DLM and GFS2 checks do not apply |
| `mvm-2.0-virtswitch-ubuntu-26.04-morpheus-amd64` | 2.0 | 26.04 | VirtSwitch, built with `hvmcli` |

## Values

| Key | Default | Source | |
|---|---|---|---|
| `minHosts` | 3 | hpe | |
| `mpio` | true | hpe | Check multipath paths |
| `fencingEnabled` | true | upstream | `enable_fencing` in dlm.conf |
| `consensusRatio` | 1.2 | upstream | corosync.conf(5): consensus at least 1.2 x token |
| `corosyncToken` | 60000 on 1.3 | morpheus | Not pinned on 2.0 yet |
| `corosyncConsensus` | 72000 on 1.3 | morpheus | Not pinned on 2.0 yet |
| `gfs2WarnPct`, `gfs2FailPct` | 80, 90 | plugin | Datastore usage |
| `heartbeatMaxAgeSec` | 120 | plugin | Files are rewritten about every 20 s |
| `agentMaxAgeSec` | 300 | plugin | |
| `maxHostDriftMs` | 50 | plugin | Between hosts reading the same time server, by address |
| `maxClockSkewSec` | 5 | plugin | Between hosts sharing no time server: a coarse comparison, which shows neither drift nor agreement in milliseconds |
| `ntpServers` | not set | plugin | Your time sources. Normally defined per cluster on the tab |
| `maxNtpOffsetMs` | 100 | plugin | Offset from the time source |
| `clusterCpuModel` | not set | plugin | The CPU model a cluster must use, HVM's equivalent of EVC |
| `ringRttWarnMs` | 5 | plugin | Between corosync ring addresses |
| `storageWarnMs`, `storageFailMs` | 20, 100 | plugin | Average I/O wait per LUN over 2 s |
| `gfs2StatWarnMs` | 500 | plugin | Time to read a file's attributes, which takes a cluster lock |
| `selDays` | 7 | plugin | How far back serious hardware log events count |
| `hungTasksWarn` | 1 | plugin | Kernel reports in the last hour |
| `iscsiErrorsWarn` | 1 | plugin | Session errors in 24 h |
| `osVersion` | by layout | hpe | |
| `agentMinVersion` | 4.0.1 | plugin | Oldest Morpheus agent seen to acknowledge the right node in a freeze |
| `certWarnDays`, `certFailDays` | 30, 7 | plugin | Days left on a libvirt certificate in use, or the appliance's |
| `trunkAllowedVms` | not set | plugin | VMs meant to have a trunk port, by libvirt domain name. Morpheus has no record of this |

## Time sources

The time source a cluster is verified against comes from, in order: what is defined on its **Time** panel, the `ntpServers` site
standard, then the NTP servers in the cluster's layout settings (the 2.0 layout has them). Defining one changes nothing on the hosts. A
host not using it gets a finding, and a fix that needs maintenance mode, with the OS file left as shipped: on chrony a drop-in marked `prefer`; on
`systemd-timesyncd` a drop-in in `/etc/systemd/timesyncd.conf.d/` and a restart, undone if no listed server answers.

Drift between hosts compares the offsets each host measures to the time servers they share, which reads their clocks against a
common reference to within milliseconds. Comparing their own timestamps would carry seconds of agent latency, and is only the fallback.
Hosts on different servers, such as different members of a pool, are not compared to the millisecond, and the finding says so. `systemd-timesyncd` gives one
sample per host from its last poll, so an offset or a drift over the limit from it is a warning, not a failure.

## CPU models: the EVC equivalent

The cluster's **CPU Model** does what EVC does on vSphere, and it can be changed on an existing cluster by editing it. HPE's guide says to leave
`host-passthrough` unless hosts have different CPU families, and to pick a model every host supports and test a live migration in both
directions. Running VMs keep their CPU at least until their next power cycle, and reports differ on whether existing VMs then pick the
change up, so test on one VM first.

MIG-01 names the model to set: from the family of the hosts' own CPUs, usable on every host according to libvirt, and in the
unversioned form Morpheus lists. When none fits, it says so rather than guess.

## Setting your own

The **Site standards** plugin setting takes JSON. Top-level keys apply to every layout; `profiles` overrides them per layout
version; `disabledChecks` drops checks by id.

```json
{
  "ntpServers": ["ntp1.example.invalid", "ntp2.example.invalid"],
  "gfs2WarnPct": 75,
  "profiles": { "2.0": { "corosyncToken": 60000 } },
  "disabledChecks": ["PKG-01"]
}
```

A broken or misspelled setting, or a value of the wrong type, is reported in the tab and by an alarm, PRB-03, rather than ignored: a standard that silently stops applying is worse than none. Checks carry on with the built-in standards until it is fixed, so a freeze is still found, but none of the site's overrides or disabled checks apply meanwhile.
