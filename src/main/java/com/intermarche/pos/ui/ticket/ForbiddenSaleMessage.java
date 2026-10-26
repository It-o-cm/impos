package com.intermarche.pos.ui.ticket;

import com.intermarche.pos.domain.catalog.Product;
import com.intermarche.pos.domain.catalog.attribute.ProductAttributes;
import com.intermarche.pos.service.PosSettingsService;
import com.intermarche.pos.ui.Messages;

/**
 * The sentence a register shows when it refuses an article for a FORBIDDEN SALE
 * or a RECALL (BO-10-07-20), shared by every road an article is entered by — the
 * scanned EAN, the in-store weighed label, the GS1 code, the keyed EAN or PLU.
 *
 * <p>Three sources, the most precise first: the article's own message
 * ({@code FORBIDDEN_SALE_MESSAGE}, the recall of this reference with its
 * reason), the shop's default message ({@code sale.forbidden-message}, what a
 * withdrawal posted in a hurry with no message typed still shows), and the
 * register's own sentence. A register assembled without its parameters —
 * a unit test, a handler built by hand — keeps the register's sentence.
 *
 * <p>Non-instantiable.
 */
public final class ForbiddenSaleMessage {

    /**
     * Non-instantiable utility.
     */
    private ForbiddenSaleMessage() {
    }

    /** The register's own sentence for an article forbidden to sale. */
    static final String FORBIDDEN_KEY = "op.produitInterditVente";

    /** The register's own sentence for an article under recall. */
    static final String RECALL_KEY = "op.articleRetraitRappel";

    /**
     * Returns the refusal sentence of an article forbidden to sale.
     *
     * @param i18n the operator messages
     * @param settings the back-office parameters, or null
     * @param product the refused article
     * @return the sentence to show the cashier
     */
    public static String forbidden(Messages i18n, PosSettingsService settings, Product product) {
        return of(i18n, settings, product, FORBIDDEN_KEY);
    }

    /**
     * Returns the refusal sentence of an article under recall.
     *
     * @param i18n the operator messages
     * @param settings the back-office parameters, or null
     * @param product the refused article
     * @return the sentence to show the cashier
     */
    public static String recall(Messages i18n, PosSettingsService settings, Product product) {
        return of(i18n, settings, product, RECALL_KEY);
    }

    /**
     * Returns the refusal sentence of an article.
     *
     * @param i18n the operator messages, for the register's own sentence
     * @param settings the back-office parameters, or null
     * @param product the refused article
     * @param key the bundle key of the register's own sentence
     * @return the sentence to show the cashier
     */
    static String of(Messages i18n, PosSettingsService settings, Product product, String key) {
        String administered = ProductAttributes.forbiddenSaleMessage(product,
                settings == null ? null : settings.forbiddenSaleMessage());
        return administered != null ? administered : i18n.get(key);
    }
}
