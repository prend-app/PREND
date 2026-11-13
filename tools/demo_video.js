// 시연 영상 장면을 차례로 돌린다. 조종판(Virtual Console) 신호와 앱 누르기를 한 번에 이어 간다.
// 녹화와 자막까지 한 번에 하려면 demo_make.js 를 쓴다(이 파일을 부른다). 사람이 직접 녹화할 때만 이 파일을 따로 돌린다.
// 사용: node demo_video.js            장면 1 → 3 → 2 → 6 → 4 → 7 → 0 을 이어서(약 4분). 조종판 준비가 끝나면 Enter를 기다린다(그때 녹화를 시작)
//       node demo_video.js 1 3        고른 장면만(적은 차례대로)
//       node demo_video.js nopause    Enter를 기다리지 않고 바로
//       node demo_video.js check 2    확인용: 장면 사이사이 화면을 찍어 둔다(VCSHOTS 폴더, 없으면 tools/vcshots). 찍는 동안 시간이 밀려 누르는 때가 어긋날 수 있다
// 먼저 할 것: 에뮬레이터를 켜고 내비 앱을 한 번 열어 위치를 잡고, `adb forward tcp:8088 tcp:8088`, 시연판 APK(pt)를 깔고 PREND를 연다.
// 찍히는 시각(── 로 시작하는 줄)이 자막 시각표다. 에어백·상담원 통화·고속도로 위치·연속 운전 시간은 가상 차에 값이 없어 앱에 넣는 시험 값이고,
// 그때는 앱 위 막대에 '시연 · …' 표시가 뜬다. [프렌드]를 누르고 하는 말은 마이크 대신 시험 명령(heard_)으로 넣는다.
// 11/13 고침(외부 검토와 사용자 "더 추가하면 좋을 장면은 없나"): 가장 센 장면(일반 도로)을 맨 앞에, 대답 없이 기다리던 곳은 [괜찮아요]로 바로 잇고,
//   아이를 태운 날(멀미·열)과 깜빡이를 켠 동안 말을 미루는 장면을 더했다. 같은 날 밤: 접촉 사고 장면에 충격(가속도 센서 값)을 넣고, 경고등 장면(7)을 더했다. 에어백 장면은 '대답 없음 → 구조 요청' 대신 '상담원 통화 동안 기다렸다 잇기'만 보인다.
const path = require('path');
const { spawnSync } = require('child_process');

// 누르는 자리(앱 영역 1680×1210 기준)
const YES = 'tap:290:1030';      // 혹시 사고 물음의 [사고가 났어요]
const OK = 'tap:434:250';        // "괜찮으세요?" 띠의 [괜찮아요]
const NEXT = 'tap:1500:1100';    // 안내 화면의 [다음]
const E_ROW = 'tap:840:430';     // 달리는 화면 가운데의 [긴급] 줄
const ILL = 'tap:1100:670';      // 긴급 화면의 [갑자기 아파요]
const FRIEND = 'tap:1271:527';   // 세워 둔 첫 화면의 큰 [프렌드] 단추
// 쉼터 권유 카드의 [괜찮아요]. [경유지로](255, 1016)는 시연에서 누르지 않는다: 가상 차의 내비가 앞으로 나와 '경로를 얻지 못했습니다'를 띄우고,
// 그 뒤로 앱 자리가 검게 나온다(11/13 두 번 확인, 가상 차를 다시 켜야 풀림). 병원 화면의 [달빛어린이병원으로]도 같은 까닭으로 누르지 않는다
const REST_NO = 'tap:504:1016';
const LAMP_ENGINE = 'tap:212:876'; // 경고등 목록 둘째 줄 첫 칸 [엔진]

