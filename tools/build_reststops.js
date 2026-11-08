// 고속도로 휴게소 붙이기(11/02, 쉼터에 휴게소 더하기).
// 재료: 공공데이터포털 전국휴게소정보표준데이터의 원본 '한국도로공사_휴게소정보'(기준 2026-09-17, 215곳, 이용허락범위 제한 없음,
//       https://www.data.go.kr/data/15025446/standard.do). 위도·경도·주유소·LPG·전기차 충전소·수유실 유무가 들어 있다.
// 방법: 앱의 고속도로 도로 점(assets/rest/route_points.csv)에서 가장 가까운 점으로 노선 무리·이정을 얻고(1.5km 안만),
//       휴게소명 괄호 속 도시(진행 방향)가 이정이 늘어나는 쪽인지 줄어드는 쪽인지를 그 도시와의 거리로 정한다(build_rest.js와 같은 방법).
//       '양방향' 휴게소는 두 방향 모두에 넣는다. 도로 점과 멀거나 방향 도시를 모르면 뺀다(목록을 찍는다).
// 출력: assets/rest/rest_stops.csv (name,route,group,dir,km,sign,lat,lon,travel,fuel,lpg,ev,nursing)
//       nursing(수유실 유무, 11/07 화장실 찾기)은 맨 끝에 더했다. 앱이 앞 12칸을 자리로 읽으니 순서를 바꾸지 않는다
// 사용: node build_reststops.js <휴게소 CSV(UTF-8)> <앱 assets/rest 폴더>
const fs = require('fs');
const path = require('path');
const parse = require('./csv.js');

const [stopCsv, restDir] = process.argv.slice(2);

const CITY = {
  일산: [37.66, 126.77], 판교: [37.39, 127.11], 퇴계원: [37.65, 127.14], 서울: [37.57, 126.98], 목포: [34.81, 126.39],
  인천: [37.46, 126.71], 강릉: [37.75, 128.88], 평택: [36.99, 127.11], 제천: [37.13, 128.19], 부산: [35.18, 129.08],
  통영: [34.85, 128.43], 하남: [37.54, 127.21], 포천: [37.89, 127.2], 구리: [37.59, 127.13], 춘천: [37.88, 127.73],
  양양: [38.08, 128.62], 삼척: [37.45, 129.17], 속초: [38.21, 128.59], 양평: [37.49, 127.49], 창원: [35.23, 128.68],
  청주: [36.64, 127.49], 영덕: [36.42, 129.37], 논산: [36.19, 127.1], 대전: [36.35, 127.38], 서천: [36.08, 126.69],
  공주: [36.45, 127.12], 순천: [34.95, 127.49], 천안: [36.81, 127.15], 익산: [35.95, 126.96], 장수: [35.65, 127.52],
  대구: [35.87, 128.6], 광주: [35.16, 126.85], 고창: [35.44, 126.7], 담양: [35.32, 126.99], 영암: [34.8, 126.7],
  포항: [36.02, 129.34], 울산: [35.54, 129.31], 당진: [36.89, 126.63], 파주: [37.76, 126.78], 양주: [37.79, 127.05],
  세종: [36.48, 127.29], 마산: [35.21, 128.58], 진주: [35.18, 128.11], 김해: [35.23, 128.88], 상주: [36.41, 128.16],
  안동: [36.57, 128.73], 원주: [37.34, 127.92], 여주: [37.3, 127.64], 충주: [36.99, 127.93], 김천: [36.14, 128.11],
  전주: [35.82, 127.15], 군산: [35.97, 126.74], 무안: [34.99, 126.48], 함양: [35.52, 127.73], 거창: [35.69, 127.91],
  남원: [35.42, 127.39], 경주: [35.86, 129.22], 동해: [37.52, 129.11], 홍천: [37.69, 127.89], 아산: [36.79, 127.0],
  시흥: [37.38, 126.8], 안성: [37.01, 127.28], 이천: [37.27, 127.44], 용인: [37.24, 127.18], 광명: [37.48, 126.86],
  수원: [37.26, 127.03], 화성: [37.2, 126.83], 서산: [36.78, 126.45], 보령: [36.33, 126.61], 군위: [36.24, 128.57],
  청송: [36.44, 129.06], 영천: [35.97, 128.94], 밀양: [35.5, 128.75], 양산: [35.34, 129.04], 창녕: [35.54, 128.49],
  고령: [35.73, 128.26], 성주: [35.92, 128.28], 칠곡: [35.99, 128.4], 구미: [36.12, 128.34], 함안: [35.27, 128.41],
  사천: [35.0, 128.06], 광양: [34.94, 127.7], 보성: [34.77, 127.08], 해남: [34.57, 126.6], 강진: [34.64, 126.77],
  완주: [35.9, 127.16], 무주: [36.01, 127.66], 영동: [36.18, 127.78], 옥천: [36.31, 127.57], 보은: [36.49, 127.73],
  음성: [36.94, 127.69], 진천: [36.86, 127.44], 괴산: [36.82, 127.79], 문경: [36.59, 128.19], 예천: [36.66, 128.45],
  영주: [36.81, 128.62], 단양: [36.98, 128.37], 횡성: [37.49, 127.98], 평창: [37.37, 128.39], 정선: [37.38, 128.66],
  인제: [38.07, 128.17], 가평: [37.83, 127.51], 청평: [37.73, 127.42], 남양주: [37.64, 127.22], 의정부: [37.74, 127.03],
  김포: [37.62, 126.72], 고양: [37.66, 126.83], 부천: [37.5, 126.78], 안산: [37.32, 126.83], 오산: [37.15, 127.07],
  당진시: [36.89, 126.63], 예산: [36.68, 126.85], 홍성: [36.6, 126.66], 부여: [36.28, 126.91], 정읍: [35.57, 126.86],
  장성: [35.3, 126.78], 나주: [35.02, 126.71], 함평: [35.07, 126.52], 영광: [35.28, 126.51], 곡성: [35.28, 127.29],
  남이: [36.56, 127.45], 서부산: [35.15, 128.98], 구례: [35.2, 127.46], 하동: [35.07, 127.75], 산청: [35.42, 127.87], 의령: [35.32, 128.26], 합천: [35.57, 128.17],
};

