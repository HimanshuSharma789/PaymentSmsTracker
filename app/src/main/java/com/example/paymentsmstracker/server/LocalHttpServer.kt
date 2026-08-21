package com.example.paymentsmstracker.server

import android.content.Context
import android.util.Log
import com.example.paymentsmstracker.config.ConfigStore
import com.example.paymentsmstracker.data.AppDatabase
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.header
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondText
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.routing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.ServerSocket

class LocalHttpServer(private val appContext: Context) {

    private var engine: EmbeddedServer<*, *>? = null
    private val api = LanApi(
        db = AppDatabase.getDatabase(appContext),
        configStore = ConfigStore(appContext),
        splitwiseStore = com.example.paymentsmstracker.splitwise.SplitwiseAuthStore(appContext)
    )

    fun start(preferredPort: Int = 0): Int {
        stop()
        val port = if (preferredPort > 0) preferredPort else findFreePort()
        val server = embeddedServer(CIO, port = port, host = "0.0.0.0") {
            install(StatusPages) {
                exception<Throwable> { call, cause ->
                    Log.e(TAG, "Request failed", cause)
                    call.respondJson(
                        HttpStatusCode.InternalServerError,
                        JSONObject().put("error", cause.message ?: "Server error")
                    )
                }
            }
            routing {
                get("/") {
                    call.response.headers.append(HttpHeaders.CacheControl, "no-store")
                    call.serveAsset("lan_web/index.html", ContentType.Text.Html)
                }
                get("/index.html") {
                    call.serveAsset("lan_web/index.html", ContentType.Text.Html)
                }
                get("/app.js") {
                    call.serveAsset("lan_web/app.js", ContentType.Application.JavaScript)
                }
                get("/style.css") {
                    call.serveAsset("lan_web/style.css", ContentType.Text.CSS)
                }
                get("/sheet") {
                    call.serveAsset("lan_web/sheet.html", ContentType.Text.Html)
                }
                get("/sheet.html") {
                    call.serveAsset("lan_web/sheet.html", ContentType.Text.Html)
                }
                get("/sheet.js") {
                    call.serveAsset("lan_web/sheet.js", ContentType.Application.JavaScript)
                }

                get("/api/status") {
                    call.respondJson(HttpStatusCode.OK, api.statusJson())
                }
                post("/api/login") {
                    val body = runCatching { JSONObject(call.receiveText()) }.getOrNull()
                    val code = body?.optString("code").orEmpty()
                    if (!LanServerState.isAccessCodeValid(code)) {
                        call.respondJson(
                            HttpStatusCode.Unauthorized,
                            JSONObject().put("error", "Invalid access code")
                        )
                        return@post
                    }
                    val token = LanServerState.createSession()
                    call.respondJson(
                        HttpStatusCode.OK,
                        JSONObject().put("token", token).put("status", api.statusJson())
                    )
                }
                get("/api/meta") {
                    if (!requireAuth(call)) return@get
                    call.respondJson(HttpStatusCode.OK, api.metaJson())
                }
                get("/api/transactions") {
                    if (!requireAuth(call)) return@get
                    val status = call.request.queryParameters["status"]
                    val rows = withContext(Dispatchers.IO) { api.listTransactions(status) }
                    call.respondJson(HttpStatusCode.OK, JSONObject().put("items", rows))
                }
                get("/api/transactions/{id}") {
                    if (!requireAuth(call)) return@get
                    val id = call.parameters["id"]?.toLongOrNull()
                    if (id == null) {
                        call.respondJson(
                            HttpStatusCode.BadRequest,
                            JSONObject().put("error", "Invalid id")
                        )
                        return@get
                    }
                    val row = withContext(Dispatchers.IO) { api.getTransaction(id) }
                    if (row == null) {
                        call.respondJson(
                            HttpStatusCode.NotFound,
                            JSONObject().put("error", "Not found")
                        )
                    } else {
                        call.respondJson(HttpStatusCode.OK, row)
                    }
                }
                post("/api/transactions") {
                    if (!requireAuth(call)) return@post
                    val body = parseBody(call) ?: return@post
                    val (code, json) = withContext(Dispatchers.IO) { api.createTransaction(body) }
                    call.respondJson(HttpStatusCode.fromValue(code), json)
                }
                post("/api/transactions/bulk") {
                    if (!requireAuth(call)) return@post
                    val body = parseBody(call) ?: return@post
                    val (code, json) = withContext(Dispatchers.IO) { api.bulkAction(body) }
                    call.respondJson(HttpStatusCode.fromValue(code), json)
                }
                put("/api/transactions/{id}") {
                    if (!requireAuth(call)) return@put
                    val id = call.parameters["id"]?.toLongOrNull()
                    if (id == null) {
                        call.respondJson(
                            HttpStatusCode.BadRequest,
                            JSONObject().put("error", "Invalid id")
                        )
                        return@put
                    }
                    val body = parseBody(call) ?: return@put
                    val (code, json) = withContext(Dispatchers.IO) {
                        api.updateTransaction(id, body)
                    }
                    call.respondJson(HttpStatusCode.fromValue(code), json)
                }
                post("/api/transactions/{id}/review") {
                    if (!requireAuth(call)) return@post
                    val id = call.parameters["id"]?.toLongOrNull()
                    if (id == null) {
                        call.respondJson(
                            HttpStatusCode.BadRequest,
                            JSONObject().put("error", "Invalid id")
                        )
                        return@post
                    }
                    val (code, json) = withContext(Dispatchers.IO) { api.markReviewed(id) }
                    call.respondJson(HttpStatusCode.fromValue(code), json)
                }
                delete("/api/transactions/{id}") {
                    if (!requireAuth(call)) return@delete
                    val id = call.parameters["id"]?.toLongOrNull()
                    if (id == null) {
                        call.respondJson(
                            HttpStatusCode.BadRequest,
                            JSONObject().put("error", "Invalid id")
                        )
                        return@delete
                    }
                    val (code, json) = withContext(Dispatchers.IO) { api.deleteTransaction(id) }
                    call.respondJson(HttpStatusCode.fromValue(code), json)
                }
            }
        }
        engine = server
        server.start(wait = false)
        return port
    }

