package dev.mangaflow.net

import java.security.cert.CertPathValidatorException
import java.security.cert.CertificateExpiredException
import java.security.cert.CertificateNotYetValidException
import java.security.cert.PKIXReason
import javax.net.ssl.SSLPeerUnverifiedException

internal actual fun platformTlsFailureDetail(failure: Throwable): String? = when (failure) {
    is CertificateExpiredException -> "站点证书已过期，请核对设备时间"
    is CertificateNotYetValidException -> "站点证书尚未生效，请核对设备时间"
    is CertPathValidatorException -> when (failure.reason) {
        CertPathValidatorException.BasicReason.EXPIRED -> "站点证书已过期，请核对设备时间"
        CertPathValidatorException.BasicReason.NOT_YET_VALID -> "站点证书尚未生效，请核对设备时间"
        CertPathValidatorException.BasicReason.REVOKED -> "站点证书已被吊销"
        PKIXReason.NO_TRUST_ANCHOR -> "系统未能信任站点证书"
        else -> null
    }
    is SSLPeerUnverifiedException -> "未能验证站点的服务器身份"
    else -> null
}
