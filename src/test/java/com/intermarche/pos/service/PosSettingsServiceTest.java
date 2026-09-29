package com.intermarche.pos.service;

import com.intermarche.pos.domain.setting.PosSetting;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import io.quarkus.hibernate.orm.panache.PanacheQuery;
import org.eclipse.microprofile.config.Config;
import org.eclipse.microprofile.config.ConfigProvider;
import org.junit.jupiter.api.Test;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link PosSettingsService}.
 * <p>
 * The service is a Panache active-record consumer over {@link PosSetting}
 * ({@code listAll}, the delegated {@code find} of {@code findByKey}, and the
 * {@code persist} of the upsert). Every static access resolves to
 * {@link PanacheEntityBase} and is intercepted with
 * {@link org.mockito.Mockito#mockStatic}; the inserted row is neutralized with
 * {@link org.mockito.Mockito#mockConstruction}. The legacy configuration
 * fallback goes through {@link ConfigProvider}, itself mocked statically. No
 * database and no Quarkus context is booted.
 * <p>
 * Branch enumeration (every arm exercised — 100%):
 * {@code def} (found / not found), {@code value} (cold-cache load then warm
 * reuse, stored-present arm, unknown-key null arm, config-fallback present /
 * absent arm, no-fallback default arm), {@code loadAll} (success / exception
 * arm), {@code invalidate}, {@code store} (insert / update arm),
 * {@code intValue} (parse ok / corrupt-row arm), {@code boolValue} (true /
 * false), and each public accessor. 10 two-way decision points (20 branches).
 */
class PosSettingsServiceTest {

    /**
     * Builds a {@link PosSetting} row carrying the given key and value.
     *
     * @param key the setting key
     * @param value the setting value
     * @return the row
     */
    private PosSetting row(String key, String value) {
        PosSetting row = mock(PosSetting.class);
        row.settingKey = key;
        row.settingValue = value;
        return row;
    }

    /**
     * Builds a Panache query whose {@code firstResult} resolves to the value.
     *
     * @param result the value the query returns
     * @param <T> the queried type
     * @return the configured mocked query
     */
    @SuppressWarnings("unchecked")
    private <T> PanacheQuery<T> query(T result) {
        PanacheQuery<T> query = mock(PanacheQuery.class);
        when(query.firstResult()).thenReturn(result);
        return query;
    }

    /**
     * {@code def} returns the catalog entry for a known key (found arm) and
     * null for an unknown one (not-found arm).
     */
    @Test
    void defReturnsEntryOrNull() {
        PosSettingsService service = new PosSettingsService();
        assertEquals("display.show-ean", service.def("display.show-ean").key());
        assertNull(service.def("no.such.key"));
    }

    /**
     * {@code value} loads the table on a cold cache and returns the stored
     * override (stored-present arm); a second call reuses the warm cache
     * (loadAll not called again).
     */
    @Test
    void valueReturnsStoredOverrideAndCachesTable() {
        PosSettingsService service = new PosSettingsService();
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(PanacheEntityBase::listAll).thenReturn(List.of(row("display.show-ean", "true")));
            assertEquals("true", service.value("display.show-ean"));
            assertEquals("true", service.value("display.show-ean"));
            ms.verify(PanacheEntityBase::listAll, times(1));
        }
    }

    /**
     * {@code value} returns null for an unknown key with no stored row
     * (def-null arm), the table being empty.
     */
    @Test
    void valueReturnsNullForUnknownKey() {
        PosSettingsService service = new PosSettingsService();
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(PanacheEntityBase::listAll).thenReturn(List.of());
            assertNull(service.value("no.such.key"));
        }
    }

    /**
     * {@code value} consults the legacy configuration fallback when no row
     * exists and the key declares one (config-present arm).
     */
    @Test
    void valueFallsBackToConfigWhenPresent() {
        PosSettingsService service = new PosSettingsService();
        Config config = mock(Config.class);
        when(config.getOptionalValue("pos.display.show-ean", String.class)).thenReturn(Optional.of("true"));
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class);
                MockedStatic<ConfigProvider> cfg = mockStatic(ConfigProvider.class)) {
            ms.when(PanacheEntityBase::listAll).thenReturn(List.of());
            cfg.when(ConfigProvider::getConfig).thenReturn(config);
            assertEquals("true", service.value("display.show-ean"));
        }
    }

    /**
     * {@code value} falls through to the catalog default when the declared
     * configuration fallback is absent (config-absent arm).
     */
    @Test
    void valueFallsBackToDefaultWhenConfigAbsent() {
        PosSettingsService service = new PosSettingsService();
        Config config = mock(Config.class);
        when(config.getOptionalValue("pos.display.show-ean", String.class)).thenReturn(Optional.empty());
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class);
                MockedStatic<ConfigProvider> cfg = mockStatic(ConfigProvider.class)) {
            ms.when(PanacheEntityBase::listAll).thenReturn(List.of());
            cfg.when(ConfigProvider::getConfig).thenReturn(config);
            assertEquals("false", service.value("display.show-ean"));
        }
    }

    /**
     * {@code value} returns the catalog default for a key without any
     * configuration fallback (no-fallback arm).
     */
    @Test
    void valueReturnsDefaultWhenNoFallback() {
        PosSettingsService service = new PosSettingsService();
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(PanacheEntityBase::listAll).thenReturn(List.of());
            assertEquals("true", service.value("gesture.endorsement-required"));
        }
    }

    /**
     * {@code loadAll} swallows a read failure and falls back to defaults
     * (exception arm): nothing is cached and the default is returned.
     */
    @Test
    void valueSurvivesReadFailure() {
        PosSettingsService service = new PosSettingsService();
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(PanacheEntityBase::listAll).thenThrow(new RuntimeException("no table"));
            assertEquals("true", service.value("gesture.endorsement-required"));
        }
    }

    /**
     * {@code store} inserts a new row when none exists (insert arm): the
     * constructed instance gets the key and value and is persisted, and the
     * cache is dropped so the next read reloads.
     */
    @Test
    void storeInsertsWhenAbsent() {
        PosSettingsService service = new PosSettingsService();
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class);
                MockedConstruction<PosSetting> created = mockConstruction(PosSetting.class)) {
            PanacheQuery<PosSetting> absent = query(null);
            ms.when(() -> PanacheEntityBase.find("settingKey", "display.show-ean")).thenReturn(absent);
            service.store("display.show-ean", "true");
            PosSetting inserted = created.constructed().get(0);
            assertEquals("display.show-ean", inserted.settingKey);
            assertEquals("true", inserted.settingValue);
            verify(inserted, times(1)).persist();
        }
    }

    /**
     * {@code store} updates the existing row when one is found (update arm):
     * the found row's value is overwritten and persisted, no row constructed.
     */
    @Test
    void storeUpdatesWhenPresent() {
        PosSettingsService service = new PosSettingsService();
        PosSetting existing = mock(PosSetting.class);
        existing.settingKey = "display.show-ean";
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class);
                MockedConstruction<PosSetting> created = mockConstruction(PosSetting.class)) {
            PanacheQuery<PosSetting> present = query(existing);
            ms.when(() -> PanacheEntityBase.find("settingKey", "display.show-ean")).thenReturn(present);
            service.store("display.show-ean", "true");
            assertTrue(created.constructed().isEmpty());
            assertEquals("true", existing.settingValue);
            verify(existing, times(1)).persist();
        }
    }

    /**
     * {@code invalidate} drops the cache: after a first cold load, a store's
     * invalidation forces a fresh {@code listAll} on the next value read.
     */
    @Test
    void invalidateForcesReload() {
        PosSettingsService service = new PosSettingsService();
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(PanacheEntityBase::listAll).thenReturn(List.of());
            service.value("display.show-ean");
            service.invalidate();
            service.value("display.show-ean");
            ms.verify(PanacheEntityBase::listAll, times(2));
        }
    }

    /**
     * {@code intValue} returns the parsed integer for a well-formed row
     * (parse-ok arm).
     */
    @Test
    void intValueParsesStoredNumber() {
        PosSettingsService service = new PosSettingsService();
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(PanacheEntityBase::listAll).thenReturn(List.of(row("auth.idle-lockout-seconds", " 42 ")));
            assertEquals(42L, service.idleLockoutSeconds());
        }
    }

    /**
     * {@code intValue} falls back to the catalog default on a corrupt row
     * (parse-fail arm).
     */
    @Test
    void intValueFallsBackOnCorruptRow() {
        PosSettingsService service = new PosSettingsService();
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(PanacheEntityBase::listAll).thenReturn(List.of(row("discount.line-max-percent", "oops")));
            assertEquals(100, service.lineMaxDiscountPercent());
        }
    }

    /**
     * Each public accessor projects its stored row: the boolean accessors
     * (true / false arms), the remaining integer accessor and the two text
     * accessors, plus the parking flag.
     */
    @Test
    void accessorsProjectStoredValues() {
        PosSettingsService service = new PosSettingsService();
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(PanacheEntityBase::listAll).thenReturn(List.of(
                    row("display.show-ean", "true"),
                    row("gesture.endorsement-required", "false"),
                    row("discount.global-max-percent", "30"),
                    row("customer.message-open", "Bonjour"),
                    row("customer.message-closed", "Fermé"),
                    row("parking.print-receipt", "false"),
                    row("payment.degraded-mode", "true"),
                    row("discount.enabled", "false"),
                    row("price.show-original-on-force", "false"),
                    row("ticket.header-message", "Promo"),
                    row("ticket.footer-message", "A bientot"),
                    row("customer.qr-enabled", "false"),
                    row("drawer.open-on-payment", "false"),
                    row("drawer.open-on-login", "false"),
                    row("scan.ean13-check-digit", "true"),
                    row("dashboard.alerts-enabled", "false"),
                    row("fidelity.advantages-enabled", "false"),
                    row("fidelity.allow-multiple-scan", "false")));
            assertTrue(service.showEan());
            assertFalse(service.gestureEndorsementRequired());
            assertEquals(30, service.globalMaxDiscountPercent());
            assertEquals("Bonjour", service.customerOpenMessage());
            assertEquals("Fermé", service.customerClosedMessage());
            assertFalse(service.parkingPrintReceipt());
            assertTrue(service.paymentDegradedMode());
            assertFalse(service.discountEnabled());
            assertFalse(service.priceShowOriginalOnForce());
            assertEquals("Promo", service.ticketHeaderMessage());
            assertEquals("A bientot", service.ticketFooterMessage());
            assertFalse(service.customerQrEnabled());
            assertFalse(service.drawerOpenOnPayment());
            assertFalse(service.drawerOpenOnLogin());
            assertTrue(service.ean13CheckDigitEnabled());
            assertFalse(service.dashboardAlertsEnabled());
            assertFalse(service.fidelityAdvantagesEnabled());
            assertFalse(service.fidelityAllowMultipleScan());
        }
    }

    /**
     * The remaining public accessors each project their stored row: the integer
     * accessors ({@code cashRoundingStepCents}, {@code moneticsDegradedForcedMinutes},
     * {@code creditDegradedAfterMinutes}), the boolean accessors
     * ({@code balanceCounterPrice}, {@code backupManualEndorsement},
     * {@code moneticsDegradedForcedEndorsement}, {@code creditAllowedInDegraded},
     * {@code printConditionalEnabled}, {@code printForceTicketGlc},
     * {@code printForceCardCredit}, {@code printForceCardSignature},
     * {@code printForceCardTna}, {@code ticketEmailEditable}) and the text
     * accessors ({@code backupMethodLabels}, {@code invoiceCustomerFields},
     * {@code ticketLineOrder}, {@code ticketEmailFormat}).
     */
    @Test
    void remainingAccessorsProjectStoredValues() {
        PosSettingsService service = new PosSettingsService();
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(PanacheEntityBase::listAll).thenReturn(List.of(
                    row("cash.rounding-step-cents", "5"),
                    row("balance.counter-price", "false"),
                    row("backup.manual-endorsement", "false"),
                    row("payment.degraded-forced-endorsement", "false"),
                    row("payment.degraded-forced-minutes", "90"),
                    row("backup.method-labels", "20=CB"),
                    row("credit.allowed-in-degraded", "true"),
                    row("credit.degraded-after-minutes", "30"),
                    row("print.conditional-enabled", "true"),
                    row("print.force-ticket-glc", "false"),
                    row("print.force-card-credit", "false"),
                    row("print.force-card-signature", "false"),
                    row("print.force-card-tna", "false"),
                    row("invoice.customer-fields", "companyName*;email"),
                    row("ticket.line-order", "LABEL"),
                    row("ticket.email-format", "ATTACHMENT"),
                    row("ticket.email-editable", "false"),
                    row("fidelity.offline-message", "FIDELITE HS {carte}"),
                    row("fidelity.offline-message-no-card", "PRESENTEZ VOTRE CARTE")));
            assertEquals(5, service.cashRoundingStepCents());
            assertFalse(service.balanceCounterPrice());
            assertFalse(service.backupManualEndorsement());
            assertFalse(service.moneticsDegradedForcedEndorsement());
            assertEquals(90, service.moneticsDegradedForcedMinutes());
            assertEquals("20=CB", service.backupMethodLabels());
            assertTrue(service.creditAllowedInDegraded());
            assertEquals(30, service.creditDegradedAfterMinutes());
            assertTrue(service.printConditionalEnabled());
            assertFalse(service.printForceTicketGlc());
            assertFalse(service.printForceCardCredit());
            assertFalse(service.printForceCardSignature());
            assertFalse(service.printForceCardTna());
            assertEquals("companyName*;email", service.invoiceCustomerFields());
            assertEquals("LABEL", service.ticketLineOrder());
            assertEquals("ATTACHMENT", service.ticketEmailFormat());
            assertFalse(service.ticketEmailEditable());
            // BO-03-03-29 / BO-03-03-30: the two receipt messages of a silent
            // loyalty service — a holder is told their advantages will follow,
            // a non-holder is invited to present a card next time.
            assertEquals("FIDELITE HS {carte}", service.fidelityOfflineMessage());
            assertEquals("PRESENTEZ VOTRE CARTE", service.fidelityOfflineMessageNoCard());
        }
    }

    /**
     * {@code printForcedDocuments} splits the administered list, uppercasing and
     * trimming each entry and dropping blank ones (loop iterates then exits,
     * {@code !isEmpty} both arms, {@code isBlank} false arm), and returns an empty
     * list when the setting is blank ({@code isBlank} true arm, the {@code
     * List.of()} early return). A held {@code service} reference is used for each
     * call so both arms register their line and branch coverage.
     */
    @Test
    void printForcedDocumentsCoversBothArms() {
        PosSettingsService populated = new PosSettingsService();
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(PanacheEntityBase::listAll)
                    .thenReturn(List.of(row("print.forced-documents", " ticket ; ; carte ")));
            assertEquals(List.of("TICKET", "CARTE"), populated.printForcedDocuments());
        }
        PosSettingsService blank = new PosSettingsService();
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(PanacheEntityBase::listAll)
                    .thenReturn(List.of(row("print.forced-documents", "   ")));
            assertTrue(blank.printForcedDocuments().isEmpty());
        }
    }

    /**
     * {@code cashMovementEndorsementThreshold} parses a well-formed stored
     * amount (parse-ok arm) and falls back to the catalog default on a corrupt
     * row (parse-fail arm).
     */
    @Test
    void cashMovementEndorsementThresholdParsesOrFallsBack() {
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(PanacheEntityBase::listAll)
                    .thenReturn(List.of(row("cash.movement-endorsement-threshold", " 250.50 ")));
            assertEquals(0, new java.math.BigDecimal("250.50")
                    .compareTo(new PosSettingsService().cashMovementEndorsementThreshold()));
        }
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(PanacheEntityBase::listAll)
                    .thenReturn(List.of(row("cash.movement-endorsement-threshold", "oops")));
            assertEquals(0, new java.math.BigDecimal("100.00")
                    .compareTo(new PosSettingsService().cashMovementEndorsementThreshold()));
        }
    }

    /**
     * {@code cashMovementReasons} splits the administered list, trimming each
     * entry and dropping blank ones ({@code !isEmpty} both arms), and returns an
     * empty list for a blank setting (isBlank true arm).
     */
    @Test
    void cashMovementReasonsSplitsAndDropsBlanks() {
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(PanacheEntityBase::listAll)
                    .thenReturn(List.of(row("cash.movement-reasons", " Coffre ; ; Timbres ")));
            assertEquals(List.of("Coffre", "Timbres"), new PosSettingsService().cashMovementReasons());
        }
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(PanacheEntityBase::listAll)
                    .thenReturn(List.of(row("cash.movement-reasons", "   ")));
            assertTrue(new PosSettingsService().cashMovementReasons().isEmpty());
        }
    }

    /**
     * {@code returnReasons} splits the administered list, trimming each entry
     * and dropping blank ones ({@code !isEmpty} both arms, LC-05-05-01), and
     * returns an empty list for a blank setting (isBlank true arm).
     */
    @Test
    void returnReasonsSplitsAndDropsBlanks() {
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(PanacheEntityBase::listAll)
                    .thenReturn(List.of(row("return.reasons", " Erreur de caisse ; ; Article périmé ")));
            assertEquals(List.of("Erreur de caisse", "Article périmé"),
                    new PosSettingsService().returnReasons());
        }
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(PanacheEntityBase::listAll)
                    .thenReturn(List.of(row("return.reasons", "   ")));
            assertTrue(new PosSettingsService().returnReasons().isEmpty());
        }
    }

    /**
     * {@code financialMovementSources} and {@code financialMovementDestinations}
     * hand back the administered raw lists verbatim (LC-12-06-02).
     */
    @Test
    void financialMovementListsReturnTheAdministeredRawValues() {
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(PanacheEntityBase::listAll).thenReturn(List.of(
                    row("movement.financial-sources", "CASH:Espèces"),
                    row("movement.financial-destinations", "CREDIT:Crédit client")));
            PosSettingsService service = new PosSettingsService();
            assertEquals("CASH:Espèces", service.financialMovementSources());
            assertEquals("CREDIT:Crédit client", service.financialMovementDestinations());
        }
    }

    /**
     * {@code financialMovementPrint} reads the administered flag in both arms
     * (LC-12-06-03).
     */
    @Test
    void financialMovementPrintCoversBothArms() {
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(PanacheEntityBase::listAll)
                    .thenReturn(List.of(row("movement.financial-print", "true")));
            assertTrue(new PosSettingsService().financialMovementPrint());
        }
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(PanacheEntityBase::listAll)
                    .thenReturn(List.of(row("movement.financial-print", "false")));
            assertFalse(new PosSettingsService().financialMovementPrint());
        }
    }

    /**
     * {@code changeOrderPrint} reads the administered flag in both arms
     * (LC-12-05-08).
     */
    @Test
    void changeOrderPrintCoversBothArms() {
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(PanacheEntityBase::listAll)
                    .thenReturn(List.of(row("change-order.print", "true")));
            assertTrue(new PosSettingsService().changeOrderPrint());
        }
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(PanacheEntityBase::listAll)
                    .thenReturn(List.of(row("change-order.print", "false")));
            assertFalse(new PosSettingsService().changeOrderPrint());
        }
    }

    /**
     * {@code changeOrderRollValues} hands back the administered raw
     * correspondence verbatim (LC-12-05-06/07).
     */
    @Test
    void changeOrderRollValuesReturnsTheAdministeredRawValue() {
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(PanacheEntityBase::listAll)
                    .thenReturn(List.of(row("change-order.roll-values", "0.20:8.00;0.01:0.50")));
            assertEquals("0.20:8.00;0.01:0.50", new PosSettingsService().changeOrderRollValues());
        }
    }

    /**
     * {@code printReturnReason} reads the administered flag in both arms
     * (LC-05-05-03): the stored override wins over the catalog default.
     */
    @Test
    void printReturnReasonReadsAdministeredFlag() {
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(PanacheEntityBase::listAll)
                    .thenReturn(List.of(row("return.print-reason", "false")));
            assertFalse(new PosSettingsService().printReturnReason());
        }
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(PanacheEntityBase::listAll)
                    .thenReturn(List.of(row("return.print-reason", "true")));
            assertTrue(new PosSettingsService().printReturnReason());
        }
    }

    /**
     * {@code defaultTare} reads the administered default tare, and returns zero
     * from the catalog default when no row overrides it (LC-14-01-03).
     */
    @Test
    void defaultTareReadsAdministeredValue() {
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(PanacheEntityBase::listAll)
                    .thenReturn(List.of(row("balance.default-tare", "0.010")));
            assertEquals(0, new java.math.BigDecimal("0.010")
                    .compareTo(new PosSettingsService().defaultTare()));
        }
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(PanacheEntityBase::listAll).thenReturn(List.of());
            assertEquals(0, java.math.BigDecimal.ZERO
                    .compareTo(new PosSettingsService().defaultTare()));
        }
    }

    /**
     * {@code tareContainers} parses the administered {@code CODE:LIBELLÉ:POIDS}
     * list, keeping well-formed rows and dropping the malformed ones: a wrong
     * field count, a blank code, a blank label, a negative weight and an
     * unreadable weight are each dropped, while the valid rows survive in order
     * (LC-14-01-04, both arms of every row guard).
     */
    @Test
    void tareContainersParsesAndDropsMalformedRows() {
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(PanacheEntityBase::listAll).thenReturn(List.of(row("balance.tare-containers",
                    "SACPAP:Sac kraft:0.010; BQS : Barquette S : 0,035 ;"
                            + "BADFIELDS:only-two;:Sans code:0.01;NOLABEL::0.02;"
                            + "NEG:Negatif:-0.01;NAN:Illisible:lourd")));
            List<PosSettingsService.TareContainer> containers =
                    new PosSettingsService().tareContainers();
            assertEquals(2, containers.size());
            assertEquals("SACPAP", containers.get(0).code());
            assertEquals("Sac kraft", containers.get(0).label());
            assertEquals(0, new java.math.BigDecimal("0.010").compareTo(containers.get(0).tareKg()));
            assertEquals("BQS", containers.get(1).code());
            assertEquals("Barquette S", containers.get(1).label());
            assertEquals(0, new java.math.BigDecimal("0.035").compareTo(containers.get(1).tareKg()));
        }
    }

    /**
     * {@code tareContainers} returns an empty list for a blank setting (isBlank
     * true arm) — no container is then offered.
     */
    @Test
    void tareContainersEmptyForBlankSetting() {
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(PanacheEntityBase::listAll)
                    .thenReturn(List.of(row("balance.tare-containers", "   ")));
            assertTrue(new PosSettingsService().tareContainers().isEmpty());
        }
    }

    /**
     * {@code touchGroupsPerPage} floors a value below one back to the catalog
     * default (below-one arm) and keeps a positive value verbatim (positive
     * arm).
     */
    @Test
    void touchGroupsPerPageFloorsBelowOne() {
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(PanacheEntityBase::listAll)
                    .thenReturn(List.of(row("touch.groups-per-page", "0")));
            assertEquals(12, new PosSettingsService().touchGroupsPerPage());
        }
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(PanacheEntityBase::listAll)
                    .thenReturn(List.of(row("touch.groups-per-page", "20")));
            assertEquals(20, new PosSettingsService().touchGroupsPerPage());
        }
    }

    /**
     * {@code touchDisplayOrder} normalizes the administered value: CUSTOM (first
     * disjunct true), VOLUME (second disjunct true, case-insensitively) and any
     * other value including the default (both disjuncts false) folding to ALPHA.
     */
    @Test
    void touchDisplayOrderNormalizes() {
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(PanacheEntityBase::listAll)
                    .thenReturn(List.of(row("touch.display-order", "CUSTOM")));
            assertEquals("CUSTOM", new PosSettingsService().touchDisplayOrder());
        }
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(PanacheEntityBase::listAll)
                    .thenReturn(List.of(row("touch.display-order", " volume ")));
            assertEquals("VOLUME", new PosSettingsService().touchDisplayOrder());
        }
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(PanacheEntityBase::listAll)
                    .thenReturn(List.of(row("touch.display-order", "weird")));
            assertEquals("ALPHA", new PosSettingsService().touchDisplayOrder());
        }
    }

    /**
     * Builds a service wired with an echelon engine and a node PDV number.
     *
     * @param engine the echelon engine, or null
     * @param pdvNumber the node PDV number Optional, or null when unset
     * @return the wired service
     */
    private PosSettingsService wired(EchelonSettingService engine, Optional<String> pdvNumber) {
        PosSettingsService service = new PosSettingsService();
        service.echelonSettings = engine;
        service.nodePdvNumber = pdvNumber;
        return service;
    }

    /**
     * {@code value} returns the echelon-inherited value when no local override
     * exists (inherited-present arm, all three legs of the load guard false),
     * and the inherited map is resolved once and cached across reads.
     */
    @Test
    void valueUsesInheritedWhenNoLocalOverride() {
        EchelonSettingService engine = mock(EchelonSettingService.class);
        when(engine.resolveForPdv("12345")).thenReturn(Map.of("gesture.endorsement-required", "false"));
        PosSettingsService service = wired(engine, Optional.of("12345"));
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(PanacheEntityBase::listAll).thenReturn(List.of());
            assertFalse(service.gestureEndorsementRequired());
            assertFalse(service.gestureEndorsementRequired());
            verify(engine, times(1)).resolveForPdv("12345");
        }
    }

    /**
     * A local override wins over the echelon-inherited value and never
     * consults the echelon engine — the "surcharge locale survit" guarantee:
     * the stored row is read before the inheritance layer.
     */
    @Test
    void localOverrideWinsOverInheritance() {
        EchelonSettingService engine = mock(EchelonSettingService.class);
        when(engine.resolveForPdv("12345")).thenReturn(Map.of("gesture.endorsement-required", "false"));
        PosSettingsService service = wired(engine, Optional.of("12345"));
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(PanacheEntityBase::listAll)
                    .thenReturn(List.of(row("gesture.endorsement-required", "true")));
            assertTrue(service.gestureEndorsementRequired());
            verify(engine, never()).resolveForPdv(org.mockito.ArgumentMatchers.anyString());
        }
    }

    /**
     * The inheritance layer is skipped when this node carries no PDV number
     * ({@code nodePdvNumber} null leg): the engine is never consulted and the
     * catalog default applies.
     */
    @Test
    void inheritanceSkippedWhenNoPdvNumber() {
        EchelonSettingService engine = mock(EchelonSettingService.class);
        PosSettingsService service = wired(engine, null);
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(PanacheEntityBase::listAll).thenReturn(List.of());
            assertTrue(service.gestureEndorsementRequired());
            verify(engine, never()).resolveForPdv(org.mockito.ArgumentMatchers.anyString());
        }
    }

    /**
     * The inheritance layer is skipped when the PDV number is present but blank
     * ({@code isEmpty} leg): the engine is never consulted.
     */
    @Test
    void inheritanceSkippedWhenPdvNumberEmpty() {
        EchelonSettingService engine = mock(EchelonSettingService.class);
        PosSettingsService service = wired(engine, Optional.empty());
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(PanacheEntityBase::listAll).thenReturn(List.of());
            assertTrue(service.gestureEndorsementRequired());
            verify(engine, never()).resolveForPdv(org.mockito.ArgumentMatchers.anyString());
        }
    }

    /**
     * A failed echelon resolution falls back to the catalog default (the
     * try/catch exception arm) rather than propagating, so a central hiccup
     * never stops a node reading its parameters.
     */
    @Test
    void inheritanceSurvivesResolveFailure() {
        EchelonSettingService engine = mock(EchelonSettingService.class);
        when(engine.resolveForPdv("12345")).thenThrow(new RuntimeException("central down"));
        PosSettingsService service = wired(engine, Optional.of("12345"));
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(PanacheEntityBase::listAll).thenReturn(List.of());
            assertTrue(service.gestureEndorsementRequired());
        }
    }

    /**
     * {@code administeredValues} publishes the RESOLVED effective values a store
     * exports to its registers (route A): the echelon-inherited keys overlaid by
     * the local overrides, the local winning on a shared key (all three legs of
     * the guard false, echelon-present arm). It resolves fresh on each call so a
     * crossed effect date is picked up (engine consulted every time), while the
     * local table is cached ({@code cache == null} then reuse arm).
     */
    @Test
    void administeredValuesMergesLocalOverEchelonResolvingFresh() {
        EchelonSettingService engine = mock(EchelonSettingService.class);
        when(engine.resolveForPdv("12345")).thenReturn(Map.of(
                "shared", "echelon", "ensOnly", "ev"));
        PosSettingsService service = wired(engine, Optional.of("12345"));
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(PanacheEntityBase::listAll).thenReturn(List.of(
                    row("shared", "local"), row("localOnly", "lv")));
            Map<String, String> first = service.administeredValues();
            Map<String, String> second = service.administeredValues();
            assertEquals("local", first.get("shared"));
            assertEquals("ev", first.get("ensOnly"));
            assertEquals("lv", first.get("localOnly"));
            assertEquals(3, first.size());
            assertEquals(first, second);
            ms.verify(PanacheEntityBase::listAll, times(1));
            verify(engine, times(2)).resolveForPdv("12345");
        }
    }

    /**
     * {@code administeredValues} skips the echelon layer when no engine is wired
     * (echelonSettings-null leg) and returns the local overrides alone.
     */
    @Test
    void administeredValuesSkipsEchelonWhenEngineNull() {
        PosSettingsService service = wired(null, Optional.of("12345"));
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(PanacheEntityBase::listAll).thenReturn(List.of(row("k", "v")));
            Map<String, String> resolved = service.administeredValues();
            assertEquals(Map.of("k", "v"), resolved);
        }
    }

    /**
     * {@code administeredValues} skips the echelon layer when this node carries
     * no PDV number (nodePdvNumber-null leg): the engine is never consulted.
     */
    @Test
    void administeredValuesSkipsEchelonWhenNoPdvNumber() {
        EchelonSettingService engine = mock(EchelonSettingService.class);
        PosSettingsService service = wired(engine, null);
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(PanacheEntityBase::listAll).thenReturn(List.of(row("k", "v")));
            Map<String, String> resolved = service.administeredValues();
            assertEquals(Map.of("k", "v"), resolved);
            verify(engine, never()).resolveForPdv(org.mockito.ArgumentMatchers.anyString());
        }
    }

    /**
     * {@code administeredValues} skips the echelon layer when the PDV number is
     * present but blank ({@code isEmpty} leg): the engine is never consulted.
     */
    @Test
    void administeredValuesSkipsEchelonWhenPdvEmpty() {
        EchelonSettingService engine = mock(EchelonSettingService.class);
        PosSettingsService service = wired(engine, Optional.empty());
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(PanacheEntityBase::listAll).thenReturn(List.of(row("k", "v")));
            Map<String, String> resolved = service.administeredValues();
            assertEquals(Map.of("k", "v"), resolved);
            verify(engine, never()).resolveForPdv(org.mockito.ArgumentMatchers.anyString());
        }
    }

    /**
     * {@code badgeScanEnabled} reads its stored boolean value (BO-10-02-29/30).
     */
    @Test
    void badgeScanEnabledReadsStoredValue() {
        PosSettingsService service = new PosSettingsService();
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(PanacheEntityBase::listAll).thenReturn(List.of(row("auth.badge-scan-enabled", "false")));
            assertFalse(service.badgeScanEnabled());
        }
    }

    /**
     * {@code defaultOpeningFloat} reads its stored value (BO-03-02-41).
     */
    @Test
    void defaultOpeningFloatReadsStoredValue() {
        PosSettingsService service = new PosSettingsService();
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(PanacheEntityBase::listAll).thenReturn(List.of(row("cash.default-opening-float", "200.00")));
            assertEquals("200.00", service.defaultOpeningFloat());
        }
    }

    /**
     * {@code drawerOpenOnSessionClose} reads its stored boolean value (BO-10-02-26).
     */
    @Test
    void drawerOpenOnSessionCloseReadsStoredValue() {
        PosSettingsService service = new PosSettingsService();
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(PanacheEntityBase::listAll).thenReturn(List.of(row("drawer.open-on-session-close", "false")));
            assertFalse(service.drawerOpenOnSessionClose());
        }
    }

    /**
     * {@code vatBreakdownEnabled} reads its stored boolean value (BO-10-06-04).
     */
    @Test
    void vatBreakdownEnabledReadsStoredValue() {
        PosSettingsService service = new PosSettingsService();
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(PanacheEntityBase::listAll).thenReturn(List.of(row("ticket.vat-breakdown-enabled", "false")));
            assertFalse(service.vatBreakdownEnabled());
        }
    }

    /**
     * {@code fidelityExternalEnabled} reads its stored boolean value (BO-10-03-07).
     */
    @Test
    void fidelityExternalEnabledReadsStoredValue() {
        PosSettingsService service = new PosSettingsService();
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(PanacheEntityBase::listAll).thenReturn(List.of(row("fidelity.external-enabled", "false")));
            assertFalse(service.fidelityExternalEnabled());
        }
    }

    /**
     * {@code cashMovementTenders} parses the semicolon list, trimming and
     * dropping blank entries (BO-03-02-20, non-blank arm and the blank-part skip).
     */
    @Test
    void cashMovementTendersParsesSemicolonList() {
        PosSettingsService service = new PosSettingsService();
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(PanacheEntityBase::listAll)
                    .thenReturn(List.of(row("cash.movement-tenders", "ESPECES; CHEQUE ; ;TR")));
            assertEquals(List.of("ESPECES", "CHEQUE", "TR"), service.cashMovementTenders());
        }
    }

    /**
     * {@code cashMovementTenders} yields an empty list on the blank default
     * (BO-03-02-20, blank arm — a movement then always concerns the cash).
     */
    @Test
    void cashMovementTendersEmptyWhenBlank() {
        PosSettingsService service = new PosSettingsService();
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(PanacheEntityBase::listAll).thenReturn(List.of());
            assertTrue(service.cashMovementTenders().isEmpty());
        }
    }

    /**
     * A FAILED read caches nothing, so the very next access tries the database
     * again: two {@code value} calls issue two {@code listAll}.
     * <p>
     * This is the defect the guard exists for. {@code loadAll} used to clear
     * the cache itself and hand back an empty map, which its own caller then
     * stored — so the empty map became the register's parameters until someone
     * saved a setting or restarted it. A register that cannot read its
     * parameters at boot must recover on its own.
     */
    @Test
    void aFailedReadIsNotCachedAndTheNextAccessRetries() {
        PosSettingsService service = new PosSettingsService();
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(PanacheEntityBase::listAll).thenThrow(new RuntimeException("no table"));
            assertEquals("true", service.value("gesture.endorsement-required"));
            assertEquals("true", service.value("gesture.endorsement-required"));
            ms.verify(PanacheEntityBase::listAll, times(2));
        }
    }

    /**
     * A read that FAILS then SUCCEEDS serves the default first and the stored
     * override next — the recovery the retry buys, asserted on the value and
     * not only on the call count.
     */
    @Test
    void aReadRecoveringAfterAFailureServesTheStoredValue() {
        PosSettingsService service = new PosSettingsService();
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(PanacheEntityBase::listAll)
                    .thenThrow(new RuntimeException("no table"))
                    .thenReturn(List.of(row("gesture.endorsement-required", "false")));
            assertEquals("true", service.value("gesture.endorsement-required"));
            assertEquals("false", service.value("gesture.endorsement-required"));
        }
    }

    /**
     * A SUCCESSFUL read is cached, failure or not afterwards: once the table
     * has answered, a later throw never reaches the caller because no reload
     * is attempted.
     */
    @Test
    void aSuccessfulReadIsCachedAndNeverReloaded() {
        PosSettingsService service = new PosSettingsService();
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(PanacheEntityBase::listAll)
                    .thenReturn(List.of(row("gesture.endorsement-required", "false")));
            assertEquals("false", service.value("gesture.endorsement-required"));
            assertEquals("false", service.value("gesture.endorsement-required"));
            ms.verify(PanacheEntityBase::listAll, times(1));
        }
    }

    /**
     * {@code administeredValues} goes through the same cache as {@code value}:
     * a failed read caches nothing there either, so the screen that renders the
     * administered values does not freeze the defaults in.
     */
    @Test
    void administeredValuesDoesNotCacheAFailedRead() {
        PosSettingsService service = new PosSettingsService();
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(PanacheEntityBase::listAll).thenThrow(new RuntimeException("no table"));
            service.administeredValues();
            service.administeredValues();
            ms.verify(PanacheEntityBase::listAll, times(2));
        }
    }

    /**
     * A FAILED echelon resolution caches nothing: the next value read resolves
     * the echelons again, instead of staying on the local overrides for good.
     */
    @Test
    void aFailedEchelonResolutionIsNotCached() {
        PosSettingsService service = new PosSettingsService();
        service.echelonSettings = mock(EchelonSettingService.class);
        service.nodePdvNumber = Optional.of("07039");
        when(service.echelonSettings.resolveForPdv("07039"))
                .thenThrow(new RuntimeException("echelons indisponibles"));
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(PanacheEntityBase::listAll).thenReturn(List.of());
            assertEquals("true", service.value("gesture.endorsement-required"));
            assertEquals("true", service.value("gesture.endorsement-required"));
            verify(service.echelonSettings, times(2)).resolveForPdv("07039");
        }
    }

    /**
     * A SUCCESSFUL echelon resolution is cached even when it yields NOTHING:
     * "this node inherits no value" is a settled fact, not a failure, and
     * re-resolving it on every read would query the echelons for nothing.
     */
    @Test
    void anEmptyEchelonResolutionIsCached() {
        PosSettingsService service = new PosSettingsService();
        service.echelonSettings = mock(EchelonSettingService.class);
        service.nodePdvNumber = Optional.of("07039");
        when(service.echelonSettings.resolveForPdv("07039")).thenReturn(Map.of());
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(PanacheEntityBase::listAll).thenReturn(List.of());
            assertEquals("true", service.value("gesture.endorsement-required"));
            assertEquals("true", service.value("gesture.endorsement-required"));
            verify(service.echelonSettings, times(1)).resolveForPdv("07039");
        }
    }

    /**
     * An echelon value INHERITED is served when the local table states nothing,
     * and it is resolved once — the warm arm of the inherited cache carrying a
     * real value rather than an empty map.
     */
    @Test
    void anInheritedEchelonValueIsServedAndCachedOnce() {
        PosSettingsService service = new PosSettingsService();
        service.echelonSettings = mock(EchelonSettingService.class);
        service.nodePdvNumber = Optional.of("07039");
        when(service.echelonSettings.resolveForPdv("07039"))
                .thenReturn(Map.of("gesture.endorsement-required", "false"));
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(PanacheEntityBase::listAll).thenReturn(List.of());
            assertEquals("false", service.value("gesture.endorsement-required"));
            assertEquals("false", service.value("gesture.endorsement-required"));
            verify(service.echelonSettings, times(1)).resolveForPdv("07039");
        }
    }

    /**
     * {@code invalidate} drops BOTH caches: after an admin save, the next read
     * re-queries the table AND re-resolves the echelons.
     */
    @Test
    void invalidateDropsTheEchelonCacheToo() {
        PosSettingsService service = new PosSettingsService();
        service.echelonSettings = mock(EchelonSettingService.class);
        service.nodePdvNumber = Optional.of("07039");
        when(service.echelonSettings.resolveForPdv("07039")).thenReturn(Map.of());
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(PanacheEntityBase::listAll).thenReturn(List.of());
            service.value("gesture.endorsement-required");
            service.invalidate();
            service.value("gesture.endorsement-required");
            ms.verify(PanacheEntityBase::listAll, times(2));
            verify(service.echelonSettings, times(2)).resolveForPdv("07039");
        }
    }

    /**
     * {@code customerCreditTender} returns the stored key TRIMMED when it is
     * present and non-blank (BO-10-04-09): both compound-guard legs are false
     * ({@code key == null} false, {@code key.isBlank()} false) and the ternary
     * takes its false arm, {@code key.trim()}.
     */
    @Test
    void customerCreditTenderTrimsStoredKey() {
        PosSettingsService service = new PosSettingsService();
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(PanacheEntityBase::listAll).thenReturn(List.of(row("customer.credit-tender", "  CB_CREDIT  ")));
            assertEquals("CB_CREDIT", service.customerCreditTender());
        }
    }

    /**
     * {@code customerCreditTender} falls back to {@code CREDIT} when the stored
     * value is blank (BO-10-04-09): the first guard leg {@code key == null} is
     * false, the second leg {@code key.isBlank()} is true, and the ternary takes
     * its true arm. The {@code key == null} true leg is UNREACHABLE through this
     * accessor because {@code value("customer.credit-tender")} never yields null
     * for a catalog key carrying the default {@code CREDIT}.
     */
    @Test
    void customerCreditTenderFallsBackToCreditWhenBlank() {
        PosSettingsService service = new PosSettingsService();
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(PanacheEntityBase::listAll).thenReturn(List.of(row("customer.credit-tender", "   ")));
            assertEquals("CREDIT", service.customerCreditTender());
        }
    }

    /**
     * {@code passwordRequiredOnOpen} reads its stored boolean value
     * (LC-01-01-03/09, BO-10-02-22).
     */
    @Test
    void passwordRequiredOnOpenReadsStoredValue() {
        PosSettingsService service = new PosSettingsService();
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(PanacheEntityBase::listAll).thenReturn(List.of(row("auth.password-required-on-open", "false")));
            assertFalse(service.passwordRequiredOnOpen());
        }
    }

    /**
     * {@code passwordRequiredOnClose} reads its stored boolean value
     * (LC-01-02-02/03, BO-10-02-24).
     */
    @Test
    void passwordRequiredOnCloseReadsStoredValue() {
        PosSettingsService service = new PosSettingsService();
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(PanacheEntityBase::listAll).thenReturn(List.of(row("auth.password-required-on-close", "true")));
            assertTrue(service.passwordRequiredOnClose());
        }
    }

    /**
     * {@code closeEndorsementOnPending} reads its stored boolean value
     * (LC-01-02-10, BO-10-02-08).
     */
    @Test
    void closeEndorsementOnPendingReadsStoredValue() {
        PosSettingsService service = new PosSettingsService();
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(PanacheEntityBase::listAll).thenReturn(List.of(row("session.close-endorsement-on-pending", "false")));
            assertFalse(service.closeEndorsementOnPending());
        }
    }

    /**
     * {@code printOpenReceipt} reads its stored boolean value
     * (LC-01-01-08, BO-10-07-09).
     */
    @Test
    void printOpenReceiptReadsStoredValue() {
        PosSettingsService service = new PosSettingsService();
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(PanacheEntityBase::listAll).thenReturn(List.of(row("session.print-open-receipt", "true")));
            assertTrue(service.printOpenReceipt());
        }
    }

    /**
     * {@code printCloseReceipt} reads its stored boolean value
     * (LC-01-02-11, BO-10-07-09).
     */
    @Test
    void printCloseReceiptReadsStoredValue() {
        PosSettingsService service = new PosSettingsService();
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(PanacheEntityBase::listAll).thenReturn(List.of(row("session.print-close-receipt", "true")));
            assertTrue(service.printCloseReceipt());
        }
    }

    /**
     * {@code volatileCustomerCreationEnabled} reads its stored boolean value
     * (BO-10-04-01).
     */
    @Test
    void volatileCustomerCreationEnabledReadsStoredValue() {
        PosSettingsService service = new PosSettingsService();
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(PanacheEntityBase::listAll).thenReturn(List.of(row("customer.volatile-creation-enabled", "false")));
            assertFalse(service.volatileCustomerCreationEnabled());
        }
    }

    /**
     * {@code accountCustomerCreationBlocked} reads its stored boolean value
     * (BO-10-04-05, BO-02-04-12).
     */
    @Test
    void accountCustomerCreationBlockedReadsStoredValue() {
        PosSettingsService service = new PosSettingsService();
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(PanacheEntityBase::listAll).thenReturn(List.of(row("customer.account-creation-blocked", "false")));
            assertFalse(service.accountCustomerCreationBlocked());
        }
    }

    /**
     * {@code showCustomerDetails} reads its stored boolean value (BO-10-04-02).
     */
    @Test
    void showCustomerDetailsReadsStoredValue() {
        PosSettingsService service = new PosSettingsService();
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(PanacheEntityBase::listAll).thenReturn(List.of(row("customer.show-details", "false")));
            assertFalse(service.showCustomerDetails());
        }
    }

    /**
     * {@code customerUpdateEnabled} reads its stored boolean value
     * (BO-10-04-03/04).
     */
    @Test
    void customerUpdateEnabledReadsStoredValue() {
        PosSettingsService service = new PosSettingsService();
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(PanacheEntityBase::listAll).thenReturn(List.of(row("customer.update-enabled", "true")));
            assertTrue(service.customerUpdateEnabled());
        }
    }

    /**
     * {@code showCustomerDiscount} reads its stored boolean value (BO-10-04-08).
     */
    @Test
    void showCustomerDiscountReadsStoredValue() {
        PosSettingsService service = new PosSettingsService();
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(PanacheEntityBase::listAll).thenReturn(List.of(row("customer.show-discount", "false")));
            assertFalse(service.showCustomerDiscount());
        }
    }

    /**
     * {@code customerDiscountSegment} reads its stored text value (BO-10-04-07).
     */
    @Test
    void customerDiscountSegmentReadsStoredValue() {
        PosSettingsService service = new PosSettingsService();
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(PanacheEntityBase::listAll).thenReturn(List.of(row("customer.discount-segment", "GOLD")));
            assertEquals("GOLD", service.customerDiscountSegment());
        }
    }

    /**
     * {@code partialCreditAllowed} reads its stored boolean value (BO-10-04-10).
     */
    @Test
    void partialCreditAllowedReadsStoredValue() {
        PosSettingsService service = new PosSettingsService();
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(PanacheEntityBase::listAll).thenReturn(List.of(row("customer.partial-credit-allowed", "false")));
            assertFalse(service.partialCreditAllowed());
        }
    }

    /**
     * {@code customerOfflineMessage} reads its stored text value (BO-10-04-11/12).
     */
    @Test
    void customerOfflineMessageReadsStoredValue() {
        PosSettingsService service = new PosSettingsService();
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(PanacheEntityBase::listAll).thenReturn(List.of(row("customer.offline-message", "HORS LIGNE")));
            assertEquals("HORS LIGNE", service.customerOfflineMessage());
        }
    }

    /**
     * {@code customerOverLimitMessage} reads its stored text value (BO-10-04-13).
     */
    @Test
    void customerOverLimitMessageReadsStoredValue() {
        PosSettingsService service = new PosSettingsService();
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(PanacheEntityBase::listAll).thenReturn(List.of(row("customer.over-limit-message", "PLAFOND {plafond}")));
            assertEquals("PLAFOND {plafond}", service.customerOverLimitMessage());
        }
    }

    /**
     * {@code defaultTaxId} reads its stored text value (BO-10-04-15).
     */
    @Test
    void defaultTaxIdReadsStoredValue() {
        PosSettingsService service = new PosSettingsService();
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(PanacheEntityBase::listAll).thenReturn(List.of(row("customer.default-tax-id", "123456789")));
            assertEquals("123456789", service.defaultTaxId());
        }
    }

    /**
     * {@code defaultTaxIdLabel} reads its stored text value (BO-10-04-16).
     */
    @Test
    void defaultTaxIdLabelReadsStoredValue() {
        PosSettingsService service = new PosSettingsService();
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(PanacheEntityBase::listAll).thenReturn(List.of(row("customer.default-tax-id-label", "CLIENT FINAL")));
            assertEquals("CLIENT FINAL", service.defaultTaxIdLabel());
        }
    }

    /**
     * {@code foreignTaxIdCountries} reads its stored text value (BO-10-04-17).
     */
    @Test
    void foreignTaxIdCountriesReadsStoredValue() {
        PosSettingsService service = new PosSettingsService();
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(PanacheEntityBase::listAll).thenReturn(List.of(row("customer.foreign-tax-id-countries", "ES,PT")));
            assertEquals("ES,PT", service.foreignTaxIdCountries());
        }
    }

    /**
     * {@code invoiceExportDueDate} reads its stored boolean value (BO-10-04-19).
     */
    @Test
    void invoiceExportDueDateReadsStoredValue() {
        PosSettingsService service = new PosSettingsService();
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(PanacheEntityBase::listAll).thenReturn(List.of(row("customer.invoice-export-due-date", "false")));
            assertFalse(service.invoiceExportDueDate());
        }
    }

    /**
     * {@code invoiceExportEcoTax} reads its stored boolean value (BO-10-04-14).
     */
    @Test
    void invoiceExportEcoTaxReadsStoredValue() {
        PosSettingsService service = new PosSettingsService();
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(PanacheEntityBase::listAll).thenReturn(List.of(row("customer.invoice-export-ecotax", "true")));
            assertTrue(service.invoiceExportEcoTax());
        }
    }

    /**
     * {@code periodAutoCloseEnabled} reads its stored boolean value
     * (LC-01-02-08, BO-09-01-06/09).
     */
    @Test
    void periodAutoCloseEnabledReadsStoredValue() {
        PosSettingsService service = new PosSettingsService();
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(PanacheEntityBase::listAll).thenReturn(List.of(row("period.auto-close-enabled", "true")));
            assertTrue(service.periodAutoCloseEnabled());
        }
    }

    /**
     * {@code periodEndTime} reads its stored text value (BO-09-01-06).
     */
    @Test
    void periodEndTimeReadsStoredValue() {
        PosSettingsService service = new PosSettingsService();
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(PanacheEntityBase::listAll).thenReturn(List.of(row("period.end-time", "22:00")));
            assertEquals("22:00", service.periodEndTime());
        }
    }

    /**
     * {@code periodCloseLeadMinutes} reads its stored integer value (BO-09-03-07).
     */
    @Test
    void periodCloseLeadMinutesReadsStoredValue() {
        PosSettingsService service = new PosSettingsService();
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(PanacheEntityBase::listAll).thenReturn(List.of(row("period.close-lead-minutes", "15")));
            assertEquals(15, service.periodCloseLeadMinutes());
        }
    }

    /**
     * {@code fidelityShowHolderName} reads its stored boolean value (BO-10-03-04).
     */
    @Test
    void fidelityShowHolderNameReadsStoredValue() {
        PosSettingsService service = new PosSettingsService();
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(PanacheEntityBase::listAll).thenReturn(List.of(row("fidelity.show-holder-name", "false")));
            assertFalse(service.fidelityShowHolderName());
        }
    }

    /**
     * {@code customerAccountPattern} reads its stored text value (BO-03-06-52).
     */
    @Test
    void customerAccountPatternReadsStoredValue() {
        PosSettingsService service = new PosSettingsService();
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(PanacheEntityBase::listAll).thenReturn(List.of(row("customer.account-pattern", "^CC\\d{8}$")));
            assertEquals("^CC\\d{8}$", service.customerAccountPattern());
        }
    }

    /**
     * {@code fidelityCardPattern} reads its stored text value (BO-03-06-54).
     */
    @Test
    void fidelityCardPatternReadsStoredValue() {
        PosSettingsService service = new PosSettingsService();
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(PanacheEntityBase::listAll).thenReturn(List.of(row("fidelity.card-pattern", "^299\\d{10}$")));
            assertEquals("^299\\d{10}$", service.fidelityCardPattern());
        }
    }

    /**
     * {@code fidelityJvPromotionEnabled} reads its stored boolean value
     * (BO-10-03-22).
     */
    @Test
    void fidelityJvPromotionEnabledReadsStoredValue() {
        PosSettingsService service = new PosSettingsService();
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(PanacheEntityBase::listAll).thenReturn(List.of(row("fidelity.jv-promotion-enabled", "false")));
            assertFalse(service.fidelityJvPromotionEnabled());
        }
    }

    /**
     * {@code fidelityUrl} reads its stored text value (BO-11-04-04).
     */
    @Test
    void fidelityUrlReadsStoredValue() {
        PosSettingsService service = new PosSettingsService();
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(PanacheEntityBase::listAll).thenReturn(List.of(row("fidelity.url", "https://fid.example")));
            assertEquals("https://fid.example", service.fidelityUrl());
        }
    }

    /**
     * {@code fidelityUser} reads its stored text value (BO-11-04-04).
     */
    @Test
    void fidelityUserReadsStoredValue() {
        PosSettingsService service = new PosSettingsService();
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(PanacheEntityBase::listAll).thenReturn(List.of(row("fidelity.user", "caisse-c04")));
            assertEquals("caisse-c04", service.fidelityUser());
        }
    }

    /**
     * {@code trainingTheme} reads its stored text value (BO-10-07-08).
     */
    @Test
    void trainingThemeReadsStoredValue() {
        PosSettingsService service = new PosSettingsService();
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(PanacheEntityBase::listAll).thenReturn(List.of(row("training.theme", "sombre")));
            assertEquals("sombre", service.trainingTheme());
        }
    }

    /**
     * {@code lineMaxDiscountAmount} parses its stored decimal value (BO-03-07-04).
     */
    @Test
    void lineMaxDiscountAmountReadsStoredValue() {
        PosSettingsService service = new PosSettingsService();
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(PanacheEntityBase::listAll).thenReturn(List.of(row("discount.line-max-amount", "12.50")));
            assertEquals(0, new java.math.BigDecimal("12.50").compareTo(service.lineMaxDiscountAmount()));
        }
    }

    /**
     * {@code globalMaxDiscountAmount} parses its stored decimal value
     * (BO-03-07-04).
     */
    @Test
    void globalMaxDiscountAmountReadsStoredValue() {
        PosSettingsService service = new PosSettingsService();
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(PanacheEntityBase::listAll).thenReturn(List.of(row("discount.global-max-amount", "40.00")));
            assertEquals(0, new java.math.BigDecimal("40.00").compareTo(service.globalMaxDiscountAmount()));
        }
    }

    /**
     * {@code invoiceDocumentTypes} reads its stored text value (LC-08-04-04).
     */
    @Test
    void invoiceDocumentTypesReadsStoredValue() {
        PosSettingsService service = new PosSettingsService();
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(PanacheEntityBase::listAll).thenReturn(List.of(row("invoice.document-types", "FACTURE;BON_LIVRAISON")));
            assertEquals("FACTURE;BON_LIVRAISON", service.invoiceDocumentTypes());
        }
    }

    /**
     * {@code invoiceDocumentOutput} reads its stored text value (LC-08-04-11).
     */
    @Test
    void invoiceDocumentOutputReadsStoredValue() {
        PosSettingsService service = new PosSettingsService();
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(PanacheEntityBase::listAll).thenReturn(List.of(row("invoice.document-output", "FACTURE:A4")));
            assertEquals("FACTURE:A4", service.invoiceDocumentOutput());
        }
    }

    /**
     * {@code invoiceSlipLines} reads its stored integer value (LC-08-04-12/13).
     */
    @Test
    void invoiceSlipLinesReadsStoredValue() {
        PosSettingsService service = new PosSettingsService();
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(PanacheEntityBase::listAll).thenReturn(List.of(row("invoice.slip-lines", "12")));
            assertEquals(12, service.invoiceSlipLines());
        }
    }

    /**
     * {@code invoiceAutoPrint} reads its stored text value (LC-08-04-16).
     */
    @Test
    void invoiceAutoPrintReadsStoredValue() {
        PosSettingsService service = new PosSettingsService();
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(PanacheEntityBase::listAll).thenReturn(List.of(row("invoice.auto-print", "CREDIT:FACTURE")));
            assertEquals("CREDIT:FACTURE", service.invoiceAutoPrint());
        }
    }

    /**
     * {@code gs1ExpiryAlert} reads its stored text value (LC-11-03-13).
     */
    @Test
    void gs1ExpiryAlertReadsStoredValue() {
        PosSettingsService service = new PosSettingsService();
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(PanacheEntityBase::listAll).thenReturn(List.of(row("gs1.expiry-alert", "BLOCK")));
            assertEquals("BLOCK", service.gs1ExpiryAlert());
        }
    }

    /**
     * {@code restrictedTenders} reads its stored text value (LC-09-01-11 to -18).
     */
    @Test
    void restrictedTendersReadsStoredValue() {
        PosSettingsService service = new PosSettingsService();
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(PanacheEntityBase::listAll).thenReturn(List.of(row("tender.restricted", "TR:MEAL_VOUCHER_ELIGIBLE:Titre")));
            assertEquals("TR:MEAL_VOUCHER_ELIGIBLE:Titre", service.restrictedTenders());
        }
    }

    /**
     * {@code abandonReasons} reads its stored text value (LC-04-04-10).
     */
    @Test
    void abandonReasonsReadsStoredValue() {
        PosSettingsService service = new PosSettingsService();
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(PanacheEntityBase::listAll).thenReturn(List.of(row("abandon.reasons", "Erreur;Client parti")));
            assertEquals("Erreur;Client parti", service.abandonReasons());
        }
    }

    /**
     * {@code drawerWithdrawalMethods} reads its stored text value
     * (LC-12-03-02/03).
     */
    @Test
    void drawerWithdrawalMethodsReadsStoredValue() {
        PosSettingsService service = new PosSettingsService();
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(PanacheEntityBase::listAll).thenReturn(List.of(row("drawer.withdrawal-methods", "CASH:Especes")));
            assertEquals("CASH:Especes", service.drawerWithdrawalMethods());
        }
    }

    /**
     * {@code drawerWithdrawalPrint} reads its stored boolean value (LC-12-03-07).
     */
    @Test
    void drawerWithdrawalPrintReadsStoredValue() {
        PosSettingsService service = new PosSettingsService();
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(PanacheEntityBase::listAll).thenReturn(List.of(row("drawer.withdrawal-print", "false")));
            assertFalse(service.drawerWithdrawalPrint());
        }
    }

    /**
     * {@code drawerTransferMethods} reads its stored text value (LC-12-10-02).
     */
    @Test
    void drawerTransferMethodsReadsStoredValue() {
        PosSettingsService service = new PosSettingsService();
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(PanacheEntityBase::listAll).thenReturn(List.of(row("drawer.transfer-methods", "CASH:Especes;CHEQUE:Cheques")));
            assertEquals("CASH:Especes;CHEQUE:Cheques", service.drawerTransferMethods());
        }
    }

    /**
     * {@code drawerTransferPrint} reads its stored boolean value (LC-12-10-05).
     */
    @Test
    void drawerTransferPrintReadsStoredValue() {
        PosSettingsService service = new PosSettingsService();
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(PanacheEntityBase::listAll).thenReturn(List.of(row("drawer.transfer-print", "false")));
            assertFalse(service.drawerTransferPrint());
        }
    }

    /**
     * {@code abandonPartialPayment} reads its stored text value
     * (LC-04-04-06 to -09).
     */
    @Test
    void abandonPartialPaymentReadsStoredValue() {
        PosSettingsService service = new PosSettingsService();
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(PanacheEntityBase::listAll).thenReturn(List.of(row("abandon.partial-payment", "BLOCK")));
            assertEquals("BLOCK", service.abandonPartialPayment());
        }
    }

    /**
     * {@code abandonPrint} reads its stored text value (LC-04-04-12).
     */
    @Test
    void abandonPrintReadsStoredValue() {
        PosSettingsService service = new PosSettingsService();
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(PanacheEntityBase::listAll).thenReturn(List.of(row("abandon.print", "ALWAYS")));
            assertEquals("ALWAYS", service.abandonPrint());
        }
    }

    /**
     * {@code abandonPrintDetail} reads its stored boolean value (LC-04-04-12).
     */
    @Test
    void abandonPrintDetailReadsStoredValue() {
        PosSettingsService service = new PosSettingsService();
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(PanacheEntityBase::listAll).thenReturn(List.of(row("abandon.print-detail", "false")));
            assertFalse(service.abandonPrintDetail());
        }
    }

    /**
     * {@code gs1ExpiryWarnDays} reads its stored integer value (LC-11-03-13).
     */
    @Test
    void gs1ExpiryWarnDaysReadsStoredValue() {
        PosSettingsService service = new PosSettingsService();
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(PanacheEntityBase::listAll).thenReturn(List.of(row("gs1.expiry-warn-days", "7")));
            assertEquals(7, service.gs1ExpiryWarnDays());
        }
    }

    /**
     * {@code gs1CouponExpiryAlert} reads its stored text value (LC-11-03-13).
     */
    @Test
    void gs1CouponExpiryAlertReadsStoredValue() {
        PosSettingsService service = new PosSettingsService();
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(PanacheEntityBase::listAll).thenReturn(List.of(row("gs1.coupon-expiry-alert", "INFO")));
            assertEquals("INFO", service.gs1CouponExpiryAlert());
        }
    }

    /**
     * {@code gs1GiftCouponTypes} reads its stored text value (LC-11-03-05).
     */
    @Test
    void gs1GiftCouponTypesReadsStoredValue() {
        PosSettingsService service = new PosSettingsService();
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(PanacheEntityBase::listAll).thenReturn(List.of(row("gs1.gdti-coupon-types", "9526000:CADEAU")));
            assertEquals("9526000:CADEAU", service.gs1GiftCouponTypes());
        }
    }

    /**
     * {@code gs1CouponType} reads its stored text value (LC-11-03-06).
     */
    @Test
    void gs1CouponTypeReadsStoredValue() {
        PosSettingsService service = new PosSettingsService();
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(PanacheEntityBase::listAll).thenReturn(List.of(row("gs1.gcn-coupon-type", "COUPON")));
            assertEquals("COUPON", service.gs1CouponType());
        }
    }

    /**
     * {@code islandChangeAllowed} (BO-03-10-04) defaults to true when no row is
     * posted (current behaviour) and honours a stored prohibition — both arms.
     */
    @Test
    void islandChangeAllowedReadsAdministeredFlag() {
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(PanacheEntityBase::listAll).thenReturn(List.of());
            assertTrue(new PosSettingsService().islandChangeAllowed());
        }
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(PanacheEntityBase::listAll)
                    .thenReturn(List.of(row("island.change-allowed", "false")));
            assertFalse(new PosSettingsService().islandChangeAllowed());
        }
    }

    /**
     * {@code islandAutoDeclaration} (BO-03-10-10) defaults to false when no row
     * is posted (the counting screen stays) and honours a stored activation —
     * both arms.
     */
    @Test
    void islandAutoDeclarationReadsAdministeredFlag() {
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(PanacheEntityBase::listAll).thenReturn(List.of());
            assertFalse(new PosSettingsService().islandAutoDeclaration());
        }
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(PanacheEntityBase::listAll)
                    .thenReturn(List.of(row("island.auto-declaration", "true")));
            assertTrue(new PosSettingsService().islandAutoDeclaration());
        }
    }

    /**
     * {@code islandPromotionChannel} (BO-03-10-08) defaults to the empty string
     * when no row is posted (no channel is added, remontée unchanged) and
     * returns a stored channel — both arms.
     */
    @Test
    void islandPromotionChannelReadsAdministeredValue() {
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(PanacheEntityBase::listAll).thenReturn(List.of());
            assertEquals("", new PosSettingsService().islandPromotionChannel());
        }
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(PanacheEntityBase::listAll)
                    .thenReturn(List.of(row("island.promotion-channel", "WEB")));
            assertEquals("WEB", new PosSettingsService().islandPromotionChannel());
        }
    }

    /**
     * {@code welcomeMessage} (EXP-01) defaults to the invitation to log in when
     * no row is posted, and returns a stored override — both arms.
     */
    @Test
    void welcomeMessageReadsAdministeredValue() {
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(PanacheEntityBase::listAll).thenReturn(List.of());
            assertEquals("Bonjour : Connectez-vous", new PosSettingsService().welcomeMessage());
        }
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(PanacheEntityBase::listAll)
                    .thenReturn(List.of(row("session.welcome-message", "Bienvenue !")));
            assertEquals("Bienvenue !", new PosSettingsService().welcomeMessage());
        }
    }

    /**
     * {@code waitingLabel} (EXP-03) defaults to the retail "next customer"
     * prompt when no row is posted, and returns a stored override — both arms.
     */
    @Test
    void waitingLabelReadsAdministeredValue() {
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(PanacheEntityBase::listAll).thenReturn(List.of());
            assertEquals("CLIENT SUIVANT", new PosSettingsService().waitingLabel());
        }
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(PanacheEntityBase::listAll)
                    .thenReturn(List.of(row("sale.waiting-label", "AU SUIVANT")));
            assertEquals("AU SUIVANT", new PosSettingsService().waitingLabel());
        }
    }

    /**
     * {@code drawerCap} (EXP-17) defaults to zero (no cap) when no row is
     * posted, returns a stored amount, and falls back to the catalog default
     * when the stored row is corrupt (bigDecimalValue guard) — all three arms.
     */
    @Test
    void drawerCapReadsAdministeredValue() {
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(PanacheEntityBase::listAll).thenReturn(List.of());
            assertEquals(0, new java.math.BigDecimal("0.00").compareTo(new PosSettingsService().drawerCap()));
        }
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(PanacheEntityBase::listAll)
                    .thenReturn(List.of(row("cash.drawer-cap", "1000.00")));
            assertEquals(0, new java.math.BigDecimal("1000.00").compareTo(new PosSettingsService().drawerCap()));
        }
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(PanacheEntityBase::listAll)
                    .thenReturn(List.of(row("cash.drawer-cap", "not-a-number")));
            assertEquals(0, new java.math.BigDecimal("0.00").compareTo(new PosSettingsService().drawerCap()));
        }
    }

    /**
     * {@code gestureEndorsementRequired(key)} (EXP-32) reads the per-gesture flag:
     * cancel-line defaults on, cancel-last defaults off, and a stored override wins
     * — both arms of one gesture plus a distinct default.
     */
    @Test
    void gestureEndorsementRequiredReadsPerGestureFlag() {
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(PanacheEntityBase::listAll).thenReturn(List.of());
            assertTrue(new PosSettingsService().gestureEndorsementRequired("cancel-line"));
            assertFalse(new PosSettingsService().gestureEndorsementRequired("cancel-last"));
        }
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(PanacheEntityBase::listAll)
                    .thenReturn(List.of(row("gesture.endorsement-cancel-line", "false")));
            assertFalse(new PosSettingsService().gestureEndorsementRequired("cancel-line"));
        }
    }

    /**
     * {@code endorsementScope} (EXP-33) defaults to PER_OP and returns a stored
     * PER_TICKET override — both arms.
     */
    @Test
    void endorsementScopeReadsAdministeredValue() {
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(PanacheEntityBase::listAll).thenReturn(List.of());
            assertEquals("PER_OP", new PosSettingsService().endorsementScope());
        }
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(PanacheEntityBase::listAll)
                    .thenReturn(List.of(row("gesture.endorsement-scope", "PER_TICKET")));
            assertEquals("PER_TICKET", new PosSettingsService().endorsementScope());
        }
    }
}
