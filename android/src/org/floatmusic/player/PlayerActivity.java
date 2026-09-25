package org.floatmusic.player;

import org.qtproject.qt.android.bindings.QtActivity;
import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.widget.Toast;
import android.view.KeyEvent;
import android.window.OnBackInvokedDispatcher;
import java.lang.ref.WeakReference;

public class PlayerActivity extends QtActivity {
    private static final int PICK_AUDIO = 7101;
    private boolean waitingOverlay = false;
    private boolean overlayRequested = false;
    private boolean returnToOverlay = false;
    private boolean picking = false;
    private boolean pendingPick = false;
    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        PlayerBridge.activity = new WeakReference<>(this);
        if (Build.VERSION.SDK_INT >= 33) {
            getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                OnBackInvokedDispatcher.PRIORITY_DEFAULT, this::handleBack);
        }
        if (saved != null) { waitingOverlay = saved.getBoolean("overlay"); overlayRequested = saved.getBoolean("overlayRequested"); returnToOverlay = saved.getBoolean("return"); picking = saved.getBoolean("picking"); }
        handle(getIntent());
    }
    @Override protected void onSaveInstanceState(Bundle out) {
        out.putBoolean("overlay", waitingOverlay); out.putBoolean("return", returnToOverlay); out.putBoolean("picking", picking);
        out.putBoolean("overlayRequested", overlayRequested);
        super.onSaveInstanceState(out);
    }
    @Override public void onNewIntent(Intent intent) { super.onNewIntent(intent); setIntent(intent); handle(intent); }
    // Closing QtActivity destroys the Qt process, including its playback service.
    // Back means background; the explicit close button remains the way to quit.
    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getKeyCode() == KeyEvent.KEYCODE_BACK) {
            if (event.getAction() == KeyEvent.ACTION_UP) handleBack();
            return true;
        }
        return super.dispatchKeyEvent(event);
    }
    @Override public void onBackPressed() { handleBack(); }
    private void handleBack() {
        android.view.View decor = getWindow().getDecorView();
        boolean keyboardVisible;
        if (Build.VERSION.SDK_INT >= 30) {
            android.view.WindowInsets insets = decor.getRootWindowInsets();
            keyboardVisible = insets != null && insets.isVisible(android.view.WindowInsets.Type.ime());
        } else {
            android.graphics.Rect visible = new android.graphics.Rect();
            decor.getWindowVisibleDisplayFrame(visible);
            keyboardVisible = decor.getRootView().getHeight() - visible.bottom > 150 * getResources().getDisplayMetrics().density;
        }
        if (keyboardVisible) {
            ((android.view.inputmethod.InputMethodManager)getSystemService(INPUT_METHOD_SERVICE))
                    .hideSoftInputFromWindow(decor.getWindowToken(), 0);
        } else moveTaskToBack(true);
    }
    private void handle(Intent intent) {
        if (intent != null && intent.getBooleanExtra("pickFromOverlay", false)) {
            intent.removeExtra("pickFromOverlay"); pendingPick=true; PlayerBridge.main.post(() -> { pendingPick=false; pickMusic(true); });
        }
    }
    @Override public void onResume() {
        super.onResume(); PlayerBridge.activity = new WeakReference<>(this); PlayerBridge.foreground = true;
        getWindow().getDecorView().post(this::applySystemBarTheme);
        if (PlaybackService.instance != null) PlaybackService.instance.syncOverlay();
        if (PlaybackService.instance != null) PlaybackService.instance.update(); else PlayerBridge.idle("");
        if (waitingOverlay) {
            waitingOverlay=false;
            if(!Settings.canDrawOverlays(this)) {
                overlayRequested=false;
                Toast.makeText(this,"允许悬浮权限后，即可在图标中使用浮音",Toast.LENGTH_LONG).show();
            } else completeOverlayRequest();
        }
    }
    // Only a tap on the welcome page may start this flow. Resuming the app must stay visible.
    public void completeOverlayRequest() {
        if(!overlayRequested || picking || pendingPick || waitingOverlay || !PlayerBridge.qtReady)return;
        if(!Settings.canDrawOverlays(this)){PlayerBridge.idle("");return;}
        if(Build.VERSION.SDK_INT>=33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED
                && !getPreferences(MODE_PRIVATE).getBoolean("notificationAsked",false)) {
            getPreferences(MODE_PRIVATE).edit().putBoolean("notificationAsked",true).apply();
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},7103);return;
        }
        startOverlay();
    }
    @Override public void onStop() {
        PlayerBridge.foreground = false;
        if (PlaybackService.instance != null) PlaybackService.instance.syncOverlay();
        super.onStop();
    }
    void applySystemBarTheme() {
        boolean dark = PlayerBridge.isDark(this);
        if (Build.VERSION.SDK_INT >= 30) {
            android.view.WindowInsetsController controller = getWindow().getInsetsController();
            if (controller != null) {
                int mask = android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                        | android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
                controller.setSystemBarsAppearance(dark ? 0 : mask, mask);
            }
        } else {
            android.view.View decor = getWindow().getDecorView();
            int mask = android.view.View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | android.view.View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
            decor.setSystemUiVisibility(dark ? decor.getSystemUiVisibility() & ~mask : decor.getSystemUiVisibility() | mask);
        }
    }
    void send(String command, String value) {
        Intent intent = new Intent(this, PlaybackService.class).setAction(command).putExtra("value", value);
        startForegroundService(intent);
    }
    public void enableOverlay() {
        if (waitingOverlay) return;
        overlayRequested = true;
        if (!Settings.canDrawOverlays(this)) {
            waitingOverlay = true;
            startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + getPackageName())));
        } else completeOverlayRequest();
    }
    private void startOverlay() { overlayRequested=false; send("float", ""); moveTaskToBack(true); }
    public void pickMusic(boolean fromOverlay) {
        if (picking) return;
        returnToOverlay = fromOverlay;
        picking = true;
        PlayerBridge.picking = true;
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 7102);
            return;
        }
        openPicker();
    }
    @Override public void onRequestPermissionsResult(int request, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(request, permissions, results);
        if (request == 7102 && picking) openPicker();
        if (request == 7103) completeOverlayRequest();
    }
    private void openPicker() {
        Intent picker = new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("audio/*");
        picker.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try { picking = true; startActivityForResult(picker, PICK_AUDIO); }
        catch (Exception e) { picking = false; PlayerBridge.picking = false; Toast.makeText(this, "无法打开文件选择器：" + e.getMessage(), Toast.LENGTH_LONG).show(); }
    }
    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request == PICK_AUDIO) {
            picking = false;
            PlayerBridge.picking = false;
            if (PlaybackService.instance != null) PlaybackService.instance.syncOverlay();
            if (result == RESULT_OK && data != null && data.getData() != null) {
                try { send("import", data.getData().toString()); }
                catch (Exception e) { Toast.makeText(this, "无法启动播放服务：" + e.getMessage(), Toast.LENGTH_LONG).show(); }
            }
            if (returnToOverlay) { returnToOverlay = false; moveTaskToBack(true); }
        }
    }
    @Override public void onDestroy() { if (PlayerBridge.activity.get() == this) PlayerBridge.activity.clear(); super.onDestroy(); }
}
