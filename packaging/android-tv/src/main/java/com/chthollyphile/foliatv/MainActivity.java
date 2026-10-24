package com.chthollyphile.foliatv;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.os.Build;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.webkit.CookieManager;
import android.webkit.SslErrorHandler;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebStorage;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

// Native Android TV shell that hosts the existing Folia Web application and translates remote keys.
public final class MainActivity extends Activity {
    private static final String PREFS = "folia_tv";
    private static final String PREF_SERVER_URL = "server_url";
    private static final String PREF_ALLOW_INSECURE_TLS = "allow_insecure_tls";
    private static final int FILE_CHOOSER_REQUEST = 4101;

    private FrameLayout root;
    private WebView webView;
    private View settingsView;
    private EditText serverUrlInput;
    private CheckBox insecureTlsCheckbox;
    private TextView settingsStatus;
    private ProgressBar progressBar;
    private ValueCallback<Uri[]> filePathCallback;
    private String tvBridgeScript = "";
    private String loadedUrl = "";
    private boolean settingsVisible;
    private boolean inputActive;
    private boolean allowingInsecureTls;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        hideSystemUi();

        tvBridgeScript = readAsset("tv_bridge.js");
        root = new FrameLayout(this);
        root.setBackgroundColor(Color.rgb(9, 9, 11));
        setContentView(root);

        createWebView();
        createSettingsView();

