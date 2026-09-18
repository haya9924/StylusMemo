# Add project specific ProGuard rules here.
# Keep Ink serialization classes.
-keepattributes *Annotation*, InnerClasses

# ---- Plugin system (ServiceLoader) ----
# The plugin SPI and every implemented plugin class must survive shrinking and
# keep their no-arg constructors, otherwise PluginRegistry discovery breaks.
-keep class com.stylusmemo.app.plugin.** { *; }
-keepclassmembers class * implements com.stylusmemo.app.plugin.Plugin {
    public <init>();
}
-keep class * implements com.stylusmemo.app.plugin.Plugin { *; }

# Keep ServiceLoader registration files so plugins can be discovered by name.
-keepdirectories META-INF/services
-keepclassmembers !enum class **META-INF/services** { *; }
-keepnames class **META-INF/services/**

# Serialized models referenced by plugins / note.json must not be renamed.
-keep class com.stylusmemo.app.model.** { *; }