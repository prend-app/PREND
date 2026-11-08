package app.prend.voice

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import app.prend.Prend
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.LongBuffer
import kotlin.math.log10

/**
 * 이야기에 비켜 서기의 귀(접수양식 3번 '함께 타면', 별첨 1·5-5). 마이크 소리를 0.032초(512표본) 조각마다 기기 안의
 * Silero VAD(v6.2, MIT, assets/models)에 넣어 '지금 말소리가 있는가'만 판정하고 조각은 바로 버린다.
 * 글자로 바꾸지 않고, 저장하지도 밖으로 보내지도 않는다. 기록에는 이야기의 시작·끝과 길이만 남긴다.
 * - 판정: 말소리 확률 0.5 이상인 조각이 최근 20조각(0.64초) 중 8개 이상이면 '이야기 중', 말소리 조각 없이 1.5초가 지나면 끝
 * - 크기: 말소리 조각과 그 밖의 조각의 평균 크기 차(dB)를 '목소리가 배경보다 몇 dB 큰가'로 넘긴다(PREND 곡을 얼마나 줄일지)
 * - muted()인 동안(PREND가 말하는 중·Pleos 음성 인식이 듣는 중)은 조각을 판정하지 않는다(제 목소리를 이야기로 알지 않게)
 * 마이크는 운행 서비스가 정한 때(켜 둠·마이크 허락·이번 운행에 화면을 엶·동승자)에만 연다.
 */
class TalkSense(private val context: Context, private val muted: () -> Boolean) {

    /** 이야기의 시작(true)·끝(false). 메인 스레드에서 부른다 */
    var onTalking: ((Boolean) -> Unit)? = null
    /** 목소리가 배경보다 몇 dB 큰가(모르면 null). 1초쯤마다 메인 스레드에서 부른다 */
    var onVoiceOverBg: ((Double?) -> Unit)? = null

    private val main = Handler(Looper.getMainLooper())
    @Volatile private var running = false
    private var thread: Thread? = null

    /** 지금 귀를 열어 두었나(마이크 또는 시험 파일) */
    val active get() = running

    /** 오류로 듣기가 저절로 끝난 때(운행 서비스가 30초 뒤에 다시 연다) */
    @Volatile var diedAt = 0L
        private set
    /** 시험 파일을 흘리는 중(운행 서비스가 멈추지 않는다) */
    @Volatile var testing = false
        private set

    @Volatile private var env: OrtEnvironment? = null
    @Volatile private var session: OrtSession? = null
    @Volatile private var loadFailed = false
    private var wasMuted = false
    private var skip = 0
    private var runFailed = false

    // 아래는 듣는 스레드만 만진다(시작 전에 비운다)
    private val state = FloatArray(2 * 1 * 128)
    private val input = FloatArray(CONTEXT + FRAME)
    private val tail = FloatArray(CONTEXT)
    private val window = BooleanArray(WINDOW)
    private var wi = 0
    private var speechInWindow = 0
    private var frame = 0L
    private var talking = false
    private var lastSpeechMs = 0L
    private var talkStartMs = 0L
    private var speechDb = Double.NaN
    private var bgDb = Double.NaN
    private var speechN = 0
    private var bgN = 0

    /** Silero 모델을 처음 한 번 읽는다. 실패하면 이 운행에는 다시 하지 않는다. 읽는 중에 멈췄다 다시 켜도 두 번 읽지 않게 한 번에 하나만 */
    @Synchronized private fun load(): Boolean {
        if (session != null) return true
        if (loadFailed) return false
        return runCatching {
            val bytes = context.assets.open("models/silero_vad.onnx").use { it.readBytes() }
            val e = OrtEnvironment.getEnvironment()
            val opts = OrtSession.SessionOptions().apply { setIntraOpNumThreads(1); setInterOpNumThreads(1) }
            session = e.createSession(bytes, opts)
            env = e
            Prend.log("· 말소리 판정기 준비(Silero VAD, ${bytes.size / 1024}KB)")
            true
        }.getOrElse { loadFailed = true; Prend.log("✗ 말소리 판정기 읽기 실패 $it"); false }
    }

