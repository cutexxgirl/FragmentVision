"""The complete baked material -> final composite path applies air exactly once.
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
# Exercise the actual second pass; checking only the material hid double air.
post=(root/'src/main/resources/assets/voxy/shaders/post/blit_texture_depth_cutout.frag').read_text()
post=post.replace('#version 450 core','#version 450 core\n#define EMIT_COLOUR\n#define FRAGMENT_ATMOSPHERE')
post=post.replace('#import <voxy:util/depthutils.glsl>',(root/'src/main/resources/assets/voxy/shaders/util/depthutils.glsl').read_text())
post=post.replace('// FRAGMENT_ATMOSPHERE_SOURCE','uniform vec4 fr_Aerial; uniform float fr_AerialSoftness;\n'+stubs)
post_vertex="""#version 450 core
out vec2 UV;
void main(){vec2 p=vec2((gl_VertexID==1||gl_VertexID==2)?1.:0.,gl_VertexID>=2?1.:0.);UV=p;gl_Position=vec4(p*2.-1.,0,1);}
"""
post_program=ctx.program(vertex_shader=post_vertex,fragment_shader=post)
for name in ('invProjMat','projMat'):post_program[name].write(np.eye(4,dtype='f4').tobytes())
post_program['fr_Aerial'].value=(0,0,0,1);post_program['fr_AerialSoftness'].value=0
if 'fv_LodEdge' in post_program:post_program['fv_LodEdge'].value=(409.6,512.)
depth=ctx.texture((8,8),1,np.full((8,8),.5,dtype='f4').tobytes(),dtype='f4')
post_fb=ctx.framebuffer([ctx.texture((8,8),4,dtype='f4')]);post_vao=ctx.vertex_array(post_program,[])

for kind in (0.,5.,6.125):
 materials=np.zeros((2,6,8),dtype='f4');materials[:,:,2:4]=1;materials[:,:,4:7]=1;materials[1,:,7]=kind
 table=ctx.buffer(materials.tobytes());table.bind_to_storage_buffer(14)
 fb.use();atlas.use(0)
 p['u_TestPosition'].value=(10,20,10);fb.clear(0,0,0,0);vao.render(mode=moderngl.TRIANGLE_FAN,vertices=4)
 post_fb.use();depth.use(0);texture.use(3);post_fb.clear(0,0,0,0);post_vao.render(mode=moderngl.TRIANGLE_FAN,vertices=4)
 pixels=np.frombuffer(post_fb.read(components=4,dtype='f4'),dtype='f4').reshape(-1,4)
 assert np.allclose(pixels,[.625,.5,.5,1],atol=1e-6),('Air omitted/doubled or snow treated as water',kind,pixels[0])
 fb.use();atlas.use(0)
 p['u_TestPosition'].value=(513,0,0);fb.clear(.04,.02,.01,0);vao.render(mode=moderngl.TRIANGLE_FAN,vertices=4)
 pixels=np.frombuffer(fb.read(components=4,dtype='f4'),dtype='f4').reshape(-1,4)
 assert np.allclose(pixels,[.04,.02,.01,0],atol=1e-6),'Distant geometry leaked beyond configured horizon'
 table.release()
print('Shipping baked material + final composite: ordinary/snow/water apply shared air and edge once; horizon clips outside the configured range;',ctx.info['GL_RENDERER'])
