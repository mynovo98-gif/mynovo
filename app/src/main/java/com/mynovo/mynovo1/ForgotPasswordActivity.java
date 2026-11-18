package com.mynovo.mynovo1;

import android.graphics.Color;
import android.os.Bundle;
import android.text.InputType;
import android.util.Patterns;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.google.firebase.auth.FirebaseAuth;

/**
 * Sends a Firebase password-reset email to the address entered.
 */
public class ForgotPasswordActivity extends AppCompatActivity {

    private FirebaseAuth mAuth;
    private EditText etEmail;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mAuth = FirebaseAuth.getInstance();
        setContentView(buildUi());
    }

    private ScrollView buildUi() {
        ScrollView scrollView = new ScrollView(this);
        scrollView.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        scrollView.setBackgroundColor(Color.parseColor("#161925"));

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        root.setPadding(60, 80, 60, 80);

        TextView tvTitle = new TextView(this);
        tvTitle.setText("Reset Password");
        tvTitle.setTextColor(Color.WHITE);
        tvTitle.setTextSize(20f);
        tvTitle.setTypeface(null, android.graphics.Typeface.BOLD);
        tvTitle.setGravity(Gravity.CENTER);
        tvTitle.setPadding(0, 0, 0, 16);
        root.addView(tvTitle);

        TextView tvSubtitle = new TextView(this);
        tvSubtitle.setText("Enter your email and we'll send you a reset link.");
        tvSubtitle.setTextColor(Color.parseColor("#8BE9FD"));
        tvSubtitle.setTextSize(13f);
        tvSubtitle.setGravity(Gravity.CENTER);
        tvSubtitle.setPadding(0, 0, 0, 30);
        root.addView(tvSubtitle);

        etEmail = new EditText(this);
        etEmail.setHint("Email");
        etEmail.setHintTextColor(Color.parseColor("#6272A4"));
        etEmail.setTextColor(Color.WHITE);
        etEmail.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS);
        etEmail.setPadding(24, 24, 24, 24);
        etEmail.setBackgroundColor(Color.parseColor("#1E2230"));
        LinearLayout.LayoutParams fieldParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        fieldParams.setMargins(0, 0, 0, 24);
        etEmail.setLayoutParams(fieldParams);
        root.addView(etEmail);

        Button btnSend = new Button(this);
        btnSend.setText("SEND RESET LINK");
        btnSend.setTextColor(Color.WHITE);
        btnSend.setBackgroundColor(Color.parseColor("#BD93F9"));
        btnSend.setLayoutParams(fieldParams);
        btnSend.setOnClickListener(v -> attemptReset());
        root.addView(btnSend);

        TextView tvBack = new TextView(this);
        tvBack.setText("Back to Login");
        tvBack.setTextColor(Color.parseColor("#8BE9FD"));
        tvBack.setTextSize(13f);
        tvBack.setGravity(Gravity.CENTER);
        tvBack.setPadding(0, 10, 0, 0);
        tvBack.setOnClickListener(v -> finish());
        root.addView(tvBack);

        scrollView.addView(root);
        return scrollView;
    }

    private void attemptReset() {
        String email = etEmail.getText().toString().trim();
        if (email.isEmpty() || !Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
            etEmail.setError("Enter a valid email");
            return;
        }

        mAuth.sendPasswordResetEmail(email).addOnCompleteListener(task -> {
            if (task.isSuccessful()) {
                Toast.makeText(this, "✅ Reset link sent! Check your inbox.", Toast.LENGTH_LONG).show();
                finish();
            } else {
                String msg = task.getException() != null ? task.getException().getMessage() : "Failed to send reset email";
                Toast.makeText(this, "❌ " + msg, Toast.LENGTH_LONG).show();
            }
        });
    }
}
