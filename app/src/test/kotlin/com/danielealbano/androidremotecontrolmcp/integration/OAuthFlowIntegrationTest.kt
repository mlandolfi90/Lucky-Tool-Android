package com.danielealbano.androidremotecontrolmcp.integration

import io.ktor.client.request.forms.FormDataContent
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.Parameters
import io.ktor.http.Url
import io.ktor.http.contentType
import io.mockk.coEvery
import io.mockk.coVerify
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("OAuth Flow Integration Tests")
class OAuthFlowIntegrationTest {
    @BeforeEach
    fun setUp() = McpIntegrationTestHelper.mockAndroidLog()

    @AfterEach
    fun tearDown() = McpIntegrationTestHelper.unmockAndroidLog()

    // ── discovery / metadata ────────────────────────────────────────────────

    @Test
    @DisplayName("unauthenticated /mcp returns 401 with WWW-Authenticate when oauth on")
    fun unauthenticatedReturns401WithHeader() =
        runTest {
            McpIntegrationTestHelper.withOAuthTestApplication { _ ->
                val resp = client.post("/mcp") { mcpInitializeBody() }
                assertEquals(HttpStatusCode.Unauthorized, resp.status)
                val header = resp.headers["WWW-Authenticate"]
                assertNotNull(header)
                assertTrue(header!!.contains("/.well-known/oauth-protected-resource/mcp"))
            }
        }

    @Test
    @DisplayName("401 has no WWW-Authenticate when only bearer enabled")
    fun bearerOnly401NoHeader() =
        runTest {
            McpIntegrationTestHelper.withRawTestApplication { _ ->
                val resp = client.post("/mcp") { mcpInitializeBody() }
                assertEquals(HttpStatusCode.Unauthorized, resp.status)
                assertNull(resp.headers["WWW-Authenticate"])
            }
        }

    @Test
    @DisplayName("serves PRM at path-suffixed and bare paths")
    fun servesPrmBothPaths() =
        runTest {
            McpIntegrationTestHelper.withOAuthTestApplication { _ ->
                val suffixed = client.get("/.well-known/oauth-protected-resource/mcp")
                val bare = client.get("/.well-known/oauth-protected-resource")
                assertEquals(HttpStatusCode.OK, suffixed.status)
                assertEquals(HttpStatusCode.OK, bare.status)
                val resource =
                    Json
                        .parseToJsonElement(suffixed.bodyAsText())
                        .jsonObject["resource"]!!
                        .jsonPrimitive.content
                assertTrue(resource.endsWith("/mcp"))
            }
        }

    @Test
    @DisplayName("serves AS metadata at bare, openid-configuration, and path-suffixed")
    fun servesAsMetadata() =
        runTest {
            McpIntegrationTestHelper.withOAuthTestApplication { _ ->
                for (
                path in
                listOf(
                    "/.well-known/oauth-authorization-server",
                    "/.well-known/openid-configuration",
                    "/.well-known/oauth-authorization-server/mcp",
                )
                ) {
                    val resp = client.get(path)
                    assertEquals(HttpStatusCode.OK, resp.status, "expected 200 for $path")
                    val obj = Json.parseToJsonElement(resp.bodyAsText()).jsonObject
                    assertEquals(
                        listOf("S256"),
                        obj["code_challenge_methods_supported"]!!.jsonObjectArray(),
                    )
                }
            }
        }

    @Test
    @DisplayName("metadata reflects X-Forwarded-Host and -Proto")
    fun metadataReflectsForwarded() =
        runTest {
            McpIntegrationTestHelper.withOAuthTestApplication(publicUrlOverride = "") { _ ->
                val resp =
                    client.get("/.well-known/oauth-protected-resource/mcp") {
                        header("X-Forwarded-Host", "tunnel.example")
                        header("X-Forwarded-Proto", "https")
                    }
                val resource =
                    Json
                        .parseToJsonElement(resp.bodyAsText())
                        .jsonObject["resource"]!!
                        .jsonPrimitive.content
                assertEquals("https://tunnel.example/mcp", resource)
            }
        }

    @Test
    @DisplayName("metadata honors publicUrlOverride")
    fun metadataHonorsOverride() =
        runTest {
            McpIntegrationTestHelper.withOAuthTestApplication(publicUrlOverride = "https://pinned.host") { _ ->
                val resp =
                    client.get("/.well-known/oauth-protected-resource/mcp") {
                        header("X-Forwarded-Host", "tunnel.example")
                        header("X-Forwarded-Proto", "https")
                    }
                val resource =
                    Json
                        .parseToJsonElement(resp.bodyAsText())
                        .jsonObject["resource"]!!
                        .jsonPrimitive.content
                assertEquals("https://pinned.host/mcp", resource)
            }
        }

