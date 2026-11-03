package org.hvmhealth.model

/**
 * A host's management controller (iDRAC, iLO) as ipmitool sees it through the local driver: its status, inventory and network, and
 * the scripts that read it and reset it. Reading changes nothing. A reset restarts the controller only, never the host.
 */
class Bmc {
    /** Read-only, and done well within the proxy's two minutes: past its budget each command answers at once. */
    static final String READ_SCRIPT = '''export LC_ALL=C PATH=/usr/sbin:/usr/bin:/sbin:/bin
BUDGET=60
t() { local s="$1" left=$((BUDGET - SECONDS)); shift; [ "$left" -gt 0 ] || return 124; [ "$s" -le "$left" ] || s="$left"; timeout -k 2 "$s" "$@" 2>/dev/null; }
echo "@@tool"
echo "Path: $(command -v ipmitool)"
echo "Device: $(ls /dev/ipmi[0-9]* 2>/dev/null | head -1)"
echo "Package: $(dpkg-query -W -f='${Version}' ipmitool 2>/dev/null)"
echo "@@dmi"
for k in system-manufacturer system-product-name system-serial-number bios-version; do echo "$k: $(t 5 dmidecode -s "$k")"; done
command -v ipmitool >/dev/null || exit 0
echo "@@mc"; t 10 ipmitool mc info
echo "@@selftest"; t 10 ipmitool mc selftest
echo "@@chassis"; t 10 ipmitool chassis status
echo "@@lan"; t 10 ipmitool lan print | grep -E '^(IP Address Source|IP Address|Subnet Mask|MAC Address|Default Gateway IP|802.1q VLAN ID) +:'
echo "@@fru"; t 15 ipmitool fru print 0 | grep -E '^ *(Board Mfg Date|Board Mfg|Board Product|Board Serial|Board Part Number|Product Manufacturer|Product Name|Product Serial|Product Part Number|Chassis Serial) +:'
echo "@@sel"; t 10 ipmitool sel info | grep -E '^(Entries|Percent Used|Last Add Time|Overflow) +:'
echo "@@events"; t 15 ipmitool sel elist last 15
echo "@@watchdog"; t 10 ipmitool mc watchdog get | grep -E '^Watchdog Timer (Is|Action)'
echo "@@sensors"; t 20 ipmitool sdr elist
'''

    /** Refuses when the controller is not answering here, as a reset sent into nothing proves nothing. */
    static final String RESET_SCRIPT = '''export LC_ALL=C PATH=/usr/sbin:/usr/bin:/sbin:/bin
command -v ipmitool >/dev/null || { echo 'REFUSED: ipmitool is not installed on this host'; exit 1; }
timeout -k 2 10 ipmitool mc info >/dev/null 2>&1 || { echo 'REFUSED: the management controller is not answering through ipmitool, so it cannot be reset from here. Use its own web page, or drain the power of the host in a maintenance window.'; exit 1; }
timeout -k 2 20 ipmitool mc reset cold
'''

    private static final Set<String> CHASSIS_FAULTS = ['Power Overload', 'Main Power Fault', 'Power Control Fault', 'Drive Fault', 'Cooling/Fan Fault'] as Set

