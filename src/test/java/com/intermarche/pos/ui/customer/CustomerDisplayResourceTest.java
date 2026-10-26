package com.intermarche.pos.ui.customer;

import com.intermarche.pos.domain.sale.Ticket;
import com.intermarche.pos.ui.PosState;
import com.intermarche.pos.ui.payment.PaymentState;
import com.intermarche.pos.ui.ticket.TicketState;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import io.quarkus.qute.Template;
import io.quarkus.qute.TemplateInstance;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link CustomerDisplayResource}, the read-only customer-facing
 * second screen. Both endpoints are thin projections of {@link PosState}:
 * {@code customerPage()} renders a template and {@code customerData()} builds a
 * version-gated JSON snapshot delegating to the private {@code digitalPath()}
 * and {@code quantityDisplay()} helpers. All collaborators are mocked; the
 * {@code Ticket.findById} static finder resolves to {@link PanacheEntityBase}
 * under plain {@code mvn test} and is intercepted with
 * {@link org.mockito.Mockito#mockStatic}. Tests cover the version gate (both
 * arms and the null client version), the change-amount ternary, the empty and
 * populated item loop, the three digital-path guards and the three quantity
 * display formats (20 branches).
 */
class CustomerDisplayResourceTest {

    /**
     * Builds a {@link CustomerDisplayResource} whose collaborators are fresh
     * mocks wired onto its package-private fields, including mocked
     * {@link PosState#ticket} and {@link PosState#payment} sub-states with an
     * empty item list so no direct field access hits a null.
     *
     * @return a resource with fully mocked state and template
     */
    private CustomerDisplayResource newResource() {
        CustomerDisplayResource resource = new CustomerDisplayResource();
        resource.posSettingsService = mock(com.intermarche.pos.service.PosSettingsService.class);
        when(resource.posSettingsService.customerOpenMessage()).thenReturn("Bienvenue");
        when(resource.posSettingsService.customerClosedMessage()).thenReturn("Caisse fermée");
        // The customer-display QR defaults to ON, the pre-existing behavior the
        // digital-path cases rely on (BO-10-07-02).
        when(resource.posSettingsService.customerQrEnabled()).thenReturn(true);
        resource.state = mock(PosState.class);
        resource.state.ticket = mock(TicketState.class);
        resource.state.ticket.items = new ArrayList<>();
        when(resource.state.ticket.mealVoucherEligibleTotal()).thenReturn(BigDecimal.ZERO);
        resource.state.payment = mock(PaymentState.class);
        resource.state.payment.payments = new ArrayList<>();
        resource.i18n = com.intermarche.pos.ui.TestMessages.french();
        resource.cashMenuService = mock(com.intermarche.pos.ui.home.CashMenuService.class);
        when(resource.cashMenuService.current()).thenReturn(
                new com.intermarche.pos.ui.home.CashMenuService.View(null, java.util.List.of(), "", "#cartLines{}"));
        return resource;
    }

    /**
     * Builds a mocked ticket item carrying the given quantity fields and a
     * stubbed formatted price.
     *
     * @param plu the PLU code, or null
     * @param quantity the quantity
     * @param label the display label
     * @param priceFormatted the value returned by {@code getPriceFormatted()}
     * @return the mocked ticket item
     */
    private TicketState.TicketItem item(String plu, BigDecimal quantity, String label, String priceFormatted) {
        TicketState.TicketItem it = mock(TicketState.TicketItem.class);
        it.plu = plu;
        it.quantity = quantity;
        it.label = label;
        when(it.getPriceFormatted()).thenReturn(priceFormatted);
        return it;
    }

    // --- customerPage ---

    /**
     * {@code customerPage()} renders the customer template bound to the state,
     * at once, so that the page is the rendered text.
     */
    @Test
    void customerPageRendersCustomerTemplate() {
        CustomerDisplayResource resource = newResource();
        resource.customer = mock(Template.class);
        TemplateInstance view = mock(TemplateInstance.class);
        when(resource.customer.data("state", resource.state)).thenReturn(view);
        when(view.data(org.mockito.ArgumentMatchers.eq("customerCss"), org.mockito.ArgumentMatchers.any())).thenReturn(view);
        when(view.render()).thenReturn("<html>");
        assertEquals("<html>", resource.customerPage());
    }

    /**
     * LC-13-06-08: the page is rendered INSIDE the customer's language, which a
     * template instance rendered after the method returned would not be.
     */
    @Test
    void customerPageIsRenderedInTheCustomersLanguage() {
        CustomerDisplayResource resource = newResource();
        resource.i18n = com.intermarche.pos.ui.TestMessages.french(resource.state);
        resource.state.customerLanguage = "nl";
        resource.customer = mock(Template.class);
        TemplateInstance view = mock(TemplateInstance.class);
        when(resource.customer.data("state", resource.state)).thenReturn(view);
        when(view.data(org.mockito.ArgumentMatchers.eq("customerCss"), org.mockito.ArgumentMatchers.any())).thenReturn(view);
        when(view.render()).thenAnswer(call -> resource.i18n.getLang() + ":" + resource.i18n.get("common.back"));
        assertEquals("nl:TERUG", resource.customerPage());
        assertEquals("fr", resource.i18n.getLang());
    }

    // --- the customer's language (LC-13-06-08 / -13) ---

