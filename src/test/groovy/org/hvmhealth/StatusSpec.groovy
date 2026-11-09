package org.hvmhealth

import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.MorpheusServices
import com.morpheusdata.model.ComputeServerGroup
import com.morpheusdata.model.ComputeServerGroupType
import io.reactivex.rxjava3.core.Single
import org.hvmhealth.morpheus.FreezeWatch
import org.hvmhealth.morpheus.HealthMonitor
import org.hvmhealth.morpheus.StatusAnswer
import org.hvmhealth.morpheus.StatusServer
import spock.lang.Specification
import spock.util.concurrent.PollingConditions

/** The status URL: what it says, from the monitor's state, and then the same over a real socket. */
class StatusSpec extends Specification {
    static final long NOW = 1_800_000_000_000L
    static final long MIN = 60_000L
    /** Everything a body may hold. A test below would fail on anything else, a name above all. */
    static final String WORDS_ONLY = /\AHVM-HEALTH (ALIVE|OK|WARNING|CRITICAL|STALE|UNKNOWN)\n(clusters=\d+ critical=\d+ warning=\d+ stale=\d+ held=\d+ reloading=\d+\n|reloading\n)?\z/

    private static Map view(String overall, long at = NOW - MIN, Map extra = [:]) {
        [at: at, overall: overall, hosts: [[name: 'hvm-node01.invalid', frozen: false]], results: [], tenant: 'Initech'] + extra
    }

    private static Map state(Map<Long, Map> views, Map over = [:]) {
        [running: true, loadedAt: NOW - 60 * MIN, lastSweepAt: NOW - MIN, quickMs: 5 * MIN, behind: false, ids: views.keySet(), views: views] + over
    }

    def cleanup() {
        FreezeWatch.FROZEN_SINCE.clear()
        StatusServer.stop()
        StatusServer.error = null
        HealthMonitor.stop()
    }

    // ------------------------------------------------------------------ /alive

    def 'alive answers 200 while the sweep is recent and checks keep up'() {
        expect:
        StatusAnswer.answer('/alive', state([1L: view('HEALTHY')]), NOW) == [200, 'HVM-HEALTH ALIVE\n']
    }

    def 'alive answers STALE when #why'() {
        expect:
        StatusAnswer.answer('/alive', state([1L: view('HEALTHY')], over), NOW) == [503, 'HVM-HEALTH STALE\n']

        where:
        why                                     | over
        'the sweep has stopped'                 | [lastSweepAt: NOW - 6 * MIN]
        'checks are too far behind (PRB-04)'    | [behind: true]
        'automatic checks are off'              | [quickMs: 0L]
        'the monitor is not running'            | [running: false]
    }

    def 'whatever a cluster has wrong does not make the monitor itself stale'() {
        expect:
        StatusAnswer.answer('/alive', state([1L: view('CRITICAL')]), NOW)[0] == 200
    }

    def 'a plugin reload is not an outage: alive holds until the first sweep is really late, then says so'() {
        given:
        Map loading = state([:], [loadedAt: NOW - MIN, lastSweepAt: null, quickMs: 0L])

        expect:
        StatusAnswer.answer('/alive', loading, NOW)[0] == 200
        StatusAnswer.answer('/alive', loading, NOW + 5 * MIN)[0] == 503
    }

    // ------------------------------------------------------------------ /status

