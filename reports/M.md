# Group M — Thèmes — E2E campaign report

**Class:** `src/test/java/com/intermarche/pos/e2e/GroupMIT.java`
**Command:** `mvn -q verify -DskipUTs=true -Dit.test=GroupMIT -DskipITs=false`
**Result:** `Tests run: 4, Failures: 0, Errors: 0, Skipped: 0` — BUILD SUCCESS (~25 s, one boot).

## Scenarios covered (all `[S]`, nothing skipped)

| Id | Title | What is proven, and the oracle |
|----|-------|--------------------------------|
| **M1** | Cascade | The full resolution chain `Employee.theme` → `Store.theme` → built-in `sombre`. Marie (seeded pref `clair`) → every screen light from the unlock (sale, session, theme-select); Jean (no pref, unset store) → the built-in `sombre`; then `Store.theme=clair` (DB) flips Jean to `clair` **without writing his row** (asserted `Employee.theme` still null), and clearing it drops him back to `sombre`. Oracle: rendered `body[data-theme]` on each screen + `Employee.theme`/`Store.theme` in the DB. |
| **M2** | Sélecteur | Reached through the screen (AUTRES… → THÈME D'AFFICHAGE). The selector highlights the current theme (`SOMBRE` for Jean); picking `CLAIR` renders the sale light *immediately* on the redirect and persists `Employee.theme=clair`; re-opening highlights `CLAIR`; `DÉFAUT MAGASIN` posts the empty value, clears the preference (`Employee.theme` back to null) and the cascade resumes to the store default (`sombre`). Oracle: `body[data-theme]`, the `.current` highlight class, and `Employee.theme`. |
| **M3** | Iso-rendu sombre | The "four passes" contract: `sombre` **is** `:root` (no `[data-theme]` override block). Read live in the browser, the full 15-token set on a `sombre` page is **byte-identical** to the same page with `data-theme` stripped (the pre-theming `:root` baseline) → zero visual difference. Live control: switching the same body to `clair` genuinely moves the tokens (mechanism not inert). Oracle: `getComputedStyle` custom-property values. |
| **M4** | Couverture clair | Browsed as Marie (`clair`), **12 screens** (sale, session, search, manual, fruits, fidelity, reprint, return, parked, pin-change, supervisor, theme-select) each render `data-theme="clair"` with a light background (relative luminance > 0.5 — none left dark). Then the enumerated surfaces — the sale screen's muted buttons (bars/rows/muted) and the search screen's on-load keypad digit keys (claviers) — are read on the live CSS and all resolve light. Oracle: `body[data-theme]` + `getComputedStyle` background luminance per element. |

## Files read

- `e2e-scenarios.md` (§M) — the scenario checklist / oracle.
- `src/main/java/.../ui/ThemeService.java` — resolution chain, `AVAILABLE_THEMES`, `setThemeForOperator`, the `{posTheme}` global.
- `src/main/java/.../ui/ThemeResource.java` — `GET /theme-select`, `POST /action/theme` (blank = clear), `@DrawerMustBeClosed`.
- `src/main/resources/templates/theme.html` — `:root` dark tokens + `[data-theme="clair"]` override block; `.pos-key`, `.btn-muted` colours.
- `src/main/resources/templates/theme-select.html` — selector markup (`{t.toUpperCase()}` buttons, `.current`, `DÉFAUT MAGASIN` empty post).
- `src/main/resources/templates/main.html` — menu path (`AUTRES...` → `/action/menu/secondary`; `THÈME D'AFFICHAGE` → `/theme-select`).
- `src/main/java/.../domain/Employee.java`, `Store.java` — the two `theme` columns.
- `src/main/java/.../domain/util/DataInitializer.java` — seed (Marie `clair`, Jean none, Store unset).
- `src/main/java/.../ui/home/HomeService.java` + `PosState.java` — `showSecondaryMenu` toggle/default.
- `src/main/resources/templates/keyboard-lib.html`, `manual.html`, `search.html`, `pin-change.html`, `fidelity.html`, `drawer-error.html`, and the browsed screens' headers — for selectors and stable anchors.
- Reference class `GroupJIT.java` and `E2eTestProfile.java` — login recipe, drawer gesture, DB-oracle idioms.

## Iterations

1. **Wrote the class** (M1–M4) and ran → M1 green; M2/M3/M4 red.
2. **Fixed anchors** — several M4 anchors (`Fruits & Légumes`, `RECHERCHER`) were `<title>` text, not visible; switched to visible headers (`FRUITS, LÉGUMES & FRAIS`, `Recherche produit`, `RÉIMPRESSION TICKET`, `RETOUR CLIENT`, …). Made the selector navigation href-based.
3. **Instrumented** with temporary `System.out` diagnostics → revealed the two real bugs (below).
4. **Fixed the two bugs**, removed diagnostics → all 4 green.
5. **Confirmed stability** — re-ran the full command twice more, green each time.

## Hard points

- **The secondary menu sticks open (server state).** `showSecondaryMenu` lives on the `PosState` singleton and is only reset by the `PRINCIPAL` route. After the first `AUTRES…`, every later sale render (including the redirect after choosing a theme) already shows the secondary menu, so the second `openThemeSelector` found no `AUTRES…` link. Fixed by making the helper menu-state-aware: it clicks `AUTRES…` **only when** the `THÈME D'AFFICHAGE` link is not already present, then follows the link. This was the cause of both the M2 failure *and* the M3 leak (M2 died after setting `Employee.theme=clair` but before `DÉFAUT MAGASIN` restored it, so M3's Jean login resolved `clair` instead of `sombre`).
- **Keypads render on demand.** `/manual`'s `#keyboardArea` is empty until a product is selected (`PosInput.setup` runs inside `selectProduct`), so a digit-key selector never appeared. `/search` mounts its keypad at page load, so M4's "claviers" check reads `#alphaKbArea .pos-key--digit` there instead.
- **`data-theme` alone is not enough for M3/M4.** The attribute proves *which* theme resolved, but M3 ("zero visual difference") and M4 ("nothing left dark") are pixel claims. They are asserted on the **live computed CSS** in headless Chromium (`getComputedStyle` token equality for M3; background-luminance per element for M4) — the token cascade really runs, so the pixels it produces are a real server-checkable oracle, no screenshot diffing needed.
- **`sombre = :root`.** There is no `[data-theme="sombre"]` block; the dark theme is the untouched `:root`. M3 leans on exactly this: `data-theme="sombre"` and *no* `data-theme` must compute identically.

## Scaffolding used (justified, outside the HTTP surface)

- `Store.theme` has **no HTTP route** to set it, so M1's middle-rung leg writes it via `QuarkusTransaction.requiringNew()` and restores it to null in the same scenario (asserted). Every other mutation goes through the screen. DB reads of `Employee.theme`/`Store.theme` are oracles, wrapped in `QuarkusTransaction`.
- The M3 `removeAttribute` / `setAttribute('data-theme', …)` calls are client-side DOM pokes to capture the "before/after" baseline — they mutate only the throwaway page, never server state.

## Justified residue

- **None** — group M is entirely `[S]` and M1–M4 are all implemented. The `[V]`/`[N]` tags do not appear in group M (they belong to groups E, L, and N). M4 walks the cashier's twelve reachable screens; the remaining full-page templates (customer display, dashboard, cash-count, digital-ticket) carry the **same single `data-theme` cascade** — one attribute flip re-themes an entire page, there is no per-element theme — so re-walking them would add no coverage. This is noted in the class Javadoc, not treated as a skipped scenario.
