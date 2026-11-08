// 졸음쉼터 좌표 붙이기(개발 2-2단계).
// 재료(공공데이터포털, 한국도로공사, 이용허락범위 제한 없음):
//   1) 졸음쉼터 설치현황_20260801: 노선·이정·방향·명칭(좌표 없음)
//   2) 고속도로 암거 좌표_20260514: 노선·이정·위도·경도(WGS84)
// 방법: 같은 노선의 앞뒤 암거 사이를 이정으로 끼워 맞춰 쉼터의 도로 중심 좌표를 만들고,
//       방향 이름(도시)이 이정이 늘어나는 쪽인지 줄어드는 쪽인지를 그 도시와의 거리로 정한 뒤,
//       진행 방향 오른쪽(우측 통행)으로 35m 옮긴다.
// 출력: app assets/rest/rest_areas.csv, assets/rest/route_points.csv
// 사용: node build_rest.js <쉼터 CSV(UTF-8)> <암거 CSV(UTF-8)> <출력 폴더>
const fs = require('fs');
const path = require('path');
const parse = require('./csv.js');

const [restCsv, culvertCsv, outDir] = process.argv.slice(2);

// 쉼터 자료의 노선 → 암거 자료의 노선(들)
const ROUTE = {
  '수도권제1순환선': ['수도권제1순환선', '수도권제1순환선(판교일산)', '수도권제1순환선(판교퇴계원)'],
  '서해안선': ['서해안선'], '영동선': ['영동선'], '평택제천선': ['평택제천선'], '경부선': ['경부선'],
  '중부선': ['통영대전선,중부선'], '통영대전선': ['통영대전선,중부선'], '제2중부선': ['제2중부선'],
  '중앙선': ['중앙선'], '서울양양선': ['서울양양선'], '동해선': ['동해선'], '중부내륙선': ['중부내륙선'],
  '당진영덕선(청주영덕)': ['서산영덕선(청주상주선)', '서산영덕선(상주영덕선)'],
  '당진영덕선(당진대전)': ['서산영덕선(당진대전선)'], '당진영덕선(당진청주)': ['당진청주선'],
  '호남지선': ['호남선의 지선'], '대전남부순환선': ['대전남부순환선'], '서천공주선': ['서천공주선'],
  '호남선': ['논산천안선,호남선'], '새만금포항선(익산장수)': ['완주장수선'], '광주대구선': ['광주대구선'],
  '고창담양선': ['고창담양선'], '남해선(순천부산)': ['남해선(순천부산)'], '남해선(영암순천)': ['남해선(영암순천)'],
  '대구외곽순환선': ['대구외곽순환선'], '새만금포항선(대구포항)': ['새만금포항선(대구~포항)'],
  '부산외곽순환선': ['부산외곽순환선'], '남해1지선': ['남해제1지선'], '울산선': ['울산선'],
  '수도권제2순환선': ['수도권제2순환선'], '세종포천선': ['세종포천선'],
};

// 방향 이름(도시)의 대략 위치. 이정이 어느 쪽으로 늘어나는지 가리는 데만 쓴다
const CITY = {
  일산: [37.66, 126.77], 판교: [37.39, 127.11], 퇴계원: [37.65, 127.14], 서울: [37.57, 126.98], 목포: [34.81, 126.39],
  인천: [37.46, 126.71], 강릉: [37.75, 128.88], 평택: [36.99, 127.11], 제천: [37.13, 128.19], 부산: [35.18, 129.08],
  통영: [34.85, 128.43], 하남: [37.54, 127.21], 포천: [37.89, 127.2], 구리: [37.59, 127.13], 춘천: [37.88, 127.73],
  양양: [38.08, 128.62], 근덕: [37.36, 129.24], 삼척: [37.45, 129.17], 속초: [38.21, 128.59], 양평: [37.49, 127.49],
  창원: [35.23, 128.68], 청주: [36.64, 127.49], 영덕: [36.42, 129.37], 논산: [36.19, 127.1], 회덕: [36.39, 127.42],
  산내: [36.28, 127.47], 대전: [36.35, 127.38], 서천: [36.08, 126.69], 공주: [36.45, 127.12], 순천: [34.95, 127.49],
  천안: [36.81, 127.15], 익산: [35.95, 126.96], 장수: [35.65, 127.52], 대구: [35.87, 128.6], 광주: [35.16, 126.85],
  고창: [35.44, 126.7], 담양: [35.32, 126.99], 영암: [34.8, 126.7], 달서: [35.83, 128.53], 동대구: [35.88, 128.63],
  포항: [36.02, 129.34], 기장: [35.24, 129.22], 산인: [35.3, 128.42], 언양: [35.56, 129.13], 울산: [35.54, 129.31],
  당진: [36.89, 126.63], 파주: [37.76, 126.78], 양주: [37.79, 127.05], 세종: [36.48, 127.29], 새만금: [35.8, 126.55],
};

const rad = (d) => d * Math.PI / 180;
function dist(a, b) { // m
  const R = 6371000, dl = rad(b[0] - a[0]), dn = rad(b[1] - a[1]);
  const h = Math.sin(dl / 2) ** 2 + Math.cos(rad(a[0])) * Math.cos(rad(b[0])) * Math.sin(dn / 2) ** 2;
  return 2 * R * Math.asin(Math.sqrt(h));
}
function bearing(a, b) {
  const y = Math.sin(rad(b[1] - a[1])) * Math.cos(rad(b[0]));
  const x = Math.cos(rad(a[0])) * Math.sin(rad(b[0])) - Math.sin(rad(a[0])) * Math.cos(rad(b[0])) * Math.cos(rad(b[1] - a[1]));
  return (Math.atan2(y, x) * 180 / Math.PI + 360) % 360;
}
function move(a, brg, m) {
  const R = 6371000, d = m / R, t = rad(brg), p1 = rad(a[0]), l1 = rad(a[1]);
  const p2 = Math.asin(Math.sin(p1) * Math.cos(d) + Math.cos(p1) * Math.sin(d) * Math.cos(t));
  const l2 = l1 + Math.atan2(Math.sin(t) * Math.sin(d) * Math.cos(p1), Math.cos(d) - Math.sin(p1) * Math.sin(p2));
  return [p2 * 180 / Math.PI, l2 * 180 / Math.PI];
}

