package org.hvmhealth

import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.MorpheusServices
import com.morpheusdata.model.ComputeServer
import com.morpheusdata.model.ComputeServerGroup
import com.morpheusdata.model.ComputeServerGroupType
import com.morpheusdata.model.ComputeTypeLayout
import com.morpheusdata.model.TaskResult
import io.reactivex.rxjava3.core.Single
import org.hvmhealth.model.HostFacts
import org.hvmhealth.morpheus.Availability
import org.hvmhealth.morpheus.FreezeWatch
import org.hvmhealth.morpheus.FreezeWatch.Reading
import org.hvmhealth.morpheus.HealthContext
import org.hvmhealth.morpheus.HealthMonitor
import spock.lang.Specification
import spock.util.concurrent.PollingConditions

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** What a reading means, what it sets off, and the real threads around it. The load across 200 clusters is in ScaleSpec. */
class FreezeWatchSpec extends Specification {
    static final long NOW = 1_800_000_000_000L
    static final long SEC = 1000L
    static final List<String> HOSTS = ['hvm-a.invalid', 'hvm-b.invalid', 'hvm-c.invalid']

    /** A stopped lockspace, as dlm_tool prints it. */
    static final String STOPPED = '''@@dlm_ls
dlm lockspaces
name          ds-one
id            0x1
flags         0x00000004 kern_stop
change        member 2 joined 0 remove 1 failed 1 seq 4,4
members       2 3
new change    member 2 joined 0 remove 1 failed 1 seq 5,5
new status    wait fencing
new members   2 3
DLM_RC=0
@@quorum
Quorate:          Yes
'''
    static final String RUNNING = '''@@dlm_ls
dlm lockspaces
name          ds-one
id            0x1
flags         0x00000000
change        member 3 joined 1 remove 0 failed 0 seq 3,3
members       1 2 3
DLM_RC=0
@@quorum
Quorate:          Yes
'''

    private static FreezeWatch.Schedule schedule(List<Long> ids = [1L], long now = NOW) {
        FreezeWatch.Schedule s = new FreezeWatch.Schedule(new Random(7))
        s.update(ids, 60L, 120, now)
        s
    }

    /** Facts a check would hold: frozen, clear with a clean DLM answer, or a silent host. */
    private static HostFacts facts(String kind) {
        kind == 'frozen' ? HostFacts.parse(STOPPED) : (kind == 'clear' ? HostFacts.parse(RUNNING) : new HostFacts())
    }

    def cleanup() {
        FreezeWatch.stop()
        HealthMonitor.stop()
        FreezeWatch.FROZEN_SINCE.clear()
    }

    // ------------------------------------------------------------------ what one read says

    def 'a stopped lockspace reads as frozen, and running ones as clear'() {
        expect:
        FreezeWatch.reading(HostFacts.parse(STOPPED)) == Reading.FROZEN
        FreezeWatch.reading(HostFacts.parse(RUNNING)) == Reading.CLEAR
    }

    def 'a fence line is not a freeze, the same as for HostFacts.isFrozen'() {
        given:
        HostFacts f = HostFacts.parse(RUNNING + '@@dlm_status\nfence 1 add pid 123 actor 2 fail 1800000000 fence 0 now 1800000060\n')

        expect:
        f.fenceWaits
        !f.frozen
        FreezeWatch.reading(f) == Reading.CLEAR
    }

    def 'no lockspaces with a clean answer is clear: nothing is mounted to freeze'() {
        expect:
        FreezeWatch.reading(HostFacts.parse('@@dlm_ls\ndlm lockspaces\nDLM_RC=0\n@@quorum\n')) == Reading.CLEAR
    }

    def 'an unread answer is no answer, never clear: #why'() {
        expect:
        FreezeWatch.reading(output == null ? null : HostFacts.parse(output)) == Reading.NONE

        where:
        why                       | output
        'nothing came back'       | ''
        'no agent call at all'    | null
        'dlm_tool timed out'      | '@@dlm_ls\nDLM_RC=124\n@@quorum\n'
        'dlm_controld not there'  | '@@dlm_ls\nDLM_RC=1\n@@quorum\nQuorate: Yes\n'
        'cut off before the code' | '@@dlm_ls\nname ds-one\nflags 0x0\n'
    }

    def 'quorum read is yes, no, or not read, and not read is not no'() {
        expect:
        FreezeWatch.quorate(HostFacts.parse(text)) == want

        where:
        text                                 | want
        '@@quorum\nQuorate:          Yes\n'  | true
        '@@quorum\nQuorate:          No\n'   | false
        '@@quorum\n'                         | null
        '@@dlm_ls\nDLM_RC=0\n'               | null
    }

    def 'the script reads DLM and quorum only, within the limit, and could not touch GFS2 or change anything'() {
        given:
        String s = HealthContext.WATCH_SCRIPT
        List<Integer> limits = s.findAll(/timeout -k (\d+) (\d+) /) { all, k, t -> (k as int) + (t as int) }

        expect:
        s.indexOf('dlm_tool ls') < s.indexOf('corosync-quorumtool')
        limits.sum() < HealthContext.WATCH_TIMEOUT_SEC
        !(s =~ /(?i)\bdf\b|\bstat\b|\bls \/|gfs2|\/proc\/mounts|heartbeat|virsh|fence_ack|\bmount\b|umount|systemctl|\brm\b|>\s*\/(?!dev\/null)/)
        s.readLines().findAll { it.contains('dlm_tool') || it.contains('corosync') }.every { it.startsWith('timeout ') }
    }

    // ------------------------------------------------------------------ a freeze starting

    private static FreezeWatch.Watch due(FreezeWatch.Schedule s, long now) {
        s.pick(now + 60 * SEC, 8)[0]
    }

