"""User-authorized, manual, scoped CineStill validation. No build/package hooks.

Requires the isolated Android native validator and a running D:-hosted emulator.
Independent public upstream packed kernels + SciPy spatial oracle; never reuses
the production solver/spread functions to calculate expected values.
"""
from pathlib import Path
import argparse, hashlib, json, os, shutil, struct, subprocess, sys
import numpy as np
from scipy.ndimage import convolve1d, map_coordinates
from PIL import Image

ROOT=Path(__file__).resolve().parents[1]
sys.path.insert(0,str(ROOT/'tools'))
from produce_fotufilm_models import Pack, SOURCE
ASSETS=ROOT/'core/media/src/main/assets/film/mobile-fotufilm-v1'
OUT=ROOT/'artifacts/validation/fotufilm-halation-20261017'
FIXTURES=OUT/'fixtures'
REMOTE='/data/local/tmp/lfm-halation-validation'

def adb(*args,timeout=180):
    config=ROOT/'tools/.local/android-paths.json'
    sdk=json.loads(config.read_text(encoding='utf-8-sig'))['sdk'] if config.exists() else os.environ.get('ANDROID_HOME',os.environ.get('ANDROID_SDK_ROOT'))
    executable=Path(sdk)/'platform-tools'/('adb.exe' if os.name=='nt' else 'adb') if sdk else shutil.which('adb')
    if not executable:raise RuntimeError('Set ANDROID_HOME or put adb on PATH')
    result=subprocess.run([str(executable),'-s',os.environ.get('LFM_ADB_SERIAL','emulator-5582'),*map(str,args)],capture_output=True,text=True,timeout=timeout)
    if result.returncode:raise RuntimeError(result.stdout+result.stderr)
    return result.stdout

def effects(model):
    g,h=model['grainReference'],model['halationReference'];k=h['fotufilm_kernel']
    maximum=np.array(model['tables']['scan']['maximum'])-.2
    return np.array([0,1,0,0,g['particle_area_um2'],*g['particle_scale'],*g['density_min'],*g['uniformity'],*maximum,
        *h['halation_strength'],*h['halation_first_sigma_um'],36,1,3,.5,g['spacing_um'],1,
        2.2,2,1.6,9.3,9.7,9.1,.78,.65,.67,*k['sigma_mm'],*np.ravel(k['weights']),*np.ravel(k['mix_matrix'])],dtype='<f4')

def sample_table(image,folder):
    desc=json.loads((folder/'model.json').read_text())['tables']['response'];n=desc['size']
    table=np.fromfile(folder/'response.f32',dtype='<f4').reshape(n,n,n,3)
    p=image[...,:3].astype(np.float32)/255
    low,high=np.array(desc['minimum'],dtype=np.float32),np.array(desc['maximum'],dtype=np.float32)
    coords=np.clip((p-low)/(high-low),0,1)*(n-1)
    return np.stack([map_coordinates(table[...,c],np.moveaxis(coords[...,::-1],-1,0),order=1,mode='nearest',prefilter=False) for c in range(3)],axis=-1)

def area(image,w,h):
    ih,iw=image.shape[:2]
    xs=np.arange(w+1,dtype=np.int64)*iw//w;ys=np.arange(h+1,dtype=np.int64)*ih//h
    # Integral image gives independent exact rectangle means without the production row loops.
    integral=np.pad(image.astype(np.float64),((1,0),(1,0),(0,0))).cumsum(0).cumsum(1)
    values=integral[ys[1:,None],xs[None,1:]]-integral[ys[:-1,None],xs[None,1:]]-integral[ys[1:,None],xs[None,:-1]]+integral[ys[:-1,None],xs[None,:-1]]
    return (values/(np.diff(ys)[:,None,None]*np.diff(xs)[None,:,None])).astype(np.float32)

def bilinear(field,px,py):
    return np.stack([map_coordinates(field[...,c],np.array(np.broadcast_arrays(py,px)),order=1,mode='nearest',prefilter=False) for c in range(3)],axis=-1)

