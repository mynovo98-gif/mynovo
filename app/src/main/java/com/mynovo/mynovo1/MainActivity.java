package com.mynovo.mynovo1;

import android.app.AppOpsManager;
import android.app.Dialog; // [แก้] เพิ่ม import
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.InputDevice;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.GridView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.cardview.widget.CardView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import rikka.shizuku.Shizuku;

public class MainActivity extends AppCompatActivity {

    private static final String PREF_NAME = "MynovoPrefs";
    private static final String KEY_SAVED_GAMES_JSON = "savedGamesJson";
    private static final String KEY_SAVED_PACKAGES_SET = "savedPackagesSet";

    private List<AppInfo> allInstalledApps = new ArrayList<>();
    private List<AppInfo> filteredApps = new ArrayList<>();
    private List<GameItem> savedGamesList = new ArrayList<>();

    private LinearLayout layoutGamesContainer;
    private LinearLayout layoutPeripheralsContainer;
    private LinearLayout btnAddApp;
    private LinearLayout btnCardActivation;
    private TextView tvActivationStatus;

    private LinearLayout navStartGame, navAccount, navActivation, navSettings;
    private FrameLayout fragmentContainer;

    private Dialog currentDialog; // [แก้] เดิมเป็น AlertDialog
    private String currentDialogType = "";

    // [ใหม่] wizard Wireless activation (ใช้ WirelessAdbWizardManager ภายใน)
    private WirelessActivationDialog wirelessDialog;

    private final Shizuku.OnBinderReceivedListener BINDER_RECEIVED_LISTENER = new Shizuku.OnBinderReceivedListener() {
        @Override
        public void onBinderReceived() {
            runOnUiThread(() -> {
                checkRealActivationStatus();
                refreshCurrentDialog();
            });
        }
    };

    private final Shizuku.OnBinderDeadListener BINDER_DEAD_LISTENER = new Shizuku.OnBinderDeadListener() {
        @Override
        public void onBinderDead() {
            runOnUiThread(() -> {
                checkRealActivationStatus();
                refreshCurrentDialog();
            });
        }
    };

