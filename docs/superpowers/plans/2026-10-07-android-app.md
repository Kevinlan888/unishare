# UniShare Android Client Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 构建一个接入现有 `share-sdk` 的原生 Android 客户端，完成设备发现、配对、文本/图片/文件收发、活动历史以及可选后台接收。

**Architecture:** 在现有 Android Gradle 工程中新增单 Activity Compose `app` 模块。应用级 `ShareSessionManager` 独占一个 `ShareSdk`，领域状态机和仓储隔离 SDK、Room、DataStore、ContentResolver 与前台服务；页面只消费不可变 StateFlow，并通过明确的用户动作调用协调器。

**Tech Stack:** Kotlin、AGP 9.2 内置 Kotlin、Jetpack Compose/Material 3、Navigation Compose、Kotlin Flow/Coroutines、Room 2.8.4、Preferences DataStore 1.2.1、KSP 2.3.10、JUnit 4、AndroidX Test。

**Spec:** `docs/superpowers/specs/2026-10-07-android-app-design.md`

## Global Constraints

- `namespace` 与 `applicationId` 均为 `com.unishare.app`；`minSdk 26`、`compileSdk 36`、`targetSdk 36`。
- 保留 AGP 9.2 的内置 Kotlin；不得添加 `org.jetbrains.kotlin.android` 或 kapt，Room 代码生成使用 KSP 2.3.10。
- Compose 固定使用 BOM `2026.02.01`，`activity-compose 1.12.4`、`navigation-compose 2.9.7`，避免引入要求 compileSdk 37 的后续版本。
- Android 应用依赖现有 `:share-sdk`，不得复制 SDK 逻辑、改变 C ABI 或修改 `share-sdk` 公开 API。
- 进程中只能存在一个 `ShareSdk`；UI、Service、Repository 均不得自行创建 SDK。
- 传输必须流式处理；不得把完整文件读入内存，不得记录正文、剪贴板内容、完整路径、完整 URI、设备名或密钥材料。
- Room 只保存摘要；每台设备最多 500 条且不超过 30 天。文本正文、图片字节、文件内容和发送源 URI 不持久化。
- 后台接收默认关闭；后台常驻与活动传输使用 `connectedDevice` 前台服务类型，不使用受六小时限制的 `dataSync` 类型，也不注册开机启动。
- 可信设备仅自动接受文本；图片、文件以及临时设备的所有内容必须逐次确认。
- 接收内容先写应用私有暂存文件，只有 SDK 成功终态与发布都成功后才暴露最终内容；所有失败路径必须清理半成品。
- 所有可见字符串必须同时提供 `values/strings.xml` 与 `values-zh-rCN/strings.xml`，主题默认跟随系统。
- 不提交 `windows/.vs/`、构建产物、接收内容、数据库、身份存储、日志或私钥。

## Review Focus

- 多设备并发发送时，首个未知进度必须绑定正确的待发送操作；迟到进度不得窃取下一次发送的操作 ID（Task 5 测试）。
- SDK 进度、终态和操作完成乱序或重复到达时，每条活动只能进入一次终态且不能倒退（Task 2、Task 6 测试）。
- 用户选择的 URI 在读取元数据或提交期间失效、大小未知或提供者阻塞时，不创建错误活动、不泄漏句柄且能安全停止（Task 6 测试）。
- 后台接收开关、通知权限与活动传输同时变化时，前台服务不能从不允许的后台状态启动，也不能提前取消用户已接受的传输（Task 7 测试）。
- 发布失败、进程死亡或十分钟恢复窗口到期时，最终目录不得出现半文件，私有暂存必须在下次启动清理（Task 4、Task 6 测试）。

---

### Task 1: Android 应用模块、依赖与主题骨架

**Files:**
- Modify: `android/build.gradle.kts`
- Modify: `android/settings.gradle.kts`
- Create: `android/gradle/libs.versions.toml`
- Create: `android/app/build.gradle.kts`
- Create: `android/app/proguard-rules.pro`
- Create: `android/app/src/main/AndroidManifest.xml`
- Create: `android/app/src/main/java/com/unishare/app/UniShareApplication.kt`
- Create: `android/app/src/main/java/com/unishare/app/MainActivity.kt`
- Create: `android/app/src/main/java/com/unishare/app/presentation/theme/Color.kt`
- Create: `android/app/src/main/java/com/unishare/app/presentation/theme/Shape.kt`
- Create: `android/app/src/main/java/com/unishare/app/presentation/theme/Theme.kt`
- Create: `android/app/src/main/java/com/unishare/app/presentation/theme/Type.kt`
- Create: `android/app/src/main/res/values/strings.xml`
- Create: `android/app/src/main/res/values-zh-rCN/strings.xml`
- Create: `android/app/src/main/res/values/themes.xml`
- Create: `android/app/src/main/res/drawable-nodpi/unishare_logo.png`
- Create: `android/app/src/main/res/drawable/ic_launcher_foreground.xml`
- Create: `android/app/src/main/res/mipmap-anydpi-v26/ic_launcher.xml`
- Create: `android/app/src/main/res/mipmap-anydpi-v26/ic_launcher_round.xml`
- Create: `android/app/src/test/java/com/unishare/app/BuildContractTest.kt`

**Interfaces:**
- Consumes: existing `:share-sdk`, AGP 9.2.0, Gradle 9.4.1, Java 17.
- Produces: installable `:app`, `UniShareTheme(darkTheme: Boolean, content: @Composable () -> Unit)`, and an empty `MainActivity` Compose root that Task 8 replaces with navigation.

- [ ] **Step 1: Write the failing module contract test**

