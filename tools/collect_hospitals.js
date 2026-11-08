// 인터넷이 없을 때 쓸 병원 목록 모으기(감사 F40).
// 중계 서버(relay/src/worker.js)가 병원을 찾을 때 부르는 국립중앙의료원 API의 '목록' 쪽을 불러 전국 목록을 한 번에 받는다.
//   응급실: 국립중앙의료원_전국 응급의료기관 정보 조회 서비스 · getEgytListInfoInqire(응급의료기관 목록)
//   달빛어린이병원: 국립중앙의료원_전국 병·의원 찾기 서비스 · getBabyListInfoInqire(달빛어린이병원 목록, 요일별 진료 시간)
// 키는 ../.secrets/datago-key.txt 에서 읽기만 한다. 키와 키가 든 주소는 화면·파일 어디에도 찍지 않는다(실패해도 결과 코드만).
// 출력: <출력 폴더>/hospitals.csv(첫 줄 #asof=YYYY-MM-DD, 둘째 줄 머리말), 앱은 ride/Hospitals.kt 가 읽는다
// 사용: node collect_hospitals.js <출력 폴더>   (예: ../prend/app/src/main/assets/hospitals)
const fs = require('fs');
const path = require('path');

const outDir = process.argv[2];
if (!outDir) { console.log('사용: node collect_hospitals.js <출력 폴더>'); process.exit(1); }
const key = fs.readFileSync(path.join(__dirname, '../.secrets/datago-key.txt'), 'utf8').trim();

/** 한 쪽(page)을 받아 item 목록과 전체 수를 돌려준다. 실패하면 결과 코드만 담아 던진다(주소에 키가 있어 주소는 남기지 않는다) */
async function page(op, pageNo, rows) {
  const r = await fetch(`https://apis.data.go.kr/B552657/${op}?serviceKey=${encodeURIComponent(key)}&` + new URLSearchParams({ pageNo, numOfRows: rows }));
  const xml = await r.text();
  if (!r.ok) throw new Error(`${op.split('/')[1]} HTTP ${r.status}`);
  const code = tag(xml, 'resultCode');
  if (code && code !== '00') throw new Error(`${op.split('/')[1]} ${code} ${tag(xml, 'resultMsg')}`);
  const err = tag(xml, 'returnAuthMsg') || tag(xml, 'errMsg');
  if (err) throw new Error(`${op.split('/')[1]} ${err}`);
  const items = [...xml.matchAll(/<item>([\s\S]*?)<\/item>/g)].map((m) => Object.fromEntries([...m[1].matchAll(/<(\w+)>([\s\S]*?)<\/\1>/g)].map((x) => [x[1], x[2].trim()])));
  return { items, total: Number(tag(xml, 'totalCount') || 0) };
}

async function all(op) {
  const out = [];
  for (let p = 1; p < 50; p++) {
    const { items, total } = await page(op, p, 500);
    out.push(...items);
    if (!items.length || out.length >= total) break;
  }
  return out;
}

const tag = (xml, name) => (xml.match(new RegExp(`<${name}>([^<]*)</${name}>`)) || [])[1] || null;
// 앱은 쉼표로만 나눠 읽는다(RestAreas와 같은 법). 글 속 쉼표는 빈칸으로
const clean = (s) => (s || '').replace(/&amp;/g, '&').replace(/[,\r\n]+/g, ' ').replace(/\s+/g, ' ').trim();
const num = (s) => { const v = Number(s); return Number.isFinite(v) ? v.toFixed(6) : null; };

(async () => {
  const [er, moon] = await Promise.all([
    all('ErmctInfoInqireService/getEgytListInfoInqire'),
    all('HsptlAsembySearchService/getBabyListInfoInqire'),
  ]);
  const rows = [];
  for (const h of er) {
    const lat = num(h.wgs84Lat), lon = num(h.wgs84Lon);
    if (!lat || !lon) continue;
    rows.push(['er', clean(h.dutyName), clean(h.dutyTel3 || h.dutyTel1), lat, lon, clean(h.dutyEmclsName), clean(h.dutyAddr), '']);
  }
  for (const h of moon) {
    const lat = num(h.wgs84Lat), lon = num(h.wgs84Lon);
    if (!lat || !lon) continue;
    // 요일별 진료 시간: 1(월)~7(일), 8(공휴일). 'HHmm-HHmm'을 |로 잇고, 그날 진료가 없으면 비운다
    const hours = [1, 2, 3, 4, 5, 6, 7, 8].map((d) => (h[`dutyTime${d}s`] && h[`dutyTime${d}c`] ? `${h[`dutyTime${d}s`]}-${h[`dutyTime${d}c`]}` : '')).join('|');
    rows.push(['moon', clean(h.dutyName), clean(h.dutyTel1), lat, lon, clean(h.dutyEmclsName), clean(h.dutyAddr), hours]);
  }
  const asof = new Date(Date.now() + 9 * 3600_000).toISOString().slice(0, 10); // 한국 날짜
  fs.mkdirSync(outDir, { recursive: true });
  const text = [`#asof=${asof}`, 'kind,name,tel,lat,lon,class,addr,hours', ...rows.map((r) => r.join(','))].join('\n') + '\n';
  fs.writeFileSync(path.join(outDir, 'hospitals.csv'), text);
  const classes = {};
  for (const r of rows) if (r[0] === 'er') classes[r[5]] = (classes[r[5]] || 0) + 1;
  console.log(`응급의료기관 ${er.length}곳(좌표 있는 곳 ${rows.filter((r) => r[0] === 'er').length}), 달빛어린이병원 ${moon.length}곳(좌표 있는 곳 ${rows.filter((r) => r[0] === 'moon').length}), 기준일 ${asof}`);
  console.log('응급실 종류:', JSON.stringify(classes));
  console.log(`${Math.round(Buffer.byteLength(text) / 1024)}KB → ${path.join(outDir, 'hospitals.csv')}`);
})().catch((e) => { console.log('실패:', e.message); process.exit(1); });
