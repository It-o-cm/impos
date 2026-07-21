package com.intermarche.pos.ui.ticket;

import com.intermarche.pos.domain.Price;
import com.intermarche.pos.domain.Product;
import com.intermarche.pos.service.TicketPrinterService;
import com.intermarche.pos.ui.PosState;
import com.intermarche.pos.ui.hardware.HardwareService;
import com.intermarche.pos.ui.scanner.ScanContext;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import java.util.Comparator;
import java.util.List;
import java.util.stream.StreamSupport;

@ApplicationScoped
public class TicketService {
    @Inject
    HardwareService hardwareService;
    @Inject
    Instance<ScanContext.ScanHandler> scanHandlers;
    @Inject PosState state;

    @Inject
    TicketPrinterService ticketPrinterService;

    public void processScan(String code) {
        if (state.ticket.transientError != null) state.ticket.transientError = null;
        state.selectedTicketIndex = -1;

        ScanContext ctx = new ScanContext(code, state);
        List<ScanContext.ScanHandler> handlers = StreamSupport.stream(scanHandlers.spliterator(), false)
                .sorted(Comparator.comparing(this::getPriority)).toList();
        handlers.stream().forEach(handler -> handler.handle(ctx));
    }

    public void processWeight(String weightStr) {
        if (state.isLocked()) return;
        try {
            double w = Double.parseDouble(weightStr.replace(',', '.'));
            state.ticket.setWeight(w);
        } catch (NumberFormatException e) { System.err.println("Poids invalide: " + weightStr); }
    }

    private int getPriority(ScanContext.ScanHandler handler) {
        Class<?> effectiveClass = handler.getClass();
        if (effectiveClass.getName().contains("$Proxy") || effectiveClass.getName().contains("_ClientProxy")) {
            effectiveClass = effectiveClass.getSuperclass();
        }
        Priority priority = effectiveClass.getAnnotation(Priority.class);
        return (priority != null) ? priority.value() : 100;
    }

    // --- LOGIQUE AFFICHAGE CLIENT ---

    private void displayItem(TicketState.TicketItem item) {
        if (item == null) {
            hardwareService.displayMessage("INTERMARCHE");
            return;
        }
        String label = item.label;
        double price = item.getTotalPrice();
        String msg = String.format("%s\t%.2fE", label, price);
        hardwareService.displayMessage(msg);
    }

    private void displayLastItemOrWelcome(PosState state) {
        if (state.ticket.items.isEmpty()) {
            hardwareService.displayMessage("INTERMARCHE");
        } else {
            displayItem(state.ticket.items.get(state.ticket.items.size() - 1));
        }
    }

    // --- MODIFICATIONS PRIX ---

    public void applyRemise(TicketState.TicketItem item, double amount) {
        if (item == null || amount <= 0) return;
        if (item.originalUnitPrice == 0.0 || item.originalUnitPrice == item.unitPrice) {
            item.originalUnitPrice = item.unitPrice;
        }
        double currentTotal = item.unitPrice * item.quantity;
        double newTotal = currentTotal - amount;
        if (newTotal < 0) newTotal = 0;
        if (item.quantity != 0) item.unitPrice = newTotal / item.quantity;
        else item.unitPrice = newTotal;
        item.modifierLabel = String.format("Remise -%.2f€", amount);
        displayItem(item);
    }

    public void applyDiscount(TicketState.TicketItem item, double percent) {
        if (item == null || percent <= 0 || percent > 100) return;
        if (item.originalUnitPrice == 0.0 || item.originalUnitPrice == item.unitPrice) {
            item.originalUnitPrice = item.unitPrice;
        }
        double reduction = item.unitPrice * (percent / 100.0);
        item.unitPrice -= reduction;
        item.modifierLabel = String.format("Discount -%.2f%%", percent);
        displayItem(item);
    }

    public void forcePrice(TicketState.TicketItem item, double newTotalPrice) {
        if (item == null || newTotalPrice < 0) return;
        if (item.originalUnitPrice == 0.0 || item.originalUnitPrice == item.unitPrice) {
            item.originalUnitPrice = item.unitPrice;
        }
        double oldTotalPrice = item.originalUnitPrice * item.quantity;
        if (item.quantity != 0) item.unitPrice = newTotalPrice / item.quantity;
        else item.unitPrice = newTotalPrice;
        item.modifierLabel = String.format("Prix initial: %.2f€", oldTotalPrice);
        displayItem(item);
    }

