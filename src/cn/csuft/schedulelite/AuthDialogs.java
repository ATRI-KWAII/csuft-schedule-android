package cn.csuft.schedulelite;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.CountDownTimer;
import android.text.InputType;
import android.widget.*;
import java.util.ArrayList;
import java.util.concurrent.ExecutorService;
import org.json.JSONObject;

/** User-driven OTP flows. No code is sent on dialog opening or channel selection. */
final class AuthDialogs {
    interface Success { void accept(ApiClient.LoginResult result); }
    interface Work { Object run() throws Exception; }
    interface Done { void accept(Object result); }
    private final Activity activity;
    private final ApiClient api;
    private final ExecutorService executor;
    private final Success success;
    private AlertDialog dialog;
    private LinearLayout panel;
    private EditText phone, code;
    private TextView status;
    private Button send, submit;
    private boolean busy, closed, cooling;
    private CountDownTimer timer;
    private String nonce = "", sentMobile = "", type = "";
    private JSONObject challenge;
    private ApiClient.MfaRequired pending;

    AuthDialogs(Activity activity, ApiClient api, ExecutorService executor, Success success) {
        this.activity = activity; this.api = api; this.executor = executor; this.success = success;
    }

    private void create(String title, boolean sms) {
        panel = new LinearLayout(activity);
        panel.setOrientation(LinearLayout.VERTICAL);
        int padding = (int)(20 * activity.getResources().getDisplayMetrics().density);
        panel.setPadding(padding, padding, padding, padding);
        if (sms) {
            phone = new EditText(activity); phone.setHint("绑定的安全手机号");
            phone.setInputType(InputType.TYPE_CLASS_PHONE); panel.addView(phone);
        }
        status = new TextView(activity); status.setTextSize(14); panel.addView(status);
        code = new EditText(activity); code.setHint("验证码");
        code.setSingleLine(true); code.setInputType(InputType.TYPE_CLASS_NUMBER);
        panel.addView(code);
        send = new Button(activity); send.setText("获取验证码"); panel.addView(send);
        submit = new Button(activity); submit.setText("验证并登录"); panel.addView(submit);
        dialog = new AlertDialog.Builder(activity).setTitle(title).setView(panel)
                .setNegativeButton("取消", (d,w) -> {}).create();
        dialog.setOnDismissListener(d -> {
            closed = true;
            if (timer != null) timer.cancel();
            // Tasks may still be finishing. Clear credentials on the same serial executor.
            final ApiClient.MfaRequired toClear = pending;
            if (toClear != null && !executor.isShutdown()) executor.execute(toClear::clear);
            nonce = ""; code.setText("");
        });
        dialog.show();
    }

    void sms() {
        create("短信验证码登录", true);
        status.setText("使用学校账号绑定的手机号，点击获取验证码后发送短信。");
        submit.setEnabled(false);
        send.setOnClickListener(v -> {
            String mobile = phone.getText().toString().trim();
            if (!mobile.matches("1[0-9]{10}")) { status.setText("请输入 11 位手机号"); return; }
            nonce = "";
            task(() -> api.sendSmsLogin(mobile), result -> {
                nonce = (String)result; sentMobile = mobile;
                status.setText("短信已发送，请输入验证码"); cooldown();
            });
        });
        submit.setOnClickListener(v -> {
            String mobile = phone.getText().toString().trim();
            String otp = code.getText().toString().trim();
            if (nonce.isEmpty() || !mobile.equals(sentMobile)) { status.setText("手机号发生变化，请重新获取验证码"); return; }
            if (otp.isEmpty()) { status.setText("请输入验证码"); return; }
            String loginNonce = nonce;
            task(() -> api.smsLogin(mobile, otp, loginNonce), this::finish);
        });
    }

    void mfa(ApiClient.MfaRequired pending) {
        this.pending = pending;
        ArrayList<String> types = new ArrayList<>(), labels = new ArrayList<>();
        if (pending.detection.optBoolean("mfaTypeSecurePhone")) { types.add("securephone"); labels.add("安全手机短信验证"); }
        if (pending.detection.optBoolean("mfaTypeSecureEmail")) { types.add("secureemail"); labels.add("安全邮箱验证"); }
        if (types.isEmpty()) {
            pending.clear();
            new AlertDialog.Builder(activity).setTitle("需要额外认证")
                    .setMessage("学校要求的认证方式暂非手机或邮箱验证码。可尝试短信登录，或使用官方客户端完成所需认证。")
                    .setPositiveButton("知道了", null).show(); return;
        }
        if (types.size() == 1) { openMfa(types.get(0)); return; }
        new AlertDialog.Builder(activity).setTitle("选择二次验证方式")
                .setItems(labels.toArray(new String[0]), (d, which) -> openMfa(types.get(which)))
                .setOnCancelListener(d -> pending.clear()).show();
    }

    private void openMfa(String selectedType) {
        type = selectedType;
        create(type.equals("securephone") ? "安全手机验证" : "安全邮箱验证", false);
        task(() -> api.initMfa(pending, type), result -> {
            challenge = (JSONObject)result;
            status.setText("验证码将发送至：" + challenge.optString(type.equals("securephone") ? "securePhone" : "secureEmail", "已绑定的联系方式"));
        });
        send.setOnClickListener(v -> {
            if (challenge == null) {
                task(() -> api.initMfa(pending, type), result -> {
                    challenge = (JSONObject)result;
                    status.setText("认证已初始化，请再次点击获取验证码");
                }); return;
            }
            task(() -> { api.sendMfa(challenge, type); return null; }, result -> {
                status.setText("验证码已发送"); cooldown();
            });
        });
        submit.setOnClickListener(v -> {
            String otp = code.getText().toString().trim();
            if (challenge == null || otp.isEmpty()) { status.setText("请先获取并输入验证码"); return; }
            task(() -> api.completeMfa(pending, challenge, type, otp), this::finish);
        });
    }

    private void task(Work work, Done done) {
        if (busy || closed) return;
        busy = true; updateButtons(); status.setText("正在处理…");
        dialog.setCancelable(false); dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setEnabled(false);
        executor.execute(() -> {
            Object value = null; Exception failure = null;
            try { value = work.run(); } catch (Exception error) { failure = error; }
            final Object result = value; final Exception error = failure;
            activity.runOnUiThread(() -> {
                if (closed || activity.isFinishing() || activity.isDestroyed()) {
                    if (pending != null) pending.clear(); return;
                }
                busy = false; dialog.setCancelable(true); dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setEnabled(true);
                if (error == null) done.accept(result);
                else status.setText(error.getMessage() == null ? "请求失败，请重试" : error.getMessage());
                updateButtons();
            });
        });
    }

    private void finish(Object result) {
        dialog.dismiss(); success.accept((ApiClient.LoginResult) result);
    }

    private void updateButtons() {
        send.setEnabled(!busy && !cooling);
        submit.setEnabled(!busy && (phone != null ? !nonce.isEmpty() : challenge != null));
        if (phone != null) phone.setEnabled(!busy);
        code.setEnabled(!busy);
    }

    private void cooldown() {
        cooling = true;
        timer = new CountDownTimer(60000,1000) {
            public void onTick(long millis) { send.setText((millis / 1000 + 1) + " 秒后重发"); }
            public void onFinish() { cooling = false; send.setText("重新获取验证码"); updateButtons(); }
        }.start();
    }
}