Create `BuildContractTest` with `manifest_declares_expected_package`, `english_and_chinese_core_strings_exist`, and `theme_tokens_define_light_and_dark_palettes`; assert `com.unishare.app`, the four bottom-navigation labels, and nonidentical light/dark surface colors.

- [ ] **Step 2: Run the test to verify the app module is absent**

Run from `android`: `./gradlew.bat :app:testDebugUnitTest --tests "com.unishare.app.BuildContractTest"`

Expected: FAIL because project `:app` does not exist.

- [ ] **Step 3: Add the module and pinned dependencies**

Add `com.android.application` 9.2.0 and `com.google.devtools.ksp` 2.3.10 as root plugins, include `:app`, enable Compose, Java 17, BuildConfig off, vector drawables, unit Android resources, and dependencies pinned by Global Constraints. Add Room 2.8.4, DataStore 1.2.1, coroutines 1.10.2, lifecycle 2.10.0, JUnit 4.13.2, AndroidX Test, and Compose UI test artifacts.

- [ ] **Step 4: Add the minimal application, activity, resources, and design tokens**

Use the approved blue accent, low-contrast blue-gray surfaces, 16–28dp rounded shapes, Material 3 typography, edge-to-edge system bars, and the localized “UniShare” title. Reuse `logo/logo.png` as the source for an inset adaptive launcher icon. Do not add feature screens yet.

- [ ] **Step 5: Run module verification**

Run: `./gradlew.bat :app:testDebugUnitTest :app:assembleDebug`

Expected: PASS and `android/app/build/outputs/apk/debug/app-debug.apk` exists.

- [ ] **Step 6: Commit**

```powershell
git add android/build.gradle.kts android/settings.gradle.kts android/gradle/libs.versions.toml android/app
git commit -m "feat(android): scaffold Compose application"
```

### Task 2: 领域模型、接收策略与活动状态机

**Files:**
- Create: `android/app/src/main/java/com/unishare/app/domain/ShareModels.kt`
- Create: `android/app/src/main/java/com/unishare/app/domain/ReceivePolicy.kt`
- Create: `android/app/src/main/java/com/unishare/app/domain/ActivityReducer.kt`
- Create: `android/app/src/test/java/com/unishare/app/domain/ReceivePolicyTest.kt`
- Create: `android/app/src/test/java/com/unishare/app/domain/ActivityReducerTest.kt`

**Interfaces:**
- Consumes: no Android framework or SDK types.
- Produces:
  - `@JvmInline value class DeviceKey(val value: String)` and matching `ActivityKey`, `TransferKey`, `OperationKey`.
  - `ContentKind`, `ShareDirection`, `ShareOrigin`, `ActivityStatus`, `ShareErrorKind`, `ReceiveDecision`.
  - `data class ShareActivity(...)` containing summary metadata only.
  - `sealed interface ActivityEvent` and `fun ActivityReducer.reduce(current: ShareActivity, event: ActivityEvent): ShareActivity`.
  - `fun ReceivePolicy.decide(isTrusted: Boolean, kind: ContentKind): ReceiveDecision`.

- [ ] **Step 1: Write failing receive-policy tests**

Assert trusted text returns `AUTO_ACCEPT`; trusted image/file and every untrusted kind return `PROMPT`.

- [ ] **Step 2: Write failing reducer tests**

Cover progress clamping, zero-byte content, duplicate terminal events, terminal-after-terminal immutability, progress after terminal, failure mapping, and earlier timestamps. Assert no transition can leave a terminal state.

- [ ] **Step 3: Run the focused tests and verify failure**

Run: `./gradlew.bat :app:testDebugUnitTest --tests "com.unishare.app.domain.*"`

Expected: FAIL because domain types are missing.

- [ ] **Step 4: Implement immutable models and reducers**

Keep preview text/image references in a separate process-only `ActivityPreview` type that is not a field on the persisted record. The domain stores only `ContentKind.TEXT`; Task 9 supplies its localized presentation label.

- [ ] **Step 5: Run the focused and full unit suite**

Run: `./gradlew.bat :app:testDebugUnitTest`

Expected: PASS.

- [ ] **Step 6: Commit**

```powershell
git add android/app/src/main/java/com/unishare/app/domain android/app/src/test/java/com/unishare/app/domain
git commit -m "feat(android): add transfer domain state"
```

### Task 3: Room 活动摘要与 DataStore 设置

**Files:**
- Create: `android/app/src/main/java/com/unishare/app/data/history/ActivityEntity.kt`
- Create: `android/app/src/main/java/com/unishare/app/data/history/ActivityDao.kt`
- Create: `android/app/src/main/java/com/unishare/app/data/history/ActivityDatabase.kt`
- Create: `android/app/src/main/java/com/unishare/app/data/history/ActivityRepository.kt`
- Create: `android/app/src/main/java/com/unishare/app/data/history/RoomActivityRepository.kt`
- Create: `android/app/src/main/java/com/unishare/app/data/settings/AppSettings.kt`
- Create: `android/app/src/main/java/com/unishare/app/data/settings/SettingsRepository.kt`
- Create: `android/app/src/main/java/com/unishare/app/data/settings/DataStoreSettingsRepository.kt`
- Create: `android/app/src/androidTest/java/com/unishare/app/data/history/RoomActivityRepositoryTest.kt`
- Create: `android/app/src/androidTest/java/com/unishare/app/data/settings/DataStoreSettingsRepositoryTest.kt`
- Create: `android/app/schemas/.gitkeep`

