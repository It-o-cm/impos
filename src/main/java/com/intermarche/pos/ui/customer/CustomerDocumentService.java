package com.intermarche.pos.ui.customer;

import com.intermarche.pos.domain.sale.CustomerDocument;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import java.time.LocalDateTime;
import java.util.List;

/** LC-08-02-06: freezes only explicitly identified customer copies for later delivery. */
@ApplicationScoped
public class CustomerDocumentService {
    /** Stores the exact issued copy; callers never pass merchant or technical output.
     * @param kind the customer document family
     * @param reference the issue reference
     * @param content the rendered customer copy
     */
    @Transactional
    public void archive(CustomerDocument.Kind kind, String reference, String content) {
        CustomerDocument document = new CustomerDocument();
        document.kind = kind;
        document.reference = reference;
        document.content = content;
        document.issuedAt = LocalDateTime.now();
        document.persist();
    }

    /** Lists one page of issued customer copies, newest first.
     * @param page zero-based page, clamped to zero
     * @return at most twenty customer copies
     */
    public List<CustomerDocument> list(int page) {
        return CustomerDocument.<CustomerDocument>find("order by id desc")
                .page(Math.max(0, page), 20).list();
    }
}