    def 'status says #word and #code for #why, with the counts'() {
        when:
        List r = StatusAnswer.answer('/status', state(views), NOW)

        then:
        r[0] == code
        (r[1] as String).startsWith("HVM-HEALTH ${word}\n")
        (r[1] as String).readLines()[1] == counts

        where:
        why                       | views                                                       | code | word       | counts
        'all healthy'             | [1L: view('HEALTHY'), 2L: view('HEALTHY')]                  | 200  | 'OK'       | 'clusters=2 critical=0 warning=0 stale=0 held=0 reloading=0'
        'a warning'               | [1L: view('HEALTHY'), 2L: view('WARNING')]                  | 200  | 'WARNING'  | 'clusters=2 critical=0 warning=1 stale=0 held=0 reloading=0'
        'a degraded cluster'      | [1L: view('HEALTHY'), 2L: view('DEGRADED')]                 | 200  | 'WARNING'  | 'clusters=2 critical=0 warning=1 stale=0 held=0 reloading=0'
        'a critical one'          | [1L: view('WARNING'), 2L: view('CRITICAL')]                 | 503  | 'CRITICAL' | 'clusters=2 critical=1 warning=1 stale=0 held=0 reloading=0'
        'one out of date'         | [1L: view('HEALTHY'), 2L: view('HEALTHY', NOW - 16 * MIN)]  | 200  | 'WARNING'  | 'clusters=2 critical=0 warning=0 stale=1 held=0 reloading=0'
        'every one out of date'   | [1L: view('HEALTHY', NOW - 16 * MIN)]                       | 503  | 'STALE'    | 'clusters=1 critical=0 warning=0 stale=1 held=0 reloading=0'
        'none monitored'          | [:]                                                         | 200  | 'OK'       | 'clusters=0 critical=0 warning=0 stale=0 held=0 reloading=0'
    }

    def 'a cluster with no evaluation is never counted as OK'() {
        given:
        Map s = state([1L: view('HEALTHY')], [ids: [1L, 2L]])

        when:
        List r = StatusAnswer.answer('/status', s, NOW)

        then:
        r[1] == 'HVM-HEALTH WARNING\nclusters=2 critical=0 warning=0 stale=1 held=0 reloading=0\n'
    }

    def 'before the first sweep nothing is known, so status does not say OK'() {
        expect:
        StatusAnswer.answer('/status', state([1L: view('HEALTHY')], [lastSweepAt: null]), NOW) == [503, 'HVM-HEALTH STALE\nclusters=0 critical=0 warning=0 stale=0 held=0 reloading=0\n']
    }

    def 'with the sweep stopped, or checks off, healthy clusters still cannot be vouched for'() {
        expect:
        StatusAnswer.answer('/status', state([1L: view('HEALTHY')], [lastSweepAt: NOW - 6 * MIN]), NOW)[1].startsWith('HVM-HEALTH STALE')
        StatusAnswer.answer('/status', state([1L: view('HEALTHY')], [quickMs: 0L]), NOW)[1].startsWith('HVM-HEALTH STALE')
    }

    def 'a critical cluster still pages when the sweep has stopped'() {
        expect:
        StatusAnswer.answer('/status', state([1L: view('CRITICAL')], [lastSweepAt: NOW - 6 * MIN]), NOW)[1].startsWith('HVM-HEALTH CRITICAL')
    }

    def 'a freeze the watch has seen is critical at once, ahead of the next check and even with no check yet'() {
        given:
        FreezeWatch.FROZEN_SINCE[2L] = NOW - 5000L

        expect:
        StatusAnswer.answer('/status', state([1L: view('HEALTHY'), 2L: view('HEALTHY')]), NOW)[1].startsWith('HVM-HEALTH CRITICAL\nclusters=2 critical=1')
        StatusAnswer.answer('/cluster/2', state([1L: view('HEALTHY')], [ids: [1L, 2L]]), NOW) == [503, 'HVM-HEALTH CRITICAL\n']
    }

    // ------------------------------------------------------------------ after a reload

    private static final List RELOADING = [200, 'HVM-HEALTH OK\nreloading\n']

    /** Loaded {@code minutes} ago, with its sweeps running and no check yet of any of these. */
    private static Map loaded(long minutes, Map<Long, Map> views = [7L: null], Map over = [:]) {
        state(views, [startedAt: NOW - minutes * MIN, loadedAt: NOW - minutes * MIN + 2 * MIN] + over)
    }

    def 'a cluster not yet checked since the load reads as reloading for 15 minutes, and after that STALE as before'() {
        expect:
        StatusAnswer.answer('/cluster/7', loaded(minutes), NOW) == want

        where:
        minutes | want
        3L      | RELOADING
        14L     | RELOADING
        15L     | [503, 'HVM-HEALTH STALE\n']
        16L     | [503, 'HVM-HEALTH STALE\n']
    }

