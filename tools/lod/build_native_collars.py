"""Bake same-topology near terrain from immutable saved snapshots, never live saves."""
import argparse,json,os,subprocess
from pathlib import Path
p=argparse.ArgumentParser();p.add_argument('package',type=Path);p.add_argument('snapshots',type=Path);p.add_argument('output',type=Path);p.add_argument('--bounds',type=int,nargs=4);a=p.parse_args()
r=Path(__file__).resolve().parents[2];a.output.mkdir(parents=True,exist_ok=True)
palette=json.loads((a.package/'palette.json').read_text());tsv=a.output/'palette.tsv';tsv.write_text(''.join(f"{x['state']}\t{x['biome']}\t{i}\n" for i,x in enumerate(palette)))
manifest=json.loads((a.package/'manifest.json').read_text());pages=[x for x in manifest['pages'] if x.get('layer')=='terrain'];bounds=a.bounds or [min(x['tile'][0] for x in pages),min(x['tile'][1] for x in pages),max(x['tile'][0]+x.get('tile_width',32) for x in pages),max(x['tile'][1]+x.get('tile_width',32) for x in pages)]
jdk=Path(os.environ['JAVA_HOME'])/'bin' if 'JAVA_HOME' in os.environ else None
classes=a.output/'classes';classes.mkdir(exist_ok=True);compiled=r/'build/classes/java/main'
subprocess.run([str(jdk/'javac') if jdk else 'javac','-cp',str(compiled),'-d',str(classes),str(r/'tools/lod/BakeNativeCollarsMain.java')],check=True)
subprocess.run([str(jdk/'java') if jdk else 'java','-Xmx768m','-cp',os.pathsep.join([str(classes),str(compiled)]),'BakeNativeCollarsMain',str(a.snapshots),str(a.output),*[str(x) for x in bounds],str(tsv)],check=True)
