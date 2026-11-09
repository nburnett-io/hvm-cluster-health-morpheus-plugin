package org.hvmhealth

import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.MorpheusServices
import com.morpheusdata.model.ComputeServerGroup
import com.morpheusdata.model.ComputeServerGroupType
import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import io.reactivex.rxjava3.core.Single
import org.hvmhealth.checks.CheckCatalog
import org.hvmhealth.morpheus.ChangeWindows
import org.hvmhealth.morpheus.DetailAnswer
import org.hvmhealth.morpheus.FreezeWatch
import org.hvmhealth.morpheus.HealthMonitor
import org.hvmhealth.morpheus.Lease
import org.hvmhealth.morpheus.ReaderKeys
import org.hvmhealth.morpheus.StatusAnswer
import org.hvmhealth.morpheus.StatusServer
import spock.lang.Shared
import spock.lang.Specification

import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.PublicKey
import java.security.Signature
import java.text.SimpleDateFormat

/**
 * The signed read, /v1/clusters: who may read it, what a reader gets, and that anything not signed right gets what a path that does not exist gets.
 * Keys are made here, or are the RFC 8032 test vector, which the reader's own suite pins too.
 */
class ReaderSpec extends Specification {
    static final long NOW = 1_800_000_000_000L
    static final long MIN = 60_000L
    static final String UNKNOWN = 'HVM-HEALTH UNKNOWN\n'
    /** RFC 8032 section 7.1, test 1: public test data, not a credential. The signature is over the signed bytes of a GET of /v1/clusters at 1800000000. */
    static final String VECTOR_KEY = 'MCowBQYDK2VwAyEA11qYAYKxCrfVS/7TyWQHOg7hcvPapiMlrwIaaPcHURo='
    static final String VECTOR_SIGNATURE = 'hXh1A0NwK+/vHhRv5S/IjReaSLS3A9Nzor2m69cnQv/AdU1WOLXxTTR9c6aRD1FoX56ibJdnKUxRLo4Qs1GDCg=='

    @Shared KeyPair reader = pair('Ed25519')
    @Shared KeyPair stranger = pair('Ed25519')

    def setup() {
        HealthMonitor.@lastRefusal = null
        HealthMonitor.@refusalLoggedAt = 0L
    }

    def cleanup() {
        HealthMonitor.stop()
        StatusServer.stop()
        StatusServer.error = null
        HealthMonitor.@standby = false
        HealthMonitor.@readerKeys = []
        HealthMonitor.@readerKeysBad = 0
        HealthMonitor.@lastRefusal = null
        HealthMonitor.LAST.remove(27L)
        Lease.forget()
        Lease.node = null
        FreezeWatch.FROZEN_SINCE.clear()
        ChangeWindows.stop()
    }

    // ------------------------------------------------------------------ helpers

    static KeyPair pair(String algorithm) {
        KeyPairGenerator.getInstance(algorithm).generateKeyPair()
    }

    static String text(PublicKey k) {
        Base64.encoder.encodeToString(k.encoded)
    }

    /** The signed bytes as the wire format gives them, written out here rather than taken from the code under test. */
    static String wire(String method, String path, String time) {
        "hvm-health-v1\n${method}\n${path}\n${time}".toString()
    }

    static String sign(PrivateKey key, String text) {
        Signature s = Signature.getInstance('Ed25519')
        s.initSign(key)
        s.update(text.getBytes('UTF-8'))
        Base64.encoder.encodeToString(s.sign())
    }

    /** Now, in whole seconds, as a reader sends it. */
    static String seconds() {
        Math.floorDiv(System.currentTimeMillis(), 1000L).toString()
    }

    /** At least {@code s} seconds from this node's clock, whenever in its second the request lands: rounded down behind, and up ahead. */
    static String behind(long s) {
        (Math.floorDiv(System.currentTimeMillis(), 1000L) - s).toString()
    }

    static String ahead(long s) {
        (Math.floorDiv(System.currentTimeMillis() + 999L, 1000L) + s).toString()
    }

