# 跨平台局域网分享 SDK 设计

日期：2026-10-04  
状态：待书面规格审阅

## 目标与边界

为 Windows、Android、iOS 原生应用提供可复用的局域网分享 SDK。业务应用通过语义一致的原生 API 发现设备、配对、连接和传送文本、图片、文件及手动选择的剪贴板内容，无须实现平台网络协议。SDK 不提供业务 UI、云端中继、跨网段发现、目录批量传输或中断续传。

首版最低版本为 Windows 10、Android API 26、iOS 15。移动端仅在宿主应用处于前台且 SDK 已启动时保证发现、监听与传输；系统暂停应用时，SDK 报告中断并在再次启动时重新发现。Windows 由宿主决定前台或后台运行。

## 架构与交付

```
Windows C++ API      Android Kotlin API      iOS Swift API
       |                    |                    |
Windows 平台适配器    Android 平台适配器       iOS 平台适配器
       +--------------------+--------------------+
                     稳定 C ABI
                        |
              C++17 共享核心 + OpenSSL
```

共享核心负责设备目录、协议编解码、连接状态、TLS、配对、可信设备校验、传输状态机、流式校验和事件分发。平台适配器负责 mDNS/DNS-SD、TCP socket 与系统生命周期集成、剪贴板、文件流、密钥的安全存储以及回调调度。核心不直接调用 UI。原生封装隐藏 C ABI 的内存管理，所有跨 ABI 的数据使用显式长度与释放函数，错误码稳定，异常不得跨 ABI 传播。

交付物包括 Windows DLL、导入库及 C++ 头文件、Android AAR（Kotlin API + JNI + 各 ABI 原生库）、iOS XCFramework（Swift API + 原生库）、集成说明、协议文档和无业务 UI 的互通测试宿主。三端 API 使用相同概念与状态；平台权限与文件句柄由宿主提供。

## 身份与发现

首次启动生成随机 UUIDv4 `DeviceId` 和长期身份密钥，在后续启动中保持稳定。`DeviceId` 表示一次应用安装实例；卸载后若身份材料不保留，新安装获得新身份。密钥材料由平台适配器安全保存：Windows 使用 DPAPI 保护，Android 使用 Keystore 包装持久化密钥，iOS 使用 Keychain。不能读取或安全保存身份时启动失败，不静默生成替代身份。

服务类型为 `_share._tcp.local.`。实例名可由设备名派生，但不得用作身份键。TXT 包含 `id`（DeviceId）、`name`（DeviceName）、`platform`（windows/android/ios）、`pv`（协议主次版本，例如 `1.0`）；值限制长度，解析时验证 UTF-8、字段格式和总大小。SRV 给出端口，A/AAAA 给出可用 IP 地址，因此不把 IP 写入 TXT。SDK 的 `DeviceInfo` 返回 `DeviceId`、名称、平台、协议版本、地址列表、端口、是否已配对与最后发现时间。mDNS 只提供候选地址，任何字段都不构成可信身份。

发现目录以 `DeviceId` 合并多个接口和地址，忽略自身 UUID。相同 UUID 的地址或元数据变化只发一次 `Updated`；首见发 `Online`；所有服务记录消失后经过 5 秒去抖再发 `Offline`。查询失败、权限拒绝和网络接口变化通过独立错误/状态事件报告；接口变化时重新注册并浏览。无效 TXT 或不兼容主版本的设备不进入可连接目录；不兼容版本可作为诊断事件报告。

## 连接与配对安全

使用 TCP + 双向证书 TLS 1.3，禁用 TLS 0-RTT。每端使用长期身份密钥关联的自签名证书；TLS 握手必须证明双方证书私钥持有。尚未配对时证书链本身不代表信任，SDK 在加密通道内交换双方 `DeviceId`、证书指纹、协议版本和随机数。双方使用 TLS exporter（标签 `EXPORTER-unishare-pairing-v1`、空 context、32 字节）及发起方/接收方顺序排列的设备 ID、证书公钥 SHA-256 指纹和各 32 字节随机数计算 SHA-256，再将摘要前 32 位模 1000000，显示补零后的六位校验码。两端用户核对一致并显式确认后，SDK 原子持久化对方 `DeviceId` ↔ 证书公钥指纹；接收方还须同意首次连接请求。配对失败、60 秒超时或码不一致时丢弃候选信任记录并关闭连接。每个来源每分钟最多发起 3 次配对，同时最多保留 4 个待确认请求。

