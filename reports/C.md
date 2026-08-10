# Campaign report — Group C (Gestes de prix sous avenant)

**Class:** `src/test/java/com/intermarche/pos/e2e/GroupCIT.java`
**Command:** `mvn -q verify -DskipUTs=true -Dit.test=GroupCIT -DskipITs=false`
**Result:** `Tests run: 6, Failures: 0, Errors: 0, Skipped: 0` — one Quarkus boot, ~65 s.

## Scenarios covered (all 6 are `[S]`)

| Id | Scenario | Gesture driven through the screen | Oracle |
|----|----------|-----------------------------------|--------|
| C1 | REMISE € | REMISE modal (`#priceKbArea`) + manager endorsement | `PosState` (`modifierType=REMISE`, `modifierValue=1`, total 6→5) ; floor: remise 10 → total **0,00** |
| C2 | DISCOUNT % | DISCOUNT (secondary menu) modal + endorsement, ×4 carts | 0 % = no-op ; 100 % → 0 ; 101 % refused ; 15 % on 0,96 → **0,82** on the rendered line AND on the printed paper |
| C3 | FORÇAGE prix | qty-2 line (two scans) + FORÇAGE (secondary) + endorsement | forced total 5,00 → **unit price 2,50 = total/qty** ; label `Prix initial: …` |
| C4 | Avenant refusé | ANNULER on the modal ; 3 wrong manager PINs ; correct PIN while locked | parked gesture inert (no modifier) ; manager `isCurrentlyLocked()` ; `AUTORISATION REFUSÉE` |
| C5 | Badge manager sur la modale | badge on the bus prefills the manager login, PIN only | remise applied through the badge (login never typed) — A4 precedence, on the endorsement |
| C6 | Geste sur ligne déjà modifiée | REMISE then FORÇAGE on the same line, then re-scan | second gesture replaces type/value/label (5,00→4,00) ; re-scan → **2 lines** (merge forbidden) |

## Files read

- `e2e-scenarios.md` (§C, §O-A / §O-E for the exact message strings).
- Reference: `GroupAIT.java`, `GroupBIT.java`, `E2eTestProfile.java`.
- Sale screen & gestures: `main.html` (menu split — REMISE/QUANTITÉ primary, DISCOUNT/FORÇAGE PRIX secondary behind `AUTRES...`; price modal `#priceKbArea` / VALIDER / ANNULER), `ticket.html`, `endorsement.html`.
- Server flow: `HomeResource`/`HomeService` (`/action/price-mod/{type}`, `submit`, `cancel`), `PriceModState`, `EndorsementResource` (dispatch registry, `AUTORISATION REFUSÉE`), `EndorsementService`/`EndorsementState`, `AuthService.checkCredentials` (shared 3-strike lockout), `Employee` (`failedAttempts`/`lockedUntil`).
- Computations: `TicketService.applyRemise` (floor 0), `applyDiscount` (≤0 / >100 silent refusal), `forcePrice` (total/qty), `recalculateTotal`→`syncAndRevalue`.
- State: `TicketState.TicketItem` (`modifierType`/`modifierValue`/`modifierLabel`, `getTotalPrice()`), `PosState`.
- Payment & print (C2): `PaymentResource` (`/pay`, `/action/pay-cash`, `/action/print`, `/action/finish`), `PaymentService`, `pay.html` (ESPÈCES / VALIDER ESPÈCES / completion modal), `TicketPrinterService` (line `totalPrice` via `DecimalFormat("0.00", FRENCH)`), `MockHardwareResource` (`/printer/content`, `/printer/clear`).
- Seed catalog: `DataInitializer` (HUILE 6,00 € `3300000000006`, BAGUETTE 0,96 € `3300000000003`; manager mcurie `11111111`/`1111`).

## Iterations

