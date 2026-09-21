package actor.starintel.hackmode;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;

final class TermuxBridge {
    static final String TERMUX_PACKAGE = "com.termux";
    static final String TERMUX_PERMISSION = "com.termux.permission.RUN_COMMAND";

    private static final String ACTION_RUN_COMMAND = "com.termux.RUN_COMMAND";
    private static final String SERVICE = "com.termux.app.RunCommandService";
    private static final String BASH = "/data/data/com.termux/files/usr/bin/bash";
    private static final String HOME = "/data/data/com.termux/files/home";

    private static final String EXTRA_COMMAND_PATH = "com.termux.RUN_COMMAND_PATH";
    private static final String EXTRA_ARGUMENTS = "com.termux.RUN_COMMAND_ARGUMENTS";
    private static final String EXTRA_WORKDIR = "com.termux.RUN_COMMAND_WORKDIR";
    private static final String EXTRA_BACKGROUND = "com.termux.RUN_COMMAND_BACKGROUND";
    private static final String EXTRA_STDIN = "com.termux.RUN_COMMAND_STDIN";
    private static final String EXTRA_COMMAND_LABEL = "com.termux.RUN_COMMAND_COMMAND_LABEL";
    private static final String EXTRA_COMMAND_DESCRIPTION = "com.termux.RUN_COMMAND_COMMAND_DESCRIPTION";

    private TermuxBridge() {}

    static boolean installed(Context context) {
        try {
            context.getPackageManager().getPackageInfo(TERMUX_PACKAGE, 0);
            return true;
        } catch (PackageManager.NameNotFoundException missing) {
            return false;
        }
    }

    static boolean allowed(Context context) {
        return context.checkSelfPermission(TERMUX_PERMISSION) == PackageManager.PERMISSION_GRANTED;
    }

    static void launchHackmode(Context context, String configuredCommand) {
        String command = safeCommand(configuredCommand, "hm");
        run(
                context,
                "exec " + command + " expert",
                null,
                false,
                "Hackmode",
                "Interactive Hackmode expert terminal");
    }

    static void launchKali(Context context, String configuredCommand) {
        String command = safeCommand(configuredCommand, "nethunter");
        run(
                context,
                "exec " + command,
                null,
                false,
                "Hackmode · Kali",
                "Rootless Kali/NetHunter terminal profile");
    }

    static void submitOperation(Context context, String configuredCommand, String operationJson) {
        String command = safeCommand(configuredCommand, "hm");
        if (operationJson == null || operationJson.length() > 256 * 1024) {
            throw new IllegalArgumentException("Hackmode operation exceeds 256 KiB");
        }
        run(
                context,
                "exec " + command + " android-bridge",
                operationJson,
                true,
                "Hackmode actor operation",
                "Typed StarIntel Android operation");
    }

    static void openTermux(Context context) {
        Intent launch = context.getPackageManager().getLaunchIntentForPackage(TERMUX_PACKAGE);
        if (launch == null) throw new IllegalStateException("Termux is not installed");
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        context.startActivity(launch);
    }

    private static void run(
            Context context,
            String shellCommand,
            String stdin,
            boolean background,
            String label,
            String description) {
        if (!installed(context)) throw new IllegalStateException("Termux is not installed");
        if (!allowed(context)) throw new SecurityException("Termux RUN_COMMAND permission is not granted");

        Intent intent = new Intent(ACTION_RUN_COMMAND);
        intent.setClassName(TERMUX_PACKAGE, SERVICE);
        intent.putExtra(EXTRA_COMMAND_PATH, BASH);
        intent.putExtra(EXTRA_ARGUMENTS, new String[] {"-lc", shellCommand});
        intent.putExtra(EXTRA_WORKDIR, HOME);
        intent.putExtra(EXTRA_BACKGROUND, background);
        intent.putExtra(EXTRA_COMMAND_LABEL, label);
        intent.putExtra(EXTRA_COMMAND_DESCRIPTION, description);
        if (stdin != null) intent.putExtra(EXTRA_STDIN, stdin);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent);
        } else {
            context.startService(intent);
        }
    }

    private static String safeCommand(String value, String fallback) {
        String command = value == null || value.trim().isEmpty() ? fallback : value.trim();
        if (!command.matches("[A-Za-z0-9_./-]{1,256}")) {
            throw new IllegalArgumentException("Configured command must be one executable token");
        }
        return command;
    }
}
