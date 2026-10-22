package me.cortex.voxy.client.core.model;

import me.cortex.voxy.client.core.RenderResourceReuse;
import me.cortex.voxy.client.core.gl.GlBuffer;
import me.cortex.voxy.client.core.gl.GlTexture;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.resources.ResourceLocation;

import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL11C.GL_NEAREST;
import static org.lwjgl.opengl.GL11C.GL_NEAREST_MIPMAP_NEAREST;
import static org.lwjgl.opengl.GL12C.GL_TEXTURE_MAX_LOD;
import static org.lwjgl.opengl.GL12C.GL_TEXTURE_MIN_LOD;
import static org.lwjgl.opengl.GL30.glBindBufferBase;
import static org.lwjgl.opengl.GL33.*;
import static org.lwjgl.opengl.GL33C.glSamplerParameteri;
import static org.lwjgl.opengl.GL43.GL_SHADER_STORAGE_BUFFER;
import static org.lwjgl.opengl.GL45.glBindTextureUnit;

public class ModelStore {
    public static final int MODEL_SIZE = 64;
    final GlBuffer modelBuffer;
    final GlBuffer modelColourBuffer;
    final GlTexture textures;
    public final int blockSampler = glGenSamplers();
    private final int leafSampler = glGenSamplers();

    public ModelStore() {
        this.modelBuffer = new GlBuffer(MODEL_SIZE * (1<<16)).name("ModelData");
        this.modelColourBuffer = new GlBuffer(4 * (1<<16)).name("ModelColour");
        this.textures = RenderResourceReuse.getOrCreateModelStoreTextureAtlas();

        //Limit the mips of the texture to match that of the terrain atlas
        int mipLvl = ((TextureAtlas) Minecraft.getInstance().getTextureManager()
                .getTexture(ResourceLocation.fromNamespaceAndPath("minecraft", "textures/atlas/blocks.png")))
                .mipLevel;

        mipLvl = Math.min(mipLvl, Math.max(0, ModelFactory.LAYERS - 2));

        glSamplerParameteri(this.blockSampler, GL_TEXTURE_MIN_FILTER, GL_NEAREST_MIPMAP_NEAREST);
        glSamplerParameteri(this.blockSampler, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
        glSamplerParameteri(this.blockSampler, GL_TEXTURE_MIN_LOD, 0);
        glSamplerParameteri(this.blockSampler, GL_TEXTURE_MAX_LOD, mipLvl);//Integer.numberOfTrailingZeros(ModelFactory.MODEL_TEXTURE_SIZE)
        glSamplerParameteri(this.leafSampler, GL_TEXTURE_MIN_FILTER, GL_LINEAR_MIPMAP_LINEAR);
        glSamplerParameteri(this.leafSampler, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
        glSamplerParameteri(this.leafSampler, GL_TEXTURE_MIN_LOD, 0);
        glSamplerParameteri(this.leafSampler, GL_TEXTURE_MAX_LOD, ModelFactory.LAYERS);
    }


    public void free() {
        this.modelBuffer.free();
        this.modelColourBuffer.free();
        RenderResourceReuse.giveBackModelStoreTextureAtlas(this.textures);
        glDeleteSamplers(this.blockSampler);
        glDeleteSamplers(this.leafSampler);
    }

    public LeafAtlasBinding filteredLeaves() { return new LeafAtlasBinding(); }
    /** Separate filtered view; ordinary blocks retain their pixel-art sampler. */
    public final class LeafAtlasBinding implements AutoCloseable {
        private final int texture, sampler;
        private LeafAtlasBinding() {
            int active = glGetInteger(org.lwjgl.opengl.GL13.GL_ACTIVE_TEXTURE);
            org.lwjgl.opengl.GL13.glActiveTexture(org.lwjgl.opengl.GL13.GL_TEXTURE0 + 11);
            texture = glGetInteger(GL_TEXTURE_BINDING_2D);
            sampler = org.lwjgl.opengl.GL30.glGetIntegeri(GL_SAMPLER_BINDING, 11);
            glBindTexture(GL_TEXTURE_2D, textures.id);
            glBindSampler(11, leafSampler);
            org.lwjgl.opengl.GL13.glActiveTexture(active);
        }
        @Override public void close() {
            int active = glGetInteger(org.lwjgl.opengl.GL13.GL_ACTIVE_TEXTURE);
            org.lwjgl.opengl.GL13.glActiveTexture(org.lwjgl.opengl.GL13.GL_TEXTURE0 + 11);
            glBindTexture(GL_TEXTURE_2D, texture);
            glBindSampler(11, sampler);
            org.lwjgl.opengl.GL13.glActiveTexture(active);
        }
    }


    public void bind(int modelBindingIndex, int colourBindingIndex, int textureBindingIndex) {
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER, modelBindingIndex, this.modelBuffer.id);
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER, colourBindingIndex, this.modelColourBuffer.id);
        glBindTextureUnit(textureBindingIndex, this.textures.id);
        glBindSampler(textureBindingIndex, this.blockSampler);
    }
}
