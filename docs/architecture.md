# 实现与系统状态

## 频段选择

`MainActivity`保存名称、密码和频段；`HotspotService`持有Wi-Fi Direct Group Owner。

- 自动：`setGroupOperatingBand(0)`。
- 2.4GHz：`setGroupOperatingBand(1)`。
- 5GHz：上游已在5GHz时请求其实际频率；其他情况下请求band2。

原生热点在已验证固件上会跟随STA频段。本项目使用已实测支持跨频的P2P路径。

## 透明共享与DNS

Helper以shell身份调用小米`MiuiWifiService.startP2ptethering(p2p0, wlan0)`建立NAT和转发，再创建测试TAP并启动`TETHERING_VIRTUAL`请求，使系统共享状态机提供DNS转发。

普通应用不能直接获得`MANAGE_TEST_NETWORKS`或`TETHER_PRIVILEGED`。这些权限由shell Helper使用；应用本身通过公开的Wi-Fi P2P API管理无线组。

切换频段时，`creating`标记覆盖重建阶段。期间暂时的`group=null`不会发送Helper stop；NAT与TAP会话保留。只有显式停止或实际连接结束才完整清理。

原因是小米`stopP2ptethering`会调用全局`tetherStop`。AOSP TetherMain状态机的通知列表同时包含LOCAL_ONLY的P2P与TETHERED的TAP；通知列表未清空时，状态机继续保持Alive。此时OEM停掉DNS代理后再加入TAP，可能只更新DNS forwarders而没有重新启动代理。因此“SET DNS forwarders”日志本身不能证明DNS代理已运行。

一手实现参考：

- [AOSP Android16 TetherController](https://android.googlesource.com/platform/system/netd/+/refs/heads/android16-release/server/TetherController.cpp)：`usingLegacyDnsProxy=false`与DNS代理启动条件。
- [AOSP Android16 Tethering](https://android.googlesource.com/platform/packages/modules/Connectivity/+/refs/heads/android16-release/Tethering/src/com/android/networkstack/tethering/Tethering.java)：主状态机、通知列表与主共享启停。

## 重启恢复

`LocalAdb`保存应用独立生成的RSA身份与证书，通过NSD发现本机TLS ADB服务。首次配对成功后，经本机shell为应用授予可持久保留的`WRITE_SECURE_SETTINGS`。后续启动时应用设置`adb_wifi_enabled`，连接本机服务，仅执行固定的Helper启动命令。

Helper监听`127.0.0.1:37643`，验证应用私有密钥，只接受`start/stop/status`。热点生命周期的控制任务使用进程级串行队列，避免旧Service的停止任务晚于新Service启动。

## 代码入口

| 文件 | 职责 |
| --- | --- |
| MainActivity.java | 热点配置和启动入口 |
| HotspotService.java | 无线组、前台服务和生命周期 |
| PairingActivity.java / PairingService.java | 平板内配对与首次授权 |
| LocalAdb.java | 持久身份、本机TLS连接和固定后台启动 |
| Helper.java / HelperClient.java | 小米NAT、虚拟TAP及本机控制协议 |
