package me.cortex.voxy.client.core.model.bakery;

import com.mojang.blaze3d.vertex.PoseStack;
import me.cortex.voxy.client.core.model.ModelFactory;
import me.cortex.voxy.common.util.UnsafeUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.ItemBlockRenderTypes;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.ColorResolver;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.SingleThreadedRandomSource;
import net.minecraft.world.level.lighting.LevelLightEngine;
import net.minecraft.world.level.material.FluidState;
import net.neoforged.neoforge.client.model.data.ModelData;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.lwjgl.system.MemoryUtil;

import static org.lwjgl.opengl.ARBDirectStateAccess.glGetTextureImage;
import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL11.GL_UNPACK_ALIGNMENT;
import static org.lwjgl.opengl.GL11C.GL_RGBA;
import static org.lwjgl.opengl.GL12.GL_PACK_IMAGE_HEIGHT;
import static org.lwjgl.opengl.GL15C.glBindBuffer;
import static org.lwjgl.opengl.GL21.GL_PIXEL_PACK_BUFFER;
import static org.lwjgl.opengl.GL30C.GL_FRAMEBUFFER;
import static org.lwjgl.opengl.GL30C.glBindFramebuffer;
import static org.lwjgl.opengl.GL45C.glGetTextureLevelParameteri;

public class SoftwareModelTextureBakery {
    //Note: the first bit of metadata is if alpha discard is enabled
    private static final Matrix4f[] VIEWS = new Matrix4f[6];

    private final ReuseVertexConsumer vc = new ReuseVertexConsumer();
    private final SoftwareRasterizer[] mipRasterizers = java.util.stream.IntStream.range(0, ModelFactory.MIP_LEVELS)
            .mapToObj(level -> new SoftwareRasterizer(ModelFactory.MODEL_TEXTURE_SIZE >> level))
            .toArray(SoftwareRasterizer[]::new);
    private SoftwareRasterizer rasterizer = mipRasterizers[0];
    private boolean compactLeafCube;
    private int nativeMipLevels;
    public boolean supportsNativeLeafMips() { return compactLeafCube && nativeMipLevels == mipRasterizers.length; }

    public SoftwareModelTextureBakery() {
    }

    public void setupTexture() {
        var tex = Minecraft.getInstance().getTextureManager().getTexture(ResourceLocation.fromNamespaceAndPath("minecraft", "textures/atlas/blocks.png"));
        int textureId = tex.getId();

        glFlush();
        glFinish();
        // Pixel-pack state is global. Leaking the atlas row length makes F2
        // downloadTexture write atlas-sized rows into a screen-sized native buffer.
        int packBuffer = glGetInteger(org.lwjgl.opengl.GL21.GL_PIXEL_PACK_BUFFER_BINDING);
        int[] parameters = {GL_PACK_ROW_LENGTH, GL_PACK_IMAGE_HEIGHT, GL_PACK_SKIP_ROWS,
                GL_PACK_SKIP_PIXELS, GL_PACK_ALIGNMENT, org.lwjgl.opengl.GL12.GL_PACK_SKIP_IMAGES,
                GL_PACK_SWAP_BYTES, GL_PACK_LSB_FIRST};
        int[] previous = new int[parameters.length];
        for (int i = 0; i < parameters.length; i++) previous[i] = glGetInteger(parameters[i]);
        try {
            glBindBuffer(GL_PIXEL_PACK_BUFFER, 0);
            for (int parameter : parameters) glPixelStorei(parameter, parameter == GL_PACK_ALIGNMENT ? 4 : 0);
            // Preserve the actual native mip pyramid, including Sodium's alpha-
            // weighted filtering and pack-defined mips. Rebuilding from discarded
            // cutouts/dilated RGB changes both canopy albedo and coverage.
            int[] texture = null;
            int width = 0, height = 0;
            nativeMipLevels = 0;
            for (int level = 0; level < mipRasterizers.length; level++) {
                int levelWidth = glGetTextureLevelParameteri(textureId, level, GL_TEXTURE_WIDTH);
                if (levelWidth > 0) {
                    width = levelWidth;
                    height = glGetTextureLevelParameteri(textureId, level, GL_TEXTURE_HEIGHT);
                    texture = new int[width * height];
                    glGetTextureImage(textureId, level, GL_RGBA, GL_UNSIGNED_BYTE, texture);
                    dumpNativeSpriteAudit((net.minecraft.client.renderer.texture.TextureAtlas) tex, texture, width, level);
                    nativeMipLevels = level + 1;
                }
                if (texture == null) throw new IllegalStateException("Native block atlas is empty");
                // When mipmaps are disabled, retain only one base atlas copy.
                // Such models use Vision's normal generated mip fallback below.
                mipRasterizers[level].setSamplerTexture(texture, width, height);
            }
        } finally {
            for (int i = 0; i < parameters.length; i++) glPixelStorei(parameters[i], previous[i]);
            glBindBuffer(GL_PIXEL_PACK_BUFFER, packBuffer);
        }
    }