**Interfaces:**
- Consumes: Task 2 domain identifiers and `ShareActivity`.
- Produces:
  - `ActivityRepository.observeRecent(limit: Int): Flow<List<ShareActivity>>`
  - `ActivityRepository.observeDevice(device: DeviceKey): Flow<List<ShareActivity>>`
  - `suspend fun upsert(activity: ShareActivity)`, `markNonTerminalInterrupted(nowMillis: Long)`, and `prune(nowMillis: Long)`.
  - `SettingsRepository.settings: Flow<AppSettings>` plus setters for device name, receive tree URI/display name, background receive, notifications, language, and theme.

- [ ] **Step 1: Write failing in-memory Room tests**

Test entity round-trip, enum mapping, device filter/order, upsert idempotence, marking nonterminal rows failed on restart, 500-row per-device pruning, and 30-day pruning without deleting another device's valid rows.

- [ ] **Step 2: Write failing temporary DataStore tests**

Assert background receive defaults false, language/theme default `SYSTEM`, receive URI is nullable, and each setter survives repository recreation.

- [ ] **Step 3: Compile instrumentation tests and verify failure**

Run: `./gradlew.bat :app:compileDebugAndroidTestKotlin`

Expected: FAIL because repositories and generated Room code do not exist.

- [ ] **Step 4: Implement Room schema version 1 and repository**

Use explicit converters, `String` UUID keys, indexed `(deviceId, updatedAt)` and `(status, updatedAt)`, and exported schemas under `android/app/schemas`. Persist final content URI only for successful incoming image/file rows.

- [ ] **Step 5: Implement Preferences DataStore repository**

Reject blank device names, store only sanitized directory display name plus URI, and keep all writes transactional through `edit`.

- [ ] **Step 6: Run connected data tests**

Run: `./gradlew.bat :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.unishare.app.data.history.RoomActivityRepositoryTest,com.unishare.app.data.settings.DataStoreSettingsRepositoryTest`

Expected: PASS on an attached API 26+ device/emulator.

- [ ] **Step 7: Commit**

```powershell
git add android/app/src/main/java/com/unishare/app/data android/app/src/androidTest/java/com/unishare/app/data android/app/schemas
git commit -m "feat(android): persist activity summaries and settings"
```

### Task 4: 私有暂存 ContentProvider 与安全发布

**Files:**
- Modify: `android/app/src/main/AndroidManifest.xml`
- Create: `android/app/src/main/java/com/unishare/app/data/content/StagingContentProvider.kt`
- Create: `android/app/src/main/java/com/unishare/app/data/content/StagingStore.kt`
- Create: `android/app/src/main/java/com/unishare/app/data/content/ReceivePublisher.kt`
- Create: `android/app/src/main/java/com/unishare/app/data/content/AndroidReceivePublisher.kt`
- Create: `android/app/src/androidTest/java/com/unishare/app/data/content/StagingContentProviderTest.kt`
- Create: `android/app/src/androidTest/java/com/unishare/app/data/content/AndroidReceivePublisherTest.kt`

**Interfaces:**
- Consumes: Task 2 `ContentKind` and `TransferKey`.
- Produces:
  - `data class PreparedStaging(val transferId: TransferKey, val uri: Uri, val file: File, val createdAtMillis: Long)`.
  - `StagingStore.create(transferId: TransferKey): PreparedStaging`, `delete(transferId)`, `cleanupOrphans(nowMillis)`.
  - `ReceivePublisher.publish(staging, displayName, mimeType, destination: ReceiveDestination): Uri`.

- [ ] **Step 1: Write failing provider contract tests**

Assert only opaque generated tokens under `${applicationId}.staging` open, each destination is a regular read/write file, traversal is rejected, `delete(uri)` returns one once and zero thereafter, and no external caller can enumerate staging files.

- [ ] **Step 2: Write failing publication tests**

Use a test provider/directory to verify success, `name (1).ext` collision behavior, source deletion only after durable publication, publish failure preserving private staging, 10-minute expiry, explicit discard, and startup cleanup after simulated process death.

- [ ] **Step 3: Run connected tests and verify failure**

Run: `./gradlew.bat :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.package=com.unishare.app.data.content`

Expected: FAIL because provider and publisher are missing.

- [ ] **Step 4: Implement the non-exported provider and staging store**

Declare `android:exported="false"` and `android:grantUriPermissions="true"`. Open with `ParcelFileDescriptor.open(..., MODE_READ_WRITE or MODE_TRUNCATE)` and implement resolver deletion exactly as required by `share-sdk` destination cleanup.

- [ ] **Step 5: Implement MediaStore Downloads and SAF-tree publication**

For MediaStore use pending rows until copy and flush complete; for SAF create a uniquely named document and delete it on copy failure. Stream with a bounded buffer and never expose staging before success.

- [ ] **Step 6: Run connected tests**

Run: `./gradlew.bat :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.package=com.unishare.app.data.content`

Expected: PASS and test cleanup leaves no files or MediaStore rows.

- [ ] **Step 7: Commit**

```powershell
git add android/app/src/main/AndroidManifest.xml android/app/src/main/java/com/unishare/app/data/content android/app/src/androidTest/java/com/unishare/app/data/content
git commit -m "feat(android): add safe receive staging"
```

### Task 5: SDK 网关、发送绑定与设备会话

**Files:**
- Create: `android/app/src/main/java/com/unishare/app/data/sdk/GatewayModels.kt`
- Create: `android/app/src/main/java/com/unishare/app/data/sdk/ShareSdkGateway.kt`
- Create: `android/app/src/main/java/com/unishare/app/data/sdk/OutgoingTransferRegistry.kt`
- Create: `android/app/src/main/java/com/unishare/app/data/sdk/AndroidShareSdkGateway.kt`
- Create: `android/app/src/main/java/com/unishare/app/data/sdk/ShareSessionManager.kt`
- Create: `android/app/src/main/java/com/unishare/app/data/device/DeviceRepository.kt`
- Create: `android/app/src/main/java/com/unishare/app/data/device/DefaultDeviceRepository.kt`
- Create: `android/app/src/test/java/com/unishare/app/data/sdk/OutgoingTransferRegistryTest.kt`
- Create: `android/app/src/test/java/com/unishare/app/data/sdk/ShareSessionManagerTest.kt`
- Create: `android/app/src/test/java/com/unishare/app/data/device/DefaultDeviceRepositoryTest.kt`

