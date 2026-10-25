package me.cortex.voxy.client.core.rendering.lod.bake;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.GZIPInputStream;

/** CPU-only immutable-input format. Palette keys are saved block states and biome names. */
public record VoxelSnapshot(int originX, int originY, int originZ, int sizeX, int sizeY, int sizeZ,
                            String[] states, String[] biomes, int[] blocks, byte[] light, byte[] natural) {
    private static final int MAX_CELLS = 8 * 1024 * 1024;

    public VoxelSnapshot {
        long cells = (long) sizeX * sizeY * sizeZ;
        if (sizeX < 3 || sizeZ < 3 || sizeY < 3 || sizeX > 144 || sizeZ > 144 || sizeY > 512
                || (long) originX + sizeX > Integer.MAX_VALUE || (long) originY + sizeY > Integer.MAX_VALUE
                || (long) originZ + sizeZ > Integer.MAX_VALUE
                || cells > MAX_CELLS || blocks.length != cells || light.length != cells || natural.length != cells
                || states.length != biomes.length || states.length < 1 || states.length > 65536
                || !states[0].equals("minecraft:air")) {
            throw new IllegalArgumentException("Invalid proof snapshot dimensions/palette");
        }
        for (int i = 0; i < blocks.length; i++) {
            if (blocks[i] < -1 || blocks[i] >= states.length || (natural[i] != 0 && natural[i] != 1)
                    || (natural[i] != 0 && blocks[i] <= 0)) {
                throw new IllegalArgumentException("Invalid voxel/palette/mask value");
            }
        }
    }

    public int index(int x, int y, int z) { return (y * sizeZ + z) * sizeX + x; }
    public int block(int x, int y, int z) {
        return x < 0 || y < 0 || z < 0 || x >= sizeX || y >= sizeY || z >= sizeZ ? -1 : blocks[index(x,y,z)];
    }

    public static VoxelSnapshot read(Path path) throws IOException {
        try (var in = new DataInputStream(new BufferedInputStream(new GZIPInputStream(Files.newInputStream(path))))) {
            if (!new String(in.readNBytes(8), StandardCharsets.US_ASCII).equals("FVOL0001"))
                throw new IOException("Unsupported snapshot format");
            int ox = in.readInt(), oy = in.readInt(), oz = in.readInt();
            int sx = in.readInt(), sy = in.readInt(), sz = in.readInt(), count = in.readInt();
            long cells = (long) sx * sy * sz;
            if (sx < 3 || sz < 3 || sy < 3 || sx > 144 || sz > 144 || sy > 512 || cells > MAX_CELLS
                    || count < 1 || count > 65536) throw new IOException("Snapshot size/palette limit");
            String[] states = new String[count], biomes = new String[count];
            for (int i = 0; i < count; i++) { states[i] = string(in); biomes[i] = string(in); }
            int[] blocks = new int[(int)cells];
            for (int i = 0; i < blocks.length; i++) blocks[i] = in.readInt();
            byte[] light = in.readNBytes(blocks.length), natural = in.readNBytes(blocks.length);
            if (in.read() != -1) throw new IOException("Trailing snapshot payload");
            try { return new VoxelSnapshot(ox,oy,oz,sx,sy,sz,states,biomes,blocks,light,natural); }
            catch (IllegalArgumentException e) { throw new IOException(e.getMessage(),e); }
        }
    }

    private static String string(DataInputStream in) throws IOException {
        int length = in.readInt();
        if (length < 1 || length > 4096) throw new IOException("Palette string limit");
        byte[] bytes = in.readNBytes(length);
        if (bytes.length != length) throw new EOFException("Truncated palette string");
        return new String(bytes, StandardCharsets.UTF_8);
    }
}