    public void recalculateTotal(PosState state) {
        double total = 0.0;
        for(TicketState.TicketItem i : state.ticket.items) {
            total += i.unitPrice * i.quantity;
        }
        state.ticket.totalAmount = total;
        state.ticket.onChange();
    }

    // --- ACTIONS STANDARDS ---

    public void addItemByEan(PosState state, String ean, double quantity) {
        if (ean == null || ean.isEmpty()) return;
        state.selectedTicketIndex = -1;
        if (state.ticket.transientError != null) state.ticket.transientError = null;
        Product p = Product.find("ean = ?1 and active = true", ean).firstResult();
        if (p != null) {
            if (p.forbiddenToSale) {
                state.ticket.setError("PRODUIT INTERDIT À LA VENTE");
                return;
            }
            Price price = Price.findCurrentPrice(p.id);
            double finalPrice = (price != null) ? price.priceIncludingTax.doubleValue() : 0.0;
            // CORRECTION : On passe null pour le PLU car c'est une vente à l'unité
            state.ticket.addItem(ean, null, p.name.toUpperCase(), finalPrice, quantity);
            displayItem(state.ticket.items.get(state.ticket.items.size() - 1));
        } else {
            state.ticket.setError("PRODUIT INTROUVABLE");
        }
    }

    public void addItemByPlu(PosState state, String pluCode) {
        state.selectedTicketIndex = -1;
        if (state.ticket.transientError != null) state.ticket.transientError = null;
        Product p = Product.findActiveByPlu(pluCode);
        if (p != null) {
            if (p.forbiddenToSale) {
                state.ticket.setError("PRODUIT INTERDIT À LA VENTE");
                return;
            }
            double weight = hardwareService.requestWeighing();
            if (weight <= 0) { state.ticket.setError("POIDS INVALIDE"); return; }
            if (!Double.isNaN(state.ticket.lastRecordedWeight) && Double.compare(weight, state.ticket.lastRecordedWeight) == 0) { state.ticket.setError("ERREUR POIDS IDENTIQUE"); return; }

            state.ticket.lastRecordedWeight = weight;
            Price price = Price.findCurrentPrice(p.id);
            double unitPrice = (price != null) ? price.priceIncludingTax.doubleValue() : 0.0;

            // CORRECTION : On passe le pluCode réel. Le champ EAN est mis à null (ou p.ean si besoin pour info, mais c'est le PLU qui compte pour l'affichage)
            state.ticket.addItem(null, pluCode, p.name.toUpperCase(), unitPrice, weight);

            displayItem(state.ticket.items.get(state.ticket.items.size() - 1));
        } else {
            state.ticket.setError("PLU INTROUVABLE");
        }
    }

    public void addUnknownItem(PosState state, String label, String priceStr) {
        state.selectedTicketIndex = -1;
        if (state.ticket.transientError != null) state.ticket.transientError = null;
        try {
            double price = Double.parseDouble(priceStr.replace(',', '.').replace(" ", ""));
            if (price >= 0 && label != null && !label.isEmpty()) {
                state.ticket.addItem(null, null, label.toUpperCase(), price, 1);
                displayItem(state.ticket.items.get(state.ticket.items.size() - 1));
            }
        } catch (Exception e) {
            state.ticket.setError("ERREUR PRIX SAISI");
        }
    }

    public void addDeposit(PosState state) {
        state.selectedTicketIndex = -1;
        state.ticket.addItem(null, null, "DECONSIGNATION", -1.00, 1);
        displayItem(state.ticket.items.get(state.ticket.items.size() - 1));
    }

    public void cancelItemById(PosState state, String uid) {
        state.ticket.removeItemById(uid);
        recalculateTotal(state);
        state.selectedTicketIndex = -1;
        displayLastItemOrWelcome(state);
    }

    public void cancelTicket(PosState state) {
        state.clearTicket();
        hardwareService.displayMessage("INTERMARCHE");
    }

    public void reprintTicket(Long ticketId) {
        if (ticketId != null) {
            ticketPrinterService.printTicket(ticketId);
        }
    }
}