    // ── full dance + token grants ───────────────────────────────────────────

    @Test
    @DisplayName("full DCR to authorize to approve to token to /mcp succeeds")
    fun fullDanceSucceeds() =
        runTest {
            val deps = McpIntegrationTestHelper.createMockDependencies()
            coEvery { deps.actionExecutor.tap(any(), any()) } returns Result.success(Unit)
            McpIntegrationTestHelper.withOAuthTestApplication(deps = deps, publicUrlOverride = OVERRIDE) { ctx ->
                val clientId = register(client)
                val tokens = danceToTokens(ctx, clientId, includeResource = true)
                val access = tokens.access

                val mcpClient = connectMcp(access)
                try {
                    val result = mcpClient.callTool(name = "android_tap", arguments = mapOf("x" to 1, "y" to 1))
                    assertNotEquals(true, result.isError)
                } finally {
                    mcpClient.close()
                }
            }
        }

    @Test
    @DisplayName("register rejects disallowed redirect_uri")
    fun registerRejectsDisallowedRedirect() =
        runTest {
            McpIntegrationTestHelper.withOAuthTestApplication { _ ->
                val resp =
                    client.post("/register") {
                        contentType(ContentType.Application.Json)
                        setBody("""{"redirect_uris":["https://evil.example/cb"],"token_endpoint_auth_method":"none"}""")
                    }
                assertEquals(HttpStatusCode.BadRequest, resp.status)
                assertTrue(resp.bodyAsText().contains("invalid_redirect_uri"))
            }
        }

    @Test
    @DisplayName("register accepts ChatGPT connector redirect_uri")
    fun registerAcceptsChatGptRedirect() =
        runTest {
            McpIntegrationTestHelper.withOAuthTestApplication { _ ->
                val resp =
                    client.post("/register") {
                        contentType(ContentType.Application.Json)
                        setBody(
                            """{"redirect_uris":["https://chatgpt.com/connector/oauth/abc123"],""" +
                                """"token_endpoint_auth_method":"none"}""",
                        )
                    }
                assertEquals(HttpStatusCode.Created, resp.status)
                assertTrue(resp.bodyAsText().contains("https://chatgpt.com/connector/oauth/abc123"))
            }
        }

    @Test
    @DisplayName("authorize rejects non-S256 challenge method")
    fun authorizeRejectsNonS256() =
        runTest {
            McpIntegrationTestHelper.withOAuthTestApplication(publicUrlOverride = OVERRIDE) { _ ->
                val clientId = register(client)
                val noRedirect = createNoRedirectClient()
                val resp = authorize(noRedirect, clientId, AuthorizeOptions(challengeMethod = "plain"))
                assertEquals(HttpStatusCode.Found, resp.status)
                assertTrue(resp.headers["Location"]!!.contains("error="))
            }
        }

    @Test
    @DisplayName("token rejects wrong PKCE verifier")
    fun tokenRejectsWrongVerifier() =
        runTest {
            McpIntegrationTestHelper.withOAuthTestApplication(publicUrlOverride = OVERRIDE) { ctx ->
                val clientId = register(client)
                val code = danceToCode(ctx, clientId, includeResource = true)
                val resp = tokenRequest(client, clientId, code, verifier = "wrong-verifier", includeResource = true)
                assertEquals(HttpStatusCode.BadRequest, resp.status)
                assertTrue(resp.bodyAsText().contains("invalid_grant"))
            }
        }

    @Test
    @DisplayName("token rejects client_id mismatch against code")
    fun tokenRejectsClientMismatch() =
        runTest {
            McpIntegrationTestHelper.withOAuthTestApplication(publicUrlOverride = OVERRIDE) { ctx ->
                val clientId = register(client)
                val code = danceToCode(ctx, clientId, includeResource = true)
                val resp = tokenRequest(client, "arc-other", code, verifier = VERIFIER, includeResource = true)
                assertEquals(HttpStatusCode.BadRequest, resp.status)
                assertTrue(resp.bodyAsText().contains("invalid_grant"))
            }
        }

