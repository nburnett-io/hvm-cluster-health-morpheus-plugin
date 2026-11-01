package org.hvmhealth

import org.hvmhealth.checks.CheckCatalog
import org.hvmhealth.morpheus.HealthMonitor
import org.hvmhealth.standards.Standards
import spock.lang.Specification

/** The reference against the code it describes, read as source because many ids only exist in a branch nothing here reaches. */
class CheckCatalogSpec extends Specification {
    static final File CHECKER = new File('src/main/groovy/org/hvmhealth/checks/HealthChecker.groovy')
    static final File MONITOR = new File('src/main/groovy/org/hvmhealth/morpheus/HealthMonitor.groovy')
    static final File CORRELATOR = new File('src/main/groovy/org/hvmhealth/morpheus/FleetCorrelator.groovy')
    static final File CATALOG = new File('src/main/groovy/org/hvmhealth/checks/CheckCatalog.groovy')
    static final File DOC = new File('docs/CHECKS.md')
    static final String REGENERATE = '$env:UPDATE_CHECKS_DOC="1"; .\\gradlew.bat test --rerun --tests "*CheckCatalogSpec*"; Remove-Item env:UPDATE_CHECKS_DOC'
    static final List<String> FIELDS = ['area', 'name', 'what', 'why', 'action']

