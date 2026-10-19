"""radOS startup chime: the pull (low tones bending up), a quick bright run, then "rad-OS" as two soft notes."""
import math, wave, struct, random, sys
random.seed(7)
SR = 44100; dur = 4.4; N = int(SR * dur)
L = [0.0] * N
def add(i, v):
    if 0 <= i < N: L[i] += v
for base, amp in [(110.0, 0.20), (220.0, 0.14)]:
    for det in (-0.6, 0.0, 0.7):
        ph = 0.0
        for i in range(int(1.45 * SR)):
            t = i / SR; p = min(1, t / 1.25); glide = base * (1 + 0.5 * (p * p * (3 - 2 * p))) + det
            ph += 2 * math.pi * glide / SR
            env = min(1, t / 0.5) * (1 if t < 1.15 else max(0, 1 - (t - 1.15) / 0.3))
            add(i, amp / 3 * env * (math.sin(ph) + 0.3 * math.sin(2 * ph)))
lp = 0.0
for i in range(int(1.4 * SR)):
    t = i / SR; cut = 0.02 + 0.25 * (t / 1.4)
    lp += cut * (random.uniform(-1, 1) - lp)
    add(i, 0.10 * lp * min(1, t / 0.6) * (1 if t < 1.15 else max(0, 1 - (t - 1.15) / 0.25)))
def mallet(f, start, length, amp):
    s0 = int(start * SR)
    for i in range(int(length * SR)):
        t = i / SR
        env = min(1, t / 0.004) * math.exp(-t * 5.5)
        v = math.sin(2*math.pi*f*t) + 0.45*math.sin(2*math.pi*4*f*t)*math.exp(-t*14) + 0.15*math.sin(2*math.pi*10*f*t)*math.exp(-t*30)
        add(s0 + i, amp * env * v)
def soft(f, start, length, amp):
    """A soft, round note: slow attack, almost no bright overtones, long gentle fade."""
    s0 = int(start * SR)
    for i in range(int(length * SR)):
        t = i / SR
        env = min(1, t / 0.045) ** 2 * math.exp(-t * 1.5)
        v = math.sin(2*math.pi*f*t) + 0.18*math.sin(2*math.pi*2*f*t)*math.exp(-t*4)
        add(s0 + i, amp * env * v)
run = [440, 554.37, 659.25, 783.99, 880, 1046.5, 1318.5]
for k, f in enumerate(run): mallet(f, 1.18 + k * 0.055, 0.6, 0.10)
soft(659.25, 1.98, 1.4, 0.16)     # rad  (E5, an octave lower and softer)
soft(880.00, 2.32, 2.0, 0.17)     # OS   (A5)
for f in (440.0, 554.37, 659.25):
    s0 = int(2.32 * SR)
    for i in range(int(2.0 * SR)):
        t = i / SR; env = min(1, t / 0.12) * math.exp(-t * 1.4)
        add(s0 + i, 0.05 * env * math.sin(2 * math.pi * f * t))
out = L[:]
for dl, g in [(0.11, 0.22), (0.23, 0.12), (0.37, 0.06)]:
    d = int(dl * SR)
    for i in range(d, N): out[i] += g * L[i - d]
for i in range(N):
    t = i / SR
    if t > dur - 0.5: out[i] *= max(0, (dur - t) / 0.5)
m = max(abs(x) for x in out); out = [x / m * 0.72 for x in out]
w = wave.open(sys.argv[1], 'wb'); w.setnchannels(1); w.setsampwidth(2); w.setframerate(SR)
w.writeframes(b''.join(struct.pack('<h', int(x * 32767)) for x in out)); w.close()
