// PREND 중계 서버(Cloudflare Workers). 앱에 API 키를 넣지 않으려고 둔다.
// 키는 Worker 비밀값(NAVER_ID, NAVER_SECRET, DATAGO_KEY, GEMINI_KEY, TMAP_KEY)에만 있고, 앱은 APP_TOKEN으로만 부른다.
// 받은 위치·지역 이름·말은 그 요청에 답하는 데만 쓰고 저장하지 않는다(기록·캐시 없음).
//
// GET /places?area=수정구 신흥동&kind=food|cafe   같이 고르기: 네이버 지역 검색 + 블로그 후기 수
// GET /hospitals?lat=37.4&lon=127.1              급할 때: 가까운 응급실(실시간 병상) + 달빛어린이병원
// POST /intent {text, parked}                     [프렌드]에 건넨 말 중 앱의 낱말 규칙에 안 맞는 것만: 정해 둔 할 일 하나로(Gemini)
// GET /weather?nx=60&ny=127&at=2026110218        출발 전 한 장: 목적지 5km 격자의 도착 무렵(앞뒤 1시간) 비·눈 예보(기상청 단기예보). 격자 번호와 시각만 받고 kind 하나만 돌려준다
// POST /road {pts:[[lat,lon],...], name?}         골목 판정: 차가 선 순간 지나온 자리(최대 100개)의 TMAP 도로 등급. 등급·제한속도·차로만 돌려주고 좌표는 버린다.
//                                                 name:true면(급할 때 119에 말할 위치, 11/07) 마지막 자리의 도로 이름도 돌려준다
// POST /toilet {lat, lon, heading}                "화장실 급해": 둘레 5km 주유소(TMAP 주변 검색, 거리순)와 앞쪽 세 곳의 24시간 영업 여부. 앱 안 공공데이터에 빠진 곳을 채운다
//
// 요청 수 상한(11/07 점검: 토큰은 APK 안에 있다): 보낸 곳(IP)마다 1분에 40번, 생성형 AI(/intent)는 1분에 10번.
// 앱이 정상으로 부르는 수(정차마다 /road 한 번, 말 한마디에 /intent 한 번)보다 넉넉하다. 묶음(ratelimits)이 없으면 막지 않는다
// 하루 상한(11/09, GitHub에 APK를 올리기 전): Gemini를 실제로 부르는 /intent는 모든 곳을 합쳐 하루(한국 시간 0시부터) 500번까지.
// 한 번에 입력 약 2,230토큰·출력 약 12토큰(11/09 실측)이라 다 써도 하루 0.35달러 남짓이다. 넘으면 다음 0시까지 429(앱은 "서버가 바빠요")

import { DurableObject } from 'cloudflare:workers';

const AI_DAILY_LIMIT = 500;

export default {
  async fetch(req, env) {
    const url = new URL(req.url);
    if (req.headers.get('x-prend-token') !== env.APP_TOKEN) return json({ error: 'token' }, 401);
    const ip = req.headers.get('cf-connecting-ip') || 'unknown';
    if (env.PER_IP && !(await env.PER_IP.limit({ key: ip })).success) return json({ error: 'busy' }, 429);
    if (url.pathname === '/intent' && env.PER_IP_AI && !(await env.PER_IP_AI.limit({ key: ip })).success) return json({ error: 'busy' }, 429);
    try {
      if (url.pathname === '/places') return await places(url, env);
      if (url.pathname === '/hospitals') return await hospitals(url, env);
      if (url.pathname === '/intent' && req.method === 'POST') return await intent(req, env);
      if (url.pathname === '/road' && req.method === 'POST') return await road(req, env);
      if (url.pathname === '/toilet' && req.method === 'POST') return await toilet(req, env);
      if (url.pathname === '/weather') return await weather(url, env);
      return json({ error: 'not found' }, 404);
    } catch (e) {
      return json({ error: String(e?.message || e) }, 502);
    }
  },
};

const json = (body, status = 200) =>
  new Response(JSON.stringify(body), { status, headers: { 'content-type': 'application/json; charset=utf-8', 'cache-control': 'no-store' } });

// ── 화장실 찾기(TMAP 주변 주유소) ──

/**
 * "화장실 급해"(11/07): 일반 도로에서 지금 자리 둘레 5km의 주유소를 거리순으로(주유소는 등록할 때 공중화장실을 둔다, 석유사업법 시행령 별표 2).
 * 앱 안 공공데이터(좌표가 있는 주유소는 전국의 절반쯤)에 빠진 곳을 채우는 몫이고, 고르는 것은 앱이 한다(앞쪽·오른쪽).
 * - 경유지로 넣을 좌표는 차가 들어가는 입구(frontLat/Lon), 없으면 중심점
 * - 진행 방향 ±60° 앞쪽 가까운 세 곳만 상세 조회로 평일 24시간 영업 여부(twFlag)를 붙인다(밤에 닫힌 곳을 뒤로 미루려고)
 * 받은 위치와 결과는 이 요청에 답하는 데만 쓰고 저장하지 않는다(TMAP 약관: 받은 자료를 24시간 넘게 저장하지 않는다)
 */
