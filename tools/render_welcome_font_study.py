"""Manual typography artwork only. Does not change Android resources or run tests.

Original candidate fonts, metadata and licences stay in the requested output
folder. Downloading is explicit; later rendering is offline. No font is modified.
"""
from pathlib import Path
import argparse
import hashlib
import json
from urllib.parse import quote
from urllib.request import urlopen

import numpy as np
from PIL import Image, ImageDraw, ImageFont

ROOT = Path(__file__).resolve().parents[1]
CANDIDATES = [
    ('Teko Bold', 'teko', 'Teko[wght].ttf', {'wght': 700}),
    ('Big Shoulders Black', 'bigshoulders', 'BigShoulders[opsz,wght].ttf', {'opsz': 72, 'wght': 900}),
    ('Antonio Bold', 'antonio', 'Antonio[wght].ttf', {'wght': 700}),
    ('Anton', 'anton', 'Anton-Regular.ttf', {}),
]


def font_at(path, size, axes=None):
    font = ImageFont.truetype(str(path), size)
    if axes:
        font.set_variation_by_axes([axes.get(axis['name'].decode(), axis['default'])
                                    for axis in font.get_variation_axes()])
    return font


def candidate_font(path, axes):
    # FreeType exposes axis names, not OpenType tags.
    values = {'Weight': axes.get('wght'), 'Optical Size': axes.get('opsz')}
    return font_at(path, 480, {k: v for k, v in values.items() if v is not None})


def heading(path, axes, width, height, dark):
    font = candidate_font(path, axes)
    bounds = font.getbbox('WELCOME')
    ink = Image.new('L', (bounds[2] - bounds[0], bounds[3] - bounds[1]))
    ImageDraw.Draw(ink).text((-bounds[0], -bounds[1]), 'WELCOME', font=font, fill=255)
    # Match the intended ink rectangle (not font ascender/descender padding).
    ink = ink.resize((width, height), Image.Resampling.LANCZOS)
    # Four-point projective transform, top right at 23% of the height.
    dst = [(0, 0), (width, height * .23), (width, height), (0, height)]
    src = [(0, 0), (width, 0), (width, height), (0, height)]
    rows, result = [], []
    for (x, y), (u, v) in zip(dst, src):
        rows += [[x, y, 1, 0, 0, 0, -u*x, -u*y], [0, 0, 0, x, y, 1, -v*x, -v*y]]
        result += [u, v]
    coefficients = np.linalg.solve(np.array(rows), np.array(result))
    ink = ink.transform((width, height), Image.Transform.PERSPECTIVE, coefficients,
                        Image.Resampling.BICUBIC)
    top, bottom = ((246, 243, 233), (101, 158, 192)) if dark else ((34, 42, 46), (93, 143, 168))
    pixels = np.zeros((height, width, 4), dtype=np.uint8)
    for y in range(height):
        t = max(0, (y / height - .55) / .45)
        pixels[y, :, :3] = [int(a + (b-a)*t) for a, b in zip(top, bottom)]
    pixels[:, :, 3] = np.asarray(ink)
    return Image.fromarray(pixels)


def render(output, records, dark=False):
    width, row_height = 1320, 330
    bg, ink, muted = ((16, 18, 20), '#f0eee4', '#a6b6c3') if dark else ((242, 235, 218), '#222a2e', '#6b705f')
    board = Image.new('RGB', (width, 165 + row_height*len(records)), bg)
    draw = ImageDraw.Draw(board)
    body = ROOT/'core/design/src/main/res/font/oppo_sans_4.ttf'
    label, small = font_at(body, 29), font_at(body, 20)
    draw.text((44, 24), 'WELCOME / 免费商用候选 · 透视字形对照', fill=ink, font=label)
    draw.text((44, 70), '同一字面框、23% 透视落差、底部同位置遮挡。静态排字研究，不是应用截图。', fill=muted, font=small)
    draw.text((44, 103), '不含 NHL 文件；保留原字体字节。Android 现用 Bebas Neue 暂不替换。', fill=muted, font=small)
    for index, record in enumerate(records):
        y = 165 + index * row_height
        draw.line((44, y-12, width-44, y-12), fill=('#3c4146' if dark else '#d4ccbc'))
        draw.text((44, y+6), f'{index+1:02d}  {record["name"]}', fill=ink, font=label)
        draw.text((44, y+48), '当前版本' if index == 0 else 'OFL 1.1', fill=muted, font=small)
        word = heading(record['path'], record['axes'], 850, 255, dark)
        board.paste(word, (426, y+8), word)
        # A consistent card edge makes headline-to-card layering comparable.
        draw.rounded_rectangle((426, y+234, 1276, y+295), 34, fill='#538ba1', outline='#a1c6d5', width=2)
        draw.text((453, y+248), '你的松下相机', font=small, fill='#ffffff')
        # Natural cap ratio helps distinguish shape from the common stage stretch.
        font = candidate_font(record['path'], record['axes'])
        bounds = font.getbbox('WELCOME')
        ratio = (bounds[2]-bounds[0])/(bounds[3]-bounds[1])
        draw.text((44, y+94), f'原生词宽 / 大写字高  {ratio:.2f}', fill=muted, font=small)
    board.save(output/('welcome-dark.png' if dark else 'welcome-light.png'))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--download', action='store_true')
    args = parser.parse_args()
    output = args.output.resolve()
    output.mkdir(parents=True, exist_ok=True)
    records = [{'name': 'Bebas Neue / 当前', 'path': ROOT/'core/design/src/main/res/font/bebas_neue.ttf', 'axes': {}}]
    manifest = {'date': '2026-10-18', 'purpose': 'Typography study only; no Android resource replacement', 'files': []}
    for name, family, filename, axes in CANDIDATES:
        folder = output/'originals'/family
        folder.mkdir(parents=True, exist_ok=True)
        for source in [filename, 'OFL.txt', 'METADATA.pb']:
            url = f'https://raw.githubusercontent.com/google/fonts/main/ofl/{family}/{quote(source)}'
            path = folder/source
            if not path.exists() and args.download:
                with urlopen(url, timeout=30) as response:
                    data = response.read(4*1024*1024+1)
                if len(data) > 4*1024*1024:
                    raise ValueError('Unexpectedly large font input')
                path.write_bytes(data)
            data = path.read_bytes()
            manifest['files'].append({'file': str(path.relative_to(output)), 'url': url,
                                      'bytes': len(data), 'sha256': hashlib.sha256(data).hexdigest()})
        records.append({'name': name, 'path': folder/filename, 'axes': axes})
    (output/'sources.json').write_text(json.dumps(manifest, ensure_ascii=False, indent=2)+'\n', encoding='utf-8')
    render(output, records)
    render(output, records, dark=True)
    print(json.dumps({'output': str(output), 'candidateFonts': len(CANDIDATES), 'androidChanged': False}))


if __name__ == '__main__':
    main()
