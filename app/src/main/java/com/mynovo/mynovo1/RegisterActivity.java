package com.mynovo.mynovo1;

import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.text.InputType;
import android.util.Patterns;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;

/**
 * Registration screen: creates account with email/password,
 * then sends a verification email before returning to Login.
 */
public class RegisterActivity extends AppCompatActivity {

    private FirebaseAuth mAuth;
    private EditText etEmail, etPassword, etConfirmPassword;
    private ProgressBar progressBar;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mAuth = FirebaseAuth.getInstance();
        setContentView(buildUi());
    }

    private LinearLayout.LayoutParams fieldParams() {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        p.setMargins(0, 0, 0, 20);
        return p;
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
        tvTitle.setText("Create Account");
        tvTitle.setTextColor(Color.WHITE);
        tvTitle.setTextSize(20f);
        tvTitle.setTypeface(null, android.graphics.Typeface.BOLD);
        tvTitle.setGravity(Gravity.CENTER);
        tvTitle.setPadding(0, 0, 0, 40);
        root.addView(tvTitle);

        etEmail = new EditText(this);
        etEmail.setHint("Email");
        etEmail.setHintTextColor(Color.parseColor("#6272A4"));
        etEmail.setTextColor(Color.WHITE);
        etEmail.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS);
        etEmail.setPadding(24, 24, 24, 24);
        etEmail.setBackgroundColor(Color.parseColor("#1E2230"));
        etEmail.setLayoutParams(fieldParams());
        root.addView(etEmail);

        etPassword = new EditText(this);
        etPassword.setHint("Password (min. 6 characters)");
        etPassword.setHintTextColor(Color.parseColor("#6272A4"));
        etPassword.setTextColor(Color.WHITE);
        etPassword.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        etPassword.setPadding(24, 24, 24, 24);
        etPassword.setBackgroundColor(Color.parseColor("#1E2230"));
        etPassword.setLayoutParams(fieldParams());
        root.addView(etPassword);

        etConfirmPassword = new EditText(this);
        etConfirmPassword.setHint("Confirm Password");
        etConfirmPassword.setHintTextColor(Color.parseColor("#6272A4"));
        etConfirmPassword.setTextColor(Color.WHITE);
        etConfirmPassword.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        etConfirmPassword.setPadding(24, 24, 24, 24);
        etConfirmPassword.setBackgroundColor(Color.parseColor("#1E2230"));
        etConfirmPassword.setLayoutParams(fieldParams());
        root.addView(etConfirmPassword);

        Button btnRegister = new Button(this);
        btnRegister.setText("SIGN UP");
        btnRegister.setTextColor(Color.WHITE);
        btnRegister.setBackgroundColor(Color.parseColor("#50FA7B"));
        LinearLayout.LayoutParams btnParams = fieldParams();
        btnParams.setMargins(0, 10, 0, 20);
        btnRegister.setLayoutParams(btnParams);
        btnRegister.setOnClickListener(v -> attemptRegister());
        root.addView(btnRegister);

        progressBar = new ProgressBar(this);
        LinearLayout.LayoutParams pbParams = fieldParams();
        pbParams.gravity = Gravity.CENTER;
        progressBar.setLayoutParams(pbParams);
        progressBar.setVisibility(View.GONE);
        root.addView(progressBar);

        TextView tvLogin = new TextView(this);
        tvLogin.setText("Already have an account? Log in");
        tvLogin.setTextColor(Color.parseColor("#8BE9FD"));
        tvLogin.setTextSize(13f);
        tvLogin.setGravity(Gravity.CENTER);
        tvLogin.setOnClickListener(v -> finish());
        root.addView(tvLogin);

        scrollView.addView(root);
        return scrollView;
    }

    private void attemptRegister() {
        String email = etEmail.getText().toString().trim();
        String password = etPassword.getText().toString().trim();
        String confirm = etConfirmPassword.getText().toString().trim();

        if (email.isEmpty() || !Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
            etEmail.setError("Enter a valid email");
            return;
        }
        if (password.length() < 6) {
            etPassword.setError("Password must be at least 6 characters");
            return;
        }
        if (!password.equals(confirm)) {
            etConfirmPassword.setError("Passwords do not match");
            return;
        }

        setLoading(true);
        mAuth.createUserWithEmailAndPassword(email, password)
                .addOnCompleteListener(this, task -> {
                    if (task.isSuccessful()) {
                        sendVerificationEmail();
                    } else {
                        setLoading(false);
                        String msg = task.getException() != null ? task.getException().getMessage() : "Registration failed";
                        Toast.makeText(this, "❌ " + msg, Toast.LENGTH_LONG).show();
                    }
                });
    }

    private void sendVerificationEmail() {
        FirebaseUser user = mAuth.getCurrentUser();
        if (user == null) {
            setLoading(false);
            return;
        }
        user.sendEmailVerification().addOnCompleteListener(task -> {
            setLoading(false);
            if (task.isSuccessful()) {
                Toast.makeText(this, "✅ Account created! Verification email sent — please check your inbox.", Toast.LENGTH_LONG).show();
            } else {
                Toast.makeText(this, "Account created, but failed to send verification email.", Toast.LENGTH_LONG).show();
            }
            // Sign out until the user verifies & logs in explicitly
            mAuth.signOut();
            Intent intent = new Intent(this, LoginActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            startActivity(intent);
            finish();
        });
    }

    private void setLoading(boolean loading) {
        progressBar.setVisibility(loading ? View.VISIBLE : View.GONE);
    }
}