    private void dumpNativeSpriteAudit(net.minecraft.client.renderer.texture.TextureAtlas atlas, int[] pixels, int width, int mip) {
        String directory = System.getProperty("voxy.fragment.materialAuditDirectory");
        if (directory == null) return;
        try {
            var path = java.nio.file.Path.of(directory);
            java.nio.file.Files.createDirectories(path);
            for (String name : new String[]{"birch_leaves", "oak_leaves", "dark_oak_leaves", "spruce_leaves"}) {
                var sprite = atlas.getSprite(ResourceLocation.fromNamespaceAndPath("minecraft", "block/" + name));
                int size = Math.max(1, sprite.contents().width() >> mip);
                var image = new java.awt.image.BufferedImage(size, size, java.awt.image.BufferedImage.TYPE_INT_ARGB);
                for (int y = 0; y < size; y++) for (int x = 0; x < size; x++) {
                    int abgr = pixels[(sprite.getY() >> mip) * width + (sprite.getX() >> mip) + y * width + x];
                    image.setRGB(x, y, (abgr & 0xff00ff00) | ((abgr & 255) << 16) | ((abgr >>> 16) & 255));
                }
                javax.imageio.ImageIO.write(image, "png", path.resolve("native-" + name + "-mip" + mip + ".png").toFile());
            }
        } catch (java.io.IOException error) {
            org.slf4j.LoggerFactory.getLogger("FragmentLODMaterialAudit").warn("Cannot save native atlas reference", error);
        }
    }

    public static int getMetaFromLayer(RenderType layer) {
        boolean hasDiscard = layer == RenderType.cutout() ||
                layer == RenderType.cutoutMipped() ||
                layer == RenderType.translucent()||
                layer == RenderType.tripwire();

        int meta = hasDiscard?1:0;
        meta |= true?2:0;
        return meta;
    }

    private final java.util.Set<net.minecraft.world.level.block.Block> auditedLeaves = new java.util.HashSet<>();

    static boolean isLeafCubeFace(net.minecraft.client.renderer.block.model.BakedQuad quad) {
        int[] data = quad.getVertices();
        int stride = data.length / 4;
        int axis = quad.getDirection().getAxis().ordinal(); // X,Y,Z
        float plane = quad.getDirection().getAxisDirection() == Direction.AxisDirection.POSITIVE ? 1.0f : 0.0f;
        for (int v = 0; v < 4; v++) for (int c = 0; c < 3; c++) {
            float p = Float.intBitsToFloat(data[v * stride + c]);
            if (p < -0.0001f || p > 1.0001f || c == axis && Math.abs(p - plane) > 0.0001f) return false;
        }
        return !quad.getSprite().contents().name().getPath().contains("_bushy");
    }

