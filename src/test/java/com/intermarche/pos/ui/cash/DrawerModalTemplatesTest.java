package com.intermarche.pos.ui.cash;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * The drawer screens of the TIROIR menu are shown in a modal over the sale: each
 * page asks for its modal size, wears the register's modal look, keeps its own
 * pages and posts in the frame, and the landing pages a guard may divert it to
 * break out of the frame instead of being drawn inside it.
 *
 * <p>Read from {@code src/main/resources/templates}, the shipped sources.
 */
class DrawerModalTemplatesTest {

    /** Where the templates live, relative to the module root. */
    private static final Path TEMPLATES = Path.of("src", "main", "resources", "templates");

    /** Each drawer page and the paths its own gestures post or link to. */
    private static final Map<String, List<String>> PAGES = Map.of(
            "withdrawal", List.of("/withdrawal", "/action/withdrawal/record", "/action/endorse-validate",
                    "/action/endorse-self", "/action/endorse-cancel"),
            "apport", List.of("/apport", "/action/apport/record", "/action/endorse-validate",
                    "/action/endorse-self", "/action/endorse-cancel"),
            "cash-movement", List.of("/cash-movement", "/action/cash-movement/record",
                    "/action/endorse-validate", "/action/endorse-self", "/action/endorse-cancel"),
            "transfer", List.of("/transfer", "/action/transfer/record", "/action/endorse-validate",
                    "/action/endorse-self", "/action/endorse-cancel"),
            "financial-movement", List.of("/financial-movement", "/action/financial-movement/record",
                    "/action/endorse-validate", "/action/endorse-self", "/action/endorse-cancel"),
            "change-order", List.of("/change-order", "/action/change-order/entry",
                    "/action/change-order/print", "/action/change-order/print-empty",
                    "/action/change-order/clear"),
            "cash-changer", List.of("/cash-changer"),
            "drawer-open", List.of("/drawer-open"));

    /**
     * Reads a shipped template.
     *
     * @param name the template name, without its extension
     * @return the source
     * @throws IOException if the template cannot be read
     */
    private static String source(String name) throws IOException {
        return Files.readString(TEMPLATES.resolve(name + ".html"));
    }

    /**
     * Reads the paths a page keeps in its frame, from its body's
     * {@code data-embed-stay} attribute.
     *
     * @param source the page source
     * @return the paths, empty when the page declares none
     */
    private static List<String> stayPaths(String source) {
        Matcher stay = Pattern.compile("data-embed-stay=\"([^\"]*)\"").matcher(source);
        return stay.find() ? Arrays.asList(stay.group(1).trim().split("\\s+")) : List.of();
    }

    /**
     * True when the path stays in the frame under the page's stay list, by the
     * same rule as the screen-embed include: the path itself or anything below.
     *
     * @param stay the page's stay paths
     * @param path the path to test
     * @return true when the path stays in the frame
     */
    private static boolean staysHere(List<String> stay, String path) {
        return stay.stream().anyMatch(own -> path.equals(own) || path.startsWith(own + "/"));
    }

    /**
     * Every drawer page asks for its modal size, includes the register's modal
     * look and the embedding include, and puts RETOUR at the bottom right.
     *
     * @throws IOException if a template cannot be read
     */
    @Test
    void everyDrawerPageIsAModal() throws IOException {
        for (String page : PAGES.keySet()) {
            String source = source(page);
            assertTrue(source.matches("(?s).*data-modal-size=\"[^\"]*\\d+rem (\\d+rem|fit)[^\"]*\".*"), page + " modal size");
            assertTrue(source.contains("{#include modal-kit /}"), page + " modal look");
            assertTrue(source.contains("{#include screen-embed /}"), page + " embedding");
            assertTrue(source.contains("btn btn-muted mk-back"), page + " RETOUR bottom right");
        }
    }

    /**
     * Every path a drawer page's own gestures post or link to stays in the
     * frame, so the outcome is stated in the modal; the endorsement panel's
     * posts and its cancel stay there too.
     *
     * @throws IOException if a template cannot be read
     */
    @Test
    void everyOwnGestureStaysInTheModal() throws IOException {
        for (Map.Entry<String, List<String>> page : PAGES.entrySet()) {
            List<String> stay = stayPaths(source(page.getKey()));
            for (String path : page.getValue()) {
                assertTrue(staysHere(stay, path), page.getKey() + " keeps " + path);
            }
        }
    }

    /**
     * Every form a drawer page holds posts to a path it keeps in its frame.
     *
     * @throws IOException if a template cannot be read
     */
    @Test
    void everyFormOfADrawerPagePostsInTheModal() throws IOException {
        for (String page : PAGES.keySet()) {
            String source = source(page);
            List<String> stay = stayPaths(source);
            Matcher action = Pattern.compile("<form[^>]*action=\"([^\"]+)\"").matcher(source);
            List<String> leaving = new ArrayList<>();
            while (action.find()) {
                if (!staysHere(stay, action.group(1))) {
                    leaving.add(action.group(1));
                }
            }
            assertTrue(leaving.isEmpty(), page + " posts out of the modal: " + leaving);
        }
    }

    /**
     * A forced drawer opening leaves the modal: its reasons are carried out by
     * the whole register page, which lands on the sale.
     *
     * @throws IOException if a template cannot be read
     */
    @Test
    void aDrawerOpeningReasonLeavesTheModal() throws IOException {
        List<String> stay = stayPaths(source("drawer-open"));
        assertFalse(staysHere(stay, "/action/drawer-open"), "the reason runs in the whole page");
    }

    /**
     * The pages a guard or a landing redirect may divert a framed screen to
     * take the whole register page instead of being drawn inside the modal.
     *
     * @throws IOException if a template cannot be read
     */
    @Test
    void theLandingPagesBreakOutOfTheModal() throws IOException {
        for (String page : List.of("drawer-error", "session", "lock")) {
            assertTrue(source(page).contains(
                    "if (window.top !== window.self) { window.top.location.href = window.location.href; }"),
                    page + " breaks out of the frame");
        }
    }
}
