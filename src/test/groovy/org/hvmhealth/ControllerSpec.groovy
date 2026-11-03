package org.hvmhealth

import org.hvmhealth.checks.HealthChecker
import org.hvmhealth.fixes.Fixes
import org.hvmhealth.model.Bmc
import org.hvmhealth.model.HostFacts
import org.hvmhealth.morpheus.HealthMonitor
import spock.lang.Specification

/** The management controller panel, the install fix and the probe lines behind HW-08 and HW-09. Shapes as a 2.0 host with an iDRAC printed them; values made up. */
class ControllerSpec extends Specification {
    static String probe() { ControllerSpec.getResourceAsStream('/scripts/health-probe.sh').getText('UTF-8') }

    static final String READ = '''@@tool
Path: /usr/bin/ipmitool
Device: /dev/ipmi0
Package: 1.8.19-10ubuntu1
@@dmi
system-manufacturer: Example Inc.
system-product-name: Server X
system-serial-number: TESTSERIAL1
bios-version: 2.27.0
@@mc
Device ID                 : 32
Firmware Revision         : 7.00
IPMI Version              : 2.0
Manufacturer Name         : Example Inc.
Device Available          : yes
Aux Firmware Rev Info     :
@@selftest
Selftest: passed
@@chassis
System Power         : on
Power Overload       : false
Main Power Fault     : false
Power Restore Policy : previous
Chassis Intrusion    : inactive
Drive Fault          : true
Cooling/Fan Fault    : false
@@lan
IP Address Source       : Static Address
IP Address              : 192.0.2.29
MAC Address             : 00:00:5e:00:53:01
Default Gateway IP      : 192.0.2.1
802.1q VLAN ID          : 195
@@fru
 Board Mfg Date        : Thu Jan 16 08:34:00 2020 EST
 Board Product         : Server X
 Board Serial          : TESTBOARD1
 Board Part Number     : 0PART1
 Product Name          : Server X
 Product Serial        : TESTSERIAL1
@@sel
Entries          : 40
Percent Used     : 3%
Last Add Time    : 08/03/26 05:27:54 EDT
Overflow         : false
@@events
  27 | 08/02/26 | 07:04:37 EDT | Temperature Inlet Temp | Upper Non-critical going high | Asserted | Reading 43 > Threshold 43 degrees C
  28 | 08/03/26 | 05:27:54 EDT | Temperature Inlet Temp | Upper Non-critical going high | Deasserted | Reading 40 < Threshold 43 degrees C
@@watchdog
Watchdog Timer Is:      Stopped
Watchdog Timer Action:  No action (0x00)
@@sensors
Temp             | 01h | ok  |  3.1 | 24 degrees C
Temp             | 02h | ns  |  3.2 | Disabled
Fan1A            | 38h | lcr |  7.1 | 0 RPM
Fan1B            | 40h | ok  |  7.1 | 5640 RPM
Intrusion        | 73h | ok  |  7.1 |
'''

    def 'the panel reads status, inventory, network and events, and calls a sensor with no reading not fitted rather than a fault'() {
        when:
        Map b = Bmc.parse(READ)

        then:
        b.installed && b.answered
        b.device == '/dev/ipmi0'
        b.controller == [manufacturer: 'Example Inc.', firmware: '7.00', ipmiVersion: '2.0', selftest: 'passed']
        b.chassis == [power: 'on', restorePolicy: 'previous', intrusion: 'inactive', faults: ['Drive Fault']]
        b.network == [source: 'Static Address', ip: '192.0.2.29', gateway: '192.0.2.1', mac: '00:00:5e:00:53:01', vlan: '195']
        b.inventory.serial == 'TESTSERIAL1'
        b.inventory.productSerial == 'TESTSERIAL1'
        b.inventory.board == 'Server X'
        b.inventory.partNumber == '0PART1'
        b.inventory.bios == '2.27.0'
        b.eventLog.percentUsed == '3%'
        b.eventLog.recent.size() == 2
        b.watchdog == [state: 'Stopped', action: 'No action (0x00)']
        b.sensors == [ok: 3, notReading: 1, problems: [[name: 'Fan1A', status: 'lcr', reading: '0 RPM']]]   // the intrusion sensor has no reading text, and still counts
    }

    def 'no ipmitool is said as such, with what the OS alone knows, and nothing guessed'() {
        when:
        Map b = Bmc.parse('@@tool\nPath: \nDevice: \nPackage: \n@@dmi\nsystem-manufacturer: Example Inc.\nsystem-serial-number: TESTSERIAL1\n')

        then:
        !b.installed
        !b.answered
        b.inventory == [manufacturer: 'Example Inc.', serial: 'TESTSERIAL1']
        !b.containsKey('controller')
    }

