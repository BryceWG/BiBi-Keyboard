/** 本次录音的润色策略，独立于全局偏好。归属模块：ime。 */
package com.brycewg.asrkb.ime

internal sealed interface RecordingPolishPolicy {
    data object FollowSettings : RecordingPolishPolicy
    data object Skip : RecordingPolishPolicy
    data class UsePrompt(val title: String, val content: String) : RecordingPolishPolicy
}
