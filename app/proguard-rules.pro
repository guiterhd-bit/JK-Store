# Giữ các hàm mà trang giao diện (JavaScript) gọi vào
-keepattributes JavascriptInterface
-keepclassmembers class com.jks.store.MainActivity$Bridge {
    @android.webkit.JavascriptInterface <methods>;
}