const rad = (d) => d * Math.PI / 180;
function dist(a, b) {
  const R = 6371000, dl = rad(b[0] - a[0]), dn = rad(b[1] - a[1]);
  const h = Math.sin(dl / 2) ** 2 + Math.cos(rad(a[0])) * Math.cos(rad(b[0])) * Math.sin(dn / 2) ** 2;
  return 2 * R * Math.asin(Math.sqrt(h));
}
function bearing(a, b) {
  const y = Math.sin(rad(b[1] - a[1])) * Math.cos(rad(b[0]));
  const x = Math.cos(rad(a[0])) * Math.sin(rad(b[0])) - Math.sin(rad(a[0])) * Math.cos(rad(b[0])) * Math.cos(rad(b[1] - a[1]));
  return (Math.atan2(y, x) * 180 / Math.PI + 360) % 360;
}

// 앱의 도로 점: 무리별 (이정, 좌표)
const pts = fs.readFileSync(path.join(restDir, 'route_points.csv'), 'utf8').trim().split('\n').slice(1).map((l) => {
  const f = l.split(','); return { group: f[0], km: +f[1], p: [+f[2], +f[3]] };
});
const byGroup = {};
for (const x of pts) (byGroup[x.group] = byGroup[x.group] || []).push(x);
for (const g of Object.values(byGroup)) g.sort((a, b) => a.km - b.km);
function at(list, km) {
  let lo = null, hi = null;
  for (const x of list) { if (x.km <= km) lo = x; if (x.km >= km) { hi = x; break; } }
  if (!lo && !hi) return null;
  if (!lo) lo = hi; if (!hi) hi = lo;
  if (Math.abs(km - lo.km) > 5 || Math.abs(hi.km - km) > 5) return null;
  const f = hi.km === lo.km ? 0 : (km - lo.km) / (hi.km - lo.km);
  return [lo.p[0] + (hi.p[0] - lo.p[0]) * f, lo.p[1] + (hi.p[1] - lo.p[1]) * f];
}

const rows = parse(fs.readFileSync(stopCsv, 'utf8').replace(/^﻿/, ''));
const head = rows[0].map((s) => s.trim());
const col = (n) => head.indexOf(n);
const I = { name: col('휴게소명'), route: col('도로노선명'), dir: col('도로노선방향'), lat: col('위도'), lon: col('경도'), fuel: col('주유소유무'), lpg: col('LPG충전소유무'), ev: col('전기차충전소유무'), nursing: col('수유실유무') };
if (I.nursing < 0) throw new Error("휴게소 CSV에 '수유실유무' 칸이 없음");
const out = [], skipped = [];
for (const r of rows.slice(1)) {
  if (r.length < head.length - 3) continue;
  const raw = r[I.name].trim();
  const p = [+r[I.lat], +r[I.lon]];
  if (!isFinite(p[0]) || !isFinite(p[1])) { skipped.push(`${raw}: 좌표 없음`); continue; }
  // 가장 가까운 도로 점(1.5km 안)
  let best = null, bd = Infinity;
  for (const x of pts) { const d = dist(p, x.p); if (d < bd) { bd = d; best = x; } }
  if (!best || bd > 1500) { skipped.push(`${raw}: 도로 점과 ${Math.round(bd)}m`); continue; }
  const m = raw.match(/^(.*?)\((.*)\)\s*$/);
  const base = (m ? m[1] : raw).trim();
  const city = m ? m[2].trim() : '';
  const both = r[I.dir].trim() === '양방향';
  const list = byGroup[best.group];
  const ahead = at(list, best.km + 1), behind = at(list, best.km - 1);
  let signs;
  if (both) signs = [1, -1];
  else {
    const c = CITY[city];
    if (!c || !ahead || !behind || city === base) { skipped.push(`${raw}: 방향 도시 '${city}' 모름`); continue; }
    signs = [dist(ahead, c) < dist(behind, c) ? 1 : -1];
  }
  for (const sign of signs) {
    const p1 = at(list, best.km - 0.3 * sign) || best.p, p2 = at(list, best.km + 0.3 * sign) || best.p;
    out.push({ name: `${base}휴게소`, route: r[I.route].trim(), group: best.group, dir: both ? '양방향' : city, km: best.km, sign, lat: p[0], lon: p[1], travel: bearing(p1, p2),
      fuel: r[I.fuel].trim() === 'Y' ? 1 : 0, lpg: r[I.lpg].trim() === 'Y' ? 1 : 0, ev: r[I.ev].trim() === 'Y' ? 1 : 0, nursing: r[I.nursing].trim() === 'Y' ? 1 : 0 });
  }
}
fs.writeFileSync(path.join(restDir, 'rest_stops.csv'),
  'name,route,group,dir,km,sign,lat,lon,travel,fuel,lpg,ev,nursing\n' +
  out.map((o) => [o.name, o.route, o.group, o.dir, o.km, o.sign, o.lat.toFixed(6), o.lon.toFixed(6), o.travel.toFixed(1), o.fuel, o.lpg, o.ev, o.nursing].join(',')).join('\n') + '\n');
console.log(`휴게소 ${out.length}줄(양방향은 두 줄), 뺀 곳 ${skipped.length}`);
for (const s of skipped) console.log('  뺌: ' + s);