    def 'what a Web Check matches on is there, on its own first line'() {
        when:
        List<String> lines = (StatusAnswer.answer('/cluster/7', loaded(3L), NOW)[1] as String).readLines()

        then:
        lines == ['HVM-HEALTH OK', 'reloading']
    }

    def 'reloading is no shelter from a freeze the watch holds, with no check yet or just before the first sweep'() {
        given:
        FreezeWatch.FROZEN_SINCE[7L] = NOW - 5000L

        expect:
        StatusAnswer.answer('/cluster/7', loaded(3L), NOW) == [503, 'HVM-HEALTH CRITICAL\n']
        StatusAnswer.answer('/cluster/7', loaded(1L, [7L: null], [lastSweepAt: null]), NOW) == [503, 'HVM-HEALTH CRITICAL\n']
        StatusAnswer.answer('/status', loaded(3L), NOW) == [503, 'HVM-HEALTH CRITICAL\nclusters=1 critical=1 warning=0 stale=0 held=0 reloading=0\n']
    }

    def 'once checked since the load a cluster says what it found, however early'() {
        expect:
        StatusAnswer.answer('/cluster/7', loaded(3L, [7L: view(overall)]), NOW) == want

        where:
        overall    | want
        'HEALTHY'  | [200, 'HVM-HEALTH OK\n']
        'WARNING'  | [200, 'HVM-HEALTH WARNING\n']
        'CRITICAL' | [503, 'HVM-HEALTH CRITICAL\n']
    }

    def 'an evaluation from before the load does not count as checked, but what it knew to be wrong still does'() {
        expect:
        StatusAnswer.answer('/cluster/7', loaded(3L, [7L: view('HEALTHY', NOW - 4 * MIN)]), NOW) == RELOADING
        StatusAnswer.answer('/cluster/7', loaded(3L, [7L: view('CRITICAL', NOW - 4 * MIN)]), NOW) == [503, 'HVM-HEALTH CRITICAL\n']
    }

    def 'in the counts a reloading cluster is apart from the rest, like one held, so it raises nothing'() {
        expect:
        StatusAnswer.answer('/status', loaded(3L, views), NOW) == [code, body]

        where:
        views                                  | code | body
        [7L: null, 8L: null]                   | 200  | 'HVM-HEALTH OK\nclusters=2 critical=0 warning=0 stale=0 held=0 reloading=2\n'
        [7L: null, 8L: view('HEALTHY')]        | 200  | 'HVM-HEALTH OK\nclusters=2 critical=0 warning=0 stale=0 held=0 reloading=1\n'
        [7L: null, 8L: view('WARNING')]        | 200  | 'HVM-HEALTH WARNING\nclusters=2 critical=0 warning=1 stale=0 held=0 reloading=1\n'
        [7L: null, 8L: view('CRITICAL')]       | 503  | 'HVM-HEALTH CRITICAL\nclusters=2 critical=1 warning=0 stale=0 held=0 reloading=1\n'
    }

    def 'past the grace the same clusters count as stale'() {
        expect:
        StatusAnswer.answer('/status', loaded(15L, [7L: null, 8L: null]), NOW) == [503, 'HVM-HEALTH STALE\nclusters=2 critical=0 warning=0 stale=2 held=0 reloading=0\n']
    }

    def 'before the first sweep, within the grace, a reload still reads as one, as a Web Check is polling all through it'() {
        given:
        Map s = state([:], [startedAt: NOW - MIN, loadedAt: NOW + MIN, lastSweepAt: null, quickMs: 0L])

        expect:
        StatusAnswer.answer('/cluster/7', s, NOW) == RELOADING
        StatusAnswer.answer('/status', s, NOW) == [200, 'HVM-HEALTH OK\nclusters=0 critical=0 warning=0 stale=0 held=0 reloading=0\n']
    }

