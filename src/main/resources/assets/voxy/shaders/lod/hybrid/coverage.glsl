uniform int fv_HybridEnabled;
uniform sampler2D fv_HybridCoverage;
uniform vec4 fv_HybridGrid; // camera-relative origin x/z, spacing, cell count
uniform float fv_HybridNear;

float fv_hybridWeight(vec3 relativePosition) {
    if(fv_HybridEnabled==0)return 0.0;
    vec2 cell=floor((relativePosition.xz-fv_HybridGrid.xy)/fv_HybridGrid.z);
    if(any(lessThan(cell,vec2(0)))||any(greaterThanEqual(cell,vec2(fv_HybridGrid.w))))return 0.0;
    float coverage=texelFetch(fv_HybridCoverage,ivec2(cell),0).r;
    float distanceToNative=max(abs(relativePosition.x),abs(relativePosition.z));
    return coverage*step(fv_HybridNear,distanceToNative);
}
