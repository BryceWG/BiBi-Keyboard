// Classifies primary ASR terminal errors for backup failover arbitration.
package com.brycewg.asrkb.asr

import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.Locale
import javax.net.ssl.SSLException

internal enum class AsrPrimaryErrorStrategy {
    ImmediateFailover,
    WaitTimeout
}

internal object AsrPrimaryErrorClassifier {
    private val httpStatusRegex = Regex("""\bHTTP\s*(\d{3})\b""", RegexOption.IGNORE_CASE)
    private val explicitProviderErrorRegex = Regex("""\bASR\s*Error\s*[:=]?\s*[1-9]\d{2,}\b""", RegexOption.IGNORE_CASE)

    fun classify(
        error: Throwable? = null,
        message: String? = null,
        httpStatusCode: Int? = null
    ): AsrPrimaryErrorStrategy {
        if (AsrErrorMessageMapper.containsEmptyRecognitionSignal(message.orEmpty())) {
            return AsrPrimaryErrorStrategy.WaitTimeout
        }
        if (httpStatusCode != null && httpStatusCode in 400..599) {
            return AsrPrimaryErrorStrategy.ImmediateFailover
        }

        val throwableStrategy = classifyThrowable(error)
        if (throwableStrategy != null) return throwableStrategy

        return classifyMessage(message)
    }

    fun classifyMessage(message: String?): AsrPrimaryErrorStrategy {
        val raw = message.orEmpty()
        val lower = raw.lowercase(Locale.ROOT)

        if (AsrErrorMessageMapper.containsEmptyRecognitionSignal(raw)) {
            return AsrPrimaryErrorStrategy.WaitTimeout
        }

        val httpStatus = httpStatusRegex.find(raw)
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()
        if (httpStatus != null && httpStatus in 400..599) {
            return AsrPrimaryErrorStrategy.ImmediateFailover
        }

        if (containsAny(
                lower,
                "unknownhostexception",
                "unable to resolve host",
                "no address associated",
                "network is unreachable",
                "noroutetohostexception",
                "connection refused",
                "connectexception",
                "failed to connect",
                "sslhandshakeexception",
                "sslpeerunverifiedexception",
                "trust anchor",
                "certificate",
                "handshake failed",
                "authentication failed",
                "auth failed",
                "unauthorized",
                "forbidden",
                "invalid api key",
                "invalid token",
                "credentials invalid",
                "quota exceeded",
                "insufficient quota",
                "rate limit",
                "rate_limit"
            )
        ) {
            return AsrPrimaryErrorStrategy.ImmediateFailover
        }

        if (explicitProviderErrorRegex.containsMatchIn(raw)) {
            return AsrPrimaryErrorStrategy.ImmediateFailover
        }

        return AsrPrimaryErrorStrategy.WaitTimeout
    }

    private fun classifyThrowable(error: Throwable?): AsrPrimaryErrorStrategy? {
        var current = error
        while (current != null) {
            when (current) {
                is SocketTimeoutException -> return AsrPrimaryErrorStrategy.WaitTimeout
                is UnknownHostException,
                is ConnectException,
                is NoRouteToHostException,
                is SSLException -> return AsrPrimaryErrorStrategy.ImmediateFailover
            }
            current = current.cause
        }
        return null
    }

    private fun containsAny(lower: String, vararg hints: String): Boolean = hints.any { lower.contains(it) }
}
