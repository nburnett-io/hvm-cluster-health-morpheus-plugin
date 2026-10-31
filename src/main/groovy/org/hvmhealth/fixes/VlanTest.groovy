package org.hvmhealth.fixes

/**
 * Whether a VLAN really passes on each uplink, by traffic, on a host in maintenance mode: LLDP cannot say, because switches
 * rarely advertise a trunk's allowed VLANs. For each uplink in turn it makes that uplink active, and ARPs for each VLAN's
 * gateway. This is how a VLAN pruned from the standby switch gets found, which otherwise stays invisible until a failover.
 *
 * It bounces the host's active uplink, so the host's own management path moves, and if the standby uplink does not carry
 * management the agent loses the host until the original returns. So the test runs detached on the host, writes to a file, and
 * has a timer that restores the original uplink and removes what it added, whatever happens to the session that started it.
 *
 * A host can have several bridges, each on its own bond (HPE's mgmt, cmpt and vxln), and nothing on the host proves which
 * should carry a VLAN, so every VLAN is tested on every active-backup bond on a bridge, and each result says which bond it came
 * from. A bond that stays silent is only a failure if no bond answers. A bridge that cannot be tested is reported, and a VLAN
 * silent everywhere else is then inconclusive, as it may be on that bridge. A test that cannot run says so: it never counts as
 * a pass, or as a VLAN failing.
 */
class VlanTest {
    static final String OUT = '/run/hvm-health-vlan-test.out'
    /** Held by a running test and by its timer: a retry must not start while the uplink is still moved, or it saves the standby as the original. */
    static final String LOCK = '/run/hvm-health-vlan-test.lock'
    static final int BUSY_EXIT = 9

    /**
     * How many VLANs one run can test: seconds on an uplink are its 4 s settling plus at most 8 s per VLAN, and that stays within half the corosync
     * token, as a standby without the ring's VLAN loses the host's membership for that long, with GFS2 mounted. Each run puts the uplink back.
     * Below 1 the token is too short to test at all; a host with no token is in no membership to lose.
     */
    static int vlansPerRun(Long tokenMs, int vlans) {
        tokenMs == null ? vlans : (((tokenMs / 2000L) - 4) / 8) as int
    }

    /** @param networks [vlan, gateway] */
    static String launchScript(List<Map> networks) {
        networks.each { Map n ->
            if (!((n.vlan as String) ==~ /\d{1,4}/) || !((n.gateway as String) ==~ /[0-9.]+/)) {
                throw new IllegalArgumentException("Unsafe VLAN or gateway: ${n.vlan} ${n.gateway}")
            }
        }
        String pairs = networks.collect { "${it.vlan}:${it.gateway}" }.join(' ')
        String body = TEST.replace('@PAIRS@', pairs).replace('@LOCK@', LOCK)
        Fixes.HEADER + "flock -n ${LOCK} true || { echo 'a VLAN test is still running on this host'; exit ${BUSY_EXIT}; }\nrm -f ${OUT}\n" +
                "echo '${body.getBytes('UTF-8').encodeBase64()}' | base64 -d > /run/hvm-health-vlan-test.sh\n" +
                "nohup setsid bash /run/hvm-health-vlan-test.sh > ${OUT} 2>&1 < /dev/null &\necho started\n"
    }

    static final String READ_SCRIPT = "#!/bin/bash\ncat ${OUT} 2>/dev/null\n"
    /** Once its result has been read; the timer's restore has run by then. */
    static final String CLEAN_SCRIPT = "#!/bin/bash\nrm -f ${OUT} /run/hvm-health-vlan-test.sh\n"

    /** Lines of the test's output as results; done when it reached its end marker. */
    static Map parse(String output) {
        List<String> lines = (output ?: '').readLines()
        [done: lines.any { it == 'END' }, error: lines.find { it.startsWith('ERROR=') }?.substring(6), leftover: lines.find { it.startsWith('LEFTOVER=') }?.substring(9),
         original: lines.find { it.startsWith('ORIGINAL=') }?.substring(9),
         restored: lines.find { it.startsWith('RESTORED=') }?.substring(9),
         budget: lines.find { it ==~ /BUDGET=\d+/ }?.substring(7)?.toLong(),
         skipped: lines.findAll { it.startsWith('SKIPPED=') }.collect { String l ->
             List<String> p = l.substring(8).split(/\|/, 2) as List<String>
             [bridge: p[0], why: p.size() > 1 ? p[1] : null]
         },
         results: lines.findAll { it.startsWith('RESULT=') }.collect { String l ->
             List<String> p = l.substring(7).split(/\|/, -1) as List<String>
             // arping's own count, or nothing to read because arping itself failed: never the same as no reply
             [uplink: p[0], vlan: p[1], gateway: p[2], replies: p[3]?.isInteger() ? p[3] as Integer : 0, untested: !p[3]?.isInteger(), via: p.size() > 4 ? p[4] : null, bond: p.size() > 5 ? p[5] : null]
         }]
    }

