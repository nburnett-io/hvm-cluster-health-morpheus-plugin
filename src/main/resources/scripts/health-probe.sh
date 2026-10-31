#!/bin/bash
# HVM Cluster Health: read-only host probe. Changes nothing on the host.
#   $1 = full (default) or quick (skips the slower checks; the monitor's frequent run)
#   $2 = seconds it may read for
#
# Rule that shapes this script: while a DLM lockspace is stopped, ANY access to a GFS2 mount blocks in uninterruptible
# sleep and cannot be killed, not even by timeout. So nothing here touches a GFS2 path unless DLM reports every
# lockspace healthy, and even then only from a background job the script does not wait on past a deadline.
export LC_ALL=C PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin
MODE="${1:-full}"
# The caller throws away everything from a run that outlasts it, the freeze included. So past this, t() answers at once, and the end says the run was cut.
BUDGET="${2:-120}"
t() {
  local s="$1" left=$((BUDGET - SECONDS))
  shift
  [ "$left" -gt 0 ] || return 124
  [ "$s" -le "$left" ] || s="$left"
  timeout -k 2 "$s" "$@" 2>/dev/null
}
# For what can sleep unkillably, where timeout waits along with it: a job this stops waiting for at the limit, and leaves behind. Fails if it did not finish.
bounded() {
  local s="$1" left=$((BUDGET - SECONDS)) o j r=0
  shift
  [ "$left" -gt 0 ] || return 124
  [ "$s" -le "$left" ] || s="$left"
  o="$(mktemp /run/hvm-health.XXXXXX)" || return 1
  "$@" > "$o" 2>/dev/null &
  j=$!
  for _ in $(seq 1 $((s * 2))); do kill -0 "$j" 2>/dev/null || break; sleep 0.5; done
  kill -0 "$j" 2>/dev/null && r=1
  cat "$o"; rm -f "$o"
  return "$r"
}
sec() { echo "@@$1"; }

sec meta
echo "HOSTNAME=$(hostname -s)"
echo "NOW=$(date +%s)"
echo "BOOT=$(awk '/^btime/{print $2}' /proc/stat)"
echo "KERNEL=$(uname -r)"
echo "OS_VERSION=$(. /etc/os-release 2>/dev/null; echo "$VERSION_ID")"
echo "LOAD1=$(cut -d' ' -f1 /proc/loadavg)"
echo "NPROC=$(nproc)"
echo "REBOOT_REQUIRED=$([ -f /var/run/reboot-required ] && echo 1 || echo 0)"
echo "HVMCLI=$(command -v hvmcli)"
echo "MODE=$MODE"

# First, so these print however slow the rest is: a freeze must be named even by a host that runs out of time.
sec dlm_ls
DLM_LS="$(t 10 dlm_tool ls)"; DLM_RC=$?
echo "$DLM_LS"

sec dlm_status
t 10 dlm_tool status

sec dlm_conf
grep -vE '^\s*(#|$)' /etc/dlm/dlm.conf 2>/dev/null

sec quorum
t 10 corosync-quorumtool -s

sec corosync_conf
if [ -r /etc/corosync/corosync.conf ]; then cat /etc/corosync/corosync.conf; fi

sec corosync_key
[ -r /etc/corosync/authkey ] && sha256sum /etc/corosync/authkey | cut -c1-16

sec services
for u in corosync dlm pacemaker pcsd libvirtd multipathd iscsid chrony systemd-timesyncd morpheus-morphd; do
  echo "$u|$(t 5 systemctl is-active "$u")|$(t 5 systemctl is-enabled "$u")"
done
echo "COROSYNC_UP=$(ps -o etimes= -C corosync | head -1 | tr -d ' ')"

