"""Turtle model inspection, preview renders and app export.

    python -I tools/turtle_model.py inspect <raw.glb>...        bbox, axes, tris, materials, animations
    python -I tools/turtle_model.py render <raw.glb> <out.png>  side / front / top textured renders (up = +Y)
    python -I tools/turtle_model.py export <raw.glb> <out.glb> [--yaw DEG]
                                                                bakes the node transforms into one node so that the
                                                                model is Y-up, head along -Z (the app's local
                                                                forward), feet on y = 0, centred in x/z, length 1 m
                                                                (the app scales it to the turtle's size).

Raw files (CC BY 4.0, Sketchfab via the Objaverse-1.0 mirror) live outside the repository in
../turtle-walk-assets/raw; only the exported model is bundled (app/src/main/assets/models/turtle.glb).
The renders are a numpy z-buffer rasteriser with the base colour texture and a simple lambert term, so the
up / down and head direction can be checked before the model goes into the app.
"""
import io
import json
import math
import struct
import sys

import numpy as np
from PIL import Image

CT = {5126: (np.float32, 4), 5123: (np.uint16, 2), 5121: (np.uint8, 1), 5125: (np.uint32, 4), 5122: (np.int16, 2), 5120: (np.int8, 1)}
NC = {"SCALAR": 1, "VEC2": 2, "VEC3": 3, "VEC4": 4, "MAT4": 16}


def read_glb(path):
    data = open(path, "rb").read()
    magic, _version, length = struct.unpack_from("<III", data, 0)
    assert magic == 0x46546C67, "not a GLB"
    off, js, binc = 12, None, b""
    while off < length:
        clen, ctype = struct.unpack_from("<II", data, off)
        chunk = data[off + 8 : off + 8 + clen]
        if ctype == 0x4E4F534A:
            js = json.loads(chunk.decode("utf-8"))
        elif ctype == 0x004E4942:
            binc = chunk
        off += 8 + clen
    return js, binc


def write_glb(path, js, binc):
    jb = json.dumps(js, separators=(",", ":")).encode("utf-8")
    jb += b" " * ((4 - len(jb) % 4) % 4)
    bb = binc + b"\x00" * ((4 - len(binc) % 4) % 4)
    with open(path, "wb") as f:
        f.write(struct.pack("<III", 0x46546C67, 2, 12 + 8 + len(jb) + 8 + len(bb)))
        f.write(struct.pack("<II", len(jb), 0x4E4F534A))
        f.write(jb)
        f.write(struct.pack("<II", len(bb), 0x004E4942))
        f.write(bb)


def accessor(js, binc, ai):
    a = js["accessors"][ai]
    bv = js["bufferViews"][a["bufferView"]]
    dt, size = CT[a["componentType"]]
    n = NC[a["type"]]
    start = bv.get("byteOffset", 0) + a.get("byteOffset", 0)
    stride = bv.get("byteStride", 0) or size * n
    raw = np.frombuffer(binc, dtype=np.uint8, count=stride * (a["count"] - 1) + size * n, offset=start)
    rows = np.lib.stride_tricks.as_strided(raw, shape=(a["count"], size * n), strides=(stride, 1))
    out = np.ascontiguousarray(rows).view(dt).reshape(a["count"], n).astype(np.float64)
    if a.get("normalized"):
        out /= float(np.iinfo(dt).max)
    return out


def node_matrix(n):
    if "matrix" in n:
        return np.array(n["matrix"], dtype=np.float64).reshape(4, 4).T
    t = n.get("translation", [0, 0, 0])
    r = n.get("rotation", [0, 0, 0, 1])
    s = n.get("scale", [1, 1, 1])
    x, y, z, w = r
    rot = np.array(
        [
            [1 - 2 * (y * y + z * z), 2 * (x * y - z * w), 2 * (x * z + y * w)],
            [2 * (x * y + z * w), 1 - 2 * (x * x + z * z), 2 * (y * z - x * w)],
            [2 * (x * z - y * w), 2 * (y * z + x * w), 1 - 2 * (x * x + y * y)],
        ]
    )
    m = np.eye(4)
    m[:3, :3] = rot * np.array(s)
    m[:3, 3] = t
    return m


def meshes_in_world(js, binc):
    """[(mesh index, primitive, world matrix)] for every mesh node (skins ignored: these files have none)."""
    out = []
    scene = js["scenes"][js.get("scene", 0)]

    def walk(ni, parent):
        n = js["nodes"][ni]
        m = parent @ node_matrix(n)
        if "mesh" in n:
            for p in js["meshes"][n["mesh"]]["primitives"]:
                out.append((n["mesh"], p, m))
        for c in n.get("children", []):
            walk(c, m)

    for r in scene["nodes"]:
        walk(r, np.eye(4))
    return out


