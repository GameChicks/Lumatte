"""Extract display metadata only from pinned film JSON resources, without executing the model."""
import json
from pathlib import Path

root = Path(__file__).resolve().parents[1]
profiles = root / 'third_party/spektralab/upstream/engine/resources/profiles'
entries = []
for file in sorted(profiles.glob('*.json')):
    info = json.loads(file.read_text(encoding='utf-8'))['info']
    if info.get('stage') != 'filming' or info.get('support') != 'film':
        continue
    positive = info['type'] == 'positive'
    category = 'MONOCHROME' if info.get('channel_model') == 'bw' else 'REVERSAL' if positive else 'CINEMA' if info.get('use') == 'cine' else 'NEGATIVE'
    entries.append({'id': info['stock'], 'name': info['name'], 'category': category,
                    'printId': info.get('target_print') or 'kodak_portra_endura', 'positive': positive,
                    'source': f'profiles/{file.name}'})
destination = root / 'core/media/src/main/assets/film/catalogue.json'
destination.parent.mkdir(parents=True, exist_ok=True)
destination.write_text(json.dumps({'sourceCommit': '6a347d5756f8b1a96ec393c60bf5d0452fa5498b',
                                  'author': 'Andrea Volpato', 'license': 'CC-BY-SA-4.0', 'stocks': entries},
                                 ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
print(f'Wrote display metadata for {len(entries)} film models')
