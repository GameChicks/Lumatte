"""Bake six licensed Fotufilm profiles into the Android three-stage transport.

Explicit asset production, not a test, image render, benchmark or APK build.
Inputs are preserved public FSWP demo packs, not encrypted distribution containers.
Pointwise curve/coupler/print equations adapted from Fotufilm (Apache-2.0).
Profiles and derived tables: CC-BY-SA-4.0, MUAStudio Inc., https://fotufilm.com.
The bounded mobile spatial/grain approximation is NOT full-engine parity.
"""
from __future__ import annotations

import hashlib
import json
from pathlib import Path
import shutil
import struct

import numpy as np
from produce_fotufilm_halation import halation_reference, ALGORITHM as HALATION_ALGORITHM

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / "research/fotufilm"
DEST = ROOT / "core/media/src/main/assets/film/mobile-fotufilm-v1"
VERSION = "lfm-fotufilm-public-three-stage-v1"
COMMIT = "12c8efea259e4cea70ec22166e504634d401a0cd"
STOCKS = (
    ("cinestill800t", "cinestill_800t", "CineStill 800T", "CINEMA", "钨丝灯夜景；独立红橙光晕，保留明亮灯芯。"),
    ("cinestill400d", "cinestill_400d", "CineStill 400D", "NEGATIVE", "日光彩色负片；暖色返光比 800T 克制。"),
    ("acros100", "fujifilm_acros_100_ii", "Fujifilm Acros 100 II", "MONOCHROME", "细腻颗粒与平顺灰阶，适合建筑和日常光影。"),
    ("trix400", "kodak_tri_x_400", "Kodak Tri-X 400", "MONOCHROME", "鲜明的黑白层次与可见颗粒，适合街头纪实。"),
    ("hp5plus400", "ilford_hp5_plus_400", "Ilford HP5 Plus 400", "MONOCHROME", "宽容的中间调与柔和过渡，适合人像和日常。"),
    ("delta3200", "ilford_delta_3200", "Ilford Delta 3200", "MONOCHROME", "明显颗粒与低照度气氛，适合夜间和室内。"),
)
SRGB_2020 = np.array([[.627403896,.329283038,.043313066],[.069097289,.919540395,.011362316],[.016391439,.088013308,.895595253]])
DOMAIN = np.array([[.670231843,.152168745,.177599412],[.044501114,.854482372,.101016514],[0,.025777047,.974222953]])
P3_SRGB = np.array([[1.22494018,-.224940176,0],[-.042056955,1.04205695,0],[-.019637555,-.078636046,1.09827360]])


def digest(data):
    return hashlib.sha256(data).hexdigest()


def write_json(path, value):
    path.write_bytes((json.dumps(value, ensure_ascii=False, indent=2, allow_nan=False)+"\n").encode())


def grid(size, low, high):
    axes = [np.linspace(low[c], high[c], size) for c in range(3)]
    z,y,x = np.meshgrid(axes[2], axes[1], axes[0], indexing="ij")
    return np.stack((x,y,z), axis=-1).reshape(-1,3)


def sample(cube, rgb):
    n = cube.shape[0]
    q = np.clip(rgb,0,1)*(n-1)
    lo = np.floor(q).astype(int)
    hi = np.minimum(lo+1,n-1)
    f = q-lo
    out = np.zeros_like(rgb)
    for z in (0,1):
        for y in (0,1):
            for x in (0,1):
                ix = hi[:,0] if x else lo[:,0]
                iy = hi[:,1] if y else lo[:,1]
                iz = hi[:,2] if z else lo[:,2]
                weight = (f[:,0] if x else 1-f[:,0])*(f[:,1] if y else 1-f[:,1])*(f[:,2] if z else 1-f[:,2])
                out += cube[iz,iy,ix,:3]*weight[:,None]
    return out


