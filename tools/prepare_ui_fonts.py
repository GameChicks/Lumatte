"""Manual UI font installation/subsetting. No build hooks, tests or Android packaging.

OPPO 4.0 stays byte-for-byte unchanged. The OFL Tongyuan UI subset changes its
family name and retains upstream copyright/license records; no proprietary glyphs.
"""
from pathlib import Path
from urllib.request import Request, urlopen
import argparse, hashlib, json, shutil, zipfile
from fontTools import subset
from fontTools.ttLib import TTFont

ROOT = Path(__file__).resolve().parents[1]
OPPO_URL = 'https://coloros-website-cn.allawnfs.com/font/OPPO_Sans_4.0.zip'
JINZI_URL = 'https://oss.maoken.com/%E7%8C%AB%E5%95%83%E5%AD%97%E4%BD%93/%E4%B8%AD%E5%9B%BD%E5%A4%A7%E9%99%86/%E9%87%91%E5%AD%97%E7%A4%BE%E7%BB%9F%E5%9C%86%E4%BD%93_%E7%8C%AB%E5%95%83%E7%BD%91.zip'
INPUTS = {
    'oppo-sans-4.0.zip': (OPPO_URL, '412d10fd4ed8447e31eec913a9bd4814816ce9614c12eaae23f77dcb0f54f97d'),
    'jinzi-round.zip': (JINZI_URL, 'f3052954051a82205694e6bcf1410f062d7e37401c2ce1a735262c8a45c9169d'),
    'BebasNeue-Regular.ttf': ('https://raw.githubusercontent.com/google/fonts/main/ofl/bebasneue/BebasNeue-Regular.ttf',
        '08e4623805102d819f58601e46e345648846075e363b2ceb23313c2d1c83ec73'),
    'BebasNeue-OFL.txt': ('https://raw.githubusercontent.com/google/fonts/main/ofl/bebasneue/OFL.txt',
        '72082f6cb4d04be2ecf7cc7d9e1e7d73787f0af8a5a278a47cade70c16b78341'),
}

def sha(data): return hashlib.sha256(data).hexdigest()

def main():
    parser=argparse.ArgumentParser();parser.add_argument('--work',type=Path,required=True)
    parser.add_argument('--download',action='store_true');args=parser.parse_args()
    args.work.mkdir(parents=True,exist_ok=True)
    for name,(url,digest) in INPUTS.items():
        path=args.work/name
        if not path.exists() and args.download:
            with urlopen(Request(url,headers={'User-Agent':'Mozilla/5.0','Referer':'https://www.coloros.com/' if name.startswith('oppo') else 'https://www.maoken.com/'}),timeout=60) as response:
                data=response.read(64*1024*1024+1)
            if len(data)>64*1024*1024 or sha(data)!=digest:raise ValueError('Unexpected font download: '+name)
            path.write_bytes(data)
        if not path.exists() or sha(path.read_bytes())!=digest:raise ValueError('Missing/mismatched font input: '+str(path))
    fonts=ROOT/'core/design/src/main/res/font';licences=ROOT/'core/design/src/main/assets/licenses'
    fonts.mkdir(parents=True,exist_ok=True);licences.mkdir(parents=True,exist_ok=True)
    with zipfile.ZipFile(args.work/'oppo-sans-4.0.zip') as z:
        oppo=z.read('OPPO_Sans_4.0/OPPO Sans 4.0.ttf')
        (fonts/'oppo_sans_4.ttf').write_bytes(oppo)
        (licences/'oppo-sans-4-license.txt').write_bytes(z.read('OPPO_Sans_4.0/OPPO Sans 4.0 License Notice.txt'))
    shutil.copyfile(args.work/'BebasNeue-Regular.ttf',fonts/'bebas_neue.ttf')
    shutil.copyfile(args.work/'BebasNeue-OFL.txt',licences/'bebas-neue-OFL.txt')
    with zipfile.ZipFile(args.work/'jinzi-round.zip') as z:
        original=z.read(next(n for n in z.namelist() if n.endswith('/JinzisheTongyuan-Regular.ttf')))
        (args.work/'JinzisheTongyuan-Regular.ttf').write_bytes(original)
        (licences/'jinzi-tongyuan-OFL.txt').write_bytes(z.read(next(n for n in z.namelist() if n.endswith('/OFL.txt'))))
    home=(ROOT/'app/src/main/kotlin/com/lumixfilmmirror/app/MirrorHome.kt').read_text(encoding='utf-8')
    requested=set(chr(i) for i in range(32,127)) | {c for c in home if ord(c)>=128}
    font=TTFont(args.work/'JinzisheTongyuan-Regular.ttf',recalcTimestamp=False)
    charset=sorted(ord(c) for c in requested if ord(c) in font.getBestCmap())
    options=subset.Options();options.name_IDs=['*'];options.name_legacy=True;options.name_languages=['*']
    sub=subset.Subsetter(options=options);sub.populate(unicodes=charset);sub.subset(font)
    family='Pineframe Tongyuan UI'
    for record in font['name'].names:
        values={1:family,2:'Regular',3:'PineframeTongyuanUI-v1',4:family+' Regular',6:'PineframeTongyuanUI-Regular',16:family,17:'Regular'}
        if record.nameID in values:record.string=values[record.nameID].encode(record.getEncoding())
    font.save(fonts/'jinzi_tongyuan_ui.ttf')
    manifest={'schema':1,'inputs':{name:{'url':url,'sha256':digest} for name,(url,digest) in INPUTS.items()},
        'oppo':{'family':'OPPO Sans 4.0','fileVersion':'1.700','variableWeight':[100,400,700],'modified':False,'sha256':sha(oppo)},
        'tongyuan':{'upstreamFamily':'Jinzishe Tongyuan','upstreamAuthor':'金留庆 / Roman Czyborra / Paul Hardy',
            'authorPage':'https://www.typeface.cn/index.php?title=Project:金字社统圆体','sourceSha256':sha(original),
            'family':family,'modification':'UI character subset only; outlines unchanged; renamed derivative; original notices retained',
            'license':'OFL-1.1','unicode':charset,'sha256':sha((fonts/'jinzi_tongyuan_ui.ttf').read_bytes())},
        'bebas':{'family':'Bebas Neue','license':'OFL-1.1','modified':False}}
    (ROOT/'third_party/provenance/font-sources.json').write_text(json.dumps(manifest,ensure_ascii=False,indent=2)+'\n',encoding='utf-8',newline='\n')
    print('Installed original OPPO Sans 4.0 / Bebas Neue and renamed OFL Tongyuan subset:',len(charset),'characters')

if __name__=='__main__':main()
