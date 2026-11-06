"""The Village Board's pictures and models: a dark walnut face that tiles across all fifty
panels, an oak frame round its edge, and the item's icon. Run from the module directory."""
import json, os, random
from PIL import Image

R = 'src/main/resources/assets/mc_assistant'
random.seed(1907)

def save(img, path):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    img.save(path)

def wood(base, grain, size=16, vertical=False, seed=0):
    rnd = random.Random(seed)
    img = Image.new('RGBA', (size, size))
    px = img.load()
    for y in range(size):
        streak = rnd.uniform(-6, 6)
        for x in range(size):
            i, j = (x, y) if not vertical else (y, x)
            n = rnd.uniform(-5, 5) + streak + (grain if (j % 5 == 2 and rnd.random() < 0.7) else 0)
            c = tuple(max(0, min(255, int(v + n))) for v in base)
            px[i, j] = c + (255,)
    return img

# The face: dark walnut, quiet enough that the writing stands out on it.
face = wood((44, 32, 24), -6, seed=3)
save(face, f'{R}/textures/block/village_board/face.png')
# The back and the frame: oak.
frame = wood((122, 88, 52), -14, vertical=True, seed=5)
save(frame, f'{R}/textures/block/village_board/frame.png')

# The icon: a little board on two posts with lines of writing.
icon = Image.new('RGBA', (16, 16), (0, 0, 0, 0))
p = icon.load()
for y in range(2, 12):
    for x in range(1, 15):
        edge = x in (1, 14) or y in (2, 11)
        p[x, y] = (122, 88, 52, 255) if edge else (44, 32, 24, 255)
for y in range(12, 16):
    for x in (3, 12):
        p[x, y] = (70, 48, 30, 255)
for x in range(4, 12):
    p[x, 4] = (255, 224, 138, 255)
for y, w in ((6, 9), (8, 7), (10, 8)):
    for x in range(3, 3 + w):
        if x < 13:
            p[x, y] = (230, 230, 230, 255)
save(icon, f'{R}/textures/item/village_board.png')

def model(name, elements, extra=None):
    m = {"parent": "block/block", "render_type": "minecraft:cutout",
         "textures": {"particle": "mc_assistant:block/village_board/frame",
                      "face": "mc_assistant:block/village_board/face",
                      "frame": "mc_assistant:block/village_board/frame"},
         "elements": elements}
    path = f'{R}/models/block/village_board/{name}.json'
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, 'w') as f:
        json.dump(m, f, indent=1)

def box(frm, to, front='#frame', back='#frame', side='#frame'):
    faces = {}
    for d in ('north', 'south', 'east', 'west', 'up', 'down'):
        tex = front if d == 'south' else back if d == 'north' else side
        faces[d] = {"texture": tex}
    return {"from": frm, "to": to, "faces": faces}

# Written facing south: the plank against the north of the cell, its face towards you.
model('panel', [box([0, 0, 0], [16, 16, 3], front='#face')])
model('frame_left', [box([0, 0, 0], [2, 16, 4])])
model('frame_right', [box([14, 0, 0], [16, 16, 4])])
model('frame_top', [box([0, 14, 0], [16, 16, 4])])
model('frame_bottom', [box([0, 0, 0], [16, 2, 4])])

ROT = {"south": 0, "west": 90, "north": 180, "east": 270}
parts = []
for facing, y in ROT.items():
    def add(model_name, extra_when):
        when = {"facing": facing}
        when.update(extra_when)
        apply = {"model": f"mc_assistant:block/village_board/{model_name}"}
        if y:
            apply["y"] = y
        parts.append({"when": when, "apply": apply})
    add('panel', {})
    add('frame_left', {"col": "0"})
    add('frame_right', {"col": "9"})
    add('frame_top', {"row": "4"})
    add('frame_bottom', {"row": "0"})
with open(f'{R}/blockstates/village_board.json', 'w') as f:
    json.dump({"multipart": parts}, f, indent=1)

with open(f'{R}/models/item/village_board.json', 'w') as f:
    json.dump({"parent": "minecraft:item/generated", "textures": {"layer0": "mc_assistant:item/village_board"}}, f, indent=1)
print('village board art written')