**Interfaces:**
- Consumes: existing `com.unishare.sdk` API and Task 2 domain types.
- Produces:
  - `ShareSdkGateway.events: Flow<GatewayEvent>`, lifecycle commands, snapshots, pairing commands, accept/reject/cancel, clipboard read, and `beginSend*` methods.
  - `data class PendingSend(val operationId: OperationKey, val completion: Deferred<TransferTerminal>)`.
  - `OutgoingTransferRegistry.register(operationId)`, `observeIncoming(transferId)`, `observeProgress(transferId): OutgoingBinding?`, `complete(operationId, transferId)`, and `clear()`.
  - `ShareSessionManager.state: StateFlow<SessionState>` with idempotent `initialize`, `start`, `stop`, and `close`.
  - `DeviceRepository.devices: StateFlow<List<DeviceSummary>>`, trusted lookup, connection, and forget operations.

- [ ] **Step 1: Write failing outgoing-registry tests**

Port the Windows registry contract: first unknown progress binds the oldest pending send; repeated progress binds nothing; incoming IDs never consume pending sends; completion-before-progress removes its operation; delayed terminal progress cannot consume the next send; `clear` retires all state. Add interleaved two-device submissions to document FIFO behavior inherited from serialized SDK submission.

- [ ] **Step 2: Write failing session and device tests with a fake gateway**

Assert one initialization, ordered observer registration, retry after initialization failure, no command before ready, idempotent stop/close, online-update-offline merge, trusted offline retention, no duplicates, and identity-changed blocking.

- [ ] **Step 3: Run focused tests and verify failure**

Run: `./gradlew.bat :app:testDebugUnitTest --tests "com.unishare.app.data.sdk.*" --tests "com.unishare.app.data.device.*"`

Expected: FAIL because gateway/session/device classes are missing.

- [ ] **Step 4: Implement SDK model mapping and CompletableFuture bridging**

Copy SDK values before emitting them, map stable error categories one-to-one, and use `suspendCancellableCoroutine`/`Deferred` without allowing coroutine cancellation to cancel or complete the SDK-owned future. Register outgoing operation IDs immediately after accepted `send*` calls and bind transfer IDs on first unknown progress exactly as `OutgoingTransferRegistry` specifies.

- [ ] **Step 5: Implement application-scoped session and device aggregation**

Subscriptions are created once after successful initialization and closed during final teardown. `stop` must finish before `setForeground(false)`; `close` is final and prevents future delivery.

- [ ] **Step 6: Run focused and full unit tests**

Run: `./gradlew.bat :app:testDebugUnitTest`

Expected: PASS.

- [ ] **Step 7: Commit**

```powershell
git add android/app/src/main/java/com/unishare/app/data/sdk android/app/src/main/java/com/unishare/app/data/device android/app/src/test/java/com/unishare/app/data
git commit -m "feat(android): bridge SDK lifecycle and devices"
```

### Task 6: 活动跟踪与传输协调器

**Files:**
- Create: `android/app/src/main/java/com/unishare/app/domain/ActivityTracker.kt`
- Create: `android/app/src/main/java/com/unishare/app/data/transfer/SelectedContent.kt`
- Create: `android/app/src/main/java/com/unishare/app/data/transfer/SelectedContentResolver.kt`
- Create: `android/app/src/main/java/com/unishare/app/data/transfer/TransferCoordinator.kt`
- Create: `android/app/src/test/java/com/unishare/app/domain/ActivityTrackerTest.kt`
- Create: `android/app/src/test/java/com/unishare/app/data/transfer/SelectedContentResolverTest.kt`
- Create: `android/app/src/test/java/com/unishare/app/data/transfer/TransferCoordinatorTest.kt`

**Interfaces:**
- Consumes: Tasks 2–5 repositories, gateway, staging, publisher, settings, device trust, and domain reducers.
- Produces:
  - `ActivityTracker.activities(device: DeviceKey): StateFlow<List<ShareActivity>>` and idempotent event application.
  - `SelectedContentResolver.resolve(uri: Uri, expected: ContentKind): SelectedContent` returning display name, MIME, exact byte count, and original URI without reading full content.
  - `TransferCoordinator.sendText`, `sendContent`, `sendClipboard`, `accept`, `reject`, `cancel`, `retry`, `pendingOffer`, and `activeTransferCount: StateFlow<Int>`.

- [ ] **Step 1: Write failing tracker tests**

Cover early transfer registration before the activity commit, progress before binding, duplicate incoming offers, loaded history colliding with a live transfer, duplicate terminal events, terminal immutability, manual retry linking, and persistence writes serialized after history hydration.

- [ ] **Step 2: Write failing selected-content tests**

Assert blank names are normalized, path segments are sanitized, exact size is mandatory, negative/unknown size is rejected, mismatched image MIME is rejected, revoked URI becomes `STORAGE_FAILED`, provider exceptions do not retain messages/paths, and blocking open can be retired without leaking descriptors.

- [ ] **Step 3: Write failing coordinator tests**

Test text/photo/file submission, clipboard text/image, unsupported clipboard, trusted-text auto-accept, prompt-only image/file, untrusted text prompt, accept/reject/cancel races, incoming success followed by publication, publication failure recovery for ten minutes, cleanup on failure, and outgoing completion without prior progress.