    @Test
    @DisplayName("token rejects missing client_id")
    fun tokenRejectsMissingClientId() =
        runTest {
            McpIntegrationTestHelper.withOAuthTestApplication(publicUrlOverride = OVERRIDE) { ctx ->
                val clientId = register(client)
                val code = danceToCode(ctx, clientId, includeResource = true)
                val resp =
                    client.post("/token") {
                        setBody(
                            FormDataContent(
                                Parameters.build {
                                    append("grant_type", "authorization_code")
                                    append("code", code)
                                    append("redirect_uri", REDIRECT)
                                    append("code_verifier", VERIFIER)
                                },
                            ),
                        )
                    }
                assertEquals(HttpStatusCode.BadRequest, resp.status)
                assertTrue(resp.bodyAsText().contains("invalid_request"))
            }
        }

    @Test
    @DisplayName("replayed auth code rejected")
    fun replayedCodeRejected() =
        runTest {
            McpIntegrationTestHelper.withOAuthTestApplication(publicUrlOverride = OVERRIDE) { ctx ->
                val clientId = register(client)
                val code = danceToCode(ctx, clientId, includeResource = true)
                val first = tokenRequest(client, clientId, code, verifier = VERIFIER, includeResource = true)
                assertEquals(HttpStatusCode.OK, first.status)
                val second = tokenRequest(client, clientId, code, verifier = VERIFIER, includeResource = true)
                assertEquals(HttpStatusCode.BadRequest, second.status)
                assertTrue(second.bodyAsText().contains("invalid_grant"))
            }
        }

    @Test
    @DisplayName("refresh_token rotates and old jti rejected")
    fun refreshRotates() =
        runTest {
            McpIntegrationTestHelper.withOAuthTestApplication(publicUrlOverride = OVERRIDE) { ctx ->
                val clientId = register(client)
                val tokens = danceToTokens(ctx, clientId, includeResource = true)
                val refreshed = refreshRequest(client, clientId, tokens.refresh)
                assertEquals(HttpStatusCode.OK, refreshed.status)
                val reused = refreshRequest(client, clientId, tokens.refresh)
                assertEquals(HttpStatusCode.BadRequest, reused.status)
                assertTrue(reused.bodyAsText().contains("invalid_grant"))
                // The client is NOT revoked: it still exists in the registry.
                assertNotNull(ctx.clientRepository.getClient(clientId))
            }
        }

    @Test
    @DisplayName("refresh rejects invalid refresh token")
    fun refreshRejectsInvalidRefreshToken() =
        runTest {
            McpIntegrationTestHelper.withOAuthTestApplication(publicUrlOverride = OVERRIDE) { _ ->
                val clientId = register(client)
                val resp = refreshRequest(client, clientId, "not-a-valid-refresh-token")
                assertEquals(HttpStatusCode.BadRequest, resp.status)
                assertTrue(resp.bodyAsText().contains("invalid_grant"))
            }
        }

    @Test
    @DisplayName("refresh rejects client_id mismatch")
    fun refreshRejectsClientIdMismatch() =
        runTest {
            McpIntegrationTestHelper.withOAuthTestApplication(publicUrlOverride = OVERRIDE) { ctx ->
                val clientId = register(client)
                val tokens = danceToTokens(ctx, clientId, includeResource = true)
                val mismatched = refreshRequest(client, "arc-other", tokens.refresh)
                assertEquals(HttpStatusCode.BadRequest, mismatched.status)
                assertTrue(mismatched.bodyAsText().contains("invalid_grant"))
                // The rejection did not rotate the jti: the same refresh token still works with the right client_id.
                val accepted = refreshRequest(client, clientId, tokens.refresh)
                assertEquals(HttpStatusCode.OK, accepted.status)
            }
        }

    @Test
    @DisplayName("refresh rejects revoked client")
    fun refreshRejectsRevokedClient() =
        runTest {
            McpIntegrationTestHelper.withOAuthTestApplication(publicUrlOverride = OVERRIDE) { ctx ->
                val clientId = register(client)
                val tokens = danceToTokens(ctx, clientId, includeResource = true)
                ctx.clientRepository.revoke(clientId)
                val resp = refreshRequest(client, clientId, tokens.refresh)
                assertEquals(HttpStatusCode.BadRequest, resp.status)
                assertTrue(resp.bodyAsText().contains("invalid_grant"))
            }
        }