    /** What the results prove, VLAN by VLAN, and sentences for the operator. */
    static Map summary(Map parsed) {
        List<Map> results = parsed.results as List<Map>
        List<Map> skipped = (parsed.skipped ?: []) as List<Map>
        List<String> notTested = results.findAll { it.untested }.collect { "VLAN ${it.vlan} on ${it.uplink}: arping gave no result, so this was not tested".toString() }
        List<String> uplinkOnly = [], nowhere = [], inconclusive = [], carriedBy = []
        results.findAll { !it.untested }.groupBy { it.vlan as String }.each { String vlan, List<Map> rs ->
            Map<String, List<Map>> byBond = rs.groupBy { (it.bond ?: '') as String }
            Map<String, List<Map>> answering = byBond.findAll { k, v -> v.any { it.replies } }
            answering.each { String bond, List<Map> v ->
                List<Map> silent = v.findAll { !it.replies }
                if (silent) {
                    uplinkOnly << "VLAN ${vlan} does not pass on ${silent*.uplink.join(', ')}${bond ? ' (' + bond + ')' : ''}".toString()
                }
            }
            if (answering) {
                if (answering.keySet().any()) {
                    carriedBy << "VLAN ${vlan}: answered on ${answering.keySet().join(', ')}".toString()
                }
            } else if (skipped) {
                // the bridge not tested may be the one that carries it
                inconclusive << "VLAN ${vlan}: gateway ${rs[0].gateway} did not answer on the bonds tested, and ${skipped*.bridge.join(', ')} could not be tested".toString()
            } else {
                // no replies on any uplink says more about the gateway than about the trunk
                nowhere << "VLAN ${vlan}: gateway ${rs[0].gateway} did not answer on any uplink".toString()
            }
        }
        // no results is a failed test, never a pass
        [ok: results && !notTested && !uplinkOnly && !nowhere && !inconclusive, uplinkFailures: uplinkOnly, nowhere: nowhere, inconclusive: inconclusive, notTested: notTested, carriedBy: carriedBy, skipped: skipped]
    }

