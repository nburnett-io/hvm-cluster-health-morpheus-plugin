# Getting findings out of the plugin

**The plugin's alarms do not reach Morpheus's alert rules.** Plugin alarms show in Morpheus's list of alarms, and that is as far as they go. Alert rules attach to checks, check groups and apps, and with a dozen alarms open Monitoring > Incidents had none. The plugin API has no way for a plugin to create a check or an incident. So a rule you make in Monitoring > Alerts will never fire on a plugin alarm.

The way out is the **status URL**. Something outside the plugin reads it and decides what to do: a URL monitor such as N-able, or a Morpheus **Web Check**, whose incidents alert rules do see.

```
finding -> status URL -> Web Check -> incident -> alert rule -> your contacts
                      -> URL monitor (N-able and the like)
```

## The status URL

Turn it on with the **Status URL port** plugin setting, a port above 1024. It starts within a minute, and moves within a minute of a change. If the port cannot be used, the plugin carries on, logs why, tries again each minute, and says why at the top of the all-clusters page.

It is plain HTTP with no login, because a URL monitor cannot use a browser session, which every other route in the plugin needs. It carries no secret and needs no certificate. GET and HEAD only, always `text/plain` but for the signed read below. Each request is answered from what the monitor already holds, and never reaches a host or the database.

A body holds only the words and counts below: no host, cluster, tenant, VM or finding. Without a reader key there is no route listing clusters. Ids are not secret, and an id tells a stranger nothing but whether that cluster is healthy.

| Path | Normal | Problem |
|---|---|---|
| `/alive` | 200 `HVM-HEALTH ALIVE` | 503 `HVM-HEALTH STALE`: no sweep for 5 minutes, checks too far behind or the freeze watch stopped (PRB-04), or automatic checks turned off |
| `/status` | 200 `HVM-HEALTH OK` and a counts line | 503 `HVM-HEALTH CRITICAL`: any cluster critical. 503 `HVM-HEALTH STALE`: nothing can be vouched for, as the first sweep is late, the sweep has stopped or every cluster is out of date. 200 `HVM-HEALTH WARNING`: warnings, or some clusters out of date |
| `/cluster/<id>` | 200 `HVM-HEALTH OK`, with a second line `reloading` if it has not been checked since a reload | 503 `HVM-HEALTH CRITICAL`. 503 `HVM-HEALTH STALE`. 200 `HVM-HEALTH WARNING`. 404 `HVM-HEALTH UNKNOWN` for an id it does not monitor |

The counts line reads `clusters=12 critical=0 warning=3 stale=0 held=1 reloading=0`.

- **Critical** means a frozen cluster, a finding that always pages (see below), or a cluster whose overall health is CRITICAL. The freeze watch makes a freeze critical within about a minute, ahead of the next check. A degraded cluster reads as WARNING.
- **Stale** means this cluster has not been checked for 3 quick check intervals, or at least 5 minutes, or not since the plugin loaded and the 15 minutes below have passed. It is never reported as OK. A cluster known to be critical stays critical even if out of date.
- **Held** counts clusters in a change window. A window holds a cluster's warnings at OK, and never a freeze, a withdrawn filesystem, lost quorum or a tenant isolation failure.
- **Reloading** counts clusters not yet checked since the plugin loaded, for the first 15 minutes after it loads. See below.
- **Any appliance node can answer.** One node runs the checks, and the others answer from what it last stored, up to about 2 minutes behind. So a monitor on any node's address sees the same words, though a freeze reaches the node running the checks first. With no fresh record for 5 minutes a node reads STALE. If the node running the checks stops, another takes over within about 4 minutes, and clusters read as after a reload, below. A monitor on the stopped node's own address sees its connection fail, so give it another node's too, or read through a load balancer.

## A signed read, for a dashboard

