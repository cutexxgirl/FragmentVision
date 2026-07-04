package me.cortex.voxy.commonImpl.mixin.chunky;

import me.cortex.voxy.common.world.service.VoxelIngestService;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.concurrent.CompletableFuture;

@Pseudo
@Mixin(targets = "org.popcraft.chunky.platform.NeoForgeWorld", remap = false)
public abstract class MixinNeoForgeWorld {
    @Shadow
    @Final
    private ServerLevel world;

    @Inject(method = "getChunkAtAsync", at = @At("RETURN"), remap = false)
    private void voxy$captureGeneratedChunk(int chunkX, int chunkZ, CallbackInfoReturnable<CompletableFuture<Void>> cir) {
        var future = cir.getReturnValue();
        if (future == null) {
            return;
        }

        future.thenRunAsync(() -> {
            var chunk = this.world.getChunkSource().getChunk(chunkX, chunkZ, ChunkStatus.FULL, false);
            if (chunk instanceof LevelChunk levelChunk) {
                VoxelIngestService.tryAutoIngestChunk(levelChunk);
            }
        }, this.world.getServer());
    }
}
