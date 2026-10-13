package com.danielealbano.androidremotecontrolmcp.integration

import io.ktor.client.HttpClient
import io.ktor.client.request.forms.FormDataContent
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.Parameters
import io.ktor.http.Url
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.ApplicationTestBuilder
import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.client.StreamableHttpClientTransport
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals

// Shared helpers and fixtures of OAuthFlowIntegrationTest.

internal const val REDIRECT = "https://claude.ai/api/mcp/auth_callback"
internal const val OVERRIDE = "https://test.host"
internal const val CANONICAL = "https://test.host/mcp"

// RFC 7636 Appendix B PKCE test vector.
internal const val VERIFIER = "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"
internal const val CHALLENGE = "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM"

internal data class Tokens(
    val access: String,
    val refresh: String,
)

internal suspend fun register(
    client: HttpClient,
    redirectUri: String = REDIRECT,
): String {
    val resp =
        client.post("/register") {
            contentType(ContentType.Application.Json)
            setBody(
                """{"redirect_uris":["$redirectUri"],"token_endpoint_auth_method":"none",""" +
                    """"grant_types":["authorization_code","refresh_token"],"response_types":["code"],""" +
                    """"scope":"mcp","client_name":"Claude","application_type":"web"}""",
            )
        }
    return Json
        .parseToJsonElement(resp.bodyAsText())
        .jsonObject["client_id"]!!
        .jsonPrimitive.content
}

internal data class AuthorizeOptions(
    val redirectUri: String = REDIRECT,
    val challengeMethod: String = "S256",
    val resourceOverride: String? = null,
    val includeResource: Boolean = true,
    val state: String = "xyz",
)

internal suspend fun authorize(
    client: HttpClient,
    clientId: String,
    options: AuthorizeOptions = AuthorizeOptions(),
): HttpResponse =
    client.get("/authorize") {
        url {
            parameters.append("response_type", "code")
            parameters.append("client_id", clientId)
            parameters.append("redirect_uri", options.redirectUri)
            parameters.append("code_challenge", CHALLENGE)
            parameters.append("code_challenge_method", options.challengeMethod)
            parameters.append("state", options.state)
            parameters.append("scope", "mcp")
            if (options.resourceOverride != null) {
                parameters.append("resource", options.resourceOverride)
            } else if (options.includeResource) {
                parameters.append("resource", CANONICAL)
            }
        }
    }

internal suspend fun danceToCode(
    ctx: McpIntegrationTestHelper.OAuthTestContext,
    clientId: String,
    includeResource: Boolean,
): String {
    authorize(ctx.httpClient, clientId, AuthorizeOptions(includeResource = includeResource))
    val approval =
        ctx.approvalCoordinator
            .observePending()
            .value
            .single()
    ctx.approvalCoordinator.approve(approval.id, System.currentTimeMillis())
    val status = ctx.httpClient.get("/authorize/status?id=${approval.id}")
    val redirect =
        Json
            .parseToJsonElement(status.bodyAsText())
            .jsonObject["redirect"]!!
            .jsonPrimitive.content
    return Url(redirect).parameters["code"]!!
}

internal suspend fun danceToTokens(
    ctx: McpIntegrationTestHelper.OAuthTestContext,
    clientId: String,
    includeResource: Boolean,
): Tokens {
    val code = danceToCode(ctx, clientId, includeResource)
    val resp = tokenRequest(ctx.httpClient, clientId, code, verifier = VERIFIER, includeResource = includeResource)
    assertEquals(HttpStatusCode.OK, resp.status)
    val obj = Json.parseToJsonElement(resp.bodyAsText()).jsonObject
    return Tokens(obj["access_token"]!!.jsonPrimitive.content, obj["refresh_token"]!!.jsonPrimitive.content)
}

internal suspend fun tokenRequest(
    client: HttpClient,
    clientId: String,
    code: String,
    verifier: String,
    includeResource: Boolean,
): HttpResponse =
    client.post("/token") {
        setBody(
            FormDataContent(
                Parameters.build {
                    append("grant_type", "authorization_code")
                    append("code", code)
                    append("redirect_uri", REDIRECT)
                    append("client_id", clientId)
                    append("code_verifier", verifier)
                    if (includeResource) append("resource", CANONICAL)
                },
            ),
        )
    }

internal suspend fun refreshRequest(
    client: HttpClient,
    clientId: String?,
    refreshToken: String,
): HttpResponse =
    client.post("/token") {
        setBody(
            FormDataContent(
                Parameters.build {
                    append("grant_type", "refresh_token")
                    append("refresh_token", refreshToken)
                    if (clientId != null) append("client_id", clientId)
                },
            ),
        )
    }

internal suspend fun ApplicationTestBuilder.connectMcp(accessToken: String): Client {
    val httpClient =
        createClient {
            install(io.ktor.client.plugins.contentnegotiation.ContentNegotiation) {
                json(io.modelcontextprotocol.kotlin.sdk.types.McpJson)
            }
            install(io.ktor.client.plugins.sse.SSE)
        }
    val transport =
        StreamableHttpClientTransport(
            client = httpClient,
            url = "/mcp",
            requestBuilder = { header("Authorization", "Bearer $accessToken") },
        )
    val mcpClient = Client(clientInfo = Implementation(name = "oauth-test-client", version = "1.0.0"))
    mcpClient.connect(transport)
    return mcpClient
}

internal fun ApplicationTestBuilder.createNoRedirectClient(): HttpClient =
    createClient {
        followRedirects = false
        install(io.ktor.client.plugins.contentnegotiation.ContentNegotiation) {
            json(io.modelcontextprotocol.kotlin.sdk.types.McpJson)
        }
    }

internal fun io.ktor.client.request.HttpRequestBuilder.mcpInitializeBody() {
    header("Accept", "application/json")
    contentType(ContentType.Application.Json)
    setBody(
        """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18",""" +
            """"capabilities":{},"clientInfo":{"name":"t","version":"1"}}}""",
    )
}

internal fun kotlinx.serialization.json.JsonElement.jsonObjectArray(): List<String> =
    (this as kotlinx.serialization.json.JsonArray).map { it.jsonPrimitive.content }