    static Map<String, String> signed(PrivateKey key, String time = seconds(), String method = 'GET', String path = ReaderKeys.PATH) {
        [(ReaderKeys.TIME): time, (ReaderKeys.SIGNATURE): sign(key, wire(method, path, time))]
    }

    private static int freePort() {
        ServerSocket s = new ServerSocket(0)
        int p = s.localPort
        s.close()
        p
    }

    /** [code, body, content type, Date, Cache-Control]. */
    private static List fetch(int port, String path, Map<String, String> headers = [:], String method = 'GET') {
        HttpURLConnection c = new URL("http://127.0.0.1:${port}${path}").openConnection() as HttpURLConnection
        c.requestMethod = method
        c.connectTimeout = 3000
        c.readTimeout = 3000
        headers.each { k, v -> c.setRequestProperty(k, v) }
        int code = c.responseCode
        InputStream in_ = code < 400 ? c.inputStream : c.errorStream
        [code, in_ ? in_.getText('UTF-8') : '', c.contentType, c.getHeaderField('Date'), c.getHeaderField('Cache-Control')]
    }

    private static Map json(List r) {
        assert r[0] == 200
        new JsonSlurper().parseText(r[1] as String) as Map
    }

    private MorpheusContext morpheus(String settings, List<ComputeServerGroup> clusters = []) {
        def clusterService = Stub(MorpheusServices.getMethod('getCluster').returnType) { list(_) >> clusters }
        def services = Stub(MorpheusServices) { getCluster() >> clusterService }
        Stub(MorpheusContext) { getServices() >> services; getSettings(_) >> Single.just(settings) }
    }

    /** The monitor as it runs, its status URL on a free port with these reader keys, after one sweep. {@code before} runs between the load and the sweep. */
    private int monitor(String keys, List<ComputeServerGroup> clusters = [], Closure before = {}) {
        int port = freePort()
        MorpheusContext m = morpheus(JsonOutput.toJson([statusPort: port.toString(), monitorMinutes: '5', readerKeys: keys]), clusters)
        HealthMonitor.start(m, new ClusterHealthPlugin())
        before.call()
        HealthMonitor.sweep(m, new ClusterHealthPlugin())
        port
    }

    private static Map result(String id, String status, String host) {
        [id: id, title: "${id} as found".toString(), severity: 'MEDIUM', status: status, host: host, observed: 'as read', expected: 'as set', source: 'plugin', fix: null, tier: 'NONE']
    }

    /** One evaluation, as HealthContext.view shapes it: counts are rows, so a check on two hosts counts twice and is listed once. */
    private static Map view(long at, Long fullAt) {
        [at: at, mode: 'quick', fullAt: fullAt, overall: 'WARNING', layout: 'HVM 2.0', hosts: [[name: 'hvm-node01.invalid', frozen: false]],
         readiness: [[area: 'High availability', status: 'PASS', fail: 0, warn: 0, ids: [], notChecked: []],
                     [area: 'Live migration', status: 'WARN', fail: 0, warn: 2, ids: ['TIM-02'], notChecked: []],
                     [area: 'Storage migration', status: 'NA', fail: 0, warn: 0, ids: [], notChecked: []]],
         counts: [fail: 0, warn: 2, pass: 3],
         results: [result('TIM-02', 'WARN', 'hvm-node01.invalid'), result('TIM-02', 'WARN', 'hvm-node02.invalid'), result('HW-08', 'NOT_CHECKED', 'hvm-node01.invalid'),
                   result('CLU-01', 'PASS', null), result('CLU-02', 'PASS', 'hvm-node01.invalid'), result('CLU-02', 'PASS', 'hvm-node02.invalid'), result('HA-04', 'NA', null)]]
    }

    private static Map state(Map<Long, Map> views, Map over = [:]) {
        [running: true, loadedAt: NOW - 60 * MIN, lastSweepAt: NOW - MIN, quickMs: 5 * MIN, behind: false, ids: views.keySet(), views: views] + over
    }

    // ------------------------------------------------------------------ what a reader gets

