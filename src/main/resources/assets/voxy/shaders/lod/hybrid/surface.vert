#version 460 core
layout(location=0) in vec3 position;
layout(location=1) in vec3 normal;
layout(location=2) in vec3 tint;
layout(location=3) in vec3 layersA;
layout(location=4) in vec3 layersB;
layout(location=5) in vec3 weightsA;
layout(location=6) in vec3 weightsB;
layout(location=7) in vec2 light;
uniform mat4 fv_SurfaceMVP;
uniform vec3 fv_SurfaceOrigin;
out vec3 fr_RelativePosition,fr_BaseLight,fr_CanopyNormal,fv_SurfaceNormal;
out float fr_SkyLight,fr_BlockLight;
out vec3 fv_LocalPosition,fv_Tint,fv_WeightsA,fv_WeightsB;
flat out vec3 fv_LayersA,fv_LayersB;
void main(){
    fr_RelativePosition=position+fv_SurfaceOrigin;
    fv_LocalPosition=position;fv_SurfaceNormal=normal;fv_Tint=tint;
    fv_LayersA=layersA;fv_LayersB=layersB;fv_WeightsA=weightsA;fv_WeightsB=weightsB;
    fr_BaseLight=vec3(1);fr_CanopyNormal=vec3(0);fr_SkyLight=light.x;fr_BlockLight=light.y;
    gl_Position=fv_SurfaceMVP*vec4(fr_RelativePosition,1);
}