    private final Shizuku.OnRequestPermissionResultListener REQUEST_PERMISSION_RESULT_LISTENER = new Shizuku.OnRequestPermissionResultListener() {
        @Override
        public void onRequestPermissionResult(int requestCode, int grantResult) {
            runOnUiThread(() -> {
                if (grantResult == PackageManager.PERMISSION_GRANTED) TouchSimulator.setShizukuEnabled(MainActivity.this, true);
                checkRealActivationStatus();
                refreshCurrentDialog();
                if (grantResult == PackageManager.PERMISSION_GRANTED) {
                    Toast.makeText(MainActivity.this, "✅ Shizuku Connected Successfully!", Toast.LENGTH_LONG).show();
                } else {
                    Toast.makeText(MainActivity.this, "❌ Shizuku Permission Denied", Toast.LENGTH_SHORT).show();
                }
            });
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // [ใหม่] ให้ client ของ shell server (Quick Activation) รู้จัก context
        TouchSimulator.setContext(this);

        try {
            Shizuku.addBinderReceivedListenerSticky(BINDER_RECEIVED_LISTENER);
            Shizuku.addBinderDeadListener(BINDER_DEAD_LISTENER);
            Shizuku.addRequestPermissionResultListener(REQUEST_PERMISSION_RESULT_LISTENER);
        } catch (Exception ignored) {}

        fragmentContainer = findViewById(R.id.fragmentContainer);
        navStartGame = findViewById(R.id.navStartGame);
        navAccount = findViewById(R.id.navAccount);
        navActivation = findViewById(R.id.navActivation);
        navSettings = findViewById(R.id.navSettings);

        loadStartGameView();

        navStartGame.setOnClickListener(v -> loadStartGameView());
        navAccount.setOnClickListener(v -> loadAccountView());
        navActivation.setOnClickListener(v -> handleActivationClick());
        navSettings.setOnClickListener(v -> loadSettingsView());
    }

    @Override
    protected void onPause() {
        super.onPause();
    }

    @Override
    protected void onStop() {
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        // [ใหม่] ปิด wizard ถ้ายังเปิดอยู่ (จะหยุด mDNS + ล้าง notification ให้เอง)
        try {
            if (wirelessDialog != null && wirelessDialog.isShowing()) wirelessDialog.getDialog().dismiss();
        } catch (Exception ignored) {}
        try {
            Shizuku.removeBinderReceivedListener(BINDER_RECEIVED_LISTENER);
            Shizuku.removeBinderDeadListener(BINDER_DEAD_LISTENER);
            Shizuku.removeRequestPermissionResultListener(REQUEST_PERMISSION_RESULT_LISTENER);
        } catch (Exception ignored) {}
    }

    @Override
    protected void onResume() {
        super.onResume();
        checkRealActivationStatus();
        checkInputDevicesStatus();
        refreshCurrentDialog();

        // [ใหม่] ลองเชื่อม shell server เบื้องหลัง (เผื่อผู้ใช้เพิ่งรัน activate.sh แล้วสลับกลับมา) เสร็จแล้วอัปเดตสถานะ
        ShellServerClient.connectAsync(ok -> {
            checkRealActivationStatus();
            if (ok) refreshCurrentDialog();
        });
    }

    private void refreshCurrentDialog() {
        if (currentDialog != null && currentDialog.isShowing()) {
            // [แก้] Wireless wizard อัปเดตสถานะในตัวเอง ห้ามปิด-เปิดใหม่ ไม่งั้น flow pair/connect จะรีเซ็ต
            if ("WIRELESS".equals(currentDialogType)) {
                if (wirelessDialog != null) wirelessDialog.refresh();
                return;
            }
            currentDialog.dismiss();
            if ("SHIZUKU".equals(currentDialogType)) {
                showProShizukuActivationDialog();
            } else if ("PC".equals(currentDialogType)) {
                showPcActivationGuideDialog();
            }
        }
    }

    // [ใหม่] เชื่อม shell server (Quick Activation) อยู่หรือไม่
    private boolean isShellServerActive() {
        return ShellServerClient.isConnected();
    }

    private boolean isReallyActivated() {
        // [ใหม่] นับ shell server ด้วย
        if (isShellServerActive()) return true;
        // [ใหม่] ผู้ใช้กด Deactivate แล้ว ไม่นับ Shizuku (จนกว่าจะกด Activate ใหม่)
        if (!TouchSimulator.isShizukuEnabled()) return false;
        try {
            if (Shizuku.pingBinder()) {
                return Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED;
            }
        } catch (Exception ignored) {}
        return false;
    }

    private void checkRealActivationStatus() {
        boolean active = isReallyActivated();
        if (tvActivationStatus != null) {
            if (active) {
                if (isShellServerActive()) {
                    tvActivationStatus.setText("Status: Activated & Ready (Command Active)");
                } else {
                    tvActivationStatus.setText("Status: Activated & Ready (Shizuku Active)");
                }
                tvActivationStatus.setTextColor(Color.parseColor("#50FA7B"));
            } else {
                tvActivationStatus.setText("Status: Not Activated (Tap to Activate)");
                tvActivationStatus.setTextColor(Color.parseColor("#FF5555"));
            }
        }
    }

    private void loadStartGameView() {
        ScrollView scrollView = new ScrollView(this);
        scrollView.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        scrollView.setFillViewport(true);

        LinearLayout rootContent = new LinearLayout(this);
        rootContent.setOrientation(LinearLayout.VERTICAL);
        rootContent.setPadding(20, 20, 20, 20);

        // [ลบหัวข้อซ้ำออกเรียบร้อยแล้ว ให้แสดงผลแค่แถบ Toolbar ด้านบนสุดอันเดียว]

        btnCardActivation = new LinearLayout(this);
        btnCardActivation.setOrientation(LinearLayout.VERTICAL);
        btnCardActivation.setBackgroundColor(Color.parseColor("#1E2230"));
        btnCardActivation.setPadding(14, 14, 14, 14);
        LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cardParams.setMargins(0, 0, 0, 14);
        btnCardActivation.setLayoutParams(cardParams);
        btnCardActivation.setClickable(true);
        btnCardActivation.setFocusable(true);

        TextView tvActTitle = new TextView(this);
        tvActTitle.setText("🛡️ Activation Center");
        tvActTitle.setTextColor(Color.WHITE);
        tvActTitle.setTextSize(13f);
        tvActTitle.setTypeface(null, android.graphics.Typeface.BOLD);
        btnCardActivation.addView(tvActTitle);

        tvActivationStatus = new TextView(this);
        tvActivationStatus.setText("Status: Checking...");
        tvActivationStatus.setTextSize(11f);
        tvActivationStatus.setPadding(0, 6, 0, 0);
        btnCardActivation.addView(tvActivationStatus);

        btnCardActivation.setOnClickListener(v -> handleActivationClick());
        rootContent.addView(btnCardActivation);

        LinearLayout peripheralCard = new LinearLayout(this);
        peripheralCard.setOrientation(LinearLayout.VERTICAL);
        peripheralCard.setBackgroundColor(Color.parseColor("#1E2230"));
        peripheralCard.setPadding(16, 16, 16, 16);
        peripheralCard.setLayoutParams(cardParams);

        TextView tvPeriTitle = new TextView(this);
        tvPeriTitle.setText("Available peripherals");
        tvPeriTitle.setTextColor(Color.parseColor("#8BE9FD"));
        tvPeriTitle.setTextSize(14f);
        tvPeriTitle.setTypeface(null, android.graphics.Typeface.BOLD);
        tvPeriTitle.setPadding(0, 0, 0, 10);
        peripheralCard.addView(tvPeriTitle);

        layoutPeripheralsContainer = new LinearLayout(this);
        layoutPeripheralsContainer.setOrientation(LinearLayout.VERTICAL);
        peripheralCard.addView(layoutPeripheralsContainer);
        rootContent.addView(peripheralCard);

        TextView tvMyGameHeader = new TextView(this);
        tvMyGameHeader.setText("My game");
        tvMyGameHeader.setTextColor(Color.parseColor("#8BE9FD"));
        tvMyGameHeader.setTextSize(14f);
        tvMyGameHeader.setTypeface(null, android.graphics.Typeface.BOLD);
        tvMyGameHeader.setPadding(4, 4, 0, 8);
        rootContent.addView(tvMyGameHeader);

        btnAddApp = new LinearLayout(this);
        btnAddApp.setOrientation(LinearLayout.HORIZONTAL);
        btnAddApp.setGravity(Gravity.CENTER);
        btnAddApp.setBackgroundColor(Color.parseColor("#21263d"));
        btnAddApp.setPadding(10, 10, 10, 10);
        btnAddApp.setLayoutParams(cardParams);
        btnAddApp.setClickable(true);
        btnAddApp.setFocusable(true);

        TextView tvAddText = new TextView(this);
        tvAddText.setText("＋ Add Game (Max 5)");
        tvAddText.setTextColor(Color.parseColor("#8BE9FD"));
        tvAddText.setTextSize(13f);
        tvAddText.setTypeface(null, android.graphics.Typeface.BOLD);
        btnAddApp.addView(tvAddText);

        btnAddApp.setOnClickListener(v -> {
            if (savedGamesList.size() >= 5) {
                Toast.makeText(this, "Maximum 5 games allowed", Toast.LENGTH_SHORT).show();
                return;
            }
            showAppSelectionDialog();
        });
        rootContent.addView(btnAddApp);

        layoutGamesContainer = new LinearLayout(this);
        layoutGamesContainer.setOrientation(LinearLayout.VERTICAL);
        rootContent.addView(layoutGamesContainer);

        scrollView.addView(rootContent);
        fragmentContainer.removeAllViews();
        fragmentContainer.addView(scrollView);

        loadSavedGames();
        checkRealActivationStatus();
        checkInputDevicesStatus();
    }

    private void loadAccountView() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setGravity(Gravity.CENTER);
        layout.setPadding(32, 32, 32, 32);
        TextView tv = new TextView(this);
        tv.setText("👤 Account & Cloud Sync\nComing Soon...");
        tv.setTextColor(Color.WHITE);
        tv.setTextSize(16f);
        tv.setGravity(Gravity.CENTER);
        layout.addView(tv);
        fragmentContainer.removeAllViews();
        fragmentContainer.addView(layout);
    }

