"""Run the actual early-cull GLSL against a brute-force cell oracle (software GL is supported)."""
import json,pathlib,re
import moderngl,numpy as np
root=pathlib.Path(__file__).resolve().parents[2]
shaders=root/'src/main/resources/assets/voxy/shaders/lod/hybrid'
source=(shaders/'early_cull.glsl').read_text().replace('#import <voxy:lod/hybrid/coverage.glsl>',(shaders/'coverage.glsl').read_text())
ctx=moderngl.create_standalone_context(require=460,backend='egl')
shader=ctx.compute_shader('#version 460\n'+source+'''
layout(local_size_x=64) in;
layout(std430,binding=0) readonly buffer Input { vec4 rectangles[]; };
layout(std430,binding=1) writeonly buffer Output { uint results[]; };
void main(){uint i=gl_GlobalInvocationID.x;if(i>=rectangles.length())return;
vec4 r=rectangles[i];results[i]=uint(fv_hybridFullyCovered(vec3(r.x,0,r.y),vec3(r.z,0,r.w)));}
''')
rng=np.random.default_rng(21234);n=43;total=0
# Dense coverage, isolated holes and wholly missing tiles.
for density in [1,.999,.95,0]:
 mask=(rng.random((n,n))<density).astype(np.uint32)
 sums=np.zeros((n+1,n+1),np.uint32);sums[1:,1:]=mask.cumsum(0).cumsum(1)
 tex=ctx.texture((n+1,n+1),1,sums.tobytes(),dtype='u4');tex.use(13)
 rectangles=rng.uniform(-50,50,(4096,4)).astype(np.float32)
 # Exact boundaries and rectangles covering interior holes, not merely their corners.
 rectangles[:8]=[[-42,-42,40,40],[-42,-42,-42,-42],[40,40,40,40],[-44,-44,40,40],[-42,-42,44,44],[-1,-1,1,1],[15,15,15,15],[40,40,-42,-42]]
 inp=ctx.buffer(rectangles.tobytes());out=ctx.buffer(reserve=len(rectangles)*4)
 inp.bind_to_storage_buffer(0);out.bind_to_storage_buffer(1)
 shader['fv_HybridCoverageSum']=13;shader['fv_HybridGrid']=(-42,-42,2,n)
 for near in [0,10,30]:
  for enabled,early in [(1,1),(0,1),(1,0)]:
   shader['fv_HybridNear']=near;shader['fv_HybridEnabled']=enabled;shader['fv_HybridEarlyCull']=early
   shader.run(group_x=64);ctx.memory_barrier();actual=np.frombuffer(out.read(),dtype=np.uint32)
   expected=[]
   for r in rectangles:
    lo=np.minimum(r[:2],r[2:]);hi=np.maximum(r[:2],r[2:]);a=np.floor((lo+42)/2).astype(int);b=np.floor((hi+42)/2).astype(int)
    good=bool(enabled and early and max(np.maximum(0,np.maximum(lo,-hi)))>=near and min(a)>=0 and max(b)<n)
    if good:good=bool(mask[a[1]:b[1]+1,a[0]:b[0]+1].all())
    expected.append(good)
   assert np.array_equal(actual,np.array(expected,dtype=np.uint32)),(density,near,enabled,early)
   total+=len(rectangles)
 tex.release();inp.release();out.release()
print(json.dumps({'result':'passed','checks':total,'renderer':ctx.info['GL_RENDERER'],'scope':'actual GLSL whole-quad coverage decisions against exhaustive interior-cell checks'}))
