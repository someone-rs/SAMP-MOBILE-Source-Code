package com.sampmobile.fork;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
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
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Enumeration;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Game bootstrap activity.
 *
 * Flow:
 *   1. Load the SA-MP client (libsamp.so, bundled).
 *   2. Resolve the proprietary game engine (libGTASA.so):
 *        a. bundled in the APK (loadLibrary), else
 *        b. previously extracted to the app's files dir, else
 *        c. ask the user to pick their GTA:SA APK -> extract
 *           lib/<abi>/libGTASA.so from it (APK is just a zip).
 *   3. GameDataManager downloads/verifies the game data (same source the
 *      SA-MP Mobile Launcher uses).
 *   4. Write SAMP/settings.ini, then initializeSAMP(storagePath) hands
 *      control to the native client, which hooks the game engine and shows
 *      the in-game server browser.
 *
 * NOTE: the proprietary game engine (libGTASA.so) is not part of this
 * repository and cannot be distributed with it. The app extracts it from
 * the user's own GTA:SA APK on first run instead.
 */
public class GameActivity extends Activity {
    private static final String TAG = "GameActivity";
    private static final int REQ_PICK_APK = 4101;
    private static final String ENGINE_LIB = "libGTASA.so";

    private TextView statusView;
    private boolean enginePresent = false;

    /** Hands control to the native SAMP client (see main.cpp). */
    private native void initializeSAMP(String storagePath);

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        applyImmersive(true);
        buildStatusUi("Loading native client...");

        try {
            System.loadLibrary("samp");
        } catch (UnsatisfiedLinkError e) {
            Log.e(TAG, "Failed to load samp client", e);
            showFatal("Could not load the SA-MP client library:\n\n" + e.getMessage());
            return;
        }

        resolveEngine();
    }

    // ------------------------------------------------ engine resolution ---

    /** Step 2 of the flow: find libGTASA.so or ask the user for their GTA:SA APK. */
    private void resolveEngine() {
        // a. Bundled with the APK (whoever builds with the engine in jniLibs).
        try {
            System.loadLibrary("GTASA");
            enginePresent = true;
            Log.i(TAG, "Game engine library loaded (bundled)");
            proceedToData();
            return;
        } catch (UnsatisfiedLinkError ignored) {
        }

        // b. Previously extracted to our files dir.
        File extracted = engineFile();
        if (extracted.exists()) {
            try {
                System.load(extracted.getAbsolutePath());
                enginePresent = true;
                Log.i(TAG, "Game engine loaded from " + extracted.getAbsolutePath());
                proceedToData();
                return;
            } catch (UnsatisfiedLinkError e) {
                Log.w(TAG, "Saved engine failed to load, re-extracting", e);
                extracted.delete();
            }
        }

        // c. Ask the user to pick their GTA:SA APK.
        askForEngineApk();
    }

    private File engineFile() {
        File root = getExternalFilesDir(null);
        return new File(root, ENGINE_LIB);
    }

    private void askForEngineApk() {
        if (isFinishing()) return;
        applyImmersive(false);
        setStatus("Game engine needed.");
        new AlertDialog.Builder(this)
                .setTitle("Game engine not found")
                .setMessage("The SA-MP client needs the GTA:SA game engine (libGTASA.so), " +
                        "which is not bundled with this app.\n\n" +
                        "Please select your GTA:SA APK file — the engine will be " +
                        "extracted from it automatically (one time only).")
                .setCancelable(false)
                .setPositiveButton("SELECT APK", (d, w) -> openApkPicker())
                .setNegativeButton("BACK", (d, w) -> finish())
                .show();
    }

    private void openApkPicker() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("application/vnd.android.package-archive");
        try {
            startActivityForResult(i, REQ_PICK_APK);
        } catch (Exception e) {
            // Some file managers dislike the APK mime type; fall back to any file.
            Intent any = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            any.addCategory(Intent.CATEGORY_OPENABLE);
            any.setType("*/*");
            startActivityForResult(any, REQ_PICK_APK);
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_PICK_APK) return;
        if (resultCode != RESULT_OK || data == null || data.getData() == null) {
            askForEngineApk();
            return;
        }
        Uri uri = data.getData();
        setStatus("Extracting game engine...");
        applyImmersive(true);
        new Thread(() -> extractEngineFromApk(uri)).start();
    }

    /** Copies the picked APK to a temp file, extracts lib/<abi>/libGTASA.so. */
    private void extractEngineFromApk(Uri uri) {
        File tmpApk = null;
        try {
            tmpApk = File.createTempFile("gtasa", ".apk", getCacheDir());
            try (InputStream in = getContentResolver().openInputStream(uri);
                 OutputStream out = new FileOutputStream(tmpApk)) {
                if (in == null) throw new Exception("Cannot read the selected file.");
                byte[] buf = new byte[65536];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            }

            String entryName = findEngineEntry(tmpApk);
            if (entryName == null) {
                throw new Exception("No " + ENGINE_LIB + " found for this device's CPU " +
                        "inside the selected APK. Make sure it is a GTA:SA APK.");
            }

            File dest = engineFile();
            try (ZipFile zf = new ZipFile(tmpApk);
                 InputStream in = zf.getInputStream(zf.getEntry(entryName));
                 OutputStream out = new FileOutputStream(dest)) {
                byte[] buf = new byte[65536];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            }

            System.load(dest.getAbsolutePath());
            enginePresent = true;
            Log.i(TAG, "Engine extracted from APK entry " + entryName);
            runOnUiThread(this::proceedToData);
        } catch (Exception e) {
            Log.e(TAG, "Engine extraction failed", e);
            final String msg = e.getMessage();
            runOnUiThread(() -> {
                applyImmersive(false);
                new AlertDialog.Builder(GameActivity.this)
                        .setTitle("Could not use that APK")
                        .setMessage(msg + "\n\nPlease try another GTA:SA APK.")
                        .setCancelable(false)
                        .setPositiveButton("SELECT APK", (d, w) -> openApkPicker())
                        .setNegativeButton("BACK", (d, w) -> finish())
                        .show();
            });
        } finally {
            if (tmpApk != null) tmpApk.delete();
        }
    }

    /** Finds lib/<abi>/libGTASA.so inside the APK, preferring this device's ABIs. */
    private String findEngineEntry(File apk) throws Exception {
        String[] abis = Build.SUPPORTED_ABIS;
        try (ZipFile zf = new ZipFile(apk)) {
            // Preferred: exact ABI match, in device preference order.
            for (String abi : abis) {
                String want = "lib/" + abi + "/" + ENGINE_LIB;
                if (zf.getEntry(want) != null) return want;
            }
            // Fallback: any libGTASA.so in the APK.
            Enumeration<? extends ZipEntry> en = zf.entries();
            while (en.hasMoreElements()) {
                String name = en.nextElement().getName();
                if (name.endsWith("/" + ENGINE_LIB)) return name;
            }
        }
        return null;
    }

    // ------------------------------------------------------------ data -------

    private void proceedToData() {
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

    // ------------------------------------------------------ UI helpers --------
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
