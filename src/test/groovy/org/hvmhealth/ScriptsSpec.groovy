package org.hvmhealth

import org.hvmhealth.fixes.Fixes
import org.hvmhealth.fixes.Preflight
import org.hvmhealth.fixes.VlanTest
import org.hvmhealth.model.Bmc
import org.hvmhealth.model.HostFacts
import org.hvmhealth.morpheus.HealthContext
import spock.lang.Requires
import spock.lang.Specification

import java.nio.file.Files
import java.util.concurrent.TimeUnit

/**
 * Runs the scripts themselves: a syntax check of every one, and the unmount, the VLAN test and the probe against stub commands in a temporary
 * directory. Nothing outside that directory is touched, as every path the scripts would change is pointed into it first. Skipped where there is no bash.
 */
@Requires({ ScriptsSpec.bash() })
class ScriptsSpec extends Specification {
    static boolean bash() {
        try {
            new ProcessBuilder('bash', '-c', 'exit 0').redirectErrorStream(true).start().waitFor() == 0
        } catch (IOException ignored) {
            false
        }
    }

    File dir
    File stubs

    def setup() {
        dir = Files.createTempDirectory('hh-scripts').toFile()
        stubs = new File(dir, 'stubs')
        stubs.mkdirs()
    }

    def cleanup() {
        dir?.deleteDir()
    }

    private void stub(String name, String body) {
        File f = new File(stubs, name)
        f.text = "#!/bin/bash\necho \"${name} \$*\" >> ${dir}/calls\n${body}\n"
        f.setExecutable(true)
    }

    private List<String> calls() {
        File f = new File(dir, 'calls')
        f.exists() ? f.readLines() : []
    }

    /** [exit, output, seconds]. A stub left running writes nothing here, so reading to the end cannot wait for it. */
    private Map run(String script, long limitSec = 120) {
        File f = new File(dir, 'script.sh')
        f.text = script
        File out = new File(dir, 'out')
        long began = System.currentTimeMillis()
        Process p = new ProcessBuilder('bash', f.path).redirectErrorStream(true).redirectOutput(out).redirectInput(new File('/dev/null')).start()
        assert p.waitFor(limitSec, TimeUnit.SECONDS)
        [exit: p.exitValue(), output: out.text, seconds: (System.currentTimeMillis() - began) / 1000L]
    }

    private static List<String> everyScript() {
        HostFacts chrony = Fixture.facts('probe-healthy', 'x') { it.replace('dlm|active|enabled', 'dlm|active|disabled') }
        HostFacts timesyncd = Fixture.facts('probe-timesyncd', 'x')
        String launch = VlanTest.launchScript([[vlan: 300, gateway: '192.0.2.1']])
        [HealthContext.probeScript('quick'), HealthContext.probeScript('full'),
         Fixes.fix('ntp-sources', chrony, ['192.0.2.123']).script, Fixes.fix('ntp-sources', timesyncd, ['192.0.2.123']).script,
         Fixes.fix('ntp-enable', chrony).script, Fixes.fix('service-enable', chrony).script, Fixes.fix('rejoin', chrony).script, Fixes.fix('multipath-reconfigure', chrony).script,
         Fixes.fix('ipmitool-install', chrony).script, Bmc.READ_SCRIPT, Bmc.RESET_SCRIPT,
         Fixes.fenceAckScript(1), Preflight.unmountScript(), launch, vlanBody(), VlanTest.READ_SCRIPT]
    }

    private static String vlanBody() {
        new String(VlanTest.launchScript([[vlan: 300, gateway: '192.0.2.1'], [vlan: 400, gateway: '192.0.2.2']]).find(/echo '([A-Za-z0-9+\/=]+)' \| base64 -d > /) { all, g -> g }.decodeBase64(), 'UTF-8')
    }

    def 'every script parses'() {
        expect:
        everyScript().every { String s ->
            Map r = run('bash -n <<\'HH_END\'\n' + s + '\nHH_END\n')
            assert r.exit == 0: r.output
            true
        }
    }

    // ------------------------------------------------------------------ the unmount

    /** The unmount with its paths in the temporary directory, one GFS2 mount, and a pool on it. */
    private String unmount() {
        new File(dir, 'mounts').text = '/dev/mapper/3624a0000000000000000 /mnt/ds1 gfs2 rw 0 0\n'
        stub('virsh', '''case "$*" in
  *pool-list*) echo ' pool1 active yes' ;;
  *pool-dumpxml*) echo '<path>/mnt/ds1</path>' ;;
esac''')
        stub('dlm_tool', 'exit 0')
        Preflight.unmountScript().replace('PATH=/usr/sbin', "PATH=${stubs}:/usr/sbin").replace('/proc/mounts', "${dir}/mounts").replace('seq 1 60', 'seq 1 2')
    }

