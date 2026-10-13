# Application Architecture

This document describes the runtime architecture of the Android Remote Control MCP application.
It focuses on **how** components interact at runtime rather than **what** they are
(for design decisions and specifications, see [PROJECT.md](PROJECT.md)).

---

## Component Diagram

```mermaid
graph TB
    Client["MCP Client (AI)"]
    Client -->|"HTTP or HTTPS/TLS 1.2+"| McpServerSvc

    subgraph Device["Android Device"]
        subgraph MainAct["MainActivity (Compose UI)"]
            VM["MainViewModel"]
            VM --- Settings["Settings"]
            VM --- Status["Status"]
            Settings --- ProfEditor["ApprovalProfilesScreen / ApprovalProfileEditorScreen<br/>(Settings → Approval profiles)"]
            Status --- PendingCard["PendingPolicyApprovalsCard<br/>(Server screen, while requests are pending)"]
        end

        ApprovalAct["PolicyApprovalActivity<br/>(not exported)"]
        Notifier["PolicyApprovalNotifier<br/>(heads-up for NOTIFY requests, no action buttons)"]

        subgraph AccSvc["McpAccessibilityService (System-managed)"]
            TreeParser["AccessibilityTreeParser"]
            ElemFinder["ElementFinder"]
            ActionExec["ActionExecutor"]
            ScreenEnc["ScreenshotEncoder"]
            TypeInputCtrl["TypeInputController"]
        end

        subgraph StorageSvc["Storage & App Services"]
            StorageProv["StorageLocationProvider"]
            FileOps["FileOperationProvider"]
            AppMgr["AppManager"]
            IntentDisp["IntentDispatcher"]
            LocationProv["LocationProvider"]
        end

        subgraph NotifSvc["McpNotificationListenerService (System-managed)"]
            NotifProv["NotificationProvider"]
        end

        subgraph CameraSvc["Camera Services"]
            CamProv["CameraProvider\n(CameraX)"]
            SvcLifecycle["ServiceLifecycleOwner"]
            CamProv --> SvcLifecycle
        end

        subgraph McpServerSvc["McpServerService (Foreground Service)"]
            subgraph Ktor["McpServer (Ktor)"]
                HTTP["HTTP :8080 (HTTPS optional)"]
                StreamHTTP["Streamable HTTP /mcp\n(POST, DELETE; JSON-only, no SSE)"]
                Auth["BearerTokenAuth (global)"]
                SDK["SDK Server → Server.addTool()"]
                HTTP --> StreamHTTP --> Auth --> SDK
            end
            subgraph Policy["Approval policy (policy/)"]
                Gate["ToolPolicyGate<br/>(every tool call)"]
                Subject["ToolSubjectResolver<br/>(ToolPolicyCatalog + resolvers)"]
                Engine["PolicyEngine"]
                Coord["ActionApprovalCoordinator<br/>(in-memory)"]
                Reeval["PendingReevaluator"]
                ProfRepo["ApprovalProfileRepository<br/>(approval_profiles DataStore)"]
                Logger["ApprovalEventLogger<br/>(APPROVAL server log)"]
                Gate --> Subject
                Gate --> Engine
                Gate --> Coord
                Gate --> ProfRepo
                Reeval --> ProfRepo
                Reeval --> Engine
                Reeval --> Coord
                Gate --> Logger
                Reeval --> Logger
            end
            Handlers["Tool handlers<br/>(registered via LoggedToolRegistrar)"]
            subgraph Tunnel["TunnelManager (optional)"]
                CF["CloudflareTunnelProvider\n(process-based)"]
                Ngrok["NgrokTunnelProvider\n(in-process JNI)"]
                Relay["RelayTunnelProvider\n(outbound WebSocket)"]
                PubURL["Public HTTPS URL\n(*.trycloudflare.com / ngrok / relay)"]
                CF --> PubURL
                Ngrok --> PubURL
                Relay --> PubURL
            end
        end

        MainAct -->|"StateFlow (status)"| McpServerSvc
        ProfEditor -->|"edit profiles"| ProfRepo
        ProfEditor -->|"profile events"| Logger
        SDK -->|"every tools/call"| Gate
        Gate -->|"execute()"| Handlers
        Subject -->|"getWindows(), node lookup"| AccSvc
        Subject -->|"notification package"| NotifProv
        Coord -->|"observePending()"| Notifier
        Coord -->|"observePending() (count)"| PendingCard
        Notifier -->|"tap"| ApprovalAct
        PendingCard -->|"tap"| ApprovalAct
        ApprovalAct -->|"approve / deny"| Coord
        ApprovalAct -->|"add exception"| Reeval
        ApprovalAct -->|"owner events"| Logger
        Handlers -->|"Singleton\n(companion object)"| AccSvc
        Handlers --> StorageSvc
        Handlers --> LocationProv
        Handlers --> CameraSvc
        Handlers --> NotifSvc
    end
```