    def 'a signed read gets every monitored cluster by id, with its state, check ids by status, readiness and times'() {
        given:
        ComputeServerGroup c = new ComputeServerGroup(id: 27L, name: 'cluster-a', type: new ComputeServerGroupType(code: 'mvm-cluster'))
        long at = 0L
        int port = monitor("dashboard-a ${text(reader.public)}", [c]) {
            at = System.currentTimeMillis()
            HealthMonitor.LAST[27L] = view(at, at - 60 * MIN)
        }

        when:
        List r = fetch(port, ReaderKeys.PATH, signed(reader.private))
        Map body = json(r)
        Map one = (body.clusters as List<Map>)[0]

        then:
        r[2] == 'application/json; charset=utf-8'
        r[4] == 'no-store'
        body.version == 1
        body.standingBy == false
        body.alive == true
        body.sweptAt instanceof Number
        body.quickMinutes == 5
        (body.clusters as List).size() == 1
        one.id == 27
        one.state == 'WARNING'
        one.held == false
        one.checkedAt == at
        one.mode == 'quick'
        one.fullAt == at - 60 * MIN
        one.overall == 'WARNING'

        and: 'counts are result rows and checks are ids, and neither has what does not apply (NA), which readiness keeps'
        one.counts == [fail: 0, warn: 2, pass: 3, notChecked: 1]
        one.checks == [FAIL: [], WARN: ['TIM-02'], NOT_CHECKED: ['HW-08'], PASS: ['CLU-01', 'CLU-02']]
        one.readiness == [[area: 'High availability', status: 'PASS'], [area: 'Live migration', status: 'WARN'], [area: 'Storage migration', status: 'NA']]
        body.names == ['CLU-01', 'CLU-02', 'HW-08', 'TIM-02'].collectEntries { [(it): CheckCatalog.ENTRIES[it].name] }
        HealthMonitor.@lastRefusal == null

        and: 'only a GET reads it: a HEAD gets what a HEAD of a path that does not exist gets, and a POST is refused as before'
        fetch(port, ReaderKeys.PATH, signed(reader.private), 'HEAD')[0..2] == fetch(port, '/x', [:], 'HEAD')[0..2]
        fetch(port, ReaderKeys.PATH, signed(reader.private, seconds(), 'HEAD'), 'HEAD')[0] == 404
        fetch(port, ReaderKeys.PATH, signed(reader.private), 'POST')[0..2] == fetch(port, '/x', [:], 'POST')[0..2]
        fetch(port, ReaderKeys.PATH, signed(reader.private), 'POST')[0] == 405
    }

    def 'nothing in a signed answer names a host, cluster, tenant, VM or finding, whatever the state holds'() {
        given:
        Map full = [at: NOW - MIN, mode: 'full', fullAt: NOW - MIN, overall: 'CRITICAL', name: 'cluster-acme', owner: 'Acme', tenant: 'Initech', layout: 'HVM 2.0',
                    hosts: [[name: 'hvm-node01.invalid', frozen: false]],
                    readiness: [[area: 'High availability', status: 'FAIL', fail: 1, warn: 0, ids: ['DLM-01'], notChecked: []]],
                    results: [[id: 'DLM-01', status: 'FAIL', title: 'Initech is frozen', host: 'hvm-node01.invalid', observed: 'vm-initech', expected: 'acme expected',
                               impact: 'Initech: 3 VMs, vm-initech among them', hint: 'ask Acme', fix: 'acme-fix', source: 'plugin']],
                    busiest: [[host: 'hvm-node01.invalid', vm: 'vm-initech', tenant: 'Initech']], historyError: 'acme history could not be read']
        Map s = state([7L: full, 8L: [at: NOW - 60 * MIN, overall: 'HEALTHY', name: 'Initech', hosts: [], results: []], 9L: null])
        ChangeWindows.@open = [7L: [clusterId: 7L, startedAt: NOW - MIN, endsAt: NOW + 60 * MIN, by: 'jdoe.initech', note: 'swapping the acme uplink']]
        Lease.node = 'node-initech.invalid'

        when:
        Map answer = DetailAnswer.answer(s, NOW)
        String json = JsonOutput.toJson(answer).toLowerCase()

        then:
        !json.find(/initech|acme|hvm-node|jdoe|uplink|frozen/)

        and: 'it is not empty: the ids, words and counts are there'
        answer.clusters*.id == [7L, 8L, 9L]
        answer.clusters*.state == ['CRITICAL', 'STALE', 'STALE']
        (answer.clusters[0] as Map).held == true
        (answer.clusters[0] as Map).checks == [FAIL: ['DLM-01'], WARN: [], NOT_CHECKED: [], PASS: []]
        answer.names == ['DLM-01': CheckCatalog.ENTRIES['DLM-01'].name]
    }

