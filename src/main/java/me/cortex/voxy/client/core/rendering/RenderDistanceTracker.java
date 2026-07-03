package me.cortex.voxy.client.core.rendering;

import me.cortex.voxy.client.core.util.RingTracker;
import me.cortex.voxy.common.world.WorldEngine;

import java.util.function.LongConsumer;

public class RenderDistanceTracker {
    private static final int CHECK_DISTANCE_BLOCKS = 128;
    private static final int TOP_LEVEL = WorldEngine.MAX_LOD_LAYER;
    private static final int TOP_LEVEL_SCALE = 1 << TOP_LEVEL;
    private final LongConsumer addTopLevelNode;
    private final LongConsumer removeTopLevelNode;
    private final int processRate;
    private final int minSec;
    private final int maxSec;
    private RingTracker tracker;
    private int renderDistance;
    private double posX;
    private double posZ;
    public RenderDistanceTracker(int rate, int minSec, int maxSec, LongConsumer addTopLevelNode, LongConsumer removeTopLevelNode) {
        this.addTopLevelNode = addTopLevelNode;
        this.removeTopLevelNode = removeTopLevelNode;
        this.renderDistance = 2;
        this.tracker = new RingTracker(this.renderDistance, 0, 0, true);
        this.processRate = rate;
        this.minSec = Math.floorDiv(minSec, TOP_LEVEL_SCALE);
        this.maxSec = Math.floorDiv(maxSec, TOP_LEVEL_SCALE);
    }

    public void setRenderDistance(int renderDistance) {
        if (renderDistance == this.renderDistance) {
            return;
        }
        this.renderDistance = renderDistance;
        this.tracker.unload();//Mark all as unload
        this.tracker = new RingTracker(this.tracker, renderDistance, ((int)this.posX)>>9, ((int)this.posZ)>>9, true);//Steal from previous tracker
    }

    public boolean setCenterAndProcess(double x, double z) {
        double dx = this.posX-x;
        double dz = this.posZ-z;
        if (CHECK_DISTANCE_BLOCKS*CHECK_DISTANCE_BLOCKS<dx*dx+dz*dz) {
            this.posX = x;
            this.posZ = z;
            this.tracker.moveCenter(((int)x)>>9, ((int)z)>>9);
        }

        //TODO: make process rate in terms of updatesPerSecond not updates per frame
        return this.tracker.process(this.processRate, this::add, this::rem)!=0;
    }

    private void add(int x, int z) {
        for (int y = this.minSec; y <= this.maxSec; y++) {
            this.addTopLevelNode.accept(WorldEngine.getWorldSectionId(TOP_LEVEL, x, y, z));
        }
    }

    private void rem(int x, int z) {
        for (int y = this.minSec; y <= this.maxSec; y++) {
            this.removeTopLevelNode.accept(WorldEngine.getWorldSectionId(TOP_LEVEL, x, y, z));
        }
    }
}