    def 'a mount in use stops before anything changes'() {
        given:
        String s = unmount()
        stub('fuser', 'exit 0')
        stub('umount', 'exit 0')

        when:
        Map r = run(s)

        then:
        r.exit == 3
        r.output.contains('REFUSED: still in use')
        !calls().any { it.startsWith('umount') || it.contains('pool-destroy') }
    }

    def 'a clean unmount stops the pool, unmounts and leaves every lockspace'() {
        given:
        String s = unmount()
        stub('fuser', 'exit 1')
        stub('umount', 'exit 0')

        when:
        Map r = run(s)

        then:
        r.exit == 0
        r.output.contains('out of every lockspace')
        calls().findIndexOf { it.contains('pool-destroy pool1') } < calls().findIndexOf { it.startsWith('umount') }
        !calls().any { it.contains('pool-start') }
    }

    def 'an unmount refused starts the pool again, as GFS2 stays mounted'() {
        given:
        String s = unmount()
        stub('fuser', 'exit 1')
        stub('umount', 'exit 32')

        when:
        Map r = run(s)

        then:
        r.exit == 4
        calls().any { it.contains('pool-start pool1') }
    }

    def 'a hung unmount stops at its limit, and leaves the pool alone'() {
        given:
        String s = unmount()
        stub('fuser', 'exit 1')
        stub('umount', 'exec >/dev/null 2>&1; sleep 8')

        when:
        Map r = run(s)

        then:
        r.exit == 6
        r.output.contains('HUNG: umount')
        !calls().any { it.contains('pool-start') }
    }

    // ------------------------------------------------------------------ the fence acknowledgement

    private String fenceAck() {
        Fixes.fenceAckScript(1).replace('PATH=/usr/sbin', "PATH=${stubs}:/usr/sbin").replace('seq 1 30', 'seq 1 2')
    }

    def 'recovered is only said when dlm_tool answered and shows nothing stopped'() {
        given:
        String s = fenceAck()
        stub('sleep', 'exit 0')
        stub('dlm_tool', '''[ "$1" = fence_ack ] && exit 0
''' + body)

        when:
        Map r = run(s)

        then:
        r.exit == exit
        r.output.contains('lockspaces recovered') == (exit == 0)

        where:
        body                                       | exit
        'echo "name x"; echo "flags 0x00000000"'   | 0
        'exit 1'                                   | 1
        'echo "flags 0x00000004 kern_stop"'        | 1
    }

    // ------------------------------------------------------------------ installing ipmitool

    /** The install with apt answering {@code plan} to its simulation. Absent unless {@code have}: whatever this machine has is not looked for. */
    private String install(String plan, boolean have = false) {
        new File(dir, 'plan').text = plan
        stub('apt-get', "case \" \$* \" in *' -s '*) cat ${dir}/plan ;; esac\nexit 0")
        stub('dpkg-query', "printf '1.0'")
        stub('modprobe', 'exit 0')
        String s = Fixes.fix('ipmitool-install', new HostFacts()).script.replace('PATH=/usr/sbin', "PATH=${stubs}:/usr/sbin").replace('/etc/modules-load.d/', "${dir}/")
        if (have) {
            stub('ipmitool', 'exit 1')
            return s
        }
        s.replace('command -v ipmitool >/dev/null', 'command -v hh-no-such-tool >/dev/null')
    }

    private List<String> realInstalls() {
        calls().findAll { it.startsWith('apt-get') && it.contains(' install ') && !it.contains(' -s ') }
    }

    def 'an install that would upgrade another package is refused before anything is installed'() {
        when:
        Map r = run(install('Inst libc6 [2.39-0ubuntu8] (2.39-0ubuntu8.3 Ubuntu:24.04/noble-updates [amd64])\nInst ipmitool (1.8.19 Ubuntu:24.04/noble-updates [amd64])\n'))

        then:
        r.exit == 1
        r.output.contains('would also upgrade or remove other packages')
        r.output.contains('Inst libc6 [2.39-0ubuntu8]')
        realInstalls() == []
    }

    def 'an install of new packages only goes ahead, and names them'() {
        when:
        Map r = run(install('Inst freeipmi-common (1.6.13 Ubuntu:24.04/noble-updates [all])\nInst ipmitool (1.8.19 Ubuntu:24.04/noble-updates [amd64])\n'))

        then:
        r.exit == 0
        r.output.contains('installing freeipmi-common ipmitool')
        realInstalls().size() == 1
        !calls().any { it.startsWith('apt-get') && it.contains(' update') }
    }

    def 'lists that do not offer it are refreshed once, and then it is refused'() {
        when:
        Map r = run(install(''))

        then:
        r.exit == 1
        r.output.contains('no repository this host uses offers ipmitool')
        calls().count { it.startsWith('apt-get') && it.contains(' update') } == 1
        realInstalls() == []
    }

