#!/usr/bin/env bash
#
# Runs the END-TO-END suite and explains every failure.
#
# The e2e groups are failsafe ITs, skipped by default (skipITs=true), so running
# them is one flag; UNDERSTANDING a failure is the part that costs a quarter of
# an hour per test, between the Maven log, the failsafe report, the test source
# and — for the printed documents — a diff nobody computes by hand. This script
# does that reading.
#
# Usage:
#   ./e2e.sh                  every group
#   ./e2e.sh B                GroupBIT only
#   ./e2e.sh GroupBIT         the same
#   ./e2e.sh B#someCase       one case of one group
#   ./e2e.sh --report         explain the LAST run again, without re-running
#
# What it writes:
#   target/e2e-last.log       the whole Maven output of the run
#   target/e2e-report.txt     the explained report, same text as stdout
#
# Not set -e: a failing suite is the case this script exists for, and Maven's
# non-zero status must not stop the reading that follows it.
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$ROOT" || exit 1

REPORT_DIR="target/failsafe-reports"
LOG="target/e2e-last.log"
REPORT="target/e2e-report.txt"

if ! command -v python3 >/dev/null 2>&1; then
  echo "python3 est introuvable : il porte la lecture des rapports." >&2
  echo "Sur macOS il arrive avec les outils de ligne de commande Xcode :" >&2
  echo "  xcode-select --install" >&2
  exit 127
fi

MVN="./mvnw"
if [ ! -x "$MVN" ]; then
  MVN="mvn"
fi

ONLY=""
REPORT_ONLY=0
if [ "$#" -gt 0 ]; then
  case "$1" in
    --report|-r)
      REPORT_ONLY=1
      ;;
    --help|-h)
      sed -n '3,20p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'
      exit 0
      ;;
    *)
      # "B", "GroupBIT" and "B#case" all name the same class: the group letter
      # is what a human says, the class name is what failsafe wants.
      SPEC="$1"
      CLASS="${SPEC%%#*}"
      METHOD=""
      case "$SPEC" in
        *#*) METHOD="#${SPEC#*#}" ;;
      esac
      case "$CLASS" in
        Group*IT|RoutingIT) ;;
        *) CLASS="Group${CLASS}IT" ;;
      esac
      if [ ! -f "src/test/java/com/intermarche/e2e/${CLASS}.java" ]; then
        echo "Groupe inconnu : ${CLASS}. Les groupes existants :" >&2
        ls src/test/java/com/intermarche/e2e/*IT.java 2>/dev/null \
          | sed 's#.*/##; s#\.java$##' | sed 's/^/  /' >&2
        exit 2
      fi
      ONLY="-Dit.test=${CLASS}${METHOD}"
      ;;
  esac
fi

STATUS=0
if [ "$REPORT_ONLY" -eq 0 ]; then
  # The previous run's leftovers are removed first: a report must describe THIS
  # run, and a stale golden-actual file would be diffed as if it were fresh.
  rm -rf "$REPORT_DIR" target/golden-actual
  mkdir -p target
  echo "== Lancement des tests e2e ${ONLY:-(tous les groupes)}"
  echo "== Journal complet : $LOG"
  echo
  set -o pipefail
  "$MVN" verify -DskipITs=false -DskipUTs=true ${ONLY} 2>&1 | tee "$LOG"
  STATUS=${PIPESTATUS[0]}
fi

python3 - "$REPORT_DIR" "$LOG" "$STATUS" <<'PY' | tee "$REPORT"
"""Reads the failsafe reports of the run just finished and explains each failure.

For every failing case it prints what the test was checking (its own Javadoc,
which in this code base names the requirement), what the assertion actually
said, where it broke in the project's own source, and a HYPOTHESIS on the cause
drawn from the failure's signature. For a printed-document golden master it also
computes the diff between the recorded reference and the document produced.
"""
import glob
import os
import re
import sys
import difflib
import xml.etree.ElementTree as ET

REPORT_DIR, LOG, STATUS = sys.argv[1], sys.argv[2], sys.argv[3]
BAR = '=' * 78
SUB = '-' * 78

