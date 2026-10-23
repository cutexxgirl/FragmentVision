#version 460 core
layout(location=0) in vec3 position;
layout(location=1) in vec3 normal;
layout(location=2) in vec3 tint;
layout(location=3) in vec2 material;
uniform mat4 fv_SurfaceMVP;
uniform vec3 fv_SurfaceOrigin;
out vec3 fr_RelativePosition,fr_BaseLight,fr_CanopyNormal,fv_SurfaceNormal;
out float fr_SkyLight,fr_BlockLight;
out vec3 fv_LocalPosition,fv_Tint;
flat out vec2 fv_Material;
void main(){
    fr_RelativePosition=position+fv_SurfaceOrigin;
    fv_LocalPosition=position;fv_SurfaceNormal=normal;fv_Tint=tint;fv_Material=material;
    fr_BaseLight=vec3(1);fr_CanopyNormal=vec3(0);fr_SkyLight=1;fr_BlockLight=0;
    gl_Position=fv_SurfaceMVP*vec4(fr_RelativePosition,1);
}