    def 'one sighting is not a freeze: it is read again on the same host, and clears with no fuss if it was a moment'() {
        given:
        FreezeWatch.Schedule s = schedule()
        FreezeWatch.Watch w = due(s, NOW)

        when: 'a lockspace stopped for a moment, as one does while a host mounts'
        Map first = s.result(w, HOSTS[1], Reading.FROZEN, true, HOSTS, NOW)

        then:
        first == [:]
        w.pendingAt == NOW
        w.frozenHost == null
        w.nextAt == NOW + FreezeWatch.CONFIRM_MS
        s.chooseHost(w, HOSTS, NOW) == HOSTS[1]

        when:
        Map second = s.result(w, HOSTS[1], Reading.CLEAR, true, HOSTS, NOW + FreezeWatch.CONFIRM_MS)

        then:
        second == [:]
        w.pendingAt == null
        w.frozenHost == null
        !w.soon
    }

    def 'seen twice it is a freeze, dated from the first sighting, with a check asked for'() {
        given:
        FreezeWatch.Schedule s = schedule()
        FreezeWatch.Watch w = due(s, NOW)
        s.result(w, HOSTS[1], Reading.FROZEN, true, HOSTS, NOW)

        when:
        Map e = s.result(w, HOSTS[1], Reading.FROZEN, true, HOSTS, NOW + FreezeWatch.CONFIRM_MS)

        then:
        e == [started: NOW, check: true]
        w.frozenHost == HOSTS[1]
        w.pendingAt == null
    }

    def 'a confirmation comes ahead of clusters that are simply due'() {
        given:
        FreezeWatch.Schedule s = schedule([1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L, 9L, 10L, 11L, 12L])
        s.pick(NOW + 5 * 60 * SEC, 1)
        FreezeWatch.Watch w = s.watches[3L]
        w.reading = false
        s.result(w, HOSTS[0], Reading.FROZEN, true, HOSTS, NOW)

        when: 'plenty are due, and there is room for one'
        List<FreezeWatch.Watch> picked = s.pick(NOW + 5 * 60 * SEC + 5 * SEC, 1)

        then:
        picked*.id == [3L]
    }

    def 'a read too early does not count as the second sighting'() {
        given:
        FreezeWatch.Schedule s = schedule()
        FreezeWatch.Watch w = due(s, NOW)
        s.result(w, HOSTS[1], Reading.FROZEN, true, HOSTS, NOW)

        when:
        Map e = s.result(w, HOSTS[1], Reading.FROZEN, true, HOSTS, NOW + 1 * SEC)

        then:
        e == [:]
        w.frozenHost == null
        w.nextAt == NOW + FreezeWatch.CONFIRM_MS
    }

    def 'a host that does not answer proves nothing, so a second is tried, and only a second'() {
        given:
        FreezeWatch.Schedule s = schedule()
        FreezeWatch.Watch w = due(s, NOW)
        String first = s.chooseHost(w, HOSTS, NOW)

        when:
        Map e = s.result(w, first, Reading.NONE, null, HOSTS, NOW)

        then:
        e == [:]
        w.soon
        w.nextAt == NOW
        w.frozenHost == null

        when: 'the other is chosen, and is silent too'
        String second = s.chooseHost(w, HOSTS, NOW + 2 * SEC)
        Map again = s.result(w, second, Reading.NONE, null, HOSTS, NOW + 20 * SEC)

        then:
        second != first
        again == [:]
        !w.soon
        w.nextAt > NOW + 20 * SEC
        w.tried.isEmpty()
        w.frozenHost == null
    }

    def 'a single-host cluster has no second host to try'() {
        given:
        FreezeWatch.Schedule s = schedule()
        FreezeWatch.Watch w = due(s, NOW)

        when:
        s.result(w, HOSTS[0], Reading.NONE, null, HOSTS.take(1), NOW)

        then:
        !w.soon
        w.nextAt > NOW
    }

    def 'a host that did not answer is left alone for a while if others answer, then tried again'() {
        given:
        FreezeWatch.Schedule s = schedule()
        FreezeWatch.Watch w = due(s, NOW)
        s.result(w, HOSTS[0], Reading.NONE, null, HOSTS, NOW)
        s.result(w, HOSTS[1], Reading.CLEAR, true, HOSTS, NOW + 2 * SEC)

        expect: 'it is not read again meanwhile'
        (1..6).collect { s.chooseHost(w, HOSTS, NOW + 100 * SEC) }.every { it != HOSTS[0] }

        and: 'its time up, it is back in the rotation'
        (1..6).collect { s.chooseHost(w, HOSTS, NOW + FreezeWatch.QUIET_MS) }.contains(HOSTS[0])

        when: 'and every host quiet: one must still be tried'
        s.result(w, HOSTS[1], Reading.NONE, null, HOSTS, NOW + 120 * SEC)
        s.result(w, HOSTS[2], Reading.NONE, null, HOSTS, NOW + 140 * SEC)

        then:
        s.chooseHost(w, HOSTS, NOW + 160 * SEC) != null

        when: 'answering takes it off the list'
        s.result(w, HOSTS[1], Reading.CLEAR, true, HOSTS, NOW + 180 * SEC)

        then:
        !w.quiet.containsKey(HOSTS[1])
    }

    def 'a frozen host that has gone silent is left too, so a frozen cluster with a dead host does not pay for two reads every round'() {
        given:
        FreezeWatch.Schedule s = frozenOn(HOSTS[1])
        FreezeWatch.Watch w = s.watches[1L]

        when:
        s.result(w, HOSTS[1], Reading.NONE, null, HOSTS, NOW + 100 * SEC)
        s.result(w, HOSTS[0], Reading.FROZEN, true, HOSTS, NOW + 102 * SEC)

        then: 'the host that answered is now the one watched'
        w.frozenHost == HOSTS[0]
        s.chooseHost(w, HOSTS, NOW + 200 * SEC) == HOSTS[0]
    }

