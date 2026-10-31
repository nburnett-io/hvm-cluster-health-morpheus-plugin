package org.hvmhealth

import org.hvmhealth.standards.Layout
import org.hvmhealth.standards.Standards
import spock.lang.Specification

class StandardsSpec extends Specification {
    def 'layout codes map to a layout'() {
        when:
        Layout l = Layout.of(code)

        then:
        l.version == version
        l.os == os
        l.virtswitch == vs
        l.hciCeph == hci

        where:
        code                                             | version | os      | vs    | hci
        'mvm-1.3-ubuntu-24.04-std-morpheus-amd64'        | '1.3'   | '24.04' | false | false
        'mvm-1.3-ubuntu-24.04-morpheus-amd64'            | '1.3'   | '24.04' | false | true
        'mvm-2.0-virtswitch-ubuntu-26.04-morpheus-amd64' | '2.0'   | '26.04' | true  | false
        'kubernetes-1.35-ubuntu-24.04-hvm-amd64-single'  | null    | '24.04' | false | false
        null                                             | null    | null    | false | false
    }

    def 'with no layout code the layout is unknown'() {
        expect:
        Layout.of(null).profile == 'unknown'
    }

    def 'each layout has its own baseline'() {
        expect:
        Standards.forLayout(Layout.of(Fixture.LAYOUT_13)).val('osVersion') == '24.04'
        Standards.forLayout(Layout.of(Fixture.LAYOUT_13)).val('corosyncToken') == 60000
        Standards.forLayout(Layout.of(Fixture.LAYOUT_20)).val('osVersion') == '26.04'
    }

    def 'every standard names where it came from'() {
        expect:
        Standards.forLayout(Layout.of(Fixture.LAYOUT_20)).toList().every { it.source in ['hpe', 'upstream', 'morpheus', 'plugin', 'site'] && it.note }
    }

    def 'a site value overrides the baseline, a per-layout one overrides that, and both say so'() {
        given:
        String site = '{"gfs2WarnPct": 70, "profiles": {"2.0": {"gfs2WarnPct": 60, "corosyncToken": 30000}}}'

        when:
        Standards one = Standards.forLayout(Layout.of(Fixture.LAYOUT_13), site)
        Standards two = Standards.forLayout(Layout.of(Fixture.LAYOUT_20), site)

        then:
        one.val('gfs2WarnPct') == 70
        one.val('corosyncToken') == 60000
        one.source('corosyncToken') == 'morpheus'
        two.val('gfs2WarnPct') == 60
        two.val('corosyncToken') == 30000
        two.source('corosyncToken') == 'site'
    }

    def 'a value set for one cluster takes over and says so'() {
        given:
        Standards s = Standards.forLayout(Layout.of(Fixture.LAYOUT_20), '{"ntpServers":["site.example.invalid"]}')

        when:
        s.set('ntpServers', ['own.example.invalid'], 'cluster', 'Defined on the tab')

        then:
        s.val('ntpServers') == ['own.example.invalid']
        s.source('ntpServers') == 'cluster'
    }

    def 'a broken or misspelled site standard is an error, never silently ignored'() {
        when:
        Standards.forLayout(Layout.of(Fixture.LAYOUT_20), json)

        then:
        IllegalArgumentException e = thrown()
        e.message.contains(says)

        where:
        json                | says
        '{not json'         | 'not valid JSON'
        '[1,2]'             | 'JSON object'
        '{"gfs2WarnPc":70}' | 'unknown key'
    }
}