- [ ] **Step 4: Run focused tests and verify failure**

Run: `./gradlew.bat :app:testDebugUnitTest --tests "com.unishare.app.domain.ActivityTrackerTest" --tests "com.unishare.app.data.transfer.*"`

Expected: FAIL because tracker and coordinator are missing.

- [ ] **Step 5: Implement metadata resolution and activity tracking**

Never resolve filesystem paths from content URIs. Reserve an activity before SDK submission, associate the accepted `OperationKey`, then bind `TransferKey` through Task 5 events. Persist only sanitized summary fields.

- [ ] **Step 6: Implement send, receive, retry, and retirement coordination**

Keep source URI permissions and staging ownership until SDK terminal completion. Accepted incoming success is not a completed activity until Task 4 publication or text read/delete succeeds. Reject new work before session stop, wait for owned continuations, then release SDK observers.

- [ ] **Step 7: Run focused and full unit tests**

Run: `./gradlew.bat :app:testDebugUnitTest`

Expected: PASS.

- [ ] **Step 8: Commit**

```powershell
git add android/app/src/main/java/com/unishare/app/domain/ActivityTracker.kt android/app/src/main/java/com/unishare/app/data/transfer android/app/src/test/java/com/unishare/app/domain/ActivityTrackerTest.kt android/app/src/test/java/com/unishare/app/data/transfer
git commit -m "feat(android): coordinate real transfers"
```

### Task 7: 权限、通知与前台服务生命周期

**Files:**
- Modify: `android/app/src/main/AndroidManifest.xml`
- Create: `android/app/src/main/java/com/unishare/app/platform/PermissionPolicy.kt`
- Create: `android/app/src/main/java/com/unishare/app/service/ShareNotificationFactory.kt`
- Create: `android/app/src/main/java/com/unishare/app/service/ShareForegroundService.kt`
- Create: `android/app/src/main/java/com/unishare/app/service/ForegroundServiceController.kt`
- Create: `android/app/src/main/java/com/unishare/app/service/RuntimeController.kt`
- Create: `android/app/src/main/java/com/unishare/app/AppGraph.kt`
- Modify: `android/app/src/main/java/com/unishare/app/UniShareApplication.kt`
- Create: `android/app/src/test/java/com/unishare/app/service/RuntimeControllerTest.kt`
- Create: `android/app/src/androidTest/java/com/unishare/app/service/ShareForegroundServiceTest.kt`
- Create: `android/app/src/androidTest/java/com/unishare/app/platform/PermissionPolicyTest.kt`

**Interfaces:**
- Consumes: Tasks 3, 5, and 6 settings, session, device, and active-transfer state.
- Produces:
  - `PermissionPolicy.requirementsFor(api: Int, target: Int): PermissionRequirements`.
  - `RuntimeController.setAppVisible(Boolean)`, `setBackgroundReceive(Boolean)`, and `state: StateFlow<RuntimeState>`.
  - `ForegroundServiceController.start(reason: ServiceReason)` and `stopWhenIdle()`.
  - Process-wide `AppGraph` wiring all non-UI dependencies exactly once.

- [ ] **Step 1: Write failing runtime transition tests**

Cover foreground-only start/stop, background-enabled persistence, toggle-off with active work, temporary service for active sends, notification denial keeping background receive off, rapid lifecycle flapping, service start rejection, stop waiting for idle, and no second SDK instance.

- [ ] **Step 2: Write failing manifest and service instrumentation tests**

Assert `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_CONNECTED_DEVICE`, `CHANGE_NETWORK_STATE`, and `POST_NOTIFICATIONS`; service type `connectedDevice`; non-exported service; correct notification channel/actions; `START_STICKY`; and no boot receiver. Verify notification action stops background receive without cancelling active transfers.

- [ ] **Step 3: Run focused tests and verify failure**

Run: `./gradlew.bat :app:testDebugUnitTest --tests "com.unishare.app.service.RuntimeControllerTest" :app:compileDebugAndroidTestKotlin`

Expected: FAIL because runtime/service classes are missing.

- [ ] **Step 4: Implement permission policy and notifications**

Target 36 requires notification runtime consent on API 33+; local-network runtime permission is not requested while target remains 36. Create separate ongoing-receive and transfer-progress notification channels. Never include device names, filenames, or content in lock-screen text.

- [ ] **Step 5: Implement connected-device foreground service and runtime controller**

Only start a new foreground service from a visible Activity or an explicit notification PendingIntent. Promote within the platform deadline, handle task removal/system restart, and call session `setForeground(true)` before `start`. Stop in the reverse order after transfer drain.

- [ ] **Step 6: Run unit and connected service tests**

Run: `./gradlew.bat :app:testDebugUnitTest --tests "com.unishare.app.service.*" :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.package=com.unishare.app.service`

Expected: PASS.

- [ ] **Step 7: Commit**

```powershell
git add android/app/src/main/AndroidManifest.xml android/app/src/main/java/com/unishare/app/AppGraph.kt android/app/src/main/java/com/unishare/app/UniShareApplication.kt android/app/src/main/java/com/unishare/app/platform android/app/src/main/java/com/unishare/app/service android/app/src/test/java/com/unishare/app/service android/app/src/androidTest/java/com/unishare/app
git commit -m "feat(android): manage foreground sharing lifecycle"
```

### Task 8: 导航壳、分享首页与设计系统组件

