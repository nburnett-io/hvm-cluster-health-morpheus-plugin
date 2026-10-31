#!/bin/bash
# HVM Cluster Health: read-only host probe. Changes nothing on the host.
#   $1 = full (default) or quick (skips the slower checks; the monitor's frequent run)
#
# Rule that shapes this script: while a DLM lockspace is stopped, ANY access to a GFS2 mount blocks in uninterruptible
# sleep and cannot be killed, not even by timeout. So nothing here touches a GFS2 path unless DLM reports every
# lockspace healthy, and even then only from a background job the script does not wait on past a deadline.
export LC_ALL=C PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin
MODE="${1:-full}"
t() { local s="$1"; shift; timeout -k 2 "$s" "$@" 2>/dev/null; }
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

sec services
for u in corosync dlm pacemaker pcsd libvirtd multipathd iscsid chrony systemd-timesyncd morpheus-morphd; do
  echo "$u|$(systemctl is-active "$u" 2>/dev/null)|$(systemctl is-enabled "$u" 2>/dev/null)"
done

sec corosync_conf
if [ -r /etc/corosync/corosync.conf ]; then cat /etc/corosync/corosync.conf; fi

sec corosync_key
[ -r /etc/corosync/authkey ] && sha256sum /etc/corosync/authkey | cut -c1-16

sec quorum
t 10 corosync-quorumtool -s

sec links
t 10 corosync-cfgtool -s

sec totem
t 10 corosync-cmapctl | grep -E '^runtime\.config\.totem\.(token|consensus) '

sec dlm_ls
DLM_LS="$(t 10 dlm_tool ls)"; DLM_RC=$?
echo "$DLM_LS"

sec dlm_status
t 10 dlm_tool status

sec dlm_conf
grep -vE '^\s*(#|$)' /etc/dlm/dlm.conf 2>/dev/null

sec fence_acks
t 20 journalctl -t sudo --since '15 min ago' -o cat --no-pager | grep 'morpheus-node' | grep -oE 'dlm_tool fence_ack [0-9]+' | awk '{print $3}' | sort | uniq -c | awk '{print $2"|"$1}'