    def 'hosts are read in rotation'() {
        given:
        FreezeWatch.Schedule s = schedule()
        FreezeWatch.Watch w = s.watches[1L]

        expect:
        (1..6).collect { s.chooseHost(w, HOSTS, NOW) } == HOSTS + HOSTS
    }

    def 'a sighting that cannot be confirmed because the host went silent asks a check, which reads them all, and does not call it frozen'() {
        given:
        FreezeWatch.Schedule s = schedule()
        FreezeWatch.Watch w = due(s, NOW)
        s.result(w, HOSTS[1], Reading.FROZEN, true, HOSTS, NOW)

        when: 'its second host says clear, which says nothing about the first'
        s.result(w, HOSTS[1], Reading.NONE, null, HOSTS, NOW + 15 * SEC)
        Map e = s.result(w, HOSTS[2], Reading.CLEAR, true, HOSTS, NOW + 35 * SEC)

        then:
        e == [check: true]
        w.frozenHost == null
        w.pendingAt == null
    }

    def 'losing quorum asks for a check once, not on every read, and coming back asks again'() {
        given:
        FreezeWatch.Schedule s = schedule()
        FreezeWatch.Watch w = due(s, NOW)

        expect:
        s.result(w, HOSTS[0], Reading.CLEAR, true, HOSTS, NOW) == [:]
        s.result(w, HOSTS[1], Reading.CLEAR, false, HOSTS, NOW + 100 * SEC) == [check: true]
        s.result(w, HOSTS[2], Reading.CLEAR, false, HOSTS, NOW + 200 * SEC) == [:]
        s.result(w, HOSTS[0], Reading.CLEAR, true, HOSTS, NOW + 300 * SEC) == [check: true]
        s.result(w, HOSTS[1], Reading.CLEAR, null, HOSTS, NOW + 400 * SEC) == [:]
    }

    // ------------------------------------------------------------------ a freeze ending

    private static FreezeWatch.Schedule frozenOn(String host) {
        FreezeWatch.Schedule s = schedule()
        FreezeWatch.Watch w = due(s, NOW)
        s.result(w, host, Reading.FROZEN, true, HOSTS, NOW)
        s.result(w, host, Reading.FROZEN, true, HOSTS, NOW + FreezeWatch.CONFIRM_MS)
        s
    }

    def 'while frozen, the frozen host is the one read, and that costs one read a round like any other'() {
        given:
        FreezeWatch.Schedule s = frozenOn(HOSTS[1])
        FreezeWatch.Watch w = s.watches[1L]

        expect:
        s.chooseHost(w, HOSTS, NOW) == HOSTS[1]
        s.chooseHost(w, HOSTS, NOW) == HOSTS[1]
        s.result(w, HOSTS[1], Reading.FROZEN, true, HOSTS, NOW + 100 * SEC) == [:]
        w.nextAt >= NOW + 100 * SEC + 50 * SEC
        !w.soon
    }

    def 'the frozen host reading clear does not end a freeze: a restarted host has no lockspaces at all. It asks for a check'() {
        given:
        FreezeWatch.Schedule s = frozenOn(HOSTS[1])
        FreezeWatch.Watch w = s.watches[1L]

        when:
        Map e = s.result(w, HOSTS[1], Reading.CLEAR, true, HOSTS, NOW + 100 * SEC)

        then:
        e == [check: true]
        w.frozenHost == HOSTS[1]
        w.clearAt == NOW + 100 * SEC
    }

    def 'another host reading clear while the frozen one is silent says nothing'() {
        given:
        FreezeWatch.Schedule s = frozenOn(HOSTS[1])
        FreezeWatch.Watch w = s.watches[1L]

        when:
        s.result(w, HOSTS[1], Reading.NONE, null, HOSTS, NOW + 100 * SEC)
        Map e = s.result(w, HOSTS[2], Reading.CLEAR, true, HOSTS, NOW + 120 * SEC)

        then:
        e == [:]
        w.frozenHost == HOSTS[1]
    }

    def 'a check reading every host clear ends it, dated from the first clear read, and the watch goes back to rotating'() {
        given:
        FreezeWatch.Schedule s = frozenOn(HOSTS[1])
        FreezeWatch.Watch w = s.watches[1L]
        s.result(w, HOSTS[1], Reading.CLEAR, true, HOSTS, NOW + 100 * SEC)

        when:
        Map e = s.fromCheck(1L, HOSTS.collectEntries { [(it): facts('clear')] }, NOW + 120 * SEC)

        then:
        e == [ended: NOW + 100 * SEC]
        w.frozenHost == null
        w.clearAt == null
        w.mustRead == null
    }

    def 'a check with #why does not end it'() {
        given:
        FreezeWatch.Schedule s = frozenOn(HOSTS[1])

        when:
        Map e = s.fromCheck(1L, read.collectEntries { k, v -> [(k): facts(v)] }, NOW + 120 * SEC)

        then:
        e == [:]
        s.watches[1L].frozenHost != null

        where:
        why                                    | read
        'the frozen host still frozen'         | [(HOSTS[0]): 'clear', (HOSTS[1]): 'frozen', (HOSTS[2]): 'clear']
        'the frozen host silent'               | [(HOSTS[0]): 'clear', (HOSTS[1]): 'silent', (HOSTS[2]): 'clear']
        'too few hosts answering'              | [(HOSTS[0]): 'silent', (HOSTS[1]): 'clear', (HOSTS[2]): 'silent']
        'every host silent'                    | [(HOSTS[0]): 'silent', (HOSTS[1]): 'silent', (HOSTS[2]): 'silent']
        'another host frozen'                  | [(HOSTS[0]): 'frozen', (HOSTS[1]): 'clear', (HOSTS[2]): 'clear']
        'no hosts at all'                      | [:]
    }

