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
# A tenant chooses its VM's name, and that name flows into |-delimited, @@-sectioned output below. Strip control characters
# (newline and tab included) and turn | into _, so a crafted name cannot forge a section marker or shift another field.
san() { printf '%s' "$1" | tr -d '\000-\037\177' | tr '|' '_'; }

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
echo "DLM_RC=$DLM_RC"

sec dlm_status
t 10 dlm_tool status

sec dlm_conf
grep -vE '^\s*(#|$)' /etc/dlm/dlm.conf 2>/dev/null

sec quorum
t 10 corosync-quorumtool -s

sec agent_quorum
# HPE's agent quorum is the operational authority on layouts 1.3 and 2.0; corosync Quorate is not (it reads No on healthy two-node and
# stretch clusters). Read over HTTPS to localhost and from a local file: on the host, never a GFS2 path, so safe here. The payload shape is
# only partly documented, so the parser reads NOT CHECKED on any surprise and never a pass. -k is what HPE documents and reaches localhost only.
AQ="$(t 5 curl -sk --max-time 4 https://127.0.0.1:7443/quorum)"
[ -n "$AQ" ] && echo "AGENT_QUORUM=$(printf '%s' "$AQ" | tr -d '\r\n' | head -c 4000)"
[ -r /opt/morpheus-node/.quorum-nodes ] && echo "QUORUM_NODES=$(tr -d '\r\n' < /opt/morpheus-node/.quorum-nodes | head -c 4000)"

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
GFS_BLOCKED=0
for d in /sys/fs/gfs2/*; do
  [ -d "$d" ] || continue
  echo "TABLE=$(basename "$d")|$(cat "$d/withdraw" 2>/dev/null)|$(cat "$d/freeze" 2>/dev/null)"
  # DFL_BLOCK_LOCKS: GFS2 locking is blocked on this host (recovery sets it briefly). A safe read: no cluster lock, no I/O.
  blk="$(cat "$d/lock_module/block" 2>/dev/null)"
  [ -n "$blk" ] && echo "BLOCK=$(basename "$d")|$blk"
  [ "$blk" = 1 ] && GFS_BLOCKED=1
done
# -t kernel rather than -k: -k is this boot only, and the agent reboots a host soon after a withdrawal.
# Case-insensitive, with the wordings the kernel uses for a withdrawal or consistency error (read from GFS2 source; the exact lines are not yet seen on a live host).
GFS_ERR_RE='gfs2: fsid=.*(consistency error|withdrawn|about to withdraw|fatal:|lvb (flag|free|dinode|igen) mismatch)|file system withdrawn'
# The QUECVT/-22 conversion failure HPE documents (KRN-05) is counted on its own, not folded into the withdrawal count.
CONV_ERR_RE='validate_lock_args -22|lm_lock ret -22'
# One read, both patterns, so a quick check pays for the 7-day kernel log once. A log that could not be read prints no count: grep alone would say 0, and clear a critical finding.
GFS_LOG="$(t 20 journalctl -t kernel --since '7 days ago' -o short-iso --no-pager | grep -iE "$GFS_ERR_RE|$CONV_ERR_RE"; exit "${PIPESTATUS[0]}")" && GFS_LOG_READ=1
if [ -n "$GFS_LOG_READ" ]; then
  echo "GFS2_ERRORS_7D=$(printf '%s\n' "$GFS_LOG" | grep -iE "$GFS_ERR_RE" | grep -c .)"
  echo "DLM_CONV_ERR_7D=$(printf '%s\n' "$GFS_LOG" | grep -iE "$CONV_ERR_RE" | grep -c .)"
fi
echo "GFS2_ERROR_LAST=$(printf '%s\n' "$GFS_LOG" | grep -iE "$GFS_ERR_RE" | tail -1 | cut -c1-200)"
echo "DLM_CONV_ERR_LAST=$(printf '%s\n' "$GFS_LOG" | grep -iE "$CONV_ERR_RE" | tail -1 | cut -c1-200)"
# Every mounted filesystem needs its lockspace listed. After dlm_controld restarts with GFS2 mounted the list is empty, which is not healthy.
GFS2_DEVS="$(awk '$3=="gfs2"{print $1}' /proc/mounts | sort -u)"
DLM_NAMES="$(echo "$DLM_LS" | grep -c '^name ')"
FS_SAFE=0
if [ -n "$GFS2_TARGETS" ] && [ "$DLM_RC" -eq 0 ] && [ "$DLM_NAMES" -ge "$(echo "$GFS2_DEVS" | wc -l)" ] && [ "$GFS_BLOCKED" != 1 ] && ! echo "$DLM_LS" | grep -qE 'kern_stop|new change|new status|wait fenc'; then FS_SAFE=1; fi
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
  if bounded 15 gfs_read; then echo 'FS_TIMEOUT=0'; else echo 'FS_TIMEOUT=1'; FS_HUNG=1; fi
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
# polling_interval sets the no_path_retry window in seconds; fast_io_fail_tmo is what multipathd writes into recovery_tmo. First (defaults) occurrence only.
t 10 multipathd show config 2>/dev/null | awk '
  $1=="polling_interval" && !p {print "MP_POLLING="$2; p=1}
  $1=="fast_io_fail_tmo" && !f {print "MP_FAST_IO_FAIL="$2; f=1}'

sec iscsi
t 10 iscsiadm -m session | awk '{print $3}' | cut -d, -f1 | sed 's/:[0-9]*$//' | sort -u | while read -r p; do
  [ -n "$p" ] && echo "PORTAL_DEV=$p|$(ip -o route get "$p" 2>/dev/null | grep -oE 'dev [^ ]+' | cut -d' ' -f2)"
done
echo "SESSIONS=$(t 10 iscsiadm -m session | wc -l)"
echo "ERRORS_24H=$(t 20 journalctl -k --since '24 hours ago' -o cat --no-pager | grep -cE 'ping timeout of|rejecting I/O to offline')"

sec fc
# Fibre Channel transport, read-only sysfs. A host with none prints only FC_PRESENT=0. Statistics are hex in sysfs, so printed as decimal; roles and speed can hold a space, joined with _.
if ls -d /sys/class/fc_host/host* >/dev/null 2>&1; then
  echo "FC_PRESENT=1"
  for h in /sys/class/fc_host/host*; do
    [ -e "$h" ] || continue
    lf=$(cat "$h/statistics/link_failure_count" 2>/dev/null); lf=$(( ${lf:-0} + 0 ))
    sy=$(cat "$h/statistics/loss_of_sync_count" 2>/dev/null); sy=$(( ${sy:-0} + 0 ))
    sg=$(cat "$h/statistics/loss_of_signal_count" 2>/dev/null); sg=$(( ${sg:-0} + 0 ))
    cr=$(cat "$h/statistics/invalid_crc_count" 2>/dev/null); cr=$(( ${cr:-0} + 0 ))
    echo "FC_HOST=$(basename "$h")|$(cat "$h/port_state" 2>/dev/null)|$(cat "$h/speed" 2>/dev/null | tr ' ' '_')|$(cat "$h/port_name" 2>/dev/null)|$lf|$sy|$sg|$cr"
  done
  for r in /sys/class/fc_remote_ports/rport-*; do
    [ -e "$r" ] || continue
    echo "FC_RPORT=$(basename "$r")|$(cat "$r/port_state" 2>/dev/null)|$(cat "$r/roles" 2>/dev/null | tr ' ' '_')|$(cat "$r/dev_loss_tmo" 2>/dev/null)|$(cat "$r/fast_io_fail_tmo" 2>/dev/null)"
  done
else
  echo "FC_PRESENT=0"
fi

sec mpath_paths
# transport per path (%P: scsi:iscsi, scsi:fcp, ...) and host adapter. %A (TPG) and %k (max_sectors_kb) exist only on 0.14.3 (26.04), so they are only asked for there, and MPP_EXT says which columns were read.
if dpkg --compare-versions "$(dpkg-query -W -f='${Version}' multipath-tools 2>/dev/null)" ge 0.14.3 2>/dev/null; then
  echo "MPP_EXT=1"
  t 10 multipathd show paths format '%w %d %P %a %A %k' | awk 'NR>1 && NF>=6 {print "MPP="$1"|"$2"|"$3"|"$4"|"$5"|"$6}'
else
  echo "MPP_EXT=0"
  t 10 multipathd show paths format '%w %d %P %a' | awk 'NR>1 && NF>=4 {print "MPP="$1"|"$2"|"$3"|"$4}'
fi

sec iscsi_timers
# node.startup per target, read from the open-iscsi node db files (no iscsiadm -o, which the write-op guard flags): a rebooted host returns with no storage if a target is not automatic. recovery_tmo per session: multipathd writes fast_io_fail_tmo into it, the live per-path timer.
for n in /etc/iscsi/nodes/*/*/default; do
  [ -r "$n" ] || continue
  tgt="$(sed -n 's/^node.name = //p' "$n")"
  [ -n "$tgt" ] && echo "NODE_STARTUP=$tgt|$(sed -n 's/^node.startup = //p' "$n")"