---

## Service Lifecycle

### Startup Sequence

1. **User opens app** -> `MainActivity.onCreate()` renders Compose UI
2. **User enables accessibility** -> System starts `McpAccessibilityService`
   - `onServiceConnected()` stores `instance` in companion object
   - Service remains running until disabled in Settings
3. **User taps "Start Server"** -> `MainViewModel.startServer()` called
   - Sends `ACTION_START` intent to `McpServerService`
   - `McpServerService.onStartCommand()`:
     a. Calls `startForeground()` with notification (within 5 seconds)
     b. Reads `ServerConfig` from `SettingsRepository`
     c. Gets/creates SSL keystore from `CertificateManager` (only if HTTPS enabled)
     d. Creates `McpServer` with config, keystore, and SDK `Server` (MCP Kotlin SDK)
        - Every tool is registered through `LoggedToolRegistrar.addTool`, which composes `logged(gate(indicator(handler)))`. Registration throws `IllegalStateException` if a tool is missing from `ToolPolicyCatalog`, so the server does not start with an unclassified tool. The two approval tools are always registered, whatever the per-tool permissions say.
     e. Starts Ktor server (HTTP by default, HTTPS if enabled)
        - Starts the action-approval observer: `PolicyApprovalNotifier.awaitingChanges(ActionApprovalCoordinator.observePending())` updates the notification only when the set of unexpired `NOTIFY` request ids changes
     f. Updates `ServerStatus.Running` via companion-level StateFlow
     g. If tunnel enabled: starts `TunnelManager` (connects to Cloudflare, ngrok or the WebSocket relay)
     h. Tunnel status and URL logged to UI via `serverLogEvents` SharedFlow

### Shutdown Sequence

1. **User taps "Stop Server"** -> `MainViewModel.stopServer()` called
   - Sends `ACTION_STOP` intent to `McpServerService`
   - `McpServerService.onDestroy()`:
     a. Updates `ServerStatus.Stopping` via companion-level StateFlow
        - Cancels the action-approval observer and the `PolicyApprovalNotifier` notification, then denies every pending action approval and drops unused grants (`ActionApprovalCoordinator.clearAll()`), waking calls still waiting for the owner
     b. Stops tunnel (with 3s ANR-safe timeout) — tunnel stops BEFORE server
     c. Stops Ktor server gracefully (1s grace + 5s timeout)
        - Calls `ActionApprovalCoordinator.clearAll()` again for any approval a request created while the server was stopping
     d. Cancels coroutine scope
     e. Clears singleton instance
     f. Updates `ServerStatus.Stopped` via companion-level StateFlow

### McpNotificationListenerService Lifecycle

- **Type**: Android `NotificationListenerService` (system-managed)
- **Lifecycle**: Runs as long as enabled in Settings > Notification access
- **Singleton**: Stores `instance` in `@Volatile` companion property
- **Connected**: `onListenerConnected()` sets singleton instance
- **Disconnected**: `onListenerDisconnected()` clears singleton instance
- **Destroyed**: `onDestroy()` clears singleton instance
- **Memory**: `onLowMemory()` and `onTrimMemory()` logged for diagnostics

### Auto-Start on Boot

1. Device boots -> Android delivers `BOOT_COMPLETED` broadcast
2. `BootCompletedReceiver.onReceive()`:
   a. Reads auto-start setting from `SettingsRepository`
   b. If enabled: starts `McpServerService` via `startForegroundService()`
   c. If disabled: does nothing

---

## Threading Model

### Thread Assignments

