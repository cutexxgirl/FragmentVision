"""The direct mesh fragment path must apply air exactly once and obey the LOD horizon.
Material/light stubs isolate this composition contract; check_hybrid_shaders separately
links the complete shipping Render/sky implementations.
"""
from pathlib import Path
import moderngl,numpy as np
root=Path(__file__).resolve().parents[2]
ctx=moderngl.create_standalone_context(require=460,backend='egl');texture=ctx.texture((8,8),4,dtype='f4');fb=ctx.framebuffer([texture]);fb.use()
fragment=(root/'src/main/resources/assets/voxy/shaders/lod/mesh/terrain.frag').read_text().replace('#version 460 core','#version 460 core\n#define FV_PACKED_QUADS')
stubs='''vec3 fr_RelativePosition,fv_SurfaceNormal,fr_BaseLight,fr_CanopyNormal;
float fr_SkyLight,fr_BlockLight;
vec3 fr_lighting(vec3 c,float m){return c;}
vec3 fr_lodWaterColor(){return vec3(1);}
vec3 fr_aerialFog(vec3 c,vec3 p){return c*.5;}
vec3 fr_aerialLodEdge(vec3 c,vec3 p,float s,float e){return c+vec3(.125,0,0);}
'''
fragment=fragment.replace('// FRAGMENT_MATERIAL_SOURCE',stubs)
vertex='''#version 460 core
uniform vec3 u_TestPosition;
out vec3 v_Position,v_Normal;flat out uint v_Attribute;
void main(){vec2 p=vec2((gl_VertexID==1||gl_VertexID==2)?1.:0.,gl_VertexID>=2?1.:0.);v_Position=u_TestPosition;v_Normal=vec3(0,1,0);v_Attribute=(1u<<11u)|(3u<<8u)|240u;gl_Position=vec4(p*2.-1.,0,1);}
'''
p=ctx.program(vertex_shader=vertex,fragment_shader=fragment);p['u_SurfaceMacroWidth'].value=0;p['u_LodEdge'].value=(409.6,512.)
if 'u_CameraPhase' in p:p['u_CameraPhase'].value=(0,0,0)
atlas=ctx.texture((2,2),4,bytes([255]*16));atlas.use(0);atlas.filter=(moderngl.NEAREST,moderngl.NEAREST)
cap=ctx.buffer(reserve=32);cap.clear();cap.bind_to_storage_buffer(15)
vao=ctx.vertex_array(p,[])
for kind in (0.,5.,6.125):
 materials=np.zeros((2,6,8),dtype='f4');materials[:,:,2:4]=1;materials[:,:,4:7]=1;materials[1,:,7]=kind
 table=ctx.buffer(materials.tobytes());table.bind_to_storage_buffer(14)
 p['u_TestPosition'].value=(10,20,10);fb.clear(0,0,0,0);vao.render(mode=moderngl.TRIANGLE_FAN,vertices=4)
 pixels=np.frombuffer(fb.read(components=4,dtype='f4'),dtype='f4').reshape(-1,4)
 assert np.allclose(pixels,[.625,.5,.5,1],atol=1e-6),('Air omitted/doubled or snow treated as water',kind,pixels[0])
 p['u_TestPosition'].value=(513,0,0);fb.clear(.04,.02,.01,0);vao.render(mode=moderngl.TRIANGLE_FAN,vertices=4)
 pixels=np.frombuffer(fb.read(components=4,dtype='f4'),dtype='f4').reshape(-1,4)
 assert np.allclose(pixels,[.04,.02,.01,0],atol=1e-6),'Distant geometry leaked beyond configured horizon'
 table.release()
print('Shipping direct mesh fragment: ordinary/snow/water apply shared air and edge once; horizon clips outside the configured range;',ctx.info['GL_RENDERER'])
