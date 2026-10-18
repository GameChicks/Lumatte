"""Manual, user-authorized native JPEG halation checks. No Gradle hooks or APK build.

Pass an explicitly compiled Android validator and an external work directory. Optional
photos stay local to that directory/emulator. No upload, model production or AVD startup.
"""
from pathlib import Path
import argparse, hashlib, json, os, struct, subprocess
import numpy as np
from PIL import Image, ImageDraw
from validate_fotufilm_halation import effects, oracle, scene

ROOT = Path(__file__).resolve().parents[1]
ASSETS = ROOT/'core/media/src/main/assets/film/mobile-fotufilm-v1'


def main():
    parser=argparse.ArgumentParser()
    parser.add_argument('--work',type=Path,required=True)
    parser.add_argument('--validator',type=Path,required=True)
    parser.add_argument('--image',type=Path,action='append',default=[])
    parser.add_argument('--performance',action='store_true')
    args=parser.parse_args();work=args.work.resolve();work.mkdir(parents=True,exist_ok=True)
    config=json.loads((ROOT/'tools/.local/android-paths.json').read_text(encoding='utf-8-sig'))
    executable=Path(config['sdk'])/'platform-tools/adb.exe'
    serial=os.environ.get('LFM_ADB_SERIAL','emulator-5582')
    remote='/data/local/tmp/lfm-jpeg-look-validation'
    def adb(*arguments):
        r=subprocess.run([str(executable),'-s',serial,*map(str,arguments)],capture_output=True,text=True,timeout=120)
        if r.returncode:raise RuntimeError(r.stdout+r.stderr)
        return r.stdout
    adb('shell','mkdir','-p',remote)
    adb('push',args.validator,remote+'/validator');adb('shell','chmod','700',remote+'/validator')
    report={'checks':[],'renders':[],'photos':[],'performance':[]}
    def checked(name,value,limit=None):
        if limit is not None: assert value<=limit,(name,value,limit)
        else: assert value,name
        report['checks'].append({'name':name,'value':value,'limit':limit})
    def fixture(name,pixels,stock,amount=1.4,diffusion=0,ev=0):
        folder=ASSETS/stock;model=json.loads((folder/'model.json').read_text())
        prefix=work/name;h,w=pixels.shape[:2]
        prefix.with_suffix('.input').write_bytes(struct.pack('<II',w,h)+pixels.tobytes())
        data=bytearray()
        for stage in ['response','development','scan']:
            t=model['tables'][stage];data+=struct.pack('<I6f',t['size'],*t['minimum'],*t['maximum'])+(folder/f'{stage}.f32').read_bytes()
        prefix.with_suffix('.model').write_bytes(data)
        controls=np.append(effects(model),amount).astype('<f4');controls[0]=diffusion;controls[2]=ev
        for suffix in ['input','model']:adb('push',prefix.with_suffix('.'+suffix),remote+'/'+name+'.'+suffix)
        return prefix,controls,folder,model
    def render(f,pixels,mode='new',backend='cpu'):
        prefix,controls,_,_=f;v=controls.copy()
        if mode=='legacy':v[59]=0
        v.tofile(prefix.with_suffix('.effects'));adb('push',prefix.with_suffix('.effects'),remote+'/'+prefix.name+'.effects')
        extra=['off'] if mode=='off' else []
        timing=json.loads(adb('shell',remote+'/validator',remote+'/'+prefix.name,backend,*extra))
        report['renders'].append(dict(case=prefix.name,mode=mode,**timing))
        local=prefix.with_suffix('.'+mode+'.'+backend)
        adb('pull',remote+'/'+prefix.name+'.'+backend,local)
        return np.fromfile(local,np.uint8).reshape(pixels.shape)

    for stock,amount in [('cinestill_800t',1.4),('cinestill_400d',1.0)]:
        pixels=scene('lights',1600,900);f=fixture(stock+'-lights',pixels,stock,amount)
        off=render(f,pixels,'off');legacy=render(f,pixels,'legacy');new=render(f,pixels)
        gpu=render(f,pixels,backend='gpu')
        checked(stock+' CPU/GLES max byte error',int(np.abs(new.astype(int)-gpu.astype(int)).max()),1)
        delta=new.astype(int)-legacy.astype(int)
        checked(stock+' visible red rim',int(delta[...,0].max())>=30)
        checked(stock+' white lamp core unchanged',int(np.abs(delta[pixels[...,0]==255,:3]).max()),1)
        # Original physical field is still checked against independent SciPy convolution/pack math.
        v=f[1].copy();v[59]=0;v.tofile(f[0].with_suffix('.effects'))
        adb('push',f[0].with_suffix('.effects'),remote+'/'+f[0].name+'.effects')
        adb('shell',remote+'/validator',remote+'/'+f[0].name,'spatial')
        adb('pull',remote+'/'+f[0].name+'.spatial',f[0].with_suffix('.spatial'))
        actual=np.fromfile(f[0].with_suffix('.spatial'),'<f4').reshape(*pixels.shape[:2],3)
        expected,_=oracle(pixels,f[2],f[3]['halationReference']['fotufilm_kernel'])
        checked(stock+' physical oracle unchanged',float(np.max(np.abs(actual-expected))),2e-5)
        sheet=Image.new('RGB',(1600,930*3),'#202020');draw=ImageDraw.Draw(sheet)
        for i,(label,data) in enumerate([('OFF',off),('ORIGINAL PHYSICAL',legacy),('JPEG HALATION',new)]):
            draw.text((12,i*930+8),label,fill='white');sheet.paste(Image.fromarray(data).convert('RGB'),(0,i*930+30))
        sheet.save(work/(stock+'-synthetic.png'))

    for name,rgb in [('black',[0,0,0]),('middle-grey',[120,120,120]),('bright-flat',[230,230,230]),('white',[255,255,255])]:
        p=np.zeros((120,180,4),np.uint8);p[...,:3]=rgb;p[...,3]=255
        f=fixture(name,p,'cinestill_800t');old=render(f,p,'legacy');new=render(f,p)
        checked(name+' no global colour wash',int(np.abs(new.astype(int)-old.astype(int)).max()),1)
    p=np.full((1000,1000,4),16,np.uint8);p[...,3]=255;p[400:600,400:600,:3]=255
    f=fixture('visible-rim',p,'cinestill_800t');old=render(f,p,'legacy');new=render(f,p)
    rim=new[500,600:,0].astype(int)-old[500,600:,0].astype(int)
    checked('visible rim red rise at least 100',int(rim.max())>=100)
    width=int((rim>=rim.max()/2).sum())
    checked('visible rim half-height width at least 8 pixels',width>=8)
    checked('visible rim half-height width at most 18 pixels',width,18)
    checked('no distant coloured wash beyond 50 pixels',int(np.abs(rim[50:]).max()),1)
    f=fixture('diffusion',scene('lights',513,343),'cinestill_800t',diffusion=.25,ev=1)
    new=render(f,scene('lights',513,343));gpu=render(f,scene('lights',513,343),backend='gpu')
    checked('diffusion and exposure CPU/GLES',int(np.abs(new.astype(int)-gpu.astype(int)).max()),1)

    for i,path in enumerate(args.image):
        image=Image.open(path).convert('RGBA');pixels=np.asarray(image)
        name=f'photo-{i+1}';f=fixture(name,pixels,'cinestill_800t')
        legacy=render(f,pixels,'legacy');new=render(f,pixels)
        delta=new.astype(int)-legacy.astype(int)
        report['photos'].append({'case':name,'inputSha256':hashlib.sha256(path.read_bytes()).hexdigest(),
            'size':image.size,'redRiseMax':int(delta[...,0].max()),'redRiseOver10Pixels':int((delta[...,0]>10).sum())})
        for label,data in [('legacy',legacy),('new',new)]:Image.fromarray(data).convert('RGB').save(work/f'{name}-{label}.png')
        # Same photograph, exposure 0, grain off, no added export frame; not a claim to reproduce unknown prior settings.
        canvas=Image.new('RGB',(image.width*2,image.height+30),'#202020');draw=ImageDraw.Draw(canvas)
        for x,label,data in [(0,'ORIGINAL PHYSICAL',legacy),(image.width,'JPEG HALATION',new)]:
            draw.text((x+12,8),label,fill='white');canvas.paste(Image.fromarray(data).convert('RGB'),(x,30))
        canvas.save(work/f'{name}-compare.png')
        # Also verify the weaker daylight stock on the same real input.
        daylight=fixture(name+'-400d',pixels,'cinestill_400d',amount=1.0)
        day_old=render(daylight,pixels,'legacy');day_new=render(daylight,pixels)
        Image.fromarray(day_new).convert('RGB').save(work/f'{name}-400d-new.png')
    if args.performance:
        p=scene('lights',6000,4000);f=fixture('24mp',p,'cinestill_800t')
        for mode in ['legacy','new']:render(f,p,mode)
        report['performance']=[r for r in report['renders'] if r['case']=='24mp']
    (work/'results.json').write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
    print(json.dumps({'checks':len(report['checks']),'photos':len(report['photos']),'performance':report['performance']}),flush=True)

if __name__=='__main__':main()
