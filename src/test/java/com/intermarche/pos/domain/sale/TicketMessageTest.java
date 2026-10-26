package com.intermarche.pos.domain.sale;

/** Unit contracts for HD_43 referential and document coverage. */
class TicketMessageTest {
    /** GB-01-07-05/06: persisted ticket wording can be reconstructed through the no-argument entity constructor. */
    @org.junit.jupiter.api.Test
    void storedTicketMessageDeserializesItsCodeTextAndPlacement() throws Exception {
        var message = new com.fasterxml.jackson.databind.ObjectMapper().readValue("{\"code\":\"OFFER\",\"text\":\"Offer applied\",\"printMode\":\"FOOTER\"}", TicketMessage.class);
        org.junit.jupiter.api.Assertions.assertEquals("OFFER", message.code);
        org.junit.jupiter.api.Assertions.assertEquals("Offer applied", message.text);
        org.junit.jupiter.api.Assertions.assertEquals("FOOTER", message.printMode);
    }

}
