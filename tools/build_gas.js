// 주유소·LPG 충전소 화장실 자리(11/07, 일반 도로에서 "화장실 급해").
// 주유소는 등록할 때 공중화장실을 한 곳 이상 두어야 한다(석유 및 석유대체연료 사업법 시행령 별표 2).
// 재료(공공데이터포털, 이용허락범위 제한 없음, 로그인 없이 받음):
//   1) 전국주유소표준데이터(https://www.data.go.kr/data/15129441/standard.do): 주유소명·시도·시군구·도로명주소·위도·경도(WGS84).
//      지방자치단체가 올린 것만 모은 자료라 전국의 절반쯤만 있다. 영업 상태·폐업 칸은 없고, 이름에 '(휴업)'처럼 적힌 곳이 있다.
//      포털의 JSON(배열 또는 {records})이나 CSV(머리줄 주유소명·위도·경도, UTF-8 또는 EUC-KR) 모두 읽는다.
//   2) 한국가스안전공사_전국 LPG 충전소 현황(https://www.data.go.kr/data/15001643/fileData.do): 업소명·관리구분·위도·경도(WGS84).
//      관리구분에 '자동차'가 든 곳만 쓴다. 따옴표 안에 쉼표가 든 칸("용기, 자동차")이 있어 csv.js로 읽는다.
// 거르는 것:
//   - 좌표가 비거나 한국 밖(위도 33~39, 경도 124~132)
//   - 주유소: 이름에 휴업·폐업·목록에서 삭제가 적힌 곳, '석유판매소'처럼 판매소(일반판매소는 화장실 의무가 없다)인 곳,
//     주소에 '공군내'처럼 군부대 안이라고 적힌 곳. 영업 상태 칸(영업상태명 등)이나 폐업일자 칸이 있는 자료면 그것으로도 거른다
//   - LPG: 자동차 충전이 아닌 곳(용기·탱크로리만), 이름으로 보아 자동차 공장·연구소·가스 기지·운수 회사 안의 전용 충전소
//     (자료에 사업장 전용을 가리는 칸이 없어 이름으로만 가린다)
//   - 같은 곳 두 번: 30m 안에 이름이 비슷하면 하나만(주유소 이름을 남기고 kind는 gas). 방향(괄호 속 말·○○방향·상행/하행)이나
//     지점 번호(제2 등)가 다르면 다른 곳으로 본다. 도로명주소가 뚜렷이 다르면(같은 자료 안은 도로명+건물번호가 다르면,
//     주유소↔LPG는 다른 도로이거나 건물번호가 20 넘게 차이 나면) 다른 곳(좌표가 똑같이 찍힌 이웃 주유소가 있다)
// 출력: assets/rest/gas_stations.csv (name,lat,lon,kind) kind는 gas 또는 lpg. 이름의 쉼표·따옴표는 공백으로 바꾼다(앱이 쉼표로 자른다)
// 사용: node build_gas.js <주유소 표준데이터(JSON 또는 CSV)> <LPG 충전소 CSV> <앱 assets/rest 폴더>
const fs = require('fs');
const path = require('path');
const parse = require('./csv.js');

const [gasFile, lpgFile, restDir] = process.argv.slice(2);
if (!gasFile || !lpgFile || !restDir) {
  console.error('사용: node build_gas.js <주유소 표준데이터(JSON 또는 CSV)> <LPG 충전소 CSV> <앱 assets/rest 폴더>');
  process.exit(1);
}

/** UTF-8이면 그대로, 아니면 EUC-KR(공공데이터포털 CSV의 흔한 글자 체계)로 읽는다 */
function readText(file) {
  const buf = fs.readFileSync(file);
  let text;
  try { text = new TextDecoder('utf-8', { fatal: true }).decode(buf); } catch (e) { text = new TextDecoder('euc-kr').decode(buf); }
  return text.replace(/^﻿/, '');
}

