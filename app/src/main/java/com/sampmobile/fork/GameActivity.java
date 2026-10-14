package com.sampmobile.fork;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Color;
import android.os.Bundle;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;

/**
 * Game bootstrap activity.
 *
 * Flow: load native libraries -> GameDataManager downloads/verifies the game
 * data (same source the SA-MP Mobile Launcher uses) -> write SAMP/settings.ini
 * -> initializeSAMP(storagePath) hands control to the native client, which
 * hooks the game engine and shows the in-game server browser.
 *
 * NOTE: the proprietary game engine (libGTASA.so) is not part of this
 * repository. If it is not present in the process, the client logs an error
 * and this activity explains the situation instead of showing a black screen.
 */
public class GameActivity extends Activity {
    private static final String TAG = "GameActivity";

    private TextView statusView;
    private boolean enginePresent = false;

    /** Hands control to the native SAMP client (see main.cpp). */
    private native void initializeSAMP(String storagePath);

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        applyImmersive(true);
        buildStatusUi("Loading native client...");

        // 1. Game engine first (optional), then the SAMP client - same load
        //    order the reference launcher uses.
        try {
            System.loadLibrary("GTASA");
            enginePresent = true;
            Log.i(TAG, "Game engine library loaded");
        } catch (UnsatisfiedLinkError e) {
            Log.w(TAG, "Game engine (libGTASA.so) not present: " + e.getMessage());
        }
        try {
            System.loadLibrary("samp");
        } catch (UnsatisfiedLinkError e) {
            Log.e(TAG, "Failed to load samp client", e);
            showFatal("Could not load the SA-MP client library:\n\n" + e.getMessage());
            return;
        }

        // 2. Game data (downloaded once from the shared data source).
        setStatus("Checking game data...");
        GameDataManager.checkAndPrepare(this, this::onDataReady);
    }

    private void onDataReady() {
        if (isFinishing()) return;
        try {
            File root = getExternalFilesDir(null);
            if (root == null) throw new Exception("Storage unavailable.");
            String storagePath = root.getAbsolutePath();
            if (!storagePath.endsWith("/")) storagePath += "/";

            writeSettingsIni(new File(root, "SAMP/settings.ini"));

            if (!enginePresent) {
                showEngineMissing();
                return;
            }
            setStatus("Starting SA-MP...");
            initializeSAMP(storagePath);
        } catch (Exception e) {
            Log.e(TAG, "Game start failed", e);
            showFatal("Failed to start the game:\n\n" + e.getMessage());
        }
    }

    /** Writes the settings file the native client requires (it aborts without it). */
    private void writeSettingsIni(File iniFile) throws Exception {
        File parent = iniFile.getParentFile();
        if (parent != null && !parent.exists()) parent.mkdirs();

        String nickname = getIntent().getStringExtra("nickname");
        if (nickname == null || nickname.isEmpty()) nickname = "Player";
        if (nickname.length() > 24) nickname = nickname.substring(0, 24);
        String ip = getIntent().getStringExtra("ip");
        if (ip == null || ip.isEmpty()) ip = "127.0.0.1";
        int port = getIntent().getIntExtra("port", 7777);

        StringBuilder sb = new StringBuilder();
        sb.append("[client]\n");
        sb.append("name=").append(nickname).append('\n');
        sb.append("host=").append(ip).append('\n');
        sb.append("port=").append(port).append('\n');
        sb.append("password=\n");
        // browser=true (default): show the in-game server browser instead of
        // auto-connecting to the server above.
        sb.append("\n[debug]\n");
        sb.append("debug=true\n");
        try (OutputStream os = new FileOutputStream(iniFile)) {
            os.write(sb.toString().getBytes("UTF-8"));
        }
        Log.i(TAG, "settings.ini written for " + ip + ":" + port);
    }

    private void showEngineMissing() {
        if (isFinishing()) return;
        applyImmersive(false);
        new AlertDialog.Builder(this)
                .setTitle("Game engine not found")
                .setMessage("The SA-MP client is ready and the game data is installed, but the " +
                        "game engine library (libGTASA.so) is not present in this build.\n\n" +
                        "This repository ships the open-source client only; the proprietary " +
                        "game engine is not included. Add the engine library to build a " +
                        "fully playable APK.")
                .setCancelable(false)
                .setPositiveButton("BACK", (d, w) -> finish())
                .show();
    }

    private void showFatal(String message) {
        if (isFinishing()) return;
        applyImmersive(false);
        new AlertDialog.Builder(this)
                .setTitle("Startup failed")
                .setMessage(message)
                .setCancelable(false)
                .setPositiveButton("BACK", (d, w) -> finish())
                .show();
    }

    // ---------------------------------------------------------- UI helpers --
    private void buildStatusUi(String initial) {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        root.setBackgroundColor(Color.rgb(18, 21, 31));
        ProgressBar bar = new ProgressBar(this);
        statusView = new TextView(this);
        statusView.setText(initial);
        statusView.setTextColor(Color.rgb(245, 247, 255));
        statusView.setTextSize(16);
        statusView.setGravity(Gravity.CENTER);
        root.addView(bar);
        View gap = new View(this);
        gap.setLayoutParams(new LinearLayout.LayoutParams(1, dp(16)));
        root.addView(gap);
        root.addView(statusView,
                new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT));
        setContentView(root);
    }

    private void setStatus(String s) {
        runOnUiThread(() -> { if (statusView != null) statusView.setText(s); });
    }

    private void applyImmersive(boolean on) {
        try {
            View decor = getWindow().getDecorView();
            if (on) {
                int flags = View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY;
                decor.setSystemUiVisibility(flags);
            } else {
                decor.setSystemUiVisibility(View.SYSTEM_UI_FLAG_VISIBLE);
            }
        } catch (Exception ignored) {}
    }

    private int dp(int n) {
        return (int) (n * getResources().getDisplayMetrics().density + 0.5f);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) applyImmersive(true);
    }
}