async function toilet(req, env) {
  if (!env.TMAP_KEY) return json({ error: 'TMAP_KEY 없음' }, 503);
  const b = await req.json().catch(() => ({}));
  const lat = Number(b.lat), lon = Number(b.lon), heading = Number(b.heading);
  if (!(lat > 33 && lat < 39 && lon > 124 && lon < 132)) return json({ error: 'position' }, 400);
  const key = String(env.TMAP_KEY).trim().replace(/^["']+|["']+$/g, '');
  const headers = { appKey: key, Accept: 'application/json', 'Accept-Language': 'ko' };
  const q = new URLSearchParams({
    version: '1', categories: '주유소', centerLat: lat.toFixed(5), centerLon: lon.toFixed(5),
    radius: '5', count: '40', reqCoordType: 'WGS84GEO', resCoordType: 'WGS84GEO', sort: 'distance',
  });
  // 앱은 2.5초까지 기다린다: 주변 검색은 1.2초, 앞쪽 세 곳 상세 조회는 모두 합쳐 0.7초에서 끊는다(끝나지 않은 곳은 24시간 여부 없이)
  const r = await fetch(`https://apis.openapi.sk.com/tmap/pois/search/around?${q}`, { headers, signal: AbortSignal.timeout(1200) }).catch(() => null);
  if (!r) return json({ error: 'tmap' }, 502);
  // 둘레에 없으면 빈 답(204)
  if (r.status === 204) return json({ source: 'TMAP', items: [] });
  if (!r.ok) {
    const text = (await r.text().catch(() => '')).split(key).join('***').slice(0, 160);
    return json({ error: 'tmap', status: r.status, why: text }, 502);
  }
  // 본문을 읽다 끊기면(1.2초) 둘레에 없음과 가리게 오류로 돌려준다
  const j = await r.json().catch(() => null);
  if (!j) return json({ error: 'tmap', why: 'body' }, 502);
  const pois = j?.searchPoiInfo?.pois?.poi || [];
  const items = pois.map((p) => {
    const la = Number(p.frontLat) || Number(p.noorLat);
    const lo = Number(p.frontLon) || Number(p.noorLon);
    // dataKind: 3 주유소, 4 충전소(가스)
    return { id: p.id, name: String(p.name || '').trim(), lat: la, lon: lo, kind: String(p.dataKind) === '4' ? 'lpg' : 'gas' };
  }).filter((i) => i.name && Number.isFinite(i.lat) && Number.isFinite(i.lon) && i.lat > 33 && i.lon > 124);
  if (Number.isFinite(heading)) {
    // 앱이 고르지 않을 곳(가스 충전소, 고속도로 휴게소 안 주유소, 150m 안)에는 상세 조회를 쓰지 않는다
    const hwName = (n) => /(상)|(하)|상행|하행/.test(n) || (n.includes('휴게소') && /방향|방면/.test(n));
    const ahead = items.filter((i) => i.kind === 'gas' && !hwName(i.name) && distM(lat, lon, i.lat, i.lon) > 150).filter((i) => { const rel = (bearingDeg(lat, lon, i.lat, i.lon) - heading + 360) % 360; return rel <= 60 || rel >= 300; }).slice(0, 3);
    const details = Promise.all(ahead.map(async (i) => {
      const d = await fetch(`https://apis.openapi.sk.com/tmap/pois/${encodeURIComponent(i.id)}?version=1&resCoordType=WGS84GEO`, { headers, signal: AbortSignal.timeout(700) })
        .then((x) => (x.ok ? x.json() : null)).catch(() => null);
      const f = String(d?.poiDetailInfo?.twFlag ?? '');
      if (f === '1') i.open24 = true; else if (f === '0') i.open24 = false;
    }));
    await Promise.race([details, new Promise((res) => setTimeout(res, 700))]);
  }
  return json({ source: 'TMAP', items: items.map(({ id, ...rest }) => rest) });
}

function distM(aLat, aLon, bLat, bLon) {
  const rad = (d) => (d * Math.PI) / 180;
  const h = Math.sin(rad(bLat - aLat) / 2) ** 2 + Math.cos(rad(aLat)) * Math.cos(rad(bLat)) * Math.sin(rad(bLon - aLon) / 2) ** 2;
  return 2 * 6371000 * Math.asin(Math.sqrt(h));
}

function bearingDeg(aLat, aLon, bLat, bLon) {
  const rad = (d) => (d * Math.PI) / 180;
  const y = Math.sin(rad(bLon - aLon)) * Math.cos(rad(bLat));
  const x = Math.cos(rad(aLat)) * Math.sin(rad(bLat)) - Math.sin(rad(aLat)) * Math.cos(rad(bLat)) * Math.cos(rad(bLon - aLon));
  return ((Math.atan2(y, x) * 180) / Math.PI + 360) % 360;
}

// ── 골목 판정(TMAP) ──

const TMAP = 'https://apis.openapi.sk.com/tmap/road';

/**
 * 차가 선 순간 지나온 자리로 지금 길의 등급을 묻는다(SK open API, 키는 요청 머리에만).
 * - 이동한 도로 찾기(matchToRoads): 지나온 자리를 길에 맞춰 점마다 등급·제한속도. 한 점만 보면 옆 큰길에 붙을 수 있어 지나온 길로 맞춘다
 * - 가까운 도로 찾기(nearToRoad): 마지막 자리 하나의 등급·차로 수·제한속도(차로 수는 이것만 준다)
 * 원도로등급: 0 고속국도 … 5~7 주요도로, 8 기타도로1, 9 이면도로, 11 단지내도로, 12 이면도로2(세도로). 판정은 앱이 한다
 */
async function road(req, env) {
  if (!env.TMAP_KEY) return json({ error: 'TMAP_KEY 없음' }, 503);
  const body = await req.json().catch(() => ({}));
  const pts = (Array.isArray(body.pts) ? body.pts : [])
    .map((p) => (Array.isArray(p) ? [Number(p[0]), Number(p[1])] : null))
    .filter((p) => p && Number.isFinite(p[0]) && Number.isFinite(p[1]) && Math.abs(p[0]) <= 90 && Math.abs(p[1]) <= 180)
    .slice(-100);
  if (!pts.length) return json({ error: 'pts' }, 400);
  // 붙여 넣을 때 섞인 앞뒤 빈칸·따옴표는 뗀다
  const key = String(env.TMAP_KEY).trim().replace(/^["']+|["']+$/g, '');
  const headers = { appKey: key, Accept: 'application/json', 'Accept-Language': 'ko' };
  const [lat, lon] = pts[pts.length - 1];
  // TMAP이 거절하면 그 까닭(오류 코드·문구)을 앱 기록에 남길 수 있게 짧게 돌려준다(키가 섞여 있으면 가린다)
  // 키 모양(길이·글자 종류)만 함께 돌려준다: 붙여 넣기가 잘못됐는지 가르려고(키 자체는 돌려주지 않는다)
  const shape = `${key.length}자 ${/^[A-Za-z0-9]+$/.test(key) ? '영문·숫자만' : '다른 글자 섞임'}`;
  const why = async (r) => ({ status: r.status, key: shape, why: (await r.text().catch(() => '')).split(key).join('***').slice(0, 160) });
  const near = fetch(`${TMAP}/nearToRoad?version=1&lat=${lat.toFixed(6)}&lon=${lon.toFixed(6)}`, { headers })
    .then(async (r) => (r.ok ? r.json() : why(r))).catch(() => null);
  let match = null;
  if (pts.length >= 2) {
    const coords = pts.map(([a, o]) => `${o.toFixed(6)},${a.toFixed(6)}`).join('|');
    const r = await fetch(`${TMAP}/matchToRoads?version=1`, {
      method: 'POST',
      headers: { ...headers, 'Content-Type': 'application/x-www-form-urlencoded' },
      body: new URLSearchParams({ responseType: '2', coords }),
    }).catch(() => null);
    if (r?.ok) {
      const j = await r.json();
      // 보간점을 빼고 요청한 자리에 맞춘 점만(sourceIndex가 있는 점), 뒤에서부터 다섯 개(지금 선 곳에 가까운 길)
      const all = j?.resultData?.matchedPoints || [];
      const src = all.filter((p) => p.sourceIndex !== undefined);
      match = (src.length ? src : all).slice(-5).map((p) => ({ cat: Number(p.roadCategory), speed: Number(p.speed) }));
    } else if (r) match = await why(r);
  }
  const n = await near;
  const h = n?.resultData?.header;
  // 도로 이름은 급할 때 위치 줄을 물을 때만(골목 판정에는 쓰지 않는다)
  const name = body.name === true ? String(h?.roadName || '').trim() : '';
  return json({
    match,
    near: h ? { cat: Number(h.roadCategory), lane: Number(h.lane), speed: Number(h.speed), ...(name ? { name } : {}) } : n?.status ? { status: n.status, why: n.why } : null,
  });
}

// ── 같이 고르기 ──

/** 종류마다 검색어와 남길 업종. 지역 검색은 한 번에 5곳까지라 검색어 두 개로 후보를 모은다 */
const KINDS = {
  food: { queries: ['맛집', '식당'], keep: /음식점|한식|양식|중식|일식|분식|아시아|육류|고기|해물|생선|국수|칼국수|냉면|국밥|찌개|치킨|피자|햄버거|돈가스|뷔페|족발|곱창|백반|두부|기사식당/, drop: /카페|디저트|베이커리|술집|주점|바\b/ },
  cafe: { queries: ['카페', '디저트 카페'], keep: /카페|디저트|베이커리|제과|차,커피|찻집/, drop: /PC방|키즈카페|만화카페|보드카페|스터디카페/ },
  // 차 돌봄: 가까운 현대차 블루핸즈(공식 정비 협력점). 이름에 '블루핸즈'가 든 정비소만, 블로그 글 수는 세지 않는다(11/01 검색 확인: 업종 '자동차정비,수리')
  service: { queries: ['블루핸즈', '현대자동차 블루핸즈'], keep: /자동차정비/, drop: /오토바이|세차|렌터카|중고/, name: /블루핸즈/, sort: 'random', noReviews: true },
  // 골목 목적지(운전 도움): 목적지 동네의 공영주차장. 큰길가인지는 앱이 TMAP 도로 등급으로 본다
  parking: { queries: ['공영주차장', '공영 주차장'], keep: /주차장/, drop: /자전거|오토바이|화물/, name: /주차장/, sort: 'random', noReviews: true },
};

async function places(url, env) {
  const area = (url.searchParams.get('area') || '').trim().slice(0, 30);
  const kind = KINDS[url.searchParams.get('kind')] || KINDS.food;
  if (!area) return json({ error: 'area' }, 400);
  const seen = new Map();
  for (const q of kind.queries) {
    const r = await naver(env, 'local', { query: `${area} ${q}`, display: 5, sort: kind.sort || 'comment' });
    for (const it of r.items || []) {
      const name = strip(it.title);
      const cat = it.category || '';
      if (!kind.keep.test(cat) || kind.drop.test(cat) || (kind.name && !kind.name.test(name))) continue;
      const key = name + '|' + (it.roadAddress || it.address);
      if (!seen.has(key)) seen.set(key, { name, category: cat.split('>').pop(), address: it.roadAddress || it.address, lat: Number(it.mapy) / 1e7, lon: Number(it.mapx) / 1e7 });
    }
  }
  // 블로그 후기 수: 가게 이름으로 찾은 글 수. 블로그 검색은 이름을 정확히 묶지 못해("단풍", "가든서비스"처럼 흔한 낱말이 든 이름은
  // 상관없는 글까지 센다), 찾은 글 10개 중 절반 넘게 그 이름이 실제로 들어 있고 이름이 네 글자 이상일 때만 믿는다. 아니면 비운다
  const list = [...seen.values()].slice(0, 8);
  if (kind.noReviews) return json({ source: 'NAVER 지역 검색', items: list });
  await Promise.all(list.map(async (p) => {
    const key = p.name.replace(/\s+/g, '');
    if (key.length < 4) { p.reviews = null; return; }
    const b = await naver(env, 'blog', { query: `"${p.name}"`, display: 10 }).catch(() => null);
    const hits = (b?.items || []).filter((i) => strip(i.title + i.description).replace(/\s+/g, '').includes(key)).length;
    // 가게 하나에 글이 3만 건을 넘으면 이름이 흔한 낱말과 겹친 것으로 본다
    p.reviews = b && hits >= 5 && b.total <= 30_000 ? b.total : null;
  }));
  return json({ source: 'NAVER 지역·블로그 검색', items: list });
}

async function naver(env, what, params) {
  const r = await fetch(`https://naverapihub.apigw.ntruss.com/search/v1/${what}?` + new URLSearchParams(params), {
    headers: { 'X-NCP-APIGW-API-KEY-ID': env.NAVER_ID, 'X-NCP-APIGW-API-KEY': env.NAVER_SECRET },
  });
  if (!r.ok) throw new Error(`naver ${what} ${r.status}`);
  return r.json();
}

const strip = (s) => (s || '').replace(/<[^>]+>/g, '').replace(/&amp;/g, '&').replace(/&lt;/g, '<').replace(/&gt;/g, '>').replace(/&quot;/g, '"').replace(/&#39;/g, "'");

// ── 급할 때: 응급실과 달빛어린이병원(국립중앙의료원) ──

async function hospitals(url, env) {
  const lat = Number(url.searchParams.get('lat')), lon = Number(url.searchParams.get('lon'));
  if (!(lat > 33 && lat < 39 && lon > 124 && lon < 132)) return json({ error: 'position' }, 400);
  const [near, moon] = await Promise.all([
    datago(env, 'ErmctInfoInqireService/getEgytLcinfoInqire', { WGS84_LON: lon, WGS84_LAT: lat, pageNo: 1, numOfRows: 30 }),
    datago(env, 'HsptlAsembySearchService/getBabyLcinfoInqire', { WGS84_LON: lon, WGS84_LAT: lat, pageNo: 1, numOfRows: 5 }),
  ]);
  // 위치로 찾은 목록에는 일반 병원도 섞여 있다. 실시간 병상을 보고하는 곳만 응급실로 본다(시도·시군구별로 묻는다)
  const regions = [...new Set(near.slice(0, 15).map((h) => (h.dutyAddr || '').split(' ').slice(0, 2).join(' ')).filter((r) => r.includes(' ')))].slice(0, 3);
  const beds = {};
  await Promise.all(regions.map(async (r) => {
    const [s1, s2] = r.split(' ');
    for (const b of await datago(env, 'ErmctInfoInqireService/getEmrrmRltmUsefulSckbdInfoInqire', { STAGE1: s1, STAGE2: s2, pageNo: 1, numOfRows: 100 })) beds[b.hpid] = b;
  }));
  const er = near.filter((h) => beds[h.hpid]).slice(0, 5).map((h) => ({
    name: h.dutyName, address: h.dutyAddr, tel: beds[h.hpid].dutyTel3 || h.dutyTel1,
    lat: Number(h.latitude), lon: Number(h.longitude), km: Number(h.distance),
    beds: beds[h.hpid].hvec != null ? Number(beds[h.hpid].hvec) : null, bedsAt: beds[h.hpid].hvidate || null,
  }));
  const kids = moon.map((h) => ({
    name: h.dutyName, address: h.dutyAddr, tel: h.dutyTel1,
    lat: Number(h.latitude), lon: Number(h.longitude), km: Number(h.distance),
    open: h.startTime || null, close: h.endTime || null,
  }));
  return json({ source: '국립중앙의료원 응급의료기관·병의원 찾기', er, moon: kids });
}

async function datago(env, op, params) {
  const r = await fetch(`https://apis.data.go.kr/B552657/${op}?serviceKey=${encodeURIComponent(env.DATAGO_KEY)}&` + new URLSearchParams(params));
  const xml = await r.text();
  const code = tag(xml, 'resultCode');
  if (code && code !== '00') throw new Error(`datago ${op.split('/')[1]} ${code} ${tag(xml, 'resultMsg')}`);
  const err = tag(xml, 'errMsg');
  if (err) throw new Error(`datago ${op.split('/')[1]} ${err}`);
  return [...xml.matchAll(/<item>([\s\S]*?)<\/item>/g)].map((m) => Object.fromEntries([...m[1].matchAll(/<(\w+)>([\s\S]*?)<\/\1>/g)].map((x) => [x[1], strip(x[2])])));
}

const tag = (xml, name) => (xml.match(new RegExp(`<${name}>([^<]*)</${name}>`)) || [])[1] || null;

// ── 말 → 할 일(생성형 AI 대역, 접수양식 5번) ──
// 앱이 먼저 낱말 규칙으로 고르고, 맞지 않은 말만 여기로 온다. AI는 아래 목록 중 하나만 고르고 글을 지어내지 않는다.
// 응급 안내의 문장은 앱에 미리 써 두어 AI가 끼지 않는다(여기서는 사고·고장·연기·급병 화면을 열지 고르기만 한다).

const INTENT_MODEL = 'gemini-3.5-flash-lite';

/** 할 일 이름 → AI에게 주는 설명 */
const INTENTS = {
  accident: '이 차에 교통사고가 났다(이 차가 부딪혔다, 박았다, 박혔다, 긁혔다, 접촉 사고). 다른 차의 사고, 앞쪽 사고로 길이 막힌다는 말, 뉴스·지난 이야기, 사고가 날 뻔했다는 말은 other',
  fire: '이 차에서 연기가 난다, 이 차에서 타는 냄새가 난다, 이 차에 불이 났다(밖이나 다른 차에서 들어오는 연기·냄새는 inside_air. 사람이 연기를 마셔 숨쉬기 힘들거나 아프면 illness)',
  breakdown: '차가 고장 나서 섰다, 차가 안 움직인다, 타이어가 터졌다, 기름이 떨어졌다, 배터리가 나갔다, 시동이 안 걸리거나 꺼졌다(부딪혔으면 accident, 연기·타는 냄새면 fire, 언제·어디서 고칠지 묻는 말은 care)',
  tow: '견인차(렉카)가 왔다, 견인차가 차를 끌고 가려 한다(견인차를 불러야 한다는 말은 breakdown)',
  aid_cpr: '어른이 쓰러져 반응이 없다, 숨을 안 쉰다, 기절했다(심폐소생술 카드). 아기·아이면 kid=true(앱이 119 먼저 안내로 보낸다). "기절할 것 같다"·"가슴이 아프다"처럼 의식이 있으면 illness',
  aid_choke: '1세 이상의 사람이 목에 뭔가 걸려 숨을 못 쉬거나 캑캑거린다(기도 막힘 카드)',
  aid_infant: '돌이 안 된 아기(생후 몇 개월, 백일, 갓난아기) 목에 뭔가 걸렸다(영아 기도 막힘 카드). 애·아이·아들·딸·어린이처럼 나이를 모르면 aid_choke',
  aid_seizure: '누가 경련·발작을 한다, 몸을 떤다, 거품을 문다(경련 카드)',
  illness: '차에 탄 사람이 갑자기 아프거나 이상하다(가슴 통증, 숨쉬기 힘듦, 의식이 흐림, 열, 피, 토함, 심한 통증, 말이 어눌하거나 이상함, 얼굴이 한쪽으로 처짐, 팔다리에 힘이 빠짐). 위 응급처치 카드에 맞으면 그쪽. 멀미·속이 울렁거림만이면 motion',
  motion: '차멀미가 난다, 속이 안 좋다, 울렁거린다, 토할 것 같다(숨·의식·피·쓰러짐·머리 통증이 함께면 illness)',
  rest: '졸리거나 피곤해서 쉬어 가고 싶다, 졸음쉼터나 휴게소를 찾는다, 달리는 중에 운전자가 자고 싶다·눈 좀 붙이고 싶다',
  rest_no: '졸리지 않다, 쉬지 않고 그냥 가겠다, 쉼터에 안 들르겠다',
  toilet: '화장실이 급하다, 화장실에 가고 싶다, 소변·대변이 마렵다(아이가 쉬 마렵다고 한다), 가까운 화장실을 찾는다',
  nap: '같이 탄 사람(옆자리·뒷자리)이 자고 싶다, 또는 차를 세워 둔 운전자가 잠깐 자고 싶다(달리는 중에 운전자가 졸리거나 자고 싶으면 rest)',
  nap_off: '쪽잠(세워 두고 잠깐 자기)이나 잘래요(같이 탄 사람이 자는 동안)를 끝낸다, 다 쉬었다, 깼다',
  food: '밥 먹을 곳, 식당, 배고프다',
  cafe: '카페, 커피, 디저트',
  warm: '운전자가 춥다, 손발이 시리다, 따뜻하게 해 달라(운전석 열선을 켠다)',
  cool: '운전자가 덥다, 땀이 난다, 시원하게 해 달라(운전석 통풍을 켠다)',
  heat_off: '운전석 열선을 끄라',
  vent_off: '운전석 통풍을 끄라',
  warm_at: '같이 탄 사람(옆자리·뒷자리·아이·엄마 등)이 춥다, 그 자리를 따뜻하게 해 달라(운전자 자신이면 warm)',
  cool_at: '같이 탄 사람이 덥다, 그 자리를 시원하게 해 달라(운전자 자신이면 cool)',
  heat_off_at: '옆자리·뒷자리 열선을 끄라',
  vent_off_at: '옆자리·뒷자리 통풍을 끄라',
  fog: '앞유리에 김이나 성에가 서려 앞이 잘 안 보인다',
  fuel: '주유구·충전구가 어느 쪽인지, 이 차에 어떤 기름을 넣는지',
  toll: '하이패스 차로·요금소에서 실수했다, 통행료를 못 냈다',
  park: '주차를 도와 달라, 핸들 방향을 알려 달라',
  spot_off: '내리기 전에 주차 자리를 확인하라는 알림을 그만 알려 달라',
  care: '차 정비·소모품(와이퍼·엔진오일·필터·타이어·브레이크·배터리·워셔액)을 언제 갈지, 어디서 고칠지, 얼마인지 묻는다(오늘 정비하고 왔다는 말은 shop_done, 고친 데가 또 이상하면 shop_again, 지금 차가 서 버렸으면 breakdown)',
  shop_done: '오늘 정비소·카센터·블루핸즈에서 정비나 수리를 받고 왔다, 맡긴 차를 찾아왔다',
  shop_again: '정비소에서 고친 곳이 또 고장 났다, 수리한 데가 다시 이상하다(지금 차가 서 버렸으면 breakdown)',
  baby_on: '아이(아기·어린아이)가 차에 탔다',
  baby_off: '아이가 내렸다, 아이가 안 탔다',
  novice_on: '말하는 사람이 자기가 초보라고 한다, 운전 도움(예전 이름 초보 운전)을 켜 달라고 한다(다른 차나 다른 사람이 초보라는 말은 other)',
  novice_off: '운전 도움(예전 이름 초보 운전)을 끄라, 이제 운전 도움은 필요 없다',
  memo: 'PREND가 기억한 것을 보거나 잊게 하라',
  again: 'PREND가 방금 한 말을 못 들었다, 다시 말해 달라, 뭐라고 했느냐(목소리를 크게·작게 해 달라는 말은 other)',
  slow_on: 'PREND가 말을 너무 빨리 한다, 앞으로 천천히 말해 달라',
  slow_off: 'PREND 말을 원래 빠르기로 해 달라, 이제 천천히 말하지 않아도 된다',
  dusk_on: '밤 운전이나 어두운 길이 불편하거나 무섭다, 해 지기 전에 알려 달라',
  fresh_air: '차 안 공기를 바꾸고 싶다, 환기, 바깥 공기로, 공기가 텁텁하다(사람이 아프다는 말이 아니라 공기. 막아 달라는 말은 inside_air)',
  inside_air: '바깥 공기를 막아 달라, 안쪽 공기로 해 달라, 밖이나 다른 차에서 매연·냄새·연기·타는 냄새가 들어온다(이 차에서 나는 연기·타는 냄새는 fire)',
  music: '음악이나 노래를 틀어 달라(끄라는 말은 music_off, 무슨 노래냐는 물음은 song_what)',
  music_off: 'PREND 곡이나 음악을 멈춰 달라, 꺼 달라',
  song_what: '지금 나오는 노래나 아까 나온 노래가 무엇인지, 제목·가수가 누구인지 묻는다',
  undo: '방금 PREND가 알아서 바꾼 것(무드등·곡)을 되돌려 달라, 원래대로 해 달라',
  rain_on: '빗소리를 깔아 달라, 비 오는 소리를 듣고 싶다',
  rain_off: '빗소리를 꺼 달라, 빗소리를 빼라',
  quiet_ride: '오늘은 조용히 가자, 말 걸지 마라, 먼저 말하지 마라(이번 운행 동안. 목소리를 작게 해 달라는 말은 other)',
  quiet_off: '이제 다시 말을 걸어도 된다, 조용히 가기를 끝낸다',
  daeri_on: '지금 대리운전 기사님이 이 차를 운전하고 있다(대리를 부를지·번호·요금을 묻는 말, 직급 "대리", 대리점은 other)',
  daeri_off: '대리 운행이 끝났다, 대리가 아니다',
  other: '위 어디에도 맞지 않는다(잡담, 질문, 차가 못 하는 일, 알아듣기 어려운 말)',
};

const INTENT_PROMPT = [
  '너는 자동차 앱 PREND의 요청 분류기다. 운전자나 동승자가 PREND에게 건넨 한국어 말 한 줄을 받아, 아래 할 일 중 하나만 고른다.',
  '규칙:',
  '- 사람이 다쳤거나 갑자기 아프다는 말은 다른 것보다 먼저: 쓰러짐·목 막힘·경련이면 그 aid_ 카드, 그 밖에는 illness.',
  '- 그다음 차가 급한 말: 이 차가 부딪혔다는 말은 accident, 이 차의 연기·타는 냄새·불은 fire, 이 차가 고장 나 멈췄거나 못 간다는 말은 breakdown. 셋 다 care보다 먼저. 남의 차·지난 일·날 뻔한 일은 여기에 넣지 않는다.',
  '- kid는 illness와 aid_ 카드일 때만 쓴다: 아픈 사람이 아이(아기, 우리 애, 딸, 아들, 어린이)면 true, 아니면 false.',
  '- 확실하지 않으면 other. 억지로 맞추지 않는다.',
  '- 대답은 JSON 하나: {"intent": 할 일 이름, "kid": true|false}. 다른 글은 쓰지 않는다.',
  '할 일 목록:',
  ...Object.entries(INTENTS).map(([k, v]) => `- ${k}: ${v}`),
].join('\n');

async function intent(req, env) {
  const body = await req.json().catch(() => ({}));
  const text = String(body.text || '').trim().slice(0, 120);
  if (!text) return json({ intent: 'other', kid: false });
  if (!env.GEMINI_KEY) return json({ intent: 'other', kid: false, model: 'none' });
  if (!(await aiDayOk(env))) return json({ error: 'busy', why: 'daily' }, 429);
  const t0 = Date.now();
  const r = await fetch(`https://generativelanguage.googleapis.com/v1beta/models/${INTENT_MODEL}:generateContent`, {
    method: 'POST',
    headers: { 'x-goog-api-key': env.GEMINI_KEY, 'content-type': 'application/json' },
    body: JSON.stringify({
      systemInstruction: { parts: [{ text: INTENT_PROMPT }] },
      contents: [{ role: 'user', parts: [{ text: `상황: ${body.parked ? '차를 세워 둠' : '달리는 중'}\n들은 말: ${text}` }] }],
      generationConfig: {
        temperature: 0,
        maxOutputTokens: 40,
        responseMimeType: 'application/json',
        responseSchema: { type: 'OBJECT', properties: { intent: { type: 'STRING', enum: Object.keys(INTENTS) }, kid: { type: 'BOOLEAN' } }, required: ['intent'] },
      },
    }),
  });
  if (!r.ok) throw new Error(`gemini ${r.status}`);
  const j = await r.json();
  let out = {};
  try { out = JSON.parse(j.candidates?.[0]?.content?.parts?.map((p) => p.text || '').join('') || '{}'); } catch { out = {}; }
  const name = INTENTS[out.intent] ? out.intent : 'other';
  const sick = name === 'illness' || name.startsWith('aid_');
  return json({ intent: name, kid: sick && out.kid === true, ms: Date.now() - t0, model: INTENT_MODEL });
}

/**
 * Gemini 하루 상한: 셈은 Durable Object 하나(AiDay)에 두어 어느 곳에서 불러도 같은 수를 센다(곳마다 따로 세는 분당 상한과 다름).
 * 묶음(AI_DAY)이 없거나 셈이 실패하면 막지 않는다(시연 중에 셈 하나 때문에 말을 못 알아듣지 않게). 시험할 때는 AI_DAILY_LIMIT 변수로 줄인다
 */
async function aiDayOk(env) {
  if (!env.AI_DAY) return true;
  const limit = Number(env.AI_DAILY_LIMIT) || AI_DAILY_LIMIT;
  const day = new Date(Date.now() + 9 * 3600 * 1000).toISOString().slice(0, 10);
  try {
    return await env.AI_DAY.get(env.AI_DAY.idFromName('gemini')).take(day, limit);
  } catch {
    return true;
  }
}

/** 오늘(한국 날짜) Gemini를 부른 수. 날짜가 바뀌면 0부터 다시 센다. 수만 두고 말·위치는 두지 않는다 */
export class AiDay extends DurableObject {
  async take(day, limit) {
    const n = (await this.ctx.storage.get('day')) === day ? (await this.ctx.storage.get('n')) || 0 : 0;
    if (n >= limit) return false;
    await this.ctx.storage.put({ day, n: n + 1 });
    return true;
  }
}

// ── 출발 전 날씨(기상청 단기예보, 공공누리 제1유형) ──
// 공공데이터포털 '기상청_단기예보 조회서비스'(getVilageFcst). DATAGO_KEY로 부르고, 서비스 활용신청이 되어 있어야 한다.
// 발표는 하루 8번(02·05·08·11·14·17·20·23시), 발표 10분 뒤부터 나온다. 강수형태 PTY: 0 없음, 1 비, 2 비/눈, 3 눈, 4 소나기.
// 판정: 도착 시각의 정시와 앞뒤 한 시간 가운데 하나라도 3이면 눈, 아니고 2면 비나 눈, 아니고 1·4면 비. 강수확률은 쓰지 않는다(기상청이 정한 기준이 없음).
// 오류·자료 없음이면 kind:null(앱은 카드를 띄우지 않는다). 격자·시각은 저장하지 않는다.
async function weather(url, env) {
  const nx = Number(url.searchParams.get('nx')), ny = Number(url.searchParams.get('ny'));
  const at = url.searchParams.get('at') || '';
  if (!Number.isInteger(nx) || !Number.isInteger(ny) || !/^[0-9]{10}$/.test(at)) return json({ error: 'nx, ny, at(YYYYMMDDHH)' }, 400);
  if (!env.DATAGO_KEY) return json({ kind: null, why: 'key' });
  const kst = new Date(Date.now() + 9 * 3600_000);
  const hours = [23, 20, 17, 14, 11, 8, 5, 2];
  const bases = [];
  for (let back = 0; back < 2 && bases.length < 2; back++) {
    const d = new Date(kst.getTime() - back * 86400_000);
    const ymd = d.toISOString().slice(0, 10).replace(/-/g, '');
    for (const h of hours) {
      const ready = new Date(Date.UTC(d.getUTCFullYear(), d.getUTCMonth(), d.getUTCDate(), h, 10));
      if (ready.getTime() <= kst.getTime()) bases.push([ymd, String(h).padStart(2, '0') + '00']);
      if (bases.length >= 2) break;
    }
  }
  const want = [-1, 0, 1].map((dh) => {
    const t = new Date(Date.UTC(+at.slice(0, 4), +at.slice(4, 6) - 1, +at.slice(6, 8), +at.slice(8, 10) + dh));
    return t.toISOString().slice(0, 10).replace(/-/g, '') + String(t.getUTCHours()).padStart(2, '0') + '00';
  });
  for (const [bd, bt] of bases) {
    const r = await fetch('https://apis.data.go.kr/1360000/VilageFcstInfoService_2.0/getVilageFcst?serviceKey=' + encodeURIComponent(env.DATAGO_KEY) + '&' +
      new URLSearchParams({ pageNo: '1', numOfRows: '1000', dataType: 'JSON', base_date: bd, base_time: bt, nx: String(nx), ny: String(ny) }));
    if (!r.ok) return json({ kind: null, why: 'http ' + r.status });
    const j = await r.json().catch(() => null);
    const code = j?.response?.header?.resultCode;
    if (code === '03') continue;
    if (code !== '00') return json({ kind: null, why: 'code ' + code });
    const items = j?.response?.body?.items?.item || [];
    const pty = items.filter((x) => x.category === 'PTY' && want.includes(x.fcstDate + x.fcstTime)).map((x) => Number(x.fcstValue));
    if (!pty.length) continue;
    const kind = pty.includes(3) ? 'snow' : pty.includes(2) ? 'rainsnow' : pty.some((v) => v === 1 || v === 4) ? 'rain' : null;
    return json({ kind, base: bd + bt });
  }
  return json({ kind: null, why: 'no data' });
}
