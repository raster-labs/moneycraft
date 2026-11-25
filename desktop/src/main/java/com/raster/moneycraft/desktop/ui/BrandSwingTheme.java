package com.raster.moneycraft.desktop.ui;

import com.raster.moneycraft.desktop.keyboard.StateTokenRole;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Component;
import java.awt.Font;
import java.awt.FontFormatException;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GraphicsEnvironment;
import java.awt.Insets;
import java.awt.font.TextAttribute;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import javax.swing.JButton;
import javax.swing.UIManager;
import javax.swing.border.AbstractBorder;

/**
 * MoneyCraft Brand Identity v0.2.0 and VIS-001 Swing visual theme (Doc 29 §5-6, Doc 46 §3).
 * Loads pinned Manrope 4.505 and JetBrains Mono 2.304 TTF assets from classpath/workspace,
 * preserves OFL notices, and enforces WCAG 2.2 AA contrast tokens (#006D68 Fold primary,
 * #142F42 Ink on #F6FAF9 Paper, 1px dashed disabled boundary with zero opacity fade, and
 * 2px white + 2px ink focus ring).
 */
public final class BrandSwingTheme {

    public static final Color INK = new Color(0x14, 0x2F, 0x42);
    public static final Color TEAL = new Color(0x00, 0x8D, 0x87);
    public static final Color FOLD = new Color(0x00, 0x6D, 0x68);
    public static final Color MINT = new Color(0x4C, 0xE1, 0xBD);
    public static final Color JADE = new Color(0x22, 0xB6, 0x9C);
    public static final Color PAPER = new Color(0xF6, 0xFA, 0xF9);
    public static final Color WHITE = new Color(0xFF, 0xFF, 0xFF);
    public static final Color ERROR = new Color(0xB4, 0x23, 0x18);
    public static final Color WARNING = new Color(0x6F, 0x4E, 0x00);

    // Dark-theme semantic surface and state tokens meeting >= 4.5:1 WCAG 2.2 AA contrast (Doc 50 §4)
    public static final Color DARK_PAGE = new Color(0x0D, 0x1E, 0x2B);
    public static final Color DARK_SURFACE = new Color(0x14, 0x2A, 0x3B);
    public static final Color DARK_TEXT = PAPER;
    public static final Color DARK_WARNING = new Color(0xFF, 0xD1, 0x66);

    private static final String MANROPE_RESOURCE = "/fonts/Manrope-VariableFont_wght.ttf";
    private static final String JETBRAINS_MONO_RESOURCE = "/fonts/JetBrainsMono-Variable.ttf";
    private static final String MANROPE_OFL_RESOURCE = "/fonts/OFL-Manrope.txt";
    private static final String JETBRAINS_OFL_RESOURCE = "/fonts/OFL-JetBrainsMono.txt";

    private static final MathContext CONTRAST_MC = new MathContext(12, RoundingMode.HALF_UP);

    private final Font manropeBase;
    private final Font jetBrainsMonoBase;
    private final String manropeLicenseNotice;
    private final String jetBrainsMonoLicenseNotice;

    public BrandSwingTheme() {
        this.manropeBase = loadAndRegisterFont(
            MANROPE_RESOURCE,
            Path.of("desktop/src/main/resources/fonts/Manrope-VariableFont_wght.ttf")
        );
        this.jetBrainsMonoBase = loadAndRegisterFont(
            JETBRAINS_MONO_RESOURCE,
            Path.of("desktop/src/main/resources/fonts/JetBrainsMono-Variable.ttf")
        );
        this.manropeLicenseNotice = loadRequiredNotice(
            MANROPE_OFL_RESOURCE,
            Path.of("desktop/src/main/resources/fonts/OFL-Manrope.txt")
        );
        this.jetBrainsMonoLicenseNotice = loadRequiredNotice(
            JETBRAINS_OFL_RESOURCE,
            Path.of("desktop/src/main/resources/fonts/OFL-JetBrainsMono.txt")
        );
    }