# Failure signature -> hypothesis. Ordered: the first match wins, so the
# specific patterns come before the general ones. Each hypothesis is a guess
# and says so — the facts above it are what the report is for.
HINTS = [
    (r"Lire le diff",
     "Le document imprimé n'est plus celui de la référence. Le diff ci-dessus dit quoi.\n"
     "Si le NOUVEAU document est le bon, réenregistrer la référence :\n"
     "cp target/golden-actual/<nom>.txt src/test/resources/golden/<nom>.txt"),
    (r"R.f.rence absente pour",
     "Aucune référence enregistrée pour ce document : la première est à créer\n"
     "APRÈS lecture, avec -Dgolden.record=true."),
    (r"UnnecessaryStubbing",
     "Un stub du test n'est plus appelé par le code : une signature ou un chemin\n"
     "d'exécution a changé sous le test. Regarder la ligne pointée dans le test,\n"
     "pas dans le code."),
    (r"because \"[^\"]*\" is null|NullPointerException",
     "Un collaborateur n'est pas câblé. Dans un test, c'est presque toujours un champ\n"
     "injecté que la fabrique du test ne remplit pas ; en e2e, un état que le\n"
     "scénario n'a pas posé avant d'agir."),
    (r"TimeoutError|Timeout .* exceeded|waiting for (locator|selector)",
     "L'écran n'a jamais atteint l'état attendu : le sélecteur n'est pas apparu dans\n"
     "le délai. Soit le libellé/l'identifiant a changé dans le gabarit, soit le\n"
     "geste précédent n'a pas abouti (regarder le message d'erreur de la caisse)."),
    (r"Connection refused|ConnectException|Failed to bind|Address already in use",
     "L'application de test n'a pas démarré, ou son port était pris. Les lignes [ERROR]\n"
     "du journal en disent la raison ; un précédent run resté debout tient le port."),
    (r"ConstraintViolation|could not execute statement|SQLException",
     "Le jeu de données du scénario viole une contrainte : une entité posée deux fois,\n"
     "ou une colonne obligatoire laissée vide."),
    (r"NoSuchElementException|IndexOutOfBounds",
     "Le scénario lit un élément que l'état ne contient pas : l'action d'avant n'a pas\n"
     "produit la ligne, le règlement ou le ticket attendu."),
    (r"expected: <(.*)> but was: <(.*)>",
     "Une valeur a changé. Comparer l'attendu et l'obtenu ci-dessus : si l'obtenu est\n"
     "le bon, c'est l'assertion qui est périmée."),
    (r"AssertionFailedError|AssertionError",
     "Assertion en échec sans attendu/obtenu lisible : le message du test est ce qu'il\n"
     "y a de plus précis."),
]


_DOC_CACHE = {}


def javadocs_of(class_name):
    """Returns {method: javadoc} for a test class, read once and remembered.

    The e2e cases of this project document what they check and name the
    requirement they cover, so the comment IS both the title of a scenario in
    the listing and the explanation of what broke in the detail below it.
    """
    if class_name in _DOC_CACHE:
        return _DOC_CACHE[class_name]
    path = os.path.join('src', 'test', 'java', 'com', 'intermarche', 'e2e',
                        class_name + '.java')
    if not os.path.exists(path):
        for candidate in glob.glob('src/test/java/**/%s.java' % class_name, recursive=True):
            path = candidate
            break
    docs = {'__path__': path if os.path.exists(path) else None}
    if docs['__path__']:
        try:
            lines = open(path, encoding='utf-8', errors='replace').read().split('\n')
        except OSError:
            lines = []
        signature = re.compile(r'\b(?:public\s+)?void\s+(\w+)\s*\(')
        for i, line in enumerate(lines):
            match = signature.search(line)
            if not match:
                continue
            # Walk back over the annotations to the closing */ of the Javadoc.
            j = i - 1
            while j >= 0 and (lines[j].strip().startswith('@') or lines[j].strip() == ''):
                j -= 1
            if j < 0 or lines[j].strip() != '*/':
                continue
            end = j
            while j >= 0 and not lines[j].strip().startswith('/**'):
                j -= 1
            if j < 0:
                continue
            body = []
            for raw in lines[j + 1:end]:
                text = raw.strip()
                text = text[1:].strip() if text.startswith('*') else text
                body.append(text)
            joined = '\n'.join(body)
            joined = re.sub(r'\{@\w+\s+([^}]*)\}', r'\1', joined, flags=re.DOTALL)
            joined = re.sub(r'<[^>]+>', '', joined)
            joined = re.sub(r'\n{3,}', '\n\n', joined)
            docs[match.group(1)] = joined.strip()
    _DOC_CACHE[class_name] = docs
    return docs


def javadoc_of(class_name, method):
    """Returns the source path of a test class and the Javadoc of one case.

    @param class_name the test class
    @param method the case
    """
    docs = javadocs_of(class_name)
    return docs.get('__path__'), docs.get(method)


def title_of(class_name, method):
    """Returns a scenario's one-line title: the first sentence of its Javadoc.

    The e2e cases open on their own heading ("B2 — Refus de fusion."), which is
    exactly what a per-scenario listing has to show beside the method name.
    """
    doc = javadocs_of(class_name).get(method)
    if not doc:
        return ''
    head = doc.split('\n\n')[0]
    head = re.sub(r'\s+', ' ', head).strip()
    cut = re.search(r'(?<=[a-zA-Z0-9\)\]])\.(\s|$)', head)
    if cut:
        head = head[:cut.start()]
    return head.strip(' .')


