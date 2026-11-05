# Giữ các hàm mà trang giao diện (JavaScript) gọi vào
-keepattributes JavascriptInterface
-keepclassmembers class com.jks.store.MainActivity$Bridge {
    @android.webkit.JavascriptInterface <methods>;
}

# Thư viện ADB (dadb) và các phụ thuộc của nó
-keep class dadb.** { *; }
-dontwarn dadb.**
-dontwarn okio.**
-dontwarn kotlin.**
-dontwarn org.jetbrains.annotations.**