    /** What READ_SCRIPT printed, as the panel shows it. Fields a controller did not give are left out, never guessed. */
    static Map parse(String text) {
        Map<String, List<String>> sec = [:]
        List<String> cur = null
        (text ?: '').readLines().each { String l ->
            if (l.startsWith('@@')) {
                cur = []
                sec[l.substring(2).trim()] = cur
            } else if (cur != null) {
                cur << l
            }
        }
        Map<String, Map<String, String>> kv = sec.collectEntries { String name, List<String> lines ->
            [(name): lines.findAll { it.contains(':') }.collectEntries { String l ->
                int i = l.indexOf(':')
                [(l.substring(0, i).trim()): l.substring(i + 1).trim()]
            }]
        }
        Map tool = kv.tool ?: [:]
        boolean installed = tool.Path as boolean
        Map dmi = kv.dmi ?: [:]
        Map out = [installed: installed, device: tool.Device ?: null, version: tool.Package ?: null,
                   inventory: clean([manufacturer: dmi['system-manufacturer'], product: dmi['system-product-name'], serial: dmi['system-serial-number'], bios: dmi['bios-version']])]
        Map mc = kv.mc ?: [:]
        out.answered = installed && mc['Device Available'] == 'yes'
        if (!out.answered) {
            return out   // empty sections from a controller that is not there would read as a healthy one
        }
        out.controller = clean([manufacturer: mc['Manufacturer Name'], firmware: mc['Firmware Revision'], ipmiVersion: mc['IPMI Version'],
                                selftest: (kv.selftest ?: [:]).Selftest])
        Map ch = kv.chassis ?: [:]
        out.chassis = clean([power: ch['System Power'], restorePolicy: ch['Power Restore Policy'], intrusion: ch['Chassis Intrusion']]) +
                [faults: CHASSIS_FAULTS.findAll { ch[it] == 'true' }.sort()]
        Map lan = kv['lan'] ?: [:]
        out.network = clean([source: lan['IP Address Source'], ip: lan['IP Address'], mask: lan['Subnet Mask'], gateway: lan['Default Gateway IP'],
                             mac: lan['MAC Address'], vlan: lan['802.1q VLAN ID']])
        Map fru = kv.fru ?: [:]
        out.inventory = (out.inventory as Map) + clean([productName: fru['Product Name'], productSerial: fru['Product Serial'], partNumber: fru['Product Part Number'] ?: fru['Board Part Number'],
                                                        board: fru['Board Product'], boardSerial: fru['Board Serial'], chassisSerial: fru['Chassis Serial'], made: fru['Board Mfg Date']])
        Map sel = kv.sel ?: [:]
        out.eventLog = clean([entries: sel.Entries, percentUsed: sel['Percent Used'], lastAdded: sel['Last Add Time'], overflow: sel.Overflow]) +
                [recent: (sec.events ?: []).findAll { it.trim() }.collect { it.trim() }]
        Map wd = kv.watchdog ?: [:]
        // "Action" on ipmitool 1.8.19, "Actions" on older ones
        out.watchdog = clean([state: wd['Watchdog Timer Is'], action: wd.find { k, v -> k.startsWith('Watchdog Timer Action') }?.value])
        // "Fan1A | 38h | ok | 7.1 | 9480 RPM": no reading (ns) is a sensor the server does not fit, not a fault. A discrete sensor can end
        // "| 7.1 |" with no reading at all, so the split keeps trailing empty fields.
        List<List<String>> rows = (sec.sensors ?: []).collect { it.split(/\s*\|\s*/, -1) as List<String> }.findAll { it.size() >= 5 }
        out.sensors = [ok: rows.count { it[2].trim() == 'ok' }, notReading: rows.count { it[2].trim() == 'ns' },
                       problems: rows.findAll { faulted(it[2], it[4]) }.collect { [name: it[0].trim(), status: it[2].trim(), reading: it[4].trim()] }]
        out
    }

    /**
     * ipmitool's status column judges threshold sensors only: a discrete one that has failed still says ok, and names the failure in its
     * reading ("Presence detected, Failure detected", "IERR"). The probe's awk says the same.
     */
    static final String DISCRETE_FAULT = 'fail|fault|error|ierr|critical|predictive|non-recoverable'

    static boolean faulted(String status, String reading) {
        String s = status?.trim()
        (s && !(s in ['ok', 'ns'])) || (s != 'ns' && reading?.toLowerCase() =~ DISCRETE_FAULT)
    }

    private static Map clean(Map m) {
        m.findAll { k, v -> v != null && v.toString().trim() } as Map
    }
}
