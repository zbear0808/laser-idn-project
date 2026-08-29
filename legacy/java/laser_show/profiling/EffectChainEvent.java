package laser_show.profiling;

import jdk.jfr.*;

/**
 * JFR event for tracking effect chain application timing.
 * 
 * Records the time taken to apply all effects in a chain to a frame,
 * useful for identifying slow effects or excessive effect counts.
 */
@Name("laser_show.EffectChain")
@Label("Effect Chain Application")
@Category({"Laser Show", "Effects"})
@Description("Timing for applying an effect chain to a frame")
@StackTrace(false)
public class EffectChainEvent extends Event {
    
    @Label("Duration")
    @Description("Time to apply all effects in the chain (microseconds)")
    @Timespan(Timespan.MICROSECONDS)
    public long durationUs;
    
    @Label("Effect Count")
    @Description("Number of effects in the chain")
    public int effectCount;
    
    @Label("Points Processed")
    @Description("Number of points transformed by the effect chain")
    public int pointCount;
    
    /**
     * Create and commit an effect chain event.
     * 
     * @param durationUs Time to apply all effects (microseconds)
     * @param effectCount Number of effects applied
     * @param pointCount Number of points processed
     */
    public static void emit(long durationUs, int effectCount, int pointCount) {
        EffectChainEvent event = new EffectChainEvent();
        event.durationUs = durationUs;
        event.effectCount = effectCount;
        event.pointCount = pointCount;
        event.commit();
    }
}
