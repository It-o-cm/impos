package com.intermarche.pos.ui.recall;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.Optional;

/**
 * Unit tests for {@link BackOfficeDocumentClient}, targeting 100% branch
 * coverage of its {@code fetch} guards. The client is a plain seam whose only
 * collaborator is the injected {@code documentUrl} Optional set directly on the
 * package-private field; no Panache, no HTTP is exercised (the configured-store
 * path is [N] residue reached only against imedge). Each test is isolated and
 * asserts absolute expected values.
 */
class BackOfficeDocumentClientTest {

    /**
     * A null code resolves to no document (first guard true arm).
     */
    @Test
    void fetchNullCodeReturnsNull() {
        BackOfficeDocumentClient client = new BackOfficeDocumentClient();
        client.documentUrl = Optional.empty();
        Assertions.assertNull(client.fetch(null));
    }

    /**
     * A blank code resolves to no document (first guard blank arm).
     */
    @Test
    void fetchBlankCodeReturnsNull() {
        BackOfficeDocumentClient client = new BackOfficeDocumentClient();
        client.documentUrl = Optional.empty();
        Assertions.assertNull(client.fetch("   "));
    }

    /**
     * With no store URL configured the local stub knows no document, so a real
     * code still resolves to null (empty-URL arm).
     */
    @Test
    void fetchWithoutStoreUrlReturnsNull() {
        BackOfficeDocumentClient client = new BackOfficeDocumentClient();
        client.documentUrl = Optional.empty();
        Assertions.assertNull(client.fetch("CMD1"));
    }

    /**
     * A blank store URL is treated as no store (blank-URL arm): still null.
     */
    @Test
    void fetchWithBlankStoreUrlReturnsNull() {
        BackOfficeDocumentClient client = new BackOfficeDocumentClient();
        client.documentUrl = Optional.of("   ");
        Assertions.assertNull(client.fetch("CMD1"));
    }

    /**
     * With a configured store URL, this build does not reach the network and
     * answers null (the connected fetch is [N] residue against imedge).
     */
    @Test
    void fetchWithConfiguredStoreReturnsNullInThisBuild() {
        BackOfficeDocumentClient client = new BackOfficeDocumentClient();
        client.documentUrl = Optional.of("http://store");
        Assertions.assertNull(client.fetch("CMD1"));
    }
}