    private void loadSettingsView() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setGravity(Gravity.CENTER);
        layout.setPadding(32, 32, 32, 32);
        TextView tv = new TextView(this);
        tv.setText("⚙️ Application Settings\nComing Soon...");
        tv.setTextColor(Color.WHITE);
        tv.setTextSize(16f);
        tv.setGravity(Gravity.CENTER);
        layout.addView(tv);
        fragmentContainer.removeAllViews();
        fragmentContainer.addView(layout);
    }

    private void handleActivationClick() {
        if (isReallyActivated()) {
            showDeactivationDialog();
        } else {
            showActivationMethodSelector();
        }
    }

    private void showActivationMethodSelector() {
        LinearLayout container = new LinearLayout(this);
        container.setOrientation(LinearLayout.VERTICAL);
        container.setPadding(32, 24, 32, 24);
        container.setBackgroundColor(Color.parseColor("#1E2230"));

        TextView tvTitle = new TextView(this);
        tvTitle.setText("Please select the activation method.");
        tvTitle.setTextColor(Color.parseColor("#8BE9FD"));
        tvTitle.setTextSize(15f);
        tvTitle.setTypeface(null, android.graphics.Typeface.BOLD);
        tvTitle.setPadding(0, 0, 0, 24);
        container.addView(tvTitle);

        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setView(container);
        AlertDialog dialog = builder.create();

        container.addView(createMethodButton("🛡️ Shizuku Activation", v -> {
            dialog.dismiss();
            showProShizukuActivationDialog();
        }));

        container.addView(createMethodButton("⚡ Wireless Activation (ADB)", v -> {
            dialog.dismiss();
            showWirelessActivationGuide();
        }));

        container.addView(createMethodButton("💻 Wired / Computer Activation", v -> {
            dialog.dismiss();
            showPcActivationGuideDialog();
        }));

        // [ใหม่] วิธีที่ 4: Quick Activation (Command) แบบ K2 Tap
        container.addView(createMethodButton("⌨️ Quick Activation (Command)", v -> {
            dialog.dismiss();
            ShellActivationDialog.show(MainActivity.this);
        }));

        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawable(new ColorDrawable(Color.parseColor("#1E2230")));
        }
        dialog.show();
    }

    private View createMethodButton(String text, View.OnClickListener listener) {
        TextView btn = new TextView(this);
        btn.setText(text);
        btn.setTextColor(Color.WHITE);
        btn.setTextSize(13f);
        btn.setTypeface(null, android.graphics.Typeface.BOLD);
        btn.setPadding(24, 20, 24, 20);

        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.setMargins(0, 8, 0, 8);
        btn.setLayoutParams(params);
        btn.setBackgroundColor(Color.parseColor("#282C34"));
        btn.setOnClickListener(listener);

        return btn;
    }

    private void showProShizukuActivationDialog() {
        boolean binderRunning = false;
        boolean hasPermission = false;
        try {
            binderRunning = Shizuku.pingBinder();
            if (binderRunning) {
                hasPermission = (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED);
            }
        } catch (Exception ignored) {}

        final boolean isBinderRunning = binderRunning;
        final boolean isHasPermission = hasPermission;

        LinearLayout container = new LinearLayout(this);
        container.setOrientation(LinearLayout.VERTICAL);
        container.setPadding(32, 24, 32, 24);
        container.setBackgroundColor(Color.parseColor("#1E2230"));

        TextView tvTitle = new TextView(this);
        tvTitle.setText("Shizuku Activation");
        tvTitle.setTextColor(Color.parseColor("#8BE9FD"));
        tvTitle.setTextSize(18f);
        tvTitle.setTypeface(null, android.graphics.Typeface.BOLD);
        tvTitle.setPadding(0, 0, 0, 24);
        container.addView(tvTitle);

        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setView(container);
        AlertDialog dialog = builder.create();

        container.addView(createProStepRow("1. Start Shizuku service", isBinderRunning, v -> {
            try {
                Intent intent = getPackageManager().getLaunchIntentForPackage("moe.shizuku.privileged.api");
                if (intent != null) {
                    startActivity(intent);
                    Toast.makeText(this, "👉 ไปเปิด Start ในแอป Shizuku แล้วกดกลับมาหน้านี้", Toast.LENGTH_LONG).show();
                } else {
                    Toast.makeText(this, "ไม่พบแอป Shizuku ในเครื่อง", Toast.LENGTH_SHORT).show();
                }
            } catch (Exception e) {
                Toast.makeText(this, "กรุณาเปิดแอป Shizuku ด้วยตัวเอง", Toast.LENGTH_SHORT).show();
            }
        }));

        container.addView(createProStepRow("2. Grant permission to use Shizuku", isHasPermission, v -> {
            try {
                if (Shizuku.pingBinder()) {
                    Shizuku.requestPermission(0);
                    Toast.makeText(this, "⚠️ กรุณากด 'อนุญาต (Allow)' ในหน้าต่าง Shizuku", Toast.LENGTH_LONG).show();
                } else {
                    Toast.makeText(this, "⚠️ กรุณาเปิด Shizuku Service ในข้อ 1 ก่อน", Toast.LENGTH_SHORT).show();
                }
            } catch (Exception e) {
                Toast.makeText(this, "เกิดข้อผิดพลาดในการขอสิทธิ์", Toast.LENGTH_SHORT).show();
            }
        }));

        LinearLayout row3 = new LinearLayout(this);
        row3.setOrientation(LinearLayout.HORIZONTAL);
        row3.setGravity(Gravity.CENTER_VERTICAL);
        row3.setPadding(0, 16, 0, 8);

        TextView tvText3 = new TextView(this);
        tvText3.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f));
        tvText3.setText("3. Tap to Activate");
        tvText3.setTextColor(Color.WHITE);
        tvText3.setTextSize(13f);
        row3.addView(tvText3);

        TextView btnActivate = new TextView(this);
        btnActivate.setText(isHasPermission ? "READY" : "ACTIVATE");
        btnActivate.setTextColor(Color.WHITE);
        btnActivate.setTextSize(12f);
        btnActivate.setTypeface(null, android.graphics.Typeface.BOLD);
        btnActivate.setPadding(28, 14, 28, 14);
        btnActivate.setBackgroundColor(Color.parseColor(isHasPermission ? "#50FA7B" : "#BD93F9"));

        btnActivate.setOnClickListener(v -> {
            try {
                boolean binderOK = Shizuku.pingBinder();
                boolean permOK = binderOK && (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED);

                if (permOK) {
                    TouchSimulator.setShizukuEnabled(this, true);
                    checkRealActivationStatus();
                    Toast.makeText(this, "✅ Activation Successful!", Toast.LENGTH_LONG).show();
                    dialog.dismiss();
                } else if (binderOK) {
                    Shizuku.requestPermission(0);
                    Toast.makeText(this, "⚠️ กรุณากด Allow ในหน้าต่าง Shizuku", Toast.LENGTH_LONG).show();
                } else {
                    Toast.makeText(this, "❌ กรุณาเปิด Shizuku Service ในข้อ 1 ก่อน", Toast.LENGTH_SHORT).show();
                }
            } catch (Exception e) {
                Toast.makeText(this, "❌ เกิดข้อผิดพลาด", Toast.LENGTH_SHORT).show();
            }
        });
        row3.addView(btnActivate);
        container.addView(row3);

        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawable(new ColorDrawable(Color.parseColor("#1E2230")));
        }

        currentDialog = dialog;
        currentDialogType = "SHIZUKU";
        dialog.show();
    }

    private View createProStepRow(String titleText, boolean isDone, View.OnClickListener listener) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, 12, 0, 12);

        TextView tvText = new TextView(this);
        tvText.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f));
        tvText.setText(titleText);
        tvText.setTextColor(Color.WHITE);
        tvText.setTextSize(13f);
        row.addView(tvText);

        if (isDone) {
            TextView tvCheck = new TextView(this);
            tvCheck.setText("✓");
            tvCheck.setTextColor(Color.parseColor("#50FA7B"));
            tvCheck.setTextSize(18f);
            tvCheck.setTypeface(null, android.graphics.Typeface.BOLD);
            tvCheck.setPadding(16, 0, 16, 0);
            row.addView(tvCheck);
        } else {
            TextView btnAction = new TextView(this);
            btnAction.setText("RUN / ALLOW");
            btnAction.setTextColor(Color.WHITE);
            btnAction.setTextSize(11f);
            btnAction.setTypeface(null, android.graphics.Typeface.BOLD);
            btnAction.setPadding(20, 10, 20, 10);
            btnAction.setBackgroundColor(Color.parseColor("#BD93F9"));
            btnAction.setOnClickListener(listener);
            row.addView(btnAction);
        }

        return row;
    }

    // [แก้ทั้งหมด] ใช้ WirelessActivationDialog (wizard 6 ขั้นตอน: Developer options -> USB debugging
    // -> Wireless debugging -> PAIR -> รอพอร์ตอัตโนมัติ -> ACTIVATE)
    private void showWirelessActivationGuide() {
        wirelessDialog = WirelessActivationDialog.show(this, () -> {
            checkRealActivationStatus();
            refreshCurrentDialog();
        });
        currentDialog = wirelessDialog.getDialog();
        currentDialogType = "WIRELESS";
    }

    private void showPcActivationGuideDialog() {
        boolean hasPermission = isReallyActivated();
        String statusCheck = hasPermission ? " [ ✅ Connected ]" : " [ ❌ Not Connected ]";

        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        TextView tvTitle = new TextView(this);
        tvTitle.setText("💻 Wired / Computer Activation");
        tvTitle.setTextColor(Color.parseColor("#8BE9FD"));
        tvTitle.setTextSize(14f);
        tvTitle.setTypeface(null, android.graphics.Typeface.BOLD);
        tvTitle.setPadding(32, 32, 32, 16);
        builder.setCustomTitle(tvTitle);

        builder.setMessage("1. Connect device via USB\n2. Enable USB Debugging\n3. Run PC activation command\n\nStatus:" + statusCheck);

        builder.setPositiveButton("ACTIVATE", (dialog, which) -> {
            // [ใหม่] หลังกด Deactivate แล้ว กด ACTIVATE เพื่อกลับมาใช้ทางเชื่อมที่คอมเปิดไว้
            TouchSimulator.setShizukuEnabled(this, true);
            checkRealActivationStatus();
            if (isReallyActivated()) {
                Toast.makeText(this, "✅ Connection Successful!", Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(this, "❌ Connection not found", Toast.LENGTH_SHORT).show();
            }
        });
        builder.setNegativeButton("Close", null);

        AlertDialog dialog = builder.create();
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawable(new ColorDrawable(Color.parseColor("#1E2230")));
        }

        currentDialog = dialog;
        currentDialogType = "PC";
        dialog.show();
    }

    private void showDeactivationDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        TextView tvTitle = new TextView(this);
        tvTitle.setText("🛡️ Activation Status");
        tvTitle.setTextColor(Color.parseColor("#8BE9FD"));
        tvTitle.setTextSize(14f);
        tvTitle.setTypeface(null, android.graphics.Typeface.BOLD);
        tvTitle.setPadding(32, 32, 32, 16);
        builder.setCustomTitle(tvTitle);

        // [แก้] บอกวิธีที่ใช้งานอยู่ (Command หรือ Shizuku)
        builder.setMessage(isShellServerActive()
                ? "Current Status: Activated & Ready (Command Active)"
                : "Current Status: Activated & Ready (Shizuku Active)");

        builder.setPositiveButton("Refresh Status", (dialog, which) -> {
            checkRealActivationStatus();
            Toast.makeText(this, "Status Refreshed", Toast.LENGTH_SHORT).show();
        });
        // [ใหม่] ปุ่มปิดระบบแอคติเวท
        builder.setNeutralButton("Deactivate", (dialog, which) -> deactivateSystem());
        builder.setNegativeButton("Close", null);

        AlertDialog dialog = builder.create();
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawable(new ColorDrawable(Color.parseColor("#1E2230")));
        }
        dialog.show();
    }

    // [ใหม่] ปิดระบบแอคติเวท (ทั้ง Command และ Shizuku)
    private void deactivateSystem() {
        // ปล่อยนิ้วเสมือนที่อาจค้างอยู่ และหยุด overlay
        try { TouchSimulator.releaseAllKeys(); } catch (Exception ignored) {}
        try { stopService(new Intent(this, FloatingService.class)); } catch (Exception ignored) {}

        final boolean hadServer = isShellServerActive();

        // รอให้ CANCEL ที่ปล่อยนิ้วถูกส่งออกไปก่อน แล้วค่อยตัดทางส่ง event
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            // เลิกใช้ Shizuku (Shizuku ยังรันอยู่ แต่แอปจะไม่ใช้ จนกว่าจะกด Activate ใหม่)
            TouchSimulator.setShizukuEnabled(MainActivity.this, false);
            if (hadServer) {
                // Quick Activation (Command): สั่งปิด InputServer
                ShellServerClient.stopServerAsync(sent -> finishDeactivation(sent));
            } else {
                finishDeactivation(true);
            }
        }, 200);
    }

    private void finishDeactivation(boolean serverStopped) {
        checkRealActivationStatus();
        refreshCurrentDialog();
        if (!serverStopped) {
            Toast.makeText(this, "❌ ปิด server ไม่สำเร็จ ลองรันคำสั่ง activate ใหม่อีกครั้ง", Toast.LENGTH_LONG).show();
        } else if (isReallyActivated()) {
            Toast.makeText(this, "⚠️ ยังมีทางเชื่อมที่ทำงานอยู่", Toast.LENGTH_LONG).show();
        } else {
            Toast.makeText(this, "✅ Deactivated", Toast.LENGTH_LONG).show();
        }
    }

    private void checkInputDevicesStatus() {
        if (layoutPeripheralsContainer == null) return;
        layoutPeripheralsContainer.removeAllViews();

        int[] deviceIds = InputDevice.getDeviceIds();
        List<String> connectedDevices = new ArrayList<>();

        for (int id : deviceIds) {
            InputDevice device = InputDevice.getDevice(id);
            if (device != null) {
                int sources = device.getSources();
                String name = device.getName();

                if (name != null) {
                    String lowerName = name.toLowerCase();
                    if (lowerName.contains("gpio") || lowerName.contains("pmic") ||
                            lowerName.contains("resin") || lowerName.contains("pwrkey") ||
                            lowerName.contains("touchscreen") || lowerName.contains("touchpad") ||
                            lowerName.contains("e-pen") || lowerName.contains("pen") ||
                            device.isVirtual()) {
                        continue;
                    }
                }

                boolean isKeyboard = (sources & InputDevice.SOURCE_KEYBOARD) == InputDevice.SOURCE_KEYBOARD;
                boolean isMouse = (sources & InputDevice.SOURCE_MOUSE) == InputDevice.SOURCE_MOUSE ||
                        (sources & InputDevice.SOURCE_STYLUS) == InputDevice.SOURCE_STYLUS;

                if ((isKeyboard || isMouse) && !connectedDevices.contains(name)) {
                    connectedDevices.add(name);
                }
            }
        }

        if (connectedDevices.isEmpty()) {
            TextView tvEmpty = new TextView(this);
            tvEmpty.setText("• No peripherals connected");
            tvEmpty.setTextColor(Color.parseColor("#FF5555"));
            tvEmpty.setTextSize(12f);
            layoutPeripheralsContainer.addView(tvEmpty);
        } else {
            for (String deviceName : connectedDevices) {
                TextView tvDevice = new TextView(this);
                tvDevice.setText("• " + deviceName);
                tvDevice.setTextColor(Color.parseColor("#FFFFFF"));
                tvDevice.setTextSize(13f);
                tvDevice.setPadding(0, 4, 0, 4);
                layoutPeripheralsContainer.addView(tvDevice);
            }
        }
    }

    private void loadSavedGames() {
        SharedPreferences prefs = getSharedPreferences(PREF_NAME, MODE_PRIVATE);
        String jsonStr = prefs.getString(KEY_SAVED_GAMES_JSON, "");

        savedGamesList.clear();

        try {
            if (!jsonStr.isEmpty()) {
                JSONArray jsonArray = new JSONArray(jsonStr);
                for (int i = 0; i < jsonArray.length(); i++) {
                    JSONObject obj = jsonArray.getJSONObject(i);
                    String name = obj.getString("name");
                    String packageName = obj.getString("packageName");
                    savedGamesList.add(new GameItem(name, packageName));
                }
            }
        } catch (Exception e) {
            try {
                Set<String> packageSet = prefs.getStringSet(KEY_SAVED_PACKAGES_SET, new HashSet<>());
                PackageManager pm = getPackageManager();
                for (String pkg : packageSet) {
                    try {
                        ApplicationInfo appInfo = pm.getApplicationInfo(pkg, 0);
                        String name = pm.getApplicationLabel(appInfo).toString();
                        savedGamesList.add(new GameItem(name, pkg));
                    } catch (Exception ignored) {}
                }
            } catch (Exception ignored) {}
        }

        renderSavedGamesUI();
    }

    private void saveGamesList() {
        try {
            JSONArray jsonArray = new JSONArray();
            Set<String> packageSet = new HashSet<>();

            for (GameItem item : savedGamesList) {
                JSONObject obj = new JSONObject();
                obj.put("name", item.name);
                obj.put("packageName", item.packageName);
                jsonArray.put(obj);
                packageSet.add(item.packageName);
            }

            SharedPreferences prefs = getSharedPreferences(PREF_NAME, MODE_PRIVATE);
            prefs.edit()
                    .putString(KEY_SAVED_GAMES_JSON, jsonArray.toString())
                    .putStringSet(KEY_SAVED_PACKAGES_SET, packageSet)
                    .commit();

        } catch (Exception ignored) {}
    }

    private void renderSavedGamesUI() {
        if (layoutGamesContainer == null) return;
        layoutGamesContainer.removeAllViews();
        PackageManager pm = getPackageManager();

        for (int i = 0; i < savedGamesList.size(); i++) {
            GameItem item = savedGamesList.get(i);

            CardView gameCard = new CardView(this);
            LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            cardParams.setMargins(0, 0, 0, 12);
            gameCard.setLayoutParams(cardParams);
            gameCard.setCardBackgroundColor(Color.parseColor("#1E2230"));
            gameCard.setRadius(14f);
            gameCard.setCardElevation(3f);

            LinearLayout contentLayout = new LinearLayout(this);
            contentLayout.setOrientation(LinearLayout.HORIZONTAL);
            contentLayout.setGravity(Gravity.CENTER_VERTICAL);
            contentLayout.setPadding(16, 16, 16, 16);

            ImageView ivIcon = new ImageView(this);
            LinearLayout.LayoutParams iconParams = new LinearLayout.LayoutParams(70, 70);
            ivIcon.setLayoutParams(iconParams);
            try {
                Drawable icon = pm.getApplicationIcon(item.packageName);
                ivIcon.setImageDrawable(icon);
            } catch (Exception e) {
                ivIcon.setImageResource(android.R.drawable.sym_def_app_icon);
            }
            contentLayout.addView(ivIcon);

            TextView tvName = new TextView(this);
            LinearLayout.LayoutParams textParams = new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f);
            textParams.setMargins(16, 0, 16, 0);
            tvName.setLayoutParams(textParams);
            tvName.setText(item.name);
            tvName.setTextColor(Color.WHITE);
            tvName.setTextSize(13f);
            contentLayout.addView(tvName);

            TextView tvDelete = new TextView(this);
            tvDelete.setText("✕");
            tvDelete.setTextColor(Color.parseColor("#FF5555"));
            tvDelete.setTextSize(15f);
            tvDelete.setPadding(10, 10, 10, 10);
            final int index = i;
            tvDelete.setOnClickListener(v -> {
                savedGamesList.remove(index);
                saveGamesList();
                renderSavedGamesUI();
                Toast.makeText(this, "Game removed", Toast.LENGTH_SHORT).show();
            });
            contentLayout.addView(tvDelete);

            gameCard.addView(contentLayout);

            gameCard.setOnClickListener(v -> {
                if (isReallyActivated()) {
                    launchGame(item.packageName);
                } else {
                    Toast.makeText(this, "❌ กรุณาแอคติเวทระบบ Shizuku ก่อนเข้าเกม!", Toast.LENGTH_LONG).show();
                    showActivationMethodSelector();
                }
            });

            layoutGamesContainer.addView(gameCard);
        }
    }

    private void loadInstalledApps() {
        allInstalledApps.clear();
        PackageManager pm = getPackageManager();
        List<ApplicationInfo> packages = pm.getInstalledApplications(PackageManager.GET_META_DATA);

        for (ApplicationInfo appInfo : packages) {
            try {
                if (appInfo.packageName.equals(getPackageName())) continue;
                if (pm.getLaunchIntentForPackage(appInfo.packageName) == null) continue;

                String pkg = appInfo.packageName.toLowerCase();

                if (pkg.contains("android") ||
                        pkg.contains("samsung") ||
                        pkg.contains("google") ||
                        pkg.contains("sec.android") ||
                        pkg.contains("miui") ||
                        pkg.contains("oppo") ||
                        pkg.contains("vivo") ||
                        pkg.contains("huawei") ||
                        pkg.contains("ui.quicktool") ||
                        pkg.contains("providers") ||
                        pkg.contains("overlay")) {
                    continue;
                }

                String name = pm.getApplicationLabel(appInfo).toString();
                Drawable icon;
                try {
                    icon = pm.getApplicationIcon(appInfo);
                } catch (Exception e) {
                    icon = getResources().getDrawable(android.R.drawable.sym_def_app_icon, null);
                }

                if (!name.isEmpty()) {
                    allInstalledApps.add(new AppInfo(name, appInfo.packageName, icon));
                }
            } catch (Exception ignored) {}
        }

        Collections.sort(allInstalledApps, (a, b) -> a.name.compareToIgnoreCase(b.name));
    }

    public void showAppSelectionDialog() {
        try {
            loadInstalledApps();

            if (allInstalledApps.isEmpty()) {
                Toast.makeText(this, "No apps found", Toast.LENGTH_SHORT).show();
                return;
            }

            filteredApps.clear();
            filteredApps.addAll(allInstalledApps);

            LinearLayout rootLayout = new LinearLayout(this);
            rootLayout.setOrientation(LinearLayout.VERTICAL);
            rootLayout.setPadding(24, 24, 24, 24);
            rootLayout.setBackgroundColor(Color.parseColor("#161925"));

            TextView tvTitle = new TextView(this);
            tvTitle.setText("🎮 Select Game or App (Max 5)");
            tvTitle.setTextColor(Color.WHITE);
            tvTitle.setTextSize(15f);
            tvTitle.setPadding(0, 0, 0, 16);
            rootLayout.addView(tvTitle);

            EditText etSearch = new EditText(this);
            etSearch.setHint("🔍 Search app...");
            etSearch.setHintTextColor(Color.parseColor("#6272A4"));
            etSearch.setTextColor(Color.WHITE);
            etSearch.setTextSize(13f);
            etSearch.setPadding(20, 16, 20, 16);
            etSearch.setBackgroundColor(Color.parseColor("#0F111A"));
            rootLayout.addView(etSearch);

            GridView gridView = new GridView(this);
            gridView.setNumColumns(3);
            gridView.setVerticalSpacing(20);
            gridView.setHorizontalSpacing(14);
            gridView.setPadding(0, 20, 0, 0);

            LinearLayout.LayoutParams gridParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, 750);
            gridView.setLayoutParams(gridParams);
            rootLayout.addView(gridView);

            AppGridAdapter adapter = new AppGridAdapter();
            gridView.setAdapter(adapter);

            AlertDialog dialog = new AlertDialog.Builder(this)
                    .setView(rootLayout)
                    .create();

            if (dialog.getWindow() != null) {
                dialog.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            }

            etSearch.addTextChangedListener(new TextWatcher() {
                @Override
                public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

                @Override
                public void onTextChanged(CharSequence s, int start, int before, int count) {
                    filteredApps.clear();
                    String query = s.toString().toLowerCase().trim();
                    if (query.isEmpty()) {
                        filteredApps.addAll(allInstalledApps);
                    } else {
                        for (AppInfo app : allInstalledApps) {
                            if (app.name.toLowerCase().contains(query)) {
                                filteredApps.add(app);
                            }
                        }
                    }
                    adapter.notifyDataSetChanged();
                }

                @Override
                public void afterTextChanged(Editable s) {}
            });

            gridView.setOnItemClickListener((parent, view, position, id) -> {
                if (position >= 0 && position < filteredApps.size()) {
                    AppInfo selectedApp = filteredApps.get(position);

                    for (GameItem item : savedGamesList) {
                        if (item.packageName.equals(selectedApp.packageName)) {
                            Toast.makeText(this, "Game already added", Toast.LENGTH_SHORT).show();
                            return;
                        }
                    }

                    savedGamesList.add(new GameItem(selectedApp.name, selectedApp.packageName));
                    saveGamesList();
                    renderSavedGamesUI();

                    dialog.dismiss();
                    Toast.makeText(this, "Added: " + selectedApp.name, Toast.LENGTH_SHORT).show();
                }
            });

            dialog.show();
        } catch (Exception e) {
            Toast.makeText(this, "Error loading apps: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    private boolean hasUsageStatsPermission() {
        AppOpsManager appOps = (AppOpsManager) getSystemService(Context.APP_OPS_SERVICE);
        int mode = appOps.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, android.os.Process.myUid(), getPackageName());
        return mode == AppOpsManager.MODE_ALLOWED;
    }

    private void launchGame(String packageName) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
                Intent permissionIntent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:" + getPackageName()));
                startActivity(permissionIntent);
                Toast.makeText(this, "⚠️ กรุณาเปิดสิทธิ์ 'ปรากฏด้านบนสุด' ก่อนเข้าเกม", Toast.LENGTH_LONG).show();
                return;
            }

            if (!hasUsageStatsPermission()) {
                startActivity(new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS));
                Toast.makeText(this, "⚠️ กรุณาเปิดสิทธิ์ 'การเข้าถึงข้อมูลการใช้งาน' ให้แอป Blue Mouse Pro ด้วยครับ", Toast.LENGTH_LONG).show();
                return;
            }

            PackageManager pm = getPackageManager();
            Intent intent = pm.getLaunchIntentForPackage(packageName);
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(intent);
                Toast.makeText(this, "🚀 กำลังเข้าเกมพร้อมระบบเซนเซอร์อัตโนมัติ...", Toast.LENGTH_SHORT).show();

                new Handler(Looper.getMainLooper()).postDelayed(() -> {
                    Intent serviceIntent = new Intent(MainActivity.this, FloatingService.class);
                    serviceIntent.putExtra("GAME_PACKAGE", packageName);
                    startService(serviceIntent);
                }, 1200);
            } else {
                Toast.makeText(this, "Cannot launch this app", Toast.LENGTH_SHORT).show();
            }
        } catch (Exception e) {
            Toast.makeText(this, "Error launching app", Toast.LENGTH_SHORT).show();
        }
    }

    private class AppGridAdapter extends BaseAdapter {
        @Override
        public int getCount() {
            return filteredApps.size();
        }

        @Override
        public Object getItem(int position) {
            return filteredApps.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            LinearLayout itemLayout;
            if (convertView == null) {
                itemLayout = new LinearLayout(MainActivity.this);
                itemLayout.setOrientation(LinearLayout.VERTICAL);
                itemLayout.setGravity(Gravity.CENTER);
                itemLayout.setPadding(10, 10, 10, 10);

                ImageView ivIcon = new ImageView(MainActivity.this);
                ivIcon.setLayoutParams(new LinearLayout.LayoutParams(110, 110));
                itemLayout.addView(ivIcon);

                TextView tvName = new TextView(MainActivity.this);
                tvName.setTextColor(Color.WHITE);
                tvName.setTextSize(10f);
                tvName.setGravity(Gravity.CENTER);
                tvName.setMaxLines(2);
                LinearLayout.LayoutParams textParams = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                textParams.setMargins(0, 6, 0, 0);
                tvName.setLayoutParams(textParams);
                itemLayout.addView(tvName);
            } else {
                itemLayout = (LinearLayout) convertView;
            }

            if (position < filteredApps.size()) {
                AppInfo app = filteredApps.get(position);
                ImageView ivIcon = (ImageView) itemLayout.getChildAt(0);
                TextView tvName = (TextView) itemLayout.getChildAt(1);

                ivIcon.setImageDrawable(app.icon);
                tvName.setText(app.name);
            }

            return itemLayout;
        }
    }

    private static class AppInfo {
        String name;
        String packageName;
        Drawable icon;

        AppInfo(String name, String packageName, Drawable icon) {
            this.name = name;
            this.packageName = packageName;
            this.icon = icon;
        }
    }

    private static class GameItem {
        String name;
        String packageName;

        GameItem(String name, String packageName) {
            this.name = name;
            this.packageName = packageName;
        }
    }
}