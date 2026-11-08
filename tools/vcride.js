// 한 운행을 차례대로 몬다: 콘솔 주행(기어·가속·브레이크·핸들), 문, 기능 카드 버튼, 앱 시험 명령을 한 줄씩.
// 사용: node vcride.js <단계> <단계> ...
//   P|R|N|D            기어 버튼
//   gas:ms  brake:ms   페달을 그만큼 밟고 뗀다
//   wait:ms            기다린다
//   steer:right|center 핸들 슬라이더
//   door:Rear left:Open        Door Position 카드(문: Front left|Front right|Rear left|Rear right, Open|Close)
//   window:Front left:60       Window Position 카드(자리, 열림 0~100%). PT 킥의 '운전석 창 0 → 60%'
//   set:Turn Signal State:Off  Emulation Controls 기능 카드의 버튼
//   app:cmd            앱 시험 명령(adb broadcast app.prend.RIDE). PT 시연 버튼은 app:pt_pair·pt_basement·pt_night_kid·pt_drive·pt_values_on
// PT 킥 예: node vcride.js app:pt_values_on app:pt_drive D gas:1500 "window:Front left:60" wait:2000 "window:Front left:0" gas:4000
//   speed              지금 속도·기어·핸들을 적는다
//   click:x:y          주행 화면의 그 자리를 누른다(공조 AIR MODE 등)
//   read:WORD          주행 화면에서 그 말 근처 글을 적는다(예: read:AIR MODE)
// 좌표는 1600×1000 화면 기준(drive.js와 같음). 문·기능 카드는 두 번째 탭의 Emulation Controls에서 누른다.
const { execFileSync } = require('child_process');
const puppeteer = require('puppeteer-core');
const ADB = process.env.ADB || 'adb';
const wait = (ms) => new Promise((r) => setTimeout(r, ms));
const GEAR = { P: 1439, R: 1473, N: 1507, D: 1540 };

