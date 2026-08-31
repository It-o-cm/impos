package com.intermarche.pos.ui.journal;

import com.intermarche.pos.domain.ticket.CardPayment;
import com.intermarche.pos.domain.ticket.CashPayment;
import com.intermarche.pos.domain.ticket.ChequePayment;
import com.intermarche.pos.domain.ticket.FidelityPayment;
import com.intermarche.pos.domain.ticket.TicketRestoPayment;
import com.intermarche.pos.domain.ticket.VoucherPayment;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Maps the payment method keys the journal form offers (BO-04-01-06) to the
 * concrete {@code TicketPayment} subclasses they select. The keys are the JPA
 * discriminator values of the single-table hierarchy — the same strings the
 * session reports and the sync payloads use — so the journal filter and the
 * rest of the system name the methods identically.
 */
public final class PaymentTypes {

    /** The immutable key → entity-class registry, in display order. */
    private static final Map<String, Class<?>> BY_KEY = new LinkedHashMap<>();

    static {
        BY_KEY.put("CASH", CashPayment.class);
        BY_KEY.put("CARD", CardPayment.class);
        BY_KEY.put("CHEQUE", ChequePayment.class);
        BY_KEY.put("VOUCHER", VoucherPayment.class);
        BY_KEY.put("FIDELITY", FidelityPayment.class);
        BY_KEY.put("TR", TicketRestoPayment.class);
    }

    /**
     * Not instantiable.
     */
    private PaymentTypes() {
    }

    /**
     * Resolves a method key to its entity class.
     *
     * @param key the discriminator key, possibly unknown or null
     * @return the entity class, or null when the key is unknown
     */
    public static Class<?> forKey(String key) {
        return BY_KEY.get(key);
    }

    /**
     * Returns the known method keys in display order.
     *
     * @return the ordered key set
     */
    public static java.util.Set<String> keys() {
        return BY_KEY.keySet();
    }
}
