#version 460 core
#ifdef FV_PACKED_QUADS
layout(std430,binding=13) readonly buffer Quads {uvec2 quads[];};
flat out uint v_Attribute;
#else
layout(location=0) in uvec2 packedPosition;
#endif
uniform mat4 u_MVP;
uniform vec3 u_Origin;
out vec3 v_Position,v_Normal;
void main(){
#ifdef FV_PACKED_QUADS
 uvec2 q=quads[gl_BaseInstance+gl_InstanceID];uint lo=q.x,hi=q.y;
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
#else
 uint lo=packedPosition.x,hi=packedPosition.y;
 vec3 p=vec3(float(lo&32767u),float(lo>>15u),float(hi&32767u))/256.-1.;
 vec2 oct=vec2(float((hi>>15u)&255u),float((hi>>23u)&255u))/255.*2.-1.;
 vec3 n=vec3(oct,1.-abs(oct.x)-abs(oct.y));
 if(n.z<0.) n.xy=(1.-abs(n.yx))*mix(vec2(-1),vec2(1),greaterThanEqual(n.xy,vec2(0)));
#endif
 v_Normal=normalize(n);v_Position=p+u_Origin;gl_Position=u_MVP*vec4(v_Position,1);
}