    /** Every id the code can emit: add('X-1', powerOnSetting('X-1' and new Result('X-1'. */
    static Set<String> emitted() {
        [CHECKER, MONITOR, CORRELATOR].collectMany { File f ->
            f.getText('UTF-8').findAll(/\b(?:add|powerOnSetting|Result)\(\s*'([A-Z]+-\d+)'/) { m, id -> id }
        } as Set
    }

    def 'every id the code can emit has an entry, and no entry outlives its id'() {
        expect:
        emitted() - CheckCatalog.ENTRIES.keySet() == [] as Set
        CheckCatalog.ENTRIES.keySet() - emitted() == [] as Set
        emitted().size() == CheckCatalog.ENTRIES.size()
    }

    def 'the parse above has not gone blind: ids reach findings only through it, and it sees the ones that are easy to miss'() {
        given:
        String checker = CHECKER.getText('UTF-8')
        // any add( or powerOnSetting( without a literal id: the second is the helper's own, which is handed one
        List<String> dynamic = checker.findAll(/\b(?:add|powerOnSetting)\(\s*[^'\s][^,)]*[,)]/).findAll { !it.contains('String id') }

        expect:
        dynamic.every { it.startsWith('add(id,') }
        emitted().containsAll(['HA-03', 'PRB-02', 'TIM-03', 'NET-13'])
    }

    def 'every id the code mentions anywhere is a real check'() {
        given:
        String all = [CHECKER, MONITOR].collect { it.getText('UTF-8') }.join('\n')

        expect:
        all.findAll(/\b(?:CAP|CLU|DLM|FEN|FLT|GFS|HA|HB|HST|HW|LAY|MIG|MOR|NET|PKG|PRB|STO|TIM|VM)-\d\d\b/).toSet() - CheckCatalog.ENTRIES.keySet() == [] as Set
    }

    def 'no id is written twice, as a map keeps only the last'() {
        given:
        List<String> keys = CATALOG.getText('UTF-8').findAll(/(?m)^\s*'([A-Z]+-\d+)': \[/) { m, id -> id }

        expect:
        keys.size() == keys.unique(false).size()
        keys.size() == CheckCatalog.ENTRIES.size()
    }

    def 'every field of every entry says something'() {
        expect:
        CheckCatalog.ENTRIES.each { String id, Map<String, String> e ->
            assert e.keySet() == FIELDS as Set, id
            FIELDS.each { String f -> assert e[f]?.trim(), "${id} ${f}" }
        }
    }

    def 'each entry is in the area of its prefix, and every area is explained'() {
        expect:
        CheckCatalog.ENTRIES.each { String id, Map<String, String> e ->
            assert e.area == CheckCatalog.PREFIXES[id.tokenize('-')[0]], id
        }
        CheckCatalog.ENTRIES.values()*.area as Set == CheckCatalog.PREFIXES.values() as Set
    }

    def 'a check says it is full-only when, and only when, it is'() {
        expect:
        CheckCatalog.ENTRIES.findAll { k, v -> v.what.startsWith(CheckCatalog.FULL_ONLY_NOTE) }.keySet() == HealthMonitor.FULL_ONLY
    }

    def 'every standards key is named by the checks it sets, and every key named is real'() {
        given:
        String text = CheckCatalog.ENTRIES.values().collectMany { [it.what, it.why, it.action] }.join(' ')

        expect:
        Standards.DEFAULTS.keySet().findAll { !text.contains(it) }.isEmpty()
        text.findAll(/site standards? ([a-z][a-z0-9]*[A-Z][A-Za-z0-9]*)/) { m, k -> k }.every { Standards.DEFAULTS.containsKey(it) }
    }

    def 'an id one entry points at exists'() {
        given:
        String text = CheckCatalog.ENTRIES.values().collectMany { [it.what, it.why, it.action] }.join(' ')

        expect:
        text.findAll(/\b[A-Z]{2,3}-\d\d\b/).toSet() - CheckCatalog.ENTRIES.keySet() == [] as Set
    }

    def 'HA-01 says its meaning is unknown, and raises nothing'() {
        expect:
        CheckCatalog.ENTRIES['HA-01'].why.contains('unknown')
        CheckCatalog.ENTRIES['HA-01'].what.contains('never a finding')
    }

    def 'the statuses are the ones the checker has, and the tab can tell them apart'() {
        expect:
        CheckCatalog.STATUSES*.status as Set == org.hvmhealth.checks.HealthChecker.Status.values()*.name() as Set
        CheckCatalog.forTab().keySet() == ['statuses', 'notes', 'areas', 'checks'] as Set
        CheckCatalog.forTab().checks.size() == CheckCatalog.ENTRIES.size()
    }

    def 'docs/CHECKS.md is what the catalog says'() {
        given:
        String want = render()
        if (System.getenv('UPDATE_CHECKS_DOC')) {
            DOC.setText(want, 'UTF-8')
            println "Wrote ${DOC.path}"
        }
        String have = DOC.exists() ? DOC.getText('UTF-8').replace('\r\n', '\n') : null
        // not a Spock condition, which would print both whole files and bury this
        if (have != want) {
            throw new AssertionError("docs/CHECKS.md is out of step with CheckCatalog.groovy. Regenerate it with: ${REGENERATE}".toString())
        }
    }

    def 'the README links to the reference'() {
        expect:
        new File('README.md').getText('UTF-8').contains('(docs/CHECKS.md)')
    }

    /** The file, from the catalog. Line endings are always \n, whatever machine wrote it. */
    static String render() {
        StringBuilder out = new StringBuilder()
        out << '# Check reference\n\n'
        out << 'What every check id means. The tab shows the same text: click an id, or press Help. '
        out << 'Generated from `src/main/groovy/org/hvmhealth/checks/CheckCatalog.groovy`, so edit that, not this file: `CheckCatalogSpec` fails if this one falls behind, and prints the command that regenerates it.\n\n'
        out << '## Statuses\n\n'
        CheckCatalog.STATUSES.each { out << "- **${it.status.replace('_', ' ')}**: ${it.meaning}\n" }
        out << '\n## Id prefixes\n\n| Prefix | Area |\n|---|---|\n'
        CheckCatalog.PREFIXES.each { String p, String a -> out << "| ${p} | ${a} |\n" }
        out << '\n## Notes\n\n'
        CheckCatalog.NOTES.each { out << "- ${it}\n" }
        CheckCatalog.PREFIXES.each { String prefix, String area ->
            out << "\n## ${area}\n"
            CheckCatalog.ENTRIES.findAll { k, v -> v.area == area }.each { String id, Map<String, String> e ->
                out << "\n### ${id}: ${e.name}\n\n"
                out << "- **What it looks at:** ${e.what}\n- **Why it matters:** ${e.why}\n- **What to do:** ${e.action}\n"
            }
        }
        out.toString()
    }
}
