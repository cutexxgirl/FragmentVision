"""Exercise shipping column lookup and shaped snow/water vertices through OpenGL."""
from pathlib import Path
import numpy as np,moderngl,struct
root=Path(__file__).resolve().parents[2];data=root/'build/lod-proof/gpu-layout/material-map'
c=moderngl.create_standalone_context(require=460,backend='egl');fb=c.simple_framebuffer((36,36));fb.use()
head=(root/'src/main/resources/assets/voxy/shaders/lod/mesh/terrain.frag').read_text().split('// FRAGMENT_MATERIAL_SOURCE')[0]
vertex='''#version 460 core
uniform vec3 u_Origin;
out vec3 v_Position,v_Normal;
void main(){vec2 p=vec2((gl_VertexID==1||gl_VertexID==2)?1.:0.,gl_VertexID>=2?1.:0.);v_Position=vec3(p.x*36.-1.,0.,p.y*36.-1.)+u_Origin;v_Normal=vec3(0,1,0);gl_Position=vec4(p*2.-1.,0,1);}
'''
frag=head+'\nvoid main(){uint id=fv_capMaterial();outColour=vec4(float(id)/255.,0,0,1);}'
p=c.program(vertex_shader=vertex,fragment_shader=frag);p['u_SurfaceMacroWidth'].value=9
if 'u_Origin' in p:p['u_Origin'].value=(45,0,-23)
for suffix in ('','-17','-257'):
 words=c.buffer((data/f'words{suffix}.bin').read_bytes());words.bind_to_storage_buffer(15)
 # A 16-bit identity occupies two colour channels, so IDs >255 are checked too.
 fragment=head+'\nvoid main(){uint id=fv_capMaterial();outColour=vec4(float(id&255u)/255.,float(id>>8u)/255.,0,1);}'
 program=c.program(vertex_shader=vertex,fragment_shader=fragment);program['u_SurfaceMacroWidth'].value=9
 if 'u_Origin' in program:program['u_Origin'].value=(45,0,-23)
 c.vertex_array(program,[]).render(mode=moderngl.TRIANGLE_FAN,vertices=4)
 rgba=np.frombuffer(fb.read(components=4),dtype='u1').reshape(36,36,4);ids=rgba[:,:,0].astype('u4')+(rgba[:,:,1].astype('u4')<<8)
 assert np.array_equal(ids,np.frombuffer((data/f'expected{suffix}.bin').read_bytes(),dtype='<u4').reshape(36,36)),('Shipping point cap lookup/halo/macro decode',suffix)
 words.release()
# All six faces and all eight snow thicknesses. Expected Y values use the actual
# native block interval; partial side starts at its lower neighbouring layer.
vsrc=(root/'src/main/resources/assets/voxy/shaders/lod/mesh/terrain.vert').read_text().replace('#version 460 core','#version 460 core\n#define FV_PACKED_QUADS')
p=c.program(vertex_shader=vsrc,varyings=['v_Position']);p['u_MVP'].write(np.eye(4,dtype='f4').tobytes());p['u_Origin'].value=(0,0,0)
mat=np.zeros((16,6,8),dtype='f4')
for h in range(1,9):mat[h,:,7]=6+h/8.
mat[9,:,7]=5 # water
m=c.buffer(mat.tobytes());m.bind_to_storage_buffer(14)
for h in range(1,11):
 for face in range(6):
  cuts=range(h) if h<=8 and face not in (2,3) else [0]
  for cut in cuts:
   q=3|(4<<6)|(5<<15)|(face<<33)|(h<<44)
   if h<=8 or cut:q|=(1<<63)|(cut<<60)
   qbuf=c.buffer(struct.pack('<Q',q));qbuf.bind_to_storage_buffer(13);vao=c.vertex_array(p,[]);out=c.buffer(reserve=48)
   vao.transform(out,mode=moderngl.POINTS,vertices=4,instances=1)
   positions=np.frombuffer(out.read(),dtype='f4').reshape(4,3)
   low=4+cut/8.;high=4+(h/8. if h<=8 else 8/9. if h==9 else 1)
   expected=[low,low,high,high] if face not in (2,3) else [4 if face==2 else high]*4
   assert np.allclose(positions[:,1],expected,atol=1e-6),(h,face,cut,positions,expected)
   qbuf.release();vao.release();out.release()
print('Shipping material map: source IDs/halos exact; all native snow heights/side cuts, water 8/9 and legacy cube vertices correct;',c.info['GL_RENDERER'])