    @Test
    @DisplayName("refresh accepts omitted client_id")
    fun refreshAcceptsOmittedClientId() =
        runTest {
            McpIntegrationTestHelper.withOAuthTestApplication(publicUrlOverride = OVERRIDE) { ctx ->
                val clientId = register(client)
                val tokens = danceToTokens(ctx, clientId, includeResource = true)
                val resp = refreshRequest(client, null, tokens.refresh)
                assertEquals(HttpStatusCode.OK, resp.status)
                val obj = Json.parseToJsonElement(resp.bodyAsText()).jsonObject
                assertNotNull(obj["access_token"])
                assertNotNull(obj["refresh_token"])
                assertNotEquals(tokens.refresh, obj["refresh_token"]!!.jsonPrimitive.content)
            }
        }

    @Test
    @DisplayName("revoked client token rejected on /mcp")
    fun revokedClientRejected() =
        runTest {
            McpIntegrationTestHelper.withOAuthTestApplication(publicUrlOverride = OVERRIDE) { ctx ->
                val clientId = register(client)
                val tokens = danceToTokens(ctx, clientId, includeResource = true)
                ctx.clientRepository.revoke(clientId)
                val resp =
                    client.post("/mcp") {
                        header("Authorization", "Bearer ${tokens.access}")
                        mcpInitializeBody()
                    }
                assertEquals(HttpStatusCode.Unauthorized, resp.status)
            }
        }

    @Test
    @DisplayName("wrong-aud access token rejected on /mcp")
    fun wrongAudRejected() =
        runTest {
            McpIntegrationTestHelper.withOAuthTestApplication(publicUrlOverride = OVERRIDE) { ctx ->
                val registered = ctx.clientRepository.register("Claude", listOf(REDIRECT), "web", null, 0L)
                val token = ctx.tokenService.issueAccessToken(registered.clientId, "https://other.host/mcp")
                val resp =
                    client.post("/mcp") {
                        header("Authorization", "Bearer $token")
                        mcpInitializeBody()
                    }
                assertEquals(HttpStatusCode.Unauthorized, resp.status)
            }
        }

    @Test
    @DisplayName("static bearer token still accepted (dual-accept)")
    fun staticBearerAccepted() =
        runTest {
            McpIntegrationTestHelper.withOAuthTestApplication(
                bearerTokenEnabled = true,
                bearerToken = "static-tok",
                publicUrlOverride = OVERRIDE,
            ) { _ ->
                val resp =
                    client.post("/mcp") {
                        header("Authorization", "Bearer static-tok")
                        mcpInitializeBody()
                    }
                assertNotEquals(HttpStatusCode.Unauthorized, resp.status)
            }
        }

    @Test
    @DisplayName("open when both disabled")
    fun openWhenBothDisabled() =
        runTest {
            McpIntegrationTestHelper.withRawTestApplication(bearerTokenEnabled = false) { _ ->
                val resp = client.post("/mcp") { mcpInitializeBody() }
                assertNotEquals(HttpStatusCode.Unauthorized, resp.status)
            }
        }

    @Test
    @DisplayName("authorize rejects present-but-mismatched resource")
    fun authorizeRejectsMismatchedResource() =
        runTest {
            McpIntegrationTestHelper.withOAuthTestApplication(publicUrlOverride = OVERRIDE) { _ ->
                val clientId = register(client)
                val noRedirect = createNoRedirectClient()
                val resp =
                    authorize(noRedirect, clientId, AuthorizeOptions(resourceOverride = "https://wrong.host/mcp"))
                assertEquals(HttpStatusCode.Found, resp.status)
                assertTrue(resp.headers["Location"]!!.contains("error="))
            }
        }

    @Test
    @DisplayName("authorize omits resource defaults to canonical")
    fun authorizeOmitsResource() =
        runTest {
            val deps = McpIntegrationTestHelper.createMockDependencies()
            coEvery { deps.actionExecutor.tap(any(), any()) } returns Result.success(Unit)
            McpIntegrationTestHelper.withOAuthTestApplication(deps = deps, publicUrlOverride = OVERRIDE) { ctx ->
                val clientId = register(client)
                val tokens = danceToTokens(ctx, clientId, includeResource = false)
                val mcpClient = connectMcp(tokens.access)
                try {
                    val result = mcpClient.callTool(name = "android_tap", arguments = mapOf("x" to 1, "y" to 1))
                    assertNotEquals(true, result.isError)
                } finally {
                    mcpClient.close()
                }
            }
        }

