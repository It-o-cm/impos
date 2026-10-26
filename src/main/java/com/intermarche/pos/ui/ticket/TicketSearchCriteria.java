package com.intermarche.pos.ui.ticket;

/** LC-04-05-01: independent, cumulative ticket-search fields shared by return and duplicata. */
public class TicketSearchCriteria implements java.io.Serializable {
    private static final long serialVersionUID = 1L;
    /** Ticket-number fragment. */
    @jakarta.ws.rs.FormParam("number") @jakarta.ws.rs.DefaultValue("") public String number = "";
    /** Exact loyalty-card number. */
    @jakarta.ws.rs.FormParam("loyaltyCard") @jakarta.ws.rs.DefaultValue("") public String loyaltyCard = "";
    /** Exact article EAN retained on a sold line. */
    @jakarta.ws.rs.FormParam("articleCode") @jakarta.ws.rs.DefaultValue("") public String articleCode = "";
    /** Sale date, dd/MM/yyyy. */
    @jakarta.ws.rs.FormParam("date") @jakarta.ws.rs.DefaultValue("") public String date = "";
    /** Exact tax-included total, accepting a comma or dot. */
    @jakarta.ws.rs.FormParam("amount") @jakarta.ws.rs.DefaultValue("") public String amount = "";
    /** Exact checkout identifier. */
    @jakarta.ws.rs.FormParam("terminalId") @jakarta.ws.rs.DefaultValue("") public String terminalId = "";
    /** Payment discriminator from the shared payment registry. */
    @jakarta.ws.rs.FormParam("paymentMethod") @jakarta.ws.rs.DefaultValue("") public String paymentMethod = "";
    /** Bundle key explaining invalid input, never a silently ignored criterion. */
    public String errorKey;
    /** Distinguishes a submitted advanced search from the initial empty screen. */
    public boolean submitted;
}
