#version 460 core
#import <voxy:util/depthutils.glsl>
#import <voxy:lod/hybrid/coverage.glsl>
in vec3 fv_OriginalPosition,fv_CoveragePosition,fv_Tint;
flat in vec3 fv_FaceNormal;
flat in float fv_Material;
uniform sampler2DArray fv_SurfaceTextures;
layout(binding=2) uniform sampler2D depthTex;
out vec4 outColour;
// FRAGMENT_MATERIAL_SOURCE
void main(){
    if(DEPTH_SCALAR_COMPARE(gl_FragCoord.z,texelFetch(depthTex,ivec2(gl_FragCoord.xy),0).r))discard;
    if(fv_hybridWeight(fv_CoveragePosition)<0.5)discard;
    // Preserve each source face's material and UV projection throughout morphing.
    // No colour averaging between snow, soil and rock; mipmaps only filter one material.
    vec2 uv=abs(fv_FaceNormal.y)>.5?fv_OriginalPosition.xz:
            (abs(fv_FaceNormal.x)>.5?fv_OriginalPosition.zy:fv_OriginalPosition.xy);
    vec4 tex=texture(fv_SurfaceTextures,vec3(uv,fv_Material));
    vec3 normal=normalize(fv_SurfaceNormal);
    float shade=dot(normal*normal,vec3(.6,1,.8));
    vec3 albedo=tex.rgb*mix(vec3(1),fv_Tint,tex.a)*shade;
    outColour=vec4(fr_lighting(albedo,tex.a>.5?3.0:0.0),1);
}