| Thread/Dispatcher     | Responsibilities                                        |
|-----------------------|---------------------------------------------------------|
| Main Thread           | Compose UI, Activity lifecycle, AccessibilityService    |
|                       | node operations, `onAccessibilityEvent()`               |
| Dispatchers.IO        | DataStore reads/writes, Ktor server startup, network I/O, file operations (SAF) |
| Dispatchers.Default   | Screenshot JPEG encoding, accessibility tree parsing    |
| Ktor Netty threads    | HTTP request handling (NIO event loop)                  |

### Coroutine Scopes

| Component             | Scope                      | Lifecycle                    |
|-----------------------|---------------------------|------------------------------|
| MainViewModel         | `viewModelScope`          | ViewModel lifecycle          |
| McpServerService      | Custom `CoroutineScope`   | Service onCreate to onDestroy|
| McpAccessibilityService| Custom `CoroutineScope`  | Service lifecycle            |

### Thread Safety

- SDK `Server` tool registry: thread-safe (managed by MCP SDK)
- `McpAccessibilityService.instance`: `@Volatile` singleton
- `McpNotificationListenerService.instance`: `@Volatile` singleton
- `McpServer.running`: `AtomicBoolean`
- Accessibility node access: Must be on main thread (Android requirement)
- `ActionApprovalCoordinator`: in-memory state guarded by one `Mutex` (same pattern as `OAuthApprovalCoordinatorImpl`); no critical section suspends
- `ToolPolicyGate` mutation lock: while at least one approval profile is stored (or the profile store is unreadable), every call of a tool class that is not read-only is resolved, evaluated and, when authorized, run under a single `Mutex`, one call at a time; a long mutating call (`type`, about 250 ms per character) queues the others. The `NOTIFY` wait (up to 25 s, a suspended request coroutine) releases the lock; after the owner approves, the call re-checks under the lock before it runs.
- `ApprovalProfileRepository`: in-memory `ProfilesSnapshot`, replaced only after the DataStore write succeeds; writes are serialized by the repository `Mutex`

---

## Data Flow: MCP Request

```mermaid
sequenceDiagram
    participant Client as MCP Client
    participant Ktor as Ktor Netty (IO threads)
    participant Auth as BearerTokenAuth Plugin
    participant Route as McpStreamableHttp (/mcp)
    participant SDK as SDK Server (MCP Kotlin SDK)
    participant Gate as ToolPolicyGate
    participant Tool as Tool Handler
    participant Acc as AccessibilityService (Main Thread)

    Client->>Ktor: HTTP(S) POST /mcp<br/>Authorization: Bearer <token><br/>{"method":"tools/call","params":{"name":"android_tap",...}}
    Ktor->>Auth: Forward request
    Auth->>Auth: Extract Bearer token<br/>Constant-time compare
    alt Invalid token
        Auth-->>Client: 401 Unauthorized
    end
    Auth->>Route: Authenticated request
    Route->>SDK: StreamableHttpServerTransport<br/>(JSON-only, no SSE)
    SDK->>SDK: Route by method ("tools/call")<br/>Extract tool name + arguments<br/>Look up registered tool
    SDK->>Gate: guard(toolName, arguments, execute)<br/>(inside the TOOL_CALL logging wrapper)
    Gate->>Gate: Resolve subject, evaluate profiles<br/>(see Data Flow: Approval Gate)
    alt forbidden, approval needed, denied, expired or too many pending
        Gate-->>SDK: CallToolResult with the approval status JSON
    end
    Gate->>Tool: execute() (RUN or a consumed grant)
    Tool->>Acc: withContext(Dispatchers.Main)<br/>dispatchGesture / performAction / ...
    Acc->>Acc: Perform action on Android UI
    Acc-->>Tool: Success / Failure
    Tool-->>Gate: CallToolResult<br/>(TextContent or ImageContent)
    Gate-->>SDK: CallToolResult
    SDK-->>Ktor: JSON-RPC response via transport
    Ktor-->>Client: HTTP 200 + JSON body
```

---

## Data Flow: Approval Gate

