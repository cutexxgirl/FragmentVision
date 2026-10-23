#version 460 core
#import <voxy:util/depthutils.glsl>
#import <voxy:lod/hybrid/coverage.glsl>
in vec3 fv_LocalPosition,fv_Tint;
flat in vec2 fv_Material;
uniform sampler2DArray fv_SurfaceTextures;
layout(binding=2) uniform sampler2D depthTex;
out vec4 outColour;
// FRAGMENT_MATERIAL_SOURCE
void main(){
    if(DEPTH_SCALAR_COMPARE(gl_FragCoord.z,texelFetch(depthTex,ivec2(gl_FragCoord.xy),0).r))discard;
    vec3 dx=dFdx(fv_LocalPosition),dy=dFdy(fv_LocalPosition);
    vec3 n=abs(normalize(fv_SurfaceNormal));
    vec2 uv,gx,gy;
    if(n.y>=max(n.x,n.z)){uv=fv_LocalPosition.xz;gx=dx.xz;gy=dy.xz;}
    else if(n.x>n.z){uv=fv_LocalPosition.zy;gx=dx.zy;gy=dy.zy;}
    else {uv=fv_LocalPosition.xy;gx=dx.xy;gy=dy.xy;}
    vec3 albedo=textureGrad(fv_SurfaceTextures,vec3(uv*.25,fv_Material.x),gx*.25,gy*.25).rgb*fv_Tint;
    if(fv_hybridWeight(fr_RelativePosition)<0.5)discard;
    outColour=vec4(fr_lighting(albedo,fv_Material.y),1);
}
