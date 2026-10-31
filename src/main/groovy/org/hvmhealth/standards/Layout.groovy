package org.hvmhealth.standards

import groovy.transform.Canonical

/**
 * The HVM cluster layout a cluster was built from, read from its Morpheus layout code, e.g.
 * mvm-1.3-ubuntu-24.04-std-morpheus-amd64 or mvm-2.0-virtswitch-ubuntu-26.04-morpheus-amd64.
 */
@Canonical
class Layout {
    String code
    /** "1.3", "2.0", or null when unknown. */
    String version
    String os
    boolean virtswitch
    /** 1.3's other layout: Ceph instead of a shared LUN, so GFS2 and DLM checks do not apply as such. */
    boolean hciCeph

    /** The layout code is the only source: "morpheus_version" in corosync.conf is a template version, 1.3 on 2.0 too. */
    static Layout of(String code) {
        String c = code ?: ''
        def v = c =~ /(?:^|-)(?:mvm|hvm)-(\d+\.\d+)/
        def o = c =~ /ubuntu-(\d+\.\d+)/
        String version = v.find() ? v.group(1) : null
        boolean std = c.contains('-std-')
        new Layout(code, version, o.find() ? o.group(1) : null, c.contains('virtswitch'),
                version == '1.3' && c && !std)
    }

    /** The profile key in {@link Standards}. */
    String getProfile() {
        version ?: 'unknown'
    }

    String getLabel() {
        version ? "HVM ${version}${virtswitch ? ' (VirtSwitch)' : ''}${hciCeph ? ' (HCI Ceph)' : ''}${os ? ' on Ubuntu ' + os : ''}".toString() : 'unknown layout'
    }
}