class Pack:
    def __init__(self, path, config):
        self.bytes = path.read_bytes()
        magic,version,self.width,self.height,self.mask,_,count,n,lut_count,self.has_paper = struct.unpack_from("<4s9I",self.bytes)
        if magic != b"FSWP" or version != 2 or count != config["FOTUFILM_FRAME_CONFIGURATION_COUNT"] or n != 33 or lut_count != n**3*4:
            raise ValueError(f"Unsupported public pack ABI: {path.name}")
        payload = np.frombuffer(self.bytes,dtype="<f4",offset=40,count=count+lut_count*3)
        self.c = payload[:count].astype(float)
        self.k = config
        self.exposure,self.film,self.paper = [payload[count+i*lut_count:count+(i+1)*lut_count].reshape(n,n,n,4).astype(float) for i in range(3)]
        if not np.isfinite(payload).all():
            raise ValueError("Non-finite source pack")

    def get(self,name,count=1):
        i=self.k[name]
        return self.c[i] if count==1 else self.c[i:i+count]

    def curve_range(self):
        p=self.get("CURVES",18).reshape(3,6)
        s=self.get("CURVE_SECONDARY",15).reshape(3,5)
        return p[:,1]*(p[:,4]-p[:,2])+s[:,0]*(s[:,3]-s[:,1])

    def density(self,x):
        out=np.empty_like(x)
        for c in range(3):
            base=self.k["SAMPLED_CURVES"]+3073*c
            count=int(self.c[base])
            if count>=2:
                knots=self.c[base+1:base+1+count*3].reshape(count,3)
                j=np.clip(np.searchsorted(knots[:,0],x[:,c],side="right")-1,0,count-2)
                h=np.maximum(knots[j+1,0]-knots[j,0],1e-12)
                t=np.clip((x[:,c]-knots[j,0])/h,0,1)
                y0=knots[j,1]; delta=knots[j+1,1]-y0
                a=h*knots[j,2]; b=h*knots[j+1,2]
                out[:,c]=y0+t*(a+t*(3*delta-2*a-b+t*(-2*delta+a+b)))
                out[:,c]=np.where(x[:,c]<=knots[0,0],knots[0,1],out[:,c])
                out[:,c]=np.where(x[:,c]>=knots[-1,0],knots[-1,1],out[:,c])
            else:
                out[:,c]=self.analytic(self.c[c*6:c*6+6],x[:,c])
                s=self.get("CURVE_SECONDARY",15).reshape(3,5)[c]
                out[:,c]+=self.analytic(np.r_[0,s],x[:,c])
        return out

    @staticmethod
    def analytic(p,x):
        d,g,t,tw,s,sw=p
        tw=max(tw,1e-6); sw=max(sw,1e-6)
        return d+g*np.clip(tw*np.logaddexp(0,(x-t)/tw)-sw*np.logaddexp(0,(x-s)/sw),0,max(s-t,0))

    def develop(self,x):
        base=self.get("CURVES",18).reshape(3,6)[:,0]
        span=self.curve_range()
        if self.mask & 8:  # FOTUFILM_FRAME_COUPLERS; local term only.
            a=np.clip((self.density(x)-base)/span,0,1)
            gamma=self.get("COUPLER_RELEASE_GAMMA",3)
            release=a**gamma/np.maximum(a**gamma+(1-a)**gamma,1e-8)
            u=x-release@self.get("COUPLER",9).reshape(3,3).T*self.get("COUPLER_SCALE")
            q=np.clip((u+4)*(127/8),0,127)
            i=np.minimum(q.astype(int),126); f=q-i
            nonlinear=np.any(gamma!=1) or self.get("DONOR_RELEASE_GAMMA")!=1
            warp=self.get("COUPLER_WARP",384).reshape(3,128)
            for c in range(3):
                y0=warp[c,i[:,c]]; y1=warp[c,i[:,c]+1]; t=f[:,c]
                moved=y0+t*(y1-y0)
                if nonlinear:
                    delta=np.maximum(y1-y0,0)
                    a0=np.clip(.5*(y1-warp[c,np.maximum(i[:,c]-1,0)]),0,3*delta)
                    b0=np.clip(.5*(warp[c,np.minimum(i[:,c]+2,127)]-y0),0,3*delta)
                    moved=(2*t**3-3*t*t+1)*y0+(t**3-2*t*t+t)*a0+(-2*t**3+3*t*t)*y1+(t**3-t*t)*b0
                u[:,c]+=moved
            x=u
        out=self.density(x)
        return 2*base+span-out if self.get("DEVELOP_COMPLEMENT")>.5 else out

    def response(self,rgb):
        linear=np.where(rgb<=.04045,rgb/12.92,((rgb+.055)/1.055)**2.4)
        domain=(linear@SRGB_2020.T)@DOMAIN.T
        peak=np.maximum(domain.max(axis=1,keepdims=True),1e-6)
        return np.maximum(sample(self.exposure,domain/peak)*peak*self.get("EXPOSURE_GAIN")/.18,0)

    def scan(self,density,mono):
        base=self.get("CURVES",18).reshape(3,6)[:,0]
        relative=sample(self.film,(density-base)/self.curve_range())
        if self.has_paper:
            flashed=self.get("PRINTER_PREFLASH")
            if flashed>0: relative=np.log10(10**relative+flashed)
            pbase=[self.k["PAPER_RED"],self.k["PAPER"],self.k["PAPER_BLUE"]]
            mid=[self.get("PAPER_MIDPOINT_RED"),self.get("PAPER_MIDPOINT"),self.get("PAPER_MIDPOINT_BLUE")]
            activation=np.empty_like(relative)
            for c in range(3):
                p=self.c[pbase[c]:pbase[c]+6]
                loge=mid[c]+self.get("MASKING",3)[c]*relative[:,c]
                activation[:,c]=(self.analytic(p,loge)-p[0])/(p[1]*(p[4]-p[2]))
            relative=sample(self.paper,activation)
        if mono: relative[:]=relative.mean(axis=1,keepdims=True)
        relative=np.maximum((relative+self.get("GRADE_LIFT",3))*self.get("GRADE_GAIN",3),0)**self.get("GRADE_INV_GAMMA",3)
        linear=relative@P3_SRGB.T
        knee=self.get("OUTPUT_SHOULDER")
        if 0<=knee<1:
            over=np.maximum(linear-knee,0); room=1-knee
            linear=np.where(linear>knee,knee+room*over/(over+room),linear)
        linear=np.clip(linear,0,1)
        rgb=np.where(linear<=.0031308,linear*12.92,1.055*linear**(1/2.4)-.055)
        if mono: rgb[:]=rgb.mean(axis=1,keepdims=True)
        return rgb