/** CSV를 머리줄(이 이름 중 하나가 든 줄)부터 {머리: 값} 목록으로 */
function csvRecords(text, mustHave) {
  const rows = parse(text);
  const h = rows.findIndex((r) => r.some((c) => mustHave.includes(c.trim())));
  if (h < 0) throw new Error(`머리줄(${mustHave.join('/')})을 찾지 못함`);
  const head = rows[h].map((s) => s.trim());
  return rows.slice(h + 1).filter((r) => r.length >= head.length - 2 && r.some((c) => c.trim()))
    .map((r) => Object.fromEntries(head.map((k, i) => [k, (r[i] || '').trim()])));
}

function pick(rec, keys) { for (const k of keys) if (rec[k] !== undefined && rec[k] !== null) return String(rec[k]).trim(); return ''; }

const inKorea = (lat, lon) => isFinite(lat) && isFinite(lon) && lat >= 33 && lat <= 39 && lon >= 124 && lon <= 132;
const cleanName = (s) => s.replace(/[,"\r\n\t]/g, ' ').replace(/\s+/g, ' ').trim();

const skipped = {}; // 까닭 → [이름]
function skip(why, name) { (skipped[why] = skipped[why] || []).push(name); }

// ---- 1) 주유소
const gasText = readText(gasFile);
let gasRecs;
if (/^\s*[[{]/.test(gasText)) {
  const j = JSON.parse(gasText);
  gasRecs = Array.isArray(j) ? j : (j.records || j.data || []);
} else gasRecs = csvRecords(gasText, ['주유소명']);
const gas = [];
for (const r of gasRecs) {
  const name = cleanName(pick(r, ['CONM_NM', '주유소명']));
  const lat = parseFloat(pick(r, ['LAT', '위도'])), lon = parseFloat(pick(r, ['LOT', '경도']));
  const status = pick(r, ['영업상태명', '영업상태', '상세영업상태명', 'BSN_STTS_NM']);
  const closed = pick(r, ['폐업일자', 'CLSBIZ_YMD']);
  if (!name) { skip('이름 없음', '(이름 없음)'); continue; }
  if (status && !/영업|정상/.test(status)) { skip('영업 상태가 영업 아님', `${name}(${status})`); continue; }
  if (closed) { skip('폐업일자 있음', name); continue; }
  if (/휴업|폐업|폐쇄|목록에서 삭제/.test(name)) { skip('이름에 휴업·폐업 표시', name); continue; }
  if (/판매소/.test(name) && !/주유/.test(name)) { skip('석유판매소(일반판매소)', name); continue; }
  const addr = pick(r, ['LCTN_ROAD_NM', '소재지도로명주소']) + ' ' + pick(r, ['LCTN_LOTNO_ADDR', '소재지지번주소']);
  if (/공군내|육군내|해군내|군부대|부대 ?안/.test(name + ' ' + addr)) { skip('군부대 안', `${name}(${addr.trim()})`); continue; }
  if (!inKorea(lat, lon)) { skip('좌표 없음·한국 밖', name); continue; }
  gas.push({ name, lat, lon, kind: 'gas', src: 'gas', addr: roadKey(pick(r, ['LCTN_ROAD_NM', '소재지도로명주소'])),
    sido: pick(r, ['CTPV_NM', '시도명']), sgg: pick(r, ['SGG_NM', '시군구명']), road: pick(r, ['LCTN_ROAD_NM', '소재지도로명주소']) });
}

// ---- 2) LPG 충전소
const lpgRecs = csvRecords(readText(lpgFile), ['업소명', '충전소명']);
// 자동차 회사 공장·연구소, 가스 기지·터미널, 운수 회사 안의 충전소(일반 차가 넣는 곳이 아님). 이름에 '충전소'가 있으면 일반 충전소로 둔다
const PRIVATE = /^(현대자동차|기아|르노코리아|한국지엠|지엠코리아|KG모빌리티|쌍용자동차|타타대우)|공장|연구소|기지$|엘엔지터미널|운수|여객/;
const lpg = [];
for (const r of lpgRecs) {
  const name = cleanName(pick(r, ['업소명', '충전소명']));
  const lat = parseFloat(pick(r, ['위도'])), lon = parseFloat(pick(r, ['경도']));
  const use = pick(r, ['관리구분']);
  if (!name) { skip('이름 없음', '(이름 없음)'); continue; }
  if (!use.includes('자동차')) { skip('LPG 자동차 충전 아님', `${name}(${use})`); continue; }
  if (PRIVATE.test(name) && !/충전소/.test(name)) { skip('LPG 공장·사업장 전용', `${name}(${pick(r, ['행정구역'])})`); continue; }
  if (!inKorea(lat, lon)) { skip('좌표 없음·한국 밖', name); continue; }
  lpg.push({ name, lat, lon, kind: 'lpg', src: 'lpg', addr: roadKey(pick(r, ['주소'])), sido: pick(r, ['행정구역']).split(' ')[0], sgg: '', road: pick(r, ['주소']) });
}

// ---- 3) 같은 곳 하나로
const rad = (d) => d * Math.PI / 180;
function dist(a, b) {
  const R = 6371000, dl = rad(b.lat - a.lat), dn = rad(b.lon - a.lon);
  const h = Math.sin(dl / 2) ** 2 + Math.cos(rad(a.lat)) * Math.cos(rad(b.lat)) * Math.sin(dn / 2) ** 2;
  return 2 * R * Math.asin(Math.sqrt(h));
}
/** 이름의 줄기(회사 꼴·주유소·충전소 같은 말을 뺀 것), 방향(괄호 속 말·'○○방향'·상행/하행), 숫자(제2·2호 같은 지점 번호) */
function core(s) {
  s = s.replace(/\(주\)|㈜|주식회사|\(유\)|유한회사|\(자\)|합자회사|\(합\)|합명회사/g, '');
  const dirs = new Set();
  for (const x of s.match(/\([^()]*\)/g) || []) {
    const t = x.slice(1, -1).replace(/방향|방면|\s/g, '');
    if (t && !/^구[:：]|^신규$|^(주유소|충전소|셀프|LPG)$/i.test(t)) dirs.add(t === '상행' ? '상' : t === '하행' ? '하' : t);
  }
  const out = s.replace(/\([^()]*\)/g, ' ');
  for (const m of out.matchAll(/([가-힣]{2})(방향|방면)/g)) dirs.add(m[1]);
  for (const m of out.matchAll(/(상|하)행/g)) dirs.add(m[1]);
  const base = out.replace(/[가-힣]{2}(방향|방면)|[상하]행/g, '').replace(/주유소|충전소|엘피지|LPG|셀프|self|직영/gi, '').replace(/[^0-9A-Za-z가-힣]/g, '');
  return { base, dirs: [...dirs].sort().join('|'), digits: (base.match(/\d+/g) || []).join('|') };
}
function bigrams(s) { const g = new Set(); for (let i = 0; i < s.length - 1; i++) g.add(s.slice(i, i + 2)); return g; }
/** 이름이 같은 곳을 가리키는가. sameAddr면(같은 자료 안에서 도로명주소가 같으면) 지점 번호는 보지 않는다 */
function similar(a, b, sameAddr) {
  const x = core(a), y = core(b);
  if (x.dirs && y.dirs && x.dirs !== y.dirs) return false;
  if (!sameAddr && x.digits !== y.digits) return false;
  if (!x.base || !y.base) return a === b;
  if (x.base === y.base) return true;
  const [p, q] = x.base.length <= y.base.length ? [x.base, y.base] : [y.base, x.base];
  if (p.length >= 2 && q.includes(p)) return true;
  const A = bigrams(x.base), B = bigrams(y.base);
  let n = 0; for (const g of A) if (B.has(g)) n++;
  return A.size + B.size > 0 && 2 * n / (A.size + B.size) >= 0.6;
}
/** 도로명주소에서 '도로명+건물번호'(공백 없이). 못 찾으면 '' */
function roadKey(addr) {
  const m = [...addr.replace(/\([^()]*\)/g, ' ').matchAll(/([가-힣A-Za-z0-9·.]+(?:로|길))\s*(\d+(?:-\d+)?)/g)];
  return m.length ? m[m.length - 1][1] + m[m.length - 1][2] : '';
}
/**
 * 두 주소가 한자리일 수 있는가. 같은 자료 안이면 도로명+건물번호가 같아야 하고(모르면 된다고 본다),
 * 주유소↔LPG면 같은 도로에 건물번호 차이 20 안(약 200m, 겸업은 번지가 조금 다르기도 하다)
 */
function addrOk(a, b, sameSet) {
  if (!a || !b) return true;
  if (sameSet) return a === b;
  const x = a.match(/^(.*?)(\d+)(?:-\d+)?$/), y = b.match(/^(.*?)(\d+)(?:-\d+)?$/);
  return !!x && !!y && x[1] === y[1] && Math.abs(+x[2] - +y[2]) <= 20;
}
const SAME_M = 30;
const grid = new Map(); // 0.001° 칸 → 남긴 곳
const cell = (lat, lon) => `${Math.floor(lat * 1000)},${Math.floor(lon * 1000)}`;
const kept = [], merged = [];
const near = []; // 30m 밖 150m 안의 비슷한 이름(주유소↔LPG), 알림용
for (const s of [...gas, ...lpg]) {
  const ci = Math.floor(s.lat * 1000), cj = Math.floor(s.lon * 1000);
  let same = null;
  for (let di = -2; di <= 2 && !same; di++) for (let dj = -2; dj <= 2 && !same; dj++) {
    for (const k of grid.get(`${ci + di},${cj + dj}`) || []) {
      const d = dist(s, k);
      if (d > 150) continue;
      // 주소가 뚜렷이 다르면 다른 곳(좌표가 똑같이 찍힌 이웃 주유소가 있다)
      const sameSet = s.src === k.src;
      if (!addrOk(s.addr, k.addr, sameSet)) continue;
      const ok = similar(s.name, k.name, sameSet && s.addr && s.addr === k.addr);
      if (d <= SAME_M && ok) { same = k; break; }
      if (ok && !sameSet && d > SAME_M) near.push(`${k.name} ↔ ${s.name} ${Math.round(d)}m`);
    }
  }
  if (same) {
    merged.push(`${same.name}(${same.kind}, ${same.addr}) ← ${s.name}(${s.kind}, ${s.addr})`);
    if (s.kind === 'gas') same.kind = 'gas';
    continue;
  }
  kept.push(s);
  const key = cell(s.lat, s.lon);
  if (!grid.has(key)) grid.set(key, []);
  grid.get(key).push(s);
}

kept.sort((a, b) => a.lat - b.lat || a.lon - b.lon);
const outFile = path.join(restDir, 'gas_stations.csv');
fs.writeFileSync(outFile, 'name,lat,lon,kind\n' + kept.map((s) => [s.name, s.lat.toFixed(6), s.lon.toFixed(6), s.kind].join(',')).join('\n') + '\n');

// ---- 알림
const nGas = kept.filter((s) => s.kind === 'gas').length, nLpg = kept.length - nGas;
console.log(`원본: 주유소 ${gasRecs.length}곳, LPG 충전소 ${lpgRecs.length}곳`);
console.log(`남김: ${kept.length}곳(gas ${nGas}, lpg ${nLpg}) → ${outFile} ${fs.statSync(outFile).size} bytes`);
for (const [why, list] of Object.entries(skipped)) {
  console.log(`뺌(${why}): ${list.length}`);
  if (list.length <= 40) for (const n of list) console.log('  ' + n);
}
console.log(`합침(30m 안 비슷한 이름): ${merged.length}`);
for (const m of merged) console.log('  ' + m);
console.log(`30~150m에 비슷한 이름(주유소↔LPG, 합치지 않음): ${near.length}`);
for (const m of near.slice(0, 30)) console.log('  ' + m);
const bySido = {};
for (const s of kept) if (s.sido && gas.includes(s)) bySido[s.sido] = (bySido[s.sido] || 0) + 1;
console.log('시도별 남긴 주유소(표준데이터 출신): ' + Object.entries(bySido).sort((a, b) => b[1] - a[1]).map(([k, v]) => `${k} ${v}`).join(', '));
