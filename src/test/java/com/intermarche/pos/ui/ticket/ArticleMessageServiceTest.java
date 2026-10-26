package com.intermarche.pos.ui.ticket;

import com.intermarche.pos.domain.catalog.Product;
import com.intermarche.pos.ui.PosState;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** LC-02-03-11: informational messages remain admissible; blocking messages never replay an article. */
class ArticleMessageServiceTest {
    /** Null products, absent/false flags and blank messages cannot block. */
    @Test
    void nonBlockingAndBlankMessagesRemainAdmissible() {
        PosState state = new PosState();
        assertFalse(ArticleMessageService.refuse(state, null));
        Product product = new Product();
        product.infoMessage = "Notice";
        assertFalse(ArticleMessageService.refuse(state, product));
        product.attributes.put("INFO_MESSAGE_BLOCKING", "false");
        assertFalse(ArticleMessageService.refuse(state, product));
        product.attributes.put("INFO_MESSAGE_BLOCKING", "true");
        product.infoMessage = null;
        assertFalse(ArticleMessageService.refuse(state, product));
        product.infoMessage = "  ";
        assertFalse(ArticleMessageService.refuse(state, product));
        assertNull(state.blockingArticleMessage);
    }

    /** First refusal wins; stale acknowledgements preserve it and valid ones add no line. */
    @Test
    void refusalRequiresItsOwnAcknowledgementAndNeverReplays() {
        PosState state = new PosState();
        state.ageCheck.active = true;
        state.entryPrompt.active = true;
        Product product = new Product();
        product.infoMessage = "  Do not sell <script>  ";
        product.attributes.put("INFO_MESSAGE_BLOCKING", "true");
        assertTrue(ArticleMessageService.refuse(state, product));
        assertEquals("Do not sell <script>", state.blockingArticleMessage);
        assertFalse(state.ageCheck.active);
        assertFalse(state.entryPrompt.active);
        String id = state.blockingArticleMessageId;
        long version = state.version;
        product.infoMessage = "A different message";
        assertTrue(ArticleMessageService.refuse(state, product));
        assertEquals("Do not sell <script>", state.blockingArticleMessage);
        assertEquals(id, state.blockingArticleMessageId);
        ArticleMessageService.acknowledge(state, null);
        ArticleMessageService.acknowledge(state, "stale");
        assertEquals(version, state.version);
        ArticleMessageService.acknowledge(state, id);
        assertNull(state.blockingArticleMessage);
        assertNull(state.blockingArticleMessageId);
        assertNull(state.ticket.transientError);
        assertTrue(state.version > version);
        assertTrue(state.ticket.items.isEmpty());
        ArticleMessageService.acknowledge(state, id);
        assertTrue(state.ticket.items.isEmpty());
    }
}
