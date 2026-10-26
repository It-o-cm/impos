package com.intermarche.pos.ui.ticket;

import com.intermarche.pos.domain.catalog.Product;
import com.intermarche.pos.domain.catalog.attribute.ProductAttributeCatalog;
import com.intermarche.pos.domain.catalog.attribute.ProductAttributes;
import com.intermarche.pos.ui.PosState;

/** LC-02-03-11: refuses an article before registration and never replays it on acknowledgement. */
public final class ArticleMessageService {
    /** Static article admission policy. */
    private ArticleMessageService() {}

    /**
     * Keeps the first blocking message until acknowledged; blank messages cannot block a sale.
     * Island suppression applies only to informative popups, never to a registration refusal.
     * @param state current register
     * @param product resolved article
     * @return true when this article must not be recorded
     */
    public static boolean refuse(PosState state, Product product) {
        if (state.blockingArticleMessage != null) return true;
        if (!ProductAttributes.flag(product, ProductAttributeCatalog.INFO_MESSAGE_BLOCKING)) return false;
        if (product.infoMessage == null || product.infoMessage.isBlank()) return false;
        state.blockingArticleMessage = product.infoMessage.trim();
        state.blockingArticleMessageId = java.util.UUID.randomUUID().toString();
        state.entryPrompt.clear();
        state.ageCheck.clear();
        state.ticket.setError(state.blockingArticleMessage);
        state.touch();
        return true;
    }

    /**
     * Clears only the message the operator actually saw; no deferred article is admitted.
     * @param state current register
     * @param messageId identifier submitted by the displayed form
     */
    public static void acknowledge(PosState state, String messageId) {
        if (state.blockingArticleMessageId != null && state.blockingArticleMessageId.equals(messageId)) {
            state.blockingArticleMessage = null;
            state.blockingArticleMessageId = null;
            state.ticket.setError(null);
            state.touch();
        }
    }
}