    /** 마이크로 듣기 시작(이미 듣는 중이면 그대로). 마이크를 열지 못하면 false. 판정기(약 2MB, 네이티브 라이브러리)는 듣는 스레드에서 읽는다(화면이 멈칫하지 않게) */
    fun start(): Boolean {
        if (running) return true
        if (loadFailed) return false
        val rec = openMic() ?: return false
        reset()
        running = true
        val g = ++gen
        // 판정기를 읽는 동안 멈췄으면 마이크를 열지 않고 놓는다(전에는 멈춘 뒤에도 잠깐 녹음을 시작했다, 11/04 점검).
        // 멈췄다 곧 다시 켰으면 옛 스레드는 제 세대가 아니라 물러난다(둘이 같은 판정 상태를 함께 쓰지 않게, 11/04 검토)
        thread = Thread({ if (load() && running && gen == g) loopMic(rec, g) else { rec.release(); if (running && gen == g) died() } }, "prend-talk").also { it.start() }
        Prend.log("◆ 말소리 감지 시작(마이크, 말은 글자로 바꾸지 않음)")
        return true
    }

    /** 듣는 스레드가 오류로 끝났다: 듣기를 내려놓고, 이야기 중이었으면 끝난 것으로 알린다(말 삼키기·곡·바람이 굳지 않게) */
    private fun died() {
        if (!running) return
        running = false
        diedAt = android.os.SystemClock.elapsedRealtime()
        val was = talking
        talking = false
        main.post { Prend.log("✗ 말소리 감지가 멈춰 30초 뒤 다시 열어요"); if (was) onTalking?.invoke(false) }
    }

    /** 듣는 스레드가 쥔 마이크(stop이 막힌 읽기를 깨우려고) */
    @Volatile private var mic: AudioRecord? = null

    /** 듣기를 멈춘다. 이야기 중이었으면 끝난 것으로 알린다 */
    fun stop(why: String) {
        if (!running) return
        running = false
        gen++
        // 읽기에 막힌 스레드를 바로 깨운다(메인이 최대 0.6초 기다리지 않게). 마이크는 여기서 내려놓아야 Pleos 음성 인식이 연다
        // 녹음 중일 때만 스레드가 마이크를 놓기를 기다린다(보통 몇 ms). 판정기를 읽는 중(마이크 아직 안 엶)이면 기다리지 않는다: 전에는 그 동안 메인이 최대 0.6초 멈췄다(11/04 점검)
        val m = mic
        m?.let { runCatching { it.stop() } }
        if (m != null) thread?.let { runCatching { it.join(600) } }
        thread = null
        if (talking) { talking = false; main.post { onTalking?.invoke(false) } }
        Prend.log("◆ 말소리 감지 멈춤($why)")
    }

    fun release() {
        stop("서비스 끝")
        runCatching { session?.close() }
        session = null
    }

