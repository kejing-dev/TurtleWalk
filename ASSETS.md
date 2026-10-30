# Assets and credits

## Turtle model (v0.1.2)

| | |
|---|---|
| Model | "Turtle By RISD Naturelab - optimised" |
| Author | nebulousflynn (scan by RISD Nature Lab) |
| License | CC BY 4.0, https://creativecommons.org/licenses/by/4.0/ (checked on the Sketchfab API: `license.slug = "by"`) |
| Source | https://sketchfab.com/3d-models/07f52e154aca40cfa1ce4e9260d2ae73 |
| File used | Objaverse-1.0 mirror: https://huggingface.co/datasets/allenai/objaverse/resolve/main/glbs/000-085/07f52e154aca40cfa1ce4e9260d2ae73.glb (3,404,628 B, 25,000 triangles, core glTF 2.0, no extensions) |
| Changes | One root node added (TRS, not a matrix, since v0.1.3): turned −90° about Y so that the head points along −Z, moved so the feet stand on y = 0 and the body is centred, scaled to a length of 1. Geometry, UVs and textures are unchanged. At run time the app measures the loaded model's bounding box and calibrates it again (taste D25, `ModelCalibration`): bottom centre on the origin, head along −Z, length `TurtleAsset.LENGTH_M` = 0.35 m. |

The credit also shows at the bottom of the lesson list in the app.

The model files are not in git (`.gitignore`: `*.glb`, `raw/`); on a new machine the APK has no turtle until this is done
(the app logs `MODEL turtle loaded=false` and shows the shape-made fallback). To rebuild `app/src/main/assets/models/turtle.glb`
(`pip install numpy pillow` first):

```
mkdir -p ../turtle-walk-assets/raw
curl -L -o ../turtle-walk-assets/raw/07f52e154aca40cfa1ce4e9260d2ae73.glb https://huggingface.co/datasets/allenai/objaverse/resolve/main/glbs/000-085/07f52e154aca40cfa1ce4e9260d2ae73.glb
python -I tools/turtle_model.py export ../turtle-walk-assets/raw/07f52e154aca40cfa1ce4e9260d2ae73.glb app/src/main/assets/models/turtle.glb --yaw -90
python -I tools/turtle_model.py render app/src/main/assets/models/turtle.glb .scratch/turtle.png   # check: shell up, head on the right in the side view
```

Candidates that were compared (all CC BY 4.0, same route): B "Tortoise - turtle" by dalez92
(sketchfab 031b140380654dedb3b18e7cdd8c190c, export `--yaw 180`), C "Giant Tortoise, Beauval Zoo, Fr. (Smoothie 3D)" by
nebulousflynn (sketchfab 282c8c0383ba4f8d8dc07d38e2b1b766, export `--yaw -90`). Comparison page:
https://claude.ai/artifact/MMMr8G7AEd6as6JW1dpNdc

## Fonts

Noto Sans and Noto Sans SC (subset), SIL Open Font License 1.1, see `third_party/`.
