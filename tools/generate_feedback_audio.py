"""Manually generate the original 32ms UI tick; no recordings or third-party samples."""
from pathlib import Path
import math,struct,wave

target=Path(__file__).resolve().parents[1]/'app/src/main/res/raw/feedback_tick.wav'
target.parent.mkdir(parents=True,exist_ok=True)
rate=44100
samples=[]
for i in range(round(.032*rate)):
    t=i/rate
    envelope=min(1,t/.0005)*math.exp(-t/.0058)*min(1,(.032-t)/.004)
    signal=.9*math.sin(2*math.pi*920*t)+.1*math.sin(2*math.pi*1840*t)
    samples.append(round(32767*.4*envelope*signal))
with wave.open(str(target),'wb') as out:
    out.setnchannels(1);out.setsampwidth(2);out.setframerate(rate)
    out.writeframes(struct.pack('<'+'h'*len(samples),*samples))
print(target)
