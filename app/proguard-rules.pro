# Reglas de R8 para el APK de release.

# UniFFI + JNA: el código generado llama a la biblioteca nativa por nombre y
# JNA lee por reflexión los campos de las Structure y los métodos de las
# interfaces Library/Callback. Si R8 los renombra o elimina, la app se cae
# al cargar libkushare_mobile.so.
-keep class com.sun.jna.** { *; }
-keepclassmembers class * extends com.sun.jna.** { public *; }
-keep class uniffi.** { *; }
-dontwarn java.awt.**
-dontwarn com.sun.jna.**

# Implementaciones de interfaces de callback de UniFFI (LinkCallback,
# ByteStreamIo, ReceiveCallback…) que el lado Rust invoca por JNA.
-keep class * implements uniffi.kushare_mobile.** { *; }
