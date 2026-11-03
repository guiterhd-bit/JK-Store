package com.jks.store;

import android.app.Activity;
import android.app.UiModeManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.View;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import androidx.core.content.FileProvider;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

public class MainActivity extends Activity {

    private WebView web;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private volatile boolean busy = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        web = new WebView(this);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        web.setWebViewClient(new WebViewClient());
        web.addJavascriptInterface(new Bridge(), "Android");
        web.loadUrl("file:///android_asset/index.html");
        setContentView(web);
        web.requestFocus();
    }

    /** Ẩn thanh trạng thái/thanh điều hướng để giao diện phủ kín màn hình (điện thoại). */
    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus && Build.VERSION.SDK_INT >= 19) {
            getWindow().getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        }
    }

    private void js(final String code) {
        ui.post(new Runnable() {
            @Override
            public void run() {
                web.evaluateJavascript(code, null);
            }
        });
    }

    private void status(String text) {
        js("setStatus(" + JSONObject.quote(text) + ")");
    }

    /** Mở kết nối, tự theo chuyển hướng (kể cả http -> https). */
    private HttpURLConnection open(String url) throws IOException {
        for (int n = 0; n < 6; n++) {
            HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(15000);
            c.setReadTimeout(30000);
            c.setInstanceFollowRedirects(false);
            c.setRequestProperty("User-Agent", "KhoAppTV/1.0");
            int code = c.getResponseCode();
            if (code >= 300 && code < 400) {
                String loc = c.getHeaderField("Location");
                c.disconnect();
                if (loc == null) throw new IOException("Chuyển hướng bị lỗi");
                url = new URL(new URL(url), loc).toString();
                continue;
            }
            if (code != 200) {
                c.disconnect();
                throw new IOException("Máy chủ trả về mã " + code);
            }
            return c;
        }
        throw new IOException("Quá nhiều lần chuyển hướng");
    }

    private String readText(String url) throws IOException {
        HttpURLConnection c = open(url);
        try (InputStream in = c.getInputStream()) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
            return out.toString("UTF-8");
        } finally {
            c.disconnect();
        }
    }

    /** Android 7.0+ dùng thư mục cache + FileProvider; Android 5.1-6.0 cần file ở bộ nhớ ngoài của app. */
    private File apkFile() {
        File base = Build.VERSION.SDK_INT >= 24 ? getCacheDir() : getExternalFilesDir(null);
        if (base == null) base = getCacheDir();
        File dir = new File(base, "apk");
        dir.mkdirs();
        return new File(dir, "update.apk");
    }

    private File download(String url, String name) throws IOException {
        File f = apkFile();
        HttpURLConnection c = open(url);
        long total = c.getContentLength();
        try (InputStream in = c.getInputStream(); FileOutputStream out = new FileOutputStream(f)) {
            byte[] buf = new byte[32768];
            long done = 0;
            int n;
            int last = -1;
            while ((n = in.read(buf)) != -1) {
                out.write(buf, 0, n);
                done += n;
                int p = total > 0 ? (int) (done * 100 / total) : (int) (done / 1048576);
                if (p != last) {
                    last = p;
                    if (total > 0) status("Đang tải " + name + ": " + p + "%");
                    else status("Đang tải " + name + ": " + p + " MB");
                }
            }
        } finally {
            c.disconnect();
        }
        f.setReadable(true, false);
        // Kiểm tra đúng là file APK (file zip bắt đầu bằng "PK")
        try (FileInputStream chk = new FileInputStream(f)) {
            int a = chk.read();
            int b = chk.read();
            if (a != 'P' || b != 'K') {
                throw new IOException("Link này không phải file APK tải trực tiếp");
            }
        }
        return f;
    }

    private class Bridge {

        /** true nếu là điện thoại/máy tính bảng cảm ứng (không phải TV hay TV box). */
        @JavascriptInterface
        public boolean isPhone() {
            UiModeManager um = (UiModeManager) getSystemService(Context.UI_MODE_SERVICE);
            boolean tv = um != null && um.getCurrentModeType() == Configuration.UI_MODE_TYPE_TELEVISION;
            PackageManager pm = getPackageManager();
            boolean leanback = pm.hasSystemFeature("android.software.leanback");
            boolean touch = pm.hasSystemFeature(PackageManager.FEATURE_TOUCHSCREEN);
            return touch && !tv && !leanback;
        }

        @JavascriptInterface
        public void loadCatalog() {
            new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        String text = readText(getString(R.string.catalog_url));
                        js("onCatalog(" + JSONObject.quote(text) + ")");
                    } catch (Exception e) {
                        js("onCatalogError(" + JSONObject.quote(String.valueOf(e.getMessage())) + ")");
                    }
                }
            }).start();
        }

        @JavascriptInterface
        public void install(final String url, final String name) {
            if (busy) {
                status("Đang tải, vui lòng đợi…");
                return;
            }
            if (Build.VERSION.SDK_INT >= 26 && !getPackageManager().canRequestPackageInstalls()) {
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                    Uri.parse("package:" + getPackageName())));
                        } catch (Exception ignored) {
                        }
                    }
                });
                status("Hãy bật \"Cho phép cài ứng dụng\" cho Kho ứng dụng, rồi quay lại và bấm Tải lần nữa.");
                return;
            }
            busy = true;
            new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        File f = download(url, name);
                        status("Đã tải xong. Đang mở màn hình cài đặt…");
                        Uri uri;
                        if (Build.VERSION.SDK_INT >= 24) {
                            uri = FileProvider.getUriForFile(MainActivity.this,
                                    getPackageName() + ".fileprovider", f);
                        } else {
                            uri = Uri.fromFile(f); // Android 5.1-6.0 chưa hỗ trợ content:// cho trình cài đặt
                        }
                        final Intent i = new Intent(Intent.ACTION_VIEW);
                        i.setDataAndType(uri, "application/vnd.android.package-archive");
                        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
                        ui.post(new Runnable() {
                            @Override
                            public void run() {
                                MainActivity.this.startActivity(i);
                            }
                        });
                    } catch (Exception e) {
                        status("Lỗi: " + e.getMessage());
                    } finally {
                        busy = false;
                    }
                }
            }).start();
        }
    }
}
