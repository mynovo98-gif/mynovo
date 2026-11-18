package com.mynovo.mynovo1;

import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.text.InputType;
import android.util.Patterns;
import android.view.Gravity;
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
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;

import com.google.android.gms.auth.api.signin.GoogleSignIn;
import com.google.android.gms.auth.api.signin.GoogleSignInAccount;
import com.google.android.gms.auth.api.signin.GoogleSignInClient;
import com.google.android.gms.auth.api.signin.GoogleSignInOptions;
import com.google.android.gms.common.SignInButton;
import com.google.android.gms.common.api.ApiException;
import com.google.android.gms.tasks.Task;
import com.google.firebase.auth.AuthCredential;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.auth.GoogleAuthProvider;

/**
 * Login screen: email/password sign-in + Google sign-in.
 * Links to RegisterActivity and ForgotPasswordActivity.
 *
 * NOTE: replace YOUR_WEB_CLIENT_ID below with the "Web client ID" from
 * Firebase Console > Project settings > General > Your apps (or the
 * auto-generated default_web_client_id string resource once google-services.json is added).
 */
public class LoginActivity extends AppCompatActivity {

    private FirebaseAuth mAuth;
    private GoogleSignInClient googleSignInClient;
    private EditText etEmail, etPassword;
    private ProgressBar progressBar;