**Files:**
- Modify: `android/app/src/main/java/com/unishare/app/MainActivity.kt`
- Modify: `android/app/src/main/java/com/unishare/app/UniShareApplication.kt`
- Create: `android/app/src/main/java/com/unishare/app/presentation/navigation/AppDestination.kt`
- Create: `android/app/src/main/java/com/unishare/app/presentation/navigation/UniShareNavHost.kt`
- Create: `android/app/src/main/java/com/unishare/app/presentation/components/DeviceIcon.kt`
- Create: `android/app/src/main/java/com/unishare/app/presentation/components/StatusIndicator.kt`
- Create: `android/app/src/main/java/com/unishare/app/presentation/components/EmptyState.kt`
- Create: `android/app/src/main/java/com/unishare/app/presentation/home/HomeUiState.kt`
- Create: `android/app/src/main/java/com/unishare/app/presentation/home/HomeViewModel.kt`
- Create: `android/app/src/main/java/com/unishare/app/presentation/home/HomeScreen.kt`
- Create: `android/app/src/androidTest/java/com/unishare/app/presentation/home/HomeScreenTest.kt`

**Interfaces:**
- Consumes: AppGraph, device repository, recent activity repository, runtime state, Task 1 theme.
- Produces: typed routes for Home/Session/Receive/Devices/History/Settings, bottom navigation shell, and `HomeUiState` with this-device, recent, nearby, loading, and discovery-error sections.

- [ ] **Step 1: Write failing Compose tests for shell and home**

Assert four localized bottom tabs, selected semantics, this-device card, recent/nearby headings, trusted/online labels, refresh, empty/error states, FAB, item click route, 48dp touch targets, and survival at 200% font scale without hiding actions.

- [ ] **Step 2: Compile and run the UI tests to verify failure**

Run: `./gradlew.bat :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.unishare.app.presentation.home.HomeScreenTest`

Expected: FAIL because navigation and home screen are missing.

- [ ] **Step 3: Implement typed navigation and bottom shell**

Show bottom navigation only on top-level destinations. Persist selected top-level state and restore nested destination/device ID after configuration change.

- [ ] **Step 4: Implement the reference-aligned home UI**

Use card groupings, rounded surfaces, platform icons, green online dot, recent progress summary, refresh action, and the blue circular FAB. Do not add folder or social/messaging concepts.

- [ ] **Step 5: Run connected Compose tests**

Run: `./gradlew.bat :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.unishare.app.presentation.home.HomeScreenTest`

Expected: PASS in English and Simplified Chinese parameterized cases.

- [ ] **Step 6: Commit**

```powershell
git add android/app/src/main/java/com/unishare/app android/app/src/androidTest/java/com/unishare/app/presentation/home android/app/src/main/res
git commit -m "feat(android): add sharing home navigation"
```

### Task 9: 设备会话、活动卡片与发送入口

**Files:**
- Create: `android/app/src/main/java/com/unishare/app/presentation/session/SessionUiState.kt`
- Create: `android/app/src/main/java/com/unishare/app/presentation/session/SessionViewModel.kt`
- Create: `android/app/src/main/java/com/unishare/app/presentation/session/SessionScreen.kt`
- Create: `android/app/src/main/java/com/unishare/app/presentation/session/ActivityCard.kt`
- Create: `android/app/src/main/java/com/unishare/app/presentation/session/ShareSheet.kt`
- Create: `android/app/src/main/java/com/unishare/app/platform/ContentPickerLauncher.kt`
- Create: `android/app/src/androidTest/java/com/unishare/app/presentation/session/SessionScreenTest.kt`
- Create: `android/app/src/test/java/com/unishare/app/presentation/session/SessionViewModelTest.kt`

**Interfaces:**
- Consumes: Task 6 coordinator/tracker, Task 5 devices, Android Photo Picker and `ACTION_OPEN_DOCUMENT`.
- Produces: per-device activity flow, composer actions, photo/file/clipboard/text/link send actions, cancel and retry UI events.

- [ ] **Step 1: Write failing ViewModel tests**

Assert offline/untrusted send gating, blank-text rejection, send clears composer only after accepted submission, picker cancellation is a no-op, failures produce one user event, cancel/retry target the selected activity, and device disappearance keeps historical content visible.

- [ ] **Step 2: Write failing Compose tests**

Assert outgoing-right/incoming-left alignment, text/image/file cards, waiting/progress/terminal content descriptions, progress clamp, cancel/retry buttons, attachment sheet entries excluding folder, and send control behavior under large fonts.

- [ ] **Step 3: Run focused tests and verify failure**

Run: `./gradlew.bat :app:testDebugUnitTest --tests "com.unishare.app.presentation.session.*" :app:compileDebugAndroidTestKotlin`

Expected: FAIL because session feature is missing.

- [ ] **Step 4: Implement ViewModel and picker launchers**

Use Photo Picker for images and `ACTION_OPEN_DOCUMENT` for files. Link uses `sendText`; clipboard calls the explicit SDK clipboard read only while Activity is resumed.

- [ ] **Step 5: Implement activity stream, cards, composer, and share sheet**

Auto-scroll only when the user is already near the end; new events must not pull a user away from older history. Rate/ETA are ephemeral presentation values and never persisted.

- [ ] **Step 6: Run unit and connected UI tests**

Run: `./gradlew.bat :app:testDebugUnitTest --tests "com.unishare.app.presentation.session.*" :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.unishare.app.presentation.session.SessionScreenTest`

Expected: PASS.

- [ ] **Step 7: Commit**

```powershell
git add android/app/src/main/java/com/unishare/app/presentation/session android/app/src/main/java/com/unishare/app/platform/ContentPickerLauncher.kt android/app/src/test/java/com/unishare/app/presentation/session android/app/src/androidTest/java/com/unishare/app/presentation/session android/app/src/main/res
git commit -m "feat(android): add device sharing session"
```

