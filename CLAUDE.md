# Test generation conventions

## Test architecture — NON-NEGOTIABLE
- Plain unit tests only: JUnit 5 + Mockito. NEVER @QuarkusTest,
  never H2, never boot the application for a class-level test.
- Mock all collaborators.
- Panache entities are NOT bytecode-enhanced under plain mvn test:
  static finders resolve to PanacheEntityBase — mock them with
  Mockito.mockStatic(PanacheEntityBase.class), and neutralize
  persist() with Mockito.mockConstruction(<Entity>.class).
- Static mocks go in try-with-resources blocks.
- Each test must be fully isolated: no shared state, no ordering
  dependency, assertions on absolute expected values.

## Style
- Code and comments in English.
- Javadoc on EVERY method without exception, test methods and
  private helpers included.
- Assertions: org.junit.jupiter.api.Assertions only — never AssertJ.
- No blank lines inside method bodies.
- One complete, compilable test class per production class.

## Coverage
- Target: 100% JaCoCo branch coverage on the target class.
- Systematically cover BOTH arms (null and non-null) of every
  ternary and null guard — do not wait for a JaCoCo re-run.
- Always report the branch count, not only the percentage.

## Scope — STRICT
- Never modify anything under src/main. If a bug or an obstacle
  to testability is found, stop and report it in one line.
- Touch only the test class being generated.

## Per-class workflow
1. Read the target class (and only what you actually need).
2. Enumerate every branch before writing.
3. Write the test class → mvn -Dtest=XTest test → fix until green.
4. mvn verify → read JaCoCo for the class → fill missing branches.
5. Report: branch count, coverage, files read, iterations.

## E2E scenario tests (*IT classes) — separate contract
- Spec source: e2e-scenarios.md at project root. One test class per
  group letter (e.g. GroupAIT in package com.intermarche.pos.e2e),
  one @Test per scenario, the scenario id in the method name and its
  Javadoc.
- Architecture: @QuarkusTest + Playwright (headless Chromium) via the
  quarkus-playwright extension. Scenarios are played THROUGH THE
  SCREEN exactly as the cashier acts: navigation, taps, touch-keypad
  input. Client-side JS (calculator, polls, overlays) really runs and
  is therefore assertable — no more client-side residue category.
- Selectors doctrine: prefer the visible texts the catalog quotes and
  stable ids; never deep structural CSS paths. Rely on Playwright
  auto-waiting; never sleep-based waits.
- Login is performed on the lock screen (badge + PIN via the on-screen
  keypad), not by posting forms. Reset between scenarios by navigating
  to /lock. A successful unlock fires the drawer-open pulse and the
  embedded simulator really opens the till, so the login recipe ends
  with the cashier's physical close: POST /api/hardware/drawer/close on
  the hardware bus before navigating to any @DrawerMustBeClosed screen
  (sale, pay, manual, fruits, fidelity, reprint, theme), else the
  drawer guard diverts to /drawer-error. /lock and /session are not
  guarded and stay reachable drawer-open.
- Login recipe (reusable across groups): navigate /lock FIRST (it logs
  out and clears the badge mailbox), THEN present the badge on the
  hardware bus — POST /api/pos/scan (text/plain, the 8-digit code); the
  /lock-data poll (1s) flips the overlay to PIN entry. Wait on the
  catalog text `Entrez votre code PIN :` (never a sleep), tap the PIN
  on the #keyboardArea buttons (by exact role+name), submit with
  #actionBtn. Auto-wait only.
- The badge is a HARDWARE gesture, not a form: the IDENTIFIANT keyboard
  is alpha-only (no digits), so a numeric badge cannot be typed —
  always present it through the scan bus (POST /api/pos/scan).
- Post-unlock routing: no open session → SESSION screen; session
  already open (A2) or training mode → straight to the SALE screen (no
  session detour). Assert the sale landing by the text `TOTAL À PAYER`,
  never by the session screen.