    private void bakeBlockModel(BlockState state, RenderType layer) {
        if (state.getRenderShape() == RenderShape.INVISIBLE) {
            return;//Dont bake if invisible
        }
        var model = Minecraft.getInstance()
                .getModelManager()
                .getBlockModelShaper()
                .getBlockModel(state);

        int meta = getMetaFromLayer(layer);

        var quads = new java.util.ArrayList<net.minecraft.client.renderer.block.model.BakedQuad>();
        for (Direction direction : new Direction[]{Direction.DOWN, Direction.UP, Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST, null}) {
            quads.addAll(model.getQuads(state, direction, new SingleThreadedRandomSource(42L), ModelData.EMPTY, layer));
        }
        boolean leaves = state.is(net.minecraft.tags.BlockTags.LEAVES);
        int baseFaces = 0;
        if (leaves) for (var quad : quads) {
            if (isLeafCubeFace(quad)) baseFaces |= 1 << quad.getDirection().get3DDataValue();
        }
        // A known full leaf cube can be reduced by geometry, even when a pack
        // reuses its base sprite for decorative cards. Unknown custom leaf
        // models retain their geometry rather than disappearing altogether.
        boolean compactCube = leaves && baseFaces == 63;
        this.compactLeafCube = compactCube;
        int kept = 0, skipped = 0;
        for (var quad : quads) {
            if (leaves && (quad.getSprite().contents().name().getPath().contains("_bushy")
                    || compactCube && !isLeafCubeFace(quad))) { skipped++; continue; }
            this.vc.quad(quad, meta|(quad.isTinted()?4:0));
            kept++;
        }
        if (Boolean.getBoolean("voxy.fragment.materialAudit") && leaves && auditedLeaves.add(state.getBlock())) {
            org.slf4j.LoggerFactory.getLogger("FragmentLODMaterialAudit").info("LOD leaf geometry "
                    + net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(state.getBlock())
                    + " compactCube=" + compactCube + " kept=" + kept + " skipped=" + skipped);
        }
    }


    private void bakeFluidState(BlockState state, RenderType layer, int face) {
        {
            //TODO: somehow set the tint flag per quad or something?
            int metadata = getMetaFromLayer(layer);
            //Just assume all fluids are tinted, if they arnt it should be implicitly culled in the model baking phase
            // since it wont have the colour provider
            metadata |= 4;//Has tint
            this.vc.setDefaultMeta(metadata);//Set the meta while baking
        }
        Minecraft.getInstance().getBlockRenderer().renderLiquid(BlockPos.ZERO, new BlockAndTintGetter() {
            @Override
            public float getShade(Direction direction, boolean shaded) {
                return 0;
            }

            @Override
            public LevelLightEngine getLightEngine() {
                return null;
            }

            @Override
            public int getBrightness(LightLayer type, BlockPos pos) {
                return 0;
            }

            @Override
            public int getBlockTint(BlockPos pos, ColorResolver colorResolver) {
                return 0;
            }

            @Nullable
            @Override
            public BlockEntity getBlockEntity(BlockPos pos) {
                return null;
            }

            @Override
            public BlockState getBlockState(BlockPos pos) {
                if (shouldReturnAirForFluid(pos, face)) {
                    return Blocks.AIR.defaultBlockState();
                }

                if (pos.getY() == 1) {
                    return Blocks.AIR.defaultBlockState();
                }
                return state;
            }

            @Override
            public FluidState getFluidState(BlockPos pos) {
                if (shouldReturnAirForFluid(pos, face)) {
                    return Blocks.AIR.defaultBlockState().getFluidState();
                }

                if (pos.getY() == 1) {
                    return Blocks.AIR.defaultBlockState().getFluidState();
                }
                return state.getFluidState();
            }

            @Override
            public int getHeight() {
                return 0;
            }

            @Override
            public int getMinBuildHeight() {
                return 0;
            }
        }, this.vc, state, state.getFluidState());
        this.vc.setDefaultMeta(0);//Reset default meta
    }

    private static boolean shouldReturnAirForFluid(BlockPos pos, int face) {
        var fv = Direction.from3DDataValue(face).getNormal();
        int dot = fv.getX()*pos.getX() + fv.getY()*pos.getY() + fv.getZ()*pos.getZ();
        return dot >= 1;
    }