    public Font bodyFont(int sizePx) {
        Map<TextAttribute, Object> attrs = new HashMap<>();
        attrs.put(TextAttribute.SIZE, Integer.valueOf(sizePx));
        attrs.put(TextAttribute.WEIGHT, new BigDecimal("1.0"));
        return manropeBase.deriveFont(attrs);
    }

    public Font headingFont(int sizePx) {
        Map<TextAttribute, Object> attrs = new HashMap<>();
        attrs.put(TextAttribute.SIZE, Integer.valueOf(sizePx));
        attrs.put(TextAttribute.WEIGHT, new BigDecimal("1.5"));
        return manropeBase.deriveFont(attrs);
    }

    /**
     * Financial amounts remain in Manrope (which contains U+20B9 Indian Rupee).
     */
    public Font amountFont(int sizePx) {
        Map<TextAttribute, Object> attrs = new HashMap<>();
        attrs.put(TextAttribute.SIZE, Integer.valueOf(sizePx));
        attrs.put(TextAttribute.KERNING, Integer.valueOf(0));
        return manropeBase.deriveFont(attrs);
    }

    /**
     * Identifiers and diagnostics use JetBrains Mono with ligatures explicitly disabled (Doc 29 §6).
     */
    public Font identifierMonoFont(int sizePx) {
        Map<TextAttribute, Object> attrs = new HashMap<>();
        attrs.put(TextAttribute.SIZE, Integer.valueOf(sizePx));
        attrs.put(TextAttribute.LIGATURES, Integer.valueOf(0));
        attrs.put(TextAttribute.KERNING, Integer.valueOf(0));
        return jetBrainsMonoBase.deriveFont(attrs);
    }

    public boolean supportsRupeeGlyphInAmountFont() {
        return amountFont(14).canDisplay('\u20B9');
    }

    public String manropeLicenseNotice() {
        return manropeLicenseNotice;
    }

    public String jetBrainsMonoLicenseNotice() {
        return jetBrainsMonoLicenseNotice;
    }

    public void applyGlobalDefaults() {
        applyGlobalDefaults(false, new BigDecimal("100"));
    }

    /**
     * Applies Light or Dark semantic colours and scaled typography (75%..200%) across Swing UIManager.
     */
    public void applyGlobalDefaults(boolean darkTheme, BigDecimal scalePercent) {
        int scaledBodyPx = scaleFontPx(14, scalePercent);
        Font scaledBody = bodyFont(scaledBodyPx);
        Color pageBg = darkTheme ? DARK_PAGE : PAPER;
        Color surfaceBg = darkTheme ? DARK_SURFACE : WHITE;
        Color textFg = darkTheme ? DARK_TEXT : INK;

        UIManager.put("Panel.background", pageBg);
        UIManager.put("Panel.foreground", textFg);
        UIManager.put("Label.foreground", textFg);
        UIManager.put("Label.font", scaledBody);
        UIManager.put("Button.font", scaledBody);
        UIManager.put("Menu.font", scaledBody);
        UIManager.put("MenuItem.font", scaledBody);
        UIManager.put("TextField.font", scaledBody);
        UIManager.put("TextField.background", surfaceBg);
        UIManager.put("TextField.foreground", textFg);
        UIManager.put("Table.font", scaledBody);
        UIManager.put("Table.background", surfaceBg);
        UIManager.put("Table.foreground", textFg);
        UIManager.put("Table.rowHeight", Integer.valueOf(Math.max(22, scaledBodyPx + 10)));
        UIManager.put("Table.selectionBackground", MINT);
        UIManager.put("Table.selectionForeground", INK);
    }

    public static int scaleFontPx(int basePx, BigDecimal scalePercent) {
        if (scalePercent == null) {
            return basePx;
        }
        return BigDecimal.valueOf(basePx)
            .multiply(scalePercent)
            .divide(BigDecimal.valueOf(100), 0, RoundingMode.HALF_UP)
            .intValue();
    }

