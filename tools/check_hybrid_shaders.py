"""Compile the actual WIP and ordinary Vision shaders against the actual Render material files."""
from pathlib import Path
import re,json
import moderngl
root=Path(__file__).resolve().parents[1]
v=root/'src/main/resources/assets/voxy/shaders'
r=root.parent/'fragmentrender-sky/src/main/resources/assets/fragmentrender/shaders/world'
def parse(path):
    s=(v/path).read_text()
    s=re.sub(r'^#version.*$','',s,flags=re.M)
    return re.sub(r'#import <voxy:([^>]+)>',lambda m:parse(m[1]),s)
shared=(r/'sky/receivers.glsl').read_text().replace('// COMMON',(r/'sky/common.glsl').read_text())
material='#define FR_LOD_MATERIAL\n#define FR_HIGH_LIGHTING\nuniform int fr_LodHigh;\n'+(r/'aplus_colour.glsl').read_text()+shared+(r/'sky/aerial.glsl').read_text()+(r/'lighting.glsl').read_text().replace('vec3 fr_lighting(','vec3 fr_mediumLighting(')+(r/'lighting_high.glsl').read_text()+(r/'lighting_lod_water.glsl').read_text()
ctx=moderngl.create_standalone_context(require=460,backend='egl')
programs=[]
for enabled in (False,True):
    for translucent in (False,True):
        prefix='#version 460 core\n#define FV_HYBRID_TERRAIN\n'+('#define FR_LOD_MATERIAL\n' if enabled else '')+('#define TRANSLUCENT\n' if translucent else '')+'#define UP_FACE_TINT 1.0\n#define DOWN_FACE_TINT 0.5\n#define X_AXIS_FACE_TINT 0.6\n#define Z_AXIS_FACE_TINT 0.8\n#define NO_SHADE_FACE_TINT 1.0\n'
        p=ctx.program(vertex_shader=prefix+parse('lod/gl46/quads3.vert'),fragment_shader=prefix+parse('lod/gl46/quads.frag').replace('// FRAGMENT_MATERIAL_SOURCE',material if enabled else ''))
        programs.append(f'voxel material={enabled},translucent={translucent}')
for reverse in (False,True):
    prefix='#version 460 core\n#define FR_HYBRID_SURFACE\n'+('#define USE_REVERSE_Z\n' if reverse else '')
    p=ctx.program(vertex_shader=prefix+parse('lod/hybrid/surface.vert'),fragment_shader=prefix+parse('lod/hybrid/surface.frag').replace('// FRAGMENT_MATERIAL_SOURCE',material))
    programs.append(f'surface reverseZ={reverse}')
report={'result':'passed','renderer':ctx.info['GL_RENDERER'],'programs':programs}
out=root/'docs/hybrid-wip';out.mkdir(parents=True,exist_ok=True)
(out/'gpu-shaders.json').write_text(json.dumps(report,indent=2)+'\n')
print(json.dumps(report,indent=2))
