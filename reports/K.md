# Group K — Reprise & robustesse (E2E campaign report)

**Class:** `src/test/java/com/intermarche/pos/e2e/GroupKIT.java`
**Run:** `mvn -q verify -DskipUTs=true -Dit.test=GroupKIT -DskipITs=false`
**Result:** `Tests run: 3, Failures: 0, Errors: 0, Skipped: 0` — BUILD SUCCESS (one Quarkus boot, ~26 s wall).

## Scenarios covered (all `[S]`, all implemented)

| Id | Scenario | Oracles exercised |
|----|----------|-------------------|
| **K1** | Crash en plein panier → panier restauré à l'identique (uids, gestes structurés, fidélité), draft réconcilié, pas de doublon | Three-line cart (endorsed 1,00 € REMISE line, plain line, weighed PLU line) + fidelity card built through the screen; snapshot the uids, draft id, total, fidelity, `openDraftCount==1`, and the durable draft (3 lines + `fidelityCard`). Crash = `PosState.clearTicket()` (memory wiped: items empty, `ticketDbId` null, fidelity off). Restart = `TicketRecoveryService.recover()`. After: **same uids in the same order**, `modifierType=REMISE`/`modifierValue=1`, weighed line `plu=4020`/`quantity=1,250`, fidelity reattached (`CARD_1`), same total, **same draft id reconciled**, `openDraftCount` still `1` (no duplicate). The restored cart re-renders on the sale screen (`HUILE`, `POMMES GOLDEN`, `#fidIcon.active`). |
| **K2** | Crash en plein paiement → draft OPEN, paiements persistés, complétion possible | Plain 12,00 € cart, enter pay, register a **partial 5,00 € CARD** payment (virtual-TPE `payThroughScreen` + `paymentService.confirmPendingCard`), persisted on the draft (`draftPaymentCount==1`, amount 5,00). Crash = `clearTicket()` (in-memory payments emptied). Restart = `recover()`: same draft reattached, draft still **OPEN**, the CARD payment **rebuilt from the DB**, `transactionComplete==false`, 7,00 € still due. Completion then really carried through: cash tender of the balance completes and `finishSale` drives the ticket to **CLOSED**. |
| **K3** | Matériel dégradé — imprimante coupée (vente continue), capteur tiroir mort (garde désactivée), afficheur coupé (silencieux) | **Printer:** `toggle-paper` → `NO_PAPER`; the sale completes (`TRANSACTION TERMINÉE`) and CLOSES fiscally, an explicit `IMPRIMER` prints onto the dead printer (swallowed — buffer carries no ticket number, status still `NO_PAPER`). **Drawer sensor:** baseline — drawer physically OPEN + live sensor diverts `/` to `/drawer-error`; kill the sensor (`toggle-sensor` → `GET /drawer/status` 503 → `isDrawerOpen()` fail-safe false) → the SAME guarded `/` reaches the sale (`TOTAL À PAYER`), guard disabled, no brick. **Display:** `display/toggle` → `POST /display` 503; a scan whose handler pushes to the dead display still lands its line and sets no `transientError` (silent). Every fault restored, the probe cart cancelled. |

## Files read

- `e2e-scenarios.md` (group K spec).
- Reference test classes: `GroupJIT.java` (login recipe, DB oracles, pay/finish helpers), `GroupCIT.java` (REMISE gesture + endorsement helpers), `GroupBIT.java` (weight-label weighed line), `GroupDIT.java` (fidelity scan, `#fidIcon.active`), `GroupFIT.java` (virtual-TPE card via `payThroughScreen` + `confirmPendingCard`), `E2eTestProfile.java`.
- Production: `TicketRecoveryService.java` (the `recover()`/`restoreDraft` reconciliation, `@Observes StartupEvent` boot hook), `TicketPersistenceService.java` (`syncDraft` write funnel, uid-keyed `reconcileLines`), `PosState.java` (`clearTicket()` — the memory wipe; "rebuilt from the draft at recovery"), `TicketState.java` (`TicketItem.uid`), `PaymentState.java` (`reset()`, `PaymentEntry`), `PaymentService.java` (`processCard`/`confirmPendingCard`/`handlePaymentWithChange`→`savePayment`, `finalizeTransaction`), `PaymentResource.java` (`@DrawerMustBeClosed`, `/action/print`, `/action/finish`), `AuthService.java` (logout = `clearTicket`, draft untouched), `HardwareService.java` (the degraded-mode philosophy: `printReceipt`/`isDrawerOpen`/`displayMessage` catch blocks), `HardwareClient.java`, `DrawerCheckFilter.java` + `DrawerMustBeClosed.java`, `TicketService.java` (`displayMessage` on scan), `TechnicalEvent.java` (event-type enum), `Ticket.java`/`TicketLine.java` (`TicketStatus`, `lineUid`).
- Templates: `drawer-error.html` (`TIROIR CAISSE`/`OUVERT`).
- Simulator: `src/mock/java/.../MockHardwareResource.java`.
- Seed: `DataInitializer.java` (Huile 3300000000006, Miel 3300000000024, Pommes PLU 4020 / EAN 3300000000001, fidelity `^789\d{12}$`).

