"""Offline full-frame package for the fixed Sky_Art_Study bounds [0,192]..[1024,1216].
Read a CLOSED save. Two bounded worker processes, deterministic palette/page merging.
This is a capture helper, not general world streaming or a production map baker.
"""
import sys,subprocess,json,time,hashlib,struct,shutil,os,argparse
from pathlib import Path
import export_section_arrays as export_world
parser=argparse.ArgumentParser(description=__doc__)
parser.add_argument('--region',type=Path,required=True)
parser.add_argument('--output',type=Path,required=True)
parser.add_argument('--work',type=Path,required=True)
parser.add_argument('--snapshots',type=Path,help='Reuse immutable b0..b63.fvvol.gz inputs; do not re-export the open save')
parser.add_argument('--exact',action='store_true',help='Clean layers without smooth candidates')
parser.add_argument('--java',default=str(Path(os.environ['JAVA_HOME'])/'bin/java') if 'JAVA_HOME' in os.environ else 'java')
parser.add_argument('--joined',action='store_true',help='Experimental shared 64-block terrain groups, still encoded as 32-block pages')
args=parser.parse_args()
if args.exact and args.joined:parser.error('--exact and --joined are exclusive')
root=Path(__file__).resolve().parents[2];work=args.work;work.mkdir(parents=True,exist_ok=True)
region=args.region;out=args.output;out.mkdir(parents=True,exist_ok=True)
if (out/'manifest.json').exists():raise SystemExit('Refusing to overwrite completed full-frame package')
sources=sorted((root/'src/main/java/me/cortex/voxy/client/core/rendering/lod/bake').glob('*.java'))+[root/'tools/lod/BakeLayersMain.java',root/'tools/lod/BakeProofMain.java',root/'src/main/java/me/cortex/voxy/client/core/util/ScanMesher2D.java']
fingerprint=hashlib.sha256(b''.join(str(p.relative_to(root)).encode()+b'\0'+p.read_bytes() for p in sources)).hexdigest()
config={'baker_source_sha256':fingerprint,'tile':32,'mode':'exact' if args.exact else 'joined' if args.joined else 'smooth','snapshot_root':str((args.snapshots if args.snapshots else work).resolve())}
config_path=work/'bake-config.json'
if config_path.exists() and json.loads(config_path.read_text())!=config:raise SystemExit('Bake source/settings changed: use a fresh work directory')
if not config_path.exists() and any(work.glob('b*/manifest.json')):raise SystemExit('Existing batches lack a source fingerprint: use a fresh work directory')
config_path.write_text(json.dumps(config,indent=2)+'\n')
java=args.java;palette=[];ids={};pages=[];start=time.time()
from concurrent.futures import ThreadPoolExecutor,as_completed
import fcntl

def prepare(index):
 bx=index%8;bz=index//8;x=bx*128;z=192+bz*128
 snapshot=(args.snapshots if args.snapshots else work)/f'b{index}.fvvol.gz';package=work/f'b{index}'
 with (work/f'b{index}.lock').open('w') as lock:
  fcntl.flock(lock,fcntl.LOCK_EX)
  if args.snapshots and not snapshot.exists():raise FileNotFoundError(snapshot)
  if not snapshot.exists():export_world.export(region,snapshot,(x-7,-64,z-7),(142,384,142),[(x-7,-64,z-7,x+135,320,z+135)])
  if not package.exists():
   with (work/f'b{index}.log').open('w') as log:subprocess.run([java,'-XX:ActiveProcessorCount=1','-Xmx512m','-cp',str(root/'build/lod-proof/classes'),'BakeLayersMain',str(snapshot),str(package),'32',*(['exact'] if args.exact else ['joined'] if args.joined else [])],stdout=log,stderr=subprocess.STDOUT,check=True)
 return index

with ThreadPoolExecutor(max_workers=2) as pool:
 for completed,future in enumerate(as_completed([pool.submit(prepare,i) for i in range(64)]),1):
  index=future.result();print(f'{completed}/64 snapshots complete, last batch {index}, {time.time()-start:.0f}s',flush=True)
  (work/'progress.json').write_text(json.dumps({'completed':completed,'total':64,'elapsed':time.time()-start}))
