package com.jks.store;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.DialogInterface;
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

import dadb.AdbKeyPair;
import dadb.Dadb;

import org.json.JSONArray;
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
            c.setRequestProperty("User-Agent", "JKStore/1.0");
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

    /** Bật ADB (nếu được cấp WRITE_SECURE_SETTINGS) rồi cài APK qua ADB nội bộ của TV. Trả về true nếu cài xong. */
    private boolean installViaAdb(File apk, String name) {
        Dadb dadb = null;
        try {
            if (getPackageManager().checkPermission("android.permission.WRITE_SECURE_SETTINGS", getPackageName())
                    == PackageManager.PERMISSION_GRANTED) {
                try {
                    Settings.Global.putInt(getContentResolver(), "adb_enabled", 1);
                } catch (Exception ignored) {
                }
            }
            File priv = new File(getFilesDir(), "adbkey");
            File pub = new File(getFilesDir(), "adbkey.pub");
            if (!priv.exists() || !pub.exists()) {
                AdbKeyPair.generate(priv, pub);
            }
            AdbKeyPair keyPair = AdbKeyPair.read(priv, pub);
            status("Đang cài " + name + " qua ADB… Nếu TV hỏi cho phép gỡ lỗi USB, hãy chọn \"Luôn cho phép\".");
            dadb = Dadb.create("127.0.0.1", 5555, keyPair);
            dadb.install(apk, "-r");
            status("Đã cài xong " + name);
            return true;
        } catch (Throwable t) {
            // ADB chưa bật / bị từ chối / lỗi cài: dùng trình cài đặt hệ thống
            status("Không cài được qua ADB, chuyển sang trình cài đặt hệ thống…");
            return false;
        } finally {
            if (dadb != null) {
                try {
                    dadb.close();
                } catch (Exception ignored) {
                }
            }
        }
    }

    /** Thử lần lượt nhiều màn hình cài đặt; TV Xiaomi nội địa thường thiếu màn hình chuẩn của Android. */
    private boolean openInstallPermissionScreen() {
        Intent[] tries = new Intent[] {
                new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + getPackageName())),
                new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES),
                new Intent(Settings.ACTION_SECURITY_SETTINGS),
                new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getPackageName())),
                new Intent(Settings.ACTION_SETTINGS)
        };
        for (Intent i : tries) {
            try {
                startActivity(i);
                return true;
            } catch (ActivityNotFoundException ignored) {
            } catch (SecurityException ignored) {
            } catch (Exception ignored) {
            }
        }
        return false;
    }

    private void showPermissionDialog() {
        String msg = "Để cài ứng dụng, cần bật quyền cài đặt cho JK Store.\n\n"
                + "Bấm \"Mở cài đặt\" rồi bật \"Cho phép cài ứng dụng\" / \"Nguồn không xác định\".\n\n"
                + "Nếu TV không mở được màn hình đó, vào: Cài đặt > Tài khoản và bảo mật > Bảo mật > Cài ứng dụng không rõ nguồn "
                + "(hoặc cài đặt có tên tương tự), bật cho \"JK Store\".\n\n"
                + "Cách khác (qua ADB):\nadb shell appops set " + getPackageName() + " REQUEST_INSTALL_PACKAGES allow";
        new AlertDialog.Builder(this)
                .setTitle("Cần cấp quyền cài đặt")
                .setMessage(msg)
                .setPositiveButton("Mở cài đặt", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        if (!openInstallPermissionScreen()) {
                            status("TV không mở được cài đặt. Hãy vào Cài đặt > Bảo mật và bật thủ công, hoặc dùng ADB.");
                        }
                    }
                })
                .setNegativeButton("Đóng", null)
                .show();
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

        /** Hỏi GitHub Releases xem có bản mới hơn bản đang cài không. */
        @JavascriptInterface
        public void checkUpdate() {
            new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        int current = getPackageManager().getPackageInfo(getPackageName(), 0).versionCode;
                        JSONObject o = new JSONObject(readText(getString(R.string.update_api_url)));
                        String tag = o.optString("tag_name", "").replaceAll("[^0-9]", "");
                        if (tag.length() == 0) return;
                        int latest = Integer.parseInt(tag);
                        if (latest <= current) return;
                        JSONArray assets = o.getJSONArray("assets");
                        String url = null;
                        for (int i = 0; i < assets.length(); i++) {
                            JSONObject a = assets.getJSONObject(i);
                            if (a.optString("name", "").endsWith(".apk")) {
                                url = a.getString("browser_download_url");
                                break;
                            }
                        }
                        if (url == null) return;
                        js("onUpdate(" + latest + "," + JSONObject.quote(url) + ")");
                    } catch (Exception ignored) {
                        // không có mạng hoặc chưa có bản phát hành: bỏ qua, không báo lỗi
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
            // Không chặn theo canRequestPackageInstalls(): một số ROM Xiaomi nội địa báo false dù đã cấp quyền
            // qua ADB. Cứ tải và mở trình cài đặt; nếu hệ thống thật sự từ chối thì mới hiện hướng dẫn.
            final boolean maybeNoPerm = Build.VERSION.SDK_INT >= 26
                    && !getPackageManager().canRequestPackageInstalls();
            busy = true;
            new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        File f = download(url, name);
                        // Ưu tiên cài qua ADB nội bộ (không cần cấp quyền); lỗi thì quay về trình cài đặt hệ thống
                        if (installViaAdb(f, name)) {
                            return;
                        }
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
                                try {
                                    MainActivity.this.startActivity(i);
                                } catch (Exception ex) {
                                    status("Không mở được trình cài đặt: " + ex.getMessage());
                                    showPermissionDialog();
                                    return;
                                }
                                if (maybeNoPerm) {
                                    status("Nếu trình cài đặt không hiện, hãy bật quyền cài ứng dụng cho JK Store.");
                                }
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