def source_line(file_name, line_number):
    """Returns the project source line a stack frame points at."""
    for path in glob.glob('src/**/%s' % file_name, recursive=True):
        try:
            lines = open(path, encoding='utf-8', errors='replace').read().split('\n')
        except OSError:
            continue
        if 0 < line_number <= len(lines):
            return path, lines[line_number - 1].strip()
    return None, None


def project_frames(stack):
    """Returns the stack frames that belong to this project, innermost first."""
    out = []
    for match in re.finditer(r'at (com\.intermarche\.[\w.$]+)\(([\w$]+\.java):(\d+)\)', stack or ''):
        out.append((match.group(1), match.group(2), int(match.group(3))))
    return out


def golden_diff(message):
    """Diffs a golden master's reference against the document just produced."""
    match = re.search(r"document '([\w-]+)' a chang", message or '')
    if not match:
        match = re.search(r"golden-actual/([\w-]+)\.txt", message or '')
    if not match:
        return None
    name = match.group(1)
    reference = os.path.join('src', 'test', 'resources', 'golden', name + '.txt')
    produced = os.path.join('target', 'golden-actual', name + '.txt')
    if not (os.path.exists(reference) and os.path.exists(produced)):
        return None
    before = open(reference, encoding='utf-8', errors='replace').read().split('\n')
    after = open(produced, encoding='utf-8', errors='replace').read().split('\n')
    lines = list(difflib.unified_diff(before, after, 'reference', 'produit', lineterm='', n=2))
    return '\n'.join(lines) if lines else None


def hint_for(text):
    """Returns the hypothesis matching a failure's signature, or None."""
    for pattern, hint in HINTS:
        if re.search(pattern, text or '', re.IGNORECASE | re.DOTALL):
            return hint
    return None


def condense(message):
    """Strips a Playwright timeout down to what it actually says.

    Such a message carries a kilometre of JS internals from the driver bundle
    and, buried at the end, the ONE useful sentence: the locator it waited for.
    Printed whole it hides its own answer.
    """
    text = message or ''
    if 'Call log' not in text and 'coreBundle.js' not in text:
        return re.sub(r'\s+', ' ', text).strip()
    kept = []
    head = re.search(r'(Timeout [^.]*exceeded\.?)', text)
    if head:
        kept.append(head.group(1))
    for waiting in re.findall(r'waiting for ([^\n]*)', text):
        waiting = re.sub(r'\s+', ' ', waiting).strip(' -')
        if waiting and waiting not in kept:
            kept.append('en attente de ' + waiting)
    if not kept:
        return re.sub(r'\s+', ' ', text).strip()[:400]
    return '\n'.join(kept)


def wrap(text, indent='       '):
    """Indents a block so it reads as one answer under its heading."""
    return '\n'.join(indent + line for line in (text or '').split('\n'))


def error_lines():
    """Returns the Maven [ERROR] lines, which carry a build or startup failure."""
    if not os.path.exists(LOG):
        return []
    out = []
    for line in open(LOG, encoding='utf-8', errors='replace'):
        line = line.rstrip('\n')
        if '[ERROR]' in line and 'To see the full stack trace' not in line \
                and 'Re-run Maven' not in line and 'For more information' not in line \
                and not line.strip().endswith('[ERROR]'):
            out.append(line)
    return out[:40]


suites = sorted(glob.glob(os.path.join(REPORT_DIR, 'TEST-*.xml')))
totals = {'tests': 0, 'failures': 0, 'errors': 0, 'skipped': 0}
rows = []
broken = []

for path in suites:
    try:
        root = ET.parse(path).getroot()
    except ET.ParseError:
        continue
    name = root.get('name', os.path.basename(path))
    counts = {k: int(float(root.get(k, 0) or 0)) for k in totals}
    for k in totals:
        totals[k] += counts[k]
    group = name.split('.')[-1]
    scenarios = []
    for case in root.iter('testcase'):
        entry = {
            'class': (case.get('classname') or name).split('.')[-1],
            'method': case.get('name', '?'),
            'time': float(case.get('time', 0) or 0),
            'status': 'OK',
            'kind': None,
            'type': '',
            'message': '',
            'stack': '',
        }
        skipped = case.find('skipped')
        if skipped is not None:
            entry['status'] = 'IGNORE'
            entry['message'] = skipped.get('message', '') or (skipped.text or '')
        for kind in ('failure', 'error'):
            node = case.find(kind)
            if node is None:
                continue
            entry['status'] = 'ECHEC' if kind == 'failure' else 'ERREUR'
            entry['kind'] = kind
            entry['type'] = node.get('type', '')
            entry['message'] = node.get('message', '') or ''
            entry['stack'] = node.text or ''
            break
        scenarios.append(entry)
        if entry['kind']:
            broken.append(entry)
    rows.append((group, counts, float(root.get('time', 0) or 0), scenarios))