const SCENES = {
  // 평소: 연속 운전 1시간 59분 50초에서 시작해(시험 값) 10초 뒤 쉬어 가자는 말이 줄에 선다. 그 전에 깜빡이를 켜 두면 말을 삼키고(기록 "말 삼킴: REST(깜빡이)"),
  //   끄면 몇 초 뒤에 건넨다. 깜빡이를 끈 뒤에는 가속 페달을 밟지 않는다(급가속 직후에는 하던 말도 멈춘다)
  0: ['평소: 깜빡이를 켠 동안은 말을 미뤘다가, 끈 뒤에 쉬어 가자고 권한다(연속 운전 시간과 고속도로 위치는 시험 값)', [
    'door:Front left:Close', 'app:pt_e_ready', 'wait:1500', 'app:pt_swallow', 'wait:1200',
    'mark:0 달리는 중(운전 1시간 59분 50초)', 'D', 'gas:3200', 'wait:1500', 'set:Turn Signal State:Blink left', 'mark:0 깜빡이 켬 · 하려던 말을 미룸',
    'wait:2500', 'gas:900', 'wait:4500', 'gas:900', 'wait:4000', '?shot:0a', 'set:Turn Signal State:Off', 'mark:0 깜빡이 끔',
    'until:한마디(쉼터):25', 'mark:0 쉬어 가실래요?', 'wait:6500', '?shot:0b', REST_NO, 'mark:0 [괜찮아요]', 'wait:3000',
    'brake:3000', 'wait:1200', 'P', 'wait:800',
  ]],
  1: ['일반 도로: 접촉 사고가 났을 때 차가 먼저 묻는다(조종판 신호와 가속도 센서 값만)', [
    'door:Front left:Close', 'app:pt_e_ready', 'wait:1500', 'app:pt_values_off', 'app:home', 'wait:2500',
    'mark:1 출발', 'D', 'gas:4000', 'wait:1200', 'mark:1 급제동', 'brake:1200', 'mark:1 충격(가속도 센서)', 'jolt:1.5', 'brake:2500', 'wait:800', 'P', 'wait:800', 'mark:1 운전석 문 열림', 'door:Front left:Open',
    'until:한마디(혹시 사고):20', 'mark:1 혹시 사고 나셨어요?', 'wait:3500', '?shot:1a', YES, 'mark:1 [사고가 났어요]',
    // [괜찮아요]면 가벼운 사고 순서(비상등 → 사진 → 길가로 → 보험사 …)를 차가 한 문장씩 읽으며 화면을 넘긴다. [다음]은 누르지 않고,
    //   보험사 이름이 늘어선 걸음(넷째)이 나오기 전에 장면을 끝낸다
    'wait:3500', OK, 'mark:1 [괜찮아요]', 'until:e_mn_2:12', 'mark:1 옮기기 전에 사진부터', '?shot:1b', 'until:e_mn_3:14', 'mark:1 차를 길가로', 'wait:4500', '?shot:1c',
  ]],
  // 에어백(시험 값)과 상담원 통화(시험 값, 8초): 통화 동안 말하지 않고 기다렸다가, 끝나면 "괜찮으세요?"부터 잇는다. 대답 없이 두지 않고 [괜찮아요]로 잇는다
  2: ['에어백이 터진 뒤: 상담원 통화가 끝나길 기다렸다가 이어서 안내한다(에어백·통화는 시험 값)', [
    'door:Front left:Close', 'app:pt_bluelink', 'wait:800', 'app:pt_values_off',
    'mark:2 에어백 · 상담원 통화 중(말하지 않고 기다림)', 'wait:5000', '?shot:2a', 'until:e_ok:12', 'mark:2 통화가 끝나자 "괜찮으세요?"', 'wait:1800', OK, 'mark:2 [괜찮아요]',
    'wait:9000', '?shot:2b',
  ]],
  // 고속도로에서는 물음 앞에 대피 문장이 붙는다(9.8초): 그 말이 끝난 뒤에 누른다(11/13 검토: 앞 판은 말이 끝나기 전에 눌러 말이 잘렸다).
  //   [괜찮아요] 뒤로는 차가 한 문장씩 읽으며 화면을 넘긴다(비상등 → 가드레일 밖 → 119). 119 걸음 뒤에는 운전자가 내리기를 기다리므로 견인 걸음은 [다음]으로 넘겨 보인다(사람이 넘긴 걸음은 읽지 않는다)
  3: ['고속도로 위: 대피 순서와 119·견인에 말할 위치(위치만 시험 값)', [
    'door:Front left:Close', 'app:pt_highway', 'wait:1200', 'app:pt_values_off',
    'mark:3 고속도로 출발', 'D', 'gas:6000', 'mark:3 급제동', 'brake:1200', 'mark:3 충격(가속도 센서)', 'jolt:1.5', 'brake:3000', 'wait:800', 'P', 'wait:800', 'mark:3 운전석 문 열림', 'door:Front left:Open',
    'mark:3 고속도로: 대피부터 말하고 묻습니다', 'until:한마디(혹시 사고) 끝:25', 'wait:500', '?shot:3a', YES, 'mark:3 [사고가 났어요]', 'wait:3500', '?shot:3b', OK, 'mark:3 [괜찮아요]',
    'wait:900', 'mark:3 비상등을 켜세요', 'until:말하기: e_hw_2:10', 'mark:3 차 뒤로 가지 말고 가드레일 밖으로', '?shot:3c', 'until:말하기: e_hw_3:16', 'mark:3 119에 말할 위치', 'wait:6000', '?shot:3d',
    NEXT, 'mark:3 [다음] 견인 1588-2504', 'wait:5000', '?shot:3e',
  ]],
  4: ['운전하다 아플 때(조종판 신호와 단추)', [
    'door:Front left:Close', 'app:pt_e_ready', 'wait:1500', 'app:pt_values_off', 'app:home', 'wait:1500',
    'mark:4 달리는 중', 'D', 'gas:2500', 'wait:1500', '?shot:4a', E_ROW, 'mark:4 [긴급]', 'wait:3000', '?shot:4b', ILL, 'mark:4 [갑자기 아파요]',
    'wait:7000', '?shot:4c', 'brake:3000', 'wait:1500', 'P', 'mark:4 세움', 'wait:6500', '?shot:4d',
  ]],
  // 아이를 태운 날: [프렌드]를 누르고 한마디. 말은 마이크 대신 시험 명령으로 넣는다(빈칸 없이 적는다. 앱은 빈칸을 지우고 알아듣는다)
  6: ['아이를 태운 날: 한마디면 공조를 바꾸고, 아플 때는 지금 여는 병원을 찾는다(말은 시험 명령으로 넣음)', [
    'door:Front left:Close', 'app:pt_e_ready', 'wait:1500', 'app:pt_values_off', 'app:home', 'wait:2500',
    'mark:6 [프렌드]', FRIEND, 'wait:1500', 'app:heard_애가멀미해', 'mark:6 "애가 멀미해"', 'wait:8000', '?shot:6a',
    'mark:6 [프렌드]', FRIEND, 'wait:1500', 'app:heard_아이가열이나', 'mark:6 "아이가 열이 나"', 'until:e_ill_moon:25', 'mark:6 지금 여는 달빛어린이병원', 'wait:5200', '?shot:6b',
    'app:home', 'wait:1500',
  ]],
  // 초보 운전: 경고등이 켜졌을 때. 앱은 어떤 경고등이 켜졌는지 읽지 못한다(일반 앱에 주지 않는 값). 운전자가 같은 모양을 고르면 뜻과 할 일을 보인다
  7: ['초보 운전: 경고등이 켜졌을 때 뜻과 할 일을 보여 준다(말은 시험 명령으로 넣음)', [
    'door:Front left:Close', 'app:pt_e_ready', 'wait:1500', 'app:pt_values_off', 'app:home', 'wait:2500',
    'mark:7 [프렌드]', FRIEND, 'wait:1500', 'app:heard_경고등켜졌어', 'mark:7 "경고등 켜졌어"', 'wait:6500', '?shot:7a',
    LAMP_ENGINE, 'mark:7 켜진 것과 같은 모양을 고름', 'wait:7500', '?shot:7b', 'app:home', 'wait:1500',
  ]],
};
const END = ['app:pt_e_ready', 'wait:800', 'app:pt_values_off', 'app:home', 'door:Front left:Close', 'mark:끝'];

