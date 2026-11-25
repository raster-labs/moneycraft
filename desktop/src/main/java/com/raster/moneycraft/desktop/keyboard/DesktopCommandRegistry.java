package com.raster.moneycraft.desktop.keyboard;

import java.awt.Component;
import java.awt.event.ActionEvent;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import javax.swing.AbstractAction;
import javax.swing.ActionMap;
import javax.swing.InputMap;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JTable;
import javax.swing.KeyStroke;
import javax.swing.text.JTextComponent;

/**
 * Shared semantic command registry and Swing InputMap/ActionMap + immediate modal action-letter
 * dispatcher for MoneyCraft Desktop (KEY-001, KEY-002, DEV-003).
 */
public final class DesktopCommandRegistry {

    public static final String DESIGNATED_CAPTURE_CLIENT_PROP = "mc.designatedCaptureControl";

    private final Map<String, DesktopCommandDefinition> definitions = new LinkedHashMap<>();
    private final Map<String, NativeKeyBindingProfile> admittedProfiles = new LinkedHashMap<>();
    private final Deque<ActiveModalContext> modalStack = new ArrayDeque<>();
    private final Set<Integer> currentlyHeldKeyCodes = new HashSet<>();
    private final List<ScopedBindingTarget> boundComponents = new ArrayList<>();
    private final AtomicLong contextGeneration = new AtomicLong(1L);

    private KeyboardPreferenceState preferenceState = KeyboardPreferenceState.defaults(null);
    private boolean unframedWedgeScannerActive;
    private String inFlightMutationCommandId;

    public DesktopCommandRegistry() {
        registerDefaultDefinitions();
        NativeKeyBindingProfile defaultProfile = NativeKeyBindingProfile.defaultDesktopProfile();
        admittedProfiles.put(defaultProfile.profileId(), defaultProfile);
    }

    public void registerCommand(DesktopCommandDefinition definition) {
        Objects.requireNonNull(definition, "definition must not be null");
        definitions.put(definition.commandId(), definition);
    }

    public void registerProfile(NativeKeyBindingProfile profile) {
        Objects.requireNonNull(profile, "profile must not be null");
        admittedProfiles.put(profile.profileId(), profile);
    }

    public Optional<DesktopCommandDefinition> findCommand(String commandId) {
        return Optional.ofNullable(definitions.get(commandId));
    }

    public Map<String, DesktopCommandDefinition> commands() {
        return Collections.unmodifiableMap(definitions);
    }

    public long currentGeneration() {
        return contextGeneration.get();
    }

    public KeyboardPreferenceState preferenceState() {
        return preferenceState;
    }

    public boolean isUnframedWedgeScannerActive() {
        return unframedWedgeScannerActive;
    }

    public boolean isCharacterShortcutDispatchEffective() {
        return !unframedWedgeScannerActive && preferenceState.mode() == ShortcutMode.CHARACTER;
    }

    /**
     * Turns on or off unframed keyboard-wedge barcode workflow suppression (DEV-003, KEY-001).
     * Forces all character-only shortcuts off across open and newly opened modals without
     * overwriting the user's saved preference mode.
     */
    public synchronized void setUnframedWedgeScannerActive(boolean active) {
        if (this.unframedWedgeScannerActive != active) {
            this.unframedWedgeScannerActive = active;
            advanceGeneration();
        }
    }

    public synchronized void bindAuthenticatedOwner(String principalId) {
        Objects.requireNonNull(principalId, "principalId must not be null");
        String existingOwner = preferenceState.ownerPrincipalId();
        if (existingOwner == null) {
            this.preferenceState = new KeyboardPreferenceState(
                1,
                principalId,
                preferenceState.mode(),
                preferenceState.locale(),
                preferenceState.qualifiedProfileId(),
                preferenceState.modifierOverrides()
            );
        } else if (!existingOwner.equals(principalId)) {
            this.preferenceState = KeyboardPreferenceState.defaults(principalId);
        }
        advanceGeneration();
    }

    public synchronized void clearPreferencesOnLogout() {
        this.preferenceState = KeyboardPreferenceState.defaults(null);
        this.inFlightMutationCommandId = null;
        this.currentlyHeldKeyCodes.clear();
        this.modalStack.clear();
        advanceGeneration();
    }

    /**
     * Atomically validates and applies a user preference state, advancing contextGeneration.
     */
    public synchronized void applyPreferences(KeyboardPreferenceState nextPreference) {
        Objects.requireNonNull(nextPreference, "nextPreference must not be null");
        if (nextPreference.mode() == ShortcutMode.MODIFIER) {
            String profileId = nextPreference.qualifiedProfileId();
            if (profileId == null || !admittedProfiles.containsKey(profileId)) {
                throw new InvalidModalActionSetException(
                    "MODIFIER mode requires a qualified NativeKeyBindingProfile (" + profileId + ")."
                );
            }
            NativeKeyBindingProfile profile = admittedProfiles.get(profileId);
            Set<String> seenPerContext = new HashSet<>();
            for (Map.Entry<String, KeyStroke> entry : nextPreference.modifierOverrides().entrySet()) {
                KeyStroke chord = entry.getValue();
                if (chord == null || (chord.getModifiers() & (InputEvent.CTRL_DOWN_MASK | InputEvent.ALT_DOWN_MASK)) == 0) {
                    throw new InvalidModalActionSetException(
                        "Override chord for '" + entry.getKey() + "' must include a qualified Ctrl/Alt modifier."
                    );
                }
                String chordToken = entry.getKey().split("::")[0] + "|" + chord;
                if (!seenPerContext.add(chordToken)) {
                    throw new InvalidModalActionSetException(
                        "Duplicate modifier chord override within same context: " + chord
                    );
                }
                if (profile.systemReservedUnavailableBindings().contains(chord.toString())) {
                    throw new InvalidModalActionSetException(
                        "Chord conflicts with OS-reserved binding: " + chord
                    );
                }
            }
        }
        this.preferenceState = nextPreference;
        advanceGeneration();
        rebindAllScopedComponents();
    }

    /**
     * Connects scoped ANCESTOR InputMap/ActionMap bindings to shared semantic command handlers,
     * avoiding duplicate window-level bindings with unpredictable lookup order (KEY-002).
     */
    public synchronized void bindScopedCommands(
        JComponent container,
        Map<String, Runnable> handlers,
        Map<String, BooleanSupplier> enablementPredicates
    ) {
        Objects.requireNonNull(container, "container must not be null");
        Objects.requireNonNull(handlers, "handlers must not be null");
        Map<String, BooleanSupplier> safePredicates = enablementPredicates == null ? Map.of() : enablementPredicates;
        ScopedBindingTarget target = new ScopedBindingTarget(
            container,
            new LinkedHashMap<>(handlers),
            new LinkedHashMap<>(safePredicates)
        );
        boundComponents.removeIf(existing -> existing.container() == container);
        boundComponents.add(target);
        installScopedBindings(target);
    }