    /**
     * Creates a semantic action button adhering strictly to Document 46 §3 state tokens
     * (opaque colours, dashed ink disabled border with zero opacity fade, and double focus ring).
     */
    public JButton createStyledActionButton(String label, StateTokenRole role) {
        Objects.requireNonNull(label, "label must not be null");
        Objects.requireNonNull(role, "role must not be null");

        JButton button = new ThemedActionButton(label, role);
        button.setFont(bodyFont(14));
        button.setOpaque(true);
        button.setContentAreaFilled(true);
        button.setFocusPainted(false);
        styleButtonForCurrentState(button, role);
        button.addPropertyChangeListener("enabled", evt -> styleButtonForCurrentState(button, role));
        return button;
    }

    public static void styleButtonForCurrentState(JButton button, StateTokenRole role) {
        if (!button.isEnabled()) {
            button.setBackground(PAPER);
            button.setForeground(INK);
            button.setBorder(new BrandButtonBorder(role, true, button.isFocusOwner()));
            return;
        }
        switch (role) {
            case ACTION_PRIMARY -> {
                button.setBackground(FOLD);
                button.setForeground(WHITE);
            }
            case ACTION_DANGER -> {
                button.setBackground(ERROR);
                button.setForeground(WHITE);
            }
            case ACTION_SECONDARY -> {
                button.setBackground(WHITE);
                button.setForeground(INK);
            }
        }
        button.setBorder(new BrandButtonBorder(role, false, button.isFocusOwner()));
    }

    /**
     * Computes exact WCAG 2.2 sRGB contrast ratio between two opaque colours using BigDecimal math.
     */
    public static BigDecimal computeContrastRatio(Color first, Color second) {
        Objects.requireNonNull(first, "first must not be null");
        Objects.requireNonNull(second, "second must not be null");
        BigDecimal lum1 = relativeLuminance(first);
        BigDecimal lum2 = relativeLuminance(second);
        BigDecimal lighter = lum1.max(lum2);
        BigDecimal darker = lum1.min(lum2);
        BigDecimal offset = new BigDecimal("0.05");
        return lighter.add(offset).divide(darker.add(offset), 3, RoundingMode.HALF_UP);
    }

    private static BigDecimal relativeLuminance(Color color) {
        BigDecimal r = channelLinear(color.getRed());
        BigDecimal g = channelLinear(color.getGreen());
        BigDecimal b = channelLinear(color.getBlue());
        return r.multiply(new BigDecimal("0.2126"), CONTRAST_MC)
            .add(g.multiply(new BigDecimal("0.7152"), CONTRAST_MC), CONTRAST_MC)
            .add(b.multiply(new BigDecimal("0.0722"), CONTRAST_MC), CONTRAST_MC);
    }

    private static BigDecimal channelLinear(int channel8Bit) {
        BigDecimal srgb = BigDecimal.valueOf(channel8Bit)
            .divide(BigDecimal.valueOf(255), CONTRAST_MC);
        if (srgb.compareTo(new BigDecimal("0.04045")) <= 0) {
            return srgb.divide(new BigDecimal("12.92"), CONTRAST_MC);
        }
        // ((srgb + 0.055) / 1.055) ^ 2.4 computed via high-precision series/pow approximation
        BigDecimal base = srgb.add(new BigDecimal("0.055"), CONTRAST_MC)
            .divide(new BigDecimal("1.055"), CONTRAST_MC);
        // Exact rational exponent 2.4 = 12/5 -> fifthRoot(base^12) via Newton-Raphson in BigDecimal!
        BigDecimal pow12 = base.pow(12, CONTRAST_MC);
        return fifthRoot(pow12, base.multiply(base, CONTRAST_MC));
    }

    private static BigDecimal fifthRoot(BigDecimal target, BigDecimal initialGuess) {
        if (target.signum() == 0) {
            return BigDecimal.ZERO;
        }
        BigDecimal x = initialGuess.signum() > 0 ? initialGuess : BigDecimal.ONE;
        BigDecimal four = BigDecimal.valueOf(4);
        BigDecimal five = BigDecimal.valueOf(5);
        for (int i = 0; i < 25; i++) {
            BigDecimal x4 = x.pow(4, CONTRAST_MC);
            x = four.multiply(x, CONTRAST_MC)
                .add(target.divide(x4, CONTRAST_MC), CONTRAST_MC)
                .divide(five, CONTRAST_MC);
        }
        return x;
    }