def texture_image(js, binc, mat):
    pbr = mat.get("pbrMetallicRoughness", {})
    tex = pbr.get("baseColorTexture")
    if tex is None:
        sg = mat.get("extensions", {}).get("KHR_materials_pbrSpecularGlossiness", {})
        tex = sg.get("diffuseTexture")
    if tex is None:
        return None
    src = js["textures"][tex["index"]]["source"]
    img = js["images"][src]
    bv = js["bufferViews"][img["bufferView"]]
    data = binc[bv.get("byteOffset", 0) : bv.get("byteOffset", 0) + bv["byteLength"]]
    return np.asarray(Image.open(io.BytesIO(data)).convert("RGB"), dtype=np.float32) / 255.0


def triangles(js, binc):
    """World-space triangles with uv and the material colour source: list of (P[n,3,3], UV[n,3,2] | None, tex | colour)."""
    out = []
    for _mi, p, m in meshes_in_world(js, binc):
        if p.get("mode", 4) != 4:
            continue
        pos = accessor(js, binc, p["attributes"]["POSITION"])
        pos = (np.c_[pos, np.ones(len(pos))] @ m.T)[:, :3]
        idx = accessor(js, binc, p["indices"]).astype(np.int64).ravel() if "indices" in p else np.arange(len(pos))
        tri = idx.reshape(-1, 3)
        uv = accessor(js, binc, p["attributes"]["TEXCOORD_0"]) if "TEXCOORD_0" in p["attributes"] else None
        mat = js["materials"][p["material"]] if "material" in p else {}
        tex = texture_image(js, binc, mat)
        col = mat.get("pbrMetallicRoughness", {}).get("baseColorFactor", [0.8, 0.8, 0.8, 1])[:3]
        out.append((pos[tri], uv[tri] if uv is not None else None, tex if tex is not None else np.array(col)))
    return out


def inspect(path):
    js, binc = read_glb(path)
    tris = triangles(js, binc)
    P = np.concatenate([t[0] for t in tris])
    lo, hi = P.reshape(-1, 3).min(0), P.reshape(-1, 3).max(0)
    print(path)
    print("  tris", len(P), "meshes", len(js.get("meshes", [])), "materials", len(js.get("materials", [])), "textures", len(js.get("textures", [])))
    print("  bbox lo", np.round(lo, 4), "hi", np.round(hi, 4), "size", np.round(hi - lo, 4))
    print("  extensionsUsed", js.get("extensionsUsed"), "required", js.get("extensionsRequired"))
    print("  animations", [a.get("name") for a in js.get("animations", [])], "skins", len(js.get("skins", [])))
    imgs = js.get("images", [])
    print("  images", [(i.get("mimeType"), js["bufferViews"][i["bufferView"]]["byteLength"]) for i in imgs if "bufferView" in i])
    return P


