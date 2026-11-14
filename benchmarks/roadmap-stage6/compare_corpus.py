#!/usr/bin/env python3
"""Compare actual JavaFX captures to an installed Inkscape renderer, not a browser oracle."""
import argparse,csv,hashlib,json,os,pathlib,subprocess
import numpy as np
from PIL import Image
p=argparse.ArgumentParser();p.add_argument('--project',type=pathlib.Path,required=True);p.add_argument('--output',type=pathlib.Path,required=True);a=p.parse_args()
a.output.mkdir(parents=True,exist_ok=True)
env=os.environ.copy();env['INKSCAPE_PROFILE_DIR']=str((a.output/'inkscape-profile').resolve())
version=subprocess.run(['inkscape','--version'],env=env,capture_output=True,text=True,check=True)
(a.output/'inkscape-version.txt').write_text(version.stdout+version.stderr)
rows=[];provenance=[]
for name in ['activity','airplay','atom','cpu']:
    source=a.project/'src/test/resources/svg/real-corpus'/f'{name}.svg'
    actual=a.project/'target'/f'stage6-corpus-{name}.png'
    reference=a.output/f'{name}-inkscape.png'
    command=['inkscape',str(source.resolve()),'--export-width=96','--export-height=96','--export-background-opacity=0',f'--export-filename={reference.resolve()}']
    r=subprocess.run(command,env=env,capture_output=True,text=True,check=True)
    (a.output/f'{name}-inkscape.log').write_text(r.stdout+r.stderr)
    x=np.asarray(Image.open(actual).convert('RGBA'),dtype=np.float64)/255
    y=np.asarray(Image.open(reference).convert('RGBA'),dtype=np.float64)/255
    assert x.shape==y.shape==(96,96,4),(name,x.shape,y.shape)
    xp=x.copy();yp=y.copy();xp[:,:,:3]*=xp[:,:,3:4];yp[:,:,:3]*=yp[:,:,3:4]
    delta=np.abs(xp-yp);alpha=np.abs(x[:,:,3]-y[:,:,3]);ca=x[:,:,3]>=.5;cb=y[:,:,3]>=.5
    union=np.count_nonzero(ca|cb);intersection=np.count_nonzero(ca&cb)
    interior=(x[:,:,3]>=254/255)&(y[:,:,3]>=254/255)
    # Erode one pixel to avoid measuring antialiased edges as interior.
    for dy,dx in [(0,1),(0,-1),(1,0),(-1,0)]:interior &= np.roll(np.roll((x[:,:,3]>=254/255)&(y[:,:,3]>=254/255),dy,0),dx,1)
    row={'name':name,'pixels':96*96,'premultiplied_rgba_mae':float(delta.mean()),'alpha_mae':float(alpha.mean()),'max_component_delta':float(delta.max()),'pixels_delta_gt_2_255':int(np.count_nonzero(delta.max(axis=2)>2/255)),'coverage_iou_half_alpha':intersection/union if union else 1,'interior_pixels':int(interior.sum()),'interior_rgb_max_delta':float(delta[:,:,:3][interior].max()) if interior.any() else None}
    row['review_required']=bool(row['alpha_mae']>.05 or row['coverage_iou_half_alpha']<.9)
    rows.append(row)
    provenance.append({'name':name,'source_sha256':hashlib.sha256(source.read_bytes()).hexdigest(),'actual_sha256':hashlib.sha256(actual.read_bytes()).hexdigest(),'reference_sha256':hashlib.sha256(reference.read_bytes()).hexdigest(),'command':command})
with (a.output/'corpus-comparison.csv').open('w') as f:
    w=csv.DictWriter(f,fieldnames=rows[0]);w.writeheader();w.writerows(rows)
(a.output/'corpus-comparison.json').write_text(json.dumps({'renderer':'Inkscape','meaning':'Independent implementation comparison; antialias differences expected; not browser or GPU parity.','rows':rows,'provenance':provenance},indent=2))
print(json.dumps(rows,indent=2))