- A tool that wants more than words puts its public key in the **Reader keys** setting and keeps the private half. The plugin holds no secret.
- A key is Ed25519: the standard base64 of its X.509 SubjectPublicKeyInfo, 60 characters starting `MCowBQYDK2VwAyEA`. One a line; a line may start with a label, and the key is its last word.
- Up to 20 keys are used. A line that is not a key, and any key past 20, is ignored, and the all-clusters page counts them.
- The request is `GET /v1/clusters` on the status URL port, with two headers: `X-HH-Time`, the time in Unix seconds, and `X-HH-Signature`, the standard base64 of the 64-byte signature.
- The signed bytes are `hvm-health-v1`, `GET`, `/v1/clusters` and the time, a line each, joined by line feeds, with nothing after the time.
- The time must be within 5 minutes of the appliance node's clock, either way. Every answer has a `Date` header, so a tool can see a difference.
- The answer is JSON, version 1: each monitored cluster by id, with its state (`OK`, `WARNING`, `CRITICAL`, `STALE` or `RELOADING`, judged as `/cluster/<id>` judges it), whether a change window holds its paging, when it was last checked and how, the last full check, its overall health, counts by status, readiness by area, and its check ids by status. `names` gives each listed check's name from the check catalog.
- `alive` is true when `/alive` would answer 200, `sweptAt` is when the last sweep ran, and `quickMinutes` is the quick check interval.
- Readiness is `PASS`, `WARN`, `FAIL`, `NOT_CHECKED` or `NA`, where nothing in the area applies. Counts and check ids leave out what does not apply.
- It never holds a host, cluster name, tenant, VM or finding text.
- Anything unsigned, or not signed right, gets 404 `HVM-HEALTH UNKNOWN`, like a path that does not exist. A HEAD gets the same.
- The all-clusters page shows how many keys are in force, and the last read refused with why. The log says why too, at most once a minute.
- A node standing by gives each cluster's word only, from the record of the node that runs the checks.
- The answer is plain HTTP, neither encrypted nor signed: keep the port on a management network and allow only the reader's address.
- A copied request can be replayed for 5 minutes, against any appliance that holds the same key. It only reads. Give each appliance its own key.

```json
{"version": 1, "standingBy": false, "alive": true, "sweptAt": 1800000000000, "quickMinutes": 5,
 "clusters": [{"id": 27, "state": "WARNING", "held": false,
               "checkedAt": 1799999700000, "mode": "quick", "fullAt": 1799982000000, "overall": "WARNING",
               "counts": {"fail": 0, "warn": 2, "pass": 41, "notChecked": 3},
               "readiness": [{"area": "Live migration", "status": "WARN"}],
               "checks": {"FAIL": [], "WARN": ["TIM-02"], "NOT_CHECKED": ["HW-08"], "PASS": ["CLU-01"]}}],
 "names": {"CLU-01": "Enough hosts for quorum", "HW-08": "Management controller healthy", "TIM-02": "Host clocks agree"}}
```

Times are epoch milliseconds, or null. Counts are result rows, so a check on three hosts counts three times, and is listed once. Before the first sweep no cluster is listed. A cluster with no check yet has only its id, state and `held`. On a node standing by, each cluster is its id and state only, `quickMinutes` is null and `names` is empty.

## After a plugin reload, an upgrade or a takeover

When another appliance node takes over the checks, everything here holds as it does for a load, counted from the takeover.

A reload forgets what the monitor held, and the first round of checks takes up to about 11 minutes at 200 clusters. So that a reload does not open an incident on every Web Check, for the first 15 minutes after the plugin loads:

- `/cluster/<id>` answers 200 `HVM-HEALTH OK` and a second line, `reloading`, for a cluster not yet checked since the load. A check matching `HVM-HEALTH OK` stays green, and a person reading the URL can see what it means. Once its first check is done it says what it found.
- `/status` counts such a cluster under `reloading=N`, apart from the others, as it does one under `held`. It raises neither a warning nor STALE. In the 2 minutes before the first sweep, when the monitor does not yet know what it watches, `/status` is 200 `HVM-HEALTH OK` with every count 0, and `/cluster/<id>` says `reloading` for any id.
- **The freeze watch keeps running.** Once the first sweep has handed it the clusters, it reads each in its usual turn, and a freeze it finds is `HVM-HEALTH CRITICAL` at once, with no check needed. At 200 clusters that means a freeze already under way at the reload is known within about 4 minutes of the load, and until then that cluster reads `reloading`.
- After 15 minutes a cluster still not checked reads STALE, as it would at any other time.