sec stuck
echo "D_COUNT=$(ps -eo state= | grep -c '^D')"
ps -eo state=,etimes=,comm= | awk '$1=="D"{c[$3]++; if($2>m)m=$2} END{print "D_OLDEST="m+0; for(k in c)print "D_COMM="k"|"c[k]}'
# a process's args take its memory lock, which a qemu stuck on its disk can hold: ps would then sleep with it
d_vms() { ps -eo pid=,state=,args= | awk '$2=="D" && /qemu-system/' | grep -oE 'guest=[^, ]+' | sed 's/guest=/D_VM=/' | sort -u; }
bounded 5 d_vms
echo "HUNG_1H=$(t 20 journalctl -k --since '1 hour ago' -o cat --no-pager | grep -c 'blocked for more than')"

sec links
t 10 corosync-cfgtool -s

sec totem
t 10 corosync-cmapctl | grep -E '^runtime\.config\.totem\.(token|consensus) '

sec fence_acks
t 20 journalctl -t sudo --since '15 min ago' -o cat --no-pager | grep 'morpheus-node' | grep -oE 'dlm_tool fence_ack [0-9]+' | awk '{print $3}' | sort | uniq -c | awk '{print $2"|"$1}'

sec gfs2
GFS2_TARGETS="$(awk '$3=="gfs2"{print $2}' /proc/mounts)"
awk '$3=="gfs2"{print "MOUNT="$1"|"$2}' /proc/mounts
for d in /sys/fs/gfs2/*; do
  [ -d "$d" ] && echo "TABLE=$(basename "$d")|$(cat "$d/withdraw" 2>/dev/null)|$(cat "$d/freeze" 2>/dev/null)"
done
# -t kernel rather than -k: -k is this boot only, and the agent reboots a host soon after a withdrawal
GFS_ERR_RE='GFS2: fsid=.*(consistency error|withdrawn|fatal:)|gfs2.*(consistency error|File system withdrawn)'
# a log that could not be read prints no count: grep alone would say 0, and clear a critical finding
GFS_ERR_LOG="$(t 20 journalctl -t kernel --since '7 days ago' -o short-iso --no-pager | grep -E "$GFS_ERR_RE"; exit "${PIPESTATUS[0]}")" && echo "GFS2_ERRORS_7D=$(printf '%s' "$GFS_ERR_LOG" | grep -c .)"
echo "GFS2_ERROR_LAST=$(printf '%s\n' "$GFS_ERR_LOG" | tail -1 | cut -c1-200)"
# Every mounted filesystem needs its lockspace listed. After dlm_controld restarts with GFS2 mounted the list is empty, which is not healthy.
GFS2_DEVS="$(awk '$3=="gfs2"{print $1}' /proc/mounts | sort -u)"
DLM_NAMES="$(echo "$DLM_LS" | grep -c '^name ')"
FS_SAFE=0
if [ -n "$GFS2_TARGETS" ] && [ "$DLM_RC" -eq 0 ] && [ "$DLM_NAMES" -ge "$(echo "$GFS2_DEVS" | wc -l)" ] && ! echo "$DLM_LS" | grep -qE 'kern_stop|new change|new status|wait fenc'; then FS_SAFE=1; fi
echo "FS_SAFE=$FS_SAFE"

sec gfs2_fs
if [ "$FS_SAFE" = 1 ]; then
  gfs_read() {
    for m in $GFS2_TARGETS; do
      echo "USAGE=$m|$(df -B1 --output=size,used "$m" 2>/dev/null | awk 'NR==2{print $1"|"$2}')"
      echo "IN_USE=$m|$(fuser -m "$m" 2>/dev/null | wc -w)"
      find "$m/mvm-hb" -mindepth 3 -maxdepth 3 -name hb.properties 2>/dev/null | while read -r f; do
        echo "HB=$(sed -n 's/^hostname=//p' "$f")|$(sed -n 's/^lastUpdated=//p' "$f")"
      done
      # stat takes a cluster lock when another host has just written the file: the time a VM's metadata access would wait
      f="$(find "$m/mvm-hb" -mindepth 3 -maxdepth 3 -name hb.properties 2>/dev/null | head -1)"
      if [ -n "$f" ]; then s="$(date +%s%N)"; stat "$f" >/dev/null 2>&1; e="$(date +%s%N)"; echo "GFS_STAT=$m|$(( (e-s)/1000000 ))"; fi
    done
  }
  if bounded 15 gfs_read; then echo 'FS_TIMEOUT=0'; else echo 'FS_TIMEOUT=1'; fi
fi

sec vms
t 15 virsh -q list --all

sec vm_ops
echo "STATE_LOCK_15M=$(t 20 journalctl -u libvirtd --since '15 min ago' -o cat --no-pager | grep -c 'cannot acquire state change lock')"
echo "AGENT_KILLS_15M=$(t 20 journalctl -t sudo --since '15 min ago' -o cat --no-pager | grep 'morpheus-node' | grep -cE 'virsh (destroy|shutdown) |xargs kill')"

sec multipath
t 15 multipath -ll

sec multipath_queue
# multipathd's own view of no_path_retry: "on" queues for ever, "12 chk" or "30 sec" is a finite retry, "off" fails at once.
# dm's features line says queue_if_no_path for all but the last, so it cannot tell a finite retry from an endless one.
t 10 multipathd show maps format '%n %Q' | awk 'NR>1 && NF>1 {n=$1; $1=""; sub(/^ +/,""); print "MP_QUEUE="n"|"$0}'

sec iscsi
t 10 iscsiadm -m session | awk '{print $3}' | cut -d, -f1 | sed 's/:[0-9]*$//' | sort -u | while read -r p; do
  [ -n "$p" ] && echo "PORTAL_DEV=$p|$(ip -o route get "$p" 2>/dev/null | grep -oE 'dev [^ ]+' | cut -d' ' -f2)"
done
echo "SESSIONS=$(t 10 iscsiadm -m session | wc -l)"
echo "ERRORS_24H=$(t 20 journalctl -k --since '24 hours ago' -o cat --no-pager | grep -cE 'ping timeout of|rejecting I/O to offline')"

sec links_net
ip -o link show 2>/dev/null | awk -F': ' '{split($3,a," "); m="?"; for(i=1;i<=NF;i++){} print $2"|"$3}' | sed -E 's/@[^|]*//' | awk -F'|' '{ n=$1; s=$2; mtu="?"; st="?"; if (match(s,/mtu [0-9]+/)) mtu=substr(s,RSTART+4,RLENGTH-4); if (match(s,/state [A-Z]+/)) st=substr(s,RSTART+6,RLENGTH-6); print n"|"mtu"|"st }'

