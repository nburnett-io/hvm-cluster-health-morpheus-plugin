package org.hvmhealth.morpheus

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import groovy.util.logging.Slf4j

import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.ExecutorService
import java.util.concurrent.Future
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * A status URL a monitor can read without logging in, because every plugin route needs a browser session and a URL monitor has none.
 * Off until a port is set. Answers from what the monitor already holds, so a request never reaches a host, and says nothing a stranger could use.
 * What it says is StatusAnswer's. A port that cannot be bound is logged and shown on the all-clusters page, and tried again on the next sweep: it never fails the plugin.
 */
@Slf4j
class StatusServer {
    /** The JDK's server has no limit on reading a request by default, so two slow clients on an open port could hold both threads for ever. */
    static long requestLimitMs = 5000L

    private static HttpServer server
    private static ExecutorService pool
    private static ScheduledExecutorService reaper
    private static int port
    private static String logged
    /** Why the status URL is not listening when it should be, for the all-clusters page. */
    static volatile String error

    /** [status code, body] for a path, from a closure given to start. */
    static synchronized void start(int wanted, Closure<List> answer) {
        stop()
        if (wanted <= 0) {
            return
        }
        try {
            server = HttpServer.create(new InetSocketAddress(wanted), 8)
            pool = Executors.newFixedThreadPool(2, { Runnable r -> daemon(r) })
            reaper = Executors.newSingleThreadScheduledExecutor({ Runnable r -> daemon(r) })
            server.executor = { Runnable r ->
                Future f = pool.submit(r)
                reaper.schedule({ f.cancel(true) } as Runnable, requestLimitMs, TimeUnit.MILLISECONDS)
            } as Executor
            server.createContext('/') { HttpExchange x -> respond(x, answer) }
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
    static synchronized void ensure(int wanted, Closure<List> answer) {
        if (wanted <= 0) {
            stop()
            error = wanted < 0 ? 'The Status URL port setting is not a port number, so the status URL is off.' : null
        } else if (!server || port != wanted) {
            start(wanted, answer)
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

    private static void respond(HttpExchange x, Closure<List> answer) {
        try {
            List r = reply(x.requestMethod, x.requestURI.path, answer)
            byte[] body = (r[1] as String).getBytes('UTF-8')
            x.responseHeaders.add('Content-Type', 'text/plain; charset=utf-8')
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
}
