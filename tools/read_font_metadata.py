"""Read SFNT name records for asset provenance; does not render or validate fonts."""
import json
import struct
import sys
from pathlib import Path


def names(path):
    data = path.read_bytes()
    count = struct.unpack_from('>H', data, 4)[0]
    for index in range(count):
        tag, _, offset, _ = struct.unpack_from('>4sIII', data, 12 + index * 16)
        if tag != b'name':
            continue
        _, entries, strings = struct.unpack_from('>HHH', data, offset)
        result = {}
        for entry in range(entries):
            platform, _, language, name, length, relative = struct.unpack_from('>6H', data, offset + 6 + entry * 12)
            if name not in (0, 1, 2, 8, 13, 14):
                continue
            raw = data[offset + strings + relative:offset + strings + relative + length]
            text = raw.decode('utf-16-be' if platform in (0, 3) else 'mac_roman', errors='replace')
            if str(name) not in result or language == 0x409:
                result[str(name)] = text
        return result
    return {}


if __name__ == '__main__':
    print(json.dumps([{'file': Path(arg).name, 'names': names(Path(arg))} for arg in sys.argv[1:]], ensure_ascii=False))
