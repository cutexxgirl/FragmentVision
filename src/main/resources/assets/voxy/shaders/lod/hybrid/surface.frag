#version 460 core
#import <voxy:util/depthutils.glsl>
#import <voxy:lod/hybrid/coverage.glsl>
in vec3 fv_LocalPosition,fv_Tint,fv_WeightsA,fv_WeightsB;
flat in vec3 fv_LayersA,fv_LayersB;
uniform sampler2DArray fv_SurfaceTextures;
layout(binding=2) uniform sampler2D depthTex;
out vec4 outColour;
// FRAGMENT_MATERIAL_SOURCE
void main(){
    if(DEPTH_SCALAR_COMPARE(gl_FragCoord.z,texelFetch(depthTex,ivec2(gl_FragCoord.xy),0).r))discard;
    if(fv_hybridWeight(fr_RelativePosition)<0.5)discard;
    vec3 dx=dFdx(fv_LocalPosition),dy=dFdy(fv_LocalPosition),normal=normalize(fv_SurfaceNormal);
    vec3 n=abs(normal);
    vec2 uv,gx,gy;
    if(n.y>=max(n.x,n.z)){uv=fv_LocalPosition.xz;gx=dx.xz;gy=dy.xz;}
    else if(n.x>n.z){uv=fv_LocalPosition.zy;gx=dx.zy;gy=dy.zy;}
    else {uv=fv_LocalPosition.xy;gx=dx.xy;gy=dy.xy;}
    float weights[6]=float[6](fv_WeightsA.x,fv_WeightsA.y,fv_WeightsA.z,fv_WeightsB.x,fv_WeightsB.y,fv_WeightsB.z);
    float layers[6]=float[6](fv_LayersA.x,fv_LayersA.y,fv_LayersA.z,fv_LayersB.x,fv_LayersB.y,fv_LayersB.z);
    vec3 albedo=vec3(0);float grass=0;
    for(int i=0;i<6;i++)if(weights[i]>0.001){
        vec4 tex=textureGrad(fv_SurfaceTextures,vec3(uv,layers[i]),gx,gy);
        albedo+=weights[i]*tex.rgb*mix(vec3(1),fv_Tint,tex.a);
        grass+=weights[i]*tex.a;
    }
    float shade=dot(normal*normal,vec3(.6,1,.8));
    outColour=vec4(fr_lighting(albedo*shade,grass>.5?3.0:0.0),1);
}