    /**
     * Re-applies scoped InputMap/ActionMap bindings cleanly after a Swing Look-and-Feel change (AT09-KEY-002-C).
     */
    public synchronized void refreshAfterLookAndFeelChange() {
        advanceGeneration();
        rebindAllScopedComponents();
    }

    /**
     * Validates and activates a topmost modal surface context, recording held physical keys so
     * opening or pre-held keys cannot immediately trigger an action in the new modal (KEY-001).
     */
    public synchronized long pushModalContext(
        String contextId,
        String locale,
        List<ModalActionSpec> actions
    ) {
        Objects.requireNonNull(contextId, "contextId must not be null");
        Objects.requireNonNull(locale, "locale must not be null");
        Objects.requireNonNull(actions, "actions must not be null");
        if (contextId.isBlank() || actions.isEmpty()) {
            throw new InvalidModalActionSetException("Modal context requires non-blank contextId and actions.");
        }

        validateCompleteActionSet(contextId, locale, actions);
        long generation = advanceGeneration();
        Set<Integer> heldAtOpen = new HashSet<>(currentlyHeldKeyCodes);
        modalStack.push(new ActiveModalContext(
            contextId,
            locale,
            generation,
            List.copyOf(actions),
            heldAtOpen
        ));
        return generation;
    }

    public synchronized void popModalContext(String contextId) {
        if (!modalStack.isEmpty() && modalStack.peek().contextId().equals(contextId)) {
            modalStack.pop();
            advanceGeneration();
            return;
        }
        modalStack.removeIf(ctx -> ctx.contextId().equals(contextId));
        advanceGeneration();
    }

    public synchronized Optional<String> activeModalContextId() {
        return modalStack.isEmpty() ? Optional.empty() : Optional.of(modalStack.peek().contextId());
    }

    public synchronized void markMutationCompleted(String commandId) {
        if (Objects.equals(this.inFlightMutationCommandId, commandId)) {
            this.inFlightMutationCommandId = null;
        }
    }

    public synchronized Optional<String> inFlightMutationCommandId() {
        return Optional.ofNullable(inFlightMutationCommandId);
    }

    /**
     * Formats visible hint suffix for a modal action button reflecting current mode and scanner state.
     */
    public synchronized String formatActionHint(ModalActionSpec spec) {
        Objects.requireNonNull(spec, "spec must not be null");
        if (isCharacterShortcutDispatchEffective()) {
            return "[" + spec.pairDisplay() + "]";
        }
        if (preferenceState.mode() == ShortcutMode.MODIFIER) {
            KeyStroke effectiveChord = resolveEffectiveModifierChord(spec);
            if (effectiveChord != null) {
                return "[" + effectiveChord + "]";
            }
        }
        return "[Tab/Enter]";
    }

    /**
     * Processes a Swing KeyEvent against the topmost modal context with full KEY-001 / KEY-002 guards.
     *
     * @return true if the event matched the topmost modal (dispatched or reserved by disabled action)
     */
    public synchronized boolean processModalKeyEvent(
        KeyEvent event,
        Component focusOwner,
        boolean imeCompositionActive,
        long eventGeneration
    ) {
        Objects.requireNonNull(event, "event must not be null");
        int keyCode = event.getKeyCode();

        if (event.getID() == KeyEvent.KEY_RELEASED) {
            currentlyHeldKeyCodes.remove(keyCode);
            for (ActiveModalContext ctx : modalStack) {
                ctx.heldAtOpenKeyCodes().remove(keyCode);
            }
            return false;
        }

        if (event.getID() != KeyEvent.KEY_PRESSED) {
            return false;
        }

        boolean alreadyHeld = !currentlyHeldKeyCodes.add(keyCode);
        if (modalStack.isEmpty()) {
            return false;
        }

        ActiveModalContext top = modalStack.peek();
        if (eventGeneration != top.generation() || eventGeneration != contextGeneration.get()) {
            return false;
        }
        if (imeCompositionActive || isInputConsumingComponent(focusOwner)) {
            return false;
        }
        if (event.isAltGraphDown()) {
            return false;
        }
        if (alreadyHeld || top.heldAtOpenKeyCodes().contains(keyCode)) {
            return true;
        }

        if (preferenceState.mode() == ShortcutMode.MODIFIER) {
            KeyStroke incoming = KeyStroke.getKeyStrokeForEvent(event);
            for (ModalActionSpec spec : top.actions()) {
                KeyStroke targetChord = resolveEffectiveModifierChord(spec);
                if (targetChord != null && targetChord.equals(incoming)) {
                    executeEligibleModalAction(spec);
                    event.consume();
                    return true;
                }
            }
            return false;
        }

        if (!isCharacterShortcutDispatchEffective()) {
            return false;
        }

        int disallowedModifiers = InputEvent.CTRL_DOWN_MASK | InputEvent.ALT_DOWN_MASK | InputEvent.META_DOWN_MASK;
        if ((event.getModifiersEx() & disallowedModifiers) != 0) {
            return false;
        }

        char ch = event.getKeyChar();
        if (ch == KeyEvent.CHAR_UNDEFINED) {
            return false;
        }

        List<ModalActionSpec> matches = new ArrayList<>();
        for (ModalActionSpec spec : top.actions()) {
            if (spec.matchesCharacter(ch)) {
                matches.add(spec);
            }
        }
        if (matches.size() > 1) {
            // Runtime ambiguity safeguard: fail closed and never pick an arbitrary action.
            event.consume();
            return true;
        }
        if (matches.size() == 1) {
            ModalActionSpec matched = matches.get(0);
            executeEligibleModalAction(matched);
            event.consume();
            return true;
        }
        // Topmost modal consumes unmapped character fall-through so background screens never fire.
        return Character.isLetterOrDigit(ch);
    }

    public static boolean isInputConsumingComponent(Component focusOwner) {
        if (focusOwner == null) {
            return false;
        }
        if (focusOwner instanceof JTextComponent) {
            return true;
        }
        if (focusOwner instanceof JTable table && table.isEditing()) {
            return true;
        }
        if (focusOwner instanceof JComboBox<?> combo && combo.isEditable()) {
            return true;
        }
        if (focusOwner instanceof JComponent jc
            && Boolean.TRUE.equals(jc.getClientProperty(DESIGNATED_CAPTURE_CLIENT_PROP))) {
            return true;
        }
        Component parent = focusOwner.getParent();
        if (parent instanceof JComboBox<?> parentCombo && parentCombo.isEditable()) {
            return true;
        }
        return parent instanceof JTable parentTable && parentTable.isEditing();
    }