print(BAR)
print('RAPPORT E2E')
print(BAR)

if not suites:
    print()
    print("Aucun rapport failsafe : aucun test e2e n'a tourne.")
    print("Ce n'est donc pas un echec de test mais un echec AVANT les tests —")
    print("compilation, demarrage de l'application, ou filtre -Dit.test sans correspondance.")
    errors = error_lines()
    if errors:
        print()
        print('Les lignes [ERROR] du journal :')
        print(SUB)
        for line in errors:
            print('  ' + line)
    print()
    print('Journal complet : ' + LOG)
    sys.exit(0)

# The result of EVERY scenario, group by group: a run whose report only named
# the failures left the reader unable to tell a scenario that passed from one
# that never ran.
width = 10
for _, _, _, scenarios in rows:
    for entry in scenarios:
        width = max(width, len(entry['method']))
width = min(width, 52)

for group, counts, seconds, scenarios in rows:
    passed = sum(1 for e in scenarios if e['status'] == 'OK')
    print()
    print('%s   %d %s : %d OK, %d echecs, %d erreurs, %d ignores   (%.1fs)'
          % (group, len(scenarios), 'scenario' if len(scenarios) == 1 else 'scenarios',
             passed, counts['failures'], counts['errors'], counts['skipped'], seconds))
    print(SUB)
    if not scenarios:
        print('  (aucun scenario dans ce rapport)')
        continue
    for entry in scenarios:
        label = title_of(entry['class'], entry['method'])
        if entry['status'] == 'IGNORE' and entry['message']:
            reason = re.sub(r'\s+', ' ', entry['message']).strip()
            label = (label + '  [' + reason + ']') if label else ('[' + reason + ']')
        print('  %-7s %7.1fs  %-*s  %s'
              % (entry['status'], entry['time'], width, entry['method'], label))

print()
print(SUB)
print('TOTAL : %d scenarios, %d OK, %d echecs, %d erreurs, %d ignores'
      % (totals['tests'], totals['tests'] - totals['failures'] - totals['errors'] - totals['skipped'],
         totals['failures'], totals['errors'], totals['skipped']))
print(SUB)

if not broken:
    print()
    print('Tout passe.')
    sys.exit(0)

print()
print('%d cas en echec, expliques un par un.' % len(broken))

for index, case in enumerate(broken, start=1):
    print()
    print(BAR)
    print('%d/%d  %s.%s   (%.1fs)' % (index, len(broken), case['class'], case['method'], case['time']))
    print(BAR)

    path, doc = javadoc_of(case['class'], case['method'])
    print()
    print('  CE QUE LE TEST VERIFIAIT')
    if doc:
        print(wrap(doc))
    else:
        print(wrap('Pas de commentaire sur ce cas%s.'
                   % ((' (' + path + ')') if path else '')))

    print()
    print('  CE QUI A ECHOUE')
    label = 'Echec d\'assertion' if case['kind'] == 'failure' else 'Erreur'
    print(wrap('%s : %s' % (label, case['type'] or '?')))
    message = condense(case['message'])
    expected = re.search(r'expected: ?<(.*?)> but was: ?<(.*?)>', case['message'] or '', re.DOTALL)
    if expected:
        head = case['message'][:expected.start()].strip().rstrip('=>').strip()
        if head:
            print(wrap(re.sub(r'\s+', ' ', head)))
        print(wrap('attendu : ' + re.sub(r'\s+', ' ', expected.group(1))))
        print(wrap('obtenu  : ' + re.sub(r'\s+', ' ', expected.group(2))))
    elif message:
        print(wrap(message))

    frames = project_frames(case['stack'])
    if frames:
        print()
        print('  OU')
        for where, file_name, number in frames[:4]:
            source, text = source_line(file_name, number)
            print(wrap('%s:%d' % (source or file_name, number)))
            if text:
                print(wrap('| ' + text, indent='         '))

    diff = golden_diff(case['message'])
    if diff:
        print()
        print('  DIFF DU DOCUMENT  (- reference enregistree, + document produit)')
        print(wrap(diff))

    hint = hint_for((case['message'] or '') + '\n' + (case['type'] or '') + '\n' + (case['stack'] or ''))
    if hint:
        print()
        print('  PISTE  (hypothese, a confirmer)')
        print(wrap(hint))

print()
print(BAR)
print('Journal complet : %s' % LOG)
print('Ce rapport      : target/e2e-report.txt')
print(BAR)
PY

exit "$STATUS"