    def 'with ipmitool already there apt is never run'() {
        when:
        Map r = run(install('Inst ipmitool (1.8.19 x [amd64])\n', true))

        then:
        r.exit == 0
        r.output.contains('ipmitool is already installed: 1.0')
        !calls().any { it.startsWith('apt-get') }
    }

    // ------------------------------------------------------------------ the VLAN test

    /** One active-backup bond on a linux bridge, in files, with stubs for what would touch the network. */
    private String vlanTest() {
        File bond = new File(dir, 'sys/bond0/bonding')
        bond.mkdirs()
        new File(bond, 'mode').text = 'active-backup 1\n'
        new File(bond, 'active_slave').text = 'eno1\n'
        new File(bond, 'slaves').text = 'eno1 eno2\n'
        stub('arping', 'echo "Received 3 response(s)"')
        stub('ovs-vsctl', 'exit 1')
        stub('bridge', 'exit 0')
        stub('ip', '''case "$*" in
  "-o link show bond0") echo '5: bond0: <UP> mtu 1500 master br0 state UP' ;;
  "-o link show type bridge") echo '6: br0: <UP> mtu 1500' ;;
esac
exit 0''')
        vlanBody().replace('PATH=/usr/local/sbin', "PATH=${stubs}:/usr/local/sbin").replace('/sys/class/net', "${dir}/sys").replace(VlanTest.LOCK, "${dir}/lock")
    }

    def 'it tests each uplink, says what it found the uplink on, and puts it back'() {
        given:
        String s = vlanTest()

        when:
        Map r = run(s)
        Map parsed = VlanTest.parse(r.output as String)

        then:
        parsed.done
        parsed.original == 'eno1'
        parsed.restored == 'eno1'
        parsed.results*.uplink == ['eno1', 'eno1', 'eno2', 'eno2']
        new File(dir, 'sys/bond0/bonding/active_slave').text.trim() == 'eno1'
    }

    def 'a second test is refused while one holds the lock, without moving anything'() {
        given:
        String s = vlanTest()
        Process holder = new ProcessBuilder('flock', '-n', "${dir}/lock".toString(), 'sleep', '10').redirectErrorStream(true).redirectOutput(new File(dir, 'holder')).start()
        Thread.sleep(500)

        when:
        Map r = run(s)

        then:
        r.output.contains('ERROR=a VLAN test is still running')
        r.output.contains('END')
        new File(dir, 'sys/bond0/bonding/active_slave').text.trim() == 'eno1'

        cleanup:
        holder?.destroyForcibly()
    }

    def 'the lock is free again soon after a run ends, so the next run can start'() {
        given:
        String s = vlanTest()

        when:
        run(s)
        Thread.sleep(2500)

        then:
        new ProcessBuilder('flock', '-n', "${dir}/lock".toString(), 'true').start().waitFor() == 0
    }

    // ------------------------------------------------------------------ the probe

    /** The probe with the mount table and its temporary file in the temporary directory, and a lockspace per the stub. */
    private String probe(int budget) {
        new File(dir, 'mounts').text = '/dev/mapper/3624a0000000000000000 /mnt/ds1 gfs2 rw 0 0\n'
        ("set -- quick ${budget}\n" + HealthContext.getResourceAsStream('/scripts/health-probe.sh').getText('UTF-8'))
                .replace('PATH=/usr/local/sbin', "PATH=${stubs}:/usr/local/sbin").replace('/proc/mounts', "${dir}/mounts").replace('mktemp /run/', "mktemp ${dir}/")
    }

    def 'with GFS2 mounted, an empty lockspace list is not safe, and one per mount is'() {
        given:
        String s = probe(60)
        stub('dlm_tool', listing)
        // anything that reads the mount would be seen here
        stub('df', 'exit 0')
        stub('fuser', 'exit 0')

        when:
        HostFacts f = HostFacts.parse(run(s).output as String)

        then:
        f.complete
        f.fsSafe == safe
        calls().any { it.startsWith('df ') } == safe

        where:
        listing                                                  | safe
        '[ "$1" = ls ] || exit 0'                                | false
        'exit 1'                                                 | false
        '[ "$1" = ls ] && echo "name          ds1"; exit 0'      | true
        '[ "$1" = ls ] && { echo "name ds1"; echo "flags 0x00000004 kern_stop"; }; exit 0' | false
    }

