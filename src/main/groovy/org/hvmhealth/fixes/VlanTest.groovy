package org.hvmhealth.fixes

/**
 * Whether a VLAN really passes on each uplink, by traffic, on a host in maintenance mode: LLDP cannot say, because switches
 * rarely advertise a trunk's allowed VLANs. For each uplink in turn it makes that uplink active, and ARPs for each VLAN's
 * gateway. This is how a VLAN pruned from the standby switch gets found, which otherwise stays invisible until a failover.
 *
 * It bounces the host's active uplink, so the host's own management path moves, and if the standby uplink does not carry
 * management the agent loses the host until the original returns. So the test runs detached on the host, writes to a file, and
 * has a timer that restores the original uplink and removes what it added, whatever happens to the session that started it.
 */
class VlanTest {
    static final String OUT = '/run/hvm-health-vlan-test.out'

    /** @param networks [vlan, gateway] */
    static String launchScript(List<Map> networks) {
        networks.each { Map n ->
            if (!((n.vlan as String) ==~ /\d{1,4}/) || !((n.gateway as String) ==~ /[0-9.]+/)) {
                throw new IllegalArgumentException("Unsafe VLAN or gateway: ${n.vlan} ${n.gateway}")
            }
        }
        String pairs = networks.collect { "${it.vlan}:${it.gateway}" }.join(' ')
        String body = TEST.replace('@PAIRS@', pairs)
        Fixes.HEADER + "rm -f ${OUT}\n" +
                "echo '${body.getBytes('UTF-8').encodeBase64()}' | base64 -d > /run/hvm-health-vlan-test.sh\n" +
                "nohup setsid bash /run/hvm-health-vlan-test.sh > ${OUT} 2>&1 < /dev/null &\necho started\n"
    }

    static final String READ_SCRIPT = "#!/bin/bash\ncat ${OUT} 2>/dev/null\n"

    /** Lines of the test's output as results; done when it reached its end marker. */
    static Map parse(String output) {
        List<String> lines = (output ?: '').readLines()
        [done: lines.any { it == 'END' }, error: lines.find { it.startsWith('ERROR=') }?.substring(6), leftover: lines.find { it.startsWith('LEFTOVER=') }?.substring(9),
         restored: lines.find { it.startsWith('RESTORED=') }?.substring(9),
         results: lines.findAll { it.startsWith('RESULT=') }.collect { String l ->
             List<String> p = l.substring(7).split(/\|/, -1) as List<String>
             [uplink: p[0], vlan: p[1], gateway: p[2], replies: p[3]?.isInteger() ? p[3] as Integer : 0, via: p.size() > 4 ? p[4] : null]
         }]
    }

    /** Failed pairs, and a sentence for the operator. */
    static Map summary(Map parsed) {
        List<Map> bad = (parsed.results as List<Map>).findAll { !it.replies }
        Map<String, List<Map>> byVlan = (parsed.results as List<Map>).groupBy { it.vlan as String }
        // no replies on any uplink says more about the gateway than about the trunk
        List<String> uplinkOnly = byVlan.findAll { k, v -> v.any { it.replies } && v.any { !it.replies } }.collect { k, v -> "VLAN ${k} does not pass on ${v.findAll { !it.replies }*.uplink.join(', ')}".toString() }
        List<String> nowhere = byVlan.findAll { k, v -> v.every { !it.replies } }.collect { k, v -> "VLAN ${k}: gateway ${v[0].gateway} did not answer on any uplink".toString() }
        // no results is a failed test, never a pass
        [ok: parsed.results && !bad, uplinkFailures: uplinkOnly, nowhere: nowhere]
    }

