package app.prend.sound

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tanh

/** RBJ 오디오 EQ 요리책의 2차 필터. 계수는 블록마다 다시 계산하고, 상태는 채널별 배열에 둔다 */
class Biquad {
    private var b0 = 1.0; private var b1 = 0.0; private var b2 = 0.0; private var a1 = 0.0; private var a2 = 0.0

    private fun set(nb0: Double, nb1: Double, nb2: Double, a0: Double, na1: Double, na2: Double) {
        b0 = nb0 / a0; b1 = nb1 / a0; b2 = nb2 / a0; a1 = na1 / a0; a2 = na2 / a0
    }

    fun lowPass(fc: Double, rate: Int, q: Double = 0.7071) {
        val w = 2 * Math.PI * fc / rate; val cs = cos(w); val al = sin(w) / (2 * q)
        set((1 - cs) / 2, 1 - cs, (1 - cs) / 2, 1 + al, -2 * cs, 1 - al)
    }

    fun lowShelf(fc: Double, db: Double, rate: Int) {
        val a = 10.0.pow(db / 40); val w = 2 * Math.PI * fc / rate; val cs = cos(w)
        val al = sin(w) / 2 * sqrt(2.0); val sa = 2 * sqrt(a) * al
        set(a * ((a + 1) - (a - 1) * cs + sa), 2 * a * ((a - 1) - (a + 1) * cs), a * ((a + 1) - (a - 1) * cs - sa),
            (a + 1) + (a - 1) * cs + sa, -2 * ((a - 1) + (a + 1) * cs), (a + 1) + (a - 1) * cs - sa)
    }

    fun highShelf(fc: Double, db: Double, rate: Int) {
        val a = 10.0.pow(db / 40); val w = 2 * Math.PI * fc / rate; val cs = cos(w)
        val al = sin(w) / 2 * sqrt(2.0); val sa = 2 * sqrt(a) * al
        set(a * ((a + 1) + (a - 1) * cs + sa), -2 * a * ((a - 1) + (a + 1) * cs), a * ((a + 1) + (a - 1) * cs - sa),
            (a + 1) - (a - 1) * cs + sa, 2 * ((a - 1) - (a + 1) * cs), (a + 1) - (a - 1) * cs - sa)
    }

    fun peaking(fc: Double, q: Double, db: Double, rate: Int) {
        val a = 10.0.pow(db / 40); val w = 2 * Math.PI * fc / rate; val cs = cos(w); val al = sin(w) / (2 * q)
        set(1 + al * a, -2 * cs, 1 - al * a, 1 + al / a, -2 * cs, 1 - al / a)
    }

    /** s[o..o+3] = x1, x2, y1, y2 */
    fun run(s: DoubleArray, o: Int, x: Double): Double {
        val y = b0 * x + b1 * s[o] + b2 * s[o + 1] - a1 * s[o + 2] - a2 * s[o + 3]
        s[o + 1] = s[o]; s[o] = x; s[o + 3] = s[o + 2]; s[o + 2] = y
        return y
    }
}

/**
 * 한 값을 목표로 천천히 옮긴다. 올라갈 때와 내려갈 때의 시간 상수(초)가 다를 수 있다.
 * rateUp·rateDown(단위/초)을 주면 시간 상수 대신 그 속도로 곧게 옮긴다(알아채지 못할 만큼 천천히 줄이고 키울 때)
 */
class Smooth(initial: Double, private val tauUp: Double, private val tauDown: Double = tauUp) {
    @Volatile var target = initial
    @Volatile var rateUp = 0.0
    @Volatile var rateDown = 0.0
    var value = initial
        private set

    fun step(dt: Double) {
        val rate = if (target > value) rateUp else rateDown
        if (rate > 0) {
            val d = target - value
            value += d.coerceIn(-rate * dt, rate * dt)
            return
        }
        val tau = if (target > value) tauUp else tauDown
        value += (target - value) * (1 - exp(-dt / tau))
    }

    fun jump(v: Double) { value = v; target = v }
}

/** 디버그용: 엔진이 내보낸 소리를 그대로 WAV로 남긴다(시연 미리듣기·검증용, 마이크 소리는 들어가지 않는다) */
class WavCapture(private val file: File, private val rate: Int, private val channels: Int) {
    private val out = BufferedOutputStream(FileOutputStream(file), 1 shl 16)
    private var bytes = 0L

    init { out.write(ByteArray(44)) }

    @Synchronized fun write(b: ByteArray, len: Int) { out.write(b, 0, len); bytes += len }