def table(folder,name,size,low,high,values,input_space,output_space):
    values=np.asarray(values,dtype="<f4")
    if values.size!=size**3*3 or not np.isfinite(values).all():
        raise ValueError(f"Cannot serialize {name}")
    payload=values.tobytes()
    (folder/f"{name}.f32").write_bytes(payload)
    return dict(file=f"{name}.f32",size=size,bytes=len(payload),sha256=digest(payload),minimum=list(map(float,low)),maximum=list(map(float,high)),shaper="linear",inputSpace=input_space,outputSpace=output_space)


def main():
    config=json.loads((SOURCE/"config-layout.json").read_text())
    sources=json.loads((SOURCE/"source-index.json").read_text())
    stage=ROOT/"tools/.cache/fotufilm-production"/VERSION
    stage.mkdir(parents=True,exist_ok=True)
    entries=[]; catalogue=[]
    for upstream,stock,name,category,description in STOCKS:
        pack_path=SOURCE/"public-packs"/f"{upstream}.pack"
        profile_path=SOURCE/"profiles"/f"{upstream}.json"
        if digest(pack_path.read_bytes())!=sources["files"][f"public-packs/{upstream}.pack"] or digest(profile_path.read_bytes())!=sources["files"][f"profiles/{upstream}.json"]:
            raise ValueError("Source snapshot changed; record a new version deliberately")
        pack=Pack(pack_path,config)
        profile=json.loads(profile_path.read_text())
        mono=category=="MONOCHROME"
        folder=stage/stock; folder.mkdir(exist_ok=True)
        response=table(folder,"response",33,[0]*3,[1]*3,pack.response(grid(33,[0]*3,[1]*3)),"srgb_encoded","film_raw_linear")
        development=table(folder,"development",65,[-4]*3,[2]*3,pack.develop(grid(65,[-4]*3,[2]*3)),"film_log10_exposure","film_cmy_density")
        base=pack.get("CURVES",18).reshape(3,6)[:,0]
        maximum=base+pack.curve_range()
        scan=table(folder,"scan",41,[-.2]*3,maximum+.2,pack.scan(grid(41,[-.2]*3,maximum+.2),mono),"film_cmy_density","srgb_encoded")
        weights=np.array(profile["grainLayerWeights"])
        anchor=np.minimum(1,pack.curve_range()*.8)
        p=anchor/np.maximum(maximum,1e-5)
        area=(profile["grainStrength"]*weights)**2*48**2/np.maximum(maximum**2*p*(1-p*.9),1e-6)
        grain={"active":True,"particle_area_um2":.1,"particle_scale":np.clip(area/.1,.0001,20).tolist(),"density_min":[.02]*3,"uniformity":[.9]*3,
               "spacing_um":min(40,max(.1,float(profile["grainSizeMM"])*1000))}
        cine=upstream.startswith("cinestill")
        halo=halation_reference(profile) if cine else {"halation_strength":[.05,.015,0],
              "halation_first_sigma_um":[65]*3,"halation_n_bounces":3,
              "halation_bounce_decay":.5,"halation_renormalize":True}
        metadata={"schema":1,"modelVersion":VERSION,"stockId":stock,"printId":"fotu_fixed_print","positive":False,
                  "source":{"origin":"fotufilm_public_pack_adapter","repository":"https://github.com/DhaliwalX/fotufilm-engine","commit":COMMIT,"packSnapshot":sources["snapshot"],"packSha256":digest(pack.bytes),"profileSha256":digest(profile_path.read_bytes()),"author":"Fotufilm / MUAStudio Inc.","license":"CC-BY-SA-4.0"},
                  "storage":"float32_le_rgb_x_fastest","dir":"baked_local_only","spatialEffectsBaked":False,"grainBaked":False,
                  "tables":{"response":response,"development":development,"scan":scan},"grainReference":grain,"halationReference":halo,
                  "settingsSnapshot":{"camera":{"film_format_mm":36.0}},
                  "mobileAdaptation":{"monochrome":mono,"halationModel":HALATION_ALGORITHM if cine else "spektrafilm_default_shape","fullEngineParity":False,"sampledCurves":True,"sceneMetering":False,"spatialDIR":False,"grain":"density-statistical-approximation"}}
        write_json(folder/"model.json",metadata)
        entries.append(dict(stockId=stock,printId="fotu_fixed_print",positive=False,modelVersion=VERSION,manifestSha256=digest((folder/"model.json").read_bytes()),tableBytes=sum(t["bytes"] for t in (response,development,scan))))
        catalogue.append(dict(id=stock,name=name,category=category,printId="fotu_fixed_print",positive=False,description=description+" 移动近似风格，非实测胶卷或完整上游引擎一致性声明。"))
        print(f"Produced {name}: {entries[-1]['tableBytes']} bytes; fixed grey={pack.scan(pack.develop(np.zeros((1,3))),mono)[0].round(4)}",flush=True)
    write_json(stage/"index.json",dict(schema=1,modelVersion=VERSION,origin="fotufilm_public_pack_adapter",sourceCommit=COMMIT,models=entries))
    write_json(stage/"catalogue.json",dict(stocks=catalogue))
    shutil.copyfile(SOURCE/"licenses/CC-BY-SA-4.0.txt",stage/"CC-BY-SA-4.0.txt")
    shutil.copyfile(SOURCE/"licenses/LICENSE",stage/"Apache-2.0.txt")
    shutil.copyfile(SOURCE/"licenses/NOTICE",stage/"UPSTREAM-NOTICE.txt")
    (stage/"NOTICE.txt").write_text("Fotufilm profiles and derived tables: CC BY-SA 4.0. Copyright MUAStudio Inc.\nAttribution: Fotufilm, https://fotufilm.com, https://github.com/DhaliwalX/fotufilm-engine\nPointwise equations adapted from Fotufilm, Apache-2.0; original NOTICE preserved in research/fotufilm/licenses.\nChanges: fixed public-demo pack snapshot to three sRGB mobile tables; no scene auto levels, spatial DIR or full-engine grain; CineStill physical default halation kernel adapted from pinned upstream, with bounded mobile spatial sampling.\nSource profiles, public pack hashes and adapter are preserved in research/fotufilm and tools/produce_fotufilm_models.py.\nNot calibrated physical-film, full-engine-parity, rendered-photo or device-performance verification.\n",encoding="utf-8")
    DEST.mkdir(parents=True,exist_ok=True)
    shutil.copytree(stage,DEST,dirs_exist_ok=True)


if __name__=="__main__":
    main()
