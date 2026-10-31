package org.hvmhealth.morpheus

import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.model.Account
import com.morpheusdata.model.AuditLog
import com.morpheusdata.model.ComputeServerGroup
import com.morpheusdata.model.User
import groovy.util.logging.Slf4j

/**
 * Writes plugin actions to Morpheus's audit log. Entries go to the acting user's tenant; automatic actions to the
 * cluster owner's tenant. Auditing never fails the operation it records.
 */
@Slf4j
class Audit {
    static final String PREFIX = 'HVM Cluster Health'
    static final Set<String> NOISE = ['clusterId', 'urlName', 'controller', 'action', 'format'] as Set

    static String describe(String action, ComputeServerGroup cluster, Map details = [:]) {
        String d = details.findAll { k, v -> v != null && v != '' && !(k as String).startsWith('_') && !NOISE.contains(k as String) }
                .collect { k, v -> "${k}=${v}" }.join(', ')
        "${PREFIX}: ${action}${cluster ? " on cluster ${cluster.name} (${cluster.id})" : ''}${d ? ' [' + d + ']' : ''}".toString()
    }

    static void info(MorpheusContext morpheus, User user, ComputeServerGroup cluster, String action, Map details = [:]) {
        write(morpheus, AuditLog.LEVEL_INFO, user, cluster, action, details)
    }

    static void warn(MorpheusContext morpheus, User user, ComputeServerGroup cluster, String action, Map details = [:]) {
        write(morpheus, AuditLog.LEVEL_WARN, user, cluster, action, details)
    }

    private static void write(MorpheusContext morpheus, int level, User user, ComputeServerGroup cluster, String action, Map details) {
        String text = describe(action, cluster, details)
        try {
            Account account = user?.account ?: cluster?.owner
            MorpheusSession.run(morpheus) {
                morpheus.services.admin.auditLog.log(level, new AuditLog(account: account, user: user, actualUser: user, description: text))
            }
        } catch (Throwable e) {
            log.warn("audit log write failed (${e.message}): ${text}")
        }
        log.info("[audit] ${user?.username ?: 'system'}: ${text}")
    }
}