    def 'the survivor of two hosts can end it, as the dead one may never answer'() {
        given:
        FreezeWatch.Schedule s = frozenOn(HOSTS[1])

        expect:
        s.fromCheck(1L, [(HOSTS[0]): facts('silent'), (HOSTS[1]): facts('clear')], NOW + 120 * SEC) == [ended: NOW + 120 * SEC]
    }

    def 'another host frozen takes over as the one to watch'() {
        given:
        FreezeWatch.Schedule s = frozenOn(HOSTS[1])

        when:
        s.fromCheck(1L, [(HOSTS[0]): facts('frozen'), (HOSTS[1]): facts('clear')], NOW + 120 * SEC)

        then:
        s.watches[1L].frozenHost == HOSTS[0]
    }

    def 'a frozen host that has left the cluster does not hold the freeze open for ever'() {
        given:
        FreezeWatch.Schedule s = frozenOn(HOSTS[1])

        when: 'removed, or in maintenance'
        Map e = s.fromCheck(1L, [(HOSTS[0]): facts('clear'), (HOSTS[2]): facts('clear')], NOW + 120 * SEC)

        then:
        e == [ended: NOW + 120 * SEC]
    }

    def 'a check that sees a freeze first needs the same second look'() {
        given:
        FreezeWatch.Schedule s = schedule()
        Map<String, HostFacts> frozen = [(HOSTS[0]): facts('clear'), (HOSTS[1]): facts('frozen')]

        when:
        Map first = s.fromCheck(1L, frozen, NOW)

        then:
        first == [:]
        s.watches[1L].pendingHost == HOSTS[1]
        s.watches[1L].soon

        when: 'the watch turned off or not yet round: the next check is the second look'
        Map second = s.fromCheck(1L, frozen, NOW + 300 * SEC)

        then:
        second == [started: NOW]
        s.watches[1L].frozenHost == HOSTS[1]
    }

    def 'a check that finds the sighting has passed forgets it'() {
        given:
        FreezeWatch.Schedule s = schedule()
        s.fromCheck(1L, [(HOSTS[1]): facts('frozen')], NOW)

        when:
        Map e = s.fromCheck(1L, [(HOSTS[1]): facts('clear')], NOW + 5 * SEC)

        then:
        !e.started
        s.watches[1L].pendingAt == null
        !s.watches[1L].soon
    }

    // ------------------------------------------------------------------ a reload mid-way

    def 'after a reload, a freeze still there is found again by the same two sightings'() {
        given: 'all in memory: a new schedule knows nothing'
        FreezeWatch.Schedule s = schedule()
        FreezeWatch.Watch w = due(s, NOW)

        expect:
        s.result(w, HOSTS[0], Reading.FROZEN, true, HOSTS, NOW) == [:]
        s.result(w, HOSTS[0], Reading.FROZEN, true, HOSTS, NOW + FreezeWatch.CONFIRM_MS) == [started: NOW, check: true]
    }

    def 'the first clear check after a reload closes any freeze left open, once, and a host read by the watch alone does not'() {
        given:
        FreezeWatch.Schedule s = schedule()
        FreezeWatch.Watch w = due(s, NOW)
        Map<String, HostFacts> all = HOSTS.collectEntries { [(it): facts('clear')] }

        expect:
        s.result(w, HOSTS[0], Reading.CLEAR, true, HOSTS, NOW) == [:]
        s.fromCheck(1L, all, NOW + 60 * SEC) == [ended: NOW + 60 * SEC]
        s.fromCheck(1L, all, NOW + 360 * SEC) == [:]
    }

    def 'a first check with hosts silent does not close it'() {
        given:
        FreezeWatch.Schedule s = schedule()

        expect:
        s.fromCheck(1L, HOSTS.collectEntries { [(it): facts('silent')] }, NOW) == [:]
        !s.watches[1L].endChecked
    }

    // ------------------------------------------------------------------ the settings

    def 'freezeWatchSeconds #value reads as #want'() {
        expect:
        FreezeWatch.seconds(value == null ? [:] : [freezeWatchSeconds: value]) == want

        where:
        value  | want
        null   | 60L
        ''     | 60L
        'x'    | 60L
        '0'    | 0L
        '-5'   | 0L
        '1'    | 10L
        '45'   | 45L
        30     | 30L
    }

    def 'off means no reads, and the interval follows the budget only when it must'() {
        given:
        FreezeWatch.Schedule off = new FreezeWatch.Schedule(new Random(1))
        off.update([1L], 0L, 120, NOW)

        expect:
        off.pick(NOW + 3600 * SEC, 8) == []
        off.summary(NOW).on == false
        FreezeWatch.intervalMs(60L, 120, 30) == 60_000L
        FreezeWatch.intervalMs(60L, 120, 120) == 60_000L
        FreezeWatch.intervalMs(60L, 120, 200) == 100_000L
        FreezeWatch.intervalMs(60L, 10, 200) == 1_200_000L
    }

    def 'the tab and page are told the real interval, and when it has stretched'() {
        given:
        FreezeWatch.Schedule s = schedule((1L..200L) as List<Long>)

        expect:
        s.forCluster(7L) == [seconds: 100L, setTo: 60L]
        s.forCluster(999L) == null
        s.summary(NOW).realSeconds == 100L
        s.summary(NOW).clusters == 200
    }

    def 'HCI Ceph clusters are not read, and a cluster whose layout is not on its record is'() {
        expect:
        FreezeWatch.hasGfs2(new ComputeServerGroup(layout: new ComputeTypeLayout(code: Fixture.LAYOUT_13)))
        FreezeWatch.hasGfs2(new ComputeServerGroup(layout: new ComputeTypeLayout(code: Fixture.LAYOUT_20)))
        FreezeWatch.hasGfs2(new ComputeServerGroup())
        !FreezeWatch.hasGfs2(new ComputeServerGroup(layout: new ComputeTypeLayout(code: 'mvm-1.3-ubuntu-24.04-ceph-hci-amd64')))
    }

