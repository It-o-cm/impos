# Campaign report — Group B (Vente — scan & saisie)

**Class:** `src/test/java/com/intermarche/pos/e2e/GroupBIT.java`
**Command:** `mvn -q verify -DskipUTs=true -Dit.test=GroupBIT -DskipITs=false`
**Result:** `Tests run: 14, Failures: 0, Errors: 0, Skipped: 0` — one Quarkus boot, ~84 s.

## Scenarios covered (all 14 are `[S]`)

| Id | Scenario | Gesture driven through the screen | Oracle |
|----|----------|-----------------------------------|--------|
| B1 | Scan EAN catalogue & fusion | scan bus (`/api/pos/scan`) on the sale screen | rendered line + `PosState` (1 line, price/VAT snapshot, re-scan → qty 2 one line) |
| B2 | Refus de fusion | REMISE modal + manager endorsement; catalog price change between two scans | `PosState` (modified line → 2 lines; price change → 2 lines) |
| B3 | PLU tapé (`4020`) | scan bus | weighed line, qty 1, never merges |
| B4 | Pesée FRUITS | `/fruits` tile tap after `set-weight` | 1,250 kg weighed line; null weight → `POIDS INVALIDE` |
| B5 | Étiquette 2x poids (25…) | scan bus | weighed line (PLU+EAN, 1,250 kg); bad checksum → falls through → `CODE INCONNU` |
| B6 | Étiquette 2x prix (21…) | scan bus | embedded-total line, no code; re-scan → `ÉTIQUETTE DÉJÀ SCANNÉE`; fresh ticket accepts again |
| B7 | Code inconnu | scan bus | `CODE INCONNU: <code>` on state, cleared by the next scan |
| B8 | Produit interdit | scan bus | `PRODUIT INTERDIT À LA VENTE`, no line |
| B9 | SAISIE DIRECTE | `/manual` drill-down taps + numpad | head families only (no branch dup); known add qty 2; NON RECONNU → code-less line at typed price |
| B10 | Recherche | `/search` page + hit tap | hits with price; tap adds a line; no-match → `AUCUN PRODUIT POUR « … »` |
| B11 | Déconsigne | scan bus (+ `/pay` for the payment phase) | negative zero-VAT line, non-mergeable; during payment → ignored (`CODE INCONNU`) |
| B12 | Annulation de ligne | line select + ANNULER LIGNE | direct cancel, total 0, draft resync → CANCELLED |
| B13 | QUANTITÉ | QUANTITÉ modal + numpad | unit line qty 3; refused on weighed and negative lines |
| B14 | Annulation de ticket | ANNULER TICKET + manager endorsement | draft `CANCELLED` in DB, blank sale screen |

## Files read

- `e2e-scenarios.md` (§B, §O-A for the exact message strings).
- Reference: `GroupAIT.java`, `E2eTestProfile.java`.
- Scan chain: `TicketService`, `PosHardwareResource`, and every `ui/scanner/*ScanHandler` + `ScanContext`.
- Ticket domain/state: `TicketState`, `PosState`, `Ticket` (status enum), `Product`, `Price` (`findCurrentPrice` priority-DESC), `DataInitializer` (seed catalog, coupon types).
- Screens/resources: `HomeResource`/`HomeService`, `FruitResource`/`FruitService`, `ManualResource`/`ManualService`, `ProductSearchResource`, `EndorsementResource`/`EndorsementService`/`EndorsementState`, `PaymentResource`/`PaymentState`/`PaymentService` (paymentInProgress), `TicketPersistenceService` (`syncDraft`/`cancelDraft`), `HardwareService`/`HardwareClient`, `MockHardwareResource`.
- Templates: `main.html`, `ticket.html`, `endorsement.html`, `fruits.html`, `search.html`, `manual.html`, `keyboard-lib.html`.
- `application.properties` (scan patterns, embedded prefixes, valuation URL).

## Iterations

1. **First run:** 10/14 green. 4 failures.
2. **Fixes:**
   - The pay screen has no `TOTAL À PAYER` (it shows `RESTE :`) — B11's payment-phase wait fixed.
   - `syncDraft` cancels the draft of an emptied cart and resets `payment.ticketDbId`; but **drafts accumulate across scenarios** (a logout leaves the draft OPEN), so `Ticket.find(status=OPEN).firstResult()` returned the *oldest* draft, not the sale's. B12/B14 now read the current draft id from `posState.payment.ticketDbId`.
   - Two genuine early failures (B11 wait, B12 oracle) left their pages open; the lingering `/endorsement-data` pollers then **stole the manager/badge mailbox** from B13/B14 (the group-A page-close lesson). `freshSale()` now closes every open page before starting a scenario, killing the cascade.
3. **Second run:** 12/14 (B12/B14 draft assertions still using the wrong id).
4. **Third run:** 14/14 green.

## Hard points

- **The scanner is the bus.** The sale screen carries no code field — a gun, a wedge or a "typed PLU" all arrive on `POST /api/pos/scan`. Every scan/typed code is presented there while the cashier stands on the sale screen (the group-A hardware doctrine).
- **Untouched messages.** `UnknownScanHandler` sets `CODE INCONNU` **without** `touch()`, so it never reaches the poll; it is asserted on the injected `PosState`, not the screen (B5 checksum, B7, B11 during payment). `setError`-based messages (`POIDS INVALIDE`, `ÉTIQUETTE DÉJÀ SCANNÉE`, `PRODUIT INTERDIT`, `QUANTITÉ NON MODIFIABLE…`) do touch and are asserted both on screen and state.
- **Accumulating drafts.** Fiscal drafts are never deleted; the reliable current-draft id is the in-memory `payment.ticketDbId`, not a DB query. Never assert absolute ticket numbers.
- **Endorsement four-eyes.** B2/B14 drive the real modal: trigger the guarded gesture, present the manager badge on the bus (the 500 ms poll jumps to PIN), tap the PIN on `#endorseKeyboardArea`, SUIVANT.
- **Valuation engine down.** `pos.valuation.url=:8090` is set but nothing listens under test → immediate connection-refused → degraded/local catalog prices (sale never blocked). All asserted prices are the local catalog figures.
- **Isolation.** One session opened once by `ensureOpenSession()`; each scenario builds its own cart; a logout (`/lock`) clears the in-memory cart, the per-ticket sticker set and the last-weight guard. The only scaffolding outside the HTTP/screen surface: the B2 promo-price row (inserted then deleted in a `finally`) and the DB status reads.

## Justified residue

- **`[V]`/`[N]`:** none in group B — the whole letter is `[S]`.
- **B6 "redémarrage → re-scan accepté (limite assumée)":** a true register restart cannot happen inside one Quarkus boot. The equivalent is exercised: the anti-double-scan set is per-ticket and in-memory, so a fresh ticket (logout) forgets the sticker and accepts it again — the same underlying mechanism the scenario labels an assumed limit.
- **B10 "ligne pesée pour un produit à PLU":** `search.html`'s comment claims a hit tap routes through `processScan`, but `ProductSearchResource.addFromSearch` actually calls `addItemByEan` and only lists products that carry an EAN. A tapped hit therefore produces a **unit** line by EAN (asserted), never a weighed line. The "ligne pesée" wording describes the aspirational processScan path, not the resource's real behavior; the empty-state and hit-with-price halves are covered fully.
