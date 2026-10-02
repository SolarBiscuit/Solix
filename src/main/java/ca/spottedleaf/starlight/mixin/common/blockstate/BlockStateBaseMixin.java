package ca.spottedleaf.starlight.mixin.common.blockstate;

import ca.spottedleaf.starlight.common.blockstate.ExtendedAbstractBlockState;
import ca.spottedleaf.starlight.common.blockstate.PositionalEmission;
import com.google.common.collect.ImmutableMap;
import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateHolder;
import net.minecraft.world.level.block.state.properties.Property;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(BlockBehaviour.BlockStateBase.class)
public abstract class BlockStateBaseMixin extends StateHolder<Block, BlockState> implements ExtendedAbstractBlockState {

    @Shadow
    @Final
    private boolean useShapeForLightOcclusion;

    @Shadow
    @Final
    private boolean canOcclude;

    @Shadow
    protected BlockBehaviour.BlockStateBase.Cache cache;

    @Shadow
    public abstract Block getBlock();

    @Unique
    private int opacityIfCached = -1; // Solix - start at "not cached": ModernFix may build the cache lazily

    @Unique
    private boolean isPositionAwareEmission; // Solix

    protected BlockStateBaseMixin(final Block object, final ImmutableMap<Property<?>, Comparable<?>> immutableMap, final MapCodec<BlockState> mapCodec) {
        super(object, immutableMap, mapCodec);
    }

    /**
     * Initialises our light state for this block.
     */
    @Inject(
            method = "initCache",
            at = @At("RETURN")
    )
    public void initLightAccessState(final CallbackInfo ci) {
        // Solix start - Forge-correct opacity cache
        // Starlight Forge 1.1.2 always forced -1 here. The cached opacity is only unsafe when getLightBlock depends on the
        // position (dynamic shape: no vanilla cache) or when the cached fast paths would use the position-less light
        // emission for a block that overrides Forge's positional getLightEmission. In every other case
        // getLightBlock(level, pos) returns cache.lightBlock (computed by vanilla at EmptyBlockGetter/BlockPos.ZERO).
        this.isPositionAwareEmission = PositionalEmission.isPositionAware(this.getBlock().getClass());
        this.opacityIfCached = this.cache == null || this.getBlock().hasDynamicShape() || this.isConditionallyFullOpaque()
                || this.isPositionAwareEmission ? -1 : this.cache.lightBlock;
        // Solix end - Forge-correct opacity cache
    }

    @Override
    public final boolean isConditionallyFullOpaque() {
        // Solix - both fields are final and set in the constructor, so this does not depend on initCache having run
        // (ModernFix builds block state caches lazily, and the cached field would read false until then)
        return this.canOcclude & this.useShapeForLightOcclusion;
    }

    @Override
    public final int getOpacityIfCached() {
        return this.opacityIfCached;
    }

    @Override
    public final boolean isPositionAwareEmission() {
        return this.isPositionAwareEmission;
    }
}