    def 'a 1.3 layout code it does not recognise is still watched, and keeps its DLM checks: #code'() {
        given:
        HostFacts f = Fixture.facts('probe-frozen', 'hvm-node01')

        expect:
        FreezeWatch.hasGfs2(new ComputeServerGroup(layout: new ComputeTypeLayout(code: code)))
        new org.hvmhealth.checks.HealthChecker().check(Fixture.input([Fixture.host('hvm-node01', f)], code)).any { it.id == 'DLM-01' }

        where:
        code << ['mvm-1.3-ubuntu-24.04-morpheus-amd64', 'mvm-1.3-ubuntu-24.04-custom-amd64', 'mvm-1.3-ubuntu-24.04-gfs2-amd64']
    }

    // ------------------------------------------------------------------ with real threads, on a Morpheus that records what it was asked

    def 'during the reload grace the watch still reads, and a freeze it confirms is critical on the status URL with no check yet'() {
        given:
        HealthMonitor.LAST.remove(27L)
        onRead = { new TaskResult(success: true, exitCode: '0', output: STOPPED) }
        MorpheusContext m = morpheus([CLUSTER], [new ComputeServer(id: 2L, name: 'hvm-b.invalid')])
        HealthMonitor.start(m, new ClusterHealthPlugin())
        long now = HealthMonitor.@loadedAt - 60_000L
        HealthMonitor.sweep(m, new ClusterHealthPlugin(), now)
        FreezeWatch.Schedule s = FreezeWatch.@schedule
        FreezeWatch.Watch w = s.watches[27L]

        expect: 'reloading until the watch knows better'
        HealthMonitor.statusAnswer('/cluster/27') == [200, 'HVM-HEALTH OK\nreloading\n']

        when:
        FreezeWatch.tick(m, s, now + 200 * SEC)
        new PollingConditions(timeout: 5).eventually { assert w.pendingAt != null }
        w.pendingAt = w.pendingAt - 2 * FreezeWatch.CONFIRM_MS
        w.nextAt = 0L
        FreezeWatch.tick(m, s, now + 400 * SEC)

        then:
        new PollingConditions(timeout: 5).eventually { assert FreezeWatch.FROZEN_SINCE.containsKey(27L) }
        HealthMonitor.statusAnswer('/cluster/27') == [503, 'HVM-HEALTH CRITICAL\n']

        cleanup:
        HealthMonitor.stop()
    }

    def 'a check still waiting for a checker does not stop the watch reading that cluster, and one running does'() {
        given:
        long now = System.currentTimeMillis()
        MorpheusContext m = morpheus([CLUSTER], [new ComputeServer(id: 2L, name: 'hvm-b.invalid')])
        FreezeWatch.Schedule s = running(m, now)

        when: 'a check is running'
        HealthMonitor.@CHECKING.add(27L)
        FreezeWatch.tick(m, s, now + 200 * SEC)
        Thread.sleep(200)

        then:
        asked == []

        when: 'it is waiting in the queue'
        HealthMonitor.@QUEUED[27L] = new HealthMonitor.CheckTask(1, 27L, { } as Runnable)
        s.watches[27L].nextAt = 0L
        FreezeWatch.tick(m, s, now + 400 * SEC)

        then:
        new PollingConditions(timeout: 5).eventually { assert asked == ['hvm-b.invalid'] }

        cleanup:
        HealthMonitor.@CHECKING.remove(27L)
        HealthMonitor.@QUEUED.remove(27L)
    }

    def 'with every thread held by a call that never returns, the watch says it has stopped, and stops saying so when a read starts again'() {
        given:
        long now = System.currentTimeMillis()
        CountDownLatch never = new CountDownLatch(1)
        // one that ignores being interrupted, as only that really holds a thread
        onRead = {
            long stop = System.currentTimeMillis() + 30_000L
            while (never.count > 0 && System.currentTimeMillis() < stop) {
                try {
                    Thread.sleep(20)
                } catch (InterruptedException ignored) {
                    // carries on
                }
            }
            new TaskResult(success: true, exitCode: '0', output: RUNNING)
        }
        MorpheusContext m = morpheus([CLUSTER], [new ComputeServer(id: 2L, name: 'hvm-b.invalid')])
        FreezeWatch.Schedule s = running(m, now)
        FreezeWatch.@threads.maximumPoolSize = 1
        FreezeWatch.Watch w = s.watches[27L]
        long t = now + 200 * SEC

        when: 'the one thread is taken, and then its limit passes with the call still in it'
        FreezeWatch.tick(m, s, t)
        new PollingConditions(timeout: 5).eventually { assert asked.size() == 1 }
        t += FreezeWatch.READ_LIMIT_MS + SEC
        FreezeWatch.tick(m, s, t)
        w.nextAt = 0L
        t += 2 * SEC
        FreezeWatch.tick(m, s, t)

        then: 'refused, but not for long enough to mean anything'
        !w.reading
        FreezeWatch.@refusedSince == t
        !FreezeWatch.stopped(t)

        when:
        w.nextAt = 0L
        FreezeWatch.tick(m, s, t + FreezeWatch.STOPPED_MS)

        then:
        FreezeWatch.stopped(t + FreezeWatch.STOPPED_MS)
        asked.size() == 1

        when: 'the call comes back'
        never.countDown()
        new PollingConditions(timeout: 5).eventually { assert FreezeWatch.@threads.activeCount == 0 }
        w.nextAt = 0L
        FreezeWatch.tick(m, s, t + FreezeWatch.STOPPED_MS + SEC)

        then:
        !FreezeWatch.stopped(t + FreezeWatch.STOPPED_MS + SEC)
        new PollingConditions(timeout: 5).eventually { assert asked.size() == 2 }

        cleanup:
        never.countDown()
    }

