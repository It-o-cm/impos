package com.intermarche.pos.domain.ticket;

import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link VatBreakdown}, targeting 100% branch coverage.
 * <p>
 * The class accumulates tax-included totals per VAT rate in a {@link java.util.TreeMap}
 * and derives the HT/VAT split per bucket. Its only branches are the null guard on
 * the rate in {@link VatBreakdown#add} (covered by both a non-null and a null rate)
 * and the iteration of {@link VatBreakdown#getBuckets} (covered by both an empty and
 * a populated breakdown). The remaining coverage exercises the {@code merge}
 * accumulation of two lines under the same rate, the {@code stripTrailingZeros}
 * rate normalization, the HT/VAT derivation, the three totals and the percentage
 * formatting including its 0% form. The class holds no static finder or persist, so
 * no Panache mocking is required. Each test is fully isolated and asserts absolute
 * expected values.
 */
class VatBreakdownTest {

    /**
     * A non-null rate is used as-is (after stripping) as the bucket key.
     */
    @Test
    void addWithNonNullRateKeepsTheRate() {
        VatBreakdown breakdown = new VatBreakdown();
        breakdown.add(new BigDecimal("0.20"), new BigDecimal("12.00"));
        List<VatBreakdown.Bucket> buckets = breakdown.getBuckets();
        Assertions.assertEquals(1, buckets.size());
        Assertions.assertEquals(0, buckets.get(0).rate.compareTo(new BigDecimal("0.2")));
        Assertions.assertEquals(new BigDecimal("12.00"), buckets.get(0).totalIncludingTax);
    }

    /**
     * A null rate falls back to the zero rate, landing in the 0% bucket.
     */
    @Test
    void addWithNullRateFallsBackToZero() {
        VatBreakdown breakdown = new VatBreakdown();
        breakdown.add(null, new BigDecimal("5.00"));
        List<VatBreakdown.Bucket> buckets = breakdown.getBuckets();
        Assertions.assertEquals(1, buckets.size());
        Assertions.assertEquals(0, buckets.get(0).rate.compareTo(BigDecimal.ZERO));
        Assertions.assertEquals(new BigDecimal("5.00"), buckets.get(0).totalIncludingTax);
    }

    /**
     * Two lines with the same rate accumulate into a single bucket (merge branch).
     */
    @Test
    void addAccumulatesSameRateIntoOneBucket() {
        VatBreakdown breakdown = new VatBreakdown();
        breakdown.add(new BigDecimal("0.20"), new BigDecimal("6.00"));
        breakdown.add(new BigDecimal("0.20"), new BigDecimal("6.00"));
        List<VatBreakdown.Bucket> buckets = breakdown.getBuckets();
        Assertions.assertEquals(1, buckets.size());
        Assertions.assertEquals(new BigDecimal("12.00"), buckets.get(0).totalIncludingTax);
    }

    /**
     * Rates differing only by trailing zeros (0.20 vs 0.2000) land in the same bucket.
     */
    @Test
    void addNormalizesTrailingZeros() {
        VatBreakdown breakdown = new VatBreakdown();
        breakdown.add(new BigDecimal("0.20"), new BigDecimal("3.00"));
        breakdown.add(new BigDecimal("0.2000"), new BigDecimal("3.00"));
        List<VatBreakdown.Bucket> buckets = breakdown.getBuckets();
        Assertions.assertEquals(1, buckets.size());
        Assertions.assertEquals(new BigDecimal("6.00"), buckets.get(0).totalIncludingTax);
    }

    /**
     * An empty breakdown yields no buckets (the loop iterates zero times).
     */
    @Test
    void getBucketsIsEmptyWhenNothingAdded() {
        VatBreakdown breakdown = new VatBreakdown();
        Assertions.assertTrue(breakdown.getBuckets().isEmpty());
    }

    /**
     * The HT is derived as TTC / (1 + rate) rounded to the cent, and the VAT as TTC minus HT.
     */
    @Test
    void getBucketsDerivesHtAndVat() {
        VatBreakdown breakdown = new VatBreakdown();
        breakdown.add(new BigDecimal("0.20"), new BigDecimal("12.00"));
        VatBreakdown.Bucket bucket = breakdown.getBuckets().get(0);
        Assertions.assertEquals(new BigDecimal("10.00"), bucket.totalExcludingTax);
        Assertions.assertEquals(new BigDecimal("2.00"), bucket.vatAmount);
    }

    /**
     * Buckets are returned sorted by ascending rate.
     */
    @Test
    void getBucketsAreSortedByAscendingRate() {
        VatBreakdown breakdown = new VatBreakdown();
        breakdown.add(new BigDecimal("0.20"), new BigDecimal("12.00"));
        breakdown.add(new BigDecimal("0.055"), new BigDecimal("10.55"));
        List<VatBreakdown.Bucket> buckets = breakdown.getBuckets();
        Assertions.assertEquals(2, buckets.size());
        Assertions.assertEquals(0, buckets.get(0).rate.compareTo(new BigDecimal("0.055")));
        Assertions.assertEquals(0, buckets.get(1).rate.compareTo(new BigDecimal("0.2")));
    }

    /**
     * getTotalIncludingTax sums the tax-included totals of every bucket.
     */
    @Test
    void getTotalIncludingTaxSumsAllBuckets() {
        VatBreakdown breakdown = new VatBreakdown();
        breakdown.add(new BigDecimal("0.20"), new BigDecimal("12.00"));
        breakdown.add(new BigDecimal("0.055"), new BigDecimal("10.55"));
        Assertions.assertEquals(new BigDecimal("22.55"), breakdown.getTotalIncludingTax());
    }

    /**
     * getTotalIncludingTax returns zero when the breakdown is empty.
     */
    @Test
    void getTotalIncludingTaxIsZeroWhenEmpty() {
        VatBreakdown breakdown = new VatBreakdown();
        Assertions.assertEquals(0, breakdown.getTotalIncludingTax().compareTo(BigDecimal.ZERO));
    }

    /**
     * getTotalExcludingTax sums the per-bucket HT amounts.
     */
    @Test
    void getTotalExcludingTaxSumsBucketHt() {
        VatBreakdown breakdown = new VatBreakdown();
        breakdown.add(new BigDecimal("0.20"), new BigDecimal("12.00"));
        breakdown.add(new BigDecimal("0.055"), new BigDecimal("10.55"));
        Assertions.assertEquals(new BigDecimal("20.00"), breakdown.getTotalExcludingTax());
    }

    /**
     * getTotalVat sums the per-bucket VAT amounts.
     */
    @Test
    void getTotalVatSumsBucketVat() {
        VatBreakdown breakdown = new VatBreakdown();
        breakdown.add(new BigDecimal("0.20"), new BigDecimal("12.00"));
        breakdown.add(new BigDecimal("0.055"), new BigDecimal("10.55"));
        Assertions.assertEquals(new BigDecimal("2.55"), breakdown.getTotalVat());
    }

    /**
     * getRateFormatted renders the rate as a French-formatted percentage.
     */
    @Test
    void getRateFormattedRendersPercentage() {
        VatBreakdown breakdown = new VatBreakdown();
        breakdown.add(new BigDecimal("0.20"), new BigDecimal("12.00"));
        Assertions.assertEquals("20,00%", breakdown.getBuckets().get(0).getRateFormatted());
    }

    /**
     * getRateFormatted renders the zero rate as "0,00%".
     */
    @Test
    void getRateFormattedRendersZeroRate() {
        VatBreakdown breakdown = new VatBreakdown();
        breakdown.add(null, new BigDecimal("5.00"));
        Assertions.assertEquals("0,00%", breakdown.getBuckets().get(0).getRateFormatted());
    }
}