    private void executeEligibleModalAction(ModalActionSpec spec) {
        if (!spec.enabledPredicate().getAsBoolean()) {
            // Disabled action reserves its pair; never falls through to another command.
            return;
        }
        DesktopCommandDefinition def = definitions.get(spec.commandId());
        boolean isMutation = def != null && def.financialMutation();
        if (isMutation) {
            if (inFlightMutationCommandId != null) {
                return;
            }
            inFlightMutationCommandId = spec.commandId();
        }
        spec.actionHandler().run();
    }

    private KeyStroke resolveEffectiveModifierChord(ModalActionSpec spec) {
        String key = KeyboardPreferenceState.overrideKey(spec.contextId(), spec.commandId());
        KeyStroke override = preferenceState.modifierOverrides().get(key);
        if (override != null) {
            return override;
        }
        if (spec.qualifiedModifierChord() != null) {
            return spec.qualifiedModifierChord();
        }
        String profileId = preferenceState.qualifiedProfileId();
        if (profileId != null && admittedProfiles.containsKey(profileId)) {
            return admittedProfiles.get(profileId).qualifiedModifierCandidates().get(spec.commandId());
        }
        return null;
    }

    private static void validateCompleteActionSet(
        String contextId,
        String locale,
        List<ModalActionSpec> actions
    ) {
        Set<Character> usedChars = new HashSet<>();
        Set<String> seenCommandIds = new HashSet<>();
        for (ModalActionSpec spec : actions) {
            if (!contextId.equals(spec.contextId()) || !locale.equals(spec.locale())) {
                throw new InvalidModalActionSetException(
                    "Action '" + spec.commandId() + "' context/locale mismatch for modal '" + contextId + "'."
                );
            }
            if (!seenCommandIds.add(spec.commandId())) {
                throw new InvalidModalActionSetException(
                    "Duplicate commandId '" + spec.commandId() + "' in modal '" + contextId + "'."
                );
            }
            if (!usedChars.add(spec.lowerLetter()) || !usedChars.add(spec.upperLetter())) {
                throw new InvalidModalActionSetException(
                    "Colliding action-letter pair '" + spec.pairDisplay() + "' in modal '" + contextId + "'."
                );
            }
        }
    }

    private void rebindAllScopedComponents() {
        for (ScopedBindingTarget target : boundComponents) {
            installScopedBindings(target);
        }
    }

    private void installScopedBindings(ScopedBindingTarget target) {
        JComponent container = target.container();
        InputMap ancestorMap = container.getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT);
        ActionMap actionMap = container.getActionMap();
        ancestorMap.clear();
        actionMap.clear();

