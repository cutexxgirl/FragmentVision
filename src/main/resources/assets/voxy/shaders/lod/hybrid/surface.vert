#version 460 core
layout(location=0) in uvec4 faceVertex;
struct MorphVertex {vec4 position;vec4 smoothed;vec4 parent4;vec4 parent8;vec4 normal;};
layout(binding=12) uniform samplerBuffer fv_MorphPositions;
uniform mat4 fv_SurfaceMVP;
uniform vec3 fv_SurfaceOrigin;
uniform float fv_HybridNear;
uniform float fv_SurfacePixelScale;
uniform vec3 fv_SurfaceBands;
uniform int fv_SurfaceLevel;
out vec3 fr_RelativePosition,fr_BaseLight,fr_CanopyNormal,fv_SurfaceNormal;
out float fr_SkyLight,fr_BlockLight;
out vec3 fv_OriginalPosition,fv_CoveragePosition,fv_Tint;
flat out vec3 fv_FaceNormal;
flat out float fv_Material;
void main(){
    // HybridGpuLayout packs normal.xyz into the three morph targets' unused w slots.
    int address=int(faceVertex.x)*4;
    vec4 roundedPoint=texelFetch(fv_MorphPositions,address+1);
    vec4 coarsePoint=texelFetch(fv_MorphPositions,address+2);
    vec4 farPoint=texelFetch(fv_MorphPositions,address+3);
    MorphVertex point=MorphVertex(texelFetch(fv_MorphPositions,address),roundedPoint,
            coarsePoint,farPoint,vec4(roundedPoint.w,coarsePoint.w,farPoint.w,0));
    vec3 position=point.position.xyz,smoothed=point.smoothed.xyz,parent4=point.parent4.xyz,parent8=point.parent8.xyz,averagedNormal=point.normal.xyz;
    vec3 normals[6]=vec3[6](vec3(-1,0,0),vec3(1,0,0),vec3(0,-1,0),vec3(0,1,0),vec3(0,0,-1),vec3(0,0,1));
    vec3 faceNormal=normals[faceVertex.w&7u];
    vec3 tint=vec3((faceVertex.z>>16u)&255u,(faceVertex.z>>8u)&255u,faceVertex.z&255u)/255.0;
    uint light=faceVertex.w>>3u;
    vec3 materialLight=vec3(float(faceVertex.y),float(light&15u)/15.0,float((light>>4u)&15u)/15.0);
    vec2 locks=vec2(point.position.w,0);

    fv_CoveragePosition=position+fv_SurfaceOrigin;
    float distance=max(abs(fv_CoveragePosition.x),abs(fv_CoveragePosition.z));
    float rounded=smoothstep(fv_HybridNear,fv_HybridNear+fv_SurfaceBands.x,distance)*(1.0-locks.x);
    float coarse=smoothstep(fv_HybridNear+fv_SurfaceBands.x,fv_HybridNear+fv_SurfaceBands.y,distance)*(1.0-locks.x);
    // Every vertex collapsed onto the same parent follows the same next morph.
    // This permits removing its degenerate triangles throughout the transition.
    vec3 coarsePosition=parent4+fv_SurfaceOrigin;
    float farDistance=max(abs(coarsePosition.x),abs(coarsePosition.z));
    float farCoarse=smoothstep(fv_HybridNear+fv_SurfaceBands.y,fv_HybridNear+fv_SurfaceBands.z,farDistance)*(1.0-locks.x);
    // Retain geometry when the proposed simplification would visibly move its silhouette.
    // FOV/resolution affect the decision; distance alone is not a quality measure.
    float pixels=fv_SurfacePixelScale/max(length(fv_CoveragePosition),1.0);
    coarse*=1.0-smoothstep(2.0,3.0,length(parent4-smoothed)*pixels);
    farCoarse*=1.0-smoothstep(2.0,3.0,length(parent8-parent4)*fv_SurfacePixelScale/max(length(coarsePosition),1.0));
    // Compacted topology is selected only after every vertex in the tile reaches
    // the corresponding target and its projected displacement stays within budget.
    if(fv_SurfaceLevel>=1)coarse=1.0;
    if(fv_SurfaceLevel>=2)farCoarse=1.0;
    vec3 p=mix(position,smoothed,rounded);
    p=mix(p,parent4,coarse);p=mix(p,parent8,farCoarse);
    fr_RelativePosition=p+fv_SurfaceOrigin;
    fv_OriginalPosition=position;fv_SurfaceNormal=normalize(mix(faceNormal,averagedNormal,rounded));
    fv_FaceNormal=faceNormal;fv_Tint=tint;fv_Material=materialLight.x;
    fr_BaseLight=vec3(1);fr_CanopyNormal=vec3(0);fr_SkyLight=materialLight.y;fr_BlockLight=materialLight.z;
    gl_Position=fv_SurfaceMVP*vec4(fr_RelativePosition,1);
}
