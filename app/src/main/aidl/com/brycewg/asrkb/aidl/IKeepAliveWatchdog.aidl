// 增强保活内部协议：独立特权进程观察应用 Binder 生命周期。
package com.brycewg.asrkb.aidl;

import android.os.Bundle;

interface IKeepAliveWatchdog {
    Bundle watch(IBinder client, String packageName, String token) = 0;
    void unwatch(IBinder client) = 1;
    void destroy() = 16777114;
}
