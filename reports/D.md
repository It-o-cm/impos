# Group D — Fidélité — E2E campaign report

**Class:** `src/test/java/com/intermarche/pos/e2e/GroupDIT.java`
**Command:** `mvn -q verify -DskipUTs=true -Dit.test=GroupDIT -DskipITs=false`
**Result:** `Tests run: 3, Failures: 0, Errors: 0, Skipped: 0` — **BUILD SUCCESS** (one Quarkus boot, ~21 s).

## Scenarios covered (all `[S]`)

| Id | Scenario | Gesture (through the screen) | Oracles asserted |
|----|----------|------------------------------|------------------|
| **D1** | Scan carte en cours de panier | Scan a product then the card (`789…`, 15 digits) on the bus `POST /api/pos/scan` | Screen: `#fidIcon.active` lit by the 1s poll. In-memory: `PosState.fidelity.active` + exact `label`. **Durable draft:** `Ticket.fidelityCard == card` in H2 (the scan-driven sync copies it — the precondition the boot recovery reads back). |
| **D2** | Saisie manuelle (fallback) + dernière carte gagne | Type the card on `/fidelity` numpad, VALIDER (fallback path) | Icon lit + attached label after the returned home page; a **second** manual entry replaces the first (`label == CARD_2`, last presented wins); a later scan syncs the winning card onto the draft. |
| **D3** | Carte sur écran de lock → inerte | Navigate `/lock` (locked), scan the card on the bus | `fidelity.active == false`, empty `label`, `isLocked()` still true, overlay never flips to PIN entry (`Entrez votre code PIN :` count 0) — the card is neither a badge nor a sale gesture while locked. |

## How the surface works (verified in src/main, read-only)

- **Recognition:** `scan.pattern.fidelity = ^789\d{12}$` (application.properties) → `FidelityScanHandler` (`@Priority(1)`), inert when `isLocked()`.
- **Attachment:** `FidelityService.validateCard` → `FidelityState.assignCard` (guards null / len ≤ 2) + `state.touch()` (wakes the 1s poll; `#fidIcon` toggled from `HomeResource`'s `fidelityActive`).
- **Persistence:** `TicketService.processScan` runs a single sync (`syncAndRevalue`) after the handler chain **whenever a draft exists** (`ticketDbId != null`), and `TicketPersistenceService` writes `ticket.fidelityCard = active ? label : null`. So the **scan** path persists immediately (D1); the **manual** POST path (`FidelityResource`) attaches in-memory only, so D2 fires a follow-up scan to drive the sync that lands the card on the draft.
- **Recovery:** `TicketRecoveryService` reads `draft.fidelityCard` back into state on boot — the "survit à un redémarrage" contract; the durable draft asserted in D1/D2 is exactly its input.

## Files read

- `e2e-scenarios.md` (group D spec + shared `[S]` conventions).
- Test pattern: `GroupBIT.java`, `E2eTestProfile.java`; lock anchors cross-checked in `GroupAIT.java`.
- Production surface: `FidelityScanHandler`, `FidelityService`, `FidelityState`, `FidelityResource`, `TicketService`, `TicketPersistenceService`, `TicketRecoveryService`, `PosState`, `Ticket`, `HomeResource`.
- Templates: `main.html` (`#fidIcon`, poll `fidelityActive`), `fidelity.html` (`#fidKeyboardArea`, VALIDER), `lock.html` (`CAISSE FERMÉE`, `#resumeScreen`, PIN-entry hint).
- `application.properties` (`scan.pattern.fidelity`).

## Iterations

1. First run: D1 & D2 green; D3 failed — waited on `#resumeScreen` which stays `display:none` until `showResume()`/badge/error (it is not shown on a plain `/lock` load).
2. Fix: anchor D3 on the initially-visible `CAISSE FERMÉE` (closed-lane) screen. **All green.**

## Hard points

- **Scan vs manual sync asymmetry.** Only `processScan` funnels through `syncAndRevalue`; the manual `/action/fidelity` POST does not. D2 therefore needs a trailing scan to make the "persists on draft" half observable — asserting the durable card straight after a manual entry would have been a false negative.
- **Lock overlay visibility.** `#resumeScreen` is a hidden overlay activated only by JS (badge poll / error / `S'IDENTIFIER` tap). The stable visible lock anchor is `CAISSE FERMÉE`.
- **D3 meaningful baseline.** `/lock` logs out and clears the transactional state, so `fidelity.active` is already false before the scan; D3's value is asserting it **stays** false after the card AND that the overlay never flips to PIN (a 15-digit card is not the 8-digit badge), plus `isLocked()`.
- **No absolute counters.** Session asserted only via `findOpenByTerminal("C04")`; the draft is read by the in-memory `ticketDbId`, never by an absolute row.

## Justified residue

- **Restart survival (D1, "croiser K1").** A real `kill -9` + reboot cannot be played inside a single `@QuarkusTest`. Its durable precondition — the card written on the OPEN draft (`Ticket.fidelityCard`), which `TicketRecoveryService` reads back on boot — is asserted here; the actual crash/recovery replay is group K's remit (K1).
- **`[V]`/`[N]` scenarios:** none in group D — every D scenario is `[S]`, so there is no skipped-infrastructure residue for this letter.