def spread(source,sigma):
    radius=int((np.sqrt(np.float32(4)*sigma*sigma+1)-1)*.5) if sigma>.3 else 0
    box_sigma=np.sqrt(np.float32(radius*(radius+1)))
    stride=1
    while stride<8 and 2*stride*2.5<=box_sigma:stride*=2
    r=radius
    if stride>1:r=max(1,int((np.sqrt(4*max(.25,box_sigma*box_sigma/(stride*stride)-.25)+1)-1)*.5+.5))
    sh,sw=source.shape[:2];dw=(sw+stride-1)//stride;dh=(sh+stride-1)//stride
    pad=np.pad(source,((0,dh*stride-sh),(0,dw*stride-sw),(0,0)))
    valid=np.pad(np.ones((sh,sw),np.float32),((0,dh*stride-sh),(0,dw*stride-sw)))
    down=pad.reshape(dh,stride,dw,stride,3).sum((1,3))/valid.reshape(dh,stride,dw,stride).sum((1,3))[...,None]
    box=np.full(2*r+1,1/(2*r+1),np.float32)
    kernel=np.convolve(np.convolve(box,box),box)
    data=down
    for axis in [1,0]:
        norm=convolve1d(np.ones(data.shape[:2],np.float32),kernel,axis=axis,mode='constant',cval=0)
        data=convolve1d(data,kernel,axis=axis,mode='constant',cval=0)/norm[...,None]
    return bilinear(data,(np.arange(sw)[None,:]+.5)/stride-.5,(np.arange(sh)[:,None]+.5)/stride-.5)

def oracle(image,folder,k,edge=1536):
    raw=sample_table(image,folder);ih,iw=image.shape[:2]
    scale=min(1,edge/max(iw,ih));w=min(edge,int(np.ceil(iw*scale)));h=min(edge,int(np.ceil(ih*scale)))
    reduced=area(raw,w,h);scattered=np.zeros_like(reduced)
    for scale in range(3):
        field=spread(reduced,np.float32(k['sigma_mm'][scale])*np.float32(min(w,h)/24))
        scattered+=field*np.array(k['weights'],np.float32)[:,scale]
    returned=scattered@np.array(k['mix_matrix'],np.float32).T
    halo=bilinear(returned,(np.arange(iw)[None,:]+.5)*w/iw-.5,(np.arange(ih)[:,None]+.5)*h/ih-.5)
    return raw+halo-raw@np.array(k['mix_matrix'],np.float32).T,raw