This holds only while the monitor is doing its job. If no sweep has run for 5 minutes, or automatic checks are off, an unchecked cluster reads STALE, and `/status` too.

`/alive` gets none of this. It is ALIVE through a reload as it always was, until the first sweep is really late, and STALE if checks fall behind or the watch stops. A connection refused for a few seconds is normal during the reload itself.

The cost: a cluster with a real problem before the reload reads `reloading` until its first check, so an incident already open on it closes and then opens again.

## The firewall

A real appliance may need the port opened. Allow it from your monitor's addresses only: the answers give little away, but there is no reason to offer them to everyone.

```
sudo ufw allow from 203.0.113.10 to any port 18443 proto tcp
```

A Morpheus Web Check on the appliance itself reads `127.0.0.1` and needs no rule.

## N-able

Add an HTTP service on the port, with a content check and response code states:

| What | Path | Regular expression | States |
|---|---|---|---|
| Is the monitor working? | `/alive` | `HVM-HEALTH ALIVE` | 200 normal, anything else failed |
| Is anything wrong? | `/status` | `HVM-HEALTH OK` | 200 with the text normal, 200 without it warning, 503 failed |

If the appliance is down, the connection fails, which is the point: that is the alert a monitor inside Morpheus could never send.

## A Morpheus Web Check and alert rule per cluster

A Web Check goes to `error` when the text does not match, opens an incident named after the check within a minute, and alert rules then apply as to any other check. Make one for each cluster you want paged, or one on `/status` for the whole appliance. Use a Morpheus API token in `$MORPHEUS_TOKEN`; it is not something this plugin holds.

```bash
# the check
curl -sk -X POST "https://morpheus.example.invalid/api/monitoring/checks" \
  -H "Authorization: Bearer $MORPHEUS_TOKEN" -H "Content-Type: application/json" -d '{
  "check": {
    "name": "Cluster health: cluster-a",
    "checkType": {"code": "webGetCheck"},
    "checkInterval": 60000,
    "severity": "critical",
    "config": {
      "webMethod": "GET",
      "webUrl": "http://127.0.0.1:18443/cluster/27",
      "textCheckOn": true,
      "webTextMatch": "HVM-HEALTH OK"
    }
  }
}'

# the rule, with the id the call above returned
curl -sk -X POST "https://morpheus.example.invalid/api/monitoring/alerts" \
  -H "Authorization: Bearer $MORPHEUS_TOKEN" -H "Content-Type: application/json" -d '{
  "alert": {
    "name": "Cluster health: cluster-a",
    "minSeverity": "warning",
    "allChecks": false,
    "allGroups": false,
    "allApps": false,
    "checks": [101],
    "contacts": []
  }
}'
```

Add your contacts to `contacts`. The cluster's id is in its URL in Morpheus.

Things to know:

- A WARNING also opens an incident, as the text is not `HVM-HEALTH OK`. Give the check the severity you want for the whole of it.
- Remove the check when you remove the cluster, or it will read UNKNOWN and stay open.
- A plugin reload does not open incidents on these checks unless its first checks take over 15 minutes: see above. If that happens on your appliance, PRB-04 will usually say why.
- A check by interval is a poll, so it adds up to a minute to what the freeze watch found.

## Also

The alarms stay useful on their own: they name the host and finding, which the status URL will not. **Change windows** hold what the URL says, not what is detected, and a cluster in one does not count towards FLT-01 unless it has a confirmed isolation failure. Escalation and on-call schedules belong to your paging tool.
