package org.hvmhealth.morpheus

import com.morpheusdata.core.MorpheusContext
import groovy.util.logging.Slf4j

/**
 * Runs a Morpheus core call inside a Hibernate session. The plugin's jobs and monitor run on their own threads, and some
 * core services assume a web request's session is bound to the thread ("Could not obtain current Hibernate Session").
 *
 * This reaches into Morpheus internals: it opens the session through GORM's static withNewSession on a core domain class,
 * loaded from the classloader of the MorpheusContext implementation. Where that class is absent, the call runs as before.
 */
@Slf4j
class MorpheusSession {
    static final String DOMAIN_CLASS = 'com.morpheus.Datastore'

    static <T> T run(MorpheusContext morpheus, Closure<T> work) {
        Class domain = domainClass(morpheus)
        if (domain == null) {
            return work.call()
        }
        T out = null
        domain.withNewSession { session ->
            out = work.call()
            session.flush()
        }
        out
    }

    private static Class domainClass(MorpheusContext morpheus) {
        try {
            Class c = Class.forName(DOMAIN_CLASS, false, morpheus.getClass().classLoader)
            c.metaClass.respondsTo(c, 'withNewSession', Closure) || c.methods.any { it.name == 'withNewSession' } ? c : null
        } catch (Throwable e) {
            log.debug("no ${DOMAIN_CLASS} on the Morpheus classloader (${e.class.simpleName}); calling core without a new session")
            null
        }
    }
}
