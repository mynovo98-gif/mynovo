package com.mynovo.mynovo1;

import android.app.ActivityOptions;
import android.app.AppOpsManager;
import android.app.Dialog;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.hardware.display.DisplayManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.util.Log;
import android.view.Display;
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

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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

    private Dialog currentDialog;
    private String currentDialogType = "";

    // ปุ่ม C ที่เด้งขึ้นมาเหนือปุ่มปากกา
    private ImageView cButton;

    // wizard Wireless activation (ใช้ WirelessAdbWizardManager ภายใน)
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

        // ให้ client ของ shell server (Quick Activation) รู้จัก context
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

        // ปุ่มปากกากลางแถบเมนูล่าง
        addCenterPenButton();
    }

    @Override
    protected void onPause() {
        super.onPause();
        hideCButton();
    }

    @Override
    protected void onStop() {
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        // ปิด wizard ถ้ายังเปิดอยู่ (จะหยุด mDNS + ล้าง notification ให้เอง)
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

        // ลองเชื่อม shell server เบื้องหลัง (เผื่อผู้ใช้เพิ่งรัน activate.sh แล้วสลับกลับมา) เสร็จแล้วอัปเดตสถานะ
        ShellServerClient.connectAsync(ok -> {
            checkRealActivationStatus();
            if (ok) refreshCurrentDialog();
        });

        // ถ้าเพิ่งส่งทัชไม่ผ่าน (ROM บล็อก) แสดงวิธีเปิดสวิตช์ของยี่ห้อนั้น
        RomGuide.showIfNeeded(this);
    }

    private void refreshCurrentDialog() {
        if (currentDialog != null && currentDialog.isShowing()) {
            // Wireless wizard อัปเดตสถานะในตัวเอง ห้ามปิด-เปิดใหม่ ไม่งั้น flow pair/connect จะรีเซ็ต
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

    // เชื่อม shell server (Quick Activation) อยู่หรือไม่
    private boolean isShellServerActive() {
        return ShellServerClient.isConnected();
    }

    private boolean isReallyActivated() {
        // นับ shell server ด้วย
        if (isShellServerActive()) return true;
        // ผู้ใช้กด Deactivate แล้ว ไม่นับ Shizuku (จนกว่าจะกด Activate ใหม่)
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

        TextView tvMyGameHeader = new TextView(this);
        tvMyGameHeader.setText("My game");
        tvMyGameHeader.setTextColor(Color.parseColor("#8BE9FD"));
        tvMyGameHeader.setTextSize(14f);
        tvMyGameHeader.setTypeface(null, android.graphics.Typeface.BOLD);
        tvMyGameHeader.setPadding(4, 4, 0, 8);

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

        // ===== แถวบน: คอลัมน์ซ้าย (Activation Center + Available peripherals + My game + Add Game)
        //       + กล่องด้านขวา (สูงเท่าคอลัมน์ซ้ายเสมอ ไม่ว่าคอลัมน์ซ้ายจะสูงเท่าไหร่ก็ตาม) =====
        LinearLayout topRow = new LinearLayout(this);
        topRow.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams topRowParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        topRowParams.setMargins(0, 0, 0, 14);
        topRow.setLayoutParams(topRowParams);

        LinearLayout leftColumn = new LinearLayout(this);
        leftColumn.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams leftColumnParams = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        leftColumn.setLayoutParams(leftColumnParams);
        leftColumn.addView(btnCardActivation);
        leftColumn.addView(peripheralCard);
        leftColumn.addView(tvMyGameHeader);
        leftColumn.addView(btnAddApp);
        topRow.addView(leftColumn);

        // กล่องด้านขวา พื้นหลังโทนเดียวกับการ์ดอื่นในแอป สูง MATCH_PARENT = เท่ากับคอลัมน์ซ้ายเสมอโดยอัตโนมัติ
        LinearLayout sideBox = new LinearLayout(this);
        sideBox.setOrientation(LinearLayout.VERTICAL);

        android.graphics.drawable.GradientDrawable sideBoxBg = new android.graphics.drawable.GradientDrawable();
        sideBoxBg.setColor(Color.parseColor("#1E2230"));          // พื้นหลังโทนเดียวกับการ์ดอื่นๆ
        sideBoxBg.setStroke(4, Color.parseColor("#FF5555"));      // เส้นขอบแดง ปรับได้
        sideBoxBg.setCornerRadius(12f);                           // ใส่ 0 ถ้าอยากได้เหลี่ยมคม
        sideBox.setBackground(sideBoxBg);

        LinearLayout.LayoutParams sideBoxParams = new LinearLayout.LayoutParams(
                780, ViewGroup.LayoutParams.MATCH_PARENT); // กว้างคงที่ 780px (ลดลงจาก 950), สูงเท่า leftColumn เสมอ ไม่ทับกัน
        sideBoxParams.setMargins(14, 0, 0, 0);
        sideBox.setLayoutParams(sideBoxParams);

        // ===== ข้อความเลื่อนแนวตั้ง จากล่างขึ้นบน (เหมือนเครดิตท้ายหนัง) วนไม่หยุด =====
        FrameLayout sideBoxScrollArea = new FrameLayout(this);
        sideBoxScrollArea.setClipChildren(true); // ตัดส่วนที่เลื่อนเกินกรอบไม่ให้โผล่ออกนอกกล่อง
        sideBoxScrollArea.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        sideBox.addView(sideBoxScrollArea);

        // กล่องแนวตั้งที่รวมทุกบรรทัดเครดิตไว้ด้วยกัน เลื่อนขึ้นพร้อมกันเป็นชุดเดียว
        LinearLayout creditsList = new LinearLayout(this);
        creditsList.setOrientation(LinearLayout.VERTICAL);
        FrameLayout.LayoutParams creditsListParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL);
        creditsList.setLayoutParams(creditsListParams);
        sideBoxScrollArea.addView(creditsList);

        TextView tvCredit1 = new TextView(this);
        tvCredit1.setText("ผู้พัฒนาแอปBluemouseproมายโนโว");
        tvCredit1.setTextColor(Color.parseColor("#8BE9FD"));
        tvCredit1.setTextSize(14f);
        tvCredit1.setTypeface(null, android.graphics.Typeface.BOLD);
        tvCredit1.setGravity(Gravity.CENTER_HORIZONTAL);
        LinearLayout.LayoutParams tvCredit1Params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tvCredit1Params.setMargins(0, 0, 0, 24); // ระยะห่างก่อนบรรทัดถัดไป ปรับได้
        tvCredit1.setLayoutParams(tvCredit1Params);
        creditsList.addView(tvCredit1);

        TextView tvCredit2 = new TextView(this);
        tvCredit2.setText("Racerx ครีเอเตอร์ Bluemousepro");
        tvCredit2.setTextColor(Color.parseColor("#8BE9FD"));
        tvCredit2.setTextSize(14f);
        tvCredit2.setTypeface(null, android.graphics.Typeface.BOLD);
        tvCredit2.setGravity(Gravity.CENTER_HORIZONTAL);
        LinearLayout.LayoutParams tvCredit2Params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tvCredit2.setLayoutParams(tvCredit2Params);
        creditsList.addView(tvCredit2);

        TextView tvCredit3 = new TextView(this);
        tvCredit3.setText("Kisper ครีเอเตอร์");
        tvCredit3.setTextColor(Color.parseColor("#8BE9FD"));
        tvCredit3.setTextSize(14f);
        tvCredit3.setTypeface(null, android.graphics.Typeface.BOLD);
        tvCredit3.setGravity(Gravity.CENTER_HORIZONTAL);
        LinearLayout.LayoutParams tvCredit3Params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tvCredit3.setLayoutParams(tvCredit3Params);
        creditsList.addView(tvCredit3);

        // รอให้ layout วัดขนาดจริงเสร็จก่อน ค่อยเริ่มคำนวณระยะเลื่อน (ต้องรู้ความสูงกล่อง + ความสูงชุดเครดิตทั้งหมด)
        sideBoxScrollArea.getViewTreeObserver().addOnGlobalLayoutListener(
                new android.view.ViewTreeObserver.OnGlobalLayoutListener() {
                    @Override
                    public void onGlobalLayout() {
                        sideBoxScrollArea.getViewTreeObserver().removeOnGlobalLayoutListener(this);
                        float containerHeight = sideBoxScrollArea.getHeight();
                        float creditsHeight = creditsList.getHeight();
                        if (containerHeight <= 0) return;

                        creditsList.setTranslationY(containerHeight); // เริ่มจากใต้กล่อง (มองไม่เห็น)

                        android.animation.ObjectAnimator scrollAnim = android.animation.ObjectAnimator.ofFloat(
                                creditsList, "translationY", containerHeight, -creditsHeight);
                        scrollAnim.setDuration(8000); // 8 วินาทีต่อรอบ (ยาวขึ้นเพราะมี 2 บรรทัด) ปรับความเร็วได้ตรงนี้
                        scrollAnim.setInterpolator(new android.view.animation.LinearInterpolator());
                        scrollAnim.setRepeatCount(android.animation.ObjectAnimator.INFINITE); // วนลูปไปเรื่อยๆ ไม่หยุด
                        scrollAnim.setRepeatMode(android.animation.ObjectAnimator.RESTART);
                        scrollAnim.start();
                    }
                });

        topRow.addView(sideBox);

        rootContent.addView(topRow);

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

    // ===================== ระบบ Account (Firebase Email/Password) =====================

    private void loadAccountView() {
        FirebaseUser user = null;
        try {
            user = FirebaseAuth.getInstance().getCurrentUser();
        } catch (Exception ignored) {}
        if (user != null) showAccountLoggedIn(user);
        else showAccountLogin();
    }

    private void showAccountLogin() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);

        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setGravity(Gravity.CENTER);
        layout.setPadding(40, 32, 40, 32);

        TextView tvTitle = new TextView(this);
        tvTitle.setText("👤 Account & Cloud Sync");
        tvTitle.setTextColor(Color.parseColor("#8BE9FD"));
        tvTitle.setTextSize(18f);
        tvTitle.setTypeface(null, android.graphics.Typeface.BOLD);
        tvTitle.setGravity(Gravity.CENTER);
        tvTitle.setPadding(0, 0, 0, 24);
        layout.addView(tvTitle);

        final EditText etEmail = makeAccountInput("Email", false);
        final EditText etPass = makeAccountInput("Password (min 6)", true);
        layout.addView(etEmail);
        layout.addView(etPass);

        layout.addView(makeAccountButton("เข้าสู่ระบบ", "#BD93F9", v -> {
            String email = etEmail.getText().toString().trim();
            String pass = etPass.getText().toString();
            if (email.isEmpty() || pass.isEmpty()) {
                Toast.makeText(this, "กรอกอีเมลและรหัสผ่านก่อน", Toast.LENGTH_SHORT).show();
                return;
            }
            FirebaseAuth.getInstance().signInWithEmailAndPassword(email, pass)
                    .addOnCompleteListener(task -> {
                        if (isFinishing()) return;
                        if (task.isSuccessful()) {
                            Toast.makeText(this, "✅ Login สำเร็จ", Toast.LENGTH_SHORT).show();
                            loadAccountView();
                        } else {
                            Toast.makeText(this, "❌ " + (task.getException() != null
                                    ? task.getException().getMessage() : "Login failed"),
                                    Toast.LENGTH_LONG).show();
                        }
                    });
        }));

        layout.addView(makeAccountButton("สร้างแอคเคาท์", "#282C34", v -> {
            String email = etEmail.getText().toString().trim();
            String pass = etPass.getText().toString();
            if (email.isEmpty() || pass.length() < 6) {
                Toast.makeText(this, "กรอกอีเมล และรหัสผ่านอย่างน้อย 6 ตัว", Toast.LENGTH_SHORT).show();
                return;
            }
            FirebaseAuth.getInstance().createUserWithEmailAndPassword(email, pass)
                    .addOnCompleteListener(task -> {
                        if (isFinishing()) return;
                        if (task.isSuccessful()) {
                            Toast.makeText(this, "✅ สมัครสำเร็จ", Toast.LENGTH_SHORT).show();
                            loadAccountView();
                        } else {
                            Toast.makeText(this, "❌ " + (task.getException() != null
                                    ? task.getException().getMessage() : "Register failed"),
                                    Toast.LENGTH_LONG).show();
                        }
                    });
        }));

        TextView tvForgot = new TextView(this);
        tvForgot.setText("รีเซ็ตรหัส/เปลี่ยนรหัส");
        tvForgot.setTextColor(Color.parseColor("#8BE9FD"));
        tvForgot.setTextSize(12f);
        tvForgot.setGravity(Gravity.CENTER);
        tvForgot.setPadding(0, 20, 0, 0);
        tvForgot.setOnClickListener(v -> {
            String email = etEmail.getText().toString().trim();
            if (email.isEmpty()) {
                Toast.makeText(this, "กรอกอีเมลก่อน", Toast.LENGTH_SHORT).show();
                return;
            }
            FirebaseAuth.getInstance().sendPasswordResetEmail(email)
                    .addOnCompleteListener(task -> {
                        if (isFinishing()) return;
                        Toast.makeText(this, task.isSuccessful()
                                ? "📧 ส่งลิงก์รีเซ็ตรหัสผ่านแล้ว"
                                : "❌ ส่งไม่สำเร็จ", Toast.LENGTH_LONG).show();
                    });
        });
        layout.addView(tvForgot);

        scroll.addView(layout);
        fragmentContainer.removeAllViews();
        fragmentContainer.addView(scroll);
    }

    private void showAccountLoggedIn(FirebaseUser user) {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setGravity(Gravity.CENTER);
        layout.setPadding(40, 32, 40, 32);

        TextView tvTitle = new TextView(this);
        tvTitle.setText("👤 Signed in");
        tvTitle.setTextColor(Color.parseColor("#50FA7B"));
        tvTitle.setTextSize(18f);
        tvTitle.setTypeface(null, android.graphics.Typeface.BOLD);
        tvTitle.setGravity(Gravity.CENTER);
        layout.addView(tvTitle);

        TextView tvEmail = new TextView(this);
        tvEmail.setText(user.getEmail() != null ? user.getEmail() : "(no email)");
        tvEmail.setTextColor(Color.WHITE);
        tvEmail.setTextSize(14f);
        tvEmail.setGravity(Gravity.CENTER);
        tvEmail.setPadding(0, 12, 0, 24);
        layout.addView(tvEmail);

        layout.addView(makeAccountButton("LOGOUT", "#FF5555", v -> {
            FirebaseAuth.getInstance().signOut();
            Toast.makeText(this, "Logged out", Toast.LENGTH_SHORT).show();
            loadAccountView();
        }));

        fragmentContainer.removeAllViews();
        fragmentContainer.addView(layout);
    }

    private EditText makeAccountInput(String hint, boolean password) {
        EditText et = new EditText(this);
        et.setHint(hint);
        et.setHintTextColor(Color.parseColor("#6272A4"));
        et.setTextColor(Color.WHITE);
        et.setTextSize(14f);
        et.setSingleLine(true);
        et.setPadding(24, 20, 24, 20);
        et.setBackgroundColor(Color.parseColor("#0F111A"));
        et.setInputType(password
                ? (InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD)
                : (InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS));
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        p.setMargins(0, 8, 0, 8);
        et.setLayoutParams(p);
        return et;
    }

    private View makeAccountButton(String text, String colorHex, View.OnClickListener l) {
        TextView btn = new TextView(this);
        btn.setText(text);
        btn.setTextColor(Color.WHITE);
        btn.setTextSize(13f);
        btn.setTypeface(null, android.graphics.Typeface.BOLD);
        btn.setGravity(Gravity.CENTER);
        btn.setPadding(24, 20, 24, 20);
        btn.setBackgroundColor(Color.parseColor(colorHex));
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        p.setMargins(0, 12, 0, 0);
        btn.setLayoutParams(p);
        btn.setOnClickListener(l);
        return btn;
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

    // ===== ปุ่มปากกากลางแถบเมนูล่าง (แทรกระหว่าง Account กับ Activation) =====
    // แทรกจากโค้ด ไม่ต้องแก้ activity_main.xml
    // ต้องมี res/drawable/ic_command_menu.png (รูปปากกา) และ res/drawable/btn_pen.png (รูปตัว C)
    private void addCenterPenButton() {
        try {
            ViewGroup parent = (ViewGroup) navAccount.getParent();
            if (!(parent instanceof LinearLayout)) {
                Log.w("NAV", "nav parent is not LinearLayout -> skip pen button");
                return;
            }
            if (parent.findViewWithTag("NAV_PEN") != null) return;

            float d = getResources().getDisplayMetrics().density;
            int size = Math.round(44 * d);

            FrameLayout slot = new FrameLayout(this);
            slot.setTag("NAV_PEN");
            slot.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));

            ImageView pen = new ImageView(this);
            pen.setImageResource(R.drawable.ic_command_menu); // รูปปากกา res/drawable/ic_command_menu.png
            pen.setScaleType(ImageView.ScaleType.FIT_CENTER);
            pen.setContentDescription("Command Menu");
            pen.setClickable(true);
            pen.setFocusable(true);
            pen.setLayoutParams(new FrameLayout.LayoutParams(size, size, Gravity.CENTER));

            // กดปากกา -> ปุ่ม C เด้งขึ้นมาเหนือปากกา (กดซ้ำเพื่อซ่อน)
            pen.setOnClickListener(v -> toggleCButton(pen));

            // เอาเมาส์ไปชี้ (hover) ปากกา ก็ให้ปุ่ม C ขึ้นมาได้เลย ไม่ต้องคลิก (เผื่อใช้งานผ่านเมาส์จริงที่ต่อกับแอปนี้)
            pen.setOnHoverListener((v, event) -> {
                if (event.getAction() == android.view.MotionEvent.ACTION_HOVER_ENTER) {
                    showCButton(pen);
                    return true;
                }
                return false;
            });

            slot.addView(pen);

            parent.addView(slot, parent.indexOfChild(navAccount) + 1);
        } catch (Exception e) {
            Log.w("NAV", "addCenterPenButton failed: " + e);
        }
    }

    private void onPenButtonClick() {
        showTerminalDialog();
    }

    // ===== ปุ่ม C ที่เด้งขึ้นมาเหนือปุ่มปากกา =====
    private void toggleCButton(View pen) {
        if (cButton != null && cButton.getParent() != null) hideCButton();
        else showCButton(pen);
    }

    private void hideCButton() {
        if (cButton != null && cButton.getParent() instanceof ViewGroup) {
            ((ViewGroup) cButton.getParent()).removeView(cButton);
        }
    }

    private void showCButton(View pen) {
        if (cButton != null && cButton.getParent() != null) return;
        ViewGroup content = findViewById(android.R.id.content);
        float d = getResources().getDisplayMetrics().density;
        int size = Math.round(44 * d);
        int gap = Math.round(90 * d);      // ระยะจากกลางปากกาขึ้นไปถึงกลางปุ่ม C (เพิ่ม = สูงขึ้น, ลด = ต่ำลง)
        int offsetX = Math.round(-4 * d);  // เลื่อนซ้าย/ขวา (ลบ = ซ้าย, บวก = ขวา)

        int[] pl = new int[2];
        int[] cl = new int[2];
        pen.getLocationInWindow(pl);
        content.getLocationInWindow(cl);
        int cx = pl[0] - cl[0] + pen.getWidth() / 2;
        int cy = pl[1] - cl[1] + pen.getHeight() / 2;

        cButton = new ImageView(this);
        cButton.setImageResource(R.drawable.btn_pen); // รูปตัว C res/drawable/btn_pen.png (ถ้าชื่อไฟล์อื่น แก้ตรงนี้)
        cButton.setScaleType(ImageView.ScaleType.FIT_CENTER);
        cButton.setContentDescription("C");
        cButton.setElevation(24 * d);
        cButton.setClickable(true);
        cButton.setOnClickListener(v -> {
            hideCButton();
            showTerminalDialog(); // ระบบคอมมานัด (Terminal)
        });

        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                size, size, Gravity.TOP | Gravity.START);
        lp.leftMargin = cx - size / 2 + offsetX;
        lp.topMargin = cy - gap - size / 2;
        content.addView(cButton, lp);
    }

    // ===== Terminal: พิมพ์คำสั่ง shell เอง สั่งผ่าน Shizuku =====
    private void showTerminalDialog() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.parseColor("#1E2230"));
        root.setPadding(24, 24, 24, 24);

        TextView tvTitle = new TextView(this);
        tvTitle.setText("⌨️ Terminal (Shizuku)");
        tvTitle.setTextColor(Color.parseColor("#8BE9FD"));
        tvTitle.setTextSize(16f);
        tvTitle.setTypeface(null, android.graphics.Typeface.BOLD);
        tvTitle.setPadding(0, 0, 0, 16);
        root.addView(tvTitle);

        // พื้นที่แสดงผลลัพธ์ (output log) เลื่อนได้
        ScrollView outputScroll = new ScrollView(this);
        LinearLayout.LayoutParams outputScrollParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 650);
        outputScroll.setLayoutParams(outputScrollParams);
        outputScroll.setBackgroundColor(Color.parseColor("#0F111A"));
        outputScroll.setPadding(16, 16, 16, 16);

        TextView tvOutput = new TextView(this);
        tvOutput.setText("$ พร้อมรับคำสั่ง... (รัน Shizuku Service + ขอสิทธิ์ก่อนใช้งาน)\n");
        tvOutput.setTextColor(Color.parseColor("#50FA7B"));
        tvOutput.setTextSize(12f);
        tvOutput.setTypeface(android.graphics.Typeface.MONOSPACE);
        outputScroll.addView(tvOutput);
        root.addView(outputScroll);

        // แถวพิมพ์คำสั่ง + ปุ่ม Run
        LinearLayout inputRow = new LinearLayout(this);
        inputRow.setOrientation(LinearLayout.HORIZONTAL);
        inputRow.setGravity(Gravity.CENTER_VERTICAL);
        inputRow.setPadding(0, 16, 0, 0);

        EditText etCommand = new EditText(this);
        etCommand.setHint("พิมพ์คำสั่ง shell เช่น ls, pwd, dumpsys ...");
        etCommand.setHintTextColor(Color.parseColor("#6272A4"));
        etCommand.setTextColor(Color.WHITE);
        etCommand.setTextSize(13f);
        etCommand.setTypeface(android.graphics.Typeface.MONOSPACE);
        etCommand.setSingleLine(true);
        etCommand.setPadding(20, 16, 20, 16);
        etCommand.setBackgroundColor(Color.parseColor("#0F111A"));
        etCommand.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        etCommand.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_GO);
        LinearLayout.LayoutParams etCommandParams = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        etCommandParams.setMargins(0, 0, 10, 0);
        etCommand.setLayoutParams(etCommandParams);
        inputRow.addView(etCommand);

        TextView btnRun = new TextView(this);
        btnRun.setText("RUN");
        btnRun.setTextColor(Color.WHITE);
        btnRun.setTextSize(13f);
        btnRun.setTypeface(null, android.graphics.Typeface.BOLD);
        btnRun.setPadding(28, 16, 28, 16);
        btnRun.setBackgroundColor(Color.parseColor("#BD93F9"));
        inputRow.addView(btnRun);
        root.addView(inputRow);

        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setView(root);
        builder.setNegativeButton("Close", null);
        AlertDialog dialog = builder.create();
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawable(new ColorDrawable(Color.parseColor("#1E2230")));
        }

        // รันคำสั่งที่พิมพ์ ผ่าน Shizuku (background thread) แล้วเอาผลลัพธ์มาต่อท้าย output log
        Runnable executeCommand = () -> {
            String cmd = etCommand.getText().toString().trim();
            if (cmd.isEmpty()) return;

            tvOutput.append("\n$ " + cmd + "\n");
            etCommand.setText("");
            btnRun.setEnabled(false);

            new Thread(() -> {
                String result = runShizukuShell(cmd);
                runOnUiThread(() -> {
                    if (result == null) {
                        tvOutput.append("❌ ใช้งานไม่ได้ กรุณาเปิด Shizuku Service และขอสิทธิ์ก่อน (ดูที่ Activation)\n");
                    } else {
                        tvOutput.append(result.isEmpty() ? "(ไม่มีผลลัพธ์)\n" : result);
                    }
                    outputScroll.post(() -> outputScroll.fullScroll(View.FOCUS_DOWN));
                    btnRun.setEnabled(true);
                });
            }).start();
        };

        btnRun.setOnClickListener(v -> executeCommand.run());
        etCommand.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_GO) {
                executeCommand.run();
                return true;
            }
            return false;
        });

        dialog.show();
        // ขยายความกว้าง dialog ให้เต็มจอ เพราะ terminal ต้องการพื้นที่เยอะ
        if (dialog.getWindow() != null) {
            dialog.getWindow().setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        }
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

        // วิธีที่ 4: Quick Activation (Command) แบบ K2 Tap
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

    // ใช้ WirelessActivationDialog (wizard 6 ขั้นตอน: Developer options -> USB debugging
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
            // หลังกด Deactivate แล้ว กด ACTIVATE เพื่อกลับมาใช้ทางเชื่อมที่คอมเปิดไว้
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

        // บอกวิธีที่ใช้งานอยู่ (Command หรือ Shizuku)
        builder.setMessage(isShellServerActive()
                ? "Current Status: Activated & Ready (Command Active)"
                : "Current Status: Activated & Ready (Shizuku Active)");

        builder.setPositiveButton("Refresh Status", (dialog, which) -> {
            checkRealActivationStatus();
            Toast.makeText(this, "Status Refreshed", Toast.LENGTH_SHORT).show();
        });
        // ปุ่มปิดระบบแอคติเวท
        builder.setNeutralButton("Deactivate", (dialog, which) -> deactivateSystem());
        builder.setNegativeButton("Close", null);

        AlertDialog dialog = builder.create();
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawable(new ColorDrawable(Color.parseColor("#1E2230")));
        }
        dialog.show();
    }

    // ปิดระบบแอคติเวท (ทั้ง Command และ Shizuku)
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

    // ===================== ระบบเปิดเกมบนจอ DeX =====================

    // จอที่ MainActivity อยู่ตอนนี้ (0 = มือถือ)
    @SuppressWarnings("deprecation")
    private int myDisplayId() {
        try {
            return getWindowManager().getDefaultDisplay().getDisplayId();
        } catch (Exception e) {
            return Display.DEFAULT_DISPLAY;
        }
    }

    // Samsung DeX เปิดอยู่หรือไม่ (อ่าน Configuration.semDesktopModeEnabled ผ่าน reflection)
    private boolean isDexModeActive() {
        try {
            Configuration c = getResources().getConfiguration();
            java.lang.reflect.Field f = Configuration.class.getField("semDesktopModeEnabled");
            if (f.getInt(c) == 1) return true;
        } catch (Throwable ignored) {}
        try {
            // อาจไม่ใช่ DeX แต่ไม่ใช่ Samsung: นับว่าเปิดเมื่อมี MainActivity อยู่บนจอนอก
            if (myDisplayId() != Display.DEFAULT_DISPLAY) return true;
        } catch (Throwable ignored) {}
        return false;
    }

    // รันคำสั่ง shell ผ่าน Shizuku (เรียกจากเธรดพื้นหลังเท่านั้น) คืน null ถ้าใช้ไม่ได้
    private String runShizukuShell(String cmd) {
        try {
            if (!Shizuku.pingBinder()
                    || Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) return null;
            java.lang.reflect.Method m = Shizuku.class.getDeclaredMethod(
                    "newProcess", String[].class, String[].class, String.class);
            m.setAccessible(true);
            Process p = (Process) m.invoke(null, new Object[]{
                    new String[]{"sh", "-c", cmd + " 2>&1"}, null, null});
            java.io.BufferedReader r = new java.io.BufferedReader(
                    new java.io.InputStreamReader(p.getInputStream()));
            StringBuilder sb = new StringBuilder();
            String l;
            while ((l = r.readLine()) != null) sb.append(l).append('\n');
            try { r.close(); } catch (Exception ignored) {}
            try { p.waitFor(); } catch (Exception ignored) {}
            return sb.toString();
        } catch (Throwable t) {
            Log.w("DEX", "runShizukuShell failed: " + t);
            return null;
        }
    }

    // หา displayId ของจอ DeX/จอนอก คืน -1 ถ้าไม่เจอ (เรียกจากเธรดพื้นหลัง)
    private int detectExternalDisplayId() {
        // 1) MainActivity เปิดอยู่บนจอนอกอยู่แล้ว ใช้จอนั้นเลย
        int mine = myDisplayId();
        if (mine != Display.DEFAULT_DISPLAY) return mine;

        // 2) จอนอกที่แอปมองเห็น (ไม่นับจอ private)
        try {
            DisplayManager dm = (DisplayManager) getSystemService(DISPLAY_SERVICE);
            if (dm != null) {
                for (Display d : dm.getDisplays()) {
                    if (d.getDisplayId() == Display.DEFAULT_DISPLAY) continue;
                    if ((d.getFlags() & Display.FLAG_PRIVATE) != 0) continue;
                    if (d.getState() == Display.STATE_OFF) continue;
                    return d.getDisplayId();
                }
            }
        } catch (Throwable ignored) {}

        // 3) จอ DeX แบบ private แอปมองไม่เห็น ถาม Shizuku จาก dumpsys
        String out = runShizukuShell("dumpsys activity activities | grep 'Display #'");
        if (out != null) {
            Matcher m = Pattern.compile("Display #(\\d+)").matcher(out);
            while (m.find()) {
                int id = Integer.parseInt(m.group(1));
                if (id > 0) return id;
            }
        }
        return -1;
    }

    // เกมนี้มี ActivityRecord อยู่บนจออื่นที่ไม่ใช่จอเป้าหมายหรือไม่
    private boolean hasTaskOnOtherDisplay(String dump, String pkg, int targetDisplay) {
        Pattern dispPat = Pattern.compile("Display #(\\d+)");
        int cur = -1;
        for (String line : dump.split("\n")) {
            Matcher dm = dispPat.matcher(line);
            if (dm.find()) { cur = Integer.parseInt(dm.group(1)); continue; }
            if (cur >= 0 && cur != targetDisplay && line.contains(" " + pkg + "/")) return true;
        }
        return false;
    }

    // สั่งเปิดเกมบนจอ displayId ที่กำหนด คืน true ถ้าสำเร็จ (เรียกจากเธรดพื้นหลัง)
    private boolean startGameOnDisplayViaShell(Intent intent, String pkg, int displayId) {
        // ถ้าเกมมี task ค้างอยู่คนละจอ (เช่นบนมือถือ) ต้องปิดก่อน ไม่งั้น am start จะแค่ดึง task เดิม
        String dump = runShizukuShell("dumpsys activity activities | grep -E 'Display #|ActivityRecord'");
        if (dump != null && hasTaskOnOtherDisplay(dump, pkg, displayId)) {
            Log.w("DEX", "game has old task on another display -> force-stop before launch");
            runShizukuShell("am force-stop " + pkg);
            try { Thread.sleep(500); } catch (InterruptedException ignored) {}
        }
        String cmd;
        ComponentName cn = intent.getComponent();
        if (cn != null) {
            cmd = "am start --display " + displayId + " --activity-no-animation -n "
                    + cn.getPackageName() + "/" + cn.getClassName();
        } else {
            cmd = "am start --display " + displayId
                    + " --activity-no-animation -a android.intent.action.MAIN"
                    + " -c android.intent.category.LAUNCHER -p " + pkg;
        }
        String out = runShizukuShell(cmd);
        Log.w("DEX", "launch game display=" + displayId + " -> " + out);
        if (out == null) return false;
        return !out.contains("Error") && !out.contains("Exception");
    }

    // ถ้า DeX เปิดอยู่ จะสั่งเปิดเกมบนจอ DeX ตรงๆ ไม่เปิดบนมือถือ
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
            final Intent intent = pm.getLaunchIntentForPackage(packageName);
            if (intent == null) {
                Toast.makeText(this, "Cannot launch this app", Toast.LENGTH_SHORT).show();
                return;
            }
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            Toast.makeText(this, "🚀 กำลังเข้าเกมพร้อมระบบเซนเซอร์อัตโนมัติ...", Toast.LENGTH_SHORT).show();

            final boolean dex = isDexModeActive();
            final int minePre = myDisplayId();

            new Thread(() -> {
                int displayId = -1;
                if (dex) displayId = detectExternalDisplayId();

                boolean launched = false;
                if (displayId > 0) {
                    // วิธีที่ 1: สั่งผ่าน Shizuku ได้ทุกจอ รวมจอ DeX แบบ private
                    launched = startGameOnDisplayViaShell(intent, packageName, displayId);

                    // วิธีที่ 2: ถ้า Shizuku ใช้ไม่ได้ และเรามองเห็นจอนั้นอยู่ ให้ใช้ ActivityOptions
                    if (!launched && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        try {
                            DisplayManager dm = (DisplayManager) getSystemService(DISPLAY_SERVICE);
                            if (dm != null && dm.getDisplay(displayId) != null) {
                                final ActivityOptions opts = ActivityOptions.makeBasic();
                                opts.setLaunchDisplayId(displayId);
                                final Bundle b = opts.toBundle();
                                runOnUiThread(() -> {
                                    try { startActivity(intent, b); } catch (Exception e) {
                                        Log.w("DEX", "startActivity with display failed: " + e);
                                    }
                                });
                                launched = true;
                            }
                        } catch (Throwable t) {
                            Log.w("DEX", "ActivityOptions launch failed: " + t);
                        }
                    }
                }

                final boolean done = launched;
                final int did = displayId;
                final boolean shOk = runShizukuShell("echo ok") != null;
                runOnUiThread(() -> {
                    // แสดงผลวินิจฉัยบนจอ เพื่อดูว่าติดที่ขั้นไหน
                    Toast.makeText(MainActivity.this,
                            "DeX=" + dex + " จอแอป=" + minePre + " จอเป้าหมาย=" + did
                                    + " Shizuku shell=" + shOk + " สั่งเปิดสำเร็จ=" + done,
                            Toast.LENGTH_LONG).show();
                    if (!done) {
                        // DeX ไม่เปิด หรือเปิดบนจอ DeX ไม่ได้ ใช้วิธีเดิม (จอที่ MainActivity อยู่)
                        if (dex && did <= 0 && minePre == Display.DEFAULT_DISPLAY) {
                            Log.w("DEX", "DeX active but no external display found -> launch on phone");
                        }
                        try { startActivity(intent); } catch (Exception e) {
                            Toast.makeText(MainActivity.this, "Error launching app", Toast.LENGTH_SHORT).show();
                            return;
                        }
                    }

                    new Handler(Looper.getMainLooper()).postDelayed(() -> {
                        Intent serviceIntent = new Intent(MainActivity.this, FloatingService.class);
                        serviceIntent.putExtra("GAME_PACKAGE", packageName);
                        // บอก service ว่าเกมถูกเปิดบนจอไหน ไม่ต้องเดาจาก dumpsys
                        // done && did>0 = สั่งเปิดบนจอ DeX จริง, นอกนั้นเกมเปิดตามจอที่ MainActivity อยู่ (0 = มือถือ)
                        serviceIntent.putExtra("GAME_DISPLAY", (done && did > 0) ? did : minePre);
                        startService(serviceIntent);
                    }, 1200);
                });
            }).start();
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
