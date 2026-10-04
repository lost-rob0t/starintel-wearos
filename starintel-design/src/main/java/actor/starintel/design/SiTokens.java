package actor.starintel.design;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Canonical StarIntel design tokens for phone surfaces.
 *
 * This is the Java projection of docs/design/theme-tokens.md: semantic color
 * positions, a fixed spacing/radius scale, and a type scale. Surfaces consume
 * tokens; they never declare literal palette colors.
 *
 * Palettes mirror the watch-face knowledge base so a mission reads consistently
 * from wrist to pocket. Qtile Electric is locked to the real Qtile values.
 */
public final class SiTokens {
    /** Semantic color positions. Every palette provides all of them. */
    public static final class Palette {
        public final String id;
        public final String label;
        public final int background;
        public final int surface;
        public final int raised;
        public final int border;
        public final int accent;
        public final int accentAlt;
        public final int ok;
        public final int warn;
        public final int danger;
        public final int text;
        public final int muted;

        Palette(String id, String label, int background, int surface, int raised, int border,
                int accent, int accentAlt, int ok, int warn, int danger, int text, int muted) {
            this.id = id;
            this.label = label;
            this.background = background;
            this.surface = surface;
            this.raised = raised;
            this.border = border;
            this.accent = accent;
            this.accentAlt = accentAlt;
            this.ok = ok;
            this.warn = warn;
            this.danger = danger;
            this.text = text;
            this.muted = muted;
        }
    }

    // Spacing scale (dp). Nothing outside these steps.
    public static final int SPACE_XS = 4;
    public static final int SPACE_S = 8;
    public static final int SPACE_M = 12;
    public static final int SPACE_L = 16;
    public static final int SPACE_XL = 20;
    public static final int SPACE_XXL = 24;
    public static final int SPACE_XXXL = 32;

    // Corner radius scale (dp).
    public static final int RADIUS_CARD = 20;
    public static final int RADIUS_CONTROL = 15;
    public static final int RADIUS_PILL = 99;

    // Type scale (sp).
    public static final float TYPE_DISPLAY = 31f;
    public static final float TYPE_TITLE = 19f;
    public static final float TYPE_HEADLINE = 15f;
    public static final float TYPE_BODY = 14f;
    public static final float TYPE_LABEL = 12f;
    public static final float TYPE_EYEBROW = 10f;

    // Interaction floor: One UI and stock Android both treat 48dp as comfortable.
    public static final int TOUCH_MIN_DP = 48;

    /** StarIntel Cyan — the default operational palette. */
    public static final Palette CYAN = new Palette(
            "cyan", "StarIntel Cyan",
            rgb(6, 8, 12), rgb(16, 19, 28), rgb(24, 28, 40), rgb(46, 52, 68),
            rgb(45, 226, 230), rgb(151, 0, 204),
            rgb(98, 255, 0), rgb(251, 169, 34), rgb(221, 84, 110),
            rgb(243, 244, 245), rgb(164, 170, 188));

    /** Qtile Electric — accents locked to the real Qtile values; muted is a legibility tint of the KB mauve. */
    public static final Palette ELECTRIC = new Palette(
            "electric", "Qtile Electric",
            rgb(0x20, 0x21, 0x46), rgb(0x17, 0x0C, 0x32), rgb(0x24, 0x1B, 0x4D), rgb(0x3A, 0x2E, 0x6E),
            rgb(0x2D, 0xE2, 0xE6), rgb(0xF6, 0x01, 0x9D),
            rgb(0x62, 0xFF, 0x00), rgb(0xFB, 0xA9, 0x22), rgb(0xDD, 0x54, 0x6E),
            rgb(0xF3, 0xF4, 0xF5), rgb(0xC4, 0xBA, 0xD6));

    /** StarIntel Gold — warm command palette. */
    public static final Palette GOLD = new Palette(
            "gold", "StarIntel Gold",
            rgb(10, 9, 6), rgb(24, 21, 14), rgb(34, 29, 18), rgb(64, 56, 36),
            rgb(251, 169, 34), rgb(45, 226, 230),
            rgb(98, 255, 0), rgb(251, 169, 34), rgb(221, 84, 110),
            rgb(243, 244, 245), rgb(168, 158, 138));

    /** Tactical Green — restrained field palette. */
    public static final Palette GREEN = new Palette(
            "green", "Tactical Green",
            rgb(5, 10, 7), rgb(13, 22, 16), rgb(19, 32, 23), rgb(38, 58, 45),
            rgb(98, 255, 0), rgb(45, 226, 230),
            rgb(98, 255, 0), rgb(251, 169, 34), rgb(221, 84, 110),
            rgb(240, 244, 240), rgb(150, 170, 156));

    /** Monochrome — white/gray only; no chromatic accents. */
    public static final Palette MONO = new Palette(
            "mono", "Monochrome",
            rgb(8, 8, 8), rgb(20, 20, 20), rgb(30, 30, 30), rgb(56, 56, 56),
            rgb(243, 244, 245), rgb(180, 180, 180),
            rgb(220, 220, 220), rgb(180, 180, 180), rgb(255, 120, 120),
            rgb(243, 244, 245), rgb(160, 160, 160));

    private static final Palette[] ALL = {CYAN, ELECTRIC, GOLD, GREEN, MONO};

    private SiTokens() {}

    public static Palette[] palettes() {
        return ALL.clone();
    }

    public static Palette byId(String id) {
        for (Palette palette : ALL) {
            if (palette.id.equals(id)) return palette;
        }
        return CYAN;
    }

    /** Pure ARGB packing so token definitions stay unit-testable on the JVM. */
    public static int rgb(int r, int g, int b) {
        if (r < 0 || r > 255 || g < 0 || g > 255 || b < 0 || b > 255) {
            throw new IllegalArgumentException("color channel out of range");
        }
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    /** WCAG relative contrast ratio; used by tests to lock text-token legibility. */
    public static double contrastRatio(int a, int b) {
        double la = luminance(a);
        double lb = luminance(b);
        double lighter = Math.max(la, lb);
        double darker = Math.min(la, lb);
        return (lighter + 0.05d) / (darker + 0.05d);
    }

    static double luminance(int color) {
        double r = channel((color >> 16) & 0xFF);
        double g = channel((color >> 8) & 0xFF);
        double b = channel(color & 0xFF);
        return 0.2126d * r + 0.7152d * g + 0.0722d * b;
    }

    private static double channel(int value) {
        double v = value / 255.0d;
        return v <= 0.03928d ? v / 12.92d : Math.pow((v + 0.055d) / 1.055d, 2.4d);
    }

    /** Per-surface persisted palette selection. Apps stay dark-first by design. */
    public static final class ThemeStore {
        private static final String PREFS = "starintel_design";
        private static final String KEY_PALETTE = "palette";

        private final SharedPreferences prefs;

        public ThemeStore(Context context) {
            this.prefs = context.getApplicationContext()
                    .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        }

        public Palette current() {
            return SiTokens.byId(prefs.getString(KEY_PALETTE, null));
        }

        public void set(Palette palette) {
            prefs.edit().putString(KEY_PALETTE, palette.id).apply();
        }

        public Palette cycle() {
            Palette[] all = SiTokens.palettes();
            Palette current = current();
            Palette next = all[(current_index(current) + 1) % all.length];
            set(next);
            return next;
        }

        private static int current_index(Palette current) {
            for (int index = 0; index < ALL.length; index++) {
                if (ALL[index].id.equals(current.id)) return index;
            }
            return 0;
        }
    }
}
