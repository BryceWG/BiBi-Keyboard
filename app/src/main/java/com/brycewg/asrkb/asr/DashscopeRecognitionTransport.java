/**
 * DashScope Recognition 连接关闭适配，归属 asr 模块。
 * SDK getter 的泛型参数是私有嵌套类，通过 Java 通配符避免 Kotlin 引用不可见类型。
 */
package com.brycewg.asrkb.asr;

import com.alibaba.dashscope.api.SynchronizeFullDuplexApi;
import com.alibaba.dashscope.audio.asr.recognition.Recognition;

final class DashscopeRecognitionTransport {
    private DashscopeRecognitionTransport() {}

    static void close(Recognition recognition, String reason) {
        SynchronizeFullDuplexApi<?> duplexApi = recognition.getDuplexApi();
        if (duplexApi != null) {
            duplexApi.close(1000, reason);
        }
    }
}
