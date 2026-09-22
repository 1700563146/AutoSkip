# Keep the accessibility service entry point: the system instantiates it by name
# from AndroidManifest.xml, so it must survive shrinking.
-keep class com.example.autoskip.a11y.AutoSkipService { *; }
-keep class com.example.autoskip.testmode.TestModeService { *; }
