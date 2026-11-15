# The toolkit itself does not use reflection. Retain public entry points when a
# reviewed Smali/JNI bridge calls them by class name from a modified host APK.
-keep public class com.mahdi.camera.hdr.HdrRenderer { public *; }
-keep public interface com.mahdi.camera.hdr.HdrRenderer$Listener { *; }
-keep public class com.mahdi.camera.hdr.HdrPreset { public *; }
