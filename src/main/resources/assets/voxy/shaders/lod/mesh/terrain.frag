#version 460 core
in vec3 v_Position,v_Normal;
struct Material {vec4 uv;vec4 tint;};
layout(std430,binding=14) readonly buffer Materials {Material materials[];};
#ifdef FV_PACKED_QUADS
flat in uint v_Attribute;
#else
layout(std430,binding=13) readonly buffer Attributes {uint attributes[];};
#endif
#ifdef FV_COMPACT_TERRAIN
uniform int u_AttributeBits,u_AttributeOffset;
#endif
#ifdef FV_NATIVE_COLLAR
in float v_NativeWeight;
#endif
uniform vec3 u_CameraPhase,u_Origin;
uniform int u_SurfaceMacroWidth;
uniform vec2 u_LodEdge;
layout(std430,binding=15) readonly buffer SurfaceColumns {uint surfaceColumns[];};
// Point lookup, not averaged colour: the current atlas still supplies the full texel detail.
uint fv_capMaterial(){
 ivec2 cell=clamp(ivec2(floor(v_Position.xz-u_Origin.xz))+1,ivec2(0),ivec2(u_SurfaceMacroWidth*4-1));
 uint bits=surfaceColumns[0],count=surfaceColumns[1];
 uint controls=2u+(count+1u)/2u;
 uint code=surfaceColumns[controls+uint((cell.y/4)*u_SurfaceMacroWidth+cell.x/4)];
 if((code&0x80000000u)!=0u)return code&65535u;
 uint sub=uint((cell.y%4)*4+cell.x%4),per=32u/bits;
 uint id=(surfaceColumns[code+sub/per]>>((sub%per)*bits))&((1u<<bits)-1u);
 return(surfaceColumns[2u+id/2u]>>((id%2u)*16u))&65535u;
}
layout(binding=0) uniform sampler2D u_Atlas;
out vec4 outColour;
uint fv_attribute(){
#ifdef FV_PACKED_QUADS
 return v_Attribute;
#elif defined(FV_COMPACT_TERRAIN)
 uint bits=uint(u_AttributeBits),per=32u/bits;
 uint word=attributes[uint(u_AttributeOffset)+uint(gl_PrimitiveID)/per];
 uint id=(word>>((uint(gl_PrimitiveID)%per)*bits))&((1u<<bits)-1u);
 return attributes[id];
#else
 return attributes[gl_PrimitiveID];
#endif
}
// FRAGMENT_MATERIAL_SOURCE
// NormalRenderPipeline.finish composites this colour and applies shared air once.
// FR_LOD_MATERIAL leaves the lighting result un-fogged for that pass.
void main(){
 if(max(length(v_Position.xz),abs(v_Position.y))>u_LodEdge.y)discard;
 uint attributeData=fv_attribute();
 uint face=(attributeData>>8u)&7u;
 uint materialID=attributeData>>11u;
 vec3 normal=normalize(v_Normal);
 if(u_SurfaceMacroWidth>0&&normal.y>=max(abs(normal.x),abs(normal.z))){
  uint cap=fv_capMaterial();if(cap!=0u){materialID=cap;face=3u;}
 }
 Material m=materials[materialID*6u+face];
 fr_RelativePosition=v_Position;fv_SurfaceNormal=normalize(v_Normal);
#ifdef FV_NATIVE_COLLAR
 vec3 axis=face==0u?vec3(-1,0,0):face==1u?vec3(1,0,0):face==2u?vec3(0,-1,0):face==3u?vec3(0,1,0):face==4u?vec3(0,0,-1):vec3(0,0,1);
 fv_SurfaceNormal=normalize(mix(axis,fv_SurfaceNormal,v_NativeWeight));
#endif
 fr_BaseLight=vec3(1);fr_CanopyNormal=m.tint.w==2.0?fv_SurfaceNormal:vec3(0);
 fr_SkyLight=float((attributeData>>4u)&15u)/15.;fr_BlockLight=float(attributeData&15u)/15.;
 // Far water reuses Fragment Render's deep scattering and reflected sky. It is
 // opaque at LOD distances; native water retains refraction and shoreline foam.
 if(m.tint.w==5.){outColour=vec4(fr_lodWaterColor(),1);return;}
 vec3 worldUV=v_Position+u_CameraPhase;vec2 rawUV=face<2u?worldUV.zy:face<4u?worldUV.xz:worldUV.xy;
 vec2 scale=m.uv.zw-m.uv.xy;vec2 inset=.5/vec2(textureSize(u_Atlas,0));
 vec2 uv=m.uv.xy+clamp(fract(rawUV)*scale,inset,scale-inset);
 vec4 tex=textureGrad(u_Atlas,uv,dFdx(rawUV)*scale,dFdy(rawUV)*scale);
 if(tex.a<.1)discard;
 vec3 n=normalize(fv_SurfaceNormal);float shade=dot(n*n,vec3(.6,1,.8));
 outColour=vec4(fr_lighting(tex.rgb*m.tint.rgb*shade,m.tint.w>=6.?0.:m.tint.w),1);
}
