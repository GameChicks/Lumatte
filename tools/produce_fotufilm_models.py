"""Bake matching licensed Fotufilm profiles into the Android three-stage transport.

Explicit asset production, not a test, image render, benchmark or APK build.
Inputs are offline FSWP packs produced by the pinned public WASI profile builder.
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
from scipy.interpolate import LinearNDInterpolator
from scipy.spatial import cKDTree

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / "research/fotufilm/model-reference-e15b098"
DEST = ROOT / "core/media/src/main/assets/film/mobile-fotufilm-v1"
VERSION = "lfm-fotufilm-catalogue-v3"
COMMIT = "e15b09815a83b0e00ba9381b65d31d253667a1b1"
PACKS = ROOT / "tools/.cache/fotufilm-profile-builder"
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


def sample(cube, rgb, channels=3):
    n = cube.shape[0]
    q = np.clip(rgb,0,1)*(n-1)
    lo = np.floor(q).astype(int)
    hi = np.minimum(lo+1,n-1)
    f = q-lo
    out = np.zeros((len(rgb),channels))
    for z in (0,1):
        for y in (0,1):
            for x in (0,1):
                ix = hi[:,0] if x else lo[:,0]
                iy = hi[:,1] if y else lo[:,1]
                iz = hi[:,2] if z else lo[:,2]
                weight = (f[:,0] if x else 1-f[:,0])*(f[:,1] if y else 1-f[:,1])*(f[:,2] if z else 1-f[:,2])
                out += cube[iz,iy,ix,:channels]*weight[:,None]
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
        donor=getattr(self,"donor_read",None)
        if self.mask & 8 or donor is not None:  # Local inhibition; no spatial diffusion.
            a=np.clip((self.density(x)-base)/span,0,1)
            gamma=self.get("COUPLER_RELEASE_GAMMA",3)
            release=a**gamma/np.maximum(a**gamma+(1-a)**gamma,1e-8)
            inhibition=release@self.get("COUPLER",9).reshape(3,3).T if self.mask & 8 else np.zeros_like(x)
            if donor is not None:
                dp=self.get("DONOR_CURVE",6)
                donor_log=np.log10(np.maximum(donor(10**x),1e-6))
                a=np.clip((self.analytic(dp,donor_log)-dp[0])/(dp[1]*(dp[4]-dp[2])),0,1)
                g=self.get("DONOR_RELEASE_GAMMA")
                released=a**g/np.maximum(a**g+(1-a)**g,1e-8)
                inhibition+=released[:,None]*self.get("DONOR_RELEASE",3)
            u=x-inhibition*self.get("COUPLER_SCALE")
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

    def response(self,rgb,channels=3):
        linear=np.where(rgb<=.04045,rgb/12.92,((rgb+.055)/1.055)**2.4)
        domain=(linear@SRGB_2020.T)@DOMAIN.T
        peak=np.maximum(domain.max(axis=1,keepdims=True),1e-6)
        return np.maximum(sample(self.exposure,domain/peak,channels)*peak*self.get("EXPOSURE_GAIN")/.18,0)

    def prepare_donor(self,profile):
        if not profile.get("donorLayers") or not np.any(self.get("DONOR_RELEASE",3)):
            return None
        # Exposure is homogeneous in radiance. Invert its chromaticity map on the three
        # illuminated sRGB cube faces, then read the fourth record from that same table.
        # This keeps the fourth layer's spectral selectivity, rather than substituting green
        # or fitting a three-coefficient colour matrix. The donor-inclusive neutral warp stays.
        a,b=np.meshgrid(np.linspace(0,1,65),np.linspace(0,1,65))
        faces=[]
        for channel in range(3):
            face=np.ones((a.size,3))
            other=[c for c in range(3) if c!=channel]
            face[:,other[0]]=a.ravel(); face[:,other[1]]=b.ravel()
            faces.append(face)
        linear=np.concatenate(faces)
        encoded=np.where(linear<=.0031308,linear*12.92,1.055*linear**(1/2.4)-.055)
        light=self.response(encoded,4)
        total=np.maximum(light[:,:3].sum(axis=1),1e-12)
        chroma=light[:,:2]/total[:,None]
        _,indices=np.unique(np.round(chroma,10),axis=0,return_index=True)
        points=chroma[indices]; values=light[indices,3]/total[indices]
        inverse=LinearNDInterpolator(points,values)
        boundary=cKDTree(points)
        def read(raw):
            total=np.maximum(raw.sum(axis=1),1e-12)
            chroma=raw[:,:2]/total[:,None]
            ratio=inverse(chroma)
            outside=~np.isfinite(ratio)
            if np.any(outside):
                ratio[outside]=values[boundary.query(chroma[outside])[1]]
            return np.maximum(ratio,0)*total
        self.donor_read=read
        return {"method":"inverse-exposure-chromaticity-interpolation",
                "faceSamples":65,"uniqueChromaticities":len(points),
                "outsideCaptureGamut":"nearest-boundary", "fullEngineParity":False}

    def scan(self,density,mono):
        base=getattr(self,"scan_base",self.get("CURVES",18).reshape(3,6)[:,0])
        span=getattr(self,"scan_span",self.curve_range())
        relative=sample(self.film,(density-base)/span)
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
        encoded=self.get("GRADE_SPACE")!=0
        if encoded:
            relative=np.where(relative<=.0031308,relative*12.92,
                np.where(relative>=1,1+(relative-1)*(1.055/2.4),1.055*np.maximum(relative,0)**(1/2.4)-.055))
        lift=self.get("GRADE_LIFT",3); gain=self.get("GRADE_GAIN",3); gamma=self.get("GRADE_INV_GAMMA",3)
        relative=relative*(gain-lift)+lift
        relative=np.where(gamma==1,relative,np.maximum(relative,0)**gamma)
        if encoded:
            relative=np.where(relative<=.04045,relative/12.92,
                np.where(relative>=1,1+(relative-1)/(1.055/2.4),np.maximum((relative+.055)/1.055,0)**2.4))
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
    config=json.loads((SOURCE/"config-layout.json").read_bytes())
    sources=json.loads((SOURCE/"source-index.json").read_bytes())
    for path,expected in sources["files"].items():
        if digest((SOURCE/path).read_bytes())!=expected:
            raise ValueError(f"Pinned input changed: {path}")
    constants=(SOURCE/"web/src/engine-constants.js").read_text()
    if config!=json.JSONDecoder().raw_decode(constants.split("export const CONFIG = ",1)[1])[0]:
        raise ValueError("Configuration layout no longer matches the pinned equations")
    production=json.loads((PACKS/"production.json").read_bytes())
    original=json.loads((SOURCE/"catalogue-before-migration.json").read_bytes())["stocks"]
    stage=ROOT/"tools/.cache/fotufilm-production"/VERSION
    stage.mkdir(parents=True,exist_ok=True)
    entries=[]; catalogue=[]
    for stock_info in original:
        stock=stock_info["id"]
        upstream=sources["stockMapping"].get(stock)
        if upstream is None:
            continue
        recipe=production[stock]
        pack_path=PACKS/"packs"/f"{upstream}.pack"
        profile_path=SOURCE/"profiles"/f"{upstream}.json"
        previous_path=SOURCE/"existing-effects"/f"{stock}.json"
        if (digest(pack_path.read_bytes())!=recipe["sha256"] or
            digest(profile_path.read_bytes())!=sources["files"][f"profiles/{upstream}.json"] or
            digest(previous_path.read_bytes())!=sources["files"][f"existing-effects/{stock}.json"]):
            raise ValueError("Source snapshot changed; record a new version deliberately")
        pack=Pack(pack_path,config)
        if pack.has_paper not in (0,1):
            raise ValueError("Cineon code-value media cannot be delivered as sRGB")
        previous=json.loads(previous_path.read_bytes())
        donor=pack.prepare_donor(json.loads(profile_path.read_bytes()))
        mono=stock_info["category"]=="MONOCHROME"
        positive=stock_info["positive"]
        folder=stage/stock; folder.mkdir(exist_ok=True)
        response=table(folder,"response",33,[0]*3,[1]*3,pack.response(grid(33,[0]*3,[1]*3)),"srgb_encoded","film_raw_linear")
        developed=pack.develop(grid(65,[-4]*3,[2]*3))
        development=table(folder,"development",65,[-4]*3,[2]*3,developed,"film_log10_exposure","film_cmy_density")
        base=pack.get("CURVES",18).reshape(3,6)[:,0]
        maximum=np.maximum(base+pack.curve_range(),developed.max(axis=0))
        scan=table(folder,"scan",41,[-.2]*3,maximum+.2,pack.scan(grid(41,[-.2]*3,maximum+.2),mono),"film_cmy_density","srgb_encoded")
        # Preserve the previously shipped grain controls, statistical algorithm and calibration.
        # The new colour model's density span must not silently become a new grain maximum.
        grain=dict(previous["grainReference"])
        grain["density_maximum"]=grain.get("density_maximum",[x-.2 for x in previous["tables"]["scan"]["maximum"]])
        metadata={"schema":1,"modelVersion":VERSION,"stockId":stock,"printId":"fotu_fixed_print","positive":positive,
                  "source":{"origin":"fotufilm_public_pack_adapter","repository":sources["repository"],"commit":COMMIT,
                    "builderCommitNotPublished":True,"builderSha256":sources["publicAssets"]["builder.wasm"],
                    "packSha256":digest(pack.bytes),"profileSha256":digest(profile_path.read_bytes()),
                    "author":"Fotufilm / MUAStudio Inc.","license":"CC-BY-SA-4.0"},
                  "storage":"float32_le_rgb_x_fastest","dir":"baked_local_only","spatialEffectsBaked":False,"grainBaked":False,
                  "tables":{"response":response,"development":development,"scan":scan},"grainReference":grain,
                  "halationReference":previous["halationReference"],
                  "settingsSnapshot":{"camera":{"film_format_mm":36.0},"profileRequest":recipe["request"]},
                  "mobileAdaptation":{"monochrome":mono,"fullEngineParity":False,"sampledCurves":True,
                    "sceneMetering":False,"spatialDIR":False,"donorRecord":donor,
                    "grain":"existing-density-statistical-approximation",
                    "effectsSourceModelVersion":previous["modelVersion"],"effectsSourceManifestSha256":digest(previous_path.read_bytes())}}
        write_json(folder/"model.json",metadata)
        entries.append(dict(stockId=stock,printId="fotu_fixed_print",positive=positive,modelVersion=VERSION,manifestSha256=digest((folder/"model.json").read_bytes()),tableBytes=sum(t["bytes"] for t in (response,development,scan))))
        catalogue.append({**stock_info,"printId":"fotu_fixed_print"})
        print(f"Produced {stock}: {entries[-1]['tableBytes']} bytes; receiver={recipe['request']['medium']}",flush=True)
    if len(entries)!=len(sources["stockMapping"]):
        raise ValueError("Migration mapping does not cover the requested stock set")
    write_json(stage/"index.json",dict(schema=1,modelVersion=VERSION,origin="fotufilm_public_pack_adapter",sourceCommit=COMMIT,models=entries))
    write_json(stage/"catalogue.json",dict(stocks=catalogue))
    for original_name,destination in (("licenses/CC-BY-SA-4.0.txt","CC-BY-SA-4.0.txt"),
                                      ("LICENSE","Apache-2.0.txt"),("NOTICE","UPSTREAM-NOTICE.txt"),
                                      ("licenses/FILM-PROFILES.txt","FILM-PROFILES.txt"),
                                      ("THIRD_PARTY_NOTICES.md","UPSTREAM-THIRD-PARTY-NOTICES.md")):
        shutil.copyfile(SOURCE/original_name,stage/destination)
    (stage/"NOTICE.txt").write_text(
        "Fotufilm profiles and derived tables: CC BY-SA 4.0. Copyright MUAStudio Inc.\n"
        "Attribution: Fotufilm, https://fotufilm.com, https://github.com/DhaliwalX/fotufilm-engine\n"
        f"Profiles and equations pinned to {COMMIT}; WASI builder identified separately by SHA-256, its source commit is not published.\n"
        "Changes: three bounded sRGB mobile tables, local coupler chemistry, fixed stock-appropriate physical print or direct scan; normal development, no image metering.\n"
        "400D and Vision3 250D use the pinned profiles directly, including the 5207 corrections; no SpektraFilm dye-basis projection.\n"
        "Existing grain and halation parameters retained from the prior shipped models; their original notices remain in film/mobile-v1 and research/fotufilm.\n"
        "No spatial DIR, upstream crystal grain or full-engine parity. No rendered-photo or device verification.\n",
        encoding="utf-8",newline="\n")
    DEST.mkdir(parents=True,exist_ok=True)
    shutil.copytree(stage,DEST,dirs_exist_ok=True)
    # The five unmatched stocks keep their existing manifest/table bytes and version.
    fallback_root=ROOT/"core/media/src/main/assets/film/mobile-v1"
    fallback_index=json.loads((fallback_root/"index.json").read_bytes())
    fallback_index["models"]=[m for m in fallback_index["models"] if m["stockId"] not in sources["stockMapping"]]
    if len(fallback_index["models"])!=5:
        raise ValueError("Expected exactly five unmatched SpektraFilm stocks")
    write_json(fallback_root/"index.json",fallback_index)
    base_catalogue=ROOT/"core/media/src/main/assets/film/catalogue.json"
    fallback_catalogue=json.loads(base_catalogue.read_bytes())
    fallback_catalogue["stocks"]=[s for s in original if s["id"] not in sources["stockMapping"]]
    write_json(base_catalogue,fallback_catalogue)
    print(f"Published {len(entries)} Fotufilm models; retained five original models",flush=True)


if __name__=="__main__":
    main()