sec bonds
for b in /proc/net/bonding/*; do
  [ -r "$b" ] || continue
  awk -v b="$(basename "$b")" '
    /^Bonding Mode:/ {sub(/^Bonding Mode: /,""); print "BOND="b"|"$0}
    /^Slave Interface:/ {s=$3}
    /^MII Status:/ && s!="" {print "SLAVE="b"|"s"|"$3; s=""}' "$b"
done

sec addrs
ip -o -4 addr show 2>/dev/null | awk '$2!="lo"{print "ADDR="$2"|"$4}' | sed 's/@[^|]*|/|/'

sec ring
RING_ADDRS="$(awk '/ring0_addr:/{print $2}' /etc/corosync/corosync.conf 2>/dev/null)"
for a in $RING_ADDRS; do
  dev="$(ip -o -4 addr show 2>/dev/null | awk -v a="$a" '{split($4,p,"/"); if(p[1]==a)print $2}')"
  [ -n "$dev" ] && echo "RING_DEV=$dev" && echo "RING_ADDR=$a"
  ip -o -4 addr show 2>/dev/null | grep " $a/" | grep -q ' dynamic ' && echo 'RING_DYNAMIC=1'
done
# The physical ports under a device, down through VLANs, bonds and bridges, Linux or Open vSwitch. Guest taps have no device behind them.
ports_of() {
  local d="$1" depth="${2:-0}" l found=0
  [ "$depth" -gt 6 ] && return 0
  for l in /sys/class/net/"$d"/lower_*; do
    [ -e "$l" ] || continue
    found=1
    ports_of "${l##*/lower_}" $((depth + 1))
  done
  if [ "$found" = 0 ] && command -v ovs-vsctl >/dev/null 2>&1 && t 3 ovs-vsctl br-exists "$d" 2>/dev/null; then
    found=1
    for l in $(t 3 ovs-vsctl list-ports "$d" 2>/dev/null); do ports_of "$l" $((depth + 1)); done
  fi
  [ "$found" = 0 ] && [ -e "/sys/class/net/$d/device" ] && echo "$d"
  return 0
}
for d in $(ip -o -4 addr show 2>/dev/null | awk -v want=" $(echo $RING_ADDRS) " '{split($4,p,"/"); if (index(want, " " p[1] " ")) print $2}') \
         $(t 10 iscsiadm -m session | awk '{print $3}' | cut -d, -f1 | sed 's/:[0-9]*$//' | sort -u | while read -r p; do ip -o route get "$p" 2>/dev/null | grep -oE 'dev [^ ]+' | cut -d' ' -f2; done | sort -u); do
  echo "DEV_PORTS=$d|$(ports_of "$d" | sort -u | paste -sd, -)"