### Task 10: 配对与接收确认流程

**Files:**
- Create: `android/app/src/main/java/com/unishare/app/presentation/pairing/PairingUiState.kt`
- Create: `android/app/src/main/java/com/unishare/app/presentation/pairing/PairingViewModel.kt`
- Create: `android/app/src/main/java/com/unishare/app/presentation/pairing/PairingDialog.kt`
- Create: `android/app/src/main/java/com/unishare/app/presentation/receive/ReceiveUiState.kt`
- Create: `android/app/src/main/java/com/unishare/app/presentation/receive/ReceiveViewModel.kt`
- Create: `android/app/src/main/java/com/unishare/app/presentation/receive/ReceiveScreen.kt`
- Create: `android/app/src/test/java/com/unishare/app/presentation/pairing/PairingViewModelTest.kt`
- Create: `android/app/src/test/java/com/unishare/app/presentation/receive/ReceiveViewModelTest.kt`
- Create: `android/app/src/androidTest/java/com/unishare/app/presentation/receive/PairingAndReceiveUiTest.kt`

**Interfaces:**
- Consumes: gateway pairing events, coordinator pending offers, device metadata, Task 4 receive destination state.
- Produces: modal pairing confirmation and notification-deep-linkable receive route keyed by `ActivityKey`.

- [ ] **Step 1: Write failing pairing tests**

Assert exactly six digits including leading zeros, full accessibility description, one decision only, reject on mismatch, timeout/error dismissal, and identity-change route to forget/re-pair rather than bypass.

- [ ] **Step 2: Write failing receive tests**

Assert filename/type/size/source rendering, accept/reject exclusivity, disabled actions after decision, no “always accept” switch, expired offer handling, directory failure action, and notification deep link resolving only a still-pending activity.

- [ ] **Step 3: Run focused tests and verify failure**

Run: `./gradlew.bat :app:testDebugUnitTest --tests "com.unishare.app.presentation.pairing.*" --tests "com.unishare.app.presentation.receive.*" :app:compileDebugAndroidTestKotlin`

Expected: FAIL because pairing and receive features are missing.

- [ ] **Step 4: Implement pairing orchestration and dialog**

Serialize simultaneous requests, keep request IDs process-only, and call exactly one of confirm/reject. A configuration change must not repeat the decision.

- [ ] **Step 5: Implement receive detail screen and actions**

Use a safe generic preview before content completes; never open staging content. After successful publish, replace actions with “open” using the final content URI.

- [ ] **Step 6: Run unit and connected UI tests**

Run: `./gradlew.bat :app:testDebugUnitTest --tests "com.unishare.app.presentation.pairing.*" --tests "com.unishare.app.presentation.receive.*" :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.unishare.app.presentation.receive.PairingAndReceiveUiTest`

Expected: PASS.

- [ ] **Step 7: Commit**

```powershell
git add android/app/src/main/java/com/unishare/app/presentation/pairing android/app/src/main/java/com/unishare/app/presentation/receive android/app/src/test/java/com/unishare/app/presentation android/app/src/androidTest/java/com/unishare/app/presentation/receive android/app/src/main/res
git commit -m "feat(android): add pairing and receive consent"
```

### Task 11: 设备、历史、设置、本地化与辅助功能

**Files:**
- Create: `android/app/src/main/java/com/unishare/app/presentation/devices/DevicesViewModel.kt`
- Create: `android/app/src/main/java/com/unishare/app/presentation/devices/DevicesScreen.kt`
- Create: `android/app/src/main/java/com/unishare/app/presentation/history/HistoryViewModel.kt`
- Create: `android/app/src/main/java/com/unishare/app/presentation/history/HistoryScreen.kt`
- Create: `android/app/src/main/java/com/unishare/app/presentation/settings/SettingsViewModel.kt`
- Create: `android/app/src/main/java/com/unishare/app/presentation/settings/SettingsScreen.kt`
- Create: `android/app/src/main/java/com/unishare/app/presentation/format/DisplayFormatters.kt`
- Modify: `android/app/src/main/res/values/strings.xml`
- Modify: `android/app/src/main/res/values-zh-rCN/strings.xml`
- Create: `android/app/src/test/java/com/unishare/app/presentation/history/HistoryViewModelTest.kt`
- Create: `android/app/src/test/java/com/unishare/app/presentation/settings/SettingsViewModelTest.kt`
- Create: `android/app/src/androidTest/java/com/unishare/app/presentation/AppAccessibilityTest.kt`
- Create: `android/app/src/test/java/com/unishare/app/ResourceParityTest.kt`

**Interfaces:**
- Consumes: device repository, Room flows, settings repository, permission policy, runtime controller, SAF tree launcher.
- Produces: device management, searchable/filterable history, complete settings, locale-aware display formatting, and full bilingual resource coverage.

- [ ] **Step 1: Write failing ViewModel and resource tests**

Cover trusted/nearby/offline sections, forget confirmation, history query plus device/direction/type/status filters, default settings, notification-denied background toggle rollback, persisted directory grant, invalid device names, and exact English/Chinese key parity.

- [ ] **Step 2: Write failing accessibility Compose tests**

Assert every icon button has a role/label, status has text in addition to color, controls are at least 48dp, focus order follows visual order, progress terminal announcements are present but progress ticks are not live regions, and critical actions remain visible at 200% font scale in portrait and landscape.

- [ ] **Step 3: Run focused tests and verify failure**

Run: `./gradlew.bat :app:testDebugUnitTest --tests "com.unishare.app.presentation.history.*" --tests "com.unishare.app.presentation.settings.*" --tests "com.unishare.app.ResourceParityTest" :app:compileDebugAndroidTestKotlin`

