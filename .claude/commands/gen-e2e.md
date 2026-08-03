Write the full e2e scenario test class for $ARGUMENTS following CLAUDE.md
(section "E2E scenario tests (*IT classes)"), then make it pass. One test
class per group letter in package com.intermarche.pos.e2e, one @Test per
[S]-tagged scenario, driving ONLY through the public HTTP surface. Read
the group's scenarios in e2e-scenarios.md, enumerate the attendus (screen
texts, DB state, journal), write the class, then run
mvn -q verify -DskipUTs=true -Dit.test=<ClassIT> -DskipITs=false until green. Finish with
the report: scenarios covered, files read, iterations, hard points, and
any justified residue ([V]/[N] ids skipped). Write the full report to
reports/<group>.md (create the directory if needed).
