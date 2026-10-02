package ca.spottedleaf.starlight.common.blockstate;

public interface ExtendedAbstractBlockState {

    public boolean isConditionallyFullOpaque();

    public int getOpacityIfCached();

    /**
     * Solix: whether the block overrides Forge's positional light emission, see {@link PositionalEmission}.
     * Only valid once the block state cache has been initialised; callers on the light path must treat
     * an uninitialised state through the slow path (opacity -1) anyway.
     */
    public boolean isPositionAwareEmission();

}
