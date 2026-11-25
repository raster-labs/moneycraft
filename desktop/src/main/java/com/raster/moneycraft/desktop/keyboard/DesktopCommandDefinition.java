package com.raster.moneycraft.desktop.keyboard;

import java.util.List;
import java.util.Objects;
import javax.swing.KeyStroke;

/**
 * Shared semantic command descriptor matching browser command IDs, permissions, and visual tokens
 * while adding qualified Swing native bindings and reachable menu fallbacks (KEY-002).
 */
public record DesktopCommandDefinition(
    String commandId,
    String label,
    String permissionRef,
    StateTokenRole stateTokenRole,
    String iconSemanticId,
    boolean financialMutation,
    KeyStroke primaryBinding,
    KeyStroke qualifiedAlternativeBinding,
    String reachableMenuPath,
    List<String> osReservationNotes
) {

    public DesktopCommandDefinition {
        Objects.requireNonNull(commandId, "commandId must not be null");
        Objects.requireNonNull(label, "label must not be null");
        Objects.requireNonNull(permissionRef, "permissionRef must not be null");
        Objects.requireNonNull(stateTokenRole, "stateTokenRole must not be null");
        Objects.requireNonNull(reachableMenuPath, "reachableMenuPath must not be null");
        if (commandId.isBlank() || label.isBlank() || reachableMenuPath.isBlank()) {
            throw new IllegalArgumentException("commandId, label, and reachableMenuPath must be non-blank.");
        }
        osReservationNotes = osReservationNotes == null ? List.of() : List.copyOf(osReservationNotes);
    }
}
