"""Five radOS startup chimes, about 5 seconds each. python3 chimes.py <out dir>"""
import math, wave, struct, random, sys
SR = 44100
def blank(sec): return [0.0] * int(SR * sec)
def add(buf, i, v):
    if 0 <= i < len(buf): buf[i] += v
def tone(buf, f, start, length, amp, attack=0.01, decay=2.0, partials=((1, 1.0),), glide=0.0):
    s0 = int(start * SR); ph = [0.0] * len(partials)
    for i in range(int(length * SR)):
        t = i / SR
        env = min(1, t / attack) ** 2 * math.exp(-t * decay)
        fr = f * (1 + glide * min(1, t / length))
        v = 0.0
        for k, (mul, a) in enumerate(partials):
            ph[k] += 2 * math.pi * fr * mul / SR
            v += a * math.sin(ph[k])
        add(buf, s0 + i, amp * env * v)
def noise_swell(buf, start, length, amp, rise=True):
    lp = 0.0; s0 = int(start * SR); n = int(length * SR)
    for i in range(n):
        p = i / n
        cut = 0.01 + 0.3 * (p if rise else 1 - p)
        lp += cut * (random.uniform(-1, 1) - lp)
        env = (p ** 2 if rise else (1 - p) ** 2)
        add(buf, s0 + i, amp * lp * env)
def echo(buf, taps):
    out = buf[:]
    for dl, g in taps:
        d = int(dl * SR)
        for i in range(d, len(buf)): out[i] += g * buf[i - d]
    return out
def finish(buf, path, fade=0.6, peak=0.72):
    n = len(buf)
    for i in range(n):
        t = (n - i) / SR
        if t < fade: buf[i] *= t / fade
    m = max(abs(x) for x in buf) or 1
    w = wave.open(path, 'wb'); w.setnchannels(1); w.setsampwidth(2); w.setframerate(SR)
    w.writeframes(b''.join(struct.pack('<h', int(x / m * peak * 32767)) for x in buf)); w.close()

MALLET = ((1, 1.0), (4, 0.35), (10, 0.08))
BELL = ((1, 1.0), (2, 0.25), (2.76, 0.12))
SOFT = ((1, 1.0), (2, 0.15))
EP = ((1, 1.0), (2, 0.4), (3, 0.12), (7, 0.03))
out = sys.argv[1]

# A  Glass: the pull up, a bright run, a soft "rad-OS" (the current one, stretched to 5 s)
random.seed(1); b = blank(5.0)
for base, amp in [(110, .18), (220, .12)]:
    for det in (-.6, 0, .7): tone(b, base + det, 0, 1.7, amp / 3, attack=.6, decay=.4, partials=SOFT, glide=.5)
noise_swell(b, 0, 1.5, .09)
for k, f in enumerate([440, 554.37, 659.25, 783.99, 880, 1046.5, 1318.5]): tone(b, f, 1.4 + k * .06, .7, .09, .004, 5.5, MALLET)
tone(b, 659.25, 2.3, 2.2, .16, .045, 1.3, SOFT); tone(b, 880, 2.7, 2.3, .17, .045, 1.1, SOFT)
for f in (440, 554.37, 659.25): tone(b, f, 2.7, 2.3, .05, .12, 1.0, SOFT)
finish(echo(b, [(.11, .22), (.23, .12), (.37, .06)]), out + '/A-glass.wav')

# B  Bloom: one slow warm chord that opens up like a sunrise, with a high shimmer on top
random.seed(2); b = blank(5.0)
chord = [130.81, 196.0, 261.63, 329.63, 392.0, 493.88]    # C major 7, spread
for k, f in enumerate(chord):
    for det in (-.8, .8): tone(b, f + det, k * .12, 4.6, .07, attack=1.4, decay=.35, partials=SOFT)
for k, f in enumerate([1567.98, 1975.53, 2349.32, 2637.0]): tone(b, f, 1.6 + k * .35, 2.5, .025, .3, 1.2, BELL)
noise_swell(b, 0, 1.8, .05)
finish(echo(b, [(.17, .25), (.34, .15), (.51, .08)]), out + '/B-bloom.wav', fade=1.2)

# C  Pop: bouncy and playful, three quick mallet hits, then a cheerful "rad-OS" up a fifth
random.seed(3); b = blank(5.0)
for k, f in enumerate([523.25, 659.25, 783.99]): tone(b, f, .25 + k * .14, .8, .16, .003, 6, MALLET)
tone(b, 1046.5, .8, .9, .1, .003, 5, MALLET)
tone(b, 783.99, 1.5, 1.6, .2, .005, 2.6, MALLET); tone(b, 1174.66, 1.85, 2.6, .22, .005, 1.8, MALLET)
for f in (392, 493.88, 587.33): tone(b, f, 1.85, 2.6, .05, .08, 1.2, SOFT)
finish(echo(b, [(.09, .2), (.19, .1)]), out + '/C-pop.wav')

# D  Cinema: an airy rise into a deep soft hit, then a calm two note "rad-OS" on top
random.seed(4); b = blank(5.0)
noise_swell(b, 0, 1.6, .16)
for det in (-.4, 0, .4): tone(b, 41.2 + det, 1.55, 3.0, .35, .01, 1.6, ((1, 1), (2, .3)), glide=-.15)   # low boom
for f in (82.41, 123.47, 164.81): tone(b, f, 1.55, 3.2, .09, .2, .8, SOFT)
tone(b, 659.25, 2.2, 2.4, .11, .06, 1.2, BELL); tone(b, 987.77, 2.6, 2.4, .12, .06, 1.0, BELL)
finish(echo(b, [(.21, .25), (.43, .14)]), out + '/D-cinema.wav', fade=1.0)

# E  Lounge: a soft electric piano chord rolled upward, then "rad-OS" on the keys
random.seed(5); b = blank(5.0)
for k, f in enumerate([146.83, 220.0, 261.63, 329.63, 440.0]): tone(b, f, .2 + k * .07, 3.6, .1, .01, .9, EP)   # Dm9 roll
tone(b, 659.25, 1.9, 2.2, .14, .01, 1.4, EP); tone(b, 880, 2.25, 2.5, .15, .01, 1.1, EP)
for f in (174.61, 261.63, 349.23, 440): tone(b, f, 2.25, 2.5, .05, .05, .9, EP)
finish(echo(b, [(.14, .22), (.29, .12)]), out + '/E-lounge.wav')
print('ok')