    public void free() {
        this.vc.free();
    }

    //The outputBuffer layout is different from the non software rasterized ModelTextureBakery
    // in this version the values are simply appended (0,0),(1,0),(2,0),(0,1),(1,1),(2,1)

    public int renderToOutput(BlockState state, long outputBuffer) {
        return renderToOutput(state, outputBuffer, 0, false);
    }

    private int renderToOutput(BlockState state, long outputBuffer, int mip, boolean preserveTransparent) {
        this.compactLeafCube = false;
        this.rasterizer = mipRasterizers[mip];
        this.rasterizer.setPreserveTransparentTexels(preserveTransparent);
        int size = ModelFactory.MODEL_TEXTURE_SIZE >> mip;
        long faceBytes = (long) size * size * 8;
        MemoryUtil.memSet(outputBuffer, 0, faceBytes * 6);


        boolean isBlock = true;
        RenderType layer;
        if (state.getBlock() instanceof LiquidBlock) {
            layer = ItemBlockRenderTypes.getRenderLayer(state.getFluidState());
            isBlock = false;
        } else {
            layer = ItemBlockRenderTypes.getChunkRenderType(state);
        }

        //TODO: support block model entities
        //BakedBlockEntityModel bbem = null;
        if (state.hasBlockEntity()) {
            //bbem = BakedBlockEntityModel.bake(state);
        }

        {
            this.rasterizer.setBlending(layer == RenderType.translucent());

            //var tex = Minecraft.getInstance().getTextureManager().getTexture(ResourceLocation.fromNamespaceAndPath("minecraft", "textures/atlas/blocks.png")).getTexture();
            //blockTextureId = ((com.mojang.blaze3d.opengl.GlTexture)tex).glId();
        }

        boolean isAnyShaded = false;
        boolean isAnyDarkend = false;
        if (isBlock) {
            this.vc.reset();
            this.bakeBlockModel(state, layer);
            isAnyShaded |= this.vc.anyShaded;
            isAnyDarkend |= this.vc.anyDarkendTex;
            if (!this.vc.isEmpty()) {//only render if there... is shit to render
                for (int i = 0; i < VIEWS.length; i++) {
                    this.rasterizer.setFaceCull(i==1||i==2||i==4);
                    this.rasterizer.clear();

                    this.rasterizer.raster(VIEWS[i], this.vc);
                    UnsafeUtil.memcpy(this.rasterizer.getRawFramebuffer(), outputBuffer+(faceBytes*i));
                }
            }
        } else {//Is fluid, slow path :(

            if (!(state.getBlock() instanceof LiquidBlock)) throw new IllegalStateException();
            for (int i = 0; i < VIEWS.length; i++) {
                this.vc.reset();
                this.bakeFluidState(state, layer, i);
                if (this.vc.isEmpty()) continue;
                isAnyShaded |= this.vc.anyShaded;
                isAnyDarkend |= this.vc.anyDarkendTex;

                this.rasterizer.setFaceCull(i==1||i==2||i==4);
                this.rasterizer.clear();

                //The projection matrix
                this.rasterizer.raster(VIEWS[i], this.vc);
                UnsafeUtil.memcpy(this.rasterizer.getRawFramebuffer(), outputBuffer+(faceBytes*i));
            }
        }


        return (isAnyShaded?1:0)|(isAnyDarkend?2:0);
    }




    /** Bake the same atlas mip pyramid as native chunks; metadata keeps the normal cutout bake. */
    public void putNativeMipTextures(BlockState state, long scratch, me.cortex.voxy.common.util.MemoryBuffer into) {
        long target = into.address;
        for (int mip = 0; mip < mipRasterizers.length; mip++) {
            int size = ModelFactory.MODEL_TEXTURE_SIZE >> mip;
            renderToOutput(state, scratch, mip, true);
            for (int face = 0; face < 6; face++) {
                for (int y = 0; y < size; y++) for (int x = 0; x < size; x++) {
                    int colour = MemoryUtil.memGetInt(scratch + ((long)face * size * size + y * size + x) * 8);
                    int offset = (((face & 1) * size + y) * size * 3 + (face >> 1) * size + x) * 4;
                    MemoryUtil.memPutInt(target + offset, colour);
                }
            }
            target += (long) size * size * 6 * 4;
        }
        this.rasterizer = mipRasterizers[0];
        this.rasterizer.setPreserveTransparentTexels(false);
        dumpMipAudit(state, into, "");
    }

