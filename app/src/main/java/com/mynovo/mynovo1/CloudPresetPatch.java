// =====================================================================
//  PATCH สำหรับ FloatingService.java  (มี 4 จุดที่ต้องแก้)
//  - พรีเซ็ต 3 ช่อง (พรีเซ็ต 1 ใช้คีย์เดิม ปุ่มเก่าของคุณไม่หาย)
//  - ระบบโค้ดแชร์ปุ่ม (คัดลอกโค้ด / นำเข้าโค้ด) ปรับตำแหน่งตามขนาดจออัตโนมัติ
//  - หน้าต่าง Cloud Preset ดีไซน์ใหม่
// =====================================================================


// ---------------------------------------------------------------------
// [จุดที่ 1] เพิ่ม field ไว้ใกล้ ๆ "private View cloudView;"
// ---------------------------------------------------------------------
    private int presetSlot = 0;                       // 0,1,2 = พรีเซ็ต 1,2,3
    private final Button[] btnSlot = new Button[3];
    private EditText etShareCode;


// ---------------------------------------------------------------------
// [จุดที่ 2] ใน onCreate() ในบล็อก try เดียวกับ fps_target_hz เพิ่มบรรทัดนี้
// ---------------------------------------------------------------------
            presetSlot = Math.max(0, Math.min(2, cp.getInt("preset_slot", 0)));


// ---------------------------------------------------------------------
// [จุดที่ 3] แทนที่เมธอด presetKey() เดิมทั้งเมธอด
// ---------------------------------------------------------------------
    private String presetKey(boolean external) {
        return targetPackage + (external ? "_preset_dex" : "_preset")
                + (presetSlot > 0 ? "_s" + presetSlot : "");
    }


