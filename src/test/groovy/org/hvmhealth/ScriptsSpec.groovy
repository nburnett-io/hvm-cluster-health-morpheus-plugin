package org.hvmhealth

import org.hvmhealth.fixes.Fixes
import org.hvmhealth.fixes.Preflight
import org.hvmhealth.fixes.VlanTest
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
}