done
if [ "$MODE" = full ]; then
  for a in $RING_ADDRS; do
    ip -o -4 addr show 2>/dev/null | grep -q " $a/" && continue
    r="$(t 5 ping -c 3 -i 0.2 -W 1 -q "$a")"
    ssh=0; t 3 bash -c "exec 3<>/dev/tcp/$a/22" && ssh=1
    # the largest packet this interface claims to carry, unfragmented: a smaller path drops live-migration traffic
    mtu="$(ip -o link show "$(ip -o route get "$a" 2>/dev/null | grep -oE 'dev [^ ]+' | cut -d' ' -f2)" 2>/dev/null | grep -oE 'mtu [0-9]+' | cut -d' ' -f2)"
    pm=0; [ -n "$mtu" ] && t 4 ping -M do -s $((mtu - 28)) -c 2 -i 0.2 -W 1 -q "$a" >/dev/null && pm=1
    echo "PEER=$a|$(echo "$r" | grep -oE '[0-9]+% packet loss' | grep -oE '^[0-9]+')|$(echo "$r" | awk -F/ '/^rtt/{print $5}')|$ssh|$pm|$mtu"
  done
fi

sec vlans
# VLANs this host has heard traffic on from its uplinks: a VLAN its peers hear and it does not points at the trunk
t 5 bridge fdb show | grep -v permanent | awk '{d="";v="";for(i=1;i<=NF;i++){if($i=="dev")d=$(i+1); if($i=="vlan")v=$(i+1)}; if(v!="" && d!~/^vnet/)print v}' | sort -n | uniq -c | awk '{print "VLAN_SEEN="$2"|"$1}'
for br in $(t 5 ovs-vsctl list-br); do
  ups=" $(t 5 ovs-vsctl list-ports "$br" | grep -v '^vnet' | while read -r p; do t 5 ovs-vsctl get Interface "$p" ofport; done | tr '\n' ' ') "
  t 5 ovs-appctl fdb/show "$br" | awk -v ups="$ups" 'NR>1 && $2!="0" && index(ups," "$1" ")>0 {print $2}' | sort -n | uniq -c | awk '{print "VLAN_SEEN="$2"|"$1}'
done

sec storage
declare -A BEFORE
for d in /sys/block/dm-*; do
  n="$(cat "$d/dm/name" 2>/dev/null)"; case "$n" in 3*) BEFORE["$d"]="$(cat "$d/stat")";; esac
done
sleep 2
for d in "${!BEFORE[@]}"; do
  read -r r1 _ _ rt1 w1 _ _ wt1 _ <<< "${BEFORE[$d]}"
  read -r r2 _ _ rt2 w2 _ _ wt2 fl _ <<< "$(cat "$d/stat")"
  echo "STAT=$(cat "$d/dm/name")|$((r2-r1))|$((rt2-rt1))|$((w2-w1))|$((wt2-wt1))|$fl"