    def 'a watch with nothing to read, or switched off, has not stopped, whatever was refused before'() {
        given:
        long now = System.currentTimeMillis()
        MorpheusContext m = morpheus([CLUSTER], [])
        FreezeWatch.Schedule s = running(m, now)

        when:
        FreezeWatch.@refusedSince = now
        boolean on = FreezeWatch.stopped(now + FreezeWatch.STOPPED_MS)
        FreezeWatch.update([CLUSTER], [:], false, now)
        boolean off = FreezeWatch.stopped(now + FreezeWatch.STOPPED_MS)
        FreezeWatch.update([], [:], true, now)
        boolean none = FreezeWatch.stopped(now + FreezeWatch.STOPPED_MS)

        then:
        s != null
        on
        !off
        !none
    }

    List<String> asked = Collections.synchronizedList([])
    Closure<TaskResult> onRead = { new TaskResult(success: true, exitCode: '0', output: RUNNING) }

    private MorpheusContext morpheus(List<ComputeServerGroup> clusters, List<ComputeServer> hosts) {
        FreezeWatchSpec self = this
        def clusterService = Stub(MorpheusServices.getMethod('getCluster').returnType) {
            list(_) >> clusters
            get(_) >> { Long id -> clusters.find { it.id == id } }
        }
        def servers = Stub(MorpheusServices.getMethod('getComputeServer').returnType) { list(_) >> { hosts } }
        def services = Stub(MorpheusServices) { getCluster() >> clusterService; getComputeServer() >> servers }
        Stub(MorpheusContext) {
            getServices() >> services
            getSettings(_) >> Single.just('{}')
            executeCommandOnServer(_, _) >> { args ->
                self.asked << (args[0] as ComputeServer).name
                Single.fromCallable { self.onRead.call() }
            }
        }
    }

    private static final ComputeServerGroup CLUSTER = new ComputeServerGroup(id: 27L, name: 'cluster-x', type: new ComputeServerGroupType(code: 'mvm-cluster'))

    private static FreezeWatch.Schedule running(MorpheusContext m, long now) {
        FreezeWatch.start(m)
        FreezeWatch.update([CLUSTER], [:], true, now)
        FreezeWatch.@schedule
    }

    def 'one read asks one host, not one in maintenance, and a healthy read sets off nothing'() {
        given:
        long now = System.currentTimeMillis()
        MorpheusContext m = morpheus([CLUSTER], [new ComputeServer(id: 1L, name: 'hvm-a.invalid', status: 'maintenance'), new ComputeServer(id: 2L, name: 'hvm-b.invalid', status: 'provisioned')])
        FreezeWatch.Schedule s = running(m, now)

        when:
        FreezeWatch.tick(m, s, now + 200 * SEC)

        then:
        new PollingConditions(timeout: 5).eventually { assert s.watches[27L].lastReadAt != null }
        asked == ['hvm-b.invalid']
        FreezeWatch.FROZEN_SINCE.isEmpty()
    }

    def 'a whole cluster in maintenance costs no agent call'() {
        given:
        long now = System.currentTimeMillis()
        MorpheusContext m = morpheus([CLUSTER], [new ComputeServer(id: 1L, name: 'hvm-a.invalid', status: 'maintenance')])
        FreezeWatch.Schedule s = running(m, now)

        when:
        FreezeWatch.tick(m, s, now + 200 * SEC)
        new PollingConditions(timeout: 5).eventually { assert !s.watches[27L].reading }

        then:
        asked == []
        FreezeWatch.FROZEN_SINCE.isEmpty()
    }

    def 'a real freeze ends up in FROZEN_SINCE after the second look, whoever keeps count is told, and a check ends it'() {
        given:
        long now = System.currentTimeMillis()
        List<List> told = Collections.synchronizedList([])
        Availability.metaClass.static.freezeStarted = { MorpheusContext x, Long id, long at -> told << ['started', id, at] }
        Availability.metaClass.static.freezeEnded = { MorpheusContext x, Long id, long at -> told << ['ended', id, at] }
        onRead = { new TaskResult(success: true, exitCode: '0', output: STOPPED) }
        MorpheusContext m = morpheus([CLUSTER], [new ComputeServer(id: 2L, name: 'hvm-b.invalid')])
        FreezeWatch.Schedule s = running(m, now)
        FreezeWatch.Watch w = s.watches[27L]

        when: 'the first read'
        FreezeWatch.tick(m, s, now + 200 * SEC)
        new PollingConditions(timeout: 5).eventually { assert w.pendingAt != null }

        then: 'one sighting: not yet'
        FreezeWatch.FROZEN_SINCE.isEmpty()
        told == []

        when: 'the second, with the first put CONFIRM_MS back, as these read the real clock'
        long first = w.pendingAt - 2 * FreezeWatch.CONFIRM_MS
        w.pendingAt = first
        w.nextAt = 0L
        FreezeWatch.tick(m, s, now + 400 * SEC)

        then:
        new PollingConditions(timeout: 5).eventually { assert FreezeWatch.FROZEN_SINCE[27L] == first }
        told == [['started', 27L, first]]

        when: 'a read of it clear is only a reason to look'
        onRead = { new TaskResult(success: true, exitCode: '0', output: RUNNING) }
        w.nextAt = 0L
        FreezeWatch.tick(m, s, now + 600 * SEC)
        new PollingConditions(timeout: 5).eventually { assert w.clearAt != null }

        then:
        FreezeWatch.FROZEN_SINCE.containsKey(27L)

        when: 'a check of every host ends it'
        FreezeWatch.fromCheck(m, 27L, ['hvm-b.invalid': facts('clear')], System.currentTimeMillis())

        then:
        !FreezeWatch.FROZEN_SINCE.containsKey(27L)
        told[1][0] == 'ended'
        told[1][2] == w.clearAt ?: told[1][2]

        cleanup:
        GroovySystem.metaClassRegistry.removeMetaClass(Availability)
    }

