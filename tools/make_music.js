// 시연용 음원 두 개를 직접 합성한다(저작권 문제 없음).
// 1) lofi_a.wav: 84bpm 로파이 루프. 고음(하이햇·종소리)이 있어 저역 통과 필터 효과가 귀에 들린다.
// 2) rain.wav: 빗소리 결(갈색 잡음 + 물방울). 와이퍼가 돌 때 음악 밑에 얹는다.
// 사용: node make_music.js <출력 폴더>
const fs = require('fs');
const path = require('path');

const SR = 44100;
const out = process.argv[2] || __dirname;

function writeWav(file, L, R) {
  const n = L.length;
  const buf = Buffer.alloc(44 + n * 4);
  buf.write('RIFF', 0); buf.writeUInt32LE(36 + n * 4, 4); buf.write('WAVE', 8);
  buf.write('fmt ', 12); buf.writeUInt32LE(16, 16); buf.writeUInt16LE(1, 20); buf.writeUInt16LE(2, 22);
  buf.writeUInt32LE(SR, 24); buf.writeUInt32LE(SR * 4, 28); buf.writeUInt16LE(4, 32); buf.writeUInt16LE(16, 34);
  buf.write('data', 36); buf.writeUInt32LE(n * 4, 40);
  let peak = 0;
  for (let i = 0; i < n; i++) peak = Math.max(peak, Math.abs(L[i]), Math.abs(R[i]));
  const g = peak > 0 ? 0.89 / peak : 1; // 약 -1dBFS
  for (let i = 0; i < n; i++) {
    buf.writeInt16LE(Math.round(Math.max(-1, Math.min(1, L[i] * g)) * 32767), 44 + i * 4);
    buf.writeInt16LE(Math.round(Math.max(-1, Math.min(1, R[i] * g)) * 32767), 46 + i * 4);
  }
  fs.writeFileSync(file, buf);
  console.log('wrote', file, (n / SR).toFixed(1) + 's');
}

// 결정적 난수(매번 같은 곡)
let seed = 12345;
const rnd = () => ((seed = (seed * 1664525 + 1013904223) >>> 0) / 4294967296);

