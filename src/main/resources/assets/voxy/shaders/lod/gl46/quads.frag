#version 460 core
//Use quad shuffling to compute fragment mip
//#extension GL_KHR_shader_subgroup_quad: enable
#ifdef USE_SINGLE_TRI
#define USE_NV_BARRY
#endif

#ifdef USE_NV_BARRY
#extension GL_NV_fragment_shader_barycentric: require
#endif

layout(binding = 0) uniform sampler2D blockModelAtlas;
layout(binding = 2) uniform sampler2D depthTex;

const vec2 MODEL_FACE_TILE_SCALE = 1.0 / (vec2(3.0, 2.0) * 256.0);
const vec2 MODEL_FACE_TEXEL_INSET = vec2(0.5 / 16.0);

//#define DEBUG_RENDER

//TODO: need to fix when merged quads have discardAlpha set to false but they span multiple tiles
// however they are not a full block

layout(location = 0) in flat uvec4 interData;
#ifndef USE_NV_BARRY
layout(location = 1) in vec2 uv;
#endif

#ifdef DEBUG_RENDER
layout(location = 7) in flat uint quadDebug;
#endif


#ifndef PATCHED_SHADER
layout(location = 0) out vec4 outColour;
#else

//Bind the model buffer and import the model system as we need it
#define MODEL_BUFFER_BINDING 3
#import <voxy:lod/block_model.glsl>

#endif

#import <voxy:lod/gl46/bindings.glsl>
#import <voxy:lod/lighting.glsl>


#import <voxy:util/depthutils.glsl>


vec4 uint2vec4RGBA(uint colour) {
    return vec4((uvec4(colour)>>uvec4(24,16,8,0))&uvec4(0xFF))/255.0;
}

//bool useMipmaps() {
//    return (interData.x&2u)==0u;
//}

uint tintingState() {
    return (interData.x>>2)&3u;
}

bool useDiscard() {
    return (interData.x&1u)==1u;
}

uint getFace() {
    return (interData.x>>4)&7u;
}


uint getModelId() {
    return interData.x>>16;
}

vec2 getBaseUV() {
    uint face = getFace();
    uint modelId = interData.x>>16;
    vec2 modelUV = vec2(modelId&0xFFu, (modelId>>8)&0xFFu)*(1.0/(256.0));
    return modelUV + (vec2(face>>1, face&1u) * MODEL_FACE_TILE_SCALE);
}


#ifdef PATCHED_SHADER
struct VoxyFragmentParameters {
    //TODO: pass in derivative data
    vec4 sampledColour;
    vec2 tile;
    vec2 uv;
    uint face;
    uint modelId;
    vec2 lightMap;
    vec4 tinting;
    uint customId;//Same as iris's modelId
};

void voxy_emitFragment(VoxyFragmentParameters parameters);
#else

vec4 computeColour(vec2 texturePos, vec4 colour) {
    //Conditional tinting, TODO: FIXME: this is better but still not great, try encode data into the top bit of alpha so its per pixel

    uint tintingFunction = tintingState();
    bool doTint = tintingFunction==2;//Always tint if function == 2
    if (tintingFunction == 1) {//partial tint
        vec4 tintTest = textureLod(blockModelAtlas, texturePos, 0);
        if (abs(tintTest.r-tintTest.g) < 0.02f && abs(tintTest.g-tintTest.b) < 0.02f) {
            doTint = true;
        }
    }
    if (doTint) {
        colour *= uint2vec4RGBA(interData.z).yzwx;
    }
    return (colour * uint2vec4RGBA(interData.y)) + vec4(0,0,0,float(interData.w&0xFFu)/255);
}

#endif


