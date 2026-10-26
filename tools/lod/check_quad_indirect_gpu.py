"""Compare direct and direction-culled indirect draws using the shipping vertex shader.
Run QuadFaceBucketsCheck first. EGL/llvmpipe is a correctness test, not a benchmark.
"""
from pathlib import Path
import numpy as np
import moderngl

root = Path(__file__).resolve().parents[2]
data = root / 'build/lod-proof/gpu-layout'
ctx = moderngl.create_standalone_context(require=460, backend='egl')
shader = (root/'src/main/resources/assets/voxy/shaders/lod/mesh/terrain.vert').read_text()
shader = shader.replace('#version 460 core', '#version 460 core\n#define FV_PACKED_QUADS')
program = ctx.program(vertex_shader=shader, fragment_shader='''#version 460 core
in vec3 v_Normal;
flat in uint v_Attribute;
out vec4 colour;
void main() { colour=vec4((v_Normal*.3+.4)*float(v_Attribute>>11u)*.4,1); }
''')
program['u_Origin'].value = (0, 0, 0)
indices = ctx.buffer(np.array([0,1,2,0,2,3], dtype='u2').tobytes())
vao = ctx.vertex_array(program, [], index_buffer=indices, index_element_size=2)
fb = ctx.simple_framebuffer((256,256))
fb.use()
ctx.enable(moderngl.DEPTH_TEST)
ctx.disable(moderngl.CULL_FACE)
materials=ctx.buffer(reserve=65536*6*32);materials.clear();materials.bind_to_storage_buffer(14)
source = ctx.buffer((data/'bucket-source.bin').read_bytes())
ordered = ctx.buffer((data/'bucket-sorted.bin').read_bytes())
cameras = [(20,15,20),(-15,15,20),(20,-15,20),(20,15,-20),(4,20,0),(4,-20,0)]
for i, camera in enumerate(cameras):
    eye = np.array(camera, dtype=float)
    forward = np.array([4.5,.5,.5])-eye
    forward /= np.linalg.norm(forward)
    up = np.array([0,0,1]) if abs(forward[1])>.95 else np.array([0,1,0])
    side = np.cross(forward,up); side /= np.linalg.norm(side)
    up = np.cross(side,forward)
    view = np.eye(4); view[:3,:3] = [side,up,-forward]
    view[:3,3] = -view[:3,:3]@eye
    projection = np.zeros((4,4)); projection[0,0] = projection[1,1] = 2.4
    projection[2,2] = -101/99; projection[2,3] = -200/99; projection[3,2] = -1
    program['u_MVP'].write(np.asarray((projection@view).T,dtype='f4').tobytes())
    fb.clear(0,0,0,0,depth=1)
    source.bind_to_storage_buffer(13)
    vao.render(mode=moderngl.TRIANGLES, instances=18)
    baseline = fb.read(components=4)
    assert any(baseline), 'Empty reference image'
    fb.clear(0,0,0,0,depth=1)
    ordered.bind_to_storage_buffer(13)
    raw = (data/f'bucket-commands-{i}.bin').read_bytes()
    assert np.frombuffer(raw,dtype='u4').reshape(7,5)[:,1].sum()<18
    commands = ctx.buffer(raw)
    vao.render_indirect(commands, mode=moderngl.TRIANGLES, count=7)
    assert fb.read(components=4)==baseline, f'Indirect/baseInstance/culling mismatch at camera {camera}'
    commands.release()
print('Six views: direct and culled multi-draw images bit-identical, including two-sided bucket;',ctx.info['GL_RENDERER'])
