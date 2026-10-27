"""Publish audited linked parents into a separate package, preserving the baseline."""
import argparse,hashlib,json,os,shutil,struct
from pathlib import Path
p=argparse.ArgumentParser();p.add_argument('source',type=Path);p.add_argument('bake',type=Path);p.add_argument('output',type=Path);p.add_argument('--bounds',type=int,nargs=4);a=p.parse_args()
if a.source.resolve()==a.output.resolve():p.error('A separate package is required')
if not a.output.exists():shutil.copytree(a.source,a.output,copy_function=os.link)
def sha(f):return hashlib.sha256(f.read_bytes()).hexdigest()
def publish(f):
 d=a.output/f.name
 if d.exists():
  if sha(d)==sha(f):return
  d.unlink()
 os.link(f,d)
m=json.loads((a.source/'manifest.json').read_text());reports={}
for f in a.bake.glob('linked_*.tsv'):
 x,z=map(int,f.stem.split('_')[1:]);
 if a.bounds and not (a.bounds[0]<=x<a.bounds[2] and a.bounds[1]<=z<a.bounds[3]):continue
 rows=[l.split('\t') for l in f.read_text().splitlines()]
 if len(rows)!=3 or not (a.bake/f'linked_{x}_{z}.cap').exists():continue
 reports[x,z]=rows
# Replacing a joined group is atomic across all four members and three levels.
for r in m['pages']:
 if r.get('layer')=='terrain' and 'lod_group' in r:
  x,z,w=r['lod_group'];members={(xx,zz) for xx in range(x,x+w,32) for zz in range(z,z+w,32)}
  if not members<=reports.keys():
   for k in members:reports.pop(k,None)
canonical={}
sources={tuple(r['tile']):dict(r) for r in m['pages'] if r.get('layer')=='terrain' and r['level']==0}
assets=[]
for key,rows in sorted(reports.items()):
 x,z=key;fine=a.bake/f'linked_{x}_{z}.fine';collar=a.bake/f'collar_{x}_{z}.mesh';cap=a.bake/f'linked_{x}_{z}.cap'
 for f in [fine,collar,cap]:publish(f)
 source=sources[key]
 # Exact protected endpoints already match native quads and need no transition VBO.
 if not (int(rows[0][6])==0 and float(rows[0][4])<1e-7):
  assets.append(dict(file=collar.name,sha256=sha(collar),fine_file=fine.name,fine_sha256=sha(fine),source_file=source['file'],source_sha256=source['sha256']))
 for r in m['pages']:
  if r.get('layer')!='terrain' or tuple(r['tile'])!=key:continue
  row=rows[r['level']]
  # Retain a prior smooth parent when it costs less OR has a tighter error bound.
  # A loose bound would otherwise delay its projected-error switch and draw L0 farther away.
  if r['level']>0 and r['representation']=='mesh' and (r['triangles']<int(row[2]) or r['candidate_error']['upper_bound_blocks']<float(row[3])):
   continue
  # A fully protected planar/exact endpoint is the same surface as the original
  # greedy quads. Retain that cheap encoding rather than triple-upload its fine triangulation.
  if int(row[6])==0 and float(row[4])<1e-7:
   level=r['level'];tile=r['tile'];r.clear();r.update(source);r.update(level=level,tile=tile,native_source_file=source['file'],native_source_sha256=source['sha256'])
   continue
  file=a.bake/row[1];file=canonical.setdefault(sha(file),file);publish(file);header=struct.unpack('>6i',file.read_bytes()[8:32]);vc,tc=header[3:5]
  r.pop('mesh_layout',None)
  r.update(file=file.name,sha256=sha(file),representation='mesh',triangles=tc,vertices=vc,bytes=file.stat().st_size,payload_bytes=file.stat().st_size-52,gpu_geometry_bytes=vc*8+tc*10,
    linked_parent=True,native_source_file=source['file'],native_source_sha256=source['sha256'],surface_map=cap.name,surface_map_sha256=sha(cap),surface_map_bytes=cap.stat().st_size,surface_map_gpu_bytes=cap.stat().st_size-32,
    error_limit_blocks=[1.5,2,3][r['level']],candidate_error=dict(within_limit=True,upper_bound_blocks=float(row[3]),sampled_max_blocks=float(row[4]),queries=int(row[5]),reason='bidirectional_linked_parent_bound_plus_snow_thickness'),reason='source_correspondent_smooth_parent')
m['parent_manifest_sha256']=sha(a.source/'manifest.json');m.pop('replacement_manifest_sha256',None)
encoded={r['file']:r['bytes'] for r in m['pages']}
for r in m['pages']:
 if 'surface_map' in r:encoded[r['surface_map']]=r['surface_map_bytes']
m['prepared_unique_pages_bytes']=sum(encoded.values())
m['linked_native']=True;m['algorithm']='linked-progressive-parent-native-transition-v1';m['linked_parent_tiles']=len(reports)
for name,value in [('manifest.json',m),('native-collars.json',assets)]:
 temp=a.output/(name+'.tmp');temp.write_text(json.dumps(value,indent=2));temp.replace(a.output/name)
print(f'Published {len(reports)} linked tiles, three parents each; source package preserved')
