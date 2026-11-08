package app.prend.ride

import android.content.Context
import app.prend.ui.Hospital
import java.util.Calendar

/**
 * 인터넷이 없을 때 쓰는 병원 목록(감사 F40). 응급의료기관과 달빛어린이병원을 앱에 넣어 두었다(assets/hospitals/hospitals.csv).
 * 출처: 국립중앙의료원 '전국 응급의료기관 정보 조회 서비스'와 '전국 병·의원 찾기 서비스'(공공데이터포털, 이용허락범위 제한 없음).
 * 중계 서버가 부르는 API의 목록 쪽을 tools/collect_hospitals.js 가 불러 만든다. 남은 병상은 없고 그 뒤 바뀌었을 수 있어,
 * 화면과 목소리에 '가기 전에 전화로 확인하세요'를 붙인다.
 * 고르는 법은 직선거리다. 응급실은 지정 응급의료기관(권역·지역센터·지역기관) 가운데 30km 안의 가장 가까운 곳,
 * 없으면 응급실을 운영하는 어느 곳이든 가장 가까운 곳. 달빛어린이병원은 오늘 요일의 진료 시간 안이고 50km 안인 가장 가까운 곳
 */
class Hospitals(context: Context) {

    /** 목록 한 줄. hours는 요일별 진료 시간 'HHmm-HHmm' 여덟 칸(월~일, 공휴일), 응급실은 비어 있다 */
    private class Row(val kind: String, val name: String, val tel: String?, val lat: Double, val lon: Double, val cls: String, val addr: String, val hours: List<String>)

    /** 목록을 모은 날(YYYY-MM-DD). 화면의 '기준'에 쓴다 */
    val asOf: String?
    private val rows: List<Row>

    init {
        var date: String? = null
        val list = mutableListOf<Row>()
        context.assets.open("hospitals/hospitals.csv").bufferedReader().useLines { lines ->
            for (line in lines) {
                if (line.startsWith("#asof=")) { date = line.removePrefix("#asof="); continue }
                val f = line.split(',')
                if (f.size < 8 || f[0] == "kind") continue
                val lat = f[3].toDoubleOrNull() ?: continue
                val lon = f[4].toDoubleOrNull() ?: continue
                list += Row(f[0], f[1], f[2].ifBlank { null }, lat, lon, f[5], f[6], f[7].split('|'))
            }
        }
        asOf = date
        rows = list
    }

    val count get() = rows.size

    /** 가까운 응급실. 100km 안에 없으면(나라 밖 시험 위치 등) null */
    fun er(lat: Double, lon: Double): Hospital? {
        val near = rows.filter { it.kind == "er" }.map { it to RestAreas.dist(lat, lon, it.lat, it.lon) / 1000 }.sortedBy { it.second }
        val (row, km) = near.firstOrNull { it.first.cls in DESIGNATED && it.second <= 30 } ?: near.firstOrNull() ?: return null
        if (km > 100) return null
        return Hospital(row.name, row.addr, row.tel, row.lat, row.lon, km, kind = row.cls)
    }

    /** 오늘 진료 시간 안인 가까운 달빛어린이병원(50km 안). open·close는 오늘의 HHmm(Crash가 '오늘 몇 시~몇 시'로 보인다) */
    fun moon(lat: Double, lon: Double, now: Calendar = Calendar.getInstance()): Hospital? {
        // Calendar: 일요일 1 … 토요일 7 → 목록의 칸: 월 0 … 일 6(공휴일 칸은 공휴일을 몰라 쓰지 않는다)
        val day = (now.get(Calendar.DAY_OF_WEEK) + 5) % 7
        val hm = now.get(Calendar.HOUR_OF_DAY) * 100 + now.get(Calendar.MINUTE)
        return rows.asSequence().filter { it.kind == "moon" }
            .mapNotNull { r ->
                val (o, c) = r.hours.getOrNull(day)?.split('-')?.takeIf { it.size == 2 } ?: return@mapNotNull null
                val oi = o.toIntOrNull() ?: return@mapNotNull null
                val ci = c.toIntOrNull() ?: return@mapNotNull null
                // 끝이 시작보다 이르면 밤을 넘기는 진료
                val open = if (oi <= ci) hm in oi until ci else hm >= oi || hm < ci
                if (!open) null else Triple(r, RestAreas.dist(lat, lon, r.lat, r.lon) / 1000, o to c)
            }
            .filter { it.second <= 50 }
            .minByOrNull { it.second }
            ?.let { (r, km, oc) -> Hospital(r.name, r.addr, r.tel, r.lat, r.lon, km, open = oc.first, close = oc.second, kind = "달빛어린이병원") }
    }

    companion object {
        /** 목록의 응급의료기관 분류 가운데 지정 기관 셋. '응급실운영신고기관'은 30km 안에 이 셋이 없을 때만 고른다 */
        private val DESIGNATED = setOf("권역응급의료센터", "지역응급의료센터", "지역응급의료기관")
    }
}