    def 'out of time, everything slow is skipped: DLM still prints, the end says it was cut, and it returns near its limit'() {
        given:
        String s = probe(4)
        stub('dlm_tool', '[ "$1" = ls ] && { echo "name ds1"; echo "flags 0x00000004 kern_stop"; }; [ "$1" = status ] && echo "fence 1 procdown pid 9 actor 2 fail 100 fence 0 now 200"; exit 0')
        stub('corosync-quorumtool', 'echo "Quorate:          Yes"')
        // each would take far longer than the limit
        ['journalctl', 'virsh', 'multipath', 'multipathd', 'iscsiadm', 'chronyc', 'timedatectl', 'lldpcli'].each { stub(it, 'exec sleep 60') }

        when:
        Map r = run(s, 40)
        HostFacts f = HostFacts.parse(r.output as String)

        then:
        r.seconds < 20
        f.partial
        !f.complete
        f.frozen
        f.quorate
        f.fenceWaits*.node == [1]
    }

    def 'a process table that will not answer does not hold the probe: its reader is left behind'() {
        given:
        String s = probe(60)
        stub('dlm_tool', 'exit 0')
        stub('ps', 'case "$*" in *args=*) exec >/dev/null 2>&1; exec sleep 30 ;; esac; exit 0')

        when:
        Map r = run(s, 70)

        then:
        r.seconds < 40
        HostFacts.parse(r.output as String).complete
    }

    // ------------------------------------------------------------------ 0.4.0: host load, isolation on quick checks, busiest VMs

    def 'with GFS2 stopped, the new reads still answer and never touch the mount, and a quick run says it did not read the ports'() {
        given:
        String s = probe(60)
        stub('dlm_tool', '[ "$1" = ls ] && { echo "name ds1"; echo "flags 0x00000004 kern_stop"; }; exit 0')
        stub('df', 'printf "Filesystem Mounted Use%% IUse%%\\n/dev/sda2 / 42%% 10%%\\n/dev/sda2 / 42%% 10%%\\n/dev/sda1 /boot 30%% 2%%\\n"')
        stub('virsh', 'exit 0')

        when:
        HostFacts f = HostFacts.parse(run(s).output as String)

        then:
        f.complete
        !f.fsSafe
        f.portsCut
        f.vmPorts == []
        f.vmActivity == []
        f.rootFs*.target == ['/', '/boot']
        f.rootFs*.pct == [42, 30]
        calls().findAll { it.startsWith('df ') } == ['df --output=source,target,pcent,ipcent / /var /var/log /tmp /boot']
        !calls().any { it.contains('domiflist') || it.contains('domstats') }
    }

    def 'the agent quorum endpoint and the quorum-node file are read, on the host and never a GFS2 path'() {
        given:
        String s = probe(60).replace('/opt/morpheus-node/.quorum-nodes', "${dir}/qnodes")
        new File(dir, 'qnodes').text = '["hvm-node01","hvm-node02"]'
        stub('dlm_tool', 'exit 0')
        stub('curl', 'echo \'{"quorumAchieved":true,"fenced":false,"waitFencing":false,"totalNodes":2,"reachableNodes":2,"corosyncActive":true,"dlmActive":true,"nodes":[{"name":"hvm-node01"},{"name":"hvm-node02"}]}\'')

        when:
        HostFacts f = HostFacts.parse(run(s).output as String)

        then:
        f.complete
        f.agentQuorum.achieved == true
        f.agentQuorum.total == 2
        f.agentQuorumNodes.containsAll(['hvm-node01', 'hvm-node02'])
        calls().any { it.startsWith('curl ') && it.contains('https://127.0.0.1:7443/quorum') }
    }

    def 'with the agent endpoint unreachable and no quorum-node file the section still prints, with nothing to read'() {
        given:
        String s = probe(60).replace('/opt/morpheus-node/.quorum-nodes', "${dir}/nope")
        stub('dlm_tool', 'exit 0')
        stub('curl', 'exit 7')

        when:
        HostFacts f = HostFacts.parse(run(s).output as String)

        then:
        f.complete
        f.sections.containsKey('agent_quorum')
        f.agentQuorum == null
        f.agentQuorumNodes == []
    }

    def 'on a quick run with DLM healthy the ports are read, one VM at a time, and libvirt failing is cut rather than no ports'() {
        given:
        String s = probe(60)
        stub('dlm_tool', '[ "$1" = ls ] && echo "name ds1"; exit 0')
        stub('ovs-vsctl', 'exit 1')
        stub('bridge', 'echo "[]"')
        stub('virsh', body)

        when:
        HostFacts f = HostFacts.parse(run(s).output as String)

        then:
        f.complete
        f.portsCut == cut
        f.vmPorts*.vm == vms

        where:
        body                                                                                                                         | cut   | vms
        'case "$*" in *"list --name"*) echo vm-one ;; *domiflist*) printf "Interface Type\\n---\\nvnet0 bridge br0 virtio 02:00:00:00:00:01\\n" ;; esac' | false | ['vm-one']
        'case "$*" in *"list --name"*) echo ;; esac'                                                                                 | false | []
        'exit 1'                                                                                                                     | true  | []
        'case "$*" in *"list --name"*) echo vm-one ;; *) exit 1 ;; esac'                                                             | true  | []
    }

