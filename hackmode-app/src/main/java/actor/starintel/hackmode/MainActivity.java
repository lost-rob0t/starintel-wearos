package actor.starintel.hackmode;

import actor.starintel.android.config.StarIntelSharedConfig;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

public final class MainActivity extends Activity {
    private static final int REQUEST_TERMUX = 17;

    private StarIntelSharedConfig config;
    private PendingAction pending = PendingAction.NONE;
    private TextView status;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        config = new StarIntelSharedConfig(this);

        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(24), dp(20), dp(32));
        root.setBackgroundColor(Color.rgb(2, 6, 4));

        TextView eyebrow = text("STARINTEL // HACKMODE", 12, Color.rgb(84, 255, 158), true);
        eyebrow.setLetterSpacing(.12f);
        root.addView(eyebrow, matchWrap());
        root.addView(text("Field Terminal", 30, Color.WHITE, true), matchWrap(4));
        root.addView(
                text(
                        "Full Hackmode runs in Termux. The Android APK is the signed control surface: terminal launcher, documentation, shared StarIntel config, and typed operation service.",
                        14,
                        Color.rgb(164, 183, 171),
                        false),
                matchWrap(8));

        status = text("", 13, Color.rgb(164, 183, 171), false);
        root.addView(status, matchWrap(20));

        Button shell = button("OPEN HACKMODE TERMINAL");
        shell.setOnClickListener(v -> launch(PendingAction.HACKMODE));
        root.addView(shell, matchWrap(16));

        Button kali = button("OPEN KALI / NETHUNTER TERMINAL");
        kali.setOnClickListener(v -> launch(PendingAction.KALI));
        root.addView(kali, matchWrap(8));

        Button termux = button("OPEN TERMUX");
        termux.setOnClickListener(
                v -> runVisible(() -> TermuxBridge.openTermux(this)));
        root.addView(termux, matchWrap(8));

        root.addView(text("Bridge setup", 19, Color.WHITE, true), matchWrap(24));
        root.addView(
                text(
                        "1. Install Termux from a trusted source.\n"
                                + "2. Grant this app Termux's RUN_COMMAND permission when prompted.\n"
                                + "3. In Termux, set allow-external-apps=true in ~/.termux/termux.properties.\n"
                                + "4. Install/build Hackmode so the configured hm command is on PATH.\n"
                                + "5. Optional Kali profile: install NetHunter Rootless or change hackmode.kali.command in shared StarIntel config.",
                        13,
                        Color.rgb(164, 183, 171),
                        false),
                matchWrap(8));

        root.addView(text("Actor service", 19, Color.WHITE, true), matchWrap(24));
        root.addView(
                text(
                        "Other StarIntel APKs may send signature-authorized typed operations to actor.starintel.action.HACKMODE_OPERATION. The service passes bounded JSON on stdin to hm android-bridge. It never accepts a shell command from another app.",
                        13,
                        Color.rgb(164, 183, 171),
                        false),
                matchWrap(8));

        root.addView(text("Shared config", 19, Color.WHITE, true), matchWrap(24));
        root.addView(
                text(
                        "Shared: server URL, map endpoints/profile, Prolog-RLM endpoint, Hackmode executable profile, default collector dataset/ruleset. Credentials remain app-local/Keystore-backed.",
                        13,
                        Color.rgb(164, 183, 171),
                        false),
                matchWrap(8));

        scroll.addView(root);
        setContentView(scroll);
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
    }

    private void launch(PendingAction action) {
        if (!TermuxBridge.installed(this)) {
            toast("Termux is not installed");
            return;
        }
        if (!TermuxBridge.allowed(this)) {
            pending = action;
            requestPermissions(new String[] {TermuxBridge.TERMUX_PERMISSION}, REQUEST_TERMUX);
            return;
        }
        runAction(action);
    }

    @Override
    public void onRequestPermissionsResult(
            int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQUEST_TERMUX) return;
        PendingAction action = pending;
        pending = PendingAction.NONE;
        if (grantResults.length == 0 || grantResults[0] != PackageManager.PERMISSION_GRANTED) {
            toast("Termux RUN_COMMAND permission denied");
            refresh();
            return;
        }
        runAction(action);
    }

    private void runAction(PendingAction action) {
        runVisible(
                () -> {
                    if (action == PendingAction.HACKMODE) {
                        TermuxBridge.launchHackmode(
                                this,
                                config.get(StarIntelSharedConfig.KEY_HACKMODE_COMMAND, "hm"));
                    } else if (action == PendingAction.KALI) {
                        TermuxBridge.launchKali(
                                this,
                                config.get(StarIntelSharedConfig.KEY_HACKMODE_KALI_COMMAND, "nethunter"));
                    }
                });
    }

    private void refresh() {
        boolean termux = TermuxBridge.installed(this);
        boolean permission = termux && TermuxBridge.allowed(this);
        String server = config.get(StarIntelSharedConfig.KEY_SERVER_URL, "");
        String prolog = config.get(StarIntelSharedConfig.KEY_PROLOG_RLM_ENDPOINT, "http://127.0.0.1:18765");
        status.setText(
                "TERMUX · "
                        + (termux ? "INSTALLED" : "MISSING")
                        + "    RUN_COMMAND · "
                        + (permission ? "GRANTED" : "NOT GRANTED")
                        + "\nSTAR SERVER · "
                        + (server.isEmpty() ? "UNSET" : server)
                        + "\nPROLOG-RLM · "
                        + prolog);
    }

    private void runVisible(Runnable operation) {
        try {
            operation.run();
        } catch (RuntimeException error) {
            toast(error.getMessage() == null ? "Operation failed" : error.getMessage());
        }
        refresh();
    }

    private Button button(String label) {
        Button button = new Button(this);
        button.setText(label);
        return button;
    }

    private TextView text(String value, float size, int color, boolean bold) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        if (bold) view.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        else view.setTypeface(Typeface.MONOSPACE);
        return view;
    }

    private LinearLayout.LayoutParams matchWrap() {
        return matchWrap(0);
    }

    private LinearLayout.LayoutParams matchWrap(int top) {
        LinearLayout.LayoutParams params =
                new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT);
        params.topMargin = dp(top);
        return params;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
    }

    private enum PendingAction {
        NONE,
        HACKMODE,
        KALI
    }
}
