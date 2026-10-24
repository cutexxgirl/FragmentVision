"""Diagnostic geometry-only raster test; identical simple shader/expanded streams, NOT shipping shaders or game FPS."""
import json,statistics,os,time
from pathlib import Path
os.environ.setdefault('LIBGL_ALWAYS_SOFTWARE','1')
import moderngl
from PIL import Image,ImageDraw
ctx=moderngl.create_standalone_context(require=330,backend='egl')
fbo=ctx.simple_framebuffer((640,360));fbo.use();ctx.enable(moderngl.DEPTH_TEST)
p=ctx.program(vertex_shader='''#version 330
in vec3 pos; out float height;
void main(){vec3 v=pos-vec3(64,48,64);gl_Position=vec4((v.x-v.z)/100.,(v.y*.85-(v.x+v.z)*.35)/70.,-(v.x+v.z+v.y)*.002,1);height=pos.y;}
''',fragment_shader='''#version 330
in float height;out vec4 colour;void main(){colour=vec4(.18+height*.003,.3+height*.005,.18,1);}
''')
report={'renderer':ctx.info['GL_RENDERER'],'scope':'640x360, simple identical shader, expanded XYZ triangles, no textures/lighting/grass/occlusion/streaming; 16 clear+draw submissions then finish, wall time divided by 16; software raster microbenchmark, NOT full renderer comparison or hardware FPS','samples':[]}
sheet=Image.new('RGB',(640*4,390*3));draw=ImageDraw.Draw(sheet)
for fixture,name in enumerate(['flat','hills','rugged']):
 resources=[]
 for variant in ['voxel','hybrid-0','hybrid-1','hybrid-2']:
  data=Path(f'/workspace/scratch/mesh-bench/{fixture}-{variant}.bin').read_bytes();b=ctx.buffer(data);vao=ctx.simple_vertex_array(p,b,'pos');resources.append((variant,b,vao,len(data)//36))
 timings={v:[] for v,_,_,_ in resources}
 for iteration in range(36):
  # Alternate forward/reverse order to reduce order bias. Clear outside timed draw.
  for variant,b,vao,count in resources[::1 if iteration%2==0 else -1]:
   fbo.clear(.45,.66,.85,depth=1);ctx.finish()
   start=time.perf_counter_ns()
   for repeat in range(16):
    fbo.clear(.45,.66,.85,depth=1);vao.render(moderngl.TRIANGLES)
   ctx.finish()
   elapsed=(time.perf_counter_ns()-start)/1e6/16
   if iteration>=6:timings[variant].append(elapsed)
 for column,(variant,b,vao,count) in enumerate(resources):
  fbo.clear(.45,.66,.85,depth=1);vao.render(moderngl.TRIANGLES);ctx.finish()
  im=Image.frombytes('RGB',fbo.size,fbo.read(components=3)).transpose(Image.Transpose.FLIP_TOP_BOTTOM);sheet.paste(im,(640*column,390*fixture));draw.text((640*column+8,390*fixture+365),f'{name} {variant}: {count} triangles',fill='white')
  values=sorted(timings[variant]);report['samples'].append({'fixture':name,'variant':variant,'triangles':count,'median_clear_draw_finish_ms':statistics.median(values),'p95_clear_draw_finish_ms':values[int(.95*(len(values)-1))]});vao.release();b.release()
out=Path(__file__).resolve().parents[1]/'docs/hybrid-wip';(out/'geometry-raster-benchmark.json').write_text(json.dumps(report,indent=2)+'\n');sheet.save(out/'geometry-benchmark.png');print(json.dumps(report,indent=2))