    private final ActivityResultLauncher<Intent> googleSignInLauncher =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
                Task<GoogleSignInAccount> task = GoogleSignIn.getSignedInAccountFromIntent(result.getData());
                try {
                    GoogleSignInAccount account = task.getResult(ApiException.class);
                    firebaseAuthWithGoogle(account.getIdToken());
                } catch (ApiException e) {
                    Toast.makeText(this, "Google Sign-In failed: " + e.getStatusCode(), Toast.LENGTH_SHORT).show();
                }
            });

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mAuth = FirebaseAuth.getInstance();

        GoogleSignInOptions gso = new GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
                .requestIdToken(getString(R.string.default_web_client_id))
                .requestEmail()
                .build();
        googleSignInClient = GoogleSignIn.getClient(this, gso);

        setContentView(buildUi());
    }

    @Override
    protected void onStart() {
        super.onStart();
        // If already logged in, skip straight to MainActivity
        FirebaseUser currentUser = mAuth.getCurrentUser();
        if (currentUser != null) {
            goToMain();
        }
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
        tvTitle.setText("🛡️ Blue Mouse Pro");
        tvTitle.setTextColor(Color.WHITE);
        tvTitle.setTextSize(22f);
        tvTitle.setTypeface(null, android.graphics.Typeface.BOLD);
        tvTitle.setGravity(Gravity.CENTER);
        tvTitle.setPadding(0, 0, 0, 10);
        root.addView(tvTitle);

        TextView tvSubtitle = new TextView(this);
        tvSubtitle.setText("Sign in to your account");
        tvSubtitle.setTextColor(Color.parseColor("#8BE9FD"));
        tvSubtitle.setTextSize(14f);
        tvSubtitle.setGravity(Gravity.CENTER);
        tvSubtitle.setPadding(0, 0, 0, 40);
        root.addView(tvSubtitle);

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
        etPassword.setHint("Password");
        etPassword.setHintTextColor(Color.parseColor("#6272A4"));
        etPassword.setTextColor(Color.WHITE);
        etPassword.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        etPassword.setPadding(24, 24, 24, 24);
        etPassword.setBackgroundColor(Color.parseColor("#1E2230"));
        etPassword.setLayoutParams(fieldParams());
        root.addView(etPassword);

        TextView tvForgot = new TextView(this);
        tvForgot.setText("Forgot password?");
        tvForgot.setTextColor(Color.parseColor("#BD93F9"));
        tvForgot.setTextSize(12f);
        tvForgot.setGravity(Gravity.END);
        LinearLayout.LayoutParams forgotParams = fieldParams();
        forgotParams.setMargins(0, 0, 0, 30);
        tvForgot.setLayoutParams(forgotParams);
        tvForgot.setOnClickListener(v -> startActivity(new Intent(this, ForgotPasswordActivity.class)));
        root.addView(tvForgot);

        Button btnLogin = new Button(this);
        btnLogin.setText("LOG IN");
        btnLogin.setTextColor(Color.WHITE);
        btnLogin.setBackgroundColor(Color.parseColor("#50FA7B"));
        btnLogin.setLayoutParams(fieldParams());
        btnLogin.setOnClickListener(v -> attemptLogin());
        root.addView(btnLogin);

        progressBar = new ProgressBar(this);
        LinearLayout.LayoutParams pbParams = fieldParams();
        pbParams.gravity = Gravity.CENTER;
        progressBar.setLayoutParams(pbParams);
        progressBar.setVisibility(android.view.View.GONE);
        root.addView(progressBar);

        TextView tvOr = new TextView(this);
        tvOr.setText("— or —");
        tvOr.setTextColor(Color.parseColor("#6272A4"));
        tvOr.setGravity(Gravity.CENTER);
        tvOr.setPadding(0, 10, 0, 20);
        root.addView(tvOr);

        SignInButton googleBtn = new SignInButton(this);
        googleBtn.setSize(SignInButton.SIZE_WIDE);
        LinearLayout.LayoutParams googleParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        googleParams.gravity = Gravity.CENTER;
        googleParams.setMargins(0, 0, 0, 30);
        googleBtn.setLayoutParams(googleParams);
        googleBtn.setOnClickListener(v -> googleSignInLauncher.launch(googleSignInClient.getSignInIntent()));
        root.addView(googleBtn);

        TextView tvSignUp = new TextView(this);
        tvSignUp.setText("Don't have an account? Sign up");
        tvSignUp.setTextColor(Color.parseColor("#8BE9FD"));
        tvSignUp.setTextSize(13f);
        tvSignUp.setGravity(Gravity.CENTER);
        tvSignUp.setOnClickListener(v -> startActivity(new Intent(this, RegisterActivity.class)));
        root.addView(tvSignUp);

        scrollView.addView(root);
        return scrollView;
    }

    private void attemptLogin() {
        String email = etEmail.getText().toString().trim();
        String password = etPassword.getText().toString().trim();

        if (email.isEmpty() || !Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
            etEmail.setError("Enter a valid email");
            return;
        }
        if (password.isEmpty()) {
            etPassword.setError("Enter your password");
            return;
        }

        setLoading(true);
        mAuth.signInWithEmailAndPassword(email, password)
                .addOnCompleteListener(this, task -> {
                    setLoading(false);
                    if (task.isSuccessful()) {
                        FirebaseUser user = mAuth.getCurrentUser();
                        if (user != null && !user.isEmailVerified()) {
                            Toast.makeText(this, "⚠️ Please verify your email first. Check your inbox.", Toast.LENGTH_LONG).show();
                        }
                        goToMain();
                    } else {
                        String msg = task.getException() != null ? task.getException().getMessage() : "Login failed";
                        Toast.makeText(this, "❌ " + msg, Toast.LENGTH_LONG).show();
                    }
                });
    }

    private void firebaseAuthWithGoogle(String idToken) {
        setLoading(true);
        AuthCredential credential = GoogleAuthProvider.getCredential(idToken, null);
        mAuth.signInWithCredential(credential)
                .addOnCompleteListener(this, task -> {
                    setLoading(false);
                    if (task.isSuccessful()) {
                        goToMain();
                    } else {
                        Toast.makeText(this, "❌ Google authentication failed", Toast.LENGTH_SHORT).show();
                    }
                });
    }

    private void setLoading(boolean loading) {
        progressBar.setVisibility(loading ? android.view.View.VISIBLE : android.view.View.GONE);
    }

    private void goToMain() {
        Intent intent = new Intent(this, MainActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        startActivity(intent);
        finish();
    }
}
