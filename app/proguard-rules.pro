# Add project specific ProGuard rules here.

# NanoHTTPD — keep all server classes (uses reflection internally)
-keep class fi.iki.elonen.** { *; }
-dontwarn fi.iki.elonen.**

# ZXing — keep QR/barcode encoder classes
-keep class com.google.zxing.** { *; }
-dontwarn com.google.zxing.**

# App classes — keep data models
-keep class com.localshare.app.** { *; }

# Preserve reflection attributes
-keepattributes Signature, InnerClasses, Exceptions