for index in range(64):
 package=work/f'b{index}'
 manifest=json.loads((package/'manifest.json').read_text());local=json.loads((package/'palette.json').read_text());mapping=[]
 for entry in local:
  key=(entry['state'],entry['biome'])
  if key not in ids:ids[key]=len(palette);palette.append(entry)
  mapping.append(ids[key])
 for page in manifest['pages']:
  name=f'b{index}-'+page['file'];dest=out/name
  if not dest.exists():
   data=bytearray((package/page['file']).read_bytes())
   if data[:8] in (b'FVQUAD01',b'FVQUAD02'):
    for at in range(24,len(data),8):
     q=struct.unpack_from('>Q',data,at)[0];q=(q&~(65535<<44))|(mapping[(q>>44)&65535]<<44);struct.pack_into('>Q',data,at,q)
   elif data[:8]==b'FVMSH003':
    vc,tc,scale,ac=struct.unpack_from('>4i',data,20);bits=sum(data[48:51]);vb=(bits+16+7)//8;offset=52+vc*vb
    for at in range(offset,offset+4*ac,4):
     a=struct.unpack_from('>I',data,at)[0];struct.pack_into('>I',data,at,(mapping[a>>11]<<11)|(a&2047))
   else:raise ValueError('Unknown page')
   dest.write_bytes(data)
  p=dict(page,file=name,sha256=hashlib.sha256(dest.read_bytes()).hexdigest())
  if 'surface_map' in page:
   capname=f'b{index}-'+page['surface_map'];capdest=out/capname
   if not capdest.exists():
    cap=bytearray((package/page['surface_map']).read_bytes());macro=struct.unpack_from('>i',cap,20)[0]
    if cap[:8]!=b'FVCAP002':raise ValueError('Unknown material map')
    count=struct.unpack_from('>I',cap,36)[0];base=2+(count+1)//2
    for i in range((count+1)//2):
     at=40+i*4;pair=struct.unpack_from('>I',cap,at)[0];low=mapping[pair&65535];high=mapping[pair>>16] if i*2+1<count else 0
     struct.pack_into('>I',cap,at,low|(high<<16))
    for word in range(base,base+macro*macro):
     code=struct.unpack_from('>I',cap,32+word*4)[0]
     if code&0x80000000:struct.pack_into('>I',cap,32+word*4,0x80000000|mapping[code&65535])
    capdest.write_bytes(cap)
   p.update(surface_map=capname,surface_map_sha256=hashlib.sha256(capdest.read_bytes()).hexdigest())
  pages.append(p)
p=(json.dumps(palette,ensure_ascii=False)+'\n').encode();(out/'palette.json').write_bytes(p)
inventory=[{'batch':i,'sha256':hashlib.sha256(((args.snapshots if args.snapshots else work)/f'b{i}.fvvol.gz').read_bytes()).hexdigest()} for i in range(64)]
encoded=(json.dumps(inventory,sort_keys=True)+'\n').encode();(out/'source-inventory.json').write_bytes(encoded)
manifest.update(baker_source_sha256=fingerprint,pages=pages,palette_sha256=hashlib.sha256(p).hexdigest(),coverage={'bounds':[0,-64,192,1024,320,1216],'scope':'saved Sky_Art_Study, full resolution diagnostic pages, no streaming'},snapshot_sha256=hashlib.sha256(encoded).hexdigest(),source_inventory='source-inventory.json',prepared_unique_pages_bytes=sum((out/name).stat().st_size for name in ({page['file'] for page in pages}|{page['surface_map'] for page in pages if 'surface_map' in page})))
(out/'manifest.json').write_text(json.dumps(manifest,indent=2))
print('COMPLETE',len(pages)//3,'tiles',sum(p['bytes'] for p in pages if p['level']==2),'selected bytes',flush=True)