// ---------- 1) 로파이 루프 ----------
{
  const bpm = 84, beat = 60 / bpm, bar = beat * 4, bars = 22;
  const dur = bar * bars + 1.5;
  const n = Math.floor(dur * SR);
  const L = new Float32Array(n), R = new Float32Array(n);
  const add = (i, l, r) => { if (i >= 0 && i < n) { L[i] += l; R[i] += r; } };

  // 화음: Fmaj7 → Em7 → Dm7 → Cmaj7
  const chords = [
    [174.61, 220.0, 261.63, 329.63],
    [164.81, 196.0, 246.94, 293.66],
    [146.83, 174.61, 220.0, 261.63],
    [130.81, 164.81, 196.0, 246.94],
  ];
  const roots = [87.31, 82.41, 73.42, 65.41];

  // 전자 피아노 비슷한 건반: 배음 10개, 두 박마다 다시 친다
  function keys(t0, freqs, len) {
    freqs.forEach((f, k) => {
      const pan = 0.35 + 0.1 * k;
      const s0 = Math.floor(t0 * SR), s1 = Math.floor((t0 + len) * SR);
      for (let i = s0; i < s1; i++) {
        const t = (i - s0) / SR;
        const env = Math.min(1, t / 0.01) * Math.exp(-t * 1.6);
        let v = 0;
        for (let h = 1; h <= 10; h++) v += Math.sin(2 * Math.PI * f * h * t * (1 + 0.0007 * k)) / Math.pow(h, 1.25);
        v *= 0.05 * env;
        add(i, v * (1 - pan), v * pan);
      }
    });
  }
  // 베이스
  function bass(t0, f, len) {
    const s0 = Math.floor(t0 * SR), s1 = Math.floor((t0 + len) * SR);
    for (let i = s0; i < s1; i++) {
      const t = (i - s0) / SR;
      const env = Math.min(1, t / 0.005) * Math.exp(-t * 2.2);
      const v = (Math.sin(2 * Math.PI * f * t) + 0.35 * Math.sin(4 * Math.PI * f * t)) * 0.22 * env;
      add(i, v, v);
    }
  }
  // 킥
  function kick(t0) {
    const s0 = Math.floor(t0 * SR);
    let ph = 0;
    for (let i = 0; i < 0.35 * SR; i++) {
      const t = i / SR;
      const f = 45 + 75 * Math.exp(-t * 30);
      ph += 2 * Math.PI * f / SR;
      const v = Math.sin(ph) * Math.exp(-t * 9) * 0.55;
      add(s0 + i, v, v);
    }
  }
  // 스네어(잡음 + 낮은 음)
  function snare(t0) {
    const s0 = Math.floor(t0 * SR);
    let prev = 0;
    for (let i = 0; i < 0.25 * SR; i++) {
      const t = i / SR;
      const w = rnd() * 2 - 1;
      const hp = w - prev; prev = w; // 밝게
      const v = (hp * 0.5 + Math.sin(2 * Math.PI * 185 * t) * 0.4) * Math.exp(-t * 18) * 0.22;
      add(s0 + i, v * 0.9, v);
    }
  }
  // 하이햇(밝은 잡음, 짧게)
  function hat(t0, amp) {
    const s0 = Math.floor(t0 * SR);
    let p1 = 0, p2 = 0;
    for (let i = 0; i < 0.06 * SR; i++) {
      const t = i / SR;
      const w = rnd() * 2 - 1;
      const hp = w - 2 * p1 + p2; p2 = p1; p1 = w; // 2차 차분 = 고음만
      const v = hp * Math.exp(-t * 70) * amp;
      add(s0 + i, v, v * 0.8);
    }
  }
  // 종소리 멜로디(고음)
  function bell(t0, f, amp) {
    const s0 = Math.floor(t0 * SR);
    for (let i = 0; i < 1.6 * SR; i++) {
      const t = i / SR;
      const env = Math.min(1, t / 0.003) * Math.exp(-t * 2.8);
      const v = (Math.sin(2 * Math.PI * f * t) + 0.5 * Math.sin(2 * Math.PI * f * 2.76 * t) * Math.exp(-t * 4) + 0.25 * Math.sin(2 * Math.PI * f * 5.4 * t) * Math.exp(-t * 7)) * amp * env;
      add(s0 + i, v * 0.7, v);
    }
  }
  const scale = [523.25, 587.33, 659.25, 783.99, 880.0, 1046.5]; // C5 펜타토닉
  for (let b = 0; b < bars; b++) {
    const t0 = b * bar;
    const c = b % 4;
    keys(t0, chords[c], beat * 2.2);
    keys(t0 + beat * 2, chords[c], beat * 2.2);
    bass(t0, roots[c], beat * 1.8);
    bass(t0 + beat * 2.5, roots[c], beat * 1.3);
    if (b >= 1) { kick(t0); kick(t0 + beat * 2.5); snare(t0 + beat); snare(t0 + beat * 3); }
    if (b >= 2) for (let e = 0; e < 8; e++) hat(t0 + e * beat / 2 + (e % 2 ? 0.035 : 0), e % 2 ? 0.07 : 0.11);
    if (b >= 4 && b < bars - 1) {
      for (let k = 0; k < 4; k++) if (rnd() < 0.6) bell(t0 + k * beat + (rnd() < 0.3 ? beat / 2 : 0), scale[Math.floor(rnd() * scale.length)], 0.09);
    }
  }
  // 끝 페이드
  const fadeN = Math.floor(1.5 * SR);
  for (let i = 0; i < fadeN; i++) { const g = 1 - i / fadeN; L[n - fadeN + i] *= g; R[n - fadeN + i] *= g; }
  writeWav(path.join(out, 'lofi_a.wav'), L, R);
}

// ---------- 2) 빗소리 결 ----------
{
  const dur = 30;
  const n = dur * SR;
  const L = new Float32Array(n), R = new Float32Array(n);
  let bl = 0, br = 0, pl = 0, pr = 0;
  for (let i = 0; i < n; i++) {
    // 갈색 잡음(낮은 쏴아) + 살짝 밝은 잡음
    const wl = rnd() * 2 - 1, wr = rnd() * 2 - 1;
    bl = (bl + 0.02 * wl) * 0.995; br = (br + 0.02 * wr) * 0.995;
    pl = 0.9 * pl + 0.1 * wl; pr = 0.9 * pr + 0.1 * wr;
    L[i] += bl * 1.2 + pl * 0.15;
    R[i] += br * 1.2 + pr * 0.15;
  }
  // 물방울(짧고 높은 톡)
  for (let d = 0; d < dur * 35; d++) {
    const s0 = Math.floor(rnd() * (n - SR * 0.05));
    const f = 1800 + rnd() * 3500, amp = 0.05 + rnd() * 0.12, pan = rnd();
    for (let i = 0; i < 0.03 * SR; i++) {
      const t = i / SR;
      const v = Math.sin(2 * Math.PI * f * t * (1 - t * 8)) * Math.exp(-t * 180) * amp;
      L[s0 + i] += v * (1 - pan); R[s0 + i] += v * pan;
    }
  }
  // 이음매가 덜 튀게 앞뒤 0.8초 페이드
  const fadeN = Math.floor(0.8 * SR);
  for (let i = 0; i < fadeN; i++) { const g = i / fadeN; L[i] *= g; R[i] *= g; L[n - 1 - i] *= g; R[n - 1 - i] *= g; }
  writeWav(path.join(out, 'rain.wav'), L, R);
}
