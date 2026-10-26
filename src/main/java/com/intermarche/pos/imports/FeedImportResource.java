package com.intermarche.pos.imports;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.intermarche.pos.service.sync.EngineFeedService;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * The register's CSV front door, for a till that runs WITHOUT a store node.
 *
 * <p>A register attached to a node receives its referential by PULLING it, and
 * nothing enters it over HTTP. A standalone till has no node to pull from, and
 * it must still sell — and before that, someone must be able to sign in on it.
 * So everything it needs comes through this one door, and everything means
 * everything: a till that receives only part of its referential is a till that
 * does not work.
 *
 * <p>ONE grammar, the node's own: {@code POST /feeds/import/{code}} where the
 * code is the referential domain — the same token as
 * {@link com.intermarche.pos.service.sync.RefPayloads#DOMAINS}, as the engine
 * feed catalog, and as the node's relay. No kebab-case second spelling, no
 * table of correspondence to maintain.
 *
 * <p>A pushed code takes one of two routes:
 * <ul>
 *   <li>a code owned by a DEDICATED IMPORTER is delegated to it — stores,
 *       products, families, VAT rates, prices, employees, account customers,
 *       postal codes, nomenclature. The importer applies the file to the
 *       register's referential and answers with its own counts; the ones that
 *       name an engine feed ({@code feedCode()}) capture the verbatim copy on
 *       the way, so the valuation engine attached to this till is served
 *       exactly as it would be from a node;</li>
 *   <li>any other code of the ENGINE CATALOG is stored verbatim without being
 *       opened — {@code STORE_GROUPS}, {@code CATEGORY_STORAGES},
 *       {@code OFFERS}. The register has nothing to do with them; parsing
 *       belongs to imvaluation, which ingests the shared format itself.</li>
 * </ul>
 *
 * <p>Two feeds need more than their code and the table carries what they need,
 * rather than sending them to an address of their own: the customer population
 * is full or delta ({@code mode}), and a nomenclature must be told which scheme
 * it lands in ({@code scheme}, {@code label}, {@code enseigne}) because the
 * published lines number nodes INSIDE a scheme and never name the scheme.
 *
 * <p>The node's {@code SAFE_RECEIPTS} has no counterpart here: the safe is the
 * node's, and the register carries no such entity.
 *
 * <p>No fingerprint is recorded: a pushed feed is not a pulled one. Should a
 * node ever be wired to this till, its next pull compares fingerprints and
 * takes over, which is the intended precedence — the node is the reference
 * whenever there is one.
 *
 * <p>It is the register's ONLY authenticated surface, and it is authenticated
 * because it writes the catalogue, the prices and the staff file. HTTP Basic,
 * no form and no session: no human signs in here, sh/caisse.sh pushes. The
 * identities are the EMPLOYEES, the same accounts as the badge.
 */
@Path("/feeds/import")
public class FeedImportResource {

    /** Technical log of this class. */
    private static final Logger LOGGER = Logger.getLogger(FeedImportResource.class);

    /** Keeps the verbatim feed for the engine attached to this register. */
    @Inject EngineFeedService engineFeedService;

    /** The dedicated store importer (STORES delegation). */
    @Inject StoreCsvResource storeCsvResource;

    /** The dedicated product importer (PRODUCTS delegation). */
    @Inject ProductCsvResource productCsvResource;

    /** The dedicated family importer (FAMILIES delegation). */
    @Inject ProductFamilyCsvResource productFamilyCsvResource;

    /** The dedicated VAT importer (VAT_RATES delegation). */
    @Inject VatRateCsvResource vatRateCsvResource;

    /** The dedicated EGAlim importer (EGALIM_REGIMES delegation). */
    @Inject EgalimRegimeCsvResource egalimRegimeCsvResource;

    /** The dedicated price importer (PRICES delegation). */
    @Inject PriceCsvResource priceCsvResource;

    /** The dedicated employee importer (EMPLOYEES delegation). */
    @Inject EmployeeCsvResource employeeCsvResource;

    /** The dedicated customer importer (CUSTOMERS delegation). */
    @Inject AccountCustomerCsvResource accountCustomerCsvResource;

    /** The dedicated postal-code importer (POSTAL_CODES delegation). */
    @Inject PostalCodeCsvResource postalCodeCsvResource;

    /** The dedicated nomenclature importer (NOMENCLATURE delegation). */
    @Inject NomenclatureCsvResource nomenclatureCsvResource;

    /**
     * Takes one CSV feed, applies it when the register owns an importer for
     * that code, and keeps it for the engine otherwise.
     *
     * @param code the referential domain, as the catalog names it
     * @param mode the import mode, read by the customer feed alone
     * @param scheme the nomenclature scheme to load into
     * @param label the nomenclature scheme label, used on creation
     * @param enseigne the enseigne the nomenclature scheme classifies for
     * @param inputStream the raw CSV stream, header line included
     * @return the importer's counts when the register applied the feed, the
     *         stored version when it only kept it for the engine, 400 on an
     *         unknown code and 400 on an empty body
     */
    @POST
    @Path("/{code}")
    @RolesAllowed("ADMIN")
    @Consumes({MediaType.TEXT_PLAIN, MediaType.APPLICATION_OCTET_STREAM})
    @Produces(MediaType.APPLICATION_JSON)
    public Response importFeed(@PathParam("code") String code,
                               @QueryParam("mode") String mode,
                               @QueryParam("scheme") String scheme,
                               @QueryParam("label") String label,
                               @QueryParam("enseigne") String enseigne,
                               InputStream inputStream) {
        LOGGER.info("Entering method importFeed with code: " + code + ", mode: " + mode
                + ", scheme: " + scheme);
        String normalized = code == null ? "" : code.trim().toUpperCase(Locale.ROOT);
        Function<InputStream, Response> delegate =
                delegates(mode, scheme, label, enseigne).get(normalized);
        if (delegate != null) {
            LOGGER.info("Exiting method importFeed");
            return delegate.apply(inputStream);
        }
        if (EngineFeedService.catalogEntry(normalized).isEmpty()) {
            LOGGER.info("Exiting method importFeed: code inconnu");
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(ImporterCsvResource.errorAnswer("Code de flux inconnu: " + normalized
                            + " - codes connus: " + knownCodes(mode, scheme, label, enseigne))).build();
        }
        String content;
        try {
            content = new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            LOGGER.info("Exiting method importFeed: lecture impossible");
            return Response.serverError()
                    .entity(ImporterCsvResource.errorAnswer("Lecture du flux impossible: " + e.getMessage()))
                    .build();
        }
        if (content.isBlank()) {
            LOGGER.info("Exiting method importFeed: contenu vide");
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(ImporterCsvResource.errorAnswer("Contenu vide pour " + normalized)).build();
        }
        String version = engineFeedService.store(normalized, content);
        LOGGER.infof("Flux %s conserve pour le moteur (aucun importeur local)", normalized);
        LOGGER.info("Exiting method importFeed");
        return Response.ok(JsonNodeFactory.instance.objectNode().put("code", normalized)
                .put("version", version).put("applied", false).toString()).build();
    }

    /**
     * The codes owned by a dedicated importer, each mapped to its import
     * entry — the delegation table of the unified grammar.
     *
     * <p>Built per call: nine entries, no state worth caching against
     * injection timing. Same shape and same codes as the node's relay, minus
     * {@code SAFE_RECEIPTS}, which has no entity on a register.
     *
     * @param mode the import mode, read by the customer feed alone
     * @param scheme the nomenclature scheme to load into
     * @param label the nomenclature scheme label, used on creation
     * @param enseigne the enseigne the nomenclature scheme classifies for
     * @return the code-to-importer delegation map
     */
    private Map<String, Function<InputStream, Response>> delegates(
            String mode, String scheme, String label, String enseigne) {
        return Map.ofEntries(
                Map.entry("STORES", storeCsvResource::importStores),
                Map.entry("PRODUCTS", productCsvResource::importProducts),
                Map.entry("FAMILIES", productFamilyCsvResource::importProductFamilies),
                Map.entry("VAT_RATES", vatRateCsvResource::importVatRates),
                Map.entry("EGALIM_REGIMES", egalimRegimeCsvResource::importEgalimRegimes),
                Map.entry("PRICES", priceCsvResource::importPrices),
                Map.entry("EMPLOYEES", employeeCsvResource::importEmployees),
                Map.entry("CUSTOMERS",
                        stream -> accountCustomerCsvResource.importCustomers(stream, mode)),
                Map.entry("POSTAL_CODES", postalCodeCsvResource::importPostalCodes),
                Map.entry("NOMENCLATURE", stream -> nomenclatureCsvResource
                        .importNomenclature(scheme, label, enseigne, stream)));
    }

    /**
     * Lists every code the door accepts, for the message of a refusal: the
     * delegated ones first, then the engine-only ones the catalog adds.
     *
     * @param mode the import mode, read by the customer feed alone
     * @param scheme the nomenclature scheme to load into
     * @param label the nomenclature scheme label, used on creation
     * @param enseigne the enseigne the nomenclature scheme classifies for
     * @return the known codes, comma separated
     */
    private String knownCodes(String mode, String scheme, String label, String enseigne) {
        Map<String, Function<InputStream, Response>> table = delegates(mode, scheme, label, enseigne);
        return Stream.concat(
                        table.keySet().stream().sorted(),
                        EngineFeedService.CATALOG.stream()
                                .map(EngineFeedService.FeedDef::code)
                                .filter(c -> !table.containsKey(c)))
                .collect(Collectors.joining(", "));
    }
}