    def 'trouble recording a freeze does not stop it being known'() {
        given:
        long now = System.currentTimeMillis()
        Availability.metaClass.static.freezeStarted = { MorpheusContext x, Long id, long at -> throw new IllegalStateException('no store') }
        MorpheusContext m = morpheus([CLUSTER], [])
        FreezeWatch.Schedule s = running(m, now)
        Map<String, HostFacts> frozen = ['hvm-b.invalid': facts('frozen')]

        when:
        FreezeWatch.fromCheck(m, 27L, frozen, now)
        FreezeWatch.fromCheck(m, 27L, frozen, now + 300 * SEC)

        then:
        noExceptionThrown()
        s != null
        FreezeWatch.FROZEN_SINCE[27L] == now

        cleanup:
        GroovySystem.metaClassRegistry.removeMetaClass(Availability)
    }

    def 'a silent agent, which never returns, does not hold the reader for ever and proves nothing'() {
        given:
        long now = System.currentTimeMillis()
        CountDownLatch never = new CountDownLatch(1)
        AtomicInteger calls = new AtomicInteger()
        onRead = {
            if (calls.incrementAndGet() == 1) {
                // one that ignores being interrupted, so it really does come back late
                long stop = System.currentTimeMillis() + 30_000L
                while (never.count > 0 && System.currentTimeMillis() < stop) {
                    try {
                        Thread.sleep(20)
                    } catch (InterruptedException ignored) {
                        // carries on
                    }
                }
                return new TaskResult(success: true, exitCode: '0', output: STOPPED)
            }
            new TaskResult(success: true, exitCode: '0', output: RUNNING)
        }
        MorpheusContext m = morpheus([CLUSTER], [new ComputeServer(id: 1L, name: 'hvm-a.invalid'), new ComputeServer(id: 2L, name: 'hvm-b.invalid')])
        FreezeWatch.Schedule s = running(m, now)
        FreezeWatch.Watch w = s.watches[27L]

        when:
        FreezeWatch.tick(m, s, now + 200 * SEC)
        new PollingConditions(timeout: 5).eventually { assert asked.size() == 1 }

        then: 'still within its limit'
        w.reading

        when: 'past it'
        FreezeWatch.tick(m, s, now + 200 * SEC + FreezeWatch.READ_LIMIT_MS + SEC)

        then: 'the second host is tried at once, and nothing says frozen or clear'
        new PollingConditions(timeout: 5).eventually { assert asked.size() == 2 && !w.reading }
        asked[0] != asked[1]
        w.quiet.containsKey(asked[0])
        w.pendingAt == null
        FreezeWatch.FROZEN_SINCE.isEmpty()

        when: 'the first, late, says frozen'
        never.countDown()
        Thread.sleep(200)

        then: 'its limit had passed, and it does not stand in for the second'
        w.pendingAt == null

        cleanup:
        never.countDown()
    }

    def 'calls that never return cannot silence the watch: past their limit they no longer hold a place'() {
        given:
        long now = System.currentTimeMillis()
        CountDownLatch never = new CountDownLatch(1)
        onRead = { never.await(30, TimeUnit.SECONDS); new TaskResult(success: true, exitCode: '0', output: RUNNING) }
        List<ComputeServerGroup> many = (1..20).collect { new ComputeServerGroup(id: 700L + it, name: "c${it}".toString(), type: new ComputeServerGroupType(code: 'mvm-cluster')) }
        MorpheusContext m = morpheus(many, [new ComputeServer(id: 2L, name: 'hvm-b.invalid')])
        FreezeWatch.start(m)
        FreezeWatch.update(many, [:], true, now)
        FreezeWatch.Schedule s = FreezeWatch.@schedule

        when: 'ticks go by with every read stuck'
        (1..10).each { FreezeWatch.tick(m, s, now + 120 * SEC + it * 2 * SEC) }
        new PollingConditions(timeout: 5).eventually { assert asked.size() == FreezeWatch.READERS }
        Thread.sleep(100)

        then: 'no more than the readers it allows'
        asked.size() == FreezeWatch.READERS

        when: 'their limit passes'
        long later = now + 120 * SEC + FreezeWatch.READ_LIMIT_MS + 30 * SEC
        FreezeWatch.tick(m, s, later)

        then: 'and the watch carries on, though those threads have not come back'
        new PollingConditions(timeout: 5).eventually { assert asked.size() > FreezeWatch.READERS }

        cleanup:
        never.countDown()
    }

    def 'a failed agent call is no answer, not a clear one, and does not stop the tick'() {
        given:
        long now = System.currentTimeMillis()
        onRead = { throw new IOException('agent gone') }
        MorpheusContext m = morpheus([CLUSTER], [new ComputeServer(id: 1L, name: 'hvm-a.invalid'), new ComputeServer(id: 2L, name: 'hvm-b.invalid')])
        FreezeWatch.Schedule s = running(m, now)

        when:
        FreezeWatch.tick(m, s, now + 200 * SEC)

        then:
        new PollingConditions(timeout: 5).eventually { assert s.watches[27L].soon }
        FreezeWatch.FROZEN_SINCE.isEmpty()
    }

    def 'with a check already running or waiting the watch spends nothing on that cluster'() {
        given:
        FreezeWatch.Schedule s = schedule([1L, 2L])

        when:
        List<FreezeWatch.Watch> picked = s.pick(NOW + 300 * SEC, 8) { Long id -> id == 1L }

        then:
        picked*.id == [2L]
        s.watches[1L].nextAt > NOW + 300 * SEC
    }

