package com.fersaiyan.cyanbridge.shared.platform

/** One HTTP exchange. Timeouts are honoured where the platform transport supports them. */
data class HttpRequest(
    val method: String,
    val url: String,
    val headers: Map<String, String> = emptyMap(),
    val body: String? = null,
    val connectTimeoutMs: Int = 30_000,
    val readTimeoutMs: Int = 60_000,
)

/**
 * The HTTP seam used by the shared AI clients. Android passes a HttpURLConnection
 * transport (the one the app always used, with per-request timeouts); iOS uses
 * [PlatformHttpTransport] over NSURLSession.
 */
fun interface HttpTransport {
    suspend fun execute(request: HttpRequest): HttpResponse
}

/** [HttpTransport] over [PlatformHttpClient]; timeouts are the client's own. */
class PlatformHttpTransport(
    private val client: PlatformHttpClient = PlatformHttpClient(),
) : HttpTransport {
    override suspend fun execute(request: HttpRequest): HttpResponse =
        if (request.method.equals("GET", ignoreCase = true)) {
            client.get(request.url, request.headers)
        } else {
            client.post(request.url, request.body.orEmpty(), request.headers)
        }
}
