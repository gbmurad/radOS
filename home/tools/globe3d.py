"""Renders the radOS glass globe spinning, from a real 3D model, so every frame is the same object.

The globe is three glass bands (flat ribbons bent into rings): a pink and a violet band through the poles at
different angles, and an orange band around the middle. Ray marched with signed distances; each ray picks up
to three glass layers so overlaps read as glass. Writes spin-00.png ... with transparent backgrounds.

python3 globe3d.py <out dir> [frames] [size]
"""
import math, sys
import numpy as np
from PIL import Image

out = sys.argv[1]
FRAMES = int(sys.argv[2]) if len(sys.argv) > 2 else 36
S = int(sys.argv[3]) if len(sys.argv) > 3 else 360


def rot(axis, a):
    c, s = math.cos(a), math.sin(a)
    if axis == 'x': return np.array([[1, 0, 0], [0, c, -s], [0, s, c]])
    if axis == 'y': return np.array([[c, 0, s], [0, 1, 0], [-s, 0, c]])
    return np.array([[c, -s, 0], [s, c, 0], [0, 0, 1]])


# A band: a ring of radius R around an axis, cross section a rounded rectangle (half width w along the axis,
# half thickness t across). Each band: (frame rotation, R, w, t, colour).
BANDS = [
    (rot('y', math.radians(28)), 1.00, 0.24, 0.07, np.array([1.00, 0.22, 0.55])),   # pink, through the poles
    (rot('y', math.radians(-52)), 0.97, 0.24, 0.07, np.array([0.50, 0.22, 1.00])),  # violet, through the poles
    (rot('z', math.radians(90)) @ rot('x', math.radians(10)), 1.10, 0.20, 0.08, np.array([1.00, 0.58, 0.12])),  # orange, around
]


def band_sdf(p, M, R, w, t):
    # into the band's own frame: its ring axis is local x
    q = p @ M
    radial = np.sqrt(q[..., 1] ** 2 + q[..., 2] ** 2) - R
    axial = q[..., 0]
    dx = np.abs(axial) - w + 0.02
    dy = np.abs(radial) - t + 0.02
    outside = np.sqrt(np.maximum(dx, 0) ** 2 + np.maximum(dy, 0) ** 2)
    inside = np.minimum(np.maximum(dx, dy), 0)
    return outside + inside - 0.02


def scene(p, mats):
    ds = np.stack([band_sdf(p, M, R, w, t) for (M, R, w, t, _) in mats], 0)
    return ds.min(0), ds.argmin(0)


def normal(p, mats, idx):
    e = 1e-3
    n = np.zeros_like(p)
    for k in range(3):
        d = np.zeros(3); d[k] = e
        a = np.stack([band_sdf(p + d, M, R, w, t) for (M, R, w, t, _) in mats], 0)
        b = np.stack([band_sdf(p - d, M, R, w, t) for (M, R, w, t, _) in mats], 0)
        n[..., k] = np.take_along_axis(a - b, idx[None], 0)[0]
    return n / (np.linalg.norm(n, axis=-1, keepdims=True) + 1e-9)


def render(theta):
    spin = rot('y', theta) @ rot('x', math.radians(-14))      # turn round, slightly tipped toward the viewer
    mats = [((spin @ M), R, w, t, c) for (M, R, w, t, c) in BANDS]
    # camera
    ys, xs = np.mgrid[0:S, 0:S]
    u = (xs + 0.5) / S * 2 - 1
    v = 1 - (ys + 0.5) / S * 2
    ro = np.array([0.0, 0.0, 3.6])
    rd = np.stack([u * 0.42, v * 0.42, -np.ones_like(u)], -1)
    rd /= np.linalg.norm(rd, axis=-1, keepdims=True)
    # mats rotate world into band frames: p @ M, so use transpose of the world rotation
    # (band frame: q = p @ (spin @ M), which is M^T spin^T applied to p)

    col = np.zeros((S, S, 3)); acc = np.zeros((S, S))
    t_ray = np.full((S, S), 2.0)
    alive = np.ones((S, S), bool)
    light = np.array([-0.5, 0.8, 0.6]); light /= np.linalg.norm(light)
    for layer in range(3):
        hit = np.zeros((S, S), bool)
        for _ in range(90):
            p = ro + rd * t_ray[..., None]
            d, idx = scene(p, mats)
            newhit = alive & ~hit & (d < 1e-3)
            hit |= newhit
            step = np.where(alive & ~hit, np.maximum(d, 1e-3), 0)
            t_ray += step
            alive &= t_ray < 5.5
        if not hit.any(): break
        p = ro + rd * t_ray[..., None]
        d, idx = scene(p, mats)
        n = normal(p, mats, idx)
        base = np.stack([c for (_, _, _, _, c) in mats], 0)[idx]
        ndl = np.clip((n * light).sum(-1), 0, 1)
        h = light - rd; h /= np.linalg.norm(h, axis=-1, keepdims=True)
        spec = np.clip((n * h).sum(-1), 0, 1) ** 60
        fres = (1 - np.abs((n * rd).sum(-1))) ** 3
        c = base * (0.55 + 0.6 * ndl)[..., None] + (spec * 1.4 + fres * 0.6)[..., None]
        a = (0.62 + 0.33 * fres) * hit                      # see through in the middle, solid at the edges
        w_ = (1 - acc) * a
        col += c * w_[..., None]
        acc += w_
        # continue through the glass: step past this surface
        t_ray = np.where(hit, t_ray + 0.12, t_ray)
        alive = hit & (t_ray < 5.5)
    img = np.concatenate([np.clip(col, 0, 1), acc[..., None]], -1)
    return Image.fromarray((img * 255).astype(np.uint8))


for f in range(FRAMES):
    im = render(2 * math.pi * f / FRAMES)
    im.save('%s/spin-%02d.png' % (out, f))
print('ok', FRAMES)