done
sec layout
echo "OVS_BRIDGES=$(t 5 ovs-vsctl list-br | tr '\n' ',')"
echo "VIRTSWITCH_FILES=$(ls /etc/netplan/ 2>/dev/null | grep -c hvmcli-virtswitch)"
echo "NETPLAN_FILES=$(ls /etc/netplan/ 2>/dev/null | tr '\n' ',')"

sec lldp
echo "LLDPCLI=$(command -v lldpcli)"
echo "LLDPD=$(t 5 systemctl is-active lldpd)"
[ -n "$(command -v lldpcli)" ] && t 8 lldpcli -f keyvalue show neighbors details

sec time
echo "NTP=$(t 5 timedatectl show -p NTP --value)"
echo "NTP_SYNCED=$(t 5 timedatectl show -p NTPSynchronized --value)"
echo "CHRONY=$(t 5 chronyc -c tracking)"
t 5 chronyc -c sources | sed 's/^/CHRONY_SOURCE=/'
echo "CHRONY_FOLLOWING=$(t 5 chronyc -N -c sources | awk -F, '$2=="*"{print $3; exit}')"
# only when it runs: asking over D-Bus could otherwise start it
if t 5 systemctl is-active --quiet systemd-timesyncd; then
  t 5 timedatectl timesync-status | sed -nE 's/^ *Server: *(.*)/TS_SERVER=\1/p; s/^ *Offset: *(.*)/TS_OFFSET=\1/p; s/^ *Packet count: *(.*)/TS_PACKETS=\1/p; s/^ *Stratum: *(.*)/TS_STRATUM=\1/p; s/^ *Leap: *(.*)/TS_LEAP=\1/p'
  t 5 timedatectl show-timesync --all | sed -nE 's/^SystemNTPServers=/TS_SYSTEM=/p; s/^LinkNTPServers=/TS_LINK=/p; s/^RuntimeNTPServers=/TS_RUNTIME=/p; s/^FallbackNTPServers=/TS_FALLBACK=/p'
fi
grep -hE '^\s*(server|pool)\s' /etc/chrony/chrony.conf /etc/chrony/sources.d/* /etc/chrony/conf.d/* 2>/dev/null | awk '{print "CONF_SERVER="$2}'
grep -hE '^\s*(NTP|FallbackNTP)=' /etc/systemd/timesyncd.conf /etc/systemd/timesyncd.conf.d/* 2>/dev/null | sed 's/^ */CONF_TIMESYNCD=/'

sec cpu
echo "CPU_MODEL=$(awk -F': *' '/^model name/{print $2; exit}' /proc/cpuinfo)"
echo "CPU_FLAGS=$(awk '/^flags/{ $1="";$2=""; print; exit}' /proc/cpuinfo | tr ' ' '\n' | sort | tr -d '\n' | sha256sum | cut -c1-16)"
echo "LIBVIRT_CPU=$(t 10 virsh capabilities | sed -n 's/.*<model>\(.*\)<\/model>.*/\1/p' | head -1)"
echo "USABLE_CPUS=$(t 10 virsh domcapabilities | sed -n "s/.*usable='yes'[^>]*>\(.*\)<\/model>.*/\1/p" | tr '\n' ',')"
echo "MEM_TOTAL_KB=$(awk '/^MemTotal:/{print $2}' /proc/meminfo)"
echo "MEM_AVAIL_KB=$(awk '/^MemAvailable:/{print $2}' /proc/meminfo)"

sec pools
t 15 virsh -q pool-list --all

sec packages
for p in corosync dlm-controld gfs2-utils libvirt-daemon-system qemu-system-x86 open-iscsi multipath-tools morpheus-node; do
  v="$(dpkg-query -W -f='${Version}' "$p" 2>/dev/null)"; [ -n "$v" ] && echo "$p|$v"