    def 'nothing reads as reloading when #why, as nothing is on its way to end it'() {
        expect:
        StatusAnswer.answer('/cluster/7', loaded(3L, [7L: null], over), NOW) == [503, 'HVM-HEALTH STALE\n']
        (StatusAnswer.answer('/status', loaded(3L, [7L: null], over), NOW)[1] as String).startsWith('HVM-HEALTH STALE\n')

        where:
        why                       | over
        'the sweep has stopped'   | [startedAt: NOW - 14 * MIN, loadedAt: NOW - 12 * MIN, lastSweepAt: NOW - 6 * MIN]
        'the first has not come'  | [startedAt: NOW - 8 * MIN, loadedAt: NOW - 6 * MIN, lastSweepAt: null]
        'automatic checks are off' | [quickMs: 0L]
        'the monitor is stopped'  | [running: false]
    }

    def 'an id it does not monitor is unknown during the grace too, once a sweep has said what is monitored'() {
        expect:
        StatusAnswer.answer('/cluster/8', loaded(3L), NOW) == [404, 'HVM-HEALTH UNKNOWN\n']
    }

    def 'alive is relaxed no further during the grace: #why'() {
        expect:
        StatusAnswer.answer('/alive', loaded(3L, [7L: null], over), NOW) == [503, 'HVM-HEALTH STALE\n']

        where:
        why                     | over
        'the first sweep late'  | [startedAt: NOW - 8 * MIN, loadedAt: NOW - 6 * MIN, lastSweepAt: null]
        'checks too far behind' | [behind: true]
        'checks off'            | [quickMs: 0L]
    }

    def 'through the monitor, over the socket: a load with no check yet says reloading, and a real check ends it'() {
        given:
        int port = freePort()
        ComputeServerGroup c = new ComputeServerGroup(id: 27L, name: 'cluster-a', type: new ComputeServerGroupType(code: 'mvm-cluster'))
        MorpheusContext m = morpheus("{\"statusPort\":\"${port}\",\"monitorMinutes\":\"5\"}".toString(), [c])
        HealthMonitor.LAST.remove(27L)
        HealthMonitor.start(m, new ClusterHealthPlugin())
        HealthMonitor.sweep(m, new ClusterHealthPlugin(), HealthMonitor.@loadedAt - 60_000L)

        when:
        List<List> first = ['/cluster/27', '/status'].collect { fetch(port, it) }

        then:
        first[0][0..1] == RELOADING
        first[1][0..1] == [200, 'HVM-HEALTH OK\nclusters=1 critical=0 warning=0 stale=0 held=0 reloading=1\n']
        first.every { (it[1] as String) ==~ WORDS_ONLY }

        when:
        HealthMonitor.LAST[27L] = [at: System.currentTimeMillis(), overall: 'WARNING', hosts: [], results: []]

        then:
        fetch(port, '/cluster/27')[0..1] == [200, 'HVM-HEALTH WARNING\n']

        cleanup:
        HealthMonitor.LAST.remove(27L)
    }

    // ------------------------------------------------------------------ /cluster/<id>

    def 'a cluster answers #want for #why'() {
        expect:
        StatusAnswer.answer('/cluster/7', state([7L: v]), NOW) == want

        where:
        why            | v                                                                                  | want
        'healthy'      | view('HEALTHY')                                                                    | [200, 'HVM-HEALTH OK\n']
        'warning'      | view('WARNING')                                                                    | [200, 'HVM-HEALTH WARNING\n']
        'degraded'     | view('DEGRADED')                                                                   | [200, 'HVM-HEALTH WARNING\n']
        'critical'     | view('CRITICAL')                                                                   | [503, 'HVM-HEALTH CRITICAL\n']
        'out of date'  | view('HEALTHY', NOW - 16 * MIN)                                                    | [503, 'HVM-HEALTH STALE\n']
        'not checked'  | null                                                                               | [503, 'HVM-HEALTH STALE\n']
        'frozen host'  | view('WARNING', NOW - MIN, [hosts: [[frozen: true]]])                              | [503, 'HVM-HEALTH CRITICAL\n']
        'stale critical' | view('CRITICAL', NOW - 60 * MIN)                                                 | [503, 'HVM-HEALTH CRITICAL\n']
    }

