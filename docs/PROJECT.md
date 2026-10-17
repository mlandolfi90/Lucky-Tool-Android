# Android Remote Control MCP - Project Bible

This document is the source of truth for the Android Remote Control MCP project. It defines the architecture, technical decisions, conventions, and implementation guidelines.

**Project Goal**: Build an Android application that runs as an MCP (Model Context Protocol) server, enabling AI models to fully control an Android device remotely using accessibility services and screenshot capture.

---

## Table of Contents

1. [Architecture](#architecture)
2. [Tech Stack](#tech-stack)
3. [Folder Structure](#folder-structure)
4. [MCP Protocol Implementation](#mcp-protocol-implementation)
5. [MCP Tools Specification](#mcp-tools-specification)
6. [Android-Specific Conventions](#android-specific-conventions)
7. [Kotlin Coding Standards](#kotlin-coding-standards)
8. [UI Design Principles](#ui-design-principles)
9. [Testing Strategy](#testing-strategy)
10. [Build & Deployment](#build--deployment)
11. [Security Practices](#security-practices)
12. [Default Configuration](#default-configuration)
13. [Makefile Targets](#makefile-targets)
14. [Related Documentation](#related-documentation)

---

## Architecture

### Overview

The application is a **service-based Android app** that exposes an MCP server over HTTP (with optional HTTPS). It consists of three main components:

1. **AccessibilityService** — Provides UI introspection and action execution (including screenshot capture via `takeScreenshot()` API on Android 11+)
2. **McpServerService** — Runs the HTTP server implementing MCP protocol
3. **MainActivity** — UI for configuration and control

### Component Details

#### 1. AccessibilityService

- **Type**: Android `AccessibilityService`
- **Purpose**: Introspect UI hierarchy of all apps and perform actions
- **Lifecycle**: Runs as long as enabled in Android Settings (Accessibility)
- **Capabilities**: Traverse accessibility tree (full depth), find elements by text/content description/resource ID/class name, perform actions (click, long-click, scroll, swipe, type text via InputConnection), execute global actions (back, home, recents, notifications, quick settings)
- **Implementation**: Extends `android.accessibilityservice.AccessibilityService`, registers for all event types and all packages, stores singleton instance for inter-service communication, uses coroutines for non-blocking operations

#### 2. McpServerService

- **Type**: Android Foreground Service
- **Purpose**: Run HTTP server implementing MCP protocol
- **Lifecycle**: User-controlled via MainActivity (start/stop)
- **Capabilities**: HTTP/HTTPS server using Ktor, MCP JSON-RPC 2.0 protocol, bearer token authentication, configurable binding address (127.0.0.1 or 0.0.0.0), orchestrates calls to AccessibilityService
- **Implementation**: Foreground service with persistent notification, Kotlin coroutines for async request handling, reads configuration from DataStore, exposes Streamable HTTP endpoint at `/mcp` via MCP Kotlin SDK, graceful shutdown on service stop

#### 3. MainActivity

- **Type**: Android Activity (Jetpack Compose UI)
- **Purpose**: Configuration and control interface
- **Features**: Server status display (running/stopped), start/stop MCP server toggle, configuration settings (binding address, port, bearer token, auto-start on boot, HTTPS toggle and certificate management, remote access tunnel toggle and provider selection), quick links (enable accessibility service), connection info display (with tunnel public URL when connected), server logs viewer (recent server events including MCP tool calls and tunnel events)
- **Implementation**: Material Design 3 with dark mode support, Jetpack Compose, ViewModel for state management, observes service status via Flow/StateFlow

### Inter-Service Communication

**Pattern**: Singleton + StateFlow
- **AccessibilityService**: Stores singleton instance in companion object for direct access (system-managed, long-lived). Also provides screenshot capture via `takeScreenshot()` API (Android 11+)
- **McpServerService**: Exposes status via companion-level StateFlow for UI observation

### Service Lifecycle

The typical startup flow: User opens app → enables Accessibility Service in Android Settings → starts MCP server via button → McpServerService starts as foreground service → starts Ktor HTTP/HTTPS server → MCP server ready on configured address:port → user minimizes app (services continue in background) → MCP clients can connect and control device.

**Auto-start on Boot** (if enabled): Device boots → `BootCompletedReceiver` triggers → if auto-start enabled in settings → McpServerService starts automatically.

### Threading Model

- **Main Thread**: UI operations only (Compose, Activity lifecycle)
- **IO Dispatcher**: Network operations (Ktor server, HTTP requests)
- **Default Dispatcher**: CPU-intensive operations (screenshot encoding, accessibility tree parsing)
- **Service Operations**: All AccessibilityService operations must be posted to main thread (Android requirement)

---

## Tech Stack

### Core Technologies

- **Language**: Kotlin 2.3.10 (latest stable, Feb 2026)
- **Android Gradle Plugin (AGP)**: 8.13 (latest stable 8.x)
- **Gradle**: 8.14.4 (latest stable 8.x)
- **KSP**: 2.3.5 (Kotlin Symbol Processing, decoupled from Kotlin since 2.3.0)
- **Android SDK**: Target API 34 (Android 14), Minimum API 33 (Android 13 Tiramisu)
- **JDK**: Java 17 (standard for Android development)

### Frameworks & Libraries

- **Jetpack Compose**: UI framework (Material Design 3)
- **Lifecycle**: ViewModel, LiveData, Flow
- **DataStore**: Settings persistence (modern replacement for SharedPreferences)
- **Hilt**: Dependency injection (Dagger-based, official Android DI)
- **Ktor Server**: HTTP/HTTPS server (Kotlin-native, async, coroutine-based)
- **MCP Kotlin SDK**: Official Model Context Protocol implementation v0.15.0 (from Anthropic/ModelContextProtocol), including `Server`, the stateless Streamable HTTP transport (`mcpStatelessStreamableHttp`), and type-safe tool registration via `Server.addTool()`
- **SLF4J-Android**: Routes MCP SDK internal SLF4J logs to `android.util.Log`
- **Kotlinx Serialization**: JSON serialization for MCP protocol
- **Kotlinx Coroutines**: Async/concurrency
- **Android Log**: Logging (standard Android Log class)
- **Accompanist**: Compose utilities (permissions handling)
- **Google Play Services Location**: Device location via FusedLocationProviderClient

### Testing

- **JUnit 5**: Unit test framework
- **MockK**: Mocking framework for Kotlin
- **Turbine**: Flow testing library
- **Compose UI Test**: Jetpack Compose testing
- **Testcontainers Kotlin**: Container-based E2E tests
- **MCP Kotlin SDK Client**: SDK `Client` + `StreamableHttpClientTransport` for E2E tests

### Build Tools

- **Gradle 8.x**: Build system (Kotlin DSL)
- **Makefile**: Development workflow automation
- **ktlint** and **detekt**: Kotlin linting. detekt runs its default rules plus `config/detekt/detekt.yml`, whose
  only override exempts `@Composable` functions from `FunctionNaming` (Compose requires PascalCase).

---

## Folder Structure

- `app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/`
  - `McpApplication.kt` — Application class (Hilt setup)
  - `services/accessibility/` — `McpAccessibilityService.kt`, `AccessibilityTreeParser.kt`, `ElementFinder.kt`, `ActionExecutor.kt`, `ActionExecutorImpl.kt`, `AccessibilityServiceProvider.kt`, `AccessibilityServiceProviderImpl.kt`, `TypeInputController.kt`, `TypeInputControllerImpl.kt`, `ScreenInfo.kt`, `CompactTreeFormatter.kt`, `CompactFormatOptions.kt`, `AccessibilityNodeCache.kt`, `AccessibilityNodeCacheImpl.kt`
  - `services/screencapture/` — `ScreenCaptureProvider.kt`, `ScreenCaptureProviderImpl.kt`, `ScreenshotEncoder.kt`, `ScreenshotAnnotator.kt`, `ApiLevelProvider.kt`
  - `services/storage/` — `StorageLocationProvider.kt`, `StorageLocationProviderImpl.kt`, `FileOperationProvider.kt`, `FileOperationProviderImpl.kt`
  - `services/apps/` — `AppManager.kt`, `AppManagerImpl.kt`
  - `services/camera/` — `CameraProvider.kt`, `CameraProviderImpl.kt`, `ServiceLifecycleOwner.kt`
  - `services/location/` — `LocationProvider.kt`, `LocationProviderImpl.kt`
  - `services/mcp/` — `McpServerService.kt`, `BootCompletedReceiver.kt`, `AdbConfigHandler.kt`, `AdbConfigReceiver.kt`, `AdbServiceTrampolineActivity.kt`
  - `services/mcp/` (approval profiles) — `PolicyApprovalNotifier.kt`
  - `services/tunnel/` — `TunnelProvider.kt`, `TunnelManager.kt`, `CloudflareTunnelProvider.kt`, `CloudflaredBinaryResolver.kt`, `AndroidCloudflareBinaryResolver.kt`, `NgrokTunnelProvider.kt`, `RelayTunnelProvider.kt`, `RelaySession.kt`, `RelayTiming.kt`, `RelayTarget.kt`, `RelayFrames.kt`, `LocalRequestReplayer.kt`
  - `mcp/` — `McpServer.kt`, `McpStatelessTransport.kt`, `McpToolException.kt`, `CertificateManager.kt`
  - `mcp/tools/` — `McpToolUtils.kt`, `TreeFingerprint.kt`, `ScreenIntrospectionTools.kt`, `TouchActionTools.kt`, `NodeActionTools.kt`, `NodeSelectorResolver.kt`, `TextInputTools.kt`, `SystemActionTools.kt`, `GestureTools.kt`, `UtilityTools.kt`, `FileTools.kt`, `AppManagementTools.kt`, `CameraTools.kt`, `LocationTools.kt`
  - `mcp/tools/` (approval profiles) — `ApprovalTools.kt`
  - `mcp/auth/` — `BearerTokenAuth.kt`
  - `policy/` — per-app approval profiles (see [Approval Profiles](#approval-profiles-per-app-action-policy)): `WireCode.kt`
  - `policy/model/` — `PolicyActionType.kt`, `RuleMode.kt`, `ProfileTemplate.kt`, `SelectorKind.kt`, `PolicySelector.kt`, `AmountTier.kt`, `PolicyException.kt`, `ApprovalProfile.kt`, `PolicyExceptionKind.kt`, `ProfilesSnapshot.kt`, `PolicyLimits.kt`, `AmountLimits.kt`, `ProfileTemplates.kt`, `ProfileValidationError.kt`, `ApprovalProfileValidator.kt`
  - `policy/amount/` — `AmountScan.kt`, `AmountTokenizer.kt`, `AmountReader.kt`, `AmountParser.kt`
  - `policy/engine/` — `PolicyLabels.kt`, `ActionKeywords.kt`, `PolicyTarget.kt`, `PolicySubject.kt`, `DecisionReason.kt`, `PolicyDecision.kt`, `PolicyEngine.kt`, `OwnPackageName.kt`, `TargetClassifier.kt`, `AmountRule.kt`, `PolicyEngineImpl.kt`
  - `policy/subject/` — `ToolPolicyClass.kt`, `ToolPolicyCatalog.kt`, `SubjectResolution.kt`, `PolicySubjectResolver.kt`, `ToolSubjectResolver.kt`, `PolicyWindowSource.kt`, `PolicyWindowSourceImpl.kt`, `IntentTargetResolver.kt`, `IntentTargetResolverImpl.kt`, `WindowHitTester.kt`, `NodeLabels.kt`, `PolicyTargets.kt`, `SubjectSupport.kt`, `WindowSubjectResolver.kt`, `NodeSubjectResolver.kt`, `CoordinateSubjectResolver.kt`, `TextSubjectResolver.kt`, `NotificationSubjectResolver.kt`, `LaunchSubjectResolver.kt`, `ToolSubjectResolverImpl.kt`
  - `policy/approval/` — `ActionApprovalPolicy.kt`, `ActionApprovalState.kt`, `PendingAction.kt`, `CallFingerprint.kt`, `ExceptionEligibility.kt`, `PendingReevaluator.kt`, `ActionApprovalCoordinator.kt`, `PendingActionTable.kt`, `GrantLedger.kt`, `ActionApprovalCoordinatorImpl.kt`, `PolicyApprovalLabels.kt`
  - `policy/gate/` — `ToolPolicyGate.kt`, `GateTiming.kt`, `GateResults.kt`, `GateAssessment.kt`, `GateOutcomes.kt`, `GateAuthorizer.kt`, `ToolPolicyGateImpl.kt`, `PendingReevaluatorImpl.kt`
  - `policy/log/` — `ApprovalLogMessage.kt`, `ApprovalEventLogger.kt`
  - `policy/di/` — `PolicyModule.kt`, `PolicySubjectModule.kt`, `PolicyGateModule.kt`
  - `ui/` — `MainActivity.kt`
  - `ui/` (approval profiles) — `PolicyApprovalActivity.kt`
  - `ui/theme/` — `Theme.kt`, `Color.kt`, `Type.kt`
  - `ui/screens/` — `HomeScreen.kt`
  - `ui/screens/` (approval profiles) — `PolicyApprovalScreen.kt`
  - `ui/screens/settings/` (approval profiles) — `ApprovalProfilesScreen.kt`, `ApprovalProfileAddScreen.kt`, `ApprovalProfileEditorScreen.kt`, `ApprovalProfileAmountSection.kt`, `ApprovalProfileExceptionsSection.kt`, `ApprovalProfileComponents.kt`, `ApprovalProfileEditorActions.kt`, `ApprovalProfileUiText.kt`, `ApprovalProfileDestinations.kt`
  - `ui/components/` — `ServerStatusCard.kt`, `ConfigurationSection.kt`, `RemoteAccessSection.kt`, `ConnectionInfoCard.kt`, `PermissionsSection.kt`, `ServerLogsSection.kt`, `StorageLocationsSection.kt`
  - `ui/components/` (approval profiles) — `PolicyApprovalCardView.kt`, `ApprovalLogText.kt`
  - `ui/viewmodels/` — `MainViewModel.kt`
  - `ui/viewmodels/` (approval profiles) — `PolicyApprovalUiState.kt`, `PolicyApprovalViewModel.kt`, `PolicyApprovalCountViewModel.kt`, `ApprovalProfilesListState.kt`, `ApprovalProfilesViewModel.kt`, `ApprovalProfileEditorState.kt`, `ApprovalProfileEditorViewModel.kt`
  - `data/repository/` — `SettingsRepository.kt`, `SettingsRepositoryImpl.kt`, `RelaySettings.kt`, `RelaySettingsImpl.kt`
  - `data/repository/` (approval profiles) — `ApprovalProfileRepository.kt`, `ApprovalProfileRepositoryImpl.kt`, `ApprovalProfileResults.kt`, `ProfileStoreStatus.kt`
  - `data/model/` — `ServerConfig.kt`, `ServerStatus.kt`, `ServerLogEntry.kt`, `BindingAddress.kt`, `CertificateSource.kt`, `ScreenshotData.kt`, `TunnelProviderType.kt`, `TunnelStatus.kt`, `StorageLocation.kt`, `FileInfo.kt`, `AppInfo.kt`, `AppFilter.kt`, `CameraInfo.kt`, `CameraResolution.kt`, `LocationData.kt`
  - `di/` — `AppModule.kt`
  - `utils/` — `NetworkUtils.kt`, `PermissionUtils.kt`, `Logger.kt`, `RecentsUtils.kt`
- `app/src/main/res/` — `values/strings.xml`, `values/themes.xml`, `drawable/`, `mipmap/`, `xml/accessibility_service_config.xml`
- `app/src/main/AndroidManifest.xml`
- `app/src/debug/` — `AndroidManifest.xml` (debug overlay), `kotlin/.../debug/E2EConfigReceiver.kt`
- `app/src/test/kotlin/` — Unit tests and JVM-based integration tests
- `app/src/test/kotlin/.../integration/` — Integration tests (Ktor `testApplication`, no emulator)
- `e2e-tests/` — E2E tests (redroid/Podman, JVM-only module)
- `relay/` — Self-hosted WebSocket relay for the `RELAY` tunnel provider (Node 24 + `ws`, `node:test`, `Dockerfile`)
- `gradle/libs.versions.toml` — Gradle version catalog
- `build.gradle.kts`, `settings.gradle.kts`, `gradle.properties`
- `Makefile`, `CLAUDE.md`, `README.md`, `LICENSE`
- `.github/workflows/ci.yml` — GitHub Actions CI workflow
- `.github/workflows/relay.yml` — Relay lint, tests and image build (runs only when `relay/**` changes)

---

## MCP Protocol Implementation

### Protocol Overview

The application implements the **Model Context Protocol (MCP)** specification from Anthropic/ModelContextProtocol. MCP is a JSON-RPC 2.0 based protocol that enables AI models to interact with external tools and resources. The official **MCP Kotlin/JVM SDK** is used for message parsing/serialization, tool registration, error handling, and type-safe tool definitions.

### Transport Layer

- **Transport**: Streamable HTTP (JSON-only, no SSE) via the MCP Kotlin SDK's `StreamableHttpServerTransport(enableJsonResponse = true)`
- **Endpoint**: `POST /mcp` — Single MCP endpoint handling all protocol messages (initialize, tools/list, tools/call, etc.)
- **Framework**: Ktor Server (async, coroutine-based)
- **Authentication**: Global Application-level `McpAuthPlugin` enforcing combined (dual-accept) auth on `/mcp`: a request is authorized when it presents the static bearer token (`Authorization: Bearer <token>`) OR a valid issued OAuth access token. Two independent toggles control it — `bearer_token_enabled` (default true) and `oauth_enabled` (default true). Authentication is required iff at least one is enabled; with BOTH disabled the server is OPEN (explicit, allowed — the UI warns and confirms). Clearing the bearer token while `bearer_token_enabled=true` does NOT open the server: it fails CLOSED (401) until a token is set or bearer is disabled. The `/health` endpoint, the `/.well-known/` namespace, the unauthenticated OAuth endpoints (`/register`, `/authorize`, `/authorize/status`, `/token`), and the `/s/` capability route are excluded from auth. A 401 carries `WWW-Authenticate: Bearer resource_metadata="…/.well-known/oauth-protected-resource/mcp"` ONLY when OAuth is enabled.

#### OAuth 2.1 Authorization Server (self-contained)
The app is its own OAuth Authorization Server and Resource Server, so Claude.ai / Claude Desktop custom connectors (which speak only OAuth 2.1 + PKCE) can connect. Because the app both mints and verifies tokens, they are HS256-signed with a device-held secret (no JWKS).
- **Endpoints** (all unauthenticated, mounted only when `oauth_enabled`): `GET /.well-known/oauth-protected-resource` (and path-suffixed `…/mcp`), `GET /.well-known/oauth-authorization-server` (+ `openid-configuration` + path-suffixed alias), `POST /register` (RFC 7591 DCR), `GET /authorize` (+ `GET /authorize/status` poll), `POST /token`.
- **Tokens**: access JWT TTL 24h, refresh JWT TTL 90d, authorization code TTL 60s. Both access and refresh carry `aud` = the canonical resource (`<base>/mcp`) and a `client_id` claim. The signing secret is generated once (SecureRandom, base64url) and persisted in DataStore (`jwt_signing_secret`).
- **Approval**: on-device number-match — `/authorize` shows a 2-digit code and registers a pending approval (5-minute window); the user approves in-app (heads-up notification → approval screen) and `/authorize/status` polls the result.
- **Client registry**: persisted in a dedicated DataStore (`oauth_clients`), capped at 20 (eviction prefers clients that never completed a token grant, protecting approved clients from registration spam). Revoking a client immediately invalidates its tokens. `/mcp` validates signature + exp + `aud` + the client still being registered (instant revocation), and updates last-used (debounced).
- **Public base URL**: every absolute URL (OAuth metadata, `aud`, share links) is derived from the request (`X-Forwarded-Host`/`Host` + `X-Forwarded-Proto`), or pinned by the optional `public_url_override` setting (empty = auto-detect; also adb-configurable).
- **Content-Type**: `application/json`
- **Compatibility**: Standard MCP clients (`mcp-remote`, Claude Desktop, etc.) can connect via the standard `/mcp` endpoint

### Error Handling

Tool errors are returned as `CallToolResult(isError = true)` with an error message in `TextContent`, following the standard MCP SDK pattern. The SDK handles protocol-level errors (parse errors, invalid requests) automatically. Custom JSON-RPC error codes (`-32001` to `-32004`) are no longer exposed to clients — the server catches `McpToolException` subtypes and wraps them as tool-level error results.

### Authentication

- Every request must include `Authorization: Bearer <token>` header
- Token is validated by `BearerTokenAuth` Application-level plugin (constant-time comparison to prevent timing attacks)
- Invalid/missing token returns `401 Unauthorized`
- Token is stored in DataStore, configurable via UI

---

## MCP Tools Specification

The MCP server exposes 59 tools across 15 categories. For full JSON-RPC schemas, detailed usage examples, and implementation notes, see [MCP_TOOLS.md](MCP_TOOLS.md).

> **Approval profiles**: every tool call passes through `ToolPolicyGate` before it runs. A call on an app with an approval profile can return `forbidden`, `needs_human_confirmation`, `awaiting_approval`, `denied`, `expired`, `not_required`, `already_used` or `too_many_pending` instead of running. See [Approval Profiles](#approval-profiles-per-app-action-policy) and [MCP_TOOLS.md](MCP_TOOLS.md#approval-profiles).

> **Tool Naming Convention**: All tool names are prefixed with `android_` by default (e.g., `android_tap`, `android_find_nodes`). When a device slug is configured (e.g., `pixel7`), the prefix becomes `android_pixel7_` (e.g., `android_pixel7_tap`). See [MCP_TOOLS.md](MCP_TOOLS.md) for details.

### 1. Screen Introspection Tools (1 tool)

| Tool | Description | Parameters | Output |
|------|-------------|------------|--------|
| `android_get_screen_state` | Returns consolidated screen state: app info, screen dimensions, and a compact filtered flat TSV list of UI nodes | `include_screenshot` (boolean, optional, default false), `cursor` (string, optional), `include_offscreen` (boolean, optional, default true), `include_hierarchy` (boolean, optional, default true) | `TextContent` with compact TSV (text/desc truncated to 100 chars, comma-separated abbreviated flags with on/off visibility). Optionally includes `ImageContent` with annotated low-resolution JPEG screenshot (700px max, red bounding boxes with node ID labels). |

**Error**: Returns `CallToolResult(isError = true)` if accessibility permission not granted or screen capture not available.

**Note**: `android_get_screen_state` returns a filtered flat TSV list — structural-only nodes (no text, no contentDescription, no resourceId, not interactive) are omitted. Text and contentDescription are truncated to 100 characters; use `android_get_node_details` to retrieve full values. Flags use comma-separated abbreviations with a legend in the TSV notes. When screenshot is included, it is annotated with red bounding boxes and node ID labels for on-screen nodes. `include_offscreen=false` omits nodes not visible to the user and `include_hierarchy=false` omits the `hierarchy:` section; both apply to fresh captures only (cursor pages reuse the capture's values).

### 2. Touch Action Tools (5 tools)

| Tool | Description | Required Params | Optional Params |
|------|-------------|-----------------|-----------------|
| `android_tap` | Single tap at coordinates | `x` (number), `y` (number) | — |
| `android_long_press` | Long press at coordinates | `x` (number), `y` (number) | `duration` (number, ms, default 1000) |
| `android_double_tap` | Double tap at coordinates | `x` (number), `y` (number) | — |
| `android_swipe` | Swipe from point A to B | `x1`, `y1`, `x2`, `y2` (all number) | `duration` (number, ms, default 300) |
| `android_scroll` | Scroll in direction | `direction` (string: up/down/left/right) | `amount` (string: small/medium/large, default medium), `variance` (number: 0-20, default 5) |

**Errors**: Returns `CallToolResult(isError = true)` if accessibility not enabled or action execution failed.

### 3. Node Action Tools (5 tools)

| Tool | Description | Required Params | Optional Params |
|------|-------------|-----------------|-----------------|
| `android_find_nodes` | Find UI nodes by criteria | `by` (string: text/content_desc/resource_id/class_name), `value` (string) | `exact_match` (boolean, default false), `visible_only` (boolean, default false) |
| `android_click_node` | Click an accessibility node | `node_id` (string) OR selector `by` (string: text/content_desc/resource_id/class_name) + `value` (string) | `exact_match` (boolean, default false; selector only) |
| `android_long_click_node` | Long-click an accessibility node | `node_id` (string) | — |
| `android_tap_node` | Gesture-based tap at a random point within node bounds (unlike `android_click_node`, which uses ACTION_CLICK) | `node_id` (string) OR selector `by` (string: text/content_desc/resource_id/class_name) + `value` (string) | `exact_match` (boolean, default false; selector only), `inset_percentage` (number: 0.0-45.0, default 5.0) |
| `android_scroll_to_node` | Scroll to make node visible | `node_id` (string) | — |

**Errors**: Returns `CallToolResult(isError = true)` if node not found (ID invalid or stale) or node not clickable. `android_find_nodes` returns empty array (not error) when no matches found. `android_click_node`/`android_tap_node` with a selector return an error when no on-screen node matches, or an untrusted-warning error listing candidates when more than one on-screen node matches.

### 4. Text Input Tools (5 tools)

| Tool | Description | Required Params | Optional Params |
|------|-------------|-----------------|-----------------|
| `android_type_append_text` | Type text at end of field via InputConnection | `node_id` (string), `text` (string) | `typing_speed` (int, ms, default 250), `typing_speed_variance` (int, ms, default 50) |
| `android_type_insert_text` | Type text at specific position via InputConnection | `node_id` (string), `text` (string), `offset` (int) | `typing_speed` (int, ms, default 250), `typing_speed_variance` (int, ms, default 50) |
| `android_type_replace_text` | Find and replace text in field via InputConnection | `node_id` (string), `search` (string), `new_text` (string) | `typing_speed` (int, ms, default 250), `typing_speed_variance` (int, ms, default 50) |
| `android_type_clear_text` | Clear all text from field via select-all + delete | `node_id` (string) | — |
| `android_press_key` | Press a specific key | `key` (string: ENTER/BACK/DEL/HOME/TAB/SPACE) | — |

### 5. System Action Tools (6 tools)

| Tool | Description | Parameters |
|------|-------------|------------|
| `android_press_back` | Press back button | None |
| `android_press_home` | Navigate to home screen | None |
| `android_press_recents` | Open recent apps | None |
| `android_open_notifications` | Pull down notification shade | None |
| `android_open_quick_settings` | Open quick settings panel | None |
| `android_dismiss_keyboard` | Dismiss the on-screen soft keyboard if open | None |

### 6. Gesture Tools (2 tools)

| Tool | Description | Required Params | Optional Params |
|------|-------------|-----------------|-----------------|
| `android_pinch` | Pinch-to-zoom gesture | `center_x` (number), `center_y` (number), `scale` (number: >1 zoom in, <1 zoom out) | `duration` (number, ms, default 300) |
| `android_custom_gesture` | Multi-touch gesture from path points | `paths` (array of arrays of {x, y, time} objects) | — |

### 7. Utility Tools (5 tools)

| Tool | Description | Required Params | Optional Params |
|------|-------------|-----------------|-----------------|
| `android_get_clipboard` | Get current clipboard content | — | — |
| `android_set_clipboard` | Set clipboard content | `text` (string) | — |
| `android_wait_for_node` | Wait until node appears | `by` (string), `value` (string), `timeout` (number, ms, 1-30000) | — |
| `android_wait_for_idle` | Wait for UI to become idle | `timeout` (number, ms, 1-30000) | — |
| `android_get_node_details` | Get full untruncated text/desc by node_ids | `node_ids` (array of strings) | — |

**Timeout behavior**: Both `android_wait_for_node` and `android_wait_for_idle` require a mandatory `timeout` parameter (max 30000ms). On timeout, they return a non-error `CallToolResult` with an informational message (not an error).

### 8. File Tools (8 tools)

| Tool | Description | Required Params | Optional Params |
|------|-------------|-----------------|-----------------|
| `android_list_storage_locations` | List user-added storage locations with metadata | — | — |
| `android_list_files` | List files/directories in a storage location | `location_id` (string) | `path` (string, default ""), `offset` (int, default 0), `limit` (int, default 200, max 200) |
| `android_read_file` | Read text file with line-based pagination | `location_id` (string), `path` (string) | `offset` (int, 1-based line number, default 1), `limit` (int, default 200, max 200) |
| `android_write_file` | Write/create text file (creates parents, overwrites) | `location_id` (string), `path` (string), `content` (string) | — |
| `android_append_file` | Append to text file (tries native "wa" mode) | `location_id` (string), `path` (string), `content` (string) | — |
| `android_file_replace` | Literal string replacement in text file | `location_id` (string), `path` (string), `old_string` (string), `new_string` (string) | `replace_all` (boolean, default false) |
| `android_download_from_url` | Download file from URL to storage location | `location_id` (string), `path` (string), `url` (string) | — |
| `android_delete_file` | Delete a single file (not directories) | `location_id` (string), `path` (string) | — |

**Virtual path system**: All file tools use virtual paths: `{location_id}/{relative_path}` where `location_id` is `{authority}/{treeDocumentId}`. The location must be added by the user via the UI before file operations can be performed.

**File size limit**: All file operations are subject to the configurable file size limit (default 50 MB, range 1-500 MB). Stored in `ServerConfig.fileSizeLimitMb`.

**Encoding**: All text operations use UTF-8.

**Errors**: Returns `CallToolResult(isError = true)` if location not found, file not found, file exceeds size limit, or operation fails. `android_append_file` returns a hint to use `android_write_file` if the storage provider does not support append mode. `android_download_from_url` returns an error if HTTP downloads are disabled and the URL is HTTP, or if the download times out.

### 9. App Management Tools (3 tools)

| Tool | Description | Required Params | Optional Params |
|------|-------------|-----------------|-----------------|
| `android_open_app` | Launch an application by package ID | `package_id` (string) | — |
| `android_list_apps` | List installed applications with filtering | — | `filter` (string: all/user/system, default "all"), `name_query` (string) |
| `android_close_app` | Kill a background application process | `package_id` (string) | — |

**Errors**: `android_open_app` returns `CallToolResult(isError = true)` if the app is not installed or has no launchable activity. `android_close_app` only affects background processes — for foreground apps, use `android_press_home` first to send the app to the background. `android_list_apps` requires the `QUERY_ALL_PACKAGES` permission. `android_close_app` requires the `KILL_BACKGROUND_PROCESSES` permission.

### 10. Camera Tools (6 tools)

| Tool | Description | Required Params | Optional Params |
|------|-------------|-----------------|-----------------|
| `android_list_cameras` | List all available cameras with capabilities | — | — |
| `android_list_camera_photo_resolutions` | List supported photo resolutions for a camera | `camera_id` (string) | — |
| `android_list_camera_video_resolutions` | List supported video resolutions for a camera | `camera_id` (string) | — |
| `android_take_camera_photo` | Capture a photo and return as base64 JPEG (max 1920x1080) | `camera_id` (string) | `resolution` (string, WIDTHxHEIGHT), `quality` (int, 1-100, default 80), `flash_mode` (string: off/on/auto, default auto) |
| `android_save_camera_photo` | Capture a photo and save to storage location | `camera_id` (string), `location_id` (string), `path` (string) | `resolution` (string, WIDTHxHEIGHT), `quality` (int, 1-100, default 80), `flash_mode` (string: off/on/auto, default auto) |
| `android_save_camera_video` | Record video and save to storage (max 30s), returns thumbnail | `camera_id` (string), `location_id` (string), `path` (string), `duration` (int, 1-30) | `resolution` (string, WIDTHxHEIGHT), `audio` (boolean, default true), `flash_mode` (string: off/on/auto, default auto) |

**Errors**: All camera tools return `McpToolException.PermissionDenied` if CAMERA permission is not granted. `android_save_camera_video` also requires RECORD_AUDIO permission when audio is enabled. Save tools delegate write permission checks to `FileOperationProvider.createFileUri()`. Invalid parameters (resolution format, quality range, duration range, flash mode) return `McpToolException.InvalidParams`.

### 11. Intent Tools (2 tools)

| Tool | Description | Required Params | Optional Params |
|------|-------------|-----------------|-----------------|
| `android_send_intent` | Send an Android intent (activity, broadcast, or service) | `type` (string: activity/broadcast/service) | `action` (string), `data` (string), `component` (string, package/class), `package` (string, target package / setPackage), `extras` (object), `extras_types` (object), `flags` (string[]) |
| `android_open_uri` | Open a URI using ACTION_VIEW | `uri` (string) | `package_name` (string), `mime_type` (string) |

**Extras type inference**: String values → `String`, integers fitting Int range → `Int`, integers exceeding Int range → `Long`, decimals → `Double`, booleans → `Boolean`, string arrays → `StringArrayList`. Use `extras_types` to override (supported: `"string"`, `"int"`, `"long"`, `"float"`, `"double"`, `"boolean"`).

**Flags**: Resolved dynamically via `Intent::class.java.fields` reflection. `FLAG_ACTIVITY_NEW_TASK` auto-added for `type = "activity"`.

**Errors**: Invalid type/component/flag → `McpToolException.InvalidParams`. No handler found / permission denied / background start restriction → `McpToolException.ActionFailed` with sanitized message.

### 12. Notification Tools (6 tools)

| Tool | Description | Required Params | Optional Params |
|------|-------------|-----------------|-----------------|
| `android_notification_list` | List active notifications with structured data | — | `package_name`, `limit` |
| `android_notification_open` | Open/tap a notification | `notification_id` | — |
| `android_notification_dismiss` | Dismiss a notification | `notification_id` | — |
| `android_notification_snooze` | Snooze a notification for a duration | `notification_id`, `duration_ms` | — |
| `android_notification_action` | Execute a notification action button | `action_id` | — |
| `android_notification_reply` | Reply to a notification with text | `action_id`, `text` | — |

**Permission**: Requires "Notification Listener" special permission (Settings > Apps > Special app access > Notification access). This is NOT a runtime permission — the user must manually enable it in Settings.

**ID scheme**: `notification_id` = 6 hex chars (SHA-256 hash of notification key), `action_id` = 6 hex chars (SHA-256 hash of notification key + "::" + action index). Both returned by `android_notification_list`.

**Errors**: All tools return `McpToolException.PermissionDenied` if notification listener is not enabled. ID-based tools return `McpToolException.ActionFailed` if the notification/action is not found. `android_notification_snooze` validates `duration_ms` (positive, max 604800000 = 7 days).

### 13. Location Tools (1 tool)

| Tool | Description | Required Params | Optional Params |
|------|-------------|-----------------|-----------------|
| `android_get_location` | Get current location (coordinates, accuracy, reverse-geocoded street address) | — | `fresh_fix` (boolean, default false: request a fresh GPS fix instead of last known location) |

**Permission**: Requires `ACCESS_FINE_LOCATION` runtime permission. `fresh_fix` may take up to 10 seconds and can be force-disabled by the user via the MCP Tools settings toggle.

**Errors**: Returns `McpToolException.PermissionDenied` if location permission not granted. Returns `McpToolException.ActionFailed` if location services are unavailable, no last known location exists, or the fresh fix times out (10 seconds).

### 14. Sharing Tools (2 tools)

| Tool | Description | Required Params | Optional Params |
|------|-------------|-----------------|-----------------|
| `android_get_shared_content` | Return and clear items shared to the app via the Android Share sheet (read-once inbox) | — | — |
| `android_share_file_via_web` | Expose a device file as a temporary capability URL | `location_id` (string), `path` (string) | — |

**Capability URLs**: Random single-purpose token, expires after 1 hour, multiple fetches allowed, served from memory (total in-memory budget 64 MB). `android_share_file_via_web` is limited to the smaller of the configured file size limit and 64 MB.

**Errors**: `android_get_shared_content` has no specific errors — an empty inbox is a normal (non-error) response. `android_share_file_via_web` returns `McpToolException.InvalidParams` for missing/invalid `location_id` or `path`, and `McpToolException.ActionFailed` if the file is not found, the location is not authorized, or the file exceeds the size limit.

### 15. Approval Tools (2 tools)

| Tool | Description | Required Params | Optional Params |
|------|-------------|-----------------|-----------------|
| `android_get_approval_status` | State of an approval request (`pending` / `approved` / `denied` / `expired` / `not_required` / `already_used`), with an optional long-poll that returns when the request leaves `pending` | `approval_id` (string) | `wait_ms` (integer, 0-25000, default 0) |
| `android_confirm_human_approval` | Record the human's agreement in the conversation for an `ASK_IN_CHAT` request. Creates a single-use 2-minute grant for the identical call. | `approval_id` (string) | — |

**Not app-scoped and always registered**: profiles never gate these tools, and the per-tool permissions cannot disable them (otherwise an approval request could never finish). Results are server-generated, with no untrusted-content warning: `textResult` on success, `errorTextResult` on error. `android_get_approval_status` reads state and expiry in one `ActionApprovalCoordinator.statusOf` call and long-polls at most `min(wait_ms, expires_at_ms - now)`. An unknown `approval_id` reads `expired`.

**Errors**: `McpToolException.InvalidParams` for a missing or empty `approval_id`, or `wait_ms` outside 0-25000. `android_confirm_human_approval` returns `isError=true` with one of:
- `{"status":"not_confirmable"}` for a `NOTIFY` request;
- `{"status":"denied"}` for a denied request;
- `{"status":"expired"}` for an expired or unknown id;
- `{"status":"not_required"}` for a request closed because it no longer needs approval;
- `{"status":"already_used"}` for a request whose grant was already used.

---

## Android-Specific Conventions

### Service Lifecycle Management

- All long-running services (McpServerService) MUST run as foreground services with persistent notifications
- Call `startForeground()` within 5 seconds of service start, `stopForeground()` before destruction
- Service-to-UI communication via Flow/StateFlow (LocalBroadcastManager is deprecated and NOT used)

### AccessibilityService Best Practices

- Register only for needed event types (TYPE_WINDOW_STATE_CHANGED, TYPE_WINDOW_CONTENT_CHANGED)
- Keep `onAccessibilityEvent()` fast, offload heavy work to coroutines
- Cache accessibility tree when possible; call `node.recycle()` after use
- Check `node.refresh()` before using cached nodes (stale detection)
- Fresh tree reads (`getFreshWindows`, `rawNodeExists`, the wait_for_idle fingerprint, `findFocusedEditableNode`) clear the framework node cache via `AccessibilityServiceProvider.clearFrameworkNodeCache()` inside `AccessibilityTreeLock` before traversing; traversals MUST NOT call per-node `refresh()` (one blocking IPC per node on the target app's UI thread). `refresh()` is used only to validate nodes taken from `AccessibilityNodeCache` (ActionExecutorImpl S1 check)
- All node operations MUST happen on main thread
- Use `performAction()` for element actions, `performGlobalAction()` for system actions, `dispatchGesture()` for complex touch sequences (API 24+)

### `isAccessibilityTool` and `accessibilityDataSensitive` (Android 14+ / API 34)

- `accessibility_service_config.xml` declares `android:isAccessibilityTool="true"`. On API 34+, apps can mark UI subtrees `accessibilityDataSensitive`; those nodes are delivered **only** to services that declare `isAccessibilityTool="true"` (and to the system `UiAutomation`). Without the flag, such subtrees are silently filtered out of the tree we receive — e.g. the GitHub app renders as an empty `main_activity_container` in `get_screen_state`/`find_nodes` even though the content is fully present in the framework tree (confirmed via `uiautomator dump`). The flag is what lets this tool introspect and control apps that mark their content sensitive.
- **Security implication**: this intentionally bypasses the `accessibilityDataSensitive` anti-scraping protection that security-conscious apps (banking, password managers) rely on. This is acceptable for a user-installed, user-enabled remote-control tool, but is a deliberate trade-off that MUST stay documented.
- **Google Play implication (deferred)**: Play considers only genuine assistive tools eligible to declare `isAccessibilityTool`; declaring it on a remote-control app risks rejection. Current distribution is sideload / GitHub Releases / F-Droid, where the manifest is never reviewed, so the flag is fine. If Play distribution is pursued later, split into per-distribution build variants — a `play` flavor overriding `accessibility_service_config.xml` **without** the flag (plus the required prominent disclosure/consent flow), keeping the flag only in the sideload/F-Droid variant.

### Permission Handling

- **Accessibility**: User must enable manually in Settings (provide deep link). Also provides screenshot capture via `takeScreenshot()` API (Android 11+)
- **Internet**: Declared in manifest, granted automatically
- **QUERY_ALL_PACKAGES**: Required for listing all installed applications via `PackageManager`. Declared in manifest, granted automatically
- **KILL_BACKGROUND_PROCESSES**: Required for killing background app processes via `ActivityManager.killBackgroundProcesses()`. Declared in manifest, granted automatically
- **CAMERA**: Runtime permission required for camera photo/video tools. Requested via UI permission launcher
- **RECORD_AUDIO**: Runtime permission required for video recording with audio. Requested via UI permission launcher
- **ACCESS_FINE_LOCATION**: Runtime permission required for device location. Requested via UI permission launcher
- **ACCESS_COARSE_LOCATION**: Declared in manifest (implied by ACCESS_FINE_LOCATION). Provides location fallback
- Always check permission state before operations; return `CallToolResult(isError = true)` if permission missing

### Storage Access Framework (SAF) — User-Managed Locations

The application uses Android's Storage Access Framework (SAF) for unified, secure access to both physical and virtual storage (device storage, SD cards, Google Drive, Dropbox, etc.).

**User-managed model**: Storage locations are entirely user-driven — no automatic discovery.

**Add flow**:
1. The user taps "Add Storage Location" in the UI
2. A dialog appears with a description field and a "Browse" button
3. Tapping "Browse" launches the system file picker via `ACTION_OPEN_DOCUMENT_TREE`
4. The user selects a directory (mandatory Android security requirement — apps cannot self-grant storage access)
5. The granted tree URI is persisted via `ContentResolver.takePersistableUriPermission()` (read + write)
6. The location metadata (ID, name, path, description, tree URI) is stored in DataStore (via `SettingsRepository`)

**Location ID format**: `{authority}/{treeDocumentId}` derived from the tree URI (e.g., `com.android.externalstorage.documents/primary:Documents`). This provides a stable, cross-session identifier.

**Edit**: Users can update the description of any location via the UI.

**Delete**: Users can remove a location. The persistent URI permission is released via `ContentResolver.releasePersistableUriPermission()` and the entry is removed from DataStore. A confirmation dialog is shown before deletion.

**Duplicate prevention**: The app prevents adding the same tree URI twice (both in the UI and as a defense-in-depth check inside the provider).

**Description**: Each location has an optional user-provided description (max 500 characters) to give context/hints to MCP clients.

**Known constraint — Android URI permission limit**: Android enforces a limit on the number of persistable URI permissions per app (typically 128-512 depending on OEM/version). Once the limit is reached, oldest permissions may be silently evicted. In practice, users will have far fewer storage locations than this limit, but it is a known platform constraint.

### Storage Location Permissions

Each storage location has per-location permission flags controlling what MCP tools can do:

| Permission | Default (new) | Default (migrated) | Description |
|---|---|---|---|
| Read | Always `true` | Always `true` | List files and read file content. Cannot be disabled. |
| Write | `false` | `true` | Write, append, replace file content, download files. |
| Delete | `false` | `true` | Delete files. |

- Permissions are managed per-location via toggle switches in the UI.
- Permissions can be changed while the MCP server is running.
- When a tool attempts an operation not permitted by the location's permissions, a generic error is returned ("Write not allowed" or "Delete not allowed") with no guidance on how to enable the permission.
- The `list_storage_locations` MCP tool exposes `allow_read`, `allow_write`, `allow_delete` for each location.

### Background Restrictions & Memory Management

- Foreground services are exempt from Doze restrictions
- Never store Activity context in long-lived objects — use ApplicationContext
- Cancel coroutine scopes in `onDestroy()`; recycle large bitmaps after encoding; use `use {}` for automatic stream closure

### Threading Rules

- All AccessibilityService operations and UI operations MUST run on main thread
- Network operations (Ktor) on IO dispatcher, screenshot encoding and tree parsing on Default dispatcher

---

## Kotlin Coding Standards

### Naming Conventions

- **Classes/Interfaces**: PascalCase, no "I" prefix (e.g., `SettingsRepository`, not `ISettingsRepository`)
- **Functions/Variables**: camelCase (e.g., `captureScreenshot()`, `bearerToken`)
- **Constants**: UPPER_SNAKE_CASE (e.g., `DEFAULT_PORT`)
- **Backing fields**: underscore prefix (e.g., `_serverStatus`)
- **Packages**: All lowercase, no underscores

### Null Safety

- Prefer non-null types by default; use nullable types only when null is a valid state
- Avoid `!!` operator — use safe calls `?.`, `let {}`, or elvis operator `?:` instead
- Use `require()` or `check()` for preconditions

### Coroutines Best Practices

- Always use `CoroutineScope` (never `GlobalScope`); cancel scope in lifecycle cleanup
- Use `viewModelScope` for ViewModels, `lifecycleScope` for Activities
- Dispatchers: `Main` for UI/AccessibilityService, `IO` for network/file I/O/DataStore, `Default` for CPU-intensive work

### Code Organization

- File structure: package declaration → imports → class declaration → companion object → properties → init blocks → public methods → private methods → inner classes
- Keep classes focused (single responsibility), prefer files under 300 lines
- Prefer functions under 20 lines with meaningful names
- Prefer `val` over `var`; use `data class` for immutable data with `copy()` for modifications
- Use extension functions for utility operations; don't overuse — prefer member functions for core logic

---

## UI Design Principles

### Design System

- **Material Design 3** components (Compose Material3 library) with theme tokens for consistent styling
- Define primary/secondary/tertiary colors in `Color.kt`, type scale in `Type.kt`
- Support both light and dark themes; ensure sufficient contrast (WCAG AA minimum)
- Use semantic color names (e.g., `surfaceVariant`, not `grey200`)

### Visual Style

- Clean (minimal clutter, ample whitespace), modern (rounded corners, subtle shadows, smooth animations)
- Elevated cards for grouped content, filled buttons for primary actions, outlined for secondary
- Material Icons, switches for toggles, outlined text fields for input
- Consistent spacing scale (4dp, 8dp, 16dp, 24dp, 32dp), 16dp padding inside components

### Dark Mode

- Mandatory dark theme support; use dynamic colors (Material You) if appropriate
- Avoid pure white/black — use surface colors; test contrast in both modes

### Screen Structure

HomeScreen contains a TopAppBar, then a scrollable layout with: ServerStatusCard (status, start/stop), ConfigurationSection (binding address, port, token, auto-start, HTTPS, file size limit, download settings), RemoteAccessSection (tunnel toggle, provider selection, ngrok / relay config, tunnel status), StorageLocationsSection (user-managed SAF storage locations with add/edit/delete), PermissionsSection (accessibility/screenshot links), ServerLogsSection (scrollable recent server events including MCP tool calls and tunnel events), and ConnectionInfoCard (IP, port, token, tunnel URL, share button).

### Accessibility (UI)

- All interactive elements have minimum 48dp touch target
- Use `contentDescription` for icons/images; ensure logical focus order; support TalkBack; test with large text sizes

### Compose Best Practices

- PascalCase for composables, suffix with noun (e.g., `ServerStatusCard`, not `ShowServerStatus`)
- Hoist state to parent composables; use `remember` for UI state, `rememberSaveable` for surviving config changes
- Extract reusable components; use modifiers for customization; keep composables small and focused

---

## Testing Strategy

### Unit Tests

- **Framework**: JUnit 5, MockK, Turbine (for Flows)
- **Scope**: MCP protocol parsing/formatting, accessibility tree parsing, element finding, screenshot encoding, network utils, settings repository, ViewModel logic
- **Mocking**: Mock Android framework classes (AccessibilityNodeInfo, Context) with MockK; use `@MockK`/`@RelaxedMockK` annotations; verify interactions with `verify {}`
- **Pattern**: Arrange-Act-Assert consistently
- **Run**: `make test-unit` or `./gradlew test`

### Integration Tests

- **Framework**: Ktor `testApplication`, JUnit 5, MockK
- **Scope**: Full HTTP stack (authentication, JSON-RPC protocol, tool dispatch) via in-process Ktor test server; all 11 tool categories, error code propagation
- **Mocking**: Mock Android services (`ActionExecutor`, `AccessibilityServiceProvider`, `ScreenCaptureProvider`, `AccessibilityTreeParser`, `ElementFinder`, `TypeInputController`, `StorageLocationProvider`, `FileOperationProvider`, `AppManager`, `CameraProvider`, `LocationProvider`) via interfaces; real SDK `Server` with stateless Streamable HTTP routing (`mcpStatelessStreamableHttp`) and `BearerTokenAuth`
- **Infrastructure**: `McpIntegrationTestHelper` configures `testApplication` with same routing as production `McpServer`; uses SDK `Client` + `StreamableHttpClientTransport` for type-safe MCP communication
- **Run**: `make test-integration` or `./gradlew :app:testDebugUnitTest --tests "com.danielealbano.androidremotecontrolmcp.integration.*"`
- **Note**: JVM-based, no emulator or device required. Runs as part of `make test-unit` since both are under `app/src/test/`

### E2E Tests

- **Framework**: Testcontainers Kotlin (`redroid/redroid:14.0.0-latest`), JUnit 5, MCP Kotlin SDK Client
- **Scope**: Full MCP client → server → Android → action flow, Calculator app test (7 + 3 = 10), screenshot capture validation, error handling (auth, unknown tool, invalid params, node not found)
- **Infrastructure**: `SharedAndroidContainer` singleton shares one container across all test classes (avoids ~2-4 min boot per class); `McpClient` test utility wraps SDK `Client` + `StreamableHttpClientTransport` with trust-all TLS for self-signed certs; `E2EConfigReceiver` debug-only BroadcastReceiver injects test settings via `adb shell am broadcast`
- **Run**: `make test-e2e` or `./gradlew :e2e-tests:test`
- **Note**: E2E tests are slow (container startup, emulator boot). Run selectively during development, always in CI.

### Coverage

- **Target**: Minimum 80% code coverage for unit tests (Jacoco, enforced via `jacocoTestCoverageVerification`)
- **Report**: `make coverage` or `./gradlew jacocoTestReport`

### Continuous Testing

- Unit tests and JVM integration tests on every commit (fast); E2E tests on PR and pre-merge (slow, comprehensive)

---

## Build & Deployment

### Build System

- **Gradle 8.x** with Kotlin DSL (`build.gradle.kts`) and version catalog (`libs.versions.toml`)

### Build Variants

Two product flavors under the `distribution` dimension: **`gms`** (full app, GitHub/Play — includes Google
Play Services location + geofencing) and **`foss`** (F-Droid — no Google Play Services; `get_location` uses the
framework `LocationManager` and geofencing is excluded). The release applicationId is identical across flavors;
debug builds get a per-flavor suffix so both can be installed side by side.

| Variant | Application ID | Debuggable | Minify | Logging |
|---------|---------------|-----------|--------|---------|
| gmsDebug | `com.danielealbano.androidremotecontrolmcp.gms.debug` | true | false | Verbose |
| fossDebug | `com.danielealbano.androidremotecontrolmcp.foss.debug` | true | false | Verbose |
| gmsRelease / fossRelease | `com.danielealbano.androidremotecontrolmcp` | false | false (open source) | Info+ |

### Versioning

- **Semantic versioning** (MAJOR.MINOR.PATCH): MAJOR for breaking MCP protocol changes, MINOR for new features, PATCH for bug fixes
- `VERSION_NAME` is derived from git tags (with a `gradle.properties` fallback for git-less builds); the `versionCode` is derived from git history by Gradle, never hardcoded (see [TOOLS.md](TOOLS.md) → VERSION_CODE Derivation)
- Bump the version name via Makefile: `make version-bump-patch`, `make version-bump-minor`, `make version-bump-major` (the version code is git-derived, not bumped)

### APK Signing

- **Debug**: Default debug keystore (automatic)
- **Release**: Custom keystore via `keystore.properties` (gitignored), loaded in `app/build.gradle.kts`

### CI/CD (GitHub Actions)

- **Trigger**: Push to main, pull requests
- **Pipeline**: lint → test-unit (includes JVM integration tests) → test-e2e → build-release (sequential)
- **E2E tests**: Podman installed on GitHub Actions runners via CI workflow, Testcontainers configured via rootful podman socket
- **Artifacts**: Debug and release APKs uploaded on successful build

### Deployment

- Build release APK: `make build-release` → `app/build/outputs/apk/release/app-release.apk`
- Distribute via GitHub Releases (tag with version, attach APK, include changelog)

---

## Security Practices

### Bearer Token Security

- Stored in DataStore (Preferences DataStore, accessed via `SettingsRepository`)
- Auto-generated UUID exactly once on first launch (preserved across app upgrades); user can view, copy, regenerate via the UI (Settings → Access). Whether bearer auth is enforced is controlled by the `bearer_token_enabled` toggle, NOT by the value: disabling keeps the value (re-enabling restores it); enabling with an empty value auto-generates one.
- When `bearer_token_enabled=true`, every MCP request must present a valid `Authorization: Bearer <token>` header (or, when `oauth_enabled`, a valid OAuth access token — dual-accept). Clearing the token while bearer is enabled fails CLOSED (401) — it does NOT open the server. The server is open only when BOTH `bearer_token_enabled` and `oauth_enabled` are false.
- One-time migration on upgrade preserves current behavior: `bearer_token_enabled` is initialized once from whether a non-empty token already existed (fresh install → enabled + auto-generated token).
- Constant-time comparison to prevent timing attacks; return `401 Unauthorized` if invalid/missing

### HTTPS (Optional — Disabled by Default)

- **HTTP is the default and primary transport.** The server starts on plain HTTP. This is intentional and the recommended mode for most users.
- **Why HTTP is the priority**: The MCP server runs on an Android device whose IP address changes frequently (WiFi reconnects, mobile data, different networks). Standard/public Certificate Authorities (CAs) cannot issue valid TLS certificates for bare IP addresses or dynamic IPs. Any HTTPS certificate the device can generate will be self-signed, meaning every MCP client would need to explicitly trust it or disable certificate verification. This makes HTTPS impractical as a default — it adds configuration friction with no real security benefit for the primary use case (localhost via ADB port forwarding, where traffic never leaves the USB cable).
- **HTTPS is a nice-to-have, not a priority.** It exists for users who need encrypted transport over a local network (binding to `0.0.0.0`), but even then the certificate will be self-signed and clients must allow insecure/untrusted certificates. Users who enable HTTPS must understand this trade-off.
- **Remote access tunnels**: The app integrates with **Cloudflare Quick Tunnels** (no account required, random `*.trycloudflare.com` URL), **ngrok** (account required, optional custom domain) and a **self-hosted WebSocket relay** (your own server; the device connects outbound) to expose the local MCP server via a public HTTPS URL with valid certificates. See the Remote Access / Tunnel section below.
- **When HTTPS is enabled** (user opt-in via UI toggle):
  - **Option 1 — Auto-Generated Self-Signed Certificate**: Generated on first enable using Bouncy Castle, configurable hostname (default "android-mcp.local"), valid for 1 year, stored in app-private storage, regeneratable. Clients must allow insecure/self-signed certificates.
  - **Option 2 — Custom Certificate Upload**: User uploads `.p12`/`.pfx` file with password, supports CA-signed certificates, stored in app-private storage.

### Remote Access / Tunnel

The app supports exposing the local MCP server to the internet via tunnel providers. This allows MCP clients to connect from anywhere without port forwarding or VPN configuration.

- **Cloudflare Quick Tunnels** (default): Runs the `cloudflared` binary as a child process. Creates a temporary tunnel with a random `*.trycloudflare.com` HTTPS URL. No account or configuration needed. The cloudflared binary is bundled as a native library (`libcloudflared.so`) via a git submodule in `vendor/cloudflared/`.
- **ngrok**: Uses the `ngrok-java` library (JNI-based, in-process). Requires an ngrok authtoken (free tier available). Supports optional custom domains. Available on arm64-v8a and x86_64 devices.
- **WebSocket relay**: The device opens an outbound WebSocket (`<relayUrl>/device`, bearer-authenticated with the relay device token, ≥ 16 characters) to a relay you host — the Node service in `relay/` (container image from `relay/Dockerfile`). The relay forwards each HTTP request it receives as a JSON frame (`{"type":"request",id,method,path,headers,body(base64)}`); the app replays it against `http://127.0.0.1:<port>` (at most 8 concurrently, 100 s each, responses up to 8 MiB) and answers with a `response` frame. The relay URL is `ws[s]://host[:port]` only (no path); the public URL is the same authority with `wss`→`https` (`ws`→`http`). The provider reconnects with exponential backoff (1 s … 30 s, reset after a session ≥ 10 s) and redials a socket silent for 90 s. No inbound port on the device, no third-party account. The relay answers `GET /relay/health` itself; everything else reaches the app. Cleartext `ws://` is allowed only with a warning (loopback/private hosts excepted). Each relayed request is logged on both ends with the same `rid` (first 8 characters of the frame id): the relay writes `relay: request rid=… bodyMs=… deviceMs=… totalMs=…` and the app writes `Relay request rid=… queueMs=… replayMs=… totalMs=…` at info level under `MCP:RelaySession`. Only method, sanitized path, status, sizes and timings are logged, plus the JSON-RPC method and tool name of `POST /mcp` JSON bodies on the relay. Headers, query strings and bodies never are (see `relay/README.md`, "Request timing log").

Tunnel architecture:
- `TunnelProvider` interface defines `start(localPort, config)` / `stop()` with `status: StateFlow<TunnelStatus>`
- `TunnelManager` orchestrates provider lifecycle, reads `ServerConfig` to select the active provider
- `McpServerService` starts tunnel AFTER the Ktor server is running and stops tunnel BEFORE the server on shutdown
- Tunnel failure does NOT prevent the MCP server from running locally
- Tunnel URL is logged to both logcat and the UI server logs via `McpServerService.serverLogEvents` SharedFlow

### Network Security

- **Default binding**: `127.0.0.1` (localhost only, requires `adb forward tcp:8080 tcp:8080`)
- **Network mode**: `0.0.0.0` (all interfaces) — display security warning dialog when selected

| Connection Type | Binding: 127.0.0.1 | Binding: 0.0.0.0 |
|-----------------|-------------------|------------------|
| Mobile Data (4G/5G) | Not accessible | Not accessible from internet (CGNAT) |
| WiFi (Private) | Not accessible | Accessible to same-network devices |
| WiFi (Public) | Not accessible | **DANGER**: Accessible to anyone on public WiFi |
| USB Tethered | Not accessible | Accessible to tethered device |
| Device as Hotspot | Not accessible | Accessible to hotspot clients |
| ADB Port Forward | Accessible via host | Accessible via host |

**CORS (browser clients)**: The server sends permissive CORS (`Access-Control-Allow-Origin: *`, no credentials mode) so browser-based MCP clients (e.g. MCP Inspector) can complete the OAuth flow and `/mcp` exchange. This does not weaken authentication — `/mcp` still requires a bearer/OAuth token that a cross-origin page cannot obtain, and non-browser clients send no `Origin` so CORS is a no-op for them. **Trade-off**: the wildcard removes the browser same-origin barrier, so in OPEN mode (both auth methods disabled) on a network-reachable or DNS-rebindable binding, a malicious web page in the victim's browser could drive the tool surface. No `Origin`/`Host` allowlist is enforced because the device's public host is dynamic (changing IPs, Cloudflare/ngrok tunnels, the WebSocket relay, `public_url_override`); any static allowlist would break remote access. Mitigation: stay on the loopback binding and keep at least one auth method enabled unless the network is trusted.

### Permission Security

Only necessary permissions: `INTERNET`, `FOREGROUND_SERVICE`, `RECEIVE_BOOT_COMPLETED`, `QUERY_ALL_PACKAGES` (app listing), `KILL_BACKGROUND_PROCESSES` (app closing), `CAMERA` (camera tools, runtime), `RECORD_AUDIO` (video recording with audio, runtime), `ACCESS_FINE_LOCATION` (device location, runtime), `ACCESS_COARSE_LOCATION` (location fallback, declared), Accessibility Service (user-granted via Settings), SAF tree URI permissions (user-granted per storage location via system file picker). Display clear explanations before requesting.
Per-location read/write/delete permissions are enforced by `FileOperationProvider` — see the Storage Location Permissions section above.

### Code Security

- No hardcoded tokens/keys/passwords; all secrets in DataStore or injected at runtime
- Validate all MCP request parameters (type, range, format); sanitize inputs before AccessibilityService operations
- Don't leak sensitive information in error messages; don't expose internal paths/stack traces to clients

### Anti-Prompt-Injection (Tool Response Safety)

MCP tools return data originating from the Android device (UI element text, content descriptions, file contents, clipboard data, logcat output, notification text, app metadata). This data is untrusted — a malicious app could embed adversarial instructions in UI text that would be returned verbatim in tool responses.

**Mitigation**: Every tool that returns device-derived content prepends `McpToolUtils.UNTRUSTED_CONTENT_WARNING` as the first line of the response text. This warning instructs LLM clients to treat the content as untrusted and ignore any directives found within it.

- Helper functions: `untrustedTextResult()`, `untrustedTextAndImageResult()`, `untrustedImageResult()`
- Pure action confirmations (tap, click, swipe, etc.) that return only server-generated text are exempt
- New tools returning device content MUST use the `untrusted*` helpers
- Approval outcomes that name the target app (`forbidden`, `needs_human_confirmation`, `awaiting_approval`) use the `untrusted*` helpers; `denied`, `expired`, `not_required`, `already_used`, `too_many_pending` and the approval tools' results are server-generated and do not
- **Limitation**: Image content (screenshots, camera photos) cannot carry an inline text warning. The warning is added as a separate `TextContent` before the `ImageContent`, but a multimodal LLM processing the image directly could still be influenced by adversarial text rendered on screen. This is an inherent limitation of the MCP protocol.

### Approval Profiles (Per-App Action Policy)

The owner can attach an approval profile to an app (package) in **Settings → Approval profiles**. A profile applies to EVERY tool call from ANY MCP client that acts on that app. Apps without a profile behave as if the feature did not exist, except for the own-package guard, which is unconditional. The profile editor does not offer this app itself (`@OwnPackageName`).

**Data model** (`policy/model/`, kotlinx.serialization):

| Field | Type | Meaning |
|-------|------|---------|
| `packageName` | `String` | The app the profile belongs to |
| `template` | `ProfileTemplate` | `MONEY_APP` or `COMMON_APP`: the template it was created from |
| `rules` | `Map<PolicyActionType, RuleMode>` | One mode for each of `READ`, `NAVIGATE`, `TYPE`, `ACTION_BUTTON` |
| `maxAmount` | `String?` | Plain decimal string (`AmountLimits.canonical`). Any amount above it is `FORBIDDEN`. `null` = amounts are not checked. |
| `amountTiers` | `List<AmountTier>` | Ascending `{upTo, mode}` steps up to `maxAmount` |
| `notAmountFields` | `List<PolicyException>` | Fields the owner marked "not an amount" (by `PolicySelector`) |
| `notActionButtons` | `List<PolicyException>` | Elements the owner marked "not an action button" (by `PolicySelector`) |
| `updatedAtMs` | `Long` | Last change |

- **Rule modes**: `RUN` (runs), `ASK_IN_CHAT` (the agent must ask the human in the conversation and confirm with `android_confirm_human_approval`), `NOTIFY` (the owner approves on the device), `FORBIDDEN` (hard limit). Severity, strictest wins: `RUN` < `ASK_IN_CHAT` < `NOTIFY` < `FORBIDDEN`. New modes are additive enum values with their own severity.
- **Selectors**: `RESOURCE_ID` (the view id) when the element has one, otherwise `LABEL` (`<className>|<normalized hint, label or content description>`), always built by `PolicyLabels.preferredSelector`, so stored exceptions and live targets compare equal.
- **Validation** (`ApprovalProfileValidator`, pure). Each error is a `ProfileValidationError` shown through a string resource. A profile is valid only when:
  - the package name is a valid Android package name and all four rules are set;
  - a `MONEY_APP` profile has `maxAmount`;
  - `maxAmount` and every tier `upTo` are plain ASCII decimals `> 0` (`AmountLimits.parse`): `.` is the only decimal mark, at most 8 decimals (`AmountLimits.MAX_DECIMALS`), at most 30 characters, no sign, no exponent, no grouping separators; the stored form is `AmountLimits.canonical` (`1500.50` → `1500.5`);
  - tiers exist only with `maxAmount`, are at most 5, strictly ascending, each `upTo` ≤ `maxAmount`, and tier modes never get less strict as amounts grow;
  - each exception list has at most 100 entries and every selector value is non-blank and at most 256 characters.

**Storage**:
- A dedicated Preferences DataStore, `approval_profiles`, provided in `AppModule` like `oauth_clients`, is accessed only through `ApprovalProfileRepository`.
- One key per package (`profile:<packageName>`), holding the profile as JSON.
- Up to 100 stored profiles (readable and unreadable together).
- Writes: `upsert` (create or replace, keeping the stored exception lists), `updateSettings` (template, rules, `maxAmount`, tiers of an existing profile, atomically under the repository `Mutex`), `delete`, `addException` / `removeException`. Every write is validated first; the disk is written before the in-memory snapshot.
- The gate reads an in-memory `ProfilesSnapshot`, so profile edits apply to the next tool call without restarting the server.
- No MCP tool reads or writes this store.
- **Fail-closed**:
  - a stored value that cannot be decoded, names another package or fails validation puts its package in `ProfilesSnapshot.unreadable`; every non-read action on that package is then `FORBIDDEN` (reason `PROFILE_UNREADABLE`), and the log names only the package;
  - if the store cannot be read at all, `current()` throws `ProfileStoreUnreadableException` (`storeStatus` = `UNREADABLE`) and the next call retries the read; it never reads as "no profiles". The gate (`ProfilesView` in `policy/gate/GateAssessment.kt`) then hands the subject resolver the named marker `ProfilesView.UNREADABLE_STORE_MARKER` (not a valid package name) as the only profiled package and evaluates every target package as unreadable. Result:
    - every non-`READ` app-scoped call is `FORBIDDEN`: `profile_unreadable`, `own_package` for this app, or a `BlockCause` code when the target cannot be attributed;
    - `READ` calls whose targets resolve run; unattributable ones are `FORBIDDEN` (`target_unresolved`);
    - not-app-scoped and exempt-exit tools run without reading the store;
    - the mutation `Mutex` applies as if a profile existed.
  - The profile list shows an error card with "Try again" while the store is unreadable, and a delete-only row for each unreadable package.

**Templates** (applied on creation, editable afterwards): see [Approval Profile Defaults](#approval-profile-defaults).

**What a call does** (`policy/subject/`):
- `ToolPolicyCatalog` maps every registered tool to a class:
  - not app-scoped (file, camera and location tools, `list_apps`, `get_clipboard`, the sharing tools, the approval tools);
  - exempt exit actions (`press_home`, `press_recents`, `open_notifications`, `open_quick_settings`, `dismiss_keyboard`; also `press_key` `HOME`);
  - an app-scoped resolver kind.

  A tool missing from the catalog makes `LoggedToolRegistrar.addTool` throw `IllegalStateException` at registration (fail-closed).
- How the target app is found:
  - window reads use every visible app window (the strictest `READ` rule among them applies);
  - node tools use the resolved node's window (an ambiguous selector: every on-screen candidate, evaluated before the tool returns its ambiguity error);
  - coordinate tools use `WindowHitTester`: every window containing the point counts (except this app's own touch-through tool-call overlay), plus the nearest interactive element at the point; `pinch` hit-tests its centre and both finger start points (centre x ± `ActionExecutorImpl.PINCH_BASE_DISTANCE`, clamped like `ActionExecutorImpl.pinch`) and targets the union;
  - intents use the explicit component or package, else `PackageManager` resolution;
  - notification tools use the posting app.

  `getCurrentPackageName()` is never used to decide.
- How taps and clicks are classified:
  - a label matching an action keyword (whole word, accent-insensitive, `Locale.ROOT`, Spanish and English) is `ACTION_BUTTON`, even over an exception. The matching fails on the safe side: labels such as "Done" (Spanish `done`), "Sign in" or "Accept" count as action buttons;
  - an identified target with a non-keyword label is `NAVIGATE`;
  - a target with no usable label, or one that cannot be identified, is `ACTION_BUTTON`, unless its selector is in `notActionButtons`.
- `android_swipe` in a profiled app is `ACTION_BUTTON` (slide-to-pay). `android_scroll` is `NAVIGATE`.
- While at least one profile exists, each of these is `FORBIDDEN` before any rule is read (`BlockCause`):
  - a call that cannot be attributed to an app (`target_unresolved`);
  - an unresolvable implicit intent, or any implicit broadcast or service intent (`unresolved_implicit_intent`);
  - a broadcast or service intent into a profiled package (`background_component_in_profiled_app`);
  - a tap on the IME window while a profiled app or this app has input focus (`ime_input_in_profiled_app`).

**Amounts** (`policy/amount/AmountParser`, pure, regex-free, linear):
- Checked only when the profile has `maxAmount`. Then every number counts as an amount: type tools and `press_key` `DEL`/`TAB`/`SPACE` (the field's FINAL value: current text plus the edit, after `PlaceholderSubstitutor.substitute`), notification replies, intent data and extras, `open_uri` URIs, and clipboard text.
- The largest number wins. Ambiguous `1.500` / `1,500` reads as the larger value. Signs and `(500)` are ignored, scientific notation counts, Unicode digits count, currency symbols are ignored.
- Input over the length cap or an exponent over the cap counts as above `maxAmount`.
- Password fields and fields in `notAmountFields` never count. Words (`5k`, `mil`) are not numbers.
- Above `maxAmount` → `FORBIDDEN`. Otherwise the first tier whose `upTo` covers the amount applies (an amount above the last tier keeps the last tier's mode), combined with the action-type rule; strictest wins.

**Exceptions**:
- "Not an amount" and "not an action button" are learned only from the approval screen:
  - "Not an amount" is offered only when the reason is an amount reason, on an identifiable, non-password field with a selector;
  - "Not an action button" is offered only when the reason is `UNKNOWN_TARGET`, on an identifiable target with a selector.
- Each needs a second confirmation (a dialog).
- Each is listed in the profile editor and can be removed at any time. The UI says "until you remove it", never "forever".
- Adding one goes through `PendingReevaluator` (`policy/gate/PendingReevaluatorImpl`): it stores the exception with `addException`, re-runs the engine on the pending request's stored subject and applies the result with `applyReevaluation`; it never approves. A request that still needs approval stays pending; one that no longer needs approval closes as `NOT_REQUIRED` without a grant (the agent repeats the call and it runs); one that is now forbidden closes as `DENIED`. The result (`ExceptionReevaluation`) tells the approval screen whether the exception was stored, even when the request was no longer pending afterwards.

**Approvals** (`policy/approval/ActionApprovalCoordinator`, in-memory, `Mutex`-guarded like `OAuthApprovalCoordinatorImpl`):
- States (`ActionApprovalState`, separate from the OAuth `ApprovalState`; the wire code is the lowercase name): `PENDING`, `APPROVED`, `DENIED`, `EXPIRED` (not decided within the window), `NOT_REQUIRED` (closed by a re-evaluation to `RUN`), `ALREADY_USED` (approved and its grant consumed: a consumed grant marks every `APPROVED` entry with that fingerprint). An unknown id reads `EXPIRED`. Expiry wins over a late approval.
- Ids are 16 `SecureRandom` bytes in lowercase hex.
- A request stays pending for at most 5 minutes; at most 10 requests can be pending (a new one beyond that is refused with `too_many_pending`; joins never count).
- A grant lasts 2 minutes and is single use. A grant approved by the owner on the device satisfies `ASK_IN_CHAT` and `NOTIFY`; a grant confirmed through `android_confirm_human_approval` satisfies only `ASK_IN_CHAT`.
- Fingerprint: SHA-256 over the tool name, the canonical JSON arguments, the target package and the selector. Identical calls join the same pending entry; a stricter call upgrades it in place. A grant runs only the identical call.
- `NOTIFY` waits up to 25 s inside the call, then returns `awaiting_approval`.
- When `McpServerService` stops, the approval notification and its observer are cancelled, then every pending request becomes `DENIED` and every grant is dropped (`clearAll()`, before and again after the Ktor server stops).

**Concurrency** (`ToolPolicyGateImpl`):
- When at least one profile exists (or the store is unreadable), every call of a tool class that is not read-only (read-only: `SCREEN_READ`, `NODE_DETAILS_READ`, `NOTIFICATION_LIST`) takes a single mutation `Mutex`. Under it the call is resolved and evaluated ONCE, and an authorized call (`RUN`, or a consumed grant) runs before the lock is released: two window-tree reads, the gate's and the tool's.
- Tools without a resolver (not app-scoped, exempt exits) never read the store and never take the lock; with zero profiles nothing locks.
- A `NOTIFY` wait releases the lock. Once the owner approves, the call takes the lock again and is re-resolved and re-evaluated without waiting. It runs only when the fingerprint is unchanged and the decision is not stricter than the one authorized, or when the fresh decision allows it on its own; otherwise it returns the fresh outcome. A read-only class whose subject turns out to be non-`READ` takes the same re-check under the lock.
- The lock is held for the whole run of an authorized mutating call, so a long tool (`type`, about 250 ms per character) queues every other mutating call until it ends; calls of read-only classes do not wait for it.

**Own-package guard** (non-negotiable): any non-`READ` action whose target package is this app's own (`@OwnPackageName`, provided from `BuildConfig.APPLICATION_ID`, flavor and debug suffixes included) is `FORBIDDEN`, always, even with zero profiles. This covers:
- the approval screen, the profile editor and the settings;
- the app's notifications;
- intents to its components;
- coordinate taps landing on its windows.

Without it, an agent could approve its own requests.

**Owner UI**:
- Notification channel `policy_approvals` (importance HIGH). `PolicyApprovalNotifier` posts one heads-up notification (id 1004) for the unexpired `NOTIFY` requests: the app and action type for a single request, a count for several. `ASK_IN_CHAT` requests never notify. It is posted again only when the set of unexpired `NOTIFY` request ids changes, alerts only once while shown (`setOnlyAlertOnce(true)`), and times out with the latest pending window.
- The notification has NO action buttons, because an agent could press them through the notification tools. The owner taps it, which opens `PolicyApprovalActivity` (not exported, no `FLAG_SECURE`), and approves or denies there.
- The lock-screen (public) version names neither the app nor the action, and no version shows the amount.
- While at least one request is pending (`ASK_IN_CHAT` included), the Server screen shows a pending-approvals card (like the OAuth one) that opens the same approval screen. It is the path when the notification permission is denied or the notification was dismissed.
- The approval screen lists every pending request (`ASK_IN_CHAT` ones too) and is the only place an amount is displayed (never for password fields). Each card shows the app icon and name, action type, tool, amount, field label (display only), reason, rule and a countdown, with Deny / Approve (Approve disabled once the window has lapsed) and, when `ExceptionEligibility.selectorFor` offers one, the exception buttons. Action types and rule modes are labelled only through `PolicyApprovalLabels` (`policy_action_type_*`, `policy_rule_mode_*`), shared with the profile editor.
- The profile editor lives in Settings → Approval profiles: list (unreadable packages shown with delete only), add (launcher apps with search, or a package name; this app and already-profiled apps are not offered), template choice (`MONEY_APP` preselected, `DEFAULT_TEMPLATE`), the four rules, maximum amount and tiers with validation errors, and the learned exceptions with remove buttons. A stored readable profile is saved with `updateSettings`; any other package with `upsert`.

**`ASK_IN_CHAT` limitation**: the app cannot verify that the human was asked; this mode relies on the model. Use `NOTIFY` or `FORBIDDEN` where a guarantee is needed.

**Logging**:
- Log type `ServerLogEntry.Type.APPROVAL` (id 8, never renumbered), written only through `ApprovalEventLogger`.
- The message (`ApprovalLogMessage`) is `<outcome> action=<code> mode=<code> reason=<code> app=<package>`: absent fields are omitted and `app` is last. `reason` holds a `DecisionReason` code or a `BlockCause.code`.
- Outcome codes (`ApprovalLogOutcome`, stored on disk, never renamed), by writer:
  - the gate: `approved`, `denied`, `expired`, `not_required`, `already_used`, `forbidden`, `needs_confirmation`, `awaiting_approval`, `too_many_pending` (with the un-prefixed tool name and the duration);
  - `PendingReevaluatorImpl`: `reevaluated` (with the tool name);
  - the approval screen: `owner_approved` (`expired` for an approval that came too late), `owner_denied`, `exception_added` (whenever the exception was stored);
  - the profile editor: `exception_removed`, `profile_created`, `profile_updated`, `profile_deleted` (package only).
- The Logs screen has an Approvals chip and renders each line from string resources as outcome, app and reason.
- It NEVER records amount values, field text, selector values or approval ids.
- `TOOL_CALL` entries use the constant markers `forbidden`, `needs_confirmation`, `awaiting_approval` or `denied` for calls the gate halted that way; every other `isError` result (including `expired`, `not_required`, `already_used` and `too_many_pending`) keeps `failed`. `LogsScreen` and `ServerLogsSection` show those four markers from string resources (`ApprovalLogText.kt`), never raw.

---

## Default Configuration

### Server Defaults

- **Port**: `8080`
- **Binding Address**: `127.0.0.1` (localhost)
- **Bearer Token**: Auto-generated UUID once on first launch (persisted, upgrade-safe). Whether bearer auth is enforced is set by `bearer_token_enabled` (default true), not by the value — clearing the value (`--es bearer_token ""`) while enabled fails closed (401), it does NOT disable auth. To disable bearer auth use `--ez bearer_token_enabled false`.
- **OAuth**: Enabled by default (`oauth_enabled`, default true) so Claude.ai / Claude Desktop connectors work out of the box. Disable via the UI or `--ez oauth_enabled false`.
- **Public URL override**: Empty by default (`public_url_override`); when set (UI or `--es public_url_override <url>`) it pins the host used for OAuth metadata and share links.
- **HTTPS**: Disabled by default (HTTP is the primary transport). When enabled by the user, uses auto-generated self-signed certificate with hostname "android-mcp.local", 1-year validity. Clients must allow insecure/self-signed certificates.
- **Auto-start on Boot**: Disabled
- **Remote Access Tunnel**: Disabled by default
- **Tunnel Provider**: Cloudflare (no account required)
- **ngrok Authtoken**: Empty (required when using ngrok)
- **ngrok Domain**: Empty (auto-assigned when empty)
- **Relay URL**: Empty (required when using the WebSocket relay; `ws://host[:port]` or `wss://host[:port]`, the app appends `/device`)
- **Relay Device Token**: Empty (required when using the WebSocket relay; at least 16 characters, presented as bearer on the relay's `/device` socket)
- **File Size Limit**: 50 MB (range 1-500 MB, configurable via UI, applies to all file operations)
- **Allow HTTP Downloads**: Disabled (must be explicitly enabled to allow non-HTTPS downloads)
- **Allow Unverified HTTPS Certificates**: Disabled (must be explicitly enabled to accept self-signed/invalid certs for downloads)
- **Download Timeout**: 60 seconds (range 10-300 seconds, configurable via UI)
- **Device Slug** (`String`, default: `""`): Optional device identifier used as part of the MCP tool name prefix. When empty, tools use the `android_` prefix. When set (e.g., `pixel7`), tools use `android_pixel7_` prefix. Valid characters: letters (a-z, A-Z), digits (0-9), underscores (_). Maximum length: 20 characters.

### MCP Defaults

- **Screenshot Quality**: 80 (JPEG, 1-100)
- **Timeout**: 5000ms (wait_for_node), 3000ms (wait_for_idle)
- **Long Press Duration**: 1000ms
- **Swipe Duration**: 300ms
- **Gesture Duration**: 300ms
- **Scroll Amount**: "medium" (50% of screen dimension)

### Camera Defaults

- **Default Resolution**: 720p (closest available match)
- **Default Quality**: 80 (JPEG, 1-100)
- **Default Flash Mode**: auto (off/on/auto)
- **Max Video Duration**: 30 seconds
- **Default Audio**: Enabled (true)
- **Max Photo Resolution (take_camera_photo)**: 1920x1080 (to prevent excessively large base64 responses)

### Accessibility Defaults

- **Event Types**: TYPE_WINDOW_STATE_CHANGED, TYPE_WINDOW_CONTENT_CHANGED
- **Feedback Type**: FEEDBACK_GENERIC
- **Flags**: FLAG_REPORT_VIEW_IDS, FLAG_RETRIEVE_INTERACTIVE_WINDOWS, FLAG_INPUT_METHOD_EDITOR
- **Capabilities**: CAPABILITY_CAN_PERFORM_GESTURES, CAPABILITY_CAN_RETRIEVE_WINDOW_CONTENT

### Approval Profile Defaults

- **Profiles**: none. Every app behaves as before, except the own-package guard.
- **Templates** offered when a profile is created (`MONEY_APP` preselected; every value editable afterwards):

| | `MONEY_APP` | `COMMON_APP` |
|---|---|---|
| `READ` | `RUN` | `RUN` |
| `NAVIGATE` | `RUN` | `RUN` |
| `TYPE` | `ASK_IN_CHAT` | `RUN` |
| `ACTION_BUTTON` | `NOTIFY` | `ASK_IN_CHAT` |
| `maxAmount` | REQUIRED (cannot be saved without it); above it → `FORBIDDEN` | none (amounts not checked) |
| Amount tiers | none | none |

`maxAmount` is required for `MONEY_APP` because the app does not know which currency each app uses: the owner sets the number.

- **Approval window**: 300 000 ms (5 minutes)
- **Approval grant TTL**: 120 000 ms (2 minutes, single use)
- **`NOTIFY` wait inside the call**: 25 000 ms. Observed on 2026-10-13: the MCP client (claude.ai connector through the relay) cut a 60 s wait before the server answered; its exact timeout is not measured. After an approval the call still re-checks and runs, so a long call (typing) can end after the client gave up even though it ran
- **`android_get_approval_status` `wait_ms`**: 0 (range 0-25000)
- **Max pending approvals**: 10
- **Max profiles**: 100; at most 5 amount tiers and 100 entries per exception list per profile
- **Amount limits**: plain decimal `> 0`, `.` as the only decimal mark, at most 8 decimals, no thousands separators

### UI Defaults

- **Theme**: System default (light/dark follows system)
- **Language**: System default

---

## Makefile Targets

All common development tasks are accessible via `make <target>`. Run `make help` for a full list.

### Build & Clean

| Target | Description | Underlying Command |
|--------|-------------|-------------------|
| `build` | Build gms debug APK | `./gradlew assembleGmsDebug` |
| `build-release` | Build gms + foss release APKs | `./gradlew assembleGmsRelease assembleFossRelease` |
| `clean` | Clean build artifacts | `./gradlew clean` |

### Testing

| Target | Description | Underlying Command |
|--------|-------------|-------------------|
| `test-unit` | Run unit and JVM integration tests | `./gradlew test` |
| `test-integration` | Run JVM integration tests only | `./gradlew :app:testDebugUnitTest --tests "...integration.*"` |
| `test-e2e` | Run E2E tests (requires rootful podman socket) | `./gradlew :e2e-tests:test` |
| `test` | Run all tests sequentially | test-unit + test-e2e |
| `coverage` | Generate Jacoco coverage report | `./gradlew jacocoTestReport` |

### Linting

| Target | Description | Underlying Command |
|--------|-------------|-------------------|
| `lint` | Run all linters | `./gradlew ktlintCheck detekt` |
| `lint-fix` | Auto-fix linting issues | `./gradlew ktlintFormat` |

### Device Management

| Target | Description |
|--------|-------------|
| `install` | Install debug APK on connected device/emulator |
| `install-release` | Install release APK |
| `uninstall` | Uninstall app from device |
| `grant-permissions` | Grant permissions via adb (accessibility, notifications, camera, microphone) |
| `start-server` | Launch MainActivity on device via adb |
| `forward-port` | Set up adb port forwarding (device 8080 → host 8080) |

### Emulator

| Target | Description |
|--------|-------------|
| `setup-emulator` | Create AVD (API 34, x86_64, Pixel 6) |
| `start-emulator` | Start emulator in headless mode |
| `stop-emulator` | Stop running emulator |

### Logging, Versioning, All-in-One

| Target | Description |
|--------|-------------|
| `logs` | Show app logs via adb logcat (filtered by MCP tags) |
| `logs-clear` | Clear logcat buffer |
| `version-bump-patch` | Increment patch version (1.0.0 → 1.0.1) |
| `version-bump-minor` | Increment minor version (1.0.0 → 1.1.0) |
| `version-bump-major` | Increment major version (1.0.0 → 2.0.0) |
| `check-deps` | Check for required tools (Android SDK, Java 17+, Gradle, adb, Podman) |
| `all` | Run full workflow: clean → build → lint → test-unit |
| `ci` | Run CI workflow: check-deps → lint → test-unit (includes JVM integration) → test-e2e → build-release |

---

## Related Documentation

- **[TOOLS.md](TOOLS.md)** — Git branching conventions, commit format, PR creation, GitHub CLI commands, and local CI testing with `act`
- **[ARCHITECTURE.md](ARCHITECTURE.md)** — Detailed application architecture: component interactions, service lifecycle diagrams, threading model, inter-service communication patterns
- **[MCP_TOOLS.md](MCP_TOOLS.md)** — Full MCP tools documentation with JSON-RPC schemas, usage examples, error codes, and implementation notes for all 59 tools, plus the approval-profile outcomes

---

**End of PROJECT.md**
