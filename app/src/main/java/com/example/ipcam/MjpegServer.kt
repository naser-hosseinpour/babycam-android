package com.example.ipcam

import android.util.Base64
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import kotlin.concurrent.thread

/**
 * A tiny HTTP server for the camera stream and its controls.
 *
 *   /            control page
 *   /video       MJPEG stream (multipart/x-mixed-replace)
 *   /jpg         single JPEG snapshot
 *   /status      JSON state
 *   /control     ?camera=on|off  &sensitivity=0..100  &rotate=1  &rotation=..
 *                &brightness=100..300  &zoom=100..  &zone=l,t,r,b (percent)
 */
class MjpegServer(
    private val port: Int,
    private val authUser: String,
    private val authPassword: String,
    private val hub: StreamHub
) {
    @Volatile private var running = false
    private var serverSocket: ServerSocket? = null
    private val boundary = "frameboundary"

    fun start() {
        running = true
        thread(name = "mjpeg-accept") {
            try {
                val ss = ServerSocket(port)
                serverSocket = ss
                while (running) {
                    val client = try { ss.accept() } catch (e: Exception) { break }
                    thread(name = "mjpeg-client") { handle(client) }
                }
            } catch (_: Exception) {}
        }
    }

    fun stop() {
        running = false
        try { serverSocket?.close() } catch (_: Exception) {}
    }

    private fun authOk(headers: Map<String, String>): Boolean {
        if (authUser.isEmpty() && authPassword.isEmpty()) return true
        val header = headers["authorization"] ?: return false
        if (!header.startsWith("Basic ", ignoreCase = true)) return false
        return try {
            val decoded = String(Base64.decode(header.substring(6).trim(), Base64.DEFAULT))
            decoded == "$authUser:$authPassword"
        } catch (_: Exception) { false }
    }

    private fun handle(socket: Socket) {
        try {
            socket.use { s ->
                val reader = BufferedReader(InputStreamReader(s.getInputStream()))
                val requestLine = reader.readLine() ?: return
                val target = requestLine.split(" ").getOrNull(1) ?: "/"
                val qIndex = target.indexOf('?')
                val path = if (qIndex >= 0) target.substring(0, qIndex) else target
                val query = if (qIndex >= 0) target.substring(qIndex + 1) else ""

                val headers = HashMap<String, String>()
                while (true) {
                    val line = reader.readLine() ?: break
                    if (line.isEmpty()) break
                    val idx = line.indexOf(':')
                    if (idx > 0) headers[line.substring(0, idx).trim().lowercase()] =
                        line.substring(idx + 1).trim()
                }

                val out = s.getOutputStream()

                if (!authOk(headers)) {
                    val body = "Authentication required"
                    out.write(
                        ("HTTP/1.1 401 Unauthorized\r\n" +
                         "WWW-Authenticate: Basic realm=\"BabyCam\"\r\n" +
                         "Content-Type: text/plain\r\n" +
                         "Content-Length: ${body.length}\r\n" +
                         "Connection: close\r\n\r\n" + body).toByteArray()
                    )
                    out.flush()
                    return
                }

                when (path) {
                    "/video" -> streamMjpeg(out)
                    "/jpg", "/photo" -> sendSnapshot(out)
                    "/status" -> sendStatus(out)
                    "/control" -> { applyControl(query); sendStatus(out) }
                    else -> sendIndex(out)
                }
            }
        } catch (_: Exception) {}
    }

    private fun parseQuery(query: String): Map<String, String> {
        val map = HashMap<String, String>()
        if (query.isEmpty()) return map
        for (pair in query.split('&')) {
            val eq = pair.indexOf('=')
            if (eq > 0) {
                val k = pair.substring(0, eq)
                val v = try { URLDecoder.decode(pair.substring(eq + 1), "UTF-8") } catch (_: Exception) { "" }
                map[k] = v
            }
        }
        return map
    }

    private fun applyControl(query: String) {
        val p = parseQuery(query)
        p["camera"]?.let { v ->
            when (v.lowercase()) {
                "on", "1", "true" -> hub.applyCamera(true)
                "off", "0", "false" -> hub.applyCamera(false)
                "toggle" -> hub.applyCamera(!hub.cameraOn)
            }
        }
        p["sensitivity"]?.toIntOrNull()?.let { hub.applySensitivity(it) }
        p["rotation"]?.toIntOrNull()?.let { hub.applyRotation(it) }
        if (p.containsKey("rotate")) hub.rotate90()
        p["brightness"]?.toIntOrNull()?.let { hub.applyBrightness(it) }
        p["zoom"]?.toIntOrNull()?.let { hub.applyZoom(it) }
        p["focus"]?.toIntOrNull()?.let { hub.applyFocus(it) }
        p["facing"]?.let { v ->
            when (v.lowercase()) {
                "front", "1" -> hub.applyFacing(true)
                "back", "0" -> hub.applyFacing(false)
                "toggle" -> hub.toggleFacing()
            }
        }
        p["zone"]?.let { z ->
            val a = z.split(',').mapNotNull { it.trim().toIntOrNull() }
            if (a.size == 4) hub.applyZone(a[0], a[1], a[2], a[3])
        }
    }

    private fun sendStatus(out: OutputStream) {
        val json = buildString {
            append("{")
            append("\"camera\":${hub.cameraOn},")
            append("\"motion\":${hub.motion},")
            append("\"level\":${hub.motionLevel},")
            append("\"sensitivity\":${hub.sensitivity},")
            append("\"rotation\":${hub.rotation},")
            append("\"brightness\":${hub.brightness},")
            append("\"zoom\":${hub.zoom},")
            append("\"focus\":${hub.focus},")
            append("\"front\":${hub.useFront},")
            append("\"zone\":[${hub.zoneLeft},${hub.zoneTop},${hub.zoneRight},${hub.zoneBottom}],")
            append("\"box\":[${if (hub.hasBox) 1 else 0},${hub.boxLeft},${hub.boxTop},${hub.boxRight},${hub.boxBottom}]")
            append("}")
        }
        val bytes = json.toByteArray()
        out.write(
            ("HTTP/1.1 200 OK\r\n" +
             "Content-Type: application/json\r\n" +
             "Cache-Control: no-cache\r\n" +
             "Content-Length: ${bytes.size}\r\n" +
             "Connection: close\r\n\r\n").toByteArray()
        )
        out.write(bytes)
        out.flush()
    }

    private fun sendIndex(out: OutputStream) {
        val bytes = INDEX_HTML.toByteArray()
        out.write(
            ("HTTP/1.1 200 OK\r\n" +
             "Content-Type: text/html; charset=utf-8\r\n" +
             "Content-Length: ${bytes.size}\r\n" +
             "Connection: close\r\n\r\n").toByteArray()
        )
        out.write(bytes)
        out.flush()
    }

    private fun sendSnapshot(out: OutputStream) {
        val frame = hub.latestJpeg
        if (frame == null) {
            out.write("HTTP/1.1 503 Service Unavailable\r\nConnection: close\r\n\r\n".toByteArray())
            out.flush()
            return
        }
        out.write(
            ("HTTP/1.1 200 OK\r\n" +
             "Content-Type: image/jpeg\r\n" +
             "Content-Length: ${frame.size}\r\n" +
             "Connection: close\r\n\r\n").toByteArray()
        )
        out.write(frame)
        out.flush()
    }

    private fun streamMjpeg(out: OutputStream) {
        out.write(
            ("HTTP/1.1 200 OK\r\n" +
             "Connection: close\r\n" +
             "Cache-Control: no-cache\r\n" +
             "Content-Type: multipart/x-mixed-replace; boundary=$boundary\r\n\r\n").toByteArray()
        )
        out.flush()
        try {
            while (running) {
                val frame = hub.latestJpeg
                if (frame == null) { Thread.sleep(50); continue }
                out.write(
                    ("--$boundary\r\n" +
                     "Content-Type: image/jpeg\r\n" +
                     "Content-Length: ${frame.size}\r\n\r\n").toByteArray()
                )
                out.write(frame)
                out.write("\r\n".toByteArray())
                out.flush()
                Thread.sleep(66) // ~15 fps cap
            }
        } catch (_: Exception) {
            // client disconnected
        }
    }

    companion object {
        private val INDEX_HTML = """
<!doctype html>
<html>
<head>
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>BabyCam</title>
<style>
  body{margin:0;background:#111;color:#eee;font-family:sans-serif;text-align:center}
  #wrap{max-width:900px;margin:0 auto;padding:10px}
  #stage{position:relative;display:inline-block;max-width:100%;touch-action:none}
  img{display:block;max-width:100%;height:auto;background:#000;border-radius:8px}
  #zonebox{position:absolute;border:2px dashed #38bdf8;pointer-events:none;display:none;box-sizing:border-box}
  #motbox{position:absolute;border:2px solid #ef4444;pointer-events:none;display:none;box-sizing:border-box}
  .row{display:flex;align-items:center;gap:14px;justify-content:center;margin:12px 0;flex-wrap:wrap}
  button{padding:10px 18px;font-size:16px;border:0;border-radius:8px;background:#2563eb;color:#fff}
  button:active{background:#1d4ed8}
  #alarmBtn.on{background:#16a34a}
  #title{font-size:22px;font-weight:bold;margin:8px 0}
  #dot{display:inline-block;width:14px;height:14px;border-radius:50%;background:#444;vertical-align:middle}
  #motion.on #dot{background:#e11}
  input[type=range]{width:200px}
  label{font-size:15px}
  #power{font-size:18px;padding:14px 24px;min-width:260px}
  #power.on{background:#16a34a}
  #power.off{background:#b91c1c}
  .hint{font-size:13px;color:#9aa}
  :root{--amp:12}
  @keyframes shake{
    0%{transform:translate(0,0)}
    20%{transform:translate(calc(var(--amp)*-1px),calc(var(--amp)*1px))}
    40%{transform:translate(calc(var(--amp)*1px),calc(var(--amp)*-1px))}
    60%{transform:translate(calc(var(--amp)*-1px),calc(var(--amp)*-1px))}
    80%{transform:translate(calc(var(--amp)*1px),calc(var(--amp)*1px))}
    100%{transform:translate(0,0)}
  }
  #stage.shake{animation:shake .18s infinite}
</style>
</head>
<body>
<div id="wrap">
  <div id="title">&#128118; BabyCam</div>
  <div class="row">
    <button id="power" class="off" onclick="toggleCam()">Camera</button>
  </div>
  <div id="stage">
    <img src="/video" id="cam"/>
    <div id="zonebox"></div>
    <div id="motbox"></div>
  </div>
  <div class="hint">Drag on the image to set the motion zone.</div>
  <div class="row">
    <button onclick="rotate()">Rotate 90&deg;</button>
    <button id="facing" onclick="switchCam()">Camera: Back</button>
    <button onclick="clearZone()">Clear zone</button>
    <span id="motion"><span id="dot"></span> <span id="mtext">&mdash;</span></span>
  </div>
  <div class="row">
    <button id="alarmBtn" onclick="toggleAlarm()">&#128276; Motion alarm: OFF</button>
    <button onclick="buzz()">&#128276; Buzz</button>
  </div>
  <div class="hint">Turn the alarm ON so this page sounds a siren when the baby moves. Tap Buzz to test.</div>
  <div class="row">
    <label>Sensitivity: <b id="sval">50</b></label>
    <input type="range" min="0" max="100" value="50" id="sens" oninput="setSens(this.value)">
  </div>
  <div class="row">
    <label>Shake on motion: <b id="shval">12</b></label>
    <input type="range" min="0" max="30" value="12" id="shake" oninput="setShake(this.value)">
  </div>
  <div class="row">
    <label>Brightness: <b id="bval">100</b>%</label>
    <input type="range" min="100" max="300" value="100" id="bright" oninput="setBright(this.value)">
  </div>
  <div class="row">
    <label>Zoom: <b id="zval">1.0</b>x</label>
    <input type="range" min="100" max="400" value="100" id="zoom" oninput="setZoom(this.value)">
  </div>
  <div class="row">
    <label>Focus (far &rarr; near): <b id="fval">Auto</b></label>
    <input type="range" min="0" max="100" value="0" id="focus" oninput="setFocus(this.value)">
    <button onclick="setFocusAuto()">Auto</button>
  </div>
  <div class="hint">Drag Focus toward "far" (left) to keep distant things sharp.</div>
</div>
<script>
  var camOn = false, dragging = false, sx = 0, sy = 0;
  var stage = document.getElementById('stage');
  var zonebox = document.getElementById('zonebox');
  var motbox = document.getElementById('motbox');

  function toggleCam(){ fetch('/control?camera=' + (camOn ? 'off' : 'on')); }
  function rotate(){ fetch('/control?rotate=1'); }
  function clearZone(){ fetch('/control?zone=0,0,100,100'); }
  function setSens(v){ document.getElementById('sval').textContent=v; fetch('/control?sensitivity='+v); }
  function setBright(v){ document.getElementById('bval').textContent=v; fetch('/control?brightness='+v); }
  function setZoom(v){ document.getElementById('zval').textContent=(v/100).toFixed(1); fetch('/control?zoom='+v); }
  function setFocus(v){ document.getElementById('fval').textContent=v; fetch('/control?focus='+v); }
  function setFocusAuto(){ document.getElementById('fval').textContent='Auto'; fetch('/control?focus=-1'); }
  function switchCam(){ fetch('/control?facing=toggle'); }
  function setShake(v){
    document.getElementById('shval').textContent=v;
    document.documentElement.style.setProperty('--amp', v);
    try{ localStorage.setItem('amp', v); }catch(e){}
  }
  (function(){
    var a = 12;
    try{ var s = localStorage.getItem('amp'); if(s !== null) a = s; }catch(e){}
    document.getElementById('shake').value = a;
    document.getElementById('shval').textContent = a;
    document.documentElement.style.setProperty('--amp', a);
  })();

  // ---- Motion alarm (sound) ----
  var audioCtx = null, alarmOn = false, alarmPlaying = false, alarmTimer = null, lastMotionTs = 0;
  function ensureAudio(){
    try{
      if(!audioCtx){ audioCtx = new (window.AudioContext || window.webkitAudioContext)(); }
      if(audioCtx.state === 'suspended'){ audioCtx.resume(); }
    }catch(e){}
    return audioCtx;
  }
  function beep(){
    var ctx = ensureAudio(); if(!ctx) return;
    var t = ctx.currentTime;
    var osc = ctx.createOscillator(), g = ctx.createGain();
    osc.type = 'square'; osc.connect(g); g.connect(ctx.destination);
    g.gain.setValueAtTime(0.0001, t);
    g.gain.exponentialRampToValueAtTime(0.6, t + 0.02);
    osc.frequency.setValueAtTime(880, t);
    osc.frequency.linearRampToValueAtTime(1320, t + 0.22);
    osc.frequency.linearRampToValueAtTime(880, t + 0.44);
    g.gain.setValueAtTime(0.6, t + 0.40);
    g.gain.exponentialRampToValueAtTime(0.0001, t + 0.5);
    osc.start(t); osc.stop(t + 0.52);
  }
  function buzz(){ ensureAudio(); beep(); setTimeout(beep, 560); setTimeout(beep, 1120); }
  function startAlarm(){ if(alarmPlaying) return; alarmPlaying = true; beep(); alarmTimer = setInterval(beep, 600); }
  function stopAlarm(){ alarmPlaying = false; if(alarmTimer){ clearInterval(alarmTimer); alarmTimer = null; } }
  function toggleAlarm(){
    alarmOn = !alarmOn;
    var b = document.getElementById('alarmBtn');
    b.textContent = (alarmOn ? '🔔 Motion alarm: ON' : '🔕 Motion alarm: OFF');
    b.className = alarmOn ? 'on' : '';
    ensureAudio();
    if(!alarmOn) stopAlarm();
    try{ localStorage.setItem('alarmOn', alarmOn ? '1' : '0'); }catch(e){}
  }
  (function(){
    var v = '0'; try{ v = localStorage.getItem('alarmOn') || '0'; }catch(e){}
    if(v === '1'){
      alarmOn = true;
      var b = document.getElementById('alarmBtn');
      b.textContent = '🔔 Motion alarm: ON'; b.className = 'on';
    }
  })();

  function place(el, l, t, r, b){
    el.style.left=l+'%'; el.style.top=t+'%';
    el.style.width=(r-l)+'%'; el.style.height=(b-t)+'%';
    el.style.display='block';
  }
  function pct(e){
    var r = stage.getBoundingClientRect();
    var x = (e.clientX - r.left) / r.width * 100;
    var y = (e.clientY - r.top) / r.height * 100;
    return [Math.max(0,Math.min(100,x)), Math.max(0,Math.min(100,y))];
  }
  stage.addEventListener('pointerdown', function(e){ dragging=true; var p=pct(e); sx=p[0]; sy=p[1]; e.preventDefault(); });
  stage.addEventListener('pointermove', function(e){
    if(!dragging) return;
    var p=pct(e);
    place(zonebox, Math.min(sx,p[0]), Math.min(sy,p[1]), Math.max(sx,p[0]), Math.max(sy,p[1]));
  });
  stage.addEventListener('pointerup', function(e){
    if(!dragging) return; dragging=false;
    var p=pct(e);
    var l=Math.round(Math.min(sx,p[0])), t=Math.round(Math.min(sy,p[1]));
    var r=Math.round(Math.max(sx,p[0])), b=Math.round(Math.max(sy,p[1]));
    if(r-l < 3 || b-t < 3) return;
    fetch('/control?zone='+l+','+t+','+r+','+b);
  });

  async function poll(){
    try{
      const res = await fetch('/status');
      const s = await res.json();
      camOn = s.camera;
      const power = document.getElementById('power');
      power.className = s.camera ? 'on' : 'off';
      power.textContent = s.camera ? 'Camera is ON — tap to turn OFF'
                                   : 'Camera is OFF — tap to turn ON';
      const m = document.getElementById('motion');
      m.className = s.motion ? 'on' : '';
      document.getElementById('mtext').textContent =
        (s.motion ? 'Motion' : 'No motion') + ' (' + s.level + '%)';
      if(s.motion && s.camera) stage.classList.add('shake'); else stage.classList.remove('shake');

      // Sound the alarm while there is motion (with a short hang-over so brief moves still alert)
      if(s.camera && s.motion) lastMotionTs = Date.now();
      if(alarmOn && (Date.now() - lastMotionTs < 3000)) startAlarm(); else stopAlarm();

      // Zone overlay (hidden when it is the whole frame)
      if(!dragging){
        const z = s.zone;
        if(z[0]==0 && z[1]==0 && z[2]==100 && z[3]==100) zonebox.style.display='none';
        else place(zonebox, z[0], z[1], z[2], z[3]);
      }
      // Motion bounding box
      const bx = s.box;
      if(s.motion && bx[0]==1) place(motbox, bx[1], bx[2], bx[3], bx[4]);
      else motbox.style.display='none';

      // Reflect slider positions (without fighting the user while they drag)
      sync('sens', 'sval', s.sensitivity);
      sync('bright', 'bval', s.brightness);
      syncZoom(s.zoom);
      syncFocus(s.focus);
      document.getElementById('facing').textContent = s.front ? 'Camera: Front' : 'Camera: Back';
    }catch(e){}
    setTimeout(poll, 600);
  }
  function sync(inputId, labelId, val){
    const el = document.getElementById(inputId);
    if(document.activeElement !== el){ el.value = val; document.getElementById(labelId).textContent = val; }
  }
  function syncZoom(val){
    const el = document.getElementById('zoom');
    if(document.activeElement !== el){ el.value = val; document.getElementById('zval').textContent = (val/100).toFixed(1); }
  }
  function syncFocus(val){
    const el = document.getElementById('focus');
    if(document.activeElement !== el){
      if(val < 0){ document.getElementById('fval').textContent = 'Auto'; }
      else { el.value = val; document.getElementById('fval').textContent = val; }
    }
  }
  poll();
</script>
</body>
</html>
        """.trimIndent()
    }
}
