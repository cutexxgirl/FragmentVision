"""Shipping collar VS: fine endpoint, field addressing, handover and exact new native."""
from pathlib import Path
import numpy as np,moderngl
r=Path(__file__).resolve().parents[2];c=moderngl.create_standalone_context(require=460,backend='egl');fb=c.simple_framebuffer((1,1));fb.use()
s=(r/'src/main/resources/assets/voxy/shaders/lod/mesh/terrain.vert').read_text().replace('#version 460 core','#version 460 core\n#define FV_NATIVE_COLLAR')
p=c.program(vertex_shader=s,varyings=['v_Position','v_NativeWeight']);p['u_MVP'].write(np.eye(4,dtype='f4').tobytes());p['u_Origin'].value=(100,0,-50)
fine=np.array([[0,2,0],[1,2,1],[2,3,2],[3,4,3]],dtype='f4');target=fine+np.array([[0,0,0],[.125,-.0625,.0625],[-.0625,.125,0],[0,-.0625,-.125]],dtype='f4')
q=[]
for x,y,z in target:
 lo=round(float((x+1)*256))|(round(float((y+1)*256))<<15);hi=round(float((z+1)*256))|(128<<15)|(255<<23);q.append(lo|(hi<<32))
b=c.buffer(np.array(q,dtype='u8').tobytes());vao=c.vertex_array(p,[(b,'2u','packedPosition')]);out=c.buffer(reserve=4*16)
for name in ['u_NativeCurrent','u_NativePrevious']:p[name].value=(-100+.5,50+.5,.25,.25)
for now,old in [(0,1),(1,0),(.5,1),(.25,0)]:
 current=c.texture((4,4),1,np.full((4,4),now,dtype='f4').tobytes(),dtype='f4');previous=c.texture((4,4),1,np.full((4,4),old,dtype='f4').tobytes(),dtype='f4')
 current.use(11);previous.use(12)
 for t in [0,.5,1]:
  p['u_NativeBlend'].value=t;vao.transform(out,mode=moderngl.POINTS,vertices=4);got=np.frombuffer(out.read(),dtype='f4').reshape(4,4)
  w=min(now,old+(now-old)*t);expected=fine+(target-fine)*w+np.array([100,0,-50]);assert np.allclose(got[:,:3],expected,atol=1e-5),(now,old,t,got,expected);assert np.allclose(got[:,3],w)
 current.release();previous.release()
print('Collar GPU: exact native, target geometry, world field coordinates, growth clamp and temporal release passed;',c.info['GL_RENDERER'])
