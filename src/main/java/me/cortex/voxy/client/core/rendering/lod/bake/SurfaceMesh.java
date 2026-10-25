package me.cortex.voxy.client.core.rendering.lod.bake;

/** Independent compact level: no retained voxel faces, unused vertices or morph streams.
 * Coordinates are relative to the snapshot origin, never large-world floats.
 * faceData = paletteIndex << 11 | originalFace << 8 | savedNeighbourLight.
 */
public record SurfaceMesh(float[] positions, float[] normals, int[] indices, int[] faceData, boolean[] locked) {
    public SurfaceMesh {
        if (positions.length % 3 != 0 || positions.length != normals.length || indices.length % 3 != 0
                || faceData.length != indices.length / 3 || locked.length != positions.length / 3)
            throw new IllegalArgumentException("Invalid mesh streams");
        for (float value : positions) if (!Float.isFinite(value)) throw new IllegalArgumentException("Non-finite position");
        for (float value : normals) if (!Float.isFinite(value)) throw new IllegalArgumentException("Non-finite normal");
        for (int index : indices) if (index < 0 || index >= locked.length) throw new IllegalArgumentException("Invalid mesh index");
    }
    public int vertices() { return positions.length / 3; }
    public int triangles() { return indices.length / 3; }
    /** v3 payload including the face-attribute dictionary; page header is separate. */
    public long packedBytes() { return MeshPage.payloadBytes(this); }
    /** Actual buffers allocated by BakedMeshBackend, excluding shared material table. */
    public long gpuBytes() {
        return vertices()*8L + triangles()*4L + indices.length*(vertices()<=65536?2L:4L);
    }
    public int attributeCount(){var values=new java.util.HashSet<Integer>();for(int data:faceData)values.add(data);return values.size();}
}
