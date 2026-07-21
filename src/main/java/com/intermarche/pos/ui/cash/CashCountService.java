package com.intermarche.pos.ui.cash;

import jakarta.enterprise.context.ApplicationScoped;
import java.util.ArrayList;
import java.util.List;

@ApplicationScoped
public class CashCountService {

    public List<CashItem> getBills() {
        List<CashItem> items = new ArrayList<>();
        items.add(new CashItem("b50", "Billet 50 €", 50.0));
        items.add(new CashItem("b20", "Billet 20 €", 20.0));
        items.add(new CashItem("b10", "Billet 10 €", 10.0));
        items.add(new CashItem("b5",  "Billet 5 €",  5.0));
        return items;
    }

    public List<CashItem> getCoins() {
        List<CashItem> items = new ArrayList<>();
        items.add(new CashItem("c2",   "Pièce 2 €",    2.0));
        items.add(new CashItem("c1",   "Pièce 1 €",    1.0));
        items.add(new CashItem("c050", "Pièce 0,50 €", 0.5));
        items.add(new CashItem("c020", "Pièce 0,20 €", 0.2));
        items.add(new CashItem("c010", "Pièce 0,10 €", 0.1));
        items.add(new CashItem("c005", "Pièce 0,05 €", 0.05));
        items.add(new CashItem("c002", "Pièce 0,02 €", 0.02));
        items.add(new CashItem("c001", "Pièce 0,01 €", 0.01));
        return items;
    }

    public List<CashItem> getRolls() {
        List<CashItem> items = new ArrayList<>();
        items.add(new CashItem("r2",   "Rouleau 2€ (50€)",  50.0));
        items.add(new CashItem("r1",   "Rouleau 1€ (25€)",  25.0));
        items.add(new CashItem("r050", "Rouleau 0.50€ (20€)", 20.0));
        items.add(new CashItem("r020", "Rouleau 0.20€ (8€)",  8.0));
        items.add(new CashItem("r010", "Rouleau 0.10€ (4€)",  4.0));
        items.add(new CashItem("r005", "Rouleau 0.05€ (2€)",  2.0));
        return items;
    }
}