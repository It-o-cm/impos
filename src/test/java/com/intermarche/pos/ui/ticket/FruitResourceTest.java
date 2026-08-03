package com.intermarche.pos.ui.ticket;

import com.intermarche.pos.domain.Product;
import com.intermarche.pos.ui.PosState;
import io.quarkus.qute.Template;
import io.quarkus.qute.TemplateInstance;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link FruitResource}.
 * <p>
 * The resource is a thin JAX-RS facade over {@link PosState}, the
 * {@link FruitService} and two Qute {@link Template}s ({@code fruits} and
 * {@code lock}). Every collaborator is a Mockito mock: the templates echo a
 * {@link TemplateInstance} so the returned view can be identified, and
 * {@code PosState} exposes its {@code isLocked()} decision. Tests assert
 * absolute expected values and cover both arms of the single lock guard.
 */
class FruitResourceTest {

    /**
     * Builds a {@link FruitResource} whose collaborators are fresh mocks wired
     * onto its package-private fields.
     *
     * @return a resource with fully mocked state, service and templates
     */
    private FruitResource newResource() {
        FruitResource resource = new FruitResource();
        resource.state = mock(PosState.class);
        resource.fruitService = mock(FruitService.class);
        resource.fruits = mock(Template.class);
        resource.lock = mock(Template.class);
        return resource;
    }

    /**
     * {@code fruitsPage()} renders the lock page seeded with the state and never
     * queries the PLU catalog when the terminal is locked (guard true arm).
     */
    @Test
    void fruitsPageRendersLockWhenLocked() {
        FruitResource resource = newResource();
        when(resource.state.isLocked()).thenReturn(true);
        TemplateInstance lockView = mock(TemplateInstance.class);
        when(resource.lock.data("state", resource.state)).thenReturn(lockView);
        assertSame(lockView, resource.fruitsPage());
        verifyNoInteractions(resource.fruitService);
        verifyNoInteractions(resource.fruits);
    }

    /**
     * {@code fruitsPage()} renders the fruits grid seeded with the state and the
     * PLU products when the terminal is unlocked (guard false arm).
     */
    @Test
    void fruitsPageRendersFruitsWhenUnlocked() {
        FruitResource resource = newResource();
        when(resource.state.isLocked()).thenReturn(false);
        List<Product> products = List.of(mock(Product.class), mock(Product.class));
        when(resource.fruitService.getPluProducts()).thenReturn(products);
        TemplateInstance withState = mock(TemplateInstance.class);
        TemplateInstance withProducts = mock(TemplateInstance.class);
        when(resource.fruits.data("state", resource.state)).thenReturn(withState);
        when(withState.data("products", products)).thenReturn(withProducts);
        assertSame(withProducts, resource.fruitsPage());
        verifyNoInteractions(resource.lock);
    }
}
