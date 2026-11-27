'use strict';
// What Spotify is playing on this PC, for the game's Now Playing HUD. Windows only: one small PowerShell process
// reads the system media controls (the same ones the volume flyout shows) every two seconds while the game asks.
// Only Spotify's session is read, never other apps.
const { spawn } = require('child_process');

const SCRIPT = `
$ErrorActionPreference = 'SilentlyContinue'
Add-Type -AssemblyName System.Runtime.WindowsRuntime
$asTask = ([System.WindowsRuntimeSystemExtensions].GetMethods() | Where-Object { $_.Name -eq 'AsTask' -and $_.GetParameters().Count -eq 1 -and $_.GetParameters()[0].ParameterType.Name -eq 'IAsyncOperation\`1' })[0]
function Await($op, $type) { $t = $asTask.MakeGenericMethod($type).Invoke($null, @($op)); $t.Wait(-1) | Out-Null; $t.Result }
$null = [Windows.Media.Control.GlobalSystemMediaTransportControlsSessionManager, Windows.Media.Control, ContentType = WindowsRuntime]
$mgr = Await ([Windows.Media.Control.GlobalSystemMediaTransportControlsSessionManager]::RequestAsync()) ([Windows.Media.Control.GlobalSystemMediaTransportControlsSessionManager])
while ($true) {
  $out = '{}'
  $s = $mgr.GetSessions() | Where-Object { $_.SourceAppUserModelId -like '*Spotify*' } | Select-Object -First 1
  if ($s) {
    $p = Await ($s.TryGetMediaPropertiesAsync()) ([Windows.Media.Control.GlobalSystemMediaTransportControlsSessionMediaProperties])
    $tl = $s.GetTimelineProperties()
    $playing = $s.GetPlaybackInfo().PlaybackStatus -eq 'Playing'
    $pos = $tl.Position.TotalSeconds
    if ($playing) { $pos += ([DateTimeOffset]::Now - $tl.LastUpdatedTime).TotalSeconds }
    $out = @{ title = $p.Title; artist = $p.Artist; position = [math]::Round($pos, 1); duration = [math]::Round($tl.EndTime.TotalSeconds, 1); playing = $playing } | ConvertTo-Json -Compress
  }
  [Console]::Out.WriteLine($out)
  [Console]::Out.Flush()
  Start-Sleep -Seconds 2
}
`;

const IDLE_STOP = 15000; // stop the helper when the game hasn't asked for a while

function createNowPlaying({ platform = process.platform, spawnImpl = spawn, now = Date.now } = {}) {
  let child = null, last = {}, lastAsk = 0, timer = null;

  function stop() {
    if (timer) clearInterval(timer);
    timer = null;
    if (child) {
      try {
        child.kill();
      } catch {
        // already gone
      }
    }
    child = null;
  }

  function start() {
    if (child || platform !== 'win32') return;
    child = spawnImpl('powershell.exe', ['-NoProfile', '-NonInteractive', '-ExecutionPolicy', 'Bypass', '-Command', SCRIPT], { windowsHide: true });
    let buf = '';
    child.stdout.on('data', (d) => {
      buf += String(d);
      let i;
      while ((i = buf.indexOf('\n')) >= 0) {
        const line = buf.slice(0, i).trim();
        buf = buf.slice(i + 1);
        try {
          const o = JSON.parse(line || '{}');
          last = o && typeof o.title === 'string' ? { title: o.title, artist: String(o.artist || ''), position: Number(o.position) || 0, duration: Number(o.duration) || 0, playing: Boolean(o.playing), at: now() } : {};
        } catch {
          // a partial line
        }
      }
    });
    child.on('exit', () => {
      child = null;
    });
    child.on('error', () => {
      child = null;
    });
    timer = setInterval(() => {
      if (now() - lastAsk > IDLE_STOP) stop();
    }, 5000);
  }

  return {
    /** What Spotify plays right now ({} when nothing, or not on Windows). */
    current() {
      lastAsk = now();
      start();
      if (!last.title) return {};
      const { at, ...rest } = last;
      return now() - at > 8000 ? {} : rest;
    },
    stop,
  };
}

module.exports = { createNowPlaying };
