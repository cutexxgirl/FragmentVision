package me.cortex.voxy.client.core.rendering.lod.runtime;

import me.cortex.voxy.client.core.rendering.lod.bake.*;
import me.cortex.voxy.client.mixin.sodium.AccessorSodiumWorldRenderer;
import net.caffeinemc.mods.sodium.client.render.SodiumWorldRenderer;
import net.minecraft.client.Minecraft;
import org.lwjgl.system.MemoryUtil;
import java.util.*;
import static org.lwjgl.opengl.GL45C.*;

/** Native-ready exterior footprint, shared by all near pages, including concave corners.
 * Built/uploaded only when readiness changes; no voxel lookup or boundary loop per GPU vertex.
 */
final class NativeTerrainJoin implements AutoCloseable {
 private final Map<NativeTransitionField.Cell,Set<Integer>> requirements=new HashMap<>();
 private Set<NativeTransitionField.Cell> ready=Set.of(),observed=Set.of(),active=Set.of();
 private record Bounds(int x,int z,int width){}
 private final Map<Bounds,Boolean> affected=new HashMap<>();
 private record Field(int texture,int x,int z,int width,int height,float[] weights){
  float at(int wx,int wz){int ix=wx-x,iz=wz-z;return ix<0||iz<0||ix>=width||iz>=height?1:weights[iz*width+ix];}
 }
 private Field current,previous;private long changed;
 private int activeTexture;private int[] savedTextures,savedSamplers;
 private final Map<Integer,int[]> uniforms=new HashMap<>();
 void register(PackedQuads quads,int ox,int oy,int oz){
  for(long q:quads.data()){
   int face=PackedQuads.face(q);if(face==2)continue;
   int x=PackedQuads.x(q),y=PackedQuads.y(q),z=PackedQuads.z(q);
   for(int v=0;v<PackedQuads.v(q);v++)for(int u=0;u<PackedQuads.u(q);u++){
    int wx=ox+x+(face<2?0:u),wy=oy+y+(face<2||face>3?v:0),wz=oz+z+(face<2?u:face<4?v:0);
    var c=new NativeTransitionField.Cell(Math.floorDiv(wx,16),Math.floorDiv(wz,16));
    requirements.computeIfAbsent(c,k->new HashSet<>()).add(Math.floorDiv(wy,16));
   }
  }
 }
 void update(){
  long now=System.nanoTime();
  var mc=Minecraft.getInstance();var world=SodiumWorldRenderer.instanceNullable();
  var manager=world==null?null:((AccessorSodiumWorldRenderer)world).getRenderSectionManager();
  var next=new HashSet<NativeTransitionField.Cell>();
  if(manager!=null&&mc.level!=null){var camera=mc.gameRenderer.getMainCamera().getPosition();double limit=(mc.options.renderDistance().get()+3)*16.;
   for(var entry:requirements.entrySet()){
    var c=entry.getKey();if(Math.abs(c.x()*16.+8-camera.x)>limit||Math.abs(c.z()*16.+8-camera.z)>limit)continue;
    // Conservative union: any built exterior section keeps the whole column exact.
    // Requiring ALL heights under-counts ready cliffs with occluded lower sections.
    boolean built=false;for(int y:entry.getValue())if(manager.isSectionBuilt(c.x(),y,c.z())){built=true;break;}
    if(built)next.add(c);
   }
  }
  if(current!=null&&next.equals(observed)){
   if(now-changed>=150_000_000L&&!active.equals(ready)){active=ready;affected.clear();}
   return;
  }
  var held=new HashSet<>(active);held.addAll(next);active=Set.copyOf(held);observed=Set.copyOf(next);
  ready=Set.copyOf(next);affected.clear();var field=new NativeTransitionField(ready,2,12);
  int minX=0,minZ=0,maxX=0,maxZ=0;
  if(!active.isEmpty()){
   minX=active.stream().mapToInt(c->c.x()*16).min().orElseThrow()-16;minZ=active.stream().mapToInt(c->c.z()*16).min().orElseThrow()-16;
   maxX=active.stream().mapToInt(c->(c.x()+1)*16).max().orElseThrow()+16;maxZ=active.stream().mapToInt(c->(c.z()+1)*16).max().orElseThrow()+16;
  }
  int w=Math.max(1,maxX-minX+1),h=Math.max(1,maxZ-minZ+1);if(w>1024||h>1024){ready=active=Set.of();affected.clear();field=new NativeTransitionField(ready,2,12);w=h=1;minX=minZ=0;}
  float[] weights=new float[w*h],resolved=new float[w*h];
  float t=(float)Math.min(1,(now-changed)/150_000_000.);
  for(int z=0;z<h;z++)for(int x=0;x<w;x++){
   int wx=minX+x,wz=minZ+z,index=z*w+x;weights[index]=(float)field.weight(wx,wz);
   if(current==null)resolved[index]=weights[index];else{
    float old=current.at(wx,wz),start=previous.at(wx,wz);
    resolved[index]=Math.min(old,start+(old-start)*t);
   }
  }
  Field nextCurrent=upload(weights,minX,minZ,w,h),nextPrevious=upload(resolved,minX,minZ,w,h);
  if(current!=null)glDeleteTextures(current.texture);if(previous!=null&&previous!=current)glDeleteTextures(previous.texture);
  current=nextCurrent;previous=nextPrevious;changed=now;
 }
 private static Field upload(float[] weights,int x,int z,int w,int h){
  var values=MemoryUtil.memAllocFloat(weights.length);int tex=glCreateTextures(GL_TEXTURE_2D);
  try{values.put(weights).flip();glTextureStorage2D(tex,1,GL_R32F,w,h);glTextureSubImage2D(tex,0,0,0,w,h,GL_RED,GL_FLOAT,values);
   glTextureParameteri(tex,GL_TEXTURE_MIN_FILTER,GL_LINEAR);glTextureParameteri(tex,GL_TEXTURE_MAG_FILTER,GL_LINEAR);
   glTextureParameteri(tex,GL_TEXTURE_WRAP_S,GL_CLAMP_TO_BORDER);glTextureParameteri(tex,GL_TEXTURE_WRAP_T,GL_CLAMP_TO_BORDER);glTextureParameterfv(tex,GL_TEXTURE_BORDER_COLOR,new float[]{1,1,1,1});
  }finally{MemoryUtil.memFree(values);}return new Field(tex,x,z,w,h,weights);
 }
 boolean affects(int x,int z,int width){return affected.computeIfAbsent(new Bounds(x,z,width),b->affectsUncached(x,z,width));}
 private boolean affectsUncached(int x,int z,int width){
  for(var c:active){double dx=Math.max(0,Math.max(c.x()*16.-(x+width),x-(c.x()+1)*16.));double dz=Math.max(0,Math.max(c.z()*16.-(z+width),z-(c.z()+1)*16.));if(dx*dx+dz*dz<=14*14)return true;}return false;
 }
 void saveBindings(){
  activeTexture=glGetInteger(GL_ACTIVE_TEXTURE);savedTextures=new int[2];savedSamplers=new int[2];
  for(int i=0;i<2;i++){glActiveTexture(GL_TEXTURE0+11+i);savedTextures[i]=glGetInteger(GL_TEXTURE_BINDING_2D);savedSamplers[i]=glGetIntegeri(GL_SAMPLER_BINDING,11+i);}glActiveTexture(activeTexture);
 }
 void restoreBindings(){if(savedTextures==null)return;for(int i=0;i<2;i++){glBindTextureUnit(11+i,savedTextures[i]);glBindSampler(11+i,savedSamplers[i]);}savedTextures=null;savedSamplers=null;}
 void bind(int program,double cameraX,double cameraZ){
  if(current==null)update();if(current==null)return;
  glBindTextureUnit(11,current.texture);glBindSampler(11,0);glBindTextureUnit(12,previous.texture);glBindSampler(12,0);
  int[] u=uniforms.computeIfAbsent(program,p->new int[]{glGetUniformLocation(p,"u_NativeCurrent"),glGetUniformLocation(p,"u_NativePrevious"),glGetUniformLocation(p,"u_NativeBlend")});
  origin(u[0],current,cameraX,cameraZ);origin(u[1],previous,cameraX,cameraZ);
  glUniform1f(u[2],(float)Math.min(1,(System.nanoTime()-changed)/150_000_000.));
 }
 private static void origin(int location,Field f,double x,double z){glUniform4f(location,(float)(x-f.x+.5),(float)(z-f.z+.5),1f/f.width,1f/f.height);}
 int readyColumns(){return ready.size();}
 @Override public void close(){if(current!=null)glDeleteTextures(current.texture);if(previous!=null&&previous!=current)glDeleteTextures(previous.texture);current=previous=null;ready=observed=active=Set.of();requirements.clear();uniforms.clear();affected.clear();}
}