    private static final String TEST = '''#!/bin/bash
export LC_ALL=C PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin
# first, before anything moves: without arping nothing can be tested, and that says nothing about any VLAN
command -v arping >/dev/null 2>&1 || { echo 'ERROR=arping not installed: not tested'; echo END; exit 0; }
exec 9>@LOCK@
flock -n 9 || { echo 'ERROR=a VLAN test is still running on this host'; echo END; exit 0; }
declare -A ORIG BR OVS DEV
bonds=""; bridged=" "
for b in /sys/class/net/*/bonding; do
  [ -d "$b" ] || continue
  n="$(basename "$(dirname "$b")")"
  o="$(ovs-vsctl port-to-br "$n" 2>/dev/null)"
  m="$(ip -o link show "$n" | grep -oE 'master [^ ]+' | cut -d' ' -f2)"
  # a bond on no bridge carries no VM's VLAN, usually storage: not ours to move
  [ -n "$o$m" ] || continue
  bridged="$bridged${o:-$m} "
  if grep -q active-backup "$b/mode" && [ -n "$(cat "$b/active_slave")" ]; then
    bonds="$bonds $n"; ORIG[$n]="$(cat "$b/active_slave")"; BR[$n]="${o:-$m}"; OVS[$n]="$o"
  else
    echo "SKIPPED=${o:-$m}|$n is not active-backup with an active uplink"
  fi
done
for x in $(ovs-vsctl list-br 2>/dev/null) $(ip -o link show type bridge 2>/dev/null | awk -F': ' '{print $2}' | grep -v '^virbr'); do
  case "$bridged" in *" $x "*) ;; *) echo "SKIPPED=$x|its uplink is not a bond, so it cannot be tested one uplink at a time" ;; esac
done
[ -n "$bonds" ] || { echo 'ERROR=no active-backup bond on a bridge with an active uplink here: uplinks cannot be tested one at a time'; echo END; exit 0; }
pairs=0; for pair in @PAIRS@; do pairs=$((pairs + 1)); done
uplinks=0; for n in $bonds; do for s in $(cat "/sys/class/net/$n/bonding/slaves"); do uplinks=$((uplinks + 1)); done; done
# longer than the slowest whole run, and told to the plugin, which waits for it
budget=$((40 + uplinks * (4 + pairs * 9)))
echo "BUDGET=$budget"
r=""; for n in $bonds; do r="$r ${ORIG[$n]}"; done
echo "ORIGINAL=${r# }"
ifaces=""; addedvlans=""
put_back() {
  for n in $bonds; do echo "${ORIG[$n]}" > "/sys/class/net/$n/bonding/active_slave" 2>/dev/null; done
}
clean() {
  for i in $ifaces; do
    ovs-vsctl --if-exists del-port "$i" >/dev/null 2>&1
    ip link del "$i" 2>/dev/null
  done
  for e in $addedvlans; do bridge vlan del vid "${e%%:*}" dev "${e##*:}" self 2>/dev/null; done
  ifaces=""; addedvlans=""
}
finish() {
  kill "$timer" 2>/dev/null; put_back; clean
  r=""; for n in $bonds; do r="$r $(cat "/sys/class/net/$n/bonding/active_slave")"; done
  echo "RESTORED=${r# }"
  echo "LEFTOVER=$(ip -o link show 2>/dev/null | grep -c ': hhv[0-9]')"
  echo END
}
trap finish EXIT
# The backstop if this script itself is killed or hangs. It keeps the lock until then, and sleeps in steps so that killing it lets the lock go at once.
( for _ in $(seq 1 "$budget"); do sleep 1; done; put_back ) >/dev/null 2>&1 &
timer=$!
iface_for() {
  v="$1"
  ex="$(ip -d -o link show type vlan 2>/dev/null | grep -E "@$br:" | grep -E "vlan protocol 802.1Q id $v " | awk -F': ' '{print $2}' | cut -d@ -f1 | head -1)"
  if [ -n "$ex" ]; then DEV[$v]="$ex"; return; fi
  i="hhv$v"
  if [ -n "$ovs" ]; then
    ovs-vsctl --may-exist add-port "$br" "$i" tag="$v" -- set Interface "$i" type=internal >/dev/null 2>&1
  else
    bridge vlan show dev "$br" 2>/dev/null | grep -qwE "$v" || { bridge vlan add vid "$v" dev "$br" self 2>/dev/null; addedvlans="$addedvlans $v:$br"; }
    ip link add link "$br" name "$i" type vlan id "$v" 2>/dev/null
  fi
  ip link set "$i" up 2>/dev/null
  ifaces="$ifaces $i"
  DEV[$v]="$i"
}
for bond in $bonds; do
  br="${BR[$bond]}"; ovs="${OVS[$bond]}"; DEV=()
  for pair in @PAIRS@; do iface_for "${pair%%:*}"; done
  sleep 2
  for s in $(cat "/sys/class/net/$bond/bonding/slaves"); do
    echo "$s" > "/sys/class/net/$bond/bonding/active_slave" || { echo "ERROR=could not make $s active"; continue; }
    sleep 4
    for pair in @PAIRS@; do
      v="${pair%%:*}"; gw="${pair##*:}"; d="${DEV[$v]}"
      if ip -o -4 addr show dev "$d" 2>/dev/null | grep -q inet; then a=(-c 3 -w 4 -I "$d" "$gw"); else a=(-D -c 3 -w 4 -I "$d" "$gw"); fi
      n="$(timeout 8 arping "${a[@]}" 2>/dev/null | grep -oE 'Received [0-9]+' | grep -oE '[0-9]+')"
      echo "RESULT=$s|$v|$gw|${n:-none}|$d|$bond"
    done
  done
  # back to where it was, with its temporary interfaces gone, before the next bridge is touched
  echo "${ORIG[$bond]}" > "/sys/class/net/$bond/bonding/active_slave" 2>/dev/null
  clean
done
'''
}
