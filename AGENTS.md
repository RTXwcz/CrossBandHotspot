# CrossBandHotspot

- 用户界面和主要文档使用简体中文，代码标识使用英文。
- 本项目针对Android16上的小米P2P共享接口，当前实机为小米平板7。兼容性事实见docs/validation.md。
- 频段必须支持自动、2.4GHz、5GHz，不能固定为2.4GHz。
- 构建入口为`python app/build.py`，依赖版本和SHA256固定在`app/dependencies.lock.json`。
- 修改热点生命周期时，同时检查实际上下游频率、客户端DHCP/DNS/HTTPS和停止清理。
- 切频中保留Helper/NAT/TAP，仅重建无线组；不要把重建中的临时group=null当成用户停止。
- 共享控制队列是进程级串行队列，旧Service清理必须先于新启动。
- Helper仅接受本机密钥认证的start/stop/status，不扩展为通用命令接口。
- 不提交构建产物、设备日志、ADB身份、配对码或签名私钥。
