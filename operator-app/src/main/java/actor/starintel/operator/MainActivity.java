package actor.starintel.operator;

import actor.starintel.android.StarIntelAndroidContract;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

public final class MainActivity extends Activity {
    private static final int BG = Color.rgb(4, 7, 9);
    private static final int PANEL = Color.rgb(12, 18, 21);
    private static final int CYAN = Color.rgb(85, 235, 255);
    private static final int MUTED = Color.rgb(145, 160, 166);

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);

        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(18), dp(24), dp(18), dp(32));
        body.setBackgroundColor(BG);

        TextView eyebrow = text("STARINTEL // TACTICAL OPS", 12, CYAN, true);
        eyebrow.setLetterSpacing(.12f);
        body.addView(eyebrow, matchWrap());

        body.addView(text("Operator", 34, Color.WHITE, true), matchWrap(2));
        body.addView(
                text(
                        "ATAK-style command shell. Keep the map, collection runtime, Quasar, and watch companion independently installable.",
                        14,
                        MUTED,
                        false),
                matchWrap(8));

        body.addView(section("PRIMARY SURFACES"), matchWrap(22));
        body.addView(action("MAPS", "Geo documents · tactical projection", () -> openMaps()), matchWrap(8));
        body.addView(action("COLLECTOR", "Share intake · visible foreground session", () -> launchPackage(StarIntelAndroidContract.PACKAGE_COLLECTOR)), matchWrap(8));

        body.addView(section("STARINTEL"), matchWrap(22));
        body.addView(action("QUASAR", "Documents · actors · targets · graph", () -> launchPackage(StarIntelAndroidContract.PACKAGE_QUASAR)), matchWrap(8));
        body.addView(action("COMPANION", "Wear pairing · packages · update channel", () -> launchPackage(StarIntelAndroidContract.PACKAGE_COMPANION)), matchWrap(8));

        body.addView(section("BOOTSTRAP STATUS"), matchWrap(22));
        body.addView(
                text(
                        "v" + StarIntelAndroidContract.VERSION + " inter-app contract · no synthetic map state · server protocols remain authoritative",
                        12,
                        MUTED,
                        false),
                matchWrap(8));

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.addView(body);
        setContentView(scroll);
    }

    private void openMaps() {
        Intent intent = new Intent(StarIntelAndroidContract.ACTION_OPEN_MAP);
        intent.setPackage(StarIntelAndroidContract.PACKAGE_MAPS);
        try {
            startActivity(intent);
        } catch (ActivityNotFoundException missing) {
            missing("Maps");
        }
    }

    private void launchPackage(String packageName) {
        Intent intent = getPackageManager().getLaunchIntentForPackage(packageName);
        if (intent == null) {
            missing(packageName);
            return;
        }
        startActivity(intent);
    }

    private void missing(String target) {
        Toast.makeText(this, target + " is not installed", Toast.LENGTH_SHORT).show();
    }

    private TextView section(String value) {
        TextView view = text(value, 11, CYAN, true);
        view.setLetterSpacing(.1f);
        return view;
    }

    private LinearLayout action(String title, String detail, Runnable onClick) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(16), dp(14), dp(16), dp(14));
        card.setBackgroundColor(PANEL);
        card.setClickable(true);
        card.setFocusable(true);
        card.setOnClickListener(v -> onClick.run());

        TextView heading = text(title, 18, Color.WHITE, true);
        card.addView(heading, matchWrap());
        card.addView(text(detail, 13, MUTED, false), matchWrap(3));

        Button open = new Button(this);
        open.setText("OPEN");
        open.setAllCaps(true);
        open.setOnClickListener(v -> onClick.run());
        card.addView(open, matchWrap(10));
        return card;
    }

    private TextView text(String value, float size, int color, boolean bold) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        view.setGravity(Gravity.START);
        if (bold) view.setTypeface(Typeface.DEFAULT_BOLD);
        return view;
    }

    private LinearLayout.LayoutParams matchWrap() {
        return matchWrap(0);
    }

    private LinearLayout.LayoutParams matchWrap(int top) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        params.topMargin = dp(top);
        return params;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