    @Synchronized fun close() {
        out.close()
        RandomAccessFile(file, "rw").use { f ->
            val h = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
            h.put("RIFF".toByteArray()); h.putInt((36 + bytes).toInt()); h.put("WAVE".toByteArray()); h.put("fmt ".toByteArray())
            h.putInt(16); h.putShort(1); h.putShort(channels.toShort()); h.putInt(rate); h.putInt(rate * channels * 2)
            h.putShort((channels * 2).toShort()); h.putShort(16); h.put("data".toByteArray()); h.putInt(bytes.toInt())
            f.write(h.array())
        }
    }
}

/**
 * 숨 쉬는 음악 엔진. PREND가 트는 곡의 PCM을 재생 직전에 다듬는다(Media3 AudioProcessor, 자기 음원에만).
 * 처리 순서: 저역 통과 2단(신호 대기·잠·눈) → 밤의 고음 누그러뜨리기 → 작은 음량 보정(저음·고음)
 * → 목소리 길 비우기(2kHz 대역) → 크기(숨 고르기 감쇠·비켜 섬·물러섬·말하는 동안·도착·시동·상한) → 넓이(M/S) → 빗소리 결 → 부드러운 한계.
 * 목표 값은 메인 스레드의 SoundDirector가 정하고, 엔진은 블록(64프레임)마다 천천히 따라간다.
 * 어떤 처리도 사람이 정한 음량보다 크게 만들지 않는다(보정으로 늘어난 만큼 미리 줄이고, 끝에 한계를 둔다).
 */
@OptIn(UnstableApi::class)
class BreathEngine(context: Context, private val meter: (String) -> Unit) : BaseAudioProcessor() {

    companion object {
        const val OPEN_HZ = 18000.0
        const val MUFFLED_HZ = 650.0
        const val MUFFLE_GAIN_DB = -3.0
        const val OFF_DB = -90.0
        private val LOG_OPEN = ln(OPEN_HZ)
        private val LOG_MUFFLED = ln(MUFFLED_HZ)
        private const val BLOCK = 64
    }

    // ── 목표 값(메인 스레드가 쓴다) ──
    /** 저역 통과 차단 주파수. 로그 값으로 따라간다(물러남 약 1.5초, 피어남 약 2.5초) */
    val logCutoff = Smooth(LOG_OPEN, tauUp = 0.8, tauDown = 0.5)
    /** 빗소리 결 크기(dB). 얹기 약 3초, 걷기 약 4초 */
    val rainDb = Smooth(OFF_DB, tauUp = 1.0, tauDown = 1.33)
    /** 밤: 6kHz 위를 이만큼(dB) 누그러뜨린다 */
    val nightDb = Smooth(0.0, 2.0)
    /** 넓이: 1.0 그대로, 혼자일 때 1.3 */
    val width = Smooth(1.0, 1.5)
    /** 작은 음량 보정: 120Hz 아래와 10kHz 위를 이만큼(dB) 올린다 */
    val lowBoostDb = Smooth(0.0, 1.0)
    val highBoostDb = Smooth(0.0, 1.0)
    /** 목소리 길 비우기: 2kHz 둘레(약 1~4kHz)를 이만큼(dB) 비운다 */
    val carveDb = Smooth(0.0, tauUp = 0.8, tauDown = 0.15)
    /** 비켜 섬(dB). 내려갈 때 약 1.5초, 돌아올 때 약 5초 */
    val duckDb = Smooth(0.0, tauUp = 1.6, tauDown = 0.5)
    /** 상한(아기 −3dB, 셋 이상 −3dB)과 시동 때 맞춘 크기(dB) */
    val levelDb = Smooth(0.0, 1.0)
    /** 물러서기(dB): 드라이브스루·후진·급제동. 속도(dB/초)는 SoundDirector.hush가 그때마다 정한다 */
    val hushDb = Smooth(0.0, 0.3)
    /** PREND가 말하는 동안(dB). 말보다 먼저 내려가게 약 0.3초에, 말이 끝나면 약 1.5초에 걸쳐 돌아온다 */
    val speakDb = Smooth(0.0, tauUp = 0.5, tauDown = 0.1)

    /** 블록마다 따라가는 값들(블록마다 배열을 새로 만들지 않게 한 번만 묶어 둔다) */
    private val smooths = arrayOf(logCutoff, rainDb, nightDb, width, lowBoostDb, highBoostDb, carveDb, duckDb, levelDb, hushDb, speakDb)

    // 시동·도착의 직선 페이드(dB). 메인 스레드가 fade()로 건다
    @Volatile private var fadeReq: DoubleArray? = null
    private var fadeFrom = 0.0; private var fadeTo = 0.0; private var fadeLen = 0L; private var fadePos = 0L
    private var fadeDb = 0.0