    fun stop() {
        try {
            engine?.stop(1000, 2000)
        } catch (e: Exception) {
            Log.w(TAG, "Stop failed", e)
        } finally {
            engine = null
        }
    }

    private suspend fun ApplicationCall.serveAsset(path: String, type: ContentType) {
        val bytes = withContext(Dispatchers.IO) {
            appContext.assets.open(path).use { it.readBytes() }
        }
        respondBytes(bytes, type)
    }

    private suspend fun parseBody(call: ApplicationCall): JSONObject? {
        return try {
            JSONObject(call.receiveText())
        } catch (_: Exception) {
            call.respondJson(
                HttpStatusCode.BadRequest,
                JSONObject().put("error", "Invalid JSON")
            )
            null
        }
    }

    private suspend fun requireAuth(call: ApplicationCall): Boolean {
        if (LanServerState.isOpenAccess()) return true
        val header = call.request.header(HttpHeaders.Authorization).orEmpty()
        val token = header.removePrefix("Bearer").trim()
        if (LanServerState.isSessionValid(token)) return true
        call.respondJson(
            HttpStatusCode.Unauthorized,
            JSONObject().put("error", "Login required")
        )
        return false
    }

    private suspend fun ApplicationCall.respondJson(status: HttpStatusCode, json: JSONObject) {
        respondText(json.toString(), ContentType.Application.Json, status)
    }

    private fun findFreePort(): Int {
        ServerSocket(0).use { return it.localPort }
    }

    companion object {
        private const val TAG = "LocalHttpServer"
    }
}
