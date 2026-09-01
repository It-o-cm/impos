package com.intermarche.pos.service;

import com.intermarche.pos.domain.EchelonLevel;
import com.intermarche.pos.domain.EchelonSetting;
import com.intermarche.pos.domain.Enseigne;
import com.intermarche.pos.domain.Pdv;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The ECHELON INHERITANCE engine (BO-02-05-04, BO-03-12-07): given a point de
 * vente, it resolves the back-office parameters it inherits by walking the
 * organisation tree from the bottom up — the PDV's own value, then its
 * enseigne's default, then its country's — the more specific echelon winning.
 * <p>
 * It knows nothing of a node's own local overrides: those live in
 * {@link com.intermarche.pos.domain.PosSetting} and are layered on top by
 * {@link PosSettingsService}, which is exactly why a local override survives a
 * fresh resolution of the echelon defaults (they never share a row). This
 * service is the "central defaults" half; {@code PosSettingsService} is the
 * "node override wins" half.
 * <p>
 * Lives in {@code pos.service} because it serves another service
 * ({@link PosSettingsService}) and a back-office screen, not a single UI area.
 */
@ApplicationScoped
public class EchelonSettingService {

    /**
     * Resolves every inherited parameter of a point de vente into a single
     * key-to-value map, the more specific echelon overriding the less specific
     * (COUNTRY overlaid by ENSEIGNE overlaid by PDV). Node-local overrides are
     * NOT included — that layer is {@link PosSettingsService}'s.
     *
     * @param pdvNumber the point-of-vente number, or null on a node with none
     * @return the inherited values, empty when the PDV is unknown or unattached
     */
    public Map<String, String> resolveForPdv(String pdvNumber) {
        Map<String, String> merged = new HashMap<>();
        if (pdvNumber == null) {
            return merged;
        }
        Pdv pdv = Pdv.findByNumber(pdvNumber);
        if (pdv == null) {
            return merged;
        }
        String enseigneCode = pdv.enseigneCode;
        String countryCode = null;
        if (enseigneCode != null) {
            Enseigne enseigne = Enseigne.findByCode(enseigneCode);
            if (enseigne != null) {
                countryCode = enseigne.countryCode;
            }
        }
        if (countryCode != null) {
            overlay(merged, EchelonLevel.COUNTRY, countryCode);
        }
        if (enseigneCode != null) {
            overlay(merged, EchelonLevel.ENSEIGNE, enseigneCode);
        }
        overlay(merged, EchelonLevel.PDV, pdvNumber);
        return merged;
    }

    /**
     * Resolves one inherited key for a point de vente.
     *
     * @param pdvNumber the point-of-vente number, or null
     * @param key the catalog key
     * @return the inherited value, empty when no echelon posed it
     */
    public Optional<String> resolve(String pdvNumber, String key) {
        return Optional.ofNullable(resolveForPdv(pdvNumber).get(key));
    }

    /**
     * Overlays the rows posed at one echelon onto the accumulator, each key
     * overwriting whatever a less specific echelon left there.
     *
     * @param merged the accumulator being built bottom-agnostic then overlaid
     * @param level the echelon level to overlay
     * @param code the echelon code at that level
     */
    private void overlay(Map<String, String> merged, EchelonLevel level, String code) {
        for (EchelonSetting row : EchelonSetting.listForEchelon(level, code)) {
            merged.put(row.settingKey, row.settingValue);
        }
    }

    /**
     * Poses (upserts) a value for a key at an echelon — the admin save path.
     *
     * @param level the echelon level
     * @param echelonCode the echelon code at that level
     * @param key the catalog key
     * @param value the value to store
     */
    @Transactional
    public void set(EchelonLevel level, String echelonCode, String key, String value) {
        EchelonSetting row = EchelonSetting.findValue(level, echelonCode, key);
        if (row == null) {
            row = new EchelonSetting();
            row.level = level;
            row.echelonCode = echelonCode;
            row.settingKey = key;
            row.settingValue = value;
            row.persist();
        } else {
            row.settingValue = value;
        }
    }

    /**
     * Removes a value posed at an echelon, so it reverts to inheriting the
     * level above — the admin "clear override" path.
     *
     * @param level the echelon level
     * @param echelonCode the echelon code at that level
     * @param key the catalog key
     * @return true when a row existed and was removed, false when there was none
     */
    @Transactional
    public boolean clear(EchelonLevel level, String echelonCode, String key) {
        EchelonSetting row = EchelonSetting.findValue(level, echelonCode, key);
        if (row == null) {
            return false;
        }
        row.delete();
        return true;
    }

    /**
     * Lists the PDVs of an enseigne that carry their OWN value for a key — the
     * central view of which points de vente personalised a parameter away from
     * the enseigne default (BO-03-12-07).
     *
     * @param enseigneCode the enseigne code
     * @param key the catalog key
     * @return the personalised PDVs, ordered by number, never null
     */
    public List<Pdv> personalizedPdvs(String enseigneCode, String key) {
        List<Pdv> pdvs = Pdv.listByEnseigne(enseigneCode);
        if (pdvs.isEmpty()) {
            return List.of();
        }
        Set<String> overridden = new HashSet<>();
        for (EchelonSetting row : EchelonSetting.listForKeyAtLevel(EchelonLevel.PDV, key)) {
            overridden.add(row.echelonCode);
        }
        List<Pdv> personalised = new ArrayList<>();
        for (Pdv pdv : pdvs) {
            if (overridden.contains(pdv.pdvNumber)) {
                personalised.add(pdv);
            }
        }
        return personalised;
    }
}
