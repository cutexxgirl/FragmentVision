uniform int fv_HybridEarlyCull;
uniform usampler2D fv_HybridCoverageSum;
#import <voxy:lod/hybrid/coverage.glsl>

bool fv_hybridFullyCovered(vec3 first,vec3 last) {
    if(fv_HybridEnabled==0||fv_HybridEarlyCull==0)return false;
    vec2 lo=min(first.xz,last.xz),hi=max(first.xz,last.xz);
    vec2 nativeDistance=max(vec2(0),max(lo,-hi));
    if(max(nativeDistance.x,nativeDistance.y)<fv_HybridNear)return false;
    ivec2 a=ivec2(floor((lo-fv_HybridGrid.xy)/fv_HybridGrid.z));
    ivec2 b=ivec2(floor((hi-fv_HybridGrid.xy)/fv_HybridGrid.z));
    if(any(lessThan(a,ivec2(0)))||any(greaterThanEqual(b,ivec2(fv_HybridGrid.w))))return false;
    b+=1;
    // Prefix counts include every interior cell, not only corners of a large greedy quad.
    uint count=texelFetch(fv_HybridCoverageSum,b,0).r+texelFetch(fv_HybridCoverageSum,a,0).r
        -texelFetch(fv_HybridCoverageSum,ivec2(a.x,b.y),0).r
        -texelFetch(fv_HybridCoverageSum,ivec2(b.x,a.y),0).r;
    return count==uint((b.x-a.x)*(b.y-a.y));
}
