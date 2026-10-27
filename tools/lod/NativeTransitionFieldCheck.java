import me.cortex.voxy.client.core.rendering.lod.bake.NativeTransitionField;
import me.cortex.voxy.client.core.rendering.lod.bake.NativeTransitionField.Cell;
import java.util.*;
public final class NativeTransitionFieldCheck {
    static void close(double a,double b){if(Math.abs(a-b)>1e-9)throw new AssertionError(a+" != "+b);}
    public static void main(String[] args){
        Set<Cell> straight=new HashSet<>();for(int z=-8;z<8;z++)for(int x=1;x<5;x++)straight.add(new Cell(x,z));
        var line=new NativeTransitionField(straight,2,12);
        if(line.boundarySegments()!=4)throw new AssertionError("Internal chunk lines survived");
        close(line.weight(15,0),0);close(line.weight(14,0),0);close(line.weight(8,0),.5);close(line.weight(2,0),1);
        close(line.weight(8,15.999),line.weight(8,16.001));
        Set<Cell> corner=new HashSet<>(straight);for(int z=1;z<8;z++)for(int x=-8;x<1;x++)corner.add(new Cell(x,z));
        var l=new NativeTransitionField(corner,2,12);
        close(l.weight(8,8),.25);close(l.weight(15,4),0);close(l.weight(4,15),0);
        double e=.001,center=l.weight(8,8),a=(center-l.weight(8-e,8))/e,b=(l.weight(8+e,8)-center)/e;
        if(Math.abs(a-b)>1e-4)throw new AssertionError("Diagonal normal crease");
        var translated=new HashSet<Cell>();for(Cell c:corner)translated.add(new Cell(c.x()-10,c.z()-10));
        var negative=new NativeTransitionField(translated,2,12);close(l.weight(8,8),negative.weight(-152,-152));
        var single=new NativeTransitionField(Set.of(new Cell(0,0)),2,12);
        close(single.weight(-5,-5),single.weight(21,21));close(single.weight(-1,-1),0);close(single.weight(-20,-20),1);
        var empty=new NativeTransitionField(Set.of(),2,12);close(empty.weight(0,0),1);
        for(double x=-20;x<36;x+=.3)for(double z=-20;z<36;z+=.3){double w=single.weight(x,z);if(w<0||w>1||!Double.isFinite(w))throw new AssertionError("Invalid field");}
        System.out.println("Native collar reference: straight, merged boundaries, concave/convex corner, negative coordinates, C1 diagonal: passed (not runtime morph)");
    }
}
