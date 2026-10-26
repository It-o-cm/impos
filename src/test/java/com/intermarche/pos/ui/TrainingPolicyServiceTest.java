package com.intermarche.pos.ui;

import com.intermarche.pos.service.PosSettingsService;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Every rule-matching leg, including safe escape paths (LC-13-05-04). */
class TrainingPolicyServiceTest {

    /**
     * Uses real rule parsing with an inherited setting fixture.
     * @param rules exact administered text
     * @return policy under test
     */
    private TrainingPolicyService policy(String rules) {
        TrainingPolicyService service = new TrainingPolicyService();
        service.settings = mock(PosSettingsService.class);
        when(service.settings.trainingBlockedKeys()).thenReturn(rules);
        return service;
    }

    /** Blank settings add no restriction; blank lines are discarded and handler semicolons retained. */
    @Test
    void parsesRulesAndEmptyDefaults() {
        assertEquals(List.of(), policy("").rules());
        assertFalse(policy("").blocksPath("/pay"));
        assertEquals(List.of("path:/pay", "key:/:doOne();doTwo()"),
                policy("  path:/pay  \n \nkey:/:doOne();doTwo()\r\n").rules());
    }

    /** A configured key affects browser keys; unknown rule types never block HTTP paths. */
    @Test
    void ignoresNonPathRules() {
        assertFalse(policy("key:/:pay\ninvalid\npath:relative").blocksPath("/pay"));
    }

    /** Exact paths do not accidentally select siblings or descendants. */
    @Test
    void exactPaths() {
        TrainingPolicyService service = policy("path:/pay");
        assertTrue(service.blocksPath("/pay"));
        assertFalse(service.blocksPath("/payment"));
        assertFalse(service.blocksPath("/pay/card"));
    }

    /** Explicit subtree rules cover their root and children, with a slash boundary. */
    @Test
    void subtreePaths() {
        TrainingPolicyService service = policy("path:/action/discount/*");
        assertTrue(service.blocksPath("/action/discount"));
        assertTrue(service.blocksPath("/action/discount/ten"));
        assertFalse(service.blocksPath("/action/discounted"));
    }

    /** A broad rule cannot remove the error home or the training exit. */
    @Test
    void escapePathsRemainAvailable() {
        TrainingPolicyService service = policy("path:/*");
        assertFalse(service.blocksPath("/"));
        assertFalse(service.blocksPath("/action/training"));
    }
}
