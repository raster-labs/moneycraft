package com.raster.moneycraft.desktop.help;

import com.raster.moneycraft.desktop.client.DesktopJcsCanonicalizer;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import tools.jackson.databind.JsonNode;

/**
 * Air-gapped contextual help catalogue and Escape-first help overlay state manager
 * covering HELP-110-001 through HELP-110-026 (Doc 50 §7, Doc 57, Swing Execution Prompt §6).
 */
public final class ContextualHelpCatalog {

    private static final String CATALOGUE_RESOURCE = "/help/contextual_help_catalogue.json";

    private final Map<String, HelpEntry> byHelpId = new LinkedHashMap<>();
    private final Map<String, HelpEntry> byContextKey = new LinkedHashMap<>();
    private HelpEntry activeOverlayEntry;
    private String returnControlName;

    public ContextualHelpCatalog() {
        loadBundledCatalogue();
        registerEntry(new HelpEntry(
            "HELP-110-026",
            "en-GB",
            "Local printers, scales and barcode workflows",
            "localDevices",
            "Bind only admitted printer, serial scale and barcode profiles; spool acceptance is not physical paper completion.",
            "Reconcile any OUTCOME_UNKNOWN print attempt before requesting an audited DUPLICATE / REPRINT copy. "
                + "Activating an unframed keyboard-wedge barcode workflow suppresses character-only modal shortcuts.",
            "Native client operator"
        ));
    }

    public Map<String, HelpEntry> entriesById() {
        return Collections.unmodifiableMap(byHelpId);
    }

    public Optional<HelpEntry> findByHelpId(String helpId) {
        return Optional.ofNullable(byHelpId.get(helpId));
    }

    public Optional<HelpEntry> findByContextKey(String contextKey) {
        return Optional.ofNullable(byContextKey.get(contextKey));
    }

    public Optional<HelpEntry> activeOverlayEntry() {
        return Optional.ofNullable(activeOverlayEntry);
    }

    public Optional<String> closeButtonAccessibleLabel() {
        if (activeOverlayEntry == null) {
            return Optional.empty();
        }
        String target = returnControlName == null || returnControlName.isBlank()
            ? activeOverlayEntry.title()
            : returnControlName;
        return Optional.of("Close help and return to " + target + ".");
    }

    public HelpEntry openHelpOverlay(String helpIdOrContextKey, String invokingControlName) {
        Objects.requireNonNull(helpIdOrContextKey, "helpIdOrContextKey must not be null");
        HelpEntry entry = byHelpId.get(helpIdOrContextKey);
        if (entry == null) {
            entry = byContextKey.get(helpIdOrContextKey);
        }
        if (entry == null) {
            throw new IllegalArgumentException("Unknown contextual help entry: " + helpIdOrContextKey);
        }
        this.activeOverlayEntry = entry;
        this.returnControlName = invokingControlName;
        return entry;
    }

    /**
     * Dismisses the topmost help overlay on Escape BEFORE any underlying financial dialog or
     * editor processes the keystroke (Doc 50 §7, HELP-A).
     *
     * @return true if an open help overlay consumed the Escape keystroke
     */
    public boolean dismissTopmostHelpOnEscape() {
        if (this.activeOverlayEntry != null) {
            this.activeOverlayEntry = null;
            this.returnControlName = null;
            return true;
        }
        return false;
    }

    private void registerEntry(HelpEntry entry) {
        byHelpId.put(entry.helpId(), entry);
        byContextKey.put(entry.contextKey(), entry);
    }

    private void loadBundledCatalogue() {
        String rawJson = readCatalogueJson();
        DesktopJcsCanonicalizer canonicalizer = new DesktopJcsCanonicalizer();
        try {
            JsonNode root = canonicalizer.wireMapper().readTree(rawJson);
            JsonNode items = root.get("items");
            if (items == null || !items.isArray()) {
                throw new IllegalStateException("Contextual help catalogue missing items array.");
            }
            for (int i = 0; i < items.size(); i++) {
                JsonNode item = items.get(i);
                HelpEntry entry = new HelpEntry(
                    item.path("help_id").asString(),
                    item.path("locale").asString("en-GB"),
                    item.path("title").asString(),
                    item.path("context_key").asString(),
                    item.path("short_text").asString(),
                    item.path("instructions").asString(),
                    item.path("audience").asString("All users")
                );
                registerEntry(entry);
            }
        } catch (Exception ex) {
            throw new IllegalStateException("Failed parsing bundled contextual_help_catalogue.json", ex);
        }
    }

    private static String readCatalogueJson() {
        try (InputStream in = ContextualHelpCatalog.class.getResourceAsStream(CATALOGUE_RESOURCE)) {
            if (in != null) {
                return new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
            Path fallback = Path.of("desktop/src/main/resources/help/contextual_help_catalogue.json");
            if (Files.exists(fallback)) {
                return Files.readString(fallback, StandardCharsets.UTF_8);
            }
            throw new IllegalStateException("Missing bundled help catalogue: " + CATALOGUE_RESOURCE);
        } catch (IOException ex) {
            throw new IllegalStateException("Failed reading bundled help catalogue.", ex);
        }
    }

    public record HelpEntry(
        String helpId,
        String locale,
        String title,
        String contextKey,
        String shortText,
        String instructions,
        String audience
    ) {
        public HelpEntry {
            Objects.requireNonNull(helpId, "helpId must not be null");
            Objects.requireNonNull(locale, "locale must not be null");
            Objects.requireNonNull(title, "title must not be null");
            Objects.requireNonNull(contextKey, "contextKey must not be null");
            Objects.requireNonNull(shortText, "shortText must not be null");
            Objects.requireNonNull(instructions, "instructions must not be null");
            Objects.requireNonNull(audience, "audience must not be null");
        }
    }
}