def scene(name,w,h):
    image=np.zeros((h,w,4),np.uint8);image[...,3]=255
    if name=='constant':image[...,:3]=[177,108,37]
    elif name=='edge':
        image[:,:w//2,:3]=[241,209,153]
        image[h//3:2*h//3,w//2:,:3]=[7,117,219]
    else:
        image[...,:3]=[3,8,17]
        image[h//5:h//5+max(1,h//90),w//8:7*w//8,:3]=[255,255,241]
        image[h//2:h//2+max(1,h//80),w//3:w//3+max(1,w//5),:3]=[255,233,210]
        image[3*h//4,3*w//4,:3]=255
        image[h//3:h//3+max(1,h//80),w//4:w//4+max(1,w//40),:3]=[11,247,255]
    return image

def fixture(name,image,folder,model):
    prefix=FIXTURES/name
    with (prefix.with_suffix('.input')).open('wb') as f:f.write(struct.pack('<II',image.shape[1],image.shape[0]));f.write(image.tobytes())
    with prefix.with_suffix('.model').open('wb') as f:
        for stage in ['response','development','scan']:
            desc=model['tables'][stage];f.write(struct.pack('<I6f',desc['size'],*desc['minimum'],*desc['maximum']));f.write((folder/f'{stage}.f32').read_bytes())
    effects(model).tofile(prefix.with_suffix('.effects'))
    return prefix

def main():
    parser=argparse.ArgumentParser();parser.add_argument('--prepare-only',action='store_true')
    parser.add_argument('--performance-only',action='store_true');args=parser.parse_args()
    FIXTURES.mkdir(parents=True,exist_ok=True)
    if args.performance_only:
        folder=ASSETS/'cinestill_800t';model=json.loads((folder/'model.json').read_text())
        prefix=fixture('cinestill_800t-24mp',scene('lights',6000,4000),folder,model)
        adb('shell','mkdir','-p',REMOTE+'/fixtures')
        adb('push',ROOT/'tools/.cache/fotu-halation-validator',REMOTE+'/validator')
        adb('shell','chmod','700',REMOTE+'/validator')
        for suffix in ['input','model','effects']:adb('push',prefix.with_suffix('.'+suffix),REMOTE+'/fixtures/')
        timings=[]
        for backend,toggle in [('prepare','on'),('cpu','off'),('cpu','on'),('gpu','off'),('gpu','on')]:
            extra=['off'] if toggle=='off' else []
            timing=json.loads(adb('shell',REMOTE+'/validator',REMOTE+'/fixtures/'+prefix.name,backend,*extra,timeout=180).strip())
            timings.append(timing);print(json.dumps(timing),flush=True)
            (OUT/'performance-results.json').write_text(json.dumps({'timings':timings,
                'environment':'Android x86_64 emulator, host GPU, Windows; cold native render plus raw output write, excludes model/input load, JPEG, EXIF and frame; no Snapdragon 865 claim'},indent=2)+'\n')
        return
    results={'scope':'user-authorized CineStill halation only','kernel':[],'spatial':[],'cpuGles':[],'samplingBudget':[],'timings':[],
        'performanceEnvironment':'Android x86_64 emulator on Windows; no Snapdragon 865 or end-to-end JPEG export claim'}
    config=json.loads((SOURCE/'config-layout.json').read_text())
    jobs=[]
    for upstream,stock in [('cinestill800t','cinestill_800t'),('cinestill400d','cinestill_400d')]:
        folder=ASSETS/stock;model=json.loads((folder/'model.json').read_text());k=model['halationReference']['fotufilm_kernel']
        pack=Pack(SOURCE/'public-packs'/f'{upstream}.pack',config)
        weight_error=float(np.max(abs(np.ravel(k['weights'])-pack.get('HALATION_KERNEL',9))))
        matrix_error=float(np.max(abs(np.ravel(k['mix_matrix'])-pack.get('HALATION_MATRIX',9))))
        sigma=np.array(k['sigma_mm'],np.float32)*np.float32(900/24)
        radii=((np.sqrt(4*sigma*sigma+1)-1)*.5).astype(np.int32)
        entry={'stock':stock,'weightMaxAbsError':weight_error,'matrixMaxAbsError':matrix_error,'radii1600x900':radii.tolist(),
            'packSha256':hashlib.sha256(pack.bytes).hexdigest(),'packCommitNotPublished':True}
        assert weight_error<2e-6 and matrix_error<1e-7 and np.array_equal(radii,pack.get('HALATION_RADIUS',3)),entry
        results['kernel'].append(entry)
        for name,w,h in [('constant',65,49),('edge',257,171),('lights',513,343),('sampling',2000,1333)]:
            image=scene(name,w,h);prefix=fixture(stock+'-'+name,image,folder,model)
            jobs.append((prefix,image,folder,k,name))
    (OUT/'kernel-results.json').write_text(json.dumps(results,indent=2)+'\n')
    if args.prepare_only:print(json.dumps(results));return
    adb('shell','mkdir','-p',REMOTE)
    adb('push',ROOT/'tools/.cache/fotu-halation-validator',REMOTE+'/validator')
    adb('shell','chmod','700',REMOTE+'/validator')
    adb('push',str(FIXTURES),REMOTE)
    for prefix,image,folder,k,name in jobs:
        remote=REMOTE+'/fixtures/'+prefix.name
        timing=json.loads(adb('shell',REMOTE+'/validator',remote,'spatial').strip())
        adb('pull',remote+'.spatial',prefix.with_suffix('.spatial'))
        actual=np.fromfile(prefix.with_suffix('.spatial'),dtype='<f4').reshape(*image.shape[:2],3)
        expected,raw=oracle(image,folder,k)
        error=abs(actual-expected)
        entry={'case':prefix.name,'maxAbsError':float(error.max()),'meanAbsError':float(error.mean()),'finite':bool(np.isfinite(actual).all())}
        if name=='constant':entry['flatFieldMaxDrift']=float(abs(actual-raw).max())
        assert entry['finite'] and entry['maxAbsError']<2e-5,entry
        if name=='constant':assert entry['flatFieldMaxDrift']<2e-6,entry
        results['spatial'].append(entry);results['timings'].append(dict(case=prefix.name,**timing))
        print(json.dumps(entry),flush=True)
        if name=='sampling':
            full,_=oracle(image,folder,k,edge=max(image.shape[:2]))
            difference=abs(expected-full)
            results['samplingBudget'].append({'case':prefix.name,'fullFieldLongEdge':max(image.shape[:2]),
                'cappedFieldLongEdge':1536,'maxAbsExposureDifference':float(difference.max()),
                'meanAbsExposureDifference':float(difference.mean()),
                'scope':'Synthetic strips and one-pixel light only; not a universal image quality percentage'})
        if name in ['lights','sampling']:
            rendered={}
            for backend in ['cpu','gpu']:
                timing=json.loads(adb('shell',REMOTE+'/validator',remote,backend).strip())
                adb('pull',remote+'.'+backend,prefix.with_suffix('.'+backend))
                rendered[backend]=np.fromfile(prefix.with_suffix('.'+backend),np.uint8).reshape(image.shape)
                Image.fromarray(rendered[backend]).save(OUT/(prefix.name+'-'+backend+'.png'))
                results['timings'].append(dict(case=prefix.name,**timing))
            delta=abs(rendered['cpu'].astype(np.int16)-rendered['gpu'].astype(np.int16))
            entry={'case':prefix.name,'maxByteDifference':int(delta.max()),'meanByteDifference':float(delta.mean())}
            assert entry['maxByteDifference']<=2,entry
            results['cpuGles'].append(entry)
            if name=='lights':
                adb('shell',REMOTE+'/validator',remote,'cpu','off')
                adb('pull',remote+'.cpu',prefix.with_suffix('.off'))
                off=np.fromfile(prefix.with_suffix('.off'),np.uint8).reshape(image.shape)
                Image.fromarray(off).save(OUT/(prefix.name+'-off.png'))
                pair=Image.new('RGB',(image.shape[1]*2,image.shape[0]))
                pair.paste(Image.fromarray(off).convert('RGB'),(0,0))
                pair.paste(Image.fromarray(rendered['cpu']).convert('RGB'),(image.shape[1],0))
                pair.save(OUT/(prefix.name+'-off-on.png'))
                results.setdefault('toggle',[]).append({'case':prefix.name,
                    'maxByteChange':int(abs(off.astype(np.int16)-rendered['cpu'].astype(np.int16)).max())})
                if prefix.name.startswith('cinestill_800t'):
                    mist=fixture(prefix.name+'-mist',image,folder,json.loads((folder/'model.json').read_text()))
                    controls=np.fromfile(mist.with_suffix('.effects'),dtype='<f4');controls[0]=.25
                    controls.tofile(mist.with_suffix('.effects'))
                    for suffix in ['input','model','effects']:adb('push',mist.with_suffix('.'+suffix),REMOTE+'/fixtures/')
                    outputs={}
                    for backend in ['cpu','gpu']:
                        adb('shell',REMOTE+'/validator',REMOTE+'/fixtures/'+mist.name,backend)
                        adb('pull',REMOTE+'/fixtures/'+mist.name+'.'+backend,mist.with_suffix('.'+backend))
                        outputs[backend]=np.fromfile(mist.with_suffix('.'+backend),np.uint8).astype(np.int16)
                    diff=abs(outputs['cpu']-outputs['gpu'])
                    entry={'case':mist.name,'diffusion':.25,'maxByteDifference':int(diff.max()),'meanByteDifference':float(diff.mean())}
                    assert entry['maxByteDifference']<=2,entry
                    results['cpuGles'].append(entry)
        (OUT/'results.json').write_text(json.dumps(results,indent=2)+'\n')
    print('PASS: two upstream packed kernels, eight spatial cases and five CPU/GLES comparisons',flush=True)

if __name__=='__main__':main()
