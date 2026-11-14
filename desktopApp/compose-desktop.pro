# Keep reflection, JNI and desktop runtime entry points intact.
# Only unused Material icon classes may be removed from the release package.
-keep class !androidx.compose.material.icons.**,** { *; }
-keepattributes *

# jpackage uses the bundled JDK 21 TLS provider. OkHttp checks before selecting these
# optional providers; none is bundled. Keep missing application/OkHttp APIs fatal.
-dontwarn org.bouncycastle.jsse.BCSSLParameters
-dontwarn org.bouncycastle.jsse.BCSSLSocket
-dontwarn org.bouncycastle.jsse.provider.BouncyCastleJsseProvider
-dontwarn org.conscrypt.Conscrypt
-dontwarn org.conscrypt.Conscrypt$Version
-dontwarn org.conscrypt.ConscryptHostnameVerifier
-dontwarn org.openjsse.javax.net.ssl.SSLParameters
-dontwarn org.openjsse.javax.net.ssl.SSLSocket
-dontwarn org.openjsse.net.ssl.OpenJSSE

# Native-image build hooks are inactive in the ordinary JDK/jpackage distribution.
-dontwarn org.graalvm.nativeimage.hosted.Feature
-dontwarn org.graalvm.nativeimage.hosted.Feature$BeforeAnalysisAccess
-dontwarn com.oracle.svm.core.annotate.Delete
-dontwarn com.oracle.svm.core.annotate.Substitute
-dontwarn com.oracle.svm.core.annotate.TargetClass