// FRAGMENT_MATERIAL_SOURCE
#ifdef FV_HYBRID_TERRAIN
in vec3 fv_HybridPosition;
flat in int fv_HybridNatural;
#import <voxy:lod/hybrid/coverage.glsl>
#endif
#ifdef FR_LOD_MATERIAL
flat in float fr_LodMaterial;
uniform int fr_LodDebug;
layout(binding = 11) uniform sampler2D fv_LeafAtlas;
#endif
void main() {
    //vec2 uv = vec2(0);
    //Tile is the tile we are in
    vec2 tile;
    #ifdef USE_NV_BARRY
    #ifdef USE_SINGLE_TRI
    if (gl_BaryCoordNV.x>=0.5||gl_BaryCoordNV.y>=0.5) discard;
    vec2 uv = gl_BaryCoordNV.yx*(vec2((interData.x>>8)&0xFu, (interData.x>>12)&0xFu)+1)*2;
    #else
    vec2 uv = mix(gl_BaryCoordNV.yx, 1-gl_BaryCoordNV.xz, gl_PrimitiveID&1)*(vec2((interData.x>>8)&0xFu, (interData.x>>12)&0xFu)+1);
    #endif
    #endif

    vec2 localUv = clamp(modf(uv, tile), MODEL_FACE_TEXEL_INSET, 1.0 - MODEL_FACE_TEXEL_INSET);
    vec2 uv2 = localUv * MODEL_FACE_TILE_SCALE;
    vec4 colour;
    vec2 texPos = uv2 + getBaseUV();
//This is deprecated, TODO: remove the non mip code path
    //if (useMipmaps())
    {
        vec2 uvSmol = uv * MODEL_FACE_TILE_SCALE;
        vec2 dx = dFdx(uvSmol);//vec2(lDx, dDx);
        vec2 dy = dFdy(uvSmol);//vec2(lDy, dDy);
#ifdef FR_LOD_MATERIAL
        float leafBias = fr_LodHigh != 0 && fr_LodMaterial > 1.5 && fr_LodMaterial < 2.5 ? 1.68179283 : 1.0;
#ifdef FR_LEAF_DETAIL_POLICY
        if (fr_LodHigh != 0 && fr_LeafDetail != 0 && ((interData.w >> 3u) & 7u) == 0u && fr_LodMaterial > 1.5 && fr_LodMaterial < 2.5) {
            vec4 gradients = fr_leafGradients(uvSmol, vec2(textureSize(blockModelAtlas, 0)), fr_RelativePosition, fr_LeafMipBias);
            dx = gradients.xy; dy = gradients.zw;
        } else
#endif
        { dx *= leafBias; dy *= leafBias; }
#endif
#ifdef FR_LOD_MATERIAL
        if (fr_LightEnabled != 0 && fr_LodHigh != 0 && fr_LodMaterial > 1.5 && fr_LodMaterial < 2.5) {
            vec2 atlasSize = vec2(textureSize(blockModelAtlas, 0));
            float footprint = max(length(dx * atlasSize), length(dy * atlasSize));
            float mip = clamp(log2(max(footprint, 1.0)), 0.0, 4.0);
            // Clamp against the coarser trilinear level: every face/model stays
            // inside its own tile, including the final 1x1 average colour.
            float inset = 0.5 / exp2(4.0 - ceil(mip));
            vec2 filteredUV = clamp(localUv, vec2(inset), vec2(1.0 - inset));
            colour = textureLod(fv_LeafAtlas, getBaseUV() + filteredUV * MODEL_FACE_TILE_SCALE, mip);
        }
        else
#endif
        colour = textureGrad(blockModelAtlas, texPos, dx, dy);
    }// else {
    //    colour = textureLod(blockModelAtlas, texPos, 0);
    //}

    //If we are in shaders and are a helper invocation, just exit, as it enables extra performance gains for small sized
    // fragments, we do this here after derivative computation
    //Trying it with all shaders
    //#ifdef PATCHED_SHADER
    #ifndef PATCHED_SHADER_ALLOW_DERIVATIVES
    if (gl_HelperInvocation) {
        return;
    }
    #endif
    //#endif

    if (any(notEqual(clamp(tile, vec2(0), vec2((interData.x>>8)&0xFu, (interData.x>>12)&0xFu)), tile))) {
        discard;
        return;
    }

    //Check the minimum bounding texture and ensure we are greater than it
    if (DEPTH_SCALAR_COMPARE(gl_FragCoord.z, texelFetch(depthTex, ivec2(gl_FragCoord.xy), 0).r)) {
        discard;
        return;
    }


#if defined(FV_HYBRID_TERRAIN) && !defined(TRANSLUCENT)
    if(fv_HybridNatural!=0 && fv_hybridWeight(fv_HybridPosition)>0.5)discard;
#endif
    //Also, small quad is really fking over the mipping level somehow
    float cutoutAlpha = 1.0;
#ifndef TRANSLUCENT
    if (useDiscard())
#endif
    {
#ifdef FR_LOD_MATERIAL
        // Match native filtered alpha: sharp level-zero cutouts made distant crowns noisy.
        if (fr_LightEnabled != 0 && fr_LodMaterial > 1.5 && fr_LodMaterial < 2.5)
            cutoutAlpha = colour.a;
        else
#endif
            cutoutAlpha = textureLod(blockModelAtlas, texPos, 0).a;
    }
    #ifndef TRANSLUCENT
    colour.a = 1.0f;
    float cutoff = 0.1;
#ifdef FR_LOD_MATERIAL
#ifdef FR_LEAF_DETAIL_POLICY
    if (fr_LodHigh != 0 && fr_LeafDetail != 0 && ((interData.w >> 3u) & 7u) == 0u && fr_LodMaterial > 1.5 && fr_LodMaterial < 2.5)
        cutoff = fr_leafCutoff(fr_RelativePosition);
#endif
#endif
    if (useDiscard() && (cutoutAlpha <= cutoff)) {
    //if (useDiscard() && (colour.a <= 0.1f)) {
    #else
    if (cutoutAlpha == 0.0f) {
    #endif
        //This is stupidly stupidly bad for divergence
        //TODO: FIXME, basicly what this do is sample the exact pixel (no lod) for discarding, this stops mipmapping fucking it over
        #ifndef DEBUG_RENDER
        discard;
        return;
        #endif
    }

    #ifndef PATCHED_SHADER_ALLOW_DERIVATIVES
    if (gl_HelperInvocation) {
        return;
    }
    #endif

    #ifndef PATCHED_SHADER
    colour = computeColour(texPos, colour);
#ifdef FR_LOD_MATERIAL
    if (fr_LodMaterial > 4.5 && fr_LightEnabled != 0) {
        colour.rgb = fr_lodWaterColor();
        colour.a = 1.0;
    } else colour.rgb = fr_lighting(colour.rgb, fr_LodMaterial > 4.5 ? 0.0 : fr_LodMaterial);
    if (fr_LodDebug != 0) colour.rgb = vec3(1.0, 0.0, 0.8);
#endif
    outColour = colour;

    #ifdef DEBUG_RENDER
    uint hash = quadDebug*1231421+123141;
    hash ^= hash>>16;
    hash = hash*1231421+123141;
    hash ^= hash>>16;
    hash = hash * 1827364925 + 123325621;
    outColour = vec4(float(hash&15u)/15, float((hash>>4)&15u)/15, float((hash>>8)&15u)/15, 0);
    #endif

    #else
    uint modelId = getModelId();
    BlockModel model = modelData[modelId];
    uint tintingFunction = tintingState();
    bool doTint = tintingFunction==2;//Always tint if function == 2
    if (tintingFunction==1) {//Partial tint
        vec4 tintTest = texture(blockModelAtlas, texPos, -2);
        if (abs(tintTest.r-tintTest.g) < 0.02f && abs(tintTest.g-tintTest.b) < 0.02f) {
            doTint = true;
        }
    }
    vec4 tint = vec4(1);
    if (doTint) {
        tint = uint2vec4RGBA(interData.z).yzwx;
    }

    uint face = getFace();
    face ^= uint((face&1u)!=uint(gl_FrontFacing!=((face>>1)!=0u)));
    voxy_emitFragment(VoxyFragmentParameters(colour, tile, texPos, face, modelId, getLightmapUv(interData.y), tint, model.customId));

    #endif
}



//#ifdef GL_KHR_shader_subgroup_quad
/*
uint hash = (uint(tile.x)*(1<<16))^uint(tile.y);
uint horiz = subgroupQuadSwapHorizontal(hash);
bool sameTile = horiz==hash;
uint sv = mix(uint(-1), hash, sameTile);
uint vert = subgroupQuadSwapVertical(sv);
sameTile = sameTile&&vert==hash;
mipBias = sameTile?0:-5.0;
*/
/*
vec2 uvSmol = uv*(1.0/(vec2(3.0,2.0)*256.0));
float lDx = subgroupQuadSwapHorizontal(uvSmol.x)-uvSmol.x;
float lDy = subgroupQuadSwapVertical(uvSmol.y)-uvSmol.y;
float dDx = subgroupQuadSwapDiagonal(lDx);
float dDy = subgroupQuadSwapDiagonal(lDy);
vec2 dx = vec2(lDx, dDx);
vec2 dy = vec2(lDy, dDy);
colour = textureGrad(blockModelAtlas, texPos, dx, dy);
*/
//#else
//colour = texture(blockModelAtlas, texPos);
//#endif

//Undefine the depth stuff
#import <voxy:util/depthutils.glsl>