Expected: FAIL because remaining screens/resources are missing.

- [ ] **Step 4: Implement devices and history screens**

History filtering occurs in Room where possible; search matches sanitized display names only. Opening a final URI first verifies read permission and gracefully disables missing content.

- [ ] **Step 5: Implement settings and directory selection**

The background switch requests notification permission while Activity is visible, then delegates to RuntimeController. Persist SAF access with `takePersistableUriPermission`; on revocation clear the stored URI and show a localized correction action.

- [ ] **Step 6: Complete bilingual resources, formatting, and semantics**

Use `DateFormat`, `NumberFormat`, and binary byte units through localized resources. Do not concatenate user-visible sentences in Kotlin.

- [ ] **Step 7: Run unit and accessibility tests**

Run: `./gradlew.bat :app:testDebugUnitTest :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.unishare.app.presentation.AppAccessibilityTest`

Expected: PASS.

- [ ] **Step 8: Commit**

```powershell
git add android/app/src/main/java/com/unishare/app/presentation android/app/src/test/java/com/unishare/app android/app/src/androidTest/java/com/unishare/app/presentation android/app/src/main/res
git commit -m "feat(android): complete management and settings UI"
```

### Task 12: 集成验收、构建脚本与文档

**Files:**
- Create: `android/scripts/test-app.ps1`
- Create: `android/scripts/build-app.ps1`
- Create: `android/tests/app-acceptance.md`
- Create: `docs/integration/android-app.md`
- Modify: `README.md`
- Modify: `docs/integration/README.md`
- Modify: `docs/HANDOFF.md`

**Interfaces:**
- Consumes: complete Tasks 1–11 app and existing Android/Windows SDK test infrastructure.
- Produces: repeatable local build/test commands, APK location, operator acceptance matrix, and release handoff.

- [ ] **Step 1: Add the failing acceptance checklist and script contract**

Document exact gates for API 26 emulator, API 33+ notification permission, API 35/36 foreground service, Android 17 compatibility mode, and physical Android↔Windows discovery/pairing/transfers. Add Pester-free PowerShell validation that expected tasks, APK, manifest permissions, and test reports exist.

- [ ] **Step 2: Run the script to verify it reports missing deliverables**

Run: `powershell -ExecutionPolicy Bypass -File android/scripts/test-app.ps1 -VerifyOnly`

Expected: FAIL until build/test outputs exist.

- [ ] **Step 3: Implement build and test orchestration**

`test-app.ps1` runs `:app:testDebugUnitTest`, `:app:lintDebug`, `:app:connectedDebugAndroidTest` when a device is selected, and `:share-sdk` contract checks without `clean`. `build-app.ps1` builds the debug APK and prints its SHA-256 and absolute output path without signing secrets.

- [ ] **Step 4: Run automated verification**

Run from repository root:

```powershell
powershell -ExecutionPolicy Bypass -File android/scripts/test-app.ps1
powershell -ExecutionPolicy Bypass -File android/scripts/build-app.ps1
git diff --check
```

Expected: all unit/lint/connected tests PASS, APK exists, SHA-256 is printed, and `git diff --check` is silent.

- [ ] **Step 5: Perform physical Android↔Windows acceptance**

Record device/API/app commit and results for discovery, first pairing, restart trust, forget/re-pair, text/image/file both directions, clipboard send, reject, cancel, network loss, background switch off/on, active transfer background completion, directory collision, and byte-for-byte file integrity. Do not mark unexecuted rows as passing.

- [ ] **Step 6: Update integration and handoff documentation with measured evidence**

Keep automated results separate from physical evidence. List any blocked rows and their exact missing environment rather than weakening the completion gate.

- [ ] **Step 7: Commit**

```powershell
git add android/scripts android/tests/app-acceptance.md docs/integration/android-app.md docs/integration/README.md docs/HANDOFF.md README.md
git commit -m "docs(android): add app build and acceptance workflow"
```

### Task 13: Final regression and release-candidate audit

**Files:**
- Modify only files required by failures found in this task.
- Review: `docs/superpowers/specs/2026-10-07-android-app-design.md`
- Review: `docs/superpowers/plans/2026-10-07-android-app.md`

**Interfaces:**
- Consumes: every prior task.
- Produces: one verified Android client candidate with no known automated regressions and an explicit list of any remaining operator-only blockers.

- [ ] **Step 1: Run the complete Android application gate from a clean process state**

Run:

```powershell
powershell -ExecutionPolicy Bypass -File android/scripts/test-app.ps1
.\android\gradlew.bat -p android :app:assembleRelease :share-sdk:assembleRelease
git diff --check
```

Expected: PASS with zero lint/test failures and both release artifacts built.

- [ ] **Step 2: Inspect release manifest and packaged contents**

Verify application ID, min/target SDK, non-exported provider/service, `connectedDevice` type, no boot receiver, no debug/test provider, expected ABIs, and no database/log/identity/sample content packaged.

- [ ] **Step 3: Re-run privacy and state-machine focused tests**

Run: `.\android\gradlew.bat -p android :app:testDebugUnitTest --tests "*Activity*" --tests "*Transfer*" --tests "*RuntimeController*" --tests "*ResourceParity*"`

Expected: PASS.

- [ ] **Step 4: Compare delivered behavior with every spec completion criterion**

Record each criterion as automated-pass, physical-pass, or blocked. A blocked physical row keeps release acceptance open but does not permit deleting the criterion.

- [ ] **Step 5: Commit only necessary audit fixes**

If Steps 1–4 required fixes, stage each changed file by its explicit literal path and run `git commit -m "fix(android): resolve release audit findings"`. Skip this commit if no files changed; never use `git add .` for the audit.