done
for s in /sys/class/iscsi_session/session*; do
  [ -e "$s/recovery_tmo" ] || continue
  echo "RECOVERY_TMO=$(basename "$s")|$(cat "$s/recovery_tmo" 2>/dev/null)"
done

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
    # HPE requires TCP 7443 between every host (the agent's heartbeat and quorum pings run on it); a block here makes a peer count as unreachable
    agent=0; t 3 bash -c "exec 3<>/dev/tcp/$a/7443" && agent=1
    # the largest packet this interface claims to carry, unfragmented: a smaller path drops live-migration traffic
    mtu="$(ip -o link show "$(ip -o route get "$a" 2>/dev/null | grep -oE 'dev [^ ]+' | cut -d' ' -f2)" 2>/dev/null | grep -oE 'mtu [0-9]+' | cut -d' ' -f2)"
    pm=0; [ -n "$mtu" ] && t 4 ping -M do -s $((mtu - 28)) -c 2 -i 0.2 -W 1 -q "$a" >/dev/null && pm=1
    echo "PEER=$a|$(echo "$r" | grep -oE '[0-9]+% packet loss' | grep -oE '^[0-9]+')|$(echo "$r" | awk -F/ '/^rtt/{print $5}')|$ssh|$pm|$mtu|$agent"
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
# over the same 2 s, so neither adds a wait: pages swapped, and CPU time a hypervisor under this host took. A counter not read prints no line
swap_pages() { awk '$1=="pswpin"{i=$2} $1=="pswpout"{o=$2} END{if(i!="" && o!="")print i, o}' /proc/vmstat 2>/dev/null; }
cpu_ticks() { awk '$1=="cpu"{printf "%.0f %.0f\n", $2+$3+$4+$5+$6+$7+$8+$9, $9; exit}' /proc/stat 2>/dev/null; }
read -r swi1 swo1 <<< "$(swap_pages)"
read -r tick1 steal1 <<< "$(cpu_ticks)"
sleep 2
HOT_IO=0
for d in "${!BEFORE[@]}"; do
  read -r r1 _ _ rt1 w1 _ _ wt1 _ <<< "${BEFORE[$d]}"
  read -r r2 _ _ rt2 w2 _ _ wt2 fl _ <<< "$(cat "$d/stat")"
  echo "STAT=$(cat "$d/dm/name")|$((r2-r1))|$((rt2-rt1))|$((w2-w1))|$((wt2-wt1))|$fl"
  # the default of storageWarnMs, on the sample size STO-06 trusts: this only decides whether the busiest VMs are worth reading
  ios=$(( (r2-r1) + (w2-w1) ))
  if [ "$ios" -ge 20 ] && [ $(( ((rt2-rt1) + (wt2-wt1)) / ios )) -ge 20 ]; then HOT_IO=1; fi
