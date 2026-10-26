package com.intermarche.pos.ui.home;

import com.intermarche.pos.ui.PosState;
import io.quarkus.qute.Template;
import io.quarkus.qute.TemplateInstance;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** LC-02-03-11: display and acknowledgement do not submit an article. */
class ArticleMessageResourceTest {
    /** The page receives the current message and the POST clears it without replay. */
    @Test
    void rendersAndAcknowledgesWithoutAddingAnArticle() {
        HomeResource resource = new HomeResource();
        resource.state = new PosState();
        resource.state.blockingArticleMessage = "Stop";
        resource.state.blockingArticleMessageId = "current";
        resource.articleMessage = mock(Template.class);
        TemplateInstance page = mock(TemplateInstance.class);
        when(resource.articleMessage.data("state", resource.state)).thenReturn(page);
        assertSame(page, resource.articleMessagePage());
        assertEquals(303, resource.acknowledgeArticleMessage("current").getStatus());
        assertNull(resource.state.blockingArticleMessage);
        assertTrue(resource.state.ticket.items.isEmpty());
    }
}
