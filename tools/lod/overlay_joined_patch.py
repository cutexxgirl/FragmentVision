"""Replace ONE immutable 128x128 source batch in a full package for a bounded game A/B.
Keep the existing global palette, verify source identity, remap every material-map ID.
"""
from pathlib import Path
import argparse,json,hashlib,struct,shutil
p=argparse.ArgumentParser(description=__doc__);p.add_argument('original',type=Path);p.add_argument('replacement',type=Path);p.add_argument('output',type=Path);p.add_argument('--batch',type=int,required=True);args=p.parse_args()
old=args.original;new=args.replacement;out=args.output
m=json.loads((old/'manifest.json').read_text());r=json.loads((new/'manifest.json').read_text())
if r['algorithm']!='joined-64-terrain-column-material-v1':raise ValueError('Expected joined baker output')
inventory=json.loads((old/'source-inventory.json').read_text());source=next(i for i in inventory if i['batch']==args.batch)
if source['sha256']!=r['snapshot_sha256']:raise ValueError('Replacement has different source voxels')
palette=json.loads((old/'palette.json').read_text());lookup={(e['state'],e['biome']):i for i,e in enumerate(palette)};mapping=[lookup[(e['state'],e['biome'])] for e in json.loads((new/'palette.json').read_text())]
removed=[q for q in m['pages'] if q['file'].startswith(f'b{args.batch}-')]
if {(tuple(q['tile']),q['layer'],q['level']) for q in removed}!={(tuple(q['tile']),q['layer'],q['level']) for q in r['pages']}:raise ValueError('Replacement changes physical coverage/layers')
pages=[q for q in m['pages'] if q not in removed];out.mkdir(parents=True,exist_ok=False)
for q in pages:
 for key in ('file','surface_map'):
  if key in q and not (out/q[key]).exists():shutil.copyfile(old/q[key],out/q[key])
for q in r['pages']:
 q=dict(q)
 for key in ('file','surface_map'):
  if key not in q:continue
  original=q[key];name=f'joined{args.batch}-'+original;target=out/name
  if not target.exists():
   data=bytearray((new/original).read_bytes())
   if key=='surface_map':
    if data[:8]!=b'FVCAP002':raise ValueError('Cap ABI')
    macro=struct.unpack_from('>I',data,20)[0];count=struct.unpack_from('>I',data,36)[0];base=2+(count+1)//2
    for i in range((count+1)//2):
     at=40+i*4;pair=struct.unpack_from('>I',data,at)[0];struct.pack_into('>I',data,at,mapping[pair&65535]|((mapping[pair>>16] if i*2+1<count else 0)<<16))
    for word in range(base,base+macro*macro):
     at=32+word*4;code=struct.unpack_from('>I',data,at)[0]
     if code&0x80000000:struct.pack_into('>I',data,at,0x80000000|mapping[code&65535])
   elif data[:8] in (b'FVQUAD01',b'FVQUAD02'):
    for at in range(24,len(data),8):
     value=struct.unpack_from('>Q',data,at)[0];struct.pack_into('>Q',data,at,(value&~(65535<<44))|(mapping[(value>>44)&65535]<<44))
   elif data[:8]==b'FVMSH003':
    vc,tc,scale,ac=struct.unpack_from('>4i',data,20);at=52+vc*((sum(data[48:51])+23)//8)
    for i in range(ac):
     value=struct.unpack_from('>I',data,at+i*4)[0];struct.pack_into('>I',data,at+i*4,(mapping[value>>11]<<11)|(value&2047))
   else:raise ValueError('Geometry ABI')
   target.write_bytes(data)
  q[key]=name;q['sha256' if key=='file' else 'surface_map_sha256']=hashlib.sha256(target.read_bytes()).hexdigest()
 pages.append(q)
for name in ('palette.json','source-inventory.json'):shutil.copyfile(old/name,out/name)
assets={q['file'] for q in pages}|{q['surface_map'] for q in pages if 'surface_map' in q}
m.update(algorithm='mixed independent32 and joined64 terrain WIP',pages=pages,prepared_unique_pages_bytes=sum((out/name).stat().st_size for name in assets),joined_source_batches=[args.batch],replacement_manifest_sha256=hashlib.sha256((new/'manifest.json').read_bytes()).hexdigest())
(out/'manifest.json').write_text(json.dumps(m,indent=2)+'\n');print('Full coverage retained; replaced immutable batch',args.batch,'with',len(r['pages']),'level pages')