`ToolPolicyGate.guard()` runs for every tool call, between the TOOL_CALL logging wrapper and the tool-call indicator, so the indicator overlay never shows while a call waits for approval. Specification: [PROJECT.md, Approval Profiles](PROJECT.md#approval-profiles-per-app-action-policy). Agent-facing outcomes: [MCP_TOOLS.md, Approval Profiles](MCP_TOOLS.md#approval-profiles).

### Decision Flow

```mermaid
flowchart TB
    Call["tools/call<br/>(inside the TOOL_CALL logging wrapper)"] --> Kind{"Catalog class without resolver?<br/>(not app-scoped, exempt exit)"}
    Kind -->|"yes, profiles never read"| Exec["tool-call indicator + tool handler"]
    Kind -->|"no"| Serial{"At least one profile (or unreadable store)<br/>and a class that is not read-only?"}
    Serial -->|"yes"| Lock["take the mutation Mutex<br/>(held until the call ends, released only for the NOTIFY wait)"]
    Serial -->|"no, no lock"| Load["ApprovalProfileRepository.current()"]
    Lock --> Load
    Load -->|"snapshot"| Resolve["ToolSubjectResolver<br/>target packages, action type, amount text"]
    Load -->|"ProfileStoreUnreadableException"| Unreadable["fail-closed view (ProfilesView):<br/>a profile may exist, every target package is unreadable"]
    Unreadable --> Resolve
    Resolve -->|"Blocked (BlockCause)"| Forbidden["forbidden<br/>isError=true"]
    Resolve -->|"ExemptExit, or Unattributed with no profile"| Exec
    Resolve -->|"Resolved"| Eval["PolicyEngine.evaluate(subject, snapshot)<br/>strictest mode across targets"]
    Eval --> Mode{"Decision mode"}
    Mode -->|"FORBIDDEN"| Forbidden
    Mode -->|"RUN"| Held{"Non-READ call under profiles<br/>and the lock not held?"}
    Mode -->|"ASK_IN_CHAT or NOTIFY"| Grant{"Unused grant for this<br/>fingerprint that satisfies the mode?"}
    Grant -->|"yes, consume it"| Held
    Grant -->|"no, ASK_IN_CHAT"| Ask["create or join pending entry"]
    Grant -->|"no, NOTIFY"| Notify["create or join pending entry<br/>release the lock, notify the owner<br/>wait up to 25 s"]
    Ask -->|"new entry"| NeedsConfirm["needs_human_confirmation"]
    Ask -->|"joined an entry waiting for the owner"| Awaiting["awaiting_approval"]
    Ask -->|"10 entries already pending"| TooMany["too_many_pending<br/>isError=true"]
    Notify -->|"10 entries already pending"| TooMany
    Notify -->|"approved, consume grant"| Held
    Notify -->|"denied"| Denied["denied<br/>isError=true"]
    Notify -->|"expired, or grant already used"| Expired["expired<br/>isError=true"]
    Notify -->|"still pending"| Awaiting
    Held -->|"no"| Exec
    Held -->|"yes"| Recheck["take the mutation Mutex<br/>re-resolve + re-evaluate, no waiting"]
    Recheck -->|"same fingerprint, not stricter"| Exec
    Recheck -->|"changed"| Again["decide the fresh result without waiting<br/>run it or return its outcome"]
```

### ASK_IN_CHAT and NOTIFY

```mermaid
sequenceDiagram
    participant A as MCP Client (agent)
    participant H as Human (conversation)
    participant G as ToolPolicyGate
    participant C as ActionApprovalCoordinator
    participant O as Owner (device)
    participant T as Tool Handler

    Note over A,T: ASK_IN_CHAT
    A->>G: tools/call (type into a profiled app)
    G->>C: createOrJoin(request, nowMs)
    G-->>A: needs_human_confirmation + approval_id
    A->>H: describe the action and ask
    H-->>A: agrees
    A->>C: android_confirm_human_approval(approval_id)<br/>confirmInChat(id, nowMs)
    C-->>A: approved (single-use grant, 2 min)
    A->>G: the exact same tools/call
    G->>C: consumeGrant(fingerprint, ASK_IN_CHAT, nowMs)
    G->>T: execute()
    T-->>A: tool result

    Note over A,T: NOTIFY
    A->>G: tools/call (action button in a profiled app)
    G->>C: createOrJoin(request, nowMs)
    C->>O: heads-up notification via PolicyApprovalNotifier (no action buttons)
    G->>C: awaitDecision(approval_id, 25 s)
    alt owner approves within 25 s
        O->>C: tap the notification, then Approve in PolicyApprovalActivity
        G->>C: consumeGrant(fingerprint, NOTIFY, nowMs)
        G->>G: take the mutation Mutex again<br/>re-resolve + re-evaluate, no waiting
        G->>T: execute()
        T-->>A: tool result
    else still pending after 25 s
        G-->>A: awaiting_approval + approval_id
        A->>C: android_get_approval_status(approval_id, wait_ms)<br/>statusOf(id, nowMs), awaitDecision(id, budget)
        O->>C: tap the notification, then Approve
        C-->>A: approved
        A->>G: the exact same tools/call
        G->>C: consumeGrant(fingerprint, NOTIFY, nowMs)
        G->>T: execute()
        T-->>A: tool result
    else owner denies
        O->>C: tap the notification, then Deny
        G-->>A: denied
    end
```

- The approval tools (`android_get_approval_status`, `android_confirm_human_approval`) also pass through the gate. They are not app-scoped, so the gate runs them directly; the diagram draws them straight to the coordinator.
- The notification has no action buttons: the owner always approves or denies inside `PolicyApprovalActivity`, which the own-package guard keeps out of the agent's reach. The Server screen's pending-approvals card, shown while any request is pending, opens the same activity; it is the path when notifications are off.
- An unreadable profile store never reads as "no profiles": `ProfilesView` hands the resolver the marker `UNREADABLE_STORE_MARKER` (so unattributable calls are blocked) and the engine a snapshot in which every target package is unreadable (non-READ → `FORBIDDEN` `profile_unreadable`, READ → `RUN`). The next call retries the read.

---

## Security Model

### HTTPS (Optional Transport Security)

- When HTTPS is enabled, all traffic encrypted with TLS 1.2+
- Self-signed or custom certificate (when HTTPS is enabled)
- HTTP by default; HTTPS available as optional toggle in settings
- Certificate stored in app-private directory

### Combined Authentication (dual-accept)

- The global Application-level `McpAuthPlugin` authorizes a `/mcp` request when it presents the static bearer token OR a valid issued OAuth access token. Two independent toggles control it: `bearer_token_enabled` (default true) and `oauth_enabled` (default true).
- Auth is required iff at least one toggle is on; with BOTH off the server is OPEN (explicit — the UI shows a warning and a confirm dialog before the last method is disabled). An enabled-but-empty bearer fails CLOSED (401).
- Bearer token validated with constant-time comparison; auto-generated (UUID) once on first launch (existing tokens preserved on upgrade), persisted in DataStore. The enabled flags are decoupled from the value (disabling keeps the value; enabling with an empty value auto-generates one). A one-time migration initializes `bearer_token_enabled` from whether a non-empty token already existed.
- A 401 carries `WWW-Authenticate: Bearer resource_metadata="…/.well-known/oauth-protected-resource/mcp"` ONLY when OAuth is enabled (triggers Claude's discovery).

### OAuth 2.1 Authorization Server (self-contained)

- New components live under `mcp/oauth/`: `OAuthPolicy` (redirect allowlist, resource matching, TTL/cap constants), `Pkce` (S256), `JwtTokenService(Impl)` (HS256 issue/verify, memoized algorithm), `OAuthClientRepository(Impl)` (persisted registry in a dedicated `oauth_clients` DataStore with an in-memory snapshot), `AuthorizationCodeStore(Impl)` (single-use 60s codes), `OAuthApprovalCoordinator(Impl)` (number-match pending approvals), `OAuthMetadata` (RFC 9728/8414 docs), `OAuthRoutes` (the HTTP endpoints), `OAuthAccessValidator` (the `/mcp` token check, shared by server and tests), `LogoUrlPolicy` (SSRF guard for client-logo rendering). `RequestBaseUrl` derives the per-request public base URL (used by OAuth metadata/`aud` and the share-content links).
- The app is both Authorization Server and Resource Server, so tokens are HS256-signed with a device-held secret (no JWKS). Access TTL 24h, refresh TTL 90d, code TTL 60s; both tokens carry `aud` = `<base>/mcp` and `client_id`.

```mermaid
sequenceDiagram
    participant C as Claude.ai
    participant S as MCP Server
    participant U as User (device)
    C->>S: POST /mcp (no token)
    S-->>C: 401 + WWW-Authenticate (resource_metadata)
    C->>S: GET /.well-known/oauth-protected-resource/mcp
    C->>S: GET /.well-known/oauth-authorization-server
    C->>S: POST /register (DCR)
    S-->>C: 201 client_id
    C->>S: GET /authorize (PKCE S256, resource)
    S->>S: createPending (2-digit code)
    S-->>C: consent page (polls /authorize/status)
    U->>S: approve in-app (match code)
    C->>S: GET /authorize/status
    S-->>C: redirect with code
    C->>S: POST /token (code_verifier, resource)
    S-->>C: access + refresh JWT
    C->>S: POST /mcp (Bearer access)
    S->>S: verify sig + aud + client in registry
    S-->>C: 200 (tools)
```

### Network Binding (Exposure Control)

- Default: `127.0.0.1` (localhost only, requires `adb forward`)
- Optional: `0.0.0.0` (all interfaces, with security warning)
- No external firewall; relies on Android's app sandbox, the network-binding choice (loopback by default), and the combined auth. When both auth methods are disabled, the network binding is the only remaining layer — stay on loopback unless you trust the network.

### Approval Profiles (Per-App Action Policy)

- Optional per-app profiles decide whether a tool call that acts on an app runs, needs the human's confirmation in the conversation, needs the owner's approval on the device, or is forbidden. Specification: [PROJECT.md](PROJECT.md#approval-profiles-per-app-action-policy).
- **Own-package guard**: any non-read action on this app's own package (approval screen, settings, its notifications, intents to its components, coordinate taps on its windows) is forbidden, with or without profiles. Without it, an agent could approve its own requests.
- **No agent path to an approval**: the approval notification has no action buttons, `android_confirm_human_approval` cannot approve a `NOTIFY` request, and a chat grant never satisfies a `NOTIFY` rule.
- Fail-closed:
  - an unclassified tool stops server start;
  - an unreadable profile forbids every non-read action on its app, and an unreadable store forbids every non-read action on every app (reads that resolve to an app still run);
  - while a profile exists, a call that cannot be attributed to an app is forbidden;
  - an unidentifiable tap target in a profiled app counts as an action button.

---

## Configuration Flow

```mermaid
flowchart TB
    User["User (UI)"]
    User --> VM["MainViewModel"]
    VM -->|"updatePort(), updateBindingAddress(), etc."| Repo["SettingsRepository (interface)"]
    Repo --> Impl["SettingsRepositoryImpl\n(DataStore&lt;Preferences&gt;)"]
    Impl -->|"Persists to DataStore file\nEmits via serverConfig: Flow&lt;ServerConfig&gt;"| DS[(DataStore)]
    DS -->|"config = serverConfig.first()"| SvcRead["McpServerService\n(reads on start)"]
    SvcRead --> McpServer["McpServer\n(uses config for Ktor setup)"]
```

Settings are read at server start time. Changing settings while the server is
running requires a restart (UI disables config editing when server is running).

Approval profiles are the exception. They live in their own DataStore (`approval_profiles`) behind
`ApprovalProfileRepository`, can be edited while the server runs, and apply to the next tool call.

---

## Permission Model

| Permission               | Type          | How Granted                        | Required For              |
|--------------------------|---------------|------------------------------------|---------------------------|
| INTERNET                 | Normal        | Auto-granted (manifest)            | HTTP server               |
| FOREGROUND_SERVICE       | Normal        | Auto-granted (manifest)            | Foreground services       |
| RECEIVE_BOOT_COMPLETED   | Normal        | Auto-granted (manifest)            | Auto-start on boot        |
| QUERY_ALL_PACKAGES       | Normal        | Auto-granted (manifest)            | Listing installed apps    |
| KILL_BACKGROUND_PROCESSES| Normal        | Auto-granted (manifest)            | Closing background apps   |
| POST_NOTIFICATIONS       | Runtime (13+) | System dialog                      | Foreground and action-approval notifications (pending approvals also show as a card on the Server screen, so they still reach the owner without it) |
| Accessibility Service    | Special       | User enables in Settings           | UI introspection/actions  |
| AccessibilityService takeScreenshot | Special | User enables in Settings | Screenshots (Android 11+) |
| CAMERA                   | Runtime       | System dialog                      | Camera photo/video tools  |
| RECORD_AUDIO             | Runtime       | System dialog                      | Video recording with audio|
| ACCESS_FINE_LOCATION     | Runtime       | System dialog                      | Device location tool      |
| ACCESS_COARSE_LOCATION   | Runtime       | Declared (implied by FINE)         | Device location fallback  |
| SAF tree URI permissions | Special       | User grants via system file picker | File operations per storage location |
| Notification Listener    | Special       | User enables in Settings > Notification access | Reading/interacting with notifications |

---

## Multi-Window Accessibility Architecture

The application uses Android's multi-window accessibility API (`AccessibilityService.getWindows()`) to enumerate and introspect **all** interactive windows on screen, not just the foreground app. This enables the MCP client to see and interact with system dialogs, permission popups, IME keyboards, and accessibility overlays.

### Window Discovery Flow

```mermaid
flowchart TB
    Service["McpAccessibilityService"]
    Service -->|"getWindows()"| Windows["List<AccessibilityWindowInfo>"]
    Windows --> ForEach["For each window"]
    ForEach -->|"window.root"| Root["AccessibilityNodeInfo (root)"]
    Root -->|"parseTree(root, 'root_w{windowId}')"| Tree["AccessibilityNodeData tree"]
    Tree --> WD["WindowData(windowId, type, pkg, title, activity, layer, focused, tree)"]
    WD --> Result["MultiWindowResult(windows, degraded=false)"]

    Service -->|"getWindows() fails/empty"| Fallback["rootInActiveWindow"]
    Fallback -->|"parseTree(root, 'root_w{rootNode.windowId}')"| FallbackTree["AccessibilityNodeData tree"]
    FallbackTree --> FallbackWD["WindowData(windowId=rootNode.windowId, ...)"]
    FallbackWD --> DegradedResult["MultiWindowResult(windows, degraded=true)"]
```

### Key Data Types

| Type | Description |
|------|-------------|
| `WindowData` | Window metadata (ID from `AccessibilityWindowInfo.getId()`, type, package, title, activity, layer, focused) plus the parsed `AccessibilityNodeData` tree |
| `MultiWindowResult` | List of `WindowData` plus a `degraded` flag indicating fallback to single-window mode |

### Node ID Uniqueness

Node IDs are deterministic hashes generated from the node's properties and parent chain. The `rootParentId` passed to `parseTree()` (e.g., `"root_w42"`) is the root of the hash chain, so identical nodes in different windows produce different IDs. The window ID is not appended as a visible suffix — it influences the hash internally. Example: `node_a1b2` (not `node_a1b2_w42`).

### Degraded Mode

When `getWindows()` returns empty or fails, the system falls back to `rootInActiveWindow` (single-window mode). In this mode:
- A single `WindowData` is created with `windowId` set to `rootNode.windowId` (the system-assigned window ID of the active window)
- The window type is detected via `rootNode.window?.type` when available, defaulting to `APPLICATION` otherwise
- The `MultiWindowResult.degraded` flag is set to `true`
- The TSV output includes a `note:DEGRADED` line to inform the MCP client
- Action execution falls back to `getRootNode()` for node resolution

### Cross-Window Action Execution

When executing a node-based action (click, long-click, scroll-to-node):
1. The caller provides the `List<WindowData>` from the multi-window snapshot, each containing a `windowId` (from `AccessibilityWindowInfo.getId()`)
2. `performNodeAction()` calls `getAccessibilityWindows()` to get the live window list
3. For each `WindowData`, find the matching live `AccessibilityWindowInfo` by `getId()`
4. Get the window's root `AccessibilityNodeInfo`
5. Walk the live tree in parallel with the parsed tree to find the target node by matching the deterministic node ID
6. Perform the accessibility action on the live node

### Required Configuration

The accessibility service XML config (`accessibility_service_config.xml`) must include `FLAG_RETRIEVE_INTERACTIVE_WINDOWS` in the `accessibilityFlags` attribute for `getWindows()` to return results.

---

**End of ARCHITECTURE.md**