        String savedUrl = prefs().getString(PREF_SERVER_URL, "");
        if (savedUrl == null || savedUrl.isEmpty()) {
            showSettings();
        } else {
            connectTo(savedUrl);
        }
    }

    private SharedPreferences prefs() {
        return getSharedPreferences(PREFS, MODE_PRIVATE);
    }

    private void createWebView() {
        webView = new WebView(this);
        webView.setBackgroundColor(Color.rgb(9, 9, 11));
        webView.setFocusable(true);
        webView.setFocusableInTouchMode(true);
        webView.setHorizontalScrollBarEnabled(false);
        webView.setVerticalScrollBarEnabled(false);

        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setSupportMultipleWindows(false);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        settings.setBuiltInZoomControls(false);
        settings.setDisplayZoomControls(false);
        settings.setTextZoom(100);
        settings.setCacheMode(WebSettings.LOAD_DEFAULT);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        settings.setUserAgentString(settings.getUserAgentString() + " FoliaTV/1.2.1");

        CookieManager cookies = CookieManager.getInstance();
        cookies.setAcceptCookie(true);
        cookies.setAcceptThirdPartyCookies(webView, true);

        webView.addJavascriptInterface(new TvJsBridge(), "FoliaTvBridge");
        webView.setWebViewClient(new TvWebViewClient());
        webView.setWebChromeClient(new TvWebChromeClient());

        root.addView(webView, new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        ));

        progressBar = new ProgressBar(this, null, android.R.attr.progressBarStyleLarge);
        FrameLayout.LayoutParams progressParams = new FrameLayout.LayoutParams(dp(48), dp(48));
        progressParams.gravity = Gravity.CENTER;
        progressBar.setVisibility(View.GONE);
        root.addView(progressBar, progressParams);
    }

    private void createSettingsView() {
        FrameLayout backdrop = new FrameLayout(this);
        backdrop.setBackgroundColor(Color.argb(248, 9, 9, 11));
        backdrop.setVisibility(View.GONE);

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setGravity(Gravity.CENTER_HORIZONTAL);
        int pad = dp(56);
        panel.setPadding(pad, dp(36), pad, dp(36));

        TextView title = text(getString(R.string.setup_title), 34, R.color.tv_text);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        panel.addView(title, matchWidth());

        TextView hint = text(getString(R.string.setup_hint), 19, R.color.tv_muted);
        hint.setPadding(0, dp(12), 0, dp(22));
        panel.addView(hint, matchWidth());

        serverUrlInput = new EditText(this);
        serverUrlInput.setSingleLine(true);
        serverUrlInput.setTextSize(TypedValue.COMPLEX_UNIT_SP, 21);
        serverUrlInput.setTextColor(getColorCompat(R.color.tv_text));
        serverUrlInput.setHintTextColor(getColorCompat(R.color.tv_muted));
        serverUrlInput.setHint(R.string.server_url_example);
        serverUrlInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        serverUrlInput.setImeOptions(EditorInfo.IME_ACTION_DONE);
        serverUrlInput.setSelectAllOnFocus(false);
        serverUrlInput.setPadding(dp(18), dp(10), dp(18), dp(10));
        panel.addView(serverUrlInput, matchWidth());

        insecureTlsCheckbox = new CheckBox(this);
        insecureTlsCheckbox.setText(R.string.allow_insecure_tls);
        insecureTlsCheckbox.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        insecureTlsCheckbox.setTextColor(getColorCompat(R.color.tv_text));
        insecureTlsCheckbox.setPadding(0, dp(18), 0, 0);
        panel.addView(insecureTlsCheckbox, matchWidth());

        TextView tlsWarning = text(getString(R.string.allow_insecure_tls_warning), 15, R.color.tv_muted);
        panel.addView(tlsWarning, matchWidth());

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setGravity(Gravity.CENTER_VERTICAL);
        actions.setPadding(0, dp(24), 0, 0);

        Button connect = button(getString(R.string.connect));
        connect.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                saveAndConnect();
            }
        });
        actions.addView(connect, new LinearLayout.LayoutParams(0, dp(54), 1f));

        Button cancel = button(getString(R.string.cancel));
        cancel.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                if (loadedUrl.isEmpty()) {
                    finish();
                } else {
                    hideSettings();
                }
            }
        });
        LinearLayout.LayoutParams cancelParams = new LinearLayout.LayoutParams(0, dp(54), 1f);
        cancelParams.setMargins(dp(18), 0, 0, 0);
        actions.addView(cancel, cancelParams);

        Button clear = button(getString(R.string.clear_web_data));
        clear.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                clearWebData();
            }
        });
        LinearLayout.LayoutParams clearParams = new LinearLayout.LayoutParams(0, dp(54), 1f);
        clearParams.setMargins(dp(18), 0, 0, 0);
        actions.addView(clear, clearParams);
        panel.addView(actions, matchWidth());

        settingsStatus = text("", 16, R.color.tv_muted);
        settingsStatus.setPadding(0, dp(18), 0, 0);
        settingsStatus.setGravity(Gravity.CENTER);
        panel.addView(settingsStatus, matchWidth());

        TextView remoteHint = text(getString(R.string.settings_hint), 15, R.color.tv_muted);
        remoteHint.setGravity(Gravity.CENTER);
        remoteHint.setPadding(0, dp(18), 0, 0);
        panel.addView(remoteHint, matchWidth());

        scroll.addView(panel, new ScrollView.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ));
        backdrop.addView(scroll, new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        ));
        root.addView(backdrop, new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        ));
        settingsView = backdrop;

        serverUrlInput.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            @Override
            public boolean onEditorAction(TextView view, int actionId, KeyEvent event) {
                if (actionId == EditorInfo.IME_ACTION_DONE) {
                    saveAndConnect();
                    return true;
                }
                return false;
            }
        });
    }

    private TextView text(String value, int sizeSp, int colorResource) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp);
        view.setTextColor(getColorCompat(colorResource));
        return view;
    }

    private Button button(String value) {
        Button button = new Button(this);
        button.setText(value);
        button.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        button.setAllCaps(false);
        return button;
    }

    private LinearLayout.LayoutParams matchWidth() {
        return new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        );
    }

    private int getColorCompat(int resource) {
        if (Build.VERSION.SDK_INT >= 23) {
            return getColor(resource);
        }
        return getResources().getColor(resource);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private void showSettings() {
        settingsVisible = true;
        String savedUrl = prefs().getString(PREF_SERVER_URL, "");
        serverUrlInput.setText(savedUrl == null ? "" : savedUrl);
        insecureTlsCheckbox.setChecked(prefs().getBoolean(PREF_ALLOW_INSECURE_TLS, false));
        settingsStatus.setText(loadedUrl.isEmpty() ? "" : settingsStatus.getText());
        settingsView.setVisibility(View.VISIBLE);
        serverUrlInput.requestFocus();
        serverUrlInput.postDelayed(new Runnable() {
            @Override
            public void run() {
                serverUrlInput.setSelection(serverUrlInput.length());
                if (Build.VERSION.SDK_INT < 30) {
                    getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE);
                }
            }
        }, 150);
    }

    private void hideSettings() {
        settingsVisible = false;
        inputActive = false;
        settingsView.setVisibility(View.GONE);
        webView.requestFocus();
        hideSystemUi();
        applyTvBridge();
    }

    private void saveAndConnect() {
        String normalized = normalizeServerUrl(serverUrlInput.getText().toString());
        if (normalized == null) {
            settingsStatus.setText(R.string.invalid_server_url);
            serverUrlInput.requestFocus();
            return;
        }

        prefs().edit()
            .putString(PREF_SERVER_URL, normalized)
            .putBoolean(PREF_ALLOW_INSECURE_TLS, insecureTlsCheckbox.isChecked())
            .apply();
        connectTo(normalized);
    }

    static String normalizeServerUrl(String raw) {
        if (raw == null) return null;
        String value = raw.trim();
        if (value.isEmpty()) return null;
        if (!value.matches("(?i)^https?://.*")) {
            value = "http://" + value;
        }
        try {
            URI uri = URI.create(value);
            String scheme = uri.getScheme();
            if (scheme == null || uri.getHost() == null) return null;
            scheme = scheme.toLowerCase(Locale.ROOT);
            if (!scheme.equals("http") && !scheme.equals("https")) return null;
            while (value.endsWith("/") && value.length() > scheme.length() + 3) {
                value = value.substring(0, value.length() - 1);
            }
            return value;
        } catch (IllegalArgumentException error) {
            return null;
        }
    }

    private void connectTo(String url) {
        String normalized = normalizeServerUrl(url);
        if (normalized == null) {
            showSettings();
            settingsStatus.setText(R.string.invalid_server_url);
            return;
        }
        loadedUrl = normalized;
        allowingInsecureTls = prefs().getBoolean(PREF_ALLOW_INSECURE_TLS, false);
        settingsVisible = false;
        inputActive = false;
        settingsView.setVisibility(View.GONE);
        settingsStatus.setText(R.string.connecting);
        progressBar.setVisibility(View.VISIBLE);
        webView.setVisibility(View.VISIBLE);
        webView.loadUrl(normalized);
        webView.requestFocus();
        hideSystemUi();
    }

    private void clearWebData() {
        CookieManager.getInstance().removeAllCookies(new ValueCallback<Boolean>() {
            @Override
            public void onReceiveValue(Boolean value) {
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        CookieManager.getInstance().flush();
                        WebStorage.getInstance().deleteAllData();
                        webView.clearCache(true);
                        webView.clearFormData();
                        webView.clearHistory();
                        settingsStatus.setText(R.string.web_data_cleared);
                    }
                });
            }
        });
    }

    private void showLoadError() {
        progressBar.setVisibility(View.GONE);
        settingsStatus.setText(R.string.load_failed);
        showSettings();
    }

    private void applyTvBridge() {
        if (tvBridgeScript.isEmpty() || webView.getUrl() == null) return;
        webView.evaluateJavascript(tvBridgeScript, new ValueCallback<String>() {
            @Override
            public void onReceiveValue(String ignored) {
                webView.evaluateJavascript(
                    "(window.FoliaTvRemote && window.FoliaTvRemote.focusInitial())",
                    null
                );
            }
        });
    }

    private void dispatchRemoteKey(String key, int modifiers) {
        if (webView.getUrl() == null) return;
        String script = "window.FoliaTvRemote && window.FoliaTvRemote.dispatchRemoteKey("
            + JSONObject.quote(key) + "," + modifiers + ")";
        webView.evaluateJavascript(script, null);
    }

    private void dispatchMediaAction(String action) {
        if (webView.getUrl() == null) return;
        String script = "window.FoliaTvRemote && window.FoliaTvRemote.mediaAction("
            + JSONObject.quote(action) + ")";
        webView.evaluateJavascript(script, null);
    }

    private void handleRemoteBack() {
        if (webView.getUrl() == null) {
            confirmExit();
            return;
        }
        webView.evaluateJavascript(
            "(window.FoliaTvRemote && window.FoliaTvRemote.handleBack()) || false",
            new ValueCallback<String>() {
                @Override
                public void onReceiveValue(String result) {
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            if ("true".equals(result)) return;
                            if (webView.canGoBack()) {
                                webView.goBack();
                            } else {
                                confirmExit();
                            }
                        }
                    });
                }
            }
        );
    }

    private void confirmExit() {
        new AlertDialog.Builder(this)
            .setTitle(R.string.exit_title)
            .setMessage(R.string.exit_message)
            .setPositiveButton(R.string.exit, new android.content.DialogInterface.OnClickListener() {
                @Override
                public void onClick(android.content.DialogInterface dialog, int which) {
                    finish();
                }
            })
            .setNegativeButton(R.string.stay, null)
            .show();
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        int keyCode = event.getKeyCode();
        int action = event.getAction();

        if (action == KeyEvent.ACTION_DOWN
            && (keyCode == KeyEvent.KEYCODE_MENU
            || keyCode == KeyEvent.KEYCODE_INFO
            || keyCode == KeyEvent.KEYCODE_SETTINGS)) {
            showSettings();
            return true;
        }

        if (settingsVisible || inputActive || action != KeyEvent.ACTION_DOWN) {
            return super.dispatchKeyEvent(event);
        }

        if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) {
            dispatchRemoteKey("ArrowLeft", 0);
            return true;
        }
        if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
            dispatchRemoteKey("ArrowRight", 0);
            return true;
        }
        if (keyCode == KeyEvent.KEYCODE_DPAD_UP) {
            dispatchRemoteKey("ArrowUp", 0);
            return true;
        }
        if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
            dispatchRemoteKey("ArrowDown", 0);
            return true;
        }
        if (keyCode == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
            || keyCode == KeyEvent.KEYCODE_HEADSETHOOK) {
            dispatchMediaAction("playPause");
            return true;
        }
        if (keyCode == KeyEvent.KEYCODE_MEDIA_NEXT
            || keyCode == KeyEvent.KEYCODE_CHANNEL_UP) {
            dispatchMediaAction("next");
            return true;
        }
        if (keyCode == KeyEvent.KEYCODE_MEDIA_PREVIOUS
            || keyCode == KeyEvent.KEYCODE_CHANNEL_DOWN) {
            dispatchMediaAction("previous");
            return true;
        }
        if (keyCode == KeyEvent.KEYCODE_MEDIA_FAST_FORWARD) {
            dispatchMediaAction("seekForward");
            return true;
        }
        if (keyCode == KeyEvent.KEYCODE_MEDIA_REWIND) {
            dispatchMediaAction("seekBackward");
            return true;
        }
        if (keyCode == KeyEvent.KEYCODE_REFRESH) {
            webView.reload();
            return true;
        }
        return super.dispatchKeyEvent(event);
    }

    @Override
    public void onBackPressed() {
        if (settingsVisible) {
            if (loadedUrl.isEmpty()) {
                finish();
            } else {
                hideSettings();
            }
            return;
        }
        handleRemoteBack();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != FILE_CHOOSER_REQUEST || filePathCallback == null) return;
        Uri[] result = null;
        if (resultCode == RESULT_OK && data != null) {
            if (data.getClipData() != null) {
                int count = data.getClipData().getItemCount();
                result = new Uri[count];
                for (int index = 0; index < count; index++) {
                    result[index] = data.getClipData().getItemAt(index).getUri();
                }
            } else if (data.getData() != null) {
                result = new Uri[]{data.getData()};
            }
        }
        filePathCallback.onReceiveValue(result);
        filePathCallback = null;
    }

    @Override
    protected void onResume() {
        super.onResume();
        webView.onResume();
        hideSystemUi();
    }

    @Override
    protected void onPause() {
        webView.onPause();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        if (webView != null) {
            webView.loadUrl("about:blank");
            webView.removeAllViews();
            webView.destroy();
            webView = null;
        }
        super.onDestroy();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) hideSystemUi();
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        hideSystemUi();
    }

    private void hideSystemUi() {
        if (Build.VERSION.SDK_INT >= 30) {
            getWindow().setDecorFitsSystemWindows(false);
            WindowInsetsController controller = getWindow().getInsetsController();
            if (controller != null) {
                controller.hide(WindowInsets.Type.systemBars());
                controller.setSystemBarsBehavior(
                    WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                );
            }
            return;
        }
        getWindow().getDecorView().setSystemUiVisibility(
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                | View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        );
    }

    private String readAsset(String name) {
        try (InputStream stream = getAssets().open(name);
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = stream.read(buffer)) != -1) {
                output.write(buffer, 0, count);
            }
            return output.toString(StandardCharsets.UTF_8.name());
        } catch (Exception error) {
            return "";
        }
    }

    private boolean isSameServer(String candidate) {
        try {
            URI allowed = URI.create(loadedUrl);
            URI actual = URI.create(candidate);
            return allowed.getScheme() != null
                && allowed.getHost() != null
                && allowed.getScheme().equalsIgnoreCase(actual.getScheme())
                && allowed.getHost().equalsIgnoreCase(actual.getHost())
                && effectivePort(allowed) == effectivePort(actual);
        } catch (IllegalArgumentException error) {
            return false;
        }
    }

    private int effectivePort(URI uri) {
        if (uri.getPort() != -1) return uri.getPort();
        return "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
    }

    private final class TvJsBridge {
        @android.webkit.JavascriptInterface
        public void setInputActive(boolean active) {
            inputActive = active;
        }

        @android.webkit.JavascriptInterface
        public void openSettings() {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    showSettings();
                }
            });
        }
    }

    private final class TvWebViewClient extends WebViewClient {
        @Override
        public boolean shouldOverrideUrlLoading(WebView view, String url) {
            try {
                Uri uri = Uri.parse(url);
                String scheme = uri.getScheme();
                if ("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme)) {
                    return false;
                }
                startActivity(new Intent(Intent.ACTION_VIEW, uri));
            } catch (Exception ignored) {
                // No installed app can handle this URL.
            }
            return true;
        }

        @Override
        public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
            Uri uri = request.getUrl();
            String scheme = uri.getScheme();
            if ("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme)) {
                return false;
            }
            try {
                startActivity(new Intent(Intent.ACTION_VIEW, uri));
            } catch (Exception ignored) {
                // No installed app can handle this scheme.
            }
            return true;
        }

        @Override
        public void onPageStarted(WebView view, String url, android.graphics.Bitmap favicon) {
            progressBar.setVisibility(View.VISIBLE);
        }

        @Override
        public void onPageFinished(WebView view, String url) {
            progressBar.setVisibility(View.GONE);
            applyTvBridge();
        }

        @Override
        public void onReceivedError(
            WebView view,
            WebResourceRequest request,
            WebResourceError error
        ) {
            if (request.isForMainFrame()) showLoadError();
        }

        @Override
        public void onReceivedSslError(WebView view, SslErrorHandler handler, android.net.http.SslError error) {
            if (allowingInsecureTls && isSameServer(error.getUrl())) {
                handler.proceed();
                return;
            }
            handler.cancel();
            settingsStatus.setText(R.string.ssl_blocked);
            showSettings();
        }
    }

    private final class TvWebChromeClient extends WebChromeClient {
        @Override
        public boolean onShowFileChooser(
            WebView view,
            ValueCallback<Uri[]> callback,
            FileChooserParams params
        ) {
            if (filePathCallback != null) {
                filePathCallback.onReceiveValue(null);
            }
            filePathCallback = callback;

            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType(params.getAcceptTypes().length > 0
                ? firstAcceptType(params.getAcceptTypes())
                : "*/*");
            intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, params.getMode() == FileChooserParams.MODE_OPEN_MULTIPLE);
            try {
                startActivityForResult(intent, FILE_CHOOSER_REQUEST);
            } catch (Exception error) {
                filePathCallback = null;
                callback.onReceiveValue(null);
                return false;
            }
            return true;
        }

        private String firstAcceptType(String[] accepts) {
            for (String accept : accepts) {
                String value = accept == null ? "" : accept.trim();
                if (value.isEmpty()) continue;
                int comma = value.indexOf(',');
                return comma >= 0 ? value.substring(0, comma) : value;
            }
            return "*/*";
        }
    }
}