    def 'an id it does not monitor is unknown, and that is all it says'() {
        expect:
        StatusAnswer.answer('/cluster/8', state([7L: view('CRITICAL')]), NOW) == [404, 'HVM-HEALTH UNKNOWN\n']
    }

    def 'before the first sweep an id cannot be called unknown either'() {
        expect:
        StatusAnswer.answer('/cluster/7', state([7L: view('HEALTHY')], [lastSweepAt: null]), NOW) == [503, 'HVM-HEALTH STALE\n']
    }

    def 'there is no route for #path, and nothing from the request comes back'() {
        when:
        List r = StatusAnswer.answer(path, state([7L: view('CRITICAL')]), NOW)

        then:
        r == [404, 'HVM-HEALTH UNKNOWN\n']

        where:
        path << ['/', '', null, '/clusters', '/cluster', '/cluster/', '/cluster/7/', '/cluster/x', '/cluster/-7', '/cluster/7 Initech', '/alive/', '/status/x', '/cluster/99999999999999999999999', '/Initech']
    }

    def 'no answer ever holds a host, cluster, tenant or finding, whatever the state holds'() {
        given:
        Map full = view('CRITICAL', NOW - MIN, [name: 'cluster-acme', owner: 'Acme', results: [[id: 'DLM-01', status: 'FAIL', title: 'Initech is frozen', host: 'hvm-node01.invalid', observed: 'vm-initech']]])
        Map s = state([7L: full, 8L: view('HEALTHY', NOW - 60 * MIN, [name: 'Initech']), 9L: null])

        when:
        List<List> all = ['/alive', '/status', '/cluster/7', '/cluster/8', '/cluster/9', '/cluster/10', '/x'].collect { StatusAnswer.answer(it, s, NOW) }

        then:
        all.every { (it[1] as String) ==~ WORDS_ONLY }
        all.every { !(it[1] as String).toLowerCase().find(/initech|acme|node|dlm|vm-initech/) }
    }

    def 'the words a monitor matches on: only a healthy answer contains the OK a Web Check looks for'() {
        expect:
        !StatusAnswer.WARNING.contains('HVM-HEALTH OK')
        !StatusAnswer.CRITICAL.contains('HVM-HEALTH OK')
        !StatusAnswer.STALE.contains('HVM-HEALTH OK')
        !StatusAnswer.UNKNOWN.contains('HVM-HEALTH OK')
    }

    // ------------------------------------------------------------------ the socket

    private static int freePort() {
        ServerSocket s = new ServerSocket(0)
        int p = s.localPort
        s.close()
        p
    }

    private static List fetch(int port, String path, String method = 'GET') {
        HttpURLConnection c = new URL("http://127.0.0.1:${port}${path}").openConnection() as HttpURLConnection
        c.requestMethod = method
        c.connectTimeout = 3000
        c.readTimeout = 3000
        int code = c.responseCode
        String type = c.contentType
        InputStream in_ = code < 400 ? c.inputStream : c.errorStream
        [code, in_ ? in_.getText('UTF-8') : '', type]
    }

    private static final Closure<List> ANSWER = { String p -> [200, "HVM-HEALTH OK\n"] }

    def 'GET and HEAD answer as plain text, and anything else is refused without calling the monitor'() {
        given:
        int port = freePort()
        List<String> asked = []
        StatusServer.start(port) { String p -> asked << p; [503, 'HVM-HEALTH CRITICAL\n'] }

        when:
        List get = fetch(port, '/status?x=1')
        List head = fetch(port, '/status', 'HEAD')
        List post = fetch(port, '/status', 'POST')

        then:
        get == [503, 'HVM-HEALTH CRITICAL\n', 'text/plain; charset=utf-8']
        head[0] == 503
        head[1] == ''
        asked == ['/status', '/status']
        post[0] == 405
        (post[1] as String) ==~ WORDS_ONLY
    }

    def 'an answer that fails says STALE and keeps its reason in the log'() {
        given:
        int port = freePort()
        StatusServer.start(port) { String p -> throw new IllegalStateException('tenant Initech broke') }

        expect:
        fetch(port, '/status')[0..1] == [503, 'HVM-HEALTH STALE\n']
    }

