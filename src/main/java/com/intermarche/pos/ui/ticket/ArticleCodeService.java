package com.intermarche.pos.ui.ticket;

import com.intermarche.pos.domain.catalog.Product;
import com.intermarche.pos.domain.catalog.attribute.ProductAttributes;
import com.intermarche.pos.service.PosSettingsService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

/**
 * Decides, line by line, whether the ARTICLE CODE accompanies the label on the
 * register screen and the customer display (BO-10-02-34/35).
 *
 * <p>The decision belongs to the article: its fiche carries the flag
 * {@code CODE_ARTICLE_ON_SCREEN}, administered one by one or in bulk on the back
 * office. An article that was never given one takes the shop's parameter
 * ({@code display.show-ean}), which is what initialises the flag of every article
 * without a decision of its own.
 *
 * <p>RESOLVED ONCE PER LINE, at its first display, and kept on the line: the
 * screens are polled every second and must not read the catalog on each poll,
 * and a line keeps saying what it said when it was rung up. The ticket printer
 * reads the same rule through {@link ProductAttributes#codeShown}.
 */
@ApplicationScoped
public class ArticleCodeService {

    /** Technical log of this class. */
    private static final Logger LOGGER = Logger.getLogger(ArticleCodeService.class);

    /** The shop's article-code display, the default of a flagless article. */
    @Inject
    PosSettingsService posSettingsService;

    /**
     * Resolves the code display of every live line not resolved yet.
     *
     * @param ticket the ticket being shown
     */
    public void resolve(TicketState ticket) {
        LOGGER.info("Entering method resolve");
        Boolean shopDefault = null;
        for (TicketState.TicketItem item : ticket.items) {
            if (item.codeOnScreen != null) {
                continue;
            }
            if (item.ean == null || item.ean.isEmpty()) {
                item.codeOnScreen = Boolean.FALSE;
                continue;
            }
            if (shopDefault == null) {
                shopDefault = posSettingsService.showEan();
            }
            item.codeOnScreen = ProductAttributes.codeShown(Product.findByEan(item.ean), shopDefault);
        }
        LOGGER.info("Exiting method resolve");
    }
}
