"""Audit lossless palette remap, source columns, geometry gates and budgets before game load.
This reports prepared geometry, not GPU frame time or total renderer memory.
"""
import argparse,collections,hashlib,json,struct
from pathlib import Path

def cap_ids(data):
 if data[:8]!=b'FVCAP002':raise ValueError('Cap ABI')
 x,z,w,m,n=struct.unpack_from('>5i',data,8);cover=struct.unpack_from('>f',data,28)[0]
 assert len(data)==32+4*n
 words=struct.unpack_from(f'>{n}I',data,32);bits,count=words[:2];base=2+(count+1)//2
 assert bits in (4,8,16) and 0<count<=1296 and m==(w+5)//4
 result=[]
 for zz in range(m*4):
  for xx in range(m*4):
   code=words[base+(zz//4)*m+xx//4]
   if code&0x80000000:material=code&65535
   else:
    k=zz%4*4+xx%4;per=32//bits;index=(words[code+k//per]>>((k%per)*bits))&((1<<bits)-1)
    assert index<count;material=(words[2+index//2]>>((index%2)*16))&65535
   result.append(material)
 return (x,z,w,m,cover),result

def audit(root,work=None):
 manifest=json.loads((root/'manifest.json').read_text());palette=json.loads((root/'palette.json').read_text())
 assert manifest['format']=='fragment-lod-proof-3' and manifest['unknown_voxels']==0
 assert hashlib.sha256((root/'palette.json').read_bytes()).hexdigest()==manifest['palette_sha256']
 pages=manifest['pages'];levels=collections.defaultdict(set);assets=set();local_cache={};cap_cache={};geometry={};stats=collections.defaultdict(lambda:collections.Counter())
 small=set(('short_grass tall_grass fern large_fern dandelion poppy blue_orchid allium azure_bluet red_tulip orange_tulip white_tulip pink_tulip oxeye_daisy cornflower lily_of_the_valley wither_rose sunflower lilac rose_bush peony seagrass tall_seagrass kelp kelp_plant').split())
 for page in pages:
  key=(*page['tile'],page['layer']);level=page['level'];assert level not in levels[key];levels[key].add(level)
  name=page['file'];assets.add(name)
  if name not in geometry:
   data=(root/name).read_bytes();assert len(data)==page['bytes'] and hashlib.sha256(data).hexdigest()==page['sha256']
   assert list(struct.unpack_from('>3i',data,8)[::2])==page['tile']
   if data[:8] in (b'FVQUAD01',b'FVQUAD02'):
    count=struct.unpack_from('>i',data,20)[0];assert len(data)==24+count*8;materials={(q>>44)&65535 for q, in struct.iter_unpack('>Q',data[24:])};assert count*2==page['triangles']
    gpu=count*8+152
   elif data[:8]==b'FVMSH003':
    vc,tc,scale,ac=struct.unpack_from('>4i',data,20);vb=(sum(data[48:51])+16+7)//8;at=52+vc*vb
    attrs=struct.unpack_from(f'>{ac}I',data,at);materials={a>>11 for a in attrs};assert tc==page['triangles']
    gpu=page['gpu_geometry_bytes'] # Strip ABI allocation is independently exercised on the GPU.
   else:raise ValueError('Geometry ABI')
   assert all(0<i<len(palette) for i in materials)
   assert not any(palette[i]['state'].split('[')[0].removeprefix('minecraft:') in small for i in materials)
   geometry[name]={'materials':materials,'gpu':gpu,'triangles':page['triangles']}
  assert geometry[name]['gpu']==page['gpu_geometry_bytes']
  if 'surface_map' in page:
   name=page['surface_map'];assets.add(name)
   if name not in cap_cache:
    data=(root/name).read_bytes();assert len(data)==page['surface_map_bytes'] and hashlib.sha256(data).hexdigest()==page['surface_map_sha256']
    meta,ids=cap_ids(data);assert len(data)-32==page['surface_map_gpu_bytes'] and max(ids)<len(palette);assert list(meta[:2])==page['tile']
    if work:
     batch,localname=name.split('-',1);localmeta,localids=cap_ids((work/batch/localname).read_bytes());assert localmeta==meta
     if batch not in local_cache:local_cache[batch]=json.loads((work/batch/'palette.json').read_text())
     assert [palette[i] for i in ids]==[local_cache[batch][i] for i in localids],f'Source column remap changed in {name}'
    cap_cache[name]=len(data)-32
  if page['representation']=='mesh':
   error=page['candidate_error'];assert level>0 and error['within_limit'] and error['upper_bound_blocks']<=page['error_limit_blocks']
   assert page['triangles']<page['packed_triangles']
   assert page['gpu_geometry_bytes']+page.get('surface_map_gpu_bytes',0)<page['packed_gpu_bytes']
  stat=stats[(page['layer'],level,page['representation'])];stat.update(pages=1,triangles=page['triangles'],gpu=page['gpu_geometry_bytes'],cap_gpu=page.get('surface_map_gpu_bytes',0),exact_triangles=page['packed_triangles'],exact_gpu=page['packed_gpu_bytes'])
 assert all(value=={0,1,2} for value in levels.values())
 assert sum((root/name).stat().st_size for name in assets)==manifest['prepared_unique_pages_bytes']
 unique_gpu=sum(p['gpu'] for p in geometry.values())+sum(cap_cache.values())+len(palette)*6*32
 assert len(geometry)<=8192 and manifest['prepared_unique_pages_bytes']<=128*1024*1024 and unique_gpu<=192*1024*1024
 report={'scope':'prepared geometry; not frame-time or total renderer GPU memory','format':manifest['format'],'algorithm':manifest['algorithm'],'input_policy':manifest['input_policy'],'tiles_and_layers':len(levels),'unique_geometry_pages':len(geometry),'material_maps':len(cap_cache),'resident_geometry_material_command_bytes':unique_gpu,'encoded_unique_bytes':manifest['prepared_unique_pages_bytes'],'statistics':[dict(layer=key[0],level=key[1],representation=key[2],**value) for key,value in sorted(stats.items())]}
 return report
if __name__=='__main__':
 p=argparse.ArgumentParser(description=__doc__);p.add_argument('package',type=Path);p.add_argument('--work',type=Path);p.add_argument('--output',type=Path);args=p.parse_args();report=audit(args.package,args.work);text=json.dumps(report,indent=2)
 if args.output:args.output.write_text(text+'\n')
 print(text)
