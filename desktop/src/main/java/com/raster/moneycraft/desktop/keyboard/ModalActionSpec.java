package com.raster.moneycraft.desktop.keyboard;

import java.util.Objects;
import java.util.function.BooleanSupplier;
import javax.swing.KeyStroke;

/**
 * Explicit contextual modal action-letter binding (KEY-001, KEY-002).
 * Never inferred from English label initials or array positions.
 */
public record ModalActionSpec(
    String contextId,
    String locale,
    String commandId,
    String label,
    char lowerLetter,
    char upperLetter,
    KeyStroke qualifiedModifierChord,
    StateTokenRole stateTokenRole,
    String iconSemanticId,
    String permissionRef,
    BooleanSupplier enabledPredicate,
    Runnable actionHandler
) {

    public ModalActionSpec {
        Objects.requireNonNull(contextId, "contextId must not be null");
        Objects.requireNonNull(locale, "locale must not be null");
        Objects.requireNonNull(commandId, "commandId must not be null");
        Objects.requireNonNull(label, "label must not be null");
        Objects.requireNonNull(stateTokenRole, "stateTokenRole must not be null");
        Objects.requireNonNull(permissionRef, "permissionRef must not be null");
        Objects.requireNonNull(enabledPredicate, "enabledPredicate must not be null");
        Objects.requireNonNull(actionHandler, "actionHandler must not be null");

        if (contextId.isBlank() || locale.isBlank() || commandId.isBlank() || label.isBlank()) {
            throw new InvalidModalActionSetException("Context, locale, commandId, and label must be non-blank.");
        }
        if (!isValidExplicitPair(lowerLetter, upperLetter)) {
            throw new InvalidModalActionSetException(
                "Invalid explicit case pair '" + upperLetter + "/" + lowerLetter
                    + "' for command '" + commandId + "' in context '" + contextId + "'."
            );
        }
    }

    public String pairDisplay() {
        return upperLetter + "/" + lowerLetter;
    }

    public boolean matchesCharacter(char ch) {
        return ch == lowerLetter || ch == upperLetter;
    }

    private static boolean isValidExplicitPair(char lower, char upper) {
        if (lower >= 'a' && lower <= 'z') {
            return upper == (char) (lower - ('a' - 'A'));
        }
        return Character.isLetter(lower)
            && Character.isLetter(upper)
            && lower != upper
            && Character.toUpperCase(lower) == upper
            && Character.toLowerCase(upper) == lower;
    }
}
