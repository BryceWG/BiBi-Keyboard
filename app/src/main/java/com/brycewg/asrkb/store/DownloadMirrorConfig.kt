/** GitHub 下载镜像前缀校验，归属 store。 */
package com.brycewg.asrkb.store

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

internal fun normalizeDownloadMirrorPrefix(value: String): String? {
    val trimmed = value.trim()
    if (!trimmed.startsWith("https://", ignoreCase = true) &&
        !trimmed.startsWith("http://", ignoreCase = true)
    ) {
        return null
    }
    if (trimmed.any { it.isWhitespace() } || '\\' in trimmed) return null
    val url = trimmed.toHttpUrlOrNull() ?: return null
    if (
        url.username.isNotEmpty() ||
        url.password.isNotEmpty() ||
        url.encodedQuery != null ||
        url.encodedFragment != null
    ) {
        return null
    }
    return url.toString().trimEnd('/') + "/"
}