    def 'ipmitool with no controller answering, as on a virtual machine, is installed but not answered'() {
        expect:
        Bmc.parse('@@tool\nPath: /usr/bin/ipmitool\nDevice: \n@@mc\n@@selftest\n@@chassis\n@@sensors\n').with { it.installed && !it.answered && !it.containsKey('sensors') && !it.containsKey('chassis') }
    }

    def 'the install holds off needrestart, which would restart any service on an old library, cluster services included'() {
        expect:
        Fixes.fix('ipmitool-install', new HostFacts()).script.contains('export DEBIAN_FRONTEND=noninteractive NEEDRESTART_SUSPEND=1 NEEDRESTART_MODE=l')
    }

    def 'the probe lines behind HW-08 and HW-09 parse, and a host that printed none read nothing'() {
        when:
        HostFacts f = Fixture.facts('probe-healthy', 'hvm-node02') {
            it.replace('WATCHDOG=/dev/watchdog', 'IPMITOOL=/usr/bin/ipmitool\nBMC_SELFTEST=passed\nCHASSIS=Drive Fault|true\nCHASSIS=Power Overload|false\nSEL_USED=95\nSEL_OVERFLOW=false\nSDR_READ=1\nSDR_BAD=Fan1A|lcr|0 RPM')
        }
        HostFacts none = Fixture.facts('probe-healthy', 'hvm-node02')

        then:
        f.bmcSelftest == 'passed'
        f.chassisRead
        f.sensorsRead
        !none.sensorsRead
        f.chassisFaults == ['Drive Fault']
        f.selUsedPct == 95
        f.selOverflow == false
        f.sensorFaults == [[name: 'Fan1A', status: 'lcr', reading: '0 RPM']]
        !none.chassisRead && none.bmcSelftest == null && none.selUsedPct == null
    }

    def 'a discrete sensor that failed is a problem though its status says ok; one with no reading never is'() {
        expect:
        Bmc.faulted('ok', 'Presence detected, Failure detected')
        Bmc.faulted('ok', 'IERR')
        Bmc.faulted('lcr', '0 RPM')
        !Bmc.faulted('ok', 'Fully Redundant')
        !Bmc.faulted('ok', '24 degrees C')
        !Bmc.faulted('ns', 'Disabled')
        !Bmc.faulted('ok', '')
    }

    def 'the probe and the panel call the same readings a failure'() {
        expect:
        probe().contains("tolower(\$5) ~ /${Bmc.DISCRETE_FAULT}/")
    }

    def 'the probe asks the controller once before anything else, and reads only the latest events'() {
        given:
        String p = probe()

        expect:
        p.contains('MC=""; command -v ipmitool >/dev/null && MC="$(t 10 ipmitool mc info)"')
        p.findAll(/ipmitool mc info/).size() == 1
        !(p =~ /(?m)^\s*t \d+ ipmitool (lan|sdr type|sel elist)/)
        p.contains('ipmi 15 ipmitool sel elist last 40')
        Bmc.READ_SCRIPT.contains('ipmitool sel elist last 15')
    }

    def 'the older watchdog wording is read too'() {
        expect:
        Bmc.parse(READ.replace('Watchdog Timer Action:', 'Watchdog Timer Actions:')).watchdog.action == 'No action (0x00)'
    }

    def 'installing ipmitool is a safe fix that only a person starts, never an automatic pass'() {
        given:
        HealthChecker.Input in_ = Fixture.timesyncd()
        List<HealthChecker.Result> rs = new HealthChecker().check(in_)
        Map on = [autoFixSafe: 'on', autoFixMaintenance: 'on', autoFixClusters: 'cluster-x']

        expect:
        Fixes.fix('ipmitool-install', new HostFacts()).tier == HealthChecker.Tier.SAFE
        rs.any { it.fix == 'ipmitool-install' && it.status == HealthChecker.Status.NOT_CHECKED }
        !HealthMonitor.autoFixable(in_, rs.collect { new HealthChecker.Result(it.id, it.title, it.severity, HealthChecker.Status.WARN, it.host, it.observed, it.expected, it.source, it.fix, it.tier, it.hint) }, on)
                .any { it.fix == 'ipmitool-install' }
    }

    def 'the install only ever adds packages, and the reset restarts the controller and nothing else'() {
        given:
        String install = Fixes.fix('ipmitool-install', new HostFacts()).script

        expect:
        install.contains('--no-install-recommends ipmitool')
        install.contains("grep -qE '^(Remv|Purg) |^Inst [^ ]+ \\[' <<<\"\$p\"")
        !(install =~ /apt-get [^\n]*\b(upgrade|dist-upgrade|full-upgrade|remove|purge|autoremove)\b/)
        Bmc.RESET_SCRIPT.contains('ipmitool mc reset cold')
        !(Bmc.RESET_SCRIPT =~ /(?m)^\s*(reboot|shutdown|poweroff|systemctl|ipmitool (chassis|power))/)
        !(Bmc.READ_SCRIPT =~ /ipmitool (mc reset|chassis power|power|sel clear|raw|lan set|user set)/)
    }
}
