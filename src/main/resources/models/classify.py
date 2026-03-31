#!/usr/bin/env python3
"""
BirdNET Classifier Bridge fuer AMSEL Desktop.
Wird von AMSEL als Subprocess aufgerufen.
Usage: python classify.py <audio_file> [--lat LAT] [--lon LON] [--min_conf 0.1]
Output: JSON Array mit {species, confidence} Objekten
"""
import sys
import json
import os

def classify(audio_path, lat=-1, lon=-1, min_conf=0.1):
    """Klassifiziert eine Audio-Datei mit BirdNET."""
    try:
        from birdnetlib import Recording
        from birdnetlib.analyzer import Analyzer

        # Analyzer laden (cached nach erstem Aufruf)
        analyzer = Analyzer()

        recording = Recording(
            analyzer,
            audio_path,
            lat=lat,
            lon=lon,
            min_conf=min_conf
        )
        recording.analyze()

        results = []
        for det in recording.detections:
            results.append({
                "species": det.get("common_name", det.get("scientific_name", "Unknown")),
                "scientific_name": det.get("scientific_name", ""),
                "confidence": round(det.get("confidence", 0.0), 4),
                "start_time": det.get("start_time", 0.0),
                "end_time": det.get("end_time", 0.0),
                "label": det.get("label", "")
            })

        # Nach Konfidenz sortieren
        results.sort(key=lambda x: x["confidence"], reverse=True)
        return results

    except Exception as e:
        return [{"error": str(e)}]

if __name__ == "__main__":
    import argparse
    parser = argparse.ArgumentParser(description="BirdNET Classifier Bridge")
    parser.add_argument("audio_file", help="Pfad zur Audio-Datei (WAV)")
    parser.add_argument("--lat", type=float, default=-1, help="Breitengrad")
    parser.add_argument("--lon", type=float, default=-1, help="Laengengrad")
    parser.add_argument("--min_conf", type=float, default=0.1, help="Minimale Konfidenz")

    args = parser.parse_args()

    if not os.path.exists(args.audio_file):
        print(json.dumps([{"error": f"Datei nicht gefunden: {args.audio_file}"}]))
        sys.exit(1)

    results = classify(args.audio_file, args.lat, args.lon, args.min_conf)
    print(json.dumps(results, ensure_ascii=False))