    /** to까지 seconds초에 걸쳐 직선으로(dB 단위). from이 null이면 지금 값에서 시작한다 */
    fun fade(to: Double, seconds: Double, from: Double? = null) {
        fadeReq = doubleArrayOf(from ?: Double.NaN, to, seconds)
    }

    @Volatile var capture: WavCapture? = null

    // ── 빗소리 결(에셋의 WAV를 출력 샘플링 주파수로 바꿔 둔다) ──
    // 5MB라 엔진을 만들 때(음악 서비스 onCreate, 메인 스레드) 읽지 않는다. 처음 비가 필요할 때 다른 스레드가 읽고,
    // 읽기 전에는 비 없이 흐른다(비는 3초에 걸쳐 얹히므로 조금 늦게 들어와도 티가 나지 않는다). 비가 없는 운행은 힙에 올리지도 않는다
    private val assets = context.applicationContext.assets
    @Volatile private var rainSrc: ShortArray? = null
    @Volatile private var rainSrcRate = 44100
    @Volatile private var rainLoading = false
    private var rain = ShortArray(0)
    private var rainPos = 0
    /** rain을 맞춘 출력 샘플링 주파수(-1이면 다시 맞춘다) */
    private var rainFor = -1

    // ── 처리 상태 ──
    private var rate = 44100
    private var channels = 2
    private val lp = Biquad(); private val night = Biquad(); private val lowB = Biquad(); private val highB = Biquad(); private val carve = Biquad()
    /** 넓이를 줄 때 150Hz 아래의 옆 소리는 그대로 둔다(저음은 가운데에 모여 있어야 단단하다) */
    private val sideLow = Biquad()
    private var sideSt = DoubleArray(4)
    private var st = Array(2) { DoubleArray(24) }
    private var scratch = ByteArray(0)

    // 기록: 1초마다 지금 값과 나가는 소리의 크기
    private var meterFrames = 0
    private var meterSum = 0.0

    // 필터 계수를 마지막으로 계산한 값(값이 그대로면 블록마다 cos·sin·pow를 다시 하지 않는다)
    private var cutFor = Double.NaN; private var nightFor = Double.NaN; private var lowFor = Double.NaN
    private var highFor = Double.NaN; private var carveFor = Double.NaN

    /** 빗소리를 처음 쓸 때 한 번, 다른 스레드에서 읽는다 */
    private fun loadRain() {
        if (rainLoading) return
        rainLoading = true
        Thread({ runCatching { readRain() }.onFailure { meter("빗소리 읽기 실패 $it") } }, "prend-rain").start()
    }

    private fun readRain() {
        val bytes = assets.open("rain.wav").use { it.readBytes() }
        val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        var p = 12; var srcRate = 44100; var ch = 2; var data = 44; var len = bytes.size - 44
        while (p + 8 <= bytes.size) {
            val id = String(bytes, p, 4); val size = bb.getInt(p + 4)
            if (id == "fmt ") { ch = bb.getShort(p + 10).toInt(); srcRate = bb.getInt(p + 12) }
            if (id == "data") { data = p + 8; len = minOf(size, bytes.size - data); break }
            p += 8 + size
        }
        val frames = len / (2 * ch)
        val src = ShortArray(frames * 2)
        for (i in 0 until frames) {
            val l = bb.getShort(data + i * 2 * ch)
            val r = if (ch > 1) bb.getShort(data + i * 2 * ch + 2) else l
            src[i * 2] = l; src[i * 2 + 1] = r
        }
        // 주파수를 먼저 적고 배열을 내놓는다(재생 스레드가 배열을 보면 주파수도 맞다)
        rainSrcRate = srcRate
        rainSrc = src
    }

