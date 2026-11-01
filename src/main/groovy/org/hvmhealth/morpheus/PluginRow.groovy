package org.hvmhealth.morpheus

import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.data.DataQuery
import com.morpheusdata.model.Account
import com.morpheusdata.model.OperationData
import groovy.json.JsonSlurper
import groovy.util.logging.Slf4j

/**
 * One JSON document per cluster in one OperationData row, on FindingHistory's terms: replaced rather than updated, read back because the API
 * does not say how large a field may be, and kept with the master tenant. For CapacityTrend and Availability, which write far less often, and for Lease.
 */
@Slf4j
class PluginRow {
    private static List<OperationData> rows(MorpheusContext morpheus, String category, Long clusterId) {
        String ref = clusterId.toString()
        MorpheusSession.run(morpheus) {
            morpheus.services.operationData.list(new DataQuery().withFilter('category', category).withFilter('refId', ref))
                    .findAll { it.category == category && it.refId == ref }
        }
    }

    private static Map parse(OperationData row) {
        try {
            row?.rawData ? (new JsonSlurper().parseText(row.rawData) as Map) : null
        } catch (Exception e) {
            log.warn("cluster health row ${row.id} is unreadable, starting again: ${e.message}")
            null
        }
    }

    /** The newest row's document; null when there is none. Throws if the store cannot be read, so that a passing failure never replaces what is there. Lease dates its row `at`. */
    static Map read(MorpheusContext morpheus, String category, Long clusterId) {
        rows(morpheus, category, clusterId).collect { parse(it) }.findAll().max { (it.savedAt ?: it.at ?: 0L) as long }
    }

    /** Every cluster's document, in one query rather than one per cluster. */
    static Map<Long, Map> readAll(MorpheusContext morpheus, String category) {
        List<OperationData> all = MorpheusSession.run(morpheus) {
            morpheus.services.operationData.list(new DataQuery().withFilter('category', category)).findAll { it.category == category && it.refId?.isLong() }
        }
        all.groupBy { it.refId as Long }.collectEntries { Long id, List<OperationData> rs ->
            [(id): rs.collect { parse(it) }.findAll().max { (it.savedAt ?: 0L) as long }]
        }.findAll { k, v -> v != null } as Map<Long, Map>
    }

    /** The new row first, read back, and only then the old ones go: a failure leaves what was there. */
    static void replace(MorpheusContext morpheus, String category, String name, Long clusterId, String json, long now) {
        List<OperationData> old = rows(morpheus, category, clusterId)
        Account owner = HealthMonitor.masterAccount(morpheus) ?: morpheus.services.cluster.get(clusterId)?.owner
        String ref = clusterId.toString()
        def store = morpheus.services.operationData
        OperationData saved = MorpheusSession.run(morpheus) {
            String code = "${category}-${ref}-${now}".toString()
            store.create(new OperationData(account: owner, code: code, keyValue: code, value: now.toString(), category: category, name: name,
                    refType: 'computeServerGroup', refId: ref, refDate: new Date(now), rawData: json))
        }
        if (saved?.id == null) {
            throw new IllegalStateException("Morpheus did not store the ${name} record")
        }
        boolean kept = MorpheusSession.run(morpheus) { store.get(saved.id)?.rawData == json }
        MorpheusSession.run(morpheus) { (kept ? old : [saved]).each { store.remove(it) } }
        if (!kept) {
            throw new IllegalStateException("Morpheus did not keep the ${name} record whole (${json.length()} characters)")
        }
    }
}