    def 'a freeze found asks for a quick check ahead of clusters waiting in the queue'() {
        given:
        List<ComputeServerGroup> clusters = (1..3).collect { new ComputeServerGroup(id: 600L + it, name: "c${it}".toString(), type: new ComputeServerGroupType(code: 'mvm-cluster')) }
        CountDownLatch release = new CountDownLatch(1)
        List<String> order = Collections.synchronizedList([])
        AtomicInteger n = new AtomicInteger()
        onRead = { new TaskResult(success: true, exitCode: '0', output: RUNNING) }
        // the checker's own stub: the first holds the one checker; those behind it wait in the queue
        MorpheusContext m = held(clusters, release, order, n)
        HealthMonitor.start(m, new ClusterHealthPlugin())

        when: 'one checker, busy; 602 then 603 waiting'
        HealthMonitor.sweep(m, new ClusterHealthPlugin(), System.currentTimeMillis() + 10 * 60_000L)
        new PollingConditions(timeout: 10).eventually { assert n.get() == 1 }
        String runningFirst = order[0]
        boolean asked = HealthMonitor.checkNow(m, 603L)

        then:
        asked
        HealthMonitor.@QUEUED[603L].priority == 0

        when:
        release.countDown()

        then:
        new PollingConditions(timeout: 20).eventually { assert order.size() >= 3 }
        order[0] == runningFirst
        order[1] == 'c3'
        order[2] == 'c2'
    }

    def 'a cluster that is already being checked is not asked for twice, and an unmonitored one not at all'() {
        given:
        List<ComputeServerGroup> clusters = [new ComputeServerGroup(id: 601L, name: 'c1', type: new ComputeServerGroupType(code: 'mvm-cluster'))]
        CountDownLatch release = new CountDownLatch(1)
        AtomicInteger n = new AtomicInteger()
        MorpheusContext m = held(clusters, release, Collections.synchronizedList([]), n)
        HealthMonitor.start(m, new ClusterHealthPlugin())
        HealthMonitor.sweep(m, new ClusterHealthPlugin(), System.currentTimeMillis() + 10 * 60_000L)
        new PollingConditions(timeout: 10).eventually { assert n.get() == 1 }

        expect:
        !HealthMonitor.checkNow(m, 601L)
        !HealthMonitor.checkNow(m, 999L)

        cleanup:
        release.countDown()
    }

    /** A Morpheus whose agent names the cluster it was asked about, by host, and holds the first caller until released. */
    private MorpheusContext held(List<ComputeServerGroup> clusters, CountDownLatch release, List<String> order, AtomicInteger n) {
        def clusterService = Stub(MorpheusServices.getMethod('getCluster').returnType) {
            list(_) >> clusters
            get(_) >> { Long id -> clusters.find { it.id == id } }
        }
        Map<Long, ComputeServer> hostOf = clusters.collectEntries { [(it.id): new ComputeServer(id: it.id, name: it.name)] }
        def servers = Stub(MorpheusServices.getMethod('getComputeServer').returnType) {
            list(_) >> { args ->
                def f = args[0].filters.find { it.name == 'serverGroup.id' }
                f ? [hostOf[f.value as Long]].findAll() : []
            }
        }
        def services = Stub(MorpheusServices) { getCluster() >> clusterService; getComputeServer() >> servers }
        Stub(MorpheusContext) {
            getServices() >> services
            getSettings(_) >> Single.just('{"checkConcurrency":"1"}')
            executeCommandOnServer(_, _) >> { args ->
                String name = (args[0] as ComputeServer).name
                Single.fromCallable {
                    order << name
                    if (n.incrementAndGet() == 1) {
                        release.await(30, TimeUnit.SECONDS)
                    }
                    new TaskResult(success: true, exitCode: '0', output: RUNNING)
                }
            }
        }
    }

    def 'a check that runs tells the watch what it read, and leaves out hosts in maintenance'() {
        given:
        long now = System.currentTimeMillis()
        MorpheusContext m = morpheus([CLUSTER], [])
        FreezeWatch.Schedule s = running(m, now)
        FreezeWatch.FROZEN_SINCE[27L] = now
        s.watches[27L].frozenHost = 'hvm-node02'

        when: 'the frozen host is the one the fixture has healthy'
        FreezeWatch.fromCheck(m, 27L, ['hvm-node02': facts('clear'), 'hvm-node03': facts('clear')], now + 60 * SEC)

        then:
        !FreezeWatch.FROZEN_SINCE.containsKey(27L)
    }

    def 'trouble in the watch never fails the check that called it'() {
        given:
        long now = System.currentTimeMillis()
        MorpheusContext m = morpheus([CLUSTER], [])
        FreezeWatch.Schedule s = running(m, now)

        when:
        FreezeWatch.fromCheck(m, 27L, null, now)

        then:
        noExceptionThrown()
        s != null
    }

    def 'stopping it stops reading, and a late answer changes nothing'() {
        given:
        long now = System.currentTimeMillis()
        CountDownLatch release = new CountDownLatch(1)
        onRead = { release.await(10, TimeUnit.SECONDS); new TaskResult(success: true, exitCode: '0', output: STOPPED) }
        MorpheusContext m = morpheus([CLUSTER], [new ComputeServer(id: 2L, name: 'hvm-b.invalid')])
        FreezeWatch.Schedule s = running(m, now)
        FreezeWatch.tick(m, s, now + 200 * SEC)
        new PollingConditions(timeout: 5).eventually { assert asked.size() == 1 }

        when:
        FreezeWatch.stop()
        release.countDown()
        Thread.sleep(200)

        then:
        s.watches[27L].pendingAt == null
        FreezeWatch.FROZEN_SINCE.isEmpty()
        FreezeWatch.summary(now) == [on: false]
    }
}
