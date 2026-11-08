package app.prend.car

import ai.pleos.playground.navi.constants.NaviErrorCode
import ai.pleos.playground.navi.constants.WaypointIndex
import ai.pleos.playground.navi.data.CurrentLocationInfo
import ai.pleos.playground.navi.data.RequestWaypointInfo
import ai.pleos.playground.navi.data.RouteInfo
import ai.pleos.playground.navi.data.RouteStartInfo
import ai.pleos.playground.navi.data.WaypointInfo
import ai.pleos.playground.navi.helper.NaviHelper
import ai.pleos.playground.navi.helper.listener.NaviHelperEventListener
import android.content.Context
import app.prend.Prend

/**
 * 시험 46: 쉼터·병원을 차 내비게이션의 경유지로 넣을 수 있는가(NaviHelper).
 * 시연 패널 명령 navi_init → navi_route → navi_wp → navi_wpinfo / navi_loc 순서로 부른다.
 */
class NaviProbe(context: Context) {

    private val navi = NaviHelper(context.applicationContext)
    private var ready = false

    private val listener = object : NaviHelperEventListener {
        override fun onRouteStarted(routeStartInfo: RouteStartInfo) = Prend.log("· 내비: 경로 시작 $routeStartInfo")
        override fun onRoutingCompleted() = Prend.log("· 내비: 경로 탐색 끝")
        override fun onRouteCancelled() = Prend.log("· 내비: 경로 취소")
        override fun onCurrentLocationInfo(currentLocationInfo: CurrentLocationInfo) = Prend.log("· 내비: 현재 위치 $currentLocationInfo")
        override fun onWayPointInfo(waypointInfo: List<WaypointInfo>) = Prend.log("· 내비: 경유지 목록 $waypointInfo")
        override fun onError(errorCode: NaviErrorCode) = Prend.log("✗ 내비 오류 $errorCode")
    }

    fun init() {
        navi.initialize(
            onConnected = { ready = true; navi.addListener(listener); Prend.log("◆ 내비: 연결됨") },
            onFailed = { t -> Prend.log("✗ 내비 연결 실패 $t") },
        )
    }

    private fun guard(label: String, block: () -> Unit) {
        if (!ready) { Prend.log("✗ 내비: 연결 전($label)"); return }
        runCatching(block).onSuccess { Prend.log("· 내비: $label 요청 보냄") }.onFailure { Prend.log("✗ 내비 $label 실패 $it") }
    }

    // 시험용 목적지(정부대전청사)와 경유지(경부선 위 한 지점). 좌표만 맞으면 된다
    fun route() = guard("경로") {
        navi.requestRoute(RouteInfo(latitude = 36.3587, longitude = 127.3845, poiName = "정부대전청사", address = "대전 서구 청사로 189", poiId = "", poiSubId = ""))
    }

    fun waypoint() = guard("경유지") {
        navi.addWaypoint(RequestWaypointInfo(latitude = 36.7716, longitude = 127.2005, poiName = "시험 졸음쉼터", address = "경부고속도로", waypointIndex = WaypointIndex.FIRST, poiId = "", poiSubId = ""))
    }

    fun waypointInfo() = guard("경유지 목록") { navi.getWaypointInfo() }
    fun location() = guard("현재 위치") { navi.getCurrentLocationInfo() }
    fun cancel() = guard("경로 취소") { navi.cancelRoute() }

    fun release() {
        runCatching { navi.removeListener(listener); navi.release() }
        ready = false
    }
}
