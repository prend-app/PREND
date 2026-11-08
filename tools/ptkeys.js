// PT 장면 키(11/07, 검토-전체점검 고칠 것 10): 장면마다 콘솔 조작(기어·페달·문·깜빡이)과 시연 단추를 키 하나로 묶는다.
// 발표자가 노트북에서 키를 누르면 vcride.js와 같은 법으로 Virtual Console을 몬다. 브라우저는 처음 한 번만 열어 두어 키를 누른 즉시 움직인다.
// 누른 때와 걸음마다 걸린 시간을 tools/ptkeys_times.txt에 적는다(리허설에서 잰 값을 기획안 별첨 B-2에 옮기려고).
// 사용: node ptkeys.js   (에뮬레이터·Virtual Console(127.0.0.1:8088)·adb가 붙어 있어야 한다. h 를 누르면 키 목록)
const fs = require('fs');
const path = require('path');
const { execFileSync } = require('child_process');
const puppeteer = require('puppeteer-core');
const ADB = process.env.ADB || 'adb';
const wait = (ms) => new Promise((r) => setTimeout(r, ms));
const GEAR = { P: 1439, R: 1473, N: 1507, D: 1540 };
const TIMES = path.join(__dirname, 'ptkeys_times.txt');

// 키 → [이름, 걸음]. 걸음은 vcride.js와 같다(P|R|N|D, gas:ms, brake:ms, wait:ms, app:명령, door:문:Open|Close, set:카드:버튼)
const SCENES = {
  '1': ['킥 준비: ① 장면 준비 → D → 시속 60~70까지', ['app:pt_e_ready', 'wait:1200', 'D', 'gas:4000']],
  k: ['킥: 브레이크 3초 → P → 운전석 문 열기', ['brake:3000', 'wait:1500', 'P', 'wait:800', 'door:Front left:Open']],
  '2': ['고속도로: 문 닫기 → ② → D → 시속 80 → 브레이크 3초 → P → 운전석 문 열기', ['door:Front left:Close', 'app:pt_highway', 'wait:1200', 'D', 'gas:6000', 'brake:3000', 'wait:1500', 'P', 'wait:800', 'door:Front left:Open']],
  '3': ['에어백과 블루링크: 문 닫기 → ③', ['door:Front left:Close', 'app:pt_bluelink']],
  b: ['블루링크 장면의 뒷문 열기', ['door:Rear left:Open']],
  '4': ['아플 때 준비: 뒷문 닫기 → ① → D → 시속 40', ['door:Rear left:Close', 'app:pt_e_ready', 'wait:1200', 'D', 'gas:2500']],
  s: ['세우기: 브레이크 3초 → P', ['brake:3000', 'wait:1500', 'P']],
  '5': ['평소의 PREND: ④ → D → 시속 50 → 깜빡이 켬', ['app:pt_swallow', 'wait:1200', 'D', 'gas:3000', 'set:Turn Signal State:Blink left']],
  x: ['깜빡이 끔', ['set:Turn Signal State:Off']],
  '6': ['화장실: ⑤ → D → 시속 40 (그다음 [프렌드] → "애가 쉬 마렵대")', ['app:pt_toilet', 'wait:1200', 'D', 'gas:2500']],
  o: ['운전석 문 열기', ['door:Front left:Open']],
  c: ['문 모두 닫기', ['door:Front left:Close', 'door:Front right:Close', 'door:Rear left:Close', 'door:Rear right:Close']],
  '0': ['① 장면 준비', ['app:pt_e_ready']],
  z: ['PT 끝: 시험 값 모두 끔', ['app:pt_clean']],
  v: ['지금 속도·기어 보기', ['speed']],
};