    /**
     * Builds a mocked resolver that runs the customer scope through, offers the
     * three shipped languages named in their own language and answers each
     * mention by its key.
     *
     * @return the mocked resolver
     */
    @SuppressWarnings("unchecked")
    private com.intermarche.pos.ui.Messages offeringMessages() {
        com.intermarche.pos.ui.Messages messages = mock(com.intermarche.pos.ui.Messages.class);
        when(messages.forCustomerDisplay(org.mockito.ArgumentMatchers.any()))
                .thenAnswer(call -> ((java.util.function.Supplier<Object>) call.getArgument(0)).get());
        when(messages.both(org.mockito.ArgumentMatchers.anyString())).thenAnswer(call -> call.getArgument(0));
        when(messages.getAvailableLanguages()).thenReturn(List.of("fr", "nl", "pt"));
        when(messages.languageName("fr")).thenReturn("Français");
        when(messages.languageName("nl")).thenReturn("Nederlands");
        when(messages.languageName("pt")).thenReturn("Português");
        when(messages.getLang()).thenReturn("nl");
        return messages;
    }

    /**
     * The choice is recorded only when the store offers it, the register is
     * signed in and the language is one the register offers; every refusal
     * changes nothing and the answer is always 204.
     */
    @Test
    void chooseLanguageRecordsOnlyAnOfferedLanguageOnASignedInRegister() {
        CustomerDisplayResource off = newResource();
        off.i18n = offeringMessages();
        assertEquals(204, off.chooseLanguage("nl").getStatus());
        assertNull(off.state.customerLanguage);
        CustomerDisplayResource locked = newResource();
        locked.i18n = offeringMessages();
        when(locked.posSettingsService.customerLanguageChoice()).thenReturn(true);
        when(locked.state.isLocked()).thenReturn(true);
        assertEquals(204, locked.chooseLanguage("nl").getStatus());
        assertNull(locked.state.customerLanguage);
        CustomerDisplayResource forged = newResource();
        forged.i18n = offeringMessages();
        when(forged.posSettingsService.customerLanguageChoice()).thenReturn(true);
        assertEquals(204, forged.chooseLanguage("de").getStatus());
        assertEquals(204, forged.chooseLanguage(null).getStatus());
        assertNull(forged.state.customerLanguage);
        org.mockito.Mockito.verify(forged.state, org.mockito.Mockito.never()).touch();
        CustomerDisplayResource chosen = newResource();
        chosen.i18n = offeringMessages();
        when(chosen.posSettingsService.customerLanguageChoice()).thenReturn(true);
        assertEquals(204, chosen.chooseLanguage("nl").getStatus());
        assertEquals("nl", chosen.state.customerLanguage);
        verify(chosen.state).touch();
    }

    /**
     * The display offers one key per language, named in its own language, only
     * where the store offers the choice, the register is signed in and there is
     * more than one language; it always states the language it is drawn in.
     */
    @Test
    @SuppressWarnings("unchecked")
    void customerDataOffersTheLanguageKeysOnlyWhenThereIsAChoice() {
        CustomerDisplayResource off = newResource();
        off.i18n = offeringMessages();
        assertEquals(List.of(), off.customerData(null).get("languages"));
        assertEquals("nl", off.customerData(null).get("displayLang"));
        CustomerDisplayResource locked = newResource();
        locked.i18n = offeringMessages();
        when(locked.posSettingsService.customerLanguageChoice()).thenReturn(true);
        when(locked.state.isLocked()).thenReturn(true);
        assertEquals(List.of(), locked.customerData(null).get("languages"));
        CustomerDisplayResource single = newResource();
        single.i18n = offeringMessages();
        when(single.i18n.getAvailableLanguages()).thenReturn(List.of("fr"));
        when(single.posSettingsService.customerLanguageChoice()).thenReturn(true);
        assertEquals(List.of(), single.customerData(null).get("languages"));
        CustomerDisplayResource offered = newResource();
        offered.i18n = offeringMessages();
        when(offered.posSettingsService.customerLanguageChoice()).thenReturn(true);
        List<Map<String, String>> keys = (List<Map<String, String>>) offered.customerData(null).get("languages");
        assertEquals(List.of(Map.of("tag", "fr", "name", "Français"), Map.of("tag", "nl", "name", "Nederlands"),
                Map.of("tag", "pt", "name", "Português")), keys);
    }

    /**
     * LC-13-06-08: with the real bundles, a customer's choice redraws every
     * mention of the payload in that language, monolingual, and the payload
     * names the language; without a choice the cashier's language is kept.
     */
    @Test
    void customerDataSpeaksTheCustomersLanguage() {
        CustomerDisplayResource resource = newResource();
        resource.i18n = com.intermarche.pos.ui.TestMessages.french(resource.state);
        assertEquals("fr", resource.customerData(null).get("displayLang"));
        assertEquals("A rendre", ((Map<?, ?>) resource.customerData(null).get("labels")).get("change"));
        resource.state.customerLanguage = "nl";
        assertEquals("nl", resource.customerData(null).get("displayLang"));
        assertEquals("Terug te geven", ((Map<?, ?>) resource.customerData(null).get("labels")).get("change"));
        assertEquals("fr", resource.i18n.getLang());
    }

    // --- customerData: version gate ---

    /**
     * {@code customerData()} reports no change when a non-null client version
     * matches the current state version (guard true / true).
     */
    @Test
    void customerDataUnchangedWhenVersionMatches() {
        CustomerDisplayResource resource = newResource();
        resource.state.version = 7L;
        Map<String, Object> result = resource.customerData(7L);
        assertEquals(1, result.size());
        assertEquals(false, result.get("changed"));
        verifyNoInteractions(resource.state.ticket);
    }

