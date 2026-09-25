package actor.starintel.design;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Shared widget factory for StarIntel phone surfaces.
 *
 * Every factory method reads the active palette; callers pass semantic roles, never
 * colors. Minimum touch target, type scale, spacing steps, and ripple treatment are
 * enforced here so One UI and stock Android render the same component identically.
 */
public final class Si {
    private final Context context;
    private final SiTokens.Palette palette;

    public Si(Context context, SiTokens.Palette palette) {
        this.context = context.getApplicationContext();
        this.palette = palette;
    }

    public SiTokens.Palette palette() {
        return palette;
    }

    // ---- type ----

    public TextView eyebrow(String value) {
        TextView view = label(value, SiTokens.TYPE_EYEBROW, palette.accent, true);
        view.setLetterSpacing(0.16f);
        view.setAllCaps(true);
        return view;
    }

    public TextView title(String value) {
        return label(value, SiTokens.TYPE_TITLE, palette.text, true);
    }

    public TextView display(String value) {
        return label(value, SiTokens.TYPE_DISPLAY, palette.text, true);
    }

    public TextView body(String value) {
        return label(value, SiTokens.TYPE_BODY, palette.muted, false);
    }

    public TextView bodyStrong(String value) {
        return label(value, SiTokens.TYPE_BODY, palette.text, false);
    }

    public TextView label(String value, float sizeSp, int color, boolean bold) {
        TextView view = new TextView(context);
        view.setText(value);
        view.setTextSize(sizeSp);
        view.setTextColor(color);
        view.setTypeface(bold ? Typeface.create("sans-serif-medium", Typeface.BOLD) : Typeface.DEFAULT);
        view.setLineSpacing(0f, 1.15f);
        return view;
    }

    /** Eyebrow + title + optional body; the standard screen header block. */
    public LinearLayout header(String eyebrowText, String titleText, String bodyText) {
        LinearLayout block = vertical();
        if (eyebrowText != null) block.addView(eyebrow(eyebrowText));
        block.addView(display(titleText), match(SiTokens.SPACE_XS));
        if (bodyText != null) block.addView(body(bodyText), match(SiTokens.SPACE_S));
        return block;
    }

    // ---- containers ----

    public LinearLayout vertical() {
        LinearLayout layout = new LinearLayout(context);
        layout.setOrientation(LinearLayout.VERTICAL);
        return layout;
    }

    public LinearLayout row() {
        LinearLayout layout = new LinearLayout(context);
        layout.setOrientation(LinearLayout.HORIZONTAL);
        layout.setGravity(Gravity.CENTER_VERTICAL);
        return layout;
    }

    public LinearLayout card() {
        return card(palette.border);
    }

    public LinearLayout card(int stroke) {
        LinearLayout card = vertical();
        card.setPadding(dp(SiTokens.SPACE_L), dp(SiTokens.SPACE_L), dp(SiTokens.SPACE_L), dp(SiTokens.SPACE_L));
        card.setBackground(rounded(palette.surface, stroke, SiTokens.RADIUS_CARD));
        return card;
    }

    /** Card with a left accent edge for section identity. */
    public LinearLayout accentCard(int accent) {
        GradientDrawable background = new GradientDrawable();
        background.setColor(palette.surface);
        background.setCornerRadius(dp(SiTokens.RADIUS_CARD));
        background.setStroke(dp(1), accent);
        LinearLayout card = vertical();
        card.setPadding(dp(SiTokens.SPACE_L), dp(SiTokens.SPACE_L), dp(SiTokens.SPACE_L), dp(SiTokens.SPACE_L));
        card.setBackground(background);
        return card;
    }

