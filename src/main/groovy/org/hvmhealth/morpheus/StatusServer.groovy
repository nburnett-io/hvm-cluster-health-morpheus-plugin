package org.hvmhealth.morpheus

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import groovy.util.logging.Slf4j

import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.ExecutorService
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * A status URL a monitor can read without logging in, because every plugin route needs a browser session and a URL monitor has none.
 * Off until a port is set. Answers from what the monitor already holds, so a request never reaches a host, and says nothing a stranger could use.
 * What it says is StatusAnswer's, and what a signed read of /v1/clusters gets is DetailAnswer's (see ReaderKeys). A port that cannot be bound is logged and shown on the all-clusters page, and tried again on the next sweep: it never fails the plugin.
 */
@Slf4j
class StatusServer {
    /**
     * The JDK's server has no limit on reading a request by default, so two slow clients on an open port could hold both threads for ever. Each request gets this
     * long from when a thread takes it, reading its request line included, as the JDK reads that on our thread.
     */
    static long requestLimitMs = 5000L
    /** Requests waiting for a thread. Past that the JDK closes the connection at once, as it does when execute throws. */
    static final int QUEUE = 32

    private static HttpServer server
    private static ExecutorService pool
    private static ScheduledExecutorService reaper
    private static int port
    private static String logged
    /** Why the status URL is not listening when it should be, for the all-clusters page. */
    static volatile String error

    /**
     * [status code, body] for a path, from a closure given to start. {@code signed}, when given, answers a GET of ReaderKeys.PATH from its time and signature
     * headers, with [status code, body, content type].
     */
    static synchronized void start(int wanted, Closure<List> answer, Closure<List> signed = null) {
        stop()
        if (wanted <= 0) {
            return
        }
        try {
            server = HttpServer.create(new InetSocketAddress(wanted), 8)
            ExecutorService workers = new ThreadPoolExecutor(2, 2, 0L, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<Runnable>(QUEUE), { Runnable r -> daemon(r) } as ThreadFactory)
            ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor({ Runnable r -> daemon(r) })
            pool = workers
            reaper = timer
            // a full queue throws RejectedExecutionException, and the JDK's dispatcher then closes that connection
            server.executor = { Runnable r -> workers.execute { limited(r, timer) } } as Executor
            server.createContext('/') { HttpExchange x -> respond(x, answer, signed) }
            server.start()
            port = wanted
            error = null
            logged = null
            log.info("cluster health status URL listening on port ${wanted}")
        } catch (Throwable e) {
            stop()
            error = "Could not listen on port ${wanted}: ${FindingHistory.shortError(e)}".toString()
            if (logged != error) {   // tried again every sweep
                log.warn("cluster health status URL: ${error}")
                logged = error
            }
        }
    }

    /** Brings the server in line with the setting: 0 is off, a negative number is not a port, and a change of port restarts it. */
    static synchronized void ensure(int wanted, Closure<List> answer, Closure<List> signed = null) {
        if (wanted <= 0) {
            stop()
            error = wanted < 0 ? 'The Status URL port setting is not a port number, so the status URL is off.' : null
        } else if (!server || port != wanted) {
            start(wanted, answer, signed)
        }
    }

    static synchronized void stop() {
        server?.stop(0)
        pool?.shutdownNow()
        reaper?.shutdownNow()
        server = null
        pool = null
        reaper = null
        port = 0
    }

    /** The port it is listening on; 0 when it is not. */
    static synchronized int listening() {
        server ? port : 0
    }

    private static Thread daemon(Runnable r) {
        Thread t = new Thread(r, 'hvm-cluster-health-status')
        t.daemon = true
        t
    }

    /**
     * Runs one request, interrupted requestLimitMs after it STARTS: a socket read then fails and the JDK closes the connection. A limit counted from the queue
     * instead cancelled tasks that never ran, and a task that never runs leaves its connection open and unanswered for good. The interrupt is cleared before
     * the thread takes the next request, and cannot land after that.
     */
    private static void limited(Runnable work, ScheduledExecutorService timer) {
        Thread me = Thread.currentThread()
        boolean[] done = [false] as boolean[]
        ScheduledFuture limit = timer.schedule({ synchronized (done) { if (!done[0]) me.interrupt() } } as Runnable, requestLimitMs, TimeUnit.MILLISECONDS)
        try {
            work.run()
        } finally {
            synchronized (done) { done[0] = true }
            limit.cancel(false)
            Thread.interrupted()
        }
    }

    private static void respond(HttpExchange x, Closure<List> answer, Closure<List> signed) {
        try {
            List r = signed != null && x.requestURI.path == ReaderKeys.PATH && x.requestMethod == 'GET'
                    ? signedReply(x.requestHeaders.getFirst(ReaderKeys.TIME), x.requestHeaders.getFirst(ReaderKeys.SIGNATURE), signed)
                    : reply(x.requestMethod, x.requestURI.path, answer)
            byte[] body = (r[1] as String).getBytes('UTF-8')
            x.responseHeaders.add('Content-Type', (r[2] ?: 'text/plain; charset=utf-8') as String)
            x.responseHeaders.add('Cache-Control', 'no-store')
            x.sendResponseHeaders(r[0] as int, x.requestMethod == 'HEAD' ? -1 : body.length)
            if (x.requestMethod != 'HEAD') {
                x.responseBody.write(body)
            }
        } finally {
            x.close()
        }
    }

    /** GET and HEAD only. A failure here says STALE, which pages someone, and its text stays in the log rather than go to whoever asked. */
    static List reply(String method, String path, Closure<List> answer) {
        if (!(method in ['GET', 'HEAD'])) {
            return [405, StatusAnswer.UNKNOWN]
        }
        try {
            answer.call(path)
        } catch (Throwable e) {
            log.warn("cluster health status URL could not answer: ${e.message}")
            [503, StatusAnswer.STALE]
        }
    }

    /** A GET of ReaderKeys.PATH. A HEAD is no signed read, so it goes to reply and gets what a path that does not exist gets. A failure says STALE, as reply does, and its text stays in the log. */
    static List signedReply(String time, String signature, Closure<List> signed) {
        try {
            signed.call(time, signature)
        } catch (Throwable e) {
            log.warn("cluster health status URL could not answer a signed read: ${e.message}")
            [503, StatusAnswer.STALE]
        }
    }
}