(async () => {
  // 문·기능 카드 탭을 먼저 열면 뒤에 놓인 주행 탭의 타이머가 늦춰져 가속이 먹지 않았다(11/07): 늦추지 않게 하고, 누를 탭을 앞으로 가져온다
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
  let panel = null;
  const controls = async () => {
    if (!panel) { panel = await open(); await panel.mouse.click(222, 480); await wait(1200); }
    await panel.bringToFront();
    return panel;
  };
  const status = () => drive.evaluate(() => {
    const t = document.body.innerText;
    const m = t.match(/SPEED\s*\n\s*([\d.]+)\s*km\/h/);
    const g = t.match(/GEAR\s*\n\s*([PRND])/);
    const s = t.match(/STEERING\s*\n\s*(-?[\d.]+)\s*deg/);
    return `${m ? m[1] : '?'}km/h ${g ? g[1] : '?'} 핸들 ${s ? s[1] : '?'}°`;
  });
  const hold = async (x, y, ms) => { await drive.mouse.move(x, y); await drive.mouse.down(); await wait(ms); await drive.mouse.up(); };
  const t0 = Date.now();
  const log = async (what) => console.log(`${new Date().toTimeString().slice(0, 8)} +${((Date.now() - t0) / 1000).toFixed(1)}s ${what} → ${await status()}`);

  for (const step of process.argv.slice(2)) {
    const [verb, ...rest] = step.split(':');
    const arg = rest.join(':');
    if (GEAR[verb] || verb === 'gas' || verb === 'brake' || verb === 'steer') await drive.bringToFront();
    if (GEAR[verb]) { await drive.mouse.click(GEAR[verb], 502); await wait(800); await log(`기어 ${verb}`); }
    else if (verb === 'gas') { await hold(147, 623, +arg); await log(`가속 ${arg}ms`); }
    else if (verb === 'brake') { await hold(147, 777, +arg); await log(`브레이크 ${arg}ms`); }
    else if (verb === 'wait') { await wait(+arg); }
    else if (verb === 'steer') { await drive.mouse.click(arg === 'right' ? 250 : 196, 940); await wait(800); await log(`핸들 ${arg}`); }
    else if (verb === 'speed') { await log('지금'); }
    else if (verb === 'click') { const [x, y] = rest.map(Number); await drive.mouse.click(x, y); await wait(600); await log(`누름 ${x},${y}`); }
    else if (verb === 'read') { const t = await drive.evaluate((w) => { const s = document.body.innerText; const i = s.indexOf(w); return i < 0 ? '(없음)' : s.slice(i, i + 60).split('\n').join(' / '); }, arg); console.log(`  읽음: ${t}`); }
    else if (verb === 'app') {
      execFileSync(ADB, ['shell', 'am', 'broadcast', '--user', '10', '-a', 'app.prend.RIDE', '--es', 'cmd', arg], { stdio: 'ignore' });
      await log(`앱 명령 ${arg}`);
    } else if (verb === 'door') {
      const [door, action] = rest;
      const p = await controls();
      const val = await p.evaluate((door) => {
        const head = [...document.querySelectorAll('*')].find((e) => e.childElementCount === 0 && e.textContent.trim() === 'Door Position');
        if (!head) return null;
        for (let box = head.parentElement; box; box = box.parentElement) {
          const sel = box.querySelector('select');
          if (sel) { sel.setAttribute('data-door', '1'); const o = [...sel.options].find((x) => x.textContent.trim() === door); return o ? o.value : null; }
        }
        return null;
      }, door);
      let ok = 'no-door';
      if (val != null) {
        await p.select('select[data-door]', val);
        await wait(300);
        ok = await p.evaluate((action) => {
          const head = [...document.querySelectorAll('*')].find((e) => e.childElementCount === 0 && e.textContent.trim() === 'Door Position');
          for (let box = head.parentElement; box; box = box.parentElement) {
            const btn = [...box.querySelectorAll('button, [role=button]')].find((b) => b.textContent.trim() === action);
            if (btn) { btn.click(); return 'ok'; }
          }
          return 'no-button';
        }, action);
      }
      await wait(500);
      await log(`문 ${door} ${action} ${ok}`);
    } else if (verb === 'window') {
      // vcwindow.js와 같은 법: 카드의 자리 고르기를 바꾸고 슬라이더 값을 넣은 뒤 입력·놓기 이벤트를 보낸다
      const [seat, pct] = rest;
      const p = await controls();
      const ok = await p.evaluate(async (seat, pct) => {
        const art = [...document.querySelectorAll('article')].find((a) => a.querySelector('h3')?.textContent.trim() === 'Window Position');
        if (!art) return 'no-card';
        const set = (el, proto, v) => {
          Object.getOwnPropertyDescriptor(proto, 'value').set.call(el, v);
          el.dispatchEvent(new Event('input', { bubbles: true }));
          el.dispatchEvent(new Event('change', { bubbles: true }));
        };
        const sel = art.querySelector('select');
        if (sel) {
          const opt = [...sel.options].find((o) => o.textContent.trim() === seat);
          if (!opt) return 'no-seat';
          set(sel, HTMLSelectElement.prototype, opt.value);
        }
        await new Promise((r) => setTimeout(r, 300));
        const range = art.querySelector('input[type=range]');
        if (!range) return 'no-range';
        set(range, HTMLInputElement.prototype, String(pct));
        range.dispatchEvent(new PointerEvent('pointerup', { bubbles: true }));
        range.dispatchEvent(new MouseEvent('mouseup', { bubbles: true }));
        return 'ok';
      }, seat, pct);
      await wait(500);
      await log(`창 ${seat} ${pct}% ${ok}`);
    } else if (verb === 'set') {
      const [title, option] = rest;
      const p = await controls();
      const ok = await p.evaluate((title, option) => {
        const head = [...document.querySelectorAll('*')].find((e) => e.childElementCount === 0 && e.textContent.trim() === title);
        if (!head) return 'no-title';
        for (let box = head.parentElement; box; box = box.parentElement) {
          const btn = [...box.querySelectorAll('button, [role=button]')].find((b) => b.textContent.trim() === option);
          if (btn) { btn.scrollIntoView({ block: 'center' }); btn.click(); return 'ok'; }
        }
        return 'no-option';
      }, title, option);
      await wait(500);
      await log(`${title} ${option} ${ok}`);
    } else console.log(`모르는 단계: ${step}`);
  }
  await browser.close();
})().catch((e) => { console.error(e); process.exit(1); });