已配对连接必须验证证书指纹与持久化绑定一致，并验证通道内声明的 `DeviceId`；不一致时关闭连接并报告 `IdentityChanged`，不自动覆盖信任。解除配对删除绑定。设备名、IP、端口和 mDNS 广播可被伪造，均不能绕过该验证。任何内容传输之前都要求完成配对及本次接收确认。SDK 不保证防范用户错误核对校验码或已被攻陷的终端。

## 传输协议与数据流

协议版本 `1.0`。TLS 通道内使用 `4 字节大端长度 + 1 字节消息类型 + 消息体` 帧，长度计入类型字节。控制消息体采用 Protocol Buffers，数据帧为 16 字节传输 ID、8 字节大端块序号和原始字节。消息类型固定为 `Hello=1`、`Offer=2`、`Accept=3`、`Reject=4`、`Data=5`、`Finish=6`、`Complete=7`、`Failed=8`、`Cancel=9`、`PairDecision=10`。控制消息的基础字段固定为：`Hello`（device_id、protocol_major、protocol_minor、nonce、public_key_fingerprint）；`Offer`（transfer_id、content_type、display_name、mime_type、byte_count）；`Accept/Reject/Cancel`（transfer_id、reason）；`Finish`（transfer_id、byte_count、sha256）；`Complete/Failed`（transfer_id、reason）。这些字段在 `.proto` 文件中使用稳定字段编号，生成的编码向量纳入跨端互通测试；平台封装不得自行定义字段。未知可选字段忽略；未知消息类型或主版本拒绝。控制帧上限 64 KiB，单个数据块上限 256 KiB，解析前检查长度。每实例最多 8 条连接、4 个待处理接收请求和 4 MiB 内存缓冲；超过限制返回 `ResourceLimit`。

内容类型为 `Text`（UTF-8）、`Image`（MIME 类型 + 原始图片字节）、`File`（显示名 + 大小 + 字节流）。剪贴板不是独立的线协议类型：`IClipboardService` 在宿主显式调用时读取纯文本或图片，再交给 `ITransferService` 发送。首版剪贴板不监听、不自动发送；收到内容后宿主决定是否调用写入剪贴板。文本上限默认 8 MiB，图片 100 MiB，文件 20 GiB，且受宿主更严格的配置约束；发送方提供大小，接收方在确认前可见。

发送流程：`Offer` → 接收方请求事件 → 宿主 `Accept`/`Reject`（默认 60 秒超时）→ `Data` 块 → `Finish`（SHA-256 摘要）→ `Complete`/`Failed`。每次传输生成随机 128 位 ID。单连接首版同时只运行一个数据传输；控制消息仍可用于取消。发送和接收都流式计算摘要、报告已传字节/总字节，并支持取消。接收方在接受前由宿主提供目标文件流或临时存储位置；SDK/适配器只写入该位置，校验成功后调用宿主完成回调，宿主决定最终文件名与落盘位置。失败、取消或摘要不符时关闭并清理临时输出，不交付不完整内容。文件名仅作显示，禁止直接拼接到目标路径。首版失败后重新发送，不续传。

## 公共 API 语义

- `IShareSdk`: `Initialize(config)`、`Start()`、`Stop()`、`GetState()`；配置设备名、资源上限、回调执行器和平台权限代理。`Stop` 取消未完成操作并完成其结果回调。
- `IDeviceDiscovery`: `GetDevices()` 快照；`Online`、`Updated`、`Offline`、`DiscoveryError` 事件。事件按设备顺序交付，回调在指定执行器上运行。
- `IConnectionManager`: `Connect(deviceId)`、`ConfirmPairing(requestId, codeMatches)`、`RejectPairing`、`GetTrustedDevices()`、`ForgetDevice(deviceId)`，以及配对请求/结果/身份变化事件。
- `ITransferService`: `SendText`、`SendImage`、`SendFile`；`IncomingOffer`、`Accept(requestId, destination)`、`Reject`、`Cancel(transferId)`；进度与最终结果事件。发送源和接收目标使用平台文件流/URI/句柄抽象，避免让核心猜测沙盒路径。
- `IClipboardService`: `ReadShareableContent()` 与 `WriteContent(content)`；只支持纯文本和图片。读取必须由宿主的明确用户操作触发。

