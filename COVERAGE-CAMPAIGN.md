# Coverage campaign — impos

Objective: **100 %** of branches AND lines (JaCoCo) and 100 % of branches (vitest/istanbul),
with no exclusion, no `@Generated`, no assertion-free test. The gate in `pom.xml` is raised
from 0.99 to 1.00 and the vitest config carries a 100 % threshold; neither ever goes down.

## Baseline (2026-11-10, `mvn -o clean verify -DskipITs=true`)

| Counter | Covered / Total | % |
|---|---|---|
| Java BRANCH | 20240 / 20240 | 100.0000 % |
| Java LINE | 37768 / 37770 | 99.9947 % |
| JS branch (7 of 8 files attributed) | 116 / 116 | 100 % |

The pom gate comment claimed 99.72 % / 99.79 %; the tree had already been pushed far higher.
The real residue was two Java lines and one un-attributed JS file.

## Work-list (classes/files with a missed branch or line)

| File | Missed branch | Missed line | Resolution |
|---|---|---|---|
| `com.intermarche.pos.domain.store.MenuLayout` | 0 | 2 | defensive `toJson()` catch — minimal seam + test |
| `pos/expense-tender.js` | (unmeasured) | (unmeasured) | lost istanbul attribution (bust query) — harness fix |

`MenuLayout` is a **copied class** (identical to imedge's `com.intermarche.edge.domain.store.MenuLayout`
except package/imports); the src/main and test changes are mirrored there character-for-character.

## Final (2026-11-10)

| Counter | Covered / Total | % |
|---|---|---|
| Java BRANCH | 20240 / 20240 | 100 % |
| Java LINE | 37770 / 37770 | 100 % |
| JS branch (8 of 8 files attributed) | 124 / 124 | 100 % |
| JS statement | 293 / 293 | 100 % |

Remaining classes with a gap: **0**.

## src/main changes (each minimal, behaviour-preserving, one line of justification)

- `domain/store/MenuLayout.java`: `MAPPER` made package-private non-final (was `private static final`)
  — seam so a unit test can swap in a mapper that throws and drive the defensive `toJson()` catch
  that no real layout can reach (case 3). Mirrored in imedge.

## Test-infra changes (not src/main)

- `src/test/js/harness.js`: `loadScript` no longer appends a `?bust=` query to the import URL.
  The query made istanbul attribute coverage to `…/expense-tender.js?bust=a`, which the
  `pos/*.js` include does not match, so the file vanished from the report; re-evaluation is
  already driven by the caller's `vi.resetModules()`, so dropping the query keeps the fresh
  boot while restoring attribution to the real source path.
