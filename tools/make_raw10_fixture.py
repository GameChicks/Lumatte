"""Manual targeted fixture: small uncompressed Bayer DNG with 14-bit sample range, not a camera sample."""
import struct
import sys
from pathlib import Path

w, h = 128, 96
tags = {}
def item(tag, kind, values):
    if kind == 2:
        data = values.encode('ascii') + b'\0'; count = len(data)
    elif kind == 1:
        data = bytes(values); count = len(values)
    elif kind == 3:
        data = struct.pack('<'+'H'*len(values), *values); count = len(values)
    elif kind == 4:
        data = struct.pack('<'+'I'*len(values), *values); count = len(values)
    else:
        data = b''.join(struct.pack('<ii', *v) for v in values); count = len(values)
    tags[tag] = (kind, count, data)
for tag, values in {256:[w],257:[h],273:[0],278:[h],279:[w*h*2],50717:[16383],50719:[0,0],50720:[w,h],50829:[0,0,h,w]}.items(): item(tag,4,values)
for tag, values in {258:[16],259:[1],262:[32803],274:[1],277:[1],284:[1],33421:[2,2],50711:[1],50713:[1,1],50778:[21]}.items(): item(tag,3,values)
item(271,2,'Lumatte Synthetic');item(272,2,'Raw10 Fixture');item(50708,2,'Lumatte Synthetic Raw10 Fixture')
item(33422,1,[0,1,1,2]);item(50706,1,[1,4,0,0]);item(50707,1,[1,1,0,0]);item(50710,1,[0,1,2])
item(50714,5,[(64,1)]);item(50721,10,[(1 if i%4==0 else 0,1) for i in range(9)]);item(50728,5,[(1,1)]*3)
def build():
    start=8+2+12*len(tags)+4;extra=bytearray();entries=[]
    for tag,(kind,count,data) in sorted(tags.items()):
        if len(data)<=4: payload=data.ljust(4,b'\0')
        else:
            payload=struct.pack('<I',start+len(extra));extra.extend(data)
            if len(extra)%2:extra.append(0)
        entries.append(struct.pack('<HHI',tag,kind,count)+payload)
    return b'II'+struct.pack('<HIH',42,8,len(tags))+b''.join(entries)+struct.pack('<I',0)+extra
header=build();item(273,4,[len(header)]);header=build()
pixels=b''.join(struct.pack('<H',64+int(14000*x/(w-1))) for y in range(h) for x in range(w))
target=Path(sys.argv[1]);target.parent.mkdir(parents=True,exist_ok=True);target.write_bytes(header+pixels)
print(f'Synthetic Bayer DNG: {w}x{h}, 14-bit range in 16-bit container, {target.stat().st_size} bytes')
