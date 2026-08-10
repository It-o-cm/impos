# Group F — Paiement & clôture — e2e campaign report

**Class:** `src/test/java/com/intermarche/pos/e2e/GroupFIT.java`
**Run:** `mvn -q verify -DskipUTs=true -Dit.test=GroupFIT -DskipITs=false`
**Result:** `Tests run: 7, Failures: 0, Errors: 0, Skipped: 0` (one Quarkus boot, ~70 s).

## Scenarios covered ([S])

| # | Method | What it proves |
|---|--------|----------------|
| **F1** | `f1_especes_exactes_et_trop_percu` | Exact cash (6,00 € on a 6,00 € ticket) completes with **no** `Rendu Client`; a 10,00 € overpayment computes **4,00 €** change (asserted on the modal `.change-amount` **and** on `payment.lastChangeAmount`) and pulses the **drawer open**. |
| **F2** | `f2_multi_paiements_cb_partielle_puis_especes` | A partial card (4,00 €) on the virtual terminal + a cash balance (2,00 €) → **two** entries (`CARD`, `CASH`), remaining exact at each step (2,00 € then 0,00 €). |
| **F3** | `f3_tpe_virtuel_accept_refuse_annulation` | The card request parks the amount and raises the `PAIEMENT CARTE EN COURS` overlay. **Accept** → registered + complete; **refuse** → cart intact, `PAIEMENT REFUSÉ PAR LE TPE`, full amount still due; **register cancel** (`/action/card-cancel`) → demand withdrawn and **not replayable** (a late accept is inert). |
| **F4** | `f4_bons_encode_catalina_generique_inconnu_plafond` | Encoded "Bon enseigne" (amount **decoded** 1,50 €), Catalina (**manual** amount 3,00 € after its number), generic numberless bon (amount 2,00 €), a bad number → `Numéro non reconnu — vérifiez la saisie` (nothing registered), and a 50,00 € voucher on a 0,96 € ticket **capped** at 0,96 € (vouchers never render change). |
| **F5** | `f5_cheque_et_ticket_resto_tiroir_ouvert` | Cheque (3,00 €) + meal ticket (3,00 €) both pulse the **drawer open**; the closed ticket carries only `CHEQUE`+`TR` and **no `CASH`**, so the session's theoretical cash (float + cash payments) is unchanged. |
| **F6** | `f6_arrondi_solidaire_toggle` | The toggle adds a zero-VAT `ARRONDI SOLIDAIRE` line rounding 0,96 € → 1,00 € (difference 0,04 €); re-toggling removes it and the total falls back to 0,96 €. |
| **F8** | `f8_apres_terminer_numerotation_signature_grand_total` | Two consecutive `TERMINER` closings: numbers `C04-\d{8}` sequential without a gap, each signature a 64-hex SHA-256 chained (`t2.previousSignature == t1.signature`), the perpetual grand total advanced by the new TTC, VAT ventilation coherent (TTC = HT + TVA). The `IMPRIMER TICKET` paper carries `REGLEMENT`, `TOTAL TTC`, `Dont TVA`. |

## Attendus asserted (oracles)

- **Reactive screen:** `RESTE :`, the completion modal `TRANSACTION TERMINÉE` / `Rendu Client`, the TPE overlay `PAIEMENT CARTE EN COURS`, the voucher error zone.
- **Server singleton (`PosState.payment`):** exact registered entries (method key / label / number / amount), `getRemaining()` at each step, `pendingCardAmount` lifecycle, `transientError`, `lastChangeAmount`, `donationLineUid`.
- **Database (`Ticket` / `TicketPayment`):** status flip to `CLOSED`, `ticketNumber` format & sequence, `signature` / `previousSignature` chain, `grandTotal`, the tender method keys (`getMethodKey`), VAT ventilation.
- **Hardware simulator:** drawer status `OPEN` after cash-like tenders; printer buffer content for the printed ticket.

## Files read

