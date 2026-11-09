# ---------------------------------------------------------------------------
# ML Kit component discovery (release builds only)
#
# ML Kit reads its ComponentRegistrar implementations by name from the metadata
# of com.google.mlkit.common.internal.MlKitComponentDiscoveryService. R8 cannot
# see that reflective lookup. Without these rules R8 deletes FaceRegistrar,
# VisionCommonRegistrar and CommonComponentRegistrar from the minified release
# APK, so FaceDetection.getClient() throws at runtime on every device while
# debug builds and JVM unit tests keep working.
# ---------------------------------------------------------------------------
-keep class * implements com.google.firebase.components.ComponentRegistrar { *; }

-keep class com.google.mlkit.common.internal.MlKitInitProvider { *; }
-keep class com.google.mlkit.common.internal.MlKitComponentDiscoveryService { *; }

# The bundled face model is resolved through ML Kit's internal registry. Keep
# the registry and model implementation names stable; their consumer rules only
# cover generated protobuf fields.
-keep class com.google.mlkit.vision.face.internal.** { *; }
-keep class com.google.mlkit.vision.common.internal.** { *; }
-keep class com.google.mlkit.common.internal.** { *; }
-keep class com.google.android.gms.internal.mlkit_vision_face.** { *; }
-keep class com.google.android.gms.internal.mlkit_vision_face_bundled.** { *; }
