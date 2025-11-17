# Grizzly-tap
It is simple and joyful game. tap the grizzly bear and protect it from the stone and collect honey. every honey claims 10 points.
<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8" />
<meta name="viewport" content="width=device-width,initial-scale=1,viewport-fit=cover" />
<title>Grizzly Tap — Tap to Jump</title>
<style>
  :root{
    --bg:#fff7e6;
    --ui:#3b3b3b;
    --accent:#f3b342;
  }
  html,body{height:100%;margin:0;font-family:Inter,system-ui,Segoe UI,Roboto,Arial;}
  body{background:linear-gradient(180deg,var(--bg),#fff);display:flex;align-items:center;justify-content:center;}
  #gameWrap{width:100%;max-width:480px;margin:18px;border-radius:14px;box-shadow:0 8px 30px rgba(0,0,0,0.12);overflow:hidden;background:#fafafa;}
  header{display:flex;align-items:center;justify-content:space-between;padding:10px 14px;background:linear-gradient(90deg,#fff,#fff0);border-bottom:1px solid rgba(0,0,0,0.04)}
  .title{font-weight:700;color:var(--ui)} .controls{display:flex;gap:8px;align-items:center}
  button{background:transparent;border:1px solid rgba(0,0,0,0.06);padding:6px 8px;border-radius:8px;font-weight:600}
  canvas{display:block;width:100%;height:520px;background:linear-gradient(180deg,#c8f1ff,#baf0ff 20%,#e6f7ff 40%,#fff);touch-action:manipulation;}
  .hud{position:absolute;left:12px;top:12px;font-weight:700;color:#223;filter:drop-shadow(0 1px 0 rgba(255,255,255,0.6))}
  .scoreBox{position:absolute;right:12px;top:12px;text-align:right;color:#0a2140;font-weight:800}
  .centerMsg{position:absolute;left:0;right:0;top:50%;transform:translateY(-50%);text-align:center;padding:0 20px}
  .btnBig{background:var(--accent);color:#fff;border:0;padding:10px 16px;border-radius:12px;font-weight:800}
  footer{display:flex;justify-content:space-between;padding:8px 14px;color:#444;font-size:13px}
  .small{font-size:12px;color:#666}
  @media (max-width:420px){canvas{height:460px}}
</style>
</head>
<body>
  <div id="gameWrap">
    <header>
      <div class="title">Grizzly Tap</div>
      <div class="controls">
        <button id="soundBtn">🔊</button>
        <button id="pauseBtn">⏸</button>
      </div>
    </header>

    <div style="position:relative">
      <canvas id="game" width="720" height="780"></canvas>

      <div class="hud" id="hud-left">
        <div>🐻 Jump & Collect 🍯</div>
      </div>
      <div class="scoreBox" id="hud-right">
        <div id="score">Score: 0</div>
        <div class="small">High: <span id="high">0</span></div>
      </div>

      <div class="centerMsg" id="centerMsg" style="display:none">
        <h2 id="msgTitle">Tap to Start</h2>
        <p id="msgSub" class="small">Tap anywhere to jump. Avoid rocks 🪨. Collect honey 🍯.</p>
        <div style="margin-top:14px">
          <button id="startBtn" class="btnBig">Start</button>
        </div>
      </div>
    </div>

    <footer>
      <div class="small">Controls: Tap / Click</div>
      <div class="small">Built for mobile & desktop</div>
    </footer>
  </div>

<script>
(() => {
  // Canvas & scaling
  const canvas = document.getElementById('game');
  const ctx = canvas.getContext('2d', { alpha: false });
  const W = canvas.width;
  const H = canvas.height;

  // UI elements
  const centerMsg = document.getElementById('centerMsg');
  const startBtn = document.getElementById('startBtn');
  const msgTitle = document.getElementById('msgTitle');
  const msgSub = document.getElementById('msgSub');
  const scoreEl = document.getElementById('score');
  const highEl = document.getElementById('high');
  const soundBtn = document.getElementById('soundBtn');
  const pauseBtn = document.getElementById('pauseBtn');

  // Game state
  let running = false;
  let paused = false;
  let lastTime = 0;
  let spawnTimer = 0;
  let spawnInterval = 1200; // ms
  let objects = [];
  let particles = [];
  let score = 0;
  let speed = 240; // px/sec base world speed
  let difficultyTimer = 0;

  // Player
  const player = {
    x: 140,
    y: H * 0.65,
    w: 68,
    h: 68,
    vy: 0,
    onGround: false,
    gravity: 1400,
    jumpStrength: -520,
    scale: 1.0,
  };

  // Audio
  let audioOn = true;
  let audioCtx = null;
  function ensureAudio() {
    if (!audioCtx) {
      try { audioCtx = new (window.AudioContext || window.webkitAudioContext)(); } catch(e){ audioCtx = null; audioOn=false; soundBtn.style.opacity=0.5; }
    }
  }
  function beep(freq=440, dur=0.08, type='sine') {
    if (!audioOn) return;
    ensureAudio();
    if (!audioCtx) return;
    const o = audioCtx.createOscillator();
    const g = audioCtx.createGain();
    o.type = type; o.frequency.value = freq;
    g.gain.value = 0.08;
    o.connect(g); g.connect(audioCtx.destination);
    o.start();
    g.gain.exponentialRampToValueAtTime(0.0001, audioCtx.currentTime + dur);
    o.stop(audioCtx.currentTime + dur + 0.02);
  }

  // Utilities
  function rand(min,max){return Math.random()*(max-min)+min;}
  function rectsOverlap(a,b){
    return !(a.x + a.w < b.x || a.x > b.x + b.w || a.y + a.h < b.y || a.y > b.y + b.h);
  }

  // Local high score
  const HS_KEY = 'grizzly_highscore_v1';
  function loadHigh(){ const v = parseInt(localStorage.getItem(HS_KEY)||'0',10); highEl.textContent = v; return v; }
  function saveHigh(v){ localStorage.setItem(HS_KEY, String(v)); }

  // Start & reset
  function showCenter(title, sub, startText='Start'){
    msgTitle.textContent = title;
    msgSub.textContent = sub;
    startBtn.textContent = startText;
    centerMsg.style.display = 'block';
  }
  function hideCenter(){ centerMsg.style.display = 'none'; }
  function resetGame(){
    objects = [];
    particles = [];
    score = 0;
    speed = 240;
    difficultyTimer = 0;
    spawnTimer = 0;
    player.y = H * 0.65;
    player.vy = 0;
    player.onGround = false;
    player.scale = 1;
    scoreEl.textContent = 'Score: 0';
    loadHigh();
    running = true;
    paused = false;
    hideCenter();
    lastTime = performance.now();
    loop(lastTime);
  }

  // Spawn objects: rocks (obstacle) and honey (collectible)
  function spawnObject(){
    const isHoney = Math.random() < 0.45;
    const size = isHoney ? 44 : Math.round(rand(48,88));
    const yPos = isHoney ? rand(H*0.35, H*0.6) : (H*0.72 - size);
    const obj = {
      type: isHoney ? 'honey' : 'rock',
      x: W + 60,
      y: yPos,
      w: size,
      h: size,
      vx: -speed * (1 + rand(0,0.15)),
      collected: false
    };
    objects.push(obj);
  }

  // Particle (small pop)
  function addParticles(x,y,count=8){
    for(let i=0;i<count;i++){
      particles.push({
        x,y,
        vx: rand(-160,160),
        vy: rand(-340,-80),
        t: rand(400,900),
        size: rand(4,9),
        started: performance.now()
      });
    }
  }

  // Input (tap/click)
  function jump(){
    if (!running) { resetGame(); return; }
    if (paused) { paused = false; pauseBtn.textContent='⏸'; lastTime = performance.now(); loop(lastTime); return; }
    // jump only when on or near ground (allow small coyote time)
    if (player.onGround || player.vy > -20) {
      player.vy = player.jumpStrength;
      player.onGround = false;
      beep(680,0.06,'sine');
    }
  }

  // Touch & mouse
  canvas.addEventListener('pointerdown', (e) => { e.preventDefault(); jump(); });
  startBtn.addEventListener('click', (e)=>{ e.preventDefault(); resetGame(); });
  soundBtn.addEventListener('click', ()=>{
    audioOn = !audioOn;
    soundBtn.textContent = audioOn?'🔊':'🔇';
    if(audioOn) { ensureAudio(); if (audioCtx && audioCtx.state === 'suspended') audioCtx.resume(); }
  });
  pauseBtn.addEventListener('click', ()=>{
    if (!running) return;
    paused = !paused;
    pauseBtn.textContent = paused ? '▶️' : '⏸';
    if (!paused){ lastTime = performance.now(); loop(lastTime); }
  });

  // Game loop
  function loop(ts){
    if (!running) return;
    if (paused) return;
    const dt = Math.min(40, ts - lastTime) / 1000; // seconds, clamp
    lastTime = ts;

    // update difficulty
    difficultyTimer += dt * 1000;
    if (difficultyTimer > 8000) {
      difficultyTimer = 0;
      speed *= 1.06; // increase speed over time
      if (spawnInterval > 700) spawnInterval -= 50;
    }

    // spawn
    spawnTimer += dt*1000;
    if (spawnTimer > spawnInterval) {
      spawnTimer = 0;
      spawnObject();
    }

    // physics
    player.vy += player.gravity * dt;
    player.y += player.vy * dt;
    if (player.y + player.h/2 >= H*0.72) {
      player.y = H*0.72 - player.h/2;
      player.vy = 0;
      player.onGround = true;
    } else {
      player.onGround = false;
    }

    // update objects
    for (let i = objects.length-1; i>=0; i--){
      const o = objects[i];
      o.x += o.vx * dt;
      // hitbox simplified
      const pbox = { x: player.x - player.w/2, y: player.y - player.h/2, w: player.w, h: player.h };
      const obox = { x: o.x - o.w/2, y: o.y - o.h/2, w: o.w, h: o.h };
      if (!o.collected && rectsOverlap(pbox, obox)) {
        if (o.type === 'honey') {
          o.collected = true;
          score += 10;
          addParticles(o.x, o.y - 10, 10);
          beep(880,0.06,'triangle');
        } else if (o.type === 'rock') {
          // collision -> game over
          running = false;
          beep(120,0.3,'sawtooth');
          showCenter('Game Over','Tap to restart','Restart');
          // save highscore
          const old = loadHigh();
          if (score > old) { saveHigh(score); highEl.textContent = score; }
          return;
        }
      }
      // remove off-screen or collected
      if (o.x + o.w/2 < -60 || o.collected && o.x < -10) objects.splice(i,1);
    }

    // update particles
    const now = performance.now();
    for (let i=particles.length-1;i>=0;i--){
      const q = particles[i];
      const t = now - q.started;
      if (t > q.t) { particles.splice(i,1); continue; }
      q.vy += 1000 * dt;
      q.x += q.vx * dt;
      q.y += q.vy * dt;
    }

    // score slowly increases
    score += Math.floor(2 * dt * (speed / 240));
    scoreEl.textContent = 'Score: ' + score;

    // clear
    ctx.clearRect(0,0,W,H);

    // draw sky / ground
    // ground band
    ctx.fillStyle = '#eaf7ff';
    ctx.fillRect(0, H*0.72, W, H*0.28);
    ctx.fillStyle = '#efd1a4';
    ctx.fillRect(0, H*0.78, W, H*0.04);

    // draw objects (rocks/honey) - using emoji draw via font
    ctx.textAlign = 'center';
    ctx.textBaseline = 'middle';
    for (const o of objects) {
      if (o.type === 'honey') {
        ctx.font = `${Math.round(o.w)}px serif`;
        ctx.fillText('🍯', o.x, o.y);
      } else {
        ctx.font = `${Math.round(o.w)}px serif`;
        ctx.fillText('🪨', o.x, o.y + (o.h*0.06));
      }
    }

    // draw player (bear) - animate slight squash when landing
    const squish = Math.max(0.9, 1 - Math.abs(player.vy) / 1200);
    const pw = player.w * squish;
    const ph = player.h / squish;
    ctx.save();
    ctx.translate(player.x, player.y);
    ctx.font = `${Math.round(player.h * 1.0)}px serif`;
    ctx.fillText('🐻', 0, 0);
    ctx.restore();

    // draw particles (small circles)
    for (const q of particles) {
      ctx.beginPath();
      ctx.globalAlpha = Math.max(0, 1 - ((performance.now() - q.started) / q.t));
      ctx.arc(q.x, q.y, q.size, 0, Math.PI*2);
      ctx.fillStyle = '#ffd36b';
      ctx.fill();
      ctx.globalAlpha = 1;
    }

    // small HUD text (draw high score top left on canvas too)
    ctx.fillStyle = '#0b2b45';
    ctx.font = '18px Inter, sans-serif';
    ctx.fillText('High: ' + (localStorage.getItem(HS_KEY)||0), W - 80, 34);

    requestAnimationFrame(loop);
  }

  // initial screen
  showCenter('Tap to Start','Tap anywhere to jump. Avoid rocks 🪨 and collect honey 🍯.','Start');

  // load highscore into UI
  loadHigh();

  // small friendly hint if page hidden -> resume on focus
  document.addEventListener('visibilitychange', ()=> {
    if (document.visibilityState === 'hidden' && running && !paused) {
      paused = true;
      pauseBtn.textContent = '▶️';
    }
  });

  // keyboard support (space)
  window.addEventListener('keydown', (e)=>{
    if (e.code === 'Space') { e.preventDefault(); jump(); }
    if (e.code === 'KeyP') { paused = !paused; pauseBtn.textContent = paused ? '▶️' : '⏸'; if (!paused) lastTime = performance.now(); }
  });

  // friendly resize handler for crisp canvas on high DPR
  function updateCanvasSize(){
    const dpr = Math.max(window.devicePixelRatio || 1, 1);
    const clientW = Math.min(window.innerWidth - 40, 480);
    const clientH = Math.min(window.innerHeight - 120, 780);
    canvas.style.width = clientW + 'px';
    canvas.style.height = clientH + 'px';
    canvas.width = Math.round(clientW * dpr);
    canvas.height = Math.round(clientH * dpr);
    ctx.setTransform(dpr,0,0,dpr,0,0);
  }
  updateCanvasSize();
  window.addEventListener('resize', updateCanvasSize);

})();
</script>
</body>
</html>