done
read -r swi2 swo2 <<< "$(swap_pages)"
read -r tick2 steal2 <<< "$(cpu_ticks)"
sec host_sample
if [ -n "$swi1" ] && [ -n "$swi2" ]; then echo "SWAP_SAMPLE=$((swi2-swi1))|$((swo2-swo1))"; fi
if [ -n "$tick1" ] && [ -n "$tick2" ]; then echo "CPU_SAMPLE=$((tick2-tick1))|$((steal2-steal1))"; fi
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

sec host_health
# /proc, the kernel log and named local paths only, so all of this still answers in a freeze. What cannot be read prints nothing: the parser takes that as not checked, never as a pass.
psi() { awk '$1=="some"{for(i=2;i<=NF;i++) if($i ~ /^avg60=/){sub(/^avg60=/,"",$i); print $i}}' "/proc/pressure/$1" 2>/dev/null; }
echo "PSI_CPU=$(psi cpu)"
echo "PSI_MEMORY=$(psi memory)"
PSI_IO="$(psi io)"
# -t kernel rather than -k, as in @@gfs2: the agent can reboot a host soon after. "oom-kill:" comes first, and is the only line saying whose cgroup the victim was in
OOM_LOG="$(t 15 journalctl -t kernel --since '24 hours ago' -o short-iso --no-pager | grep -E 'Killed process [0-9]+ \(|oom-kill:'; exit "${PIPESTATUS[0]}")" && echo 'OOM_READ=1'
printf '%s\n' "$OOM_LOG" | awk '
  /oom-kill:/ { v = ""; if (match($0, /machine-qemu[^ ,\/]*\.scope/)) v = substr($0, RSTART, RLENGTH) }
  /Killed process [0-9]+ \(/ { c = $0; sub(/.*Killed process [0-9]+ \(/, "", c); sub(/\).*/, "", c); print "OOM=" $1 "|" c "|" v; v = "" }' | tail -20
# how libvirt ends a VM it was not told to stop, which includes a qemu the kernel killed. Local files, the ones touched in the last day, and only the end of each
vm_crashes() {
  [ -d /var/log/libvirt/qemu ] || return 0
  find /var/log/libvirt/qemu -maxdepth 1 -name '*.log' -mmin -1500 2>/dev/null | head -500 | while read -r f; do
    tail -c 16384 "$f" 2>/dev/null | grep -a 'reason=crashed' | tail -3 | cut -c1-40 | awk -v n="$(basename "$f" .log)" '{print "VM_CRASH=" n "|" $0}'
  done
  echo 'VM_LOGS_READ=1'
}
bounded 5 vm_crashes
# Named paths only, and in a job as it could sleep with a dead root disk. A bare df, or df -x gfs2, stats every mount, and that blocks on a frozen GFS2.
root_df() { df --output=source,target,pcent,ipcent / /var /var/log /tmp /boot 2>/dev/null | awk 'NR>1 && !s[$1]++ {gsub(/%/, ""); print "ROOT_DF=" $2 "|" $3 "|" $4}'; }
bounded 5 root_df || echo 'ROOT_DF_CUT=1'
# The units systemd holds as failed, from its own memory: no unit file or mount is read. FAILED_READ says the list was read, so none listed is none failed.
# A unit name can carry a VM's name (machine-qemu-...scope), so only the characters systemd allows in a name are passed on.
if FAILED="$(t 5 systemctl --failed --no-legend --plain)"; then
  echo 'FAILED_READ=1'
  # the name is the first word, or the second where a systemd prints its status marker first
  awk 'NF {u = ($1 ~ /\./) ? $1 : $2; gsub(/[^A-Za-z0-9@._:\\-]/, "_", u); print "FAILED_UNIT=" u}' <<<"$FAILED" | head -40
fi

if [ "$MODE" = full ]; then
  sec journals
  # reads the device, where with its paths lost and I/O queueing it sleeps unkillably. Nothing read is not a count: the parser takes that as unread
  journal_count() { gfs2_edit -p jindex "$1" | grep -cE '^ *(journal[0-9]+|[0-9]+/[0-9]+ .*journal[0-9]+)'; }
  if [ "$FS_SAFE" = 1 ]; then
    for dev in $GFS2_DEVS; do echo "JOURNALS=$dev|$(bounded 15 journal_count "$dev")"; done
  fi
  sec bmc
  # empty answers from ipmitool below mean nothing when it is not installed, as on a Morpheus-built 1.3 host (2.0 ships it). The controller
  # answers once first: a wedged one would hold every read below to its limit, and the whole probe's budget with them.
  echo "IPMITOOL=$(command -v ipmitool)"
  MC=""; command -v ipmitool >/dev/null && MC="$(t 10 ipmitool mc info)"
  ipmi() { [ -n "$MC" ] && t "$@"; }
  echo "BMC_IP=$(ipmi 15 ipmitool lan print | awk -F': *' '/^IP Address +:/{print $2}')"
  echo "WATCHDOG=$(ls /dev/watchdog* 2>/dev/null | head -1)"
  if [ -n "$MC" ]; then
    echo "BMC_SELFTEST=$(t 10 ipmitool mc selftest | sed -n 's/^Selftest: *//p')"
    t 10 ipmitool chassis status | awk -F' *: *' '/^(Power Overload|Main Power Fault|Power Control Fault|Drive Fault|Cooling\/Fan Fault) *:/{print "CHASSIS=" $1 "|" $2}'
    t 10 ipmitool sel info | awk -F' *: *' '/^Percent Used/{sub(/%.*/, "", $2); print "SEL_USED=" $2} /^Overflow/{print "SEL_OVERFLOW=" $2}'
    # every sensor not ok, whatever its type, and a discrete one whose reading names a failure, as its status column still says ok.
    # SDR_READ says the list was read to its end: nothing listed from a list cut short is not nothing failing.
    if o="$(t 20 ipmitool sdr elist)"; then
      echo 'SDR_READ=1'
      awk -F' *[|] *' 'NF >= 5 && $3 != "ns" && ($3 != "ok" || tolower($5) ~ /fail|fault|error|ierr|critical|predictive|non-recoverable/) {print "SDR_BAD=" $1 "|" $3 "|" $5}' <<<"$o" | head -20
    fi
  fi
  sec hardware
  echo "HW_PRODUCT=$(t 5 dmidecode -s system-product-name)"
  echo "HW_BIOS=$(t 5 dmidecode -s bios-version)"
  echo "HW_BMC=$(awk -F': *' '/^Firmware Revision/{print $2}' <<<"$MC")"
  for b in /proc/net/bonding/*; do
    [ -r "$b" ] || continue
    for s in $(awk '/^Slave Interface:/{print $3}' "$b"); do
      echo "NIC=$s|$(t 3 ethtool -i "$s" | awk -F': ' '/^firmware-version/{print $2}')|$(cat "/sys/class/net/$s/speed" 2>/dev/null)|$(basename "$b")"
    done
  done
  for m in /sys/devices/system/edac/mc/mc*; do [ -d "$m" ] && echo "EDAC=$(basename "$m")|$(cat "$m/ce_count")|$(cat "$m/ue_count")"; done
  for ty in 'Power Supply' Temperature; do ipmi 15 ipmitool sdr type "$ty" | awk -F' *\\| *' -v ty="$ty" '{print "SDR="ty"|"$1"|"$3"|"$5}'; done
  # "last" reads only those: a full log read whole can be cut at the limit partway, leaving entries from its middle
  ipmi 15 ipmitool sel elist last 40 | sed 's/^/SEL=/'
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
    # which SCSI host it hangs off: smartctl's /dev/bus/N is SCSI host N, which is how a RAID volume is matched to the physical disks on its own controller
    echo "BOOT_HOST=$d|$(lsblk -dn -o HCTL "/dev/$d" 2>/dev/null | cut -d: -f1 | tr -d ' ')"
    [ -n "$(command -v smartctl)" ] && t 15 smartctl -H -A -i "/dev/$d" | grep -viE 'serial|wwn|logical unit id' | sed "s/^/SMART=$d|/"
  done
  sec thin
  # the array's warning that a thin LUN crossed its soft limit (SCSI sense 38/07). A log that could not be read prints no count: grep alone would say 0
  THIN_LOG="$(t 20 journalctl -t kernel --since '7 days ago' -o short-iso --no-pager | grep -iE 'thin provisioning soft threshold'; exit "${PIPESTATUS[0]}")" && echo "THIN_SOFT_7D=$(printf '%s' "$THIN_LOG" | grep -c .)"
  echo "THIN_SOFT_LAST=$(printf '%s\n' "$THIN_LOG" | tail -1 | cut -c1-200)"
  # the hard space-allocation failure (sense 27/07 DATA PROTECT): a withdraw with df still showing free. Separate grep, the same no-count-when-unread rule
  HARD_LOG="$(t 20 journalctl -t kernel --since '7 days ago' -o short-iso --no-pager | grep -iE 'critical space allocation|space allocation failed write protect'; exit "${PIPESTATUS[0]}")" && echo "THIN_HARD_7D=$(printf '%s' "$HARD_LOG" | grep -c .)"
  echo "THIN_HARD_LAST=$(printf '%s\n' "$HARD_LOG" | tail -1 | cut -c1-200)"
  sec mpath_log
  # all paths lost on a map over 7 days: multipathd logs "remaining active paths: 0" and "Entering recovery mode" when a map runs out of paths. After the fact this is the key near-miss. Kept in a storage section, not @@gfs2.
  MPATH_LOG="$(t 20 journalctl -t multipathd --since '7 days ago' -o short-iso --no-pager | grep -iE 'remaining active paths: 0|Entering recovery mode'; exit "${PIPESTATUS[0]}")" && MPATH_READ=1
  if [ "${MPATH_READ:-0}" = 1 ]; then
    echo "PATHS_LOST_7D=$(printf '%s' "$MPATH_LOG" | grep -c .)"
    echo "PATHS_LOST_LAST=$(printf '%s\n' "$MPATH_LOG" | tail -1 | cut -c1-200)"
  fi
  sec patch_exposure
  # Can an automatic update reach this host and restart the cluster stack? apt sources, unattended-upgrades state and the needrestart drop-in. Files only, read-only, and no credentials: the host of each URI, and whether the daemons are exempt.
  echo "UU_INSTALLED=$(dpkg-query -W -f='${db:Status-Abbrev}' unattended-upgrades 2>/dev/null | tr -d ' ')"
  echo "NR_INSTALLED=$(dpkg-query -W -f='${db:Status-Abbrev}' needrestart 2>/dev/null | tr -d ' ')"
  echo "UU_TIMER=$(t 5 systemctl is-enabled apt-daily-upgrade.timer 2>/dev/null)"
  # APT::Periodic::Unattended-Upgrade "1" turns it on, "0" off; the last uncommented setting wins
  echo "UU_PERIODIC=$(grep -rhsiE 'APT::Periodic::Unattended-Upgrade[[:space:]]' /etc/apt/apt.conf.d/ 2>/dev/null | grep -vE '^[[:space:]]*(//|#)' | grep -oE '"[0-9]+"' | tr -d '"' | tail -1)"
  # a stock Ubuntu archive beside HPE's own: the public ubuntu.com mirror host, in a classic .list or a deb822 .sources
  echo "APT_UBUNTU=$(grep -rhsiE 'https?://[a-z0-9.-]*ubuntu\.com' /etc/apt/sources.list /etc/apt/sources.list.d/ 2>/dev/null | grep -vE '^[[:space:]]*(//|#)' | grep -c .)"
  echo "APT_SOURCES=$(ls /etc/apt/sources.list.d/ 2>/dev/null | tr '\n' ',')"
  # needrestart made safe: restart mode set to list-only, or a drop-in naming the cluster daemons (HVM's mvm drop-in is this case)
  echo "NR_LISTONLY=$(grep -rhsE "restart\}[[:space:]]*=[[:space:]]*['\"]l['\"]" /etc/needrestart/ 2>/dev/null | grep -c .)"
  echo "NR_EXEMPT=$(grep -rlsiE 'corosync|dlm|morpheus|mvm' /etc/needrestart/conf.d/ /etc/needrestart/needrestart.conf 2>/dev/null | grep -c .)"
  sec kernel_posture
  # What makes a sick node die rather than hang: panic sysctls, kernel taint, the error policy of each GFS2 filesystem, PCIe AER. /proc and /sys only, so this still answers in a freeze. A value that cannot be read prints nothing, taken as not checked, never a pass.
  for k in hung_task_panic softlockup_panic panic panic_on_oops; do
    echo "SYSCTL=$k|$(cat /proc/sys/kernel/$k 2>/dev/null)"
  done
  echo "TAINT=$(cat /proc/sys/kernel/tainted 2>/dev/null)"
  awk '$3=="gfs2"{print "GFS2_OPTS="$2"|"$4}' /proc/mounts 2>/dev/null
  # AER counts summed across devices, skipping each file's TOTAL line so error types are not double counted. No AER files (firmware-first, or none) prints nothing.
  aer_sum() {
    local f any=0 tot=0
    for f in /sys/bus/pci/devices/*/aer_dev_"$1"; do
      [ -r "$f" ] || continue
      any=1
      tot=$((tot + $(awk '$1 !~ /TOTAL/ {s+=$2+0} END{print s+0}' "$f")))
    done
    [ "$any" = 1 ] && echo "$tot"
  }
  echo "AER_FATAL=$(aer_sum fatal)"
  echo "AER_NONFATAL=$(aer_sum nonfatal)"
  echo "AER_CORRECTABLE=$(aer_sum correctable)"
  sec net_timing
  # Bond failover timing, link-flap counters, and whether a 2.0 linux bridge filters VLANs. sysfs and /proc/net/bonding only.
  for b in /sys/class/net/*/bonding; do
    [ -d "$b" ] || continue
    d="$(basename "$(dirname "$b")")"
    echo "BONDOPT=$d|$(awk '{print $1}' "$b/mode" 2>/dev/null)|$(cat "$b/miimon" 2>/dev/null)|$(cat "$b/updelay" 2>/dev/null)|$(cat "$b/downdelay" 2>/dev/null)|$(cat "$b/arp_interval" 2>/dev/null)|$(awk '{print $1}' "$b/lacp_rate" 2>/dev/null)|$(awk '{print $1}' "$b/xmit_hash_policy" 2>/dev/null)"
  done
  # carrier transitions since boot, per physical NIC: a link that flaps but reads up at sample time
  for n in /sys/class/net/*; do
    [ -e "$n/device" ] || continue
    echo "FLAP=$(basename "$n")|$(cat "$n/carrier_changes" 2>/dev/null)|$(cat "$n/carrier_down_count" 2>/dev/null)"
  done
  # linux bridges: VLAN filtering off removes tenant isolation on 2.0; the STP state is shown beside it
  for br in /sys/class/net/*/bridge; do
    [ -d "$br" ] || continue
    d="$(basename "$(dirname "$br")")"
    echo "BRIDGE=$d|$(cat "$br/vlan_filtering" 2>/dev/null)|$(cat "$br/stp_state" 2>/dev/null)"
  done
  sec sec
  # Tenant-isolation and side-channel state for the W-TENANT checks. The /sys, /proc and ip reads never touch GFS2. virsh dumpxml is formatted from
  # libvirt's own memory and asks neither QEMU nor the datastore, so it is safe in a freeze, but libvirt can still hang on a wedged VM, hence the bound.
  echo "KVM_NESTED_INTEL=$(cat /sys/module/kvm_intel/parameters/nested 2>/dev/null)"
  echo "KVM_NESTED_AMD=$(cat /sys/module/kvm_amd/parameters/nested 2>/dev/null)"
  for v in /sys/devices/system/cpu/vulnerabilities/*; do
    [ -r "$v" ] && echo "CPU_VULN=$(basename "$v")|$(san "$(cat "$v" 2>/dev/null)")"
  done
  echo "SMT_CONTROL=$(cat /sys/devices/system/cpu/smt/control 2>/dev/null)"
  echo "SMT_ACTIVE=$(cat /sys/devices/system/cpu/smt/active 2>/dev/null)"
  echo "CPU_CMDLINE=$(san "$(cat /proc/cmdline 2>/dev/null)")"
  # the VLAN a host infrastructure interface is tagged with, if any: an untagged interface prints no line and so is compared with nothing
  infra_vlan() { local id; [ -n "$2" ] || return 0; id="$(ip -o -d link show "$2" 2>/dev/null | sed -n 's/.*vlan protocol [^ ]* id \([0-9][0-9]*\).*/\1/p' | head -1)"; [ -n "$id" ] && echo "INFRA_VLAN=$1|$id"; }
  infra_vlan cluster "$(ip -o -4 addr show 2>/dev/null | awk -v want=" $(echo $RING_ADDRS) " '{split($4,p,"/"); if (index(want, " " p[1] " ")) {print $2; exit}}')"
  t 10 iscsiadm -m session 2>/dev/null | awk '{print $3}' | cut -d, -f1 | sed 's/:[0-9]*$//' | sort -u | while read -r p; do
    [ -n "$p" ] && infra_vlan storage "$(ip -o route get "$p" 2>/dev/null | grep -oE 'dev [^ ]+' | cut -d' ' -f2)"
  done
  # 2.0 Linux bridges: with STP on a guest can send superior BPDUs and take the root role; with it off guest BPDUs reach the uplink, where a switch's BPDU guard can err-disable the trunk
  for b in /sys/class/net/*/bridge; do
    [ -d "$b" ] || continue
    bd="$(basename "$(dirname "$b")")"
    echo "BRIDGE_STP=$bd|$(cat "$b/stp_state" 2>/dev/null)|$(cat "$b/vlan_filtering" 2>/dev/null)"
  done
  # each running VM's effective CPU block, disk cache modes and whether any per-VM I/O, CPU or network limit is set. The name is sanitised; the rest is libvirt's own XML
  vm_sec() {
    local end=$((SECONDS + 20)) d xml nm cpu feats caches lim
    t 10 virsh -q list --name | while read -r d; do
      [ -n "$d" ] || continue
      [ "$SECONDS" -lt "$end" ] || { echo 'SEC_CUT=1'; break; }
      if ! xml="$(t 5 virsh dumpxml "$d")"; then echo 'SEC_CUT=1'; continue; fi
      nm="$(san "$d")"
      cpu="$(printf '%s\n' "$xml" | tr '\n' ' ' | grep -oE '<cpu[^>]*>' | head -1)"
      feats="$(printf '%s\n' "$xml" | grep -oE "policy='[a-z]+'[^/]*name='(vmx|svm)'|name='(vmx|svm)'[^/]*policy='[a-z]+'" | tr '\n' ',')"
      caches="$(printf '%s\n' "$xml" | grep -oE "cache='[a-z]+'" | sort -u | tr '\n' ',')"
      lim=0; printf '%s\n' "$xml" | grep -qE '<iotune>|<blkiotune>|<bandwidth' && lim=1
      echo "VM_SEC=$nm|$(san "$cpu")|$(san "$feats")|$(san "$caches")|$lim"
    done
  }
  bounded 25 vm_sec
  sec fence_agent
  # advisory a00156214: the SCSI fence agent that layout-1.2/Pacemaker clusters created on Manager 8.0.11/8.0.12 carry. Read-only presence test; 1.3 and 2.0 do their own fencing and should not have it.
  echo "FENCE_SCSI=$([ -e /usr/sbin/fence_scsi_hpevm ] && echo 1 || echo 0)"
  sec apd
  # the agent's all-paths-down VM shutdown over 7 days. The one real line is "...heartbeat datastore paths have been unhealthy...Shutting down all VMs to protect data integrity"; HPE also lists "APD threshold" messages to watch.
  # The whole journal, as the agent's own unit name is not known here; a log that could not be read prints no count, so grep alone cannot clear a finding. The exact agent wording is unverified against a live host.
  APD_RE='shutting down all vms to protect|heartbeat datastore paths.*unhealthy|apd threshold'
  APD_LOG="$(t 20 journalctl --since '7 days ago' -o short-iso --no-pager | grep -iE "$APD_RE"; exit "${PIPESTATUS[0]}")" && echo "APD_7D=$(printf '%s' "$APD_LOG" | grep -c .)"
  echo "APD_LAST=$(printf '%s\n' "$APD_LOG" | tail -1 | cut -c1-200)"
fi

sec vm_ports
# each running VM's ports and the VLANs its bridge gives them, from the bridge rather than the VM's definition. Bounded, as libvirt can hang on a VM stuck on its disk.
# Every run reads these, so a quick one has the shorter limit, and in a freeze leaves libvirt alone. PORTS_CUT says something was not read, which holds what was last found, rather than clearing it.
if [ "$MODE" != full ] && [ -n "$GFS2_TARGETS" ] && [ "$FS_SAFE" != 1 ]; then
  echo 'PORTS_CUT=1'
elif VM_NAMES="$(t 15 virsh -q list --name)"; then
  if [ "$MODE" = full ]; then ports_end=$((SECONDS + 25)); else ports_end=$((SECONDS + 12)); fi
  printf '%s\n' "$VM_NAMES" | while read -r d; do
    [ -n "$d" ] || continue
    [ "$SECONDS" -lt "$ports_end" ] || { echo 'PORTS_CUT=1'; break; }
    ifl="$(t 5 virsh domiflist "$d")" || { echo 'PORTS_CUT=1'; continue; }
    sd="$(san "$d")"
    # column 2 is the interface type: direct is macvtap (a Private network), hostdev is SR-IOV. Neither sits on a readable bridge, so NET-12/NET-13 must say "not checked" for it, not pass it.
    printf '%s\n' "$ifl" | awk 'NR>2 && NF>=5 {print $1, $2, $5}' | while read -r dev typ mac; do
      case "$typ" in
        direct|hostdev) echo "PORT_SKIP=$sd|$dev|$mac|$typ"; continue ;;
      esac
      [ "$dev" != "-" ] || continue
      if br="$(t 3 ovs-vsctl port-to-br "$dev")" && [ -n "$br" ]; then
        echo "OVS_PORT=$sd|$dev|$mac|$br|$(t 3 ovs-vsctl get Port "$dev" tag)|$(t 3 ovs-vsctl get Port "$dev" trunks)|$(t 3 ovs-vsctl get Port "$dev" vlan_mode)"
      else
        echo "BR_PORT=$sd|$dev|$mac|$(basename "$(readlink "/sys/class/net/$dev/master" 2>/dev/null)")|$(cat "/sys/class/net/$dev/master/bridge/vlan_filtering" 2>/dev/null)|$(t 3 bridge -j vlan show dev "$dev" | tr -d '\n')"
      fi
    done
  done
else
  echo 'PORTS_CUT=1'
fi

sec busiest
# Each running VM's CPU and disk counters, summed over its disks, with no paths. Asks each qemu, and libvirt may look at a disk file, so only with DLM healthy, and only in a job.
vm_stats() {
  t 5 virsh domstats --cpu-total --block --list-running --nowait | awk -F= -v q="'" '
    function row() { if (d != "") printf "%s|%.0f|%.0f|%.0f|%.0f|%.0f\n", d, c, rr, rb, wr, wb }
    /^Domain:/ { row(); d = $0; sub(/^Domain: */, "", d); gsub(q, "", d); c = rr = rb = wr = wb = 0; next }
    $1 ~ /^ *cpu\.time$/ { c = $2 }
    $1 ~ /\.rd\.reqs$/ { rr += $2 }
    $1 ~ /\.rd\.bytes$/ { rb += $2 }
    $1 ~ /\.wr\.reqs$/ { wr += $2 }
    $1 ~ /\.wr\.bytes$/ { wb += $2 }
    END { row() }'
}
# A quick run pays for this only when a LUN or the kernel says I/O is waiting; 10 is a plugin judgement, as the probe does not get the standards. Two whole samples or none: half of one is not a rate
HOT="$HOT_IO"
if awk -v v="$PSI_IO" 'BEGIN{exit !(v+0 >= 10)}'; then HOT=1; fi
if { [ -z "$GFS2_TARGETS" ] || [ "$FS_SAFE" = 1 ]; } && { [ "$MODE" = full ] || [ "$HOT" = 1 ]; }; then
  busy_a="$(date +%s%N)"
  if BUSY1="$(bounded 7 vm_stats)" && [ -n "$BUSY1" ]; then
    sleep 2
    busy_b="$(date +%s%N)"
    if BUSY2="$(bounded 7 vm_stats)" && [ -n "$BUSY2" ]; then
      echo "BUSY_MS=$(( (busy_b - busy_a) / 1000000 ))"
      printf '%s\n' "$BUSY1" | sed 's/^/BUSY_A=/'
      printf '%s\n' "$BUSY2" | sed 's/^/BUSY_B=/'
    fi
  fi