    def 'a VM that hangs libvirt cannot hold a quick run past the limit on ports'() {
        given:
        String s = probe(60)
        stub('dlm_tool', '[ "$1" = ls ] && echo "name ds1"; exit 0')
        stub('virsh', 'case "$*" in *"list --name"*) for i in 1 2 3 4 5 6 7 8 9 10; do echo vm-$i; done ;; *domiflist*) exec sleep 60 ;; esac')

        when:
        Map r = run(s, 70)
        HostFacts f = HostFacts.parse(r.output as String)

        then:
        r.seconds < 45
        f.complete
        f.portsCut
    }

    def 'pressure, swap and steal come through from the kernel\'s own files as they are printed'() {
        given:
        String s = probe(60).replace('/proc/pressure/', "${dir}/pressure/").replace('/proc/vmstat', "${dir}/vmstat").replace('/proc/stat', "${dir}/stat")
        new File(dir, 'pressure').mkdirs()
        new File(dir, 'pressure/cpu').text = 'some avg10=1.00 avg60=12.34 avg300=0.50 total=100\nfull avg10=0.00 avg60=99.00 avg300=0.00 total=0\n'
        new File(dir, 'pressure/memory').text = 'some avg10=0.00 avg60=0.00 avg300=0.00 total=0\nfull avg10=0.00 avg60=0.00 avg300=0.00 total=0\n'
        new File(dir, 'vmstat').text = 'pgfault 1\npswpin 100\npswpout 200\n'
        new File(dir, 'stat').text = 'cpu  100 0 100 800 0 0 0 50 0 0\ncpu0 1 1 1 1 1 1 1 1 1 1\nbtime 1\n'
        stub('dlm_tool', 'exit 0')

        when:
        HostFacts f = HostFacts.parse(run(s).output as String)

        then:
        f.psiCpu == 12.34
        f.psiMemory == 0
        f.swapPerSec == 0
        f.stealPct == null
    }

    def 'no pressure files prints nothing to read, which the parser takes as not read'() {
        given:
        String s = probe(60).replace('/proc/pressure/', "${dir}/none/").replace('/proc/vmstat', "${dir}/none/vmstat").replace('/proc/stat ', "${dir}/none/stat ")
        stub('dlm_tool', 'exit 0')

        when:
        HostFacts f = HostFacts.parse(run(s).output as String)

        then:
        f.sections.containsKey('host_health')
        f.psiCpu == null
        f.psiMemory == null
        f.swapPerSec == null
        f.stealPct == null
    }

    def 'OOM kills: a log that cannot be read says so, and the victim and its cgroup are read from the kernel\'s own lines'() {
        given:
        String s = probe(60)
        stub('dlm_tool', 'exit 0')
        stub('journalctl', body)

        when:
        HostFacts f = HostFacts.parse(run(s).output as String)

        then:
        f.oomRead == read
        f.oomKills*.comm == comms
        f.oomKills*.vm == vms

        where:
        body | read | comms | vms
        'exit 1' | false | [] | []
        'exit 0' | true | [] | []
        '''echo '2026-10-31T00:00:00+0000 h kernel: oom-kill:constraint=CONSTRAINT_NONE,nodemask=(null),cpuset=/,mems_allowed=0,global_oom,task_memcg=/machine.slice/machine-qemu\\x2d5\\x2dvm\\x2done.scope/libvirt/emulator,task=qemu-system-x86,pid=9,uid=64055'
echo '2026-10-31T00:00:00+0000 h kernel: Out of memory: Killed process 9 (qemu-system-x86) total-vm:1kB'
echo '2026-10-31T00:00:01+0000 h kernel: Out of memory: Killed process 7 (java) total-vm:1kB\'''' | true | ['qemu-system-x86', 'java'] | ['vm-one', null]
    }

    def 'VM crash lines come from the end of recent logs, and an absent directory is not read'() {
        given:
        File logs = new File(dir, 'qemu')
        if (make) {
            logs.mkdirs()
            new File(logs, 'vm-one.log').text = '2026-10-31 00:00:00.123+0000: starting up\n2026-10-31 01:00:00.123+0000: shutting down, reason=crashed\n'
            new File(logs, 'vm-two.log').text = '2026-10-31 00:00:00.123+0000: shutting down, reason=destroyed\n'
        }
        String s = probe(60).replace('/var/log/libvirt/qemu', logs.path)
        stub('dlm_tool', 'exit 0')

        when:
        HostFacts f = HostFacts.parse(run(s).output as String)

        then:
        f.vmLogsRead == make
        f.vmCrashes*.vm == (make ? ['vm-one'] : [])

        where:
        make << [true, false]
    }

