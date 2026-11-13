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
//   tap:x:y            앱 화면의 그 자리를 누른다(앱 영역 1680×1210 기준, 에뮬레이터 화면에서는 왼쪽 계기판만큼 옮겨 누른다)
//   until:글:초        앱 기록에 그 글이 새로 찍힐 때까지 기다린다(기본 20초까지). 장면이 앱의 실제 판단을 따라가게
//   jolt:1.5           충격(g): 가상 차의 가속도 센서 값을 잠깐 옆으로 튀게 한다(접촉 사고)
//   mark:글            지금 시각을 적는다(시연 영상의 자막 시각표용)
//   shot:이름          에뮬레이터 화면을 VCSHOTS 폴더(없으면 tools/vcshots)에 찍어 둔다
// 좌표는 1600×1000 화면 기준(drive.js와 같음). 문·기능 카드는 두 번째 탭의 Emulation Controls에서 누른다.
const fs = require('fs');
const path = require('path');
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

  // 도중에 오류가 나도 브라우저를 닫는다: 남은 창 없는 크롬이 페달을 밟은 채 조종판을 붙잡아 다음 실행의 가속이 먹지 않았다(11/11)
  try {
  for (const step of process.argv.slice(2)) {
    const [verb, ...rest] = step.split(':');
    const arg = rest.join(':');
    if (GEAR[verb] || verb === 'gas' || verb === 'brake' || verb === 'steer') await drive.bringToFront();
    if (GEAR[verb]) { await drive.mouse.click(GEAR[verb], 502); await wait(800); await log(`기어 ${verb}`); }
    else if (verb === 'gas') {
      // 가끔 주행 탭이 뒤에 있던 탓에 페달이 먹지 않는다(속도가 0 그대로): 탭을 다시 앞으로 가져와 두 번까지 더 밟는다
      for (let k = 0; k < 3; k++) {
        await hold(147, 623, +arg);
        if (!/^(0|\?)km\/h/.test(await status())) break;
        await drive.bringToFront(); await wait(700);
      }
      await log(`가속 ${arg}ms`);
    }
    else if (verb === 'brake') { await hold(147, 777, +arg); await log(`브레이크 ${arg}ms`); }
    else if (verb === 'wait') { await wait(+arg); }
    else if (verb === 'steer') { await drive.mouse.click(arg === 'right' ? 250 : 196, 940); await wait(800); await log(`핸들 ${arg}`); }
    else if (verb === 'speed') { await log('지금'); }
    else if (verb === 'warm') {
      // 문·기능 카드 탭을 처음 열면 한동안 주행 탭의 페달이 먹지 않는다(짧게는 몇 초, 길게는 1분): 미리 열어 두고 속도가 붙을 때까지 살짝 밟아 본 뒤 세운다
      await controls();
      await drive.bringToFront();
      let ok = false;
      for (let k = 0; k < 40 && !ok; k++) {
        await drive.mouse.click(GEAR.D, 502); await wait(600);
        await hold(147, 623, 1200);
        ok = !/^(0|\?)km\/h/.test(await status());
        if (!ok) await wait(1500);
      }
      await hold(147, 777, 3000); await drive.mouse.click(GEAR.P, 502); await wait(800);
      await log(ok ? '조종판 준비 끝' : '조종판 준비 실패(페달이 먹지 않음)');
    } else if (verb === 'pause') {
      // 사람이 Enter를 누를 때까지 기다린다(녹화를 시작할 틈)
      console.log(`\n▶ ${arg}`);
      await new Promise((r) => { process.stdin.resume(); process.stdin.once('data', () => { process.stdin.pause(); r(); }); });
    }
    else if (verb === 'tap') {
      // 앱 영역은 에뮬레이터 화면(2560×1440)의 (854, 90)에서 시작한다
      const [x, y] = rest.map(Number);
      execFileSync(ADB, ['shell', 'input', 'tap', String(x + 854), String(y + 90)], { stdio: 'ignore' });
      await log(`앱 누름 ${x},${y}`);
    } else if (verb === 'mark') { await log(`── ${arg}`); }
    else if (verb === 'shot') {
      const dir = process.env.VCSHOTS || path.join(__dirname, 'vcshots');
      fs.mkdirSync(dir, { recursive: true });
      fs.writeFileSync(path.join(dir, `${arg}.png`), execFileSync(ADB, ['exec-out', 'screencap', '-p'], { maxBuffer: 64 * 1024 * 1024 }));
      await log(`화면 ${arg}`);
    } else if (verb === 'until') {
      // 앱 기록(PREND 꼬리표)에서 그 글이 든 줄 수가 늘 때까지. 기록에는 위치·들은 말이 없다(길이만)
      // 끝 조각이 숫자일 때만 초로 본다(글 안에 ':'가 있어도 되게)
      const sec = /^\d+$/.test(rest[rest.length - 1] || '') && rest.length > 1 ? rest.pop() : '20';
      const text = rest.join(':');
      const count = () => execFileSync(ADB, ['logcat', '-d', '-s', 'PREND:I'], { maxBuffer: 64 * 1024 * 1024 }).toString('utf8').split(text).length - 1;
      const base = count(), end = Date.now() + +sec * 1000;
      let ok = false;
      while (Date.now() < end) { await wait(400); if (count() > base) { ok = true; break; } }
      await log(`${ok ? '기다림 끝' : '기다렸지만 안 찍힘'}: ${text}`);
    }
    else if (verb === 'jolt') {
      // 충격: 가상 차의 가속도 센서 값을 1.2초 동안 옆으로(0.4초까지는 가상 차가 값을 전하지 못했다) 튀게 했다가 되돌린다(앱은 이 센서로 충격을 읽는다, car/Jolt). 크기는 g(기본 1.5)
      const g = +arg || 1.5;
      const m = execFileSync(ADB, ['emu', 'sensor', 'get', 'acceleration-uncalibrated']).toString().match(/=\s*(-?[\d.]+):(-?[\d.]+):(-?[\d.]+)/);
      const b = m && Math.abs(+m[1]) < 3 ? m.slice(1).map(Number) : [0, 9.776, 0.812];
      execFileSync(ADB, ['emu', 'sensor', 'set', 'acceleration-uncalibrated', `${(b[0] + g * 9.81).toFixed(2)}:${b[1]}:${b[2]}`], { stdio: 'ignore' });
      await wait(1200);
      execFileSync(ADB, ['emu', 'sensor', 'set', 'acceleration-uncalibrated', b.join(':')], { stdio: 'ignore' });
      await log(`충격 ${g}g`);
    }
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
        // 카드가 그사이 다시 그려지면 표시해 둔 고르기 칸이 사라진다: 값을 직접 넣고 바뀜을 알린다(창문 카드와 같은 법)
        const picked = await p.evaluate((door) => {
          const head = [...document.querySelectorAll('*')].find((e) => e.childElementCount === 0 && e.textContent.trim() === 'Door Position');
          for (let box = head && head.parentElement; box; box = box.parentElement) {
            const sel = box.querySelector('select');
            if (!sel) continue;
            const o = [...sel.options].find((x) => x.textContent.trim() === door);
            if (!o) return false;
            Object.getOwnPropertyDescriptor(HTMLSelectElement.prototype, 'value').set.call(sel, o.value);
            sel.dispatchEvent(new Event('input', { bubbles: true }));
            sel.dispatchEvent(new Event('change', { bubbles: true }));
            return true;
          }
          return false;
        }, door);
        await wait(300);
        ok = !picked ? 'no-select' : await p.evaluate((action) => {
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
  } finally {
    await drive.mouse.up().catch(() => {});
    await browser.close().catch(() => {});
  }
})().catch((e) => { console.error(e); process.exit(1); });