const args = process.argv.slice(2);
const check = args.includes('check');
const pick = args.filter((a) => SCENES[a]);
// 기본 차례: 가장 센 장면(조종판 신호만으로 차가 먼저 묻는다)을 맨 앞에, 평소 장면을 끝에
const order = pick.length ? pick : ['1', '3', '2', '6', '4', '7', '0'];
// 먼저 조종판을 깨워 둔다(페달이 먹을 때까지). 그다음 녹화를 시작할 틈을 준다('nopause'를 붙이면 기다리지 않는다)
let steps = ['door:Front left:Close', 'warm', 'app:pt_e_ready', 'wait:1000', 'app:pt_values_off', 'app:home'];
// 병원 찾기 미리 한 번(영상에는 들어가지 않는 앞부분): 가상 차는 녹화하는 동안 한참 만의 첫 인터넷 요청이 5초를 넘겨, 병원 목록이 실시간 자료 대신
//   앱 안 목록으로 나오곤 했다(11/13 밤 두 번, 서버는 PC에서 0.3초에 답함). 장면 전에 두 번 불러 연결을 열어 둔다
const PREFLIGHT = ['wait:1500', FRIEND, 'wait:1500', 'app:heard_아이가열이나', 'until:병원:12', 'wait:800', 'app:pt_e_ready', 'wait:1000', 'app:pt_values_off', 'app:home'];
if (order.includes('6') || order.includes('4')) steps.push(...PREFLIGHT, ...PREFLIGHT, 'wait:1500');
if (!check && !args.includes('nopause')) steps.push('pause:조종판 준비가 끝났습니다. 화면 녹화를 시작한 뒤 이 창에서 Enter를 누르세요.');
for (const n of order) {
  console.log(`장면 ${n}: ${SCENES[n][0]}`);
  steps.push(...SCENES[n][1]);
}
steps.push(...END);
steps = steps.filter((s) => check || !s.startsWith('?')).map((s) => s.replace(/^\?/, ''));
const r = spawnSync('node', [path.join(__dirname, 'vcride.js'), ...steps], { stdio: 'inherit' });
process.exit(r.status || 0);