    def 'busiest VMs: full always, with counters summed over disks and no paths, DLM permitting'() {
        given:
        new File(dir, 'mounts').text = '/dev/mapper/3624a0000000000000000 /mnt/ds1 gfs2 rw 0 0\n'
        String s = ("set -- full 60\n" + HealthContext.getResourceAsStream('/scripts/health-probe.sh').getText('UTF-8'))
                .replace('PATH=/usr/local/sbin', "PATH=${stubs}:/usr/local/sbin").replace('/proc/mounts', "${dir}/mounts").replace('mktemp /run/', "mktemp ${dir}/")
        stub('dlm_tool', listing)
        stub('virsh', '''case "$*" in
  *domstats*) echo "Domain: 'vm-one'"; echo "  cpu.time=$(date +%s%N)"; echo "  block.count=2"; echo "  block.0.path=/mnt/ds1/secret-path.qcow2"; echo "  block.0.rd.reqs=$(($(date +%s) * 2))"; echo "  block.0.rd.bytes=10"; echo "  block.0.wr.reqs=5"; echo "  block.0.wr.bytes=20"; echo "  block.1.rd.reqs=1"; echo "  block.1.rd.bytes=10"; echo "  block.1.wr.reqs=5"; echo "  block.1.wr.bytes=20" ;;
esac''')

        when:
        Map r = run(s)
        HostFacts f = HostFacts.parse(r.output as String)

        then:
        f.complete
        f.vmActivity*.vm == vms
        !(r.output as String).contains('secret-path')
        calls().any { it.contains('domstats') } == !vms.isEmpty()

        where:
        listing                                                                            | vms
        '[ "$1" = ls ] && echo "name ds1"; exit 0'                                         | ['vm-one']
        '[ "$1" = ls ] && { echo "name ds1"; echo "flags 0x00000004 kern_stop"; }; exit 0' | []
    }

    def 'a hung qemu: a domstats that never returns leaves no half sample and does not hold the probe'() {
        given:
        new File(dir, 'mounts').text = '/dev/mapper/3624a0000000000000000 /mnt/ds1 gfs2 rw 0 0\n'
        String s = ("set -- full 60\n" + HealthContext.getResourceAsStream('/scripts/health-probe.sh').getText('UTF-8'))
                .replace('PATH=/usr/local/sbin', "PATH=${stubs}:/usr/local/sbin").replace('/proc/mounts', "${dir}/mounts").replace('mktemp /run/', "mktemp ${dir}/")
        stub('dlm_tool', '[ "$1" = ls ] && echo "name ds1"; exit 0')
        stub('virsh', 'case "$*" in *domstats*) exec >/dev/null 2>&1; exec sleep 60 ;; esac')

        when:
        Map r = run(s, 70)
        HostFacts f = HostFacts.parse(r.output as String)

        then:
        r.seconds < 50
        f.complete
        f.vmActivity == []
        !(r.output as String).contains('BUSY_A')
    }

    def 'a quick run pays for the busiest read only when the kernel says I/O is waiting, and never with DLM stopped'() {
        given:
        new File(dir, 'pressure').mkdirs()
        new File(dir, 'pressure/io').text = "some avg10=0.00 avg60=${avg60} avg300=0.00 total=0\nfull avg10=0.00 avg60=0.00 avg300=0.00 total=0\n".toString()
        String s = probe(60).replace('/proc/pressure/', "${dir}/pressure/")
        stub('dlm_tool', listing)
        stub('virsh', 'exit 0')

        when:
        run(s)

        then:
        calls().any { it.contains('domstats') } == read

        where:
        avg60   | listing                                                                            | read
        '0.00'  | '[ "$1" = ls ] && echo "name ds1"; exit 0'                                         | false
        '9.99'  | '[ "$1" = ls ] && echo "name ds1"; exit 0'                                         | false
        '10.00' | '[ "$1" = ls ] && echo "name ds1"; exit 0'                                         | true
        '10.00' | '[ "$1" = ls ] && { echo "name ds1"; echo "flags 0x00000004 kern_stop"; }; exit 0' | false
    }

    def 'a kernel log that cannot be read prints no count, and an empty one prints 0'() {
        given:
        String s = probe(60)
        stub('dlm_tool', 'exit 0')
        stub('journalctl', body)

        when:
        HostFacts f = HostFacts.parse(run(s).output as String)

        then:
        f.gfsErrors == count

        where:
        body     | count
        'exit 1' | null
        'exit 0' | 0
    }

