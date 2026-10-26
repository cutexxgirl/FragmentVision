"""Fixed-scene diagnostic helper, not general map streaming. Never modify the save while baking."""
from pathlib import Path
import json,hashlib,struct,shutil
import argparse
parser=argparse.ArgumentParser(description="Diagnostic Sky Art overlay: replace batches 0 and 27 with 32-block pages; retain the original full coverage elsewhere.")
parser.add_argument('--original',type=Path,required=True)
parser.add_argument('--batches',type=Path,required=True,help='Contains reduced-b0 and reduced-b27 from the same immutable source snapshots')
parser.add_argument('--output',type=Path,required=True)
args=parser.parse_args();old=args.original;out=args.output
m=json.loads((old/'manifest.json').read_text())
reduced_manifests={batch:json.loads((args.batches/f'reduced-b{batch}'/'manifest.json').read_text()) for batch in (0,27)}
if any('surface_map' in p or 'layer' in p for manifest in [m,*reduced_manifests.values()] for p in manifest['pages']):raise SystemExit('Legacy overlay only accepts single-layer pages. Use build_sky_art_frame.py for layered/material-map packages.')
out.mkdir(parents=True,exist_ok=False)
palette=json.loads((old/'palette.json').read_text());lookup={(p['state'],p['biome']):i for i,p in enumerate(palette)}
pages=[dict(p,tile_width=64) for p in m['pages'] if not p['file'].startswith(('b0-','b27-'))]
for p in pages:
 dest=out/p['file']
 if not dest.exists():shutil.copyfile(old/p['file'],dest)
for batch in (0,27):
 source=args.batches/f'reduced-b{batch}';local=json.loads((source/'palette.json').read_text());mapping=[lookup[(p['state'],p['biome'])] for p in local]
 for p in reduced_manifests[batch]['pages']:
  name=f'reduced{batch}-'+p['file'];dest=out/name
  if not dest.exists():
   data=bytearray((source/p['file']).read_bytes())
   if data[:8]==b'FVQUAD01':
    for at in range(24,len(data),8):
     q=struct.unpack_from('>Q',data,at)[0];struct.pack_into('>Q',data,at,(q&~(65535<<44))|(mapping[(q>>44)&65535]<<44))
   elif data[:8]==b'FVMSH003':
    vc,tc,scale,ac=struct.unpack_from('>4i',data,20);vb=(sum(data[48:51])+23)//8;offset=52+vc*vb
    for at in range(offset,offset+4*ac,4):
     a=struct.unpack_from('>I',data,at)[0];struct.pack_into('>I',data,at,(mapping[a>>11]<<11)|(a&2047))
   else:raise ValueError('Unsupported page representation')
   dest.write_bytes(data)
  pages.append(dict(p,file=name,tile_width=32,sha256=hashlib.sha256(dest.read_bytes()).hexdigest()))
shutil.copyfile(old/'palette.json',out/'palette.json');shutil.copyfile(old/'source-inventory.json',out/'source-inventory.json')
m.update(pages=pages,algorithm='mixed exact + bounded triangle-priority WIP',prepared_unique_pages_bytes=sum((out/f).stat().st_size for f in {p['file'] for p in pages}))
(out/'manifest.json').write_text(json.dumps(m,indent=2))
print(len(pages),'level pages',m['prepared_unique_pages_bytes'],'bytes; replaced batches 0 and 27 only')
