package com.intermarche.pos.ui.ticket;

import com.intermarche.pos.domain.catalog.Price;
import com.intermarche.pos.domain.catalog.Product;
import io.quarkus.qute.Location;
import io.quarkus.qute.Template;
import io.quarkus.qute.TemplateInstance;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;

import java.math.BigDecimal;
import java.math.RoundingMode;
import org.jboss.logging.Logger;

/**
 * INFO ARTICLE — a READ-ONLY article lookup (EXP-93). A code is scanned or keyed
 * and the register shows the article's label, code, price and VAT, and nothing
 * else: there is NO path from this screen that adds a line to the ticket.
 *
 * <p>It is reachable CAISSE FERMÉE without a user code — it is on the
 * {@code LockCheckFilter} allowlist and needs neither an open session nor a
 * logged-in operator, because looking up what an article costs is not a sale.
 * It is not drawer-guarded either (no {@code @DrawerMustBeClosed}): a pure
 * consultation never touches the drawer.
 */
@Path("/")
public class ArticleInfoResource {

    /** Technical log of this class. */
    private static final Logger LOGGER = Logger.getLogger(ArticleInfoResource.class);

    /** The article-info screen. */
    @Inject @Location("article-info") Template articleInfo;

    /**
     * Shows the article-info screen: a lookup form, and — when a code is given —
     * the found article read-only, or a not-found message.
     *
     * @param code the code to look up (EAN, PLU or internal code), or null/blank
     *        to show just the form
     * @return the article-info page
     */
    @GET
    @Path("/article-info")
    @Produces(MediaType.TEXT_HTML)
    public TemplateInstance infoPage(@QueryParam("code") String code) {
        LOGGER.info("Entering method infoPage with code: " + code);
        ArticleInfo info = (code == null || code.isBlank()) ? null : lookup(code.trim());
        boolean notFound = code != null && !code.isBlank() && info == null;
        LOGGER.info("Exiting method infoPage");
        return articleInfo
                .data("code", code == null ? "" : code)
                .data("info", info)
                .data("notFound", notFound);
    }

    /**
     * Looks an article up by EAN, then PLU, then internal code — read only.
     *
     * @param code the code keyed or scanned
     * @return the article info, or null when no active article carries the code
     */
    private ArticleInfo lookup(String code) {
        Product product = Product.findActiveByEan(code);
        if (product == null) {
            product = Product.findActiveByPlu(code);
        }
        if (product == null) {
            return null;
        }
        Price price = Price.findCurrentPrice(product.id);
        return new ArticleInfo(product, price);
    }

    /**
     * The read-only view of an article: label, code, price and VAT.
     */
    public static class ArticleInfo {
        /** The article's sale label. */
        public final String label;
        /** The article's EAN, or a dash when it has none. */
        public final String ean;
        /** The article's PLU, or a dash when it has none. */
        public final String plu;
        /** The article's brand, or a dash. */
        public final String brand;
        /** The price including tax, formatted with a French comma, or a dash when unpriced. */
        public final String priceFormatted;
        /** The VAT rate as a percentage, formatted, or a dash when unpriced. */
        public final String vatFormatted;

        /**
         * Builds the view from a product and its current price.
         *
         * @param product the found product
         * @param price the current price, or null when the article is unpriced
         */
        ArticleInfo(Product product, Price price) {
            this.label = product.saleLabel();
            this.ean = product.ean == null || product.ean.isBlank() ? "—" : product.ean;
            this.plu = product.plu == null || product.plu.isBlank() ? "—" : product.plu;
            this.brand = product.brand == null || product.brand.isBlank() ? "—" : product.brand;
            if (price == null || price.priceIncludingTax == null) {
                this.priceFormatted = "—";
            } else {
                this.priceFormatted = String.format("%.2f",
                        price.priceIncludingTax.setScale(2, RoundingMode.HALF_UP)).replace('.', ',');
            }
            BigDecimal vat = price == null ? null : price.vatRate();
            if (vat == null) {
                this.vatFormatted = "—";
            } else {
                this.vatFormatted = vat.multiply(BigDecimal.valueOf(100))
                        .stripTrailingZeros().toPlainString() + " %";
            }
        }

        /** @return the article's sale label */
        public String getLabel() { return label; }

        /** @return the article's EAN or a dash */
        public String getEan() { return ean; }

        /** @return the article's PLU or a dash */
        public String getPlu() { return plu; }

        /** @return the article's brand or a dash */
        public String getBrand() { return brand; }

        /** @return the price including tax, formatted, or a dash */
        public String getPriceFormatted() { return priceFormatted; }

        /** @return the VAT rate as a formatted percentage, or a dash */
        public String getVatFormatted() { return vatFormatted; }
    }
}
