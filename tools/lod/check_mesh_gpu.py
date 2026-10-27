"""Run MeshGpuLayoutCheck first. Verify the shipping vertex shader via transform feedback."""
from pathlib import Path
import moderngl,numpy as np
root=Path(__file__).resolve().parents[2];data=root/'build/lod-proof/gpu-layout'
c=moderngl.create_standalone_context(require=460,backend='egl');f=c.simple_framebuffer((1,1));f.use()
p=c.program(vertex_shader=(root/'src/main/resources/assets/voxy/shaders/lod/mesh/terrain.vert').read_text(),varyings=['v_Position','v_Normal'])
p['u_MVP'].write(np.eye(4,dtype='f4').tobytes());p['u_Origin'].value=(0,0,0)
b=c.buffer((data/'vertices.bin').read_bytes());o=c.buffer(reserve=6000*24);v=c.vertex_array(p,[(b,'2u','packedPosition')]);v.transform(o,mode=moderngl.POINTS,vertices=6000)
a=np.frombuffer(o.read(),dtype='f4').reshape(-1,6);e=np.frombuffer((data/'expected.bin').read_bytes(),dtype='f4').reshape(-1,6)
assert np.array_equal(a[:,:3],e[:,:3]),'GPU coordinate precision regression'
assert np.all(np.sum(a[:,3:]*e[:,3:],axis=1)>.999),'GPU oct normal regression'
print('Shipping mesh shader: 6000 positions bit exact, all six normal axes correct;',c.info['GL_RENDERER'])
src=(root/'src/main/resources/assets/voxy/shaders/lod/mesh/terrain.vert').read_text().replace('#version 460 core','#version 460 core\n#define FV_PACKED_QUADS')
p=c.program(vertex_shader=src,varyings=['v_Position','v_Normal','v_Attribute']);p['u_MVP'].write(np.eye(4,dtype='f4').tobytes());p['u_Origin'].value=(0,0,0)
materials=c.buffer(reserve=65536*6*32);materials.clear();materials.bind_to_storage_buffer(14)
b=c.buffer((data/'quads.bin').read_bytes());b.bind_to_storage_buffer(13);o=c.buffer(reserve=600*4*28);v=c.vertex_array(p,[(b,'2u /i','packedQuad')]);v.transform(o,mode=moderngl.POINTS,vertices=4,instances=600)
assert o.read()==(data/'quad-expected.bin').read_bytes(),'Shipping packed quad position/normal/attribute mismatch'
print('Shipping packed quad shader: all six faces, 2400 corners and material/light attributes bit-exact')