    /**
     * {@code customerData()} builds a full snapshot when a non-null client
     * version differs from the current one (guard true / false), with no
     * change amount (ternary false), an empty ticket (loop not entered) and a
     * null draft id (digital path short-circuit).
     */
    @Test
    void customerDataChangedWhenVersionDiffers() {
        CustomerDisplayResource resource = newResource();
        resource.state.version = 2L;
        resource.state.trainingMode = false;
        when(resource.state.isLocked()).thenReturn(false);
        when(resource.state.getRemainingFormatted()).thenReturn("0,00");
        when(resource.state.ticket.getTotalFormatted()).thenReturn("0,00");
        resource.state.payment.paymentInProgress = false;
        resource.state.payment.transactionComplete = false;
        resource.state.payment.lastChangeAmount = null;
        resource.state.payment.ticketDbId = null;
        resource.state.customerMessage = "";
        Map<String, Object> result = resource.customerData(1L);
        assertEquals(27, result.size());
        assertEquals("0,00", result.get("mealEligible"));
        assertEquals(true, result.get("changed"));
        assertEquals(2L, result.get("version"));
        assertEquals(false, result.get("locked"));
        assertEquals("Bienvenue", result.get("welcomeMessage"));
        assertEquals(false, result.get("training"));
        assertEquals(true, result.get("empty"));
        assertEquals("0,00", result.get("total"));
        assertEquals("0,00", result.get("remaining"));
        assertEquals(false, result.get("paying"));
        assertEquals(false, result.get("complete"));
        assertEquals("", result.get("change"));
        assertEquals("", result.get("message"));
        assertEquals("", result.get("digitalPath"));
        List<?> items = (List<?>) result.get("items");
        assertTrue(items.isEmpty());
    }