    @spock.lang.Unroll
    def 'the gfs2 log read counts withdrawals and -22 conversions separately from one read, and lock_module/block feeds FS_SAFE (block=#block)'() {
        given:
        new File(dir, 'gfs2/cluster-b:ds_01/lock_module').mkdirs()
        new File(dir, 'gfs2/cluster-b:ds_01/withdraw').text = '0\n'
        new File(dir, 'gfs2/cluster-b:ds_01/freeze').text = '0\n'
        new File(dir, 'gfs2/cluster-b:ds_01/lock_module/block').text = "${block}\n"
        String s = probe(60).replace('/sys/fs/gfs2', "${dir}/gfs2")
        stub('dlm_tool', '[ "$1" = ls ] && echo "name cluster-b:ds_01"; exit 0')
        stub('journalctl', '''case "$*" in
  *"7 days ago"*) printf '%s\\n' '2026-10-31T00:00:00+00:00 h kernel: gfs2: fsid=c:d.0: about to withdraw this file system' '2026-10-31T00:01:00+00:00 h kernel: gfs2: fsid=c:d.0: fatal: I/O error' '2026-10-31T00:02:00+00:00 h kernel: dlm: cluster-b:ds_01: validate_lock_args -22' ;;
esac
exit 0''')

        when:
        HostFacts f = HostFacts.parse(run(s).output as String)

        then:
        f.gfsErrors == 2
        f.dlmConvErrors == 1
        f.dlmConvErrorLast.contains('validate_lock_args -22')
        f.gfsBlock['cluster-b:ds_01'] == block
        f.fsSafe == safe

        where:
        block | safe
        0     | true
        1     | false
    }

    // ------------------------------------------------------------------ 0.4.0: vendor-neutral storage sections

    def 'the new storage sections are printed and parse, with no Fibre Channel reading as none'() {
        given:
        String s = probe(60)
        stub('dlm_tool', 'exit 0')

        when:
        HostFacts f = HostFacts.parse(run(s).output as String)

        then:
        f.complete
        f.sections.containsKey('fc')
        f.sections.containsKey('mpath_paths')
        f.sections.containsKey('iscsi_timers')
        !f.fcPresent
        f.fcHosts == []
        f.mpathPaths == []
    }

    def 'a full probe reads the hard space-allocation log and the all-paths-lost log, a readable but empty log counting 0'() {
        given:
        String s = fullProbe(120)
        stub('dlm_tool', DLM_OK)
        stub('journalctl', 'exit 0')

        when:
        HostFacts f = HostFacts.parse(run(s, 140).output as String)

        then:
        f.sections.containsKey('mpath_log')
        f.thinHard7d == 0
        f.pathsLost7d == 0
    }

    def 'the fence agent and the all-paths-down search are their own full-probe sections: presence, and a 7-day count'() {
        given:
        String s = fullProbe(120)
        stub('dlm_tool', DLM_OK)
        stub('journalctl', journal)

        when:
        HostFacts f = HostFacts.parse(run(s, 140).output as String)

        then:
        f.sections.containsKey('fence_agent')
        f.sections.containsKey('apd')
        f.fenceScsiAgent == false
        f.apdSeen == seen

        where:
        journal                                                                                           | seen
        'exit 1'                                                                                          | null
        'exit 0'                                                                                          | 0
        'printf "2026-11-01T02:00:00+0000 h morphd: Shutting down all VMs to protect data integrity\\n"'  | 1
    }

    // ------------------------------------------------------------------ HPE's own check and disks behind a RAID controller

    private String fullProbe(int budget) {
        probe(budget).replaceFirst('set -- quick', 'set -- full')
    }

    private static final String DLM_OK = '[ "$1" = ls ] && echo "name          ds1"; exit 0'
    private static final String DLM_STOPPED = '[ "$1" = ls ] && { echo "name ds1"; echo "flags 0x00000004 kern_stop"; }; exit 0'
    private static final String HVMCLI_OK = '''cat <<'EOF'
{
  "errorCode" : 0,
  "messages" : "",
  "data" : {
    "checks" : [ {
      "check" : "Cluster status",
      "type" : "Cluster",
      "status" : "WARN",
      "details" : "Cluster health requires attention."
    }, {
      "check" : "Kernel errors",
      "type" : "OS",
      "status" : "WARN",
      "details" : "Found 2 critical error(s) in kernel ring buffer."
    } ]
  }
}
EOF'''

    def 'with DLM healthy a full probe runs HPE\'s check and prints it as one line of JSON that parses'() {
        given:
        String s = fullProbe(120)
        stub('dlm_tool', DLM_OK)
        stub('hvmcli', HVMCLI_OK)

        when:
        Map r = run(s, 140)
        HostFacts f = HostFacts.parse(r.output as String)

        then:
        calls().any { it == 'hvmcli health check --json' }
        f.hvmcliState == 'read'
        f.hvmcliChecks*.check == ['Cluster status', 'Kernel errors']
        r.output.readLines().count { it.startsWith('HVMCLI_JSON=') } == 1
    }

