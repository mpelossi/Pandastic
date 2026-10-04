package org.pandastic.relay;

import android.Manifest;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.Build;
import android.provider.MediaStore;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.PermissionRequest;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;
import android.widget.FrameLayout;
import androidx.core.content.FileProvider;
import androidx.webkit.WebViewAssetLoader;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.pandastic.relay.hub.HubPrefs;
import org.pandastic.relay.hub.HubService;

/** Hosts the bundled React UI without a development server or internet connection. */
public final class FrontendActivity extends Activity {
    private static final String LOCAL_HOST = "appassets.androidplatform.net";
    private static final String START_URL = "https://" + LOCAL_HOST + "/assets/pandastic/index.html";
    private static final int PICK_IMAGE = 20;
    private static final int HUB_PERMISSION = 22;
    private static final int PICK_MODEL = 23;
    private static final int MICROPHONE_PERMISSION = 24;
    private static final int CONTACT_PERMISSION = 25;
    private static final int NUMBER_PERMISSION = 26;
    private final List<Runnable> afterContactPermission = new ArrayList<>();
    private WebView webView;
    private ValueCallback<Uri[]> fileCallback;
    private Uri cameraUri;
    private final List<File> cameraFiles = new ArrayList<>();
    private NativeBridge bridge;
    private Runnable afterHubPermissions;
    private String modelRequest;
    private final List<Runnable> afterAudioPermission = new ArrayList<>();
    private PermissionRequest webAudioRequest;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        webView = new WebView(this);
        webView.setBackgroundColor(Color.rgb(250, 251, 248));
        FrameLayout container = new FrameLayout(this);
        container.setBackgroundColor(Color.rgb(250, 251, 248));
        container.setOnApplyWindowInsetsListener((view, insets) -> {
            view.setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(),
                insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom());
            return insets.consumeSystemWindowInsets();
        });
        container.addView(webView, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        setContentView(container);
        BrainHost.get(this).warmUp();  // models load in the background while the first screen draws
        int systemUi = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
        if (Build.VERSION.SDK_INT >= 26) systemUi |= View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
        getWindow().getDecorView().setSystemUiVisibility(systemUi);
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        settings.setBlockNetworkLoads(true);  // the app holds INTERNET for the model download only, never for the UI
        WebView.setWebContentsDebuggingEnabled((getApplicationInfo().flags & android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0);

        WebViewAssetLoader assets = new WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", new WebViewAssetLoader.AssetsPathHandler(this))
            .build();
        webView.setWebViewClient(new WebViewClient() {
            @Override public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                if (isLocalOrigin(request.getUrl())) {
                    WebResourceResponse response = assets.shouldInterceptRequest(request.getUrl());
                    if (response != null) return response;
                }
                // Never fall through to the internet if an asset is missing or a URL changes.
                return new WebResourceResponse("text/plain", "UTF-8", 404, "Not Found", null,
                    new ByteArrayInputStream(new byte[0]));
            }
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return !isLocalOrigin(request.getUrl());
            }
            @Override public boolean shouldOverrideUrlLoading(WebView view, String url) {
                return !isLocalOrigin(Uri.parse(url));
            }
        });
        webView.setWebChromeClient(new WebChromeClient() {
            @Override public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback, FileChooserParams params) {
                if (fileCallback != null) fileCallback.onReceiveValue(null);
                fileCallback = callback;
                cameraUri = null;
                if (params.isCaptureEnabled()) {
                    Intent camera = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
                    if (camera.resolveActivity(getPackageManager()) != null) {
                        try {
                            File directory = new File(getCacheDir(), "frontend-camera");
                            if (!directory.exists() && !directory.mkdirs()) throw new IOException("Cannot create photo cache");
                            File photo = File.createTempFile("picture-", ".jpg", directory);
                            cameraFiles.add(photo);
                            cameraUri = FileProvider.getUriForFile(FrontendActivity.this,
                                getPackageName() + ".frontend.files", photo);
                            camera.putExtra(MediaStore.EXTRA_OUTPUT, cameraUri);
                            camera.setClipData(ClipData.newRawUri("Photo", cameraUri));
                            camera.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                            startActivityForResult(camera, PICK_IMAGE);
                            return true;
                        } catch (IOException | ActivityNotFoundException e) {
                            cameraUri = null;
                            Toast.makeText(FrontendActivity.this, "Camera unavailable. Choose a picture instead.", Toast.LENGTH_SHORT).show();
                        }
                    }
                }
                Intent picker = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                picker.addCategory(Intent.CATEGORY_OPENABLE);
                picker.setType("image/*");
                try { startActivityForResult(picker, PICK_IMAGE); }
                catch (ActivityNotFoundException e) {
                    fileCallback.onReceiveValue(null);
                    fileCallback = null;
                    Toast.makeText(FrontendActivity.this, "No image picker is installed.", Toast.LENGTH_SHORT).show();
                }
                return true;
            }
            // Only the bundled app may request audio capture. Other WebView resources stay denied.
            @Override public void onPermissionRequest(PermissionRequest request) {
                runOnUiThread(() -> {
                    if (!isLocalOrigin(request.getOrigin())) { request.deny(); return; }
                    boolean audio = false;
                    for (String resource : request.getResources()) if (PermissionRequest.RESOURCE_AUDIO_CAPTURE.equals(resource)) audio = true;
                    if (!audio) { request.deny(); return; }
                    webAudioRequest = request;
                    requestMicrophonePermission(() -> {
                        if (webAudioRequest != request || isDestroyed()) return;
                        webAudioRequest = null;
                        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
                            request.grant(new String[]{PermissionRequest.RESOURCE_AUDIO_CAPTURE});
                        else request.deny();
                    });
                });
            }
            @Override public void onPermissionRequestCanceled(PermissionRequest request) {
                runOnUiThread(() -> { if (webAudioRequest == request) webAudioRequest = null; });
            }
            @Override public boolean onConsoleMessage(android.webkit.ConsoleMessage message) {
                if ((getApplicationInfo().flags & android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0)
                    android.util.Log.d("PandasticWeb", message.messageLevel() + " " + message.message() + " @" + message.lineNumber());
                return true;
            }
        });
        bridge = new NativeBridge(this, webView);
        bridge.initTts();  // the voice engine takes a moment to start; start it before the first "listen"
        webView.addJavascriptInterface(bridge, "PandasticNative");
        webView.loadUrl(START_URL);
        // Ask once for SIM identity; the Contacts button can request it again if declined.
        if (PhoneContacts.identity(this).optString("number").isEmpty()
            && checkSelfPermission(PhoneContacts.numberPermission()) != PackageManager.PERMISSION_GRANTED
            && !getPreferences(MODE_PRIVATE).getBoolean("number_asked", false)) {
            getPreferences(MODE_PRIVATE).edit().putBoolean("number_asked", true).apply();
            requestPermissions(new String[]{PhoneContacts.numberPermission()}, NUMBER_PERMISSION);
        }
        // Opening the app answers SMS questions that arrived while the helper was stopped.
        if (new HubPrefs(this).enabled() && !HubService.isRunning()) HubService.start(this);
    }

    private static boolean isLocalOrigin(Uri uri) {
        return "https".equals(uri.getScheme()) && LOCAL_HOST.equals(uri.getHost()) && uri.getPort() == -1;
    }

    void pickModel(String id) {
        modelRequest = id;
        Intent picker = new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*");
        try { startActivityForResult(picker, PICK_MODEL); }
        catch (ActivityNotFoundException e) { modelRequest = null; bridge.finishModelImport(id, null); }
    }

    /** Asks for SMS (and notification) permissions, then runs done whatever the answer was. */
    void requestHubPermissions(Runnable done) {
        requestSmsPermissions(done, true);
    }

    void requestSmsPermissions(Runnable done) {
        requestSmsPermissions(done, false);
    }

    private void requestSmsPermissions(Runnable done, boolean notifications) {
        List<String> missing = new ArrayList<>();
        for (String permission : new String[]{Manifest.permission.RECEIVE_SMS, Manifest.permission.SEND_SMS})
            if (checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) missing.add(permission);
        if (notifications && Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            missing.add(Manifest.permission.POST_NOTIFICATIONS);
        if (missing.isEmpty()) { done.run(); return; }
        afterHubPermissions = done;
        requestPermissions(missing.toArray(new String[0]), HUB_PERMISSION);
    }

    void requestMicrophonePermission(Runnable done) {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) { done.run(); return; }
        afterAudioPermission.add(done);
        if (afterAudioPermission.size() == 1) requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, MICROPHONE_PERMISSION);
    }

    void requestContactPermissions(Runnable done) {
        List<String> missing = new ArrayList<>();
        for (String permission : new String[]{Manifest.permission.READ_CONTACTS, PhoneContacts.numberPermission()})
            if (checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) missing.add(permission);
        if (missing.isEmpty()) { done.run(); return; }
        afterContactPermission.add(done);
        if (afterContactPermission.size() == 1) requestPermissions(missing.toArray(new String[0]), CONTACT_PERMISSION);
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == NUMBER_PERMISSION) bridge.announcePhone();
        if (requestCode == CONTACT_PERMISSION) {
            List<Runnable> callbacks = new ArrayList<>(afterContactPermission);
            afterContactPermission.clear();
            bridge.announcePhone();
            for (Runnable callback : callbacks) callback.run();
        }
        if (requestCode == MICROPHONE_PERMISSION) {
            List<Runnable> callbacks = new ArrayList<>(afterAudioPermission);
            afterAudioPermission.clear();
            for (Runnable callback : callbacks) callback.run();
        }
        if (requestCode == HUB_PERMISSION && afterHubPermissions != null) {
            Runnable done = afterHubPermissions;
            afterHubPermissions = null;
            done.run();
        }
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == PICK_MODEL && modelRequest != null) {
            String id = modelRequest; modelRequest = null;
            bridge.finishModelImport(id, resultCode == RESULT_OK && data != null ? data.getData() : null);
            return;
        }
        if (requestCode != PICK_IMAGE || fileCallback == null) return;
        Uri result = resultCode == RESULT_OK ? (cameraUri != null ? cameraUri : data == null ? null : data.getData()) : null;
        fileCallback.onReceiveValue(result == null ? null : new Uri[]{result});
        fileCallback = null;
        cameraUri = null;
    }

    /** The React app keeps its own screen history; walk it before leaving the app. */
    @Override public void onBackPressed() {
        if (webView != null && webView.canGoBack()) webView.goBack();
        else super.onBackPressed();
    }

    @Override protected void onPause() {
        if (bridge != null) bridge.stopSpeaking();
        if (bridge != null && checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) bridge.cancelDictation();
        webView.onPause();
        super.onPause();
    }

    @Override protected void onResume() {
        super.onResume();
        if (webView != null) webView.onResume();
        if (bridge != null) { bridge.announceHub(); bridge.announceChat(); bridge.announceModels(); bridge.announcePhone(); }
    }

    @Override protected void onStop() {
        if (bridge != null) bridge.cancelDictation();
        super.onStop();
    }

    @Override protected void onDestroy() {
        afterContactPermission.clear();
        afterAudioPermission.clear();
        webAudioRequest = null;
        if (modelRequest != null) bridge.finishModelImport(modelRequest, null);
        if (fileCallback != null) { fileCallback.onReceiveValue(null); fileCallback = null; }
        bridge.close();
        webView.destroy();
        for (File file : cameraFiles) file.delete();
        super.onDestroy();
    }
}
