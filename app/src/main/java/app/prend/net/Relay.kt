package app.prend.net

import app.prend.BuildConfig
import app.prend.Prend
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * PREND 중계 서버(dev/hmg-pleos/relay, Cloudflare Workers). 네이버·공공데이터 키는 서버에만 있고 앱은 토큰으로만 부른다.
 * 보내는 지역 이름·위치는 기록에 남기지 않는다(경로만 적는다).
 */
object Relay {
    private const val BASE = "https://prend-relay.whereis-lab.workers.dev"

    /** 기다림 상한을 넘긴 요청을 기다리지 않고 버려 둘 곳(막힌 연결은 끊고, 늦게 끝나도 결과를 쓰지 않는다) */
    private val io = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * GET 하고 JSON을 돌려준다. 인터넷이 없거나 서버가 실패하면 null.
     * POST처럼 전체를 waitMs로 자른다: 전에는 연결 6초·읽기 10초만 걸어 망이 막히면 16초 넘게 기다렸고,
     * 그동안 아플 때 안내가 병원을 고르지 못한 채 넘어갈 수 있었다(11/04 점검). 넘으면 앱 안 목록 같은 대신할 길로 간다
     */
    suspend fun get(path: String, params: Map<String, String>, waitMs: Int = 6_000): JSONObject? {
        if (BuildConfig.RELAY_TOKEN.isEmpty()) { Prend.log("✗ 중계 서버 토큰 없음"); return null }
        val query = params.entries.joinToString("&") { "${it.key}=${URLEncoder.encode(it.value, "UTF-8")}" }
        var conn: HttpURLConnection? = null
        var gaveUp = false
        val job = io.async {
            runCatching {
                val c = URL("$base$path?$query").openConnection() as HttpURLConnection
                conn = c
                c.connectTimeout = waitMs
                c.readTimeout = waitMs
                c.setRequestProperty("x-prend-token", BuildConfig.RELAY_TOKEN)
                try {
                    val ok = c.responseCode in 200..299
                    val body = (if (ok) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() } ?: ""
                    if (!ok) error("HTTP ${c.responseCode} ${body.take(80)}")
                    JSONObject(body)
                } finally { c.disconnect() }
            }.onFailure { if (!gaveUp) Prend.log("✗ 중계 서버 $path: ${it.javaClass.simpleName} ${it.message?.take(80)}") }.getOrNull()
        }
        val done = withTimeoutOrNull(waitMs.toLong()) { job.await() }
        if (done == null && job.isActive) {
            gaveUp = true
            job.cancel()
            runCatching { conn?.disconnect() }
            Prend.log("✗ 중계 서버 $path: ${waitMs}ms 안에 답이 없어 그만")
        }
        return done
    }

    /**
     * POST로 JSON을 보내고 JSON을 돌려준다(들은 말처럼 주소에 남기면 안 되는 것). 차 안에서 기다릴 수 있는 만큼만 기다린다.
     * 연결·읽기 제한은 따로 걸려 최악이면 둘을 더한 만큼(8초) 걸리고, 주소 찾기는 어느 쪽에도 들지 않는다:
     * 그래서 전체를 waitMs로 자르고, 넘으면 연결을 끊고 결과를 버린다(감사 F94③).
     * onHttp: 서버가 2xx가 아닌 상태로 답하면 그 번호를 알린다(429 바쁨·5xx 실패를 망 끊김과 가르려고, 11/08 오류 점검 D5). 답이 없으면 부르지 않는다
     */
    suspend fun post(path: String, body: JSONObject, waitMs: Int = 4_000, onHttp: ((Int) -> Unit)? = null): JSONObject? {
        if (BuildConfig.RELAY_TOKEN.isEmpty()) { Prend.log("✗ 중계 서버 토큰 없음"); return null }
        var conn: HttpURLConnection? = null
        // 그만둔 뒤 끊긴 연결의 실패는 기록에 다시 남기지 않는다(기록용이라 스레드 사이에 늦게 보여도 된다)
        var gaveUp = false
        val job = io.async {
            runCatching {
                val c = URL("$base$path").openConnection() as HttpURLConnection
                conn = c
                c.connectTimeout = waitMs
                c.readTimeout = waitMs
                c.requestMethod = "POST"
                c.doOutput = true
                c.setRequestProperty("x-prend-token", BuildConfig.RELAY_TOKEN)
                c.setRequestProperty("content-type", "application/json; charset=utf-8")
                try {
                    c.outputStream.use { it.write(body.toString().toByteArray()) }
                    val ok = c.responseCode in 200..299
                    val text = (if (ok) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() } ?: ""
                    if (!ok) { onHttp?.invoke(c.responseCode); error("HTTP ${c.responseCode} ${text.take(80)}") }
                    JSONObject(text)
                } finally { c.disconnect() }
            }.onFailure { if (!gaveUp) Prend.log("✗ 중계 서버 $path: ${it.javaClass.simpleName} ${it.message?.take(80)}") }.getOrNull()
        }
        val done = withTimeoutOrNull(waitMs.toLong()) { job.await() }
        if (done == null && job.isActive) {
            gaveUp = true
            job.cancel()
            // 막힌 읽기·쓰기는 연결을 끊어야 풀린다(주소 찾기는 끝날 때까지 남지만 결과는 쓰지 않는다)
            runCatching { conn?.disconnect() }
            Prend.log("✗ 중계 서버 $path: ${waitMs}ms 안에 답이 없어 그만")
        }
        return done
    }

    /** 시험: 중계 서버 주소를 PC에서 돌리는 것(에뮬레이터에서 10.0.2.2)으로 바꾼다. null이면 원래대로 */
    fun pretendBase(url: String?) { base = url ?: BASE; Prend.log("· 시험: 중계 서버 ${if (url == null) "원래대로" else "PC"}") }
    private var base = BASE
}