sec gfs2
GFS2_TARGETS="$(awk '$3=="gfs2"{print $2}' /proc/mounts)"
awk '$3=="gfs2"{print "MOUNT="$1"|"$2}' /proc/mounts
for d in /sys/fs/gfs2/*; do
  [ -d "$d" ] && echo "TABLE=$(basename "$d")|$(cat "$d/withdraw" 2>/dev/null)|$(cat "$d/freeze" 2>/dev/null)"
done
FS_SAFE=0
if [ -n "$GFS2_TARGETS" ] && [ "$DLM_RC" -eq 0 ] && ! echo "$DLM_LS" | grep -qE 'kern_stop|new change|new status|wait fenc'; then FS_SAFE=1; fi
echo "FS_SAFE=$FS_SAFE"

sec gfs2_fs
if [ "$FS_SAFE" = 1 ]; then
  OUT="$(mktemp /run/hvm-health.XXXXXX)"
  (
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
  ) > "$OUT" 2>/dev/null &
  JOB=$!
  for _ in $(seq 1 30); do kill -0 "$JOB" 2>/dev/null || break; sleep 0.5; done
  if kill -0 "$JOB" 2>/dev/null; then echo 'FS_TIMEOUT=1'; else echo 'FS_TIMEOUT=0'; fi
  cat "$OUT"; rm -f "$OUT"
fi

sec stuck
echo "D_COUNT=$(ps -eo state= | grep -c '^D')"
ps -eo state=,etimes=,comm= | awk '$1=="D"{c[$3]++; if($2>m)m=$2} END{print "D_OLDEST="m+0; for(k in c)print "D_COMM="k"|"c[k]}'
ps -eo pid=,state=,args= | awk '$2=="D" && /qemu-system/' | grep -oE 'guest=[^, ]+' | sed 's/guest=/D_VM=/' | sort -u
echo "HUNG_1H=$(t 20 journalctl -k --since '1 hour ago' -o cat --no-pager | grep -c 'blocked for more than')"

sec vms
t 15 virsh -q list --all

sec vm_ops
echo "STATE_LOCK_15M=$(t 20 journalctl -u libvirtd --since '15 min ago' -o cat --no-pager | grep -c 'cannot acquire state change lock')"
echo "AGENT_KILLS_15M=$(t 20 journalctl -t sudo --since '15 min ago' -o cat --no-pager | grep 'morpheus-node' | grep -cE 'virsh (destroy|shutdown) |xargs kill')"

sec multipath
t 15 multipath -ll

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
done
if [ "$MODE" = full ]; then
  for a in $RING_ADDRS; do
    ip -o -4 addr show 2>/dev/null | grep -q " $a/" && continue
    r="$(t 5 ping -c 3 -i 0.2 -W 1 -q "$a")"
    ssh=0; timeout 3 bash -c "exec 3<>/dev/tcp/$a/22" 2>/dev/null && ssh=1
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
echo "LLDPD=$(systemctl is-active lldpd 2>/dev/null)"
[ -n "$(command -v lldpcli)" ] && t 8 lldpcli -f keyvalue show neighbors details

sec time
echo "NTP=$(t 5 timedatectl show -p NTP --value)"
echo "NTP_SYNCED=$(t 5 timedatectl show -p NTPSynchronized --value)"
echo "CHRONY=$(t 5 chronyc -c tracking)"
t 5 chronyc -c sources | sed 's/^/CHRONY_SOURCE=/'
echo "CHRONY_FOLLOWING=$(t 5 chronyc -N -c sources | awk -F, '$2=="*"{print $3; exit}')"
t 5 timedatectl timesync-status | awk -F': *' '/^ *Server:/{print "TS_SERVER="$2} /^ *Offset:/{print "TS_OFFSET="$2} /^ *Packet count:/{print "TS_PACKETS="$2}'
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

if [ "$MODE" = full ]; then
  sec journals
  awk '$3=="gfs2"{print $1}' /proc/mounts | while read -r dev; do
    echo "JOURNALS=$dev|$(t 15 gfs2_edit -p jindex "$dev" | grep -cE '^ *(journal[0-9]+|[0-9]+/[0-9]+ .*journal[0-9]+)')"
  done
  sec bmc
  echo "BMC_IP=$(t 15 ipmitool lan print | awk -F': *' '/^IP Address +:/{print $2}')"
  echo "WATCHDOG=$(ls /dev/watchdog* 2>/dev/null | head -1)"
  sec hardware
  echo "HW_PRODUCT=$(t 5 dmidecode -s system-product-name)"
  echo "HW_BIOS=$(t 5 dmidecode -s bios-version)"
  echo "HW_BMC=$(t 10 ipmitool mc info | awk -F': *' '/^Firmware Revision/{print $2}')"
  for b in /proc/net/bonding/*; do
    [ -r "$b" ] || continue
    for s in $(awk '/^Slave Interface:/{print $3}' "$b"); do
      echo "NIC=$s|$(t 3 ethtool -i "$s" | awk -F': ' '/^firmware-version/{print $2}')|$(cat "/sys/class/net/$s/speed" 2>/dev/null)"
    done
  done
  for m in /sys/devices/system/edac/mc/mc*; do [ -d "$m" ] && echo "EDAC=$(basename "$m")|$(cat "$m/ce_count")|$(cat "$m/ue_count")"; done
  for ty in 'Power Supply' Temperature; do t 15 ipmitool sdr type "$ty" | awk -F' *\\| *' -v ty="$ty" '{print "SDR="ty"|"$1"|"$3"|"$5}'; done
  t 15 ipmitool sel elist | tail -40 | sed 's/^/SEL=/'
fi

sec end
echo "COMPLETE=1"