    def 'a port already in use is shown, does not throw, and is tried again until it works'() {
        given:
        ServerSocket taken = new ServerSocket(0)
        int port = taken.localPort

        when:
        StatusServer.ensure(port, ANSWER)

        then:
        noExceptionThrown()
        StatusServer.listening() == 0
        StatusServer.error.contains("port ${port}")

        when: 'whoever had it lets go, and the next sweep comes round'
        taken.close()
        StatusServer.ensure(port, ANSWER)

        then:
        StatusServer.listening() == port
        StatusServer.error == null
        fetch(port, '/alive')[0] == 200

        cleanup:
        taken?.close()
    }

    def 'changing the port moves it, and the old one is let go'() {
        given:
        int one = freePort()
        int two = freePort()
        StatusServer.ensure(one, ANSWER)

        when:
        StatusServer.ensure(two, ANSWER)

        then:
        StatusServer.listening() == two
        fetch(two, '/alive')[0] == 200

        when:
        fetch(one, '/alive')

        then:
        thrown(ConnectException)
    }

    def 'the same port is not restarted on every sweep'() {
        given:
        int port = freePort()
        int calls = 0
        StatusServer.ensure(port) { String p -> calls++; [200, "first\n"] }

        when:
        StatusServer.ensure(port) { String p -> [200, "second\n"] }

        then:
        fetch(port, '/alive')[1] == "first\n"
        calls == 1
    }

    def 'emptying the setting turns it off, and a value that is not a port says so'() {
        given:
        int port = freePort()
        StatusServer.ensure(port, ANSWER)

        when:
        StatusServer.ensure(0, ANSWER)

        then:
        StatusServer.listening() == 0
        StatusServer.error == null

        when:
        StatusServer.ensure(-1, ANSWER)

        then:
        StatusServer.listening() == 0
        StatusServer.error.contains('not a port')
    }

    def 'the setting: #value reads as #want'() {
        expect:
        HealthMonitor.statusPort(value == null ? [:] : [statusPort: value]) == want

        where:
        value    | want
        null     | 0
        ''       | 0
        '  '     | 0
        '18443'  | 18443
        18443    | 18443
        ' 8080 ' | 8080
        '0'      | -1
        '65536'  | -1
        '-1'     | -1
        'http'   | -1
    }

    def 'a client that never finishes its request cannot hold the status URL'() {
        given:
        StatusServer.requestLimitMs = 300L
        int port = freePort()
        StatusServer.start(port, ANSWER)
        List<Socket> slow = (1..4).collect {
            Socket s = new Socket('127.0.0.1', port)
            s.outputStream.write('GET /sta'.bytes)
            s.outputStream.flush()
            s
        }

        expect: 'more than the two threads it has were held, and it answers anyway, once the limit has passed'
        new PollingConditions(timeout: 5, delay: 0.2).eventually { assert fetch(port, '/alive')[0] == 200 }

        cleanup:
        slow*.close()
        StatusServer.requestLimitMs = 5000L
    }

    /** What a socket got: 'answered' (a byte came), 'closed' (end of stream or a reset) or 'hung' (neither, by the deadline). */
    private static String outcome(Socket s, long deadline) {
        try {
            s.soTimeout = Math.max(1L, deadline - System.currentTimeMillis()) as int
            s.inputStream.read() == -1 ? 'closed' : 'answered'
        } catch (SocketTimeoutException ignored) {
            'hung'
        } catch (IOException ignored) {
            'closed'
        }
    }