def raster(tris, view, width, height, light):
    """view: 3x3 rows = screen x, screen y (up), depth (towards the camera). Orthographic, fitted."""
    allp = np.concatenate([t[0] for t in tris]).reshape(-1, 3) @ view.T
    lo, hi = allp.min(0), allp.max(0)
    span = max(hi[0] - lo[0], hi[1] - lo[1]) * 1.08
    cx, cy = (lo[0] + hi[0]) / 2, (lo[1] + hi[1]) / 2
    k = min(width, height) / span
    img = np.ones((height, width, 3), np.float32) * np.array([0.93, 0.95, 0.97], np.float32)
    zbuf = np.full((height, width), -np.inf, np.float32)
    for P, UV, src in tris:
        S = P @ view.T
        n = np.cross(P[:, 1] - P[:, 0], P[:, 2] - P[:, 0])
        n /= np.linalg.norm(n, axis=1, keepdims=True) + 1e-12
        shade = 0.45 + 0.55 * np.abs(n @ light)
        xs = (S[:, :, 0] - cx) * k + width / 2
        ys = height / 2 - (S[:, :, 1] - cy) * k
        zs = S[:, :, 2]
        for t in range(len(P)):
            x0, x1 = int(max(0, math.floor(xs[t].min()))), int(min(width - 1, math.ceil(xs[t].max())))
            y0, y1 = int(max(0, math.floor(ys[t].min()))), int(min(height - 1, math.ceil(ys[t].max())))
            if x1 < x0 or y1 < y0:
                continue
            gx, gy = np.meshgrid(np.arange(x0, x1 + 1) + 0.5, np.arange(y0, y1 + 1) + 0.5)
            (ax, bx, cxx), (ay, by, cy_) = xs[t], ys[t]
            den = (by - cy_) * (ax - cxx) + (cxx - bx) * (ay - cy_)
            if abs(den) < 1e-9:
                continue
            w0 = ((by - cy_) * (gx - cxx) + (cxx - bx) * (gy - cy_)) / den
            w1 = ((cy_ - ay) * (gx - cxx) + (ax - cxx) * (gy - cy_)) / den
            w2 = 1 - w0 - w1
            inside = (w0 >= 0) & (w1 >= 0) & (w2 >= 0)
            if not inside.any():
                continue
            z = w0 * zs[t, 0] + w1 * zs[t, 1] + w2 * zs[t, 2]
            sub = zbuf[y0 : y1 + 1, x0 : x1 + 1]
            win = inside & (z > sub)
            if not win.any():
                continue
            sub[win] = z[win]
            if UV is not None and src.ndim == 3:
                u = (w0 * UV[t, 0, 0] + w1 * UV[t, 1, 0] + w2 * UV[t, 2, 0]) % 1.0
                v = (w0 * UV[t, 0, 1] + w1 * UV[t, 1, 1] + w2 * UV[t, 2, 1]) % 1.0
                th, tw = src.shape[:2]
                c = src[(v * (th - 1)).astype(int), (u * (tw - 1)).astype(int)]
            else:
                c = np.broadcast_to(src if src.ndim == 1 else src.mean((0, 1)), gx.shape + (3,))
            img[y0 : y1 + 1, x0 : x1 + 1][win] = c[win] * shade[t]
    return Image.fromarray((np.clip(img, 0, 1) * 255).astype(np.uint8))


def render(path, out):
    js, binc = read_glb(path)
    tris = triangles(js, binc)
    light = np.array([0.3, 0.8, 0.5])
    light /= np.linalg.norm(light)
    views = {
        # side: looking along -X (screen x = -Z, y = +Y)
        "side": np.array([[0, 0, -1], [0, 1, 0], [1, 0, 0]], float),
        # front: looking from -Z towards +Z... screen x = X
        "front": np.array([[1, 0, 0], [0, 1, 0], [0, 0, -1]], float),
        # three-quarter from above
        "3/4": np.array([[0.7071, 0, -0.7071], [-0.35, 0.866, -0.35], [0.61, 0.5, 0.61]], float),
    }
    tiles = [raster(tris, v, 420, 300, light) for v in views.values()]
    sheet = Image.new("RGB", (420 * len(tiles), 300), "white")
    for i, t in enumerate(tiles):
        sheet.paste(t, (420 * i, 0))
    sheet.save(out)
    print("render", out)


def export(path, out, yaw_deg):
    """One node, baked: Y-up, head along -Z, feet at y = 0, centred, the longest horizontal size = 1."""
    js, binc = read_glb(path)
    P = np.concatenate([t[0] for t in triangles(js, binc)]).reshape(-1, 3)
    yaw = math.radians(yaw_deg)
    rot = np.array([[math.cos(yaw), 0, math.sin(yaw)], [0, 1, 0], [-math.sin(yaw), 0, math.cos(yaw)]])
    Q = P @ rot.T
    lo, hi = Q.min(0), Q.max(0)
    length = max(hi[0] - lo[0], hi[2] - lo[2])
    k = 1.0 / length
    center = np.array([(lo[0] + hi[0]) / 2, lo[1], (lo[2] + hi[2]) / 2])
    root = np.eye(4)
    root[:3, :3] = rot * k
    root[:3, 3] = -center * k
    scene = js["scenes"][js.get("scene", 0)]
    new_root = {"name": "turtle_root", "matrix": list(root.T.ravel()), "children": list(scene["nodes"])}
    js["nodes"].append(new_root)
    scene["nodes"] = [len(js["nodes"]) - 1]
    write_glb(out, js, binc)
    print("export", out, "scale", round(k, 6), "size after", np.round((hi - lo) * k, 4))


if __name__ == "__main__":
    cmd = sys.argv[1]
    if cmd == "inspect":
        for p in sys.argv[2:]:
            inspect(p)
    elif cmd == "render":
        render(sys.argv[2], sys.argv[3])
    elif cmd == "export":
        yaw = float(sys.argv[sys.argv.index("--yaw") + 1]) if "--yaw" in sys.argv else 0.0
        export(sys.argv[2], sys.argv[3], yaw)
