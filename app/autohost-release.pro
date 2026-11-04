# P15-07 (ADR-093): conservative release keep rules for AutoHost.
# Everything referenced from the manifest (services, receivers, activities, providers) is kept
# automatically; the rules below cover what reflection/kotlinx-serialization reach past R8.

# --- kotlinx.serialization (the broker envelope and all model types are @Serializable) --------
-keepattributes *Annotation*, InnerClasses, Signature
-dontnote kotlinx.serialization.**
-keepclassmembers class app.autohost.core.model.** {
    *** Companion;
}
-keepclasseswithmembers class app.autohost.core.model.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class app.autohost.**$$serializer { *; }
-keepclassmembers class app.autohost.** {
    *** Companion;
}

# --- Chaquopy: the Python bridge reflects over the task's classes (its own consumer rules ship
#     with the plugin; these keep the SDK entry points the interpreter calls by name) ----------
-keep class autohost.** { *; }

# --- Dev API: the JSON-RPC surface dispatches method names to handlers registered at runtime;
#     nothing is stripped from the router (registration is constructor-driven, but keep the
#     backend seams honest) ---------------------------------------------------------------------
-keep class app.autohost.core.devapi.** { *; }

# --- Crash-report readability: keep source file names and line numbers for the in-app report ---
-keepattributes SourceFile, LineNumberTable
-renamesourcefileattribute SourceFile
