# Group H — Retours (customer refunds) — e2e campaign report

**Class:** `src/test/java/com/intermarche/pos/e2e/GroupHIT.java`
**Run command:** `mvn -q verify -DskipUTs=true -Dit.test=GroupHIT -DskipITs=false`
**Result:** `Tests run: 5, Failures: 0, Errors: 0, Skipped: 0` — BUILD SUCCESS (one Quarkus boot, ~52 s).

## Scenarios covered (all [S], H1–H5)

| Id | Title | What is driven through the screen | Oracles |
|----|-------|-----------------------------------|---------|
| **H1** | Retour nominal espèces | search → open → return full line → CASH → manager endorsement | `Refund` row (CASH, CLOSED, 6,00 €), VAT restituted (HT 5,00 / TVA 1,00), `REFUND_CREATED` +1, theoretical drawer −6,00 € via `CashSessionService.buildReport` |
| **H2** | Anti-double remboursement | full refund of a qty-2 line, then a second attempt on the exhausted ticket | staging stepper caps the remainder at **0** (`PosState.refund`), method request refused with `RIEN À REMBOURSER`, no endorsement opened, still exactly 1 refund in DB |
| **H3** | Plafond ticket avec montant libre | open → free-amount edit → key 10,00 € (> 6,00 € total) → CASH → endorse | staging stays **uncapped** (10 €), the transaction re-check refuses with `PLAFOND DU TICKET DÉPASSÉ (DÉJÀ REMBOURSÉ : 0.00 €)` and **rolls back** — no refund persisted |
| **H4** | Bon de remboursement | VOUCHER refund → printed store voucher → scanned on a later sale's payment | printed paper carries `BON D'ACHAT`, `(scannable en caisse)` and a `50\d{12}` number; scanning it during payment registers a `Bon enseigne` voucher payment of 6,00 €, leaving 6,00 € due on the 12,00 € sale |
| **H5** | Retour en formation → bloqué | endorsed training toggle ON → open a real ticket → request CASH | `RETOURS INDISPONIBLES EN FORMATION` on screen and on `PosState.refund.errorMessage`, no endorsement, no refund document; training toggled back OFF for isolation |

## Attendus enumerated before writing

- **Screen texts:** `Montant Retour`, `RETOUR CLIENT`, search result rows (`/return/select/…`), `RIEN À REMBOURSER`, `PLAFOND DU TICKET DÉPASSÉ (DÉJÀ REMBOURSÉ : 0.00 €)`, `RETOURS INDISPONIBLES EN FORMATION`, `BON D'ACHAT`, `(scannable en caisse)`, endorsement modal `Code PIN :` / `SUIVANT`.
- **DB state:** `Refund` (method, status CLOSED, `totalAmount`, `totalVat`, `totalExcludingTax`, `originalTicketId`, session attachment), refund count per ticket, `RefundLine` cap (`quantity` − already refunded).
- **Journal:** `REFUND_CREATED` (delta +1 for a created refund; unchanged for refused ones).
- **Derived:** theoretical drawer cash (`buildReport().theoreticalCash`), the store-voucher encoding `50` + `%08d`(refund id) + `%04d`(cents), voucher encashment via `VoucherScanHandler` (STORE_VOUCHER, ≤ 99,99 €).

## Files read

- Spec: `e2e-scenarios.md` (§H, and §O-C/O-D/O-E for the return-guard/voucher/modal wording).
- Templates: `return-search.html`, `return-detail.html`, `endorsement.html`.
- Production: `RefundResource`, `RefundService`, `RefundState`, `Refund` (+ `RefundMethod`/`RefundStatus`), `EndorsementResource`, `EndorsementService`, `TicketPrinterService` (`printRefund`, `printRefundVoucher`), `VoucherService`, `VoucherScanHandler`, `CouponType` seed (`loadCouponTypes`), `CashSessionService` (`buildReport`), `HomeService`/`HomeResource` (training toggle), `PosState`, `PaymentState`, `TicketNumberService`, `DataInitializer` (seed HUILE 6,00 € @ 20 %).
- Templates of siblings for the recipe: `GroupGIT`, `GroupCIT`, `E2eTestProfile`.

## Iterations to green

1. **v1 → strict-mode collision.** `approveEndorsementWithManager` clicked `getByRole(BUTTON,"SUIVANT")` page-wide; the return **detail** page carries a disabled ticket-pagination `SUIVANT` besides the modal's. Fixed by scoping the click to `#endorseModal`. (The uncaught exception also left `endorsement.active=true`, which stole the next scenarios' badges — a cascade that vanished once H1 passed.)
2. **v2 → hidden `+` stepper.** Dialling quantity 2 by calling the select-then-add helper twice re-clicked the line link, which **toggles** the selection off (hiding the controls). Split into `selectReturnLine` (once) + `addReturnQty` (N times); each `+` reload keeps the line selected.
3. **v3 → green.** All five pass; confirmed on a clean re-run.

## Hard points

- **The method buttons don't navigate.** `ESPÈCES/CARTE/BON/CAGNOTTE` carry `onclick="return submitAction(this)"`, which submits the staging form and returns `false` — so a click **commits the typed amount** but never reaches `/return/pay/<method>`. The faithful "press the method" gesture is therefore the link's own href (`GET /return/pay/cash|voucher`), navigated directly after the amount/quantity is staged. Documented inline (esp. H3, where ESPÈCES first commits the free amount, then `/return/pay/cash` requests it).
- **Ticket search entry.** The search keypad auto-submits the form on every keystroke past 3 chars (a reload per key), which is fragile for a full `C04-00000000`-style number. Since `PosState` is a server-side singleton, the search is run through the search form's own endpoint (`POST /return/search`, identical to what the keypad fires), its results asserted in the returned HTML, then the ticket opened by its **result row** (`/return/select/{id}`) — the "search then click the result" gesture, kept deterministic.
- **Theoretical drawer while OPEN.** `CashSession.theoreticalAmount` is only stamped at the Z close; the live value is computed by `CashSessionService.buildReport(session).theoreticalCash` (float + cash payments − cash refunds), so H1 asserts a **before/after delta** of −6,00 € around its own refund rather than an absolute.
- **Endorsement `SUIVANT` ambiguity** on the return detail page (see iteration 1).

## Justified residue

- **H2 per-line *transaction* re-check race — architecturally unreachable through the screen.** The `QUANTITÉ DÉJÀ REMBOURSÉE` guard fires only when a staged line quantity, valid at staging, becomes stale because *another* refund landed on the same line **between** staging and execution. The register exposes a single server-side `PosState` (one operator, one staging area), and staging always re-caps to the current remainder, so a stale-but-positive staged quantity cannot be produced via the HTTP/screen surface; forcing it would require injecting an intervening `Refund` via DB, which the campaign restricts to timestamp aging. The **staging** per-line guard is fully proven (cap → `RIEN À REMBOURSER`), and the transaction re-check *with full rollback* is proven from the screen for the ticket-level cap in **H3** (`PLAFOND DU TICKET DÉPASSÉ`), so the "guards re-verify in transaction, rollback complete" contract is demonstrated.
- **[V]/[N] ids:** none in group H — every H scenario is `[S]`.

## Cross-references honoured

- H4's store voucher closes the loop back through `VoucherScanHandler` as a `STORE_VOUCHER` payment (the O-D/§payment surface).
- H5's training block is the return-flow half of the group-J "actions bloquées en formation" contract (endorsed toggle via `TRAINING_TOGGLE`).
- PARKED-at-Z (H2/spec cross to I3) is out of group H's scope.