    // ------------------------------------------------------------------ what everyone else gets

    def 'a read without a good signature is answered as a path that does not exist: #why'() {
        given:
        int port = monitor(keys ? "dashboard-a ${text(reader.public)}" : '')

        when:
        List got = fetch(port, ReaderKeys.PATH, headers.call() as Map<String, String>)
        List none = fetch(port, '/x')

        then:
        got[0] == 404
        got[1] == UNKNOWN
        got[2] == 'text/plain; charset=utf-8'
        [got[0], got[1], got[2], got[4]] == [none[0], none[1], none[2], none[4]]
        (HealthMonitor.@lastRefusal as Map).why ==~ reason

        where:
        why                          | keys  | headers                                                                                                   | reason
        'no headers'                 | true  | { -> [:] }                                                                                                | /the request carries no signature/
        'no keys set'                | false | { -> signed(reader.private) }                                                                             | /no reader keys are set/
        'a key not in the setting'   | true  | { -> signed(stranger.private) }                                                                           | /the signature matches none of the reader keys/
        'a time 301 s behind'        | true  | { -> signed(reader.private, behind(301)) }                                                                | /its time is 30\d s from this node's clock/
        'a time 301 s ahead'         | true  | { -> signed(reader.private, ahead(301)) }                                                                 | /its time is 30\d s from this node's clock/
        'a signature over /v1/other' | true  | { -> signed(reader.private, seconds(), 'GET', '/v1/other') }                                              | /the signature matches none of the reader keys/
        'a signature over HEAD'      | true  | { -> signed(reader.private, seconds(), 'HEAD') }                                                          | /the signature matches none of the reader keys/
        'base64 junk'                | true  | { -> [(ReaderKeys.TIME): seconds(), (ReaderKeys.SIGNATURE): 'not base64 at all!'] }                       | /the request carries no signature/
        'a 63-byte signature'        | true  | { -> Map h = signed(reader.private); h[ReaderKeys.SIGNATURE] = cut(h[ReaderKeys.SIGNATURE], 63); h }      | /the request carries no signature/
        'a time with letters'        | true  | { -> String t = seconds() + 'x'; [(ReaderKeys.TIME): t, (ReaderKeys.SIGNATURE): sign(reader.private, wire('GET', ReaderKeys.PATH, t))] } | /the request carries no signature/
        'a 13-digit time, in ms'     | true  | { -> String t = System.currentTimeMillis().toString(); [(ReaderKeys.TIME): t, (ReaderKeys.SIGNATURE): sign(reader.private, wire('GET', ReaderKeys.PATH, t))] } | /the request carries no signature/
        'a time of -1'               | true  | { -> String t = '-1'; [(ReaderKeys.TIME): t, (ReaderKeys.SIGNATURE): sign(reader.private, wire('GET', ReaderKeys.PATH, t))] } | /the request carries no signature/
    }

    def 'a refused read is logged at most once a minute, and a minute to the millisecond is enough'() {
        when: 'refused at once, 30 s on, 61 s on, and exactly a minute after that'
        List<Long> loggedAt = [0L, 30_000L, 61_000L, 121_000L].collect { long d ->
            assert HealthMonitor.readerAnswer(null, null, NOW + d)[0] == 404
            HealthMonitor.@refusalLoggedAt as Long
        }

        then:
        loggedAt == [NOW, NOW, NOW + 61_000L, NOW + 121_000L]
        (HealthMonitor.@lastRefusal as Map).at == NOW + 121_000L
    }

