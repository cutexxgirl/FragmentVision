"""Attach audited near collars to a copy of an immutable baked package."""
import argparse,hashlib,json,os,shutil,struct
from pathlib import Path
p=argparse.ArgumentParser();p.add_argument('source',type=Path);p.add_argument('collars',type=Path);p.add_argument('output',type=Path);p.add_argument('--link',action='store_true');a=p.parse_args()
if a.source.resolve()==a.output.resolve():p.error('Use a separate output package to preserve the baseline')
if not a.output.exists():shutil.copytree(a.source,a.output,copy_function=os.link if a.link else shutil.copy2)
manifest=json.loads((a.source/'manifest.json').read_text());source={tuple(row['tile']):row['sha256'] for row in manifest['pages'] if row['level']==0 and row.get('layer')=='terrain'}
rows=[]
for f in sorted(a.collars.glob('collar_*.mesh')):
 data=f.read_bytes()
 if data[:8]!=b'FVMSH003':raise ValueError(f'Unexpected mesh ABI: {f}')
 x,_,z=struct.unpack('>iii',data[8:20]);expected=f'collar_{x}_{z}.mesh'
 if f.name!=expected or (x,z) not in source:raise ValueError(f'Unknown source tile: {f}')
 dest=a.output/f.name
 if not dest.exists():(os.link if a.link else shutil.copy2)(f,dest)
 if hashlib.sha256(dest.read_bytes()).digest()!=hashlib.sha256(data).digest():raise ValueError(f'Existing collar differs: {dest}')
 rows.append({'file':f.name,'sha256':hashlib.sha256(data).hexdigest(),'source_sha256':source[x,z]})
if len(rows)>2048 or sum((a.output/r['file']).stat().st_size for r in rows)>32<<20:raise ValueError('Collar package budget')
tmp=a.output/'native-collars.json.tmp';tmp.write_text(json.dumps(rows,indent=2));tmp.replace(a.output/'native-collars.json');print(f'{len(rows)} native collars attached to {a.output}')
