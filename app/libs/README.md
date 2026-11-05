# QuickJS Android vendor

The native libraries in `src/main/jniLibs` and `quickjs-wrapper-android-3.2.0.jar`
were extracted without modification from Maven Central
`wang.harlon.quickjs:wrapper-android:3.2.0` (the version used by
`congduan/widget-chat-android`). The Java wrapper API comes from
`wang.harlon.quickjs:wrapper-java:3.2.0`. The publisher declares Apache-2.0
in the artifact POM: https://repo.maven.apache.org/maven2/wang/harlon/quickjs/wrapper-android/3.2.0/wrapper-android-3.2.0.pom

Native SHA-256:

- arm64-v8a: `634b4dbd5849b875c80659a63d0f37e6a73c5ca7cdc54af7ac1bb0e63f47ff4e`
- armeabi-v7a: `8bbb62aca31bb33715dada21f9aaa2e884ed2cc89136ba57cc2b981b3f174cac`
- x86: `09257fa991a2ecf652721ac28e129980984896290d61442ca4d8d3a169909877`
- x86_64: `2bd056ec4382ed7a48b0ef037b24f616e2a73ffc543e9dd2591fb82599127a0d`

# ZXing core (R35)

`zxing-core-3.5.3.jar` is the unmodified `com.google.zxing:core:3.5.3`
artifact from Maven Central (Apache-2.0). It is vendored as a plain file
dependency so the QR tools build fully offline with no Google Maven / GMS
dependency. Only the pure-Java `core` artifact is used (no `android`/`javase`
integration, no camera, no permissions).

SHA-256: `8d8064c1636fdaef7189dd9055c7d59950a8940a12f2293956446ec3c109fd82`
Source: https://repo1.maven.org/maven2/com/google/zxing/core/3.5.3/core-3.5.3.jar