        Set<KeyStroke> boundChords = new HashSet<>();
        for (Map.Entry<String, Runnable> entry : target.handlers().entrySet()) {
            String commandId = entry.getKey();
            DesktopCommandDefinition def = definitions.get(commandId);
            if (def == null) {
                throw new IllegalArgumentException("Unregistered semantic commandId: " + commandId);
            }
            BooleanSupplier enabledSupplier = target.enablementPredicates()
                .getOrDefault(commandId, () -> true);

            KeyStroke chord = selectScopedChord(def);
            if (chord != null && boundChords.add(chord)) {
                ancestorMap.put(chord, commandId);
            }
            actionMap.put(commandId, new ScopedCommandAction(commandId, def.financialMutation(), enabledSupplier, entry.getValue()));
        }
        for (Map.Entry<String, Runnable> entry : target.handlers().entrySet()) {
            String commandId = entry.getKey();
            DesktopCommandDefinition def = definitions.get(commandId);
            KeyStroke altChord = def != null ? def.qualifiedAlternativeBinding() : null;
            if (altChord != null && boundChords.add(altChord)) {
                ancestorMap.put(altChord, commandId);
            }
        }
    }

    private KeyStroke selectScopedChord(DesktopCommandDefinition def) {
        KeyStroke primary = def.primaryBinding();
        if (primary != null) {
            int mods = primary.getModifiers();
            int code = primary.getKeyCode();
            boolean hasModifier = (mods & (InputEvent.CTRL_DOWN_MASK | InputEvent.ALT_DOWN_MASK | InputEvent.META_DOWN_MASK)) != 0;
            boolean specialKey = (code >= KeyEvent.VK_F1 && code <= KeyEvent.VK_F12)
                || code == KeyEvent.VK_ESCAPE
                || code == KeyEvent.VK_PAGE_UP
                || code == KeyEvent.VK_PAGE_DOWN
                || code == KeyEvent.VK_SPACE
                || (code == KeyEvent.VK_ENTER && (mods & InputEvent.SHIFT_DOWN_MASK) != 0);
            if (hasModifier || specialKey) {
                return primary;
            }
        }
        return def.qualifiedAlternativeBinding();
    }

    private long advanceGeneration() {
        return contextGeneration.incrementAndGet();
    }

    private void registerDefaultDefinitions() {
        registerCommand(new DesktopCommandDefinition(
            "company.open", "Open company", "company.read", StateTokenRole.ACTION_PRIMARY,
            "mc.icon.company", false, KeyStroke.getKeyStroke(KeyEvent.VK_F3, 0),
            KeyStroke.getKeyStroke("ctrl shift O"), "Company > Open Company", List.of("macOS Mission Control F3")
        ));
        registerCommand(new DesktopCommandDefinition(
            "company.features", "Company features", "company.manage", StateTokenRole.ACTION_SECONDARY,
            null, false, KeyStroke.getKeyStroke(KeyEvent.VK_F11, 0),
            KeyStroke.getKeyStroke("ctrl shift F11"), "Company > Features", List.of("macOS Show Desktop F11")
        ));
        registerCommand(new DesktopCommandDefinition(
            "ledger.select", "Select", "master.read", StateTokenRole.ACTION_PRIMARY,
            "mc.icon.ledger", false, KeyStroke.getKeyStroke("ctrl ENTER"),
            KeyStroke.getKeyStroke("ctrl shift L"), "Masters > Select Ledger", List.of()
        ));
        registerCommand(new DesktopCommandDefinition(
            "ledger.create", "Create ledger", "master.create", StateTokenRole.ACTION_SECONDARY,
            "mc.icon.ledger", false, KeyStroke.getKeyStroke("alt C"),
            KeyStroke.getKeyStroke("ctrl shift N"), "Masters > Create Ledger", List.of()
        ));
        registerCommand(new DesktopCommandDefinition(
            "ledger.save", "Save", "master.create", StateTokenRole.ACTION_PRIMARY,
            "mc.icon.save-document", true, KeyStroke.getKeyStroke("ctrl A"),
            KeyStroke.getKeyStroke("ctrl shift S"), "Masters > Save Ledger", List.of()
        ));
        registerCommand(new DesktopCommandDefinition(
            "journal.saveDraft", "Save draft", "voucher.create", StateTokenRole.ACTION_PRIMARY,
            "mc.icon.save-document", true, KeyStroke.getKeyStroke("ctrl S"),
            KeyStroke.getKeyStroke("ctrl shift S"), "Voucher > Save Draft", List.of()
        ));
        registerCommand(new DesktopCommandDefinition(
            "journal.approve", "Approve", "voucher.approve", StateTokenRole.ACTION_PRIMARY,
            "mc.icon.approve-shield", true, KeyStroke.getKeyStroke("ctrl shift A"),
            KeyStroke.getKeyStroke("alt shift A"), "Voucher > Approve", List.of()
        ));
        registerCommand(new DesktopCommandDefinition(
            "journal.post", "Post", "voucher.post", StateTokenRole.ACTION_PRIMARY,
            "mc.icon.post-ledger", true, KeyStroke.getKeyStroke("ctrl A"),
            KeyStroke.getKeyStroke("ctrl shift P"), "Voucher > Post", List.of()
        ));
        registerCommand(new DesktopCommandDefinition(
            "context.date", "Change date", "company.read", StateTokenRole.ACTION_SECONDARY,
            "mc.icon.calendar", false, KeyStroke.getKeyStroke(KeyEvent.VK_F2, 0),
            KeyStroke.getKeyStroke("ctrl shift D"), "Context > Change Date", List.of()
        ));
        registerCommand(new DesktopCommandDefinition(
            "context.applyDate", "Apply", "company.read", StateTokenRole.ACTION_PRIMARY,
            "mc.icon.calendar", false, KeyStroke.getKeyStroke("ctrl ENTER"),
            KeyStroke.getKeyStroke("ctrl shift A"), "Context > Apply Date", List.of()
        ));
        registerCommand(new DesktopCommandDefinition(
            "report.open", "Ledger report", "report.read", StateTokenRole.ACTION_SECONDARY,
            "mc.icon.ledger", false, KeyStroke.getKeyStroke("alt G"),
            KeyStroke.getKeyStroke("ctrl shift R"), "Reports > Open Report", List.of()
        ));
        registerCommand(new DesktopCommandDefinition(
            "report.back", "Return to report", "report.read", StateTokenRole.ACTION_SECONDARY,
            "mc.icon.back", false, KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0),
            KeyStroke.getKeyStroke("ctrl shift B"), "Reports > Return", List.of()
        ));
        registerCommand(new DesktopCommandDefinition(
            "report.refresh", "Refresh report", "report.read", StateTokenRole.ACTION_SECONDARY,
            "mc.icon.refresh", false, KeyStroke.getKeyStroke(KeyEvent.VK_F5, 0),
            KeyStroke.getKeyStroke("ctrl shift H"), "Reports > Refresh", List.of()
        ));
        registerCommand(new DesktopCommandDefinition(
            "report.applyFilters", "Apply", "report.read", StateTokenRole.ACTION_PRIMARY,
            "mc.icon.filter", false, KeyStroke.getKeyStroke("ctrl ENTER"),
            KeyStroke.getKeyStroke("ctrl shift F"), "Reports > Apply Filters", List.of()
        ));
        registerCommand(new DesktopCommandDefinition(
            "report.clearFilters", "Clear", "report.read", StateTokenRole.ACTION_SECONDARY,
            "mc.icon.clear-filter", false, KeyStroke.getKeyStroke("ctrl shift L"),
            KeyStroke.getKeyStroke("alt shift L"), "Reports > Clear Filters", List.of()
        ));
        registerCommand(new DesktopCommandDefinition(
            "report.output", "Report output", "export", StateTokenRole.ACTION_SECONDARY,
            "mc.icon.print", false, KeyStroke.getKeyStroke("ctrl P"),
            KeyStroke.getKeyStroke("ctrl shift U"), "Reports > Print / Export", List.of()
        ));
        registerCommand(new DesktopCommandDefinition(
            "report.print", "Print", "export", StateTokenRole.ACTION_SECONDARY,
            "mc.icon.print", false, KeyStroke.getKeyStroke("ctrl shift P"),
            KeyStroke.getKeyStroke("alt shift P"), "Reports > Print", List.of()
        ));
        registerCommand(new DesktopCommandDefinition(
            "report.export", "Export PDF", "export", StateTokenRole.ACTION_SECONDARY,
            "mc.icon.export", false, KeyStroke.getKeyStroke("ctrl E"),
            KeyStroke.getKeyStroke("ctrl shift E"), "Reports > Export PDF", List.of()
        ));
        registerCommand(new DesktopCommandDefinition(
            "editor.close", "Close", "authenticated", StateTokenRole.ACTION_SECONDARY,
            "mc.icon.close", false, KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0),
            KeyStroke.getKeyStroke("ctrl shift C"), "View > Close", List.of()
        ));
        registerCommand(new DesktopCommandDefinition(
            "editor.discard", "Discard", "authenticated", StateTokenRole.ACTION_DANGER,
            "mc.icon.discard-draft", false, KeyStroke.getKeyStroke("ctrl shift D"),
            KeyStroke.getKeyStroke("alt shift D"), "Voucher > Discard Unsaved Edits", List.of()
        ));
        registerCommand(new DesktopCommandDefinition(
            "editor.stay", "Stay", "authenticated", StateTokenRole.ACTION_SECONDARY,
            null, false, KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0),
            KeyStroke.getKeyStroke("ctrl shift T"), "Voucher > Stay in Editor", List.of()
        ));
        registerCommand(new DesktopCommandDefinition(
            "request.check", "Check status", "authenticated", StateTokenRole.ACTION_SECONDARY,
            "mc.icon.refresh", false, KeyStroke.getKeyStroke("ctrl shift K"),
            KeyStroke.getKeyStroke("alt shift K"), "Recovery > Check Status", List.of()
        ));
        registerCommand(new DesktopCommandDefinition(
            "request.retryOriginal", "Retry original", "authenticated", StateTokenRole.ACTION_SECONDARY,
            "mc.icon.refresh", true, KeyStroke.getKeyStroke("ctrl shift R"),
            KeyStroke.getKeyStroke("alt shift R"), "Recovery > Retry Original", List.of()
        ));
        registerCommand(new DesktopCommandDefinition(
            "request.details", "Details", "authenticated", StateTokenRole.ACTION_SECONDARY,
            "mc.icon.details", false, KeyStroke.getKeyStroke("ctrl shift I"),
            KeyStroke.getKeyStroke("alt shift I"), "Recovery > Diagnostic Details", List.of()
        ));
        registerCommand(new DesktopCommandDefinition(
            "keyboard.open", "Keyboard settings", "public", StateTokenRole.ACTION_SECONDARY,
            "mc.icon.keyboard", false, KeyStroke.getKeyStroke("ctrl COMMA"),
            KeyStroke.getKeyStroke("ctrl shift K"), "Preferences > Keyboard Settings", List.of()
        ));
        registerCommand(new DesktopCommandDefinition(
            "keyboard.apply", "Apply", "public", StateTokenRole.ACTION_PRIMARY,
            null, false, KeyStroke.getKeyStroke("ctrl ENTER"),
            KeyStroke.getKeyStroke("ctrl shift A"), "Preferences > Apply Keyboard Settings", List.of()
        ));
        registerCommand(new DesktopCommandDefinition(
            "keyboard.defaults", "Reset defaults", "public", StateTokenRole.ACTION_SECONDARY,
            null, false, KeyStroke.getKeyStroke("ctrl shift D"),
            KeyStroke.getKeyStroke("alt shift D"), "Preferences > Reset Keyboard Defaults", List.of()
        ));
        registerCommand(new DesktopCommandDefinition(
            "device.captureScale", "Capture scale weight", "voucher.create", StateTokenRole.ACTION_SECONDARY,
            "mc.icon.refresh", false, KeyStroke.getKeyStroke("ctrl shift W"),
            KeyStroke.getKeyStroke("alt shift W"), "Devices > Capture Scale Reading", List.of()
        ));
        registerCommand(new DesktopCommandDefinition(
            "device.reconcilePrint", "Reconcile print status", "export", StateTokenRole.ACTION_SECONDARY,
            "mc.icon.unknown", false, KeyStroke.getKeyStroke("ctrl shift Q"),
            KeyStroke.getKeyStroke("alt shift Q"), "Devices > Reconcile Uncertain Output", List.of()
        ));
        registerCommand(new DesktopCommandDefinition(
            "device.reprintDuplicate", "Audited duplicate reprint", "export", StateTokenRole.ACTION_PRIMARY,
            "mc.icon.print", false, KeyStroke.getKeyStroke("ctrl shift M"),
            KeyStroke.getKeyStroke("alt shift M"), "Devices > Reprint Duplicate Copy", List.of()
        ));
        registerFullShortcutParityDefinitions();
    }

    private void registerFullShortcutParityDefinitions() {
        registerCommand(new DesktopCommandDefinition(
            "help.open", "Contextual help", "public", StateTokenRole.ACTION_SECONDARY,
            "mc.icon.details", false, KeyStroke.getKeyStroke(KeyEvent.VK_F1, 0),
            KeyStroke.getKeyStroke("ctrl shift F1"), "Help > Contextual Help", List.of()
        ));
        registerCommand(new DesktopCommandDefinition(
            "report.toggleDetailed", "Detailed / Condensed", "report.read", StateTokenRole.ACTION_SECONDARY,
            null, false, KeyStroke.getKeyStroke(KeyEvent.VK_F1, InputEvent.ALT_DOWN_MASK),
            KeyStroke.getKeyStroke("ctrl shift F1"), "Reports > Toggle Detailed", List.of()
        ));
        registerCommand(new DesktopCommandDefinition(
            "context.period", "Change period", "company.read", StateTokenRole.ACTION_SECONDARY,
            "mc.icon.calendar", false, KeyStroke.getKeyStroke(KeyEvent.VK_F2, InputEvent.ALT_DOWN_MASK),
            KeyStroke.getKeyStroke("ctrl shift F2"), "Context > Change Period", List.of()
        ));
        registerCommand(new DesktopCommandDefinition(
            "company.select", "Select company", "company.read", StateTokenRole.ACTION_SECONDARY,
            "mc.icon.company", false, KeyStroke.getKeyStroke(KeyEvent.VK_F3, InputEvent.ALT_DOWN_MASK),
            KeyStroke.getKeyStroke("ctrl shift F3"), "Company > Select Company", List.of()
        ));
        registerCommand(new DesktopCommandDefinition(
            "company.shut", "Shut company", "company.read", StateTokenRole.ACTION_SECONDARY,
            "mc.icon.close", false, KeyStroke.getKeyStroke(KeyEvent.VK_F3, InputEvent.CTRL_DOWN_MASK),
            KeyStroke.getKeyStroke("ctrl alt F3"), "Company > Shut Company", List.of()
        ));
        registerCommand(new DesktopCommandDefinition(
            "company.configure", "Configure", "authenticated", StateTokenRole.ACTION_SECONDARY,
            null, false, KeyStroke.getKeyStroke(KeyEvent.VK_F12, 0),
            KeyStroke.getKeyStroke("ctrl shift F12"), "Company > Configure", List.of()
        ));
        registerCommand(new DesktopCommandDefinition(
            "nav.goTo", "Go To overlay", "authenticated", StateTokenRole.ACTION_SECONDARY,
            "mc.icon.search", false, KeyStroke.getKeyStroke("alt G"),
            KeyStroke.getKeyStroke("ctrl shift G"), "Navigation > Go To", List.of()
        ));
        registerCommand(new DesktopCommandDefinition(
            "nav.switchTo", "Switch To", "authenticated", StateTokenRole.ACTION_SECONDARY,
            "mc.icon.search", false, KeyStroke.getKeyStroke("ctrl G"),
            KeyStroke.getKeyStroke("ctrl alt G"), "Navigation > Switch To", List.of()
        ));
        registerCommand(new DesktopCommandDefinition(
            "menu.company", "Company menu", "authenticated", StateTokenRole.ACTION_SECONDARY,
            "mc.icon.company", false, KeyStroke.getKeyStroke("alt K"),
            KeyStroke.getKeyStroke("ctrl shift K"), "Top Bar > Company", List.of()
        ));
        registerCommand(new DesktopCommandDefinition(
            "menu.data", "Data menu", "authenticated", StateTokenRole.ACTION_SECONDARY,
            null, false, KeyStroke.getKeyStroke("alt Y"),
            KeyStroke.getKeyStroke("ctrl shift Y"), "Top Bar > Data", List.of()
        ));
        registerCommand(new DesktopCommandDefinition(
            "menu.exchange", "Exchange menu", "authenticated", StateTokenRole.ACTION_SECONDARY,
            null, false, KeyStroke.getKeyStroke("alt Z"),
            KeyStroke.getKeyStroke("ctrl shift Z"), "Top Bar > Exchange", List.of()
        ));
        registerCommand(new DesktopCommandDefinition(
            "menu.import", "Import menu", "import.read", StateTokenRole.ACTION_SECONDARY,
            "mc.icon.import", false, KeyStroke.getKeyStroke("alt O"),
            KeyStroke.getKeyStroke("ctrl shift O"), "Top Bar > Import", List.of()
        ));
        registerCommand(new DesktopCommandDefinition(
            "menu.export", "Export menu", "export", StateTokenRole.ACTION_SECONDARY,
            "mc.icon.export", false, KeyStroke.getKeyStroke("alt E"),
            KeyStroke.getKeyStroke("ctrl shift E"), "Top Bar > Export", List.of()
        ));
        registerCommand(new DesktopCommandDefinition(
            "menu.share", "Share menu", "export", StateTokenRole.ACTION_SECONDARY,
            null, false, KeyStroke.getKeyStroke("alt M"),
            KeyStroke.getKeyStroke("ctrl shift M"), "Top Bar > Share", List.of()
        ));
        registerCommand(new DesktopCommandDefinition(
            "menu.print", "Print menu", "export", StateTokenRole.ACTION_SECONDARY,
            "mc.icon.print", false, KeyStroke.getKeyStroke("alt P"),
            KeyStroke.getKeyStroke("ctrl shift P"), "Top Bar > Print", List.of()
        ));
        registerCommand(new DesktopCommandDefinition(
            "voucher.contra", "Contra voucher", "voucher.create", StateTokenRole.ACTION_SECONDARY,
            null, false, KeyStroke.getKeyStroke(KeyEvent.VK_F4, 0),
            KeyStroke.getKeyStroke("ctrl shift F4"), "Vouchers > Contra", List.of("Alt+F4 reserved by Windows")
        ));
        registerCommand(new DesktopCommandDefinition(
            "voucher.payment", "Payment voucher", "voucher.create", StateTokenRole.ACTION_SECONDARY,
            null, false, KeyStroke.getKeyStroke(KeyEvent.VK_F5, 0),
            KeyStroke.getKeyStroke("ctrl shift F5"), "Vouchers > Payment", List.of()
        ));
        registerCommand(new DesktopCommandDefinition(
            "voucher.receipt", "Receipt voucher", "voucher.create", StateTokenRole.ACTION_SECONDARY,
            null, false, KeyStroke.getKeyStroke(KeyEvent.VK_F6, 0),
            KeyStroke.getKeyStroke("ctrl shift F6"), "Vouchers > Receipt", List.of()
        ));
        registerCommand(new DesktopCommandDefinition(
            "voucher.journal", "Journal voucher", "voucher.create", StateTokenRole.ACTION_SECONDARY,
            null, false, KeyStroke.getKeyStroke(KeyEvent.VK_F7, 0),
            KeyStroke.getKeyStroke("ctrl shift F7"), "Vouchers > Journal", List.of()
        ));
        registerCommand(new DesktopCommandDefinition(
            "voucher.sales", "Sales voucher", "voucher.create", StateTokenRole.ACTION_SECONDARY,
            null, false, KeyStroke.getKeyStroke(KeyEvent.VK_F8, 0),
            KeyStroke.getKeyStroke("ctrl shift F8"), "Vouchers > Sales", List.of()
        ));
        registerCommand(new DesktopCommandDefinition(
            "voucher.purchase", "Purchase voucher", "voucher.create", StateTokenRole.ACTION_SECONDARY,
            null, false, KeyStroke.getKeyStroke(KeyEvent.VK_F9, 0),
            KeyStroke.getKeyStroke("ctrl shift F9"), "Vouchers > Purchase", List.of()
        ));
        registerCommand(new DesktopCommandDefinition(
            "voucher.other", "Other vouchers", "voucher.create", StateTokenRole.ACTION_SECONDARY,
            null, false, KeyStroke.getKeyStroke(KeyEvent.VK_F10, 0),
            KeyStroke.getKeyStroke("ctrl shift F10"), "Vouchers > Other Vouchers", List.of()
        ));
        registerCommand(new DesktopCommandDefinition(
            "voucher.debitNote", "Debit note", "voucher.create", StateTokenRole.ACTION_SECONDARY,
            null, false, KeyStroke.getKeyStroke(KeyEvent.VK_F5, InputEvent.ALT_DOWN_MASK),
            KeyStroke.getKeyStroke("ctrl alt F5"), "Vouchers > Debit Note", List.of()
        ));
        registerCommand(new DesktopCommandDefinition(
            "voucher.creditNote", "Credit note", "voucher.create", StateTokenRole.ACTION_SECONDARY,
            null, false, KeyStroke.getKeyStroke(KeyEvent.VK_F6, InputEvent.ALT_DOWN_MASK),
            KeyStroke.getKeyStroke("ctrl alt F6"), "Vouchers > Credit Note", List.of()
        ));
        registerCommand(new DesktopCommandDefinition(
            "voucher.stockJournal", "Stock / Manufacturing journal", "voucher.create", StateTokenRole.ACTION_SECONDARY,
            null, false, KeyStroke.getKeyStroke(KeyEvent.VK_F7, InputEvent.ALT_DOWN_MASK),
            KeyStroke.getKeyStroke("ctrl alt F7"), "Vouchers > Stock Journal", List.of()
        ));
        registerCommand(new DesktopCommandDefinition(
            "voucher.deliveryNote", "Delivery note", "voucher.create", StateTokenRole.ACTION_SECONDARY,
            null, false, KeyStroke.getKeyStroke(KeyEvent.VK_F8, InputEvent.ALT_DOWN_MASK),
            KeyStroke.getKeyStroke("ctrl alt F8"), "Vouchers > Delivery Note", List.of()
        ));
        registerCommand(new DesktopCommandDefinition(
            "voucher.receiptNote", "Receipt note", "voucher.create", StateTokenRole.ACTION_SECONDARY,
            null, false, KeyStroke.getKeyStroke(KeyEvent.VK_F9, InputEvent.ALT_DOWN_MASK),
            KeyStroke.getKeyStroke("ctrl alt F9"), "Vouchers > Receipt Note", List.of()
        ));
        registerCommand(new DesktopCommandDefinition(
            "voucher.rejectionsOut", "Rejections Out", "voucher.create", StateTokenRole.ACTION_SECONDARY,
            null, false, KeyStroke.getKeyStroke(KeyEvent.VK_F5, InputEvent.CTRL_DOWN_MASK),
            KeyStroke.getKeyStroke("alt shift F5"), "Vouchers > Rejections Out", List.of()
        ));
        registerCommand(new DesktopCommandDefinition(
            "voucher.rejectionsIn", "Rejections In", "voucher.create", StateTokenRole.ACTION_SECONDARY,
            null, false, KeyStroke.getKeyStroke(KeyEvent.VK_F6, InputEvent.CTRL_DOWN_MASK),
            KeyStroke.getKeyStroke("alt shift F6"), "Vouchers > Rejections In", List.of()
        ));
        registerCommand(new DesktopCommandDefinition(
            "voucher.physicalStock", "Physical stock", "voucher.create", StateTokenRole.ACTION_SECONDARY,
            null, false, KeyStroke.getKeyStroke(KeyEvent.VK_F7, InputEvent.CTRL_DOWN_MASK),
            KeyStroke.getKeyStroke("alt shift F7"), "Vouchers > Physical Stock", List.of()
        ));
        registerCommand(new DesktopCommandDefinition(
            "voucher.salesOrder", "Sales order", "voucher.create", StateTokenRole.ACTION_SECONDARY,
            null, false, KeyStroke.getKeyStroke(KeyEvent.VK_F8, InputEvent.CTRL_DOWN_MASK),
            KeyStroke.getKeyStroke("alt shift F8"), "Vouchers > Sales Order", List.of()
        ));
        registerCommand(new DesktopCommandDefinition(
            "voucher.purchaseOrder", "Purchase order", "voucher.create", StateTokenRole.ACTION_SECONDARY,
            null, false, KeyStroke.getKeyStroke(KeyEvent.VK_F9, InputEvent.CTRL_DOWN_MASK),
            KeyStroke.getKeyStroke("alt shift F9"), "Vouchers > Purchase Order", List.of()
        ));
        registerCommand(new DesktopCommandDefinition(
            "voucher.changeMode", "Change voucher mode", "voucher.create", StateTokenRole.ACTION_SECONDARY,
            null, false, KeyStroke.getKeyStroke("ctrl H"),
            KeyStroke.getKeyStroke("ctrl shift H"), "Vouchers > Change Mode", List.of()
        ));
        registerCommand(new DesktopCommandDefinition(
            "voucher.toggleOptional", "Toggle Optional voucher", "voucher.create", StateTokenRole.ACTION_SECONDARY,
            null, false, KeyStroke.getKeyStroke("ctrl L"),
            KeyStroke.getKeyStroke("ctrl shift L"), "Vouchers > Optional", List.of()
        ));
        registerCommand(new DesktopCommandDefinition(
            "voucher.togglePostDated", "Toggle Post-Dated voucher", "voucher.create", StateTokenRole.ACTION_SECONDARY,
            null, false, KeyStroke.getKeyStroke("ctrl T"),
            KeyStroke.getKeyStroke("ctrl shift T"), "Vouchers > Post-Dated", List.of()
        ));
        registerCommand(new DesktopCommandDefinition(
            "voucher.moreDetails", "More details / Tax analysis", "voucher.read", StateTokenRole.ACTION_SECONDARY,
            "mc.icon.details", false, KeyStroke.getKeyStroke("ctrl I"),
            KeyStroke.getKeyStroke("ctrl shift I"), "Vouchers > More Details", List.of()
        ));
        registerCommand(new DesktopCommandDefinition(
            "voucher.inlineAlter", "Inline master alter", "master.create", StateTokenRole.ACTION_SECONDARY,
            "mc.icon.ledger", false, KeyStroke.getKeyStroke("ctrl ENTER"),
            KeyStroke.getKeyStroke("ctrl shift ENTER"), "Vouchers > Alter Master", List.of()
        ));
        registerCommand(new DesktopCommandDefinition(
            "voucher.repeatNarration", "Repeat narration", "voucher.create", StateTokenRole.ACTION_SECONDARY,
            null, false, KeyStroke.getKeyStroke("ctrl R"),
            KeyStroke.getKeyStroke("ctrl shift R"), "Vouchers > Repeat Narration", List.of()
        ));
        registerCommand(new DesktopCommandDefinition(
            "voucher.prev", "Previous voucher", "voucher.read", StateTokenRole.ACTION_SECONDARY,
            "mc.icon.back", false, KeyStroke.getKeyStroke(KeyEvent.VK_PAGE_UP, 0),
            KeyStroke.getKeyStroke("ctrl PAGE_UP"), "Vouchers > Previous Voucher", List.of()
        ));
        registerCommand(new DesktopCommandDefinition(
            "voucher.next", "Next voucher", "voucher.read", StateTokenRole.ACTION_SECONDARY,
            null, false, KeyStroke.getKeyStroke(KeyEvent.VK_PAGE_DOWN, 0),
            KeyStroke.getKeyStroke("ctrl PAGE_DOWN"), "Vouchers > Next Voucher", List.of()
        ));
        registerCommand(new DesktopCommandDefinition(
            "voucher.deleteLine", "Delete voucher line", "voucher.create", StateTokenRole.ACTION_DANGER,
            "mc.icon.delete", false, KeyStroke.getKeyStroke("ctrl D"),
            KeyStroke.getKeyStroke("ctrl shift D"), "Vouchers > Delete Line", List.of()
        ));
        registerCommand(new DesktopCommandDefinition(
            "voucher.accept", "Accept voucher", "voucher.create", StateTokenRole.ACTION_PRIMARY,
            "mc.icon.save-document", true, KeyStroke.getKeyStroke("ctrl A"),
            KeyStroke.getKeyStroke("ctrl shift A"), "Vouchers > Accept", List.of()
        ));
        registerCommand(new DesktopCommandDefinition(
            "app.quitOrBack", "Quit or step back", "public", StateTokenRole.ACTION_SECONDARY,
            "mc.icon.close", false, KeyStroke.getKeyStroke("ctrl Q"),
            KeyStroke.getKeyStroke("ctrl shift Q"), "File > Exit", List.of("Cmd+Q reserved by macOS QuitHandler")
        ));
        registerCommand(new DesktopCommandDefinition(
            "daybook.duplicate", "Duplicate voucher", "voucher.create", StateTokenRole.ACTION_SECONDARY,
            null, false, KeyStroke.getKeyStroke("alt 2"),
            KeyStroke.getKeyStroke("ctrl shift 2"), "Day Book > Duplicate Voucher", List.of()
        ));
        registerCommand(new DesktopCommandDefinition(
            "daybook.insert", "Insert voucher", "voucher.create", StateTokenRole.ACTION_SECONDARY,
            null, false, KeyStroke.getKeyStroke("alt I"),
            KeyStroke.getKeyStroke("ctrl shift I"), "Day Book > Insert Voucher", List.of()
        ));
        registerCommand(new DesktopCommandDefinition(
            "daybook.cancel", "Cancel voucher", "voucher.post", StateTokenRole.ACTION_DANGER,
            "mc.icon.delete", true, KeyStroke.getKeyStroke("alt X"),
            KeyStroke.getKeyStroke("ctrl shift X"), "Day Book > Cancel Voucher", List.of()
        ));
        registerCommand(new DesktopCommandDefinition(
            "grid.selectRow", "Select / deselect row", "authenticated", StateTokenRole.ACTION_SECONDARY,
            null, false, KeyStroke.getKeyStroke(KeyEvent.VK_SPACE, 0),
            KeyStroke.getKeyStroke("alt SPACE"), "Grid > Toggle Row Selection", List.of()
        ));
        registerCommand(new DesktopCommandDefinition(
            "grid.selectAll", "Select all rows", "authenticated", StateTokenRole.ACTION_SECONDARY,
            null, false, KeyStroke.getKeyStroke(KeyEvent.VK_SPACE, InputEvent.CTRL_DOWN_MASK),
            KeyStroke.getKeyStroke("ctrl shift SPACE"), "Grid > Select All Rows", List.of()
        ));
        registerCommand(new DesktopCommandDefinition(
            "grid.removeLine", "Remove / hide line", "report.read", StateTokenRole.ACTION_SECONDARY,
            null, false, KeyStroke.getKeyStroke("alt R"),
            KeyStroke.getKeyStroke("ctrl alt R"), "Grid > Hide Selected Row", List.of()
        ));
        registerCommand(new DesktopCommandDefinition(
            "grid.restoreLine", "Restore last hidden line", "report.read", StateTokenRole.ACTION_SECONDARY,
            null, false, KeyStroke.getKeyStroke("alt U"),
            KeyStroke.getKeyStroke("ctrl alt U"), "Grid > Restore Last Hidden Row", List.of()
        ));
        registerCommand(new DesktopCommandDefinition(
            "grid.restoreAllLines", "Restore all hidden lines", "report.read", StateTokenRole.ACTION_SECONDARY,
            null, false, KeyStroke.getKeyStroke("ctrl U"),
            KeyStroke.getKeyStroke("ctrl shift U"), "Grid > Restore All Hidden Rows", List.of()
        ));
        registerCommand(new DesktopCommandDefinition(
            "report.explodeRow", "Explode row details", "report.read", StateTokenRole.ACTION_SECONDARY,
            null, false, KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, InputEvent.SHIFT_DOWN_MASK),
            KeyStroke.getKeyStroke("alt ENTER"), "Reports > Explode Row", List.of()
        ));
        registerCommand(new DesktopCommandDefinition(
            "report.addColumn", "New comparative column", "report.read", StateTokenRole.ACTION_SECONDARY,
            null, false, KeyStroke.getKeyStroke("alt C"),
            KeyStroke.getKeyStroke("ctrl alt C"), "Reports > New Column", List.of()
        ));
        registerCommand(new DesktopCommandDefinition(
            "report.autoColumn", "Auto column", "report.read", StateTokenRole.ACTION_SECONDARY,
            null, false, KeyStroke.getKeyStroke("alt N"),
            KeyStroke.getKeyStroke("ctrl alt N"), "Reports > Auto Column", List.of()
        ));
        registerCommand(new DesktopCommandDefinition(
            "audit.editLog", "MCA Edit Log viewer", "report.read", StateTokenRole.ACTION_SECONDARY,
            "mc.icon.details", false, KeyStroke.getKeyStroke("alt Q"),
            KeyStroke.getKeyStroke("ctrl alt Q"), "Audit > Edit Log Redline", List.of()
        ));
        registerCommand(new DesktopCommandDefinition(
            "search.smartFind", "SmartFind global search", "authenticated", StateTokenRole.ACTION_SECONDARY,
            "mc.icon.search", false, KeyStroke.getKeyStroke("alt F"),
            KeyStroke.getKeyStroke("ctrl F"), "Navigation > SmartFind", List.of()
        ));
        registerCommand(new DesktopCommandDefinition(
            "voucher.autofill", "Autofill voucher lines", "voucher.create", StateTokenRole.ACTION_SECONDARY,
            null, false, KeyStroke.getKeyStroke("ctrl alt F"),
            KeyStroke.getKeyStroke("alt shift F"), "Vouchers > Autofill", List.of()
        ));
        registerCommand(new DesktopCommandDefinition(
            "daybook.add", "Add voucher from register", "voucher.create", StateTokenRole.ACTION_SECONDARY,
            null, false, KeyStroke.getKeyStroke("alt A"),
            KeyStroke.getKeyStroke("ctrl alt A"), "Day Book > Add Voucher", List.of()
        ));
        registerCommand(new DesktopCommandDefinition(
            "daybook.delete", "Delete draft voucher", "voucher.create", StateTokenRole.ACTION_DANGER,
            "mc.icon.delete", true, KeyStroke.getKeyStroke("alt D"),
            KeyStroke.getKeyStroke("ctrl alt D"), "Day Book > Delete Voucher", List.of()
        ));
        registerCommand(new DesktopCommandDefinition(
            "report.basisOfValues", "Basis of Values", "report.read", StateTokenRole.ACTION_SECONDARY,
            "mc.icon.filter", false, KeyStroke.getKeyStroke("ctrl B"),
            KeyStroke.getKeyStroke("ctrl shift B"), "Reports > Basis of Values", List.of()
        ));
        registerCommand(new DesktopCommandDefinition(
            "report.exceptions", "Exception Reports", "report.read", StateTokenRole.ACTION_SECONDARY,
            "mc.icon.warning", false, KeyStroke.getKeyStroke("ctrl J"),
            KeyStroke.getKeyStroke("ctrl shift J"), "Reports > Exceptions", List.of()
        ));
        registerCommand(new DesktopCommandDefinition(
            "banking.autoMatch", "Auto BRS Matching", "voucher.create", StateTokenRole.ACTION_SECONDARY,
            "mc.icon.refresh", true, KeyStroke.getKeyStroke("ctrl alt M"),
            KeyStroke.getKeyStroke("alt shift M"), "Banking > Auto BRS", List.of()
        ));
    }

    private record ActiveModalContext(
        String contextId,
        String locale,
        long generation,
        List<ModalActionSpec> actions,
        Set<Integer> heldAtOpenKeyCodes
    ) {
    }

    private record ScopedBindingTarget(
        JComponent container,
        Map<String, Runnable> handlers,
        Map<String, BooleanSupplier> enablementPredicates
    ) {
    }

    private final class ScopedCommandAction extends AbstractAction {
        private final String commandId;
        private final boolean mutation;
        private final BooleanSupplier enabledSupplier;
        private final Runnable handler;

        private ScopedCommandAction(
            String commandId,
            boolean mutation,
            BooleanSupplier enabledSupplier,
            Runnable handler
        ) {
            this.commandId = commandId;
            this.mutation = mutation;
            this.enabledSupplier = enabledSupplier;
            this.handler = handler;
        }

        @Override
        public void actionPerformed(ActionEvent event) {
            synchronized (DesktopCommandRegistry.this) {
                if (!modalStack.isEmpty()) {
                    return;
                }
                if (!enabledSupplier.getAsBoolean()) {
                    return;
                }
                Component focusOwner = event.getSource() instanceof Component comp ? comp : null;
                if (isInputConsumingComponent(focusOwner) && event.getActionCommand() != null
                    && event.getActionCommand().length() == 1) {
                    return;
                }
                if (mutation) {
                    if (inFlightMutationCommandId != null) {
                        return;
                    }
                    inFlightMutationCommandId = commandId;
                }
                handler.run();
            }
        }
    }
}