(async () => {
  // 탭 둘(주행·문 카드)을 함께 열어 두므로 뒤에 놓인 탭의 타이머를 늦추지 않게 한다(11/07 시험: 문 카드 탭을 연 뒤 주행 탭의 가속이 먹지 않았다)
  const browser = await puppeteer.launch({
    executablePath: process.env.CHROME || 'C:/Program Files/Google/Chrome/Application/chrome.exe', headless: true,
    args: ['--disable-background-timer-throttling', '--disable-backgrounding-occluded-windows', '--disable-renderer-backgrounding'],
  });
  const open = async () => {
    const p = await browser.newPage();
    await p.setViewport({ width: 1600, height: 1000 });
    await p.goto('http://127.0.0.1:8088/', { waitUntil: 'networkidle2', timeout: 30000 });
    await wait(1500);
    return p;
  };
  const drive = await open();
  const panel = await open();
  await panel.mouse.click(222, 480); // 두 번째 탭(Emulation Controls): 문·기능 카드
  await wait(1200);
  const status = () => drive.evaluate(() => {
    const t = document.body.innerText;
    const m = t.match(/SPEED\s*\n\s*([\d.]+)\s*km\/h/);
    const g = t.match(/GEAR\s*\n\s*([PRND])/);
    return `${m ? m[1] : '?'}km/h ${g ? g[1] : '?'}`;
  });
  const hold = async (x, y, ms) => { await drive.mouse.move(x, y); await drive.mouse.down(); await wait(ms); await drive.mouse.up(); };
  const card = (title, option) => panel.evaluate((title, option) => {
    const head = [...document.querySelectorAll('*')].find((e) => e.childElementCount === 0 && e.textContent.trim() === title);
    if (!head) return 'no-title';
    for (let box = head.parentElement; box; box = box.parentElement) {
      const btn = [...box.querySelectorAll('button, [role=button]')].find((b) => b.textContent.trim() === option);
      if (btn) { btn.scrollIntoView({ block: 'center' }); btn.click(); return 'ok'; }
    }
    return 'no-option';
  }, title, option);
  const door = async (which, action) => {
    const val = await panel.evaluate((which) => {
      const head = [...document.querySelectorAll('*')].find((e) => e.childElementCount === 0 && e.textContent.trim() === 'Door Position');
      if (!head) return null;
      for (let box = head.parentElement; box; box = box.parentElement) {
        const sel = box.querySelector('select');
        if (sel) { sel.setAttribute('data-door', '1'); const o = [...sel.options].find((x) => x.textContent.trim() === which); return o ? o.value : null; }
      }
      return null;
    }, which);
    if (val == null) return 'no-door';
    await panel.select('select[data-door]', val);
    await wait(300);
    return card('Door Position', action);
  };

  let busy = false;
  const run = async (key) => {
    const scene = SCENES[key];
    if (!scene) return;
    if (busy) { console.log('  (앞 장면이 아직 도는 중)'); return; }
    busy = true;
    const [name, steps] = scene;
    const t0 = Date.now();
    const lines = [`${new Date().toTimeString().slice(0, 8)} [${key}] ${name}`];
    console.log(lines[0]);
    for (const step of steps) {
      const [verb, ...rest] = step.split(':');
      let r = '';
      // 누를 탭을 앞으로(주행 걸음은 주행 탭, 문·기능 카드는 두 번째 탭)
      if (GEAR[verb] || verb === 'gas' || verb === 'brake') await drive.bringToFront();
      else if (verb === 'door' || verb === 'set') await panel.bringToFront();
      if (GEAR[verb]) { await drive.mouse.click(GEAR[verb], 502); await wait(600); r = `기어 ${verb}`; }
      else if (verb === 'gas') { await hold(147, 623, +rest[0]); r = `가속 ${rest[0]}ms`; }
      else if (verb === 'brake') { await hold(147, 777, +rest[0]); r = `브레이크 ${rest[0]}ms`; }
      else if (verb === 'wait') { await wait(+rest[0]); continue; }
      else if (verb === 'app') { execFileSync(ADB, ['shell', 'am', 'broadcast', '--user', '10', '-a', 'app.prend.RIDE', '--es', 'cmd', rest[0]], { stdio: 'ignore' }); r = `앱 ${rest[0]}`; }
      else if (verb === 'door') r = `문 ${rest[0]} ${rest[1]} ${await door(rest[0], rest[1])}`;
      else if (verb === 'set') r = `${rest[0]} ${rest[1]} ${await card(rest[0], rest[1])}`;
      else if (verb === 'speed') r = '지금';
      const line = `  +${((Date.now() - t0) / 1000).toFixed(1)}s ${r} → ${await status()}`;
      console.log(line); lines.push(line);
    }
    fs.appendFileSync(TIMES, lines.join('\n') + '\n');
    busy = false;
  };

  const help = () => {
    console.log('\nPT 장면 키 (q 끝, h 목록)');
    for (const [k, [name]] of Object.entries(SCENES)) console.log(`  ${k}  ${name}`);
    console.log('');
  };
  // 시험: node ptkeys.js --keys 6x 처럼 키를 차례로 누른 것으로 하고 끝낸다(키 사이 1초)
  const ki = process.argv.indexOf('--keys');
  if (ki > 0) {
    for (const k of process.argv[ki + 1] || '') { await run(k); await wait(1000); }
    await browser.close(); process.exit(0);
  }
  help();
  process.stdin.setRawMode(true);
  process.stdin.resume();
  process.stdin.setEncoding('utf8');
  process.stdin.on('data', async (k) => {
    if (k === 'q' || k === '\u0003') { await browser.close(); process.exit(0); }
    if (k === 'h') return help();
    run(k).catch((e) => { busy = false; console.error(e.message); });
  });
})().catch((e) => { console.error(e); process.exit(1); });