// 암거: 노선 무리(쉼터 노선 이름)별 (이정, 좌표) 목록
const culverts = parse(fs.readFileSync(culvertCsv, 'utf8')).slice(1).filter((r) => r.length >= 11 && r[9] && r[10]);
const group = {};
for (const [restRoute, names] of Object.entries(ROUTE)) {
  const key = names.join('|');
  if (!group[key]) group[key] = culverts.filter((r) => names.includes(r[4].trim()))
    .map((r) => ({ km: +r[5], p: [+r[9], +r[10]] })).filter((x) => isFinite(x.km) && isFinite(x.p[0]) && isFinite(x.p[1]))
    .sort((a, b) => a.km - b.km);
}

/** 이정 km의 도로 중심 좌표. 앞뒤 암거가 5km 안에 없으면 null */
function at(list, km) {
  let lo = null, hi = null;
  for (const x of list) { if (x.km <= km) lo = x; if (x.km >= km) { hi = x; break; } }
  if (!lo && !hi) return null;
  if (!lo) lo = hi; if (!hi) hi = lo;
  if (Math.abs(km - lo.km) > 5 || Math.abs(hi.km - km) > 5) return null;
  const f = hi.km === lo.km ? 0 : (km - lo.km) / (hi.km - lo.km);
  return [lo.p[0] + (hi.p[0] - lo.p[0]) * f, lo.p[1] + (hi.p[1] - lo.p[1]) * f];
}

const rests = parse(fs.readFileSync(restCsv, 'utf8')).slice(1).filter((r) => r.length >= 6 && r[2]);
const out = [], skipped = [];
for (const r of rests) {
  const [, , routeRaw, kmRaw, dirRaw, nameRaw] = r.map((s) => s.trim());
  const names = ROUTE[routeRaw];
  if (!names) { skipped.push(`${routeRaw} ${nameRaw}: 암거 노선 없음`); continue; }
  const key = names.join('|'), list = group[key], km = +kmRaw;
  const c = at(list, km);
  if (!c) { skipped.push(`${routeRaw} ${kmRaw} ${nameRaw}: 이정 근처 암거 없음`); continue; }
  const city = CITY[dirRaw];
  if (!city) { skipped.push(`${routeRaw} ${nameRaw}: 방향 도시 ${dirRaw} 모름`); continue; }
  // 그 도시 쪽으로 15km 가 보아 더 가까워지는 쪽이 진행 방향
  const ahead = at(list, km + 15) || at(list, km + 8) || at(list, km + 3);
  const behind = at(list, km - 15) || at(list, km - 8) || at(list, km - 3);
  if (!ahead || !behind) { skipped.push(`${routeRaw} ${nameRaw}: 방향 판정 불가(노선 끝)`); continue; }
  const sign = dist(ahead, city) < dist(behind, city) ? 1 : -1;
  const p1 = at(list, km - 0.3 * sign) || c, p2 = at(list, km + 0.3 * sign) || c;
  const travel = bearing(p1, p2);
  const pos = move(c, (travel + 90) % 360, 35);
  out.push({ name: `${nameRaw}졸음쉼터`, route: routeRaw, group: key, dir: dirRaw, km, sign, lat: pos[0], lon: pos[1], travel });
}

fs.mkdirSync(outDir, { recursive: true });
fs.writeFileSync(path.join(outDir, 'rest_areas.csv'),
  'name,route,group,dir,km,sign,lat,lon,travel\n' +
  out.map((o) => [o.name, o.route, o.group.replace(/,/g, '·'), o.dir, o.km, o.sign, o.lat.toFixed(6), o.lon.toFixed(6), o.travel.toFixed(1)].join(',')).join('\n') + '\n');

// 지도 맞추기용 도로 점: 쉼터가 있는 노선 무리만, 0.4km보다 촘촘한 점은 솎는다
const used = [...new Set(out.map((o) => o.group))];
const pts = [];
for (const key of used) {
  let last = -1e9;
  for (const x of group[key]) if (x.km - last >= 0.4) { pts.push([key.replace(/,/g, '·'), x.km, x.p[0].toFixed(6), x.p[1].toFixed(6)]); last = x.km; }
}
fs.writeFileSync(path.join(outDir, 'route_points.csv'), 'group,km,lat,lon\n' + pts.map((p) => p.join(',')).join('\n') + '\n');

console.log(`쉼터 ${out.length}/${rests.length}곳, 도로 점 ${pts.length}개, 노선 무리 ${used.length}`);
console.log('빠진 곳:\n  ' + skipped.join('\n  '));
for (const o of out.filter((o) => o.route === '경부선').slice(0, 40)) console.log(`  ${o.name} ${o.dir}방향 ${o.km}km → ${o.lat.toFixed(4)}, ${o.lon.toFixed(4)} (진행 ${o.travel.toFixed(0)}°, 이정 ${o.sign > 0 ? '늘어나는' : '줄어드는'} 쪽)`);