    private fun prepareRain(src: ShortArray, srcRate: Int, outRate: Int) {
        rainFor = outRate
        if (outRate == srcRate) { if (rain !== src) { rain = src; rainPos = 0 }; return }
        val srcFrames = src.size / 2
        val outFrames = (srcFrames.toLong() * outRate / srcRate).toInt()
        rain = ShortArray(outFrames * 2)
        for (i in 0 until outFrames) {
            val x = i.toDouble() * srcRate / outRate
            val j = x.toInt().coerceAtMost(srcFrames - 2); val f = x - j
            for (c in 0..1) rain[i * 2 + c] = (src[j * 2 + c] * (1 - f) + src[(j + 1) * 2 + c] * f).toInt().toShort()
        }
        rainPos = 0
    }

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT) throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        rate = inputAudioFormat.sampleRate
        channels = inputAudioFormat.channelCount
        st = Array(channels) { DoubleArray(24) }
        sideSt = DoubleArray(4)
        sideLow.lowPass(150.0, rate)
        // 주파수가 바뀌었을 수 있어 계수와 빗소리를 다시 맞춘다
        cutFor = Double.NaN; nightFor = Double.NaN; lowFor = Double.NaN; highFor = Double.NaN; carveFor = Double.NaN
        rainFor = -1
        rainSrc?.let { prepareRain(it, rainSrcRate, rate) }
        return inputAudioFormat
    }

    /** 목표가 기준값이고 거의 닿았다: 그 필터는 소리를 바꾸지 않으니 건너뛴다 */
    private fun Smooth.flat(at: Double = 0.0) = target == at && abs(value - at) < 1e-4

    override fun onFlush() {
        st = Array(channels) { DoubleArray(24) }
        sideSt = DoubleArray(4)
    }

    private fun db(x: Double) = 10.0.pow(x / 20)

    override fun queueInput(inputBuffer: ByteBuffer) {
        val bytes = inputBuffer.remaining()
        if (bytes == 0) return
        inputBuffer.order(ByteOrder.nativeOrder())
        val out = replaceOutputBuffer(bytes)
        val frames = bytes / (2 * channels)
        val outStart = out.position()
        var done = 0
        while (done < frames) {
            val block = minOf(BLOCK, frames - done)
            val dt = block.toDouble() / rate
            for (s in smooths) s.step(dt)

            fadeReq?.let { r ->
                fadeReq = null
                fadeFrom = if (r[0].isNaN()) fadeDb else r[0]
                fadeTo = r[1]; fadeLen = maxOf(1L, (r[2] * rate).toLong()); fadePos = 0
                fadeDb = fadeFrom
            }

            // 빗소리: 처음 필요할 때 읽기를 맡기고, 읽혀 들어오면 그때 맞춘다
            if (rainFor != rate) {
                val src = rainSrc
                if (src != null) prepareRain(src, rainSrcRate, rate) else if (rainDb.target > OFF_DB + 1) loadRain()
            }

            // 소리를 바꾸지 않는 필터(0dB 선반·봉우리, 넓이 1)는 건너뛴다. 건너뛰는 동안 상태를 비워 다시 쓸 때 지난 소리가 끼지 않게 한다.
            // 저역 통과(열림 18kHz)는 늘 거친다(빼면 10kHz 위가 조금 밝아져 지금 소리와 달라진다)
            val useNight = !nightDb.flat(); val useLow = !lowBoostDb.flat(); val useHigh = !highBoostDb.flat(); val useCarve = !carveDb.flat()
            val useSide = !width.flat(1.0)
            for (s in st) {
                if (!useNight) s.fill(0.0, 8, 12)
                if (!useLow) s.fill(0.0, 12, 16)
                if (!useHigh) s.fill(0.0, 16, 20)
                if (!useCarve) s.fill(0.0, 20, 24)
            }
            if (!useSide) sideSt.fill(0.0)

            // 계수는 값이 바뀐 블록에서만 다시 계산한다
            if (logCutoff.value != cutFor) { cutFor = logCutoff.value; lp.lowPass(exp(cutFor), rate) }
            if (useNight && nightDb.value != nightFor) { nightFor = nightDb.value; night.highShelf(6000.0, nightFor, rate) }
            if (useLow && lowBoostDb.value != lowFor) { lowFor = lowBoostDb.value; lowB.lowShelf(120.0, lowFor, rate) }
            if (useHigh && highBoostDb.value != highFor) { highFor = highBoostDb.value; highB.highShelf(10000.0, highFor, rate) }
            if (useCarve && carveDb.value != carveFor) { carveFor = carveDb.value; carve.peaking(2000.0, 0.8, carveFor, rate) }

            val m = ((LOG_OPEN - logCutoff.value) / (LOG_OPEN - LOG_MUFFLED)).coerceIn(0.0, 1.0)
            val headroom = -(maxOf(lowBoostDb.value, 0.0) * 0.5 + maxOf(highBoostDb.value, 0.0) * 0.3)
            val w = width.value
            val rainG = if (rainDb.value <= OFF_DB + 1) 0.0 else db(rainDb.value)
            val base = db(MUFFLE_GAIN_DB * m + duckDb.value + levelDb.value + hushDb.value + speakDb.value + headroom)
            var fadeLin = db(fadeDb)

            repeat(block) {
                if (fadePos < fadeLen) {
                    fadePos++; fadeDb = fadeFrom + (fadeTo - fadeFrom) * fadePos / fadeLen
                    fadeLin = db(fadeDb)
                }
                val g = base * fadeLin
                var l = 0.0; var r = 0.0
                for (ch in 0 until channels) {
                    val s = st[ch]
                    var y = inputBuffer.short / 32768.0
                    y = lp.run(s, 0, y); y = lp.run(s, 4, y)
                    if (useNight) y = night.run(s, 8, y)
                    if (useLow) y = lowB.run(s, 12, y)
                    if (useHigh) y = highB.run(s, 16, y)
                    if (useCarve) y = carve.run(s, 20, y)
                    y *= g
                    if (ch == 0) l = y else if (ch == 1) r = y
                }
                if (channels >= 2 && useSide) {
                    // 가운데(목소리·저음)는 그대로, 150Hz 위의 옆 소리만 w배로 벌린다(넓이 1이면 그대로라 건너뛴다)
                    val mid = (l + r) / 2; val side = (l - r) / 2
                    val low = sideLow.run(sideSt, 0, side)
                    val wide = low + (side - low) * w
                    l = mid + wide; r = mid - wide
                }
                if (rainG > 0 && rain.isNotEmpty()) {
                    l += rain[rainPos] / 32768.0 * rainG * fadeLin; r += rain[rainPos + 1] / 32768.0 * rainG * fadeLin
                    rainPos += 2; if (rainPos >= rain.size) rainPos = 0
                }
                l = soft(l); r = soft(r)
                meterSum += l * l
                out.putShort((l * 32767).toInt().toShort())
                if (channels >= 2) {
                    out.putShort((r * 32767).toInt().toShort())
                    repeat(channels - 2) { out.putShort(0) }
                }
            }
            done += block
            meterFrames += block
            if (meterFrames >= rate) {
                val rms = 10 * log10(meterSum / meterFrames + 1e-12)
                meter("차단 ${exp(logCutoff.value).toInt()}Hz · 비 ${fmt(rainDb.value)} · 밤 ${fmt(nightDb.value)} · 넓이 ${"%.2f".format(w)} · " +
                    "보정 ${fmt(lowBoostDb.value)}/${fmt(highBoostDb.value)} · 목소리길 ${fmt(carveDb.value)} · 비켜섬 ${fmt(duckDb.value)} · 물러섬 ${fmt(hushDb.value)} · 말 ${fmt(speakDb.value)} · " +
                    "크기 ${fmt(levelDb.value)} · 페이드 ${fmt(fadeDb)} · 출력 ${"%.1f".format(rms)}dBFS")
                meterFrames = 0; meterSum = 0.0
            }
        }
        capture?.let { cap ->
            val len = out.position() - outStart
            if (scratch.size < len) scratch = ByteArray(len)
            val dup = out.duplicate(); dup.position(outStart); dup.get(scratch, 0, len)
            cap.write(scratch, len)
        }
        out.flip()
    }

    /** 0.9를 넘는 부분만 부드럽게 눌러 깨지지 않게 한다 */
    private fun soft(x: Double): Double {
        val a = abs(x)
        if (a <= 0.9) return x
        val y = 0.9 + 0.1 * tanh((a - 0.9) / 0.1)
        return if (x < 0) -y else y
    }

    private fun fmt(v: Double) = if (v <= OFF_DB + 1) "끔" else "%.1f".format(v)

    /** 지금 따라가고 있는 값(화면의 시연 패널용) */
    fun snapshot(): Map<String, String> = mapOf(
        "차단" to "${exp(logCutoff.value).toInt()}Hz",
        "비" to fmt(rainDb.value),
        "밤" to fmt(nightDb.value),
        "넓이" to "%.2f".format(width.value),
        "보정" to "${fmt(lowBoostDb.value)}/${fmt(highBoostDb.value)}",
        "목소리길" to fmt(carveDb.value),
        "비켜섬" to fmt(duckDb.value),
        "말" to fmt(speakDb.value),
        "크기" to fmt(levelDb.value),
        "페이드" to fmt(fadeDb),
    )

    /** 화면의 소리 막대용: 목소리 길(dB), 비켜 섬(dB), 빗소리(dB 또는 OFF_DB), 넓이 */
    fun levels(): DoubleArray = doubleArrayOf(carveDb.value, duckDb.value, rainDb.value, width.value, levelDb.value)

    /** 비교 녹음용: 모든 값을 목표로 바로 옮긴다(멈춰 있는 동안에 부른다) */
    fun settle() {
        for (s in arrayOf(logCutoff, rainDb, nightDb, width, lowBoostDb, highBoostDb, carveDb, duckDb, levelDb)) s.jump(s.target)
    }

    fun muffledCutoffLog() = LOG_MUFFLED
    fun openCutoffLog() = LOG_OPEN
}
