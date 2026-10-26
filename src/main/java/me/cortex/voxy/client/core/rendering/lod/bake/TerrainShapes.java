package me.cortex.voxy.client.core.rendering.lod.bake;
/** Intrinsic snow-layer heights, in eighths. Other retained models keep the existing cube ABI. */
public final class TerrainShapes {
 public static boolean snow(String state){return LodInputPolicy.name(state).equals("minecraft:snow");}
 public static int height(String state){if(!snow(state))return 8;int at=state.indexOf("layers=");if(at<0)throw new IllegalArgumentException("Snow layer property missing");int value=state.charAt(at+7)-'0';if(value<1||value>8)throw new IllegalArgumentException("Snow layers");return value;}
 private TerrainShapes(){}
}