## Crash-simulation doctrine

A `@QuarkusTest` cannot `kill -9` its own JVM, and it does not need to: the register's resilience lives in two facts the code states outright, not in the process lifecycle.
- **Durable from the first article.** `TicketPersistenceService.syncDraft` writes/updates an OPEN draft on every cart mutation and every payment.
- **Memory is disposable.** `PosState` is "memory only: the durable truth is the draft, this object is rebuilt from it at recovery."

So a crash is `PosState.clearTicket()` (exactly what `/lock` logout does to memory — `AuthService` clears the cart but NOT the persisted draft) and a restart is `TicketRecoveryService.recover()` (the very method the `@Observes StartupEvent` boot hook runs). The draft on disk is untouched by the wipe, so recovery rebuilds the cart/payments from the single source of truth — this is the faithful, deterministic stand-in for the real reboot.

## Simulator extension (src/mock, not src/main)

K3 needs three **hardware faults**. The embedded simulator only carried a printer fault toggle (`POST /printer/toggle-paper`, pre-existing) — it had no hook to make the drawer sensor or the display fail, and neither device can be failed through any production route. Two symmetric injectors were added to `MockHardwareResource` (test classpath only, never in the production jar), mirroring the printer toggle:

- `POST /api/hardware/drawer/toggle-sensor` → flips `GET /drawer/status` to **503**.
- `POST /api/hardware/display/toggle` → flips `POST /display` to **503**.

Default state is alive/200, so **no other group's behavior changes**. These 503s make the MP rest-client throw, which is exactly what the REAL `src/main` degraded-mode code catches (`isDrawerOpen()`→false, `displayMessage()`→swallow) — the same mechanism the pre-existing `printer/print` 503 already exercised. **No production code (src/main) was touched.** The run log confirms the real paths fired: `ERROR HardwareService: Erreur d'impression: HTTP 503` and `Erreur de communication avec le tiroir: HTTP 503` (both swallowed, no test impact).

## Iterations

1. **v1** — K2 green first run; K1 and K3 failed.
   - K1: `expected 3 but was 2` lines — a **fidelity card is not a cart line**. Added a third plain line (Miel) so the cart is `{REMISE, plain, weighed}` + fidelity card, and reindexed the weighed assertions to `items[2]`.
   - K3: `TRANSACTION TERMINÉE` timed out after the `IMPRIMER` click — the cash tender had opened the drawer and `PaymentResource` is `@DrawerMustBeClosed`, so `POST /action/print` diverted to `/drawer-error`. Added `closeDrawer()` before the print click.
2. **v2** — all three green.

## Hard points

- **`clearTicket()` == the logout wipe.** `AuthService.logout` calls `state.clearTicket()` and explicitly leaves the persisted draft alone; that is precisely the post-crash empty singleton, which is why the crash simulation uses `clearTicket()` (not a draft mutation) and why recovery has a durable draft to rebuild from.
- **Line uids are the identity end-to-end.** `TicketItem.uid` (a UUID minted in memory) is persisted as `TicketLine.lineUid` and restored verbatim by `restoreCart`; K1 asserts the exact uid list survives, which is what "restauré à l'identique" and "pas de doublon" (uid-keyed `reconcileLines`) actually mean.
- **`cutPaper` always appends a marker.** The mock's `printer/cut` ignores paper state and appends a `[COUPE PAPIER]` line, so a failed print leaves the cut marker in the buffer. K3 therefore asserts the **ticket number is absent** from the paper (+ status `NO_PAPER`), not that the buffer is empty.
- **Drawer baseline vs dead-sensor.** With a healthy-but-closed drawer the guard also passes, so "no divert" alone cannot distinguish "disabled because dead" from "closed". K3 first proves a **live sensor + OPEN drawer diverts** to `/drawer-error`, then kills the sensor and reaches the same screen with the drawer still open — isolating the fail-safe.
- **Partial card, then complete.** A full card auto-completes; K2 deliberately tenders a partial 5,00 € so the recovered state is genuinely mid-payment (draft OPEN, `transactionComplete==false`, 7,00 € due) and "complétion possible" is then proven by actually closing it.

## Justified residue & production gap

- **K3 "+ TechnicalEvent" on a printer failure — production gap (reported, not asserted).** Per CLAUDE.md scope ("if a bug or an obstacle to testability is found, stop and report it in one line"): **no `TechnicalEvent` is ever logged on a print/cut failure** — `HardwareService.printReceipt` only does `LOGGER.error(...)`, and the `TechnicalEvent.EventType` enum has no `PRINTER`/`DRAWER`/`DISPLAY` value at all. The observable, real contract of "imprimante coupée → vente continue" is fully asserted (the sale completes and the ticket reaches **CLOSED** while the receipt reaches no paper); the "+ TechnicalEvent" clause has no code behind it and would require a `src/main` change, which is out of scope.
- **No `[V]`/`[N]` scenarios in group K** — every K id is `[S]` and implemented.