done
# the agent is not a package on Morpheus-built hosts: morpheus-node-ctl reads its version from this link, and so does this
v="$(readlink /opt/morpheus-node/lib/morpheus/morpheus-agent.jar 2>/dev/null | sed -E 's/^morphd(-ws)?-//; s/(-all)?\.jar$//')"
[ -n "$v" ] || v="$(sed -n 's/^version=//p' /opt/morpheus-node/embedded/.build-info 2>/dev/null)"
[ -n "$v" ] && echo "morpheus-agent|$v"

sec qdevice
# corosync-quorumtool's own view of a quorum device is already in @@quorum; a host with none stops at this line
echo "QDEVICE_TOOL=$(command -v corosync-qdevice-tool)"
grep -qE '^\s*device\s*\{' /etc/corosync/corosync.conf 2>/dev/null && t 10 corosync-qdevice-tool -s | sed 's/^/QD=/'

sec vm_cpu
# from the process, so it is what each VM runs now rather than what its definition would start: -cpu host is host-passthrough
vm_cpus() { ps -eo args= | awk '/qemu-system/ {g="";c="";n=0; for(i=1;i<=NF;i++){ if($i ~ /^guest=/){g=$i; sub(/^guest=/,"",g); sub(/,.*/,"",g)} if($i=="-cpu"){c=$(i+1); n=(c ~ /migratable=off|invtsc=on/)?1:0; sub(/,.*/,"",c)} } if(g!="")print "VM_CPU="g"|"c"|"n}'; }
bounded 5 vm_cpus

sec pool_paths
# which datastore each GFS2 path is. libvirt answers this from memory, and it is only asked when DLM is healthy all the same
[ "$FS_SAFE" = 1 ] && t 10 virsh -q pool-list --name | while read -r p; do [ -n "$p" ] && echo "POOL_PATH=$(t 5 virsh pool-dumpxml "$p" | sed -n 's/.*<path>\(.*\)<\/path>.*/\1/p' | head -1)|$p"; done

