// 시연 영상 장면을 차례로 돌린다(녹화는 사람이 화면 녹화 프로그램으로). 조종판(Virtual Console) 신호와 앱 누르기를 한 번에 이어 간다.
// 사용: node demo_video.js            장면 1~4를 이어서(약 4분). 조종판 준비가 끝나면 Enter를 기다린다(그때 녹화를 시작)
//       node demo_video.js 1 3        고른 장면만
//       node demo_video.js nopause    Enter를 기다리지 않고 바로
//       node demo_video.js check 2    확인용: 장면 사이사이 화면을 찍어 둔다(VCSHOTS 폴더, 없으면 tools/vcshots). 찍는 동안 시간이 밀려 누르는 때가 어긋날 수 있다
// 먼저 할 것: 에뮬레이터를 켜고 내비 앱을 한 번 열어 위치를 잡고, `adb forward tcp:8088 tcp:8088`, 시연판 APK(pt)를 깔고 PREND를 연다.
// 찍히는 시각(── 로 시작하는 줄)이 자막 시각표다. 에어백·상담원 통화·고속도로 위치·연속 운전 시간은 가상 차에 값이 없어 앱에 넣는 시험 값이고,
// 그때는 앱 위 막대에 '시연 · …' 표시가 뜬다.
const path = require('path');
const { spawnSync } = require('child_process');

// 누르는 자리(앱 영역 1680×1210 기준)
const YES = 'tap:290:1030';      // 혹시 사고 물음의 [사고가 났어요]
const OK = 'tap:434:250';        // "괜찮으세요?" 띠의 [괜찮아요]
const NEXT = 'tap:1500:1100';    // 안내 화면의 [다음]
const E_ROW = 'tap:840:430';     // 달리는 화면 가운데의 [긴급] 줄
const ILL = 'tap:1100:670';      // 긴급 화면의 [갑자기 아파요]

const SCENES = {
  1: ['일반 도로: 세게 멈춘 뒤 차가 먼저 묻는다(조종판 신호만)', [
    'door:Front left:Close', 'app:pt_e_ready', 'wait:1500', 'app:pt_values_off', 'app:home', 'wait:2500',
    'mark:1 출발', 'D', 'gas:4000', 'wait:1200', 'mark:1 급제동', 'brake:3000', 'wait:1500', 'P', 'wait:800', 'mark:1 운전석 문 열림', 'door:Front left:Open',
    'until:한마디(혹시 사고):20', 'mark:1 혹시 사고 나셨어요?', 'wait:3500', '?shot:1a', YES, 'mark:1 [사고가 났어요]',
    'wait:12000', '?shot:1b', 'wait:7000', NEXT, 'mark:1 [다음]', 'wait:6000', '?shot:1c',
  ]],
  2: ['에어백이 터지고 상담원 통화가 끝난 뒤 대답이 없을 때(에어백·통화는 시험 값)', [
    'door:Front left:Close', 'app:pt_bluelink', 'wait:800', 'app:pt_values_off',
    'mark:2 에어백·상담원 통화', 'wait:6000', '?shot:2a', 'wait:10000', 'mark:2 대답 없음', 'wait:9000', '?shot:2b', 'wait:7000',
  ]],
  3: ['고속도로 위: 대피와 119에 말할 위치(위치만 시험 값)', [
    'door:Front left:Close', 'app:pt_highway', 'wait:1200', 'app:pt_values_off',
    'mark:3 고속도로 출발', 'D', 'gas:6000', 'brake:3000', 'wait:1500', 'P', 'wait:800', 'mark:3 운전석 문 열림', 'door:Front left:Open',
    'wait:8500', '?shot:3a', YES, 'mark:3 [사고가 났어요]', 'wait:3500', '?shot:3b', OK, 'mark:3 [괜찮아요]',
    'wait:16000', '?shot:3c', NEXT, 'wait:6000', '?shot:3d', NEXT, 'mark:3 119에 말할 위치', 'wait:7000', '?shot:3e',
  ]],
  4: ['운전하다 아플 때(조종판 신호와 단추)', [
    'door:Front left:Close', 'app:pt_e_ready', 'wait:1500', 'app:pt_values_off', 'app:home', 'wait:1500',
    'mark:4 달리는 중', 'D', 'gas:2500', 'wait:1500', '?shot:4a', E_ROW, 'mark:4 [긴급]', 'wait:3000', '?shot:4b', ILL, 'mark:4 [갑자기 아파요]',
    'wait:7000', '?shot:4c', 'brake:3000', 'wait:1500', 'P', 'mark:4 세움', 'wait:6000', '?shot:4d', 'wait:19000', '?shot:4e', 'wait:4000',
  ]],
  5: ['평소: 깜빡이를 켠 동안 말을 미뤘다가 끄면 건넨다(연속 운전 시간은 시험 값)', [
    'door:Front left:Close', 'app:pt_swallow', 'wait:1200',
    'mark:5 달리는 중', 'D', 'gas:3000', 'set:Turn Signal State:Blink left', 'mark:5 깜빡이 켬', 'wait:11000', '?shot:5a',
    'set:Turn Signal State:Off', 'mark:5 깜빡이 끔', 'wait:9000', '?shot:5b', 'wait:5000', 'brake:3000', 'wait:1000', 'P',
  ]],
};
const END = ['app:pt_e_ready', 'wait:800', 'app:pt_values_off', 'app:home', 'door:Front left:Close', 'mark:끝'];

const args = process.argv.slice(2);
const check = args.includes('check');
const pick = args.filter((a) => SCENES[a]);
// 기본은 장면 1~4. 장면 5(말 미루기)는 고른 때만: 11/11 확인에서 깜빡이를 끈 뒤 속도가 시속 100을 넘은 것으로 읽혀 쉬어 가라는 말이 나오지 않았다
const order = pick.length ? pick : ['1', '2', '3', '4'];
// 먼저 조종판을 깨워 둔다(페달이 먹을 때까지). 그다음 녹화를 시작할 틈을 준다('nopause'를 붙이면 기다리지 않는다)
let steps = ['door:Front left:Close', 'warm', 'app:pt_e_ready', 'wait:1000', 'app:pt_values_off', 'app:home'];
if (!check && !args.includes('nopause')) steps.push('pause:조종판 준비가 끝났습니다. 화면 녹화를 시작한 뒤 이 창에서 Enter를 누르세요.');
for (const n of order) {
  console.log(`장면 ${n}: ${SCENES[n][0]}`);
  steps.push(...SCENES[n][1]);
}
steps.push(...END);
steps = steps.filter((s) => check || !s.startsWith('?')).map((s) => s.replace(/^\?/, ''));
const r = spawnSync('node', [path.join(__dirname, 'vcride.js'), ...steps], { stdio: 'inherit' });
process.exit(r.status || 0);
