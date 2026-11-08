package app.prend

import android.content.Context
import android.util.Log
import java.io.File

/**
 * 앱 기록: logcat(태그 PREND)과 앱 내부 폴더의 prend.log(adb run-as로 읽는다). 대화 내용·원음은 어디에도 남기지 않는다.
 * 읽기: adb shell run-as app.prend --user 10 cat files/prend.log
 */
object Prend {
    /** 앱 이름과 부르는 이름([프렌드] 캡슐·배너). 정하는 곳은 app/build.gradle.kts 한 곳이고, 화면·알림·토스트는 이것만 쓴다 */
    const val NAME = BuildConfig.APP_NAME
    const val CALL = BuildConfig.CALL_NAME

    private var file: File? = null
    private val writer = java.util.concurrent.Executors.newSingleThreadExecutor { Thread(it, "prend-log").apply { isDaemon = true } }

    fun init(context: Context) {
        if (file == null) file = File(context.filesDir, "prend.log")
    }

    fun log(s: String) {
        Log.i("PREND", s)
        val f = file ?: return
        val line = "${System.currentTimeMillis()} $s\n"
        writer.execute {
            runCatching {
                // 한 운행이 아주 길어도 1MB를 넘기지 않는다. 넘으면 지금 것을 prend.over.log로 돌리고 새로 쓴다(전에는 통째로 지워 그 운행의 앞 기록을 잃었다)
                if (f.length() > MAX_BYTES) File(f.parentFile, "prend.over.log").let { o -> o.delete(); f.renameTo(o) }
                f.appendText(line)
            }
        }
    }

    /** 새 운행: 지난 운행의 기록을 지운다. 디버그 빌드는 시험을 되짚으려고 하나 앞(prend.prev.log)까지 둔다 */
    fun newRide() {
        val f = file ?: return
        writer.execute {
            runCatching {
                val prev = File(f.parentFile, "prend.prev.log")
                prev.delete()
                File(f.parentFile, "prend.over.log").delete()
                if (BuildConfig.DEBUG) f.renameTo(prev) else f.delete()
            }
        }
    }

    private const val MAX_BYTES = 1L shl 20
}