    public View divider() {
        View view = new View(context);
        view.setBackgroundColor(palette.border);
        view.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, Math.max(1, dp(1))));
        return view;
    }

    public TextView sectionHeader(String value) {
        TextView view = label(value, SiTokens.TYPE_EYEBROW, palette.muted, true);
        view.setLetterSpacing(0.14f);
        view.setAllCaps(true);
        return view;
    }

    // ---- controls ----

    /** The one primary action of a screen or section. */
    public Button primaryButton(String text, Runnable onClick) {
        Button button = baseButton(text, onClick);
        button.setBackground(ripple(palette.accent, palette.accent, SiTokens.RADIUS_CONTROL));
        button.setTextColor(onColor(palette.accent));
        return button;
    }

    public Button secondaryButton(String text, Runnable onClick) {
        Button button = baseButton(text, onClick);
        button.setBackground(ripple(palette.raised, palette.border, SiTokens.RADIUS_CONTROL));
        button.setTextColor(palette.text);
        return button;
    }

    public Button dangerButton(String text, Runnable onClick) {
        Button button = baseButton(text, onClick);
        button.setBackground(ripple(palette.raised, palette.danger, SiTokens.RADIUS_CONTROL));
        button.setTextColor(palette.danger);
        return button;
    }

    private Button baseButton(String text, Runnable onClick) {
        Button button = new Button(context);
        button.setText(text);
        button.setAllCaps(false);
        button.setTextSize(SiTokens.TYPE_LABEL);
        button.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        button.setLetterSpacing(0.06f);
        button.setMinHeight(dp(SiTokens.TOUCH_MIN_DP));
        button.setPadding(dp(SiTokens.SPACE_L), dp(SiTokens.SPACE_M), dp(SiTokens.SPACE_L), dp(SiTokens.SPACE_M));
        button.setStateListAnimator(null);
        button.setOnClickListener(ignored -> onClick.run());
        return button;
    }

    /** Bounded status pill: STATE · detail. Never used for actions. */
    public TextView statusPill(String state, String detail, int accent) {
        TextView pill = label(state + (detail == null || detail.isEmpty() ? "" : "  ·  " + detail),
                SiTokens.TYPE_LABEL, accent, true);
        pill.setLetterSpacing(0.08f);
        pill.setAllCaps(true);
        pill.setGravity(Gravity.CENTER);
        pill.setMinHeight(dp(SiTokens.TOUCH_MIN_DP));
        pill.setPadding(dp(SiTokens.SPACE_M), dp(SiTokens.SPACE_S), dp(SiTokens.SPACE_M), dp(SiTokens.SPACE_S));
        pill.setBackground(rounded(palette.raised, accent, SiTokens.RADIUS_PILL));
        return pill;
    }

    /** Big numeric metric with caption, for dashboards. */
    public LinearLayout metric(String caption, String value, int accent) {
        LinearLayout column = vertical();
        column.setGravity(Gravity.CENTER_HORIZONTAL);
        TextView number = label(value, 21f, palette.text, true);
        column.addView(number);
        TextView captionView = label(caption, 9f, accent, true);
        captionView.setLetterSpacing(0.08f);
        captionView.setAllCaps(true);
        column.addView(captionView, match(SiTokens.SPACE_XS));
        return column;
    }

    /** Explicit empty state: icon-free, honest copy, optional action. */
    public LinearLayout emptyState(String headline, String detail) {
        LinearLayout state = vertical();
        state.setGravity(Gravity.CENTER);
        state.setPadding(dp(SiTokens.SPACE_L), dp(SiTokens.SPACE_XXL), dp(SiTokens.SPACE_L), dp(SiTokens.SPACE_XXL));
        TextView heading = label(headline, SiTokens.TYPE_HEADLINE, palette.text, true);
        heading.setGravity(Gravity.CENTER);
        state.addView(heading);
        TextView explanation = label(detail, SiTokens.TYPE_LABEL, palette.muted, false);
        explanation.setGravity(Gravity.CENTER);
        state.addView(explanation, match(SiTokens.SPACE_S));
        return state;
    }

    // ---- layout helpers ----

    public LinearLayout.LayoutParams match() {
        return match(0);
    }

    public LinearLayout.LayoutParams match(int topDp) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        params.topMargin = dp(topDp);
        return params;
    }

    public LinearLayout.LayoutParams weight() {
        return weight(0);
    }

    public LinearLayout.LayoutParams weight(int leftDp) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        params.leftMargin = dp(leftDp);
        return params;
    }

    public int dp(int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    // ---- color rules ----

    /** Text color that stays legible on an accent-filled control. */
    public int onColor(int accent) {
        return SiTokens.contrastRatio(accent, palette.background) >= 4.5d
                ? palette.background
                : palette.text;
    }

    public int accent() {
        return palette.accent;
    }

    public int accentAlt() {
        return palette.accentAlt;
    }

    public int ok() {
        return palette.ok;
    }

    public int warn() {
        return palette.warn;
    }

    public int danger() {
        return palette.danger;
    }

    public int text() {
        return palette.text;
    }

    public int muted() {
        return palette.muted;
    }

    public int surface() {
        return palette.surface;
    }

    public int background() {
        return palette.background;
    }

    // ---- drawables ----

    public GradientDrawable rounded(int fill, int stroke, int radiusDp) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(fill);
        drawable.setCornerRadius(dp(radiusDp));
        drawable.setStroke(dp(1), stroke);
        return drawable;
    }

    public RippleDrawable ripple(int fill, int stroke, int radiusDp) {
        return new RippleDrawable(
                ColorStateList.valueOf(Color.argb(55, 255, 255, 255)),
                rounded(fill, stroke, radiusDp),
                null);
    }
}
