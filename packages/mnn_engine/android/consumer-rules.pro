-keep class com.arkanefans.mnn_engine.runtime.MnnNativeBridge { *; }
-keep class com.arkanefans.mnn_engine.runtime.MnnNativeSession { *; }
-keep interface com.arkanefans.mnn_engine.runtime.MnnNativeSession$TokenCallback { *; }

# Ktor's optional IntelliJ debugger detection references the desktop-only
# java.lang.management API. Android does not execute that branch.
-dontwarn java.lang.management.ManagementFactory
-dontwarn java.lang.management.RuntimeMXBean
