"""Real packed vertex/fragment + Fragment Render material, including the two-sided
MDI tail and large baseInstance. No FPS claim: EGL/llvmpipe correctness only.
"""
from pathlib import Path
import ctypes as C, re
import moderngl, numpy as np
root=Path(__file__).resolve().parents[2]
r=root.parent/'fragmentrender-sky/src/main/resources/assets/fragmentrender/shaders/world'
c=moderngl.create_standalone_context(require=460,backend='egl')
shared=(r/'sky/receivers.glsl').read_text().replace('// COMMON',(r/'sky/common.glsl').read_text())
material='#define FR_LOD_MATERIAL\n#define FR_HIGH_LIGHTING\nuniform int fr_LodHigh;\n'+(r/'aplus_colour.glsl').read_text()+shared+(r/'sky/aerial.glsl').read_text()+(r/'lighting.glsl').read_text().replace('vec3 fr_lighting(','vec3 fr_mediumLighting(')+(r/'lighting_high.glsl').read_text()+(r/'lighting_lod_water.glsl').read_text()
material=re.sub(r'(?m)^in (vec3|float) (fr_RelativePosition|fr_SkyLight|fr_BlockLight|fr_BaseLight|fr_CanopyNormal|fv_SurfaceNormal);',r'\1 \2;',material)
shader=root/'src/main/resources/assets/voxy/shaders/lod/mesh'
prefix='#version 460 core\n#define FV_PACKED_QUADS\n#define FR_HYBRID_SURFACE'
vs=(shader/'terrain.vert').read_text().replace('#version 460 core',prefix)
fs=(shader/'terrain.frag').read_text().replace('#version 460 core',prefix).replace('// FRAGMENT_MATERIAL_SOURCE',material)
p=c.program(vertex_shader=vs,fragment_shader=fs)
m=np.eye(4,dtype='f4');m[0,0]=.25;m[1,1]=2;m[0,3]=-1;m[1,3]=-1;m[2,3]=-1
p['u_MVP'].write(m.T.tobytes());p['u_Origin'].value=(0,0,0);p['u_SurfaceMacroWidth'].value=0;p['u_LodEdge'].value=(409,512)
for n,value in {'fr_LodHigh':1,'fr_LightEnabled':1,'fr_ShadowAvailable':0,'fr_Daylight':1.,'fr_LightDirection':(.4,.8,.2),'fr_DirectColor':(1.,1.,1.),'fr_AmbientColor':(.5,.62,.68),'fr_Art':(1.,1.,1.,1.),'fr_SkyEnvironment':10}.items():
 if n in p:p[n].value=value
image=np.full((16,64,4),255,dtype='u1');image[:,16:32,3]=np.indices((16,16)).sum(0)%2*255
atlas=c.texture((64,16),4,image.tobytes());atlas.filter=(moderngl.NEAREST,moderngl.NEAREST);atlas.use(0)
sky=c.texture((1,1),4,bytes([100,160,230,255]));sky.use(10)
table=np.zeros((4101,6,8),dtype='f4')
for i,kind in enumerate([0,2,5,6.125]):
 table[4097+i,:,0]=i/4;table[4097+i,:,2]=(i+1)/4;table[4097+i,:,3]=1;table[4097+i,:,4:7]=1;table[4097+i,:,7]=kind
buffer=c.buffer(table.tobytes());buffer.bind_to_storage_buffer(14);cap=c.buffer(bytes(32));cap.bind_to_storage_buffer(15)
indices=c.buffer(np.array([0,1,2,0,2,3],dtype='u2').tobytes());fb=c.framebuffer([c.texture((128,64),4,dtype='f4')]);fb.use()
gl=C.CDLL('libGL.so.1');gl.glUseProgram.argtypes=[C.c_uint];gl.glBindVertexArray.argtypes=[C.c_uint];gl.glDrawElementsInstancedBaseInstance.argtypes=[C.c_uint,C.c_int,C.c_uint,C.c_void_p,C.c_int,C.c_uint]
def quad(x,material):return x | (5<<33) | (240<<36) | (material<<44)
for base in [0,8192]:
 q=np.zeros(base+1002,dtype='u8');q[base:base+2]=[quad(0,4097),quad(6,4100)];q[base+2:base+502]=quad(2,4098);q[base+502:]=quad(4,4099)
 data=c.buffer(q.tobytes());vao=c.vertex_array(p,[(data,'2u /i','packedQuad')],index_buffer=indices,index_element_size=2)
 fb.clear(0,0,0,0);gl.glUseProgram(p.glo);gl.glBindVertexArray(vao.glo);gl.glDrawElementsInstancedBaseInstance(4,6,0x1403,None,1002,base)
 direct=fb.read(components=4,dtype='f4')
 commands=np.zeros((10,5),dtype='u4');commands[3:]=[[6,1,0,0,base],[6,1,0,0,base+1],[6,0,0,0,base+2],[6,0,0,0,base+2],[6,0,0,0,base+2],[6,0,0,0,base+2],[6,1000,0,0,base+2]]
 cmd=c.buffer(commands.tobytes());fb.clear(0,0,0,0);vao.render_indirect(cmd,mode=moderngl.TRIANGLES,first=3,count=7)
 indirect=fb.read(components=4,dtype='f4');assert direct==indirect,(base,'MDI/direct material mismatch')
 fb.clear(0,0,0,0);vao.render_indirect(cmd,mode=moderngl.TRIANGLES,first=3,count=6)
 gl.glUseProgram(p.glo);gl.glBindVertexArray(vao.glo);gl.glDrawElementsInstancedBaseInstance(4,6,0x1403,None,1000,base+2)
 assert fb.read(components=4,dtype='f4')==direct,(base,'Split two-sided submission mismatch')
 compact=c.buffer(commands[3:][commands[3:,1]>0].tobytes());fb.clear(0,0,0,0)
 vao.render_indirect(compact,mode=moderngl.TRIANGLES,count=3)
 assert fb.read(components=4,dtype='f4')==direct,(base,'Compact indirect submission mismatch')
 compact.release()
 pixels=np.frombuffer(indirect,dtype='f4').reshape(64,128,4);assert np.isfinite(pixels).all()
 coverage=[int(np.count_nonzero(pixels[:,i*32:(i+1)*32,3])) for i in range(4)]
 assert coverage[0]==1024 and 350<coverage[1]<750 and 850<coverage[2]<1024 and coverage[3]==1024,(base,coverage)
 assert c.error=='GL_NO_ERROR';print('baseInstance',base,'opaque/leaf/water/snow visible pixels',coverage,'direct == MDI')
 vao.release();data.release();cmd.release()
print('Packed material coverage, large instance offset, zero-count commands and two-sided tail: passed;',c.info['GL_RENDERER'])