    def 'with DLM stopped, or the mount read hanging, HPE\'s check is never started'() {
        given:
        String s = fullProbe(120)
        stub('dlm_tool', dlm)
        stub('hvmcli', HVMCLI_OK)
        if (fuser) { stub('fuser', fuser) }

        when:
        HostFacts f = HostFacts.parse(run(s, 140).output as String)

        then:
        f.hvmcliState == 'unsafe'
        !calls().any { it.startsWith('hvmcli ') }

        where:
        dlm         | fuser
        DLM_STOPPED | null
        'exit 1'    | null
        DLM_OK      | 'exec >/dev/null 2>&1; exec sleep 20'
    }

    def 'a quick probe never runs HPE\'s check or reads disks through a controller'() {
        given:
        String s = probe(60)
        stub('dlm_tool', DLM_OK)
        stub('hvmcli', HVMCLI_OK)
        stub('smartctl', 'exit 0')

        when:
        run(s)

        then:
        !calls().any { it.startsWith('hvmcli ') || it.startsWith('smartctl ') }
    }

    def 'no hvmcli reads as missing, and one that hangs is left behind and reads as timed out'() {
        given:
        String s = fullProbe(120)
        stub('dlm_tool', DLM_OK)
        if (body) { stub('hvmcli', body) }

        when:
        Map r = run(s, 140)

        then:
        HostFacts.parse(r.output as String).hvmcliState == state
        r.seconds < 100

        where:
        body                                  | state
        null                                  | 'missing'
        'exec >/dev/null 2>&1; exec sleep 40' | 'timeout'
    }

    /** A scan as on a real host: LUNs and local disks as scsi, and {@code disks} behind the controller. */
    private void smartctl(int disks) {
        String scan = (['/dev/sda -d scsi # /dev/sda, SCSI device', '/dev/sdd -d scsi # /dev/sdd, SCSI device'] +
                (0..<disks).collect { "/dev/bus/6 -d megaraid,${it} # /dev/bus/6 [megaraid_disk_${it}], SCSI device".toString() }).join('\n')
        stub('smartctl', """case "\$*" in
  --scan) cat <<'EOF'
${scan}
EOF
  ;;
  *megaraid*) cat <<'EOF'
Device Model:     EXAMPLE SSD 240
Serial Number:    EXAMPLESERIAL
LU WWN Device Id: 5 000000 000000000
SMART Status not supported: ATA return descriptor not supported by controller firmware
SMART overall-health self-assessment test result: PASSED
  5 Reallocated_Sector_Ct   0x0033   100   100   010    Pre-fail  Always       -       0
EOF
  ;;
esac""")
    }

    def 'only the scan\'s megaraid entries are read, each as its own disk, with serials left on the host'() {
        given:
        String s = fullProbe(120)
        stub('dlm_tool', DLM_OK)
        smartctl(2)

        when:
        Map r = run(s, 140)
        HostFacts f = HostFacts.parse(r.output as String)

        then:
        calls().count { it.startsWith('smartctl ') && it.contains('megaraid') } == 2
        calls().any { it == 'smartctl -H -A -i -d megaraid,1 /dev/bus/6' }
        calls().findAll { it.startsWith('smartctl ') && it.contains(' -d ') }.every { it.contains('megaraid') }
        f.raidDisks*.slot == ['megaraid,0', 'megaraid,1']
        f.raidDisks*.bus == ['6', '6']
        f.raidDisks.every { it.health == 'PASSED' }
        f.raidDisksUnread == 0
        !r.output.contains('EXAMPLESERIAL')
        !r.output.contains('5 000000')
    }

    def 'a full probe prints what every check needs before what it can go without'() {
        given:
        String s = fullProbe(120)
        stub('dlm_tool', DLM_OK)
        stub('hvmcli', HVMCLI_OK)
        smartctl(1)

        when:
        List<String> printed = run(s, 140).output.readLines().findAll { it.startsWith('@@') }

        then:
        ['@@smart', '@@thin', '@@vm_ports', '@@busiest', '@@raid', '@@hvmcli_health', '@@end'].collect { printed.indexOf(it) }.with { it == it.sort(false) && !it.contains(-1) }
    }

    def 'over 24 disks, the rest are counted and not read'() {
        given:
        String s = fullProbe(120)
        stub('dlm_tool', DLM_OK)
        smartctl(30)

        when:
        HostFacts f = HostFacts.parse(run(s, 140).output as String)

        then:
        calls().count { it.startsWith('smartctl ') && it.contains('megaraid') } == 24
        f.raidDisks.size() == 24
        f.raidDisksUnread == 6
    }
}
