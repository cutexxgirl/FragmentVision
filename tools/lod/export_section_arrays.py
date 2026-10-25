"""Vectorized diagnostic exporter. Read CLOSED saves only. No block generation or light substitution."""
import sys,gzip,struct,json,os
from pathlib import Path
import numpy as np
sys.path.insert(0,str(Path(__file__).resolve().parent))
from export_world import Anvil,AIR,GROUND,state_key,simplifiable

def export(region,output,origin,size,boxes,simplify_leaves=False):
 ox,oy,oz=origin;sx,sy,sz=size
 if sx<3 or sz<3 or sy<3 or sx>144 or sz>144 or sy>512 or sx*sy*sz>8*1024*1024:raise ValueError('Snapshot dimensions outside budget')
 if any(origin[i]+size[i]>2147483647 or origin[i]<-2147483648 for i in range(3)):raise ValueError('Snapshot coordinate range')
 if any(any(b[i]>=b[i+3] for i in range(3)) for b in boxes):raise ValueError('Invalid natural box')
 source=Anvil(Path(region));palette=[('minecraft:air','minecraft:plains')];keys={};ids=np.full((sy,sz,sx),-1,dtype=np.int32);lights=np.zeros(ids.shape,dtype=np.uint8);natural=np.zeros_like(lights)
 for cz in range(oz//16,(oz+sz-1)//16+1):
  for cx in range(ox//16,(ox+sx-1)//16+1):
   chunk=source.chunk(cx,cz)
   if chunk is None:continue
   for cy in range(oy//16,(oy+sy-1)//16+1):
    wx,wy,wz=cx*16,cy*16,cz*16
    ax=max(0,ox-wx);ay=max(0,oy-wy);az=max(0,oz-wz);bx=min(16,ox+sx-wx);by=min(16,oy+sy-wy);bz=min(16,oz+sz-wz)
    dst=np.s_[wy+ay-oy:wy+by-oy,wz+az-oz:wz+bz-oz,wx+ax-ox:wx+bx-ox];cut=np.s_[ay:by,az:bz,ax:bx]
    section=chunk.get(cy)
    if section is None:
     if cy not in chunk:ids[dst]=0
     continue
    states,blocks,biomes,biome_ids,sky,block=section
    b=np.asarray(blocks,dtype=np.int32).reshape(16,16,16)[cut];bio=np.asarray(biome_ids,dtype=np.int32).reshape(4,4,4).repeat(4,0).repeat(4,1).repeat(4,2)[cut]
    pairs=b*len(biomes)+bio;lookup=np.empty(len(states)*len(biomes),dtype=np.int32)
    for p in np.unique(pairs):
     st=states[int(p)//len(biomes)];biome=biomes[int(p)%len(biomes)]
     if st['Name'] in AIR:lookup[p]=0;continue
     key=(state_key(st),biome)
     if key not in keys:
      if len(palette)>=65536:raise ValueError('Palette budget exceeded')
      keys[key]=len(palette);palette.append(key)
     lookup[p]=keys[key]
    ids[dst]=lookup[pairs]
    def nibble(data):
     if not data:return np.zeros((16,16,16),dtype=np.uint8)
     d=np.frombuffer(bytes(data),dtype=np.uint8);v=np.empty(4096,dtype=np.uint8);v[::2]=d&15;v[1::2]=d>>4;return v.reshape(16,16,16)
    lights[dst]=(nibble(sky)[cut]<<4)|nibble(block)[cut]
    ground=np.array([simplifiable(s,simplify_leaves) for s in states],dtype=np.uint8)[b];allowed=np.zeros(b.shape,dtype=bool)
    ys=np.arange(wy+ay,wy+by)[:,None,None];zs=np.arange(wz+az,wz+bz)[None,:,None];xs=np.arange(wx+ax,wx+bx)[None,None,:]
    for a,bb,c,d,e,f in boxes:allowed|=(xs>=a)&(xs<d)&(ys>=bb)&(ys<e)&(zs>=c)&(zs<f)
    natural[dst]=ground&allowed
 output=Path(output);output.parent.mkdir(parents=True,exist_ok=True);temporary=output.with_suffix('.partial')
 try:
  with temporary.open('wb') as destination, gzip.GzipFile(fileobj=destination,mode='wb',mtime=0,filename='') as out:
   out.write(b'FVOL0001'+struct.pack('>7i',ox,oy,oz,sx,sy,sz,len(palette)))
   for pair in palette:
    for text in pair:
     data=text.encode();out.write(struct.pack('>i',len(data))+data)
   out.write(ids.astype('>i4').tobytes());out.write(lights.tobytes());out.write(natural.tobytes())
  os.link(temporary,output)
 finally:temporary.unlink(missing_ok=True)
 return {'palette':len(palette),'unknown':int(np.count_nonzero(ids<0)),'chunks':source.read_chunks}

def read(p):
 with gzip.open(p,'rb') as f:
  assert f.read(8)==b'FVOL0001';ox,oy,oz,sx,sy,sz,n=struct.unpack('>7i',f.read(28));palette=[]
  for i in range(n):
   pair=[]
   for _ in range(2):pair.append(f.read(struct.unpack('>i',f.read(4))[0]).decode())
   palette.append(tuple(pair))
  cells=sx*sy*sz;ids=np.frombuffer(f.read(cells*4),dtype='>i4');light=f.read(cells);natural=f.read(cells)
  assert f.read()==b'';return palette,ids,light,natural
if __name__=='__main__':
 import argparse
 parser=argparse.ArgumentParser(description='Closed-world diagnostic snapshot exporter using section arrays; same input semantics as export_world.')
 parser.add_argument('region',type=Path);parser.add_argument('output',type=Path)
 parser.add_argument('--origin',nargs=3,type=int,required=True);parser.add_argument('--size',nargs=3,type=int,required=True)
 parser.add_argument('--natural-box',nargs=6,type=int,action='append',default=[])
 parser.add_argument('--simplify-natural-leaves',action='store_true',help='Allow nonpersistent dry vanilla leaves inside natural boxes')
 args=parser.parse_args();print(json.dumps(export(args.region,args.output,args.origin,args.size,args.natural_box,args.simplify_natural_leaves),indent=2))
