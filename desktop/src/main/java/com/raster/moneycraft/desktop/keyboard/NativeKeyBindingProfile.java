package com.raster.moneycraft.desktop.keyboard;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import javax.swing.KeyStroke;

/**
 * Native Swing OS/layout/assistive-technology keyboard qualification profile (AT09-KEY-002-A..B).
 * Explicitly records OS-reserved system chords rather than claiming universal key interception.
 */
public record NativeKeyBindingProfile(
    String profileId,
    String clientBuildVersion,
    String javaRuntimeVersion,
    String operatingSystem,
    String keyboardLayout,
    String functionKeyMode,
    String assistiveTechnology,
    List<String> systemReservedUnavailableBindings,
    Map<String, KeyStroke> qualifiedModifierCandidates
) {

    public NativeKeyBindingProfile {
        Objects.requireNonNull(profileId, "profileId must not be null");
        Objects.requireNonNull(clientBuildVersion, "clientBuildVersion must not be null");
        Objects.requireNonNull(javaRuntimeVersion, "javaRuntimeVersion must not be null");
        Objects.requireNonNull(operatingSystem, "operatingSystem must not be null");
        Objects.requireNonNull(keyboardLayout, "keyboardLayout must not be null");
        Objects.requireNonNull(functionKeyMode, "functionKeyMode must not be null");
        Objects.requireNonNull(assistiveTechnology, "assistiveTechnology must not be null");

        systemReservedUnavailableBindings = systemReservedUnavailableBindings == null
            ? List.of()
            : List.copyOf(systemReservedUnavailableBindings);
        qualifiedModifierCandidates = qualifiedModifierCandidates == null
            ? Map.of()
            : Map.copyOf(qualifiedModifierCandidates);
    }

    public static NativeKeyBindingProfile defaultDesktopProfile() {
        String osName = System.getProperty("os.name", "GenericOS");
        String javaVer = System.getProperty("java.version", "25");
        return new NativeKeyBindingProfile(
            "mc-swing-default-en-gb",
            "1.0.0",
            javaVer,
            osName,
            "en-GB-QWERTY",
            "STANDARD_FUNCTION_KEYS",
            "OS_ACCESSIBILITY_STANDARD",
            List.of("META+Q", "META+TAB", "ALT+TAB", "ALT+F4", "CTRL+ALT+DELETE"),
            Map.of(
                "company.open", KeyStroke.getKeyStroke("ctrl shift O"),
                "ledger.create", KeyStroke.getKeyStroke("ctrl shift N"),
                "ledger.save", KeyStroke.getKeyStroke("ctrl shift S"),
                "journal.saveDraft", KeyStroke.getKeyStroke("ctrl shift S"),
                "journal.approve", KeyStroke.getKeyStroke("ctrl shift A"),
                "journal.post", KeyStroke.getKeyStroke("ctrl shift P"),
                "editor.discard", KeyStroke.getKeyStroke("ctrl shift D"),
                "editor.stay", KeyStroke.getKeyStroke("ctrl shift T"),
                "editor.close", KeyStroke.getKeyStroke("ctrl shift C"),
                "report.output", KeyStroke.getKeyStroke("ctrl shift U")
            )
        );
    }
}
