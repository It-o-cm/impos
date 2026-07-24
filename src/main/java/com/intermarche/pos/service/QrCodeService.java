package com.intermarche.pos.service;

import io.nayuki.qrcodegen.QrCode;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * Renders QR codes as standalone SVG documents (debt sweep of phase 4: the
 * digital-receipt link was text only).
 * <p>
 * Wraps the zero-dependency Nayuki generator; requires one dependency in the
 * pom: {@code io.nayuki:qrcodegen:1.8.0}. SVG keeps the rendering crisp at
 * any size on the customer display and needs no image library.
 * <p>
 * Single consumer today: the digital-receipt QR served under the receipt's
 * own access key and shown on the customer display's thank-you screen. The
 * service is deliberately generic (text in, SVG out) so future codes — a
 * refund voucher, for instance — cost one call.
 */
@ApplicationScoped
public class QrCodeService {

    /** Quiet-zone width around the code, in modules. */
    private static final int BORDER = 3;

    /**
     * Renders the given text as a standalone SVG QR code (medium error
     * correction).
     *
     * @param text the text to encode
     * @return the SVG document
     */
    public String toSvg(String text) {
        QrCode qr = QrCode.encodeText(text, QrCode.Ecc.MEDIUM);
        int size = qr.size + BORDER * 2;
        StringBuilder path = new StringBuilder();
        for (int y = 0; y < qr.size; y++) {
            for (int x = 0; x < qr.size; x++) {
                if (qr.getModule(x, y)) {
                    path.append(String.format("M%d,%dh1v1h-1z", x + BORDER, y + BORDER));
                }
            }
        }
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                + "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 " + size + " " + size + "\""
                + " shape-rendering=\"crispEdges\">"
                + "<rect width=\"100%\" height=\"100%\" fill=\"#FFFFFF\"/>"
                + "<path d=\"" + path + "\" fill=\"#000000\"/>"
                + "</svg>";
    }
}
