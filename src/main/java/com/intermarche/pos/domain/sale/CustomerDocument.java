package com.intermarche.pos.domain.sale;

import io.quarkus.hibernate.orm.panache.PanacheEntity;
import jakarta.persistence.*;
import java.time.LocalDateTime;

/** LC-08-02-06: an issued customer copy, never a store or technical document. */
@Entity
@Table(name = "customer_document")
public class CustomerDocument extends PanacheEntity {
    /** The closed allowlist of customer-facing document families. */
    public enum Kind {
        RETURN("customerdocs.kindRETURN"),
        CARD("customerdocs.kindCARD"),
        VOUCHER("customerdocs.kindVOUCHER"),
        CREDIT("customerdocs.kindCREDIT"),
        DEPOSIT("customerdocs.kindDEPOSIT"),
        LOYALTY("customerdocs.kindLOYALTY"),
        EXIT("customerdocs.kindEXIT"),
        RESUME("customerdocs.kindRESUME"),
        COLLECTION("customerdocs.kindCOLLECTION"),
        EXCHANGE("customerdocs.kindEXCHANGE"),
        PARKED("customerdocs.kindPARKED"),
        PAYMENT_CANCELLATION("customerdocs.kindPAYMENT_CANCELLATION"),
        INVOICE("customerdocs.kindINVOICE");

        /** Bundle key of the customer-facing family name. */
        public final String labelKey;

        /** Associates a family with its explicit translatable label.
         * @param labelKey existing operator bundle key
         */
        Kind(String labelKey) {
            this.labelKey = labelKey;
        }
    }

    /** Customer-only family, assigned at the issuing call site. */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    public Kind kind;
    /** The issued document's identifying reference. */
    @Column(nullable = false)
    public String reference;
    /** Exact customer copy, frozen independently of subsequent template changes. */
    @Lob
    @Column(nullable = false)
    public String content;
    /** Local issue time, for selecting the right copy at the till. */
    @Column(nullable = false)
    public LocalDateTime issuedAt;
    /** Last destination confirmed for this document. */
    public String customerEmail;
}