    private static String cut(String signature, int bytes) {
        Base64.encoder.encodeToString(Arrays.copyOf(Base64.decoder.decode(signature), bytes))
    }

    def 'the time may be up to 5 minutes from this node clock, either way, and no further: #offset ms'() {
        given:
        List<PublicKey> keys = ReaderKeys.parse(VECTOR_KEY).keys as List<PublicKey>

        expect:
        ReaderKeys.refusal(keys, 'GET', ReaderKeys.PATH, '1800000000', VECTOR_SIGNATURE, NOW + offset) == want

        where:
        offset    | want
        -300_000L | null
        300_000L  | null
        -301_000L | "its time is 301 s from this node's clock"
        301_000L  | "its time is 301 s from this node's clock"
    }

    def 'the vector shared with the reader verifies'() {
        given:
        List<PublicKey> keys = ReaderKeys.parse(VECTOR_KEY).keys as List<PublicKey>

        expect:
        ReaderKeys.refusal(keys, 'GET', '/v1/clusters', '1800000000', VECTOR_SIGNATURE, 1_800_000_000_000L) == null
        ReaderKeys.message('GET', '/v1/clusters', '1800000000') == 'hvm-health-v1\nGET\n/v1/clusters\n1800000000'
    }

    // ------------------------------------------------------------------ the setting

    def 'Reader keys: one per line, a label allowed before the key, anything else counted and never used: #why'() {
        expect:
        Map p = ReaderKeys.parse(setting as String)
        (p.keys as List).size() == keys
        p.bad == bad

        where:
        why                                    | setting                                                                                   | keys | bad
        'a bare key'                           | VECTOR_KEY                                                                                | 1    | 0
        'a labelled key'                       | "dashboard-a ${VECTOR_KEY}"                                                               | 1    | 0
        'a blank line'                         | '  \n\t\r\n'                                                                              | 0    | 0
        'a # line'                             | "# dashboard-a ${VECTOR_KEY}"                                                             | 0    | 0
        'nothing at all'                       | null                                                                                      | 0    | 0
        'a 32-byte blob, the key without its X.509 wrapping' | Base64.encoder.encodeToString(Base64.decoder.decode(VECTOR_KEY)[-32..-1] as byte[]) | 0    | 1
        'an RSA key'                           | text(KeyPairGenerator.getInstance('RSA').with { initialize(1024); generateKeyPair() }.public) | 0 | 1
        'an Ed448 key'                         | text(pair('Ed448').public)                                                                | 0    | 1
        'a word after the key'                 | "${VECTOR_KEY} dashboard-a"                                                               | 0    | 1
        'Windows line ends, and a comment'     | "# readers\r\ndashboard-a ${VECTOR_KEY}\r\ndashboard-b ${text(pair('Ed25519').public)}\r\n" | 2    | 0
        '21 keys'                              | (1..21).collect { text(pair('Ed25519').public) }.join('\n')                               | 20   | 1
    }

    def 'a key past the twentieth is never used, and a line that is not a key stops none of the others'() {
        given:
        List<KeyPair> pairs = (1..21).collect { pair('Ed25519') }
        List<PublicKey> keys = ReaderKeys.parse((['not-a-key'] + pairs.collect { text(it.public) }).join('\n')).keys as List<PublicKey>
        String t = '1800000000'

        expect:
        ReaderKeys.refusal(keys, 'GET', ReaderKeys.PATH, t, sign(pairs[0].private, wire('GET', ReaderKeys.PATH, t)), NOW) == null
        ReaderKeys.refusal(keys, 'GET', ReaderKeys.PATH, t, sign(pairs[19].private, wire('GET', ReaderKeys.PATH, t)), NOW) == null
        ReaderKeys.refusal(keys, 'GET', ReaderKeys.PATH, t, sign(pairs[20].private, wire('GET', ReaderKeys.PATH, t)), NOW) == 'the signature matches none of the reader keys'
    }

