package org.hvmhealth.checks

import groovy.transform.Canonical

/**
 * Vendor-neutral storage identity and per-vendor profile, built from documentation (research_notes storage_vendor_matrix.md),
 * not yet seen against a live array. The host reports a trimmed INQUIRY vendor and product (multipath -ll prints them as
 * "VENDOR,PRODUCT"); this maps the pair to a family profile. An unknown pair returns null, and a vendor-specific check must
 * then read NOT CHECKED, never PASS, with the raw values shown.
 *
 * Every value here is a documented expectation, named in {@code doc}, not a live reading: the plugin judges the live values a
 * host reports, and uses these only to say what the vendor intends and who set it. The trimmed pair gives a family, not a
 * model (3PARdata/VV covers B10000, Primera, A9000 and 3PAR), so the family name lists them. Pure is present for identity and
 * its no_path_retry only; the Pure plugin owns Pure-specific tuning (detect_prio, udev, the Pure stanza), which is not here.
 */
class StorageProfiles {
    /**
     * npr: the vendor's documented no_path_retry, one of the strings 'fail'/'queue' or an Integer count, or null when the
     * vendor states none (it is OS-dependent). recoveryTmo: the documented live iSCSI recovery_tmo in seconds (multipathd
     * writes fast_io_fail_tmo into it), or null. Both are expectations from a document, not values seen on a host.
     */
    @Canonical
    static class Profile {
        String key
        String family
        Object npr
        Integer recoveryTmo
        String doc
    }

    /** vendor and product are matched trimmed and case-insensitively; product disambiguates a vendor that spans families. */
    private static final List<Map> TABLE = [
            [v: /3PARdata/, p: /VV/, key: '3par', family: 'HPE 3PARdata family (B10000, Primera, A9000, 3PAR, Alletra 9000)', npr: 18, rtmo: 10, doc: 'HPE 3PAR/Primera/A9000 RHEL guides'],
            [v: /Nimble/, p: /Server/, key: 'nimble', family: 'HPE Alletra 5000/6000 or Nimble', npr: 30, rtmo: 5, doc: 'HPE Nimble/Alletra multipath.conf guide (built-in table queues)'],
            [v: /NETAPP|LSI|ENGENIO/, p: /INF-01-00/, key: 'netapp-eseries', family: 'NetApp E-Series (RDAC)', npr: 30, rtmo: 5, doc: 'multipath-tools hwtable'],
            [v: /NETAPP/, p: /LUN/, key: 'netapp-ontap', family: 'NetApp ONTAP', npr: 'queue', rtmo: 5, doc: 'NetApp Ubuntu SAN host guide ("You shouldn\'t override")'],
            [v: /DellEMC/, p: /PowerStore/, key: 'powerstore', family: 'Dell PowerStore', npr: 3, rtmo: 15, doc: 'Dell Host Connectivity Guide (2020)'],
            [v: /EMC/, p: /SYMMETRIX/, key: 'powermax', family: 'Dell PowerMax/VMAX', npr: 'queue', rtmo: 5, doc: 'Dell Host Connectivity Guide (queue or 6; multibus)'],
            [v: /DGC/, p: /.*/, key: 'unity', family: 'Dell Unity/VNX', npr: 60, rtmo: 5, doc: 'Dell Host Connectivity Guide (2020)'],
            [v: /IBM/, p: /^2145/, key: 'ibm', family: 'IBM FlashSystem/SVC', npr: 5, rtmo: 5, doc: 'IBM Linux settings page'],
            [v: /PURE/, p: /FlashArray/, key: 'pure', family: 'Pure FlashArray', npr: 0, rtmo: 10, doc: 'Pure (Everpure) recommended settings'],
            [v: /HUAWEI/, p: /XSG1/, key: 'huawei', family: 'Huawei OceanStor', npr: 15, rtmo: null, doc: 'multipath-tools hwtable'],
            [v: /HITACHI|HP|HPE/, p: /^OPEN-/, key: 'hitachi', family: 'Hitachi VSP or HPE XP', npr: null, rtmo: null, doc: 'multipath-tools hwtable (none on 24.04, 10 on 26.04: OS-dependent)'],
            [v: /HP|HPE/, p: /MSA/, key: 'msa', family: 'HPE MSA', npr: 18, rtmo: null, doc: 'multipath-tools hwtable'],
            [v: /LENOVO/, p: /DE/, key: 'lenovo-de', family: 'Lenovo DE', npr: null, rtmo: null, doc: 'multipath-tools hwtable (RDAC family)'],
    ]

    /** The profile for a trimmed vendor/product pair, or null when the pair is not recognised. */
    static Profile of(String vendor, String product) {
        String v = (vendor ?: '').trim()
        String p = (product ?: '').trim()
        Map row = TABLE.find { (v ==~ /(?i)${it.v}/) && (p =~ /(?i)${it.p}/) }
        row ? new Profile(row.key as String, row.family as String, row.npr, row.rtmo as Integer, row.doc as String) : null
    }

    /** The profile for a map whose vendor field is "VENDOR,PRODUCT" as multipath -ll prints it; null when unrecognised. */
    static Profile forMap(String vendorProduct) {
        if (!vendorProduct) {
            return null
        }
        int i = vendorProduct.indexOf(',')
        i < 0 ? of(vendorProduct, '') : of(vendorProduct.substring(0, i), vendorProduct.substring(i + 1))
    }

    /** What the vendor's documented no_path_retry says, in words, for the finding text; empty for an unknown or unstated value. */
    static String nprText(Profile pr) {
        pr?.npr == null ? '' : "${pr.family} documents no_path_retry ${pr.npr} (${pr.doc}, not seen live)".toString()
    }
}