    private static final String TEST = '''#!/bin/bash
export LC_ALL=C PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin
bond=""
for b in /sys/class/net/*/bonding; do
  [ -d "$b" ] || continue
  n="$(basename "$(dirname "$b")")"
  if grep -q active-backup "$b/mode" && [ -n "$(cat "$b/active_slave")" ]; then bond="$n"; break; fi
done
[ -n "$bond" ] || { echo 'ERROR=no active-backup bond with an active uplink here: uplinks cannot be tested one at a time'; echo END; exit 0; }
orig="$(cat "/sys/class/net/$bond/bonding/active_slave")"
slaves="$(cat "/sys/class/net/$bond/bonding/slaves")"
ovs="$(ovs-vsctl port-to-br "$bond" 2>/dev/null)"
br="$(ip -o link show "$bond" | grep -oE 'master [^ ]+' | cut -d' ' -f2)"
[ -n "$ovs" ] && br="$ovs"
ifaces=""; addedvlans=""
restore() {
  echo "$orig" > "/sys/class/net/$bond/bonding/active_slave" 2>/dev/null
  for i in $ifaces; do
    if [ -n "$ovs" ]; then ovs-vsctl --if-exists del-port "$i" >/dev/null 2>&1; else ip link del "$i" 2>/dev/null; fi
  done
  for v in $addedvlans; do bridge vlan del vid "$v" dev "$br" self 2>/dev/null; done
}
finish() {
  kill "$timer" 2>/dev/null; restore
  echo "RESTORED=$(cat "/sys/class/net/$bond/bonding/active_slave")"
  echo "LEFTOVER=$(ip -o link show 2>/dev/null | grep -c ': hhv[0-9]')"
  echo END
}
trap finish EXIT
# the backstop if this script itself is killed or hangs
( sleep 150; echo "$orig" > "/sys/class/net/$bond/bonding/active_slave" ) >/dev/null 2>&1 &
timer=$!
iface_for() {
  v="$1"
  ex="$(ip -d -o link show type vlan 2>/dev/null | grep -E "@$br:" | grep -E "vlan protocol 802.1Q id $v " | awk -F': ' '{print $2}' | cut -d@ -f1 | head -1)"
  [ -n "$ex" ] && { echo "$ex"; return; }
  i="hhv$v"
  if [ -n "$ovs" ]; then
    ovs-vsctl --may-exist add-port "$br" "$i" tag="$v" -- set Interface "$i" type=internal >/dev/null 2>&1
  else
    bridge vlan show dev "$br" 2>/dev/null | grep -qwE "$v" || { bridge vlan add vid "$v" dev "$br" self 2>/dev/null; addedvlans="$addedvlans $v"; }
    ip link add link "$br" name "$i" type vlan id "$v" 2>/dev/null
  fi
  ip link set "$i" up 2>/dev/null
  ifaces="$ifaces $i"
  echo "$i"
}
declare -A DEV
for pair in @PAIRS@; do
  v="${pair%%:*}"
  iface_for "$v" > /run/hvm-health-vlan-if; DEV[$v]="$(cat /run/hvm-health-vlan-if)"
  # iface_for ran in this shell through a file so what it added is remembered
  if [ "${DEV[$v]}" = "hhv$v" ] && ! echo "$ifaces" | grep -qw "hhv$v"; then ifaces="$ifaces hhv$v"; fi
done
rm -f /run/hvm-health-vlan-if
sleep 2
for s in $slaves; do
  echo "$s" > "/sys/class/net/$bond/bonding/active_slave" || { echo "ERROR=could not make $s active"; continue; }
  sleep 4
  for pair in @PAIRS@; do
    v="${pair%%:*}"; gw="${pair##*:}"; d="${DEV[$v]}"
    if ip -o -4 addr show dev "$d" 2>/dev/null | grep -q inet; then a=(-c 3 -w 4 -I "$d" "$gw"); else a=(-D -c 3 -w 4 -I "$d" "$gw"); fi
    n="$(timeout 8 arping "${a[@]}" 2>/dev/null | grep -oE 'Received [0-9]+' | grep -oE '[0-9]+')"
    echo "RESULT=$s|$v|$gw|${n:-0}|$d"
  done
done
'''
}