    private static Font loadAndRegisterFont(String classpathPath, Path fallbackPath) {
        try (InputStream in = BrandSwingTheme.class.getResourceAsStream(classpathPath)) {
            Font loaded;
            if (in != null) {
                loaded = Font.createFont(Font.TRUETYPE_FONT, in);
            } else if (Files.exists(fallbackPath)) {
                try (InputStream fileIn = Files.newInputStream(fallbackPath)) {
                    loaded = Font.createFont(Font.TRUETYPE_FONT, fileIn);
                }
            } else {
                throw new IllegalStateException("Missing required Brand v0.2.0 TTF font: " + classpathPath);
            }
            GraphicsEnvironment.getLocalGraphicsEnvironment().registerFont(loaded);
            return loaded;
        } catch (FontFormatException | IOException ex) {
            throw new IllegalStateException("Failed loading Brand v0.2.0 TTF font " + classpathPath, ex);
        }
    }

    private static String loadRequiredNotice(String classpathPath, Path fallbackPath) {
        try (InputStream in = BrandSwingTheme.class.getResourceAsStream(classpathPath)) {
            if (in != null) {
                return new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
            if (Files.exists(fallbackPath)) {
                return Files.readString(fallbackPath, StandardCharsets.UTF_8);
            }
            throw new IllegalStateException("Missing required OFL font licence notice: " + classpathPath);
        } catch (IOException ex) {
            throw new IllegalStateException("Failed loading OFL notice " + classpathPath, ex);
        }
    }

    private static final class ThemedActionButton extends JButton {
        private final StateTokenRole role;

        private ThemedActionButton(String text, StateTokenRole role) {
            super(text);
            this.role = role;
            addFocusListener(new java.awt.event.FocusAdapter() {
                @Override
                public void focusGained(java.awt.event.FocusEvent e) {
                    styleButtonForCurrentState(ThemedActionButton.this, ThemedActionButton.this.role);
                }

                @Override
                public void focusLost(java.awt.event.FocusEvent e) {
                    styleButtonForCurrentState(ThemedActionButton.this, ThemedActionButton.this.role);
                }
            });
        }

        @Override
        protected void paintComponent(Graphics g) {
            g.setColor(getBackground());
            g.fillRect(0, 0, getWidth(), getHeight());
            super.paintComponent(g);
        }
    }

    private static final class BrandButtonBorder extends AbstractBorder {
        private final StateTokenRole role;
        private final boolean disabled;
        private final boolean focused;

        private BrandButtonBorder(StateTokenRole role, boolean disabled, boolean focused) {
            this.role = role;
            this.disabled = disabled;
            this.focused = focused;
        }

        @Override
        public void paintBorder(Component c, Graphics g, int x, int y, int width, int height) {
            Graphics2D g2 = (Graphics2D) g.create();
            try {
                if (focused) {
                    g2.setColor(INK);
                    g2.setStroke(new BasicStroke(2));
                    g2.drawRect(x + 1, y + 1, width - 3, height - 3);
                    g2.setColor(WHITE);
                    g2.drawRect(x + 3, y + 3, width - 7, height - 7);
                }
                if (disabled) {
                    g2.setColor(INK);
                    g2.setStroke(new BasicStroke(
                        1,
                        BasicStroke.CAP_BUTT,
                        BasicStroke.JOIN_MITER,
                        10,
                        new float[]{4, 3},
                        0
                    ));
                    g2.drawRect(x + 4, y + 4, width - 9, height - 9);
                } else if (role == StateTokenRole.ACTION_SECONDARY) {
                    g2.setColor(INK);
                    g2.setStroke(new BasicStroke(1));
                    g2.drawRect(x + 4, y + 4, width - 9, height - 9);
                }
            } finally {
                g2.dispose();
            }
        }

        @Override
        public Insets getBorderInsets(Component c) {
            return new Insets(8, 14, 8, 14);
        }
    }
}