    // ------------------------------------------------------------------ a node standing by, the clock, the page

    def 'a node standing by answers each cluster word from the row, and nothing more'() {
        given:
        int port = monitor(text(reader.public))
        long t = System.currentTimeMillis()
        HealthMonitor.@standby = true
        HealthMonitor.LAST[27L] = view(t, t)   // what a person's check left here is not what it answers
        Lease.row = [node: 'node-a.invalid', at: t, summary: [alive: true, status: [200, 'HVM-HEALTH OK\n'], other: 'UNKNOWN', monitor: [pace: [clusters: 3]],
                     clusters: ['27': 'WARNING', '28': 'SOMETHING-NEW', '29': 'CRITICAL', 'x': 'OK', '1234567890123456789': 'OK']]]

        when:
        Map body = json(fetch(port, ReaderKeys.PATH, signed(reader.private)))

        then:
        body == [version: 1, standingBy: true, alive: true, sweptAt: t, quickMinutes: null,
                 clusters: [[id: 27, state: 'WARNING'], [id: 28, state: 'STALE'], [id: 29, state: 'CRITICAL']], names: [:]]

        when: 'the row is older than a sweep may be'
        Lease.row = [*:Lease.row, at: t - StatusAnswer.SWEEP_LATE_MS - MIN]
        Map old = json(fetch(port, ReaderKeys.PATH, signed(reader.private)))

        then:
        old.alive == false
        old.clusters == [[id: 27, state: 'STALE'], [id: 28, state: 'STALE'], [id: 29, state: 'STALE']]

        when: 'there is no row'
        Lease.row = null
        Map none = json(fetch(port, ReaderKeys.PATH, signed(reader.private)))

        then:
        none == [version: 1, standingBy: true, alive: false, sweptAt: null, quickMinutes: null, clusters: [], names: [:]]
    }

    /** A pin for the Date header: the JDK's server adds it to every answer. */
    def 'the answer carries a Date header, so a reader can see a clock difference'() {
        given:
        int port = monitor(text(reader.public))
        SimpleDateFormat http = new SimpleDateFormat('EEE, dd MMM yyyy HH:mm:ss zzz', Locale.US)
        http.timeZone = TimeZone.getTimeZone('GMT')

        when:
        List read = fetch(port, ReaderKeys.PATH, signed(reader.private))
        List refused = fetch(port, ReaderKeys.PATH)

        then:
        read[0] == 200
        refused[0] == 404
        [read, refused].every { Math.abs(http.parse(it[3] as String).time - System.currentTimeMillis()) < MIN }
    }

    def 'the all-clusters page says how many reader keys are in force, and when the port is off'() {
        given:
        MorpheusContext m = morpheus(JsonOutput.toJson([monitorMinutes: '5', readerKeys: "dashboard-a ${text(reader.public)}\nnot-a-key"]))
        HealthMonitor.start(m, new ClusterHealthPlugin())
        HealthMonitor.sweep(m, new ClusterHealthPlugin())
        HealthMonitor.readerAnswer(null, null, NOW)
        String pages = new File('src/assets/javascripts/health-pages.js').getText('UTF-8')
        String lines = pages.substring(pages.indexOf('function monitorLines'), pages.indexOf('function initFleet'))

        when:
        Map s = HealthMonitor.monitorState()

        then:
        s.statusPort == 0
        s.readerKeys == 1
        s.readerKeysBad == 1
        s.lastRefusal == [at: NOW, why: 'the request carries no signature']
        !JsonOutput.toJson(s).contains(text(reader.public).substring(16, 40))   // counts, never a key

        and:
        lines.contains("' line(s) are not a public key, or are past the 20th, so they are ignored.'")
        lines.contains("'Reader keys are set, but the Status URL port is off, so no reader can use them.'")
        lines.contains("' in force.'")
        lines.contains("' Last refused read, '")
        ['m.readerKeysBad', 'm.readerKeys', 'm.statusPort', 'm.lastRefusal.at', 'm.lastRefusal.why'].every { lines.contains(it) }
    }
}