- `e2e-scenarios.md` (§F, and §O-A/O-D/O-E for the exact payment texts).
- Existing group classes `GroupBIT.java`, `GroupCIT.java` (payment-screen driving, helpers, login recipe), `GroupDIT.java`, `E2eTestProfile.java`.
- `src/main/resources/templates/pay.html`, `keyboard-lib.html` (selectors, button texts, numpad arithmetic).
- `ui/payment/PaymentResource.java`, `PaymentService.java`, `PaymentState.java`, `VoucherService.java`, `VoucherScanHandler.java`; `domain/CouponType.java`, `domain/util/DataInitializer.java` (voucher formats).
- `domain/ticket/Ticket.java`, `TicketPayment.java`; `service/TicketPersistenceService.java` (validateTicket / chaining), `TicketNumberService.java` (number format), `TicketPrinterService.java` (paper content), `service/CashSessionService.java` + `domain/CashSession.java` (theoretical cash).
- `ui/home/PosHardwareResource.java` (TPE endpoints), `src/mock/.../MockHardwareResource.java`, `ui/DrawerCheckFilter.java`, `ui/auth/AuthService.java` (logout reset).

## Iterations

1. **First run** — F1, F6, F8 green. Four issues surfaced:
   - **F5** diverted to `/drawer-error`: the cheque opens the drawer, and the following `/action/pay-tr` POST is drawer-guarded. **Fix:** `closeDrawer()` between the cheque and the meal ticket (the cashier's physical close).
   - **F4 Catalina** timed out on the amount form: the payment numpad **collapses a leading zero** (`0` then a digit resets the buffer), so a `0482…` Catalina number cannot be keyed. **Fix:** present encoded and Catalina vouchers on the **hardware scan bus** (`VoucherScanHandler`, the intended payment-voucher path while `paymentInProgress`) instead of typing them; keep the typed path only for the bad-number and generic cases.
   - **F2 / F3 card accept** returned **404** on `POST /api/hardware/tpe/accept`. Root cause: under `@QuarkusTest` the embedded `MockHardwareResource` (`@Path("/api/hardware")`) is the most-specific root resource for that namespace and implements no `/tpe`, so it **shadows** `PosHardwareResource`'s `/api/hardware/tpe/*` sub-paths. **Fix:** play the terminal's accept/refuse at the service boundary the endpoint wraps (`paymentService.confirmPendingCard` / `refusePendingCard`) via `@Inject PaymentService` — parking, register-cancel and completion all stay on the screen.
2. **Second run** — all 7 green.

## Hard points

- **The virtual-TPE decision has no HTTP surface under test.** The register's inbound TPE endpoints live on `PosHardwareResource`, but the test-classpath `MockHardwareResource` owns `/api/hardware` and shadows them (404). The terminal's accept/refuse is therefore driven through the injected `PaymentService` — the same justified-scaffolding category as injected-state and DB-aging oracles, and the *only* gesture off the HTTP surface. Everything else is driven through the screen and the scan bus.
- **The drawer is real.** Cash / cheque / meal-ticket tenders pulse it open, and the completion actions (`/action/finish`, `/action/print`) are drawer-guarded, so every cash-like flow closes the drawer before finalizing or printing.
- **Numpad leading-zero collapse** makes leading-zero voucher numbers un-typeable; the scan bus is the faithful entry for them.
- **Perpetual counters** (ticket number, grand total, session): asserted by format and by delta, never as absolute values, and each scenario finalizes its own sale so nothing leaks (a logout resets the whole payment state).

## Justified residue

- **F7** *(Complétion, contrat des deux boutons)* — tagged **[V]** in the catalog header (`## F. … [S] ([V] pour F7)`); it belongs to the mirror-seeded valuation-engine campaign, so per the campaign rule ("implement ONLY [S] scenarios") it is skipped here. The two-button contract it describes is nonetheless exercised incidentally: `IMPRIMER TICKET` prints the still-OPEN draft (F8) and `NOUVELLE VENTE` is the fiscal moment whose CLOSED result F8 asserts.
- **F6 valuation sub-clause** ("avec valorisation active → total juste (ajustement constant)") — the `[V]` half of an otherwise `[S]` scenario; the engine is not up under test, so only the base toggle behavior is asserted.
- **F1/F5 customer display (`afficheur client`)** — the display text is pushed to the hardware bus (`DONNE … RENDU …`); it is asserted through the change amount on the modal and `lastChangeAmount` rather than by scraping the simulator display, which mirrors it.
- No `[N]` scenarios in this letter.