if [ "$MODE" = full ]; then
  sec journals
  # reads the device, where with its paths lost and I/O queueing it sleeps unkillably. Nothing read is not a count: the parser takes that as unread
  journal_count() { gfs2_edit -p jindex "$1" | grep -cE '^ *(journal[0-9]+|[0-9]+/[0-9]+ .*journal[0-9]+)'; }
  if [ "$FS_SAFE" = 1 ]; then
    for dev in $GFS2_DEVS; do echo "JOURNALS=$dev|$(bounded 15 journal_count "$dev")"; done
  fi
  sec bmc
  # empty answers from ipmitool below mean nothing when it is not installed, as on a Morpheus-built 1.3 host
  echo "IPMITOOL=$(command -v ipmitool)"
  echo "BMC_IP=$(t 15 ipmitool lan print | awk -F': *' '/^IP Address +:/{print $2}')"
  echo "WATCHDOG=$(ls /dev/watchdog* 2>/dev/null | head -1)"
  sec hardware
  echo "HW_PRODUCT=$(t 5 dmidecode -s system-product-name)"
  echo "HW_BIOS=$(t 5 dmidecode -s bios-version)"
  echo "HW_BMC=$(t 10 ipmitool mc info | awk -F': *' '/^Firmware Revision/{print $2}')"
  for b in /proc/net/bonding/*; do
    [ -r "$b" ] || continue
    for s in $(awk '/^Slave Interface:/{print $3}' "$b"); do
      echo "NIC=$s|$(t 3 ethtool -i "$s" | awk -F': ' '/^firmware-version/{print $2}')|$(cat "/sys/class/net/$s/speed" 2>/dev/null)|$(basename "$b")"
    done
  done
  for m in /sys/devices/system/edac/mc/mc*; do [ -d "$m" ] && echo "EDAC=$(basename "$m")|$(cat "$m/ce_count")|$(cat "$m/ue_count")"; done
  for ty in 'Power Supply' Temperature; do t 15 ipmitool sdr type "$ty" | awk -F' *\\| *' -v ty="$ty" '{print "SDR="ty"|"$1"|"$3"|"$5}'; done
  t 15 ipmitool sel elist | tail -40 | sed 's/^/SEL=/'
  sec certs
  # public certificates only, by exact name: never the private directories beside them. "date -d ''" means today, so an unread date stays empty
  echo "OPENSSL=$(command -v openssl)"
  echo "TLS_SOCKETS=$(t 5 systemctl is-active libvirtd-tls.socket virtqemud-tls.socket | grep -c '^active')"
  echo "TLS_CONF=$(cat /etc/libvirt/libvirtd.conf /etc/libvirt/qemu.conf 2>/dev/null | grep -cE '^\s*(listen_tls|vnc_tls|spice_tls|chardev_tls|migrate_tls_force|nbd_tls)\s*=\s*1')"
  for c in $( (echo /etc/pki/CA/cacert.pem /etc/pki/libvirt/clientcert.pem /etc/pki/libvirt/servercert.pem /etc/pki/qemu/ca-cert.pem /etc/pki/qemu/client-cert.pem /etc/pki/qemu/server-cert.pem | tr ' ' '\n'; grep -E '^\s*(cert_file|ca_file)\s*=' /etc/libvirt/libvirtd.conf 2>/dev/null | cut -d'"' -f2) | sort -u); do
    [ -r "$c" ] || continue
    e="$(t 5 openssl x509 -noout -enddate -in "$c" | cut -d= -f2)"
    echo "CERT=$c|$([ -n "$e" ] && date -d "$e" +%s 2>/dev/null)"
  done
  sec smart
  # the disks under /. Serial numbers and WWNs stay on the host. A disk behind a RAID controller or a hypervisor answers with no health line
  echo "SMARTCTL=$(command -v smartctl)"
  for d in $(lsblk -s -n -l -o NAME,TYPE "$(findmnt -n -v -o SOURCE /)" 2>/dev/null | awk '$2=="disk"{print $1}' | sort -u); do
    echo "BOOT_DISK=$d"
    [ -n "$(command -v smartctl)" ] && t 15 smartctl -H -A -i "/dev/$d" | grep -viE 'serial|wwn|logical unit id' | sed "s/^/SMART=$d|/"
  done
  sec vm_ports
  # each running VM's ports and the VLANs its bridge gives them, from the bridge rather than the VM's definition. Bounded, as libvirt can hang on a VM stuck on its disk
  ports_end=$((SECONDS + 25))
  t 15 virsh -q list --name | while read -r d; do
    [ -n "$d" ] || continue
    [ "$SECONDS" -lt "$ports_end" ] || { echo 'PORTS_CUT=1'; break; }
    t 5 virsh domiflist "$d" | awk 'NR>2 && NF>=5 {print $1, $5}' | while read -r dev mac; do
      [ "$dev" != "-" ] || continue
      if br="$(t 3 ovs-vsctl port-to-br "$dev")" && [ -n "$br" ]; then
        echo "OVS_PORT=$d|$dev|$mac|$br|$(t 3 ovs-vsctl get Port "$dev" tag)|$(t 3 ovs-vsctl get Port "$dev" trunks)|$(t 3 ovs-vsctl get Port "$dev" vlan_mode)"
      else
        echo "BR_PORT=$d|$dev|$mac|$(basename "$(readlink "/sys/class/net/$dev/master" 2>/dev/null)")|$(cat "/sys/class/net/$dev/master/bridge/vlan_filtering" 2>/dev/null)|$(t 3 bridge -j vlan show dev "$dev" | tr -d '\n')"
      fi
    done
  done
fi

sec end
# what was reached before the limit is good, and what was skipped must not read as nothing wrong
[ "$SECONDS" -lt "$BUDGET" ] || echo 'CUT=1'
echo "COMPLETE=1"