    /**
     * {@code customerData()} builds a full snapshot when the client version is
     * null (guard short-circuit false), carrying a change amount (ternary
     * true), a populated ticket (loop entered) covering the four quantity
     * formats — kilograms, whole units, fractional units, and the empty
     * quantity of a price-embedded sticker — and a resolvable digital path
     * (all three guards false).
     */
    @Test
    void customerDataFullSnapshotWithItemsAndChangeAndDigitalPath() {
        CustomerDisplayResource resource = newResource();
        resource.state.version = 9L;
        resource.state.trainingMode = true;
        when(resource.state.isLocked()).thenReturn(true);
        when(resource.state.getRemainingFormatted()).thenReturn("0,00");
        when(resource.state.ticket.getTotalFormatted()).thenReturn("12,34");
        resource.state.payment.paymentInProgress = true;
        resource.state.payment.transactionComplete = true;
        resource.state.payment.lastChangeAmount = new BigDecimal("2.5");
        resource.state.payment.ticketDbId = 42L;
        resource.state.customerMessage = "PAIEMENT REFUSE";
        resource.state.ticket.items.add(item("100", new BigDecimal("1.5"), "Bananas", "3,00"));
        resource.state.ticket.items.add(item(null, new BigDecimal("2"), "Milk", "2,00"));
        resource.state.ticket.items.add(item("", new BigDecimal("1.25"), "Nails", "4,00"));
        TicketState.TicketItem sticker = item("200", BigDecimal.ONE, "Ham", "5,00");
        sticker.priceEmbedded = true;
        resource.state.ticket.items.add(sticker);
        Ticket ticket = mock(Ticket.class);
        ticket.id = 42L;
        ticket.digitalKey = "ABCDEF0123456789";
        when(resource.state.getCashRoundedRemainingFormatted()).thenReturn("24,60");
        when(resource.state.isCashRoundingVisible()).thenReturn(true);
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            mocked.when(() -> Ticket.findById(42L)).thenReturn(ticket);
            Map<String, Object> result = resource.customerData(null);
            assertEquals(27, result.size());
            assertEquals("0,00", result.get("mealEligible"));
            assertEquals(true, result.get("changed"));
            assertEquals(9L, result.get("version"));
            assertEquals(true, result.get("locked"));
            assertEquals("Caisse fermée", result.get("welcomeMessage"));
            assertEquals(true, result.get("training"));
            assertEquals(false, result.get("empty"));
            assertEquals("12,34", result.get("total"));
            assertEquals(true, result.get("paying"));
            assertEquals(true, result.get("complete"));
            assertEquals("2,50", result.get("change"));
            assertEquals("PAIEMENT REFUSE", result.get("message"));
            assertEquals("/t/42/ABCDEF0123456789", result.get("digitalPath"));
            // LC-07-03-03: where the shop rounds, the customer display carries BOTH
            // figures — what the sale owes and what can be handed over in coins.
            assertEquals("24,60", result.get("roundedRemaining"));
            assertEquals(true, result.get("rounding"));
            @SuppressWarnings("unchecked")
            List<Map<String, String>> items = (List<Map<String, String>>) result.get("items");
            assertEquals(4, items.size());
            assertEquals("Bananas", items.get(0).get("label"));
            assertEquals("1,500 kg", items.get(0).get("qty"));
            assertEquals("3,00", items.get(0).get("amount"));
            assertEquals("Milk", items.get(1).get("label"));
            assertEquals("x2", items.get(1).get("qty"));
            assertEquals("2,00", items.get(1).get("amount"));
            assertEquals("Nails", items.get(2).get("label"));
            assertEquals("x1,25", items.get(2).get("qty"));
            assertEquals("Ham", items.get(3).get("label"));
            assertEquals("", items.get(3).get("qty"));
            assertEquals("4,00", items.get(2).get("amount"));
        }
    }

    /**
     * BO-10-07-02: with the customer-display QR DISABLED, the snapshot carries
     * an empty digital path — the {@code digitalPath()} helper is
     * short-circuited by the {@code customerQrEnabled()} false arm, so no
     * ticket lookup happens.
     */
    @Test
    void customerDataQrDisabledYieldsEmptyDigitalPath() {
        CustomerDisplayResource resource = newResource();
        when(resource.posSettingsService.customerQrEnabled()).thenReturn(false);
        resource.state.version = 1L;
        when(resource.state.isLocked()).thenReturn(false);
        when(resource.state.getRemainingFormatted()).thenReturn("0,00");
        when(resource.state.ticket.getTotalFormatted()).thenReturn("0,00");
        resource.state.payment.lastChangeAmount = null;
        Map<String, Object> result = resource.customerData(null);
        assertEquals("", result.get("digitalPath"));
    }

    /**
     * BO-10-02-35: a line resolved as showing its article code exposes it on
     * the customer screen while a line resolved otherwise does not; the lines
     * are resolved before the projection when the service is there.
     */
    @Test
    void customerDataShowsEanOnlyWhenEnabledAndPresent() {
        CustomerDisplayResource resource = newResource();
        resource.articleCodeService = mock(com.intermarche.pos.ui.ticket.ArticleCodeService.class);
        resource.state.version = 3L;
        when(resource.state.isLocked()).thenReturn(false);
        when(resource.state.getRemainingFormatted()).thenReturn("0,00");
        when(resource.state.ticket.getTotalFormatted()).thenReturn("3,00");
        resource.state.payment.lastChangeAmount = null;
        TicketState.TicketItem withEan = item(null, new BigDecimal("1"), "PAIN", "2,00");
        withEan.ean = "3017620422003";
        when(withEan.getCodeShown()).thenReturn(true);
        TicketState.TicketItem noEan = item(null, new BigDecimal("1"), "LAIT", "1,00");
        noEan.ean = null;
        resource.state.ticket.items.add(withEan);
        resource.state.ticket.items.add(noEan);
        Map<String, Object> result = resource.customerData(null);
        @SuppressWarnings("unchecked")
        List<Map<String, String>> items = (List<Map<String, String>>) result.get("items");
        assertEquals("3017620422003", items.get(0).get("ean"));
        assertFalse(items.get(1).containsKey("ean"));
        org.mockito.Mockito.verify(resource.articleCodeService).resolve(resource.state.ticket);
    }

    /**
     * LC-10-01-03: applied engine and manual labels accompany their article;
     * cancelled labels disappear, and the allocated ticket discount is explicit.
     */
    @Test
    void customerDataProjectsAppliedAdvantagesAndTicketDiscount() {
        CustomerDisplayResource resource = newResource();
        TicketState.TicketItem discounted = item(null, BigDecimal.ONE, "Milk", "8,00");
        discounted.promotionLabel = "Bundle <offer>";
        when(discounted.getModifierLabel()).thenReturn("Discount 10 %");
        TicketState.TicketItem cancelled = item(null, BigDecimal.ONE, "Cancelled", "0,00");
        cancelled.cancelled = true;
        cancelled.promotionLabel = "Obsolete offer";
        when(cancelled.getModifierLabel()).thenReturn("Obsolete discount");
        resource.state.ticket.items.add(discounted);
        resource.state.ticket.items.add(cancelled);
        resource.state.ticket.globalDiscountApplied = new BigDecimal("0.40");
        resource.state.ticket.globalDiscountType = "PERCENT";
        resource.state.ticket.globalDiscountValue = new BigDecimal("5");
        when(resource.state.ticket.getGlobalDiscountAppliedFormatted()).thenReturn("-0,40");
        Map<String, Object> data = resource.customerData(null);
        @SuppressWarnings("unchecked")
        List<Map<String, String>> items = (List<Map<String, String>>) data.get("items");
        assertEquals("Bundle <offer>", items.get(0).get("promotion"));
        assertEquals("Discount 10 %", items.get(0).get("modifier"));
        assertEquals("", items.get(1).get("promotion"));
        assertEquals("", items.get(1).get("modifier"));
        assertEquals("REMISE TICKET (5 %)", data.get("discountLabel"));
        resource.state.ticket.globalDiscountType = "AMOUNT";
        assertEquals("REMISE TICKET", resource.customerData(null).get("discountLabel"));
        assertEquals("-0,40", data.get("discountAmount"));
        assertEquals("8,00", items.get(0).get("amount"));
    }

    /** LC-10-01-17: show every applied settlement, resolve administered labels,
     * preserve voucher wording and cover every bilingual fallback and absent/blank label. */
    @Test
    void customerPaymentsShowAppliedAmountsAndEveryTenderLabel() {
        CustomerDisplayResource resource = newResource();
        var cash = new PaymentState.PaymentEntry("CASH", new BigDecimal("2.50"), new BigDecimal("10"));
        resource.state.payment.payments.add(cash);
        resource.state.payment.payments.add(new PaymentState.PaymentEntry("Shop voucher", BigDecimal.ONE, "123", true));
        try (MockedStatic<com.intermarche.pos.domain.payment.TenderDefinition> tenders =
                     mockStatic(com.intermarche.pos.domain.payment.TenderDefinition.class)) {
            var definition = new com.intermarche.pos.domain.payment.TenderDefinition();
            definition.label = "Cash <admin>";
            tenders.when(() -> com.intermarche.pos.domain.payment.TenderDefinition.findByCode("CASH"))
                    .thenReturn(definition);
            @SuppressWarnings("unchecked")
            var rows = (List<Map<String, String>>) resource.customerData(null).get("payments");
            assertEquals("Cash <admin>", rows.get(0).get("label"));
            assertEquals("2,50", rows.get(0).get("amount"));
            assertEquals("Shop voucher", rows.get(1).get("label"));
            definition.label = null;
            assertTrue(resource.customerData(null).get("payments").toString().contains("ESPECES"));
            definition.label = "  ";
            assertTrue(resource.customerData(null).get("payments").toString().contains("ESPECES"));
            resource.state.payment.payments.clear();
            var keys = com.intermarche.pos.domain.payment.PaymentTypes.keys();
            for (String key : keys) resource.state.payment.payments.add(new PaymentState.PaymentEntry(key, BigDecimal.ONE));
            resource.state.payment.payments.add(new PaymentState.PaymentEntry("CUSTOM", BigDecimal.ONE));
            @SuppressWarnings("unchecked")
            var all = (List<Map<String, String>>) resource.customerData(null).get("payments");
            assertEquals(keys.size() + 1, all.size());
            assertEquals("CARTE BANCAIRE", all.get(1).get("label"));
            assertEquals("CUSTOM", all.get(all.size() - 1).get("label"));
        }
    }

    /** LC-07-15-02: the amount fed into the cash changer rides the payload, empty when no deposit runs. */
    @Test
    void customerDataCarriesTheCashChangerInsertion() {
        CustomerDisplayResource resource = newResource();
        try (MockedStatic<com.intermarche.pos.domain.payment.TenderDefinition> tenders =
                     mockStatic(com.intermarche.pos.domain.payment.TenderDefinition.class)) {
            when(resource.state.payment.getCashChangerInsertedFormatted()).thenReturn("12,50");
            assertEquals("12,50", resource.customerData(null).get("cashChangerInserted"));
            @SuppressWarnings("unchecked")
            Map<String, String> labels = (Map<String, String>) resource.customerData(null).get("labels");
            assertEquals("Inséré", labels.get("inserted"));
        }
    }

    /** LC-07-04-07/08: the cash given back by a card cashback names itself, with its signed amount. */
    @Test
    void customerPaymentsNameTheCashback() {
        CustomerDisplayResource resource = newResource();
        resource.state.payment.payments.add(new PaymentState.PaymentEntry("CARD", new BigDecimal("70.00")));
        var cashback = new PaymentState.PaymentEntry("CASH", new BigDecimal("-20.00"), null);
        cashback.cashback = true;
        resource.state.payment.payments.add(cashback);
        try (MockedStatic<com.intermarche.pos.domain.payment.TenderDefinition> tenders =
                     mockStatic(com.intermarche.pos.domain.payment.TenderDefinition.class)) {
            @SuppressWarnings("unchecked")
            var rows = (List<Map<String, String>>) resource.customerData(null).get("payments");
            assertEquals("CARTE BANCAIRE", rows.get(0).get("label"));
            assertEquals("Cashback espèces", rows.get(1).get("label"));
            assertEquals("-20,00", rows.get(1).get("amount"));
        }
    }

    /** LC-13-06-06: dynamic mentions call the bilingual resolver without changing article labels. */
    @Test
    void dynamicCustomerMentionsUseTheBilingualResolver() {
        CustomerDisplayResource resource = newResource();
        resource.i18n = mock(com.intermarche.pos.ui.Messages.class);
        when(resource.i18n.forCustomerDisplay(org.mockito.ArgumentMatchers.any()))
                .thenAnswer(call -> ((java.util.function.Supplier<?>) call.getArgument(0)).get());
        when(resource.i18n.both(org.mockito.ArgumentMatchers.anyString()))
                .thenAnswer(call -> "NL / " + call.getArgument(0));
        resource.state.ticket.items.add(item(null, BigDecimal.ONE, "Milk", "1,00"));
        Map<String, Object> data = resource.customerData(null);
        assertEquals("Milk", ((List<?>) data.get("items")).stream()
                .map(row -> ((Map<?, ?>) row).get("label")).findFirst().orElseThrow());
        assertEquals("NL / customer.paying", ((Map<?, ?>) data.get("labels")).get("paying"));
        assertEquals("NL / customer.digital", ((Map<?, ?>) data.get("labels")).get("digital"));
        assertEquals("NL / customer.tare", ((Map<?, ?>) data.get("labels")).get("tare"));
        assertEquals("NL / customer.fidelityBalance", ((Map<?, ?>) data.get("labels")).get("fidelityBalance"));
    }

    /** LC-14-02-07: a weighing carries the tare really applied to it; a line without one carries no tare key. */
    @Test
    void appliedTareReachesTheCustomerLineOnlyWhenOneWasApplied() {
        CustomerDisplayResource resource = newResource();
        resource.state.ticket = new TicketState();
        TicketState.TicketItem weighed = new TicketState.TicketItem("2000001", "1234", "Pommes",
                new BigDecimal("2.50"), new BigDecimal("0.750"), BigDecimal.ZERO);
        weighed.appliedTareKg = new BigDecimal("0.05");
        TicketState.TicketItem zeroTare = new TicketState.TicketItem("2000002", "1235", "Poires",
                new BigDecimal("3.00"), new BigDecimal("0.500"), BigDecimal.ZERO);
        zeroTare.appliedTareKg = BigDecimal.ZERO;
        resource.state.ticket.items.add(weighed);
        resource.state.ticket.items.add(zeroTare);
        resource.state.ticket.items.add(new TicketState.TicketItem("3300000000002", null, "Lait",
                new BigDecimal("2.76"), BigDecimal.ONE, BigDecimal.ZERO));
        @SuppressWarnings("unchecked")
        List<Map<String, String>> items = (List<Map<String, String>>) resource.customerData(null).get("items");
        assertEquals("0,050 kg", items.get(0).get("tare"));
        assertFalse(items.get(1).containsKey("tare"));
        assertFalse(items.get(2).containsKey("tare"));
        assertEquals("Tare déduite", ((Map<?, ?>) resource.customerData(null).get("labels")).get("tare"));
    }

    /** LC-03-04-13: the available balance is shown only when administered, with a card attached and a balance read. */
    @Test
    void fidelityBalanceFollowsTheSettingTheCardAndTheBalance() {
        CustomerDisplayResource resource = newResource();
        resource.state.fidelity = new com.intermarche.pos.ui.fidelity.FidelityState();
        resource.state.fidelity.assignCard("6045200000123");
        resource.state.fidelity.availableBalance = new BigDecimal("12.345");
        assertEquals("", resource.customerData(null).get("fidelityBalance"));
        when(resource.posSettingsService.fidelityShowBalanceOnCustomerDisplay()).thenReturn(true);
        assertEquals("12,35", resource.customerData(null).get("fidelityBalance"));
        assertEquals("", resource.customerData(null).get("fidelityHolder"));
        resource.state.fidelity.availableBalance = null;
        assertEquals("", resource.customerData(null).get("fidelityBalance"));
        resource.state.fidelity.availableBalance = new BigDecimal("4.00");
        resource.state.fidelity.active = false;
        assertEquals("", resource.customerData(null).get("fidelityBalance"));
        assertEquals("Cagnotte disponible", ((Map<?, ?>) resource.customerData(null).get("labels")).get("fidelityBalance"));
    }

    /** LC-10-01-07: engine eligibility takes precedence without subtracting tender caps or payments. */
    @Test
    void mealEligibilityUsesEngineBaseThroughPayment() {
        CustomerDisplayResource resource = newResource();
        resource.state.payment.valuationStatus = "ENGINE";
        resource.state.payment.valuationMealEligible = new BigDecimal("17.235");
        resource.state.payment.valuationMealThreshold = new BigDecimal("10.00");
        resource.state.payment.paymentInProgress = true;
        when(resource.state.ticket.mealVoucherEligibleTotal()).thenReturn(new BigDecimal("5.00"));
        assertEquals("17,24", resource.customerData(null).get("mealEligible"));
        resource.state.payment.valuationMealEligible = new BigDecimal("-2.00");
        assertEquals("0,00", resource.customerData(null).get("mealEligible"));
    }

    /** LC-10-01-07: absent engine hints and degraded valuation use the current local eligible base. */
    @Test
    void mealEligibilityFallsBackWithoutUsableEngineHint() {
        CustomerDisplayResource resource = newResource();
        when(resource.state.ticket.mealVoucherEligibleTotal()).thenReturn(new BigDecimal("6.50"));
        assertEquals("6,50", resource.customerData(null).get("mealEligible"));
        resource.state.payment.valuationStatus = "ENGINE";
        assertEquals("6,50", resource.customerData(null).get("mealEligible"));
        resource.state.payment.valuationStatus = "LOCAL";
        resource.state.payment.valuationMealEligible = new BigDecimal("99.00");
        assertEquals("6,50", resource.customerData(null).get("mealEligible"));
    }

    /** LC-10-01-07: local eligibility follows additions, valued discounts, allocated discounts and removal. */
    @Test
    void mealEligibilityFollowsLiveTicketChanges() {
        CustomerDisplayResource resource = newResource();
        resource.state.ticket = new TicketState();
        TicketState.TicketItem food = new TicketState.TicketItem("1", null, "Food",
                new BigDecimal("8.00"), BigDecimal.ONE, BigDecimal.ZERO);
        food.mealVoucherEligible = true;
        resource.state.ticket.items.add(food);
        resource.state.ticket.items.add(new TicketState.TicketItem("2", null, "Other",
                new BigDecimal("4.00"), BigDecimal.ONE, BigDecimal.ZERO));
        assertEquals("8,00", resource.customerData(null).get("mealEligible"));
        food.valuedTotal = new BigDecimal("7.00");
        food.globalDiscountShare = new BigDecimal("0.50");
        assertEquals("6,50", resource.customerData(null).get("mealEligible"));
        resource.state.ticket.items.remove(food);
        resource.state.ticket.cancelledItems.add(food);
        assertEquals("0,00", resource.customerData(null).get("mealEligible"));
    }

    /**
     * Builds a live ticket line carrying restricted-tender eligibilities.
     *
     * @param ean the article code
     * @param price the unit price
     * @param quantity the quantity
     * @param eligibilities the snapshotted eligibilities, or null
     * @return the line
     */
    private TicketState.TicketItem restrictedLine(String ean, String price, String quantity, String eligibilities) {
        TicketState.TicketItem line = new TicketState.TicketItem(ean, null, "Article " + ean,
                new BigDecimal(price), new BigDecimal(quantity), BigDecimal.ZERO);
        line.restrictedTenders = eligibilities;
        return line;
    }

    /**
     * Reads the restricted-tender bases the customer display was given.
     *
     * @param resource the resource under test
     * @return the projected rows
     */
    @SuppressWarnings("unchecked")
    private List<Map<String, String>> restrictedRows(CustomerDisplayResource resource) {
        return (List<Map<String, String>>) resource.customerData(null).get("restrictedEligible");
    }

    /** LC-10-01-09/11: with neither base administered the customer display shows none, whatever the lines carry. */
    @Test
    void restrictedBasesStayHiddenUntilTheShopShowsThem() {
        CustomerDisplayResource resource = newResource();
        resource.state.ticket = new TicketState();
        resource.state.ticket.items.add(restrictedLine("1", "4.00", "1", "ECO_VOUCHER_ELIGIBLE,SOCIAL_CARD_ELIGIBLE"));
        assertTrue(restrictedRows(resource).isEmpty());
    }

    /** LC-10-01-09: the eco-voucher base alone, shown at zero before any eligible article and refreshed after it. */
    @Test
    void ecoVoucherBaseIsShownAloneAndFollowsTheTicket() {
        CustomerDisplayResource resource = newResource();
        when(resource.posSettingsService.customerEcoVoucherEligible()).thenReturn(true);
        resource.state.ticket = new TicketState();
        resource.state.ticket.items.add(restrictedLine("2", "3.00", "1", null));
        List<Map<String, String>> rows = restrictedRows(resource);
        assertEquals(1, rows.size());
        assertEquals("Articles éligibles aux éco-chèques", rows.get(0).get("label"));
        assertEquals("0,00", rows.get(0).get("amount"));
        resource.state.ticket.items.add(restrictedLine("1", "2.505", "2", "ECO_VOUCHER_ELIGIBLE"));
        assertEquals("5,01", restrictedRows(resource).get(0).get("amount"));
    }

    /** LC-10-01-11: the social-card base alone, ignoring eco-voucher lines and negative lines. */
    @Test
    void socialCardBaseIsShownAloneAndIgnoresOtherEligibilities() {
        CustomerDisplayResource resource = newResource();
        when(resource.posSettingsService.customerSocialCardEligible()).thenReturn(true);
        resource.state.ticket = new TicketState();
        resource.state.ticket.items.add(restrictedLine("1", "4.00", "1", "ECO_VOUCHER_ELIGIBLE"));
        resource.state.ticket.items.add(restrictedLine("3", "6.00", "1", "SOCIAL_CARD_ELIGIBLE"));
        resource.state.ticket.items.add(restrictedLine("4", "2.00", "-1", "SOCIAL_CARD_ELIGIBLE"));
        List<Map<String, String>> rows = restrictedRows(resource);
        assertEquals(1, rows.size());
        assertEquals("Articles éligibles à la carte achat sociale", rows.get(0).get("label"));
        assertEquals("6,00", rows.get(0).get("amount"));
    }

    /** LC-10-01-09/11: both bases in a fixed order, following valuation, allocated discount, payment entry and cancellation. */
    @Test
    void bothBasesFollowValuationDiscountPaymentAndCancellation() {
        CustomerDisplayResource resource = newResource();
        when(resource.posSettingsService.customerEcoVoucherEligible()).thenReturn(true);
        when(resource.posSettingsService.customerSocialCardEligible()).thenReturn(true);
        resource.state.ticket = new TicketState();
        TicketState.TicketItem both = restrictedLine("1", "10.00", "1", "ECO_VOUCHER_ELIGIBLE,SOCIAL_CARD_ELIGIBLE");
        resource.state.ticket.items.add(both);
        resource.state.ticket.items.add(restrictedLine("3", "6.00", "1", "SOCIAL_CARD_ELIGIBLE"));
        List<Map<String, String>> rows = restrictedRows(resource);
        assertEquals(2, rows.size());
        assertEquals("Articles éligibles aux éco-chèques", rows.get(0).get("label"));
        assertEquals("10,00", rows.get(0).get("amount"));
        assertEquals("Articles éligibles à la carte achat sociale", rows.get(1).get("label"));
        assertEquals("16,00", rows.get(1).get("amount"));
        both.valuedTotal = new BigDecimal("8.00");
        both.globalDiscountShare = new BigDecimal("0.80");
        resource.state.payment.paymentInProgress = true;
        rows = restrictedRows(resource);
        assertEquals("7,20", rows.get(0).get("amount"));
        assertEquals("13,20", rows.get(1).get("amount"));
        resource.state.ticket.items.remove(both);
        resource.state.ticket.cancelledItems.add(both);
        rows = restrictedRows(resource);
        assertEquals("0,00", rows.get(0).get("amount"));
        assertEquals("6,00", rows.get(1).get("amount"));
    }

    // --- digitalPath guards ---

    /**
     * {@code digitalPath()} yields an empty string when the draft id resolves
     * to no ticket (first arm of the null guard true).
     */
    @Test
    void customerDataDigitalPathEmptyWhenTicketNotFound() {
        CustomerDisplayResource resource = newResource();
        resource.state.version = 1L;
        when(resource.state.isLocked()).thenReturn(false);
        when(resource.state.getRemainingFormatted()).thenReturn("0,00");
        when(resource.state.ticket.getTotalFormatted()).thenReturn("0,00");
        resource.state.payment.lastChangeAmount = null;
        resource.state.payment.ticketDbId = 5L;
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            mocked.when(() -> Ticket.findById(5L)).thenReturn(null);
            Map<String, Object> result = resource.customerData(null);
            assertEquals("", result.get("digitalPath"));
        }
    }

    /**
     * {@code digitalPath()} yields an empty string when the found ticket has no
     * digital key (second arm of the null guard true).
     */
    @Test
    void customerDataDigitalPathEmptyWhenDigitalKeyNull() {
        CustomerDisplayResource resource = newResource();
        resource.state.version = 1L;
        when(resource.state.isLocked()).thenReturn(false);
        when(resource.state.getRemainingFormatted()).thenReturn("0,00");
        when(resource.state.ticket.getTotalFormatted()).thenReturn("0,00");
        resource.state.payment.lastChangeAmount = null;
        resource.state.payment.ticketDbId = 6L;
        Ticket ticket = mock(Ticket.class);
        ticket.id = 6L;
        ticket.digitalKey = null;
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            mocked.when(() -> Ticket.findById(6L)).thenReturn(ticket);
            Map<String, Object> result = resource.customerData(null);
            assertEquals("", result.get("digitalPath"));
            assertFalse(result.containsKey("nonexistent"));
        }
    }

    /**
     * LC-02-03-02: a non-weighed line sold under a unit of measure states the
     * quantity with its unit — the true/true leg of the
     * {@code unitName != null && !unitName.isBlank()} guard — while a line whose
     * unit name is present but blank falls back to the plain count, the
     * blank leg the null guard alone would miss.
     */
    @Test
    void customerDataFormatsTheUnitOfMeasureAndFallsBackWhenBlank() {
        CustomerDisplayResource resource = newResource();
        resource.state.version = 4L;
        when(resource.state.isLocked()).thenReturn(false);
        when(resource.state.getRemainingFormatted()).thenReturn("0,00");
        when(resource.state.ticket.getTotalFormatted()).thenReturn("0,00");
        resource.state.payment.lastChangeAmount = null;
        TicketState.TicketItem measured = item(null, new BigDecimal("2.36"), "CABLE", "5,00");
        measured.unitName = "m";
        TicketState.TicketItem blankUnit = item(null, new BigDecimal("2"), "LAIT", "2,00");
        blankUnit.unitName = "   ";
        resource.state.ticket.items.add(measured);
        resource.state.ticket.items.add(blankUnit);
        Map<String, Object> result = resource.customerData(null);
        @SuppressWarnings("unchecked")
        List<Map<String, String>> items = (List<Map<String, String>>) result.get("items");
        assertEquals("2,36 m", items.get(0).get("qty"));
        assertEquals("x2", items.get(1).get("qty"));
    }

    /**
     * BO-10-03-03: the customer display names the holder only where the shop
     * asked for it — the cashier-side rule is a separate decision.
     */
    @Test
    void theCustomerDisplayNamesTheHolderOnlyWhenAsked() {
        CustomerDisplayResource naming = newResource();
        naming.state.fidelity = new com.intermarche.pos.ui.fidelity.FidelityState();
        naming.state.fidelity.assignCard("6045200000123");
        naming.state.fidelity.holderLastName = "Dupont";
        naming.state.fidelity.holderFirstName = "Jean";
        when(naming.posSettingsService.fidelityShowHolderNameOnCustomerDisplay())
                .thenReturn(true);
        assertEquals("DUPONT Jean", naming.customerData(null).get("fidelityHolder"));

        CustomerDisplayResource silent = newResource();
        silent.state.fidelity = new com.intermarche.pos.ui.fidelity.FidelityState();
        silent.state.fidelity.assignCard("6045200000123");
        silent.state.fidelity.holderLastName = "Dupont";
        assertEquals("", silent.customerData(null).get("fidelityHolder"));
    }

    /**
     * Builds a resource whose resolver renders inside the display scope and
     * names a second language or none.
     *
     * @param second the second language the resolver reports, or null
     * @return the resource
     */
    @SuppressWarnings("unchecked")
    private CustomerDisplayResource bilingualResource(String second) {
        CustomerDisplayResource resource = newResource();
        resource.i18n = mock(com.intermarche.pos.ui.Messages.class);
        when(resource.i18n.forCustomerDisplay(org.mockito.ArgumentMatchers.any()))
                .thenAnswer(call -> ((java.util.function.Supplier<Object>) call.getArgument(0)).get());
        when(resource.i18n.both(org.mockito.ArgumentMatchers.anyString())).thenAnswer(call -> call.getArgument(0));
        when(resource.i18n.getSecondLang()).thenReturn(second);
        return resource;
    }

    /**
     * BO-10-07-17: on a bilingual display the administered translation stands
     * beside the welcome message; without a translation, without a second
     * language, or with a translation equal to the message, the message alone.
     */
    @Test
    void welcomeMessagesAreBilingualOnABilingualDisplay() {
        CustomerDisplayResource open = bilingualResource("nl");
        when(open.posSettingsService.customerOpenMessageSecond()).thenReturn("Welkom");
        assertEquals("Welkom / Bienvenue", open.customerData(null).get("welcomeMessage"));
        CustomerDisplayResource closed = bilingualResource("nl");
        when(closed.state.isLocked()).thenReturn(true);
        when(closed.posSettingsService.customerClosedMessageSecond()).thenReturn("Kassa gesloten");
        assertEquals("Kassa gesloten / Caisse fermée", closed.customerData(null).get("welcomeMessage"));
        assertEquals("Bienvenue", bilingualResource("nl").customerData(null).get("welcomeMessage"));
        CustomerDisplayResource monolingual = bilingualResource(null);
        when(monolingual.posSettingsService.customerOpenMessageSecond()).thenReturn("Welkom");
        assertEquals("Bienvenue", monolingual.customerData(null).get("welcomeMessage"));
        CustomerDisplayResource same = bilingualResource("nl");
        when(same.posSettingsService.customerOpenMessageSecond()).thenReturn("Bienvenue");
        assertEquals("Bienvenue", same.customerData(null).get("welcomeMessage"));
    }

    /**
     * BO-10-08-28/29: the customer display writes weights, measures and pieces
     * with the administered decimals and weight symbol.
     */
    @Test
    @SuppressWarnings("unchecked")
    void quantitiesFollowTheAdministeredWriting() {
        CustomerDisplayResource resource = newResource();
        when(resource.posSettingsService.unitFormat()).thenReturn(
                new com.intermarche.pos.domain.catalog.UnitFormat(1, 2, "KG"));
        TicketState.TicketItem weighed = new TicketState.TicketItem("2000001", "1234", "Pommes",
                new BigDecimal("2.50"), new BigDecimal("0.756"), BigDecimal.ZERO);
        weighed.appliedTareKg = new BigDecimal("0.05");
        TicketState.TicketItem measured = new TicketState.TicketItem("2000002", null, "Tissu",
                new BigDecimal("3.00"), new BigDecimal("2.36"), BigDecimal.ZERO);
        measured.unitName = "m";
        TicketState.TicketItem pieces = new TicketState.TicketItem("3300000000002", null, "Lait",
                new BigDecimal("1.00"), new BigDecimal("1.5"), BigDecimal.ZERO);
        resource.state.ticket.items.addAll(List.of(weighed, measured, pieces));
        List<Map<String, String>> items = (List<Map<String, String>>) resource.customerData(null).get("items");
        assertEquals("0,76 KG", items.get(0).get("qty"));
        assertEquals("0,05 KG", items.get(0).get("tare"));
        assertEquals("0,76 KG x 2,50 €/KG", items.get(0).get("detail"));
        assertEquals("2,4 m", items.get(1).get("qty"));
        assertEquals("x1,5", items.get(2).get("qty"));
    }
}
