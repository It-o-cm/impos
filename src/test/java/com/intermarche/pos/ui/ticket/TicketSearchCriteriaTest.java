package com.intermarche.pos.ui.ticket;

/** Unit contracts for HD_43 referential and document coverage. */
class TicketSearchCriteriaTest {
    /** LC-04-05-01: the real shared form preserves seven independent filters, the selected tender and explicit validation errors. */
    @org.junit.jupiter.api.Test
    void criteriaFormRendersIndependentValuesAndErrors() throws Exception {
        String source = java.nio.file.Files.readString(java.nio.file.Path.of("src/main/resources/templates/ticket-search-criteria.html"));
        var engine = io.quarkus.qute.Engine.builder().addDefaults()
                .addValueResolver(new io.quarkus.qute.ReflectionValueResolver())
                .addNamespaceResolver(io.quarkus.qute.NamespaceResolver.builder("inject")
                        .resolve(context -> com.intermarche.pos.ui.TestMessages.french()).build()).build();
        var template = engine.parse(source);
        var criteria = new TicketSearchCriteria();
        criteria.number = "C04-42";
        criteria.loyaltyCard = "999888777";
        criteria.articleCode = "12345670";
        criteria.date = "29/02/2024";
        criteria.amount = "12.50";
        criteria.terminalId = "C04";
        criteria.paymentMethod = "CARD";
        criteria.errorKey = "ticketsearch.invalidDate";
        String html = template.data("criteria", criteria).data("searchAction", "/return/search/criteria").render();
        for (String field : java.util.List.of("number", "loyaltyCard", "articleCode", "date", "amount", "terminalId", "paymentMethod")) {
            org.junit.jupiter.api.Assertions.assertTrue(html.contains("name=\"" + field + "\""));
        }
        org.junit.jupiter.api.Assertions.assertTrue(html.contains("value=\"C04-42\""));
        org.junit.jupiter.api.Assertions.assertTrue(html.contains("value=\"12345670\""));
        org.junit.jupiter.api.Assertions.assertTrue(html.contains("value=\"CARD\" selected"));
        org.junit.jupiter.api.Assertions.assertTrue(html.contains("DATE INVALIDE"));
        org.junit.jupiter.api.Assertions.assertTrue(html.contains("action=\"/return/search/criteria\""));
        criteria.errorKey = null;
        html = template.data("criteria", criteria).data("searchAction", "/reprint/search/criteria").render();
        org.junit.jupiter.api.Assertions.assertFalse(html.contains("DATE INVALIDE"));
        org.junit.jupiter.api.Assertions.assertTrue(html.contains("action=\"/reprint/search/criteria\""));
    }

}