// ---------------------------------------------------------------------
// [จุดที่ 4] ลบเมธอด createCloudWindow() เดิมทิ้ง แล้ววางโค้ดทั้งหมดด้านล่างนี้แทน
//            (เมธอด createSubMenuOption() เดิมเก็บไว้ได้ ยังมีที่อื่นใช้)
// ---------------------------------------------------------------------

    private static final String SHARE_PREFIX = "MNV1:";
    private static final int SHARE_MAX_BUTTONS = 60;

    private void createCloudWindow() {
        try {
            LinearLayout card = new LinearLayout(this);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setPadding(dpToPx(16), dpToPx(14), dpToPx(16), dpToPx(10));
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(Color.parseColor("#151823"));
            bg.setCornerRadius(dpToPx(20));
            bg.setStroke(dpToPx(1), Color.parseColor("#2E3447"));
            card.setBackground(bg);

            TextView tvTitle = new TextView(this);
            tvTitle.setText("☁️ Cloud Preset");
            tvTitle.setTextColor(Color.parseColor("#8BE9FD"));
            tvTitle.setTextSize(16f);
            tvTitle.setTypeface(null, android.graphics.Typeface.BOLD);
            tvTitle.setGravity(Gravity.CENTER);
            tvTitle.setPadding(0, 0, 0, dpToPx(6));
            card.addView(tvTitle);

            android.widget.ScrollView sv = makeCappedScrollView(dpToPx(170));
            LinearLayout body = new LinearLayout(this);
            body.setOrientation(LinearLayout.VERTICAL);
            sv.addView(body, new android.widget.ScrollView.LayoutParams(
                    android.widget.ScrollView.LayoutParams.MATCH_PARENT,
                    android.widget.ScrollView.LayoutParams.WRAP_CONTENT));
            card.addView(sv, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

            // ---- เลือกพรีเซ็ต 3 ช่อง ----
            body.addView(cloudLabel("เลือกพรีเซ็ต"));
            LinearLayout slotRow = cloudRow();
            for (int i = 0; i < 3; i++) {
                final int slot = i;
                btnSlot[i] = cloudButton("พรีเซ็ต " + (i + 1), "#8BE9FD", v -> switchPresetSlot(slot));
                slotRow.addView(btnSlot[i]);
            }
            body.addView(slotRow);
            refreshSlotButtons();

            body.addView(cloudDivider());

            // ---- บันทึก / โหลด ----
            body.addView(cloudLabel("จัดการปุ่ม"));
            LinearLayout saveRow = cloudRow();
            saveRow.addView(cloudButton("💾 บันทึก", "#50FA7B", v -> {
                savePresetToCloud();
                Toast.makeText(this, "✅ บันทึกลงพรีเซ็ต " + (presetSlot + 1) + " แล้ว",
                        Toast.LENGTH_SHORT).show();
                setCloudFocusable(false);
                closeSubWindows();
                updateEditModeUI();
            }));
            saveRow.addView(cloudButton("📂 โหลด", "#BD93F9", v -> {
                loadPresetFromCloud();
                Toast.makeText(this, "📂 โหลดพรีเซ็ต " + (presetSlot + 1) + " แล้ว",
                        Toast.LENGTH_SHORT).show();
                setCloudFocusable(false);
                closeSubWindows();
                updateEditModeUI();
            }));
            body.addView(saveRow);

            body.addView(cloudDivider());

            // ---- แชร์ปุ่มด้วยโค้ด ----
            body.addView(cloudLabel("แชร์ปุ่มด้วยโค้ด"));
            LinearLayout shareRow = cloudRow();
            shareRow.addView(cloudButton("📤 คัดลอกโค้ด", "#F1FA8C", v -> onCopyShareCode()));
            shareRow.addView(cloudButton("📥 นำเข้าโค้ด", "#FFB86C", v -> onImportShareCode()));
            body.addView(shareRow);

            etShareCode = new EditText(this);
            etShareCode.setHint("วางโค้ดที่นี่ (แตะช่อง แล้วกดวาง)");
            etShareCode.setHintTextColor(Color.parseColor("#5C6280"));
            etShareCode.setTextColor(Color.WHITE);
            etShareCode.setTextSize(11f);
            etShareCode.setMaxLines(3);
            etShareCode.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
            etShareCode.setImeOptions(EditorInfo.IME_ACTION_DONE);
            etShareCode.setPadding(dpToPx(10), dpToPx(8), dpToPx(10), dpToPx(8));
            GradientDrawable etBg = new GradientDrawable();
            etBg.setColor(Color.parseColor("#0F111A"));
            etBg.setCornerRadius(dpToPx(8));
            etBg.setStroke(dpToPx(1), Color.parseColor("#2E3447"));
            etShareCode.setBackground(etBg);
            LinearLayout.LayoutParams etLp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            etLp.setMargins(dpToPx(4), dpToPx(6), dpToPx(4), dpToPx(4));
            etShareCode.setLayoutParams(etLp);
            etShareCode.setOnEditorActionListener((tv, actionId, ev) -> {
                etShareCode.clearFocus();
                setCloudFocusable(false);
                return true;
            });
            etShareCode.setOnFocusChangeListener((v, has) -> { if (!has) setCloudFocusable(false); });
            etShareCode.setOnTouchListener((v, ev) -> {
                if (ev.getAction() == MotionEvent.ACTION_DOWN) {
                    setCloudFocusable(true);
                    etShareCode.post(etShareCode::requestFocus);
                }
                return false;
            });
            body.addView(etShareCode);

            // ---- แถบล่าง: รีเซ็ต / ปิด (ติดอยู่ล่างเสมอ ไม่เลื่อนไปกับเนื้อหา) ----
            card.addView(cloudDivider());
            LinearLayout footer = cloudRow();
            footer.addView(cloudButton("🗑️ รีเซ็ตปุ่ม", "#FF5555", v -> {
                setCloudFocusable(false);
                showWarningDialog(
                        "🗑️ รีเซ็ตปุ่มพรีเซ็ต " + (presetSlot + 1),
                        "ปุ่มทั้งหมดบนหน้าจอของเกมนี้ในพรีเซ็ต " + (presetSlot + 1)
                                + " จะถูกลบ (พรีเซ็ตอื่นไม่ถูกแตะ)",
                        () -> {
                            resetAllMappingButtons();
                            Toast.makeText(this, "🗑️ รีเซ็ตปุ่มเรียบร้อย!", Toast.LENGTH_SHORT).show();
                            if (cloudView != null) cloudView.setVisibility(View.GONE);
                            if (menuView != null) menuView.setVisibility(View.VISIBLE);
                            updateEditModeUI();
                        });
            }));
            footer.addView(cloudButton("✕ ปิด", "#A0A5C0", v -> {
                if (isWaitingForKey) cancelKeyWait();
                setCloudFocusable(false);
                cloudView.setVisibility(View.GONE);
                menuView.setVisibility(View.VISIBLE);
                updateEditModeUI();
            }));
            card.addView(footer);

            cloudParams = new WindowManager.LayoutParams(
                    dpToPx(360), WindowManager.LayoutParams.WRAP_CONTENT, overlayType(),
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT);
            cloudParams.gravity = Gravity.CENTER;

            cloudView = card;
            cloudView.setVisibility(View.GONE);
            windowManager.addView(cloudView, cloudParams);
        } catch (Exception e) {}
    }

    // ---------- UI helpers ----------

    private TextView cloudLabel(String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextColor(Color.parseColor("#8C92A4"));
        tv.setTextSize(12f);
        tv.setPadding(dpToPx(4), dpToPx(6), 0, dpToPx(4));
        return tv;
    }

    private LinearLayout cloudRow() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER);
        return row;
    }

    private View cloudDivider() {
        View d = new View(this);
        d.setBackgroundColor(Color.parseColor("#262B3D"));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, Math.max(1, dpToPx(1)));
        lp.setMargins(0, dpToPx(8), 0, dpToPx(4));
        d.setLayoutParams(lp);
        return d;
    }

    private Button cloudButton(String text, String colorHex, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextSize(13f);
        b.setMinHeight(0);
        b.setMinimumHeight(0);
        b.setMinWidth(0);
        b.setMinimumWidth(0);
        b.setPadding(dpToPx(6), 0, dpToPx(6), 0);
        b.setTag(colorHex);
        styleCloudButton(b, false);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dpToPx(40), 1f);
        lp.setMargins(dpToPx(4), dpToPx(2), dpToPx(4), dpToPx(2));
        b.setLayoutParams(lp);
        b.setOnClickListener(l);
        return b;
    }

    private void styleCloudButton(Button b, boolean selected) {
        String hex = b.getTag() instanceof String ? (String) b.getTag() : "#8BE9FD";
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(dpToPx(10));
        if (selected) {
            g.setColor(Color.parseColor(hex));
            b.setTextColor(Color.parseColor("#1B1E2E"));
        } else {
            g.setColor(Color.parseColor("#1E2235"));
            g.setStroke(Math.max(1, dpToPx(1)), Color.parseColor(hex));
            b.setTextColor(Color.parseColor(hex));
        }
        b.setBackground(g);
    }

    private void refreshSlotButtons() {
        for (int i = 0; i < 3; i++) {
            if (btnSlot[i] != null) styleCloudButton(btnSlot[i], i == presetSlot);
        }
    }

    private void setCloudFocusable(boolean on) {
        if (settingsTextEditing == on) return;
        // ตอนพิมพ์/วางโค้ด ต้องปล่อยคีย์บอร์ดคืนระบบ (เหมือนช่องตั้งค่าตัวเลข)
        settingsTextEditing = on;
        updateKbGrab();
        try {
            if (cloudView == null || cloudParams == null || cloudView.getParent() == null) return;
            if (on) {
                cloudParams.flags &= ~WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
                cloudParams.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN;
            } else {
                cloudParams.flags |= WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
            }
            windowManager.updateViewLayout(cloudView, cloudParams);
        } catch (Exception ignored) {}
    }

    // ---------- สลับพรีเซ็ต ----------

    private void switchPresetSlot(int slot) {
        if (slot == presetSlot || slot < 0 || slot > 2) return;
        if (!batchLoading) savePresetToCloud();      // เซฟของช่องเดิมก่อนสลับ
        presetSlot = slot;
        try {
            getSharedPreferences("MynovoCore", MODE_PRIVATE).edit()
                    .putInt("preset_slot", presetSlot).apply();
        } catch (Exception ignored) {}
        refreshSlotButtons();
        loadPresetFromCloud();
        Toast.makeText(this, "☁️ ใช้พรีเซ็ต " + (presetSlot + 1), Toast.LENGTH_SHORT).show();
    }

    // ---------- ระบบโค้ดแชร์ปุ่ม ----------

    private void onCopyShareCode() {
        String code = encodeShareCode();
        if (code == null) {
            Toast.makeText(this, "ยังไม่มีปุ่มให้แชร์ในพรีเซ็ตนี้", Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            android.content.ClipboardManager cm =
                    (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            if (cm != null) cm.setPrimaryClip(
                    android.content.ClipData.newPlainText("MynovoPreset", code));
        } catch (Exception ignored) {}
        if (etShareCode != null) etShareCode.setText(code);
        Toast.makeText(this, "📤 คัดลอกโค้ดแล้ว (ส่งให้เพื่อนได้เลย)", Toast.LENGTH_SHORT).show();
    }

    private void onImportShareCode() {
        String text = etShareCode != null ? etShareCode.getText().toString().trim() : "";
        if (text.isEmpty()) text = readClipboardText();
        if (text.isEmpty()) {
            Toast.makeText(this, "วางโค้ดในช่องก่อน (แตะช่อง แล้วกดวาง)", Toast.LENGTH_LONG).show();
            return;
        }
        final JSONArray parsed = parseShareCode(text);
        if (parsed == null) {
            Toast.makeText(this, "❌ โค้ดไม่ถูกต้อง", Toast.LENGTH_LONG).show();
            return;
        }
        setCloudFocusable(false);
        final int slotNow = presetSlot + 1;
        showWarningDialog(
                "📥 นำเข้าปุ่ม",
                "พบ " + parsed.length() + " ปุ่มในโค้ด\nปุ่มเดิมในพรีเซ็ต " + slotNow
                        + " จะถูกแทนที่ (พรีเซ็ตอื่นไม่ถูกแตะ)",
                () -> {
                    try {
                        SharedPreferences prefs = getSharedPreferences(mappingPrefsName(), MODE_PRIVATE);
                        String key = presetKey(onExternalDisplay());
                        prefs.edit()
                                .putString(key, parsed.toString())
                                .putString(key + "_size", (int) maxScreenWidth + "," + (int) maxScreenHeight)
                                .apply();
                        loadPresetFromCloud();
                        if (etShareCode != null) etShareCode.setText("");
                        Toast.makeText(this, "✅ นำเข้าลงพรีเซ็ต " + slotNow + " สำเร็จ",
                                Toast.LENGTH_SHORT).show();
                        if (cloudView != null) cloudView.setVisibility(View.GONE);
                        if (menuView != null) menuView.setVisibility(View.VISIBLE);
                        updateEditModeUI();
                    } catch (Exception e) {
                        Toast.makeText(this, "❌ นำเข้าไม่สำเร็จ", Toast.LENGTH_SHORT).show();
                    }
                });
    }

    private String readClipboardText() {
        try {
            android.content.ClipboardManager cm =
                    (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            android.content.ClipData d = cm != null ? cm.getPrimaryClip() : null;
            if (d != null && d.getItemCount() > 0) {
                CharSequence t = d.getItemAt(0).coerceToText(this);
                if (t != null) return t.toString().trim();
            }
        } catch (Exception ignored) {}
        return "";
    }

    // สร้างโค้ด: JSON -> GZIP -> Base64 (ขึ้นต้นด้วย MNV1:)
    private String encodeShareCode() {
        try {
            savePresetToCloud(); // ให้ prefs เป็นข้อมูลล่าสุด
            SharedPreferences prefs = getSharedPreferences(mappingPrefsName(), MODE_PRIVATE);
            String arr = prefs.getString(presetKey(onExternalDisplay()), "");
            if (arr == null || arr.isEmpty() || arr.equals("[]")) return null;

            JSONObject o = new JSONObject();
            o.put("v", 1);
            o.put("w", (int) maxScreenWidth);
            o.put("h", (int) maxScreenHeight);
            o.put("b", new JSONArray(arr));

            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            java.util.zip.GZIPOutputStream gz = new java.util.zip.GZIPOutputStream(bos);
            gz.write(o.toString().getBytes("UTF-8"));
            gz.close();
            return SHARE_PREFIX + android.util.Base64.encodeToString(
                    bos.toByteArray(), android.util.Base64.NO_WRAP | android.util.Base64.URL_SAFE);
        } catch (Exception e) {
            return null;
        }
    }

    // ถอดโค้ด + ตรวจความปลอดภัย (โค้ดมาจากคนอื่น) + ปรับตำแหน่งตามขนาดจอเรา
    private JSONArray parseShareCode(String code) {
        try {
            code = code.replaceAll("\\s+", "");
            if (!code.startsWith(SHARE_PREFIX)) return null;
            byte[] data = android.util.Base64.decode(
                    code.substring(SHARE_PREFIX.length()), android.util.Base64.URL_SAFE);
            java.util.zip.GZIPInputStream gin = new java.util.zip.GZIPInputStream(
                    new java.io.ByteArrayInputStream(data));
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n, total = 0;
            while ((n = gin.read(buf)) > 0) {
                total += n;
                if (total > 512 * 1024) return null; // กันไฟล์บวมผิดปกติ
                bos.write(buf, 0, n);
            }
            JSONObject o = new JSONObject(bos.toString("UTF-8"));
            JSONArray src = o.getJSONArray("b");
            int w = o.optInt("w", 0), h = o.optInt("h", 0);
            float sx = (w > 0 && maxScreenWidth > 0) ? maxScreenWidth / w : 1f;
            float sy = (h > 0 && maxScreenHeight > 0) ? maxScreenHeight / h : 1f;

            java.util.Set<String> allowed = new java.util.HashSet<>(java.util.Arrays.asList(
                    "NORMAL", "DPAD", "HIDE_MOUSE", "RAPID_FIRE", "MOUSE_LOCK", "FIRE", "SWIPE", "ZOOM"));
            JSONArray out = new JSONArray();
            boolean hasDpad = false;
            for (int i = 0; i < src.length() && out.length() < SHARE_MAX_BUTTONS; i++) {
                JSONObject b = src.getJSONObject(i);
                String type = b.optString("type", "NORMAL");
                if (!allowed.contains(type)) continue;
                if ("DPAD".equals(type)) { if (hasDpad) continue; hasDpad = true; }
                int x = Math.round(b.getInt("x") * sx);
                int y = Math.round(b.getInt("y") * sy);
                b.put("x", Math.max(0, Math.min((int) maxScreenWidth, x)));
                b.put("y", Math.max(0, Math.min((int) maxScreenHeight, y)));
                out.put(b);
            }
            return out.length() > 0 ? out : null;
        } catch (Exception e) {
            return null;
        }
    }