    @Test
    @DisplayName("token rejects present-but-mismatched resource")
    fun tokenRejectsMismatchedResource() =
        runTest {
            McpIntegrationTestHelper.withOAuthTestApplication(publicUrlOverride = OVERRIDE) { ctx ->
                val clientId = register(client)
                val code = danceToCode(ctx, clientId, includeResource = true)
                val resp =
                    client.post("/token") {
                        setBody(
                            FormDataContent(
                                Parameters.build {
                                    append("grant_type", "authorization_code")
                                    append("code", code)
                                    append("redirect_uri", REDIRECT)
                                    append("client_id", clientId)
                                    append("code_verifier", VERIFIER)
                                    append("resource", "https://wrong.host/mcp")
                                },
                            ),
                        )
                    }
                assertEquals(HttpStatusCode.BadRequest, resp.status)
                assertTrue(resp.bodyAsText().contains("invalid_grant"))
            }
        }

    @Test
    @DisplayName("authorize with disallowed redirect_uri returns 400 HTML no Location")
    fun authorizeDisallowedRedirect400() =
        runTest {
            McpIntegrationTestHelper.withOAuthTestApplication(publicUrlOverride = OVERRIDE) { _ ->
                // Register with a localhost redirect, then request authorize for the Claude callback (not registered).
                val clientId = register(client, redirectUri = "http://localhost/cb")
                val noRedirect = createNoRedirectClient()
                val resp = authorize(noRedirect, clientId, AuthorizeOptions(redirectUri = REDIRECT))
                assertEquals(HttpStatusCode.BadRequest, resp.status)
                assertNull(resp.headers["Location"])
                assertTrue(resp.headers["Content-Type"]?.contains("html") == true)
            }
        }

    @Test
    @DisplayName("authorize with unknown or missing client_id returns 400 HTML without Location")
    fun authorizeUnknownOrMissingClientId400() =
        runTest {
            McpIntegrationTestHelper.withOAuthTestApplication(publicUrlOverride = OVERRIDE) { _ ->
                val noRedirect = createNoRedirectClient()
                val unknown = authorize(noRedirect, "arc-unknown")
                val missing =
                    noRedirect.get("/authorize") {
                        url {
                            parameters.append("response_type", "code")
                            parameters.append("redirect_uri", REDIRECT)
                            parameters.append("code_challenge", CHALLENGE)
                            parameters.append("code_challenge_method", "S256")
                            parameters.append("state", "xyz")
                            parameters.append("scope", "mcp")
                            parameters.append("resource", CANONICAL)
                        }
                    }
                for (resp in listOf(unknown, missing)) {
                    assertEquals(HttpStatusCode.BadRequest, resp.status)
                    assertNull(resp.headers["Location"])
                    assertTrue(resp.headers["Content-Type"]?.contains("html") == true)
                }
            }
        }

    @Test
    @DisplayName("last-used updated once then throttled across two /mcp calls")
    fun lastUsedDebounced() =
        runTest {
            McpIntegrationTestHelper.withOAuthTestApplication(publicUrlOverride = OVERRIDE) { ctx ->
                // Mint a token directly (no /token grant) so the only touches come from the /mcp path.
                val registered = ctx.clientRepository.register("Claude", listOf(REDIRECT), "web", null, 0L)
                val token = ctx.tokenService.issueAccessToken(registered.clientId, CANONICAL)
                repeat(2) {
                    client.post("/mcp") {
                        header("Authorization", "Bearer $token")
                        mcpInitializeBody()
                    }
                }
                coVerify(exactly = 1) { ctx.clientRepository.touchLastUsed(registered.clientId, any()) }
            }
        }

    @Test
    @DisplayName("authorize redirect percent-encodes state")
    fun authorizeEncodesState() =
        runTest {
            McpIntegrationTestHelper.withOAuthTestApplication(publicUrlOverride = OVERRIDE) { ctx ->
                val clientId = register(client)
                val rawState = "a&b=c d"
                authorize(client, clientId, AuthorizeOptions(state = rawState))
                val approval =
                    ctx.approvalCoordinator
                        .observePending()
                        .value
                        .single()
                ctx.approvalCoordinator.approve(approval.id, System.currentTimeMillis())
                val status = client.get("/authorize/status?id=${approval.id}")
                val redirect =
                    Json
                        .parseToJsonElement(status.bodyAsText())
                        .jsonObject["redirect"]!!
                        .jsonPrimitive.content
                assertEquals(rawState, Url(redirect).parameters["state"])
            }
        }
}