    @SuppressLint("MissingPermission") // 운행 서비스가 마이크 허락을 확인한 뒤에만 부른다
    private fun openMic(): AudioRecord? {
        val min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val rec = runCatching {
            AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min, FRAME * 2 * 8))
        }.getOrNull()
        if (rec == null || rec.state != AudioRecord.STATE_INITIALIZED) {
            rec?.release()
            Prend.log("✗ 말소리 감지: 마이크를 열지 못함")
            return null
        }
        return rec
    }

    /** 듣는 스레드의 세대(start마다 하나 올리고 stop에서도 올린다) */
    @Volatile private var gen = 0

    private fun loopMic(rec: AudioRecord, g: Int) {
        val pcm = ShortArray(FRAME)
        var fails = 0
        mic = rec
        val live = { running && gen == g }
        try {
            if (!live()) return
            rec.startRecording()
            while (live()) {
                var got = 0
                while (got < FRAME && live()) {
                    val n = rec.read(pcm, got, FRAME - got)
                    if (n <= 0) break
                    got += n
                }
                if (!live()) break
                if (got < FRAME) {
                    // 읽기 오류가 1초 넘게 이어지면(오디오 서버가 다시 뜸 등) 마이크를 놓고 다시 열기에 맡긴다
                    if (fails++ == 0) main.post { Prend.log("✗ 말소리 감지: 마이크 읽기 오류") }
                    if (fails >= 20) break
                    Thread.sleep(50)
                    continue
                }
                fails = 0
                process(pcm)
            }
        } catch (e: Exception) {
            main.post { Prend.log("✗ 말소리 감지 멈춤: ${e.javaClass.simpleName}") }
        } finally {
            if (mic === rec) mic = null
            runCatching { rec.stop() }
            rec.release()
            pcm.fill(0)
            if (gen == g) died()
        }
    }

    /**
     * 시험: 마이크 대신 16kHz 모노 16비트 WAV 파일을 실제 시간으로 흘려 같은 판정을 거친다(에뮬레이터에는 사람 목소리가 없다).
     * 파일은 디버그 빌드의 앱 폴더(files)에 adb exec-in run-as로 넣는다
     */
    fun feedFile(f: File, done: () -> Unit = {}) {
        if (running) stop("시험 파일")
        if (!load()) return
        val pcm = runCatching { readWav(f) }.getOrElse { Prend.log("✗ 시험 파일 읽기 실패 ${f.name}: $it"); return }
        reset()
        running = true
        testing = true
        Prend.log("◆ 말소리 감지 시험: ${f.name} ${"%.1f".format(pcm.size / RATE.toFloat())}초")
        thread = Thread({
            val buf = ShortArray(FRAME)
            var i = 0
            val t0 = System.nanoTime()
            while (running && i + FRAME <= pcm.size) {
                System.arraycopy(pcm, i, buf, 0, FRAME)
                process(buf)
                i += FRAME
                // 마이크처럼 0.032초에 한 조각
                val due = t0 + (i.toLong() * 1_000_000_000L / RATE)
                val wait = (due - System.nanoTime()) / 1_000_000
                if (wait > 0) Thread.sleep(wait)
            }
            // 파일이 끝나면 조용한 1.6초를 더 흘려 이야기의 끝을 판정한다
            buf.fill(0)
            repeat((END_MS / FRAME_MS + 2).toInt()) { if (running) { process(buf); Thread.sleep(FRAME_MS) } }
            running = false
            testing = false
            val was = talking
            talking = false
            main.post { Prend.log("· 말소리 감지 시험 끝: ${f.name}"); if (was) onTalking?.invoke(false); done() }
        }, "prend-talk-test").also { it.start() }
    }

    private fun reset() {
        state.fill(0f); tail.fill(0f); window.fill(false)
        wi = 0; speechInWindow = 0; frame = 0; talking = false; lastSpeechMs = 0; talkStartMs = 0; wasMuted = false; skip = 0
        speechDb = Double.NaN; bgDb = Double.NaN; speechN = 0; bgN = 0
    }

    /** 한 조각(512표본)을 판정한다. 조각의 소리는 여기서만 쓰고 남기지 않는다 */
    private fun process(pcm: ShortArray) {
        frame++
        val t = frame * FRAME_MS
        if (muted()) { wasMuted = true; return }
        if (wasMuted) {
            // PREND 목소리가 끝났다: 그 앞의 판정 창·문맥·상태는 묵었으니 새 흐름처럼 비우고, 목소리 꼬리(0.4초)는 버린다.
            // 이야기 중이었으면 끝 판정은 지금부터 1.5초를 다시 센다
            wasMuted = false
            state.fill(0f); tail.fill(0f); window.fill(false); speechInWindow = 0
            skip = 12
            if (talking) lastSpeechMs = t
        }
        if (skip > 0) { skip--; return }
        System.arraycopy(tail, 0, input, 0, CONTEXT)
        var power = 0.0
        for (i in 0 until FRAME) {
            val v = pcm[i] / 32768f
            input[CONTEXT + i] = v
            power += v * v
        }
        val p = prob() ?: return
        System.arraycopy(input, FRAME, tail, 0, CONTEXT)
        val db = 10 * log10(power / FRAME + 1e-10)
        val speech = p >= THRESHOLD
        if (window[wi]) speechInWindow--
        window[wi] = speech
        if (speech) speechInWindow++
        wi = (wi + 1) % WINDOW
        if (speech) { lastSpeechMs = t; speechDb = ema(speechDb, db); speechN++ } else { bgDb = ema(bgDb, db); bgN++ }
        if (!talking && speechInWindow >= START_FRAMES) {
            talking = true
            // 판정 창에 든 말소리 조각만큼 앞에서 시작한 것으로 본다
            talkStartMs = t - speechInWindow * FRAME_MS
            main.post { Prend.log("◆ 이야기 시작(말소리)"); onTalking?.invoke(true) }
        } else if (talking && t - lastSpeechMs >= END_MS) {
            talking = false
            val sec = maxOf(0L, lastSpeechMs - talkStartMs) / 1000.0
            main.post { Prend.log("◆ 이야기 끝(${"%.1f".format(sec)}초)"); onTalking?.invoke(false) }
        }
        if (frame % 31 == 0L) {
            val gap = if (speechN >= 10 && bgN >= 10) speechDb - bgDb else null
            main.post { onVoiceOverBg?.invoke(gap) }
        }
    }

    private fun ema(prev: Double, x: Double) = if (prev.isNaN()) x else prev * .95 + x * .05

    /** Silero v6 한 번: 입력은 앞 조각의 끝 64표본 + 이번 512표본, 상태 2×1×128을 이어 쓴다 */
    @Suppress("UNCHECKED_CAST")
    private fun prob(): Float? {
        val e = env ?: return null
        val s = session ?: return null
        return runCatching {
            OnnxTensor.createTensor(e, FloatBuffer.wrap(input), longArrayOf(1, (CONTEXT + FRAME).toLong())).use { x ->
                OnnxTensor.createTensor(e, FloatBuffer.wrap(state), longArrayOf(2, 1, 128)).use { st ->
                    OnnxTensor.createTensor(e, LongBuffer.wrap(longArrayOf(RATE.toLong())), longArrayOf()).use { sr ->
                        s.run(mapOf("input" to x, "state" to st, "sr" to sr)).use { out ->
                            val p = (out.get(0).value as Array<FloatArray>)[0][0]
                            (out.get(1) as OnnxTensor).floatBuffer.get(state)
                            p
                        }
                    }
                }
            }
        }.onFailure { if (!runFailed) { runFailed = true; main.post { Prend.log("✗ 말소리 판정 실패 $it") } } }.getOrNull()
    }

    /** 16kHz 모노 16비트 WAV의 data 덩어리만 읽는다 */
    private fun readWav(f: File): ShortArray {
        RandomAccessFile(f, "r").use { raf ->
            val head = ByteArray(12); raf.readFully(head)
            require(String(head, 0, 4) == "RIFF" && String(head, 8, 4) == "WAVE") { "WAV가 아님" }
            val b4 = ByteArray(4)
            while (true) {
                raf.readFully(b4); val id = String(b4)
                raf.readFully(b4); val len = ByteBuffer.wrap(b4).order(ByteOrder.LITTLE_ENDIAN).int
                if (id == "fmt ") {
                    val fmt = ByteArray(len); raf.readFully(fmt)
                    val bb = ByteBuffer.wrap(fmt).order(ByteOrder.LITTLE_ENDIAN)
                    val ch = bb.getShort(2).toInt(); val rate = bb.getInt(4); val bits = bb.getShort(14).toInt()
                    require(ch == 1 && rate == RATE && bits == 16) { "16kHz 모노 16비트가 아님($rate Hz, ${ch}ch, ${bits}bit)" }
                } else if (id == "data") {
                    val data = ByteArray(len); raf.readFully(data)
                    val out = ShortArray(len / 2)
                    ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(out)
                    return out
                } else raf.seek(raf.filePointer + len + (len and 1))
            }
        }
    }

    private companion object {
        const val RATE = 16_000
        const val FRAME = 512
        const val CONTEXT = 64
        const val FRAME_MS = 32L
        const val THRESHOLD = .5f
        /** 최근 WINDOW조각 중 START_FRAMES조각 넘게 말소리면 이야기 시작 */
        const val WINDOW = 20
        const val START_FRAMES = 8
        /** 말소리 조각 없이 이만큼 지나면 이야기 끝 */
        const val END_MS = 1_500L
    }
}