1. **First run:** 5/6 green. Only C2's printed-ticket assertion failed — the paper came back **empty**.
2. **Diagnosis:** the cash payment pulses the drawer OPEN (deposit + change), and `POST /action/print` is `@DrawerMustBeClosed`-guarded, so the print request was diverted to `/drawer-error` (log: `Accès bloqué (Tiroir ouvert). Return URL enregistrée: /pay`) and nothing ever reached the printer. A `page.waitForResponse("**/action/print", …)` around the click had already ruled out a read/write race, so the empty buffer pointed at the guard, not at timing.
3. **Fix:** `closeDrawer()` after the payment completes and before clicking IMPRIMER (the same physical-close doctrine as the login recipe). Printed line total `0,82` now matches the screen.
4. **Second run:** 6/6 green.

## Hard points

- **The gesture is a three-step dance.** Pick the button → type on `#priceKbArea` → VALIDER **parks** the gesture into an endorsement (nothing applied yet) → the manager badges + PINs on `#endorseKeyboardArea` → SUIVANT executes it. Only QUANTITY skips the endorsement.
- **Menu split.** REMISE/QUANTITÉ live in the primary menu, DISCOUNT/FORÇAGE PRIX in the secondary one behind `AUTRES...`. `showSecondaryMenu` is a server-singleton flag that is NOT reset on login (`clearTicket` leaves it), so it can leak between scenarios. `openGesture()` forces the right menu with the toggle's own GET (`/action/menu/{main|secondary}`) before tapping the button — deterministic regardless of leaked state.
- **Silent refusals.** A >100 % discount, a ≤0 % discount and a floored REMISE all return SILENTLY from `TicketService` — **no message**. They are asserted as the ABSENCE of a modifier (`modifierType == null`) and an unchanged total, never as an error string. `VALEUR INVALIDE` is only the non-numeric parse guard, out of scope for these value cases.
- **Shared lockout counter (C4).** The endorsement credential check goes through the SAME `AuthService.checkCredentials` as the register login, so three wrong endorsement PINs lock the manager's account (5 min). Proven two ways: `Employee.isCurrentlyLocked()` read from the row, AND a subsequently CORRECT PIN still refused while the lockout stands. The account is restored (`failedAttempts=0`, `lockedUntil=null`) at the end so C5/C6 endorse against a clean manager — the isolation contract.
- **Refused-endorsement driving.** Each wrong attempt full-page-reloads the modal back to step 1 (login), so every attempt re-presents the badge (mailbox → straight to PIN) and waits on `AUTORISATION REFUSÉE` — no sleeps, deterministic sequencing.
- **Printed-ticket capture (C2).** The manual gesture is baked into the persisted line `unitPrice`/`totalPrice` (not printed as a separate delta line — that block is the phase-7 engine advantage), so the cent rounding shows purely as the reduced `totalPrice`. Captured from the embedded printer simulator (`GET /api/hardware/printer/content`) after a completing overpaid cash payment + IMPRIMER; the amount is compared separator-agnostically (`DecimalFormat` is `Locale.FRENCH` → `0,82`).
- **FORÇAGE for C6's replacement proof.** REMISE and DISCOUNT recompute from the CURRENT (already-reduced) unit price, so they compound; FORÇAGE resets from the typed total. C6 uses FORÇAGE as the second gesture so the replacement is unambiguous (total truly becomes 4,00, not a compounded figure), while `originalUnitPrice` stays preserved for the `Prix initial:` label.

## Justified residue

- **`[V]`/`[N]`:** none in group C — the whole letter is `[S]`.
- **Locale of `modifierLabel`.** The label is built with `String.format("Remise -%.2f€", …)` under the default JVM locale, so its decimal separator is environment-dependent. Assertions use the locale-independent PREFIX (`startsWith("Remise")` / `startsWith("Prix initial:")`) and the structured `modifierType`/`modifierValue` for the numbers — never the localized label string.
- **C2 "sur l'écran".** The screen half is asserted on the rendered ticket line (`#ticket-container` contains `0,82`, the `getPriceFormatted` comma form) plus the exact `PosState` line total; the printed half on the paper buffer. Both agree at the cent, which is the scenario's oracle.