    private final java.util.Set<String> auditedMipLeaves = new java.util.HashSet<>();
    public void dumpMipAudit(BlockState state, me.cortex.voxy.common.util.MemoryBuffer data, String prefix) {
        String directory = System.getProperty("voxy.fragment.materialAuditDirectory");
        if (directory == null || !auditedMipLeaves.add(prefix + net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(state.getBlock()))) return;
        try {
            var path = java.nio.file.Path.of(directory);
            java.nio.file.Files.createDirectories(path);
            String name = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(state.getBlock()).getPath();
            long ptr = data.address;
            for (int mip = 0; mip < mipRasterizers.length; mip++) {
                int size = ModelFactory.MODEL_TEXTURE_SIZE >> mip;
                var image = new java.awt.image.BufferedImage(size * 3, size * 2, java.awt.image.BufferedImage.TYPE_INT_ARGB);
                for (int y = 0; y < size * 2; y++) for (int x = 0; x < size * 3; x++) {
                    int abgr = MemoryUtil.memGetInt(ptr + (y * size * 3L + x) * 4);
                    image.setRGB(x, y, (abgr & 0xff00ff00) | ((abgr & 255) << 16) | ((abgr >>> 16) & 255));
                }
                javax.imageio.ImageIO.write(image, "png", path.resolve(prefix + name + "-mip" + mip + ".png").toFile());
                ptr += (long) size * size * 6 * 4;
            }
        } catch (java.io.IOException error) {
            org.slf4j.LoggerFactory.getLogger("FragmentLODMaterialAudit").warn("Cannot save mip audit", error);
        }
    }

    static {
        //the face/direction is the face (e.g. down is the down face)
        addView(0, -90,0, 0, 0);//Direction.DOWN
        addView(1, 90,0, 0, 0b100);//Direction.UP

        addView(2, 0,180, 0, 0b001);//Direction.NORTH
        addView(3, 0,0, 0, 0);//Direction.SOUTH

        addView(4, 0,90, 270, 0b100);//Direction.WEST
        addView(5, 0,270, 270, 0);//Direction.EAST
    }

    private static void addView(int i, float pitch, float yaw, float rotation, int flip) {
        var stack = new PoseStack();
        stack.translate(0.5f,0.5f,0.5f);
        stack.mulPose(makeQuatFromAxisExact(new Vector3f(0,0,1), rotation));
        stack.mulPose(makeQuatFromAxisExact(new Vector3f(1,0,0), pitch));
        stack.mulPose(makeQuatFromAxisExact(new Vector3f(0,1,0), yaw));
        stack.mulPose(new Matrix4f().scale(1-2*(flip&1), 1-(flip&2), 1-((flip>>1)&2)));
        stack.translate(-0.5f,-0.5f,-0.5f);
        var mat = new Matrix4f(stack.last().pose());

        mat = new Matrix4f().set(
                        2,0,0,0,
                        0,2,0,0,
                        0,0,-2,0,
                        -1,-1,1,1)
                .mul(mat);
        VIEWS[i] = mat;
    }

    private static Quaternionf makeQuatFromAxisExact(Vector3f vec, float angle) {
        angle = (float) Math.toRadians(angle);
        float hangle = angle / 2.0f;
        float sinAngle = (float) Math.sin(hangle);
        float invVLength = (float) (1/Math.sqrt(vec.lengthSquared()));
        return new Quaternionf(vec.x * invVLength * sinAngle,
                vec.y * invVLength * sinAngle,
                vec.z * invVLength * sinAngle,
                Math.cos(hangle));
    }
}
