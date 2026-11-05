package ca.spottedleaf.starlight.common.blockstate;

public interface ExtendedAbstractBlockState {

    public boolean isConditionallyFullOpaque();

    public int getOpacityIfCached();

    // Solix (0.3.4) - unused: nothing called this (the light paths only use getOpacityIfCached() == -1, which already
    // covers position-aware emission and is also safe before ModernFix builds a state's cache lazily)
    // /**
    //  * Solix: whether the block overrides Forge's positional light emission, see {@link PositionalEmission}.
    //  * Only valid once the block state cache has been initialised; callers on the light path must treat
    //  * an uninitialised state through the slow path (opacity -1) anyway.
    //  */
    // public boolean isPositionAwareEmission();

}