fi

# What only a full check reads, and can go without: it comes after what every check needs, so running short costs these and says so.
if [ "$MODE" = full ]; then
  sec raid
  # Physical disks behind a MegaRAID controller, one at a time, up to 24. The scan also lists iSCSI LUNs, as -d scsi, and those are never read.
  # A controller that stops answering gets no more readers, and what was left is counted: a disk not read must not look like a disk with nothing wrong.
  # Thin, ports and busiest have had their turn by now, so this waits for them rather than the other way round. It stops early enough to leave HPE's check below its time.
  raid_smart() { smartctl -H -A -i -d "$1" "$2" | grep -viE 'serial|wwn|logical unit id' | sed "s/^/RAID_SMART=$1@${2##*/}|/"; }
  if [ -n "$(command -v smartctl)" ]; then
    raid_n=0; raid_unread=0; raid_stop=0; raid_end=$((SECONDS + 40))
    [ "$raid_end" -le $((BUDGET - 45)) ] || raid_end=$((BUDGET - 45))
    while read -r dev ty; do
      [ -n "$dev" ] || continue
      raid_n=$((raid_n + 1))
      if [ "$raid_n" -gt 24 ] || [ "$raid_stop" = 1 ] || [ "$SECONDS" -ge "$raid_end" ]; then raid_unread=$((raid_unread + 1)); continue; fi
      echo "RAID_DISK=$ty@${dev##*/}"
      bounded 5 raid_smart "$ty" "$dev" || raid_stop=1
    done <<< "$(t 10 smartctl --scan | awk '$2=="-d" && $1 ~ /^\/dev\/bus\/[0-9]+$/ && $3 ~ /^megaraid,[0-9]+$/ {print $1, $3}')"
    echo "RAID_DISKS_UNREAD=$raid_unread"
  fi
  sec hvmcli_health
  # HPE's own readiness check, on hosts that have hvmcli (2.0), printed as one line. Its disk space check may stat GFS2, so it runs only with DLM healthy, asked again now because
  # the answer above can be a minute old, and as a job this stops waiting for. It took 6 s on the one host measured, and pings the gateway and iSCSI portals. Short of time it is left out rather than cut the probe.
  dlm_calm() { local l; l="$(t 10 dlm_tool ls)" && [ -n "$l" ] && ! echo "$l" | grep -qE 'kern_stop|new change|new status|wait fenc'; }
  if [ -z "$(command -v hvmcli)" ]; then
    echo 'HVMCLI_STATE=missing'
  elif [ "$FS_SAFE" != 1 ] || [ -n "$FS_HUNG" ] || ! dlm_calm; then
    echo 'HVMCLI_STATE=unsafe'
  elif [ $((BUDGET - SECONDS)) -le 35 ]; then
    echo 'HVMCLI_STATE=no_time'
  elif HVM_HEALTH="$(bounded 30 hvmcli health check --json)"; then
    echo 'HVMCLI_STATE=read'
    echo "HVMCLI_JSON=$(printf '%s\n' "$HVM_HEALTH" | sed 's/^ *//' | tr -d '\r\n' | head -c 40000)"
  else
    echo 'HVMCLI_STATE=timeout'
  fi
fi

sec end
# what was reached before the limit is good, and what was skipped must not read as nothing wrong
[ "$SECONDS" -lt "$BUDGET" ] || echo 'CUT=1'
echo "COMPLETE=1"
