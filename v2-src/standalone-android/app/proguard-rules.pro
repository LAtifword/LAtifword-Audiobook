########################################
# ONNX Runtime / JNI
########################################
-keep class ai.onnxruntime.** { *; }
-keep interface ai.onnxruntime.** { *; }
-keep enum ai.onnxruntime.** { *; }

-keepclasseswithmembernames,includedescriptorclasses class * {
    native <methods>;
}

########################################
# LATIF application entry points and model bridge
########################################
-keep class com.latif.audiobook.offline.** { *; }

########################################
# PDFBox Android
########################################
-keep class com.tom_roush.pdfbox.** { *; }
-keep class org.apache.fontbox.** { *; }

-dontwarn ai.onnxruntime.**
-dontwarn org.apache.**
-dontwarn com.tom_roush.**
-dontwarn javax.annotation.**
