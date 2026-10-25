#version 460 core
in vec3 v_Position,v_Normal;
struct Material {vec4 uv;vec4 tint;};
layout(std430,binding=14) readonly buffer Materials {Material materials[];};
#ifdef FV_PACKED_QUADS
flat in uint v_Attribute;
#else
layout(std430,binding=13) readonly buffer Attributes {uint attributes[];};
#endif
uniform vec3 u_CameraPhase;
layout(binding=0) uniform sampler2D u_Atlas;
out vec4 outColour;
// FRAGMENT_MATERIAL_SOURCE
void main(){
#ifdef FV_PACKED_QUADS
 uint attributeData=v_Attribute;
#else
 uint attributeData=attributes[gl_PrimitiveID];
#endif
 uint face=(attributeData>>8u)&7u;
 Material m=materials[(attributeData>>11u)*6u+face];
 fr_RelativePosition=v_Position;fv_SurfaceNormal=normalize(v_Normal);
 fr_BaseLight=vec3(1);fr_CanopyNormal=m.tint.w==2.0?fv_SurfaceNormal:vec3(0);
 fr_SkyLight=float((attributeData>>4u)&15u)/15.;fr_BlockLight=float(attributeData&15u)/15.;
 vec3 worldUV=v_Position+u_CameraPhase;vec2 rawUV=face<2u?worldUV.zy:face<4u?worldUV.xz:worldUV.xy;
 vec2 scale=m.uv.zw-m.uv.xy;vec2 inset=.5/vec2(textureSize(u_Atlas,0));
 vec2 uv=m.uv.xy+clamp(fract(rawUV)*scale,inset,scale-inset);
 vec4 tex=textureGrad(u_Atlas,uv,dFdx(rawUV)*scale,dFdy(rawUV)*scale);
 if(tex.a<.1)discard;
 vec3 n=normalize(v_Normal);float shade=dot(n*n,vec3(.6,1,.8));
 outColour=vec4(fr_lighting(tex.rgb*m.tint.rgb*shade,m.tint.w),1);
}