    def 'with both threads held and the queue full, every request is answered or closed, and none is left open and unanswered'() {
        given: 'each answer takes a while, so requests wait in the queue for longer than the limit'
        StatusServer.requestLimitMs = 300L
        int port = freePort()
        StatusServer.start(port) { String p -> Thread.sleep(100); [200, "HVM-HEALTH OK\n"] }
        List<Socket> stalled = (1..2).collect {
            Socket s = new Socket('127.0.0.1', port)
            s.outputStream.write('GET /sta'.bytes)
            s.outputStream.flush()
            s
        }
        Thread.sleep(100)   // both threads have them
        List<Socket> rest = []

        when: 'a third request, and enough more to fill the queue and go past it'
        (1..StatusServer.QUEUE + 8).each {
            Socket s = new Socket('127.0.0.1', port)
            rest << s
            s.outputStream.write('GET /alive HTTP/1.1\r\nHost: status.invalid\r\nConnection: close\r\n\r\n'.bytes)
            s.outputStream.flush()
        }
        long deadline = System.currentTimeMillis() + 8000L
        List<String> got = (stalled + rest).collect { outcome(it, deadline) }

        then: 'the stalled two are cut off, all that waited are answered, the rest are closed at once, and the port still answers'
        !got.contains('hung')
        got[0..1] == ['closed', 'closed']
        got.count('answered') >= StatusServer.QUEUE
        fetch(port, '/alive')[0] == 200

        cleanup:
        (stalled + rest)*.close()
        StatusServer.requestLimitMs = 5000L
    }

    def 'stopping lets the port go, so a reloaded plugin can have it'() {
        given:
        int port = freePort()
        StatusServer.start(port, ANSWER)

        when:
        StatusServer.stop()
        ServerSocket again = new ServerSocket(port)

        then:
        noExceptionThrown()

        cleanup:
        again?.close()
    }

    private MorpheusContext morpheus(String settings, List<ComputeServerGroup> clusters = []) {
        def clusterService = Stub(MorpheusServices.getMethod('getCluster').returnType) { list(_) >> clusters }
        def services = Stub(MorpheusServices) { getCluster() >> clusterService }
        Stub(MorpheusContext) { getServices() >> services; getSettings(_) >> Single.just(settings) }
    }

    def 'the sweep starts it from the setting, moves it when that changes, and stopping the monitor stops it'() {
        given:
        int one = freePort()
        int two = freePort()
        MorpheusContext first = morpheus("{\"statusPort\":\"${one}\"}".toString())
        HealthMonitor.start(first, new ClusterHealthPlugin())

        when:
        HealthMonitor.sweep(first, new ClusterHealthPlugin())

        then:
        StatusServer.listening() == one
        fetch(one, '/alive') [0] == 200

        when:
        MorpheusContext second = morpheus("{\"statusPort\":\"${two}\"}".toString())
        HealthMonitor.sweep(second, new ClusterHealthPlugin())

        then:
        StatusServer.listening() == two

        when:
        HealthMonitor.stop()

        then:
        StatusServer.listening() == 0
    }

    def 'unloading the plugin stops it'() {
        given:
        int port = freePort()
        StatusServer.start(port, ANSWER)

        when:
        new ClusterHealthPlugin().onDestroy()

        then:
        StatusServer.listening() == 0
    }

    def 'through the monitor, over the socket, a tenant name on a cluster never comes out'() {
        given:
        int port = freePort()
        ComputeServerGroup c = new ComputeServerGroup(id: 27L, name: 'cluster-initech', type: new ComputeServerGroupType(code: 'mvm-cluster'))
        MorpheusContext m = morpheus("{\"statusPort\":\"${port}\",\"monitorMinutes\":\"5\"}".toString(), [c])
        HealthMonitor.start(m, new ClusterHealthPlugin())
        HealthMonitor.LAST[27L] = [at: System.currentTimeMillis(), overall: 'CRITICAL', hosts: [[name: 'hvm-node01.invalid', frozen: true]], results: [[id: 'DLM-01', status: 'FAIL', title: 'cluster-initech']]]
        HealthMonitor.sweep(m, new ClusterHealthPlugin())

        when:
        List<List> all = ['/alive', '/status', '/cluster/27', '/cluster/28'].collect { fetch(port, it) }

        then:
        all*.getAt(0) == [200, 503, 503, 404]
        all.every { (it[1] as String) ==~ WORDS_ONLY && !(it[1] as String).contains('initech') }

        cleanup:
        HealthMonitor.LAST.remove(27L)
    }
}
