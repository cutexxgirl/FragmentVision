#version 450 core

layout(binding = 0) uniform sampler2D depthTex;
layout(location = 1) uniform mat4 invProjMat;
layout(location = 2) uniform mat4 projMat;

#ifdef EMIT_COLOUR
layout(binding = 3) uniform sampler2D colourTex;
#ifdef USE_ENV_FOG
layout(location = 4) uniform vec4 endParams;
layout(location = 5) uniform vec4 fogColour;
layout(location = 6) uniform vec4 renderParams;
#endif
#endif

#import <voxy:util/depthutils.glsl>

out vec4 colour;
in vec2 UV;

vec3 rev3d(vec3 clip) {
    vec4 view = invProjMat * vec4(SCREEN2NDC(clip),1.0f);
    return view.xyz/view.w;
}

float projDepth(vec3 pos) {
    vec4 view = projMat * vec4(pos, 1);
    return view.z/view.w;
}

// FRAGMENT_ATMOSPHERE_SOURCE
#ifdef FRAGMENT_ATMOSPHERE
uniform vec2 fv_LodEdge;
#endif

void main() {
    float depth = texture(depthTex, UV.xy).r;
    if (depth == 0.0f || depth == 1.0f) {
        discard;
    }

    vec3 point = rev3d(vec3(UV.xy, depth));
    depth = projDepth(point);
    //TODO: HERE make an option/define to emit the output depth as something other then the input (i.e. if voxy is reverse z and vanilla isnt, transform and emit as not reverrse z)
    depth = REDUCTION2(FAR+CLOSER_SIGN*(2.0f/((1<<24)-1)), depth);
    depth = NDC2SCREEN_DEPTH(depth);

    depth = gl_DepthRange.diff * depth + gl_DepthRange.near;//TODO: dont think this is right at all so should fix this

    gl_FragDepth = depth;

    #ifdef EMIT_COLOUR
    colour = texture(colourTex, UV.xy);
    if (colour.a == 0.0) {
        discard;
    }
    #ifdef FRAGMENT_ATMOSPHERE
    if (fr_Aerial.w > 0.5) {
        // A subpixel, depth-aware softening only in distant air. Reuses this
        // composite and its existing colour/depth targets; no blur framebuffer.
        float softness = fr_AerialSoftness * smoothstep(96.0, 256.0, length(point));
        if (softness > 0.001) {
            vec2 inverseSize = 1.0 / vec2(textureSize(colourTex, 0));
            vec2 pixel = 0.85 * inverseSize;
            vec2 offsets[4] = vec2[4](vec2(1,0), vec2(-1,0), vec2(0,1), vec2(0,-1));
            vec3 sum = colour.rgb;
            float total = 1.0;
            float distanceToEye = length(point);
            for (int i = 0; i < 4; i++) {
                vec2 uv = clamp(UV + offsets[i] * pixel, 0.5 * inverseSize, 1.0 - 0.5 * inverseSize);
                float d = texture(depthTex, uv).r;
                if (d <= 0.0 || d >= 1.0) continue;
                vec4 neighbour = texture(colourTex, uv);
                float gap = abs(length(rev3d(vec3(uv, d))) - distanceToEye);
                float w = (1.0 - smoothstep(max(1.0, distanceToEye * 0.01),
                            max(3.0, distanceToEye * 0.035), gap)) * step(0.01, neighbour.a);
                sum += neighbour.rgb * w;
                total += w;
            }
            colour.rgb = mix(colour.rgb, sum / total, softness);
        }
        colour.rgb = fr_aerialFog(colour.rgb, point);
        colour.rgb = fr_aerialLodEdge(colour.rgb, point, fv_LodEdge.x, fv_LodEdge.y);
    } else
    #endif
    {
    #ifdef USE_ENV_FOG
    if (fogColour.a>0.0){
        float environmentalDistance = endParams.w > 0.5 ? max(length(point.xz), abs(point.y)) : length(point.xyz);
        float environmentalFog = clamp(fma(environmentalDistance,endParams.x,endParams.y),0,endParams.z);
        float renderDistance = max(length(point.xz), abs(point.y));
        float renderFog = clamp(fma(renderDistance,renderParams.x,renderParams.y),0,renderParams.z);
        float fogLerp = smoothstep(0.0, 1.0, max(environmentalFog, renderFog));
        colour.rgb = mix(colour.rgb, fogColour.rgb, fogLerp*fogColour.a);
    }
    #endif
    }
    #else
    colour = vec4(0);
    #endif

}
