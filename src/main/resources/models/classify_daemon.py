#!/usr/bin/env python3
"""
BirdNET Daemon - HTTP-Server fuer persistente BirdNET-Klassifikation.
Laedt das Modell EINMAL, haelt es im Speicher, antwortet per HTTP.
Auto-Shutdown nach 30 Min Inaktivitaet.
"""
import json, sys, time, threading
from http.server import HTTPServer, BaseHTTPRequestHandler
from birdnetlib import Recording
from birdnetlib.analyzer import Analyzer

analyzer = None
last_activity = time.time()
TIMEOUT_SEC = 30 * 60
PORT = 5757

def load_analyzer():
    global analyzer
    print("[Daemon] Lade BirdNET-Modell...", file=sys.stderr)
    analyzer = Analyzer()
    print("[Daemon] Modell geladen.", file=sys.stderr)

def classify_file(audio_path, min_conf=0.1, lat=-1, lon=-1):
    global last_activity
    last_activity = time.time()
    recording = Recording(analyzer, audio_path,
        lat=lat if lat > 0 else -1, lon=lon if lon > 0 else -1, min_conf=min_conf)
    recording.analyze()
    return [{"species": d.get("common_name",""), "scientific_name": d.get("scientific_name",""),
             "confidence": round(d.get("confidence",0.0),4), "start_time": d.get("start_time",0.0),
             "end_time": d.get("end_time",0.0), "label": d.get("label",d.get("common_name",""))}
            for d in recording.detections]

class Handler(BaseHTTPRequestHandler):
    def log_message(self, format, *args): pass
    def do_GET(self):
        global last_activity; last_activity = time.time()
        if self.path == "/health":
            self._json({"status": "ok", "model": "BirdNET V2.4"})
        else: self.send_error(404)
    def do_POST(self):
        global last_activity; last_activity = time.time()
        if self.path == "/classify": self._classify()
        elif self.path == "/shutdown":
            self._json({"status": "shutting_down"})
            threading.Thread(target=self.server.shutdown, daemon=True).start()
        else: self.send_error(404)
    def _classify(self):
        try:
            body = json.loads(self.rfile.read(int(self.headers.get("Content-Length",0))).decode())
            results = classify_file(body.get("audio_file",""),
                float(body.get("min_conf",0.1)), float(body.get("lat",-1)), float(body.get("lon",-1)))
            self._json(results)
        except Exception as e:
            print(f"[Daemon] Fehler: {e}", file=sys.stderr)
            self._json([{"error": str(e)}])
    def _json(self, data):
        r = json.dumps(data, ensure_ascii=False).encode()
        self.send_response(200)
        self.send_header("Content-Type","application/json"); self.send_header("Content-Length",str(len(r)))
        self.end_headers(); self.wfile.write(r)

def watchdog(server):
    while True:
        time.sleep(60)
        if time.time() - last_activity > TIMEOUT_SEC:
            print(f"[Daemon] Inaktiv - beende.", file=sys.stderr); server.shutdown(); break

if __name__ == "__main__":
    load_analyzer()
    server = HTTPServer(("127.0.0.1", PORT), Handler)
    print(f"[Daemon] http://127.0.0.1:{PORT}", file=sys.stderr)
    threading.Thread(target=watchdog, args=(server,), daemon=True).start()
    try: server.serve_forever()
    except KeyboardInterrupt: pass
    finally: server.server_close()
