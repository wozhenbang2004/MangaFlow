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

# Commons Logging can use java.util.logging in the bundled JDK. These adapters
# reference optional logging and servlet backends that the app does not install.
-dontwarn org.apache.commons.logging.impl.AvalonLogger
-dontwarn org.apache.commons.logging.impl.Log4JLogger
-dontwarn org.apache.commons.logging.impl.Log4jApiLogFactory
-dontwarn org.apache.commons.logging.impl.Log4jApiLogFactory$*
-dontwarn org.apache.commons.logging.impl.LogKitLogger
-dontwarn org.apache.commons.logging.impl.ServletContextCleaner
-dontwarn org.apache.commons.logging.jakarta.ServletContextCleaner

# PDF import uses password-based loading. PDFBox's certificate handler needs
# optional Bouncy Castle classes. IOUtils calls the JDK's signature-polymorphic
# MethodHandle.invokeExact(ByteBuffer) for optional mapped-buffer cleanup.
-dontwarn org.apache.pdfbox.pdmodel.encryption.PublicKeySecurityHandler
-dontwarn org.apache.pdfbox.pdmodel.encryption.SecurityProvider
-dontwarn org.apache.pdfbox.io.IOUtils
