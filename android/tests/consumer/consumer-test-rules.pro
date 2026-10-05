# Instrumentation in the consuming APK is the test entrypoint; JNI rules come from the AAR.
-keep class com.unishare.consumer.ConsumerSmoke { *; }