异步方法立即返回操作句柄或 future/promise；操作完成恰好产生一个终态结果。错误分类为 `PermissionDenied`、`DiscoveryFailed`、`VersionMismatch`、`PairingRejected`、`AuthenticationFailed`、`IdentityChanged`、`TransferRejected`、`Cancelled`、`Timeout`、`NetworkLost`、`StorageFailed`、`IntegrityFailed`、`InvalidInput`、`ResourceLimit`，并保留可选平台原始错误。回调不得阻塞网络线程，宿主可设置执行器；未设置时使用 SDK 串行回调队列。

## 平台集成

Windows 使用 Win32 DNS-SD API 和系统 TCP 能力；宿主负责适用的防火墙配置。Android 使用 `NsdManager`，宿主声明当前目标 SDK 所需的局域网权限并处理运行时授权；系统版本升级引入的新权限由适配层检测并报告。iOS 使用 Network framework Bonjour 浏览/发布，宿主在 `Info.plist` 提供 `NSLocalNetworkUsageDescription` 和 `_share._tcp` 的 `NSBonjourServices` 声明。iOS 剪贴板访问可能产生系统提示。三端适配器均需处理 IPv4/IPv6、多网卡、网络切换及系统暂停/恢复。

## 验收与测试

1. C++ 核心测试覆盖设备去重及事件、协议边界/畸形帧、版本不兼容、配对状态、身份变化拒绝、取消/超时、摘要错误和资源上限。
2. 三端平台测试覆盖 mDNS 发布与浏览、身份材料跨重启保持、权限拒绝、剪贴板文本/图片读写、文件流及生命周期中断。
3. Windows↔Android、Windows↔iOS、Android↔iOS 每对双向完成发现、配对、文本、图片、文件和手动剪贴板分享；同一设备多个地址只显示一次，离线及改名产生正确事件。
4. 传输测试至少包含零字节文件、边界大小、网络中断、接收拒绝、错误摘要和恶意超长帧；未确认或身份不匹配时不得交付内容。
5. 文档说明宿主权限配置、原生包接入、API 生命周期、线程/回调规则、错误处理和协议兼容策略。

## 参考依据

- [RFC 6763: DNS-Based Service Discovery](https://www.rfc-editor.org/rfc/rfc6763.html)：SRV/TXT/A/AAAA 分工。
- [RFC 8446: TLS 1.3](https://www.rfc-editor.org/rfc/rfc8446.html) 与 [RFC 9266: TLS 1.3 channel binding](https://www.rfc-editor.org/rfc/rfc9266.html)：加密通道与 exporter。
- [Apple: Local Network Privacy](https://developer.apple.com/documentation/technotes/tn3179-understanding-local-network-privacy)、[Apple: UIPasteboard](https://developer.apple.com/documentation/uikit/uipasteboard)：iOS 权限与剪贴板。
- [Android: NsdManager](https://developer.android.com/reference/android/net/nsd/NsdManager)：Android DNS-SD 与版本化权限。
- [Microsoft: DnsServiceBrowse](https://learn.microsoft.com/en-us/windows/win32/api/windns/nf-windns-dnsservicebrowse)：Windows DNS-SD。

## 2026-10-04 Task 5 控制器批准的补充

尚未发布的协议 1.0 增加 PairDecision（消息类型 10），字段 1 为必须出现的
confirmed 布尔值。双方在加密 Hello 之后发送本地确认结果；只有双方同意后才可
持久化信任并允许内容传输。已配对发起方的显式 Connect 表示本次同意，接收方仍须
确认。具体字段、SAS 二进制顺序、错误与断线持久化语义见 docs/protocol-v1.md。
ISocket 增加 RemoteEndpoint()，返回传输层获得的规范源 IP（不含端口，IPv6 必要时
含 scope），用于每来源限速，不能使用对端自报的 DeviceId 代替。