- Selector anchors (group A): lock — overlay #resumeScreen, keypad
  #keyboardArea, submit #actionBtn, PIN prompt `Entrez votre code
  PIN :`; session — native float field #openingFloat (no numpad, set
  via fill), button `OUVRIR LA SESSION`, heading `Aucune session
  ouverte`; sale — `TOTAL À PAYER`, operator in #opInfo.
- Assert what the catalog states: rendered HTML with the EXACT
  user-facing texts, database state, technical event journal.
  Client-side-only behaviors (JS poll cadence, visual stacking) are
  asserted server-side when possible, otherwise listed as justified
  residue.
- Isolation: each scenario builds its own prerequisites (session,
  cart lines) through the HTTP surface and must not depend on another
  scenario. Never assert absolute ticket/session numbers — fiscal
  counters are perpetual; assert formats and deltas.
- Implement ONLY [S]-tagged scenarios in this campaign; skip [V]/[N]
  ones and list their ids as justified residue.
- The oracle of this campaign is the scenario checklist, not JaCoCo.
- Run command: ITs are gated by <skipITs>true</skipITs> in pom.xml
  (only the `native` profile flips it). The campaign command MUST be
  `mvn -q verify -DskipUTs=true -Dit.test=GroupAIT -DskipITs=false`,
  else failsafe reports "Tests are skipped" and BUILD SUCCESS is a
  false green. -DskipUTs=true skips the whole unit suite (wired to
  maven-surefire-plugin's skip; property default false) so the rafale
  does not re-run the ~120 unit tests on every IT run; the ITs still
  run under failsafe.
- Fixed cost per IT: ~10–26s of Quarkus boot + browser-context startup
  (cold vs warm), nearly independent of the number of gestures. Group
  ALL of a letter's scenarios into ONE @QuarkusTest class — one boot
  per group — rather than splitting into many classes.
- Browser provisioning: the first run on a fresh environment downloads
  the Playwright bundle (Chromium + Firefox + WebKit, ~300MB) before
  any test; budget it once — later runs reuse the ms-playwright cache.
- Shared QuarkusTestProfile for every group: pin pos.terminal.id=C04
  (default POS01 breaks the C04-Sxxxxx assertions) and point
  quarkus.rest-client.hardware-api.url at the app's OWN base URL
  (http://localhost:8081, the @QuarkusTest port) so hardware calls hit
  the embedded simulator (MockHardwareResource) and return 200 — no
  ConnectException/404 stack traces in the logs. That simulator lives
  in src/mock/java and is wired into the TEST classpath by
  build-helper-maven-plugin (add-test-source); it never enters the
  production jar. Because the drawer is now REAL, the unlock pulse
  actually opens it — see the login recipe for the mandatory
  drawer-close gesture that keeps the drawer guard from diverting
  guarded screens to /drawer-error.
- Session number is `%s-S%05d` -> "C04-S00001", 10 chars. Assert with
  regex `C04-S\d{5}`, never a fixed length or an absolute counter.
- Seed (DataInitializer, runs under build profile `test`):
  cashier badge 12341234 / PIN 1234 / jdupont / "Jean Dupont";
  managers 11111111 / 1111 (mcurie), 00000000 / 0000 (admin, ADMIN).
- Public HTTP surface, group A: login POST /action/unlock
  (form login,password); open session POST /action/session/open
  (form openingFloat = euros String, comma ok); reset auth GET /lock.
- Auth state is a server-side singleton (PosState), NOT a cookie: no
  RestAssured session filter needed, but state LEAKS between tests -
  GET /lock at the start of each scenario. RestAssured follows the 303
  redirects by default; @QuarkusTest auto-wires the port.
- Assert DB state with CashSession.findOpenByTerminal("C04") wrapped in
  QuarkusTransaction.requiringNew().call(...).
- Screen names in the catalog are logical names, not displayed texts:
  assert the texts the catalog quotes verbatim, never the screen titles.
