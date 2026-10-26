"""Execute shipping compact vertex and attribute decode, including primitive restart IDs."""
from pathlib import Path
import ctypes,json,numpy as np,moderngl
root=Path(__file__).resolve().parents[2];data=root/'build/lod-proof/gpu-layout/compact';meta=json.loads((data/'metadata.json').read_text())
c=moderngl.create_standalone_context(require=460,backend='egl');fb=c.simple_framebuffer((384,384));fb.use()
vsrc=(root/'src/main/resources/assets/voxy/shaders/lod/mesh/terrain.vert').read_text();head=(root/'src/main/resources/assets/voxy/shaders/lod/mesh/terrain.frag').read_text().split('// FRAGMENT_MATERIAL_SOURCE')[0]
def define(s,compact):return s.replace('#version 460 core','#version 460 core\n'+('#define FV_COMPACT_TERRAIN\n' if compact else ''))
# Transform feedback verifies actual published positions and angular normal error.
p=c.program(vertex_shader=define(vsrc,True),varyings=['v_Position','v_Normal']);p['u_Origin'].value=(0,0,0);p['u_MVP'].write(np.eye(4,dtype='f4').tobytes())
vbo=c.buffer((data/'vertices.bin').read_bytes());out=c.buffer(reserve=meta['vertices']*24);vao=c.vertex_array(p,[(vbo,'3u2','packedPosition')]);vao.transform(out,mode=moderngl.POINTS,vertices=meta['vertices'])
a=np.frombuffer(out.read(),dtype='f4').reshape(-1,6);expected=np.frombuffer((data/'expected.bin').read_bytes(),dtype='f4').reshape(-1,6)
assert np.array_equal(a[:,:3],expected[:,:3]),'Compact position ABI/quantization mismatch'
assert np.all(np.sum(a[:,3:]*expected[:,3:],axis=1)>.999),'Compact normal angular error'
# The fragment header is shipping code, so both 8/16-bit dictionary lookup and
# gl_PrimitiveID across restart boundaries are exercised, not a mirrored decoder.
fragment=head+'\nvoid main(){uint a=fv_attribute();outColour=vec4(float((a>>11u)&255u)/255.,float(a&255u)/255.,gl_FrontFacing?1.:0.,1.);}'
gl=ctypes.CDLL('libGL.so.1');gl.glEnable(0x8D69)
for small in (False,True):
 results=[]
 for compact in (False,True):
  program=c.program(vertex_shader=define(vsrc,compact),fragment_shader=define(fragment,compact));program['u_Origin'].value=(0,0,0)
  matrix=np.array([[.052,0,.008,-.95],[0,.055,.012,-.75],[0,0,-.025,.5],[0,0,0,1]],dtype='f4')
  program['u_MVP'].write(matrix.T.tobytes())
  v=c.buffer((data/('vertices.bin' if compact else 'legacy-vertices.bin')).read_bytes());ix=c.buffer((data/('indices.bin' if compact else 'legacy-indices.bin')).read_bytes())
  words=(data/('small-attributes.bin' if small else 'attributes.bin')).read_bytes() if compact else (bytes(meta['triangles']*4) if small else (data/'legacy-attributes.bin').read_bytes())
  attrs=c.buffer(words);attrs.bind_to_storage_buffer(13)
  if compact:program['u_AttributeBits'].value=8 if small else meta['bits'];program['u_AttributeOffset'].value=1 if small else meta['dictionary']
  va=c.vertex_array(program,[(v,'3u2' if compact else '2u','packedPosition')],index_buffer=ix,index_element_size=4)
  c.enable(moderngl.DEPTH_TEST);c.disable(moderngl.CULL_FACE);fb.clear(0,0,0,0,depth=1)
  va.render(mode=moderngl.TRIANGLE_STRIP if compact else moderngl.TRIANGLES)
  results.append(fb.read(components=4))
 assert any(results[0]) and results[0]==results[1],f'Compact strip/dictionary pixel mismatch, small={small}'
print('Shipping compact terrain: positions exact, oct7 normals within bound, 8/16-bit attributes and restart-strip images bit-identical;',c.info['GL_RENDERER'])
