package com.intermarche.pos.ui.endorsement;

import io.quarkus.qute.Template;
import io.quarkus.qute.TemplateInstance;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Only a current approved form reaches the escaped continuation template (LC-01-05-01). */
class FunctionApprovalResourceTest {
    /** A missing grant redirects; a valid grant carries its exact repeated form values. */
    @Test
    void continuationOrHome() {
        var resource=new FunctionApprovalResource();resource.approvals=mock(FunctionApprovalService.class);
        resource.page=mock(Template.class);var page=mock(TemplateInstance.class);
        when(resource.page.data(anyString(),any())).thenReturn(page);when(page.data(anyString(),any())).thenReturn(page);
        assertEquals("/",((Response)resource.show("bad")).getLocation().toString());
        var gesture=new FunctionApprovalService.Gesture("POST","/sale-tax-id","taxId=123","cart","token");
        when(resource.approvals.continuation("token")).thenReturn(gesture);
        when(resource.approvals.fields(gesture.body())).thenReturn(java.util.Map.of("taxId",java.util.List.of("123")));
        assertSame(page,resource.show("token"));verify(resource.page).data("target","/sale-tax-id");
    }
}
