#version 460 core
#ifdef FV_PACKED_QUADS
layout(location=0) in uvec2 packedQuad;
flat out uint v_Attribute;
struct Material {vec4 uv;vec4 tint;};
layout(std430,binding=14) readonly buffer Materials {Material materials[];};
#elif defined(FV_COMPACT_TERRAIN)
layout(location=0) in uvec3 packedPosition;
#else
layout(location=0) in uvec2 packedPosition;
#endif
uniform mat4 u_MVP;
uniform vec3 u_Origin;
out vec3 v_Position,v_Normal;
void main(){
#ifdef FV_PACKED_QUADS
 uvec2 q=packedQuad;uint lo=q.x,hi=q.y;
 uint face=(hi>>1u)&7u;float x=float(lo&63u),y=float((lo>>6u)&511u),z=float((lo>>15u)&63u);
 float u=float((lo>>21u)&63u)+1.,v=float(((lo>>27u)|(hi<<5u))&63u)+1.;
 uint light=(hi>>4u)&255u,material=(hi>>12u)&65535u;v_Attribute=(material<<11u)|(face<<8u)|light;
 int k=gl_VertexID;vec2 c=vec2((k==2||k==3)?1.:0.,(k==1||k==2)?1.:0.);
 vec3 p,n;
 if(face==0u){p=vec3(x,y+c.x*v,z+c.y*u);n=vec3(-1,0,0);}
 else if(face==1u){p=vec3(x+1.,y+c.x*v,z+(1.-c.y)*u);n=vec3(1,0,0);}
 else if(face==2u){p=vec3(x+c.x*u,y,z+(1.-c.y)*v);n=vec3(0,-1,0);}
 else if(face==3u){p=vec3(x+c.x*u,y+1.,z+c.y*v);n=vec3(0,1,0);}
 else if(face==4u){p=vec3(x+(1.-c.y)*u,y+c.x*v,z);n=vec3(0,0,-1);}
 else {p=vec3(x+c.y*u,y+c.x*v,z+1.);n=vec3(0,0,1);}
 float kind=materials[material*6u+face].tint.w;
 // ABI v2 marks intrinsic snow heights and the exposed side above a lower neighbour.
 // Legacy v1 full-cube pages remain available for a faithful geometry A/B.
 if((hi&0x80000000u)!=0u){
  float height=kind>=6.?kind-6.:1.;float lower=float((hi>>28u)&7u)/8.;
  if(face==3u)p.y=y+height;
  else if(face<2u||face>3u)p.y=mix(y+lower,y+height,c.x);
 }
 // Source water surface is 8/9 of a full block at an exposed top.
 if(kind==5.){
  if(face==3u)p.y-=1./9.;
  else if((face<2u||face>3u)&&c.x>.5)p.y-=1./9.;
 }
#elif defined(FV_COMPACT_TERRAIN)
 uint lo=packedPosition.x|(packedPosition.y<<16u),hi=packedPosition.z;
 vec3 p=vec3(float(lo&1023u),float((lo>>10u)&8191u),float(((lo>>23u)|(hi<<9u))&1023u))/16.-1.;
 vec2 oct=vec2(float((hi>>1u)&127u),float((hi>>8u)&127u))/127.*2.-1.;
 vec3 n=vec3(oct,1.-abs(oct.x)-abs(oct.y));
 if(n.z<0.)n.xy=(1.-abs(n.yx))*mix(vec2(-1),vec2(1),greaterThanEqual(n.xy,vec2(0)));
#else
 uint lo=packedPosition.x,hi=packedPosition.y;
 vec3 p=vec3(float(lo&32767u),float(lo>>15u),float(hi&32767u))/256.-1.;
 vec2 oct=vec2(float((hi>>15u)&255u),float((hi>>23u)&255u))/255.*2.-1.;
 vec3 n=vec3(oct,1.-abs(oct.x)-abs(oct.y));
 if(n.z<0.) n.xy=(1.-abs(n.yx))*mix(vec2(-1),vec2(1),greaterThanEqual(n.xy,vec2(0)));
#endif
 v_Normal=normalize(n);v_Position=p+u_Origin;gl_Position=u_MVP*vec4(v_Position,1);
}
